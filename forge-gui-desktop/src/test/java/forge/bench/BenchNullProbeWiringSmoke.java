package forge.bench;

import com.google.gson.JsonObject;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

/** One priority decision per fresh fixture, never a match or benchmark panel.
 * Exercises the real lobby-created NULL_PROBE override, not direct probe calls.
 */
public final class BenchNullProbeWiringSmoke {
    private record Result(String action, Map<String, String> before, Map<String, String> after,
                          int allocatedIds, JsonObject rng, long nextRandom, JsonObject counters) {}
    private static final Set<String> warningPaths = new LinkedHashSet<>();

    private static int sequence() {
        try {
            var field = SpellAbility.class.getDeclaredField("maxId"); field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Map<String, String> state(Game game) {
        var raw = BenchMenuStateAudit.capture(game);
        var names = new HashMap<String, String>();
        // Across fresh games absolute global ability IDs differ. Normalize only
        // those key names, while separately comparing actual allocation deltas.
        for (var card : game.getCardsInGame()) {
            int index = 0;
            for (var ability : card.getAllSpellAbilities())
                names.put("ability/" + card.getId() + "/" + ability.getId(), "ability/" + card.getId() + "/ordinal" + index++);
        }
        var out = new TreeMap<String, String>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey().equals("abilitySequence")) continue;
            String key = entry.getKey();
            if (key.startsWith("ability/")) {
                int end = key.indexOf('/', "ability/".length());
                end = key.indexOf('/', end + 1);
                String prefix = end < 0 ? key : key.substring(0, end);
                key = Objects.requireNonNull(names.get(prefix), "Unknown original ability path") + (end < 0 ? "" : key.substring(end));
            }
            out.put(key, entry.getValue());
        }
        return out;
    }
    private static Result decision(BenchSession.Mode mode, String scenario, boolean live) {
        BenchRandomAudit.install(91531); // fixture-local deterministic setup, no match seed.
        var wire = new ByteArrayOutputStream();
        var session = new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]), wire));
        var bridge = mode == null ? null : new LobbyPlayerBridge("Payer", null, session, mode, 0);
        if (bridge != null) bridge.setAiProfile("Default");
        var payerLobby = bridge == null ? GamePlayerUtil.createAiPlayer("Payer", 0, 0, null, "Default") : bridge;
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(payerLobby),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1, 0, null, "Default")));
        var match = new Match(new GameRules(GameType.Constructed), players, "Null/probe wiring fixture");
        var game = match.createGame();
        var payer = game.getPlayers().get(0);
        if (!(payer.getController() instanceof PlayerControllerAi)) throw new AssertionError("Not a real AI controller");
        if (bridge != null && ((PlayerControllerBridge) payer.getController()).getMode() != mode) throw new AssertionError("Lobby mode not installed");
        session.setLiveGame(live ? game : match.createGame());
        game.setAge(GameStage.Play);
        // Default Forge deliberately defers an ordinary non-haste creature to
        // Main 2; exercise its real selection without changing that policy.
        game.getPhaseHandler().devModeSet(scenario.equals("spell") ? PhaseType.MAIN2 : PhaseType.MAIN1, payer);
        if (!scenario.equals("empty")) {
            StaticData.instance().attemptToLoadCard("Plains");
            var land = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Plains"), payer);
            land.setGameTimestamp(game.getNextTimestamp()); land.setSickness(false);
            payer.getZone(scenario.equals("land") ? ZoneType.Hand : ZoneType.Battlefield).add(land);
        }
        if (scenario.equals("spell")) {
            StaticData.instance().attemptToLoadCard("Savannah Lions");
            var spell = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Savannah Lions"), payer);
            spell.setGameTimestamp(game.getNextTimestamp()); payer.getZone(ZoneType.Hand).add(spell);
        }
        game.getAction().checkStateEffects(true);
        var before = state(game);
        int ids = sequence();
        var choices = payer.getController().chooseSpellAbilityToPlay();
        int allocated = sequence() - ids;
        String action = choices == null ? "pass" : choices.stream()
                .map(a -> StateEncoder.encodeSpellAbility(a, payer.getView()).toString()).toList().toString();
        if (scenario.equals("empty") && choices != null) throw new AssertionError("Empty fixture unexpectedly chose an action");
        if (!scenario.equals("empty") && (choices == null || choices.isEmpty())) throw new AssertionError("Fixture did not exercise actual AI " + scenario + " selection");
        var after = state(game);
        var rng = ((BenchRandomAudit.AuditedRandom) MyRandom.getRandom()).snapshot();
        rng.remove("purityChecks"); // audit instrumentation, not RNG stream state.
        long next = MyRandom.getRandom().nextLong();
        var counters = bridge == null ? new JsonObject() : bridge.getCounters().toJson();
        if (wire.size() != 0) throw new AssertionError("Native/null/probe decision made a host RPC");
        if (bridge != null) {
            var calls = counters.getAsJsonObject("calls");
            if (live && (!calls.has("chooseSpellAbilityToPlay") || calls.get("chooseSpellAbilityToPlay").getAsInt() != 1))
                throw new AssertionError("Missing real priority call counter: " + counters);
            if (!live && counters.get("totalCalls").getAsInt() != 0) throw new AssertionError("Simulation counted as live decision");
            var instruments = counters.getAsJsonObject("instruments");
            int probes = instruments.has("auditPriorityProbe") ? instruments.get("auditPriorityProbe").getAsInt() : 0;
            if (probes != (mode == BenchSession.Mode.NULL_PROBE && live ? 1 : 0)) throw new AssertionError("Wrong probe instrumentation: " + counters);
            if (counters.get("totalDelegatedRequested").getAsInt() != 0 || counters.get("totalDelegatedRefused").getAsInt() != 0)
                throw new AssertionError("Null control entered host delegation protocol");
        }
        return new Result(action, before, after, allocated, rng, next, counters);
    }
    private static void same(Result left, Result right, String label) {
        if (!left.before().equals(right.before())) throw new AssertionError(label + " setup state differs");
        if (!left.action().equals(right.action())) throw new AssertionError(label + " actual AI action differs: " + left.action() + " vs " + right.action());
        if (!left.after().equals(right.after())) {
            for (String key : left.after().keySet()) if (!Objects.equals(left.after().get(key), right.after().get(key)))
                throw new AssertionError(label + " postdecision state differs: " + key);
            throw new AssertionError(label + " state keys differ");
        }
        if (left.allocatedIds() != right.allocatedIds()) throw new AssertionError(label + " global ID allocation differs");
        if (!left.rng().equals(right.rng()) || left.nextRandom() != right.nextRandom()) throw new AssertionError(label + " RNG differs");
    }
    private static void scenario(String scenario, boolean live) {
        Result stock = decision(null, scenario, live);
        Result plain = decision(BenchSession.Mode.NULL, scenario, live);
        Result probe = decision(BenchSession.Mode.NULL_PROBE, scenario, live);
        same(stock, plain, "stock/null " + scenario);
        same(plain, probe, "null/probe " + scenario);
        JsonObject plainCounters = plain.counters().deepCopy(), probeCounters = probe.counters().deepCopy();
        // The declared mode intentionally differs; all ownership buckets and
        // actual call counts must still be equal, just like the engine state.
        if (!plainCounters.getAsJsonObject("controllerCoverage").get("mode").getAsString().equals("null")
                || !probeCounters.getAsJsonObject("controllerCoverage").get("mode").getAsString().equals("null-probe"))
            throw new AssertionError("Controller coverage lost its actual mode identity");
        plainCounters.getAsJsonObject("controllerCoverage").remove("mode");
        probeCounters.getAsJsonObject("controllerCoverage").remove("mode");
        plainCounters.remove("instruments"); probeCounters.remove("instruments");
        if (!plainCounters.equals(probeCounters)) throw new AssertionError("Probe changed actual controller call counters");
        System.out.println("PASS lobby-created stock/null/null-probe " + scenario + " live=" + live
                + " allocationDelta=" + stock.allocatedIds() + " counters=" + probe.counters());
    }
    private static void parseModes() {
        if (BenchSession.Mode.parse(null) != BenchSession.Mode.NULL || BenchSession.Mode.parse(" forge ") != BenchSession.Mode.NULL
                || BenchSession.Mode.parse(" BRIDGE ") != BenchSession.Mode.BRIDGE || BenchSession.Mode.parse(" NULL-PROBE ") != BenchSession.Mode.NULL_PROBE)
            throw new AssertionError("Mode parsing differs from production contract");
        for (String invalid : List.of("", "native", "forg", "null_probe", "default")) {
            try { BenchSession.Mode.parse(invalid); throw new AssertionError("Unknown mode silently accepted: " + invalid); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS exact mode parsing and unknown-mode rejection");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "null-probe-wiring-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            PrintStream normal = System.out;
            System.setOut(new PrintStream(normal, true) {
                @Override public void println(String line) {
                    if (line != null && line.contains("Did not have activator set"))
                        warningPaths.add(StackWalker.getInstance().walk(frames -> frames.limit(9).map(Object::toString).toList()).toString());
                    super.println(line);
                }
            });
            try { parseModes(); scenario("empty", true); scenario("land", true); scenario("spell", true); scenario("spell", false); }
            finally {
                System.setOut(normal);
                for (String path : warningPaths) System.out.println("DIAGNOSTIC unset-actor call path " + path);
            }
            System.out.println("PASS real NULL_PROBE wiring development fixtures; not whole-game certification");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
