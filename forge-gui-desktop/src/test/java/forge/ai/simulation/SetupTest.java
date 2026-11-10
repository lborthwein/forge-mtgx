package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * solver-distill-cc-1109 (SETUP): the planner's play for this turn towards a kill more than a turn away, as a line the
 * follower plays with its own picks (a tutor's card, a pile's five). The frames are card facts used as tests only
 * (Doomsday piles five cards and halves our life; Vampiric Tutor puts a card on top for 2 life; Gush returns two Islands
 * and draws two; Thassa's Oracle wins when our library is at most our devotion to blue).
 */
public class SetupTest extends forge.ai.AITest {

    static final String REST = "Gush;Thassa's Oracle;Island;Island;Island;Island;Swamp;Swamp;Swamp;Swamp;Swamp";

    private static ForcedWinSolver.GoldfishOpts opts() {
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.horizon = 5;
        o.active = 3;
        o.nodes = 4000;
        o.maxNodes = 12000;
        o.ms = -1L;
        return o;
    }

    private static Game game(final String battlefield, final String hand, final String library) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    private static ForcedWinSolver solver(final Game g) {
        return ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
    }

    private static ForcedWinSolver.Proposal setup(final Game g) {
        final ForcedWinSolver s = solver(g);
        final ForcedWinSolver.Proposal p = s.setup(g, s.knownTop(g), opts());
        System.out.println("SETUP " + (p == null ? "none" : "gf=" + p.gf + " turn+" + p.winTurn + " steps=" + p.steps + " | " + p.line));
        return p;
    }

    private static List<String> libraryNames(final Player me) {
        final List<String> out = new java.util.ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            out.add(c.getName());
        }
        return out;
    }

    /** Doomsday in hand with its mana: the setup is the cast with the pile the planner wins with next turn. */
    @Test
    public void doomsdayWithItsPile() {
        final Game g = game("Underground Sea;Watery Grave;Swamp", "Doomsday", REST);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver.Proposal p = setup(g);
        Assert.assertNotNull(p, "a kill next turn through the pile");
        Assert.assertTrue(p.setup);
        Assert.assertEquals(p.winTurn, 1);
        Assert.assertEquals(p.pUnopposed, 0.0, 1e-12, "unverified");
        Assert.assertFalse(p.proven(1), "never taken at once");
        final ForcedWinPlanner.Line l = (ForcedWinPlanner.Line) p.line;
        final ForcedWinPlanner.Step cast = l.steps.get(l.steps.size() - 1);
        Assert.assertEquals(cast.hostName, "Doomsday", String.valueOf(l));
        Assert.assertEquals(cast.pile.size(), 5, "the pile is the step's own answer: " + l);
        for (ForcedWinPlanner.Step st : l.steps) {
            Assert.assertTrue(st.hostId < ForcedWinSolver.GRANT_ID, "only our own cards: " + l);
        }
        // followed live: the cast, and the planner's pile on top
        solver(g).adopt(p.line);
        ForcedWinSolverTest.play(g, 7);
        Assert.assertEquals(me.getLife(), 10, "Doomsday resolved");
        final List<String> lib = libraryNames(me);
        Assert.assertEquals(lib.size(), 5, String.valueOf(lib));
        Assert.assertTrue(lib.contains("Gush") && lib.contains("Thassa's Oracle"), "the pile that wins: " + lib);
    }

    /** A land in hand: the setup plays it before the cast. */
    @Test
    public void landDropThenTheCast() {
        final Game g = game("Underground Sea;Watery Grave", "Doomsday;Swamp", REST);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver.Proposal p = setup(g);
        Assert.assertNotNull(p);
        final ForcedWinPlanner.Line l = (ForcedWinPlanner.Line) p.line;
        Assert.assertEquals(l.steps.get(0).kind, AbilKind.LAND, String.valueOf(l));
        Assert.assertEquals(l.steps.get(l.steps.size() - 1).hostName, "Doomsday", String.valueOf(l));
        solver(g).adopt(p.line);
        ForcedWinSolverTest.play(g, 7);
        Assert.assertNotNull(ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Swamp"));
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).size(), 5);
    }

    /** A tutor in hand, Doomsday somewhere in the library: the setup is the tutor with Doomsday as its pick. */
    @Test
    public void tutorWithItsPick() {
        final Game g = game("Underground Sea;Watery Grave", "Vampiric Tutor", "Island;Doomsday;" + REST);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver.Proposal p = setup(g);
        Assert.assertNotNull(p, "tutor now, Doomsday next turn with its land drop, the kill the turn after");
        Assert.assertEquals(p.winTurn, 2);
        final ForcedWinPlanner.Line l = (ForcedWinPlanner.Line) p.line;
        final ForcedWinPlanner.Step cast = l.steps.get(l.steps.size() - 1);
        Assert.assertEquals(cast.hostName, "Vampiric Tutor", String.valueOf(l));
        final Card dd = ForcedWinSolverTest.inZone(me, ZoneType.Library, "Doomsday");
        Assert.assertEquals(cast.picks, List.of(dd.getId()), "the pick is the step's own answer: " + l);
        solver(g).adopt(p.line);
        ForcedWinSolverTest.play(g, 7);
        Assert.assertEquals(me.getLife(), 18, "the tutor resolved");
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).get(0).getName(), "Doomsday", "on top: " + libraryNames(me));
    }

    /** No line: nothing. A line that wins this turn is the offense's, not a setup. */
    @Test
    public void noLineOrAWinThisTurn() {
        Assert.assertNull(setup(game("Island;Island", "Island", "Island;Island;Island;Island")), "lands only");
        Assert.assertNull(setup(game("Island;Island", "Thassa's Oracle", "Island;Island")), "the Oracle wins now");
        // a kill that needs no play this turn (a quiet turn first): nothing to adopt
        Assert.assertNull(setup(game("", "", "Doomsday;" + REST)), "nothing to play this turn");
    }
}
