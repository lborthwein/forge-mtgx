package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.player.Player;
import forge.game.player.PlayerView;

/** Small, visibility-filtered facts copied synchronously from engine events.
 * Never serialize event.toString(), ability descriptions, or a mutable game snapshot.
 */
final class InteractiveGameEvents {
    private InteractiveGameEvents() { }

    static JsonObject encode(final GameEvent event, final Game game, final PlayerView viewer) {
        final JsonObject result = new JsonObject();
        // Retained for diagnostics, not player-facing prose. Unknown events have no kind.
        result.addProperty("class", event.getClass().getSimpleName());
        if (event instanceof GameEventTurnBegan e) {
            kind(result, "turn_started");
            seat(result, game, e.turnOwner());
            result.addProperty("turn", e.turnNumber());
        } else if (event instanceof GameEventLandPlayed e) {
            kind(result, "land_played");
            seat(result, game, e.player());
            result.addProperty("card", label(e.land(), viewer));
        } else if (event instanceof GameEventSpellAbilityCast e) {
            kind(result, e.sa().isSpell() ? "spell_cast"
                    : e.si().isTrigger() ? "ability_triggered" : "ability_activated");
            seat(result, game, e.si().getActivatingPlayer());
            result.addProperty("card", label(e.sa().getHostCard(), viewer));
        } else if (event instanceof GameEventPlayerLivesChanged e) {
            if (e.oldLives() == e.newLives()) {
                return result;
            }
            kind(result, "life_changed");
            seat(result, game, e.player());
            result.addProperty("oldLife", e.oldLives());
            result.addProperty("newLife", e.newLives());
        } else if (event instanceof GameEventPlayerDamaged e) {
            kind(result, "player_damaged");
            seat(result, game, e.target());
            result.addProperty("source", label(e.source(), viewer));
            result.addProperty("amount", e.amount());
            result.addProperty("combat", e.combat());
            result.addProperty("infect", e.infect());
        } else if (event instanceof GameEventAttackersDeclared e) {
            kind(result, "attackers_declared");
            seat(result, game, e.player());
            final JsonArray cards = new JsonArray();
            for (CardView card : e.attackersMap().values()) {
                cards.add(label(card, viewer));
            }
            result.add("cards", cards);
        } else if (event instanceof GameEventBlockersDeclared e) {
            kind(result, "blockers_declared");
            seat(result, game, e.defendingPlayer());
            final JsonArray blocks = new JsonArray();
            for (var assignments : e.blockers().values()) {
                for (var assignment : assignments.entries()) {
                    // PhaseHandler represents an unblocked attacker by mapping
                    // it to itself. That is not an actual blocking assignment.
                    if (assignment.getKey().getId() == assignment.getValue().getId()) {
                        continue;
                    }
                    final JsonObject block = new JsonObject();
                    block.addProperty("attacker", label(assignment.getKey(), viewer));
                    block.addProperty("blocker", label(assignment.getValue(), viewer));
                    blocks.add(block);
                }
            }
            result.add("blocks", blocks);
        } else if (event instanceof GameEventShuffle e) {
            kind(result, "library_shuffled");
            seat(result, game, e.player());
        } else if (event instanceof GameEventMulligan e) {
            kind(result, "mulligan");
            seat(result, game, e.player());
        }
        // A library-to-hand zone change is not necessarily a draw (it can be a
        // search). Do not fabricate draw/discard/destroy causes from zone changes.
        return result;
    }

    private static void kind(final JsonObject result, final String kind) {
        result.addProperty("kind", kind);
    }

    private static void seat(final JsonObject result, final Game game, final PlayerView player) {
        if (player == null) {
            return;
        }
        int index = 0;
        for (Player registered : game.getRegisteredPlayers()) {
            if (registered.getId() == player.getId()) {
                result.addProperty("playerSeat", index);
                return;
            }
            index++;
        }
    }

    private static String label(final CardView card, final PlayerView viewer) {
        if (card == null) {
            return "a card";
        }
        // Do not attach engine IDs: an event is prose, not a selection handle.
        // A face-down card remains anonymous in the public log even to its owner.
        if (card.isFaceDown()) {
            return "a face-down card";
        }
        if (!InteractiveState.mayReceiveIdentity(card, viewer)) {
            return "a card";
        }
        final String name = card.getCurrentState().getName();
        return name == null || name.isBlank() ? "a card" : name;
    }
}
