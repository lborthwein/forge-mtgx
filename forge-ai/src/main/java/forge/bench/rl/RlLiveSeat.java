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
 * <p><b>Faults in a game.</b> A service or protocol fault (a refused connection, a timeout, an ERROR frame, a malformed
 * answer, max_decisions) never ends or stalls the game. The seat degrades once, for the rest of the game: every ask is
 * Forge AI's from then on, and its priority decisions go through the K8 look-ahead with the live K8 spec, i.e. the live
 * K8 seat's behaviour ({@link PlayerControllerPolicy}). Search-internal failures stay inside the search (a failed
 * play-out drops its candidate; a failed leaf call keeps the static leaf for that decision; a failed SCORE keeps the
 * policy's choice), as in the read.
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
        /** The seat's own Forge AI (asks the policy leaves to Forge): off, as for the read's RL seat. */
        public AiFixes.Mode aiFixes0928 = AiFixes.Mode.OFF;
        /** One {@code [lookahead-decision]} line per searched decision. */
        public boolean log = true;
        public String raw;

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
                    case "aiFixes0928": s.aiFixes0928 = AiFixes.Mode.parse(v); break;
                    case "log": s.log = !"0".equals(v); break;
                    default: throw new IllegalArgumentException("policySearch: unknown key " + k);
                }
            }
            if (c.server == null || s.cardIndex == null || c.policySha == null || !c.policySha.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("policySearch needs server=host:port, cardIndex=<path> and policySha=<64 hex>");
            }
            if (s.seatTimeoutMs < 1) {
                throw new IllegalArgumentException("policySearch: seatTimeoutMs >= 1");
            }
            c.check();
            AiFixes.Mode.parse(c.departMedian);
            return s;
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
            h.addProperty("schema_sha", RlSchema.schemaSha());
            h.addProperty("card_index_sha", index.sha());
            h.addProperty("jar_sha", jarSha);
            h.addProperty("actor_id", actorId);
            h.addProperty("thread", Thread.currentThread().getName());
            h.addProperty("pid", ProcessHandle.current().pid());
            h.addProperty("mode", "search");
            final JsonObject ack = c.hello(h);
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
            o.addProperty("server", spec.search.server);
            o.addProperty("budgetMs", spec.search.budgetMs);
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
    public void bind(final Game g, final int seatIndex, final long seed, final LookaheadSearch.Config k8,
            final AiFixes.Mode k8AiFixes) {
        this.game = g;
        this.k8Config = k8;
        this.k8AiFixes = k8AiFixes == null ? AiFixes.Mode.OFF : k8AiFixes;
        final long uid = RlSearch.splitmix(seed ^ 0x6c697665L);
        final RlFeaturizer feat = new RlFeaturizer(index);
        feat.setVersion(1);
        feat.reset();
        final String[] ctl = new String[g.getRegisteredPlayers().size()];
        for (int s = 0; s < ctl.length; s++) {
            ctl[s] = s == seatIndex ? "rl:eval" : "forge";
        }
        final RlSeat.Config sc = new RlSeat.Config();
        sc.mode = "eval";
        sc.capActions = spec.capActions;
        sc.maxDecisions = spec.maxDecisions;
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
        know.attach();
        session.setKnowledgeObserver(know);
        feat.setKnowledge(know);
        rs.seenNames = know::opponentSeen;
        rs.onFault = this::degrade;
        final RlSearch s = new RlSearch(spec.search, seed, uid, index, know, jarSha, actorId);
        if (spec.log) {
            s.onSearched = this::searched;
        }
        rs.search = s;
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
        System.err.println("[lookahead-decision] " + d);
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
            }
            final long calls = seatClient.calls - seatCalls0;
            o.addProperty("seatCalls", calls);
            o.addProperty("seatMsMean", calls == 0 ? 0 : Math.round(seatClient.nanos / 1e5 / Math.max(1, seatClient.calls)) / 10.0);
            if (search != null) {
                o.add("search", search.summary());
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
            seatClient.close();
        }
    }
}
