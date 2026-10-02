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
        final List<String> subjects = subjectNames == null ? List.of() : subjectNames.stream()
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
            result = result.replace(name, "Face-down card");
        }
        for (int i = 0; i < saved.size(); i++) result = result.replace(prefix + i + "\u0000", saved.get(i));
        return result;
    }
}
