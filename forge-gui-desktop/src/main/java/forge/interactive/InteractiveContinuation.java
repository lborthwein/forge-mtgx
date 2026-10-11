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
import forge.ai.WallClock;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * "Continue from here" (lane forge-continue-1011; off unless {@code -Dforge.interactive.continuation=rebuild}).
 *
 * <p>The host rebuilds a finished one-seat game in a new JVM from its journal: the same seed, decks and AI settings,
 * the human's recorded inputs fed back one by one, every request frame checked against the original transcript. The AI
 * seat decides again. With its wall clocks off ({@link WallClock}, and {@code game.AI_TIMEOUT} at
 * {@link #REBUILD_AI_TIMEOUT_SEC}) nothing time-dependent can make it decide differently. At the chosen request the
 * host sends a {@code handover}: optionally reshuffle both libraries from a seeded stream, and (the final handover)
 * switch the AI back to its live budgets. The browser then plays on from that request.</p>
 *
 * <p>{@link InteractiveGuiGame} runs {@link #handover} on its reader thread at a safe point: the human's request is the
 * one Forge is parked on, and nothing runs in Forge's model meanwhile.</p>
 */
final class InteractiveContinuation {
    static final String PROPERTY = "forge.interactive.continuation";
    static final String REBUILD = "rebuild";
    /** {@code game.AI_TIMEOUT} while rebuilding (s): Forge AI's own evaluation timeouts never bind. */
    static final int REBUILD_AI_TIMEOUT_SEC = 600;
    /** How long a handover waits for its safe point (ms), and how often it looks. */
    static final long SAFE_POINT_TIMEOUT_MS = 10_000L;
    static final long SAFE_POINT_RETRY_MS = 20L;

    private final Game game;
    private final int liveAiTimeoutSec;
    private final long safePointTimeoutMs;
    /** Set by the final ({@code live:true}) handover; no handover is accepted after it. */
    private volatile boolean live;

    private InteractiveContinuation(final Game game, final int liveAiTimeoutSec, final long safePointTimeoutMs) {
        this.game = game;
        this.liveAiTimeoutSec = liveAiTimeoutSec;
        this.safePointTimeoutMs = safePointTimeoutMs;
    }

    /**
     * {@code -Dforge.interactive.continuation}: false when absent or blank (every normal game), true for
     * {@code rebuild}. Any other value is refused, so a typo never starts a game the host believes is a rebuild.
     */
    static boolean requested() throws InteractiveProtocol.ProtocolException {
        final String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank()) {
            return false;
        }
        if (!REBUILD.equals(v.trim())) {
            throw new InteractiveProtocol.ProtocolException("invalid_config",
                    PROPERTY + " must be '" + REBUILD + "' or absent");
        }
        return true;
    }

    /** The hello's extra field in this mode: {@code "continuation":"rebuild"}. */
    static JsonObject helloExtras() {
        final JsonObject extras = new JsonObject();
        extras.addProperty("continuation", REBUILD);
        return extras;
    }

    /** Boot, before the game is created: no AI wall clock binds until the live handover. */
    static void beginRebuild() {
        WallClock.setRebuilding(true);
    }

    /** After {@code match.createGame()}: remember {@code game.AI_TIMEOUT} as the live value, and make it non-binding. */
    static InteractiveContinuation forGame(final Game game) {
        return forGame(game, SAFE_POINT_TIMEOUT_MS);
    }

    /** As {@link #forGame(Game)}, with another safe-point wait (tests). */
    static InteractiveContinuation forGame(final Game game, final long safePointTimeoutMs) {
        final InteractiveContinuation continuation =
                new InteractiveContinuation(game, game.AI_TIMEOUT, safePointTimeoutMs);
        game.AI_TIMEOUT = REBUILD_AI_TIMEOUT_SEC;
        return continuation;
    }

    boolean isLive() {
        return live;
    }

    long safePointTimeoutMs() {
        return safePointTimeoutMs;
    }

    /**
     * The handover's effects, in order, and its reply body (the caller holds the safe point and sends the reply).
     * <ol>
     *   <li>{@code reshuffle}: Forge's RNG becomes {@code new Random(seed)}, then each seat's library is shuffled, in seat
     *       order, with {@code Zone.shuffle} (no Shuffled trigger, no GameEventShuffle: hidden information is
     *       re-randomised, no game action happens). Everything random afterwards continues from that stream, so a replay
     *       of the same journal reproduces it.</li>
     *   <li>{@code live}: the AI's wall clocks and {@code game.AI_TIMEOUT} are the live ones again.</li>
     * </ol>
     */
    JsonObject handover(final InteractiveProtocol.HandoverMessage handover) {
        if (live) {
            throw new IllegalStateException("the game was already handed over live");
        }
        final List<Player> seats = new ArrayList<>(game.getRegisteredPlayers());
        final List<String> cardsBefore = new ArrayList<>();
        final List<String> orderBefore = new ArrayList<>();
        for (Player player : seats) {
            cardsBefore.add(cardsDigest(player));
            orderBefore.add(orderDigest(player));
        }
        if (handover.reshuffle()) {
            MyRandom.setRandom(new Random(handover.seed()));
            for (Player player : seats) {
                player.getZone(ZoneType.Library).shuffle();
            }
        }
        if (handover.live()) {
            WallClock.setRebuilding(false);
            game.AI_TIMEOUT = liveAiTimeoutSec;
            live = true;
        }

        final JsonArray libraries = new JsonArray();
        for (int seat = 0; seat < seats.size(); seat++) {
            final Player player = seats.get(seat);
            final String cards = cardsDigest(player);
            if (!cards.equals(cardsBefore.get(seat))) {
                throw new IllegalStateException("seat " + seat + "'s library changed its cards during the handover");
            }
            final JsonObject library = new JsonObject();
            library.addProperty("seat", seat);
            library.addProperty("count", player.getZone(ZoneType.Library).size());
            library.addProperty("cards", cards);
            library.addProperty("before", orderBefore.get(seat));
            library.addProperty("after", orderDigest(player));
            libraries.add(library);
        }
        final JsonObject reply = new JsonObject();
        reply.addProperty("requestId", handover.requestId());
        reply.addProperty("reshuffled", handover.reshuffle());
        reply.addProperty("live", handover.live());
        reply.addProperty("aiTimeoutSec", game.AI_TIMEOUT);
        reply.addProperty("rebuilding", WallClock.rebuilding());
        reply.add("libraries", libraries);
        return reply;
    }

    /** {@code <card id>:<name>} for each card of the seat's library, top first. */
    static List<String> libraryOrder(final Player player) {
        final List<String> lines = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Library).getCards()) {
            lines.add(card.getId() + ":" + card.getName());
        }
        return lines;
    }

    /** The library's order: sha256 hex of {@link #libraryOrder}, each line ending in a newline. */
    static String orderDigest(final Player player) {
        return sha256Lines(libraryOrder(player));
    }

    /** The library's cards, not their order: sha256 hex of the card names sorted, each line ending in a newline. */
    static String cardsDigest(final Player player) {
        final List<String> names = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Library).getCards()) {
            names.add(card.getName());
        }
        names.sort(null);
        return sha256Lines(names);
    }

    static String sha256Lines(final List<String> lines) {
        final StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
