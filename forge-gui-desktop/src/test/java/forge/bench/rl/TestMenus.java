package forge.bench.rl;

import forge.game.card.Card;

/** sdcc-scorerleak-1115 (tests only): menus a test outside this package builds, as a resolving ability poses them. */
public final class TestMenus {
    private TestMenus() {
    }

    /** The one-card CARDS menu a resolving ability poses over {@code cards} (a tutor's pick). */
    public static RlCandidates.Menu singleCards(final Iterable<Card> cards) {
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
}
