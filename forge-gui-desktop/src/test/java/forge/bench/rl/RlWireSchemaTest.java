package forge.bench.rl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Brief B1 tests 1 (schema), 2 (wire codec, every message type) and 3 (golden frames decode field by field). No Forge
 * boot. Test resources: {@code -Drl.testres=<dir>} or {@code ../forge-ai/src/test/resources/rl} (mvn cwd).
 */
public class RlWireSchemaTest {

    public static Path res() {
        final String p = System.getProperty("rl.testres", "../forge-ai/src/test/resources/rl");
        return Paths.get(p);
    }

    // ------------------------------------------------------------------------------------------------ 1. schema

    @Test
    public void schemaShaIsTheVendoredJson() throws Exception {
        final byte[] b = Files.readAllBytes(res().resolve("obs-v1.json"));
        Assert.assertEquals(CardIndex.sha256(b), RlSchema.schemaSha());
        Assert.assertEquals(RlSchema.schemaSha(), "24eee76e8ac4b1a275f66517f3cd21b3ff86703f133901a8828d216c800766ab");
    }

    @Test
    public void constantsMirrorTheJson() throws Exception {
        final JsonObject s = JsonParser.parseString(new String(Files.readAllBytes(res().resolve("obs-v1.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        Assert.assertEquals(s.get("schema").getAsString(), RlSchema.SCHEMA);
        Assert.assertEquals(s.get("L_MAX").getAsInt(), RlSchema.L_MAX);
        Assert.assertEquals(s.get("D_MAX").getAsInt(), RlSchema.D_MAX);
        Assert.assertEquals(s.get("C_MAX").getAsInt(), RlSchema.C_MAX);
        Assert.assertEquals(s.get("S_MAX").getAsInt(), RlSchema.S_MAX);
        Assert.assertEquals(s.get("P_MAX").getAsInt(), RlSchema.P_MAX);
        Assert.assertEquals(s.get("X_MAX").getAsInt(), RlSchema.X_MAX);
        final JsonArray zones = s.getAsJsonArray("zones");
        Assert.assertEquals(zones.size(), RlSchema.ZONES.size());
        for (int i = 0; i < zones.size(); i++) {
            Assert.assertEquals(zones.get(i).getAsJsonObject().get("id").getAsInt(), i);
            Assert.assertEquals(zones.get(i).getAsJsonObject().get("name").getAsString(), RlSchema.ZONES.get(i));
        }
        Assert.assertEquals(strings(s.getAsJsonArray("attrs")), RlSchema.ATTRS);
        Assert.assertEquals(RlSchema.ATTRS.size(), RlSchema.N_ATTR);
        Assert.assertEquals(strings(s.getAsJsonArray("scal")), RlSchema.SCAL);
        Assert.assertEquals(RlSchema.SCAL.size(), RlSchema.N_SCAL);
        Assert.assertEquals(strings(s.getAsJsonArray("ctx")), RlSchema.CTX);
        Assert.assertEquals(RlSchema.CTX.size(), RlSchema.N_CTX);
        Assert.assertEquals(strings(s.getAsJsonArray("kinds")), RlSchema.KINDS);
        Assert.assertEquals(strings(s.getAsJsonArray("modes")), RlSchema.MODES);
        final JsonArray fam = s.getAsJsonArray("families");
        Assert.assertEquals(fam.size(), RlSchema.N_FAMILIES);
        for (JsonElement e : fam) {
            final JsonObject f = e.getAsJsonObject();
            final int id = f.get("id").getAsInt();
            Assert.assertEquals(f.get("name").getAsString(), RlSchema.familyName(id));
            Assert.assertEquals("A".equals(f.get("phase").getAsString()), RlSchema.isPhaseA(id), "phase of " + id);
        }
        // named index constants agree with the lists
        Assert.assertEquals(RlSchema.ATTRS.get(RlSchema.A_STACK_POS), "stack_pos/10");
        Assert.assertEquals(RlSchema.ATTRS.get(RlSchema.A_CONTROLLER_DIFFERS_OWNER), "controller_differs_owner");
        Assert.assertEquals(RlSchema.CTX.get(RlSchema.C_PRIORITY), "i_have_priority");
        Assert.assertEquals(RlSchema.CTX.get(RlSchema.C_POOL0), "pool_W/5");
        Assert.assertEquals(RlSchema.CTX.get(RlSchema.C_INITIATIVE_O), "initiative_opp");
        Assert.assertEquals(RlSchema.KINDS.get(RlSchema.K_ATTACKER), "ATTACKER");
        Assert.assertEquals(RlSchema.ZONES.get(RlSchema.Z_PRIV_O_LIB), "priv_o_lib");
        Assert.assertEquals(RlSchema.ZONES.get(RlSchema.Z_COMMAND), "command");
    }

    static List<String> strings(final JsonArray a) {
        final List<String> l = new ArrayList<>();
        for (JsonElement e : a) l.add(e.getAsString());
        return l;
    }

    // ------------------------------------------------------------------------------------------------ 2. wire

    static RlWire.Decide sampleDecide(final Random r, final boolean priv, final int nTeacher) {
        final RlWire.Decide d = new RlWire.Decide();
        d.gameUid = 0xF000000000000123L; // above 2^63: unsigned on the wire
        d.decIdx = r.nextInt(3000);
        d.seat = 1;
        d.family = RlSchema.F_ATTACK;
        d.mode = RlSchema.M_ASSIGN;
        d.L = 1 + r.nextInt(96);
        d.D = 1 + r.nextInt(48);
        d.S = 3;
        d.C = d.S * 2;
        d.flags = priv ? RlWire.F_HAS_PRIV : 0;
        d.P = priv ? 1 + r.nextInt(100) : 0;
        d.minPick = d.S;
        d.maxPick = d.S;
        d.turn = 17;
        d.tokCard = new int[d.L];
        d.tokZone = new byte[d.L];
        d.tokAttr = new float[d.L * RlSchema.N_ATTR];
        for (int i = 0; i < d.L; i++) {
            d.tokCard[i] = r.nextInt(36589);
            d.tokZone[i] = (byte) (1 + r.nextInt(21));
        }
        for (int i = 0; i < d.tokAttr.length; i++) d.tokAttr[i] = r.nextFloat();
        d.deckCard = new int[d.D];
        d.deckCnt = new byte[d.D];
        for (int i = 0; i < d.D; i++) {
            d.deckCard[i] = r.nextInt(36589);
            d.deckCnt[i] = (byte) (1 + r.nextInt(4));
        }
        for (int i = 0; i < RlSchema.N_SCAL; i++) d.scal[i] = r.nextFloat();
        for (int i = 0; i < RlSchema.N_CTX; i++) d.ctx[i] = r.nextFloat();
        d.candKind = new byte[d.C];
        d.candTok = new short[d.C];
        d.candCard = new int[d.C];
        d.candTgt = new short[2 * d.C];
        d.candSlot = new short[d.C];
        d.candNum = new short[d.C];
        d.candAbility = new byte[d.C];
        d.candFlags = new byte[d.C];
        for (int i = 0; i < d.C; i++) {
            d.candKind[i] = (byte) (i % 2 == 0 ? RlSchema.K_NONE : RlSchema.K_DEFENDER);
            d.candTok[i] = (short) (r.nextInt(d.L + 1) - 1);
            d.candCard[i] = r.nextInt(36589);
            d.candTgt[2 * i] = (short) -3;
            d.candTgt[2 * i + 1] = (short) -1;
            d.candSlot[i] = (short) (i / 2);
            d.candNum[i] = -1;
            d.candAbility[i] = (byte) r.nextInt(16);
            d.candFlags[i] = (byte) r.nextInt(16);
        }
        d.slotTok = new short[] {0, (short) (d.L - 1), -1};
        if (priv) {
            d.privCard = new int[d.P];
            d.privZone = new byte[d.P];
            d.privCnt = new byte[d.P];
            for (int i = 0; i < d.P; i++) {
                d.privCard[i] = r.nextInt(36589);
                d.privZone[i] = (byte) (22 + r.nextInt(3));
                d.privCnt[i] = (byte) (1 + r.nextInt(4));
            }
        }
        d.teacher = new short[nTeacher];
        for (int i = 0; i < nTeacher; i++) d.teacher[i] = (short) (2 * i);
        return d;
    }

    static void assertSame(final RlWire.Decide a, final RlWire.Decide b) {
        Assert.assertEquals(b.gameUid, a.gameUid);
        Assert.assertEquals(b.decIdx, a.decIdx);
        Assert.assertEquals(b.seat, a.seat);
        Assert.assertEquals(b.family, a.family);
        Assert.assertEquals(b.mode, a.mode);
        Assert.assertEquals(b.flags, a.flags);
        Assert.assertEquals(b.L, a.L);
        Assert.assertEquals(b.D, a.D);
        Assert.assertEquals(b.C, a.C);
        Assert.assertEquals(b.S, a.S);
        Assert.assertEquals(b.P, a.hasPriv() ? a.P : 0);
        Assert.assertEquals(b.minPick, a.minPick);
        Assert.assertEquals(b.maxPick, a.maxPick);
        Assert.assertEquals(b.turn, a.turn);
        Assert.assertEquals(b.tokCard, a.tokCard);
        Assert.assertEquals(b.tokZone, a.tokZone);
        Assert.assertEquals(b.tokAttr, a.tokAttr);
        Assert.assertEquals(b.deckCard, a.deckCard);
        Assert.assertEquals(b.deckCnt, a.deckCnt);
        Assert.assertEquals(b.scal, a.scal);
        Assert.assertEquals(b.ctx, a.ctx);
        Assert.assertEquals(b.candKind, a.candKind);
        Assert.assertEquals(b.candTok, a.candTok);
        Assert.assertEquals(b.candCard, a.candCard);
        Assert.assertEquals(b.candTgt, a.candTgt);
        Assert.assertEquals(b.candSlot, a.candSlot);
        Assert.assertEquals(b.candNum, a.candNum);
        Assert.assertEquals(b.candAbility, a.candAbility);
        Assert.assertEquals(b.candFlags, a.candFlags);
        Assert.assertEquals(b.slotTok, a.slotTok);
        if (a.hasPriv()) {
            Assert.assertEquals(b.privCard, a.privCard);
            Assert.assertEquals(b.privZone, a.privZone);
            Assert.assertEquals(b.privCnt, a.privCnt);
        }
        Assert.assertEquals(b.teacher, a.teacher);
    }

    @Test
    public void decideAndRecordRoundTrip() throws Exception {
        final Random r = new Random(1005);
        for (int k = 0; k < 50; k++) {
            final boolean priv = k % 2 == 0;
            final int nt = k % 3 == 0 ? 3 : 0;
            final RlWire.Decide d = sampleDecide(r, priv, nt);
            final byte[] p = RlWire.encodeDecide(d);
            Assert.assertEquals(p.length, RlWire.decideSize(d));
            final int type = nt > 0 ? RlWire.T_RECORD : RlWire.T_DECIDE;
            final RlWire.Frame f = RlWire.parseFrame(RlWire.frameBytes(type, 0, p));
            Assert.assertEquals(f.type, type);
            assertSame(d, RlWire.decodeDecide(f.payload));
        }
        // 64-byte header with the documented offsets
        final RlWire.Decide d = sampleDecide(r, true, 2);
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(RlWire.encodeDecide(d))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(b.getLong(0), d.gameUid);
        Assert.assertEquals(b.getInt(8), d.decIdx);
        Assert.assertEquals(b.get(12), (byte) d.seat);
        Assert.assertEquals(b.get(13), (byte) d.family);
        Assert.assertEquals(b.get(14), (byte) d.mode);
        Assert.assertEquals(b.get(15), (byte) d.flags);
        Assert.assertEquals(b.getShort(16), (short) d.L);
        Assert.assertEquals(b.getShort(20), (short) d.C);
        Assert.assertEquals(b.getShort(24), (short) d.P);
        Assert.assertEquals(b.getShort(30), (short) d.turn);
        Assert.assertEquals(b.getInt(32), 2);
        for (int i = 36; i < 64; i++) Assert.assertEquals(b.get(i), 0);
        Assert.assertEquals(b.getInt(64), d.tokCard[0]); // arrays start right after the header
    }

    @Test
    public void malformedDecideIsRefused() {
        final RlWire.Decide d = sampleDecide(new Random(7), false, 0);
        final byte[] p = RlWire.encodeDecide(d);
        final byte[] longer = java.util.Arrays.copyOf(p, p.length + 1);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.decodeDecide(longer));
        final byte[] reserved = p.clone();
        reserved[40] = 1;
        Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.decodeDecide(reserved));
        final byte[] privNoFlag = p.clone();
        privNoFlag[24] = 1; // P = 1 without has_priv
        Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.decodeDecide(privNoFlag));
    }

    @Test
    public void decisionRoundTrip() throws Exception {
        final RlWire.Decision d = new RlWire.Decision();
        d.gameUid = 0xFFFFFFFFFFFFFFFEL;
        d.decIdx = 2999;
        d.status = RlWire.ST_OK;
        d.policyVersion = 0xFFFFFFF0L;
        d.logp = -3.25f;
        d.valueObs = 0.5f;
        d.steps = new short[] {0, 4, 6};
        final byte[] p = RlWire.encodeDecision(d);
        Assert.assertEquals(p.length, 28 + 6);
        final RlWire.Decision e = RlWire.decodeDecision(RlWire.parseFrame(RlWire.frameBytes(RlWire.T_DECISION, 0, p))
                .payload);
        Assert.assertEquals(e.gameUid, d.gameUid);
        Assert.assertEquals(e.decIdx, d.decIdx);
        Assert.assertEquals(e.status, d.status);
        Assert.assertEquals(e.policyVersion, d.policyVersion);
        Assert.assertEquals(e.logp, d.logp);
        Assert.assertEquals(e.valueObs, d.valueObs);
        Assert.assertEquals(e.steps, d.steps);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.decodeDecision(java.util.Arrays.copyOf(p,
                p.length - 2)));
    }

    @Test
    public void jsonMessagesRoundTripCanonically() throws Exception {
        final String[] msgs = {
            "{\"proto\":\"mtgx-rl-wire/1\",\"schema_sha\":\"24ee\",\"card_index_sha\":\"7e3e\",\"jar_sha\":\"x\","
                + "\"actor_id\":\"a03\",\"thread\":5,\"mode\":\"train\",\"pid\":12345}",
            "{\"ok\":true,\"server_id\":\"s1\",\"run_id\":\"r\",\"config_sha\":\"c\"}",
            "{\"actor_id\":\"a03\",\"thread\":5}",
            "{\"game_uid\":\"18446744073709551615\",\"seed\":719780123,\"decks\":[\"/a\",\"/b\"],\"deck_sha\":[\"1\",\"2\"],"
                + "\"controllers\":[\"rl:M\",\"forge\"],\"priv\":true}",
            "{\"wait_ms\":500}",
            "{\"stop\":true}",
            "{\"game_uid\":\"7\",\"result\":[1,-1],\"void\":null,\"reason\":\"life\",\"turns\":17,\"decisions\":[71,66],"
                + "\"overridden\":[12],\"cap_hits\":0,\"digest\":\"abc\",\"cpu_ms\":1234,\"wall_ms\":2100}",
            "{\"code\":\"deck_guard\",\"msg\":\"refused \\\"x\\\"\\n\"}",
        };
        final int[] types = {RlWire.T_HELLO, RlWire.T_HELLO_ACK, RlWire.T_NEXT_GAME, RlWire.T_GAME, RlWire.T_GAME,
                RlWire.T_GAME, RlWire.T_GAME_END, RlWire.T_ERROR};
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int i = 0; i < msgs.length; i++) {
            RlWire.writeFrame(bo, types[i], 0, RlWire.canonical(JsonParser.parseString(msgs[i])));
        }
        RlWire.writeFrame(bo, RlWire.T_RECORD_ACK, 0, new byte[0]);
        RlWire.writeFrame(bo, RlWire.T_GAME_END_ACK, 0, new byte[0]);
        final ByteArrayInputStream in = new ByteArrayInputStream(bo.toByteArray());
        for (int i = 0; i < msgs.length; i++) {
            final RlWire.Frame f = RlWire.readFrame(in);
            Assert.assertEquals(f.type, types[i]);
            Assert.assertEquals(f.json(), JsonParser.parseString(msgs[i]));
            // canonical: sorted keys, no spaces, so re-encoding is the identity
            Assert.assertEquals(RlWire.canonical(f.json()), f.payload);
        }
        Assert.assertEquals(RlWire.readFrame(in).type, RlWire.T_RECORD_ACK);
        Assert.assertEquals(RlWire.readFrame(in).type, RlWire.T_GAME_END_ACK);
        Assert.assertEquals(RlWire.canonicalString(JsonParser.parseString("{\"b\":1,\"a\":[true,null,\"é\\u0001\"]}")),
                "{\"a\":[true,null,\"é\\u0001\"],\"b\":1}");
    }

    @Test
    public void oversizedFramesAreRefused() {
        Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.frameBytes(RlWire.T_DECIDE, 0,
                new byte[RlWire.MAX_FRAME]));
        final byte[] bad = RlWire.frameBytes(RlWire.T_DECIDE, 0, new byte[4]);
        bad[0] = 0;
        bad[1] = 0;
        bad[2] = 0x20; // 2 MiB
        Assert.assertThrows(java.io.IOException.class, () -> RlWire.parseFrame(bad));
    }

    // ------------------------------------------------------------------------------------------------ card index

    @Test
    public void cardIndexLookupRules() {
        final String tsv = "<pad>\t0\n<unk>\t1\nLightning Bolt\t2\nSoldier\t3\nFire // Ice\t4\nBrazen Borrower\t5\n"
                + "Lightning Bolt\t9\n";
        final CardIndex ix = CardIndex.of(tsv.getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(ix.lookup("Lightning Bolt"), 2); // duplicates keep the first index
        Assert.assertEquals(ix.lookup("Soldier Token"), 3);   // trailing " Token" stripped
        Assert.assertEquals(ix.lookup("Fire // Ice"), 4);     // exact split name
        Assert.assertEquals(ix.lookup("Brazen Borrower // Petty Theft"), 5); // front face of the asked name
        Assert.assertEquals(ix.lookup("Nope"), 1);
        Assert.assertEquals(ix.unknownLookups(), 1);
        Assert.assertTrue(ix.unknownNames().contains("Nope"));
        Assert.assertEquals(ix.size(), 7);
        Assert.assertEquals(ix.sha(), CardIndex.sha256(tsv.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------------------------------------ 3. goldens

    @Test
    public void goldenFramesDecodeFieldByField() throws Exception {
        final File dir = res().resolve("golden").toFile();
        final File[] bins = dir.listFiles((d, n) -> n.startsWith("decide-") && n.endsWith(".bin"));
        if (bins == null || bins.length == 0) {
            throw new SkipException("no golden frames under " + dir);
        }
        int n = 0;
        for (File b : bins) {
            final RlWire.Frame f = RlWire.parseFrame(Files.readAllBytes(b.toPath()));
            Assert.assertEquals(f.type, RlWire.T_DECIDE, b.getName());
            final RlWire.Decide d = RlWire.decodeDecide(f.payload);
            final JsonObject g = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(b.getPath()
                    .replace(".bin", ".json"))), StandardCharsets.UTF_8)).getAsJsonObject();
            final JsonObject want = RlGoldens.describe(d, null, null, null, null);
            Assert.assertEquals(want.get("header"), g.get("header"), b.getName());
            Assert.assertEquals(want.get("arrays"), g.get("arrays"), b.getName());
            // the answer contract reproduces the example answer
            final JsonArray st = g.getAsJsonArray("example_steps");
            final short[] steps = new short[st.size()];
            for (int i = 0; i < steps.length; i++) steps[i] = st.get(i).getAsShort();
            Assert.assertNull(FakeRlServer.legal(d, steps), b.getName());
            final JsonObject got = RlGoldens.applyContract(g, steps);
            final JsonObject ex = g.getAsJsonObject("example_answer");
            final JsonObject contract = g.getAsJsonObject("answer_contract");
            if ("two_frame".equals(contract.get("shape").getAsString())) {
                // SCRY/SURVEIL first frame (lane rl-r0-b4-1006): it fixes which cards stay on top and the rest in
                // order; their order on top is the second frame's
                final String rk = contract.get("rest_key").getAsString();
                Assert.assertEquals(got.get(rk), ex.get(rk), b.getName());
                Assert.assertEquals(sorted(got.getAsJsonArray("top")), sorted(ex.getAsJsonArray("top")), b.getName());
            } else if (RlSchema.isTwoFrame(d.family)) {
                Assert.assertEquals(got.get("choices"), ex.get("top"), b.getName()); // the PERMUTE frame: top order
            } else {
                Assert.assertEquals(got, ex, b.getName());
            }
            n++;
        }
        Assert.assertTrue(n >= 7, "at least one golden per Phase A family, got " + n);
    }

    private static java.util.List<String> sorted(final JsonArray a) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement e : a) out.add(e.toString());
        java.util.Collections.sort(out);
        return out;
    }
}
