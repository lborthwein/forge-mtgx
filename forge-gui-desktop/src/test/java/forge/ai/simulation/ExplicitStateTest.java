package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.util.IdScope;
import forge.util.StateMachine;

/**
 * state-machines-1113: the explicit state added to the engine. PhaseHandler's priority round (the record the search
 * used to write by reflection), the restart's trigger-id reset that never touches the live counter from a search
 * copy, and the {@link StateMachine} helper itself.
 */
public class ExplicitStateTest extends SimulationTest {

    // ---------------------------------------------------------------- priority round

    @Test
    public void resumeWithGivesTheSeatPriorityAsTheReflectionDid() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        final PhaseHandler ph = game.getPhaseHandler();
        ph.devModeSet(PhaseType.MAIN1, me);
        LookaheadSearch.givePriority(ph, me);
        AssertJUnit.assertEquals(new PhaseHandler.PriorityState(me, me, true), ph.getPriorityState());
        AssertJUnit.assertSame(me, LookaheadSearch.firstPriority(ph));
        AssertJUnit.assertTrue(LookaheadSearch.givePriorityFlag(ph));
        AssertJUnit.assertSame(me, ph.getPriorityPlayer());
    }

    @Test
    public void passCompletesRoundFollowsTheMainLoopTest() {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0);
        final Player b = game.getPlayers().get(1);
        final PhaseHandler ph = game.getPhaseHandler();
        ph.devModeSet(PhaseType.MAIN1, a);
        ph.dangerouslySetPriorityState(new PhaseHandler.PriorityState(a, a, true));
        AssertJUnit.assertFalse("a acted first and holds priority: b still gets a window", ph.passCompletesRound());
        ph.dangerouslySetPriorityState(new PhaseHandler.PriorityState(b, a, true));
        AssertJUnit.assertTrue("b holds priority after a: b's pass completes the round", ph.passCompletesRound());
        ph.dangerouslySetFirstPriorityPlayer(b);
        AssertJUnit.assertEquals(new PhaseHandler.PriorityState(b, b, true), ph.getPriorityState());
    }

    // ---------------------------------------------------------------- restart trigger ids

    @Test
    public void restartInsideASearchScopeLeavesTheLiveCounter() {
        final int before = Trigger.peekMaxId();
        try {
            Trigger.dangerouslySetMaxId(12345);
            final Object prev = IdScope.capture();
            IdScope.open();
            try {
                Trigger.resetIDsForRestart();
            } finally {
                IdScope.install(prev);
            }
            AssertJUnit.assertEquals("a copy's restart must not reset the live counter", 12345, Trigger.peekMaxId());
            Trigger.resetIDsForRestart();
            AssertJUnit.assertEquals("a live restart resets it, as before", 50000, Trigger.peekMaxId());
        } finally {
            Trigger.dangerouslySetMaxId(before);
        }
    }

    // ---------------------------------------------------------------- the helper

    enum Light { OFF, ON, BROKEN }

    private static final StateMachine<Light> LIGHT = StateMachine.builder("test-light", Light.class)
            .edge(Light.OFF, Light.ON)
            .edge(Light.ON, Light.OFF, Light.BROKEN)
            .doc("a test machine")
            .build();

    @Test
    public void stateMachineChecksTheTableAndNeverBlocksOutsideStrict() {
        AssertJUnit.assertTrue(LIGHT.allowed(Light.OFF, Light.ON));
        AssertJUnit.assertFalse(LIGHT.allowed(Light.BROKEN, Light.ON));
        final StateMachine.Cell<Light> cell = LIGHT.cell("lamp", Light.OFF);
        AssertJUnit.assertEquals(Light.OFF, cell.go(Light.ON, "switch"));
        AssertJUnit.assertTrue(cell.is(Light.ON));
        cell.go(Light.BROKEN, "bulb");
        final long illegalBefore = LIGHT.illegalCount();
        if (StateMachine.STRICT) {
            try {
                cell.go(Light.ON, "magic");
                AssertJUnit.fail("strict mode throws on an illegal transition");
            } catch (IllegalStateException expected) {
                AssertJUnit.assertTrue(expected.getMessage().contains("ILLEGAL test-light lamp BROKEN->ON"));
            }
        } else {
            cell.go(Light.ON, "magic");
            AssertJUnit.assertTrue("the transition still happens", cell.is(Light.ON));
        }
        AssertJUnit.assertEquals(illegalBefore + 1, LIGHT.illegalCount());
        AssertJUnit.assertTrue(StateMachine.registry().contains(LIGHT));
        AssertJUnit.assertTrue(LIGHT.toMermaid().contains("ON --> BROKEN"));
    }
}
