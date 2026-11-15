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
    public void theJarReadsBooleansStrictly() {
        // the play host passes true|false only (lborthwein/mtgx#1896); the jar also takes 1|0, on|off, yes|no
        for (String v : new String[] {"true", "1", "on", "yes"}) {
            Assert.assertTrue(scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOpp=" + v).redealOpp, v);
            Assert.assertTrue(scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOwnOrder=" + v).redealOwnOrder, v);
        }
        for (String v : new String[] {"false", "0", "off", "no"}) {
            Assert.assertFalse(scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOpp=" + v).redealOpp, v);
            Assert.assertFalse(scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOwnOrder=" + v).redealOwnOrder, v);
        }
        for (String v : new String[] {"2", "maybe", "TRUE", "On", "0.5", "-1"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOpp=" + v));
            Assert.assertThrows(IllegalArgumentException.class, () -> scorer(OwnerMisplaysSpecTest.SHIP + ",choiceScorer.redealOwnOrder=" + v));
        }
    }
}
