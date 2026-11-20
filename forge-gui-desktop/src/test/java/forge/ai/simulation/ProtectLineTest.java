package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * sdcc-protectline-1120 (lane solver-distill-cc; solver key {@code protectLine}, 0 = off): an active line's protection
 * of its own stack on the live seat. Census p138 (owner priority: counterspell play): the opponent's free removal hit the
 * line's last creature while its win trigger was on the stack, and the seat passed with two counters and the mana for
 * them, because protect() answered only spells that target our spell abilities. Synthetic positions, card names test data
 * only:
 * <ul>
 * <li>R1 (level 3): our line's last spell waits on the stack under an opponent spell that targets something else; with
 * nothing left of the line, the seat counters it with the strong-opp ranking (a hard counter, never the bounce one).</li>
 * <li>Soft counter: cast only when the opponent cannot pay its "unless" cost (alternative cost: a tapped Island).</li>
 * <li>R0 (level 2): "return target spell to its owner's hand" targets our spell as a card on the stack.</li>
 * <li>Guards: steps left (a spell or a PASS), an ability on top, nothing of ours under the top: R1 stays out.</li>
 * <li>Level 1 (rows only) decides as level 0 everywhere, and level 0 is the old seat.</li>
 * </ul>
 */
public class ProtectLineTest extends SimulationTest {

    private Game game;
    private Player me, opp;
    private Card oracle;

    private void start() {
        game = initAndCreateGame();
        me = game.getPlayers().get(0);
        opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    /** A line step casting {@code c}'s spell (as BreakOnCounterTest builds its steps). */
    private Step step(final Card c) {
        SpellAbility sa = null;
        for (SpellAbility x : c.getAllPossibleAbilities(me, true)) {
            sa = x;
        }
        AssertJUnit.assertNotNull(c.getName() + " has a spell ability", sa);
        return Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), sa.getDescription(),
                sa.getPayCosts() == null ? "" : sa.getPayCosts().toString(), true);
    }

    /** The live seat's solver at {@code level} (-1: key absent) following {@code steps}. */
    private ForcedWinSolver follow(final int level, final List<Step> steps) {
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("act", "false"); // a follower only, as the plan search's solver
        if (level >= 0) {
            cfg.addProperty("protectLine", String.valueOf(level));
        }
        final SolverDriver driver = new SolverDriver(me, SolverDriver.config(cfg));
        final ForcedWinSolver solver = driver.solver();
        AssertJUnit.assertTrue("the test seat is the live seat", solver.liveSeat);
        solver.knownTopCount(game);
        solver.adopt(new Line(steps, List.of(), 0, 20, 0, 0, List.of(), "test"));
        return solver;
    }

    /** {@code c} moved to the stack as {@code p}'s spell, targeting {@code target} when given. */
    private SpellAbility onStack(final Card c, final Player p, final GameObject target) {
        game.copyLastState();
        final Card s = game.getAction().moveToStack(c, null);
        final SpellAbility sa = s.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
        return sa;
    }

    private void tapLands(final Player p, final int n) {
        int t = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (t < n && c.isLand() && !c.isTapped()) {
                c.tap(true, null, null);
                t++;
            }
        }
    }

    /**
     * The p138 shape: four Islands, the line's last step is Thassa's Oracle (cast with two of them, now on the stack), the
     * hand holds {@code counters}; the opponent, tapped out, casts {@code theirs} at our player on top. Extra steps after
     * the Oracle when given. Returns the solver after the line's last step was played.
     */
    private ForcedWinSolver p138(final int level, final String theirs, final String[] counters, final String... after) {
        start();
        for (int i = 0; i < 4; i++) {
            addCard("Island", me);
        }
        addCard("Mountain", opp).tap(true, null, null);
        oracle = addCardToZone("Thassa's Oracle", me, ZoneType.Hand);
        for (String n : counters) {
            addCardToZone(n, me, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        final List<Step> steps = new ArrayList<>();
        steps.add(step(oracle));
        for (String a : after) {
            // "PASS": a PASS step (a next-turn line); else a spell step of that card, put in our hand
            steps.add("PASS".equals(a) ? Step.PASS : step(addCardToZone(a, me, ZoneType.Hand)));
        }
        final ForcedWinSolver solver = follow(level, steps);
        final ForcedWinSolver.Decision d1 = solver.decide(game);
        AssertJUnit.assertNotNull("the line's step is played", d1.action);
        AssertJUnit.assertEquals(oracle.getId(), d1.action.getHostCard().getId());
        tapLands(me, 2);
        onStack(oracle, me, null);
        if (theirs != null) {
            onStack(addCardToZone(theirs, opp, ZoneType.Hand), opp, me);
        }
        return solver;
    }

    private static String cast(final ForcedWinSolver.Decision d) {
        return d.action == null || d.action.getHostCard() == null ? (d.owned ? "PASS" : "NONE") : d.action.getHostCard().getName();
    }

    @Test
    public void r1CountersOnTopOfTheFinishedLineWithTheHardCounter() {
        for (int level : new int[] {-1, 0, 1, 2}) {
            final ForcedWinSolver s = p138(level, "Lightning Bolt", new String[] {"Remand", "Counterspell"});
            AssertJUnit.assertEquals("level " + level + ": the old seat passes (the bolt does not target our stack)", "PASS",
                    cast(s.decide(game)));
            AssertJUnit.assertEquals("level " + level + ": no R1 cast", 0, s.finishCasts);
            AssertJUnit.assertEquals("level " + level + ": rows from level 1 only", level >= 1 ? 1 : 0, s.protectRows);
        }
        final ForcedWinSolver s = p138(3, "Lightning Bolt", new String[] {"Remand", "Counterspell"});
        AssertJUnit.assertEquals("level 3: the hard counter, never the bounce one (Remand first in hand)", "Counterspell",
                cast(s.decide(game)));
        AssertJUnit.assertEquals(1, s.finishCasts);
    }

    @Test
    public void r1NeverCastsTheBounceCounter() {
        final ForcedWinSolver s = p138(3, "Lightning Bolt", new String[] {"Remand"});
        AssertJUnit.assertEquals("Remand only: no counter (the spell would come back)", "PASS", cast(s.decide(game)));
        AssertJUnit.assertEquals(0, s.finishCasts);
    }

    /** Daze with both Islands tapped (its alternative cost returns one); the opponent with {@code oppOpen} untapped lands. */
    private ForcedWinSolver daze(final int level, final int oppOpen) {
        start();
        for (int i = 0; i < 2; i++) {
            addCard("Island", me);
        }
        for (int i = 0; i < 2; i++) {
            final Card m = addCard("Plains", opp);
            if (i >= oppOpen) {
                m.tap(true, null, null);
            }
        }
        oracle = addCardToZone("Thassa's Oracle", me, ZoneType.Hand);
        addCardToZone("Daze", me, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final ForcedWinSolver solver = follow(level, List.of(step(oracle)));
        AssertJUnit.assertNotNull(solver.decide(game).action);
        tapLands(me, 2);
        onStack(oracle, me, null);
        onStack(addCardToZone("Solitude", opp, ZoneType.Hand), opp, null); // a creature spell, no target as cast
        return solver;
    }

    @Test
    public void r1SoftCounterOnlyWhenTheyCannotPay() {
        AssertJUnit.assertEquals("level 1 passes", "PASS", cast(daze(1, 0).decide(game)));
        AssertJUnit.assertEquals("level 3, opponent tapped out: Daze by its alternative cost", "Daze",
                cast(daze(3, 0).decide(game)));
        AssertJUnit.assertEquals("level 3, opponent with one land open: no soft counter", "PASS",
                cast(daze(3, 1).decide(game)));
    }

    /** Our Oracle on the stack, the opponent's Reprieve at it (a card target on the stack), Counterspell and two Islands. */
    private ForcedWinSolver reprieve(final int level) {
        start();
        for (int i = 0; i < 4; i++) {
            addCard("Island", me);
        }
        addCard("Plains", opp).tap(true, null, null);
        oracle = addCardToZone("Thassa's Oracle", me, ZoneType.Hand);
        addCardToZone("Counterspell", me, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        // a spell step after the Oracle: R1 stays out, only R0 can answer
        final Card opt = addCardToZone("Opt", me, ZoneType.Hand);
        final ForcedWinSolver solver = follow(level, List.of(step(oracle), step(opt)));
        AssertJUnit.assertNotNull(solver.decide(game).action);
        tapLands(me, 2);
        final Card onTheStack = onStack(oracle, me, null).getHostCard();
        AssertJUnit.assertTrue(onTheStack.isInZone(ZoneType.Stack));
        onStack(addCardToZone("Reprieve", opp, ZoneType.Hand), opp, onTheStack);
        return solver;
    }

    @Test
    public void r0SeesTheCardTargetOnTheStack() {
        for (int level : new int[] {-1, 0, 1}) {
            final ForcedWinSolver s = reprieve(level);
            AssertJUnit.assertFalse("the old target test misses it",
                    ForcedWinSolver.targetsOurStack(game, game.getStack().peekAbility(), me));
            AssertJUnit.assertTrue("R0's target test sees it",
                    ForcedWinSolver.targetsOurStack(game, game.getStack().peekAbility(), me, true));
            AssertJUnit.assertEquals("level " + level + " passes", "PASS", cast(s.decide(game)));
        }
        for (int level : new int[] {2, 3}) {
            AssertJUnit.assertEquals("level " + level + ": R0 counters it", "Counterspell", cast(reprieve(level).decide(game)));
        }
    }

    @Test
    public void r1StaysOutWithStepsLeft() {
        // a spell step left: the Oracle's win is not the line's end
        ForcedWinSolver s = p138(3, "Lightning Bolt", new String[] {"Counterspell"}, "Opt");
        AssertJUnit.assertEquals("a spell step left: pass", "PASS", cast(s.decide(game)));
        AssertJUnit.assertEquals(0, s.finishCasts);
        // a PASS left (a next-turn line)
        s = p138(3, "Lightning Bolt", new String[] {"Counterspell"}, "PASS");
        AssertJUnit.assertEquals("a PASS left: pass", "PASS", cast(s.decide(game)));
        AssertJUnit.assertEquals(0, s.finishCasts);
    }

    @Test
    public void r1StaysOutForAnAbilityOnTopOrNothingOfOursBelow() {
        // an activated ability on top: no counter can answer it
        ForcedWinSolver s = p138(3, null, new String[] {"Counterspell"});
        final Card pyro = addCard("Prodigal Pyromancer", opp);
        SpellAbility ab = null;
        for (SpellAbility x : pyro.getSpellAbilities()) {
            if (x.isActivatedAbility()) {
                ab = x;
            }
        }
        AssertJUnit.assertNotNull(ab);
        ab.setActivatingPlayer(opp);
        ab.getTargets().add(me);
        game.getStack().add(ab);
        AssertJUnit.assertEquals("an ability on top: pass", "PASS", cast(s.decide(game)));
        AssertJUnit.assertEquals(0, s.finishCasts);
        // nothing of ours under their spell (our line's spell already resolved)
        start();
        for (int i = 0; i < 4; i++) {
            addCard("Island", me);
        }
        oracle = addCardToZone("Thassa's Oracle", me, ZoneType.Hand);
        addCardToZone("Counterspell", me, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        s = follow(3, List.of(step(oracle)));
        AssertJUnit.assertNotNull(s.decide(game).action);
        tapLands(me, 2);
        game.getAction().moveTo(ZoneType.Battlefield, oracle, null, null);
        onStack(addCardToZone("Lightning Bolt", opp, ZoneType.Hand), opp, me);
        AssertJUnit.assertEquals("nothing of ours below: pass", "PASS", cast(s.decide(game)));
        AssertJUnit.assertEquals(0, s.finishCasts);
    }

    /** R1's check (counterForLive over the hand) in the p138 shape: its ms, median and max of 200 calls. */
    @Test
    public void r1CheckCost() {
        final ForcedWinSolver s = p138(3, "Lightning Bolt", new String[] {"Remand", "Counterspell"});
        final double[] ms = new double[200];
        for (int i = 0; i < ms.length; i++) {
            final long t0 = System.nanoTime();
            AssertJUnit.assertNotNull(s.finishCounter(game));
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(ms);
        System.out.println("PROTECTLINE r1-check-ms median=" + ms[ms.length / 2] + " p95=" + ms[(int) (ms.length * 0.95)]
                + " max=" + ms[ms.length - 1]);
        AssertJUnit.assertTrue("R1's check stays well inside a decision (median " + ms[ms.length / 2] + " ms)",
                ms[ms.length / 2] < 50);
    }
}
