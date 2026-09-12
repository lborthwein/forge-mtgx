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

import forge.ImageKeys;
import forge.bench.StateEncoder;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.item.IPaperCard;
import forge.item.PaperToken;

import java.util.Set;

/**
 * Produces a browser payload from the human seat's information set.
 *
 * <p>{@link StateEncoder} already omits opposing hands and both libraries, but its public
 * encoder intentionally describes battlefield and stack cards without applying Forge's
 * separate face-down identity predicate. A native GUI receives rich views and hides those
 * fields at render time. An untrusted browser must never receive them, so this final pass
 * removes private face characteristics and unsafe rendered stack strings.</p>
 *
 * <p>The same pass adds, to zone card objects whose identity the viewer may receive and
 * that are not face-down, the current {@code colors}, {@code basePower},
 * {@code baseToughness}, {@code isToken} and {@code tokenScript}
 * ({@link #addPublicCharacteristics}). These live here rather than in
 * {@link StateEncoder} so the bench wire used by bridged seats is unchanged.</p>
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
            final Card visible = identityVisibleCard(cardObject, "fid", game, viewer);
            if (visible == null) {
                redactPrivateCard(cardObject);
            } else {
                addPublicCharacteristics(cardObject, visible);
            }
        }
    }

    /**
     * Current characteristics a browser needs to render an object whose identity is
     * already disclosed: {@code colors}, {@code basePower}/{@code baseToughness},
     * {@code isToken} and {@code tokenScript}.
     *
     * <p>A token has no catalog entry, so without these a white Soldier and an artifact
     * Soldier, or a 0/0 and a 4/4 Construct, are indistinguishable to the client, and
     * abilities that key off colour cannot be read from the table.</p>
     *
     * <p>Called only on the identity-visible branch of {@link #sanitizeCardArray}; the
     * redacted branch keeps {@link #PUBLIC_FACE_DOWN_FIELDS} only, which excludes all
     * four keys. A face-down object gains nothing even when its controller may look at
     * it: the wire's face-down contract is the redacted shell, and a face-down 2/2 must
     * never carry a colour or token flag. On an unexpected engine failure the keys are
     * omitted together (absent = this jar cannot say, as with an older jar) rather than
     * half-written.</p>
     *
     * <p>Read-only: every accessor below is a getter over the current state. In particular
     * {@link PaperToken#getImageKey(int)} is used instead of {@code getImageKey(boolean)},
     * which draws an art index from Forge's shared {@code MyRandom} and would perturb a
     * seeded game.</p>
     */
    static void addPublicCharacteristics(final JsonObject cardObject, final Card card) {
        if (card == null || card.isFaceDown()) {
            return;
        }
        final JsonObject added = new JsonObject();
        try {
            // Current colour: Card#getColor applies text-changing, characteristic-defining
            // and ordinary colour-setting effects over the current state's colour.
            final ColorSet color = card.getColor();
            final JsonArray colors = new JsonArray();
            for (final byte c : MagicColor.WUBRG) {
                if (color.hasAnyColor(c)) {
                    colors.add(MagicColor.toShortString(c));
                }
            }
            added.add("colors", colors);
            // Base P/T of the current state (the token's defined P/T, including a
            // TokenPower-resolved X): no counters, pumps, anthems, or layer-7a/7b
            // P/T-defining/setting effects, which Forge tracks outside the state's base.
            // Net P/T stays in the existing power/toughness keys.
            final boolean creature = card.isCreature();
            added.addProperty("basePower", creature ? (Integer) card.getBasePower() : null);
            added.addProperty("baseToughness", creature ? (Integer) card.getBaseToughness() : null);
            final boolean token = card.isToken();
            added.addProperty("isToken", token);
            added.addProperty("tokenScript", token ? tokenScript(card) : null);
            /*
             * The room a dungeon is standing in, by its printed name. A dungeon
             * is a public command-zone object whose whole state is which room
             * is current, and without it the browser can draw the map but not
             * where anyone is on it — the owner had to know the Undercity by
             * heart. Empty for everything that is not a dungeon.
             */
            final String room = card.getCurrentRoom();
            if (room != null && !room.isEmpty()) {
                added.addProperty("currentRoom", room);
            }
        } catch (RuntimeException e) {
            System.err.println("[forge.interactive] characteristic encoding failed for card "
                    + card.getId() + ": " + e);
            return;
        }
        for (var entry : added.entrySet()) {
            cardObject.add(entry.getKey(), entry.getValue());
        }
    }

    /**
     * The Forge token script the token was created from (e.g. {@code w_1_1_spirit_flying}),
     * or null when the object is not a scripted token (a token copy of a nontoken card has
     * that card's paper card; a scripted token copied by Populate or a copy effect keeps
     * the original's). Read from the {@link PaperToken}'s image file name, whose first
     * {@code |} segment is exactly the script key {@code TokenDb#getToken} was asked for;
     * the token rules' normalized name (the script file name) is the fallback. It is
     * provenance, not current identity: a token that later becomes a copy of something
     * else keeps the script it was created from.
     */
    static String tokenScript(final Card card) {
        final IPaperCard paper = card.getPaperCard();
        if (!(paper instanceof PaperToken)) {
            return null;
        }
        final PaperToken token = (PaperToken) paper;
        try {
            String key = token.getImageKey(0);
            if (key != null && key.startsWith(ImageKeys.TOKEN_PREFIX)) {
                key = key.substring(ImageKeys.TOKEN_PREFIX.length());
            }
            final int bar = key == null ? -1 : key.indexOf('|');
            final String script = bar >= 0 ? key.substring(0, bar) : key;
            if (script != null && !script.isBlank()) {
                return script;
            }
        } catch (RuntimeException e) {
            // no recorded image file name; fall through to the rules name
        }
        final CardRules rules = token.getRules();
        final String normalized = rules == null ? null : rules.getNormalizedName();
        return normalized == null || normalized.isBlank() ? null : normalized;
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
        return identityVisibleCard(object, field, game, viewer) != null;
    }

    /** The card named by {@code field} if its identity may be shown to {@code viewer}, else null. */
    private static Card identityVisibleCard(final JsonObject object, final String field,
                                            final Game game, final PlayerView viewer) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            return null;
        }
        try {
            final Card card = game.findById(object.get(field).getAsInt());
            return card != null && mayReceiveIdentity(card.getView(), viewer) ? card : null;
        } catch (RuntimeException e) {
            return null;
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
