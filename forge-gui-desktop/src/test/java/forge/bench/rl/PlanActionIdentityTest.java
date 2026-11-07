package forge.bench.rl;

import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Action round trips must not depend on the order of numerically different menu entries. */
public class PlanActionIdentityTest {
    @BeforeClass
    public void setup() {
        forge.util.Localizer.getInstance().initialize("en-US", "res/languages/");
    }

    private static RlCandidates.Menu menu(int... xs) {
        final RlCandidates.Menu m = new RlCandidates.Menu();
        for (int x : xs) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = RlSchema.K_ACTIVATE;
            final JsonObject f = new JsonObject();
            f.addProperty("choice", 1);
            if (x >= 0) f.addProperty("x", x);
            c.frag = f;
            m.cands.add(c);
        }
        return m;
    }

    private static SpellAbility ability() {
        final Card c = new Card(1, null);
        c.setName("opaque-object");
        return new SpellAbility.EmptySa(c, null);
    }

    @Test
    public void announcedXSurvivesReorderingAndMissingAnswerFailsClosed() {
        final SpellAbility sa = ability();
        // The ability can still carry an old announcement when the bridge reports the new answer.
        sa.setXManaCostPaid(3);
        final PlanSearch.PStep step = new PlanSearch.PStep(sa, "MAIN1", 7);
        final RlCandidates.Menu m = menu(0, 7, 3);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), step), 1);
        Collections.rotate(m.cands, 1);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), step), 2);
        Assert.assertEquals(PlanSearch.match(menu(0, 3), List.of(sa), step), -1);
        Assert.assertNotEquals(step.key(), new PlanSearch.PStep(sa, "MAIN1", 3).key());
    }

    @Test
    public void absentAnnouncementDiffersFromZeroAndLegacyIsUnchanged() {
        final SpellAbility sa = ability();
        final RlCandidates.Menu m = menu(0, -1);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), new PlanSearch.PStep(sa, "MAIN1", -1)), 1);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), new PlanSearch.PStep(sa, "MAIN1")), 0);
        Assert.assertFalse(PlanSearch.Spec.parse(new JsonObject()).exactActions);
        final JsonObject enabled = new JsonObject();
        enabled.addProperty("exactActions", true);
        Assert.assertTrue(PlanSearch.Spec.parse(enabled).exactActions);
    }
}
