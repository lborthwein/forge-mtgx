package forge.bench.rl;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Lane unified-search-1106: a plan step lost its X at the recording boundary; matching took the first X variant. */
public class PlanStepXTest extends SimulationTest {
    private static RlCandidates.Menu menuOfX(final int top) {
        final RlCandidates.Menu m = new RlCandidates.Menu();
        for (int x = 0; x <= top; x++) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = 2;
            final JsonObject f = new JsonObject();
            f.addProperty("choice", 1);
            f.addProperty("x", x);
            c.frag = f;
            m.cands.add(c);
        }
        return m;
    }

    @Test
    public void matchKeepsTheRecordedX() {
        final Game game = initAndCreateGame();
        final Card c = addCardToZone("Fireball", game.getPlayers().get(0), ZoneType.Hand);
        final SpellAbility sa = c.getFirstSpellAbility();
        final List<SpellAbility> objs = List.of(sa);
        final RlCandidates.Menu m = menuOfX(7);
        AssertJUnit.assertEquals("without a recorded X: the first variant (the loss)", 0, PlanSearch.match(m, objs, new PlanSearch.PStep(sa, "MAIN1")));
        AssertJUnit.assertEquals("the recorded X", 5, PlanSearch.match(m, objs, new PlanSearch.PStep(sa, "MAIN1", 5)));
        AssertJUnit.assertEquals("an X no longer offered: the first variant", 0, PlanSearch.match(m, objs, new PlanSearch.PStep(sa, "MAIN1", 12)));
    }

    /** pr209-followups (review of #209): recording, key off vs key on (plans.planX sets Recorder.recordX). */
    @Test
    public void recordingKeepsTheAnnouncedXOnlyWithTheKey() {
        final Game game = initAndCreateGame();
        final forge.game.player.Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, p);
        final Card c = addCardToZone("Fireball", p, ZoneType.Hand);
        final SpellAbility sa = c.getFirstSpellAbility();
        final List<SpellAbility> objs = List.of(sa);
        final int turn = game.getPhaseHandler().getTurn();
        AssertJUnit.assertFalse("default off", PlanSearch.Spec.parse(new JsonObject()).planX);
        final JsonObject on = new JsonObject();
        on.addProperty("planX", true);
        AssertJUnit.assertTrue(PlanSearch.Spec.parse(on).planX);

        final PlanSearch.Recorder off = new PlanSearch.Recorder(turn, 0L, 1.0, false, 10);
        off.answered(game, p, null, objs, 1, 5);
        off.answered(game, p, null, objs, 0, -1);
        AssertJUnit.assertEquals(2, off.steps.size());
        AssertJUnit.assertEquals("key off: the step has no X", -1, off.steps.get(0).x);
        AssertJUnit.assertEquals("key off: the step key is unchanged", new PlanSearch.PStep(sa, "MAIN1").key(), off.steps.get(0).key());
        AssertJUnit.assertEquals("key off: matching takes the first variant", 0, PlanSearch.match(menuOfX(7), objs, off.steps.get(0)));

        final PlanSearch.Recorder rec = new PlanSearch.Recorder(turn, 0L, 1.0, false, 10);
        rec.recordX = true;
        rec.answered(game, p, null, objs, 1, 5);
        rec.answered(game, p, null, objs, 1, -1);
        rec.answered(game, p, null, objs, 0, -1);
        AssertJUnit.assertEquals(3, rec.steps.size());
        AssertJUnit.assertEquals("key on: the announced X", 5, rec.steps.get(0).x);
        AssertJUnit.assertTrue(rec.steps.get(0).key(), rec.steps.get(0).key().endsWith("|x5"));
        AssertJUnit.assertEquals("key on: matching keeps it", 5, PlanSearch.match(menuOfX(7), objs, rec.steps.get(0)));
        AssertJUnit.assertEquals("key on, no X announced: none recorded", -1, rec.steps.get(1).x);
        AssertJUnit.assertTrue("a pass", rec.steps.get(2).pass);

        final PlanSearch.Recorder later = new PlanSearch.Recorder(turn + 1, 0L, 1.0, false, 10);
        later.recordX = true;
        later.answered(game, p, null, objs, 1, 5);
        AssertJUnit.assertTrue("outside the plan's turn: nothing recorded", later.steps.isEmpty());
    }
}
