package forge.ai;

import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterType;
import forge.game.cost.Cost;
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
 * not at all - those go through native {@code StaticAbilityCantAttackBlock}.</p> */
public final class CubeBombPlan {
    /** A creature worth cheating in. Emrakul, the Aeons Torn evaluates at about
     * 1020; 400 admits Griselbrand, Ulamog and Archon of Cruelty and excludes
     * ordinary beaters. CMC >= 8 is the brief's own bomb threshold and is the
     * alternative for a non-creature permanent. */
    private static final int BOMB_EVALUATION = 400, BOMB_CMC = 8;
    /** Per-turn action cap, matching the other plans' bounded-action discipline. */
    private static final int ACTION_CAP = 4;
    private static final Pattern ZERO_COUNTER = Pattern.compile("Card\\.Self\\+counters_EQ0_(\\w+)");

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
     * {@code priest} or {@code no-attack-value}. The three routes are
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
        String type = ability.getParamOrDefault("ChangeType", "Card");
        Card best = null;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || creatureOnly && !card.isCreature()) continue;
            if (!card.isValid(type.split(","), player, ability.getHostCard(), ability)) continue;
            if (!card.isPermanent()) continue;
            boolean bomb = card.isCreature() && ComputerUtilCard.evaluateCreature(card) >= BOMB_EVALUATION
                    || card.getCMC() >= BOMB_CMC;
            if (!bomb) continue;
            // The legend rule would eat our own copy before it ever attacked.
            if (card.getType().isLegendary() && player.isCardInPlay(card.getName())) continue;
            if (best == null || rank(card) > rank(best)) best = card;
        }
        return best;
    }

    /** One ordering for both halves of {@link #bestBomb}: a creature by the
     * same native evaluation the hidden-origin chooser uses, a non-creature
     * permanent by mana value, which is all that gate admits it on. */
    private static int rank(Card card) {
        return card.isCreature() ? ComputerUtilCard.evaluateCreature(card) : card.getCMC();
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
                if (!canAttackForValue(player, bomb, manaLeftAfter(player, cast))) { decline = "breach:no-attack-value"; continue; }
                if (bomb.getType().isLegendary() && legendaryBounceVisible(player)) { decline = "breach:legendary-bounce"; continue; }
                return audit("through-the-breach", cast);
            }
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
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, ability, null);
        if (!played) {
            failedTurn = turn;
            System.err.println("CUBE_BOMB_PLAN native-payment-failed turn=" + turn
                    + " card=" + ability.getHostCard().getName().replace(' ', '_'));
        }
        return played;
    }
}
