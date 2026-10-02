package forge.interactive;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Forge's raw target descriptors in plain words (owner report 2026-10-02T16-19-27: casting Reprieve
 * showed "Select target Card.inZoneStack").
 */
public class InteractiveTargetTextTest {

    private static String render(final String text) {
        return InteractiveTargetText.render(text);
    }

    @Test
    public void aSpellOnTheStack() {
        // Reprieve, Aether Gust, Swat Away ... and Counterspell's stack choice.
        assertEquals(render("Select target Card.inZoneStack"), "Select target spell");
        assertEquals(render("Select target Card.inZoneStack+OppCtrl"), "Select target spell an opponent controls");
        assertEquals(render("Select target Card.inZoneStack+Creature"), "Select target creature spell");
        assertEquals(render("Select target Card.inZoneStack+cmcGE1"), "Select target spell with mana value 1 or greater");
        assertEquals(render("Select target creature or Card.inZoneStack"), "Select target creature or spell");
        assertEquals(render("Select target Permanent.nonLand or Card.inZoneStack"),
                "Select target nonland permanent or spell");
    }

    @Test
    public void removal() {
        assertEquals(render("Select target Creature.nonBlack"), "Select target nonblack creature");
        assertEquals(render("Select target Permanent.nonLand+OppCtrl"),
                "Select target nonland permanent an opponent controls");
        assertEquals(render("Select target Creature.YouCtrl"), "Select target creature you control");
        assertEquals(render("Select target Creature.Other+YouCtrl"), "Select target other creature you control");
        assertEquals(render("Select target Creature.powerLE2"), "Select target creature with power 2 or less");
        assertEquals(render("Select target Creature.withFlying"), "Select target creature with flying");
        assertEquals(render("Select target Artifact.cmcEQX"), "Select target artifact with mana value X");
        assertEquals(render("Select target Creature.nonGod"), "Select target non-God creature");
        assertEquals(render("Select target Creature.Cleric"), "Select target Cleric creature");
        assertEquals(render("Select target Card.Creature+YouOwn"), "Select target creature card you own");
        assertEquals(render("Select target Artifact.cmcLTX+YouCtrl"),
                "Select target artifact you control with mana value less than X");
        assertEquals(render("Select target Artifact.!token+YouCtrl+Other"), "Select target other nontoken artifact you control");
        assertEquals(render("Select target Creature.YouOwn+YouCtrl"), "Select target creature you own and control");
    }

    @Test
    public void aPlayer() {
        assertEquals(render("Select target Player.Opponent"), "Select target opponent");
        assertEquals(render("Select target Player.Other"), "Select target other player");
    }

    @Test
    public void aRestrictionWithNoWordsIsNeverShown() {
        assertEquals(render("Select target Creature.IsRemembered"), InteractiveTargetText.FALLBACK);
        assertEquals(render("Select target Creature.ControlledBy TriggeredTarget"), InteractiveTargetText.FALLBACK);
        // Not a target line: the descriptor becomes its noun.
        assertEquals(render("Sacrifice a Creature.IsRemembered."), "Sacrifice a creature.");
        // Only the line that asks for the target falls back.
        assertEquals(render("Reprieve (12) - Return target spell to its owner's hand.\n\nSelect target Card.IsRemembered"),
                "Reprieve (12) - Return target spell to its owner's hand.\n\n" + InteractiveTargetText.FALLBACK);
    }

    @Test
    public void ordinaryTextIsUnchanged() {
        for (String text : List.of(
                "Lightning Bolt (4) - Lightning Bolt deals 3 damage to any target.",
                "Select target creature", "Select any target", "Select target creature you control",
                "B.F.M. (Big Furry Monster) (7)", "Dr. Julius Jumblemorph", "Pay Mana Cost: {2}{R}",
                "Draw a card. Then discard a card.", "Time Walk (57) - Default Forge takes an extra turn after this one.",
                "Priority: Browser Player\nTurn: 4 (Default Forge)\nPhase: Main phase, precombat\nStack: 1 to Resolve.",
                "", "mtg.orth.win")) {
            assertEquals(render(text), text, text);
        }
        assertEquals(render(null), null);
    }

    /**
     * Every card script Forge ships whose target prompt would be built from raw ValidTgts (no
     * TgtPrompt$, no ValidTgtsDesc$): rendered the way Forge builds it ("Select target " + the
     * tokens joined with "or"), the prompt never shows descriptor syntax. Most are said in words;
     * the rest fall back. The share in words is pinned so a regression shows.
     */
    @Test
    public void everyRawPromptInTheCardScriptsIsWordsOrTheFallback() throws IOException {
        final Path cards = Paths.get("..", "forge-gui", "res", "cardsfolder");
        assertTrue(Files.isDirectory(cards), "card scripts at " + cards.toAbsolutePath());
        final Map<String, String> rendered = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(cards)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.contains("ValidTgts$") || line.contains("TgtPrompt$") || line.contains("ValidTgtsDesc$")) {
                        continue;
                    }
                    for (String part : line.split("\\|")) {
                        final String p = part.trim();
                        if (!p.startsWith("ValidTgts$")) {
                            continue;
                        }
                        final String valid = p.substring("ValidTgts$".length()).trim();
                        if (!valid.contains(".") || "Any".equals(valid)) {
                            continue;
                        }
                        // Lang.buildValidDesc lowercases a bare type and leaves the rest; "or" joins.
                        final List<String> words = new ArrayList<>();
                        for (String token : valid.split(",")) {
                            words.add(token.contains(".") ? token
                                    : token.matches("Player|Opponent|Card|Spell|Permanent|Creature|Artifact|Enchantment|Land|Planeswalker|Instant|Sorcery|Battle")
                                    ? token.toLowerCase() : token);
                        }
                        final String prompt = "Select target " + String.join(" or ", words);
                        rendered.put(prompt, render(prompt));
                    }
                }
            }
        }
        final Pattern raw = Pattern.compile("[A-Za-z]\\.[A-Za-z!]|\\+|[a-z][A-Z]");
        int inWords = 0;
        for (Map.Entry<String, String> e : rendered.entrySet()) {
            assertFalse(raw.matcher(e.getValue()).find(), e.getKey() + " -> " + e.getValue());
            if (!InteractiveTargetText.FALLBACK.equals(e.getValue())) {
                inWords++;
            } else {
                System.out.println("[target-text] fallback: " + e.getKey());
            }
        }
        assertTrue(rendered.size() > 100, "scanned " + rendered.size());
        System.out.println("[target-text] " + inWords + "/" + rendered.size() + " raw prompts said in words, the rest fall back");
        assertTrue(inWords * 100 >= rendered.size() * 75, inWords + "/" + rendered.size() + " in words");
    }
}
