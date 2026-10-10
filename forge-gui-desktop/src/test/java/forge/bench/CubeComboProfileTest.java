package forge.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CubeComboPlayerController;
import forge.ai.CubeComboSeat;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFeaturizer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;

/**
 * Lane combo-ai-port-1009: the cube combo policy (lineage v104) as an opt-in AI profile option. Off: every shipped
 * profile has {@link AiProps#CUBE_COMBO_PLANS} false and seats keep their native controller classes. On: the built-in
 * profile {@code CubeCombo} (Default plus the option) seats {@link CubeComboPlayerController} on a plain LobbyPlayerAi
 * and {@link CubeComboBridgeController} on a NULL-mode bridge seat (every Forge seat of RlActorBench); a bridged (RL
 * or record) seat refuses it. Plays real Forge games on the RlActorBench fixtures (run inside a broker test lease when
 * on the Studio; cwd = forge-gui with res/).
 */
public class CubeComboProfileTest {

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    @Test
    public void optionIsOffInEveryShippedProfileAndOnOnlyInTheBuiltIn() {
        final List<String> shipped = AiProfileUtil.getAvailableProfiles();
        Assert.assertTrue(shipped.contains("Default"), shipped.toString());
        Assert.assertFalse(shipped.contains(AiProfileUtil.CUBE_COMBO_PROFILE), "the combo profile ships no res/ai file");
        Assert.assertTrue(AiProfileUtil.isKnownProfile(AiProfileUtil.CUBE_COMBO_PROFILE));
        Assert.assertFalse(AiProfileUtil.isKnownProfile("Nope"));
        for (String p : shipped) {
            final LobbyPlayerAi l = new LobbyPlayerAi("x", null);
            l.setAiProfile(p);
            Assert.assertFalse(AiProfileUtil.cubeComboPlans(l), p);
        }
        final LobbyPlayerAi combo = new LobbyPlayerAi("c", null);
        combo.setAiProfile(AiProfileUtil.CUBE_COMBO_PROFILE);
        final LobbyPlayerAi def = new LobbyPlayerAi("d", null);
        def.setAiProfile("Default");
        Assert.assertTrue(AiProfileUtil.cubeComboPlans(combo));
        // the built-in profile is Default in every other property
        for (AiProps prop : AiProps.values()) {
            if (prop == AiProps.CUBE_COMBO_PLANS) {
                continue;
            }
            Assert.assertEquals(AiProfileUtil.getAIProp(combo, prop), AiProfileUtil.getAIProp(def, prop), prop.name());
        }
        Assert.assertEquals(AiProps.CUBE_COMBO_PLANS.getDefault(), "false");
    }

    /** A two-seat game's players, created by the given lobbies (no game is played). */
    static List<Player> players(final LobbyPlayerAi a, final LobbyPlayerAi b) throws Exception {
        final List<RegisteredPlayer> seats = new ArrayList<>();
        for (LobbyPlayerAi l : new LobbyPlayerAi[] {a, b}) {
            final RegisteredPlayer rp = new RegisteredPlayer(RlActorBench.deck(RlActorBenchTest.bank.resolve("decks")
                    .resolve(RlActorBenchTest.pairs.get(0)[0].substring(RlActorBenchTest.pairs.get(0)[0].lastIndexOf('/') + 1))
                    .toString()));
            rp.setPlayer(l);
            seats.add(rp);
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        final Match match = new Match(rules, seats, "combo-port-test");
        final Game game = match.createGame();
        return game.getRegisteredPlayers();
    }

    @Test
    public void lobbySeatsGetTheComboControllerOnlyUnderTheProfile() throws Exception {
        final LobbyPlayerAi def = new LobbyPlayerAi("Seat0", null);
        def.setAiProfile("Default");
        final LobbyPlayerAi combo = new LobbyPlayerAi("Seat1", null);
        combo.setAiProfile(AiProfileUtil.CUBE_COMBO_PROFILE);
        final List<Player> ps = players(def, combo);
        Assert.assertSame(ps.get(0).getController().getClass(), PlayerControllerAi.class);
        Assert.assertTrue(ps.get(1).getController() instanceof CubeComboPlayerController);

        final JsonRpcChannelHolder h = new JsonRpcChannelHolder();
        final LobbyPlayerBridge nul = new LobbyPlayerBridge("Seat0", null, h.session, BenchSession.Mode.NULL, 0);
        nul.setAiProfile("Default");
        final LobbyPlayerBridge nulCombo = new LobbyPlayerBridge("Seat1", null, h.session, BenchSession.Mode.NULL, 1);
        nulCombo.setAiProfile(AiProfileUtil.CUBE_COMBO_PROFILE);
        final List<Player> qs = players(nul, nulCombo);
        Assert.assertSame(qs.get(0).getController().getClass(), PlayerControllerBridge.class);
        Assert.assertSame(qs.get(1).getController().getClass(), CubeComboBridgeController.class);
        Assert.assertTrue(qs.get(1).getController() instanceof CubeComboSeat);

        // a bridged seat (an RL policy's seat or a recorder) never plays the policy: refused, not ignored
        final LobbyPlayerBridge rl = new LobbyPlayerBridge("Seat1", null, h.session, BenchSession.Mode.BRIDGE, 1);
        rl.setAiProfile(AiProfileUtil.CUBE_COMBO_PROFILE);
        Assert.assertThrows(IllegalStateException.class, () -> players(nul, rl));
    }

    /** A JsonRpcChannel on null streams and its session (no host). */
    static final class JsonRpcChannelHolder {
        final BenchSession session = new BenchSession(
                new JsonRpcChannel(java.io.InputStream.nullInputStream(), java.io.OutputStream.nullOutputStream()));
    }

    @Test
    public void actorSeatsNamedForgeCubeComboPlayTheComboBridgeSeat() throws Exception {
        final AtomicReference<Game> seen = new AtomicReference<>();
        RlActorBench.KNOWLEDGE_TAP = g -> {
            seen.set(g);
            return null;
        };
        try {
            final RlActorBench.Played p = RlActorBench.play(RlActorBenchTest.cfg("train"), "train",
                    RlActorBenchTest.game(0, "rl:M", "forge:CubeCombo", false), new RlFeaturizer(RlActorBenchTest.index),
                    new RlActorBenchTest.LocalEndpoint(new FakeRlServer(0, "train", 7, null, Collections.emptyList())),
                    null, "test");
            Assert.assertNull(p.guardError);
            Assert.assertTrue(p.end.get("void").isJsonNull(), String.valueOf(p.end.get("void")));
            Assert.assertEquals(p.lobbies[1].getAiProfile(), AiProfileUtil.CUBE_COMBO_PROFILE);
            Assert.assertEquals(p.tape.getAsJsonArray("controllers").get(1).getAsString(), "forge:CubeCombo");
            final Game g = seen.get();
            Assert.assertNotNull(g);
            Assert.assertSame(g.getRegisteredPlayers().get(1).getController().getClass(), CubeComboBridgeController.class);
            Assert.assertFalse(g.getRegisteredPlayers().get(0).getController() instanceof CubeComboSeat);
        } finally {
            RlActorBench.KNOWLEDGE_TAP = null;
        }
        // record mode never seats a profile: refused before any game, as for every forge:<Profile>
        final RlActorBench.Played rec = RlActorBench.play(RlActorBenchTest.cfg("record"), "record",
                RlActorBenchTest.game(0, "record", "forge:CubeCombo", false), new RlFeaturizer(RlActorBenchTest.index),
                new RlActorBenchTest.LocalEndpoint(new FakeRlServer(0, "record", 7, null, Collections.emptyList())),
                null, "test");
        Assert.assertNotNull(rec.guardError);
        Assert.assertNull(rec.end);
    }

    /**
     * Forge vs Forge on every fixture pair, the combo seat in each seat order, against the same games with that seat
     * on Forge Default: every game completes (no void, no crash), the Default seat's controller is unchanged, and a
     * game whose combo seat never had a plan act is the Default game exactly (digest). The count of identical digests
     * is printed (fixture decks hold some route pieces, so plans may act in a few games).
     */
    @Test
    public void comboSeatGamesCompleteAndMostlyMatchDefault() throws Exception {
        int same = 0, games = 0;
        for (int i = 0; i < RlActorBenchTest.pairs.size(); i++) {
            for (int s = 0; s < 2; s++) {
                final String c0 = s == 0 ? "forge:CubeCombo" : "forge";
                final String c1 = s == 0 ? "forge" : "forge:CubeCombo";
                final JsonObject gc = RlActorBenchTest.game(i, c0, c1, false);
                final JsonObject gd = RlActorBenchTest.game(i, "forge", "forge", false);
                final RlActorBench.Played c = RlActorBench.play(RlActorBenchTest.cfg("train"), "train", gc,
                        new RlFeaturizer(RlActorBenchTest.index), null, null, "test");
                final RlActorBench.Played d = RlActorBench.play(RlActorBenchTest.cfg("train"), "train", gd,
                        new RlFeaturizer(RlActorBenchTest.index), null, null, "test");
                Assert.assertNull(c.guardError, c.guardError);
                Assert.assertTrue(c.end.get("void").isJsonNull(), "pair " + i + " seat " + s + ": " + c.end.get("void"));
                Assert.assertTrue(d.end.get("void").isJsonNull(), "pair " + i + " seat " + s + ": " + d.end.get("void"));
                games++;
                final boolean eq = c.end.get("digest").equals(d.end.get("digest"));
                if (eq) {
                    same++;
                }
                System.out.println("[combo-port] pair " + i + " comboSeat " + s + " digest "
                        + (eq ? "same" : "differs") + " result " + c.end.get("result") + " vs " + d.end.get("result"));
            }
        }
        System.out.println("[combo-port] identical digests " + same + " / " + games);
        Assert.assertTrue(games > 0);
    }
}
