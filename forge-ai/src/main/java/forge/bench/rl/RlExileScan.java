package forge.bench.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.GameCopier;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

/**
 * Lane bvw-step1-build-1024 (OPEN-6 ruling, 2026-10-24): the read-only post-hoc scan of STEP-0 tapes for the known
 * honesty limit of the step-0 labeller (HonestLabel re-draws hidden hand and library cards only, so a face-down exiled
 * card keeps its real identity in its worlds). It replays a tape like the labeller, at the same positions with the
 * same numbering (every priority decision of either seat counts; the window is turns before the end in [0, T)), and
 * at each window position reads a copy of the live game from the deciding seat's view ({@link HiddenExile#scan}, as
 * the labeller's solver sees it). It labels nothing and changes nothing: the step-0 labels are joined to its rows by
 * (game_uid, seat, dec_idx) in mtgx (tools/ml/rl/bvw_exile_scan.py). A copy is made only where the live game holds a
 * face-down exiled card at all (read only).
 *
 * <p>Outputs ({@link Sink}, one of each per JVM): {@code scan-<pid>.jsonl} (one row per window position of a game whose
 * replay digest equals the tape's) and {@code games-<pid>.jsonl} (one row per game).
 *
 * <p>With a step-0 reference ({@code exileScan.reference}, {@link TrajectoryRef}; review-align #7): rows are written only
 * when the digest is the tape's AND the window positions are the reference's; the games row keeps {@code equal} = the
 * digest (as without a reference) and adds {@code trajectory_equal} and its {@code trajectory} object; scan rows carry
 * {@code stack} only then (without a reference the rows are byte for byte the plain scan's).
 */
public final class RlExileScan implements PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-bvw-exile-scan/1";

    /** Shared by the replay threads of one JVM. */
    public static final class Sink {
        private final Path scan, games;

        public Sink(final Path dir) throws IOException {
            Files.createDirectories(dir);
            final long pid = ProcessHandle.current().pid();
            this.scan = dir.resolve("scan-" + pid + ".jsonl");
            this.games = dir.resolve("games-" + pid + ".jsonl");
        }

        synchronized void write(final List<JsonObject> rows, final JsonObject game) throws IOException {
            if (!rows.isEmpty()) {
                final StringBuilder sb = new StringBuilder();
                for (JsonObject r : rows) {
                    sb.append(RlWire.canonicalString(r)).append('\n');
                }
                Files.write(scan, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
            Files.write(games, (RlWire.canonicalString(game) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        public Path scanFile() {
            return scan;
        }
    }

    private final int turnsWindow;
    private final long uid;
    private final int tapeTurns;
    private final Game game;
    private final List<JsonObject> rows = new ArrayList<>();
    private int decisions = 0, flagged = 0, copies = 0;
    /** The seat/dec_idx swap fix (2026-10-25): the step-0 reference positions (null = no check). */
    private TrajectoryRef.Recorder trajectory;

    public void setReference(final java.util.TreeMap<Integer, String> ref, final int turns) {
        setReference(ref, turns, null);
    }

    public void setReference(final java.util.TreeMap<Integer, String> ref, final int turns, final JsonObject provenance) {
        this.trajectory = new TrajectoryRef.Recorder(ref, turns, provenance);
    }

    public RlExileScan(final int turnsWindow, final JsonObject tapeLine, final Game game) {
        if (turnsWindow < 1) {
            throw new IllegalArgumentException("exileScan.turns >= 1, not " + turnsWindow);
        }
        this.turnsWindow = turnsWindow;
        this.uid = RlWire.parseUid(tapeLine.get("game_uid").getAsString());
        this.tapeTurns = tapeLine.get("turns").getAsInt();
        this.game = game;
    }

    @Override
    public void beforeDecision(final Player p) {
        final Game g = game;
        final int ord = decisions++;
        if (g == null || g.isGameOver()) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final int tbe = tapeTurns - turn;
        if (trajectory != null) {
            trajectory.add(ord, seat, turn, tbe, String.valueOf(g.getPhaseHandler().getPhase()), g.getStack().size());
        }
        if (tbe < 0 || tbe >= turnsWindow || seat < 0 || seat > 1) {
            return;
        }
        final JsonObject r = new JsonObject();
        r.addProperty("schema", SCHEMA);
        r.addProperty("game_uid", Long.toUnsignedString(uid));
        r.addProperty("seat", seat);
        r.addProperty("dec_idx", ord);
        r.addProperty("turn", turn);
        r.addProperty("turns_before_end", tbe);
        r.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        if (trajectory != null) {
            r.addProperty("stack", g.getStack().size()); // review-align #7: only with a reference
        }
        final boolean any = HiddenExile.anyFaceDownExile(g);
        r.addProperty("face_down_exile", any);
        JsonArray hidden = new JsonArray();
        if (any) {
            copies++;
            hidden = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
                final Game c;
                final Player cp;
                synchronized (g) {
                    final GameCopier copier = new GameCopier(g, true);
                    copier.setCopyFidelity(1);
                    c = copier.makeCopy();
                    cp = (Player) copier.find(p);
                }
                return HiddenExile.scan(c, cp);
            }));
        }
        r.add("hidden_exile", hidden);
        if (hidden.size() > 0) {
            flagged++;
        }
        rows.add(r);
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /**
     * The game is over: its rows only when the replay's digest is the tape's (and, with a reference, its window
     * positions are the reference's); the games row says which: {@code equal} is the digest only, as the labeller's.
     */
    public int finish(final Sink sink, final boolean digestEqual, final JsonObject replayRow) throws IOException {
        final JsonObject traj = trajectory == null ? null
                : TrajectoryRef.compareLoudly(trajectory, Long.toUnsignedString(uid), "exile-scan");
        final boolean trajEqual = traj == null || traj.get("equal").getAsBoolean();
        final boolean written = digestEqual && trajEqual;
        if (digestEqual && !trajEqual) {
            System.err.println("[bvw-exile-scan] TRAJECTORY MISMATCH game " + Long.toUnsignedString(uid) + ": " + traj
                    + " -- its " + rows.size() + " scan rows are VOID (not written)");
        }
        final JsonObject gr = new JsonObject();
        if (traj != null) {
            gr.add("trajectory", traj);
            gr.addProperty("trajectory_equal", trajEqual);
        }
        gr.addProperty("schema", SCHEMA);
        gr.addProperty("game_uid", Long.toUnsignedString(uid));
        gr.addProperty("equal", digestEqual);
        gr.addProperty("rows", written ? rows.size() : 0);
        gr.addProperty("rows_void", written ? 0 : rows.size());
        gr.addProperty("flagged", written ? flagged : 0);
        gr.addProperty("copies", copies);
        gr.addProperty("decisions", decisions);
        gr.addProperty("turns_window", turnsWindow);
        for (String k : new String[] {"digest_tape", "digest_replay"}) {
            if (replayRow.has(k)) {
                gr.add(k, replayRow.get(k));
            }
        }
        sink.write(written ? rows : List.of(), gr);
        if (traj != null) {
            replayRow.addProperty("trajectory_equal", trajEqual); // review-align #7
        }
        replayRow.addProperty("exile_scan_rows", written ? rows.size() : 0);
        replayRow.addProperty("exile_scan_flagged", written ? flagged : 0);
        return written ? rows.size() : 0;
    }
}
