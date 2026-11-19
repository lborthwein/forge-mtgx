package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlSearch;
import forge.bench.rl.SolverDriver;

/**
 * line-own-target-1119: the {@code plans.solverCfg.lineOwnTarget} key in the policy seat's search spec
 * ({@link RlLiveSeat.Spec#parse}). Today's live string (the knowledgeSameName jar's: cb96edb2 plus knowledgeSameName=on;
 * its card index a temp file) with the key absent parses to the same search JSON, byte for byte (its sha256 pinned from
 * the live jar forge-samename-96168f0929b, sha 196b9b84), and so with the key explicitly false; true adds only the key
 * and reaches the solver's config. The K8 look-ahead seat follows no solver line, so its spec has no such key.
 */
public class LineOwnTargetLiveKeyTest {

    /** The live policy-search string of 2026-11-19 (the reported games' journals). */
    static String live(final String extra) throws Exception {
        return KnowledgeSameNameLiveKeyTest.live("knowledgeSameName=on" + (extra.isEmpty() ? "" : "," + extra));
    }

    /** sha256 of the search JSON of {@link #live} with no extra key, on the live jar forge-samename-96168f0929b. */
    static final String BASE_JSON_SHA256 = "90a73ed7befbc356f08ca3b3198967e34ecd30326490710bdf5b7d4d02ef00a2";

    @Test
    public void theLiveStringParsesAsOnTheLiveJar() throws Exception {
        final String json = RlLiveSeat.Spec.parse(live("")).search.toJson().toString();
        final String sha = AttackHoldbackLiveKeyTest.sha256(json);
        System.out.println("[line-own-target-identity] live 1119 search JSON sha256 " + sha + " (" + json.length() + " chars)");
        Assert.assertEquals(sha, BASE_JSON_SHA256, "the key absent leaves the live search JSON unchanged");
    }

    private static JsonObject solverCfg(final RlLiveSeat.Spec s) {
        return s.search.plans.getAsJsonObject("solverCfg");
    }

    /** The solver config the plan search makes from the spec's plans.solverCfg (PlanSearch.Spec#solverConfig), as text. */
    private static String cfg(final RlLiveSeat.Spec s) {
        return SolverDriver.config(solverCfg(s)).toString();
    }

    @Test
    public void theKeyIsOffUnlessSet() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(live(""));
        Assert.assertFalse(solverCfg(off).has("lineOwnTarget"));
        Assert.assertFalse(cfg(off).contains("lineOwnTarget"));
        final RlLiveSeat.Spec explicitOff = RlLiveSeat.Spec.parse(live("plans.solverCfg.lineOwnTarget=false"));
        Assert.assertFalse(cfg(explicitOff).contains("lineOwnTarget"));
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(live("plans.solverCfg.lineOwnTarget=true"));
        Assert.assertEquals(solverCfg(on).get("lineOwnTarget").getAsString(), "true");
        Assert.assertTrue(cfg(on).contains(" lineOwnTarget"),
                "the key reaches the solver's config");
        final JsonObject onJson = on.search.toJson();
        onJson.getAsJsonObject("plans").getAsJsonObject("solverCfg").remove("lineOwnTarget");
        Assert.assertEquals(onJson.toString(), off.search.toJson().toString(), "on adds the key and nothing else");
        for (String bad : new String[] {"on", "1", "0", "yes", "True", ""}) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> RlLiveSeat.Spec.parse(live("plans.solverCfg.lineOwnTarget=" + bad)));
        }
    }

    /** The bench JSON spec carries the key in plans.solverCfg as given. */
    @Test
    public void theBenchSpecRoundTrips() throws Exception {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(live("plans.solverCfg.lineOwnTarget=true")).search;
        final JsonObject back = c.toJson();
        back.addProperty("server", "127.0.0.1:1");
        final RlSearch.Config again = RlSearch.Config.parse(back);
        Assert.assertTrue(SolverDriver.config(again.plans.getAsJsonObject("solverCfg")).toString().contains(" lineOwnTarget"));
    }

    /** The K8 parser ignores keys it does not know: the key arms nothing there and leaves its JSON unchanged. */
    @Test
    public void theK8SpecHasNoSuchKey() {
        final InteractiveMain.ParsedLookahead k8 = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        final InteractiveMain.ParsedLookahead with = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8
                + ",plans.solverCfg.lineOwnTarget=true");
        Assert.assertEquals(with.config.toJson().toString(), k8.config.toJson().toString());
    }
}
