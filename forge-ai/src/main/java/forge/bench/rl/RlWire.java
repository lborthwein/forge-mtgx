package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Frame codec for {@code mtgx-rl-wire/1} (interfaces.md §2; lane rl-r0-b1-1005). Little-endian everywhere. A frame
 * is {@code u32 len} (the bytes after this field), {@code u16 type}, {@code u16 flags}, then the payload: canonical
 * UTF-8 JSON for the JSON messages, the packed structs of §2.3 / §2.4 for DECIDE, RECORD and DECISION.
 */
public final class RlWire {
    private RlWire() {
    }

    public static final String PROTO = "mtgx-rl-wire/1";
    /** Observation v2 frames (ICR obs-v2-1006, note N1): the same messages, DECIDE / RECORD extended. */
    public static final String PROTO_V2 = RlSchemaV2.PROTO;
    public static final int MAX_FRAME = 1 << 20;

    public static final int T_HELLO = 0x0001, T_HELLO_ACK = 0x0002, T_NEXT_GAME = 0x0010, T_GAME = 0x0011,
            T_DECIDE = 0x0020, T_DECISION = 0x0021, T_RECORD = 0x0030, T_RECORD_ACK = 0x0031, T_GAME_END = 0x0040,
            T_GAME_END_ACK = 0x0041, T_ERROR = 0x007F;

    public static final int HEADER = 64;
    public static final int DECISION_HEADER = 28;
    public static final int F_HAS_PRIV = 1, F_TRUNC_TOKENS = 2, F_TRUNC_CANDS = 4;
    /** v2: a relation or fact was dropped because its token was truncated. */
    public static final int F_DROPPED_REFS = 8;
    public static final int ST_OK = 0, ST_DELEGATE = 1, ST_ERROR = 2;

    public static String typeName(final int t) {
        switch (t) {
            case T_HELLO: return "HELLO";
            case T_HELLO_ACK: return "HELLO_ACK";
            case T_NEXT_GAME: return "NEXT_GAME";
            case T_GAME: return "GAME";
            case T_DECIDE: return "DECIDE";
            case T_DECISION: return "DECISION";
            case T_RECORD: return "RECORD";
            case T_RECORD_ACK: return "RECORD_ACK";
            case T_GAME_END: return "GAME_END";
            case T_GAME_END_ACK: return "GAME_END_ACK";
            case T_ERROR: return "ERROR";
            default: return "0x" + Integer.toHexString(t);
        }
    }

    // ------------------------------------------------------------------------------------------------ framing

    public static final class Frame {
        public final int type;
        public final int flags;
        public final byte[] payload;

        public Frame(final int type, final int flags, final byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.payload = payload == null ? new byte[0] : payload;
        }

        public JsonObject json() {
            return parseJson(payload);
        }
    }

    /** The whole frame as bytes (length prefix included). */
    public static byte[] frameBytes(final int type, final int flags, final byte[] payload) {
        final int n = payload == null ? 0 : payload.length;
        if (4 + n > MAX_FRAME) {
            throw new IllegalArgumentException("frame of " + (4 + n) + " bytes exceeds " + MAX_FRAME);
        }
        final ByteBuffer b = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4 + n).putShort((short) type).putShort((short) flags);
        if (n > 0) {
            b.put(payload);
        }
        return b.array();
    }

    public static void writeFrame(final OutputStream out, final int type, final int flags, final byte[] payload)
            throws IOException {
        out.write(frameBytes(type, flags, payload));
        out.flush();
    }

    public static Frame readFrame(final InputStream in) throws IOException {
        final byte[] h = readFully(in, 8);
        final ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        final long len = b.getInt() & 0xffffffffL;
        final int type = b.getShort() & 0xffff;
        final int flags = b.getShort() & 0xffff;
        if (len < 4 || len > MAX_FRAME) {
            throw new IOException("bad frame length " + len);
        }
        return new Frame(type, flags, readFully(in, (int) len - 4));
    }

    /** Parse one whole frame held in a byte array (golden files). */
    public static Frame parseFrame(final byte[] all) throws IOException {
        return readFrame(new java.io.ByteArrayInputStream(all));
    }

    private static byte[] readFully(final InputStream in, final int n) throws IOException {
        final byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            final int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new EOFException("connection closed after " + off + " of " + n + " bytes");
            }
            off += r;
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------------ JSON

    public static JsonObject parseJson(final byte[] payload) {
        return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Canonical JSON: UTF-8, keys sorted, separators (',', ':'), no NaN (Python json.dumps sort_keys, no ASCII escaping). */
    public static byte[] canonical(final JsonElement e) {
        final StringBuilder sb = new StringBuilder();
        canon(e, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String canonicalString(final JsonElement e) {
        final StringBuilder sb = new StringBuilder();
        canon(e, sb);
        return sb.toString();
    }

    private static void canon(final JsonElement e, final StringBuilder sb) {
        if (e == null || e.isJsonNull()) {
            sb.append("null");
        } else if (e.isJsonObject()) {
            final TreeMap<String, JsonElement> m = new TreeMap<>();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                m.put(en.getKey(), en.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                str(en.getKey(), sb);
                sb.append(':');
                canon(en.getValue(), sb);
            }
            sb.append('}');
        } else if (e.isJsonArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonElement x : e.getAsJsonArray()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                canon(x, sb);
            }
            sb.append(']');
        } else {
            final JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                sb.append(p.getAsBoolean() ? "true" : "false");
            } else if (p.isString()) {
                str(p.getAsString(), sb);
            } else {
                final Number n = p.getAsNumber();
                final double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException("canonical JSON has no NaN/Infinity");
                }
                if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
                    sb.append(n.longValue());
                } else if (d == Math.rint(d) && Math.abs(d) < 1e15 && !(n instanceof Double || n instanceof Float)) {
                    sb.append((long) d); // a parsed integral number (LazilyParsedNumber)
                } else {
                    sb.append(pyFloat(d));
                }
            }
        }
    }

    /** Python repr of a float for the common cases (no exponent between 1e-4 and 1e16). */
    static String pyFloat(final double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e16) {
            return ((long) d) + ".0";
        }
        String s = Double.toString(d);
        if (s.contains("E")) {
            final String[] p = s.split("E");
            final int exp = Integer.parseInt(p[1]);
            String m = p[0];
            if (m.endsWith(".0")) {
                m = m.substring(0, m.length() - 2);
            }
            s = m + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        }
        return s;
    }

    private static void str(final String s, final StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------------------------------------ DECIDE / RECORD

    /** One DECIDE (or RECORD, with {@link #teacher}) frame payload, field for field (§2.3). */
    public static final class Decide {
        public long gameUid;
        public int decIdx;
        public int seat;
        public int family;
        public int mode;
        public int flags;
        public int minPick;
        public int maxPick;
        public int turn;
        public int L, D, C, S, P;
        public int[] tokCard = new int[0];
        public byte[] tokZone = new byte[0];
        public float[] tokAttr = new float[0];
        public int[] deckCard = new int[0];
        public byte[] deckCnt = new byte[0];
        public float[] scal = new float[RlSchema.N_SCAL];
        public float[] ctx = new float[RlSchema.N_CTX];
        public byte[] candKind = new byte[0];
        public short[] candTok = new short[0];
        public int[] candCard = new int[0];
        public short[] candTgt = new short[0];
        public short[] candSlot = new short[0];
        public short[] candNum = new short[0];
        public byte[] candAbility = new byte[0];
        public byte[] candFlags = new byte[0];
        public short[] slotTok = new short[0];
        public int[] privCard = new int[0];
        public byte[] privZone = new byte[0];
        public byte[] privCnt = new byte[0];
        /** RECORD only: the teacher's steps in the §2.4 encoding. */
        public short[] teacher = new short[0];
        /** Observation schema version: 1 (wire/1) or 2 (wire/2: v2 widths and the arrays below). */
        public int version = 1;
        public int R, F, Dr;
        public long[] tokBits = new long[0];
        public short[] relSrc = new short[0];
        public short[] relDst = new short[0];
        public byte[] relType = new byte[0];
        public byte[] relArg = new byte[0];
        public short[] relNum = new short[0];
        public short[] factTok = new short[0];
        /** u16 on the wire. */
        public short[] factId = new short[0];
        public int[] factArg = new int[0];
        public short[] factNum = new short[0];
        public int[] restCard = new int[0];
        public byte[] restCnt = new byte[0];

        /** A v2 frame: v2 widths, empty v2 arrays. */
        public static Decide v2() {
            final Decide d = new Decide();
            d.version = 2;
            d.ctx = new float[RlSchemaV2.N_CTX];
            return d;
        }

        public int nAttr() {
            return version == 2 ? RlSchemaV2.N_ATTR : RlSchema.N_ATTR;
        }

        public int nCtx() {
            return version == 2 ? RlSchemaV2.N_CTX : RlSchema.N_CTX;
        }

        public boolean hasPriv() {
            return (flags & F_HAS_PRIV) != 0;
        }
    }

    public static int decideSize(final Decide d) {
        final int na = d.nAttr();
        int n = HEADER;
        n += d.L * 4 + d.L + d.L * na * 4;
        n += d.D * 4 + d.D;
        n += RlSchema.N_SCAL * 4 + d.nCtx() * 4;
        n += d.C * (1 + 2 + 4 + 4 + 2 + 2 + 1 + 1);
        n += d.S * 2;
        if (d.version == 2) {
            n += d.L * 8 + d.R * (2 + 2 + 1 + 1 + 2) + d.F * (2 + 2 + 4 + 2) + d.Dr * (4 + 1);
        }
        if (d.hasPriv()) {
            n += d.P * (4 + 1 + 1);
        }
        n += d.teacher.length * 2;
        return n;
    }

    public static byte[] encodeDecide(final Decide d) {
        check(d);
        final ByteBuffer b = ByteBuffer.allocate(decideSize(d)).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(d.gameUid).putInt(d.decIdx);
        b.put((byte) d.seat).put((byte) d.family).put((byte) d.mode).put((byte) d.flags);
        b.putShort((short) d.L).putShort((short) d.D).putShort((short) d.C).putShort((short) d.S)
                .putShort((short) (d.hasPriv() ? d.P : 0)).putShort((short) d.minPick).putShort((short) d.maxPick)
                .putShort((short) d.turn);
        b.putInt(d.teacher.length);
        if (d.version == 2) {
            b.putShort((short) d.R).putShort((short) d.F).putShort((short) d.Dr);
            b.put(new byte[22]);
        } else {
            b.put(new byte[28]);
        }
        for (int i = 0; i < d.L; i++) b.putInt(d.tokCard[i]);
        b.put(d.tokZone, 0, d.L);
        for (int i = 0; i < d.L * d.nAttr(); i++) b.putFloat(d.tokAttr[i]);
        for (int i = 0; i < d.D; i++) b.putInt(d.deckCard[i]);
        b.put(d.deckCnt, 0, d.D);
        for (float f : d.scal) b.putFloat(f);
        for (float f : d.ctx) b.putFloat(f);
        b.put(d.candKind, 0, d.C);
        for (int i = 0; i < d.C; i++) b.putShort(d.candTok[i]);
        for (int i = 0; i < d.C; i++) b.putInt(d.candCard[i]);
        for (int i = 0; i < 2 * d.C; i++) b.putShort(d.candTgt[i]);
        for (int i = 0; i < d.C; i++) b.putShort(d.candSlot[i]);
        for (int i = 0; i < d.C; i++) b.putShort(d.candNum[i]);
        b.put(d.candAbility, 0, d.C);
        b.put(d.candFlags, 0, d.C);
        for (int i = 0; i < d.S; i++) b.putShort(d.slotTok[i]);
        if (d.version == 2) {
            for (int i = 0; i < d.L; i++) b.putLong(d.tokBits[i]);
            for (int i = 0; i < d.R; i++) b.putShort(d.relSrc[i]);
            for (int i = 0; i < d.R; i++) b.putShort(d.relDst[i]);
            b.put(d.relType, 0, d.R);
            b.put(d.relArg, 0, d.R);
            for (int i = 0; i < d.R; i++) b.putShort(d.relNum[i]);
            for (int i = 0; i < d.F; i++) b.putShort(d.factTok[i]);
            for (int i = 0; i < d.F; i++) b.putShort(d.factId[i]);
            for (int i = 0; i < d.F; i++) b.putInt(d.factArg[i]);
            for (int i = 0; i < d.F; i++) b.putShort(d.factNum[i]);
            for (int i = 0; i < d.Dr; i++) b.putInt(d.restCard[i]);
            b.put(d.restCnt, 0, d.Dr);
        }
        if (d.hasPriv()) {
            for (int i = 0; i < d.P; i++) b.putInt(d.privCard[i]);
            b.put(d.privZone, 0, d.P);
            b.put(d.privCnt, 0, d.P);
        }
        for (short s : d.teacher) b.putShort(s);
        if (b.hasRemaining()) {
            throw new IllegalStateException("DECIDE encoder left " + b.remaining() + " bytes");
        }
        return b.array();
    }

    private static void check(final Decide d) {
        final int na = d.nAttr();
        if (d.version != 1 && d.version != 2) {
            throw new IllegalArgumentException("DECIDE version " + d.version);
        }
        if (d.version == 2 && (d.tokBits.length < d.L || d.relSrc.length < d.R || d.relDst.length < d.R
                || d.relType.length < d.R || d.relArg.length < d.R || d.relNum.length < d.R || d.factTok.length < d.F
                || d.factId.length < d.F || d.factArg.length < d.F || d.factNum.length < d.F
                || d.restCard.length < d.Dr || d.restCnt.length < d.Dr || d.R > 0xffff || d.F > 0xffff
                || d.Dr > 0xffff)) {
            throw new IllegalArgumentException("DECIDE v2 arrays shorter than their counts");
        }
        if (d.tokCard.length < d.L || d.tokZone.length < d.L || d.tokAttr.length < d.L * na
                || d.deckCard.length < d.D || d.deckCnt.length < d.D || d.scal.length != RlSchema.N_SCAL
                || d.ctx.length != d.nCtx() || d.candKind.length < d.C || d.candTok.length < d.C
                || d.candCard.length < d.C || d.candTgt.length < 2 * d.C || d.candSlot.length < d.C
                || d.candNum.length < d.C || d.candAbility.length < d.C || d.candFlags.length < d.C
                || d.slotTok.length < d.S
                || (d.hasPriv() && (d.privCard.length < d.P || d.privZone.length < d.P || d.privCnt.length < d.P))) {
            throw new IllegalArgumentException("DECIDE arrays shorter than their counts");
        }
        if (d.L > 0xffff || d.D > 0xffff || d.C > 0xffff || d.S > 0xffff || d.P > 0xffff || d.turn > 0xffff
                || d.minPick > 0xffff || d.maxPick > 0xffff || d.seat > 255 || d.family > 255 || d.mode > 255) {
            throw new IllegalArgumentException("DECIDE header field out of range");
        }
    }

    public static Decide decodeDecide(final byte[] p) {
        return decodeDecide(p, 1);
    }

    /** Decode a DECIDE / RECORD payload of observation schema {@code version} (1 = wire/1, 2 = wire/2). */
    public static Decide decodeDecide(final byte[] p, final int version) {
        final ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        final Decide d = version == 2 ? Decide.v2() : new Decide();
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("DECIDE version " + version);
        }
        d.gameUid = b.getLong();
        d.decIdx = b.getInt();
        d.seat = b.get() & 0xff;
        d.family = b.get() & 0xff;
        d.mode = b.get() & 0xff;
        d.flags = b.get() & 0xff;
        d.L = b.getShort() & 0xffff;
        d.D = b.getShort() & 0xffff;
        d.C = b.getShort() & 0xffff;
        d.S = b.getShort() & 0xffff;
        d.P = b.getShort() & 0xffff;
        d.minPick = b.getShort() & 0xffff;
        d.maxPick = b.getShort() & 0xffff;
        d.turn = b.getShort() & 0xffff;
        final long nTeacher = b.getInt() & 0xffffffffL;
        if (version == 2) {
            d.R = b.getShort() & 0xffff;
            d.F = b.getShort() & 0xffff;
            d.Dr = b.getShort() & 0xffff;
        }
        for (int i = 0; i < (version == 2 ? 22 : 28); i++) {
            if (b.get() != 0) {
                throw new IllegalArgumentException("DECIDE reserved bytes are not zero");
            }
        }
        if (!d.hasPriv() && d.P != 0) {
            throw new IllegalArgumentException("DECIDE P > 0 without has_priv");
        }
        final int na = d.nAttr();
        d.tokCard = ints(b, d.L);
        d.tokZone = bytes(b, d.L);
        d.tokAttr = floats(b, d.L * na);
        d.deckCard = ints(b, d.D);
        d.deckCnt = bytes(b, d.D);
        d.scal = floats(b, RlSchema.N_SCAL);
        d.ctx = floats(b, d.nCtx());
        d.candKind = bytes(b, d.C);
        d.candTok = shorts(b, d.C);
        d.candCard = ints(b, d.C);
        d.candTgt = shorts(b, 2 * d.C);
        d.candSlot = shorts(b, d.C);
        d.candNum = shorts(b, d.C);
        d.candAbility = bytes(b, d.C);
        d.candFlags = bytes(b, d.C);
        d.slotTok = shorts(b, d.S);
        if (version == 2) {
            d.tokBits = new long[d.L];
            for (int i = 0; i < d.L; i++) d.tokBits[i] = b.getLong();
            d.relSrc = shorts(b, d.R);
            d.relDst = shorts(b, d.R);
            d.relType = bytes(b, d.R);
            d.relArg = bytes(b, d.R);
            d.relNum = shorts(b, d.R);
            d.factTok = shorts(b, d.F);
            d.factId = shorts(b, d.F);
            d.factArg = ints(b, d.F);
            d.factNum = shorts(b, d.F);
            d.restCard = ints(b, d.Dr);
            d.restCnt = bytes(b, d.Dr);
        }
        if (d.hasPriv()) {
            d.privCard = ints(b, d.P);
            d.privZone = bytes(b, d.P);
            d.privCnt = bytes(b, d.P);
        }
        d.teacher = shorts(b, (int) nTeacher);
        if (b.hasRemaining()) {
            throw new IllegalArgumentException("DECIDE has " + b.remaining() + " trailing bytes");
        }
        return d;
    }

    private static int[] ints(final ByteBuffer b, final int n) {
        final int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = b.getInt();
        return a;
    }

    private static short[] shorts(final ByteBuffer b, final int n) {
        final short[] a = new short[n];
        for (int i = 0; i < n; i++) a[i] = b.getShort();
        return a;
    }

    private static float[] floats(final ByteBuffer b, final int n) {
        final float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = b.getFloat();
        return a;
    }

    private static byte[] bytes(final ByteBuffer b, final int n) {
        final byte[] a = new byte[n];
        b.get(a);
        return a;
    }

    // ------------------------------------------------------------------------------------------------ DECISION

    /** One DECISION payload (§2.4). */
    public static final class Decision {
        public long gameUid;
        public int decIdx;
        public int status;
        public long policyVersion;
        public float logp;
        public float valueObs;
        public short[] steps = new short[0];
    }

    public static byte[] encodeDecision(final Decision d) {
        if (d.steps.length > 255) {
            throw new IllegalArgumentException("DECISION has more than 255 steps");
        }
        final ByteBuffer b = ByteBuffer.allocate(DECISION_HEADER + 2 * d.steps.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(d.gameUid).putInt(d.decIdx).put((byte) d.status).put((byte) d.steps.length).putShort((short) 0)
                .putInt((int) d.policyVersion).putFloat(d.logp).putFloat(d.valueObs);
        for (short s : d.steps) b.putShort(s);
        return b.array();
    }

    public static Decision decodeDecision(final byte[] p) {
        if (p.length < DECISION_HEADER) {
            throw new IllegalArgumentException("DECISION shorter than its header: " + p.length);
        }
        final ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        final Decision d = new Decision();
        d.gameUid = b.getLong();
        d.decIdx = b.getInt();
        d.status = b.get() & 0xff;
        final int n = b.get() & 0xff;
        b.getShort();
        d.policyVersion = b.getInt() & 0xffffffffL;
        d.logp = b.getFloat();
        d.valueObs = b.getFloat();
        if (p.length != DECISION_HEADER + 2 * n) {
            throw new IllegalArgumentException("DECISION length " + p.length + " does not match n_steps " + n);
        }
        d.steps = shorts(b, n);
        return d;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    public static JsonObject error(final String code, final String msg) {
        final JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("msg", msg == null ? "" : msg);
        return o;
    }

    /** u64 game uid as its decimal string (the GAME message's {@code game_uid}). */
    public static long parseUid(final String s) {
        return Long.parseUnsignedLong(s);
    }

    public static String uidString(final long uid) {
        return Long.toUnsignedString(uid);
    }

    public static JsonArray intArray(final int... xs) {
        final JsonArray a = new JsonArray();
        for (int x : xs) a.add(x);
        return a;
    }

    public static List<Integer> ints(final JsonArray a) {
        final List<Integer> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsInt());
        return out;
    }

    /** Collects frames written to a buffer (tests, goldens). */
    public static final class Buffer extends ByteArrayOutputStream {
    }
}
