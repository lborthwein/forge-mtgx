package forge.ai.simulation;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane ledger-sweep2-1107, ledger row 15 (solverCfg sourceColorsLive). The offense reader learns a card's template once, at
 * its first sight in the game. Urborg seen in the hand has no mana ability (its B comes from making itself a Swamp on the
 * battlefield) and an Island seen before Urborg arrived makes only U; both are kept, so later, with Urborg out, the planner
 * cannot make B from either (the dd6 T15 Doomsday kill needs both). With the key on, battlefield sources are read from the
 * position.
 */
public class SourceColorsLiveTest extends forge.ai.AITest {
    private static final int U = 1 << 1, B = 1 << 2;

    private static ForcedWinModel.ACard named(final ForcedWinModel.Position p, final String name) {
        for (ForcedWinModel.ACard a : p.battlefield) {
            if (a.name.equals(name)) {
                return a;
            }
        }
        throw new AssertionError(name + " not on the battlefield: " + p.describe());
    }

    /** Templates learned on the earlier frame, carried into a solver with the given config on the later frame. */
    private static ForcedWinModel.Position later(final boolean on) {
        final Game early = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Urborg, Tomb of Yawgmoth;Doomsday", "Island;Island;Swamp", 20, "", "Island", 20, "human"));
        final Player e = early.getPlayers().get(0);
        final ForcedWinSolver se = new ForcedWinSolver(e, ForcedWinSolver.Config.fromProps(k -> null));
        se.readPosition(early, se.knownTop(early));
        final String learned = se.reader.exportJson();
        final Game late = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Doomsday", "Urborg, Tomb of Yawgmoth;Island;Island;Swamp", 20, "", "Island", 20, "human"));
        final Player me = late.getPlayers().get(0);
        final Map<String, String> props = on ? Map.of("sourceColorsLive", "true") : Map.of();
        final ForcedWinSolver s = new ForcedWinSolver(me, ForcedWinSolver.Config.fromProps(props::get));
        s.reader.preload(learned);
        return s.readPosition(late, s.knownTop(late));
    }

    @Test
    public void battlefieldSourcesAreReadFromThePosition() {
        final ForcedWinModel.Position off = later(false);
        // off: the first-sight templates (Urborg from the hand: no output; Island before Urborg: U only)
        Assert.assertEquals(named(off, "Urborg, Tomb of Yawgmoth").sourceColors & B, 0, off.describe());
        Assert.assertEquals(named(off, "Island").sourceColors & B, 0, off.describe());
        final ForcedWinModel.Position on = later(true);
        Assert.assertEquals(named(on, "Urborg, Tomb of Yawgmoth").sourceColors & B, B, on.describe());
        Assert.assertEquals(named(on, "Island").sourceColors & (U | B), U | B, on.describe());
        Assert.assertTrue(named(on, "Island").sourceAmount >= 1, on.describe());
    }

    @Test
    public void theKeyIsOffByDefault() {
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).sourceColorsLive);
        Assert.assertTrue(ForcedWinSolver.Config.fromProps(Map.of("sourceColorsLive", "true")::get).sourceColorsLive);
    }
}
