package forge.ai.simulation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Maps;

import forge.ai.ComputerUtil;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.cost.CostSacrifice;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * Lane lki-leak-1027: a last-known-information card kept after its snapshot was replaced must not keep the rest of
 * that snapshot alive. A permanent that leaves the battlefield is remembered (Game#getLeftBattlefieldThisTurn) as its
 * LKI card from the game's last-state snapshot; that card's zone is the snapshot's LKI zone (Zone#getLKICopy), which
 * held every LKI card of the snapshot. With one snapshot per departure (the game takes a new one at every state check
 * and resolution), N departures in a turn kept N whole battlefields: the labelling replays of long thopter-sword games
 * filled a 5 GB heap with 275k cards this way.
 *
 * <p>Lane lki-fix-port-1027 (review of #121 / #122): the same chain through a sacrifice cost
 * (CostPartWithList -> Game#copyLastState(ZoneType) -> Player#getSacrificedThisTurn), the typed snapshot released at
 * once, and only last-known-information zones are ever released (never a player's zone or the stack zone).
 */
public class LkiSnapshotRetentionTest extends SimulationTest {

    /** The LKI cards reachable from {@code kept}: each one and every card its zone still lists. */
    private static Set<Card> reachableLki(final Iterable<Card> kept) {
        final Set<Card> reach = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Card lki : kept) {
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
        final int reach = reachableLki(game.getLeftBattlefieldThisTurn()).size();
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

    /**
     * Review S3-1: the sacrifice chain. Each sacrifice cost is paid through CostPartWithList, which takes a typed
     * snapshot (Game#copyLastStateBattlefield) for its zone table; the sacrificed card's LKI comes from that snapshot and
     * is kept in Player#getSacrificedThisTurn until cleanup. Before the fix each one kept its whole snapshot zone.
     */
    @Test
    public void sacrificedPermanentsDoNotKeepTheirCostSnapshot() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCards("Plains", 30, p);
        final List<Card> gnomes = addCards("Bottle Gnomes", 40, p);
        game.getAction().checkStateEffects(true);

        for (Card g : gnomes) {
            game.copyLastState();
            AssertJUnit.assertTrue("Bottle Gnomes paid its sacrifice cost",
                    ComputerUtil.handlePlayingSpellAbility(p, sacrificeAbility(g, p), null));
        }
        game.copyLastState();

        AssertJUnit.assertEquals(40, p.getSacrificedThisTurn().size());
        AssertJUnit.assertEquals(30, p.getCardsIn(ZoneType.Battlefield).size());
        // before the fix: ~40 typed snapshots x (30 Plains + the Gnomes still there) = ~2,000 LKI cards; after: 40
        final int reach = reachableLki(p.getSacrificedThisTurn()).size();
        AssertJUnit.assertTrue("LKI cards reachable from the 40 sacrifices: " + reach, reach <= 40);
        for (Card lki : p.getSacrificedThisTurn()) {
            AssertJUnit.assertTrue(lki.isLKI());
            AssertJUnit.assertEquals(ZoneType.Battlefield, lki.getZone().getZoneType());
            AssertJUnit.assertEquals("Bottle Gnomes", lki.getName());
        }
    }

    private static SpellAbility sacrificeAbility(final Card c, final Player p) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.getPayCosts() != null
                    && sa.getPayCosts().hasSpecificCostType(CostSacrifice.class)) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        throw new AssertionError("no sacrifice ability on " + c);
    }

    /** Review S3-2: the typed snapshot's zone (cost and zone-change tables) is released at once; its cards stay LKI. */
    @Test
    public void aTypedSnapshotIsReleasedAtOnce() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCards("Plains", 7, p);
        game.getAction().checkStateEffects(true);
        final CardCollectionView lki = game.copyLastStateBattlefield();
        AssertJUnit.assertEquals(7, lki.size());
        for (Card c : lki) {
            AssertJUnit.assertTrue(c.isLKI());
            AssertJUnit.assertEquals(ZoneType.Battlefield, c.getZone().getZoneType());
            AssertJUnit.assertTrue("released: " + c.getZone().getCards().size(), c.getZone().getCards().isEmpty());
        }
        AssertJUnit.assertEquals(7, p.getCardsIn(ZoneType.Battlefield).size());
    }

    /** Review S3-3: only a last-known-information zone is ever emptied, never a live player zone or the stack zone. */
    @Test
    public void liveZonesAreNeverReleased() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCards("Plains", 4, p);
        addCards("Memnite", 2, p);
        game.getAction().checkStateEffects(true);
        final Zone battlefield = p.getZone(ZoneType.Battlefield);
        AssertJUnit.assertFalse(battlefield.releaseLastKnownCards());
        AssertJUnit.assertEquals(6, battlefield.getCards().size());
        AssertJUnit.assertEquals(6, p.getCardsIn(ZoneType.Battlefield).size());
        AssertJUnit.assertFalse(p.getZone(ZoneType.Library).releaseLastKnownCards());
        AssertJUnit.assertFalse(game.getStackZone().releaseLastKnownCards());
        // many snapshots later the live zones still hold every card
        for (int i = 0; i < 5; i++) {
            game.copyLastState();
            game.copyLastStateBattlefield();
            game.copyLastStateGraveyard();
        }
        AssertJUnit.assertEquals(6, battlefield.getCards().size());
        for (Card c : battlefield.getCards()) {
            AssertJUnit.assertFalse(c.isLKI());
            AssertJUnit.assertSame(battlefield, c.getZone());
        }
        // and a last-known-information copy is released exactly once
        final Map<Integer, Card> cache = Maps.newHashMap();
        final Zone copy = battlefield.getLKICopy(cache);
        AssertJUnit.assertEquals(6, copy.getCards().size());
        AssertJUnit.assertTrue(copy.releaseLastKnownCards());
        AssertJUnit.assertTrue(copy.getCards().isEmpty());
        AssertJUnit.assertEquals(6, battlefield.getCards().size());
    }
}
