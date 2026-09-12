package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

/** Bounded native mana-loop execution toward a known Ballista outlet. No
 * fabricated mana, counters, damage or priority skips. This recognizes the
 * named engines, but measures each tap and prices untaps through native costs. */
public final class CubeMonolithPlan {
    /** The names {@link #nextAction} already looks for, as the roles of this
     * plan's entry gate. Used only by {@link #completingPieceNames}; the
     * decision code keeps its own literals unchanged. */
    private static final String OUTLET = "Walking Ballista";
    private static final java.util.List<String> UNTAPPERS =
            java.util.List.of("Kinnan, Bonder Prodigy", "Zirda, the Dawnwaker");
    private static final java.util.List<String> ENGINES =
            java.util.List.of("Basalt Monolith", "Grim Monolith");
    private final Player player;
    private int turn = -1, actions, failedTurn = -1;
    private SpellAbility selected;
    private Card measuredEngine;
    private int measuredGain;
    private SpellAbility pending;
    private int countersBefore, lifeBefore;
    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeDoomsdayPlan#declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code action-cap},
     * {@code failed-this-turn}, {@code multiplayer},
     * {@code stopped-no-progress}, {@code cant-damage}, {@code no-outlet},
     * {@code outlet-unusable}, {@code outlet-untargetable},
     * {@code cost-unreadable}, {@code outlet-disabled},
     * {@code unpayable:outlet}, {@code no-untapper} or
     * {@code no-engine-gain}. Every one names our own battlefield/hand or a
     * public quantity; none names an opponent zone.</p> */
    private String decline = "other check=monolith-plan";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline = reason; return null; }
    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order. Called only after that guard has
     * already decided to decline, so it can change nothing. */
    private String gateReason(int cap) {
        var phase = player.getGame().getPhaseHandler();
        if (failedTurn == turn) return "failed-this-turn";
        if (actions >= cap) return "action-cap";
        if (player.cantWin()) return "cant-win";
        if (!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        if (!(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))) return "phase";
        return "multiplayer";
    }

    public CubeMonolithPlan(Player player) { this.player = player; }

    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
            if (!card.isFaceDown() && name.equals(card.getName())) return card;
        return null;
    }

    private SpellAbility ability(Card card, ApiType api) {
        if (card == null) return null;
        for (SpellAbility original : card.getSpellAbilities()) {
            SpellAbility sa = original.copy(player);
            if (sa.getApi() == api) return sa;
        }
        return null;
    }

    private boolean payable(SpellAbility sa) {
        return sa != null && CubeComboAi.canPlayNative(sa, player)
                && CubeComboAi.canPayCost(sa, player, false);
    }

    private int genericCost(SpellAbility sa, int x) {
        if (sa == null) return -1;
        var cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, player, true, x, false);
        return cost.getConvertedManaCost() == cost.getGenericManaAmount() ? cost.getGenericManaAmount() : -1;
    }

    private SpellAbility select(SpellAbility sa) { selected = sa; actions++; return sa; }


    /** v60 - the three roles this plan's entry gate needs, by the same names
     * and the same zones {@link #nextAction} reads them in: the Walking Ballista
     * outlet (our own battlefield or our own hand - the plan casts it from
     * hand itself), an untapper on our own battlefield, and a Monolith engine
     * on our own battlefield. Empty unless exactly one role is missing.
     *
     * <p>Deliberately the entry gate only, exactly as
     * {@code CubeStormPlan.completingPieceNames} states for its own downstream
     * gate: the untap arithmetic {@link #nextAction} applies
     * ({@code expectedGain > cost}) prices an ability on a card that is ON the
     * battlefield, and is not re-derived here for a card that is not, so the
     * engine role can name a Monolith the plan later declines. Own battlefield
     * and own hand only; a face-down card is never identified.</p> */
    static java.util.List<String> completingPieceNames(Player player) {
        CubeMonolithPlan plan = new CubeMonolithPlan(player);
        boolean outlet = plan.find(OUTLET, ZoneType.Battlefield) != null || plan.find(OUTLET, ZoneType.Hand) != null;
        boolean untapper = false, engine = false;
        for (String name : UNTAPPERS) if (plan.find(name, ZoneType.Battlefield) != null) untapper = true;
        for (String name : ENGINES) if (plan.find(name, ZoneType.Battlefield) != null) engine = true;
        if ((outlet ? 1 : 0) + (untapper ? 1 : 0) + (engine ? 1 : 0) != 2) return java.util.List.of();
        if (!outlet) return java.util.List.of(OUTLET);
        return untapper ? ENGINES : UNTAPPERS;
    }

    public SpellAbility nextAction() {
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) {
            turn = phase.getTurn(); actions = 0; selected = null; measuredEngine = null; measuredGain = 0; pending = null;
        }
        if (failedTurn == turn || actions >= 512 || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return decline(gateReason(512));
        decline = "other check=monolith-plan";
        Player opponent = player.getOpponents().get(0);
        if (pending != null) {
            // Replacements, prevention, or interaction may defeat the route.
            // Observe the resolved native result, then stop rather than repeat
            // an action that made no progress toward this plan's objective.
            boolean stalled = pending.getApi() == ApiType.PutCounter
                    && pending.getHostCard().getCounters(CounterEnumType.P1P1) <= countersBefore
                    || pending.getApi() == ApiType.DealDamage && opponent.getLife() >= lifeBefore
                    || pending.getApi() == ApiType.Untap && pending.getHostCard().isTapped();
            pending = null;
            if (stalled) {
                failedTurn = turn;
                System.err.println("CUBE_MONOLITH_PLAN stopped-no-progress turn=" + turn);
                return decline("stopped-no-progress");
            }
        }
        if (!opponent.canLoseLife() || opponent.getLife() <= 0 || opponent.getLife() > 128) return decline("cant-damage");
        Card outlet = find("Walking Ballista", ZoneType.Battlefield);
        boolean inHand = outlet == null;
        if (inHand) outlet = find("Walking Ballista", ZoneType.Hand);
        SpellAbility shot = ability(outlet, ApiType.DealDamage);
        if (shot == null || !shot.canTarget(opponent))
            return decline(outlet == null ? "no-outlet" : shot == null ? "outlet-unusable" : "outlet-untargetable");
        shot.resetTargets(); shot.getTargets().add(opponent);
        if (!shot.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(shot)) return decline("outlet-untargetable");
        int counters = inHand ? 0 : outlet.getCounters(CounterEnumType.P1P1);
        if (!inHand && counters >= opponent.getLife() && payable(shot)) return select(shot);

        SpellAbility spend = null;
        int required;
        if (inHand) {
            for (SpellAbility original : outlet.getAllPossibleAbilities(player, false, null, true)) {
                if (!original.isSpell()) continue;
                spend = original.copy(player); spend.setXManaCostPaid(opponent.getLife()); break;
            }
            required = genericCost(spend, opponent.getLife());
        } else {
            spend = ability(outlet, ApiType.PutCounter);
            int each = genericCost(spend, 0);
            required = each < 0 ? -1 : each * Math.max(0, opponent.getLife() - counters);
        }
        if (required < 0 || spend == null) return decline("cost-unreadable");
        // A disabled outlet must not turn a profitable engine into an endless
        // plan. Native static restrictions apply even before we can pay it.
        if (!CubeComboAi.canPlayNative(spend, player)) return decline("outlet-disabled");
        if (player.getManaPool().totalMana() >= required) return payable(spend) ? select(spend) : decline("unpayable:outlet");

        boolean kinnan = find("Kinnan, Bonder Prodigy", ZoneType.Battlefield) != null;
        boolean zirda = find("Zirda, the Dawnwaker", ZoneType.Battlefield) != null;
        if (!kinnan && !zirda) return decline("no-untapper");
        for (String name : new String[]{"Basalt Monolith", "Grim Monolith"}) {
            Card engine = find(name, ZoneType.Battlefield);
            SpellAbility untap = ability(engine, ApiType.Untap);
            int cost = genericCost(untap, 0);
            // Initial reach estimate; actual production is checked after every
            // native activation. Grim+Kinnan with no reducer is net zero.
            int expectedGain = 3 + (kinnan ? 1 : 0);
            if (engine == null || cost < 0 || expectedGain <= cost) continue;
            if (engine.isTapped()) {
                if (measuredEngine == engine && measuredGain <= cost) continue;
                if (payable(untap)) return select(untap);
            } else {
                for (SpellAbility original : engine.getManaAbilities()) {
                    SpellAbility tap = original.copy(player);
                    if (payable(tap)) return select(tap);
                }
            }
        }
        return decline("no-engine-gain");
    }

    public boolean owns(SpellAbility sa) { return sa == selected; }

    public boolean play(SpellAbility sa) {
        int before = player.getManaPool().totalMana();
        countersBefore = sa.getHostCard().getCounters(CounterEnumType.P1P1);
        lifeBefore = player.getOpponents().get(0).getLife();
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, sa, null,
                current -> new AiCostDecision(player, current, false));
        if (played && sa.isManaAbility()) {
            measuredEngine = sa.getHostCard();
            measuredGain = player.getManaPool().totalMana() - before;
        }
        if (played && !sa.isManaAbility()) pending = sa;
        if (!played) failedTurn = turn;
        System.err.println("CUBE_MONOLITH_PLAN " + (played ? "played" : "native-payment-failed")
                + " turn=" + turn + " card=" + sa.getHostCard().getName() + " api=" + sa.getApi()
                + " mana=" + player.getManaPool().totalMana() + " gain=" + measuredGain);
        return played;
    }

    public boolean waitingForOwnSpell() {
        var stack = player.getGame().getStack();
        if (selected == null || turn != player.getGame().getPhaseHandler().getTurn() || stack.isEmpty()) return false;
        var top = stack.peekAbility();
        return top != null && top.getActivatingPlayer() == player && top.getHostCard() == selected.getHostCard();
    }
}
