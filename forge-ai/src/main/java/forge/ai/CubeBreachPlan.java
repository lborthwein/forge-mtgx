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
 * already wins is never postponed.</p> */
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

    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
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
     * read by four other suites whose receipts must not move.</p> */
    static java.util.List<String> completingPieceNames(Player player) {
        return new CubeBreachPlan(player).missingPieces();
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
        SpellAbility action = tideRoute(opponent, fuel, storm);
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
        int f = fuel - graveyardRouteCards(), s = storm, treasure = treasures();
        int ourLibrary = player.getCardsIn(ZoneType.Library).size();
        int opponentLibrary = opponent.getCardsIn(ZoneType.Library).size();
        int hand = player.getCardsIn(ZoneType.Hand).size() - (breachInHand == null ? 0 : 1);
        for (int iteration = 0; iteration < 8; iteration++) {
            if (ourLibrary < WHEEL_DRAW) return null;
            int need = 3; // {2}{R}
            int fromPool = Math.min(pool, need);
            pool -= fromPool; need -= fromPool;
            int fromTreasure = Math.min(treasure, need);
            treasure -= fromTreasure; need -= fromTreasure;
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
                    && payable(1, 1, blue, f, treasure, engineYield, engineEscape, freezeEscape)) return "freeze";
            if (drainable && 2L * (s + 1) >= life
                    && payable(2, 2, black, f, treasure, engineYield, engineEscape, tendrilsEscape)) return "tendrils";
            if (oracle != null && ourLibrary == 0
                    && payable(0, 2, blue, f, treasure, engineYield, engineEscape, oracleEscape)) return "oracle";
        }
        return null;
    }

    /** Can the terminal itself be paid for at the end of the forecast? The
     * terminal escapes from our graveyard, so it needs its own exile amount in
     * fuel; a Treasure is one mana of any colour, and one more Lotus-type
     * activation is available when enough fuel is left over for both escapes.
     * Sources that cannot make the required colour are deliberately not counted
     * toward the generic half either, which understates the pool. */
    private static boolean payable(int generic, int colored, int sources, int fuel, int treasure,
                                   int engineYield, int engineEscape, int terminalEscape) {
        if (fuel < terminalEscape) return false;
        int extra = engineYield > 0 && fuel >= terminalEscape + engineEscape ? engineYield : 0;
        int available = sources + treasure + extra;
        return available >= colored && available >= generic + colored;
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
