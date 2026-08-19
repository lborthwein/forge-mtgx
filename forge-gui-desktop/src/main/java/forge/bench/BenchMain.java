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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.BuildInfo;
import forge.util.MyRandom;
import forge.view.TimeLimitedCodeBlock;

/**
 * Headless entry point for the mtgx play benchmark (wire protocol v1).
 *
 * <p>Reads a single JSON config line from stdin, then speaks the protocol on stdout:
 * {@code hello}, then per game a stream of {@code event} and {@code ask} lines and one
 * {@code result}. Forge's own {@code System.out} chatter is redirected to stderr the
 * moment the process starts, so stdout carries protocol only.
 *
 * <p>Config:
 * <pre>
 * {"decks":["/abs/a.dck","/abs/b.dck"], "games":3, "seed":123,
 *  "seats":{"0":"bridge","1":"forge"}, "aiProfile":"Default",
 *  "useSimulation":false, "timeoutSec":120}
 * </pre>
 */
public final class BenchMain {

    private BenchMain() {
    }

    public static void main(final String[] args) {
        // Grab the real stdout for the protocol, then point System.out at stderr so no
        // Forge println can corrupt a protocol line.
        final PrintStream protocolOut =
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        final JsonRpcChannel ch = new JsonRpcChannel(System.in, protocolOut);

        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        System.setProperty("sun.java2d.d3d", "false");

        JsonObject cfg;
        try {
            cfg = ch.readLine();
        } catch (Exception e) {
            JsonRpcChannel.logErr("could not read config line", e);
            System.exit(2);
            return;
        }
        if (cfg == null) {
            JsonRpcChannel.log("no config line on stdin; nothing to do");
            System.exit(2);
            return;
        }

        final long seed = cfg.has("seed") ? cfg.get("seed").getAsLong() : 0L;
        final int games = cfg.has("games") ? cfg.get("games").getAsInt() : 1;
        final int timeoutSec = cfg.has("timeoutSec") ? cfg.get("timeoutSec").getAsInt() : 120;
        final boolean useSimulation = cfg.has("useSimulation") && cfg.get("useSimulation").getAsBoolean();
        final String aiProfile = cfg.has("aiProfile") ? cfg.get("aiProfile").getAsString() : "Default";

        final List<String> deckPaths = new ArrayList<>();
        if (cfg.has("decks")) {
            for (JsonElement e : cfg.getAsJsonArray("decks")) {
                deckPaths.add(e.getAsString());
            }
        }
        if (deckPaths.size() < 2) {
            JsonRpcChannel.log("need at least 2 decks in config.decks");
            System.exit(2);
            return;
        }

        // ---------------------------------------------------------------- boot Forge
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        MyRandom.setRandom(new Random(seed));

        final BenchSession session = new BenchSession(ch);

        final JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", JsonRpcChannel.PROTOCOL_VERSION);
        hello.addProperty("forgeCommit", forgeCommit());
        hello.addProperty("forgeVersion", BuildInfo.getVersionString());
        hello.addProperty("aiProfile", aiProfile);
        hello.addProperty("seed", seed);
        hello.addProperty("games", games);
        hello.addProperty("useSimulation", useSimulation);
        hello.addProperty("sequentialAi", Boolean.getBoolean("forge.bench.sequentialAi"));
        ch.send(hello);

        // ------------------------------------------------------------- register seats
        final Set<AIOption> options = useSimulation
                ? Sets.newHashSet(AIOption.USE_FULL_SIMULATION) : null;

        final List<RegisteredPlayer> seats = new ArrayList<>();
        final List<LobbyPlayerBridge> bridged = new ArrayList<>();
        for (int i = 0; i < deckPaths.size(); i++) {
            final File f = new File(deckPaths.get(i));
            if (!f.isFile()) {
                JsonRpcChannel.log("deck not found: " + f.getAbsolutePath());
                System.exit(2);
                return;
            }
            final Deck deck = DeckSerializer.fromFile(f);
            if (deck == null) {
                JsonRpcChannel.log("could not parse deck: " + f.getAbsolutePath());
                System.exit(2);
                return;
            }
            final String modeStr = seatMode(cfg, i);
            final String name = "Seat" + i + "-" + deck.getName();
            final LobbyPlayer lp;
            if ("forge".equalsIgnoreCase(modeStr)) {
                lp = GamePlayerUtil.createAiPlayer(name, i, 0, options, aiProfile);
            } else {
                final LobbyPlayerBridge b = new LobbyPlayerBridge(name, options, session,
                        BenchSession.Mode.parse(modeStr), i);
                b.setAiProfile(aiProfile);
                bridged.add(b);
                lp = b;
            }
            final RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(lp);
            seats.add(rp);
            JsonRpcChannel.log("seat " + i + " = " + modeStr + " / " + f.getName()
                    + " (" + deck.getMain().countAll() + " main)");
        }

        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setSimTimeout(timeoutSec);

        final Match match = new Match(rules, seats, "mtgx-bench");

        // -------------------------------------------------------------------- play
        for (int iGame = 0; iGame < games; iGame++) {
            final String gameId = "g" + (iGame + 1);
            session.setGameId(gameId);
            for (LobbyPlayerBridge b : bridged) {
                b.getCounters().reset();
            }
            // Fresh, reproducible RNG per game (the harness pairs seeds across seats).
            MyRandom.setRandom(new Random(seed + iGame));

            final long t0 = System.currentTimeMillis();
            final Game game = match.createGame();
            game.AI_CAN_USE_TIMEOUT = false;
            final EventEmitter emitter = new EventEmitter(ch, gameId);
            game.subscribeToEvents(emitter);

            String abort = null;
            try {
                TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), timeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                abort = "timeout";
                JsonRpcChannel.log(gameId + ": sim timeout after " + timeoutSec + "s, scoring as a draw");
            } catch (Exception | StackOverflowError e) {
                abort = "exception: " + e;
                JsonRpcChannel.logErr(gameId + ": game threw", e instanceof Throwable ? e : new RuntimeException("?"));
            } finally {
                if (!game.isGameOver()) {
                    game.setGameOver(GameEndReason.Draw);
                }
            }
            final long wallMs = System.currentTimeMillis() - t0;

            final JsonObject result = new JsonObject();
            result.addProperty("type", "result");
            result.addProperty("game", gameId);
            final JsonObject outcome = new JsonObject();
            final GameOutcome go = game.getOutcome();
            int winner = -1;
            String reason = abort != null ? abort : "unknown";
            int turns = 0;
            if (go != null) {
                turns = go.getLastTurnNumber();
                if (go.isDraw()) {
                    reason = abort != null ? abort : "draw";
                } else {
                    reason = String.valueOf(go.getWinCondition());
                    final LobbyPlayer wlp = go.getWinningLobbyPlayer();
                    for (int i = 0; i < seats.size(); i++) {
                        if (seats.get(i).getPlayer() == wlp) {
                            winner = i;
                        }
                    }
                }
            }
            outcome.addProperty("winner", winner);
            outcome.addProperty("reason", reason);
            outcome.addProperty("turns", turns);
            outcome.addProperty("wallMs", wallMs);
            outcome.addProperty("events", emitter.emitted());
            result.add("outcome", outcome);

            final JsonObject counts = new JsonObject();
            for (LobbyPlayerBridge b : bridged) {
                counts.add(String.valueOf(b.getSeat()), b.getCounters().toJson());
            }
            result.add("delegationCounts", counts);
            ch.send(result);
        }

        final JsonObject bye = new JsonObject();
        bye.addProperty("type", "bye");
        ch.send(bye);
        System.exit(0);
    }

    private static String seatMode(final JsonObject cfg, final int seat) {
        if (cfg.has("seats") && cfg.get("seats").isJsonObject()) {
            final JsonObject seatsCfg = cfg.getAsJsonObject("seats");
            if (seatsCfg.has(String.valueOf(seat))) {
                return seatsCfg.get(String.valueOf(seat)).getAsString();
            }
        }
        if (cfg.has("seats") && cfg.get("seats").isJsonArray()) {
            final JsonArray arr = cfg.getAsJsonArray("seats");
            if (seat < arr.size()) {
                return arr.get(seat).getAsString();
            }
        }
        return "null";
    }

    private static String forgeCommit() {
        final String p = System.getProperty("forge.bench.commit");
        return p == null ? "" : p;
    }

    /**
     * Turns Forge's game events into one-way {@code event} protocol lines.
     * Guava's EventBus walks the type hierarchy, so a single {@code GameEvent}
     * handler sees every posted event.
     */
    public static final class EventEmitter {
        private final JsonRpcChannel ch;
        private final String gameId;
        private int n = 0;

        EventEmitter(final JsonRpcChannel ch, final String gameId) {
            this.ch = ch;
            this.gameId = gameId;
        }

        int emitted() {
            return n;
        }

        @Subscribe
        public void receive(final GameEvent ev) {
            final JsonObject e = new JsonObject();
            if (ev instanceof GameEventTurnBegan g) {
                e.addProperty("kind", "turnBegan");
                e.addProperty("turn", g.turnNumber());
                e.addProperty("player", String.valueOf(g.turnOwner()));
            } else if (ev instanceof GameEventTurnPhase g) {
                e.addProperty("kind", "phase");
                e.addProperty("phase", String.valueOf(g.phase()));
                e.addProperty("player", String.valueOf(g.playerTurn()));
            } else if (ev instanceof GameEventSpellAbilityCast g) {
                e.addProperty("kind", "cast");
                e.addProperty("ability", String.valueOf(g.sa()));
                e.addProperty("stackIndex", g.stackIndex());
                e.addProperty("targets", String.valueOf(g.targetDescription()));
            } else if (ev instanceof GameEventLandPlayed g) {
                e.addProperty("kind", "landPlayed");
                e.addProperty("player", String.valueOf(g.player()));
                e.addProperty("card", String.valueOf(g.land()));
            } else if (ev instanceof GameEventAttackersDeclared g) {
                e.addProperty("kind", "attackersDeclared");
                e.addProperty("player", String.valueOf(g.player()));
                e.addProperty("attackers", String.valueOf(g.attackersMap()));
            } else if (ev instanceof GameEventBlockersDeclared g) {
                e.addProperty("kind", "blockersDeclared");
                e.addProperty("player", String.valueOf(g.defendingPlayer()));
                e.addProperty("blockers", String.valueOf(g.blockers()));
            } else if (ev instanceof GameEventCardChangeZone g) {
                final String from = String.valueOf(g.from());
                final String to = String.valueOf(g.to());
                if (!interestingZone(from) && !interestingZone(to)) {
                    return;
                }
                e.addProperty("kind", "zoneChange");
                e.addProperty("card", String.valueOf(g.card()));
                e.addProperty("from", from);
                e.addProperty("to", to);
            } else if (ev instanceof GameEventPlayerLivesChanged g) {
                e.addProperty("kind", "lifeChanged");
                e.addProperty("player", String.valueOf(g.player()));
                e.addProperty("from", g.oldLives());
                e.addProperty("to", g.newLives());
            } else if (ev instanceof GameEventMulligan g) {
                e.addProperty("kind", "mulligan");
                e.addProperty("player", String.valueOf(g.player()));
            } else if (ev instanceof GameEventGameOutcome g) {
                e.addProperty("kind", "gameOutcome");
                e.addProperty("winner", String.valueOf(g.winningPlayerName()));
                e.addProperty("lastTurn", g.lastTurnNumber());
            } else {
                return; // not part of the v1 trace
            }
            final JsonObject msg = new JsonObject();
            msg.addProperty("type", "event");
            msg.addProperty("game", gameId);
            msg.add("event", e);
            ch.send(msg);
            n++;
        }

        private static boolean interestingZone(final String z) {
            return z != null && (z.contains("Battlefield") || z.contains("Graveyard"));
        }
    }
}
