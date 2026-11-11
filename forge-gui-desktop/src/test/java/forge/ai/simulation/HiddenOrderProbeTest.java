package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * live-leak-1111 (ported from sdcc-leak-1111): a sandbox probe must not read the real order of the seat's library (nor the opponent's hidden hand)
 * when the solver hides it. The probe of a draw spell on a copy of the real game drew the real top cards. With
 * {@link ForcedWinProbe#HIDE} (a reader of a {@code hideOwnLibrary} solver without {@code hiddenOrderLegacy}) the
 * sandbox is scrubbed first, and what it draws is a function of the card ids and the seed, not of the real order.
 */
public class HiddenOrderProbeTest extends forge.ai.AITest {

    static final String FRAME = "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\n"
            + "humanlandsplayed=0\nailandsplayed=0\naihand=Forest;Forest;Forest\nailibrary=" + ForcedWinSolverTest.LIB20
            + "\naigraveyard=\naiexile=\naibattlefield=Forest\nhumanhand=Divination\nhumanbattlefield=Island;Island;Island\n"
            + "humangraveyard=\nhumanexile=\nhumanlibrary=" + ForcedWinSolverTest.OURLIB + ";" + ForcedWinSolverTest.OURLIB + "\n";

    /** The ids the probe of Divination moved from our library to our hand, in order. */
    private static List<Integer> drawn(final Game g, final Player me, final boolean hide, final long seed) {
        final Card div = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Divination");
        final boolean prev = ForcedWinProbe.HIDE.get();
        ForcedWinProbe.HIDE.set(hide);
        try {
            final ForcedWinProbe.Obs o = ForcedWinProbe.run(g, me, div.getId(), null, (gg, m, c) -> {
                SpellAbility sa = null;
                for (SpellAbility x : c.getAllPossibleAbilities(m, true)) {
                    sa = x;
                }
                return sa != null && ForcedWinProbe.play(gg, m, sa);
            }, seed, new ForcedWinProbe.Stats());
            final List<Integer> out = new ArrayList<>();
            for (String t : o.trace) {
                final String[] w = t.split(" ");
                if (w.length >= 4 && "MOVE".equals(w[0]) && "Library@me".equals(w[2]) && "Hand@me".equals(w[3])) {
                    out.add(Integer.parseInt(w[1]));
                }
            }
            Assert.assertEquals(out.size(), 2, "the probe drew two cards: " + o.trace + " " + o.error);
            return out;
        } finally {
            ForcedWinProbe.HIDE.set(prev);
        }
    }

    private static List<Integer> top2(final Player me) {
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        return List.of(lib.get(0).getId(), lib.get(1).getId());
    }

    @Test
    public void theOldProbeDrawsTheRealTop() {
        final Game g = ForcedWinSolverTest.game("Default", "Default", FRAME);
        final Player me = g.getPlayers().get(0);
        Assert.assertEquals(drawn(g, me, false, 5L), top2(me), "legacy: the sandbox is the real game's copy");
    }

    @Test
    public void theHiddenProbeDoesNotFollowTheRealOrder() {
        final Game g = ForcedWinSolverTest.game("Default", "Default", FRAME);
        final Player me = g.getPlayers().get(0);
        final List<Card> lib0 = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final List<Integer> first = drawn(g, me, true, 5L);
        int followed = 0;
        for (int k = 0; k < 6; k++) {
            // the same cards (same ids) in another real order: the probe must draw what it drew before
            final List<Card> lib = new ArrayList<>(lib0);
            Collections.shuffle(lib, new Random(100 + k));
            me.getZone(ZoneType.Library).setCards(lib);
            Assert.assertEquals(drawn(g, me, true, 5L), first, "reordering " + k + ": the draw depends on the real order");
            followed += drawn(g, me, true, 5L).equals(top2(me)) ? 1 : 0;
        }
        Assert.assertTrue(followed <= 1, "the probe drew the real top two in " + followed + " of 6 reorderings");
        Assert.assertNotEquals(drawn(g, me, true, 6L), first, "another seed, another sandbox order");
    }

    @Test
    public void theHiddenProbeDealsTheOpposingHandAgain() {
        final Game g = ForcedWinSolverTest.game("Default", "Default", FRAME);
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        final List<Integer> real = new ArrayList<>();
        for (Card c : opp.getCardsIn(ZoneType.Hand)) {
            real.add(c.getId());
        }
        final boolean prev = ForcedWinProbe.HIDE.get();
        ForcedWinProbe.HIDE.set(true);
        try {
            int same = 0;
            for (long seed = 1; seed <= 6; seed++) {
                final GameCopier copier = new GameCopier(g, true);
                final Game c = copier.makeCopy();
                final Player cm = (Player) copier.find(me);
                ForcedWinProbe.scrub(g, me, c, cm, seed);
                final List<Integer> hand = new ArrayList<>();
                for (Card x : c.getPlayer(opp.getId()).getCardsIn(ZoneType.Hand)) {
                    hand.add(x.getId());
                }
                Assert.assertEquals(hand.size(), real.size(), "their hand keeps its size");
                Assert.assertEquals(c.getPlayer(opp.getId()).getCardsIn(ZoneType.Library).size(),
                        opp.getCardsIn(ZoneType.Library).size(), "their library keeps its size");
                same += new java.util.HashSet<>(hand).equals(new java.util.HashSet<>(real)) ? 1 : 0;
            }
            Assert.assertTrue(same <= 1, "the sandbox kept their real hand in " + same + " of 6 seeds");
        } finally {
            ForcedWinProbe.HIDE.set(prev);
        }
    }

    @Test
    public void theKeysDefault() {
        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(off.hideHidden(), "no hideOwnLibrary: nothing changes");
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(k -> "hideOwnLibrary".equals(k) ? "true" : null);
        Assert.assertTrue(on.hideHidden(), "hideOwnLibrary: the fix is on");
        final ForcedWinSolver.Config legacy = ForcedWinSolver.Config.fromProps(
                k -> "hideOwnLibrary".equals(k) || "hiddenOrderLegacy".equals(k) ? "true" : null);
        Assert.assertFalse(legacy.hideHidden(), "hiddenOrderLegacy: the old behaviour");
    }
}
