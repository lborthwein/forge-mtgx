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
    /**
     * Lane bvw-step1-build-1024: rows with a search target (a {@code search} block): the step-0 row's fields plus the
     * band, the proof flag, the honest S-b search's value and the row's target ({@link StepSearch}).
     */
    public static final String SCHEMA_STEP = "mtgx-bvw-value/2";
    /**
     * bvw-step2-fork-1027 (S2T, a search block with a {@code target} variant): the /2 fields plus {@code s2t} (the
     * variant, every variant's value, OPR on the seat's first band row with a target, a smoke run's per-world pairs).
     */
    public static final String SCHEMA_STEP3 = "mtgx-bvw-value/3";
    static final byte[] MAGIC = {'M', 'X', 'L', '1'};

    /** Shared by the replay threads of one JVM. */
    public static final class Sink {
        private final Path labels, index, games;
        /**
         * Lane lki-fix-port-1027: set by the replay stall watchdog ({@code forge.bench.ReplayStallWatchdog}) when it
         * voids the unfinished games; a game that finishes after it writes nothing (its games row is the watchdog's
         * {@code void:"stall"} row).
         */
        private boolean sealed = false;
        /** Lane lki-fix-port-1027: the games rows written so far, by game uid (the watchdog reads them at the seal). */
        private final Map<String, JsonObject> written = new java.util.LinkedHashMap<>();

        public Sink(final Path dir) throws IOException {
            Files.createDirectories(dir);
            final long pid = ProcessHandle.current().pid();
            this.labels = dir.resolve("labels-" + pid + ".mxl.gz");
            this.index = dir.resolve("index-" + pid + ".jsonl");
            this.games = dir.resolve("games-" + pid + ".jsonl");
        }

        synchronized void write(final byte[] member, final List<JsonObject> idx, final JsonObject game)
                throws IOException {
            if (sealed) {
                System.err.println("[bvw-label] sink sealed by the stall watchdog: game " + game.get("game_uid")
                        + " finished after the seal; its rows are not written (its games row is void \"stall\")");
                return;
            }
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
            written.put(game.get("game_uid").getAsString(), game);
        }

        /**
         * Lane lki-fix-port-1027: the stall watchdog's seal. From now on {@link #write} writes nothing; returns the games
         * rows written before it (by game uid), so the watchdog voids exactly the games without one.
         */
        public synchronized Map<String, JsonObject> seal() {
            sealed = true;
            return new java.util.LinkedHashMap<>(written);
        }

        /** Lane lki-fix-port-1027: whether the stall watchdog sealed this sink (tests). */
        synchronized boolean sealed() {
            return sealed;
        }

        /** Lane lki-fix-port-1027: the watchdog's {@code void:"stall"} games row of an unfinished game (after the seal). */
        public synchronized void writeStallGame(final JsonObject game) throws IOException {
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
    /** bvw-step1-build-1024: the band of turns before the end, [bandLo, turnsWindow) (bandLo 0 = step 0's window). */
    private final int bandLo;
    /** bvw-step1-build-1024: the step-1 search (null = none, the step-0 labeller exactly), its block's sha. */
    private final StepSearch search;
    private final String searchSha;
    /** bvw-step2-fork-1027 (S2T; null = a step-1 block): the spec variant, and each seat's OPR row done in this game. */
    private final String variant;
    private final boolean[] oprDone = new boolean[2];
    private final int oprK;
    private final RlKnowledge knowledge;
    private final Map<String, Integer> targets = new TreeMap<>();
    private final Map<String, Integer> searchWhy = new TreeMap<>();
    private double searchMs = 0;
    /**
     * Review #9: the run's fatal search error (a pin the service does not match, or no service), or null. Set by any
     * game's labeller; the runner stops replaying and exits 2; the game it happened in writes no rows.
     */
    public static final java.util.concurrent.atomic.AtomicReference<String> FATAL = new java.util.concurrent.atomic.AtomicReference<>();
    private String fatal;
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
    /**
     * The seat/dec_idx swap fix (2026-10-25): with a step-0 reference (null = none), this replay's positions in the
     * step-0 window [0, referenceTurns) must equal the reference's, or the game writes no rows ({@link TrajectoryRef}).
     * It aligns step 0's window, not the band: band positions are this replay's own (review-align #3); every step row
     * carries {@code state_sha} for the checks that need content.
     */
    private TrajectoryRef.Recorder trajectory;

    /** Attach the step-0 reference positions of this game ({@code ref} null: none known, the game is void). */
    public void setReference(final java.util.TreeMap<Integer, String> ref, final int turns) {
        setReference(ref, turns, null);
    }

    /** As above, with the reference's provenance (file sha256s, window, games known) for the games row (review-align #2). */
    public void setReference(final java.util.TreeMap<Integer, String> ref, final int turns, final JsonObject provenance) {
        this.trajectory = new TrajectoryRef.Recorder(ref, turns, provenance);
    }

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
        this(spec, 0, turnsWindow, null, tapeLine, jarSha, game, knowledge, index, obs);
    }

    /**
     * bvw-step1-build-1024: the labeller of the band [{@code bandLo}, {@code bandHi}) of turns before the end; with a
     * {@code searchSpec}, every row also gets the honest search's value and a target ({@link StepSearch}).
     */
    public RlReplayLabeller(final HonestLabel.Spec spec, final int bandLo, final int bandHi, final StepSearch.Spec searchSpec,
            final JsonObject tapeLine, final String jarSha, final Game game, final RlKnowledge knowledge,
            final CardIndex index, final int obs) {
        spec.checkHonest();
        checkTrainTape(tapeLine);
        if (bandHi < 1 || bandLo < 0 || bandLo >= bandHi) {
            throw new IllegalArgumentException("label band [lo, hi): 0 <= lo < hi, hi >= 1, not [" + bandLo + ", " + bandHi
                    + ")");
        }
        if (searchSpec != null && !spec.counterBudgets()) {
            throw new IllegalArgumentException("refused: a step-1 search target needs the proof check on budget=counter too");
        }
        if (bandLo > 0 && searchSpec == null) {
            // review #14: a band is a step-k labeller; step-0 rows come only from the turns window
            throw new IllegalArgumentException("refused: a label band without a search block (step-0 rows use turns)");
        }
        if (searchSpec != null && (searchSpec.bandLo != bandLo || searchSpec.bandHi != bandHi)) {
            throw new IllegalArgumentException("refused: the search block's band is not the labeller's");
        }
        if (game != null) {
            // review #3: the game runs under the Forge AI settings a counter spec pins
            final String no = spec.runtimeRefusal(game.getAITimeout());
            if (no != null) {
                throw new IllegalArgumentException(no);
            }
        }
        this.spec = spec;
        this.turnsWindow = bandHi;
        this.bandLo = bandLo;
        this.knowledge = knowledge;
        this.search = searchSpec == null || game == null ? null
                : new StepSearch(searchSpec, index, obs, jarSha, "step1-" + Long.toUnsignedString(
                        RlWire.parseUid(tapeLine.get("game_uid").getAsString())));
        this.searchSha = searchSpec == null ? null : searchSpec.sha();
        this.variant = searchSpec == null ? null : searchSpec.variant;
        this.oprK = searchSpec == null ? 0 : searchSpec.oprK;
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

    /** Labelling is TRAIN-only even though ordinary replay also supports EVAL tapes. */
    public static void checkTrainTape(final JsonObject tape) {
        final String mode = tape.has("mode") && !tape.get("mode").isJsonNull()
                ? tape.get("mode").getAsString() : null;
        if (!"train".equals(mode) && !"record".equals(mode)) {
            throw new IllegalArgumentException("refused: value labels need a TRAIN tape (train/record), not " + mode);
        }
    }

    @Override
    public void beforeDecision(final Player p) {
        final Game g = game;
        final int ord = decisions++;
        if (g == null || g.isGameOver() || fatal != null || FATAL.get() != null) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final int tbe = tapeTurns - turn;
        if (trajectory != null) {
            trajectory.add(ord, seat, turn, tbe, String.valueOf(g.getPhaseHandler().getPhase()), g.getStack().size());
        }
        if (tbe < bandLo || tbe >= turnsWindow || seat < 0 || seat > 1) {
            return;
        }
        windowDecisions++;
        final String phase = String.valueOf(g.getPhaseHandler().getPhase());
        // review-align #3: the position's public-state hash (step rows only; read before anything else reads the game)
        final String stateSha = search == null ? null : TrajectoryRef.stateSha(g);
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
        // bvw-step1-build-1024: the search value, in its own isolation (a copy of the live game; never moves it)
        StepSearch.Result sr = null;
        if (search != null) {
            // bvw-step2-fork-1027 (S2T): this row may be the seat's OPR row; a proof already gives it a target
            final boolean oprWanted = variant != null && oprK > 0 && !oprDone[seat];
            final String plabel = ((HonestLabel.Result) got[1]).label;
            final boolean proofTarget = "win".equals(plabel) || "loss".equals(plabel);
            try {
                sr = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> variant == null
                        ? search.value(g, p, knowledge, uid, key)
                        : search.value(g, p, knowledge, uid, key, oprWanted, proofTarget)));
            } catch (StepSearch.PinError e) {
                // review #9: fatal for the run; this game writes no rows
                fatal = e.getMessage();
                FATAL.compareAndSet(null, "game " + Long.toUnsignedString(uid) + ": " + e.getMessage());
                System.err.println("[bvw-label] FATAL " + e.getMessage());
                return;
            }
            searchMs += sr.ms;
            searchWhy.merge(sr.why, 1, Integer::sum);
            if (sr.s2t != null && sr.s2t.oprRan) {
                oprDone[seat] = true;
            }
        }
        final byte[] payload = (byte[]) got[0];
        final HonestLabel.Result r = (HonestLabel.Result) got[1];
        frameMs += (Double) got[2];
        solveMs += r.ms;
        if (!"none".equals(r.check)) {
            solved++;
        }
        gates.merge(r.gate, 1, Integer::sum);
        row(seat, ord, turn, tbe, phase, g.getStack().size(), stateSha, payload, (Integer) got[3], r, sr);
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    private void row(final int seat, final int ord, final int turn, final int tbe, final String phase, final int stack,
            final String stateSha, final byte[] payload, final int C, final HonestLabel.Result r,
            final StepSearch.Result sr) {
        final JsonObject h = new JsonObject();
        h.addProperty("kind", "value");
        h.addProperty("schema", search == null ? SCHEMA : variant != null ? SCHEMA_STEP3 : SCHEMA_STEP);
        h.addProperty("split", "TRAIN");
        h.addProperty("game_uid", Long.toUnsignedString(uid));
        h.addProperty("seed", tape.get("seed").getAsLong());
        h.addProperty("seat", seat);
        h.addProperty("dec_idx", ord);
        h.addProperty("turn", turn);
        h.addProperty("phase", phase);
        h.addProperty("stack", stack);
        if (stateSha != null) {
            h.addProperty("state_sha", stateSha);
        }
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
        if (search != null) {
            stepFields(h, tbe, r, sr);
        }
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
                "line_steps", "turns_to_end", "ms", "error", "controllers", "decks", "deck_sha", "meter", "band", "proof",
                "target", "target_source", "search", "search_sha", "value_backend", "hidden_exile", "unsupported_proof",
                "state_sha", "s2t"}) {
            if (h.has(k)) {
                ir.add(k, h.get(k));
            }
        }
        idx.add(ir);
    }

    /**
     * bvw-step1-build-1024: a step row's target. A strict proof of the honest solver (all K worlds; step 0's label) is 1
     * (win) or 0 (loss) with its proof flag; otherwise S-b's proven solver line is 1; otherwise the honest search's value
     * of the candidate it plays; otherwise no target (null): a leaf fallback, an error, a position with no search.
     */
    private void stepFields(final JsonObject h, final int tbe, final HonestLabel.Result r, final StepSearch.Result sr) {
        final JsonArray band = new JsonArray();
        band.add(bandLo);
        band.add(turnsWindow);
        h.add("band", band);
        final StepTarget st = StepTarget.of(r.label, sr);
        h.add("proof", st.proof == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(st.proof));
        h.addProperty("target_source", st.source);
        h.add("target", StepSearch.Result.num(st.target));
        if (st.hiddenExile != null) {
            // OPEN-6 ruling: no target, the proof withheld (kept for the audit as unsupported_proof)
            h.add("hidden_exile", st.hiddenExile);
            if (st.unsupportedProof != null) {
                h.addProperty("unsupported_proof", st.unsupportedProof);
            }
        }
        targets.merge(st.source, 1, Integer::sum);
        h.add("search", sr == null ? com.google.gson.JsonNull.INSTANCE : sr.toJson());
        if (sr != null && sr.backend != null) {
            h.add("value_backend", sr.backend);
        }
        h.addProperty("search_sha", searchSha);
        if (variant != null) {
            // bvw-step2-fork-1027 (S2T): the variant, every variant's value, OPR, a smoke run's pairs
            h.add("s2t", sr != null && sr.s2t != null ? sr.s2t.toJson(variant) : new StepSearch.S2t().toJson(variant));
        }
    }

    /**
     * A step row's target (bvw-step1-build-1024). A strict proof of the honest solver (all K worlds; step 0's label) is
     * 1 (win) or 0 (loss) with its proof flag; otherwise S-b's proven solver line is 1; otherwise the honest search's
     * value of the candidate it plays; otherwise none. OPEN-6 ruling: at a position with a face-down exile the seat may
     * not look at (the search's copy, {@link HiddenExile}), nothing: target_source {@code unsupported-hidden-exile}, no
     * target, and a proof is withheld too ({@code unsupportedProof} keeps it for the audit).
     */
    static final class StepTarget {
        String proof;
        String source;
        double target = Double.NaN;
        JsonArray hiddenExile;
        String unsupportedProof;

        static StepTarget of(final String label, final StepSearch.Result sr) {
            final StepTarget t = new StepTarget();
            final String proof = "win".equals(label) || "loss".equals(label) ? label : null;
            if (sr != null && sr.hiddenExile != null && sr.hiddenExile.size() > 0) {
                t.source = HiddenExile.TARGET_SOURCE;
                t.hiddenExile = sr.hiddenExile;
                t.unsupportedProof = proof;
                return t;
            }
            t.proof = proof;
            if (proof != null) {
                t.source = "proof";
                t.target = "win".equals(proof) ? 1.0 : 0.0;
            } else if (sr != null && sr.hasTarget()) {
                t.source = sr.provenLine ? "search_proven_line" : "search";
                // bvw-step2-fork-1027: the spec variant's value for an S2T block (sr.v for step 1)
                t.target = sr.provenLine ? 1.0 : sr.targetValue();
            } else {
                t.source = "none";
            }
            return t;
        }
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
    public int finish(final Sink sink, final boolean digestEqual0, final JsonObject replayRow) throws IOException {
        // review #9: a game whose labelling hit a fatal search error writes no rows, whatever its digest; with a step-0
        // reference, nor does a game whose positions are not step 0's (the seat/dec_idx swap)
        final JsonObject traj = trajectory == null ? null
                : TrajectoryRef.compareLoudly(trajectory, Long.toUnsignedString(uid), "label");
        final boolean trajEqual = traj == null || traj.get("equal").getAsBoolean();
        final boolean digestEqual = digestEqual0 && fatal == null && trajEqual;
        if (digestEqual0 && !trajEqual) {
            System.err.println("[bvw-label] TRAJECTORY MISMATCH game " + Long.toUnsignedString(uid) + ": " + traj
                    + " -- its " + records.size() + " label rows are VOID (not written)");
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
        if (!digestEqual0) {
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
        gr.addProperty("equal", digestEqual0);
        if (traj != null) {
            gr.add("trajectory", traj);
            gr.addProperty("trajectory_equal", trajEqual);
        }
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
        if (search != null) {
            // bvw-step1-build-1024: the band, the search block and the targets by source
            gr.addProperty("schema", variant != null ? SCHEMA_STEP3 : SCHEMA_STEP);
            gr.addProperty("band_lo", bandLo);
            gr.addProperty("search_sha", searchSha);
            gr.addProperty("search_ms", Math.round(searchMs));
            final JsonObject tj = new JsonObject();
            for (Map.Entry<String, Integer> e : targets.entrySet()) tj.addProperty(e.getKey(), e.getValue());
            gr.add("targets", tj);
            // review #9: how every search ended (score_failed / error / leaf fallbacks show here per game)
            final JsonObject wj = new JsonObject();
            for (Map.Entry<String, Integer> e : searchWhy.entrySet()) wj.addProperty(e.getKey(), e.getValue());
            gr.add("search_why", wj);
            gr.addProperty("band_hi", turnsWindow);
            if (fatal != null) {
                gr.addProperty("fatal", fatal);
            }
        }
        if (sink != null) {
            sink.write(member, digestEqual ? idx : java.util.Collections.emptyList(), gr);
        }
        if (traj != null) {
            replayRow.addProperty("trajectory_equal", trajEqual);
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
