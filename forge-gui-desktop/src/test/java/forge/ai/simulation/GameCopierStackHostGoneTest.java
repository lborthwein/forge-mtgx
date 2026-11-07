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
 *
 * <p>unified-jar-1106 (936560e5cf9) keyed this refusal on {@code -Dforge.researchFixes} (off = the live line's copier,
 * byte for byte), so the test sets the key: on, the stack is refused; off, it is accepted as before.
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
        final java.util.Map<forge.game.ability.AbilityKey, Object> params = forge.game.ability.AbilityKey.newMap();
        params.put(forge.game.ability.AbilityKey.LastStateBattlefield, game.copyLastStateBattlefield());
        game.getAction().sacrifice(new forge.game.card.CardCollection(clue), draw, false, params);
        game.getAction().checkStateEffects(true);
        Assert.assertNull(game.findById(clue.getId()), "a sacrificed token exists in no zone");
        Assert.assertFalse(game.getStack().isEmpty());
        final boolean was = forge.game.EngineFixes.researchFixes();
        try {
            // key off: the live line's check, unchanged, accepts this stack (the label void's stack)
            forge.game.EngineFixes.setResearchFixesForTest(false);
            Assert.assertNull(GameCopier.stackUnsupported(game), "key off: the check is unchanged");
            // key on: refused before the copy, instead of the copy throwing
            forge.game.EngineFixes.setResearchFixesForTest(true);
            final String why = GameCopier.stackUnsupported(game);
            if (why == null) {
                final GameCopier copier = new GameCopier(game, true);
                copier.setCopyStack(true);
                try {
                    copier.makeCopy();
                    Assert.fail("accepted, and the copy did not throw either");
                } catch (RuntimeException e) {
                    Assert.fail("stackUnsupported accepted a stack the copier cannot rebuild: " + e);
                }
            }
            Assert.assertEquals(why, "gone");
        } finally {
            forge.game.EngineFixes.setResearchFixesForTest(was);
        }
    }
}
