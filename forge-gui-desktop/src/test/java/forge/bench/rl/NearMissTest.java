package forge.bench.rl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.ai.simulation.GameCopier;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import static org.testng.Assert.*;

/**
 * nearmiss-gen-1109: the near-miss edit option. Off by default; an edit is reproducible from (seed, game seed, seat,
 * dec_idx, k); applied to a GameCopier copy it does exactly what it says (one card moved, one land added or removed,
 * one land tapped or untapped, or life changed) and leaves the live game as it was.
 */
public class NearMissTest extends SimulationTest {

    private Game position() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        addCard("Island", me);
        addCard("Swamp", me);
        addCard("Island", me).setTapped(true);
        for (String n : new String[] {"Dark Ritual", "Brainstorm", "Island"}) {
            addCardToZone(n, me, ZoneType.Hand);
        }
        for (String n : new String[] {"Swamp", "Duress", "Island", "Ponder", "Swamp", "Lotus Petal", "Island", "Duress"}) {
            addCardToZone(n, me, ZoneType.Library);
        }
        addCardToZone("Ponder", me, ZoneType.Graveyard);
        addCardToZone("Island", g.getPlayers().get(1), ZoneType.Library);
        return g;
    }

    private static Map<ZoneType, Integer> sizes(final Player p) {
        final Map<ZoneType, Integer> m = new HashMap<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Library, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Battlefield}) {
            m.put(z, p.getCardsIn(z).size());
        }
        return m;
    }

    @Test
    public void offByDefault() {
        assertEquals(NearMiss.K, 0, "no -Dforge.bench.label.nearMiss in the test JVM");
        assertFalse(NearMiss.on());
        assertTrue(NearMiss.selected(1L, 0, 0), "no list: every position");
    }

    @Test
    public void anEditIsReproducibleFromTheSeed() {
        final Game g = position();
        final Player me = g.getPlayers().get(0);
        int differ = 0;
        for (int k = 1; k <= 40; k++) {
            final NearMiss.Edit a = NearMiss.choose(g, me, 747631005L, 17, k);
            final NearMiss.Edit b = NearMiss.choose(g, me, 747631005L, 17, k);
            assertEquals(a.desc, b.desc, "same seed, same edit");
            final NearMiss.Edit c = NearMiss.choose(g, me, 747631006L, 17, k);
            differ += a.desc.equals(c.desc) ? 0 : 1;
        }
        assertTrue(differ > 20, "another game seed draws other edits: " + differ + "/40");
    }

    @Test
    public void eachEditDoesWhatItSaysOnACopyOnly() {
        final Game g = position();
        final Player me = g.getPlayers().get(0);
        final Map<ZoneType, Integer> live0 = sizes(me);
        final int life0 = me.getLife();
        final Map<String, Integer> kinds = new HashMap<>();
        for (int k = 1; k <= 120; k++) {
            final NearMiss.Edit e = NearMiss.choose(g, me, 747631005L, 23, k);
            kinds.merge(e.kind, 1, Integer::sum);
            final GameCopier copier = new GameCopier(g, true);
            final Game c = copier.makeCopy();
            final Player pc = (Player) copier.find(me);
            final Map<ZoneType, Integer> s0 = sizes(pc);
            final int l0 = pc.getLife();
            assertNull(e.apply(c), e.desc);
            final Map<ZoneType, Integer> s1 = sizes(pc);
            if ("life".equals(e.kind)) {
                assertEquals(pc.getLife(), l0 + e.lifeDelta, e.desc);
                assertTrue(pc.getLife() >= 1, e.desc);
                assertEquals(s1, s0, e.desc);
                continue;
            }
            assertEquals(pc.getLife(), l0, e.desc);
            final Card moved = c.findById(e.cardId);
            assertNotNull(moved, e.desc);
            switch (e.kind) {
                case "tap":
                case "untap":
                    assertEquals(moved.isTapped(), "tap".equals(e.kind), e.desc);
                    assertEquals(s1, s0, e.desc);
                    break;
                case "land:add":
                    assertTrue(moved.isInZone(ZoneType.Battlefield) && moved.isLand() && !moved.isTapped(), e.desc);
                    assertEquals((int) s1.get(ZoneType.Battlefield), s0.get(ZoneType.Battlefield) + 1, e.desc);
                    assertEquals((int) s1.get(e.from), s0.get(e.from) - 1, e.desc);
                    break;
                case "land:remove":
                    assertTrue(moved.isInZone(ZoneType.Graveyard), e.desc);
                    assertEquals((int) s1.get(ZoneType.Battlefield), s0.get(ZoneType.Battlefield) - 1, e.desc);
                    assertEquals((int) s1.get(ZoneType.Graveyard), s0.get(ZoneType.Graveyard) + 1, e.desc);
                    break;
                default:
                    assertTrue(moved.isInZone(e.to), e.desc);
                    assertEquals((int) s1.get(e.from), s0.get(e.from) - 1, e.desc);
                    assertEquals((int) s1.get(e.to), s0.get(e.to) + 1, e.desc);
                    if (e.to == ZoneType.Library) {
                        final List<Card> lib = new java.util.ArrayList<>(pc.getCardsIn(ZoneType.Library));
                        assertEquals(lib.indexOf(moved), Math.min(e.libPos, lib.size() - 1), e.desc);
                    }
            }
            // the live game is never edited
            assertEquals(sizes(me), live0, e.desc);
            assertEquals(me.getLife(), life0, e.desc);
        }
        final int handLib = kinds.getOrDefault("move:H>L", 0) + kinds.getOrDefault("move:L>H", 0);
        assertTrue(handLib > 50, "hand/library moves carry most of the mass: " + kinds);
        assertTrue(kinds.size() >= 6, "the other kinds still occur: " + kinds);
    }

    @Test
    public void aimedEditsHitTheLinesCardsOrTheDefendersInteraction() {
        final Game g = position();
        final Player me = g.getPlayers().get(0);
        Card ritual = null, swamp = null, libCard = null;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if ("Dark Ritual".equals(c.getName())) ritual = c;
        }
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if ("Swamp".equals(c.getName())) swamp = c;
        }
        libCard = me.getCardsIn(ZoneType.Library).get(3);
        final java.util.Set<Integer> ids = java.util.Set.of(ritual.getId(), swamp.getId(), libCard.getId());
        final List<int[]> line = List.of(new int[] {ritual.getId(), 1}, new int[] {swamp.getId(), 4},
                new int[] {libCard.getId(), 5});
        int aimed = 0;
        for (int k = 1; k <= 100; k++) {
            final NearMiss.Edit e = NearMiss.choose(g, me, 5L, 9, k, "win", line);
            assertEquals(NearMiss.choose(g, me, 5L, 9, k, "win", line).desc, e.desc, "reproducible");
            if (!e.kind.startsWith("line:")) {
                continue;
            }
            aimed++;
            assertTrue(ids.contains(e.cardId), e.desc);
            final GameCopier copier = new GameCopier(g, true);
            final Game c = copier.makeCopy();
            assertNull(e.apply(c), e.desc);
            assertTrue(c.findById(e.cardId).isInZone(e.to), e.desc);
        }
        assertTrue(aimed >= 40 && aimed <= 80, "about 60 % aimed: " + aimed);
        int def = 0;
        for (int k = 1; k <= 100; k++) {
            final NearMiss.Edit e = NearMiss.choose(g, me, 5L, 9, k, "loss", null);
            if (!e.kind.startsWith("def:")) {
                continue;
            }
            def++;
            final Card c = g.findById(e.cardId);
            assertTrue("def:land:remove".equals(e.kind) ? c.isLand() && !c.isTapped() : NearMiss.interaction(c), e.desc);
        }
        assertTrue(def >= 40 && def <= 80, "about 60 % aimed at the defender: " + def);
    }

    @Test
    public void aMissingCardIsReportedAndChangesNothing() {
        final Game g = position();
        final Player me = g.getPlayers().get(0);
        NearMiss.Edit e = null;
        for (int k = 1; k <= 200 && (e == null || !e.kind.startsWith("move:H>")); k++) {
            e = NearMiss.choose(g, me, 1L, 1, k);
        }
        assertNotNull(e);
        final GameCopier copier = new GameCopier(g, true);
        final Game c = copier.makeCopy();
        final Player pc = (Player) copier.find(me);
        final Card x = c.findById(e.cardId);
        c.getAction().moveToGraveyard(x, null);
        final Map<ZoneType, Integer> s0 = sizes(pc);
        assertNotNull(e.apply(c), "the card is not where the live game had it");
        assertEquals(sizes(pc), s0);
    }
}
