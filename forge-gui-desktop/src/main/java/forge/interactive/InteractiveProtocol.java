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
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Strict NDJSON framing for the browser-owned human seat. */
final class InteractiveProtocol {
    static final String VERSION = "mtgx-forge-interactive/1";

    private InteractiveProtocol() {
    }

    /**
     * {@code startingChooser} is the seat that chooses who takes the first turn
     * (CR 103.1), or -1 for Forge's own coin toss; {@code gameNumber} is this
     * game's number in the caller's match (1 when not part of one).
     */
    record Config(String session, int humanSeat, List<Path> decks, long seed, String aiProfile,
                  int startingChooser, int gameNumber, List<Integer> humanSeats, List<String> names) {
        Config {
            decks = List.copyOf(decks);
            humanSeats = humanSeats == null ? List.of(humanSeat) : List.copyOf(humanSeats);
            names = names == null ? List.of() : List.copyOf(names);
        }

        Config(final String session, final int humanSeat, final List<Path> decks, final long seed,
               final String aiProfile, final int startingChooser, final int gameNumber) {
            this(session, humanSeat, decks, seed, aiProfile, startingChooser, gameNumber, null, null);
        }

        /**
         * A TABLE (mtgx, 2026-09-27): more than one browser seat in this one game. Each seat
         * gets its own tagged channel ({@code "to"} on the way out, {@code "seat"} on the way
         * in) and its own sequence; nothing about the one-seat wire changes.
         */
        boolean isTable() {
            return humanSeats.size() > 1;
        }
    }

    /** The fixed requestId of a table seat's concession while it has no request of its own. */
    static final String SEAT_CONCEDE_REQUEST = "seat-concede";

    record InputMessage(String requestId, String inputId, String kind, JsonObject action,
                        JsonObject original) {
    }

    static final class ProtocolException extends Exception {
        private final String code;

        ProtocolException(final String code, final String message) {
            super(message);
            this.code = code;
        }

        ProtocolException(final String code, final String message, final Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    static Config readConfig(final String line) throws ProtocolException {
        final JsonObject json = parseObject(line, "invalid_config", "config");
        requireExactString(json, "protocol", VERSION, "invalid_config");
        requireExactString(json, "type", "config", "invalid_config");

        final String session = requireString(json, "session", "invalid_config");
        if (session.isBlank() || session.length() > 256) {
            throw new ProtocolException("invalid_config", "session must be 1..256 non-blank characters");
        }

        final int humanSeat = requireInt(json, "humanSeat", "invalid_config");
        if (humanSeat != 0 && humanSeat != 1) {
            throw new ProtocolException("invalid_config", "humanSeat must be 0 or 1");
        }
        if (!json.has("seed") || !json.get("seed").isJsonPrimitive()
                || !json.getAsJsonPrimitive("seed").isNumber()) {
            throw new ProtocolException("invalid_config", "seed must be an integer");
        }
        final long seed;
        try {
            seed = new BigDecimal(json.get("seed").getAsString()).longValueExact();
        } catch (RuntimeException e) {
            throw new ProtocolException("invalid_config", "seed must be a signed 64-bit integer", e);
        }

        final String aiProfile = requireString(json, "aiProfile", "invalid_config");
        if (!"Default".equals(aiProfile)) {
            throw new ProtocolException("invalid_config", "aiProfile must be exactly 'Default'");
        }

        if (!json.has("decks") || !json.get("decks").isJsonArray()
                || json.getAsJsonArray("decks").size() != 2) {
            throw new ProtocolException("invalid_config", "decks must contain exactly two absolute paths");
        }
        final List<Path> decks = new ArrayList<>(2);
        for (JsonElement value : json.getAsJsonArray("decks")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new ProtocolException("invalid_config", "each deck path must be a string");
            }
            final Path path;
            try {
                path = Path.of(value.getAsString()).normalize();
            } catch (RuntimeException e) {
                throw new ProtocolException("invalid_config", "invalid deck path", e);
            }
            if (!path.isAbsolute()) {
                throw new ProtocolException("invalid_config", "deck paths must be absolute: " + path);
            }
            decks.add(path);
        }
        // Optional: a game of a longer match whose play/draw choice belongs to a
        // particular seat. Absent keeps the coin toss of a one-game match.
        int startingChooser = -1;
        if (json.has("startingChooser") && !json.get("startingChooser").isJsonNull()) {
            startingChooser = requireInt(json, "startingChooser", "invalid_config");
            if (startingChooser != 0 && startingChooser != 1) {
                throw new ProtocolException("invalid_config", "startingChooser must be 0, 1 or null");
            }
        }
        int gameNumber = 1;
        if (json.has("gameNumber") && !json.get("gameNumber").isJsonNull()) {
            gameNumber = requireInt(json, "gameNumber", "invalid_config");
            if (gameNumber < 1 || gameNumber > 99) {
                throw new ProtocolException("invalid_config", "gameNumber must be 1..99");
            }
        }
        // Optional (mtgx tables, 2026-09-27): every seat a browser plays. Absent is exactly
        // [humanSeat], the one-seat game this protocol has always been.
        List<Integer> humanSeats = null;
        if (json.has("humanSeats") && !json.get("humanSeats").isJsonNull()) {
            if (!json.get("humanSeats").isJsonArray()) {
                throw new ProtocolException("invalid_config", "humanSeats must be an array");
            }
            final List<Integer> seats = new ArrayList<>();
            for (JsonElement value : json.getAsJsonArray("humanSeats")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                        || (value.getAsDouble() != 0 && value.getAsDouble() != 1)) {
                    throw new ProtocolException("invalid_config", "humanSeats entries must be 0 or 1");
                }
                final int seat = value.getAsInt();
                if (seats.contains(seat)) {
                    throw new ProtocolException("invalid_config", "humanSeats must not repeat a seat");
                }
                seats.add(seat);
            }
            if (seats.isEmpty() || seats.get(0) != humanSeat) {
                throw new ProtocolException("invalid_config", "humanSeats must start with humanSeat");
            }
            humanSeats = seats;
        }
        List<String> names = null;
        if (json.has("names") && !json.get("names").isJsonNull()) {
            if (!json.get("names").isJsonArray() || json.getAsJsonArray("names").size() != 2) {
                throw new ProtocolException("invalid_config", "names must contain exactly two display names");
            }
            names = new ArrayList<>(2);
            for (JsonElement value : json.getAsJsonArray("names")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new ProtocolException("invalid_config", "each name must be a string");
                }
                final String name = value.getAsString().trim();
                if (name.isEmpty() || name.length() > 40 || !name.matches("[\\p{L}\\p{N} ._'-]+")) {
                    throw new ProtocolException("invalid_config",
                            "each name must be 1..40 letters, digits, spaces or ._'-");
                }
                names.add(name);
            }
        }
        return new Config(session, humanSeat, decks, seed, aiProfile, startingChooser, gameNumber,
                humanSeats, names);
    }

    static String bestEffortSession(final String line) {
        try {
            final JsonObject object = JsonParser.parseString(Objects.requireNonNullElse(line, ""))
                    .getAsJsonObject();
            if (object.has("session") && object.get("session").isJsonPrimitive()) {
                final String session = object.get("session").getAsString();
                if (!session.isBlank() && session.length() <= 256) {
                    return session;
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed config has no trustworthy routing identity.
        }
        return "unknown";
    }

    static void writeBootFatal(final PrintStream out, final String session, final String code,
                               final String message) {
        final JsonObject error = new JsonObject();
        error.addProperty("protocol", VERSION);
        error.addProperty("session", Objects.requireNonNullElse(session, "unknown"));
        error.addProperty("seq", 1);
        error.addProperty("type", "error");
        error.addProperty("fatal", true);
        error.addProperty("code", code);
        error.addProperty("message", Objects.requireNonNullElse(message, "startup failure"));
        synchronized (out) {
            out.println(error);
            out.flush();
        }
    }

    /** Where a channel's input lines come from: stdin itself, or one seat's queue of a table. */
    interface LineSource {
        /** The next line, or null at end of input. */
        String readLine() throws IOException;
    }

    static final class Channel {
        private final LineSource input;
        private final PrintStream output;
        private final String session;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean ended = new AtomicBoolean();
        /** A table seat's number, or null for the one-seat wire (no tag, exactly as before). */
        private final Integer seat;
        /** Every seat channel of a table, this one included; empty for a one-seat channel. */
        private final List<Channel> table;

        Channel(final BufferedReader input, final PrintStream output, final String session) {
            this(Objects.requireNonNull(input)::readLine, output, session, null, List.of());
        }

        private Channel(final LineSource input, final PrintStream output, final String session,
                        final Integer seat, final List<Channel> table) {
            this.input = Objects.requireNonNull(input);
            this.output = Objects.requireNonNull(output);
            this.session = Objects.requireNonNull(session);
            this.seat = seat;
            this.table = table;
        }

        /**
         * One channel per table seat over a single stdin/stdout. Outgoing messages carry
         * {@code "to": seat} and each seat has its own sequence; incoming lines are routed by
         * their {@code "seat"} field ({@link SeatDemux}). A fatal on any seat is sent to every
         * seat, because a table has one game.
         */
        static List<Channel> table(final SeatDemux demux, final PrintStream output, final String session,
                                   final List<Integer> seats) {
            final List<Channel> channels = new java.util.concurrent.CopyOnWriteArrayList<>();
            for (int seat : seats) {
                channels.add(new Channel(demux.source(seat), output, session, seat, channels));
            }
            return List.copyOf(channels);
        }

        Integer seat() {
            return seat;
        }

        String session() {
            return session;
        }

        boolean isEnded() {
            return ended.get();
        }

        JsonObject hello(final String forgeCommit, final String forgeVersion, final String aiProfile,
                         final int humanSeat, final long seed) throws ProtocolException {
            if (sequence.get() != 0) {
                throw new ProtocolException("engine", "hello must be the first protocol message");
            }
            final JsonObject body = new JsonObject();
            body.addProperty("forgeCommit", forgeCommit);
            body.addProperty("forgeVersion", forgeVersion);
            body.addProperty("aiProfile", aiProfile);
            body.addProperty("humanSeat", humanSeat);
            body.addProperty("seed", seed);
            return send("hello", body);
        }

        JsonObject send(final String type, final JsonObject body) throws ProtocolException {
            if (ended.get()) {
                throw new ProtocolException("engine", "cannot emit after terminal/fatal message");
            }
            return sendInternal(type, body, false);
        }

        JsonObject ack(final String requestId, final String inputId, final String kind,
                       final boolean accepted, final String reason) throws ProtocolException {
            final JsonObject body = new JsonObject();
            body.addProperty("requestId", requestId);
            body.addProperty("inputId", inputId);
            body.addProperty("kind", kind);
            body.addProperty("accepted", accepted);
            if (reason != null && !reason.isBlank()) {
                body.addProperty("reason", reason);
            }
            return send("ack", body);
        }

        void fatal(final String code, final String message, final String requestId,
                   final String method, final String kind) {
            fatalOne(code, message, requestId, method, kind);
            for (Channel sibling : table) {
                if (sibling != this) {
                    sibling.fatalOne(code, message, null, method, null);
                }
            }
        }

        private void fatalOne(final String code, final String message, final String requestId,
                              final String method, final String kind) {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            final JsonObject body = new JsonObject();
            body.addProperty("fatal", true);
            body.addProperty("code", code);
            body.addProperty("message", Objects.requireNonNullElse(message, code));
            if (requestId != null) {
                body.addProperty("requestId", requestId);
            }
            if (method != null) {
                body.addProperty("method", method);
            }
            if (kind != null) {
                body.addProperty("kind", kind);
            }
            try {
                sendInternal("error", body, true);
            } catch (ProtocolException e) {
                System.err.println("[forge.interactive] unable to write fatal message: " + e.getMessage());
            }
        }

        void terminal(final String game, final Integer winner, final String reason,
                      final Integer turns) throws ProtocolException {
            terminal(game, winner, reason, turns, null);
        }

        /** {@code losses}: each losing seat's GameLossReason (additive, 2026-09-25); null omits it. */
        void terminal(final String game, final Integer winner, final String reason,
                      final Integer turns, final JsonArray losses) throws ProtocolException {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            final JsonObject body = new JsonObject();
            body.addProperty("game", game);
            if (winner == null) {
                body.add("winner", null);
            } else {
                body.addProperty("winner", winner);
            }
            body.addProperty("reason", reason);
            if (turns != null) {
                body.addProperty("turns", turns);
            }
            if (losses != null) {
                body.add("losses", losses);
            }
            sendInternal("terminal", body, true);
        }

        InputMessage readInput() throws IOException, ProtocolException {
            final String line = input.readLine();
            if (line == null) {
                throw new ProtocolException("eof", "stdin closed while Forge was awaiting human input");
            }
            final JsonObject json = parseObject(line, "protocol_mismatch", "input");
            requireExactString(json, "protocol", VERSION, "protocol_mismatch");
            requireExactString(json, "session", session, "protocol_mismatch");
            requireExactString(json, "type", "input", "protocol_mismatch");
            if (seat != null) {
                if (!json.has("seat") || !json.get("seat").isJsonPrimitive()
                        || !json.getAsJsonPrimitive("seat").isNumber()
                        || json.get("seat").getAsDouble() != seat) {
                    throw new ProtocolException("protocol_mismatch", "table input must carry seat " + seat);
                }
            } else if (json.has("seat")) {
                throw new ProtocolException("protocol_mismatch", "one-seat input must not carry seat");
            }
            if (json.has("seq")) {
                throw new ProtocolException("protocol_mismatch", "input messages must not carry seq");
            }
            final String requestId = requireString(json, "requestId", "protocol_mismatch");
            final String inputId = requireString(json, "inputId", "protocol_mismatch");
            final String kind = requireString(json, "kind", "protocol_mismatch");
            if (requestId.isBlank() || inputId.isBlank() || kind.isBlank()) {
                throw new ProtocolException("protocol_mismatch", "requestId, inputId and kind must be non-blank");
            }
            if (!json.has("action") || !json.get("action").isJsonObject()) {
                throw new ProtocolException("protocol_mismatch", "input.action must be an object");
            }
            final JsonObject action = json.getAsJsonObject("action");
            requireString(action, "type", "protocol_mismatch");
            return new InputMessage(requestId, inputId, kind, action, json.deepCopy());
        }

        private JsonObject sendInternal(final String type, final JsonObject body,
                                        final boolean allowEnded) throws ProtocolException {
            synchronized (output) {
                if (!allowEnded && ended.get()) {
                    throw new ProtocolException("engine", "protocol session has ended");
                }
                // Allocate the sequence number under the same lock as the write. Requests,
                // state events and acks are produced by different threads; assigning outside
                // this critical section could put N+1 on the wire before N.
                final JsonObject message = new JsonObject();
                message.addProperty("protocol", VERSION);
                message.addProperty("session", session);
                message.addProperty("seq", sequence.incrementAndGet());
                if (seat != null) {
                    message.addProperty("to", seat);
                }
                message.addProperty("type", type);
                if (body != null) {
                    for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                        if (message.has(entry.getKey())) {
                            throw new ProtocolException("engine", "body attempted to replace envelope field "
                                    + entry.getKey());
                        }
                        message.add(entry.getKey(), entry.getValue());
                    }
                }
                output.println(message);
                output.flush();
                if (output.checkError()) {
                    throw new ProtocolException("eof", "stdout closed while writing protocol message");
                }
                return message;
            }
        }
    }

    /**
     * Routes a table's stdin to its seats: one reader thread, one queue per seat, by the line's
     * {@code "seat"}. A line naming no configured seat goes to the first seat, whose channel
     * then refuses it as a protocol mismatch (a fatal for the table), so a bad line is never
     * silently dropped. End of input reaches every seat.
     */
    static final class SeatDemux {
        private static final String EOF = new String("<eof>");
        private final BufferedReader input;
        private final Map<Integer, java.util.concurrent.BlockingQueue<String>> queues =
                new java.util.LinkedHashMap<>();
        private volatile Thread reader;

        SeatDemux(final BufferedReader input, final List<Integer> seats) {
            this.input = Objects.requireNonNull(input);
            for (int seat : seats) {
                queues.put(seat, new java.util.concurrent.LinkedBlockingQueue<>());
            }
        }

        LineSource source(final int seat) {
            final java.util.concurrent.BlockingQueue<String> queue = queues.get(seat);
            return () -> {
                start();
                final String line;
                try {
                    line = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while awaiting seat " + seat + " input", e);
                }
                if (line == EOF) {
                    queue.add(EOF);
                    return null;
                }
                return line;
            };
        }

        synchronized void start() {
            if (reader != null) {
                return;
            }
            reader = new Thread(this::pump, "Forge Interactive Table Demux");
            reader.setDaemon(true);
            reader.start();
        }

        static Integer seatOf(final String line) {
            try {
                final JsonElement parsed = JsonParser.parseString(line);
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("seat")
                        && parsed.getAsJsonObject().get("seat").isJsonPrimitive()
                        && parsed.getAsJsonObject().getAsJsonPrimitive("seat").isNumber()) {
                    final double seat = parsed.getAsJsonObject().get("seat").getAsDouble();
                    return seat == Math.rint(seat) ? (int) seat : null;
                }
            } catch (RuntimeException ignored) {
                // Unroutable: the first seat's channel refuses it.
            }
            return null;
        }

        private void pump() {
            try {
                String line;
                while ((line = input.readLine()) != null) {
                    final Integer seat = seatOf(line);
                    final java.util.concurrent.BlockingQueue<String> queue =
                            seat != null && queues.containsKey(seat) ? queues.get(seat)
                                    : queues.values().iterator().next();
                    queue.add(line);
                }
            } catch (IOException ignored) {
                // End of input, as far as the seats are concerned.
            }
            for (java.util.concurrent.BlockingQueue<String> queue : queues.values()) {
                queue.add(EOF);
            }
        }
    }

    private static JsonObject parseObject(final String line, final String code,
                                          final String description) throws ProtocolException {
        if (line == null) {
            throw new ProtocolException(code, "missing " + description + " line");
        }
        try {
            final JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                throw new ProtocolException(code, description + " must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (ProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProtocolException(code, "malformed " + description + " JSON", e);
        }
    }

    private static String requireString(final JsonObject json, final String field,
                                        final String code) throws ProtocolException {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isString()) {
            throw new ProtocolException(code, field + " must be a string");
        }
        return json.get(field).getAsString();
    }

    private static void requireExactString(final JsonObject json, final String field,
                                           final String expected, final String code)
            throws ProtocolException {
        final String actual = requireString(json, field, code);
        if (!expected.equals(actual)) {
            throw new ProtocolException(code, field + " must be exactly '" + expected + "'");
        }
    }

    private static int requireInt(final JsonObject json, final String field,
                                  final String code) throws ProtocolException {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isNumber()) {
            throw new ProtocolException(code, field + " must be an integer");
        }
        try {
            final int value = json.get(field).getAsInt();
            if (json.get(field).getAsDouble() != value) {
                throw new NumberFormatException("not integral");
            }
            return value;
        } catch (RuntimeException e) {
            throw new ProtocolException(code, field + " must be a 32-bit integer", e);
        }
    }
}
