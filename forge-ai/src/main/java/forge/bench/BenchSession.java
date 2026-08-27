/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.bench;

/**
 * Process-wide handle shared by every bridged seat: the stdio channel plus the id of
 * the game currently being played (games run strictly sequentially in one JVM).
 */
public final class BenchSession {
    /** Seat behaviour selected from the harness config. */
    public enum Mode {
        /**
         * Delegate everything to {@link forge.ai.PlayerControllerAi}, but count every
         * controller entry point. This is the decision-surface measurement and the
         * protocol null: it must reproduce pure-Forge results.
         */
        NULL,
        /** Answer the strategic decision set over the wire; delegate the rest. */
        BRIDGE;

        public static Mode parse(final String s) {
            if (s == null) {
                return NULL;
            }
            switch (s.trim().toLowerCase()) {
                case "bridge":
                    return BRIDGE;
                case "null":
                case "forge":
                default:
                    return NULL;
            }
        }
    }

    private final JsonRpcChannel channel;
    private String gameId = "g0";
    private forge.game.Game liveGame;

    /*
     * =======================================================================
     * THE FRAME-BATCH STOP — v2.21, AND WHY IT LIVES ON THE SESSION
     * =======================================================================
     * A native-teacher corpus asks Forge ONE question per installed position:
     * "at this frame, which of these do you take?". Everything the game does
     * after that answer is Forge playing on from a board our engine handed it,
     * which is the BRIDGED distribution again -- the very distribution
     * `docs/ml/priority-clone.md` measured and refused. It is not a teacher row
     * and it is not free: `docs/ml/native-oracle.md` §5 priced the game itself
     * at 1,557 ms against a 4.8 ms install, so playing on is ~99% of the
     * marginal cost of a frame and 0% of its yield.
     *
     * So the batch says, once, "stop after the first delegated echo of kind K",
     * and the controller that publishes such an echo ends the game. It is on
     * the SESSION rather than on the controller because the two bridged seats
     * of one game share the decision -- the first echo on either seat ends the
     * game -- and because BenchMain must be able to read, per game, whether the
     * stop fired. A game ended this way is NOT a game: `outcome.aborted` says
     * `frameEchoStop` so nothing downstream counts a draw that never happened.
     */
    private String stopAfterEchoKind = null;
    private boolean echoStopFired = false;

    public BenchSession(final JsonRpcChannel channel) {
        this.channel = channel;
    }

    /**
     * Arm the frame-batch stop for every game in this JVM. {@code null} disarms it,
     * which is the pre-v2.21 behaviour to the byte.
     */
    public void setStopAfterEchoKind(final String kind) {
        this.stopAfterEchoKind = (kind == null || kind.isEmpty()) ? null : kind;
    }

    /** Reset per game. The flag is a fact about ONE game, the arming is not. */
    public void resetEchoStop() {
        this.echoStopFired = false;
    }

    /** Did the stop fire in the game just played? */
    public boolean echoStopFired() {
        return echoStopFired;
    }

    /**
     * Called by a bridged controller straight after it publishes a {@code delegated}
     * echo. Ends the live game when the kind is the armed one and it has not already
     * fired.
     *
     * <p>The mechanism is {@code Game.setGameOver}, not an exception, and the choice
     * is deliberate. {@code PhaseHandler.mainGameLoop} tests {@code isGameOver()} at
     * the top of every step and {@code mainLoopStep}'s own priority loop tests it
     * through {@code checkStateBasedEffects}, so the loop unwinds through its normal
     * exit. A throw from inside {@code chooseSpellAbilityToPlay} would unwind through
     * Forge's game thread instead, and {@code BenchMain} would have to tell an
     * intentional stop apart from a real crash by matching on a message -- which is
     * exactly the {@code InstrumentError}-as-draw confusion v2.11 was written to end.
     *
     * <p>Forge may still finish the decision it was making (it has already chosen; the
     * echo is published before {@code super}'s side effects settle in some handlers),
     * so up to one more spell can be played after the stop. That costs milliseconds
     * and cannot corrupt the row: the row is the echo, which is already on the wire.
     *
     * @return true when this call ended the game
     */
    public boolean noteEcho(final String kind) {
        if (stopAfterEchoKind == null || echoStopFired || !stopAfterEchoKind.equals(kind)) {
            return false;
        }
        final forge.game.Game g = liveGame;
        if (g == null || g.isGameOver()) {
            return false;
        }
        echoStopFired = true;
        g.setGameOver(forge.game.GameEndReason.Draw);
        return true;
    }

    public JsonRpcChannel getChannel() {
        return channel;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(final String gameId) {
        this.gameId = gameId;
    }

    /**
     * The one real game currently being played.
     *
     * <p>Load-bearing when a seat runs Forge's simulation AI. {@code GameCopier.clonePlayer}
     * reuses the existing {@code LobbyPlayer} whenever it is a {@code LobbyPlayerAi} — and
     * {@link LobbyPlayerBridge} is one — so every copied game inside the simulation search
     * builds a real {@link PlayerControllerBridge}. Without this identity check a simulated
     * game would send {@code ask} messages to the host about a hypothetical position it has
     * no way to distinguish from the real one, and would inflate the decision-surface
     * counters with search internals.
     */
    public forge.game.Game getLiveGame() {
        return liveGame;
    }

    public void setLiveGame(final forge.game.Game liveGame) {
        this.liveGame = liveGame;
    }
}
