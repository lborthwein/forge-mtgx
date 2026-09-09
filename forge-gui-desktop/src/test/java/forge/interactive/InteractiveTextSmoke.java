package forge.interactive;

import java.util.List;

public final class InteractiveTextSmoke {
    private static int checks;
    private static void check(String source, String expected, List<InteractiveText.CardLabel> labels) {
        String actual = InteractiveText.sanitize(source, labels);
        if (!expected.equals(actual)) throw new AssertionError(source + " -> " + actual);
        checks++;
    }
    public static void main(String[] args) {
        var labels = List.of(new InteractiveText.CardLabel(1, "Figure of Destiny", true),
                new InteractiveText.CardLabel(2, "Figure of Destiny", false),
                new InteractiveText.CardLabel(3, "Secret Card", false));
        check("Figure of Destiny (1) — Pay {R/W}", "Figure of Destiny (1) — Pay {R/W}", labels);
        check("Figure of Destiny (2)", "Face-down card (2)", labels);
        check("Figure of Destiny", "Face-down card", labels);
        check("Figure of Destiny (1) targets Secret Card (3)", "Figure of Destiny (1) targets Face-down card (3)", labels);
        check("CARDNAME becomes a Spirit", "CARDNAME becomes a Spirit", labels);
        check("Secret Card and Figure of Destiny (1)", "Face-down card and Figure of Destiny (1)", labels);
        check("Secret Card (30)", "Face-down card (30)", labels);
        check("\u0000MTGX_PUBLIC_0\u0000 Secret Card", "\u0000MTGX_PUBLIC_0\u0000 Face-down card", labels);
        check(null, "", labels);
        System.out.println("InteractiveTextSmoke: " + checks + " visibility checks passed");
    }
}
