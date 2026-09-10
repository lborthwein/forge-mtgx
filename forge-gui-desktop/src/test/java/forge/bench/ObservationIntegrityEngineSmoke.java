package forge.bench;

import forge.StaticData;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.LandAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.List;

/** Real card/view visibility, no matches, windows, or AI choices. */
public final class ObservationIntegrityEngineSmoke {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS " + label); checks++;
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing pinned card " + name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getAssetsDir" -> args[0] + "/forge-gui/";
                    case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                    case "getCurrentVersion" -> "observation-fixture";
                    default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
            var game = new Match(new GameRules(GameType.Constructed), players, "Observation fixture").createGame();
            var viewer = game.getPlayers().get(0); var owner = game.getPlayers().get(1);
            var stolen = card("Swamp", owner, ZoneType.Exile);
            stolen.turnFaceDown();
            check(StateEncoder.encodeCard(stolen, viewer.getView()) == null, "unknown face-down exile is not exposed");
            check(StateEncoder.encodeCard(stolen, owner.getView()) == null, "ownership alone does not reveal face-down exile");
            // Same engine permission written by exile WithMayLook effects.
            stolen.addMayLookFaceDownExile(viewer);
            final var state = stolen.getCurrentStateName();
            var encoded = StateEncoder.encodeCard(stolen, viewer.getView());
            check(encoded.getAsJsonObject("knownFace").get("name").getAsString().equals("Swamp"), "authorized viewer sees original face");
            check(encoded.get("name").getAsString().isEmpty() && encoded.get("faceDown").getAsBoolean(), "face knowledge does not turn object face up");
            check(stolen.getCurrentStateName() == state, "encoding leaves actual card state untouched");
            check(StateEncoder.encodeCard(stolen, owner.getView()) == null, "face knowledge does not leak to owner/opponent");
            check(!StateEncoder.encodeCardUnchecked(stolen).has("knownFace"), "unchecked serializer does not grant face knowledge");
            var land = new LandAbility(stolen, stolen.getState(CardStateName.Original)); land.setActivatingPlayer(viewer);
            var option = StateEncoder.encodeSpellAbility(land);
            check(option.get("isLandAbility").getAsBoolean(), "land kind is structural, not display text");
            check(option.get("source").getAsString().equals("Swamp"), "permission-bearing land retains authorized source identity");
            land.setActivatingPlayer(owner);
            check(StateEncoder.encodeSpellAbility(land).get("source").getAsString().isEmpty(), "ability cannot grant unauthorized face knowledge");
            var morph = card("Grave Titan", owner, ZoneType.Battlefield); morph.turnFaceDown();
            var publicMorph = StateEncoder.encodeCard(morph, viewer.getView());
            check(publicMorph != null && !publicMorph.has("knownFace"), "opponent morph object visible but original face hidden");
            var ownMorph = StateEncoder.encodeCard(morph, owner.getView());
            check(ownMorph.getAsJsonObject("knownFace").get("name").getAsString().equals("Grave Titan"), "controller can inspect own face-down creature");
            check(ownMorph.get("power").getAsInt() == 2 && ownMorph.get("toughness").getAsInt() == 2, "known Grave Titan still has face-down 2/2 characteristics");
            var hiddenHand = card("Lightning Bolt", owner, ZoneType.Hand);
            check(StateEncoder.encodeCard(hiddenHand, viewer.getView()) == null, "ordinary opponent hand remains hidden");
            System.out.println("PASS all " + checks + " observation checks");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
