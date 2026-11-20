package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * sdcc-overbudget-1119: {@code choiceScorer.departBudgetReach} (absent = off, the guard as it was). With the departure
 * guard at level 1, an ask that ran over its budget keeps the policy's answer even when the option the scorer would play
 * was scored and reaches a line the policy's answer does not. The key lets that departure stand when the reach part
 * separates the two; value-only departures, the floor and the seat's own discards keep the guard. Configs and the
 * choice rule only: no game, no card database; options carry terminal values and a screen's reach part.
 */
public class OverBudgetReachTest {

    static final String SHIP = DiscardGuardTest.SHIP + ",choiceScorer.departScored=1";

    private static ChoiceScorer scorer(final String extra) {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(SHIP + extra).search;
        return new ChoiceScorer(ChoiceScorer.Config.parse(c.choiceScorer), null, 2, 1L);
    }

    private static ChoiceScorer.Option opt(final String label, final double v, final double reach, final boolean policy,
            final String fail) {
        final ChoiceScorer.Option o = new ChoiceScorer.Option();
        o.label = label;
        o.steps = new short[] {(short) label.length()};
        o.terminal = v;
        o.policy = policy;
        o.fail = fail;
        if (!Double.isNaN(reach)) {
            o.screen = new forge.ai.simulation.ForcedWinSolver.Screen();
            o.screen.reach = reach;
        }
        return o;
    }

    /** The policy's answer (scored first), a scored option with its own reach and value, and one dropped for the budget. */
    private static ChoiceScorer.Scored overBudget(final double ownV, final double ownR, final double altV, final double altR) {
        final ChoiceScorer.Scored s = new ChoiceScorer.Scored();
        s.options.add(opt("own", ownV, ownR, true, null));
        s.options.add(opt("alt", altV, altR, false, null));
        s.options.add(opt("late", Double.NaN, Double.NaN, false, "budget"));
        s.overBudget = true;
        return s;
    }

    private static short[] choose(final ChoiceScorer sc, final ChoiceScorer.Scored s) {
        return sc.choose(s, new double[0], null);
    }

    @Test
    public void offTheGuardHoldsAReachSeparatedDepartureAsToday() {
        final ChoiceScorer sc = scorer("");
        Assert.assertFalse(sc.config().departBudgetReach);
        Assert.assertFalse(sc.config().toJson().has("departBudgetReach"));
        final ChoiceScorer.Scored s = overBudget(0.03, 0.0, 0.02, 1.0);
        Assert.assertNull(choose(sc, s), "keys off: an over-budget ask keeps the policy's answer");
        Assert.assertEquals(s.held, "unscored");
        Assert.assertNull(s.stood);
        Assert.assertEquals(s.chosen.label, "own");
        Assert.assertFalse(sc.row(s, "t").has("stood"));
    }

    @Test
    public void onAScoredOptionThatReachesALineThePolicyAnswerDoesNotIsPlayed() {
        final ChoiceScorer sc = scorer(",choiceScorer.departBudgetReach=true");
        Assert.assertTrue(sc.config().departBudgetReach);
        Assert.assertTrue(sc.config().toJson().get("departBudgetReach").getAsBoolean());
        final ChoiceScorer.Scored s = overBudget(0.03, 0.0, 0.02, 1.0);
        Assert.assertNotNull(choose(sc, s));
        Assert.assertNull(s.held);
        Assert.assertEquals(s.stood, "reach");
        Assert.assertEquals(s.chosen.label, "alt");
        Assert.assertEquals(sc.row(s, "t").get("stood").getAsString(), "reach");
        // a partial reach (a later turn's goldfish line) against none separates too
        final ChoiceScorer.Scored part = overBudget(0.05, 0.0, 0.05, 0.384);
        Assert.assertNotNull(choose(sc, part));
        Assert.assertEquals(part.chosen.label, "alt");
    }

    @Test
    public void onAValueOnlyDepartureIsStillHeld() {
        final ChoiceScorer sc = scorer(",choiceScorer.departBudgetReach=true");
        final ChoiceScorer.Scored same = overBudget(0.10, 0.384, 0.60, 0.384);
        Assert.assertNull(choose(sc, same), "equal reach: the departure rests on the value alone");
        Assert.assertEquals(same.held, "unscored");
        Assert.assertNull(same.stood);
        final ChoiceScorer.Scored none = overBudget(0.10, Double.NaN, 0.60, Double.NaN);
        Assert.assertNull(choose(sc, none), "no screen at all: no reach part");
        Assert.assertEquals(none.held, "unscored");
        final ChoiceScorer.Scored lower = overBudget(0.10, 0.64, 0.95, 0.384);
        Assert.assertNull(choose(sc, lower), "the option played reaches less than the policy's answer");
        Assert.assertEquals(lower.held, "unscored");
    }

    @Test
    public void onTheMarginAndTheFloorStillApply() {
        final ChoiceScorer sc = scorer(",choiceScorer.departBudgetReach=true,choiceScorer.departFloor=0.1");
        final ChoiceScorer.Scored low = overBudget(0.01, 0.0, 0.03, 0.05);
        Assert.assertNull(choose(sc, low), "the reach winner is worth about 0.07: under the floor");
        Assert.assertEquals(low.held, "floor");
        Assert.assertNull(low.stood);
        final ChoiceScorer.Scored near = overBudget(0.0, 0.0, 0.005, 0.01);
        Assert.assertNull(choose(sc, near), "within the margin: no departure to stand");
        Assert.assertNull(near.held);
        Assert.assertNull(near.stood);
    }

    @Test
    public void onTheSeatsOwnDiscardsKeepTheGuard() {
        final ChoiceScorer sc = scorer(",choiceScorer.departBudgetReach=true");
        final ChoiceScorer.Scored s = overBudget(0.03, 0.0, 0.02, 1.0);
        s.reachDiscards = true;
        Assert.assertNull(choose(sc, s));
        Assert.assertEquals(s.held, "unscored");
        Assert.assertNull(s.stood);
    }

    @Test
    public void onAWholeScoredMenuDepartsAsBeforeAndTheKeyNeedsTheBlend() {
        final ChoiceScorer sc = scorer(",choiceScorer.departBudgetReach=true");
        final ChoiceScorer.Scored full = overBudget(0.10, 0.384, 0.60, 0.384);
        full.options.remove(2);
        full.overBudget = false;
        Assert.assertNotNull(choose(sc, full), "nothing unscored: the guard never held, the key does nothing");
        Assert.assertNull(full.held);
        Assert.assertNull(full.stood);
        final com.google.gson.JsonObject bare = new com.google.gson.JsonObject();
        bare.addProperty("departBudgetReach", true);
        try {
            ChoiceScorer.Config.parse(bare);
            Assert.fail("departBudgetReach without reachAlpha accepted");
        } catch (IllegalArgumentException expected) {
            // the rule reads the reach part, which only the blend computes
        }
        try {
            RlLiveSeat.Spec.parse(SHIP + ",choiceScorer.departBudgetReach=maybe");
            Assert.fail("a non-boolean value accepted");
        } catch (IllegalArgumentException expected) {
            // strict boolean
        }
    }
}
