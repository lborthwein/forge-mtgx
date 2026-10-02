package forge.ai.simulation;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

/**
 * A seat played by Forge AI plus policy P1 ({@link PolicyPilot}; LookaheadBench seat mode {@code "policy"}, lane
 * l2-fork-1001). The runner binds it to one live game ({@link #bind}); the pilot acts only there and only for the
 * player this lobby created. A mind-slave controller is plain Forge AI (as {@link LobbyPlayerAi} makes it).
 */
public class LobbyPlayerPolicy extends LobbyPlayerAi {
    private PolicyPilot pilot;
    private Game liveGame;

    public LobbyPlayerPolicy(String name) {
        super(name, null);
    }

    public void bind(Game game, Player me, PolicyPilot pilot) {
        this.liveGame = game;
        this.pilot = pilot;
        if (pilot != null) {
            pilot.bind(game, me);
        }
    }

    public Game getLiveGame() {
        return liveGame;
    }

    public PolicyPilot getPilot() {
        return pilot;
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        // As LobbyPlayerAi.createIngamePlayer / createControllerFor (no AI option, no profile rotation), so that a seat
        // whose pilot keeps every answer plays exactly as a "default" seat.
        Player ai = new Player(getName(), game, id);
        PlayerControllerPolicy c = new PlayerControllerPolicy(game, ai, this);
        c.getAi().setUseSimulation(null);
        ai.setFirstController(c);
        return ai;
    }
}
