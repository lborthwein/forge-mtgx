package forge.ai.simulation;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * sdcc-untaps-1114 (solver key planUntaps): the reader observes that a spell untaps lands or makes lands tap for more
 * this turn, and the planner's mana counts it. The frames are card facts used as tests only (High Tide: Islands tap
 * for one more blue this turn; Frantic Search: draw two, discard two, untap up to three lands; Time Spiral untaps up
 * to six; Brain Freeze mills three per spell cast this turn). No card names in the code under test.
 */
public class UntapTest extends forge.ai.AITest {

    static ForcedWinSolver solver(final Player me, final boolean untaps, final boolean grave) {
        final Map<String, String> p = new java.util.HashMap<>();
        for (String k : new String[] {"cycles", "hideOwnLibrary", "breakOnCounter", "engineAfterPlanFail", "engineLongLoops",
                "engineLoopBisect", "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen",
                "engineRerun", "engineTriggerTargets", "engineVerify", "followPay", "historyLki", "landEntersProbe", "loopMarginal",
                "paySearch", "planDiscardWall", "probeSettle", "readerPerGame", "sourceColorsLive", "sourceLeaves", "sourceOutput",
                "stackHistory", "tutorCast", "unknownWorlds", "verifyReplan", "passBlockers", "planLoops", "engineModeChoice"}) {
            p.put(k, "true");
        }
        p.put("planOrder", "7");
        if (untaps) {
            p.put("planUntaps", "true");
        }
        if (grave) {
            p.put("planGraveCasts", "true");
        }
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(p::get);
        c.sharedReader = false;
        c.honestProbes = false;
        return ForcedWinSolver.withReader(me, c, ForcedWinSolver.newSharedReader());
    }

    static Game game(final String hand, final String battlefield, final String graveyard, final String library, final String oppLibrary) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library)
                .replace("humangraveyard=", "humangraveyard=" + graveyard)
                .replace("ailibrary=" + ForcedWinSolverTest.LIB20, "ailibrary=" + oppLibrary);
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    private static ForcedWinModel.Eff eff(final ForcedWinModel.ACard a, final ForcedWinModel.EffKind k) {
        for (ForcedWinModel.Abil ab : a.fromHand) {
            for (ForcedWinModel.Eff e : ab.effects) {
                if (e.kind == k) {
                    return e;
                }
            }
        }
        return null;
    }

    static final String NINE = "Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest";

    @Test
    public void theReaderObservesUntapsAndBoostsOnlyWithTheKey() {
        final Game g = game("High Tide;Frantic Search;Time Spiral;Brainstorm", "Island;Island;Island;Island;Mountain", "",
                GraveCastTest.LIB40, NINE);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false, false), on = solver(me, true, false);
        Assert.assertFalse(off.config().planUntaps);
        for (String n : new String[] {"High Tide", "Frantic Search", "Time Spiral"}) {
            Assert.assertNull(eff(GraveCastTest.model(off, g, n), ForcedWinModel.EffKind.UNTAP_LANDS), n);
            Assert.assertNull(eff(GraveCastTest.model(off, g, n), ForcedWinModel.EffKind.LAND_BOOST), n);
            System.out.println("UNTAP model " + n + " " + GraveCastTest.model(on, g, n).fromHand);
        }
        final ForcedWinModel.Eff fs = eff(GraveCastTest.model(on, g, "Frantic Search"), ForcedWinModel.EffKind.UNTAP_LANDS);
        Assert.assertNotNull(fs, "untap up to three lands");
        Assert.assertEquals(fs.n, 3);
        final ForcedWinModel.Eff ts = eff(GraveCastTest.model(on, g, "Time Spiral"), ForcedWinModel.EffKind.UNTAP_LANDS);
        Assert.assertNotNull(ts);
        Assert.assertEquals(ts.n, 6);
        final ForcedWinModel.Eff ht = eff(GraveCastTest.model(on, g, "High Tide"), ForcedWinModel.EffKind.LAND_BOOST);
        Assert.assertNotNull(ht, "Islands tap for one more");
        Assert.assertEquals(ht.n, 1);
        Assert.assertEquals(ht.colors, 1 << ForcedWinModel.U);
        Assert.assertTrue(ht.names.contains("Island") && !ht.names.contains("Mountain"), String.valueOf(ht.names));
        Assert.assertEquals(ForcedWinModel.signature(GraveCastTest.model(on, g, "Brainstorm")),
                ForcedWinModel.signature(GraveCastTest.model(off, g, "Brainstorm")), "a card with neither shape is the plain model");
    }

    /**
     * Four Islands, the boost, the untap spell and a storm mill against a nine-card library: the boost first, then the
     * untap spell, then the mill as the third spell. Key off there is no line; key on there is one, and it casts the
     * boost before the untap spell.
     */
    @Test
    public void theBoostBeforeTheUntapMakesTheMana() {
        final Game g = game("High Tide;Frantic Search;Brain Freeze;Mountain", "Island;Island;Island;Island", "", GraveCastTest.LIB40, NINE);
        // (a fourth hand card: with two other cards in hand the probe reads the loot's discard as "the whole hand", a
        // first-sight model defect that exists without this key)
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false, false), on = solver(me, true, false);
        final ForcedWinSolver.Screen a = off.screenAt(g, off.knownTop(g), null, 2000, -1L, true, true);
        final ForcedWinSolver.Screen b = on.screenAt(g, on.knownTop(g), null, 2000, -1L, true, true);
        System.out.println("UNTAP storm pos " + on.readPosition(g, on.knownTop(g)).describe());
        System.out.println("UNTAP storm off lines=" + a.lines + " nodes=" + a.nodes + " | on lines=" + b.lines + " nodes=" + b.nodes
                + " best=" + b.best);
        Assert.assertEquals(a.lines, 0);
        Assert.assertTrue(b.lines > 0, "the boosted, untapped lands pay for the third spell");
        Assert.assertTrue(b.best.indexOf("High Tide") >= 0 && b.best.indexOf("High Tide") < b.best.indexOf("Frantic Search"), b.best);
        // no boost or untap card in the position: the same search
        final Game plain = game("Brainstorm;Brain Freeze", "Island;Island;Island;Island", "", GraveCastTest.LIB40, NINE);
        final Player pm = plain.getPlayers().get(0);
        final ForcedWinSolver.Screen p0 = solver(pm, false, false).screenAt(plain, java.util.List.of(), null, 2000, -1L, true, true);
        final ForcedWinSolver.Screen p1 = solver(pm, true, false).screenAt(plain, java.util.List.of(), null, 2000, -1L, true, true);
        System.out.println("UNTAP nodes plain: off " + p0.nodes + " on " + p1.nodes);
        Assert.assertEquals(p1.nodes, p0.nodes);
        Assert.assertEquals(p1.lines, p0.lines);
    }

    /** NC06's list and turn-4 position (see GraveCastTest), both keys: measured and printed; holding is never worth less. */
    @Test
    public void nc06WithBothKeysIsMeasured() {
        final String gy = "Llanowar Elves;Mountain;Elvish Mystic;Brainstorm";
        final Game hold = GraveCastTest.game("Treachery;Island;" + GraveCastTest.BREACH, "Island;Mox Diamond", gy, GraveCastTest.NC06_LIB,
                "Thieving Skydiver", 20, 16);
        final Game spent = GraveCastTest.game("Treachery;Island", "Island;Island;Mox Diamond", "Brainstorm;" + GraveCastTest.BREACH,
                GraveCastTest.NC06_LIB, "Thieving Skydiver", 20, 16);
        final double[] g = new double[2];
        int i = 0;
        for (Game x : new Game[] {hold, spent}) {
            final ForcedWinSolver s = solver(x.getPlayers().get(0), true, true);
            final ForcedWinSolver.Goldfish gf = GraveCastTest.goldfish(s, x, 16);
            System.out.println("UNTAP NC06 both keys " + (x == hold ? "hold" : "spent") + " goldfish g=" + gf.g + " p=" + gf.p
                    + " nodes=" + gf.nodes + " cut=" + gf.cut + " reach=" + GraveCastTest.reach(s, x, 2000).p);
            g[i++] = gf.g;
        }
        Assert.assertTrue(g[0] >= g[1] - 1e-9, g[0] + " vs " + g[1]);
    }

    @Test
    public void theKeyIsOffWhenAbsent() {
        final java.util.function.Function<String, String> none = k -> null;
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(none).planUntaps);
        Assert.assertTrue(forge.bench.rl.RlLiveSeat.Spec.LIVE_SOLVER_KEYS.contains("planUntaps"));
        Assert.assertFalse(new ForcedWinModel.Reader().planUntaps);
    }
}
