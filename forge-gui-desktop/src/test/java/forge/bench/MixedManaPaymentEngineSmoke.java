package forge.bench;

import com.google.gson.JsonObject;
import forge.StaticData;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.mana.Mana;
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
import java.util.ArrayList;
import java.util.List;

/** Real card scripts and engine execution, not whole games or held-out evidence.
 * In particular a multi-output source is not an exclusive choice of colors.
 */
public final class MixedManaPaymentEngineSmoke {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing actual card " + name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        // A post-ETB fixture snapshot: no fake removal of printed rules/abilities.
        player.getZone(zone).add(card); card.setSickness(false);
        return card;
    }
    private static Game game() {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Mixed mana fixture").createGame();
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static void execute(String land, String spell, int plan, int expectedPlans, List<String> colors) {
        var game = game(); var player = game.getPlayers().get(0);
        var source = card(land, player, ZoneType.Battlefield);
        var ability = card(spell, player, ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(player);
        if (ability.usesTargeting()) ability.getTargets().add(game.getPlayers().get(1));
        game.getAction().checkStateEffects(true);
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        var choices = new RulesPaymentChoices(player, ability); var request = choices.request();
        check(request.getAsJsonArray("menu").size() == expectedPlans, land + " / " + spell + " complete plan count");
        var options = request.getAsJsonArray("sourceOptions");
        check(options.size() == 1, land + " is one activation, not exclusive color alternatives");
        var output = options.get(0).getAsJsonObject().getAsJsonArray("output");
        check(output.asList().stream().map(e -> e.getAsString()).toList().equals(colors), land + " exact ordered mixed output");
        var selected = request.getAsJsonArray("menu").get(plan).getAsJsonObject();
        var answer = new JsonObject(); answer.addProperty("choice", plan);
        answer.add("sourceOrder", selected.getAsJsonArray("sources").deepCopy());
        var witness = choices.select(answer);
        check(witness.sources().size() == 1, "host selected one complete mixed-output activation");
        BenchRandomAudit.assertUnchanged(rng, "mixed mana enumeration");
        BenchMenuStateAudit.assertUnchanged(before, game);
        var payment = new RulesPaymentExecutor(player, ability, witness);
        player.dangerouslySetController(new forge.ai.PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost, forge.game.cost.CostPartMana part,
                    SpellAbility actual, String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (matrix != null) throw new AssertionError("Unexpected mana conversion");
                return payment.pay(cost, part, actual, effect);
            }
        });
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(player, ability, null, payment::decisions), land + " actual cast succeeds");
        payment.assertPaid();
        var emitted = List.copyOf(witness.sources().get(0).ability().getManaPart().getLastManaProduced());
        var paid = ability.getPayingMana();
        check(paid.size() == witness.allocations().size(), "exact number of allocated tokens consumed");
        for (int i = 0; i < witness.allocations().size(); i++) {
            var allocation = witness.allocations().get(i);
            check(paid.get(i) == emitted.get(allocation.token().outputIndex()), "consumed exact source output identity " + i);
            check(allocation.shard().canBePaidWithManaOfColor(paid.get(i).getColor()), "consumed color satisfies selected shard " + i);
        }
        var remaining = new ArrayList<Mana>(); player.getManaPool().forEach(remaining::add);
        for (var token : emitted) {
            boolean spent = paid.stream().anyMatch(p -> p == token);
            check(remaining.stream().anyMatch(p -> p == token) != spent, "unspent mixed output retained exactly: " + MagicColor.toShortString(token.getColor()));
        }
        check(source.isTapped() && ability.getHostCard().isInZone(ZoneType.Stack), "actual tapped source and spell on stack");
        check(player.getLife() == 20, "ordinary mixed output does not spend life");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "getAssetsDir" -> args[0] + "/forge-gui/";
                    case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                    case "getCurrentVersion" -> "mixed-mana-fixture";
                    default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(501);
            execute("Gruul Turf", "Burning-Tree Emissary", 0, 1, List.of("R", "G"));
            execute("Boros Garrison", "Lightning Helix", 0, 1, List.of("R", "W"));
            execute("Azorius Chancery", "Sol Ring", 0, 2, List.of("W", "U"));
            execute("Azorius Chancery", "Sol Ring", 1, 2, List.of("W", "U"));
            System.out.println("PASS all " + checks + " mixed-output execution checks; development only"); System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
