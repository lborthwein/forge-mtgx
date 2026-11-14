package forge.bench.rl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.LookaheadSearch;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * sdcc-floor-1114 (search.priorFloor, off by default): a menu entry with a policy prior below search.priorFloorMin is not
 * offered to the look-ahead as a departure, and a sampled policy plan that starts with one is dropped; the solver's lines
 * are kept. NC06 (owner replay, turn 4): the policy played its land (prior 0.808), the look-ahead departed to the graveyard
 * engine (prior 0.014) on play-out values alone; with the floor the pick is the land.
 */
public class PriorFloorTest extends SimulationTest {

    /** NC06 rerun y-NC06-t4, decision 0: the look-ahead's per-world values (lookahead-explain) and the policy's priors. */
    static final double[] ISLAND = {0.42351333796977997, 0.33372344076633453, 0.31700803339481354, 0.3924590274691582,
            0.32537776231765747, 0.2831133157014847, 0.36305785179138184, 0.381458543241024};
    static final double[] BREACH = {0.7084571272134781, 0.6879309117794037, 0.48516566399484873, 0.10542628169059753,
            0.6151441782712936, 0.45111437514424324, 0.7259679734706879, 0.7676245272159576};
    static final double P_PASS = 0.1776, P_LAND = 0.8082, P_ENGINE = 0.0141;

    /** The given order RlSearch builds: the policy's own entry, then the rest by prior (entries: 0 pass, 1 land, 2 engine). */
    private static List<Integer> nc06Order(final boolean floor) {
        final Map<Integer, double[]> by = new TreeMap<>();
        by.put(0, new double[] {P_PASS, 0, P_PASS});
        by.put(1, new double[] {P_LAND, 1, P_LAND});
        by.put(2, new double[] {P_ENGINE, 2, P_ENGINE});
        final List<Integer> rest = new ArrayList<>(List.of(0, 2));
        final int dropped = floor ? RlSearch.priorFloor(rest, by, 0.05) : 0;
        AssertJUnit.assertEquals(floor ? 1 : 0, dropped);
        final List<Integer> order = new ArrayList<>(List.of(1));
        order.addAll(rest);
        return order;
    }

    private static int pick(final List<Integer> order) {
        final int n = order.size(), k = ISLAND.length;
        final double[][] v = new double[n][];
        final boolean[][] f = new boolean[n][k];
        final boolean[] ok = new boolean[n];
        for (int c = 0; c < n; c++) {
            v[c] = order.get(c) == 2 ? BREACH : ISLAND; // pass's values were the land's, world for world
            java.util.Arrays.fill(f[c], true);
            ok[c] = true;
        }
        return order.get(LookaheadSearch.chooseAnytime(v, f, ok, n, k, 1.645, 0.0, Double.NaN));
    }

    @Test
    public void theKeyIsOffByDefaultAndParses() {
        final JsonObject none = new JsonObject();
        none.addProperty("server", "127.0.0.1:1");
        AssertJUnit.assertFalse(RlSearch.Config.parse(none).priorFloor);
        AssertJUnit.assertFalse(RlSearch.Config.parse(none).toJson().has("priorFloor"));
        final JsonObject o = none.deepCopy();
        o.addProperty("priorFloor", true);
        final RlSearch.Config c = RlSearch.Config.parse(o);
        AssertJUnit.assertTrue(c.priorFloor);
        AssertJUnit.assertEquals(0.05, c.priorFloorMin, 1e-12);
        AssertJUnit.assertTrue(c.toJson().get("priorFloor").getAsBoolean());
        o.addProperty("priorFloorMin", 0.0);
        try {
            RlSearch.Config.parse(o);
            AssertJUnit.fail("priorFloorMin 0 is refused");
        } catch (IllegalArgumentException expected) {
            // the floor's minimum is in (0, 1)
        }
    }

    @Test
    public void nc06WithTheFloorThePickIsTheLand() {
        AssertJUnit.assertEquals("key off: the recorded departure to the engine", 2, pick(nc06Order(false)));
        final List<Integer> on = nc06Order(true);
        AssertJUnit.assertEquals("the engine is not offered", List.of(1, 0), on);
        AssertJUnit.assertEquals("key on: the policy's land", 1, pick(on));
    }

    @Test
    public void thePolicysOwnAnswerIsNeverDropped() {
        final Map<Integer, double[]> by = new TreeMap<>();
        by.put(0, new double[] {0.97, 0, 0.97});
        by.put(1, new double[] {0.02, 1, 0.02});
        by.put(2, new double[] {0.01, 2, 0.01});
        final List<Integer> rest = new ArrayList<>(List.of(0, 1)); // the policy's own (2) is not in rest
        AssertJUnit.assertEquals(1, RlSearch.priorFloor(rest, by, 0.05));
        AssertJUnit.assertEquals(List.of(0), rest);
    }

    /**
     * NC06's list on a later turn with the kill in sight (UntapTest's position, both planner keys): the solver proposes a
     * line through the engine. At the same decision a sampled policy plan starting with the engine (prior 0.014) is dropped
     * by the floor; the solver's line is kept, so the low-prior move it backs is still offered.
     */
    @Test
    public void aPlanBackedLowPriorMoveIsStillOffered() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 6; i++) {
            addCardToZone("Island", me, ZoneType.Battlefield);
        }
        addCardToZone("Mox Diamond", me, ZoneType.Battlefield);
        for (String n : new String[] {"Llanowar Elves", "Mountain", "Elvish Mystic", "Brainstorm", "Forest", "Forest", "Wild Growth",
                "Pentad Prism"}) {
            addCardToZone(n, me, ZoneType.Graveyard);
        }
        final Card tide = addCardToZone("High Tide", me, ZoneType.Hand);
        addCardToZone("Frantic Search", me, ZoneType.Hand);
        addCardToZone("Brain Freeze", me, ZoneType.Hand);
        addCardToZone("Island", me, ZoneType.Hand);
        final Card engine = addCardToZone("Underworld Breach", me, ZoneType.Hand);
        for (int i = 0; i < 20; i++) {
            addCardToZone("Island", me, ZoneType.Library);
            addCardToZone("Island", opp, ZoneType.Library);
        }
        opp.setLife(16, null);

        final Map<String, String> p = new HashMap<>();
        for (String k : new String[] {"cycles", "hideOwnLibrary", "breakOnCounter", "engineAfterPlanFail", "engineLongLoops",
                "engineLoopBisect", "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen",
                "engineRerun", "engineTriggerTargets", "engineVerify", "followPay", "historyLki", "landEntersProbe", "loopMarginal",
                "paySearch", "planDiscardWall", "probeSettle", "readerPerGame", "sourceColorsLive", "sourceLeaves", "sourceOutput",
                "stackHistory", "tutorCast", "unknownWorlds", "verifyReplan", "passBlockers", "planLoops", "engineModeChoice",
                "planUntaps", "planGraveCasts"}) {
            p.put(k, "true");
        }
        p.put("planOrder", "7");
        final ForcedWinSolver.Config cfg = ForcedWinSolver.Config.fromProps(p::get);
        cfg.sharedReader = false;
        cfg.honestProbes = false;
        final ForcedWinSolver s = ForcedWinSolver.withReader(me, cfg, ForcedWinSolver.newSharedReader());
        ForcedWinSolver.Proposal line = null;
        for (ForcedWinSolver.Proposal pr : s.propose(g)) {
            System.out.println("FLOOR proposal " + pr.text);
            if (pr.text != null && pr.text.contains("Underworld Breach") && (line == null || pr.pBelief > line.pBelief)) {
                line = pr;
            }
        }
        AssertJUnit.assertNotNull("the solver's line goes through the engine", line);

        final SpellAbility tideSa = tide.getFirstSpellAbility(), engineSa = engine.getFirstSpellAbility();
        final List<SpellAbility> objs = List.of(tideSa, engineSa);
        final RlCandidates.Menu m = new RlCandidates.Menu();
        for (int ch = 0; ch <= 2; ch++) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = ch == 0 ? RlSchema.K_PASS : RlSchema.K_CAST;
            final JsonObject f = new JsonObject();
            f.addProperty("choice", ch);
            c.frag = f;
            m.cands.add(c);
        }
        final Map<String, Integer> entryOf = new HashMap<>();
        entryOf.put(RlSearch.actionKey(null), 0);
        entryOf.put(RlSearch.actionKey(tideSa), 1);
        entryOf.put(RlSearch.actionKey(engineSa), 2);
        final Map<Integer, double[]> by = new TreeMap<>();
        by.put(0, new double[] {P_PASS, 0, P_PASS});
        by.put(1, new double[] {P_LAND, 1, P_LAND});
        by.put(2, new double[] {P_ENGINE, 2, P_ENGINE});

        final PlanSearch.Stage st = new PlanSearch.Stage();
        st.plans.add(new PlanSearch.LinePlan(line));
        st.labels.add("line#0");
        st.plans.add(new PlanSearch.PolicyPlan(g.getPhaseHandler().getTurn(), List.of(new PlanSearch.PStep(engineSa, "MAIN1"))));
        st.labels.add("sample#1 engine");
        st.plans.add(new PlanSearch.PolicyPlan(g.getPhaseHandler().getTurn(), List.of(new PlanSearch.PStep(tideSa, "MAIN1"))));
        st.labels.add("sample#2 boost");
        // NC06 live rerun with the floor: the look-ahead then adopted a sample playing the policy's own first move and the
        // engine next; a sampled step the policy rated below the floor drops the plan too
        final PlanSearch.PolicyPlan later = new PlanSearch.PolicyPlan(g.getPhaseHandler().getTurn(),
                List.of(new PlanSearch.PStep(tideSa, "MAIN1"), new PlanSearch.PStep(engineSa, "MAIN1")));
        later.minStepP = P_ENGINE;
        st.plans.add(later);
        st.labels.add("sample#3 boost, engine");
        AssertJUnit.assertEquals(2, RlSearch.priorFloorSamples(st, m, objs, by, entryOf, 0.05));
        AssertJUnit.assertEquals(2, st.plans.size());
        AssertJUnit.assertTrue("the solver's line through the engine is still offered", st.plans.get(0) instanceof PlanSearch.LinePlan);
        AssertJUnit.assertEquals(List.of("line#0", "sample#2 boost"), st.labels);
    }
}
