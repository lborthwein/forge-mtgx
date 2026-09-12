package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.CostExile;
import forge.game.cost.CostTap;
import forge.game.cost.CostPartMana;
import forge.game.cost.PaymentDecision;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.GameLossReason;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Breach/zero-cost mana artifact/Freeze turn plan. Counts are a conservative resource
 * forecast, not a rules simulator or forced-win proof. All actions, escape
 * costs, storm copies and responses execute in the native engine. Only own
 * hand/public zones, library SIZES and public storm count are consulted.
 *
 * <p>v64 adds three routes BEHIND the v41 Brain Freeze route, for the
 * catalogue families the plan did not execute: High Tide and Frantic Search as
 * fuel for that same Brain Freeze terminal, and the Wheel of Fortune loop with
 * four terminals (Brain Freeze, Tendrils of Agony, Thassa's Oracle, each of
 * them optionally paid for by our own Hullbreacher's Treasures). Every route
 * forecasts a win from own-visible information BEFORE its first irreversible
 * action, and the v41 route is evaluated first on every pass so a line that
 * already wins is never postponed.</p>
 *
 * <p>v75 adds R0 {@link #breachSequence} IN FRONT of them: the breach2 opening
 * panel measured {@code planEntryActions = 0} - Underworld Breach reached our
 * own battlefield on 35 passes and every one of those entries was the ORDINARY
 * AI's cast - and the Breach sacrifices itself at the beginning of the end
 * step, so R1 and R2, which both require it already on the battlefield, were
 * strictly downstream of a cast this plan never made. R0 makes the entry the
 * plan's own, on a board where the R1 forecast proves the terminal over the
 * post-Breach counts. v75 also broadens the tutor-facing completing name
 * ({@link #completingPieceNames}), adds this family's
 * {@link #wouldConvert} predicate for v74's steering gate, and counts
 * colourless mana in {@link #wheelTerminal}'s pool.</p> */
public final class CubeBreachPlan {
    private static final String BREACH = "Underworld Breach", LED = "Lion's Eye Diamond", FREEZE = "Brain Freeze";
    private static final List<String> ENGINES = List.of("Black Lotus", LED, "Lotus Petal");
    /** v64 route cards. None of these is a {@link #key} card: adding them to
     * {@code key} would change {@link #fuel}, which the v41 route's own
     * {@code breach-not-ready:fuel=<n>} token reports. They are protected from
     * being spent as escape fuel through {@link #routeKeys} instead, which is
     * empty on every v41 pass. */
    private static final String WHEEL = "Wheel of Fortune", TIDE = "High Tide", FRANTIC = "Frantic Search",
            TENDRILS = "Tendrils of Agony", ORACLE = "Thassa's Oracle";
    /** Opposing permanents that take our own draws away, read from the public
     * battlefield only. */
    private static final String HULLBREACHER = "Hullbreacher", NARSET = "Narset, Parter of Veils";
    private static final String TREASURE = "Treasure Token";
    private static final List<String> ROUTE_CARDS = List.of(WHEEL, TIDE, FRANTIC, TENDRILS, ORACLE);
    /** Wheel of Fortune draws seven each; rule 704.5b makes a draw from an
     * empty library a loss, so the plan never proposes a wheel below this. */
    private static final int WHEEL_DRAW = 7;
    private final Player player;
    private SpellAbility selected;
    private Player freezeTarget;
    private Player freezeOpponent;
    private int selfCopiesRemaining;
    private final Map<SpellAbility, Player> copyTargets = new IdentityHashMap<>();
    private Card reservedEngine;
    private int turn = -1, actions, failedTurn = -1;
    /** v64, per turn: how many High Tides this plan has resolved (the second
     * one is real but unmodelled) and how many wheels it has cast (the progress
     * predicate distinguishes "never started" from "started and stalled"). */
    private int tideCasts, wheelsCast;
    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeDoomsdayPlan#declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code action-cap},
     * {@code failed-this-turn}, {@code multiplayer}, {@code no-mill-route},
     * {@code missing=<our own missing half>},
     * {@code finisher-untargetable}, {@code breach-not-ready:fuel=<n>},
     * {@code engine-disabled}, {@code breach-unaffordable},
     * {@code engine-unaffordable}, {@code freeze-unaffordable},
     * {@code no-self-mill-room} or {@code target-illegal}. {@code fuel} is the
     * count of non-key cards in OUR OWN graveyard, which is the quantity
     * {@link #breachReady} already reads. The opponent's mill room is a public
     * quantity this plan already consults, and the token deliberately reports
     * only the route word, never the number.</p>
     *
     * <p>v64 adds {@code tide-already-cast}, {@code tide-no-gain},
     * {@code tide-unaffordable}, {@code frantic-no-gain},
     * {@code frantic-unaffordable}, {@code wheel-decks-us},
     * {@code wheel-draw-denied}, {@code wheel-no-terminal},
     * {@code wheel-unaffordable}, {@code wheel-breach-unaffordable},
     * {@code wheel-engine-unaffordable} and {@code wheel-forecast-lost}. A v64
     * route writes a token ONLY when its own route card is own-visible and
     * {@link #breachReady} is true; otherwise the token the v41 route wrote
     * stands unchanged, which is what preserves every pre-v64 decline line.
     * v75 adds {@code sequence-no-gain}, {@code sequence-engine-disabled} and
     * {@code sequence-unaffordable} under the same discipline: R0 writes none
     * of them unless an Underworld Breach is in OUR OWN HAND and a High Tide
     * is own-visible on the same pass, so every pre-v75 decline line stands
     * byte for byte.
     * {@code wheel-draw-denied} names the class of opposing permanent, read
     * from the public battlefield, never a hand or a library.</p> */
    private String decline = "other check=breach-plan";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline = reason; return null; }
    /** v64: the token a v64 route wrote on this pass, or null when no v64 route
     * was gated. {@link #nextAction} copies it over {@link #decline} only when
     * it is non-null, so a board that reaches no v64 route keeps the v41
     * route's token byte for byte. */
    private String routeDecline;
    private SpellAbility routeDecline(String reason) { routeDecline = reason; return null; }
    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order, after that guard has already
     * decided to decline. */
    private String gateReason() {
        var phase = player.getGame().getPhaseHandler();
        if (failedTurn == turn) return "failed-this-turn";
        if (actions >= 64) return "action-cap";
        if (player.cantWin()) return "cant-win";
        if (!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        if (!(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))) return "phase";
        return "multiplayer";
    }

    /** A card name as one log token: our own missing half, never an opponent
     * card and never a library read. */
    private static String token(String name) { return name.replace(' ', '_'); }

    public CubeBreachPlan(Player player) { this.player = player; }

    /** v49, predicate-only: one hand card this instance must pretend it does
     * not have, so {@link #discardProtectedCards} can ask the plan's own gate
     * what it would say without that card. Set only on the throwaway instances
     * the two static predicates build; the live plan never sets it. */
    private Card excluded;

    /** v64: names the route acting on THIS pass must not exile as its own fuel.
     * Empty on every v41 pass and on both static predicates, so
     * {@link #escapeChoices} is byte-identical there. */
    private Set<String> routeKeys = Set.of();

    /** v75, predicate-only: a HYPOTHETICAL hand this instance reads instead of
     * our own live one. Set by {@link #wouldConvert} alone, on a throwaway plan
     * object it constructs itself, so every live decision path - every
     * {@link #nextAction}, every route, every bridge - sees {@code null} here
     * and reads the real hand exactly as v41..v74 did. The same shape as v74's
     * {@code CubeDoomsdayPlan.handOverride}. */
    private java.util.Collection<Card> handOverride;

    private Card find(String name, ZoneType zone) {
        Iterable<Card> cards = handOverride != null && zone == ZoneType.Hand
                ? handOverride : player.getCardsIn(zone);
        for (Card card : cards)
            if (card != excluded && !card.isFaceDown() && name.equals(card.getName())) return card;
        return null;
    }

    private boolean key(Card card) { return ENGINES.contains(card.getName()) || FREEZE.equals(card.getName()); }

    private Card engine() {
        for (String name : ENGINES) for (ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard)) {
            Card card = find(name, zone);
            if (card != null) return card;
        }
        return null;
    }

    private <T> T reserveEngine(Supplier<T> action) {
        if (reservedEngine == null || !reservedEngine.isInPlay()) return action.get();
        var memory = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        boolean already = AiCardMemory.isRememberedCard(player, reservedEngine, memory);
        if (!already) AiCardMemory.rememberCard(player, reservedEngine, memory);
        try { return action.get(); }
        finally { if (!already) AiCardMemory.forgetCard(player, reservedEngine, memory); }
    }

    private int fuel() {
        int count = 0;
        for (Card card : player.getCardsIn(ZoneType.Graveyard)) if (!key(card)) count++;
        return count;
    }

    /** Brain Freeze where this plan can use it: our own hand, else our own
     * graveyard (it escapes from there under Breach). */
    private Card freeze() {
        Card freeze = find(FREEZE, ZoneType.Hand);
        return freeze != null ? freeze : find(FREEZE, ZoneType.Graveyard);
    }

    /** Underworld Breach as a usable half: already on our own battlefield, or
     * in our own hand together with the graveyard fuel and own library size
     * the hand route needs. This is the single definition of that half - both
     * {@link #nextAction} and {@link #completingPieceNames} read it, so the
     * missing-piece predicate cannot drift from the plan's own thresholds. */
    private boolean breachReady(int fuel) {
        if (find(BREACH, ZoneType.Battlefield) != null) return true;
        return find(BREACH, ZoneType.Hand) != null && fuel >= 6
                && player.getCardsIn(ZoneType.Library).size() >= 4;
    }

    /** Which single card, fetched from our own library, would complete this
     * plan's entry gate - the gate being Brain Freeze plus a Lotus-type engine
     * plus a usable Underworld Breach. Empty unless exactly one of those three
     * halves is missing from our own visible zones, and empty for a missing
     * Breach whose hand route could not be paid for anyway. Reads our own
     * hand, battlefield and graveyard and our own library SIZE only; never
     * library contents or order, never an opponent zone.
     *
     * <p>Deliberately still the v41 gate in v64: the new routes get no tutor
     * selection, because this predicate and {@link #discardProtectedCards} are
     * read by four other suites whose receipts must not move.</p>
     *
     * <p><b>v75</b> adds exactly one clause, in {@link #completingNames}, and
     * only where {@link #missingPieces} answers nothing at all. The breach2
     * panel measured {@code missing=Brain_Freeze} on 264 of 370 breach
     * declines - 71% - on a deck holding three admitted tutors, and the
     * singleton terminal was never fetched: with the Breach and a Lotus-type
     * engine in our own hand below the hand route's fuel gate the v41 count
     * makes the gate TWO halves short, so this method named nothing and no
     * tutor could be steered, while the plan's own decline on the same pass
     * read {@code missing=Brain_Freeze}. {@link #discardProtectedCards} keeps
     * calling {@link #missingPieces} and does not see the clause: its contract
     * is the last obtainable copy of a gate that is ALREADY complete, which is
     * a different question and is read by four suites whose receipts must not
     * move.</p> */
    static java.util.List<String> completingPieceNames(Player player) {
        return new CubeBreachPlan(player).completingNames();
    }

    /** v75 - {@link #missingPieces} plus the Brain Freeze clause. Named only
     * when the v41 gate reports nothing, no Brain Freeze is own-visible to
     * this plan at all, a Lotus-type fuel source IS own-visible, and an
     * Underworld Breach is in our own hand or on our own battlefield. A copy
     * in the GRAVEYARD is deliberately not counted, for v68's own recorded
     * reason: an Underworld Breach there grants escape to nothing. Own hand,
     * battlefield and graveyard and our own library SIZE only. */
    private java.util.List<String> completingNames() {
        java.util.List<String> v41 = missingPieces();
        if (!v41.isEmpty() || freeze() != null || engine() == null) return v41;
        return breachEntryLive(fuel()) && millableOpponent() ? java.util.List.of(FREEZE) : v41;
    }

    /** v75 - is a usable Underworld Breach reachable THIS TURN apart from the
     * mana? Three ways, and the third is what v75 itself adds:
     * <ol>
     * <li>{@link #breachReady}: already on our own battlefield, or in our own
     *     hand under the v41 hand route's own {@code fuel >= 6} and
     *     {@code library >= 4} gate;</li>
     * <li>(the same method, hand branch) - kept verbatim, so nothing the v41
     *     route admits is narrowed here;</li>
     * <li><b>R0</b>: in our own hand with a High Tide own-visible and at least
     *     one untapped Island. {@link #breachSequence} enters from that board
     *     and needs NO graveyard fuel at all, which is exactly why the fuel
     *     threshold can be dropped in this branch and only in this branch.</li>
     * </ol>
     *
     * <p>Our own hand, battlefield and graveyard and our own library SIZE
     * only.</p> */
    private boolean breachEntryLive(int fuel) {
        if (breachReady(fuel)) return true;
        return find(BREACH, ZoneType.Hand) != null && routeCard(TIDE) != null && islands() >= 1;
    }

    /** The opponent half of {@link #freezeRoute}'s {@code no-mill-route}
     * clause, as a predicate: one opponent, we can still win, their library is
     * not empty and they can lose to being milled. Public zones only. */
    private boolean millableOpponent() {
        if (player.cantWin() || player.getOpponents().size() != 1) return false;
        Player opponent = player.getOpponents().get(0);
        return !opponent.getCardsIn(ZoneType.Library).isEmpty()
                && !opponent.cantLoseCheck(GameLossReason.Milled);
    }

    /** v75 - the v74 convertibility predicate for THIS family, the same shape
     * as {@code CubeDoomsdayPlan.wouldConvert}: would the v41 Brain Freeze
     * route actually be live against the hand we would hold after a search
     * resolves, apart from the mana? Read by
     * {@code CubeComboAi.familyConverts}, which is reached only from v63's C2
     * hand-destination path. No zone is written, nothing is printed and no RNG
     * is consumed. */
    static boolean wouldConvert(Player player, java.util.Collection<Card> hypotheticalHand) {
        CubeBreachPlan plan = new CubeBreachPlan(player);
        plan.handOverride = hypotheticalHand;
        return plan.routeLiveApartFromMana();
    }

    /** {@link #wouldConvert}'s body, an instance method so it reads through
     * this class's own predicates and the {@link #handOverride}.
     *
     * <p>Every clause is one of {@link #freezeRoute}'s own STRUCTURAL declines
     * - {@code no-mill-route}, {@code missing=Brain_Freeze},
     * {@code finisher-untargetable}, {@code missing=Lotus-engine} and
     * {@code breach-not-ready:fuel=<n>} - and the mana declines
     * ({@code breach-unaffordable}, {@code engine-unaffordable},
     * {@code freeze-unaffordable}) are the ones deliberately dropped, exactly
     * as v74 drops Doomsday's payment.</p>
     *
     * <p><b>The fuel threshold is kept where it is the only entry.</b>
     * {@code breach-not-ready:fuel=<n>} is not a mana shortfall: it counts
     * non-key cards in our own graveyard, it is the threshold
     * {@link #breachReady} itself publishes, and v68's hold already treats it
     * as a real horizon. A later turn does not supply it the way it supplies an
     * untapped land, and dropping it outright would reintroduce in this family
     * the very defect v74 measured in Doomsday - a tutor spent on a combo piece
     * with no live route. {@link #breachEntryLive} therefore keeps it for the
     * v41 hand route and drops it ONLY down R0's own entry, which needs no
     * fuel because the High Tide, not the graveyard, is what pays.</p> */
    private boolean routeLiveApartFromMana() {
        if (!millableOpponent()) return false;
        Player opponent = player.getOpponents().get(0);
        Card freeze = freeze();
        if (freeze == null || !freezeTargetable(freeze, opponent)) return false;
        if (engine() == null) return false;
        return breachEntryLive(fuel());
    }

    private java.util.List<String> missingPieces() {
        int fuel = fuel();
        boolean engine = engine() != null, freeze = freeze() != null, breach = breachReady(fuel);
        if ((engine ? 1 : 0) + (freeze ? 1 : 0) + (breach ? 1 : 0) != 2) return java.util.List.of();
        if (!freeze) return java.util.List.of(FREEZE);
        if (!engine) return ENGINES;
        // The Breach half is the missing one. Fetching it is only worth a
        // selection where the hand route's own gate would then be satisfied.
        return fuel >= 6 && player.getCardsIn(ZoneType.Library).size() >= 4
                ? java.util.List.of(BREACH) : java.util.List.of();
    }

    /** v49: is this plan's entry gate currently complete - Brain Freeze, a
     * Lotus-type engine and a usable Underworld Breach, each where the plan can
     * use it? The single definition, read by {@link #discardProtectedCards} so
     * the protection cannot drift from {@link #breachReady}'s own thresholds. */
    private boolean gateReady() {
        int fuel = fuel();
        return engine() != null && freeze() != null && breachReady(fuel);
    }

    /** v49, the Breach-diagnosis C2 shape: cards in our own hand that are the
     * LAST copy this plan can reach of a half of an entry gate that is
     * otherwise ready. Empty unless the gate is complete right now, and a card
     * is named only when the same gate stops being complete once that card is
     * taken away - which is exactly "last obtainable copy", computed from the
     * plan's own zone definitions rather than a card-name rule.
     *
     * <p>Deliberately narrow, and deliberately NOT "never discard a combo
     * piece": a copy still on the battlefield or in the graveyard, where
     * {@link #engine} and {@link #freeze} already accept it, is not named; a
     * gate that is already a card short is not defended; and the extra
     * graveyard fuel the discard would itself supply is not modelled, so a half
     * that would only become ready BECAUSE of the discard is never protected.
     * Own hand, battlefield and graveyard and our own library SIZE only.</p> */
    static CardCollection discardProtectedCards(Player player) {
        CardCollection kept = new CardCollection();
        CubeBreachPlan plan = new CubeBreachPlan(player);
        if (!plan.gateReady()) return kept;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            plan.excluded = card;
            boolean stillReady = plan.gateReady();
            boolean stillCompletable = !plan.missingPieces().isEmpty();
            plan.excluded = null;
            if (!stillReady && stillCompletable) kept.add(card);
        }
        return kept;
    }

    private CardCollection escapeChoices(CostExile cost, SpellAbility ability) {
        CardCollection valid = CardLists.getValidCards(player.getCardsIn(cost.getFrom()),
                cost.getType().split(";"), player, ability.getHostCard(), ability);
        valid = CardLists.filter(valid, CardPredicates.canExiledBy(ability, false));
        valid = ComputerUtilCost.paymentChoicesWithoutTargets(valid, ability, player);
        valid.removeIf(card -> key(card) || routeKeys.contains(card.getName()));
        return valid;
    }

    private boolean enoughReservedFuel(SpellAbility ability) {
        for (var part : ability.getPayCosts().getCostParts()) {
            if (part instanceof CostExile cost) {
                if (cost.zoneRestriction != 1 || cost.getFrom().size() != 1
                        || cost.getFrom().get(0) != ZoneType.Graveyard
                        || escapeChoices(cost, ability).size() < cost.getAbilityAmount(ability)) return false;
            }
        }
        return true;
    }

    private SpellAbility spell(Card card) {
        if (card == null) return null;
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility ability = original.copy(player);
            if (!ability.isSpell() || card.isInZone(ZoneType.Graveyard) && !ability.isEscape()) continue;
            if (CubeComboAi.canPlayNative(ability, player) && enoughReservedFuel(ability)
                    && CubeComboAi.canPayCost(ability, player, false)) return ability;
        }
        return null;
    }

    private SpellAbility crack(Card card) { return crack(card, "U", MagicColor.BLUE); }

    /** v64: the same activation, for a named colour. The v41 call sites pass
     * blue and are unchanged. */
    private SpellAbility crack(Card card, String symbol, byte color) {
        if (card == null) return null;
        for (SpellAbility original : card.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (CubeComboAi.canPlayNative(ability, player) && ability.getManaPart().canProduce(symbol, ability)
                    && CubeComboAi.canPayCost(ability, player, false)) {
                ability.setManaExpressChoice(ColorSet.fromMask(color));
                return ability;
            }
        }
        return null;
    }

    private SpellAbility floatBlue() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (key(card)) continue;
            for (SpellAbility original : card.getManaAbilities()) {
                SpellAbility ability = original.copy(player);
                if (!CubeComboAi.canPlayNative(ability, player) || ability.getPayCosts().getTotalMana().getCMC() != 0
                        || !ability.getManaPart().canProduce("U", ability)
                        || ability.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostTap || p instanceof CostPartMana))
                        || !CubeComboAi.canPayCost(ability, player, false)) continue;
                ability.setManaExpressChoice(ColorSet.fromMask(MagicColor.BLUE));
                return ability;
            }
        }
        return null;
    }

    /** Forecast only the known mana-artifact/Freeze loop, using already floating blue.
     * No unknown draws, hidden library order or virtual execution. Taxes,
     * replacement effects and interaction can invalidate it: each next action
     * is checked afresh, and failures do not count as wins.
     *
     * <p>v64 admits a null engine - a High-Tide-fuelled loop needs no Lotus at
     * all - by treating it as an engine that yields nothing. Every v41 caller
     * passes a non-null engine, so their behaviour is unchanged.</p> */
    private boolean enoughToMill(int fuel, int blue, Card engine, boolean freezeInHand, int storm, int remaining) {
        boolean engineInPlay = engine != null && engine.isInPlay();
        boolean engineInHand = engine != null && engine.isInZone(ZoneType.Hand);
        int yield = engine == null ? 0 : engine.getName().equals("Lotus Petal") ? 1 : 3;
        boolean discardsHand = engine != null && engine.getName().equals(LED);
        for (int step = 0; step < 64 && remaining > 0; step++) {
            if (blue < 2) {
                if (yield == 0) return false;
                if (!engineInPlay) {
                    if (!engineInHand) { if (fuel < 3) return false; fuel -= 3; }
                    engineInHand = false;
                    storm++;
                }
                blue += yield;
                engineInPlay = false;
                // LED discards a held Freeze; it must subsequently escape.
                if (discardsHand) freezeInHand = false;
            } else {
                if (!freezeInHand) { if (fuel < 3) return false; fuel -= 3; }
                freezeInHand = false;
                blue -= 2;
                remaining -= 3 * ++storm;
            }
        }
        return remaining <= 0;
    }

    private SpellAbility select(SpellAbility ability) {
        if (ability != null) { selected = ability; actions++; }
        return ability;
    }

    public SpellAbility nextAction() {
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) {
            turn = phase.getTurn(); actions = 0; selected = null; freezeTarget = null; freezeOpponent = null;
            reservedEngine = null; selfCopiesRemaining = 0; copyTargets.clear();
            tideCasts = 0; wheelsCast = 0;
        }
        if (failedTurn == turn || actions >= 64 || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return decline(gateReason());
        decline = "other check=breach-plan";
        routeDecline = null;
        routeKeys = Set.of();
        Player opponent = player.getOpponents().get(0);
        int fuel = fuel(), storm = game.getStack().getSpellsCastThisTurn().size();
        // R1/R2 are evaluated BEFORE the v41 route because that route is greedy:
        // it floats blue and casts the Freeze the moment it can, so a fuel
        // improvement offered afterwards would never be reachable. They are safe
        // in front of it because each carries its own "the v41 route does not
        // already win this" clause - the two-sided forecast of tideRoute and
        // franticRoute - and because neither writes a token, or an action, on a
        // board with no High Tide own-visible.
        // v75 R0 runs in front of R1/R2 for the reason their own comment
        // gives about the v41 route: it is the only route that can put OUR OWN
        // Underworld Breach on the battlefield below the hand route's fuel
        // gate, and R1/R2 cannot evaluate at all until it has. It writes
        // nothing and does nothing on a board with no Breach in our own hand
        // and no High Tide own-visible.
        SpellAbility action = breachSequence(opponent, fuel, storm);
        if (action != null) return action;
        action = tideRoute(opponent, fuel, storm);
        if (action != null) return action;
        action = franticRoute(opponent, fuel, storm);
        if (action != null) return action;
        action = freezeRoute(opponent);
        if (action != null) return action;
        action = wheelRoute(opponent, fuel, storm);
        if (action != null) return action;
        if (routeDecline != null) decline = routeDecline;
        return null;
    }

    /** The v41 Brain Freeze route, moved verbatim out of {@code nextAction}:
     * same checks, same order, same decline tokens. */
    private SpellAbility freezeRoute(Player opponent) {
        var game = player.getGame();
        int remaining = opponent.getCardsIn(ZoneType.Library).size();
        if (remaining == 0 || opponent.cantLoseCheck(GameLossReason.Milled)) return decline("no-mill-route");
        Card engine = engine();
        boolean fromHand = find(FREEZE, ZoneType.Hand) != null;
        Card freeze = freeze();
        if (freeze == null || engine == null)
            return decline("missing=" + (freeze == null ? token(FREEZE) : "Lotus-engine"));
        // A mill-out isn't a plan if its eventual target is currently illegal.
        boolean targetable = false;
        for (SpellAbility original : freeze.getSpellAbilities()) {
            SpellAbility ability = original.copy(player);
            if (ability.isSpell() && ability.canTarget(opponent)) targetable = true;
        }
        if (!targetable) return decline("finisher-untargetable");
        int blue = player.getManaPool().getAmountOfColor(MagicColor.BLUE);
        int fuel = fuel(), storm = game.getStack().getSpellsCastThisTurn().size();
        if (find(BREACH, ZoneType.Battlefield) == null) {
            Card breach = find(BREACH, ZoneType.Hand);
            if (!breachReady(fuel)) return decline("breach-not-ready:fuel=" + fuel);
            // An on-board disabled engine is not a reason to spend Breach.
            if (engine.isInPlay() && crack(engine) == null) return decline("engine-disabled");
            reservedEngine = engine.isInPlay() ? engine : null;
            SpellAbility entry = select(reserveEngine(() -> spell(breach)));
            return entry == null ? decline("breach-unaffordable") : entry;
        }
        if (blue < 2) {
            SpellAbility floating = floatBlue();
            if (floating != null) return select(floating);
            int yield = engine.getName().equals("Lotus Petal") ? 1 : 3;
            int activations = (2 - blue + yield - 1) / yield;
            int escapes = Math.max(0, activations - (engine.isInPlay() || engine.isInZone(ZoneType.Hand) ? 1 : 0));
            int freezeFuel = fromHand && !engine.getName().equals(LED) ? 0 : 3;
            if (fuel >= 3 * escapes + freezeFuel) {
                SpellAbility blueSource = select(engine.isInPlay() ? crack(engine) : spell(engine));
                if (blueSource == null) return decline("engine-unaffordable");
                return blueSource;
            }
        }
        SpellAbility cast = spell(freeze);
        if (cast == null) return decline("freeze-unaffordable");
        int copies = storm + 1, selfCopies = 0;
        if (3 * copies < remaining && !enoughToMill(fuel, blue, engine, fromHand, storm, remaining)) {
            // Copies may target different players. Find the minimum self-mill
            // that replenishes enough fuel for the remaining opponent kill;
            // never force an entire storm batch into our nearly empty library.
            int maxSelf = Math.min(copies, Math.max(0, (player.getCardsIn(ZoneType.Library).size() - 1) / 3));
            if (maxSelf == 0) return decline("no-self-mill-room");
            int payment = 0;
            for (var part : cast.getPayCosts().getCostParts()) if (part instanceof CostExile cost)
                payment += cost.getAbilityAmount(cast);
            selfCopies = maxSelf;
            for (int count = 1; count <= maxSelf; count++) {
                if (enoughToMill(fuel - payment + 3 * count, Math.max(0, blue - 2), engine, false,
                        storm + 1, remaining - 3 * (copies - count))) {
                    selfCopies = count;
                    break;
                }
            }
        }
        Player target = selfCopies > 0 ? player : opponent;
        if (!cast.canTarget(target)) return decline("target-illegal");
        cast.resetTargets();
        cast.getTargets().add(target);
        if (!cast.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(cast)) return decline("target-illegal");
        freezeTarget = target;
        freezeOpponent = opponent;
        selfCopiesRemaining = Math.max(0, selfCopies - 1); // the original already has its target
        copyTargets.clear();
        return select(cast);
    }

    // ---------------------------------------------------------------- v64

    /** A v64 route card where this plan can use it: our own hand, else our own
     * graveyard (escapable under a Breach that is already on the battlefield). */
    private Card routeCard(String name) {
        Card card = find(name, ZoneType.Hand);
        return card != null ? card : find(name, ZoneType.Graveyard);
    }

    /** Our own route cards sitting in our graveyard are never counted as escape
     * fuel by a v64 forecast, because {@link #routeKeys} will not let the
     * payment spend them. {@link #fuel} itself is untouched: the v41 token
     * {@code breach-not-ready:fuel=<n>} reports that number. */
    private int graveyardRouteCards() {
        int count = 0;
        for (Card card : player.getCardsIn(ZoneType.Graveyard)) if (ROUTE_CARDS.contains(card.getName())) count++;
        return count;
    }

    private boolean isTreasure(Card card) { return TREASURE.equals(card.getName()); }

    private int treasures() {
        int count = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) if (isTreasure(card)) count++;
        return count;
    }

    /** v64: one zero-cost mana ability of a permanent we control that can make
     * the named colour. Unlike {@link #floatBlue} this admits an ability that
     * sacrifices its own source, which is exactly what a Treasure costs; it is
     * a separate method precisely so the v41 route's own source selection does
     * not change. */
    private SpellAbility floatFrom(Card card, String symbol, byte color) {
        if (key(card)) return null;
        for (SpellAbility original : card.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (!CubeComboAi.canPlayNative(ability, player) || ability.getPayCosts().getTotalMana().getCMC() != 0
                    || !ability.getManaPart().canProduce(symbol, ability)
                    || !CubeComboAi.canPayCost(ability, player, false)) continue;
            ability.setManaExpressChoice(ColorSet.fromMask(color));
            return ability;
        }
        return null;
    }

    /** How much mana of one colour our own side can make right now without
     * spending fuel: what is already floating, plus one per non-Treasure
     * permanent we control that can make it. Treasures are counted separately
     * so no source is counted twice. */
    private int colorMana(String symbol, byte color) {
        int count = player.getManaPool().getAmountOfColor(color);
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (isTreasure(card)) continue;
            if (floatFrom(card, symbol, color) != null) count++;
        }
        return count;
    }

    /** Untapped Islands we control that can make blue right now. Each of these
     * yields TWO blue once a High Tide has resolved. */
    private int islands() {
        int count = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (card.getType().hasSubtype("Island") && floatFrom(card, "U", MagicColor.BLUE) != null) count++;
        return count;
    }

    /** Islands we control that are tapped - the ones Frantic Search can untap. */
    private int tappedIslands() {
        int count = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && card.isTapped() && card.getType().hasSubtype("Island")) count++;
        return count;
    }

    /** The escape exile amount for this card, read from the card's own escape
     * ability rather than assumed. The printed three is used only when no
     * escape ability is visible yet (Breach still in hand). */
    private int escapeCost(Card card) {
        if (card == null) return 3;
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            if (!original.isEscape()) continue;
            int amount = 0;
            for (var part : original.getPayCosts().getCostParts())
                if (part instanceof CostExile cost) amount += cost.getAbilityAmount(original);
            if (amount > 0) return amount;
        }
        return 3;
    }

    /** A loop card is admitted by NAME and by SCRIPT SHAPE. Timetwister, Echo
     * of Eons and Time Spiral shuffle the graveyard back into the library:
     * they delete the fuel, delete every escape target and refill the
     * opponent's library. Rejecting the shape means a renamed reprint cannot
     * smuggle that behaviour into the loop. */
    private boolean wheelShape(Card card) {
        for (SpellAbility spell : card.getSpellAbilities()) {
            if (!spell.isSpell()) continue;
            for (SpellAbility part = spell; part != null; part = part.getSubAbility()) {
                if (part.getApi() != ApiType.ChangeZoneAll) continue;
                String origin = part.getParam("Origin"), destination = part.getParam("Destination");
                if (origin != null && origin.contains("Graveyard") && "Library".equals(destination)) return false;
            }
        }
        return true;
    }

    private boolean opposingDrawDenial(Player opponent) {
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && (HULLBREACHER.equals(card.getName()) || NARSET.equals(card.getName()))) return true;
        return false;
    }

    private boolean targetOpponent(SpellAbility ability, Player opponent) {
        if (!ability.canTarget(opponent)) return false;
        ability.resetTargets();
        ability.getTargets().add(opponent);
        return ability.isTargetNumberValid() && StaticAbilityMustTarget.meetsMustTargetRestriction(ability);
    }

    private boolean freezeTargetable(Card freeze, Player opponent) {
        for (SpellAbility original : freeze.getSpellAbilities()) {
            SpellAbility ability = original.copy(player);
            if (ability.isSpell() && ability.canTarget(opponent)) return true;
        }
        return false;
    }

    /** v75: mana sources we control that this plan's BLUE forecast does not
     * already count - untapped non-Treasure permanents carrying a zero-cost
     * mana ability that is playable and payable right now and that cannot make
     * blue. They are what pays for Underworld Breach before a High Tide is
     * worth anything, and counting them is what stops {@link #breachSequence}
     * charging the Breach to the Islands the Tide is about to double. */
    private int nonBlueSources() {
        int count = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || isTreasure(card) || key(card)) continue;
            if (floatFrom(card, "U", MagicColor.BLUE) != null) continue;
            if (zeroCostMana(card)) count++;
        }
        return count;
    }

    /** Does this permanent carry a zero-cost mana ability we could use right
     * now? The same legality and payment discipline {@link #floatFrom} applies,
     * without the colour question. */
    private boolean zeroCostMana(Card card) {
        for (SpellAbility original : card.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (!CubeComboAi.canPlayNative(ability, player) || ability.getPayCosts().getTotalMana().getCMC() != 0
                    || !CubeComboAi.canPayCost(ability, player, false)) continue;
            return true;
        }
        return false;
    }

    /** v75: mana we can make right now that is COLOURLESS and no colour at all
     * - Sol Ring, Mana Crypt, Ancient Tomb - plus floating colourless. These
     * raise real castability ({@code canPayCost} prices them) but never raise
     * {@link #colorMana}, which is what {@link #wheelTerminal}'s pool was built
     * from, so the v64 forecast counted them at ZERO and could answer
     * {@code wheel-no-terminal} on a board that pays for the wheel. Registered
     * as a known conservatism before the breach2 panel ran
     * ({@code 2026-09-12-breach2-dev-deck/rationale.md}, item 4).
     *
     * <p>Counted once each. Treasures are excluded because {@link #treasures}
     * already counts them; a source that can make ANY colour is excluded
     * because {@link #colorMana} already counts it wherever its colour
     * matters.</p> */
    private int colorlessSources() {
        int count = player.getManaPool().getAmountOfColor(MagicColor.COLORLESS);
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || isTreasure(card) || key(card)) continue;
            if (colorlessOnly(card)) count++;
        }
        return count;
    }

    private boolean colorlessOnly(Card card) {
        for (SpellAbility original : card.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (!CubeComboAi.canPlayNative(ability, player) || ability.getPayCosts().getTotalMana().getCMC() != 0
                    || !CubeComboAi.canPayCost(ability, player, false)) continue;
            var mana = ability.getManaPart();
            if (!mana.canProduce("C", ability)) continue;
            boolean colored = false;
            for (String symbol : List.of("W", "U", "B", "R", "G"))
                if (mana.canProduce(symbol, ability)) colored = true;
            if (!colored) return true;
        }
        return false;
    }

    /** R0 - v75's Breach sequencing. The breach2 opening panel measured
     * {@code planEntryActions = 0}: this plan never once cast its own
     * Underworld Breach in 32 games, while the ordinary AI cast it 9 times at
     * moments of its own choosing. Because the Breach sacrifices itself at the
     * beginning of the end step, R1 and R2 - which both require it ALREADY on
     * the battlefield - were strictly downstream of an entry this plan did not
     * make, and fired 0 times on 35 passes with the Breach down.
     *
     * <p>R0's only action is the Breach cast itself. The High Tide, the Frantic
     * Search and the terminal are taken afterwards by the unchanged R1/R2,
     * from a board where the Breach really is on the battlefield. The
     * alternative - relaxing R1's own gate to "or we would cast it this turn" -
     * is deliberately NOT taken: R1 would then resolve a High Tide with the
     * Breach still in hand, and a Tide spent in front of an unaffordable Breach
     * is exactly the wasted card v68's hold exists to refuse.</p>
     *
     * <p>Gate discipline, as in v64: a board with no Underworld Breach in OUR
     * OWN HAND, or no High Tide own-visible, returns null with NO token and no
     * action, so every pre-v75 decline line is preserved byte for byte.
     * Frantic Search alone is not admitted, because R2's own gate needs a
     * RESOLVED High Tide.</p>
     *
     * <p>When the forecast fails, R0 declines and v68's {@link #holdBreach}
     * governs the ordinary AI's cast exactly as before - clause 5 asks a fresh
     * probe for an action and still gets none. When the forecast succeeds the
     * probe now proposes the Breach and the hold RELEASES, which is correct:
     * the cast is no longer a wasted card.</p> */
    private SpellAbility breachSequence(Player opponent, int fuel, int storm) {
        if (find(BREACH, ZoneType.Battlefield) != null) return null;
        Card breach = find(BREACH, ZoneType.Hand);
        if (breach == null) return null;
        Card tide = routeCard(TIDE);
        if (tide == null) return null;
        int remaining = opponent.getCardsIn(ZoneType.Library).size();
        Card freeze = freeze();
        if (freeze == null || remaining == 0 || opponent.cantLoseCheck(GameLossReason.Milled)
                || !freezeTargetable(freeze, opponent)) return routeDecline("sequence-no-gain");
        int blue = player.getManaPool().getAmountOfColor(MagicColor.BLUE);
        int islands = islands(), other = Math.max(0, colorMana("U", MagicColor.BLUE) - blue - islands);
        // The Breach's own cost comes out of the mana this blue forecast does
        // NOT count first, then out of the single blue sources, and only last
        // out of the Islands - each of which is worth two once the Tide has
        // resolved, so spending them first would both misprice the line and be
        // the payment the native engine is least likely to choose.
        int cost = breach.getCMC();
        cost -= Math.min(nonBlueSources(), cost);
        int take = Math.min(other, cost); other -= take; cost -= take;
        take = Math.min(blue, cost); blue -= take; cost -= take;
        take = Math.min(islands, cost); islands -= take; cost -= take;
        // The Tide's own {U}, priced exactly as R1 prices it.
        int islandPays = blue == 0 && other == 0 ? 1 : 0;
        if (islands < islandPays || blue + islands + other < 1) return routeDecline("sequence-no-gain");
        int routeFuel = fuel - graveyardRouteCards();
        int tideCost = tide.isInZone(ZoneType.Graveyard) ? escapeCost(tide) : 0;
        Card engine = engine();
        boolean fromHand = find(FREEZE, ZoneType.Hand) != null;
        // The Breach and the Tide are both spells cast this turn, so the storm
        // the terminal will see is two higher than the one on this pass.
        if (!enoughToMill(routeFuel - tideCost,
                blue + 2 * (islands - islandPays) + other - (1 - islandPays),
                engine, fromHand, storm + 2, remaining)) return routeDecline("sequence-no-gain");
        // An on-board disabled engine is not a reason to spend the Breach -
        // freezeRoute's own clause, in this route's token namespace.
        if (engine != null && engine.isInPlay() && crack(engine) == null)
            return routeDecline("sequence-engine-disabled");
        routeKeys = Set.copyOf(ROUTE_CARDS);
        reservedEngine = engine != null && engine.isInPlay() ? engine : null;
        SpellAbility entry = select(reserveEngine(() -> spell(breach)));
        if (entry == null) {
            routeKeys = Set.of();
            reservedEngine = null;
            return routeDecline("sequence-unaffordable");
        }
        return entry;
    }

    /** R1 - High Tide as fuel for the v41 Brain Freeze terminal. Gated on a
     * Breach already on our battlefield and a High Tide own-visible, so no
     * board without a High Tide can reach a v64 token. Casts the Tide only
     * when the mill forecast succeeds WITH the blue it adds and fails without
     * it: the win is proved before the card is spent, and a position the v41
     * route already wins never spends one. */
    private SpellAbility tideRoute(Player opponent, int fuel, int storm) {
        if (find(BREACH, ZoneType.Battlefield) == null) return null;
        Card tide = routeCard(TIDE);
        if (tide == null) return null;
        if (tideCasts > 0) return routeDecline("tide-already-cast");
        int remaining = opponent.getCardsIn(ZoneType.Library).size();
        Card freeze = freeze();
        if (freeze == null || remaining == 0 || opponent.cantLoseCheck(GameLossReason.Milled)
                || !freezeTargetable(freeze, opponent)) return routeDecline("tide-no-gain");
        int blue = player.getManaPool().getAmountOfColor(MagicColor.BLUE);
        int islands = islands(), other = Math.max(0, colorMana("U", MagicColor.BLUE) - blue - islands);
        // The Tide's own {U} comes from floating blue or another blue source
        // when there is one; otherwise an Island pays for it BEFORE the effect
        // exists, so that Island yields one, not two.
        int islandPays = blue == 0 && other == 0 ? 1 : 0;
        if (islands < islandPays || blue + islands + other < 1) return routeDecline("tide-unaffordable");
        int routeFuel = fuel - graveyardRouteCards();
        int cost = tide.isInZone(ZoneType.Graveyard) ? escapeCost(tide) : 0;
        Card engine = engine();
        boolean fromHand = find(FREEZE, ZoneType.Hand) != null;
        boolean without = enoughToMill(routeFuel, blue + islands + other, engine, fromHand, storm, remaining);
        boolean with = enoughToMill(routeFuel - cost, blue + 2 * (islands - islandPays) + other - (1 - islandPays),
                engine, fromHand, storm + 1, remaining);
        if (without || !with) return routeDecline("tide-no-gain");
        routeKeys = Set.copyOf(ROUTE_CARDS);
        SpellAbility cast = select(spell(tide));
        if (cast == null) { routeKeys = Set.of(); return routeDecline("tide-unaffordable"); }
        return cast;
    }

    /** R2 - Frantic Search as the untapper inside a turn that already has a
     * High Tide up: untapping three Islands returns six blue for the three it
     * spends. Same two-sided forecast as R1, and the same gate discipline: no
     * High Tide own-visible, no token. The loot's own discard is the native
     * controller's decision, which v49's discard ownership already guards. */
    private SpellAbility franticRoute(Player opponent, int fuel, int storm) {
        if (find(BREACH, ZoneType.Battlefield) == null || tideCasts == 0) return null;
        Card tide = routeCard(TIDE);
        Card frantic = routeCard(FRANTIC);
        if (tide == null || frantic == null) return null;
        int remaining = opponent.getCardsIn(ZoneType.Library).size();
        Card freeze = freeze();
        if (freeze == null || remaining == 0 || opponent.cantLoseCheck(GameLossReason.Milled)
                || !freezeTargetable(freeze, opponent)) return routeDecline("frantic-no-gain");
        int tapped = Math.min(3, tappedIslands());
        // Frantic Search draws two: never from a library that cannot pay them.
        if (tapped == 0 || player.getCardsIn(ZoneType.Library).size() < 2) return routeDecline("frantic-no-gain");
        int blue = player.getManaPool().getAmountOfColor(MagicColor.BLUE);
        int islands = islands(), other = Math.max(0, colorMana("U", MagicColor.BLUE) - blue - islands);
        int routeFuel = fuel - graveyardRouteCards();
        int cost = frantic.isInZone(ZoneType.Graveyard) ? escapeCost(frantic) : 0;
        Card engine = engine();
        boolean fromHand = find(FREEZE, ZoneType.Hand) != null;
        int now = blue + 2 * islands + other;
        boolean without = enoughToMill(routeFuel, now, engine, fromHand, storm, remaining);
        boolean with = enoughToMill(routeFuel - cost + 2, now - 3 + 2 * tapped, engine, fromHand, storm + 1, remaining);
        if (without || !with || now < 3) return routeDecline("frantic-no-gain");
        routeKeys = Set.copyOf(ROUTE_CARDS);
        SpellAbility cast = select(spell(frantic));
        if (cast == null) { routeKeys = Set.of(); return routeDecline("frantic-unaffordable"); }
        return cast;
    }

    /** R3 - the Wheel of Fortune loop. Gated on a Wheel own-visible and a
     * usable Breach, so a board without a Wheel can never reach a v64 token.
     * A terminal must be proved from own-visible counts before the first
     * irreversible action; the step actually taken is re-decided from the live
     * state on every pass. */
    private SpellAbility wheelRoute(Player opponent, int fuel, int storm) {
        Card wheel = routeCard(WHEEL);
        if (wheel == null || !wheelShape(wheel) || !breachReady(fuel)) return null;
        // A terminal that wins RIGHT NOW is taken before another wheel is even
        // considered: wheeling again spends fuel and hands over seven cards.
        SpellAbility now = terminalNow(opponent, storm);
        if (now != null) return now;
        now = terminalMana(opponent, storm);
        if (now != null) return now;
        if (opposingDrawDenial(opponent)) return routeDecline("wheel-draw-denied");
        if (player.getCardsIn(ZoneType.Library).size() < WHEEL_DRAW)
            return stalled(wheelsCast > 0 ? "wheel-forecast-lost" : "wheel-decks-us");
        if (wheelTerminal(opponent, fuel, storm, wheel) == null)
            return stalled(wheelsCast > 0 ? "wheel-forecast-lost" : "wheel-no-terminal");
        routeKeys = Set.copyOf(ROUTE_CARDS);
        Card engine = engine();
        if (find(BREACH, ZoneType.Battlefield) == null) {
            Card breach = find(BREACH, ZoneType.Hand);
            if (engine != null && engine.isInPlay() && crack(engine, "R", MagicColor.RED) == null)
                return routeDecline("wheel-engine-unaffordable");
            reservedEngine = engine != null && engine.isInPlay() ? engine : null;
            SpellAbility entry = select(reserveEngine(() -> spell(breach)));
            return entry == null ? routeDecline("wheel-breach-unaffordable") : entry;
        }
        SpellAbility cast = spell(wheel);
        if (cast != null) return select(cast);
        if (engine == null) return routeDecline("wheel-unaffordable");
        SpellAbility mana = select(engine.isInPlay() ? crack(engine, "R", MagicColor.RED) : spell(engine));
        return mana == null ? routeDecline("wheel-engine-unaffordable") : mana;
    }

    /** The progress predicate of CubeThopterPlan, in this plan's shape: once a
     * wheel has resolved, a position whose forecast no longer proves a terminal
     * is abandoned for the turn rather than retried. */
    private SpellAbility stalled(String reason) {
        if (wheelsCast > 0) {
            failedTurn = turn;
            System.err.println("CUBE_BREACH_PLAN wheel stopped-no-progress turn=" + turn + " reason=" + reason);
        }
        return routeDecline(reason);
    }

    /** The four terminals, evaluated against the LIVE state: Brain Freeze when
     * the storm count already mills the opponent out, Tendrils when it already
     * drains them out, Thassa's Oracle when our own library is already empty.
     * Our own Hullbreacher is not a terminal - it is the Treasure engine that
     * pays for one of these. */
    private SpellAbility terminalNow(Player opponent, int storm) {
        int oppLibrary = opponent.getCardsIn(ZoneType.Library).size();
        Card freeze = freeze();
        if (freeze != null && oppLibrary > 0 && !opponent.cantLoseCheck(GameLossReason.Milled)
                && 3L * (storm + 1) >= oppLibrary) {
            routeKeys = Set.copyOf(ROUTE_CARDS);
            SpellAbility cast = spell(freeze);
            if (cast != null && targetOpponent(cast, opponent)) {
                freezeTarget = opponent; freezeOpponent = opponent; selfCopiesRemaining = 0; copyTargets.clear();
                return select(cast);
            }
            routeKeys = Set.of();
        }
        Card tendrils = routeCard(TENDRILS);
        if (tendrils != null && opponent.canLoseLife() && 2L * (storm + 1) >= opponent.getLife()) {
            routeKeys = Set.copyOf(ROUTE_CARDS);
            SpellAbility cast = spell(tendrils);
            if (cast != null && targetOpponent(cast, opponent)) return select(cast);
            routeKeys = Set.of();
        }
        Card oracle = routeCard(ORACLE);
        if (oracle != null && player.getCardsIn(ZoneType.Library).isEmpty()) {
            routeKeys = Set.copyOf(ROUTE_CARDS);
            SpellAbility cast = spell(oracle);
            if (cast != null) return select(cast);
            routeKeys = Set.of();
        }
        return null;
    }

    /** A terminal whose arithmetic already wins but whose own cost is not yet
     * payable is not a reason to wheel again: make its colour instead, from the
     * Lotus-type engine, exactly as the v41 route makes blue for the Freeze.
     * Returns null when no terminal is arithmetically live or no engine is
     * reachable, in which case the caller falls through to the loop. */
    private SpellAbility terminalMana(Player opponent, int storm) {
        int oppLibrary = opponent.getCardsIn(ZoneType.Library).size();
        Card freeze = freeze();
        String symbol = null;
        byte color = 0;
        if (freeze != null && oppLibrary > 0 && !opponent.cantLoseCheck(GameLossReason.Milled)
                && 3L * (storm + 1) >= oppLibrary) { symbol = "U"; color = MagicColor.BLUE; }
        else if (routeCard(TENDRILS) != null && opponent.canLoseLife()
                && 2L * (storm + 1) >= opponent.getLife()) { symbol = "B"; color = MagicColor.BLACK; }
        else if (routeCard(ORACLE) != null && player.getCardsIn(ZoneType.Library).isEmpty()) {
            symbol = "U"; color = MagicColor.BLUE;
        }
        if (symbol == null) return null;
        Card engine = engine();
        if (engine == null) return null;
        routeKeys = Set.copyOf(ROUTE_CARDS);
        SpellAbility mana = select(engine.isInPlay() ? crack(engine, symbol, color) : spell(engine));
        if (mana == null) routeKeys = Set.of();
        return mana;
    }

    /** Bounded forecast of the wheel loop over own-visible counts only: our
     * fuel, our storm, both library SIZES, our hand size, our Treasures and
     * the opponent's public life. No library order and no library content are
     * read, and no forecast depends on the identity of a drawn card. Returns
     * the name of the first terminal the loop proves, or null. */
    private String wheelTerminal(Player opponent, int fuel, int storm, Card wheel) {
        Card engine = engine();
        int engineYield = engine == null ? 0 : engine.getName().equals("Lotus Petal") ? 1 : 3;
        boolean discardsHand = engine != null && LED.equals(engine.getName());
        boolean engineInPlay = engine != null && engine.isInPlay();
        boolean engineInHand = engine != null && engine.isInZone(ZoneType.Hand);
        int engineEscape = escapeCost(engine), wheelEscape = escapeCost(wheel);
        boolean wheelInHand = wheel.isInZone(ZoneType.Hand);
        boolean hullbreacher = find(HULLBREACHER, ZoneType.Battlefield) != null;
        Card freeze = freeze(), tendrils = routeCard(TENDRILS), oracle = routeCard(ORACLE);
        boolean millable = !opponent.cantLoseCheck(GameLossReason.Milled)
                && freeze != null && freezeTargetable(freeze, opponent);
        boolean drainable = opponent.canLoseLife() && tendrils != null;
        int life = opponent.getLife();
        int blue = colorMana("U", MagicColor.BLUE), black = colorMana("B", MagicColor.BLACK);
        int freezeEscape = escapeCost(freeze), tendrilsEscape = escapeCost(tendrils), oracleEscape = escapeCost(oracle);
        // Red we can make right now without spending fuel, less what casting
        // Underworld Breach from our hand would take out of it first.
        Card breachInHand = find(BREACH, ZoneType.Battlefield) == null ? find(BREACH, ZoneType.Hand) : null;
        int pool = Math.max(0, colorMana("R", MagicColor.RED)
                - (breachInHand == null ? 0 : breachInHand.getCMC()));
        // v75: the colourless half of the pool. The subtraction above is
        // deliberately NOT allowed to draw on it, which over-charges red by up
        // to the Breach's generic half and can only WITHHOLD a proposal.
        int colorless = colorlessSources();
        int f = fuel - graveyardRouteCards(), s = storm, treasure = treasures();
        int ourLibrary = player.getCardsIn(ZoneType.Library).size();
        int opponentLibrary = opponent.getCardsIn(ZoneType.Library).size();
        int hand = player.getCardsIn(ZoneType.Hand).size() - (breachInHand == null ? 0 : 1);
        for (int iteration = 0; iteration < 8; iteration++) {
            if (ourLibrary < WHEEL_DRAW) return null;
            // v75: the wheel's cost is priced as its printed shape rather
            // than as a lump of three, so the COLOURLESS half of the pool can
            // pay the generic half. Red pays the coloured symbol first, then a
            // Treasure; the generic half is then paid from colourless mana,
            // from what is left of the red, and from Treasures. With
            // colorless == 0 every residual here is arithmetically identical to
            // v64's two lines, which is what keeps every existing wheel row
            // byte for byte.
            int colored = 1, generic = 2; // {2}{R}
            int take = Math.min(pool, colored); pool -= take; colored -= take;
            take = Math.min(treasure, colored); treasure -= take; colored -= take;
            take = Math.min(colorless, generic); colorless -= take; generic -= take;
            take = Math.min(pool, generic); pool -= take; generic -= take;
            take = Math.min(treasure, generic); treasure -= take; generic -= take;
            int need = colored + generic;
            while (need > 0) {
                if (engineYield == 0) return null;
                if (!engineInPlay) {
                    if (engineInHand) engineInHand = false;
                    else { if (f < engineEscape) return null; f -= engineEscape; }
                    s++;
                }
                engineInPlay = false;
                need -= engineYield;
                // Lion's Eye Diamond discards our hand as part of its cost, so a
                // Wheel still in hand lands in the graveyard and must escape.
                if (discardsHand) { f += hand; hand = 0; wheelInHand = false; }
            }
            if (wheelInHand) wheelInHand = false;
            else { if (f < wheelEscape) return null; f -= wheelEscape; }
            s++;
            f += hand; hand = 0;
            ourLibrary -= WHEEL_DRAW; hand = WHEEL_DRAW;
            if (hullbreacher) treasure += WHEEL_DRAW;
            else opponentLibrary = Math.max(0, opponentLibrary - WHEEL_DRAW);
            if (millable && 3L * (s + 1) >= opponentLibrary && opponentLibrary > 0
                    && payable(1, 1, blue, f, treasure, engineYield, engineEscape, freezeEscape, colorless)) return "freeze";
            if (drainable && 2L * (s + 1) >= life
                    && payable(2, 2, black, f, treasure, engineYield, engineEscape, tendrilsEscape, colorless)) return "tendrils";
            if (oracle != null && ourLibrary == 0
                    && payable(0, 2, blue, f, treasure, engineYield, engineEscape, oracleEscape, colorless)) return "oracle";
        }
        return null;
    }

    /** Can the terminal itself be paid for at the end of the forecast? The
     * terminal escapes from our graveyard, so it needs its own exile amount in
     * fuel; a Treasure is one mana of any colour, and one more Lotus-type
     * activation is available when enough fuel is left over for both escapes.
     * Sources that cannot make the required colour are deliberately not counted
     * toward a coloured symbol.
     *
     * <p>v75 closes the understatement this method's own v64 comment named:
     * {@code colorless} - what is left of {@link #colorlessSources} after the
     * wheel itself has been paid for - is counted toward the GENERIC half and
     * never toward a coloured symbol. With {@code colorless == 0} the answer is
     * v64's, bit for bit.</p> */
    private static boolean payable(int generic, int colored, int sources, int fuel, int treasure,
                                   int engineYield, int engineEscape, int terminalEscape, int colorless) {
        if (fuel < terminalEscape) return false;
        int extra = engineYield > 0 && fuel >= terminalEscape + engineEscape ? engineYield : 0;
        int available = sources + treasure + extra;
        return available >= colored && available + colorless >= generic + colored;
    }

    // ---------------------------------------------------------------- v68

    /** v68 C5, the v41 hand route's own fuel gate: {@link #breachReady} admits
     * a Breach in hand only at {@code fuel >= 6}. */
    private static final int HAND_ROUTE_FUEL = 6;
    /** The same two land drops v61's H1 forecast uses. */
    private static final int LAND_DROP_HORIZON = 2;
    /** The four finishers this plan can actually end on - the v41 terminal and
     * v64's three - in the order {@link #holdBreach} scans them. */
    private static final List<String> HOLD_TERMINALS = List.of(FREEZE, TENDRILS, WHEEL, ORACLE);
    /** Where a terminal is own-visible to this seat. */
    private static final List<ZoneType> HOLD_ZONES = List.of(ZoneType.Hand, ZoneType.Graveyard, ZoneType.Battlefield);
    private static final ThreadLocal<String> HOLD_STAMP = new ThreadLocal<>();

    /** The PRINTED static that grants escape to our own graveyard cards, or
     * null. Underworld Breach is recognised by this ability and never by its
     * name, so a renamed reprint is held on the same terms and a card that
     * merely shares the name is not.
     *
     * <p>The shape asked for is exactly the one the card prints:
     * {@code Mode$ Continuous}, {@code AffectedZone$ Graveyard},
     * {@code Affected$ …YouOwn…} and an {@code AddKeyword$} that adds
     * {@code Escape}. Nothing else about the card is read.</p> */
    private static StaticAbility escapeGrant(Card card) {
        if (card == null || card.isFaceDown()) return null;
        for (StaticAbility stat : card.getStaticAbilities()) {
            if (!"Continuous".equals(stat.getParam("Mode"))) continue;
            String zone = stat.getParam("AffectedZone"), add = stat.getParam("AddKeyword"),
                    affected = stat.getParam("Affected");
            if (zone != null && zone.contains("Graveyard") && affected != null && affected.contains("YouOwn")
                    && add != null && add.contains("Escape")) return stat;
        }
        return null;
    }

    /** How many OTHER graveyard cards an escape granted by this static exiles,
     * parsed out of the printed {@code ExileFromGrave<n/…>} text rather than
     * assumed - the same discipline {@link #escapeCost} already applies to a
     * card's own escape ability. Zero when the text cannot be read, which
     * refuses the hold outright. */
    private static int grantExileAmount(StaticAbility grant) {
        String add = grant.getParam("AddKeyword");
        int open = add == null ? -1 : add.indexOf("ExileFromGrave<");
        if (open < 0) return 0;
        int start = open + "ExileFromGrave<".length(), end = start;
        while (end < add.length() && Character.isDigit(add.charAt(end))) end++;
        return end == start ? 0 : Integer.parseInt(add.substring(start, end));
    }

    /** Another card carrying the same printed grant, own-visible where it could
     * still be deployed: our own battlefield or our own hand. A copy in the
     * graveyard is deliberately NOT counted - an Underworld Breach there grants
     * escape to nothing, as the diagnosis's {@code 69-s1} recorded. */
    private static boolean otherEscapeGrant(Player player, Card host) {
        for (ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Hand))
            for (Card card : player.getCardsIn(zone))
                if (card != host && escapeGrant(card) != null) return true;
        return false;
    }

    /** Everything we could tap for mana right now without spending fuel:
     * floating mana plus every untapped permanent we control that carries a
     * mana ability. Deliberately more generous than
     * {@link CubeComboAi#ownVisibleMana}, which counts lands only: this number
     * feeds {@link #escapeAvailableThisTurn}, where over-counting finds an
     * escape line and RELEASES the hold. */
    private int ownVisibleSources() {
        int sources = player.getManaPool().totalMana();
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && card.isUntapped() && !card.getManaAbilities().isEmpty()) sources++;
        return sources;
    }

    /** v68's binding clause, and the one the v63 worker's constraint names:
     * could ANY escape cast happen this turn once this Breach resolves? If one
     * could, the ordinary cast is not a wasted card and the hold must not fire
     * - this is what keeps the diagnosis's {@code 63-s1} and {@code 76-s1},
     * where the ordinary Breach escaped Lotus Petals the same turn.
     *
     * <p>Own-visible counts only, and coarse in the direction of RELEASE:
     * colour is never priced, a source that makes two mana counts once, and the
     * mana left after paying for the Breach is the generous
     * {@link #ownVisibleSources}. Each of those errors can only find an escape
     * line a stricter forecast would miss, and finding one releases.</p> */
    private boolean escapeAvailableThisTurn(Card breach, StaticAbility grant) {
        int exile = grantExileAmount(grant);
        if (exile <= 0) return true;
        boolean nonLandOnly = grant.getParam("Affected").contains("nonLand");
        var graveyard = player.getCardsIn(ZoneType.Graveyard);
        if (graveyard.size() <= exile) return false;
        int mana = Math.max(0, ownVisibleSources() - breach.getCMC());
        for (Card card : graveyard) {
            if (card.isFaceDown() || nonLandOnly && card.isLand()) continue;
            if (card.getCMC() <= mana) return true;
        }
        return false;
    }

    /** A named card where this seat can see it: our own hand, then graveyard,
     * then battlefield. Never a library, never an opponent zone. */
    private Card ownVisible(String name) {
        for (ZoneType zone : HOLD_ZONES) {
            Card card = find(name, zone);
            if (card != null) return card;
        }
        return null;
    }

    /** v68 C5 - refuse the ORDINARY AI's cast of an escape-granting enchantment
     * (Underworld Breach, recognised by the printed static of
     * {@link #escapeGrant}) when that cast throws the card away.
     *
     * <p>Underworld Breach sacrifices itself at the beginning of the end step,
     * so casting it on a turn that can take no escape line spends the Breach
     * half of this plan's entry gate for nothing. The diagnosis measured the
     * ordinary AI doing exactly that in 5 of 32 storm games, once on turn 1.
     * Its own sizing governs: this keeps the engine in 3 of those 5 and supplies
     * none of the Brain Freezes the other 4 were missing.</p>
     *
     * <p>Every clause must hold, and a failed clause means DEFAULT BEHAVIOUR
     * with no log line at all, so a released position is byte-identical:</p>
     * <ol>
     * <li>our own spell, our own card, in OUR OWN HAND;</li>
     * <li>the printed escape grant is present and its exile amount parses;</li>
     * <li>stack empty, our own MAIN1/MAIN2, one opponent, and we can still win;</li>
     * <li>it is the LAST own copy we could deploy ({@link #otherEscapeGrant});</li>
     * <li>no route of this plan can fire this turn - asked of a FRESH plan
     *     instance, whose only asymmetry with the live one ({@code failedTurn}
     *     unset) can make it propose where the live plan declined, which
     *     releases;</li>
     * <li>no escape cast is available this turn ({@link #escapeAvailableThisTurn});</li>
     * <li>a terminal is own-visible AND its fuel or its mana is within a short
     *     horizon - {@code fuel + 2 >= } the hand route's own gate, or v61 H1's
     *     unchanged {@code castableWithinTwoDrops}.</li>
     * </ol>
     *
     * <p>Own hand, own battlefield, own graveyard and both public life totals
     * only. OUR OWN LIBRARY IS NEVER READ - not its contents, not its order,
     * not its size - and neither is our registered decklist; the opponent's
     * hidden zones are never touched and a face-down card is never identified.
     * The plan probe of clause 5 reads exactly what {@code nextAction} already
     * reads on every pass.</p>
     *
     * <p>Side-effect free: the probe's only {@code System.err} path is
     * {@link #stalled}, which needs {@code wheelsCast > 0} and a fresh probe
     * has none; {@link #reserveEngine} restores {@code AiCardMemory} in a
     * {@code finally}; and every payment query runs inside
     * {@link CubeComboAi#probePayment}, which snapshots and restores memory,
     * mana-pool conversion state and ability actor/target state.</p> */
    public static boolean holdBreach(Player player, SpellAbility spell) {
        if (spell == null || !spell.isSpell()) return false;
        Card host = spell.getHostCard();
        if (host == null || host.isFaceDown() || host.getOwner() != player
                || host.getController() != player || !host.isInZone(ZoneType.Hand)) return false;
        StaticAbility grant = escapeGrant(host);
        if (grant == null || grantExileAmount(grant) <= 0) return false;
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (!game.getStack().isEmpty() || player.cantWin() || player.getOpponents().size() != 1
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))) return false;
        if (otherEscapeGrant(player, host)) return false;
        CubeBreachPlan probe = new CubeBreachPlan(player);
        if (probe.nextAction() != null) return false;
        if (probe.escapeAvailableThisTurn(host, grant)) return false;
        boolean fuelClose = probe.fuel() + LAND_DROP_HORIZON >= HAND_ROUTE_FUEL;
        for (String name : HOLD_TERMINALS) {
            Card terminal = probe.ownVisible(name);
            if (terminal == null) continue;
            String reason = fuelClose ? "fuel-horizon"
                    : CubeComboAi.castableWithinTwoDrops(player, terminal.getManaCost()) ? "mana-horizon" : null;
            if (reason == null) continue;
            holdLine(player, "CUBE_BREACH_HOLD reason=" + reason + " terminal=" + token(terminal.getName()));
            return true;
        }
        return false;
    }

    /** One {@code CUBE_BREACH_HOLD} line per (seat, turn, phase), the v53/v61
     * {@code twinLine} budget reproduced here because {@code CubeComboAi} is
     * another worker's file this round. The budget suppresses the LINE, never
     * the hold. Observability only: the identity stamp is compared, never
     * printed, and no decision reads any of it. There is deliberately NO
     * release line - a release is Default behaviour and must leave every
     * preserved log byte-identical. */
    private static void holdLine(Player player, String line) {
        var phases = player.getGame().getPhaseHandler();
        String stamp = System.identityHashCode(player) + ":" + phases.getTurn() + ":" + phases.getPhase();
        if (stamp.equals(HOLD_STAMP.get())) return;
        HOLD_STAMP.set(stamp);
        System.err.println(line);
    }

    public boolean waitingForOwnSpell() {
        if (player.getGame().getStack().isEmpty()) return false;
        var top = player.getGame().getStack().peekAbility();
        return selected != null && turn == player.getGame().getPhaseHandler().getTurn() && top != null
                && top.getActivatingPlayer() == player && (key(top.getHostCard())
                    || BREACH.equals(top.getHostCard().getName()) || top.getHostCard() == selected.getHostCard());
    }

    /** Native storm's setupTargets callback otherwise replaces a deliberate
     * self-mill with Default's opponent-only MillAi target. */
    public boolean chooseCopyTarget(SpellAbility ability) {
        if (freezeTarget == null || turn != player.getGame().getPhaseHandler().getTurn()
                || !ability.isCopied() || !FREEZE.equals(ability.getHostCard().getName())
                || ability.getActivatingPlayer() != player) return false;
        Player target = copyTargets.get(ability);
        boolean fresh = target == null;
        if (fresh) target = selfCopiesRemaining > 0 ? player : freezeOpponent;
        if (target == null || !ability.canTarget(target)) return false;
        ability.resetTargets();
        ability.getTargets().add(target);
        if (!ability.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(ability)) return false;
        if (fresh) {
            copyTargets.put(ability, target);
            if (target == player) selfCopiesRemaining--;
        }
        return true;
    }

    public boolean owns(SpellAbility ability) { return ability == selected; }

    public boolean play(SpellAbility ability) {
        boolean played = BREACH.equals(ability.getHostCard().getName())
                ? reserveEngine(() -> playNative(ability)) : playNative(ability);
        if (played) {
            String name = ability.getHostCard().getName();
            if (TIDE.equals(name)) tideCasts++;
            else if (WHEEL.equals(name)) wheelsCast++;
        }
        return played;
    }

    private boolean playNative(SpellAbility ability) {
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, ability, null,
                current -> new AiCostDecision(player, current, false) {
                    @Override public PaymentDecision visit(CostExile cost) {
                        if (!current.isEscape() || cost.zoneRestriction != 1 || cost.getFrom().size() != 1
                                || cost.getFrom().get(0) != ZoneType.Graveyard) return super.visit(cost);
                        CardCollection choices = escapeChoices(cost, current);
                        int amount = cost.getAbilityAmount(current);
                        return choices.size() < amount ? null : PaymentDecision.card(choices.subList(0, amount));
                    }
                });
        if (!played) {
            failedTurn = turn;
            System.err.println("CUBE_BREACH_PLAN native-payment-failed turn=" + turn + " card=" + ability.getHostCard().getName());
        }
        return played;
    }
}
