package forge.ai.simulation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane lki-leak-1027: a last-known-information card kept after its snapshot was replaced must not keep the rest of
 * that snapshot alive. A permanent that leaves the battlefield is remembered (Game#getLeftBattlefieldThisTurn) as its
 * LKI card from the game's last-state snapshot; that card's zone is the snapshot's LKI zone (Zone#getLKICopy), which
 * held every LKI card of the snapshot. With one snapshot per departure (the game takes a new one at every state check
 * and resolution), N departures in a turn kept N whole battlefields: the labelling replays of long thopter-sword games
 * filled a 5 GB heap with 275k cards this way.
 */
public class LkiSnapshotRetentionTest extends SimulationTest {

    private static Set<Card> reachableLki(final Game game) {
        final Set<Card> reach = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Card lki : game.getLeftBattlefieldThisTurn()) {
            reach.add(lki);
            if (lki.getZone() != null) {
                for (Card sibling : lki.getZone().getCards()) {
                    reach.add(sibling);
                }
            }
        }
        return reach;
    }

    @Test
    public void departedPermanentsDoNotKeepTheirWholeSnapshot() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCards("Plains", 30, p);
        final List<Card> leaving = addCards("Memnite", 40, p);
        game.getAction().checkStateEffects(true);

        for (Card c : leaving) {
            game.copyLastState();              // as every state check / resolution does
            game.getAction().moveToGraveyard(c, null);
        }
        game.copyLastState();

        AssertJUnit.assertEquals(40, game.getLeftBattlefieldThisTurn().size());
        // what the battlefield said a moment ago is unchanged: the current snapshot still knows all 30 Plains
        AssertJUnit.assertEquals(30, game.getLastStateBattlefield().size());
        AssertJUnit.assertEquals(40, game.getLastStateGraveyard().size());
        // before the fix: ~40 snapshots x (30 + the Memnites still there) = ~2,000 LKI cards; after: the 40 kept ones
        final int reach = reachableLki(game).size();
        AssertJUnit.assertTrue("LKI cards reachable from the 40 departures: " + reach, reach <= 40);
        // every kept LKI still answers as the battlefield card it was
        for (Card lki : game.getLeftBattlefieldThisTurn()) {
            AssertJUnit.assertTrue(lki.isLKI());
            AssertJUnit.assertEquals(ZoneType.Battlefield, lki.getZone().getZoneType());
            AssertJUnit.assertEquals("Memnite", lki.getName());
        }
    }

    @Test
    public void theCurrentSnapshotKeepsItsZoneContents() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCards("Plains", 5, p);
        game.getAction().checkStateEffects(true);
        game.copyLastState();
        // until the next snapshot, an LKI card's zone lists its snapshot exactly as before the fix
        for (Card lki : game.getLastStateBattlefield()) {
            if (lki.getController() == p) {
                AssertJUnit.assertEquals(5, lki.getZone().getCards().size());
                AssertJUnit.assertTrue(lki.getZone().getCards().contains(lki));
            }
        }
    }
}
