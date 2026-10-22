package forge.interactive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Public object references survive name-based fallback redaction; bare text
 * does not acquire authorization just because another public copy exists. */
final class InteractiveText {
    record CardLabel(int id, String name, boolean visible) {}

    static String sanitize(String text, List<CardLabel> cards) {
        return sanitize(text, cards, Set.of());
    }

    /**
     * As {@link #sanitize(String, List)}, but a bare occurrence of a {@code subjectName} is kept.
     *
     * <p>A subject is the card a prompt is about. The caller passes only the name of a subject the
     * viewer may identify, never a hidden one. Forge writes that card's own name bare into its
     * own text: CARDNAME in "Lightning Bolt (4) - Lightning Bolt deals 3 damage to any target.",
     * or the stack description "Squee, Goblin Nabob - Creature 1 / 1" of a spell being paid for.
     * Without the exemption, a hidden card of the same name (a second copy in a library or a hand)
     * turned that self-reference into "Face-down card". That misnamed the viewer's own face-up
     * spell, and the redaction itself told the viewer that a hidden card of that name exists.
     * A hidden name longer than a subject name is still redacted first, so a subject never
     * shields part of a longer hidden name. Every other bare hidden name is redacted as before.</p>
     */
    static String sanitize(String text, List<CardLabel> cards, Collection<String> subjectNames) {
        return sanitize(text, cards, subjectNames, null);
    }

    /**
     * As {@link #sanitize(String, List, Collection)}, given the printed rules text of the card the text is about
     * ({@code publicText}; the caller passes it only for a card the viewer may identify, else null).
     *
     * <p>Lane facedown-label-1021 (owner report 2026-10-21T21-17-01, a review row "Cast Lórien Revealed ·
     * (Face-down cardcycling {1})"). A bare hidden name is redacted only where it stands as a word, never inside a
     * longer one: an Island in a library no longer cuts "Island" out of "Islandcycling" or "Swampwalk". A hidden name
     * that is a word of {@code publicText} is that card's own public wording ("Search your library for an Island
     * card") and is kept like a subject name. Both redactions also told the viewer that a hidden card of that name
     * exists. A reference by id ("Island (37)") to a hidden card is still always redacted.</p>
     */
    static String sanitize(String text, List<CardLabel> cards, Collection<String> subjectNames, String publicText) {
        String result = text == null ? "" : text;
        String prefix = "\u0000MTGX_PUBLIC_";
        while (result.contains(prefix)) prefix += "_";
        final List<String> saved = new ArrayList<>();
        for (CardLabel card : cards) {
            if (card.name() == null || card.name().isBlank()) continue;
            final String reference = card.name() + " (" + card.id() + ")";
            if (!result.contains(reference)) continue;
            final String token = prefix + saved.size() + "\u0000";
            saved.add(card.visible() ? reference : "Face-down card (" + card.id() + ")");
            result = result.replace(reference, token);
        }
        // Longest first prevents a shorter hidden name leaving a recognizable
        // remainder of another hidden name (e.g. an extended card name).
        final List<String> privateNames = cards.stream().filter(c -> !c.visible())
                .map(CardLabel::name).filter(n -> n != null && !n.isBlank()).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        final List<String> shields = new ArrayList<>();
        if (subjectNames != null) shields.addAll(subjectNames);
        for (String name : privateNames) {
            if (indexOfWord(publicText, name, 0) >= 0) shields.add(name);
        }
        final List<String> subjects = shields.stream()
                .filter(n -> n != null && !n.isBlank()).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        int nextSubject = 0;
        for (String name : privateNames) {
            // Shield each subject name just before the first hidden name no longer than it.
            while (nextSubject < subjects.size() && subjects.get(nextSubject).length() >= name.length()) {
                final String subject = subjects.get(nextSubject++);
                if (!result.contains(subject)) continue;
                final String token = prefix + saved.size() + "\u0000";
                saved.add(subject);
                result = result.replace(subject, token);
            }
            result = replaceWord(result, name, "Face-down card");
        }
        for (int i = 0; i < saved.size(); i++) result = result.replace(prefix + i + "\u0000", saved.get(i));
        return result;
    }

    /** {@code text} with every occurrence of {@code name} that stands as a word ({@link #indexOfWord}) replaced. */
    static String replaceWord(final String text, final String name, final String replacement) {
        int at = indexOfWord(text, name, 0);
        if (at < 0) return text;
        final StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (at >= 0) {
            out.append(text, from, at).append(replacement);
            from = at + name.length();
            at = indexOfWord(text, name, from);
        }
        return out.append(text, from, text.length()).toString();
    }

    /**
     * The first index at or after {@code from} where {@code name} occurs in {@code text} with no letter or digit
     * glued to it on either side ("Island" in "an Island card" or "Island's", not in "Islandcycling" or "Islands"),
     * or -1. A name that begins or ends with punctuation needs no boundary on that side.
     */
    static int indexOfWord(final String text, final String name, final int from) {
        if (text == null || name == null || name.isEmpty()) return -1;
        int at = text.indexOf(name, from);
        while (at >= 0) {
            final int end = at + name.length();
            final boolean before = at == 0 || !wordChar(name.charAt(0)) || !wordChar(text.charAt(at - 1));
            final boolean after = end == text.length() || !wordChar(name.charAt(name.length() - 1))
                    || !wordChar(text.charAt(end));
            if (before && after) return at;
            at = text.indexOf(name, at + 1);
        }
        return -1;
    }

    private static boolean wordChar(final char c) {
        return Character.isLetterOrDigit(c);
    }
}
