package forge.bench.rl;

import forge.bench.BenchSession;
import forge.bench.LobbyPlayerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * Live play (lane live-sc-1009): the AI seat of an interactive game played by {@link RlLiveSeat} (the S1 read's S-c).
 * A bridge seat like the read's RL seat (its asks are answered in-process by the seat), whose controller adds the
 * degraded path ({@link PlayerControllerPolicy}). Game copies replace it with plain Forge AI (GameCopier), as every
 * bridge seat.
 */
public class LobbyPlayerPolicy extends LobbyPlayerBridge {
    private final BenchSession session;
    private final int seat;
    private final RlLiveSeat live;

    public LobbyPlayerPolicy(final String name, final BenchSession session, final int seat, final RlLiveSeat live) {
        super(name, null, session, BenchSession.Mode.BRIDGE, seat);
        this.session = session;
        this.seat = seat;
        this.live = live;
    }

    public RlLiveSeat live() {
        return live;
    }

    private PlayerControllerPolicy controllerFor(final Player p) {
        final PlayerControllerPolicy c = new PlayerControllerPolicy(p.getGame(), p, this, session, seat, getCounters(), live);
        c.getAi().setUseSimulation(null);
        return c;
    }

    @Override
    public PlayerController createMindSlaveController(final Player master, final Player slave) {
        return controllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(final Game game, final int id) {
        final Player p = new Player(getName(), game, id);
        p.setFirstController(controllerFor(p));
        return p;
    }
}
