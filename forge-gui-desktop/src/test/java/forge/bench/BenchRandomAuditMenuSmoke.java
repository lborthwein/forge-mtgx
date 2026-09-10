package forge.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import com.google.gson.JsonParser;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

/** Actual-card full controller menu + encoding, repeatedly answered pass. No matches. */
public final class BenchRandomAuditMenuSmoke {
    private static void fixture(String spell, String land) {
        final String answers = "{\"type\":\"answer\",\"id\":1,\"choice\":0}\n"
                + "{\"type\":\"answer\",\"id\":2,\"choice\":0}\n";
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final BenchSession session = new BenchSession(new JsonRpcChannel(
                new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8)), wire));
        final LobbyPlayerBridge lobby = new LobbyPlayerBridge("Payer", null, session, BenchSession.Mode.BRIDGE, 0);
        lobby.setAiProfile("Default");
        final var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1, 0, null, "Default")));
        final var game = new Match(new GameRules(GameType.Constructed), registered, "RNG menu fixture").createGame();
        final var player = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        for (final String name : List.of(spell, land)) {
            StaticData.instance().attemptToLoadCard(name);
            final var paper = FModel.getMagicDb().getCommonCards().getCard(name);
            if (paper == null) throw new AssertionError("Missing pinned card " + name);
            final Card card = Card.fromPaperCard(paper, player);
            card.setGameTimestamp(game.getNextTimestamp());
            player.getZone(name.equals(land) ? ZoneType.Battlefield : ZoneType.Hand).add(card);
            card.setSickness(false);
        }
        game.getAction().checkStateEffects(true);
        BenchRandomAudit.install(95600);
        final var before = BenchRandomAudit.begin();
        for (int i = 0; i < 2; i++) {
            if (player.getController().chooseSpellAbilityToPlay() != null) throw new AssertionError("Pass changed identity");
        }
        BenchRandomAudit.assertUnchanged(before, "two real " + spell + " menus");
        final String[] lines = wire.toString(StandardCharsets.UTF_8).trim().split("\n");
        if (lines.length != 2) throw new AssertionError("Expected exactly two asks, not " + lines.length);
        for (final String line : lines) {
            final var ask = JsonParser.parseString(line).getAsJsonObject();
            if (ask.getAsJsonArray("menu").size() < 2) throw new AssertionError("Missing affordable spell " + spell);
            if (line.contains("forge-bench-private-rng") || ask.has("rng")) throw new AssertionError("RNG leaked into observation");
        }
        if (MyRandom.getRandom().nextLong() != new Random(95600).nextLong()) throw new AssertionError("Menu changed next RNG result");
        System.out.println("PASS actual-card full-menu RNG neutrality: " + spell + " / " + land);
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "rng-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            fixture("Lightning Bolt", "Mountain");
            fixture("Savannah Lions", "Plains");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
