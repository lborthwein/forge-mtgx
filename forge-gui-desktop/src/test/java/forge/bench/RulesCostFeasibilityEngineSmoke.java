package forge.bench;

import forge.StaticData;
import forge.ai.AiCardMemory;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;
import java.util.List;
import java.util.Random;
import static forge.bench.RulesCostFeasibility.Status.*;

/** Actual pinned card scripts, no matches or AI decisions. */
public final class RulesCostFeasibilityEngineSmoke {
    private static int checks;
    private static Game game() {
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Payer", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), registered, "Rules cost fixture").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing pinned card " + name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        card.setSickness(false);
        return card;
    }
    private static String state(Game game) {
        var out = new StringBuilder();
        for (var player : game.getPlayers()) {
            out.append(player.getLife()).append('/').append(player.getManaPool().totalMana());
            for (var set : AiCardMemory.MemorySet.values()) out.append(set).append(AiCardMemory.getMemorySet(player, set));
        }
        for (var card : game.getCardsInGame()) {
            out.append('|').append(card.getId()).append(':').append(card.getZone()).append(':').append(card.isTapped()).append(':').append(card.getCounters());
            for (var sa : card.getSpellAbilities()) out.append(';').append(sa.getId()).append(':').append(sa.getActivatingPlayer())
                    .append(':').append(sa.getPayCosts()).append(':').append(sa.getXManaCostPaid()).append(':').append(sa.getTargets())
                    .append(':').append(sa.getPipsToReduce()).append(':').append(sa.getPayingMana());
        }
        return out.toString();
    }
    private static void verify(Player player, SpellAbility sa, RulesCostFeasibility.Status expected, String label) {
        sa.setActivatingPlayer(player);
        String before = state(player.getGame());
        Random previous = MyRandom.getRandom();
        MyRandom.setRandom(new Random(1) { @Override protected int next(int bits) { throw new AssertionError("Feasibility consumed game RNG"); } });
        try {
            for (int i = 0; i < 3; i++) {
                var actual = RulesCostFeasibility.assess(player, sa);
                if (actual.status() != expected) throw new AssertionError(label + ": " + actual + " expected " + expected);
                if (!before.equals(state(player.getGame()))) throw new AssertionError(label + " mutated state/memory");
            }
        } finally { MyRandom.setRandom(previous); }
        checks++;
        System.out.println("PASS " + label + " " + expected);
    }
    private static void spell(String name, String[] lands, String modifier, RulesCostFeasibility.Status expected) {
        var game = game(); var player = game.getPlayers().get(0);
        for (String land : lands) {
            var source = card(land, player, ZoneType.Battlefield);
            for (var set : AiCardMemory.MemorySet.values()) AiCardMemory.rememberCard(player, source, set);
        }
        if (modifier != null) card(modifier, player, ZoneType.Battlefield);
        var host = card(name, player, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        verify(player, host.getFirstSpellAbility(), expected, name + " " + List.of(lands) + " " + modifier);
    }
    public static void main(String[] args) {
        try {
            // No screen/window needed to load card scripts. Unexpected GUI calls fail
            // the fixture; none may choose a gameplay action or synthesize an answer.
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> {
                        return switch (method.getName()) {
                            case "getAssetsDir" -> args[0] + "/forge-gui/";
                            case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                            case "getCurrentVersion" -> "rules-fixture";
                            default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                        };
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            spell("Lightning Bolt", new String[]{}, null, UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Plains"}, null, UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain"}, null, PAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain"}, "Nether Void", PAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain"}, "Sphere of Resistance", UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain", "Plains"}, "Sphere of Resistance", PAYABLE);
            spell("Lightning Bolt", new String[]{"Black Lotus"}, null, PAYABLE);
            spell("Esper Charm", new String[]{"Black Lotus"}, null, UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Mana Confluence"}, null, UNSUPPORTED);
            spell("Lightning Bolt", new String[]{"Mountain"}, "Mana Reflection", UNSUPPORTED);
            spell("Dismember", new String[]{"Swamp"}, null, UNSUPPORTED);
            var game = game(); var player = game.getPlayers().get(0);
            var liliana = card("Liliana of the Veil", player, ZoneType.Battlefield);
            liliana.setCounters(CounterEnumType.LOYALTY, 2);
            var minus = liliana.getSpellAbilities().stream().filter(a -> a.getPayCosts().toString().startsWith("-2")).findFirst().orElseThrow();
            verify(player, minus, PAYABLE, "Liliana may spend last two loyalty");
            liliana.setCounters(CounterEnumType.LOYALTY, 1);
            verify(player, minus, UNPAYABLE, "Liliana may not overspend loyalty");
            var bolt = card("Lightning Bolt", player, ZoneType.Hand).getFirstSpellAbility();
            card("Mountain", player, ZoneType.Battlefield);
            var ward = card("Phyrexian Fleshgorger", game.getPlayers().get(1), ZoneType.Battlefield);
            bolt.getTargets().add(ward);
            verify(player, bolt, PAYABLE, "Targeting Ward does not add a casting cost");
            var battlemage = card("Thornscape Battlemage", player, ZoneType.Hand).getFirstSpellAbility();
            battlemage.setActivatingPlayer(player);
            var options = GameActionUtil.getOptionalCostValues(battlemage.copy());
            var variants = BenchmarkOptionalCosts.variants(battlemage, options, player);
            if (options.size() != 2 || variants.size() != 3
                    || variants.stream().map(a -> a.getPayCosts().getTotalMana().toString()).distinct().count() != 3)
                throw new AssertionError("Both singleton kicker subsets and combined kicker must survive: options=" + options
                        + " variants=" + variants.stream().map(a -> a.getPayCosts().getTotalMana().toString()).toList());
            checks++;
            System.out.println("PASS actual dual-kicker subsets " + variants.stream().map(a -> a.getPayCosts().getTotalMana().toString()).toList());
            try {
                var unsupported = card("Dismember", player, ZoneType.Hand).getFirstSpellAbility();
                unsupported.setActivatingPlayer(player);
                RulesCostFeasibility.requirePayable(player, unsupported);
                throw new AssertionError("Unknown was accepted");
            } catch (RulesCostFeasibility.Unsupported expected) { checks++; }
            System.out.println("PASS all " + checks + " rules-feasibility checks");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
