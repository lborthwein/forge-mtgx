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

    // ------------------------------------------------------------------------------------------------ owner scenarios
    // (test scenarios only: nothing in the code knows these cards; the values come from the R3 row search: oppMin at the
    // root window, Forge AI seats in the lines, a terminal leaf (1 / 0), else the stub value below)

    private static JsonObject scenarioSpec() {
        final JsonObject o = R3LabelSearchTest.base();
        o.addProperty("instantCands", true);
        o.addProperty("leaf", "value");
        o.addProperty("horizon", 2);
        o.addProperty("oppMin", true);
        o.addProperty("oppMinIn", "both");
        o.addProperty("selectWorlds", 1);
        o.addProperty("targetWorlds", 2);
        return o;
    }

    private static int find(final List<R3LabelSearch.PCand> cands, final String label, final boolean pass) {
        for (int i = 0; i < cands.size(); i++) {
            final R3LabelSearch.PCand c = cands.get(i);
            if (pass ? c.pass && c.hold == null : !c.pass && c.label.startsWith(label)) return i;
        }
        return -1;
    }

    private static double mean(final JsonObject j, final int i) {
        return j.getAsJsonArray("cands").get(i).getAsJsonObject().get("mean").getAsDouble();
    }

    /** They are at 3 with Counterspell and two untapped Islands; we hold Vampiric Tutor and one Bolt, Badlands + Mountain. */
    private Game vampiricBoard(final PhaseType phase, final boolean theirs) {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        for (int i = 0; i < 12; i++) addCardToZone("Swamp", a, ZoneType.Library);
        addCardToZone("Lightning Bolt", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Island", b, ZoneType.Library);
        addCardToZone("Vampiric Tutor", a, ZoneType.Hand);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        addCard("Badlands", a);
        addCard("Mountain", a);
        addCardToZone("Counterspell", b, ZoneType.Hand);
        addCard("Island", b);
        addCard("Island", b);
        b.setLife(3, null);
        g.getPhaseHandler().devModeSet(phase, theirs ? b : a);
        g.getAction().checkStateEffects(true);
        return g;
    }

    @Test
    public void vampiricAtTheirEndStepBeatsOurUpkeepAgainstAnOpenCounterspell() {
        // at their end step Vampiric uses last turn's mana: on our turn both lands cast two Bolts into one Counterspell
        final Game g = vampiricBoard(PhaseType.END_OF_TURN, true);
        final Player a = g.getPlayers().get(0);
        final R3LabelSearch r3 = new R3LabelSearch(R3LabelSearchTest.spec(scenarioSpec()),
                R3LabelSearchTest.env(R3LabelSearchTest.flat(0.0), null));
        final List<R3LabelSearch.PCand> cands = r3.candidates(g, a, null);
        final int vt = find(cands, "Vampiric Tutor", false), pass = find(cands, "", true);
        AssertJUnit.assertTrue(String.valueOf(cands), vt >= 0 && pass >= 0);
        final JsonObject es = r3.priorityRow(g, a, cands, 21L).toJson();
        System.err.println("[endstep-test] vampiric end-step row " + es);
        final Game u = vampiricBoard(PhaseType.UPKEEP, false);
        final Player au = u.getPlayers().get(0);
        final List<R3LabelSearch.PCand> cu = r3.candidates(u, au, null);
        final int vu = find(cu, "Vampiric Tutor", false);
        AssertJUnit.assertTrue(String.valueOf(cu), vu >= 0);
        final JsonObject up = r3.priorityRow(u, au, cu, 21L).toJson();
        System.err.println("[endstep-test] vampiric upkeep row " + up);
        // measured 11-03: hold ties end step (1.0 / 1.0) in the selection world: after the root window the opponent is
        // Forge AI (an expectation, not the MIN) and does not hold its Counterspell for the second Bolt. A stated gap
        // (L3: the opponent's replies on later turns by search), so only "never worse" is asserted here.
        AssertJUnit.assertTrue("end step never worse than hold: " + es, mean(es, vt) >= mean(es, pass));
        AssertJUnit.assertTrue("end step beats our upkeep: " + es + " / " + up, mean(es, vt) > mean(up, vu));
    }

    /** They are at 1 with Chainer's Edict (a sorcery) in hand; we hold Spectral Sailor (flash 1/1 flier) and an Island. */
    private Game flashBoard(final PhaseType phase, final boolean theirs) {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        for (int i = 0; i < 20; i++) addCardToZone("Island", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Swamp", b, ZoneType.Library);
        addCardToZone("Spectral Sailor", a, ZoneType.Hand);
        addCard("Island", a);
        addCardToZone("Chainer's Edict", b, ZoneType.Hand);
        for (int i = 0; i < 2; i++) addCard("Swamp", b);
        b.setLife(1, null);
        g.getPhaseHandler().devModeSet(phase, theirs ? b : a);
        g.getAction().checkStateEffects(true);
        return g;
    }

    @Test
    public void flashCreatureAtTheirEndStepBeatsOurMainIntoSorceryRemoval() {
        final ChooseACardTest.Stub zero = R3LabelSearchTest.flat(0.0);
        final JsonObject o = scenarioSpec();
        o.addProperty("horizon", 3); // through their next turn (their Edict) to our following turn
        final R3LabelSearch r3 = new R3LabelSearch(R3LabelSearchTest.spec(o), R3LabelSearchTest.env(zero, null));
        final Game g = flashBoard(PhaseType.END_OF_TURN, true);
        final Player a = g.getPlayers().get(0);
        final List<R3LabelSearch.PCand> cands = r3.candidates(g, a, null);
        final int fl = find(cands, "Spectral Sailor", false);
        AssertJUnit.assertTrue(String.valueOf(cands), fl >= 0);
        final JsonObject es = r3.priorityRow(g, a, cands, 23L).toJson();
        System.err.println("[endstep-test] flash end-step row " + es);
        final Game m = flashBoard(PhaseType.MAIN1, false);
        final Player am = m.getPlayers().get(0);
        final List<R3LabelSearch.PCand> cm = r3.candidates(m, am,
                java.util.Collections.singletonList(R3LabelSearchTest.firstSpell(am, "Spectral Sailor")));
        final JsonObject mm = r3.priorityRow(m, am, cm.subList(0, 1), 23L).toJson();
        System.err.println("[endstep-test] flash main row " + mm);
        AssertJUnit.assertEquals("end step: it attacks on our turn for the last point " + es, 1.0, mean(es, fl), 1e-9);
        AssertJUnit.assertTrue("main phase: summoning sick, then their Edict " + mm, mean(mm, 0) < mean(es, fl));
    }

    @Test
    public void liveLookaheadOffTurnBreadthKeepsEveryInstantAtTheirEndStep() {
        // the live look-ahead (LookaheadSearch) enumerates Forge's answer, pass and the next legal actions in Forge's own
        // order, cut at breadth B: with five castable instants at their end step B = 2 keeps one; offTurnBreadth keeps all
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        for (int i = 0; i < 20; i++) addCardToZone("Island", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Plains", b, ZoneType.Library);
        for (String n : new String[] {"Pestermite", "Brainstorm", "Lightning Bolt", "Vampiric Tutor", "Opt"}) {
            addCardToZone(n, a, ZoneType.Hand);
        }
        for (String n : new String[] {"Volcanic Island", "Underground Sea", "Island", "Mountain"}) addCard(n, a);
        addCard("Plains", b);
        g.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, b);
        g.getAction().checkStateEffects(true);
        final LookaheadSearch.Config c0 = new LookaheadSearch.Config();
        c0.breadth = 2;
        final int n0 = new LookaheadSearch(c0).enumerate(g, a, null, 5L, null).size();
        final LookaheadSearch.Config c1 = new LookaheadSearch.Config();
        c1.breadth = 2;
        c1.offTurnBreadth = 8;
        final List<LookaheadSearch.Cand> on = new LookaheadSearch(c1).enumerate(g, a, null, 5L, null);
        System.err.println("[endstep-test] live enumerate B=2: " + n0 + " candidates; offTurnBreadth=8: " + on.size());
        AssertJUnit.assertEquals(2, n0);
        AssertJUnit.assertTrue(String.valueOf(on.size()), on.size() >= 6);
        AssertJUnit.assertFalse(c0.toJson().has("offTurnBreadth"));
        // our own main phase: the key changes nothing
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        AssertJUnit.assertEquals(new LookaheadSearch(c0).enumerate(g, a, null, 5L, null).size(),
                new LookaheadSearch(c1).enumerate(g, a, null, 5L, null).size());
    }
}
