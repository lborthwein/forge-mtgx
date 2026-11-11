package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-tutorcast-1110 (solver key {@code tutorCast}; off by default): a spell that searches our library for a card and
 * lets us cast it at once without paying its mana cost, here only when an optional additional cost (a sacrificed
 * permanent) was paid and only for cards up to a mana value. Card names are test data: the model learns the effect,
 * the cost and what the engine offers in sandboxes.
 */
public class TutorCastTest extends AITest {

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        kv.put("tutorCast", on ? "true" : "false");
        kv.put("engine", "false"); // the width search and its verification only
        kv.put("defense", "false");
        kv.put("act", "false");
        kv.put("budgetMs", "60000");
        kv.put("planMs", "20000");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    static String frame(final String hand, final String battlefield, final int oppLife, final String library) {
        return ForcedWinSolverTest.frame(hand, battlefield, 20, "", "Forest", oppLife, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
    }

    /** Four black sources and an artifact: the spell (1BBB) with a permanent to sacrifice; the library's kill is a storm spell. */
    static final String STORM = frame("Beseech the Mirror", "Swamp;Swamp;Swamp;Swamp;Sol Ring", 4,
            "Tendrils of Agony;Griselbrand;" + ForcedWinSolverTest.OURLIB);

    /** The same spell finds a pile spell: five cards on top, then a draw-three into a creature that wins on a small library. */
    static final String PILE = frame("Beseech the Mirror;Ancestral Recall",
            "Underground Sea;Underground Sea;Underground Sea;Underground Sea;Underground Sea;Underground Sea;Sol Ring", 20,
            "Doomsday;Thassa's Oracle;" + ForcedWinSolverTest.OURLIB);

    private static Eff eff(final Abil a, final EffKind k) {
        for (Eff e : a.effects) {
            if (e.kind == k) {
                return e;
            }
        }
        return null;
    }

    @Test
    public void theProbeLearnsTheSearchAndCastItsCostAndWhatTheEngineOffers() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Beseech the Mirror",
                "Swamp;Swamp;Swamp;Swamp;Sol Ring", 20,
                "Tendrils of Agony;Griselbrand;Doomsday;Thassa's Oracle;Island;Island;Island"));
        final Player me = g.getPlayers().get(0);
        final Card beseech = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Beseech the Mirror");
        // off (every reader until the key is set): one ability, which only shuffles, as before
        final ACard off = new ForcedWinModel.Reader().card(g, me, beseech);
        Assert.assertEquals(off.fromHand.size(), 1, off.fromHand.toString());
        Assert.assertEquals(off.fromHand.get(0).effects.size(), 1, off.fromHand.toString());
        Assert.assertEquals(off.fromHand.get(0).effects.get(0).kind, EffKind.SHUFFLE, off.fromHand.toString());
        Assert.assertNull(off.fromHand.get(0).optCosts);
        Assert.assertEquals(off.fromHand.get(0).sacN, 0);
        // on: the printed spell is a tutor (search, exile, to hand); with its optional cost paid it is a search-and-cast
        final ForcedWinModel.Reader reader = new ForcedWinModel.Reader();
        reader.tutorCast = true;
        final ACard on = reader.card(g, me, beseech);
        Abil plain = null, paid = null;
        for (Abil a : on.fromHand) {
            if (a.optCosts == null) {
                plain = a;
            } else {
                paid = a;
            }
        }
        Assert.assertNotNull(plain, on.fromHand.toString());
        Assert.assertNotNull(paid, on.fromHand.toString());
        Assert.assertEquals(on.fromHand.size(), 2, on.fromHand.toString());
        Assert.assertNotNull(eff(plain, EffKind.TUTOR), plain.toString());
        Assert.assertNull(eff(plain, EffKind.TUTOR_CAST), plain.toString());
        Assert.assertEquals(plain.sacN, 0, plain.toString());
        Assert.assertEquals(paid.optCosts, List.of("Bargain"), paid.toString());
        Assert.assertEquals(paid.sacN, 1, paid.toString());
        Assert.assertTrue(paid.sacNames.contains("Sol Ring"), paid.toString());
        Assert.assertFalse(paid.sacNames.contains("Swamp"), paid.toString());
        Assert.assertNotEquals(paid.costKey, plain.costKey);
        Assert.assertEquals(paid.pips[ForcedWinModel.B], 3, paid.toString());
        Assert.assertEquals(paid.generic, 1, paid.toString());
        final Eff cast = eff(paid, EffKind.TUTOR_CAST);
        Assert.assertNotNull(cast, paid.toString());
        // what the engine offered to cast for free, one sandbox per card name: spells of mana value 4 or less
        Assert.assertNotNull(cast.names, paid.toString());
        Assert.assertTrue(cast.names.contains("Tendrils of Agony"), cast.names.toString());
        Assert.assertTrue(cast.names.contains("Doomsday"), cast.names.toString());
        Assert.assertTrue(cast.names.contains("Thassa's Oracle"), cast.names.toString());
        Assert.assertFalse(cast.names.contains("Griselbrand"), cast.names.toString());
        Assert.assertFalse(cast.names.contains("Island"), cast.names.toString());
        Assert.assertEquals(cast.n, 1, "a searched card that is not cast goes to our hand: " + paid);
        // the two readers keep their templates apart (a reader without the key never sees the new kinds)
        Assert.assertNull(eff(new ForcedWinModel.Reader().card(g, me, beseech).fromHand.get(0), EffKind.TUTOR));
    }

    @Test
    public void theProbePaysTheCostInItsSandboxWithoutUsingUpTheCardItSearchesFor() {
        // nothing in play can pay the optional cost: the sandbox puts one of our cards that can into play, but never the
        // card the search is told to take (both artifacts here are offered as free casts)
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Beseech the Mirror",
                "Swamp;Swamp;Swamp;Swamp", 20, "Sol Ring;Black Lotus;Tendrils of Agony;Island;Island"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader reader = new ForcedWinModel.Reader();
        reader.tutorCast = true;
        final ACard on = reader.card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Beseech the Mirror"));
        Abil paid = null;
        for (Abil a : on.fromHand) {
            paid = a.optCosts != null ? a : paid;
        }
        Assert.assertNotNull(paid, on.fromHand.toString());
        Assert.assertTrue(paid.sacNames.contains("Sol Ring") && paid.sacNames.contains("Black Lotus"), paid.toString());
        final Eff cast = eff(paid, EffKind.TUTOR_CAST);
        Assert.assertNotNull(cast, paid.toString());
        Assert.assertTrue(cast.names.contains("Sol Ring"), cast.names.toString());
        Assert.assertTrue(cast.names.contains("Black Lotus"), cast.names.toString());
        Assert.assertTrue(cast.names.contains("Tendrils of Agony"), cast.names.toString());
        Assert.assertFalse(cast.names.contains("Island"), cast.names.toString());
        // the plan cannot pay the cost yet (nothing to sacrifice is in play): no line casts the spell that way
        final ForcedWinModel.Position pos = reader.read(g, me, null, true);
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.tutorCast = true;
        lim.horizon = 1;
        for (Line l : ForcedWinPlanner.plan(pos, lim).lines) {
            for (Step st : l.steps) {
                Assert.assertTrue(st.optCosts == null || !st.sacIds.isEmpty(), l.toString());
            }
        }
    }

    /**
     * Solver key {@code sourceLeaves}: an activated ability that puts its own source on top of our library (it draws a
     * card first) is learned with where the source ends; without the key the template is as before.
     */
    @Test
    public void theProbeNotesWhereAnAbilityLeavesItsOwnSource() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Beseech the Mirror",
                "Swamp;Swamp;Swamp;Swamp;Sensei's Divining Top;Mind Stone", 20, "Island;Island;Island;Island;Island;Island;Island"));
        final Player me = g.getPlayers().get(0);
        final Card top = ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Sensei's Divining Top");
        final ACard off = new ForcedWinModel.Reader().card(g, me, top);
        for (Abil a : off.onBattlefield) {
            Assert.assertEquals(a.selfTo, 0, off.onBattlefield.toString());
        }
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.sourceLeaves = true;
        final ACard on = r.card(g, me, top);
        Assert.assertEquals(on.onBattlefield.size(), off.onBattlefield.size(), on.onBattlefield.toString());
        int leaves = 0;
        for (Abil a : on.onBattlefield) {
            if (a.selfTo != 0) {
                leaves++;
                Assert.assertEquals(a.selfTo, Abil.SELF_TOP, on.onBattlefield.toString());
                Assert.assertNotNull(eff(a, EffKind.DRAW), on.onBattlefield.toString());
                Assert.assertFalse(a.sacSelf);
            }
        }
        Assert.assertEquals(leaves, 1, on.onBattlefield.toString());
        // a sacrifice that is the ability's cost is not "leaving": the cost model already has it
        final ACard stone = r.card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Mind Stone"));
        for (Abil a : stone.onBattlefield) {
            Assert.assertEquals(a.selfTo, 0, stone.onBattlefield.toString());
        }
        Assert.assertTrue(top.isInZone(ZoneType.Battlefield), "the sandbox never touches the live game");
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

    /** The line's first cast is the searching spell, with a sacrifice, a searched card and that card as a free step named {@code free}. */
    private static void assertSearchAndCast(final ForcedWinSolver.Proposal p, final String free) {
        final Line line = (Line) p.line;
        Step first = null, next = null;
        for (int i = 0; i < line.steps.size(); i++) {
            final Step st = line.steps.get(i);
            if (st.kind != ForcedWinModel.AbilKind.FLOAT) {
                first = st;
                next = i + 1 < line.steps.size() ? line.steps.get(i + 1) : null;
                break;
            }
        }
        Assert.assertNotNull(first, line.toString());
        Assert.assertEquals(first.hostName, "Beseech the Mirror", line.toString());
        Assert.assertEquals(first.optCosts, List.of("Bargain"), line.toString());
        Assert.assertEquals(first.sacIds.size(), 1, line.toString());
        Assert.assertTrue(first.castFree >= 0, line.toString());
        Assert.assertEquals(first.picks, List.of(first.castFree), line.toString());
        Assert.assertNotNull(next, line.toString());
        Assert.assertTrue(next.free, line.toString());
        Assert.assertEquals(next.hostId, first.castFree, line.toString());
        Assert.assertEquals(next.hostName, free, line.toString());
    }

    @Test
    public void theSolverProposesAndVerifiesTheSearchAndCastStormKill() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", STORM);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        Assert.assertTrue(off.propose(g0).isEmpty(), "key off: no candidate, as before: " + off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", STORM);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        Assert.assertEquals(p.winTurn, 0, p.text);
        assertSearchAndCast(p, "Tendrils of Agony");
        // nothing was played in the live game
        Assert.assertEquals(g.getPlayers().get(0).getCardsIn(ZoneType.Hand).size(), 1);
        Assert.assertEquals(g.getPlayers().get(1).getLife(), 4);
    }

    @Test
    public void theSolverProposesAndVerifiesTheSearchAndCastPileKill() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", PILE);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        Assert.assertTrue(off.propose(g0).isEmpty(), "key off: no candidate, as before: " + off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", PILE);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        assertSearchAndCast(p, "Doomsday");
        // the pile is the free step's: the follower answers its picks and order as for a pile spell cast from hand
        boolean pile = false;
        for (Step st : ((Line) p.line).steps) {
            pile |= st.free && st.pile.size() == 5;
        }
        Assert.assertTrue(pile, p.text);
    }

    /** Two rituals, the search with no permanent to sacrifice, and the storm spell cast from hand: the printed spell is a tutor. */
    static final String TUTOR = frame("Beseech the Mirror;Dark Ritual;Dark Ritual", "Swamp;Swamp;Swamp;Swamp;Swamp", 8,
            "Tendrils of Agony;" + ForcedWinSolverTest.OURLIB);

    @Test
    public void withoutItsOptionalCostTheSearchIsATutorToHand() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", TUTOR);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        Assert.assertTrue(off.propose(g0).isEmpty(), "key off: no candidate, as before: " + off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", TUTOR);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        boolean searched = false, fromHand = false;
        for (Step st : ((Line) p.line).steps) {
            // nothing of ours can pay the optional cost here: the card has no such variant, and nothing is cast for free
            Assert.assertNull(st.optCosts, p.text);
            Assert.assertFalse(st.free, p.text);
            searched |= st.hostName.equals("Beseech the Mirror") && st.picks.size() == 1 && st.castFree < 0;
            fromHand |= st.hostName.equals("Tendrils of Agony") && st.kind == ForcedWinModel.AbilKind.SPELL;
        }
        Assert.assertTrue(searched && fromHand, p.text);
    }

    /**
     * Another card of the same shape, to show nothing is card-specific: no optional cost, a search the engine filters
     * (a creature, instant or sorcery up to a mana value), and a searched card that stays in exile when it is not cast.
     */
    static final String CONVERGE = frame("Bring to Light",
            "Tropical Island;Tropical Island;Tropical Island;Tropical Island;Tropical Island", 20,
            "Thassa's Oracle;Island;Island");

    @Test
    public void aSearchAndCastWithNoOptionalCostIsLearnedPlannedAndVerified() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", CONVERGE);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver on = new ForcedWinSolver(me, cfg(true));
        final ACard btl = on.readerForTest().card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Bring to Light"));
        Assert.assertEquals(btl.fromHand.size(), 1, btl.fromHand.toString());
        final Abil ab = btl.fromHand.get(0);
        Assert.assertNull(ab.optCosts, ab.toString());
        Assert.assertEquals(ab.sacN, 0, ab.toString());
        final Eff cast = eff(ab, EffKind.TUTOR_CAST);
        Assert.assertNotNull(cast, ab.toString());
        Assert.assertTrue(cast.names.contains("Thassa's Oracle"), cast.names.toString());
        Assert.assertFalse(cast.names.contains("Island"), cast.names.toString());
        Assert.assertEquals(cast.n, 0, "an uncast card stays in exile: " + ab);
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        boolean free = false;
        for (Step st : ((Line) p.line).steps) {
            free |= st.free && st.hostName.equals("Thassa's Oracle");
            Assert.assertNull(st.optCosts, p.text);
            Assert.assertTrue(st.sacIds.isEmpty(), p.text);
        }
        Assert.assertTrue(free, p.text);
    }

    private static void liveSeatWins(final String frame) {
        System.setProperty("forge.ai.forcedWin.spec", "tutorCast=true,engine=false,defense=false,budgetMs=60000,planMs=20000");
        try {
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
            ForcedWinSolverTest.play(g, 7);
            final ForcedWinSolver s = ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
            Assert.assertTrue(s.config().tutorCast);
            Assert.assertTrue(g.isGameOver() && g.getPlayers().get(0).hasWon(), "no win on turn 7: " + s.summary());
            Assert.assertTrue(s.taken >= 1, s.summary());
        } finally {
            System.clearProperty("forge.ai.forcedWin.spec");
        }
    }

    /** The live seat follows the line: the optional cost, the sacrifice, the search, the "cast it" answer, the free spell's target. */
    @Test
    public void theLiveSeatPlaysTheSearchAndCastStormKill() {
        liveSeatWins(STORM);
    }

    /** ... and the free pile spell's five picks and their order. */
    @Test
    public void theLiveSeatPlaysTheSearchAndCastPileKill() {
        liveSeatWins(PILE);
    }

    /** ... and a search answered from the line with no cast offered. */
    @Test
    public void theLiveSeatPlaysTheSearchAsATutor() {
        liveSeatWins(TUTOR);
    }

    /** ... and the other card of the shape: a free permanent that wins on entering. */
    @Test
    public void theLiveSeatPlaysASearchAndCastWithNoOptionalCost() {
        liveSeatWins(CONVERGE);
    }
}
