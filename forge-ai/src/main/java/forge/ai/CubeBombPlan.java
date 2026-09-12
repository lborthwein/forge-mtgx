package forge.ai;

import forge.StaticData;
import forge.card.CardRules;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterType;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostSacrifice;
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
 * {@link #lethalForecast} (design R2/R3 - the Breach's payload leaves at end of
 * turn, so a Breach that does not end the game spends two cards for one hit)
 * and {@link #depthsLandAction} (design R6 - spend the land drops on the two
 * halves of the Depths route before any other land).</p>
 *
 * <p><b>v56 payload selection.</b> v55 could see that a different own-visible
 * payload would be lethal and could not make the native chooser take it, because
 * a hidden-origin ChangeZone picks its card at RESOLUTION through
 * {@code PlayerController.chooseSingleCardForZoneChange}. v56 adds that hook in
 * {@link CubeComboPlayerController}, and this class supplies the two rankings it
 * asks for: {@link #bestLethalPayload} (design R3 - among our own hand, the
 * payload whose forecast attack ends the game, unblockable infect first and then
 * the highest forecast damage) and {@link #bestShowAndTellPayload} (design R4 -
 * a printed enter-the-battlefield trigger that changes the board, then a flying
 * lifelink/deathtouch body while we are behind on life, otherwise the ordinary
 * AI's own choice). The hook answers only for OUR OWN cards from OUR OWN hand on
 * an ability this plan proposed on this turn ({@link #ownsPayloadChoice}); Show
 * and Tell's opponent-side choice is made by the opponent's own controller and
 * is never reached from here. With the hook, v55's
 * {@code breach:payload-not-selectable} decline becomes a cast.</p>
 *
 * <p><b>v58 blocker forecast.</b> v55 and v56 gave the opponent at most one
 * hypothetical instant-speed blocker, found by a cost filter that admitted only
 * mana and a tap, and modelled it as an ordinary GROUND body (v55 Amendment 1).
 * Both halves were wrong in one observed receipt: Retrofitter Foundry's
 * {@code {1}, {T}, Sacrifice a Servo} is payable from the PUBLIC board and the
 * Thopter it makes has printed flying, and in {@code breach-foundry} seat 0/1
 * MAIN2 the opponent made exactly that Thopter at declare-attackers and blocked
 * the 7/7 the Breach had just paid for. {@link #publicPayment} now answers the
 * whole cost against their battlefield and life, {@link #tokenBody} reads the
 * token's printed characteristics out of the static token rules without
 * instantiating it, {@link #grantedBody} covers an instant-speed flying/reach
 * GRANT on a public permanent, and {@link #take} still spends only ONE of them,
 * removing whatever that payment sacrifices from the real blocker pool.
 * Amendment 1's rule survives for a body that stays unqualified: it blocks
 * neither a flier nor a menace attacker.</p>
 *
 * <p><b>v62 cheat-in value.</b> v55's R2 gate was fitted to the bomb2 panel's
 * losses, all of which were a vanilla beater chump-blocked for nothing, and it
 * refused everything a payload does BESIDES unblocked damage. Breaching Emrakul
 * wipes six permanents without winning the turn; Ashen Rider exiles one
 * permanent on the way in and a second when the Breach's end-step SACRIFICE
 * fires its dies trigger - {@code through_the_breach.txt} ships
 * {@code AtEOT$ Sacrifice}, not an exile, which is the line term 4 rests on;
 * Worldspine Wurm leaves three 5/5 bodies; Griselbrand's pay-7-draw-7 keeps
 * seven cards after the body is gone. {@link #breachValueTier} replaces the
 * lethal-only question with a floor over six independent terms, every one read
 * from a PRINTED property - {@link Keyword#ANNIHILATOR}'s magnitude, a
 * {@code ChangesZone} trigger of the card itself whose printed {@code Execute$}
 * chain reaches {@link #VALUE_APIS}, a reanimation shape in our own hand, an
 * activated {@code Draw} for a literal life payment - and never from a card
 * name. The decline token {@code breach:not-lethal} becomes
 * {@code breach:no-value}; the decision is unchanged wherever no term fires.
 * Designed in {@code 2026-09-10-forge-combo-ai/design-v62-breach-value.md}.</p>
 *
 * <p><b>v71 the artifact half of the cheat-in family.</b> The CubeCobra synergy
 * census makes Tinker the rank-1 drafted-with partner of eight cube cards, six
 * of them payloads, and this class was blind to it: {@code tinker.txt} is
 * {@code Origin$ Library}, {@code ChangeType$ Artifact} and carries no
 * {@code AILogic}, so it matches neither {@link #cheatInShape} nor
 * {@link #showAndTellShape}. The increment's own claims probe
 * ({@code 2026-09-12-tinker-v71/control-1}) overturned the premise of building
 * a route for it: Default already CASTS Tinker, on turn 1, in every payable
 * position of both seats and both mains, and its hidden-origin chooser already
 * fetches the most expensive artifact. What Default gets wrong is what it is
 * willing to spend two cards on ({@code tinker-vanilla}: Tinker plus an
 * Ornithopter for a 5/3 into a board that already blocks it) and what it pays
 * with ({@code tinker-sac-choice}: {@code tinker.txt}'s own
 * {@code SVar:AIPreference:SacCost$} excludes Lotus Petal BY NAME from its
 * {@code Artifact.cmcEQ0} tier, so the payment reaches the {@code cmcEQ1} tier
 * and sacrifices Sensei's Divining Top - a piece {@link CubeTopPlan} owns -
 * with the Petal still on the battlefield). v71 is therefore a VETO arm in the
 * v52 D4 idiom, not a proposal arm: {@link #declineArtifactFetch} refuses the
 * ordinary cast when no artifact in our own library clears a value floor
 * written in the v62 terms ({@code tinker:no-value}) or when the artifact the
 * NATIVE payment would take is not expendable ({@code tinker:sac-piece}).
 * Our library is read as an unordered COMPOSITION - derivable from our own
 * registered decklist less our own visible zones - and never as an ORDER: the
 * scan is sorted by name and every tie breaks by name. Designed in
 * {@code 2026-09-12-tinker-v71/design.md}.</p> */
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
    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeDoomsdayPlan#declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code action-cap},
     * {@code failed-this-turn}, {@code no-opponent}, {@code no-bomb-line}, or
     * a hazard token {@code <line>:<hazard>} where {@code <line>} is
     * {@code depths}, {@code show-and-tell} or {@code breach} and
     * {@code <hazard>} is {@code legendary-bounce}, {@code land-destruction},
     * {@code priest}, {@code no-attack-value} or {@code no-value} (the last is
     * v62's value floor, which REPLACES v55 R2's {@code not-lethal}: that token
     * can no longer occur, exactly as v55's
     * {@code breach:payload-not-selectable} was retired by v56's payload hook).
     * The three routes are
     * consulted in order and each overwrites the token, so the LAST hazard
     * reached is the one reported; a pass that reaches no hazard at all
     * reports {@code no-bomb-line}. Every hazard is read from a PUBLIC
     * permanent, exactly as the gates that set it already do; no token reads
     * an opponent's hand, library or decklist.</p> */
    private String decline = "other check=bomb-plan";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline = reason; return null; }
    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order, after that guard has already
     * decided to decline. */
    private String gateReason() {
        PhaseHandler phases = player.getGame().getPhaseHandler();
        if (failedTurn == turn) return "failed-this-turn";
        if (actions >= ACTION_CAP) return "action-cap";
        if (player.cantWin()) return "cant-win";
        if (!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        if (player.getOpponents().isEmpty()) return "no-opponent";
        return "phase";
    }
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

    /** v71 R1. Tinker: a spell that sacrifices an artifact as an additional
     * cost and searches our own LIBRARY for an artifact to put onto the
     * battlefield. Recognised by printed property, never by name.
     *
     * <p>The two ARTIFACT clauses are load-bearing. {@code natural_order.txt}
     * is the same {@code Origin$ Library | Destination$ Battlefield} spell with
     * a sacrifice cost - and is a registered position in this very fixture
     * ({@code natural-order-hoof}) - but its {@code ChangeType$ Creature.Green}
     * and {@code Sac<1/Creature.Green>} keep it out of this shape, which is
     * exactly the narrowing this increment wants: the census's design brief is
     * about the artifact half of the family.</p> */
    private static boolean tinkerShape(SpellAbility sa) {
        return sa.isSpell() && sa.getApi() == ApiType.ChangeZone && !sa.usesTargeting()
                && "Library".equals(sa.getParam("Origin"))
                && "Battlefield".equals(sa.getParam("Destination"))
                && sa.getParamOrDefault("ChangeType", "").startsWith("Artifact")
                && artifactSacrifice(sa) != null;
    }

    /** The {@code Sac<1/Artifact>} part of a cost, or null. A cost that
     * sacrifices the spell's own host, or names the original host, is not an
     * artifact we choose and is refused here. */
    private static CostSacrifice artifactSacrifice(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null) return null;
        for (CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostSacrifice sacrifice)) continue;
            if (sacrifice.payCostFromSource() || !sacrifice.getType().startsWith("Artifact")) continue;
            return sacrifice;
        }
        return null;
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
     * that change where that damage lands. {@code payload} marks the one body
     * the line would put onto the battlefield, so v56's R3 ranking can read what
     * that body specifically contributes without re-running the forecast. */
    private record Strike(Card card, int damage, boolean infect, boolean trample, boolean payload) {}

    private static Strike strike(Card card, boolean payload) {
        return new Strike(card, Math.max(0, card.getNetPower()),
                card.hasKeyword(Keyword.INFECT), card.hasKeyword(Keyword.TRAMPLE), payload);
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
        strikes.add(strike(prospective(payload), true));
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.isCreature() || card.isTapped() || card.isSick()) continue;
            if (!canAttackForValue(player, card, manaLeft)) continue;
            strikes.add(strike(card, false));
        }
        strikes.sort((a, b) -> b.damage() - a.damage());
        return strikes;
    }

    /** One blocker the opponent does not have yet but could produce at instant
     * speed from PUBLIC information alone: the printed characteristics that
     * decide whether it can block, and the public permanents its cost would
     * consume on the way.
     *
     * <p>v55 and v56 carried a single boolean here and modelled whatever it
     * found as one ordinary GROUND body (Amendment 1). That is the defect this
     * increment fixes: Retrofitter Foundry's flying Thopter costs
     * {@code {1}, {T}, Sacrifice a Servo}, the sacrifice is as public as their
     * untapped lands, and the token it makes has printed flying. In
     * {@code breach-foundry} seat 0/1 MAIN2 the opponent made exactly that
     * Thopter at declare-attackers and ate a 7/7 Griselbrand the Breach had
     * just paid for.</p> */
    private record Hypothetical(boolean flying, boolean reach, int toughness, List<Card> consumed) {}

    /** The cost amount that means "not a literal": anything the public board
     * cannot count, such as {@code X} or a script variable. */
    private static Integer literalAmount(CostPart part) {
        try {
            return part.convertAmount();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** R1. Can the opponent pay every part of this cost from what a spectator
     * can see, and which of their public permanents would that payment consume?
     *
     * <p>Returns null when any part is not verifiable. The allowlist is four
     * parts and is deliberately closed: mana (counted as untapped public
     * sources), the ability's own {@code {T}}, a sacrifice of public permanents,
     * and a life payment against their public life total. Every other cost part
     * - exile, discard, counter removal, tapping other permanents - keeps v55's
     * stance and makes the ability unpayable, because verifying it would need
     * their hand, their library or a hidden choice.</p>
     *
     * <p>A sacrificed permanent need not be untapped. The Servo that paid for
     * the Thopter in the observed receipt had attacked on the previous turn and
     * was tapped; only {@link CostTap} reads tapped-ness, and only of the
     * ability's own host.</p> */
    private static List<Card> publicPayment(Card host, SpellAbility ability, Player opponent, int mana) {
        Cost cost = ability.getPayCosts();
        List<Card> consumed = new ArrayList<>();
        if (cost == null) return consumed;
        if (cost.getTotalMana().getCMC() > mana) return null;
        for (CostPart part : cost.getCostParts()) {
            if (part instanceof CostPartMana) continue;
            if (part instanceof CostTap) {
                if (host.isTapped()) return null;
                continue;
            }
            if (part instanceof CostPayLife) {
                Integer amount = literalAmount(part);
                if (amount == null || opponent.getLife() <= amount) return null;
                continue;
            }
            if (!(part instanceof CostSacrifice)) return null;
            Integer amount = literalAmount(part);
            if (amount == null || amount < 0) return null;
            int found = 0;
            if (part.payCostFromSource()) {
                if (consumed.contains(host)) return null;
                consumed.add(host);
                found = amount == 0 ? 0 : 1;
                if (found < amount) return null;
                continue;
            }
            String[] types = part.getType().split(";");
            for (Card candidate : opponent.getCardsIn(ZoneType.Battlefield)) {
                if (found >= amount) break;
                if (candidate.isFaceDown() || consumed.contains(candidate)) continue;
                if (!candidate.isValid(types, opponent, host, ability)) continue;
                consumed.add(candidate);
                found++;
            }
            if (found < amount) return null;
        }
        return consumed;
    }

    /** R2. The body this {@code Token} ability would put onto their battlefield,
     * or null when it makes no blocker.
     *
     * <p>Read from the token's own RULES in the static card database rather
     * than instantiated: {@code TokenDb.getRules()} is the same map
     * {@code containsRule} answers from, keyed by the {@code TokenScript$} name
     * the ability already carries, and reading it allocates nothing. The v55
     * objection stands against {@link forge.game.card.token.TokenInfo#getProtoType}
     * specifically, which calls {@code CardFactory.getCard} - taking a card id -
     * and pins a token edition for the whole game.</p>
     *
     * <p>Three ability params override the printed card and are read from the
     * ability: {@code PumpKeywords$} (keywords the token gets as it is made),
     * {@code TokenToughness$} (a literal only) and {@code TokenTapped$} - a
     * token that enters tapped is no blocker at all.</p> */
    private static Hypothetical tokenBody(SpellAbility ability, List<Card> consumed) {
        if (ability.hasParam("TokenTapped")) return null;
        String script = ability.getParam("TokenScript");
        if (script == null || script.isEmpty()) return null;
        CardRules rules = StaticData.instance().getAllTokens().getRules().get(script.split(",")[0].trim());
        if (rules == null || !rules.getType().isCreature()) return null;
        boolean flying = false, reach = false;
        for (String keyword : rules.getMainPart().getKeywords()) {
            if ("Flying".equalsIgnoreCase(keyword)) flying = true;
            if ("Reach".equalsIgnoreCase(keyword)) reach = true;
        }
        String pumped = ability.getParam("PumpKeywords");
        if (pumped != null) for (String keyword : pumped.split(" & ")) {
            if ("Flying".equalsIgnoreCase(keyword)) flying = true;
            if ("Reach".equalsIgnoreCase(keyword)) reach = true;
        }
        int toughness = rules.getMainPart().getIntToughness();
        String override = ability.getParam("TokenToughness");
        if (override != null) try {
            toughness = Integer.parseInt(override.trim());
        } catch (NumberFormatException ignored) {
            // A calculated toughness is not public arithmetic; keep the printed one.
        }
        return new Hypothetical(flying, reach, Math.max(0, toughness), consumed);
    }

    /** R3. Does this ability give a creature flying or reach at instant speed?
     * A permanent that pumps an existing body into a blocker for the payload is
     * the same hazard as one that makes a new body. */
    private static boolean grantsEvasion(SpellAbility ability) {
        if (ability.getApi() != ApiType.Pump) return false;
        String keywords = ability.getParam("KW");
        if (keywords == null) return false;
        for (String keyword : keywords.split(" & "))
            if ("Flying".equalsIgnoreCase(keyword) || "Reach".equalsIgnoreCase(keyword)) return true;
        return false;
    }

    /** R3's modelled answer: their own best untapped creature, given reach.
     * Reach is enough to block a flier and claims nothing about attacking. The
     * creature is CONSUMED - a body pumped to block the payload is not also
     * blocking something else. */
    private static Hypothetical grantedBody(Card host, SpellAbility ability, Player opponent, List<Card> consumed) {
        boolean self = "Self".equals(ability.getParam("Defined")) || ability.getParam("Defined") == null && !ability.usesTargeting();
        String targets = ability.getParamOrDefault("ValidTgts", "");
        if (!self && !targets.contains("Creature")) return null;
        Card best = null;
        for (Card candidate : opponent.getCardsIn(ZoneType.Battlefield)) {
            if (candidate.isFaceDown() || !candidate.isCreature() || !candidate.isUntapped()) continue;
            if (consumed.contains(candidate)) continue;
            if (self && candidate != host) continue;
            if (best == null || candidate.getNetToughness() > best.getNetToughness()) best = candidate;
        }
        if (best == null) return null;
        List<Card> all = new ArrayList<>(consumed);
        all.add(best);
        return new Hypothetical(false, true, Math.max(0, best.getNetToughness()), all);
    }

    /** Every instant-speed blocker the opponent's PUBLIC board could produce,
     * in battlefield order. Their battlefield, life and poison only - never
     * their hand, never their library. */
    private static List<Hypothetical> instantBlockers(Player opponent) {
        int mana = publicMana(opponent);
        List<Hypothetical> bodies = new ArrayList<>();
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (SpellAbility ability : card.getSpellAbilities()) {
                if (!ability.isActivatedAbility()) continue;
                if (ability.isPwAbility() || ability.getRestrictions().isSorcerySpeed()) continue;
                boolean token = ability.getApi() == ApiType.Token;
                if (!token && !grantsEvasion(ability)) continue;
                List<Card> consumed = publicPayment(card, ability, opponent, mana);
                if (consumed == null) continue;
                Hypothetical body = token ? tokenBody(ability, consumed)
                        : grantedBody(card, ability, opponent, consumed);
                if (body != null) bodies.add(body);
            }
        }
        return bodies;
    }

    /** Could this modelled body block that attacker?
     *
     * <p>A body that does not exist yet cannot go through
     * {@link CombatUtil#canBlock}, so this is the same two-keyword test v55
     * asked - now asked against the body's PRINTED characteristics rather than
     * against Amendment 1's assumed ground creature. Amendment 1's rule
     * survives exactly where it still applies: an unqualified body, one whose
     * token rules could not be read or which carries neither flying nor reach,
     * still blocks neither a flier nor a menace attacker.</p>
     *
     * <p>The menace half is kept conservative on purpose: pairing two
     * hypothetical bodies is not modelled, and one body never blocks a menace
     * attacker.</p> */
    private static boolean blocks(Hypothetical body, Card attacker) {
        if (attacker.hasKeyword(Keyword.MENACE)) return false;
        if (attacker.hasKeyword(Keyword.FLYING)) return body.flying() || body.reach();
        return true;
    }

    /** R4. Spend the opponent's ONE hypothetical body on this attacker, if any
     * of the modelled bodies can block it.
     *
     * <p>The cap of one is exactly v55's allowance, so v58 can only ever hand
     * the opponent a BETTER body, never more of them. Taking a body removes the
     * public permanents its cost consumes from the real blocker pool - the
     * Servo that is sacrificed for a Thopter is no longer a blocker itself -
     * and empties the candidate list, because only one is ever paid for.</p> */
    private static Hypothetical take(List<Hypothetical> bodies, List<Card> blockers, Card attacker) {
        for (Hypothetical body : bodies) {
            if (!blocks(body, attacker)) continue;
            boolean available = true;
            for (Card spent : body.consumed())
                if (spent.isCreature() && !blockers.contains(spent)) available = false;
            if (!available) continue;
            blockers.removeAll(body.consumed());
            bodies.clear();
            return body;
        }
        return null;
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
     * bound on the rule, not an input to it.</p>
     *
     * <p>Returns the first opponent this payload's attack would kill, with what
     * the payload itself contributed, or null when no opponent dies. Same loop,
     * same order and the same two lethality tests v55's {@code attackEndsGame}
     * shipped - "not null" is v55's answer exactly; the two extra fields are
     * read only by v56's R3 ranking.</p> */
    private Forecast lethalForecast(Card payload, int manaLeft) {
        for (Player opponent : player.getOpponents()) {
            Forecast forecast = forecast(payload, manaLeft, opponent);
            if (forecast.lethal()) return forecast;
        }
        return null;
    }

    /** What one prospective attack does to one opponent: whether it ends the
     * game, and - for the R3 tie-break - what the payload body itself put
     * through and whether anything blocked it at all. */
    private record Forecast(boolean lethal, int payloadThrough, boolean payloadUnblocked) {}

    private Forecast forecast(Card payload, int manaLeft, Player opponent) {
        List<Card> blockers = new ArrayList<>();
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && card.isCreature()) blockers.add(card);
        List<Hypothetical> instants = instantBlockers(opponent);
        int life = 0, poison = 0, payloadThrough = 0;
        boolean payloadUnblocked = false;
        for (Strike attacker : ownStrikes(payload, manaLeft)) {
            Card blocker = null;
            for (Card candidate : blockers)
                if (CombatUtil.canBlock(attacker.card(), candidate)) { blocker = candidate; break; }
            int through;
            boolean unblocked = false;
            Hypothetical made = blocker != null ? null : take(instants, blockers, attacker.card());
            if (blocker != null) {
                blockers.remove(blocker);
                through = attacker.trample() ? Math.max(0, attacker.damage() - blocker.getNetToughness()) : 0;
            } else if (made != null) {
                // ONE body the opponent does not have yet, now with the printed
                // characteristics of whatever their public board can actually
                // make. Its toughness matters only against trample.
                through = attacker.trample() ? Math.max(0, attacker.damage() - made.toughness()) : 0;
            } else {
                through = attacker.damage();
                unblocked = true;
            }
            if (attacker.payload()) { payloadThrough = through; payloadUnblocked = unblocked; }
            if (attacker.infect()) poison += through; else life += through;
        }
        boolean lethal = poison > 0 && poison + opponent.getPoisonCounters() >= LETHAL_POISON
                || life > 0 && life >= opponent.getLife();
        return new Forecast(lethal, payloadThrough, payloadUnblocked);
    }

    /** R3. The own-visible payload this turn's attack should actually use.
     *
     * <p>v55 could only ask whether the payload the native chooser was going to
     * take happened to be lethal, and declined
     * ({@code breach:payload-not-selectable}) when a different one in our own
     * hand was - all four zero-damage Breaches of the panel put in Blightsteel
     * Colossus while a flier sat in hand. v56 chooses instead: every candidate
     * the cheat-in could legally take is forecast the same way, only the lethal
     * ones qualify, and among those an unblockable infect body comes first -
     * eleven infect damage is ten poison counters, lethal from any life total,
     * which is exactly 789 s1 - then the highest damage the payload itself puts
     * through. Own-visible only: our own hand and the opponent's PUBLIC board,
     * life and poison.</p>
     *
     * <p>The same ranking answers at propose time (the R2 gate below) and at
     * resolution ({@link #choosePayload}), over the same candidate set, so the
     * plan can never cast into a body the hook would then decline to take.</p> */
    private Card bestLethalPayload(SpellAbility ability, int manaLeft, List<Card> pool) {
        Card best = null;
        int bestTier = -1, bestThrough = -1;
        for (Card candidate : pool) {
            if (!canAttackForValue(player, candidate, manaLeft)) continue;
            Forecast forecast = lethalForecast(candidate, manaLeft);
            if (forecast == null) continue;
            int tier = candidate.hasKeyword(Keyword.INFECT) && forecast.payloadUnblocked() ? 1 : 0;
            if (tier < bestTier || tier == bestTier && forecast.payloadThrough() <= bestThrough) continue;
            best = candidate; bestTier = tier; bestThrough = forecast.payloadThrough();
        }
        return best;
    }

    // --------------------------------------- v62: the cheat-in value floor

    /** v62. The (sub)ability APIs whose resolution is value against the
     * opponent's public board, against the opponent themselves, or - for a
     * trigger the end-step sacrifice fires - on ours. Read from the card's own
     * printed script, so this is a property and not a card name.
     *
     * <p>Deliberately NOT the same list as v56 R4's {@link #BOARD_CHANGE_APIS},
     * which answers a different question ("does a Show and Tell payoff change
     * the board"). {@code GainLife} is in that list and not in this one -
     * gaining life is not value taken from the opponent - and {@code Token} is
     * in this one and not in that: term 4's registered example leaves bodies on
     * OUR battlefield ({@code worldspine_wurm.txt},
     * {@code SVar:TrigToken:DB$ Token | TokenAmount$ 3}). Keeping the two lists
     * separate is what preserves every v56 R4 receipt.</p> */
    private static final List<String> VALUE_APIS = List.of(
            "Sacrifice", "SacrificeAll", "Discard", "LoseLife", "Draw", "Destroy", "DestroyAll", "Token");

    /** The two zone-changing APIs, admitted ONLY when they move a permanent off
     * a battlefield. That clause is the whole discriminator of term 3/4 and it
     * was derived from the frozen scripts rather than assumed:
     * {@code ashen_rider.txt} exiles with {@code Origin$ Battlefield} and is
     * value, while {@code emrakul_the_aeons_torn.txt},
     * {@code ulamog_the_infinite_gyre.txt} and {@code worldspine_wurm.txt} all
     * carry a {@code ChangeZone(All) | Origin$ Graveyard | Destination$ Library}
     * shuffle of their OWN owner's graveyard, which touches no public board and
     * must count as zero. */
    private static final List<String> VALUE_ZONE_APIS = List.of("ChangeZone", "ChangeZoneAll");

    /** Annihilator N on the payload is worth the permanents it would actually
     * take: {@code min(N, their public permanents)}, over the opponent it takes
     * most from. Their PUBLIC battlefield only; a face-down permanent is
     * counted but never identified. */
    private int annihilatorTake(Card payload) {
        int amount = payload.getKeywordMagnitude(Keyword.ANNIHILATOR);
        if (amount <= 0) return 0;
        int best = 0;
        for (Player opponent : player.getOpponents()) {
            int permanents = 0;
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) permanents++;
            best = Math.max(best, Math.min(amount, permanents));
        }
        return best;
    }

    /** Is this one printed ability body a value effect? */
    private static boolean valueEffect(String body) {
        String api = scriptParam(body, "DB$");
        if (api == null) return false;
        if (VALUE_APIS.contains(api)) return true;
        return VALUE_ZONE_APIS.contains(api) && "Battlefield".equals(scriptParam(body, "Origin$"));
    }

    /** Does this value effect have something to resolve against?
     *
     * <p>A non-targeted effect always does. A targeted one is passed when its
     * {@code ValidTgts$} names a player or an opponent, or when an opponent
     * controls at least one face-up permanent. The restriction string itself is
     * NOT evaluated - the chain is read from printed SVar text and never
     * instantiated, which is v56's own rule - so this is deliberately coarse and
     * is registered as a limitation.</p> */
    private boolean valueTargetAvailable(String body) {
        String targets = scriptParam(body, "ValidTgts$");
        if (targets == null) return true;
        if (targets.contains("Player") || targets.contains("Opponent")) return true;
        for (Player opponent : player.getOpponents())
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
                if (!card.isFaceDown()) return true;
        return false;
    }

    /** Walk a printed {@code Execute$} SVar chain, bounded exactly as
     * {@link #etbChangesBoard} bounds its own, and answer whether any hop is a
     * value effect with something to resolve against. */
    private boolean chainHasValue(Card card, String svar) {
        for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
            String body = card.getSVar(svar);
            if (body == null || body.isEmpty()) break;
            if (valueEffect(body) && valueTargetAvailable(body)) return true;
            svar = scriptParam(body, "SubAbility$");
        }
        return false;
    }

    /** Term 3. A printed enters-the-battlefield trigger of the card itself that
     * acts on the public board or on the opponent. Same trigger shape
     * {@link #etbChangesBoard} matches; a different effect set. */
    private boolean entersWithValue(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParam("Destination"))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            if (chainHasValue(card, trigger.getParam("Execute"))) return true;
        }
        return false;
    }

    /** Term 4. A printed trigger the Breach's end-step SACRIFICE will fire: the
     * card itself leaving OUR battlefield for the graveyard, or a
     * {@code Sacrificed} trigger of itself.
     *
     * <p>{@code Destination$} is accepted as {@code Graveyard}, {@code Any} or
     * absent - {@code sundering_titan.txt} writes its leave trigger as
     * {@code Origin$ Battlefield | Destination$ Any} and a sacrifice does fire
     * it. {@code Origin$ Battlefield} is REQUIRED, and that is what excludes the
     * {@code Origin$ Any} graveyard-shuffle triggers Emrakul, Ulamog and
     * Worldspine Wurm all carry.</p> */
    private boolean diesWithValue(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            if (trigger.getMode() == TriggerType.Sacrificed) {
                if (chainHasValue(card, trigger.getParam("Execute"))) return true;
                continue;
            }
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParamOrDefault("Origin", ""))) continue;
            String destination = trigger.getParam("Destination");
            if (destination != null && !"Graveyard".equals(destination) && !"Any".equals(destination)) continue;
            if (chainHasValue(card, trigger.getParam("Execute"))) return true;
        }
        return false;
    }

    /** What our own board produces on its next untap: every one of our
     * battlefield permanents with a no-cost mana ability, tapped or not. The
     * reanimation follow-up is cast on a LATER turn, so the untapped-only count
     * {@link #ownMana} uses would understate it while the line this turn is
     * paying for the Breach. Own-visible only, and not a colour-aware payment. */
    private int nextUntapMana() {
        int sources = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (SpellAbility ability : card.getManaAbilities())
                if (ability.getPayCosts() != null && ability.getPayCosts().getTotalMana().getCMC() == 0) { sources++; break; }
        }
        return sources;
    }

    /** The spell form of a reanimation: a creature card moved from a graveyard
     * onto the battlefield. {@code reanimate.txt} and {@code persist.txt} name
     * it with {@code ValidTgts$ Creature...}, {@code exhume.txt} with
     * {@code ChangeType$ Creature}. */
    private static boolean reanimationShape(SpellAbility sa) {
        return sa.getApi() == ApiType.ChangeZone
                && "Graveyard".equals(sa.getParam("Origin"))
                && "Battlefield".equals(sa.getParam("Destination"))
                && (sa.getParamOrDefault("ChangeType", "").startsWith("Creature")
                        || sa.getParamOrDefault("ValidTgts", "").startsWith("Creature"));
    }

    /** The Aura form: a permanent whose own enters-trigger chain puts a CREATURE
     * card from a graveyard onto the battlefield ({@code animate_dead.txt}
     * {@code SVar:TrigReanimate}, {@code necromancy.txt} {@code SVar:RaiseDead}).
     *
     * <p>The creature clause is load-bearing and is the design's own wording
     * ("on a creature"). Without it {@code titania_protector_of_argoth.txt} -
     * whose enters trigger is the same {@code ChangeZone | Origin$ Graveyard |
     * Destination$ Battlefield} shape but with {@code ValidTgts$ Land.YouCtrl} -
     * would count as a reanimation follow-up for a creature it can never
     * return. It is the ONLY such false positive among the cube's ten
     * graveyard-to-battlefield permanents, and it is a real cube card.</p>
     *
     * <p>The creature is named two ways, both printed: the chain's own
     * {@code ChangeType$} / {@code ValidTgts$} (Necromancy), or
     * {@code Defined$ Enchanted} on a card whose printed
     * {@code Enchant:Creature...} keyword says what it may be attached to
     * (Animate Dead, which names no target in the chain at all).</p> */
    private static boolean entersReanimates(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParam("Destination"))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            String svar = trigger.getParam("Execute");
            for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
                String body = card.getSVar(svar);
                if (body == null || body.isEmpty()) break;
                if ("ChangeZone".equals(scriptParam(body, "DB$"))
                        && "Graveyard".equals(scriptParam(body, "Origin$"))
                        && "Battlefield".equals(scriptParam(body, "Destination$"))
                        && namesACreature(card, body)) return true;
                svar = scriptParam(body, "SubAbility$");
            }
        }
        return false;
    }

    /** Does this printed zone-change name a creature card? */
    private static boolean namesACreature(Card card, String body) {
        String type = scriptParam(body, "ChangeType$"), targets = scriptParam(body, "ValidTgts$");
        if (type != null && type.startsWith("Creature") || targets != null && targets.startsWith("Creature")) return true;
        return "Enchanted".equals(scriptParam(body, "Defined$")) && card.hasStartOfKeyword("Enchant:Creature");
    }

    /** Term 5's precondition, and the reason it is not simply "a big body".
     * The Breach's end-step sacrifice only sets a reanimation up if the payload
     * can REACH a graveyard and STAY there, and four of the cube's biggest
     * bodies cannot:
     *
     * <ul>
     * <li>{@code blightsteel_colossus.txt} replaces the move itself -
     *     {@code R:Event$ Moved | Destination$ Graveyard | ValidCard$ Card.Self |
     *     ReplaceWith$ DBShuffle}. A REPLACEMENT, not a trigger, so a
     *     trigger-only test would miss it;</li>
     * <li>{@code emrakul_the_aeons_torn.txt},
     *     {@code ulamog_the_infinite_gyre.txt} and {@code worldspine_wurm.txt}
     *     each carry {@code T:Mode$ ChangesZone | Origin$ Any |
     *     Destination$ Graveyard | ValidCard$ Card.Self} executing a
     *     {@code ChangeZone(All) | Origin$ Graveyard | Destination$ Library}
     *     shuffle.</li>
     * </ul>
     *
     * <p>Both shapes are recognised here, by printed property: a self
     * replacement of a move to the graveyard, or a self trigger on reaching the
     * graveyard whose chain moves it back OUT of the graveyard. Without this,
     * a hand of Through the Breach + Blightsteel Colossus + a reanimation spell
     * would rate tier 2 and cast for a body that shuffles itself away. Our own
     * card's printed text only; public graveyard hate is NOT read (registered
     * limitation, inherited from v52's hazard set).</p> */
    private static boolean staysInGraveyard(Card card) {
        for (var replacement : card.getReplacementEffects()) {
            if (replacement.getMode() != forge.game.replacement.ReplacementType.Moved) continue;
            if (!"Graveyard".equals(replacement.getParam("Destination"))) continue;
            if (!replacement.getParamOrDefault("ValidCard", "").startsWith("Card.Self")) continue;
            return false;
        }
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Graveyard".equals(trigger.getParamOrDefault("Destination", ""))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            String svar = trigger.getParam("Execute");
            for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
                String body = card.getSVar(svar);
                if (body == null || body.isEmpty()) break;
                String api = scriptParam(body, "DB$");
                if (VALUE_ZONE_APIS.contains(api)
                        && "Graveyard".equals(scriptParam(body, "Origin$"))
                        && !"Graveyard".equals(scriptParam(body, "Destination$"))) return false;
                svar = scriptParam(body, "SubAbility$");
            }
        }
        return true;
    }

    /** Term 5's first half. A reanimation spell or Aura in our OWN hand whose
     * mana value our own board will produce on its next untap. Our hand and our
     * battlefield only; the graveyard it would fetch from is not consulted,
     * because the body this line is about is not in it yet. */
    private boolean reanimationInHand() {
        int mana = nextUntapMana();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || card.getCMC() > mana) continue;
            for (SpellAbility ability : card.getSpellAbilities())
                if (ability.isSpell() && reanimationShape(ability)) return true;
            if (card.isPermanent() && entersReanimates(card)) return true;
        }
        return false;
    }

    /** v71. A STANDING reanimation engine, by printed property: a permanent
     * whose own printed UPKEEP trigger, or whose own printed activated ability,
     * moves a CREATURE card from a graveyard onto the battlefield - with no
     * spell, no card and (for the trigger half) no mana.
     *
     * <p>{@code portal_to_phyrexia.txt} carries
     * {@code T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You} executing
     * {@code DB$ ChangeZone | Origin$ Graveyard | Destination$ Battlefield |
     * ValidTgts$ Creature}; {@code recurring_nightmare.txt} carries the
     * activated form. v62's term 5 asked only for a reanimation SPELL in our
     * own hand; a permanent that does it every upkeep for free is strictly
     * stronger and was not recognised. The creature clause is
     * {@link #namesACreature}, the same predicate {@link #entersReanimates}
     * uses and for the same reason (Titania returns LANDS with this shape).</p> */
    private static boolean standingEngine(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.Phase) continue;
            if (!"Upkeep".equals(trigger.getParam("Phase"))) continue;
            if (!"You".equals(trigger.getParamOrDefault("ValidPlayer", ""))) continue;
            String svar = trigger.getParam("Execute");
            for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
                String body = card.getSVar(svar);
                if (body == null || body.isEmpty()) break;
                if ("ChangeZone".equals(scriptParam(body, "DB$"))
                        && "Graveyard".equals(scriptParam(body, "Origin$"))
                        && "Battlefield".equals(scriptParam(body, "Destination$"))
                        && namesACreature(card, body)) return true;
                svar = scriptParam(body, "SubAbility$");
            }
        }
        for (SpellAbility ability : card.getSpellAbilities()) {
            if (!ability.isActivatedAbility() || ability.getApi() != ApiType.ChangeZone) continue;
            if (!"Graveyard".equals(ability.getParam("Origin"))
                    || !"Battlefield".equals(ability.getParam("Destination"))) continue;
            if (ability.getParamOrDefault("ValidTgts", "").startsWith("Creature")
                    || ability.getParamOrDefault("ChangeType", "").startsWith("Creature")) return true;
        }
        return false;
    }

    /** v71. Term 5's first half, widened from "a reanimation spell in our own
     * hand" to "or a standing engine already on our own battlefield". Our hand
     * and our battlefield only. Inert wherever no such permanent is in play,
     * which is every fixture this policy has ever run. */
    private boolean reanimationAvailable() {
        if (reanimationInHand()) return true;
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && standingEngine(card)) return true;
        return false;
    }

    /** Term 6. A printed activated ability that draws for a literal life
     * payment we can afford ({@code griselbrand.txt},
     * {@code A:AB$ Draw | Cost$ PayLife<7> | NumCards$ 7}). The cards stay after
     * the end-step sacrifice takes the body, which is the whole point. Our own
     * life total only. */
    private boolean drawsForLife(Card payload) {
        for (SpellAbility ability : payload.getSpellAbilities()) {
            if (!ability.isActivatedAbility() || ability.getApi() != ApiType.Draw) continue;
            Cost cost = ability.getPayCosts();
            if (cost == null) continue;
            for (CostPart part : cost.getCostParts()) {
                if (!(part instanceof CostPayLife)) continue;
                Integer amount = literalAmount(part);
                if (amount != null && player.getLife() > amount) return true;
            }
        }
        return false;
    }

    /** v62's floor, as a tier so the payload ranking and the cast gate ask one
     * question. 0 means "no value at all" - a vanilla beater the public board
     * blocks for nothing, which is the only thing v62 still declines.
     *
     * <p>Tier 4 is v55 R2 / v56 R3 verbatim. Tier 3 is terms 2, 3 and 4 - the
     * board the payload changes whether or not it connects. Tier 2 is terms 5
     * and 6 - value that outlives the body. Own-visible only throughout.</p>
     *
     * <p>Term 5 asks {@link #staysInGraveyard} as well as the power floor,
     * because a reanimation follow-up is worth nothing behind a body that
     * shuffles itself out of the graveyard the moment the Breach sacrifices
     * it.</p> */
    private int breachValueTier(Card candidate, int manaLeft) {
        if (!canAttackForValue(player, candidate, manaLeft)) return 0;
        if (lethalForecast(candidate, manaLeft) != null) return 4;
        if (annihilatorTake(candidate) >= 2) return 3;
        if (entersWithValue(candidate) || diesWithValue(candidate)) return 3;
        if (candidate.getNetPower() >= 7 && staysInGraveyard(candidate) && reanimationAvailable()) return 2;
        if (drawsForLife(candidate)) return 2;
        return 0;
    }

    /** v62. The own-visible payload this Breach should take, or null when no
     * candidate clears the floor.
     *
     * <p>{@link #bestLethalPayload} is asked FIRST and its answer is returned
     * unchanged, so a hand that holds a lethal payload picks exactly the card
     * v56 picked and every lethal row of every suite stays byte-identical. Only
     * when it answers null are the lower tiers consulted, ranked by tier and
     * then by {@link #rank}, the same ordering {@link #bestBomb} uses.</p>
     *
     * <p>The same ranking answers at propose time and at resolution
     * ({@link #choosePayload}), over the same candidate set, exactly as v56
     * arranged for the lethal one.</p> */
    private Card bestValuePayload(SpellAbility ability, int manaLeft, List<Card> pool) {
        Card lethal = bestLethalPayload(ability, manaLeft, pool);
        if (lethal != null) return lethal;
        Card best = null;
        int bestTier = 0, bestRank = -1;
        for (Card candidate : pool) {
            int tier = breachValueTier(candidate, manaLeft);
            if (tier <= 0) continue;
            int value = rank(candidate);
            if (tier < bestTier || tier == bestTier && value <= bestRank) continue;
            best = candidate; bestTier = tier; bestRank = value;
        }
        return best;
    }

    // ------------------------------------- v56 R4: the Show and Tell payoff

    /** The (sub)ability APIs that make an enter-the-battlefield trigger change
     * the board on resolution rather than promise a later attack. Read from the
     * card's own printed script, so this is a property, not a card name. */
    private static final List<String> BOARD_CHANGE_APIS =
            List.of("Sacrifice", "Discard", "LoseLife", "Draw", "Destroy", "GainLife");
    /** How far the printed {@code SubAbility$} chain is followed. Bounded so a
     * malformed or cyclic script cannot spin here. */
    private static final int CHAIN_DEPTH = 8;

    /** R4, tier 1. Does this card have a printed enter-the-battlefield trigger
     * that changes the board when it resolves?
     *
     * <p>Show and Tell is symmetric and the payload has no haste (9 of 9 panel
     * actions: the payload never attacked that turn), so a vanilla beater has to
     * survive a full opponent turn AND the free permanent the opponent just
     * received. The one Show and Tell that won its own game put in Archon of
     * Cruelty, whose trigger forced the opponent to sacrifice the permanent that
     * same Show and Tell had just given it
     * ({@code 2026-09-12-bomb2-conversion-diagnosis/diagnosis.md} R4).</p>
     *
     * <p>Recognised by shape: a {@code ChangesZone} trigger of the card itself
     * with {@code Destination$ Battlefield}, whose executed ability chain names
     * one of {@link #BOARD_CHANGE_APIS}. The chain is read from the printed SVar
     * text rather than instantiated, so nothing is allocated and no trigger
     * state is touched - the v55 lesson from {@code TokenInfo.getProtoType}.</p>
     *
     * <p>Limitation, registered: a wording whose board change hides behind
     * another API (Atraxa, Grand Unifier's reveal-and-take chain is
     * {@code PeekAndReveal}/{@code RepeatEach}/{@code ChangeZone}) is NOT
     * recognised here and reaches tier 2 or the ordinary chooser instead.</p> */
    private static boolean etbChangesBoard(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParam("Destination"))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            String svar = trigger.getParam("Execute");
            for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
                String body = card.getSVar(svar);
                if (body == null || body.isEmpty()) break;
                if (BOARD_CHANGE_APIS.contains(scriptParam(body, "DB$"))) return true;
                svar = scriptParam(body, "SubAbility$");
            }
        }
        return false;
    }

    /** One {@code Key$ value} field of a printed ability script, or null. */
    private static String scriptParam(String body, String key) {
        for (String part : body.split("\\|")) {
            String field = part.trim();
            if (!field.startsWith(key + " ")) continue;
            return field.substring(key.length() + 1).trim();
        }
        return null;
    }

    /** R4, tier 2. A flier that also gains us life or kills what it blocks, while
     * we are the player who is behind. 775 s1 is the registered case: at 3 life
     * facing four attackers the plan put in Worldspine Wurm - the most expensive
     * permanent - over Atraxa, Grand Unifier, and died the following turn. Our
     * own life and the opponent's PUBLIC life only. */
    private boolean stabilisesLowLife(Card card) {
        if (!card.isCreature() || !card.hasKeyword(Keyword.FLYING)) return false;
        if (!card.hasKeyword(Keyword.LIFELINK) && !card.hasKeyword(Keyword.DEATHTOUCH)) return false;
        for (Player opponent : player.getOpponents()) if (player.getLife() < opponent.getLife()) return true;
        return false;
    }

    /** R4. The payload Show and Tell should put in, or null to leave the choice
     * to the ordinary AI - which is tier 3 of the design's ranking and is what
     * a hand of ordinary beaters still gets. */
    private Card bestShowAndTellPayload(List<Card> pool) {
        Card best = null;
        int bestTier = 0;
        for (Card candidate : pool) {
            int tier = etbChangesBoard(candidate) ? 2 : stabilisesLowLife(candidate) ? 1 : 0;
            if (tier <= bestTier) continue;
            best = candidate; bestTier = tier;
        }
        return best;
    }

    // ------------------------------- v56: the resolution-time payload choice

    /** The host card and turn of the cheat-in whose payload this plan proposed,
     * and the mana the R2 gate forecast would be left. {@code -1} means no
     * payload choice is ours. */
    private int payloadHostId = -1, payloadTurn = -1, payloadMana;

    private void armPayload(SpellAbility cast, int manaLeft) {
        payloadHostId = cast.getHostCard().getId();
        payloadTurn = player.getGame().getPhaseHandler().getTurn();
        payloadMana = manaLeft;
    }

    /** Is this resolving ability the one whose payload this plan proposed on
     * this turn? Keyed to the host card's id, exactly as
     * {@code CubeDoomsdayPlan.ownsPileDecision} keys the pile decision, so no
     * other ability - and no other player's ability - can answer true. Show and
     * Tell's opponent-side choice is made by the opponent's own controller and
     * never reaches this class at all. */
    public boolean ownsPayloadChoice(SpellAbility source) {
        return payloadHostId >= 0 && source != null && source.getHostCard() != null
                && source.getHostCard().getId() == payloadHostId
                && payloadTurn == player.getGame().getPhaseHandler().getTurn();
    }

    /** The card this plan wants put onto the battlefield from the offered list,
     * or null to keep the ordinary AI's own choice.
     *
     * <p>{@code options} is the native fetch list as the effect offered it - our
     * own hand, already filtered by the spell's printed {@code ChangeType} - and
     * is narrowed here to the same bomb candidates the propose-time gate ranked,
     * so the two rankings cannot disagree. Called only from
     * {@link CubeComboPlayerController#chooseSingleCardForZoneChange} and only
     * after {@link #ownsPayloadChoice}.</p> */
    public Card choosePayload(SpellAbility source, List<Card> options) {
        boolean breach = source.isSpell() && cheatInShape(source);
        if (!breach && !showAndTellShape(source)) return null;
        List<Card> pool = new ArrayList<>();
        List<Card> candidates = bombCandidates(source, breach);
        for (Card option : options) if (candidates.contains(option)) pool.add(option);
        if (pool.isEmpty()) return null;
        return breach ? bestValuePayload(source, payloadMana, pool) : bestShowAndTellPayload(pool);
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

    // ------------------------------------- v71: the library->battlefield veto

    /** v71 R2. What one artifact in our own library is worth fetching, as a
     * tier in the v62 idiom. Every term is a printed property of the candidate
     * plus the PUBLIC board; nothing reads an opponent's hand or library.
     *
     * <ul>
     * <li><b>3</b> - {@link #entersWithValue}, v62's term 3 verbatim and
     *     type-agnostic (Portal to Phyrexia's {@code DB$ Sacrifice}, Sundering
     *     Titan's {@code DB$ DestroyAll}, Myr Battlesphere's {@code DB$ Token}),
     *     or {@link #standingEngine}, the term-5 extension.</li>
     * <li><b>2</b> - bomb-class by THIS class's own threshold, the one
     *     {@link #bombCandidates} already applies to a card in hand
     *     ({@code evaluateCreature >= BOMB_EVALUATION} or
     *     {@code CMC >= BOMB_CMC}): Blightsteel Colossus, Triplicate Titan,
     *     Sundering Titan.</li>
     * <li><b>1</b> - the card completes another plan's line, asked through the
     *     accessor that plan already exposes
     *     ({@link CubeTopPlan#completingPieceNames}) - Bolas's Citadel and
     *     Mystic Forge, and only while the Top plan is one permission short.
     *     No Top plan internals are read and no Top plan file is edited.</li>
     * <li><b>0</b> - anything else. A vanilla beater is not worth two cards.</li>
     * </ul> */
    private int tinkerPayloadTier(Card candidate) {
        if (entersWithValue(candidate) || standingEngine(candidate)) return 3;
        if (candidate.isCreature() && ComputerUtilCard.evaluateCreature(candidate) >= BOMB_EVALUATION
                || candidate.getCMC() >= BOMB_CMC) return 2;
        if (CubeTopPlan.completingPieceNames(player).contains(candidate.getName())) return 1;
        return 0;
    }

    /** v71 R2. The best payload our own library's COMPOSITION offers this
     * spell, or null when none clears the floor.
     *
     * <p><b>Composition, never order.</b> The candidates are sorted BY NAME and
     * every tie breaks by name (the comparison is strict), so the answer cannot
     * depend on where a card sits in the library - which is the whole reason
     * reading the zone at all is admissible: our registered decklist is
     * own-visible and so is every other zone our own cards can be in, so the
     * library's composition is derivable from own-visible information. Its
     * order is not, and is not read. {@code tinker-portal:deep} is the
     * registered witness.</p> */
    private Card bestTinkerPayload(SpellAbility cast) {
        String type = cast.getParamOrDefault("ChangeType", "Card");
        List<Card> candidates = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Library)) {
            if (card.isValid(type.split(","), player, cast.getHostCard(), cast)) candidates.add(card);
        }
        candidates.sort(java.util.Comparator.comparing(Card::getName));
        Card best = null;
        int bestTier = 0, bestRank = -1;
        for (Card candidate : candidates) {
            int tier = tinkerPayloadTier(candidate);
            if (tier <= 0) continue;
            int value = rank(candidate);
            if (tier < bestTier || tier == bestTier && value <= bestRank) continue;
            best = candidate; bestTier = tier; bestRank = value;
        }
        return best;
    }

    /** v71. Is this one of our own permanents expendable - a rock or a blank
     * body whose loss the board does not feel?
     *
     * <p>By printed property: no printed trigger, no printed replacement, and
     * no activated ability other than a mana ability. Ornithopter, a Mox, Sol
     * Ring and Lotus Petal pass; Sensei's Divining Top (two activated non-mana
     * abilities), Bolas's Citadel, Mystic Forge and Retrofitter Foundry do not.
     * A token always passes - it is not a card and cannot be drawn again.</p>
     *
     * <p><b>Static abilities are deliberately NOT consulted, and probe-1 is the
     * reason.</b> The first draft of this predicate also required
     * {@code getStaticAbilities().isEmpty()} and refused EVERY position: Forge
     * attaches an intrinsic static ability for a printed KEYWORD, so a plain
     * Ornithopter reports {@code statics=1} for its Flying
     * ({@code BOMB_SHAPE ... card=Ornithopter triggers=0 statics=1
     * replacements=0 activated=0}, probe-2, which is why that line is in the
     * fixture). A printed {@code S:} line and a keyword's own static cannot be
     * told apart from the live card, so the clause is dropped rather than
     * guessed at. The consequence is registered: an artifact whose ONLY printed
     * ability is a static one - a Torpor Orb, an Ensnaring Bridge - counts as
     * expendable here.</p> */
    private static boolean expendable(Card card) {
        if (card.isToken()) return true;
        if (!card.getTriggers().isEmpty() || !card.getReplacementEffects().isEmpty()) return false;
        for (SpellAbility ability : card.getSpellAbilities())
            if (ability.isActivatedAbility() && !ability.isManaAbility()) return false;
        return true;
    }

    /** v71. Which of our own permanents the NATIVE payment would sacrifice for
     * this cost, forecast exactly rather than guessed: the same read-only query
     * {@code AiCostDecision.visit(CostSacrifice)} makes at payment time, with
     * the cost part's own type and amount and the ability's own target. The
     * plan cannot CHOOSE the sacrifice - that choice lives in
     * {@code ComputerUtil.chooseSacrificeType}, outside this increment's file
     * budget - so it can only refuse a cast whose payment would take a card
     * another plan is relying on. Registered as a limitation. */
    private static forge.game.card.CardCollection forecastSacrifice(Player ai, SpellAbility cast) {
        CostSacrifice sacrifice = artifactSacrifice(cast);
        if (sacrifice == null) return null;
        return ComputerUtil.chooseSacrificeType(ai, sacrifice.getType(), cast, cast.getTargetCard(), false,
                sacrifice.getAbilityAmount(cast), null);
    }

    /** Observability only: the last {@code tinker:} decline printed, as
     * (this Tinker object, turn, phase, reason), so the line prints once per
     * position rather than once per priority pass. The object's identity is
     * part of the KEY and never of the OUTPUT, which is what keeps two games in
     * one JVM from suppressing each other's line. Never read by a decision. */
    private static String tinkerDeclined = "";

    private static void declineTinkerOnce(Player ai, SpellAbility cast, String reason) {
        PhaseHandler phases = ai.getGame().getPhaseHandler();
        String stamp = System.identityHashCode(cast.getHostCard()) + "/" + phases.getTurn()
                + "/" + phases.getPhase() + "/" + reason;
        if (stamp.equals(tinkerDeclined)) return;
        tinkerDeclined = stamp;
        System.err.println("CUBE_BOMB_PLAN decline=" + reason
                + " card=" + cast.getHostCard().getName().replace(' ', '_')
                + " turn=" + phases.getTurn() + " phase=" + phases.getPhase());
    }

    /** v71. The veto. Refuse an ordinary Tinker cast for exactly two reasons,
     * both read from our own zones and the public board:
     *
     * <ol>
     * <li>{@code tinker:no-value} - no artifact in our own library clears
     *     {@link #tinkerPayloadTier}'s floor. The claims probe's
     *     {@code tinker-vanilla} row is the case: Default spends Tinker, an
     *     Ornithopter and {@code {2}{U}} on a 5/3 the public board already
     *     blocks.</li>
     * <li>{@code tinker:sac-piece} - the artifact the native payment would take
     *     is not {@link #expendable}. The probe's {@code tinker-sac-choice} row
     *     is the case: the card's own preference list skips the Lotus Petal by
     *     name and eats Sensei's Divining Top instead.</li>
     * </ol>
     *
     * <p>Reached through the generic hook this class already owns at
     * {@code SpellAbilityAi:87} rather than through the v52 D4 hook, which sits
     * inside {@code ChangeZoneAi}'s {@code AILogic$ BeforeCombat} branch that
     * Tinker never enters. Gated on {@link CubeComboAi#enabled}, so the Default
     * arm is byte-identical.</p> */
    private static boolean declineArtifactFetch(Player ai, SpellAbility sa) {
        if (!tinkerShape(sa)) return false;
        CubeBombPlan plan = new CubeBombPlan(ai);
        if (plan.bestTinkerPayload(sa) == null) {
            declineTinkerOnce(ai, sa, "tinker:no-value");
            return true;
        }
        forge.game.card.CardCollection payment = forecastSacrifice(ai, sa);
        if (payment == null) return false;
        for (Card card : payment) {
            if (expendable(card)) continue;
            declineTinkerOnce(ai, sa, "tinker:sac-piece");
            return true;
        }
        return false;
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
     * <p>Kept simple on purpose: our own turn, a phase no later than the
     * beginning of combat, an empty stack, attackers not yet declared, a body
     * that can actually attack, and no public uncast-entry exile. The "unless it
     * wins or blocks lethal at instant speed" exception the brief allows is
     * deliberately NOT implemented - this is a veto arm, and a veto that never
     * invents a line is worth more here than one that models a combat it cannot
     * verify.</p>
     *
     * <p><b>v59 R1, the window.</b> v52 wrote the phase test as a whitelist of
     * MAIN1 and COMBAT_BEGIN, which also refused our OWN upkeep and draw steps.
     * The registered defect was the OPPONENT'S upkeep, and the isPlayerTurn line
     * above already covers it. The ordinary-path divergence diagnosis
     * ({@code 2026-09-12-ordinary-path-divergence-diagnosis}) attributes 11 of 11
     * D4 divergences to those two steps - Default acted at our own UPKEEP (9) or
     * DRAW (2) - and in 10 of them the candidate re-took the IDENTICAL action one
     * priority later at MAIN1. The only effect was that one MyRandom roll was not
     * made, desynchronising the matched pair's shared seeded stream for the rest
     * of the game. The window is now the native gate's own test, {@code not after
     * COMBAT_BEGIN}, so the veto no longer differs from Default on a pass where it
     * has nothing to say. A body cheated in at our upkeep has haste from the
     * script's own {@code SubAbility$ DBPump} and still reaches the attack step;
     * at upkeep we have not yet drawn or made a land drop, so the value test below
     * sees no MORE mana than it would at MAIN1 - conservative, never looser.</p> */
    public static boolean declineCheatIn(Player ai, SpellAbility sa) {
        if (!CubeComboAi.enabled(ai) || !cheatInShape(sa)) return false;
        PhaseHandler phases = ai.getGame().getPhaseHandler();
        if (!phases.isPlayerTurn(ai)) return true;
        if (phases.getPhase().isAfter(PhaseType.COMBAT_BEGIN)) return true;
        if (!ai.getGame().getStack().isEmpty()) return true;
        // Implied by the line above, and kept verbatim: "we already declared
        // attackers" is one of v52 D4's registered predicates in its own words.
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
        // v71. This method's call site is the policy's only GENERIC one - every
        // ability of a cube-combo seat passes through it - so the Tinker veto
        // is asked here, first and in its own method. The RemoveCounter clause
        // below is untouched and every D3 receipt with it. The method's NAME is
        // now narrower than what it does; renaming it needs SpellAbilityAi.java,
        // which is outside this increment's file budget (registered follow-up).
        if (CubeComboAi.enabled(ai) && declineArtifactFetch(ai, sa)) return true;
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
            if (legendaryBounceVisible(player)) { decline = "depths:legendary-bounce"; continue; }
            // Do not spend into a public land-destruction answer at all: it can
            // destroy either half in response and the activation fizzles.
            if (landDestructionVisible(player)) { decline = "depths:land-destruction"; continue; }
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
                if (exiledOnUncastEntry(player)) { decline = "show-and-tell:priest"; continue; }
                if (bomb.getType().isLegendary() && legendaryBounceVisible(player)) { decline = "show-and-tell:legendary-bounce"; continue; }
                // v56 R4: the payload is picked at resolution, so the choice is
                // claimed here and answered by choosePayload. No gate above
                // changes: which payoff we take is a better question than
                // whether to cast, and v55's cast decision is preserved.
                armPayload(cast, manaLeftAfter(player, cast));
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
                if (exiledOnUncastEntry(player)) { decline = "breach:priest"; continue; }
                int manaLeft = manaLeftAfter(player, cast);
                if (!canAttackForValue(player, bomb, manaLeft)) { decline = "breach:no-attack-value"; continue; }
                if (bomb.getType().isLegendary() && legendaryBounceVisible(player)) { decline = "breach:legendary-bounce"; continue; }
                // v55 R2. The payload leaves at the beginning of the next end
                // step (observed six times of nine), so a Breach that does not
                // end the game spends two cards for one hit and hands the board
                // straight back. v55 answered that with "only cast when this
                // turn's attack is lethal against the public board"; v62 keeps
                // the concern and replaces the answer.
                //
                // v56 R3: the question is no longer "is the body the native
                // chooser will take lethal" but "is ANY own-visible payload
                // lethal", because the resolution hook now takes that one. The
                // not-lethal decline is unchanged for a hand where none is, and
                // v55's payload-not-selectable decline becomes this cast.
                //
                // v62. The question is no longer "is any own-visible payload
                // lethal" but "does any own-visible payload clear the value
                // floor": annihilator against a public board, an enter or dies
                // trigger the cheat-in and its end-step sacrifice will fire, a
                // reanimation follow-up behind a real body, or an activated draw
                // whose cards outlive the body. A hand with a lethal payload is
                // unchanged, because the lethal ranking is asked first.
                if (bestValuePayload(cast, manaLeft, bombCandidates(cast, true)) == null) {
                    decline = "breach:no-value";
                    declineOnce("breach:no-value");
                    continue;
                }
                armPayload(cast, manaLeft);
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
                || !(phases.is(PhaseType.MAIN1, player) || phases.is(PhaseType.MAIN2, player))) return decline(gateReason());
        decline = "no-bomb-line";
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

    // --------------------------------------------------- v60 completing pieces

    /** The cube's two Depths halves and its three cheat-in spells, as FETCH
     * CANDIDATES only. Both roles are still recognised on a live card by shape
     * ({@link #zeroCounterPayoffType}, {@link #hasLandCloneAbility},
     * {@link #cheatInShape}, {@link #showAndTellShape}); a name list is what a
     * card we do not have leaves us, and it is confined to naming a candidate. */
    private static final List<String> DEPTHS_PAYOFF_NAMES = List.of("Dark Depths");
    private static final List<String> DEPTHS_CLONE_NAMES = List.of("Thespian's Stage");
    private static final List<String> CHEAT_IN_NAMES =
            List.of("Show and Tell", "Through the Breach", "Sneak Attack");

    /** v60 - which single card, fetched from our own library, would complete
     * one of this class's lines. Two lines are checked, in {@link #nextAction}'s
     * own order.
     *
     * <p><b>A, the Depths route (D3/R6).</b> Its two halves are a zero-counter
     * payoff permanent and a land-clone permanent, each own-visible in our own
     * hand or on our own battlefield - exactly the zones and exactly the shape
     * tests {@link #depthsLandAction} already uses. Exactly one missing names
     * the other. Both halves are LANDS, so the fetched piece is played as a
     * land drop, which is the drop {@link #depthsLandAction} itself proposes.</p>
     *
     * <p><b>B, the cheat-in routes (D1/D2).</b> A bomb we can already see in
     * our own hand, and no own-visible ability that puts a creature from hand
     * onto the battlefield. The bomb test is {@link #bombCandidates}' own
     * threshold and its own legend-rule refusal, minus the printed
     * {@code ChangeType} filter - which cannot be applied, because the spell
     * that would carry it is the card we do not have yet. Deliberately wider
     * there and nowhere else; the real cast still applies that filter.</p>
     *
     * <p>Our own hand, our own battlefield and the PUBLIC battlefield only. No
     * library contents or order, no opponent hand, and a face-down card is
     * never identified. Entry gate only: the hazard gates
     * ({@link #exiledOnUncastEntry}, {@link #legendaryBounceVisible},
     * {@link #landDestructionVisible}) and {@code canAttackForValue} are this
     * plan's business on the turn it acts.</p> */
    static List<String> completingPieceNames(Player player) {
        boolean payoff = false, clone = false;
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield))
            for (Card card : player.getCardsIn(zone)) {
                if (card.isFaceDown()) continue;
                boolean isPayoff = zeroCounterPayoffType(card) != null;
                if (isPayoff) payoff = true;
                else if (hasLandCloneAbility(card)) clone = true;
            }
        if (payoff != clone) return payoff ? DEPTHS_CLONE_NAMES : DEPTHS_PAYOFF_NAMES;
        boolean bomb = false;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || !card.isPermanent()) continue;
            if (!(card.isCreature() && ComputerUtilCard.evaluateCreature(card) >= BOMB_EVALUATION
                    || card.getCMC() >= BOMB_CMC)) continue;
            if (card.getType().isLegendary() && player.isCardInPlay(card.getName())) continue;
            bomb = true;
            break;
        }
        if (!bomb) return List.of();
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield))
            for (Card card : player.getCardsIn(zone)) {
                if (card.isFaceDown()) continue;
                for (SpellAbility ability : card.getSpellAbilities())
                    if (cheatInShape(ability) || showAndTellShape(ability)) return List.of();
            }
        return CHEAT_IN_NAMES;
    }
}
