package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.simulation.ForcedWinProbe;
import forge.game.Game;
import forge.game.player.Player;

/**
 * sdcc-scorerleak-1115 (review S3 of 676305b1938): every plan-hook entry, not only priority and lineKeep, arms its seat
 * before its body runs (PlanSearch.worked): with choiceScorer.redealOpp the seat's belief re-draw is registered by the
 * first hook call of a game, whichever it is; without the key nothing is registered.
 */
public class PlanHookArmTest extends forge.ai.simulation.SimulationTest {

    private static PlanSearch hook(final String extra, final Player me) {
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(OwnerMisplaysSpecTest.SHIP + extra);
        final RlSearch rs = new RlSearch(s.search, 1L, 2L, null, null, "jar", "t", 2);
        return new PlanSearch(PlanSearch.Spec.parse(s.search.plans), rs, me, null, null);
    }

    private interface Entry {
        void call(PlanSearch ps);
    }

    private void check(final String name, final Entry e) {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0);
        final PlanSearch on = hook(",choiceScorer.redealOpp=true", me);
        Assert.assertFalse(ForcedWinProbe.hasRedraw(g, me), name + ": nothing before the first hook call");
        try {
            e.call(on);
        } catch (RuntimeException ignored) {
            // the hook's own answer is not under test (no line, no stack); the arming happens before it
        }
        Assert.assertTrue(ForcedWinProbe.hasRedraw(g, me), name + ": the hook armed its seat");
        final Game g2 = initAndCreateGame();
        final Player me2 = g2.getPlayers().get(0);
        final PlanSearch off = hook("", me2);
        try {
            e.call(off);
        } catch (RuntimeException ignored) {
            // as above
        }
        Assert.assertFalse(ForcedWinProbe.hasRedraw(g2, me2), name + ": key off, nothing registered");
    }

    @Test
    public void everyHookEntryArmsItsSeat() {
        check("forgeCosts", PlanSearch::forgeCosts);
        check("forgeChoices", PlanSearch::forgeChoices);
        check("look", ps -> ps.look(new forge.game.card.CardCollection()));
        check("confirm", ps -> ps.confirm(null, "?"));
        check("order", ps -> ps.order(new forge.game.card.CardCollection(), forge.game.zone.ZoneType.Library, null));
    }
}
