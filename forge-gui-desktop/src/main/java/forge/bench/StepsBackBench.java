package forge.bench;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.StepsBackReach;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;
import forge.game.Game;
import forge.game.GameAction;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane steps-back-1016 (bench only; diagnostic and curriculum tooling, nothing here plays a live seat): the seed
 * harvest and the position probe of the "steps back from the solver" ladder and reverse-curriculum generator.
 *
 * <p>Usage: {@code java ... forge.bench.StepsBackBench cfg.json} where cfg is an RlActorBench config plus
 * {@code "stepsBack": {"out": "rows.jsonl.gz", "jobTimeoutSec": 900, "jobs": [...]}}. Each job is one game played
 * through {@link RlActorBench#play} (decks, seed and controllers as a GAME message; the pilot seat must be a forced-win
 * solver seat, {@code forge:ForcedWin}), optionally from a Forge GameState frame installed at the start of its first
 * turn (the puzzle hook):</p>
 * <ul>
 * <li>{@code "kind": "harvest"}: the game is played out. Every time the pilot's solver TAKES a line (a proven win) the
 * position is dumped as a frame with the line and its pieces (card names and zones); the pilot's position at the start
 * of each of its main phases 1 is dumped too (the "a turn earlier" positions).</li>
 * <li>{@code "kind": "probe"}: at the pilot's first main-phase-1 decision (empty stack) the solver is asked whether it
 * proves a win from here ({@link ForcedWinSolver#prove}); when it does not and the job has a {@code "reach"} block,
 * {@link StepsBackReach} searches for a reach within the horizon. Then the game ends (a draw).</li>
 * </ul>
 * <p>One row per job. A job past {@code jobTimeoutSec} plus a grace is stopped (its row says "hung").</p>
 */
public final class StepsBackBench {
    private StepsBackBench() {
    }

    /** One job's live game state. */
    static final class Ctx {
        final JsonObject job;
        final String kind;
        final int pilot;
        Game game;
        int frameTurn = -1;
        boolean probed;
        final JsonArray takes = new JsonArray();
        final JsonArray turnFrames = new JsonArray();
        final Set<Integer> dumpedTurns = new HashSet<>();
        JsonObject probe;
        int maxTakes = 3;

        Ctx(JsonObject job) {
            this.job = job;
            this.kind = job.has("kind") ? job.get("kind").getAsString() : "probe";
            this.pilot = job.has("pilot") ? job.get("pilot").getAsInt() : 0;
        }
    }

    static final Map<Game, Ctx> CTX = new ConcurrentHashMap<>();

    static final BenchSession.KnowledgeObserver NOOP = new BenchSession.KnowledgeObserver() {
        @Override
        public void onReveal(final Game g, final Player v, final List<Card> cards, final ZoneType z, final Player owner) {
        }

        @Override
        public void onLook(final Game g, final Player v, final List<Card> cards, final ZoneType dest) {
        }
    };
    static final ThreadLocal<Ctx> CUR = new ThreadLocal<>();

    // ------------------------------------------------------------------ frames

    /** GameState applied synchronously on the calling (game) thread. */
    static final class NowState extends GameState {
        void applyNow(final Game g) {
            applyGameOnThread(g);
        }
    }

    /** GameAction whose startGame runs the frame hook when the caller passes none. */
    static final class HookedAction extends GameAction {
        private final Runnable hook;

        HookedAction(final Game g, final Runnable hook) {
            super(g);
            this.hook = hook;
        }

        @Override
        public void startGame(final GameOutcome last, final Runnable h) {
            super.startGame(last, h != null ? h : hook);
        }
    }

    static void install(final Game game, final String frameText, final int maxTurn) {
        try {
            final Field f = Game.class.getField("action");
            f.setAccessible(true);
            f.set(game, new HookedAction(game, () -> {
                final NowState gs = new NowState();
                gs.parse(Arrays.asList(frameText.split("\\R")));
                gs.applyNow(game);
            }));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("frame install: " + e, e);
        }
        if (maxTurn > 0) {
            game.subscribeToEvents(new Object() {
                @Subscribe
                public void on(final GameEventTurnBegan e) {
                    if (e.turnNumber() > maxTurn && !game.isGameOver()) {
                        game.setGameOver(GameEndReason.Draw);
                    }
                }
            });
        }
    }

    static int frameTurn(final String frameText) {
        for (String l : frameText.split("\\R")) {
            if (l.startsWith("turn=")) {
                return Integer.parseInt(l.substring(5).trim());
            }
        }
        return -1;
    }

    /** The position as a Forge GameState frame (Forge's own dumper). */
    static String dump(final Game g) {
        final GameState gs = new GameState();
        gs.initFromGame(g);
        return "[state]\n" + gs.toString();
    }

    static JsonArray names(final Iterable<Card> cards) {
        final JsonArray a = new JsonArray();
        for (Card c : cards) {
            a.add(c.getName());
        }
        return a;
    }

    static String zoneOf(final Game g, final Card c) {
        final ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
        return z == null ? "?" : z.name();
    }

    static JsonObject proofJson(final Game live, final ForcedWinSolver.Proof p) {
        final JsonObject o = new JsonObject();
        o.addProperty("line", p.line);
        o.addProperty("winTurn", p.winTurn);
        o.addProperty("pUnopposed", p.pUnopposed);
        o.addProperty("pBelief", p.pBelief);
        o.addProperty("engine", p.engine);
        final JsonArray steps = new JsonArray();
        p.steps.forEach(steps::add);
        o.add("steps", steps);
        final JsonArray pieces = new JsonArray();
        final Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < p.stepHostIds.size(); i++) {
            final int id = p.stepHostIds.get(i);
            if (!seen.add(id)) {
                continue;
            }
            final Card c = live.findById(id);
            final JsonObject pc = new JsonObject();
            pc.addProperty("id", id);
            pc.addProperty("name", c == null ? p.stepHostNames.get(i) : c.getName());
            pc.addProperty("zone", c == null ? "?" : zoneOf(live, c));
            pc.addProperty("token", c != null && c.isToken());
            pieces.add(pc);
        }
        o.add("pieces", pieces);
        final JsonArray lib = new JsonArray();
        for (int id : p.libraryPickIds) {
            final Card c = live.findById(id);
            lib.add(c == null ? "#" + id : c.getName());
        }
        o.add("libraryPicks", lib);
        return o;
    }

    static JsonObject position(final Game live, final Player me) {
        final JsonObject o = new JsonObject();
        o.addProperty("turn", live.getPhaseHandler().getTurn());
        o.addProperty("phase", String.valueOf(live.getPhaseHandler().getPhase()));
        o.addProperty("life", me.getLife());
        final Player opp = me.getOpponents().isEmpty() ? null : me.getOpponents().get(0);
        o.addProperty("oppLife", opp == null ? -1 : opp.getLife());
        o.add("hand", names(me.getCardsIn(ZoneType.Hand)));
        o.add("battlefield", names(me.getCardsIn(ZoneType.Battlefield)));
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        o.addProperty("library", lib.size());
        o.add("libraryTop", names(lib.subList(0, Math.min(5, lib.size()))));
        return o;
    }

    // ------------------------------------------------------------------ the solver hooks

    static final ForcedWinSolver.Hooks HOOKS = new ForcedWinSolver.Hooks() {
        @Override
        public boolean beforeDecide(final Game live, final Player me, final ForcedWinSolver solver) {
            final Ctx x = CTX.get(live);
            if (x == null || live.getRegisteredPlayers().indexOf(me) != x.pilot) {
                return false;
            }
            final PhaseHandler ph = live.getPhaseHandler();
            if (!live.getStack().isEmpty()) {
                return false;
            }
            if ("harvest".equals(x.kind)) {
                if (!ph.isPlayerTurn(me) || !ph.is(PhaseType.MAIN1)) {
                    return false;
                }
                if (x.takes.size() == 0 && x.dumpedTurns.add(ph.getTurn())) {
                    final JsonObject t = new JsonObject();
                    t.addProperty("turn", ph.getTurn());
                    t.add("pos", position(live, me));
                    t.addProperty("frame", dump(live));
                    x.turnFrames.add(t);
                    // keep the last 4 own turns only
                    while (x.turnFrames.size() > 4) {
                        x.turnFrames.remove(0);
                    }
                }
                return false;
            }
            if (x.probed || (x.frameTurn > 0 && ph.getTurn() < x.frameTurn)) {
                return false;
            }
            x.probed = true;
            x.probe = probe(x, live, me, solver);
            live.setGameOver(GameEndReason.Draw);
            return true;
        }

        @Override
        public void taken(final Game live, final Player me, final ForcedWinSolver.Proof proof) {
            final Ctx x = CTX.get(live);
            if (x == null || !"harvest".equals(x.kind) || live.getRegisteredPlayers().indexOf(me) != x.pilot
                    || x.takes.size() >= x.maxTakes) {
                return;
            }
            final JsonObject t = new JsonObject();
            t.addProperty("turn", live.getPhaseHandler().getTurn());
            t.addProperty("phase", String.valueOf(live.getPhaseHandler().getPhase()));
            t.addProperty("ourTurn", live.getPhaseHandler().isPlayerTurn(me));
            t.add("pos", position(live, me));
            t.add("proof", proofJson(live, proof));
            t.addProperty("frame", dump(live));
            x.takes.add(t);
        }
    };

    static JsonObject probe(final Ctx x, final Game live, final Player me, final ForcedWinSolver solver) {
        final JsonObject o = new JsonObject();
        o.add("pos", position(live, me));
        o.addProperty("frameOut", dump(live));
        final long t0 = System.nanoTime();
        final ForcedWinSolver.Proof p = solver.prove(live);
        o.addProperty("proveMs", (System.nanoTime() - t0) / 1_000_000);
        o.addProperty("proven", p != null);
        if (p != null) {
            o.add("proof", proofJson(live, p));
        }
        final JsonObject rj = x.job.has("reach") ? x.job.getAsJsonObject("reach") : null;
        if (rj != null && (p == null || rj.has("always") && rj.get("always").getAsBoolean())) {
            final StepsBackReach.Config rc = new StepsBackReach.Config();
            rc.solver = solver.config();
            if (rj.has("maxFixes")) rc.maxFixes = rj.get("maxFixes").getAsInt();
            if (rj.has("maxOwnTurns")) rc.maxOwnTurns = rj.get("maxOwnTurns").getAsInt();
            if (rj.has("nodes")) rc.nodes = rj.get("nodes").getAsInt();
            if (rj.has("budgetSec")) rc.budgetMs = rj.get("budgetSec").getAsLong() * 1000L;
            if (rj.has("extraPicks")) rc.extraPicks = rj.get("extraPicks").getAsInt();
            if (rj.has("seed")) rc.seed = rj.get("seed").getAsLong();
            if (rj.has("pieces")) {
                for (JsonElement e : rj.getAsJsonArray("pieces")) rc.pieces.add(e.getAsString());
            }
            final StepsBackReach.Result r = StepsBackReach.search(live, me, rc);
            final JsonObject rr = new JsonObject();
            rr.addProperty("reached", r.reached);
            rr.addProperty("why", r.why);
            rr.addProperty("label", r.label);
            final JsonArray path = new JsonArray();
            r.path.forEach(path::add);
            rr.add("path", path);
            final JsonArray cls = new JsonArray();
            r.classes.forEach(cls::add);
            rr.add("classes", cls);
            rr.addProperty("fixes", r.fixes);
            rr.addProperty("passes", r.passes);
            rr.addProperty("ownTurns", r.ownTurns);
            rr.addProperty("proofLine", r.proofLine);
            rr.addProperty("proofWinTurn", r.proofWinTurn);
            rr.addProperty("pUnopposed", r.pUnopposed);
            rr.addProperty("pBelief", r.pBelief);
            rr.addProperty("nodes", r.nodes);
            rr.addProperty("proofs", r.proofs);
            rr.addProperty("ms", r.ms);
            o.add("reach", rr);
        }
        return o;
    }

    // ------------------------------------------------------------------ main

    public static void main(final String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        final JsonObject o = JsonParser.parseString(Files.readString(Paths.get(args[0]))).getAsJsonObject();
        final RlActorBench.Cfg cfg = RlActorBench.parse(o);
        if (cfg.cardIndex == null) {
            cfg.cardIndex = cfg.rlRoot + "/data/card-index.tsv";
        }
        RlActorBench.ensureBooted(cfg.globalSeed);
        final CardIndex index = CardIndex.load(Paths.get(cfg.cardIndex));
        final String jarSha = RlActorBench.jarSha(cfg);
        final JsonObject sb = o.getAsJsonObject("stepsBack");
        final JsonArray jobs = sb.getAsJsonArray("jobs");
        final long jobTimeoutMs = (sb.has("jobTimeoutSec") ? sb.get("jobTimeoutSec").getAsLong() : 900L) * 1000L;
        final Path outP = Paths.get(sb.get("out").getAsString());
        Files.createDirectories(outP.getParent());
        ForcedWinSolver.HOOKS = HOOKS;
        RlActorBench.KNOWLEDGE_TAP = g -> {
            final Ctx x = CUR.get();
            if (x != null) {
                x.game = g;
                CTX.put(g, x);
                if (x.job.has("frame")) {
                    try {
                        final String text = Files.readString(Paths.get(x.job.get("frame").getAsString()));
                        x.frameTurn = frameTurn(text);
                        install(g, text, x.job.has("maxTurn") ? x.job.get("maxTurn").getAsInt() : 0);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return NOOP;
        };
        final PrintWriter w = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(
                new FileOutputStream(outP.toFile()), true), StandardCharsets.UTF_8));
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        final int n = jobs.size();
        final Map<Thread, long[]> started = new ConcurrentHashMap<>(); // thread -> {startMs, jobIndex}
        final List<Thread> ts = new ArrayList<>();
        final Runnable work = () -> {
            final RlFeaturizer feat = new RlFeaturizer(index);
            for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) {
                final JsonObject j = jobs.get(i).getAsJsonObject();
                final JsonObject row = runJob(cfg, j, feat, jarSha, started, i);
                synchronized (w) {
                    w.println(RlWire.canonicalString(row));
                    w.flush();
                }
                final int dn = done.incrementAndGet();
                System.err.println("[stepsback] " + dn + "/" + n + " " + j.get("id").getAsString() + " "
                        + (row.has("error") ? "error " + row.get("error").getAsString() : "ok"));
                if (row.has("hung")) {
                    return; // this thread was stopped: a fresh worker takes over
                }
            }
        };
        final int threads = Math.max(1, Math.min(cfg.threads, n));
        for (int k = 0; k < threads; k++) {
            final Thread t = new Thread(work, "stepsback-w" + k);
            ts.add(t);
            t.start();
        }
        int spawned = threads;
        while (true) {
            boolean alive = false;
            for (Thread t : new ArrayList<>(ts)) {
                if (t.isAlive()) {
                    alive = true;
                    continue;
                }
                ts.remove(t);
                if (next.get() < n) {
                    final Thread r = new Thread(work, "stepsback-w" + spawned++);
                    ts.add(r);
                    r.start();
                    alive = true;
                }
            }
            if (!alive) {
                break;
            }
            Thread.sleep(1000);
            final long now = System.currentTimeMillis();
            for (Map.Entry<Thread, long[]> e : started.entrySet()) {
                if (now - e.getValue()[0] > jobTimeoutMs + 120_000L) {
                    System.err.println("[stepsback] job " + e.getValue()[1] + " past its timeout: stopping " + e.getKey().getName());
                    started.remove(e.getKey());
                    stop(e.getKey());
                }
            }
        }
        w.close();
        System.err.println("[stepsback] done " + done.get() + "/" + n);
        System.exit(0);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static void stop(final Thread t) {
        try {
            t.stop();
        } catch (UnsupportedOperationException e) {
            System.err.println("[stepsback] cannot stop " + t.getName() + ": " + e);
        }
    }

    static JsonObject runJob(final RlActorBench.Cfg cfg, final JsonObject j, final RlFeaturizer feat, final String jarSha,
            final Map<Thread, long[]> started, final int index) {
        final JsonObject row = new JsonObject();
        row.add("id", j.get("id"));
        row.addProperty("kind", j.has("kind") ? j.get("kind").getAsString() : "probe");
        final Ctx x = new Ctx(j);
        CUR.set(x);
        started.put(Thread.currentThread(), new long[] {System.currentTimeMillis(), index});
        try {
            final JsonObject g = new JsonObject();
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("game").entrySet()) g.add(e.getKey(), e.getValue());
            final String mode = j.has("mode") ? j.get("mode").getAsString() : "eval";
            final RlSeat.Endpoint ep = new RlSeat.Endpoint() {
                @Override
                public RlWire.Decision decide(final byte[] p) throws java.io.IOException {
                    throw new java.io.IOException("no RL seat expected");
                }

                @Override
                public void record(final byte[] p) {
                }
            };
            row.add("seed", g.get("seed"));
            row.add("decks", g.get("decks"));
            row.add("controllers", g.get("controllers"));
            final RlActorBench.Played p = RlActorBench.play(cfg, mode, g, feat, ep, null, jarSha);
            if (p.guardError != null) {
                row.addProperty("error", "deck_guard: " + p.guardError);
            } else {
                for (String key : new String[] {"result", "void", "reason", "turns", "digest", "digest_lb", "cpu_ms", "wall_ms"}) {
                    if (p.end.has(key)) {
                        row.add(key, p.end.get(key));
                    }
                }
                if (x.game != null) {
                    int ws = -1;
                    if (x.game.getOutcome() != null && !x.game.getOutcome().isDraw()) {
                        for (int s = 0; s < 2; s++) {
                            final Player pl = x.game.getRegisteredPlayers().get(s);
                            if (pl.getOutcome() != null && pl.getOutcome().hasWon()) {
                                ws = s;
                            }
                        }
                    }
                    row.addProperty("winner", ws);
                    row.addProperty("lastTurn", x.game.getPhaseHandler().getTurn());
                }
            }
        } catch (ThreadDeath td) {
            row.addProperty("error", "hung: stopped past the job timeout");
            row.addProperty("hung", true);
        } catch (Throwable e) {
            row.addProperty("error", e.toString());
        } finally {
            started.remove(Thread.currentThread());
            CUR.remove();
            if (x.game != null) {
                CTX.remove(x.game);
            }
        }
        row.add("takes", x.takes);
        row.add("turnFrames", x.turnFrames);
        if (x.probe != null) {
            row.add("probe", x.probe);
        }
        return row;
    }
}
