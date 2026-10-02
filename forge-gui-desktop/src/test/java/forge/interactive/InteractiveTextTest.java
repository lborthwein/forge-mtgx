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

    @Test
    public void noSubjectNamesIsTheOldBehaviour() {
        for (String text : List.of(PROMPT, "Secret Card (31) and Lightning Bolt", "Lightning Bolt (30)")) {
            assertEquals(InteractiveText.sanitize(text, BOLTS, Set.of()), InteractiveText.sanitize(text, BOLTS));
            assertEquals(InteractiveText.sanitize(text, BOLTS, null), InteractiveText.sanitize(text, BOLTS));
        }
    }
}
