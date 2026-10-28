package forge.bench;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiTape;
import forge.ai.AiTapeController;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.PlayoutKeys;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane bvw-decision-log-1027 (owner, 2026-10-21 and 2026-10-27: "record all moves by both players"): a research game's
 * DECISION LOG, the AI decision tape of fork #110 ({@link AiTape}, {@link AiTapeController}) on both seats of an
 * {@link RlActorBench} game.
 *
 * <ul>
 *   <li>RECORD (the generator, {@code "decisionLog": "record"}; or a replay that re-runs a tape's seats and logs them,
 *   {@code "decisionLog": "record"} in replay mode): every decision of both Forge seats (targets, modes, X, payments,
 *   mana sources, orderings, attacks, blocks, discards, mulligans) is taped as it is made. The game's tape line carries
 *   it under {@link #KEY}: nothing else on the line changes (the RNG is {@link PlayoutKeys.TrackedRandom}, the same
 *   stream as {@code new Random(seed)}; the controller swap never decides).</li>
 *   <li>FORCED (a replay of a tape that carries a log, the default): both seats answer every decision from the log and
 *   nothing searches or deliberates. A recorded answer that does not fit the game is a DIVERGENCE: the game stops there
 *   and is void ({@link #VOID}), never re-searched.</li>
 * </ul>
 *
 * Off (every existing run, and every tape without a log): nothing is created and nothing changes.
 */
final class DecisionLog {
    /** The tape line's key: {@code {"format", "entries": [top-level entries, seat-tagged], "end": [status lines], ...}}. */
    static final String KEY = "decision_log";
    /** The void reason of a forced replay that diverged from its log. */
    static final String VOID = "decision_log_divergence";
    /** Review #124 S2-4: the void reason of a game a forced-only replay ({@code "decisionLog": "forced"}) has no log for. */
    static final String MISSING = "decision_log_missing";

    enum Mode { RECORD, FORCED }

    final Mode mode;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
    private final PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
    private final List<AiTape> tapes = new ArrayList<>();
    private final List<Integer> seats = new ArrayList<>();
    private final JsonObject log;
    private long nanos;
    private boolean finished;
    private List<JsonObject> lines;

    private DecisionLog(final Mode mode, final JsonObject log) {
        this.mode = mode;
        this.log = log;
    }

    static DecisionLog record() {
        return new DecisionLog(Mode.RECORD, null);
    }

    /** A forced replay of {@code log} (a tape line's {@link #KEY} object). */
    static DecisionLog forced(final JsonObject log) {
        if (log == null || !log.has("entries") || !log.has("format")
                || log.get("format").getAsInt() != AiTape.FORMAT) {
            throw new IllegalArgumentException("decision log: not an AI tape of format " + AiTape.FORMAT);
        }
        return new DecisionLog(Mode.FORCED, log);
    }

    /**
     * Swap in the taping controller on every seat in {@code seatIdx} (after the game is created and its seats bound) and
     * install the tapes; {@code rng} is the game's random stream.
     */
    void install(final Game game, final PlayoutKeys.TrackedRandom rng, final int[] seatIdx) {
        final long t0 = System.nanoTime();
        final List<String> entries = new ArrayList<>();
        if (mode == Mode.FORCED) {
            for (JsonElement e : log.getAsJsonArray("entries")) {
                entries.add(e.toString());
            }
        }
        for (int s : seatIdx) {
            final Player p = game.getRegisteredPlayers().get(s);
            if (!(p.getController() instanceof PlayerControllerAi inner)) {
                throw new IllegalStateException("decision log: seat " + s + " has no Forge AI controller to tape");
            }
            final AiTape t = mode == Mode.RECORD ? AiTape.record(game, p, rng, out)
                    : AiTape.forced(game, p, rng, out, entries);
            t.tagSeat();
            p.dangerouslySetController(AiTapeController.wrap(inner, t));
            t.install();
            tapes.add(t);
            seats.add(s);
        }
        nanos += System.nanoTime() - t0;
    }

    /** On the game thread, right before the game starts: only its calls are decisions. */
    void bindGameThread() {
        for (AiTape t : tapes) {
            t.bindGameThread(Thread.currentThread());
        }
    }

    /** The game is over (or stopped): the tapes' end lines, and the tapes uninstalled. Idempotent. */
    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        final long t0 = System.nanoTime();
        for (AiTape t : tapes) {
            try {
                t.finish();
            } finally {
                t.uninstall();
            }
        }
        out.flush();
        lines = new ArrayList<>();
        for (String l : new String(buf.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            final String s = l.trim();
            if (s.startsWith(AiTape.PREFIX.trim())) {
                final JsonElement e = JsonParser.parseString(s.substring(AiTape.PREFIX.trim().length()).trim());
                if (e.isJsonObject()) {
                    lines.add(e.getAsJsonObject());
                }
            }
        }
        nanos += System.nanoTime() - t0;
    }

    private static boolean status(final JsonObject o) {
        final String k = o.has("k") ? o.get("k").getAsString() : "";
        return "boot".equals(k) || "end".equals(k) || "divergence".equals(k) || "live".equals(k);
    }

    /** RECORD: the tape line's {@link #KEY} object. */
    JsonObject tapeJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("format", AiTape.FORMAT);
        final JsonArray ss = new JsonArray();
        seats.forEach(ss::add);
        o.add("seats", ss);
        final JsonArray entries = new JsonArray(), end = new JsonArray();
        for (JsonObject l : lines) {
            if (!status(l)) {
                entries.add(l);
            } else if ("end".equals(l.get("k").getAsString())) {
                final JsonObject e = l.deepCopy();
                e.remove("recordMs"); // the record's own time is below, beside the other timings
                end.add(e);
            }
        }
        o.add("entries", entries);
        o.add("end", end);
        o.addProperty("record_ms", Math.round(nanos / 1e5) / 10.0 + recordMs());
        return o;
    }

    /** The tapes' own encoding time (their end lines' recordMs). */
    private double recordMs() {
        double ms = 0;
        for (JsonObject l : lines) {
            if (status(l) && "end".equals(l.get("k").getAsString()) && l.has("recordMs")) {
                ms += l.get("recordMs").getAsDouble();
            }
        }
        return ms;
    }

    /** The first divergence any seat's tape reported, or null. */
    String divergence() {
        for (AiTape t : tapes) {
            if (t.divergence() != null) {
                return "seat " + seats.get(tapes.indexOf(t)) + ": " + t.divergence();
            }
        }
        return null;
    }

    /**
     * FORCED: the replay's verdict, from the tapes' end lines: {@code complete} (every seat used its whole log and none
     * diverged), {@code exact} (and no drift), and the per-seat end lines.
     */
    JsonObject replayJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("mode", mode == Mode.FORCED ? "forced" : "record");
        final JsonArray end = new JsonArray();
        boolean complete = true, exact = true;
        int ends = 0;
        for (JsonObject l : lines == null ? List.<JsonObject>of() : lines) {
            if (status(l) && "end".equals(l.get("k").getAsString())) {
                ends++;
                final JsonObject e = l.deepCopy();
                e.remove("recordMs");
                end.add(e);
                if (mode == Mode.FORCED) {
                    complete &= !e.has("divergence") && e.get("used").getAsInt() == e.get("entries").getAsInt();
                    exact &= e.has("exact") && e.get("exact").getAsBoolean();
                }
            }
        }
        complete &= ends == tapes.size() && divergence() == null;
        if (mode == Mode.FORCED) {
            o.addProperty("complete", complete);
            o.addProperty("exact", complete && exact);
        }
        final String d = divergence();
        if (d != null) {
            o.addProperty("divergence", d);
        }
        o.add("end", end);
        o.addProperty("ms", Math.round(nanos / 1e5) / 10.0 + recordMs());
        return o;
    }

    /** Review #124 S2-2: the verdict the label games and index rows carry ({@code mode, source, complete, exact}). */
    JsonObject labelVerdict(final String source) {
        final JsonObject r = replayJson();
        final JsonObject o = new JsonObject();
        o.addProperty("mode", mode == Mode.FORCED ? "forced" : "record");
        o.addProperty("source", source);
        if (mode == Mode.FORCED) {
            o.add("complete", r.get("complete"));
            o.add("exact", r.get("exact"));
        }
        return o;
    }

    /** FORCED: every seat used its whole log and none diverged. */
    boolean complete() {
        final JsonObject r = replayJson();
        return r.has("complete") && r.get("complete").getAsBoolean();
    }

    // ------------------------------------------------------------------ logs a replay recorded (sidecar files)

    /** A replay's record sink (null = none): {@code decision-logs-<pid>.jsonl.gz}, one line per replayed game. */
    static volatile Sink SINK = null;
    /** Logs an earlier record replay wrote, by canonical game uid (null = none). */
    static volatile java.util.Map<String, JsonObject> SIDECAR = null;

    /**
     * The sidecar line of tape line {@code t}'s game, when an earlier record replay of this exact game (its uid, its
     * tape digest) was digest-equal (and trajectory-equal when checked): its log; else null.
     */
    static JsonObject sidecarFor(final JsonObject t) {
        final java.util.Map<String, JsonObject> m = SIDECAR;
        if (m == null) {
            return null;
        }
        final JsonObject r = m.get(forge.bench.rl.TrajectoryRef.canonicalUid(t.get("game_uid").getAsString()));
        if (r == null || !r.get("digest_tape").getAsString().equals(t.get("digest").getAsString())) {
            return null;
        }
        return r.getAsJsonObject(KEY);
    }

    /** Every file's lines, all of them kept (tests). */
    static java.util.Map<String, JsonObject> loadSidecars(final List<String> files) throws java.io.IOException {
        return loadSidecars(files, null);
    }

    /**
     * The accepted logs of {@code files} for the games in {@code wanted} (canonical uids; null = every game). Review
     * #124 S2-7: the files are streamed a line at a time and only the wanted games' logs are kept, so a replay's heap
     * holds its own games' logs only (a step-3 harness gives each shard its own sidecar file, 1:1, or a superset).
     * Review #124 S2-3: a log is accepted only from a digest-equal replay whose trajectory check against step 0 ran and
     * passed when its tape needed one ({@code reference_required}: an HonestForcedWin tape), and whose band positions
     * matched step 2's state_sha wherever they were checked.
     */
    static java.util.Map<String, JsonObject> loadSidecars(final List<String> files, final java.util.Set<String> wanted)
            throws java.io.IOException {
        final java.util.Map<String, JsonObject> m = new java.util.HashMap<>();
        for (String f : files) {
            try (java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(java.nio.file.Paths.get(f))),
                    StandardCharsets.UTF_8))) {
                while (true) {
                    final String line;
                    try {
                        line = rd.readLine();
                    } catch (java.io.EOFException e) {
                        break; // a file cut short by a killed JVM: the lines before the cut
                    }
                    if (line == null) {
                        break;
                    }
                    if (line.isEmpty()) {
                        continue;
                    }
                    final JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                    final String uid = forge.bench.rl.TrajectoryRef.canonicalUid(r.get("game_uid").getAsString());
                    if (wanted != null && !wanted.contains(uid)) {
                        continue;
                    }
                    if (!accepted(r)) {
                        continue;
                    }
                    final JsonObject prev = m.putIfAbsent(uid, r);
                    if (prev != null && !prev.get("digest_replay").equals(r.get("digest_replay"))) {
                        throw new IllegalArgumentException("decision logs: game " + uid + " has two logs of different games");
                    }
                }
            }
        }
        return m;
    }

    /** Review #124 S2-3: may this sidecar line's log be forced? */
    static boolean accepted(final JsonObject r) {
        if (!r.has(KEY) || !r.get("equal").getAsBoolean()) {
            return false;
        }
        final boolean need = r.has("reference_required") && r.get("reference_required").getAsBoolean();
        if (need && !(r.has("trajectory_equal") && r.get("trajectory_equal").getAsBoolean())) {
            return false;
        }
        if (r.has("trajectory_equal") && !r.get("trajectory_equal").getAsBoolean()) {
            return false;
        }
        return !r.has("state_sha_mismatch") || r.get("state_sha_mismatch").getAsInt() == 0;
    }

    /** Shared by the replay threads of one JVM; every line is gzip sync-flushed (a killed JVM's file stays readable). */
    static final class Sink implements java.io.Closeable {
        private final java.nio.file.Path file;
        private java.io.Writer w;

        Sink(final java.nio.file.Path dir) throws java.io.IOException {
            java.nio.file.Files.createDirectories(dir);
            this.file = dir.resolve("decision-logs-" + ProcessHandle.current().pid() + ".jsonl.gz");
        }

        java.nio.file.Path file() {
            return file;
        }

        /** One replayed game: the tape line it replays, the replay's verdict and digest, and its seats' log. */
        synchronized void write(final JsonObject t, final String tape, final int line, final JsonObject row,
                final boolean equal, final DecisionLog dl) throws java.io.IOException {
            if (w == null) {
                w = new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(
                        java.nio.file.Files.newOutputStream(file), 1 << 16, true), StandardCharsets.UTF_8);
            }
            final JsonObject o = new JsonObject();
            o.addProperty("schema", "mtgx-decision-log/1");
            o.add("game_uid", t.get("game_uid"));
            o.add("seed", t.get("seed"));
            o.addProperty("tape", tape);
            o.addProperty("line", line);
            o.add("digest_tape", t.get("digest"));
            o.add("digest_replay", row.get("digest_replay"));
            o.addProperty("equal", equal);
            // review #124 S2-3: an HonestForcedWin tape's re-run is host-dependent; its log counts only with the
            // trajectory check against step 0 (and step 2's state_sha where checked)
            o.addProperty("reference_required", RlActorBench.honestForcedWinTape(t));
            for (String k : new String[] {"trajectory_equal", "state_sha_checked", "state_sha_mismatch"}) {
                if (row.has(k)) {
                    o.add(k, row.get(k));
                }
            }
            o.add(KEY, dl.tapeJson());
            w.write(forge.bench.rl.RlWire.canonicalString(o));
            w.write('\n');
            w.flush();
        }

        @Override
        public synchronized void close() throws java.io.IOException {
            if (w != null) {
                w.close();
                w = null;
            }
        }
    }
}
