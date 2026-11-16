package forge.interactive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * Lane mullstart-1116: a puzzle that starts at the browser seat's opening mulligan ({@code puzzle.mulligan: true}).
 * The seat's controller answers Forge's own London mulligan (here a scripted human controller: mulligan once, put the
 * first offered card on the bottom, keep); the AI seat keeps the position's hand; the game stays at the position's
 * turn 1 and phase, with Forge's turn-1 rule (the player on the play skips the draw) intact on the play and on the
 * draw. A puzzle without the field never asks a mulligan question (InteractivePuzzleSetupTest covers the rest).
 */
public class InteractivePuzzleMulliganTest extends AITest {

    private static final String SESSION = "puzzle-mulligan-test";

    /** One forty: the dealt seven (hand) and 33 more (library). Test data only. */
    private static String state(final boolean humanOnThePlay) {
        final List<String> hand = List.of("Island", "Island", "Swamp", "Dark Ritual", "Ponder", "Brainstorm", "Swamp");
        final List<String> lib = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            lib.add(i % 3 == 0 ? "Swamp" : i % 3 == 1 ? "Island" : "Ponder");
        }
        final List<String> aiHand = List.of("Plains", "Plains", "Plains", "Savannah Lions", "Savannah Lions", "Plains", "Plains");
        final List<String> aiLib = new ArrayList<>(Collections.nCopies(33, "Plains"));
        return String.join("\n",
                "turn=1",
                "activeplayer=" + (humanOnThePlay ? "human" : "ai"),
                "activephase=MAIN1",
                "humanlife=20", "ailife=20",
                "humanhand=" + String.join(";", hand),
                "humanlibrary=" + String.join(";", lib),
                "humanbattlefield=", "humangraveyard=", "humanexile=",
                "aihand=" + String.join(";", aiHand),
                "ailibrary=" + String.join(";", aiLib),
                "aibattlefield=", "aigraveyard=", "aiexile=");
    }

    /** A browser-seat controller that mulligans {@code mulls} times, then keeps; it bottoms the first offered card(s). */
    static final class Scripted extends PlayerControllerHuman {
        int asked;
        final int mulls;
        final List<String> bottomed = new ArrayList<>();
        final List<Integer> handSizesAsked = new ArrayList<>();

        Scripted(final Game game, final Player p, final int mulls) {
            super(game, p, p.getLobbyPlayer());
            this.mulls = mulls;
        }

        @Override
        public boolean mulliganKeepHand(final Player firstPlayer, final int cardsToReturn) {
            handSizesAsked.add(getPlayer().getCardsIn(ZoneType.Hand).size());
            return asked++ >= mulls;
        }

        @Override
        public boolean defersLondonMulliganTuckUntilKeep() {
            return false;
        }

        @Override
        public CardCollectionView tuckCardsViaMulligan(final CardCollectionView hand, final int cardsToReturn) {
            final CardCollection out = new CardCollection();
            for (Card c : hand) {
                if (out.size() < cardsToReturn) {
                    out.add(c);
                    bottomed.add(c.getName());
                }
            }
            return out;
        }
    }

    private static InteractivePuzzle.Spec spec(final String state, final Boolean mulligan) throws Exception {
        final JsonObject puzzle = new JsonObject();
        puzzle.addProperty("state", state);
        puzzle.addProperty("goal", "win");
        puzzle.addProperty("turns", 21);
        if (mulligan != null) {
            puzzle.addProperty("mulligan", mulligan);
        }
        final JsonObject config = JsonParser.parseString("{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":"
                + "\"config\",\"session\":\"" + SESSION + "\",\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\"}")
                .getAsJsonObject();
        config.add("puzzle", puzzle);
        return InteractivePuzzle.read(config);
    }

    private static boolean drawSkipped(final Game game) throws Exception {
        final Method m = game.getPhaseHandler().getClass().getDeclaredMethod("isSkippingPhase", PhaseType.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(game.getPhaseHandler(), PhaseType.DRAW);
    }

    private void runMulligan(final boolean humanOnThePlay) throws Exception {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);
        final Scripted seat = new Scripted(game, human, 1);
        human.dangerouslySetController(seat);
        final String st = state(humanOnThePlay);
        final InteractivePuzzle.Spec spec = spec(st, true);
        assertTrue(spec.mulligan());
        final List<String> lines = InteractivePuzzle.lines(spec, 0);
        InteractivePuzzle.checkCards(lines);
        InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game).run();

        assertEquals(seat.asked, 2, "asked twice: mulligan, then keep");
        assertEquals(seat.handSizesAsked, List.of(7, 7), "the dealt seven, then a fresh seven (London)");
        assertEquals(seat.bottomed.size(), 1, "one card to the bottom after one mulligan");
        assertEquals(human.getCardsIn(ZoneType.Hand).size(), 6, "keeps six");
        assertEquals(human.getCardsIn(ZoneType.Library).size(), 34, "the forty minus the six");
        final List<Card> lib = new ArrayList<>(human.getCardsIn(ZoneType.Library));
        assertEquals(lib.get(lib.size() - 1).getName(), seat.bottomed.get(0), "the chosen card is on the bottom");
        assertEquals(human.getNumDrawnThisTurn(), 0, "mulligan draws are not the turn's draws");
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 7, "the AI seat keeps the position's hand");
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 33);

        // turn 1, the position's phase and active player; Forge's turn-1 rule skips the draw of whoever is on the play
        assertEquals(game.getPhaseHandler().getTurn(), 1);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertSame(game.getPhaseHandler().getPlayerTurn(), humanOnThePlay ? human : ai);
        assertTrue(drawSkipped(game), "turn 1 of a two-player game skips the draw step");
        assertEquals(game.getAge(), forge.game.GameStage.Play, "back in the play stage after the mulligan");
    }

    @Test(timeOut = 600000)
    public void mulliganOnThePlay() throws Exception {
        runMulligan(true);
    }

    @Test(timeOut = 600000)
    public void mulliganOnTheDraw() throws Exception {
        runMulligan(false);
    }

    @Test(timeOut = 600000)
    public void withoutTheFieldNoMulliganIsAsked() throws Exception {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Scripted seat = new Scripted(game, human, 1);
        human.dangerouslySetController(seat);
        final String st = state(true);
        final InteractivePuzzle.Spec spec = spec(st, null);
        assertFalse(spec.mulligan());
        final List<String> lines = InteractivePuzzle.lines(spec, 0);
        InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game).run();
        assertEquals(seat.asked, 0, "no mulligan question without the field");
        assertEquals(human.getCardsIn(ZoneType.Hand).size(), 7);
        assertEquals(human.getCardsIn(ZoneType.Library).size(), 33);
    }

    @Test
    public void refusedOutsideAnOpeningHand() throws Exception {
        final String later = state(true).replace("turn=1", "turn=3");
        assertNotNull(refusal(() -> spec(later, true)), "a mulligan start is a turn-1 position");
        final String board = state(true).replace("humanbattlefield=", "humanbattlefield=Island");
        assertNotNull(refusal(() -> spec(board, true)), "nothing of the seat's on the battlefield");
        assertNull(refusal(() -> spec(state(true), false)), "mulligan false is the old start");
    }

    private interface Call {
        void run() throws Exception;
    }

    private static String refusal(final Call c) {
        try {
            c.run();
            return null;
        } catch (InteractiveProtocol.ProtocolException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "unexpected " + e;
        }
    }
}
