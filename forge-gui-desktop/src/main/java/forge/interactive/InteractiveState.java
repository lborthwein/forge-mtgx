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
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * A face-down card's own face on its zone entry (lane block-search-1019), present only on the channel of a seat Forge
     * lets look at it (Thief of Sanity's exile, a "you may look at it" grant, the controller's own face-down permanent);
     * never on a watch channel.
     */
    static final String FACE_DOWN_IDENTITY = "faceDownIdentity";

    /**
     * HELD (orchestrator 2026-10-19): {@link #FACE_DOWN_IDENTITY} and the looked-at face-down label are sent only with
     * {@code -Dforge.interactive.faceDownIdentity=on}. Off (the default) until the review pipeline stops carrying a seat's
     * transcript names into the public review (mtgx #1126): a seat's own channel feeds review-game.mjs transcriptFacts,
     * then review.json, then CubeTV. Read on each call, so tests can switch it.
     */
    static boolean faceDownIdentityOn() {
        return "on".equals(System.getProperty("forge.interactive.faceDownIdentity"));
    }

    /** A player's field for the top card of their library, present only for a viewer who may see it. */
    static final String LIBRARY_TOP = "libraryTop";

    private InteractiveState() {
    }

    static JsonObject encode(final Game game, final Player human) {
        return encode(game, human, true);
    }

    /**
     * {@code lookedAt}: whether face-down cards this seat may look at carry {@link #FACE_DOWN_IDENTITY} (the seat's own
     * GUI: true; a watch channel, which a spectator replays: false).
     */
    static JsonObject encode(final Game game, final Player human, final boolean lookedAt) {
        final JsonObject view = StateEncoder.encode(game, human).deepCopy();
        // StateEncoder omits eliminated players from its positional life array.
        // Keep this wire vector indexed by the original registered seats.
        final JsonArray life = new JsonArray();
        for (Player player : game.getRegisteredPlayers()) {
            life.add(player.getLife());
        }
        view.add("life", life);
        final PlayerView viewer = human.getView();
        addEliminatedSeats(view, game, viewer);
        sanitizePlayerZones(view, game, viewer, lookedAt);
        addVisibleLibraryTops(view, game, viewer);
        sanitizeStack(view, game, viewer);
        sanitizeCombat(view, game, viewer);
        return view;
    }

    /**
     * Lane block-search-1019 (review S3): {@link StateEncoder#encode} lists in-game players only (it also feeds the
     * bridge's state and the search's payloads, so it stays as it is). A player who has lost leaves that list, and the
     * browser's terminal view showed the loser at default life, poison and zones. This view lists every registered seat
     * in seat order; an eliminated seat is encoded as the encoder encodes a player (its final public state under the
     * same visibility rules, sanitized below like every seat) and flagged {@code "lost": true}.
     */
    private static void addEliminatedSeats(final JsonObject view, final Game game, final PlayerView viewer) {
        if (!view.has("players") || !view.get("players").isJsonArray()) {
            return;
        }
        final JsonArray listed = view.getAsJsonArray("players");
        if (listed.size() >= game.getRegisteredPlayers().size()) {
            return; // nobody has left the game: the view is the encoder's, unchanged
        }
        final Map<Integer, JsonElement> byIndex = new HashMap<>();
        for (JsonElement e : listed) {
            if (e.isJsonObject() && e.getAsJsonObject().has("index")) {
                byIndex.put(e.getAsJsonObject().get("index").getAsInt(), e);
            }
        }
        final JsonArray out = new JsonArray();
        int seat = 0;
        for (Player p : game.getRegisteredPlayers()) {
            final JsonElement kept = byIndex.get(seat);
            if (kept != null) {
                out.add(kept);
            } else {
                final JsonObject lost = StateEncoder.encodePlayerFor(game, p, viewer);
                lost.addProperty("lost", true);
                out.add(lost);
            }
            seat++;
        }
        view.add("players", out);
    }

    /**
     * The view a seat's browser receives in a {@code state} message: {@link #encode} from {@code seatPlayer}'s information
     * set, its {@code seat}, and each card's and stack entry's controller/owner as a seat ({@code controllerSeat},
     * {@code ownerSeat}). The one helper for a human seat's GUI and a watch table's channels (lane forge-bot-tables-1011).
     */
    static JsonObject seatView(final Game game, final Player seatPlayer, final int seat) {
        return seatView(game, seatPlayer, seat, true);
    }

    /** As above; {@code lookedAt} false for a watch channel (no {@link #FACE_DOWN_IDENTITY}: a spectator replays it). */
    static JsonObject seatView(final Game game, final Player seatPlayer, final int seat, final boolean lookedAt) {
        final JsonObject view = encode(game, seatPlayer, lookedAt);
        view.addProperty("seat", seat);
        normalizePlayerNamespaces(game, view);
        view.addProperty(InteractiveCardFacts.FIELD, InteractiveCardFacts.VERSION);
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

    // CardView treats a missing zone as public; a frozen tracker must instead fail closed.
    static boolean mayReceiveIdentity(final CardView card, final PlayerView viewer) {
        return card != null && card.getZone() != null && card.canBeShownTo(viewer)
                && (!card.isFaceDown() || card.canFaceDownBeShownTo(viewer));
    }

    static String safeCardLabel(final CardView card, final PlayerView viewer) {
        if (card == null) {
            return "Unknown card";
        }
        if (!mayReceiveIdentity(card, viewer)) {
            return "Face-down card (" + card.getId() + ")";
        }
        // block-search-1019: a face-down card this viewer may look at (Thief of Sanity's exile) has no name in its
        // face-down state, so the label read "Card (73)"; name it by its original face, as the desktop GUI shows it
        return card.isFaceDown() && faceDownIdentityOn() ? lookedAtCardLabel(card) : visibleCardLabel(card);
    }

    /** Only after {@link #mayReceiveIdentity} for the viewer: a face-down card by its original face's name. */
    private static String lookedAtCardLabel(final CardView card) {
        final CardView.CardStateView original = card.getAlternateState();
        final String name = original == null || original.getState() != CardStateName.Original ? null : original.getName();
        return name == null || name.isBlank() ? visibleCardLabel(card) : name + " (" + card.getId() + ")";
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
                                            final PlayerView viewer, final boolean lookedAt) {
        if (!view.has("players") || !view.get("players").isJsonArray()) {
            return;
        }
        for (JsonElement player : view.getAsJsonArray("players")) {
            if (!player.isJsonObject()) {
                continue;
            }
            final JsonObject object = player.getAsJsonObject();
            for (String zone : ListNames.CARD_ZONES) {
                sanitizeCardArray(object.get(zone), game, viewer, zone, lookedAt);
            }
            addHiddenExileShells(object, game);
        }
    }

    /**
     * Lane block-search-1019: a face-down exiled card the viewer may not look at is not listed at all
     * ({@link StateEncoder} omits it, {@link #sanitizeCardArray} drops an unverified one), so the viewer's exile count
     * was short (the owner of a card Thief of Sanity exiled saw one card fewer). Each such card gets a redacted shell
     * in zone order: the public face-down fields only ({@link #redactPrivateCard}), never its identity.
     */
    private static void addHiddenExileShells(final JsonObject playerObject, final Game game) {
        if (!playerObject.has("index") || !playerObject.has("exile") || !playerObject.get("exile").isJsonArray()) {
            return;
        }
        final int index = playerObject.get("index").getAsInt();
        if (index < 0 || index >= game.getRegisteredPlayers().size()) {
            return;
        }
        final Player owner = game.getRegisteredPlayers().get(index);
        final JsonArray listed = playerObject.getAsJsonArray("exile");
        final java.util.Map<Integer, JsonElement> byFid = new java.util.HashMap<>();
        for (JsonElement e : listed) {
            if (e.isJsonObject() && e.getAsJsonObject().has("fid") && e.getAsJsonObject().get("fid").isJsonPrimitive()) {
                byFid.put(e.getAsJsonObject().get("fid").getAsInt(), e);
            }
        }
        final JsonArray out = new JsonArray();
        boolean added = false;
        for (Card c : owner.getCardsIn(forge.game.zone.ZoneType.Exile)) {
            final JsonElement kept = byFid.remove(c.getId());
            if (kept != null) {
                out.add(kept);
            } else if (c.isFaceDown()) {
                final JsonObject shell = StateEncoder.encodeCardUnchecked(c);
                redactPrivateCard(shell);
                out.add(shell);
                added = true;
            }
        }
        if (!added) {
            return;
        }
        for (JsonElement rest : byFid.values()) {
            out.add(rest); // never seen: an entry not in the zone any more stays as the encoder listed it
        }
        playerObject.add("exile", out);
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
            if (top != null && !top.isFaceDown() && mayReceiveLibraryTop(top.getView(), viewer)
                    && !searchLookOnly(game, registered.get(index), top, viewer)) {
                final JsonObject shown = StateEncoder.encodeCardUnchecked(top);
                InteractiveCardFacts.addTokenAndCopy(shown, top);
                player.add(LIBRARY_TOP, shown);
            }
        }
    }

    /**
     * Is the viewer's only look at this library top the one a library search gives (afford-jar-1021, the owner's
     * report 2026-10-21T16-56-19: "Top: Narset" mid-search, swampcycling and Expedition Map)? A search shows the
     * searcher the library, or its matching cards, through Forge's temporary look ({@code tempShowCards}); that never
     * makes the top card itself known, and the library is shuffled afterwards. The top is suppressed when the viewer's
     * permission on it is temporary only and either (a) the whole library is temporarily visible to the viewer (the
     * search's reveal), or (b) the resolving ability's chain moves cards out of a library without naming them (a
     * search, cycling's landcycling included). A lasting permission (Courser of Kruphix, Bolas's Citadel) still shows
     * it, and so does a temporary look at the top cards alone (scry, surveil, look at the top N).
     */
    static boolean searchLookOnly(final Game game, final Player owner, final Card top, final PlayerView viewer) {
        if (viewer == null) {
            return false;
        }
        final Player looker;
        try {
            looker = game.getPlayer(viewer);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (looker == null || !top.mayLookOnlyTemporarily(looker)) {
            return false;
        }
        boolean wholeLibrary = true;
        for (Card card : owner.getCardsIn(ZoneType.Library)) {
            if (!card.getView().mayPlayerLook(viewer)) {
                wholeLibrary = false;
                break;
            }
        }
        if (wholeLibrary) {
            return true;
        }
        if (game.getStack().isResolving() && !game.getStack().isEmpty()) {
            for (forge.game.spellability.SpellAbility sa = game.getStack().peekAbility(); sa != null;
                 sa = sa.getSubAbility()) {
                if (searchesALibrary(sa)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean searchesALibrary(final forge.game.spellability.SpellAbility sa) {
        final forge.game.ability.ApiType api = sa.getApi();
        return (api == forge.game.ability.ApiType.ChangeZone || api == forge.game.ability.ApiType.ChangeZoneAll)
                && sa.getParamOrDefault("Origin", "").contains("Library") && !sa.hasParam("Defined");
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
        final Map<Integer, SpellAbilityStackInstance> instances = new HashMap<>();
        for (SpellAbilityStackInstance si : game.getStack()) {
            instances.put(si.getId(), si);
        }
        for (JsonElement element : view.getAsJsonArray("stack")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject entry = element.getAsJsonObject();
            final Card source = visibleCardForFid(entry, "fid", game, viewer);
            boolean renderedTextSafe = source != null;
            if (!renderedTextSafe) {
                entry.addProperty("name", "Face-down spell or ability");
            } else if (!source.isFaceDown()) {
                // Facts exclude face-down sources even when their owner may inspect their identity.
                final SpellAbilityStackInstance si = instanceOf(entry, instances);
                InteractiveCardFacts.addOwnerTokenAndCopy(entry, game, source,
                        si == null ? null : si.getSpellAbility());
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
                                          final PlayerView viewer, final String zone, final boolean lookedAt) {
        if (value == null || !value.isJsonArray()) {
            return;
        }
        for (var entries = value.getAsJsonArray().iterator(); entries.hasNext();) {
            final JsonElement element = entries.next();
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject cardObject = element.getAsJsonObject();
            final Card card = visibleCardForFid(cardObject, "fid", game, viewer);
            if (card == null) {
                // A hidden-zone entry cannot even expose an id when visibility is unverified.
                if ("hand".equals(zone) || "exile".equals(zone)) {
                    entries.remove();
                    continue;
                }
                redactPrivateCard(cardObject);
            } else {
                addNamedCards(cardObject, game);
                // Looking at an owned face-down card does not authorize the new provenance facts.
                if (!card.isFaceDown()) {
                    InteractiveCardFacts.addTokenAndCopy(cardObject, card);
                } else if (lookedAt && faceDownIdentityOn()) {
                    // block-search-1019: Forge lets this viewer look at the face-down card (visibleCardForFid: its
                    // may-look rule); its own face, for this seat's channel only
                    addFaceDownIdentity(cardObject, card);
                }
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

    /**
     * {@link #FACE_DOWN_IDENTITY} (owner's report 2026-10-19T21-15-50: "I just exiled Karn with Thief of Sanity, but I
     * don't see it anywhere"): the printed face of a face-down card the viewer may look at: name, type line, mana cost,
     * mana value, base P/T (creatures). The entry's own fields stay the face-down state's. Called only for an entry
     * {@link #sanitizeCardArray} admitted for this viewer by Forge's may-look rule, and only on a seat's own channel.
     */
    private static void addFaceDownIdentity(final JsonObject cardObject, final Card card) {
        final CardState original = card.getState(CardStateName.Original);
        if (original == null || original.getName() == null || original.getName().isBlank()) {
            return;
        }
        final JsonObject identity = new JsonObject();
        identity.addProperty("name", original.getName());
        identity.addProperty("types", original.getType().toString());
        identity.addProperty("manaCost", String.valueOf(original.getManaCost()));
        identity.addProperty("cmc", original.getManaCost().getCMC());
        if (original.getType().isCreature()) {
            identity.addProperty("basePower", original.getBasePower());
            identity.addProperty("baseToughness", original.getBaseToughness());
        }
        cardObject.add(FACE_DOWN_IDENTITY, identity);
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
        return visibleCardForFid(object, field, game, viewer) != null;
    }

    /** The card {@code object[field]} names, when the viewer may receive its identity; otherwise null. */
    private static Card visibleCardForFid(final JsonObject object, final String field,
                                          final Game game, final PlayerView viewer) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            return null;
        }
        try {
            final Card card = game.findById(object.get(field).getAsInt());
            return card != null && card.getZone() != null
                    && card.getView().getZone() == card.getZone().getZoneType()
                    && mayReceiveIdentity(card.getView(), viewer) ? card : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The stack instance a {@code stack[]} entry's {@code id} names, or null. */
    private static SpellAbilityStackInstance instanceOf(final JsonObject entry,
                                                        final Map<Integer, SpellAbilityStackInstance> instances) {
        if (!entry.has("id") || !entry.get("id").isJsonPrimitive()) {
            return null;
        }
        try {
            return instances.get(entry.get("id").getAsInt());
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
