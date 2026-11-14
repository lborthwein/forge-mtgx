package forge.ai.simulation;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;

/**
 * trigger-owner-1113: the waiting-trigger ownership diagnostic (off unless -Dforge.debug.triggerOwner=true). It names
 * the thread that touches a game's waiting triggers from outside the game's loop thread, and two threads inside the
 * list at once; off, it reports nothing.
 */
public class TriggerOwnerCheckTest extends SimulationTest {

    @AfterMethod
    public void off() {
        TriggerHandler.setOwnerCheckForTest(false, null);
    }

    private static void holdOne(final TriggerHandler th) {
        th.runTrigger(TriggerType.ChangesZone, AbilityKey.newMap(), true); // held: goes on the waiting list
    }

    private static void fromThread(final String name, final Runnable r) throws InterruptedException {
        final Thread t = new Thread(r, name);
        t.start();
        t.join();
    }

    @Test
    public void offReportsNothing() throws InterruptedException {
        final List<String> sink = new CopyOnWriteArrayList<>();
        TriggerHandler.setOwnerCheckForTest(false, sink);
        final Game game = initAndCreateGame();
        final TriggerHandler th = game.getTriggerHandler();
        th.claimLoopThread();
        fromThread("other-game-w1", () -> holdOne(th));
        th.clearWaitingTriggers();
        AssertJUnit.assertTrue(sink.isEmpty());
    }

    @Test
    public void aWriteFromAnotherThreadIsNamedOnce() throws InterruptedException {
        final List<String> sink = new CopyOnWriteArrayList<>();
        TriggerHandler.setOwnerCheckForTest(true, sink);
        final Game game = initAndCreateGame();
        final TriggerHandler th = game.getTriggerHandler();
        th.claimLoopThread();
        holdOne(th);                        // the loop thread's own write: not reported
        th.collectTriggerForWaiting();
        AssertJUnit.assertTrue(sink.toString(), sink.isEmpty());
        final Runnable write = () -> holdOne(th);
        fromThread("other-game-w1", write);
        fromThread("other-game-w1", write); // the same stack again: reported once
        AssertJUnit.assertEquals(sink.toString(), 1, sink.size());
        final String r = sink.get(0);
        AssertJUnit.assertTrue(r, r.startsWith("[trigger-owner] NON_OWNER add ChangesZone game " + game.getId()));
        AssertJUnit.assertTrue(r, r.contains("thread 'other-game-w1' loop thread '" + Thread.currentThread().getName() + "'"));
        AssertJUnit.assertTrue(r, r.contains("TriggerOwnerCheckTest.holdOne"));
        th.clearWaitingTriggers();
        AssertJUnit.assertEquals(1, sink.size());
    }

    @Test
    public void twoThreadsInTheListAtOnceAreReported() throws ReflectiveOperationException {
        final List<String> sink = new CopyOnWriteArrayList<>();
        TriggerHandler.setOwnerCheckForTest(true, sink);
        final Game game = initAndCreateGame();
        final TriggerHandler th = game.getTriggerHandler();
        th.claimLoopThread();
        // stand-in for a thread that is inside the list right now
        final Field f = TriggerHandler.class.getDeclaredField("inList");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        final AtomicReference<Thread> inList = (AtomicReference<Thread>) f.get(th);
        inList.set(new Thread(() -> { }, "search-worker-3"));
        holdOne(th);
        AssertJUnit.assertEquals(sink.toString(), 1, sink.size());
        AssertJUnit.assertTrue(sink.get(0), sink.get(0).startsWith("[trigger-owner] CONCURRENT add ChangesZone"));
        AssertJUnit.assertTrue(sink.get(0), sink.get(0).contains("other 'search-worker-3'"));
        AssertJUnit.assertNull("the outermost call frees the list", inList.get());
        th.clearWaitingTriggers();
    }
}
