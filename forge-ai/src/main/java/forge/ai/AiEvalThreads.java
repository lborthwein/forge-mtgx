package forge.ai;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * The "Game AI Eval" threads of {@link AiController}'s spell choice, reused instead of one new thread per call (lane
 * thread-pool-1030: a new thread per AI priority decision made ~half of a labelling box's CPU kernel time).
 *
 * <p>A reused thread must behave exactly like the new one it replaces, so every hand-off rebuilds a new thread's state
 * on the worker before the task runs: no thread locals of its own, the inheritable thread locals the calling thread
 * would pass to a child created now ({@code ThreadLocal.createInheritedMap}, run on the calling thread as the
 * {@code Thread} constructor does: MyRandom's override, IdScope's counters and AiCache's scope keep their stream and
 * order), the caller's priority and context class loader, no pending interrupt. After the task, the worker drops both
 * maps again, so nothing it saw can reach a later task. The name ("Game", so ThreadUtil.isGameThread holds) and daemon
 * flag are the old thread's.
 *
 * <p>That needs reflective access to {@code Thread}'s two map fields ({@code --add-opens java.base/java.lang}, which
 * every Forge launcher and the test argLine pass). Without it, a failed self-test, or with
 * {@code -Dforge.ai.evalThreads=fresh}, each call gets a new thread as before.
 *
 * <p>Idle workers park forever (daemon); the pool grows only to the peak number of concurrent evaluations (nested ones
 * included: a busy pool never blocks, it adds a worker). A worker whose task timed out or failed is never reused: a
 * late {@code Thread.stop} or interrupt aimed at that task can only reach the retiring thread.
 */
final class AiEvalThreads {
    static final String NAME = "Game AI Eval";

    private static final Field THREAD_LOCALS;
    private static final Field INHERITABLE;
    private static final Method CREATE_INHERITED;
    /** The reflective hooks resolved (and the kill switch is off). */
    private static final boolean REFLECT_OK;

    private static final ArrayDeque<Worker> IDLE = new ArrayDeque<>();

    static {
        Field tl = null, itl = null;
        Method cim = null;
        boolean ok = false;
        if (!"fresh".equals(System.getProperty("forge.ai.evalThreads"))) {
            try {
                tl = Thread.class.getDeclaredField("threadLocals");
                itl = Thread.class.getDeclaredField("inheritableThreadLocals");
                final Class<?> map = Class.forName("java.lang.ThreadLocal$ThreadLocalMap");
                cim = ThreadLocal.class.getDeclaredMethod("createInheritedMap", map);
                tl.setAccessible(true);
                itl.setAccessible(true);
                cim.setAccessible(true);
                ok = true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                ok = false;
            }
        }
        THREAD_LOCALS = tl;
        INHERITABLE = itl;
        CREATE_INHERITED = cim;
        REFLECT_OK = ok;
    }

    /**
     * The mode, decided on first use. Its own class: the self-test's worker thread uses AiEvalThreads' statics, so
     * the test cannot run inside AiEvalThreads' initializer (the worker would wait for it to finish).
     */
    static final class Mode {
        /** True when hand-offs go to reused workers; false: a new thread per call (the old behaviour). */
        static final boolean POOLED = REFLECT_OK && selfTest();

        private Mode() {
        }
    }

    private AiEvalThreads() {
    }

    /** A started evaluation: the thread running it and its end. */
    interface Handle {
        Thread thread();

        /** Wait up to {@code ms} for the task to end. */
        void join(long ms) throws InterruptedException;

        boolean isAlive();

        /** The task will not be waited for normally (timeout or failure): its worker must not be reused. */
        void abandon();

        /** Last resort after a timeout: {@code Thread.stop} the task's thread while it still runs the task. */
        void stop();
    }

    /** Run {@code task} on a "Game AI Eval" thread equivalent to a new one created by the calling thread now. */
    static Handle start(final Runnable task) {
        if (!Mode.POOLED) {
            return fresh(task);
        }
        final Job job = job(task);
        return job == null ? fresh(task) : dispatch(job);
    }

    /** The hand-off, created on the calling thread (its inheritable map copied now, as the Thread constructor does). */
    private static Job job(final Runnable task) {
        final Thread caller = Thread.currentThread();
        try {
            final Object parentMap = INHERITABLE.get(caller);
            return new Job(task, parentMap == null ? null : CREATE_INHERITED.invoke(null, parentMap),
                    caller.getPriority(), caller.getContextClassLoader());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** An idle worker takes {@code job}; none idle: a new worker. */
    private static Job dispatch(final Job job) {
        Worker w;
        synchronized (IDLE) {
            w = IDLE.pollFirst();
        }
        job.worker = w == null ? new Worker() : w;
        if (w == null) {
            job.worker.first = job;
            job.worker.start();
        } else {
            w.give(job);
        }
        return job;
    }

    /** The old path: one new daemon thread. */
    private static Handle fresh(final Runnable task) {
        final Thread t = new Thread(task, NAME);
        t.setDaemon(true);
        t.start();
        return new Handle() {
            @Override
            public Thread thread() {
                return t;
            }

            @Override
            public void join(final long ms) throws InterruptedException {
                t.join(ms);
            }

            @Override
            public boolean isAlive() {
                return t.isAlive();
            }

            @Override
            public void abandon() {
            }

            @SuppressWarnings({"deprecation", "removal"})
            @Override
            public void stop() {
                t.stop();
            }
        };
    }

    private static final class Job implements Handle {
        final Runnable task;
        final Object inherited;
        final int priority;
        final ClassLoader ccl;
        final CountDownLatch done = new CountDownLatch(1);
        volatile Worker worker;
        private boolean finished;
        private boolean abandoned;

        Job(final Runnable task, final Object inherited, final int priority, final ClassLoader ccl) {
            this.task = task;
            this.inherited = inherited;
            this.priority = priority;
            this.ccl = ccl;
        }

        @Override
        public Thread thread() {
            return worker;
        }

        @Override
        public void join(final long ms) throws InterruptedException {
            done.await(ms, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isAlive() {
            return done.getCount() > 0;
        }

        @Override
        public synchronized void abandon() {
            abandoned = true;
        }

        @SuppressWarnings({"deprecation", "removal"})
        @Override
        public synchronized void stop() {
            abandoned = true;
            if (!finished) {
                worker.stop();
            }
        }

        /** Worker side, after the task: true when the worker may serve another task. */
        synchronized boolean finish() {
            finished = true;
            return !abandoned;
        }
    }

    private static final class Worker extends Thread {
        private final SynchronousQueue<Job> inbox = new SynchronousQueue<>();
        Job first;

        Worker() {
            super(NAME);
            setDaemon(true);
        }

        void give(final Job job) {
            boolean interrupted = false;
            while (true) {
                try {
                    inbox.put(job);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            Job job = first;
            first = null;
            while (true) {
                if (job == null) {
                    try {
                        job = inbox.take();
                    } catch (InterruptedException e) {
                        continue; // a stray interrupt on an idle worker: the next task starts with none pending
                    }
                }
                if (!runOne(job)) {
                    return; // retired
                }
                job = null;
                synchronized (IDLE) {
                    IDLE.addFirst(this);
                }
            }
        }

        private boolean runOne(final Job job) {
            try {
                reset(job.inherited);
                setPriority(job.priority);
                setContextClassLoader(job.ccl);
                job.task.run();
            } finally {
                reset(null);
                Thread.interrupted();
                final boolean reuse = job.finish();
                job.done.countDown();
                if (!reuse) {
                    return false;
                }
            }
            return true;
        }

        /** This thread's thread-local maps as a new thread's: none of its own, {@code inherited} inheritable. */
        private void reset(final Object inherited) {
            try {
                THREAD_LOCALS.set(this, null);
                INHERITABLE.set(this, inherited);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e); // cannot happen once the self-test passed
            }
            Thread.interrupted();
        }
    }

    /** The pooled path keeps a new thread's thread-local semantics (two tasks on one worker), or it is not used. */
    private static boolean selfTest() {
        final ThreadLocal<Object> plain = new ThreadLocal<>();
        final InheritableThreadLocal<Object> inh = new InheritableThreadLocal<>();
        final Object mark = new Object();
        final Object[] seen = new Object[4];
        final Thread[] ran = new Thread[2];
        try {
            plain.set(mark);
            inh.set(mark);
            final Job ja = job(() -> {
                seen[0] = plain.get();
                seen[1] = inh.get();
                ran[0] = Thread.currentThread();
                plain.set(new Object());
                inh.set(new Object());
            });
            final Job jb = job(() -> {
                seen[2] = plain.get();
                seen[3] = inh.get();
                ran[1] = Thread.currentThread();
            });
            if (ja == null || jb == null) {
                return false;
            }
            dispatch(ja);
            if (!ja.done.await(10, TimeUnit.SECONDS) || !awaitIdle(ja.worker)) {
                return false;
            }
            dispatch(jb); // the same (only) idle worker
            if (!jb.done.await(10, TimeUnit.SECONDS) || !awaitIdle(jb.worker)) {
                return false;
            }
            return ran[0] == ja.worker && ran[1] == ja.worker && seen[0] == null && seen[1] == mark && seen[2] == null
                    && seen[3] == mark;
        } catch (InterruptedException | RuntimeException e) {
            return false;
        } finally {
            plain.remove();
            inh.remove();
        }
    }

    /** True once {@code w} is back in the idle list (it returns itself just after counting its task down). */
    private static boolean awaitIdle(final Worker w) throws InterruptedException {
        for (int i = 0; i < 10_000; i++) {
            synchronized (IDLE) {
                if (IDLE.contains(w)) {
                    return true;
                }
            }
            Thread.sleep(1);
        }
        return false;
    }
}
