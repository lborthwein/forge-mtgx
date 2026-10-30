package forge.ai.simulation;

import java.util.List;

import forge.game.Game;
import forge.game.player.Player;

/**
 * r2-choose-a-card-1029: Forge's static {@link GameStateEvaluator} squashed to [0, 1], for a choose-a-card seat with no
 * value service (report only; never a label). The payload is the value itself, taken at the leaf.
 */
final class StaticValuer implements ChooseACard.Valuer {
    static final StaticValuer INSTANCE = new StaticValuer();

    @Override
    public Object onCopy(final Game copy, final Player me) {
        return null;
    }

    @Override
    public Object capture(final Object ctx, final Game g, final Player me) {
        final double v = LookaheadSearch.value(g, me);
        return 1.0 / (1.0 + Math.exp(-v / 200.0));
    }

    @Override
    public double[] values(final List<Object> payloads) {
        final double[] out = new double[payloads.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Double) payloads.get(i);
        }
        return out;
    }
}
