package forge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import forge.ai.simulation.PlayoutKeys;
import forge.game.Game;
import forge.game.GlobalIds;
import forge.game.cost.CostPart;
import forge.game.cost.PaymentDecision;
import forge.game.player.DecisionTap;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * THE AI SEAT'S DECISION TAPE (lane replay-record-1021; owner, 2026-10-21: "Replays must of course save all moves by
 * both players, otherwise they're not replays and will become broken any time we update or fix the AI").
 *
 * <p>RECORD: every decision the AI seat makes in the live game is written, as it is made, to stderr as one
 * {@code [ai-tape] <json>} line per top-level entry (the host journals it; it never reaches a browser). FORCED: a replay
 * reads those entries back and the seat answers from them instead of deciding: no search, no policy service, no Forge AI
 * deliberation, and so no wall-clock dependence. A recorded answer that is not legal now, or a decision of another kind
 * than the record's next one, is a DIVERGENCE: reported at that decision ({@code [ai-tape] {"divergence":...}}) and the
 * game stops. Nothing is ever re-searched silently.
 *
 * <p>WHERE THE ENTRIES COME FROM. (1) {@link AiTapeController}: the seat's controller, swapped in after the seat was
 * bound, records each top-level {@link forge.game.player.PlayerController} call (a call on the game thread with no
 * decision open; the AI's own nested calls while deliberating are not decisions). Choice methods are forced by decoding
 * the answer; execution methods (play a trigger, pay a cost) run in both modes, and the decisions made while they run are
 * their child entries. (2) Hooks in the code that executes the seat's actions: each cost part's decision
 * ({@link DecisionTap}, {@link forge.game.cost.CostPayment}), each mana source the AI taps
 * ({@link ComputerUtilMana}), an SA's targets and X right before it is paid ({@link ComputerUtil}), the order of
 * simultaneous triggers ({@link PlayerControllerAi#orderSimultaneousSa}).
 *
 * <p>SYNC. Skipping the AI's deliberation must not change the game: deliberation draws from the game's RNG (Forge's AI
 * rolls dice for its own choices), creates abilities (process-wide id counters, which feed hash order) and cards, and
 * takes timestamps. So every entry records the state it left behind: the RNG ({@link PlayoutKeys.TrackedRandom}, the same
 * stream as {@code new Random(seed)}), the card-id counters, the timestamp and the {@link GlobalIds}; a forced replay
 * restores it after each entry (id counters only ever forward). An entry whose start state differs from the state the
 * last entry left also records its start ({@code pre}); a forced replay restores that too and counts it as drift.
 *
 * <p>IDENTITY. Cards by Forge card id (deterministic: the counters are synced), players by seat, abilities by host card id
 * plus a hash of their description, choices among offered options by the option's identity. No card names, except where a
 * name IS the answer (naming a card).
 */
public final class AiTape {
    public enum Mode { RECORD, FORCED }

    public static final String PREFIX = "[ai-tape] ";
    public static final int FORMAT = 1;

    /** A forced replay that cannot go on: the record's next answer is not this game's. */
    public static final class Divergence extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Divergence(final String message) {
            super(message);
        }
    }

    /** The live game's tapes: one per taped AI seat (a one-seat game's AI; both seats of a watch table). */
    private static final java.util.concurrent.CopyOnWriteArrayList<AiTape> installed = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The engine's seam ({@link DecisionTap}): the installed tape of the paying player, if any. */
    private static final DecisionTap.Tap DISPATCH = new DecisionTap.Tap() {
        @Override
        public boolean covers(final Player payer) {
            for (AiTape t : installed) {
                if (t.covers(payer)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public PaymentDecision cost(final Player payer, final CostPart part, final int index, final SpellAbility ability,
                final Supplier<PaymentDecision> decide) {
            for (AiTape t : installed) {
                if (t.covers(payer)) {
                    return t.cost(payer, part, index, ability, decide);
                }
            }
            return decide.get();
        }
    };

    /** The first installed tape, or null (every game without one: nothing anywhere changes). */
    public static AiTape current() {
        return installed.isEmpty() ? null : installed.get(0);
    }

    /** The tape when {@code p}'s decisions, on this thread, are taped (record or forced) and a hook may act; else null. */
    public static AiTape covering(final Player p) {
        for (AiTape t : installed) {
            if (t.covers(p) && t.hookHere()) {
                return t;
            }
        }
        return null;
    }

    /** RECORD or FORCED; a RESUME tape is FORCED until its record runs out, then RECORD (see {@link #resume}). */
    volatile Mode mode;
    /** A resumed game (a restore, a continuation): the seat is bound, and after the record it decides live. */
    final boolean resume;
    /**
     * A strict resume (a restore: the game handed back must be the game that was played): a decision that is not the
     * record's next one is a divergence. Otherwise (a continuation, a replay to a turn) the seat plays on live from there.
     */
    private boolean strict;
    final Game game;
    final Player seat;
    final int seatIndex;
    private final PlayoutKeys.TrackedRandom rng;
    private final PrintStream out;
    private volatile Thread gameThread;

    // RECORD
    private static final class Rec {
        final JsonObject o = new JsonObject();
        final boolean exec;
        final String pre;
        /** The start state differs from what the last entry left (so the entry carries it). */
        final boolean preNew;
        JsonArray sub;

        Rec(final boolean exec, final String pre, final boolean preNew) {
            this.exec = exec;
            this.pre = pre;
            this.preNew = preNew;
        }
    }

    private final ArrayDeque<Rec> open = new ArrayDeque<>();

    // FORCED
    private static final class Cur {
        final JsonArray list;
        final boolean exec;
        int i;

        Cur(final JsonArray list, final boolean exec) {
            this.list = list;
            this.exec = exec;
        }
    }

    private JsonArray tape;
    private final ArrayDeque<Cur> cursors = new ArrayDeque<>();

    private String lastPost;
    private int entries;
    private int children;
    private int forcedEntries;
    private int drift;
    private int ahead;
    private int unencodable;
    private long bytes;
    private long recordNanos;
    private String divergence;
    private final List<String> driftNotes = new ArrayList<>();

    /** Which sync fields differ (record vs replay), by name. */
    static String syncDiff(final String want, final String got) {
        final String[] names = {"rng", "cid", "hid", "ts", "gameIds", "saIds", "stackIds", "trigIds", "replIds", "staticIds", "combatIds"};
        final String[] w = want.split("\\."), g = got.split("\\.");
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(w.length, g.length); i++) {
            final String a = i < w.length ? w[i] : "-", b = i < g.length ? g[i] : "-";
            if (!a.equals(b)) {
                sb.append(i < names.length ? names[i] : "f" + i).append(' ').append(a).append("->").append(b).append(' ');
            }
        }
        return sb.toString().trim();
    }
    private boolean finished;

    private AiTape(final Mode mode, final Game game, final Player seat, final PlayoutKeys.TrackedRandom rng,
            final PrintStream out, final JsonArray tape, final boolean resume) {
        this.mode = mode;
        this.resume = resume;
        this.game = game;
        this.seat = seat;
        this.seatIndex = game.getRegisteredPlayers().indexOf(seat);
        this.rng = rng;
        this.out = out;
        this.tape = tape;
    }

    /** RECORD the seat's decisions in {@code game}; {@code rng} must be the game's RNG (MyRandom's). */
    public static AiTape record(final Game game, final Player seat, final PlayoutKeys.TrackedRandom rng, final PrintStream out) {
        return new AiTape(Mode.RECORD, game, seat, rng, out, null, false);
    }

    /** FORCED: answer the seat's decisions from {@code lines} (the top-level entries, one JSON object per line). */
    public static AiTape forced(final Game game, final Player seat, final PlayoutKeys.TrackedRandom rng, final PrintStream out,
            final List<String> lines) {
        return new AiTape(Mode.FORCED, game, seat, rng, out, parse(lines), false);
    }

    /**
     * RESUME: a game rebuilt from its journal (a restore after a host restart, a "Continue from here", a replay to a
     * turn) on a seat bound as live. Its recorded decisions are forced, each echoed ({@code "r":1}) so that the new
     * game's own journal holds them too; when the record runs out the seat decides live and is recorded.
     */
    public static AiTape resume(final Game game, final Player seat, final PlayoutKeys.TrackedRandom rng, final PrintStream out,
            final List<String> lines) {
        return new AiTape(Mode.FORCED, game, seat, rng, out, parse(lines), true);
    }

    /** A RESTORE: {@link #resume}, strict (see {@link #strict}). */
    public static AiTape restore(final Game game, final Player seat, final PlayoutKeys.TrackedRandom rng, final PrintStream out,
            final List<String> lines) {
        final AiTape t = new AiTape(Mode.FORCED, game, seat, rng, out, parse(lines), true);
        t.strict = true;
        return t;
    }

    private static JsonArray parse(final List<String> lines) {
        final JsonArray t = new JsonArray();
        for (String l : lines) {
            if (l == null || l.isBlank()) {
                continue;
            }
            String s = l.trim();
            if (s.startsWith(PREFIX.trim())) {
                s = s.substring(PREFIX.trim().length()).trim();
            }
            final JsonElement e = JsonParser.parseString(s);
            if (e.isJsonObject() && e.getAsJsonObject().has("k")) {
                t.add(e);
            }
        }
        return t;
    }

    /**
     * True when this decision is recorded, false when it is forced. A resumed game whose record has run out (no frame
     * open) goes live here: from now on every decision is the seat's own, recorded.
     */
    boolean recordsNow(final String k, final String m) {
        if (mode == Mode.FORCED && resume && cursors.isEmpty()) {
            if (forcedTop >= tape.size()) {
                goLive(null);
            } else {
                // a resumed game whose next decision is not the record's (a replay to a turn handed over before the
                // record's end, a newer engine) plays on live rather than stop a game a player is about to take over
                final JsonObject next = tape.get(forcedTop).getAsJsonObject();
                if (!strict && (!k.equals(str(next, "k")) || (m != null && !m.equals(str(next, "m"))))) {
                    goLive("the game asked for " + what(k, m) + " where the record has " + what(str(next, "k"), str(next, "m")));
                }
            }
        }
        return mode == Mode.RECORD;
    }

    private void goLive(final String why) {
        mode = Mode.RECORD;
        entries = forcedTop;
        final JsonObject o = new JsonObject();
        o.addProperty("at", forcedTop);
        o.addProperty("of", tape.size());
        if (why != null) {
            o.addProperty("early", why);
        }
        o.addProperty("forced", forcedEntries);
        o.addProperty("drift", drift);
        o.addProperty("turn", game.getPhaseHandler().getTurn());
        emit("live", o);
    }

    /**
     * Several seats taped in one game (a watch table): each top-level entry and status line carries its seat
     * ({@code "s"}), and a forced or resumed tape reads only its own seat's entries. Before {@link #install}.
     */
    private boolean tagged;

    public AiTape tagSeat() {
        tagged = true;
        if (tape != null) {
            final JsonArray own = new JsonArray();
            for (JsonElement e : tape) {
                final JsonObject o = e.getAsJsonObject();
                if (o.has("s") && o.get("s").getAsInt() == seatIndex) {
                    own.add(o);
                }
            }
            tape = own;
        }
        return this;
    }

    /** Make this a live tape: the controller swap and the hooks start reading it. */
    public void install() {
        installed.add(this);
        DecisionTap.install(DISPATCH);
        final JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("mode", resume ? (strict ? "restore" : "resume") : mode == Mode.RECORD ? "record" : "forced");
        o.addProperty("seat", seatIndex);
        if (tape != null) {
            o.addProperty("entries", tape.size());
        }
        emit("boot", o);
    }

    /** The thread the game runs on: only its calls are decisions (set when the game starts). */
    public void bindGameThread(final Thread t) {
        gameThread = t;
    }

    public void uninstall() {
        installed.remove(this);
        if (installed.isEmpty()) {
            DecisionTap.install(null);
        }
    }

    public boolean isForced() {
        return mode == Mode.FORCED;
    }

    public String divergence() {
        return divergence;
    }

    // ------------------------------------------------------------------ where decisions are taped

    public boolean covers(final Player payer) {
        return payer == seat && payer.getGame() == game && Thread.currentThread() == gameThread && divergence == null;
    }

    /** A controller call here is a decision: on the game thread, and no choice frame open (the AI's own deliberation). */
    boolean controllerHere() {
        if (Thread.currentThread() != gameThread || divergence != null) {
            return false;
        }
        return mode == Mode.RECORD ? open.isEmpty() || open.peek().exec : cursors.isEmpty() || cursors.peek().exec;
    }

    boolean hookHere() {
        return controllerHere();
    }

    // ------------------------------------------------------------------ sync state

    /** rng(3) . card ids(2) . timestamp . global ids(7), hex, '.'-joined. */
    String sync() {
        final StringBuilder sb = new StringBuilder(96);
        final long[] r = rng.snapshot();
        sb.append(Long.toHexString(r[0]));
        if (r[1] != 0) {
            sb.append('g').append(Long.toHexString(r[2]));
        }
        final int[] c = game.peekCardIdCounters();
        sb.append('.').append(Integer.toHexString(c[0])).append('.').append(Integer.toHexString(c[1]));
        sb.append('.').append(Long.toHexString(game.getTimestamp()));
        for (int v : GlobalIds.capture()) {
            sb.append('.').append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /** Restore {@code s} (from {@link #sync}); returns how many counters were already past it. */
    private int restore(final String s) {
        final String[] p = s.split("\\.");
        final String r = p[0];
        final int g = r.indexOf('g');
        final long seed = Long.parseUnsignedLong(g < 0 ? r : r.substring(0, g), 16);
        final long gauss = g < 0 ? 0 : Long.parseUnsignedLong(r.substring(g + 1), 16);
        rng.restore(new long[] {seed, g < 0 ? 0 : 1, gauss});
        int behind = 0;
        final int[] c = game.peekCardIdCounters();
        final int cid = Integer.parseUnsignedInt(p[1], 16);
        final int hid = Integer.parseUnsignedInt(p[2], 16);
        if (c[0] > cid || c[1] > hid) {
            behind++;
        }
        game.dangerouslySetCardIdCounters(Math.max(c[0], cid), Math.max(c[1], hid));
        final long ts = Long.parseUnsignedLong(p[3], 16);
        if (game.getTimestamp() > ts) {
            behind++;
        } else {
            game.dangerouslySetTimestamp(ts);
        }
        final int[] ids = new int[p.length - 4];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = Integer.parseUnsignedInt(p[4 + i], 16);
        }
        return behind + GlobalIds.restoreForward(ids);
    }

    // ------------------------------------------------------------------ RECORD

    /** Open an entry of kind {@code k} ({@code m}: the method or hook detail). Exec entries take children. */
    Object begin(final String k, final String m, final boolean exec) {
        final long t0 = System.nanoTime();
        final String pre = sync();
        final Rec r = new Rec(exec, pre, !pre.equals(lastPost));
        r.o.addProperty("k", k);
        if (m != null) {
            r.o.addProperty("m", m);
        }
        open.push(r);
        recordNanos += System.nanoTime() - t0;
        return r;
    }

    /** A field of the open entry {@code token} (from {@link #begin}). */
    void put(final Object token, final String key, final JsonElement v) {
        ((Rec) token).o.add(key, v == null ? JsonNull.INSTANCE : v);
    }

    /** Close {@code token} with answer {@code a}: written (top level) or kept as its parent's child. */
    void end(final Object token, final JsonElement a) {
        final long t0 = System.nanoTime();
        final Rec r = (Rec) token;
        if (open.peek() != r) {
            // an exception unwound an inner entry without closing it: drop the inner ones (they never finished)
            while (!open.isEmpty() && open.peek() != r) {
                open.pop();
            }
        }
        open.pop();
        if (a != null) {
            r.o.add("a", a);
        }
        if (r.sub != null) {
            r.o.add("sub", r.sub);
        }
        final String post = sync();
        if (r.preNew) {
            r.o.addProperty("pre", r.pre);
        }
        r.o.addProperty("post", post);
        lastPost = post;
        if (open.isEmpty()) {
            r.o.addProperty("n", entries++);
            r.o.addProperty("t", game.getPhaseHandler().getTurn());
            if (tagged) {
                r.o.addProperty("s", seatIndex);
            }
            final String line = r.o.toString();
            bytes += line.length() + PREFIX.length() + 1;
            out.println(PREFIX + line);
        } else {
            final Rec parent = open.peek();
            if (parent.sub == null) {
                parent.sub = new JsonArray();
            }
            parent.sub.add(r.o);
            children++;
        }
        recordNanos += System.nanoTime() - t0;
    }

    void noteUnencodable() {
        unencodable++;
    }

    /** Time spent encoding an answer (the record's own cost, beside {@link #begin} / {@link #end}'s). */
    void noteRecordNanos(final long nanos) {
        recordNanos += nanos;
    }

    // ------------------------------------------------------------------ FORCED

    /** The next entry, which must be of kind {@code k} and detail {@code m}; its start state restored. */
    JsonObject take(final String k, final String m) {
        final JsonArray list = cursors.isEmpty() ? tape : cursors.peek().list;
        final int at = cursors.isEmpty() ? forcedTop : cursors.peek().i;
        if (cursors.isEmpty()) {
            currentTop = forcedTop;
        }
        if (at >= list.size()) {
            throw diverge("the record has no " + what(k, m) + " here: " + (cursors.isEmpty()
                    ? "the tape ended after " + list.size() + " entries" : "its frame had only " + list.size() + " entries"));
        }
        final JsonObject e = list.get(at).getAsJsonObject();
        final String ek = str(e, "k");
        final String em = str(e, "m");
        if (!k.equals(ek) || (m != null && !m.equals(em))) {
            throw diverge("the game asked for " + what(k, m) + " where the record has " + what(ek, em));
        }
        if (cursors.isEmpty()) {
            forcedTop++;
        } else {
            cursors.peek().i++;
        }
        final String pre = e.has("pre") ? str(e, "pre") : lastPost;
        if (pre != null) {
            final String now = sync();
            if (!pre.equals(now)) {
                drift++;
                if (driftNotes.size() < 8) {
                    driftNotes.add((cursors.isEmpty() ? "n" + (forcedTop - 1) : "child") + " " + what(ek, em) + " T"
                            + game.getPhaseHandler().getTurn() + ": " + syncDiff(pre, now));
                }
                ahead += restore(pre);
            }
        }
        return e;
    }

    private int forcedTop;
    /** The top-level entry being forced (what a divergence names). */
    private int currentTop;

    /** Run {@code e}'s children (an execution entry that runs in the replay too). */
    void enter(final JsonObject e, final boolean exec) {
        cursors.push(new Cur(e.has("sub") ? e.getAsJsonArray("sub") : new JsonArray(), exec));
    }

    void leave(final JsonObject e) {
        final Cur c = cursors.pop();
        if (c.i < c.list.size()) {
            final JsonObject next = c.list.get(c.i).getAsJsonObject();
            throw diverge(what(str(e, "k"), str(e, "m")) + " ended with " + (c.list.size() - c.i)
                    + " of its recorded decisions unused (next: " + what(str(next, "k"), str(next, "m")) + ")");
        }
    }

    /** {@code e} answered: the state it left restored. */
    void done(final JsonObject e) {
        final String post = str(e, "post");
        if (post != null) {
            if (!post.equals(sync())) {
                ahead += restore(post);
            }
            lastPost = post;
        }
        forcedEntries++;
        if (resume && cursors.isEmpty()) {
            // the resumed game's own journal keeps its forced decisions too (a restore's host already has them)
            final JsonObject echo = e.deepCopy();
            echo.addProperty("r", 1);
            out.println(PREFIX + echo);
        }
    }

    /** Report a divergence (once) and return the exception that stops the game. */
    Divergence diverge(final String why) {
        if (divergence == null) {
            divergence = why;
            final JsonObject o = new JsonObject();
            o.addProperty("divergence", why);
            o.addProperty("entry", currentTop);
            o.addProperty("turn", game.getPhaseHandler().getTurn());
            o.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
            o.addProperty("forced", forcedEntries);
            emit("divergence", o);
        }
        // the reason (card ids, hashes) stays on stderr; what may reach a player says only where
        return new Divergence("the AI's recorded decision " + currentTop + " does not fit this game");
    }

    private static String what(final String k, final String m) {
        return m == null ? String.valueOf(k) : k + ":" + m;
    }

    static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    // ------------------------------------------------------------------ the end

    /** The tape's end line: counts, and (forced) the top-level entries the game never asked for. */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        final JsonObject o = new JsonObject();
        o.addProperty("end", true);
        o.addProperty("mode", resume ? "resume" : mode == Mode.RECORD ? "record" : "forced");
        if (mode == Mode.RECORD) {
            if (resume) {
                o.addProperty("forced", forcedEntries);
                o.addProperty("drift", drift);
            }
            o.addProperty("entries", entries);
            o.addProperty("children", children);
            o.addProperty("bytes", bytes);
            o.addProperty("recordMs", Math.round(recordNanos / 1e5) / 10.0);
            o.addProperty("unencodable", unencodable);
        } else {
            o.addProperty("entries", tape.size());
            o.addProperty("used", forcedTop);
            o.addProperty("forced", forcedEntries);
            o.addProperty("drift", drift);
            if (!driftNotes.isEmpty()) {
                final JsonArray dn = new JsonArray();
                driftNotes.forEach(dn::add);
                o.add("driftNotes", dn);
            }
            o.addProperty("ahead", ahead);
            if (divergence != null) {
                o.addProperty("divergence", divergence);
            }
        }
        emit("end", o);
    }

    private void emit(final String what, final JsonObject o) {
        final JsonObject line = new JsonObject();
        line.addProperty("k", what);
        if (tagged) {
            line.addProperty("s", seatIndex);
        }
        for (String key : o.keySet()) {
            line.add(key, o.get(key));
        }
        out.println(PREFIX + line);
    }

    // ------------------------------------------------------------------ hooks

    PaymentDecision cost(final Player payer, final CostPart part, final int index, final SpellAbility ability,
            final Supplier<PaymentDecision> decide) {
        if (!hookHere()) {
            return decide.get();
        }
        final String m = part.getClass().getSimpleName();
        if (recordsNow("cost", m)) {
            final Object r = begin("cost", m, false);
            put(r, "i", new JsonPrimitive(index));
            PaymentDecision d = null;
            try {
                d = decide.get();
            } finally {
                final PaymentDecision chosen = d;
                end(r, safe(() -> AiTapeCodec.decision(game, chosen)));
            }
            return d;
        }
        final JsonObject e = take("cost", m);
        final PaymentDecision d;
        try {
            d = AiTapeCodec.decision(game, e.get("a"));
        } catch (Divergence x) {
            throw x;
        } catch (RuntimeException x) {
            throw diverge("cost " + m + ": " + x.getMessage());
        }
        done(e);
        return d;
    }

    /** The mana source the seat taps for one shard (ComputerUtilMana's real payment loop). */
    SpellAbility mana(final Collection<SpellAbility> options, final Supplier<SpellAbility> choose) {
        if (recordsNow("mana", null)) {
            final Object r = begin("mana", null, false);
            SpellAbility ma = null;
            try {
                ma = choose.get();
            } finally {
                final SpellAbility chosen = ma;
                end(r, safe(() -> AiTapeCodec.manaChoice(game, chosen)));
            }
            return ma;
        }
        final JsonObject e = take("mana", null);
        final SpellAbility ma;
        try {
            ma = AiTapeCodec.manaChoice(game, options, e.get("a"));
        } catch (RuntimeException x) {
            throw diverge("mana source: " + x.getMessage());
        }
        done(e);
        return ma;
    }

    /** An SA's targets and X right before it is paid ({@code where}: h|s|n|c); forced: the record's are set. */
    void config(final SpellAbility sa, final String where) {
        if (recordsNow("cfg", where)) {
            final Object r = begin("cfg", where, false);
            end(r, safe(() -> AiTapeCodec.config(game, seat, sa)));
            return;
        }
        final JsonObject e = take("cfg", where);
        try {
            AiTapeCodec.applyConfig(game, seat, sa, e.get("a"));
        } catch (RuntimeException x) {
            throw diverge("targets of " + AiTapeCodec.saWhat(sa) + ": " + x.getMessage());
        }
        done(e);
    }

    /** The order the seat plays simultaneous abilities in. */
    List<SpellAbility> order(final List<SpellAbility> sas, final Supplier<List<SpellAbility>> choose) {
        if (recordsNow("order", null)) {
            // the AI may reorder the list it is given: positions are the list's as offered
            final List<SpellAbility> offered = new ArrayList<>(sas);
            final Object r = begin("order", null, false);
            List<SpellAbility> o = null;
            try {
                o = choose.get();
            } finally {
                final List<SpellAbility> chosen = o;
                end(r, safe(() -> AiTapeCodec.indices(offered, chosen)));
            }
            return o;
        }
        final JsonObject e = take("order", null);
        final List<SpellAbility> o;
        try {
            o = AiTapeCodec.pick(sas, e.get("a"));
        } catch (RuntimeException x) {
            throw diverge("trigger order: " + x.getMessage());
        }
        done(e);
        return new ArrayList<>(o);
    }

    /** An encoding that cannot fail the live game: an unencodable answer is recorded as such (a forced replay stops there). */
    JsonElement safe(final Supplier<JsonElement> enc) {
        final long t0 = System.nanoTime();
        try {
            return enc.get();
        } catch (RuntimeException e) {
            noteUnencodable();
            return AiTapeCodec.unencodable(e);
        } finally {
            recordNanos += System.nanoTime() - t0;
        }
    }

    /**
     * Whether the seat plays a triggered ability it must prepare (its modes, its targets): PlayerControllerAi's
     * prepareSingleSa, the policy seat's prepareForSeat. Recorded with the decisions made while preparing (a charm's
     * modes, another player's targets) as children. Forced: the record's answer; a declined trigger is declined again
     * without asking anything, an accepted one runs {@code nonAi} (the parts that are not the AI's own choice) against
     * the recorded children. The targets themselves are the payment checkpoint's.
     */
    boolean prepared(final java.util.function.BooleanSupplier body) {
        if (recordsNow("prep", null)) {
            final Object r = begin("prep", null, true);
            boolean ok = false;
            try {
                ok = body.getAsBoolean();
            } finally {
                end(r, new JsonPrimitive(ok));
            }
            return ok;
        }
        throw new IllegalStateException("a forced replay prepares triggers with forcedPrep");
    }

    /** Forced: see {@link #prepared}. */
    boolean forcedPrep(final java.util.function.BooleanSupplier nonAi) {
        if (recordsNow("prep", null)) {
            return prepared(nonAi);
        }
        final JsonObject e = take("prep", null);
        final boolean want = e.get("a").getAsBoolean();
        if (!want) {
            done(e);
            return false;
        }
        enter(e, true);
        final boolean got = nonAi.getAsBoolean();
        leave(e);
        if (!got) {
            throw diverge("a trigger the record prepared could not be prepared in the replay");
        }
        done(e);
        return true;
    }

    /** PlayerControllerAi.prepareSingleSa and the policy seat's own: {@code body} (recorded) unless {@code p} is not taped. */
    public static boolean prepare(final Player p, final java.util.function.BooleanSupplier body) {
        final AiTape t = covering(p);
        return t == null ? body.getAsBoolean() : t.prepared(body);
    }

    // ------------------------------------------------------------------ static hook entry points

    /** ComputerUtil's checkpoints: {@code sa} is about to be paid for (and put on the stack or resolved). */
    public static void checkpoint(final Player ai, final SpellAbility sa, final String where) {
        final AiTape t = covering(ai);
        if (t != null && sa != null) {
            t.config(sa, where);
        }
    }
}
