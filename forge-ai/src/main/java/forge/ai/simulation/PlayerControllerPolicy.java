package forge.ai.simulation;

import java.util.List;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Forge AI whose priority choice may be replaced by policy P1 ({@link PolicyPilot}, lane l2-fork-1001), in the live
 * game its lobby player was bound to and only for its own seat. Forge AI computes its answer first, exactly as
 * without the pilot; the pilot then keeps it, vetoes it (Forge AI is asked again without the vetoed hand casts), or
 * replaces it with a legal cast / land play. Every other decision -- targets, modes, payments, attacks, blocks,
 * mulligans, library searches, choices inside resolving effects -- is Forge AI's own (no other override, unlike
 * {@link PlayerControllerLookahead}'s combat and tutor hooks). In any copy of the game it is plain Forge AI.
 */
public class PlayerControllerPolicy extends PlayerControllerAi {
    private final LobbyPlayerPolicy lobby;

    public PlayerControllerPolicy(Game game, Player p, LobbyPlayerPolicy lp) {
        super(game, p, lp);
        this.lobby = lp;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final List<SpellAbility> def = super.chooseSpellAbilityToPlay();
        final PolicyPilot pilot = lobby.getPilot();
        if (pilot == null || getGame() != lobby.getLiveGame()) {
            return def;
        }
        return pilot.decide(this, def);
    }
}
