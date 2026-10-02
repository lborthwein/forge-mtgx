package forge.bench;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Forge Default vs Forge Default on fixed seeds, written against UPSTREAM Forge APIs only (lane forge-upstream-1002).
 *
 * <p>The same source file is compiled into a pure upstream build and into the mtgx fork, and both play the same game
 * list in the same order: the fork with every option off must produce the same games (the upstream-sync gate "a").
 * It mirrors {@link LookaheadBench}'s default seats, rules and preferences, minus everything upstream does not have
 * (IdScope, look-ahead, policy, aiFixes0928). Without IdScope the process-wide object ids carry over between games,
 * so two runs are comparable only when they play the same list in the same order.
 *
 * <p>{@code java -cp <jar> forge.bench.UpstreamParityBench <config.json> <out.jsonl>}, cwd = {@code <forge>/forge-gui}.
 * Config: LookaheadBench's ({@code aiTimeoutSec}, {@code gameTimeoutSec}, {@code games[{id,seed,decks,seats}]});
 * every seat must be "default". One JSON line per game: winner, reason, turns, startingSeat, and three digests:
 * {@code digest} (LookaheadBench's: the fingerprint at every turn start plus the final one), {@code phaseDigest}
 * (at every phase) and {@code logDigest} (the whole game log text; {@code -Dparity.logDir=<dir>} also writes it).
 */
public final class UpstreamParityBench {
    private UpstreamParityBench() {
    }

    public static void main(String[] args) {
        final PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        try {
            run(args, err);
        } catch (Throwable t) {
            // Forge's GUI threads are not daemons: without an explicit exit a failed run would hang
            t.printStackTrace(err);
            System.exit(5);
        }
    }

    static void run(String[] args, PrintStream err) throws Exception {
        System.setOut(err);
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        final JsonObject cfg = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        final Path out = Path.of(args[1]);
        final int aiTimeoutSec = cfg.has("aiTimeoutSec") ? cfg.get("aiTimeoutSec").getAsInt() : 600;
        final int gameTimeoutSec = cfg.has("gameTimeoutSec") ? cfg.get("gameTimeoutSec").getAsInt() : 1800;
        for (JsonElement ge : cfg.getAsJsonArray("games")) {
            for (JsonElement s : ge.getAsJsonObject().getAsJsonArray("seats")) {
                if (!"default".equals(s.getAsString())) {
                    err.println("[parity-bench] refusing: seat mode " + s + " (only \"default\")");
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
        if (!"false".equals(System.getProperty("lookahead.preloadTokens"))) {
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
        if (!done.isEmpty()) {
            // ids carry over between games: a resumed list is not comparable with a fresh one
            err.println("[parity-bench] refusing: " + out + " already has rows; parity runs are never resumed");
            System.exit(4);
        }
        final ExecutorService gameThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Game-parity-bench");
            t.setDaemon(true);
            return t;
        });

        for (JsonElement ge : cfg.getAsJsonArray("games")) {
            final JsonObject spec = ge.getAsJsonObject();
            final String id = spec.get("id").getAsString();
            final long seed = spec.get("seed").getAsLong();
            final JsonArray decks = spec.getAsJsonArray("decks");
            final List<RegisteredPlayer> seats = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final Deck deck = DeckSerializer.fromFile(new File(decks.get(i).getAsString()));
                final LobbyPlayerAi l = new LobbyPlayerAi("Seat" + i, null);
                l.setAiProfile("Default");
                final RegisteredPlayer rp = new RegisteredPlayer(deck);
                rp.setPlayer(l);
                seats.add(rp);
            }
            final GameRules rules = new GameRules(GameType.Constructed);
            rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
            rules.setGamesPerMatch(1);
            rules.setAISideboardingEnabled(false);
            rules.setSideboardForAI(false);
            rules.setAllowCheatShuffle(false);
            rules.setWarnAboutAICards(false);
            final Match match = new Match(rules, seats, "mtgx-parity");
            MyRandom.setRandom(new Random(seed));
            final Game game = match.createGame();
            game.AI_TIMEOUT = aiTimeoutSec;
            final Digest digest = new Digest(game);
            game.subscribeToEvents(digest);

            final long t0 = System.currentTimeMillis();
            String abort = null;
            final Future<?> f = gameThread.submit(() -> match.startGame(game));
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

            final JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("seed", seed);
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
            int startingSeat = -1;
            if (game.getStartingPlayer() != null) {
                for (int i = 0; i < 2; i++) {
                    if (seats.get(i).getPlayer() == game.getStartingPlayer().getLobbyPlayer()) {
                        startingSeat = i;
                    }
                }
            }
            row.addProperty("winner", winner);
            row.addProperty("reason", reason);
            row.addProperty("turns", turns);
            row.addProperty("startingSeat", startingSeat);
            row.addProperty("wallMs", wallMs);
            row.addProperty("digest", digest.hex());
            row.addProperty("phaseDigest", digest.phaseHex());
            row.addProperty("logDigest", logDigest(game, id));
            Files.writeString(out, row + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            err.println("[parity-bench] " + id + " winner=" + winner + " reason=" + reason + " turns=" + turns + " wall=" + wallMs + "ms");
            if ("timeout".equals(abort)) {
                err.println("[parity-bench] timeout: stopping (the game thread may still run)");
                System.exit(3);
            }
        }
        System.exit(0);
    }

    static String logDigest(Game game, String id) throws Exception {
        final List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getAllEntries());
        Collections.reverse(entries);
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final StringBuilder text = new StringBuilder();
        for (GameLogEntry le : entries) {
            md.update(le.toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            text.append(le.type()).append(" | ").append(le).append('\n');
        }
        final String dir = System.getProperty("parity.logDir");
        if (dir != null) {
            Files.writeString(Path.of(dir, id + ".log.txt"), text.toString(), StandardCharsets.UTF_8);
        }
        return hex(md.digest());
    }

    static String hex(byte[] d) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, 16);
    }

    /** LookaheadSearch.fingerprint, restated on upstream APIs. */
    static String fingerprint(Game g) {
        final StringBuilder sb = new StringBuilder();
        sb.append("T").append(g.getPhaseHandler().getTurn()).append(' ').append(g.getPhaseHandler().getPhase()).append('\n');
        for (Player p : g.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife())
                    .append(" poison=").append(p.getPoisonCounters())
                    .append(" lib=").append(p.getCardsIn(ZoneType.Library).size())
                    .append(" lost=").append(p.hasLost()).append('\n');
            sb.append(" hand=").append(names(p.getCardsIn(ZoneType.Hand))).append('\n');
            sb.append(" gy=").append(names(p.getCardsIn(ZoneType.Graveyard))).append('\n');
            sb.append(" exile=").append(names(p.getCardsIn(ZoneType.Exile))).append('\n');
            final List<String> bf = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                final StringBuilder cb = new StringBuilder(c.getName());
                cb.append(c.isTapped() ? "/T" : "/U");
                if (c.isCreature()) {
                    cb.append('/').append(c.getNetPower()).append('/').append(c.getNetToughness()).append("/d").append(c.getDamage());
                }
                final Map<String, Integer> cn = new TreeMap<>();
                for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                    cn.put(String.valueOf(e.getElement()), e.getCount());
                }
                if (!cn.isEmpty()) {
                    cb.append(cn);
                }
                bf.add(cb.toString());
            }
            Collections.sort(bf);
            sb.append(" bf=").append(bf).append('\n');
        }
        return sb.toString();
    }

    static List<String> names(Iterable<Card> cs) {
        final List<String> l = new ArrayList<>();
        for (Card c : cs) {
            l.add(c.getName());
        }
        Collections.sort(l);
        return l;
    }

    /** Turn-start digest (LookaheadBench's) and an every-phase digest. */
    public static final class Digest {
        private final Game game;
        private final MessageDigest md;
        private final MessageDigest phase;

        Digest(Game game) throws Exception {
            this.game = game;
            this.md = MessageDigest.getInstance("SHA-256");
            this.phase = MessageDigest.getInstance("SHA-256");
        }

        @Subscribe
        public void on(GameEventTurnBegan e) {
            md.update(fingerprint(game).getBytes(StandardCharsets.UTF_8));
        }

        @Subscribe
        public void onPhase(GameEventTurnPhase e) {
            phase.update(("--phase " + e.phase() + "\n" + fingerprint(game)).getBytes(StandardCharsets.UTF_8));
        }

        String hex() {
            md.update(fingerprint(game).getBytes(StandardCharsets.UTF_8));
            return UpstreamParityBench.hex(md.digest());
        }

        String phaseHex() {
            phase.update(fingerprint(game).getBytes(StandardCharsets.UTF_8));
            return UpstreamParityBench.hex(phase.digest());
        }
    }
}
