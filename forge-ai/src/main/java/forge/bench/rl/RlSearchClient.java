package forge.bench.rl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * One connection to the S1 search service (lane s1-search-1007; {@code tools/ml/rl/search_server.py}), protocol
 * {@code mtgx-rl-search/1}: the wire/1 framing and DECIDE layout, stateless (no game lifecycle), for the look-ahead's
 * learned parts. Strictly synchronous; one connection per thread.
 *
 * <ul>
 *   <li>HELLO (JSON, as wire/1 with {@code proto = mtgx-rl-search/1}) → HELLO_ACK {@code {ok, policy_sha,
 *       policy_version, device}}.</li>
 *   <li>SCORE 0x0050 (a DECIDE payload, SINGLE mode) → SCORES 0x0051: {@code u64 game_uid, u32 dec_idx, u8 status,
 *       u8 0, u16 C, u32 policy_version, f32 value_obs}, then {@code f32[C]} the policy's probabilities over the
 *       candidates (0 for illegal ones).</li>
 *   <li>DECIDE 0x0020 (a DECIDE payload; frame flags bit 0 = sample, else greedy) → DECISION 0x0021 (wire/1 §2.4); a
 *       sampled decision's seed is {@code decision_seed(game_uid, seat, dec_idx)}.</li>
 *   <li>LEAVES 0x0052: {@code u32 n}, then n × ({@code u32 len}, a DECIDE payload) → VALUES 0x0053: {@code u32 n,
 *       u32 policy_version}, then {@code f32[n]} the observation-only value v(o) in [-1, 1] of each.</li>
 * </ul>
 * Any ERROR frame throws {@link RlClient.ServerError}; transport failures throw {@link IOException}.
 *
 * <p>Leaf batches larger than one frame (lane bvw-p3-batch-1023; every wire/1 frame is at most {@link RlWire#MAX_FRAME}
 * bytes, and before this lane a bigger LEAVES batch threw in {@link RlWire#frameBytes} and the search kept STATIC leaf
 * values for that decision). A batch whose LEAVES payload fits one frame is sent exactly as before: one LEAVES frame,
 * flags 0, the same bytes. A bigger batch:
 * <ul>
 *   <li>to a service whose HELLO_ACK has {@code leaves_chunked >= 1} (search_server.py since this lane): ONE LEAVES
 *       message in several frames. Each frame's payload is itself a LEAVES payload over the next run of leaves (whole
 *       leaves, in order); every frame but the last carries {@link #FLAG_LEAVES_MORE}; the service answers one VALUES
 *       for the whole message after the last frame, so it scores the batch together. A message never exceeds the
 *       ACK's {@code leaves_max} leaves or {@code leaves_bytes_max} LEAVES-payload bytes; a bigger batch is several
 *       such messages in turn;</li>
 *   <li>to an older service (no {@code leaves_chunked} in its HELLO_ACK): the batch goes as consecutive single-frame
 *       LEAVES messages, one VALUES each (the old service answers each frame on its own; the flag is never sent to
 *       it). Said once per connection on stderr.</li>
 * </ul>
 * Either way every leaf gets the network's value; a leaf that cannot fit one frame by itself, or any answer whose count
 * does not match, throws (the caller's fallback is then loud). Values are written back in the batch's order.
 */
public final class RlSearchClient implements Closeable {
    public static final String PROTO = "mtgx-rl-search/1";
    public static final int T_SCORE = 0x0050, T_SCORES = 0x0051, T_LEAVES = 0x0052, T_VALUES = 0x0053;
    public static final int SCORES_HEADER = 24;
    public static final int FLAG_SAMPLE = 1;
    /** LEAVES frame flag (bvw-p3-batch-1023): more frames of the same LEAVES message follow; no answer until the last. */
    public static final int FLAG_LEAVES_MORE = 2;
    /** The HELLO_ACK keys of the chunked-LEAVES capability (bvw-p3-batch-1023). */
    public static final String ACK_LEAVES_CHUNKED = "leaves_chunked", ACK_LEAVES_MAX = "leaves_max",
            ACK_LEAVES_BYTES_MAX = "leaves_bytes_max", ACK_MAX_FRAME = "max_frame";
    /** A LEAVES frame's own bytes on top of its leaves: the frame header (u32 len counts the u16 type + u16 flags) and u32 n. */
    static final int LEAVES_FRAME_OVERHEAD = 4 + 4;

    /** One SCORES answer. */
    public static final class Scores {
        public long gameUid;
        public int decIdx;
        public int status;
        public long policyVersion;
        public float valueObs;
        public float[] probs = new float[0];
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    public JsonObject ack;
    public long calls, nanos;
    /** The configured read timeout, and the one the socket has now (lane forge-continue-1011). */
    private final int readTimeoutMs;
    private int appliedReadTimeoutMs;
    /**
     * The service's chunked-LEAVES capability from its HELLO_ACK (bvw-p3-batch-1023): its version (0 = an older service
     * without it), the leaves and LEAVES-payload bytes it takes in one message, and the frame limit the leaves are packed
     * to (the smaller of {@link RlWire#MAX_FRAME} and the ACK's {@code max_frame}).
     */
    private int leavesChunked = 0;
    private int leavesMax = Integer.MAX_VALUE;
    private long leavesBytesMax = Long.MAX_VALUE;
    private int frameLimit = RlWire.MAX_FRAME;
    private boolean saidSplit = false;
    /** Leaf batches that needed more than one frame, and the frames and messages they took (bvw-p3-batch-1023). */
    public long bigBatches, bigFrames, bigMessages;

    public RlSearchClient(final String hostPort, final int connectTimeoutMs, final int readTimeoutMs) throws IOException {
        final int c = hostPort.lastIndexOf(':');
        if (c <= 0) {
            throw new IllegalArgumentException("search server must be host:port, got " + hostPort);
        }
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(hostPort.substring(0, c), Integer.parseInt(hostPort.substring(c + 1))),
                connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        this.readTimeoutMs = readTimeoutMs;
        this.appliedReadTimeoutMs = readTimeoutMs;
        in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    private void send(final int type, final int flags, final byte[] payload) throws IOException {
        // lane forge-continue-1011: each call's read timeout as it binds now (forge.ai.WallClock): a "Continue from here"
        // rebuild stretches it so a slow answer never degrades the seat differently from the original game. Live it is
        // the configured one, and the socket is never touched again.
        final int timeout = forge.ai.WallClock.readTimeoutMs(readTimeoutMs);
        if (timeout != appliedReadTimeoutMs) {
            socket.setSoTimeout(timeout);
            appliedReadTimeoutMs = timeout;
        }
        out.write(RlWire.frameBytes(type, flags, payload));
        out.flush();
    }

    private RlWire.Frame expect(final int type) throws IOException {
        final RlWire.Frame f = RlWire.readFrame(in);
        if (f.type == RlWire.T_ERROR) {
            String code = "error", msg = "";
            try {
                final JsonObject e = f.json();
                code = e.has("code") ? e.get("code").getAsString() : code;
                msg = e.has("msg") ? e.get("msg").getAsString() : msg;
            } catch (RuntimeException ex) {
                msg = "unparseable ERROR payload";
            }
            throw new RlClient.ServerError(code, msg);
        }
        if (f.type != type) {
            throw new RlClient.ServerError("protocol", "expected 0x" + Integer.toHexString(type) + ", got 0x"
                    + Integer.toHexString(f.type));
        }
        return f;
    }

    public JsonObject hello(final JsonObject hello) throws IOException {
        send(RlWire.T_HELLO, 0, RlWire.canonical(hello));
        ack = expect(RlWire.T_HELLO_ACK).json();
        if (!ack.has("ok") || !ack.get("ok").getAsBoolean()) {
            throw new RlClient.ServerError("hello", "HELLO_ACK not ok: " + ack);
        }
        leavesCaps(ack);
        return ack;
    }

    /** Read the chunked-LEAVES capability of a HELLO_ACK (absent = an older service: single frames only). */
    void leavesCaps(final JsonObject a) throws RlClient.ServerError {
        try {
            leavesChunked = a.has(ACK_LEAVES_CHUNKED) && !a.get(ACK_LEAVES_CHUNKED).isJsonNull()
                    ? a.get(ACK_LEAVES_CHUNKED).getAsInt() : 0;
            if (leavesChunked > 0) {
                leavesMax = a.has(ACK_LEAVES_MAX) ? a.get(ACK_LEAVES_MAX).getAsInt() : Integer.MAX_VALUE;
                leavesBytesMax = a.has(ACK_LEAVES_BYTES_MAX) ? a.get(ACK_LEAVES_BYTES_MAX).getAsLong() : Long.MAX_VALUE;
            }
            final long mf = a.has(ACK_MAX_FRAME) ? a.get(ACK_MAX_FRAME).getAsLong() : RlWire.MAX_FRAME;
            frameLimit = (int) Math.min(RlWire.MAX_FRAME, mf);
        } catch (RuntimeException e) {
            throw new RlClient.ServerError("hello", "unreadable LEAVES capability in HELLO_ACK " + a + ": " + e);
        }
        if (leavesMax < 1 || leavesBytesMax < 1 || frameLimit <= LEAVES_FRAME_OVERHEAD + 4) {
            throw new RlClient.ServerError("hello", "HELLO_ACK LEAVES limits out of range: " + a);
        }
    }

    /** The service's chunked-LEAVES version (0 = none: an older service). */
    public int leavesChunked() {
        return leavesChunked;
    }

    public Scores score(final byte[] decidePayload) throws IOException {
        final long t = System.nanoTime();
        send(T_SCORE, 0, decidePayload);
        final Scores s = decodeScores(expect(T_SCORES).payload);
        calls++;
        nanos += System.nanoTime() - t;
        return s;
    }

    public RlWire.Decision decide(final byte[] decidePayload, final boolean sample) throws IOException {
        final long t = System.nanoTime();
        send(RlWire.T_DECIDE, sample ? FLAG_SAMPLE : 0, decidePayload);
        final RlWire.Decision d;
        try {
            d = RlWire.decodeDecision(expect(RlWire.T_DECISION).payload);
        } catch (IllegalArgumentException e) {
            throw new RlClient.ServerError("protocol", "bad DECISION: " + e.getMessage());
        }
        calls++;
        nanos += System.nanoTime() - t;
        return d;
    }

    /**
     * v(o) in [-1, 1] for each DECIDE payload, in order. A batch whose LEAVES payload fits one frame goes exactly as
     * before; a bigger one is chunked (see the class comment). One call whatever the frames.
     */
    public float[] values(final List<byte[]> decidePayloads) throws IOException {
        final long t = System.nanoTime();
        final float[] v;
        if (LEAVES_FRAME_OVERHEAD + leavesBytes(decidePayloads, 0, decidePayloads.size()) <= frameLimit) {
            v = new float[decidePayloads.size()];
            send(T_LEAVES, 0, encodeLeaves(decidePayloads));
            readValues(v, 0, decidePayloads.size());
        } else {
            v = bigValues(decidePayloads);
        }
        calls++;
        nanos += System.nanoTime() - t;
        return v;
    }

    /** The leaves' bytes in a LEAVES payload, {@code u32 len} each included ({@code u32 n} not). */
    static long leavesBytes(final List<byte[]> ps, final int from, final int to) {
        long s = 0;
        for (int i = from; i < to; i++) {
            s += 4L + ps.get(i).length;
        }
        return s;
    }

    /**
     * The frame runs of a batch: {@code cut[j]} = the first leaf of frame j (and {@code cut[last]} = n), every frame's
     * LEAVES payload within {@code frameLimit}. A leaf that alone cannot fit a frame throws.
     */
    static int[] frameCuts(final List<byte[]> ps, final int frameLimit) throws IOException {
        final int n = ps.size();
        final int[] cut = new int[n + 1];
        int frames = 0;
        long used = 0;
        for (int i = 0; i < n; i++) {
            final long one = 4L + ps.get(i).length;
            if (LEAVES_FRAME_OVERHEAD + one > frameLimit) {
                throw new IOException("LEAVES: leaf " + i + " of " + n + " is " + ps.get(i).length
                        + " bytes and cannot fit one " + frameLimit + "-byte frame");
            }
            if (i == 0 || LEAVES_FRAME_OVERHEAD + used + one > frameLimit) {
                cut[frames++] = i;
                used = 0;
            }
            used += one;
        }
        cut[frames++] = n;
        return java.util.Arrays.copyOf(cut, frames);
    }

    private float[] bigValues(final List<byte[]> ps) throws IOException {
        final int n = ps.size();
        final float[] v = new float[n];
        bigBatches++;
        if (leavesChunked < 1) {
            // an older service: one single-frame LEAVES message per frame run, each answered on its own
            final int[] cut = frameCuts(ps, frameLimit);
            final int frames = cut.length - 1;
            if (!saidSplit) {
                saidSplit = true;
                System.err.println("[rlsearch] the search service has no chunked LEAVES (no " + ACK_LEAVES_CHUNKED
                        + " in its HELLO_ACK): a batch of " + n + " leaves (" + leavesBytes(ps, 0, n) + " bytes) goes as "
                        + frames + " single-frame LEAVES messages (said once per connection)");
            }
            for (int j = 0; j < frames; j++) {
                send(T_LEAVES, 0, encodeLeaves(ps.subList(cut[j], cut[j + 1])));
                readValues(v, cut[j], cut[j + 1] - cut[j]);
                bigFrames++;
                bigMessages++;
            }
            return v;
        }
        // chunked: one message per run of leaves within the ACK's leaves_max and leaves_bytes_max (in practice one)
        int i = 0;
        while (i < n) {
            int e = i;
            long bytes = 0;
            while (e < n && e - i < leavesMax) {
                final long one = 4L + ps.get(e).length;
                if (e > i && bytes + one > leavesBytesMax) {
                    break;
                }
                bytes += one;
                e++;
            }
            final List<byte[]> sub = ps.subList(i, e);
            final int[] cut = frameCuts(sub, frameLimit);
            sendMessage(sub, cut, v, i);
            bigFrames += cut.length - 1;
            bigMessages++;
            i = e;
        }
        return v;
    }

    /**
     * One LEAVES message over {@code sub} in the frames {@code cut} gives ({@link #FLAG_LEAVES_MORE} on all but the last),
     * then its one VALUES, written to {@code v[off ..]}.
     */
    private void sendMessage(final List<byte[]> sub, final int[] cut, final float[] v, final int off) throws IOException {
        final int frames = cut.length - 1;
        try {
            for (int j = 0; j < frames; j++) {
                send(T_LEAVES, j < frames - 1 ? FLAG_LEAVES_MORE : 0, encodeLeaves(sub.subList(cut[j], cut[j + 1])));
            }
        } catch (IOException e) {
            // the service may have refused the message (ERROR, then close) while frames were still going out: its
            // reason is the better error
            try {
                expect(T_VALUES);
            } catch (RlClient.ServerError se) {
                se.addSuppressed(e);
                throw se;
            } catch (IOException | RuntimeException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
        readValues(v, off, sub.size());
    }

    /** One VALUES answer for {@code n} leaves, written to {@code v[off .. off + n)}. */
    private void readValues(final float[] v, final int off, final int n) throws IOException {
        final ByteBuffer b = ByteBuffer.wrap(expect(T_VALUES).payload).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 8) {
            throw new RlClient.ServerError("protocol", "VALUES shorter than its header: " + b.remaining());
        }
        final int got = b.getInt();
        b.getInt(); // policy_version
        if (got != n || b.remaining() != 4L * n) {
            throw new RlClient.ServerError("protocol", "VALUES for " + got + " leaves, sent " + n);
        }
        for (int i = 0; i < n; i++) {
            v[off + i] = b.getFloat();
        }
    }

    static byte[] encodeLeaves(final List<byte[]> payloads) {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final ByteBuffer h = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(payloads.size());
        bo.write(h.array(), 0, 4);
        for (byte[] p : payloads) {
            final ByteBuffer l = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            l.putInt(p.length);
            bo.write(l.array(), 0, 4);
            bo.write(p, 0, p.length);
        }
        return bo.toByteArray();
    }

    static Scores decodeScores(final byte[] p) throws RlClient.ServerError {
        if (p.length < SCORES_HEADER) {
            throw new RlClient.ServerError("protocol", "SCORES shorter than its header: " + p.length);
        }
        final ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        final Scores s = new Scores();
        s.gameUid = b.getLong();
        s.decIdx = b.getInt();
        s.status = b.get() & 0xff;
        b.get();
        final int c = b.getShort() & 0xffff;
        s.policyVersion = b.getInt() & 0xffffffffL;
        s.valueObs = b.getFloat();
        if (p.length != SCORES_HEADER + 4 * c) {
            throw new RlClient.ServerError("protocol", "SCORES length " + p.length + " does not match C " + c);
        }
        s.probs = new float[c];
        for (int i = 0; i < c; i++) {
            s.probs[i] = b.getFloat();
        }
        return s;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // nothing to do
        }
    }
}
