package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.player.Player;

/**
 * sdcc-bridge-1111 (solver key {@code planBridge}; off by default): the width search's setup lines (modeled steps up
 * to an ability whose outcome the model cannot express) are handed to the engine search, and the joined line is
 * verified like any other. In each position the pieces are in our hand with the mana to deploy them. Card names are
 * test data only.
 */
public class BridgeTest extends AITest {

    /** The pilot's solver keys (the census spec), probeObjects, and the bridge on or off. */
    static final String BASE = "cycles=true,engineAfterPlanFail=true,engineLongLoops=true,engineLoopBisect=true,"
            + "engineLoopHoist=true,engineLoopReach=true,engineMoves=true,engineNodesPerSearch=true,engineOpen=true,"
            + "engineRerun=true,engineTriggerTargets=true,engineVerify=true,followPay=true,hideOwnLibrary=true,"
            + "historyLki=true,landEntersProbe=true,lineUncapped=true,loopMarginal=true,offBattlefieldLimits=true,"
            + "paySearch=true,planExpandCap=true,planOrder=7,probeSettle=true,sourceColorsLive=true,sourceLeaves=true,"
            + "sourceOutput=true,stackAbilityCopies=true,stackHistory=true,stalePlayerStatics=true,staleStatics=true,"
            + "turnLimits=true,tutorCast=true,unknownWorlds=true,verifyReplan=true,probeObjects=true,"
            + "budgetMs=6000,engineMs=4000,evalReserveMs=1500";

    static String spec(final boolean on) {
        // off: the pilot's allowance (6 s a plan point, of which the engine search has about 4). On: a long decision, so
        // the bridge's share does not depend on the test host's load; its lines are told apart by their kind ("bridge").
        return on ? BASE.replace("budgetMs=6000", "budgetMs=45000") + ",planBridge=true,planBridgeMs=25000" : BASE;
    }

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        for (String p : spec(on).split(",")) {
            kv.put(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1));
        }
        kv.put("defense", "false");
        kv.put("act", "false");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    static String frame(final String hand, final String battlefield, final String library) {
        return ForcedWinSolverTest.frame(hand, battlefield, 20, "", "Forest", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
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

    private static final String ISLANDS = "Island;Island;Island;Island;Island;Island;Island;Island";
    /** What else a real position holds: the engine search, a move at a time, spends its allowance on it. */
    private static final String HAND = ";Shock;Opt;Brainstorm;Ponder;Lightning Bolt;Mountain";
    private static final String BOARD = ";Sensei's Divining Top;Mishra's Factory;Mishra's Factory;Aether Vial";

    /** (1) A creature that makes hasty token copies and a creature whose entering untaps it: both in hand. */
    static final String COPIER = frame("Kiki-Jiki, Mirror Breaker;Deceiver Exarch" + HAND,
            "Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;"
                    + "Volcanic Island" + BOARD, ISLANDS);

    /** (2) An Aura that grants "tap: create a copy of this creature" and such a creature: both in hand. */
    static final String AURA = frame("Splinter Twin;Deceiver Exarch" + HAND,
            "Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island;Volcanic Island"
                    + BOARD, ISLANDS);

    /**
     * (3) A sacrifice-an-artifact search that puts an artifact from our library onto the battlefield (39 of the
     * training decks hold this pair), the artifact to sacrifice still in hand.
     */
    static final String SEARCH = frame("Tinker;Mox Sapphire" + HAND,
            "Volcanic Island;Volcanic Island;Volcanic Island;Mishra's Factory;Mishra's Factory;Merfolk Looter;Grim Lavamancer",
            "Island;Island;Blightsteel Colossus;Island;Island;Island;Island;Island");

    private static Line proven(final String frame, final String opaqueHost) {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        final ForcedWinSolver.Proposal none = best(off.propose(g0));
        Assert.assertNull(none, "key off: no line, as before: " + (none == null ? "" : none.text) + " " + off.summary());
        Assert.assertFalse(off.summary().contains("bridge"), off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver on = new ForcedWinSolver(me, cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        Assert.assertEquals(p.how, "bridge", p.text);
        Assert.assertTrue(on.bridgePoints == 1 && on.bridgeTries >= 1 && on.bridgeTries <= on.config().planBridgeMax
                && on.bridgeFound >= 1 && on.bridgeTakes == 1, on.summary());
        Assert.assertTrue(on.summary().contains("bridgeTries="), on.summary());
        // modeled steps first (planner steps), then the engine's, the first of which is the opaque ability
        final Line line = (Line) p.line;
        int firstEngine = -1;
        for (int i = 0; i < line.steps.size(); i++) {
            final AbilKind k = line.steps.get(i).kind;
            if (k == AbilKind.ENGINE || k == AbilKind.REPEAT) {
                firstEngine = i;
                break;
            }
        }
        Assert.assertTrue(firstEngine >= 1, "a modeled step, then the engine's: " + p.text);
        final Step opaque = line.steps.get(firstEngine);
        Assert.assertEquals((opaque.kind == AbilKind.REPEAT ? opaque.body.get(0) : opaque).hostName, opaqueHost, p.text);
        Assert.assertEquals(g.getPlayers().get(1).getLife(), 20, "nothing was played in the live game");
        return line;
    }

    @Test
    public void aCopierAndItsUntapperAreCastAndTheEngineFindsTheLoop() {
        final Line line = proven(COPIER, "Kiki-Jiki, Mirror Breaker");
        boolean exarch = false, kiki = false, attack = false;
        for (Step st : line.steps) {
            exarch |= st.kind == AbilKind.SPELL && st.hostName.equals("Deceiver Exarch");
            kiki |= st.kind == AbilKind.SPELL && st.hostName.equals("Kiki-Jiki, Mirror Breaker");
            attack |= st.kind == AbilKind.ATTACK;
        }
        Assert.assertTrue(exarch && kiki && attack, line.toString());
        Assert.assertEquals(line.winTurn, 0, line.toString());
    }

    @Test
    public void anAuraIsCastOnItsCreatureAndTheEngineFindsTheLoop() {
        final Line line = proven(AURA, "Splinter Twin");
        boolean exarch = false, pass = false;
        for (Step st : line.steps) {
            exarch |= st.kind == AbilKind.SPELL && st.hostName.equals("Deceiver Exarch");
            pass |= st.pass;
        }
        Assert.assertTrue(exarch, line.toString());
        Assert.assertTrue(pass, "the creature cast this turn taps on our next: " + line);
        Assert.assertEquals(line.winTurn, 1, line.toString());
    }

    @Test
    public void aSacrificeSearchPutsTheWinningArtifactOntoTheBattlefield() {
        final Line line = proven(SEARCH, "Tinker");
        boolean mox = false;
        for (Step st : line.steps) {
            mox |= st.kind == AbilKind.SPELL && st.hostName.equals("Mox Sapphire");
        }
        Assert.assertTrue(mox, line.toString());
    }

    /** The planner alone: the setup lines of a position, ordered, and none with the key off. */
    @Test
    public void setupLinesAreNotedOnlyWithTheKeyAndOrdered() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", COPIER);
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader r = ProbeObjectsTest.reader(true);
        final ForcedWinModel.Position pos = r.read(g, me, null, true);
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.probeObjects = true;
        lim.horizon = 1;
        final ForcedWinPlanner.Result off = ForcedWinPlanner.plan(pos, lim);
        Assert.assertTrue(off.setups.isEmpty() && off.lines.isEmpty());
        lim.bridge = true;
        final ForcedWinPlanner.Result on = ForcedWinPlanner.plan(pos, lim);
        Assert.assertTrue(on.lines.isEmpty(), "a setup line is no line");
        Assert.assertEquals(on.nodes, off.nodes, "the search itself is unchanged");
        Assert.assertFalse(on.setups.isEmpty());
        final ForcedWinPlanner.Setup first = on.setups.get(0);
        Assert.assertEquals(first.hostName, "Kiki-Jiki, Mirror Breaker", on.setups.toString());
        Assert.assertEquals(first.objName, "Deceiver Exarch", on.setups.toString());
        Assert.assertTrue(first.cls <= 1, on.setups.toString());
        Assert.assertEquals(first.modeled, 2, "both pieces cast, nothing else: " + first);
        for (int i = 1; i < on.setups.size(); i++) {
            Assert.assertTrue(on.setups.get(i - 1).cls <= on.setups.get(i).cls, on.setups.toString());
        }
    }

    /** The live seat takes the bridged line and follows it to the win. */
    @Test
    public void theLiveSeatDeploysThePiecesAndWins() {
        System.setProperty("forge.ai.forcedWin.spec", spec(true) + ",defense=false");
        try {
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", COPIER);
            ForcedWinSolverTest.play(g, 7);
            final ForcedWinSolver s = ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
            Assert.assertTrue(s.config().planBridge);
            Assert.assertTrue(g.isGameOver() && g.getPlayers().get(0).hasWon(), "no win on turn 7: " + s.summary());
            Assert.assertTrue(s.bridgeTakes >= 1, s.summary());
        } finally {
            System.clearProperty("forge.ai.forcedWin.spec");
        }
    }
}
