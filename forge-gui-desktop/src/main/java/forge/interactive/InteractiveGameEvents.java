package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

import java.util.HashMap;
import java.util.Map;

/** Small, visibility-filtered facts copied synchronously from engine events.
 * Never serialize event.toString(), ability descriptions, or a mutable game snapshot.
 */
final class InteractiveGameEvents {
    private InteractiveGameEvents() { }

    static JsonObject encode(final GameEvent event, final Game game, final PlayerView viewer) {
        return encode(event, game, viewer, null);
    }

    /**
     * As {@link #encode(GameEvent, Game, PlayerView)}, with the stream's {@link Moves} ({@code null}: none), which a
     * {@code land_played} event needs for its {@code fromZone}. Give every event of one game, in order.
     */
    static JsonObject encode(final GameEvent event, final Game game, final PlayerView viewer, final Moves moves) {
        if (moves != null && event instanceof GameEventCardChangeZone e) {
            moves.note(game, e);
        }
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
            final Card land = publicIn(game, e.land(), ZoneType.Battlefield, viewer);
            if (land != null) {
                facts(result, game, land, null);
                final ZoneType from = moves == null ? null : moves.lastIntoBattlefield(game, land.getId());
                if (from != null) {
                    result.addProperty("fromZone", from.name());
                }
            }
        } else if (event instanceof GameEventSpellAbilityCast e) {
            kind(result, e.sa().isSpell() ? "spell_cast"
                    : e.si().isTrigger() ? "ability_triggered" : "ability_activated");
            seat(result, game, e.si().getActivatingPlayer());
            result.addProperty("card", label(e.sa().getHostCard(), viewer));
            if (e.sa().isSpell()) {
                castFrom(result, game, e.sa().getHostCard());
                final Card spell = publicIn(game, e.sa().getHostCard(), ZoneType.Stack, viewer);
                if (spell != null) {
                    // A spell: the card says whether it is a copy (a copied spell's card is a COPIED_SPELL).
                    facts(result, game, spell, null);
                }
            }
        } else if (event instanceof GameEventCardChangeZone e) {
            zoneChanged(result, game, e, viewer);
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

    /**
     * {@code fromZone}: the zone a spell was cast from (Forge's zone name: "Hand", "Graveyard", "Exile",
     * "Library", "Command"), read from the card on the stack as the cast event fires. Where a spell is
     * cast from is public (the card moves from there to the stack), whatever the card's identity. A
     * client uses it to say where a card went between two of its views: Venser, Shaper Savant returned a
     * spell to its owner's hand and the owner cast it again before the human looked (owner report
     * 2026-10-10T06-30-52). Absent for a copy, which was cast from nowhere.
     */
    private static void castFrom(final JsonObject result, final Game game, final CardView host) {
        if (host == null) {
            return;
        }
        final Card card = game.findById(host.getId());
        final Zone from = card == null || !card.isInZone(ZoneType.Stack) ? null : card.getCastFrom();
        if (from != null && from.getZoneType() != null) {
            result.addProperty("fromZone", from.getZoneType().name());
        }
    }

    /**
     * Destinations where a {@code zone_changed} may carry the moved card's identity. Forge fires the event inside the
     * move, before the effect that moved the card has finished with it: an exile "face down" turns the card face down
     * only after it arrives ({@code ChangeZoneEffect}, ExileFaceDown), so a card arriving in exile or on the
     * battlefield is not yet known to stay public. Those identities come from the next view's zone entries instead
     * (and from {@code land_played} / {@code spell_cast}, which fire once the move is done). A graveyard is face up
     * (CR 404.2); hand, library and stack visibility is Forge's own may-see rule on the card's view.
     */
    private static final java.util.Set<ZoneType> IDENTITY_DESTINATIONS =
            java.util.EnumSet.of(ZoneType.Graveyard, ZoneType.Hand, ZoneType.Library, ZoneType.Stack);

    /**
     * {@code zone_changed} (lane bridgemeta-1018): {@code fromZone} and {@code toZone} (Forge's zone names; a zone is
     * absent when Forge names none, e.g. a token created from nowhere). That a card moved between two zones is public;
     * the card's identity is added ({@code card}, {@code fid}, {@code ownerSeat}, {@code isToken}, {@code isCopy})
     * only when the move ends in one of {@link #IDENTITY_DESTINATIONS} and the viewer may see the card there. A draw
     * the viewer does not see, a card put into a library, any move into exile or onto the battlefield, and any other
     * move into a zone the viewer cannot see carries no identity at all, not even an id.
     *
     * <p>No {@code playerSeat}: whose zone it was is not claimed, and the game log therefore leaves the event out.</p>
     */
    private static void zoneChanged(final JsonObject result, final Game game, final GameEventCardChangeZone e,
                                    final PlayerView viewer) {
        kind(result, "zone_changed");
        final ZoneType from = zoneType(e.from());
        final ZoneType to = zoneType(e.to());
        if (from != null) {
            result.addProperty("fromZone", from.name());
        }
        if (to != null) {
            result.addProperty("toZone", to.name());
        }
        final Card card = to == null || !IDENTITY_DESTINATIONS.contains(to) ? null
                : publicIn(game, e.card(), to, viewer);
        if (card != null) {
            final String name = card.getView().getCurrentState().getName();
            if (name != null && !name.isBlank()) {
                result.addProperty("card", name);
            }
            facts(result, game, card, null);
        }
    }

    private static ZoneType zoneType(final ZoneView zone) {
        return zone == null ? null : zone.zoneType();
    }

    /**
     * The game's current card for {@code view}, when it is in {@code zone} (by the card and by its view, which a
     * frozen tracker may not have moved yet), face up, and its identity may go to {@code viewer}; otherwise null. A
     * view without a zone reads as visible to all in {@link CardView#canBeShownTo}, so the zone is required, never
     * assumed.
     */
    static Card publicIn(final Game game, final CardView view, final ZoneType zone, final PlayerView viewer) {
        if (view == null || zone == null) {
            return null;
        }
        final Card card;
        try {
            card = game.findById(view.getId());
        } catch (RuntimeException e) {
            return null;
        }
        if (card == null || card.getZone() == null || card.getZone().getZoneType() != zone) {
            return null;
        }
        final CardView current = card.getView();
        if (current == null || current.getZone() != zone || current.isFaceDown()
                || !InteractiveState.mayReceiveIdentity(current, viewer)) {
            return null;
        }
        return card;
    }

    /** {@code fid}, {@code ownerSeat}, {@code isToken}, {@code isCopy} of a card {@link #publicIn} returned. */
    private static void facts(final JsonObject result, final Game game, final Card card, final SpellAbility ability) {
        result.addProperty("fid", card.getId());
        InteractiveCardFacts.addOwnerTokenAndCopy(result, game, card, ability);
    }

    /**
     * Where each card last entered the battlefield from, per game, from the stream's {@code GameEventCardChangeZone}
     * (which Forge fires inside the move, before {@code GameEventLandPlayed}). The land event names no zone of its
     * own. Noting the same event twice (a watch table encodes it once per seat) changes nothing.
     */
    static final class Moves {
        private Game game;
        private final Map<Integer, ZoneType> intoBattlefield = new HashMap<>();

        synchronized void note(final Game current, final GameEventCardChangeZone e) {
            if (current != game) {
                game = current;
                intoBattlefield.clear();
            }
            if (e.card() == null || zoneType(e.to()) != ZoneType.Battlefield) {
                return;
            }
            final ZoneType from = zoneType(e.from());
            if (from == null) {
                intoBattlefield.remove(e.card().getId());
            } else {
                intoBattlefield.put(e.card().getId(), from);
            }
        }

        synchronized ZoneType lastIntoBattlefield(final Game current, final int fid) {
            return current == game ? intoBattlefield.get(fid) : null;
        }
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
