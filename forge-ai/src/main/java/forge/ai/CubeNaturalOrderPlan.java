package forge.ai;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * combo-ai-port-1009 (an addition to the v104 lineage, which had no Natural Order family): Natural Order for Craterhoof
 * Behemoth in our own first main phase, when the forecast attack is lethal; the search then takes the Behemoth.
 *
 * <p>Forge Default casts Natural Order after combat and its search takes the "best" creature by body score (Woodfall
 * Primus over Craterhoof in 20 of 31 R8 games, combo-exec-1009); neither ever ends a game. This plan proposes the cast
 * only when, for EVERY green creature the sacrifice could take, our attackers this turn - the creatures that can attack
 * now, plus the Behemoth (haste) - with the Behemoth's +X/+X and trample (X = creatures we control after it enters)
 * deal at least the opponent's life after every untapped potential blocker absorbs its lethal damage. Inputs are
 * own-visible only: our hand and battlefield, our own deck list minus our own visible zones (never library contents or
 * order), and the opponent's public battlefield and life. Costs, the sacrifice choice, the search, the trigger and the
 * attack declaration stay Forge's own; the plan answers only the search's pick for the spell it proposed.
 */
final class CubeNaturalOrderPlan {
    static final String PAYOFF = "Craterhoof Behemoth";
    private final Player player;
    private int turn = -1;
    private SpellAbility selected;
    private int selectedHostId = -1;
    private String decline = "none";

    CubeNaturalOrderPlan(Player player) {
        this.player = player;
    }

    /** Observability only (receipts), never read by a decision. */
    String declineReason() {
        return decline;
    }

    /** The printed Natural Order shape: a spell moving a creature card from our library to the battlefield whose cost
     * sacrifices a creature. Recognised by shape, not by name. */
    static boolean shape(SpellAbility sa) {
        if (sa == null || !sa.isSpell() || sa.getApi() != ApiType.ChangeZone) {
            return false;
        }
        if (!"Library".equals(sa.getParam("Origin")) || !"Battlefield".equals(sa.getParam("Destination"))
                || !sa.getParamOrDefault("ChangeType", "").startsWith("Creature")) {
            return false;
        }
        final Cost cost = sa.getPayCosts();
        if (cost == null) {
            return false;
        }
        for (CostPart part : cost.getCostParts()) {
            if (part instanceof CostSacrifice s && s.getType().startsWith("Creature")) {
                return true;
            }
        }
        return false;
    }

    SpellAbility nextAction() {
        final Game game = player.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        if (turn != ph.getTurn()) {
            turn = ph.getTurn();
            selected = null;
            selectedHostId = -1;
        }
        if (!ph.is(PhaseType.MAIN1, player) || !game.getStack().isEmpty() || player.getOpponents().size() != 1) {
            decline = "phase";
            return null;
        }
        if (selected != null) {
            decline = "already-cast";
            return null;
        }
        if (!payoffInLibrary()) {
            decline = "no-payoff";
            return null;
        }
        SpellAbility spell = null;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) {
                continue;
            }
            for (SpellAbility original : CubeComboAi.possibleAbilities(card, player)) {
                if (!shape(original)) {
                    continue;
                }
                final SpellAbility sa = original.copy(player);
                if (CubeComboAi.canPlayNative(sa, player) && CubeComboAi.canPayCost(sa, player, false)) {
                    spell = sa;
                    break;
                }
            }
            if (spell != null) {
                break;
            }
        }
        if (spell == null) {
            decline = "no-castable-order";
            return null;
        }
        final Player opp = player.getOpponents().get(0);
        if (!opp.canLoseLife() || opp.cantLoseForZeroOrLessLife()) {
            decline = "opponent-cant-lose";
            return null;
        }
        final int worst = worstForecast(opp);
        if (worst < opp.getLife()) {
            decline = "not-lethal forecast=" + worst + " life=" + opp.getLife();
            return null;
        }
        selected = spell;
        selectedHostId = spell.getHostCard().getId();
        decline = "none";
        CubeComboAi.receipt("CUBE_NATURAL_ORDER_PLAN cast turn=" + turn + " forecast=" + worst + " oppLife=" + opp.getLife());
        return spell;
    }

    /** Whether the Behemoth is still in our library: membership only (never the order), which our own decklist minus
     * the cards we have seen already settles in a real game. Read from the zone rather than the decklist so that a
     * position installed over another deck (the EVAL puzzle frames) is answered the same way. */
    private boolean payoffInLibrary() {
        for (Card c : player.getCardsIn(ZoneType.Library)) {
            if (PAYOFF.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** The smallest forecast damage over every green creature the sacrifice could take. */
    private int worstForecast(Player opp) {
        final CardCollection ours = new CardCollection();
        for (Card c : player.getCreaturesInPlay()) {
            if (!c.isPhasedOut()) {
                ours.add(c);
            }
        }
        int absorb = 0;
        for (Card b : opp.getCreaturesInPlay()) {
            if (!b.isPhasedOut() && b.isUntapped() && CombatUtil.canBlock(b)) {
                absorb += Math.max(0, b.getLethalDamage());
            }
        }
        int worst = Integer.MAX_VALUE;
        boolean any = false;
        for (Card sac : ours) {
            if (!sac.isGreen()) {
                continue;
            }
            any = true;
            int creatures = ours.size(); // after the sacrifice (-1) and the Behemoth (+1)
            int x = creatures;
            int damage = 5 + x;          // the Behemoth: 5 power, haste
            for (Card c : ours) {
                if (c == sac || !CombatUtil.canAttack(c, opp)) {
                    continue;
                }
                damage += Math.max(0, c.getNetCombatDamage() + x);
            }
            worst = Math.min(worst, damage - absorb);
        }
        return any ? worst : Integer.MIN_VALUE;
    }

    boolean owns(SpellAbility sa) {
        return sa != null && sa == selected;
    }

    boolean play(SpellAbility sa) {
        final boolean played = ComputerUtil.handlePlayingSpellAbility(player, sa, null,
                current -> new AiCostDecision(player, current, false));
        CubeComboAi.receipt("CUBE_NATURAL_ORDER_PLAN " + (played ? "played" : "native-payment-failed") + " turn=" + turn);
        if (!played) {
            selected = null;
            selectedHostId = -1;
        }
        return played;
    }

    /** Our resolving Natural Order (the one this plan cast this turn) searching our own library for the battlefield. */
    boolean ownsSearch(SpellAbility source, Player decider) {
        return source != null && decider == player && source.getActivatingPlayer() == player && selectedHostId >= 0
                && turn == player.getGame().getPhaseHandler().getTurn()
                && source.getHostCard() != null && source.getHostCard().getId() == selectedHostId && shape(source.getRootAbility());
    }

    Card choosePayoff(Iterable<Card> choices) {
        for (Card c : choices) {
            if (c != null && !c.isFaceDown() && PAYOFF.equals(c.getName()) && c.getOwner() == player) {
                return c;
            }
        }
        return null;
    }

    boolean waitingForOwnSpell() {
        final var stack = player.getGame().getStack();
        if (selected == null || stack.isEmpty() || turn != player.getGame().getPhaseHandler().getTurn()) {
            return false;
        }
        final SpellAbility top = stack.peekAbility();
        return top != null && top.getActivatingPlayer() == player && top.getHostCard() != null
                && top.getHostCard().getId() == selectedHostId;
    }
}
