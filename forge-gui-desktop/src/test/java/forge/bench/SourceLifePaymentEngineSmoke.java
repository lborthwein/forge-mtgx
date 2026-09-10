package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
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

/** Actual scripts, exact costs and receipts; development fixtures, no strength games. */
public final class SourceLifePaymentEngineSmoke {
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
        player.getZone(zone).add(card); card.setSickness(false);
        return card;
    }
    private static Game game() {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Source life fixture").createGame();
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static SpellAbility spell(Player player, String name) {
        return card(name, player, ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(player);
    }
    private static RulesCostFeasibility.PaymentWitness select(RulesPaymentChoices choices) {
        var request = choices.request(); var first = request.getAsJsonArray("menu").get(0).getAsJsonObject();
        var answer = new JsonObject(); answer.addProperty("choice", 0);
        answer.add("sourceOrder", first.getAsJsonArray("sources").deepCopy());
        return choices.select(answer);
    }
    private static void execute(String name, int sourceLife) {
        execute(name, sourceLife, 20);
    }
    private static void execute(String name, int sourceLife, int initialLife) {
        var game = game(); var player = game.getPlayers().get(0);
        var source = card(name, player, ZoneType.Battlefield);
        var ability = spell(player, "Savannah Lions");
        player.setLife(initialLife, null);
        game.getAction().checkStateEffects(true);
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        var result = RulesCostFeasibility.assess(player, ability);
        check(result.status() == RulesCostFeasibility.Status.PAYABLE, name + " pays an actual white spell: " + result.status() + " " + result.reason());
        var choices = new RulesPaymentChoices(player, ability);
        var witness = select(choices);
        check(witness.life() == 0 && witness.totalLife() == sourceLife && witness.sources().size() == 1,
            name + " separates source and action life");
        check(choices.request().getAsJsonArray("sourceOptions").asList().stream()
            .allMatch(o -> o.getAsJsonObject().get("life").getAsInt() == sourceLife), name + " every color option retains life cost");
        BenchRandomAudit.assertUnchanged(rng, "source life enumeration");
        BenchMenuStateAudit.assertUnchanged(before, game);
        var payment = new RulesPaymentExecutor(player, ability, witness);
        player.dangerouslySetController(new forge.ai.PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost, forge.game.cost.CostPartMana part,
                    SpellAbility actual, String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (matrix != null) throw new AssertionError("Unexpected mana conversion");
                return payment.pay(cost, part, actual, effect);
            }
        });
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(player, ability, null, payment::decisions), name + " actual cast executes");
        payment.assertPaid();
        check(player.getLife() == initialLife - sourceLife && source.isTapped() && ability.getHostCard().isInZone(ZoneType.Stack),
            name + " exact life/tap/stack receipt");
    }
    private static void aggregate() {
        var game = game(); var player = game.getPlayers().get(0);
        card("Mana Confluence", player, ZoneType.Battlefield); card("Mana Confluence", player, ZoneType.Battlefield);
        var reclamation = card("Phyrexian Reclamation", player, ZoneType.Battlefield);
        var creature = card("Savannah Lions", player, ZoneType.Graveyard);
        var ability = reclamation.getSpellAbilities().stream().filter(a -> a.isActivatedAbility()).findFirst().orElseThrow().copyForEnumeration(player);
        ability.getTargets().add(creature); game.getAction().checkStateEffects(true);
        player.setLife(3, null);
        check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.UNPAYABLE,
            "two individually payable sources plus action cannot overspend shared life");
        player.setLife(4, null);
        check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.PAYABLE,
            "exact total life is legal; no survivability threshold");
        var choices = new RulesPaymentChoices(player, ability);
        check(choices.request().getAsJsonArray("menu").asList().stream().allMatch(p -> p.getAsJsonObject().get("lifePaid").getAsInt() == 4),
            "every complete witness sums action and both source payments");
        player.setLife(3, null);
        boolean refused = false;
        try { new RulesPaymentExecutor(player, ability, select(choices)); }
        catch (RulesCostFeasibility.Unsupported expected) { refused = true; }
        check(refused, "stale life budget rejected before payment");
    }
    private static void skipAndZero() {
        var game = game(); var player = game.getPlayers().get(0);
        card("Blightsoil Druid", player, ZoneType.Battlefield);
        var confluence = card("Mana Confluence", player, ZoneType.Battlefield);
        var ability = spell(player, "Duress");
        player.setLife(1, null); game.getAction().checkStateEffects(true);
        var result = RulesCostFeasibility.assess(player, ability);
        check(result.status() == RulesCostFeasibility.Status.PAYABLE && result.witness().sources().size() == 1
            && result.witness().sources().get(0).ability().getHostCard() == confluence,
            "feasibility skips unnecessary painful green source to reserve life for black");
        player.setLife(0, null);
        check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.UNPAYABLE,
            "zero life cannot activate a positive life cost source");
        card("Swamp", player, ZoneType.Battlefield);
        check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.PAYABLE,
            "unpayable painful source does not hide a free legal mana source");
    }
    private static void distinctUnsupported() {
        for (String name : List.of("City of Brass", "Ancient Tomb", "Silent Clearing")) {
            var game = game(); var player = game.getPlayers().get(0);
            card(name, player, ZoneType.Battlefield); var ability = spell(player, "Sol Ring");
            game.getAction().checkStateEffects(true);
            check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.UNSUPPORTED,
                name + " effect/choice domain remains explicit unsupported, never relabelled fixed life");
        }
        var game = game(); var player = game.getPlayers().get(0);
        card("Mana Confluence", player, ZoneType.Battlefield); card("Platinum Emperion", player, ZoneType.Battlefield);
        var ability = spell(player, "Sol Ring"); game.getAction().checkStateEffects(true);
        check(RulesCostFeasibility.assess(player, ability).status() == RulesCostFeasibility.Status.UNSUPPORTED,
            "nonordinary life loss remains unsupported");
    }
    private static void replacements() {
        for (String name : List.of("Ashiok, Wicked Manipulator", "Bloodletter of Aclazotz")) {
            var game = game(); var player = game.getPlayers().get(0); var opponent = game.getPlayers().get(1);
            card("Mana Confluence", player, ZoneType.Battlefield); card("Forest", player, ZoneType.Library);
            var replacement = card(name, name.startsWith("Bloodletter") ? opponent : player, ZoneType.Battlefield);
            if (replacement.isPlaneswalker()) replacement.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
            if (name.startsWith("Bloodletter")) game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent);
            var ability = spell(player, "Lightning Bolt"); game.getAction().checkStateEffects(true);
            var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
            var result = RulesCostFeasibility.assess(player, ability);
            check(result.status() == RulesCostFeasibility.Status.UNSUPPORTED && result.reason().contains("replacement"),
                name + " actual active payment/life-loss replacement cannot masquerade as fixed source life: " + result.status() + " " + result.reason());
            BenchRandomAudit.assertUnchanged(rng, "life replacement rejection"); BenchMenuStateAudit.assertUnchanged(before, game);
        }
    }
    private static void production() {
        String answers = "{\"type\":\"answer\",\"id\":1,\"choice\":1}\n"
            + "{\"type\":\"answer\",\"id\":2,\"paymentVersion\":\"" + RulesPaymentDomain.PAYMENT_VERSION
            + "\",\"sourceOrder\":[\"SOURCE\"],\"spend\":[{\"token\":\"SOURCE:0\",\"shardIndex\":0}],\"lifePaid\":1}\n";
        // Prepare source id using real object/ability identity before binding input.
        var input = new java.io.PipedInputStream();
        var wire = new java.io.ByteArrayOutputStream();
        var session = new BenchSession(new JsonRpcChannel(input, wire));
        var lobby = new LobbyPlayerBridge("Payer", null, session, BenchSession.Mode.BRIDGE, 0); lobby.setAiProfile("Default");
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Source life controller fixture").createGame();
        var player = game.getPlayers().get(0); game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        card("Mana Confluence", player, ZoneType.Battlefield); var ability = spell(player, "Savannah Lions");
        game.getAction().checkStateEffects(true); BenchRandomAudit.install(402);
        var choices = new RulesPaymentDomain(player, ability);
        var white = choices.request().getAsJsonArray("sourceOptions").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(option -> option.get("choice").getAsString().equals("W")).toList();
        if (white.size() != 1) throw new AssertionError("Expected exactly one Confluence white option");
        var sourceId = white.get(0).get("id").getAsString();
        try (var sender = new java.io.PipedOutputStream(input)) {
            sender.write(answers.replace("SOURCE", sourceId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var before = BenchMenuStateAudit.capture(game);
            ((PlayerControllerBridge) player.getController()).probePriorityMenuPurity(); BenchMenuStateAudit.assertUnchanged(before, game);
            var selected = player.getController().chooseSpellAbilityToPlay();
            check(selected.size() == 1 && selected.get(0).getHostCard().getId() == ability.getHostCard().getId(), "production controller offers source-life-funded spell");
            check(player.getController().playChosenSpellAbility(selected.get(0)), "production controller executes selected source-life plan");
            check(player.getLife() == 19 && selected.get(0).getHostCard().isInZone(ZoneType.Stack), "production source life and stack receipts agree");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        var rows = wire.toString(java.nio.charset.StandardCharsets.UTF_8).lines().map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
        check(rows.size() == 2 && rows.get(1).get("kind").getAsString().equals("payment"), "production has no delegated source costs or colors");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "getAssetsDir" -> args[0] + "/forge-gui/";
                    case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                    case "getCurrentVersion" -> "source-life-fixture";
                    default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(401);
            execute("Mana Confluence", 1); execute("Myr Convert", 2);
            execute("Mana Confluence", 1, 1);
            aggregate(); skipAndZero(); distinctUnsupported(); replacements(); production();
            System.out.println("PASS all " + checks + " source-life checks; development only"); System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
