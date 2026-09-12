package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
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

/** Scripted production callbacks, not a game or policy/playing-strength test. */
public final class PriorityOwnershipEngineSmoke {
    private static int checks, seat;
    private static final String PRIORITY = "chooseSpellAbilityToPlay";
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; System.out.println("PASS " + label); }
    private static void rejects(Runnable action) { try { action.run(); } catch (RulesCostFeasibility.Unsupported expected) { checks++; System.out.println("EXPECTED_UNSUPPORTED " + expected.getMessage()); return; } throw new AssertionError("Expected explicit failure"); }
    private static Card card(String name, Player p, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(zone).add(c); c.setSickness(false); return c;
    }
    private static int bucket(Context c, String method, String owner) {
        var methods = c.controller.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
        return methods.has(method) ? methods.getAsJsonObject(method).get(owner).getAsInt() : 0;
    }
    private static void ownership(Context c, String owner) {
        for (String name : List.of("host", "forced", "rules", "stock", "unclassified"))
            check(bucket(c, PRIORITY, name) == (name.equals(owner) ? 1 : 0), "priority " + name + " seat=" + seat + " scenario=" + c.host.scenario);
        var ledger = c.controller.getCounters().toJson(); int sum = 0;
        var calls = ledger.getAsJsonObject("calls");
        for (var entry : ledger.getAsJsonObject("controllerCoverage").getAsJsonObject("methods").entrySet()) {
            int n = entry.getValue().getAsJsonObject().entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
            check(n == calls.get(entry.getKey()).getAsInt(), "per-method conservation " + entry.getKey()); sum += n;
        }
        check(sum == ledger.get("totalCalls").getAsInt(), "total invocation conservation");
        System.out.println("OWNERSHIP " + ledger);
    }
    private static JsonObject rng() {
        var out = ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot();
        out.remove("purityChecks"); return out;
    }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final List<JsonObject> asks = new ArrayList<>(), answers = new ArrayList<>();
        String scenario = "cast", rawChoice; int fid, targetId; Card removeBeforeReturn;
        byte[] pending = new byte[0]; int offset, answered;
        private void prepare() {
            if (offset < pending.length) return;
            var all = wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o -> o.has("type") && o.get("type").getAsString().equals("ask")).toList();
            var ask = all.get(all.size() - 1); int id = ask.get("id").getAsInt();
            if (id <= answered) throw new AssertionError("Unexpected repeated host read");
            var answer = new JsonObject(); answer.addProperty("type", "answer"); answer.addProperty("id", id);
            String kind = ask.get("kind").getAsString();
            if (scenario.equals("delegate")) answer.addProperty("delegate", true);
            else switch (kind) {
                case "priority" -> {
                    var menu = ask.getAsJsonArray("menu"); int choice = -1;
                    for (int i = 1; i < menu.size(); i++) if (menu.get(i).getAsJsonObject().get("fid").getAsInt() == fid) choice = i;
                    if (scenario.equals("pass")) choice = 0;
                    else if (scenario.equals("invalid-choice")) choice = menu.size() + 1;
                    else if (choice < 1) throw new AssertionError("Fixture action not offered " + ask);
                    answer.addProperty("choice", choice);
                    if (rawChoice != null) answer.add("choice", JsonParser.parseString(rawChoice));
                    if (scenario.equals("invalid-x")) answer.addProperty("x", 2);
                    if (removeBeforeReturn != null) removeBeforeReturn.getGame().getAction().moveToGraveyard(removeBeforeReturn, null);
                }
                case "targets" -> {
                    var choices = new JsonArray(); choices.add(scenario.equals("invalid-target") ? -999 : targetId); answer.add("choices", choices);
                }
                case "payment" -> {
                    var option = ask.getAsJsonArray("sourceOptions").get(0).getAsJsonObject(); String source = option.get("id").getAsString();
                    answer.addProperty("paymentVersion", scenario.equals("invalid-payment") ? "wrong-version" : RulesPaymentDomain.PAYMENT_VERSION);
                    answer.addProperty("lifePaid", 0); answer.addProperty("x", 0);
                    var order = new JsonArray(); order.add(source); answer.add("sourceOrder", order);
                    var spend = new JsonArray(); var token = new JsonObject(); token.addProperty("token", source + ":0"); token.addProperty("shardIndex", 0); spend.add(token); answer.add("spend", spend);
                }
                default -> answer.addProperty("delegate", true); // Existing stock fallback may make nested asks; never pretend those were host choices.
            }
            asks.add(ask.deepCopy()); answers.add(answer.deepCopy());
            System.out.println("RAW_ASK " + ask); System.out.println("RAW_ANSWER " + answer);
            pending = (answer + "\n").getBytes(StandardCharsets.UTF_8); offset = 0; answered = id;
        }
        @Override public int read() { if (scenario.equals("eof")) return -1; prepare(); return pending[offset++] & 255; }
        @Override public int read(byte[] data, int start, int length) { if (length == 0) return 0; if (scenario.equals("eof")) return -1; prepare(); int n = Math.min(length, pending.length - offset); System.arraycopy(pending, offset, data, start, n); offset += n; return n; }
        List<String> kinds() { return asks.stream().map(a -> a.get("kind").getAsString()).toList(); }
    }
    private record Context(Game game, Player payer, PlayerControllerBridge controller, Host host, BenchSession session) {}
    private static Context context(BenchSession.Mode mode) {
        var host = new Host(); var session = new BenchSession(new JsonRpcChannel(host, host.wire));
        var lobby = new LobbyPlayerBridge("Payer", null, session, mode, seat); lobby.setAiProfile("Default");
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1 - seat, 0, null, "Default"));
        var game = new Match(new GameRules(GameType.Constructed), seat == 0 ? List.of(own, other) : List.of(other, own), "Priority ownership fixture").createGame();
        game.setAge(GameStage.Play); var payer = game.getPlayers().get(seat); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, payer); session.setLiveGame(game);
        return new Context(game, payer, (PlayerControllerBridge) payer.getController(), host, session);
    }
    private static void ready(Context c) { c.game.getAction().checkStateEffects(true); c.controller.getCounters().reset(); BenchRandomAudit.install(71939); }
    private static void hostCast(boolean land, boolean targeted) {
        var c = context(BenchSession.Mode.BRIDGE);
        if (!land) card(targeted ? "Mountain" : "Plains", c.payer, ZoneType.Battlefield);
        var spell = card(land ? "Plains" : targeted ? "Lightning Bolt" : "Savannah Lions", c.payer, ZoneType.Hand);
        c.host.fid = spell.getId(); c.host.targetId = c.game.getPlayers().get(1 - seat).getId(); ready(c);
        var state = BenchMenuStateAudit.capture(c.game); var before = rng();
        var selected = c.controller.chooseSpellAbilityToPlay();
        check(selected.size() == 1 && selected.get(0).getHostCard().getId() == spell.getId(), "host-selected actual card identity");
        ownership(c, "host");
        check(before.equals(rng()), "priority selection consumes no policy/chance RNG");
        BenchMenuStateAudit.assertUnchanged(state, c.game); checks++;
        check(c.host.kinds().equals(List.of("priority")), "selection is limited to the host priority action/X answer");
        check(bucket(c, "payManaCost", "unclassified") + bucket(c, "payManaCost", "rules") == 0, "direct payment ask is not an invented public callback");
        check(c.controller.playChosenSpellAbility(selected.get(0)), "selected real action executes");
        check(c.host.kinds().equals(land ? List.of("priority") : targeted ? List.of("priority", "targets", "payment") : List.of("priority", "payment")), "execution binds the selected action to target/payment answers");
        if (targeted) check(bucket(c, "chooseTargetsFor", "unclassified") == 0 && bucket(c, "chooseTargetsFor", "host") == 1, "validated target invocation has its own HOST receipt, separate from priority");
        check(spell.isInZone(land ? ZoneType.Battlefield : ZoneType.Stack), "executed action reaches actual zone");
        check(bucket(c, PRIORITY, "host") == 1 && bucket(c, "playChosenSpellAbility", "rules") == 1, "parent HOST and later execution RULES stay separate");
        if (!land) check(bucket(c, "payManaCost", "rules") >= 1 && bucket(c, "payManaCost", "unclassified") == 0, "nested execution payment retains RULES ownership, including source zero-bills");
        System.out.println("AFTER_EXECUTION " + c.controller.getCounters().toJson());
    }
    private static void pass() {
        var c = context(BenchSession.Mode.BRIDGE); card("Plains", c.payer, ZoneType.Hand); c.host.scenario = "pass"; ready(c);
        var state = BenchMenuStateAudit.capture(c.game); var before = rng();
        check(c.controller.chooseSpellAbilityToPlay() == null, "explicit pass despite legal land"); ownership(c, "host");
        BenchMenuStateAudit.assertUnchanged(state, c.game); checks++;
        check(before.equals(rng()) && c.host.kinds().equals(List.of("priority")), "pass makes no payment/target asks or RNG changes");
    }
    private static void failure(String scenario) {
        var c = context(BenchSession.Mode.BRIDGE); boolean target = scenario.equals("invalid-target");
        var source = card(target ? "Mountain" : "Plains", c.payer, ZoneType.Battlefield);
        var spell = card(target ? "Lightning Bolt" : "Savannah Lions", c.payer, ZoneType.Hand);
        c.host.fid = spell.getId(); c.host.scenario = scenario; ready(c);
        var state = BenchMenuStateAudit.capture(c.game); var before = rng();
        if (scenario.equals("invalid-x")) {
            rejects(() -> c.controller.chooseSpellAbilityToPlay()); ownership(c, "unclassified");
            BenchMenuStateAudit.assertUnchanged(state, c.game); checks++;
            check(before.equals(rng()) && !source.isTapped() && spell.isInZone(ZoneType.Hand), "invalid action/X answer has no execution or RNG side effects");
            return;
        }
        var selected=c.controller.chooseSpellAbilityToPlay(); ownership(c, "host");
        BenchMenuStateAudit.assertUnchanged(state, c.game); checks++;
        check(before.equals(rng()) && !source.isTapped() && spell.isInZone(ZoneType.Hand), "selection has no execution or RNG side effects");
        rejects(() -> c.controller.playChosenSpellAbility(selected.get(0)));
        check(!source.isTapped() && spell.isInZone(ZoneType.Hand) && c.game.getStack().isEmpty(), "failed announcement has no payment or stack receipt");
        if (target) check(bucket(c, "chooseTargetsFor", "unclassified") == 1, "failed execution target callback remains independently unknown");
    }
    private static String signature(List<SpellAbility> abilities) { return abilities == null ? "pass" : abilities.stream().map(a -> a.getHostCard().getName() + "/" + a.isLandAbility()).toList().toString(); }
    private static void stock(BenchSession.Mode mode, String scenario) {
        var c = context(mode); var land = card("Plains", c.payer, ZoneType.Hand); c.host.fid = land.getId(); c.host.scenario = scenario; ready(c);
        // Separate native controller on the identical position: only priority selection,
        // no execution. Reinstall the fixture seed, never production compensation.
        var reference = new PlayerControllerAi(c.game, c.payer, c.payer.getLobbyPlayer());
        var expected = signature(reference.chooseSpellAbilityToPlay()); var expectedRng = rng();
        c.controller.getCounters().reset(); BenchRandomAudit.install(71939);
        var actual = signature(c.controller.chooseSpellAbilityToPlay());
        check(expected.equals(actual) && expectedRng.equals(rng()), "stock result and RNG preserved mode=" + mode + " scenario=" + scenario);
        ownership(c, "stock");
        if (mode != BenchSession.Mode.BRIDGE) check(c.host.asks.isEmpty(), "null/probe makes no host asks");
        if (mode == BenchSession.Mode.NULL_PROBE) check(c.controller.getCounters().toJson().getAsJsonObject("instruments").get("auditPriorityProbe").getAsInt() == 1, "actual null-probe subclass executed production purity probe");
        if (scenario.equals("delegate")) check(c.host.wire.toString(StandardCharsets.UTF_8).contains("\"type\":\"delegated\""), "actual stock fallback emits existing delegated echo");
    }
    private static void pendingFailure() {
        var c = context(BenchSession.Mode.BRIDGE); var land = card("Plains", c.payer, ZoneType.Hand); c.host.fid = land.getId(); ready(c);
        c.controller.chooseSpellAbilityToPlay(); rejects(() -> c.controller.chooseSpellAbilityToPlay());
        check(bucket(c, PRIORITY, "host") == 1 && bucket(c, PRIORITY, "unclassified") == 1 && c.host.asks.size() == 1, "failed second invocation does not relabel first validated host selection");
    }
    private static void staleAction() {
        var c = context(BenchSession.Mode.BRIDGE); var land = card("Plains", c.payer, ZoneType.Hand);
        c.host.fid = land.getId(); c.host.scenario = "stale-action"; c.host.removeBeforeReturn = land; ready(c);
        check(c.controller.chooseSpellAbilityToPlay() == null && land.isInZone(ZoneType.Graveyard), "fixture action moved after menu; actual stale-action stock fallback passes");
        ownership(c, "stock");
        check(c.controller.getCounters().toJson().getAsJsonObject("delegatedRefused").get(PRIORITY).getAsInt() == 1, "stale action still reported as refusal, not a valid host choice");
    }
    private static void eof() {
        var c = context(BenchSession.Mode.BRIDGE); card("Plains", c.payer, ZoneType.Hand); c.host.scenario = "eof"; ready(c);
        check(c.controller.chooseSpellAbilityToPlay().get(0).isLandAbility(), "host EOF takes existing actual Default fallback"); ownership(c, "stock");
        check(c.session.getChannel().isClosed(), "EOF closes channel");
        c.controller.chooseSpellAbilityToPlay();
        check(bucket(c, PRIORITY, "stock") == 2 && bucket(c, PRIORITY, "host") == 0, "already closed channel remains actual stock path");
    }
    private static void unsupportedMenu(BenchSession.Mode mode) {
        var c = context(mode); card("Gitaxian Probe", c.payer, ZoneType.Hand); c.host.scenario = "unsupported-menu"; ready(c);
        var before = rng(); var state = BenchMenuStateAudit.capture(c.game);
        rejects(() -> c.controller.chooseSpellAbilityToPlay());
        if (mode == BenchSession.Mode.BRIDGE) ownership(c, "unclassified");
        else check(bucket(c, PRIORITY, "unclassified") == 0 && c.controller.getCounters().toJson().get("totalCalls").getAsInt() == 0,
                "actual NULL_PROBE wrapper fails before entering primary; no fabricated invocation");
        check(c.host.asks.isEmpty() && before.equals(rng()), "unsupported menu never delegates or consumes RNG");
        BenchMenuStateAudit.assertUnchanged(state, c.game); checks++;
    }
    private static void nonliveCopy() {
        var c = context(BenchSession.Mode.BRIDGE); card("Plains", c.payer, ZoneType.Hand); c.host.scenario = "nonlive-copy"; ready(c);
        var other = context(BenchSession.Mode.NULL); c.session.setLiveGame(other.game);
        check(c.controller.chooseSpellAbilityToPlay().get(0).isLandAbility(), "nonlive search-copy controller retains stock behavior");
        check(c.controller.getCounters().toJson().get("totalCalls").getAsInt() == 0 && c.host.asks.isEmpty(), "nonlive search-copy selection adds no live invocation or host RPC");
    }
    private static void malformedChoices(boolean characterize) {
        for (String raw : List.of("\"1\"", "1.75", "true", "4294967297", "4294967296", "2147483648", "[1]", "null", "{}", "-1", "1e1000", "0.75", "1.0000000000000001")) {
            var c = context(BenchSession.Mode.BRIDGE); var land = card("Plains", c.payer, ZoneType.Hand);
            c.host.fid = land.getId(); c.host.rawChoice = raw; c.host.scenario = "malformed-choice:" + raw; ready(c);
            var reference = new PlayerControllerAi(c.game, c.payer, c.payer.getLobbyPlayer());
            var expected = signature(reference.chooseSpellAbilityToPlay()); var expectedRng = rng();
            c.controller.getCounters().reset(); BenchRandomAudit.install(71939);
            var result = c.controller.chooseSpellAbilityToPlay();
            System.out.println("PRIORITY_INDEX_CASE " + raw + " result=" + signature(result) + " ledger=" + c.controller.getCounters().toJson());
            if (!characterize) {
                ownership(c, "stock");
                check(result != null && result.get(0).isLandAbility(), "malformed choice falls back to actual Default land choice, never coerced pass");
                check(c.controller.getCounters().toJson().getAsJsonObject("delegatedRefused").get(PRIORITY).getAsInt() == 1, "malformed index explicitly refused");
                check(c.host.kinds().equals(List.of("priority")), "malformed priority index never starts host targets/payment");
                check(expected.equals(signature(result)) && expectedRng.equals(rng()), "refused index preserves actual Default result and RNG, not an assumed RNG no-op");
            }
            check(land.isInZone(ZoneType.Hand) && !land.isTapped(), "malformed-index fixture does not execute the selected action");
        }
    }
    private static void exactNumericIndices() {
        for (String raw : List.of("1.0", "1e0", "0.0", "0e2")) {
            var c = context(BenchSession.Mode.BRIDGE); var land = card("Plains", c.payer, ZoneType.Hand);
            c.host.fid = land.getId(); c.host.rawChoice = raw; c.host.scenario = "exact-numeric-index:" + raw; ready(c);
            var before = rng(); var result = c.controller.chooseSpellAbilityToPlay();
            ownership(c, "host");
            check(raw.startsWith("0") ? result == null : result != null && result.get(0).isLandAbility(), "exact integral numeric notation keeps intended choice");
            check(before.equals(rng()) && c.controller.getCounters().toJson().get("totalDelegatedRefused").getAsInt() == 0, "valid integral notation has no fallback or RNG draw");
        }
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> switch (method.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "priority-ownership-fixture";
                default -> throw new AssertionError("Unexpected GUI call " + method.getName());
            }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            for (seat = 0; seat < 2; seat++) {
                System.out.println("ACTOR_SEAT " + seat); BenchRandomAudit.install(71939);
                if (args.length > 1 && args[1].equals("--characterize-coercion")) { malformedChoices(true); continue; }
                hostCast(false, false); hostCast(true, false); hostCast(false, true); pass();
                for (String failure : List.of("invalid-x", "invalid-target", "invalid-payment")) failure(failure);
                stock(BenchSession.Mode.NULL, "null"); stock(BenchSession.Mode.NULL_PROBE, "null-probe");
                stock(BenchSession.Mode.BRIDGE, "delegate"); stock(BenchSession.Mode.BRIDGE, "invalid-choice"); pendingFailure();
                staleAction(); eof(); unsupportedMenu(BenchSession.Mode.BRIDGE); unsupportedMenu(BenchSession.Mode.NULL_PROBE); nonliveCopy();
                malformedChoices(false);
                exactNumericIndices();
            }
            System.out.println("PASS " + checks + " priority ownership checks; development callback fixtures, NOT complete-control or strength certification"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
