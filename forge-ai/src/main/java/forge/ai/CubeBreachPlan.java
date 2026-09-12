package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
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
import java.util.function.Supplier;

/** Breach/zero-cost mana artifact/Freeze turn plan. Counts are a conservative resource
 * forecast, not a rules simulator or forced-win proof. All actions, escape
 * costs, storm copies and responses execute in the native engine. Only own
 * hand/public zones, library SIZES and public storm count are consulted. */
public final class CubeBreachPlan {
    private static final String BREACH = "Underworld Breach", LED = "Lion's Eye Diamond", FREEZE = "Brain Freeze";
    private static final List<String> ENGINES = List.of("Black Lotus", LED, "Lotus Petal");
    private final Player player;
    private SpellAbility selected;
    private Player freezeTarget;
    private Player freezeOpponent;
    private int selfCopiesRemaining;
    private final Map<SpellAbility, Player> copyTargets = new IdentityHashMap<>();
    private Card reservedEngine;
    private int turn = -1, actions, failedTurn = -1;

    public CubeBreachPlan(Player player) { this.player = player; }

    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
            if (!card.isFaceDown() && name.equals(card.getName())) return card;
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
     * library contents or order, never an opponent zone. */
    static java.util.List<String> completingPieceNames(Player player) {
        CubeBreachPlan plan = new CubeBreachPlan(player);
        int fuel = plan.fuel();
        boolean engine = plan.engine() != null, freeze = plan.freeze() != null, breach = plan.breachReady(fuel);
        if ((engine ? 1 : 0) + (freeze ? 1 : 0) + (breach ? 1 : 0) != 2) return java.util.List.of();
        if (!freeze) return java.util.List.of(FREEZE);
        if (!engine) return ENGINES;
        // The Breach half is the missing one. Fetching it is only worth a
        // selection where the hand route's own gate would then be satisfied.
        return fuel >= 6 && player.getCardsIn(ZoneType.Library).size() >= 4
                ? java.util.List.of(BREACH) : java.util.List.of();
    }

    private CardCollection escapeChoices(CostExile cost, SpellAbility ability) {
        CardCollection valid = CardLists.getValidCards(player.getCardsIn(cost.getFrom()),
                cost.getType().split(";"), player, ability.getHostCard(), ability);
        valid = CardLists.filter(valid, CardPredicates.canExiledBy(ability, false));
        valid = ComputerUtilCost.paymentChoicesWithoutTargets(valid, ability, player);
        valid.removeIf(this::key);
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

    private SpellAbility crack(Card card) {
        if (card == null) return null;
        for (SpellAbility original : card.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (CubeComboAi.canPlayNative(ability, player) && ability.getManaPart().canProduce("U", ability)
                    && CubeComboAi.canPayCost(ability, player, false)) {
                ability.setManaExpressChoice(ColorSet.fromMask(MagicColor.BLUE));
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
     * is checked afresh, and failures do not count as wins. */
    private boolean enoughToMill(int fuel, int blue, Card engine, boolean freezeInHand, int storm, int remaining) {
        boolean engineInPlay = engine.isInPlay(), engineInHand = engine.isInZone(ZoneType.Hand);
        int yield = engine.getName().equals("Lotus Petal") ? 1 : 3;
        boolean discardsHand = engine.getName().equals(LED);
        for (int step = 0; step < 64 && remaining > 0; step++) {
            if (blue < 2) {
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
        }
        if (failedTurn == turn || actions >= 64 || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return null;
        Player opponent = player.getOpponents().get(0);
        int remaining = opponent.getCardsIn(ZoneType.Library).size();
        if (remaining == 0 || opponent.cantLoseCheck(GameLossReason.Milled)) return null;
        Card engine = engine();
        boolean fromHand = find(FREEZE, ZoneType.Hand) != null;
        Card freeze = freeze();
        if (freeze == null || engine == null) return null;
        // A mill-out isn't a plan if its eventual target is currently illegal.
        boolean targetable = false;
        for (SpellAbility original : freeze.getSpellAbilities()) {
            SpellAbility ability = original.copy(player);
            if (ability.isSpell() && ability.canTarget(opponent)) targetable = true;
        }
        if (!targetable) return null;
        int blue = player.getManaPool().getAmountOfColor(MagicColor.BLUE);
        int fuel = fuel(), storm = game.getStack().getSpellsCastThisTurn().size();
        if (find(BREACH, ZoneType.Battlefield) == null) {
            Card breach = find(BREACH, ZoneType.Hand);
            if (!breachReady(fuel)) return null;
            // An on-board disabled engine is not a reason to spend Breach.
            if (engine.isInPlay() && crack(engine) == null) return null;
            reservedEngine = engine.isInPlay() ? engine : null;
            return select(reserveEngine(() -> spell(breach)));
        }
        if (blue < 2) {
            SpellAbility floating = floatBlue();
            if (floating != null) return select(floating);
            int yield = engine.getName().equals("Lotus Petal") ? 1 : 3;
            int activations = (2 - blue + yield - 1) / yield;
            int escapes = Math.max(0, activations - (engine.isInPlay() || engine.isInZone(ZoneType.Hand) ? 1 : 0));
            int freezeFuel = fromHand && !engine.getName().equals(LED) ? 0 : 3;
            if (fuel >= 3 * escapes + freezeFuel) return select(engine.isInPlay() ? crack(engine) : spell(engine));
        }
        SpellAbility cast = spell(freeze);
        if (cast == null) return null;
        int copies = storm + 1, selfCopies = 0;
        if (3 * copies < remaining && !enoughToMill(fuel, blue, engine, fromHand, storm, remaining)) {
            // Copies may target different players. Find the minimum self-mill
            // that replenishes enough fuel for the remaining opponent kill;
            // never force an entire storm batch into our nearly empty library.
            int maxSelf = Math.min(copies, Math.max(0, (player.getCardsIn(ZoneType.Library).size() - 1) / 3));
            if (maxSelf == 0) return null;
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
        if (!cast.canTarget(target)) return null;
        cast.resetTargets();
        cast.getTargets().add(target);
        if (!cast.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(cast)) return null;
        freezeTarget = target;
        freezeOpponent = opponent;
        selfCopiesRemaining = Math.max(0, selfCopies - 1); // the original already has its target
        copyTargets.clear();
        return select(cast);
    }

    public boolean waitingForOwnSpell() {
        if (player.getGame().getStack().isEmpty()) return false;
        var top = player.getGame().getStack().peekAbility();
        return selected != null && turn == player.getGame().getPhaseHandler().getTurn() && top != null
                && top.getActivatingPlayer() == player && (key(top.getHostCard()) || BREACH.equals(top.getHostCard().getName()));
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
        return BREACH.equals(ability.getHostCard().getName()) ? reserveEngine(() -> playNative(ability)) : playNative(ability);
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
