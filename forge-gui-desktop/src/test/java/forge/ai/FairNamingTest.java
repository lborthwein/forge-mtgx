package forge.ai;

import java.util.Arrays;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Fair card naming (lane ai-misplays-1006, owner report 2026-10-06T01:51Z, live session a559930d, round 2 game 1):
 * "Did the AI name Oko with its Phyrexian Revoker? How did it know I had that in my deck?". At the AI's turn 3 the owner
 * had shown only Botanical Sanctum, and Oko, Thief of Crowns was in his hand or library. Upstream Forge AI's Revoker
 * logic ({@link SpecialCardAi.PithingNeedle}) scores every card the opponent owns in every zone, so it named Oko.
 *
 * <p>The position below is the journal's: the AI with Forest and Swamp; the owner's 40-card deck from the journal header,
 * Botanical Sanctum on the battlefield and the other 39 cards hidden (hand or library: upstream reads both alike). With
 * fairNaming off (the default) the AI still names Oko; ON names only an opponent's card the AI has seen (visible now,
 * visible at an earlier priority decision, or revealed to it); SHADOW decides as off and counts the decision.
 */
public class FairNamingTest extends AITest {

    /** The owner's deck, journal a559930d (seat 1). */
    private static final String[] OWNER_DECK = {
            "Ancestral Recall", "Black Lotus", "Botanical Sanctum", "Brainstorm", "Corpse Dance", "Counterspell",
            "Demonic Tutor", "Echo of Eons", "Force of Will", "Forest", "Glen Elendra Archmage", "Hullbreacher",
            "Island", "Island", "Island", "Island", "Island", "Island", "Island", "Karakas", "Library of Alexandria",
            "Lotus Petal", "Mana Drain", "Mana Leak", "Mana Vault", "Miscalculation", "Mystic Confluence",
            "Oko, Thief of Crowns", "Polluted Delta", "Ponder", "Preordain", "Prismatic Vista", "Remand",
            "Restless Vinestalk", "Snapcaster Mage", "Spellseeker", "Swamp", "Thoughtseize", "Trinket Mage",
            "Vampiric Tutor"};
    private static final String OKO = "Oko, Thief of Crowns";

    private FairNaming.Counters counters;

    /** The journal's T3 position; Oko in {@code okoZone} (Library = hidden, as live). */
    private Game setup(AiFixes.Mode mode, ZoneType okoZone) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();
        lobby.setFairNaming(mode);
        counters = FairNaming.count(lobby, game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Forest", ai);
        addCard("Swamp", ai);
        boolean sanctum = false;
        for (String name : OWNER_DECK) {
            if (!sanctum && name.equals("Botanical Sanctum")) {
                addCard(name, opp);
                sanctum = true;
            } else {
                final Card c = addCardToZone(name, opp, name.equals(OKO) ? okoZone : ZoneType.Library);
                if (name.equals(OKO) && okoZone == ZoneType.Battlefield) {
                    c.addCounterInternal(CounterEnumType.LOYALTY, 4, opp, false, null, null);
                }
            }
        }
        fillLibrary(ai, 10);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** Phyrexian Revoker enters under the AI: "As this creature enters, choose a nonland card name." */
    private String revokerName(Game game) {
        Player ai = game.getPlayers().get(1);
        Card revoker = addCardToZone("Phyrexian Revoker", ai, ZoneType.Hand);
        game.getAction().moveToPlay(revoker, null, null);
        game.getAction().checkStateEffects(true);
        Card onBattlefield = null;
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Phyrexian Revoker")) {
                onBattlefield = c;
            }
        }
        AssertJUnit.assertNotNull("Phyrexian Revoker entered", onBattlefield);
        return onBattlefield.getNamedCard();
    }

    @Test
    public void offNamesOkoFromTheHiddenZones() {
        // The live game: upstream Forge AI (fairNaming off) names the owner's hidden Oko.
        Game game = setup(AiFixes.Mode.OFF, ZoneType.Library);
        AssertJUnit.assertEquals(OKO, revokerName(game));
        AssertJUnit.assertEquals("off counts nothing", 0, counters.fired());
    }

    @Test
    public void offNamesOkoFromTheHand() {
        Game game = setup(AiFixes.Mode.OFF, ZoneType.Hand);
        AssertJUnit.assertEquals(OKO, revokerName(game));
    }

    @Test
    public void onNamesNoCardItHasNotSeen() {
        Game game = setup(AiFixes.Mode.ON, ZoneType.Library);
        final String name = revokerName(game);
        AssertJUnit.assertFalse("ON must not name a card the AI has not seen: " + name,
                Arrays.asList(OWNER_DECK).contains(name));
        AssertJUnit.assertEquals("nothing seen but a land: upstream's own fallback name",
                SpecialCardAi.PithingNeedle.chooseNonBattlefieldName(), name);
        AssertJUnit.assertEquals(1, counters.fired());
        AssertJUnit.assertEquals(1, counters.changed());
    }

    @Test
    public void onDoesNotNameItsOwnCard() {
        // Nothing of the opponent's scores, so upstream's scoring would return the least-bad of the AI's own cards.
        Game game = setup(AiFixes.Mode.ON, ZoneType.Library);
        Player ai = game.getPlayers().get(1);
        addCard("Deathrite Shaman", ai);
        final String name = revokerName(game);
        AssertJUnit.assertFalse(name.equals("Deathrite Shaman") || name.equals("Forest") || name.equals("Swamp"));
    }

    @Test
    public void shadowDecidesAsOffAndCountsTheChange() {
        Game game = setup(AiFixes.Mode.SHADOW, ZoneType.Library);
        AssertJUnit.assertEquals(OKO, revokerName(game));
        AssertJUnit.assertEquals(1, counters.fired());
        AssertJUnit.assertEquals(1, counters.changed());
    }

    @Test
    public void onNamesAVisibleCard() {
        Game game = setup(AiFixes.Mode.ON, ZoneType.Battlefield);
        AssertJUnit.assertEquals("Oko stays on the battlefield", 1, countCardsWithName(game, OKO, ZoneType.Battlefield));
        AssertJUnit.assertEquals(OKO, revokerName(game));
        AssertJUnit.assertEquals("the fair name equals upstream's here", 0, counters.changed());
    }

    @Test
    public void onRemembersARevealedCard() {
        // Thoughtseize-style: the hand is revealed to the AI, then stays hidden.
        Game game = setup(AiFixes.Mode.ON, ZoneType.Hand);
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        ai.getController().reveal(new CardCollection(opp.getCardsIn(ZoneType.Hand)), ZoneType.Hand, opp, "", false);
        AssertJUnit.assertEquals(OKO, revokerName(game));
    }

    @Test
    public void onRemembersACardSeenAtAnEarlierDecision() {
        // Oko was on the battlefield at one of the AI's priority decisions, then returned to its owner's hand.
        Game game = setup(AiFixes.Mode.ON, ZoneType.Battlefield);
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        FairNaming.observe(ai);
        Card oko = null;
        for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(OKO)) {
                oko = c;
            }
        }
        game.getAction().moveToHand(oko, null);
        boolean inHand = false;
        for (Card c : opp.getCardsIn(ZoneType.Hand)) {
            inHand |= c.getName().equals(OKO);
        }
        AssertJUnit.assertTrue("Oko is back in its owner's hand", inHand);
        AssertJUnit.assertEquals(OKO, revokerName(game));
    }

    @Test
    public void offDoesNotRemember() {
        Game game = setup(AiFixes.Mode.OFF, ZoneType.Battlefield);
        Player ai = game.getPlayers().get(1);
        FairNaming.observe(ai);
        AssertJUnit.assertTrue(((PlayerControllerAi) ai.getController()).getFairNamingSeen().isEmpty());
    }

    @Test
    public void namingWithoutAiLogicReadsOnlySeenCards() {
        // Tamiyo, Collector of Tales' +1 has no AILogic: upstream names the first nonland card an opponent owns, in any zone.
        for (AiFixes.Mode mode : new AiFixes.Mode[] {AiFixes.Mode.OFF, AiFixes.Mode.ON}) {
            Game game = setup(mode, ZoneType.Library);
            Player ai = game.getPlayers().get(1);
            Card tamiyo = addCard("Tamiyo, Collector of Tales", ai);
            SpellAbility plus = findSAWithPrefix(tamiyo, "+1");
            AssertJUnit.assertNotNull(plus);
            plus.setActivatingPlayer(ai);
            final String name = ai.getController().chooseCardName(plus, x -> true, "Card.nonLand", "");
            if (mode == AiFixes.Mode.OFF) {
                AssertJUnit.assertTrue("upstream names a hidden card of the owner's: " + name, Arrays.asList(OWNER_DECK).contains(name));
            } else {
                AssertJUnit.assertEquals("nothing nonland seen", "Morphling", name);
            }
        }
    }

    @Test
    public void copiesInheritTheMode() {
        final LobbyPlayerAi from = new LobbyPlayerAi("a", null);
        from.setFairNaming(AiFixes.Mode.ON);
        final LobbyPlayerAi to = new LobbyPlayerAi("b", null);
        AiFixes.inherit(from, to);
        AssertJUnit.assertEquals(AiFixes.Mode.ON, to.getFairNaming());
    }
}
