package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.HonestLabel;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

/**
 * Lane bvw-plr-1023 (GOAL rev 8, BVW prerequisite P-LR): label during replay. A {@link PriorityDecisionListener} of ONE
 * replayed tape game ({@code Game#addPriorityDecisionListener}; nothing is added without a label spec). At every priority
 * decision of either seat (Forge seat or recorder alike) within the last {@code T} turns of the game (turns before the
 * end = the tape's {@code turns} minus the decision's turn, in {@code [0, T)}), just before the seat's controller
 * decides, it labels the position with {@link HonestLabel} (the honest solver on copies of the live game) and keeps one
 * row: the seat's own observation as a v(o) frame ({@link RlSearch#leafFrame}: a PRIORITY DECIDE payload, PASS the only
 * candidate, never a privileged block; the frame the search's value leaf scores) and the label. Everything it does runs
 * under the bench isolation (scratch random stream, fresh id scope, AI caches and card memory put back), with its own
 * featurizer over the game's seat-knowledge tracker.
 *
 * <p>Nothing is written during the game. {@link #finish} writes the game's rows only when its replayed digest equals the
 * tape's; a mismatch writes none and is reported loudly (the labels of a game that the labelling moved are void).
 *
 * <p>Outputs ({@link Sink}, one file of each per JVM): {@code labels-<pid>.mxl.gz}, one gzip member per game of
 * {@code mtgx-rl-labelsink/1} records ({@code b"MXL1" | u32le hlen | header JSON | u32le plen | DECIDE payload}) of kind
 * {@code value} ({@code tools/ml/rl/labels.py} packs them into {@code mtgx-rl-labels/2} value rows);
 * {@code index-<pid>.jsonl}, one row per labelled position; {@code games-<pid>.jsonl}, one row per replayed game.
 */
public final class RlReplayLabeller implements PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-bvw-value/1";
    static final byte[] MAGIC = {'M', 'X', 'L', '1'};

    /** Shared by the replay threads of one JVM. */
    public static final class Sink {
        private final Path labels, index, games;

        public Sink(final Path dir) throws IOException {
            Files.createDirectories(dir);
            final long pid = ProcessHandle.current().pid();
            this.labels = dir.resolve("labels-" + pid + ".mxl.gz");
            this.index = dir.resolve("index-" + pid + ".jsonl");
            this.games = dir.resolve("games-" + pid + ".jsonl");
        }

        synchronized void write(final byte[] member, final List<JsonObject> idx, final JsonObject game)
                throws IOException {
            if (member != null) {
                try (OutputStream o = new FileOutputStream(labels.toFile(), true)) {
                    o.write(member);
                }
            }
            if (!idx.isEmpty()) {
                final StringBuilder sb = new StringBuilder();
                for (JsonObject r : idx) {
                    sb.append(RlWire.canonicalString(r)).append('\n');
                }
                Files.write(index, sb.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
            Files.write(games, (RlWire.canonicalString(game) + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }

        public Path labelsFile() {
            return labels;
        }

        public Path indexFile() {
            return index;
        }

        public Path gamesFile() {
            return games;
        }
    }

    private final HonestLabel.Spec spec;
    private final int turnsWindow;
    private final JsonObject tape;
    private final long uid;
    private final int tapeTurns;
    private final String specSha;
    private final String jarSha;
    private final Game game;
    private final RlFeaturizer feat;
    private final HonestLabel.Seat[] seats = new HonestLabel.Seat[2];
    private final List<byte[]> records = new ArrayList<>();
    private final List<JsonObject> idx = new ArrayList<>();
    /** Priority decisions seen (both seats, from the start), those in the window, and those a solver check ran at. */
    private int decisions = 0, windowDecisions = 0, solved = 0;
    private double solveMs = 0, frameMs = 0;
    private final Map<String, Integer> gates = new TreeMap<>();
    private final Map<String, Integer> labels = new TreeMap<>();
    /** Tests: this game's rows (headers), in order. */
    public final List<JsonObject> headers = new ArrayList<>();
    /**
     * Tests only (null by default): runs inside every position's solve with the LIVE game; a probe that moves the game
     * (the identity test's mutant) must void the game's labels through the digest check.
     */
    public static volatile java.util.function.Consumer<Game> TEST_PROBE = null;

    /**
     * @param knowledge the game's seat-knowledge tracker (each seat's observations; read only), {@code obs} the tape's
     *     observation schema (1 or 2)
     */
    public RlReplayLabeller(final HonestLabel.Spec spec, final int turnsWindow, final JsonObject tapeLine,
            final String jarSha, final Game game, final RlKnowledge knowledge, final CardIndex index, final int obs) {
        spec.checkHonest();
        if (turnsWindow < 1) {
            throw new IllegalArgumentException("label turns >= 1, not " + turnsWindow);
        }
        this.spec = spec;
        this.turnsWindow = turnsWindow;
        this.tape = tapeLine;
        this.uid = RlWire.parseUid(tapeLine.get("game_uid").getAsString());
        this.tapeTurns = tapeLine.get("turns").getAsInt();
        this.specSha = spec.sha();
        this.jarSha = jarSha;
        this.game = game;
        this.feat = game == null ? null : new RlFeaturizer(index);
        if (feat != null) {
            feat.setVersion(obs);
            feat.reset();
            feat.setKnowledge(knowledge);
        }
    }

    @Override
    public void beforeDecision(final Player p) {
        final Game g = game;
        final int ord = decisions++;
        if (g == null || g.isGameOver()) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final int tbe = tapeTurns - turn;
        if (tbe < 0 || tbe >= turnsWindow || seat < 0 || seat > 1) {
            return;
        }
        windowDecisions++;
        final String phase = String.valueOf(g.getPhaseHandler().getPhase());
        final long key = RlSearch.splitmix(uid ^ (0x6276774c4142454cL + 2L * ord + seat));
        final Object[] got = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
            // the seat's own observation (its knowledge only), before anything else reads the game
            final long f0 = System.nanoTime();
            final RlFeaturizer.Obs o = feat.observe(g, p, 0, false, null);
            final RlWire.Decide f = RlSearch.leafFrame(o, uid, seat, turn);
            f.decIdx = ord;
            if (f.hasPriv()) {
                throw new IllegalStateException("bvw label: a value frame with the privileged block");
            }
            final byte[] payload = RlWire.encodeDecide(f);
            final double fms = (System.nanoTime() - f0) / 1e6;
            if (seats[seat] == null) {
                seats[seat] = new HonestLabel.Seat(spec);
            }
            final java.util.function.Consumer<Game> probe = TEST_PROBE;
            if (probe != null) {
                probe.accept(g);
            }
            return new Object[] {payload, seats[seat].label(g, p, key), fms, f.C};
        }));
        final byte[] payload = (byte[]) got[0];
        final HonestLabel.Result r = (HonestLabel.Result) got[1];
        frameMs += (Double) got[2];
        solveMs += r.ms;
        if (!"none".equals(r.check)) {
            solved++;
        }
        gates.merge(r.gate, 1, Integer::sum);
        row(seat, ord, turn, tbe, phase, g.getStack().size(), payload, (Integer) got[3], r);
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    private void row(final int seat, final int ord, final int turn, final int tbe, final String phase, final int stack,
            final byte[] payload, final int C, final HonestLabel.Result r) {
        final JsonObject h = new JsonObject();
        h.addProperty("kind", "value");
        h.addProperty("schema", SCHEMA);
        h.addProperty("split", "TRAIN");
        h.addProperty("game_uid", Long.toUnsignedString(uid));
        h.addProperty("seed", tape.get("seed").getAsLong());
        h.addProperty("seat", seat);
        h.addProperty("dec_idx", ord);
        h.addProperty("turn", turn);
        h.addProperty("phase", phase);
        h.addProperty("stack", stack);
        h.addProperty("family", RlSchema.F_PRIORITY);
        h.addProperty("C", C);
        h.addProperty("obs", feat == null ? 1 : feat.version());
        final JsonArray prior = new JsonArray();
        for (int c = 0; c < C; c++) {
            prior.add(1.0 / C);
        }
        h.add("prior", prior);
        h.addProperty("turns_before_end", tbe);
        h.addProperty("tape_turns", tapeTurns);
        h.addProperty("tape_reason", tape.has("reason") ? tape.get("reason").getAsString() : "none");
        h.addProperty("tape_result", tape.getAsJsonArray("result").get(seat).getAsInt());
        final JsonObject lj = r.toJson();
        for (Map.Entry<String, com.google.gson.JsonElement> e : lj.entrySet()) {
            h.add(e.getKey(), e.getValue());
        }
        h.addProperty("spec_sha", specSha);
        h.addProperty("jar_sha", jarSha);
        h.add("controllers", tape.get("controllers"));
        final JsonArray dp = new JsonArray(), ds = new JsonArray();
        for (com.google.gson.JsonElement d : tape.getAsJsonArray("decks")) {
            dp.add(d.getAsJsonObject().get("path"));
            ds.add(d.getAsJsonObject().get("sha"));
        }
        h.add("decks", dp);
        h.add("deck_sha", ds);
        records.add(record(h, payload));
        headers.add(h);
        labels.merge(r.label, 1, Integer::sum);
        final JsonObject ir = new JsonObject();
        for (String k : new String[] {"game_uid", "seed", "seat", "dec_idx", "turn", "phase", "stack",
                "turns_before_end", "tape_turns", "tape_reason", "tape_result", "label", "check", "gate", "p_win",
                "p_loss", "worlds_win", "worlds_loss", "lost_worlds", "proposals", "how", "end_reason", "line_how",
                "line_steps", "turns_to_end", "ms", "error", "controllers", "decks", "deck_sha"}) {
            if (h.has(k)) {
                ir.add(k, h.get(k));
            }
        }
        idx.add(ir);
    }

    static byte[] record(final JsonObject header, final byte[] payload) {
        final byte[] h = RlWire.canonicalString(header).getBytes(StandardCharsets.UTF_8);
        final ByteBuffer b = ByteBuffer.allocate(4 + 4 + h.length + 4 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(MAGIC).putInt(h.length).put(h).putInt(payload.length).put(payload);
        return b.array();
    }

    /**
     * The game is over and its digest checked: write its rows when {@code digestEqual}, none otherwise (loudly). The
     * games row says which. Returns the number of rows written.
     */
    public int finish(final Sink sink, final boolean digestEqual, final JsonObject replayRow) throws IOException {
        byte[] member = null;
        if (digestEqual && !records.isEmpty()) {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                for (byte[] r : records) {
                    gz.write(r);
                }
            }
            member = bo.toByteArray();
        }
        if (!digestEqual) {
            System.err.println("[bvw-label] DIGEST MISMATCH game " + Long.toUnsignedString(uid) + ": tape "
                    + tape.get("digest") + ", replay " + replayRow.get("digest_replay") + " -- its " + records.size()
                    + " label rows are VOID (not written)");
        }
        final JsonObject gr = new JsonObject();
        gr.addProperty("schema", SCHEMA);
        gr.addProperty("split", "TRAIN");
        gr.addProperty("game_uid", Long.toUnsignedString(uid));
        gr.add("seed", tape.get("seed"));
        gr.add("controllers", tape.get("controllers"));
        gr.add("decks", tape.get("decks"));
        gr.add("result", tape.get("result"));
        gr.add("void", tape.get("void"));
        gr.add("reason", tape.get("reason"));
        gr.addProperty("turns", tapeTurns);
        if (tape.has("fair_naming")) {
            gr.add("fair_naming", tape.get("fair_naming"));
        }
        gr.add("digest_tape", tape.get("digest"));
        gr.add("digest_replay", replayRow.get("digest_replay"));
        gr.addProperty("equal", digestEqual);
        gr.addProperty("decisions", decisions);
        gr.addProperty("window_decisions", windowDecisions);
        gr.addProperty("rows", digestEqual ? records.size() : 0);
        gr.addProperty("rows_void", digestEqual ? 0 : records.size());
        gr.addProperty("solved", solved);
        gr.addProperty("solve_ms", Math.round(solveMs));
        gr.addProperty("frame_ms", Math.round(frameMs));
        final JsonObject gj = new JsonObject();
        for (Map.Entry<String, Integer> e : gates.entrySet()) gj.addProperty(e.getKey(), e.getValue());
        gr.add("gates", gj);
        final JsonObject lj = new JsonObject();
        for (Map.Entry<String, Integer> e : labels.entrySet()) lj.addProperty(e.getKey(), e.getValue());
        gr.add("labels", lj);
        gr.addProperty("turns_window", turnsWindow);
        gr.addProperty("spec_sha", specSha);
        gr.addProperty("jar_sha", jarSha);
        if (sink != null) {
            sink.write(member, digestEqual ? idx : java.util.Collections.emptyList(), gr);
        }
        replayRow.addProperty("label_rows", digestEqual ? records.size() : 0);
        replayRow.addProperty("label_rows_void", digestEqual ? 0 : records.size());
        replayRow.addProperty("label_solve_ms", Math.round(solveMs));
        return digestEqual ? records.size() : 0;
    }

    public int windowDecisions() {
        return windowDecisions;
    }

    public double solveMs() {
        return solveMs;
    }
}
