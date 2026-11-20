package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * target-picks-1119, review 1 of b8b01a0ed3e: the given path's variant filter ({@link LookaheadSearch#variantLegal}) on
 * the variants K8's generator ({@code targetVariants}) actually offers. A divided-amount ability is never offered (S1 3),
 * and a variant whose target set fails Forge's MustTarget restriction is not offered (S1 2).
 */
public class TargetVariantLegalTest extends SimulationTest {
    private Game game() {
        final Game g = initAndCreateGame();
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, g.getPlayers().get(0));
        return g;
    }

    /** The generator's variants (their target refs) of {@code sa}, as the reviewer's repro builds them. */
    private static List<LookaheadSearch.Cand> variants(final Game g, final Player p, final SpellAbility sa) {
        final List<LookaheadSearch.Cand> out = new ArrayList<>();
        out.add(new LookaheadSearch.Cand(sa, true));
        final java.lang.reflect.Method gen;
        try {
            gen = LookaheadSearch.class.getDeclaredMethod("targetVariants", Game.class, Player.class, List.class,
                    SpellAbility.class, java.util.Set.class, int.class);
            gen.setAccessible(true);
            gen.invoke(null, g, p, out, null, new HashSet<String>(), 16);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        out.remove(0);
        return out;
    }

    private static List<String> refs(final List<LookaheadSearch.Cand> vs) {
        final List<String> r = new ArrayList<>();
        for (LookaheadSearch.Cand v : vs) {
            r.add(v.tgt);
        }
        return r;
    }

    /**
     * Impact Resonance ("TargetMax$ Count$Valid Creature") with one creature on the battlefield has a maximum of one
     * target from hand, so the generator offers its card variant; the filter refuses it. (Volcanic Wind, the reviewer's
     * card, counts the last-state battlefield of a cast spell only: 0, so no slot, until it is cast.)
     */
    @Test
    public void aDividedVariantIsNotOffered() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card bear = addCard("Grizzly Bears", q);
        addCard("Mountain", p);
        addCard("Mountain", p);
        final SpellAbility wind = addCardToZone("Volcanic Wind", p, ZoneType.Hand).getFirstSpellAbility();
        wind.setActivatingPlayer(p);
        Assert.assertEquals(wind.getMaxTargets(), 0, "Volcanic Wind from hand: no target slot");
        final SpellAbility sa = addCardToZone("Impact Resonance", p, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        Assert.assertTrue(sa.isDividedAsYouChoose());
        Assert.assertEquals(sa.getMaxTargets(), 1);
        final List<LookaheadSearch.Cand> vs = variants(g, p, sa);
        System.out.println("[target-variant-legal] Impact Resonance variants " + refs(vs));
        Assert.assertFalse(vs.isEmpty(), "the generator offers divided variants");
        for (LookaheadSearch.Cand v : vs) {
            Assert.assertFalse(LookaheadSearch.variantLegal(g, p, v), "a divided variant is never offered: " + v.tgt);
        }
        System.out.println("[target-variant-legal] Bears variant generated: " + refs(vs).contains("C" + bear.getId()));
    }

    /** Their Standard Bearer: Lightning Bolt's face variant fails MustTarget and is not offered; the Flagbearer's is. */
    @Test
    public void aVariantPastStandardBearerIsNotOffered() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card flag = addCard("Standard Bearer", q);
        addCard("Grizzly Bears", q);
        addCard("Mountain", p);
        final SpellAbility sa = addCardToZone("Lightning Bolt", p, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        final List<LookaheadSearch.Cand> vs = variants(g, p, sa);
        System.out.println("[target-variant-legal] Lightning Bolt variants " + refs(vs));
        boolean face = false, flagbearer = false;
        for (LookaheadSearch.Cand v : vs) {
            final boolean ok = LookaheadSearch.variantLegal(g, p, v);
            if (v.tgt.startsWith("P")) {
                face = true;
                Assert.assertFalse(ok, "a face variant fails MustTarget: " + v.tgt);
            } else if (v.tgt.equals("C" + flag.getId())) {
                flagbearer = true;
                Assert.assertTrue(ok, "the Flagbearer's variant meets it");
            } else {
                Assert.assertFalse(ok, "a non-Flagbearer variant fails MustTarget: " + v.tgt);
            }
        }
        Assert.assertTrue(face, "the generator offers a face variant: " + refs(vs));
        Assert.assertTrue(sa.getTargets().isEmpty(), "the filter leaves no target behind: " + sa.getTargets());
        System.out.println("[target-variant-legal] Flagbearer variant generated: " + flagbearer
                + " (absent when it is Forge AI's own pick, which variants leave out)");
    }

    /** Control: no MustTarget static, the same Bolt's variants all pass the filter. */
    @Test
    public void withoutAFlagbearerBoltVariantsAreOffered() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        addCard("Grizzly Bears", q);
        addCard("Llanowar Elves", q);
        addCard("Mountain", p);
        final SpellAbility sa = addCardToZone("Lightning Bolt", p, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        final List<LookaheadSearch.Cand> vs = variants(g, p, sa);
        Assert.assertFalse(vs.isEmpty());
        for (LookaheadSearch.Cand v : vs) {
            Assert.assertTrue(LookaheadSearch.variantLegal(g, p, v), "offered: " + v.tgt);
        }
    }
}
