package forge.cardsfolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

/**
 * order-memory-1102 (card data): a static that lets players look at library cards names WHO may look
 * ({@code MayLookAt$ You} = its controller, {@code MayLookAt$ Player} = every player). "You may look at the top card of
 * your library any time" is private to the controller; "play with the top card of your library revealed" is public.
 * A static scoped to every player whose own text says "you may look" shows a private card to the opponent (an
 * information leak). This scan reads every card script and fails on any such static; it needs no game.
 */
public class MayLookAtScopeTest {

    static Path cardsFolder() {
        for (String p : new String[] {"res/cardsfolder", "../forge-gui/res/cardsfolder", "forge-gui/res/cardsfolder"}) {
            final Path d = Paths.get(p);
            if (Files.isDirectory(d)) {
                return d;
            }
        }
        throw new IllegalStateException("cardsfolder not found from " + Paths.get("").toAbsolutePath());
    }

    /** The offending statics of one script: {@code MayLookAt$ Player} whose Description (or, with none, the Oracle) says "you may look". */
    static List<String> offenders(final List<String> lines) {
        String oracle = "";
        for (String l : lines) {
            if (l.startsWith("Oracle:")) {
                oracle = l.substring("Oracle:".length());
            }
        }
        final List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (!l.startsWith("S:") || !l.matches(".*\\bMayLookAt\\$\\s*Player\\b.*")) {
                continue;
            }
            final int d = l.indexOf("Description$");
            final String text = d >= 0 ? l.substring(d) : oracle;
            if (text.toLowerCase(Locale.ROOT).contains("you may look")) {
                out.add(l);
            }
        }
        return out;
    }

    /** A script's lines; decoded leniently (a few scripts carry bytes that are not UTF-8). */
    static List<String> lines(final Path f) throws IOException {
        return List.of(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\\R"));
    }

    @Test
    public void noPublicScopeOnAPrivateLook() throws IOException {
        final List<String> bad = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(cardsFolder())) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".txt"))::iterator) {
                scanned++;
                for (String l : offenders(lines(f))) {
                    bad.add(f.getFileName() + ": " + l);
                }
            }
        }
        assertTrue(scanned > 1000, "too few scripts scanned: " + scanned);
        assertTrue(bad.isEmpty(), "MayLookAt$ Player on a 'you may look' static (use MayLookAt$ You):\n" + String.join("\n", bad));
    }

    @Test
    public void scanFlagsTheMistakeAndPassesTheFix() {
        final List<String> wrong = List.of(
                "Name:X",
                "S:Mode$ Continuous | Affected$ Card.TopLibrary+YouCtrl | AffectedZone$ Library | MayLookAt$ Player | Description$ You may look at the top card of your library any time.");
        final List<String> right = List.of(
                "Name:X",
                "S:Mode$ Continuous | Affected$ Card.TopLibrary+YouCtrl | AffectedZone$ Library | MayLookAt$ You | Description$ You may look at the top card of your library any time.");
        final List<String> revealed = List.of(
                "Name:X",
                "S:Mode$ Continuous | Affected$ Card.TopLibrary+YouCtrl | AffectedZone$ Library | MayLookAt$ Player | Description$ Play with the top card of your library revealed.");
        assertTrue(offenders(wrong).size() == 1);
        assertTrue(offenders(right).isEmpty());
        assertTrue(offenders(revealed).isEmpty());
    }
}
