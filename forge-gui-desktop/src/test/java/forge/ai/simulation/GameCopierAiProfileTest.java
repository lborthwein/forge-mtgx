package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * A look-ahead play-out must be played by the same Forge AI profile as the seat it copies (lane pf1-1003: the bench's
 * per-seat "aiProfiles"). The look-ahead copies the live game with plain AI players ({@code new GameCopier(live, true)});
 * each copied player must hold its original's profile, and read that profile's properties.
 */
public class GameCopierAiProfileTest extends SimulationTest {

    private static void setProfiles(Game game, String p0, String p1) {
        ((LobbyPlayerAi) game.getPlayers().get(0).getLobbyPlayer()).setAiProfile(p0);
        ((LobbyPlayerAi) game.getPlayers().get(1).getLobbyPlayer()).setAiProfile(p1);
    }

    private static void assertCopyKeepsProfiles(Game game, boolean plainAiPlayers) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(1));
        final GameCopier copier = new GameCopier(game, plainAiPlayers);
        final Game copy = copier.makeCopy();
        AssertJUnit.assertNotSame(game, copy);
        for (Player orig : game.getPlayers()) {
            final Player c = copier.find(orig);
            AssertJUnit.assertNotNull(c);
            final String want = ((LobbyPlayerAi) orig.getLobbyPlayer()).getAiProfile();
            AssertJUnit.assertTrue(c.getLobbyPlayer() instanceof LobbyPlayerAi);
            AssertJUnit.assertEquals("copy of " + orig.getName() + " (plain=" + plainAiPlayers + ")", want,
                    ((LobbyPlayerAi) c.getLobbyPlayer()).getAiProfile());
            AssertJUnit.assertEquals(AiProfileUtil.getProperty(orig, AiProps.CHANCE_TO_COUNTER_CMC_1),
                    AiProfileUtil.getProperty(c, AiProps.CHANCE_TO_COUNTER_CMC_1));
            AssertJUnit.assertEquals(AiProfileUtil.getProperty(orig, AiProps.PLAY_AGGRO),
                    AiProfileUtil.getProperty(c, AiProps.PLAY_AGGRO));
        }
    }

    @Test
    public void plainPlayoutCopiesKeepEachSeatsProfile() {
        final Game game = initAndCreateGame();
        setProfiles(game, "Reckless", "Default");
        assertCopyKeepsProfiles(game, true);
        AssertJUnit.assertEquals("80", AiProfileUtil.getProperty(game.getPlayers().get(0), AiProps.CHANCE_TO_COUNTER_CMC_1));
        AssertJUnit.assertEquals("30", AiProfileUtil.getProperty(game.getPlayers().get(1), AiProps.CHANCE_TO_COUNTER_CMC_1));
    }

    @Test
    public void everyShippedProfileCarriesIntoTheCopy() {
        for (String p : new String[] {"Default", "Reckless", "Cautious", "Experimental"}) {
            final Game game = initAndCreateGame();
            setProfiles(game, "Default", p);
            assertCopyKeepsProfiles(game, true);
            setProfiles(game, p, "Default");
            assertCopyKeepsProfiles(game, true);
        }
    }
}
