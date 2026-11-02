package forge.ai.simulation;

import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiWork;
import forge.game.Game;
import forge.game.player.Player;

/**
 * rollout-budget-1101 (DESIGN-R3 N4/N9 blocker): Forge AI's evaluation work inside a counted solver decision is counted
 * against the decision's aiWork cap. Over the cap the decision is a cap hit ({@link AiWork#CAP}), never a proof; under
 * it nothing changes. Fixture positions only; none is part of a corpus.
 */
public class AiWorkBudgetTest extends SimulationTest {

    /** Thassa's Oracle with a two-card library (BvwCounterBudgetTest's proven win), against a Prodigal Sorcerer. */
    static Game oracle() {
        final String frame = ForcedWinSolverTest.frame("Thassa's Oracle", "Island;Island", 20, "",
                "Prodigal Sorcerer;Mountain", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island");
        return ForcedWinSolverTest.game("Default", "Default", frame);
    }

    static JsonObject spec(final String aiWork) {
        final JsonObject o = BvwCounterBudgetTest.counterSpec("1");
        if (aiWork != null) {
            for (String k : new String[] {"solverCfg", "lossCfg"}) {
                o.getAsJsonObject(k).addProperty("aiWork", aiWork);
            }
        }
        return o;
    }

    @Test
    public void budgetsCountTicksNestAndStaySpent() {
        Assert.assertNull(AiWork.current());
        AiWork.tick("none"); // no budget open: a no-op
        final AiWork.Budget prev = AiWork.open(5);
        try {
            final AiWork.Budget outer = AiWork.current();
            AiWork.tick("a", 2);
            final AiWork.Budget p2 = AiWork.open(100);
            try {
                AiWork.tick("b", 3); // charges the outer budget too: 5, at its limit
                Assert.assertFalse(AiWork.spent());
                Assert.assertThrows(AiWork.Exhausted.class, () -> AiWork.tick("c")); // outer: 6 > 5
                Assert.assertTrue(AiWork.spent(), "an inner budget is spent when its outer one is");
                Assert.assertEquals(AiWork.current().spentAt(), "c");
            } finally {
                AiWork.restore(p2);
            }
            Assert.assertSame(AiWork.current(), outer);
            Assert.assertEquals(outer.used(), 6);
            Assert.assertTrue(outer.spent());
            Assert.assertThrows(AiWork.Exhausted.class, () -> AiWork.tick("d")); // sticky
            Assert.assertThrows(AiWork.Exhausted.class, () -> AiWork.check("between calls"));
            // the eval thread hand-off carries the asking thread's budget
            final AtomicReference<AiWork.Budget> seen = new AtomicReference<>();
            final Thread t = new Thread(AiWork.carried(() -> seen.set(AiWork.current())));
            t.start();
            t.join();
            Assert.assertSame(seen.get(), outer);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            AiWork.restore(prev);
        }
        Assert.assertNull(AiWork.current());
        Assert.assertNull(AiWork.open(0), "limit 0 opens nothing");
        Assert.assertNull(AiWork.current());
    }

    @Test
    public void theCapIsOnByDefaultForCountedDecisionsAndUnrecorded() {
        final ForcedWinSolver.Config counted = BvwCounterBudgetTest.fromSpec("budget=counter,defense=false");
        Assert.assertEquals(counted.aiWorkCap(), ForcedWinSolver.Config.DEFAULT_AI_WORK);
        Assert.assertEquals(BvwCounterBudgetTest.fromSpec("defense=false").aiWorkCap(), 0L, "the ms path: no cap");
        Assert.assertEquals(BvwCounterBudgetTest.fromSpec("budget=counter,defense=false,aiWork=-1").aiWorkCap(), 0L);
        Assert.assertEquals(BvwCounterBudgetTest.fromSpec("budget=counter,defense=false,aiWork=77").aiWorkCap(), 77L);
        Assert.assertFalse(HonestGenerator.solverCfgJson(counted).has("aiWork"), "the default is not recorded");
        Assert.assertFalse(counted.toString().contains("aiWork"));
        Assert.assertEquals(HonestGenerator.solverCfgJson(BvwCounterBudgetTest.fromSpec("budget=counter,defense=false,"
                + "aiWork=77")).get("aiWork").getAsLong(), 77L);
        Assert.assertThrows(IllegalArgumentException.class,
                () -> BvwCounterBudgetTest.fromSpec("budget=counter,defense=false,aiWork=lots"));
    }

    @Test(timeOut = 300000)
    public void aForgeAiCallOverTheBudgetIsACapNotAPass() {
        // the eval thread's spent budget comes back to the asking thread as Exhausted (a pass would be a silent
        // decision change); the engine's seat turns it into its cut
        final Game g = oracle();
        final Player opp = g.getPlayers().get(1);
        final ForcedWinEngine.GuardedAi ai = new ForcedWinEngine.GuardedAi(g, opp, opp.getController().getLobbyPlayer());
        final AiWork.Budget prev = AiWork.open(1_000_000_000L);
        final long free;
        try {
            ai.chooseSpellAbilityToPlay();
            free = AiWork.current().used();
        } finally {
            AiWork.restore(prev);
        }
        Assert.assertTrue(free >= 1, "Forge AI weighed the Sorcerer's ping: " + free);
        final AiWork.Budget p2 = AiWork.open(1);
        try {
            AiWork.tick("before", 1); // the budget is at its limit: the first unit inside Forge AI spends it
            Assert.assertThrows(AiWork.Exhausted.class, ai::chooseSpellAbilityToPlay);
            Assert.assertThrows(ForcedWinEngine.OutOfBudget.class, ai::chooseSpellAbilityToPlay);
        } finally {
            AiWork.restore(p2);
        }
    }

    @Test(timeOut = 600000)
    public void aCheapLineIsUnchangedAndAnExpensiveOneIsACap() {
        final Game g = oracle();
        final Player me = g.getPlayers().get(0);
        final String before = LookaheadSearch.fingerprint(g);
        // under the cap (the default, or a huge one) the label is the uncapped one, byte for byte
        final JsonObject off = BvwCounterBudgetTest.label(g, me, spec("-1"));
        final JsonObject dflt = BvwCounterBudgetTest.label(g, me, spec(null));
        final JsonObject huge = BvwCounterBudgetTest.label(g, me, spec("1000000000"));
        Assert.assertEquals(off.get("label").getAsString(), "win", off.toString());
        Assert.assertEquals(dflt.toString(), off.toString(), "the default cap moved a cheap label");
        Assert.assertEquals(huge.toString(), off.toString(), "a huge cap moved a cheap label");
        Assert.assertFalse(dflt.has("cap"), dflt.toString());
        Assert.assertFalse(dflt.getAsJsonObject("meter").has("ai_work_caps"), dflt.toString());
        // over the cap: a named cap hit, never a proof, and the same on every run
        final JsonObject capped = BvwCounterBudgetTest.label(g, me, spec("1"));
        Assert.assertNotEquals(capped.get("label").getAsString(), "win", capped.toString());
        Assert.assertEquals(capped.get("cap").getAsString(), AiWork.CAP, capped.toString());
        Assert.assertTrue(capped.get("cap_hits").getAsInt() >= 1, capped.toString());
        Assert.assertTrue(capped.getAsJsonObject("meter").get("ai_work_caps").getAsLong() >= 1, capped.toString());
        Assert.assertEquals(capped.get("proposals").getAsInt(), 0, capped.toString());
        Assert.assertEquals(BvwCounterBudgetTest.label(g, me, spec("1")).toString(), capped.toString(), "deterministic");
        Assert.assertEquals(LookaheadSearch.fingerprint(g), before, "labelling moved the game");
        Assert.assertNull(AiWork.current(), "no budget left open");
    }

    @Test(timeOut = 900000)
    public void theEngineStopsAtTheCapAndCountsTheSameUnitsEveryRun() {
        // the hang's path: the engine's moves (fast-forwards) ask Forge AI; a Kiki-Jiki loop line
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("Pestermite",
                ForcedWinSolverTest.KIKI_BF, 20, "", "Forest", 20, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinPlanner.Line free = ForcedWinEngine.search(g, me, BvwCounterBudgetTest.loopLimits(0),
                new ForcedWinEngine.Stats(), Long.MAX_VALUE, 7L);
        Assert.assertNotNull(free);
        final long[] used = new long[2];
        for (int run = 0; run < 2; run++) {
            final AiWork.Budget prev = AiWork.open(1_000_000_000L);
            try {
                final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
                final ForcedWinPlanner.Line l = ForcedWinEngine.search(g, me, BvwCounterBudgetTest.loopLimits(0), st,
                        Long.MAX_VALUE, 7L);
                Assert.assertEquals(String.valueOf(l), String.valueOf(free), "an open budget below its cap moved the line");
                used[run] = AiWork.current().used();
            } finally {
                AiWork.restore(prev);
            }
        }
        Assert.assertEquals(used[1], used[0], "units are counts: the same every run");
        Assert.assertTrue(used[0] > 0, "the engine's moves asked Forge AI");
        final AiWork.Budget prev = AiWork.open(Math.max(1, used[0] / 4));
        try {
            final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
            final ForcedWinPlanner.Line l = ForcedWinEngine.search(g, me, BvwCounterBudgetTest.loopLimits(0), st,
                    Long.MAX_VALUE, 7L);
            Assert.assertTrue(AiWork.spent(), "a quarter of the units spends the cap");
            Assert.assertNull(l, "a spent search finds no line: " + st.summary());
        } finally {
            AiWork.restore(prev);
        }
        System.err.println("[ai-work test] kiki engine units " + used[0]);
    }
}
