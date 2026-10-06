package forge.bench.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.player.Player;

/**
 * Test-scope frame listener (lane rl-r0-b4-1006): for every DECIDE frame an RL seat sends, checks that the bridge answer
 * the seat built from the server's steps maps back to the same steps through the record-mode mapper
 * ({@code Menu.fromEcho} / {@code fromEchoTwoFrame}), i.e. that a Forge answer of that shape would be recorded as that
 * decision. A mismatch here is a record-mode map failure waiting to happen. Also counts frames by family and mode, and
 * forwards every frame to an optional {@link RlGoldens}.
 */
public final class RlFamilyChecks implements RlSeat.FrameListener {

    public RlGoldens goldens;
    private final Map<String, int[]> byFamily = new TreeMap<>(); // family → {frames, checked, mismatches, skipped}
    private final Map<String, Integer> byMode = new TreeMap<>();
    private final List<String> problems = new ArrayList<>();
    /** Two-frame asks: the second frame's expected steps, by (game uid, dec_idx). */
    private final Map<String, short[]> expectSecond = new HashMap<>();

    private int[] row(final int family) {
        return byFamily.computeIfAbsent(RlSchema.familyName(family), k -> new int[4]);
    }

    private void problem(final String p) {
        if (problems.size() < 30) {
            problems.add(p);
        }
    }

    @Override
    public synchronized void onFrame(final Game game, final Player seat, final RlWire.Decide f,
            final RlCandidates.Menu m, final RlFeaturizer.Obs obs, final short[] steps, final JsonObject answer) {
        if (goldens != null) {
            goldens.onFrame(game, seat, f, m, obs, steps, answer);
        }
        if (f.teacher.length > 0) {
            return; // a RECORD frame: the mapper produced it
        }
        final int[] r = row(f.family);
        r[0]++;
        byMode.merge(RlSchema.familyName(f.family) + "/" + RlSchema.MODES.get(f.mode), 1, Integer::sum);
        if (answer == null || (answer.has("delegate") && answer.get("delegate").getAsBoolean())) {
            r[3]++; // NAME's "Forge's choice": nothing to map back
            return;
        }
        final String at = RlSchema.familyName(f.family) + " " + Long.toUnsignedString(f.gameUid) + "/" + f.decIdx;
        if (RlSchema.isTwoFrame(f.family)) {
            if (f.mode == RlSchema.M_PERMUTE) {
                final short[] want = expectSecond.remove(Long.toUnsignedString(f.gameUid) + "/" + f.decIdx);
                r[1]++;
                if (want == null || !Arrays.equals(want, steps)) {
                    r[2]++;
                    problem(at + " second frame: steps " + Arrays.toString(steps) + " map back to "
                            + Arrays.toString(want));
                }
                return;
            }
            final Object[] two = m.fromEchoTwoFrame(answer);
            r[1]++;
            if (two == null || !Arrays.equals((short[]) two[0], steps)) {
                r[2]++;
                problem(at + ": steps " + Arrays.toString(steps) + " answer " + answer + " map back to "
                        + (two == null ? "null" : Arrays.toString((short[]) two[0])));
                return;
            }
            if (two[2] != null) {
                expectSecond.put(Long.toUnsignedString(f.gameUid) + "/" + (f.decIdx + 1), (short[]) two[2]);
            }
            return;
        }
        final short[] back = m.fromEcho(answer);
        if (f.family == RlSchema.F_PRIORITY && back == null) {
            r[3]++; // a priority answer is an index into Forge's menu; mapped by the echo's own matcher
            return;
        }
        r[1]++;
        final boolean same = back != null && (f.mode == RlSchema.M_SUBSET ? sameSet(back, steps) : Arrays.equals(back, steps));
        if (!same) {
            r[2]++;
            problem(at + ": steps " + Arrays.toString(steps) + " answer " + answer + " map back to "
                    + Arrays.toString(back));
        }
    }

    private static boolean sameSet(final short[] a, final short[] b) {
        final short[] x = a.clone();
        final short[] y = b.clone();
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    public synchronized int mismatches() {
        int n = 0;
        for (int[] r : byFamily.values()) {
            n += r[2];
        }
        return n;
    }

    public synchronized JsonObject toJson() {
        final JsonObject o = new JsonObject();
        final JsonObject fam = new JsonObject();
        for (Map.Entry<String, int[]> e : byFamily.entrySet()) {
            final JsonObject x = new JsonObject();
            x.addProperty("frames", e.getValue()[0]);
            x.addProperty("checked", e.getValue()[1]);
            x.addProperty("mismatch", e.getValue()[2]);
            x.addProperty("skipped", e.getValue()[3]);
            fam.add(e.getKey(), x);
        }
        o.add("by_family", fam);
        final JsonObject md = new JsonObject();
        for (Map.Entry<String, Integer> e : byMode.entrySet()) {
            md.addProperty(e.getKey(), e.getValue());
        }
        o.add("by_mode", md);
        final JsonArray p = new JsonArray();
        for (String s : problems) {
            p.add(s);
        }
        o.add("problems", p);
        o.addProperty("mismatches", mismatches());
        return o;
    }
}
