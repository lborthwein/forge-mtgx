package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlLiveSeat;
import forge.game.Game;

/**
 * J1 (lane block-search-1019; review-replay-1019's jar spec): {@code -Dforge.interactive.aiReplay} replays the AI seat's
 * live choices. A game is recorded with one searched decision artificially changed (a test hook standing in for a wall
 * cut that changed a live choice). Its replay from the recorded marks ({@code decision} = the decision LINE index,
 * {@code played} = the label played) with no change reproduces every decision line and the game, with exactly that
 * line marked {@code "forced":true}. The game has decisions the search counts but the seat does not line (uncontested,
 * on the stack), so the explain rows' {@code decision} and {@code line} differ: the pairing is by {@code line}.
 *
 * <p>Plays real games: run inside a broker test lease with {@code -Dlookahead.explain=true}, cwd = forge-gui (res/).
 */
public class RlAiReplayTest {
    static final long SEED = 724411009L;

    @BeforeClass
    public static void setUp() throws Exception {
        RlLiveSeatTest.setUp();
    }

    @AfterMethod(alwaysRun = true)
    public void clean() {
        System.clearProperty("forge.interactive.aiReplay");
        forge.ai.simulation.LookaheadTestHooks.setOverride(null);
        forge.ai.simulation.LookaheadTestHooks.setBump(null);
    }

    /** One game's record: its decision lines, its explain rows (policy seat), its digest. */
    static final class Run {
        final List<JsonObject> lines = new ArrayList<>();
        final List<JsonObject> explains = new ArrayList<>();
        String digest;
        String text;
    }

    /** The first line where two game logs differ (diagnostics). */
    static String firstDiff(final String a, final String b) {
        final String[] x = a.split("\n"), y = b.split("\n");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) {
                return "line " + i + ": '" + x[i] + "' vs '" + y[i] + "'";
            }
        }
        return "lengths " + x.length + " vs " + y.length;
    }

    private static Run play(final String extraSpec) throws Exception {
        final Run run = new Run();
        try (FakeSearchService svc = new FakeSearchService(RlLiveSeatTest.SHA)) {
            // leaf values that differ by observation, so the search departs (deterministic: a hash of the payload)
            svc.leafValue = p -> (java.util.Arrays.hashCode(p) % 2000) / 2000.0;
            final String err = RlLiveSeatTest.captureErr(() -> {
                final RlLiveSeat live = RlLiveSeat.connect(RlLiveSeatTest.spec(svc.address(),
                        "budgetMs=0,worlds=2,threads=0" + extraSpec), "ai-replay-test");
                Assert.assertNotNull(live);
                final Game g = RlLiveSeatTest.play(live, SEED);
                final StringBuilder sb = new StringBuilder();
                for (forge.game.GameLogEntry e : g.getGameLog().getAllEntries()) {
                    if (!e.message().startsWith("Match Duration")) { // wall clock, not the game
                        sb.append(e.message()).append('\n');
                    }
                }
                sb.append(g.getOutcome() == null ? "?" : String.valueOf(g.getOutcome().getWinningLobbyPlayer()));
                run.text = sb.toString();
                final MessageDigest md = MessageDigest.getInstance("SHA-256");
                run.digest = java.util.HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
            });
            for (String l : err.split("\n")) {
                if (l.startsWith("[lookahead-decision] ") && l.contains("\"seat\":\"policy\"")) {
                    run.lines.add(JsonParser.parseString(l.substring("[lookahead-decision] ".length())).getAsJsonObject());
                } else if (l.startsWith("[lookahead-explain] ") && l.contains("\"given\":true")) {
                    run.explains.add(JsonParser.parseString(l.substring("[lookahead-explain] ".length())).getAsJsonObject());
                }
            }
        }
        return run;
    }

    /** A decision line without its timing (and replay) fields: what must match between two runs. */
    private static List<String> shape(final List<JsonObject> lines) {
        final List<String> out = new ArrayList<>();
        for (JsonObject l : lines) {
            final JsonObject c = l.deepCopy();
            for (String k : new String[] {"searchMs", "cpuMs", "scoreMs", "proposeMs", "forced", "forcedMiss"}) {
                c.remove(k);
            }
            out.add(c.toString());
        }
        return out;
    }

    private static Map<Integer, String> played(final Run r) {
        final Map<Integer, String> m = new TreeMap<>();
        for (JsonObject x : r.explains) {
            Assert.assertTrue(x.has("line"), "every explain row names its line: " + x);
            m.put(x.get("line").getAsInt(), x.get("played").getAsString());
        }
        return m;
    }

    @Test(timeOut = 3_600_000)
    public void aChangedDecisionIsReplayedAndEveryLaterLineMatches() throws Exception {
        Assert.assertTrue(Boolean.getBoolean("lookahead.explain"), "run with -Dlookahead.explain=true");
        // in every run: the search's own counter runs ahead of the line index from line 1 on (as uncontested, skipped or
        // failed searches make it live), so explain rows' decision and line differ
        forge.ai.simulation.LookaheadTestHooks.setBump(line -> line == 1);
        final Run base = play("");
        final Run again = play("");
        Assert.assertEquals(again.digest, base.digest, "precondition: the game is deterministic: " + firstDiff(base.text, again.text));
        Assert.assertEquals(shape(again.lines), shape(base.lines), "precondition: the lines are deterministic");
        Assert.assertTrue(base.explains.size() >= 4, "enough searched decisions: " + base.explains.size());
        int offsets = 0;
        for (JsonObject x : base.explains) {
            offsets += x.get("decision").getAsInt() != x.get("line").getAsInt() ? 1 : 0;
        }
        Assert.assertTrue(offsets > 0, "the search's counter and the line index differ (the pairing must be by line)");

        // the "cut": at the 3rd searched decision, another candidate than the search's
        final JsonObject at = base.explains.get(2);
        final int k = at.get("line").getAsInt();
        final String natural = at.get("played").getAsString();
        int other = -1;
        final com.google.gson.JsonArray cs = at.getAsJsonArray("candidates");
        for (int c = 0; c < cs.size() && other < 0; c++) {
            final JsonObject co = cs.get(c).getAsJsonObject();
            if (co.get("ok").getAsBoolean() && !co.get("label").getAsString().equals(natural)) {
                other = c;
            }
        }
        Assert.assertTrue(other >= 0, "a second playable candidate at line " + k);
        final int pick = other;
        forge.ai.simulation.LookaheadTestHooks.setOverride(line -> line == k ? pick : -1);
        final Run cut = play("");
        forge.ai.simulation.LookaheadTestHooks.setOverride(null);
        Assert.assertNotEquals(played(cut).get(k), natural, "the cut changed the choice at line " + k);

        // the journal's ai marks of the cut game: line index, label played, outcome
        final Path marks = Files.createTempFile("ai-marks-", ".jsonl");
        final StringBuilder sb = new StringBuilder();
        final Map<Integer, String> pl = played(cut);
        for (JsonObject l : cut.lines) {
            final JsonObject m = new JsonObject();
            m.addProperty("t", "ai");
            m.addProperty("decision", l.get("decision").getAsInt());
            m.addProperty("outcome", l.get("outcome").getAsString());
            final String p = pl.get(l.get("decision").getAsInt());
            if (p != null) {
                m.addProperty("played", p);
            }
            sb.append(m).append('\n');
        }
        Files.write(marks, sb.toString().getBytes(StandardCharsets.UTF_8));

        System.setProperty("forge.interactive.aiReplay", marks.toString());
        final Run replay = play("");
        System.clearProperty("forge.interactive.aiReplay");

        Assert.assertEquals(replay.digest, cut.digest, "the replay is the cut game");
        Assert.assertEquals(shape(replay.lines), shape(cut.lines), "every decision line matches the cut game's");
        Assert.assertEquals(played(replay), played(cut), "every played label matches");
        int forced = 0, missed = 0;
        for (JsonObject l : replay.lines) {
            if (l.has("forced")) {
                forced++;
                Assert.assertEquals(l.get("decision").getAsInt(), k, "only the cut line is forced: " + l);
            }
            missed += l.has("forcedMiss") ? 1 : 0;
        }
        Assert.assertEquals(forced, 1, "the cut line is forced");
        Assert.assertEquals(missed, 0);
        Assert.assertNotEquals(base.digest, cut.digest, "the cut changed the game (the test has teeth)");
        // no property: no line carries a replay field
        for (JsonObject l : base.lines) {
            Assert.assertFalse(l.has("forced") || l.has("forcedMiss"), l.toString());
        }
    }
}
