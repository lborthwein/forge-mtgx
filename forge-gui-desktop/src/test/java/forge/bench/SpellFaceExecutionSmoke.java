package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.card.CardStateName;
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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One controlled cast per invocation, through the production bridge's actual announcement path. */
public final class SpellFaceExecutionSmoke {
    private static PrintStream protocolOutput;
    private static int checks;
    private static JsonObject noStackDiagnostic;
    private static JsonObject targetDiagnostic;
    private static JsonObject targetCallbackDiagnostic;
    private static JsonObject targetRequest;
    private static JsonObject targetAnswer;
    private record Scenario(String id, String card, CardStateName state, String face, List<String> lands, boolean target) {}
    private static Scenario scenario(String id) {
        return switch (id) {
            case "petty-theft" -> new Scenario(id, "Brazen Borrower", CardStateName.Secondary, "Petty Theft", List.of("Island", "Island"), true);
            case "stomp" -> new Scenario(id, "Bonecrusher Giant", CardStateName.Secondary, "Stomp", List.of("Mountain", "Mountain"), true);
            case "harnfel" -> new Scenario(id, "Birgi, God of Storytelling", CardStateName.Backside, "Harnfel, Horn of Bounty", List.of("Mountain", "Mountain", "Mountain", "Mountain", "Mountain"), false);
            case "tibalt" -> new Scenario(id, "Valki, God of Lies", CardStateName.Backside, "Tibalt, Cosmic Impostor", List.of("Swamp", "Swamp", "Swamp", "Swamp", "Swamp", "Swamp", "Mountain"), false);
            default -> throw new IllegalArgumentException("Unknown case " + id);
        };
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    private static List<JsonObject> rows(String text) {
        return text.lines().filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
    }
    private static final class TrackingInput extends FilterInputStream {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        TrackingInput(InputStream in) { super(in); }
        @Override public int read() throws IOException { int b = in.read(); if (b >= 0) received.write(b); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); if (n > 0) received.write(b, off, n); return n; }
    }
    private static final class ScriptedHost extends InputStream {
        final ByteArrayOutputStream wire; java.util.function.Function<JsonObject, JsonObject> responder;
        byte[] pending = new byte[0]; int at; int answered;
        ScriptedHost(ByteArrayOutputStream wire) { this.wire = wire; }
        private void prepare() {
            if (at < pending.length) return;
            var asks = rows(wire.toString(StandardCharsets.UTF_8)).stream().filter(r -> "ask".equals(r.get("type").getAsString())).toList();
            if (asks.isEmpty()) throw new AssertionError("host read before emitted ask");
            JsonObject ask = asks.get(asks.size() - 1); int id = ask.get("id").getAsInt();
            if (id <= answered) throw new AssertionError("repeated host read");
            JsonObject answer = responder.apply(ask.deepCopy()); answer.addProperty("type", "answer"); answer.addProperty("id", id);
            pending = (answer + "\n").getBytes(StandardCharsets.UTF_8); at = 0; answered = id;
        }
        @Override public int read() { prepare(); return pending[at++] & 255; }
        @Override public int read(byte[] b, int off, int len) { if (len == 0) return 0; prepare(); int n = Math.min(len, pending.length - at); System.arraycopy(pending, at, b, off, n); at += n; return n; }
    }
    private static Card card(String name, Player owner, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        Card c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name), owner);
        c.setGameTimestamp(owner.getGame().getNextTimestamp()); owner.getZone(zone).add(c); c.setSickness(false); return c;
    }
    private static Card byId(Player p, ZoneType zone, int id) {
        return p.getCardsIn(zone).stream().filter(c -> c.getId() == id).findFirst().orElseThrow();
    }
    private static JsonObject abilityIdentity(SpellAbility root) {
        JsonObject out = new JsonObject(); JsonArray chain = new JsonArray();
        for (SpellAbility ability = root; ability != null; ability = ability.getSubAbility()) {
            JsonObject part = new JsonObject(); part.addProperty("identity", System.identityHashCode(ability));
            part.addProperty("abilityId", ability.getId()); part.addProperty("hostId", ability.getHostCard().getId());
            part.addProperty("host", ability.getHostCard().getName()); part.addProperty("state", ability.getCardStateName().name());
            part.addProperty("usesTargeting", ability.usesTargeting()); part.addProperty("targetCards", String.valueOf(ability.getTargets().getTargetCards()));
            part.addProperty("targetPlayers", String.valueOf(ability.getTargets().getTargetPlayers())); part.addProperty("targetCount", ability.getTargets().size()); chain.add(part);
        }
        out.add("chain", chain); return out;
    }
    private static boolean chainTargets(SpellAbility root, int cardId) {
        for (SpellAbility ability = root; ability != null; ability = ability.getSubAbility())
            if (ability.getTargets().getTargetCards().stream().anyMatch(card -> card.getId() == cardId)) return true;
        return false;
    }
    /** Observes the exact engine callback before the immutable production guard runs. */
    private static final class DiagnosticBridge extends PlayerControllerBridge {
        DiagnosticBridge(Game game, Player player, LobbyPlayerBridge lobby, BenchSession session, int seat, CallCounter counters) {
            super(game, player, lobby, session, BenchSession.Mode.BRIDGE, seat, counters);
        }
        @Override public void playSpellAbilityNoStack(SpellAbility ability, boolean mayChooseNewTargets) {
            JsonObject d = new JsonObject();
            d.addProperty("callback", "playSpellAbilityNoStack"); d.addProperty("source", String.valueOf(ability.getHostCard()));
            d.addProperty("sourceId", ability.getHostCard() == null ? -1 : ability.getHostCard().getId());
            d.addProperty("actor", ability.getActivatingPlayer() == null ? -1 : ability.getActivatingPlayer().getId());
            d.addProperty("mayChooseNewTargets", mayChooseNewTargets); d.addProperty("mandatory", ability.isMandatory());
            d.addProperty("optionalTrigger", ability.isOptionalTrigger()); d.addProperty("isTrigger", ability.isTrigger());
            d.addProperty("isSpell", ability.isSpell()); d.addProperty("isAbility", ability.isAbility()); d.addProperty("api", String.valueOf(ability.getApi()));
            d.addProperty("payCosts", String.valueOf(ability.getPayCosts())); d.add("params", new Gson().toJsonTree(ability.getMapParams()));
            noStackDiagnostic = d;
            super.playSpellAbilityNoStack(ability, mayChooseNewTargets);
        }
        @Override public boolean chooseTargetsFor(SpellAbility ability) {
            JsonObject d = new JsonObject(); d.add("before", abilityIdentity(ability)); boolean result = super.chooseTargetsFor(ability);
            d.addProperty("result", result); d.add("after", abilityIdentity(ability)); targetCallbackDiagnostic = d; return result;
        }
        @Override public boolean playChosenSpellAbility(SpellAbility ability) {
            JsonObject d = new JsonObject(); d.add("beforeExecution", abilityIdentity(ability)); boolean result = super.playChosenSpellAbility(ability);
            d.addProperty("result", result); d.add("afterExecution", abilityIdentity(ability)); targetDiagnostic = d; return result;
        }
    }
    /** Exact, deliberately fixed witness; it never calls an AI payment path. */
    private static JsonObject payment(JsonObject ask, Scenario scenario) {
        JsonObject answer = new JsonObject(); answer.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION); answer.addProperty("x", 0); answer.addProperty("lifePaid", 0);
        Map<String, ArrayDeque<JsonObject>> byColor = new HashMap<>();
        for (JsonElement raw : ask.getAsJsonArray("sourceOptions")) {
            JsonObject option = raw.getAsJsonObject(); String color = option.getAsJsonArray("output").get(0).getAsString();
            byColor.computeIfAbsent(color, ignored -> new ArrayDeque<>()).add(option);
        }
        JsonArray order = new JsonArray(), spend = new JsonArray();
        boolean tibaltRedFirst = scenario.id().equals("tibalt");
        List<Integer> shardOrder = new ArrayList<>();
        for (int i = 0; i < ask.getAsJsonObject("cost").getAsJsonArray("shards").size(); i++) shardOrder.add(i);
        if (tibaltRedFirst) shardOrder.sort(Comparator.comparingInt(i -> "RED".equals(ask.getAsJsonObject("cost").getAsJsonArray("shards").get(i).getAsString()) ? 0 : 1));
        for (int shard : shardOrder) {
            String shardName = ask.getAsJsonObject("cost").getAsJsonArray("shards").get(shard).getAsString();
            String color = switch (shardName) { case "WHITE" -> "W"; case "BLUE" -> "U"; case "BLACK" -> "B"; case "RED" -> "R"; case "GREEN" -> "G"; default -> null; };
            JsonObject option = color == null ? byColor.values().stream().filter(q -> !q.isEmpty()).findFirst().map(ArrayDeque::pollFirst).orElse(null)
                    : byColor.getOrDefault(color, new ArrayDeque<>()).pollFirst();
            if (option == null) throw new AssertionError("fixed fixture cannot pay shard " + color + " in " + ask);
            String id = option.get("id").getAsString(); order.add(id);
            JsonObject allocation = new JsonObject(); allocation.addProperty("token", id + ":0"); allocation.addProperty("shardIndex", shard); spend.add(allocation);
        }
        answer.add("sourceOrder", order); answer.add("spend", spend); return answer;
    }
    private static JsonObject response(JsonObject ask, Scenario s, Card source, Card bear) {
        String kind = ask.get("kind").getAsString(); JsonObject out = new JsonObject();
        if ("priority".equals(kind)) {
            JsonArray menu = ask.getAsJsonArray("menu"); int choice = -1;
            for (int i = 1; i < menu.size(); i++) {
                JsonObject option = menu.get(i).getAsJsonObject();
                if (option.get("fid").getAsInt() != source.getId() || !option.has("spellFace")) continue;
                JsonObject face = option.getAsJsonObject("spellFace");
                if ("host-spell-face-v1".equals(face.get("version").getAsString()) && s.state().name().equals(face.get("state").getAsString())) {
                    if (choice >= 0) throw new AssertionError("ambiguous actual face menu " + menu); choice = i;
                }
            }
            if (choice < 1) throw new AssertionError("actual priority menu omitted intended face " + s);
            out.addProperty("choice", choice); return out;
        }
        if ("targets".equals(kind)) {
            if (!s.target()) throw new AssertionError("unexpected target request " + ask);
            JsonArray choices = new JsonArray(); JsonObject target = new JsonObject(); target.addProperty("kind", "card"); target.addProperty("id", bear.getId()); choices.add(target); out.add("choices", choices);
            targetRequest = ask.deepCopy(); targetAnswer = out.deepCopy(); return out;
        }
        if ("payment".equals(kind)) return payment(ask, s);
        throw new AssertionError("unexpected host surface " + ask);
    }
    private static JsonObject run(Scenario s, int seat, boolean external) throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream(); ScriptedHost scripted = new ScriptedHost(wire);
        TrackingInput input = new TrackingInput(external ? System.in : scripted);
        OutputStream output = external ? new OutputStream() {
            @Override public void write(int b) { wire.write(b); protocolOutput.write(b); }
            @Override public void write(byte[] b, int off, int n) { wire.write(b, off, n); protocolOutput.write(b, off, n); }
            @Override public void flush() { protocolOutput.flush(); }
        } : wire;
        BenchSession session = new BenchSession(new JsonRpcChannel(input, output));
        LobbyPlayerBridge lobby = new LobbyPlayerBridge("Face Actor", null, session, BenchSession.Mode.BRIDGE, seat); lobby.setAiProfile("Default");
        RegisteredPlayer own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        RegisteredPlayer foe = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Face Foe", 1 - seat, 0, null, "Default"));
        Game game = new Match(new GameRules(GameType.Constructed), seat == 0 ? List.of(own, foe) : List.of(foe, own), "Spell face execution fixture").createGame();
        Player actor = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat); session.setLiveGame(game);
        actor.dangerouslySetController(new DiagnosticBridge(game, actor, lobby, session, seat,
                ((PlayerControllerBridge) actor.getController()).getCounters()));
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, actor);
        for (String land : s.lands()) card(land, actor, ZoneType.Battlefield);
        Card source = card(s.card(), actor, ZoneType.Hand); Card bear = s.target() ? card("Grizzly Bears", opponent, ZoneType.Battlefield) : null;
        game.getAction().checkStateEffects(true); game.getPhaseHandler().setPriority(actor);
        scripted.responder = ask -> response(ask, s, source, bear);
        BenchRandomAudit.install(91600 + seat); BenchActionAudit.beginGame(game, "face-" + s.id() + "-" + seat);
        game.subscribeToEvents(new BenchMain.EventEmitter(new JsonRpcChannel(input, output), "face", game));
        ByteArrayOutputStream audit = new ByteArrayOutputStream(); PrintStream prior = System.err; SpellAbility selected; int actualManaSpent = -1;
        JsonObject manaByColor = new JsonObject(); int initialLife=actor.getLife();
        try {
            System.setErr(new PrintStream(audit, true, StandardCharsets.UTF_8));
            List<SpellAbility> choices = actor.getController().chooseSpellAbilityToPlay();
            check(choices != null && choices.size() == 1, "one actual priority selection"); selected = choices.get(0);
            check(selected.getHostCard().getId() == source.getId() && selected.getActivatingPlayer() == actor && selected.getCardStateName() == s.state(), "selected actual face/actor");
            check(selected.getTargets().isEmpty(), "priority choice has no preselected target");
            check(actor.getController().playChosenSpellAbility(selected), "bridge executes exact host announcement");
            check(game.getStack().size() == 1, "actual face reaches stack");
            SpellAbility stacked = game.getStack().peekAbility();
            check(stacked.getCardStateName() == s.state() && stacked.getHostCard().getName().equals(s.face()), "stack retains selected face identity");
            check(stacked.getPayingMana().size() == s.lands().size() && actor.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> s.lands().contains(c.getName())).allMatch(Card::isTapped), "actual paid mana and fixed basic sources before resolution");
            actualManaSpent = stacked.getPayingMana().size();
            for(var mana:stacked.getPayingMana()) {
                String color=forge.card.MagicColor.toShortString(mana.getColor());
                manaByColor.addProperty(color,manaByColor.has(color)?manaByColor.get(color).getAsInt()+1:1);
            }
            if (s.target()) check(chainTargets(stacked, bear.getId()), "actual host target reaches stack chain expected=" + bear.getId() + " stack=" + abilityIdentity(stacked) + " execution=" + targetDiagnostic + " targetCallback=" + targetCallbackDiagnostic + " targetRequest=" + targetRequest + " targetAnswer=" + targetAnswer);
            try { game.getStack().resolveStack(); }
            catch (Throwable failure) {
                prior.println("FACE_EXECUTION_NOSTACK_DIAGNOSTIC " + noStackDiagnostic);
                throw failure;
            }
            game.getAction().checkStateEffects(true);
            BenchActionAudit.finishGame(game, session);
        } catch (Throwable failure) {
            prior.print(audit.toString(StandardCharsets.UTF_8));
            throw failure;
        } finally { System.setErr(prior); }
        String privateLog = audit.toString(StandardCharsets.UTF_8);
        check(!privateLog.contains("BENCH_INTEGRITY_"), "no action integrity failure"); check(session.integrityFailure(game) == null, "session has no integrity failure");
        List<JsonObject> receipts = privateLog.lines().filter(l -> l.startsWith("[bench-action] ")).map(l -> JsonParser.parseString(l.substring(15)).getAsJsonObject()).toList();
        JsonObject summary = receipts.stream().filter(r -> "summary".equals(r.get("kind").getAsString())).findFirst().orElseThrow();
        check(summary.get("chosen").getAsInt() == 1 && summary.get("selected").getAsInt() == 1 && summary.get("observed").getAsInt() == 1 && summary.get("mismatches").getAsInt() == 0, "one chosen/selected/observed receipt without mismatch");
        JsonObject choiceReceipt = receipts.stream().filter(r -> "priority-choice-not-announced".equals(r.get("kind").getAsString())).findFirst().orElseThrow();
        JsonObject selectedReceipt = receipts.stream().filter(r -> "selected-not-executed".equals(r.get("kind").getAsString())).findFirst().orElseThrow();
        JsonObject stackReceipt = receipts.stream().filter(r -> "engine-stack-add".equals(r.get("kind").getAsString()) && r.has("request")).findFirst().orElseThrow();
        JsonObject chosenFace = choiceReceipt.getAsJsonObject("basis").getAsJsonObject("spellFace");
        check(chosenFace.get("state").getAsString().equals(s.state().name())
                && chosenFace.equals(selectedReceipt.getAsJsonObject("selected").getAsJsonObject("spellFace"))
                && chosenFace.equals(stackReceipt.getAsJsonObject("actual").getAsJsonObject("spellFace")), "face bound across actual choice, announcement and event receipts");
        Card resolved; String resolvedState;
        if (s.id().equals("petty-theft")) {
            resolved = byId(actor, ZoneType.Exile, source.getId()); check(resolved.getCurrentStateName() == CardStateName.Original && resolved.isOnAdventure() && !resolved.getMayPlay().isEmpty(), "Petty Theft exiles original with Adventure permission");
            check(byId(opponent, ZoneType.Hand, bear.getId()) != null, "Petty Theft returns Bear to hand"); resolvedState = "Original-onAdventure";
        } else if (s.id().equals("stomp")) {
            resolved = byId(actor, ZoneType.Exile, source.getId()); check(resolved.getCurrentStateName() == CardStateName.Original && resolved.isOnAdventure() && !resolved.getMayPlay().isEmpty(), "Stomp exiles original with Adventure permission");
            check(byId(opponent, ZoneType.Graveyard, bear.getId()) != null, "Stomp kills Bear"); resolvedState = "Original-onAdventure";
        } else {
            resolved = byId(actor, ZoneType.Battlefield, source.getId()); check(resolved.getCurrentStateName() == CardStateName.Backside && resolved.getName().equals(s.face()), "back face enters battlefield");
            if (s.id().equals("tibalt")) check(resolved.getCounters(CounterEnumType.LOYALTY) == 5 && actor.getCardsIn(ZoneType.Command).stream().anyMatch(c -> c.getName().contains("Emblem — Tibalt, Cosmic Impostor")), "Tibalt has loyalty 5 and actual emblem");
            resolvedState = "Backside";
        }
        List<JsonObject> asks = rows(wire.toString(StandardCharsets.UTF_8)).stream().filter(r -> "ask".equals(r.get("type").getAsString())).toList();
        List<JsonObject> answers = rows(input.received.toString(StandardCharsets.UTF_8));
        check(asks.size() == (s.target() ? 3 : 2) && answers.size() == asks.size(), "exact bridge roundtrip surfaces");
        JsonObject paymentAsk = asks.stream().filter(a -> "payment".equals(a.get("kind").getAsString())).findFirst().orElseThrow();
        JsonObject paymentAnswer = answers.stream().filter(a -> a.has("paymentVersion")).findFirst().orElseThrow();
        JsonObject result = new JsonObject(); result.addProperty("type", "fixture-result"); result.addProperty("case", s.id()); result.addProperty("seat", seat); result.addProperty("passed", true);
        result.addProperty("actualBridgeRoundtrip", external); result.addProperty("requestedState", s.state().name()); result.addProperty("stackState", s.state().name()); result.addProperty("resolvedState", resolvedState);
        result.addProperty("sourceZone", resolved.getZone().getZoneType().name()); result.addProperty("targetZone", s.target() ? (s.id().equals("petty-theft") ? "Hand" : "Graveyard") : "none");
        result.addProperty("sourceName",resolved.getName());result.addProperty("adventureReturn",resolved.isOnAdventure()&&!resolved.getMayPlay().isEmpty());
        result.addProperty("loyalty",resolved.getCounters(CounterEnumType.LOYALTY));
        result.addProperty("emblemCount",actor.getCardsIn(ZoneType.Command).stream().filter(Card::isEmblem).count());
        result.add("manaByColor",manaByColor);
        result.addProperty("manaSpent", actualManaSpent); result.addProperty("lifePaid", initialLife-actor.getLife()); result.add("receiptSummary", summary); result.add("paymentRequest", paymentAsk); result.add("paymentAnswer", paymentAnswer); result.add("receipts", new Gson().toJsonTree(receipts));
        return result;
    }
    public static void main(String[] args) {
        boolean stdio = args.length >= 4 && "--stdio".equals(args[1]); PrintStream stdout = System.out; protocolOutput = stdout;
        try {
            if (stdio) System.setOut(System.err);
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "spell-face-execution-fixture";
                default -> throw new AssertionError("Unexpected GUI call " + m.getName());
            }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            if (stdio) stdout.println(run(scenario(args[2]), Integer.parseInt(args[3]), true));
            else if (args.length == 3) System.out.println(run(scenario(args[1]), Integer.parseInt(args[2]), false));
            else for (int seat : List.of(0, 1)) for (String id : List.of("petty-theft", "stomp", "harnfel", "tibalt")) System.out.println(run(scenario(id), seat, false));
            if (!stdio) System.out.println("PASS " + checks + " spell-face execution checks; no games or strength claim");
            System.exit(0);
        } catch (Throwable failure) { System.setOut(stdout); failure.printStackTrace(System.err); System.exit(1); }
    }
}
