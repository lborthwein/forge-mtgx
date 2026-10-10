package forge.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiCache;
import forge.ai.simulation.GameCopier;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlCandidates;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSchemaV2;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;
import forge.game.Game;
import forge.game.player.Player;
import forge.util.MyRandom;

/**
 * Lane search-v2-1009: the S1 search (S-c) and S-t on an obs-v2 seat, in whole games against a stand-in search service
 * that decodes obs-v2 only and checks every frame's v2 structure; the tapes replay WITHOUT the search to the same digest
 * (S1's G3, obs-v2); and the observation the search reads (its leaf frames, built in GameCopier copies with the forked
 * seat knowledge) against the seat's own frame at the same position, under obs-v1 and obs-v2. Plays real Forge games:
 * run inside a broker test lease, cwd = forge-gui with res/; fixtures as {@link RlActorBenchTest}.
 */
public class RlSearchV2GamesTest {
    /** S1's S-c shape at test size (as RlChoiceSearchTest's). */
    static final String SC = "{\"worlds\":2,\"breadth\":3,\"horizon\":1,\"threads\":1,\"departZ\":0,\"leaf\":\"value\","
            + "\"playout\":\"policy\",\"deadEtb\":\"on\",\"zeroX\":\"on\",\"crewNoop\":\"on\"";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static RlSearch.Config spec(final String server, final String extra) {
        return RlSearch.Config.parse(JsonParser.parseString(SC + ",\"server\":\"" + server + "\"" + extra + "}")
                .getAsJsonObject());
    }

    static RlActorBenchTest.LocalEndpoint endpoint(final int version) throws Exception {
        final RlActorBenchTest.LocalEndpoint ep = new RlActorBenchTest.LocalEndpoint(
                new FakeRlServer(0, "eval", 7, null, java.util.Collections.emptyList()));
        ep.version = version;
        return ep;
    }

    /** obs-v2 games with the search on; every summary checked; returns the tapes. */
    static List<JsonObject> playV2(final FakeSearchService svc, final RlSearch.Config search, final int games,
            final int[] totals) throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
        cfg.obsSchema = 2;
        cfg.search = search;
        final List<JsonObject> tapes = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(i,
                    i % 2 == 0 ? "rl:M" : "forge", i % 2 == 0 ? "forge" : "rl:M"),
                    new RlFeaturizer(RlActorBenchTest.index), endpoint(2), null, "test");
            Assert.assertNull(p.guardError);
            Assert.assertTrue(p.end.get("void").isJsonNull(), "game " + i + " void " + p.end.get("void"));
            Assert.assertEquals(p.tape.get("schema_sha").getAsString(), RlSchemaV2.schemaSha(), "an obs-v2 tape");
            final JsonObject s = p.end.getAsJsonObject("search");
            Assert.assertEquals(s.get("obs").getAsInt(), 2, "the search ran obs-v2");
            Assert.assertEquals(s.get("score_failures").getAsInt(), 0, "every SCORE answered");
            final JsonObject s1 = s.getAsJsonObject("lookahead").getAsJsonObject("s1");
            Assert.assertEquals(s1.get("leafFallbacks").getAsInt(), 0, "every LEAVES answered");
            Assert.assertEquals(s1.get("playoutSeatFailures").getAsInt(), 0, String.valueOf(s1.get("playoutLastFailure")));
            totals[0] += s.get("searched").getAsInt();
            totals[1] += s.get("departures").getAsInt();
            if (s.has("st")) {
                totals[2] += s.getAsJsonObject("st").get("macros").getAsInt();
                totals[3] += s.getAsJsonObject("st").get("macro_departures").getAsInt();
                Assert.assertEquals(s.getAsJsonObject("st").get("reconnects").getAsInt(), 0);
            }
            tapes.add(p.tape);
        }
        return tapes;
    }

    /** Each tape replays with no search and no service, obs-v2, to its digest (G3). */
    static void replayWithoutSearch(final List<JsonObject> tapes) {
        for (JsonObject t : tapes) {
            final JsonObject g = RlChoiceSearchTest.replayGame(t);
            g.addProperty("obs_schema", 2);
            final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("eval"), "eval", g,
                    new RlFeaturizer(RlActorBenchTest.index),
                    RlActorBench.tapeEndpoint(t, RlWire.parseUid(t.get("game_uid").getAsString())), null, "test");
            Assert.assertNull(r.guardError);
            Assert.assertNull(r.seat.fatal, "replay error");
            Assert.assertEquals(r.end.get("digest"), t.get("digest"), "obs-v2 replay digest of " + t.get("game_uid"));
        }
    }

    static FakeSearchService v2Service() throws Exception {
        final FakeSearchService svc = new FakeSearchService("0".repeat(64), 2);
        svc.leafValue = RlChoiceSearchTest::hashValue;
        svc.checker = new FakeRlServer(0, "eval", 7, null, java.util.Collections.emptyList());
        return svc;
    }

    static void serviceClean(final FakeSearchService svc) {
        Assert.assertEquals(svc.refusedHellos.get(), 0L, "a HELLO of another schema");
        Assert.assertEquals(svc.badFrames.get(), 0L, "a frame that is not obs-v2");
        Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        Assert.assertEquals(svc.checker.badFrames.get(), 0L, String.valueOf(svc.checker.problems));
        Assert.assertTrue(svc.scores.get() > 0 && svc.decides.get() > 0 && svc.leaves.get() > 0,
                "SCORE " + svc.scores + ", DECIDE " + svc.decides + ", LEAVES " + svc.leaves);
    }

    @Test
    public void scOnAnObsV2SeatSearchesDepartsAndReplaysWithoutTheSearch() throws Exception {
        try (FakeSearchService svc = v2Service()) {
            final int[] tot = new int[4];
            final List<JsonObject> tapes = playV2(svc, spec(svc.address(), ""), Integer.getInteger("rl.v2SearchGames", 3), tot);
            serviceClean(svc);
            Assert.assertTrue(tot[0] > 0, "no searched decision");
            Assert.assertTrue(tot[1] > 0, "no departure (departZ 0, leaves that differ)");
            System.err.println("[search-v2] S-c obs-v2: searched " + tot[0] + ", departures " + tot[1] + ", SCORE "
                    + svc.scores + ", DECIDE " + svc.decides + ", leaves " + svc.leaves);
            replayWithoutSearch(tapes);
        }
    }

    @Test
    public void stOnAnObsV2SeatExpandsChoicesAndReplaysWithoutTheSearch() throws Exception {
        try (FakeSearchService svc = v2Service()) {
            final int[] tot = new int[4];
            final List<JsonObject> tapes = playV2(svc, spec(svc.address(),
                    ",\"choices\":2,\"choiceAlts\":2,\"choiceCap\":6,\"obs\":2"), Integer.getInteger("rl.v2SearchGames", 3), tot);
            serviceClean(svc);
            Assert.assertTrue(tot[2] > 0, "no macro candidate was searched");
            System.err.println("[search-v2] S-t obs-v2: searched " + tot[0] + ", departures " + tot[1] + ", macros " + tot[2]
                    + ", macro departures " + tot[3]);
            replayWithoutSearch(tapes);
        }
    }

    @Test
    public void anObsPinOfAnotherSchemaIsRefusedAtConfig() {
        final JsonObject c = JsonParser.parseString("{\"mode\":\"eval\",\"obsSchema\":1,\"search\":" + SC
                + ",\"server\":\"127.0.0.1:1\",\"obs\":2}}").getAsJsonObject();
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(c));
        c.addProperty("obsSchema", 2);
        Assert.assertEquals(RlActorBench.parse(c).search.obs, 2);
        c.getAsJsonObject("search").remove("obs");
        Assert.assertEquals(RlActorBench.parse(c).obsSchema, 2, "obs-v2 with the search: no longer refused");
    }

    // ------------------------------------------------------------------------------------------------ observation parity

    /**
     * At every searched root (the RL seat's PRIORITY asks with an empty stack): (1) the leaf frame of a fresh observation
     * of the live game against the seat's own frame (observation part), and (2) the same in a GameCopier copy (as the
     * look-ahead prepares one, before its world draw) with the seat knowledge forked into it, as {@code RlSearch.onCopy}
     * does. Counts frames that differ, by field.
     */
    static final class CopyParity implements RlSeat.FrameListener {
        final RlFeaturizer liveFeat;
        final int version;
        int roots, leafEqual, copyEqual, copyFailed;
        /** -Drl.parityDump=DIR: each root's live and copy leaf payloads (u32 length + DECIDE payload) for v(o) checks. */
        static final String DUMP = System.getProperty("rl.parityDump");
        final Map<String, Integer> leafDiff = new TreeMap<>(), copyDiff = new TreeMap<>();

        CopyParity(final RlFeaturizer liveFeat, final int version) {
            this.liveFeat = liveFeat;
            this.version = version;
        }

        @Override
        public void onFrame(final Game g, final Player seat, final RlWire.Decide frame, final RlCandidates.Menu menu,
                final RlFeaturizer.Obs obs, final short[] steps, final JsonObject answer) {
            if (frame.family != RlSchema.F_PRIORITY || !g.getStack().isEmpty()) {
                return;
            }
            roots++;
            final RlKnowledge live = liveFeat.knowledge();
            final RlFeaturizer f1 = new RlFeaturizer(RlActorBenchTest.index);
            f1.setVersion(version);
            f1.setKnowledge(live);
            final RlWire.Decide l1 = RlSearch.leafFrame(f1.observe(g, seat, 0, false, null), frame.gameUid, frame.seat,
                    frame.turn);
            if (diff(frame, l1, leafDiff)) {
                leafEqual++;
            }
            final Random prev = MyRandom.getThreadRandom();
            final Object prevIds = forge.util.IdScope.capture();
            final Object prevCache = AiCache.captureScope();
            MyRandom.setThreadRandom(new Random(1));
            AiCache.openScope();
            forge.util.IdScope.open();
            try {
                synchronized (g) {
                    final GameCopier copier = new GameCopier(g, true);
                    final Game copy = copier.makeCopy();
                    final Player me = (Player) copier.find(seat);
                    final RlKnowledge k = live.forkFor(copy);
                    k.attach();
                    final RlFeaturizer f2 = new RlFeaturizer(RlActorBenchTest.index);
                    f2.setVersion(version);
                    f2.setKnowledge(k);
                    final RlWire.Decide l2 = RlSearch.leafFrame(f2.observe(copy, me, 0, false, null), frame.gameUid,
                            frame.seat, frame.turn);
                    if (diff(frame, l2, copyDiff)) {
                        copyEqual++;
                    }
                    if (DUMP != null) {
                        dump("v" + version + "-live.bin", RlWire.encodeDecide(l1));
                        dump("v" + version + "-copy.bin", RlWire.encodeDecide(l2));
                    }
                }
            } catch (RuntimeException e) {
                copyFailed++;
                copyDiff.merge("copy failed: " + e.getClass().getSimpleName(), 1, Integer::sum);
            } finally {
                forge.util.IdScope.install(prevIds);
                AiCache.installScope(prevCache);
                MyRandom.setThreadRandom(prev);
            }
        }

        static synchronized void dump(final String name, final byte[] p) {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(DUMP, name), true)) {
                o.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(p.length).array());
                o.write(p);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** True when the observation parts are equal; else each differing field once into {@code into}. */
        static boolean diff(final RlWire.Decide a, final RlWire.Decide b, final Map<String, Integer> into) {
            final java.util.Set<String> d = new java.util.TreeSet<>();
            final int mask = RlWire.F_TRUNC_TOKENS | RlWire.F_DROPPED_REFS;
            if ((a.flags & mask) != (b.flags & mask)) d.add("flags");
            if (a.version != b.version) d.add("version");
            if (!java.util.Arrays.equals(a.scal, b.scal)) {
                for (int i = 0; i < a.scal.length; i++) {
                    if (Float.compare(a.scal[i], b.scal[i]) != 0) d.add("scal:" + RlSchema.SCAL.get(i));
                }
            }
            for (int i = 0; i < Math.min(a.ctx.length, b.ctx.length); i++) {
                if (Float.compare(a.ctx[i], b.ctx[i]) != 0) {
                    d.add("ctx:" + (a.version == 2 ? RlSchemaV2.CTX : RlSchema.CTX).get(i));
                }
            }
            if (a.D != b.D || !java.util.Arrays.equals(java.util.Arrays.copyOf(a.deckCard, a.D), java.util.Arrays.copyOf(b.deckCard, b.D))
                    || !java.util.Arrays.equals(java.util.Arrays.copyOf(a.deckCnt, a.D), java.util.Arrays.copyOf(b.deckCnt, b.D))) {
                d.add("deck");
            }
            final boolean sameToks = a.L == b.L && java.util.Arrays.equals(java.util.Arrays.copyOf(a.tokCard, a.L),
                    java.util.Arrays.copyOf(b.tokCard, b.L)) && java.util.Arrays.equals(java.util.Arrays.copyOf(a.tokZone, a.L),
                    java.util.Arrays.copyOf(b.tokZone, b.L));
            if (!sameToks) {
                d.add("tokens");
            } else {
                final int na = a.nAttr();
                for (int i = 0; i < a.L * na; i++) {
                    if (Float.compare(a.tokAttr[i], b.tokAttr[i]) != 0) {
                        d.add("attr:" + (a.version == 2 ? RlSchemaV2.ATTRS : RlSchema.ATTRS).get(i % na));
                    }
                }
                if (a.version == 2) {
                    if (!java.util.Arrays.equals(java.util.Arrays.copyOf(a.tokBits, a.L), java.util.Arrays.copyOf(b.tokBits, b.L))) {
                        d.add("tok_bits");
                    }
                }
            }
            if (a.version == 2) {
                if (a.R != b.R || !eq(a.relSrc, b.relSrc, a.R) || !eq(a.relDst, b.relDst, a.R) || !eq(a.relType, b.relType, a.R)
                        || !eq(a.relArg, b.relArg, a.R) || !eq(a.relNum, b.relNum, a.R)) {
                    d.add("rel");
                }
                if (a.F != b.F || !eq(a.factTok, b.factTok, a.F) || !eq(a.factId, b.factId, a.F)
                        || !java.util.Arrays.equals(java.util.Arrays.copyOf(a.factArg, a.F), java.util.Arrays.copyOf(b.factArg, b.F))
                        || !eq(a.factNum, b.factNum, a.F)) {
                    d.add("fact");
                }
                if (a.Dr != b.Dr || !java.util.Arrays.equals(java.util.Arrays.copyOf(a.restCard, a.Dr), java.util.Arrays.copyOf(b.restCard, b.Dr))
                        || !eq(a.restCnt, b.restCnt, a.Dr)) {
                    d.add("rest");
                }
            }
            for (String k : d) {
                into.merge(k, 1, Integer::sum);
            }
            return d.isEmpty();
        }

        static boolean eq(final short[] x, final short[] y, final int n) {
            return java.util.Arrays.equals(java.util.Arrays.copyOf(x, n), java.util.Arrays.copyOf(y, n));
        }

        static boolean eq(final byte[] x, final byte[] y, final int n) {
            return java.util.Arrays.equals(java.util.Arrays.copyOf(x, n), java.util.Arrays.copyOf(y, n));
        }
    }

    static CopyParity parity(final int version, final int games) throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
        cfg.obsSchema = version;
        final CopyParity all = new CopyParity(null, version);
        for (int i = 0; i < games; i++) {
            final RlFeaturizer feat = new RlFeaturizer(RlActorBenchTest.index);
            final CopyParity cp = new CopyParity(feat, version);
            final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(i,
                    i % 2 == 0 ? "rl:M" : "forge", i % 2 == 0 ? "forge" : "rl:M"), feat, endpoint(version), cp, "test");
            Assert.assertTrue(p.end.get("void").isJsonNull(), "game " + i + " void " + p.end.get("void"));
            all.roots += cp.roots;
            all.leafEqual += cp.leafEqual;
            all.copyEqual += cp.copyEqual;
            all.copyFailed += cp.copyFailed;
            cp.leafDiff.forEach((k, v) -> all.leafDiff.merge(k, v, Integer::sum));
            cp.copyDiff.forEach((k, v) -> all.copyDiff.merge(k, v, Integer::sum));
        }
        System.err.println("[search-v2-parity] obs-v" + version + ": roots " + all.roots + ", leaf = seat frame "
                + all.leafEqual + ", copy leaf = seat frame " + all.copyEqual + ", copy failed " + all.copyFailed
                + "; leaf diffs " + all.leafDiff + "; copy diffs " + all.copyDiff);
        return all;
    }

    @Test
    public void theSearchReadsTheSeatsObservationLiveAndInItsCopies() throws Exception {
        final int games = Integer.getInteger("rl.parityGames", 4);
        final CopyParity v1 = parity(1, games);
        final CopyParity v2 = parity(2, games);
        Assert.assertTrue(v1.roots > 20 && v2.roots > 20, "too few roots: " + v1.roots + " / " + v2.roots);
        // the leaf frame of the live position is the seat's own observation, in both schemas
        Assert.assertEquals(v1.leafEqual, v1.roots, "obs-v1 leaf vs seat frame: " + v1.leafDiff);
        Assert.assertEquals(v2.leafEqual, v2.roots, "obs-v2 leaf vs seat frame: " + v2.leafDiff);
        Assert.assertEquals(v2.copyFailed, 0, String.valueOf(v2.copyDiff));
        // in the copy (forked knowledge): obs-v2 loses nothing the copy keeps under obs-v1, and every v2-only field
        // that differs is reported (Integer.getInteger("rl.parityStrict") makes any difference a failure)
        final double r1 = v1.copyEqual / (double) v1.roots, r2 = v2.copyEqual / (double) v2.roots;
        System.err.println("[search-v2-parity] copy parity obs-v1 " + v1.copyEqual + "/" + v1.roots + " ("
                + Math.round(r1 * 1000) / 10.0 + " %), obs-v2 " + v2.copyEqual + "/" + v2.roots + " (" + Math.round(r2 * 1000) / 10.0 + " %)");
        if (Boolean.getBoolean("rl.parityStrict")) {
            Assert.assertEquals(v2.copyEqual, v2.roots, "obs-v2 copy vs seat frame: " + v2.copyDiff);
        }
    }
}
