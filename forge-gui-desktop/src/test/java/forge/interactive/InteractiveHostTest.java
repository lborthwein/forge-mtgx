package forge.interactive;

import forge.game.GlobalIds;
import forge.game.trigger.Trigger;
import forge.util.GameContext;
import forge.util.IdScope;
import forge.util.MyRandom;
import forge.util.ScopedSlot;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * Lane shared-engine-1103: the multi-game host's isolation primitives (forge.util.GameContext, ScopedSlot, the pooled
 * thread carry in ThreadUtil) and its wire framing. Off (a one-game process) everything is the old static behaviour.
 */
public class InteractiveHostTest {
    /**
     * A one-thread pool like Forge's game pool (ThreadUtil.invokeInGameThread wraps exactly so), but the test's own:
     * Forge's shared pools must not keep threads created under this test's game state (they would carry it into the
     * next test class in this JVM; a real host never stops hosting).
     */
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newSingleThreadExecutor(
            r -> { final Thread t = new Thread(null, r, "test-pool", 0, false); t.setDaemon(true); return t; });

    @AfterMethod
    public void offAgain() {
        GameContext.stopHostingForTests();
        MyRandom.setThreadRandom(null);
        IdScope.close();
    }

    /** state-machines-1113 (survey M1/M10): a host refuses every per-JVM mode its class comment excludes. */
    @Test
    public void aHostRefusesTapeReviewAndContinuationSpecs() {
        assertNull(InteractiveHost.notHosted(), "an ordinary launch spec is hosted");
        for (String[] spec : new String[][] {
                {"forge.interactive.continuation", "rebuild", "continuation"},
                {"forge.interactive.aiTape", "forced:/tmp/tape.jsonl", "AI tape replay"},
                {"forge.interactive.aiTape", "resume:/tmp/tape.jsonl", "AI tape replay"},
                {"forge.interactive.aiTape", "restore:/tmp/tape.jsonl", "AI tape replay"},
                {"forge.interactive.review", "/tmp/review.json", "review"},
                {"forge.interactive.aiReplay", "/tmp/ai.jsonl", "AI replay"}}) {
            final String was = System.getProperty(spec[0]);
            System.setProperty(spec[0], spec[1]);
            try {
                final String refused = InteractiveHost.notHosted();
                assertTrue(refused != null && refused.contains(spec[2]), spec[0] + "=" + spec[1] + ": " + refused);
            } finally {
                if (was == null) {
                    System.clearProperty(spec[0]);
                } else {
                    System.setProperty(spec[0], was);
                }
            }
        }
        final String was = System.getProperty("forge.interactive.aiTape");
        try {
            System.setProperty("forge.interactive.aiTape", "off");
            assertNull(InteractiveHost.notHosted(), "aiTape=off is no tape");
            // the live server and the identity gate host games that RECORD their AI seat: each writes its own stderr
            System.setProperty("forge.interactive.aiTape", "record");
            assertNull(InteractiveHost.notHosted(), "a tape record is hosted");
        } finally {
            if (was == null) {
                System.clearProperty("forge.interactive.aiTape");
            } else {
                System.setProperty("forge.interactive.aiTape", was);
            }
        }
    }

    @Test
    public void offIsTheOldStaticBehaviour() {
        final ScopedSlot<String> slot = new ScopedSlot<>();
        slot.set("one");
        assertNull(GameContext.current());
        assertEquals(slot.get(), "one");
        final Runnable r = () -> { };
        assertSame(GameContext.wrap(r), r, "off: wrap returns the task itself");
        assertNull(GameContext.gameIds());
    }

    @Test
    public void twoHostedGamesNeverSeeEachOthersState() throws Exception {
        GameContext.enableHosting();
        final ScopedSlot<String> slot = new ScopedSlot<>();
        final AtomicReference<String> seenA = new AtomicReference<>();
        final AtomicReference<String> seenB = new AtomicReference<>();
        final AtomicReference<Random> poolRandomA = new AtomicReference<>();
        final AtomicReference<Integer> triggerB = new AtomicReference<>();
        final int[] boot = GlobalIds.capture();
        final Thread a = new Thread(null, () -> {
            final GameContext ctx = GameContext.open("a");
            final Object ids = IdScope.scopeAt(boot);
            IdScope.install(ids);
            ctx.setGameIds(ids);
            final Random ra = new Random(1);
            MyRandom.setThreadRandom(ra);
            slot.set("A");
            Trigger.resetIDs();                      // this game's own trigger counter only
            final CompletableFuture<Void> done = new CompletableFuture<>();
            POOL.execute(GameContext.wrap(() -> {    // a pooled thread carries game A's state
                seenA.set(slot.get());
                poolRandomA.set(MyRandom.getRandom());
                done.complete(null);
            }));
            done.join();
            assertEquals(GlobalIds.capture(ids)[3], 50000);
        }, "test-host-a", 0, false);
        final Thread b = new Thread(null, () -> {
            final GameContext ctx = GameContext.open("b");
            final Object ids = IdScope.scopeAt(boot);
            IdScope.install(ids);
            ctx.setGameIds(ids);
            MyRandom.setThreadRandom(new Random(2));
            final CompletableFuture<Void> done = new CompletableFuture<>();
            POOL.execute(GameContext.wrap(() -> {
                seenB.set(slot.get());               // never A's value
                triggerB.set(GlobalIds.capture(IdScope.capture())[3]);
                done.complete(null);
            }));
            done.join();
        }, "test-host-b", 0, false);
        a.start();
        a.join(TimeUnit.SECONDS.toMillis(30));
        b.start();
        b.join(TimeUnit.SECONDS.toMillis(30));
        assertEquals(seenA.get(), "A");
        assertNull(seenB.get(), "game B reads its own (unset) slot, not game A's");
        assertEquals(triggerB.get().intValue(), boot[3], "game A's trigger reset never touched game B's counter");
        assertEquals(Trigger.peekMaxId(), boot[3], "nor the process-wide one");
        assertNotSame(poolRandomA.get(), null);
        assertEquals(poolRandomA.get().nextInt(), new Random(1).nextInt(), "the pooled task drew from game A's stream");
    }

    @Test
    public void framesCarryEachStreamByteForByte() throws Exception {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final InteractiveHost.FrameSink sink = new InteractiveHost.FrameSink(wire);
        final InteractiveHost.LineFramer out = new InteractiveHost.LineFramer(sink, 'O', 'o');
        final InteractiveHost.LineFramer err = new InteractiveHost.LineFramer(sink, 'E', 'e');
        final byte[] a = "{\"a\":\"é\"}\n{\"b\"".getBytes(StandardCharsets.UTF_8);
        out.write(a, 0, a.length);
        err.write('x');
        err.write('\n');
        out.write(':');
        out.write('1');
        out.write('}');
        out.write('\n');
        err.write('y');
        err.close();
        sink.exit(0);
        assertEquals(new String(wire.toByteArray(), StandardCharsets.UTF_8),
                "O{\"a\":\"é\"}\nEx\nO{\"b\":1}\ney\nX0\n");
        assertTrue(true);
    }
    /**
     * Lane shared-engine-1104: a closed connection fails the game's protocol stream, as a lone JVM's stdout fails on a
     * closed pipe (its channel's checkError() then stops the game: a watch game never reads stdin, so this is how a
     * stopped watch game learns it), but never the shared stderr stream.
     */
    @Test
    public void aClosedConnectionFailsTheProtocolStreamOnly() throws Exception {
        final java.io.OutputStream closed = new java.io.OutputStream() {
            @Override
            public void write(final int b) throws java.io.IOException {
                throw new java.io.IOException("closed");
            }
        };
        final InteractiveHost.FrameSink sink = new InteractiveHost.FrameSink(closed);
        final InteractiveHost.LineFramer err = new InteractiveHost.LineFramer(sink, 'E', 'e', false);
        err.write("noise\n".getBytes(StandardCharsets.UTF_8));  // dropped, never thrown
        final java.io.PrintStream protocol = new java.io.PrintStream(
                new InteractiveHost.LineFramer(sink, 'O', 'o', true), true, StandardCharsets.UTF_8);
        protocol.println("{\"type\":\"state\"}");
        assertTrue(protocol.checkError(), "the protocol stream reports the closed connection");
        sink.exit(1); // dropped quietly
    }

    /**
     * Lane shared-engine-1105, the 11-04 stall: a human game's reader is parked on its connection for the whole game, and
     * the game's protocol writes must still go out (on JDK 17 Channels' two streams share a lock, and they did not).
     */
    @Test(timeOut = 30_000)
    public void aParkedReaderNeverBlocksTheGamesWrites() throws Exception {
        final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mtgx-fh-test");
        final java.net.UnixDomainSocketAddress address = java.net.UnixDomainSocketAddress.of(dir.resolve("s"));
        try (java.nio.channels.ServerSocketChannel server =
                     java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(address);
            try (java.nio.channels.SocketChannel browser = java.nio.channels.SocketChannel.open(address);
                 java.nio.channels.SocketChannel connection = server.accept()) {
                final java.io.InputStream in = InteractiveHost.inputOf(connection);
                final java.io.OutputStream out = InteractiveHost.outputOf(connection);
                final CompletableFuture<Integer> parked = new CompletableFuture<>();
                final Thread reader = new Thread(() -> {
                    try {
                        parked.complete(in.read());
                    } catch (java.io.IOException e) {
                        parked.completeExceptionally(e);
                    }
                }, "test-parked-reader");
                reader.setDaemon(true);
                reader.start();
                while (reader.getState() != Thread.State.RUNNABLE || !reader.isAlive()) {
                    Thread.sleep(10);
                }
                Thread.sleep(300); // inside its blocking read
                final CompletableFuture<Void> wrote = new CompletableFuture<>();
                final Thread writer = new Thread(() -> {
                    try {
                        out.write("Ohello\n".getBytes(StandardCharsets.UTF_8));
                        wrote.complete(null);
                    } catch (java.io.IOException e) {
                        wrote.completeExceptionally(e);
                    }
                }, "test-writer");
                writer.setDaemon(true);
                writer.start();
                wrote.get(5, TimeUnit.SECONDS);
                final java.nio.ByteBuffer got = java.nio.ByteBuffer.allocate(16);
                browser.read(got);
                assertEquals(new String(got.array(), 0, got.position(), StandardCharsets.UTF_8), "Ohello\n");
                browser.write(java.nio.ByteBuffer.wrap(new byte[] {'y'}));
                assertEquals((int) parked.get(5, TimeUnit.SECONDS), (int) 'y', "the reader still gets its input");
            }
        }
    }

    /**
     * Lane shared-host-live-1113: a thread whose interrupt flag is set (the AI-timeout path's cancelled eval thread)
     * still writes its game's frames, as a lone JVM's stdout write does, and the connection stays open for the game.
     */
    @Test
    public void anInterruptedThreadsFrameNeverClosesTheConnection() throws Exception {
        final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mtgx-fh-test");
        final java.net.UnixDomainSocketAddress address = java.net.UnixDomainSocketAddress.of(dir.resolve("s"));
        try (java.nio.channels.ServerSocketChannel server =
                     java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(address);
            try (java.nio.channels.SocketChannel browser = java.nio.channels.SocketChannel.open(address);
                 java.nio.channels.SocketChannel connection = server.accept()) {
                final InteractiveHost.FrameSink sink = new InteractiveHost.FrameSink(InteractiveHost.outputOf(connection));
                final byte[] line = "late line".getBytes(StandardCharsets.UTF_8);
                final CompletableFuture<Boolean[]> sent = new CompletableFuture<>();
                final Thread cancelled = new Thread(() -> {
                    Thread.currentThread().interrupt();
                    final boolean first = sink.frame('E', line, line.length);
                    final boolean stillInterrupted = Thread.currentThread().isInterrupted();
                    sent.complete(new Boolean[] {first, stillInterrupted});
                }, "test-cancelled-eval");
                cancelled.setDaemon(true);
                cancelled.start();
                final Boolean[] got = sent.get(5, TimeUnit.SECONDS);
                assertTrue(got[0], "the interrupted thread's frame is sent");
                assertTrue(got[1], "its interrupt flag is put back");
                assertTrue(connection.isOpen(), "the game's connection stays open");
                assertTrue(sink.frame('O', "next".getBytes(StandardCharsets.UTF_8), 4), "the game keeps writing");
                final java.nio.ByteBuffer read = java.nio.ByteBuffer.allocate(64);
                final String want = "Elate line\nOnext\n";
                while (read.position() < want.length()) {
                    browser.read(read);
                }
                assertEquals(new String(read.array(), 0, read.position(), StandardCharsets.UTF_8), want);
            }
        }
    }

    /** Lane shared-engine-1104: a hosted game's end runs its end hooks once and drops its slots; off: no hook kept. */
    @Test
    public void aGameEndRunsItsHooksAndDropsItsState() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        GameContext.onGameEnd(ran::incrementAndGet); // off: nothing to register on
        GameContext.enableHosting();
        final ScopedSlot<String> slot = new ScopedSlot<>();
        final AtomicReference<String> after = new AtomicReference<>("unset");
        final Thread a = new Thread(null, () -> {
            final GameContext ctx = GameContext.open("a");
            slot.set("A");
            GameContext.onGameEnd(ran::incrementAndGet);
            GameContext.onGameEnd(() -> { throw new IllegalStateException("one failing hook"); });
            GameContext.onGameEnd(ran::incrementAndGet);
            ctx.end();
            after.set(slot.get());
            ctx.end(); // a second end runs nothing again
        }, "test-host-end", 0, false);
        a.start();
        a.join(TimeUnit.SECONDS.toMillis(30));
        assertEquals(ran.get(), 2, "both good hooks ran, once, past the failing one");
        assertNull(after.get(), "the ended game's slots are gone");
    }
}
