package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * opp-belief-1104 (ARCH-APPENDIX-1104 A2; used only with oppBelief K >= 1): the opponent's own belief worlds. A world u
 * is our world w with every card of ours the opponent cannot see re-dealt: our hand cards not shown to it, and our
 * library cards it does not know at a position. Everything else (its own cards, every public zone, the stack, the cards
 * of ours it sees) stays as in w.
 *
 * <p><b>Canonical pool.</b> The pooled cards are put in id order before the shuffle (seeded per u), then dealt to our
 * hand first and to the unknown library slots after. Ids move with the cards, so the pool, and so u, is the same
 * whatever the real places of our hidden cards are: u depends only on what the opponent knows and the seed (the leak
 * test). The opponent is assumed to know our deck list (the cards, not their places); a deck-model belief is a
 * follow-up.
 */
public final class OppBeliefWorlds {

    private OppBeliefWorlds() {
    }

    /**
     * Re-deal, in the copy, {@code me}'s cards that {@code liveOpp} cannot see. {@code live}: the live game (whose card
     * views decide what the opponent is shown; a copy card is looked up there by id). {@code oppKnownLib}: our library
     * card ids the opponent knows at a position (InfoStates, or null: only the leading run shown to it).
     */
    public static void redeal(final Game live, final Player liveOpp, final Player me, final Set<Integer> oppKnownLib,
            final Random rng) {
        final PlayerView ov = liveOpp.getView();
        final List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final List<Card> pool = new ArrayList<>();
        final boolean[] handKept = new boolean[hand.size()];
        for (int i = 0; i < hand.size(); i++) {
            handKept[i] = shown(live, hand.get(i), ov);
            if (!handKept[i]) pool.add(hand.get(i));
        }
        final boolean[] libKept = new boolean[lib.size()];
        boolean leading = true;
        for (int i = 0; i < lib.size(); i++) {
            final Card c = lib.get(i);
            final boolean sh = shown(live, c, ov);
            leading &= sh;
            libKept[i] = oppKnownLib != null ? oppKnownLib.contains(c.getId()) || leading : leading;
            if (!libKept[i]) pool.add(c);
        }
        if (pool.isEmpty()) return;
        pool.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        Collections.shuffle(pool, rng);
        int p = 0;
        final List<Card> nh = new ArrayList<>(hand.size());
        for (int i = 0; i < hand.size(); i++) nh.add(handKept[i] ? hand.get(i) : pool.get(p++));
        final List<Card> nl = new ArrayList<>(lib.size());
        for (int i = 0; i < lib.size(); i++) nl.add(libKept[i] ? lib.get(i) : pool.get(p++));
        me.getZone(ZoneType.Hand).setCards(nh);
        me.getZone(ZoneType.Library).setCards(nl);
    }

    private static boolean shown(final Game live, final Card c, final PlayerView ov) {
        final Card lc = live.findById(c.getId());
        return lc != null && lc.getView().canBeShownTo(ov);
    }

    /** The seed of opponent world {@code u} of our world seed {@code ws}. */
    public static long seed(final long ws, final int u) {
        return LookaheadSearch.mix(ws, 0x4F42000000L + u);
    }

    /** Tests: the world's hidden layout (hand then library ids), for the leak test. */
    public static String layout(final Player me) {
        final StringBuilder sb = new StringBuilder("H");
        for (Card c : me.getCardsIn(ZoneType.Hand)) sb.append(c.getId()).append(',');
        sb.append("L");
        for (Card c : me.getCardsIn(ZoneType.Library)) sb.append(c.getId()).append(',');
        return sb.toString();
    }
}
