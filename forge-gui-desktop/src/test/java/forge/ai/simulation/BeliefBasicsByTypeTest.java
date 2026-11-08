package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.item.PaperCard;

/**
 * bugfix-basics: the belief's basic lands came from a hard-coded name list (BeliefSampler.BASICS), so a renamed card pool
 * has no basics and every belief game fails to build its pool. With the key on they are found by type.
 */
public class BeliefBasicsByTypeTest {
    private static PaperCard card(String name, String types, String mana) {
        final List<String> script = Lists.newArrayList("Name:" + name, "Types:" + types, "Oracle:");
        if (mana != null) {
            script.add("A:AB$ Mana | Cost$ T | Produced$ " + mana + " | SpellDescription$ Add {" + mana + "}.");
        }
        return new PaperCard(CardRules.fromScript(script), "", CardRarity.Common);
    }

    /** A renamed pool: the five basics under scrambled names, plus a snow basic, a nonbasic land and a spell. */
    private static List<PaperCard> renamedPool() {
        return new ArrayList<>(Arrays.asList(
                card("Qxa", "Basic Land Plains", "W"), card("Qxb", "Basic Land Island", "U"),
                card("Qxc", "Basic Land Swamp", "B"), card("Qxd", "Basic Land Mountain", "R"),
                card("Qxe", "Basic Land Forest", "G"), card("Qaa", "Basic Snow Land Island", "U"),
                card("Qxf", "Land", "C"), card("Qxg", "Instant", null)));
    }

    @AfterMethod
    public void restore() {
        BeliefSampler.BASICS_BY_TYPE = false;
    }

    @Test
    public void keyOffUsesTheHardCodedNamesWhichARenamedPoolDoesNotHave() {
        BeliefSampler.BASICS_BY_TYPE = false;
        final List<String> pool = new ArrayList<>();
        for (PaperCard pc : renamedPool()) {
            pool.add(pc.getName());
        }
        // the wrong behaviour: with the key off, the belief's basics are names the renamed pool has none of
        for (String b : BeliefSampler.basics()) {
            Assert.assertFalse(pool.contains(b), b);
        }
        Assert.assertSame(BeliefSampler.basics(), BeliefSampler.BASICS); // keys off: exactly the baseline list
    }

    @Test
    public void keyOnFindsTheBasicsByTypeInWUBRGOrder() {
        Assert.assertEquals(BeliefSampler.basicsOf(renamedPool()), new String[] {"Qxa", "Qxb", "Qxc", "Qxd", "Qxe"});
    }

    @Test
    public void onRealNamesByTypeEqualsTheBaselineList() {
        // keys-off identity in effect: on a pool with the real basics, the by-type list is the baseline list
        final List<PaperCard> real = new ArrayList<>(Arrays.asList(
                card("Island", "Basic Land Island", "U"), card("Forest", "Basic Land Forest", "G"),
                card("Plains", "Basic Land Plains", "W"), card("Swamp", "Basic Land Swamp", "B"),
                card("Mountain", "Basic Land Mountain", "R"), card("Snow-Covered Island", "Basic Snow Land Island", "U"),
                card("Wastes", "Basic Land", "C")));
        Assert.assertEquals(BeliefSampler.basicsOf(real), BeliefSampler.BASICS);
        // and a pool with no basics at all keeps the baseline names (never fewer than five)
        Assert.assertEquals(BeliefSampler.basicsOf(new ArrayList<>()), BeliefSampler.BASICS);
    }
}
