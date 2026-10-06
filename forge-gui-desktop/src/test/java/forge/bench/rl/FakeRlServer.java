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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;

/**
 * Test-scope wire/1 server (lane rl-r0-b1-1005): serves a fixed GAME schedule, answers every DECIDE with uniformly
 * random legal steps under the §5.3 decoding rules (SINGLE: one legal candidate; SUBSET: picks among the unchosen
 * legal candidates plus STOP once {@code t >= min_pick}, ending at {@code max_pick}; ASSIGN: one candidate per slot),
 * acks RECORD and GAME_END, and checks every frame it receives (layout, pointer ranges, trivial asks, RECORD teacher
 * legality). The per-decision random stream depends only on (seed, game_uid, dec_idx), never on batch or thread.
 */
public final class FakeRlServer implements Closeable {

    /** Called for every DECIDE/RECORD frame (the whole frame bytes). */
    public interface Capture {
        void frame(int type, byte[] wholeFrame, RlWire.Decide d, short[] answerSteps);
    }

    private final ServerSocket ss;
    private final String mode;
    private final long seed;
    private final String expectCardIndexSha;
    private final List<JsonObject> schedule;
    private final AtomicInteger next = new AtomicInteger();
    private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
    public volatile Capture capture;

    public final AtomicLong decides = new AtomicLong(), records = new AtomicLong(), gameEnds = new AtomicLong();
    public final AtomicLong trivialFrames = new AtomicLong(), badFrames = new AtomicLong(),
            badTeachers = new AtomicLong(), privFrames = new AtomicLong(), errors = new AtomicLong();
    public final Map<Integer, AtomicLong> byFamily = new ConcurrentHashMap<>();
    public final List<JsonObject> ends = Collections.synchronizedList(new ArrayList<>());
    public final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    public final List<JsonObject> hellos = Collections.synchronizedList(new ArrayList<>());

    public FakeRlServer(final int port, final String mode, final long seed, final String expectCardIndexSha,
            final List<JsonObject> schedule) throws IOException {
        this.ss = new ServerSocket(port, 64, InetAddress.getLoopbackAddress());
        this.mode = mode;
        this.seed = seed;
        this.expectCardIndexSha = expectCardIndexSha;
        this.schedule = schedule;
        final Thread acceptor = new Thread(this::acceptLoop, "fake-rl-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return ss.getLocalPort();
    }

    private void acceptLoop() {
        while (!ss.isClosed()) {
            try {
                final Socket s = ss.accept();
                s.setTcpNoDelay(true);
                final Thread t = new Thread(() -> serve(s), "fake-rl-conn");
                t.setDaemon(true);
                threads.add(t);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void problem(final String p) {
        if (problems.size() < 200) {
            problems.add(p);
        }
    }

    private void serve(final Socket s) {
        try (Socket sock = s) {
            final InputStream in = new BufferedInputStream(sock.getInputStream(), 1 << 16);
            final OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 1 << 16);
            final RlWire.Frame h = RlWire.readFrame(in);
            if (h.type != RlWire.T_HELLO) {
                RlWire.writeFrame(out, RlWire.T_ERROR, 0, RlWire.canonical(RlWire.error("protocol", "HELLO first")));
                return;
            }
            final JsonObject hello = h.json();
            hellos.add(hello);
            String refuse = null;
            if (!RlWire.PROTO.equals(hello.get("proto").getAsString())) {
                refuse = "proto";
            } else if (!RlSchema.schemaSha().equals(hello.get("schema_sha").getAsString())) {
                refuse = "schema_sha";
            } else if (expectCardIndexSha != null
                    && !expectCardIndexSha.equals(hello.get("card_index_sha").getAsString())) {
                refuse = "card_index_sha";
            } else if (!mode.equals(hello.get("mode").getAsString())) {
                refuse = "mode";
            }
            if (refuse != null) {
                RlWire.writeFrame(out, RlWire.T_ERROR, 0, RlWire.canonical(RlWire.error("hello", refuse)));
                return;
            }
            final JsonObject ack = new JsonObject();
            ack.addProperty("ok", true);
            ack.addProperty("server_id", "fake");
            ack.addProperty("run_id", "fake-run");
            ack.addProperty("config_sha", "0");
            RlWire.writeFrame(out, RlWire.T_HELLO_ACK, 0, RlWire.canonical(ack));
            while (true) {
                final RlWire.Frame f = RlWire.readFrame(in);
                switch (f.type) {
                    case RlWire.T_NEXT_GAME: {
                        final int i = next.getAndIncrement();
                        final JsonObject g;
                        if (i < schedule.size()) {
                            g = schedule.get(i);
                        } else {
                            g = new JsonObject();
                            g.addProperty("stop", true);
                        }
                        RlWire.writeFrame(out, RlWire.T_GAME, 0, RlWire.canonical(g));
                        break;
                    }
                    case RlWire.T_DECIDE: {
                        decides.incrementAndGet();
                        final RlWire.Decide d = RlWire.decodeDecide(f.payload);
                        check(d, false);
                        final RlWire.Decision x = new RlWire.Decision();
                        x.gameUid = d.gameUid;
                        x.decIdx = d.decIdx;
                        x.status = RlWire.ST_OK;
                        x.policyVersion = 1;
                        x.steps = randomSteps(d, new Random(mix(seed, d.gameUid, d.decIdx)));
                        if (capture != null) {
                            capture.frame(f.type, RlWire.frameBytes(f.type, f.flags, f.payload), d, x.steps);
                        }
                        RlWire.writeFrame(out, RlWire.T_DECISION, 0, RlWire.encodeDecision(x));
                        break;
                    }
                    case RlWire.T_RECORD: {
                        records.incrementAndGet();
                        final RlWire.Decide d = RlWire.decodeDecide(f.payload);
                        check(d, true);
                        if (capture != null) {
                            capture.frame(f.type, RlWire.frameBytes(f.type, f.flags, f.payload), d, d.teacher);
                        }
                        RlWire.writeFrame(out, RlWire.T_RECORD_ACK, 0, new byte[0]);
                        break;
                    }
                    case RlWire.T_GAME_END: {
                        gameEnds.incrementAndGet();
                        ends.add(f.json());
                        RlWire.writeFrame(out, RlWire.T_GAME_END_ACK, 0, new byte[0]);
                        break;
                    }
                    case RlWire.T_ERROR:
                        errors.incrementAndGet();
                        problem("actor ERROR: " + f.json());
                        return;
                    default:
                        problem("unexpected frame " + RlWire.typeName(f.type));
                        return;
                }
            }
        } catch (IOException e) {
            // the actor closed the connection
        } catch (RuntimeException e) {
            badFrames.incrementAndGet();
            problem("server exception: " + e);
        }
    }

    public static long mix(final long a, final long b, final long c) {
        long z = a * 0x9E3779B97F4A7C15L ^ b * 0xBF58476D1CE4E5B9L ^ c * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Frame invariants: header/mode/family consistency, pointer ranges, priv rules, no trivial ask. */
    public void check(final RlWire.Decide d, final boolean record) {
        byFamily.computeIfAbsent(d.family, k -> new AtomicLong()).incrementAndGet();
        if (d.hasPriv()) {
            privFrames.incrementAndGet();
            if ("eval".equals(mode)) {
                badFrames.incrementAndGet();
                problem("priv block in eval mode");
            }
            int prevZone = -1, prevCard = Integer.MIN_VALUE;
            for (int i = 0; i < d.P; i++) {
                final int z = d.privZone[i] & 0xff;
                if (z < 22 || z > 24 || (z == prevZone && d.privCard[i] <= prevCard) || z < prevZone) {
                    badFrames.incrementAndGet();
                    problem("priv block not sorted by (zone, card) at " + i);
                    break;
                }
                prevZone = z;
                prevCard = d.privCard[i];
            }
        }
        final int expectMode = d.family == RlSchema.F_PRIORITY || d.family == RlSchema.F_MULLIGAN
                || d.family == RlSchema.F_START_PLAYER ? RlSchema.M_SINGLE
                : d.family == RlSchema.F_TARGETS || d.family == RlSchema.F_MULLIGAN_BOTTOM ? RlSchema.M_SUBSET
                : d.family == RlSchema.F_ATTACK || d.family == RlSchema.F_BLOCK ? RlSchema.M_ASSIGN : -1;
        if (d.mode != expectMode) {
            badFrames.incrementAndGet();
            problem("family " + d.family + " with mode " + d.mode);
        }
        if (d.L > RlSchema.L_MAX || d.C > RlSchema.C_MAX || d.S > RlSchema.S_MAX || d.D > RlSchema.D_MAX
                || d.P > RlSchema.P_MAX) {
            badFrames.incrementAndGet();
            problem("caps exceeded: L" + d.L + " C" + d.C + " S" + d.S);
        }
        for (int i = 0; i < d.L; i++) {
            if ((d.tokZone[i] & 0xff) < 1 || (d.tokZone[i] & 0xff) > 21 || d.tokCard[i] < 1) {
                badFrames.incrementAndGet();
                problem("bad token " + i + ": zone " + d.tokZone[i] + " card " + d.tokCard[i]);
                break;
            }
        }
        for (int i = 0; i < d.C; i++) {
            final int tok = d.candTok[i], t0 = d.candTgt[2 * i], t1 = d.candTgt[2 * i + 1];
            if (tok < -1 || tok >= d.L || t0 < -3 || t0 >= d.L || t1 < -3 || t1 >= d.L
                    || d.candSlot[i] < -1 || d.candSlot[i] >= Math.max(1, d.S) || d.candKind[i] <= 0) {
                badFrames.incrementAndGet();
                problem("bad candidate " + i + " family " + d.family);
                break;
            }
        }
        for (int s = 0; s < d.S; s++) {
            if (d.slotTok[s] < -1 || d.slotTok[s] >= d.L) {
                badFrames.incrementAndGet();
                problem("bad slot_tok");
                break;
            }
        }
        if (trivial(d)) {
            trivialFrames.incrementAndGet();
            problem("trivial ask sent: family " + d.family + " C " + d.C);
        }
        if (record) {
            final String why = legal(d, d.teacher);
            if (why != null) {
                badTeachers.incrementAndGet();
                problem("illegal teacher: " + why);
            }
        }
    }

    public static boolean trivial(final RlWire.Decide d) {
        switch (d.mode) {
            case RlSchema.M_SINGLE: {
                int n = 0;
                for (int i = 0; i < d.C; i++) if (d.candKind[i] > 0) n++;
                return n <= 1;
            }
            case RlSchema.M_SUBSET:
                return d.maxPick == 0 || d.minPick >= d.C;
            case RlSchema.M_ASSIGN: {
                for (int s = 0; s < d.S; s++) {
                    int n = 0;
                    for (int i = 0; i < d.C; i++) if (d.candSlot[i] == s) n++;
                    if (n > 1) {
                        return false;
                    }
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** Null when {@code steps} is a legal decision of frame {@code d} (§2.4, §5.3). */
    public static String legal(final RlWire.Decide d, final short[] steps) {
        switch (d.mode) {
            case RlSchema.M_SINGLE:
                return steps.length == 1 && steps[0] >= 0 && steps[0] < d.C && d.candKind[steps[0]] > 0 ? null
                        : "SINGLE " + java.util.Arrays.toString(steps);
            case RlSchema.M_SUBSET: {
                if (steps.length < d.minPick || steps.length > d.maxPick) {
                    return "SUBSET count " + steps.length;
                }
                final boolean[] seen = new boolean[d.C];
                for (short s : steps) {
                    if (s < 0 || s >= d.C || seen[s] || d.candKind[s] <= 0) {
                        return "SUBSET step " + s;
                    }
                    seen[s] = true;
                }
                return null;
            }
            case RlSchema.M_ASSIGN: {
                if (steps.length != d.S) {
                    return "ASSIGN length";
                }
                for (int s = 0; s < d.S; s++) {
                    if (steps[s] < 0 || steps[s] >= d.C || d.candSlot[steps[s]] != s) {
                        return "ASSIGN slot " + s;
                    }
                }
                return null;
            }
            default:
                return "mode " + d.mode;
        }
    }

    /** Uniform legal steps under §5.3. */
    public static short[] randomSteps(final RlWire.Decide d, final Random r) {
        switch (d.mode) {
            case RlSchema.M_SINGLE: {
                final List<Integer> legal = new ArrayList<>();
                for (int i = 0; i < d.C; i++) if (d.candKind[i] > 0) legal.add(i);
                return new short[] {(short) (int) legal.get(r.nextInt(legal.size()))};
            }
            case RlSchema.M_SUBSET: {
                final List<Short> chosen = new ArrayList<>();
                final boolean[] used = new boolean[d.C];
                for (int t = 0; t < d.maxPick; t++) {
                    final List<Integer> opts = new ArrayList<>();
                    for (int i = 0; i < d.C; i++) if (!used[i] && d.candKind[i] > 0) opts.add(i);
                    final boolean stopOk = t >= d.minPick;
                    final int n = opts.size() + (stopOk ? 1 : 0);
                    if (n == 0) {
                        break;
                    }
                    final int k = r.nextInt(n);
                    if (k == opts.size()) {
                        break; // STOP
                    }
                    used[opts.get(k)] = true;
                    chosen.add((short) (int) opts.get(k));
                }
                final short[] out = new short[chosen.size()];
                for (int i = 0; i < out.length; i++) out[i] = chosen.get(i);
                return out;
            }
            case RlSchema.M_ASSIGN: {
                final short[] out = new short[d.S];
                for (int s = 0; s < d.S; s++) {
                    final List<Integer> opts = new ArrayList<>();
                    for (int i = 0; i < d.C; i++) if (d.candSlot[i] == s && d.candKind[i] > 0) opts.add(i);
                    out[s] = (short) (int) opts.get(r.nextInt(opts.size()));
                }
                return out;
            }
            default:
                throw new IllegalArgumentException("mode " + d.mode);
        }
    }

    public JsonObject stats() {
        final JsonObject o = new JsonObject();
        o.addProperty("decides", decides.get());
        o.addProperty("records", records.get());
        o.addProperty("game_ends", gameEnds.get());
        o.addProperty("trivial_frames", trivialFrames.get());
        o.addProperty("bad_frames", badFrames.get());
        o.addProperty("bad_teachers", badTeachers.get());
        o.addProperty("priv_frames", privFrames.get());
        o.addProperty("actor_errors", errors.get());
        final JsonObject bf = new JsonObject();
        final Map<Integer, AtomicLong> sorted = new TreeMap<>(byFamily);
        for (Map.Entry<Integer, AtomicLong> e : sorted.entrySet()) {
            bf.addProperty(RlSchema.familyName(e.getKey()), e.getValue().get());
        }
        o.add("frames_by_family", bf);
        final com.google.gson.JsonArray pr = new com.google.gson.JsonArray();
        synchronized (problems) {
            for (String p : problems.subList(0, Math.min(20, problems.size()))) pr.add(p);
        }
        o.add("problems", pr);
        return o;
    }

    @Override
    public void close() throws IOException {
        ss.close();
    }
}
