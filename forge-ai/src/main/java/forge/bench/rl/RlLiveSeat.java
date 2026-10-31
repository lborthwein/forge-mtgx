package forge.bench.rl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import com.google.gson.JsonObject;

import forge.ai.AiFixes;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.BenchSession;
import forge.bench.JsonRpcChannel;
import forge.game.Game;

/**
 * Live play (lane live-sc-1009): the interactive server's AI seat as the S1 read's arm S-c. The learned policy decides
 * every ask it was trained on, greedily (as in the read); at its contested priority decisions the S1 look-ahead
 * ({@link RlSearch}: the policy's prior, policy play-outs, the v(o) leaf) may depart from the policy's choice, under the
 * live wall budget. Every other ask is Forge AI's, as for the read's RL seat. Nothing here runs unless the interactive
 * server is given {@code -Dforge.interactive.policySearch=<spec>}.
 *
 * <p><b>Pins and boot.</b> {@link #connect} loads the card index and opens the seat's own connection to the policy
 * service ({@code tools/ml/rl/search_server.py}) with the S1 HELLO (schema and card-index shas, checked by the service);
 * the service's {@code policy_sha} must equal the spec's {@code policySha}. Any failure returns null and the server seats
 * its usual opponent (the K8 look-ahead seat, or classic Forge AI) instead: a player always gets a game.
 *
 * <p><b>Observation schema</b> (lane search-v2-1009): the spec's {@code obs} (1, the default, or 2) is the seat's
 * schema: its featurizer, seat knowledge and search run it, its HELLO names that schema's sha, and the service's
 * HELLO_ACK must name the same schema ({@code obs_schema}; under obs-v2 it must be present). A checkpoint of the other
 * schema is therefore refused at boot, by the service or by the seat, and the server seats its usual opponent.
 *
 * <p><b>Faults in a game.</b> A service or protocol fault (a refused connection, a timeout, an ERROR frame, a malformed
 * answer, max_decisions) never ends or stalls the game. The seat degrades once, for the rest of the game: every ask is
 * Forge AI's from then on, and its priority decisions go through the K8 look-ahead with the live K8 spec, i.e. the live
 * K8 seat's behaviour ({@link PlayerControllerPolicy}). Search-internal failures stay inside the search (a failed
 * play-out drops its candidate; a failed leaf call keeps the static leaf for that decision; a failed SCORE keeps the
 * policy's choice), as in the read.
 *
 * <p><b>S-b</b> (lane sb-live-1018; off unless the spec has a {@code plans.} key, and then the seat is S-c byte for byte):
 * the search-bones-1015 turn-plan search over S-c ({@link PlanSearch}: the forced-win solver's lines and policy-sampled
 * plans as candidates, proven backups, the solver's cycles), keyed flat in the spec string:
 * {@code plans.<key>=<value>} for {@link PlanSearch.Spec}'s {@link Spec#LIVE_PLAN_KEYS} and
 * {@code plans.solverCfg.<key>=<value>} for {@link Spec#LIVE_SOLVER_KEYS} (string values, as the bench's S-b2h.json).
 * Live refuses (d) ({@code plans.opp}, {@code plans.oppCfg.*}), payment search ({@code plans.pay},
 * {@code plans.solverCfg.paySearch}) and every other key, and requires {@code plans.solverCfg.hideOwnLibrary=true}: the
 * solver must not search on our real library order, which a player does not see.
 *
 * <p><b>Block search</b> (lane block-search-1019; off unless the spec has {@code blocks=1}, and then the seat is S-c / S-b
 * byte for byte): the seat's declare-blockers asks are searched too ({@link RlSearch#decideBlocks}): the policy's own
 * declaration (the default), its next most sampled ones ({@code blocks.samples}, {@code blocks.top}), no blocks and Forge
 * AI's declaration are played out with the priority search's worlds, leaf, departZ gate and wall budget. One
 * {@code [lookahead-decision]} line per block decision, with {@code "kind":"blocks"}.
 *
 * <p><b>Wall</b> (lane sc-wallguard-1009). A searched decision past its budget plays the policy's own choice
 * ("capped"): its policy play-outs stop at their next service request or main-loop step, and the wait for them ends at
 * the budget plus {@link LookaheadSearch#WALL_GRACE_MS} at the latest (then the decision line carries
 * {@code "wall":true}). So the seat's wait for one searched decision is bounded by about the budget plus 1 s, besides
 * the SCORE and leaf round trips (each bounded by {@code readTimeoutMs}).
 *
 * <p><b>Logs</b> (stderr, for the host): {@code [policy-seat] {...}} once at bind, at a degrade and at the end; one
 * {@code [lookahead-decision] {...}} per searched decision in the K8 line's shape (searchMs, capped, outcome, departed,
 * running totals) plus {@code "seat":"policy"}.
 */
public final class RlLiveSeat {

    /** The {@code -Dforge.interactive.policySearch} spec: comma-separated {@code key=value}. */
    public static final class Spec {
        /** The search (the S-c arm's spec by default) plus the service address, pin, budget and timeouts. */
        public final RlSearch.Config search = new RlSearch.Config();
        public String cardIndex;
        /** Read timeout of the seat's own DECIDE round trip (ms); past it the seat degrades. */
        public int seatTimeoutMs = 5000;
        public int capActions = 40;
        public int maxDecisions = 3000;
        /**
         * The "Cancel Search?" loop guard (lane forge-live-fixes-1012; 0 = off, the default): after K no-answers in a row
         * to the confirm that follows the same card ask answered with no card, the seat answers the next one yes itself
         * ({@link RlLoopGuard}). Live only: no bench seat sets it.
         */
        public int loopGuard = 0;
        /** The seat's own Forge AI (asks the policy leaves to Forge): off, as for the read's RL seat. */
        public AiFixes.Mode aiFixes0928 = AiFixes.Mode.OFF;
        /** One {@code [lookahead-decision]} line per searched decision. */
        public boolean log = true;
        public String raw;

        /** sb-live-1018: the {@code plans.<key>} keys live accepts (numbers, or true/false for {@code solver}). */
        public static final java.util.Set<String> LIVE_PLAN_KEYS = new java.util.TreeSet<>(java.util.Arrays.asList(
                "samples", "temperature", "maxSteps", "solver", "maxLines", "minProvenWorlds", "recordMs"));
        /** sb-live-1018: the {@code plans.solverCfg.<key>} keys live accepts (the solver's sizes, its wall, cycles, the leak fix). */
        public static final java.util.Set<String> LIVE_SOLVER_KEYS = new java.util.TreeSet<>(java.util.Arrays.asList(
                "budgetMs", "engineMs", "evalReserveMs", "wallGraceMs", "planMs", "nodes", "engineNodes", "worlds", "top",
                "horizon", "cycles", "hideOwnLibrary"));

        /**
         * strong-opp-1020: the {@code plans.defendCfg.<key>} keys live accepts and their value pattern; {@code prior} names
         * the jar's co-draft table ({@code jar} = {@link PlanSearch#DEFEND_PRIOR}) or {@code none}. deck-guess-1021:
         * {@code defenseWorlds} up to 16 (a counter that never rests on one sampled world) and {@code seenGain} (the
         * earlier games' cards in the deck prior; 0 = off).
         */
        public static final java.util.Map<String, String> LIVE_DEFEND_KEYS = new java.util.TreeMap<>(java.util.Map.ofEntries(
                java.util.Map.entry("prior", "jar|none"), java.util.Map.entry("priorGain", "\\d{1,3}(\\.\\d+)?"),
                java.util.Map.entry("tieBreakTurns", "[0-2]"),
                java.util.Map.entry("defendMs", "[5-9]\\d\\d|[1-5]\\d{3}|6000"),
                java.util.Map.entry("sharedReader", "true|false"), java.util.Map.entry("priorFillBasics", "true|false"),
                java.util.Map.entry("defenseWorlds", "[1-9]|1[0-6]"),
                java.util.Map.entry("counterDelta", "0(\\.\\d+)?|1(\\.0+)?"),
                java.util.Map.entry("threatExtraTurns", "[0-2]"), java.util.Map.entry("planMs", "\\d{1,5}"),
                java.util.Map.entry("seenGain", "\\d{1,2}(\\.\\d+)?")));

        /** sb-live-1018: one {@code plans.} entry into {@code c.plans} (created on the first), or an error. */
        private static void planKey(final RlSearch.Config c, final String k, final String v) {
            if (c.plans == null) {
                c.plans = new JsonObject();
            }
            final String sub = k.substring("plans.".length());
            if ("defend".equals(sub)) {
                // strong-opp-1020: the solver's defense in front of the seat (on the honest belief, always on live)
                if (!v.matches("true|false") || c.plans.has("defend")) {
                    throw new IllegalArgumentException("policySearch: " + k + "=" + v + " (want true|false, once)");
                }
                c.plans.addProperty("defend", Boolean.parseBoolean(v));
                return;
            }
            if (sub.startsWith("defendCfg.")) {
                final String dk = sub.substring("defendCfg.".length());
                final String pat = LIVE_DEFEND_KEYS.get(dk);
                if (pat == null) {
                    throw new IllegalArgumentException("policySearch: " + k + " is not supported live (plans.defendCfg keys "
                            + String.join("|", LIVE_DEFEND_KEYS.keySet()) + ")");
                }
                if (!v.matches(pat)) {
                    throw new IllegalArgumentException("policySearch: " + k + "=" + v + " (want " + pat + ")");
                }
                if (!c.plans.has("defendCfg")) {
                    c.plans.add("defendCfg", new JsonObject());
                }
                final JsonObject dc = c.plans.getAsJsonObject("defendCfg");
                if (dc.has(dk)) {
                    throw new IllegalArgumentException("policySearch: " + k + " twice");
                }
                if ("prior".equals(dk)) {
                    // jar = the jar's co-draft table; none = recorded (so a second prior key is refused), no prior
                    dc.addProperty(dk, "jar".equals(v) ? PlanSearch.DEFEND_PRIOR : "none");
                    return;
                }
                dc.addProperty(dk, v);
                return;
            }
            if (sub.startsWith("solverCfg.")) {
                final String sk = sub.substring("solverCfg.".length());
                if (!LIVE_SOLVER_KEYS.contains(sk)) {
                    throw new IllegalArgumentException("policySearch: " + k + " is not supported live (plans.solverCfg keys "
                            + String.join("|", LIVE_SOLVER_KEYS) + ")");
                }
                if (("cycles".equals(sk) || "hideOwnLibrary".equals(sk)) ? !v.matches("true|false") : !v.matches("\\d+(\\.\\d+)?")) {
                    throw new IllegalArgumentException("policySearch: " + k + "=" + v + " (want a number, or true|false)");
                }
                if (!c.plans.has("solverCfg")) {
                    c.plans.add("solverCfg", new JsonObject());
                }
                final JsonObject sc = c.plans.getAsJsonObject("solverCfg");
                if (sc.has(sk)) {
                    throw new IllegalArgumentException("policySearch: " + k + " twice");
                }
                sc.addProperty(sk, v);
                return;
            }
            if (!LIVE_PLAN_KEYS.contains(sub)) {
                throw new IllegalArgumentException("policySearch: " + k + " is not supported live (plans keys "
                        + String.join("|", LIVE_PLAN_KEYS) + ", plans.solverCfg.<key>; (d) plans.opp and payment search are off live)");
            }
            if (c.plans.has(sub)) {
                throw new IllegalArgumentException("policySearch: " + k + " twice");
            }
            if ("solver".equals(sub)) {
                if (!v.matches("true|false")) {
                    throw new IllegalArgumentException("policySearch: " + k + "=" + v + " (want true|false)");
                }
                c.plans.addProperty(sub, Boolean.parseBoolean(v));
            } else if ("temperature".equals(sub)) {
                c.plans.addProperty(sub, Double.parseDouble(v));
            } else {
                c.plans.addProperty(sub, Long.parseLong(v));
            }
        }

        /** block-search-1019: a {@code blocks} key's integer value within its bounds, or an error. */
        private static int blockKey(final String k, final String v, final int lo, final int hi) {
            if (!v.matches("\\d{1,3}") || Integer.parseInt(v) < lo || Integer.parseInt(v) > hi) {
                throw new IllegalArgumentException("policySearch: " + k + "=" + v + " (want an integer in " + lo + ".." + hi + ")");
            }
            return Integer.parseInt(v);
        }

        public static Spec parse(final String raw) {
            final Spec s = new Spec();
            s.raw = raw;
            final RlSearch.Config c = s.search;
            // the S1 read's S-c arm (tools/ml/rl/configs/s1/S-c.json), without its bench-only CPU cap
            c.worlds = 8;
            c.breadth = 4;
            c.horizon = 2;
            c.departZ = 1.645;
            c.margin = 0.0;
            c.leaf = "value";
            c.playout = "policy";
            c.playoutSample = false;
            c.deadEtb = "on";
            c.zeroX = "on";
            c.crewNoop = "on";
            c.threads = 0;
            c.cpuCapMs = 0;
            // live defaults: an 8 s wall budget as the live K8; short connects; a search call never outlives the budget
            c.budgetMs = 8000;
            c.connectTimeoutMs = 2000;
            c.readTimeoutMs = 8000;
            // honest-belief-1020 (owner, 2026-10-20: "fix the decklist leak"): live worlds never hold the opponent's real
            // hand or library. They are drawn from the event's card pool (bind's beliefPool, sent by the host) or, without
            // one, from the jar's live pool; there is no live key that turns this off.
            c.belief = "uniform";
            c.beliefCube = forge.ai.simulation.BeliefSampler.LIVE_CUBE;
            c.beliefCubeSha256 = forge.ai.simulation.BeliefSampler.LIVE_CUBE_SHA256;
            c.beliefSingleton = forge.ai.simulation.BeliefSampler.LIVE_CUBE_SINGLETON;
            for (String kv : raw.split(",")) {
                final String[] p = kv.split("=", 2);
                if (p.length != 2) {
                    throw new IllegalArgumentException("policySearch: bad entry '" + kv + "'");
                }
                final String k = p[0].trim(), v = p[1].trim();
                switch (k) {
                    case "server": c.server = v; break;
                    case "policySha": c.policySha = v.toLowerCase(Locale.ROOT); break;
                    case "cardIndex": s.cardIndex = v; break;
                    case "worlds": c.worlds = Integer.parseInt(v); break;
                    case "breadth": c.breadth = Integer.parseInt(v); break;
                    case "horizon": c.horizon = Integer.parseInt(v); break;
                    case "threads": c.threads = Integer.parseInt(v); break;
                    case "maxSteps": c.maxSteps = Integer.parseInt(v); break;
                    case "leafExtraSteps": c.leafExtraSteps = Integer.parseInt(v); break;
                    case "departZ": c.departZ = Double.parseDouble(v); break;
                    case "margin": c.margin = Double.parseDouble(v); break;
                    case "leaf": c.leaf = v; break;
                    case "playout": c.playout = v; break;
                    case "deadEtb": c.deadEtb = v; break;
                    case "zeroX": c.zeroX = v; break;
                    case "crewNoop": c.crewNoop = v; break;
                    case "departMedian": c.departMedian = v; break;
                    case "budgetMs": c.budgetMs = Long.parseLong(v); break;
                    case "connectTimeoutMs": c.connectTimeoutMs = Integer.parseInt(v); break;
                    case "readTimeoutMs": c.readTimeoutMs = Integer.parseInt(v); break;
                    case "seatTimeoutMs": s.seatTimeoutMs = Integer.parseInt(v); break;
                    case "capActions": s.capActions = Integer.parseInt(v); break;
                    case "maxDecisions": s.maxDecisions = Integer.parseInt(v); break;
                    case "loopGuard": s.loopGuard = Integer.parseInt(v); break;
                    case "aiFixes0928": s.aiFixes0928 = AiFixes.Mode.parse(v); break;
                    case "log": s.log = !"0".equals(v); break;
                    case "obs": c.obs = Integer.parseInt(v); break;
                    case "copyFidelity": c.copyFidelity = Integer.parseInt(v); break;
                    // block-search-1019: the look-ahead over declare-blockers asks (absent = off, S-c / S-b unchanged)
                    case "blocks": c.blocks = blockKey(k, v, 0, 1); break;
                    case "blocks.samples": c.blockSamples = blockKey(k, v, 0, 64); break;
                    case "blocks.top": c.blockTop = blockKey(k, v, 1, 8); break;
                    default:
                        if (k.startsWith("plans.")) {
                            planKey(c, k, v); // sb-live-1018: S-b, flat keys
                            break;
                        }
                        throw new IllegalArgumentException("policySearch: unknown key " + k);
                }
            }
            if (c.server == null || s.cardIndex == null || c.policySha == null || !c.policySha.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("policySearch needs server=host:port, cardIndex=<path> and policySha=<64 hex>");
            }
            if (s.seatTimeoutMs < 1) {
                throw new IllegalArgumentException("policySearch: seatTimeoutMs >= 1");
            }
            if (s.loopGuard < 0) {
                throw new IllegalArgumentException("policySearch: loopGuard >= 0 (0 = off)");
            }
            if (c.plans != null && c.plans.has("defend") && c.plans.get("defend").getAsBoolean() && !"uniform".equals(c.belief)) {
                // strong-opp-1020 (review S3-3): the defense reads the honest belief, or it would fall back to their real deck
                throw new IllegalArgumentException("policySearch: plans.defend needs the honest belief (belief=uniform)");
            }
            if (c.plans != null) {
                // sb-live-1018: live S-b never searches on our real library order (the r6 leak fix)
                final JsonObject sc = c.plans.has("solverCfg") ? c.plans.getAsJsonObject("solverCfg") : null;
                if (sc == null || !sc.has("hideOwnLibrary") || !"true".equals(sc.get("hideOwnLibrary").getAsString())) {
                    throw new IllegalArgumentException("policySearch: plans needs plans.solverCfg.hideOwnLibrary=true live");
                }
            }
            c.check();
            AiFixes.Mode.parse(c.departMedian);
            return s;
        }

        /** The seat's observation schema (search-v2-1009): the spec's {@code obs}, else 1. */
        public int obs() {
            return search.obs == 0 ? 1 : search.obs;
        }
    }

    private final Spec spec;
    private final CardIndex index;
    private final String jarSha;
    private final String actorId;
    private final RlSearchClient seatClient;
    private final JsonObject ack;

    // ---- the bound game
    private final BenchSession session;
    private Game game;
    private LobbyPlayerPolicy lobby;
    private RlSeat seat;
    private RlSearch search;
    private LookaheadSearch.Config k8Config;
    private AiFixes.Mode k8AiFixes = AiFixes.Mode.OFF;
    private volatile LookaheadSearch k8;
    private volatile boolean degraded = false;
    private String degradeReason;
    private int degradeTurn = -1;
    private int searchedLines = 0;
    private long seatCalls0 = 0;
    /** sb-live-1018: the seat's controller carrying S-b's plan hook (null without plans); cleared at a degrade. */
    private volatile forge.bench.PlayerControllerBridge planBridge;

    private RlLiveSeat(final Spec spec, final CardIndex index, final String jarSha, final String actorId,
            final RlSearchClient seatClient, final JsonObject ack) {
        this.spec = spec;
        this.index = index;
        this.jarSha = jarSha;
        this.actorId = actorId;
        this.seatClient = seatClient;
        this.ack = ack;
        this.session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(), OutputStream.nullOutputStream()));
        session.setEncodeState(false);
    }

    public Spec spec() {
        return spec;
    }

    /**
     * Boot: the spec, the card index and the seat's own pinned connection. Null (with one {@code [policy-seat]} line
     * saying why) when any of it fails: the caller then seats its usual opponent.
     */
    public static RlLiveSeat connect(final String raw, final String actorId) {
        final long t0 = System.nanoTime();
        Spec spec = null;
        RlSearchClient c = null;
        try {
            spec = Spec.parse(raw);
            final CardIndex index = CardIndex.load(Paths.get(spec.cardIndex));
            final String jarSha = jarSha();
            c = new RlSearchClient(spec.search.server, spec.search.connectTimeoutMs, spec.seatTimeoutMs);
            final JsonObject h = new JsonObject();
            h.addProperty("proto", RlSearchClient.PROTO);
            h.addProperty("schema_sha", RlSearch.schemaSha(spec.obs()));
            h.addProperty("card_index_sha", index.sha());
            h.addProperty("jar_sha", jarSha);
            h.addProperty("actor_id", actorId);
            h.addProperty("thread", Thread.currentThread().getName());
            h.addProperty("pid", ProcessHandle.current().pid());
            h.addProperty("mode", "search");
            final JsonObject ack = c.hello(h);
            RlSearch.checkObs(ack, spec.obs());
            final String got = ack.has("policy_sha") && !ack.get("policy_sha").isJsonNull() ? ack.get("policy_sha").getAsString() : null;
            if (!spec.search.policySha.equals(got)) {
                throw new RlClient.ServerError("policy_sha", "the service serves " + got + ", the spec pins " + spec.search.policySha);
            }
            final RlLiveSeat s = new RlLiveSeat(spec, index, jarSha, actorId, c, ack);
            final JsonObject o = new JsonObject();
            o.addProperty("bound", true);
            o.addProperty("policySha", got);
            o.addProperty("policyVersion", ack.has("policy_version") ? ack.get("policy_version").getAsLong() : -1);
            o.addProperty("device", ack.has("device") ? ack.get("device").getAsString() : "?");
            o.addProperty("cardIndexSha", index.sha());
            if (spec.obs() != 1) {
                o.addProperty("obs", spec.obs());   // search-v2-1009: only for obs-v2, so the v1 line is unchanged
            }
            o.addProperty("server", spec.search.server);
            o.addProperty("budgetMs", spec.search.budgetMs);
            if (spec.loopGuard > 0) {
                o.addProperty("loopGuard", spec.loopGuard); // forge-live-fixes-1012: only when on, so the line is otherwise unchanged
            }
            if (spec.search.plans != null) {
                o.addProperty("plans", true); // sb-live-1018: S-b on (the spec is in search.plans); absent otherwise
            }
            if (spec.search.blocks > 0) {
                o.addProperty("blocks", true); // block-search-1019: block search on; absent otherwise
            }
            if (spec.search.plans != null && spec.search.plans.has("defend") && spec.search.plans.get("defend").getAsBoolean()) {
                o.addProperty("defend", true); // strong-opp-1020: the solver's defense on; absent otherwise
            }
            o.addProperty("belief", spec.search.belief); // honest-belief-1020: always "uniform" live
            o.addProperty("helloMs", Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
            o.add("search", spec.search.toJson());
            line(o);
            return s;
        } catch (IOException | RuntimeException e) {
            if (c != null) {
                c.close();
            }
            final JsonObject o = new JsonObject();
            o.addProperty("bound", false);
            o.addProperty("fallback", "boot");
            o.addProperty("reason", String.valueOf(e));
            o.addProperty("helloMs", Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
            line(o);
            return null;
        }
    }

    private static String jarSha() {
        try {
            final java.net.URL u = RlLiveSeat.class.getProtectionDomain().getCodeSource().getLocation();
            final Path p = Paths.get(u.toURI());
            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                return CardIndex.sha256(Files.readAllBytes(p));
            }
        } catch (Exception e) {
            // fall through
        }
        return "unknown";
    }

    static void line(final JsonObject o) {
        System.err.println("[policy-seat] " + o);
    }

    /** The lobby player for the AI seat (named as the server names its Forge seat). */
    public LobbyPlayerPolicy lobby(final String name, final int seatIndex, final String aiProfile) {
        final LobbyPlayerPolicy lp = new LobbyPlayerPolicy(name, session, seatIndex, this);
        lp.setAiProfile(aiProfile);
        lp.setAiFixes0928(spec.aiFixes0928);
        this.lobby = lp;
        return lp;
    }

    /**
     * Bind the seat to the live game (after the game is created, before it starts), as the read's runner binds its RL
     * seat. {@code k8} (or null) is the live K8 look-ahead's config for a degraded seat, {@code k8AiFixes} its seat's
     * Forge AI fixes; {@code seed} the game's seed (the search's worlds derive from it).
     */
    /**
     * honest-belief-1020: the event's card pool for the next {@link #bind} (null = none: the jar's live pool).
     * {@code singleton}: a cube pool (our deck and the opponent cards we see leave it), else whole.
     */
    public void setBeliefPool(final java.util.List<String> pool, final boolean singleton) {
        spec.search.beliefPool = pool;
        spec.search.beliefSingleton = pool == null ? forge.ai.simulation.BeliefSampler.LIVE_CUBE_SINGLETON : singleton;
    }

    /**
     * deck-guess-1021: the opponent's cards from the earlier games of this match (the host's {@code beliefSeen}; null =
     * none) for the next {@link #bind}. Only the defender's deck prior reads it, and only with
     * {@code plans.defendCfg.seenGain} above 0.
     */
    public void setBeliefSeen(final java.util.List<String> seen) {
        spec.search.beliefSeen = seen;
    }

    public void bind(final Game g, final int seatIndex, final long seed, final LookaheadSearch.Config k8,
            final AiFixes.Mode k8AiFixes) {
        this.game = g;
        this.k8Config = k8;
        this.k8AiFixes = k8AiFixes == null ? AiFixes.Mode.OFF : k8AiFixes;
        final long uid = RlSearch.splitmix(seed ^ 0x6c697665L);
        final RlFeaturizer feat = new RlFeaturizer(index);
        feat.setVersion(spec.obs());
        feat.reset();
        final String[] ctl = new String[g.getRegisteredPlayers().size()];
        for (int s = 0; s < ctl.length; s++) {
            ctl[s] = s == seatIndex ? "rl:eval" : "forge";
        }
        final RlSeat.Config sc = new RlSeat.Config();
        sc.mode = "eval";
        sc.capActions = spec.capActions;
        sc.maxDecisions = spec.maxDecisions;
        sc.loopGuard = spec.loopGuard;
        final RlSeat.Endpoint ep = new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                return seatClient.decide(p, false);
            }

            @Override
            public void record(final byte[] p) throws IOException {
                throw new IOException("a live seat never records");
            }
        };
        final RlSeat rs = new RlSeat(feat, ep, sc, uid, ctl, false);
        session.setLiveGame(g);
        rs.setGame(g);
        final RlKnowledge know = new RlKnowledge(g);
        know.v2 = spec.obs() == 2;
        know.attach();
        session.setKnowledgeObserver(know);
        feat.setKnowledge(know);
        rs.seenNames = know::opponentSeen;
        rs.onFault = this::degrade;
        final RlSearch s = new RlSearch(spec.search, seed, uid, index, know, jarSha, actorId, spec.obs());
        if (spec.log) {
            s.onSearched = this::searched;
        }
        rs.search = s;
        if (spec.search.plans != null) {
            // sb-live-1018: S-b's plan hook on this seat's bridged controller (RlSearch.attach, as the bench's RL seat)
            final boolean[] rl = new boolean[ctl.length];
            rl[seatIndex] = true;
            s.attach(g, rl, rs);
            final forge.game.player.PlayerController pc = g.getRegisteredPlayers().get(seatIndex).getController();
            if (pc instanceof forge.bench.PlayerControllerBridge && ((forge.bench.PlayerControllerBridge) pc).planHook != null) {
                planBridge = (forge.bench.PlayerControllerBridge) pc;
            } else {
                // never seen: the seat would play S-c; say so (the boot line already said plans:true)
                final JsonObject o = new JsonObject();
                o.addProperty("plans", false);
                o.addProperty("reason", "no bridged controller for the plan hook");
                line(o);
            }
            if (spec.log) {
                s.onPlanDecided = this::planDecided;
            }
        }
        if (spec.search.blocks > 0 && spec.log) {
            s.onBlocksDecided = this::blocksDecided; // block-search-1019
        }
        // J1 (block-search-1019): every explain row names the decision line it belongs to; with -Dforge.interactive.aiReplay
        // the live choices are replayed by that line index
        s.nextLine = () -> searchedLines;
        s.ls().explainLine = s.nextLine;
        s.replay = forge.ai.simulation.AiReplay.current();
        this.seat = rs;
        this.search = s;
        this.seatCalls0 = seatClient.calls;
        session.setLocalAnswerer(rs);
    }

    public boolean degraded() {
        return degraded;
    }

    public Game liveGame() {
        return game;
    }

    /** lane live-ai-quality-1030: the dud-cast guard's look-ahead (the live K8 spec's config; null while dudCast is off). */
    private volatile LookaheadSearch dudGuard;

    /**
     * The look-ahead the dud-cast guard runs its copies with: the degraded seat's K8 once there is one, else the seat's own
     * (made on first use from the live K8 spec, honest belief as the K8 seat's). Null when the server has no look-ahead
     * spec or its dudCast is off (the default): the seat's answers are then unchanged.
     */
    synchronized LookaheadSearch dudGuard() {
        if (k8Config == null || k8Config.dudCast == AiFixes.Mode.OFF) {
            return null;
        }
        if (degraded && k8 != null) {
            return k8;
        }
        if (dudGuard == null) {
            try {
                dudGuard = new LookaheadSearch(k8Config);
            } catch (RuntimeException e) {
                k8Config.dudCast = AiFixes.Mode.OFF; // never retried: the seat plays as without the guard
                System.err.println("[policy-seat] dud-cast guard could not start, off for this game: " + e);
                return null;
            }
        }
        return dudGuard;
    }

    /** The guard's stats block ({"dudCast": ...} of the look-aheads it ran in), or null when it never ran. */
    private JsonObject dudStats() {
        final JsonObject o = new JsonObject();
        for (LookaheadSearch s : new LookaheadSearch[] {dudGuard, k8}) {
            final JsonObject st = s == null ? null : s.getStats().toJson();
            if (st != null && st.has("dudCast")) {
                o.add(s == dudGuard ? "seat" : "k8", st.get("dudCast"));
            }
        }
        return o.size() == 0 ? null : o;
    }

    /** The K8 look-ahead of a degraded seat (null before a degrade, or when the server has no look-ahead spec). */
    LookaheadSearch k8() {
        return k8;
    }

    /** The seat stops for the rest of the game: Forge AI decides every ask, through the K8 look-ahead at priority. */
    synchronized void degrade(final String reason) {
        if (degraded) {
            return;
        }
        degraded = true;
        degradeReason = reason + (seat != null && seat.fatal != null ? ": " + seat.fatal : "");
        degradeTurn = game == null ? -1 : game.getPhaseHandler().getTurn();
        session.setLocalAnswerer(null);
        session.getChannel().close();
        final forge.bench.PlayerControllerBridge pb = planBridge;
        if (pb != null) {
            pb.planHook = null; // sb-live-1018: after a degrade Forge AI (and K8) decide alone, no plan follower
            planBridge = null;
        }
        if (lobby != null) {
            lobby.setAiFixes0928(k8AiFixes);
        }
        if (k8Config != null) {
            try {
                k8 = new LookaheadSearch(k8Config);
            } catch (RuntimeException e) {
                k8 = null;
                System.err.println("[policy-seat] K8 fallback could not start, Forge AI alone: " + e);
            }
        }
        final JsonObject o = new JsonObject();
        o.addProperty("degraded", true);
        o.addProperty("reason", degradeReason);
        o.addProperty("turn", degradeTurn);
        o.addProperty("decisions", seat == null ? 0 : seat.totalSent());
        o.addProperty("fallback", k8 != null ? "k8" : "forge");
        line(o);
    }

    private void searched(final Game g, final LookaheadSearch.GivenResult r, final int entries, final double scoreMs) {
        final LookaheadSearch.Stats st = search.lookaheadStats();
        final JsonObject d = new JsonObject();
        d.addProperty("seat", "policy");
        d.addProperty("decision", searchedLines++);
        d.addProperty("turn", g.getPhaseHandler().getTurn());
        d.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        d.addProperty("stack", g.getStack().size());
        d.addProperty("candidates", r.givenIndex.length);
        d.addProperty("entries", entries);
        d.addProperty("worlds", r.worlds);
        d.addProperty("searchMs", Math.round(r.ms * 10) / 10.0);
        d.addProperty("cpuMs", Math.round(r.cpuMs));
        d.addProperty("scoreMs", Math.round(scoreMs * 10) / 10.0);
        d.addProperty("capped", "capped".equals(r.outcome));
        if (r.wall) {
            // sc-wallguard-1009: only when the hard wall cut the wait, so the line is otherwise unchanged
            d.addProperty("wall", true);
        }
        d.addProperty("outcome", r.outcome);
        d.addProperty("departed", r.chosen > 0);
        d.addProperty("failed", r.failed);
        d.addProperty("leafFallback", r.leafFallback);
        d.addProperty("searched", st.searched);
        d.addProperty("departedTotal", st.departed);
        d.addProperty("cappedTotal", st.capped);
        if (forge.ai.WallClock.rebuilding()) {
            // lane forge-continue-1011: a "Continue from here" rebuild's decision (wall clocks off); only then
            d.addProperty("rebuild", true);
        }
        if (search.lastProposeMs >= 0) {
            // sb-live-1018: an S-b plan point that S-c's choice decided (its proposals are inside scoreMs); only then
            d.addProperty("plan", "searched");
            d.addProperty("proposeMs", Math.round(search.lastProposeMs * 10) / 10.0);
        }
        replayFlags(d);
        System.err.println("[lookahead-decision] " + d);
    }

    /**
     * sb-live-1018 (S-b only): one {@code [lookahead-decision]} line for a plan point decided without an S-c line: a
     * proven solver line taken at once ({@code r} null: no play-outs) or a plan / line adopted after them. searchMs is
     * the whole decision (SCORE, proposals, play-outs), so the host's latency and cap counts cover every decision.
     */
    private void planDecided(final Game g, final LookaheadSearch.GivenResult r, final String note, final int plans,
            final double ms) {
        final LookaheadSearch.Stats st = search.lookaheadStats();
        final JsonObject d = new JsonObject();
        d.addProperty("seat", "policy");
        d.addProperty("decision", searchedLines++);
        d.addProperty("turn", g.getPhaseHandler().getTurn());
        d.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        d.addProperty("stack", g.getStack().size());
        d.addProperty("candidates", r == null ? 0 : r.givenIndex.length);
        d.addProperty("worlds", r == null ? 0 : r.worlds);
        d.addProperty("searchMs", Math.round(ms * 10) / 10.0);
        d.addProperty("capped", r != null && "capped".equals(r.outcome));
        if (r != null && r.wall) {
            d.addProperty("wall", true);
        }
        d.addProperty("outcome", r == null ? "proven" : r.outcome);
        d.addProperty("departed", true);
        d.addProperty("plan", r == null ? "proven" : "adopted");
        d.addProperty("plans", plans);
        if (r != null) {
            d.addProperty("failed", r.failed);
            d.addProperty("leafFallback", r.leafFallback);
        }
        d.addProperty("searched", st.searched);
        d.addProperty("departedTotal", st.departed);
        d.addProperty("cappedTotal", st.capped);
        if (forge.ai.WallClock.rebuilding()) {
            d.addProperty("rebuild", true);
        }
        replayFlags(d);
        System.err.println("[lookahead-decision] " + d);
    }

    /**
     * block-search-1019 (block search only): one {@code [lookahead-decision]} line per block decision, with
     * {@code "kind":"blocks"}. searchMs is the whole decision (samples, Forge's declaration, play-outs), so the host's
     * latency and cap counts cover it; an uncontested decision (one distinct declaration) has no play-outs.
     */
    private void blocksDecided(final Game g, final LookaheadSearch.GivenResult r, final int candidates,
            final java.util.List<String> labels, final String chosen, final double ms, final double prepMs) {
        final LookaheadSearch.Stats st = search.lookaheadStats();
        final JsonObject d = new JsonObject();
        d.addProperty("seat", "policy");
        d.addProperty("kind", "blocks");
        d.addProperty("decision", searchedLines++);
        d.addProperty("turn", g.getPhaseHandler().getTurn());
        d.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        d.addProperty("stack", g.getStack().size());
        d.addProperty("candidates", r.givenIndex.length);
        d.addProperty("entries", candidates);
        d.addProperty("worlds", r.worlds);
        d.addProperty("searchMs", Math.round(ms * 10) / 10.0);
        d.addProperty("cpuMs", Math.round(r.cpuMs));
        d.addProperty("scoreMs", Math.round(prepMs * 10) / 10.0);
        d.addProperty("capped", "capped".equals(r.outcome));
        if (r.wall) {
            d.addProperty("wall", true);
        }
        d.addProperty("outcome", r.outcome);
        d.addProperty("departed", r.chosen > 0);
        d.addProperty("chosen", chosen);
        final com.google.gson.JsonArray lb = new com.google.gson.JsonArray();
        labels.forEach(lb::add);
        d.add("labels", lb);
        final com.google.gson.JsonArray ev = new com.google.gson.JsonArray();
        for (double x : r.ev) {
            ev.add(Double.isNaN(x) ? null : Math.round(x * 1e4) / 1e4);
        }
        d.add("ev", ev);
        d.addProperty("failed", r.failed);
        d.addProperty("leafFallback", r.leafFallback);
        d.addProperty("searched", st.searched);
        d.addProperty("departedTotal", st.departed);
        d.addProperty("cappedTotal", st.capped);
        if (forge.ai.WallClock.rebuilding()) {
            d.addProperty("rebuild", true);
        }
        replayFlags(d);
        System.err.println("[lookahead-decision] " + d);
    }

    /** J1: "forced" / "forcedMiss" on a decision line, only when an aiReplay mark acted (a live line is unchanged). */
    private void replayFlags(final JsonObject d) {
        if (search.lastForced) {
            d.addProperty("forced", true);
        }
        if (search.lastForcedMiss) {
            d.addProperty("forcedMiss", true);
        }
    }

    private boolean finished = false;

    /** Game end: one summary line, then every connection and the search's pool are closed (once). */
    public synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            final JsonObject o = new JsonObject();
            o.addProperty("end", true);
            o.addProperty("degraded", degraded);
            if (degraded) {
                o.addProperty("reason", degradeReason);
                o.addProperty("degradeTurn", degradeTurn);
            }
            if (seat != null) {
                o.addProperty("decisions", seat.totalSent());
                o.addProperty("searchDepartures", seat.searchDepartures);
                o.addProperty("capHits", seat.capHits);
                final JsonObject fd = new JsonObject();
                seat.forgeDecidedExtra.forEach(fd::addProperty);
                o.add("forgeDecidedExtra", fd);
                if (spec.loopGuard > 0) {
                    o.addProperty("loopGuardHits", seat.loopGuardHits()); // forge-live-fixes-1012: only when on
                }
            }
            final long calls = seatClient.calls - seatCalls0;
            o.addProperty("seatCalls", calls);
            o.addProperty("seatMsMean", calls == 0 ? 0 : Math.round(seatClient.nanos / 1e5 / Math.max(1, seatClient.calls)) / 10.0);
            if (search != null) {
                o.add("search", search.summary());
            }
            final JsonObject dud = dudStats(); // lane live-ai-quality-1030: only when the guard ran (dudCast on)
            if (dud != null) {
                o.add("dudCast", dud);
            }
            if (k8 != null) {
                o.add("k8", k8.getStats().toJson());
            }
            line(o);
        } catch (RuntimeException e) {
            System.err.println("[policy-seat] summary failed: " + e);
        } finally {
            if (search != null) {
                search.close();
            }
            if (k8 != null) {
                if (game != null) {
                    k8.finishGame(game);
                }
                k8.shutdown();
            }
            if (dudGuard != null) {
                dudGuard.shutdown();
            }
            seatClient.close();
        }
    }
}
