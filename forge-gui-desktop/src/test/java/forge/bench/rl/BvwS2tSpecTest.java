package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lane bvw-step2-fork-1027 (F6): the S2T search block. Its play-out keys (rlsearch {@code opponent}, {@code leafSym},
 * belief {@code codraft} with its pinned table) and its {@code target} variant and {@code opr} reference are in the
 * search identity; {@code smoke} is not. Every block is refused unless honest (no Forge AI seat in an S2T play-out,
 * the variant's leaf, the prior's pin) and explicit (any S2T key needs a target). A step-1 block's identity is
 * unchanged (the step-1 golden). Parsing only.
 */
public class BvwS2tSpecTest {

    public static final String PRIOR_SHA = "70521d1fdc88fb134fb34e61e9c41fbf0bcabb6538a7d96c93aacbc205967613";

    /** The step-1 test block ({@link BvwStepSearchSpecTest#good}) as S2T T-sym with OPR, the smoke's spec. */
    public static JsonObject s2t() {
        final JsonObject b = BvwStepSearchSpecTest.good();
        final JsonObject rs = b.getAsJsonObject("rlsearch");
        rs.addProperty("opponent", "policy");
        rs.addProperty("leafSym", true);
        rs.addProperty("belief", "codraft");
        rs.addProperty("beliefPrior", PlanSearch.DEFEND_PRIOR);
        rs.addProperty("beliefPriorSha256", PRIOR_SHA);
        rs.addProperty("beliefPriorGain", 20);
        b.add("target", JsonParser.parseString("{\"variant\":\"T-sym\",\"k\":8}"));
        b.add("opr", JsonParser.parseString("{\"k\":4,\"maxTurns\":12,\"maxSteps\":20000}"));
        return b;
    }

    /** sha256 of the identity of {@link #s2t()} at band [3, 5) (mtgx bvw.search_identity must give the same). */
    public static final String GOLDEN_S2T_SHA = "7b31e66c61eda0b28011da807b77c875f71e9b0ff37b9f7ca233565f68ed2f79";

    static StepSearch.Spec parse(final JsonObject b) {
        return BvwStepSearchSpecTest.parse(b);
    }

    @Test
    public void theS2tBlockParsesWithItsIdentity() {
        final StepSearch.Spec s = parse(s2t());
        Assert.assertTrue(s.s2t());
        Assert.assertEquals(s.variant, "T-sym");
        Assert.assertEquals(s.trueK, 8);
        Assert.assertEquals(s.oprK, 4);
        Assert.assertEquals(s.oprTurns, 12);
        Assert.assertEquals(s.oprSteps, 20000);
        Assert.assertFalse(s.smoke);
        System.err.println("[s2t-spec] identity " + RlWire.canonicalString(s.identity()) + " sha " + s.sha());
        Assert.assertEquals(s.sha(), GOLDEN_S2T_SHA, "the cross-language golden");
        Assert.assertTrue(s.identity().has("target") && s.identity().has("opr"));
        Assert.assertEquals(s.identity().getAsJsonObject("rlsearch").get("opponent").getAsString(), "policy");
        // smoke is not in the identity: the smoke's rows are the main run's for the same variant
        final JsonObject smoke = s2t();
        smoke.addProperty("smoke", true);
        final StepSearch.Spec sm = parse(smoke);
        Assert.assertTrue(sm.smoke);
        Assert.assertEquals(sm.sha(), s.sha());
        Assert.assertFalse(sm.identity().has("smoke"));
        // the variant, its k and OPR are
        final JsonObject k4 = s2t();
        k4.getAsJsonObject("target").addProperty("k", 4);
        Assert.assertNotEquals(parse(k4).sha(), s.sha());
        final JsonObject noOpr = s2t();
        noOpr.remove("opr");
        Assert.assertNotEquals(parse(noOpr).sha(), s.sha());
        Assert.assertEquals(parse(noOpr).oprK, 0);
        final JsonObject h = s2t();
        h.add("target", JsonParser.parseString("{\"variant\":\"H-sym\"}"));
        final StepSearch.Spec hs = parse(h);
        Assert.assertEquals(hs.trueK, 0, "H-sym runs no T re-play");
        Assert.assertNotEquals(hs.sha(), s.sha());
        h.addProperty("smoke", true);
        Assert.assertEquals(parse(h).trueK, StepSearch.Spec.SMOKE_TRUE_K, "a smoke run re-plays T for every variant");
        final JsonObject ha = s2t();
        ha.add("target", JsonParser.parseString("{\"variant\":\"H-asym\"}"));
        ha.getAsJsonObject("rlsearch").addProperty("leafSym", false);
        Assert.assertEquals(parse(ha).variant, "H-asym");
        // a step-1 block: no S2T, its identity unchanged
        final StepSearch.Spec one = parse(BvwStepSearchSpecTest.good());
        Assert.assertFalse(one.s2t());
        Assert.assertEquals(one.sha(), BvwStepSearchSpecTest.GOLDEN_SHA);
        Assert.assertFalse(one.identity().has("target"));
    }

    interface Mut {
        void apply(JsonObject b);
    }

    @Test
    public void everyDishonestOrImplicitS2tBlockIsRefused() {
        final Mut[] muts = {
            // any S2T key needs an explicit target variant
            b -> b.remove("target"),
            b -> {
                b.remove("target");
                b.remove("opr");
                b.getAsJsonObject("rlsearch").addProperty("leafSym", false);
                b.getAsJsonObject("rlsearch").addProperty("belief", "uniform");
                b.getAsJsonObject("rlsearch").remove("beliefPrior");
                b.getAsJsonObject("rlsearch").remove("beliefPriorSha256");
            },
            // the honesty refusal: no Forge AI seat in an S2T play-out (above all none in a true world)
            b -> b.getAsJsonObject("rlsearch").addProperty("opponent", "forge"),
            b -> b.getAsJsonObject("rlsearch").remove("opponent"),
            // the variant's leaf
            b -> b.getAsJsonObject("rlsearch").addProperty("leafSym", false),
            b -> b.add("target", JsonParser.parseString("{\"variant\":\"H-asym\"}")),
            // the variant itself
            b -> b.add("target", JsonParser.parseString("{\"variant\":\"T-asym\",\"k\":8}")),
            b -> b.add("target", JsonParser.parseString("{\"variant\":\"T-sym\"}")),
            b -> b.add("target", JsonParser.parseString("{\"variant\":\"T-sym\",\"k\":0}")),
            b -> b.add("target", JsonParser.parseString("{\"variant\":\"H-sym\",\"k\":8}")),
            b -> b.add("target", JsonParser.parseString("{\"variant\":\"T-sym\",\"k\":8,\"x\":1}")),
            b -> b.addProperty("target", "T-sym"),
            // OPR
            b -> b.add("opr", JsonParser.parseString("{\"k\":4,\"maxTurns\":12}")),
            b -> b.add("opr", JsonParser.parseString("{\"k\":0,\"maxTurns\":12,\"maxSteps\":20000}")),
            b -> b.add("opr", JsonParser.parseString("{\"k\":4,\"maxTurns\":12,\"maxSteps\":20000,\"leaf\":1}")),
            // the co-draft prior: its pin, its table, only with codraft
            b -> b.getAsJsonObject("rlsearch").addProperty("beliefPriorSha256", "0".repeat(64)),
            b -> b.getAsJsonObject("rlsearch").remove("beliefPriorSha256"),
            b -> b.getAsJsonObject("rlsearch").addProperty("beliefPrior", "classpath:/forge/ai/simulation/nope.json"),
            b -> b.getAsJsonObject("rlsearch").addProperty("belief", "uniform"),
            b -> b.getAsJsonObject("rlsearch").addProperty("beliefPriorGain", -1),
            // leafSym needs the value leaf, opponent policy the policy play-outs
            b -> b.getAsJsonObject("rlsearch").addProperty("leaf", "static"),
            b -> b.getAsJsonObject("rlsearch").addProperty("playout", "forge"),
            b -> b.getAsJsonObject("rlsearch").addProperty("opponent", "human"),
            // step-3 hygiene: S2T blocks draw from the whole pool
            b -> b.getAsJsonObject("rlsearch").addProperty("beliefSingleton", true),
        };
        for (int i = 0; i < muts.length; i++) {
            final JsonObject b = s2t();
            muts[i].apply(b);
            final int ii = i;
            Assert.expectThrows(IllegalArgumentException.class, () -> parse(b));
            Assert.assertTrue(ii >= 0);
        }
        // a step-1 block with smoke or OPR alone is refused (they are S2T keys)
        final JsonObject smoke = BvwStepSearchSpecTest.good();
        smoke.addProperty("smoke", true);
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(smoke));
        final JsonObject opr = BvwStepSearchSpecTest.good();
        opr.add("opr", JsonParser.parseString("{\"k\":4,\"maxTurns\":12,\"maxSteps\":20000}"));
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(opr));
        final JsonObject opp = BvwStepSearchSpecTest.good();
        opp.getAsJsonObject("rlsearch").addProperty("opponent", "policy");
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(opp));
    }
}
