package forge.bench;

import java.util.Collections;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.HonestLabel;
import forge.bench.rl.RlReplayLabeller;

/** Refusals must happen before a game or featurizer is built; no game-playing fixtures. */
public class RlLabelRefusalTest {
    static HonestLabel.Spec honest() {
        JsonObject s = new JsonObject();
        s.addProperty("redraw", "belief");
        JsonObject cfg = new JsonObject();
        cfg.addProperty("hideOwnLibrary", "true");
        s.add("solverCfg", cfg);
        s.add("lossCfg", cfg.deepCopy());
        return HonestLabel.Spec.parse(s);
    }

    static JsonObject tape(String mode) {
        JsonObject t = new JsonObject();
        t.addProperty("game_uid", "1");
        t.addProperty("seed", 1);
        t.addProperty("turns", 9);
        t.addProperty("mode", mode);
        t.addProperty("digest", "test");
        t.add("void", com.google.gson.JsonNull.INSTANCE);
        JsonArray decks = new JsonArray();
        for (String path : new String[] {"/not-opened/data/eval/bank/a.dck", "/not-opened/data/eval/bank/b.dck"}) {
            JsonObject d = new JsonObject();
            d.addProperty("path", path);
            d.addProperty("sha", "not-opened");
            decks.add(d);
        }
        t.add("decks", decks);
        JsonArray ctls = new JsonArray();
        ctls.add("forge");
        ctls.add("forge");
        t.add("controllers", ctls);
        return t;
    }

    @Test
    public void constructorRefusesNonTrainTape() {
        for (String mode : new String[] {"eval", "replay", "unknown"}) {
            IllegalArgumentException e = Assert.expectThrows(IllegalArgumentException.class,
                    () -> new RlReplayLabeller(honest(), 3, tape(mode), "test", null, null, null, 2));
            Assert.assertTrue(e.getMessage().contains("TRAIN"), e.getMessage());
        }
    }

    @Test
    public void replayRefusesEvalBeforeDeckAccess() {
        RlActorBench.Cfg cfg = new RlActorBench.Cfg();
        cfg.labelSpec = honest();
        cfg.replayTapes.add("not-opened");
        JsonObject result = RlActorBench.replayOne(cfg, Map.of("not-opened", Collections.singletonList(tape("eval"))),
                new int[] {0, 0}, null, "test");
        Assert.assertFalse(result.get("equal").getAsBoolean());
        Assert.assertTrue(result.get("error").getAsString().contains("TRAIN"), result.toString());
    }

    @Test
    public void generatorReplayIdentityIncludesConfigButNotTiming() {
        JsonObject row = new JsonObject();
        JsonObject generator = new JsonObject(), spec = new JsonObject();
        spec.addProperty("worlds", 8);
        generator.add("spec", spec);
        generator.addProperty("engine_ms", 1.0);
        JsonArray seats = new JsonArray();
        seats.add(generator);
        seats.add(com.google.gson.JsonNull.INSTANCE);
        row.add("honest_generator", seats);
        JsonObject replay = row.deepCopy();
        replay.getAsJsonArray("honest_generator").get(0).getAsJsonObject().addProperty("engine_ms", 2.0);
        Assert.assertEquals(RlActorBench.generatorSpecs(row), RlActorBench.generatorSpecs(replay));
        replay.getAsJsonArray("honest_generator").get(0).getAsJsonObject().getAsJsonObject("spec")
                .addProperty("worlds", 4);
        Assert.assertNotEquals(RlActorBench.generatorSpecs(row), RlActorBench.generatorSpecs(replay));
        Assert.assertNotEquals(RlActorBench.generatorSpecs(row), RlActorBench.generatorSpecs(new JsonObject()));
    }
}
