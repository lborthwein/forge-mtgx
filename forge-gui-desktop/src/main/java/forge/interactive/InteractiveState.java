/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.Set;

/**
 * Produces a browser payload from the human seat's information set.
 *
 * <p>{@link StateEncoder} already omits opposing hands and both libraries, but its public
 * encoder intentionally describes battlefield and stack cards without applying Forge's
 * separate face-down identity predicate. A native GUI receives rich views and hides those
 * fields at render time. An untrusted browser must never receive them, so this final pass
 * removes private face characteristics and unsafe rendered stack strings.</p>
 */
final class InteractiveState {
    private static final Set<String> PUBLIC_FACE_DOWN_FIELDS = Set.of(
            "fid", "zone", "controller", "owner", "tapped", "faceDown", "sick",
            "power", "toughness", "damage", "counters", "attachments", "attachedTo",
            "attachedToKind");

    /** A player's field for the top card of their library, present only for a viewer who may see it. */
    static final String LIBRARY_TOP = "libraryTop";

    private InteractiveState() {
    }

    static JsonObject encode(final Game game, final Player human) {
        final JsonObject view = StateEncoder.encode(game, human).deepCopy();
        // StateEncoder omits eliminated players from its positional life array.
        // Keep this wire vector indexed by the original registered seats.
        final JsonArray life = new JsonArray();
        for (Player player : game.getRegisteredPlayers()) {
            life.add(player.getLife());
        }
        view.add("life", life);
        final PlayerView viewer = human.getView();
        sanitizePlayerZones(view, game, viewer);
        addVisibleLibraryTops(view, game, viewer);
        sanitizeStack(view, game, viewer);
        sanitizeCombat(view, game, viewer);
        return view;
    }

    /**
     * The view a seat's browser receives in a {@code state} message: {@link #encode} from {@code seatPlayer}'s information
     * set, its {@code seat}, and each card's and stack entry's controller/owner as a seat ({@code controllerSeat},
     * {@code ownerSeat}). The one helper for a human seat's GUI and a watch table's channels (lane forge-bot-tables-1011).
     */
    static JsonObject seatView(final Game game, final Player seatPlayer, final int seat) {
        final JsonObject view = encode(game, seatPlayer);
        view.addProperty("seat", seat);
        normalizePlayerNamespaces(game, view);
        return view;
    }

    private static void normalizePlayerNamespaces(final Game game, final JsonObject view) {
        if (view.has("players") && view.get("players").isJsonArray()) {
            for (JsonElement playerElement : view.getAsJsonArray("players")) {
                if (!playerElement.isJsonObject()) {
                    continue;
                }
                final JsonObject player = playerElement.getAsJsonObject();
                for (String zone : java.util.List.of("hand", "battlefield", "graveyard", "exile", "command")) {
                    addCardSeatFields(game, player.get(zone));
                }
                if (player.has(LIBRARY_TOP) && player.get(LIBRARY_TOP).isJsonObject()) {
                    final JsonObject top = player.getAsJsonObject(LIBRARY_TOP);
                    addSeatForRawPlayerId(game, top, "controller", "controllerSeat");
                    addSeatForRawPlayerId(game, top, "owner", "ownerSeat");
                }
            }
        }
        if (view.has("stack") && view.get("stack").isJsonArray()) {
            for (JsonElement entry : view.getAsJsonArray("stack")) {
                if (entry.isJsonObject()) {
                    addSeatForRawPlayerId(game, entry.getAsJsonObject(), "controller", "controllerSeat");
                }
            }
        }
    }

    private static void addCardSeatFields(final Game game, final JsonElement zone) {
        if (zone == null || !zone.isJsonArray()) {
            return;
        }
        for (JsonElement element : zone.getAsJsonArray()) {
            if (element.isJsonObject()) {
                addSeatForRawPlayerId(game, element.getAsJsonObject(), "controller", "controllerSeat");
                addSeatForRawPlayerId(game, element.getAsJsonObject(), "owner", "ownerSeat");
            }
        }
    }

    private static void addSeatForRawPlayerId(final Game game, final JsonObject object, final String rawField,
                                              final String seatField) {
        if (!object.has(rawField) || !object.get(rawField).isJsonPrimitive()) {
            return;
        }
        final int rawId;
        try {
            rawId = object.get(rawField).getAsInt();
        } catch (RuntimeException ignored) {
            return;
        }
        int seat = 0;
        for (Player player : game.getRegisteredPlayers()) {
            if (player.getId() == rawId) {
                object.addProperty(seatField, seat);
                return;
            }
            seat++;
        }
    }

    static boolean mayReceiveIdentity(final CardView card, final PlayerView viewer) {
        return card != null && card.canBeShownTo(viewer)
                && (!card.isFaceDown() || card.canFaceDownBeShownTo(viewer));
    }

    static String safeCardLabel(final CardView card, final PlayerView viewer) {
        if (card == null) {
            return "Unknown card";
        }
        return mayReceiveIdentity(card, viewer)
                ? visibleCardLabel(card)
                : "Face-down card (" + card.getId() + ")";
    }

    static String visibleCardLabel(final CardView card) {
        if (card == null) {
            return "Unknown card";
        }
        final String name = card.getName();
        return (name == null || name.isBlank() ? "Card" : name)
                + " (" + card.getId() + ")";
    }

    private static void sanitizePlayerZones(final JsonObject view, final Game game,
                                            final PlayerView viewer) {
        if (!view.has("players") || !view.get("players").isJsonArray()) {
            return;
        }
        for (JsonElement player : view.getAsJsonArray("players")) {
            if (!player.isJsonObject()) {
                continue;
            }
            final JsonObject object = player.getAsJsonObject();
            for (String zone : ListNames.CARD_ZONES) {
                sanitizeCardArray(object.get(zone), game, viewer);
            }
        }
    }

    /**
     * The top card of each library, as {@code players[i].libraryTop}, for a viewer Forge lets see it (lane
     * forge-top-card-reveal-1015). The owner's report 2026-10-14T04-17-09, with Courser of Kruphix in play: "I can't
     * see the top card of my deck, but I have a card in play that says I can!" {@link StateEncoder} enumerates no
     * library, so the browser was never told what Courser reveals.
     *
     * <p>Who may see it is Forge's own rule: the {@code MayLookAt$} of the granting static ability, which Forge keeps
     * as the card's may-look set. {@code Player} (Courser of Kruphix, Oracle of Mul Daya, Future Sight, Magus of the
     * Future: "play with the top card of your library revealed") reaches every seat; {@code You} (Bolas's Citadel,
     * Mystic Forge, Augur of Autumn, Vizier of the Menagerie: "you may look at the top card of your library any
     * time") reaches only its controller. A seat Forge does not let look gets no field at all, so not even the card's
     * id leaves the JVM.</p>
     *
     * <p>The card's view must also say it is in the library. A card that moved while the tracker was frozen has no
     * zone on its view yet, and {@link CardView#canBeShownTo} reads a zoneless card as visible to all.</p>
     *
     * <p>Only this browser payload carries the field. {@link StateEncoder} (the bench and RL observations) still
     * enumerates no library, and the name is not {@code revealedTop}, which the bench decoders read.</p>
     */
    private static void addVisibleLibraryTops(final JsonObject view, final Game game, final PlayerView viewer) {
        if (!view.has("players") || !view.get("players").isJsonArray()) {
            return;
        }
        final List<Player> registered = game.getRegisteredPlayers();
        for (JsonElement element : view.getAsJsonArray("players")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject player = element.getAsJsonObject();
            if (!player.has("index") || !player.get("index").isJsonPrimitive()) {
                continue;
            }
            final int index;
            try {
                index = player.get("index").getAsInt();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (index < 0 || index >= registered.size()) {
                continue;
            }
            final Card top = libraryTop(registered.get(index));
            if (top != null && mayReceiveLibraryTop(top.getView(), viewer)) {
                player.add(LIBRARY_TOP, StateEncoder.encodeCardUnchecked(top));
            }
        }
    }

    /** The card a player would draw next, or null for an empty library. Index 0 is the top in Forge. */
    static Card libraryTop(final Player owner) {
        final CardCollectionView library = owner.getCardsIn(ZoneType.Library);
        return library.isEmpty() ? null : library.get(0);
    }

    /** Forge's may-look rule for a library card, on a view that already knows the card is in the library. */
    static boolean mayReceiveLibraryTop(final CardView card, final PlayerView viewer) {
        return card != null && card.getZone() == ZoneType.Library && mayReceiveIdentity(card, viewer);
    }

    private static void sanitizeStack(final JsonObject view, final Game game,
                                      final PlayerView viewer) {
        if (!view.has("stack") || !view.get("stack").isJsonArray()) {
            return;
        }
        for (JsonElement element : view.getAsJsonArray("stack")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject entry = element.getAsJsonObject();
            boolean renderedTextSafe = identityVisibleForFid(entry, "fid", game, viewer);
            if (!renderedTextSafe) {
                entry.addProperty("name", "Face-down spell or ability");
            }

            // Forge's TargetChoices#toString and stack description contain raw engine names.
            // Structured IDs remain useful, but a rendered string cannot be selectively masked.
            entry.remove("targets");
            if (entry.has("targetsDetail") && entry.get("targetsDetail").isJsonArray()) {
                for (JsonElement target : entry.getAsJsonArray("targetsDetail")) {
                    if (!target.isJsonObject()) {
                        continue;
                    }
                    final JsonObject targetObject = target.getAsJsonObject();
                    if ("card".equals(stringValue(targetObject, "kind"))) {
                        final boolean visible = identityVisibleForFid(targetObject,
                                targetObject.has("fid") ? "fid" : "id", game, viewer);
                        if (!visible) {
                            redactPrivateCard(targetObject);
                            renderedTextSafe = false;
                        }
                    }
                }
            }
            if (entry.has("targetSpells") && entry.get("targetSpells").isJsonArray()) {
                for (JsonElement target : entry.getAsJsonArray("targetSpells")) {
                    if (!target.isJsonObject()) {
                        continue;
                    }
                    final JsonObject targetObject = target.getAsJsonObject();
                    if (!identityVisibleForFid(targetObject, "fid", game, viewer)) {
                        targetObject.addProperty("name", "Face-down spell or ability");
                        renderedTextSafe = false;
                    }
                }
            }
            if (!renderedTextSafe) {
                entry.addProperty("description", "Hidden spell or ability");
            }
        }
    }

    private static void sanitizeCombat(final JsonObject view, final Game game,
                                       final PlayerView viewer) {
        if (!view.has("combat") || !view.get("combat").isJsonObject()) {
            return;
        }
        final JsonObject combat = view.getAsJsonObject("combat");
        if (!combat.has("attackers") || !combat.get("attackers").isJsonArray()) {
            return;
        }
        for (JsonElement element : combat.getAsJsonArray("attackers")) {
            if (element.isJsonObject()
                    && !identityVisibleForFid(element.getAsJsonObject(), "fid", game, viewer)) {
                element.getAsJsonObject().addProperty("name", "Face-down attacker");
            }
        }
    }

    private static void sanitizeCardArray(final JsonElement value, final Game game,
                                          final PlayerView viewer) {
        if (value == null || !value.isJsonArray()) {
            return;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject cardObject = element.getAsJsonObject();
            if (!identityVisibleForFid(cardObject, "fid", game, viewer)) {
                redactPrivateCard(cardObject);
            } else {
                addNamedCards(cardObject, game);
            }
        }
    }

    /**
     * The card names this object's "choose a card name" chose (Phyrexian Revoker, Pithing Needle,
     * Meddling Mage ...), as {@code namedCards}. The choice is announced to every player, and Forge's
     * desktop shows it on the card, but the wire carried none of it: the owner's Oko was shut off by
     * a Revoker whose choice nothing on the table showed (report 2026-10-06T01-51-13). Only on an
     * object whose identity the viewer may already receive; the bench StateEncoder is untouched.
     */
    private static void addNamedCards(final JsonObject cardObject, final Game game) {
        if (!cardObject.has("fid") || !cardObject.get("fid").isJsonPrimitive()) {
            return;
        }
        try {
            final Card card = game.findById(cardObject.get("fid").getAsInt());
            final java.util.List<String> chosen = card == null ? null : card.getNamedCards();
            if (chosen == null || chosen.isEmpty()) {
                return;
            }
            final JsonArray names = new JsonArray();
            for (String name : chosen) {
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                cardObject.add("namedCards", names);
            }
        } catch (RuntimeException e) {
            // An id that does not resolve names nothing.
        }
    }

    static void redactPrivateCard(final JsonObject cardObject) {
        final JsonObject publicShell = new JsonObject();
        for (String field : PUBLIC_FACE_DOWN_FIELDS) {
            if (cardObject.has(field)) {
                publicShell.add(field, cardObject.get(field).deepCopy());
            }
        }
        publicShell.addProperty("name", "Face-down card");
        publicShell.addProperty("identityRedacted", true);
        cardObject.entrySet().clear();
        for (var entry : publicShell.entrySet()) {
            cardObject.add(entry.getKey(), entry.getValue());
        }
    }

    private static boolean identityVisibleForFid(final JsonObject object, final String field,
                                                 final Game game, final PlayerView viewer) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            return false;
        }
        try {
            final Card card = game.findById(object.get(field).getAsInt());
            return card != null && mayReceiveIdentity(card.getView(), viewer);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String stringValue(final JsonObject object, final String field) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                ? object.get(field).getAsString() : "";
    }

    private static final class ListNames {
        private static final String[] CARD_ZONES = {
                "hand", "battlefield", "graveyard", "exile", "command"
        };

        private ListNames() {
        }
    }
}
