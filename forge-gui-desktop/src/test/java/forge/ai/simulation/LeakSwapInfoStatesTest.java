package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-leakswap-1115: the swap test re-deals the UNKNOWN part of a library in place, with no game event. InfoStates
 * takes such a change for an unexplained one and forgets the library for every observer (checkExplained), so the
 * re-dealt search ran without the seat's legitimate position knowledge: the "true arrangement vs any other" split the
 * criterion-B rerun found. {@link InfoStates#leakResnap} records the re-deal as explained; the known cards (which the
 * re-deal never moves) stay known.
 */
public class LeakSwapInfoStatesTest extends forge.ai.AITest {

    /** Brainstorm leaves two known put-backs on top; the remaining (unknown) library cards are returned. */
    private static Game brainstormed(final InfoStates[] out) {
        final Game g = KnownLibTest.game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        final Player me = KnownLibTest.me(g);
        me.dangerouslySetController(new KnownLibTest.Ordered(g, me, null, false));
        out[0] = InfoStates.attach(g);
        KnownLibTest.playAndResolve(g, me, ChooseACardTest.find(me, ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        Assert.assertEquals(out[0].known(me, me).size(), 2, "the two put-backs are known");
        return g;
    }

    /** The unknown cards below the known top, reversed (an in-place re-deal that moves no known card). */
    private static List<Card> redealt(final Player me, final int knownTop) {
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final List<Card> rest = new ArrayList<>(lib.subList(knownTop, lib.size()));
        Collections.reverse(rest);
        final List<Card> out = new ArrayList<>(lib.subList(0, knownTop));
        out.addAll(rest);
        Assert.assertNotEquals(out, lib, "the re-deal changes the order");
        return out;
    }

    @Test
    public void aQuietRedealIsForgottenAsUnexplained() {
        final InfoStates[] s = new InfoStates[1];
        final Game g = brainstormed(s);
        final Player me = KnownLibTest.me(g);
        final int silent0 = s[0].silent;
        me.getZone(ZoneType.Library).setCardsQuiet(redealt(me, 2));
        Assert.assertTrue(s[0].known(me, me).isEmpty(), "the mechanism: an unexplained reorder forgets the library");
        Assert.assertEquals(s[0].silent, silent0 + 1);
    }

    @Test
    public void aResnappedRedealKeepsTheKnownCards() {
        final InfoStates[] s = new InfoStates[1];
        final Game g = brainstormed(s);
        final Player me = KnownLibTest.me(g);
        final List<Card> orig = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final java.util.Set<Integer> known0 = s[0].known(me, me);
        final int silent0 = s[0].silent;
        me.getZone(ZoneType.Library).setCardsQuiet(redealt(me, 2));
        s[0].leakResnap(me);
        Assert.assertEquals(s[0].known(me, me), known0, "the known top survives the re-deal");
        me.getZone(ZoneType.Library).setCardsQuiet(orig);
        s[0].leakResnap(me);
        Assert.assertEquals(s[0].known(me, me), known0, "and the restore");
        Assert.assertEquals(s[0].silent, silent0, "no change was taken for an unexplained one");
    }
}
