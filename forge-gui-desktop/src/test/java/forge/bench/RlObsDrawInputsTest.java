package forge.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlManaAvail;
import forge.bench.rl.RlSchema;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * v-inputs-1107. (1) obsManaAvail (off by default): manaUser / manaOpp stay 0 with the key off; with it on they are the
 * pool plus the most mana each untapped, usable source makes now, by rule objects only. (2) Leak test for the inputs the
 * draw tokens read (own known library positions, library size, rest): a top this seat did not see contributes nothing
 * beyond counts. The other seat looking at it, or re-randomising every library card the seat does not know, leaves the
 * seat's frame unchanged, on obs v1 and v2.
 */
public class RlObsDrawInputsTest extends AITest {

    @AfterMethod(alwaysRun = true)
    public void off() {
        RlManaAvail.ON = false;
    }

    private RlKnowledgeTest.Fixture fixture(final int version) {
        final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
        f.feat.setVersion(version);
        return f;
    }

    private Card land(final Player p, final String n, final boolean tapped) {
        final Card c = addCardToZone(n, p, ZoneType.Battlefield);
        c.setSickness(false);
        if (tapped) {
            c.tap(true, null, null);
        }
        return c;
    }

    @Test
    public void manaKeyOffLeavesScalarsZero() {
        final RlKnowledgeTest.Fixture f = fixture(2);
        land(f.p0, "Island", false);
        land(f.p1, "Swamp", false);
        final RlFeaturizer.Obs o = f.obs(f.p0);
        Assert.assertEquals(o.scal[7], 0f);
        Assert.assertEquals(o.scal[8], 0f);
        Assert.assertNull(o.manaAvail);
    }

    @Test
    public void manaKeyOnCountsUsableSourcesByColor() {
        final RlKnowledgeTest.Fixture f = fixture(2);
        land(f.p0, "Island", false);
        land(f.p0, "Island", false);
        land(f.p0, "Swamp", true);                         // tapped: nothing
        land(f.p0, "Sol Ring", false);                     // {T}: add {C}{C}
        final Card elf = addCardToZone("Llanowar Elves", f.p1, ZoneType.Battlefield); // summoning sick: nothing
        land(f.p1, "Forest", false);
        RlManaAvail.ON = true;
        final RlFeaturizer.Obs o = f.obs(f.p0);
        // [total, W, U, B, R, G, C]
        Assert.assertEquals(o.manaAvail[0], new int[] {4, 0, 2, 0, 0, 0, 2}, Arrays.toString(o.manaAvail[0]));
        Assert.assertEquals(o.manaAvail[1], new int[] {1, 0, 0, 0, 0, 1, 0}, Arrays.toString(o.manaAvail[1]));
        Assert.assertEquals(o.scal[7], 0.4f, 1e-6);
        Assert.assertEquals(o.scal[8], 0.1f, 1e-6);
        elf.setSickness(false);
        final RlFeaturizer.Obs o2 = f.obs(f.p0);
        Assert.assertEquals(o2.manaAvail[1], new int[] {2, 0, 0, 0, 0, 2, 0}, Arrays.toString(o2.manaAvail[1]));
        // the opponent's frame reads the same public numbers, seats swapped
        final RlFeaturizer.Obs q = f.obs(f.p1);
        Assert.assertEquals(q.manaAvail[0], o2.manaAvail[1]);
        Assert.assertEquals(q.manaAvail[1], o2.manaAvail[0]);
    }

    private static final String[] LIB = {"Island", "Swamp", "Forest", "Mountain", "Plains", "Lightning Bolt",
        "Counterspell", "Brainstorm"};

    private static String frame(final RlFeaturizer.Obs o) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < o.L; i++) {
            sb.append(o.tokZone[i]).append(':').append(o.tokCard[i]).append('@')
                    .append(o.tokAttr[i * RlSchema.N_ATTR + RlSchema.A_LIB_POS]).append(' ');
        }
        sb.append("| rest ").append(Arrays.toString(o.restCard)).append(Arrays.toString(o.restCnt));
        sb.append("| ctx ").append(Arrays.toString(o.ctx)).append("| scal ").append(Arrays.toString(o.scal));
        return sb.toString();
    }

    private static int ownKnown(final RlFeaturizer.Obs o) {
        int n = 0;
        for (int i = 0; i < o.L; i++) {
            if (o.tokZone[i] == RlSchema.Z_U_LIB_KNOWN) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void unseenTopStaysUnknownBothVersions() {
        for (int v = 1; v <= 2; v++) {
            final RlKnowledgeTest.Fixture f = fixture(v);
            for (String n : LIB) {
                addCardToZone(n, f.p0, ZoneType.Library);
            }
            final String before = frame(f.obs(f.p0));
            Assert.assertEquals(ownKnown(f.obs(f.p0)), 0);
            // the OTHER seat looks at p0's top two and leaves them (fateseal-style): p0 learns nothing
            final List<Card> top = new ArrayList<>(f.p0.getCardsIn(ZoneType.Library).subList(0, 2));
            f.know.onLook(f.game, f.p1, top, ZoneType.Library);
            Assert.assertEquals(frame(f.obs(f.p0)), before, "v" + v + ": opponent's look leaked into my frame");
            Assert.assertEquals(RlKnowledgeTest.zone(f.obs(f.p1), RlSchema.Z_O_LIB_KNOWN).size(), 2,
                    "v" + v + ": the looker knows them");
            // re-randomise every library card p0 does not know: p0's frame (tokens, rest, ctx, scal) is unchanged
            for (long seed = 1; seed <= 5; seed++) {
                final List<Card> lib = new ArrayList<>(f.p0.getCardsIn(ZoneType.Library));
                Collections.shuffle(lib, new Random(seed));
                f.p0.getZone(ZoneType.Library).setCards(lib);
                Assert.assertEquals(frame(f.obs(f.p0)), before, "v" + v + " seed " + seed);
            }
            // control: p0's own look at its top card IS known, at its real position
            final Card t = f.p0.getCardsIn(ZoneType.Library).get(0);
            f.know.onLook(f.game, f.p0, Collections.singletonList(t), ZoneType.Library);
            Assert.assertEquals(ownKnown(f.obs(f.p0)), 1, "v" + v + ": own look is known");
        }
    }
}
