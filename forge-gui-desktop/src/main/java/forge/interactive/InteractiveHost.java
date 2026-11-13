/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import forge.util.GameContext;
import forge.util.IdScope;
import forge.util.MyRandom;
import forge.util.ScopedSlot;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * THE MULTI-GAME HOST (lane shared-engine-1103; a separate entry point, used only when the mtgx play host is launched
 * with {@code MTGX_FORGE_INTERACTIVE_SHARED_HOST=1}; {@link InteractiveMain} is unchanged and still one game per JVM).
 *
 * <p>One JVM initializes Forge's model once (the card database, card scripts, tokens, AI profiles: read-only after
 * init) and then plays any number of concurrent games, each exactly as {@link InteractiveMain#runSession} plays one in its
 * own JVM. The launch spec (every {@code -D} property: the look-ahead spec, the AI fixes, the tape mode, the commit
 * stamp) is the host's, so a host serves the games of ONE spec key, as one warm spare does.</p>
 *
 * <p><b>Wire.</b> A Unix-domain socket ({@code -Dforge.interactive.host.socket=<path>}); one connection per game. Into
 * the host: exactly the bytes a game's stdin gets today (the config line, then its inputs). Out of the host: one frame
 * per line, {@code O<line>} for the game's stdout and {@code E<line>} for its stderr ({@code o}/{@code e}: a last line
 * with no newline), then {@code X<status>}, the exit status its JVM would have had. The host's own stdout carries one
 * {@code host-ready} line; its stdin closing (the parent gone) ends it.</p>
 *
 * <p><b>Isolation.</b> Each game runs on its own connection thread in its own {@link GameContext}: its own random stream
 * ({@link MyRandom#setThreadRandom}; the process-wide stream is replaced by one that refuses to be read, so a draw that
 * escaped its game fails loudly instead of leaking), its own id counters ({@link IdScope}, started where a fresh JVM's
 * stand after the same init, so a hosted game's ids are a lone JVM's), its own AI cache scope, its own GUI binding,
 * stderr and config-derived state ({@link ScopedSlot}). Forge's pooled threads (the game pool, the delayed pool, the
 * EDT) carry the submitting game's state ({@link GameContext#wrap}).</p>
 *
 * <p><b>Not hosted:</b> a "Continue from here" rebuild (its AI wall clock switch is process-wide), a tape replay, resume
 * or restore (it reads ONE tape file named in the launch spec, so every hosted game would replay it; a tape RECORD writes
 * each game's own stderr and is hosted), an offline review and an AI replay (both name a game's own files): the mtgx host
 * gives those their own JVM as before (its {@code UNSHARABLE} list), and this host refuses to start with any of them
 * ({@link #notHosted}).</p>
 */
public final class InteractiveHost {
    static final String PROTOCOL = "mtgx-forge-host/1";

    /** A hosted game's stderr (its frame stream); null on host threads. */
    private static final ScopedSlot<OutputStream> GAME_ERR = new ScopedSlot<>();

    /** The host's own stderr (never a game's). */
    private static PrintStream HOST_ERR = System.err;

    private InteractiveHost() {
    }

    public static void main(final String[] args) throws IOException {
        final PrintStream hostOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        final OutputStream realErr = new FileOutputStream(FileDescriptor.err);
        // every print to System.out / System.err goes to the stderr of the game whose thread prints it
        final PrintStream routed = new PrintStream(new RoutedErr(realErr), true, StandardCharsets.UTF_8);
        HOST_ERR = new PrintStream(realErr, true, StandardCharsets.UTF_8);
        System.setOut(routed);
        System.setErr(routed);
        final String notHosted = notHosted();
        if (notHosted != null) {
            System.err.println("[forge.host] " + notHosted);
            System.exit(2);
        }
        final String socket = System.getProperty("forge.interactive.host.socket");
        if (socket == null || socket.isBlank()) {
            System.err.println("[forge.host] -Dforge.interactive.host.socket is required");
            System.exit(2);
        }

        final long t0 = System.nanoTime();
        // exits 3 on failure, as a spare's prewarm does. The init is a lone JVM's, token preload included (a look-ahead or
        // policy spec preloads, a classic one fills the synchronized table lazily): a second preload would add every
        // token again and change which art a token draws, so the host never preloads on its own.
        InteractiveMain.prewarmModel();
        final int[] bootIds = forge.game.GlobalIds.capture();
        GameContext.register(new GameContext.Carrier() {
            @Override
            public Object capture() {
                return forge.ai.AiCache.captureScope();
            }

            @Override
            public void install(final Object captured) {
                forge.ai.AiCache.installScope(captured);
            }
        });
        GameContext.enableHosting();
        if (!"false".equals(System.getProperty("forge.interactive.host.strictRandom"))) {
            MyRandom.setRandom(new EscapedRandom());
        }

        final Path path = Path.of(socket);
        Files.deleteIfExists(path);
        final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(path));
        final Thread parentWatch = new Thread(InteractiveHost::exitWhenStdinCloses, "Forge Host stdin");
        parentWatch.setDaemon(true);
        parentWatch.start();
        hostOut.println("{\"type\":\"host-ready\",\"protocol\":\"" + PROTOCOL + "\",\"pid\":" + ProcessHandle.current().pid()
                + ",\"forgeCommit\":\"" + InteractiveMain.forgeCommit() + "\",\"initMs\":"
                + (System.nanoTime() - t0) / 1_000_000 + ",\"bootIds\":" + java.util.Arrays.toString(bootIds) + "}");

        final AtomicInteger games = new AtomicInteger();
        while (true) {
            final SocketChannel connection = server.accept();
            final int n = games.incrementAndGet();
            // no inherited thread-locals: a game thread starts with nothing of any other game (or of the host)
            final Thread game = new Thread(null, () -> serve(connection, n, bootIds), "Forge Host Game-" + n, 0, false);
            game.setDaemon(true);
            game.start();
        }
    }

    /**
     * Why this JVM's launch spec cannot host games, or null. state-machines-1113 (survey M1/M10): the class comment always
     * excluded tape replays, but only the continuation was refused; a host started with
     * {@code -Dforge.interactive.aiTape=forced:<file>} would have replayed that one tape into every game it served. The
     * rule is the mtgx host's {@code UNSHARABLE} list (tools/forge/interactive-shared-host.ts): a continuation, a tape
     * forced/resume/restore, a review, an AI replay. {@code aiTape=record} stays hosted (the live server records).
     */
    static String notHosted() {
        if (System.getProperty("forge.interactive.continuation") != null) {
            return "a continuation rebuild is never hosted";
        }
        final String tape = System.getProperty("forge.interactive.aiTape");
        if (tape != null && (tape.startsWith("forced:") || tape.startsWith("resume:") || tape.startsWith("restore:"))) {
            return "an AI tape replay (forge.interactive.aiTape=" + tape.substring(0, tape.indexOf(':'))
                    + ") is never hosted";
        }
        if (System.getProperty("forge.interactive.review") != null) {
            return "an offline review (forge.interactive.review) is never hosted";
        }
        if (System.getProperty("forge.interactive.aiReplay") != null) {
            return "an AI replay (forge.interactive.aiReplay) is never hosted";
        }
        return null;
    }

    private static void exitWhenStdinCloses() {
        try {
            while (System.in.read() >= 0) {
                // the parent writes nothing; EOF means it is gone
            }
        } catch (IOException ignored) {
            // the same: the parent is gone
        }
        System.exit(0);
    }

    /** One game: its context, its streams, {@link InteractiveMain#runSession}, its exit frame. */
    static void serve(final SocketChannel connection, final int n, final int[] bootIds) {
        final FrameSink sink = new FrameSink(Channels.newOutputStream(connection));
        int status = 1;
        GameContext context = null;
        try (connection) {
            context = GameContext.open("game-" + n);
            MyRandom.setThreadRandom(null);
            final Object gameIds = IdScope.scopeAt(bootIds);
            IdScope.install(gameIds);
            context.setGameIds(gameIds);
            forge.ai.AiCache.openScope();
            final LineFramer err = new LineFramer(sink, 'E', 'e', false);
            // lane shared-engine-1104: the protocol stream fails when the connection is gone, as a lone JVM's stdout
            // does on a closed pipe, so the game's channel sees checkError() and stops (a watch game never reads stdin)
            final LineFramer out = new LineFramer(sink, 'O', 'o', true);
            GAME_ERR.set(err);
            final PrintStream protocolOut = new PrintStream(out, true, StandardCharsets.UTF_8);
            final BufferedReader input = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(connection), StandardCharsets.UTF_8));
            try {
                status = InteractiveMain.runSession(input, protocolOut, true);
            } catch (Throwable failure) {
                System.err.println("[forge.host] game " + n + " failed: " + failure);
                failure.printStackTrace(System.err);
                status = 1;
            }
            protocolOut.flush();
            HOST_ERR.println("[forge.host] game " + n + " status " + status + " unscopedIds " + GameContext.unscopedIds());
            out.close();
            err.close();
            sink.exit(status);
        } catch (IOException ignored) {
            // the mtgx host closed the connection (a stopped game): nothing left to tell it
        } finally {
            if (context != null) {
                context.end(); // lane shared-engine-1104: what the game still holds (look-ahead pools), as a JVM exit
            }
            GameContext.clearThread();
        }
    }

    /** System.out / System.err: the calling game's stderr, else the host's own. */
    private static final class RoutedErr extends OutputStream {
        private final OutputStream host;

        RoutedErr(final OutputStream host) {
            this.host = host;
        }

        private OutputStream target() {
            final OutputStream game = GAME_ERR.get();
            return game != null ? game : host;
        }

        @Override
        public void write(final int b) throws IOException {
            target().write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            target().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            target().flush();
        }
    }

    /** One connection's frames, whole lines only, from any of the game's threads. */
    static final class FrameSink {
        private final OutputStream raw;
        private boolean broken;

        FrameSink(final OutputStream raw) {
            this.raw = raw;
        }

        /** False once the connection is gone: the frame is dropped, as a dead pipe drops a write. */
        synchronized boolean frame(final char kind, final byte[] line, final int len) {
            if (broken) {
                return false;
            }
            try {
                raw.write(kind);
                raw.write(line, 0, len);
                raw.write('\n');
                raw.flush();
                return true;
            } catch (IOException e) {
                broken = true;
                return false;
            }
        }

        synchronized void exit(final int status) {
            final byte[] b = Integer.toString(status).getBytes(StandardCharsets.US_ASCII);
            frame('X', b, b.length);
        }
    }

    /** A byte stream cut into frames at each newline ({@code whole}); a trailing partial line on close ({@code part}). */
    static final class LineFramer extends OutputStream {
        private final FrameSink sink;
        private final char whole;
        private final char part;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        /**
         * Lane shared-engine-1104: throw when the connection is gone (the game's protocol stream: its PrintStream then
         * reports checkError(), as a lone JVM's stdout on a closed pipe does). Never for stderr: System.err is ONE
         * routed PrintStream shared by every game, whose encoder must never be left mid-write by an exception.
         */
        private final boolean failWhenGone;

        LineFramer(final FrameSink sink, final char whole, final char part) {
            this(sink, whole, part, false);
        }

        LineFramer(final FrameSink sink, final char whole, final char part, final boolean failWhenGone) {
            this.sink = sink;
            this.whole = whole;
            this.part = part;
            this.failWhenGone = failWhenGone;
        }

        private void emit() throws IOException {
            final boolean sent = sink.frame(whole, line.toByteArray(), line.size());
            line.reset();
            if (!sent && failWhenGone) {
                throw new IOException("[forge.host] the game's connection is closed");
            }
        }

        @Override
        public synchronized void write(final int b) throws IOException {
            if (b == '\n') {
                emit();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(final byte[] b, final int off, final int len) throws IOException {
            int start = off;
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    line.write(b, start, i - start);
                    start = i + 1;
                    emit();
                }
            }
            line.write(b, start, off + len - start);
        }

        @Override
        public synchronized void close() {
            if (line.size() > 0) {
                sink.frame(part, line.toByteArray(), line.size());
                line.reset();
            }
        }
    }

    /**
     * The process-wide random stream in a host: every game draws from its own, so a draw from this one is game state
     * that escaped its game (a thread that carried no game). It fails loudly rather than mixing two games' streams.
     */
    static final class EscapedRandom extends Random {
        private static final long serialVersionUID = 1L;

        @Override
        protected int next(final int bits) {
            throw new IllegalStateException("[forge.host] a draw from the process-wide random stream on thread "
                    + Thread.currentThread().getName() + " (game state escaped its game)");
        }
    }
}
