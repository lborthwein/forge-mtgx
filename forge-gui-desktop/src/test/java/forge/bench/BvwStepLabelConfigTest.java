package forge.bench;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.HonestLabel;

/** Lane bvw-step1-build-1024: the replay label block's band and search keys. Parsing only; no game. */
public class BvwStepLabelConfigTest {
    static JsonObject good() {
        return forge.bench.rl.BvwStepSearchSpecTest.good();
    }

    /** The replay config refuses a search block over an ms proof spec, and a band with turns. */
    @Test
    public void theReplayConfigRefusesMixedBudgetsAndWindows() {
        final JsonObject label = JsonParser.parseString("{\"redraw\":\"belief\",\"out\":\"/x\",\"band\":[3,5],"
                + "\"worlds\":8,\"lossWallMs\":0,\"sequentialAi\":true,\"aiTimeoutSec\":600,"
                + "\"solverCfg\":{\"hideOwnLibrary\":\"true\"},\"lossCfg\":{\"hideOwnLibrary\":\"true\"}}").getAsJsonObject();
        label.add("search", good());
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("aiTimeoutSec", 600);
        cfg.add("label", label);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(cfg)); // ms proof spec + search
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            label.getAsJsonObject(k).addProperty("budget", "counter");
        }
        final RlActorBench.Cfg ok = RlActorBench.parse(cfg);
        Assert.assertNotNull(ok);
        Assert.assertEquals(ok.labelSearch.bandLo, 3);
        Assert.assertEquals(ok.labelSearch.bandHi, 5);
        // review #3: the run's Forge AI timeout must be the pinned one
        final JsonObject slow = cfg.deepCopy();
        slow.addProperty("aiTimeoutSec", 5);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(slow));
        final JsonObject both = cfg.deepCopy();
        both.getAsJsonObject("label").addProperty("turns", 3);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(both));
        Assert.assertTrue(HonestLabel.Spec.parse(label).counterBudgets());
    }

    @Test
    public void aBandNeedsASearchBlock() {
        // review #14: a band without a search block would write step-0 rows outside step 0's window: refused
        final JsonObject label = JsonParser.parseString("{\"redraw\":\"belief\",\"out\":\"/x\",\"band\":[3,5],"
                + "\"solverCfg\":{\"hideOwnLibrary\":\"true\"},\"lossCfg\":{\"hideOwnLibrary\":\"true\"}}").getAsJsonObject();
        final JsonObject cfg = new JsonObject();
        cfg.add("label", label);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(cfg));
        final JsonObject bad = cfg.deepCopy();
        bad.getAsJsonObject("label").add("band", JsonParser.parseString("[4,4]"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(bad));
        // the step-0 window is the turns key (unchanged)
        final JsonObject t = cfg.deepCopy();
        t.getAsJsonObject("label").remove("band");
        t.getAsJsonObject("label").addProperty("turns", 3);
        Assert.assertEquals(RlActorBench.parse(t).labelTurns, 3);
    }

    /** The smoke's and the main run's step-1 proof spec (smoke/run-cfg-f1a.json's label block without host keys). */
    static final String STEP1_SPEC = "{\"aiTimeoutSec\":600,\"lossCfg\":{\"budget\":\"counter\",\"budgetMs\":\"2000\","
            + "\"cycles\":\"true\",\"engineCards\":\"12500\",\"engineMs\":\"750\",\"enginePathCards\":\"20000\","
            + "\"engineSteps\":\"500\",\"evalReserveMs\":\"500\",\"evalSteps\":\"500\",\"hideOwnLibrary\":\"true\","
            + "\"wallGraceMs\":\"500\"},\"lossWallMs\":0,\"meter\":true,\"redraw\":\"belief\",\"seed\":747291000,"
            + "\"sequentialAi\":true,\"solverCfg\":{\"budget\":\"counter\",\"budgetMs\":\"4000\",\"cycles\":\"true\","
            + "\"engineCards\":\"25000\",\"engineMs\":\"2000\",\"enginePathCards\":\"20000\",\"engineSteps\":\"1000\","
            + "\"evalReserveMs\":\"1000\",\"evalSteps\":\"1250\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"1000\"},"
            + "\"worlds\":8}";
    /** Its normalized spec_sha: the JVM's in every smoke row, and mtgx label_spec's (tools/ml/rl/tests/test_bvw_step1). */
    static final String STEP1_SPEC_SHA = "84119bc7196d266612a945229499dcfa8c7485d9badfbff90ae096e5d82354b9";

    /** Review-align #1: the trajectory check's keys are host keys: spec_sha is the same with and without them (golden). */
    @Test
    public void referenceKeysAreOutsideSpecSha() {
        final JsonObject plain = JsonParser.parseString(STEP1_SPEC).getAsJsonObject();
        Assert.assertEquals(HonestLabel.Spec.parse(plain).sha(), STEP1_SPEC_SHA);
        final JsonObject withRef = plain.deepCopy();
        withRef.add("reference", JsonParser.parseString("[\"/x/TRAIN.jsonl.gz\",\"/x/HELD-OUT-GAMES.jsonl.gz\"]"));
        withRef.addProperty("referenceTurns", 3);
        withRef.addProperty("out", "/x");
        withRef.add("band", JsonParser.parseString("[3,5]"));
        Assert.assertEquals(HonestLabel.Spec.parse(withRef).sha(), STEP1_SPEC_SHA);
        final JsonObject other = withRef.deepCopy();
        other.add("reference", JsonParser.parseString("[\"/y/other.jsonl\"]"));
        other.addProperty("referenceTurns", 1);
        Assert.assertEquals(HonestLabel.Spec.parse(other).sha(), STEP1_SPEC_SHA);
        // a spec key still moves it
        final JsonObject seed = plain.deepCopy();
        seed.addProperty("seed", 747291001);
        Assert.assertNotEquals(HonestLabel.Spec.parse(seed).sha(), STEP1_SPEC_SHA);
    }

    static JsonObject step1Cfg() {
        final JsonObject label = JsonParser.parseString(STEP1_SPEC).getAsJsonObject();
        label.addProperty("out", "/x");
        label.add("band", JsonParser.parseString("[3,5]"));
        label.add("search", good());
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("aiTimeoutSec", 600);
        cfg.add("label", label);
        return cfg;
    }

    /** Review-align #5: the reference keys' own refusals (label and exileScan blocks). */
    @Test
    public void theReferenceKeysAreChecked() {
        final JsonObject ok = step1Cfg();
        ok.getAsJsonObject("label").add("reference", JsonParser.parseString("[\"/x/r.jsonl\"]"));
        final RlActorBench.Cfg c = RlActorBench.parse(ok);
        Assert.assertEquals(c.reference, java.util.List.of("/x/r.jsonl"));
        Assert.assertEquals(c.referenceTurns, 3);
        final JsonObject noRef = step1Cfg();
        noRef.getAsJsonObject("label").addProperty("referenceTurns", 3); // without reference
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(noRef));
        final JsonObject empty = step1Cfg();
        empty.getAsJsonObject("label").add("reference", new com.google.gson.JsonArray());
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(empty));
        final JsonObject zero = ok.deepCopy();
        zero.getAsJsonObject("label").addProperty("referenceTurns", 0);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(zero));
        // the exile scan takes the same keys, and nothing else
        final JsonObject x = JsonParser.parseString("{\"exileScan\":{\"turns\":3,\"out\":\"/x\",\"reference\":[\"/x/r\"],"
                + "\"referenceTurns\":3}}").getAsJsonObject();
        Assert.assertEquals(RlActorBench.parse(x).reference, java.util.List.of("/x/r"));
        final JsonObject xbad = x.deepCopy();
        xbad.getAsJsonObject("exileScan").addProperty("refrence", "/x");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(xbad));
        final JsonObject xnoRef = x.deepCopy();
        xnoRef.getAsJsonObject("exileScan").remove("reference");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(xnoRef));
    }

    static JsonObject tape(final String c0, final String c1, final boolean generator) {
        final JsonObject t = new JsonObject();
        t.addProperty("game_uid", "2988886846");
        final com.google.gson.JsonArray c = new com.google.gson.JsonArray();
        c.add(c0);
        c.add(c1);
        t.add("controllers", c);
        if (generator) {
            t.add("honest_generator", JsonParser.parseString("[null,{\"spec\":{}}]"));
        }
        return t;
    }

    /**
     * Review-align #2a: a label search on HonestForcedWin (step-0) tapes is refused without a reference, and with a
     * reference window other than step 0's [0, 3); other tapes and step-0 label runs are not concerned.
     */
    @Test
    public void aStep1SearchOnStep0TapesNeedsTheStep0Reference() {
        final java.util.List<JsonObject> hfw = java.util.List.of(tape("forge:HonestForcedWin", "forge:HonestForcedWin", true));
        final RlActorBench.Cfg none = RlActorBench.parse(step1Cfg());
        final String why = RlActorBench.referenceRefusal(none, hfw);
        Assert.assertNotNull(why);
        Assert.assertTrue(why.startsWith("refused:") && why.contains("label.reference"), why);
        final JsonObject withRef = step1Cfg();
        withRef.getAsJsonObject("label").add("reference", JsonParser.parseString("[\"/x/r.jsonl\"]"));
        Assert.assertNull(RlActorBench.referenceRefusal(RlActorBench.parse(withRef), hfw));
        for (int turns : new int[] {1, 2, 4, 5}) {
            final JsonObject w = withRef.deepCopy();
            w.getAsJsonObject("label").addProperty("referenceTurns", turns);
            final String no = RlActorBench.referenceRefusal(RlActorBench.parse(w), hfw);
            Assert.assertNotNull(no, "referenceTurns " + turns);
            Assert.assertTrue(no.contains("referenceTurns 3"), no);
        }
        // the counted profile, and a tape known only by its honest_generator seats, are step-0 generator tapes too
        Assert.assertNotNull(RlActorBench.referenceRefusal(none, java.util.List.of(tape("forge:HonestForcedWinCounted",
                "record", false))));
        Assert.assertNotNull(RlActorBench.referenceRefusal(none, java.util.List.of(tape("record", "record", true))));
        // one generator tape among others is enough
        Assert.assertNotNull(RlActorBench.referenceRefusal(none, java.util.List.of(tape("record", "record", false),
                hfw.get(0))));
        // other tapes (the test fixtures: recorders, CubeCombo seats), and a step-0 label run without a search block
        Assert.assertNull(RlActorBench.referenceRefusal(none, java.util.List.of(tape("record", "forge:CubeCombo", false))));
        final JsonObject step0 = step1Cfg();
        step0.getAsJsonObject("label").remove("search");
        step0.getAsJsonObject("label").remove("band");
        step0.getAsJsonObject("label").addProperty("turns", 3);
        Assert.assertNull(RlActorBench.referenceRefusal(RlActorBench.parse(step0), hfw));
    }

    /** Review-align #5c: a game the reference does not know is void (the labeller's own branch; no game needed). */
    @Test
    public void aGameWithoutReferencePositionsWritesNothing() throws Exception {
        final HonestLabel.Spec s = HonestLabel.Spec.parse(RlLabelReplayTest.honest());
        final JsonObject t = new JsonObject();
        t.addProperty("game_uid", "2988886846");
        t.addProperty("turns", 30);
        t.addProperty("mode", "train");
        final forge.bench.rl.RlReplayLabeller lab = new forge.bench.rl.RlReplayLabeller(s, 3, t, "test", null, null, null, 2);
        lab.setReference(null, 3);
        final JsonObject replayRow = new JsonObject();
        Assert.assertEquals(lab.finish(null, true, replayRow), 0);
        Assert.assertFalse(replayRow.get("trajectory_equal").getAsBoolean());
        Assert.assertEquals(replayRow.get("label_rows").getAsInt(), 0);
        // and the same labeller with the game's positions known (none in the window, none replayed): kept
        final forge.bench.rl.RlReplayLabeller ok = new forge.bench.rl.RlReplayLabeller(s, 3, t, "test", null, null, null, 2);
        ok.setReference(new java.util.TreeMap<>(), 3);
        final JsonObject r2 = new JsonObject();
        ok.finish(null, true, r2);
        Assert.assertTrue(r2.get("trajectory_equal").getAsBoolean());
    }
}
