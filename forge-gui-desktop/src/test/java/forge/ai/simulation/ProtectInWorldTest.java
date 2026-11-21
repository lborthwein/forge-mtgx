package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * sdcc-protectline-1120 I-2R (lane solver-distill-cc; lead ruling 11-20): the protectLine key acts only at the live seat's
 * root decision. Inside a search world or play-out copy the copy's solver decides as at key 0, and the in-world counter
 * {@link ForcedWinSolver#PROTECT_IN_WORLD} stays 0 at every key. The p138 shape of {@link ProtectLineTest} (card names test
 * data only): the line's Thassa's Oracle on the stack under the opponent's Lightning Bolt, Counterspell in hand.
 */
public class ProtectInWorldTest extends SimulationTest {

    private Game game;
    private Player me, opp;
    private Line line;

    private Step step(final Card c) {
        SpellAbility sa = null;
        for (SpellAbility x : c.getAllPossibleAbilities(me, true)) {
            sa = x;
        }
        AssertJUnit.assertNotNull(c.getName() + " has a spell ability", sa);
        return Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), sa.getDescription(),
                sa.getPayCosts() == null ? "" : sa.getPayCosts().toString(), true);
    }

    private static JsonObject cfg(final int level) {
        final JsonObject o = new JsonObject();
        o.addProperty("act", "false");
        o.addProperty("protectLine", String.valueOf(level));
        return o;
    }

    private void onStack(final Card c, final Player p, final Player target) {
        game.copyLastState();
        final Card s = game.getAction().moveToStack(c, null);
        final SpellAbility sa = s.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
    }

    /** The p138 position; returns the live seat's solver at {@code level} after it played the line's Oracle step. */
    private ForcedWinSolver p138(final int level) {
        return p138(level, true);
    }

    /**
     * As above; {@code scoped} false: the solver of a play-out copy's driver (PlanSearch.copyLineHook: new SolverDriver(copyMe,
     * cfg, false)) on its own game, as inside a search world.
     */
    private ForcedWinSolver p138(final int level, final boolean scoped) {
        return p138(level, scoped, "Counterspell");
    }

    /** As above with {@code counter} the one counter in hand. */
    private ForcedWinSolver p138(final int level, final boolean scoped, final String counter) {
        game = initAndCreateGame();
        me = game.getPlayers().get(0);
        opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 4; i++) {
            addCard("Island", me);
        }
        addCard("Mountain", opp).tap(true, null, null);
        final Card oracle = addCardToZone("Thassa's Oracle", me, ZoneType.Hand);
        addCardToZone(counter, me, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final List<Step> steps = new ArrayList<>();
        steps.add(step(oracle));
        final SolverDriver d = new SolverDriver(me, SolverDriver.config(cfg(level)), scoped);
        final ForcedWinSolver s = d.solver();
        AssertJUnit.assertEquals(scoped, s.liveSeat);
        s.knownTopCount(game);
        line = new Line(steps, List.of(), 0, 20, 0, 0, List.of(), "test");
        s.adopt(line);
        AssertJUnit.assertNotNull("the line's step is played", s.decide(game).action);
        int t = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (t < 2 && c.isLand() && !c.isTapped()) {
                c.tap(true, null, null);
                t++;
            }
        }
        onStack(oracle, me, null);
        onStack(addCardToZone("Lightning Bolt", opp, ZoneType.Hand), opp, me);
        return s;
    }

    private static String cast(final ForcedWinSolver.Decision d) {
        return d.action == null || d.action.getHostCard() == null ? (d.owned ? "PASS" : "NONE") : d.action.getHostCard().getName();
    }

    /** A play-out copy's solver at {@code level} at the p138 stack: its decision and its protect counts. */
    private String copyDecision(final int level) {
        final ForcedWinSolver c = p138(level, false);
        AssertJUnit.assertEquals("a copy's solver is at level 0", 0, c.protectLevel());
        return cast(c.decide(game)) + " rows=" + c.protectRows + " casts=" + c.finishCasts;
    }

    @Test
    public void worldDecisionsAreTheSameAtKeys013AndTheCounterStaysZero() {
        final long w0 = ForcedWinSolver.PROTECT_IN_WORLD.get();
        String ref = null;
        for (int level : new int[] {0, 1, 3}) {
            final String got = copyDecision(level);
            if (ref == null) {
                ref = got;
            }
            AssertJUnit.assertEquals("key " + level + ": the copy decides as at key 0", ref, got);
            AssertJUnit.assertEquals("the copy passes (the old protect: the bolt does not target our stack)", "PASS rows=0 casts=0", got);
            AssertJUnit.assertEquals("key " + level + ": no protectLine code in a world", w0, ForcedWinSolver.PROTECT_IN_WORLD.get());
        }
    }

    @Test
    public void theRootStillFiresAtKey3WithTheCounterUnchanged() {
        final long w0 = ForcedWinSolver.PROTECT_IN_WORLD.get(), c0 = ForcedWinSolver.PROTECT_LIVESOLVER_ON_COPY.get();
        final ForcedWinSolver s = p138(3);
        AssertJUnit.assertEquals("R1 at the root: the hard counter", "Counterspell", cast(s.decide(game)));
        AssertJUnit.assertEquals(1, s.finishCasts);
        AssertJUnit.assertEquals("the root is not a world", w0, ForcedWinSolver.PROTECT_IN_WORLD.get());
        AssertJUnit.assertEquals(c0, ForcedWinSolver.PROTECT_LIVESOLVER_ON_COPY.get());
    }

    @Test
    public void theCounterSeesTheLiveSolverOnACopyAtAnyReceiptSetting() {
        // the control: the counter does count when the live seat's solver is run on a copy (receipts on or off: it counts
        // before the receipt)
        // (another game with the same stack stands in for a world: GameCopier does not carry this test's hand-built stack)
        final ForcedWinSolver s = p138(3);
        p138(3); // a second game: 'game' is now not s's own
        AssertJUnit.assertFalse(game.getStack().isEmpty());
        final long w0 = ForcedWinSolver.PROTECT_IN_WORLD.get(), c0 = ForcedWinSolver.PROTECT_LIVESOLVER_ON_COPY.get();
        s.decide(game);
        AssertJUnit.assertEquals(w0 + 1, ForcedWinSolver.PROTECT_IN_WORLD.get());
        AssertJUnit.assertEquals(c0 + 1, ForcedWinSolver.PROTECT_LIVESOLVER_ON_COPY.get());
    }

    @Test
    public void aCopyDriverBuiltScopedIsCounted() {
        // review concern 1, mutation-style: forCopy (copyLineHook's construction) at key 3 is not counted; a driver wrongly
        // built scoped (the one-argument constructor) on a copy's player at key 3 is counted
        p138(3);
        final long before = siteCount("copyLineHook"), m0 = siteCount("test-mutant");
        AssertJUnit.assertEquals(0, SolverDriver.forCopy(me, SolverDriver.config(cfg(3))).solver().protectLevel());
        AssertJUnit.assertEquals("copyLineHook's construction is level 0", before, siteCount("copyLineHook"));
        SolverDriver.countCopy(new SolverDriver(me, SolverDriver.config(cfg(3))), "test-mutant");
        AssertJUnit.assertEquals("a scoped copy driver at key 3 is counted", m0 + 1, siteCount("test-mutant"));
        SolverDriver.countCopy(new SolverDriver(me, SolverDriver.config(cfg(0))), "test-mutant");
        AssertJUnit.assertEquals("key 0 is not counted", m0 + 1, siteCount("test-mutant"));
    }

    private static long siteCount(final String site) {
        final java.util.concurrent.atomic.AtomicLong n = SolverDriver.PROTECT_COPY_BUILT.get(site);
        return n == null ? 0 : n.get();
    }

    /** The id scope counters after {@code s} decides the p138 stack twice (IdScope opened fresh before), with its answers. */
    private String idsAfterTwoDecisions(final ForcedWinSolver s) {
        forge.util.IdScope.open();
        try {
            final String a = cast(s.decide(game)), b = cast(s.decide(game));
            return a + " " + b + " ids=" + java.util.Arrays.toString(forge.util.IdScope.peek(forge.util.IdScope.capture()));
        } finally {
            forge.util.IdScope.close();
        }
    }

    private ForcedWinSolver tappedOut(final ForcedWinSolver s) {
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                c.tap(true, null, null);
            }
        }
        return s;
    }

    @Test
    public void aLookWithoutACastLeavesTheIdsAsAtKey0() {
        // review concern 2: Counterspell in hand but every land tapped, so R1 is eligible at key 3 and its look copies the
        // counter (ids drawn) but cannot pay and does not cast: the look puts the ids back, and the next decision is level 0's
        final String k0 = idsAfterTwoDecisions(tappedOut(p138(0, true, "Counterspell")));
        for (int level : new int[] {1, 2, 3}) {
            final ForcedWinSolver s = tappedOut(p138(level, true, "Counterspell"));
            AssertJUnit.assertEquals("key " + level + ": answers and id counters as at key 0", k0, idsAfterTwoDecisions(s));
            AssertJUnit.assertEquals(0, s.finishCasts);
        }
        AssertJUnit.assertTrue("both answers PASS: " + k0, k0.startsWith("PASS PASS "));
    }
}
