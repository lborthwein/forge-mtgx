package forge.interactive;

import forge.GuiDesktop;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.cost.CostAdjustment;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.HumanManaAffordability;
import java.util.List;

/** Actual pinned card scripts and game objects; never runs AI decisions or a shared match. */
public final class HumanManaAffordabilityEngineSmoke {
    private static int checks;
    private static void checkCosmeticExileOrder() {
        var game = game(); var player = game.getPlayers().get(0);
        var relic = card("Relic of Progenitus", player, ZoneType.Battlefield);
        var source = relic.getSpellAbilities().stream()
                .filter(a -> a.getApi() == forge.game.ability.ApiType.ChangeZoneAll).findFirst().orElseThrow();
        var cards = new forge.game.card.CardCollection();
        cards.add(card("Plains", player, ZoneType.Graveyard));
        cards.add(card("Island", player, ZoneType.Graveyard));
        var controller = new forge.player.PlayerControllerHuman(game, player, player.getLobbyPlayer());
        final RuntimeException reachedGui = new RuntimeException("normal ordering path reached");
        final boolean[] cosmetic = {false};
        controller.setGui((forge.gui.interfaces.IGuiGame) java.lang.reflect.Proxy.newProxyInstance(
                forge.gui.interfaces.IGuiGame.class.getClassLoader(), new Class<?>[]{forge.gui.interfaces.IGuiGame.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("promptsForCosmeticExileOrder")) return cosmetic[0];
                    throw reachedGui;
                }));
        if (controller.orderMoveToZoneList(cards, ZoneType.Exile, source) != cards)
            throw new AssertionError("Cosmetic exile must preserve the exact supplied cards");
        // Library ordering, explicit effects and desktop behavior must still
        // take their normal UI path, not the cosmetic browser shortcut.
        var reorder = forge.game.ability.AbilityFactory.getAbility("DB$ ReorderZone | Zone$ Exile", relic);
        for (int test = 0; test < 3; test++) {
            cosmetic[0] = test == 2;
            try {
                controller.orderMoveToZoneList(cards, test == 0 ? ZoneType.Library : ZoneType.Exile,
                        test == 1 ? reorder : source);
                throw new AssertionError("Required ordering was bypassed");
            } catch (RuntimeException expected) { if (expected != reachedGui) throw expected; }
        }
        System.out.println("PASS Relic cosmetic exile bypass and preserved library/effect/desktop ordering");
    }
    private static Game game() {
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Fixture payer", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Fixture", 1, 0, null, "Default")));
        var rules = new GameRules(GameType.Constructed);
        var game = new Match(rules, registered, "Affordability fixture").createGame();
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
    private static String state(Game game) {
        var state = new StringBuilder();
        for (Player player : game.getPlayers()) {
            state.append(player.getLife()).append('/').append(player.getManaPool().totalMana());
            for (var mana : player.getManaPool()) state.append(':').append(System.identityHashCode(mana));
        }
        for (Card card : game.getCardsInGame()) {
            state.append('|').append(card.getId()).append(':').append(card.getZone()).append(':').append(card.isTapped());
            for (SpellAbility ability : card.getSpellAbilities()) {
                state.append(';').append(ability.getId()).append(':').append(ability.getActivatingPlayer())
                        .append(':').append(ability.getPayCosts()).append(':').append(ability.getXManaCostPaid())
                        .append(':').append(ability.getPayingMana().size());
                if (ability.getManaPart() != null) state.append(':').append(ability.getManaPart().getExpressChoice());
            }
        }
        return state.toString();
    }
    private static void check(String spellName, String[] sources, String modifier, boolean expected) {
        check(spellName, sources, modifier, expected, false);
    }
    private static void check(String spellName, String[] sources, String modifier, boolean expected, boolean flashback) {
        var game = game(); var player = game.getPlayers().get(0);
        for (String source : sources) {
            boolean tapped = source.endsWith(":tapped");
            var land = card(tapped ? source.substring(0, source.length() - 7) : source, player, ZoneType.Battlefield);
            if (tapped) land.setTapped(true);
        }
        // A hand full of basic lands must not force the old unknown-source escape.
        card("Plains", player, ZoneType.Hand);
        if (modifier != null) card(modifier, player, ZoneType.Battlefield);
        var host = card(spellName, player, flashback ? ZoneType.Graveyard : ZoneType.Hand);
        var spell = flashback ? host.getAllPossibleAbilities(player, false).stream().filter(SpellAbility::isFlashback)
                .findFirst().orElseThrow(() -> new AssertionError("No actual flashback candidate")) : host.getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        game.getAction().checkStateEffects(true);
        String before = state(game);
        boolean actual = HumanManaAffordability.mayAfford(player, spell);
        if (!before.equals(state(game))) throw new AssertionError("Query mutated game: " + spellName);
        if (actual != expected) throw new AssertionError(spellName + " via " + List.of(sources)
                + " modifier=" + modifier + " expected=" + expected + " actual=" + actual);
        // Differential pricing on a disposable ability/cost copy. This reference call
        // may choose reduction order; it is NEVER the production presentation query.
        var price = CostAdjustment.presentationManaCost(spell);
        if (price != null && spell.getPayCosts().getCostMana().getMana().countX() == 0) {
            var copy = spell.copy(spell.getHostCard(), player, true);
            var raised = CostAdjustment.adjust(copy.getPayCosts(), copy, false);
            var paid = new ManaCostBeingPaid(raised.getCostMana().getMana());
            CostAdjustment.adjust(paid, copy, player, null, true, false);
            if (!price.toString().equals(paid.toManaCost().toString()))
                throw new AssertionError("Pricing differs from Forge payment: " + price + " vs " + paid);
        }
        System.out.println("PASS " + spellName + " " + List.of(sources) + " modifier=" + modifier + " mayAfford=" + actual);
        checks++;
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface(new GuiDesktop() { @Override public String getAssetsDir() { return args[0] + "/forge-gui/"; } });
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            checkCosmeticExileOrder();
            check("Lightning Bolt", new String[]{}, null, false);
            check("Lightning Bolt", new String[]{"Plains"}, null, false);
            check("Lightning Bolt", new String[]{"Mountain"}, null, true);
            check("Get Lost", new String[]{"Plains:tapped", "Island:tapped", "Island:tapped"}, null, false);
            check("Elspeth, Sun's Champion", new String[]{"Plains:tapped", "Island:tapped", "Island:tapped"}, null, false);
            check("Lingering Souls", new String[]{"Plains:tapped", "Island:tapped", "Island:tapped"}, null, false, true);
            check("Lingering Souls", new String[]{"Plains", "Swamp"}, null, true, true);
            check("Lightning Bolt", new String[]{"Mountain"}, "Thalia, Guardian of Thraben", false);
            check("Lightning Bolt", new String[]{"Mountain", "Plains"}, "Thalia, Guardian of Thraben", true);
            check("Lightning Strike", new String[]{"Mountain"}, "Goblin Electromancer", true);
            check("Lightning Strike", new String[]{"Plains"}, "Goblin Electromancer", false);
            check("Lightning Bolt", new String[]{"Mountain", "Plains"}, "Trinisphere", false);
            check("Lightning Bolt", new String[]{"Mountain", "Plains", "Plains"}, "Trinisphere", true);
            check("Lightning Bolt", new String[]{"Mana Confluence"}, null, true);
            check("Lightning Bolt", new String[]{"City of Brass"}, null, true);
            check("Counterspell", new String[]{"Mystic Gate", "Island"}, null, true);
            check("Lightning Bolt", new String[]{"Mystic Gate", "Island"}, null, false);
            check("Lightning Bolt", new String[]{"Flooded Strand"}, null, false);
            check("Grizzly Bears", new String[]{"Ancient Tomb"}, null, false);
            check("Grizzly Bears", new String[]{"Ancient Tomb", "Forest"}, null, true);
            check("Gut Shot", new String[]{}, null, true);
            check("Dismember", new String[]{}, null, false);
            check("Dismember", new String[]{"Plains"}, null, true);
            check("Banefire", new String[]{}, null, false);
            check("Banefire", new String[]{"Mountain"}, null, true);
            check("Fireball", new String[]{}, null, true); // target-dependent tax is unknown
            check("Stoke the Flames", new String[]{}, null, true); // unsupported convoke remains visible
            System.out.println("PASS " + checks + " actual-engine affordability cases; no presentation-query state changes");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
