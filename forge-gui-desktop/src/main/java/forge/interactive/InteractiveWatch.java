/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlReviewObserver;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.PriorityDecisionListener;
import forge.game.event.GameEvent;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.gui.GuiBase;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A WATCH table (mtgx lane forge-bot-tables-1011; only for a config with {@code watchSeats}): an AI-vs-AI game played
 * server-side, recorded per seat so a browser can replay it from either seat's eyes, and replayable exactly from its seed
 * (it takes no input).
 *
 * <p><b>Seats.</b> {@code seatAi} names each seat's AI: {@code classic} (Default Forge, with the 0928 fixes as for an AI
 * seat of a human game), {@code lookahead} (the K8 look-ahead of {@code -Dforge.interactive.lookahead}, each seat with
 * its own search and seed {@code gameSeed * 31 + seat}), or {@code policy} (the S-c seat of
 * {@code -Dforge.interactive.policySearch}, its own service connection per seat; a seat whose service does not answer at
 * boot plays the look-ahead, else classic, as the AI seat of a human game does). A seat is named by the config's
 * {@code names} when given, else "Default Forge".
 *
 * <p><b>Wire.</b> One channel per seat over the one stdout, tagged and sequenced as a table's
 * ({@link InteractiveProtocol.Channel#watch}). Per channel: {@code hello} (its {@code humanSeat} is the channel's seat);
 * every game event as a seat's GUI encodes it ({@link InteractiveGameEvents#encode} for that seat's view, all events,
 * exactly as the GUI sends them); a {@code state} message at every priority decision of either seat
 * ({@link PriorityDecisionListener#beforeDecision}), each channel with its own seat's view and
 * {@code "decision": {"n", "seat", "turn", "phase"}} ({@code n} counts the game's decisions from 0); at the end a
 * {@code "game-completed"} state and the {@code terminal}. A failure is one fatal on every channel.
 *
 * <p><b>Threads.</b> Everything here runs on the game thread, inside the event dispatch and the decision hook, between
 * actions: nothing reads the game while it changes.
 *
 * <p><b>Determinism.</b> {@code -Dforge.interactive.aiTimeoutSec=<s>} (read only in watch mode) sets the game's AI
 * evaluation bound (Forge's 5 s wall clock otherwise). With it high, {@code -Dforge.bench.sequentialAi=true} and a
 * look-ahead spec with {@code budgetMs=0}, the same config gives the same stream.
 */
final class InteractiveWatch {
    private InteractiveWatch() {
    }

    /** main's watch path: the channels' hello, Forge, the game, its end. True when the game completed normally. */
    static boolean run(final InteractiveProtocol.Config config, final PrintStream protocolOut) {
        return run(config, protocolOut, true);
    }

    /** {@code initializeForge}: false when the caller (a test) has initialized Forge already. */
    static boolean run(final InteractiveProtocol.Config config, final PrintStream protocolOut,
                       final boolean initializeForge) {
        final List<InteractiveProtocol.Channel> channels =
                InteractiveProtocol.Channel.watch(protocolOut, config.session(), config.watchSeats());
        try {
            for (InteractiveProtocol.Channel channel : channels) {
                channel.hello(InteractiveMain.forgeCommit(), InteractiveMain.nonBlankVersion(), config.aiProfile(),
                        channel.seat(), config.seed());
            }
        } catch (InteractiveProtocol.ProtocolException e) {
            channels.get(0).fatal(e.code(), e.getMessage(), null, "hello", null);
            return false;
        }
        try {
            System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
            System.setProperty("sun.java2d.d3d", "false");
            if (initializeForge) {
                // Unbound: an engine path that asks the process-wide GUI chooser in an AI-only game fails loudly.
                GuiBase.setInterface(InteractiveMain.guiDesktop()); // a prewarm's own, when there was one
                InteractiveMain.initializeForge();
            }
        } catch (Throwable failure) {
            channels.get(0).fatal("engine", "Forge initialization failed: " + failure, null, "InteractiveMain", null);
            failure.printStackTrace(System.err);
            return false;
        }
        return play(config, channels);
    }

    /** The game (Forge already initialized): true when it completed normally and every terminal went out. */
    static boolean play(final InteractiveProtocol.Config config, final List<InteractiveProtocol.Channel> channels) {
        final RlLiveSeat[] policy = new RlLiveSeat[2];
        RlReviewObserver review = null;
        Watcher watcher = null;
        boolean completedNormally = false;
        try {
            // policy seats connect before the game's random stream is seeded, as a human game's policy seat does
            for (int seat = 0; seat < 2; seat++) {
                if ("policy".equals(config.seatAi().get(seat))) {
                    policy[seat] = RlLiveSeat.connect(InteractiveMain.policySpec(),
                            "bot-" + config.session() + "-s" + seat);
                }
            }
            final Prepared prepared = prepare(config, policy);
            final Match match = prepared.match();
            final Game game = prepared.game();
            final List<RegisteredPlayer> registered = prepared.registered();

            watcher = new Watcher(game, channels);
            game.subscribeToEvents(watcher);
            game.addPriorityDecisionListener(watcher);
            if (InteractiveReview.spec() != null) {
                // a watch table's review: the AI seat the spec's seat= names (its decisions are still the AI's)
                review = InteractiveReview.installWatch(game, config.seed());
            }

            final CompletableFuture<Void> gameFinished = new CompletableFuture<>();
            final Watcher finalWatcher = watcher;
            game.getAction().invoke(() -> {
                try {
                    match.startGame(game);
                    gameFinished.complete(null);
                } catch (WatchAbort aborted) {
                    gameFinished.complete(null);
                } catch (Throwable failure) {
                    finalWatcher.fail("engine", "Forge game failed: " + safeThrowable(failure), "match.startGame",
                            failure);
                    gameFinished.completeExceptionally(failure);
                }
            });
            try {
                gameFinished.join();
            } catch (CompletionException ignored) {
                // the watcher already sent the only fatal
            }
            finishSeats(policy, review);

            if (!watcher.hasFailed()) {
                watcher.emitFinalStates();
                if (!watcher.hasFailed()) {
                    completedNormally = true;
                    for (InteractiveProtocol.Channel channel : channels) {
                        completedNormally &= InteractiveMain.emitTerminal(channel, game, registered);
                    }
                }
            }
        } catch (InteractiveProtocol.ProtocolException failure) {
            channels.get(0).fatal(failure.code(), failure.getMessage(), null, "InteractiveWatch", null);
        } catch (Throwable failure) {
            if (watcher != null) {
                watcher.fail("engine", "Forge game failed: " + safeThrowable(failure), "InteractiveWatch", failure);
            } else {
                channels.get(0).fatal("engine", "Forge initialization failed: " + failure, null, "InteractiveWatch",
                        null);
                failure.printStackTrace(System.err);
            }
        } finally {
            finishSeats(policy, review);
        }
        return completedNormally;
    }

    /** A watch table's game: created, its seats bound, not started. */
    record Prepared(Match match, Game game, List<RegisteredPlayer> registered) {
    }

    /**
     * The game of {@code config}: the random stream seeded, both AI seats created and bound (a policy seat from
     * {@code policy}, null when it has none or its service did not answer), as a human game's AI seat is bound.
     */
    static Prepared prepare(final InteractiveProtocol.Config config, final RlLiveSeat[] policy)
            throws InteractiveProtocol.ProtocolException {
        MyRandom.setRandom(new Random(config.seed()));
        final List<RegisteredPlayer> registered = createPlayers(config, policy);
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setGamesPerMatch(1);
        rules.setAISideboardingEnabled(false);
        rules.setSideboardForAI(false);
        rules.setAllowCheatShuffle(false);
        rules.setWarnAboutAICards(false);
        if (config.startingChooser() >= 0) {
            rules.setStartingChooser(registered.get(config.startingChooser()), config.gameNumber() == 1);
        }
        final Match match = new Match(rules, registered, "Watch table");
        final Game game = match.createGame();
        final int aiTimeoutSec = Integer.getInteger("forge.interactive.aiTimeoutSec", 0);
        if (aiTimeoutSec > 0) {
            game.AI_TIMEOUT = aiTimeoutSec;
        }
        InteractiveMain.bindAiFixes(registered);
        bindLookahead(registered, game, config.seed());
        InteractiveMain.bindPolicy(registered, game, config.seed());
        InteractiveMain.countFairNaming(registered, game);
        for (Player player : game.getPlayers()) {
            player.updateOpponentsForView();
        }
        return new Prepared(match, game, registered);
    }

    private static void finishSeats(final RlLiveSeat[] policy, final RlReviewObserver review) {
        // a policy seat's end line and its connections close before the terminal goes out (the host may stop the JVM
        // as soon as it has the last terminal); each finish runs once
        for (RlLiveSeat seat : policy) {
            if (seat != null) {
                seat.finish();
            }
        }
        if (review != null) {
            review.finish();
        }
    }

    /** Both seats AI, named by the config's names when given. */
    private static List<RegisteredPlayer> createPlayers(final InteractiveProtocol.Config config,
            final RlLiveSeat[] policy) throws InteractiveProtocol.ProtocolException {
        final List<RegisteredPlayer> players = new ArrayList<>(2);
        for (int seat = 0; seat < 2; seat++) {
            final Path path = config.decks().get(seat);
            final Deck deck = DeckSerializer.fromFile(path.toFile());
            if (deck == null) {
                throw new InteractiveProtocol.ProtocolException("invalid_config", "could not parse deck: " + path);
            }
            InteractiveMain.validateLoadedDeck(path, deck);
            final String name = config.names().size() == 2 ? config.names().get(seat) : "Default Forge";
            final String ai = config.seatAi().get(seat);
            final LobbyPlayer lobbyPlayer;
            if ("policy".equals(ai) && policy[seat] != null) {
                lobbyPlayer = policy[seat].lobby(name, seat, config.aiProfile());
            } else if ("lookahead".equals(ai)
                    || ("policy".equals(ai) && InteractiveMain.lookaheadSpec() != null)) {
                final forge.ai.simulation.LobbyPlayerLookahead lp = new forge.ai.simulation.LobbyPlayerLookahead(name);
                lp.setAiProfile(config.aiProfile());
                lobbyPlayer = lp;
            } else {
                lobbyPlayer = GamePlayerUtil.createAiPlayer(name, seat, 0, null, config.aiProfile());
            }
            final RegisteredPlayer registered = new RegisteredPlayer(deck);
            registered.setPlayer(lobbyPlayer);
            players.add(registered);
        }
        return players;
    }

    /**
     * Each look-ahead seat gets its OWN config parsed from the spec (two seats never share one mutated config), seeded
     * {@code gameSeed * 31 + seat}, with the spec's aiFixes0928 and fairNaming as a human game's look-ahead seat.
     */
    private static void bindLookahead(final List<RegisteredPlayer> registered, final Game game, final long seed) {
        final String spec = InteractiveMain.lookaheadSpec();
        if (spec == null) {
            return;
        }
        for (int i = 0; i < registered.size(); i++) {
            if (registered.get(i).getPlayer() instanceof forge.ai.simulation.LobbyPlayerLookahead lp) {
                final InteractiveMain.ParsedLookahead pl = InteractiveMain.parseLookahead(spec);
                lp.setAiFixes0928(pl.aiFixes);
                if (pl.fairNaming != null) {
                    lp.setFairNaming(pl.fairNaming);
                }
                pl.config.seed = seed * 31 + i;
                lp.bind(game, new forge.ai.simulation.LookaheadSearch(pl.config));
            }
        }
    }

    private static String safeThrowable(final Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        return throwable.getClass().getSimpleName() + ": "
                + Objects.requireNonNullElse(throwable.getMessage(), "(no message)");
    }

    /** Thrown on the game thread after a failure has been sent, so the game stops at its next decision. */
    static final class WatchAbort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        WatchAbort(final String message) {
            super(message);
        }
    }

    /** The per-seat channels' producer: every event, a state at every decision, the end. Game thread only. */
    static final class Watcher implements PriorityDecisionListener {
        private final Game game;
        private final List<InteractiveProtocol.Channel> channels;
        private final Player[] seats;
        /** Where cards entered the battlefield from, for {@code land_played}'s {@code fromZone} (lane bridgemeta-1018). */
        private final InteractiveGameEvents.Moves moves = new InteractiveGameEvents.Moves();
        private int decisions = 0;
        private volatile boolean failed = false;

        Watcher(final Game game, final List<InteractiveProtocol.Channel> channels) {
            this.game = game;
            this.channels = channels;
            this.seats = new Player[channels.size()];
            for (int i = 0; i < channels.size(); i++) {
                seats[i] = InteractiveMain.playerAtSeat(game, channels.get(i).seat());
                if (seats[i] == null) {
                    throw new IllegalStateException("watch seat " + channels.get(i).seat() + " has no player");
                }
            }
        }

        boolean hasFailed() {
            if (failed) {
                return true;
            }
            for (InteractiveProtocol.Channel channel : channels) {
                if (channel.isEnded()) {
                    return true;
                }
            }
            return false;
        }

        /** Every game event to every seat, each encoded for its own seat as that seat's GUI encodes it. */
        @Subscribe
        public void receiveGameEvent(final GameEvent event) {
            if (failed) {
                return;
            }
            try {
                for (int i = 0; i < channels.size(); i++) {
                    final InteractiveProtocol.Channel channel = channels.get(i);
                    if (channel.isEnded()) {
                        continue;
                    }
                    final JsonObject body = new JsonObject();
                    body.add("event", InteractiveGameEvents.encode(event, game, seats[i].getView(), moves));
                    channel.send("event", body);
                }
            } catch (InteractiveProtocol.ProtocolException e) {
                fail("eof", e.getMessage(), "onEngineEvent", e);
            } catch (Throwable e) {
                fail("engine", "could not encode Forge event: " + safeThrowable(e), "onEngineEvent", e);
            }
        }

        @Override
        public void beforeDecision(final Player p) {
            if (failed) {
                throw new WatchAbort("the watch table failed");
            }
            final int n = decisions++;
            final JsonObject decision = new JsonObject();
            decision.addProperty("n", n);
            decision.addProperty("seat", game.getRegisteredPlayers().indexOf(p));
            decision.addProperty("turn", game.getPhaseHandler().getTurn());
            decision.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
            try {
                for (int i = 0; i < channels.size(); i++) {
                    final JsonObject body = new JsonObject();
                    body.addProperty("reason", "decision");
                    body.add("view", InteractiveState.seatView(game, seats[i], channels.get(i).seat(), false));
                    body.add("decision", decision.deepCopy());
                    channels.get(i).send("state", body);
                }
            } catch (InteractiveProtocol.ProtocolException e) {
                fail("eof", e.getMessage(), "state", e);
            } catch (Throwable e) {
                fail("engine", "could not encode Forge state: " + safeThrowable(e), "state", e);
            }
            if (failed) {
                throw new WatchAbort("the watch table failed");
            }
        }

        @Override
        public void afterDecision(final Player p, final List<SpellAbility> chosen) {
            // the next state (or event) shows what was chosen
        }

        /** After the game: each seat's final view ({@code "reason":"game-completed"}), as a seat's GUI sends it. */
        void emitFinalStates() {
            try {
                for (int i = 0; i < channels.size(); i++) {
                    final JsonObject body = new JsonObject();
                    body.addProperty("reason", "game-completed");
                    body.add("view", InteractiveState.seatView(game, seats[i], channels.get(i).seat(), false));
                    channels.get(i).send("state", body);
                }
            } catch (InteractiveProtocol.ProtocolException e) {
                fail("eof", e.getMessage(), "state", e);
            } catch (Throwable e) {
                fail("engine", "could not encode Forge state: " + safeThrowable(e), "state", e);
            }
        }

        /** One fatal, on every channel; the game is stopped (as a seat GUI's failure stops it). */
        void fail(final String code, final String message, final String method, final Throwable failure) {
            if (failed) {
                return;
            }
            failed = true;
            if (failure != null) {
                System.err.println("[forge.interactive] watch " + method + ": " + safeThrowable(failure));
                failure.printStackTrace(System.err);
            }
            channels.get(0).fatal(code, message, null, method, null);
            if (!game.isGameOver()) {
                try {
                    game.setGameOver(GameEndReason.Draw);
                } catch (Throwable secondary) {
                    System.err.println("[forge.interactive] could not stop the failed watch game: "
                            + safeThrowable(secondary));
                }
            }
        }
    }
}
