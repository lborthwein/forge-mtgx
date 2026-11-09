package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * solver-distill-cc-1109 (GOLDFISH): the planner over several of our turns, the later draws from sampled orders of our
 * unknown library. The frames are card facts used as tests only (Thassa's Oracle wins when our library is at most our
 * devotion to blue; Doomsday piles five cards).
 */
public class GoldfishTest extends forge.ai.AITest {

    private static ForcedWinSolver.Goldfish fish(final String battlefield, final String hand, final String library,
            final boolean topKnown, final int horizon, final int samples, final int nodes, final long seed) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final List<Integer> top = topKnown ? List.of(me.getCardsIn(ZoneType.Library).get(0).getId()) : s.knownTop(g);
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.horizon = horizon;
        o.samples = samples;
        o.nodes = nodes;
        o.ms = -1L;
        o.seed = seed;
        final ForcedWinSolver.Goldfish r = s.goldfish(g, top, o);
        Assert.assertFalse(r.failed, library);
        System.out.println("GOLDFISH h=" + horizon + " samples=" + r.samples + " screens=" + r.screens + " nodes=" + r.nodes
                + " capped=" + r.capped + " g=" + r.g + " p=" + r.p + " turn=" + r.bestTurn + " direct=" + r.direct + " | "
                + library.substring(0, Math.min(40, library.length())) + " | " + r.best);
        return r;
    }

    /** Two Islands, an empty hand: only a drawn Thassa's Oracle wins, on the turn our library is at most two cards. */
    @Test
    public void sampledDrawsOverSeveralTurns() {
        final ForcedWinSolver.Goldfish none = fish("Island;Island", "", "Island;Island;Island;Island", false, 3, 4, 3000, 7L);
        Assert.assertEquals(none.g, 0.0, 1e-9);
        Assert.assertEquals(none.p, 0.0, 1e-9);
        // two cards: the Oracle is drawn next turn (one card left) or the turn after (none left): every order wins
        final ForcedWinSolver.Goldfish two = fish("Island;Island", "", "Thassa's Oracle;Island", false, 3, 4, 3000, 7L);
        Assert.assertEquals(two.p, 1.0, 1e-9);
        Assert.assertTrue(two.g >= 0.64 - 1e-9 && two.g <= 0.8 + 1e-9, "between gamma^2 and gamma: " + two.g);
        Assert.assertFalse(two.direct);
        // the same seed plans the same draws
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, 3, 4, 3000, 7L).g, two.g, 1e-12);
        // one turn of horizon: this turn only, nothing to cast
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, 1, 4, 3000, 7L).g, 0.0, 1e-9);
    }

    /**
     * A tutored Doomsday on top with the mana in play: drawn next turn, cast, and the pile wins the turn after (Gush for
     * its two Islands with their mana floating, the Oracle on a two-card library). Two turns out: reach (by our next
     * turn) is 0, the three-turn goldfish finds it with no sampled card.
     */
    @Test
    public void doomsdayFromTheTop() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Island;Island;Island;Island;Swamp;Swamp;Swamp;Swamp;Swamp";
        final String bf = "Underground Sea;Watery Grave;Swamp";
        final ForcedWinSolver.Goldfish two = fish(bf, "", lib, true, 2, 0, 20000, 7L);
        Assert.assertEquals(two.g, 0.0, 1e-9, "not by our next turn");
        final ForcedWinSolver.Goldfish three = fish(bf, "", lib, true, 3, 0, 20000, 7L);
        Assert.assertTrue(three.direct, "a line with no sampled card");
        Assert.assertEquals(three.bestTurn, 2);
        Assert.assertEquals(three.g, 0.64, 1e-9);
        fish(bf, "", lib, true, 3, 0, 3000, 7L); // report: the same at the menu's node cap
        fish(bf, "", lib, true, 4, 4, 3000, 7L);
    }

    /** One land short: the third land comes from a sampled draw (most of the library is Swamps), a turn later. */
    @Test
    public void doomsdayNeedsADrawnLand() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp";
        final String bf = "Underground Sea;Watery Grave";
        Assert.assertEquals(fish(bf, "", lib, true, 3, 6, 6000, 7L).g, 0.0, 1e-9, "three turns: no land in time");
        final ForcedWinSolver.Goldfish four = fish(bf, "", lib, true, 4, 6, 6000, 7L);
        Assert.assertTrue(four.g > 0, "a sampled Swamp casts it");
        Assert.assertEquals(four.bestTurn, 3);
        Assert.assertFalse(four.direct);
    }
}
