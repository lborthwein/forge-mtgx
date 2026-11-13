package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * owner-misplays-1113: review set G17 end to end. The solver seat (the forced-win profile, its solver configured by
 * {@code -Dforge.ai.forcedWin.defaults=...,engineModeChoice=true}) is put in the position after the recast spell resolved
 * and plays on: it must find the kill and FOLLOW it, and the follow must cast the modal spell as the proof had it (the
 * drain mode alone, no additional discard), so that the finisher is still in hand and is cast at storm 6 for exactly
 * lethal. A follow that lets Forge AI pick the modes again escalates and discards the finisher (what the live pilot's
 * policy did in the recorded game). Without the property the test does nothing (it needs the whole JVM's solver config).
 */
public class OwnerMisplaysFollowTest extends forge.ai.AITest {

    @Test
    public void g17TheFollowedLineCastsTheModalSpellPlainAndTheFinisherIsStillThere() {
        final String d = System.getProperty("forge.ai.forcedWin.defaults");
        if (d == null || !d.contains("engineModeChoice=true")) {
            System.out.println("OWNERMISPLAYS follow skipped: no -Dforge.ai.forcedWin.defaults with engineModeChoice=true");
            return;
        }
        final Game g = OwnerMisplaysTest.g17(java.util.Arrays.asList("Dark Ritual", "Cabal Ritual", "Yawgmoth's Will"));
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        Assert.assertEquals(opp.getLife(), 16);
        ForcedWinSolverTest.play(g, 7);
        final java.util.List<String> cast = new java.util.ArrayList<>();
        for (Card c : g.getStack().getSpellsCastThisTurn()) {
            cast.add(c.getName());
        }
        System.out.println("OWNERMISPLAYS follow: over=" + g.isGameOver() + " turn=" + g.getPhaseHandler().getTurn() + " opp life=" + opp.getLife()
                + " our life=" + me.getLife() + " cast this turn=" + cast + " hand=" + me.getCardsIn(ZoneType.Hand) + " exile="
                + me.getCardsIn(ZoneType.Exile) + " gy=" + me.getCardsIn(ZoneType.Graveyard));
        Assert.assertTrue(cast.contains("Tendrils of Agony"), "the finisher was cast, not discarded: " + cast);
        Assert.assertTrue(cast.contains("Collective Brutality"), "the modal spell was cast: " + cast);
        Assert.assertTrue(opp.getLife() <= 0, "exactly lethal needs the drain mode alone: opponent at " + opp.getLife());
        Assert.assertTrue(g.isGameOver() && g.getPhaseHandler().getTurn() == 7, "won this turn");
    }
}
