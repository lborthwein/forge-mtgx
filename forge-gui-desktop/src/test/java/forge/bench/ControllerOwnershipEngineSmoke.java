package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.ai.AiCardMemory.MemorySet;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual controller callback fixtures, no match, no production policy substitute. */
public final class ControllerOwnershipEngineSmoke {
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; System.out.println("PASS " + label); }
    private static void rejects(Runnable action) { try { action.run(); } catch (RulesCostFeasibility.Unsupported expected) { checks++; return; } throw new AssertionError("Expected explicit failure"); }
    private static Card card(String name, Player p, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), p);
        card.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static int bucket(PlayerControllerBridge controller, String method, String owner) {
        var methods = controller.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
        return methods.has(method) ? methods.getAsJsonObject(method).get(owner).getAsInt() : 0;
    }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int fid; boolean malformedPayment; byte[] pending = new byte[0]; int offset; int answered;
        private void prepare() {
            if (offset < pending.length) return;
            var asks = wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o -> o.has("type") && o.get("type").getAsString().equals("ask")).toList();
            var ask = asks.get(asks.size() - 1); int id = ask.get("id").getAsInt();
            if (id <= answered) throw new AssertionError("Unexpected repeated host read");
            var answer = new JsonObject(); answer.addProperty("type", "answer"); answer.addProperty("id", id);
            switch (ask.get("kind").getAsString()) {
                case "priority" -> {
                    var menu = ask.getAsJsonArray("menu"); int choice = -1;
                    for (int i = 1; i < menu.size(); i++) if (menu.get(i).getAsJsonObject().get("fid").getAsInt() == fid) choice = i;
                    if (choice < 1) throw new AssertionError("Fixture action not offered"); answer.addProperty("choice", choice);
                }
                case "payment" -> {
                    var option = ask.getAsJsonArray("sourceOptions").get(0).getAsJsonObject(); String source = option.get("id").getAsString();
                    answer.addProperty("paymentVersion", malformedPayment ? "wrong-version" : RulesPaymentDomain.PAYMENT_VERSION); answer.addProperty("lifePaid", 0);
                    answer.addProperty("x", 0); // v5 requires an explicit echo, including non-X casts.
                    var order = new JsonArray(); order.add(source); answer.add("sourceOrder", order);
                    var spend = new JsonArray(); var token = new JsonObject(); token.addProperty("token", source + ":0"); token.addProperty("shardIndex", 0); spend.add(token); answer.add("spend", spend);
                }
                default -> throw new AssertionError("Unexpected host decision " + ask);
            }
            pending = (answer + "\n").getBytes(StandardCharsets.UTF_8); offset = 0; answered = id;
        }
        @Override public int read() { prepare(); return pending[offset++] & 255; }
        @Override public int read(byte[] data, int start, int length) { if (length == 0) return 0; prepare(); int n = Math.min(length, pending.length - offset); System.arraycopy(pending, offset, data, start, n); offset += n; return n; }
    }
    private record Context(Game game, Player payer, PlayerControllerBridge controller, Host host) {}
    private static int fixtureSeat;
    private static Context context() {
        return context(BenchSession.Mode.BRIDGE);
    }
    private static Context context(BenchSession.Mode mode) {
        var host = new Host(); var channel = new JsonRpcChannel(host, host.wire); var session = new BenchSession(channel);
        var lobby = new LobbyPlayerBridge("Payer", null, session, mode, fixtureSeat); lobby.setAiProfile("Default");
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var opponent = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1 - fixtureSeat, 0, null, "Default"));
        var game = new Match(new GameRules(GameType.Constructed), fixtureSeat == 0 ? List.of(own, opponent) : List.of(opponent, own), "Ownership fixture").createGame();
        game.setAge(GameStage.Play); var payer = game.getPlayers().get(fixtureSeat); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, payer); session.setLiveGame(game);
        return new Context(game, payer, (PlayerControllerBridge) payer.getController(), host);
    }
    private static void execution(boolean land, boolean wrongIdentity) {
        var c = context(); if (!land) card("Plains", c.payer, ZoneType.Battlefield);
        var target = card(land ? "Plains" : "Savannah Lions", c.payer, ZoneType.Hand); c.host.fid = target.getId();
        c.game.getAction().checkStateEffects(true); c.controller.getCounters().reset();
        var chosen = c.controller.chooseSpellAbilityToPlay().get(0);
        if (wrongIdentity) {
            var other = card("Savannah Lions", c.payer, ZoneType.Hand).getFirstSpellAbility();
            rejects(() -> c.controller.playChosenSpellAbility(other));
            check(bucket(c.controller, "playChosenSpellAbility", "unclassified") == 1 && bucket(c.controller, "playChosenSpellAbility", "rules") == 0, "failed action identity remains unclassified");
        } else {
            check(c.controller.playChosenSpellAbility(chosen), "real previously selected action executes");
            check(target.isInZone(land ? ZoneType.Battlefield : ZoneType.Stack), "actual expected zone transition land=" + land);
            check(bucket(c.controller, "playChosenSpellAbility", "rules") == 1 && bucket(c.controller, "playChosenSpellAbility", "host") == 0, "execution is RULES, not a newly made HOST decision");
            if (!land) check(bucket(c.controller, "payManaCost", "rules") >= 1 && bucket(c.controller, "payManaCost", "unclassified") == 0, "exact payment callback classified after verified spend");
            check(bucket(c.controller, "chooseSpellAbilityToPlay", "host") == 1 && bucket(c.controller, "chooseSpellAbilityToPlay", "unclassified") == 0,
                    "validated prior choice HOST is separate from RULES execution");
        }
        System.out.println("OWNERSHIP " + c.controller.getCounters().toJson());
    }
    private static void paymentFailure() {
        var c = context(); card("Plains", c.payer, ZoneType.Battlefield);
        var target = card("Savannah Lions", c.payer, ZoneType.Hand); c.host.fid = target.getId(); c.host.malformedPayment = true; c.game.getAction().checkStateEffects(true);
        var selected = c.controller.chooseSpellAbilityToPlay().get(0);
        check(c.host.wire.toString(StandardCharsets.UTF_8).contains("\"kind\":\"priority\""), "selection obtains only the action/X answer before payment");
        rejects(() -> c.controller.playChosenSpellAbility(selected));
        check(bucket(c.controller, "payManaCost", "rules") == 0, "malformed payment cannot acquire a RULES payment receipt");
        check(!c.payer.getCardsIn(ZoneType.Battlefield).get(0).isTapped() && c.game.getStack().isEmpty() && target.isInZone(ZoneType.Hand), "malformed payment leaves mana and stack unchanged");
        rejects(() -> c.controller.chooseSpellAbilityToPlay());
    }
    private static void notifications() {
        for (var mode : BenchSession.Mode.values()) {
            var c = context(mode);
            c.controller.getCounters().reset();
            var before = BenchMenuStateAudit.capture(c.game);
            var rng = ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot();
            c.controller.notifyOfValue(null, null, "fixture");
            c.controller.autoPassCancel(); c.controller.awaitNextInput(); c.controller.cancelAwaitNextInput();
            c.controller.revealAnte("fixture", com.google.common.collect.ArrayListMultimap.create());
            c.controller.revealAISkipCards("fixture", Map.of()); c.controller.revealUnsupported(Map.of());
            BenchMenuStateAudit.assertUnchanged(before, c.game);
            check(rng.equals(((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot()), "notifications preserve state and RNG mode=" + mode);
            for (String method : List.of("notifyOfValue", "autoPassCancel", "awaitNextInput", "cancelAwaitNextInput", "revealAnte", "revealAISkipCards", "revealUnsupported")) {
                check(bucket(c.controller, method, "rules") == 1 && bucket(c.controller, method, "unclassified") == 0
                        && bucket(c.controller, method, "host") == 0 && bucket(c.controller, method, "forced") == 0
                        && bucket(c.controller, method, "stock") == 0, "exact no-op notification RULES " + method + " mode=" + mode);
            }
            check(c.host.wire.size() == 0, "no-op notifications do not request host decisions mode=" + mode);
        }
    }
    private static void inheritedCallbacks() {
        var c = context(); var stock = new PlayerControllerAi(c.game, c.payer, c.payer.getLobbyPlayer());
        var host = card("Savannah Lions", c.payer, ZoneType.Hand);
        var sa = AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1", host); sa.setActivatingPlayer(c.payer);
        BenchRandomAudit.install(71931); boolean expected = stock.chooseBinary(sa, "fixture", PlayerController.BinaryChoiceType.HeadsOrTails, Map.of());
        var expectedRng = ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot();
        BenchRandomAudit.install(71931); boolean actual = c.controller.chooseBinary(sa, "fixture", PlayerController.BinaryChoiceType.HeadsOrTails, Map.of());
        check(actual == expected && expectedRng.equals(((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot()), "formerly inherited binary preserves actual Default result and RNG transcript");
        check(c.controller.chooseNumber(sa, "fixture", 1, 4, Map.of()) == stock.chooseNumber(sa, "fixture", 1, 4, Map.of()), "formerly inherited number preserves Default choice");
        check(c.controller.acceptsDrawOffer() == stock.acceptsDrawOffer(), "draw-offer behavior unchanged, not falsely forced");
        var cards = new CardCollection(host); check(c.controller.cheatShuffle(cards) == stock.cheatShuffle(cards), "small-list shuffle keeps original object identity");
        var deck = new Deck("Control fixture"); c.controller.setupAutoProfile(deck); stock.setupAutoProfile(deck);
        check(!c.controller.pilotsNonAggroDeck() && stock.pilotsNonAggroDeck(), "live host no longer configures Default's combat profile");
        check(c.controller.complainCardsCantPlayWell(deck).isEmpty(), "live host has no Default card warnings");
        c.controller.getAi().getCardMemory().rememberCard(host, MemorySet.MANDATORY_ATTACKERS);
        c.controller.resetAtEndOfTurn(); check(!c.controller.getAi().getCardMemory().isRememberedCardByName(host.getName(), MemorySet.MANDATORY_ATTACKERS), "actual AI-memory reset retained");
        check(bucket(c.controller, "cheatShuffle", "rules") == 1
                && bucket(c.controller, "cheatShuffle", "unclassified") == 0
                && bucket(c.controller, "cheatShuffle", "stock") == 0
                && bucket(c.controller, "cheatShuffle", "host") == 0
                && bucket(c.controller, "cheatShuffle", "forced") == 0,
                "verified native shuffle completion is rules-owned, never an AI decision");
        for (String method : List.of("chooseBinary", "chooseNumber", "acceptsDrawOffer"))
            check(bucket(c.controller, method, "stock") >= 1
                    && bucket(c.controller, method, "unclassified") == 0
                    && bucket(c.controller, method, "forced") == 0
                    && bucket(c.controller, method, "host") == 0,
                    "inherited callback is stock-owned, never host or forced: " + method);
        for (String method : List.of("setupAutoProfile", "complainCardsCantPlayWell", "resetAtEndOfTurn")) {
            check(bucket(c.controller,method,"rules")==1,"verified lifecycle operation is rules-owned: "+method);
            for(String owner:List.of("host","forced","stock","unclassified"))
                check(bucket(c.controller,method,owner)==0,"lifecycle never invokes a stock decision: "+method+"/"+owner);
        }
        check(c.controller.getCounters().toJson().getAsJsonObject("calls").get("setupAutoProfile").getAsInt()==1,
                "complaint no longer invokes nested Default profile setup");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "controller-ownership-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            for (fixtureSeat = 0; fixtureSeat < 2; fixtureSeat++) {
                System.out.println("ACTOR_SEAT " + fixtureSeat);
                BenchRandomAudit.install(71930); execution(false, false); execution(true, false); execution(false, true); paymentFailure(); inheritedCallbacks(); notifications();
            }
            System.out.println("PASS " + checks + " real callback checks; remaining decisions UNCLASSIFIED, NOT complete-control certification"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
