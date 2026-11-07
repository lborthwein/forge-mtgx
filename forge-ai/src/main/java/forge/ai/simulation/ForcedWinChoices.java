package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;

/**
 * kill-line-1106 (the part of research labeller-v2 this lane uses): a line's recorded discard, as name tokens
 * {@code disc:<names>} (sorted, ";"-joined). Names, not ids: a token chosen in one copy applies in another world; a missing
 * name is a miss, never a guess.
 */
final class ForcedWinChoices {

    static final String DISC = "disc";

    private ForcedWinChoices() {
    }

    static String kindOf(final String tok) {
        final int i = tok.indexOf(':');
        return i < 0 ? tok : tok.substring(0, i);
    }

    static String payload(final String tok) {
        final int i = tok.indexOf(':');
        return i < 0 ? "" : tok.substring(i + 1);
    }

    static List<String> split(final String s) {
        if (s.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(java.util.Arrays.asList(s.split(";", -1)));
    }

    static CardCollection applyDisc(final String tok, final CardCollectionView valid, final int min, final int max) {
        final List<String> want = split(payload(tok));
        if (want.size() < min || want.size() > max) {
            return null;
        }
        final List<Card> left = new ArrayList<>();
        for (Card c : valid) {
            left.add(c);
        }
        left.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        final CardCollection out = new CardCollection();
        for (String n : want) {
            Card hit = null;
            for (Card c : left) {
                if (c.getName().equals(n)) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) {
                return null;
            }
            left.remove(hit);
            out.add(hit);
        }
        return out;
    }

    static final class Cursor {
        final List<String> toks;
        final boolean[] used;
        int hits, misses;

        Cursor(final List<String> toks) {
            this.toks = toks == null ? List.of() : toks;
            this.used = new boolean[this.toks.size()];
        }

        String next(final String kind) {
            for (int i = 0; i < toks.size(); i++) {
                if (!used[i] && kindOf(toks.get(i)).equals(kind)) {
                    used[i] = true;
                    return toks.get(i);
                }
            }
            return null;
        }
    }
}
