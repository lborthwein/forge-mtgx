package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * choice-search-1109: the line-existence leaf of a choice. An answer is applied on a copy of the resolving ability (the
 * live game never moves), the copy settles to the next priority, and the planner screen asks whether a line exists from
 * there (this turn, then by our next turn). A tutor that takes Doomsday with BBB, a draw and Thassa's Oracle in the
 * library has a line; the same tutor taking Troll has none. A discard of the seat's own choice (Bazaar of Baghdad) is
 * answered on the copy with the forced cards, so "keep Doomsday" is an answer the search can see. No card rules: the
 * names only build the positions.
 */
public class ChoiceLineTest extends AITest {

    private static final String LIB = "Doomsday;Troll of Khazad-dûm;Thassa's Oracle;Island;Island;Island;Island;Island;"
            + "Island;Island;Island;Island;Island;Island;Island";

    /** The frame side for the test: no value frame, the planner screen over the copy (our known top: none). */
    static final class ScreenFrames implements ChoiceApply.Frames {
        final List<String> hands = new ArrayList<>();
        final List<String> graves = new ArrayList<>();

        @Override
        public Object onCopy(final Game copy, final Player me) {
            return null;
        }

        @Override
        public byte[] capture(final Object ctx, final Game g, final Player me) {
            hands.add(names(me, ZoneType.Hand));
            graves.add(names(me, ZoneType.Graveyard));
            return new byte[0];
        }

        @Override
        public ForcedWinSolver.Screen screen(final Object ctx, final Game g, final Player me) {
            final ForcedWinSolver.Config cfg = ForcedWinSolver.Config.fromProps(k -> "planExpandCap".equals(k) ? "true" : null);
            return ForcedWinSolver.withReader(me, cfg, ForcedWinSolver.newSharedReader()).screen(g,
                    Collections.<Integer>emptyList(), 4000, 60_000L);
        }
    }

    static String names(final Player p, final ZoneType z) {
        final List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(z)) {
            out.add(c.getName());
        }
        Collections.sort(out);
        return String.join(";", out);
    }

    private static Card find(final Player p, final ZoneType z, final String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no " + name + " in " + z + ": " + names(p, z));
    }

    private static Game game(final String hand, final String battlefield, final String lib) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(hand, battlefield, 20, "",
                "Island", 20, "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib));
    }

    /** Play {@code sa} of {@code me} (Forge AI pays) and resolve the stack once. */
    private static void playAndResolve(final Game g, final Player me, final SpellAbility sa) {
        sa.setActivatingPlayer(me);
        Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(sa), "cast " + sa);
        MyRandom.setRandom(new Random(42));
        g.getStack().resolveStack();
    }

    @Test
    public void aTutorForDoomsdayHasALineAndATutorForTrollHasNone() {
        final Game g = game("Demonic Tutor;Ancestral Recall", "Swamp;Swamp;Swamp;Swamp;Swamp;Island;Island;Island", LIB);
        final Player me = g.getPlayers().get(0);
        final Map<String, ForcedWinSolver.Screen> screens = new HashMap<>();
        final Map<String, String> hands = new HashMap<>();
        final ScreenFrames fr = new ScreenFrames();
        me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
                    final SpellAbility sa, final CardCollection fetchList, final forge.game.player.DelayedReveal delayedReveal,
                    final String selectPrompt, final boolean isOptional, final Player decider) {
                Assert.assertNull(ChoiceApply.unsupported(g, null));
                for (String n : new String[] {"Doomsday", "Troll of Khazad-dûm"}) {
                    final Card c = find(me, ZoneType.Library, n);
                    final ChoiceApply.Result r = ChoiceApply.applyPicks(g, me, Collections.singletonList(c.getId()), 7L,
                            200, 1, fr);
                    Assert.assertNull(r.fail, n + ": " + r.fail);
                    screens.put(n, r.screen);
                    hands.put(n, fr.hands.get(fr.hands.size() - 1));
                }
                // the live answer: Troll (the live game must not have moved: Doomsday is still in the library)
                Assert.assertNotNull(find(me, ZoneType.Library, "Doomsday"));
                return find(me, ZoneType.Library, "Troll of Khazad-dûm");
            }
        });
        playAndResolve(g, me, find(me, ZoneType.Hand, "Demonic Tutor").getFirstSpellAbility());
        Assert.assertTrue(hands.get("Doomsday").contains("Doomsday"), hands.toString());
        Assert.assertTrue(hands.get("Troll of Khazad-dûm").contains("Troll"), hands.toString());
        final ForcedWinSolver.Screen dd = screens.get("Doomsday"), tr = screens.get("Troll of Khazad-dûm");
        Assert.assertNotNull(dd);
        Assert.assertNotNull(tr);
        Assert.assertTrue(dd.lines > 0, "Doomsday: lines " + dd.lines + " best " + dd.best + " failed " + dd.failed);
        Assert.assertEquals(tr.lines, 0, "Troll: best " + tr.best);
        // the live game took the live answer only
        Assert.assertTrue(names(me, ZoneType.Hand).contains("Troll"), names(me, ZoneType.Hand));
        Assert.assertFalse(names(me, ZoneType.Hand).contains("Doomsday"), names(me, ZoneType.Hand));
    }

    @Test
    public void aDiscardOfTheSeatsChoiceIsForcedOnTheCopy() {
        final Game g = game("Doomsday;Troll of Khazad-dûm;Thassa's Oracle", "Bazaar of Baghdad;Island",
                ForcedWinSolverTest.OURLIB);
        final Player me = g.getPlayers().get(0);
        final ScreenFrames fr = new ScreenFrames();
        final List<String> after = new ArrayList<>();
        me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa,
                    final CardCollection validCards, final int min, final int max, final CardCollectionView visible) {
                Assert.assertEquals(max, 3);
                Assert.assertNull(ChoiceApply.unsupported(g, null));
                final List<Integer> islands = new ArrayList<>();
                for (Card c : validCards) {
                    if (c.getName().equals("Island")) {
                        islands.add(c.getId());
                    }
                }
                Assert.assertEquals(islands.size(), 2, "the two drawn Islands");
                // the discard is Bazaar's sub-ability: its draw already happened, so the copy resolves from the discard on
                final int chain = ChoiceApply.chainOf(g, sa);
                Assert.assertEquals(chain, 1);
                // keep Doomsday: discard Troll and the two Islands
                final List<Integer> keepDd = new ArrayList<>(islands);
                keepDd.add(0, find(me, ZoneType.Hand, "Troll of Khazad-dûm").getId());
                final ChoiceApply.Result a = ChoiceApply.applyPicks(g, me, keepDd, null, 9L, 200, 1, fr, chain);
                Assert.assertNull(a.fail, String.valueOf(a.fail));
                Assert.assertEquals(a.tailAsks, 0);
                after.add(fr.hands.get(fr.hands.size() - 1));
                // keep Troll: discard Doomsday and the two Islands
                final List<Integer> keepTr = new ArrayList<>(islands);
                keepTr.add(0, find(me, ZoneType.Hand, "Doomsday").getId());
                final ChoiceApply.Result b = ChoiceApply.applyPicks(g, me, keepTr, null, 9L, 200, 1, fr, chain);
                Assert.assertNull(b.fail, String.valueOf(b.fail));
                after.add(fr.hands.get(fr.hands.size() - 1));
                // a pick that is not in the menu is a miss, never a silent Forge answer
                final ChoiceApply.Result c = ChoiceApply.applyPicks(g, me, Collections.singletonList(-77), null, 9L, 200, 1,
                        fr, chain);
                Assert.assertNotNull(c.fail);
                // the live answer: the last three cards of the menu (the live hand still holds all of them here)
                final CardCollection out = new CardCollection();
                out.add(find(me, ZoneType.Hand, "Doomsday"));
                for (Card x : validCards) {
                    if (x.getName().equals("Island")) {
                        out.add(x);
                    }
                }
                return out;
            }
        });
        final Card bazaar = find(me, ZoneType.Battlefield, "Bazaar of Baghdad");
        SpellAbility draw = null;
        for (SpellAbility sa : bazaar.getSpellAbilities()) {
            if (sa.getApi() == forge.game.ability.ApiType.Draw) {
                draw = sa;
            }
        }
        Assert.assertNotNull(draw, "Bazaar's activated ability");
        playAndResolve(g, me, draw);
        Assert.assertEquals(after.size(), 2);
        Assert.assertEquals(after.get(0), "Doomsday;Thassa's Oracle", "keep Doomsday");
        Assert.assertEquals(after.get(1), "Thassa's Oracle;Troll of Khazad-dûm", "keep Troll");
        Assert.assertEquals(names(me, ZoneType.Hand), "Thassa's Oracle;Troll of Khazad-dûm", "the live answer only");
    }
}
