package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlTape;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;

/**
 * Lane bvw-decision-log-1027 (owner 2026-10-27: "record all moves by both players"): the research generator's decision
 * log ({@link DecisionLog}) on real Forge games. Recording changes nothing but the log; a replay of a logged tape is
 * FORCED from it (the seats decide nothing) and is the same game; a log that does not fit is a divergence, void, never
 * re-searched; a replay that re-runs a tape's seats can log them for later forced replays. Seats: the counted
 * HonestForcedWin generator profile (deterministic budgets), the cube combo policy and Forge Default, both seats taped.
 *
 * <p>Run inside a broker test lease, cwd = forge-gui with res/, {@code -Dforge.bench.sequentialAi=true} (the counted
 * profile's runtime). Fixtures: the 24 TRAIN decks of {@code rl/decks} in a temporary {@code $RL_ROOT} (as
 * RlLabelReplayTest); {@code -Drl.dlGames} games (default 4) from {@code -Drl.seed} (default 727100000).
 */
public class BvwDecisionLogTest {

    static Path root, bank, out;
    static CardIndex index;
    static final String[] CTL = {"forge:HonestForcedWinCounted", "forge:CubeCombo", "forge", "forge:HonestForcedWinCounted"};
    static final List<JsonObject> games = new ArrayList<>();
    static final List<JsonObject> off = new ArrayList<>(), on = new ArrayList<>();
    static final List<RlActorBench.Played> onPlayed = new ArrayList<>();
    static Path onTapes, offTapes;

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBench.ensureBooted();
        final Path decks = forge.bench.rl.RlWireSchemaTest.res().resolve("decks");
        root = Files.createTempDirectory("rl-dl-root-");
        bank = root.resolve("data/train/t24");
        Files.createDirectories(bank.resolve("decks"));
        final JsonArray td = new JsonArray();
        final TreeSet<String> names = new TreeSet<>();
        final List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(decks)) {
            s.filter(p -> p.toString().endsWith(".dck")).sorted().forEach(files::add);
        }
        for (Path f : files) {
            final String n = f.getFileName().toString();
            Files.copy(f, bank.resolve("decks").resolve(n));
            td.add(RlActorBenchTest.entry(n, f));
            final Deck d = DeckSerializer.fromFile(f.toFile());
            for (Map.Entry<PaperCard, Integer> e : d.getMain()) names.add(e.getKey().getName());
        }
        RlActorBenchTest.manifest(bank, "t24", "train", td);
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : names) sb.append(n).append('\t').append(i++).append('\n');
        index = CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
        out = Files.createTempDirectory("rl-dl-out-");
        final int n = Integer.getInteger("rl.dlGames", 4);
        final long seed0 = Long.getLong("rl.seed", 727100000L);
        for (int k = 0; k < n; k++) {
            final String c = CTL[k % CTL.length];
            final JsonObject g = RlFakeRun.schedule(bank, k, 1, seed0, new String[] {c, c}, false, false, 6000L).get(0);
            g.addProperty("fair_naming", true);
            g.addProperty("obs_schema", 2);
            games.add(g);
            off.add(play(g, null).tape);
            final RlActorBench.Played p = play(g, "record");
            onPlayed.add(p);
            on.add(p.tape);
        }
        offTapes = write("off", off);
        onTapes = write("on", on);
    }

    static RlActorBench.Played play(final JsonObject g, final String decisionLog) {
        final RlActorBench.Cfg c = cfg("train");
        c.decisionLog = decisionLog;
        final RlActorBench.Played p = RlActorBench.play(c, "train", g, new RlFeaturizer(index), RlActorBench.nullRecorder(),
                null, "test");
        Assert.assertNull(p.guardError, String.valueOf(p.guardError));
        return p;
    }

    static RlActorBench.Cfg cfg(final String mode) {
        final RlActorBench.Cfg c = new RlActorBench.Cfg();
        c.mode = mode;
        c.rlRoot = root.toString();
        c.aiTimeoutSec = 600;
        c.obsSchema = 2;
        return c;
    }

    static Path write(final String name, final List<JsonObject> lines) throws Exception {
        final Path dir = out.resolve("tapes-" + name);
        try (RlTape t = new RlTape(dir, 1000)) {
            for (JsonObject j : lines) {
                t.write(j);
            }
        }
        return dir;
    }

    /**
     * A tape line without its wall-clock fields and its log: what recording must leave unchanged. The counted
     * generator's work meter (honest_generator[*].meter: cards and steps in its search copies; outside its spec,
     * diagnostics only) also varies between two plays of one game in one JVM without any log (bvw-step1-build-1024: "a
     * residual solver-internal variation"), so it is left out here (the 40-game Studio proof reports it against an OFF-vs-OFF control).
     */
    static String masked(final JsonObject t) {
        final JsonObject m = t.deepCopy();
        m.remove(DecisionLog.KEY);
        if (m.has("honest_generator")) {
            for (JsonElement e : m.getAsJsonArray("honest_generator")) {
                if (e.isJsonObject()) {
                    e.getAsJsonObject().remove("meter");
                }
            }
        }
        strip(m);
        return RlWire.canonicalString(m);
    }

    private static void strip(final JsonElement e) {
        if (e.isJsonObject()) {
            for (String k : new ArrayList<>(e.getAsJsonObject().keySet())) {
                if (k.endsWith("_ms") || k.endsWith("Ms")) {
                    e.getAsJsonObject().remove(k);
                } else {
                    strip(e.getAsJsonObject().get(k));
                }
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) strip(x);
        }
    }

    static RlActorBench.Cfg replayCfg(final Path tapes, final String decisionLog, final String name) throws Exception {
        final RlActorBench.Cfg c = cfg("replay");
        c.replayDir = tapes.toString();
        c.replayOut = Files.createTempDirectory(out, name + "-").resolve("replay.jsonl").toString();
        c.decisionLog = decisionLog;
        return c;
    }

    static List<JsonObject> rows(final RlActorBench.Cfg c) throws Exception {
        final List<JsonObject> rows = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(c.replayOut), StandardCharsets.UTF_8)) {
            if (!l.isBlank()) {
                rows.add(JsonParser.parseString(l).getAsJsonObject());
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------ record

    @Test
    public void recordingChangesNothingButTheLog() {
        for (int k = 0; k < games.size(); k++) {
            Assert.assertFalse(off.get(k).has(DecisionLog.KEY), "no log without decisionLog record");
            Assert.assertEquals(masked(on.get(k)), masked(off.get(k)), "game " + k + " (" + CTL[k % CTL.length]
                    + "): the tape line with recording on is the line without it, apart from the log and wall times");
            final JsonObject log = on.get(k).getAsJsonObject(DecisionLog.KEY);
            final boolean[] seat = new boolean[2];
            for (JsonElement e : log.getAsJsonArray("entries")) {
                seat[e.getAsJsonObject().get("s").getAsInt()] = true;
            }
            Assert.assertTrue(seat[0] && seat[1], "both seats' decisions are logged");
            Assert.assertEquals(log.getAsJsonArray("end").size(), 2);
            for (JsonElement e : log.getAsJsonArray("end")) {
                Assert.assertEquals(e.getAsJsonObject().get("unencodable").getAsInt(), 0, "every answer encodable: " + e);
            }
        }
        // the counted generator seats really ran their solver while recording (a cube combo seat stays one)
        Assert.assertTrue(on.get(0).has("honest_generator"), "the HonestForcedWin seats' summary is on the tape");
    }

    // ------------------------------------------------------------------------------------------------ forced replay

    @Test
    public void aLoggedTapeReplaysForcedAsTheSameGameAndDecidesNothing() throws Exception {
        final RlActorBench.Cfg c = replayCfg(onTapes, null, "forced");
        Assert.assertEquals(RlActorBench.replay(c, index, "test"), 0, "every forced replay is digest-equal");
        final List<JsonObject> rows = rows(c);
        Assert.assertEquals(rows.size(), games.size());
        for (JsonObject r : rows) {
            Assert.assertTrue(r.get("equal").getAsBoolean(), r.toString());
            Assert.assertEquals(r.get("decision_log_source").getAsString(), "tape");
            final JsonObject dl = r.getAsJsonObject(DecisionLog.KEY);
            Assert.assertTrue(dl.get("complete").getAsBoolean() && dl.get("exact").getAsBoolean(), dl.toString());
            Assert.assertEquals(r.get("generator").getAsString(), "forced");
        }
        // decides nothing: the counted generator's solver is never built in a forced replay of its game
        final JsonObject t = on.get(0);
        final RlActorBench.Played p = RlActorBench.play(cfg("train"), "train", games.get(0), new RlFeaturizer(index),
                RlActorBench.nullRecorder(), null, "test", null, DecisionLog.forced(t.getAsJsonObject(DecisionLog.KEY)));
        Assert.assertEquals(p.end.get("digest").getAsString(), t.get("digest").getAsString());
        Assert.assertFalse(p.end.has("honest_generator"), "no solver ran: " + p.end.get("honest_generator"));
        Assert.assertTrue(p.decisionLog.complete());
    }

    @Test
    public void rerunIgnoresTheLogAndTapesWithoutOneRerunAsBefore() throws Exception {
        final RlActorBench.Cfg c = replayCfg(onTapes, "rerun", "rerun");
        Assert.assertEquals(RlActorBench.replay(c, index, "test"), 0);
        for (JsonObject r : rows(c)) {
            Assert.assertTrue(r.get("equal").getAsBoolean(), r.toString());
            Assert.assertFalse(r.has(DecisionLog.KEY), "re-run: no log played");
        }
        final RlActorBench.Cfg o = replayCfg(offTapes, null, "off-auto");
        Assert.assertEquals(RlActorBench.replay(o, index, "test"), 0);
        for (JsonObject r : rows(o)) {
            Assert.assertTrue(r.get("equal").getAsBoolean(), r.toString());
            Assert.assertFalse(r.has(DecisionLog.KEY), "a tape without a log re-runs its seats, as before");
        }
    }

    // ------------------------------------------------------------------------------------------------ divergence

    @Test
    public void aLogThatDoesNotFitIsADivergenceTheGameStopsAndNothingIsReSearched() throws Exception {
        final JsonObject t = on.get(0).deepCopy();
        final JsonArray entries = t.getAsJsonObject(DecisionLog.KEY).getAsJsonArray("entries");
        final int cut = entries.size() / 2;
        entries.remove(cut);
        final RlActorBench.Played p = RlActorBench.play(cfg("train"), "train", games.get(0), new RlFeaturizer(index),
                RlActorBench.nullRecorder(), null, "test", null, DecisionLog.forced(t.getAsJsonObject(DecisionLog.KEY)));
        Assert.assertEquals(p.end.get("void").getAsString(), DecisionLog.VOID, p.end.toString());
        Assert.assertNotNull(p.decisionLog.divergence());
        Assert.assertFalse(p.decisionLog.complete());
        Assert.assertFalse(p.end.has("honest_generator"), "the seats never decided in the log's place");
        final JsonObject v = p.decisionLog.replayJson();
        int used = 0, of = 0;
        for (JsonElement e : v.getAsJsonArray("end")) {
            used += e.getAsJsonObject().get("used").getAsInt();
            of += e.getAsJsonObject().get("entries").getAsInt();
        }
        Assert.assertTrue(used <= cut + 1 && used < of, "the game stopped at the divergence: used " + used + " of " + of);
        // through the runner: the replay row is unequal and says why
        final Path dir = write("cut", List.of(t));
        final RlActorBench.Cfg c = replayCfg(dir, null, "cut");
        Assert.assertEquals(RlActorBench.replay(c, index, "test"), 4);
        final JsonObject r = rows(c).get(0);
        Assert.assertFalse(r.get("equal").getAsBoolean());
        Assert.assertTrue(r.getAsJsonObject(DecisionLog.KEY).has("divergence"), r.toString());
    }

    // ------------------------------------------------------------------------------------------------ record on replay

    @Test
    public void aReplayThatReRunsCanLogItsSeatsForLaterForcedReplays() throws Exception {
        final Path logs = Files.createTempDirectory(out, "sidecar-");
        final RlActorBench.Cfg c = replayCfg(offTapes, "record", "record");
        c.decisionLogOut = logs.toString();
        Assert.assertEquals(RlActorBench.replay(c, index, "test"), 0);
        final List<String> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(logs)) {
            s.filter(x -> x.getFileName().toString().startsWith("decision-logs-")).forEach(x -> files.add(x.toString()));
        }
        Assert.assertEquals(files.size(), 1);
        final List<JsonObject> side = RlTape.read(Paths.get(files.get(0)));
        Assert.assertEquals(side.size(), games.size());
        for (JsonObject s : side) {
            Assert.assertTrue(s.get("equal").getAsBoolean());
            Assert.assertEquals(s.get("digest_replay"), s.get("digest_tape"));
        }
        final RlActorBench.Cfg f = replayCfg(offTapes, null, "from-sidecar");
        f.decisionLogs = files;
        Assert.assertEquals(RlActorBench.replay(f, index, "test"), 0);
        for (JsonObject r : rows(f)) {
            Assert.assertTrue(r.get("equal").getAsBoolean(), r.toString());
            Assert.assertEquals(r.get("decision_log_source").getAsString(), "sidecar");
            Assert.assertTrue(r.getAsJsonObject(DecisionLog.KEY).get("exact").getAsBoolean(), r.toString());
        }
        DecisionLog.SIDECAR = null;
    }

    @Test
    public void theLogRefusesBridgedSeatsAndBadValues() {
        final JsonObject g = games.get(0).deepCopy();
        final JsonArray c = new JsonArray();
        c.add("record");
        c.add("forge");
        g.add("controllers", c);
        final RlActorBench.Cfg rc = cfg("record");
        rc.decisionLog = "record";
        final RlActorBench.Played p = RlActorBench.play(rc, "record", g, new RlFeaturizer(index),
                RlActorBench.nullRecorder(), null, "test");
        Assert.assertNotNull(p.guardError);
        Assert.assertTrue(p.guardError.contains("not a Forge seat"), p.guardError);
        final JsonObject bad = new JsonObject();
        bad.addProperty("mode", "train");
        bad.addProperty("decisionLog", "forced");
        Assert.expectThrows(IllegalArgumentException.class, () -> RlActorBench.parse(bad));
        final JsonObject rec = new JsonObject();
        rec.addProperty("mode", "replay");
        rec.addProperty("decisionLog", "record");
        Assert.expectThrows(IllegalArgumentException.class, () -> RlActorBench.parse(rec)); // no decisionLogOut
    }
}
