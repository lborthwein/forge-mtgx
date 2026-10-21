package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * combo-assembly-1021 (B1): {@link LookaheadSearch#resolveChoice} replays the live game's resolving library search in
 * a copy with a given card taken, and Forge's own script places it (hand, library top + shuffle + life, graveyard,
 * battlefield). The live game is untouched; a library placement is reported to the hooks.
 */
public class LookaheadChoiceCopyTest extends SimulationTest {

    private static final String[] LIB = {"Griselbrand", "Lightning Bolt", "Counterspell", "Sol Ring", "Island", "Swamp",
            "Ponder", "Dark Ritual", "Llanowar Elves", "Forest"};

    private Game board(final String tutor) {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0);
        final Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 20; i++) {
            addCardToZone(LIB[i % LIB.length], a, ZoneType.Library);
            addCardToZone("Forest", b, ZoneType.Library);
        }
        final Card t = addCardToZone(tutor, a, ZoneType.Hand);
        final SpellAbility sa = t.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        game.getStack().add(sa);
        return game;
    }

    private static LookaheadSearch search() {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 1;
        c.threads = 1;
        c.seed = 724_000_001L;
        return new LookaheadSearch(c);
    }

    private static Card named(final Player p, final String name) {
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static final class Hooks implements LookaheadSearch.ChoiceHooks {
        final List<String> placed = new ArrayList<>();
        int copies;

        @Override
        public Object onCopy(final Game copy, final Player me) {
            copies++;
            return "ctx";
        }

        @Override
        public void placed(final Object ctx, final Game copy, final Player me, final Card card) {
            AssertJUnit.assertEquals("ctx", ctx);
            placed.add(card.getName());
        }
    }

    @Test
    public void demonicTutorPutsTheForcedCardInTheCopysHand() {
        final Game live = board("Demonic Tutor");
        final Player a = live.getPlayers().get(0);
        final int libBefore = a.getCardsIn(ZoneType.Library).size();
        final Card target = named(a, "Griselbrand");
        final Hooks h = new Hooks();
        final LookaheadSearch.ChoiceCopy cc = search().resolveChoice(live, a, target.getId(), 7L, h);
        AssertJUnit.assertFalse(String.valueOf(cc.error), cc.failed);
        AssertJUnit.assertTrue(cc.used);
        AssertJUnit.assertEquals(1, h.copies);
        AssertJUnit.assertTrue(cc.me.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getName().equals("Griselbrand")));
        AssertJUnit.assertEquals(libBefore - 1, cc.me.getCardsIn(ZoneType.Library).size());
        AssertJUnit.assertTrue("no placement for a hand destination", h.placed.isEmpty());
        // the live game is untouched: the tutor still on its stack, the card still in the library
        AssertJUnit.assertEquals(1, live.getStack().size());
        AssertJUnit.assertEquals(libBefore, a.getCardsIn(ZoneType.Library).size());
        AssertJUnit.assertNotNull(named(a, "Griselbrand"));
    }

    @Test
    public void vampiricTutorPlacesOnTopPaysLifeAndReportsThePlacement() {
        final Game live = board("Vampiric Tutor");
        final Player a = live.getPlayers().get(0);
        final int life = a.getLife();
        final Card target = named(a, "Sol Ring");
        final Hooks h = new Hooks();
        final LookaheadSearch.ChoiceCopy cc = search().resolveChoice(live, a, target.getId(), 7L, h);
        AssertJUnit.assertFalse(String.valueOf(cc.error), cc.failed);
        AssertJUnit.assertEquals("Sol Ring", cc.me.getCardsIn(ZoneType.Library).get(0).getName());
        AssertJUnit.assertEquals(life - 2, cc.me.getLife());
        AssertJUnit.assertEquals(List.of("Sol Ring"), h.placed);
        AssertJUnit.assertEquals(life, a.getLife());
    }

    @Test
    public void entombPutsTheForcedCardInTheCopysGraveyard() {
        final Game live = board("Entomb");
        final Player a = live.getPlayers().get(0);
        final Card target = named(a, "Griselbrand");
        final LookaheadSearch.ChoiceCopy cc = search().resolveChoice(live, a, target.getId(), 7L, new Hooks());
        AssertJUnit.assertFalse(String.valueOf(cc.error), cc.failed);
        AssertJUnit.assertTrue(cc.me.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c -> c.getName().equals("Griselbrand")));
    }

    /** A Doomsday deck at our main phase: seven Underground Seas, Brainstorm in hand, Doomsday and Oracle in the library. */
    private Game doomsdayBoard() {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0);
        final Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 7; i++) {
            addCard("Underground Sea", a);
        }
        addCardToZone("Brainstorm", a, ZoneType.Hand);
        final String[] lib = {"Doomsday", "Thassa's Oracle", "Island", "Swamp", "Ponder", "Lightning Bolt", "Forest", "Mountain"};
        for (int i = 0; i < 24; i++) {
            addCardToZone(i < lib.length ? lib[i] : "Island", a, ZoneType.Library);
            addCardToZone("Forest", b, ZoneType.Library);
        }
        final Card t = addCardToZone("Vampiric Tutor", a, ZoneType.Hand);
        final SpellAbility sa = t.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        game.getStack().add(sa);
        return game;
    }

    /** G1 attempt 3: the one-step reach after Vampiric -> Doomsday: our next main phase 1 with Doomsday drawn. */
    @Test
    public void vampiricDoomsdayReachDrawsTheKnownTop() {
        final Game live = doomsdayBoard();
        final Player a = live.getPlayers().get(0);
        final int turn = live.getPhaseHandler().getTurn();
        final LookaheadSearch.ChoiceCopy cc = search().resolveChoice(live, a, named(a, "Doomsday").getId(), 7L, new Hooks());
        AssertJUnit.assertFalse(String.valueOf(cc.error), cc.failed);
        AssertJUnit.assertEquals("Doomsday", cc.me.getCardsIn(ZoneType.Library).get(0).getName());
        AssertJUnit.assertTrue(LookaheadSearch.passToOurNextMain(cc, 600));
        AssertJUnit.assertTrue(cc.g.getPhaseHandler().getTurn() > turn);
        AssertJUnit.assertTrue(cc.g.getPhaseHandler().isPlayerTurn(cc.me));
        AssertJUnit.assertTrue(cc.g.getPhaseHandler().is(PhaseType.MAIN1));
        AssertJUnit.assertTrue(cc.me.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getName().equals("Doomsday")));
        // the live game did not move
        AssertJUnit.assertEquals(turn, live.getPhaseHandler().getTurn());
    }

    /** ... and from there the forced-win solver (hideOwnLibrary) proves the Doomsday line. */
    @Test
    public void vampiricDoomsdayReachIsProvenBySolver() {
        final Game live = doomsdayBoard();
        final Player a = live.getPlayers().get(0);
        final LookaheadSearch.ChoiceCopy cc = search().resolveChoice(live, a, named(a, "Doomsday").getId(), 7L, new Hooks());
        AssertJUnit.assertTrue(LookaheadSearch.passToOurNextMain(cc, 600));
        final ForcedWinSolver.Config c = new ForcedWinSolver.Config();
        c.hideOwnLibrary = true;
        c.worlds = 2;
        final ForcedWinSolver s = new ForcedWinSolver(cc.me, c);
        final List<ForcedWinSolver.Proposal> props = s.propose(cc.g);
        boolean proven = false;
        for (ForcedWinSolver.Proposal p : props) {
            proven |= p.proven(2);
        }
        AssertJUnit.assertTrue("proposals: " + props.size(), proven);
    }

    @Test
    public void aCardTheSearchDoesNotOfferFailsCleanly() {
        final Game live = board("Mystical Tutor"); // instants and sorceries only
        final Player a = live.getPlayers().get(0);
        final Card creature = named(a, "Griselbrand");
        final LookaheadSearch.ChoiceCopy cc = search().resolveChoice(live, a, creature.getId(), 7L, new Hooks());
        AssertJUnit.assertTrue(cc.failed);
        AssertJUnit.assertFalse(cc.used);
        AssertJUnit.assertNull(cc.g);
    }
}
