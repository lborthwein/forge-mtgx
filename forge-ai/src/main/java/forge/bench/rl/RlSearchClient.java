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
 */
public final class RlSearchClient implements Closeable {
    public static final String PROTO = "mtgx-rl-search/1";
    public static final int T_SCORE = 0x0050, T_SCORES = 0x0051, T_LEAVES = 0x0052, T_VALUES = 0x0053;
    public static final int SCORES_HEADER = 24;
    public static final int FLAG_SAMPLE = 1;

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
        in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    private void send(final int type, final int flags, final byte[] payload) throws IOException {
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
        return ack;
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

    /** v(o) in [-1, 1] for each DECIDE payload, in order. */
    public float[] values(final List<byte[]> decidePayloads) throws IOException {
        final long t = System.nanoTime();
        send(T_LEAVES, 0, encodeLeaves(decidePayloads));
        final ByteBuffer b = ByteBuffer.wrap(expect(T_VALUES).payload).order(ByteOrder.LITTLE_ENDIAN);
        final int n = b.getInt();
        b.getInt(); // policy_version
        if (n != decidePayloads.size() || b.remaining() != 4 * n) {
            throw new RlClient.ServerError("protocol", "VALUES for " + n + " leaves, sent " + decidePayloads.size());
        }
        final float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = b.getFloat();
        }
        calls++;
        nanos += System.nanoTime() - t;
        return v;
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
