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
        final java.nio.file.Path local = java.nio.file.Path.of("res/languages");
        final java.nio.file.Path languages = java.nio.file.Files.isDirectory(local) ? local
                : java.nio.file.Path.of("../forge-gui/res/languages");
        forge.util.Localizer.getInstance().initialize("en-US", languages.toString());
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

    /**
     * Compiles against the frozen baseline too. The opt-in representation is discovered reflectively;
     * on the baseline the existing recording API sees the already-announced amount, then loses it.
     */
    private static PlanSearch.PStep exactStep(final SpellAbility sa, final String phase, final int x) throws Exception {
        try {
            final java.lang.reflect.Constructor<PlanSearch.PStep> ctor = PlanSearch.PStep.class
                    .getDeclaredConstructor(SpellAbility.class, String.class, Integer.class);
            ctor.setAccessible(true);
            return ctor.newInstance(sa, phase, Integer.valueOf(x));
        } catch (NoSuchMethodException baseline) {
            sa.setXManaCostPaid(x);
            return new PlanSearch.PStep(sa, phase);
        }
    }

    @Test
    public void announcedXSurvivesReorderingAndMissingAnswerFailsClosed() throws Exception {
        final SpellAbility sa = ability();
        // The ability can still carry an old announcement when the bridge reports the new answer.
        sa.setXManaCostPaid(3);
        final PlanSearch.PStep step = exactStep(sa, "MAIN1", 7);
        final RlCandidates.Menu m = menu(0, 7, 3);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), step), 1);
        Collections.rotate(m.cands, 1);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), step), 2);
        Assert.assertEquals(PlanSearch.match(menu(0, 3), List.of(sa), step), -1);
        Assert.assertNotEquals(step.key(), exactStep(sa, "MAIN1", 3).key());
    }

    @Test
    public void absentAnnouncementDiffersFromZeroAndLegacyIsUnchanged() throws Exception {
        final SpellAbility sa = ability();
        final RlCandidates.Menu m = menu(0, -1);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), exactStep(sa, "MAIN1", -1)), 1);
        Assert.assertEquals(PlanSearch.match(m, List.of(sa), new PlanSearch.PStep(sa, "MAIN1")), 0);
    }
}
