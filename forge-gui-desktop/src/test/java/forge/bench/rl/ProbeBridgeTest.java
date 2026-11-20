package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiTape;
import forge.ai.AiTapeController;
import forge.ai.simulation.PlayoutKeys;
import forge.bench.BenchSession;
import forge.bench.CallCounter;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * probe-bridge-1120: a replay probe ({@link RlReplaySearch#probe}, replaySearch and playFrom) builds the seat's priority menu
 * outside any recorded frame. With an optional-cost card in hand (Beseech the Mirror's bargain) the enumeration asks the
 * seat's controller chooseOptionalCosts; a taped seat's controller took that ask as its next recorded decision and the
 * replay diverged (lineprot playFrom w05, 11-20). {@link RlReplaySearch#throughBridge} asks the inner bridge, as the live
 * seat did inside its recorded priority frame, and the tape is untouched.
 */
public class ProbeBridgeTest extends AITest {

    private static final class Seat {
        Game game;
        Player p;
        PlayerControllerBridge bridge;
        AiTapeController tc;
        AiTape tape;
    }

    /** Seat 1 in its MAIN1 with Beseech the Mirror in hand; its controller a FORCED tape (one recorded priority ask) over a bridge. */
    private Seat seat() {
        final Seat s = new Seat();
        s.game = initAndCreateGame();
        s.p = s.game.getPlayers().get(1);
        addCardToZone("Beseech the Mirror", s.p, ZoneType.Hand);
        addCard("Mind Stone", s.p);
        for (int i = 0; i < 4; i++) {
            addCard("Swamp", s.p);
        }
        s.bridge = new PlayerControllerBridge(s.game, s.p, s.p.getController().getLobbyPlayer(), new BenchSession(null),
                BenchSession.Mode.BRIDGE, 1, new CallCounter());
        s.tape = AiTape.forced(s.game, s.p, new PlayoutKeys.TrackedRandom(7L),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                List.of("{\"k\":\"m\",\"m\":\"chooseSpellAbilityToPlay\",\"a\":[]}"));
        s.tc = AiTapeController.wrap(s.bridge, s.tape);
        s.p.dangerouslySetController(s.tc);
        s.tape.bindGameThread(Thread.currentThread());
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<SpellAbility> menu(final Object[] ask) {
        return (List<SpellAbility>) ask[0];
    }

    private static boolean hasBeseech(final List<SpellAbility> menu) {
        return menu.stream().anyMatch(sa -> sa.getHostCard() != null && "Beseech the Mirror".equals(sa.getHostCard().getName()));
    }

    @Test
    public void theOldProbeAskTookTheOptionalCostAskAsARecordedDecision() {
        final Seat s = seat();
        PlayerControllerBridge.isolated(s.game, () -> IdScope.detached(() -> s.bridge.shadowPriorityAsk()));
        Assert.assertNotNull(s.tape.divergence(), "through the tape controller the enumeration's ask is taken as a decision");
        Assert.assertTrue(s.tape.divergence().contains("chooseOptionalCosts"), s.tape.divergence());
    }

    @Test
    public void theProbeAsksTheBridgeAndLeavesTheTapeUntouched() {
        final Seat s = seat();
        final PlayerController[] during = new PlayerController[1];
        final Object[] ask = PlayerControllerBridge.isolated(s.game, () -> IdScope.detached(() -> RlReplaySearch.throughBridge(s.p,
                () -> {
                    during[0] = s.p.getController();
                    return s.bridge.shadowPriorityAsk();
                })));
        Assert.assertSame(during[0], s.bridge, "the bridge answers the probe's asks");
        Assert.assertSame(s.p.getController(), s.tc, "the tape controller is back after the probe");
        Assert.assertNull(s.tape.divergence(), "no recorded decision was taken: " + s.tape.divergence());
        Assert.assertTrue(hasBeseech(menu(ask)), "the optional-cost card is on the menu: " + menu(ask));
        // the recorded priority ask is still the tape's next entry: the seat's real decision takes it
        final Object[] again = PlayerControllerBridge.isolated(s.game, () -> IdScope.detached(() -> RlReplaySearch.throughBridge(s.p,
                () -> s.bridge.shadowPriorityAsk())));
        Assert.assertEquals(menu(again).size(), menu(ask).size(), "a second probe sees the same menu");
        Assert.assertNull(s.tape.divergence());
    }

    @Test
    public void theControllerIsRestoredWhenTheProbeThrows() {
        final Seat s = seat();
        Assert.assertThrows(IllegalStateException.class, () -> RlReplaySearch.throughBridge(s.p, () -> {
            throw new IllegalStateException("probe failed");
        }));
        Assert.assertSame(s.p.getController(), s.tc);
    }

    @Test
    public void aSeatWithoutATapeIsLeftAsItIs() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final PlayerController before = p.getController();
        final PlayerController[] during = new PlayerController[1];
        RlReplaySearch.throughBridge(p, () -> {
            during[0] = p.getController();
            return null;
        });
        Assert.assertSame(during[0], before, "an untaped seat's controller is not swapped");
        Assert.assertSame(p.getController(), before);
    }
}
