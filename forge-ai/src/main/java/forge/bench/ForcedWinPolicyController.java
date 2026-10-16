package forge.bench;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.LobbyPlayer;
import forge.ai.simulation.ForcedWinSolver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Lane steps-back-1016 (EVAL only, off by default): an RL seat with the forced-win solver in front, the "S-c +
 * ForcedWin" arm. The solver owns the seat's priority while it follows a proven line or finds one (and its defense:
 * a counter of a spell that would give the opponent a forced win, the discard that ends it); every other ask goes to
 * the RL seat exactly as {@link PlayerControllerBridge} asks it (the policy, and its look-ahead when the actor has a
 * search spec). Created by {@link LobbyPlayerBridge} for a BRIDGE seat only when {@code -Dforge.bench.rlForcedWin=true};
 * {@code RlActorBench} refuses that property outside eval mode, so it never answers for, or labels, a learning seat.
 * The solver's spec is {@code -Dforge.ai.forcedWin.spec} ("worlds=6,top=3,...", empty = defaults).
 */
public class ForcedWinPolicyController extends PlayerControllerBridge {
    public static final String PROPERTY = "forge.bench.rlForcedWin";

    private final ForcedWinSolver solver;

    public ForcedWinPolicyController(final Game game, final Player p, final LobbyPlayer lp, final BenchSession session,
            final int seat, final CallCounter counters) {
        super(game, p, lp, session, BenchSession.Mode.BRIDGE, seat, counters);
        final String spec = System.getProperty("forge.ai.forcedWin.spec", "");
        final Map<String, String> kv = new HashMap<>();
        for (String part : spec.split(",")) {
            final int eq = part.indexOf('=');
            if (eq > 0) {
                kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        solver = new ForcedWinSolver(p, ForcedWinSolver.Config.fromProps(kv::get));
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Diagnostics: the seat's solver (decisions, takes). */
    public ForcedWinSolver solver() {
        return solver;
    }

    private boolean live() {
        return getGame() == getPlayer().getGame() && !getGame().isGameOver();
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (live()) {
            final ForcedWinSolver.Decision d = solver.decide(getGame());
            if (d.owned) {
                return d.action == null ? null : List.of(d.action);
            }
        }
        return super.chooseSpellAbilityToPlay();
    }

    @Override
    public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa,
            final CardCollection fetchList, final DelayedReveal delayedReveal, final String selectPrompt,
            final boolean isOptional, final Player decider) {
        final Card answer = solver.answerPick(destination, origin, sa, fetchList, decider);
        if (answer != null) {
            if (delayedReveal != null) {
                reveal(delayedReveal);
            }
            return answer;
        }
        return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                isOptional, decider);
    }

    @Override
    public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone,
            final SpellAbility source) {
        final CardCollectionView ordered = solver.answerOrder(cards, destinationZone, source);
        if (ordered != null) {
            observeLook(cards, destinationZone);
            return ordered;
        }
        return super.orderMoveToZoneList(cards, destinationZone, source);
    }

    @Override
    public void orderAndPlaySimultaneousSa(final List<SpellAbility> activePlayerSAs) {
        super.orderAndPlaySimultaneousSa(solver.answerTriggers(activePlayerSAs));
    }

    @Override
    public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message,
            final List<String> options, final Card cardToShow, final Map<String, Object> params) {
        final Boolean b = solver.answerConfirm(sa, message);
        return b != null ? b : super.confirmAction(sa, mode, message, options, cardToShow, params);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
        final ImmutablePair<CardCollection, CardCollection> kept = solver.answerLook(topN);
        return kept != null ? kept : super.arrangeForScry(topN);
    }

    @Override
    public void declareAttackers(final Player attacker, final Combat combat) {
        if (solver.answerAttack(attacker, combat)) {
            return;
        }
        super.declareAttackers(attacker, combat);
    }

    @Override
    public boolean confirmTrigger(final WrappedAbility wrapper) {
        return solver.answerConfirmTrigger(wrapper) || super.confirmTrigger(wrapper);
    }

    @Override
    public boolean chooseBinary(final SpellAbility sa, final String question, final BinaryChoiceType kind,
            final Boolean defaultVal) {
        final Boolean b = solver.answerBinary(kind, defaultVal);
        return b != null ? b : super.chooseBinary(sa, question, kind, defaultVal);
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa, final CardCollection validCards,
            final int min, final int max, final CardCollectionView visibleToChooser) {
        if (p != getPlayer()) {
            final CardCollection take = solver.answerDiscard(p, sa, validCards, min, max);
            if (take != null) {
                return take;
            }
        }
        return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
    }
}
