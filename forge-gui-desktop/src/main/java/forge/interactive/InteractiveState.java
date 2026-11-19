/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.bench.StateEncoder;
import forge.card.CardStateName;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
        addPlayerCounters(view, game);
        sanitizePlayerZones(view, game, viewer, lookedAt);
        addVisibleLibraryTops(view, game, viewer);
        sanitizeStack(view, game, viewer);
        sanitizeCombat(view, game, viewer);
        addBlockedWithoutBlockers(view, game);
        return view;
    }

    /**
     * combat-damage-view-1111: an attacker that became blocked stays blocked for the rest of combat after every blocker
     * has left combat (CR 509.1h), for example after dying to first-strike damage. It then deals no combat damage unless
     * it has trample. {@code blockedBy} is empty then, so the client's hit preview read it as unblocked. This marks
     * exactly that attacker {@code "blocked": true}. Any other attacker's entry is unchanged, because a non-empty
     * {@code blockedBy} already says blocked. Only the interactive wire gets it; the shared StateEncoder that bench and
     * training read doesn't.
     */
    static void addBlockedWithoutBlockers(final JsonObject view, final Game game) {
        final forge.game.combat.Combat combat = game.getCombat();
        if (combat == null || !view.has("combat") || !view.get("combat").isJsonObject()) {
            return;
        }
        final JsonObject wire = view.getAsJsonObject("combat");
        if (!wire.has("attackers") || !wire.get("attackers").isJsonArray()) {
            return;
        }
        for (JsonElement element : wire.getAsJsonArray("attackers")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject attacker = element.getAsJsonObject();
            final JsonElement blockedBy = attacker.get("blockedBy");
            if (!attacker.has("fid") || (blockedBy != null && blockedBy.isJsonArray() && blockedBy.getAsJsonArray().size() > 0)) {
                continue;
            }
            final Card card;
            try {
                card = game.findById(attacker.get("fid").getAsInt());
            } catch (RuntimeException e) {
                continue;
            }
            if (card != null && combat.isBlocked(card) && combat.getBlockers(card).isEmpty()) {
                attacker.addProperty("blocked", true);
            }
        }
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

    /** A player's field for the counters that player has (energy, experience, poison, rad, tickets, ...). */
    static final String PLAYER_COUNTERS = "counters";

    /**
     * Lane player-counters-1107 (owner, 2026-11-07: "Where can I see my energy counter total?"): every counter on a
     * player, as Forge holds it, keyed the way a card's {@code counters} are keyed ({@code {"ENERGY": 3}}). Generic: no
     * counter kind is named, so experience, rad, tickets and poison (also sent as the scalar {@code poison}) all arrive.
     * Player counters are public. Omitted when the player has none, so a game in which no player ever gets a counter sends
     * the same bytes as before. Added here and not in {@link StateEncoder}, whose players also feed the bridge's state,
     * the RL featurizer and the search's payloads; those stay as they are. Keys are sorted, so the bytes do not depend on
     * the counter multiset's hash order.
     */
    private static void addPlayerCounters(final JsonObject view, final Game game) {
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
            final Map<String, Integer> sorted = new TreeMap<>();
            for (Multiset.Entry<CounterType> e : registered.get(index).getCounters().entrySet()) {
                if (e.getCount() > 0) {
                    sorted.merge(e.getElement().toString(), e.getCount(), Integer::sum);
                }
            }
            if (sorted.isEmpty()) {
                continue;
            }
            final JsonObject counters = new JsonObject();
            for (Map.Entry<String, Integer> e : sorted.entrySet()) {
                counters.addProperty(e.getKey(), e.getValue());
            }
            player.add(PLAYER_COUNTERS, counters);
        }
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
    // One zoneless object is public by the rules: a spell copy (isZonelessSpellCopy). It is judged as the stack judges
    // it, except that a face-down copy fails closed for every viewer.
    static boolean mayReceiveIdentity(final CardView card, final PlayerView viewer) {
        if (card == null || viewer == null) {
            return false;
        }
        if (card.getZone() == null) {
            return isZonelessSpellCopy(card) && !card.isFaceDown();
        }
        return card.canBeShownTo(viewer) && (!card.isFaceDown() || card.canFaceDownBeShownTo(viewer));
    }

    /**
     * A copy of a spell in no zone (lane facedown-label-1021, owner report 2026-10-21T17-36-59). A spell copy is
     * created on the stack (CR 707.10), but Forge builds each copy with no zone (CardFactory.copySpellHost) and asks
     * its controller to order several copies (orderAndPlaySimultaneousSa) before it puts them there; a copy that has
     * left the stack has ceased to exist (CR 707.10a) and is zoneless too. Either way its identity is that of a spell
     * on the stack, public to every seat. Failing it closed labelled the seat's own Brain Freeze storm copies "Ability
     * of face-down card (87)". Only Forge's COPIED_SPELL piece type qualifies: a card the tracker left zoneless (a
     * frozen tracker's hand card) has no such type and still fails closed.
     */
    static boolean isZonelessSpellCopy(final CardView card) {
        return card != null && card.getZone() == null && card.getGamePieceType() == GamePieceType.COPIED_SPELL;
    }

    /**
     * The printed rules text of a card a viewer may identify, both faces (lane facedown-label-1021), or null. Call it
     * only after {@link #mayReceiveIdentity}. A face-down card gives null: its other face is the hidden one.
     */
    static String printedText(final CardView card) {
        if (card == null || card.isFaceDown()) {
            return null;
        }
        final StringBuilder text = new StringBuilder();
        for (CardView.CardStateView state : new CardView.CardStateView[] {card.getCurrentState(), card.getAlternateState()}) {
            final String oracle = state == null ? null : state.getOracleText();
            if (oracle != null && !oracle.isBlank()) {
                text.append(text.length() == 0 ? "" : "\n").append(oracle);
            }
        }
        return text.length() == 0 ? null : text.toString();
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
        final HiddenNames hidden = view.getAsJsonArray("stack").isEmpty() ? null : HiddenNames.of(game, viewer);
        for (JsonElement element : view.getAsJsonArray("stack")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject entry = element.getAsJsonObject();
            final SpellAbilityStackInstance si = instanceOf(entry, instances);
            Card source = visibleCardForFid(entry, "fid", game, viewer);
            if (source == null) {
                source = ceasedSource(entry, si, game);
            }
            boolean renderedTextSafe = source != null;
            if (!renderedTextSafe) {
                entry.addProperty("name", "Face-down spell or ability");
            } else if (!source.isFaceDown()) {
                // Facts exclude face-down sources even when their owner may inspect their identity.
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
                nameEffectSource(entry, source, game, viewer);
            } else if (si != null && entry.has("description") && entry.get("description").isJsonPrimitive()) {
                // lane trigger-label-1119 (review fix 2): all or nothing, decided now from the stack item's ability and
                // the objects it references; the text stored at push is shown only when every one passes.
                final SpellAbility sa = si.getSpellAbility();
                final AbilityCheck check = checkAbility(sa, source.getView(), game, viewer);
                if (!check.allKnown()) {
                    entry.addProperty("name", check.origin());
                    entry.addProperty("description", check.minimalLabel(sa));
                } else {
                    final String description = entry.get("description").getAsString();
                    final String clean = fullText(description, sa, check.effects());
                    if (!clean.equals(description)) entry.addProperty("description", clean);
                    nameEffectSource(entry, source, game, viewer);
                }
            } else {
                nameEffectSource(entry, source, game, viewer);
            }
            // Review fix 3: the final sink check, the authority over every string of the entry.
            sinkStackEntry(entry, si, source, game, viewer, hidden);
        }
    }

    /**
     * lane trigger-label-1119 (owner report 2026-11-19T05-23-42: the monarch's draw to order read "At the beginning of
     * your end step, draw a card. [Phase: You]"). Forge's {@code WrappedAbility.getStackDescription} appends a trigger's
     * "important stack objects" to its text as {@code " [" + Trigger.getImportantStackObjects + "]"}: the phase's player,
     * the card that changed zones, and the like. It is the engine's context, not the card's words; Forge's own order memory
     * cuts it too ({@code PlayerControllerHuman.trimStackContext}). This cuts exactly that suffix, computed from the
     * trigger, when {@code text} ends with it; any other text, and any ability but a trigger, is returned unchanged. The
     * stored text of a stack entry ends with it as well, after its targets.
     */
    static String withoutTriggerContext(final String text, final SpellAbility ability) {
        if (text == null || !(ability instanceof forge.game.trigger.WrappedAbility wrapped) || wrapped.getTrigger() == null) {
            return text;
        }
        final String important;
        try {
            important = wrapped.getTrigger().getImportantStackObjects(wrapped);
        } catch (RuntimeException unreadable) {
            return text;
        }
        if (important == null || important.isEmpty()) return text;
        final String context = " [" + important + "]";
        return text.endsWith(context) ? text.substring(0, text.length() - context.length()) : text;
    }

    /** lane trigger-label-1119: the origin of an effect whose source chain has a card this seat may not identify. */
    static final String HIDDEN_EFFECT = "A hidden card's effect";
    /** The origin of an effect with no source card and no engine provenance as a public game effect. */
    static final String AN_EFFECT = "An effect";
    /** The origin of a host this seat may not identify, face down where everyone sees it. */
    static final String A_FACE_DOWN_CARD = "A face-down card";
    /** The origin of a host this seat may not identify. */
    static final String A_HIDDEN_CARD = "A hidden card";
    /** How a referenced effect's generated name ("X's Effect") reads in a text shown in full. */
    static final String THIS_EFFECT = "this effect";
    private static final int EFFECT_CHAIN_LIMIT = 8;

    /**
     * lane trigger-label-1119 review fix 1 (Astra's review of #253: remembered cards, effect sources and an effect's
     * source effect were named from old objects). The one visibility rule for every card a label or a stack entry
     * names: the view of the card with {@code ref}'s id as it is in the game now, when this seat may identify that card
     * now ({@link #mayReceiveIdentity}), else null. The old object's view, a last-known copy, or the view a trigger kept
     * is never trusted for identity.
     * <ul>
     * <li>A spell copy in no zone keeps its rule ({@link #isZonelessSpellCopy}).</li>
     * <li>When no card with that id is in the game any more (it left the game, or a token ceased to exist), the old
     * object counts only when that is cheap to prove public: a face-up token (a token exists only face up on the
     * battlefield or the stack, and ceases elsewhere), or an object still in a public zone that this seat may identify
     * there. Anything else is hidden.</li>
     * </ul>
     */
    static CardView knownNow(final Game game, final CardView ref, final PlayerView viewer) {
        if (ref == null || viewer == null) return null;
        if (isZonelessSpellCopy(ref)) return mayReceiveIdentity(ref, viewer) ? ref : null;
        final Card current = game == null ? null : game.findById(ref.getId());
        if (current != null) return mayReceiveIdentity(current.getView(), viewer) ? current.getView() : null;
        if (ref.isFaceDown()) return null;
        final ZoneType zone = ref.getZone();
        if (zone == null) return ref.isToken() ? ref : null;
        return !zone.isHidden() && mayReceiveIdentity(ref, viewer) ? ref : null;
    }

    /**
     * lane trigger-label-1119 review fix 2: whether {@code card} is an effect whose name a player may read: one of its
     * owner's field-managed game effects ({@code Player.isFieldManagedEffect}: the monarch, the initiative, ...), made
     * by the engine with a fixed name. Decided by that engine pointer, never by the name.
     */
    static boolean isPublicGameEffect(final Card card) {
        return card != null && card.getOwner() != null && card.getOwner().isFieldManagedEffect(card);
    }

    /**
     * An effect's source chain for this seat ({@link #effectChain}): {@code known} when every link is a card this seat
     * may identify now; {@code origin} what the effect may be called (the chain's first card that is not an effect, by
     * its current name; a public game effect's own name; {@link #AN_EFFECT} when the chain ends at an effect with no
     * source) or {@link #HIDDEN_EFFECT}; {@code effects} the chain's effects other than public game effects, whose
     * generated names never show.
     */
    record EffectChain(boolean known, String origin, List<Card> effects) {
    }

    /**
     * lane trigger-label-1119 review fix 2: follows {@code effect}'s source chain to its first card that is not an
     * effect, judging each link by {@link #knownNow}, with a visited set and a depth cap: a hidden link
     * ({@link #HIDDEN_EFFECT}), a loop or a longer chain ({@link #AN_EFFECT}) is not known. A public game effect ends the
     * chain with its own name; an effect with no source ends it, known, as {@link #AN_EFFECT}. An effect's generated name
     * is never an origin.
     */
    static EffectChain effectChain(final Card effect, final Game game, final PlayerView viewer) {
        final List<Card> effects = new ArrayList<>();
        final Set<Integer> visited = new java.util.HashSet<>();
        Card at = effect;
        for (int depth = 0; depth <= EFFECT_CHAIN_LIMIT; depth++) {
            if (!visited.add(at.getId())) return new EffectChain(false, AN_EFFECT, effects);
            if (isPublicGameEffect(at)) return new EffectChain(true, at.getName(), effects);
            effects.add(at);
            final Card from = at.getEffectSource();
            if (from == null) return new EffectChain(true, AN_EFFECT, effects);
            final CardView known = knownNow(game, from.getView(), viewer);
            if (known == null) return new EffectChain(false, HIDDEN_EFFECT, effects);
            final Card current = game == null ? null : game.findById(from.getId());
            final Card next = current != null ? current : from;
            if (next.getGamePieceType() != GamePieceType.EFFECT) {
                final String name = known.getName();
                return new EffectChain(true, name == null || name.isBlank() ? AN_EFFECT : name, effects);
            }
            at = next;
        }
        return new EffectChain(false, AN_EFFECT, effects);
    }

    /**
     * lane trigger-label-1119 review fix 2 (Astra's re-review of #253: free-text redaction missed concealment in place,
     * generated effect names, a hidden-target fallback and remembered effects). An ability's label is all or nothing.
     * Every object the ability references is collected:
     * <ul>
     * <li>its host (and the view Forge supplied), the host's remembered, imprinted and chosen cards;</li>
     * <li>its trigger's remembered objects;</li>
     * <li>every target (a targeted spell by its host) and every triggering object, of it and of the ability it wraps;</li>
     * <li>the source chain of every effect among them ({@link #effectChain}).</li>
     * </ul>
     * Each is resolved by id to the current card ({@link #knownNow}). {@code allKnown} is true only when every one
     * passes; then the text may be shown in full, with only Forge's stack context cut ({@link #fullText}). Otherwise no
     * free text is shown, only {@link #minimalLabel}. {@code origin} is made only of verified pieces.
     */
    record AbilityCheck(boolean allKnown, String origin, List<Card> effects) {
        /** The fixed label for an ability whose text is not shown: "{@code origin} — triggered ability" ({@link #kindOf}). */
        String minimalLabel(final SpellAbility ability) {
            return origin + " — " + kindOf(ability);
        }
    }

    static boolean isTriggered(final SpellAbility ability) {
        return ability instanceof forge.game.trigger.WrappedAbility || ability != null && ability.isTrigger();
    }

    /** {@link AbilityCheck} of {@code ability}, whose host Forge supplied as {@code hostView}. */
    static AbilityCheck checkAbility(final SpellAbility ability, final CardView hostView, final Game game,
                                     final PlayerView viewer) {
        final ReferenceWalk walk = new ReferenceWalk(game, viewer);
        walk.view(hostView);
        if (ability != null) {
            final List<SpellAbility> layers = new ArrayList<>(2);
            layers.add(ability);
            if (ability instanceof forge.game.trigger.WrappedAbility wrapped && wrapped.getWrappedAbility() != null) {
                layers.add(wrapped.getWrappedAbility());
            }
            for (SpellAbility layer : layers) {
                try {
                    final Card host = layer.getHostCard();
                    walk.card(host);
                    if (host != null) {
                        walk.object(host.getRemembered());
                        walk.object(host.getImprintedCards());
                        walk.object(host.getChosenCards());
                    }
                    final forge.game.trigger.Trigger trigger = layer.getTrigger();
                    if (trigger != null) walk.object(trigger.getTriggerRemembered());
                    for (forge.game.spellability.TargetChoices choices : layer.getAllTargetChoices()) {
                        walk.object(choices.getTargetCards());
                        walk.object(choices.getTargetSpells());
                    }
                    walk.object(layer.getTriggeringObjects().values());
                } catch (RuntimeException unreadable) {
                    walk.failed = true;
                }
            }
        }
        // Review fix 3: an effect seen only as a view, with no card to follow its source chain, fails closed.
        if (!walk.pendingEffects.isEmpty()) walk.failed = true;
        return new AbilityCheck(!walk.failed, origin(hostView, ability == null ? null : ability.getHostCard(), game,
                viewer), walk.effects);
    }

    /** {@link #origin(CardView, Card, Game, PlayerView)} with no host card in hand. */
    static String origin(final CardView hostView, final Game game, final PlayerView viewer) {
        return origin(hostView, null, game, viewer);
    }

    /**
     * The origin of an ability whose host Forge supplied as {@code hostView} (its card {@code hostCard}, when known),
     * from verified pieces only: an effect by {@link #effectChain}; a host this seat may identify now by its current
     * name; else {@link #A_FACE_DOWN_CARD} or {@link #A_HIDDEN_CARD} (no id). Review fix 3 (finding 3): an effect is
     * recognized even when the game no longer has it (by its card, else by its view) and never falls back to its
     * generated name: with no card to follow, it is {@link #AN_EFFECT}.
     */
    static String origin(final CardView hostView, final Card hostCard, final Game game, final PlayerView viewer) {
        if (hostView == null) return A_HIDDEN_CARD;
        final Card current = game == null ? null : game.findById(hostView.getId());
        final Card card = current != null ? current
                : hostCard != null && hostCard.getId() == hostView.getId() ? hostCard : null;
        if (card != null ? card.getGamePieceType() == GamePieceType.EFFECT
                : hostView.getGamePieceType() == GamePieceType.EFFECT) {
            return card != null ? effectChain(card, game, viewer).origin() : AN_EFFECT;
        }
        final CardView known = knownNow(game, hostView, viewer);
        if (known == null) return (current != null ? current.isFaceDown() : hostView.isFaceDown()) ? A_FACE_DOWN_CARD : A_HIDDEN_CARD;
        if (known.getName() == null || known.getName().isBlank()) return known.isFaceDown() ? A_FACE_DOWN_CARD : A_HIDDEN_CARD;
        return known.getName() + (known.getGamePieceType() == GamePieceType.COPIED_SPELL ? " (copy)" : "");
    }

    /** Collects and judges the objects an ability references ({@link #checkAbility}). */
    private static final class ReferenceWalk {
        private final Game game;
        private final PlayerView viewer;
        private final Set<Integer> seen = new java.util.HashSet<>();
        /** Effects seen only as views so far: a card visit must follow each one's source chain, or the walk fails. */
        final Set<Integer> pendingEffects = new java.util.HashSet<>();
        final List<Card> effects = new ArrayList<>();
        boolean failed;

        ReferenceWalk(final Game game, final PlayerView viewer) {
            this.game = game;
            this.viewer = viewer;
        }

        void object(final Object object) {
            if (failed || object == null) return;
            if (object instanceof Card card) {
                card(card);
            } else if (object instanceof CardView view) {
                view(view);
            } else if (object instanceof SpellAbility sa) {
                card(sa.getHostCard());
            } else if (object instanceof Iterable<?> many) {
                for (Object each : many) object(each);
            }
        }

        void card(final Card card) {
            if (card != null) view(card.getView(), card);
        }

        void view(final CardView view) {
            view(view, null);
        }

        private void view(final CardView view, final Card object) {
            if (failed || view == null) return;
            if (knownNow(game, view, viewer) == null) {
                failed = true;
                return;
            }
            final Card current = game == null ? null : game.findById(view.getId());
            final Card card = current != null ? current : object;
            final boolean effect = card != null ? card.getGamePieceType() == GamePieceType.EFFECT
                    : view.getGamePieceType() == GamePieceType.EFFECT;
            if (!effect) return;
            // Review fix 3 (finding 3): an effect is marked visited only once its card's source chain is checked; a view
            // alone waits for that card (checkAbility fails the walk if it never comes).
            if (card == null) {
                if (!seen.contains(view.getId())) pendingEffects.add(view.getId());
                return;
            }
            pendingEffects.remove(view.getId());
            if (!seen.add(view.getId())) return;
            final EffectChain chain = effectChain(card, game, viewer);
            if (!chain.known()) {
                failed = true;
                return;
            }
            effects.addAll(chain.effects());
        }
    }

    /**
     * lane trigger-label-1119: an ability's text in full, for an ability whose every referenced object this seat may
     * identify now ({@link AbilityCheck#allKnown}): Forge's stack context cut ({@link #withoutTriggerContext}); a
     * trigger's remembered list ({@code " (" + Trigger.getTriggerRemembered + ")"}, before any {@code " (Targeting: ...)"})
     * dropped when the text already names each remembered card, else written without the brackets; and each referenced
     * effect's generated name read as {@link #THIS_EFFECT}.
     */
    static String fullText(final String text, final SpellAbility ability, final List<Card> effects) {
        if (text == null) return null;
        String body = text;
        String targeting = "";
        if (ability instanceof forge.game.trigger.WrappedAbility wrapped && wrapped.getTrigger() != null) {
            body = withoutTriggerContext(body, ability);
            final String targets = targetingSuffix(wrapped);
            if (targets != null && body.endsWith(targets)) {
                targeting = targets;
                body = body.substring(0, body.length() - targets.length());
            }
            final List<Object> remembered = wrapped.getTrigger().getTriggerRemembered();
            if (remembered != null && !remembered.isEmpty() && remembered.stream().allMatch(o -> o instanceof Card)) {
                final String list = " (" + remembered + ")";
                if (body.endsWith(list)) {
                    body = body.substring(0, body.length() - list.length());
                    final List<String> missing = new ArrayList<>();
                    for (Object object : remembered) {
                        if (!body.contains(object.toString())) missing.add(object.toString());
                    }
                    if (!missing.isEmpty()) body = body + " (" + String.join(", ", missing) + ")";
                }
            }
        }
        String out = body + targeting;
        for (Card effect : effects) {
            final String name = effect.getName();
            if (name == null || name.isBlank()) continue;
            out = out.replace(name + " (" + effect.getId() + ")", THIS_EFFECT).replace(name, THIS_EFFECT);
        }
        return out;
    }

    /** {@code " (Targeting: ...)"} as {@code WrappedAbility.getStackDescription} writes it, or null. */
    private static String targetingSuffix(final forge.game.trigger.WrappedAbility wrapped) {
        try {
            final SpellAbility inner = wrapped.getWrappedAbility();
            if (inner == null) return null;
            final List<forge.game.spellability.TargetChoices> all = inner.getAllTargetChoices();
            if (all.isEmpty() || forge.game.ability.ApiType.Charm.equals(inner.getApi())) return null;
            return " (Targeting: " + all + ")";
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * lane trigger-label-1119 review fix 2: a stack entry's {@code name} for its source: an effect's origin
     * ({@link #effectChain}), never its generated name; any other source keeps the name it has.
     */
    static void nameEffectSource(final JsonObject entry, final Card source, final Game game, final PlayerView viewer) {
        if (source == null) return;
        final Card current = game == null ? null : game.findById(source.getId());
        final Card card = current != null ? current : source;
        if (card.getGamePieceType() == GamePieceType.EFFECT) {
            entry.addProperty("name", effectChain(card, game, viewer).origin());
        }
    }

    /**
     * lane trigger-label-1119 review fix 3 (Astra's third review of #253: Forge renders references the collector does
     * not enumerate: paid lists, ExiledWith, a snapshot's fallback to its current host, sub-abilities, ORIGINALHOST, and
     * stored text rendered from a list that has changed since). The final sink check, and the authority: every string
     * emitted for an ability or a stack item passes it, or is replaced by a fixed label, whatever {@link #checkAbility}
     * decided. A text fails when it contains:
     * <ul>
     * <li>a real name of a card in the game this seat may not identify now ({@link #knownNow}): in a hand or a library
     * it may not see, face down, or in hidden exile. The names are the card's own, of every state but the face-down
     * one (both faces of a double-faced card, both halves of a split card), read from the card, never from a view.
     * Word-bounded, with its exact capitalization (review fix 3b): Forge renders a card's name verbatim, so a hidden
     * Life // Death's "Life" matches "Life" but not "pay 1 life", and keywords stay lowercase in rendered text. Basic
     * land names stay in (closed);</li>
     * <li>or such a card's id token, {@code (N)} or {@code (HN)}, always.</li>
     * </ul>
     * A visible card that shares its name with a hidden one fails too: closed, by design.
     */
    static final class HiddenNames {
        private final java.util.regex.Pattern pattern;

        private HiddenNames(final java.util.regex.Pattern pattern) {
            this.pattern = pattern;
        }

        /** The names and ids hidden from {@code viewer} in {@code game} now. */
        static HiddenNames of(final Game game, final PlayerView viewer) {
            if (game == null) return new HiddenNames(null);
            final Set<String> names = new java.util.LinkedHashSet<>();
            final Set<Integer> ids = new java.util.TreeSet<>();
            game.forEachCardInGame(card -> {
                if (knownNow(game, card.getView(), viewer) == null) {
                    ids.add(card.getId());
                    for (CardStateName state : card.getStates()) {
                        if (state == CardStateName.FaceDown) continue;
                        final CardState face = card.getState(state);
                        final String name = face == null ? null : face.getName();
                        if (name != null && !name.isBlank()) names.add(name.trim());
                    }
                }
                return true;
            });
            final StringBuilder regex = new StringBuilder();
            if (!names.isEmpty()) {
                regex.append("(?<![\\p{L}\\p{N}])(?:");
                boolean first = true;
                for (String name : names) {
                    if (!first) regex.append('|');
                    regex.append(java.util.regex.Pattern.quote(name));
                    first = false;
                }
                regex.append(")(?![\\p{L}\\p{N}])");
            }
            if (!ids.isEmpty()) {
                if (regex.length() > 0) regex.append('|');
                regex.append("\\([Hh]?(?:");
                boolean first = true;
                for (Integer id : ids) {
                    if (!first) regex.append('|');
                    regex.append(id);
                    first = false;
                }
                regex.append(")\\)");
            }
            // Case-sensitive (review fix 3b): names match with their exact capitalization.
            return new HiddenNames(regex.length() == 0 ? null : java.util.regex.Pattern.compile(regex.toString()));
        }

        /** True when {@code text} names no hidden card and carries no hidden card's id. */
        boolean safe(final String text) {
            return text == null || pattern == null || !pattern.matcher(text).find();
        }

        /**
         * The fixed label for an ability or a stack item whose text failed: "{@code origin} — kind" when that passes;
         * else the same with {@code fallback} ({@link #A_HIDDEN_CARD} or {@link #AN_EFFECT}); else "A hidden card —
         * kind".
         */
        String fixedLabel(final String origin, final String fallback, final SpellAbility ability) {
            final String kind = " — " + kindOf(ability);
            if (origin != null && !origin.isBlank() && safe(origin + kind)) return origin + kind;
            if (safe(fallback + kind)) return fallback + kind;
            return A_HIDDEN_CARD + kind;
        }

        /** {@code origin} when it passes; else {@code fallback} when that does; else {@link #A_HIDDEN_CARD}. */
        String safeOrigin(final String origin, final String fallback) {
            if (origin != null && !origin.isBlank() && safe(origin)) return origin;
            return safe(fallback) ? fallback : A_HIDDEN_CARD;
        }
    }

    /** lane trigger-label-1119 review fix 3: {@code text} passes the sink check ({@link HiddenNames}) for {@code viewer}. */
    static boolean hiddenSafe(final String text, final Game game, final PlayerView viewer) {
        return HiddenNames.of(game, viewer).safe(text);
    }

    /** What a fixed label calls the item: "triggered ability", "spell" or "ability". */
    static String kindOf(final SpellAbility ability) {
        if (isTriggered(ability)) return "triggered ability";
        return ability != null && ability.isSpell() ? "spell" : "ability";
    }

    /** Whether {@code card}, as it is now when the game still has it, is an effect. */
    private static boolean isEffectNow(final Card card, final Game game) {
        if (card == null) return false;
        final Card current = game == null ? null : game.findById(card.getId());
        return (current != null ? current : card).getGamePieceType() == GamePieceType.EFFECT;
    }

    /**
     * The host of a stack entry's targeted stack item: the targeted ability's own host card, found among {@code sa}'s
     * targets by its id (it holds even when that host has left the game), else the card with the target's {@code fid}.
     */
    private static Card targetedHost(final JsonObject target, final SpellAbility sa, final Game game) {
        try {
            if (sa != null && target.has("stackId")) {
                final int stackId = target.get("stackId").getAsInt();
                for (SpellAbility layer = sa; layer != null; layer = layer.getSubAbility()) {
                    for (forge.game.spellability.TargetChoices choices : layer.getAllTargetChoices()) {
                        for (SpellAbility targeted : choices.getTargetSpells()) {
                            if (targeted.getId() == stackId && targeted.getHostCard() != null) return targeted.getHostCard();
                        }
                    }
                }
            }
            return game != null && target.has("fid") ? game.findById(target.get("fid").getAsInt()) : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * lane trigger-label-1119 review fix 3: the sink over one stack entry's strings, run last. A failing name becomes
     * {@link #A_HIDDEN_CARD} (or {@link #AN_EFFECT} for an effect's entry); a failing description the fixed label
     * ({@link HiddenNames#fixedLabel}). Each targeted stack item's name (finding 4: a Stifle-style target bypassed
     * effect provenance) is first an effect's origin ({@link #effectChain}) when its host is an effect, never the
     * generated name, then checked the same way.
     */
    private static void sinkStackEntry(final JsonObject entry, final SpellAbilityStackInstance si, final Card source,
                                       final Game game, final PlayerView viewer, final HiddenNames hidden) {
        final SpellAbility sa = si == null ? null : si.getSpellAbility();
        final String fallback = isEffectNow(source, game) ? AN_EFFECT : A_HIDDEN_CARD;
        final String name = stringValue(entry, "name");
        if (name != null && !hidden.safe(name)) entry.addProperty("name", hidden.safeOrigin(null, fallback));
        final String description = stringValue(entry, "description");
        if (description != null && !hidden.safe(description)) {
            entry.addProperty("description", hidden.fixedLabel(stringValue(entry, "name"), fallback, sa));
        }
        if (entry.has("targetSpells") && entry.get("targetSpells").isJsonArray()) {
            for (JsonElement element : entry.getAsJsonArray("targetSpells")) {
                if (!element.isJsonObject()) continue;
                final JsonObject target = element.getAsJsonObject();
                final String targetName = stringValue(target, "name");
                if (targetName == null) continue;
                final Card host = targetedHost(target, sa, game);
                final boolean effect = isEffectNow(host, game);
                String named = targetName;
                if (effect) {
                    final Card current = game == null ? null : game.findById(host.getId());
                    named = effectChain(current != null ? current : host, game, viewer).origin();
                }
                target.addProperty("name", hidden.safe(named) ? named
                        : hidden.safeOrigin(null, effect ? AN_EFFECT : A_HIDDEN_CARD));
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
                addCurrentRoom(cardObject, card);
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
            final Card card = findListedCard(game, cardObject.get("fid").getAsInt());
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
     * The room a dungeon is in, as {@code currentRoom}: Forge's own room name (the {@code RoomName$} of the dungeon's
     * script; for Undercity "Secret Entrance", "Forge", "Lost Well", "Trap!", "Arena", "Stash", "Archive", "Catacombs" or
     * "Throne of the Dead Three"). Lane undercity-room-1027, owner report 2026-10-27T19-40-28: after ventures into Secret
     * Entrance and then Forge, the table still offered Secret Entrance as the next room. No room was on the wire, and the
     * browser reads a dungeon with no room as one nobody has ventured into. A dungeon is a public command-zone object
     * whose state is its room, and Forge's desktop shows the room on the card ({@code CardDetailUtil}). Absent for
     * anything in no room: every non-dungeon object, and a dungeon nobody has ventured into yet. Only on an object whose
     * identity the viewer may already receive; the bench StateEncoder is untouched (it also feeds the bridge's state,
     * the search's payloads and the label dumps).
     */
    private static void addCurrentRoom(final JsonObject cardObject, final Card card) {
        final String room = card.getCurrentRoom();
        if (room != null && !room.isEmpty()) {
            cardObject.addProperty("currentRoom", room);
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

    /**
     * The card this view lists under {@code id}, in any registered seat's zones (lane own-cards-after-loss-1028, owner
     * report 2026-10-28T23-24-50). {@link Game#findById} walks in-game players only, so once a seat has lost every card
     * in its zones (the ones {@link #addEliminatedSeats} lists) went unresolved: its own hand was dropped and its own
     * battlefield redacted to "Face-down card", in its own terminal view and in the opponent's. An eliminated seat's
     * cards are looked up in that seat's zones too. Only the lookup changes: who may receive a card's identity is still
     * {@link #mayReceiveIdentity}, Forge's own canBeShownTo / face-down rules, so the opponent's hand stays hidden.
     */
    static Card findListedCard(final Game game, final int id) {
        final Card inGame = game.findById(id);
        if (inGame != null) {
            return inGame;
        }
        for (Player player : game.getRegisteredPlayers()) {
            if (game.getPlayers().contains(player)) {
                continue; // findById already walked this seat's zones
            }
            for (ZoneType zone : ELIMINATED_SEAT_ZONES) {
                final forge.game.zone.PlayerZone cards = player.getZone(zone);
                if (cards == null) {
                    continue;
                }
                for (Card card : cards.getCards(false)) {
                    if (card.getId() == id) {
                        return card;
                    }
                }
            }
        }
        return null;
    }

    /** The zones {@link Game#findById} walks per player, for {@link #findListedCard}. */
    private static final List<ZoneType> ELIMINATED_SEAT_ZONES = List.of(ZoneType.Graveyard, ZoneType.Hand,
            ZoneType.Library, ZoneType.Battlefield, ZoneType.Exile, ZoneType.Command);

    /** The card {@code object[field]} names, when the viewer may receive its identity; otherwise null. */
    private static Card visibleCardForFid(final JsonObject object, final String field,
                                          final Game game, final PlayerView viewer) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            return null;
        }
        try {
            final Card card = findListedCard(game, object.get(field).getAsInt());
            return card != null && card.getZone() != null
                    && card.getView().getZone() == card.getZone().getZoneType()
                    && mayReceiveIdentity(card.getView(), viewer) ? card : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The source of a stack entry that has ceased to exist (lane facedown-label-1021), per {@link #isCeasedPublicObject}.
     * A Clue or a Food sacrificed to pay for its own ability is in no zone the game lists (CR 704.5d). So is a spell
     * copy that has resolved (CR 707.10a) while its delayed trigger waits, e.g. a storm or Twincast copy of Pact of
     * Negation at the next upkeep. {@link #visibleCardForFid} finds nothing, and the entry read "Face-down spell or
     * ability" to both seats. A face-down object, any other card the game no longer lists, or an instance whose
     * source is not the entry's {@code fid} gives null (fail closed).
     */
    private static Card ceasedSource(final JsonObject entry, final SpellAbilityStackInstance si, final Game game) {
        final Card source = si == null ? null : si.getSourceCard();
        if (source == null || !entry.has("fid") || !entry.get("fid").isJsonPrimitive()) {
            return null;
        }
        try {
            return entry.get("fid").getAsInt() == source.getId() && isCeasedPublicObject(game, source) ? source : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A token or a spell copy, not face down, that the game no longer lists. It has ceased to exist (CR 704.5d for a
     * token off the battlefield, CR 707.10a for a copy off the stack), and its identity was public on the battlefield
     * or the stack. Forge never lists either in a hidden zone, so no listed hidden card qualifies.
     */
    static boolean isCeasedPublicObject(final Game game, final Card card) {
        return card != null && (card.getGamePieceType() == GamePieceType.TOKEN || card.isCopiedSpell())
                && !card.isFaceDown() && !card.getView().isFaceDown() && game.findById(card.getId()) == null;
    }

    /**
     * A floating mana's source as {@code viewer} sees it now (lane facedown-label-1021). Forge keeps a last-known copy
     * of the source on each mana (Mana: CardCopyService.getLKICopy or the change-zone LKI). That copy has no zone, so
     * the identity check called the seat's own Forest "Face-down card (1)" on its payment controls. The label and the
     * visibility come from the card the game holds under that id NOW. A tapped land on the battlefield, a sacrificed
     * Lotus Petal in a graveyard and an exiled Elvish Spirit Guide are named. A source now hidden from the viewer (a
     * library, the foe's hand) is "Face-down card (id)". A token that has since ceased to exist (a sacrificed
     * Treasure) is named by its last-known face, as on the stack ({@link #isCeasedPublicObject}). Anything else the
     * game no longer has is "Face-down card (id)".
     */
    static String manaSourceLabel(final Game game, final Card source, final PlayerView viewer) {
        if (source == null) {
            return "Unknown card";
        }
        final Card current = game.findById(source.getId());
        if (current != null) {
            return safeCardLabel(current.getView(), viewer);
        }
        return isCeasedPublicObject(game, source) ? visibleCardLabel(source.getView())
                : "Face-down card (" + source.getId() + ")";
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
