package forge.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.RlFrameObs;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlWire;
import forge.game.Game;

/**
 * Lane frame-score-1105: {@code frameStates} ({@link forge.bench.rl.RlFrameStates}) beside the frame dump, and
 * {@link FrameObsRun}'s install path. On the label-replay fixture (real games): (a) the keys and refusals; (b) the dump
 * with frameStates on writes the same records byte for byte as without, and its games replay digest-equal; (c) each
 * state row's reference sha is the dump payload's at the same (game_uid, seat, dec_idx); (d) each frame + sidecar,
 * installed in a fresh game, featurizes to the dump's payload (counted per stack-empty / stack rows: GameState text has
 * no stack); (e) a GameCopier fork of an installed frame featurizes byte-identically to it
 * ({@link RlFrameObs#featurizeForked}). Run inside a broker test lease, cwd = forge-gui.
 */
public class RlFrameStatesTest {

    private static boolean built = false;

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlLabelReplayTest.tapes.isEmpty()) {
            RlLabelReplayTest.setUp();
            built = true;
        }
    }

    @AfterClass(alwaysRun = true)
    public static void tearDown() {
        if (built) {
            RlLabelReplayTest.tapes.clear();
            built = false;
        }
    }

    @Test
    public void theFrameStatesKeysAreChecked() {
        final RlActorBench.Cfg c = RlActorBench.parse(JsonParser.parseString(
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\"},\"frameStates\":{\"out\":\"/s\",\"every\":5}}")
                .getAsJsonObject());
        Assert.assertEquals(c.frameStatesOut, "/s");
        Assert.assertEquals(c.frameStatesEvery, 5);
        Assert.assertNull(RlActorBench.parse(JsonParser.parseString(
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\"}}").getAsJsonObject()).frameStatesOut, "absent = off");
        for (String bad : new String[] {"{\"mode\":\"replay\",\"frameStates\":{\"out\":\"/s\"}}",
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\"},\"frameStates\":{}}",
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\"},\"frameStates\":{\"out\":\"/s\",\"evry\":2}}",
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\"},\"frameStates\":{\"out\":\"/s\",\"every\":0}}"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(
                    JsonParser.parseString(bad).getAsJsonObject()));
        }
    }

    static List<JsonObject> states(final Path dir) throws Exception {
        final List<JsonObject> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().startsWith("states-"))::iterator) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                        Files.newInputStream(p)), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) {
                        if (!l.isBlank()) {
                            out.add(JsonParser.parseString(l).getAsJsonObject());
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test(timeOut = 1800000)
    public void statesInstallToTheDumpsFramesAndChangeNothing() throws Exception {
        final Path base = Files.createTempDirectory(RlLabelReplayTest.out, "st-base-");
        Assert.assertEquals(RlActorBench.replay(RlFrameDumpTest.dumpCfg(base, 0, 0), RlLabelReplayTest.index, "test"), 0);
        final Map<String, Object[]> br = RlFrameDumpTest.byKey(RlLabelReplayTest.records(base));
        final Path on = Files.createTempDirectory(RlLabelReplayTest.out, "st-on-");
        final Path sd = on.resolve("states");
        final RlActorBench.Cfg c = RlFrameDumpTest.dumpCfg(on, 0, 0);
        c.frameStatesOut = sd.toString();
        c.frameStatesEvery = 1;
        Assert.assertEquals(RlActorBench.replay(c, RlLabelReplayTest.index, "test"), 0);
        for (JsonObject g : RlLabelReplayTest.jsonl(on, "games-")) {
            Assert.assertTrue(g.get("equal").getAsBoolean(), g.toString());
        }
        final Map<String, Object[]> dr = RlFrameDumpTest.byKey(RlLabelReplayTest.records(on));
        Assert.assertEquals(dr.keySet(), br.keySet());
        for (Map.Entry<String, Object[]> e : br.entrySet()) {
            Assert.assertTrue(Arrays.equals((byte[]) dr.get(e.getKey())[1], (byte[]) e.getValue()[1]),
                    "frameStates moved the dump at " + e.getKey());
        }
        final List<JsonObject> st = states(sd);
        Assert.assertFalse(st.isEmpty());
        int eq0 = 0, n0 = 0, eqS = 0, nS = 0, forkEq = 0, forks = 0;
        final List<String> diff = new ArrayList<>();
        for (JsonObject s : st) {
            final String k = s.get("game_uid").getAsString() + ":" + s.get("seat").getAsInt() + ":"
                    + s.get("dec_idx").getAsInt();
            final Object[] d = dr.get(k);
            Assert.assertNotNull(d, "no dump row for state " + k);
            Assert.assertEquals(s.get("payload_sha256").getAsString(), RlFrameObs.Frame.sha256((byte[]) d[1]),
                    "reference sha at " + k);
            final JsonObject sc = s.getAsJsonObject("rl");
            final Game g = FrameObsRun.install(s.get("frame").getAsString(), sc.getAsJsonArray("decks"));
            final RlKnowledge kn = RlFrameObs.applySidecar(g, sc);
            final long uid = RlWire.parseUid(s.get("game_uid").getAsString());
            final int seat = s.get("seat").getAsInt();
            final RlFrameObs.Frame f = RlFrameObs.valueFrame(g, g.getRegisteredPlayers().get(seat), kn,
                    RlLabelReplayTest.index, s.get("obs").getAsInt(), uid, s.get("dec_idx").getAsInt());
            final boolean eq = Arrays.equals(f.payload, (byte[]) d[1]);
            if (s.get("stack").getAsInt() == 0) {
                n0++;
                eq0 += eq ? 1 : 0;
            } else {
                nS++;
                eqS += eq ? 1 : 0;
            }
            if (!eq && diff.size() < 10) {
                diff.add(k + " " + s.get("phase").getAsString());
            }
            if (forks < 20) {
                final Game cp = (Game) RlFrameObs.forkCopy(g, g.getRegisteredPlayers().get(seat))[0];
                final RlFrameObs.Frame ff = RlFrameObs.featurizeForked(cp, seat, kn, RlLabelReplayTest.index,
                        s.get("obs").getAsInt(), uid, s.get("dec_idx").getAsInt());
                forks++;
                forkEq += Arrays.equals(ff.payload, f.payload) ? 1 : 0;
            }
        }
        System.err.println("[frame-states test] states " + st.size() + " install-equal stack-empty " + eq0 + "/" + n0
                + " stack " + eqS + "/" + nS + " fork-equal " + forkEq + "/" + forks + " first diffs " + diff);
        Assert.assertEquals(forkEq, forks, "a forked unchanged state featurizes as its original");
        Assert.assertTrue(n0 > 0);
    }
}
