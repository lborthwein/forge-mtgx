package forge.ai.simulation;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * A seat that plays Forge AI plus {@link LookaheadSearch}. The runner binds it to one live game
 * ({@link #bind}); copies of that game never search (GameCopier's plain-AI copies replace it,
 * and any other copy fails the live-game identity check).
 */
public class LobbyPlayerLookahead extends LobbyPlayerAi {
    private LookaheadSearch search;
    private Game liveGame;

    public LobbyPlayerLookahead(String name) {
        super(name, null);
    }

    public void bind(Game game, LookaheadSearch search) {
        this.liveGame = game;
        this.search = search;
    }

    public Game getLiveGame() {
        return liveGame;
    }

    public LookaheadSearch getSearch() {
        return search;
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(new PlayerControllerLookahead(game, ai, this));
        return ai;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new PlayerControllerLookahead(slave.getGame(), slave, this);
    }
}
