package forge.ai;

import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterType;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** v52 "cheat a bomb" family: Dark Depths + Thespian's Stage (D3), Show and
 * Tell (D1) and Through the Breach (D2), plus the own-turn veto (D4) that the
 * two shared ability-AI hooks call.
 *
 * <p>Designed in {@code 2026-09-12-bomb-lines-diagnosis/design-v52-bomb-lines.md}
 * against receipts from that run's probe-1/probe-2. Every gate below reads only
 * our own hand/battlefield/graveyard and PUBLIC battlefield, graveyard, exile,
 * command and stack zones ({@link ZoneType#STATIC_ABILITIES_SOURCE_ZONES}
 * deliberately excludes Hand). No opponent hand, no library content or order.
 * Every action is a single native activation or cast proposed through
 * {@link CubeComboPlayerController}; costs, targets, legality, the legend rule
 * and every trigger stay native. This is not a combo solver and the token
 * budget/threshold constants are heuristics, not proofs.</p>
 *
 * <p><b>Recognition is by shape, never by card name.</b> "Sneak Attack" and
 * "Through the Breach" are {@link #cheatInShape}; "Thespian's Stage" is
 * {@link #landCloneShape}; "Show and Tell" is {@link #showAndTellShape}; "Dark
 * Depths" is {@link #zeroCounterPayoffType}. The hosers are recognised the same
 * way: Containment Priest by its uncast-entry replacement, Karakas by its
 * legendary-bounce activated ability, Wasteland/Strip Mine by their
 * sacrifice-to-destroy-a-land ability, and Ensnaring Bridge / Moat / Propaganda
 * not at all - those go through native {@code StaticAbilityCantAttackBlock}.</p>
 *
 * <p><b>v55 conversion gates.</b> The v52 policy reached these lines but did not
 * convert them: over panel {@code 2026-09-12-bomb2-opening-panel-v52} the
 * Through the Breach line fired nine times and ended the game twice, and the
 * Depths route fired twice, both on turn 11
 * ({@code 2026-09-12-bomb2-conversion-diagnosis/diagnosis.md} SS4). Two gates
 * are added here, both reading only own-visible cards plus the opponent's
 * PUBLIC battlefield, life and poison counters:
 * {@link #attackEndsGame} (design R2/R3 - the Breach's payload leaves at end of
 * turn, so a Breach that does not end the game spends two cards for one hit)
 * and {@link #depthsLandAction} (design R6 - spend the land drops on the two
 * halves of the Depths route before any other land). The design's R3 payload
 * SELECTION and R4 are deliberately absent: see {@link #anyLethalPayload}.</p> */
public final class CubeBombPlan {
    /** A creature worth cheating in. Emrakul, the Aeons Torn evaluates at about
     * 1020; 400 admits Griselbrand, Ulamog and Archon of Cruelty and excludes
     * ordinary beaters. CMC >= 8 is the brief's own bomb threshold and is the
     * alternative for a non-creature permanent. */
    private static final int BOMB_EVALUATION = 400, BOMB_CMC = 8;
    /** Per-turn action cap, matching the other plans' bounded-action discipline. */
    private static final int ACTION_CAP = 4;
    private static final Pattern ZERO_COUNTER = Pattern.compile("Card\\.Self\\+counters_EQ0_(\\w+)");

    /** Poison counters that kill a player, for the infect forecast. Forge's own
     * state-based action uses the same number. */
    private static final int LETHAL_POISON = 10;

    private final Player player;
    private SpellAbility selected;
    private int turn = -1, actions, failedTurn = -1;
    /** The last decline line printed, as turn/phase/reason, so a decline that
     * repeats across the many priority passes of one phase prints once. */
    private String declined = "";

    public CubeBombPlan(Player player) { this.player = player; }

    // ---------------------------------------------------------------- shapes

    /** Sneak Attack's ability and Through the Breach's spell: put a creature
     * from our own hand onto the battlefield with haste, before combat. */
    private static boolean cheatInShape(SpellAbility sa) {
        return sa.getApi() == ApiType.ChangeZone
                && "BeforeCombat".equals(sa.getParam("AILogic"))
                && "Hand".equals(sa.getParam("Origin"))
                && "Battlefield".equals(sa.getParam("Destination"))
                && sa.getParamOrDefault("ChangeType", "").startsWith("Creature")
                && !sa.usesTargeting();
    }

    /** Thespian's Stage's ability: become a copy of a target land and keep this
     * ability. {@code GainThisAbility$ True} is what makes the copy repeatable
     * and is the half of the shape that excludes ordinary clones. */
    private static boolean landCloneShape(SpellAbility sa) {
        return sa.getApi() == ApiType.Clone && sa.isActivatedAbility() && sa.usesTargeting()
                && "Land".equals(sa.getParam("ValidTgts"))
                && "True".equals(sa.getParam("GainThisAbility"));
    }

    /** Show and Tell: every player may put a permanent from their own hand onto
     * the battlefield. {@code DefinedPlayer$ Player} is precisely the parameter
     * whose empty-origin early return makes Default refuse the spell. */
    private static boolean showAndTellShape(SpellAbility sa) {
        return sa.isSpell() && sa.getApi() == ApiType.ChangeZone && !sa.usesTargeting()
                && "Hand".equals(sa.getParam("Origin"))
                && "Battlefield".equals(sa.getParam("Destination"))
                && "Player".equals(sa.getParam("DefinedPlayer"));
    }

    /** Dark Depths' shape: a permanent whose own {@code Mode$ Always} trigger
     * pays off when a named counter reaches zero. Returns that counter type, or
     * null. This is the entire reason the {@code {3}} ability is worth
     * suppressing: on such a card, spending mana that does not reach zero this
     * turn buys nothing at all. */
    private static String zeroCounterPayoffType(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.Always) continue;
            Matcher match = ZERO_COUNTER.matcher(trigger.getParamOrDefault("IsPresent", ""));
            if (match.matches()) return match.group(1);
        }
        return null;
    }

    // --------------------------------------------------------------- hazards

    /** Containment Priest: a public permanent replacing a nontoken creature
     * that enters without being cast. Every line in this class puts a creature
     * onto the battlefield without casting it, so this is a hard decline for
     * D1, D2 and D4 - and correctly IRRELEVANT to D3, whose payoff is a token,
     * which is why D3 never calls it. */
    private static boolean exiledOnUncastEntry(Player player) {
        for (Card card : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (var replacement : card.getReplacementEffects()) {
                if (replacement.getMode() != forge.game.replacement.ReplacementType.Moved
                        || !"Battlefield".equals(replacement.getParam("Destination"))
                        || !replacement.getParamOrDefault("ValidCard", "").contains("!wasCast")) continue;
                return true;
            }
        }
        return false;
    }

    /** Karakas: an opponent's untapped permanent with an activated ability that
     * returns a legendary creature from the battlefield to its owner's hand.
     * For D3 this is the sharpest guard in the design - the payoff is a
     * legendary creature TOKEN, so the bounce destroys it outright. */
    private static boolean legendaryBounceVisible(Player player) {
        for (Player opponent : player.getOpponents())
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) {
                if (card.isFaceDown() || card.isTapped()) continue;
                for (SpellAbility ability : card.getSpellAbilities()) {
                    if (!ability.isActivatedAbility() || ability.getApi() != ApiType.ChangeZone) continue;
                    if (!"Battlefield".equals(ability.getParam("Origin"))
                            || !"Hand".equals(ability.getParam("Destination"))) continue;
                    if (ability.getParamOrDefault("ValidTgts", "").contains("Legendary")) return true;
                }
            }
        return false;
    }

    /** Wasteland / Strip Mine: an opponent's untapped land that sacrifices
     * itself to destroy a land. Only consulted by D3, and a HARD decline.
     *
     * <p>The design proposed a soft version - decline only when we would also
     * be tapping out. The first {@code depths-stage:wasteland} run of this
     * policy refuted that: with four Forests we are nowhere near tapping out,
     * the plan activated, and the opponent answered by destroying the TARGET
     * (probe-3 trace, {@code step=2 source=Wasteland api=Destroy} then
     * {@code step=3 depthsOnBf=0}). The clone fizzled and Dark Depths was gone
     * for {@code {2}} - strictly worse than not acting. Whether we tap out is
     * not the question; whether the answer is up is. Tightened to the brief's
     * own MUST-NOT-MOVE wording on that evidence.</p> */
    private static boolean landDestructionVisible(Player player) {
        for (Player opponent : player.getOpponents())
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) {
                if (card.isFaceDown() || card.isTapped() || !card.isLand()) continue;
                for (SpellAbility ability : card.getSpellAbilities()) {
                    if (!ability.isActivatedAbility() || ability.getApi() != ApiType.Destroy
                            || ability.getPayCosts() == null || !ability.getPayCosts().hasTapCost()) continue;
                    if (ability.getParamOrDefault("ValidTgts", "").startsWith("Land")) return true;
                }
            }
        return false;
    }

    /** Can this card, once it is on our battlefield, actually attack an
     * opponent for value? Answered by the native rules, not by card names:
     * every public static ability is asked through
     * {@link StaticAbilityCantAttackBlock#applyCantAttackAbility} (Ensnaring
     * Bridge, Moat) and through {@link StaticAbility#getAttackCost} (a
     * Propaganda-style tax, declined when it exceeds the mana this line would
     * leave us). A creature we cheat in and cannot attack with is pure card
     * loss, which is the whole reason D2 and D4 gate on it. */
    private static boolean canAttackForValue(Player player, Card card, int manaLeft) {
        if (!card.isCreature() || card.getNetPower() <= 0) return false;
        Card prospective = CardCopyService.getLKICopy(card);
        prospective.setLastKnownZone(player.getZone(ZoneType.Battlefield));
        for (Player opponent : player.getOpponents()) {
            if (attackable(player, prospective, opponent, manaLeft)) return true;
        }
        return false;
    }

    private static boolean attackable(Player player, Card prospective, GameEntity defender, int manaLeft) {
        for (Card source : player.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (source.isFaceDown()) continue;
            for (StaticAbility st : source.getStaticAbilities()) {
                if (st.checkConditions(StaticAbilityMode.CantAttack)
                        && StaticAbilityCantAttackBlock.applyCantAttackAbility(st, prospective, defender)) return false;
                Cost cost = st.getAttackCost(prospective, defender, java.util.List.of());
                if (cost != null && cost.getTotalMana().getCMC() > manaLeft) return false;
            }
        }
        return true;
    }

    /** Our own untapped mana SOURCES plus our own floating mana. Deliberately
     * not {@link CubeComboAi#ownVisibleMana}, which counts every untapped land:
     * Dark Depths is a land that produces no mana, so counting it would
     * overstate what a Depths board can actually pay. */
    private static int ownMana(Player player) {
        int sources = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.isUntapped()) continue;
            for (SpellAbility ability : card.getManaAbilities()) {
                if (ability.getPayCosts() != null && ability.getPayCosts().getTotalMana().getCMC() == 0) { sources++; break; }
            }
        }
        return sources + player.getManaPool().totalMana();
    }

    /** What this line would leave us, for the attack-tax gate only: our own mana
     * sources minus this ability's mana cost. A forecast; real payment stays
     * native. */
    private static int manaLeftAfter(Player player, SpellAbility ability) {
        Cost cost = ability.getPayCosts();
        return ownMana(player) - (cost == null ? 0 : cost.getTotalMana().getCMC());
    }

    /** The best card in our own hand this cheat-in could put onto the
     * battlefield. Deliberately the same "best" the native hidden-origin
     * chooser will use at resolution, so the gate cannot promise a body that
     * Forge then declines to pick. */
    private Card bestBomb(SpellAbility ability, boolean creatureOnly) {
        Card best = null;
        for (Card card : bombCandidates(ability, creatureOnly))
            if (best == null || rank(card) > rank(best)) best = card;
        return best;
    }

    /** Every own-visible card {@link #bestBomb} would consider, in hand order.
     * Split out unchanged for v55's R3 forecast, which has to ask about the
     * payloads the native chooser will NOT take as well as the one it will. */
    private List<Card> bombCandidates(SpellAbility ability, boolean creatureOnly) {
        String type = ability.getParamOrDefault("ChangeType", "Card");
        List<Card> candidates = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || creatureOnly && !card.isCreature()) continue;
            if (!card.isValid(type.split(","), player, ability.getHostCard(), ability)) continue;
            if (!card.isPermanent()) continue;
            boolean bomb = card.isCreature() && ComputerUtilCard.evaluateCreature(card) >= BOMB_EVALUATION
                    || card.getCMC() >= BOMB_CMC;
            if (!bomb) continue;
            // The legend rule would eat our own copy before it ever attacked.
            if (card.getType().isLegendary() && player.isCardInPlay(card.getName())) continue;
            candidates.add(card);
        }
        return candidates;
    }

    /** One ordering for both halves of {@link #bestBomb}: a creature by the
     * same native evaluation the hidden-origin chooser uses, a non-creature
     * permanent by mana value, which is all that gate admits it on. */
    private static int rank(Card card) {
        return card.isCreature() ? ComputerUtilCard.evaluateCreature(card) : card.getCMC();
    }

    // ------------------------------------- v55 R2/R3: this turn's attack

    /** One prospective attacker: what it deals unblocked, and the two rules
     * that change where that damage lands. */
    private record Strike(Card card, int damage, boolean infect, boolean trample) {}

    private static Strike strike(Card card) {
        return new Strike(card, Math.max(0, card.getNetPower()),
                card.hasKeyword(Keyword.INFECT), card.hasKeyword(Keyword.TRAMPLE));
    }

    /** The card as it would exist on our battlefield, which is what the native
     * blocking rules have to be asked about. Same LKI shape
     * {@link #canAttackForValue} already uses. */
    private Card prospective(Card card) {
        Card copy = CardCopyService.getLKICopy(card);
        copy.setLastKnownZone(player.getZone(ZoneType.Battlefield));
        return copy;
    }

    /** Everything that could attack this turn if we take the line: the payload
     * (the cheat-in gives it haste) plus our own untapped, unsick bodies
     * already in play. Own-visible only. */
    private List<Strike> ownStrikes(Card payload, int manaLeft) {
        List<Strike> strikes = new ArrayList<>();
        strikes.add(strike(prospective(payload)));
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.isCreature() || card.isTapped() || card.isSick()) continue;
            if (!canAttackForValue(player, card, manaLeft)) continue;
            strikes.add(strike(card));
        }
        strikes.sort((a, b) -> b.damage() - a.damage());
        return strikes;
    }

    /** An opponent permanent that can make a creature token at instant speed
     * with the mana their PUBLIC battlefield can produce. Retrofitter Foundry
     * made a Servo mid-combat and double-blocked the payload for zero in
     * 783 s1, so the forecast owes the opponent one extra body.
     *
     * <p>Deliberately narrow: only a cost made of mana and a tap counts, so a
     * token ability with a sacrifice or exile cost we cannot verify from the
     * public board is never assumed payable (the Foundry's own Thopter and
     * Construct abilities both sacrifice a token it does not have).</p> */
    private static boolean tokenBlockerAvailable(Player opponent) {
        int mana = publicMana(opponent);
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (SpellAbility ability : card.getSpellAbilities()) {
                if (!ability.isActivatedAbility() || ability.getApi() != ApiType.Token) continue;
                if (ability.isPwAbility() || ability.getRestrictions().isSorcerySpeed()) continue;
                Cost cost = ability.getPayCosts();
                if (cost == null) return true;
                if (cost.hasTapCost() && card.isTapped()) continue;
                boolean simple = true;
                for (CostPart part : cost.getCostParts())
                    if (!(part instanceof CostPartMana) && !(part instanceof CostTap)) simple = false;
                if (!simple || cost.getTotalMana().getCMC() > mana) continue;
                return true;
            }
        }
        return false;
    }

    /** Could a body that does not exist yet block this attacker at all?
     *
     * <p>Amendment 1, on this run's probe-2 evidence. The design counts a
     * public instant-speed token maker as one extra blocker outright. Measured,
     * that costs wins the v52 policy already had: in {@code breach-foundry} and
     * the turn-3 arm of {@code breach-foundry:one-land}, Retrofitter Foundry
     * was up with mana, the payload was a 7/7 FLIER, the opponent never made a
     * blocker it could have used, and v52 won those four rows while the
     * unqualified rule declined them. The token's real characteristics cannot
     * be read without instantiating it, which would take a card id and pin a
     * token edition inside a live game, so the forecast assumes the weakest
     * ordinary body instead - a ground creature, which is exactly what the
     * Foundry's only payable ability makes ({@code {2}, {T}}: a 1/1 Servo; its
     * flying Thopter costs a Servo it does not have). A lone token therefore
     * cannot block a flier and cannot block a menace attacker. The Servo that
     * actually chump-blocked in 783 s1 blocked Blightsteel Colossus, which has
     * neither, so that case is unchanged.</p>
     *
     * <p>Limitation, registered: a token maker whose token flies or has reach
     * is not modelled, and this forecast will over-cast against one.</p> */
    private static boolean blockableByOrdinaryBody(Card attacker) {
        return !attacker.hasKeyword(Keyword.FLYING) && !attacker.hasKeyword(Keyword.MENACE);
    }

    /** Untapped public mana sources, counted the way {@link #ownMana} counts
     * ours. Their battlefield only - never their hand or library. */
    private static int publicMana(Player opponent) {
        int sources = 0;
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.isUntapped()) continue;
            for (SpellAbility ability : card.getManaAbilities()) {
                if (ability.getPayCosts() != null && ability.getPayCosts().getTotalMana().getCMC() == 0) { sources++; break; }
            }
        }
        return sources;
    }

    /** R2. Would this turn's attack end the game, judged against the opponent's
     * PUBLIC board alone?
     *
     * <p>Blocking legality is native ({@link CombatUtil#canBlock}), so flying,
     * reach, menace, protection and every printed "can't block" come from the
     * rules rather than from a keyword list of ours. Blockers are handed to the
     * biggest attacker first, which is the assignment that costs us the most.
     * A blocked attacker deals nothing unless it tramples, and an attacker with
     * infect deals poison rather than life damage - which is the whole Blightsteel
     * Colossus case: 11 infect is ten poison counters, lethal from any life
     * total if it connects (789 s1, the one Breach that converted).</p>
     *
     * <p>What it cannot see, by design: the opponent's hand. A combat trick or
     * an instant-speed blocker from there beats this forecast, and that is a
     * bound on the rule, not an input to it.</p> */
    private boolean attackEndsGame(Card payload, int manaLeft) {
        for (Player opponent : player.getOpponents()) {
            if (attackEndsGame(payload, manaLeft, opponent)) return true;
        }
        return false;
    }

    private boolean attackEndsGame(Card payload, int manaLeft, Player opponent) {
        List<Card> blockers = new ArrayList<>();
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && card.isCreature()) blockers.add(card);
        boolean token = tokenBlockerAvailable(opponent);
        int life = 0, poison = 0;
        for (Strike attacker : ownStrikes(payload, manaLeft)) {
            Card blocker = null;
            for (Card candidate : blockers)
                if (CombatUtil.canBlock(attacker.card(), candidate)) { blocker = candidate; break; }
            int through;
            if (blocker != null) {
                blockers.remove(blocker);
                through = attacker.trample() ? Math.max(0, attacker.damage() - blocker.getNetToughness()) : 0;
            } else if (token && blockableByOrdinaryBody(attacker.card())) {
                // One unknown ordinary body: enough to eat an attacker, and
                // enough toughness to matter only against trample.
                token = false;
                through = attacker.trample() ? Math.max(0, attacker.damage() - 1) : 0;
            } else {
                through = attacker.damage();
            }
            if (attacker.infect()) poison += through; else life += through;
        }
        if (poison > 0 && poison + opponent.getPoisonCounters() >= LETHAL_POISON) return true;
        return life > 0 && life >= opponent.getLife();
    }

    /** R3, and the honest statement of what this run could NOT implement.
     *
     * <p>The design ranks payloads and asks the plan to Breach the one the
     * opponent's board cannot block. The plan cannot make that choice. Through
     * the Breach is a hidden-origin ChangeZone: the payload is picked at
     * RESOLUTION by {@code ChangeZoneEffect} calling
     * {@code chooseSingleCardForZoneChange} on the controller, and
     * {@code CubeComboPlayerController} routes only {@code destination ==
     * Library} decisions to plans, so the pick falls through to
     * {@code ChangeZoneAi.chooseCardToHiddenOriginChangeZone}, i.e.
     * {@code ComputerUtilCard.getBestAI} - the highest creature evaluation,
     * which is exactly why all four zero-damage Breaches put in Blightsteel
     * Colossus while a flier sat in hand. Steering it needs a hook in the
     * controller, which this increment may not touch.</p>
     *
     * <p>So when some other own-visible payload would have been lethal and the
     * one the chooser will take is not, the plan declines and says so, instead
     * of casting into a body it knows will be blocked.</p> */
    private boolean anyLethalPayload(SpellAbility ability, int manaLeft) {
        for (Card candidate : bombCandidates(ability, true))
            if (canAttackForValue(player, candidate, manaLeft) && attackEndsGame(candidate, manaLeft)) return true;
        return false;
    }

    /** One decline line per turn, phase and reason. The full
     * {@code CUBE_PLAN_DECLINE family=bomb} instrumentation the diagnosis asks
     * for (R1) belongs to the controller and ships separately; this is the one
     * reason R2 owes the log. */
    private void declineOnce(String reason) {
        PhaseHandler phases = player.getGame().getPhaseHandler();
        String stamp = phases.getTurn() + "/" + phases.getPhase() + "/" + reason;
        if (stamp.equals(declined)) return;
        declined = stamp;
        System.err.println("CUBE_BOMB_PLAN decline=" + reason
                + " turn=" + phases.getTurn() + " phase=" + phases.getPhase());
    }

    // ------------------------------------------------ D4: the own-turn veto

    /** D4. Decline a {@code AILogic$ BeforeCombat} cheat-in that cannot pay off.
     *
     * <p>Called from {@code ChangeZoneAi.checkPhaseRestrictions} and gated there
     * on {@link CubeComboAi#enabled}, so the Default arm is untouched. The
     * defect it fixes is registered in the diagnosis: that logic's native gate
     * reads only the PHASE, so on {@code seat=0 phase=MAIN2} Default put Emrakul
     * onto the battlefield during the OPPONENT'S upkeep, where it could never
     * attack, and the end-step trigger sacrificed it for nothing
     * ({@code sneakActivations=1 opponentLife=20 emrakulZone=Library}).</p>
     *
     * <p>Kept simple on purpose: our own turn, MAIN1 or the beginning of combat,
     * an empty stack, attackers not yet declared, a body that can actually
     * attack, and no public uncast-entry exile. The "unless it wins or blocks
     * lethal at instant speed" exception the brief allows is deliberately NOT
     * implemented - this is a veto arm, and a veto that never invents a line is
     * worth more here than one that models a combat it cannot verify.</p> */
    public static boolean declineCheatIn(Player ai, SpellAbility sa) {
        if (!CubeComboAi.enabled(ai) || !cheatInShape(sa)) return false;
        PhaseHandler phases = ai.getGame().getPhaseHandler();
        if (!phases.isPlayerTurn(ai)) return true;
        if (!phases.is(PhaseType.MAIN1, ai) && !phases.is(PhaseType.COMBAT_BEGIN, ai)) return true;
        if (!ai.getGame().getStack().isEmpty()) return true;
        if (phases.getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)) return true;
        if (exiledOnUncastEntry(ai)) return true;
        Card bomb = new CubeBombPlan(ai).bestBomb(sa, true);
        return bomb == null || !canAttackForValue(ai, bomb, manaLeftAfter(ai, sa));
    }

    /** D3, second half. Decline a non-targeted counter-removal activation on one
     * of our own permanents whose payoff is that counter reaching ZERO, when the
     * activation cannot get there.
     *
     * <p>Called from {@code SpellAbilityAi} and gated there on
     * {@link CubeComboAi#enabled}. Default burns {@code {9}} here for nothing
     * ({@code depthsRemovals=3 lowestIce=7} in all four diagnosis rows) because
     * the ability is non-targeted, so {@code CountersRemoveAi} never reaches its
     * Dark Depths branch and {@code SpellAbilityAi.checkApiLogic}'s generic
     * "80% chance to play the ability" fallback fires instead.</p>
     *
     * <p>Two reasons to decline, both arithmetic. (1) The clone route is
     * available: it costs a flat {@code {2}} against {@code 3 x counters}, so for
     * any counter count >= 1 it is strictly cheaper and there is no "few
     * counters" case where this route wins - registered rather than guarded.
     * (2) We could not reach zero this turn anyway.</p> */
    static boolean declineCounterRemoval(Player ai, SpellAbility sa) {
        if (!CubeComboAi.enabled(ai) || sa.getApi() != ApiType.RemoveCounter
                || !sa.isActivatedAbility() || sa.usesTargeting()) return false;
        Card host = sa.getHostCard();
        if (host == null || host.getController() != ai || !host.isInPlay()) return false;
        String type = sa.getParam("CounterType");
        if (type == null || !type.equals(zeroCounterPayoffType(host))) return false;
        int counters = host.getCounters(CounterType.getType(type));
        if (counters <= 0) return false;
        if (new CubeBombPlan(ai).cloneRouteFor(host) != null) return true;
        Cost cost = sa.getPayCosts();
        int each = cost == null ? 0 : cost.getTotalMana().getCMC();
        int per = Math.max(1, java.lang.Integer.parseInt(sa.getParamOrDefault("CounterNum", "1")));
        long needed = (long) each * ((counters + per - 1) / per);
        return needed > ownMana(ai);
    }

    // ------------------------------------------------------------- D3 action

    /** The one native activation D3 proposes: our land-clone permanent copying
     * our zero-counter-payoff permanent. Returns a ready, targeted, payable
     * ability copy, or null. Forge finishes the line by itself - the copy enters
     * with no counters, {@code GameAction.handleLegendRule} prompts, and
     * {@code LegendaryRuleAi}'s own Dark Depths branch keeps the zero-counter
     * copy, whose state trigger sacrifices it for the token. No legend-rule hook
     * is added, by design. */
    private SpellAbility cloneRouteFor(Card payoff) {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || card == payoff || card.isTapped()) continue;
            for (SpellAbility original : card.getSpellAbilities()) {
                if (!landCloneShape(original)) continue;
                SpellAbility ability = original.copy(player);
                if (!CubeComboAi.canPlayNative(ability, player)) continue;
                if (!CubeComboAi.selectSingleTarget(ability, payoff)) continue;
                if (!CubeComboAi.canPayCost(ability, player, false)) continue;
                return ability;
            }
        }
        return null;
    }

    private SpellAbility depthsAction() {
        for (Card payoff : player.getCardsIn(ZoneType.Battlefield)) {
            if (payoff.isFaceDown()) continue;
            String type = zeroCounterPayoffType(payoff);
            if (type == null || payoff.getCounters(CounterType.getType(type)) <= 0) continue;
            SpellAbility clone = cloneRouteFor(payoff);
            if (clone == null) continue;
            // The payoff is a legendary creature TOKEN: a public legendary
            // bounce answers it for free and it ceases to exist. Containment
            // Priest is irrelevant here (it reads Creature.!token) and is
            // deliberately not consulted.
            if (legendaryBounceVisible(player)) continue;
            // Do not spend into a public land-destruction answer at all: it can
            // destroy either half in response and the activation fizzles.
            if (landDestructionVisible(player)) continue;
            return audit("depths-stage", clone);
        }
        return null;
    }

    // ------------------------------------------------------------- D1 action

    private SpellAbility showAndTellAction() {
        for (Card hand : player.getCardsIn(ZoneType.Hand)) {
            if (hand.isFaceDown()) continue;
            for (SpellAbility original : hand.getSpellAbilities()) {
                if (!showAndTellShape(original)) continue;
                SpellAbility cast = original.copy(player);
                if (!CubeComboAi.canPlayNative(cast, player) || !CubeComboAi.canPayCost(cast, player, false)) continue;
                Card bomb = bestBomb(cast, false);
                if (bomb == null) continue;
                // What the opponent's VISIBLE board already answers. The
                // opponent's own put-in is hidden and uncontrollable, and the
                // design accepts that symmetry rather than modelling it: no gate
                // here reads their hand, which is exactly the read that makes
                // Default refuse the spell when it is best.
                if (exiledOnUncastEntry(player)) continue;
                if (bomb.getType().isLegendary() && legendaryBounceVisible(player)) continue;
                return audit("show-and-tell", cast);
            }
        }
        return null;
    }

    // ------------------------------------------------------------- D2 action

    private SpellAbility breachAction() {
        for (Card hand : player.getCardsIn(ZoneType.Hand)) {
            if (hand.isFaceDown()) continue;
            for (SpellAbility original : hand.getSpellAbilities()) {
                if (!original.isSpell() || !cheatInShape(original)) continue;
                SpellAbility cast = original.copy(player);
                if (!CubeComboAi.canPlayNative(cast, player) || !CubeComboAi.canPayCost(cast, player, false)) continue;
                Card bomb = bestBomb(cast, true);
                if (bomb == null) continue;
                if (exiledOnUncastEntry(player)) continue;
                int manaLeft = manaLeftAfter(player, cast);
                if (!canAttackForValue(player, bomb, manaLeft)) continue;
                if (bomb.getType().isLegendary() && legendaryBounceVisible(player)) continue;
                // v55 R2. The payload leaves at the beginning of the next end
                // step (observed six times of nine), so a Breach that does not
                // end the game spends two cards for one hit and hands the board
                // straight back. Only cast when this turn's attack is lethal
                // against the public board.
                if (!attackEndsGame(bomb, manaLeft)) {
                    declineOnce(anyLethalPayload(cast, manaLeft) ? "breach:payload-not-selectable" : "breach:not-lethal");
                    continue;
                }
                return audit("through-the-breach", cast);
            }
        }
        return null;
    }

    // ------------------------------------------------------- v55 R6 action

    /** R6. The Depths route's two halves are both LANDS, and the v52 policy
     * never touched land drops: in 787 s0 both halves were own-visible from
     * turn 3, the seat spent its drops on Thespian's Stage, Volcanic Island and
     * Island while Dark Depths sat in hand, and the 20/20 arrived on turn 11 at
     * 2 life instead of turn 7 at 18. When both halves are own-visible and a
     * land drop is still available, the half still in hand is proposed ahead of
     * the ordinary land choice; the clone activation stays D3's job.
     *
     * <p>Own-visible only: our hand and our battlefield. Nothing public is
     * needed and nothing public is read.</p> */
    private SpellAbility depthsLandAction() {
        if (player.getLandsPlayedThisTurn() >= player.getMaxLandPlays() && !player.getMaxLandPlaysInfinite()) return null;
        Card payoff = null, clone = null, inHand = null;
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield))
            for (Card card : player.getCardsIn(zone)) {
                if (card.isFaceDown()) continue;
                boolean isPayoff = zeroCounterPayoffType(card) != null;
                boolean isClone = !isPayoff && hasLandCloneAbility(card);
                if (!isPayoff && !isClone) continue;
                if (isPayoff && payoff == null) payoff = card;
                if (isClone && clone == null) clone = card;
                // The payoff half is preferred when both are still in hand: it
                // is the half the route waits on, and the clone half is useless
                // until it has something to copy.
                if (zone == ZoneType.Hand && (inHand == null || isPayoff && inHand != payoff)) inHand = card;
            }
        if (payoff == null || clone == null || inHand == null) return null;
        if (inHand != payoff && inHand != clone) return null;
        SpellAbility land = landPlay(inHand);
        return land == null ? null : audit("depths-land", land);
    }

    private static boolean hasLandCloneAbility(Card card) {
        for (SpellAbility ability : card.getSpellAbilities()) if (landCloneShape(ability)) return true;
        return false;
    }

    /** This card's own native land play, or null. The same enumeration
     * {@code AiController} uses for the ordinary land choice, so the object we
     * propose is the object Forge would have played. */
    private SpellAbility landPlay(Card card) {
        if (!card.isLand() || !card.isInZone(ZoneType.Hand)) return null;
        for (SpellAbility ability : card.getAllPossibleAbilities(player, true)) {
            if (!ability.isLandAbility() || !CubeComboAi.canPlayNative(ability, player)) continue;
            return ability;
        }
        return null;
    }

    // ------------------------------------------------------------ plumbing

    /** Observability only, and the audit the brief asks for: this controller
     * proposes actions directly, so an ability whose host carries
     * {@code AI:RemoveDeck:All} - which
     * {@code AiController.getSpellAbilityToPlay} would have filtered out of the
     * ordinary candidate list - reaches the stack here. That bypass is confined
     * to this seat's plan actions and every one still passes
     * {@code CubeComboPlayerController.playChosenSpellAbility}'s native-legality
     * guard and native payment. The line names the host and the hint. */
    private SpellAbility audit(String line, SpellAbility ability) {
        selected = ability;
        actions++;
        System.err.println("CUBE_BOMB_PLAN line=" + line
                + " card=" + ability.getHostCard().getName().replace(' ', '_')
                + " api=" + ability.getApi()
                + " remAIDeck=" + ComputerUtilCard.isCardRemAIDeck(ability.getHostCard())
                + " turn=" + player.getGame().getPhaseHandler().getTurn()
                + " phase=" + player.getGame().getPhaseHandler().getPhase());
        return ability;
    }

    /** Our own main phase with an empty stack, our own turn, bounded actions.
     * D2 additionally needs a PRECOMBAT main, which its own gate adds. */
    public SpellAbility nextAction() {
        var game = player.getGame();
        PhaseHandler phases = game.getPhaseHandler();
        if (turn != phases.getTurn()) { turn = phases.getTurn(); actions = 0; selected = null; }
        if (failedTurn == turn || actions >= ACTION_CAP || player.cantWin() || !game.getStack().isEmpty()
                || player.getOpponents().isEmpty()
                || !(phases.is(PhaseType.MAIN1, player) || phases.is(PhaseType.MAIN2, player))) return null;
        SpellAbility action = depthsAction();
        // v55 R6, after the clone activation itself (if the copy can be made
        // now, making it beats sequencing) and before every other line.
        if (action == null) action = depthsLandAction();
        if (action == null) action = showAndTellAction();
        // Through the Breach's body has haste but must still reach the attack
        // step, so this one line is precombat-only.
        if (action == null && phases.is(PhaseType.MAIN1, player)) action = breachAction();
        return action;
    }

    public boolean waitingForOwnSpell() {
        var game = player.getGame();
        if (game.getStack().isEmpty() || selected == null || turn != game.getPhaseHandler().getTurn()) return false;
        var top = game.getStack().peekAbility();
        return top != null && top.getActivatingPlayer() == player
                && top.getHostCard() == selected.getHostCard();
    }

    public boolean owns(SpellAbility ability) { return ability == selected; }

    public boolean play(SpellAbility ability) {
        // v55 R6: a land play uses no stack, no payment and no targeting, and
        // PlayerControllerAi has its own branch for exactly that. Mirrored here
        // rather than routed through handlePlayingSpellAbility, which assumes a
        // spell or an activated ability. A land we cannot actually play marks
        // the turn failed so the plan can never re-propose it into the
        // ask-again loop CubeComboPlayerController's guard documents.
        if (ability.isLandAbility()) {
            if (!ability.canPlay()) {
                failedTurn = turn;
                System.err.println("CUBE_BOMB_PLAN native-land-refused turn=" + turn
                        + " card=" + ability.getHostCard().getName().replace(' ', '_'));
                return false;
            }
            ability.resolve();
            return true;
        }
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, ability, null);
        if (!played) {
            failedTurn = turn;
            System.err.println("CUBE_BOMB_PLAN native-payment-failed turn=" + turn
                    + " card=" + ability.getHostCard().getName().replace(' ', '_'));
        }
        return played;
    }
}
