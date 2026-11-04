package forge.interactive;

import forge.game.GlobalIds;
import forge.game.trigger.Trigger;
import forge.util.GameContext;
import forge.util.IdScope;
import forge.util.MyRandom;
import forge.util.ScopedSlot;
import forge.util.ThreadUtil;
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
    @AfterMethod
    public void offAgain() {
        GameContext.stopHostingForTests();
        MyRandom.setThreadRandom(null);
        IdScope.close();
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
            ThreadUtil.invokeInGameThread(() -> {    // a pooled thread carries game A's state
                seenA.set(slot.get());
                poolRandomA.set(MyRandom.getRandom());
                done.complete(null);
            });
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
            ThreadUtil.invokeInGameThread(() -> {
                seenB.set(slot.get());               // never A's value
                triggerB.set(GlobalIds.capture(IdScope.capture())[3]);
                done.complete(null);
            });
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
    public void framesCarryEachStreamByteForByte() {
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
}
