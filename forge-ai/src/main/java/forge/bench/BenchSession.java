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

    public BenchSession(final JsonRpcChannel channel) {
        this.channel = channel;
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
}
