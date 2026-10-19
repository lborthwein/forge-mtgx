package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFeaturizer;

/**
 * Lane r4-design-1015 (R4 draft): the RL seats' "Cancel Search?" loop guard on bench games is recorded on the tape and a
 * replay plays the guard its tape played. {@link RlActorBench#loopGuardOf}: a GAME's own {@code loop_guard} wins over
 * the actor's {@code loopGuard} (exploit-panel-1015's config key); absent, the actor's; negative refused. With the guard
 * on, a game without a hit is the guard-off game (same digest), its tape carries {@code loop_guard} and
 * {@code loop_guard_hits}, and replayOne, run by an actor whose own loopGuard differs, takes the tape's value and
 * reproduces the digest. A guard-off tape has no key. Plays real Forge games: run inside a broker test lease; cwd =
 * forge-gui with res/. Fixtures: {@link RlActorBenchTest}'s tiny-24 TRAIN bank.
 */
public class RlActorBenchLoopGuardTest {

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBenchTest.setUp();
    }

    static JsonObject g(final Integer k) {
        final JsonObject o = new JsonObject();
        if (k != null) {
            o.addProperty("loop_guard", k);
        }
        return o;
    }

    @Test
    public void precedenceAndRefusal() {
        Assert.assertEquals(RlActorBench.loopGuardOf(null, 0), 0);
        Assert.assertEquals(RlActorBench.loopGuardOf(g(null), 2), 2, "absent: the actor's");
        Assert.assertEquals(RlActorBench.loopGuardOf(g(0), 2), 0, "a tape played without the guard replays without it");
        Assert.assertEquals(RlActorBench.loopGuardOf(g(3), 0), 3, "the GAME's (tape's) value wins");
        final JsonObject nul = new JsonObject();
        nul.add("loop_guard", com.google.gson.JsonNull.INSTANCE);
        Assert.assertEquals(RlActorBench.loopGuardOf(nul, 2), 2);
        Assert.expectThrows(IllegalArgumentException.class, () -> RlActorBench.loopGuardOf(g(-1), 0));
    }

    @Test
    public void tapeKeyAndReplay() throws Exception {
        final int games = Integer.getInteger("rl.lgGames", 6);
        final List<JsonObject> tapes = new ArrayList<>();
        int hits = 0;
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        for (int i = 0; i < games; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true);
            final RlActorBench.Cfg on = RlActorBenchTest.cfg("train");
            on.loopGuard = 2;
            final RlActorBench.Played p = RlActorBench.play(on, "train", game.deepCopy(), new RlFeaturizer(
                    RlActorBenchTest.index), new RlActorBenchTest.LocalEndpoint(checker), null, "test");
            Assert.assertNull(p.guardError);
            Assert.assertEquals(p.tape.get("loop_guard").getAsInt(), 2, "the tape records the guard it played");
            Assert.assertTrue(p.tape.has("loop_guard_hits"));
            Assert.assertTrue(p.end.has("loop_guard_hits"));
            hits += p.tape.get("loop_guard_hits").getAsInt();
            final RlActorBench.Played off = RlActorBench.play(RlActorBenchTest.cfg("train"), "train", game.deepCopy(),
                    new RlFeaturizer(RlActorBenchTest.index), new RlActorBenchTest.LocalEndpoint(checker), null, "test");
            Assert.assertFalse(off.tape.has("loop_guard"), "a guard-off tape is unchanged");
            Assert.assertFalse(off.end.has("loop_guard_hits"));
            if (p.tape.get("loop_guard_hits").getAsInt() == 0) {
                Assert.assertEquals(p.tape.get("digest"), off.tape.get("digest"), "no hit: the guard-off game");
            }
            tapes.add(p.tape);
            tapes.add(off.tape);
        }
        System.err.println("[loop-guard bench] games " + games + ", guard hits " + hits);
        // replayOne by actors whose own loopGuard is the other value: each tape replays with its own guard
        final Path f = Files.createTempFile("lg-tapes-", ".jsonl");
        final StringBuilder sb = new StringBuilder();
        for (JsonObject t : tapes) sb.append(t).append('\n');
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        final Map<String, List<JsonObject>> cache = new HashMap<>();
        cache.put(f.toString(), tapes);
        for (int k = 0; k < 2; k++) {
            final RlActorBench.Cfg rc = RlActorBenchTest.cfg("replay");
            rc.loopGuard = k == 0 ? 0 : 5;
            rc.replayTapes.add(f.toString());
            for (int i = 0; i < tapes.size(); i++) {
                if (!tapes.get(i).get("void").isJsonNull()) {
                    continue;
                }
                final JsonObject row = RlActorBench.replayOne(rc, cache, new int[] {0, i},
                        new RlFeaturizer(RlActorBenchTest.index), "test");
                Assert.assertFalse(row.has("error"), String.valueOf(row));
                Assert.assertEquals(row.get("digest_replay"), row.get("digest_tape"),
                        "replay digest of " + row.get("game_uid") + " (actor loopGuard " + rc.loopGuard + ")");
            }
        }
        Files.deleteIfExists(f);
        checker.close();
        Assert.assertEquals(checker.badFrames.get(), 0L, String.valueOf(checker.problems));
    }

}
