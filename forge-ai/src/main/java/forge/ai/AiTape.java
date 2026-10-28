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
    /**
     * Lane bvw-decision-log-1027: the first id of a decode's detached scope. A bench game's own ids start at 1e9 (its
     * IdScope) and a cube combo seat's probe ids at 1.1e9, so "built by this decode" is this range, not "id >= 1e9".
     */
    static final int DECODE_BASE = 1_060_000_000;
    static final int DECODE_SPAN = 30_000_000;

    /** An id a decode's detached scope handed out ({@link #DECODE_BASE}). */
    static boolean decodeId(final int id) {
        return id > DECODE_BASE && id <= DECODE_BASE + DECODE_SPAN;
    }

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
    /**
     * Lane bvw-decision-log-1027: the game thread's own id scope when the game runs inside one (a bench game: RlActorBench
     * opens one per game, ids from 1e9), else null (a live server game: the process-wide counters). The sync reads and
     * restores this scope's counters; a trait built in another scope is the AI's look-ahead's.
     */
    private volatile Object gameScope;

    // RECORD
    /**
     * Frame order across every tape of the game (both seats of a watch or bot table): a trait built while frames of
     * several tapes are open belongs to the most recently begun one, the innermost (review forge-mtgx#110, S2-2).
     */
    private static final java.util.concurrent.atomic.AtomicLong FRAMES = new java.util.concurrent.atomic.AtomicLong();

    private static final class Rec {
        final long seq = FRAMES.incrementAndGet();
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
        final long seq = FRAMES.incrementAndGet();
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
    /** Drift in anything but the combat views' ids: a replay with any is not exact. */
    private int gameDrift;
    private int ahead;
    private int unencodable;
    private long bytes;
    private long recordNanos;
    private String divergence;
    private final List<String> driftNotes = new ArrayList<>();
    private int resync;
    private final java.util.TreeSet<String> driftFields = new java.util.TreeSet<>();
    private final java.util.TreeSet<String> resyncFields = new java.util.TreeSet<>();
    private final java.util.TreeSet<String> aheadFields = new java.util.TreeSet<>();

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
        synchronized (installed) {
            // bvw-decision-log-1027: a bench JVM plays several games at once; the seams are installed under one lock
            installed.add(this);
            DecisionTap.install(DISPATCH);
            forge.game.card.LazyTraits.install(LAZY_TAP);
        }
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
        // bvw-decision-log-1027: bound on the game thread itself, the scope its ids come from (null: none)
        gameScope = Thread.currentThread() == t ? forge.util.IdScope.capture() : null;
    }

    public void uninstall() {
        synchronized (installed) {
            installed.remove(this);
            if (installed.isEmpty()) {
                DecisionTap.install(null);
                forge.game.card.LazyTraits.install(null);
            }
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

    /**
     * Lane bvw-decision-log-1027: a FORCED replay (not a resume) that diverged decides nothing more. Every later call of
     * this seat's controller on the game thread throws the divergence again, so a game whose engine swallowed the first
     * throw stops at its next decision instead of letting the AI decide in the record's place.
     */
    void refuseAfterDivergence() {
        if (divergence != null && mode == Mode.FORCED && !resume && Thread.currentThread() == gameThread) {
            throw new Divergence("the AI's recorded decision " + currentTop + " does not fit this game");
        }
    }

    /** A controller call here is a decision: on the game thread, and no choice frame open (the AI's own deliberation). */
    boolean controllerHere() {
        if (Thread.currentThread() != gameThread || divergence != null) {
            return false;
        }
        if (!(mode == Mode.RECORD ? open.isEmpty() || open.peek().exec : cursors.isEmpty() || cursors.peek().exec)) {
            return false;
        }
        // bvw-decision-log-1027: nor while another taped seat of this game is deciding a choice: its AI's deliberation
        // asks this seat's controller too (a cost reduction's choice of static ability, an opponent's targets), and a
        // forced replay, which does not deliberate, never asks those
        return !otherSeatDeciding();
    }

    /** bvw-decision-log-1027: the innermost open frame of another tape of this game, on this thread, is a choice. */
    private boolean otherSeatDeciding() {
        for (AiTape t : installed) {
            if (t != this && t.game == game && t.gameThread == Thread.currentThread() && t.choiceOpen()) {
                return true;
            }
        }
        return false;
    }

    private boolean choiceOpen() {
        return mode == Mode.RECORD ? !open.isEmpty() && !open.peek().exec : !cursors.isEmpty() && !cursors.peek().exec;
    }

    /**
     * bvw-decision-log-1027 (forced): the next recorded entry of the open execution that is not a trait build, or null.
     * A forced body reads it to follow an execution the seat's policy shaped (the forced-win solver plays its forced
     * trigger before the native order runs).
     */
    JsonObject peekChild() {
        if (mode != Mode.FORCED || cursors.isEmpty()) {
            return null;
        }
        final Cur c = cursors.peek();
        for (int i = c.i; i < c.list.size(); i++) {
            final JsonObject n = c.list.get(i).getAsJsonObject();
            if (!LAZY.equals(str(n, "k"))) {
                return n;
            }
        }
        return null;
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
        for (int v : GlobalIds.capture(gameScope)) {
            sb.append('.').append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /** Restore {@code s} (from {@link #sync}); returns how many counters were already past it. */
    private static final String[] SYNC_FIELDS = {"rng", "cid", "hid", "ts", "gameIds", "saIds", "stackIds", "trigIds", "replIds",
        "staticIds", "combatIds"};

    /** Restore {@code s}; the names of the counters that were already past it go into {@code aheadFields}. */
    private int restore(final String s) {
        final int[] before = GlobalIds.capture(gameScope);
        final int[] cidBefore = game.peekCardIdCounters();
        final long tsBefore = game.getTimestamp();
        final int n = restoreCounting(s);
        if (n > 0) {
            final String[] p = s.split("\\.");
            if (cidBefore[0] > Integer.parseUnsignedInt(p[1], 16)) aheadFields.add("cid");
            if (cidBefore[1] > Integer.parseUnsignedInt(p[2], 16)) aheadFields.add("hid");
            if (tsBefore > Long.parseUnsignedLong(p[3], 16)) aheadFields.add("ts");
            for (int i = 0; i < before.length && 4 + i < p.length; i++) {
                final int want = Integer.parseUnsignedInt(p[4 + i], 16);
                // CombatView counts down
                if (i == before.length - 1 ? before[i] < want : before[i] > want) aheadFields.add(SYNC_FIELDS[4 + i]);
            }
        }
        return n;
    }

    /** Has this replay's {@code field} counter ever been ahead of the record's (see {@link #restore})? */
    boolean aheadOn(final String field) {
        return aheadFields.contains(field);
    }

    private int restoreCounting(final String s) {
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
        return behind + GlobalIds.restoreForward(gameScope, ids);
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
        if (!LAZY.equals(k)) {
            applyLazyAhead();
        }
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
        // An entry that records its start state (`pre`) started elsewhere than the last entry left the game: in the record
        // something ran in between, Forge's engine or (inside an execution) Forge AI's own deliberation, which a forced
        // replay skips; restoring it is a RESYNC. An entry without one started where the last left it: a replay that is
        // elsewhere there has DRIFTED (something outside the tape ran differently), and its replay is not exact.
        final boolean explicit = e.has("pre");
        final String pre = explicit ? str(e, "pre") : lastPost;
        if (pre != null) {
            final String now = sync();
            if (!pre.equals(now)) {
                final String diff = syncDiff(pre, now);
                for (String part : diff.split(" ")) {
                    for (String f : SYNC_FIELDS) {
                        if (part.equals(f)) {
                            (explicit ? resyncFields : driftFields).add(f);
                        }
                    }
                }
                if (explicit) {
                    resync++;
                } else {
                    drift++;
                    if (!diff.matches("combatIds \\S+")) {
                        gameDrift++; // anything but the combat views' ids (never game state)
                    }
                }
                if (driftNotes.size() < 8) {
                    driftNotes.add((explicit ? "resync " : "drift ") + (cursors.isEmpty() ? "n" + (forcedTop - 1) : "child") + " "
                            + what(ek, em) + " T" + game.getPhaseHandler().getTurn() + ": " + diff);
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

    /** An entry whose forced run threw: its cursor goes, whatever it left (the game stops at the divergence). */
    void abandon(final JsonObject e) {
        if (!cursors.isEmpty()) {
            cursors.pop();
        }
    }

    void leave(final JsonObject e) {
        applyLazyAhead();
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
        if (!LAZY.equals(str(e, "k"))) {
            forcedEntries++;
        }
        if (resume && cursors.isEmpty()) {
            // the resumed game's own journal keeps its forced decisions too (a restore's host already has them)
            final JsonObject echo = e.deepCopy();
            echo.addProperty("r", 1);
            out.println(PREFIX + echo);
        }
    }

    /** {@link #take} for an entry that runs nothing of its own (a choice): the traits the AI built deciding it, built. */
    JsonObject takeChoice(final String k, final String m) {
        final JsonObject e = take(k, m);
        applyLazySub(e);
        return e;
    }

    /** {@code e}'s recorded trait builds (a choice's, or what an execution the replay skips built), built here. */
    void applyLazySub(final JsonObject e) {
        if (!e.has("sub")) {
            return;
        }
        final Cur c = new Cur(e.getAsJsonArray("sub"), false);
        cursors.push(c);
        try {
            applyLazyAhead();
        } finally {
            cursors.remove(c);
        }
    }

    // ------------------------------------------------------------------ lazily built traits

    /*
     * Forge builds some of a card's traits on their first query (LazyTraits: a land's or permanent's spell, its mana
     * abilities, the counter and adventure replacements), taking ids from the process-wide counters. The live game's AI
     * often asks first, while deliberating; a forced replay does not deliberate, so the engine would build the trait
     * later, on other ids, and the replay would run ahead of its record (review forge-mtgx#110). So the record notes
     * each trait built inside an entry (a "lazy" child: kind, card id, the state it started from), and the replay
     * builds it at the same place: where the engine builds it too, as it does; where only the AI did, the replay
     * builds it itself, before the next recorded decision.
     */
    static final String LAZY = "lazy";
    private int lazyDepth;
    private int decodeDepth;
    private long decodeSeq;

    /** When this tape's innermost open frame began ({@link #FRAMES}), or 0 when none is open (a trait built then is not its). */
    long innermostFrame() {
        if (mode == Mode.RECORD) {
            return open.isEmpty() ? 0 : open.peek().seq;
        }
        final long c = cursors.isEmpty() ? 0 : cursors.peek().seq;
        return decodeDepth > 0 ? Math.max(c, decodeSeq) : c;
    }
    private int lazyMissed;
    private int lazyGone;

    private static final class LazyTok {
        final AiTape tape;
        final Object rec;
        final JsonObject e;
        final Object prevScope;
        final boolean swapped;
        final int card;

        LazyTok(final AiTape tape, final Object rec, final JsonObject e, final Object prevScope, final boolean swapped,
                final int card) {
            this.tape = tape;
            this.rec = rec;
            this.e = e;
            this.prevScope = prevScope;
            this.swapped = swapped;
            this.card = card;
        }
    }

    private static final forge.game.card.LazyTraits.Listener LAZY_TAP = new forge.game.card.LazyTraits.Listener() {
        @Override
        public Object before(final forge.game.card.CardState st, final String kind) {
            AiTape inner = null;
            long innerFrame = 0;
            for (AiTape t : installed) {
                if (st.getCard().getGame() == t.game && Thread.currentThread() == t.gameThread && t.divergence == null) {
                    final long f = t.innermostFrame();
                    if (f > innerFrame) {
                        inner = t;
                        innerFrame = f;
                    }
                }
            }
            return inner == null ? null : inner.lazyBefore(st, kind);
        }

        @Override
        public void after(final Object token) {
            if (token instanceof LazyTok lt) {
                lt.tape.lazyAfter(lt);
            }
        }
    };

    /** Decode a recorded answer: lookups only, so anything it builds takes scoped ids (never the game's own). */
    <T> T decoding(final Supplier<T> body) {
        final long prevSeq = decodeSeq;
        decodeSeq = FRAMES.incrementAndGet();
        decodeDepth++;
        try {
            return forge.util.IdScope.detached(DECODE_BASE, body);
        } finally {
            decodeDepth--;
            decodeSeq = prevSeq;
        }
    }

    private Object lazyBefore(final forge.game.card.CardState st, final String kind) {
        final int card = st.getCard().getId();
        final String state = st.getStateName() == forge.card.CardStateName.Original ? null : st.getStateName().name();
        if (lazyDepth > 0) {
            // built as part of another trait's build (recorded, and replayed, with it)
            return null;
        }
        if (mode == Mode.RECORD) {
            if (open.isEmpty()) {
                return null; // the engine's, between decisions: the replay's engine builds it at the same place
            }
            lazyDepth++;
            final Object r = begin(LAZY, kind, false);
            if (state != null) {
                put(r, "st", new JsonPrimitive(state));
            }
            final Object here = forge.util.IdScope.capture();
            if (here != gameScope) {
                // inside the AI's look-ahead: built on scoped ids (bvw-decision-log-1027: a scope other than the game's own)
                put(r, "sc", new JsonPrimitive(1));
                if (here != null) {
                    // bvw-decision-log-1027: and where that scope's counters stood (a cube combo seat's probe scope lives
                    // all game, from 1.1e9): the replay builds the trait on the same ids, never on a fresh scope's
                    // 1e9 + n, which a bench game's own scope hands out too
                    final JsonArray sci = new JsonArray();
                    for (int v : forge.util.IdScope.peek(here)) {
                        sci.add(v);
                    }
                    put(r, "sci", sci);
                }
            }
            if (game.findById(card) != st.getCard()) {
                // not the game's own card of that id: a copy (last known information, a card mid-move) or a card the AI
                // made while deliberating. The replay builds it only where its engine makes the same copy.
                put(r, "cp", new JsonPrimitive(1));
            }
            return new LazyTok(this, r, null, null, false, card);
        }
        JsonObject e = null;
        if (!cursors.isEmpty()) {
            final Cur c = cursors.peek();
            if (c.i < c.list.size()) {
                final JsonObject n = c.list.get(c.i).getAsJsonObject();
                if (LAZY.equals(str(n, "k")) && kind.equals(str(n, "m")) && n.get("a").getAsInt() == card
                        && java.util.Objects.equals(state, str(n, "st"))) {
                    e = take(LAZY, kind);
                }
            }
        }
        if (e == null) {
            if (cursors.isEmpty() && decodeDepth == 0) {
                return null; // the engine's, between decisions, as in the record
            }
            lazyMissed("a trait built where the record built none (" + kind + ")");
        }
        lazyDepth++;
        // where the record built it on scoped ids, so does the replay; anywhere else on the game's own (never a decode's)
        final Object prev = forge.util.IdScope.capture();
        final boolean scoped = e != null && e.has("sc");
        if (scoped && e.has("sci")) {
            final JsonArray sci = e.getAsJsonArray("sci");
            final int[] at = new int[sci.size()];
            for (int i = 0; i < at.length; i++) {
                at[i] = sci.get(i).getAsInt();
            }
            forge.util.IdScope.install(forge.util.IdScope.scopeAt(at)); // bvw-decision-log-1027: the record's ids
        } else if (scoped) {
            forge.util.IdScope.open();
        } else if (prev != gameScope) {
            forge.util.IdScope.install(gameScope); // bvw-decision-log-1027: the game's own ids (its scope, or none)
        }
        return new LazyTok(this, null, e, prev, scoped || prev != gameScope, card);
    }

    private void lazyAfter(final LazyTok t) {
        lazyDepth--;
        if (t.rec != null) {
            end(t.rec, new JsonPrimitive(t.card));
            return;
        }
        if (t.swapped) {
            forge.util.IdScope.install(t.prevScope);
        }
        if (t.e != null) {
            done(t.e);
        }
    }

    private void lazyMissed(final String why) {
        lazyMissed++;
        drift++;
        gameDrift++;
        driftFields.add(LAZY);
        if (driftNotes.size() < 8) {
            driftNotes.add("drift " + why + " T" + game.getPhaseHandler().getTurn());
        }
    }

    /**
     * Build the record's trait builds next at the open cursor that the replay's engine has not built by now, the latest
     * place they can go (the next recorded decision, or the frame's end): the engine builds its own where the record's
     * did, and only what the AI alone built is left (review forge-mtgx#110).
     */
    private void applyLazyAhead() {
        while (!cursors.isEmpty() && divergence == null && lazyDepth == 0) {
            final Cur c = cursors.peek();
            if (c.i >= c.list.size()) {
                return;
            }
            final JsonObject n = c.list.get(c.i).getAsJsonObject();
            if (!LAZY.equals(str(n, "k"))) {
                return;
            }
            final int at = c.i;
            final forge.game.card.Card card = n.has("cp") ? null : game.findById(n.get("a").getAsInt());
            final String st = str(n, "st");
            final forge.game.card.CardState state = card == null ? null
                    : card.getState(st == null ? forge.card.CardStateName.Original : forge.card.CardStateName.valueOf(st));
            if (state != null) {
                forge.game.card.LazyTraits.build(state, str(n, "m"));
            }
            if (cursors.peek() == c && c.i == at) {
                c.i++;
                // the record's state after that build is where the next entry starts (review forge-mtgx#110, S2-1)
                final String post = str(n, "post");
                if (post != null) {
                    if (!post.equals(sync())) {
                        ahead += restore(post);
                    }
                    lastPost = post;
                }
                if (card == null) {
                    // a copy only the AI's deliberation made, or a card it imagined (a token), never the game's own: not
                    // built on the game's card, and the ids its trait took are the record's next state, restored there
                    lazyGone++;
                } else {
                    // the replay's card had it already: the replay is not exact
                    lazyMissed("a recorded trait build the replay could not make (" + str(n, "m") + ")");
                }
            }
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
            o.add("driftFields", strings(driftFields));
            o.addProperty("resync", resync);
            o.add("resyncFields", strings(resyncFields));
            o.add("aheadFields", strings(aheadFields));
            // exact: no drift and nothing ahead but the combat views' ids (negative view ids, never game state)
            o.addProperty("exact", gameDrift == 0 && onlyCombatViews(driftFields) && onlyCombatViews(aheadFields));
            if (!driftNotes.isEmpty()) {
                final JsonArray dn = new JsonArray();
                driftNotes.forEach(dn::add);
                o.add("driftNotes", dn);
            }
            o.addProperty("ahead", ahead);
            o.addProperty("lazyMissed", lazyMissed);
            o.addProperty("lazyGone", lazyGone);
            if (divergence != null) {
                o.addProperty("divergence", divergence);
            }
        }
        emit("end", o);
    }

    private static JsonArray strings(final java.util.Collection<String> xs) {
        final JsonArray a = new JsonArray();
        xs.forEach(a::add);
        return a;
    }

    private static boolean onlyCombatViews(final java.util.Collection<String> fields) {
        for (String f : fields) {
            if (!"combatIds".equals(f)) {
                return false;
            }
        }
        return true;
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
        final JsonObject e = takeChoice("cost", m);
        final PaymentDecision d;
        try {
            d = decoding(() -> AiTapeCodec.decision(game, e.get("a")));
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
        final JsonObject e = takeChoice("mana", null);
        final SpellAbility ma;
        try {
            ma = decoding(() -> AiTapeCodec.manaChoice(game, options, e.get("a")));
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
        final JsonObject e = takeChoice("cfg", where);
        try {
            decoding(() -> {
                AiTapeCodec.applyConfig(game, seat, sa, e.get("a"));
                return null;
            });
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
                end(r, safe(() -> orderAnswer(offered, chosen)));
            }
            return o;
        }
        final JsonObject e = takeChoice("order", null);
        final List<SpellAbility> o;
        try {
            o = pickOrder(sas, e.get("a"));
        } catch (RuntimeException x) {
            throw diverge("trigger order: " + x.getMessage());
        }
        done(e);
        return new ArrayList<>(o);
    }

    /**
     * bvw-decision-log-1027: the order as positions among the offered list plus the abilities' ids. A seat may reorder
     * the list before its native order runs (the forced-win solver's forced trigger first), so a forced replay, which
     * gets the engine's list, places the abilities by id.
     */
    static JsonElement orderAnswer(final List<SpellAbility> offered, final List<SpellAbility> chosen) {
        final JsonElement a = AiTapeCodec.indices(offered, chosen);
        if (a.isJsonObject() && chosen != null) {
            final JsonArray ids = new JsonArray();
            for (SpellAbility sa : chosen) {
                ids.add(sa == null ? -1 : sa.getId());
            }
            a.getAsJsonObject().add("ids", ids);
        }
        return a;
    }

    /** {@link #orderAnswer}'s forced side: by id when every id names one offered ability, else by the positions. */
    static List<SpellAbility> pickOrder(final List<SpellAbility> sas, final JsonElement a) {
        if (a == null || a.isJsonNull() || !a.getAsJsonObject().has("ids")) {
            return AiTapeCodec.pick(sas, a);
        }
        final JsonObject o = a.getAsJsonObject();
        if (o.has("err")) {
            return AiTapeCodec.pick(sas, a); // throws with the record's reason
        }
        final JsonArray ids = o.getAsJsonArray("ids");
        final int of = o.get("of").getAsInt();
        if (of != sas.size()) {
            throw new IllegalStateException(sas.size() + " options offered, " + of + " recorded");
        }
        final List<SpellAbility> out = new ArrayList<>();
        final boolean[] used = new boolean[sas.size()];
        for (JsonElement x : ids) {
            int at = -1;
            for (int i = 0; i < sas.size(); i++) {
                if (!used[i] && sas.get(i) != null && sas.get(i).getId() == x.getAsInt()) {
                    if (at >= 0) {
                        at = -2; // two offered abilities share the id: the positions decide (checked below)
                        break;
                    }
                    at = i;
                }
            }
            if (at == -2) {
                final List<SpellAbility> byPos = AiTapeCodec.pick(sas, a);
                for (int i = 0; i < ids.size(); i++) {
                    if (byPos.get(i) == null || byPos.get(i).getId() != ids.get(i).getAsInt()) {
                        throw new IllegalStateException("the recorded order's abilities are not where the record had them");
                    }
                }
                return byPos;
            }
            if (at < 0) {
                throw new IllegalStateException("the recorded order names ability " + x.getAsInt() + ", which is not offered");
            }
            used[at] = true;
            out.add(sas.get(at));
        }
        return out;
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
        // bvw-decision-log-1027: a declined trigger runs its non-AI preparation too, as live did before declining: a
        // charm's modes (this tape's child), another player's targets (that player's own tape: they declined). A
        // trigger the AI itself declined asks nothing there.
        enter(e, true);
        final boolean got;
        boolean ok = false;
        try {
            got = nonAi.getAsBoolean();
            ok = true;
        } catch (Divergence x) {
            throw x;
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException | ClassCastException
                 | IndexOutOfBoundsException x) {
            throw diverge("a recorded trigger could not be prepared: " + x.getClass().getSimpleName());
        } finally {
            if (!ok) {
                abandon(e);
            }
        }
        leave(e);
        if (!want) {
            done(e);
            return false;
        }
        if (!got) {
            throw diverge("a trigger the record prepared could not be prepared in the replay");
        }
        done(e);
        return true;
    }

    /** bvw-decision-log-1027: did this entry's run decide anything (a child that is not a trait build)? */
    static boolean hasDecisions(final JsonObject e) {
        if (e.has("sub")) {
            for (JsonElement c : e.getAsJsonArray("sub")) {
                if (!LAZY.equals(str(c.getAsJsonObject(), "k"))) {
                    return true;
                }
            }
        }
        return false;
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
