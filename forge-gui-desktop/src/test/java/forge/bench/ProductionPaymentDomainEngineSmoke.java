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
            case "source-life" -> new Scenario(name, "Savannah Lions", "Mana Confluence", 1, "W", false, false);
            case "life-only" -> new Scenario(name, "Sol Ring", null, 0, "", false, true);
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
        var selected = new ArrayList<JsonObject>();
        for (var raw : ask.getAsJsonArray("sourceOptions")) {
            var option = raw.getAsJsonObject();
            if (option.get("choice").getAsString().equals(scenario.colorChoice())) selected.add(option);
        }
        // Deliberate fixture choice: last physical source first, retaining all
        // selected surplus if requested. This is NOT a native strategy substitute.
        Collections.reverse(selected);
        if (!scenario.surplus() && selected.size() > 1) selected.subList(1, selected.size()).clear();
        var order = new JsonArray(); var tokenIds = new ArrayList<String>(); int life = ask.getAsJsonObject("cost").get("life").getAsInt();
        for (var option : selected) {
            String id = option.get("id").getAsString(); order.add(id); life += option.get("life").getAsInt();
            for (int i = 0; i < option.getAsJsonArray("output").size(); i++) tokenIds.add(id + ":" + i);
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
    private static JsonObject run(Scenario scenario, String fault, boolean external) throws Exception {
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
        if (scenario.lifeOnly()) card("Bolas's Citadel", payer, ZoneType.Battlefield);
        var spellCard = card(scenario.spell(), payer, scenario.lifeOnly() ? ZoneType.Library : ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        scripted.responder = ask -> {
            if (ask.get("kind").getAsString().equals("payment")) return scriptedPayment(ask, scenario, fault);
            if (!ask.get("kind").getAsString().equals("priority")) throw new AssertionError("Unexpected delegated surface " + ask);
            int choice = -1; var menu = ask.getAsJsonArray("menu");
            for (int i = 1; i < menu.size(); i++) if (menu.get(i).getAsJsonObject().get("fid").getAsInt() == spellCard.getId()) {
                var option = menu.get(i).getAsJsonObject();
                if (scenario.lifeOnly() && (!option.getAsJsonObject("cost").get("alternative").getAsBoolean()
                        || option.getAsJsonObject("cost").get("life").getAsInt() != 1)) continue;
                if (choice != -1) throw new AssertionError("Ambiguous fixture priority action: " + menu); choice = i;
            }
            if (choice < 1) throw new AssertionError("Fixture action was not offered");
            var answer = new JsonObject(); answer.addProperty("choice", choice); return answer;
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
                if (fault != null) throw new AssertionError("Malformed host answer was accepted: " + fault);
                check(!audit.toString(StandardCharsets.UTF_8).contains("engine-stack-add"), "Selection not falsely labelled execution");
                check(payer.getController().playChosenSpellAbility(selected), "Real production bridge executes selected symbolic payment");
            } catch (RulesCostFeasibility.Unsupported invalid) {
                if (fault == null) throw invalid;
                rejected = invalid;
            }
            BenchActionAudit.finishGame(game);
        } finally { System.setErr(stderr); }
        var requests = rows(wire.toString(StandardCharsets.UTF_8)).stream().filter(r -> "ask".equals(r.get("type").getAsString())).toList();
        var answers = rows(input.received.toString(StandardCharsets.UTF_8));
        check(requests.size() == 2 && requests.get(0).get("kind").getAsString().equals("priority")
                && requests.get(1).get("kind").getAsString().equals("payment"), "Exactly production priority and symbolic payment asks");
        var priority = requests.get(0); var payment = requests.get(1);
        check(priority.get("paymentVersion").getAsString().equals(RulesCostFeasibility.PAYMENT_VERSION)
                && payment.get("paymentVersion").getAsString().equals(RulesCostFeasibility.PAYMENT_VERSION)
                && !payment.has("menu") && payment.get("complete").getAsBoolean()
                && payment.get("representation").getAsString().equals(RulesPaymentDomain.REPRESENTATION), "Consistent advertised complete symbolic capability; no eager production menu");
        String privateLog = audit.toString(StandardCharsets.UTF_8);
        var summaries = privateLog.lines().filter(l -> l.startsWith("[bench-action] ")).map(l -> JsonParser.parseString(l.substring("[bench-action] ".length())).getAsJsonObject()).toList();
        var summary = summaries.stream().filter(r -> r.get("kind").getAsString().equals("summary")).findFirst().orElseThrow();
        if (fault != null) {
            check(rejected != null && rejected.getMessage().startsWith("BENCH_INTEGRITY_UNSUPPORTED"), "Bad payment explicitly invalidates, no heuristic fallback");
            check(summary.get("selected").getAsInt() == 0 && summary.get("observed").getAsInt() == 0
                    && summary.get("unobserved").getAsInt() == 0 && !privateLog.contains("engine-stack-add"), "Malformed payment cannot obtain execution receipt");
            BenchMenuStateAudit.assertUnchanged(before, game); BenchRandomAudit.assertUnchanged(rng, "rejected production payment");
        } else {
            check(selected.getHostCard().isInZone(ZoneType.Stack) && summary.get("selected").getAsInt() == 1
                    && summary.get("observed").getAsInt() == 1 && summary.get("unobserved").getAsInt() == 0
                    && !privateLog.contains("BENCH_INTEGRITY_"), "Actual paid stack event matches selected action receipt");
            var selection = summaries.stream().filter(r -> r.get("kind").getAsString().equals("selected-not-executed")).findFirst().orElseThrow();
            check(selection.getAsJsonObject("answer").get("id").equals(priority.get("id")), "Action receipt retains original priority answer, not payment answer");
            var hostAnswer = answers.get(1); var order = hostAnswer.getAsJsonArray("sourceOrder");
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
                check(selected.getPayingMana().get(i) == actualTokens.get(slot), "Exact host-selected output unit consumed");
            }
            check(payer.getLife() == lifeBefore - hostAnswer.get("lifePaid").getAsInt()
                    && payer.getManaPool().totalMana() == produced - spend.size(), "Actual life and surplus equal requested payment");
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
                for (String name : List.of("twenty-plains", "twenty-surplus", "mixed-rw", "mixed-hybrid", "source-life", "life-only")) run(scenario(name), null, false);
                for (String fault : List.of("legacy-choice", "wrong-version", "order-wrong-type", "spend-wrong-type", "life-wrong-type", "unknown-order-source",
                        "duplicate-source", "source-not-selected", "duplicate-token", "duplicate-shard", "partial-payment", "shard-wrong-type", "delegate"))
                    run(scenario("two-shards"), fault, false);
                System.out.println("PASS " + checks + " production symbolic-payment checks; no games or strength claim");
            }
            System.exit(0);
        } catch (Throwable failure) { System.setOut(stdout); failure.printStackTrace(System.err); System.exit(1); }
    }
}
