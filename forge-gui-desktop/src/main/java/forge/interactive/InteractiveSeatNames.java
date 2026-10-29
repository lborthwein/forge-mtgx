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
    /** The config's {@code names}: engine names a host chose for table seats, display names already. */
    private final Set<String> hostNames;

    InteractiveSeatNames(final List<String> configured) {
        this(configured, null);
    }

    InteractiveSeatNames(final List<String> configured, final List<String> hostNames) {
        this.configured = configured == null || configured.size() != 2 ? List.of() : List.copyOf(configured);
        this.hostNames = hostNames == null ? Set.of() : Set.copyOf(hostNames);
    }

    /** The display name of {@code seat} as the player at {@code viewerSeat} reads it. */
    String displayName(final Game game, final int seat, final int viewerSeat) {
        if (!configured.isEmpty() && seat >= 0 && seat < configured.size()
                && !isOtherSeatsEngineName(game, seat, configured.get(seat))) {
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
        final Compiled compiled = compiled(game, viewerSeat);
        if (compiled == null) {
            return text;
        }
        final Matcher m = compiled.pattern.matcher(text);
        final StringBuilder out = new StringBuilder(text.length() + 16);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            final String display = compiled.display.get(m.group(1));
            final boolean possessive = m.group(2) != null;
            last = m.end();
            if (!YOU.equals(display)) {
                out.append(display).append(possessive ? m.group(2) : "");
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

    /** One alternation of every rewritable engine name (longest first), and each one's display name. */
    private record Compiled(Pattern pattern, Map<String, String> display) {
    }

    /** review #138 S3-7: compiled once per game and viewer, not per string. */
    private final Map<Long, Compiled> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Game cachedGame;

    private Compiled compiled(final Game game, final int viewerSeat) {
        if (cachedGame != game) {
            cache.clear();
            cachedGame = game;
        }
        final Compiled hit = cache.get((long) viewerSeat);
        if (hit != null) {
            return hit.pattern == null ? null : hit;
        }
        final List<Player> players = game.getRegisteredPlayers();
        final Map<String, Integer> count = new java.util.HashMap<>();
        for (Player p : players) {
            if (p.getName() != null) {
                count.merge(p.getName(), 1, Integer::sum);
            }
        }
        final Map<String, String> display = new java.util.LinkedHashMap<>();
        for (int seat = 0; seat < players.size(); seat++) {
            final String internal = players.get(seat).getName();
            // review #138 S2-1: never a host-chosen name (it is a display name, and may be a word of card text), never
            // a name two seats share (whose would it be?)
            if (internal == null || internal.isBlank() || hostNames.contains(internal) || count.get(internal) > 1) {
                continue;
            }
            final String shown = displayName(game, seat, viewerSeat);
            if (!shown.equals(internal)) {
                display.put(internal, shown);
            }
        }
        final Compiled result;
        if (display.isEmpty()) {
            result = new Compiled(null, Map.of());
        } else {
            final List<String> names = new ArrayList<>(display.keySet());
            names.sort(Comparator.comparingInt(String::length).reversed());
            final StringBuilder alternation = new StringBuilder();
            for (String name : names) {
                alternation.append(alternation.length() == 0 ? "" : "|").append(Pattern.quote(name));
            }
            result = new Compiled(Pattern.compile("(?<![\\p{L}\\p{N}])(" + alternation
                    + ")('s|’s)?(?![\\p{L}\\p{N}])"), Map.copyOf(display));
        }
        cache.put((long) viewerSeat, result);
        return result.pattern == null ? null : result;
    }

    /** A configured display name that is another seat's engine name would make the text ambiguous: not used. */
    private static boolean isOtherSeatsEngineName(final Game game, final int seat, final String name) {
        if (game == null) {
            return false;
        }
        final List<Player> players = game.getRegisteredPlayers();
        for (int i = 0; i < players.size(); i++) {
            if (i != seat && name.equals(players.get(i).getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every player-facing string of a request ({@code title}, {@code message}, {@code label}, {@code text}, at any
     * depth of {@code node}) rendered by {@link #render}.
     */
    void renderTree(final JsonElement node, final Game game, final int viewerSeat) {
        renderTree(node, game, viewerSeat, Set.of());
    }

    /** As above; {@code cardItems}: list-choice item ids whose option is a card (never rewritten either). */
    void renderTree(final JsonElement node, final Game game, final int viewerSeat, final Set<String> cardItems) {
        if (node == null || game == null) {
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray()) {
                renderTree(element, game, viewerSeat, cardItems);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        final JsonObject object = node.getAsJsonObject();
        // review #138 S2-1: a card's control or item names the card (and quotes its text): never rewritten
        final boolean card = object.has("cardId") || object.has("id") && object.get("id").isJsonPrimitive()
                && (object.get("id").getAsString().startsWith("card:")
                        || cardItems.contains(object.get("id").getAsString()));
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            final JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && TEXT_KEYS.contains(entry.getKey()) && !card) {
                final String before = value.getAsString();
                final String after = render(before, game, viewerSeat);
                if (!after.equals(before)) {
                    entry.setValue(new com.google.gson.JsonPrimitive(after));
                }
            } else if (value.isJsonObject() || value.isJsonArray()) {
                renderTree(value, game, viewerSeat, cardItems);
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
