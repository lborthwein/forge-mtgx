package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.*;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real controller/effect history, recipient isolation, LKI and hidden-world controls.
 * Names-only callback coverage is NOT complete rules/observation certification. */
public final class RevealHistorySmoke {
    private static int checks;
    private static final JsonArray snapshots = new JsonArray();
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    private record Context(Game game, BenchSession session, ByteArrayOutputStream wire) {
        Player player(int seat) { return game.getPlayers().get(seat); }
        PlayerControllerBridge controller(int seat) { return (PlayerControllerBridge) player(seat).getController(); }
    }
    private static Context context() {
        var wire = new ByteArrayOutputStream();
        var session = new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]), wire));
        var players = new ArrayList<RegisteredPlayer>();
        for (int seat = 0; seat < 2; seat++) {
            var lobby = new LobbyPlayerBridge("Seat" + seat, null, session, BenchSession.Mode.BRIDGE, seat);
            lobby.setAiProfile("Default"); players.add(new RegisteredPlayer(new Deck()).setPlayer(lobby));
        }
        var game = new Match(new GameRules(GameType.Constructed), players, "Reveal history").createGame();
        game.setAge(GameStage.Play); session.setLiveGame(game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return new Context(game, session, wire);
    }
    private static Card card(Context c, int seat, String name, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var result = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), c.player(seat));
        result.setGameTimestamp(c.game.getNextTimestamp()); c.player(seat).getZone(zone).add(result); return result;
    }
    private static JsonObject state(Context c, int seat) {
        try {
            var method = PlayerControllerBridge.class.getDeclaredMethod("envelope", boolean.class); method.setAccessible(true);
            return ((JsonObject) method.invoke(c.controller(seat), true)).getAsJsonObject("state");
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static JsonArray history(Context c, int seat) { return state(c, seat).getAsJsonArray("revealHistory"); }
    private static JsonArray names(String... names) { var array = new JsonArray(); for (var name : names) array.add(name); return array; }
    private static int bucket(Context c, int seat, String owner) {
        return c.controller(seat).getCounters().toJson().getAsJsonObject("controllerCoverage")
            .getAsJsonObject("methods").getAsJsonObject("reveal").get(owner).getAsInt();
    }
    private static void privateHistory(int seat, boolean views) {
        var c = context();
        var a = card(c, 1-seat, "Sol Ring", ZoneType.Library);
        var b = card(c, 1-seat, "Forest", ZoneType.Library);
        var duplicate = card(c, 1-seat, "Forest", ZoneType.Library);
        card(c, 1-seat, "Grave Titan", ZoneType.Library);
        check(history(c, seat).isEmpty(), "initial history empty");
        if (views) c.controller(seat).reveal(List.of(a.getView(), b.getView(), duplicate.getView()), ZoneType.Library, c.player(1-seat).getView(), "ignored", false);
        else c.controller(seat).reveal(new CardCollection(List.of(a,b,duplicate)), ZoneType.Library, c.player(1-seat), "ignored", false);
        var before = history(c, seat);
        check(before.size() == 1, "one historical callback");
        check(before.get(0).getAsJsonObject().getAsJsonArray("names").equals(names("Forest", "Forest", "Sol Ring")), "sorted duplicate-preserving disclosure");
        check(before.get(0).getAsJsonObject().keySet().equals(Set.of("sequence", "owner", "zone", "names")), "no live identifiers or position fields");
        check(history(c, 1-seat).isEmpty(), "private look not shared with other controller");
        check(!state(c, seat).toString().contains("Grave Titan"), "unrevealed suffix remains hidden");
        c.game.getAction().moveToHand(a, null);
        c.player(1-seat).shuffle(null);
        c.controller(seat).resetAtEndOfTurn();
        check(history(c, seat).equals(before), "hidden move shuffle and end turn retain only past facts");
        check(state(c, seat).getAsJsonArray("players").get(1-seat).getAsJsonObject().getAsJsonArray("hand").isEmpty(), "history never identifies newly hidden hand membership");
        before.get(0).getAsJsonObject().getAsJsonArray("names").set(0, new JsonPrimitive("mutated"));
        check(!history(c, seat).toString().contains("mutated"), "export cannot mutate ledger");
        check(bucket(c, seat, "rules") == 0 && bucket(c, seat, "stock") == 0 && bucket(c, seat, "unclassified") == 1, "partial name delivery remains untrusted, never stock or certified rules");
        check(c.wire.size() == 0, "recording did not need a host decision or notification");
    }
    private static void actualDig(int seat) {
        var c = context();
        var source = card(c, seat, "Noise Marine", ZoneType.Battlefield);
        card(c, seat, "Forest", ZoneType.Library); card(c, seat, "Forest", ZoneType.Library);
        card(c, seat, "Sol Ring", ZoneType.Library); card(c, seat, "Grave Titan", ZoneType.Library);
        var ability = AbilityFactory.getAbility("DB$ DigUntil | Defined$ You | Valid$ Card.nonLand | Amount$ 1 | FoundDestination$ Exile | RevealedDestination$ Library | RevealedLibraryPosition$ -1 | RevealRandomOrder$ True", source);
        ability.setActivatingPlayer(c.player(seat)); AbilityUtils.resolve(ability);
        check(c.player(seat).getCardsIn(ZoneType.Exile).stream().anyMatch(card -> card.getName().equals("Sol Ring")), "engine executed found-card movement");
        for (int viewer = 0; viewer < 2; viewer++) {
            var history = history(c, viewer);
            check(history.size() == 1, "actual DigUntil reveals to both recipients");
            var entry = history.get(0).getAsJsonObject();
            check(entry.getAsJsonArray("names").equals(names("Forest", "Forest", "Sol Ring")), "effect records entire disclosed prefix, not found only");
            check(entry.get("owner").getAsInt() == seat && entry.get("zone").getAsString().equals("library"), "callback labels survive movement");
            check(!history.toString().contains("Grave Titan"), "undisclosed suffix absent");
            var snapshot = new JsonObject(); snapshot.addProperty("seat", viewer); snapshot.add("state", state(c, viewer)); snapshots.add(snapshot);
        }
        check(c.session.integrityFailure(c.game) == null, "effect did not latch an integrity failure");
    }
    private static void lkiAndFace(int seat) {
        var c = context(); var original = card(c, 1-seat, "Sol Ring", ZoneType.Library);
        var lki = CardCopyService.getLKICopy(original);
        c.game.getAction().moveToHand(original, null);
        c.controller(seat).reveal(List.of(lki.getView()), ZoneType.Library, c.player(1-seat).getView(), "LKI", false);
        check(history(c, seat).get(0).getAsJsonObject().getAsJsonArray("names").equals(names("Sol Ring")), "LKI view accepted without resolving live identity");
        var morph = card(c, seat, "Grave Titan", ZoneType.Battlefield); morph.turnFaceDown(true);
        c.controller(seat).reveal(new CardCollection(morph), ZoneType.Battlefield, c.player(seat), "own face", false);
        check(history(c, seat).get(1).getAsJsonObject().getAsJsonArray("names").equals(names("Grave Titan")), "authorized face-down original retained");
        c.controller(seat).reveal(List.of(morph.getView()), ZoneType.Battlefield, c.player(seat).getView(), "own face", false);
        check(history(c, seat).get(2).getAsJsonObject().getAsJsonArray("names").equals(names("Grave Titan")), "authorized face-down view retained");
        check(history(c, 1-seat).isEmpty() && !morph.getView().canFaceDownBeShownTo(c.player(1-seat).getView()), "unrevealed face remains unknown to other viewer");
        c.controller(1-seat).reveal(new CardCollection(morph), ZoneType.Battlefield, c.player(seat), "explicit reveal", false);
        check(history(c, 1-seat).get(0).getAsJsonObject().getAsJsonArray("names").equals(names("Grave Titan")), "explicit callback grants previously unknown face");
        check(!morph.getView().canFaceDownBeShownTo(c.player(1-seat).getView()), "historical disclosure does not grant ongoing face visibility");
        try { c.controller(1-seat).reveal(Arrays.asList(morph.getView(), null), ZoneType.Battlefield, c.player(seat).getView(), "invalid callback", false); throw new AssertionError("invalid callback accepted"); }
        catch (RulesCostFeasibility.Unsupported expected) { check(c.session.integrityFailure(c.game) != null, "unsupported callback fails whole game"); }
        check(history(c, 1-seat).size() == 1, "rejected reveal did not append partial data");
        check(bucket(c, 1-seat, "unclassified") == 2 && bucket(c, 1-seat, "rules") == 0, "failure and partial callback remain untrusted");
    }
    private static void zoneLabels(int seat) {
        var c = context(); var original = card(c, seat, "Sol Ring", ZoneType.Library);
        var lki = CardCopyService.getLKICopy(original);
        var hand = card(c, seat, "Forest", ZoneType.Hand);
        int index = 0;
        for (ZoneType zone : ZoneType.values()) {
            c.controller(seat).reveal(List.of(zone == ZoneType.Hand ? hand.getView() : lki.getView()), zone, c.player(seat).getView(), "zone label", false);
            var entry = history(c, seat).get(index++).getAsJsonObject();
            check(entry.get("zone").getAsString().equals(zone.name().toLowerCase(Locale.ROOT)), "pinned callback zone label preserved: " + zone);
        }
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p,m,v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "reveal-history-fixture"; default -> throw new AssertionError(m.getName());
            }));
            FModel.initialize(null, p -> { p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            for (int seat = 0; seat < 2; seat++) { privateHistory(seat, false); privateHistory(seat, true); actualDig(seat); lkiAndFace(seat); zoneLabels(seat); }
            if (args.length > 1) Files.writeString(Path.of(args[1]), snapshots.toString(), StandardOpenOption.CREATE_NEW);
            System.out.println("PASS " + checks + " reveal-history checks; NOT CERTIFIED"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
