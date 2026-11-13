package forge.ai.simulation;

/**
 * Lane sdcc-svcbatch-1113 (search key {@code svcBatchMs}; 0 = off, and then no gate exists and nothing changes): the
 * play-outs of one wave ask the policy service together. A play-out thread about to send a request waits here until every
 * other running play-out of the wave is waiting too (or has ended), or until the first waiter has waited the cap; then all
 * are released at once, each sends its own request on its own connection as before, and the service's batcher takes them
 * as one batch instead of several small ones. Timing only: every request is the same bytes and gets its own answer.
 */
public final class SvcGate {
    private static final ThreadLocal<SvcGate> CUR = new ThreadLocal<>();
    /** Measurement: releases with every running play-out waiting, releases by the cap, and requests released. */
    public static final java.util.concurrent.atomic.AtomicLong FULL = new java.util.concurrent.atomic.AtomicLong(),
            CAPPED = new java.util.concurrent.atomic.AtomicLong(), HELD = new java.util.concurrent.atomic.AtomicLong(),
            WAIT_NANOS = new java.util.concurrent.atomic.AtomicLong();

    private final long capNanos;
    private int active, waiting;
    private long gen, firstAt;

    SvcGate(final int capMs) {
        this.capNanos = capMs * 1_000_000L;
    }

    /** A play-out task of the wave starts on this thread. */
    void enter() {
        synchronized (this) {
            active++;
        }
        CUR.set(this);
    }

    /** It has ended (whatever way). */
    void leave() {
        CUR.remove();
        synchronized (this) {
            active--;
            if (waiting > 0 && waiting >= active) {
                release(true);
            }
        }
    }

    /** Called before a policy-service request: no gate on this thread = return at once. */
    public static void hold() {
        final SvcGate g = CUR.get();
        if (g != null) {
            g.await();
        }
    }

    private void await() {
        final long t0 = System.nanoTime();
        synchronized (this) {
            if (waiting++ == 0) {
                firstAt = t0;
            }
            final long mine = gen;
            if (waiting >= active) {
                release(true);
            }
            boolean interrupted = false;
            while (gen == mine) {
                final long left = firstAt + capNanos - System.nanoTime();
                if (left <= 0) {
                    release(false);
                    break;
                }
                try {
                    wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } catch (InterruptedException e) {
                    interrupted = true;
                    release(false);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        WAIT_NANOS.addAndGet(System.nanoTime() - t0);
    }

    private void release(final boolean full) {
        (full ? FULL : CAPPED).incrementAndGet();
        HELD.addAndGet(waiting);
        gen++;
        waiting = 0;
        notifyAll();
    }
}
