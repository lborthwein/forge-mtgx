package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.PlayerControllerBridge;
import forge.bench.rl.BvwStepSearchSpecTest;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.PlanSearch;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlWire;
import forge.bench.rl.StepSearch;
import forge.game.Game;
import forge.game.player.Player;
import forge.util.IdScope;

/**
 * Lane bvw-step2-fork-1027, the S2T HONESTY INVARIANT: no player's decision and no network input reads a hidden card;
 * the real hidden cards only seed the starting state of the T re-play and the OPR roll-outs.
 *
 * <p>The test service's policy is a function of the DECIDE frame alone (a hash of its bytes), every DECIDE it answers
 * is recorded by seat, and its leaf values are a hash of the leaf frame. Then:
 * <ol>
 *   <li><b>Belief worlds (H).</b> With every S2T play-out key on (the opponent is the policy seat, the symmetric leaf,
 *       the co-draft belief), the search of a position is identical when the opponent's real hand and library are other
 *       cards and our library is permuted.</li>
 *   <li><b>True worlds (T, OPR), our seat.</b> The opponent passes at every priority (and answers its other asks by a
 *       fixed draw over its public candidates), so its hidden cards never become public. With other real cards for it,
 *       every DECIDE our seat sends (belief and true worlds, T re-play and OPR roll-outs) is byte-identical, and so are
 *       our seat's leaf values in the true worlds and the OPR results. Our seat's Forge-decided sub-asks (mana, ...)
 *       show up in its later frames, so a decision path that read their cards would show here.</li>
 *   <li><b>True worlds, the opponent's seat.</b> The same with the seats' roles exchanged: our seat passes (a constant
 *       leaf, so the search keeps its pass), our real hidden hand cards (never castable here) and library are other
 *       cards, and every DECIDE of the OPPONENT's seat and its leaf values in the true worlds are identical.</li>
 * </ol>
 * Controls: a VISIBLE change makes the frames differ (the test can see), and the MUTANT {@link RlSearch#TEST_LEAK_PRIV}
 * (the play-out seats' frames carry the hidden cards, a decision input that reads them) makes cases 2 and 3 FAIL.
 */
public class BvwS2tHonestyTest extends SimulationTest {

    static final String SHA = "a".repeat(64);
    static final String PRIOR_SHA = "70521d1fdc88fb134fb34e61e9c41fbf0bcabb6538a7d96c93aacbc205967613";

    static String frame(final String ourHand, final String ourLib, final String ourBf, final String theirHand,
            final String theirLib, final String theirBf) {
        return "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\nhumanlandsplayed=0\n"
                + "ailandsplayed=0\naihand=" + theirHand + "\nailibrary=" + theirLib + "\naigraveyard=\naiexile=\n"
                + "aibattlefield=" + theirBf + "\nhumanhand=" + ourHand + "\nhumanbattlefield=" + ourBf + "\n"
                + "humangraveyard=\nhumanexile=\nhumanlibrary=" + ourLib + "\n";
    }

    static final String OUR_HAND = "Grizzly Bears;Lightning Bolt;Llanowar Elves";
    static final String OUR_LIB = "Island;Forest;Mountain;Swamp;Plains;Shock;Island;Forest;Hill Giant;Giant Growth";
    static final String OUR_LIB_B = "Plains;Swamp;Craw Wurm;Island;Island;Mountain;Forest;Dark Ritual;Serra Angel;Forest";
    static final String OUR_LIB_PERMUTED = "Giant Growth;Plains;Shock;Island;Hill Giant;Swamp;Forest;Island;Mountain;Forest";
    static final String OUR_BF = "Forest;Forest;Mountain";
    static final String THEIR_BF = "Mountain;Mountain;Grizzly Bears";
    static final String THEIR_HAND_A = "Counterspell;Hill Giant;Craw Wurm";
    static final String THEIR_LIB_A = "Raging Goblin;Island;Island;Shock;Giant Growth;Mountain;Swamp;Plains";
    static final String THEIR_HAND_B = "Serra Angel;Dark Ritual;Island";
    static final String THEIR_LIB_B = "Craw Wurm;Counterspell;Hill Giant;Forest;Lightning Bolt;Llanowar Elves;Swamp;Island";

    /**
     * An S2T block (the S-b search of the step-1 honesty test, horizon 2, K 8): opponent "policy", the co-draft belief,
     * the variant's leaf; T-sym's true worlds k = 4; OPR k 2, 3 turns; {@code smoke} logs every variant and the pairs.
     */
    static StepSearch.Spec spec(final int port, final String variant, final boolean smoke, final boolean opr) {
        final JsonObject b = JsonParser.parseString("{\"rlsearch\":{\"server\":\"127.0.0.1:" + port + "\",\"policySha\":\""
                + SHA + "\",\"worlds\":8,\"breadth\":3,\"horizon\":2,\"leaf\":\"value\",\"playout\":\"policy\",\"threads\":1,"
                + "\"belief\":\"codraft\",\"beliefPrior\":\"" + PlanSearch.DEFEND_PRIOR + "\",\"beliefPriorSha256\":\""
                + PRIOR_SHA + "\",\"beliefPriorGain\":20,\"opponent\":\"policy\",\"leafSym\":" + !"H-asym".equals(variant)
                + ",\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE + "\",\"beliefCubeSha256\":\"" + BeliefSampler.LIVE_CUBE_SHA256
                + "\",\"beliefSingleton\":false,\"plans\":{\"samples\":2,"
                + "\"minProvenWorlds\":8,\"solverCfg\":{\"budget\":\"counter\",\"hideOwnLibrary\":\"true\",\"worlds\":\"8\","
                + "\"engineSteps\":\"500\",\"evalSteps\":\"500\",\"engineCards\":\"8000\",\"enginePathCards\":\"8000\"}}},"
                + "\"control\":true,\"maxCopyCards\":400000}").getAsJsonObject();
        final JsonObject t = new JsonObject();
        t.addProperty("variant", variant);
        if ("T-sym".equals(variant)) {
            t.addProperty("k", 4);
        }
        b.add("target", t);
        if (opr) {
            b.add("opr", JsonParser.parseString("{\"k\":2,\"maxTurns\":3,\"maxSteps\":4000}"));
        }
        if (smoke) {
            b.addProperty("smoke", true);
        }
        return StepSearch.Spec.parse(b, BvwStepSearchSpecTest.label(), 3, 5);
    }

    static String sha(final byte[] b) {
        try {
            final StringBuilder sb = new StringBuilder();
            for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) {
                sb.append(String.format("%02x", x & 0xff));
            }
            return sb.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A policy that is a function of its frame alone. Seat {@code passive} (-1 = none) passes at every priority and
     * answers every other ask by a draw seeded by its family and decision index (its candidates are public here);
     * every other seat answers by a draw seeded by its frame's bytes. Every DECIDE is recorded as "seat:sha".
     */
    static final class Policy implements java.util.function.BiFunction<RlWire.Decide, byte[], short[]> {
        final int passive;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        Policy(final int passive) {
            this.passive = passive;
        }

        @Override
        public short[] apply(final RlWire.Decide d, final byte[] raw) {
            log.add(d.seat + ":" + sha(raw));
            if (d.seat == passive) {
                if (d.family == RlSchema.F_PRIORITY) {
                    for (int i = 0; i < d.C; i++) {
                        if (d.candKind[i] == RlSchema.K_PASS) {
                            return new short[] {(short) i};
                        }
                    }
                }
                return FakeRlServer.randomSteps(d, new Random(FakeRlServer.mix(17, d.family, d.decIdx)));
            }
            return FakeRlServer.randomSteps(d, new Random(Arrays.hashCode(raw)));
        }

        List<String> of(final int seat) {
            final List<String> out = new ArrayList<>();
            synchronized (log) {
                for (String s : log) {
                    if (s.startsWith(seat + ":")) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
    }

    /** Leaf values: a hash of the frame, except seat {@code constSeat}'s (-1 = none), which are 0.2 (P(win) 0.6). */
    static java.util.function.ToDoubleFunction<byte[]> leaves(final int constSeat) {
        return p -> RlWire.decodeDecide(p, 2).seat == constSeat ? 0.2 : (Arrays.hashCode(p) % 1000) / 1000.0;
    }

    static final class Run {
        JsonObject search, s2t;
        List<String> seat0, seat1;
        String why;
    }

    /** Two registered decklists (review #123 S1-1): a decklist is a list of hidden cards for the other seat. */
    static final String DECK_A = "Counterspell;Hill Giant;Craw Wurm;Raging Goblin;Island;Island;Shock;Giant Growth;"
            + "Mountain;Mountain;Mountain;Swamp;Plains;Grizzly Bears";
    static final String DECK_B = "Serra Angel;Dark Ritual;Island;Craw Wurm;Lightning Bolt;Llanowar Elves;Forest;Forest;"
            + "Swamp;Swamp;Mountain;Mountain;Hill Giant;Shivan Dragon";

    /** Sets seat {@code seat}'s registered main deck to {@code names} (";"-separated; null = leave it). */
    static void deck(final Game g, final int seat, final String names) {
        if (names == null) {
            return;
        }
        final forge.deck.CardPool main = g.getRegisteredPlayers().get(seat).getRegisteredPlayer().getDeck()
                .getOrCreate(forge.deck.DeckSection.Main);
        main.clear();
        for (String n : names.split(";")) {
            main.add(forge.StaticData.instance().getCommonCards().getCard(n));
        }
    }

    static Run run(final StepSearch ss, final Policy pol, final String state, final boolean opr) {
        return run(ss, pol, state, opr, null, null);
    }

    /** Every player's Forge fair naming (step-3 hygiene: a true world needs it ON for every player). */
    static void fairNaming(final Game g, final forge.ai.AiFixes.Mode mode) {
        for (Player p : g.getRegisteredPlayers()) {
            ((forge.ai.LobbyPlayerAi) p.getLobbyPlayer()).setFairNaming(mode);
        }
    }

    static Run run(final StepSearch ss, final Policy pol, final String state, final boolean opr, final String ourDeck,
            final String theirDeck) {
        pol.log.clear();
        final Game g = ForcedWinSolverTest.game("Default", "Default", state);
        deck(g, 0, ourDeck);
        deck(g, 1, theirDeck);
        fairNaming(g, forge.ai.AiFixes.Mode.ON);
        final Player me = g.getPlayers().get(0);
        final RlKnowledge know = new RlKnowledge(g);
        know.v2 = true;
        know.attach();
        final String before = LookaheadSearch.fingerprint(g);
        final StepSearch.Result r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(
                () -> ss.value(g, me, know, 2988000001L, 4242L, opr, false)));
        Assert.assertEquals(LookaheadSearch.fingerprint(g), before, "the search moved the game");
        final Run out = new Run();
        out.search = r.toJson();
        out.search.remove("ms");
        out.s2t = r.s2t == null ? null : r.s2t.toJson("test");
        out.seat0 = pol.of(0);
        out.seat1 = pol.of(1);
        out.why = r.why;
        return out;
    }

    /** Column {@code col} (0 = v_s, 1 = v_o, 2 = terminal) of a per-world pairs list. */
    static List<String> column(final JsonObject s2t, final String key, final int col) {
        final List<String> out = new ArrayList<>();
        Assert.assertTrue(s2t.has(key), key + " missing: " + s2t);
        for (com.google.gson.JsonElement w : s2t.getAsJsonArray(key)) {
            out.add(String.valueOf(((JsonArray) w).get(col)));
        }
        return out;
    }

    @Test(timeOut = 1800000)
    public void beliefWorldSearchWithEveryS2tKeyDoesNotDependOnHiddenCards() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            final Policy pol = new Policy(-1);
            svc.scriptRaw = pol;
            svc.leafValue = leaves(-1);
            final StepSearch ss = new StepSearch(spec(svc.port(), "H-sym", false, false), BvwStepSearchHonestyTest.index(), 2,
                    "test", "s2t-honesty-h");
            final Run real = run(ss, pol, frame(OUR_HAND, OUR_LIB, OUR_BF, THEIR_HAND_A, THEIR_LIB_A, THEIR_BF), false, DECK_A,
                    DECK_A);
            System.err.println("[s2t-honesty H] " + real.search + " " + real.s2t + " decides " + real.seat0.size() + "/"
                    + real.seat1.size());
            Assert.assertTrue("searched".equals(real.why) || "plan".equals(real.why), real.search.toString());
            Assert.assertFalse(real.seat1.isEmpty(), "the opponent's play-out seat never asked the policy");
            // other real hidden cards for them (hand, library AND their registered decklist), our library permuted
            final String other = frame(OUR_HAND, OUR_LIB_PERMUTED, OUR_BF, THEIR_HAND_B, THEIR_LIB_B, THEIR_BF);
            final Run hidden = run(ss, pol, other, false, DECK_A, DECK_B);
            assertSame(hidden, real);
            // their decklist alone (review #123 S1-1)
            assertSame(run(ss, pol, frame(OUR_HAND, OUR_LIB, OUR_BF, THEIR_HAND_A, THEIR_LIB_A, THEIR_BF), false, DECK_A,
                    DECK_B), real);
            final Run visible = run(ss, pol, frame("Grizzly Bears;Shock;Llanowar Elves", OUR_LIB, OUR_BF, THEIR_HAND_A,
                    THEIR_LIB_A, THEIR_BF), false, DECK_A, DECK_A);
            Assert.assertNotEquals(visible.seat0, real.seat0, "a visible change left the frames unchanged: the test cannot see");
            // the deck mutant: belief worlds keep the real decklist -> the opponent seat's frames read it, and this fails
            LookaheadSearch.TEST_REAL_DECKS = true;
            try {
                final Run ma = run(ss, pol, frame(OUR_HAND, OUR_LIB, OUR_BF, THEIR_HAND_A, THEIR_LIB_A, THEIR_BF), false,
                        DECK_A, DECK_A);
                final Run mb = run(ss, pol, other, false, DECK_A, DECK_B);
                boolean caught = false;
                try {
                    assertSame(mb, ma);
                } catch (AssertionError e) {
                    caught = true;
                    System.err.println("[s2t-honesty] the deck mutant is caught: " + e.getMessage());
                }
                Assert.assertTrue(caught, "the honesty check did not catch belief worlds that keep the real decklist");
            } finally {
                LookaheadSearch.TEST_REAL_DECKS = false;
            }
        }
    }

    @Test(timeOut = 1800000)
    public void trueWorldDecisionsOfOurSeatAreBlindToTheOpponentsHiddenCards() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            final Policy pol = new Policy(1);
            svc.scriptRaw = pol;
            svc.leafValue = leaves(-1);
            final StepSearch ss = new StepSearch(spec(svc.port(), "T-sym", true, true), BvwStepSearchHonestyTest.index(), 2,
                    "test", "s2t-honesty-t0");
            final String sa = frame(OUR_HAND, OUR_LIB, OUR_BF, THEIR_HAND_A, THEIR_LIB_A, THEIR_BF);
            final String sb = frame(OUR_HAND, OUR_LIB, OUR_BF, THEIR_HAND_B, THEIR_LIB_B, THEIR_BF);
            final Run a = run(ss, pol, sa, true, DECK_A, DECK_A);
            System.err.println("[s2t-honesty T0] " + a.search + " " + a.s2t + " decides " + a.seat0.size() + "/"
                    + a.seat1.size());
            Assert.assertTrue(a.s2t.has("true_worlds"), "the T re-play did not run: " + a.s2t);
            Assert.assertTrue(a.s2t.get("opr").isJsonObject(), "OPR did not run: " + a.s2t);
            Assert.assertFalse(a.seat0.isEmpty());
            Assert.assertFalse(a.seat1.isEmpty(), "the opponent's play-out seat never asked the policy");
            // their other real hand, library and decklist
            final Run b = run(ss, pol, sb, true, DECK_A, DECK_B);
            assertBlind(a, b, 0);
            // the visible control: a creature on their battlefield changes our frames
            final Run v = run(ss, pol, frame(OUR_HAND, OUR_LIB, OUR_BF, THEIR_HAND_A, THEIR_LIB_A, THEIR_BF + ";Hill Giant"),
                    true, DECK_A, DECK_A);
            Assert.assertNotEquals(v.seat0, a.seat0, "a visible change left our frames unchanged: the test cannot see");
            // the mutant: our play-out seat's frames read the hidden cards (privileged block) -> this check must fail
            assertMutantCaught(ss, pol, sa, sb, 0, DECK_A, DECK_A, DECK_A, DECK_B);
        }
    }

    @Test(timeOut = 1800000)
    public void trueWorldDecisionsOfTheOpponentAreBlindToOurHiddenCards() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            final Policy pol = new Policy(0);
            svc.scriptRaw = pol;
            svc.leafValue = leaves(0);
            // H-asym: our constant leaf keeps the policy's pass; smoke runs the T re-play and logs the opponent's v_o
            final StepSearch ss = new StepSearch(spec(svc.port(), "H-asym", true, true), BvwStepSearchHonestyTest.index(),
                    2, "test", "s2t-honesty-t1");
            // our hidden cards: Shock (castable, the same in both) and two we cannot cast; our library's cards
            final String sa = frame("Shock;Serra Angel;Craw Wurm", OUR_LIB, OUR_BF, THEIR_HAND_A, THEIR_LIB_A, THEIR_BF);
            final String sb = frame("Shock;Shivan Dragon;Mahamoti Djinn", OUR_LIB_B, OUR_BF, THEIR_HAND_A, THEIR_LIB_A,
                    THEIR_BF);
            final Run a = run(ss, pol, sa, true, DECK_A, DECK_A);
            System.err.println("[s2t-honesty T1] " + a.search + " " + a.s2t + " decides " + a.seat0.size() + "/"
                    + a.seat1.size());
            Assert.assertFalse(a.search.get("departed").getAsBoolean(), "our constant leaf must keep the pass: " + a.search);
            Assert.assertTrue(a.s2t.has("true_worlds"), "the T re-play did not run: " + a.s2t);
            Assert.assertTrue(a.s2t.get("opr").isJsonObject(), "OPR did not run: " + a.s2t);
            Assert.assertFalse(a.seat1.isEmpty(), "the opponent's play-out seat never asked the policy");
            // our other real hand, library and decklist
            final Run b = run(ss, pol, sb, true, DECK_B, DECK_A);
            assertBlind(a, b, 1);
            final Run v = run(ss, pol, frame("Shock;Serra Angel;Craw Wurm", OUR_LIB, OUR_BF + ";Hill Giant", THEIR_HAND_A,
                    THEIR_LIB_A, THEIR_BF), true, DECK_A, DECK_A);
            Assert.assertNotEquals(v.seat1, a.seat1, "a visible change left their frames unchanged: the test cannot see");
            assertMutantCaught(ss, pol, sa, sb, 1, DECK_A, DECK_A, DECK_B, DECK_A);
        }
    }

    /** Belief worlds only: the whole search, its s2t object and every DECIDE of both seats are the same. */
    static void assertSame(final Run b, final Run a) {
        Assert.assertEquals(b.search, a.search, "the S2T belief-world search depends on hidden cards");
        Assert.assertEquals(b.s2t, a.s2t, "the S2T belief-world variants depend on hidden cards");
        Assert.assertEquals(b.seat0, a.seat0, "our seat's frames depend on hidden cards");
        Assert.assertEquals(b.seat1, a.seat1, "the opponent seat's frames depend on the real hidden cards");
    }

    /** Seat {@code seat}'s decisions and leaf values are the same in runs a and b (only the OTHER seat's hidden cards differ). */
    static void assertBlind(final Run a, final Run b, final int seat) {
        Assert.assertEquals(seat == 0 ? b.seat0 : b.seat1, seat == 0 ? a.seat0 : a.seat1,
                "seat " + seat + "'s DECIDE frames read the other seat's hidden cards");
        // its own leaf values in the true worlds (v_s for our seat, v_o for theirs) and the OPR results
        Assert.assertEquals(column(b.s2t, "true_worlds", seat), column(a.s2t, "true_worlds", seat),
                "seat " + seat + "'s true-world leaf frames read the other seat's hidden cards");
        Assert.assertEquals(column(b.s2t, "true_worlds", 2), column(a.s2t, "true_worlds", 2));
        Assert.assertEquals(b.s2t.get("opr"), a.s2t.get("opr"), "the OPR roll-outs read hidden cards");
    }

    /** The mutant (a play-out seat's frames carry the hidden cards) must make {@link #assertBlind} fail. */
    static void assertMutantCaught(final StepSearch ss, final Policy pol, final String sa, final String sb, final int seat,
            final String ourA, final String theirA, final String ourB, final String theirB) {
        RlSearch.TEST_LEAK_PRIV = true;
        try {
            final Run ma = run(ss, pol, sa, true, ourA, theirA);
            final Run mb = run(ss, pol, sb, true, ourB, theirB);
            boolean caught = false;
            try {
                assertBlind(ma, mb, seat);
            } catch (AssertionError e) {
                caught = true;
                System.err.println("[s2t-honesty] the mutant is caught: " + e.getMessage());
            }
            Assert.assertTrue(caught, "the honesty check did not catch a decision input that reads the hidden cards");
        } finally {
            RlSearch.TEST_LEAK_PRIV = false;
        }
    }
}
