package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Castable position starts (lane sdcc-startfix-1113, {@link RlStartState}'s optional key {@code cast}): the lands of
 * the assembled seat are chosen so that the cards it still has to cast can be paid, judged by Forge's own cost
 * payment; a start is left out only when no choice of lands pays the coloured pips. Plays real Forge games (Forge
 * seats) on small decks written here (card names appear only in these fixtures): cwd = forge-gui with res/.
 */
public class RlStartCastTest {

    static Path bank;
    static List<String[]> pairs;
    static final List<String> PODS = new ArrayList<>();

    private static void deck(final JsonArray manifest, final String pod, final String main) throws Exception {
        final String opp = "17 Mountain\n23 Lightning Bolt\n"; // a short game: the fixtures need the start, not a long combat
        for (int s = 0; s < 2; s++) {
            final String file = pod + "-p" + s + ".dck";
            final Path p = bank.resolve("decks").resolve(file);
            Files.write(p, ("[metadata]\nName=" + pod + "-p" + s + "\n[Main]\n" + (s == 0 ? main : opp))
                    .getBytes(StandardCharsets.UTF_8));
            manifest.add(RlActorBenchTest.entry(file, p));
        }
        PODS.add(pod);
    }

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlSeedingTest.root == null) {
            RlSeedingTest.setUp();
        }
        bank = RlSeedingTest.root.resolve("data/train/cast13");
        Files.createDirectories(bank.resolve("decks"));
        final JsonArray m = new JsonArray();
        // pods in name order = pair order
        deck(m, "c00-mono", "17 Swamp\n1 Doomsday\n1 Juggernaut\n1 Gitaxian Probe\n1 Griselbrand\n19 Ornithopter\n");
        deck(m, "c01-triple", "3 Swamp\n14 Island\n1 Doomsday\n22 Ornithopter\n");
        deck(m, "c02-twocol", "4 Mountain\n4 Plains\n9 Island\n1 Boros Charm\n1 Counterspell\n21 Ornithopter\n");
        deck(m, "c03-hybrid", "2 Forest\n2 Plains\n13 Island\n1 Kitchen Finks\n22 Ornithopter\n");
        deck(m, "c04-colourless", "3 Wastes\n14 Island\n1 Thought-Knot Seer\n22 Ornithopter\n");
        deck(m, "c05-none", "17 Island\n1 Doomsday\n22 Ornithopter\n");
        deck(m, "c06-ritual", "17 Island\n1 Doomsday\n22 Dark Ritual\n");
        deck(m, "c07-fetch", "3 Swamp\n14 Polluted Delta\n1 Doomsday\n22 Ornithopter\n");
        deck(m, "c08-petal", "17 Island\n1 Lotus Petal\n1 Vampiric Tutor\n1 Tinker\n20 Ornithopter\n");
        RlActorBenchTest.manifest(bank, "cast13", "train", m);
        pairs = RlFakeRun.pairs(bank);
        Assert.assertEquals(pairs.size(), PODS.size());
    }

    static JsonObject spec(final List<String> pieces, final List<String> place, final int lands, final List<String> cast) {
        final JsonObject o = RlStartStateTest.spec(0, pieces, place, new int[] {0});
        o.add("lands", RlStartStateTest.arr(lands, lands));
        o.add("hand", RlStartStateTest.arr(5, 5));
        if (cast != null) {
            final JsonArray a = new JsonArray();
            for (String x : cast) a.add(x);
            o.add("cast", a);
        }
        return o;
    }

    /** Game j of pod k (its own seed), with the start spec. */
    static RlSeedingTest.Run play(final String pod, final int j, final JsonObject st) throws Exception {
        final int k = PODS.indexOf(pod);
        final JsonObject g = RlFakeRun.schedule(bank, k + j * pairs.size(), 1, RlSeedingTest.seed0,
                new String[] {"forge", "forge"}, false, false, 7000L).get(0);
        g.add("start_state", st);
        final RlSeedingTest.Run r = RlSeedingTest.play("train", g);
        Assert.assertNull(r.p.guardError, String.valueOf(r.p.guardError));
        Assert.assertTrue(r.p.end.get("void").isJsonNull(), "void " + r.p.end.get("void"));
        return r;
    }

    static JsonObject started(final RlSeedingTest.Run r) {
        return r.p.end.getAsJsonObject("started");
    }

    static int count(final JsonArray names, final String... any) {
        int n = 0;
        for (JsonElement e : names) {
            if (Arrays.asList(any).contains(e.getAsString())) n++;
        }
        return n;
    }

    static JsonArray board(final JsonObject st) {
        return st.getAsJsonArray("seats").get(0).getAsJsonObject().getAsJsonArray("battlefield");
    }

    static void assertWholeDeck(final JsonObject st, final int seat, final int size) {
        final JsonObject so = st.getAsJsonArray("seats").get(seat).getAsJsonObject();
        Assert.assertEquals(so.getAsJsonArray("hand").size() + so.getAsJsonArray("battlefield").size()
                + so.getAsJsonArray("graveyard").size() + so.get("library").getAsInt(), size, st.toString());
    }

    @Test
    public void aCastableDealIsLeftAsItIsAndIsTheSameGame() throws Exception {
        final List<String> pc = Arrays.asList("Doomsday");
        for (int j = 0; j < 2; j++) {
            final RlSeedingTest.Run off = play("c00-mono", j, spec(pc, Arrays.asList("auto"), 5, null));
            final RlSeedingTest.Run on = play("c00-mono", j, spec(pc, Arrays.asList("auto"), 5, pc));
            final JsonObject a = started(off);
            final JsonObject b = started(on);
            Assert.assertFalse(a.has("cast"), "no key, no report");
            Assert.assertEquals(b.getAsJsonObject("cast").get("lands").getAsString(), "dealt", b.toString());
            Assert.assertEquals(b.getAsJsonObject("cast").get("check").getAsString(), "now", b.toString());
            Assert.assertEquals(b.getAsJsonObject("cast").get("tried").getAsInt(), 1);
            final JsonObject bb = b.deepCopy();
            bb.remove("cast");
            Assert.assertEquals(bb.toString(), a.toString(), "the same deal");
            Assert.assertEquals(RlSeedingTest.digest(on), RlSeedingTest.digest(off), "the check does not move the game");
            Assert.assertEquals(on.p.tape.getAsJsonObject("start_state").getAsJsonArray("cast").toString(), "[\"Doomsday\"]");
        }
    }

    @Test
    public void aTriplePipPieceGetsItsThreeSources() throws Exception {
        final List<String> pc = Arrays.asList("Doomsday");
        int chosen = 0;
        for (int j = 0; j < 3; j++) {
            final RlSeedingTest.Run off = play("c01-triple", j, spec(pc, Arrays.asList("auto"), 5, null));
            final RlSeedingTest.Run on = play("c01-triple", j, spec(pc, Arrays.asList("auto"), 5, pc));
            final JsonObject st = started(on);
            Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
            final JsonObject c = st.getAsJsonObject("cast");
            final int before = count(board(started(off)), "Swamp");
            Assert.assertEquals(board(st).size(), 5);
            Assert.assertEquals(count(board(st), "Swamp"), 3, st.toString());
            Assert.assertEquals(c.get("check").getAsString(), "now");
            Assert.assertEquals(c.get("lands").getAsString(), before == 3 ? "dealt" : "chosen", st.toString());
            if (before < 3) {
                chosen++;
                Assert.assertEquals(c.getAsJsonArray("dealt").toString(), board(started(off)).toString());
                // the opponent's deal is the one it had
                Assert.assertEquals(st.getAsJsonArray("seats").get(1).toString(),
                        started(off).getAsJsonArray("seats").get(1).toString());
                Assert.assertEquals(st.get("turn").getAsInt(), started(off).get("turn").getAsInt());
            }
            assertWholeDeck(st, 0, 40);
            assertWholeDeck(st, 1, 40);
            Assert.assertEquals(st.getAsJsonArray("seats").get(0).getAsJsonObject().getAsJsonArray("hand").size(), 5);
            // the same GAME is the same game
            final RlSeedingTest.Run again = play("c01-triple", j, spec(pc, Arrays.asList("auto"), 5, pc));
            Assert.assertEquals(RlSeedingTest.digest(again), RlSeedingTest.digest(on));
            Assert.assertEquals(started(again).toString(), st.toString());
        }
        Assert.assertTrue(chosen > 0, "no seed of the three dealt fewer than three sources first");
    }

    @Test
    public void aTwoColourRouteGetsBothColoursAndALeftOutPieceCounts() throws Exception {
        // Boros Charm is placed in hand; Counterspell is left out (not a piece of the GAME object) but has to be cast
        final List<String> cast = Arrays.asList("Boros Charm", "Counterspell");
        int chosen = 0;
        for (int j = 0; j < 3; j++) {
            final JsonObject st = started(play("c02-twocol", j, spec(Arrays.asList("Boros Charm"), Arrays.asList("hand"), 5,
                    cast)));
            Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
            Assert.assertEquals(st.getAsJsonObject("cast").get("check").getAsString(), "now", st.toString());
            Assert.assertTrue(count(board(st), "Mountain") >= 1 && count(board(st), "Plains") >= 1
                    && count(board(st), "Island") >= 2, st.toString());
            Assert.assertEquals(board(st).size(), 5);
            chosen += "chosen".equals(st.getAsJsonObject("cast").get("lands").getAsString()) ? 1 : 0;
        }
        Assert.assertTrue(chosen > 0);
    }

    @Test
    public void hybridAndColourlessPips() throws Exception {
        for (int j = 0; j < 2; j++) {
            final JsonObject h = started(play("c03-hybrid", j, spec(Arrays.asList("Kitchen Finks"), Arrays.asList("auto"),
                    4, Arrays.asList("Kitchen Finks"))));
            Assert.assertTrue(h.get("applied").getAsBoolean(), h.toString());
            Assert.assertEquals(h.getAsJsonObject("cast").get("check").getAsString(), "now", h.toString());
            Assert.assertTrue(count(board(h), "Forest", "Plains") >= 2, "either colour pays a hybrid pip: " + h);
            final JsonObject c = started(play("c04-colourless", j, spec(Arrays.asList("Thought-Knot Seer"),
                    Arrays.asList("auto"), 4, Arrays.asList("Thought-Knot Seer"))));
            Assert.assertTrue(c.get("applied").getAsBoolean(), c.toString());
            Assert.assertEquals(c.getAsJsonObject("cast").get("check").getAsString(), "now", c.toString());
            Assert.assertTrue(count(board(c), "Wastes") >= 1, "a colourless pip needs a colourless source: " + c);
        }
        // generic and Phyrexian pips ask for no colour: the deal stands (a generic artifact; a spell payable with life)
        final JsonObject g = started(play("c00-mono", 2, spec(Arrays.asList("Juggernaut", "Gitaxian Probe"),
                Arrays.asList("auto", "auto"), 5, Arrays.asList("Juggernaut", "Gitaxian Probe"))));
        Assert.assertTrue(g.get("applied").getAsBoolean(), g.toString());
        Assert.assertEquals(g.getAsJsonObject("cast").get("lands").getAsString(), "dealt", g.toString());
        Assert.assertEquals(g.getAsJsonObject("cast").get("check").getAsString(), "now", g.toString());
    }

    @Test
    public void tooFewLandsForTheManaValueIsDealt() throws Exception {
        // eight mana with five lands: the coloured pips are payable, the amount is not: later land drops are not this
        // rule's business, the start is dealt
        final JsonObject st = started(play("c00-mono", 3, spec(Arrays.asList("Griselbrand"), Arrays.asList("auto"), 5,
                Arrays.asList("Griselbrand"))));
        Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
        Assert.assertEquals(st.getAsJsonObject("cast").get("lands").getAsString(), "dealt");
        Assert.assertEquals(st.getAsJsonObject("cast").get("check").getAsString(), "colours", st.toString());
        Assert.assertEquals(st.getAsJsonObject("cast").getAsJsonObject("short").get("card").getAsString(), "Griselbrand");
    }

    @Test
    public void aLibraryThatCannotSupplyTheColourLeavesTheStartOff() throws Exception {
        final List<String> pc = Arrays.asList("Doomsday");
        final RlSeedingTest.Run r = play("c05-none", 0, spec(pc, Arrays.asList("auto"), 5, pc));
        final JsonObject st = started(r);
        Assert.assertFalse(st.get("applied").getAsBoolean(), st.toString());
        Assert.assertEquals(st.get("reason").getAsString(), "uncastable");
        Assert.assertEquals(st.get("card").getAsString(), "Doomsday");
        Assert.assertEquals(st.getAsJsonArray("tried").size(), 1, "every land is the same: one choice");
        Assert.assertEquals(st.getAsJsonArray("library_lands").size(), 17);
        // the game is the ordinary game from turn 1 of the same GAME with a start that is not applied
        final RlSeedingTest.Run miss = play("c05-none", 0, spec(Arrays.asList("No Such Card"), Arrays.asList("auto"), 5, null));
        Assert.assertEquals(started(miss).get("reason").getAsString(), "missing");
        Assert.assertEquals(RlSeedingTest.digest(r), RlSeedingTest.digest(miss), "the fall-back is the existing one");
        // without the key the same start is dealt as before
        Assert.assertTrue(started(play("c05-none", 0, spec(pc, Arrays.asList("auto"), 5, null))).get("applied").getAsBoolean());
    }

    @Test
    public void manaTheRuleDoesNotModelDealsTheStart() throws Exception {
        // no black source in the deck, but rituals in the dealt hand: not known to the rule, so it is dealt
        final List<String> pc = Arrays.asList("Doomsday");
        for (int j = 0; j < 2; j++) {
            final JsonObject st = started(play("c06-ritual", j, spec(pc, Arrays.asList("auto"), 5, pc)));
            final JsonArray hand = st.has("seats") ? st.getAsJsonArray("seats").get(0).getAsJsonObject().getAsJsonArray("hand")
                    : st.getAsJsonArray("hand");
            if (count(hand, "Dark Ritual") > 0) {
                Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
                Assert.assertEquals(st.getAsJsonObject("cast").get("check").getAsString(), "mana_in_hand", st.toString());
            } else {
                Assert.assertEquals(st.get("reason").getAsString(), "uncastable", st.toString());
            }
        }
        // a mana permanent the start itself places counts with the colours it can make (a rock that sacrifices itself)
        final JsonObject p = started(play("c08-petal", 0, spec(Arrays.asList("Lotus Petal", "Vampiric Tutor"),
                Arrays.asList("battlefield", "hand"), 4, Arrays.asList("Vampiric Tutor"))));
        Assert.assertTrue(p.get("applied").getAsBoolean(), p.toString());
        Assert.assertEquals(p.getAsJsonObject("cast").get("lands").getAsString(), "dealt", p.toString());
        Assert.assertEquals(p.getAsJsonObject("cast").get("check").getAsString(), "now", p.toString());
    }

    @Test
    public void anAdditionalCostIsNotTheRulesBusiness() throws Exception {
        // a spell whose additional cost (sacrifice an artifact) cannot be met in the position: the mana is there, dealt
        final JsonObject st = started(play("c08-petal", 2, spec(Arrays.asList("Tinker"), Arrays.asList("auto"), 4,
                Arrays.asList("Tinker"))));
        Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
        Assert.assertEquals(st.getAsJsonObject("cast").get("check").getAsString(), "now", st.toString());
    }

    @Test
    public void aLandThatFetchesALandCountsAsThatLand() throws Exception {
        final List<String> pc = Arrays.asList("Doomsday");
        for (int j = 0; j < 2; j++) {
            final JsonObject st = started(play("c07-fetch", j, spec(pc, Arrays.asList("auto"), 5, pc)));
            Assert.assertTrue(st.get("applied").getAsBoolean(), st.toString());
            // judged with each fetch land replaced by a land it can fetch from what is left in the library: castable
            // now, whether as dealt or with lands chosen; the fetch lands count as sources for the choice
            Assert.assertEquals(st.getAsJsonObject("cast").get("check").getAsString(), "now", st.toString());
            Assert.assertTrue(count(board(st), "Swamp") + count(board(st), "Polluted Delta") == 5, st.toString());
        }
    }

    @Test
    public void badCastListsAreRefusedAndAMissingCardLeavesTheStartOff() throws Exception {
        final JsonObject bad = spec(Arrays.asList("Doomsday"), Arrays.asList("auto"), 5, null);
        bad.addProperty("cast", "Doomsday");
        final int k = PODS.indexOf("c00-mono");
        final JsonObject g = RlFakeRun.schedule(bank, k, 1, RlSeedingTest.seed0, new String[] {"forge", "forge"}, false,
                false, 7000L).get(0);
        g.add("start_state", bad);
        Assert.assertNotNull(RlSeedingTest.play("train", g).p.guardError);
        final JsonObject st = started(play("c00-mono", 0, spec(Arrays.asList("Doomsday"), Arrays.asList("auto"), 5,
                Arrays.asList("Doomsday", "No Such Card"))));
        Assert.assertFalse(st.get("applied").getAsBoolean());
        Assert.assertEquals(st.get("reason").getAsString(), "missing");
        Assert.assertEquals(st.get("piece").getAsString(), "No Such Card");
        // a land, and a card the spec places on the battlefield, are not cast: nothing to check
        final JsonObject none = started(play("c08-petal", 1, spec(Arrays.asList("Lotus Petal"), Arrays.asList("battlefield"),
                4, Arrays.asList("Lotus Petal", "Island"))));
        Assert.assertTrue(none.get("applied").getAsBoolean(), none.toString());
        Assert.assertEquals(none.getAsJsonObject("cast").get("check").getAsString(), "none");
    }
}
