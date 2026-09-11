package forge.ai;

import forge.game.Game;
import forge.game.player.Player;

/** Native Default heuristics with explicitly versioned combo execution. */
public final class LobbyPlayerCubeComboAi extends LobbyPlayerAi {
    public LobbyPlayerCubeComboAi(String name) {
        super(name, null);
        setAiProfile("Default");
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new CubeComboPlayerController(game, player, this));
        return player;
    }
}
