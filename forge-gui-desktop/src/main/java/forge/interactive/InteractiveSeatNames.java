package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names players read (owner report 2026-10-29T18-03-47, session 6cc1b95d): prompt and modal text never says Forge's
 * internal player names ("Default Forge" for the AI seat, "Browser Player" for a one-seat human). Each player's engine
 * name in the text of a request is replaced by its seat's display name:
 *
 * <ul>
 * <li>the config line's {@code seatNames} (two display names, seat order) when the host sends them;</li>
 * <li>otherwise "AI Player" for a seat Forge plays, "You" for the seat the text is sent to, and the engine name of
 * any other browser seat (a table's {@code names}, which are already display names).</li>
 * </ul>
 *
 * <p>"You" is written as a reader expects: "your" for a possessive, lower case inside a sentence, and the verb that
 * follows takes the second person ("You gain 3 life", not "You gains 3 life"). The engine's own names, the game, and
 * every decision are untouched: this is text only.</p>
 */
final class InteractiveSeatNames {
    static final String AI_PLAYER = "AI Player";
    static final String YOU = "You";

    private final List<String> configured;

    InteractiveSeatNames(final List<String> configured) {
        this.configured = configured == null || configured.size() != 2 ? List.of() : List.copyOf(configured);
    }

    /** The display name of {@code seat} as the player at {@code viewerSeat} reads it. */
    String displayName(final Game game, final int seat, final int viewerSeat) {
        if (!configured.isEmpty() && seat >= 0 && seat < configured.size()) {
            return configured.get(seat);
        }
        final Player player = playerAt(game, seat);
        if (player != null && player.getController() != null && player.getController().isAI()) {
            return AI_PLAYER;
        }
        if (seat == viewerSeat) {
            return YOU;
        }
        return player == null ? "Player " + (seat + 1) : player.getName();
    }

    /** {@code text} with every player's engine name replaced by its display name for {@code viewerSeat}. */
    String render(final String text, final Game game, final int viewerSeat) {
        if (text == null || text.isEmpty() || game == null) {
            return text;
        }
        final List<Player> players = new ArrayList<>(game.getRegisteredPlayers());
        // the longer name first: "Player 1" inside "Player 10" is never replaced on its own
        players.sort(Comparator.comparingInt((Player p) -> p.getName() == null ? 0 : -p.getName().length()));
        String result = text;
        for (Player player : players) {
            final String internal = player.getName();
            if (internal == null || internal.isBlank()) {
                continue;
            }
            final int seat = seatOf(game, player);
            final String display = displayName(game, seat, viewerSeat);
            if (display.equals(internal)) {
                continue;
            }
            result = replaceName(result, internal, display);
        }
        return result;
    }

    /**
     * Every player-facing string of a request ({@code title}, {@code message}, {@code label}, {@code text}, at any
     * depth of {@code node}) rendered by {@link #render}.
     */
    void renderTree(final JsonElement node, final Game game, final int viewerSeat) {
        if (node == null || game == null) {
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray()) {
                renderTree(element, game, viewerSeat);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        final JsonObject object = node.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            final JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && TEXT_KEYS.contains(entry.getKey())) {
                final String before = value.getAsString();
                final String after = render(before, game, viewerSeat);
                if (!after.equals(before)) {
                    entry.setValue(new com.google.gson.JsonPrimitive(after));
                }
            } else if (value.isJsonObject() || value.isJsonArray()) {
                renderTree(value, game, viewerSeat);
            }
        }
    }

    private static final Set<String> TEXT_KEYS = Set.of("title", "message", "label", "text");

    /** Third-person verbs Forge writes after a player's name, and the second person of each. */
    private static final Map<String, String> SECOND_PERSON = Map.ofEntries(
            Map.entry("is", "are"), Map.entry("has", "have"), Map.entry("does", "do"), Map.entry("was", "were"),
            Map.entry("gains", "gain"), Map.entry("loses", "lose"), Map.entry("controls", "control"),
            Map.entry("draws", "draw"), Map.entry("discards", "discard"), Map.entry("searches", "search"),
            Map.entry("reveals", "reveal"), Map.entry("chooses", "choose"), Map.entry("pays", "pay"),
            Map.entry("puts", "put"), Map.entry("sacrifices", "sacrifice"), Map.entry("casts", "cast"),
            Map.entry("activates", "activate"), Map.entry("attacks", "attack"), Map.entry("blocks", "block"),
            Map.entry("plays", "play"), Map.entry("votes", "vote"), Map.entry("vote", "vote"),
            Map.entry("takes", "take"), Map.entry("wins", "win"), Map.entry("mills", "mill"),
            Map.entry("exiles", "exile"), Map.entry("returns", "return"), Map.entry("shuffles", "shuffle"),
            Map.entry("looks", "look"), Map.entry("gets", "get"), Map.entry("picks", "pick"),
            Map.entry("picked", "picked"), Map.entry("chose", "chose"), Map.entry("voted", "voted"),
            Map.entry("flips", "flip"), Map.entry("rolls", "roll"), Map.entry("scries", "scry"),
            Map.entry("surveils", "surveil"), Map.entry("may", "may"), Map.entry("must", "must"),
            Map.entry("can", "can"), Map.entry("cannot", "cannot"), Map.entry("would", "would"));

    private static final Pattern NEXT_WORD = Pattern.compile("^(\\s+)([a-z]+)\\b");

    private static String replaceName(final String text, final String internal, final String display) {
        final Pattern name = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(internal)
                + "('s|’s)?(?![\\p{L}\\p{N}])");
        final Matcher m = name.matcher(text);
        final StringBuilder out = new StringBuilder(text.length() + 16);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            final boolean possessive = m.group(1) != null;
            last = m.end();
            if (!YOU.equals(display)) {
                out.append(display).append(possessive ? m.group(1) : "");
                continue;
            }
            final boolean start = sentenceStart(out);
            if (possessive) {
                out.append(start ? "Your" : "your");
                continue;
            }
            out.append(start ? "You" : "you");
            // "Browser Player, you are going second." reads "You are going second."
            final String rest = text.substring(last);
            if (rest.startsWith(", you ") || rest.startsWith(", You ")) {
                last += ", you".length();
                continue;
            }
            final Matcher verb = NEXT_WORD.matcher(rest);
            if (verb.find() && SECOND_PERSON.containsKey(verb.group(2))) {
                out.append(verb.group(1)).append(SECOND_PERSON.get(verb.group(2)));
                last += verb.end();
            }
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** True when the next word written to {@code out} begins a sentence (or the text, or a line). */
    private static boolean sentenceStart(final CharSequence out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            final char c = out.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (Character.isWhitespace(c) || c == '"' || c == '“' || c == '(') {
                continue;
            }
            return c == '.' || c == '!' || c == '?' || c == ':';
        }
        return true;
    }

    private static Player playerAt(final Game game, final int seat) {
        if (game == null || seat < 0 || seat >= game.getRegisteredPlayers().size()) {
            return null;
        }
        return game.getRegisteredPlayers().get(seat);
    }

    static int seatOf(final Game game, final Player player) {
        return player == null ? -1 : game.getRegisteredPlayers().indexOf(player);
    }

    /** A JSON array of the seat display names, seat order, for {@code viewerSeat}. */
    JsonArray all(final Game game, final int viewerSeat) {
        final JsonArray names = new JsonArray();
        for (int seat = 0; seat < game.getRegisteredPlayers().size(); seat++) {
            names.add(displayName(game, seat, viewerSeat));
        }
        return names;
    }
}
