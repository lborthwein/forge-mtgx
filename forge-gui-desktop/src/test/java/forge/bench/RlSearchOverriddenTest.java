package forge.bench;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;

/**
 * Lane sdcc-solver-loop-1111: a decision whose answer the attached search changed away from the policy's own is in the
 * game's {@code overridden} list (GAME_END and the tape), so a learner never trains the played answer as the policy's
 * choice. Whole train-mode games against the in-process stand-in policy ({@link RlActorBenchTest.LocalEndpoint}) with
 * the exploration spec (choice asks and, with provenOnly, priority; eps 1): the search service's address is a dead
 * port (a failed SCORE draws the priority deviation uniformly), so the test needs no service. Without a search nothing
 * changes: no decision differs from the policy's and {@code searchOverridden} is 0.
 *
 * <p>Plays real Forge games: run inside a broker test lease, cwd = forge-gui with res/; fixtures as RlActorBenchTest.
 */
public class RlSearchOverriddenTest {
    static final String SPEC = "{\"server\":\"127.0.0.1:1\",\"connectTimeoutMs\":200,\"provenOnly\":true,"
            + "\"explore\":{\"eps\":1.0,\"priorityEps\":0.5,"
            + "\"families\":\"TARGETS,MULLIGAN,MULLIGAN_BOTTOM,START_PLAYER,CARDS,MODE,CONFIRM,DISCARD_FROM\"}}";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    /** The stand-in policy, keeping its own answer to every DECIDE by dec_idx. */
    static final class Keeping implements RlSeat.Endpoint {
        final RlActorBenchTest.LocalEndpoint in;
        final Map<Integer, short[]> own = new HashMap<>();

        Keeping(final RlActorBenchTest.LocalEndpoint in) {
            this.in = in;
        }

        @Override
        public RlWire.Decision decide(final byte[] p) {
            final RlWire.Decision d = in.decide(p);
            own.put(d.decIdx, d.steps.clone());
            return d;
        }

        @Override
        public void record(final byte[] p) {
            in.record(p);
        }
    }

    /** dec_idx of the tape rows whose played steps are not the policy's own. */
    static TreeSet<Integer> changed(final JsonObject tape, final Keeping ep) {
        final TreeSet<Integer> out = new TreeSet<>();
        for (JsonElement e : tape.getAsJsonArray("dec")) {
            final JsonArray row = e.getAsJsonArray();
            final int d = row.get(0).getAsInt();
            final JsonArray st = row.get(4).getAsJsonArray();
            final short[] played = new short[st.size()];
            for (int i = 0; i < played.length; i++) {
                played[i] = st.get(i).getAsShort();
            }
            final short[] own = ep.own.get(d);
            Assert.assertNotNull(own, "tape row " + d + " was asked of the policy");
            if (!Arrays.equals(played, own)) {
                out.add(d);
            }
        }
        return out;
    }

    static TreeSet<Integer> ints(final JsonArray a) {
        final TreeSet<Integer> out = new TreeSet<>();
        for (JsonElement e : a) {
            out.add(e.getAsInt());
        }
        return out;
    }

    @Test
    public void everyAnswerTheSearchChangedIsOverridden() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int deviations = 0, priority = 0, choice = 0;
        for (int i = 0; i < 4; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true);
            // ---- no search: the policy's answers are the played answers
            final Keeping off = new Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played a = RlActorBench.play(RlActorBenchTest.cfg("train"), "train", game.deepCopy(),
                    new RlFeaturizer(RlActorBenchTest.index), off, null, "test");
            Assert.assertNull(a.guardError);
            Assert.assertEquals(a.seat.searchOverridden, 0, "no search, no search override");
            Assert.assertTrue(changed(a.tape, off).isEmpty(), "no search: every row is the policy's own answer");
            Assert.assertFalse(a.end.has("search_overridden") || a.tape.has("search_overridden"), "no key without a search");
            // ---- exploration at choice asks and at priority
            final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("train");
            cfg.search = RlSearch.Config.parse(JsonParser.parseString(SPEC).getAsJsonObject());
            final Keeping on = new Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played p = RlActorBench.play(cfg, "train", game.deepCopy(),
                    new RlFeaturizer(RlActorBenchTest.index), on, null, "test");
            Assert.assertNull(p.guardError);
            final TreeSet<Integer> ch = changed(p.tape, on);
            final TreeSet<Integer> over = ints(p.end.getAsJsonArray("overridden"));
            Assert.assertEquals(ints(p.tape.getAsJsonArray("overridden")), over, "the tape's list is GAME_END's");
            Assert.assertTrue(over.containsAll(ch), "game " + i + ": changed " + ch + " not all in overridden " + over);
            Assert.assertEquals(p.seat.searchOverridden, ch.size(), "one search override per changed answer");
            Assert.assertEquals(p.end.get("search_overridden").getAsInt(), ch.size(), "GAME_END reports the count");
            Assert.assertEquals(p.tape.get("search_overridden").getAsInt(), ch.size(), "and the tape");
            final TreeSet<Integer> rows = new TreeSet<>();
            for (JsonElement e : p.end.getAsJsonObject("search").getAsJsonObject("explore").getAsJsonArray("rows")) {
                final JsonObject r = e.getAsJsonObject();
                rows.add(r.get("d").getAsInt());
                if ("PRIORITY".equals(r.get("f").getAsString())) {
                    priority++;
                } else {
                    choice++;
                }
            }
            Assert.assertEquals(ch, rows, "the changed answers are the exploration's deviations");
            deviations += ch.size();
        }
        checker.close();
        System.err.println("[search-overridden] deviations " + deviations + " (priority " + priority + ", choice "
                + choice + ")");
        Assert.assertTrue(priority > 0 && choice > 0, "priority " + priority + ", choice " + choice);
    }
}
