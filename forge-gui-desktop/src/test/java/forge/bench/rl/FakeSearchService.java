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
 * listening (a service that died mid-game).
 */
public final class FakeSearchService implements Closeable {
    private final ServerSocket ss;
    private final String policySha;
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    public volatile long failAfterDecides = Long.MAX_VALUE;
    /**
     * Lane cm-choice-search-1009: v(o) of a leaf payload, in [-1, 1] (null, the default: 0 for every leaf). Tests that
     * need the search to depart give the leaves values that differ.
     */
    public volatile java.util.function.ToDoubleFunction<byte[]> leafValue = null;
    public final AtomicLong hellos = new AtomicLong(), scores = new AtomicLong(), decides = new AtomicLong(),
            leaves = new AtomicLong(), errors = new AtomicLong();
    private volatile boolean dead = false;

    public FakeSearchService(final String policySha) throws IOException {
        this.ss = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
        this.policySha = policySha;
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
            hellos.incrementAndGet();
            final JsonObject ack = new JsonObject();
            ack.addProperty("ok", true);
            ack.addProperty("server", "search");
            ack.addProperty("policy_sha", policySha);
            ack.addProperty("policy_version", 7);
            ack.addProperty("device", "cpu");
            RlWire.writeFrame(out, RlWire.T_HELLO_ACK, 0, RlWire.canonical(ack));
            out.flush();
            while (!dead) {
                final RlWire.Frame f = RlWire.readFrame(in);
                if (f.type == RlSearchClient.T_SCORE) {
                    scores.incrementAndGet();
                    final RlWire.Decide d = RlWire.decodeDecide(f.payload, 1);
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
                    final RlWire.Decide d = RlWire.decodeDecide(f.payload, 1);
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
