package forge.bench;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlSchema;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane knowledge-searched-top-1105 (research key {@link RlKnowledge#SEARCHED_TOP}, off by default): a card a seat searched
 * for and put on top of its own library stays known to that seat (zone 17); the other seat's zone 18 is unchanged (a
 * revealing tutor shows the placed card, already known to it with the key off); nothing else changes. Fixtures: the four top tutors on both seats, Brainstorm as the control (already known
 * with the key off, unchanged with it on). Leak tests: (i) every token the key adds is the searched card, at its real
 * place, for a seat that saw it; (ii) re-randomising every library card the tracker does not know leaves every frame
 * unchanged; (iii) mutants that must teach nothing: the opponent's unrevealed tutor, a search whose card is shuffled
 * after it is placed, a library-to-library move of a card nobody searched for.
 */
public class RlKnowledgeSearchedTopTest extends AITest {

    static final String[] NAMES = {"Imperial Seal", "Vampiric Tutor", "Mystical Tutor", "Worldly Tutor", "Brainstorm",
        "Lightning Bolt", "Counterspell", "Llanowar Elves", "Sol Ring", "Island", "Swamp", "Forest", "Mountain",
        "Ponder", "Grizzly Bears", "Ancestral Recall", "Time Walk"};
    static final String[] LIB = {"Lightning Bolt", "Counterspell", "Llanowar Elves", "Sol Ring", "Island", "Swamp",
        "Forest", "Mountain", "Ponder", "Grizzly Bears", "Ancestral Recall", "Time Walk"};

    static CardIndex index() {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append('\t').append(i + 2).append('\n');
        }
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @AfterMethod(alwaysRun = true)
    public void off() {
        RlKnowledge.SEARCHED_TOP = false;
    }

    RlKnowledgeTest.Fixture fixture(final boolean on) {
        RlKnowledge.SEARCHED_TOP = on;
        final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture(index());
        Assert.assertEquals(f.know.searchedTop(), on);
        for (Player p : Arrays.asList(f.p0, f.p1)) {
            for (String n : LIB) {
                addCardToZone(n, p, ZoneType.Library);
            }
        }
        return f;
    }

    /** Casts {@code name} for {@code p} (Forge AI answers its asks) and resolves it. */
    void cast(final RlKnowledgeTest.Fixture f, final Player p, final String name) {
        final Card c = addCardToZone(name, p, ZoneType.Hand);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        f.game.getStack().add(sa);
        resolveAll(f);
    }

    static void resolveAll(final RlKnowledgeTest.Fixture f) {
        int guard = 0;
        while (!f.game.getStack().isEmpty() && guard++ < 10) {
            f.game.getStack().resolveStack();
        }
    }

    /** (card index, lib pos) tokens of {@code zone} in seat {@code s}'s frame. */
    static List<int[]> z(final RlKnowledgeTest.Fixture f, final Player s, final int zone) {
        return RlKnowledgeTest.zone(f.obs(s), zone);
    }

    static int idx(final String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i + 2;
            }
        }
        return 1;
    }

    static Player other(final RlKnowledgeTest.Fixture f, final Player p) {
        return p == f.p0 ? f.p1 : f.p0;
    }

    /** One tutor on one seat, key on and off: the searcher's zone 17 and the other seat's zone 18. */
    void tutor(final String name, final boolean reveals, final int seat) {
        for (boolean on : new boolean[] {false, true}) {
            final RlKnowledgeTest.Fixture f = fixture(on);
            final Player me = seat == 0 ? f.p0 : f.p1, opp = other(f, me);
            cast(f, me, name);
            final Card top = me.getCardsIn(ZoneType.Library).get(0);
            final List<int[]> mine = z(f, me, RlSchema.Z_U_LIB_KNOWN);
            final List<int[]> theirs = z(f, opp, RlSchema.Z_O_LIB_KNOWN);
            if (!on) {
                Assert.assertEquals(mine.size(), 0, name + " off: Forge's shuffle-then-move forgets the card");
                // a revealing tutor shows the card after it is placed: the other seat knows it with the key off too
                Assert.assertEquals(theirs.size(), reveals ? 1 : 0, name + " off");
                continue;
            }
            // (i) the one token the key adds is the searched card, at the top, for the searcher
            Assert.assertEquals(mine.size(), 1, name + " on: the searcher knows its top");
            Assert.assertEquals(mine.get(0)[0], idx(top.getName()), name + ": the token is the card on top");
            Assert.assertEquals(mine.get(0)[1], 0, name + ": at position 0");
            Assert.assertEquals(theirs.size(), reveals ? 1 : 0, name + ": the other seat knows it only if revealed");
            if (reveals) {
                Assert.assertEquals(theirs.get(0)[0], idx(top.getName()));
            }
            // it follows the card: drawn, it leaves zone 17
            me.drawCard();
            Assert.assertEquals(z(f, me, RlSchema.Z_U_LIB_KNOWN).size(), 0, name + ": drawn");
        }
    }

    @Test
    public void imperialSealBothSeats() {
        tutor("Imperial Seal", false, 0);
        tutor("Imperial Seal", false, 1);
    }

    @Test
    public void vampiricTutorBothSeats() {
        tutor("Vampiric Tutor", false, 0);
        tutor("Vampiric Tutor", false, 1);
    }

    @Test
    public void mysticalTutorBothSeats() {
        tutor("Mystical Tutor", true, 0);
        tutor("Mystical Tutor", true, 1);
    }

    @Test
    public void worldlyTutorBothSeats() {
        tutor("Worldly Tutor", true, 0);
        tutor("Worldly Tutor", true, 1);
    }

    /** The control: Brainstorm's put-backs are known with the key off, and the frames are the same with it on. */
    @Test
    public void brainstormIsUnchangedByTheKey() {
        final List<List<int[]>> got = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            forge.util.MyRandom.setRandom(new Random(7));
            final RlKnowledgeTest.Fixture f = fixture(on);
            cast(f, f.p0, "Brainstorm");
            final List<int[]> k = z(f, f.p0, RlSchema.Z_U_LIB_KNOWN);
            Assert.assertEquals(k.size(), 2, "Brainstorm's two put-backs known (on=" + on + ")");
            Assert.assertEquals(z(f, f.p1, RlSchema.Z_O_LIB_KNOWN).size(), 0);
            got.add(k);
        }
        Assert.assertEquals(got.get(0).size(), got.get(1).size());
        for (int i = 0; i < got.get(0).size(); i++) {
            Assert.assertEquals(got.get(1).get(i), got.get(0).get(i), "on = off for Brainstorm");
        }
    }

    /** (ii) Re-randomising every library card the tracker does not know changes no frame of either seat. */
    @Test
    public void unknownLibraryCardsDoNotShowInFrames() {
        for (String name : new String[] {"Imperial Seal", "Mystical Tutor"}) {
            final RlKnowledgeTest.Fixture f = fixture(true);
            cast(f, f.p0, name);
            final RlFeaturizer.Obs a0 = f.obs(f.p0), a1 = f.obs(f.p1);
            for (Player p : Arrays.asList(f.p0, f.p1)) {
                final List<Card> lib = new ArrayList<>(p.getCardsIn(ZoneType.Library));
                final List<Integer> free = new ArrayList<>();
                final List<Card> rest = new ArrayList<>();
                for (int i = 0; i < lib.size(); i++) {
                    if (!f.know.knows(0, lib.get(i)) && !f.know.knows(1, lib.get(i))) {
                        free.add(i);
                        rest.add(lib.get(i));
                    }
                }
                java.util.Collections.shuffle(rest, new Random(11));
                for (int j = 0; j < free.size(); j++) {
                    lib.set(free.get(j), rest.get(j));
                }
                p.getZone(ZoneType.Library).setCards(lib); // no zone-change events: the tracker sees nothing
            }
            final RlFeaturizer.Obs b0 = f.obs(f.p0), b1 = f.obs(f.p1);
            for (RlFeaturizer.Obs[] ab : new RlFeaturizer.Obs[][] {{a0, b0}, {a1, b1}}) {
                Assert.assertEquals(ab[1].L, ab[0].L, name);
                Assert.assertEquals(ab[1].tokCard, ab[0].tokCard, name + ": tokens");
                Assert.assertEquals(ab[1].tokZone, ab[0].tokZone, name);
                Assert.assertEquals(ab[1].tokAttr, ab[0].tokAttr, name);
            }
        }
    }

    /** (iii) The opponent's unrevealed tutor teaches this seat nothing (and its own seat what it searched). */
    @Test
    public void anOpponentsUnrevealedTutorTeachesNothing() {
        final RlKnowledgeTest.Fixture f = fixture(true);
        cast(f, f.p1, "Imperial Seal");
        Assert.assertEquals(z(f, f.p0, RlSchema.Z_O_LIB_KNOWN).size(), 0, "p0 did not see p1's Seal pick");
        Assert.assertEquals(z(f, f.p0, RlSchema.Z_U_LIB_KNOWN).size(), 0);
        Assert.assertEquals(z(f, f.p1, RlSchema.Z_U_LIB_KNOWN).size(), 1);
    }

    /** (iii) A search whose card is shuffled after it is placed, and a move of a card nobody searched for: nothing. */
    @Test
    public void shuffledAfterAndUnsearchedMovesTeachNothing() {
        final RlKnowledgeTest.Fixture f = fixture(true);
        final Card host = addCardToZone("Lightning Bolt", f.p0, ZoneType.Hand);
        final SpellAbility after = AbilityFactory.getAbility("SP$ ChangeZone | Origin$ Library | Destination$ Library"
                + " | LibraryPosition$ 0 | ChangeType$ Card | ChangeNum$ 1 | Mandatory$ True | Shuffle$ True", host);
        after.setActivatingPlayer(f.p0);
        f.game.getStack().add(after);
        resolveAll(f);
        Assert.assertEquals(z(f, f.p0, RlSchema.Z_U_LIB_KNOWN).size(), 0, "shuffled after the placement");
        final Card host2 = addCardToZone("Counterspell", f.p0, ZoneType.Hand);
        final SpellAbility bottom = AbilityFactory.getAbility("SP$ ChangeZone | Defined$ TopOfLibrary | Origin$ Library"
                + " | Destination$ Library | LibraryPosition$ -1", host2);
        bottom.setActivatingPlayer(f.p0);
        f.game.getStack().add(bottom);
        resolveAll(f);
        Assert.assertEquals(z(f, f.p0, RlSchema.Z_U_LIB_KNOWN).size(), 0, "a Defined (unsearched) card moved");
        Assert.assertEquals(z(f, f.p1, RlSchema.Z_O_LIB_KNOWN).size(), 0);
    }
}
