package forge.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSchemaV3;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;

/**
 * Lane r4-payment-1015: the MANA_SOURCE ask (obs-v3) in whole games, on RlActorBenchTest's 24 TRAIN decks and seeds.
 * Plays real Forge games: run inside a broker test lease; cwd = forge-gui with res/.
 * <ul>
 *   <li>Option off: obs-v3 without the ask plays the obs-v2 games byte for byte (equal digests).</li>
 *   <li>Option on, answered as Forge AI would (AUTO): the games are Forge's (equal digests),
 *       and asks were posed.</li>
 *   <li>Option on, random sources: tapes replay to equal digests, and every MANA_SOURCE frame is well formed.</li>
 * </ul>
 * The other families are answered by a random stream keyed by the game's count of non-MANA_SOURCE decisions, so the
 * answers to the rest of the game do not shift when payment asks are inserted.
 */
public class RlManaSourceGamesTest {

    static int games;

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBenchTest.setUp();
        games = Integer.getInteger("rl.manaGames", 8);
    }

    enum Pay { RANDOM, AUTO }

    /** In-process server: MANA_SOURCE by {@link Pay}; every other family uniform, keyed by its own decision count. */
    static final class Endpoint implements RlSeat.Endpoint {
        final FakeRlServer checker;
        final Pay pay;
        final int version;
        final Map<Long, Integer> otherIdx = new HashMap<>();
        int manaFrames, manaOther, badMana, manaRecords, manaRecordsForge;
        final java.util.Set<Integer> manaSeats = new java.util.TreeSet<>();
        final List<String> problems = new ArrayList<>();

        Endpoint(final FakeRlServer checker, final Pay pay, final int layout) {
            this.checker = checker;
            this.pay = pay;
            this.version = layout;
        }

        @Override
        public RlWire.Decision decide(final byte[] p) {
            final RlWire.Decide d = RlWire.decodeDecide(p, version);
            checker.check(d, false);
            final RlWire.Decision x = new RlWire.Decision();
            x.gameUid = d.gameUid;
            x.decIdx = d.decIdx;
            x.status = RlWire.ST_OK;
            x.policyVersion = 1;
            if (d.family == RlSchemaV3.F_MANA_SOURCE) {
                manaFrames++;
                manaSeats.add(d.seat);
                checkMana(d);
                x.steps = new short[] {(short) mana(d)};
            } else {
                final int k = otherIdx.merge(d.gameUid, 1, Integer::sum) - 1;
                x.steps = FakeRlServer.randomSteps(d, new Random(FakeRlServer.mix(7, d.gameUid, k)));
            }
            return x;
        }

        int mana(final RlWire.Decide d) {
            if (pay == Pay.AUTO) {
                return 0;
            }
            final int c = new Random(FakeRlServer.mix(11, d.gameUid, d.decIdx)).nextInt(d.C);
            if (c != 0) {
                manaOther++;      // a source (Forge AI's own among them: it is unflagged since decision 10-15)
            }
            return c;
        }

        void checkMana(final RlWire.Decide d) {
            String bad = null;
            int flagged = 0;
            if (d.mode != RlSchema.M_SINGLE || d.C < 3) {
                bad = "mode " + d.mode + " C " + d.C;
            } else if (d.candKind[0] != RlSchema.K_NONE || (d.candFlags[0] & RlSchemaV3.FLAG_FORGE_DEFAULT) == 0) {
                bad = "candidate 0 is not AUTO";
            } else {
                for (int i = 1; i < d.C; i++) {
                    if (d.candKind[i] != RlSchema.K_ACTIVATE || d.candTok[i] < 0) {
                        bad = "source " + i + " kind " + d.candKind[i] + " tok " + d.candTok[i];
                    }
                    if ((d.candFlags[i] & RlSchemaV3.FLAG_FORGE_DEFAULT) != 0) {
                        flagged++;
                    }
                }
                if (bad == null && flagged != 0) {
                    bad = flagged + " sources flagged forge_default (the bonus is AUTO's alone)";
                }
            }
            if (bad != null) {
                badMana++;
                if (problems.size() < 20) {
                    problems.add("dec " + d.decIdx + ": " + bad);
                }
            }
        }

        @Override
        public void record(final byte[] p) {
            final RlWire.Decide d = RlWire.decodeDecide(p, version);
            checker.check(d, true);
            if (d.family == RlSchemaV3.F_MANA_SOURCE) {
                manaRecords++;
                checkMana(d);
                // the teacher is Forge AI's own source (a source, never AUTO)
                if (d.teacher.length == 1 && d.teacher[0] > 0 && d.teacher[0] < d.C) {
                    manaRecordsForge++;
                }
            }
        }
    }

    static RlActorBench.Cfg cfg(final int obs, final boolean mana) {
        final RlActorBench.Cfg c = RlActorBenchTest.cfg("train");
        c.obsSchema = obs;
        c.manaSource = mana;
        return c;
    }

    static RlActorBench.Played play(final int i, final int obs, final boolean mana, final Endpoint ep)
            throws Exception {
        final RlActorBench.Played p = RlActorBench.play(cfg(obs, mana), "train",
                RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "forge" : "rl:M", false), new RlFeaturizer(
                        RlActorBenchTest.index), ep, null, "test");
        Assert.assertNull(p.guardError, "guard");
        Assert.assertNull(p.seat.fatal, "fatal");
        return p;
    }

    static int manaSent(final RlActorBench.Played p) {
        final JsonObject c = p.tape.getAsJsonObject("census");
        return c.has("MANA_SOURCE") ? c.getAsJsonObject("MANA_SOURCE").get("sent").getAsInt() : 0;
    }

    @Test
    public void optionOffIsByteIdentical() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        final List<String> bad = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            final RlActorBench.Played v2 = play(i, 2, false, new Endpoint(checker, Pay.RANDOM, 2));
            final RlActorBench.Played v3 = play(i, 3, false, new Endpoint(checker, Pay.RANDOM, 2));
            final String line = "game " + i + ": v2 " + v2.end.get("digest") + " v3-off " + v3.end.get("digest");
            System.err.println("[mana-off] " + line);
            if (!v2.end.get("digest").equals(v3.end.get("digest"))) {
                bad.add(line);
            }
            Assert.assertEquals(manaSent(v3), 0);
            Assert.assertFalse(v3.tape.has("mana_source"));
            Assert.assertEquals(v3.tape.get("schema_sha").getAsString(), RlSchemaV3.SCHEMA_SHA);
        }
        Assert.assertTrue(bad.isEmpty(), String.valueOf(bad));
        Assert.assertEquals(checker.badFrames.get(), 0L, String.valueOf(checker.problems));
    }

    @Test
    public void answeringAsForgeIsForgesGame() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        final List<String> bad = new ArrayList<>();
        int asks = 0;
        for (int i = 0; i < games; i++) {
            final RlActorBench.Played off = play(i, 3, false, new Endpoint(checker, Pay.RANDOM, 2));
            final Endpoint ep = new Endpoint(checker, Pay.AUTO, 2);
            final RlActorBench.Played on = play(i, 3, true, ep);
            asks += manaSent(on);
            final String line = "game " + i + ": off " + off.end.get("digest") + " on " + on.end.get("digest")
                    + " mana asks " + manaSent(on) + " notes " + on.tape.getAsJsonObject("census").get("notes");
            System.err.println("[mana-forge] " + line);
            if (!off.end.get("digest").equals(on.end.get("digest"))) {
                bad.add(line);
            }
            Assert.assertTrue(on.tape.get("mana_source").getAsBoolean());
            Assert.assertEquals(ep.badMana, 0, String.valueOf(ep.problems));
        }
        Assert.assertTrue(asks > 0, "no MANA_SOURCE ask in " + games + " games");
        Assert.assertTrue(bad.isEmpty(), String.valueOf(bad));
        Assert.assertEquals(checker.badFrames.get(), 0L, String.valueOf(checker.problems));
    }

    @Test
    public void randomSourcesReplay() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        final List<JsonObject> tapes = new ArrayList<>();
        int asks = 0, other = 0;
        for (int i = 0; i < games; i++) {
            final Endpoint ep = new Endpoint(checker, Pay.RANDOM, 2);
            final RlActorBench.Played p = play(i, 3, true, ep);
            asks += manaSent(p);
            other += ep.manaOther;
            Assert.assertEquals(ep.badMana, 0, String.valueOf(ep.problems));
            tapes.add(p.tape);
        }
        System.err.println("[mana-random] asks " + asks + ", other sources chosen " + other);
        Assert.assertTrue(asks > 0 && other > 0, "asks " + asks + " other " + other);
        Assert.assertEquals(checker.badFrames.get(), 0L, String.valueOf(checker.problems));
        for (JsonObject t : tapes) {
            if (!t.get("void").isJsonNull()) {
                continue;
            }
            final JsonObject g = new JsonObject();
            g.add("game_uid", t.get("game_uid"));
            g.add("seed", t.get("seed"));
            final JsonArray decks = new JsonArray(), shas = new JsonArray();
            for (JsonElement d : t.getAsJsonArray("decks")) {
                decks.add(d.getAsJsonObject().get("path"));
                shas.add(d.getAsJsonObject().get("sha"));
            }
            g.add("decks", decks);
            g.add("deck_sha", shas);
            g.add("controllers", t.get("controllers"));
            g.addProperty("priv", false);
            g.addProperty("obs_schema", RlSchemaV3.versionOfSha(t.get("schema_sha").getAsString()));
            g.addProperty("mana_source", t.has("mana_source") && t.get("mana_source").getAsBoolean());
            // the replay actor's own defaults are off: the GAME's keys (as the replay mode builds them) decide
            final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("train"), "train", g,
                    new RlFeaturizer(RlActorBenchTest.index), RlActorBench.tapeEndpoint(t, RlWire.parseUid(
                            t.get("game_uid").getAsString())), null, "test");
            Assert.assertNull(r.guardError);
            Assert.assertNull(r.seat.fatal, "replay error");
            Assert.assertEquals(r.end.get("digest"), t.get("digest"), "replay digest of " + t.get("game_uid"));
        }
    }

    /** The slot filter (league snapshots keep Forge paying) and its replay; record mode's RECORD frames. */
    @Test
    public void slotFilterAndRecordMode() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int asks = 0;
        for (int i = 0; i < games; i++) {
            final Endpoint ep = new Endpoint(checker, Pay.RANDOM, 2);
            final RlActorBench.Cfg c = cfg(3, true);
            c.manaSourceSlots = java.util.Arrays.asList("M");
            final JsonObject g = RlActorBenchTest.game(i, i % 2 == 0 ? "rl:M" : "rl:snap-x",
                    i % 2 == 0 ? "rl:snap-x" : "rl:M", false);
            final RlActorBench.Played p = RlActorBench.play(c, "train", g, new RlFeaturizer(RlActorBenchTest.index),
                    ep, null, "test");
            Assert.assertNull(p.guardError);
            asks += manaSent(p);
            final int mSeat = i % 2 == 0 ? 0 : 1;
            Assert.assertTrue(ep.manaSeats.isEmpty() || ep.manaSeats.equals(java.util.Collections.singleton(mSeat)),
                    "asked seats " + ep.manaSeats + ", rl:M is seat " + mSeat);
            Assert.assertEquals(p.tape.getAsJsonArray("mana_source_slots").get(0).getAsString(), "M");
            if (p.tape.get("void").isJsonNull()) {
                final JsonObject r = new JsonObject();
                r.add("game_uid", p.tape.get("game_uid"));
                r.add("seed", p.tape.get("seed"));
                final JsonArray decks = new JsonArray(), shas = new JsonArray();
                for (JsonElement d : p.tape.getAsJsonArray("decks")) {
                    decks.add(d.getAsJsonObject().get("path"));
                    shas.add(d.getAsJsonObject().get("sha"));
                }
                r.add("decks", decks);
                r.add("deck_sha", shas);
                r.add("controllers", p.tape.get("controllers"));
                r.addProperty("priv", false);
                r.addProperty("obs_schema", 3);
                r.addProperty("mana_source", true);
                r.add("mana_source_slots", p.tape.get("mana_source_slots"));
                final RlActorBench.Played rp = RlActorBench.play(RlActorBenchTest.cfg("train"), "train", r,
                        new RlFeaturizer(RlActorBenchTest.index), RlActorBench.tapeEndpoint(p.tape, RlWire.parseUid(
                                p.tape.get("game_uid").getAsString())), null, "test");
                Assert.assertNull(rp.seat.fatal, "replay error");
                Assert.assertEquals(rp.end.get("digest"), p.tape.get("digest"), "replay of a slot-filtered game");
            }
        }
        Assert.assertTrue(asks > 0, "the M seat was never asked");
        // record mode: recorders delegate every MANA_SOURCE ask; Forge AI's source is the RECORD frame's teacher, and
        // the games are the option-off games
        final FakeRlServer rc = new FakeRlServer(0, "record", 7, null, Collections.emptyList());
        int recs = 0, recsForge = 0;
        for (int i = 0; i < games; i++) {
            final Endpoint on = new Endpoint(rc, Pay.RANDOM, 2);
            final Endpoint off = new Endpoint(rc, Pay.RANDOM, 2);
            final RlActorBench.Cfg con = RlActorBenchTest.cfg("record");
            con.obsSchema = 3;
            con.manaSource = true;
            final RlActorBench.Cfg coff = RlActorBenchTest.cfg("record");
            coff.obsSchema = 3;
            final RlActorBench.Played a = RlActorBench.play(con, "record", RlActorBenchTest.game(i, "record", "record",
                    true), new RlFeaturizer(RlActorBenchTest.index), on, null, "test");
            final RlActorBench.Played b = RlActorBench.play(coff, "record", RlActorBenchTest.game(i, "record",
                    "record", true), new RlFeaturizer(RlActorBenchTest.index), off, null, "test");
            Assert.assertEquals(a.end.get("digest"), b.end.get("digest"), "record game " + i);
            Assert.assertEquals(on.badMana, 0, String.valueOf(on.problems));
            recs += on.manaRecords;
            recsForge += on.manaRecordsForge;
        }
        System.err.println("[mana-record] RECORD frames " + recs + ", teacher = Forge AI's source " + recsForge);
        Assert.assertTrue(recs > 0, "no MANA_SOURCE RECORD frame");
        Assert.assertEquals(recsForge, recs);
        Assert.assertEquals(rc.badFrames.get(), 0L, String.valueOf(rc.problems));
        Assert.assertEquals(rc.badTeachers.get(), 0L, String.valueOf(rc.problems));
    }

    @Test
    public void guards() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        final JsonObject g = RlActorBenchTest.game(0, "rl:M", "forge", false);
        g.addProperty("mana_source", true);
        final RlActorBench.Played p = RlActorBench.play(cfg(2, false), "train", g,
                new RlFeaturizer(RlActorBenchTest.index), new Endpoint(checker, Pay.RANDOM, 2), null, "test");
        Assert.assertNotNull(p.guardError, "mana_source on obs-v2 must be refused");
        try {
            final JsonObject o = new JsonObject();
            o.addProperty("obsSchema", 2);
            o.addProperty("manaSource", true);
            RlActorBench.parse(o);
            Assert.fail("manaSource without obsSchema 3 must be refused");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
