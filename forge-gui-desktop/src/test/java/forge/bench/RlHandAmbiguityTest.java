package forge.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.bench.rl.RlSchema;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * W13 (lane rl-obs-v2-1006, finding F1): when a card leaves a player's hand without the other seat seeing which card
 * it was (a Brainstorm put-back, a foretell, a morph cast face down), that seat cannot tell whether it was a hand card
 * it knew. So it forgets every hand card of that player it knew; the moved card's identity must not reach it either,
 * neither through zone 16 nor through the NAME seen-cards set. Each scenario runs twice, once with the moved card known
 * to the observing seat and once with it unknown: the observing seat's zone 16 must be the same, and empty, both times.
 */
public class RlHandAmbiguityTest extends AITest {

    private Card add(final Player p, final ZoneType z, final String name) {
        return addCardToZone(name, p, z);
    }

    private void library(final Player p, final int n) {
        for (int i = 0; i < n; i++) {
            add(p, ZoneType.Library, "Island");
        }
    }

    private static void revealHand(final RlKnowledgeTest.Fixture f) {
        f.game.getAction().revealTo(new CardCollection(f.p1.getCardsIn(ZoneType.Hand)), f.p0);
    }

    /** Seat 0's zone 16 (the opponent hand cards it knows), card indices sorted. */
    private static List<Integer> known(final RlKnowledgeTest.Fixture f) {
        final List<Integer> out = new ArrayList<>();
        for (int[] t : RlKnowledgeTest.zone(f.obs(f.p0), RlSchema.Z_O_HAND_KNOWN)) {
            out.add(t[0]);
        }
        Collections.sort(out);
        return out;
    }

    /** Brainstorm's put-back: one card from a hand seat 0 partly knows goes on top of the library. */
    @Test
    public void putBackForgetsTheWholeKnownHand() {
        final List<List<Integer>> after = new ArrayList<>();
        for (boolean knownCard : new boolean[] {true, false}) {
            final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
            library(f.p1, 8);
            final Card bolt = add(f.p1, ZoneType.Hand, "Lightning Bolt");
            add(f.p1, ZoneType.Hand, "Dark Ritual");
            add(f.p1, ZoneType.Hand, "Counterspell");
            revealHand(f);
            Assert.assertEquals(known(f).size(), 3, "the revealed hand is known");
            final Card drawn = f.p1.drawCard().get(0); // Brainstorm's draw: unknown to seat 0
            f.game.getAction().moveToLibrary(knownCard ? bolt : drawn, 0, null);
            after.add(known(f));
            Assert.assertEquals(RlKnowledgeTest.zone(f.obs(f.p1), RlSchema.Z_U_HAND).size(), 3,
                    "the owner still sees its own hand");
        }
        Assert.assertEquals(after.get(0), after.get(1),
                "seat 0 must not see whether the put-back card was one it knew (known: " + after.get(0)
                        + ", unknown: " + after.get(1) + ")");
        Assert.assertTrue(after.get(0).isEmpty(), "every known hand card is forgotten: " + after.get(0));
    }

    /** Foretell: exiled from the hand face down (Forge exiles it face up, then turns it face down). */
    @Test
    public void foretellForgetsTheWholeKnownHandAndNamesNothing() {
        final List<List<Integer>> after = new ArrayList<>();
        for (boolean knownCard : new boolean[] {true, false}) {
            final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
            library(f.p1, 8);
            add(f.p1, ZoneType.Hand, "Lightning Bolt");
            Card saw = null;
            if (knownCard) {
                saw = add(f.p1, ZoneType.Hand, "Saw It Coming");
            } else {
                add(f.p1, ZoneType.Hand, "Dark Ritual");
            }
            revealHand(f);
            Assert.assertEquals(known(f).size(), 2);
            if (!knownCard) {
                saw = add(f.p1, ZoneType.Hand, "Saw It Coming"); // drawn later: never shown to seat 0
            }
            SpellAbility fs = null;
            for (SpellAbility sa : saw.getSpellAbilities()) {
                if (sa.isForetelling()) {
                    fs = sa;
                }
            }
            Assert.assertNotNull(fs, "Saw It Coming's foretell ability");
            fs.setActivatingPlayer(f.p1);
            fs.resolve();
            Card exiled = null;
            for (Card c : f.p1.getCardsIn(ZoneType.Exile)) {
                exiled = c;
            }
            Assert.assertNotNull(exiled);
            Assert.assertTrue(exiled.isFaceDown(), "foretold face down");
            after.add(known(f));
            Assert.assertEquals(f.know.seenOpponentNames(0).contains("Saw It Coming"), knownCard,
                    "seat 0 has seen Saw It Coming only if it was revealed: " + f.know.seenOpponentNames(0));
        }
        Assert.assertTrue(after.get(0).isEmpty() && after.get(1).isEmpty(),
                "every known hand card is forgotten (known: " + after.get(0) + ", unknown: " + after.get(1) + ")");
    }

    /** A morph cast face down from a hand seat 0 partly knows. */
    @Test
    public void morphCastForgetsTheWholeKnownHandAndNamesNothing() {
        final List<List<Integer>> after = new ArrayList<>();
        for (boolean knownCard : new boolean[] {true, false}) {
            final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
            library(f.p1, 8);
            add(f.p1, ZoneType.Hand, "Lightning Bolt");
            Card angel = null;
            if (knownCard) {
                angel = add(f.p1, ZoneType.Hand, "Exalted Angel");
            } else {
                add(f.p1, ZoneType.Hand, "Dark Ritual");
            }
            revealHand(f);
            Assert.assertEquals(known(f).size(), 2);
            if (!knownCard) {
                angel = add(f.p1, ZoneType.Hand, "Exalted Angel");
            }
            SpellAbility morph = null;
            for (SpellAbility sa : angel.getSpellAbilities()) {
                if (sa.isCastFaceDown()) {
                    morph = sa;
                }
            }
            Assert.assertNotNull(morph, "Exalted Angel's face-down cast");
            morph.setActivatingPlayer(f.p1);
            angel.setSplitStateToPlayAbility(morph); // as PlaySpellAbility does before the move
            final Card onStack = f.game.getAction().moveToStack(angel, morph);
            Assert.assertTrue(onStack.isFaceDown(), "cast face down");
            after.add(known(f));
            Assert.assertEquals(f.know.seenOpponentNames(0).contains("Exalted Angel"), knownCard,
                    "seat 0 has seen Exalted Angel only if it was revealed: " + f.know.seenOpponentNames(0));
        }
        Assert.assertTrue(after.get(0).isEmpty() && after.get(1).isEmpty(),
                "every known hand card is forgotten (known: " + after.get(0) + ", unknown: " + after.get(1) + ")");
    }

    /** A card exiled face down from a library (ChangeZone ExileFaceDown: exiled, then turned face down) names nothing. */
    @Test
    public void faceDownExileFromALibraryNamesNothing() {
        final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
        library(f.p1, 4);
        final Card recall = add(f.p1, ZoneType.Library, "Ancestral Recall");
        final Card ex = f.game.getAction().exile(recall, null, null);
        ex.turnFaceDown(true);
        f.obs(f.p0);
        Assert.assertFalse(f.know.seenOpponentNames(0).contains("Ancestral Recall"),
                "a card exiled face down was never shown: " + f.know.seenOpponentNames(0));
        // control: a card exiled face up is seen
        final Card pearl = add(f.p1, ZoneType.Library, "Mox Pearl");
        f.game.getAction().exile(pearl, null, null);
        f.obs(f.p0);
        Assert.assertTrue(f.know.seenOpponentNames(0).contains("Mox Pearl"), "a face-up exile is seen");
    }

    /** Control: cards that leave the hand face up (cast, discarded, exiled as a cost) leave the rest known. */
    @Test
    public void seenDeparturesKeepTheRest() {
        final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
        library(f.p1, 8);
        final Card bolt = add(f.p1, ZoneType.Hand, "Lightning Bolt");
        add(f.p1, ZoneType.Hand, "Dark Ritual");
        final Card spell = add(f.p1, ZoneType.Hand, "Counterspell");
        final Card pearl = add(f.p1, ZoneType.Hand, "Mox Pearl");
        revealHand(f);
        f.p1.drawCard(); // an unknown card joins the hand
        Assert.assertEquals(known(f).size(), 4);
        f.game.getAction().moveToStack(spell, null); // cast
        Assert.assertEquals(known(f).size(), 3, "cast face up: only that card leaves zone 16");
        f.game.getAction().moveToGraveyard(bolt, null); // discarded
        Assert.assertEquals(known(f).size(), 2, "discarded: only that card leaves zone 16");
        f.game.getAction().exile(pearl, null, null); // exiled face up (a pitch cost)
        final List<Integer> left = known(f);
        Assert.assertEquals(left.size(), 1, "exiled face up: only that card leaves zone 16");
        Assert.assertEquals(left.get(0).intValue(), RlKnowledgeTest.idx("Dark Ritual"));
        Assert.assertTrue(f.know.seenOpponentNames(0).contains("Counterspell"));
        Assert.assertTrue(f.know.seenOpponentNames(0).contains("Mox Pearl"));
    }
}
