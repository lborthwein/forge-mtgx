package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiTape;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlTape;
import forge.bench.rl.RlWire;

/**
 * Lane sdcc-replay-sample-1113: the sampled decision log of a generation run ({@code decisionLogSample}) and the watch
 * recorder of a replay ({@code replay.watchOut}), on real games whose two seats are RL seats answered by the in-process
 * fake server (uniform legal steps, a function of the game uid and the decision index).
 *
 * <ul>
 *   <li>the sample is a function of the GAME seed alone, nested in the rate, off at 0;</li>
 *   <li>a sampled game is the game it would have been (digest, result, dec rows, every tape key but the log and the
 *   timings), and an unsampled game of a sampling actor has no log;</li>
 *   <li>its tape line replays FORCED to the same digest, and with {@code replay.watchOut} leaves a watch table's
 *   stream (both seats' hello, events, a state per priority decision, terminals) and config;</li>
 *   <li>a recorder fault, while recording or at install, drops the log and is counted; the game is not void and is still
 *   the same game.</li>
 * </ul>
 *
 * Run as RlActorBenchTest (cwd = forge-gui with res/).
 */
public class RlReplaySampleTest {
    static JsonObject game;
    static JsonObject offTape;

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBenchTest.setUp();
        game = RlActorBenchTest.game(0, "rl:M", "rl:M", true);
        offTape = play(0).tape;
        Assert.assertTrue(offTape.get("void").isJsonNull(), "the fixture game is valid: " + offTape.get("void"));
        Assert.assertFalse(offTape.has(DecisionLog.KEY));
    }

    @AfterMethod(alwaysRun = true)
    public void clean() {
        DecisionLog.SAMPLE_TEST_DIAG = null;
    }

    static RlActorBench.Played play(final double rate) throws Exception {
        final RlActorBench.Cfg c = RlActorBenchTest.cfg("train");
        c.decisionLogSample = rate;
        final RlActorBench.Played p = RlActorBench.play(c, "train", game, new RlFeaturizer(RlActorBenchTest.index),
                new RlActorBenchTest.LocalEndpoint(new FakeRlServer(0, "train", 7, null, Collections.emptyList())), null,
                "test");
        Assert.assertNull(p.guardError, String.valueOf(p.guardError));
        return p;
    }

    /** A tape line without its log and its wall-clock fields: what sampling must leave unchanged. */
    static String masked(final JsonObject t) {
        final JsonObject m = t.deepCopy();
        m.remove(DecisionLog.KEY);
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
            for (JsonElement x : e.getAsJsonArray()) {
                strip(x);
            }
        }
    }

    @Test
    public void theSampleIsAFunctionOfTheSeedNestedInTheRate() {
        int at1 = 0, at10 = 0;
        for (long s = 754_008_800L; s < 754_008_800L + 200_000; s++) {
            Assert.assertFalse(DecisionLog.sampled(s, 0), "rate 0 samples nothing");
            Assert.assertTrue(DecisionLog.sampled(s, 1), "rate 1 samples everything");
            final boolean a = DecisionLog.sampled(s, 0.01), b = DecisionLog.sampled(s, 0.10);
            Assert.assertEquals(DecisionLog.sampled(s, 0.01), a, "the same answer every time");
            Assert.assertTrue(!a || b, "a lower rate's sample is inside a higher rate's");
            at1 += a ? 1 : 0;
            at10 += b ? 1 : 0;
        }
        Assert.assertTrue(at1 > 1700 && at1 < 2300, "about 1 % of 200,000 consecutive seeds: " + at1);
        Assert.assertTrue(at10 > 19000 && at10 < 21000, "about 10 %: " + at10);
        Assert.assertFalse(DecisionLog.sampled(1L, Double.NaN));
    }

    @Test
    public void aBadRateIsRefusedAtStart() {
        for (String bad : new String[] {"{\"mode\":\"train\",\"decisionLogSample\":1.5}",
            "{\"mode\":\"train\",\"decisionLogSample\":-0.1}",
            "{\"mode\":\"replay\",\"decisionLogSample\":0.5}",
            "{\"mode\":\"train\",\"decisionLog\":\"record\",\"decisionLogSample\":0.5}",
            "{\"mode\":\"train\",\"replay\":{\"watchOut\":\"x\"}}"}) {
            Assert.assertThrows(IllegalArgumentException.class,
                    () -> RlActorBench.parse(JsonParser.parseString(bad).getAsJsonObject()));
        }
        Assert.assertEquals(RlActorBench.parse(JsonParser.parseString("{\"mode\":\"train\"}").getAsJsonObject())
                .decisionLogSample, 0.0, "off by default");
        Assert.assertEquals(RlActorBench.parse(JsonParser.parseString("{\"mode\":\"train\",\"decisionLogSample\":0.01}")
                .getAsJsonObject()).decisionLogSample, 0.01);
    }

    @Test
    public void aSampledGameIsTheSameGameAndAnUnsampledOneHasNoLog() throws Exception {
        final long before = DecisionLog.SAMPLE_WRITTEN.get();
        final JsonObject on = play(1.0).tape;
        Assert.assertTrue(on.has(DecisionLog.KEY), "the sampled game's tape line carries its log");
        final JsonObject log = on.getAsJsonObject(DecisionLog.KEY);
        Assert.assertTrue(log.get("sample").getAsBoolean());
        Assert.assertEquals(log.get("format").getAsInt(), AiTape.FORMAT);
        Assert.assertTrue(log.getAsJsonArray("entries").size() > 0);
        Assert.assertEquals(log.getAsJsonArray("end").size(), 2, "both RL seats are taped");
        Assert.assertEquals(DecisionLog.SAMPLE_WRITTEN.get(), before + 1);
        Assert.assertEquals(on.get("digest").getAsString(), offTape.get("digest").getAsString());
        Assert.assertEquals(masked(on), masked(offTape), "every other key of the tape line is the unsampled game's");
        // a rate under which this seed is not sampled: the line of an actor that does not sample at all
        double r = 0.5;
        while (DecisionLog.sampled(game.get("seed").getAsLong(), r)) {
            r /= 2;
        }
        final JsonObject not = play(r).tape;
        Assert.assertFalse(not.has(DecisionLog.KEY));
        Assert.assertEquals(masked(not), masked(offTape));
    }

    @Test
    public void aSampledTapeLineReplaysForcedAndLeavesAWatchStream() throws Exception {
        final JsonObject on = play(1.0).tape;
        final Path dir = Files.createTempDirectory("rl-sample-");
        try (RlTape t = new RlTape(dir.resolve("tapes"), 1000)) {
            t.write(on);
        }
        final RlActorBench.Cfg c = RlActorBenchTest.cfg("replay");
        c.replayDir = dir.resolve("tapes").toString();
        c.replayOut = dir.resolve("replay.jsonl").toString();
        c.decisionLog = "forced";
        c.replayWatchOut = dir.resolve("watch").toString();
        Assert.assertEquals(RlActorBench.replay(c, RlActorBenchTest.index, "test"), 0, "the forced replay is digest-equal");
        final JsonObject row = JsonParser.parseString(Files.readAllLines(dir.resolve("replay.jsonl"),
                StandardCharsets.UTF_8).get(0)).getAsJsonObject();
        Assert.assertTrue(row.get("equal").getAsBoolean(), row.toString());
        final JsonObject verdict = row.getAsJsonObject(DecisionLog.KEY);
        Assert.assertTrue(verdict.get("complete").getAsBoolean(), verdict.toString());
        final JsonObject w = row.getAsJsonObject("watch");
        Assert.assertTrue(w.get("complete").getAsBoolean() && w.get("equal").getAsBoolean(), w.toString());
        final Path g = dir.resolve("watch").resolve(on.get("game_uid").getAsString());
        final JsonObject cfgJson = JsonParser.parseString(Files.readString(g.resolve("config.json"))).getAsJsonObject();
        Assert.assertEquals(cfgJson.get("seed").getAsLong(), on.get("seed").getAsLong());
        Assert.assertEquals(cfgJson.get("session").getAsString(), "rs-" + on.get("game_uid").getAsString());
        Assert.assertEquals(cfgJson.getAsJsonArray("seatAi").toString(), "[\"policy\",\"policy\"]");
        Assert.assertTrue(cfgJson.getAsJsonObject("replay").get("equal").getAsBoolean());
        Assert.assertTrue(Files.size(g.resolve("deck0.dck")) > 0 && Files.size(g.resolve("deck1.dck")) > 0);
        final List<String> lines = Files.readAllLines(g.resolve("stream.jsonl"), StandardCharsets.UTF_8);
        final int[] hello = new int[2], terminal = new int[2], states = new int[2], events = new int[2];
        final long[] seq = new long[2];
        int lastN = -1;
        for (String l : lines) {
            final JsonObject m = JsonParser.parseString(l).getAsJsonObject();
            final int to = m.get("to").getAsInt();
            Assert.assertEquals(m.get("seq").getAsLong(), ++seq[to], "each seat's own sequence");
            Assert.assertEquals(terminal[to], 0, "nothing after a seat's terminal");
            switch (m.get("type").getAsString()) {
                case "hello":
                    Assert.assertEquals(m.get("seq").getAsLong(), 1L);
                    Assert.assertEquals(m.get("humanSeat").getAsInt(), to);
                    Assert.assertEquals(m.get("seed").getAsLong(), on.get("seed").getAsLong());
                    hello[to]++;
                    break;
                case "state":
                    Assert.assertEquals(m.getAsJsonObject("view").get("seat").getAsInt(), to);
                    if (m.has("decision")) {
                        final JsonObject d = m.getAsJsonObject("decision");
                        Assert.assertTrue(d.has("seat") && d.has("turn") && d.has("phase"));
                        if (to == 0) {
                            Assert.assertEquals(d.get("n").getAsInt(), ++lastN, "decisions are numbered from 0");
                        }
                        states[to]++;
                    } else {
                        Assert.assertEquals(m.get("reason").getAsString(), "game-completed");
                    }
                    break;
                case "event":
                    events[to]++;
                    break;
                case "terminal":
                    terminal[to]++;
                    break;
                default:
                    Assert.fail("unexpected message " + l.substring(0, Math.min(200, l.length())));
            }
        }
        Assert.assertEquals(hello, new int[] {1, 1});
        Assert.assertEquals(terminal, new int[] {1, 1});
        Assert.assertTrue(states[0] > 0 && states[0] == states[1], "a state per priority decision on both channels");
        Assert.assertTrue(events[0] > 0 && events[0] == events[1]);
    }

    /** A recorder that throws from its {@code n}-th top-level entry on. */
    private static AiTape.Diag faulty(final int n) {
        final int[] calls = {0};
        return new AiTape.Diag() {
            @Override
            public Object begin() {
                if (++calls[0] >= n) {
                    throw new IllegalStateException("injected recorder fault");
                }
                return null;
            }

            @Override
            public JsonObject end(final Object token) {
                return null;
            }
        };
    }

    @Test
    public void aRecorderFaultDropsTheLogAndNeverTheGame() throws Exception {
        final long dropped = DecisionLog.SAMPLE_DROPPED.get(), written = DecisionLog.SAMPLE_WRITTEN.get();
        DecisionLog.SAMPLE_TEST_DIAG = p -> faulty(5);
        final JsonObject t = play(1.0).tape;
        Assert.assertTrue(t.get("void").isJsonNull(), "the game is not void: " + t.get("void"));
        Assert.assertFalse(t.has(DecisionLog.KEY), "a broken log is not written");
        Assert.assertEquals(masked(t), masked(offTape), "and the game is the game it would have been");
        Assert.assertEquals(DecisionLog.SAMPLE_DROPPED.get(), dropped + 1);
        Assert.assertEquals(DecisionLog.SAMPLE_WRITTEN.get(), written);
    }

    @Test
    public void anInstallFaultIsUndone() throws Exception {
        final long dropped = DecisionLog.SAMPLE_DROPPED.get();
        final int[] seen = {0};
        DecisionLog.SAMPLE_TEST_DIAG = p -> {
            if (++seen[0] == 2) {
                throw new IllegalStateException("injected install fault"); // seat 0 is swapped already: undone
            }
            return null;
        };
        final JsonObject t = play(1.0).tape;
        Assert.assertEquals(seen[0], 2);
        Assert.assertTrue(t.get("void").isJsonNull(), "the game is not void: " + t.get("void"));
        Assert.assertFalse(t.has(DecisionLog.KEY));
        Assert.assertEquals(masked(t), masked(offTape));
        Assert.assertEquals(DecisionLog.SAMPLE_DROPPED.get(), dropped + 1);
        Assert.assertNull(AiTape.current(), "no tape is left installed");
    }
}
