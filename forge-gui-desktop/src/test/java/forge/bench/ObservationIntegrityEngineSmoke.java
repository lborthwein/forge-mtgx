package forge.bench;

import forge.StaticData;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.phase.PhaseType;
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
    private static Game topGame() {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Visible top fixture").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static com.google.gson.JsonElement top(Game game, Player viewer, int owner) {
        return StateEncoder.encode(game, viewer).getAsJsonArray("players").get(owner)
            .getAsJsonObject().get("revealedTop");
    }
    private static void visibleTop(String permanentName, boolean publicTop) {
        var game = topGame();
        var owner = game.getPlayers().get(0); var opponent = game.getPlayers().get(1);
        var first = card("Forest", owner, ZoneType.Library);
        var deeper = card("Mind Twist", owner, ZoneType.Library);
        card("Swamp", opponent, ZoneType.Library);
        check(top(game, owner, 0).isJsonNull(), permanentName + ": ordinary own library remains hidden");
        var permanent = card(permanentName, owner, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        check(first.getView().canBeShownTo(owner.getView()), permanentName + ": real static ability authorizes controller");
        check(first.getView().canBeShownTo(opponent.getView()) == publicTop, permanentName + ": real permission distinguishes public/private");
        check(top(game, owner, 0).getAsJsonObject().get("name").getAsString().equals("Forest"), permanentName + ": authorized current top encoded");
        check(publicTop ? top(game, opponent, 0).getAsJsonObject().get("name").getAsString().equals("Forest")
            : top(game, opponent, 0).isJsonNull(), permanentName + ": opponent observation follows permission");
        check(top(game, owner, 1).isJsonNull(), permanentName + ": unrelated opponent library not revealed");
        var before = StateEncoder.encode(game, owner);
        var beforeOpponent = StateEncoder.encode(game, opponent);
        owner.getZone(ZoneType.Library).remove(deeper);
        var replacement = card("Grief", owner, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        check(before.equals(StateEncoder.encode(game, owner)) && beforeOpponent.equals(StateEncoder.encode(game, opponent)),
            permanentName + ": changing deeper hidden identity cannot change either observation");
        owner.getZone(ZoneType.Library).remove(first);
        game.getAction().checkStateEffects(true);
        check(top(game, owner, 0).getAsJsonObject().get("name").getAsString().equals("Grief"), permanentName + ": top changes, not stale remembered card");
        owner.getZone(ZoneType.Battlefield).remove(permanent);
        owner.getZone(ZoneType.Graveyard).add(permanent);
        game.getAction().checkStateEffects(true);
        check(top(game, owner, 0).isJsonNull() && top(game, opponent, 0).isJsonNull(), permanentName + ": permission loss clears current top");
        owner.getZone(ZoneType.Library).remove(replacement);
        check(top(game, owner, 0).isJsonNull(), permanentName + ": empty library has no top");
    }
    private static void playVisibleLand(String permanentName) {
        var game = topGame(); var player = game.getPlayers().get(0);
        var land = card("Forest", player, ZoneType.Library);
        card("Mountain", player, ZoneType.Library);
        card(permanentName, player, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        var ability = land.getAllPossibleAbilities(player, true).stream()
            .filter(a -> a.isLandAbility() && a.canPlay()).findFirst().orElseThrow();
        check(StateEncoder.encodeSpellAbility(ability, player.getView()).get("isLandAbility").getAsBoolean(),
            permanentName + ": real entitled top action is structurally a land play");
        ability.resolve();
        check(player.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getId() == land.getId()),
            permanentName + ": real entitled top land executes onto battlefield");
    }
    private static void privateTopNoninterference() {
        var game = topGame(); var owner = game.getPlayers().get(0); var opponent = game.getPlayers().get(1);
        var first = card("Forest", owner, ZoneType.Library);
        card("Grief", owner, ZoneType.Library);
        card("Bolas's Citadel", owner, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        var before = StateEncoder.encode(game, opponent);
        owner.getZone(ZoneType.Library).remove(first);
        card("Mountain", owner, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        check(top(game, owner, 0).getAsJsonObject().get("name").getAsString().equals("Grief"), "Citadel controller sees changed private top");
        check(before.equals(StateEncoder.encode(game, opponent)), "changing private top identity with same library size cannot affect opponent observation");
    }
    private static void manaObservationPurity() throws Exception {
        var game = topGame(); var player = game.getPlayers().get(0);
        var pool = card("Reflecting Pool", player, ZoneType.Battlefield);
        var otherPool = card("Reflecting Pool", player, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        check(StateEncoder.encodeProducedMana(pool).isEmpty(), "two reflecting pools terminate without inventing mana");
        var forest = card("Forest", player, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        var originals = new java.util.ArrayList<forge.game.spellability.SpellAbility>();
        for (var source : List.of(pool, otherPool, forest)) originals.addAll(source.getSpellAbilities());
        for (var original : originals) {
            original.setActivatingPlayer(null);
            original.getPipsToReduce().add("G");
        }
        var maxId = forge.game.spellability.SpellAbility.class.getDeclaredField("maxId");
        maxId.setAccessible(true);
        int beforeId = maxId.getInt(null);
        var actors = originals.stream().map(a -> a.getActivatingPlayer()).toList();
        var pips = originals.stream().map(a -> List.copyOf(a.getPipsToReduce())).toList();
        BenchRandomAudit.install(101);
        var rng = BenchRandomAudit.begin();
        check(StateEncoder.encodeProducedMana(forest).toString().equals("[\"G\"]"), "ordinary unset-actor Forest encodes green via private ability");
        check(StateEncoder.encodeProducedMana(pool).toString().equals("[\"G\"]"), "reflecting-pool cycle sees real Forest output through private abilities");
        StateEncoder.encode(game, player);
        BenchRandomAudit.assertUnchanged(rng, "full state including reflective mana");
        check(beforeId == maxId.getInt(null), "mana observations allocate no global ability identities");
        check(actors.equals(originals.stream().map(a -> a.getActivatingPlayer()).toList()), "mana observation preserves every original actor");
        check(pips.equals(originals.stream().map(a -> List.copyOf(a.getPipsToReduce())).toList()), "mana observation preserves existing original pip reductions");
        // Exercise the boundary without allowing ensureAbility to initialize a
        // live trigger or consume a new ability ID as an observational side effect.
        var lotus = card("Lotus Field", player, ZoneType.Battlefield);
        var trigger = lotus.getTriggers().stream().filter(t -> t.hasParam("Execute")).findFirst().orElseThrow();
        var cached = trigger.getOverridingAbility();
        check(cached != null, "real Lotus Field trigger is already initialized for positive traversal fixture");
        var cachedActor = cached.getActivatingPlayer();
        int beforeInitializedId = maxId.getInt(null);
        var initializedRng = BenchRandomAudit.begin();
        var observed = StateEncoder.encodeProducedMana(pool);
        check(observed.toString().equals("[\"W\",\"U\",\"B\",\"R\",\"G\"]"), "reflection includes actual any-color source with initialized trigger");
        check(trigger.getOverridingAbility() == cached && cached.getActivatingPlayer() == cachedActor
            && beforeInitializedId == maxId.getInt(null), "positive reflective trigger traversal preserves cache, actor and global IDs");
        BenchRandomAudit.assertUnchanged(initializedRng, "initialized reflective trigger");
        // Run the stock rules query only AFTER the purity assertions: it is
        // intentionally mutating, and is used here solely as a differential oracle.
        var stockColors = new com.google.gson.JsonArray();
        for (String color : forge.card.MagicColor.Constant.COLORS_AND_COLORLESS) {
            if (pool.canProduceColorMana(java.util.Set.of(color))) stockColors.add(forge.card.MagicColor.toShortString(color));
        }
        check(observed.equals(stockColors), "private traversal agrees with stock rules color set on real pool/Forest/Lotus Field fixture");
        var triggerAbility = forge.game.TriggerReplacementBase.class.getDeclaredField("overridingAbility");
        triggerAbility.setAccessible(true);
        triggerAbility.set(trigger, null); // fault injection: emulate an uninitialized trigger cache
        int beforeUnsupportedId = maxId.getInt(null);
        var unsupportedRng = BenchRandomAudit.begin();
        boolean rejected = false;
        try { StateEncoder.encodeProducedMana(pool); }
        catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected, "uninitialized reflected trigger is explicit unsupported, never a printed-output fallback");
        check(trigger.getOverridingAbility() == null && beforeUnsupportedId == maxId.getInt(null), "unsupported reflection leaves trigger cache and global IDs untouched");
        BenchRandomAudit.assertUnchanged(unsupportedRng, "unsupported reflected trigger");
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
            var option = StateEncoder.encodeSpellAbility(land, viewer.getView());
            check(option.get("isLandAbility").getAsBoolean(), "land kind is structural, not display text");
            check(option.get("source").getAsString().equals("Swamp"), "permission-bearing land retains authorized source identity");
            check(StateEncoder.encodeSpellAbility(land, owner.getView()).get("source").getAsString().isEmpty(), "authorized actor does not disclose face to unauthorized recipient");
            check(StateEncoder.encodeSpellAbility(land).get("source").getAsString().isEmpty(), "missing recipient cannot grant face knowledge");
            land.setActivatingPlayer(owner);
            check(StateEncoder.encodeSpellAbility(land, viewer.getView()).get("source").getAsString().equals("Swamp"), "recipient authorization does not depend on actor");
            var morph = card("Grave Titan", owner, ZoneType.Battlefield); morph.turnFaceDown();
            var publicMorph = StateEncoder.encodeCard(morph, viewer.getView());
            check(publicMorph != null && !publicMorph.has("knownFace"), "opponent morph object visible but original face hidden");
            var ownMorph = StateEncoder.encodeCard(morph, owner.getView());
            check(ownMorph.getAsJsonObject("knownFace").get("name").getAsString().equals("Grave Titan"), "controller can inspect own face-down creature");
            check(ownMorph.get("power").getAsInt() == 2 && ownMorph.get("toughness").getAsInt() == 2, "known Grave Titan still has face-down 2/2 characteristics");
            var hiddenHand = card("Lightning Bolt", owner, ZoneType.Hand);
            check(StateEncoder.encodeCard(hiddenHand, viewer.getView()) == null, "ordinary opponent hand remains hidden");
            var hiddenLibrary = card("Forest", owner, ZoneType.Library);
            var beforeHiddenChange = StateEncoder.encode(game, viewer);
            owner.getZone(ZoneType.Hand).remove(hiddenHand);
            card("Grief", owner, ZoneType.Hand);
            owner.getZone(ZoneType.Library).remove(hiddenLibrary);
            card("Mind Twist", owner, ZoneType.Library);
            check(beforeHiddenChange.equals(StateEncoder.encode(game, viewer)), "changing only hidden opponent identities leaves complete seat observation unchanged");
            visibleTop("Courser of Kruphix", true);
            visibleTop("Oracle of Mul Daya", true);
            visibleTop("Bolas's Citadel", false);
            playVisibleLand("Courser of Kruphix");
            playVisibleLand("Oracle of Mul Daya");
            playVisibleLand("Bolas's Citadel");
            privateTopNoninterference();
            manaObservationPurity();
            System.out.println("PASS all " + checks + " observation checks");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
