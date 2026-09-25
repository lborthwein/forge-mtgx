package forge.bench;

import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.LobbyPlayerLookahead;
import forge.ai.simulation.LookaheadSearch;
import forge.ai.simulation.SimSearchBudget;
import forge.ai.simulation.SimulationController;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Headless native Forge-vs-Forge runner for the mtgx look-ahead agent (lane forge-search-scoping).
 *
 * <p>{@code java -cp <jar> forge.bench.LookaheadBench <config.json> <out.jsonl>}; run with cwd =
 * {@code <forge>/forge-gui} (card scripts). One JSON line per game is appended to out.jsonl, so
 * a JVM that dies loses at most the game in flight, and a restart skips every id already there.
 *
 * <p>Config: {@code {"aiTimeoutSec":600, "gameTimeoutSec":1800, "simMaxDepth":4,
 * "simMaxSimulations":1000, "lookahead":{worlds,breadth,horizonTurns,threads,shadow,probe,margin},
 * "games":[{"id":..,"seed":..,"decks":[a,b],"seats":["lookahead"|"default"|"sim", ...]}]}}.
 * A seat's look-ahead seed is the game seed mixed with the seat index, so a game is a pure
 * function of its row.
 */
public final class LookaheadBench {
    private LookaheadBench() {
    }

    public static void main(String[] args) throws Exception {
        final PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.setOut(err); // Forge chatter goes to stderr; results go to the file
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        final JsonObject cfg = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        final Path out = Path.of(args[1]);

        final int aiTimeoutSec = cfg.has("aiTimeoutSec") ? cfg.get("aiTimeoutSec").getAsInt() : 600;
        final int gameTimeoutSec = cfg.has("gameTimeoutSec") ? cfg.get("gameTimeoutSec").getAsInt() : 1800;
        if (cfg.has("simMaxDepth")) {
            SimulationController.setDefaultMaxDepth(cfg.get("simMaxDepth").getAsInt());
        }
        if (cfg.has("simMaxSimulations")) {
            SimSearchBudget.setBudget(cfg.get("simMaxSimulations").getAsInt());
        }
        final JsonObject la = cfg.has("lookahead") ? cfg.getAsJsonObject("lookahead") : new JsonObject();

        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });

        final Set<String> done = new HashSet<>();
        if (Files.exists(out)) {
            for (String line : Files.readAllLines(out)) {
                if (!line.isBlank()) {
                    done.add(JsonParser.parseString(line).getAsJsonObject().get("id").getAsString());
                }
            }
        }

        final ExecutorService gameThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Game-lookahead-bench");
            t.setDaemon(true);
            return t;
        });
        final com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        for (JsonElement ge : cfg.getAsJsonArray("games")) {
            final JsonObject spec = ge.getAsJsonObject();
            final String id = spec.get("id").getAsString();
            if (done.contains(id)) {
                continue;
            }
            final long seed = spec.get("seed").getAsLong();
            final JsonArray decks = spec.getAsJsonArray("decks");
            final JsonArray seatModes = spec.getAsJsonArray("seats");

            final List<RegisteredPlayer> seats = new ArrayList<>();
            final List<LookaheadSearch> searches = new ArrayList<>();
            final List<LobbyPlayerLookahead> laLobbies = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final Deck deck = DeckSerializer.fromFile(new File(decks.get(i).getAsString()));
                final String mode = seatModes.get(i).getAsString();
                final String name = "Seat" + i;
                final LobbyPlayer lp;
                if ("lookahead".equals(mode)) {
                    LookaheadSearch.Config c = new LookaheadSearch.Config();
                    c.worlds = la.has("worlds") ? la.get("worlds").getAsInt() : 1;
                    c.breadth = la.has("breadth") ? la.get("breadth").getAsInt() : 4;
                    c.horizonTurns = la.has("horizonTurns") ? la.get("horizonTurns").getAsInt() : 2;
                    c.threads = la.has("threads") ? la.get("threads").getAsInt() : 1;
                    c.shadow = la.has("shadow") && la.get("shadow").getAsBoolean();
                    c.probe = la.has("probe") && la.get("probe").getAsBoolean();
                    c.probeMax = la.has("probeMax") ? la.get("probeMax").getAsInt() : 6;
                    c.fidelity = !la.has("fidelity") || la.get("fidelity").getAsBoolean();
                    c.margin = la.has("margin") ? la.get("margin").getAsDouble() : 0.0;
                    c.maxSteps = la.has("maxSteps") ? la.get("maxSteps").getAsInt() : 5000;
                    c.resample = !la.has("resample") || la.get("resample").getAsBoolean();
                    c.seed = seed * 31 + i;
                    LookaheadSearch s = new LookaheadSearch(c);
                    LobbyPlayerLookahead l = new LobbyPlayerLookahead(name);
                    l.setAiProfile("Default");
                    searches.add(s);
                    laLobbies.add(l);
                    lp = l;
                } else {
                    final Set<AIOption> options = "sim".equals(mode) ? Sets.newHashSet(AIOption.USE_FULL_SIMULATION) : null;
                    LobbyPlayerAi l = new LobbyPlayerAi(name, options);
                    l.setAiProfile("Default");
                    searches.add(null);
                    laLobbies.add(null);
                    lp = l;
                }
                final RegisteredPlayer rp = new RegisteredPlayer(deck);
                rp.setPlayer(lp);
                seats.add(rp);
            }

            final GameRules rules = new GameRules(GameType.Constructed);
            rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
            // The interactive instrument's rules (InteractiveMain), so native and interactive reads compare.
            rules.setGamesPerMatch(1);
            rules.setAISideboardingEnabled(false);
            rules.setSideboardForAI(false);
            rules.setAllowCheatShuffle(false);
            rules.setWarnAboutAICards(false);
            final Match match = new Match(rules, seats, "mtgx-lookahead");
            MyRandom.setRandom(new Random(seed));
            // Every process-wide object id (SpellAbility, Trigger, StaticAbility, ...) this game creates comes
            // from a scope opened for this game alone, so a game is a function of its row and not of the games
            // this JVM played before it (ids feed hashCode and so iteration order; without this a replay in a
            // different JVM order diverged in 9 of 47 K=8 games).
            forge.util.IdScope.open();
            final Object gameIds = forge.util.IdScope.capture();
            final Game game = match.createGame();
            game.AI_CAN_USE_TIMEOUT = false;
            game.AI_TIMEOUT = aiTimeoutSec;
            for (int i = 0; i < 2; i++) {
                if (laLobbies.get(i) != null) {
                    laLobbies.get(i).bind(game, searches.get(i));
                }
            }
            final Digest digest = new Digest(game);
            if (cfg.has("traceDir")) {
                digest.trace = Path.of(cfg.get("traceDir").getAsString(), id + ".fp.txt");
                Files.deleteIfExists(digest.trace);
            }
            game.subscribeToEvents(digest);

            final long cpu0 = os.getProcessCpuTime();
            final long t0 = System.currentTimeMillis();
            String abort = null;
            final Future<?> f = gameThread.submit(() -> {
                forge.util.IdScope.install(gameIds);
                try {
                    match.startGame(game);
                } finally {
                    forge.util.IdScope.install(null);
                }
            });
            try {
                f.get(gameTimeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                abort = "timeout";
            } catch (Exception e) {
                abort = "InstrumentError: " + e.getCause();
                if (e.getCause() != null) {
                    e.getCause().printStackTrace(err);
                }
            }
            final long wallMs = System.currentTimeMillis() - t0;
            forge.util.IdScope.close();
            if (digest.trace != null) {
                StringBuilder lg = new StringBuilder();
                java.util.List<forge.game.GameLogEntry> entries = new ArrayList<>(game.getGameLog().getAllEntries());
                java.util.Collections.reverse(entries);
                for (forge.game.GameLogEntry le : entries) {
                    lg.append(le.toString()).append('\n');
                }
                Files.writeString(Path.of(digest.trace.toString().replace(".fp.txt", ".log.txt")), lg.toString(), StandardCharsets.UTF_8);
            }
            final long cpuMs = (os.getProcessCpuTime() - cpu0) / 1_000_000L;

            final JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("seed", seed);
            row.add("seats", seatModes);
            row.add("decks", decks);
            int winner = -1;
            String reason = abort;
            int turns = 0;
            final GameOutcome go = abort == null ? game.getOutcome() : null;
            if (go != null) {
                turns = go.getLastTurnNumber();
                if (go.isDraw()) {
                    reason = "draw";
                } else {
                    reason = String.valueOf(go.getWinCondition());
                    final LobbyPlayer w = go.getWinningLobbyPlayer();
                    for (int i = 0; i < 2; i++) {
                        if (seats.get(i).getPlayer() == w) {
                            winner = i;
                        }
                    }
                }
            } else if (abort == null) {
                reason = "no-outcome";
            }
            row.addProperty("winner", winner);
            row.addProperty("reason", reason);
            row.addProperty("turns", turns);
            int startingSeat = -1;
            if (game.getStartingPlayer() != null) {
                for (int i = 0; i < 2; i++) {
                    if (seats.get(i).getPlayer() == game.getStartingPlayer().getLobbyPlayer()) {
                        startingSeat = i;
                    }
                }
            }
            row.addProperty("startingSeat", startingSeat);
            row.addProperty("wallMs", wallMs);
            row.addProperty("cpuMs", cpuMs);
            row.addProperty("digest", digest.hex());
            JsonArray st = new JsonArray();
            for (int i = 0; i < 2; i++) {
                LookaheadSearch s = searches.get(i);
                if (s == null) {
                    st.add((JsonElement) null);
                } else {
                    s.finishGame(game);
                    JsonObject so = s.getStats().toJson();
                    so.add("config", s.getConfig().toJson());
                    st.add(so);
                    s.shutdown();
                }
            }
            row.add("search", st);
            Files.writeString(out, new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            err.println("[lookahead-bench] " + id + " winner=" + winner + " reason=" + reason + " turns=" + turns
                    + " wall=" + wallMs + "ms cpu=" + cpuMs + "ms");
            if (abort != null && abort.equals("timeout")) {
                // The game thread may still be running; a fresh JVM resumes from the next id.
                err.println("[lookahead-bench] timeout: exiting for a clean restart");
                System.exit(3);
            }
        }
        System.exit(0);
    }

    /** A replay digest: the position fingerprint at every turn start, plus the final one. */
    public static final class Digest {
        private final Game game;
        private final MessageDigest md;
        Path trace;

        Digest(Game game) throws Exception {
            this.game = game;
            this.md = MessageDigest.getInstance("SHA-256");
        }

        @Subscribe
        public void on(GameEventTurnBegan e) {
            final String fp = LookaheadSearch.fingerprint(game);
            md.update(fp.getBytes(StandardCharsets.UTF_8));
            if (trace != null) {
                try {
                    Files.writeString(trace, fp, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }
        }

        @Subscribe
        public void onPhase(forge.game.event.GameEventTurnPhase e) {
            if (trace != null) {
                try {
                    Files.writeString(trace, "--phase " + e.phase() + "\n" + LookaheadSearch.fingerprint(game), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }
        }

        String hex() {
            md.update(LookaheadSearch.fingerprint(game).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        }
    }
}
