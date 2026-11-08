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

    private static boolean isDraw(final GameOutcome outcome, final boolean honorReason) throws Exception {
        try {
            return (Boolean) GameOutcome.class.getMethod("isDraw", boolean.class).invoke(outcome, honorReason);
        } catch (NoSuchMethodException baseline) {
            return outcome.isDraw();
        }
    }

    private static boolean configured(final JsonObject config) throws Exception {
        final RlActorBench.Cfg parsed = RlActorBench.parse(config);
        try {
            final Field field = parsed.getClass().getDeclaredField("honorDrawReason");
            field.setAccessible(true);
            return field.getBoolean(parsed);
        } catch (NoSuchFieldException baseline) {
            return false;
        }
    }

    @Test
    public void explicitDrawOverridesSurvivingWinBitsOnlyWhenEnabled() throws Exception {
        final GameOutcome draw = survivingWinner(GameEndReason.Draw);
        Assert.assertFalse(draw.isDraw(), "reproduction: legacy outcome disagrees with its explicit draw reason");
        Assert.assertFalse(isDraw(draw, false), "keys-off identity");
        Assert.assertTrue(isDraw(draw, true), "actor must leave both results zero for an explicit draw");
        Assert.assertFalse(isDraw(survivingWinner(GameEndReason.AllOpposingTeamsLost), true));
    }

    @Test
    public void actorKeyIsOffByDefault() throws Exception {
        final JsonObject config = new JsonObject();
        Assert.assertFalse(configured(config));
        config.addProperty("honorDrawReason", true);
        Assert.assertTrue(configured(config));
    }

    @Test
    public void searchSpecReachesTheActorKey() throws Exception {
        final JsonObject search = new JsonObject();
        search.addProperty("server", "127.0.0.1:1");
        final JsonObject config = new JsonObject();
        config.add("search", search);
        Assert.assertFalse(configured(config), "spec without the key: off");
        search.addProperty("honorDrawReason", true);
        Assert.assertTrue(configured(config), "spec with the key: on");
    }
}
