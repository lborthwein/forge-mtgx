package forge.bench;

import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.bench.rl.RlWire;

/**
 * Position starts (lane sdcc-curric-1111, {@link RlStartState}): off is the plain path; an applied spec starts the game
 * in a mid-game position built from the two decks' own cards (pieces placed, lands on the battlefield, every card
 * still in the game, no mulligan asked), the game plays to its own end, the same GAME gives the same game, a piece the
 * deck lacks leaves the position off and says so, and bad specs are refused. Plays real Forge games (Forge seats
 * only) on {@link RlSeedingTest}'s fixtures: cwd = forge-gui with res/.
 */
public class RlStartStateTest {

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlSeedingTest.root == null) {
            RlSeedingTest.setUp();
        }
    }

    static JsonArray arr(final int... x) {
        final JsonArray a = new JsonArray();
        for (int v : x) a.add(v);
        return a;
    }

    static JsonObject spec(final int seat, final List<String> pieces, final List<String> place, final int[] missing) {
        final JsonObject o = new JsonObject();
        o.addProperty("schema", RlStartState.SCHEMA);
        o.addProperty("seat", seat);
        o.addProperty("route", "test-route");
        final JsonArray pc = new JsonArray();
        for (String x : pieces) pc.add(x);
        o.add("pieces", pc);
        final JsonArray pl = new JsonArray();
        for (String x : place) pl.add(x);
        o.add("place", pl);
        o.add("missing", arr(missing));
        o.add("lands", arr(5, 5));
        o.add("hand", arr(4, 4));
        o.add("gy", arr(2, 2));
        o.add("life", arr(20, 20));
        o.add("opp_lands", arr(4, 4));
        o.add("opp_hand", arr(3, 3));
        o.add("opp_board", arr(1, 1));
        o.add("opp_gy", arr(1, 1));
        o.add("opp_life", arr(10, 10));
        o.addProperty("active", 1.0);
        return o;
    }

    static JsonObject game(final int i, final JsonObject st) throws Exception {
        final JsonObject g = RlSeedingTest.game(i, null);
        if (st != null) {
            g.add("start_state", st);
        }
        return g;
    }

    static int deckSize(final int i, final int seat) {
        return RlActorBench.deck(RlSeedingTest.deckOf(i, seat)).getMain().countAll();
    }

    @Test
    public void offIsThePlainPath() throws Exception {
        final RlSeedingTest.Run a = RlSeedingTest.play("train", RlSeedingTest.game(0, null)); // not the cached run
        Assert.assertFalse(a.p.tape.has("start_state"));
        Assert.assertFalse(a.p.tape.has("started"));
        Assert.assertFalse(a.p.end.has("started"));
        final JsonObject g = RlSeedingTest.game(0, null);
        g.add("start_state", JsonNull.INSTANCE);
        final RlSeedingTest.Run n = RlSeedingTest.play("train", g);
        Assert.assertEquals(RlSeedingTest.digest(n), RlSeedingTest.digest(a));
        Assert.assertEquals(RlWire.canonicalString(RlSeedingTest.strip(n.p.tape)),
                RlWire.canonicalString(RlSeedingTest.strip(a.p.tape)), "the null-spec tape is the off tape");
    }

    @Test
    public void appliedPositionIsBuiltFromTheDecksAndPlaysToAnEnd() throws Exception {
        for (int i = 0; i < 3; i++) {
            final int seat = i % 2;
            final List<String> pieces = RlSeedingTest.pieces(i, seat, 2);
            final JsonObject sp = spec(seat, pieces, Arrays.asList("auto", "battlefield").subList(0, pieces.size()),
                    new int[] {0});
            final RlSeedingTest.Run r = RlSeedingTest.play("train", game(i, sp));
            Assert.assertNull(r.p.guardError);
            Assert.assertTrue(r.p.end.get("void").isJsonNull(), "void " + r.p.end.get("void"));
            final JsonObject st = r.p.end.getAsJsonObject("started");
            Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
            Assert.assertEquals(st.get("seat").getAsInt(), seat);
            Assert.assertEquals(st.get("active").getAsInt(), seat);
            Assert.assertEquals(st.get("turn").getAsInt(), 9);
            Assert.assertEquals(st.getAsJsonArray("missing").size(), 0);
            Assert.assertEquals(r.p.tape.getAsJsonObject("start_state").toString(), sp.toString());
            Assert.assertEquals(r.p.tape.get("started").toString(), st.toString());
            for (int s = 0; s < 2; s++) {
                final JsonObject so = st.getAsJsonArray("seats").get(s).getAsJsonObject();
                final int total = so.getAsJsonArray("hand").size() + so.getAsJsonArray("battlefield").size()
                        + so.getAsJsonArray("graveyard").size() + so.get("library").getAsInt();
                Assert.assertEquals(total, deckSize(i, s), "every card of the deck is still in the game, seat " + s);
                Assert.assertEquals(so.get("lands").getAsInt(), s == seat ? 5 : 4);
                Assert.assertEquals(so.get("life").getAsInt(), s == seat ? 20 : 10);
                Assert.assertEquals(so.getAsJsonArray("hand").size(), s == seat ? 4 : 3);
                Assert.assertEquals(so.getAsJsonArray("graveyard").size(), s == seat ? 2 : 1);
                Assert.assertEquals(r.probe.mulligans[s], 0);
            }
            final JsonObject mine = st.getAsJsonArray("seats").get(seat).getAsJsonObject();
            Assert.assertTrue(mine.getAsJsonArray("hand").toString().contains('"' + pieces.get(0) + '"'), mine.toString());
            if (pieces.size() > 1) {
                Assert.assertTrue(mine.getAsJsonArray("battlefield").toString().contains('"' + pieces.get(1) + '"'),
                        mine.toString());
                Assert.assertEquals(mine.getAsJsonArray("battlefield").size(), 6);
            }
            Assert.assertTrue(r.p.end.get("turns").getAsInt() >= 9, "the game goes on from turn 9");
            // the same GAME is the same game
            final RlSeedingTest.Run again = RlSeedingTest.play("train", game(i, sp));
            Assert.assertEquals(RlSeedingTest.digest(again), RlSeedingTest.digest(r));
            Assert.assertEquals(again.p.end.get("started").toString(), st.toString());
        }
    }

    @Test
    public void aPieceOneStepAwayIsOnTopOfTheLibrary() throws Exception {
        final List<String> pieces = RlSeedingTest.pieces(3, 0, 2);
        final JsonObject sp = spec(0, pieces, Arrays.asList("auto", "auto").subList(0, pieces.size()), new int[] {1});
        final RlSeedingTest.Run r = RlSeedingTest.play("train", game(3, sp));
        final JsonObject st = r.p.end.getAsJsonObject("started");
        Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
        Assert.assertEquals(st.getAsJsonArray("missing").size(), 1);
        final String away = st.getAsJsonArray("missing").get(0).getAsString();
        Assert.assertEquals(st.getAsJsonObject("placed").get(away).getAsString(), "library");
        Assert.assertFalse(st.getAsJsonArray("seats").get(0).getAsJsonObject().getAsJsonArray("hand").toString()
                .contains('"' + away + '"'));
    }

    @Test
    public void aMissingPieceLeavesThePositionOff() throws Exception {
        final JsonObject sp = spec(0, Arrays.asList("No Such Card"), Arrays.asList("auto"), new int[] {0});
        final RlSeedingTest.Run r = RlSeedingTest.play("train", game(1, sp));
        Assert.assertNull(r.p.guardError);
        Assert.assertTrue(r.p.end.get("void").isJsonNull());
        final JsonObject st = r.p.end.getAsJsonObject("started");
        Assert.assertFalse(st.get("applied").getAsBoolean());
        Assert.assertEquals(st.get("reason").getAsString(), "missing");
    }

    @Test
    public void badSpecsAndEvalAreRefused() throws Exception {
        final List<String> pieces = RlSeedingTest.pieces(0, 0, 1);
        final JsonObject ok = spec(0, pieces, Arrays.asList("auto"), new int[] {0});
        final JsonObject extra = ok.deepCopy();
        extra.addProperty("rule", "x");
        Assert.assertNotNull(RlSeedingTest.play("train", game(0, extra)).p.guardError);
        final JsonObject lacks = ok.deepCopy();
        lacks.remove("lands");
        Assert.assertNotNull(RlSeedingTest.play("train", game(0, lacks)).p.guardError);
        final JsonObject place = ok.deepCopy();
        place.add("place", new JsonArray());
        Assert.assertNotNull(RlSeedingTest.play("train", game(0, place)).p.guardError);
        final JsonObject both = game(0, ok);
        both.add("seeding", RlSeedingTest.spec(1.0, new int[] {1}, 8, RlSeedingTest.seat("r", pieces), null));
        Assert.assertNotNull(RlSeedingTest.play("train", both).p.guardError);
    }
}
