package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * label-speed-1104 (measurement only): a wall-time profile of the R3 label search, on with
 * {@code -Dforge.r3.profile=true} (a JVM property, never a label key: the row JSON, its sha and every decision are the
 * same on or off). It reads the clock and writes one {@code [r3prof]} stderr line per row; nothing it records is read by
 * any decision.
 *
 * <p>Per thread, a stack of categories: time is charged to the innermost one (exclusive time). Per line, a record of its
 * phase (sel / tgt / draw), candidate, world, oppMin branch, wall, steps and a full-state digest at each proof leaf, for
 * the duplicate-subtree rate.
 */
public final class R3Prof {
    public static final boolean ON = Boolean.getBoolean("forge.r3.profile");

    public static final int LINE = 0, COPY = 1, PROOF = 2, POL_ME = 3, POL_OPP = 4, NATIVE = 5, CAC = 6, OTHER = 7;
    static final String[] NAMES = {"engine", "copy", "proof", "pol_me_solver", "pol_opp", "pol_native", "cac", "other"};

    private R3Prof() {
    }

    static final class Frame {
        final long[] acc = new long[NAMES.length];
        final int[] stack = new int[64];
        int depth = -1;
        long last;
        String phase = "?";
        int cand = -1, world = -1, branch = 0;
        /** The current line, or null. */
        JsonObject line;
        JsonArray digests;
        long lineT0;
        long[] lineAcc;
    }

    private static final ThreadLocal<Frame> TL = ThreadLocal.withInitial(Frame::new);
    /** Every finished line of the row in progress (any thread). */
    private static final ConcurrentLinkedQueue<JsonObject> LINES = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<long[]> ACCS = new ConcurrentLinkedQueue<>();

    public static void enter(final int cat) {
        if (!ON) return;
        final Frame f = TL.get();
        final long now = System.nanoTime();
        if (f.depth >= 0) f.acc[f.stack[f.depth]] += now - f.last;
        else f.acc[OTHER] += 0;
        f.stack[++f.depth] = cat;
        f.last = now;
    }

    public static void exit() {
        if (!ON) return;
        final Frame f = TL.get();
        final long now = System.nanoTime();
        if (f.depth >= 0) {
            f.acc[f.stack[f.depth]] += now - f.last;
            f.depth--;
        }
        f.last = now;
    }

    /** The unit context of the next lines on this thread. */
    public static void ctx(final String phase, final int cand, final int world) {
        if (!ON) return;
        final Frame f = TL.get();
        f.phase = phase;
        f.cand = cand;
        f.world = world;
        f.branch = 0;
    }

    public static void lineStart() {
        if (!ON) return;
        final Frame f = TL.get();
        f.line = new JsonObject();
        f.line.addProperty("ph", f.phase);
        f.line.addProperty("c", f.cand);
        f.line.addProperty("w", f.world);
        f.line.addProperty("b", f.branch++);
        f.digests = new JsonArray();
        f.lineT0 = System.nanoTime();
        f.lineAcc = f.acc.clone();
        enter(LINE);
    }

    public static void lineEnd(final LineEndInfo info) {
        if (!ON) return;
        exit();
        final Frame f = TL.get();
        if (f.line == null) return;
        f.line.addProperty("ms", (System.nanoTime() - f.lineT0) / 1e6);
        final JsonObject a = new JsonObject();
        for (int i = 0; i < NAMES.length; i++) {
            final long d = f.acc[i] - f.lineAcc[i];
            if (d > 0) a.addProperty(NAMES[i], Math.round(d / 1e5) / 10.0);
        }
        f.line.add("by", a);
        f.line.addProperty("steps", info.steps);
        f.line.addProperty("end", info.end);
        f.line.add("dg", f.digests);
        LINES.add(f.line);
        f.line = null;
    }

    /** A line's end, as the profile records it. */
    public static final class LineEndInfo {
        final int steps;
        final String end;

        public LineEndInfo(final int steps, final String end) {
            this.steps = steps;
            this.end = end;
        }
    }

    /** A full-state digest at a proof leaf of the current line (turn, phase, every zone in order, life, counters). */
    public static void digest(final Game g, final String tag) {
        if (!ON) return;
        final Frame f = TL.get();
        if (f.digests == null) return;
        f.digests.add(tag + ":" + fullDigest(g));
    }

    static String fullDigest(final Game g) {
        final StringBuilder sb = new StringBuilder();
        sb.append(g.getPhaseHandler().getTurn()).append(g.getPhaseHandler().getPhase())
                .append(g.getPlayers().indexOf(g.getPhaseHandler().getPlayerTurn()));
        for (Player p : g.getPlayers()) {
            sb.append("|L").append(p.getLife()).append('P').append(p.getPoisonCounters());
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Battlefield}) {
                sb.append('|').append(z.name().charAt(0));
                final List<String> cs = new ArrayList<>();
                for (Card c : p.getCardsIn(z)) {
                    String s = c.getName();
                    if (z == ZoneType.Battlefield) {
                        s += (c.isTapped() ? "*" : "") + c.getCounters() + (c.isCreature() ? c.getNetPower() + "/" + c.getNetToughness() : "");
                    }
                    cs.add(s);
                }
                if (z != ZoneType.Library) java.util.Collections.sort(cs);
                sb.append(String.join(",", cs));
            }
        }
        sb.append("|S").append(g.getStack().size());
        if (!g.getStack().isEmpty()) sb.append(g.getStack().peekAbility());
        return Long.toHexString(sb.toString().hashCode() * 0x9E3779B97F4A7C15L ^ sb.length());
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A named event of the row in progress (cache hits and the like). */
    public static void count(final String name) {
        if (!ON) return;
        COUNTS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    /** Before a row: clear what an earlier row left. */
    public static void rowStart() {
        if (!ON) return;
        LINES.clear();
        COUNTS.clear();
        final Frame f = TL.get();
        java.util.Arrays.fill(f.acc, 0);
    }

    /** After a row: one stderr line with its lines (every thread's) and this thread's totals. */
    public static void rowEnd(final String row, final double ms, final Object key) {
        if (!ON) return;
        final JsonObject o = new JsonObject();
        o.addProperty("row", row);
        o.addProperty("key", String.valueOf(key));
        o.addProperty("ms", ms);
        final JsonArray ls = new JsonArray();
        final long[] tot = new long[NAMES.length];
        for (JsonObject l; (l = LINES.poll()) != null; ) {
            ls.add(l);
            final JsonObject by = l.getAsJsonObject("by");
            for (int i = 0; i < NAMES.length; i++) {
                if (by.has(NAMES[i])) tot[i] += Math.round(by.get(NAMES[i]).getAsDouble() * 1e6);
            }
        }
        final JsonObject t = new JsonObject();
        for (int i = 0; i < NAMES.length; i++) t.addProperty(NAMES[i], tot[i] / 1e6);
        o.add("line_ms_by", t);
        final JsonObject cn = new JsonObject();
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicLong> e : new java.util.TreeMap<>(COUNTS).entrySet()) {
            cn.addProperty(e.getKey(), e.getValue().get());
        }
        o.add("counts", cn);
        o.add("lines", ls);
        System.err.println("[r3prof] " + o);
    }
}
