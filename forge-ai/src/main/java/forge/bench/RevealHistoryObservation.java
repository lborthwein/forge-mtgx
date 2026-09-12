package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Recipient-local historical disclosures, NOT current hidden-zone membership.
 * No live card references, IDs, list order, future positions, or message text
 * survive the callback. Owner/zone label the callback, not every card's origin.
 * This identity history does not certify complete public-event/history parity.
 */
final class RevealHistoryObservation {
    static final String VERSION = "revealed-names-history-v1";
    private final Player viewer;
    private final JsonArray history = new JsonArray();

    RevealHistoryObservation(Player viewer) { this.viewer = viewer; }

    void remember(Iterable<Card> cards, ZoneType zone, Player owner) {
        if (cards == null) throw unsupported("null cards");
        var names = new ArrayList<String>();
        for (Card card : cards) {
            if (card == null || card.getGame() != viewer.getGame()) throw unsupported("foreign card");
            // The explicit callback grants the disclosed face: Human.reveal
            // temporarily adds mayLook before displaying these exact cards.
            // Capture it here without changing persistent game visibility.
            if (card.isFaceDown()) {
                names.add(card.getDisplayName(card.getState(CardStateName.Original)));
            } else names.add(card.getDisplayName());
        }
        append(names, zone, owner);
    }

    void rememberViews(List<CardView> cards, ZoneType zone, PlayerView owner) {
        if (cards == null) throw unsupported("null card views");
        Player source = viewer.getGame().getPlayers().stream().filter(p -> p.getView() == owner).findFirst().orElse(null);
        var names = new ArrayList<String>();
        for (CardView card : cards) {
            if (card == null) throw unsupported("null card view");
            // Never resolve a view to a live card: callbacks may carry LKI.
            if (card.isFaceDown()) {
                var face = card.getAlternateState();
                if (face == null || face.getState() != CardStateName.Original)
                    throw unsupported("face-down view lacks disclosed original");
                names.add(face.getName());
            } else names.add(card.getName());
        }
        append(names, zone, source);
    }

    private void append(List<String> names, ZoneType zone, Player owner) {
        if (owner == null || owner.getGame() != viewer.getGame()) throw unsupported("foreign owner");
        // The callback label may be Merged, Ante or another pinned engine zone.
        // Accept the enum label without treating it as current membership.
        if (zone == null) throw unsupported("missing zone");
        int seat = StateEncoder.playerIndex(viewer.getGame(), owner);
        if (seat != 0 && seat != 1) throw unsupported("non-duel owner");
        for (String name : names) if (name == null || name.isBlank()) throw unsupported("unnamed disclosed face");
        if (names.isEmpty()) return;
        Collections.sort(names); // multiset, never an accidental library-order oracle
        var entry = new JsonObject();
        entry.addProperty("sequence", history.size() + 1);
        entry.addProperty("owner", seat);
        entry.addProperty("zone", zone.name().toLowerCase(Locale.ROOT));
        var faces = new JsonArray();
        for (String name : names) faces.add(name);
        entry.add("names", faces);
        history.add(entry);
    }

    void augment(JsonObject state) {
        state.addProperty("revealHistoryVersion", VERSION);
        state.add("revealHistory", history.deepCopy());
    }

    private static RulesCostFeasibility.Unsupported unsupported(String reason) {
        return new RulesCostFeasibility.Unsupported("reveal history: " + reason);
    }
}
