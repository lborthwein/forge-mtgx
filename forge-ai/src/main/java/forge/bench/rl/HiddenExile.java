package forge.bench.rl;

import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane bvw-step1-build-1024 (PREDECLARATION-step1 OPEN-6, ruled 2026-10-24): face-down exiled cards a seat may not look
 * at. The honest redraw re-draws hidden hand and library cards only, so a card exiled face down keeps its real identity
 * in every world of a copy: a search or proof at such a position could use a card the seat cannot know. The six mush
 * cube cards that exile face down are {@link #SOURCES}; a face-down exiled card whose exiler is unknown counts as well
 * (conservative). A card exiled face down by any other card is not this rule's (the mush cube has none).
 *
 * <p>Visibility is read on a COPY of the live game from the seat's view ({@link Card#mayPlayerLook}), as the search
 * and the proof see it: Shelldock Isle's hideaway card stays visible to its owner on the copy and hidden from the
 * opponent; Bomat Courier's exile is hidden from both seats (BvwHiddenExileTest). Nothing here names a hidden card.
 */
public final class HiddenExile {
    /** The mush cube's face-down exilers (Forge script scan of all 540 cards), plus Decadent Dragon's adventure. */
    public static final Set<String> SOURCES = Set.of("Beseech the Mirror", "Bomat Courier", "Memory Jar",
            "Thief of Sanity", "Shelldock Isle", "Decadent Dragon", "Expensive Taste");
    public static final String TARGET_SOURCE = "unsupported-hidden-exile";

    private HiddenExile() {
    }

    /** Read only, on any game (the live one too): is any card in any exile zone face down? */
    public static boolean anyFaceDownExile(final Game g) {
        for (Player p : g.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Exile)) {
                if (c.isFaceDown()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * On a copy: one entry per face-down exiled card {@code seat} may not look at whose exiler is one of {@link #SOURCES}
     * or unknown: {@code {"owner": "self" | "opponent", "exiled_with": name | null}}. Empty when there is none.
     */
    public static JsonArray scan(final Game copy, final Player seat) {
        final JsonArray out = new JsonArray();
        for (Player p : copy.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Exile)) {
                if (!c.isFaceDown() || c.mayPlayerLook(seat)) {
                    continue;
                }
                final String src = exiler(c.getExiledWith());
                if (src != null && !SOURCES.contains(src)) {
                    continue;
                }
                final JsonObject e = new JsonObject();
                e.addProperty("owner", c.getOwner() == seat ? "self" : "opponent");
                e.add("exiled_with", src == null ? JsonNull.INSTANCE : new JsonPrimitive(src));
                out.add(e);
            }
        }
        return out;
    }

    /** The exiler's card name (its printed name; an adventure state counts as its card), or null when unknown. */
    static String exiler(final Card by) {
        if (by == null) {
            return null;
        }
        if (SOURCES.contains(by.getName())) {
            return by.getName();
        }
        final String printed = by.getPaperCard() == null ? null : by.getPaperCard().getName();
        return printed != null ? printed : by.getName();
    }
}
