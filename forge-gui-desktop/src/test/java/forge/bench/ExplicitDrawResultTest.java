package forge.bench;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import com.google.gson.JsonObject;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.player.PlayerOutcome;
import forge.game.player.PlayerStatistics;
import forge.game.player.RegisteredPlayer;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Puzzle cutoffs call setGameOver(Draw); onGameOver can leave surviving players' win bits set. */
public class ExplicitDrawResultTest {
    private static GameOutcome survivingWinner(final GameEndReason reason) throws Exception {
        final GameOutcome o = new GameOutcome(reason, Collections.emptyList());
        final PlayerStatistics stats = new PlayerStatistics();
        stats.setOutcome(PlayerOutcome.win());
        final Field f = GameOutcome.class.getDeclaredField("playerRating");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<RegisteredPlayer, PlayerStatistics> ratings = (Map<RegisteredPlayer, PlayerStatistics>) f.get(o);
        ratings.put(null, stats); // only the outcome bits matter to isDraw, no card DB or controller is needed
        return o;
    }

    @Test
    public void explicitDrawOverridesSurvivingWinBitsOnlyWhenEnabled() throws Exception {
        final GameOutcome draw = survivingWinner(GameEndReason.Draw);
        Assert.assertFalse(draw.isDraw(), "reproduction: legacy outcome disagrees with its explicit draw reason");
        Assert.assertFalse(draw.isDraw(false), "keys-off identity");
        Assert.assertTrue(draw.isDraw(true), "actor must leave both results zero for an explicit draw");
        Assert.assertFalse(survivingWinner(GameEndReason.AllOpposingTeamsLost).isDraw(true));
    }

    @Test
    public void actorKeyIsOffByDefault() {
        final JsonObject config = new JsonObject();
        Assert.assertFalse(RlActorBench.parse(config).honorDrawReason);
        config.addProperty("honorDrawReason", true);
        Assert.assertTrue(RlActorBench.parse(config).honorDrawReason);
    }
}
