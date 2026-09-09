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
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;

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
        sanitizeStack(view, game, viewer);
        sanitizeCombat(view, game, viewer);
        return view;
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
            }
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
