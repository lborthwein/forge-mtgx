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
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
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
import forge.player.HumanManaX;
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
        // These fixtures bypass entering-the-battlefield replacement effects.
        // Seed printed loyalty so state-based actions do not remove our modifier.
        if (zone == ZoneType.Battlefield && card.isPlaneswalker()) {
            card.setCounters(CounterType.get(CounterEnumType.LOYALTY),
                    Integer.parseInt(card.getCurrentState().getBaseLoyalty()));
        }
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
        var modifierCard = modifier == null ? null : card(modifier, player, ZoneType.Battlefield);
        var host = card(spellName, player, flashback ? ZoneType.Graveyard : ZoneType.Hand);
        var spell = flashback ? host.getAllPossibleAbilities(player, false).stream().filter(SpellAbility::isFlashback)
                .findFirst().orElseThrow(() -> new AssertionError("No actual flashback candidate")) : host.getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        game.getAction().checkStateEffects(true);
        if (modifierCard != null && !modifierCard.isInZone(ZoneType.Battlefield))
            throw new AssertionError("Fixture modifier did not survive state-based actions: " + modifier);
        String before = state(game);
        var assessment = HumanManaAffordability.assess(player, spell);
        boolean actual = assessment != HumanManaAffordability.Assessment.PROVEN_UNAFFORDABLE;
        if (("Nissa, Who Shakes the World".equals(modifier) || "Mana Reflection".equals(modifier))
                && assessment != HumanManaAffordability.Assessment.UNKNOWN)
            throw new AssertionError("Active mana doubler must remain unknown: " + modifier + " " + assessment);
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
    private static void checkX(String name, String[] sources, String modifier, int life, int expectedMax, boolean exact) {
        var game = game(); var player = game.getPlayers().get(0);
        player.setLife(life, null);
        for (String source : sources) card(source, player, ZoneType.Battlefield);
        card("Plains", player, ZoneType.Hand);
        if (modifier != null) card(modifier, player, ZoneType.Battlefield);
        var ability = card(name, player, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        game.getAction().checkStateEffects(true);
        String before = state(game);
        var result = HumanManaX.range(player, ability, 0, Integer.MAX_VALUE);
        if (!before.equals(state(game))) throw new AssertionError("X query mutated actual game: " + name);
        if (result.exact() != exact || (exact && result.max() != expectedMax))
            throw new AssertionError(name + " X range " + result + " expectedMax=" + expectedMax + " exact=" + exact);
        System.out.println("PASS exact X fixture " + name + " sources=" + List.of(sources) + " modifier=" + modifier + " " + result);
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
            check("Nissa, Who Shakes the World", new String[]{}, null, false);
            check("Nissa, Who Shakes the World", new String[]{"Mountain"}, null, false);
            check("Nissa, Who Shakes the World", new String[]{"Forest", "Forest", "Forest", "Forest", "Forest"}, null, true);
            check("Nissa, Ascended Animist", new String[]{}, null, false);
            check("Nissa, Ascended Animist", new String[]{"Forest"}, null, false);
            check("Nissa, Ascended Animist", new String[]{"Forest", "Forest", "Forest", "Forest", "Forest"}, null, true);
            check("Mana Reflection", new String[]{}, null, false);
            check("Mana Reflection", new String[]{"Forest"}, null, false);
            check("Grizzly Bears", new String[]{"Forest"}, "Nissa, Who Shakes the World", true);
            check("Grizzly Bears", new String[]{"Forest"}, "Mana Reflection", true);
            var five = new String[]{"Karakas", "Plains", "Plains", "Mountain", "Mana Confluence"};
            checkX("Walking Ballista", five, null, 16, 2, true);
            checkX("Walking Ballista", new String[]{}, null, 1_000_000, 0, true);
            checkX("Banefire", five, null, 16, 4, true);
            checkX("Banefire", five, "Thalia, Guardian of Thraben", 16, 3, true);
            checkX("Banefire", five, "Goblin Electromancer", 16, 5, true);
            checkX("Walking Ballista", five, "Trinisphere", 16, 2, true);
            checkX("Walking Ballista", new String[]{"Plains", "Plains"}, "Trinisphere", 16, -1, true);
            checkX("Banefire", new String[]{"Plains", "Plains", "Plains"}, null, 16, -1, true);
            checkX("Walking Ballista", new String[]{"Mana Confluence", "Mana Confluence", "Mana Confluence", "Mana Confluence", "Mana Confluence"}, null, 2, 1, true);
            checkX("Walking Ballista", new String[]{"Mystic Gate", "Island"}, null, 16, 0, false);
            checkX("Fireball", five, null, 16, 0, false);
            System.out.println("PASS " + checks + " actual-engine affordability cases; no presentation-query state changes");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
