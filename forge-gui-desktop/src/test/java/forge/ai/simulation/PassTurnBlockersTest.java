package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * blockers-1109 (ledger row 81): the planner's visible clock (ForcedWinPlanner.passTurn) counted every creature of ours
 * as a blocker for the opponent's next turn, including one tapped by our own attack this turn. Their untap step does
 * not untap our permanents, so an attack-then-pass line was planned as surviving a crack-back it does not survive. A
 * blocker now counts only if it can block at their declare-blockers step: untapped then (or able to block tapped) and
 * not prevented from blocking. Engine rules only; no card names in the planner.
 *
 * <p>The frames follow oppturn-1108's traced shape (Doomsday pair 1: a next-turn Oracle line taken at low life against an
 * opponent's board of attackers): our main phase, turn 7, land already played; Thassa's Oracle (UU) and an Island in
 * hand, one Island out, three Islands in the library. The Oracle cannot be cast this turn; next turn (land, Oracle,
 * devotion 2 >= library 2) it wins, so every line passes the turn and must survive the opponent's attack.
 */
public class PassTurnBlockersTest extends forge.ai.AITest {

    private static ForcedWinModel.Position position(String ourCreatures, int life, String theirBattlefield) {
        final String bf = "Island" + (ourCreatures.isEmpty() ? "" : ";" + ourCreatures);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Thassa's Oracle;Island", bf, life, "", theirBattlefield, 20, "human")
                .replace("humanlandsplayed=0", "humanlandsplayed=1")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island;Island"));
        final Player me = g.getPlayers().get(0);
        return new ForcedWinModel.Reader().read(g, me, null, true);
    }

    private static ForcedWinPlanner.Result plan(ForcedWinModel.Position pos) {
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 2;
        lim.ms = -1L; // nodes only: the test does not depend on the host's speed
        return ForcedWinPlanner.plan(pos, lim);
    }

    private static int lifeLeft(ForcedWinModel.Position pos) {
        final ForcedWinPlanner.Result r = plan(pos);
        Assert.assertFalse(r.lines.isEmpty(), "a next-turn line: " + r.summary() + " " + pos.describe());
        Assert.assertEquals(r.lines.get(0).winTurn, 1, r.lines.get(0).toString());
        return r.lines.get(0).lifeLeft;
    }

    private static void noLine(ForcedWinModel.Position pos, String why) {
        final ForcedWinPlanner.Result r = plan(pos);
        Assert.assertTrue(r.lines.isEmpty(), why + ": " + (r.lines.isEmpty() ? "" : r.lines.get(0).toString()) + " "
                + pos.describe());
    }

    @Test
    public void aCreatureTappedByOurAttackIsNoBlocker() {
        // the traced shape: our Bears attacked (tapped), two Bears of theirs (4 damage) against our 3 life. Before the fix
        // the tapped Bears blocked one, the line was planned to leave us at 1, and the crack-back killed us.
        noLine(position("Grizzly Bears|Tapped", 3, "Grizzly Bears;Grizzly Bears"), "the tapped Bears cannot block");
        // the same at 5 life: the line survives at 1 (both of theirs connect), not at 3
        Assert.assertEquals(lifeLeft(position("Grizzly Bears|Tapped", 5, "Grizzly Bears;Grizzly Bears")), 1);
    }

    @Test
    public void anUntappedCreatureStillBlocks() {
        // identity where the bug never fires: our Bears did not attack, so it blocks one of theirs, as before
        Assert.assertEquals(lifeLeft(position("Grizzly Bears", 3, "Grizzly Bears;Grizzly Bears")), 1);
        Assert.assertEquals(lifeLeft(position("Grizzly Bears", 5, "Grizzly Bears;Grizzly Bears")), 3);
    }

    @Test
    public void onlyTheUntappedOnesBlock() {
        // two creatures of ours, one attacked: one blocker against three attackers (2+2+2): 4 damage, not 2
        Assert.assertEquals(lifeLeft(position("Grizzly Bears|Tapped;Grizzly Bears", 6, "Grizzly Bears;Grizzly Bears;"
                + "Grizzly Bears")), 2);
        noLine(position("Grizzly Bears|Tapped;Grizzly Bears", 4, "Grizzly Bears;Grizzly Bears;Grizzly Bears"),
                "4 damage past the one untapped blocker");
    }

    @Test
    public void aCreatureThatCannotBlockIsNoBlocker() {
        // an untapped creature with a can't-block static (read through the engine's CombatUtil.canBlock)
        noLine(position("Bloodghast", 3, "Grizzly Bears;Grizzly Bears"), "it can't block");
    }

    @Test
    public void aTappedCreatureThatUntapsInTheirUntapStepBlocks() {
        // a static that untaps our permanents in each other player's untap step: the tapped Bears untaps there and
        // blocks; the Muse itself (untapped) blocks too. Three attackers of theirs (6): one connects.
        Assert.assertEquals(lifeLeft(position("Grizzly Bears|Tapped;Seedborn Muse", 3, "Grizzly Bears;Grizzly Bears;"
                + "Grizzly Bears")), 1);
        // a "doesn't untap" effect would keep it tapped: without the Muse the same board has one blocker
        noLine(position("Grizzly Bears|Tapped;Grizzly Bears", 3, "Grizzly Bears;Grizzly Bears;Grizzly Bears"),
                "one blocker without the untap static");
    }

    @Test
    public void theBlockCodesAreReadFromTheEngine() {
        final ForcedWinModel.Position pos = position("Grizzly Bears|Tapped;Grizzly Bears;Bloodghast", 20, "Grizzly Bears");
        // battlefield order: Island, Bears (tapped), Bears, Bloodghast
        Assert.assertEquals(pos.block.size(), pos.battlefield.size());
        Assert.assertEquals((byte) pos.block.get(0), ForcedWinModel.Position.BLOCK_NO, "a land");
        Assert.assertEquals((byte) pos.block.get(1), ForcedWinModel.Position.BLOCK_UNTAPPED, "tapped now, stays tapped");
        Assert.assertEquals((byte) pos.block.get(2), ForcedWinModel.Position.BLOCK_UNTAPPED);
        Assert.assertEquals((byte) pos.block.get(3), ForcedWinModel.Position.BLOCK_NO, "can't block");
        final ForcedWinModel.Position copy = ForcedWinSolver.copyPosition(pos);
        Assert.assertEquals(copy.block, pos.block, "belief-world copies keep the codes");
    }
}
