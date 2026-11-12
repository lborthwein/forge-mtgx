package forge.bench.rl;

import java.nio.charset.StandardCharsets;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.CardScorerHiddenReadTest;
import forge.ai.simulation.ForcedWinSolver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * shipA-on-leakfix-1112: the card scorer's hidden-order guard, through {@link ChoiceScorer#enumerate} (the call the
 * seat makes at a card menu). The scorer values an answer by replaying the resolving ability on a copy of the real
 * game. With the plan solver's {@code hideOwnLibrary} (and without {@code hiddenOrderLegacy}) a menu whose replay
 * reads cards of our library the seat does not know must not be scored: the menu is refused as {@code ownHidden} and
 * the policy's own answer stands. A menu whose replay reads nothing hidden (a tutor taking the card the seat picked
 * from a library it is looking through) is still scored.
 *
 * <p>The positions are the ones a live ask poses: the spell on the stack, the stack resolving, the cards the spell
 * has already drawn in hand. Card names appear here only (tests); the guard itself knows none.
 */
public class CardScorerOwnHiddenGuardTest extends forge.ai.AITest {

    static final String[] NAMES = {"Brainstorm", "Demonic Tutor", "Mountain", "Plains", "Island", "Swamp", "Forest",
            "Lightning Bolt", "Shock", "Giant Growth", "Counterspell", "Dark Ritual", "Ponder", "Grizzly Bears"};

    private static CardIndex index() {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append('\t').append(i + 2).append('\n');
        }
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A card scorer as the seat builds it: sequences on (a put-back menu is answered whole), no solver screen. */
    private static ChoiceScorer scorer(final boolean hideOwnLibrary, final boolean legacy) {
        final JsonObject o = new JsonObject();
        o.addProperty("families", "CARDS");
        o.addProperty("seqs", 2);
        o.addProperty("screenNodes", 0);
        final ChoiceScorer.Config cfg = ChoiceScorer.Config.parse(o);
        cfg.solverCfg = ForcedWinSolver.Config.fromProps(k -> "hideOwnLibrary".equals(k) ? String.valueOf(hideOwnLibrary)
                : "hiddenOrderLegacy".equals(k) ? String.valueOf(legacy) : null);
        return new ChoiceScorer(cfg, index(), 1, 77L);
    }

    /** The one-card menu a resolving ability poses over {@code cards}. */
    private static RlCandidates.Menu menu(final Iterable<Card> cards) {
        final RlCandidates.Menu m = new RlCandidates.Menu();
        m.family = RlSchema.F_CARDS;
        m.mode = RlSchema.M_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        for (Card c : cards) {
            final RlCandidates.Cand x = new RlCandidates.Cand();
            x.kind = RlSchema.K_CARD;
            x.host = c;
            x.key = "c:" + c.getId();
            m.cands.add(x);
        }
        return m;
    }

    private static ChoiceScorer.Scored ask(final ChoiceScorer sc, final Game g, final Player me, final RlCandidates.Menu m) {
        final RlKnowledge know = new RlKnowledge(g);
        know.attach();
        return sc.enumerate(g, me, m, new short[] {0}, know, 5L, null);
    }

    /** Brainstorm resolving: three cards drawn, the put-back menu open. The next library cards are all different. */
    private static Game putBackAsk() {
        final Game g = CardScorerHiddenReadTest.cast("Brainstorm;Mountain;Plains",
                "Lightning Bolt;Shock;Giant Growth;Counterspell;Dark Ritual;Ponder;Swamp;Forest;Island;Island;Island;Island;Island;Island",
                "Brainstorm");
        final Player me = g.getPlayers().get(0);
        me.drawCards(3);
        g.getStack().setResolving(true);
        Assert.assertEquals(me.getCardsIn(ZoneType.Hand).size(), 5, "two kept cards and the three drawn");
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).get(0).getName(), "Counterspell", "the real next draw");
        return g;
    }

    @Test
    public void aPutBackMenuWhoseReplayDrawsFromOurLibraryIsRefused() {
        final Game g = putBackAsk();
        final Player me = g.getPlayers().get(0);
        final ChoiceScorer.Scored s = ask(scorer(true, false), g, me, menu(me.getCardsIn(ZoneType.Hand)));
        Assert.assertEquals(s.unsupported, "ownHidden", "the replay draws again from the real top of our library");
        Assert.assertNull(s.chosen, "nothing is chosen: the policy's own answer stands");
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).get(0).getName(), "Counterspell", "the real game is untouched");
        Assert.assertEquals(me.getCardsIn(ZoneType.Hand).size(), 5);
    }

    @Test
    public void theLegacyKeyScoresThatMenuOnTheRealOrder() {
        // the old behaviour, kept behind hiddenOrderLegacy: the same menu is enumerated and its frames are built
        final Game g = putBackAsk();
        final Player me = g.getPlayers().get(0);
        final ChoiceScorer.Scored s = ask(scorer(true, true), g, me, menu(me.getCardsIn(ZoneType.Hand)));
        Assert.assertNull(s.unsupported, "no guard with hiddenOrderLegacy");
        int framed = 0;
        for (ChoiceScorer.Option o : s.options) {
            Assert.assertNotEquals(o.fail, "ownHidden");
            framed += o.frames.isEmpty() ? 0 : 1;
        }
        Assert.assertTrue(framed >= 2, "answers were replayed and framed (" + framed + " of " + s.options.size() + ")");
    }

    @Test
    public void withoutHideOwnLibraryThereIsNoGuard() {
        final Game g = putBackAsk();
        final Player me = g.getPlayers().get(0);
        final ChoiceScorer.Scored s = ask(scorer(false, false), g, me, menu(me.getCardsIn(ZoneType.Hand)));
        Assert.assertNull(s.unsupported, "the guard follows the solver's hideOwnLibrary");
    }

    @Test
    public void aTutorMenuIsStillScored() {
        final Game g = CardScorerHiddenReadTest.cast("Demonic Tutor;Mountain",
                "Counterspell;Dark Ritual;Ponder;Swamp;Forest;Lightning Bolt;Island;Island;Island;Island", "Demonic Tutor");
        final Player me = g.getPlayers().get(0);
        g.getStack().setResolving(true);
        final ChoiceScorer.Scored s = ask(scorer(true, false), g, me, menu(me.getCardsIn(ZoneType.Library)));
        Assert.assertNull(s.unsupported, "taking the picked card out of a library the seat is looking through reads nothing hidden");
        int framed = 0;
        for (ChoiceScorer.Option o : s.options) {
            Assert.assertNull(o.fail, o.label);
            framed += o.frames.isEmpty() ? 0 : 1;
        }
        Assert.assertTrue(framed >= 6, "one framed answer per distinct card (" + framed + ")");
    }
}
