package forge.interactive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.simulation.PlayoutKeys;
import forge.game.GlobalIds;
import forge.game.spellability.SpellAbility;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * The AI seat's decision tape (lane replay-record-1021; {@link forge.ai.AiTape}), on whole watch-table games (both seats
 * AI, both taped): a game recorded with {@code -Dforge.interactive.aiTape=record} and replayed with {@code forced:<file>}
 * is the same game byte for byte, also when the replay's AI is a different one (another profile, a cheaper look-ahead);
 * a record that does not fit the game is a divergence, never re-decided. Plays real Forge games: run inside a broker test
 * lease, cwd = forge-gui with res/.
 */
public class InteractiveAiTapeTest extends AITest {
    private static final String CHEAP_LOOKAHEAD = "worlds=2,breadth=2,horizon=1,threads=1,budgetMs=0,aiFixes0928=on";
    private static final String CHEAPER_LOOKAHEAD = "worlds=1,breadth=1,horizon=1,threads=1,budgetMs=0,aiFixes0928=on";

    private static String config(final long seed, final String profile, final String ai) {
        final Path decks = forge.bench.rl.RlWireSchemaTest.res().resolve("decks").toAbsolutePath().normalize();
        return "{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\",\"session\":\"tape-test\","
                + "\"humanSeat\":0,\"seed\":" + seed + ",\"aiProfile\":\"" + profile + "\","
                + "\"decks\":[\"" + decks.resolve("extC64b-pod01-p0.dck") + "\",\"" + decks.resolve("extC64b-pod01-p1.dck") + "\"],"
                + "\"startingChooser\":0,\"gameNumber\":1,\"names\":[\"Bot 3\",\"Bot 5\"],"
                + "\"watchSeats\":[0,1],\"seatAi\":[\"" + ai + "\",\"" + ai + "\"]}";
    }

    /** One watch game: its protocol stream and its stderr's tape lines. */
    private static final class Run {
        final boolean ok;
        final String stream;
        final List<String> tape = new ArrayList<>();
        final List<JsonObject> status = new ArrayList<>();

        Run(final boolean ok, final String stream, final String err) {
            this.ok = ok;
            this.stream = stream;
            for (String line : err.split("\n")) {
                if (!line.startsWith(forge.ai.AiTape.PREFIX)) {
                    continue;
                }
                final JsonObject o = JsonParser.parseString(line.substring(forge.ai.AiTape.PREFIX.length())).getAsJsonObject();
                final String k = o.get("k").getAsString();
                if (k.equals("boot") || k.equals("end") || k.equals("divergence") || k.equals("live")) {
                    status.add(o);
                } else {
                    tape.add(o.toString());
                }
            }
        }

        List<JsonObject> status(final String k) {
            final List<JsonObject> out = new ArrayList<>();
            for (JsonObject o : status) {
                if (o.get("k").getAsString().equals(k)) {
                    out.add(o);
                }
            }
            return out;
        }
    }

    private static int[] ids;

    private static Run watch(final String cfg, final String tapeProperty) throws Exception {
        if (ids == null) {
            ids = GlobalIds.capture();
        }
        // every game starts with the process-wide ids where the first found them, as a fresh watch JVM's would
        forceIds(ids);
        final InteractiveProtocol.Config config = InteractiveProtocol.readConfig(cfg);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream was = System.err;
        final String before = System.getProperty("forge.interactive.aiTape");
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(final int c) {
                err.write(c);
            }

            @Override
            public void write(final byte[] b, final int off, final int len) {
                err.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8));
        if (tapeProperty == null) {
            System.clearProperty("forge.interactive.aiTape");
        } else {
            System.setProperty("forge.interactive.aiTape", tapeProperty);
        }
        try {
            final boolean ok = InteractiveWatch.run(config, new PrintStream(out, true, StandardCharsets.UTF_8), false);
            return new Run(ok, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(was);
            if (before == null) {
                System.clearProperty("forge.interactive.aiTape");
            } else {
                System.setProperty("forge.interactive.aiTape", before);
            }
        }
    }

    /** Set the process-wide counters exactly (a test's fresh-JVM start; production code only ever moves them forward). */
    private static void forceIds(final int[] to) {
        forge.game.Game.dangerouslySetMaxId(to[0]);
        SpellAbility.dangerouslySetMaxId(to[1]);
        forge.game.spellability.SpellAbilityStackInstance.dangerouslySetMaxId(to[2]);
        forge.game.trigger.Trigger.dangerouslySetMaxId(to[3]);
        forge.game.replacement.ReplacementEffect.dangerouslySetMaxId(to[4]);
        forge.game.staticability.StaticAbility.dangerouslySetMaxId(to[5]);
        forge.game.combat.CombatView.dangerouslySetNextId(to[6]);
    }

    private static String tapeFile(final List<String> lines) throws Exception {
        final Path f = Files.createTempFile("ai-tape-", ".jsonl");
        Files.write(f, lines, StandardCharsets.UTF_8);
        return "forced:" + f;
    }

    private static void withProperty(final String key, final String value, final Body body) throws Exception {
        final String was = System.getProperty(key);
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
        try {
            body.run();
        } finally {
            if (was == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, was);
            }
        }
    }

    interface Body {
        void run() throws Exception;
    }

    /**
     * The same stream, line for line; a failure names the first line that differs. Forge AI's deliberation touches its
     * mana pool (a test payment applies and restores colour conversions), which fires a mana-pool event with no content
     * ({@code {"class":"GameEventManaPool"}}); a forced replay does not deliberate, so it has none of those. Every other
     * line, every state frame included, is compared.
     */
    private static void assertSameStream(final String got, final String want, final String what) {
        final String[] g = withoutPoolFlicker(got);
        final String[] w = withoutPoolFlicker(want);
        for (int i = 0; i < Math.min(g.length, w.length); i++) {
            if (!g[i].equals(w[i])) {
                throw new AssertionError(what + ": line " + i + " of " + w.length + " differs:\n got  " + g[i] + "\n want " + w[i]);
            }
        }
        assertEquals(g.length, w.length, what + ": the same number of lines");
    }

    private static String[] withoutPoolFlicker(final String stream) {
        final List<String> out = new ArrayList<>();
        for (String line : stream.split("\n")) {
            if (!line.contains("\"event\":{\"class\":\"GameEventManaPool\"}")) {
                out.add(line.replaceFirst("\"seq\":\\d+,", ""));
            }
        }
        return out.toArray(new String[0]);
    }

    private static void assertForcedClean(final Run r, final int entries) {
        for (JsonObject o : r.status) {
            System.out.println("[tape-status] " + o);
        }
        assertTrue(r.ok, "the forced replay completes: " + r.status);
        assertTrue(r.status("divergence").isEmpty(), "no divergence: " + r.status("divergence"));
        final List<JsonObject> ends = r.status("end");
        assertEquals(ends.size(), 2, "one end line per seat");
        int used = 0;
        for (JsonObject e : ends) {
            assertEquals(e.get("mode").getAsString(), "forced");
            assertEquals(e.get("used").getAsInt(), e.get("entries").getAsInt(), "every recorded decision was used: " + e);
            used += e.get("used").getAsInt();
        }
        assertEquals(used, entries);
    }

    /** Both seats' forced replay was exact: nothing drifted, nothing ran ahead of the record (but combat-view ids). */
    private static boolean exact(final Run r) {
        boolean all = true;
        for (JsonObject e : r.status("end")) {
            all &= e.has("exact") && e.get("exact").getAsBoolean();
        }
        return all;
    }

    // ------------------------------------------------------------------------------------------------ games

    @Test
    public void aTapedClassicGameReplaysForcedByteForByteAndRecordingDoesNotChangeIt() throws Exception {
        withProperty("forge.interactive.aiTimeoutSec", "600", () -> {
            final String cfg = config(722161001L, "Default", "classic");
            final Run plain = watch(cfg, null);
            final Run rec = watch(cfg, "record");
            assertTrue(rec.ok && plain.ok);
            assertEquals(rec.stream, plain.stream, "recording the tape does not change the game (byte for byte)");
            assertFalse(rec.tape.isEmpty());
            assertTrue(rec.tape.stream().anyMatch(l -> l.contains("\"s\":0")) && rec.tape.stream().anyMatch(l -> l.contains("\"s\":1")),
                    "both seats are taped, each line naming its seat");
            final Run forced = watch(cfg, tapeFile(rec.tape));
            assertForcedClean(forced, rec.tape.size());
            assertSameStream(forced.stream, rec.stream, "the forced replay is the game");
            assertTrue(exact(forced), "on the same AI the forced replay is exact: " + forced.status("end"));
            System.err.println("[tape-test] classic: " + rec.tape.size() + " entries, "
                    + rec.tape.stream().mapToInt(String::length).sum() + " bytes");
        });
    }

    @Test
    public void aForcedReplayIsTheGameWhateverTheAiWouldDecideNow() throws Exception {
        withProperty("forge.interactive.aiTimeoutSec", "600", () -> {
            final Run rec = watch(config(722161002L, "Default", "classic"), "record");
            assertTrue(rec.ok);
            // the control: another AI (Forge AI plus the look-ahead, with the AI fixes on), deciding, plays another game
            withProperty("forge.interactive.lookahead", CHEAP_LOOKAHEAD, () -> {
                final Run other = watch(config(722161002L, "Default", "lookahead"), null);
                assertNotEquals(other.stream, rec.stream, "the other AI decides otherwise");
                final Run forced = watch(config(722161002L, "Default", "lookahead"), tapeFile(rec.tape));
                assertForcedClean(forced, rec.tape.size());
                assertSameStream(forced.stream, rec.stream, "forced, it is the recorded game");
                // the game is the record's; its exactness is reported, never assumed (a card's lazily built abilities
                // take later ids where the record's AI built them while deliberating: the replay says inexact)
                System.out.println("[tape-test] other-AI forced exact=" + exact(forced));
            });
        });
    }

    @Test
    public void aTapedLookaheadGameReplaysForcedOnACheaperSearch() throws Exception {
        withProperty("forge.interactive.aiTimeoutSec", "600", () -> {
            final List<String> tape = new ArrayList<>();
            final String[] stream = new String[1];
            withProperty("forge.interactive.lookahead", CHEAP_LOOKAHEAD, () -> {
                final Run rec = watch(config(722161003L, "Default", "lookahead"), "record");
                assertTrue(rec.ok);
                tape.addAll(rec.tape);
                stream[0] = rec.stream;
            });
            withProperty("forge.interactive.lookahead", CHEAPER_LOOKAHEAD, () -> {
                final Run forced = watch(config(722161003L, "Default", "lookahead"), tapeFile(tape));
                assertForcedClean(forced, tape.size());
                assertSameStream(forced.stream, stream[0], "forced on another search, it is the recorded game");
                System.out.println("[tape-test] cheaper-search forced exact=" + exact(forced));
            });
        });
    }

    @Test
    public void aRecordThatDoesNotFitTheGameIsADivergenceNeverReDecided() throws Exception {
        withProperty("forge.interactive.aiTimeoutSec", "600", () -> {
            final String cfg = config(722161004L, "Default", "classic");
            final Run rec = watch(cfg, "record");
            assertTrue(rec.ok);
            // seat 1's first decision taken out: its next one is asked where the record has another
            final List<String> cut = new ArrayList<>(rec.tape);
            for (int i = 0; i < cut.size(); i++) {
                if (cut.get(i).contains("\"s\":1")) {
                    cut.remove(i);
                    break;
                }
            }
            final Run forced = watch(cfg, tapeFile(cut));
            assertFalse(forced.ok, "a diverged replay does not complete");
            final List<JsonObject> div = forced.status("divergence");
            assertEquals(div.size(), 1, "reported once, at that decision: " + forced.status);
            assertEquals(div.get(0).get("s").getAsInt(), 1);
            assertFalse(forced.stream.contains("\"type\":\"terminal\""), "the game stops there");
        });
    }

    // ------------------------------------------------------------------------------------------------ upstream drift

    /**
     * AiTapeController's forced execution bodies copy PlayerControllerAi's (review forge-mtgx#110 S3 #7). These are the
     * originals' text as the copies were made from them: when an upstream sync changes one, this fails, and the copy in
     * AiTapeController must be brought in line before the pin here is moved.
     */
    @Test
    public void theForcedBodiesMirrorTheirOriginals() throws Exception {
        final String src = Files.readString(Path.of("../forge-ai/src/main/java/forge/ai/PlayerControllerAi.java"), StandardCharsets.UTF_8);
        final String[][] pins = {
                {"public boolean playChosenSpellAbility(SpellAbility sa) {", "6801c55b72b6ae33"},
                {"public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {", "3f6ba6033e2beea2"},
                {"public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {", "2e9a06ae128aec2c"},
                {"private boolean prepareSingleSaUntaped(final Card host, SpellAbility sa, boolean isMandatory) {", "85e757ad9ace5d23"},
                {"public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {", "b54fb880090478a0"},
                {"public boolean playSaFromPlayEffect(SpellAbility tgtSA) {", "25ae78233882cdf1"},
                {"public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {", "2cd61af1a041ca66"},
                {"public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt /* ai needs hints as well */, ManaConversionMatrix matrix, boolean effect) {", "03d7956e2f68a1d2"},
                {"public boolean payCombatCost(Card c, Cost cost, SpellAbility sa, String prompt) {", "f2ed41ad7866bd19"},
        };
        for (String[] pin : pins) {
            final int i = src.indexOf(pin[0]);
            assertTrue(i >= 0, "PlayerControllerAi no longer has: " + pin[0]);
            int k = src.indexOf('{', i);
            int depth = 0;
            for (; ; k++) {
                final char ch = src.charAt(k);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}' && --depth == 0) {
                    break;
                }
            }
            final byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(src.substring(i, k + 1).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int b = 0; b < 8; b++) {
                hex.append(String.format("%02x", d[b]));
            }
            assertEquals(hex.toString(), pin[1], "PlayerControllerAi changed: bring AiTapeController's forced copy of " + pin[0]
                    + " in line, then re-pin");
        }
    }

    // ------------------------------------------------------------------------------------------------ the sync state

    @Test
    public void theTrackedStreamIsTheSeedsStreamAndRestoresInPlace() {
        final Random plain = new Random(42L);
        final PlayoutKeys.TrackedRandom tracked = new PlayoutKeys.TrackedRandom(42L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(tracked.nextInt(97), plain.nextInt(97));
            assertEquals(tracked.nextGaussian(), plain.nextGaussian());
        }
        final long[] snap = tracked.snapshot();
        final long next = tracked.nextLong();
        tracked.nextDouble();
        tracked.restore(snap);
        assertEquals(tracked.nextLong(), next, "restored, the stream continues where the snapshot was taken");
    }

    @Test
    public void theGlobalIdsOnlyEverMoveForward() {
        final int[] now = GlobalIds.capture();
        final int[] later = now.clone();
        later[1] += 5;
        later[6] -= 3; // the combat-view counter counts down
        assertEquals(GlobalIds.restoreForward(later), 0);
        assertEquals(GlobalIds.capture()[1], now[1] + 5);
        assertEquals(GlobalIds.capture()[6], now[6] - 3);
        // asked to go back: nothing moves, and the counters already past are counted
        assertEquals(GlobalIds.restoreForward(now), 2);
        assertEquals(GlobalIds.capture()[1], now[1] + 5);
    }
}
