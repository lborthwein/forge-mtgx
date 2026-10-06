package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Spell Queller blinked by Displacer Kitten (lane forge-spellhand-loop-1006; d1-fb-1004 seed T1-719302168-F1-o6-r0-s1
 * on the recast-guard jar, turn 21).
 *
 * <p>The AI casts a Mox. Kitten blinks Spell Queller, so the old Queller object's leaves-the-battlefield trigger and
 * the new object's enter trigger both go on the stack, and the new object exiles the AI's own Mox. Then the old
 * object's leaves trigger ("the exiled card's owner may cast that card", Controller$ RememberedOwner, then Cleanup
 * ClearRemembered) resolves. The old object exiled nothing, so its linked ability has no card and no player (CR 607.2a,
 * 400.7) and does nothing. It used to:
 * <ul>
 * <li>call get(0) on an empty player list, an IndexOutOfBoundsException that voided the game;</li>
 * <li>clear the NEW object's remembered card, so the Mox stayed exiled for good.</li>
 * </ul>
 *
 * <p>With a second Mox in hand the AI blinks the Queller again. The object that exiled the first Mox leaves, and the
 * Mox's owner (the AI, which also controlled it) may cast it without paying its mana cost. That cast triggers Kitten
 * again. In this position the exchange stops by itself after three exiles; should it not stop, {@link AiRecastGuard}
 * ends it, because it covers offered casts too.
 */
public class SpellQuellerBlinkTest extends AITest {

    private Card findIn(Player p, ZoneType z, String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void testBlinkedQuellerKeepsItsLinkAndTheOwnerCastsTheExiledMox() {
        AiRecastGuard.setLimit(AiRecastGuard.DEFAULT_LIMIT);
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        addCard("Displacer Kitten", ai);
        addCard("Spell Queller", ai);
        addCards("Island", 3, ai);
        addCard("Runeclaw Bear", opp);
        addCards("Swamp", 2, opp);
        Card pearl = addCardToZone("Mox Pearl", ai, ZoneType.Hand);
        Card jet = addCardToZone("Mox Jet", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        // Any exception (the old IndexOutOfBoundsException) fails the test here.
        for (int i = 0; i < 3000 && !game.isGameOver() && game.getPhaseHandler().is(PhaseType.MAIN1)
                && game.getPhaseHandler().getPlayerTurn() == ai; i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        AssertJUnit.assertFalse(game.isGameOver());
        AssertJUnit.assertFalse("the AI's main phase ends",
                game.getPhaseHandler().is(PhaseType.MAIN1) && game.getPhaseHandler().getPlayerTurn() == ai);

        // Each blink: the new Queller exiles the Mox that was just cast; the Queller that exiled the previous Mox leaves
        // and its owner casts that Mox again from exile. So some Mox was cast more than once this turn. Before the fix
        // the first exiled Mox lost its link and was never cast again (and the first blink crashed).
        final int pearlCasts = AiRecastGuard.castsThisTurn(ai, pearl.getId());
        final int jetCasts = AiRecastGuard.castsThisTurn(ai, jet.getId());
        AssertJUnit.assertTrue("a Mox exiled by a blinked Queller was cast again by its owner (Pearl " + pearlCasts
                + ", Jet " + jetCasts + ")", pearlCasts >= 2 || jetCasts >= 2);
    }
}
