package forge.bench.rl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/**
 * Lane bvw-p3-batch-1023 (BVW P3): value-leaf batches larger than one wire frame ({@link RlWire#MAX_FRAME}, 1 MiB). Before
 * this lane such a batch threw in {@link RlWire#frameBytes} and the search kept STATIC leaf values for the decision. Now:
 * <ul>
 *   <li>a batch that fits one frame is the old single LEAVES frame, byte for byte, whatever the service;</li>
 *   <li>a bigger batch to a service that advertises {@code leaves_chunked} is ONE message in several frames
 *       ({@link RlSearchClient#FLAG_LEAVES_MORE} on all but the last), every frame within the frame limit, answered by
 *       one VALUES, values in the batch's order (512 and 1,024 leaves);</li>
 *   <li>to an older service (no capability) it is consecutive single-frame messages, never a flagged frame;</li>
 *   <li>the HELLO_ACK's {@code max_frame}, {@code leaves_max} and {@code leaves_bytes_max} are honoured; limits out of
 *       range, a leaf that cannot fit a frame and a service that answers a chunked message frame by frame fail loudly.</li>
 * </ul>
 * Uses {@link FakeSearchService} (obs-v2, the live schema) with real v2 frames; no Forge game, no sound.
 */
public class RlLeafBatchTest {

    /** {@code n} obs-v2 frames (RlObsV2SchemaTest's sampler: L up to the schema maximum, relations and facts). */
    static List<byte[]> leaves(final long seed, final int n) {
        final Random r = new Random(seed);
        final List<byte[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final RlWire.Decide d = RlObsV2SchemaTest.sampleV2(r, false, 0);
            d.gameUid = 1000 + i;
            d.decIdx = i;
            out.add(RlWire.encodeDecide(d));
        }
        return out;
    }

    /** The fake service's v(o): a fixed function of the leaf's bytes (so order and pairing are checked). */
    static double value(final byte[] leaf) {
        final CRC32 c = new CRC32();
        c.update(leaf);
        return (c.getValue() % 20001) / 10000.0 - 1.0;
    }

    static FakeSearchService service() throws IOException {
        final FakeSearchService s = new FakeSearchService("a".repeat(64), 2);
        s.leafValue = RlLeafBatchTest::value;
        return s;
    }

    static RlSearchClient connect(final FakeSearchService s) throws IOException {
        final RlSearchClient c = new RlSearchClient(s.address(), 5_000, 60_000);
        final JsonObject h = new JsonObject();
        h.addProperty("proto", RlSearchClient.PROTO);
        h.addProperty("schema_sha", RlSchemaV2.schemaSha());
        h.addProperty("card_index_sha", "c".repeat(64));
        h.addProperty("thread", "test");
        c.hello(h);
        return c;
    }

    static void assertValues(final float[] v, final List<byte[]> ps) {
        Assert.assertEquals(v.length, ps.size());
        for (int i = 0; i < v.length; i++) {
            Assert.assertEquals(v[i], (float) value(ps.get(i)), "leaf " + i);
        }
    }

    /** Every frame's payload within the limit (frameBytes: 4 + payload <= limit). */
    static void assertFramesWithin(final FakeSearchService s, final long limit) {
        for (int[] f : s.leafFrames) {
            Assert.assertTrue(4L + f[1] <= limit, "a LEAVES frame of " + (4 + f[1]) + " bytes > " + limit);
        }
    }

    // ------------------------------------------------------------------------------------------------ identity

    @Test
    public void aBatchThatFitsOneFrameIsTheOldFrameByteForByte() throws IOException {
        final List<byte[]> ps = leaves(11, 32);
        Assert.assertTrue(RlSearchClient.LEAVES_FRAME_OVERHEAD + RlSearchClient.leavesBytes(ps, 0, ps.size())
                <= RlWire.MAX_FRAME, "the fixture's 32 leaves fit one frame");
        for (boolean chunked : new boolean[] {true, false}) {
            try (FakeSearchService s = service()) {
                s.chunkedLeaves = chunked;
                s.keepLeafFrames = true;
                try (RlSearchClient c = connect(s)) {
                    Assert.assertEquals(c.leavesChunked(), chunked ? 1 : 0);
                    assertValues(c.values(ps), ps);
                    Assert.assertEquals(c.bigBatches, 0);
                }
                Assert.assertEquals(s.leafFrames.size(), 1);
                Assert.assertEquals(s.leafFrames.get(0)[0], 0, "flags 0");
                // the pre-lane encoding: u32 n, then n x (u32 len, payload)
                final ByteBuffer b = ByteBuffer.allocate(4 + (int) RlSearchClient.leavesBytes(ps, 0, ps.size()))
                        .order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(ps.size());
                for (byte[] p : ps) {
                    b.putInt(p.length).put(p);
                }
                Assert.assertEquals(s.leafFramePayloads.get(0), b.array());
                Assert.assertEquals(s.leafMessages, Arrays.asList(32));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ chunking

    @DataProvider
    public Object[][] big() {
        return new Object[][] {{512}, {1024}};
    }

    @Test(dataProvider = "big")
    public void aBigBatchIsOneChunkedMessage(final int n) throws IOException {
        final List<byte[]> ps = leaves(23 + n, n);
        final long bytes = RlSearchClient.leavesBytes(ps, 0, n);
        Assert.assertTrue(bytes > 2L * RlWire.MAX_FRAME, "the fixture needs several frames: " + bytes);
        try (FakeSearchService s = service()) {
            s.keepLeaves = true;
            final float[] v;
            try (RlSearchClient c = connect(s)) {
                v = c.values(ps);
                Assert.assertEquals(c.bigBatches, 1);
                Assert.assertEquals(c.bigMessages, 1);
                Assert.assertEquals(c.bigFrames, s.leafFrames.size());
                // a small batch on the same connection afterwards is the old single frame again
                assertValues(c.values(ps.subList(0, 3)), ps.subList(0, 3));
            }
            assertValues(v, ps);
            final int frames = s.leafFrames.size() - 1;
            Assert.assertTrue(frames >= 3, "frames " + frames);
            for (int j = 0; j < frames; j++) {
                Assert.assertEquals(s.leafFrames.get(j)[0], j < frames - 1 ? RlSearchClient.FLAG_LEAVES_MORE : 0);
            }
            Assert.assertEquals(s.leafFrames.get(frames)[0], 0);
            assertFramesWithin(s, RlWire.MAX_FRAME);
            Assert.assertEquals(s.leafMessages, Arrays.asList(n, 3));
            // whole leaves, in order, nothing dropped or repeated
            Assert.assertEquals(s.leafPayloads.size(), n + 3);
            for (int i = 0; i < n; i++) {
                Assert.assertEquals(s.leafPayloads.get(i), ps.get(i), "leaf " + i);
            }
        }
    }

    @Test
    public void anOlderServiceGetsSingleFrameMessagesOnly() throws IOException {
        final List<byte[]> ps = leaves(5, 1024);
        try (FakeSearchService s = service()) {
            s.chunkedLeaves = false;
            try (RlSearchClient c = connect(s)) {
                Assert.assertEquals(c.leavesChunked(), 0);
                assertValues(c.values(ps), ps);
                Assert.assertEquals(c.bigBatches, 1);
                Assert.assertEquals(c.bigMessages, s.leafFrames.size());
            }
            Assert.assertTrue(s.leafFrames.size() >= 3);
            for (int[] f : s.leafFrames) {
                Assert.assertEquals(f[0], 0, "never a flagged frame to an older service");
            }
            assertFramesWithin(s, RlWire.MAX_FRAME);
            Assert.assertEquals(s.leafMessages.stream().mapToInt(Integer::intValue).sum(), 1024);
            Assert.assertEquals(s.leafMessages.size(), s.leafFrames.size());
        }
    }

    // ------------------------------------------------------------------------------------------------ negotiation

    @Test
    public void theAcksLimitsAreHonoured() throws IOException {
        final List<byte[]> ps = leaves(7, 1024);
        // a smaller frame limit and at most 100 leaves per message
        try (FakeSearchService s = service()) {
            s.ackMaxFrame = 128 << 10;
            s.ackLeavesMax = 100;
            try (RlSearchClient c = connect(s)) {
                assertValues(c.values(ps), ps);
                Assert.assertEquals(c.bigMessages, 11);
                // 3 leaves fit 1 MiB but not necessarily 128 KiB: the identity path uses the negotiated limit too
                assertValues(c.values(ps.subList(0, 8)), ps.subList(0, 8));
            }
            assertFramesWithin(s, 128 << 10);
            for (int m : s.leafMessages) {
                Assert.assertTrue(m <= 100, "a message of " + m + " leaves");
            }
            Assert.assertEquals(s.leafMessages.subList(0, 11).stream().mapToInt(Integer::intValue).sum(), 1024);
        }
        // a byte limit per message
        try (FakeSearchService s = service()) {
            final long cap = 3L * RlWire.MAX_FRAME;
            s.ackLeavesBytesMax = cap;
            s.keepLeafFrames = true;
            try (RlSearchClient c = connect(s)) {
                assertValues(c.values(ps), ps);
                Assert.assertTrue(c.bigMessages >= 2, "messages " + c.bigMessages);
            }
            long inMessage = 0;
            for (int j = 0; j < s.leafFrames.size(); j++) {
                inMessage += s.leafFrames.get(j)[1] - 4;
                if (s.leafFrames.get(j)[0] == 0) {
                    Assert.assertTrue(inMessage <= cap, "a message of " + inMessage + " leaf bytes");
                    inMessage = 0;
                }
            }
        }
    }

    @Test
    public void limitsOutOfRangeAreRefusedAtHello() throws IOException {
        try (FakeSearchService s = service()) {
            s.ackLeavesMax = 0;
            final RlClient.ServerError e = Assert.expectThrows(RlClient.ServerError.class, () -> connect(s).close());
            Assert.assertEquals(e.code, "hello");
        }
        try (FakeSearchService s = service()) {
            s.ackMaxFrame = 12;
            Assert.expectThrows(RlClient.ServerError.class, () -> connect(s).close());
        }
    }

    // ------------------------------------------------------------------------------------------------ loud failures

    @Test
    public void aLeafThatCannotFitAFrameFailsLoudly() throws IOException {
        final List<byte[]> ps = leaves(3, 4);
        try (FakeSearchService s = service()) {
            s.ackMaxFrame = 4096;
            try (RlSearchClient c = connect(s)) {
                final IOException e = Assert.expectThrows(IOException.class, () -> c.values(ps));
                Assert.assertTrue(e.getMessage().contains("cannot fit one 4096-byte frame"), e.getMessage());
            }
            Assert.assertEquals(s.leafFrames.size(), 0, "nothing was sent");
        }
    }

    @Test
    public void aServiceThatAnswersAChunkedMessageFrameByFrameFailsLoudly() throws IOException {
        final List<byte[]> ps = leaves(9, 512);
        try (FakeSearchService s = service()) {
            s.answerEveryFrame = true;
            try (RlSearchClient c = connect(s)) {
                final RlClient.ServerError e = Assert.expectThrows(RlClient.ServerError.class, () -> c.values(ps));
                Assert.assertEquals(e.code, "protocol");
                Assert.assertTrue(e.getMessage().contains("VALUES for"), e.getMessage());
            }
        }
    }

    @Test
    public void frameCutsKeepWholeLeavesInOrder() throws IOException {
        final List<byte[]> ps = new ArrayList<>();
        for (int len : new int[] {100, 100, 100, 50, 300, 1, 0}) {
            ps.add(new byte[len]);
        }
        // each leaf takes len + 4, each frame 8 more: at 8 + 320 -> [100 100 100] (312), [50] (+304 > 320), [300 1 0] (313)
        Assert.assertEquals(RlSearchClient.frameCuts(ps, 8 + 320), new int[] {0, 3, 4, 7});
        // the 300-byte leaf alone needs 8 + 304: a smaller limit cannot carry it
        Assert.expectThrows(IOException.class, () -> RlSearchClient.frameCuts(ps, 8 + 303));
        Assert.assertEquals(RlSearchClient.frameCuts(ps.subList(0, 1), 8 + 104), new int[] {0, 1});
    }
}
