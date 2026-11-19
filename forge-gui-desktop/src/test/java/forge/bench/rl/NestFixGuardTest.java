package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.SearchGuard;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * nestfix-1118: (1) a mulligan ask with reach on and mullKeep.ender off never runs the deck-level hasEnder probe;
 * (2) the search rule: inside a SearchGuard scope on a real game, an ask about that game is refused and counted, an ask
 * about any other game (a search copy) is not, and nothing is refused outside a scope. Card names are test data only.
 */
public class NestFixGuardTest extends AITest {
    private static final String BASE = "server=h:1,cardIndex=x,policySha=" + "a".repeat(64) + ",obs=2,budgetMs=8000,"
            + "threads=8,maxDecisions=4000,loopGuard=3,svcMemo=on,infoState=on,plans.samples=4,"
            + "plans.solverCfg.hideOwnLibrary=true,choiceScorer.budgetMs=1500,reach.gate=true,reach.ms=1000,reach.nodes=2000";

    private Game game() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(1);
        for (int i = 0; i < 10; i++) {
            addCardToZone("Island", me, ZoneType.Library);
        }
        for (int i = 0; i < 20; i++) {
            addCardToZone("Counterspell", me, ZoneType.Library);
        }
        for (String n : new String[] {"Island", "Island", "Swamp", "Brainstorm", "Ponder", "Dark Ritual", "Doomsday"}) {
            addCardToZone(n, me, ZoneType.Hand);
        }
        return g;
    }

    private static RlCandidates.Menu keepMenu() {
        final RlCandidates.Menu m = new RlCandidates.Menu();
        m.family = RlSchema.F_MULLIGAN;
        m.mode = RlSchema.M_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        for (int k : new int[] {RlSchema.K_KEEP, RlSchema.K_MULLIGAN}) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = k;
            m.cands.add(c);
        }
        return m;
    }

    private long probesOnAsk(final RlSearch.Config cfg) {
        final Game g = game();
        final RlSearch s = new RlSearch(cfg, 7L, 11L, null, null, "jar", "t", 2);
        final RlWire.Decide frame = new RlWire.Decide();
        frame.decIdx = 0;
        frame.seat = 1;
        frame.family = RlSchema.F_MULLIGAN;
        final long before = ForcedWinSolver.HAS_ENDER_CALLS.get();
        s.decideChoice(g, g.getPlayers().get(1), keepMenu(), frame, new short[] {1});
        return ForcedWinSolver.HAS_ENDER_CALLS.get() - before;
    }

    @Test
    public void mulliganAskWithReachOnAndEnderOffNeverProbes() {
        final RlSearch.Config off = RlLiveSeat.Spec.parse(BASE + ",mullKeep=on").search;
        Assert.assertNotNull(off.reach, "the test's string has reach on");
        Assert.assertEquals(probesOnAsk(off), 0L);
        // the ender key still probes (once a game), as before
        final JsonObject spec = off.toJson();
        final JsonObject mk = new JsonObject();
        mk.addProperty("ender", true);
        spec.add("mullKeep", mk);
        spec.addProperty("server", "h:1");
        Assert.assertEquals(probesOnAsk(RlSearch.Config.parse(spec)), 1L);
    }

    @Test
    public void realGameAsksAreRefusedInsideAScopeOnly() {
        final Game real = game();
        final Game copy = game();
        SearchGuard.check(real, "outside"); // no scope: nothing refused
        SearchGuard.enter(real);
        try {
            SearchGuard.check(copy, "a copy"); // another game: never refused
            try {
                SearchGuard.check(real, "tape:choosePermanentsToSacrifice");
                Assert.fail("a real-seat ask inside the search was not refused");
            } catch (SearchGuard.RealSeatAsk expected) {
                // the branch ends here
            }
            SearchGuard.enter(real); // nested scopes
            SearchGuard.exit(real);
            Assert.assertTrue(SearchGuard.active(real));
        } finally {
            SearchGuard.exit(real);
        }
        Assert.assertFalse(SearchGuard.active(real));
        SearchGuard.check(real, "after"); // scope closed
        Assert.assertEquals(SearchGuard.takeBlocked(real), 1L);
        Assert.assertEquals(SearchGuard.takeLast(real), "tape:choosePermanentsToSacrifice");
        Assert.assertEquals(SearchGuard.takeBlocked(real), 0L);
        Assert.assertEquals(SearchGuard.takeBlocked(copy), 0L);
        // note(): counted inside a scope only, never refused
        SearchGuard.note(real, "tape:chooseSingleStaticAbility");
        SearchGuard.enter(real);
        try {
            SearchGuard.note(real, "tape:chooseSingleStaticAbility");
            SearchGuard.note(copy, "tape:chooseSingleStaticAbility");
        } finally {
            SearchGuard.exit(real);
        }
        Assert.assertEquals(SearchGuard.takeSeen(real), 1L);
        Assert.assertEquals(SearchGuard.takeSeen(copy), 0L);
    }
}
