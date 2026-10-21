package forge.bench;

import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.bench.rl.PlanSearch;
import forge.bench.rl.RlLiveSeat;

/** Lane strong-opp-1020: the live spec's flat {@code plans.defend} / {@code plans.defendCfg.<key>} keys. */
public class StrongOppLiveSpecTest {
    static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String SB = "plans.samples=4,plans.solverCfg.hideOwnLibrary=true";

    static String spec(String extra) throws Exception {
        final Path index = Files.createTempFile("so-index-", ".tsv");
        return "server=127.0.0.1:1,policySha=" + SHA + ",cardIndex=" + index + "," + SB + (extra.isEmpty() ? "" : "," + extra);
    }

    @Test
    public void theLiveDefendKeysBuildTheDefender() throws Exception {
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(spec("plans.defend=true,plans.defendCfg.prior=jar,"
                + "plans.defendCfg.priorGain=20,plans.defendCfg.tieBreakTurns=1,plans.defendCfg.defendMs=6000,"
                + "plans.defendCfg.sharedReader=true,plans.defendCfg.priorFillBasics=true"));
        final JsonObject p = s.search.plans;
        Assert.assertTrue(p.get("defend").getAsBoolean());
        final JsonObject d = p.getAsJsonObject("defendCfg");
        Assert.assertEquals(d.get("prior").getAsString(), PlanSearch.DEFEND_PRIOR);
        Assert.assertEquals(d.get("defendMs").getAsString(), "6000");
        Assert.assertEquals(d.get("sharedReader").getAsString(), "true");
        final PlanSearch.Spec ps = PlanSearch.Spec.parse(p);
        Assert.assertTrue(ps.defend);
        Assert.assertEquals(s.search.belief, "uniform", "live always has the honest belief the defender reads");
    }

    @Test
    public void withoutTheKeysNothingChangesAndBadKeysAreRefused() throws Exception {
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(spec(""));
        Assert.assertFalse(s.search.plans.has("defend"));
        Assert.assertFalse(PlanSearch.Spec.parse(s.search.plans).defend);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("plans.defend=yes")));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("plans.defendCfg.wolrds=4")));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("plans.defendCfg.prior=/tmp/x.json")));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse(spec("plans.defendCfg.defendMs=1000,plans.defendCfg.defendMs=2000")));
        // review S3-2: the wall stays 500..6000 ms live (0 would turn it off)
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("plans.defendCfg.defendMs=0")));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("plans.defendCfg.defendMs=7000")));
        // review S3-6: prior=none is recorded, so a second prior key is refused
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse(spec("plans.defendCfg.prior=none,plans.defendCfg.prior=jar")));
    }
}
