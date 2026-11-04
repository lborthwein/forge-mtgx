package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * void-causes-1104 (S2 label void 2989378278, doomsday vs sneak-show, frozen jar 9489c3db): R3LabelSearch.candidates
 * copies the live game with its stack when {@link GameCopier#stackUnsupported} accepts it. A token's ability on the
 * stack whose token was sacrificed to pay for it (a Clue's "sacrifice: draw") passed the check (the host's last-known
 * zone is the graveyard), then {@code copyStackFaithfully} threw "Couldn't map Clue Token": the host exists in no zone
 * of the game, so the copy has no card to rebuild the ability on. The check now refuses such a stack ("gone").
 */
public class GameCopierStackHostGoneTest extends SimulationTest {

    @Test
    public void anAbilityWhoseTokenIsGoneIsUnsupportedNotACrash() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card clue = addToken("c_a_clue_draw", p);
        game.getAction().checkStateEffects(true);
        SpellAbility draw = null;
        for (SpellAbility sa : clue.getSpellAbilities()) {
            if (sa.isActivatedAbility()) {
                draw = sa;
            }
        }
        Assert.assertNotNull(draw);
        draw.setActivatingPlayer(p);
        game.getStack().add(draw);
        Assert.assertNull(GameCopier.stackUnsupported(game), "the token is still there: supported");
        // pay the sacrifice: the token leaves the battlefield and ceases to exist
        game.getAction().sacrifice(new forge.game.card.CardCollection(clue), draw, false, null);
        game.getAction().checkStateEffects(true);
        Assert.assertNull(game.findById(clue.getId()), "a sacrificed token exists in no zone");
        Assert.assertFalse(game.getStack().isEmpty());
        Assert.assertEquals(GameCopier.stackUnsupported(game), "gone");
    }
}
