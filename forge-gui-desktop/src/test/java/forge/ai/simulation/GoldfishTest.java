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
 * solver-distill-cc-1109 (GOLDFISH): the planner over several of our turns. The later turns' cards come from the
 * land-drop relaxation (the library's lands planned as if in hand) or from sampled orders of our unknown library. The
 * frames are card facts used as tests only (Thassa's Oracle wins when our library is at most our devotion to blue;
 * Doomsday piles five cards; Gush returns two Islands and draws two).
 */
public class GoldfishTest extends forge.ai.AITest {

    private static ForcedWinSolver.GoldfishOpts opts(final int horizon, final int active, final boolean lands,
            final int samples, final int nodes) {
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.horizon = horizon;
        o.active = active;
        o.lands = lands;
        o.samples = samples;
        o.nodes = nodes;
        o.ms = -1L;
        o.seed = 7L;
        return o;
    }

    private static ForcedWinSolver.Goldfish fish(final String battlefield, final String hand, final String library,
            final boolean topKnown, final ForcedWinSolver.GoldfishOpts o) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final List<Integer> top = topKnown ? List.of(me.getCardsIn(ZoneType.Library).get(0).getId()) : s.knownTop(g);
        final ForcedWinSolver.Goldfish r = s.goldfish(g, top, o);
        Assert.assertFalse(r.failed, library);
        System.out.println("GOLDFISH h=" + o.horizon + " a=" + o.active + " lands=" + o.lands + " samples=" + r.samples
                + " screens=" + r.screens + " nodes=" + r.nodes + " capped=" + r.capped + " granted=" + r.granted + " g=" + r.g
                + " p=" + r.p + " turn=" + r.bestTurn + " direct=" + r.direct + " | "
                + library.substring(0, Math.min(40, library.length())) + " | " + r.best);
        return r;
    }

    /** Two Islands, an empty hand: only a drawn Thassa's Oracle wins, on the turn our library is at most two cards. */
    @Test
    public void sampledDrawsOverSeveralTurns() {
        final ForcedWinSolver.Goldfish none = fish("Island;Island", "", "Island;Island;Island;Island", false,
                opts(3, 3, false, 4, 3000));
        Assert.assertEquals(none.g, 0.0, 1e-9);
        Assert.assertEquals(none.p, 0.0, 1e-9);
        // two cards: the Oracle is drawn next turn (one card left) or the turn after (none left): every order wins
        final ForcedWinSolver.Goldfish two = fish("Island;Island", "", "Thassa's Oracle;Island", false,
                opts(3, 3, false, 4, 3000));
        Assert.assertEquals(two.p, 1.0, 1e-9);
        Assert.assertTrue(two.g >= 0.64 - 1e-9 && two.g <= 0.8 + 1e-9, "between gamma^2 and gamma: " + two.g);
        Assert.assertFalse(two.direct);
        // the same seed plans the same draws
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, opts(3, 3, false, 4, 3000)).g, two.g,
                1e-12);
        // one turn of horizon: this turn only, nothing to cast
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, opts(1, 1, false, 4, 3000)).g, 0.0,
                1e-9);
        // no sample and no relaxation: the unknown draws are blanks
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, opts(3, 3, false, 0, 3000)).g, 0.0,
                1e-9);
    }

    /**
     * A tutored Doomsday on top with the mana in play: drawn next turn, cast, and the pile wins the turn after (Gush for
     * its two Islands with their mana floating, the Oracle on a two-card library). Two turns out: nothing by our next
     * turn; three turns find it, and the line needs no granted land (full value).
     */
    @Test
    public void doomsdayFromTheTop() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Island;Island;Island;Island;Swamp;Swamp;Swamp;Swamp;Swamp";
        final String bf = "Underground Sea;Watery Grave;Swamp";
        Assert.assertEquals(fish(bf, "", lib, true, opts(2, 3, true, 0, 20000)).g, 0.0, 1e-9, "not by our next turn");
        final ForcedWinSolver.Goldfish three = fish(bf, "", lib, true, opts(3, 3, true, 0, 20000));
        Assert.assertEquals(three.bestTurn, 2);
        Assert.assertTrue(three.direct, "a line that plays no granted land");
        Assert.assertEquals(three.g, 0.64, 1e-9);
        final ForcedWinSolver.Goldfish menu = fish(bf, "", lib, true, opts(5, 3, true, 0, 1500)); // the menu's knobs
        Assert.assertEquals(menu.g, 0.64, 1e-9);
        Assert.assertEquals(menu.screens, 1, "the first window finds it");
    }

    /** One land short. The relaxation plays a library Swamp (lambda); without it only a sampled Swamp casts Doomsday. */
    @Test
    public void doomsdayNeedsALand() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp";
        final String bf = "Underground Sea;Watery Grave";
        final ForcedWinSolver.Goldfish relaxed = fish(bf, "", lib, true, opts(5, 3, true, 0, 1500));
        Assert.assertEquals(relaxed.bestTurn, 2);
        Assert.assertFalse(relaxed.direct);
        Assert.assertEquals(relaxed.g, 0.6 * 0.64, 1e-9);
        Assert.assertEquals(fish(bf, "", lib, true, opts(5, 3, false, 0, 1500)).g, 0.0, 1e-9, "no land, no line");
        final ForcedWinSolver.Goldfish sampled = fish(bf, "", lib, true, opts(4, 4, false, 6, 6000));
        Assert.assertTrue(sampled.g > 0, "a sampled Swamp casts it");
        Assert.assertEquals(sampled.bestTurn, 3);
    }

    /**
     * Three lands short, with one Island: Doomsday on the third turn from now, the kill on the fourth (the second Island
     * for Gush). The first window (turns 0-2) has no line; the second (a quiet turn, then turns 1-3) finds it.
     */
    @Test
    public void quietTurnsThenTheWindow() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Island;Island;Swamp;Swamp;Swamp;Swamp";
        final ForcedWinSolver.Goldfish r = fish("Swamp", "", lib, true, opts(5, 3, true, 0, 1500));
        Assert.assertEquals(r.bestTurn, 3);
        Assert.assertEquals(r.screens, 2);
        Assert.assertEquals(r.g, 0.6 * 0.512, 1e-9);
        // a call cap below one screen's nodes stops after the first screen
        final ForcedWinSolver.GoldfishOpts o = opts(5, 3, true, 0, 1500);
        o.maxNodes = 1;
        final ForcedWinSolver.Goldfish cut = fish("Swamp", "", lib, true, o);
        Assert.assertTrue(cut.cut);
        Assert.assertEquals(cut.screens, 1);
    }
}
