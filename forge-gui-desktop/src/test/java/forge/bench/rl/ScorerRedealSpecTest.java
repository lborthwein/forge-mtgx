package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * sdcc-scorerleak-1115: the live string's {@code choiceScorer.redealOpp} / {@code choiceScorer.redealOwnOrder} entries
 * reach the card scorer's config (true|false; absent = off), as the play host passes them.
 */
public class ScorerRedealSpecTest {

    private static ChoiceScorer.Config scorer(final String spec) {
        return ChoiceScorer.Config.parse(RlLiveSeat.Spec.parse(spec).search.choiceScorer);
    }

    @Test
    public void absentIsOff() {
        final ChoiceScorer.Config c = scorer(OwnerMisplaysSpecTest.SHIP);
        Assert.assertFalse(c.redealOpp);
        Assert.assertFalse(c.redealOwnOrder);
        Assert.assertFalse(c.toJson().has("redealOpp"));
        Assert.assertFalse(c.toJson().has("redealOwnOrder"));
    }

    @Test
    public void bothKeysParse() {
        final ChoiceScorer.Config c = scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOpp=true,choiceScorer.redealOwnOrder=true");
        Assert.assertTrue(c.redealOpp);
        Assert.assertTrue(c.redealOwnOrder);
        final ChoiceScorer.Config off = scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOpp=false");
        Assert.assertFalse(off.redealOpp);
    }

    @Test
    public void aBadValueIsRefused() {
        for (String v : new String[] {"1", "yes", "on"}) {
            Assert.assertThrows(RuntimeException.class, () -> scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOpp=" + v));
        }
    }
}
