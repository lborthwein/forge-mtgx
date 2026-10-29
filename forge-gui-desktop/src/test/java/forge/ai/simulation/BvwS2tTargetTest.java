package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.PlayerControllerBridge;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlSearch;
import forge.bench.rl.StepSearch;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * Lane bvw-step2-fork-1027 (S2T, F2-F5 and F7): the step-2 teacher's pieces on fixture positions.
 * <ul>
 *   <li>F7: the smoke flag (every variant computed and logged, T re-plays, pairs) leaves the spec variant's target
 *       bit-identical, for each of T-sym, H-sym and H-asym;</li>
 *   <li>F2/F3: the opponent's play-out seat is the policy (a step-1 block never asks it), and the symmetric leaf is
 *       (v_s + 1 - v_o) / 2 of both seats' own frames;</li>
 *   <li>F4: the co-draft belief draws the opponent's hidden cards with the prior over the cards they showed, never from
 *       their real hidden cards (the same draw whatever those are), and the plans' solver uses the same prior;</li>
 *   <li>F5: a true world keeps both real hands and every library's cards, shuffled.</li>
 * </ul>
 */
public class BvwS2tTargetTest extends SimulationTest {

    static final String SHA = BvwS2tHonestyTest.SHA;

    static String position() {
        return BvwS2tHonestyTest.frame(BvwS2tHonestyTest.OUR_HAND, BvwS2tHonestyTest.OUR_LIB, BvwS2tHonestyTest.OUR_BF,
                BvwS2tHonestyTest.THEIR_HAND_A, BvwS2tHonestyTest.THEIR_LIB_A, BvwS2tHonestyTest.THEIR_BF);
    }

    static StepSearch.Result search(final StepSearch ss, final String state, final boolean opr) {
        return search(ss, ForcedWinSolverTest.game("Default", "Default", state), opr, forge.ai.AiFixes.Mode.ON);
    }

    static StepSearch.Result search(final StepSearch ss, final Game g, final boolean opr, final forge.ai.AiFixes.Mode fair) {
        BvwS2tHonestyTest.fairNaming(g, fair);
        final Player me = g.getPlayers().get(0);
        final RlKnowledge know = new RlKnowledge(g);
        know.v2 = true;
        know.attach();
        return PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> ss.value(g, me, know, 2988000001L, 4242L,
                opr, false)));
    }

    @Test(timeOut = 1800000)
    public void theSmokeFlagLeavesEachVariantsTargetBitIdentical() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            final BvwS2tHonestyTest.Policy pol = new BvwS2tHonestyTest.Policy(-1);
            svc.scriptRaw = pol;
            svc.leafValue = BvwS2tHonestyTest.leaves(-1);
            for (String variant : StepSearch.Spec.VARIANTS) {
                final StepSearch.Spec off = BvwS2tHonestyTest.spec(svc.port(), variant, false, true);
                final StepSearch.Spec on = BvwS2tHonestyTest.spec(svc.port(), variant, true, true);
                Assert.assertEquals(on.sha(), off.sha(), "smoke is not in the identity");
                final StepSearch.Result a = search(new StepSearch(off, BvwStepSearchHonestyTest.index(), 2, "test", "s2t-off"),
                        position(), true);
                final StepSearch.Result b = search(new StepSearch(on, BvwStepSearchHonestyTest.index(), 2, "test", "s2t-on"),
                        position(), true);
                System.err.println("[s2t-target] " + variant + " off " + a.s2t.toJson(variant) + " on " + b.s2t.toJson(variant));
                Assert.assertTrue(a.hasTarget(), variant + ": no target " + a.toJson());
                Assert.assertEquals(Double.doubleToLongBits(b.targetValue()), Double.doubleToLongBits(a.targetValue()),
                        variant + ": the smoke flag moved the target");
                final JsonObject ja = a.toJson(), jb = b.toJson();
                ja.remove("ms");
                jb.remove("ms");
                Assert.assertEquals(jb, ja, variant + ": the smoke flag moved the search");
                Assert.assertEquals(b.s2t.toJson(variant).get("opr"), a.s2t.toJson(variant).get("opr"));
                // the smoke run computes every variant and logs the pairs; the plain run only its own (T-sym: T and H)
                final JsonObject t = b.s2t.toJson(variant).getAsJsonObject("targets");
                for (String v : StepSearch.Spec.VARIANTS) {
                    Assert.assertFalse(t.get(v).isJsonNull(), variant + " smoke: " + v + " not computed " + t);
                }
                Assert.assertTrue(b.s2t.toJson(variant).has("worlds") && b.s2t.toJson(variant).has("true_worlds"));
                Assert.assertFalse(a.s2t.toJson(variant).has("worlds") || a.s2t.toJson(variant).has("true_worlds"));
                Assert.assertEquals(t.get(variant).getAsDouble(), b.targetValue(), 1e-6); // JSON rounds to 1e-6
            }
        }
    }

    @Test(timeOut = 1800000)
    public void theOpponentIsThePolicyAndTheLeafIsSymmetric() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            final BvwS2tHonestyTest.Policy pol = new BvwS2tHonestyTest.Policy(-1);
            svc.scriptRaw = pol;
            // our frames 0.4 (P(win) 0.7), theirs -0.2 (P(win) 0.4): a symmetric leaf is (0.7 + 1 - 0.4) / 2 = 0.65
            svc.leafValue = p -> forge.bench.rl.RlWire.decodeDecide(p, 2).seat == 0 ? 0.4 : -0.2;
            final StepSearch.Result r = search(new StepSearch(BvwS2tHonestyTest.spec(svc.port(), "H-sym", true, false),
                    BvwStepSearchHonestyTest.index(), 2, "test", "s2t-sym"), position(), false);
            final JsonObject s = r.s2t.toJson("H-sym");
            System.err.println("[s2t-sym] " + r.toJson() + " " + s);
            Assert.assertFalse(pol.of(1).isEmpty(), "the opponent's play-out seat never asked the policy");
            final List<String> vs = BvwS2tHonestyTest.column(s, "worlds", 0), vo = BvwS2tHonestyTest.column(s, "worlds", 1);
            for (int w = 0; w < vs.size(); w++) {
                if (!"null".equals(BvwS2tHonestyTest.column(s, "worlds", 2).get(w))) {
                    continue; // a terminal world
                }
                Assert.assertEquals(Double.parseDouble(vs.get(w)), 0.7, 1e-6);
                Assert.assertEquals(Double.parseDouble(vo.get(w)), 0.4, 1e-6);
            }
            final JsonObject t = s.getAsJsonObject("targets");
            Assert.assertEquals(t.get("H-sym").getAsDouble(), 0.65, 1e-6, s.toString());
            Assert.assertEquals(t.get("H-asym").getAsDouble(), 0.7, 1e-6, s.toString());
            Assert.assertEquals(t.get("T-sym").getAsDouble(), 0.65, 1e-6, s.toString());
            Assert.assertEquals(r.targetValue(), t.get("H-sym").getAsDouble(), 1e-6); // the row's JSON rounds to 1e-6
            // a step-1 block: the play-out opponent is Forge AI, the policy is never asked for it
            pol.log.clear();
            final StepSearch.Result s1 = search(new StepSearch(BvwStepSearchHonestyTest.spec(svc.port()),
                    BvwStepSearchHonestyTest.index(), 2, "test", "step1"), position(), false);
            Assert.assertNull(s1.s2t);
            Assert.assertTrue(pol.of(1).isEmpty(), "a step-1 search asked the policy for the opponent: " + pol.of(1).size());
        }
    }

    /** A copy of {@code live} and its {@code me}. */
    static Object[] copy(final Game live, final Player liveMe) {
        final GameCopier c = new GameCopier(live, true);
        final Game g = c.makeCopy();
        return new Object[] {g, c.find(liveMe)};
    }

    static List<String> names(final Player p, final ZoneType z) {
        final List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(z)) {
            out.add(c.getName());
        }
        return out;
    }

    static LookaheadSearch belief(final String mode, final Map<String, Map<String, Double>> table) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 1;
        c.belief = mode;
        c.beliefCube = BeliefSampler.LIVE_CUBE;
        c.beliefCubeSha256 = BeliefSampler.LIVE_CUBE_SHA256;
        c.beliefSingleton = false;
        c.beliefPriorTable = table;
        c.beliefPriorGain = 20;
        return new LookaheadSearch(c);
    }

    @Test(timeOut = 1800000)
    public void coDraftWorldsFollowThePriorAndNeverTheRealHiddenCards() throws Exception {
        initAndCreateGame();
        final Map<String, Map<String, Double>> table = new HashMap<>();
        final Map<String, Double> row = new HashMap<>();
        row.put("Thraben Inspector", 1.0);
        table.put("Esper Sentinel", row);
        final LookaheadSearch cod = belief("codraft", table), uni = belief("uniform", null);
        final String bf = "Plains;Plains;Esper Sentinel";
        final String a = BvwS2tHonestyTest.frame(BvwS2tHonestyTest.OUR_HAND, BvwS2tHonestyTest.OUR_LIB,
                BvwS2tHonestyTest.OUR_BF, BvwS2tHonestyTest.THEIR_HAND_A, BvwS2tHonestyTest.THEIR_LIB_A, bf);
        final String b = BvwS2tHonestyTest.frame(BvwS2tHonestyTest.OUR_HAND, BvwS2tHonestyTest.OUR_LIB,
                BvwS2tHonestyTest.OUR_BF, BvwS2tHonestyTest.THEIR_HAND_B, BvwS2tHonestyTest.THEIR_LIB_B, bf);
        final Game ga = ForcedWinSolverTest.game("Default", "Default", a), gb = ForcedWinSolverTest.game("Default", "Default", b);
        int hitsCod = 0, hitsUni = 0, hitsSolver = 0;
        final int n = 120;
        for (int i = 0; i < n; i++) {
            final List<String> drawn = new ArrayList<>();
            for (Game live : new Game[] {ga, gb}) {
                final Player liveMe = live.getPlayers().get(0);
                final Object[] c = copy(live, liveMe);
                final Game g = (Game) c[0];
                final Player me = (Player) c[1];
                cod.redraw(live, liveMe, g, me, new Random(1000 + i));
                final Player opp = me.getOpponents().get(0);
                final List<String> hid = names(opp, ZoneType.Hand);
                hid.addAll(names(opp, ZoneType.Library));
                Assert.assertEquals(hid.size(), 11);
                drawn.add(String.join(";", hid));
                if (live == ga) {
                    hitsCod += java.util.Collections.frequency(hid, "Thraben Inspector");
                }
            }
            // the draw is a function of the position the seat sees and the seed: never of their real hidden cards
            Assert.assertEquals(drawn.get(1), drawn.get(0), "a co-draft world read the real hidden cards");
            final Player liveMe = ga.getPlayers().get(0);
            final Object[] c = copy(ga, liveMe);
            uni.redraw(ga, liveMe, (Game) c[0], (Player) c[1], new Random(1000 + i));
            final Player uo = ((Player) c[1]).getOpponents().get(0);
            hitsUni += java.util.Collections.frequency(names(uo, ZoneType.Hand), "Thraben Inspector")
                    + java.util.Collections.frequency(names(uo, ZoneType.Library), "Thraben Inspector");
            final Object[] d = copy(ga, liveMe);
            cod.solverRedraw().apply(ga, liveMe, (Game) d[0], (Player) d[1], new Random(5000 + i));
            final Player so = ((Player) d[1]).getOpponents().get(0);
            hitsSolver += java.util.Collections.frequency(names(so, ZoneType.Hand), "Thraben Inspector")
                    + java.util.Collections.frequency(names(so, ZoneType.Library), "Thraben Inspector");
        }
        System.err.println("[s2t-codraft] Thraben Inspector in " + n + " worlds: codraft " + hitsCod + ", solver "
                + hitsSolver + ", uniform " + hitsUni);
        // weight 21 of ~600 slots over 11 cards: ~0.38 per world (uniform: ~0.02)
        Assert.assertTrue(hitsCod > 20, "the co-draft prior did not lift the shown card's partner: " + hitsCod);
        Assert.assertTrue(hitsSolver > 20, "the plans' solver worlds do not use the co-draft prior: " + hitsSolver);
        Assert.assertTrue(hitsUni < 12, "uniform worlds: " + hitsUni);
        Assert.assertNotNull(uni.solverRedraw());
    }

    @Test(timeOut = 1800000)
    public void aTrueWorldKeepsBothRealHandsAndShufflesTheLibraries() throws Exception {
        initAndCreateGame();
        final Game live = ForcedWinSolverTest.game("Default", "Default", position());
        final Player liveMe = live.getPlayers().get(0);
        final LookaheadSearch ls = belief("uniform", null);
        int moved = 0;
        for (int i = 0; i < 6; i++) {
            final Object[] c = copy(live, liveMe);
            final Game g = (Game) c[0];
            final Player me = (Player) c[1];
            ls.trueShuffle(live, liveMe, g, me, new Random(77 + i));
            for (Player p : g.getRegisteredPlayers()) {
                final Player lp = (Player) live.getPlayer(p.getId());
                Assert.assertEquals(names(p, ZoneType.Hand), names(lp, ZoneType.Hand), "a true world keeps the real hand");
                final List<String> x = names(p, ZoneType.Library), y = names(lp, ZoneType.Library);
                final List<String> xs = new ArrayList<>(x), ys = new ArrayList<>(y);
                java.util.Collections.sort(xs);
                java.util.Collections.sort(ys);
                Assert.assertEquals(xs, ys, "a true world keeps the library's cards");
                if (!x.equals(y)) {
                    moved++;
                }
            }
        }
        Assert.assertTrue(moved >= 10, "the libraries were not shuffled: " + moved);
    }

    /**
     * Review #123 S2-1: a true world keeps every library card some seat knows where it is (their scried top, a card at
     * the bottom, ours) and shuffles only the others; the seat knowledge (a scry seen by the tracker) says which.
     */
    @Test(timeOut = 1800000)
    public void aTrueWorldKeepsTheLibraryCardsASeatKnows() throws Exception {
        initAndCreateGame();
        final Game live = ForcedWinSolverTest.game("Default", "Default", position());
        final Player liveMe = live.getPlayers().get(0);
        final Player liveOpp = liveMe.getOpponents().get(0);
        final List<Card> theirs = new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library));
        final List<Card> ours = new ArrayList<>(liveMe.getCardsIn(ZoneType.Library));
        // the seat knowledge: they looked at their top two (a scry), we know our third card
        final RlKnowledge know = new RlKnowledge(live);
        know.attach();
        know.onLook(live, liveOpp, theirs.subList(0, 2), ZoneType.Library);
        know.onLook(live, liveMe, ours.subList(2, 3), ZoneType.Library);
        final RlSearch rs = new RlSearch(RlSearch.Config.parse(JsonParser.parseString(
                "{\"server\":\"127.0.0.1:1\",\"worlds\":1}").getAsJsonObject()), 1L, 1L, null, know, "test", "t");
        final java.util.function.Predicate<Card> known = rs.knownToAnySeat(live);
        Assert.assertTrue(known.test(theirs.get(0)) && known.test(theirs.get(1)) && known.test(ours.get(2)));
        Assert.assertFalse(known.test(theirs.get(2)) || known.test(ours.get(0)));
        final LookaheadSearch ls = belief("uniform", null);
        int moved = 0;
        for (int i = 0; i < 6; i++) {
            final Object[] c = copy(live, liveMe);
            final Game g = (Game) c[0];
            final Player me = (Player) c[1];
            ls.trueShuffle(live, liveMe, g, me, new Random(91 + i), known);
            final List<Card> t = new ArrayList<>(me.getOpponents().get(0).getCardsIn(ZoneType.Library));
            final List<Card> o = new ArrayList<>(me.getCardsIn(ZoneType.Library));
            Assert.assertEquals(t.get(0).getId(), theirs.get(0).getId(), "their known top moved");
            Assert.assertEquals(t.get(1).getId(), theirs.get(1).getId(), "their known second card moved");
            Assert.assertEquals(o.get(2).getId(), ours.get(2).getId(), "our known card moved");
            for (int j = 0; j < t.size(); j++) {
                moved += t.get(j).getId() != theirs.get(j).getId() ? 1 : 0;
            }
        }
        Assert.assertTrue(moved > 10, "their unknown library cards were not shuffled: " + moved);
    }

    /** Step-3 hygiene: a true-world re-play needs Forge's fair naming ON for every player, else it is refused. */
    @Test(timeOut = 1800000)
    public void aTrueWorldNeedsFairNamingForEveryPlayer() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.scriptRaw = new BvwS2tHonestyTest.Policy(-1);
            svc.leafValue = BvwS2tHonestyTest.leaves(-1);
            final StepSearch ss = new StepSearch(BvwS2tHonestyTest.spec(svc.port(), "T-sym", false, false),
                    BvwStepSearchHonestyTest.index(), 2, "test", "s2t-fair");
            final StepSearch.Result on = search(ss, ForcedWinSolverTest.game("Default", "Default", position()), false,
                    forge.ai.AiFixes.Mode.ON);
            Assert.assertNull(on.s2t.tWhy, on.s2t.toJson("T-sym").toString());
            Assert.assertFalse(Double.isNaN(on.s2t.tSym));
            final StepSearch.Result off = search(ss, ForcedWinSolverTest.game("Default", "Default", position()), false,
                    forge.ai.AiFixes.Mode.OFF);
            Assert.assertTrue(off.s2t.tWhy != null && off.s2t.tWhy.startsWith("refused: fair naming"),
                    off.s2t.toJson("T-sym").toString());
            Assert.assertTrue(Double.isNaN(off.s2t.tSym) && !off.hasTarget(), "a refused T re-play gives no T-sym target");
        }
    }

    /**
     * Step-3 hygiene: an opponent's face-down permanent our seat cannot see keeps its real identity in every belief world,
     * so an S2T row there gets OPEN-6's treatment (no search, no target); a step-1 block is unchanged.
     */
    @Test(timeOut = 1800000)
    public void anOpponentsFaceDownPermanentIsHandledLikeOpen6() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.scriptRaw = new BvwS2tHonestyTest.Policy(-1);
            svc.leafValue = BvwS2tHonestyTest.leaves(-1);
            final java.util.function.Supplier<Game> faceDown = () -> {
                final Game g = ForcedWinSolverTest.game("Default", "Default", position());
                for (Card c : g.getPlayers().get(1).getCardsIn(ZoneType.Battlefield)) {
                    if ("Grizzly Bears".equals(c.getName())) {
                        c.turnFaceDown(true);
                    }
                }
                return g;
            };
            final Game probe = faceDown.get();
            final Object[] cp = copy(probe, probe.getPlayers().get(0));
            Assert.assertEquals(forge.bench.rl.HiddenExile.scanFaceDownPermanents((Game) cp[0], (Player) cp[1]).size(), 1);
            final Object[] cq = copy(probe, probe.getPlayers().get(1));
            Assert.assertEquals(forge.bench.rl.HiddenExile.scanFaceDownPermanents((Game) cq[0], (Player) cq[1]).size(), 0,
                    "our own face-down permanent is not hidden from us");
            final StepSearch.Result s2t = search(new StepSearch(BvwS2tHonestyTest.spec(svc.port(), "H-sym", false, false),
                    BvwStepSearchHonestyTest.index(), 2, "test", "s2t-fd"), faceDown.get(), false, forge.ai.AiFixes.Mode.ON);
            Assert.assertEquals(s2t.why, forge.bench.rl.HiddenExile.TARGET_SOURCE, s2t.toJson().toString());
            Assert.assertFalse(s2t.hasTarget());
            final StepSearch.Result one = search(new StepSearch(BvwStepSearchHonestyTest.spec(svc.port()),
                    BvwStepSearchHonestyTest.index(), 2, "test", "step1-fd"), faceDown.get(), false, forge.ai.AiFixes.Mode.ON);
            Assert.assertNotEquals(one.why, forge.bench.rl.HiddenExile.TARGET_SOURCE, "a step-1 block is unchanged");
        }
    }
}
