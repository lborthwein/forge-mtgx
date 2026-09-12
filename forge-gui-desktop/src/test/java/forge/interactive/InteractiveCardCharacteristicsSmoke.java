package forge.interactive;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.GuiDesktop;
import forge.StaticData;
import forge.bench.StateEncoder;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.card.GamePieceType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Browser wire characteristics (colors, base P/T, isToken, tokenScript) on actual Forge
 * objects: tokens are created by resolving real Token effects, counters and the colour
 * change by real PutCounter/Animate effects, and the payload is InteractiveState's own.
 * Never runs AI decisions or a shared match.
 *
 * <p>Args: FORGE_REPO [OUT_JSON]. OUT_JSON receives the encoded human-seat view.</p>
 */
public final class InteractiveCardCharacteristicsSmoke {
    private static final Set<String> NEW_KEYS = Set.of("colors", "basePower", "baseToughness",
            "isToken", "tokenScript");
    private static int checks;

    private static Game game() {
        var registered = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Human seat", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Forge seat", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), registered, "Characteristics fixture").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }

    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing card: " + name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        card.setSickness(false);
        return card;
    }

    private static void resolve(String ability, Card host, Player activator) {
        SpellAbility sa = AbilityFactory.getAbility(ability, host);
        sa.setActivatingPlayer(activator);
        AbilityUtils.resolve(sa);
    }

    /** Creates one token through Forge's actual Token effect and returns it. */
    private static Card token(String script, Card host, Player owner) {
        var before = new ArrayList<>(owner.getCardsIn(ZoneType.Battlefield));
        resolve("DB$ Token | TokenAmount$ 1 | TokenScript$ " + script + " | TokenOwner$ You", host, owner);
        Card made = null;
        for (Card c : owner.getCardsIn(ZoneType.Battlefield)) {
            if (!before.contains(c)) {
                if (made != null) throw new AssertionError("more than one token from " + script);
                made = c;
            }
        }
        if (made == null || !made.isToken()) throw new AssertionError("no token created from " + script);
        return made;
    }

    private static JsonObject find(JsonObject view, int seat, String zone, int fid) {
        for (JsonElement e : view.getAsJsonArray("players").get(seat).getAsJsonObject().getAsJsonArray(zone)) {
            if (e.getAsJsonObject().get("fid").getAsInt() == fid) return e.getAsJsonObject();
        }
        throw new AssertionError("fid " + fid + " not in seat " + seat + " " + zone);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    private static String colors(JsonObject o) {
        return o.get("colors").toString();
    }

    private static void expect(JsonObject o, String label, String colors, Integer basePower,
                               Integer baseToughness, boolean isToken, String tokenScript) {
        for (String key : NEW_KEYS) check(o.has(key), label + " lacks " + key + ": " + o);
        check(colors.equals(colors(o)), label + " colors " + colors(o) + " != " + colors);
        check(basePower == null ? o.get("basePower").isJsonNull() : o.get("basePower").getAsInt() == basePower,
                label + " basePower " + o.get("basePower"));
        check(baseToughness == null ? o.get("baseToughness").isJsonNull()
                : o.get("baseToughness").getAsInt() == baseToughness, label + " baseToughness " + o.get("baseToughness"));
        check(o.get("isToken").getAsBoolean() == isToken, label + " isToken " + o.get("isToken"));
        check(tokenScript == null ? o.get("tokenScript").isJsonNull()
                : tokenScript.equals(o.get("tokenScript").getAsString()), label + " tokenScript " + o.get("tokenScript"));
        System.out.println("PASS " + label + " colors=" + colors(o) + " base=" + o.get("basePower") + "/"
                + o.get("baseToughness") + " net=" + o.get("power") + "/" + o.get("toughness")
                + " isToken=" + o.get("isToken") + " tokenScript=" + o.get("tokenScript"));
    }

    private static void expectNothingNew(JsonObject o, String label) {
        for (String key : NEW_KEYS) check(!o.has(key), label + " gained " + key + ": " + o);
        System.out.println("PASS " + label + " gained nothing: " + o.keySet());
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface(new GuiDesktop() { @Override public String getAssetsDir() { return args[0] + "/forge-gui/"; } });
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, true); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });

            var game = game();
            var human = game.getPlayers().get(0);
            var forge = game.getPlayers().get(1);

            var host = card("Karn, Scion of Urza", human, ZoneType.Hand);
            var spirit = token("w_1_1_spirit_flying", host, human);
            var soldier = token("c_1_1_a_soldier", host, human);
            var construct = token("c_0_0_a_construct_total_artifacts", host, human);
            resolve("DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 2", construct, human);
            var bigConstruct = token("c_4_4_a_construct", host, human);
            resolve("DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1", bigConstruct, human);
            var forgeHost = card("Lingering Souls", forge, ZoneType.Graveyard);
            var forgeSpirit = token("w_1_1_spirit_flying", forgeHost, forge);

            var bears = card("Grizzly Bears", human, ZoneType.Battlefield);
            resolve("DB$ Animate | Defined$ Self | Colors$ Blue | OverwriteColors$ True | Duration$ Permanent", bears, human);
            var figure = card("Figure of Destiny", human, ZoneType.Battlefield);
            var solRing = card("Sol Ring", human, ZoneType.Battlefield);
            var handCard = card("Lightning Bolt", human, ZoneType.Hand);
            var forgeHand = card("Swords to Plowshares", forge, ZoneType.Hand);

            // The Undercity, in the command zone, standing in its first room —
            // the object the browser needs in order to draw "you are here".
            // Built the way VentureEffect builds one: a dungeon is a token, not a
            // common card, so `getCommonCards` does not have it.
            var dungeon = CardFactory.getCard(
                    StaticData.instance().getAllTokens().getToken("undercity", "CLB"), human, game);
            dungeon.setGamePieceType(GamePieceType.DUNGEON);
            game.getAction().moveToCommand(dungeon, null);
            dungeon.setCurrentRoom("Secret Entrance");

            // A face-down 2/2 whose real face is WHITE: the opponent's is redacted, and our
            // own is shown to us but must still gain nothing.
            var forgeFaceDown = card("Exalted Angel", forge, ZoneType.Battlefield);
            check(forgeFaceDown.turnFaceDown(true), "turn opponent's Exalted Angel face down");
            var ownFaceDown = card("Exalted Angel", human, ZoneType.Battlefield);
            check(ownFaceDown.turnFaceDown(true), "turn our Exalted Angel face down");
            game.getAction().checkStaticAbilities();

            // The encoder must not consume Forge's shared RNG (PaperToken#getImageKey(boolean) would).
            MyRandom.setRandom(new Random(424242L));
            var view = InteractiveState.encode(game, human);
            final long afterEncode = MyRandom.getRandom().nextLong();
            check(afterEncode == new Random(424242L).nextLong(), "encoding consumed MyRandom");
            check(view.toString().equals(InteractiveState.encode(game, human).toString()), "encoding is not repeatable");

            expect(find(view, 0, "battlefield", spirit.getId()), "white Spirit token", "[\"W\"]", 1, 1, true, "w_1_1_spirit_flying");
            expect(find(view, 0, "battlefield", soldier.getId()), "artifact Soldier token", "[]", 1, 1, true, "c_1_1_a_soldier");
            var constructJson = find(view, 0, "battlefield", construct.getId());
            expect(constructJson, "0/0 Construct token +2 counters", "[]", 0, 0, true, "c_0_0_a_construct_total_artifacts");
            check(constructJson.get("power").getAsInt() > 2, "construct net power keeps counters and artifact pump");
            var bigJson = find(view, 0, "battlefield", bigConstruct.getId());
            expect(bigJson, "4/4 Construct token +1 counter", "[]", 4, 4, true, "c_4_4_a_construct");
            check(bigJson.get("power").getAsInt() == 5 && bigJson.get("toughness").getAsInt() == 5, "net P/T unchanged 5/5");
            expect(find(view, 1, "battlefield", forgeSpirit.getId()), "opponent white Spirit token", "[\"W\"]", 1, 1, true, "w_1_1_spirit_flying");
            expect(find(view, 0, "battlefield", bears.getId()), "Grizzly Bears turned blue", "[\"U\"]", 2, 2, false, null);
            expect(find(view, 0, "battlefield", figure.getId()), "Figure of Destiny (R/W hybrid)", "[\"W\",\"R\"]", 1, 1, false, null);
            expect(find(view, 0, "battlefield", solRing.getId()), "Sol Ring (noncreature)", "[]", null, null, false, null);
            expect(find(view, 0, "hand", handCard.getId()), "own hand Lightning Bolt", "[\"R\"]", null, null, false, null);

            var redacted = find(view, 1, "battlefield", forgeFaceDown.getId());
            check(redacted.has("identityRedacted") && redacted.get("identityRedacted").getAsBoolean(), "opponent face-down redacted");
            check(!redacted.toString().contains("Exalted"), "opponent face-down leaks name");
            expectNothingNew(redacted, "opponent face-down Exalted Angel");
            expectNothingNew(find(view, 0, "battlefield", ownFaceDown.getId()), "own face-down Exalted Angel");
            // A dungeon: a public command-zone object whose whole state is the
            // room it is standing in. Without `currentRoom` the browser can
            // draw the Undercity but not where anybody is on it.
            JsonObject dungeonJson = find(view, 0, "command", dungeon.getId());
            check("Secret Entrance".equals(dungeonJson.get("currentRoom").getAsString()),
                    "dungeon currentRoom: " + dungeonJson);
            System.out.println("PASS Undercity in the command zone reports currentRoom=\""
                    + dungeonJson.get("currentRoom").getAsString() + "\"");
            check(!find(view, 0, "battlefield", solRing.getId()).has("currentRoom"), "Sol Ring has a room");
            System.out.println("PASS a permanent that is not a dungeon carries no currentRoom");

            JsonArray forgeHandJson = view.getAsJsonArray("players").get(1).getAsJsonObject().getAsJsonArray("hand");
            check(forgeHandJson.size() == 0, "opponent hand enumerated: " + forgeHandJson);
            check(!view.toString().contains("Swords to Plowshares") && forgeHand.getId() > 0, "opponent hand card leaked");
            System.out.println("PASS opponent hand: count only, no objects");

            // The bench wire (StateEncoder) used by bridged seats is unchanged.
            var bench = StateEncoder.encode(game, human).toString();
            check(!bench.contains("\"currentRoom\""), "bench wire gained currentRoom");
            for (String key : NEW_KEYS) check(!bench.contains("\"" + key + "\""), "bench wire gained " + key);
            System.out.println("PASS bench StateEncoder wire carries none of " + NEW_KEYS);

            // The wire line is JsonObject#toString (InteractiveProtocol println): nulls are written.
            check(view.toString().contains("\"tokenScript\":null") && view.toString().contains("\"basePower\":null"),
                    "wire serialization drops explicit nulls");
            System.out.println("PASS explicit nulls survive JsonObject#toString");

            if (args.length > 1) {
                var pretty = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(view);
                Files.writeString(Path.of(args[1]), pretty + "\n", StandardCharsets.UTF_8);
                System.out.println("wrote " + args[1]);
            }
            System.out.println("InteractiveCardCharacteristicsSmoke: " + checks + " checks passed");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
