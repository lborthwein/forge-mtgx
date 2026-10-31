package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;

/**
 * Labeller v2 items (a) and (b) (PREDECLARATION-labeller-v2.md §1): a line's own choices inside one step's window, as
 * name tokens. The engine search (MoveAi) enumerates them (choose-a-card's distinct answers, capped) and forces them by
 * index; a verification play-out (LineAi) answers each recorded one as recorded. A token is
 * {@code kind:payload}: {@code pick:<name>} ("-" = find nothing), {@code scry:<top>|<away>} and
 * {@code surv:<top>|<away>} (names top first, ";"-joined, "?" = a card the planner did not know), {@code disc:<names>}
 * (sorted), {@code order:<names>} (the returned list's order), {@code shuf:y|n}. Names, not ids: a token chosen in one
 * copy is applied in another world, where it maps to the same names (a missing name is a miss, never a guess, except
 * "?" and unmatched scry/order slots, which take the remaining cards in their offered order).
 */
final class ForcedWinChoices {

    static final String PICK = "pick", SCRY = "scry", SURV = "surv", DISC = "disc", ORDER = "order", SHUF = "shuf";
    /** solver-cap12 round 3 (R3-4 b, engineCostChoice): a cost part's cards (sacrifice, exile, discard, tap-type). */
    static final String COST = "cost";
    /** R3-4 (b), engineCostChoice: our optional trigger's "you may" (trig:y / trig:n, by the trigger's host name). */
    static final String TRIG = "trig";

    static String trigTok(final String host, final boolean yes) {
        return TRIG + ":" + host + "=" + (yes ? "y" : "n");
    }

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

    static String names(final Iterable<Card> cs) {
        final StringBuilder sb = new StringBuilder();
        for (Card c : cs) {
            sb.append(sb.length() == 0 ? "" : ";").append(c.getName());
        }
        return sb.toString();
    }

    static List<String> split(final String s) {
        if (s.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(java.util.Arrays.asList(s.split(";", -1)));
    }

    // ------------------------------------------------------------------ tokens

    static String pickTok(final Card c) {
        return PICK + ":" + (c == null ? "-" : c.getName());
    }

    static String arrTok(final String kind, final Iterable<Card> top, final Iterable<Card> away) {
        return kind + ":" + names(top) + "|" + names(away);
    }

    static String discTok(final Iterable<Card> cs) {
        final List<String> n = new ArrayList<>();
        for (Card c : cs) {
            n.add(c.getName());
        }
        Collections.sort(n);
        return DISC + ":" + String.join(";", n);
    }

    /** R3-4 (b): a cost part's cards, by their sorted names (the copies are fungible). */
    static String costTok(final Iterable<Card> cs) {
        final List<String> n = new ArrayList<>();
        for (Card c : cs) {
            n.add(c.getName());
        }
        Collections.sort(n);
        return COST + ":" + String.join(";", n);
    }

    /** R3-4 (b): a cost part of exactly {@code n} cards: each distinct multiset of names (Forge AI's first). */
    static List<String> costs(final CardCollectionView valid, final int n, final CardCollectionView def, final int cap) {
        final List<String> out = new ArrayList<>();
        out.add(costTok(def));
        final List<Card> cards = new ArrayList<>();
        for (Card c : valid) {
            cards.add(c);
        }
        cards.sort((a, b) -> a.getName().equals(b.getName()) ? Integer.compare(a.getId(), b.getId())
                : a.getName().compareTo(b.getName()));
        ChooseACard.combos(cards, n, cap * 8, list -> add(out, costTok(list), cap));
        return out;
    }

    /** R3-4 (b): a cost token's cards among the valid ones (exactly its names), or null. */
    static CardCollection applyCost(final String tok, final CardCollectionView valid, final int n) {
        return applyDisc(tok, valid, n, n);
    }

    static String orderTok(final Iterable<Card> cs) {
        return ORDER + ":" + names(cs);
    }

    static String shufTok(final boolean yes) {
        return SHUF + ":" + (yes ? "y" : "n");
    }

    // ------------------------------------------------------------------ enumerators (the default answer first)

    /** A pick from our own zone: each distinct name (sorted), plus "find nothing" when optional. */
    static List<String> picks(final CardCollectionView fetch, final boolean optional, final Card def, final int cap) {
        final List<String> out = new ArrayList<>();
        out.add(pickTok(def));
        final java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (Card c : fetch) {
            names.add(c.getName());
        }
        for (String n : names) {
            add(out, PICK + ":" + n, cap);
        }
        if (optional) {
            add(out, PICK + ":-", cap);
        }
        return out;
    }

    /** Scry / surveil: each kept/away split and order of the kept cards, distinct by names. */
    static List<String> arrangements(final String kind, final CardCollectionView topN,
            final ImmutablePair<CardCollection, CardCollection> def, final int cap) {
        final List<String> out = new ArrayList<>();
        out.add(arrTok(kind, def.getLeft(), def.getRight()));
        final List<Card> cards = new ArrayList<>();
        for (Card c : topN) {
            cards.add(c);
        }
        final int m = Math.min(cards.size(), 10);
        for (int mask = 0; mask < (1 << m) && out.size() < cap; mask++) {
            final List<Card> away = new ArrayList<>(), keep = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                ((mask & (1 << i)) != 0 ? away : keep).add(cards.get(i));
            }
            final List<List<Card>> perms = new ArrayList<>();
            ChooseACard.permutations(keep, perms, cap);
            for (List<Card> p : perms) {
                if (out.size() >= cap) {
                    break;
                }
                add(out, arrTok(kind, p, away), cap);
            }
        }
        return out;
    }

    /** A discard of exactly {@code n}: each distinct multiset of names. */
    static List<String> discards(final CardCollectionView valid, final int n, final CardCollectionView def, final int cap) {
        final List<String> out = new ArrayList<>();
        out.add(discTok(def));
        final List<Card> cards = new ArrayList<>();
        for (Card c : valid) {
            cards.add(c);
        }
        cards.sort((a, b) -> a.getName().equals(b.getName()) ? Integer.compare(a.getId(), b.getId())
                : a.getName().compareTo(b.getName()));
        ChooseACard.combos(cards, n, cap * 8, list -> add(out, discTok(list), cap));
        return out;
    }

    /** An order of our cards going to our library: each distinct permutation by names. */
    static List<String> orders(final CardCollectionView cards, final CardCollectionView def, final int cap) {
        final List<String> out = new ArrayList<>();
        out.add(orderTok(def));
        final List<Card> list = new ArrayList<>();
        for (Card c : cards) {
            list.add(c);
        }
        final List<List<Card>> perms = new ArrayList<>();
        ChooseACard.permutations(list, perms, cap * 4);
        for (List<Card> p : perms) {
            add(out, orderTok(p), cap);
        }
        return out;
    }

    static List<String> shuffles(final boolean def) {
        return new ArrayList<>(List.of(shufTok(def), shufTok(!def)));
    }

    private static void add(final List<String> out, final String tok, final int cap) {
        if (out.size() < cap && !out.contains(tok)) {
            out.add(tok);
        }
    }

    // ------------------------------------------------------------------ appliers (null = not applicable)

    /** The card for a pick token: {@code {card}}, {@code {null}} for "find nothing", or null when the name is not offered. */
    static Card[] applyPick(final String tok, final CardCollectionView fetch) {
        final String n = payload(tok);
        if ("-".equals(n)) {
            return new Card[] {null};
        }
        Card best = null;
        for (Card c : fetch) {
            if (c.getName().equals(n) && (best == null || c.getId() < best.getId())) {
                best = c;
            }
        }
        return best == null ? null : new Card[] {best};
    }

    /** A scry / surveil token applied to the cards offered: named cards by name, the rest ("?") in offered order. */
    static ImmutablePair<CardCollection, CardCollection> applyArr(final String tok, final CardCollectionView topN) {
        final String p = payload(tok);
        final int bar = p.indexOf('|');
        if (bar < 0) {
            return null;
        }
        final List<String> top = split(p.substring(0, bar)), away = split(p.substring(bar + 1));
        if (top.size() + away.size() != topN.size()) {
            return null;
        }
        final List<Card> left = new ArrayList<>();
        for (Card c : topN) {
            left.add(c);
        }
        final Card[] t = new Card[top.size()], w = new Card[away.size()];
        claim(top, t, left);
        claim(away, w, left);
        fill(t, left);
        fill(w, left);
        return ImmutablePair.of(new CardCollection(java.util.Arrays.asList(t)), new CardCollection(java.util.Arrays.asList(w)));
    }

    private static void claim(final List<String> want, final Card[] into, final List<Card> left) {
        for (int i = 0; i < want.size(); i++) {
            for (Card c : left) {
                if (c.getName().equals(want.get(i))) {
                    into[i] = c;
                    left.remove(c);
                    break;
                }
            }
        }
    }

    private static void fill(final Card[] into, final List<Card> left) {
        for (int i = 0; i < into.length; i++) {
            if (into[i] == null) {
                into[i] = left.remove(0);
            }
        }
    }

    /** A discard token: exactly its names among the valid cards, or null. */
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

    /** An order token: the cards in its name order (unmatched names take the rest in offered order), or null. */
    static CardCollectionView applyOrder(final String tok, final CardCollectionView cards) {
        final List<String> want = split(payload(tok));
        if (want.size() != cards.size()) {
            return null;
        }
        final List<Card> left = new ArrayList<>();
        for (Card c : cards) {
            left.add(c);
        }
        final Card[] o = new Card[want.size()];
        claim(want, o, left);
        fill(o, left);
        return new CardCollection(java.util.Arrays.asList(o));
    }

    static boolean applyShuf(final String tok) {
        return "y".equals(payload(tok));
    }

    // ------------------------------------------------------------------ a step's recorded answers

    /** A step's recorded tokens, consumed in order, each by the first ask of its kind (null tokens = nothing recorded). */
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

    /**
     * The engine's choice points in one move (MoveAi): point k is forced by {@code forced[k]} (a kind mismatch or an
     * unknown name is a miss: the variant is a duplicate of another); every free point records Forge AI's answer and the
     * enumerated alternatives (its token first).
     */
    static final class Recorder {
        final List<String> forced;
        final int cap;
        final List<String> played = new ArrayList<>();
        /** Per free point: its index and its candidates (Forge AI's token first). */
        final List<Integer> freeAt = new ArrayList<>();
        final List<List<String>> candidates = new ArrayList<>();
        boolean miss;
        /** R3-4 (b): only the COST kind records (engineCostChoice without cacPicks); false = every kind (as before). */
        boolean costOnly;

        Recorder(final List<String> forced, final int cap) {
            this.forced = forced == null ? List.of() : forced;
            this.cap = cap;
        }

        /** The forced token at the next point (its kind checked), or null when the point is free. */
        String at(final String kind) {
            final int k = played.size();
            if (k < forced.size()) {
                final String t = forced.get(k);
                if (!kindOf(t).equals(kind)) {
                    miss = true;
                    return null;
                }
                return t;
            }
            return null;
        }

        boolean forcedPoint() {
            return played.size() < forced.size();
        }

        void played(final String tok) {
            played.add(tok);
        }

        void free(final List<String> cands, final String tok) {
            freeAt.add(played.size());
            candidates.add(cands);
            played.add(tok);
        }
    }

    /** Distinct untapped-remainder key (labeller v2 (e)): the sorted names of the untapped sources left. */
    static String remainderKey(final Iterable<Card> untapped) {
        final List<String> n = new ArrayList<>();
        for (Card c : untapped) {
            n.add(c.getName());
        }
        Collections.sort(n);
        return String.join(";", n);
    }
}
