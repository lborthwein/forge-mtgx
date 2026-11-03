package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * endstep-rows-1103 (N12): the opponent's end step as a searched window. (1) R3 instantCands: a castable flash creature at
 * their end step is a priority-row candidate (off: the row has no action besides pass, so it is never searched).
 * (2) the solver's engine at their end step (engineAnyWindow, engineEndStepOpen): a flash play now, the win on our next
 * turn. Off-identity of both keys.
 */
public class EndStepRowsTest extends SimulationTest {

    /** Their end step (b active); we hold Pestermite with three untapped lands; both libraries 20 lands. */
    private Game theirEndStep() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        for (int i = 0; i < 20; i++) addCardToZone("Mountain", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Plains", b, ZoneType.Library);
        addCardToZone("Pestermite", a, ZoneType.Hand);
        addCard("Island", a);
        addCard("Island", a);
        addCard("Mountain", a);
        addCard("Plains", b);
        g.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, b);
        g.getAction().checkStateEffects(true);
        return g;
    }

    @Test
    public void instantCandsOffIsUnchangedAndOnOffersTheFlashCreature() {
        // the identity: off (absent or false) = the default sha; on = a new sha
        final String sha0 = R3LabelSearch.Spec.parse(new JsonObject()).sha();
        final JsonObject off = new JsonObject();
        off.addProperty("instantCands", false);
        AssertJUnit.assertEquals(sha0, R3LabelSearch.Spec.parse(off).sha());
        final JsonObject onj = new JsonObject();
        onj.addProperty("instantCands", true);
        AssertJUnit.assertFalse(sha0.equals(R3LabelSearch.Spec.parse(onj).sha()));

        final Game g = theirEndStep();
        final Player a = g.getPlayers().get(0);
        final R3LabelSearch base = new R3LabelSearch(R3LabelSearchTest.spec(R3LabelSearchTest.base()),
                R3LabelSearchTest.env(R3LabelSearchTest.flat(0.5), null));
        final List<R3LabelSearch.PCand> c0 = base.candidates(g, a, null);
        AssertJUnit.assertFalse("off: their end step is uncontested " + c0, R3LabelSearch.contested(c0));

        final JsonObject o = R3LabelSearchTest.base();
        o.addProperty("instantCands", true);
        o.addProperty("leaf", "value");
        o.addProperty("horizon", 0);
        // value: 0.8 when our Pestermite is on the battlefield at the leaf, else 0.2 (a stub: the plumbing, not a policy)
        final ChooseACardTest.Stub v = new ChooseACardTest.Stub((x, p) ->
                p.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals("Pestermite")) ? 0.8 : 0.2);
        final R3LabelSearch r3 = new R3LabelSearch(R3LabelSearchTest.spec(o), R3LabelSearchTest.env(v, null));
        final List<R3LabelSearch.PCand> cands = r3.candidates(g, a, null);
        AssertJUnit.assertTrue("on: contested " + cands, R3LabelSearch.contested(cands));
        int pm = -1;
        for (int i = 0; i < cands.size(); i++) {
            if ("instant".equals(cands.get(i).src) && cands.get(i).label.startsWith("Pestermite")) pm = i;
        }
        AssertJUnit.assertTrue(String.valueOf(cands), pm >= 0);
        final String fp = LookaheadSearch.fingerprint(g);
        final R3LabelSearch.Result res = r3.priorityRow(g, a, cands, 11L);
        AssertJUnit.assertEquals("the live game never moves", fp, LookaheadSearch.fingerprint(g));
        final JsonObject j = res.toJson();
        System.err.println("[endstep-test] r3 row " + j);
        final JsonArray ca = j.getAsJsonArray("cands");
        AssertJUnit.assertEquals(j.toString(), 0.8, ca.get(pm).getAsJsonObject().get("mean").getAsDouble(), 1e-9);
        AssertJUnit.assertEquals(j.toString(), pm, res.pick);
        // main phase: the key adds nothing (our main phase's menu is the recorded move's and the policy's)
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        AssertJUnit.assertEquals(base.candidates(g, a, null).size(), r3.candidates(g, a, null).size());
    }

    private static List<ForcedWinSolver.Proposal> propose(final Game g, final String keys) {
        final java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String kv : keys.split(",")) {
            if (!kv.isEmpty()) m.put(kv.split("=")[0], kv.split("=")[1]);
        }
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(m::get));
        final List<ForcedWinSolver.Proposal> p = s.propose(g);
        System.err.println("[endstep-test] keys=" + keys + " proposals=" + p.size() + " meter=" + s.meter.toJson());
        for (ForcedWinSolver.Proposal x : p) System.err.println("[endstep-test]   " + x.text);
        return p;
    }

    @Test
    public void theEngineAtTheirEndStepFindsTheFlashSetupKill() {
        // their end step, ai at 6: we hold Pestermite and Kiki-Jiki with five lands. Flash Pestermite now (3 mana), pass,
        // untap, Kiki-Jiki (5 mana), copy Pestermite (its trigger untaps Kiki-Jiki) repeatedly, attack with hasty copies.
        final String frame = "[state]\nturn=7\nactiveplayer=ai\nactivephase=END_OF_TURN\nhumanlife=20\nailife=6\n"
                + "humanlandsplayed=0\nailandsplayed=0\naihand=\nailibrary=" + ForcedWinSolverTest.LIB20
                + "\naigraveyard=\naiexile=\naibattlefield=Forest\nhumanhand=Pestermite;Kiki-Jiki, Mirror Breaker"
                + "\nhumanbattlefield=Volcanic Island;Volcanic Island;Mountain;Mountain;Mountain\nhumangraveyard=\nhumanexile=\n"
                + "humanlibrary=" + ForcedWinSolverTest.OURLIB + "\n";
        final Game g0 = ForcedWinSolverTest.game("Default", "Default", frame);
        AssertJUnit.assertTrue("off: no engine at their end step", propose(g0, "cycles=true").isEmpty());
        final Game g1 = ForcedWinSolverTest.game("Default", "Default", frame);
        final List<ForcedWinSolver.Proposal> aw = propose(g1, "cycles=true,engineAnyWindow=true");
        final Game g2 = ForcedWinSolverTest.game("Default", "Default", frame);
        final List<ForcedWinSolver.Proposal> awo = propose(g2, "cycles=true,engineAnyWindow=true,engineEndStepOpen=true");
        AssertJUnit.assertFalse("anyWindow + endStepOpen finds the end-step setup", awo.isEmpty());
        AssertJUnit.assertTrue(awo.get(0).text, awo.get(0).text.contains("Pestermite"));
        System.err.println("[endstep-test] anyWindow alone found " + aw.size());
        // the generator spec records the key only when on
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> null);
        AssertJUnit.assertFalse(HonestGenerator.solverCfgJson(c).has("engineEndStepOpen"));
    }
}
