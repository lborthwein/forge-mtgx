package forge.ai.lesson;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Lane lessons-forge-jar-1027 (J2): the {@code lesson-script/1} text format, parsed with no Forge model (no card database,
 * no phase table): what each line means, and every malformed line refused with a message that names the line.
 */
public class LessonScriptParseTest {

    @Test
    public void theChapterOneSubsetParses() {
        final LessonScript s = LessonScript.parse(String.join("\n",
                "# Enkidu, stage 1.5",
                "format=lesson-script/1",
                "rest=pass",
                "botfrom=turn/9",
                "plays=Forest;Forest",
                "attack=Steppe Bull",
                "block=Steppe Gazelle>Rampart Sentry;Steppe Gazelle>Temple Spearman",
                "step=play:Forest|when:MAIN1/ai",
                "step=cast:Monstrous Growth|tap:Forest;Forest|target:ai/Steppe Bull|when:MAIN1/ai",
                "step=cast:Lightning Bolt|tap:Mountain|target:human|when:stack-empty",
                "step=attack:Steppe Bull;Steppe Gazelle|when:COMBAT_DECLARE_ATTACKERS/ai",
                "step=block:Steppe Gazelle>Rampart Sentry|when:COMBAT_DECLARE_BLOCKERS/human",
                "step=pass|when:turn/4|once:false",
                "step=pass|when:always"));
        assertEquals(s.rest(), LessonScript.Rest.PASS);
        assertEquals(s.botFrom().at(), "turn");
        assertEquals(s.botFrom().turn(), 9);
        assertEquals(s.plays(), List.of("Forest", "Forest"));
        assertEquals(s.attack(), List.of("Steppe Bull"));
        assertEquals(s.block(), List.of(new LessonScript.Pair("Steppe Gazelle", "Rampart Sentry"),
                new LessonScript.Pair("Steppe Gazelle", "Temple Spearman")));
        assertEquals(s.steps().size(), 7);
        final LessonScript.Step pump = s.steps().get(1);
        assertEquals(pump.kind(), LessonScript.Kind.CAST);
        assertEquals(pump.card(), "Monstrous Growth");
        assertEquals(pump.tap(), List.of("Forest", "Forest"));
        assertEquals(pump.target(), new LessonScript.Target(LessonScript.Side.AI, "Steppe Bull"));
        assertEquals(pump.when().text(), "MAIN1/ai");
        assertTrue(pump.once());
        assertEquals(s.steps().get(2).target(), new LessonScript.Target(LessonScript.Side.HUMAN, null));
        assertEquals(s.steps().get(2).when().at(), "stack-empty");
        assertEquals(s.steps().get(3).names(), List.of("Steppe Bull", "Steppe Gazelle"));
        assertEquals(s.steps().get(4).when().whose(), LessonScript.Side.HUMAN);
        assertEquals(s.steps().get(5).once(), false);
        assertEquals(s.phases(), List.of("MAIN1", "MAIN1", "COMBAT_DECLARE_ATTACKERS", "COMBAT_DECLARE_BLOCKERS"));
        assertEquals(s.names(), List.of("Forest", "Steppe Bull", "Steppe Gazelle", "Rampart Sentry", "Temple Spearman",
                "Monstrous Growth", "Lightning Bolt", "Mountain"));
    }

    @Test
    public void defaultsAreAPassingSeat() {
        for (String text : List.of("", "# nothing\n\n", "rest=pass")) {
            final LessonScript s = LessonScript.parse(text);
            assertEquals(s.rest(), LessonScript.Rest.PASS);
            assertNull(s.botFrom());
            assertNull(s.attack(), "no attack shorthand: an unnamed attack is the rest's");
            assertNull(s.block());
            assertTrue(s.plays().isEmpty());
            assertTrue(s.steps().isEmpty());
        }
        assertEquals(LessonScript.parse("rest=bot").rest(), LessonScript.Rest.BOT);
        assertEquals(LessonScript.parse("attack=").attack(), List.of(), "an empty attack shorthand: never attack");
    }

    @Test
    public void malformedLinesAreRefused() {
        final List<String> bad = List.of(
                "[script]",
                "rest",
                "rest=sometimes",
                "rest=pass\nrest=bot",
                "format=lesson-script/2",
                "colour=green",
                "botfrom=MAIN1",
                "botfrom=MAIN1/bob",
                "botfrom=turn/zero",
                "botfrom=turn/0",
                "botfrom=MAIN-1/ai",
                "step=cast:Grizzly Bears|tap:Forest",
                "step=dance:Grizzly Bears|when:always",
                "step=cast:|when:always",
                "step=cast:Grizzly Bears|when:always|when:always",
                "step=cast:Grizzly Bears|target:nobody/Grizzly Bears|when:always",
                "step=play:Forest|tap:Forest|when:always",
                "step=attack:Grizzly Bears|target:human|when:always",
                "step=attack:|when:always",
                "step=block:Grizzly Bears|when:always",
                "step=pass:now|when:always",
                "step=pass|when:always|once:maybe",
                "step=pass|when:always|wait:3",
                "block=Grizzly Bears",
                "x".repeat(LessonScript.MAX_TEXT + 1));
        for (String text : bad) {
            try {
                LessonScript.parse(text);
                fail("accepted: " + text.substring(0, Math.min(60, text.length())));
            } catch (LessonScript.Malformed e) {
                assertTrue(e.getMessage().length() < 200, e.getMessage());
            }
        }
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i <= LessonScript.MAX_STEPS; i++) {
            many.append("step=pass|when:always\n");
        }
        try {
            LessonScript.parse(many.toString());
            fail("more than " + LessonScript.MAX_STEPS + " steps accepted");
        } catch (LessonScript.Malformed expected) {
            // refused
        }
    }
}
