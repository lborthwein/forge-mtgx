package forge.ai.dp;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** decisionpoint-1102 PR-E: answerers on game copies (both seats) and the per-row meter. */
public class DecisionPointCopyTest extends AITest {
    @AfterMethod
    public void reset() {
        DecisionPoints.setCopyAnswerers(null);
        DecisionPoints.setMeter(false);
        DecisionPoints.exitRow();
    }

    @Test
    public void aCopyAnswererSeesOnlyCopiesAndBothSeats() {
        final Game game = initAndCreateGame();
        final List<String> seen = new ArrayList<>();
        DecisionPoints.setCopyAnswerers(new DecisionPoints.ObservationOnly() {
            @Override
            public DecisionAnswerer forSeat(final Player seat, final String row) {
                return (dp, cur) -> {
                    seen.add(row + "/" + seat.getName() + "/" + dp.ctx());
                    return cur;
                };
            }
        });
        DecisionPoints.enterRow("1:1:7");
        final Game copy = new GameCopier(game).makeCopy();
        DecisionPoints.exitRow();
        for (Player p : game.getPlayers()) {
            assertNull(DecisionPoints.copyAnswerer(p), "the live game has no copy answerer");
        }
        for (Player p : copy.getPlayers()) {
            assertNotNull(DecisionPoints.copyAnswerer(p), "both seats of a copy have one");
        }
        final Player cp = copy.getPlayers().get(1);
        addCard("Island", cp);
        final Card ring = addCardToZone("Sol Ring", cp, ZoneType.Hand);
        final SpellAbility sa = ring.getFirstSpellAbility();
        sa.setActivatingPlayer(cp);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(cp, sa, null));
        assertTrue(seen.stream().anyMatch(s -> s.startsWith("1:1:7/") && s.endsWith("/seam:mana")), seen.toString());
    }

    @Test
    public void theMeterCountsARowsForgeAnswers() {
        final Game game = initAndCreateGame();
        DecisionPoints.setMeter(true);
        DecisionPoints.setCopyAnswerers(DecisionPoints.FORGE_DEFAULT);
        DecisionPoints.enterRow("2:0:3");
        final Game copy = new GameCopier(game).makeCopy();
        DecisionPoints.exitRow();
        final Player cp = copy.getPlayers().get(1);
        addCard("Island", cp);
        final Card ring = addCardToZone("Sol Ring", cp, ZoneType.Hand);
        final SpellAbility sa = ring.getFirstSpellAbility();
        sa.setActivatingPlayer(cp);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(cp, sa, null));
        final Map<String, Integer> m = DecisionPoints.takeMeter("2:0:3");
        assertNotNull(m);
        assertEquals((int) m.getOrDefault("SELECT|forge", 0) > 0, true, m.toString());
        assertNull(DecisionPoints.takeMeter("2:0:3"), "taken once");
    }

    @Test
    public void aCopyAnswererThatDoesNotDeclareObservationOnlyIsRefused() {
        org.testng.Assert.expectThrows(IllegalArgumentException.class,
                () -> DecisionPoints.setCopyAnswerers((seat, row) -> (dp, cur) -> cur));
        DecisionPoints.setCopyAnswerers(DecisionPoints.FORGE_DEFAULT);
    }
}
