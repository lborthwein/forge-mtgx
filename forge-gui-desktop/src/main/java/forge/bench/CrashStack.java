package forge.bench;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Lane void-causes-1104: the WHOLE stack of a per-game crash, for stderr and for the game's anomaly record.
 *
 * <p>Before this, the per-game catches printed {@code e.toString()} plus at most 14 non-JDK frames of the root cause, so
 * the S2 label voids (games 2989384742 and 2989424198) left only "java.lang.NullPointerException". Here every frame of
 * the exception, of each cause and of each suppressed exception is kept, JDK frames included (up to
 * {@link #MAX_FRAMES} per exception, the rest counted).
 *
 * <p>An exception with NO frames is flagged {@code frames_omitted}: HotSpot's C2 throws a preallocated, frameless
 * NullPointerException (and ArithmeticException, ArrayIndexOutOfBounds, ClassCast, ArrayStore) at a site that has
 * thrown often, unless the JVM runs with {@code -XX:-OmitStackTraceInFastThrow}. The record carries that flag's value
 * ({@code omit_fast_throw}) so a frameless record says why.
 *
 * <p>Measurement only: nothing here is read by a decision, a digest or a label.
 */
public final class CrashStack {
    private CrashStack() {
    }

    /** Frames kept per exception; the rest are counted in {@code frames_more}. */
    static final int MAX_FRAMES = 400;

    /** Causes and suppressed exceptions kept, in all. */
    static final int MAX_LINKED = 16;

    private static volatile Boolean omitFastThrow;

    /** The JVM's {@code OmitStackTraceInFastThrow} (true = frameless fast throws); null when it cannot be read. */
    public static Boolean omitFastThrow() {
        Boolean v = omitFastThrow;
        if (v == null) {
            try {
                final com.sun.management.HotSpotDiagnosticMXBean hs = ManagementFactory
                        .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
                v = Boolean.valueOf(hs.getVMOption("OmitStackTraceInFastThrow").getValue());
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
            omitFastThrow = v;
        }
        return v;
    }

    /** The anomaly record: {@code {exception, frames, frames_more, frames_omitted, caused_by, suppressed, ...}}. */
    public static JsonObject json(final Throwable e) {
        final JsonObject o = one(e);
        final JsonArray causes = new JsonArray();
        final JsonArray suppressed = new JsonArray();
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(e);
        int linked = 0;
        for (Throwable s : e.getSuppressed()) {
            if (linked++ >= MAX_LINKED || !seen.add(s)) {
                break;
            }
            suppressed.add(one(s));
        }
        for (Throwable c = e.getCause(); c != null && linked < MAX_LINKED && seen.add(c); c = c.getCause()) {
            causes.add(one(c));
            linked++;
            for (Throwable s : c.getSuppressed()) {
                if (linked++ >= MAX_LINKED || !seen.add(s)) {
                    break;
                }
                suppressed.add(one(s));
            }
        }
        if (causes.size() > 0) {
            o.add("caused_by", causes);
        }
        if (suppressed.size() > 0) {
            o.add("suppressed", suppressed);
        }
        final Boolean oft = omitFastThrow();
        if (oft != null) {
            o.addProperty("omit_fast_throw", oft);
        }
        o.addProperty("thread", Thread.currentThread().getName());
        return o;
    }

    private static JsonObject one(final Throwable t) {
        final JsonObject o = new JsonObject();
        o.addProperty("exception", String.valueOf(t));
        final StackTraceElement[] st = t.getStackTrace();
        final JsonArray fr = new JsonArray();
        for (int i = 0; i < Math.min(st.length, MAX_FRAMES); i++) {
            fr.add(st[i].toString());
        }
        o.add("frames", fr);
        if (st.length > MAX_FRAMES) {
            o.addProperty("frames_more", st.length - MAX_FRAMES);
        }
        o.addProperty("frames_omitted", st.length == 0);
        return o;
    }

    /** The record as {@code printStackTrace}-style text, each line prefixed (one string: one write, never interleaved). */
    public static String text(final String prefix, final JsonObject rec) {
        final StringBuilder b = new StringBuilder();
        lines(b, prefix, "", rec);
        if (rec.has("suppressed")) {
            for (com.google.gson.JsonElement s : rec.getAsJsonArray("suppressed")) {
                lines(b, prefix, "Suppressed: ", s.getAsJsonObject());
            }
        }
        if (rec.has("caused_by")) {
            for (com.google.gson.JsonElement c : rec.getAsJsonArray("caused_by")) {
                lines(b, prefix, "Caused by: ", c.getAsJsonObject());
            }
        }
        if (rec.get("frames_omitted").getAsBoolean()) {
            final boolean noFastThrow = rec.has("omit_fast_throw") && !rec.get("omit_fast_throw").getAsBoolean();
            b.append(prefix).append("  (no frames: ").append(noFastThrow ? "the exception was created without a stack"
                    : "HotSpot's fast throw dropped them; run the JVM with -XX:-OmitStackTraceInFastThrow").append(")\n");
        }
        return b.toString();
    }

    private static void lines(final StringBuilder b, final String prefix, final String head, final JsonObject o) {
        b.append(prefix).append(head).append(o.get("exception").getAsString()).append('\n');
        for (com.google.gson.JsonElement f : o.getAsJsonArray("frames")) {
            b.append(prefix).append("  at ").append(f.getAsString()).append('\n');
        }
        if (o.has("frames_more")) {
            b.append(prefix).append("  ... ").append(o.get("frames_more").getAsInt()).append(" more\n");
        }
    }

    /** Print the whole record to stderr in one write; returns it. */
    public static JsonObject print(final String prefix, final String headline, final Throwable e) {
        final JsonObject rec = json(e);
        final StringBuilder b = new StringBuilder();
        b.append(headline).append('\n').append(text(prefix, rec));
        System.err.print(b);
        System.err.flush();
        return rec;
    }
}
