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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.player.Player;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Lane sdcc-replay-sample-1113 (research; nothing creates one unless a bench replay sets {@code replay.watchOut}): the
 * watch table's recorder ({@link InteractiveWatch.Watcher}) on a game somebody else built and runs: a bench replay of a
 * tape line ({@code forge.bench.RlActorBench} replay mode). It writes, under {@code <dir>/<game uid>/}, what a recorded
 * watch table leaves behind:
 *
 * <ul>
 *   <li>{@code stream.jsonl}: both seats' channels as a watch table's stdout: each seat's {@code hello}, every game
 *   event as that seat's GUI encodes it, a {@code state} with {@code decision: {n, seat, turn, phase}} at every priority
 *   decision, the final {@code game-completed} states and the {@code terminal};</li>
 *   <li>{@code config.json}: the config line of such a table (session, seed, decks, names, watchSeats, seatAi) plus a
 *   {@code replay} object: where the game came from (tape, line, game uid, jar, the tape's digest) and how the replay
 *   went (the replay's digest and whether it equals the tape's);</li>
 *   <li>{@code deck0.dck}, {@code deck1.dck}: copies of the seats' deck files.</li>
 * </ul>
 *
 * The recorder only reads the game (events and the seats' views), on the game thread. The seats' names are the bench's
 * lobby names ({@code Seat0}, {@code Seat1}): the replayed game is not given other names than the game it replays.
 */
public final class WatchRecorder {
    private final Game game;
    private final Path dir;
    private final PrintStream out;
    private final List<InteractiveProtocol.Channel> channels;
    private final InteractiveWatch.Watcher watcher;
    private final JsonObject config;
    private final JsonObject source;

    private WatchRecorder(final Game game, final Path dir, final PrintStream out,
            final List<InteractiveProtocol.Channel> channels, final InteractiveWatch.Watcher watcher,
            final JsonObject config, final JsonObject source) {
        this.game = game;
        this.dir = dir;
        this.out = out;
        this.channels = channels;
        this.watcher = watcher;
        this.config = config;
        this.source = source;
    }

    /**
     * Attach to {@code game} (created, its seats bound, not started), the replay of tape line {@code t}
     * ({@code tape}:{@code line}); {@code forced}: the replay answers from the line's decision log.
     */
    public static WatchRecorder attach(final Game game, final Path root, final JsonObject t, final String tape,
            final int line, final String jarSha, final boolean forced) {
        try {
            final String uid = t.get("game_uid").getAsString();
            final long seed = t.get("seed").getAsLong();
            final String session = "rs-" + uid;
            final Path dir = root.resolve(uid);
            Files.createDirectories(dir);
            final JsonArray decks = new JsonArray(), names = new JsonArray(), seatAi = new JsonArray();
            final JsonArray tdecks = t.getAsJsonArray("decks");
            for (int s = 0; s < tdecks.size(); s++) {
                final Path to = dir.resolve("deck" + s + ".dck");
                Files.copy(Path.of(tdecks.get(s).getAsJsonObject().get("path").getAsString()), to,
                        StandardCopyOption.REPLACE_EXISTING);
                decks.add(to.toString());
                names.add(game.getRegisteredPlayers().get(s).getName());
                final String ctl = t.getAsJsonArray("controllers").get(s).getAsString();
                seatAi.add(ctl.startsWith("forge") ? "classic" : ctl.startsWith("lookahead") ? "lookahead" : "policy");
            }
            final JsonObject config = new JsonObject();
            config.addProperty("protocol", InteractiveProtocol.VERSION);
            config.addProperty("type", "config");
            config.addProperty("session", session);
            config.addProperty("humanSeat", 0);
            config.add("decks", decks);
            config.addProperty("seed", seed);
            config.addProperty("aiProfile", "Default");
            config.add("names", names);
            final JsonArray seats = new JsonArray();
            seats.add(0);
            seats.add(1);
            config.add("watchSeats", seats);
            config.add("seatAi", seatAi);
            final JsonObject source = new JsonObject();
            source.addProperty("tape", tape);
            source.addProperty("line", line);
            source.addProperty("game_uid", uid);
            source.addProperty("jar_sha", jarSha);
            source.addProperty("forced", forced);
            source.add("controllers", t.get("controllers"));
            source.add("digest_tape", t.get("digest"));
            for (String k : new String[] {"result", "reason", "turns", "starting_seat", "versions", "start_state"}) {
                if (t.has(k)) {
                    source.add(k, t.get(k));
                }
            }
            final PrintStream out = new PrintStream(new BufferedOutputStream(
                    Files.newOutputStream(dir.resolve("stream.jsonl")), 1 << 16), false, StandardCharsets.UTF_8);
            final List<InteractiveProtocol.Channel> channels =
                    InteractiveProtocol.Channel.watch(out, session, List.of(0, 1));
            for (InteractiveProtocol.Channel channel : channels) {
                channel.hello(InteractiveMain.forgeCommit(), InteractiveMain.nonBlankVersion(), "Default",
                        channel.seat(), seed);
            }
            for (Player player : game.getPlayers()) {
                player.updateOpponentsForView(); // as a watch table's prepare (views only)
            }
            final InteractiveWatch.Watcher watcher = new InteractiveWatch.Watcher(game, channels);
            game.subscribeToEvents(watcher);
            game.addPriorityDecisionListener(new forge.game.PriorityDecisionListener() {
                @Override
                public void beforeDecision(final Player p) {
                    try {
                        watcher.beforeDecision(p);
                    } catch (InteractiveWatch.WatchAbort e) {
                        // a failed recorder stops recording; the replayed game is not the recorder's to stop
                    }
                }

                @Override
                public void afterDecision(final Player p, final List<forge.game.spellability.SpellAbility> chosen) {
                    watcher.afterDecision(p, chosen);
                }
            });
            return new WatchRecorder(game, dir, out, channels, watcher, config, source);
        } catch (IOException | InteractiveProtocol.ProtocolException e) {
            throw new IllegalStateException("watch recorder: " + e, e);
        }
    }

    /**
     * After the game: the final states and the terminals (only for a game that ended as its tape says: {@code ok}), the
     * files closed, {@code config.json} written. Returns the replay row's {@code watch} object.
     */
    public JsonObject finish(final String digestReplay, final boolean ok) {
        final JsonObject r = new JsonObject();
        r.addProperty("dir", dir.toString());
        boolean complete = false;
        String error = null;
        try {
            if (!ok) {
                error = "the replayed game is void";
            } else if (watcher.hasFailed()) {
                error = "the recorder failed during the game";
            } else {
                watcher.emitFinalStates();
                if (watcher.hasFailed()) {
                    error = "the recorder failed at the final states";
                } else {
                    complete = true;
                    for (InteractiveProtocol.Channel channel : channels) {
                        complete &= InteractiveMain.emitTerminal(channel, game, game.getMatch().getPlayers());
                    }
                    if (!complete) {
                        error = "no terminal";
                    }
                }
            }
        } catch (InteractiveProtocol.ProtocolException | RuntimeException e) {
            complete = false;
            error = String.valueOf(e);
        }
        out.flush();
        final boolean writeError = out.checkError();
        out.close();
        if (writeError) {
            complete = false;
            error = error == null ? "write error" : error;
        }
        final JsonElement tapeDigest = source.get("digest_tape");
        final boolean equal = digestReplay != null && tapeDigest != null && !tapeDigest.isJsonNull()
                && digestReplay.equals(tapeDigest.getAsString());
        source.addProperty("digest_replay", digestReplay);
        source.addProperty("equal", equal);
        source.addProperty("complete", complete);
        if (error != null) {
            source.addProperty("error", error);
        }
        config.add("replay", source);
        try {
            Files.writeString(dir.resolve("config.json"), config + "\n", StandardCharsets.UTF_8);
            r.addProperty("bytes", Files.size(dir.resolve("stream.jsonl")));
        } catch (IOException e) {
            complete = false;
            error = error == null ? String.valueOf(e) : error;
        }
        r.addProperty("complete", complete);
        r.addProperty("equal", equal);
        if (error != null) {
            r.addProperty("error", error);
        }
        return r;
    }
}
