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
}
