package forge.bench.rl;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AiFixes;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.BenchSession;
import forge.bench.CallCounter;
import forge.bench.JsonRpcChannel;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * S1 (lane s1-search-1007): Forge's look-ahead ({@link LookaheadSearch}) over a learned policy's own priority decisions,
 * for one game's RL seat. GOAL rev 5's search over learned nets, built for the read after R1.
 *
 * <p>At a contested PRIORITY ask of the RL seat (empty stack unless {@code stack}), after the server's greedy DECISION:
 * <ol>
 *   <li><b>Prior.</b> SCORE (search service) gives the policy's probabilities over the ask's candidates. X variants of
 *       one menu entry are pooled (their sum is the entry's prior; the most likely variant is the entry's X). The
 *       searched set is the policy's own choice first (the default), then the other entries by prior, up to
 *       {@code breadth} after the guards.</li>
 *   <li><b>Play-outs.</b> K worlds (the opponent's hidden cards and our library order re-drawn, as K8) x the set, to the
 *       start of turn now + {@code horizon}. {@code playout=forge}: Forge AI plays both seats after the candidate (it
 *       also targets the candidate), as K8. {@code playout=policy}: the searching seat is the policy itself (a bridged
 *       seat in the copy, its asks answered by the search service, greedy or sampled), the opponent Forge AI.</li>
 *   <li><b>Leaf.</b> {@code leaf=static}: Forge's static evaluator (K8). {@code leaf=value}: the policy's
 *       observation-only value head v(o) of the seat at its first priority in the horizon turn, as P(win) = (v + 1) / 2;
 *       terminal play-outs 1 / 0 / 0.5.</li>
 *   <li><b>Choice.</b> K8's: argmax of the mean over worlds, a departure from the default only past the departZ gate,
 *       the per-turn guards. A departure answers the chosen entry's most likely candidate.</li>
 * </ol>
 * Every other ask of the seat is the policy's (greedy), as for the pure policy. The observation is obs-v1 only.
 */
public final class RlSearch implements RlSeat.PrioritySearch, LookaheadSearch.SearchHooks {

    /** The arm's search spec (RlActorBench config key {@code search}). */
    public static final class Config {
        public int worlds = 8;
        public int breadth = 4;
        public int horizon = 2;
        public int threads = 0;
        public int maxSteps = 5000;
        public int leafExtraSteps = 400;
        public double departZ = 1.645;
        public double margin = 0.0;
        /** static | value */
        public String leaf = "static";
        /** forge | policy */
        public String playout = "forge";
        public boolean playoutSample = false;
        public String deadEtb = "off", zeroX = "off", crewNoop = "off", departMedian = "off";
        public boolean stack = false;
        /** Also search pass (second, after the default), as K8's candidate set does, whatever its prior. */
        public boolean includePass = false;
        /** host:port of tools/ml/rl/search_server.py. */
        public String server;
        public int readTimeoutMs = 120_000;
        /**
         * Per game, in ms: the game thread's CPU plus its account (the search's workers and every Forge AI eval thread,
         * forge.ai.CpuAccount); past it the game is void (cpu_cap). 0 = none.
         */
        public long cpuCapMs = 0;
        public long seedSalt = 0x51L;
        /** One JSONL row per searched decision (this JVM appends; "{actor}" = the actor id), or null. */
        public String decisionLog;
        /** The checkpoint the service must serve (HELLO_ACK policy_sha), or null = not checked. */
        public String policySha;

        static final java.util.Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("worlds", "breadth",
                "horizon", "threads", "maxSteps", "leafExtraSteps", "departZ", "margin", "leaf", "playout",
                "playoutSample", "deadEtb", "zeroX", "crewNoop", "departMedian", "stack", "server", "readTimeoutMs",
                "cpuCapMs", "seedSalt", "decisionLog", "policySha", "includePass"));

        public static Config parse(final JsonObject o) {
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("search: unknown key " + k);
                }
            }
            final Config c = new Config();
            if (o.has("worlds")) c.worlds = o.get("worlds").getAsInt();
            if (o.has("breadth")) c.breadth = o.get("breadth").getAsInt();
            if (o.has("horizon")) c.horizon = o.get("horizon").getAsInt();
            if (o.has("threads")) c.threads = o.get("threads").getAsInt();
            if (o.has("maxSteps")) c.maxSteps = o.get("maxSteps").getAsInt();
            if (o.has("leafExtraSteps")) c.leafExtraSteps = o.get("leafExtraSteps").getAsInt();
            if (o.has("departZ")) c.departZ = o.get("departZ").getAsDouble();
            if (o.has("margin")) c.margin = o.get("margin").getAsDouble();
            if (o.has("leaf")) c.leaf = o.get("leaf").getAsString();
            if (o.has("playout")) c.playout = o.get("playout").getAsString();
            if (o.has("playoutSample")) c.playoutSample = o.get("playoutSample").getAsBoolean();
            if (o.has("deadEtb")) c.deadEtb = o.get("deadEtb").getAsString();
            if (o.has("zeroX")) c.zeroX = o.get("zeroX").getAsString();
            if (o.has("crewNoop")) c.crewNoop = o.get("crewNoop").getAsString();
            if (o.has("departMedian")) c.departMedian = o.get("departMedian").getAsString();
            if (o.has("stack")) c.stack = o.get("stack").getAsBoolean();
            if (o.has("includePass")) c.includePass = o.get("includePass").getAsBoolean();
            if (o.has("server") && !o.get("server").isJsonNull()) c.server = o.get("server").getAsString();
            if (o.has("readTimeoutMs")) c.readTimeoutMs = o.get("readTimeoutMs").getAsInt();
            if (o.has("cpuCapMs")) c.cpuCapMs = o.get("cpuCapMs").getAsLong();
            if (o.has("seedSalt")) c.seedSalt = o.get("seedSalt").getAsLong();
            if (o.has("decisionLog") && !o.get("decisionLog").isJsonNull()) c.decisionLog = o.get("decisionLog").getAsString();
            if (o.has("policySha") && !o.get("policySha").isJsonNull()) c.policySha = o.get("policySha").getAsString();
            c.check();
            return c;
        }

        void check() {
            if (!"static".equals(leaf) && !"value".equals(leaf)) {
                throw new IllegalArgumentException("search.leaf must be static or value, not " + leaf);
            }
            if (!"forge".equals(playout) && !"policy".equals(playout)) {
                throw new IllegalArgumentException("search.playout must be forge or policy, not " + playout);
            }
            if (worlds < 1 || breadth < 2 || horizon < 1) {
                throw new IllegalArgumentException("search: worlds >= 1, breadth >= 2, horizon >= 1");
            }
            if (server == null) {
                throw new IllegalArgumentException("search.server (host:port of the search service) is required");
            }
            AiFixes.Mode.parse(deadEtb);
            AiFixes.Mode.parse(zeroX);
            AiFixes.Mode.parse(crewNoop);
            AiFixes.Mode.parse(departMedian);
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("worlds", worlds);
            o.addProperty("breadth", breadth);
            o.addProperty("horizon", horizon);
            o.addProperty("threads", threads);
            o.addProperty("maxSteps", maxSteps);
            o.addProperty("leafExtraSteps", leafExtraSteps);
            o.addProperty("departZ", departZ);
            o.addProperty("margin", margin);
            o.addProperty("leaf", leaf);
            o.addProperty("playout", playout);
            o.addProperty("playoutSample", playoutSample);
            o.addProperty("deadEtb", deadEtb);
            o.addProperty("zeroX", zeroX);
            o.addProperty("crewNoop", crewNoop);
            o.addProperty("departMedian", departMedian);
            o.addProperty("stack", stack);
            o.addProperty("includePass", includePass);
            o.addProperty("cpuCapMs", cpuCapMs);
            o.addProperty("seedSalt", seedSalt);
            if (policySha != null) {
                o.addProperty("policySha", policySha);
            }
            return o;
        }
    }

    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();

    private final Config cfg;
    private final LookaheadSearch ls;
    private final long uid;
    private final CardIndex index;
    private final RlKnowledge liveKnow;
    private final String jarSha;
    private final String actorId;
    private final long cpu0;
    /** The game's CPU account (RlActorBench sets it on the game thread): the look-ahead's and Forge AI's other threads. */
    private final java.util.concurrent.atomic.AtomicLong[] gameAcc;
    private double poolCpuMs = 0, searchCpuMs = 0, scoreMs = 0;
    private int scoreCalls = 0, scoreFailures = 0, priorityAsks = 0, searchedAsks = 0, departures = 0,
            searchStackSkipped = 0, playoutFirstAmbiguous = 0;
    /** Search-service connections, one per thread (the game thread and the look-ahead's pool threads). */
    private final ThreadLocal<RlSearchClient> client = new ThreadLocal<>();
    private final List<RlSearchClient> clients = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonObject> rows = new ArrayList<>();
    public String voided;

    /**
     * One game's search for its RL seat. {@code gameSeed} fixes the world draws (decisions are a function of the
     * position, the seed and the service's answers); {@code liveKnow} is the game's seat-knowledge tracker.
     */
    public RlSearch(final Config cfg, final long gameSeed, final long uid, final CardIndex index, final RlKnowledge liveKnow,
            final String jarSha, final String actorId) {
        this.cfg = cfg;
        this.uid = uid;
        this.index = index;
        this.liveKnow = liveKnow;
        this.jarSha = jarSha;
        this.actorId = actorId;
        final LookaheadSearch.Config lc = new LookaheadSearch.Config();
        lc.worlds = cfg.worlds;
        lc.breadth = cfg.breadth;
        lc.horizonTurns = cfg.horizon;
        lc.maxSteps = cfg.maxSteps;
        lc.leafExtraSteps = cfg.leafExtraSteps;
        lc.seed = splitmix(gameSeed ^ splitmix(cfg.seedSalt));
        lc.threads = cfg.threads;
        lc.departZ = cfg.departZ;
        lc.margin = cfg.margin;
        lc.deadEtb = AiFixes.Mode.parse(cfg.deadEtb);
        lc.zeroX = AiFixes.Mode.parse(cfg.zeroX);
        lc.crewNoop = AiFixes.Mode.parse(cfg.crewNoop);
        lc.departMedian = AiFixes.Mode.parse(cfg.departMedian);
        lc.stack = cfg.stack;
        this.ls = new LookaheadSearch(lc);
        ls.setHooks(this, "value".equals(cfg.leaf), "policy".equals(cfg.playout));
        this.cpu0 = TMX.getCurrentThreadCpuTime();
        this.gameAcc = forge.ai.CpuAccount.get();
    }

    static long splitmix(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------------------------------------ service

    private RlSearchClient client() throws IOException {
        RlSearchClient c = client.get();
        if (c == null) {
            c = new RlSearchClient(cfg.server, 10_000, cfg.readTimeoutMs);
            final JsonObject h = new JsonObject();
            h.addProperty("proto", RlSearchClient.PROTO);
            h.addProperty("schema_sha", RlSchema.schemaSha());
            h.addProperty("card_index_sha", index.sha());
            h.addProperty("jar_sha", jarSha);
            h.addProperty("actor_id", actorId);
            h.addProperty("thread", Thread.currentThread().getName());
            h.addProperty("pid", ProcessHandle.current().pid());
            h.addProperty("mode", "search");
            try {
                c.hello(h);
            } catch (IOException e) {
                c.close();
                throw e;
            }
            if (cfg.policySha != null) {
                final String got = c.ack.has("policy_sha") ? c.ack.get("policy_sha").getAsString() : null;
                if (!cfg.policySha.equals(got)) {
                    c.close();
                    throw new RlClient.ServerError("policy_sha", "search service serves " + got + ", the spec pins "
                            + cfg.policySha);
                }
            }
            client.set(c);
            clients.add(c);
        }
        return c;
    }

    /** Close every connection and the look-ahead's pool (call at game end). */
    public void close() {
        ls.shutdown();
        synchronized (clients) {
            for (RlSearchClient c : clients) {
                c.close();
            }
            clients.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------ the decision

    /** S1: one action's identity across identical copies (card name, zone, land flag, ability text); pass = "pass". */
    static String actionKey(final SpellAbility sa) {
        if (sa == null) {
            return "pass";
        }
        final forge.game.card.Card h = sa.getHostCard();
        final String zone = h == null || h.getZone() == null ? "?" : String.valueOf(h.getZone().getZoneType());
        return (h == null ? "?" : h.getName()) + "|" + zone + "|" + (sa.isLandAbility() ? "L" : "S") + "|"
                + sa.getDescription();
    }

    /** The bridge menu entry (1-based; 0 = pass) and the announced X (-1 = none) of a PRIORITY candidate. */
    static int choiceOf(final RlCandidates.Menu m, final int i) {
        final JsonElement f = m.cands.get(i).frag;
        return f != null && f.isJsonObject() && f.getAsJsonObject().has("choice") ? f.getAsJsonObject().get("choice").getAsInt()
                : -1;
    }

    @Override
    public int decide(final Game g, final Player me, final RlCandidates.Menu m, final RlWire.Decide frame, final int greedy,
            final List<SpellAbility> menuObjs) {
        priorityAsks++;
        if (cfg.cpuCapMs > 0 && gameCpuMs() > cfg.cpuCapMs) {
            voided = "cpu_cap";
            return VOID;
        }
        if (!cfg.stack && !g.getStack().isEmpty()) {
            searchStackSkipped++;
            return -1;
        }
        final long t0 = System.nanoTime();
        final RlSearchClient.Scores sc;
        try {
            sc = client().score(RlWire.encodeDecide(frame));
        } catch (IOException | RuntimeException e) {
            scoreFailures++;
            System.err.println("[rlsearch] SCORE failed, keeping the policy's choice: " + e);
            return -1;
        }
        scoreCalls++;
        scoreMs += (System.nanoTime() - t0) / 1e6;
        final int c = m.C();
        if (sc.status != RlWire.ST_OK || sc.probs.length != c) {
            scoreFailures++;
            return -1;
        }
        // Pool the candidates into entries: X variants of one menu entry, and menu entries that are the same action
        // (the same card name, zone, land flag and ability text: three Islands in hand are one land play). An entry's
        // prior is its candidates' summed probability; its menu choice and candidate are its most likely candidate's,
        // except the default entry's, which are the policy's own (greedy) choice.
        final int def0 = choiceOf(m, greedy);
        final Map<String, Integer> entryOf = new java.util.HashMap<>();
        final Map<Integer, double[]> by = new TreeMap<>();   // entry choice -> {prior, candidate, best p}
        for (int i = 0; i < c; i++) {
            if (m.cands.get(i).kind <= 0) {
                continue;
            }
            final int ch = choiceOf(m, i);
            if (ch < 0 || ch > menuObjs.size()) {
                continue;
            }
            final String key = actionKey(ch == 0 ? null : menuObjs.get(ch - 1));
            Integer e = entryOf.get(key);
            if (e == null) {
                e = ch;
                entryOf.put(key, e);
            }
            final double[] a = by.computeIfAbsent(e, k -> new double[] {0.0, -1, -1.0});
            a[0] += sc.probs[i];
            if (sc.probs[i] > a[2]) {
                a[1] = i;
                a[2] = sc.probs[i];
            }
        }
        final Integer defEntry = entryOf.get(actionKey(def0 == 0 ? null : menuObjs.get(def0 - 1)));
        if (defEntry == null || !by.containsKey(defEntry)) {
            return -1;
        }
        if (defEntry != def0) {
            // the default entry stands for the policy's own choice
            final double[] a = by.remove(defEntry);
            a[1] = greedy;
            by.put(def0, a);
            entryOf.replaceAll((k, v) -> v.equals(defEntry) ? def0 : v);
        } else {
            by.get(def0)[1] = greedy;
        }
        final int def = def0;
        final List<Integer> order = new ArrayList<>();
        order.add(def);
        final List<Integer> rest = new ArrayList<>(by.keySet());
        rest.remove(Integer.valueOf(def));
        rest.sort((x, y) -> {
            final int k = Double.compare(by.get(y)[0], by.get(x)[0]);
            return k != 0 ? k : Integer.compare(x, y);
        });
        if (cfg.includePass && def != 0 && by.containsKey(0)) {
            // K8's candidate shape: the default, then pass, then the prior's next entries
            order.remove(Integer.valueOf(0));
            rest.remove(Integer.valueOf(0));
            order.add(0);
        }
        order.addAll(rest);
        final List<SpellAbility> given = new ArrayList<>(order.size());
        for (int ch : order) {
            given.add(ch == 0 ? null : menuObjs.get(ch - 1));
        }
        final LookaheadSearch.GivenResult r = ls.decideGiven(g, me, given, cfg.breadth);
        poolCpuMs += r.poolCpuMs;
        int answer = -1;
        if (r.givenIndex.length > 0) {
            searchedAsks++;
            searchCpuMs += r.cpuMs;
        }
        if (r.chosen > 0) {
            answer = (int) by.get(order.get(r.chosen))[1];
            departures++;
        }
        if (cfg.decisionLog != null || rowsWanted) {
            final JsonObject row = new JsonObject();
            row.addProperty("game_uid", Long.toUnsignedString(uid));
            row.addProperty("dec_idx", frame.decIdx);
            row.addProperty("turn", g.getPhaseHandler().getTurn());
            row.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
            row.addProperty("C", c);
            row.addProperty("entries", by.size());
            row.addProperty("v_root", sc.valueObs);
            final JsonArray pr = new JsonArray();
            final JsonArray ix = new JsonArray();
            final JsonArray ev = new JsonArray();
            for (int j = 0; j < r.givenIndex.length; j++) {
                final int ch = order.get(r.givenIndex[j]);
                pr.add(Math.round(by.get(ch)[0] * 1e5) / 1e5);
                ix.add(ch);
                ev.add(Double.isNaN(r.ev[j]) ? null : r.ev[j]);
            }
            row.add("prior", pr);
            row.add("entry", ix);
            row.add("ev", ev);
            // per-world values (diagnostics: paired differences, sign consistency across worlds)
            final JsonArray vw = new JsonArray();
            final double scale = "value".equals(cfg.leaf) ? 1e4 : 10;
            for (int j = 0; j < r.values.length; j++) {
                final JsonArray w = new JsonArray();
                for (double x : r.values[j]) {
                    if (Double.isInfinite(x) || Double.isNaN(x)) {
                        w.add((Number) null);
                    } else {
                        w.add(Math.round(x * scale) / scale);
                    }
                }
                vw.add(w);
            }
            row.add("values", vw);
            row.addProperty("p_default", Math.round(by.get(def)[0] * 1e5) / 1e5);
            row.addProperty("outcome", r.outcome);
            row.addProperty("chosen", r.chosen <= 0 ? 0 : order.get(r.chosen));
            row.addProperty("failed", r.failed);
            row.addProperty("leaf_fallback", r.leafFallback);
            row.addProperty("ms", Math.round(r.ms * 10) / 10.0);
            row.addProperty("cpu_ms", Math.round(r.cpuMs * 10) / 10.0);
            row.addProperty("score_ms", Math.round((System.nanoTime() - t0) / 1e5 - r.ms * 10) / 10.0);
            rows.add(row);
        }
        return answer;
    }

    /** Keep the per-decision rows in memory (tests) even without a log file. */
    public boolean rowsWanted = false;

    public List<JsonObject> rows() {
        return rows;
    }

    /**
     * The game's CPU since this search was made, in ms: the game thread's, plus every thread charged to the game's
     * account (the look-ahead's workers and all Forge AI eval threads), or, without an account, the workers' only.
     */
    public double gameCpuMs() {
        final double other = gameAcc != null && gameAcc.length > 0 ? gameAcc[0].get() / 1e6 : poolCpuMs;
        return (TMX.getCurrentThreadCpuTime() - cpu0) / 1e6 + other;
    }

    public double poolCpuMs() {
        return poolCpuMs;
    }

    /** Per-game summary for the tape and GAME_END. */
    public JsonObject summary() {
        final JsonObject o = new JsonObject();
        o.add("spec", cfg.toJson());
        o.addProperty("priority_asks", priorityAsks);
        o.addProperty("searched", searchedAsks);
        o.addProperty("departures", departures);
        o.addProperty("stack_skipped", searchStackSkipped);
        o.addProperty("score_calls", scoreCalls);
        o.addProperty("score_failures", scoreFailures);
        o.addProperty("score_ms", Math.round(scoreMs * 10) / 10.0);
        o.addProperty("search_cpu_ms", Math.round(searchCpuMs));
        o.addProperty("pool_cpu_ms", Math.round(poolCpuMs));
        o.addProperty("playout_first_ambiguous", playoutFirstAmbiguous);
        if (voided != null) {
            o.addProperty("voided", voided);
        }
        o.add("lookahead", ls.getStats().toJson());
        return o;
    }

    /** Append the per-decision rows to {@code cfg.decisionLog} (one writer per JVM, synchronized). */
    public void flushLog() {
        if (cfg.decisionLog == null || rows.isEmpty()) {
            return;
        }
        final String path = cfg.decisionLog.replace("{actor}", actorId == null ? "actor" : actorId);
        synchronized (RlSearch.class) {
            try (PrintWriter w = new PrintWriter(new FileWriter(path, StandardCharsets.UTF_8, true))) {
                for (JsonObject r : rows) {
                    w.println(RlWire.canonicalString(r));
                }
            } catch (IOException e) {
                System.err.println("[rlsearch] decision log failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ hooks

    /** One copy's seat knowledge (forked from the live game's) and featurizer. */
    static final class CopyCtx {
        final RlKnowledge know;
        final RlFeaturizer feat;

        CopyCtx(final RlKnowledge know, final RlFeaturizer feat) {
            this.know = know;
            this.feat = feat;
        }
    }

    @Override
    public Object onCopy(final Game copy, final Player me) {
        final RlKnowledge k = liveKnow == null ? new RlKnowledge(copy) : liveKnow.forkFor(copy);
        k.attach();
        final RlFeaturizer f = new RlFeaturizer(index);
        f.setVersion(1);
        f.setKnowledge(k);
        return new CopyCtx(k, f);
    }

    /** A PRIORITY frame of the seat with only PASS as candidate: v(o) reads the observation alone. */
    static RlWire.Decide leafFrame(final RlFeaturizer.Obs o, final long uid, final int seat, final int turn) {
        final RlWire.Decide f = new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = 0;
        f.seat = seat;
        f.family = RlSchema.F_PRIORITY;
        f.mode = RlSchema.M_SINGLE;
        f.flags = o.truncated ? RlWire.F_TRUNC_TOKENS : 0;
        f.minPick = 1;
        f.maxPick = 1;
        f.turn = Math.min(0xffff, turn);
        f.L = o.L;
        f.D = o.D;
        f.C = 1;
        f.S = 0;
        f.P = 0;
        f.tokCard = o.tokCard;
        f.tokZone = o.tokZone;
        f.tokAttr = o.tokAttr;
        f.deckCard = o.deckCard;
        f.deckCnt = o.deckCnt;
        System.arraycopy(o.scal, 0, f.scal, 0, RlSchema.N_SCAL);
        System.arraycopy(o.ctx, 0, f.ctx, 0, RlSchema.N_CTX);
        f.candKind = new byte[] {(byte) RlSchema.K_PASS};
        f.candTok = new short[] {-1};
        f.candCard = new int[] {0};
        f.candTgt = new short[] {-1, -1};
        f.candSlot = new short[] {-1};
        f.candNum = new short[] {-1};
        f.candAbility = new byte[] {0};
        f.candFlags = new byte[] {0};
        f.slotTok = new short[0];
        return f;
    }

    @Override
    public Object captureLeaf(final Object copyCtx, final Game g, final Player me) {
        final CopyCtx c = (CopyCtx) copyCtx;
        final RlFeaturizer.Obs o = c.feat.observe(g, me, 0, false, null);
        return RlWire.encodeDecide(leafFrame(o, uid, g.getRegisteredPlayers().indexOf(me), g.getPhaseHandler().getTurn()));
    }

    @Override
    public double[] leafValues(final List<Object> payloads) {
        final List<byte[]> ps = new ArrayList<>(payloads.size());
        for (Object p : payloads) {
            ps.add((byte[]) p);
        }
        final float[] v;
        try {
            v = client().values(ps);
        } catch (IOException | RuntimeException e) {
            System.err.println("[rlsearch] LEAVES failed: " + e);
            return null;
        }
        final double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (Math.max(-1.0, Math.min(1.0, v[i])) + 1.0) / 2.0;
        }
        return out;
    }

    /** The policy-piloted seat of one play-out: a bridged controller whose asks the search service answers. */
    static final class PlayoutController extends PlayerControllerBridge {
        private final LookaheadSearch.LeafProbe probe;

        PlayoutController(final Game g, final Player p, final forge.LobbyPlayer lp, final BenchSession session,
                final int seat, final LookaheadSearch.LeafProbe probe) {
            super(g, p, lp, session, BenchSession.Mode.BRIDGE, seat, new CallCounter());
            this.probe = probe;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (probe != null) {
                probe.onPriority(getGame(), getPlayer());
            }
            return super.chooseSpellAbilityToPlay();
        }
    }

    @Override
    public LookaheadSearch.PlayoutSeat playoutSeat(final Object copyCtx, final Game g, final Player me,
            final LookaheadSearch.FirstAction first, final long seed, final LookaheadSearch.LeafProbe probe) {
        final CopyCtx cc = (CopyCtx) copyCtx;
        final int seat = g.getRegisteredPlayers().indexOf(me);
        final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),
                OutputStream.nullOutputStream()));
        session.setEncodeState(false);
        session.setLiveGame(g);
        session.setKnowledgeObserver(cc.know);
        final String[] ctl = new String[g.getRegisteredPlayers().size()];
        for (int s = 0; s < ctl.length; s++) {
            ctl[s] = s == seat ? "rl:playout" : "forge";
        }
        final RlSeat.Config sc = new RlSeat.Config();
        sc.mode = "eval";
        final RlSeat.Endpoint ep = new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                return client().decide(p, cfg.playoutSample);
            }

            @Override
            public void record(final byte[] p) throws IOException {
                throw new IOException("a play-out seat never records");
            }
        };
        final RlSeat rs = new RlSeat(cc.feat, ep, sc, seed, ctl, false);
        rs.setGame(g);
        rs.seenNames = cc.know::opponentSeen;
        final String[] why = {null};
        rs.playoutFirst = new RlSeat.PlayoutFirst() {
            @Override
            public int choose(final Game gg, final Player p, final RlCandidates.Menu m, final List<SpellAbility> objs,
                    final java.util.function.Supplier<RlWire.Decide> frame) {
                final List<Integer> match = new ArrayList<>();
                for (int i = 0; i < m.C(); i++) {
                    if (m.cands.get(i).kind <= 0) {
                        continue;
                    }
                    final int ch = choiceOf(m, i);
                    if (ch == 0 ? first.pass : ch > 0 && ch <= objs.size() && first.matches(objs.get(ch - 1))) {
                        match.add(i);
                    }
                }
                if (match.isEmpty()) {
                    why[0] = "the searched candidate is not in the play-out's menu";
                    return -1;
                }
                if (match.size() == 1) {
                    return match.get(0);
                }
                // X variants (or a duplicated entry): the policy's most likely of them
                synchronized (RlSearch.this) {
                    playoutFirstAmbiguous++;
                }
                try {
                    final RlSearchClient.Scores s = client().score(RlWire.encodeDecide(frame.get()));
                    int best = match.get(0);
                    for (int i : match) {
                        if (i < s.probs.length && s.probs[i] > s.probs[best]) {
                            best = i;
                        }
                    }
                    return best;
                } catch (IOException | RuntimeException e) {
                    why[0] = "SCORE for the first action failed: " + e;
                    return -1;
                }
            }

            @Override
            public String why() {
                return why[0];
            }
        };
        session.setLocalAnswerer(rs);
        final PlayoutController pc = new PlayoutController(g, me, me.getController().getLobbyPlayer(), session, seat, probe);
        return new LookaheadSearch.PlayoutSeat() {
            @Override
            public PlayerControllerAi controller() {
                return pc;
            }

            @Override
            public String failure() {
                if (rs.fatal != null) {
                    return rs.fatal;
                }
                return rs.voidReason;
            }
        };
    }
}
