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
            result.addProperty("card", label(e.land(), game, viewer));
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
            result.addProperty("card", label(e.sa().getHostCard(), game, viewer));
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
        } else if (event instanceof GameEventCardCounters e) {
            countersChanged(result, game, e, viewer);
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
            result.addProperty("source", label(e.source(), game, viewer));
            result.addProperty("amount", e.amount());
            result.addProperty("combat", e.combat());
            result.addProperty("infect", e.infect());
        } else if (event instanceof GameEventAttackersDeclared e) {
            kind(result, "attackers_declared");
            seat(result, game, e.player());
            final JsonArray cards = new JsonArray();
            for (CardView card : e.attackersMap().values()) {
                cards.add(label(card, game, viewer));
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
                    block.addProperty("attacker", label(assignment.getKey(), game, viewer));
                    block.addProperty("blocker", label(assignment.getValue(), game, viewer));
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
        } else if (event instanceof GameEventTurnPhase e && e.phase() != null) {
            // table-live-view-1022 (owner friend table 2026-10-22T19-53-19): the step the game enters, to every seat.
            // The other person's tracker showed their previous step until its own next request. Whose turn it is,
            // which step and the turn number are public (CR 500). playerSeat is the ACTIVE player; turn is global.
            kind(result, "phase_changed");
            seat(result, game, e.playerTurn());
            result.addProperty("phase", e.phase().name());
            result.addProperty("turn", game.getPhaseHandler().getTurn());
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
     * Origins whose card was public as it left (lane block-search-1019): a graveyard is face up (CR 404.2), and a
     * permanent leaving the battlefield is revealed (CR 708.9). A face-up spell leaving the stack was public there too;
     * a face-down one is not (see {@link #zoneChanged}). Exile is not one: a card exiled face down is hidden there, and
     * the event does not say how it lay. Hand and library never are.
     */
    private static final java.util.Set<ZoneType> PUBLIC_ORIGINS =
            java.util.EnumSet.of(ZoneType.Graveyard, ZoneType.Battlefield, ZoneType.Stack);

    /**
     * {@code zone_changed} (lane bridgemeta-1018): {@code fromZone} and {@code toZone} (Forge's zone names; a zone is
     * absent when Forge names none, e.g. a token created from nowhere). That a card moved between two zones, and whose
     * zones they are ({@code fromSeat}, {@code toSeat}: the zone's player as a seat), is public.
     *
     * <p>The card's identity ({@code card}, {@code fid}, {@code ownerSeat}, {@code controllerSeat}, {@code isToken},
     * {@code isCopy}) is added when the move ends in one of {@link #IDENTITY_DESTINATIONS} and the viewer may see the
     * card there. Lane block-search-1019: also when it left a public origin ({@link #PUBLIC_ORIGINS}; a stack origin
     * only for a face-up card): its name is then public whatever the destination (Persist returning a creature from a
     * graveyard to the battlefield; a creature returned to an opponent's hand). The {@code fid} and the other facts of
     * such a move are added only when the viewer may see the card where it now is: a card returned to a hidden hand is
     * named but not given an id, so no hidden object can be tracked by it later. A draw the viewer does not see, a card
     * put into a library or exile from a hidden zone, and any other move out of a hidden zone into one the viewer
     * cannot see carry no identity at all, not even an id.</p>
     *
     * <p>No {@code playerSeat}: who moved it is not claimed, and the game log therefore leaves the event out.</p>
     */
    private static void zoneChanged(final JsonObject result, final Game game, final GameEventCardChangeZone e,
                                    final PlayerView viewer) {
        kind(result, "zone_changed");
        final ZoneType from = zoneType(e.from());
        final ZoneType to = zoneType(e.to());
        if (from != null) {
            result.addProperty("fromZone", from.name());
            zoneSeat(result, game, e.from(), "fromSeat");
        }
        if (to != null) {
            result.addProperty("toZone", to.name());
            zoneSeat(result, game, e.to(), "toSeat");
        }
        final Card moved = cardOf(game, e.card());
        Card card = to == null || !IDENTITY_DESTINATIONS.contains(to) ? null
                : publicIn(game, e.card(), to, viewer);
        if (card == null && to == ZoneType.Graveyard && inGraveyardFaceUp(moved)) {
            // a graveyard is face up (CR 404.2): during state-based actions the arriving card's view is not filled in
            // yet (a frozen tracker), so its real zone stands in for the view's
            card = moved;
        }
        final boolean publicOrigin = from != null && PUBLIC_ORIGINS.contains(from) && e.card() != null
                && !e.card().isFaceDown() && moved != null && !moved.isFaceDown();
        if (card == null && !publicOrigin) {
            return;
        }
        String name = card == null ? null : nameOf(card.getView());
        if (name == null) {
            name = nameOf(e.card()); // the leaving card's own view (filled in even while the tracker is frozen)
        }
        if (name != null) {
            result.addProperty("card", name);
        }
        if (card == null && publicOrigin && to != null) {
            // left a public zone: its name is public; its id only if the viewer may see it where it now is
            card = publicIn(game, e.card(), to, viewer);
        }
        if (card != null) {
            facts(result, game, card, null);
            seatOf(result, game, card.getController(), "controllerSeat");
        }
    }

    private static boolean inGraveyardFaceUp(final Card card) {
        return card != null && card.getZone() != null && card.getZone().getZoneType() == ZoneType.Graveyard
                && !card.isFaceDown();
    }

    private static String nameOf(final CardView view) {
        if (view == null || view.getCurrentState() == null) {
            return null;
        }
        final String name = view.getCurrentState().getName();
        return name == null || name.isBlank() ? null : name;
    }

    /** Zones whose objects' counters are public (CR 122: counters on public objects are public information). */
    private static final java.util.Set<ZoneType> PUBLIC_ZONES = java.util.EnumSet.of(ZoneType.Battlefield,
            ZoneType.Graveyard, ZoneType.Exile, ZoneType.Stack, ZoneType.Command);

    /**
     * {@code counters_changed} (lane block-search-1019): {@code fid}, {@code counter} (Forge's counter type name, e.g.
     * {@code M1M1}, {@code P1P1}, {@code LOYALTY}), {@code oldCount}, {@code newCount}, for an object in a public zone
     * ({@code zone}). A face-up object the viewer may see also gets {@code card} and its facts; a face-down permanent
     * gets its {@code fid} and counts only (its battlefield entry shows both), never a name. An object in a hidden zone,
     * or a face-down object off the battlefield, gives an event with no kind and nothing else.
     */
    private static void countersChanged(final JsonObject result, final Game game, final GameEventCardCounters e,
                                        final PlayerView viewer) {
        final Card card = cardOf(game, e.card());
        if (card == null || card.getZone() == null || e.type() == null) {
            return;
        }
        final ZoneType zone = card.getZone().getZoneType();
        if (!PUBLIC_ZONES.contains(zone)) {
            return;
        }
        final Card shown = publicIn(game, e.card(), zone, viewer);
        final boolean faceDownPermanent = shown == null && zone == ZoneType.Battlefield && card.isFaceDown()
                && card.getView() != null && card.getView().getZone() == ZoneType.Battlefield;
        if (shown == null && !faceDownPermanent) {
            return;
        }
        kind(result, "counters_changed");
        result.addProperty("zone", zone.name());
        result.addProperty("counter", e.type().toString());
        result.addProperty("oldCount", e.oldValue());
        result.addProperty("newCount", e.newValue());
        if (shown != null) {
            final String name = shown.getView().getCurrentState().getName();
            if (name != null && !name.isBlank()) {
                result.addProperty("card", name);
            }
            facts(result, game, shown, null);
        } else {
            result.addProperty("fid", card.getId());
        }
    }

    private static Card cardOf(final Game game, final CardView view) {
        if (view == null) {
            return null;
        }
        try {
            return game.findById(view.getId());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code field}: the seat of the zone's player ({@code ZoneView.player()}), when it has one. */
    private static void zoneSeat(final JsonObject result, final Game game, final ZoneView zone, final String field) {
        if (zone == null || zone.player() == null) {
            return;
        }
        int index = 0;
        for (Player registered : game.getRegisteredPlayers()) {
            if (registered.getId() == zone.player().getId()) {
                result.addProperty(field, index);
                return;
            }
            index++;
        }
    }

    private static void seatOf(final JsonObject result, final Game game, final Player player, final String field) {
        final int seat = InteractiveCardFacts.seatOf(game, player);
        if (seat >= 0) {
            result.addProperty(field, seat);
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

    private static String label(final CardView card, final Game game, final PlayerView viewer) {
        if (card == null) {
            return "a card";
        }
        // Do not attach engine IDs: an event is prose, not a selection handle.
        // A face-down card remains anonymous in the public log even to its owner.
        if (card.isFaceDown()) {
            return "a face-down card";
        }
        if (publicIn(game, card, card.getZone(), viewer) == null) {
            return "a card";
        }
        final String name = card.getCurrentState().getName();
        return name == null || name.isBlank() ? "a card" : name;
    }
}
