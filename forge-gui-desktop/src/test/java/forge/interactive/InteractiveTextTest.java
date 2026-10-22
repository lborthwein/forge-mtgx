package forge.interactive;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;

/**
 * {@link InteractiveText#sanitize} with a prompt subject (lane bridge-race-1002, 2026-10-02).
 *
 * <p>The reported text: the seat casts its face-up Lightning Bolt (4) while a second Lightning Bolt
 * sits in its library. Forge's target prompt writes the spell's own name bare (CARDNAME), and the
 * hidden copy made the bridge print "Lightning Bolt (4) - Face-down card deals 3 damage to any
 * target."</p>
 */
public class InteractiveTextTest {

    private static final List<InteractiveText.CardLabel> BOLTS = List.of(
            new InteractiveText.CardLabel(4, "Lightning Bolt", true),
            new InteractiveText.CardLabel(30, "Lightning Bolt", false),
            new InteractiveText.CardLabel(31, "Secret Card", false));

    private static final String PROMPT = "Lightning Bolt (4) - Lightning Bolt deals 3 damage to any target.";

    @Test
    public void withoutASubjectTheHiddenCopyRedactsTheBareName() {
        // The defect, kept as the documented behaviour of the subject-free overload.
        assertEquals(InteractiveText.sanitize(PROMPT, BOLTS),
                "Lightning Bolt (4) - Face-down card deals 3 damage to any target.");
    }

    @Test
    public void theSubjectKeepsItsOwnBareName() {
        assertEquals(InteractiveText.sanitize(PROMPT, BOLTS, Set.of("Lightning Bolt")), PROMPT);
    }

    @Test
    public void otherHiddenNamesAreStillRedacted() {
        assertEquals(InteractiveText.sanitize(PROMPT + " Then Secret Card.", BOLTS, Set.of("Lightning Bolt")),
                PROMPT + " Then Face-down card.");
    }

    @Test
    public void aHiddenReferenceWithTheSubjectsNameIsStillRedacted() {
        assertEquals(InteractiveText.sanitize("Lightning Bolt (4) - exile Lightning Bolt (30)", BOLTS,
                        Set.of("Lightning Bolt")),
                "Lightning Bolt (4) - exile Face-down card (30)");
    }

    @Test
    public void aLongerHiddenNameContainingTheSubjectIsRedactedWhole() {
        final List<InteractiveText.CardLabel> labels = List.of(
                new InteractiveText.CardLabel(5, "Fire", true),
                new InteractiveText.CardLabel(6, "Fire // Ice", false));
        assertEquals(InteractiveText.sanitize("Fire (5) - Fire deals 2 damage; Fire // Ice", labels, Set.of("Fire")),
                "Fire (5) - Fire deals 2 damage; Face-down card");
    }

    @Test
    public void aShorterHiddenNameInsideTheSubjectDoesNotSplitIt() {
        final List<InteractiveText.CardLabel> labels = List.of(
                new InteractiveText.CardLabel(7, "Squee, Goblin Nabob", true),
                new InteractiveText.CardLabel(8, "Squee", false));
        assertEquals(InteractiveText.sanitize("Squee, Goblin Nabob - Creature 1 / 1; Squee", labels,
                        Set.of("Squee, Goblin Nabob")),
                "Squee, Goblin Nabob - Creature 1 / 1; Face-down card");
    }

    // Lane facedown-label-1021 (owner report 2026-10-21T21-17-01): "Cast Lórien Revealed · (Face-down cardcycling {1})".
    private static final List<InteractiveText.CardLabel> HIDDEN_LANDS = List.of(
            new InteractiveText.CardLabel(37, "Island", false),
            new InteractiveText.CardLabel(38, "Swamp", false),
            new InteractiveText.CardLabel(39, "Snow-Covered Island", false),
            new InteractiveText.CardLabel(40, "Lightning Bolt", false),
            new InteractiveText.CardLabel(2, "Lórien Revealed", true));

    private static final String LORIEN_ORACLE = "Draw three cards.\nIslandcycling {1} ({1}, Discard this card: "
            + "Search your library for an Island card, reveal it, put it into your hand, then shuffle.)";

    @Test
    public void aHiddenNameInsideALongerWordIsNotAName() {
        assertEquals(InteractiveText.sanitize("Islandcycling {1}", HIDDEN_LANDS), "Islandcycling {1}");
        assertEquals(InteractiveText.sanitize("Swampwalk; Islands you control", HIDDEN_LANDS),
                "Swampwalk; Islands you control");
    }

    @Test
    public void aHiddenNameStandingAsAWordIsStillRedacted() {
        assertEquals(InteractiveText.sanitize("Exile Island. Island's controller loses 1 life.", HIDDEN_LANDS),
                "Exile Face-down card. Face-down card's controller loses 1 life.");
        assertEquals(InteractiveText.sanitize("(Lightning Bolt)", HIDDEN_LANDS), "(Face-down card)");
        assertEquals(InteractiveText.sanitize("Snow-Covered Island and Island", HIDDEN_LANDS),
                "Face-down card and Face-down card");
    }

    @Test
    public void theSourcesPrintedWordsAreKept() {
        final String cycling = "Islandcycling {1} ({1}, Discard this card: Search your library for an Island card, "
                + "reveal it, put it into your hand, then shuffle.)";
        assertEquals(InteractiveText.sanitize(cycling, HIDDEN_LANDS, Set.of("Lórien Revealed"), LORIEN_ORACLE), cycling);
        // Without the printed text, the bare type word is redacted as a name (a word: no longer "Islandcycling").
        assertEquals(InteractiveText.sanitize(cycling, HIDDEN_LANDS, Set.of("Lórien Revealed")),
                cycling.replace("an Island card", "an Face-down card card"));
    }

    @Test
    public void printedWordsNeverShieldAReferenceALongerNameOrAnotherHiddenName() {
        assertEquals(InteractiveText.sanitize("Island (37), Snow-Covered Island, Lightning Bolt, an Island card",
                        HIDDEN_LANDS, Set.of(), LORIEN_ORACLE),
                "Face-down card (37), Face-down card, Face-down card, an Island card");
    }

    @Test
    public void wordBoundariesAreOnlyRequiredBesideLettersAndDigits() {
        assertEquals(InteractiveText.indexOfWord("an Island card", "Island", 0), 3);
        assertEquals(InteractiveText.indexOfWord("Islandcycling", "Island", 0), -1);
        assertEquals(InteractiveText.indexOfWord("Islandcycling, then Island", "Island", 0), 20);
        assertEquals(InteractiveText.indexOfWord("Lórien Revealed", "Lórien", 0), 0);
        assertEquals(InteractiveText.indexOfWord("Ach! Hans, Run!s", "Ach! Hans, Run!", 0), 0);
        assertEquals(InteractiveText.indexOfWord(null, "Island", 0), -1);
        assertEquals(InteractiveText.replaceWord("Island Islandcycling Island", "Island", "X"), "X Islandcycling X");
    }

    @Test
    public void noSubjectNamesIsTheOldBehaviour() {
        for (String text : List.of(PROMPT, "Secret Card (31) and Lightning Bolt", "Lightning Bolt (30)")) {
            assertEquals(InteractiveText.sanitize(text, BOLTS, Set.of()), InteractiveText.sanitize(text, BOLTS));
            assertEquals(InteractiveText.sanitize(text, BOLTS, null), InteractiveText.sanitize(text, BOLTS));
        }
    }
}
