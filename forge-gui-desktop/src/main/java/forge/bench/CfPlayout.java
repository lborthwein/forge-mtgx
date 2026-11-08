package forge.bench;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.bench.rl.RlWire;
import forge.game.Game;
import forge.game.GameAction;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameState;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * cf-labeller-1105 (moved into the repo by unified-jar-1106; a research entry point, nothing in the jar calls it): the PLAY-OUT stage
 * of the counterfactual choice labeller. Wraps {@link RlActorBench#main} (the live pilot jar, s1 eval mode, both seats on
 * the run's search spec): each game whose registered deck pair is a job key takes the next play-out its key's adaptive
 * scheduler hands out (a post-pick frame, one option of one recorded ask), installs that frame (Forge's puzzle hook, as
 * ComboPuzzle), with the unknown library positions of each seat shuffled by the play-out's own seed, and plays it to the
 * end or to the job's horizon turn (a draw past it). A game with nothing left to play is a draw at its first turn.
 *
 * <p>Config: the RlActorBench config plus {@code "cf": {"jobs": FILE, "outDir": DIR, "minN": 4, "maxN": 16, "z": 1.96,
 * "seed": S}}. FILE: {@code {"positions": [{"pos", "key": "<deck0>|<deck1>", "seat": asking seat (game seat = frame p
 * index), "maxTurn": 0 | last turn played, "options": [{"opt", "frame": PATH, "known": {"0": [library idx], "1": [..]},
 * "sidecar": null}]}]}}. A {@code sidecar} (the rl knowledge snapshot frame-score-1105 defines) is refused until this
 * harness restores it: every row says {@code install: "frame-only"} (frame text only: empty seat knowledge, no deck-list
 * memory, no per-turn history, no stack), so its labels are APPROXIMATE.
 *
 * <p>Adaptive N (per position): an option is stopped when it has n >= minN and its Wilson upper bound on the mean score
 * (win 1, draw/horizon 0.5, loss 0) is below the best option's Wilson lower bound, or at n = maxN; the best option stops
 * when every other option has stopped (or at maxN). Out: {@code <outDir>/cf-rows-<actorId>.jsonl} (one row per play-out)
 * and {@code cf-state-<actorId>.json} (per option counts, mean, CI, stop reason; rewritten after every finished game).
 */
public final class CfPlayout {
    private CfPlayout() {
    }

    /** GameState applied synchronously on the calling thread. */
    static final class NowState extends GameState {
        void applyNow(final Game g) {
            applyGameOnThread(g);
        }
    }

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

    static final class Opt {
        String pos;
        int opt;
        String frame;
        int[][] known = new int[2][];
        int started, n, wins, losses, draws;
        double score;
        String stopped;
        long turnsSum;
    }

    static final class Pos {
        String id, key;
        int seat, maxTurn, turn;
        /** Seats whose hidden cards (hand + library) are re-dealt from their own remaining list per play-out. */
        boolean[] redeal = new boolean[2];
        final List<Opt> opts = new ArrayList<>();
    }

    static final Map<String, List<Pos>> BY_KEY = new LinkedHashMap<>();
    static final List<Pos> ALL = new ArrayList<>();
    static int minN = 4, maxN = 16;
    static double z = 1.96;
    static long baseSeed = 0;
    static PrintWriter rows;
    static String statePath;

    /** Wilson interval of {@code s} successes in {@code n} (s may be fractional: draws count half). */
    static double[] wilson(final double s, final int n) {
        if (n == 0) {
            return new double[] {0, 1};
        }
        final double p = s / n, z2 = z * z, d = 1 + z2 / n;
        final double c = (p + z2 / (2 * n)) / d;
        final double h = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / d;
        return new double[] {Math.max(0, c - h), Math.min(1, c + h)};
    }

    static synchronized void restop(final Pos p) {
        Opt best = null;
        for (Opt o : p.opts) {
            if (o.n >= minN && (best == null || o.score / o.n > best.score / best.n)) {
                best = o;
            }
        }
        boolean othersDone = true;
        for (Opt o : p.opts) {
            if (o.stopped != null) {
                continue;
            }
            if (o.n >= maxN) {
                o.stopped = "maxN";
            } else if (best != null && o != best && o.n >= minN
                    && wilson(o.score, o.n)[1] < wilson(best.score, best.n)[0]) {
                o.stopped = "separated";
            }
            if (o != best && o.stopped == null) {
                othersDone = false;
            }
        }
        if (best != null && best.stopped == null && othersDone && p.opts.size() > 1) {
            best.stopped = "best";
        }
        if (best != null && best.stopped == null && p.opts.size() == 1 && best.n >= minN) {
            best.stopped = "single";
        }
    }

    /** The next play-out of a key: the active option with the fewest play-outs started (positions in file order). */
    static synchronized Object[] next(final String key) {
        final List<Pos> ps = BY_KEY.get(key);
        if (ps == null) {
            return null;
        }
        Opt pick = null;
        Pos pp = null;
        for (Pos p : ps) {
            for (Opt o : p.opts) {
                if (o.stopped != null || o.started >= maxN) {
                    continue;
                }
                if (pick == null || o.started < pick.started) {
                    pick = o;
                    pp = p;
                }
            }
            if (pick != null && pick.started < minN) {
                break; // finish a position's first minN before opening the next one's
            }
        }
        if (pick == null) {
            return null;
        }
        final int j = pick.started++;
        return new Object[] {pp, pick, j};
    }

    static long mix(final String pos, final int opt, final int j) {
        long h = baseSeed * 0x9E3779B97F4A7C15L;
        for (char c : pos.toCharArray()) {
            h = (h ^ c) * 0x100000001B3L;
        }
        h = (h ^ opt) * 0xBF58476D1CE4E5B9L;
        h = (h ^ j) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** The frame text with each seat's library shuffled except the indices that seat knows (kept in place). */
    static String shuffled(final String text, final Opt o, final long seed, final boolean[] redeal) {
        final Random r = new Random(seed);
        final String[] lines = text.split("\\R");
        // a re-dealt seat (never the asking one): its hand and library are one pool of hidden cards, shuffled, and the
        // hand dealt back at its size; its known library positions are not kept (the asking seat does not know them)
        for (int s = 0; s < 2; s++) {
            if (!redeal[s]) {
                continue;
            }
            int hi = -1, li = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("p" + s + "hand=")) {
                    hi = i;
                } else if (lines[i].startsWith("p" + s + "library=")) {
                    li = i;
                }
            }
            if (hi < 0 || li < 0) {
                continue;
            }
            final List<String> hand = split(lines[hi].substring(lines[hi].indexOf('=') + 1));
            final List<String> pool = new ArrayList<>(hand);
            pool.addAll(split(lines[li].substring(lines[li].indexOf('=') + 1)));
            Collections.shuffle(pool, r);
            lines[hi] = "p" + s + "hand=" + String.join(";", pool.subList(0, hand.size()));
            lines[li] = "p" + s + "library=" + String.join(";", pool.subList(hand.size(), pool.size()));
        }
        for (int i = 0; i < lines.length; i++) {
            for (int s = 0; s < 2; s++) {
                final String pre = "p" + s + "library=";
                if (!lines[i].startsWith(pre)) {
                    continue;
                }
                final String body = lines[i].substring(pre.length());
                if (body.isEmpty()) {
                    continue;
                }
                if (redeal[s]) {
                    continue;
                }
                final List<String> lib = new ArrayList<>(Arrays.asList(body.split(";")));
                final Set<Integer> keep = new HashSet<>();
                if (o.known[s] != null) {
                    for (int k : o.known[s]) {
                        keep.add(k);
                    }
                }
                final List<Integer> free = new ArrayList<>();
                final List<String> cards = new ArrayList<>();
                for (int k = 0; k < lib.size(); k++) {
                    if (!keep.contains(k)) {
                        free.add(k);
                        cards.add(lib.get(k));
                    }
                }
                Collections.shuffle(cards, r);
                for (int k = 0; k < free.size(); k++) {
                    lib.set(free.get(k), cards.get(k));
                }
                lines[i] = pre + String.join(";", lib);
            }
        }
        return String.join("\n", lines) + "\n";
    }

    static List<String> split(final String body) {
        final List<String> out = new ArrayList<>();
        for (String x : body.split(";")) {
            if (!x.isEmpty()) {
                out.add(x);
            }
        }
        return out;
    }

    static boolean roundTripAll = false, endAfterInstall = false;

    static void install(final Game game, final String frameText, final int maxTurn, final String[] endFrame,
            final String[][] roundTrip, final boolean endNow) {
        try {
            final Field f = Game.class.getField("action");
            f.setAccessible(true);
            f.set(game, new HookedAction(game, () -> {
                final NowState gs = new NowState();
                gs.parse(Arrays.asList(frameText.split("\\R")));
                gs.applyNow(game);
                if (roundTrip != null) {
                    // install-fidelity-1105: the frame read back from the installed game; lines that differ
                    final GameState back = new GameState();
                    back.initFromGame(game);
                    final java.util.Set<String> a = new java.util.TreeSet<>(Arrays.asList(frameText.split("\\R")));
                    final java.util.Set<String> b = new java.util.TreeSet<>(Arrays.asList(back.toString().split("\\R")));
                    final java.util.Set<String> lost = new java.util.TreeSet<>(a);
                    lost.removeAll(b);
                    final java.util.Set<String> added = new java.util.TreeSet<>(b);
                    added.removeAll(a);
                    roundTrip[0] = new String[] {String.join("\n", lost), String.join("\n", added)};
                }
                if (endNow) {
                    game.setGameOver(GameEndReason.Draw);
                }
            }));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cf install: " + e, e);
        }
        if (maxTurn > 0) {
            game.subscribeToEvents(new Object() {
                @Subscribe
                public void on(final GameEventTurnBegan e) {
                    if (e.turnNumber() > maxTurn && !game.isGameOver()) {
                        // the unresolved position at the horizon, for a v score next to the 0.5 draw label
                        try {
                            final GameState gs = new GameState();
                            gs.initFromGame(game);
                            endFrame[0] = gs.toString();
                        } catch (RuntimeException x) {
                            endFrame[0] = null;
                        }
                        game.setGameOver(GameEndReason.Draw);
                    }
                }
            });
        }
    }

    static String deckName(final Player p) {
        try {
            return p.getRegisteredPlayer().getDeck().getName();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    static synchronized void writeState() {
        final JsonArray a = new JsonArray();
        for (Pos p : ALL) {
            final JsonObject j = new JsonObject();
            j.addProperty("pos", p.id);
            j.addProperty("seat", p.seat);
            final JsonArray os = new JsonArray();
            for (Opt o : p.opts) {
                final JsonObject x = new JsonObject();
                x.addProperty("opt", o.opt);
                x.addProperty("frame", o.frame);
                x.addProperty("n", o.n);
                x.addProperty("wins", o.wins);
                x.addProperty("losses", o.losses);
                x.addProperty("draws", o.draws);
                x.addProperty("mean", o.n == 0 ? null : o.score / o.n);
                final double[] ci = wilson(o.score, o.n);
                final JsonArray c = new JsonArray();
                c.add(ci[0]);
                c.add(ci[1]);
                x.add("ci", c);
                x.addProperty("mean_turns", o.n == 0 ? null : (double) o.turnsSum / o.n);
                x.addProperty("stopped", o.stopped);
                os.add(x);
            }
            j.add("options", os);
            a.add(j);
        }
        final JsonObject root = new JsonObject();
        root.addProperty("schema", "mtgx-cf-state/1");
        root.addProperty("install", "frame-only");
        root.addProperty("minN", minN);
        root.addProperty("maxN", maxN);
        root.addProperty("z", z);
        root.add("positions", a);
        try {
            Files.writeString(Paths.get(statePath + ".tmp"), RlWire.canonicalString(root));
            Files.move(Paths.get(statePath + ".tmp"), Paths.get(statePath),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            System.err.println("[cf] state write: " + e);
        }
    }

    public static void main(final String[] args) throws Exception {
        final JsonObject o = JsonParser.parseString(Files.readString(Paths.get(args[0]))).getAsJsonObject();
        final JsonObject cf = o.getAsJsonObject("cf");
        if (cf.has("minN")) {
            minN = cf.get("minN").getAsInt();
        }
        if (cf.has("maxN")) {
            maxN = cf.get("maxN").getAsInt();
        }
        if (cf.has("z")) {
            z = cf.get("z").getAsDouble();
        }
        if (cf.has("roundTrip")) {
            roundTripAll = cf.get("roundTrip").getAsBoolean(); // install-fidelity-1105 check (off by default)
        }
        if (cf.has("endAfterInstall")) {
            endAfterInstall = cf.get("endAfterInstall").getAsBoolean(); // the round-trip pass: no play
        }
        if (cf.has("seed")) {
            baseSeed = cf.get("seed").getAsLong();
        }
        final JsonObject jobs = JsonParser.parseString(Files.readString(Paths.get(cf.get("jobs").getAsString())))
                .getAsJsonObject();
        final Map<String, String> frameText = new HashMap<>();
        for (JsonElement pe : jobs.getAsJsonArray("positions")) {
            final JsonObject pj = pe.getAsJsonObject();
            final Pos p = new Pos();
            p.id = pj.get("pos").getAsString();
            p.key = pj.get("key").getAsString();
            p.seat = pj.get("seat").getAsInt();
            p.maxTurn = pj.has("maxTurn") ? pj.get("maxTurn").getAsInt() : 0;
            p.turn = pj.has("turn") ? pj.get("turn").getAsInt() : -1;
            if (pj.has("redeal")) {
                for (JsonElement e : pj.getAsJsonArray("redeal")) {
                    p.redeal[e.getAsInt()] = true;
                }
            }
            for (JsonElement oe : pj.getAsJsonArray("options")) {
                final JsonObject oj = oe.getAsJsonObject();
                if (oj.has("sidecar") && !oj.get("sidecar").isJsonNull()) {
                    throw new IllegalArgumentException("cf: sidecar restore is not implemented (pos " + p.id + ")");
                }
                final Opt x = new Opt();
                x.pos = p.id;
                x.opt = oj.get("opt").getAsInt();
                x.frame = oj.get("frame").getAsString();
                frameText.put(x.frame, Files.readString(Paths.get(x.frame)));
                if (oj.has("known")) {
                    final JsonObject k = oj.getAsJsonObject("known");
                    for (int s = 0; s < 2; s++) {
                        if (k.has(String.valueOf(s))) {
                            final JsonArray ka = k.getAsJsonArray(String.valueOf(s));
                            x.known[s] = new int[ka.size()];
                            for (int i = 0; i < ka.size(); i++) {
                                x.known[s][i] = ka.get(i).getAsInt();
                            }
                        }
                    }
                }
                p.opts.add(x);
            }
            ALL.add(p);
            BY_KEY.computeIfAbsent(p.key, q -> new ArrayList<>()).add(p);
        }
        final String aid = o.has("actorId") ? o.get("actorId").getAsString() : "a";
        final String outDir = cf.get("outDir").getAsString();
        Files.createDirectories(Paths.get(outDir));
        rows = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
                Paths.get(outDir, "cf-rows-" + aid.replace('/', '_') + ".jsonl").toString(), true), StandardCharsets.UTF_8));
        statePath = Paths.get(outDir, "cf-state-" + aid.replace('/', '_') + ".json").toString();
        final BenchSession.KnowledgeObserver noop = new BenchSession.KnowledgeObserver() {
            @Override
            public void onReveal(final Game g, final Player v, final List<forge.game.card.Card> cards,
                    final forge.game.zone.ZoneType zn, final Player owner) {
            }

            @Override
            public void onLook(final Game g, final Player v, final List<forge.game.card.Card> cards,
                    final forge.game.zone.ZoneType dest) {
            }
        };
        RlActorBench.KNOWLEDGE_TAP = g -> {
            final String key = deckName(g.getRegisteredPlayers().get(0)) + "|" + deckName(g.getRegisteredPlayers().get(1));
            final Object[] job = next(key);
            if (job == null) {
                g.subscribeToEvents(new Object() {
                    @Subscribe
                    public void on(final GameEventTurnBegan e) {
                        if (!g.isGameOver()) {
                            g.setGameOver(GameEndReason.Draw);
                        }
                    }
                });
                return noop;
            }
            final Pos p = (Pos) job[0];
            final Opt x = (Opt) job[1];
            final int j = (Integer) job[2];
            final long seed = mix(p.id, x.opt, j);
            final long t0 = System.nanoTime();
            final String[] endFrame = new String[1];
            final String[][] rt = roundTripAll ? new String[1][] : null;
            install(g, shuffled(frameText.get(x.frame), x, seed, p.redeal), p.maxTurn, endFrame, rt, endAfterInstall);
            // the frame's own turn (the game's first turn event fires before the install sets it)
            final int[] startTurn = {p.turn};
            g.subscribeToEvents(new Object() {
                @Subscribe
                public void on(final GameEventGameFinished e) {
                    int ws = -1;
                    if (g.getOutcome() != null && !g.getOutcome().isDraw()) {
                        for (int s = 0; s < 2; s++) {
                            if (g.getRegisteredPlayers().get(s).getOutcome() != null
                                    && g.getRegisteredPlayers().get(s).getOutcome().hasWon()) {
                                ws = s;
                            }
                        }
                    }
                    final String why = RlActorBench.lossReason(g);
                    if ("draw".equals(why)) {
                        ws = -1; // a horizon or engine draw (Forge may still name a "winner" on setGameOver(Draw))
                    }
                    final int turns = g.getPhaseHandler().getTurn();
                    final double sc = ws == p.seat ? 1.0 : ws < 0 ? 0.5 : 0.0;
                    final JsonObject row = new JsonObject();
                    row.addProperty("schema", "mtgx-cf-row/1");
                    row.addProperty("pos", p.id);
                    row.addProperty("opt", x.opt);
                    row.addProperty("j", j);
                    row.addProperty("shuffle_seed", Long.toUnsignedString(seed));
                    row.addProperty("frame", x.frame);
                    row.addProperty("install", "frame-only");
                    row.addProperty("seat", p.seat);
                    row.addProperty("winner", ws);
                    row.addProperty("score", sc);
                    row.addProperty("reason", why);
                    row.addProperty("start_turn", startTurn[0]);
                    row.addProperty("end_turn", turns);
                    row.addProperty("horizon", p.maxTurn);
                    row.addProperty("wall_s", (System.nanoTime() - t0) / 1e9);
                    if (rt != null && rt[0] != null) {
                        row.addProperty("rt_lost", rt[0][0]);   // installed frame lines the game does not give back
                        row.addProperty("rt_added", rt[0][1]);  // lines the game gives back that the frame lacked
                    }
                    if (endFrame[0] != null) {
                        row.addProperty("frame_end", endFrame[0]); // the horizon position (frame text)
                    }
                    final Object[] run = RlActorBench.RUNNING.get(Thread.currentThread());
                    if (run != null) {
                        row.addProperty("game_uid", Long.toUnsignedString((Long) run[2]));
                    }
                    synchronized (CfPlayout.class) {
                        x.n++;
                        x.score += sc;
                        if (sc == 1.0) {
                            x.wins++;
                        } else if (sc == 0.0) {
                            x.losses++;
                        } else {
                            x.draws++;
                        }
                        x.turnsSum += Math.max(0, turns - startTurn[0]);
                        restop(p);
                        rows.println(RlWire.canonicalString(row));
                        rows.flush();
                    }
                    writeState();
                }
            });
            return noop;
        };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (CfPlayout.class) {
                rows.close();
            }
            writeState();
        }));
        RlActorBench.main(args);
    }
}
