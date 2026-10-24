package forge.game;

import com.google.common.eventbus.Subscribe;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.card.CardStateName;
import forge.card.ColorSet;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Lane gamestate-setup-1024 (owner reports 2026-10-23T23-41-50, 2026-10-24T00-16-53 and 00-21-25): a GameState
 * record is a position that already exists, so applying it must not replay how its permanents entered. Each
 * battlefield card used to be put into its controller's hand and moved onto the battlefield. That move ran every
 * ReplacementType.Moved effect: a shockland asked to pay 2 life, a clone asked what to copy, a choose-a-type land
 * asked for a type, and a Containment Priest installed earlier exiled the creatures after it.
 *
 * <p>This position holds one card of each kind. After the apply, no seat has been asked anything, no replacement
 * effect has been logged, no card has moved out of a hand, and every card is as the record says: life, tapped,
 * counters, chosen type, damage, attachments, face-down, sickness and Room doors.</p>
 */
public class GameStateBattlefieldInstallTest extends AITest {

    /** Seat 0 is "human", seat 1 is "ai". Both are AI controllers here; the recorder below sees their decisions. */
    static final String POSITION = String.join("\n",
            "turn=6", "activeplayer=human", "activephase=MAIN1",
            "humanlife=9", "ailife=13", "humanlandsplayed=1",
            "humanhand=Island;Swamp",
            "humanlibrary=Island;Island;Island;Island;Island",
            "humangraveyard=", "humanexile=",
            "humanbattlefield=Containment Priest|Id:1;Grizzly Bears|Id:2|Damage:1;Bonesplitter|AttachedTo:2;"
                    + "Overgrown Tomb|Tapped;Watery Grave;Blackcleave Cliffs;Choked Estuary;Xander's Lounge;"
                    + "Gemstone Mine|Counters:MINING=1;Cavern of Souls|ChosenType:Elf;"
                    + "Sakashima of a Thousand Faces|SummonSick;Exalted Angel|FaceDown;"
                    + "Bottomless Pool // Locker Room;Pacifism|AttachedTo:3",
            "aihand=", "ailibrary=Plains;Plains;Plains;Plains;Plains", "aigraveyard=", "aiexile=",
            "aibattlefield=Hallowed Fountain;Savannah Lions|Id:3;Plains|Tapped;Steam Vents|NoETBTrigs");

    static final List<String> HUMAN_BATTLEFIELD = List.of("Containment Priest", "Grizzly Bears", "Bonesplitter",
            "Overgrown Tomb", "Watery Grave", "Blackcleave Cliffs", "Choked Estuary", "Xander's Lounge",
            "Gemstone Mine", "Cavern of Souls", "Sakashima of a Thousand Faces", "Exalted Angel",
            "Bottomless Pool // Locker Room", "Pacifism");
    static final List<String> AI_BATTLEFIELD = List.of("Hallowed Fountain", "Savannah Lions", "Plains", "Steam Vents");

    /** Every decision a seat is asked, as "seat: method". Each answer is still the AI's own. */
    static final class Recorder extends PlayerControllerAi {
        final List<String> asked;

        Recorder(final Game game, final Player p, final List<String> asked) {
            super(game, p, p.getLobbyPlayer());
            this.asked = asked;
        }

        private void note(final String what) {
            asked.add(getPlayer().getName() + ": " + what);
        }

        @Override
        public ReplacementEffect chooseSingleReplacementEffect(final List<ReplacementEffect> possibleReplacers) {
            note("chooseSingleReplacementEffect " + possibleReplacers);
            return super.chooseSingleReplacementEffect(possibleReplacers);
        }

        @Override
        public boolean confirmReplacementEffect(final ReplacementEffect replacementEffect, final SpellAbility effectSA,
                                                final GameEntity affected, final String question) {
            note("confirmReplacementEffect " + question);
            return super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
        }

        @Override
        public boolean payCostToPreventEffect(final Cost cost, final SpellAbility sa, final boolean alreadyPaid,
                                              final FCollectionView<Player> allPayers) {
            note("payCostToPreventEffect " + cost);
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }

        @Override
        public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message,
                                     final List<String> options, final Card cardToShow,
                                     final Map<String, Object> params) {
            note("confirmAction " + message);
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }

        @Override
        public <T extends GameEntity> T chooseSingleEntityForEffect(final FCollectionView<T> optionList,
                final DelayedReveal delayedReveal, final SpellAbility sa, final String title, final boolean isOptional,
                final Player relatedPlayer, final Map<String, Object> params) {
            note("chooseSingleEntityForEffect " + title);
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer,
                    params);
        }

        @Override
        public String chooseSomeType(final String kindOfType, final SpellAbility sa, final Collection<String> validTypes,
                                     final boolean isOptional) {
            note("chooseSomeType " + kindOfType);
            return super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
        }

        @Override
        public byte chooseColor(final String message, final SpellAbility sa, final ColorSet colors) {
            note("chooseColor " + message);
            return super.chooseColor(message, sa, colors);
        }
    }

    /** Every card the apply moved, as "name: from -> to". */
    public static final class Moves {
        final List<String> all = new ArrayList<>();

        @Subscribe
        public void moved(final GameEventCardChangeZone e) {
            all.add((e.card() == null ? "?" : e.card().getName()) + ": "
                    + (e.from() == null ? "none" : e.from().zoneType()) + " -> "
                    + (e.to() == null ? "none" : e.to().zoneType()));
        }
    }

    private Game game;
    private final List<String> asked = new ArrayList<>();
    private final Moves moves = new Moves();

    private Game applied(final String position) {
        asked.clear(); // TestNG runs every method on one instance
        moves.all.clear();
        game = initAndCreateGame();
        for (Player p : game.getPlayers()) {
            p.dangerouslySetController(new Recorder(game, p, asked));
        }
        game.subscribeToEvents(moves);
        final GameState state = new GameState();
        state.parse(Arrays.asList(position.split("\\R")));
        state.applyGameOnThread(game);
        return game;
    }

    /** The card's database name: a face-down card or an empty Room has no name of its own. */
    static String printed(final Card c) {
        return c.getPaperCard().getName();
    }

    private static Card named(final Player p, final String name) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (printed(c).equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static List<String> names(final Player p, final ZoneType zone) {
        final List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(zone)) {
            out.add(printed(c));
        }
        return out;
    }

    @Test
    public void theRecordIsInstalledWithoutReplayingAnyEntry() {
        applied(POSITION);
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        assertTrue(asked.isEmpty(), "no seat is asked anything while the record is installed:\n"
                + String.join("\n", asked));
        final List<String> replaced = new ArrayList<>();
        for (GameLogEntry e : game.getGameLog().getLogEntriesExact(GameLogEntryType.EFFECT_REPLACED)) {
            replaced.add(e.message());
        }
        assertTrue(replaced.isEmpty(), "no replacement effect applies while the record is installed:\n"
                + String.join("\n", replaced));
        for (String m : moves.all) {
            assertFalse(m.contains(": Hand ->"), "no card is moved out of a hand: " + moves.all);
        }

        assertEquals(human.getLife(), 9, "no shockland's 2 life is paid");
        assertEquals(ai.getLife(), 13);
        assertEquals(names(human, ZoneType.Battlefield), HUMAN_BATTLEFIELD,
                "Containment Priest exiles nothing that the record installs after it");
        assertEquals(names(ai, ZoneType.Battlefield), AI_BATTLEFIELD);
        assertEquals(names(human, ZoneType.Hand), List.of("Island", "Swamp"));
        assertEquals(human.getCardsIn(ZoneType.Exile).size() + ai.getCardsIn(ZoneType.Exile).size(), 0);
        assertEquals(human.getCardsIn(ZoneType.Graveyard).size() + ai.getCardsIn(ZoneType.Graveyard).size(), 0);

        for (Card c : human.getCardsIn(ZoneType.Battlefield)) {
            assertEquals(c.isTapped(), printed(c).equals("Overgrown Tomb"), c + " is tapped as the record says");
        }
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            assertEquals(c.isTapped(), printed(c).equals("Plains"), c + " is tapped as the record says");
        }

        final Card mine = named(human, "Gemstone Mine");
        assertEquals(mine.getCounters(CounterEnumType.MINING), 1, "the recorded counter, not 3");
        assertEquals(mine.getCounters().size(), 1);
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (c != mine) {
                    assertTrue(c.getCounters().isEmpty(), c + " has no counters");
                }
            }
        }

        assertEquals(named(human, "Cavern of Souls").getChosenType(), "Elf");
        final Card sakashima = named(human, "Sakashima of a Thousand Faces");
        assertNotNull(sakashima, "Sakashima stays itself");
        assertFalse(sakashima.isCloned(), "Sakashima copies nothing");
        assertEquals(sakashima.getNetPower(), 3);
        assertTrue(sakashima.hasSickness(), "|SummonSick");

        final Card bears = named(human, "Grizzly Bears");
        assertFalse(bears.hasSickness());
        assertEquals(bears.getDamage(), 1);
        assertEquals(named(human, "Bonesplitter").getEntityAttachedTo(), bears);
        assertEquals(named(human, "Pacifism").getEntityAttachedTo(), named(ai, "Savannah Lions"));

        assertTrue(named(human, "Exalted Angel").isFaceDown(), "|FaceDown");
        assertEquals(named(human, "Bottomless Pool // Locker Room").getCurrentStateName(), CardStateName.EmptyRoom,
                "a Room with no unlocked door is an empty room");
    }

    /** CR 613.7d: each permanent takes a timestamp as it enters, in the record's order, as the move from hand gave. */
    @Test
    public void eachInstalledPermanentHasItsOwnTimestampInRecordOrder() {
        applied(POSITION);
        long last = 0;
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                assertTrue(c.getGameTimestamp() > last, c + " timestamp " + c.getGameTimestamp() + " after " + last);
                last = c.getGameTimestamp();
            }
        }
    }

    /** A Room with an unlocked door keeps that door: the record's UnlockedRoom, as before. */
    @Test
    public void aRoomKeepsItsRecordedDoor() {
        applied(POSITION.replace("Bottomless Pool // Locker Room;", "Bottomless Pool // Locker Room|UnlockedRoom:LeftSplit;"));
        assertEquals(named(game.getPlayers().get(0), "Bottomless Pool // Locker Room").getCurrentStateName(),
                CardStateName.LeftSplit);
        assertTrue(asked.isEmpty(), String.join("\n", asked));
    }
}
