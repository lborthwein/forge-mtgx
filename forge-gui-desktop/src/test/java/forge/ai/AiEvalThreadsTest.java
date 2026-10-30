package forge.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.util.IdScope;
import forge.util.MyRandom;
import forge.util.ThreadUtil;

/**
 * Lane thread-pool-1030: a reused "Game AI Eval" thread must look like the new thread it replaces: thread locals
 * fresh, the caller's inheritable ones (MyRandom's override, IdScope's counters) as of the hand-off, name and daemon.
 */
public class AiEvalThreadsTest {

    private static <T> T run(final java.util.concurrent.Callable<T> c) throws Exception {
        final FutureTask<T> f = new FutureTask<>(c);
        final AiEvalThreads.Handle h = AiEvalThreads.start(f);
        final T v = f.get(30, TimeUnit.SECONDS);
        h.join(5000);
        return v;
    }

    @Test
    public void pooledUnderTheTestArgLine() {
        // the surefire / testng launchers pass --add-opens java.base/java.lang: the pooled path must be the one tested
        Assert.assertTrue(AiEvalThreads.Mode.POOLED, "pooled mode (reflective hooks + self-test)");
    }

    @Test
    public void reusedThreadHasANewThreadsState() throws Exception {
        final ThreadLocal<String> plain = new ThreadLocal<>();
        final ThreadLocal<int[]> withInit = ThreadLocal.withInitial(() -> new int[] {7});
        final InheritableThreadLocal<String> inh = new InheritableThreadLocal<>();
        inh.set("caller-1");
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int k = i;
            if (i == 3) {
                inh.set("caller-2"); // a later hand-off sees the caller's value at that hand-off
            }
            final String[] seen = run(() -> {
                threads.add(Thread.currentThread());
                final String[] s = {plain.get(), String.valueOf(withInit.get()[0]), inh.get(),
                        Thread.currentThread().getName(), String.valueOf(Thread.currentThread().isDaemon()),
                        String.valueOf(ThreadUtil.isGameThread()), String.valueOf(Thread.currentThread().isInterrupted())};
                plain.set("leak-" + k);
                withInit.get()[0] = 99;
                inh.set("child-" + k); // must not reach the caller or a later task
                return s;
            });
            Assert.assertNull(seen[0], "plain thread local fresh, call " + i);
            Assert.assertEquals(seen[1], "7", "withInitial fresh, call " + i);
            Assert.assertEquals(seen[2], i < 3 ? "caller-1" : "caller-2", "inherited value, call " + i);
            Assert.assertEquals(seen[3], "Game AI Eval");
            Assert.assertEquals(seen[4], "true");
            Assert.assertEquals(seen[5], "true");
            Assert.assertEquals(seen[6], "false");
        }
        Assert.assertEquals(inh.get(), "caller-2", "a child's set does not reach the caller");
        Assert.assertEquals(threads.stream().distinct().count(), 1L, "sequential calls reuse one worker: " + threads);
        inh.remove();
    }

    @Test
    public void rngStreamAndIdCountersAreTheCallers() throws Exception {
        // the same draws, in the same order, from the caller's per-thread Random and IdScope counters
        final Random a = new Random(1030);
        final Random b = new Random(1030);
        final Random prev = MyRandom.getThreadRandom();
        try {
            MyRandom.setThreadRandom(a);
            final int x1 = run(() -> MyRandom.getRandom().nextInt());
            final int x2 = MyRandom.getRandom().nextInt();
            final int x3 = run(() -> MyRandom.getRandom().nextInt());
            Assert.assertEquals(new int[] {x1, x2, x3}, new int[] {b.nextInt(), b.nextInt(), b.nextInt()});
        } finally {
            MyRandom.setThreadRandom(prev);
        }
        final Object prevScope = IdScope.capture();
        try {
            IdScope.open();
            final int i1 = run(() -> IdScope.next(IdScope.Kind.SPELL_ABILITY));
            final int i2 = IdScope.next(IdScope.Kind.SPELL_ABILITY);
            final int i3 = run(() -> IdScope.next(IdScope.Kind.SPELL_ABILITY));
            Assert.assertEquals(new int[] {i2 - i1, i3 - i2}, new int[] {1, 1}, "one shared counter, in call order");
            IdScope.close();
            Assert.assertEquals(run(() -> IdScope.next(IdScope.Kind.SPELL_ABILITY)), Integer.valueOf(IdScope.NONE),
                    "a closed caller scope is not left on the reused worker");
        } finally {
            IdScope.install(prevScope);
        }
    }

    @Test
    public void nestedEvaluationsNeverBlock() throws Exception {
        final AtomicInteger depth = new AtomicInteger();
        final java.util.concurrent.Callable<Integer>[] rec = new java.util.concurrent.Callable[1];
        rec[0] = () -> depth.incrementAndGet() < 6 ? 1 + run(rec[0]) : 1;
        Assert.assertEquals((int) run(rec[0]), 6);
    }

    @Test
    public void anAbandonedWorkerIsNotReused() throws Exception {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final Thread[] first = new Thread[1];
        final FutureTask<Void> f = new FutureTask<>(() -> {
            first[0] = Thread.currentThread();
            release.await();
            return null;
        });
        final AiEvalThreads.Handle h = AiEvalThreads.start(f);
        try {
            f.get(200, TimeUnit.MILLISECONDS);
            Assert.fail("expected a timeout");
        } catch (java.util.concurrent.TimeoutException e) {
            h.abandon();
        }
        release.countDown();
        h.join(5000);
        Assert.assertFalse(h.isAlive());
        first[0].join(5000);
        Assert.assertFalse(first[0].isAlive(), "the abandoned worker retired");
        final Thread next = run(Thread::currentThread);
        Assert.assertNotSame(next, first[0]);
    }
}
