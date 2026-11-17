package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-fetchthin-1117 (solver key {@code fetchThin}; off by default): an ability that searches our library for a land and
 * puts it onto the battlefield is modelled as such (the land in play, the library one card smaller), so cracking it after
 * a pile thins the library for a library-size win. Card names are test data only: the model learns the effect from the
 * sandbox's moves.
 */
public class FetchThinTest extends AITest {

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        kv.put("fetchThin", on ? "true" : "false");
        kv.put("planOrder", "7"); // as live: lazy pile slots, wins first, shallow
        kv.put("hideOwnLibrary", "true");
        kv.put("engine", "false"); // the width search and its verification only
        kv.put("defense", "false");
        kv.put("act", "false");
        kv.put("budgetMs", "60000");
        kv.put("planMs", "20000");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    static String frame(final String hand, final String battlefield, final String library) {
        return ForcedWinSolverTest.frame(hand, battlefield, 20, "", "Forest", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
    }

    /**
     * Four dual lands and two untapped fetches; the pile spell (BBB), a one-mana draw and the creature that wins when our
     * library holds no more cards than our blue devotion (2). After the pile (5 cards) and the draw the library holds 4:
     * only two fetches cracked AFTER the pile, each taking a land the pile holds, bring it to 2 (and pay for the creature).
     */
    static final String HOLD = frame("Doomsday;Opt;Thassa's Oracle",
            "Underground Sea;Underground Sea;Underground Sea;Underground Sea;Scalding Tarn;Scalding Tarn",
            "Island;Island;Island;Forest;Forest;Forest;Forest;Forest");

    /** The twin with both fetches cracked beforehand: the lands in play, the library two lands smaller. No win. */
    static final String CRACKED = frame("Doomsday;Opt;Thassa's Oracle",
            "Underground Sea;Underground Sea;Underground Sea;Underground Sea;Island;Island",
            "Island;Forest;Forest;Forest;Forest;Forest");

    private static Eff eff(final Abil a, final EffKind k) {
        for (Eff e : a.effects) {
            if (e.kind == k) {
                return e;
            }
        }
        return null;
    }

    @Test
    public void theProbeMapsTheSearchedLandOnlyWithTheKey() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", HOLD);
        final Player me = g.getPlayers().get(0);
        final ACard off = new ForcedWinModel.Reader().card(g, me,
                ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Scalding Tarn"));
        Assert.assertEquals(off.onBattlefield.size(), 1, off.onBattlefield.toString());
        Assert.assertNull(eff(off.onBattlefield.get(0), EffKind.TUTOR_BF), "off: as before: " + off.onBattlefield);
        Assert.assertNotNull(eff(off.onBattlefield.get(0), EffKind.SHUFFLE), off.onBattlefield.toString());
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.fetchThin = true;
        final ACard on = r.card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Scalding Tarn"));
        Assert.assertEquals(on.onBattlefield.size(), 1, on.onBattlefield.toString());
        final Abil ab = on.onBattlefield.get(0);
        Assert.assertTrue(ab.sacSelf, ab.toString());
        final Eff bf = eff(ab, EffKind.TUTOR_BF);
        Assert.assertNotNull(bf, ab.toString());
        Assert.assertEquals(bf.n, 1, ab.toString());
        Assert.assertEquals(bf.colors, 0, "enters untapped: " + ab);
        Assert.assertNotNull(bf.names, ab.toString());
        Assert.assertTrue(bf.names.contains("Island"), bf.names.toString());
        Assert.assertFalse(bf.names.contains("Forest"), bf.names.toString());
        // the shuffle comes after the search, as the engine resolves it
        Assert.assertTrue(ab.effects.indexOf(bf) < ab.effects.indexOf(eff(ab, EffKind.SHUFFLE)), ab.toString());
    }

    /** The regression check: a library move that maps to no effect is never silent with the key on. */
    @Test
    public void aLandSearchedOntoTheBattlefieldIsNeverDroppedSilently() {
        final List<String> trace = List.of("ASK pick Battlefield [Library] 3 names=Island|Swamp",
                "MOVE 77 Library@me Battlefield@me L", "SHUFFLE");
        final boolean prev = ForcedWinProbe.FETCH_THIN.get();
        try {
            ForcedWinProbe.FETCH_THIN.set(false);
            final ForcedWinProbe.Obs off = new ForcedWinProbe.Obs();
            off.trace.addAll(trace);
            ForcedWinProbe.effectsFromTrace(off, 5, null, null);
            Assert.assertEquals(off.silent, 1, "off: the move was dropped (the bug, kept for keys-off identity)");
            Assert.assertEquals(off.unmapped, 0);
            ForcedWinProbe.FETCH_THIN.set(true);
            final ForcedWinProbe.Obs on = new ForcedWinProbe.Obs();
            on.trace.addAll(trace);
            ForcedWinProbe.effectsFromTrace(on, 5, null, null);
            Assert.assertEquals(on.silent, 0, on.effects.toString());
            Assert.assertEquals(on.unmapped, 0);
            Assert.assertEquals(on.effects.size(), 2, on.effects.toString());
            Assert.assertEquals(on.effects.get(0).kind, EffKind.TUTOR_BF);
            Assert.assertEquals(on.effects.get(0).names, java.util.Set.of("Island", "Swamp"));
            Assert.assertEquals(on.effects.get(1).kind, EffKind.SHUFFLE);
        } finally {
            ForcedWinProbe.FETCH_THIN.set(prev);
        }
    }

    private static ForcedWinSolver.Proposal best(final List<ForcedWinSolver.Proposal> props) {
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        return best;
    }

    @Test
    public void theSolverCracksTheFetchesAfterThePileOnlyWithTheKey() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", HOLD);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        final ForcedWinSolver.Proposal pOff = best(off.propose(g0));
        Assert.assertTrue(pOff == null || !pOff.proven(1), "key off: no proven line, as before: " + off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", HOLD);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        int pileAt = -1, cracksAfter = 0;
        final List<Step> steps = ((Line) p.line).steps;
        for (int i = 0; i < steps.size(); i++) {
            final Step st = steps.get(i);
            if (st.pile.size() == 5) {
                pileAt = i;
            }
            if (pileAt >= 0 && i > pileAt && "Scalding Tarn".equals(st.hostName) && st.picks.size() == 1) {
                cracksAfter++;
            }
        }
        Assert.assertTrue(pileAt >= 0, p.text);
        Assert.assertEquals(cracksAfter, 2, p.text);
        // nothing was played in the live game
        Assert.assertEquals(g.getPlayers().get(0).getCardsIn(ZoneType.Hand).size(), 3);
    }

    @Test
    public void theUncrackedTwinRatesAboveTheCrackedOne() {
        final Game gc = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", CRACKED);
        final ForcedWinSolver sc = new ForcedWinSolver(gc.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal cracked = best(sc.propose(gc));
        final Game gh = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", HOLD);
        final ForcedWinSolver sh = new ForcedWinSolver(gh.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal held = best(sh.propose(gh));
        Assert.assertNotNull(held, sh.summary());
        Assert.assertTrue(held.proven(1), held.text);
        Assert.assertTrue(cracked == null || cracked.pBelief < held.pBelief, cracked == null ? "" : cracked.text);
    }

    /** One fetch in play and one in hand with our land drop left: the one from hand is played and cracked after the pile too. */
    static final String FROM_HAND = frame("Doomsday;Opt;Thassa's Oracle;Scalding Tarn",
            "Underground Sea;Underground Sea;Underground Sea;Underground Sea;Scalding Tarn",
            "Island;Island;Island;Forest;Forest;Forest;Forest;Forest");

    /** No fetch: two searches to hand cast after the pile take two of its cards (the library 5 - 1 - 2 = 2). */
    static final String TUTORS = frame("Doomsday;Opt;Thassa's Oracle;Demonic Tutor;Demonic Tutor",
            "Underground Sea;Underground Sea;Underground Sea;Underground Sea;Underground Sea;Underground Sea;"
                    + "Underground Sea;Underground Sea;Underground Sea;Underground Sea",
            "Island;Island;Island;Forest;Forest;Forest;Forest;Forest");

    private static void onlyWithTheKey(final String frame, final String after, final int n) {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        final ForcedWinSolver.Proposal pOff = best(off.propose(g0));
        Assert.assertTrue(pOff == null || !pOff.proven(1), "key off: no proven line: " + (pOff == null ? off.summary() : pOff.text));
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        int pileAt = -1, searches = 0;
        final List<Step> steps = ((Line) p.line).steps;
        for (int i = 0; i < steps.size(); i++) {
            final Step st = steps.get(i);
            if (st.pile.size() == 5) {
                pileAt = i;
            }
            if (pileAt >= 0 && i > pileAt && after.equals(st.hostName) && st.picks.size() == 1) {
                searches++;
            }
        }
        Assert.assertEquals(searches, n, p.text);
    }

    @Test
    public void aFetchPlayedFromHandAfterThePileThinsToo() {
        onlyWithTheKey(FROM_HAND, "Scalding Tarn", 2);
    }

    @Test
    public void aTutorCastAfterThePileTakesOneOfItsCards() {
        onlyWithTheKey(TUTORS, "Demonic Tutor", 2);
    }
}
