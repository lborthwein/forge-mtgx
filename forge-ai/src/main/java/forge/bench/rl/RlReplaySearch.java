package forge.bench.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiTapeController;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

/**
 * Lane sdcc-item2-hooks-1115 (item 2 v1, surprise-mined labels; OFFLINE ONLY, default off): REPLAY-SEARCH. In
 * {@code RlActorBench} replay mode (config key {@code replaySearch}; no other way to turn it on), a tape game is replayed
 * FORCED from its decision log, and at each listed priority decision of a seat this listener runs that seat's live
 * {@link RlSearch} priority decision (the given search spec, e.g. the e2live bench spec) K times, each with its own
 * {@code seedSalt}, BEFORE the forced tape action is played and without changing the replay. The search is the seat's
 * normal one: its worlds are re-drawn from the seat's belief (the search's own redraw), never handed the true hidden
 * state.
 *
 * <p>Identity of a position: a forced replay never asks the seat, so the seat's DECIDE counter does not run. A listed
 * position ({@code game_uid, seat, dec_idx}) is found as the seat's priority decision at the row's {@code turn} (and
 * {@code phase}) whose rebuilt frame has the row's menu size {@code C} and whose policy value v(o) equals the original
 * decision-log row's {@code v_root} (within {@code tol}): the replayed observation is the original's. Not found = no rows
 * (reported).
 *
 * <p>Isolation: everything runs under the bench isolation ({@link PlayerControllerBridge#isolated}: scratch random
 * stream, AI caches, card memory, express choices and id counters put back) in a detached id scope, each run in its own;
 * every run is a fresh {@link RlSearch} (its plan search built but never installed: {@link RlSearch#attachShadow}). The
 * game's InfoStates and the seat knowledge's searched-to-top seats are set at game start as the live game set them.
 *
 * <p>Outputs (written by {@link #finish} only for a game whose replay digest equals the tape's; otherwise its rows are
 * void and only the status rows say so), under {@code out}: {@code k<k>/logs/search-decisions-<pid>.jsonl} (the normal
 * kind-less decision-log rows, plus {@code pos_id}, {@code run}, {@code salt}, {@code seat}); {@code frames-<pid>.jsonl}
 * (one per position: the seat's DECIDE payload, base64, with its greedy answer, the game's seed and result: the mtgx
 * side writes eval-shard rows from it into each {@code k<k>/shards}); {@code positions-<pid>.jsonl} ({@code pos_id, run,
 * game_uid, dec_idx, k, salt}: dlogpack's --positions file); {@code status-<pid>.jsonl} (one per listed position, void
 * games included); {@code games-<pid>.jsonl}.
 */
public final class RlReplaySearch implements PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-item2-replay-search/1";

    /** One listed position. */
    public static final class Pos {
        public String posId;
        public String gameUid;
        public String tape;
        public int line = -1;
        public int seat;
        public int decIdx;
        public int turn;
        public String phase;
        public int C;
        public double vRoot;
        public int K;
        public Integer greedy;
        public long[] salts;
        /** play-from only: candidate indices to force, continuation seeds. */
        public int[] actions;
        public int[] seeds;

        static Pos parse(final JsonObject o, final boolean playFrom) {
            final Pos p = new Pos();
            for (String k : new String[] {"game_uid", "seat", "dec_idx", "turn", "C", "v_root"}) {
                if (!o.has(k) || o.get(k).isJsonNull()) {
                    throw new IllegalArgumentException("position: missing " + k + " in " + o);
                }
            }
            p.gameUid = TrajectoryRef.canonicalUid(o.get("game_uid").getAsString());
            p.seat = o.get("seat").getAsInt();
            p.decIdx = o.get("dec_idx").getAsInt();
            p.posId = o.has("pos_id") ? o.get("pos_id").getAsString() : p.gameUid + ":" + p.seat + ":" + p.decIdx;
            p.tape = o.has("tape") && !o.get("tape").isJsonNull() ? o.get("tape").getAsString() : null;
            p.line = o.has("line") && !o.get("line").isJsonNull() ? o.get("line").getAsInt() : -1;
            p.turn = o.get("turn").getAsInt();
            p.phase = o.has("phase") && !o.get("phase").isJsonNull() ? o.get("phase").getAsString() : null;
            p.C = o.get("C").getAsInt();
            p.vRoot = o.get("v_root").getAsDouble();
            p.greedy = o.has("greedy") && !o.get("greedy").isJsonNull() ? o.get("greedy").getAsInt() : null;
            if (p.seat != 0 && p.seat != 1) {
                throw new IllegalArgumentException("position " + p.posId + ": seat must be 0 or 1");
            }
            if (playFrom) {
                p.actions = ints(o, "actions");
                p.seeds = ints(o, "seeds");
                if (p.actions.length == 0 || p.seeds.length == 0) {
                    throw new IllegalArgumentException("play-from position " + p.posId + ": actions and seeds needed");
                }
            } else {
                p.K = o.has("K") ? o.get("K").getAsInt() : 0;
                if (o.has("salts") && !o.get("salts").isJsonNull()) {
                    final JsonArray a = o.getAsJsonArray("salts");
                    p.salts = new long[a.size()];
                    for (int i = 0; i < a.size(); i++) {
                        p.salts[i] = a.get(i).getAsLong();
                    }
                    if (p.K == 0) {
                        p.K = p.salts.length;
                    }
                    if (p.salts.length != p.K) {
                        throw new IllegalArgumentException("position " + p.posId + ": K " + p.K + " but "
                                + p.salts.length + " salts");
                    }
                }
                if (p.K < 1 || p.K > 64) {
                    throw new IllegalArgumentException("position " + p.posId + ": K must be 1..64, not " + p.K);
                }
            }
            return p;
        }

        private static int[] ints(final JsonObject o, final String k) {
            if (!o.has(k) || !o.get(k).isJsonArray()) {
                return new int[0];
            }
            final JsonArray a = o.getAsJsonArray(k);
            final int[] out = new int[a.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = a.get(i).getAsInt();
            }
            return out;
        }
    }

    /** The {@code replaySearch} / {@code playFrom} config block (shared shape). */
    public static final class Spec {
        public final List<Pos> positions = new ArrayList<>();
        /** The search spec as given (no harness keys), and the service it is served by. */
        public JsonObject search;
        public String server;
        public String policySha;
        public String out;
        public long saltBase = 0x17E3A2B5EEDL;
        public double tol = 1e-4;
        /**
         * setup-gap-1115 (offline only, default off): at every found position also write the replayed game's full
         * Forge state ({@link forge.game.GameState}, puzzle text) into the position's status row as {@code state_full}.
         * Full information, both seats: the offline analysis reduces it to the seat's own view before any solver reads it;
         * nothing here reaches a search, a decision or a label.
         */
        public boolean exportState = false;
        public final boolean playFrom;

        Spec(final boolean playFrom) {
            this.playFrom = playFrom;
        }

        static final Set<String> KEYS = Set.of("positions", "spec", "server", "policySha", "out", "saltBase", "tol");

        public static Spec parse(final JsonObject o, final boolean playFrom) {
            final String what = playFrom ? "playFrom" : "replaySearch";
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException(what + ": unknown key " + k + " (" + KEYS + ")");
                }
            }
            for (String k : new String[] {"positions", "spec", "server", "out"}) {
                if (!o.has(k) || o.get(k).isJsonNull()) {
                    throw new IllegalArgumentException(what + " needs " + k);
                }
            }
            final Spec s = new Spec(playFrom);
            final JsonElement sp = o.get("spec");
            try {
                s.search = sp.isJsonObject() ? sp.getAsJsonObject().deepCopy() : JsonParser.parseString(new String(
                        Files.readAllBytes(Paths.get(sp.getAsString())), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String l : Files.readAllLines(Paths.get(o.get("positions").getAsString()), StandardCharsets.UTF_8)) {
                    if (!l.isBlank()) {
                        s.positions.add(Pos.parse(JsonParser.parseString(l).getAsJsonObject(), playFrom));
                    }
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            for (String k : new String[] {"server", "policySha", "labelSink", "valueSink", "decisionLog"}) {
                if (s.search.has(k)) {
                    throw new IllegalArgumentException(what + ".spec must not set " + k + " (the hook does)");
                }
            }
            s.server = o.get("server").getAsString();
            s.policySha = o.has("policySha") && !o.get("policySha").isJsonNull() ? o.get("policySha").getAsString() : null;
            s.out = o.get("out").getAsString();
            if (o.has("saltBase")) {
                s.saltBase = o.get("saltBase").getAsLong();
            }
            if (o.has("exportState")) {
                s.exportState = o.get("exportState").getAsBoolean();
            }
            if (o.has("tol")) {
                s.tol = o.get("tol").getAsDouble();
            }
            final Set<String> ids = new HashSet<>();
            for (Pos p : s.positions) {
                if (!ids.add(p.posId)) {
                    throw new IllegalArgumentException(what + ": position " + p.posId + " listed twice");
                }
            }
            if (s.positions.isEmpty()) {
                throw new IllegalArgumentException(what + ": no positions");
            }
            config(s, 0x51L, 2); // every key of the spec is checked once, at JVM start
            return s;
        }

        /** The positions of one game (canonical uid). */
        public List<Pos> of(final String gameUid) {
            final String u = TrajectoryRef.canonicalUid(gameUid);
            final List<Pos> out = new ArrayList<>();
            for (Pos p : positions) {
                if (p.gameUid.equals(u)) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    /** The search config of the spec with this hook's service, the policy pin, {@code seedSalt} and the seat's schema. */
    public static RlSearch.Config config(final Spec s, final long salt, final int obs) {
        final JsonObject o = s.search.deepCopy();
        o.addProperty("server", s.server);
        if (s.policySha != null) {
            o.addProperty("policySha", s.policySha);
        }
        o.addProperty("seedSalt", salt);
        o.addProperty("obs", obs);
        return RlSearch.Config.parse(o);
    }

    /** Run k's salt: the position's own, else one derived from the spec's base (never the live 0x51 by construction). */
    public static long salt(final Spec s, final Pos p, final int k) {
        if (p.salts != null) {
            return p.salts[k];
        }
        return RlSearch.splitmix(s.saltBase ^ (0x9E3779B97F4A7C15L * (k + 1)));
    }

    /** Shared by the replay threads of one JVM. */
    public static final class Sink {
        final Path dir;
        final long pid = ProcessHandle.current().pid();

        public Sink(final Path dir) throws IOException {
            this.dir = dir;
            Files.createDirectories(dir);
        }

        public synchronized void append(final Path f, final List<JsonObject> rows) throws IOException {
            if (rows.isEmpty()) {
                return;
            }
            Files.createDirectories(f.getParent());
            final StringBuilder sb = new StringBuilder();
            for (JsonObject r : rows) {
                sb.append(RlWire.canonicalString(r)).append('\n');
            }
            Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        public Path file(final String name) {
            return dir.resolve(name + "-" + pid + ".jsonl");
        }

        Path runDir(final int k) {
            return dir.resolve("k" + k).toAbsolutePath();
        }
    }

    // ------------------------------------------------------------------------------------------------ one game

    private final Spec spec;
    private final JsonObject tape;
    private final Game game;
    private final RlSeat liveSeat;
    private final RlKnowledge know;
    private final CardIndex index;
    private final int obs;
    private final String jarSha;
    private final long uid;
    private final String uidStr;
    private final long seed;
    private final List<Pos> pending;
    private final Map<String, JsonObject> status = new LinkedHashMap<>();
    private final Map<Integer, List<JsonObject>> rowsByRun = new LinkedHashMap<>();
    private final List<JsonObject> frames = new ArrayList<>();
    private final List<JsonObject> posRows = new ArrayList<>();
    /** The search that scores the rebuilt frames (live salt; its settings set the game's InfoStates and knowledge). */
    private final RlSearch anchor;
    private int priorityDecisions = 0, probed = 0;
    private String fatal;

    /** Tests: told every probe (found or not). */
    public static volatile java.util.function.Consumer<JsonObject> TEST_PROBE = null;
    /**
     * Tests only (null by default): run on the LIVE game, outside the isolation, at every found position; a mutant that
     * moves the game must void the game's rows through the digest check.
     */
    public static volatile java.util.function.Consumer<Game> TEST_MUTANT = null;

    /** At game start, on the game thread, after the seat and its knowledge are set up (RlActorBench's GameHook). */
    public RlReplaySearch(final Spec spec, final JsonObject tape, final Game game, final RlSeat liveSeat,
            final RlKnowledge know, final CardIndex index, final int obs, final String jarSha) {
        this.spec = spec;
        this.tape = tape;
        this.game = game;
        this.liveSeat = liveSeat;
        this.know = know;
        this.index = index;
        this.obs = obs;
        this.jarSha = jarSha;
        this.uidStr = TrajectoryRef.canonicalUid(tape.get("game_uid").getAsString());
        this.uid = RlWire.parseUid(tape.get("game_uid").getAsString());
        this.seed = tape.get("seed").getAsLong();
        this.pending = new ArrayList<>(spec.of(uidStr));
        for (Pos p : pending) {
            final JsonObject st = statusRow(p);
            st.addProperty("found", false);
            st.addProperty("reason", "not_reached");
            status.put(p.posId, st);
        }
        final RlSearch.Config live = config(spec, 0x51L, obs);
        this.anchor = new RlSearch(live, seed, uid, index, know, jarSha, "rs-anchor", obs);
        // as RlActorBench.play sets up the live game's search: the game's InfoStates and every RL seat's searched-to-top
        // knowledge (a both-seats game, as the H2H pilot games)
        RlSearch.attachInfoStates(live, game);
        RlSearch.attachInfoState(game, live);
        anchor.bindChoiceScorer(3);
    }

    private JsonObject statusRow(final Pos p) {
        final JsonObject st = new JsonObject();
        st.addProperty("schema", SCHEMA);
        st.addProperty("pos_id", p.posId);
        st.addProperty("game_uid", uidStr);
        st.addProperty("seat", p.seat);
        st.addProperty("dec_idx", p.decIdx);
        st.addProperty("turn", p.turn);
        st.addProperty("K", p.K);
        st.addProperty("v_root_tape", p.vRoot);
        return st;
    }

    /** The seat's bridged controller (under a forced replay's AI tape controller), or null. */
    static PlayerControllerBridge bridgeOf(final Player p) {
        Object c = p.getController();
        if (c instanceof AiTapeController tc) {
            c = tc.inner();
        }
        return c instanceof PlayerControllerBridge b ? b : null;
    }

    /** The rebuilt ask of a probe: its menu, menu objects, observation, frame and the policy's SCORE. */
    static final class Probe {
        RlCandidates.Menu m;
        List<SpellAbility> objs;
        RlWire.Decide frame;
        byte[] payload;
        RlSearchClient.Scores sc;
        String why;
    }

    /**
     * The seat's priority ask as the live seat would have sent it now, numbered {@code decIdx}, and its policy SCORE
     * (scored by {@code scorer}); runs inside the bench isolation. Null fields + {@code why} when there is no ask.
     */
    @SuppressWarnings("unchecked")
    static Probe probe(final Game g, final Player p, final int seat, final RlSeat liveSeat, final RlKnowledge know,
            final CardIndex index, final int obs, final int decIdx, final RlSearch scorer) {
        return PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
            final Probe pr = new Probe();
            final PlayerControllerBridge b = bridgeOf(p);
            if (b == null) {
                pr.why = "no_bridge";
                return pr;
            }
            final Object[] ask = b.shadowPriorityAsk();
            final List<SpellAbility> objs = (List<SpellAbility>) ask[0];
            final RlCandidates.Menu m = RlCandidates.build(g, p, "chooseSpellAbilityToPlay", "priority",
                    (JsonObject) ask[1], objs, null);
            if (m == null || m.unposable != null) {
                pr.why = "unposable";
                return pr;
            }
            if (m.trivial) {
                pr.why = "trivial";
                return pr;
            }
            final RlFeaturizer feat = new RlFeaturizer(index);
            feat.setVersion(obs);
            feat.reset();
            feat.setKnowledge(know);
            final RlFeaturizer.Obs o = feat.observe(g, p, m.mullK, false, m);
            m.bind(o, feat, p);
            final RlWire.Decide f = liveSeat.frameAt(seat, g, m, o, decIdx);
            if (f.hasPriv()) {
                throw new IllegalStateException("replay search: a frame with the privileged block");
            }
            pr.m = m;
            pr.objs = objs;
            pr.frame = f;
            pr.payload = RlWire.encodeDecide(f);
            try {
                pr.sc = scorer.policyScore(pr.payload);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return pr;
        }));
    }

    /** The pending position of {@code seat} the probe is (C and v_root), or null. */
    static Pos match(final List<Pos> pending, final int seat, final int turn, final String phase, final Probe pr,
            final double tol) {
        Pos best = null;
        double bd = Double.POSITIVE_INFINITY;
        for (Pos q : pending) {
            if (q.seat != seat || q.turn != turn || q.phase != null && !q.phase.equals(phase) || q.C != pr.m.C()) {
                continue;
            }
            final double d = Math.abs(pr.sc.valueObs - q.vRoot);
            if (d <= tol && d < bd) {
                best = q;
                bd = d;
            }
        }
        return best;
    }

    /** Whether any pending position of {@code seat} sits at this turn (and phase): a probe is worth building. */
    static boolean wanted(final List<Pos> pending, final int seat, final int turn, final String phase) {
        for (Pos q : pending) {
            if (q.seat == seat && q.turn == turn && (q.phase == null || q.phase.equals(phase))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void beforeDecision(final Player p) {
        priorityDecisions++;
        final Game g = game;
        if (pending.isEmpty() || g == null || g.isGameOver() || fatal != null) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final String phase = String.valueOf(g.getPhaseHandler().getPhase());
        if (!wanted(pending, seat, turn, phase)) {
            return;
        }
        probed++;
        final Probe pr;
        try {
            pr = probe(g, p, seat, liveSeat, know, index, obs, pending.get(0).decIdx, anchor);
        } catch (RuntimeException e) {
            fatal = "probe: " + e;
            System.err.println("[replay-search] game " + uidStr + " probe failed: " + e);
            e.printStackTrace();
            return;
        }
        final JsonObject pj = new JsonObject();
        pj.addProperty("seat", seat);
        pj.addProperty("turn", turn);
        pj.addProperty("phase", phase);
        pj.addProperty("why", pr.why);
        if (pr.m != null) {
            pj.addProperty("C", pr.m.C());
            pj.addProperty("v", pr.sc.valueObs);
        }
        final java.util.function.Consumer<JsonObject> tp = TEST_PROBE;
        if (tp != null) {
            tp.accept(pj);
        }
        if (pr.m == null || pr.sc.status != RlWire.ST_OK) {
            return;
        }
        final Pos q = match(pending, seat, turn, phase, pr, spec.tol);
        if (q == null) {
            return;
        }
        pending.remove(q);
        label(g, p, seat, q, pr);
        final java.util.function.Consumer<Game> mut = TEST_MUTANT;
        if (mut != null) {
            mut.accept(g);
        }
    }

    /** The K searches of a found position. */
    private void label(final Game g, final Player p, final int seat, final Pos q, final Probe pr) {
        final JsonObject st = status.get(q.posId);
        st.addProperty("found", true);
        st.remove("reason");
        st.addProperty("v_root_replay", pr.sc.valueObs);
        st.addProperty("dv", Math.abs(pr.sc.valueObs - q.vRoot));
        st.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        st.addProperty("C", pr.m.C());
        st.addProperty("priority_ordinal", priorityDecisions - 1);
        if (spec.exportState) {
            try {
                final forge.game.GameState gs = new forge.game.GameState();
                gs.initFromGame(g);
                st.addProperty("state_full", gs.toString());
                st.addProperty("state_seat", seat);
            } catch (RuntimeException e) {
                st.addProperty("state_error", String.valueOf(e));
            }
        }
        final Integer greedy = q.greedy != null ? q.greedy : tapeGreedy(q.decIdx);
        if (greedy == null || greedy < 0 || greedy >= pr.m.C() || pr.m.validate(new short[] {(short) (int) greedy}) != null) {
            st.addProperty("reason", greedy == null ? "no_greedy" : "bad_greedy " + greedy);
            return;
        }
        st.addProperty("greedy", greedy);
        // the frame with the original decision's number (the search seeds its worlds with it)
        final RlWire.Decide frame = pr.frame;
        frame.decIdx = q.decIdx;
        final byte[] payload = RlWire.encodeDecide(frame);
        final JsonArray answers = new JsonArray(), nrows = new JsonArray(), ms = new JsonArray(), salts = new JsonArray();
        final RlSearch.Config[] cfgs = new RlSearch.Config[q.K];
        for (int k = 0; k < q.K; k++) {
            cfgs[k] = config(spec, salt(spec, q, k), obs);
        }
        for (int k = 0; k < q.K; k++) {
            final long sk = salt(spec, q, k);
            salts.add(sk);
            final RlSearch s = new RlSearch(cfgs[k], seed, uid, index, know, jarSha, "rs" + k, obs);
            s.rowsWanted = true;
            final long t0 = System.nanoTime();
            int ans;
            List<JsonObject> rows;
            try {
                s.attachShadow(g, p, liveSeat);
                ans = PlayerControllerBridge.isolated(g, () -> IdScope.detached(
                        () -> s.decide(g, p, pr.m, frame, greedy, pr.objs)));
                rows = s.takeRows();
            } catch (RuntimeException e) {
                System.err.println("[replay-search] " + q.posId + " run " + k + " failed: " + e);
                e.printStackTrace();
                ans = Integer.MIN_VALUE;
                rows = new ArrayList<>();
                st.addProperty("error_run_" + k, String.valueOf(e));
            } finally {
                s.close();
            }
            ms.add(Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
            answers.add(ans);
            int priorityRows = 0;
            final List<JsonObject> out = rowsByRun.computeIfAbsent(k, x -> new ArrayList<>());
            for (JsonObject r : rows) {
                r.addProperty("pos_id", q.posId);
                r.addProperty("run", k);
                r.addProperty("salt", sk);
                r.addProperty("seat", seat);
                if (!r.has("kind") && r.has("dec_idx") && r.get("dec_idx").getAsInt() == q.decIdx) {
                    priorityRows++;
                }
                out.add(r);
            }
            nrows.add(priorityRows);
            if (priorityRows == 1) {
                final JsonObject pp = new JsonObject();
                pp.addProperty("pos_id", q.posId);
                pp.addProperty("run", "k" + k); // made absolute by finish (the sink's directory)
                pp.addProperty("game_uid", uidStr);
                pp.addProperty("dec_idx", q.decIdx);
                pp.addProperty("k", k);
                pp.addProperty("salt", sk);
                posRows.add(pp);
            }
        }
        st.add("salts", salts);
        st.add("answers", answers);
        st.add("priority_rows", nrows);
        st.add("ms", ms);
        final JsonObject fr = new JsonObject();
        fr.addProperty("schema", SCHEMA);
        fr.addProperty("pos_id", q.posId);
        fr.addProperty("game_uid", uidStr);
        fr.addProperty("seed", seed);
        fr.addProperty("seat", seat);
        fr.addProperty("dec_idx", q.decIdx);
        fr.addProperty("turn", g.getPhaseHandler().getTurn());
        fr.addProperty("greedy", greedy);
        fr.addProperty("C", pr.m.C());
        fr.addProperty("obs", obs);
        fr.addProperty("value_obs", pr.sc.valueObs);
        fr.addProperty("policy_version", pr.sc.policyVersion);
        fr.add("result", tape.get("result"));
        fr.add("turns", tape.get("turns"));
        fr.addProperty("payload", Base64.getEncoder().encodeToString(payload));
        frames.add(fr);
    }

    /** The tape's answer at {@code decIdx} when the search did not override it there (the policy's own), else null. */
    private Integer tapeGreedy(final int decIdx) {
        if (tape.has("overridden")) {
            for (JsonElement e : tape.getAsJsonArray("overridden")) {
                if (e.getAsInt() == decIdx) {
                    return null;
                }
            }
        }
        for (JsonElement e : tape.getAsJsonArray("dec")) {
            final JsonArray r = e.getAsJsonArray();
            if (r.get(0).getAsInt() == decIdx) {
                final JsonArray st = r.get(4).getAsJsonArray();
                return r.get(2).getAsInt() == RlSchema.F_PRIORITY && st.size() == 1 ? st.get(0).getAsInt() : null;
            }
        }
        return null;
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /**
     * The game is over and its digest checked: its rows only when {@code digestEqual} (else void, loudly); every
     * position's status row either way. Returns the number of positions written.
     */
    public int finish(final Sink sink, final boolean digestEqual, final JsonObject replayRow) throws IOException {
        anchor.close();
        final boolean ok = digestEqual && fatal == null;
        int written = 0;
        if (ok) {
            for (Map.Entry<Integer, List<JsonObject>> e : rowsByRun.entrySet()) {
                sink.append(sink.runDir(e.getKey()).resolve("logs").resolve("search-decisions-" + sink.pid + ".jsonl"),
                        e.getValue());
            }
            sink.append(sink.file("frames"), frames);
            for (JsonObject pp : posRows) {
                pp.addProperty("run", sink.runDir(pp.get("k").getAsInt()).toString());
            }
            sink.append(sink.file("positions"), posRows);
            written = frames.size();
        } else if (!frames.isEmpty()) {
            System.err.println("[replay-search] game " + uidStr + " VOID (digest " + (digestEqual ? "equal" : "UNEQUAL")
                    + (fatal == null ? "" : ", " + fatal) + "): its " + frames.size() + " positions are not written");
        }
        final List<JsonObject> sts = new ArrayList<>();
        for (JsonObject st : status.values()) {
            st.addProperty("void", !ok);
            sts.add(st);
        }
        sink.append(sink.file("status"), sts);
        final JsonObject gr = new JsonObject();
        gr.addProperty("schema", SCHEMA);
        gr.addProperty("game_uid", uidStr);
        gr.addProperty("equal", digestEqual);
        if (fatal != null) {
            gr.addProperty("fatal", fatal);
        }
        gr.addProperty("listed", status.size());
        gr.addProperty("found", status.size() - pending.size());
        gr.addProperty("written", written);
        gr.addProperty("priority_decisions", priorityDecisions);
        gr.addProperty("probes", probed);
        sink.append(sink.file("games"), List.of(gr));
        replayRow.addProperty("replay_search_positions", status.size());
        replayRow.addProperty("replay_search_found", status.size() - pending.size());
        replayRow.addProperty("replay_search_written", written);
        return written;
    }
}
