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
 * "simMaxSimulations":1000, "lookahead":{worlds,breadth,horizonTurns,threads,shadow,probe,margin,
 * priorExtra,priorUrl,priorShadow,priorTimeoutMs,priorCheckpointSha256 (read HX),
 * tutorRank,tutorUrl,tutorShadow,tutorLands,tutorTimeoutMs,tutorCheckpointSha256 (tutor ranking),
 * belief,beliefShadow,beliefUrl,beliefTimeoutMs,beliefCheckpointSha256,beliefCube,beliefCubeSha256,beliefBasics
 * (lane belief-sampling-0928)},
 * "games":[{"id":..,"seed":..,"decks":[a,b],"seats":["lookahead"|"default"|"sim", ...]}]}}.
 * A seat's look-ahead seed is the game seed mixed with the seat index, so a game is a pure
 * function of its row.
 */
public final class LookaheadBench {
    private LookaheadBench() {
    }

    /** HX keys: priorExtra (0 = off), priorUrl, priorShadow, priorTimeoutMs (2000), priorCheckpointSha256. */
    static void applyPrior(JsonObject la, LookaheadSearch.Config c) {
        c.priorExtra = la.has("priorExtra") ? la.get("priorExtra").getAsInt() : 0;
        c.priorUrl = la.has("priorUrl") ? la.get("priorUrl").getAsString() : null;
        c.priorShadow = la.has("priorShadow") && la.get("priorShadow").getAsBoolean();
        c.priorTimeoutMs = la.has("priorTimeoutMs") ? la.get("priorTimeoutMs").getAsInt() : 2000;
        c.priorCheckpointSha256 = la.has("priorCheckpointSha256") ? la.get("priorCheckpointSha256").getAsString() : null;
    }

    /** Tutor-ranking keys: tutorRank (0 = off), tutorUrl, tutorShadow, tutorLands, tutorTimeoutMs (2000), tutorCheckpointSha256. */
    static void applyTutor(JsonObject la, LookaheadSearch.Config c) {
        c.tutorRank = la.has("tutorRank") ? la.get("tutorRank").getAsInt() : 0;
        c.tutorUrl = la.has("tutorUrl") ? la.get("tutorUrl").getAsString() : null;
        c.tutorShadow = la.has("tutorShadow") && la.get("tutorShadow").getAsBoolean();
        c.tutorLands = la.has("tutorLands") && la.get("tutorLands").getAsBoolean();
        c.tutorTimeoutMs = la.has("tutorTimeoutMs") ? la.get("tutorTimeoutMs").getAsInt() : 2000;
        c.tutorCheckpointSha256 = la.has("tutorCheckpointSha256") ? la.get("tutorCheckpointSha256").getAsString() : null;
    }

    /** Belief keys: belief (off | human | uniform), beliefShadow, beliefUrl, beliefTimeoutMs (2000),
     * beliefCheckpointSha256, beliefCube, beliefCubeSha256, beliefBasics (8). */
    static void applyBelief(JsonObject la, LookaheadSearch.Config c) {
        c.belief = la.has("belief") ? la.get("belief").getAsString() : "off";
        c.beliefShadow = la.has("beliefShadow") && la.get("beliefShadow").getAsBoolean();
        c.beliefUrl = la.has("beliefUrl") ? la.get("beliefUrl").getAsString() : null;
        c.beliefTimeoutMs = la.has("beliefTimeoutMs") ? la.get("beliefTimeoutMs").getAsInt() : 2000;
        c.beliefCheckpointSha256 = la.has("beliefCheckpointSha256") ? la.get("beliefCheckpointSha256").getAsString() : null;
        c.beliefCube = la.has("beliefCube") ? la.get("beliefCube").getAsString() : null;
        c.beliefCubeSha256 = la.has("beliefCubeSha256") ? la.get("beliefCubeSha256").getAsString() : null;
        c.beliefBasics = la.has("beliefBasics") ? la.get("beliefBasics").getAsInt() : 8;
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
        {
            // HX: the policy-prior pin is checked once at JVM start; a missing pin, an unreachable service or another
            // checkpoint refuses the whole run (exit 4) before any game.
            final LookaheadSearch.Config pc = new LookaheadSearch.Config();
            applyPrior(la, pc);
            if (pc.priorOn()) {
                try {
                    if (pc.priorExtra < 1) {
                        throw new IllegalStateException("priorShadow needs priorExtra >= 1");
                    }
                    err.println("[lookahead-bench] prior service " + pc.priorUrl + " checkpoint " + LookaheadSearch.checkPriorService(pc));
                } catch (IllegalStateException e) {
                    err.println("[lookahead-bench] refusing: " + e.getMessage());
                    System.exit(4);
                }
            }
            // Tutor ranking: the same refusal for the ranker's pin.
            applyTutor(la, pc);
            if (pc.tutorOn()) {
                try {
                    if (pc.tutorRank < 1) {
                        throw new IllegalStateException("tutorShadow needs tutorRank >= 1");
                    }
                    err.println("[lookahead-bench] tutor ranker " + pc.tutorUrl + " checkpoint " + LookaheadSearch.checkTutorService(pc));
                } catch (IllegalStateException e) {
                    err.println("[lookahead-bench] refusing: " + e.getMessage());
                    System.exit(4);
                }
            }
        }

        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        {
            // Belief: the cube pin (and, for belief=human, the service's checkpoint pin) is checked once, after the card
            // database loads and before any game; a failure refuses the run (exit 4).
            final LookaheadSearch.Config bc = new LookaheadSearch.Config();
            applyBelief(la, bc);
            if (bc.beliefOn()) {
                try {
                    final forge.ai.simulation.BeliefSampler.Cube cube = LookaheadSearch.checkBelief(bc);
                    err.println("[lookahead-bench] belief " + bc.belief + (bc.beliefShadow ? " (shadow)" : "") + " cube "
                            + cube.sha256 + " names " + cube.names.size() + " unresolved " + cube.unresolved);
                } catch (IllegalStateException e) {
                    err.println("[lookahead-bench] refusing: " + e.getMessage());
                    System.exit(4);
                }
            }
        }
        if (!"false".equals(System.getProperty("lookahead.preloadTokens"))) {
            // C3c: fill the token table before any game. TokenDb fills a HashMultimap lazily on a token's first use;
            // play-outs on several threads raced on it (a reader saw a token with fewer arts and Aggregates.random drew
            // fewer numbers). TokenDb is also synchronized now; preloading keeps the lock uncontended.
            FModel.getMagicDb().getAllTokens().preloadTokens();
        }

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
                    c.threads = la.has("threads") ? la.get("threads").getAsInt() : 0;
                    c.dedup = la.has("dedup") && la.get("dedup").getAsBoolean();
                    c.dedupVerify = la.has("dedupVerify") && la.get("dedupVerify").getAsBoolean();
                    c.dedupSteps = la.has("dedupSteps") ? la.get("dedupSteps").getAsInt() : 0;
                    c.reuse = la.has("reuse") && la.get("reuse").getAsBoolean();
                    c.reuseVerify = la.has("reuseVerify") && la.get("reuseVerify").getAsBoolean();
                    c.reuseShadowFresh = la.has("reuseShadowFresh") && la.get("reuseShadowFresh").getAsBoolean();
                    c.shadow = la.has("shadow") && la.get("shadow").getAsBoolean();
                    c.probe = la.has("probe") && la.get("probe").getAsBoolean();
                    c.probeMax = la.has("probeMax") ? la.get("probeMax").getAsInt() : 6;
                    c.fidelity = !la.has("fidelity") || la.get("fidelity").getAsBoolean();
                    c.combat = la.has("combat") && la.get("combat").getAsBoolean();
                    c.stack = la.has("stack") && la.get("stack").getAsBoolean();
                    c.probeStack = la.has("probeStack") && la.get("probeStack").getAsBoolean();
                    c.margin = la.has("margin") ? la.get("margin").getAsDouble() : 0.0;
                    c.departZ = la.has("departZ") ? la.get("departZ").getAsDouble() : 0.0;
                    c.maxSteps = la.has("maxSteps") ? la.get("maxSteps").getAsInt() : 5000;
                    c.resample = !la.has("resample") || la.get("resample").getAsBoolean();
                    c.modelUrl = la.has("modelUrl") ? la.get("modelUrl").getAsString() : null;
                    c.modelTimeoutMs = la.has("modelTimeoutMs") ? la.get("modelTimeoutMs").getAsInt() : 2000;
                    c.budgetMs = la.has("budgetMs") ? la.get("budgetMs").getAsLong() : 0L;
                    c.decisionLog = la.has("decisionLog") && la.get("decisionLog").getAsBoolean();
                    applyPrior(la, c);
                    applyTutor(la, c);
                    applyBelief(la, c);
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
            // Frame probe (lane forge-ai-misplays-0928): a game may start from a mid-game position in Forge's own
            // GameState text (as BenchMain's from-frame), installed at the start of turn 1, and stop once a turn past
            // "maxTurn" begins -- to replay one reported decision under the search's trace ("-Dlookahead.explain=true").
            final String framePath = spec.has("frame") ? spec.get("frame").getAsString() : null;
            final int maxTurn = spec.has("maxTurn") ? spec.get("maxTurn").getAsInt() : 0;
            Runnable frameHook = null;
            if (framePath != null) {
                final String frameText = Files.readString(Path.of(framePath));
                frameHook = () -> {
                    final forge.game.GameState gs = new forge.game.GameState();
                    gs.parse(java.util.Arrays.asList(frameText.split("\\R")));
                    gs.applyToGame(game);
                    final forge.game.GameState back = new forge.game.GameState();
                    back.initFromGame(game);
                    err.println("[lookahead-bench] frame " + framePath + " installed:\n" + back);
                };
            }
            if (maxTurn > 0) {
                game.subscribeToEvents(new Object() {
                    @Subscribe
                    public void on(GameEventTurnBegan e) {
                        if (e.turnNumber() > maxTurn && !game.isGameOver()) {
                            game.setGameOver(forge.game.GameEndReason.Draw);
                        }
                    }
                });
            }
            final Runnable startHook = frameHook;

            final long cpu0 = os.getProcessCpuTime();
            final long t0 = System.currentTimeMillis();
            String abort = null;
            final Future<?> f = gameThread.submit(() -> {
                forge.util.IdScope.install(gameIds);
                try {
                    if (startHook != null) {
                        match.startGame(game, startHook);
                    } else {
                        match.startGame(game);
                    }
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
