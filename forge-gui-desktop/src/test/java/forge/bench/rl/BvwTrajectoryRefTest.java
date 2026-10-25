package forge.bench.rl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/**
 * Lane bvw-step1-build-1024 (review-align #5d, #9): {@link TrajectoryRef} without a game: the comparison of one game's
 * positions (an adjacent seat swap as in game 2988886846's decs 679/680, turn / phase / stack mismatches, dropped and
 * extra rows, an empty replay, no reference, the noStack fallback), the recorder's window edges, and the loader's window
 * filter, canonical uids, duplicate refusal and provenance (file sha256s).
 */
public class BvwTrajectoryRefTest {

    static TreeMap<Integer, String> ref(final Object[]... rows) {
        final TreeMap<Integer, String> m = new TreeMap<>();
        for (Object[] r : rows) {
            m.put((Integer) r[0], TrajectoryRef.fp((Integer) r[1], (Integer) r[2], (String) r[3], (Integer) r[4]));
        }
        return m;
    }

    /** Step 0's positions of a turn: seat 0 at 679, seat 1 at 680, then 681 / 682 (turn 29, MAIN1 then MAIN2). */
    static TreeMap<Integer, String> step0() {
        return ref(new Object[] {679, 0, 29, "MAIN1", 0}, new Object[] {680, 1, 29, "MAIN1", 1},
                new Object[] {681, 0, 29, "MAIN2", 0}, new Object[] {682, 1, 29, "END_OF_TURN", 0});
    }

    static TrajectoryRef.Recorder replay(final TreeMap<Integer, String> ref, final Object[]... decs) {
        final TrajectoryRef.Recorder r = new TrajectoryRef.Recorder(ref, 3);
        for (Object[] d : decs) {
            r.add((Integer) d[0], (Integer) d[1], (Integer) d[2], 1, (String) d[3], (Integer) d[4]);
        }
        return r;
    }

    static Object[][] same() {
        return new Object[][] {{679, 0, 29, "MAIN1", 0}, {680, 1, 29, "MAIN1", 1}, {681, 0, 29, "MAIN2", 0},
                {682, 1, 29, "END_OF_TURN", 0}};
    }

    @Test
    public void theSamePositionsAreEqual() {
        final JsonObject o = replay(step0(), same()).compare();
        Assert.assertTrue(o.get("equal").getAsBoolean(), o.toString());
        Assert.assertTrue(o.get("stack_compared").getAsBoolean());
        Assert.assertEquals(o.get("reference_rows").getAsInt(), 4);
        Assert.assertEquals(o.get("replay_rows").getAsInt(), 4);
        Assert.assertFalse(o.has("first_diff"));
    }

    @Test
    public void anAdjacentSeatSwapIsCaughtAtItsFirstPosition() {
        // the diagnosed shape: the replay numbers the turn's decisions on the other seats (679 / 680 swapped)
        final Object[][] d = same();
        d[0] = new Object[] {679, 1, 29, "MAIN1", 1};
        d[1] = new Object[] {680, 0, 29, "MAIN1", 0};
        final JsonObject o = replay(step0(), d).compare();
        Assert.assertFalse(o.get("equal").getAsBoolean());
        Assert.assertEquals(o.get("first_diff").getAsInt(), 679);
        Assert.assertEquals(o.get("reference_at").getAsString(), "0|29|MAIN1|0");
        Assert.assertEquals(o.get("replay_at").getAsString(), "1|29|MAIN1|1");
        Assert.assertEquals(o.get("why").getAsString(), "positions differ");
    }

    @Test
    public void turnPhaseAndStackMismatchesAreCaught() {
        for (int field = 0; field < 3; field++) {
            final Object[][] d = same();
            final Object[] x = d[2].clone();
            if (field == 0) {
                x[2] = 30;               // turn
            } else if (field == 1) {
                x[3] = "MAIN1";          // phase
            } else {
                x[4] = 1;                // stack size
            }
            d[2] = x;
            final JsonObject o = replay(step0(), d).compare();
            Assert.assertFalse(o.get("equal").getAsBoolean(), "field " + field);
            Assert.assertEquals(o.get("first_diff").getAsInt(), 681, "field " + field);
        }
    }

    @Test
    public void aDroppedOrAnExtraRowIsCaught() {
        // a reference row the replay never reached (a dropped replay position): replay_at none
        final Object[][] d = same();
        final JsonObject a = replay(step0(), d[0], d[1], d[3]).compare();
        Assert.assertFalse(a.get("equal").getAsBoolean());
        Assert.assertEquals(a.get("first_diff").getAsInt(), 681);
        Assert.assertEquals(a.get("replay_at").getAsString(), "none");
        // a replay position the reference does not have (an extra one): reference_at none
        final JsonObject b = replay(step0(), d[0], d[1], d[2], d[3], new Object[] {683, 0, 29, "CLEANUP", 0}).compare();
        Assert.assertFalse(b.get("equal").getAsBoolean());
        Assert.assertEquals(b.get("first_diff").getAsInt(), 683);
        Assert.assertEquals(b.get("reference_at").getAsString(), "none");
        // a dropped REFERENCE row: the replay's position at that dec has nothing to match
        final TreeMap<Integer, String> r = step0();
        r.remove(680);
        final JsonObject c = replay(r, d).compare();
        Assert.assertFalse(c.get("equal").getAsBoolean());
        Assert.assertEquals(c.get("first_diff").getAsInt(), 680);
    }

    @Test
    public void anEmptyReplayAndNoReferenceAreVoid() {
        final JsonObject e = replay(step0()).compare();
        Assert.assertFalse(e.get("equal").getAsBoolean());
        Assert.assertEquals(e.get("first_diff").getAsInt(), 679);
        Assert.assertEquals(e.get("replay_rows").getAsInt(), 0);
        final JsonObject n = replay(null, same()).compare();
        Assert.assertFalse(n.get("equal").getAsBoolean());
        Assert.assertEquals(n.get("why").getAsString(), "no_reference");
        Assert.assertEquals(n.get("reference_rows").getAsInt(), 0);
    }

    @Test
    public void theNoStackFallbackComparesSeatTurnPhaseAndSaysSo() {
        // reference rows without stack (the h6 scan rows): stack is not compared, and the object says so
        final TreeMap<Integer, String> r = ref(new Object[] {679, 0, 29, "MAIN1", -1}, new Object[] {680, 1, 29, "MAIN1", -1});
        final Object[][] d = {{679, 0, 29, "MAIN1", 2}, {680, 1, 29, "MAIN1", 0}};
        final JsonObject o = replay(r, d).compare();
        Assert.assertTrue(o.get("equal").getAsBoolean(), o.toString());
        Assert.assertFalse(o.get("stack_compared").getAsBoolean());
        // a seat swap is still caught
        final Object[][] s = {{679, 1, 29, "MAIN1", 0}, {680, 0, 29, "MAIN1", 0}};
        Assert.assertFalse(replay(r, s).compare().get("equal").getAsBoolean());
        // loud: counted and logged
        final int before = TrajectoryRef.NO_STACK_GAMES.get();
        TrajectoryRef.compareLoudly(replay(r, d), "1", "test");
        Assert.assertEquals(TrajectoryRef.NO_STACK_GAMES.get(), before + 1);
        TrajectoryRef.compareLoudly(replay(step0(), same()), "2", "test");
        Assert.assertEquals(TrajectoryRef.NO_STACK_GAMES.get(), before + 1);
    }

    @Test
    public void theRecorderKeepsOnlyTheWindowAndBothSeats() {
        final TrajectoryRef.Recorder r = new TrajectoryRef.Recorder(new TreeMap<>(), 3);
        r.add(1, 0, 10, -1, "MAIN1", 0);   // past the tape's end
        r.add(2, 0, 10, 3, "MAIN1", 0);    // the window's outer edge (tbe = turns) is outside
        r.add(3, 2, 10, 0, "MAIN1", 0);    // not a seat
        r.add(4, -1, 10, 0, "MAIN1", 0);
        Assert.assertTrue(r.compare().get("equal").getAsBoolean(), "nothing recorded");
        r.add(5, 1, 10, 2, "MAIN1", 0);    // the last turn inside
        r.add(6, 0, 12, 0, "MAIN1", 0);
        final JsonObject o = r.compare();
        Assert.assertEquals(o.get("replay_rows").getAsInt(), 2);
        Assert.assertEquals(o.get("first_diff").getAsInt(), 5);
    }

    static String row(final String uid, final int seat, final int dec, final int turn, final String phase,
            final Integer stack, final int tbe) {
        return "{\"dec_idx\":" + dec + ",\"game_uid\":\"" + uid + "\",\"phase\":\"" + phase + "\",\"seat\":" + seat
                + (stack == null ? "" : ",\"stack\":" + stack) + ",\"turn\":" + turn + ",\"turns_before_end\":" + tbe + "}";
    }

    @Test
    public void theLoaderFiltersTheWindowCanonicalisesUidsAndRecordsItsFiles() throws Exception {
        final Path d = Files.createTempDirectory("bvw-trajref-");
        final Path a = d.resolve("a.jsonl");
        Files.write(a, List.of(row("2988886846", 0, 679, 29, "MAIN1", 0, 1), row("2988886846", 1, 680, 29, "MAIN1", 1, 1),
                row("2988886846", 0, 700, 31, "MAIN1", 0, 3), // outside a 3-turn window
                row("7", 0, 5, 2, "UPKEEP", null, 0)), StandardCharsets.UTF_8);
        final Path b = d.resolve("b.jsonl.gz");
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(b))) {
            gz.write((row("12", 1, 9, 4, "MAIN2", 0, 2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        final TrajectoryRef t = TrajectoryRef.load(List.of(a.toString(), b.toString()), 3, null);
        Assert.assertEquals(t.games(), 3);
        Assert.assertEquals(t.of("2988886846").keySet(), Set.of(679, 680));
        Assert.assertEquals(t.of("2988886846").get(680), "1|29|MAIN1|1");
        // the lookup is by the canonical uid (review-align #9): a non-canonical tape uid finds its game
        Assert.assertNotNull(t.of("007"));
        Assert.assertTrue(t.knows("012"));
        Assert.assertNull(t.of("8"));
        Assert.assertEquals(t.rowsWithoutStack(), 1);
        Assert.assertEquals(t.of("7").get(5), "0|2|UPKEEP|-1");
        // provenance: each file's sha256, the window, the games it knows
        final JsonObject p = t.provenance();
        Assert.assertEquals(p.get("reference_turns").getAsInt(), 3);
        Assert.assertEquals(p.get("games_known").getAsInt(), 3);
        Assert.assertEquals(p.getAsJsonArray("reference_sha256").size(), 2);
        Assert.assertEquals(p.getAsJsonArray("reference_sha256").get(0).getAsString(), sha256(a));
        Assert.assertEquals(p.getAsJsonArray("reference_sha256").get(1).getAsString(), sha256(b));
        Assert.assertEquals(p.getAsJsonArray("reference_files").get(1).getAsString(), b.toString());
        // the compare object carries it
        final JsonObject c = new TrajectoryRef.Recorder(t.of("12"), 3, p).compare();
        Assert.assertEquals(c.getAsJsonArray("reference_sha256"), p.getAsJsonArray("reference_sha256"));
        Assert.assertEquals(c.get("reference_turns").getAsInt(), 3);
        // a narrower window keeps fewer rows; a uid filter keeps only its games
        Assert.assertEquals(TrajectoryRef.load(List.of(a.toString()), 2, null).of("2988886846").keySet(), Set.of(679, 680));
        Assert.assertNull(TrajectoryRef.load(List.of(a.toString()), 1, null).of("2988886846"));
        Assert.assertEquals(TrajectoryRef.load(List.of(a.toString()), 1, null).games(), 1);
        Assert.assertEquals(TrajectoryRef.load(List.of(a.toString(), b.toString()), 3, Set.of("12")).games(), 1);
        // two rows at one (game, dec_idx) abort the load
        final Path dup = d.resolve("dup.jsonl");
        Files.write(dup, List.of(row("5", 0, 3, 1, "MAIN1", 0, 0), row("5", 1, 3, 1, "MAIN1", 0, 0)), StandardCharsets.UTF_8);
        Assert.assertThrows(IllegalArgumentException.class, () -> TrajectoryRef.load(List.of(dup.toString()), 3, null));
        Assert.assertThrows(IllegalArgumentException.class, () -> TrajectoryRef.load(List.of(a.toString()), 0, null));
    }

    public static String sha256(final Path p) throws Exception {
        final byte[] h = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
        final StringBuilder sb = new StringBuilder();
        for (byte x : h) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }
}
