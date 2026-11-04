package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

/**
 * Lane outcome-arm-1104 (frame-dump replay mode; off unless the bench config has {@code frameDump}): the cheap volume
 * path. A replayed (forced) tape game emits the deciding seat's v(o) frame at every priority decision of either seat and
 * at every forced choice ask the R3 labeller hooks, with NO label search: no solver, no proof check, no copy of the
 * game. The frame is the labeller's exactly: the same featurizer over the same seat-knowledge tracker, {@link
 * RlSearch#leafFrame}, the same numbering (dec_idx = priority decisions so far, both seats; choice_ord = the seat's
 * forced-choice ordinal of {@link forge.ai.AiTapeController}), computed under the same bench isolation. So a label row
 * and the dump row at the same (game_uid, seat, dec_idx[, choice_ord]) carry byte-identical payloads.
 *
 * <p>Rows are {@code mtgx-rl-labelsink/1} records ({@code b"MXL1" | u32le hlen | header JSON | u32le plen | DECIDE
 * payload}) of kind {@code value} with no target (target null, target_source none) and the tape result of the frame's
 * seat, so {@code tools/ml/rl/labels.py read_sink} reads them as label files. Nothing is written during the game; {@link
 * #finish} writes the game's rows only when its replay is digest-equal to the tape (else none, loudly).
 *
 * <p>Outputs ({@link Sink}, one of each per JVM): {@code frames-<pid>.mxl.gz} (one gzip member per game) and {@code
 * games-<pid>.jsonl} (one row per replayed game, with its row counts and frame time).
 */
public final class RlFrameDump implements PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-bvw-frames/1";

    /** Shared by the replay threads of one JVM. */
    public static final class Sink {
        private final Path frames, games;

        public Sink(final Path dir) throws IOException {
            Files.createDirectories(dir);
            final long pid = ProcessHandle.current().pid();
            this.frames = dir.resolve("frames-" + pid + ".mxl.gz");
            this.games = dir.resolve("games-" + pid + ".jsonl");
        }

        synchronized void write(final byte[] member, final JsonObject game) throws IOException {
            if (member != null) {
                try (OutputStream o = new FileOutputStream(frames.toFile(), true)) {
                    o.write(member);
                }
            }
            Files.write(games, (RlWire.canonicalString(game) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        public Path framesFile() {
            return frames;
        }

        public Path gamesFile() {
            return games;
        }
    }

    /** The probe methods whose forced answers are choice rows (the R3 labeller's choose-a-card hooks). */
    static final java.util.Set<String> CHOICE_METHODS = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(
            java.util.Arrays.asList("chooseSingleCardForZoneChange", "orderMoveToZoneList", "arrangeForScry",
                    "arrangeForSurveil", "chooseCardsToDiscardFrom", "tuckCardsViaMulligan")));

    private final int bandLo, bandHi;
    private final boolean choices;
    private final JsonObject tape;
    private final long uid;
    private final int tapeTurns;
    private final String jarSha;
    private final Game game;
    private final RlFeaturizer feat;
    private final List<byte[]> records = new ArrayList<>();
    private int decisions = 0, priorityRows = 0, choiceRows = 0;
    private double frameMs = 0;
    private final Map<String, Integer> byMethod = new TreeMap<>();

    /**
     * @param bandLo, bandHi turns before the end in [lo, hi) (hi &lt;= 0: no upper bound, every turn)
     * @param choices also the forced choice asks (installs an {@link forge.ai.AiTapeController} probe that only reads)
     */
    public RlFrameDump(final int bandLo, final int bandHi, final boolean choices, final JsonObject tapeLine,
            final String jarSha, final Game game, final RlKnowledge knowledge, final CardIndex index, final int obs) {
        RlReplayLabeller.checkTrainTape(tapeLine);
        if (bandLo < 0 || (bandHi > 0 && bandHi <= bandLo)) {
            throw new IllegalArgumentException("frameDump band [lo, hi): 0 <= lo, hi > lo or hi <= 0 (no bound), not ["
                    + bandLo + ", " + bandHi + ")");
        }
        this.bandLo = bandLo;
        this.bandHi = bandHi;
        this.choices = choices;
        this.tape = tapeLine;
        this.uid = RlWire.parseUid(tapeLine.get("game_uid").getAsString());
        this.tapeTurns = tapeLine.get("turns").getAsInt();
        this.jarSha = jarSha;
        this.game = game;
        this.feat = game == null ? null : new RlFeaturizer(index);
        if (feat != null) {
            feat.setVersion(obs);
            feat.reset();
            feat.setKnowledge(knowledge);
        }
        if (game != null && choices) {
            forge.ai.AiTapeController.PROBES.put(game, new forge.ai.AiTapeController.Probe() {
                @Override
                public Object divert(final forge.ai.AiTapeController c, final String method, final Object[] args,
                        final int ordinal) {
                    return NONE;
                }

                @Override
                public void seen(final forge.ai.AiTapeController c, final String method, final Object[] args,
                        final int ordinal, final Object value) {
                    if (CHOICE_METHODS.contains(method)) {
                        choiceFrame(c.getPlayer(), ordinal, method);
                    }
                }

                @Override
                public Object scripted(final forge.ai.AiTapeController c, final String method, final Object[] args) {
                    return NONE;
                }
            });
        }
    }

    private boolean inBand(final int tbe) {
        return tbe >= bandLo && (bandHi <= 0 || tbe < bandHi);
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
        if (seat < 0 || seat > 1 || !inBand(tbe)) {
            return;
        }
        final String phase = String.valueOf(g.getPhaseHandler().getPhase());
        final int stack = g.getStack().size();
        final String stateSha = TrajectoryRef.stateSha(g);
        final Object[] got = frame(g, p, seat, turn, ord);
        priorityRows++;
        row(seat, ord, turn, tbe, phase, stack, stateSha, (byte[]) got[0], (Integer) got[1], "priority", -1, null);
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /** A forced choice ask of a taped seat (after its recorded answer, as the labeller's choice rows). */
    private void choiceFrame(final Player p, final int ordinal, final String method) {
        final Game g = game;
        if (g == null || g.isGameOver()) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final int tbe = tapeTurns - turn;
        if (seat < 0 || seat > 1 || !inBand(tbe)) {
            return;
        }
        final String phase = String.valueOf(g.getPhaseHandler().getPhase());
        final int stack = g.getStack().size();
        final Object[] got = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
            final String stateSha = TrajectoryRef.stateSha(g);
            final Object[] f = frameIn(g, p, seat, turn, decisions);
            return new Object[] {f[0], f[1], stateSha};
        }));
        choiceRows++;
        byMethod.merge(method, 1, Integer::sum);
        row(seat, decisions, turn, tbe, phase, stack, (String) got[2], (byte[]) got[0], (Integer) got[1], "choice",
                ordinal, method);
    }

    /** The seat's own v(o) frame, under the bench isolation (as the labeller's priority rows). */
    private Object[] frame(final Game g, final Player p, final int seat, final int turn, final int decIdx) {
        return PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> frameIn(g, p, seat, turn, decIdx)));
    }

    private Object[] frameIn(final Game g, final Player p, final int seat, final int turn, final int decIdx) {
        final long f0 = System.nanoTime();
        final RlFeaturizer.Obs o = feat.observe(g, p, 0, false, null);
        final RlWire.Decide f = RlSearch.leafFrame(o, uid, seat, turn);
        f.decIdx = decIdx;
        if (f.hasPriv()) {
            throw new IllegalStateException("frame dump: a value frame with the privileged block");
        }
        final byte[] payload = RlWire.encodeDecide(f);
        frameMs += (System.nanoTime() - f0) / 1e6;
        return new Object[] {payload, f.C};
    }

    private void row(final int seat, final int ord, final int turn, final int tbe, final String phase, final int stack,
            final String stateSha, final byte[] payload, final int C, final String rowKind, final int choiceOrd,
            final String method) {
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
        h.addProperty("state_sha", stateSha);
        h.addProperty("row_kind", rowKind);
        if (choiceOrd >= 0) {
            h.addProperty("choice_ord", choiceOrd);
            h.addProperty("choice_method", method);
        }
        h.addProperty("family", RlSchema.F_PRIORITY);
        h.addProperty("C", C);
        h.addProperty("obs", feat.version());
        h.addProperty("turns_before_end", tbe);
        h.addProperty("tape_turns", tapeTurns);
        h.addProperty("tape_reason", tape.has("reason") && !tape.get("reason").isJsonNull()
                ? tape.get("reason").getAsString() : "none");
        h.addProperty("tape_result", tape.getAsJsonArray("result").get(seat).getAsInt());
        h.add("target", com.google.gson.JsonNull.INSTANCE);
        h.addProperty("target_source", "none");
        h.addProperty("jar_sha", jarSha);
        h.add("controllers", tape.get("controllers"));
        final JsonArray dp = new JsonArray(), ds = new JsonArray();
        for (com.google.gson.JsonElement d : tape.getAsJsonArray("decks")) {
            dp.add(d.getAsJsonObject().get("path"));
            ds.add(d.getAsJsonObject().get("sha"));
        }
        h.add("decks", dp);
        h.add("deck_sha", ds);
        records.add(RlReplayLabeller.record(h, payload));
    }

    /** The game is over and its digest checked: its rows only when {@code digestEqual}; the games row says which. */
    public int finish(final Sink sink, final boolean digestEqual, final JsonObject replayRow) throws IOException {
        if (game != null && choices) {
            forge.ai.AiTapeController.PROBES.remove(game);
        }
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
            System.err.println("[frame-dump] NOT EQUAL game " + Long.toUnsignedString(uid) + ": tape " + tape.get("digest")
                    + ", replay " + replayRow.get("digest_replay") + " -- its " + records.size()
                    + " frames are not written");
        }
        final JsonObject gr = new JsonObject();
        gr.addProperty("schema", SCHEMA);
        gr.addProperty("game_uid", Long.toUnsignedString(uid));
        gr.add("seed", tape.get("seed"));
        gr.add("result", tape.get("result"));
        gr.add("reason", tape.get("reason"));
        gr.addProperty("turns", tapeTurns);
        gr.add("digest_tape", tape.get("digest"));
        gr.add("digest_replay", replayRow.get("digest_replay"));
        gr.addProperty("equal", digestEqual);
        gr.addProperty("decisions", decisions);
        gr.addProperty("rows", digestEqual ? records.size() : 0);
        gr.addProperty("rows_void", digestEqual ? 0 : records.size());
        gr.addProperty("priority_rows", priorityRows);
        gr.addProperty("choice_rows", choiceRows);
        final JsonObject bm = new JsonObject();
        for (Map.Entry<String, Integer> e : byMethod.entrySet()) {
            bm.addProperty(e.getKey(), e.getValue());
        }
        gr.add("choice_by_method", bm);
        gr.addProperty("frame_ms", Math.round(frameMs));
        if (replayRow.has("cpu_ms")) {
            gr.add("cpu_ms", replayRow.get("cpu_ms"));
        }
        final JsonArray band = new JsonArray();
        band.add(bandLo);
        band.add(bandHi);
        gr.add("band", band);
        gr.addProperty("choices", choices);
        gr.addProperty("jar_sha", jarSha);
        if (sink != null) {
            sink.write(member, gr);
        }
        replayRow.addProperty("frame_rows", digestEqual ? records.size() : 0);
        replayRow.addProperty("frame_rows_void", digestEqual ? 0 : records.size());
        return digestEqual ? records.size() : 0;
    }
}
