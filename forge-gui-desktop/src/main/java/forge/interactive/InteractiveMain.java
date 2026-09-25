/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import forge.game.player.PlayerOutcome;
import forge.game.player.PlayerStatistics;
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
            // Each game of the client's best-of-three is its own one-game match, so
            // Forge cannot see who lost the last one. The client's match layer can.
            if (config.startingChooser() >= 0) {
                rules.setStartingChooser(registered.get(config.startingChooser()), config.gameNumber() == 1);
            }

            final Match match = new Match(rules, registered, "Browser vs Default Forge");
            final Game game = match.createGame();
            bindLookahead(registered, game, config.seed());
            final Player human = playerAtSeat(game, config.humanSeat());
            if (human == null || !(human.getController() instanceof PlayerControllerHuman humanController)) {
                throw new IllegalStateException("configured human seat did not create PlayerControllerHuman");
            }

            configureHumanPayment(humanController);
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
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui,
                    new FControlGameEventHandler(humanController)));
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
            // The browser owns presentation. Opening desktop audio clips here
            // blocks game-event delivery (including each mana-source tap) on
            // the server, and cannot provide sound to the remote player.
            preferences.setPref(FPref.UI_ENABLE_SOUNDS, false);
            preferences.setPref(FPref.UI_ENABLE_MUSIC, false);
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
                    : lookaheadSpec() != null
                            ? lookaheadLobby(config.aiProfile())
                            : GamePlayerUtil.createAiPlayer("Default Forge", seat, 0, null,
                                    config.aiProfile());
            final RegisteredPlayer registered = new RegisteredPlayer(deck);
            registered.setPlayer(lobbyPlayer);
            players.add(registered);
        }
        return players;
    }

    /**
     * {@code -Dforge.interactive.lookahead=worlds=8,breadth=4,horizon=2,threads=4}: the Forge seat
     * is Forge AI plus the mtgx look-ahead ({@link forge.ai.simulation.LookaheadSearch}). Unset (the
     * default, and the live server) keeps the plain Default Forge seat.
     */
    private static String lookaheadSpec() {
        final String v = System.getProperty("forge.interactive.lookahead");
        return v == null || v.isBlank() ? null : v;
    }

    private static LobbyPlayer lookaheadLobby(final String aiProfile) {
        final forge.ai.simulation.LobbyPlayerLookahead lp = new forge.ai.simulation.LobbyPlayerLookahead("Default Forge");
        lp.setAiProfile(aiProfile);
        return lp;
    }

    private static void bindLookahead(final List<RegisteredPlayer> registered, final Game game, final long seed) {
        final String spec = lookaheadSpec();
        if (spec == null) {
            return;
        }
        final forge.ai.simulation.LookaheadSearch.Config c = new forge.ai.simulation.LookaheadSearch.Config();
        for (String kv : spec.split(",")) {
            final String[] p = kv.split("=", 2);
            if (p.length != 2) {
                continue;
            }
            switch (p[0].trim()) {
                case "worlds": c.worlds = Integer.parseInt(p[1].trim()); break;
                case "breadth": c.breadth = Integer.parseInt(p[1].trim()); break;
                case "horizon": c.horizonTurns = Integer.parseInt(p[1].trim()); break;
                case "threads": c.threads = Integer.parseInt(p[1].trim()); break;
                case "margin": c.margin = Double.parseDouble(p[1].trim()); break;
                case "maxSteps": c.maxSteps = Integer.parseInt(p[1].trim()); break;
                case "shadow": c.shadow = !"0".equals(p[1].trim()); break;
                default: break;
            }
        }
        for (int i = 0; i < registered.size(); i++) {
            if (registered.get(i).getPlayer() instanceof forge.ai.simulation.LobbyPlayerLookahead lp) {
                c.seed = seed * 31 + i;
                lp.bind(game, new forge.ai.simulation.LookaheadSearch(c));
            }
        }
    }

    private static void configureHumanPayment(final PlayerControllerHuman controller) {
        // Keep produced mana for the player's complete-payment choice. Do not
        // force desktop power-user cost-order/shard/timestamp dialogs: use
        // Forge's ordinary human defaults for those conveniences.
        controller.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
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
                outcome.getLastTurnNumber(), lossesOf(outcome, registered));
        return true;
    }

    /**
     * Each losing seat's {@link forge.game.player.GameLossReason}, in seat order, for the
     * terminal message (mtgx, 2026-09-25).
     *
     * <p>{@code GameEndReason} is the winner's view: {@code AllOpponentsLost} for a
     * concession, a decking and a life-0 loss alike, so the browser could not tell a
     * turn-one concession from a real loss. The loser's {@link PlayerOutcome} keeps the
     * difference. A player with no outcome, or one that won, is not listed.
     */
    static JsonArray lossesOf(final Iterable<Map.Entry<RegisteredPlayer, PlayerStatistics>> ratings,
                              final List<RegisteredPlayer> registered) {
        final JsonObject[] bySeat = new JsonObject[registered.size()];
        for (Map.Entry<RegisteredPlayer, PlayerStatistics> rating : ratings) {
            final int seat = registered.indexOf(rating.getKey());
            final PlayerOutcome result = rating.getValue() == null ? null : rating.getValue().getOutcome();
            if (seat < 0 || result == null || result.hasWon()) {
                continue;
            }
            final JsonObject loss = new JsonObject();
            loss.addProperty("seat", seat);
            loss.addProperty("reason", result.lossState.name());
            if (result.loseConditionSpell != null && !result.loseConditionSpell.isEmpty()) {
                loss.addProperty("spell", result.loseConditionSpell);
            }
            bySeat[seat] = loss;
        }
        final JsonArray losses = new JsonArray();
        for (JsonObject loss : bySeat) {
            if (loss != null) {
                losses.add(loss);
            }
        }
        return losses;
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
