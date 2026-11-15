package forge.ai.simulation;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-scorerleak-1115 (the residual land-probe reads on the live leak-fixed jar): LeakTrace watches ONE seat per live
 * game, the last one armed. A seat's plan hook (the defence with the opponent's spell on the stack, the line follower)
 * runs before that seat's search arms it, so a probe of the seat's OWN hand (a land played from hand in a land-entry
 * sandbox) was counted as the opponent's real hand. The hook now arms its seat first (RlSearch.armHook). Runs only in a
 * JVM with {@code -Dforge.ai.leakTrace} (LeakTrace reads it once).
 */
public class LeakTraceSeatTest extends forge.ai.AITest {

    private static Game game() {
        return ForcedWinSolverTest.game("Default", "Default", "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\n"
                + "humanlife=20\nailife=20\nhumanlandsplayed=0\nailandsplayed=0\naihand=Forest;Lightning Bolt;Counterspell\n"
                + "ailibrary=" + ForcedWinSolverTest.LIB20 + "\naigraveyard=\naiexile=\naibattlefield=Island\n"
                + "humanhand=Giant Growth;Mountain\nhumanbattlefield=Swamp\nhumangraveyard=\nhumanexile=\nhumanlibrary="
                + ForcedWinSolverTest.OURLIB + "\n");
    }

    /** A land-entry style probe by {@code prober}: a copy of the live game, its own land played from its hand. */
    private static void probeWatchedAsA(final Game live, final Player prober) {
        final Game g = new GameCopier(live, true).makeCopy();
        final Player p = g.getPlayer(prober.getId());
        final Card land = ForcedWinSolverTest.inZone(p, ZoneType.Hand, "Forest");
        g.getAction().moveTo(ZoneType.Battlefield, land, null, null);
    }

    private static void probeWatchedAsB(final Game live, final Player prober) {
        final Game g = new GameCopier(live, true).makeCopy();
        final Player p = g.getPlayer(prober.getId());
        final Card land = ForcedWinSolverTest.inZone(p, ZoneType.Hand, "Forest");
        g.getAction().moveTo(ZoneType.Battlefield, land, null, null);
    }

    @Test
    public void aSeatsOwnHandCountsAsRealOnlyWhileTheOtherSeatIsArmed() {
        if (!LeakTrace.ON) {
            throw new SkipException("needs -Dforge.ai.leakTrace");
        }
        final Game live = game();
        final Player a = live.getPlayers().get(0), b = live.getPlayers().get(1);
        // before the fix: the hook of seat B runs while LeakTrace still watches seat A (who cast the spell)
        LeakTrace.arm(live, a, null);
        probeWatchedAsA(live, b);
        final long[] wrong = LeakTrace.oppCounts("probeWatchedAsA");
        Assert.assertEquals(wrong[1], 1, "B's own hand, read as A's opponent's real hand: " + java.util.Arrays.toString(wrong));
        // with RlSearch.armHook: B's hook arms B first, and B's own hand is not an opponent's
        LeakTrace.arm(live, b, null);
        probeWatchedAsB(live, b);
        final long[] right = LeakTrace.oppCounts("probeWatchedAsB");
        Assert.assertEquals(right[0], 0, "no opposing-hand read: " + java.util.Arrays.toString(right));
        Assert.assertEquals(right[1], 0);
    }
}
