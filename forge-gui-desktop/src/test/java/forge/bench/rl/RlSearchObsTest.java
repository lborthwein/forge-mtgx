package forge.bench.rl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lane search-v2-1009: the S1 search path and the live policy seat in the seat's observation schema. Pure unit tests (no
 * Forge game, no card database): the spec's {@code obs} pin, the HELLO / HELLO_ACK schema checks, the leaf frame in
 * both schemas (obs-v1 byte-identical to the S1 read's), and the live seat's boot against services of either schema
 * (a mismatch never binds: the server then seats its usual opponent).
 */
public class RlSearchObsTest {
    static final String SHA = "3d9579d752d621b98ce70ea88237f9d76f60ff4d6a9dfbf6d062b619587f7da1";
    static Path indexFile;

    @BeforeClass
    public static void setUp() throws Exception {
        indexFile = Files.createTempFile("search-v2-index-", ".tsv");
        Files.write(indexFile, "<pad>\t0\n<unk>\t1\nForest\t2\nIsland\t3\n".getBytes(StandardCharsets.UTF_8));
    }

    static RlSearch.Config cfg(final String extra) {
        return RlSearch.Config.parse(JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"leaf\":\"value\","
                + "\"playout\":\"policy\"" + extra + "}").getAsJsonObject());
    }

    // ------------------------------------------------------------------------------------------------ the spec pin

    @Test
    public void specObsKeyIsAPinAndAnS1SpecIsUnchanged() {
        final RlSearch.Config off = cfg("");
        Assert.assertEquals(off.obs, 0, "absent = the seat's schema");
        Assert.assertFalse(off.toJson().has("obs"), "an S1 spec's JSON carries no obs key");
        Assert.assertEquals(cfg(",\"obs\":2").toJson().get("obs").getAsInt(), 2);
        Assert.assertEquals(cfg(",\"obs\":1").obs, 1);
        Assert.assertThrows(IllegalArgumentException.class, () -> cfg(",\"obs\":3"));
        Assert.assertThrows(IllegalArgumentException.class, () -> cfg(",\"obs\":-1"));
    }

    @Test
    public void aSeatOfAnotherSchemaRefusesAPinnedSpec() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new RlSearch(cfg(",\"obs\":2"), 1L, 2L, null, null, "jar", "t", 1));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new RlSearch(cfg(",\"obs\":1"), 1L, 2L, null, null, "jar", "t", 2));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new RlSearch(cfg(""), 1L, 2L, null, null, "jar", "t", 3));
        final RlSearch v2 = new RlSearch(cfg(""), 1L, 2L, null, null, "jar", "t", 2);
        Assert.assertEquals(v2.obsVersion(), 2);
        Assert.assertEquals(v2.summary().get("obs").getAsInt(), 2, "an obs-v2 game's summary says so");
        v2.close();
        final RlSearch v1 = new RlSearch(cfg(""), 1L, 2L, null, null, "jar", "t");
        Assert.assertEquals(v1.obsVersion(), 1, "the S1 constructor is obs-v1");
        Assert.assertFalse(v1.summary().has("obs"), "an obs-v1 game's summary is the S1 read's");
        v1.close();
        final RlSearch p2 = new RlSearch(cfg(",\"obs\":2"), 1L, 2L, null, null, "jar", "t", 2);
        p2.close();
    }

    @Test
    public void helloSchemaAndAckChecks() throws Exception {
        Assert.assertEquals(RlSearch.schemaSha(1), RlSchema.schemaSha());
        Assert.assertEquals(RlSearch.schemaSha(2), RlSchemaV2.schemaSha());
        Assert.assertNotEquals(RlSchema.schemaSha(), RlSchemaV2.schemaSha());
        final JsonObject none = new JsonObject();
        final JsonObject one = new JsonObject();
        one.addProperty("obs_schema", 1);
        final JsonObject two = new JsonObject();
        two.addProperty("obs_schema", 2);
        RlSearch.checkObs(none, 1);   // the S1 read's service says nothing: obs-v1
        RlSearch.checkObs(one, 1);
        RlSearch.checkObs(two, 2);
        Assert.assertThrows(RlClient.ServerError.class, () -> RlSearch.checkObs(two, 1));
        Assert.assertThrows(RlClient.ServerError.class, () -> RlSearch.checkObs(one, 2));
        Assert.assertThrows(RlClient.ServerError.class, () -> RlSearch.checkObs(none, 2));
    }

    // ------------------------------------------------------------------------------------------------ leaf frames

    static RlFeaturizer.Obs obs(final int version) {
        final RlFeaturizer.Obs o = new RlFeaturizer.Obs();
        o.version = version;
        final int na = version == 2 ? RlSchemaV2.N_ATTR : RlSchema.N_ATTR;
        o.ctx = new float[version == 2 ? RlSchemaV2.N_CTX : RlSchema.N_CTX];
        o.L = 3;
        o.tokCard = new int[] {2, 3, 1};
        o.tokZone = new byte[] {1, 4, (byte) (version == 2 ? RlSchemaV2.Z_O_SEEN : 9)};
        o.tokAttr = new float[3 * na];
        for (int i = 0; i < o.tokAttr.length; i++) {
            o.tokAttr[i] = (i % 7) / 7f;
        }
        o.truncated = true;
        o.D = 2;
        o.deckCard = new int[] {2, 3};
        o.deckCnt = new byte[] {4, 5};
        for (int i = 0; i < o.scal.length; i++) {
            o.scal[i] = i / 3f;
        }
        for (int i = 0; i < o.ctx.length; i++) {
            o.ctx[i] = i / 11f;
        }
        o.droppedRefs = true;
        if (version == 2) {
            o.tokBits = new long[] {1L, 1L << 40, 0L};
            o.R = 1;
            o.relSrc = new short[] {0};
            o.relDst = new short[] {1};
            o.relType = new byte[] {1};
            o.relArg = new byte[] {0};
            o.relNum = new short[] {-1};
            o.F = 2;
            o.factTok = new short[] {0, 1};
            o.factId = new short[] {1, 2};
            o.factArg = new int[] {0, 3};
            o.factNum = new short[] {-1, 2};
            o.Dr = 1;
            o.restCard = new int[] {3};
            o.restCnt = new byte[] {2};
        }
        return o;
    }

    /** The S1 read's obs-v1 leaf frame, verbatim (fork 99402d758b5, RlSearch.leafFrame): the v1 reference. */
    static RlWire.Decide s1Leaf(final RlFeaturizer.Obs o, final long uid, final int seat, final int turn) {
        final RlWire.Decide f = new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = 0;
        f.seat = seat;
        f.family = RlSchema.F_PRIORITY;
        f.mode = RlSchema.M_SINGLE;
        f.flags = o.truncated ? RlWire.F_TRUNC_TOKENS : 0;
        f.minPick = 1;
        f.maxPick = 1;
        f.turn = Math.min(0xffff, turn);
        f.L = o.L;
        f.D = o.D;
        f.C = 1;
        f.S = 0;
        f.P = 0;
        f.tokCard = o.tokCard;
        f.tokZone = o.tokZone;
        f.tokAttr = o.tokAttr;
        f.deckCard = o.deckCard;
        f.deckCnt = o.deckCnt;
        System.arraycopy(o.scal, 0, f.scal, 0, RlSchema.N_SCAL);
        System.arraycopy(o.ctx, 0, f.ctx, 0, RlSchema.N_CTX);
        f.candKind = new byte[] {(byte) RlSchema.K_PASS};
        f.candTok = new short[] {-1};
        f.candCard = new int[] {0};
        f.candTgt = new short[] {-1, -1};
        f.candSlot = new short[] {-1};
        f.candNum = new short[] {-1};
        f.candAbility = new byte[] {0};
        f.candFlags = new byte[] {0};
        f.slotTok = new short[0];
        return f;
    }

    @Test
    public void anObsV1LeafFrameIsTheS1ReadsByteForByte() {
        final RlFeaturizer.Obs o = obs(1);
        final byte[] now = RlWire.encodeDecide(RlSearch.leafFrame(o, 0x1234L, 1, 7));
        final byte[] s1 = RlWire.encodeDecide(s1Leaf(o, 0x1234L, 1, 7));
        Assert.assertTrue(Arrays.equals(now, s1), "obs-v1 leaf frame bytes changed");
        o.truncated = false;
        Assert.assertTrue(Arrays.equals(RlWire.encodeDecide(RlSearch.leafFrame(o, 9L, 0, 70000)),
                RlWire.encodeDecide(s1Leaf(o, 9L, 0, 70000))));
    }

    @Test
    public void anObsV2LeafFrameCarriesTheWholeV2Observation() {
        final RlFeaturizer.Obs o = obs(2);
        final RlWire.Decide f = RlSearch.leafFrame(o, 0x1234L, 1, 7);
        Assert.assertEquals(f.version, 2);
        final RlWire.Decide d = RlWire.decodeDecide(RlWire.encodeDecide(f), 2);
        Assert.assertEquals(d.family, RlSchema.F_PRIORITY);
        Assert.assertEquals(d.mode, RlSchema.M_SINGLE);
        Assert.assertEquals(d.C, 1);
        Assert.assertEquals(d.candKind[0], (byte) RlSchema.K_PASS);
        Assert.assertEquals(d.flags, RlWire.F_TRUNC_TOKENS | RlWire.F_DROPPED_REFS, "the seat's frame flags (no priv)");
        Assert.assertEquals(d.L, o.L);
        Assert.assertEquals(Arrays.copyOf(d.tokCard, d.L), o.tokCard);
        Assert.assertEquals(Arrays.copyOf(d.tokZone, d.L), o.tokZone);
        Assert.assertEquals(Arrays.copyOf(d.tokAttr, d.L * RlSchemaV2.N_ATTR), o.tokAttr);
        Assert.assertEquals(Arrays.copyOf(d.deckCard, d.D), o.deckCard);
        Assert.assertEquals(Arrays.copyOf(d.deckCnt, d.D), o.deckCnt);
        Assert.assertEquals(d.scal, o.scal);
        Assert.assertEquals(d.ctx, o.ctx);
        Assert.assertEquals(d.ctx.length, RlSchemaV2.N_CTX);
        Assert.assertEquals(Arrays.copyOf(d.tokBits, d.L), o.tokBits);
        Assert.assertEquals(d.R, 1);
        Assert.assertEquals(Arrays.copyOf(d.relSrc, d.R), o.relSrc);
        Assert.assertEquals(Arrays.copyOf(d.relDst, d.R), o.relDst);
        Assert.assertEquals(Arrays.copyOf(d.relType, d.R), o.relType);
        Assert.assertEquals(Arrays.copyOf(d.relNum, d.R), o.relNum);
        Assert.assertEquals(d.F, 2);
        Assert.assertEquals(Arrays.copyOf(d.factTok, d.F), o.factTok);
        Assert.assertEquals(Arrays.copyOf(d.factId, d.F), o.factId);
        Assert.assertEquals(Arrays.copyOf(d.factArg, d.F), o.factArg);
        Assert.assertEquals(Arrays.copyOf(d.factNum, d.F), o.factNum);
        Assert.assertEquals(d.Dr, 1);
        Assert.assertEquals(Arrays.copyOf(d.restCard, d.Dr), o.restCard);
        Assert.assertEquals(Arrays.copyOf(d.restCnt, d.Dr), o.restCnt);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.decodeDecide(RlWire.encodeDecide(f), 1));
    }

    // ------------------------------------------------------------------------------------------------ live seat boot

    static String spec(final String server, final String extra) {
        return "server=" + server + ",policySha=" + SHA + ",cardIndex=" + indexFile + (extra.isEmpty() ? "" : "," + extra);
    }

    @Test
    public void liveSpecObsKey() {
        final RlLiveSeat.Spec v1 = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", ""));
        Assert.assertEquals(v1.obs(), 1, "the live seat's default is obs-v1 (the S-c / R_8 live spec)");
        Assert.assertFalse(v1.search.toJson().has("obs"), "the obs-v1 bound line's search JSON is unchanged");
        final RlLiveSeat.Spec v2 = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "obs=2"));
        Assert.assertEquals(v2.obs(), 2);
        Assert.assertEquals(v2.search.toJson().get("obs").getAsInt(), 2);
        Assert.assertEquals(RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "obs=1")).obs(), 1);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "obs=3")));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "obs=v2")));
    }

    @Test
    public void liveBootBindsOnlyAServiceOfTheSpecsSchema() throws Exception {
        try (FakeSearchService v2 = new FakeSearchService(SHA, 2)) {
            final RlLiveSeat s = RlLiveSeat.connect(spec(v2.address(), "obs=2"), "t");
            Assert.assertNotNull(s, "obs-v2 spec, obs-v2 service");
            s.finish();
            Assert.assertNull(RlLiveSeat.connect(spec(v2.address(), ""), "t"), "an obs-v1 spec against an obs-v2 service");
            Assert.assertEquals(v2.refusedHellos.get(), 1L, "the service refused the obs-v1 HELLO");
            v2.ackObs = 1;
            Assert.assertNull(RlLiveSeat.connect(spec(v2.address(), "obs=2"), "t"), "an ACK naming obs-v1");
            v2.ackObs = -1;
            Assert.assertNull(RlLiveSeat.connect(spec(v2.address(), "obs=2"), "t"), "an ACK that does not name its schema");
        }
        try (FakeSearchService v1 = new FakeSearchService(SHA, 1)) {
            Assert.assertNull(RlLiveSeat.connect(spec(v1.address(), "obs=2"), "t"), "an obs-v2 spec against an obs-v1 service");
            Assert.assertEquals(v1.refusedHellos.get(), 1L);
            final RlLiveSeat s = RlLiveSeat.connect(spec(v1.address(), ""), "t");
            Assert.assertNotNull(s, "the S-c live spec (obs-v1) still binds its service");
            s.finish();
            v1.ackObs = -1;   // the S1 read's service: no obs_schema in its ACK
            final RlLiveSeat old = RlLiveSeat.connect(spec(v1.address(), ""), "t");
            Assert.assertNotNull(old, "an obs-v1 seat binds a service that does not name its schema (S1's)");
            old.finish();
        }
        try (FakeSearchService other = new FakeSearchService("0".repeat(64), 2)) {
            Assert.assertNull(RlLiveSeat.connect(spec(other.address(), "obs=2"), "t"), "another checkpoint");
        }
    }
}
