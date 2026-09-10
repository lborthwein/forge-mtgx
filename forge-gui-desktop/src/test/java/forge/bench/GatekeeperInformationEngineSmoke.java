package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;
import forge.player.GamePlayerUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Development information-set witness, not a played game or strength estimate.
 * Uses the real card script and stock Default controller's actual zone-choice
 * callback. Hidden identities below are test evidence, NEVER policy inputs.
 */
public final class GatekeeperInformationEngineSmoke {
    private record Result(JsonObject observation, String choice, Map<String, Integer> deck) {}
    private static final String LAND = "Mountain", CREATURE = "Savannah Lions";
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    private static forge.item.PaperCard paper(String name) {
        StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name);
    }
    private static Card card(String name, Player owner, ZoneType zone) {
        Card card = Card.fromPaperCard(paper(name), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(zone).add(card);
        return card;
    }
    private static String digest(JsonObject observation) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(observation.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static Result choose(String label, List<String> hidden, String hiddenExile, long seed) throws Exception {
        // hidden[0] is hand, [1..] is library. Registered cards exactly equal
        // all physical cards in this fixture, including both public candidates.
        var ownDeck = new Deck(); ownDeck.getMain().add(paper("Cemetery Gatekeeper"), 1);
        var deckCounts = new TreeMap<String, Integer>();
        for (String name : List.of(LAND, CREATURE)) deckCounts.merge(name, 1, Integer::sum);
        for (String name : hidden) deckCounts.merge(name, 1, Integer::sum);
        if (hiddenExile != null) deckCounts.merge(hiddenExile, 1, Integer::sum);
        var oppDeck = new Deck();
        for (var entry : deckCounts.entrySet()) oppDeck.getMain().add(paper(entry.getKey()), entry.getValue());
        MyRandom.setRandom(new Random(seed));
        var players = List.of(
                new RegisteredPlayer(ownDeck).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
                new RegisteredPlayer(oppDeck).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Gatekeeper information fixture").createGame();
        game.setAge(GameStage.Play);
        var seat = game.getPlayers().get(0); var opponent = game.getPlayers().get(1);
        check(seat.getController().getClass() == PlayerControllerAi.class, "Not unmodified stock AI controller");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
        var host = card("Cemetery Gatekeeper", seat, ZoneType.Battlefield);
        var options = new CardCollection();
        options.add(card(LAND, opponent, ZoneType.Graveyard));
        options.add(card(CREATURE, opponent, ZoneType.Graveyard));
        var hiddenCards = new CardCollection();
        for (int i = 0; i < hidden.size(); i++)
            hiddenCards.add(card(hidden.get(i), opponent, i == 0 ? ZoneType.Hand : ZoneType.Library));
        if (hiddenExile != null) {
            var exiled = card(hiddenExile, opponent, ZoneType.Exile);
            exiled.turnFaceDown(true);
            hiddenCards.add(exiled);
        }
        game.getAction().checkStateEffects(true);
        for (var hiddenCard : hiddenCards) {
            check(!hiddenCard.getView().canBeShownTo(seat.getView()), "Fixture accidentally reveals hidden identity");
            check(StateEncoder.encodeCard(hiddenCard, seat.getView()) == null, "Encoder leaked hidden identity");
        }
        // The script's Hidden=True selects this production callback even though
        // its selectable graveyard cards themselves are public, not hidden.
        var ability = AbilityFactory.getAbility(host.getSVar("TrigExile"), host);
        ability.setActivatingPlayer(seat);
        check("ExilePreference:MostProminentOppType".equals(ability.getParam("AILogic")), "Actual card script changed");
        check("True".equals(ability.getParam("Hidden")), "Actual zone-change route changed");
        var observation = new JsonObject();
        observation.add("state", StateEncoder.encode(game, seat));
        // Independently retain public zone sizes and opaque exile object IDs.
        // The current bridge encoder omits entirely hidden exile cards; the
        // witness must not rely on that omission to make two states look equal.
        var physicalPublic = new JsonObject();
        physicalPublic.addProperty("opponentHandSize", opponent.getCardsIn(ZoneType.Hand).size());
        physicalPublic.addProperty("opponentLibrarySize", opponent.getCardsIn(ZoneType.Library).size());
        var opaqueExile = new JsonArray();
        for (var exiled : opponent.getCardsIn(ZoneType.Exile)) {
            check(exiled.isFaceDown(), "Expected opaque face-down exile object");
            var object = new JsonObject(); object.addProperty("fid", exiled.getId());
            object.addProperty("faceDown", true); object.addProperty("visibleToDecider", false);
            opaqueExile.add(object);
        }
        physicalPublic.add("opponentOpaqueExile", opaqueExile);
        observation.add("independentPublicObjects", physicalPublic);
        observation.add("ability", StateEncoder.encodeSpellAbility(ability, seat.getView()));
        var visibleOptions = new JsonArray();
        for (var option : options) {
            check(option.getView().canBeShownTo(seat.getView()), "Illegal hidden candidate");
            visibleOptions.add(StateEncoder.encodeCard(option, seat.getView()));
        }
        observation.add("options", visibleOptions);
        // Reset only before the actual isolated decision, never in production.
        MyRandom.setRandom(new Random(seed));
        var chosen = seat.getController().chooseSingleCardForZoneChange(ZoneType.Exile,
                List.of(ZoneType.Graveyard), ability, options, null,
                ability.getParam("SelectPrompt"), false, seat);
        check(options.contains(chosen), "Stock controller chose an unavailable card");
        check(observation.get("state").equals(StateEncoder.encode(game, seat)), "Choice mutated visible state");
        var evidence = new JsonObject();
        evidence.addProperty("case", label); evidence.addProperty("fixtureSeed", seed);
        evidence.addProperty("visibleSha256", digest(observation));
        evidence.add("seatVisibleDecision", observation);
        evidence.addProperty("choice", chosen.getName());
        evidence.addProperty("privateWitnessOnly_handThenLibrary", hidden.toString());
        evidence.addProperty("privateWitnessOnly_faceDownExile", hiddenExile);
        evidence.addProperty("registeredOpponentDeck", deckCounts.toString());
        System.out.println("WITNESS " + evidence);
        return new Result(observation, chosen.getName(), deckCounts);
    }
    private static void pair(Result left, Result right, boolean sameChoice, boolean sameDeck, String label) {
        check(left.observation().equals(right.observation()), label + ": observations differ");
        check(left.choice().equals(right.choice()) == sameChoice, label + ": unexpected choice relation");
        check(left.deck().equals(right.deck()) == sameDeck, label + ": unexpected deck relation");
        System.out.println("PASS " + label + " sameObservation=true sameChoice=" + sameChoice + " sameRegisteredDeck=" + sameDeck);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "gatekeeper-information-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            System.out.println("STOCK_AI_CLASS_ORIGIN " + PlayerControllerAi.class.getProtectionDomain().getCodeSource().getLocation());
            System.out.println("STOCK_CHOICE_CLASS_ORIGIN " + forge.ai.ability.ChangeZoneAi.class.getProtectionDomain().getCodeSource().getLocation());
            for (long seed : new long[]{91601, 91602, 91603}) {
                var land = choose("closed-list land majority", List.of(LAND, LAND, CREATURE), null, seed);
                var creature = choose("closed-list creature majority", List.of(CREATURE, CREATURE, LAND), null, seed);
                check(land.choice().equals(LAND) && creature.choice().equals(CREATURE), "Unexpected majority preference");
                pair(land, creature, false, false, "closed-list hidden identities affect actual Default decision");
                var repartition = choose("same multiset repartition", List.of(CREATURE, LAND, LAND), null, seed);
                pair(land, repartition, true, true, "control: same aggregate type counts, different hand/library placement");
                var exileCreature = choose("open-list hidden creature exiled", List.of(LAND, LAND, CREATURE), CREATURE, seed);
                var exileLand = choose("open-list hidden land exiled", List.of(LAND, CREATURE, CREATURE), LAND, seed);
                check(exileCreature.choice().equals(LAND) && exileLand.choice().equals(CREATURE), "Unexpected fixed-deck majority preference");
                pair(exileCreature, exileLand, false, true, "same registered deck and observation: hidden library/exile swap affects decision");
            }
            System.out.println("PASS Gatekeeper information-set development witnesses; no games, no historical effect size, no policy change");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
