package forge.bench.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * svcmemo-1114 (search key {@code svcMemo}, default off): the per-search memo of the play-out seats' greedy policy
 * requests ({@link SvcMemo}, {@code RlSearch.playoutDecide}). Pure unit tests (no Forge game): the key is off by
 * default and then leaves specs, rows and summaries alone; a repeated request is sent once and answered under the
 * asker's own uid and decision index; another payload, another kind, a sampled ask, an answer that is not OK, a failed
 * send and a new policy version are never served from the memo; N threads asking one key send once.
 */
public class SvcMemoTest {
    static final String SHA = "3d9579d752d621b98ce70ea88237f9d76f60ff4d6a9dfbf6d062b619587f7da1";
    static Path indexFile;

    @BeforeClass
    public static void setUp() throws Exception {
        indexFile = Files.createTempFile("svcmemo-index-", ".tsv");
        Files.write(indexFile, "<pad>\t0\n<unk>\t1\nForest\t2\nIsland\t3\n".getBytes(StandardCharsets.UTF_8));
    }

    static RlSearch.Config cfg(final String server, final String extra) {
        return RlSearch.Config.parse(JsonParser.parseString("{\"server\":\"" + server + "\",\"leaf\":\"value\","
                + "\"playout\":\"policy\"" + extra + "}").getAsJsonObject());
    }

    /** A valid obs-v1 DECIDE payload (one PASS candidate) for a uid and a decision index; {@code ctx0} varies the observation. */
    static byte[] payload(final long uid, final int decIdx, final float ctx0) {
        final RlFeaturizer.Obs o = RlSearchObsTest.obs(1);
        o.ctx[0] = ctx0;
        final RlWire.Decide f = RlSearchObsTest.s1Leaf(o, uid, 0, 3);
        f.decIdx = decIdx;
        return RlWire.encodeDecide(f);
    }

    static RlWire.Decision answer(final byte[] p, final long version, final int status) {
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        final RlWire.Decision d = new RlWire.Decision();
        d.gameUid = b.getLong();
        d.decIdx = b.getInt();
        d.status = status;
        d.policyVersion = version;
        d.logp = -0.25f;
        d.valueObs = 0.5f;
        d.steps = new short[] {0};
        return d;
    }

    // ------------------------------------------------------------------------------------------------ key off

    @Test
    public void theKeyIsOffByDefaultAndThenSpecsRowsAndSummariesAreUntouched() throws Exception {
        final RlSearch.Config off = cfg("127.0.0.1:1", "");
        Assert.assertFalse(off.svcMemo, "absent = off");
        Assert.assertFalse(off.toJson().has("svcMemo"), "a spec without the key has no svcMemo in its JSON");
        Assert.assertTrue(cfg("127.0.0.1:1", ",\"svcMemo\":true").toJson().get("svcMemo").getAsBoolean());
        Assert.assertFalse(cfg("127.0.0.1:1", ",\"svcMemo\":false").toJson().has("svcMemo"), "false is written as absent");
        final JsonObject row = new JsonObject();
        row.addProperty("x", 1);
        final String before = row.toString();
        RlSearch.memoRow(row, null);
        Assert.assertEquals(row.toString(), before, "key off: the decision row is byte-equal");
        final SvcMemo m = new SvcMemo();
        RlSearch.memoRow(row, m);
        Assert.assertEquals(row.get("memoHits").getAsInt(), 0);
        Assert.assertEquals(row.get("memoMisses").getAsInt(), 0);
        final RlSearch s = new RlSearch(off, 1L, 2L, null, null, "jar", "t");
        Assert.assertFalse(s.summary().has("memoHits"), "key off: the search summary is unchanged");
        s.close();
        final RlSearch on = new RlSearch(cfg("127.0.0.1:1", ",\"svcMemo\":true"), 1L, 2L, null, null, "jar", "t");
        Assert.assertEquals(on.summary().get("memoHits").getAsLong(), 0L);
        Assert.assertEquals(on.summary().get("memoMisses").getAsLong(), 0L);
        on.close();
        // the live string
        final String base = "server=127.0.0.1:1,policySha=" + SHA + ",cardIndex=" + indexFile;
        Assert.assertFalse(RlLiveSeat.Spec.parse(base).search.svcMemo);
        Assert.assertFalse(RlLiveSeat.Spec.parse(base).search.toJson().has("svcMemo"));
        Assert.assertTrue(RlLiveSeat.Spec.parse(base + ",svcMemo=1").search.svcMemo);
        Assert.assertFalse(RlLiveSeat.Spec.parse(base + ",svcMemo=0").search.svcMemo);
    }

    // ------------------------------------------------------------------------------------------------ the seat's path

    @Test
    public void aRepeatedGreedyRequestIsSentOnceAndAnsweredUnderTheAskersOwnUid() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA, 1)) {
            final RlSearch s = new RlSearch(cfg(svc.address(), ",\"svcMemo\":true"), 1L, 2L, CardIndex.load(indexFile), null, "jar", "t");
            final SvcMemo m = new SvcMemo();
            final RlWire.Decision a = s.playoutDecide(m, payload(1001L, 4, 0.1f));
            Assert.assertEquals(svc.decides.get(), 1L);
            final RlWire.Decision b = s.playoutDecide(m, payload(2002L, 9, 0.1f)); // the same request but for uid and index
            Assert.assertEquals(svc.decides.get(), 1L, "the repeat is not sent");
            Assert.assertEquals(b.gameUid, 2002L, "the asker's own uid");
            Assert.assertEquals(b.decIdx, 9, "the asker's own decision index");
            Assert.assertEquals(a.gameUid, 1001L);
            Assert.assertTrue(java.util.Arrays.equals(b.steps, a.steps), "the stored steps");
            Assert.assertTrue(Float.compare(b.logp, a.logp) == 0, "the stored logp");
            Assert.assertTrue(Float.compare(b.valueObs, a.valueObs) == 0, "the stored value");
            Assert.assertEquals(b.policyVersion, a.policyVersion);
            Assert.assertEquals(b.status, RlWire.ST_OK);
            Assert.assertNotSame(b.steps, a.steps, "a hit's steps are its own array");
            s.playoutDecide(m, payload(3003L, 1, 0.2f)); // another observation
            Assert.assertEquals(svc.decides.get(), 2L, "a request that differs in one observation value is sent");
            Assert.assertEquals(m.hits.get(), 1);
            Assert.assertEquals(m.misses.get(), 2);
            // without a memo (key off, or no searched decision in progress): the send as before, every time
            s.playoutDecide(null, payload(1001L, 4, 0.1f));
            s.playoutDecide(null, payload(1001L, 4, 0.1f));
            Assert.assertEquals(svc.decides.get(), 4L);
            // another searched decision has its own memo: nothing carries over
            final SvcMemo next = new SvcMemo();
            s.playoutDecide(next, payload(1001L, 4, 0.1f));
            Assert.assertEquals(svc.decides.get(), 5L, "a new decision's memo starts empty");
            s.close();
        }
    }

    @Test
    public void aSampledAskBypassesTheMemo() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA, 1)) {
            final RlSearch s = new RlSearch(cfg(svc.address(), ",\"svcMemo\":true,\"playoutSample\":true"), 1L, 2L,
                    CardIndex.load(indexFile), null, "jar", "t");
            final SvcMemo m = new SvcMemo();
            s.playoutDecide(m, payload(1001L, 4, 0.1f));
            s.playoutDecide(m, payload(1001L, 4, 0.1f));
            Assert.assertEquals(svc.decides.get(), 2L, "sampling on: every ask is sent");
            Assert.assertEquals(m.hits.get() + m.misses.get(), 0, "and the memo is neither read nor written");
            Assert.assertEquals(m.size(), 0);
            s.close();
        }
        final SvcMemo m = new SvcMemo();
        Assert.assertThrows(IllegalArgumentException.class, () -> m.decide(payload(1L, 0, 0f), true, q -> answer(q, 7, RlWire.ST_OK)));
    }

    // ------------------------------------------------------------------------------------------------ the memo's rules

    @Test
    public void theKeyIsTheKindAndEveryPayloadByteAfterUidAndIndex() {
        final byte[] p = payload(1L, 2, 0.1f), sameElsewhere = payload(99L, 77, 0.1f), other = payload(1L, 2, 0.3f);
        final int decide = SvcMemo.kindOf(RlWire.T_DECIDE, 0);
        Assert.assertEquals(new SvcMemo.Key(decide, p), new SvcMemo.Key(decide, sameElsewhere), "uid and index are outside the key");
        Assert.assertNotEquals(new SvcMemo.Key(decide, p), new SvcMemo.Key(decide, other));
        Assert.assertNotEquals(new SvcMemo.Key(decide, p), new SvcMemo.Key(SvcMemo.kindOf(RlSearchClient.T_SCORE, 0), p),
                "the same payload as another kind of request is another entry");
        Assert.assertNotEquals(new SvcMemo.Key(decide, p), new SvcMemo.Key(SvcMemo.kindOf(RlSearchClient.T_LEAVES, 0), p));
        Assert.assertNotEquals(new SvcMemo.Key(decide, p), new SvcMemo.Key(SvcMemo.kindOf(RlWire.T_DECIDE, RlSearchClient.FLAG_SAMPLE), p),
                "a sampled DECIDE is another kind");
        // every byte counts: flip each byte after the uid and index in turn
        for (int i = SvcMemo.UID_BYTES; i < p.length; i++) {
            final byte[] q = p.clone();
            q[i] ^= 1;
            Assert.assertNotEquals(new SvcMemo.Key(decide, p), new SvcMemo.Key(decide, q), "byte " + i);
        }
        // and a shorter or longer payload with the same prefix is another request
        Assert.assertNotEquals(new SvcMemo.Key(decide, p), new SvcMemo.Key(decide, java.util.Arrays.copyOf(p, p.length + 1)));
    }

    @Test
    public void aNewPolicyVersionEmptiesTheMemo() throws Exception {
        final SvcMemo m = new SvcMemo();
        final AtomicInteger sends = new AtomicInteger();
        final long[] version = {7};
        final SvcMemo.Sender sender = q -> {
            sends.incrementAndGet();
            return answer(q, version[0], RlWire.ST_OK);
        };
        final byte[] a = payload(1L, 0, 0.1f), b = payload(1L, 1, 0.2f);
        m.decide(a, false, sender);
        m.decide(a, false, sender);
        Assert.assertEquals(sends.get(), 1);
        version[0] = 8; // the service reloaded: the next fresh answer carries another version
        final RlWire.Decision fresh = m.decide(b, false, sender);
        Assert.assertEquals(fresh.policyVersion, 8L);
        Assert.assertEquals(m.versionDrops.get(), 1);
        final RlWire.Decision again = m.decide(a, false, sender);
        Assert.assertEquals(sends.get(), 3, "the request stored under the old version is sent again");
        Assert.assertEquals(again.policyVersion, 8L, "and is not served from an old entry");
        m.decide(a, false, sender);
        m.decide(b, false, sender);
        Assert.assertEquals(sends.get(), 3, "entries of the new version are served");
    }

    @Test
    public void onlyAnOkAnswerIsStoredAndAFailedSendIsNot() throws Exception {
        final SvcMemo m = new SvcMemo();
        final AtomicInteger sends = new AtomicInteger();
        final byte[] p = payload(1L, 0, 0.1f);
        final RlWire.Decision d = m.decide(p, false, q -> {
            sends.incrementAndGet();
            return answer(q, 7, RlWire.ST_DELEGATE);
        });
        Assert.assertEquals(d.status, RlWire.ST_DELEGATE, "the asker gets the service's own answer");
        m.decide(p, false, q -> {
            sends.incrementAndGet();
            return answer(q, 7, RlWire.ST_OK);
        });
        Assert.assertEquals(sends.get(), 2, "an answer that is not OK is not stored");
        final SvcMemo m2 = new SvcMemo();
        Assert.assertThrows(IOException.class, () -> m2.decide(p, false, q -> {
            throw new IOException("service gone");
        }));
        Assert.assertEquals(m2.size(), 0, "a failed send leaves no entry");
        m2.decide(p, false, q -> {
            sends.incrementAndGet();
            return answer(q, 7, RlWire.ST_OK);
        });
        Assert.assertEquals(sends.get(), 3, "and the next ask sends");
    }

    @Test
    public void threadsAskingOneKeyAtOnceSendOnce() throws Exception {
        final int n = 16;
        final SvcMemo m = new SvcMemo();
        final AtomicInteger sends = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        final CyclicBarrier start = new CyclicBarrier(n);
        final List<RlWire.Decision> got = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            final Thread t = new Thread(() -> {
                try {
                    start.await();
                    got.add(m.decide(payload(100L + k, k, 0.1f), false, q -> {
                        sends.incrementAndGet();
                        try {
                            release.await(); // the owner's send is in flight while the others arrive
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        }
                        return answer(q, 7, RlWire.ST_OK);
                    }));
                } catch (Throwable e) {
                    failed.add(e);
                }
            }, "svcmemo-test-" + i);
            ts.add(t);
            t.start();
        }
        Thread.sleep(300);
        Assert.assertEquals(sends.get(), 1, "one thread owns the send; the others wait for its answer");
        release.countDown();
        for (Thread t : ts) {
            t.join(10_000);
            Assert.assertFalse(t.isAlive(), "no thread is left waiting");
        }
        Assert.assertTrue(failed.isEmpty(), String.valueOf(failed));
        Assert.assertEquals(sends.get(), 1);
        Assert.assertEquals(got.size(), n);
        final java.util.Set<Long> uids = new java.util.TreeSet<>();
        for (RlWire.Decision d : got) {
            uids.add(d.gameUid);
            Assert.assertEquals(d.gameUid - 100L, (long) d.decIdx, "each asker's own uid and index");
            Assert.assertTrue(java.util.Arrays.equals(d.steps, new short[] {0}));
        }
        Assert.assertEquals(uids.size(), n);
        Assert.assertEquals(m.hits.get(), n - 1);
        Assert.assertEquals(m.misses.get(), 1);
    }

    @Test
    public void waitersOfAFailedOwnerSendForThemselves() throws Exception {
        final SvcMemo m = new SvcMemo();
        final AtomicInteger sends = new AtomicInteger();
        final CountDownLatch inFlight = new CountDownLatch(1), release = new CountDownLatch(1);
        final byte[] p = payload(1L, 0, 0.1f);
        final Throwable[] ownerErr = {null};
        final Thread owner = new Thread(() -> {
            try {
                m.decide(p, false, q -> {
                    sends.incrementAndGet();
                    inFlight.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        // falls through to the failure
                    }
                    throw new IOException("service gone");
                });
            } catch (Throwable e) {
                ownerErr[0] = e;
            }
        });
        owner.start();
        inFlight.await();
        final RlWire.Decision[] w = {null};
        final Thread waiter = new Thread(() -> {
            try {
                w[0] = m.decide(payload(2L, 5, 0.1f), false, q -> {
                    sends.incrementAndGet();
                    return answer(q, 7, RlWire.ST_OK);
                });
            } catch (IOException e) {
                // fails the assertion below
            }
        });
        waiter.start();
        Thread.sleep(200);
        release.countDown();
        owner.join(10_000);
        waiter.join(10_000);
        Assert.assertTrue(ownerErr[0] instanceof IOException, "the owner's own caller sees its failure");
        Assert.assertNotNull(w[0], "the waiter asked for itself");
        Assert.assertEquals(w[0].gameUid, 2L);
        Assert.assertEquals(sends.get(), 2);
    }
}
