package forge.interactive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge's target descriptors in plain words (mtgx, 2026-10-02).
 *
 * <p>A card script that has no {@code TgtPrompt$} gets the prompt "Select target " plus its
 * {@code ValidTgts$} run through {@code Lang.formatValidDesc}. That only lowercases a bare type, so
 * every restriction reached the player as syntax: Reprieve's "Select target Card.inZoneStack"
 * (owner report 2026-10-02T16-19-27), "Select target Creature.YouCtrl", "Select target
 * Permanent.nonLand+OppCtrl". About 380 card scripts do this.</p>
 *
 * <p>{@link #render} rewrites each such descriptor in a text. A descriptor is a capitalised word of
 * three or more letters, a dot, then restrictions joined by {@code .} or {@code +}. The rewrite
 * reads type, restrictions and controller as words: "spell", "creature you control", "nonland
 * permanent an opponent controls", "creature spell with mana value 1 or greater". A restriction
 * this class cannot say in words (IsRemembered, ControlledBy TriggeredTarget ...) is never shown:
 * a line that asks for a target becomes "Choose a target", and anywhere else the descriptor
 * becomes its bare noun.</p>
 */
final class InteractiveTargetText {
    static final String FALLBACK = "Choose a target";

    /** Where a descriptor may start. The scan in {@link #render} finds where it ends. */
    private static final Pattern START = Pattern.compile("\\b[A-Z][A-Za-z]{2,}\\.[A-Za-z!]");
    private static final Pattern COMPARE =
            Pattern.compile("(cmc|power|toughness)(EQ|NE|LT|LE|GT|GE)(\\d+|X)");
    private static final Pattern WITH = Pattern.compile("with([A-Z][A-Za-z]*)");
    private static final Pattern SUBTYPE = Pattern.compile("[A-Z][a-z]+");

    private static final Set<String> CARD_TYPES = Set.of("Artifact", "Battle", "Creature", "Enchantment",
            "Instant", "Land", "Planeswalker", "Sorcery", "Kindred", "Tribal");
    private static final Map<String, String> ADJECTIVES = Map.ofEntries(
            Map.entry("Other", "other"), Map.entry("White", "white"), Map.entry("Blue", "blue"),
            Map.entry("Black", "black"), Map.entry("Red", "red"), Map.entry("Green", "green"),
            Map.entry("Colorless", "colorless"), Map.entry("MultiColor", "multicolored"),
            Map.entry("Multicolor", "multicolored"), Map.entry("MonoColor", "monocolored"),
            Map.entry("Legendary", "legendary"), Map.entry("nonLegendary", "nonlegendary"),
            Map.entry("Snow", "snow"), Map.entry("Basic", "basic"), Map.entry("nonBasic", "nonbasic"),
            Map.entry("Historic", "historic"), Map.entry("token", "token"), Map.entry("!token", "nontoken"),
            Map.entry("nonToken", "nontoken"), Map.entry("tapped", "tapped"), Map.entry("untapped", "untapped"),
            Map.entry("attacking", "attacking"), Map.entry("blocking", "blocking"),
            Map.entry("faceUp", "face-up"), Map.entry("faceDown", "face-down"),
            Map.entry("enchanted", "enchanted"), Map.entry("equipped", "equipped"));
    private static final Map<String, String> CLAUSES = Map.ofEntries(
            Map.entry("YouCtrl", "you control"), Map.entry("YouDontCtrl", "you don't control"),
            Map.entry("OppCtrl", "an opponent controls"), Map.entry("YouOwn", "you own"),
            Map.entry("YouDontOwn", "you don't own"), Map.entry("OppOwn", "an opponent owns"),
            Map.entry("DefenderCtrl", "the defending player controls"),
            Map.entry("attackedThisTurn", "that attacked this turn"),
            Map.entry("inZoneGraveyard", "in a graveyard"), Map.entry("inZoneExile", "in exile"),
            Map.entry("inZoneHand", "in a hand"), Map.entry("inZoneLibrary", "in a library"),
            Map.entry("inZoneBattlefield", ""));
    private static final Map<String, String> COMPARISONS = Map.of(
            "EQ", "%s", "NE", "other than %s", "LT", "less than %s", "LE", "%s or less",
            "GT", "greater than %s", "GE", "%s or greater");

    private InteractiveTargetText() {
    }

    /** {@code text} with every Forge descriptor said in words; see the class comment. */
    static String render(final String text) {
        if (text == null || text.isEmpty() || !START.matcher(text).find()) {
            return text;
        }
        final String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = renderLine(lines[i]);
        }
        return String.join("\n", lines);
    }

    private static String renderLine(final String line) {
        final StringBuilder out = new StringBuilder();
        final Matcher start = START.matcher(line);
        int at = 0;
        boolean unsayable = false;
        while (start.find(at)) {
            final int end = descriptorEnd(line, start.start());
            final String token = line.substring(start.start(), end);
            final String words = describe(token);
            out.append(line, at, start.start());
            if (words == null) {
                unsayable = true;
                out.append(noun(token.substring(0, token.indexOf('.'))));
            } else {
                out.append(words);
            }
            at = end;
        }
        out.append(line.substring(at));
        if (unsayable && line.toLowerCase(Locale.ROOT).contains("target")) {
            return FALLBACK;
        }
        return out.toString();
    }

    /**
     * End of the descriptor starting at {@code from}: letters, digits, {@code _ ! . +}. A
     * restriction ending in By/To/With/From/Of takes one following capitalised word as its
     * argument ("ControlledBy TriggeredTarget"), which belongs to the descriptor too.
     */
    private static int descriptorEnd(final String line, final int from) {
        int i = from;
        while (true) {
            while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                    || "_!.+".indexOf(line.charAt(i)) >= 0)) {
                i++;
            }
            final String head = line.substring(from, i);
            if (i + 1 < line.length() && line.charAt(i) == ' ' && Character.isUpperCase(line.charAt(i + 1))
                    && head.matches(".*[a-z](By|To|With|From|Of)")) {
                i++;
                continue;
            }
            // A sentence's full stop is not part of the descriptor.
            while (i > from && (line.charAt(i - 1) == '.' || line.charAt(i - 1) == '+')) {
                i--;
            }
            return i;
        }
    }

    /** One descriptor in words, or null when a restriction has no plain wording here. */
    static String describe(final String token) {
        final int dot = token.indexOf('.');
        if (dot < 0) {
            return noun(token);
        }
        final String base = token.substring(0, dot);
        final List<String> props = new ArrayList<>();
        for (String p : token.substring(dot + 1).split("[.+]")) {
            if (!p.isEmpty()) {
                props.add(p);
            }
        }
        if ("Player".equals(base) || "Opponent".equals(base)) {
            return describePlayer(base, props);
        }
        final List<String> adjectives = new ArrayList<>();
        final List<String> subtypes = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final List<String> clauses = new ArrayList<>();
        boolean spell = "Spell".equals(base);
        if (CARD_TYPES.contains(base)) {
            types.add(base.toLowerCase(Locale.ROOT));
        } else if (!"Card".equals(base) && !"Permanent".equals(base) && !"Spell".equals(base)) {
            if (!SUBTYPE.matcher(base).matches()) {
                return null;
            }
            subtypes.add(base);
        }
        for (String p : props) {
            if ("inZoneStack".equals(p)) {
                spell = true;
            } else if (CARD_TYPES.contains(p)) {
                types.add(p.toLowerCase(Locale.ROOT));
            } else if (ADJECTIVES.containsKey(p)) {
                adjectives.add(ADJECTIVES.get(p));
            } else if (CLAUSES.containsKey(p)) {
                if (!CLAUSES.get(p).isEmpty()) {
                    clauses.add(CLAUSES.get(p));
                }
            } else if (p.startsWith("non") && p.length() > 3 && Character.isUpperCase(p.charAt(3))) {
                final String negated = p.substring(3);
                if (CARD_TYPES.contains(negated) || ADJECTIVES.containsKey(negated)
                        || "Token".equals(negated)) {
                    adjectives.add("non" + negated.toLowerCase(Locale.ROOT));
                } else if (SUBTYPE.matcher(negated).matches()) {
                    adjectives.add("non-" + negated);
                } else {
                    return null;
                }
            } else if (COMPARE.matcher(p).matches()) {
                final Matcher m = COMPARE.matcher(p);
                m.matches();
                final String stat = "cmc".equals(m.group(1)) ? "mana value" : m.group(1);
                clauses.add("with " + stat + " " + String.format(COMPARISONS.get(m.group(2)), m.group(3)));
            } else if (WITH.matcher(p).matches()) {
                clauses.add("with " + words(p.substring(4)));
            } else if (SUBTYPE.matcher(p).matches()) {
                subtypes.add(p);
            } else {
                return null;
            }
        }
        // "other" leads ("other nontoken creature"); who controls or owns it comes before what it has.
        if (adjectives.remove("other")) {
            adjectives.add(0, "other");
        }
        if (clauses.contains("you own") && clauses.contains("you control")) {
            clauses.remove("you control");
            clauses.set(clauses.indexOf("you own"), "you own and control");
        }
        clauses.sort(java.util.Comparator.comparingInt(c -> c.startsWith("with ") ? 1 : 0));
        final List<String> phrase = new ArrayList<>(adjectives);
        phrase.addAll(subtypes);
        phrase.addAll(types);
        if (spell) {
            phrase.add("spell");
        } else if (types.isEmpty() && subtypes.isEmpty()) {
            phrase.add("Permanent".equals(base) ? "permanent" : "card");
        } else if ("Card".equals(base)) {
            phrase.add("card");
        } else if ("Permanent".equals(base) && types.isEmpty()) {
            phrase.add("permanent");
        }
        phrase.addAll(clauses);
        return String.join(" ", phrase);
    }

    private static String describePlayer(final String base, final List<String> props) {
        String noun = "Opponent".equals(base) ? "opponent" : "player";
        final List<String> adjectives = new ArrayList<>();
        for (String p : props) {
            if ("Opponent".equals(p)) {
                noun = "opponent";
            } else if ("Other".equals(p)) {
                adjectives.add("other");
            } else {
                return null;
            }
        }
        adjectives.add(noun);
        return String.join(" ", adjectives);
    }

    /** The bare noun for a descriptor's type word. */
    private static String noun(final String base) {
        if (CARD_TYPES.contains(base) || "Card".equals(base) || "Permanent".equals(base)
                || "Spell".equals(base) || "Player".equals(base) || "Opponent".equals(base)) {
            return base.toLowerCase(Locale.ROOT);
        }
        return SUBTYPE.matcher(base).matches() ? base : "card";
    }

    /** "FirstStrike" -> "first strike". */
    private static String words(final String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }
}
