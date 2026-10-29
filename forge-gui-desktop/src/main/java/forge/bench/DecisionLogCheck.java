package forge.bench;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import forge.bench.rl.TrajectoryRef;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Review #124 S2-3: the checks a RECORD replay (one that re-runs a tape's seats to log them) runs when nothing else
 * does: the trajectory check against step 0's positions ({@link TrajectoryRef}, as the labeller and the exile scan run
 * it), and, where a later step's rows are given, each such row's public-state hash at its own position
 * ({@link TrajectoryRef#stateSha}). Reads only; a game whose log fails either is not accepted
 * ({@link DecisionLog#accepted}). Positions count both seats' priority decisions from the start, as the labeller's
 * {@code dec_idx}.
 */
final class DecisionLogCheck implements PriorityDecisionListener {
    private final Game game;
    private final int tapeTurns;
    private final TrajectoryRef.Recorder trajectory;
    /**
     * dec_idx -> "seat|state_sha" of the later step's row, or null. Review #137 S2-1: keyed by the position alone, so a
     * replay whose seat at that position differs is a mismatch (not an unchecked row), and every expected row must be
     * reached ({@code state_sha_expected}; a game that ends before a row's position fails it).
     */
    private final Map<String, String> stateRows;
    private int decisions = 0, checked = 0, mismatch = 0;
    private String firstMismatch;

    DecisionLogCheck(final Game game, final JsonObject tape, final TrajectoryRef.Recorder trajectory,
            final Map<String, String> stateRows) {
        this.game = game;
        this.tapeTurns = tape.get("turns").getAsInt();
        this.trajectory = trajectory;
        this.stateRows = stateRows;
    }

    @Override
    public void beforeDecision(final Player p) {
        final int ord = decisions++;
        if (game.isGameOver()) {
            return;
        }
        final int seat = game.getRegisteredPlayers().indexOf(p);
        final int turn = game.getPhaseHandler().getTurn();
        if (trajectory != null) {
            trajectory.add(ord, seat, turn, tapeTurns - turn, String.valueOf(game.getPhaseHandler().getPhase()),
                    game.getStack().size());
        }
        if (stateRows != null) {
            final String want = stateRows.get(String.valueOf(ord));
            if (want != null) {
                checked++;
                final String got = seat + "|" + TrajectoryRef.stateSha(game);
                if (!want.equals(got)) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = "seat " + seat + " dec " + ord + ": " + got + " vs " + want;
                    }
                }
            }
        }
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /** The game is over: its verdicts on the replay row ({@code trajectory_equal}, {@code state_sha_*}). */
    void finish(final String uid, final JsonObject row) {
        if (trajectory != null) {
            final JsonObject traj = TrajectoryRef.compareLoudly(trajectory, uid, "decision-log");
            row.add("trajectory", traj);
            row.addProperty("trajectory_equal", traj.get("equal").getAsBoolean());
        }
        if (stateRows != null) {
            row.addProperty("state_sha_expected", stateRows.size());
            row.addProperty("state_sha_checked", checked);
            row.addProperty("state_sha_mismatch", mismatch);
            if (firstMismatch != null) {
                row.addProperty("state_sha_first_mismatch", firstMismatch);
            }
        }
    }
}
