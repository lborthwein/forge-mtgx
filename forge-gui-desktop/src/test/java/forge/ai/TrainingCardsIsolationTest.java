package forge.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.card.CardEdition;
import forge.game.GameFormat;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;

/**
 * Lane training-cards-fork-1028: the training cards (XCLT) never reach a cube, a draft, a format or the AI's pools.
 * <ul>
 *   <li>no other edition prints them;</li>
 *   <li>no res file other than their own seven scripts and the XCLT edition names one: Forge's cubes and drafts
 *       ({@code res/cube}, {@code res/draft}, including {@code rankings_mtgx.txt}), formats, puzzles, quest and
 *       generated decks, and every other card script;</li>
 *   <li>no format allows XCLT or adds a training card;</li>
 *   <li>the jar's AI pools name none: the live belief cube ({@code BeliefSampler.LIVE_CUBE}) and the co-draft defend
 *       prior ({@code PlanSearch.DEFEND_PRIOR}).</li>
 * </ul>
 */
public class TrainingCardsIsolationTest extends AITest {

    private static final Set<String> OWN_FILES = Set.of("cardsfolder/r/rampart_sentry.txt",
            "cardsfolder/t/temple_spearman.txt", "cardsfolder/w/weary_foes_end.txt", "cardsfolder/n/noonday_glare.txt",
            "cardsfolder/s/steppe_gazelle.txt", "cardsfolder/s/steppe_bull.txt", "cardsfolder/w/wildborn_surge.txt",
            "editions/Cube of Legends Training.txt");
    private static final Set<String> BINARY = Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "mp3", "wav",
            "ogg", "ttf", "otf", "zip", "jar", "pdf");

    /** The training names {@code text} mentions, case-insensitively. */
    private static List<String> named(final String text) {
        final String lo = text.toLowerCase(Locale.ROOT);
        final List<String> hits = new ArrayList<>();
        for (String n : TrainingCardsTest.NAMES) {
            if (lo.contains(n.toLowerCase(Locale.ROOT))) {
                hits.add(n);
            }
        }
        return hits;
    }

    @Test
    public void noOtherEditionPrintsATrainingCard() {
        int editions = 0;
        for (CardEdition ed : FModel.getMagicDb().getEditions()) {
            editions++;
            if (ed.getCode().equals(TrainingCardsTest.EDITION)) {
                continue;
            }
            for (CardEdition.EditionEntry e : ed.getAllCardsInSet()) {
                Assert.assertTrue(named(e.name()).isEmpty(), ed.getCode() + " prints " + e.name());
            }
        }
        Assert.assertTrue(editions > 600, "the editions are loaded (" + editions + ")");
    }

    @Test
    public void noResFileButTheirOwnNamesATrainingCard() throws IOException {
        final Path res = Paths.get(ForgeConstants.RES_DIR).toAbsolutePath().normalize();
        final List<String> leaks = new ArrayList<>();
        final int[] scanned = {0, 0};
        try (Stream<Path> walk = Files.walk(res)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                final String rel = res.relativize(p).toString().replace('\\', '/');
                final String file = p.getFileName().toString();
                final int dot = file.lastIndexOf('.');
                if (OWN_FILES.contains(rel) || dot > 0 && BINARY.contains(file.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                    return;
                }
                try {
                    final String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                    scanned[0]++;
                    if (rel.startsWith("cube/") || rel.startsWith("draft/") || rel.startsWith("formats/")) {
                        scanned[1]++;
                    }
                    for (String n : named(text)) {
                        leaks.add(rel + ": " + n);
                    }
                } catch (IOException e) {
                    leaks.add(rel + ": unreadable " + e);
                }
            });
        }
        for (String own : OWN_FILES) {
            Assert.assertTrue(Files.isRegularFile(res.resolve(own)), own + " exists");
        }
        Assert.assertTrue(scanned[0] > 30000, "the card scripts were scanned (" + scanned[0] + " files)");
        Assert.assertTrue(scanned[1] > 100, "the cube, draft and format files were scanned (" + scanned[1] + ")");
        Assert.assertTrue(Files.isRegularFile(res.resolve("draft/rankings_mtgx.txt")), "mtgx's draft rankings were scanned");
        Assert.assertEquals(leaks, List.of(), "res files naming a training card");
    }

    @Test
    public void noFormatAllowsTheTrainingEdition() {
        int formats = 0;
        for (GameFormat f : FModel.getFormats()) {
            formats++;
            Assert.assertFalse(f.getAllowedSetCodes().contains(TrainingCardsTest.EDITION), f.getName() + " lists XCLT");
            for (String n : f.getAdditionalCards()) {
                Assert.assertTrue(named(n).isEmpty(), f.getName() + " adds " + n);
            }
            if (!f.getAllowedSetCodes().isEmpty()) {
                Assert.assertFalse(f.isSetLegal(TrainingCardsTest.EDITION), f.getName() + " allows XCLT");
            }
        }
        Assert.assertTrue(formats > 10, "the formats are loaded (" + formats + ")");
    }

    @Test
    public void theJarsAiPoolsNameNoTrainingCard() throws IOException {
        for (String pool : new String[] {forge.ai.simulation.BeliefSampler.LIVE_CUBE, forge.bench.rl.PlanSearch.DEFEND_PRIOR}) {
            Assert.assertTrue(pool.startsWith("classpath:"), pool);
            final String r = pool.substring("classpath:".length());
            try (InputStream in = TrainingCardsIsolationTest.class.getResourceAsStream(r)) {
                Assert.assertNotNull(in, r + " is on the classpath");
                final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Assert.assertTrue(text.length() > 1000, r + " is read");
                Assert.assertEquals(named(text), List.of(), r + " names a training card");
            }
        }
    }
}
