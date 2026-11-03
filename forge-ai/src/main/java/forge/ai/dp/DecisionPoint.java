package forge.ai.dp;

import java.util.Collection;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * decisionpoint-1102 (layer 2 of ARCH-PROPOSAL-1102; CENSUS.md §5): one choice the engine asks of a seat, as
 * DecisionPoint(kind, legal options, observation). Built only when an observer or answerer is installed; with none,
 * the controller returns Forge AI's answer without building one (byte-identical to the uninstrumented code).
 *
 * <p>{@code options} is the legal list the engine passed (the controller's own argument; never enumerated here), or
 * null when the call carries none (PRIORITY, a bundled checkpoint); {@code args} are the raw call arguments, in the
 * method's order, for adapters that need them ({@link forge.ai.CacAdapter}). The observation is the seat's layer-1
 * InfoState view; until layer 1 lands an answerer reads {@link #seat()}'s game through its own guard.
 */
public final class DecisionPoint {
    private final Kind kind;
    private final String ctx;
    private final Player seat;
    private final SpellAbility source;
    private final Collection<?> options;
    private final int min;
    private final int max;
    private final Object forgeDefault;
    private final Object[] args;
    private final int count;
    private final boolean nested;

    public DecisionPoint(final Kind kind, final String ctx, final Player seat, final SpellAbility source,
            final Collection<?> options, final int min, final int max, final Object forgeDefault, final Object[] args) {
        this(kind, ctx, seat, source, options, min, max, forgeDefault, false, args);
    }

    /**
     * {@code nested}: asked while the same seat's controller was computing another answer (Forge AI's own nested asks,
     * e.g. a multi-pick looping its single pick; the AI tape does not count these as decisions).
     */
    public DecisionPoint(final Kind kind, final String ctx, final Player seat, final SpellAbility source,
            final Collection<?> options, final int min, final int max, final Object forgeDefault, final boolean nested,
            final Object[] args) {
        this.nested = nested;
        this.kind = kind;
        this.ctx = ctx;
        this.seat = seat;
        this.source = source;
        this.options = options;
        this.min = min;
        this.max = max;
        this.forgeDefault = forgeDefault;
        this.args = args == null ? new Object[0] : args;
        this.count = options == null ? -1 : options.size();
    }

    /** A point noted by research code that carries only its option count (no list; {@code count} -1 = unknown). */
    public DecisionPoint(final Kind kind, final String ctx, final Player seat, final SpellAbility source,
            final int count) {
        this.kind = kind;
        this.ctx = ctx;
        this.seat = seat;
        this.source = source;
        this.options = null;
        this.min = -1;
        this.max = -1;
        this.forgeDefault = null;
        this.args = new Object[0];
        this.count = count;
        this.nested = false;
    }

    public Kind kind() { return kind; }
    /** Why the engine asked: the controller method or seam ({@code seam:mana}, {@code seam:cost:CostSacrifice}, ...). */
    public String ctx() { return ctx; }
    /** The deciding seat (may be the opponent of the priority player: TargetingPlayer, edicts, "an opponent chooses"). */
    public Player seat() { return seat; }
    public SpellAbility source() { return source; }
    /** The legal options as the engine passed them, or null (not carried by this call). */
    public Collection<?> options() { return options; }
    /** Option count, or -1 when the call carries no list. */
    public int optionCount() { return count; }
    public boolean nested() { return nested; }
    public int min() { return min; }
    public int max() { return max; }
    /** Forge AI's own answer, computed first, exactly as without any answerer. */
    public Object forgeDefault() { return forgeDefault; }
    public Object arg(final int i) { return i < args.length ? args[i] : null; }
    public int argCount() { return args.length; }
}
