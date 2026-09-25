package forge.ai.simulation;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Forge's AI with a look-ahead over its priority decisions ({@link LookaheadSearch}). Every
 * other decision (attacks, blocks, targets of triggers, payments) is Forge AI's own. Searches
 * only in the live game its lobby player was bound to; in any copy it is plain Forge AI.
 */
public class PlayerControllerLookahead extends PlayerControllerAi {
    private final LobbyPlayerLookahead lobby;

    public PlayerControllerLookahead(Game game, Player p, LobbyPlayerLookahead lp) {
        super(game, p, lp);
        this.lobby = lp;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final List<SpellAbility> def = super.chooseSpellAbilityToPlay();
        final LookaheadSearch search = lobby.getSearch();
        if (search == null || getGame() != lobby.getLiveGame()) {
            return def;
        }
        return search.decide(this, def);
    }

    public LobbyPlayer getLookaheadLobby() {
        return lobby;
    }
}
