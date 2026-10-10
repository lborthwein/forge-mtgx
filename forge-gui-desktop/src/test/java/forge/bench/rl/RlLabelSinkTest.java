package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lane r3-distill-1010 (R3-CM D2): the label sink's record format and file layout (mtgx-rl-labelsink/1, read by mtgx
 * tools/ml/rl/labels.py), the spec key that switches it on without changing the teacher's spec, and the summary keys
 * that appear only when on. Pure unit tests (no Forge game, no card database, no service).
 *
 * <p>With {@code -Dmtgx.labelsink.golden=<file>} the test also writes a golden sink file (two games: a root and a choice
 * record, then a root record) that mtgx's tests/test_distill.py reads back.
 */
public class RlLabelSinkTest {

    static RlSearch.Config cfg(final String extra) {
        return RlSearch.Config.parse(JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"leaf\":\"value\","
                + "\"playout\":\"policy\",\"choices\":2" + extra + "}").getAsJsonObject());
    }

    /** A tiny wire/1 PRIORITY frame (two candidates: pass and one cast). */
    static RlWire.Decide frame(final long uid, final int dec) {
        final RlWire.Decide f = new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = dec;
        f.seat = 1;
        f.family = RlSchema.F_PRIORITY;
        f.mode = RlSchema.M_SINGLE;
        f.minPick = 1;
        f.maxPick = 1;
        f.turn = 3;
        f.L = 1;
        f.D = 1;
        f.C = 2;
        f.tokCard = new int[] {2};
        f.tokZone = new byte[] {1};
        f.tokAttr = new float[RlSchema.N_ATTR];
        f.deckCard = new int[] {3};
        f.deckCnt = new byte[] {1};
        f.candKind = new byte[] {(byte) RlSchema.K_PASS, 2};
        f.candTok = new short[] {-1, 0};
        f.candCard = new int[] {0, 2};
        f.candTgt = new short[] {-1, -1, -1, -1};
        f.candSlot = new short[] {-1, -1};
        f.candNum = new short[] {-1, -1};
        f.candAbility = new byte[] {0, 0};
        f.candFlags = new byte[] {0, 0};
        return f;
    }

    static byte[] gunzipAll(final Path p) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            return in.readAllBytes();   // java.util.zip reads concatenated members
        }
    }

    @Test
    public void specKeyIsHarnessOnlyAndOffByDefault() {
        final RlSearch.Config off = cfg("");
        Assert.assertNull(off.labelSink);
        final RlSearch.Config on = cfg(",\"labelSink\":\"/tmp/x/labels-{actor}-{pid}.mxl.gz\"");
        Assert.assertEquals(on.labelSink, "/tmp/x/labels-{actor}-{pid}.mxl.gz");
        Assert.assertEquals(RlWire.canonicalString(on.toJson()), RlWire.canonicalString(off.toJson()),
                "the teacher's spec JSON is the same with the sink on");
        Assert.assertNull(cfg(",\"labelSink\":null").labelSink);
        Assert.assertThrows(IllegalArgumentException.class, () -> cfg(",\"labelSinc\":\"x\""));
    }

    @Test
    public void recordLayout() {
        final JsonObject h = new JsonObject();
        h.addProperty("kind", "root");
        h.addProperty("C", 2);
        final byte[] payload = RlWire.encodeDecide(frame(7L, 4));
        final byte[] r = RlSearch.labelRecord(h, payload);
        final ByteBuffer b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN);
        final byte[] magic = new byte[4];
        b.get(magic);
        Assert.assertEquals(new String(magic, StandardCharsets.US_ASCII), "MXL1");
        final int hl = b.getInt();
        final byte[] hb = new byte[hl];
        b.get(hb);
        Assert.assertEquals(new String(hb, StandardCharsets.UTF_8), RlWire.canonicalString(h));
        final int pl = b.getInt();
        Assert.assertEquals(pl, payload.length);
        final byte[] pb = new byte[pl];
        b.get(pb);
        Assert.assertTrue(Arrays.equals(pb, payload));
        Assert.assertFalse(b.hasRemaining());
    }

    @Test
    public void oneGzipMemberPerGameAppended() throws Exception {
        final Path d = Files.createTempDirectory("labelsink-");
        final Path f = d.resolve("sub").resolve("labels-a00-1.mxl.gz");
        final JsonObject h1 = new JsonObject();
        h1.addProperty("i", 1);
        final JsonObject h2 = new JsonObject();
        h2.addProperty("i", 2);
        final byte[] r1 = RlSearch.labelRecord(h1, new byte[] {1, 2, 3});
        final byte[] r2 = RlSearch.labelRecord(h2, new byte[0]);
        RlSearch.appendGame(f.toString(), List.of(r1, r2));
        final long size1 = Files.size(f);
        RlSearch.appendGame(f.toString(), List.of(r1));
        final byte[] all = gunzipAll(f);
        final ByteArrayOutputStream want = new ByteArrayOutputStream();
        want.write(r1);
        want.write(r2);
        want.write(r1);
        Assert.assertTrue(Arrays.equals(all, want.toByteArray()));
        // two members: the second starts at size1 with the gzip magic
        final byte[] raw = Files.readAllBytes(f);
        Assert.assertEquals(raw[(int) size1] & 0xff, 0x1f);
        Assert.assertEquals(raw[(int) size1 + 1] & 0xff, 0x8b);
    }

    @Test
    public void summaryKeysOnlyWhenOn() {
        final RlSearch plain = new RlSearch(cfg(""), 1L, 2L, null, null, "jar", "t", 1);
        final JsonObject s0 = plain.summary();
        Assert.assertFalse(s0.has("seat"));
        Assert.assertFalse(s0.has("labels"));
        // the hook is a no-op without the sink (no service is contacted)
        plain.scheduledAsk(null, null, null, null, "a", 0, 1, 0);
        plain.flushLog();
        plain.close();
        final RlSearch on = new RlSearch(cfg(",\"labelSink\":\"/nonexistent-dir-never-written/{actor}.mxl.gz\""), 1L, 2L,
                null, null, "jar", "t", 1);
        on.searchSeat = 1;
        final JsonObject s1 = on.summary();
        Assert.assertEquals(s1.get("seat").getAsInt(), 1);
        Assert.assertEquals(s1.getAsJsonObject("labels").get("roots").getAsInt(), 0);
        on.flushLog();      // nothing recorded: nothing written
        on.close();
    }

    @Test
    public void goldenForMtgx() throws Exception {
        final String out = System.getProperty("mtgx.labelsink.golden");
        if (out == null) {
            return;
        }
        final Path p = Path.of(out);
        Files.deleteIfExists(p);
        final JsonObject root = JsonParser.parseString("{\"kind\":\"root\",\"game_uid\":\"7\",\"seed\":11,\"seat\":1,"
                + "\"dec_idx\":4,\"turn\":3,\"obs\":1,\"policy_sha\":\"p\",\"policy_version\":5,\"jar_sha\":\"j\","
                + "\"actor\":\"a00\",\"C\":2,\"prior\":[0.25,0.75],\"cand_entry\":[0,1],\"default_cand\":1,"
                + "\"default_entry\":1,\"searched\":[{\"entry\":1,\"macro\":-1,\"ev\":0.4,\"values\":[0.4,null]},"
                + "{\"entry\":0,\"macro\":-1,\"ev\":0.6,\"values\":[0.6,0.6]},{\"entry\":1,\"macro\":0,\"ev\":0.9,"
                + "\"values\":[0.9,0.9]}],\"macros\":[{\"base_entry\":1,\"ask\":\"TARGETS|m|X\",\"ordinal\":0,"
                + "\"exact\":\"e\",\"loose\":\"l\",\"own_loose\":\"o\",\"joint_prior\":0.1}],\"chosen_cand\":1,"
                + "\"chosen_entry\":1,\"chosen_macro\":0,\"departed\":true,\"outcome\":\"departed\",\"failed\":0,"
                + "\"leaf_fallback\":false}").getAsJsonObject();
        final RlWire.Decide cf = frame(7L, 5);
        cf.family = RlSchema.F_TARGETS;
        final JsonObject choice = JsonParser.parseString("{\"kind\":\"choice\",\"game_uid\":\"7\",\"seed\":11,"
                + "\"seat\":1,\"dec_idx\":5,\"turn\":3,\"obs\":1,\"policy_sha\":\"p\",\"policy_version\":5,"
                + "\"jar_sha\":\"j\",\"actor\":\"a00\",\"family\":" + RlSchema.F_TARGETS + ",\"ask\":\"TARGETS|m|X\","
                + "\"ordinal\":0,\"root_dec_idx\":4,\"macro\":0,\"C\":2,\"prior\":[0.5,0.5],\"policy_answer\":0,"
                + "\"scheduled\":1,\"match\":[-2,0]}").getAsJsonObject();
        RlSearch.appendGame(out, List.of(RlSearch.labelRecord(root, RlWire.encodeDecide(frame(7L, 4))),
                RlSearch.labelRecord(choice, RlWire.encodeDecide(cf))));
        final JsonObject root2 = root.deepCopy();
        root2.addProperty("game_uid", "8");
        RlSearch.appendGame(out, List.of(RlSearch.labelRecord(root2, RlWire.encodeDecide(frame(8L, 4)))));
    }
}
