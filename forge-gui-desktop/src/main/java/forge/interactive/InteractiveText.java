package forge.interactive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Public object references survive name-based fallback redaction; bare text
 * does not acquire authorization just because another public copy exists. */
final class InteractiveText {
    record CardLabel(int id, String name, boolean visible) {}

    static String sanitize(String text, List<CardLabel> cards) {
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
        for (String name : privateNames) result = result.replace(name, "Face-down card");
        for (int i = 0; i < saved.size(); i++) result = result.replace(prefix + i + "\u0000", saved.get(i));
        return result;
    }
}
