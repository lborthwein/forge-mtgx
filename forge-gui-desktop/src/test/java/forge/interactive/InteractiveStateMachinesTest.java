package forge.interactive;

import forge.bench.JsonRpcChannel;
import forge.gamemodes.match.input.InputSyncronizedBase;
import forge.interactive.InteractiveGuiGame.SeatPhase;
import forge.interactive.InteractiveMain.SessionPhase;
import forge.util.StateMachine;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * state-machines-1113: the bridge and session machines made explicit (survey M1, M2, M4, M8). Their tables say what a
 * correct run never does; the interactive suites run under {@code -Dforge.state.strict=true} prove the runs agree.
 */
public class InteractiveStateMachinesTest {

    @Test
    public void theSeatTableRefusesWhatACorrectRunNeverDoes() {
        final StateMachine<SeatPhase> seat = InteractiveGuiGame.SEAT;
        // a claim, or a settle, with no request seen
        assertFalse(seat.allowed(SeatPhase.NONE, SeatPhase.IN_FLIGHT));
        assertFalse(seat.allowed(SeatPhase.NONE, SeatPhase.SETTLING));
        // a modal answer with no modal open
        for (SeatPhase from : List.of(SeatPhase.NONE, SeatPhase.PUBLISHED, SeatPhase.IN_FLIGHT, SeatPhase.SETTLING)) {
            assertFalse(seat.allowed(from, SeatPhase.MODAL_CLAIMED), from.toString());
        }
        // an input published over an unanswered modal (the 2026-09-24 superseded-refusal stall)
        assertFalse(seat.allowed(SeatPhase.MODAL_OPEN, SeatPhase.PUBLISHED));
        // an action reopening after its settle began
        assertFalse(seat.allowed(SeatPhase.SETTLING, SeatPhase.IN_FLIGHT));
        // ENDED is absorbing; every other phase can end
        assertTrue(seat.next(SeatPhase.ENDED).isEmpty());
        for (SeatPhase from : SeatPhase.values()) {
            if (from != SeatPhase.ENDED) {
                assertTrue(seat.allowed(from, SeatPhase.ENDED), from.toString());
            }
        }
        // the ordinary round trips
        assertTrue(seat.allowed(SeatPhase.NONE, SeatPhase.PUBLISHED));
        assertTrue(seat.allowed(SeatPhase.PUBLISHED, SeatPhase.IN_FLIGHT));
        assertTrue(seat.allowed(SeatPhase.IN_FLIGHT, SeatPhase.SETTLING));
        assertTrue(seat.allowed(SeatPhase.SETTLING, SeatPhase.PUBLISHED));
        assertTrue(seat.allowed(SeatPhase.MODAL_OPEN, SeatPhase.MODAL_CLAIMED));
        assertTrue(seat.allowed(SeatPhase.MODAL_CLAIMED, SeatPhase.MODAL_OPEN), "a refused modal answer");
    }

    @Test
    public void theSessionTableRunsConfigToExit() {
        final StateMachine<SessionPhase> session = InteractiveMain.SESSION;
        assertTrue(session.allowed(SessionPhase.BOOT, SessionPhase.CONFIG));
        assertTrue(session.allowed(SessionPhase.CONFIG, SessionPhase.HELLO));
        assertTrue(session.allowed(SessionPhase.HELLO, SessionPhase.RUNNING));
        assertTrue(session.allowed(SessionPhase.RUNNING, SessionPhase.ENDING));
        assertTrue(session.allowed(SessionPhase.ENDING, SessionPhase.ENDED));
        assertFalse(session.allowed(SessionPhase.CONFIG, SessionPhase.RUNNING), "no game before hello");
        assertFalse(session.allowed(SessionPhase.RUNNING, SessionPhase.ENDED), "no terminal before the game thread ends");
        assertTrue(session.next(SessionPhase.ENDED).isEmpty());
        assertTrue(session.next(SessionPhase.FAILED).isEmpty());
    }

    /** The boot paths walk the session table (strict: an illegal step would throw here). */
    @Test
    public void aBadConfigAndAnUnusedSpareEndAsBefore() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final long illegal = InteractiveMain.SESSION.illegalCount();
        final int status = InteractiveMain.runSession(new BufferedReader(new StringReader("not a config\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8), false);
        assertEquals(status, 2, "a boot fatal");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"invalid_config\""), out.toString(StandardCharsets.UTF_8));
        final ByteArrayOutputStream spare = new ByteArrayOutputStream();
        assertEquals(InteractiveMain.runSession(new BufferedReader(new StringReader("")),
                new PrintStream(spare, true, StandardCharsets.UTF_8), true), 0, "a spare never configured");
        assertEquals(spare.size(), 0, "a spare that was never used says nothing");
        assertEquals(InteractiveMain.SESSION.illegalCount(), illegal);
    }

    @Test
    public void aBenchChannelSaysWhyItClosed() {
        final JsonRpcChannel eof = new JsonRpcChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        assertEquals(eof.state(), JsonRpcChannel.ChannelState.OPEN);
        final com.google.gson.JsonObject answer = eof.ask("priority", new com.google.gson.JsonObject());
        assertTrue(answer.get("delegate").getAsBoolean());
        assertEquals(eof.state(), JsonRpcChannel.ChannelState.CLOSED_EOF);
        assertTrue(eof.isClosed());
        eof.close();
        assertEquals(eof.state(), JsonRpcChannel.ChannelState.CLOSED_EOF, "the first cause wins");

        final JsonRpcChannel degraded = new JsonRpcChannel(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream());
        degraded.close();
        assertEquals(degraded.state(), JsonRpcChannel.ChannelState.CLOSED_DEGRADED);
        assertTrue(degraded.isClosed());
    }

    /** Every explicit machine of this lane, drawn (the RESULT tables come from this output). */
    @Test
    public void everyMachineDrawsItsTable() {
        final List<StateMachine<?>> machines = List.of(InteractiveGuiGame.SEAT, InteractiveMain.SESSION,
                InputSyncronizedBase.RELEASE_MACHINE, JsonRpcChannel.CHANNEL);
        for (StateMachine<?> m : machines) {
            assertTrue(StateMachine.registry().contains(m), m.name());
            final String mermaid = m.toMermaid();
            assertTrue(mermaid.startsWith("stateDiagram-v2"), mermaid);
            System.out.println("[machine] " + m.name() + " : " + m.doc() + "\n" + mermaid);
        }
    }
}
