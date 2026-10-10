package forge.bench.rl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;

/**
 * Test-scope stand-in for {@code tools/ml/rl/search_server.py} (lane live-sc-1009), protocol {@code mtgx-rl-search/1}:
 * HELLO → HELLO_ACK with a configurable {@code policy_sha}; SCORE → uniform probabilities over the legal candidates;
 * DECIDE → random legal steps (FakeRlServer's rule, seeded by game and decision); LEAVES → v(o) = 0 for every leaf.
 * {@link #failAfterDecides}: after that many DECIDE frames in all, the service drops every connection and stops
 * listening (a service that died mid-game). {@link #playoutDecideDelayMs} (lane sc-wallguard-1009): DECIDE answers on the
 * look-ahead's play-out connections (HELLO thread {@code Game-lookahead-*}) come that much later (a slow or looping
 * play-out seat that keeps the search past its budget).
 *
 * <p>Lane search-v2-1009: the service has an observation schema ({@link #FakeSearchService(String, int)}; 1 by default)
 * and is as strict as {@code search_server.py} about it: a HELLO with another schema's sha is answered ERROR and closed,
 * the HELLO_ACK names its schema ({@code obs_schema}; {@link #ackObs} overrides it, -1 leaves it out), and every SCORE,
 * DECIDE and LEAVES payload is decoded as that schema's frame (a frame of the other schema fails to decode and the
 * connection is closed, as the real service does). With {@link #checker} set, every DECIDE frame goes through
 * {@link FakeRlServer#check} and every SCORE and leaf frame through its v2 structure check.
 */
public final class FakeSearchService implements Closeable {
    private final ServerSocket ss;
    private final String policySha;
    /** The observation schema this service decodes (search-v2-1009). */
    public final int obs;
    /** The HELLO_ACK's {@code obs_schema}: the service's own by default; -1 = left out (an older service). */
    public volatile int ackObs;
    /** Optional frame checker (search-v2-1009). */
    public volatile FakeRlServer checker = null;
    /** Frames refused or failed to decode (search-v2-1009). */
    public final AtomicLong refusedHellos = new AtomicLong(), badFrames = new AtomicLong();
    /** The leaf payloads seen, when {@link #keepLeaves} (tests that compare leaf observations). */
    public volatile boolean keepLeaves = false;
    public final List<byte[]> leafPayloads = Collections.synchronizedList(new ArrayList<>());
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    public volatile long failAfterDecides = Long.MAX_VALUE;
    /**
     * Lane cm-choice-search-1009: v(o) of a leaf payload, in [-1, 1] (null, the default: 0 for every leaf). Tests that
     * need the search to depart give the leaves values that differ.
     */
    public volatile java.util.function.ToDoubleFunction<byte[]> leafValue = null;
    /** Lane sc-wallguard-1009: delay of every DECIDE answer on a play-out connection, in ms (0 = none, the default). */
    public volatile long playoutDecideDelayMs = 0;
    public final AtomicLong hellos = new AtomicLong(), scores = new AtomicLong(), decides = new AtomicLong(),
            leaves = new AtomicLong(), errors = new AtomicLong();
    private volatile boolean dead = false;

    public FakeSearchService(final String policySha) throws IOException {
        this(policySha, 1);
    }

    public FakeSearchService(final String policySha, final int obs) throws IOException {
        if (obs != 1 && obs != 2) {
            throw new IllegalArgumentException("obs " + obs);
        }
        this.ss = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
        this.policySha = policySha;
        this.obs = obs;
        this.ackObs = obs;
        final Thread t = new Thread(this::acceptLoop, "fake-search-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return ss.getLocalPort();
    }

    public String address() {
        return "127.0.0.1:" + port();
    }

    private void acceptLoop() {
        while (!ss.isClosed()) {
            try {
                final Socket s = ss.accept();
                s.setTcpNoDelay(true);
                sockets.add(s);
                final Thread t = new Thread(() -> serve(s), "fake-search-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void die() {
        dead = true;
        close();
    }

    private void serve(final Socket s) {
        try (InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            final RlWire.Frame h = RlWire.readFrame(in);
            if (h.type != RlWire.T_HELLO) {
                errors.incrementAndGet();
                return;
            }
            final JsonObject hello = h.json();
            final String want = obs == 2 ? RlSchemaV2.schemaSha() : RlSchema.schemaSha();
            if (!hello.has("schema_sha") || !want.equals(hello.get("schema_sha").getAsString())) {
                // as search_server.py: another schema's HELLO is answered ERROR and the connection closed
                refusedHellos.incrementAndGet();
                final JsonObject e = new JsonObject();
                e.addProperty("code", "schema_sha");
                e.addProperty("msg", "schema_sha " + hello.get("schema_sha") + " != " + want);
                RlWire.writeFrame(out, RlWire.T_ERROR, 0, RlWire.canonical(e));
                out.flush();
                return;
            }
            hellos.incrementAndGet();
            final boolean playout = hello.has("thread") && hello.get("thread").getAsString().startsWith("Game-lookahead");
            final JsonObject ack = new JsonObject();
            ack.addProperty("ok", true);
            ack.addProperty("server", "search");
            ack.addProperty("policy_sha", policySha);
            ack.addProperty("policy_version", 7);
            ack.addProperty("device", "cpu");
            if (ackObs >= 0) {
                ack.addProperty("obs_schema", ackObs);
            }
            RlWire.writeFrame(out, RlWire.T_HELLO_ACK, 0, RlWire.canonical(ack));
            out.flush();
            while (!dead) {
                final RlWire.Frame f = RlWire.readFrame(in);
                if (f.type == RlSearchClient.T_SCORE) {
                    scores.incrementAndGet();
                    final RlWire.Decide d = decode(f.payload);
                    if (d == null) {
                        return;
                    }
                    final FakeRlServer ck = checker;
                    if (ck != null && d.version == 2) {
                        ck.checkV2(d);
                    }
                    if (d.mode != RlSchema.M_SINGLE) {
                        // as search_server.py: SCORE takes SINGLE-mode frames only; an error closes the connection
                        errors.incrementAndGet();
                        final JsonObject e = new JsonObject();
                        e.addProperty("code", "mode");
                        e.addProperty("msg", "SCORE takes SINGLE-mode frames only");
                        RlWire.writeFrame(out, RlWire.T_ERROR, 0, RlWire.canonical(e));
                        out.flush();
                        return;
                    }
                    final ByteBuffer b = ByteBuffer.allocate(RlSearchClient.SCORES_HEADER + 4 * d.C).order(ByteOrder.LITTLE_ENDIAN);
                    int legal = 0;
                    for (int i = 0; i < d.C; i++) {
                        if (d.candKind[i] > 0) {
                            legal++;
                        }
                    }
                    b.putLong(d.gameUid).putInt(d.decIdx).put((byte) RlWire.ST_OK).put((byte) 0).putShort((short) d.C)
                            .putInt(7).putFloat(0f);
                    for (int i = 0; i < d.C; i++) {
                        b.putFloat(d.candKind[i] > 0 ? 1f / Math.max(1, legal) : 0f);
                    }
                    RlWire.writeFrame(out, RlSearchClient.T_SCORES, 0, b.array());
                } else if (f.type == RlWire.T_DECIDE) {
                    if (decides.incrementAndGet() > failAfterDecides) {
                        die();
                        return;
                    }
                    final long delay = playoutDecideDelayMs;
                    if (playout && delay > 0) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    final RlWire.Decide d = decode(f.payload);
                    if (d == null) {
                        return;
                    }
                    final FakeRlServer ck = checker;
                    if (ck != null) {
                        ck.check(d, false);
                    }
                    final RlWire.Decision x = new RlWire.Decision();
                    x.gameUid = d.gameUid;
                    x.decIdx = d.decIdx;
                    x.status = RlWire.ST_OK;
                    x.policyVersion = 7;
                    x.steps = FakeRlServer.randomSteps(d, new Random(FakeRlServer.mix(11, d.gameUid, d.decIdx)));
                    RlWire.writeFrame(out, RlWire.T_DECISION, 0, RlWire.encodeDecision(x));
                } else if (f.type == RlSearchClient.T_LEAVES) {
                    final ByteBuffer p = ByteBuffer.wrap(f.payload).order(ByteOrder.LITTLE_ENDIAN);
                    final int n = p.getInt();
                    leaves.addAndGet(n);
                    final ByteBuffer b = ByteBuffer.allocate(8 + 4 * n).order(ByteOrder.LITTLE_ENDIAN);
                    b.putInt(n).putInt(7);
                    final java.util.function.ToDoubleFunction<byte[]> lv = leafValue;
                    for (int i = 0; i < n; i++) {
                        final int len = p.getInt();
                        final byte[] leaf = new byte[len];
                        p.get(leaf);
                        final RlWire.Decide ld = decode(leaf);
                        if (ld == null) {
                            return;
                        }
                        final FakeRlServer ck = checker;
                        if (ck != null && ld.version == 2) {
                            ck.checkV2(ld);
                        }
                        if (keepLeaves) {
                            leafPayloads.add(leaf);
                        }
                        b.putFloat(lv == null ? 0f : (float) lv.applyAsDouble(leaf));
                    }
                    RlWire.writeFrame(out, RlSearchClient.T_VALUES, 0, b.array());
                } else {
                    errors.incrementAndGet();
                    return;
                }
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            // a closed connection ends this thread
        }
    }

    /** This service's decode of a DECIDE payload, or null (counted) when it is not a frame of its schema. */
    private RlWire.Decide decode(final byte[] p) {
        try {
            return RlWire.decodeDecide(p, obs);
        } catch (RuntimeException e) {
            badFrames.incrementAndGet();
            errors.incrementAndGet();
            return null;
        }
    }

    @Override
    public void close() {
        try {
            ss.close();
        } catch (IOException e) {
            // nothing to do
        }
        synchronized (sockets) {
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException e) {
                    // nothing to do
                }
            }
        }
    }
}
