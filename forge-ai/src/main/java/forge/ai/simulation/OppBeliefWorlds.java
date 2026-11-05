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
 *
 * <p><b>Conditioning at the reveal</b> ({@link #materialize}). u is drawn at the root, before we act. When our seat in u
 * plays a card of the pool that u dealt elsewhere (our library), the card is swapped into our hand for the first pooled
 * card there, at that moment: the world is conditioned on what the opponent observes when it observes it. Before the
 * card is played, u's hand holds it only as often as the opponent's belief says; once it is played, every u agrees
 * with what the opponent saw.
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
        final Set<Integer> ids = new java.util.HashSet<>();
        for (Card c : pool) ids.add(c.getId());
        synchronized (POOLED) {
            POOLED.put(me.getGame(), ids);
        }
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

    /** The pooled card ids of each opponent world (its copy game), for {@link #materialize}. */
    private static final java.util.Map<Game, Set<Integer>> POOLED = new java.util.WeakHashMap<>();

    /**
     * In an opponent world (a copy that {@link #redeal} re-dealt; a no-op in any other game): our card {@code hostId},
     * pooled and now in our library, is swapped into our hand for the first pooled card there (hand order).
     */
    public static void materialize(final Game g, final Player me, final int hostId) {
        final Set<Integer> ids;
        synchronized (POOLED) {
            ids = POOLED.get(g);
        }
        if (ids == null || !ids.contains(hostId)) return;
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        int at = -1;
        for (int i = 0; i < lib.size() && at < 0; i++) if (lib.get(i).getId() == hostId) at = i;
        if (at < 0) return;
        final List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        for (int i = 0; i < hand.size(); i++) {
            if (ids.contains(hand.get(i).getId())) {
                final Card out = hand.get(i);
                hand.set(i, lib.get(at));
                lib.set(at, out);
                me.getZone(ZoneType.Hand).setCards(hand);
                me.getZone(ZoneType.Library).setCards(lib);
                return;
            }
        }
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
