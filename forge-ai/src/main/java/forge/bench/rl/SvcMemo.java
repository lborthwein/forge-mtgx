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
 *   <li>Only an OK answer is stored, and what is stored is the memo's own copy: neither the first asker nor any later
 *       one holds the stored object, so a caller that changes its answer (a seat whose schedule, plan or search
 *       overrides the policy) changes nothing for anyone else.</li>
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
    /**
     * The shadow-verify debug flag ({@code -Dsdcc.svcMemoVerify=true}; OFF by default, and then nothing is sent, counted
     * or logged beyond the memo itself). With it on, every request the memo answers, whether a hit on a finished entry
     * or a JOIN on another thread's send still in flight, is ALSO sent, and the fresh answer is compared with the stored
     * one. The play-out still gets the stored answer, and the extra send ticks the work clock once in place of the
     * hit's own tick, so the game and the work accounting are the memo's. A fresh answer whose steps or status differ
     * is one {@code [svcmemo-verify] MISMATCH} line on stderr (hit or join, the asker's and the stored answer's uid and
     * decision index, the thread, the request kind, a sha256 of the keyed payload bytes, seat, family and mode, and both
     * answers with logp, value and policy version) and counts in {@code memoMismatches} on the decision row, beside
     * {@code memoVerified} and {@code memoVerifiedJoins}. Cost: the flag undoes the memo's saving (every request is sent
     * again), so it is for a diagnostic run, not for play. A mismatch means either that the memo served something other
     * than the policy's answer to those bytes (the fault of 11-14: the stored object was shared with the first asker,
     * whose seat changed it) or that the service gave two different greedy answers to byte-identical requests.
     */
    static final boolean VERIFY_DEFAULT = Boolean.getBoolean("sdcc.svcMemoVerify");
    private final boolean verify;
    /** Where a mismatch line goes (stderr; a test reads it). */
    java.util.function.Consumer<String> mismatchLog = System.err::println;
    public final AtomicInteger verified = new AtomicInteger(), verifiedJoins = new AtomicInteger(), mismatches = new AtomicInteger();

    public SvcMemo() {
        this(VERIFY_DEFAULT);
    }

    /** {@code verify}: the shadow-verify debug flag for this memo (production: {@link #VERIFY_DEFAULT}). */
    SvcMemo(final boolean verify) {
        this.verify = verify;
    }

    public boolean verifying() {
        return verify;
    }

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
            final boolean joined = !first.isDone(); // the first asker's send was still in flight (verify line only)
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
                if (verify) {
                    verify(key, d, payload, sender, joined); // sends (and so ticks the work clock once, as the hit would)
                } else {
                    forge.game.WorkClock.tick(forge.game.WorkClock.Site.SVC); // as the send it replaces (no clock = no-op)
                }
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
        // The stored answer is the memo's OWN copy: the owner's caller may change the object it gets back (RlSeat replaces
        // d.steps when a schedule, a plan or a search overrides the policy's answer), and no later asker may see that.
        mine.complete(copy(d));
        return d;
    }

    /** The shadow-verify (see {@link #VERIFY_DEFAULT}): the fresh answer to a request the memo just served, compared with the stored one. */
    private void verify(final Key key, final RlWire.Decision stored, final byte[] payload, final Sender sender,
            final boolean joined) throws IOException {
        final RlWire.Decision fresh = sender.send(payload);
        verified.incrementAndGet();
        if (joined) {
            verifiedJoins.incrementAndGet();
        }
        if (fresh.status == stored.status && Arrays.equals(fresh.steps, stored.steps)) {
            return;
        }
        mismatches.incrementAndGet();
        String sha;
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(payload, UID_BYTES, payload.length - UID_BYTES);
            final StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            sha = sb.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            sha = "?";
        }
        // family and mode sit right after uid, index and seat in the DECIDE header (RlWire.encodeDecide)
        final java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // the asker: a play-out's uid is its world's seed (the same for every candidate's play-out in that world)
        mismatchLog.accept("[svcmemo-verify] MISMATCH " + (joined ? "join" : "hit") + " askerUid=" + Long.toUnsignedString(hb.getLong())
                + " askerDecIdx=" + hb.getInt() + " thread=" + Thread.currentThread().getName() + " storedUid="
                + Long.toUnsignedString(stored.gameUid) + " storedDecIdx=" + stored.decIdx
                + " kind=0x" + Integer.toHexString(key.kind) + " payloadSha=" + sha
                + " bytes=" + payload.length + " seat=" + (payload[12] & 0xff) + " family=" + (payload[13] & 0xff)
                + " mode=" + (payload[14] & 0xff) + " stored{steps=" + Arrays.toString(stored.steps) + ",logp=" + stored.logp
                + ",value=" + stored.valueObs + ",version=" + stored.policyVersion + ",status=" + stored.status + "} fresh{steps="
                + Arrays.toString(fresh.steps) + ",logp=" + fresh.logp + ",value=" + fresh.valueObs + ",version="
                + fresh.policyVersion + ",status=" + fresh.status + "}");
    }

    /** A stored answer for another asker: the same steps, logp, value and version under the asker's uid and index. */
    static RlWire.Decision readdress(final RlWire.Decision d, final byte[] payload) {
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        final RlWire.Decision o = copy(d);
        o.gameUid = b.getLong();
        o.decIdx = b.getInt();
        return o;
    }

    /** A Decision that shares nothing with {@code d} (its own steps array). */
    static RlWire.Decision copy(final RlWire.Decision d) {
        final RlWire.Decision o = new RlWire.Decision();
        o.gameUid = d.gameUid;
        o.decIdx = d.decIdx;
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
