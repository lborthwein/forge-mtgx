package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * discard-guard-1113: the card scorer's departure guard ({@code choiceScorer.departScored}, {@code choiceScorer.departFloor};
 * both absent = off, the scorer as it was). The teacher test found departures to "the best of the options it happened to
 * score" at asks that ran over budget, and departures between two values near 0. Configs and the choice rule only: no
 * game, no card database; the options' values are set as terminal values.
 */
public class DiscardGuardTest {

    static final String SHIP = OwnerMisplaysSpecTest.SHIP + ",choiceScorer.reachDiscards=true,choiceScorer.redealOwn=4";

    private static ChoiceScorer scorer(final String extra) {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(SHIP + extra).search;
        return new ChoiceScorer(ChoiceScorer.Config.parse(c.choiceScorer), null, 2, 1L);
    }

    private static ChoiceScorer.Option opt(final String label, final double v, final boolean policy, final String fail) {
        final ChoiceScorer.Option o = new ChoiceScorer.Option();
        o.label = label;
        o.steps = new short[] {(short) label.length()};
        o.terminal = v;
        o.policy = policy;
        o.fail = fail;
        return o;
    }

    /** The policy's own pick (scored first), a better option, and a third that is scored, dropped for the budget, or failed. */
    private static ChoiceScorer.Scored ask(final double own, final double best, final String thirdFail) {
        final ChoiceScorer.Scored s = new ChoiceScorer.Scored();
        s.options.add(opt("own", own, true, null));
        s.options.add(opt("better", best, false, null));
        s.options.add(opt("third", thirdFail == null ? 0.0 : Double.NaN, false, thirdFail));
        s.overBudget = "budget".equals(thirdFail);
        return s;
    }

    private static short[] choose(final ChoiceScorer sc, final ChoiceScorer.Scored s) {
        return sc.choose(s, new double[0], null);
    }

    @Test
    public void offTheScorerDepartsAsBeforeEvenWithAnUnscoredOption() {
        final ChoiceScorer sc = scorer("");
        Assert.assertEquals(sc.config().departScored, 0);
        Assert.assertEquals(sc.config().departFloor, 0.0);
        Assert.assertFalse(sc.config().toJson().has("departScored"));
        Assert.assertFalse(sc.config().toJson().has("departFloor"));
        final ChoiceScorer.Scored s = ask(0.03, 0.05 + 0.02, "budget");
        Assert.assertNotNull(choose(sc, s), "keys off: the best of the scored options is played, as today");
        Assert.assertNull(s.held);
        Assert.assertEquals(s.chosen.label, "better");
        Assert.assertFalse(sc.row(s, "t").has("held"));
    }

    @Test
    public void anOverBudgetAskKeepsThePolicysAnswer() {
        final ChoiceScorer sc = scorer(",choiceScorer.departScored=1");
        final ChoiceScorer.Scored s = ask(0.10, 0.60, "budget");
        Assert.assertNull(choose(sc, s), "an option was never scored: no departure");
        Assert.assertEquals(s.held, "unscored");
        Assert.assertEquals(s.chosen.label, "own");
        Assert.assertEquals(sc.row(s, "t").get("held").getAsString(), "unscored");
        // the whole menu scored: the same values depart
        final ChoiceScorer.Scored full = ask(0.10, 0.60, null);
        Assert.assertNotNull(choose(sc, full));
        Assert.assertNull(full.held);
        Assert.assertEquals(full.chosen.label, "better");
    }

    @Test
    public void levelOneIsTheBudgetOnlyLevelTwoIsAnyUnscoredOptionOrACutMenu() {
        final ChoiceScorer one = scorer(",choiceScorer.departScored=1"), two = scorer(",choiceScorer.departScored=2");
        Assert.assertNotNull(choose(one, ask(0.10, 0.60, "copy")), "level 1: a copy failure is not the budget");
        final ChoiceScorer.Scored failed = ask(0.10, 0.60, "copy");
        Assert.assertNull(choose(two, failed));
        Assert.assertEquals(failed.held, "unscored");
        final ChoiceScorer.Scored cut = ask(0.10, 0.60, null);
        cut.cut = 2;
        Assert.assertNotNull(choose(one, cut), "level 1: a menu cut by the cap still departs");
        final ChoiceScorer.Scored cut2 = ask(0.10, 0.60, null);
        cut2.cut = 2;
        Assert.assertNull(choose(two, cut2));
        Assert.assertEquals(cut2.held, "unscored");
        Assert.assertNotNull(choose(two, ask(0.10, 0.60, null)), "level 2: a whole scored menu departs");
    }

    @Test
    public void underTheFloorNoDepartureAboveItTheDepartureStands() {
        final ChoiceScorer sc = scorer(",choiceScorer.departFloor=0.1");
        final ChoiceScorer.Scored low = ask(0.01, 0.05, null);
        Assert.assertNull(choose(sc, low), "0.05 against 0.01 in a lost position: noise");
        Assert.assertEquals(low.held, "floor");
        Assert.assertEquals(low.chosen.label, "own");
        final ChoiceScorer.Scored high = ask(0.01, 0.33, null);
        Assert.assertNotNull(choose(sc, high));
        Assert.assertNull(high.held);
        // the floor alone does not look at unscored options
        Assert.assertNotNull(choose(sc, ask(0.01, 0.33, "budget")));
    }

    @Test
    public void theKeysParseLiveKeepTheirBoundsAndAreWrittenOnlyWhenOn() {
        final ChoiceScorer.Config c = scorer(",choiceScorer.departScored=2,choiceScorer.departFloor=0.1").config();
        Assert.assertEquals(c.departScored, 2);
        Assert.assertEquals(c.departFloor, 0.1);
        Assert.assertEquals(c.toJson().get("departScored").getAsInt(), 2);
        Assert.assertEquals(c.toJson().get("departFloor").getAsDouble(), 0.1);
        for (String bad : new String[] {"choiceScorer.departScored=3", "choiceScorer.departScored=-1", "choiceScorer.departFloor=1.5",
                "choiceScorer.departFloor=-0.1"}) {
            try {
                RlLiveSeat.Spec.parse(SHIP + "," + bad);
                Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // departScored 0..2, departFloor 0..1
            }
        }
    }
}
