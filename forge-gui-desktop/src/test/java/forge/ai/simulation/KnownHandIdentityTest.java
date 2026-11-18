package forge.ai.simulation;

import java.util.List;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.event.GameEventPlayerPriority;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * attack-holdback-1118 review #248 round 2: known-in-hand ({@link InfoStates#trackHands}) keeps a card only through
 * continuous, observable identity. Each S1 repro (a private put-back, a private tutor of a known top, a randomized
 * revealed run) and each case of the same rules (a same-name public exit, a face-down exile, a reveal then a shuffle) is
 * a test that fails on 04fe29b7275; the controls (a public discard, a draw of the known top, a revealed tutor, a
 * face-up exile, a reveal straight to the hand) hold on both.
 */
public class KnownHandIdentityTest extends SimulationTest {

    private static void priority(final Game g, final Player p) {
        g.fireEvent(new GameEventPlayerPriority(PlayerView.get(p), PhaseType.MAIN1, PlayerView.get(p)));
    }

    /**
     * Plays and resolves {@code sa} with a priority while each item is on the stack, as a real game gives one: a spell cast
     * from the hand is a pending exit until then (the hand reads as unknown), which would make a test pass vacuously.
     */
    private static void playAndResolve(final Game g, final Player who, final SpellAbility sa) {
        KnownLibTest.playAndResolve(g, who, sa, top -> priority(g, who));
    }

    /** S1 1 (the reviewer's repro): a private put-back could have been the known card to this observer. */
    @Test
    public void aPrivatePutBackForgetsTheHand() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        fillLibrary(opp, 8);
        final Card unknown = addCardToZone("Island", opp, ZoneType.Hand);
        final InfoStates s = InfoStates.attach(g).trackHands();
        Card angel = addCard("Serra Angel", opp);
        angel = g.getAction().moveToHand(angel, null);
        assertTrue(s.knowsInHand(me, angel));
        g.getAction().moveToLibrary(unknown, 0, null); // the Brainstorm put-back, privately
        assertTrue(angel.isInZone(ZoneType.Hand), "the Angel is still there");
        assertFalse(s.knowsInHand(me, angel), "cannot tell which card was put back privately");
        assertTrue(s.knownHand(me, opp).isEmpty());
    }

    /** S1 1 through a resolved Brainstorm: the caster keeps the known Angel and puts back two other cards. */
    @Test
    public void aResolvedBrainstormForgetsTheHand() {
        final Game g = KnownLibTest.game("Brainstorm;Island", "Island", "Swamp;Swamp;Swamp;Swamp;Swamp;Swamp", 20);
        final Player caster = KnownLibTest.me(g), observer = KnownLibTest.opp(g);
        caster.dangerouslySetController(new PlayerControllerAi(g, caster, caster.getController().getLobbyPlayer()) {
            @Override
            public Card chooseSingleCardForZoneChange(final ZoneType dest, final List<ZoneType> origin, final SpellAbility sa,
                    final CardCollection choices, final DelayedReveal unused, final String prompt, final boolean optional,
                    final Player decider) {
                return choices.stream().filter(c -> !c.getName().equals("Serra Angel")).findFirst().orElse(choices.get(0));
            }

            @Override
            public List<Card> chooseCardsForZoneChange(final ZoneType dest, final List<ZoneType> origin, final SpellAbility sa,
                    final CardCollection choices, final int min, final int max, final DelayedReveal unused,
                    final String prompt, final Player decider) {
                return choices.stream().filter(c -> !c.getName().equals("Serra Angel")).limit(max).toList();
            }
        });
        final InfoStates s = InfoStates.attach(g).trackHands();
        Card angel = addCard("Serra Angel", caster);
        angel = g.getAction().moveToHand(angel, null);
        assertTrue(s.knowsInHand(observer, angel), "seen entering from the battlefield");
        final Card bs = caster.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Brainstorm"))
                .findFirst().orElseThrow();
        final int angelId = angel.getId();
        final boolean[] knownOnStack = {false};
        KnownLibTest.playAndResolve(g, caster, bs.getFirstSpellAbility(), top -> {
            priority(g, caster);
            knownOnStack[0] |= s.knowsInHand(observer, g.findById(angelId));
        });
        assertTrue(knownOnStack[0], "casting Brainstorm (shown on the stack) kept the Angel known");
        final Card now = g.findById(angelId);
        assertTrue(now.isInZone(ZoneType.Hand), "the caster kept the Angel");
        assertFalse(s.knowsInHand(observer, now), "two cards went back privately: either could have been the Angel");
    }

    /** S1 2 (the reviewer's repro): knowing where a card sat in the library does not reveal a private search choice. */
    @Test
    public void aPrivateTutorOfAKnownTopIsNotKnown() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        final Card selected = addCardToZone("Serra Angel", opp, ZoneType.Library);
        fillLibrary(opp, 8);
        final Card tutor = addCardToZone("Demonic Tutor", opp, ZoneType.Hand);
        addCard("Swamp", opp);
        addCard("Swamp", opp);
        final InfoStates s = InfoStates.attach(g).trackHands();
        s.pin(me, opp, List.of(selected)); // known place (an earlier public look); no reveal in this resolution
        opp.dangerouslySetController(new PlayerControllerAi(g, opp, opp.getController().getLobbyPlayer()) {
            @Override
            public Card chooseSingleCardForZoneChange(final ZoneType dest, final List<ZoneType> origin, final SpellAbility sa,
                    final CardCollection choices, final DelayedReveal unused, final String prompt, final boolean optional,
                    final Player decider) {
                return selected;
            }
        });
        Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Graveyard);
        bolt = g.getAction().moveToHand(bolt, null);
        playAndResolve(g, opp, tutor.getFirstSpellAbility());
        final Card inHand = g.findById(selected.getId());
        assertTrue(inHand.isInZone(ZoneType.Hand));
        assertTrue(s.knowsInHand(me, bolt), "the hand is still read (no pending exit): the returned Bolt stays known");
        assertFalse(s.knowsInHand(me, inHand), "a known library place does not reveal a private search choice");
    }

    /** S1 3 (the reviewer's repro): five revealed cards put back in a random order, then one drawn. */
    @Test
    public void aRandomizedRevealedRunDoesNotRevealTheDraw() {
        final Game g = KnownLibTest.game("", "Inscribed Tablet;Island",
                "Lightning Bolt;Shock;Serra Angel;Giant Growth;Counterspell", 20);
        final Player caster = KnownLibTest.me(g), observer = KnownLibTest.opp(g);
        final InfoStates s = InfoStates.attach(g).trackHands();
        final Card tablet = caster.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Inscribed Tablet")).findFirst().orElseThrow();
        final SpellAbility activation = tablet.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == forge.game.ability.ApiType.Dig).findFirst().orElseThrow();
        playAndResolve(g, caster, activation);
        assertEquals(caster.getCardsIn(ZoneType.Hand).size(), 1, "no land among the five: one card drawn");
        final Card drawn = caster.getCardsIn(ZoneType.Hand).get(0);
        assertFalse(s.knowsInHand(observer, drawn), "the revealed five were randomized before one was drawn");
    }

    /**
     * Rule 1, a public exit: the observer sees "a Serra Angel left" from a hand holding a known and an unknown Angel; it
     * cannot tell which, so the known one is forgotten. A card of another name stays known.
     */
    @Test
    public void aSameNamePublicExitForgetsTheKnownTwin() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        fillLibrary(opp, 8);
        final Card unknownAngel = addCardToZone("Serra Angel", opp, ZoneType.Hand);
        final InfoStates s = InfoStates.attach(g).trackHands();
        Card angel = addCard("Serra Angel", opp);
        angel = g.getAction().moveToHand(angel, null);
        Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Graveyard);
        bolt = g.getAction().moveToHand(bolt, null);
        assertEquals(s.knownHand(me, opp).size(), 2);
        g.getAction().moveToGraveyard(unknownAngel, null); // a discard of the other Angel, face up
        assertTrue(angel.isInZone(ZoneType.Hand), "the returned Angel is still there");
        assertFalse(s.knowsInHand(me, angel), "an Angel left: it may have been the known one");
        assertTrue(s.knowsInHand(me, bolt), "a Serra Angel leaving says nothing about the Bolt");
    }

    /** Control: a public discard of an unknown card of another name keeps the known card. */
    @Test
    public void aPublicDiscardKeepsTheRest() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        fillLibrary(opp, 8);
        final Card island = addCardToZone("Island", opp, ZoneType.Hand);
        final InfoStates s = InfoStates.attach(g).trackHands();
        Card angel = addCard("Serra Angel", opp);
        angel = g.getAction().moveToHand(angel, null);
        g.getAction().moveToGraveyard(island, null);
        assertTrue(s.knowsInHand(me, angel), "an Island shown leaving was not the Angel");
    }

    /**
     * Rule 1: an exile that turns face down after the move (Forge's ExileFaceDown order) is not observable: until the next
     * priority the hand reads as unknown, then it is forgotten. A face-up exile is observable after that priority.
     */
    @Test
    public void aFaceDownExileForgetsTheHandAtPriority() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        fillLibrary(opp, 8);
        final Card down = addCardToZone("Island", opp, ZoneType.Hand);
        final Card up = addCardToZone("Swamp", opp, ZoneType.Hand);
        final InfoStates s = InfoStates.attach(g).trackHands();
        Card angel = addCard("Serra Angel", opp);
        angel = g.getAction().moveToHand(angel, null);
        // face up: unsure until the next priority, then observable
        g.getAction().exile(up, null, null);
        assertFalse(s.knowsInHand(me, angel), "a card left and may yet turn face down");
        priority(g, opp);
        assertTrue(s.knowsInHand(me, angel), "the Swamp is face up in exile: the Angel stayed");
        // face down after the move (as ChangeZoneEffect's ExileFaceDown does)
        final Card exiled = g.getAction().exile(down, null, null);
        exiled.turnFaceDown(true);
        priority(g, opp);
        assertTrue(angel.isInZone(ZoneType.Hand));
        assertFalse(s.knowsInHand(me, angel), "a face-down exile could have been the Angel");
    }

    /** Rule 3: a library card revealed, then its library shuffled, then put into the hand: the identity was lost. */
    @Test
    public void aRevealThenAShuffleIsNotKnown() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        fillLibrary(opp, 8);
        final Card a = addCardToZone("Serra Angel", opp, ZoneType.Library);
        final InfoStates s = InfoStates.attach(g).trackHands();
        g.getAction().reveal(new CardCollection(a), opp);
        opp.shuffle(null);
        final Card inHand = g.getAction().moveToHand(a, null);
        assertFalse(s.knowsInHand(me, inHand), "the shuffle broke the revealed card's identity");
    }

    /** Control (rule 2): a revealed card moved straight to the hand is known. */
    @Test
    public void aRevealStraightToTheHandIsKnown() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        fillLibrary(opp, 8);
        final Card a = addCardToZone("Serra Angel", opp, ZoneType.Library);
        final InfoStates s = InfoStates.attach(g).trackHands();
        g.getAction().reveal(new CardCollection(a), opp);
        final Card inHand = g.getAction().moveToHand(a, null);
        assertTrue(s.knowsInHand(me, inHand), "revealed as it moved");
    }

    /** Control (rule 2): a draw of the top card the observer knew is known; a draw of an unknown top is not. */
    @Test
    public void aDrawOfTheKnownTopIsKnown() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        fillLibrary(opp, 8);
        final InfoStates s = InfoStates.attach(g).trackHands();
        final Card top = opp.getCardsIn(ZoneType.Library).get(0);
        s.pin(me, opp, List.of(top));
        opp.drawCard();
        final Card drawn = g.findById(top.getId());
        assertTrue(drawn.isInZone(ZoneType.Hand));
        assertTrue(s.knowsInHand(me, drawn), "drawn from the top the observer knew");
        opp.drawCard();
        final Card second = opp.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getId() != top.getId())
                .findFirst().orElseThrow();
        assertFalse(s.knowsInHand(me, second), "an unknown top drawn");
    }

    /** Control (rule 2): a tutor that reveals its card (Eladamri's Call) shows it. */
    @Test
    public void aRevealedTutorIsKnown() {
        final Game g = KnownLibTest.game("Eladamri's Call", "Forest;Plains",
                "Serra Angel;Island;Island;Island;Island;Island", 20);
        final Player caster = KnownLibTest.me(g), observer = KnownLibTest.opp(g);
        final InfoStates s = InfoStates.attach(g).trackHands();
        final Card call = caster.getCardsIn(ZoneType.Hand).get(0);
        playAndResolve(g, caster, call.getFirstSpellAbility());
        final Card angel = caster.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Serra Angel"))
                .findFirst().orElseThrow();
        assertTrue(s.knowsInHand(observer, angel), "searched, then revealed in the hand");
    }
}
