package forge.bench.rl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane bvw-step1-build-1024 (the seat/dec_idx swap, diagnosed 2026-10-25): the step-0 positions of a replayed game, to
 * check that a later replay of the same tape numbers step 0's positions as step 0 did, not only that it is the same game.
 *
 * <p>Why: the step-0 tapes record no Forge-seat decision (their generator seats are re-run on replay), and the replay
 * digest fingerprints the game at each turn start. The step-0 generator ran on wall-clock budgets, so a replay on
 * another host or load can take a line at another priority of the same turn (a combo line adopted in MAIN2 instead of
 * MAIN1) and reach the same state at the next turn start: the digest still matches, but the priority decisions inside
 * that turn are numbered on other seats. The trajectory realigns at the next turn start (game 2988886846: decs 679-688
 * differ, 689 on agree), so rows keyed (game_uid, seat, dec_idx) in that turn do not join step 0's.
 *
 * <p>The check: every priority decision of either seat with turns-before-end in [0, {@link #turns}) (the step-0 label
 * window, numbered as the labeller numbers them: every priority decision from the start counts) has the fingerprint
 * seat | turn | phase | stack size, and a replay's fingerprints must equal the step-0 label rows' exactly. A game that
 * fails writes no rows.
 *
 * <p>What it does NOT check (review-align #3, #8): it aligns step 0's window [0, 3), not the step-1 band [3, 5). Band
 * positions are the replay's own and cannot be checked against step 0 (step 0 has no rows there): a count-preserving
 * reorder inside a band turn passes this check, and the band rows are then honest positions of the same game numbered
 * as this replay numbered them. Inside the window, a reorder that keeps every decision's seat, turn, phase and stack size
 * (two one-item spells cast in swapped order) passes too, and after the last turn starts only the outcome is in the
 * digest. From step 1 on every band row carries {@code state_sha} ({@link #stateSha}), so the duplicate control and step
 * 2's reference can compare content, not only the shape of the sequence.
 */
public final class TrajectoryRef {
    /**
     * The step-0 label window (turns before the end in [0, 3)): the only reference window a step-1 search run on step-0
     * (HonestForcedWin) tapes may use (review-align #2).
     */
    public static final int STEP0_WINDOW = 3;
    /** Games whose reference rows lack {@code stack} (the noStack fallback: seat | turn | phase only), this JVM. */
    public static final AtomicInteger NO_STACK_GAMES = new AtomicInteger();

    public final int turns;
    private final Map<String, TreeMap<Integer, String>> byGame;
    private final List<String> files;
    private final List<String> fileShas;
    private final int rowsWithoutStack;

    private TrajectoryRef(final int turns, final Map<String, TreeMap<Integer, String>> byGame, final List<String> files,
            final List<String> fileShas, final int rowsWithoutStack) {
        this.turns = turns;
        this.byGame = byGame;
        this.files = files;
        this.fileShas = fileShas;
        this.rowsWithoutStack = rowsWithoutStack;
    }

    public static String fp(final int seat, final int turn, final String phase, final int stack) {
        return seat + "|" + turn + "|" + phase + "|" + stack;
    }

    /** The canonical decimal form of a game uid (the form the label index is written in). */
    public static String canonicalUid(final String uid) {
        return RlWire.uidString(RlWire.parseUid(uid));
    }

    /**
     * The reference positions of the games {@code uids} (canonical uid strings; null = every game) from step-0 label
     * index rows (jsonl or jsonl.gz: game_uid, seat, dec_idx, turn, phase, stack, turns_before_end), rows with
     * turns_before_end in [0, turns). Two rows at one (game, dec_idx) are an error. Each file's sha256 is recorded
     * (provenance in every games row, review-align #2).
     */
    public static TrajectoryRef load(final List<String> paths, final int turns, final Set<String> uids) throws IOException {
        if (turns < 1) {
            throw new IllegalArgumentException("referenceTurns >= 1, not " + turns);
        }
        final Map<String, TreeMap<Integer, String>> m = new HashMap<>();
        final List<String> shas = new ArrayList<>();
        int noStack = 0;
        for (String p : paths) {
            shas.add(sha256(p));
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
                        // cheap pre-filter before parsing: the row's game uid string (fail-safe: a row it skips can
                        // only leave its game without a reference, which refuses the run or voids the game)
                        final String u0 = uidOf(line);
                        if (u0 != null && !uids.contains(u0)) {
                            continue;
                        }
                    }
                    final JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    final String uid = canonicalUid(o.get("game_uid").getAsString());
                    if (uids != null && !uids.contains(uid)) {
                        continue;
                    }
                    final int tbe = o.get("turns_before_end").getAsInt();
                    if (tbe < 0 || tbe >= turns) {
                        continue;
                    }
                    final int dec = o.get("dec_idx").getAsInt();
                    final boolean hasStack = o.has("stack") && !o.get("stack").isJsonNull();
                    if (!hasStack) {
                        noStack++;
                    }
                    final String f = fp(o.get("seat").getAsInt(), o.get("turn").getAsInt(), o.get("phase").getAsString(),
                            hasStack ? o.get("stack").getAsInt() : -1);
                    if (m.computeIfAbsent(uid, k -> new TreeMap<>()).put(dec, f) != null) {
                        throw new IllegalArgumentException(p + ": two reference rows at game " + uid + " dec " + dec);
                    }
                }
            }
        }
        return new TrajectoryRef(turns, m, Collections.unmodifiableList(new ArrayList<>(paths)),
                Collections.unmodifiableList(shas), noStack);
    }

    static String sha256(final String path) throws IOException {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(Paths.get(path))) {
                final byte[] b = new byte[1 << 16];
                for (int n = in.read(b); n > 0; n = in.read(b)) {
                    md.update(b, 0, n);
                }
            }
            return hex(md.digest(), 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(final byte[] d, final int bytes) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            sb.append(String.format("%02x", d[i] & 0xff));
        }
        return sb.toString();
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

    /** The game's reference positions (by its canonical uid), or null when the reference has none. */
    public TreeMap<Integer, String> of(final String uid) {
        return byGame.get(canonicalUid(uid));
    }

    public int games() {
        return byGame.size();
    }

    /** True when the reference holds positions of this game (by its canonical uid). */
    public boolean knows(final String uid) {
        return byGame.containsKey(canonicalUid(uid));
    }

    /** The reference rows without {@code stack} (their games fall back to seat | turn | phase). */
    public int rowsWithoutStack() {
        return rowsWithoutStack;
    }

    /**
     * Review-align #2: the reference's identity, written into every games row's {@code trajectory} object and the
     * {@code [bvw-trajectory]} log line: {"reference_files", "reference_sha256" (one per file, in order),
     * "reference_turns", "games_known"}.
     */
    public JsonObject provenance() {
        final JsonObject o = new JsonObject();
        final JsonArray f = new JsonArray(), s = new JsonArray();
        for (String x : files) {
            f.add(x);
        }
        for (String x : fileShas) {
            s.add(x);
        }
        o.add("reference_files", f);
        o.add("reference_sha256", s);
        o.addProperty("reference_turns", turns);
        o.addProperty("games_known", byGame.size());
        return o;
    }

    // ------------------------------------------------------------------------------------------------ position state

    /**
     * The public state of the live game, id-free: RlSimBench.fingerprint's text exactly (turn, phase, the active seat;
     * per player life, hand / library / graveyard / exile sizes and the battlefield's names, tapped and P/T). Reads only.
     */
    public static String publicState(final Game g) {
        final StringBuilder sb = new StringBuilder();
        final PhaseHandler ph = g.getPhaseHandler();
        sb.append('T').append(ph.getTurn()).append(' ').append(ph.getPhase()).append(' ')
                .append(g.getPlayers().indexOf(ph.getPlayerTurn()));
        for (Player p : g.getPlayers()) {
            sb.append(" |L").append(p.getLife()).append(" H").append(p.getZone(ZoneType.Hand).size())
                    .append(" Y").append(p.getZone(ZoneType.Library).size())
                    .append(" G").append(p.getZone(ZoneType.Graveyard).size())
                    .append(" X").append(p.getZone(ZoneType.Exile).size()).append(" B[");
            final List<String> bf = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                bf.add(c.getName() + (c.isTapped() ? "*" : "") + (c.isCreature() ? ":" + c.getNetPower() + "/" + c.getNetToughness() : ""));
            }
            Collections.sort(bf);
            sb.append(String.join(",", bf)).append(']');
        }
        return sb.toString();
    }

    /**
     * Review-align #3: a step-1 row's position hash, the first 16 hex of sha256({@link #publicState} + "\n" + "stack " +
     * stack size + "\n"). Two replays with this hash equal at a (game, dec_idx) were at the same public position there.
     */
    public static String stateSha(final Game g) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(publicState(g).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(("stack " + g.getStack().size()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            return hex(md.digest(), 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ one game

    /** One replayed game's positions in the reference window, compared at its end. */
    public static final class Recorder {
        final TreeMap<Integer, String> ref;
        final int turns;
        final TreeMap<Integer, String> got = new TreeMap<>();
        final JsonObject provenance;

        public Recorder(final TreeMap<Integer, String> ref, final int turns) {
            this(ref, turns, null);
        }

        /** {@code provenance}: the reference's identity ({@link TrajectoryRef#provenance}), copied into compare()'s object. */
        public Recorder(final TreeMap<Integer, String> ref, final int turns, final JsonObject provenance) {
            this.ref = ref;
            this.turns = turns;
            this.provenance = provenance;
        }

        /** A priority decision of the replay (the caller's numbering), recorded when it is in the window. */
        public void add(final int ord, final int seat, final int turn, final int tbe, final String phase, final int stack) {
            if (tbe >= 0 && tbe < turns && seat >= 0 && seat <= 1) {
                got.put(ord, fp(seat, turn, phase, stack));
            }
        }

        /**
         * {"equal", "reference_rows", "replay_rows", "stack_compared", "first_diff" (dec_idx), "reference_at",
         * "replay_at", "why"} plus the reference's provenance. {@code stack_compared} false: some reference row of this
         * game has no stack, so the game was compared on seat | turn | phase only (the noStack fallback; the caller
         * logs and counts it, {@link TrajectoryRef#compareLoudly}).
         */
        public JsonObject compare() {
            final JsonObject o = new JsonObject();
            if (provenance != null) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : provenance.entrySet()) {
                    o.add(e.getKey(), e.getValue());
                }
            }
            o.addProperty("reference_rows", ref == null ? 0 : ref.size());
            o.addProperty("replay_rows", got.size());
            if (ref == null) {
                o.addProperty("equal", false);
                o.addProperty("why", "no_reference");
                return o;
            }
            final boolean noStack = ref.values().stream().anyMatch(v -> v.endsWith("|-1"));
            o.addProperty("stack_compared", !noStack);
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

    /**
     * A game's comparison with the noStack fallback made loud (review-align #9): a game compared without stack sizes is
     * counted ({@link #NO_STACK_GAMES}) and logged with its uid.
     */
    public static JsonObject compareLoudly(final Recorder r, final String uid, final String who) {
        final JsonObject t = r.compare();
        if (t.has("stack_compared") && !t.get("stack_compared").getAsBoolean()) {
            NO_STACK_GAMES.incrementAndGet();
            System.err.println("[bvw-trajectory] " + who + " game " + uid + ": its reference rows have no stack: compared "
                    + "on seat|turn|phase only (noStack fallback)");
        }
        return t;
    }
}
