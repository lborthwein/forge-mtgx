package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Production priority-menu witness for the spell face carried by a cast option. */
public final class SpellFaceIdentitySmoke {
    private static int checks;
    private record Expect(String name, CardStateName state, boolean adventure, boolean omen) {}
    private static final List<Expect> EXPECTED = List.of(
            new Expect("Brazen Borrower", CardStateName.Original, false, false),
            new Expect("Brazen Borrower", CardStateName.Secondary, true, false),
            new Expect("Bonecrusher Giant", CardStateName.Original, false, false),
            new Expect("Bonecrusher Giant", CardStateName.Secondary, true, false),
            new Expect("Birgi, God of Storytelling", CardStateName.Original, false, false),
            new Expect("Birgi, God of Storytelling", CardStateName.Backside, false, false),
            new Expect("Valki, God of Lies", CardStateName.Original, false, false),
            new Expect("Valki, God of Lies", CardStateName.Backside, false, false));

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), "Missing registered card " + name);
        var result = Card.fromPaperCard(paper, player);
        result.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(result);
        result.setSickness(false);
        return result;
    }
    private static int sequence(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
    private static Object decision(Player player) throws Exception {
        Method method = PlayerControllerBridge.class.getDeclaredMethod("buildPriorityDecision");
        method.setAccessible(true);
        return method.invoke(player.getController());
    }
    @SuppressWarnings("unchecked")
    private static List<SpellAbility> menu(Object decision) throws Exception {
        Method method = decision.getClass().getDeclaredMethod("menu");
        method.setAccessible(true);
        return (List<SpellAbility>) method.invoke(decision);
    }
    private static JsonObject body(Object decision) throws Exception {
        Method method = decision.getClass().getDeclaredMethod("body");
        method.setAccessible(true);
        return ((JsonObject) method.invoke(decision)).deepCopy();
    }
    private static SpellAbility option(List<SpellAbility> menu, Card card, CardStateName state) {
        return menu.stream().filter(sa -> sa.isSpell() && sa.getHostCard().getId() == card.getId()
                && sa.getCardStateName() == state).findFirst().orElseThrow();
    }
    private static JsonObject encoded(JsonObject body, List<SpellAbility> menu, SpellAbility option) {
        int index = menu.indexOf(option);
        if (index < 0) throw new AssertionError("option not in production menu");
        return body.getAsJsonArray("menu").get(index + 1).getAsJsonObject(); // menu[0] is pass
    }
    private static boolean allowed(JsonObject domain, String kind, int id) {
        for (var row : domain.getAsJsonArray("rows")) {
            JsonObject entry = row.getAsJsonObject();
            if (kind.equals(entry.get("kind").getAsString()) && id == entry.get("id").getAsInt())
                return entry.get("allowed").getAsBoolean();
        }
        throw new AssertionError("missing target-domain row " + kind + "/" + id);
    }
    private static void faceCase(int seat) throws Exception {
        var session = new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        var lobby = new LobbyPlayerBridge("Face Actor", null, session, BenchSession.Mode.BRIDGE, seat);
        lobby.setAiProfile("Default");
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var foe = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Face Other", 1 - seat, 0, null, "Default"));
        var game = new Match(new GameRules(GameType.Constructed), seat == 0 ? List.of(own, foe) : List.of(foe, own),
                "Spell face fixture").createGame();
        game.setAge(GameStage.Play);
        var actor = game.getPlayers().get(seat);
        var opponent = game.getPlayers().get(1 - seat);
        session.setLiveGame(game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, actor);
        // Native payment/casting enumeration gets ordinary, sufficient real sources.
        for (String land : List.of("Plains", "Island", "Island", "Swamp", "Swamp", "Mountain", "Mountain", "Mountain",
                "Forest", "Forest", "Island", "Swamp", "Mountain")) card(land, actor, ZoneType.Battlefield);
        card("Grizzly Bears", opponent, ZoneType.Battlefield); // Petty Theft's nonland-opponent target
        card("Brazen Borrower", actor, ZoneType.Hand);
        card("Bonecrusher Giant", actor, ZoneType.Hand);
        card("Birgi, God of Storytelling", actor, ZoneType.Hand);
        card("Valki, God of Lies", actor, ZoneType.Hand);
        game.getPhaseHandler().setPriority(actor);
        game.getAction().checkStaticAbilities();
        var sourceByName = Map.of(
                "Brazen Borrower", actor.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Brazen Borrower")).findFirst().orElseThrow(),
                "Bonecrusher Giant", actor.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Bonecrusher Giant")).findFirst().orElseThrow(),
                "Birgi, God of Storytelling", actor.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Birgi, God of Storytelling")).findFirst().orElseThrow(),
                "Valki, God of Lies", actor.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Valki, God of Lies")).findFirst().orElseThrow());
        var originalStates = sourceByName.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().getCurrentStateName()));
        BenchRandomAudit.install(91300 + seat);
        var before = BenchMenuStateAudit.capture(game);
        var rng = BenchRandomAudit.begin();
        int abilities = sequence(SpellAbility.class, "maxId");
        int instances = sequence(SpellAbilityStackInstance.class, "maxId");
        Object decision = decision(actor); // reflection only reaches the actual production builder
        var menu = menu(decision);
        var ask = body(decision);
        ask.addProperty("type", "ask");
        ask.addProperty("kind", "priority");
        ask.addProperty("id", 931000000 + seat);
        ask.addProperty("fixtureSynthetic", true);
        ask.addProperty("fixtureProvenance", "DEVELOPMENT SpellFaceIdentitySmoke synthetic observation; not a live RPC ask");
        Set<String> observedStates = new java.util.HashSet<>();
        for (Expect expected : EXPECTED) {
            Card source = sourceByName.get(expected.name());
            SpellAbility offered = option(menu, source, expected.state());
            JsonObject item = encoded(ask, menu, offered);
            check(item.has("spellFace"), "spell option carries structural face " + expected.name() + "/" + expected.state() + " seat=" + seat);
            JsonObject face = item.getAsJsonObject("spellFace");
            check("host-spell-face-v1".equals(face.get("version").getAsString())
                    && expected.state().name().equals(face.get("state").getAsString())
                    && expected.adventure() == face.get("adventure").getAsBoolean()
                    && expected.omen() == face.get("omen").getAsBoolean(),
                    "face payload matches actual option " + expected.name() + "/" + expected.state() + " seat=" + seat);
            observedStates.add(expected.name() + "/" + face.get("state").getAsString());
            // These are not inferred from the display name: preserve native target masks/refusals.
            JsonObject domain = item.getAsJsonObject("boardTargetDomain");
            if (expected.name().equals("Brazen Borrower") && expected.adventure())
                check("exact".equals(domain.get("kind").getAsString()), "Petty Theft retains exact native target mask seat=" + seat);
            if (expected.name().equals("Brazen Borrower") && expected.adventure())
                check(allowed(domain, "card", opponent.getCardsIn(ZoneType.Battlefield).get(0).getId())
                        && !allowed(domain, "player", seat) && !allowed(domain, "player", 1 - seat),
                        "Petty Theft mask keeps only its legal nonland permanent seat=" + seat);
            if (expected.name().equals("Bonecrusher Giant") && expected.adventure())
                check("exact".equals(domain.get("kind").getAsString()), "Stomp retains exact native target mask seat=" + seat);
            if (expected.name().equals("Bonecrusher Giant") && expected.adventure())
                check(allowed(domain, "card", opponent.getCardsIn(ZoneType.Battlefield).get(0).getId())
                        && allowed(domain, "player", seat) && allowed(domain, "player", 1 - seat),
                        "Stomp mask retains card and both player targets seat=" + seat);
            JsonObject out = new JsonObject();
            out.addProperty("seat", seat);
            out.addProperty("name", expected.name());
            out.add("ask", ask);
            out.addProperty("sourceFid", source.getId());
            System.out.println("FACE_CASE " + out);
        }
        check(observedStates.size() == EXPECTED.size(), "fronts and backs have distinct spell states seat=" + seat);
        for (var entry : sourceByName.entrySet()) check(entry.getValue().getCurrentStateName() == originalStates.get(entry.getKey()),
                "original card face/state unchanged " + entry.getKey() + " seat=" + seat);
        check(!StateEncoder.encodePriorityAbility(menu.get(0), actor.getView()).has("spellFace"),
                "legacy priority encoder remains spell-face free seat=" + seat);
        BenchMenuStateAudit.assertUnchanged(before, game);
        BenchRandomAudit.assertUnchanged(rng, "spell face priority enumeration");
        check(abilities == sequence(SpellAbility.class, "maxId") && instances == sequence(SpellAbilityStackInstance.class, "maxId"),
                "spell face enumeration preserves global IDs seat=" + seat);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "spell-face-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            faceCase(0);
            faceCase(1);
            System.out.println("PASS " + checks + " spell face identity checks");
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
