package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.IdentityHashMap;

/** Seat-private knowledge from actual reveal callbacks, bound to a hand visit.
 * Never grants Forge visibility, writes AI memory, or reads unseen card faces. */
final class KnownHandObservation {
    private record Known(Player owner, long timestamp, JsonObject snapshot) {}
    private final Player viewer;
    private final IdentityHashMap<Card, Known> known = new IdentityHashMap<>();

    KnownHandObservation(Player viewer) { this.viewer = viewer; }

    void remember(Iterable<Card> cards, Player owner) {
        if (owner == null || owner.getGame() != viewer.getGame())
            throw new RulesCostFeasibility.Unsupported("hand reveal has a foreign owner");
        for (Card card : cards) {
            if (card == null || card.getOwner() != owner || card.getGame() != viewer.getGame()
                    || !card.isInZone(ZoneType.Hand) || card.isFaceDown()
                    || owner.getCardsIn(ZoneType.Hand).stream().noneMatch(c -> c == card))
                throw new RulesCostFeasibility.Unsupported("hand reveal has an invalid live card");
            known.put(card, new Known(owner, card.getGameTimestamp(), StateEncoder.encodeCardUnchecked(card)));
        }
    }

    JsonObject remembered(Card card) {
        Known entry = known.get(card);
        if (entry == null) return null;
        if (card.getGame() != viewer.getGame() || card.getOwner() != entry.owner()
                || !card.isInZone(ZoneType.Hand) || card.getGameTimestamp() != entry.timestamp()
                || entry.owner().getCardsIn(ZoneType.Hand).stream().noneMatch(c -> c == card)) {
            known.remove(card);
            return null;
        }
        // Snapshot at reveal, not new information about an otherwise hidden face.
        return entry.snapshot().deepCopy();
    }

    JsonObject visible(Card card) {
        JsonObject current = StateEncoder.encodeCard(card, viewer.getView());
        return current != null ? current : remembered(card);
    }

    void augment(JsonObject state) {
        known.keySet().removeIf(card -> !card.isInZone(ZoneType.Hand)
            || card.getGameTimestamp() != known.get(card).timestamp()
            || known.get(card).owner().getCardsIn(ZoneType.Hand).stream().noneMatch(c -> c == card));
        for (var value : state.getAsJsonArray("players")) {
            var encoded = value.getAsJsonObject();
            Player owner = viewer.getGame().getPlayers().stream()
                .filter(p -> StateEncoder.playerIndex(viewer.getGame(), p) == encoded.get("index").getAsInt())
                .findFirst().orElseThrow();
            var hand = new JsonArray();
            for (Card card : owner.getCardsIn(ZoneType.Hand)) {
                var face = visible(card);
                if (face != null) hand.add(face);
            }
            encoded.add("hand", hand);
        }
        state.addProperty("knownHandVersion", "revealed-hand-visit-v1");
    }
}
