package forge.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lane outcome-arm-1104: the frame-dump replay mode ({@code frameDump}, {@link forge.bench.rl.RlFrameDump}). (a) Its
 * config keys and refusals. (b) On the label-replay fixture (RlLabelReplayTest's real games): every game replays
 * digest-equal under the dump; every step-0 label row's payload is byte-identical to the dump's at the same (game_uid,
 * seat, dec_idx); dump rows carry no target and the seat's tape result. (c) A whole-game dump (band [0, 0]) holds the
 * banded dump's rows byte for byte, and more. Real Forge games: run inside a broker test lease, cwd = forge-gui.
 */
public class RlFrameDumpTest {

    /** Whether this class built RlLabelReplayTest's fixture (then it clears it after, so that class builds its own). */
    private static boolean built = false;

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlLabelReplayTest.tapes.isEmpty()) {
            RlLabelReplayTest.setUp();
            built = true;
        }
    }

    /**
     * RlLabelReplayTest.setUp appends to its static tape list: a run of both classes in one JVM would otherwise replay
     * every fixture game twice there. Clear what this class built.
     */
    @org.testng.annotations.AfterClass(alwaysRun = true)
    public static void tearDown() {
        if (built) {
            RlLabelReplayTest.tapes.clear();
            built = false;
        }
    }

    @Test
    public void theFrameDumpKeysAreChecked() {
        final RlActorBench.Cfg c = RlActorBench.parse(JsonParser.parseString(
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\",\"band\":[0,3],\"choices\":false}}").getAsJsonObject());
        Assert.assertEquals(c.frameDumpOut, "/x");
        Assert.assertEquals(c.frameDumpLo, 0);
        Assert.assertEquals(c.frameDumpHi, 3);
        Assert.assertFalse(c.frameDumpChoices);
        final RlActorBench.Cfg d = RlActorBench.parse(JsonParser.parseString(
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\"}}").getAsJsonObject());
        Assert.assertEquals(d.frameDumpHi, 0, "default: every turn");
        Assert.assertTrue(d.frameDumpChoices);
        Assert.assertNull(RlActorBench.parse(JsonParser.parseString("{\"mode\":\"replay\"}").getAsJsonObject())
                .frameDumpOut, "absent = off");
        for (String bad : new String[] {"{\"mode\":\"replay\",\"frameDump\":{}}",
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\",\"bnad\":[0,3]}}",
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\",\"band\":[3,2]}}",
                "{\"mode\":\"replay\",\"frameDump\":{\"out\":\"/x\",\"band\":[-1,2]}}",
                "{\"mode\":\"train\",\"frameDump\":{\"out\":\"/x\"}}",
                "{\"mode\":\"replay\",\"exileScan\":{\"turns\":3,\"out\":\"/y\"},\"frameDump\":{\"out\":\"/x\"}}"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(
                    JsonParser.parseString(bad).getAsJsonObject()));
        }
    }

    static RlActorBench.Cfg dumpCfg(final Path dir, final int lo, final int hi) {
        final RlActorBench.Cfg c = RlLabelReplayTest.cfg("replay");
        c.replayDir = RlLabelReplayTest.out.resolve("tapes").toString();
        c.replayOut = dir.resolve("replay.jsonl").toString();
        c.frameDumpOut = dir.toString();
        c.frameDumpLo = lo;
        c.frameDumpHi = hi;
        return c;
    }

    static Map<String, Object[]> byKey(final List<Object[]> recs) {
        final Map<String, Object[]> m = new TreeMap<>();
        for (Object[] r : recs) {
            final JsonObject h = (JsonObject) r[0];
            final String k = h.get("game_uid").getAsString() + ":" + h.get("seat").getAsInt() + ":"
                    + h.get("dec_idx").getAsInt() + (h.has("choice_ord") ? ":c" + h.get("choice_ord").getAsInt() : "");
            Assert.assertNull(m.put(k, r), "duplicate row " + k);
        }
        return m;
    }

    @Test(timeOut = 1800000)
    public void dumpedFramesAreTheLabellersFramesByteForByte() throws Exception {
        // the step-0 labeller (T = 3): its rows' frames
        final Path lab = Files.createTempDirectory(RlLabelReplayTest.out, "fd-labels-");
        Assert.assertEquals(RlActorBench.replay(RlLabelReplayTest.replayCfg(lab, 3), RlLabelReplayTest.index, "test"), 0);
        final Map<String, Object[]> lr = byKey(RlLabelReplayTest.records(lab));
        Assert.assertFalse(lr.isEmpty());
        // the dump, band [0, 3)
        final Path fd = Files.createTempDirectory(RlLabelReplayTest.out, "fd-band-");
        Assert.assertEquals(RlActorBench.replay(dumpCfg(fd, 0, 3), RlLabelReplayTest.index, "test"), 0);
        final List<JsonObject> games = RlLabelReplayTest.jsonl(fd, "games-");
        Assert.assertEquals(games.size(), RlLabelReplayTest.tapes.size());
        int rows = 0;
        for (JsonObject g : games) {
            Assert.assertTrue(g.get("equal").getAsBoolean(), g.toString());
            Assert.assertEquals(g.get("rows_void").getAsInt(), 0);
            rows += g.get("rows").getAsInt();
        }
        final Map<String, Object[]> dr = byKey(RlLabelReplayTest.records(fd));
        Assert.assertEquals(dr.size(), rows);
        final Map<String, JsonObject> tapeByUid = new TreeMap<>();
        for (JsonObject t : RlLabelReplayTest.tapes) {
            tapeByUid.put(t.get("game_uid").getAsString(), t);
        }
        for (Map.Entry<String, Object[]> e : lr.entrySet()) {
            final Object[] d = dr.get(e.getKey());
            Assert.assertNotNull(d, "no dump row for label row " + e.getKey());
            Assert.assertTrue(Arrays.equals((byte[]) d[1], (byte[]) e.getValue()[1]), "payload differs at " + e.getKey());
        }
        for (Object[] d : dr.values()) {
            final JsonObject h = (JsonObject) d[0];
            Assert.assertEquals(h.get("kind").getAsString(), "value");
            Assert.assertEquals(h.get("schema").getAsString(), forge.bench.rl.RlFrameDump.SCHEMA);
            Assert.assertTrue(h.get("target").isJsonNull());
            Assert.assertEquals(h.get("target_source").getAsString(), "none");
            final int tbe = h.get("turns_before_end").getAsInt();
            Assert.assertTrue(tbe >= 0 && tbe < 3, h.toString());
            final JsonObject t = tapeByUid.get(h.get("game_uid").getAsString());
            Assert.assertEquals(h.get("tape_result").getAsInt(),
                    t.getAsJsonArray("result").get(h.get("seat").getAsInt()).getAsInt());
        }
        // every priority row of the band is a label row's position too (the step-0 labeller's window is [0, 3))
        for (Map.Entry<String, Object[]> e : dr.entrySet()) {
            if ("priority".equals(((JsonObject) e.getValue()[0]).get("row_kind").getAsString())) {
                Assert.assertTrue(lr.containsKey(e.getKey()), "dump priority row without a label row " + e.getKey());
            }
        }
        // the whole game: a superset of the band's positions, and deterministic (two runs byte for byte). Its band
        // frames may differ from the banded dump's: RlKnowledge settles pending sightings when it is queried, so what
        // a frame shows depends on which earlier positions were observed (RlFrameDump's class comment)
        final Path all = Files.createTempDirectory(RlLabelReplayTest.out, "fd-all-");
        Assert.assertEquals(RlActorBench.replay(dumpCfg(all, 0, 0), RlLabelReplayTest.index, "test"), 0);
        final Map<String, Object[]> ar = byKey(RlLabelReplayTest.records(all));
        Assert.assertTrue(ar.size() > dr.size(), ar.size() + " vs " + dr.size());
        int same = 0;
        for (Map.Entry<String, Object[]> e : dr.entrySet()) {
            final Object[] a = ar.get(e.getKey());
            Assert.assertNotNull(a, e.getKey());
            same += Arrays.equals((byte[]) a[1], (byte[]) e.getValue()[1]) ? 1 : 0;
        }
        final Path all2 = Files.createTempDirectory(RlLabelReplayTest.out, "fd-all2-");
        Assert.assertEquals(RlActorBench.replay(dumpCfg(all2, 0, 0), RlLabelReplayTest.index, "test"), 0);
        final Map<String, Object[]> ar2 = byKey(RlLabelReplayTest.records(all2));
        Assert.assertEquals(ar2.keySet(), ar.keySet());
        for (Map.Entry<String, Object[]> e : ar.entrySet()) {
            Assert.assertTrue(Arrays.equals((byte[]) ar2.get(e.getKey())[1], (byte[]) e.getValue()[1]),
                    "not deterministic at " + e.getKey());
        }
        System.err.println("[frame-dump test] label rows " + lr.size() + " band rows " + dr.size() + " all rows "
                + ar.size() + " band rows byte-equal in the whole-game dump " + same + "/" + dr.size());
    }

    /** dump-menus-1105: every MXL1 record of a dump's menus files ({header, payload}). */
    static List<Object[]> menuRecords(final Path dir) throws Exception {
        final List<Object[]> recs = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(".mxm.gz")).sorted()::iterator) {
                final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(p))) {
                    in.transferTo(bo);
                }
                final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bo.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                while (b.hasRemaining()) {
                    final byte[] magic = new byte[4];
                    b.get(magic);
                    Assert.assertEquals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII), "MXL1");
                    final byte[] h = new byte[b.getInt()];
                    b.get(h);
                    final byte[] pl = new byte[b.getInt()];
                    b.get(pl);
                    recs.add(new Object[] {JsonParser.parseString(new String(h, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject(), pl});
                }
            }
        }
        return recs;
    }

    /**
     * dump-menus-1105: {@code frameDump.menus} on the fixture's whole games. The frames are the menus-off dump's, record
     * for record and byte for byte; a games row only gains the menu counts; the menus go to their own file, and every
     * menu's chosen options are among its options, with the chosen single pick's answer equal to the record's. (These
     * policy tapes have no decision log, so no forced choice and no menu: the identity part is what this checks.)
     */
    @Test(timeOut = 1800000)
    public void menusAddTheirOwnRecordsAndChangeNoFrame() throws Exception {
        final Path off = Files.createTempDirectory(RlLabelReplayTest.out, "fm-off-");
        Assert.assertEquals(RlActorBench.replay(dumpCfg(off, 0, 0), RlLabelReplayTest.index, "test"), 0);
        final Path on = Files.createTempDirectory(RlLabelReplayTest.out, "fm-on-");
        final RlActorBench.Cfg c = dumpCfg(on, 0, 0);
        c.frameDumpMenus = true;
        Assert.assertEquals(RlActorBench.replay(c, RlLabelReplayTest.index, "test"), 0);
        final List<Object[]> fOff = RlLabelReplayTest.records(off);
        final List<Object[]> fOn = RlLabelReplayTest.records(on);
        Assert.assertEquals(fOn.size(), fOff.size());
        for (int i = 0; i < fOff.size(); i++) {
            Assert.assertEquals(fOn.get(i)[0], fOff.get(i)[0], "frame header " + i);
            Assert.assertTrue(Arrays.equals((byte[]) fOn.get(i)[1], (byte[]) fOff.get(i)[1]), "frame payload " + i);
        }
        final java.util.Set<String> menuKeys = new java.util.TreeSet<>(Arrays.asList("menus", "menus_void",
                "menu_options", "menus_ge10", "menus_skipped", "menu_by_method", "menu_ms"));
        final java.util.Set<String> timing = new java.util.TreeSet<>(Arrays.asList("frame_ms", "cpu_ms"));
        final List<JsonObject> gOff = RlLabelReplayTest.jsonl(off, "games-");
        final List<JsonObject> gOn = RlLabelReplayTest.jsonl(on, "games-");
        Assert.assertEquals(gOn.size(), gOff.size());
        int menus = 0;
        for (int i = 0; i < gOff.size(); i++) {
            final JsonObject a = gOn.get(i).deepCopy();
            final JsonObject b = gOff.get(i).deepCopy();
            Assert.assertTrue(a.get("equal").getAsBoolean(), a.toString());
            Assert.assertEquals(a.get("menus_skipped").getAsInt(), 0, a.toString());
            menus += a.get("menus").getAsInt();
            for (String k : menuKeys) {
                Assert.assertTrue(a.has(k), k);
                a.remove(k);
                Assert.assertFalse(b.has(k), k);
            }
            for (String k : timing) {
                a.remove(k);
                b.remove(k);
            }
            Assert.assertEquals(a, b, "games row " + i);
        }
        final List<Object[]> ms = menuRecords(on);
        Assert.assertEquals(ms.size(), menus);
        // the fixture replays policy tapes without a decision log: no AiTape forced choice, so no menu here (the menu
        // reader is RlFrameDumpMenusTest's; the forced-choice wiring is the r1 quick proof's, on decision-log tapes)
        Assert.assertTrue(menuRecords(off).isEmpty());
        final Map<String, Integer> byFamily = new TreeMap<>();
        int answered = 0, wide = 0;
        for (Object[] r : ms) {
            final JsonObject h = (JsonObject) r[0];
            Assert.assertEquals(h.get("kind").getAsString(), "menu");
            Assert.assertEquals(h.get("schema").getAsString(), forge.bench.rl.RlFrameDump.MENU_SCHEMA);
            Assert.assertTrue(((byte[]) r[1]).length > 0);
            byFamily.merge(h.get("family").getAsString(), 1, Integer::sum);
            final com.google.gson.JsonArray opts = h.getAsJsonArray("options");
            final com.google.gson.JsonArray ch = h.getAsJsonArray("chosen");
            for (com.google.gson.JsonElement x : ch) {
                Assert.assertTrue(x.getAsInt() >= 0 && x.getAsInt() < opts.size(), h.toString());
            }
            wide += h.get("n_options").getAsInt() >= 10 ? 1 : 0;
            if (ch.size() == 1 && opts.get(ch.get(0).getAsInt()).getAsJsonObject().has("a")) {
                Assert.assertEquals(opts.get(ch.get(0).getAsInt()).getAsJsonObject().get("a"), h.get("chosen_a"),
                        "the chosen option's answer is the record's: " + h);
                answered++;
            }
        }
        System.err.println("[frame-dump test] menus " + menus + " by family " + byFamily + "; chosen answer checked "
                + answered + "; >= 10 options " + wide);
    }

    /**
     * knowledge-settle-1104: with the tracker on the fixed settle schedule a frame no longer depends on which positions
     * were observed. The step-0 labeller (T = 3), the banded dump and the whole-game dump give byte-identical frames at
     * every shared position, and the rows say knowledge_settle fixed. Off (the other test), the schedule shows.
     */
    @Test(timeOut = 1800000)
    public void onTheFixedSettleScheduleFramesDoNotDependOnTheQuerySchedule() throws Exception {
        final boolean was = forge.bench.rl.RlKnowledge.FIXED_SETTLE;
        forge.bench.rl.RlKnowledge.FIXED_SETTLE = true;
        try {
            final Path lab = Files.createTempDirectory(RlLabelReplayTest.out, "fs-labels-");
            Assert.assertEquals(RlActorBench.replay(RlLabelReplayTest.replayCfg(lab, 3), RlLabelReplayTest.index, "test"),
                    0);
            final Map<String, Object[]> lr = byKey(RlLabelReplayTest.records(lab));
            final Path band = Files.createTempDirectory(RlLabelReplayTest.out, "fs-band-");
            Assert.assertEquals(RlActorBench.replay(dumpCfg(band, 0, 3), RlLabelReplayTest.index, "test"), 0);
            final Map<String, Object[]> dr = byKey(RlLabelReplayTest.records(band));
            final Path all = Files.createTempDirectory(RlLabelReplayTest.out, "fs-all-");
            Assert.assertEquals(RlActorBench.replay(dumpCfg(all, 0, 0), RlLabelReplayTest.index, "test"), 0);
            final Map<String, Object[]> ar = byKey(RlLabelReplayTest.records(all));
            Assert.assertFalse(lr.isEmpty());
            for (Map.Entry<String, Object[]> e : lr.entrySet()) {
                Assert.assertTrue(Arrays.equals((byte[]) dr.get(e.getKey())[1], (byte[]) e.getValue()[1]),
                        "label vs band at " + e.getKey());
            }
            for (Map.Entry<String, Object[]> e : dr.entrySet()) {
                Assert.assertEquals(((JsonObject) e.getValue()[0]).get("knowledge_settle").getAsString(), "fixed");
                Assert.assertTrue(Arrays.equals((byte[]) ar.get(e.getKey())[1], (byte[]) e.getValue()[1]),
                        "band vs whole game at " + e.getKey());
            }
            System.err.println("[frame-dump test] fixed settle: label rows " + lr.size() + " band rows " + dr.size()
                    + " all rows " + ar.size() + ", every shared position byte-equal");
        } finally {
            forge.bench.rl.RlKnowledge.FIXED_SETTLE = was;
        }
    }
}
