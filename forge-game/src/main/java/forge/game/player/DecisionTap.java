package forge.game.player;

import forge.game.cost.CostPart;
import forge.game.cost.PaymentDecision;
import forge.game.spellability.SpellAbility;

import java.util.function.Supplier;

/**
 * Lane replay-record-1021: the engine's one seam for the AI tape ({@code forge.ai.AiTape}). A payment's cost decisions
 * ({@link forge.game.cost.CostPayment#payComputerCosts}) are made by a decision maker the engine builds itself, not by a
 * {@link PlayerController} method; the tape records them here, and a forced replay answers them from the record.
 * Nothing is installed (every game but a taped one), and then nothing changes.
 */
public final class DecisionTap {
    private DecisionTap() {
    }

    public interface Tap {
        /** True when {@code payer}'s decisions, on this thread, are the tape's. */
        boolean covers(Player payer);

        /** {@code payer}'s decision for cost part {@code index} of {@code ability}: {@code decide} (the live one) or the record's. */
        PaymentDecision cost(Player payer, CostPart part, int index, SpellAbility ability, Supplier<PaymentDecision> decide);
    }

    private static volatile Tap tap;

    public static void install(final Tap t) {
        tap = t;
    }

    /** The tape that covers {@code payer} here, or null. */
    public static Tap of(final Player payer) {
        final Tap t = tap;
        return t != null && payer != null && t.covers(payer) ? t : null;
    }
}
