/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.bench;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.AiCache;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlClient;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlTape;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IdScope;
import forge.util.MyRandom;

/**
 * The RL actor runner (lane rl-r0-b1-1005; GOAL rev 5, R0 Phase A): game threads that play Forge games whose bridged
 * seats are answered by {@link RlSeat} over {@code mtgx-rl-wire/1} (interfaces.md §2), one TCP connection per game
 * thread. Nothing else uses this class; it changes no existing behaviour.
 *
 * <p>Modes: {@code train} (RL seats vs the server's policy slots, privileged block when GAME says so), {@code eval}
 * (EVAL decks only, never a privileged block), {@code record} (Forge Default on the recorder seats, Forge's answers
 * sent as RECORD frames), {@code replay} (a tape's line(s) replayed without a server; digests compared).
 *
 * <p>Games are set up exactly as {@code RlSimBench} sets up a {@code policy:"forge"} game (same lobby seats, rules,
 * per-thread random stream seeded with the GAME seed, id scope, AI-cache scope, AI timeout), so a record game and an
 * RlSimBench Forge game on the same seed and decks give the same digest (the do-no-harm check).
 *
 * <p>Usage: {@code java -cp forge.jar forge.bench.RlActorBench <cfg.json>} or
 * {@code ... RlActorBench --mode replay --tape <file> --line <n> [--card-index <tsv>] [--rl-root <dir>] [--out <f>]};
 * cwd = forge-gui with res/. Config keys: {@code mode, server, actorId, threads, rlRoot, cardIndex, tapesDir,
 * capActions (40), gameTimeoutSec (300), maxDecisions (3000), jarSha, aiTimeoutSec (5), readTimeoutSec (120),
 * tapeRotate (1000), maxGames (0 = until stop), replay {tapes, lines [[fileIdx, line]], out}}.
 * Exit codes: 0 done; 2 config / HELLO refused / deck guard; 3 a thread died on a transport or protocol error;
 * 4 replay finished with an unequal digest.
 */
public final class RlActorBench {

    private RlActorBench() {
    }

    // ------------------------------------------------------------------------------------------------ config

    static final class Cfg {
        String mode = "train";
        String server = null;
        String actorId = "a00";
        int threads = 1;
        String rlRoot = System.getProperty("user.home") + "/mtgx-data/rl";
        String cardIndex = null;
        String tapesDir = null;
        int capActions = 40;
        int gameTimeoutSec = 300;
        int maxDecisions = 3000;
        String jarSha = null;
        int aiTimeoutSec = 5;
        int readTimeoutSec = 120;
        int tapeRotate = 1000;
        int maxGames = 0;
        /** MyRandom's global (non-thread) random, seeded once at boot; RlSimBench seeds it with its config seed. */
        long globalSeed = 0x5eedL;
        List<String> replayTapes = new ArrayList<>();
        List<int[]> replayLines = new ArrayList<>();
        String replayOut = null;
    }

    static Cfg parse(final JsonObject o) {
        final Cfg c = new Cfg();
        if (o.has("mode")) c.mode = o.get("mode").getAsString();
        if (o.has("server") && !o.get("server").isJsonNull()) c.server = o.get("server").getAsString();
        if (o.has("actorId")) c.actorId = o.get("actorId").getAsString();
        if (o.has("threads")) c.threads = Math.max(1, o.get("threads").getAsInt());
        if (o.has("rlRoot")) c.rlRoot = o.get("rlRoot").getAsString();
        if (o.has("cardIndex")) c.cardIndex = o.get("cardIndex").getAsString();
        if (o.has("tapesDir")) c.tapesDir = o.get("tapesDir").getAsString();
        if (o.has("capActions")) c.capActions = o.get("capActions").getAsInt();
        if (o.has("gameTimeoutSec")) c.gameTimeoutSec = o.get("gameTimeoutSec").getAsInt();
        if (o.has("maxDecisions")) c.maxDecisions = o.get("maxDecisions").getAsInt();
        if (o.has("jarSha")) c.jarSha = o.get("jarSha").getAsString();
        if (o.has("aiTimeoutSec")) c.aiTimeoutSec = o.get("aiTimeoutSec").getAsInt();
        if (o.has("readTimeoutSec")) c.readTimeoutSec = o.get("readTimeoutSec").getAsInt();
        if (o.has("tapeRotate")) c.tapeRotate = o.get("tapeRotate").getAsInt();
        if (o.has("maxGames")) c.maxGames = o.get("maxGames").getAsInt();
        if (o.has("globalSeed")) c.globalSeed = o.get("globalSeed").getAsLong();
        if (o.has("replay") && o.get("replay").isJsonObject()) {
            final JsonObject r = o.getAsJsonObject("replay");
            if (r.has("tapes")) for (JsonElement e : r.getAsJsonArray("tapes")) c.replayTapes.add(e.getAsString());
            if (r.has("lines")) {
                for (JsonElement e : r.getAsJsonArray("lines")) {
                    final JsonArray a = e.getAsJsonArray();
                    c.replayLines.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
                }
            }
            if (r.has("out")) c.replayOut = r.get("out").getAsString();
        }
        return c;
    }

    static Cfg parseArgs(final String[] args) throws IOException {
        if (args.length == 1 && !args[0].startsWith("--")) {
            return parse(JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[0])),
                    StandardCharsets.UTF_8)).getAsJsonObject());
        }
        final Cfg c = new Cfg();
        String tape = null;
        int line = 0;
        for (int i = 0; i + 1 < args.length; i += 2) {
            final String k = args[i], v = args[i + 1];
            switch (k) {
                case "--mode": c.mode = v; break;
                case "--tape": tape = v; break;
                case "--line": line = Integer.parseInt(v); break;
                case "--card-index": c.cardIndex = v; break;
                case "--rl-root": c.rlRoot = v; break;
                case "--out": c.replayOut = v; break;
                case "--ai-timeout": c.aiTimeoutSec = Integer.parseInt(v); break;
                default: throw new IllegalArgumentException("unknown argument " + k);
            }
        }
        if (tape != null) {
            c.replayTapes.add(tape);
            c.replayLines.add(new int[] {0, line});
        }
        return c;
    }

    // ------------------------------------------------------------------------------------------------ deck guard

    /** §8 (Java side). Null when the deck may be loaded in this mode, else the refusal. */
    static String deckGuard(final String mode, final String rlRoot, final String path, final String sha) {
        final boolean eval = "eval".equals(mode);
        try {
            final Path root = Paths.get(rlRoot, "data", eval ? "eval" : "train").toRealPath();
            final Path given = Paths.get(path);
            if (!given.isAbsolute()) {
                return "deck path is not absolute: " + path;
            }
            final Path real = given.toRealPath();
            if (!real.startsWith(root)) {
                return "deck " + path + " resolves outside " + root + " (" + real + ")";
            }
            if (!eval) {
                for (Path p : new Path[] {given, real}) {
                    for (Path part : p) {
                        if ("eval".equals(part.toString())) {
                            return "deck path has an 'eval' component: " + p;
                        }
                    }
                }
            }
            final String fileSha = CardIndex.sha256(Files.readAllBytes(real));
            if (sha == null || !sha.equals(fileSha)) {
                return "deck sha mismatch for " + path + ": GAME says " + sha + ", file is " + fileSha;
            }
            final Path rel = root.relativize(real);
            if (rel.getNameCount() < 2) {
                return "deck " + path + " is not inside a bank directory";
            }
            final Path manifest = root.resolve(rel.getName(0)).resolve("MANIFEST.json");
            final JsonObject m = manifest(manifest);
            final String use = m.has("use") ? m.get("use").getAsString() : null;
            if (!(eval ? "eval" : "train").equals(use)) {
                return "bank manifest " + manifest + " has use " + use;
            }
            boolean listed = false;
            for (JsonElement d : m.getAsJsonArray("decks")) {
                if (fileSha.equals(d.getAsJsonObject().get("sha256").getAsString())) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                return "deck sha " + fileSha + " is not in " + manifest;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "deck guard could not check " + path + ": " + e;
        }
    }

    private static final Map<Path, JsonObject> MANIFESTS = new ConcurrentHashMap<>();

    private static JsonObject manifest(final Path p) throws IOException {
        JsonObject m = MANIFESTS.get(p);
        if (m == null) {
            m = JsonParser.parseString(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).getAsJsonObject();
            MANIFESTS.put(p, m);
        }
        return m;
    }

    static final Map<String, Deck> DECKS = new ConcurrentHashMap<>();

    static Deck deck(final String path) {
        return DECKS.computeIfAbsent(path, p -> {
            final Deck d = DeckSerializer.fromFile(new File(p));
            if (d == null) {
                throw new IllegalArgumentException("could not parse deck " + p);
            }
            return d;
        });
    }

    // ------------------------------------------------------------------------------------------------ one game

    /** The outcome of one played game. */
    static final class Played {
        JsonObject end;       // GAME_END
        JsonObject tape;      // tape line
        String guardError;    // deck guard refusal (nothing played)
        String fatal;         // the connection is unusable
        RlSeat seat;
    }

    static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    static final Map<Thread, Object[]> RUNNING = new ConcurrentHashMap<>(); // thread -> {game, deadlineMs, uid}
    static final Map<Long, Boolean> TIMED_OUT = new ConcurrentHashMap<>();

    /**
     * Play one GAME message. {@code ep} answers the RL seats (a server connection, or a tape in replay).
     * {@code listener} (tests) observes every sent frame.
     */
    static Played play(final Cfg cfg, final String mode, final JsonObject g, final RlFeaturizer feat,
            final RlSeat.Endpoint ep, final RlSeat.FrameListener listener, final String jarSha) {
        final Played out = new Played();
        final long uid = RlWire.parseUid(g.get("game_uid").getAsString());
        final long seed = g.get("seed").getAsLong();
        final JsonArray decksJ = g.getAsJsonArray("decks");
        final JsonArray shaJ = g.getAsJsonArray("deck_sha");
        final JsonArray ctlJ = g.getAsJsonArray("controllers");
        final String[] decks = {decksJ.get(0).getAsString(), decksJ.get(1).getAsString()};
        final String[] shas = {shaJ.get(0).getAsString(), shaJ.get(1).getAsString()};
        final String[] ctl = {ctlJ.get(0).getAsString(), ctlJ.get(1).getAsString()};
        final boolean trainSide = "train".equals(mode) || "record".equals(mode);
        final boolean priv = trainSide && g.has("priv") && g.get("priv").getAsBoolean();
        for (int s = 0; s < 2; s++) {
            final String why = deckGuard(mode, cfg.rlRoot, decks[s], shas[s]);
            if (why != null) {
                out.guardError = why;
                return out;
            }
            final RlSeat.Role r;
            try {
                r = RlSeat.roleOf(ctl[s]);
            } catch (IllegalArgumentException e) {
                out.guardError = "controller " + ctl[s] + ": " + e.getMessage();
                return out;
            }
            final boolean okRole = "record".equals(mode) ? r != RlSeat.Role.RL
                    : "replay".equals(mode) || r != RlSeat.Role.RECORD;
            if (!okRole) {
                out.guardError = "controller " + ctl[s] + " is not allowed in " + mode + " mode";
                return out;
            }
        }

        final JsonRpcChannel ch = new JsonRpcChannel(InputStream.nullInputStream(), OutputStream.nullOutputStream());
        final BenchSession session = new BenchSession(ch);
        session.setEncodeState(false);
        final RlSeat.Config sc = new RlSeat.Config();
        sc.mode = mode;
        sc.capActions = cfg.capActions;
        sc.maxDecisions = cfg.maxDecisions;
        final RlSeat seat = new RlSeat(feat, ep, sc, uid, ctl, priv);
        seat.listener = listener;
        out.seat = seat;
        boolean anyBridged = false;
        final List<RegisteredPlayer> seats = new ArrayList<>();
        final List<LobbyPlayerBridge> lps = new ArrayList<>();
        for (int s = 0; s < 2; s++) {
            final boolean bridged = RlSeat.roleOf(ctl[s]) != RlSeat.Role.FORGE;
            anyBridged |= bridged;
            final LobbyPlayerBridge lp = new LobbyPlayerBridge("Seat" + s, null, session,
                    bridged ? BenchSession.Mode.BRIDGE : BenchSession.Mode.NULL, s);
            lp.setAiProfile("Default");
            lps.add(lp);
            final RegisteredPlayer rp = new RegisteredPlayer(deck(decks[s]));
            rp.setPlayer(lp);
            seats.add(rp);
        }
        if (anyBridged) {
            session.setLocalAnswerer(seat);
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setSimTimeout(cfg.gameTimeoutSec);
        final Match match = new Match(rules, seats, "rl-sim");

        MyRandom.setThreadRandom(new Random(seed));
        IdScope.open();
        AiCache.openScope();
        feat.reset();
        final long cpu0 = TMX.getCurrentThreadCpuTime();
        final long t0 = System.nanoTime();
        Game game = null;
        RlSimBench.Digest dg = null;
        String crash = null;
        try {
            game = match.createGame();
            game.AI_TIMEOUT = cfg.aiTimeoutSec;
            session.setLiveGame(game);
            seat.setGame(game);
            dg = new RlSimBench.Digest(game);
            game.subscribeToEvents(dg);
            RUNNING.put(Thread.currentThread(), new Object[] {game,
                    System.currentTimeMillis() + cfg.gameTimeoutSec * 1000L, uid});
            match.startGame(game, null);
        } catch (Throwable e) {
            crash = e.toString();
            System.err.println("[rlactor] game " + Long.toUnsignedString(uid) + " crashed: " + e);
            final JsonArray fr = RlSimBench.forgeFrames(e);
            for (JsonElement f : fr) {
                System.err.println("[rlactor]   " + f.getAsString());
            }
        } finally {
            RUNNING.remove(Thread.currentThread());
            if (game != null && !game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        }
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;
        final long cpuMs = (TMX.getCurrentThreadCpuTime() - cpu0) / 1_000_000L;
        String voidReason = null;
        if (TIMED_OUT.remove(uid) != null) {
            voidReason = "timeout";
        } else if (seat.voidReason != null) {
            voidReason = seat.voidReason;
        } else if (crash != null || game == null) {
            voidReason = "crash";
        }
        out.fatal = seat.fatal;

        final List<String> fps = new ArrayList<>(dg == null ? Collections.emptyList() : dg.fps);
        fps.add(game == null ? "none" : RlSimBench.outcomeText(game));
        final String digest = RlSimBench.sha16(fps);
        final int[] result = {0, 0};
        String reason = voidReason == null ? "draw" : voidReason;
        if (voidReason == null && game != null && game.getOutcome() != null && !game.getOutcome().isDraw()) {
            final LobbyPlayer w = game.getOutcome().getWinningLobbyPlayer();
            final int ws = w instanceof LobbyPlayerBridge ? ((LobbyPlayerBridge) w).getSeat() : -1;
            if (ws == 0 || ws == 1) {
                result[ws] = 1;
                result[1 - ws] = -1;
            }
            reason = lossReason(game);
        }
        final int turns = game == null ? 0 : game.getPhaseHandler().getTurn();
        int startingSeat = -1;
        if (game != null && game.getStartingPlayer() != null) {
            startingSeat = game.getRegisteredPlayers().indexOf(game.getStartingPlayer());
        }

        // forge_decided: every controller call of a bridged seat that the seat did not answer, plus the caps
        final Map<String, Integer> forgeDecided = new TreeMap<>();
        for (int s = 0; s < 2; s++) {
            if (RlSeat.roleOf(ctl[s]) == RlSeat.Role.FORGE) {
                continue;
            }
            final JsonObject cj = lps.get(s).getCounters().toJson();
            if (cj.has("calls") && cj.get("calls").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : cj.getAsJsonObject("calls").entrySet()) {
                    forgeDecided.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> e : seat.okAnswers.entrySet()) {
            forgeDecided.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        forgeDecided.values().removeIf(v -> v <= 0);
        forgeDecided.keySet().removeAll(NOT_DECISIONS);
        forgeDecided.putAll(seat.forgeDecidedExtra);

        final JsonObject end = new JsonObject();
        end.addProperty("game_uid", Long.toUnsignedString(uid));
        end.add("result", RlWire.intArray(result));
        if (voidReason == null) {
            end.add("void", com.google.gson.JsonNull.INSTANCE);
        } else {
            end.addProperty("void", voidReason);
        }
        end.addProperty("reason", reason);
        end.addProperty("turns", turns);
        end.add("decisions", RlWire.intArray(seat.decisions(0), seat.decisions(1)));
        final JsonArray ov = new JsonArray();
        for (int d : seat.overridden) ov.add(d);
        end.add("overridden", ov);
        end.addProperty("cap_hits", seat.capHits);
        end.addProperty("digest", digest);
        end.addProperty("cpu_ms", cpuMs);
        end.addProperty("wall_ms", wallMs);
        out.end = end;

        final JsonObject t = new JsonObject();
        t.addProperty("schema", RlTape.SCHEMA);
        t.addProperty("game_uid", Long.toUnsignedString(uid));
        t.addProperty("seed", seed);
        t.addProperty("mode", mode);
        final JsonArray dj = new JsonArray();
        for (int s = 0; s < 2; s++) {
            final JsonObject d = new JsonObject();
            d.addProperty("path", decks[s]);
            d.addProperty("sha", shas[s]);
            dj.add(d);
        }
        t.add("decks", dj);
        t.add("controllers", ctlJ.deepCopy());
        t.addProperty("starting_seat", startingSeat);
        t.addProperty("jar_sha", jarSha);
        t.addProperty("schema_sha", RlSchema.schemaSha());
        t.addProperty("card_index_sha", feat.index().sha());
        t.add("result", end.get("result"));
        t.add("void", end.get("void"));
        t.addProperty("reason", reason);
        t.addProperty("turns", turns);
        t.addProperty("digest", digest);
        t.addProperty("cap_hits", seat.capHits);
        final JsonObject fd = new JsonObject();
        for (Map.Entry<String, Integer> e : forgeDecided.entrySet()) fd.addProperty(e.getKey(), e.getValue());
        t.add("forge_decided", fd);
        final JsonObject census = seat.censusJson();
        t.add("census", census);
        t.add("overridden", ov);
        t.add("versions", seat.versions);
        final JsonArray decRows = new JsonArray();
        for (JsonArray r : seat.dec) decRows.add(r);
        t.add("dec", decRows);
        t.addProperty("cpu_ms", cpuMs);
        t.addProperty("wall_ms", wallMs);
        out.tape = t;

        AiCache.closeScope();
        IdScope.close();
        MyRandom.setThreadRandom(null);
        return out;
    }

    /** Controller entry points that notify or plumb rather than decide; left out of forge_decided. */
    static final java.util.Set<String> NOT_DECISIONS = new java.util.HashSet<>(java.util.Arrays.asList("reveal",
            "notifyOfValue", "revealAnte", "revealAISkipCards", "revealUnsupported", "autoPassCancel",
            "awaitNextInput", "cancelAwaitNextInput", "getCostDecisionMaker", "playChosenSpellAbility"));

    static String lossReason(final Game game) {
        for (Player p : game.getRegisteredPlayers()) {
            final PlayerOutcome po = p.getOutcome();
            if (po != null && po.lossState != null) {
                final GameLossReason r = po.lossState;
                switch (r) {
                    case LifeReachedZero: return "life";
                    case Milled: return "library";
                    case Poisoned: return "poison";
                    case Conceded: return "concede";
                    default: return r.name().toLowerCase();
                }
            }
        }
        final GameOutcome go = game.getOutcome();
        return go == null ? "none" : String.valueOf(go.getWinCondition()).toLowerCase();
    }

    // ------------------------------------------------------------------------------------------------ live endpoint

    static RlSeat.Endpoint endpoint(final RlClient cl) {
        return new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                return cl.decide(p);
            }

            @Override
            public void record(final byte[] p) throws IOException {
                cl.record(p);
            }
        };
    }

    /** Replay: RL seats answer from the tape's {@code dec} rows, asserting family and C at each dec_idx. */
    static RlSeat.Endpoint tapeEndpoint(final JsonObject line, final long uid) {
        final Map<Integer, JsonArray> rows = new HashMap<>();
        for (JsonElement e : line.getAsJsonArray("dec")) {
            final JsonArray r = e.getAsJsonArray();
            rows.put(r.get(0).getAsInt(), r);
        }
        return new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                final RlWire.Decide d = RlWire.decodeDecide(p);
                final JsonArray r = rows.get(d.decIdx);
                if (r == null) {
                    throw new RlClient.ServerError("replay", "no tape row for dec " + d.decIdx);
                }
                if (r.get(2).getAsInt() != d.family || r.get(3).getAsInt() != d.C) {
                    throw new RlClient.ServerError("replay", "dec " + d.decIdx + ": tape family/C " + r.get(2) + "/"
                            + r.get(3) + ", replay " + d.family + "/" + d.C);
                }
                final JsonArray st = r.get(4).getAsJsonArray();
                final RlWire.Decision x = new RlWire.Decision();
                x.gameUid = uid;
                x.decIdx = d.decIdx;
                x.status = RlWire.ST_OK;
                x.steps = new short[st.size()];
                for (int i = 0; i < st.size(); i++) x.steps[i] = (short) st.get(i).getAsInt();
                return x;
            }

            @Override
            public void record(final byte[] p) {
                // a record tape replays with Forge on the recorder seats; the frames go nowhere
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ main

    static final AtomicInteger EXIT = new AtomicInteger(0);
    static final AtomicLong GAMES = new AtomicLong(), VOIDS = new AtomicLong(), SENT = new AtomicLong(),
            CAPPED_GAMES = new AtomicLong(), OVERRIDDEN = new AtomicLong();
    static final Map<String, AtomicLong> VOID_BY = new ConcurrentHashMap<>();

    static void boot() {
        boot(0x5eedL);
    }

    static void boot(final long globalSeed) {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        MyRandom.setRandom(new Random(globalSeed));
    }

    static String jarSha(final Cfg cfg) {
        if (cfg.jarSha != null) {
            return cfg.jarSha;
        }
        try {
            final java.net.URL u = RlActorBench.class.getProtectionDomain().getCodeSource().getLocation();
            final Path p = Paths.get(u.toURI());
            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                return CardIndex.sha256(Files.readAllBytes(p));
            }
        } catch (Exception e) {
            // fall through
        }
        return "unknown";
    }

    public static void main(final String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        final Cfg cfg;
        try {
            cfg = parseArgs(args);
            if (cfg.cardIndex == null) {
                cfg.cardIndex = cfg.rlRoot + "/data/card-index.tsv";
            }
        } catch (RuntimeException e) {
            System.err.println("[rlactor] bad config: " + e);
            System.exit(2);
            return;
        }
        System.exit(run(cfg));
    }

    /** The whole actor run; returns the exit code. Boots Forge once per JVM. */
    public static int run(final Cfg cfg) throws Exception {
        ensureBooted(cfg.globalSeed);
        final CardIndex index = CardIndex.load(Paths.get(cfg.cardIndex));
        final String jarSha = jarSha(cfg);
        if ("replay".equals(cfg.mode)) {
            return replay(cfg, index, jarSha);
        }
        if (!"train".equals(cfg.mode) && !"eval".equals(cfg.mode) && !"record".equals(cfg.mode)) {
            System.err.println("[rlactor] unknown mode " + cfg.mode);
            return 2;
        }
        if (cfg.server == null) {
            System.err.println("[rlactor] no server");
            return 2;
        }
        final Path tapesDir = Paths.get(cfg.tapesDir != null ? cfg.tapesDir : "tapes/" + cfg.actorId);
        final RlTape tape = new RlTape(tapesDir, cfg.tapeRotate);
        EXIT.set(0);
        final long w0 = System.nanoTime();
        final com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long pcpu0 = os.getProcessCpuTime();
        final List<Thread> workers = new ArrayList<>();
        for (int k = 0; k < cfg.threads; k++) {
            final int thread = k;
            final Thread t = new Thread(() -> worker(cfg, thread, index, tape, jarSha), "rlactor-w" + k);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        final int hung = watchdog(workers, cfg);
        tape.close();
        final JsonObject s = new JsonObject();
        s.addProperty("type", "summary");
        s.addProperty("mode", cfg.mode);
        s.addProperty("actor_id", cfg.actorId);
        s.addProperty("threads", cfg.threads);
        s.addProperty("games", GAMES.get());
        s.addProperty("voids", VOIDS.get());
        final JsonObject vb = new JsonObject();
        for (Map.Entry<String, AtomicLong> e : VOID_BY.entrySet()) vb.addProperty(e.getKey(), e.getValue().get());
        s.add("void_by", vb);
        s.addProperty("sent", SENT.get());
        s.addProperty("capped_games", CAPPED_GAMES.get());
        s.addProperty("overridden", OVERRIDDEN.get());
        s.addProperty("hung", hung);
        s.addProperty("wall_s", (System.nanoTime() - w0) / 1e9);
        s.addProperty("process_cpu_s", (os.getProcessCpuTime() - pcpu0) / 1e9);
        s.addProperty("unknown_card_lookups", index.unknownLookups());
        final JsonArray unk = new JsonArray();
        final List<String> un = new ArrayList<>(index.unknownNames());
        Collections.sort(un);
        for (String n : un.subList(0, Math.min(200, un.size()))) unk.add(n);
        s.add("unknown_names", unk);
        s.addProperty("exit", hung > 0 ? 3 : EXIT.get());
        System.err.println("[rlactor] " + s);
        try (PrintWriter w = new PrintWriter(new FileWriter(tapesDir.resolve("summary-" + ProcessHandle.current().pid()
                + ".json").toFile(), StandardCharsets.UTF_8))) {
            w.println(RlWire.canonicalString(s));
        }
        return hung > 0 ? 3 : EXIT.get();
    }

    private static volatile boolean booted = false;

    /** Boot Forge once per JVM (card database, preferences). */
    static synchronized void ensureBooted() {
        ensureBooted(0x5eedL);
    }

    static synchronized void ensureBooted(final long globalSeed) {
        if (!booted) {
            boot(globalSeed);
            booted = true;
        }
    }
    /** Tests only (goldens, visibility): observes every sent frame of every game. Null by default. */
    static volatile RlSeat.FrameListener LISTENER = null;

    static void worker(final Cfg cfg, final int thread, final CardIndex index, final RlTape tape, final String jarSha) {
        final RlFeaturizer feat = new RlFeaturizer(index);
        RlClient cl = null;
        try {
            cl = RlClient.connect(cfg.server, 10_000, cfg.readTimeoutSec * 1000);
            final JsonObject hello = new JsonObject();
            hello.addProperty("proto", RlWire.PROTO);
            hello.addProperty("schema_sha", RlSchema.schemaSha());
            hello.addProperty("card_index_sha", index.sha());
            hello.addProperty("jar_sha", jarSha);
            hello.addProperty("actor_id", cfg.actorId);
            hello.addProperty("thread", thread);
            hello.addProperty("mode", cfg.mode);
            hello.addProperty("pid", ProcessHandle.current().pid());
            try {
                cl.hello(hello);
            } catch (RlClient.ServerError e) {
                System.err.println("[rlactor] w" + thread + " HELLO refused: " + e.getMessage());
                EXIT.compareAndSet(0, 2);
                return;
            }
            int played = 0;
            while (cfg.maxGames <= 0 || played < cfg.maxGames) {
                final JsonObject g = cl.nextGame(cfg.actorId, thread);
                if (g.has("stop") && g.get("stop").getAsBoolean()) {
                    break;
                }
                if (g.has("wait_ms")) {
                    Thread.sleep(Math.max(1, g.get("wait_ms").getAsLong()));
                    continue;
                }
                final RlClient conn = cl;
                final Played p = play(cfg, cfg.mode, g, feat, endpoint(conn), LISTENER, jarSha);
                if (p.guardError != null) {
                    System.err.println("[rlactor] w" + thread + " refusing GAME: " + p.guardError);
                    cl.error("deck_guard", p.guardError);
                    EXIT.compareAndSet(0, 2);
                    return;
                }
                played++;
                GAMES.incrementAndGet();
                SENT.addAndGet(p.seat.totalSent());
                OVERRIDDEN.addAndGet(p.seat.overridden.size());
                if (p.seat.capHits > 0) {
                    CAPPED_GAMES.incrementAndGet();
                }
                if (!p.end.get("void").isJsonNull()) {
                    VOIDS.incrementAndGet();
                    VOID_BY.computeIfAbsent(p.end.get("void").getAsString(), k -> new AtomicLong()).incrementAndGet();
                }
                tape.write(p.tape);
                if (p.fatal != null) {
                    System.err.println("[rlactor] w" + thread + " connection unusable: " + p.fatal);
                    EXIT.compareAndSet(0, 3);
                    return;
                }
                cl.gameEnd(p.end);
            }
        } catch (Exception e) {
            System.err.println("[rlactor] w" + thread + " stopped: " + e);
            EXIT.compareAndSet(0, 3);
        } finally {
            if (cl != null) {
                cl.close();
            }
        }
    }

    /** Ends games past their deadline; returns the number of games that ignored it for 60 s (hangs). */
    static int watchdog(final List<Thread> workers, final Cfg cfg) throws InterruptedException {
        final Map<Thread, Long> stuckSince = new HashMap<>();
        while (true) {
            boolean alive = false;
            for (Thread t : workers) alive |= t.isAlive();
            if (!alive) {
                return 0;
            }
            Thread.sleep(500);
            final long now = System.currentTimeMillis();
            for (Map.Entry<Thread, Object[]> e : RUNNING.entrySet()) {
                final Object[] r = e.getValue();
                if (now > (Long) r[1]) {
                    TIMED_OUT.put((Long) r[2], Boolean.TRUE);
                    ((Game) r[0]).setGameOver(GameEndReason.Draw);
                    final long since = stuckSince.computeIfAbsent(e.getKey(), x -> now);
                    if (now - since > 60_000L) {
                        System.err.println("[rlactor] game " + Long.toUnsignedString((Long) r[2])
                                + " ignored its timeout for 60 s: hung");
                        return 1;
                    }
                } else {
                    stuckSince.remove(e.getKey());
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ replay

    static int replay(final Cfg cfg, final CardIndex index, final String jarSha) throws IOException {
        final RlFeaturizer feat = new RlFeaturizer(index);
        final Map<String, List<JsonObject>> cache = new HashMap<>();
        PrintWriter w = null;
        if (cfg.replayOut != null) {
            final Path o = Paths.get(cfg.replayOut);
            if (o.getParent() != null) {
                Files.createDirectories(o.getParent());
            }
            w = new PrintWriter(new FileWriter(o.toFile(), StandardCharsets.UTF_8, true), true);
        }
        int unequal = 0;
        for (int[] fl : cfg.replayLines) {
            final String f = cfg.replayTapes.get(fl[0]);
            final JsonObject row = new JsonObject();
            row.addProperty("tape", f);
            row.addProperty("line", fl[1]);
            try {
                final List<JsonObject> lines = cache.computeIfAbsent(f, k -> {
                    try {
                        return RlTape.read(Paths.get(k));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                final JsonObject t = lines.get(fl[1]);
                row.add("game_uid", t.get("game_uid"));
                row.add("digest_tape", t.get("digest"));
                if (!t.get("void").isJsonNull()) {
                    row.addProperty("error", "void");
                    row.addProperty("equal", false);
                } else {
                    final JsonObject g = new JsonObject();
                    g.add("game_uid", t.get("game_uid"));
                    g.add("seed", t.get("seed"));
                    final JsonArray decks = new JsonArray(), shas = new JsonArray();
                    for (JsonElement d : t.getAsJsonArray("decks")) {
                        decks.add(d.getAsJsonObject().get("path"));
                        shas.add(d.getAsJsonObject().get("sha"));
                    }
                    g.add("decks", decks);
                    g.add("deck_sha", shas);
                    g.add("controllers", t.get("controllers"));
                    g.addProperty("priv", false);
                    // the deck guard of the tape's own mode; the seat's behaviour is the tape mode's
                    final String tmode = t.get("mode").getAsString();
                    final Played p = play(cfg, tmode, g, feat, tapeEndpoint(t, RlWire.parseUid(
                            t.get("game_uid").getAsString())), null, jarSha);
                    if (p.guardError != null) {
                        row.addProperty("error", "deck_guard: " + p.guardError);
                        row.addProperty("equal", false);
                    } else {
                        row.add("digest_replay", p.end.get("digest"));
                        final boolean eq = p.end.get("digest").getAsString().equals(t.get("digest").getAsString())
                                && p.end.get("void").isJsonNull();
                        row.addProperty("equal", eq);
                        if (p.seat.fatal != null) {
                            row.addProperty("error", p.seat.fatal);
                        }
                    }
                }
            } catch (RuntimeException e) {
                row.addProperty("error", e.toString());
                row.addProperty("equal", false);
            }
            if (!row.get("equal").getAsBoolean()) {
                unequal++;
            }
            final String s = RlWire.canonicalString(row);
            System.err.println("[rlactor] replay " + s);
            if (w != null) {
                w.println(s);
            }
        }
        if (w != null) {
            w.close();
        }
        return unequal > 0 ? 4 : 0;
    }
}
