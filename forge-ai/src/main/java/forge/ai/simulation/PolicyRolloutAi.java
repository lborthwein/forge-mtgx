package forge.ai.simulation;

import java.util.List;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * L2 step 2 hook (l2-design §5 "Play-outs"; NOT USED, wired only): our seat's play-out controller piloted by P1 after
 * the scripted candidate has been played, with {@link LookaheadSearch.RolloutAi}'s loop breaker. The pilot would be
 * bound to the play-out copy and see only our view of it, so the sampled world never reaches the service. Nothing
 * constructs it yet: {@link LookaheadSearch.Config#policyRollouts} is refused at construction until step 2 has a PASS
 * at L2 and its own predeclaration.
 */
final class PolicyRolloutAi extends LookaheadSearch.RolloutAi {
    private final PolicyPilot pilot;

    PolicyRolloutAi(Game g, Player p, LobbyPlayer lp, PolicyPilot pilot) {
        super(g, p, lp);
        this.pilot = pilot;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final List<SpellAbility> def = getAi().chooseSpellAbilityToPlay();
        return capped(pilot == null ? def : pilot.decide(this, def));
    }
}
