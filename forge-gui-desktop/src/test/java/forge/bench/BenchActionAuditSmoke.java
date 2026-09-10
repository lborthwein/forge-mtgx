package forge.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

/** Actual paid cast/land event receipts, not the controller's always-true return. */
public final class BenchActionAuditSmoke {
    private static void fixture(String name, boolean land, boolean execute) {
        final var wire = new ByteArrayOutputStream();
        final var channel = new JsonRpcChannel(new ByteArrayInputStream("{\"type\":\"answer\",\"id\":1,\"choice\":1}\n".getBytes(StandardCharsets.UTF_8)), wire);
        final var session = new BenchSession(channel);
        final var lobby = new LobbyPlayerBridge("Payer", null, session, BenchSession.Mode.BRIDGE, 0);
        lobby.setAiProfile("Default");
        final var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1, 0, null, "Default")));
        final var game = new Match(new GameRules(GameType.Constructed), registered, "Action receipt fixture").createGame();
        final var player = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        StaticData.instance().attemptToLoadCard(name);
        final var card = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), player);
        card.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Hand).add(card);
        Card source = null;
        if (!land) {
            source = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Plains"), player);
            source.setGameTimestamp(game.getNextTimestamp());
            player.getZone(ZoneType.Battlefield).add(source);
            source.setSickness(false);
        }
        game.getAction().checkStateEffects(true);
        BenchRandomAudit.install(95600);
        BenchActionAudit.beginGame(game, "g1");
        game.subscribeToEvents(new BenchMain.EventEmitter(channel, "g1", game));
        final var capture = new ByteArrayOutputStream();
        final PrintStream stderr = System.err;
        try {
            System.setErr(new PrintStream(capture, true, StandardCharsets.UTF_8));
            final var chosen = player.getController().chooseSpellAbilityToPlay();
            if (chosen == null || chosen.size() != 1) throw new AssertionError("Expected chosen action");
            if (capture.toString(StandardCharsets.UTF_8).contains("engine-stack-add")
                    || capture.toString(StandardCharsets.UTF_8).contains("engine-land-played")) throw new AssertionError("Request falsely reported as executed");
            if (execute) player.getController().playChosenSpellAbility(chosen.get(0));
            BenchActionAudit.finishGame(game);
        } finally { System.setErr(stderr); }
        final String audit = capture.toString(StandardCharsets.UTF_8);
        if (execute) {
            if (audit.contains("BENCH_INTEGRITY_")) throw new AssertionError("Receipt failed: " + audit);
            if (!audit.contains(land ? "engine-land-played" : "engine-stack-add")) throw new AssertionError("No genuine engine event: " + audit);
            if (!land && (source == null || !source.isTapped() || game.getStack().size() != 1)) throw new AssertionError("Spell was not paid and put on stack");
            if (land && (!card.isInZone(ZoneType.Battlefield) || player.getLandsPlayedThisTurn() != 1)) throw new AssertionError("Land was not played");
            final String summary = audit.lines().filter(l -> l.startsWith("[bench-action] ") && l.contains("\"kind\":\"summary\"")).findFirst().orElseThrow();
            final var row = JsonParser.parseString(summary.substring("[bench-action] ".length())).getAsJsonObject();
            if (row.get("observed").getAsInt() != 1 || row.get("unobserved").getAsInt() != 0) throw new AssertionError(summary);
        } else if (!audit.contains("BENCH_INTEGRITY_UNSUPPORTED ACTION_RECEIPT: selected action lacks engine event receipt")) {
            throw new AssertionError("Unexecuted selection was certified: " + audit);
        }
        if (wire.toString(StandardCharsets.UTF_8).contains("forge-bench-private-action")) throw new AssertionError("Private receipts leaked to policy");
        System.out.println("PASS actual action receipt " + name + " execute=" + execute);
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "action-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            fixture("Savannah Lions", false, true);
            fixture("Plains", true, true);
            fixture("Savannah Lions", false, false);
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
