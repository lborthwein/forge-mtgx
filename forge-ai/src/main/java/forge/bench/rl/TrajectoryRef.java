package forge.bench.rl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lane bvw-step1-build-1024 (the seat/dec_idx swap, diagnosed 2026-10-25): the step-0 positions of a replayed game, to
 * check that a later replay of the same tape is the same trajectory, not only the same game.
 *
 * <p>Why: the step-0 tapes record no Forge-seat decision (their generator seats are re-run on replay), and the replay
 * digest fingerprints the game at each turn start. The step-0 generator ran on wall-clock budgets, so a replay on
 * another host or load can take the same turns in another order inside a turn (a combo line adopted in MAIN2 instead of
 * MAIN1, the same state at the next turn start): the digest still matches, but the priority decisions are numbered on
 * other seats from there on. Rows keyed (game_uid, seat, dec_idx) then do not join step 0's.
 *
 * <p>The check: every priority decision of either seat with turns-before-end in [0, {@link #turns}) (the step-0 label
 * window, numbered as the labeller numbers them: every priority decision from the start counts) has the fingerprint
 * seat | turn | phase | stack size, and a replay's fingerprints must equal the step-0 label rows' exactly. A game that
 * fails writes no rows.
 */
public final class TrajectoryRef {
    public final int turns;
    private final Map<String, TreeMap<Integer, String>> byGame;

    private TrajectoryRef(final int turns, final Map<String, TreeMap<Integer, String>> byGame) {
        this.turns = turns;
        this.byGame = byGame;
    }

    public static String fp(final int seat, final int turn, final String phase, final int stack) {
        return seat + "|" + turn + "|" + phase + "|" + stack;
    }

    /**
     * The reference positions of the games {@code uids} (null = every game) from step-0 label index rows (jsonl or
     * jsonl.gz: game_uid, seat, dec_idx, turn, phase, stack, turns_before_end), rows with turns_before_end in
     * [0, turns). Two rows at one (game, dec_idx) are an error.
     */
    public static TrajectoryRef load(final List<String> paths, final int turns, final Set<String> uids) throws IOException {
        if (turns < 1) {
            throw new IllegalArgumentException("referenceTurns >= 1, not " + turns);
        }
        final Map<String, TreeMap<Integer, String>> m = new HashMap<>();
        for (String p : paths) {
            InputStream in = Files.newInputStream(Paths.get(p));
            if (p.endsWith(".gz")) {
                in = new GZIPInputStream(in, 1 << 16);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (uids != null) {
                        // cheap pre-filter before parsing: the row's game uid string
                        final String u0 = uidOf(line);
                        if (u0 != null && !uids.contains(u0)) {
                            continue;
                        }
                    }
                    final JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    final String uid = o.get("game_uid").getAsString();
                    if (uids != null && !uids.contains(uid)) {
                        continue;
                    }
                    final int tbe = o.get("turns_before_end").getAsInt();
                    if (tbe < 0 || tbe >= turns) {
                        continue;
                    }
                    final int dec = o.get("dec_idx").getAsInt();
                    final String f = fp(o.get("seat").getAsInt(), o.get("turn").getAsInt(), o.get("phase").getAsString(),
                            o.has("stack") ? o.get("stack").getAsInt() : -1);
                    if (m.computeIfAbsent(uid, k -> new TreeMap<>()).put(dec, f) != null) {
                        throw new IllegalArgumentException(p + ": two reference rows at game " + uid + " dec " + dec);
                    }
                }
            }
        }
        return new TrajectoryRef(turns, m);
    }

    /** The value of a row's "game_uid" string field without parsing the row, or null. */
    static String uidOf(final String line) {
        final int at = line.indexOf("\"game_uid\"");
        if (at < 0) {
            return null;
        }
        int i = line.indexOf(':', at + 10);
        if (i < 0) {
            return null;
        }
        i++;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        if (i >= line.length() || line.charAt(i) != '"') {
            return null;
        }
        final int end = line.indexOf('"', i + 1);
        return end < 0 ? null : line.substring(i + 1, end);
    }

    /** The game's reference positions, or null when the reference has none. */
    public TreeMap<Integer, String> of(final String uid) {
        return byGame.get(uid);
    }

    public int games() {
        return byGame.size();
    }

    /** One replayed game's positions in the reference window, compared at its end. */
    public static final class Recorder {
        final TreeMap<Integer, String> ref;
        final int turns;
        final TreeMap<Integer, String> got = new TreeMap<>();

        public Recorder(final TreeMap<Integer, String> ref, final int turns) {
            this.ref = ref;
            this.turns = turns;
        }

        /** A priority decision of the replay (the caller's numbering), recorded when it is in the window. */
        public void add(final int ord, final int seat, final int turn, final int tbe, final String phase, final int stack) {
            if (tbe >= 0 && tbe < turns && seat >= 0 && seat <= 1) {
                got.put(ord, fp(seat, turn, phase, stack));
            }
        }

        /** {"equal", "reference_rows", "replay_rows", "first_diff" (dec_idx), "why"}. */
        public JsonObject compare() {
            final JsonObject o = new JsonObject();
            o.addProperty("reference_rows", ref == null ? 0 : ref.size());
            o.addProperty("replay_rows", got.size());
            if (ref == null) {
                o.addProperty("equal", false);
                o.addProperty("why", "no_reference");
                return o;
            }
            final boolean noStack = ref.values().stream().anyMatch(v -> v.endsWith("|-1"));
            Integer first = null;
            final java.util.TreeSet<Integer> keys = new java.util.TreeSet<>(ref.keySet());
            keys.addAll(got.keySet());
            for (Integer k : keys) {
                String a = ref.get(k), b = got.get(k);
                if (noStack && a != null && b != null) {
                    a = a.substring(0, a.lastIndexOf('|'));
                    b = b.substring(0, b.lastIndexOf('|'));
                }
                if (a == null || !a.equals(b)) {
                    first = k;
                    break;
                }
            }
            o.addProperty("equal", first == null);
            if (first != null) {
                o.addProperty("first_diff", first);
                o.addProperty("reference_at", ref.getOrDefault(first, "none"));
                o.addProperty("replay_at", got.getOrDefault(first, "none"));
                o.addProperty("why", "positions differ");
            }
            return o;
        }
    }
}
