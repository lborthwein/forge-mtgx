package forge.interactive;

import forge.ai.AITest;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Lane gamestate-setup-1024, run by hand (not a surefire class): install every .pzl in {@code -Dmtgx.puzzles=<dir>}
 * (mtgx's tools/forge/puzzles) exactly as {@link InteractivePuzzleSetupTest} installs its two, and report each one
 * the install changed or that asked the browser seat anything.
 */
public class InteractivePuzzleSetupSweep extends AITest {

    @Test(timeOut = 1800000)
    public void everyShippedPuzzleInstallsAsWritten() throws Exception {
        final String dir = System.getProperty("mtgx.puzzles");
        assertNotNull(dir, "-Dmtgx.puzzles=<mtgx tools/forge/puzzles>");
        final List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(dir))) {
            files = s.filter(p -> p.toString().endsWith(".pzl")).sorted().toList();
        }
        assertTrue(!files.isEmpty(), "no .pzl in " + dir);
        final List<String> problems = new ArrayList<>();
        for (Path file : files) {
            final String text = Files.readString(file, StandardCharsets.UTF_8);
            final String state = text.substring(text.indexOf("[state]") + "[state]".length()).strip();
            String goal = null;
            Integer turns = null;
            for (String line : text.substring(0, text.indexOf("[state]")).split("\\R")) {
                if (line.startsWith("Goal:")) {
                    goal = line.substring(5).trim().toLowerCase(Locale.ROOT);
                } else if (line.startsWith("Turns:")) {
                    turns = Integer.valueOf(line.substring(6).trim());
                }
            }
            try (InteractivePuzzleSetupTest.Install in = new InteractivePuzzleSetupTest.Install(initAndCreateGame(),
                    state, goal, turns)) {
                final List<String> wrong = InteractivePuzzleSetupTest.check(in, state);
                System.out.println("[setup-sweep] " + file.getFileName() + " " + (wrong.isEmpty() ? "OK" : wrong));
                for (String w : wrong) {
                    problems.add(file.getFileName() + ": " + w);
                }
            }
        }
        System.out.println("[setup-sweep] " + files.size() + " puzzles, " + problems.size() + " problems");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
