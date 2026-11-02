package forge.ai.dp;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import org.apache.commons.lang3.tuple.Pair;

import forge.game.Game;
import forge.game.GameRules;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * decisionpoint-1102 (layer 2; CENSUS.md §5.4): the process-wide side of the DecisionPoint interface.
 * <ul>
 * <li>the observer (null by default, and then nothing here runs: every controller returns Forge AI's answer through a
 * null check only);</li>
 * <li>which games are copies ({@link #markCopy}, from GameCopier) and which label row a copy was made for, so a
 * census can say "inside label lines and copies";</li>
 * <li>the label row the current thread works on ({@link #enterRow});</li>
 * <li>{@link #resolve}: the answer chain every instrumented controller method runs (choose-a-card adapter, then an
 * installed answerer, then the observer);</li>
 * <li>{@link #assertNoCheatShuffle}: the startup assertion that no research or live game lets an AI rearrange a shuffle
 * (CENSUS.md §4).</li>
 * </ul>
 * Nothing here draws randomness, allocates game ids or touches a card.
 */
public final class DecisionPoints {
    private DecisionPoints() {
    }

    private static volatile DecisionObserver observer;
    /** Copies (made while an observer was installed) -> the label row they were made for ("" = none). */
    private static final Map<Game, String> COPIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<String> ROW = new ThreadLocal<>();

    /** The installed observer, or null (the default). */
    public static DecisionObserver observer() {
        return observer;
    }

    public static void setObserver(final DecisionObserver o) {
        observer = o;
        if (o == null) {
            COPIES.clear();
        }
    }

    // ------------------------------------------------------------------ copies and rows (census attribution only)

    /** GameCopier: {@code copy} was made from {@code orig}. Recorded only while an observer is installed. */
    public static void markCopy(final Game copy, final Game orig) {
        if (observer == null || copy == null) {
            return;
        }
        final String parent = orig == null ? null : COPIES.get(orig);
        final String row = parent != null && !parent.isEmpty() ? parent : ROW.get();
        COPIES.put(copy, row == null ? "" : row);
    }

    public static boolean isCopy(final Game g) {
        return g != null && COPIES.containsKey(g);
    }

    /** The label row a decision in {@code g} belongs to: the copy's row, else the current thread's, else null. */
    public static String rowOf(final Game g) {
        final String r = g == null ? null : COPIES.get(g);
        return r != null && !r.isEmpty() ? r : ROW.get();
    }

    /** The labeller starts labelling row {@code key} (game_uid:seat:dec_idx) on this thread. */
    public static void enterRow(final String key) {
        if (observer != null) {
            ROW.set(key);
        }
    }

    public static void exitRow() {
        if (observer != null) {
            ROW.remove();
        }
    }

    // ------------------------------------------------------------------ notes from research answerers

    /**
     * Research code that answers a decision itself (the forced-win solver, the cube combo family plans, OppMin's forced
     * modes) notes it here, so a census counts who answered; a no-op without an observer.
     */
    public static void note(final Player seat, final Kind kind, final String ctx, final int optionCount, final String by) {
        final DecisionObserver o = observer;
        if (o != null) {
            o.observe(new DecisionPoint(kind, ctx, seat, null, optionCount), null, by);
        }
    }

    // ------------------------------------------------------------------ the answer chain

    /**
     * Answers {@code dp}: Forge AI's answer ({@code dp.forgeDefault()}), then the choose-a-card adapter ({@code cac},
     * null when the seat has no hook or it is suppressed; its answer is the hook's own, unchecked, exactly as before),
     * then an installed {@code answerer} (checked by {@link #legal}; refused = the answer so far stands), then the
     * observer. With all three null it returns Forge's answer and does nothing else.
     */
    public static Object resolve(final DecisionPoint dp, final DecisionAnswerer cac, final DecisionAnswerer answerer) {
        final Object def = dp.forgeDefault();
        Object out = def;
        String by = "forge";
        if (cac != null) {
            out = cac.answer(dp, def);
            if (!same(out, def)) {
                by = "cac";
            }
        }
        if (answerer != null) {
            final Object a = answerer.answer(dp, out);
            if (!same(a, out)) {
                if (legal(dp, a)) {
                    out = a;
                    by = "answerer";
                } else {
                    by = "refused";
                }
            }
        }
        final DecisionObserver o = observer;
        if (o != null) {
            o.observe(dp, out, by);
        }
        return out;
    }

    static boolean same(final Object a, final Object b) {
        return a == b || Objects.equals(a, b);
    }

    /**
     * Whether {@code answer} is one of {@code dp}'s legal answers, from the options the engine passed: a single option,
     * a subset of them within [min, max], or (ARRANGE) a permutation / partition of them. A point with no option list
     * accepts only Forge's own answer (an answerer for such a point validates itself: PRIORITY, checkpoints).
     */
    public static boolean legal(final DecisionPoint dp, final Object answer) {
        if (same(answer, dp.forgeDefault())) {
            return true;
        }
        final Collection<?> opts = dp.options();
        if (dp.kind() == Kind.NUMBER && answer instanceof Integer n && (opts == null || opts.isEmpty())) {
            return n >= dp.min() && (dp.max() < 0 || n <= dp.max()); // a range the engine passed as min/max
        }
        if (opts == null) {
            return false;
        }
        switch (dp.kind()) {
            case SELECT:
            case CHOICE:
                if (answer instanceof Collection<?> c) {
                    if (!opts.containsAll(c)) return false;
                    if (dp.min() >= 0 && c.size() < dp.min()) return false;
                    return dp.max() < 0 || c.size() <= dp.max();
                }
                return answer == null ? dp.min() == 0 : opts.contains(answer);
            case ARRANGE:
                if (answer instanceof Pair<?, ?> p && p.getLeft() instanceof Collection<?> l
                        && p.getRight() instanceof Collection<?> r) {
                    return l.size() + r.size() == opts.size() && opts.containsAll(l) && opts.containsAll(r);
                }
                if (answer instanceof Collection<?> c) {
                    return c.size() == opts.size() && opts.containsAll(c) && c.containsAll(opts);
                }
                return false;
            case NUMBER:
                if (answer instanceof Integer n) {
                    if (!opts.isEmpty()) return opts.contains(n);
                    return n >= dp.min() && (dp.max() < 0 || n <= dp.max());
                }
                return false;
            default:
                return false;
        }
    }

    /** The point every instrumented controller method builds (only when something is installed). */
    public static DecisionPoint point(final Kind kind, final String ctx, final Player seat, final SpellAbility source,
            final Collection<?> options, final int min, final int max, final Object def, final Object... args) {
        return new DecisionPoint(kind, ctx, seat, source, options, min, max, def, args);
    }

    /** As {@link #point}, for a point asked while the seat's controller was computing another answer. */
    public static DecisionPoint nestedPoint(final Kind kind, final String ctx, final Player seat,
            final SpellAbility source, final Collection<?> options, final int min, final int max, final Object def,
            final Object... args) {
        return new DecisionPoint(kind, ctx, seat, source, options, min, max, def, true, args);
    }

    // ------------------------------------------------------------------ cheatShuffle (CENSUS.md §4)

    /**
     * Startup assertion of every research and live game: the rules must not allow an AI to rearrange a shuffle
     * ({@code Player.shuffle} -> {@code controller.cheatShuffle}; AiController does it only with the profile's
     * CHEAT_WITH_MANA_ON_SHUFFLE AND this rules flag). Throws instead of playing a game whose chance stream an AI seat
     * may bend.
     */
    public static void assertNoCheatShuffle(final GameRules rules, final String where) {
        if (rules != null && rules.isAllowCheatShuffle()) {
            throw new IllegalStateException("decisionpoint-1102: " + where
                    + " would allow AI cheat shuffles (GameRules.allowCheatShuffle); research and live games refuse it");
        }
    }
}
