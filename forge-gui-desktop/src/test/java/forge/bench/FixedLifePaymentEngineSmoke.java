package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.CostPayLife;
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
import java.util.List;

/** Actual-script development fixture: no matches or strength seeds. */
public final class FixedLifePaymentEngineSmoke {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing actual card " + name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }
    private static Game game() {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Fixed life fixture").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static SpellAbility lifeOption(Card spell, Player player) {
        var original = spell.getFirstSpellAbility().copyForEnumeration(player);
        return GameActionUtil.getAlternativeCosts(original, player, false, true).stream()
            .filter(a -> a.getMayPlayOption() != null && a.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostPayLife))
            .findFirst().orElseThrow(() -> new AssertionError("Actual Citadel life option missing"));
    }
    private static void execute(String spellName, int life) {
        var game = game(); var player = game.getPlayers().get(0);
        card("Bolas's Citadel", player, ZoneType.Battlefield);
        var spell = card(spellName, player, ZoneType.Library);
        card("Forest", player, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        var ability = lifeOption(spell, player);
        check(ability.canPlay(), spellName + " actual permission is legal");
        var rng = BenchRandomAudit.begin();
        var result = RulesCostFeasibility.assess(player, ability);
        check(result.status() == RulesCostFeasibility.Status.PAYABLE && result.space().life() == life,
            spellName + " exact life feasibility without mana");
        var wire = StateEncoder.encodeSpellAbility(ability, player.getView());
        check(wire.getAsJsonObject("cost").get("life").getAsInt() == life
            && wire.getAsJsonObject("cost").get("alternative").getAsBoolean(), spellName + " structured life and alternative metadata");
        var choices = new RulesPaymentChoices(player, ability);
        check(choices.request().getAsJsonObject("cost").get("life").getAsInt() == life, spellName + " payment request retains fixed life");
        BenchRandomAudit.assertUnchanged(rng, "life choice enumeration");
        var answer = new JsonObject(); answer.addProperty("choice", 0); answer.add("sourceOrder", new JsonArray());
        var witness = choices.select(answer);
        check(witness.life() == life && witness.sources().isEmpty(), spellName + " host-selected witness has no substitute mana");
        if (life > 0) {
            boolean refused = false;
            try { new RulesPaymentExecutor(player, ability, new RulesCostFeasibility.PaymentWitness(witness.cost(), witness.sources(), witness.allocations(), 0)); }
            catch (RulesCostFeasibility.Unsupported expected) { refused = true; }
            check(refused, "wrong life amount cannot masquerade as selected witness");
        }
        final var payment = new RulesPaymentExecutor(player, ability, witness);
        player.dangerouslySetController(new forge.ai.PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost, forge.game.cost.CostPartMana part,
                    SpellAbility actual, String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (matrix != null) throw new AssertionError("Unexpected mana conversion");
                return payment.pay(cost, part, actual, effect);
            }
        });
        int before = player.getLife();
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(player, ability, null, payment::decisions), spellName + " engine executed selected cast");
        payment.assertPaid();
        check(player.getLife() == before - life && ability.getAmountLifePaid() == life && player.getManaPool().totalMana() == 0,
            spellName + " actual life receipt exact, no mana substituted");
        check(ability.getHostCard().isInZone(ZoneType.Stack), spellName + " actually entered stack");
    }
    private static void permissionFailures() {
        var game = game(); var player = game.getPlayers().get(0); var other = game.getPlayers().get(1);
        var citadel = card("Bolas's Citadel", player, ZoneType.Battlefield);
        var spell = card("Sol Ring", player, ZoneType.Library);
        card("Forest", player, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        var ability = lifeOption(spell, player);
        var wrongActor = ability.copyForEnumeration(other);
        check(RulesCostFeasibility.assess(other, wrongActor).status() == RulesCostFeasibility.Status.UNPAYABLE,
            "wrong actor cannot spend another player's permission");
        player.setLife(0, null);
        check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.UNPAYABLE,
            "insufficient life is not playable");
        player.setLife(20, null);
        player.getZone(ZoneType.Battlefield).remove(citadel); player.getZone(ZoneType.Graveyard).add(citadel);
        game.getAction().checkStateEffects(true);
        check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.UNPAYABLE,
            "expired permission cannot reuse an old life-cost option");
    }
    private static void completeControllerPath() {
        String answers = "{\"type\":\"answer\",\"id\":1,\"choice\":1}\n"
            + "{\"type\":\"answer\",\"id\":2,\"choice\":0,\"sourceOrder\":[]}\n";
        var wire = new java.io.ByteArrayOutputStream();
        var session = new BenchSession(new JsonRpcChannel(new java.io.ByteArrayInputStream(
            answers.getBytes(java.nio.charset.StandardCharsets.UTF_8)), wire));
        var lobby = new LobbyPlayerBridge("Payer", null, session, BenchSession.Mode.BRIDGE, 0);
        lobby.setAiProfile("Default");
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Fixed life controller fixture").createGame();
        var player = game.getPlayers().get(0);
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        card("Bolas's Citadel", player, ZoneType.Battlefield);
        var spell = card("Sol Ring", player, ZoneType.Library);
        card("Forest", player, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        BenchRandomAudit.install(102);
        var before = BenchMenuStateAudit.capture(game);
        ((PlayerControllerBridge) player.getController()).probePriorityMenuPurity();
        BenchMenuStateAudit.assertUnchanged(before, game);
        var selected = player.getController().chooseSpellAbilityToPlay();
        check(selected.size() == 1 && selected.get(0).getHostCard().getId() == spell.getId(), "production controller routes actual top spell, not another action");
        check(player.getController().playChosenSpellAbility(selected.get(0)), "production controller executes host life witness");
        check(player.getLife() == 19 && selected.get(0).getHostCard().isInZone(ZoneType.Stack), "production controller life and stack receipts agree");
        var rows = wire.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
            .map(line -> com.google.gson.JsonParser.parseString(line).getAsJsonObject()).toList();
        check(rows.size() == 2 && rows.get(0).get("kind").getAsString().equals("priority")
            && rows.get(1).get("kind").getAsString().equals("payment"), "exact priority then complete-payment asks, no delegated surfaces");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getAssetsDir" -> args[0] + "/forge-gui/";
                    case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                    case "getCurrentVersion" -> "fixed-life-fixture";
                    default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(101);
            execute("Sol Ring", 1);
            execute("Ornithopter", 0);
            permissionFailures();
            completeControllerPath();
            System.out.println("PASS all " + checks + " fixed-life checks; development only");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
