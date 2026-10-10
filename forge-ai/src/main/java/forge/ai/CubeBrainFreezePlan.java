package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

/**
 * combo-ai-port-1009 (an addition to the v104 lineage, whose storm plan is Yawgmoth's Will / Tendrils and whose Breach
 * plan loops Brain Freeze through Underworld Breach): a plain Brain Freeze storm kill. In our own main phase, when
 * Brain Freeze is in hand and 3 x (storm + the zero-mana spells we can still cast + 1) reaches the opponent's library,
 * cast every zero-mana spell first (Moxen, Lotus Petal, Lion's Eye Diamond: storm), then Brain Freeze at the opponent.
 * The mill kill lands at the opponent's next draw. Forge Default casts Brain Freeze as soon as it is affordable and
 * holds Lion's Eye Diamond, so it falls one copy short of the combo-exec P11 position.
 *
 * <p>Own-visible inputs only: our hand, the public storm count and the opponent's public library size. Never a
 * Lion's Eye Diamond activation (that discards the hand Brain Freeze is in); costs, copies and targets of the copies
 * stay Forge's own.
 */
final class CubeBrainFreezePlan {
    static final String FREEZE = "Brain Freeze";
    private final Player player;
    private int turn = -1, actions;
    private SpellAbility selected;
    private String decline = "none";

    CubeBrainFreezePlan(Player player) {
        this.player = player;
    }

    /** Observability only (receipts), never read by a decision. */
    String declineReason() {
        return decline;
    }

    private SpellAbility castable(Card card) {
        for (SpellAbility original : CubeComboAi.possibleAbilities(card, player)) {
            if (!original.isSpell()) {
                continue;
            }
            final SpellAbility sa = original.copy(player);
            if (CubeComboAi.canPlayNative(sa, player) && CubeComboAi.canPayCost(sa, player, false)) {
                return sa;
            }
        }
        return null;
    }

    private static boolean free(Card card) {
        return !card.isLand() && !card.isFaceDown() && card.getManaCost() != null && card.getManaCost().getCMC() == 0
                && !card.getManaCost().isNoCost() && !FREEZE.equals(card.getName());
    }

    SpellAbility nextAction() {
        final Game game = player.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        if (turn != ph.getTurn()) {
            turn = ph.getTurn();
            actions = 0;
            selected = null;
        }
        if (!(ph.is(PhaseType.MAIN1, player) || ph.is(PhaseType.MAIN2, player)) || !game.getStack().isEmpty()
                || player.getOpponents().size() != 1 || actions >= 16) {
            decline = "phase";
            return null;
        }
        Card freeze = null;
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            if (!c.isFaceDown() && FREEZE.equals(c.getName())) {
                freeze = c;
                break;
            }
        }
        if (freeze == null) {
            decline = "no-freeze";
            return null;
        }
        final Player opp = player.getOpponents().get(0);
        final int library = opp.getCardsIn(ZoneType.Library).size();
        final int storm = game.getStack().getSpellsCastThisTurn().size();
        int freeCastable = 0;
        SpellAbility firstFree = null;
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            if (!free(c)) {
                continue;
            }
            final SpellAbility sa = castable(c);
            if (sa != null) {
                freeCastable++;
                if (firstFree == null) {
                    firstFree = sa;
                }
            }
        }
        if (3 * (storm + freeCastable + 1) < library) {
            decline = "short storm=" + storm + " free=" + freeCastable + " library=" + library;
            return null;
        }
        if (firstFree != null) {
            // the free spells first: each one is storm, and each mana rock is a source for Brain Freeze
            return select(firstFree, "free");
        }
        final SpellAbility bf = castable(freeze);
        if (bf == null || !bf.usesTargeting() || !bf.canTarget(opp)) {
            decline = "freeze-not-castable";
            return null;
        }
        bf.resetTargets();
        bf.getTargets().add(opp);
        if (!bf.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(bf)) {
            decline = "freeze-target";
            return null;
        }
        return select(bf, "freeze storm=" + storm + " library=" + library);
    }

    private SpellAbility select(SpellAbility sa, String what) {
        selected = sa;
        actions++;
        decline = "none";
        CubeComboAi.receipt("CUBE_BRAIN_FREEZE_PLAN select " + what + " card=" + sa.getHostCard().getName().replace(' ', '_')
                + " turn=" + turn);
        return sa;
    }

    boolean owns(SpellAbility sa) {
        return sa != null && sa == selected;
    }

    boolean play(SpellAbility sa) {
        final boolean played = ComputerUtil.handlePlayingSpellAbility(player, sa, null,
                current -> new AiCostDecision(player, current, false));
        CubeComboAi.receipt("CUBE_BRAIN_FREEZE_PLAN " + (played ? "played" : "native-payment-failed") + " card="
                + sa.getHostCard().getName().replace(' ', '_') + " turn=" + turn);
        if (!played) {
            actions = 16; // stop for this turn
        }
        selected = null;
        return played;
    }

    boolean waitingForOwnSpell() {
        return false;
    }
}
