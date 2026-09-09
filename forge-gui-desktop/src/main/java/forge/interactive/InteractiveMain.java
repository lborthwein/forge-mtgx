/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import forge.LobbyPlayer;
import forge.StaticData;
import forge.item.PaperCard;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.gui.control.FControlGameEventHandler;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableCollection;
import forge.util.BuildInfo;
import forge.util.MyRandom;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** One-config, one-game process entry point for a browser human versus Default Forge. */
public final class InteractiveMain {
    /** The upstream engine tree this integration source is pinned to. */
    private static final String PINNED_FORGE_COMMIT =
            "3544576919d55f1deb6fe8391a3c2ff446c30c0c";

    private InteractiveMain() {
    }

    public static void main(final String[] args) {
        // Do this before touching Forge singletons: incidental Forge stdout is not wire data.
        final PrintStream protocolOut = new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        final String configLine;
        try {
            configLine = input.readLine();
        } catch (Exception e) {
            InteractiveProtocol.writeBootFatal(protocolOut, "unknown", "invalid_config",
                    "could not read config: " + e.getMessage());
            System.exit(2);
            return;
        }

        final InteractiveProtocol.Config config;
        try {
            config = InteractiveProtocol.readConfig(configLine);
            validateDeckFiles(config.decks());
        } catch (InteractiveProtocol.ProtocolException e) {
            InteractiveProtocol.writeBootFatal(protocolOut,
                    InteractiveProtocol.bestEffortSession(configLine), e.code(), e.getMessage());
            System.exit(2);
            return;
        }

        final InteractiveProtocol.Channel channel =
                new InteractiveProtocol.Channel(input, protocolOut, config.session());
        try {
            channel.hello(forgeCommit(), nonBlankVersion(), config.aiProfile(),
                    config.humanSeat(), config.seed());
        } catch (InteractiveProtocol.ProtocolException e) {
            channel.fatal(e.code(), e.getMessage(), null, "hello", null);
            System.exit(2);
            return;
        }

        InteractiveGuiGame gui = null;
        boolean completedNormally = false;
        try {
            System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
            System.setProperty("sun.java2d.d3d", "false");

            final InteractiveGuiDesktop desktop = new InteractiveGuiDesktop();
            GuiBase.setInterface(desktop);
            initializeForge();
            MyRandom.setRandom(new Random(config.seed()));

            final List<RegisteredPlayer> registered = createPlayers(config);
            final GameRules rules = new GameRules(GameType.Constructed);
            rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
            rules.setGamesPerMatch(1);
            rules.setAISideboardingEnabled(false);
            rules.setSideboardForAI(false);
            rules.setAllowCheatShuffle(false);
            // Desktop deck-advice modal, not a rules failure or human choice.
            // Its opponent-card list is inappropriate for a hidden-information game.
            rules.setWarnAboutAICards(false);

            final Match match = new Match(rules, registered, "Browser vs Default Forge");
            final Game game = match.createGame();
            final Player human = playerAtSeat(game, config.humanSeat());
            if (human == null || !(human.getController() instanceof PlayerControllerHuman humanController)) {
                throw new IllegalStateException("configured human seat did not create PlayerControllerHuman");
            }

            enableCompleteHumanControl(humanController);
            gui = new InteractiveGuiGame(channel, config.humanSeat());
            gui.bind(game, human, humanController);
            desktop.bind(gui);
            humanController.setGui(gui);
            gui.setGameView(null);
            gui.setGameView(game.getView());
            gui.setOriginalGameController(human.getView(), humanController);
            gui.openView(new TrackableCollection<>(human.getView()));
            for (Player player : game.getPlayers()) {
                player.updateOpponentsForView();
            }
            // HostedMatch installs this bridge for every local human controller. It
            // drives the normal InputQueue/UI lifecycle; our raw-event subscriber is
            // additional protocol instrumentation, not a substitute for it.
            game.subscribeToEvents(new FControlGameEventHandler(humanController));
            game.subscribeToEvents(gui);
            gui.startReader();

            final CompletableFuture<Void> gameFinished = new CompletableFuture<>();
            final InteractiveGuiGame finalGui = gui;
            game.getAction().invoke(() -> {
                try {
                    match.startGame(game);
                    gameFinished.complete(null);
                } catch (InteractiveGuiGame.GameConceded conceded) {
                    // Concede is a real Forge game action. Its outcome, not this control
                    // exception, is the terminal result.
                    gameFinished.complete(null);
                } catch (InteractiveGuiGame.InteractiveAbort aborted) {
                    gameFinished.complete(null);
                } catch (Throwable failure) {
                    finalGui.engineFailure(failure);
                    gameFinished.completeExceptionally(failure);
                }
            });
            try {
                gameFinished.join();
            } catch (CompletionException ignored) {
                // engineFailure already emitted the only allowed fatal message.
            }

            if (!gui.hasFailed()) {
                gui.emitFinalState();
                if (!gui.hasFailed()) {
                    completedNormally = emitTerminal(channel, game, registered);
                }
            }
        } catch (InteractiveProtocol.ProtocolException failure) {
            channel.fatal(failure.code(), failure.getMessage(), null,
                    "InteractiveMain", null);
        } catch (Throwable failure) {
            if (gui != null) {
                gui.engineFailure(failure);
            } else {
                channel.fatal("engine", "Forge initialization failed: " + failure,
                        null, "InteractiveMain", null);
                failure.printStackTrace(System.err);
            }
        } finally {
            if (gui != null) {
                gui.close();
            }
        }
        // Forge initializes Swing/FModel threads that can outlive a completed match.
        // This executable owns exactly one game, so terminate only after the final wire
        // message has been flushed and the interactive GUI has been closed.
        System.exit(completedNormally ? 0 : 1);
    }

    private static void initializeForge() {
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.PLAYER_NAME, "Browser Player");
            preferences.setPref(FPref.UI_CURRENT_AI_PROFILE, "Default");
            preferences.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, false);
            preferences.setPref(FPref.YIELD_AUTO_PASS_RESPECTS_INTERRUPTS, false);
            return null;
        });
    }

    private static List<RegisteredPlayer> createPlayers(
            final InteractiveProtocol.Config config) throws InteractiveProtocol.ProtocolException {
        final List<RegisteredPlayer> players = new ArrayList<>(2);
        for (int seat = 0; seat < 2; seat++) {
            final Path path = config.decks().get(seat);
            final Deck deck = DeckSerializer.fromFile(path.toFile());
            if (deck == null) {
                throw new InteractiveProtocol.ProtocolException("invalid_config",
                        "could not parse deck: " + path);
            }
            validateLoadedDeck(path, deck);
            final LobbyPlayer lobbyPlayer = seat == config.humanSeat()
                    ? GamePlayerUtil.getGuiPlayer()
                    : GamePlayerUtil.createAiPlayer("Default Forge", seat, 0, null,
                            config.aiProfile());
            final RegisteredPlayer registered = new RegisteredPlayer(deck);
            registered.setPlayer(lobbyPlayer);
            players.add(registered);
        }
        return players;
    }

    private static void enableCompleteHumanControl(final PlayerControllerHuman controller) {
        controller.getFullControl().addAll(EnumSet.of(
                FullControlFlag.ChooseCostOrder,
                FullControlFlag.ChooseCostReductionOrderAndVariableAmount,
                FullControlFlag.ChooseManaPoolShard,
                FullControlFlag.NoPaymentFromManaAbility,
                FullControlFlag.LayerTimestampOrder));
        // Deliberately omit NoFreeCombatCostHandling: it can skip a real zero-mana
        // combat-cost prompt rather than merely disabling assistance.
        controller.setDisableAutoYields(true);
        controller.setDisableAutoTriggers(true);
    }

    private static Player playerAtSeat(final Game game, final int wantedSeat) {
        int seat = 0;
        for (Player player : game.getPlayers()) {
            if (seat++ == wantedSeat) {
                return player;
            }
        }
        return null;
    }

    private static boolean emitTerminal(final InteractiveProtocol.Channel channel,
                                        final Game game,
                                        final List<RegisteredPlayer> registered)
            throws InteractiveProtocol.ProtocolException {
        final GameOutcome outcome = game.getOutcome();
        if (outcome == null) {
            channel.fatal("engine", "match returned without a GameOutcome",
                    null, "emitTerminal", null);
            return false;
        }
        final Integer winner;
        if (outcome.isDraw() || outcome.getWinningPlayer() == null) {
            winner = null;
        } else {
            final int seat = registered.indexOf(outcome.getWinningPlayer());
            if (seat < 0) {
                channel.fatal("engine", "winning player was outside configured seats",
                        null, "emitTerminal", null);
                return false;
            }
            winner = seat;
        }
        channel.terminal("g1", winner, String.valueOf(outcome.getWinCondition()),
                outcome.getLastTurnNumber());
        return true;
    }

    private static void validateDeckFiles(final List<Path> decks)
            throws InteractiveProtocol.ProtocolException {
        for (Path deck : decks) {
            if (!Files.isRegularFile(deck) || !Files.isReadable(deck)) {
                throw new InteractiveProtocol.ProtocolException("invalid_config",
                        "deck does not exist or is unreadable: " + deck);
            }
        }
    }

    /** DeckSerializer may omit an unknown card or substitute a fallback print.
     * Verify the exact requested main-deck multiset before dealing any cards. */
    private static void validateLoadedDeck(final Path path, final Deck deck)
            throws InteractiveProtocol.ProtocolException {
        final Map<String, Integer> expected = new HashMap<>();
        final Map<String, Integer> actual = new HashMap<>();
        try {
            boolean main = false;
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                final String line = raw.trim();
                if (line.startsWith("[")) { main = line.equalsIgnoreCase("[Main]"); continue; }
                if (!main || line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                final int split = line.indexOf(' ');
                if (split < 1) throw new IllegalArgumentException("main-deck entry needs count and card name");
                final int count = Integer.parseInt(line.substring(0, split));
                if (count < 1 || count > 250) throw new IllegalArgumentException("invalid card count");
                final String name = line.substring(split + 1).split("\\|", 2)[0].trim();
                final PaperCard card = StaticData.instance().getCommonCards().getCard(name);
                if (card == null || card.getRules().isUnsupported()) {
                    throw new IllegalArgumentException("unknown or unsupported card: " + name);
                }
                expected.merge(card.getName(), count, Integer::sum);
            }
            for (Map.Entry<PaperCard, Integer> entry : deck.getMain()) {
                if (entry.getKey().getRules().isUnsupported()) throw new IllegalArgumentException("unsupported loaded card");
                actual.merge(entry.getKey().getName(), entry.getValue(), Integer::sum);
            }
            if (expected.isEmpty() || !expected.equals(actual)) {
                throw new IllegalArgumentException("loaded main deck differs from requested card names/counts");
            }
        } catch (Exception error) {
            throw new InteractiveProtocol.ProtocolException("invalid_config", "Deck rejected: " + error.getMessage());
        }
    }

    private static String forgeCommit() {
        // Provenance must originate in the jar, not from a value supplied by its caller.
        // The host separately records and pins the jar digest.
        return PINNED_FORGE_COMMIT;
    }

    private static String nonBlankVersion() {
        final String version = BuildInfo.getVersionString();
        return version == null || version.isBlank() ? "Forge@" + forgeCommit() : version;
    }
}
