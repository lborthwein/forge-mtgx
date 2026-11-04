package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import forge.ai.AiWork;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFeaturizer;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.WorkMeter;
import forge.item.PaperCard;

/**
 * Lane void-causes-1104: (1) a per-game crash records its WHOLE stack (CrashStack: every frame, JDK frames included,
 * causes, suppressed; a frameless HotSpot fast throw is flagged); (2) the hang watchdog (hangWork, a host key, off by
 * default) ends a game whose thread spends its budget of engine work units with no live priority decision, voids it
 * "hang" with the stack of where it was, and changes nothing while it does not trip.
 */
public class VoidCausesWatchdogTest {

    static Path root, bank;
    static CardIndex index;
    static JsonObject game;

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBench.ensureBooted();
        final Path decks = forge.bench.rl.RlWireSchemaTest.res().resolve("decks");
        root = Files.createTempDirectory("rl-vc-root-");
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
        final String c = "forge"; // plain Forge AI (its work ticks: priority steps, SBA checks, AI work)
        game = RlFakeRun.schedule(bank, 0, 1, 727100000L, new String[] {c, c}, false, false, 6000L).get(0);
        game.addProperty("fair_naming", true);
        game.addProperty("obs_schema", 2);
    }

    static RlActorBench.Played play(final long hangWork) {
        final RlActorBench.Cfg c = new RlActorBench.Cfg();
        c.mode = "train";
        c.rlRoot = root.toString();
        c.aiTimeoutSec = 600;
        c.obsSchema = 2;
        c.hangWork = hangWork;
        final RlActorBench.Played p = RlActorBench.play(c, "train", game, new RlFeaturizer(index),
                RlActorBench.nullRecorder(), null, "test");
        Assert.assertNull(p.guardError, String.valueOf(p.guardError));
        return p;
    }

    // ------------------------------------------------------------------ (1) stacks

    static RuntimeException deep(final int n) {
        if (n == 0) {
            return new IllegalStateException("bottom", new NullPointerException("cause"));
        }
        return deep(n - 1);
    }

    @Test
    public void crashStackKeepsEveryFrameAndCause() {
        final RuntimeException e = deep(40);
        e.addSuppressed(new IllegalArgumentException("also"));
        final JsonObject rec = CrashStack.json(e);
        Assert.assertEquals(rec.get("exception").getAsString(), e.toString());
        Assert.assertEquals(rec.getAsJsonArray("frames").size(), e.getStackTrace().length); // JDK frames included
        Assert.assertTrue(rec.getAsJsonArray("frames").size() > 41);
        Assert.assertFalse(rec.get("frames_omitted").getAsBoolean());
        Assert.assertEquals(rec.getAsJsonArray("caused_by").get(0).getAsJsonObject().get("exception").getAsString(),
                "java.lang.NullPointerException: cause");
        Assert.assertEquals(rec.getAsJsonArray("suppressed").size(), 1);
        final String text = CrashStack.text("> ", rec);
        Assert.assertTrue(text.contains("> Caused by: java.lang.NullPointerException: cause"), text);
        Assert.assertTrue(text.contains("> Suppressed: java.lang.IllegalArgumentException: also"), text);
    }

    @Test
    public void framelessFastThrowIsFlagged() {
        final NullPointerException npe = new NullPointerException();
        npe.setStackTrace(new StackTraceElement[0]); // what C2's preallocated fast-throw NPE looks like
        final JsonObject rec = CrashStack.json(npe);
        Assert.assertTrue(rec.get("frames_omitted").getAsBoolean());
        Assert.assertEquals(rec.getAsJsonArray("frames").size(), 0);
        Assert.assertTrue(rec.has("omit_fast_throw")); // HotSpot: the flag's value is recorded
        if (rec.get("omit_fast_throw").getAsBoolean()) {
            Assert.assertTrue(CrashStack.text("", rec).contains("-XX:-OmitStackTraceInFastThrow"));
        }
    }

    // ------------------------------------------------------------------ (2) the meter

    @Test
    public void meterTripsWithoutProgressAndStaysTripped() {
        final WorkMeter m = WorkMeter.open(5);
        try {
            for (int k = 0; k < 3; k++) {
                for (int i = 0; i < 5; i++) {
                    WorkMeter.tick("t");
                }
                m.progress(); // 5 units then progress: never over 5
            }
            Assert.assertFalse(m.tripped());
            for (int i = 0; i < 5; i++) {
                WorkMeter.tick("t");
            }
            try {
                WorkMeter.tick("sixth");
                Assert.fail("no trip");
            } catch (WorkMeter.Hung h) {
                Assert.assertEquals(h.work, 6);
                Assert.assertEquals(h.site, "sixth");
            }
            m.progress();
            try {
                WorkMeter.tick("after");
                Assert.fail("not sticky");
            } catch (WorkMeter.Hung h) {
                Assert.assertEquals(h.site, "sixth");
            }
            Assert.assertEquals(m.summary().get("max_since_progress"), 6L);
        } finally {
            WorkMeter.close();
        }
        WorkMeter.tick("closed"); // no meter: a no-op
        Assert.assertNull(WorkMeter.open(0));
    }

    @Test
    public void meterIsCarriedToTheAiEvalThread() throws Exception {
        final WorkMeter m = WorkMeter.open(1000);
        try {
            final Runnable r = AiWork.carried(() -> {
                for (int i = 0; i < 7; i++) {
                    WorkMeter.tick("eval");
                }
            });
            final Thread t = new Thread(r);
            t.start();
            t.join();
            Assert.assertEquals(m.summary().get("total"), 7L);
        } finally {
            WorkMeter.close();
        }
    }

    // ------------------------------------------------------------------ (2) in a game

    static String masked(final JsonObject tape) {
        final JsonObject t = tape.deepCopy();
        t.remove("wall_ms");
        t.remove("cpu_ms");
        return BvwDecisionLogTest.masked(t);
    }

    @Test
    public void untrippedMeterChangesNothingAndTrippedOneVoidsHang() {
        final RlActorBench.Played off = play(0);
        Assert.assertFalse(off.end.has("hang_meter"));
        Assert.assertTrue(off.end.get("void").isJsonNull(), String.valueOf(off.end.get("void")));
        final RlActorBench.Played big = play(Long.MAX_VALUE / 4); // on, never trips
        Assert.assertTrue(big.end.has("hang_meter"));
        Assert.assertEquals(masked(big.tape), masked(off.tape));
        Assert.assertEquals(big.end.get("digest"), off.end.get("digest"));
        final long max = big.end.getAsJsonObject("hang_meter").get("max_since_progress").getAsLong();
        Assert.assertTrue(max > 2, "max " + max);

        // a budget below the game's biggest decision: the game stops there, void "hang", with where it was
        final RlActorBench.Played hung = play(max - 1);
        Assert.assertEquals(hung.end.get("void").getAsString(), "hang");
        final JsonObject hm = hung.end.getAsJsonObject("hang_meter");
        Assert.assertEquals(hm.get("max_since_progress").getAsLong(), max);
        Assert.assertTrue(hm.has("tripped_at"));
        final JsonObject crash = hung.end.getAsJsonObject("crash");
        Assert.assertTrue(crash.get("exception").getAsString().startsWith("forge.game.WorkMeter$Hung"), crash.toString());
        boolean inEngine = false;
        for (JsonElement f : crash.getAsJsonArray("frames")) {
            inEngine |= f.getAsString().startsWith("forge.game.WorkMeter.tick");
        }
        Assert.assertTrue(inEngine, crash.toString());
        // the same budget, the same stop (work units, not time)
        final RlActorBench.Played again = play(max - 1);
        Assert.assertEquals(again.end.get("void").getAsString(), "hang");
        Assert.assertEquals(again.end.get("turns"), hung.end.get("turns"));
        Assert.assertEquals(again.end.getAsJsonObject("hang_meter").get("tripped_at"), hm.get("tripped_at"));
    }
}
