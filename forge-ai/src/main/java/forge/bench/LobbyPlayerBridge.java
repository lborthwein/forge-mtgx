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

import java.util.Set;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * A lobby seat wired to {@link PlayerControllerBridge} instead of
 * {@link forge.ai.PlayerControllerAi}.
 *
 * <p>Deliberately extends {@code LobbyPlayerAi} rather than {@code LobbyPlayer}:
 * {@link forge.ai.AiProfileUtil#getAIProp} returns the empty string (i.e. every AI
 * property falls back to its hard default) for any lobby player that is not a
 * {@code LobbyPlayerAi}. A plain {@code LobbyPlayer} subclass would therefore give the
 * bridged seat a silently different Forge AI than the unbridged one, which would break
 * the null-mode equivalence the protocol depends on.
 */
public class LobbyPlayerBridge extends LobbyPlayerAi {

    private final BenchSession session;
    private final BenchSession.Mode mode;
    private final int seat;
    private final AIOption option;
    private final CallCounter counters = new CallCounter();

    public LobbyPlayerBridge(final String name, final Set<AIOption> options, final BenchSession session,
            final BenchSession.Mode mode, final int seat) {
        super(name, options);
        this.session = session;
        this.mode = mode;
        this.seat = seat;
        // Bench-only widening of the prospective-face guard, armed ONLY for a
        // live bridge session. A permission grant -- MayPlay or MayLookAt --
        // touches no characteristic layer, so the prospective face is the same
        // either way; the whitelist, shared with the guard rather than copied,
        // is what keeps any other verb out. Never armed for NULL/NULL_PROBE, so
        // a --null do-no-harm arm is byte-identical to stock and CANNOT police
        // this: its acceptance is the bridge-arm enumeration differential.
        if (mode == BenchSession.Mode.BRIDGE) {
            forge.game.GameActionUtil.setBenchProspectivePolicy(st ->
                    (st.hasParam("MayPlay") || st.hasParam("MayLookAt"))
                    && forge.game.GameActionUtil.PROSPECTIVE_PERMISSION_PARAMS
                            .containsAll(st.getMapParams().keySet()));
        }
        this.option = (options == null || options.isEmpty()) ? null : options.iterator().next();
    }

    /** Persistent across games in this JVM; the harness resets it per game. */
    public CallCounter getCounters() {
        return counters;
    }

    public int getSeat() {
        return seat;
    }

    public BenchSession.Mode getMode() {
        return mode;
    }

    private PlayerControllerBridge createControllerFor(final Player p) {
        final PlayerControllerBridge result =
                mode == BenchSession.Mode.NULL_PROBE
                ? new PlayerControllerBridge(p.getGame(), p, this, session, mode, seat, counters) {
                    @Override
                    public java.util.List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
                        if (session.getLiveGame() == null || session.getLiveGame() == getGame()) {
                            probePriorityMenuPurity();
                            counters.instrument("auditPriorityProbe");
                        }
                        return super.chooseSpellAbilityToPlay();
                    }
                }
                : new PlayerControllerBridge(p.getGame(), p, this, session, mode, seat, counters);
        result.getAi().setUseSimulation(option);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(final Player master, final Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(final Game game, final int id) {
        final Player p = new Player(getName(), game, id);
        p.setFirstController(createControllerFor(p));
        return p;
    }
}
