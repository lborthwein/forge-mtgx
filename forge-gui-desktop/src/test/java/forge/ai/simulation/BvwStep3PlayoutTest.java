package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.BvwStepSearchSpecTest;
import forge.bench.rl.CardIndex;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.PlanSearch;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlWire;
import forge.bench.rl.StepSearch;

/**
 * Lane step3-playout-fixes-1028 (BVW step 3): the search play-out seats' fixes from the step-2 smoke diagnosis, each
 * behind its own {@code rlsearch} key, on fixture positions built from real capped smoke rows (their decks).
 * <ul>
 *   <li><b>Loop guard</b> ({@code playoutLoopGuard}): s-0091 decisions 187-201 (cur-sneak-show-0022 vs cur-oath-0089):
 *       the opponent holds Show and Tell; in the T re-play (true worlds keep its hand) it casts it and its policy seat
 *       answers "no card" and "Cancel Search? no" for ever. Off: the T re-play fails at the seat's 3,000 decisions
 *       (t_why "other opponent seat: max_decisions", no T-sym target). On (K 2): the guard answers the confirm yes,
 *       the T re-play scores and the row has its target. s-0003 decision 320 (cur-kitten-0082 vs
 *       cur-breach-storm-0012): our own Polluted Delta's library search loops in every world (belief and true); off:
 *       no world finishes and the row has no target; on: it does.</li>
 *   <li><b>Determinism</b>: the guard draws nothing; two keys-on runs are identical (the row JSON, the counts).</li>
 *   <li><b>Backstop</b> ({@code playoutMaxDecisions}): with the cap alone the looping worlds still fail
 *       ({@code max_decisions}, honest: no target), at the reduced cap; with the guard too, the cap never binds and the
 *       row equals the guard-only row bit for bit.</li>
 *   <li><b>Re-play may-play</b> ({@code replayMayPlayLoose}): a face-up exiled may-play card (Brazen Borrower on an
 *       adventure, cur-kitten-0082): the T re-play's level-0 copy names the effect card as its source, so off the
 *       candidate is not in the copy (candidate_not_in_copy) and on it is found by its base description.</li>
 *   <li><b>Identity</b>: every key off leaves the spec JSON and its sha unchanged; each key on changes the sha.</li>
 * </ul>
 */
public class BvwStep3PlayoutTest extends SimulationTest {

    static final String SHA = BvwS2tHonestyTest.SHA;
    static final String[] NAMES = {"Island", "Plains", "Swamp", "Forest", "Mountain", "Show and Tell", "Blightsteel Colossus",
            "Griselbrand", "Snapcaster Mage", "Polluted Delta", "Trinket Mage", "Counterspell", "Brazen Borrower",
            "Grizzly Bears", "Hill Giant", "Llanowar Elves", "Lightning Bolt", "Dark Ritual", "Deep-Cavern Bat"};

    static CardIndex index() {
        final TreeSet<String> names = new TreeSet<>(java.util.Arrays.asList(NAMES));
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : names) sb.append(n).append('\t').append(i++).append('\n');
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A greedy policy that loops: at a priority it plays the first legal candidate whose card is {@code want} (else it
     * passes); every card ask is answered with no card and every confirm with no (the step-2 smoke's loop); any other
     * ask by a draw seeded by its family and decision index. A function of the frame only.
     */
    static final class Looper implements java.util.function.BiFunction<RlWire.Decide, byte[], short[]> {
        final int[] want;
        final java.util.concurrent.atomic.AtomicLong cards = new java.util.concurrent.atomic.AtomicLong(),
                confirms = new java.util.concurrent.atomic.AtomicLong();

        Looper(final CardIndex idx, final String... want) {
            this.want = new int[want.length];
            for (int i = 0; i < want.length; i++) {
                this.want[i] = idx.resolve(want[i]);
            }
        }

        @Override
        public short[] apply(final RlWire.Decide d, final byte[] raw) {
            if (d.family == RlSchema.F_PRIORITY) {
                for (int w : want) {
                    for (int i = 0; i < d.C; i++) {
                        if (d.candKind[i] > 0 && d.candKind[i] != RlSchema.K_PASS && d.candCard[i] == w) {
                            return new short[] {(short) i};
                        }
                    }
                }
                for (int i = 0; i < d.C; i++) {
                    if (d.candKind[i] == RlSchema.K_PASS) {
                        return new short[] {(short) i};
                    }
                }
            }
            if (d.family == RlSchema.F_CARDS) {
                cards.incrementAndGet();
                return new short[0];
            }
            if (d.family == RlSchema.F_CONFIRM) {
                confirms.incrementAndGet();
                return new short[] {1}; // [yes, no]
            }
            return FakeRlServer.randomSteps(d, new Random(FakeRlServer.mix(17, d.family, d.decIdx)));
        }
    }

    /** The T-sym block of the S2T tests (K 8, true worlds k 4, no OPR) plus {@code extra}'s rlsearch keys. */
    static StepSearch.Spec spec(final int port, final String extra) {
        return StepSearch.Spec.parse(block(port, extra), BvwStepSearchSpecTest.label(), 3, 5);
    }

    static JsonObject block(final int port, final String extra) {
        return JsonParser.parseString("{\"rlsearch\":{\"server\":\"127.0.0.1:" + port + "\",\"policySha\":\""
                + SHA + "\",\"worlds\":8,\"breadth\":3,\"horizon\":2,\"leaf\":\"value\",\"playout\":\"policy\",\"threads\":1,"
                + "\"belief\":\"codraft\",\"beliefPrior\":\"" + PlanSearch.DEFEND_PRIOR + "\",\"beliefPriorSha256\":\""
                + BvwS2tHonestyTest.PRIOR_SHA + "\",\"beliefPriorGain\":20,\"opponent\":\"policy\",\"leafSym\":true,"
                + "\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE + "\",\"beliefCubeSha256\":\"" + BeliefSampler.LIVE_CUBE_SHA256
                + "\",\"beliefSingleton\":false,\"plans\":{\"samples\":2,"
                + "\"minProvenWorlds\":8,\"solverCfg\":{\"budget\":\"counter\",\"hideOwnLibrary\":\"true\",\"worlds\":\"8\","
                + "\"engineSteps\":\"500\",\"evalSteps\":\"500\",\"engineCards\":\"8000\",\"enginePathCards\":\"8000\"}}"
                + (extra.isEmpty() ? "" : "," + extra) + "},"
                + "\"control\":true,\"maxCopyCards\":400000,\"target\":{\"variant\":\"T-sym\",\"k\":4}}").getAsJsonObject();
    }

    static RlSearch.Config config(final String extra) {
        return RlSearch.Config.parse(block(1, extra).getAsJsonObject("rlsearch"));
    }

    static final String GUARD = "\"playoutLoopGuard\":2", CAP = "\"playoutMaxDecisions\":400",
            LOOSE = "\"replayMayPlayLoose\":true";

    /** s-0091 d187-201: we (oath) pass; the opponent (sneak-show) holds Show and Tell and three untapped Islands. */
    static String showAndTell() {
        return BvwS2tHonestyTest.frame("Griselbrand;Snapcaster Mage;Island", "Island;Plains;Island;Plains;Island;Plains;"
                + "Island;Plains", "Island;Island;Plains", "Show and Tell;Blightsteel Colossus;Island",
                "Island;Plains;Island;Plains;Island;Plains;Island;Plains", "Island;Island;Island");
    }

    /** s-0003 d320: we (kitten) hold a Polluted Delta to crack; the opponent (breach-storm) has lands only out. */
    static String fetch() {
        return BvwS2tHonestyTest.frame("Trinket Mage;Counterspell", "Island;Swamp;Island;Island;Swamp;Island;Island;Island",
                "Polluted Delta;Island;Island", "Deep-Cavern Bat;Swamp;Island", "Island;Swamp;Island;Swamp;Island;Swamp;"
                + "Island;Swamp", "Island;Swamp;Swamp");
    }

    /** cur-kitten-0082: Brazen Borrower on an adventure (face-up in exile, castable from there), three Islands. */
    static String borrower() {
        return BvwS2tHonestyTest.frame("Counterspell", "Island;Swamp;Island;Island;Swamp;Island;Island;Island",
                "Island;Island;Island", "Grizzly Bears;Swamp;Island", "Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp",
                "Island;Swamp;Swamp").replace("humanexile=", "humanexile=Brazen Borrower|OnAdventure");
    }

    static final class Out {
        StepSearch.Result r;
        JsonObject row, s2t;
        double ms;
    }

    static Out run(final FakeSearchService svc, final String extra, final String state, final String tag) {
        final Out o = new Out();
        final long t0 = System.nanoTime();
        o.r = BvwS2tTargetTest.search(new StepSearch(spec(svc.port(), extra), index(), 2, "test", tag), state, false);
        o.ms = (System.nanoTime() - t0) / 1e6;
        o.row = o.r.toJson();
        o.row.remove("ms");
        o.s2t = o.r.s2t == null ? null : o.r.s2t.toJson("T-sym");
        System.err.println("[step3-playout] " + tag + " " + Math.round(o.ms) + " ms why " + o.r.why + " t_why "
                + (o.r.s2t == null ? null : o.r.s2t.tWhy) + " target " + o.r.targetValue() + " stats "
                + java.util.Arrays.toString(o.r.playoutStats) + " row " + o.row + " s2t " + o.s2t);
        return o;
    }

    static FakeSearchService service(final Looper pol) throws java.io.IOException {
        final FakeSearchService svc = new FakeSearchService(SHA, 2);
        svc.scriptRaw = pol;
        svc.leafValue = BvwS2tHonestyTest.leaves(-1);
        return svc;
    }

    @Test(timeOut = 3600000)
    public void showAndTellInTheTrueWorldsLoopsOffAndScoresWithTheGuard() throws Exception {
        initAndCreateGame();
        final Looper pol = new Looper(index(), "Show and Tell");
        try (FakeSearchService svc = service(pol)) {
            final Out off = run(svc, "", showAndTell(), "snt-off");
            Assert.assertNull(off.r.playoutStats, "keys off: no step-3 counts");
            Assert.assertNotNull(off.r.s2t.tWhy, "keys off: the T re-play loops to its cap " + off.s2t);
            Assert.assertTrue(off.r.s2t.tWhy.contains("max_decisions"), off.r.s2t.tWhy);
            Assert.assertTrue(Double.isNaN(off.r.s2t.tSym), "keys off: no T-sym value");
            final long confirmsOff = pol.confirms.get();
            Assert.assertTrue(confirmsOff > 1000, "the policy answered the cancel-search confirm no " + confirmsOff + " times");

            final Out on = run(svc, GUARD, showAndTell(), "snt-guard");
            Assert.assertNull(on.r.s2t.tWhy, "guard: the T re-play scores " + on.s2t);
            Assert.assertFalse(Double.isNaN(on.r.s2t.tSym));
            Assert.assertTrue(on.r.hasTarget(), "guard: the row has its T-sym target");
            Assert.assertEquals(on.r.targetValue(), on.r.s2t.tSym, 0.0);
            Assert.assertTrue(on.r.playoutStats[0] > 0, "the guard answered: " + java.util.Arrays.toString(on.r.playoutStats));
            Assert.assertEquals(on.r.playoutStats[2], 0L, "no seat reached the default cap");
            Assert.assertTrue(on.ms < off.ms, "guard " + on.ms + " ms vs off " + off.ms + " ms");
            // the H worlds draw the opponent's hand from the belief pool: where they did not loop, H is the same
            Assert.assertEquals(on.r.s2t.hSym, off.r.s2t.hSym, 0.0, "H-sym moved without a loop in the belief worlds");
        }
    }

    @Test(timeOut = 3600000)
    public void ownLibrarySearchLoopsEveryWorldOffAndScoresWithTheGuard() throws Exception {
        initAndCreateGame();
        final Looper pol = new Looper(index(), "Polluted Delta");
        try (FakeSearchService svc = service(pol)) {
            final Out off = run(svc, "", fetch(), "fetch-off");
            Assert.assertFalse(off.r.hasTarget(), "keys off: no world finishes, no target " + off.row);
            Assert.assertTrue(off.r.failed > 0, off.row.toString());

            final Out on = run(svc, GUARD, fetch(), "fetch-guard");
            Assert.assertTrue(on.r.hasTarget(), "guard: the row has its target " + on.row + " " + on.s2t);
            Assert.assertNull(on.r.s2t.tWhy);
            Assert.assertEquals(on.r.failed, 0, on.row.toString());
            Assert.assertTrue(on.r.playoutStats[0] > 0);

            // determinism: the guard draws nothing; the same block twice is the same row and the same counts
            final Out again = run(svc, GUARD, fetch(), "fetch-guard-2");
            Assert.assertEquals(again.row, on.row);
            Assert.assertEquals(again.s2t, on.s2t);
            Assert.assertEquals(again.r.playoutStats, on.r.playoutStats);

            // the backstop alone: the looping worlds still fail, honestly, at the reduced cap
            final Out cap = run(svc, CAP, fetch(), "fetch-cap");
            Assert.assertFalse(cap.r.hasTarget(), "cap only: still no target " + cap.row);
            Assert.assertTrue(cap.r.playoutStats[1] > 0, "worlds ended at the reduced cap");
            Assert.assertEquals(cap.r.playoutStats[2], 0L);
            Assert.assertTrue(cap.r.s2t.tWhy == null || cap.r.s2t.tWhy.contains("max_decisions"), cap.r.s2t.tWhy);
            Assert.assertTrue(cap.ms < off.ms, "cap " + cap.ms + " ms vs off " + off.ms + " ms");

            // guard and cap: the cap never binds, so the row is the guard-only row bit for bit
            final Out both = run(svc, GUARD + "," + CAP, fetch(), "fetch-both");
            Assert.assertEquals(both.row, on.row);
            Assert.assertEquals(both.s2t, on.s2t);
            Assert.assertEquals(both.r.playoutStats[1], 0L, "the cap never bound");
            Assert.assertTrue(both.r.playoutStats[3] < 400, "most decisions of a finished seat " + both.r.playoutStats[3]);
        }
    }

    @Test(timeOut = 3600000)
    public void anExiledMayPlayCandidateIsFoundInTheRePlayWithTheLooseMatch() throws Exception {
        initAndCreateGame();
        final Looper pol = new Looper(index(), "Brazen Borrower");
        try (FakeSearchService svc = service(pol)) {
            final Out off = run(svc, "", borrower(), "borrower-off");
            Assert.assertNotNull(off.r.s2t.tWhy, "keys off: the T re-play misses the candidate " + off.s2t);
            Assert.assertTrue(off.r.s2t.tWhy.contains("candidate_not_in_copy"), off.r.s2t.tWhy);
            final Out on = run(svc, LOOSE, borrower(), "borrower-loose");
            Assert.assertNull(on.r.s2t.tWhy, "loose: the T re-play finds the candidate " + on.s2t);
            Assert.assertFalse(Double.isNaN(on.r.s2t.tSym));
            // the decision's own search (its belief worlds) is untouched by the key
            final JsonObject a = off.row.deepCopy(), b = on.row.deepCopy();
            for (String k : new String[] {"target", "target_source", "s2t"}) {
                a.remove(k);
                b.remove(k);
            }
            Assert.assertEquals(b.get("search"), a.get("search"), "the key moved the belief-world search");
            Assert.assertEquals(on.r.s2t.hSym, off.r.s2t.hSym, 0.0);
        }
    }

    /**
     * Review #130 S3-2: two may-play permissions on one card (Brazen Borrower on an adventure, plus a second "you may
     * cast it" effect from the same source): in the re-play's level-0 copy both read "... by <an effect>", so the loose
     * match is ambiguous and the re-play fails (candidate_not_in_copy) rather than pick one.
     */
    @Test(timeOut = 3600000)
    public void twoMayPlayPermissionsOnOneCardAreAmbiguousAndFail() throws Exception {
        initAndCreateGame();
        final Looper pol = new Looper(index(), "Brazen Borrower");
        try (FakeSearchService svc = service(pol)) {
            final forge.game.Game g = ForcedWinSolverTest.game("Default", "Default", borrower());
            final forge.game.card.Card bb = g.getCardsIn(forge.game.zone.ZoneType.Exile).get(0);
            Assert.assertEquals(bb.getName(), "Brazen Borrower");
            final forge.game.spellability.SpellAbility again = forge.game.ability.AbilityFactory.getAbility("DB$ Effect | "
                    + "RememberObjects$ Self | StaticAbilities$ Play | ForgetOnMoved$ Exile | Duration$ Permanent", bb);
            again.setSVar("Play", "Mode$ Continuous | MayPlay$ True | EffectZone$ Command | Affected$ Card.IsRemembered+"
                    + "nonAdventure | AffectedZone$ Exile | Description$ You may cast the card.");
            again.setActivatingPlayer(bb.getOwner());
            again.resolve();
            final StepSearch.Result r = BvwS2tTargetTest.search(new StepSearch(spec(svc.port(), LOOSE), index(), 2, "test",
                    "borrower-two"), g, false, forge.ai.AiFixes.Mode.ON);
            System.err.println("[step3-playout] borrower-two t_why " + r.s2t.tWhy + " " + r.toJson());
            Assert.assertNotNull(r.s2t.tWhy, "two loose matches must not resolve to one");
            Assert.assertTrue(r.s2t.tWhy.contains("candidate_not_in_copy") || r.s2t.tWhy.contains("ambiguous"), r.s2t.tWhy);
        }
    }

    /** Review #130 S3-2: the may-play suffix is cut at the LAST " by "; rules text with " by " does not widen the match. */
    @Test
    public void theMayPlaySuffixStartsAtTheLastBy() {
        final String live = "Deals 2 damage to each creature dealt damage by a source this turn by Light Up the Stage (5)";
        Assert.assertTrue(LookaheadSearch.sameMayPlayText(live,
                "Deals 2 damage to each creature dealt damage by a source this turn by Light Up the Stage's Effect (9)"));
        Assert.assertFalse(LookaheadSearch.sameMayPlayText(live,
                "Deals 3 damage to each creature dealt damage by a source this turn by Light Up the Stage's Effect (9)"));
        // a first-" by " cut would take "Deals 2 damage to each creature dealt damage by " as the base and match this
        Assert.assertFalse(LookaheadSearch.sameMayPlayText(live,
                "Deals 2 damage to each creature dealt damage by an opponent's Effect (9)"));
        Assert.assertFalse(LookaheadSearch.sameMayPlayText(live, live), "the exact text is the exact match's");
        Assert.assertFalse(LookaheadSearch.sameMayPlayText("Plains", "Plains by Urza (3)"));
    }

    @Test
    public void everyKeyOffKeepsTheSpecAndEachKeyIsInTheIdentity() throws Exception {
        final StepSearch.Spec base = spec(1, "");
        final RlSearch.Config c = config("");
        Assert.assertEquals(c.playoutLoopGuard, 0);
        Assert.assertEquals(c.playoutMaxDecisions, 0);
        Assert.assertFalse(c.replayMayPlayLoose);
        for (String k : new String[] {"playoutLoopGuard", "playoutMaxDecisions", "replayMayPlayLoose"}) {
            Assert.assertFalse(c.toJson().has(k), "off: " + k + " is not in the spec JSON");
        }
        final java.util.Set<String> shas = new java.util.HashSet<>();
        shas.add(base.sha());
        for (String extra : new String[] {GUARD, CAP, LOOSE, GUARD + "," + CAP + "," + LOOSE}) {
            final StepSearch.Spec s = spec(1, extra);
            Assert.assertTrue(shas.add(s.sha()), extra + ": its own identity");
            Assert.assertTrue(config(extra).toJson().toString().contains(extra.split(",")[0].split(":")[0]), extra);
        }
        // refusals
        for (String bad : new String[] {"\"playoutLoopGuard\":-1", "\"playoutLoopGuard\":17", "\"playoutMaxDecisions\":50",
                "\"playoutMaxDecisions\":5000"}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> spec(1, bad));
        }
        final JsonObject forgePlayout = JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"playout\":\"forge\","
                + "\"playoutLoopGuard\":2}").getAsJsonObject();
        Assert.expectThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(forgePlayout));
    }
}
