package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiFixes;
import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlSearch;

/**
 * line-own-target-1119: the {@code ownHarm} key (off|shadow|on exactly; absent = off) in the policy seat's search spec
 * ({@link RlLiveSeat.Spec#parse}) and in the K8 look-ahead spec ({@link InteractiveMain#parseLookahead}). Today's live
 * string with the key absent parses to the same search JSON, byte for byte (sha256 pinned from the live jar
 * forge-samename-96168f0929b in {@link LineOwnTargetLiveKeyTest#BASE_JSON_SHA256}), and so with the key explicitly off;
 * on or shadow adds only the key. The K8 seat's enumerated departures are candidates the same rule applies to, so its
 * spec takes the key too, its JSON unchanged when absent.
 */
public class OwnHarmLiveKeyTest {

    @Test
    public void theKeyAbsentOrOffLeavesTheLiveSearchJsonUnchanged() throws Exception {
        final String json = RlLiveSeat.Spec.parse(LineOwnTargetLiveKeyTest.live("")).search.toJson().toString();
        Assert.assertEquals(AttackHoldbackLiveKeyTest.sha256(json), LineOwnTargetLiveKeyTest.BASE_JSON_SHA256);
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(LineOwnTargetLiveKeyTest.live("ownHarm=off"));
        Assert.assertEquals(off.search.ownHarm, "off");
        Assert.assertEquals(off.search.toJson().toString(), json, "explicit off: the same JSON");
        Assert.assertFalse(json.contains("ownHarm"));
    }

    @Test
    public void onOrShadowAddsTheKeyAndNothingElse() throws Exception {
        final String base = RlLiveSeat.Spec.parse(LineOwnTargetLiveKeyTest.live("")).search.toJson().toString();
        for (String v : new String[] {"on", "shadow"}) {
            final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(LineOwnTargetLiveKeyTest.live("ownHarm=" + v));
            Assert.assertEquals(s.search.ownHarm, v);
            final JsonObject j = s.search.toJson();
            Assert.assertEquals(j.get("ownHarm").getAsString(), v);
            j.remove("ownHarm");
            Assert.assertEquals(j.toString(), base, v + " adds the key and nothing else");
        }
        // with lineOwnTarget too (the two keys together, as the replays run them)
        final RlLiveSeat.Spec both = RlLiveSeat.Spec.parse(LineOwnTargetLiveKeyTest.live(
                "plans.solverCfg.lineOwnTarget=true,ownHarm=on"));
        Assert.assertEquals(both.search.ownHarm, "on");
        Assert.assertEquals(both.search.plans.getAsJsonObject("solverCfg").get("lineOwnTarget").getAsString(), "true");
    }

    @Test
    public void badValuesAreRefused() throws Exception {
        for (String bad : new String[] {"1", "0", "true", "false", "yes", "ON", "Shadow", ""}) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> RlLiveSeat.Spec.parse(LineOwnTargetLiveKeyTest.live("ownHarm=" + bad)));
        }
    }

    /** The bench JSON spec carries the key as given, and checks it the same way. */
    @Test
    public void theBenchSpecRoundTrips() throws Exception {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(LineOwnTargetLiveKeyTest.live("ownHarm=on")).search;
        final JsonObject back = c.toJson();
        back.addProperty("server", "127.0.0.1:1");
        Assert.assertEquals(RlSearch.Config.parse(back).ownHarm, "on");
        back.addProperty("ownHarm", "1");
        Assert.expectThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(back));
    }

    @Test
    public void theK8SpecTakesTheKey() {
        final InteractiveMain.ParsedLookahead k8 = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        Assert.assertEquals(k8.config.ownHarm, AiFixes.Mode.OFF);
        Assert.assertFalse(k8.config.toJson().has("ownHarm"));
        final InteractiveMain.ParsedLookahead off = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",ownHarm=off");
        Assert.assertEquals(off.config.toJson().toString(), k8.config.toJson().toString());
        final InteractiveMain.ParsedLookahead on = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",ownHarm=on");
        Assert.assertEquals(on.config.ownHarm, AiFixes.Mode.ON);
        final JsonObject j = on.config.toJson();
        Assert.assertEquals(j.get("ownHarm").getAsString(), "on");
        j.remove("ownHarm");
        Assert.assertEquals(j.toString(), k8.config.toJson().toString(), "on adds the key and nothing else");
        Assert.expectThrows(IllegalArgumentException.class,
                () -> InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",ownHarm=1"));
    }
}
