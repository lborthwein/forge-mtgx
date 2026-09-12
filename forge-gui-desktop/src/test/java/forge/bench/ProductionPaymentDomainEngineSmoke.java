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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Real bridge→channel→host answer→engine payment and private execution receipt.
 * --stdio CASE replaces the scripted test host with a real external stdio host.
 * This exercises one fixture cast, not a match or strength benchmark.
 */
public final class ProductionPaymentDomainEngineSmoke {
    private static PrintStream protocolOutput;
    private record Scenario(String name, String spell, String source, int count, String colorChoice, boolean surplus, boolean lifeOnly) {}
    private static int checks;
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static Scenario scenario(String name) {
        return switch (name) {
            case "twenty-plains" -> new Scenario(name, "Savannah Lions", "Plains", 20, "", false, false);
            case "twenty-surplus" -> new Scenario(name, "Savannah Lions", "Plains", 20, "", true, false);
            case "two-shards" -> new Scenario(name, "Silvercoat Lion", "Plains", 2, "", true, false);
            case "mixed-rw" -> new Scenario(name, "Boros Swiftblade", "Boros Garrison", 1, "", false, false);
            case "mixed-hybrid" -> new Scenario(name, "Burning-Tree Emissary", "Gruul Turf", 1, "", false, false);
            case "hybrid-generic" -> new Scenario(name, "Mistmeadow Witch", "Azorius Chancery", 1, "", false, false);
            case "source-life" -> new Scenario(name, "Savannah Lions", "Mana Confluence", 1, "W", false, false);
            case "noble-white" -> new Scenario(name, "Savannah Lions", "Noble Hierarch", 1, "W", false, false);
            case "noble-blue" -> new Scenario(name, "Sol Ring", "Noble Hierarch", 1, "U", false, false);
            case "ignoble-black" -> new Scenario(name, "Sol Ring", "Ignoble Hierarch", 1, "B", false, false);
            case "life-only" -> new Scenario(name, "Sol Ring", null, 0, "", false, true);
            case "face-down-exile" -> new Scenario(name, "Savannah Lions", "Plains", 1, "", false, false);
            case "adventure-exile" -> new Scenario(name, "Savannah Lions", "Plains", 1, "", false, false);
            case "x-tax-mind-twist" -> new Scenario(name, "Mind Twist", "Swamp", 4, "", false, false);
            case "x-tax-forth" -> new Scenario(name, "Forth Eorlingas!", "Plains", 4, "", false, false);
            case "top-tax" -> new Scenario(name, "Sol Ring", "Plains", 2, "", true, false);
            case "kinnan-basalt-full" -> new Scenario(name, "Juggernaut", "Basalt Monolith", 1, "", false, false);
            case "kinnan-grim-full" -> new Scenario(name, "Juggernaut", "Grim Monolith", 1, "", false, false);
            default -> throw new IllegalArgumentException("Unknown fixture " + name);
        };
    }
    private static List<JsonObject> rows(String text) {
        return text.lines().filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
    }
    private static final class TrackingInput extends FilterInputStream {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        TrackingInput(InputStream input) { super(input); }
        @Override public int read() throws IOException { int b = in.read(); if (b >= 0) received.write(b); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int count = in.read(b, off, len); if (count > 0) received.write(b, off, count); return count;
        }
    }
    private static final class ScriptedHost extends InputStream {
        final ByteArrayOutputStream wire;
        Function<JsonObject, JsonObject> responder;
        byte[] pending = new byte[0]; int at; int answered;
        ScriptedHost(ByteArrayOutputStream wire) { this.wire = wire; }
        private void prepare() {
            if (at < pending.length) return;
            var asks = rows(wire.toString(StandardCharsets.UTF_8)).stream().filter(r -> "ask".equals(r.get("type").getAsString())).toList();
            if (asks.isEmpty()) throw new AssertionError("Host read before a real emitted ask");
            var ask = asks.get(asks.size() - 1);
            int id = ask.get("id").getAsInt();
            if (id <= answered) throw new AssertionError("Unexpected speculative/repeated host read");
            var answer = responder.apply(ask.deepCopy());
            answer.addProperty("type", "answer"); answer.addProperty("id", id);
            pending = (answer + "\n").getBytes(StandardCharsets.UTF_8); at = 0; answered = id;
        }
        @Override public int read() { prepare(); return pending[at++] & 255; }
        @Override public int read(byte[] b, int off, int len) {
            if (len == 0) return 0;
            prepare(); int count = Math.min(len, pending.length - at); System.arraycopy(pending, at, b, off, count); at += count; return count;
        }
    }
    private static Card card(String name, Player owner, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp()); owner.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static JsonObject scriptedPayment(JsonObject ask, Scenario scenario, String fault) {
        var answer = new JsonObject(); answer.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION);
        answer.add("x", ask.getAsJsonObject("cost").get("x"));
        var selected = new ArrayList<JsonObject>();
        for (var raw : ask.getAsJsonArray("sourceOptions")) {
            var option = raw.getAsJsonObject();
            if (option.get("choice").getAsString().equals(scenario.colorChoice())) selected.add(option);
        }
        // Deliberate fixture choice: last physical source first, retaining all
        // selected surplus if requested. This is NOT a native strategy substitute.
        Collections.reverse(selected);
        if (!scenario.surplus() && !scenario.name().startsWith("x-tax-") && selected.size() > 1) selected.subList(1, selected.size()).clear();
        var order = new JsonArray(); var tokenIds = new ArrayList<String>(); int life = ask.getAsJsonObject("cost").get("life").getAsInt();
        for (var option : selected) {
            String id = option.get("id").getAsString(); order.add(id); life += option.get("life").getAsInt();
            for (int i = 0; i < option.getAsJsonArray("output").size(); i++) tokenIds.add(id + ":" + i);
        }
        if (scenario.name().equals("x-tax-forth")) {
            // Explicit fixture assignment: retain reverse activation order,
            // but allocate the sole red output to RED rather than WHITE.
            var shards=ask.getAsJsonObject("cost").getAsJsonArray("shards");
            int red=-1;for(int i=0;i<shards.size();i++)if(shards.get(i).getAsString().equals("RED"))red=i;
            check(red>=0&&selected.get(0).getAsJsonArray("output").get(0).getAsString().equals("R"),"Forth fixture red token/shard verified");
            Collections.swap(tokenIds,0,red);
        }
        var spend = new JsonArray();
        for (int i = 0; i < ask.getAsJsonObject("cost").getAsJsonArray("shards").size(); i++) {
            var allocation = new JsonObject(); allocation.addProperty("token", tokenIds.get(i)); allocation.addProperty("shardIndex", i); spend.add(allocation);
        }
        answer.add("sourceOrder", order); answer.add("spend", spend); answer.addProperty("lifePaid", life);
        if (fault != null) switch (fault) {
            case "legacy-choice" -> answer.addProperty("choice", 0);
            case "wrong-version" -> answer.addProperty("paymentVersion", "rules-payment-v3-source-life");
            case "order-wrong-type" -> answer.addProperty("sourceOrder", "not-an-array");
            case "spend-wrong-type" -> answer.add("spend", new JsonObject());
            case "life-wrong-type" -> answer.addProperty("lifePaid", "0");
            case "unknown-order-source" -> order.set(0, new JsonPrimitive("unadvertised-source"));
            case "duplicate-source" -> order.set(1, order.get(0));
            case "source-not-selected" -> order.remove(0);
            case "duplicate-token" -> spend.get(1).getAsJsonObject().add("token", spend.get(0).getAsJsonObject().get("token"));
            case "duplicate-shard" -> spend.get(1).getAsJsonObject().addProperty("shardIndex", 0);
            case "partial-payment" -> spend.remove(1);
            case "shard-wrong-type" -> spend.get(0).getAsJsonObject().addProperty("shardIndex", "0");
            case "delegate" -> answer.addProperty("delegate", true);
            default -> throw new AssertionError("Unknown fault " + fault);
        }
        return answer;
    }
    /** Strict rollback characterization retained for ControlledAnnouncementSmoke. */
    private static JsonObject run(Scenario scenario, String fault, boolean external) throws Exception {
        return run(scenario, fault, external, false);
    }
    /** Standalone malformed-payment contract: failed games are poisoned, not reversible. */
    private static JsonObject runFailedGameValidation(Scenario scenario, String fault) throws Exception {
        if (fault == null) throw new IllegalArgumentException("failed-game validation requires malformed payment");
        return run(scenario, fault, false, true);
    }
    private static JsonObject run(Scenario scenario, String fault, boolean external, boolean validateFailedGame) throws Exception {
        var wire = new ByteArrayOutputStream();
        var scripted = new ScriptedHost(wire);
        var input = new TrackingInput(external ? System.in : scripted);
        OutputStream output = external ? new OutputStream() {
            @Override public void write(int value) { wire.write(value); protocolOutput.write(value); }
            @Override public void write(byte[] bytes, int off, int len) { wire.write(bytes, off, len); protocolOutput.write(bytes, off, len); }
            @Override public void flush() { protocolOutput.flush(); }
        } : wire;
        var channel = new JsonRpcChannel(input, output); var session = new BenchSession(channel);
        var lobby = new LobbyPlayerBridge("Payer", null, session, BenchSession.Mode.BRIDGE, 0); lobby.setAiProfile("Default");
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Production compact payment fixture").createGame();
        var payer = game.getPlayers().get(0); game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, payer); session.setLiveGame(game);
        var sources = new ArrayList<Card>();
        for (int i = 0; i < scenario.count(); i++) sources.add(card(scenario.source(), payer, ZoneType.Battlefield));
        boolean kinnanCase=scenario.name().startsWith("kinnan-");
        Card kinnan=kinnanCase?card("Kinnan, Bonder Prodigy",payer,ZoneType.Battlefield):null;
        boolean xTax = scenario.name().startsWith("x-tax-");
        boolean topTax = scenario.name().equals("top-tax");
        if (topTax) {
            card("Future Sight", payer, ZoneType.Battlefield);
            card("Thalia, Guardian of Thraben", game.getPlayers().get(1), ZoneType.Battlefield);
        }
        if (xTax) {
            card("Thalia, Guardian of Thraben", game.getPlayers().get(1), ZoneType.Battlefield);
            if (scenario.name().equals("x-tax-forth")) sources.add(card("Mountain", payer, ZoneType.Battlefield));
            for (String name : List.of("Forest", "Island", "Mountain")) card(name, game.getPlayers().get(1), ZoneType.Hand);
        }
        if (scenario.lifeOnly()) card("Bolas's Citadel", payer, ZoneType.Battlefield);
        boolean faceDownExile = scenario.name().equals("face-down-exile");
        boolean adventureExile = scenario.name().equals("adventure-exile");
        var fixtureCard = card(scenario.spell(), faceDownExile || adventureExile ? game.getPlayers().get(1) : payer,
                faceDownExile ? ZoneType.Exile : adventureExile || scenario.lifeOnly() || topTax ? ZoneType.Library : ZoneType.Hand);
        if (faceDownExile) {
            // Payment/cleanup fixture only: construct the genuine DBEffect
            // permission without claiming full Dig/adventure-path coverage or
            // its WithMayLook observation state. Full casting is tested separately.
            var source = card("Decadent Dragon", payer, ZoneType.Battlefield);
            source.setState(forge.card.CardStateName.Secondary, false);
            fixtureCard.turnFaceDown(true); source.addRemembered(fixtureCard);
            var permission = forge.game.ability.AbilityFactory.getAbility(source.getSVar("DBEffect"), source);
            permission.setActivatingPlayer(payer); forge.game.ability.AbilityUtils.resolve(permission);
        }
        if (adventureExile) {
            // Real setup cast/resolution uses a named fixture Default controller,
            // not the tested bridge policy. Only the subsequent Lions cast is
            // counted as the production host-selected action below.
            var bridge = payer.getController();
            payer.dangerouslySetController(new forge.ai.PlayerControllerAi(game, payer, payer.getLobbyPlayer()));
            try {
                BenchRandomAudit.install(91804);
                card("Forest", game.getPlayers().get(1), ZoneType.Library);
                card("Mind Twist", game.getPlayers().get(1), ZoneType.Library);
                for (int i = 0; i < 3; i++) card("Swamp", payer, ZoneType.Battlefield);
                var dragon = card("Decadent Dragon", payer, ZoneType.Hand);
                dragon.setState(forge.card.CardStateName.Secondary, false);
                game.getAction().checkStateEffects(true);
                var dig = dragon.getSpellAbilities().stream()
                        .filter(a -> a.getApi() == forge.game.ability.ApiType.Dig).findFirst().orElseThrow();
                dig.setActivatingPlayer(payer); dig.getTargets().add(game.getPlayers().get(1));
                check(forge.ai.ComputerUtil.handlePlayingSpellAbility(payer, dig, null), "Setup casts actual Expensive Taste");
                game.getStack().resolveStack(); game.getAction().checkStateEffects(true);
                check(game.getStack().isEmpty() && payer.getCardsIn(ZoneType.Exile).stream()
                        .anyMatch(c -> c.getId() == dragon.getId() && !c.isFaceDown()), "Setup resolves real Dig and Adventure cleanup");
                check(!sources.get(0).isTapped(), "Setup leaves the intended Plains available for the bridged cast");
            } finally { payer.dangerouslySetController(bridge); }
        }
        // A real zone change may replace Card objects while preserving their fid.
        final Card spellCard = adventureExile ? game.getPlayers().get(1).getCardsIn(ZoneType.Exile).stream()
                .filter(c -> c.getId() == fixtureCard.getId()).findFirst().orElseThrow() : fixtureCard;
        if (adventureExile) {
            var visible = StateEncoder.encodeCard(spellCard, payer.getView());
            check(spellCard.isFaceDown() && visible != null && visible.getAsJsonObject("knownFace")
                    .get("name").getAsString().equals("Savannah Lions"), "Setup grants actor the real known original face");
            check(StateEncoder.encodeCard(spellCard, game.getPlayers().get(1).getView()) == null,
                    "Setup does not grant the owning opponent hidden identity access");
        }
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        scripted.responder = ask -> {
            if (ask.get("kind").getAsString().equals("payment")) return scriptedPayment(ask, scenario, fault);
            if (xTax && ask.get("kind").getAsString().equals("targets")) {
                var answer=new JsonObject();var choices=new JsonArray();var target=new JsonObject();target.addProperty("kind","player");target.addProperty("id",game.getPlayers().get(1).getId());choices.add(target);answer.add("choices",choices);return answer;
            }
            if (!ask.get("kind").getAsString().equals("priority")) throw new AssertionError("Unexpected delegated surface " + ask);
            int choice = -1; var menu = ask.getAsJsonArray("menu");
            for (int i = 1; i < menu.size(); i++) if (menu.get(i).getAsJsonObject().get("fid").getAsInt() == spellCard.getId()) {
                var option = menu.get(i).getAsJsonObject();
                if (scenario.lifeOnly() && (!option.getAsJsonObject("cost").get("alternative").getAsBoolean()
                        || option.getAsJsonObject("cost").get("life").getAsInt() != 1)) continue;
                if (choice != -1) throw new AssertionError("Ambiguous fixture priority action: " + menu); choice = i;
            }
            if (choice < 1) throw new AssertionError("Fixture action was not offered");
            var answer = new JsonObject(); answer.addProperty("choice", choice); if(xTax)answer.addProperty("x",2);return answer;
        };
        BenchRandomAudit.install(91803);
        BenchActionAudit.beginGame(game, "fixture"); game.subscribeToEvents(new BenchMain.EventEmitter(channel, "fixture", game));
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        int lifeBefore = payer.getLife(); var audit = new ByteArrayOutputStream(); var stderr = System.err;
        RulesCostFeasibility.Unsupported rejected = null; SpellAbility selected = null;
        try {
            System.setErr(new PrintStream(audit, true, StandardCharsets.UTF_8));
            try {
                var choices = payer.getController().chooseSpellAbilityToPlay();
                check(choices != null && choices.size() == 1 && choices.get(0).getHostCard().getId() == spellCard.getId(), "Actual selected fixture spell");
                selected = choices.get(0);
                check(!audit.toString(StandardCharsets.UTF_8).contains("engine-stack-add"), "Selection not falsely labelled execution");
                check(payer.getController().playChosenSpellAbility(selected), "Real production bridge executes selected symbolic payment");
                if (fault != null) throw new AssertionError("Malformed host answer was accepted: " + fault);
            } catch (RulesCostFeasibility.Unsupported invalid) {
                if (fault == null) throw invalid;
                rejected = invalid;
            }
            BenchActionAudit.finishGame(game);
        } finally { System.setErr(stderr); }
        var requests = rows(wire.toString(StandardCharsets.UTF_8)).stream().filter(r -> "ask".equals(r.get("type").getAsString())).toList();
        var answers = rows(input.received.toString(StandardCharsets.UTF_8));
        int paymentIndex = scenario.name().equals("x-tax-mind-twist") ? 2 : 1;
        check(requests.size() == paymentIndex + 1 && requests.get(0).get("kind").getAsString().equals("priority")
                && requests.get(paymentIndex).get("kind").getAsString().equals("payment")
                && (paymentIndex == 1 || requests.get(1).get("kind").getAsString().equals("targets")),
                "Exactly expected priority, optional target and payment asks");
        var priority = requests.get(0); var payment = requests.get(paymentIndex);
        if (adventureExile) {
            var option = priority.getAsJsonArray("menu").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(o -> o.has("fid") && o.get("fid").getAsInt() == spellCard.getId()).findFirst().orElseThrow();
            check(option.get("source").getAsString().equals("Savannah Lions"), "Real priority menu retains authorized exiled source identity");
            check(priority.getAsJsonObject("state").getAsJsonArray("players").get(1).getAsJsonObject().getAsJsonArray("exile")
                    .asList().stream().map(JsonElement::getAsJsonObject).anyMatch(c -> c.get("fid").getAsInt() == spellCard.getId()
                    && c.getAsJsonObject("knownFace").get("name").getAsString().equals("Savannah Lions")),
                    "Real priority state agrees with the authorized exiled spell menu");
        }
        if (scenario.lifeOnly()) {
            check(payment.getAsJsonObject("cost").get("expandedMana").getAsString().equals("{0}"),
                    "Life-only cast expands to an explicit zero mana bill, not absent/unpayable mana cost");
            var alternatives = priority.getAsJsonArray("menu").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(o -> o.has("fid") && o.get("fid").getAsInt() == spellCard.getId()).toList();
            check(alternatives.size() == 1 && alternatives.get(0).getAsJsonObject("cost").get("life").getAsInt() == 1,
                    "Production Citadel menu has exactly one legal pay1 variant, never double-applied pay2");
        }
        check(priority.get("paymentVersion").getAsString().equals(RulesCostFeasibility.PAYMENT_VERSION)
                && payment.get("paymentVersion").getAsString().equals(RulesCostFeasibility.PAYMENT_VERSION)
                && !payment.has("menu") && payment.get("complete").getAsBoolean()
                && payment.get("representation").getAsString().equals(kinnanCase?"token-shard-domain-v2-producers":RulesPaymentDomain.REPRESENTATION), "Consistent advertised complete symbolic capability; no eager production menu");
        String privateLog = audit.toString(StandardCharsets.UTF_8);
        var summaries = privateLog.lines().filter(l -> l.startsWith("[bench-action] ")).map(l -> JsonParser.parseString(l.substring("[bench-action] ".length())).getAsJsonObject()).toList();
        var summary = summaries.stream().filter(r -> r.get("kind").getAsString().equals("summary")).findFirst().orElseThrow();
        if (fault != null) {
            check(rejected != null && rejected.getMessage().startsWith("BENCH_INTEGRITY_UNSUPPORTED"), "Bad payment explicitly invalidates, no heuristic fallback");
            check(summary.get("selected").getAsInt() == 0 && summary.get("observed").getAsInt() == 0
                    && summary.get("unobserved").getAsInt() == 0 && !privateLog.contains("engine-stack-add"), "Malformed payment cannot obtain execution receipt");
            if (validateFailedGame) {
                check(sources.stream().noneMatch(Card::isTapped) && payer.getLife() == lifeBefore && game.getStack().isEmpty(),
                        "Malformed payment has no mana, life, or stack receipt");
                try { payer.getController().chooseSpellAbilityToPlay(); throw new AssertionError("failed game resumed"); }
                catch (RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("cannot continue"), "Malformed payment permanently invalidates game"); }
                check(session.integrityFailure(game) != null, "Failed game records an integrity failure on its live session");
                var outcome = new JsonObject(); outcome.addProperty("crashed", false); outcome.addProperty("aborted", "fixture");
                BenchMain.guardIntegrityOutcome(session, game, outcome);
                check(outcome.get("crashed").getAsBoolean() && outcome.get("aborted").getAsString().equals("InstrumentError")
                                && outcome.get("reason").getAsString().equals("InstrumentError"),
                        "Integrity outcome guard rejects permanently invalid game");
            } else BenchMenuStateAudit.assertUnchanged(before, game);
            BenchRandomAudit.assertUnchanged(rng, "rejected production payment");
        } else {
            check(selected.getHostCard().isInZone(ZoneType.Stack) && summary.get("selected").getAsInt() == 1
                    && summary.get("observed").getAsInt() == 1 && summary.get("unobserved").getAsInt() == 0
                    && !privateLog.contains("BENCH_INTEGRITY_"), "Actual paid stack event matches selected action receipt");
            var selection = summaries.stream().filter(r -> r.get("kind").getAsString().equals("selected-not-executed")).findFirst().orElseThrow();
            check(selection.getAsJsonObject("answer").get("id").equals(priority.get("id")), "Action receipt retains original priority answer, not payment answer");
            var hostAnswer = answers.get(paymentIndex); var order = hostAnswer.getAsJsonArray("sourceOrder");
            var options = new HashMap<String, JsonObject>();
            for (var option : payment.getAsJsonArray("sourceOptions")) options.put(option.getAsJsonObject().get("id").getAsString(), option.getAsJsonObject());
            check(selected.getPayingManaAbilities().size() == order.size(), "Actual source activation count equals host request");
            int produced = 0;
            for (int i = 0; i < order.size(); i++) {
                var option = options.get(order.get(i).getAsString());
                check(selected.getPayingManaAbilities().get(i).getHostCard().getId() == option.get("fid").getAsInt(), "Actual activation order equals host order");
                produced += option.getAsJsonArray("output").size();
            }
            var spend = hostAnswer.getAsJsonArray("spend");
            check(selected.getPayingMana().size() == spend.size(), "Exact allocated mana count executed");
            for (int i = 0; i < spend.size(); i++) {
                String tokenId = spend.get(i).getAsJsonObject().get("token").getAsString(); int split = tokenId.lastIndexOf(':');
                var option = options.get(tokenId.substring(0, split)); int slot = Integer.parseInt(tokenId.substring(split + 1));
                var source = sources.stream().filter(c -> c.getId() == option.get("fid").getAsInt()).findFirst().orElseThrow();
                var actualTokens = List.copyOf(source.getManaAbilities().get(option.get("abilityIndex").getAsInt()).getManaPart().getLastManaProduced());
                if(kinnanCase && slot==3) {
                    var actualBonus=selected.getPayingMana().get(i);
                    check(actualBonus.getSourceCard().getId()==kinnan.getId() && actualBonus.getPlayer()==payer
                            && actualTokens.stream().noneMatch(m->m==actualBonus),"Actual independent Kinnan bonus consumed, not a fourth Monolith unit");
                    check(option.getAsJsonArray("outputOrigins").get(slot).getAsJsonObject().get("sourceFid").getAsInt()==kinnan.getId(),"Wire bonus provenance agrees with native payment");
                } else check(selected.getPayingMana().get(i) == actualTokens.get(slot), "Exact host-selected output unit consumed");
            }
            check(payer.getLife() == lifeBefore - hostAnswer.get("lifePaid").getAsInt()
                    && payer.getManaPool().totalMana() == produced - spend.size(), "Actual life and surplus equal requested payment");
            if (xTax) {
                check(selected.getXManaCostPaid() == 2 && hostAnswer.get("x").getAsInt() == 2,
                        "Explicit selected X survives TypeScript payment and real execution");
                check(spend.size() == (scenario.name().equals("x-tax-forth") ? 5 : 4), "Exactly one Thalia tax paid");
                game.getStack().resolveStack();
                if (scenario.name().equals("x-tax-mind-twist"))
                    check(game.getPlayers().get(1).getCardsIn(ZoneType.Hand).size() == 1, "Actual Mind Twist discards X cards");
                else check(payer.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count() == 2,
                        "Actual Forth creates X tokens");
            }
            if (!external && scenario.count() == 20) check(order.size() == (scenario.surplus() ? 20 : 1)
                    && options.get(order.get(0).getAsString()).get("fid").getAsInt() == sources.get(19).getId(), "20-source fixture preserves deliberate late source / reverse surplus choice");
            if (!external) {
                var exported = new JsonObject(); exported.addProperty("case", scenario.name()); exported.add("priority", priority); exported.add("request", payment); exported.add("answer", hostAnswer);
                System.out.println("WIRE_PAYMENT_FIXTURE " + exported);
            }
        }
        check(!wire.toString(StandardCharsets.UTF_8).contains("forge-bench-private-action"), "Private receipt never sent to host policy");
        var result = new JsonObject(); result.addProperty("type", "fixture-result"); result.addProperty("case", scenario.name());
        result.addProperty("passed", true); result.addProperty("fault", fault); result.add("receiptSummary", summary);
        if (external) result.addProperty("actualBridgeRoundtrip", true);
        else System.out.println("PASS production symbolic payment " + scenario.name() + " fault=" + fault);
        return result;
    }
    public static void main(String[] args) {
        boolean stdio = args.length > 1 && args[1].equals("--stdio"); PrintStream stdout = System.out; protocolOutput = stdout;
        try {
            if (stdio) System.setOut(System.err);
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "production-symbolic-payment-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            if (stdio) {
                // Keep incidental engine render chatter off the stdio protocol
                // during execution as well as bootstrap. The channel owns the
                // original stdout handle explicitly, not this diagnostic stream.
                stdout.println(run(scenario(args[2]), null, true));
            } else {
                for (String name : List.of("twenty-plains", "twenty-surplus", "two-shards", "mixed-rw", "mixed-hybrid", "hybrid-generic", "source-life", "noble-white", "noble-blue", "ignoble-black", "life-only", "face-down-exile", "adventure-exile", "kinnan-basalt-full", "kinnan-grim-full")) run(scenario(name), null, false);
                for (String fault : List.of("legacy-choice", "wrong-version", "order-wrong-type", "spend-wrong-type", "life-wrong-type", "unknown-order-source",
                        "duplicate-source", "source-not-selected", "duplicate-token", "duplicate-shard", "partial-payment", "shard-wrong-type", "delegate"))
                    runFailedGameValidation(scenario("two-shards"), fault);
                System.out.println("PASS " + checks + " production symbolic-payment checks; no games or strength claim");
            }
            System.exit(0);
        } catch (Throwable failure) { System.setOut(stdout); failure.printStackTrace(System.err); System.exit(1); }
    }
}
