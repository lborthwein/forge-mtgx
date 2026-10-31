package forge.game;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * JAR-TEXT-ASKS item 4 (lane live-ai-quality-1030, live smoke 2026-10-29): RepeatEffect's upstream placeholder "Repeat
 * something. Somebody should really write a better StackDescription!" showed in the payment prompt and the stack rail
 * for Fiery Gambit. Its stack text now says the card's own words.
 */
public class RepeatStackDescriptionTest extends AITest {

    @Test
    public void fieryGambitSaysItsOwnWords() {
        Game game = initAndCreateGame();
        Player me = game.getPlayers().get(1);
        Card bears = addCard("Grizzly Bears", game.getPlayers().get(0));
        Card fg = addCardToZone("Fiery Gambit", me, ZoneType.Hand);
        SpellAbility sa = fg.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(bears);
        String d = sa.getStackDescription();
        AssertJUnit.assertFalse(d, d.contains("Somebody should really write"));
        AssertJUnit.assertTrue(d, d.contains("Flip a coin until you lose a flip"));
    }
}
