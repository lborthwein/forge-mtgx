package forge.bench.rl;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * svcmemo-1114 (search key {@code svcMemo}, default off): the policy's answers to the play-out seats' greedy DECIDE
 * requests of ONE searched decision, so that a request already asked in this search is answered without a second send.
 *
 * <p>Why it is exact: the service's greedy DECISION is a function of the request's kind and of its payload after the
 * first {@link #UID_BYTES} bytes (the game uid, u64, and the decision index, u32, which the service uses only to seed a
 * SAMPLED decision; search_server.py, policies.compute: {@code seeds=None if greedy}). A hit returns the stored steps,
 * logp, value and policy version with the asker's own uid and decision index put back.
 *
 * <p>Rules (each has a test in SvcMemoTest):
 * <ul>
 *   <li>The key is the request kind (the frame type and its flags) plus EVERY payload byte after the uid and the
 *       decision index, compared byte for byte on a hit (the hash only finds the bucket). Requests of different kinds
 *       never share an entry.</li>
 *   <li>Only greedy asks come here; a sampled ask is never looked up or stored (the caller's bypass, and
 *       {@link #decide} refuses {@code sample}).</li>
 *   <li>One memo per searched decision: created at its start, dropped at its end, never shared between decisions,
 *       seats or games.</li>
 *   <li>The entries are of one policy version: a fresh answer of another version empties the memo before it is stored
 *       (a service may reload within a search), and no later ask is served an entry of the old version.</li>
 *   <li>Two threads that ask the same key at once send once: the first owns the send on its own connection, the
 *       others wait for its answer. The owner is already running (it is not queued behind anything), so the wait
 *       cannot deadlock with the search's pool; if the owner fails, the waiters send for themselves.</li>
 *   <li>Only an OK answer is stored.</li>
 * </ul>
 */
public final class SvcMemo {
    /** The payload bytes outside the key: game uid (u64) + decision index (u32), the head of every DECIDE payload. */
    public static final int UID_BYTES = 12;

    /** One send: the caller's own connection ({@code RlSearchClient.decide}). */
    public interface Sender {
        RlWire.Decision send(byte[] payload) throws IOException;
    }

    /** Kind + the payload after uid and decision index; equality is over every byte. */
    static final class Key {
        final int kind;
        final byte[] payload;
        final int hash;

        Key(final int kind, final byte[] payload) {
            this.kind = kind;
            this.payload = payload;
            int h = kind;
            for (int i = UID_BYTES; i < payload.length; i++) {
                h = 31 * h + payload[i];
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            final Key k = (Key) o;
            return kind == k.kind && hash == k.hash && payload.length == k.payload.length
                    && Arrays.equals(payload, UID_BYTES, payload.length, k.payload, UID_BYTES, k.payload.length);
        }
    }

    private final ConcurrentHashMap<Key, CompletableFuture<RlWire.Decision>> map = new ConcurrentHashMap<>();
    private final Object versionLock = new Object();
    /** The policy version of the stored entries (-1: none stored yet). */
    private long version = -1;
    public final AtomicInteger hits = new AtomicInteger(), misses = new AtomicInteger(), versionDrops = new AtomicInteger();

    /** The kind of a greedy DECIDE request: the frame type and its flags (0 = greedy). */
    public static int kindOf(final int frameType, final int flags) {
        return (frameType << 8) | (flags & 0xff);
    }

    /**
     * The greedy DECISION for {@code payload}: from this search's memo if the same request was asked before, else from
     * {@code sender} (and stored). {@code sample} must be false: a sampled ask is not a function of the payload.
     */
    public RlWire.Decision decide(final byte[] payload, final boolean sample, final Sender sender) throws IOException {
        if (sample || payload.length < UID_BYTES) {
            throw new IllegalArgumentException("SvcMemo: only greedy DECIDE payloads are memoised");
        }
        final Key key = new Key(kindOf(RlWire.T_DECIDE, 0), payload);
        final CompletableFuture<RlWire.Decision> mine = new CompletableFuture<>();
        final CompletableFuture<RlWire.Decision> first = map.putIfAbsent(key, mine);
        if (first != null) {
            RlWire.Decision d = null;
            try {
                d = first.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // as without the memo: the send below meets the interrupt itself
            } catch (ExecutionException e) {
                // the owner's send failed: this thread asks for itself
            }
            if (d != null) {
                hits.incrementAndGet();
                forge.game.WorkClock.tick(forge.game.WorkClock.Site.SVC); // as the send it replaces (no clock = no-op)
                return readdress(d, payload);
            }
            misses.incrementAndGet();
            return sender.send(payload);
        }
        misses.incrementAndGet();
        final RlWire.Decision d;
        try {
            d = sender.send(payload);
        } catch (IOException | RuntimeException | Error e) {
            map.remove(key, mine);
            mine.completeExceptionally(e);
            throw e;
        }
        if (d.status != RlWire.ST_OK) {
            map.remove(key, mine);
            mine.completeExceptionally(new IOException("not an OK answer"));
            return d;
        }
        synchronized (versionLock) {
            if (version != d.policyVersion) {
                if (version != -1) {
                    // the service answers with another policy version: nothing stored under the old one is served again
                    versionDrops.incrementAndGet();
                    map.clear();
                    map.put(key, mine);
                }
                version = d.policyVersion;
            }
        }
        mine.complete(d);
        return d;
    }

    /** A stored answer for another asker: the same steps, logp, value and version under the asker's uid and index. */
    static RlWire.Decision readdress(final RlWire.Decision d, final byte[] payload) {
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        final RlWire.Decision o = new RlWire.Decision();
        o.gameUid = b.getLong();
        o.decIdx = b.getInt();
        o.status = d.status;
        o.policyVersion = d.policyVersion;
        o.logp = d.logp;
        o.valueObs = d.valueObs;
        o.steps = d.steps.clone();
        return o;
    }

    int size() {
        return map.size();
    }
}
