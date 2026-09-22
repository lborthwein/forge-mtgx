package forge.interactive;

import com.google.common.eventbus.Subscribe;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Thief of Sanity exiles a card face down and grants "you may cast it, and mana of any type
 * can be spent to cast it" (CR 118.7 / the card's own Oracle text).
 *
 * <p>The browser bridge publishes the human seat's controls by enumerating
 * {@code Card.getAllPossibleAbilities} over every card in the game and then keeping only the
 * ones {@link HumanManaAffordability} cannot prove unpayable
 * ({@code InteractiveGuiGame.buildStatefulControls}). Two defects made that publish pass
 * pathological once such a card existed, both reproduced from a live game on 2026-09-22:</p>
 *
 * <ol>
 *   <li>the enumeration MUTATED the card — {@code Spell.getAlternateHost} turns the
 *       last-known-information copy face up — and the resulting
 *       {@code GameEventCardStatsChanged} drove the GUI to republish, which enumerated again:
 *       an unbounded event loop (2.08M transcript entries in 12 minutes);</li>
 *   <li>the affordability filter ignored the may-play option's mana conversion, so a green
 *       pip looked unpayable from a W/U board and the only control that mattered was dropped.</li>
 * </ol>
 */
public class InteractiveThiefExileTest extends AITest {

    /** Counts the engine's card-stats events; a legality query must produce none. */
    private static final class StatsEventCounter {
        private int count;

        @Subscribe
        public void receive(final GameEvent event) {
            if (event instanceof GameEventCardStatsChanged) {
                count++;
            }
        }
    }

    /** A game in which Thief of Sanity has just connected, with a W/U board for its controller. */
    private static final class Fixture {
        private Game game;
        private Player thiefSeat;
        private Card exiled;
    }

    private Fixture connectWithThiefOfSanity() {
        final Fixture fixture = new Fixture();
        fixture.game = initAndCreateGame();
        fixture.thiefSeat = fixture.game.getPlayers().get(1);
        final Player victim = fixture.game.getPlayers().get(0);

        // The reported seat was W/U; Lotus Cobra's green pip is unpayable from it
        // WITHOUT the grant, which is exactly what the affordability filter must honour.
        addCard("Island", fixture.thiefSeat);
        addCard("Plains", fixture.thiefSeat);
        for (int i = 0; i < 5; i++) {
            addCardToZone("Lotus Cobra", victim, ZoneType.Library);
        }

        final Card thief = addCard("Thief of Sanity", fixture.thiefSeat);
        fixture.game.getTriggerHandler().registerActiveTrigger(thief, false);
        fixture.game.getAction().checkStateEffects(true);

        victim.addDamageAfterPrevention(2, thief, null, true, new GameEntityCounterTable());
        fixture.game.getTriggerHandler().collectTriggerForWaiting();
        fixture.game.getTriggerHandler().runWaitingTriggers();
        fixture.game.getStack().addAllTriggeredAbilitiesToStack();
        for (int guard = 0; !fixture.game.getStack().isEmpty() && guard < 50; guard++) {
            fixture.game.getStack().resolveStack();
            fixture.game.getAction().checkStateEffects(true);
            fixture.game.getStack().addAllTriggeredAbilitiesToStack();
        }

        for (Card card : fixture.game.getCardsIn(ZoneType.Exile)) {
            if (card.isFaceDown()) {
                fixture.exiled = card;
            }
        }
        assertNotNull(fixture.exiled, "the trigger must have exiled a card face down");
        assertTrue(fixture.exiled.getView().canBeShownTo(fixture.thiefSeat.getView()),
                "its controller may look at it");
        assertTrue(fixture.exiled.mayPlay(fixture.thiefSeat).stream()
                        .anyMatch(option -> option.isIgnoreManaCostType()),
                "the grant lets mana of any type be spent on it");
        return fixture;
    }

    /**
     * Publishing the human seat's controls must not change the game. Before the fix each
     * enumeration of the face-down exiled card fired two GameEventCardStatsChanged — one from
     * the alternative-cost pass, one from canPlay — and each event made the GUI republish.
     */
    @Test(timeOut = 900000)
    public void enumeratingControlsOverAFaceDownExiledCardFiresNoStatsEvents() {
        final Fixture fixture = connectWithThiefOfSanity();
        final StatsEventCounter counter = new StatsEventCounter();
        fixture.game.subscribeToEvents(counter);

        // Ten publish passes stand in for the bridge's event-driven republish.
        for (int pass = 0; pass < 10; pass++) {
            fixture.game.forEachCardInGame(card -> {
                card.getAllPossibleAbilities(fixture.thiefSeat, true);
                return true;
            });
        }

        assertEquals(counter.count, 0,
                "a priority-pass control enumeration must fire no card-stats events");
    }

    /** The same query, isolated to the exiled card, still reports the castable spell. */
    @Test(timeOut = 900000)
    public void theFaceDownExiledCardStillOffersItsSpellWithoutMutatingTheGame() {
        final Fixture fixture = connectWithThiefOfSanity();
        final StatsEventCounter counter = new StatsEventCounter();
        fixture.game.subscribeToEvents(counter);

        final List<SpellAbility> abilities =
                fixture.exiled.getAllPossibleAbilities(fixture.thiefSeat, true);

        assertEquals(counter.count, 0, "enumerating one exiled card must fire no card-stats events");
        assertTrue(fixture.exiled.isFaceDown(), "the real card stays face down");
        assertEquals(fixture.exiled.getCurrentStateName().name(), "FaceDown",
                "the real card's current state is untouched by the query");
        assertTrue(abilities.stream().anyMatch(SpellAbility::isSpell),
                "the controller may cast the exiled card: " + abilities);
    }

    /**
     * The bridge drops a card whose every ability is proven unaffordable. "Mana of any type
     * can be spent to cast it" makes Lotus Cobra's green pip payable from Island + Plains,
     * so the filter must not prove it unpayable.
     */
    @Test(timeOut = 900000)
    public void theGrantedAnyTypePaymentIsNotProvenUnaffordable() {
        final Fixture fixture = connectWithThiefOfSanity();
        final List<SpellAbility> abilities =
                fixture.exiled.getAllPossibleAbilities(fixture.thiefSeat, true);
        assertTrue(abilities.stream().anyMatch(SpellAbility::isSpell), "precondition: a spell is offered");

        for (SpellAbility ability : abilities) {
            assertEquals(HumanManaAffordability.assess(fixture.thiefSeat, ability),
                    HumanManaAffordability.Assessment.UNKNOWN,
                    "a may-play grant that converts mana cannot be proven unaffordable: " + ability);
        }
        assertTrue(abilities.stream().anyMatch(a -> HumanManaAffordability.mayAfford(fixture.thiefSeat, a)),
                "the bridge keeps the card only if some ability may be afforded");
    }

    /** Guard: an ordinary off-colour spell in hand is still proven unaffordable. */
    @Test(timeOut = 900000)
    public void anOrdinaryOffColourSpellIsStillProvenUnaffordable() {
        final Fixture fixture = connectWithThiefOfSanity();
        final Card ordinary = addCardToZone("Lotus Cobra", fixture.thiefSeat, ZoneType.Hand);
        fixture.game.getAction().checkStateEffects(true);

        final Iterable<SpellAbility> abilities = ordinary.getSpellAbilities();
        boolean sawSpell = false;
        for (SpellAbility ability : abilities) {
            if (!ability.isSpell()) {
                continue;
            }
            sawSpell = true;
            ability.setActivatingPlayer(fixture.thiefSeat);
            assertEquals(HumanManaAffordability.assess(fixture.thiefSeat, ability),
                    HumanManaAffordability.Assessment.PROVEN_UNAFFORDABLE,
                    "no grant, no green source: " + ability);
        }
        assertTrue(sawSpell, "precondition: the card has a spell");
    }
}
