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

    @Override
    public void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
        super.declareAttackers(attacker, combat);
        final LookaheadSearch search = lobby.getSearch();
        if (search != null && getGame() == lobby.getLiveGame() && search.getConfig().combat && attacker == getPlayer()) {
            search.decideAttack(this, combat);
        }
    }

    @Override
    public void declareBlockers(Player defender, forge.game.combat.Combat combat) {
        super.declareBlockers(defender, combat);
        final LookaheadSearch search = lobby.getSearch();
        if (search != null && getGame() == lobby.getLiveGame() && search.getConfig().combat) {
            search.decideBlock(this, defender, combat);
        }
    }

    public LobbyPlayer getLookaheadLobby() {
        return lobby;
    }
}
