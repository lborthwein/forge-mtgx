package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;

/**
 * sdcc-order-1111: the solver key engineFocus (off by default): the engine search tries the lines made of productive
 * moves first, so a kill on a board with other cards costs about what it costs on a bare board. Card names are test
 * data.
 */
public class EngineFocusTest extends forge.ai.AITest {

    /** The pilot's engine keys (the owner-tests files' solver config), plus {@code extra}. */
    static ForcedWinSolver.Config config(final String... extra) {
        final JsonObject o = new JsonObject();
        for (String k : new String[] {"copyBackFace", "cycles", "engineAfterPlanFail", "engineLongLoops", "engineLoopBisect",
                "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen", "engineRerun",
                "engineTriggerTargets", "engineVerify", "followPay", "hideOwnLibrary", "historyLki", "lineUncapped",
                "loopMarginal", "offBattlefieldLimits", "paySearch", "planDiscardWall", "planExpandCap", "readerPerGame",
                "sourceColorsLive", "sourceOutput", "stackAbilityCopies", "stackHistory", "stalePlayerStatics",
                "staleStatics", "turnLimits", "unknownWorlds", "verifyReplan"}) {
            o.addProperty(k, "true");
        }
        // a long clock: the searches below end by themselves or at the node cap, so the counts do not depend on the host
        o.addProperty("budgetMs", "120000");
        o.addProperty("engineMs", "90000");
        o.addProperty("evalReserveMs", "1500");
        o.addProperty("act", "false");
        o.addProperty("defense", "false");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            o.addProperty(extra[i], extra[i + 1]);
        }
        return SolverDriver.config(o);
    }

    /** What else a real position holds: cantrips, a counterspell and a land in hand, four permanents with abilities. */
    static final String HAND = ";Opt;Brainstorm;Ponder;Preordain;Counterspell;Island";
    static final String BOARD = ";Aether Vial;Relic of Progenitus;Wasteland;Rishadan Port";

    private static Game creatureSearch() {
        // a sorcery that searches for a creature, four mana creatures to attack with it
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Natural Order" + HAND, "Llanowar Elves;Elvish Mystic;Fyndhorn Elves;Birds of Paradise;Forest;Forest;Forest;Forest"
                        + BOARD, 20, "Forest;Llanowar Elves;Grizzly Bears", "Forest;Forest;Forest;Forest", 20, "human")
                .replace("humanlibrary=", "humanlibrary=Craterhoof Behemoth;"));
    }

    private static Game copyLoop() {
        // a creature that copies, in hand; its partner on the battlefield
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Kiki-Jiki, Mirror Breaker" + HAND, "Pestermite;Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;"
                        + "Volcanic Island" + BOARD, 20, "Forest;Llanowar Elves;Grizzly Bears", "Forest;Forest;Forest;Forest", 20,
                "human"));
    }

    private static ForcedWinSolver.Proposal best(final List<ForcedWinSolver.Proposal> props) {
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        return best;
    }

    @Test
    public void theKeyIsOffByDefaultAndParsed() {
        Assert.assertFalse(new ForcedWinSolver.Config().engineFocus);
        Assert.assertFalse(config().engineFocus);
        Assert.assertTrue(config("engineFocus", "true").engineFocus);
        Assert.assertFalse(config("engineFocus", "false").engineFocus);
        Assert.assertFalse(new ForcedWinEngine.Limits().focus);
    }

    @Test
    public void aSearchKillOnAClutteredBoard() {
        final Game g0 = creatureSearch();
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), config());
        final ForcedWinSolver.Proposal p0 = best(off.propose(g0));
        final Game g1 = creatureSearch();
        final ForcedWinSolver on = new ForcedWinSolver(g1.getPlayers().get(0), config("engineFocus", "true"));
        final ForcedWinSolver.Proposal p1 = best(on.propose(g1));
        System.out.println("ENGINEFOCUS search off moves=" + off.engineStats.moves + " nodes=" + off.engineStats.nodes + " "
                + (p0 == null ? "none" : p0.text));
        System.out.println("ENGINEFOCUS search on  moves=" + on.engineStats.moves + " nodes=" + on.engineStats.nodes + " "
                + (p1 == null ? "none" : p1.text));
        Assert.assertNotNull(p1, "key on: a line");
        Assert.assertTrue(p1.proven(1), "key on: proven: " + p1.text);
        Assert.assertEquals(p1.winTurn, 0, "this turn: " + p1.text);
        Assert.assertTrue(p1.text.contains("Natural Order") && p1.text.contains("ATTACK"), "the kill: " + p1.text);
        for (String other : new String[] {"Aether Vial", "Relic of Progenitus", "Wasteland", "Rishadan Port", "Brainstorm"}) {
            Assert.assertFalse(p1.text.contains("ENGINE:" + other), "no unrelated move in the line: " + p1.text);
        }
        Assert.assertTrue(on.engineStats.moves < 40, "key on: a few moves: " + on.engineStats.moves);
        Assert.assertTrue(on.engineStats.focusSkips > 0, "moves were left out: " + on.engineStats.summary());
        Assert.assertEquals(off.engineStats.focusSkips + off.engineStats.focusGains + off.engineStats.focusInert, 0,
                "key off: the focus code never ran");
        Assert.assertTrue(p0 == null || !p0.proven(1) || on.engineStats.moves < off.engineStats.moves,
                "key off: no line, or more moves: " + off.engineStats.moves);
    }

    @Test
    public void aLoopKillOnAClutteredBoard() {
        final Game g1 = copyLoop();
        final ForcedWinSolver on = new ForcedWinSolver(g1.getPlayers().get(0), config("engineFocus", "true"));
        final ForcedWinSolver.Proposal p1 = best(on.propose(g1));
        System.out.println("ENGINEFOCUS loop on  moves=" + on.engineStats.moves + " nodes=" + on.engineStats.nodes + " "
                + (p1 == null ? "none" : p1.text));
        Assert.assertNotNull(p1, "key on: a line");
        Assert.assertTrue(p1.proven(1), "key on: proven: " + p1.text);
        Assert.assertTrue(p1.text.contains("REPEAT") && p1.text.contains("ATTACK"), "the loop, then the attack: " + p1.text);
        Assert.assertFalse(p1.text.contains("ENGINE:Aether Vial") || p1.text.contains("ENGINE:Wasteland"),
                "no unrelated move in the line: " + p1.text);
    }

    @Test
    public void theAllowanceGrowsWhenNothingIsFound() {
        // no kill here: the productive lines run out, and the search goes on with one, two, ... unproductive moves
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Opt;Counterspell;Island", "Island;Island;Aether Vial;Rishadan Port", 20, "", "Forest;Forest;Forest;Forest", 20,
                "human"));
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), config("engineFocus", "true"));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        System.out.println("ENGINEFOCUS none on  moves=" + on.engineStats.moves + " " + on.engineStats.summary() + " "
                + (p == null ? "none" : p.text));
        Assert.assertTrue(p == null || !p.proven(1), "no kill: " + (p == null ? "" : p.text));
        Assert.assertTrue(on.engineStats.focusDepth >= 1, "the allowance grew: " + on.engineStats.summary());
        Assert.assertTrue(on.engineStats.focusInert > 0, "unproductive moves were seen: " + on.engineStats.summary());
    }
}
