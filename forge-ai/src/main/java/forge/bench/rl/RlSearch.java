package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
import java.util.zip.GZIPOutputStream;

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
 * Every other ask of the seat is the policy's (greedy), as for the pure policy.
 *
 * <p><b>Observation schema</b> (lane search-v2-1009): the search speaks the seat's own schema, obs-v1 (wire/1, the
 * default) or obs-v2 (wire/2): its HELLO names that schema's sha (the service refuses another), the copies' featurizers
 * and forked seat knowledge run it, and the leaf frames are that schema's PRIORITY frames, so v(o), the prior and the
 * play-out seat read the observation the policy was trained on. The spec key {@code obs} (absent = the seat's) pins it:
 * a seat of another schema refuses the spec, and a service whose HELLO_ACK names another schema is refused. Under
 * obs-v1 every frame, HELLO and log row is byte-identical to the S1 read's.
 *
 * <p><b>S-t</b> (lane cm-choice-search-1009; spec key {@code choices} = m > 0, policy play-outs only; with m = 0, the
 * default, nothing below the S1 search runs): each searched candidate's world-0 play-out is also a probe that records
 * the seat's choice asks inside the candidate's resolution window ({@link ChoiceWindow}: until the seat's next priority
 * with an empty stack, or the end of the turn) with the policy's prior. For the first m asks of a candidate that have an
 * alternative (and, with {@code choiceMaxProb} < 1, that the policy answers with prior below it), the top
 * {@code choiceAlts} other answers by prior each make a <i>macro candidate</i>: the candidate with that answer forced (a
 * schedule matched by the ask's identity and ordinal and the answer's identity, {@link ChoiceWindow}). Macros, in search
 * order (or by joint prior: {@code choiceRank}) up to {@code choiceCap} searched candidates in all, are played out in the
 * same K worlds and compete in the same argmax and departZ gate. A macro departure plays its base action and installs its schedule in the live seat's window; a
 * scheduled live ask whose answer is not a candidate falls back to the policy (counted).
 *
 * <p><b>Label sink</b> (lane r3-distill-1010, R3-CM D2; spec key {@code labelSink}, set by the harness like
 * {@code decisionLog}, never part of the teacher's spec; absent = off, and nothing below writes or calls anything): per
 * searched decision a ROOT record (the DECIDE payload SCORE saw, the policy's prior, each candidate's pooled entry, every
 * searched candidate's per-world values and mean, the macros, the teacher's choice), and after a macro departure a
 * CHOICE record per scheduled live ask the schedule answered (the ask's frame re-encoded SINGLE as the probe scores it,
 * SCORE's prior on it, the policy's own and the scheduled answer, each candidate's match to a searched answer). One
 * gzip member of {@code MXL1} records per game, appended at game end ({@link #flushLog}); format
 * {@code mtgx-rl-labelsink/1}, read by mtgx {@code tools/ml/rl/labels.py}. The sink only records: every decision is
 * the same with it on or off.
 *
 * <p><b>Search seat</b> (r3-distill-1010): {@link #searchSeat} >= 0 (the GAME message's {@code search_seat}) limits the
 * look-ahead to that seat (RlSeat checks it); the summary then echoes {@code seat}. -1 (the default) = every RL seat.
 */
public final class RlSearch implements RlSeat.PrioritySearch, LookaheadSearch.SearchHooks, LookaheadSearch.ChoiceExpander {

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
        /**
         * R3-CM D2 (lane r3-distill-1010): the label sink file ("{actor}" = the actor id, "{pid}" = this JVM's pid), or
         * null = off. Set by the harness, like {@link #decisionLog}; not in {@link #toJson}.
         */
        public String labelSink;
        /** The checkpoint the service must serve (HELLO_ACK policy_sha), or null = not checked. */
        public String policySha;
        /**
         * Live play (lane live-sc-1009; 0 = none, the default: decisions do not depend on the host): the look-ahead's
         * wall-clock budget per searched decision ({@link LookaheadSearch.Config#budgetMs}); past it the default (the
         * policy's own choice) is played ("capped"), as the live K8 plays Forge's answer. A policy play-out seat stops at
         * its next service request past it, and the wait for the play-outs ends at the budget plus
         * {@link LookaheadSearch#WALL_GRACE_MS} at the latest (lane sc-wallguard-1009).
         */
        public long budgetMs = 0L;
        /** Connect timeout of each search-service connection, in ms (live play shortens it; default as S1). */
        public int connectTimeoutMs = 10_000;
        /**
         * S-t (lane cm-choice-search-1009): the contested choice asks expanded per searched candidate (m); 0 = off (the
         * S1 search exactly). Policy play-outs only.
         */
        public int choices = 0;
        /** S-t: alternative answers per expanded ask (b), by the policy's prior. */
        public int choiceAlts = 2;
        /**
         * S-t: an ask is expanded when the policy's largest prior over its candidates is below this; 1 (the default) =
         * every ask with an alternative, however sure the policy is (lane cm-choice-search-1009: the policy picks Woodfall
         * Primus over Craterhoof for Natural Order with prior 1.0, so a confidence filter never searches that pick).
         */
        public double choiceMaxProb = 1.0;
        /** S-t: searched candidates in all (base + macro). */
        public int choiceCap = 10;
        /**
         * S-t: which macros fill the cap. "base" (the default): the searched candidates in search order (the policy's own
         * choice first, then by prior), each candidate's asks in window order, each ask's alternatives by prior.
         * "joint": by joint prior (candidate's x answer's; this lane's first spec, which ranks a confidently wrong
         * policy's needed answer last).
         */
        public String choiceRank = "base";
        /**
         * The loop guard (lane cm-choice-search-1009): departures to one candidate at one phase and stack size per turn.
         * 2 = the S1 read's (K8's) rule; a combo loop (Kiki-Jiki + Zealous Conscripts) repeats one action more often.
         */
        public int sameDepartures = 2;
        /** S-t: the families a probe records and expands. */
        public String choiceFamilies = ChoiceWindow.DEFAULT_FAMILIES;
        java.util.Set<Integer> choiceFamilySet = null;
        /**
         * The observation schema the search's policy speaks (lane search-v2-1009): 1 (obs-v1, wire/1) or 2 (obs-v2,
         * wire/2); 0 (the default) = the seat's. Set, it is a pin: a seat of another schema refuses the spec.
         */
        public int obs = 0;

        static final java.util.Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("worlds", "breadth",
                "horizon", "threads", "maxSteps", "leafExtraSteps", "departZ", "margin", "leaf", "playout",
                "playoutSample", "deadEtb", "zeroX", "crewNoop", "departMedian", "stack", "server", "readTimeoutMs",
                "cpuCapMs", "seedSalt", "decisionLog", "policySha", "includePass", "budgetMs", "connectTimeoutMs",
                "choices", "choiceAlts", "choiceMaxProb", "choiceCap", "choiceFamilies", "choiceRank", "sameDepartures",
                "obs", "labelSink"));

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
            if (o.has("budgetMs")) c.budgetMs = o.get("budgetMs").getAsLong();
            if (o.has("connectTimeoutMs")) c.connectTimeoutMs = o.get("connectTimeoutMs").getAsInt();
            if (o.has("choices")) c.choices = o.get("choices").getAsInt();
            if (o.has("choiceAlts")) c.choiceAlts = o.get("choiceAlts").getAsInt();
            if (o.has("choiceMaxProb")) c.choiceMaxProb = o.get("choiceMaxProb").getAsDouble();
            if (o.has("choiceCap")) c.choiceCap = o.get("choiceCap").getAsInt();
            if (o.has("choiceFamilies")) c.choiceFamilies = o.get("choiceFamilies").getAsString();
            if (o.has("choiceRank")) c.choiceRank = o.get("choiceRank").getAsString();
            if (o.has("sameDepartures")) c.sameDepartures = o.get("sameDepartures").getAsInt();
            if (o.has("obs")) c.obs = o.get("obs").getAsInt();
            if (o.has("labelSink") && !o.get("labelSink").isJsonNull()) c.labelSink = o.get("labelSink").getAsString();
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
            if (budgetMs < 0 || connectTimeoutMs < 1 || readTimeoutMs < 1) {
                throw new IllegalArgumentException("search: budgetMs >= 0, connectTimeoutMs >= 1, readTimeoutMs >= 1");
            }
            if (server == null) {
                throw new IllegalArgumentException("search.server (host:port of the search service) is required");
            }
            if (choices < 0 || choiceAlts < 1 || choiceCap < 2 || !(choiceMaxProb > 0 && choiceMaxProb <= 1)) {
                throw new IllegalArgumentException("search: choices >= 0, choiceAlts >= 1, choiceCap >= 2, 0 < choiceMaxProb <= 1");
            }
            if (sameDepartures < 1) {
                throw new IllegalArgumentException("search.sameDepartures >= 1");
            }
            if (obs != 0 && obs != 1 && obs != 2) {
                throw new IllegalArgumentException("search.obs must be 1 or 2 (or absent: the seat's), not " + obs);
            }
            if (!"base".equals(choiceRank) && !"joint".equals(choiceRank)) {
                throw new IllegalArgumentException("search.choiceRank must be base or joint, not " + choiceRank);
            }
            if (choices > 0 && !"policy".equals(playout)) {
                throw new IllegalArgumentException("search.choices (S-t) needs playout=policy");
            }
            choiceFamilySet = ChoiceWindow.parseFamilies(choiceFamilies);
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
            if (budgetMs > 0) {
                // live-sc-1009: only when set, so a bench spec's JSON is unchanged
                o.addProperty("budgetMs", budgetMs);
            }
            if (sameDepartures != 2) {
                // cm-choice-search-1009: only when changed, so an S1 spec's JSON is unchanged
                o.addProperty("sameDepartures", sameDepartures);
            }
            if (obs != 0) {
                // search-v2-1009: only when pinned, so an S1 spec's JSON is unchanged
                o.addProperty("obs", obs);
            }
            if (choices > 0) {
                // cm-choice-search-1009: only when S-t is on, so an S1 spec's JSON is unchanged
                o.addProperty("choices", choices);
                o.addProperty("choiceAlts", choiceAlts);
                o.addProperty("choiceMaxProb", choiceMaxProb);
                o.addProperty("choiceCap", choiceCap);
                o.addProperty("choiceFamilies", choiceFamilies);
                o.addProperty("choiceRank", choiceRank);
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
    /** The observation schema of the seat, its copies and the service (search-v2-1009): 1 or 2. */
    private final int obsVersion;
    private final String jarSha;
    private final String actorId;
    private final long cpu0;
    /** The game's CPU account (RlActorBench sets it on the game thread): the look-ahead's and Forge AI's other threads. */
    private final java.util.concurrent.atomic.AtomicLong[] gameAcc;
    private double poolCpuMs = 0, searchCpuMs = 0, scoreMs = 0;
    private int scoreCalls = 0, scoreFailures = 0, priorityAsks = 0, searchedAsks = 0, departures = 0,
            searchStackSkipped = 0, playoutFirstAmbiguous = 0;
    /** Lane sc-wallguard-1009: play-out service requests refused because their decision was past its budget. */
    private final java.util.concurrent.atomic.AtomicInteger budgetStops = new java.util.concurrent.atomic.AtomicInteger();
    /** Search-service connections, one per thread (the game thread and the look-ahead's pool threads). */
    private final ThreadLocal<RlSearchClient> client = new ThreadLocal<>();
    private final List<RlSearchClient> clients = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonObject> rows = new ArrayList<>();
    public String voided;

    // ---- S-t (lane cm-choice-search-1009; untouched while cfg.choices == 0)
    /** One macro candidate's forced answers, and the counts of its schedule across its play-outs. */
    static final class MacroSpec {
        final List<ChoiceWindow.Entry> entries;
        final ChoiceWindow.Counters playout = new ChoiceWindow.Counters();
        final int baseGiven;
        final double jointPrior;
        final String label;
        /** r3-distill-1010: the probe policy's own answer's identity at this ask (loose key), or null. */
        final String ownLoose;

        MacroSpec(final List<ChoiceWindow.Entry> entries, final int baseGiven, final double jointPrior, final String label) {
            this(entries, baseGiven, jointPrior, label, null);
        }

        MacroSpec(final List<ChoiceWindow.Entry> entries, final int baseGiven, final double jointPrior, final String label,
                final String ownLoose) {
            this.entries = entries;
            this.baseGiven = baseGiven;
            this.jointPrior = jointPrior;
            this.label = label;
            this.ownLoose = ownLoose;
        }
    }

    private final java.util.Set<Integer> choiceFams;
    /** The current decision's probe records (searched candidate index -> its world-0 play-out's asks). */
    private final Map<Integer, List<ChoiceWindow.Ask>> probeAsks = new java.util.concurrent.ConcurrentHashMap<>();
    /** The current decision's probe windows (diagnostics: what each probe saw). */
    private final List<ChoiceWindow> probeWindows = Collections.synchronizedList(new ArrayList<>());
    /** Over the game: every ask the probes saw, by "FAMILY:what happened". */
    private final Map<String, Integer> probeSeen = new TreeMap<>();
    /** The current decision's prior per given candidate (the pooled entries, given order). */
    private double[] givenPrior = new double[0];
    /** The current decision's macros (expander order). */
    private List<MacroSpec> macroList = new ArrayList<>();
    /** A macro departure's window, taken by the live seat right after {@link #decide}. */
    private ChoiceWindow pendingWindow = null;
    /** The live seat's schedule counts over the game, and the play-outs' (every macro's, summed at the end). */
    private final ChoiceWindow.Counters liveCounters = new ChoiceWindow.Counters();
    private final ChoiceWindow.Counters playoutCounters = new ChoiceWindow.Counters();
    private int probeAsksTotal = 0, contestedAsks = 0, macroDecisions = 0, macrosSearched = 0, macroDepartures = 0,
            macroDefaultDepartures = 0;

    // ---- the label sink (lane r3-distill-1010; untouched while cfg.labelSink == null)
    /** The look-ahead's seat (the GAME's search_seat), or -1 = every RL seat. RlActorBench sets it. */
    public int searchSeat = -1;
    private final long gameSeed;
    /** The checkpoint the service serves (its HELLO_ACK policy_sha), for the label records. */
    private volatile String servedPolicySha;
    /** This game's label records (encoded), written at game end. */
    private final List<byte[]> labelRecs = new ArrayList<>();
    /** The live macro departure whose schedule the seat answers (set by decide, read by scheduledAsk). */
    private LiveMacro liveMacro = null;
    private int labelRoots = 0, labelChoices = 0, labelFailures = 0;

    /** A macro departure's context for its scheduled live asks' choice records. */
    static final class LiveMacro {
        final int rootDecIdx;
        final int macro;
        final List<MacroSpec> macros;

        LiveMacro(final int rootDecIdx, final int macro, final List<MacroSpec> macros) {
            this.rootDecIdx = rootDecIdx;
            this.macro = macro;
            this.macros = macros;
        }
    }

    /** The sink record magic (format mtgx-rl-labelsink/1). */
    static final byte[] LABEL_MAGIC = {'M', 'X', 'L', '1'};

    /**
     * One game's search for its RL seat. {@code gameSeed} fixes the world draws (decisions are a function of the
     * position, the seed and the service's answers); {@code liveKnow} is the game's seat-knowledge tracker.
     */
    public RlSearch(final Config cfg, final long gameSeed, final long uid, final CardIndex index, final RlKnowledge liveKnow,
            final String jarSha, final String actorId) {
        this(cfg, gameSeed, uid, index, liveKnow, jarSha, actorId, 1);
    }

    /**
     * As above for a seat of observation schema {@code obsVersion} (1 or 2; lane search-v2-1009). A spec that pins
     * another schema ({@link Config#obs}) is refused.
     */
    public RlSearch(final Config cfg, final long gameSeed, final long uid, final CardIndex index, final RlKnowledge liveKnow,
            final String jarSha, final String actorId, final int obsVersion) {
        if (obsVersion != 1 && obsVersion != 2) {
            throw new IllegalArgumentException("search: observation schema " + obsVersion);
        }
        if (cfg.obs != 0 && cfg.obs != obsVersion) {
            throw new IllegalArgumentException("search.obs pins obs-v" + cfg.obs + ", the seat is obs-v" + obsVersion);
        }
        this.obsVersion = obsVersion;
        this.cfg = cfg;
        this.uid = uid;
        this.gameSeed = gameSeed;
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
        lc.budgetMs = cfg.budgetMs;
        lc.maxSameDepartures = cfg.sameDepartures;
        this.ls = new LookaheadSearch(lc);
        ls.setHooks(this, "value".equals(cfg.leaf), "policy".equals(cfg.playout));
        this.choiceFams = cfg.choices > 0 ? (cfg.choiceFamilySet != null ? cfg.choiceFamilySet
                : ChoiceWindow.parseFamilies(cfg.choiceFamilies)) : null;
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
            c = new RlSearchClient(cfg.server, cfg.connectTimeoutMs, cfg.readTimeoutMs);
            final JsonObject h = new JsonObject();
            h.addProperty("proto", RlSearchClient.PROTO);
            h.addProperty("schema_sha", schemaSha(obsVersion));
            h.addProperty("card_index_sha", index.sha());
            h.addProperty("jar_sha", jarSha);
            h.addProperty("actor_id", actorId);
            h.addProperty("thread", Thread.currentThread().getName());
            h.addProperty("pid", ProcessHandle.current().pid());
            h.addProperty("mode", "search");
            try {
                c.hello(h);
                checkObs(c.ack, obsVersion);
            } catch (IOException e) {
                c.close();
                throw e;
            }
            if (c.ack != null && c.ack.has("policy_sha") && !c.ack.get("policy_sha").isJsonNull()) {
                servedPolicySha = c.ack.get("policy_sha").getAsString();
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

    /**
     * Drop this thread's connection after a failed call (cm-choice-search-1009): the service closes a connection on any
     * error it answers, so the next call on this thread connects again instead of failing on a dead socket. Nothing
     * changes while every call succeeds.
     */
    private void drop() {
        final RlSearchClient c = client.get();
        if (c != null) {
            client.remove();
            clients.remove(c);
            c.close();
            synchronized (this) {
                reconnects++;
            }
        }
    }

    private int reconnects = 0;

    /** The HELLO's {@code schema_sha} for observation schema {@code v} (search-v2-1009; obs-v1: the S1 read's). */
    public static String schemaSha(final int v) {
        return v == 2 ? RlSchemaV2.schemaSha() : RlSchema.schemaSha();
    }

    /**
     * The service's observation schema against the seat's (search-v2-1009): a HELLO_ACK that names another schema
     * ({@code obs_schema}) is refused; under obs-v2 the ACK must name it (a service that does not say which schema it
     * decodes with is not trusted with v2 frames). An obs-v1 seat accepts an ACK without the key (the S1 read's service).
     */
    public static void checkObs(final JsonObject ack, final int v) throws RlClient.ServerError {
        final Integer got = ack != null && ack.has("obs_schema") && !ack.get("obs_schema").isJsonNull()
                ? ack.get("obs_schema").getAsInt() : null;
        if (got == null ? v != 1 : got != v) {
            throw new RlClient.ServerError("obs_schema", "the service decodes obs-v" + (got == null ? "? (not stated)" : got)
                    + ", the seat speaks obs-v" + v);
        }
    }

    public int obsVersion() {
        return obsVersion;
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
        pendingWindow = null;
        liveMacro = null;
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
        final byte[] payload = RlWire.encodeDecide(frame);
        try {
            sc = client().score(payload);
        } catch (IOException | RuntimeException e) {
            drop();
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
        final LookaheadSearch.GivenResult r;
        if (cfg.choices > 0) {
            // S-t: the given candidates' priors (the expander's joint prior), then the search with the expander
            probeAsks.clear();
            probeWindows.clear();
            macroList = new ArrayList<>();
            givenPrior = new double[order.size()];
            for (int i = 0; i < order.size(); i++) {
                givenPrior[i] = by.get(order.get(i))[0];
            }
            r = ls.decideGiven(g, me, given, cfg.breadth, this);
        } else {
            r = ls.decideGiven(g, me, given, cfg.breadth);
        }
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
        if (r.chosenMacro >= 0) {
            // S-t: a macro departure: its base action (the default's when r.chosen == 0), then its schedule
            final MacroSpec ms = macroList.get(r.chosenMacro);
            pendingWindow = ChoiceWindow.scheduled(ms.entries, liveCounters);
            macroDepartures++;
            if (cfg.labelSink != null) {
                liveMacro = new LiveMacro(frame.decIdx, r.chosenMacro, new ArrayList<>(macroList));
            }
            if (r.chosen == 0) {
                macroDefaultDepartures++;
                departures++;
            }
        }
        if (cfg.choices > 0 && r.macro.length > 0) {
            macroDecisions++;
            macrosSearched += macroList.size();
            for (MacroSpec ms : macroList) {
                ms.playout.addTo(playoutCounters);
            }
        }
        final Map<String, Integer> seenNow = new TreeMap<>();
        if (cfg.choices > 0) {
            synchronized (probeWindows) {
                for (ChoiceWindow w : probeWindows) {
                    for (Map.Entry<String, Integer> e : w.seen().entrySet()) {
                        seenNow.merge(e.getKey(), e.getValue(), Integer::sum);
                        probeSeen.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
        }
        final Searched hook = onSearched;
        if (hook != null && r.givenIndex.length > 0) {
            try {
                hook.searched(g, r, by.size(), (System.nanoTime() - t0) / 1e6 - r.ms);
            } catch (RuntimeException e) {
                System.err.println("[rlsearch] onSearched failed: " + e);
            }
        }
        if (cfg.labelSink != null && r.givenIndex.length > 0) {
            try {
                labelRecs.add(labelRecord(rootHeader(g, m, frame, sc, greedy, menuObjs, entryOf, order, def, r, answer),
                        payload));
                labelRoots++;
            } catch (RuntimeException e) {
                labelFailures++;
                System.err.println("[rlsearch] label root record failed: " + e);
            }
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
            if (cfg.choices > 0) {
                // S-t: per searched candidate, its macro (-1 = a base candidate), and every macro's schedule
                final JsonArray mi = new JsonArray();
                for (int j = 0; j < r.givenIndex.length; j++) {
                    mi.add(j < r.macro.length ? r.macro[j] : -1);
                }
                row.add("macro", mi);
                final JsonArray md = new JsonArray();
                for (MacroSpec ms : macroList) {
                    final JsonObject x = new JsonObject();
                    x.addProperty("base", order.get(ms.baseGiven));
                    x.addProperty("joint_prior", Math.round(ms.jointPrior * 1e5) / 1e5);
                    final JsonArray es = new JsonArray();
                    for (ChoiceWindow.Entry e : ms.entries) {
                        es.add(e.label());
                    }
                    x.add("schedule", es);
                    x.addProperty("playout_hits", ms.playout.hits());
                    x.addProperty("playout_miss", ms.playout.miss.get());
                    x.addProperty("playout_unused", ms.playout.unused.get());
                    md.add(x);
                }
                row.add("macros", md);
                row.addProperty("chosen_macro", r.chosenMacro);
                int asks = 0;
                final JsonArray pa = new JsonArray();
                for (Map.Entry<Integer, List<ChoiceWindow.Ask>> e : new TreeMap<>(probeAsks).entrySet()) {
                    synchronized (e.getValue()) {
                        for (ChoiceWindow.Ask a : e.getValue()) {
                            asks++;
                            // [searched candidate, ask, ordinal, C, the largest prior, the policy's own answer's prior]
                            final JsonArray x = new JsonArray();
                            x.add(e.getKey());
                            x.add(a.ask);
                            x.add(a.ordinal);
                            x.add(a.C);
                            x.add(Math.round(a.maxPrior() * 1e4) / 1e4);
                            x.add(a.greedy >= 0 && a.greedy < a.C ? Math.round(a.prior[a.greedy] * 1e4) / 1e4 : -1.0);
                            pa.add(x);
                        }
                    }
                }
                row.addProperty("probe_asks", asks);
                row.add("probe", pa);
                final JsonObject ps = new JsonObject();
                for (Map.Entry<String, Integer> e : seenNow.entrySet()) {
                    ps.addProperty(e.getKey(), e.getValue());
                }
                row.add("probe_seen", ps);
            }
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

    @Override
    public ChoiceWindow takeWindow() {
        final ChoiceWindow w = pendingWindow;
        pendingWindow = null;
        return w;
    }

    // ------------------------------------------------------------------------------------------------ the label sink

    /** One sink record: "MXL1", u32le header length, the header's canonical JSON, u32le payload length, the payload. */
    static byte[] labelRecord(final JsonObject header, final byte[] payload) {
        final byte[] h = RlWire.canonicalString(header).getBytes(StandardCharsets.UTF_8);
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + 4 + h.length + 4 + payload.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(LABEL_MAGIC).putInt(h.length).put(h).putInt(payload.length).put(payload);
        return b.array();
    }

    /** Append one game's records to {@code path} as ONE gzip member (a crash mid-write tears only that member). */
    static void appendGame(final String path, final List<byte[]> recs) throws IOException {
        final File f = new File(path);
        final File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(buf)) {
            for (byte[] r : recs) {
                z.write(r);
            }
        }
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(buf.toByteArray());
            out.getFD().sync();
        }
    }

    private static JsonArray probsJson(final float[] p) {
        final JsonArray a = new JsonArray();
        for (float x : p) {
            a.add((double) x);
        }
        return a;
    }

    private void provenance(final JsonObject h, final Game g, final RlWire.Decide frame, final long policyVersion) {
        h.addProperty("game_uid", Long.toUnsignedString(uid));
        h.addProperty("seed", gameSeed);
        h.addProperty("seat", frame.seat);
        h.addProperty("dec_idx", frame.decIdx);
        h.addProperty("turn", g.getPhaseHandler().getTurn());
        h.addProperty("obs", obsVersion);
        h.addProperty("policy_sha", servedPolicySha);
        h.addProperty("policy_version", policyVersion);
        h.addProperty("jar_sha", jarSha);
        h.addProperty("actor", actorId);
    }

    /** The ROOT record's header of one searched decision (schema mtgx-rl-labelsink/1; mtgx labels.py reads it). */
    private JsonObject rootHeader(final Game g, final RlCandidates.Menu m, final RlWire.Decide frame,
            final RlSearchClient.Scores sc, final int greedy, final List<SpellAbility> menuObjs,
            final Map<String, Integer> entryOf, final List<Integer> order, final int def,
            final LookaheadSearch.GivenResult r, final int answer) {
        final JsonObject h = new JsonObject();
        h.addProperty("kind", "root");
        provenance(h, g, frame, sc.policyVersion);
        h.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        h.addProperty("C", m.C());
        h.add("prior", probsJson(sc.probs));
        final JsonArray ce = new JsonArray();
        for (int i = 0; i < m.C(); i++) {
            final int ch = m.cands.get(i).kind <= 0 ? -1 : choiceOf(m, i);
            final Integer e = ch < 0 || ch > menuObjs.size() ? null
                    : entryOf.get(actionKey(ch == 0 ? null : menuObjs.get(ch - 1)));
            ce.add(e == null ? -1 : e);
        }
        h.add("cand_entry", ce);
        h.addProperty("default_cand", greedy);
        h.addProperty("default_entry", def);
        final JsonArray se = new JsonArray();
        for (int j = 0; j < r.givenIndex.length; j++) {
            final JsonObject x = new JsonObject();
            x.addProperty("entry", order.get(r.givenIndex[j]));
            x.addProperty("macro", cfg.choices > 0 && j < r.macro.length ? r.macro[j] : -1);
            if (j < r.ev.length && !Double.isNaN(r.ev[j]) && !Double.isInfinite(r.ev[j])) {
                x.addProperty("ev", r.ev[j]);
            } else {
                x.add("ev", com.google.gson.JsonNull.INSTANCE);
            }
            final JsonArray w = new JsonArray();
            if (j < r.values.length) {
                for (double v : r.values[j]) {
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        w.add(com.google.gson.JsonNull.INSTANCE);
                    } else {
                        w.add(v);
                    }
                }
            }
            x.add("values", w);
            se.add(x);
        }
        h.add("searched", se);
        final JsonArray ma = new JsonArray();
        if (cfg.choices > 0) {
            for (MacroSpec ms : macroList) {
                final ChoiceWindow.Entry e = ms.entries.get(0);
                final JsonObject x = new JsonObject();
                x.addProperty("base_entry", order.get(ms.baseGiven));
                x.addProperty("ask", e.ask);
                x.addProperty("ordinal", e.ordinal);
                x.addProperty("exact", e.exact);
                x.addProperty("loose", e.loose);
                x.addProperty("own_loose", ms.ownLoose);
                x.addProperty("joint_prior", ms.jointPrior);
                ma.add(x);
            }
        }
        h.add("macros", ma);
        h.addProperty("chosen_cand", answer >= 0 ? answer : greedy);
        h.addProperty("chosen_entry", r.chosen <= 0 ? def : order.get(r.chosen));
        h.addProperty("chosen_macro", r.chosenMacro);
        h.addProperty("departed", r.chosen > 0 || r.chosenMacro >= 0);
        h.addProperty("outcome", r.outcome);
        h.addProperty("failed", r.failed);
        h.addProperty("leaf_fallback", r.leafFallback);
        return h;
    }

    /**
     * The CHOICE record of a scheduled live ask (r3-distill-1010): called by the live seat after its window's schedule
     * answered ask {@code askKey}#{@code ordinal} with {@code scheduled} (the policy's own answer was {@code mine}). Each
     * candidate's {@code match}: m = macro m's answer at this ask (same ask, ordinal and base candidate as the departed
     * macro), -2 = the policy's own (live) answer's identity (its value is the base candidate's: the base's play-out
     * answered with the policy), -1 = unsearched. A candidate that is both a macro's answer and the policy's takes the
     * macro (its value is that answer's, forced).
     */
    @Override
    public void scheduledAsk(final Game g, final Player me, final RlCandidates.Menu m, final RlWire.Decide frame,
            final String askKey, final int ordinal, final int scheduled, final int mine) {
        final LiveMacro lm = liveMacro;
        if (cfg.labelSink == null || lm == null) {
            return;
        }
        final int mo = frame.mode, mi = frame.minPick, ma = frame.maxPick;
        final byte[] sp;
        try {
            frame.mode = RlSchema.M_SINGLE;
            frame.minPick = 1;
            frame.maxPick = 1;
            sp = RlWire.encodeDecide(frame);
        } finally {
            frame.mode = mo;
            frame.minPick = mi;
            frame.maxPick = ma;
        }
        try {
            final RlSearchClient.Scores ps;
            try {
                ps = client().score(sp);
            } catch (IOException | RuntimeException e) {
                drop();
                throw new IOException(e);
            }
            if (ps.status != RlWire.ST_OK || ps.probs.length != m.C()) {
                labelFailures++;
                return;
            }
            final MacroSpec m0 = lm.macros.get(lm.macro);
            // the policy's own live answer stands for the base candidate (its play-out answered with the policy)
            final String own = mine >= 0 && mine < m.C() && m.cands.get(mine).kind > 0 ? ChoiceWindow.looseKey(m, mine, me)
                    : null;
            final JsonArray match = new JsonArray();
            for (int i = 0; i < m.C(); i++) {
                if (m.cands.get(i).kind <= 0) {
                    match.add(-1);
                    continue;
                }
                final String l = ChoiceWindow.looseKey(m, i, me);
                int k = -1;
                for (int q = 0; q < lm.macros.size(); q++) {
                    final MacroSpec ms = lm.macros.get(q);
                    final ChoiceWindow.Entry e = ms.entries.get(0);
                    if (ms.baseGiven == m0.baseGiven && e.ordinal == ordinal && e.ask.equals(askKey) && e.loose.equals(l)
                            && (k < 0 || q == lm.macro)) {
                        k = q;
                    }
                }
                if (k < 0 && own != null && own.equals(l)) {
                    k = -2;
                }
                match.add(k);
            }
            final JsonObject h = new JsonObject();
            h.addProperty("kind", "choice");
            provenance(h, g, frame, ps.policyVersion);
            h.addProperty("family", frame.family);
            h.addProperty("ask", askKey);
            h.addProperty("ordinal", ordinal);
            h.addProperty("root_dec_idx", lm.rootDecIdx);
            h.addProperty("macro", lm.macro);
            h.addProperty("C", m.C());
            h.add("prior", probsJson(ps.probs));
            h.addProperty("policy_answer", mine);
            h.addProperty("scheduled", scheduled);
            h.add("match", match);
            labelRecs.add(labelRecord(h, sp));
            labelChoices++;
        } catch (IOException | RuntimeException e) {
            labelFailures++;
            System.err.println("[rlsearch] label choice record failed: " + e);
        }
    }

    /**
     * S-t: the macro candidates of the current decision. Per searched candidate (its probe's asks in order), the first
     * {@code choices} expandable asks (an alternative exists; with {@code choiceMaxProb} < 1, also the largest prior below
     * it) each give their top {@code choiceAlts} alternatives ({@link ChoiceWindow.Ask#alternatives}: other identities
     * than the policy's own answer, by prior). The first {@code choiceCap} minus the base count are kept, in the order
     * found ({@code choiceRank} base) or by joint prior (joint), ties in the order found.
     */
    @Override
    public List<LookaheadSearch.Macro> expand(final int[] givenIndex) {
        final List<MacroSpec> found = new ArrayList<>();
        for (int c = 0; c < givenIndex.length; c++) {
            final List<ChoiceWindow.Ask> asks = probeAsks.get(c);
            if (asks == null) {
                continue;
            }
            final List<ChoiceWindow.Ask> copy;
            synchronized (asks) {
                copy = new ArrayList<>(asks);
            }
            probeAsksTotal += copy.size();
            int used = 0;
            for (ChoiceWindow.Ask a : copy) {
                if (used >= cfg.choices) {
                    break;
                }
                if (cfg.choiceMaxProb < 1.0 && a.maxPrior() >= cfg.choiceMaxProb) {
                    continue;
                }
                final List<Integer> alts = a.alternatives();
                if (alts.isEmpty()) {
                    continue;
                }
                used++;
                contestedAsks++;
                for (int t = 0; t < Math.min(cfg.choiceAlts, alts.size()); t++) {
                    final int alt = alts.get(t);
                    final ChoiceWindow.Entry e = new ChoiceWindow.Entry(a.ask, a.ordinal, a.exact[alt], a.loose[alt]);
                    final int gi = givenIndex[c];
                    final double jp = (gi < givenPrior.length ? givenPrior[gi] : 0.0) * a.prior[alt];
                    found.add(new MacroSpec(java.util.Collections.singletonList(e), gi, jp, e.label(),
                            a.greedy >= 0 && a.greedy < a.C ? a.loose[a.greedy] : null));
                }
            }
        }
        final int room = cfg.choiceCap - givenIndex.length;
        final List<LookaheadSearch.Macro> out = new ArrayList<>();
        if (room <= 0 || found.isEmpty()) {
            return out;
        }
        final List<Integer> rank = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            rank.add(i);
        }
        if ("joint".equals(cfg.choiceRank)) {
            rank.sort((x, y) -> {
                final int k = Double.compare(found.get(y).jointPrior, found.get(x).jointPrior);
                return k != 0 ? k : Integer.compare(x, y);
            });
        }
        final java.util.Map<Integer, Integer> baseOf = new java.util.HashMap<>();
        for (int c = 0; c < givenIndex.length; c++) {
            baseOf.putIfAbsent(givenIndex[c], c);
        }
        for (int i = 0; i < Math.min(room, rank.size()); i++) {
            final MacroSpec ms = found.get(rank.get(i));
            macroList.add(ms);
            out.add(new LookaheadSearch.Macro(baseOf.get(ms.baseGiven), ms, ms.label));
        }
        return out;
    }

    /** Keep the per-decision rows in memory (tests) even without a log file. */
    public boolean rowsWanted = false;

    /** Live play (lane live-sc-1009): told about every searched decision (null by default: nothing is called). */
    public interface Searched {
        /** {@code entries}: the pooled menu entries; {@code scoreMs}: the SCORE round trip and pooling before the search. */
        void searched(Game g, LookaheadSearch.GivenResult r, int entries, double scoreMs);
    }

    public Searched onSearched;

    /** The search's per-game counters (live play: the decision lines' running totals). */
    public LookaheadSearch.Stats lookaheadStats() {
        return ls.getStats();
    }

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
        if (budgetStops.get() > 0) {
            // sc-wallguard-1009: only when the budget stopped a play-out seat, so an unbudgeted summary is unchanged
            o.addProperty("playout_budget_stops", budgetStops.get());
        }
        if (voided != null) {
            o.addProperty("voided", voided);
        }
        if (obsVersion != 1) {
            // search-v2-1009: only for an obs-v2 seat, so an S1 game's summary is unchanged
            o.addProperty("obs", obsVersion);
        }
        if (searchSeat >= 0) {
            // r3-distill-1010: only with a GAME search_seat, so every other game's summary is unchanged
            o.addProperty("seat", searchSeat);
        }
        if (cfg.labelSink != null) {
            // r3-distill-1010: only with the label sink on
            final JsonObject lb = new JsonObject();
            lb.addProperty("roots", labelRoots);
            lb.addProperty("choices", labelChoices);
            lb.addProperty("failures", labelFailures);
            o.add("labels", lb);
        }
        if (cfg.choices > 0) {
            // S-t (only when on, so an S1 game's summary is unchanged)
            final JsonObject st = new JsonObject();
            st.addProperty("probe_asks", probeAsksTotal);
            st.addProperty("reconnects", reconnects);
            st.addProperty("expanded_asks", contestedAsks);
            st.addProperty("macro_decisions", macroDecisions);
            st.addProperty("macros", macrosSearched);
            st.addProperty("macro_departures", macroDepartures);
            st.addProperty("macro_default_departures", macroDefaultDepartures);
            final JsonObject ps = new JsonObject();
            for (Map.Entry<String, Integer> e : probeSeen.entrySet()) {
                ps.addProperty(e.getKey(), e.getValue());
            }
            st.add("probe_seen", ps);
            st.add("live", liveCounters.toJson());
            st.add("playout", playoutCounters.toJson());
            o.add("st", st);
        }
        o.add("lookahead", ls.getStats().toJson());
        return o;
    }

    /**
     * Append the per-decision rows to {@code cfg.decisionLog} (one writer per JVM, synchronized), and this game's label
     * records to {@code cfg.labelSink} as one gzip member (r3-distill-1010).
     */
    public void flushLog() {
        if (cfg.labelSink != null && !labelRecs.isEmpty()) {
            final String path = cfg.labelSink.replace("{actor}", actorId == null ? "actor" : actorId)
                    .replace("{pid}", String.valueOf(ProcessHandle.current().pid()));
            synchronized (RlSearch.class) {
                try {
                    appendGame(path, labelRecs);
                } catch (IOException e) {
                    labelFailures++;
                    System.err.println("[rlsearch] label sink failed: " + e);
                }
            }
            labelRecs.clear();
        }
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
        final RlKnowledge k;
        if (liveKnow == null) {
            k = new RlKnowledge(copy);
            k.v2 = obsVersion == 2;
        } else {
            k = liveKnow.forkFor(copy);   // carries the live tracker's v2 extras (seen ids, tail facts and targets)
        }
        k.attach();
        final RlFeaturizer f = new RlFeaturizer(index);
        f.setVersion(obsVersion);
        f.setKnowledge(k);
        return new CopyCtx(k, f);
    }

    /**
     * A PRIORITY frame of the seat with only PASS as candidate: v(o) reads the observation alone. Its observation part
     * is the seat's own frame's ({@code RlSeat.frame}) in the observation's schema: obs-v1 as the S1 read's, obs-v2 with
     * its widths, bits, relations, facts and remaining multiset (search-v2-1009).
     */
    public static RlWire.Decide leafFrame(final RlFeaturizer.Obs o, final long uid, final int seat, final int turn) {
        final RlWire.Decide f = o.version == 2 ? RlWire.Decide.v2() : new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = 0;
        f.seat = seat;
        f.family = RlSchema.F_PRIORITY;
        f.mode = RlSchema.M_SINGLE;
        f.flags = (o.truncated ? RlWire.F_TRUNC_TOKENS : 0) | (o.version == 2 && o.droppedRefs ? RlWire.F_DROPPED_REFS : 0);
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
        System.arraycopy(o.ctx, 0, f.ctx, 0, f.nCtx());
        f.candKind = new byte[] {(byte) RlSchema.K_PASS};
        f.candTok = new short[] {-1};
        f.candCard = new int[] {0};
        f.candTgt = new short[] {-1, -1};
        f.candSlot = new short[] {-1};
        f.candNum = new short[] {-1};
        f.candAbility = new byte[] {0};
        f.candFlags = new byte[] {0};
        f.slotTok = new short[0];
        if (o.version == 2) {
            f.R = o.R;
            f.F = o.F;
            f.Dr = o.Dr;
            f.tokBits = o.tokBits;
            f.relSrc = o.relSrc;
            f.relDst = o.relDst;
            f.relType = o.relType;
            f.relArg = o.relArg;
            f.relNum = o.relNum;
            f.factTok = o.factTok;
            f.factId = o.factId;
            f.factArg = o.factArg;
            f.factNum = o.factNum;
            f.restCard = o.restCard;
            f.restCnt = o.restCnt;
        }
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
            drop();
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

    /**
     * Lane sc-wallguard-1009: a policy play-out's service request past its decision's wall budget is refused here, before
     * it is sent (the connection stays as it is). The seat ends its copy as on any transport fault, and the look-ahead
     * counts the play-out as stopped by the budget (the decision is capped and plays the policy's own choice). A seat
     * looping inside one Forge step (the SCL read's two overruns: max_decisions in one step) so stops within one request
     * of the deadline. Never without a budget.
     */
    private void budgetStop(final LookaheadSearch.FirstAction first) throws IOException {
        if (first != null && first.overBudget()) {
            budgetStops.incrementAndGet();
            throw new IOException("budget: the search's wall budget ran out");
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
                budgetStop(first);
                try {
                    return client().decide(p, cfg.playoutSample);
                } catch (IOException | RuntimeException e) {
                    drop();
                    throw e;
                }
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
                    final RlSearchClient.Scores s;
                    budgetStop(first);
                    try {
                        s = client().score(RlWire.encodeDecide(frame.get()));
                    } catch (IOException | RuntimeException e) {
                        drop();
                        throw e;
                    }
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
        if (cfg.choices > 0) {
            // S-t: a macro's play-out answers its schedule; a base candidate's world-0 play-out records its choice asks
            if (first.schedule instanceof MacroSpec) {
                final MacroSpec ms = (MacroSpec) first.schedule;
                rs.window = ChoiceWindow.scheduled(ms.entries, ms.playout);
            } else if (first.probe && first.candIndex >= 0) {
                final List<ChoiceWindow.Ask> into = probeAsks.computeIfAbsent(first.candIndex,
                        k -> Collections.synchronizedList(new ArrayList<>()));
                rs.window = ChoiceWindow.recorder(choiceFams, payload -> {
                    final RlSearchClient.Scores ps;
                    budgetStop(first);
                    try {
                        ps = client().score(payload);
                    } catch (IOException | RuntimeException e) {
                        drop();
                        throw e;
                    }
                    if (ps.status != RlWire.ST_OK) {
                        return null;
                    }
                    final double[] d = new double[ps.probs.length];
                    for (int i = 0; i < d.length; i++) {
                        d[i] = ps.probs[i];
                    }
                    return d;
                }, into);
                probeWindows.add(rs.window);
            }
        }
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
