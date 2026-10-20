/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.gson.JsonObject;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardStateName;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Public card facts on the browser wire (lane bridgemeta-1018, protocol field {@code publicCardFacts}).
 *
 * <p>A client that records what each side revealed (mtgx PR #1043, its Forge best-of-three boarding) must know, for a
 * card it was shown, three things the wire did not say: who <b>owns</b> it, whether it is a <b>token or a copy</b>
 * rather than a card from a deck, and which <b>zone</b> it came from. Inferring them from names, controllers or the
 * hidden decks leaked hidden deck membership in every form the reviews tried. So the JVM says them, and only for an
 * object whose identity the viewing seat may already receive: a face-down or redacted object gets none of them.</p>
 *
 * <ul>
 *   <li>{@code ownerSeat}: the owner's seat (registered-player order, the wire's seat namespace).</li>
 *   <li>{@code isToken}: Forge's own {@link Card#isToken()}. A copy of a permanent spell is a token once it resolves
 *       (CR 111.11, {@code GameAction.changeZone}).</li>
 *   <li>{@code isCopy}: a copied spell ({@link Card#isCopiedSpell()}), an object made as a copy of another
 *       ({@link Card#getCopiedPermanent()}: token copies, a resolved spell copy, a "cast a copy" card), or a permanent
 *       currently copying something ({@link Card#isCloned()}, a Clone after its copy effect). On a stack entry for an
 *       ability, it is also true for a copied ability ({@link SpellAbility#isCopied()}).</li>
 * </ul>
 *
 * <p><b>Token identity</b> (lane block-search-1019, for the client's token art): on a zone entry of a token the viewer
 * may see, the token's printed characteristics, as optional fields. The display name alone cannot pick the art: a
 * renamed type ("Phyrexian Wurm") is not its printing's name ("Wurm"), and some faces differ only by colours or base
 * P/T (two Insects, three Constructs, two Thopters, two Elementals).
 * <ul>
 *   <li>{@code tokenScript}: Forge's token script id (res/tokenscripts, e.g. {@code g_6_6_wurm}), when the token was made
 *       from one; absent for a token with no script (a token copy of a card).</li>
 *   <li>{@code tokenColors}: its printed colours, WUBRG letters in that order ({@code []} = colourless); not the current
 *       colours an effect may have changed.</li>
 *   <li>{@code tokenBasePower}, {@code tokenBaseToughness}: its printed base P/T, only for a creature token; not the
 *       current values.</li>
 * </ul>
 *
 * <p>The facts are additive and confined to this browser payload. {@code forge.bench.StateEncoder} (the bench and RL
 * observations) is not changed and does not call this class.</p>
 */
final class InteractiveCardFacts {
    /** The version of these facts, as the hello's and every view's {@code publicCardFacts}. Absent: an older jar. */
    static final int VERSION = 1;
    static final String FIELD = "publicCardFacts";

    private InteractiveCardFacts() {
    }

    /** Is {@code card} a copy of something (not the physical card its name says)? */
    static boolean isCopy(final Card card) {
        return card.isCopiedSpell() || card.getCopiedPermanent() != null || card.isCloned();
    }

    /** The seat of {@code player} in registered order, or -1. */
    static int seatOf(final Game game, final Player player) {
        if (player == null) {
            return -1;
        }
        int seat = 0;
        for (Player registered : game.getRegisteredPlayers()) {
            if (registered.getId() == player.getId()) {
                return seat;
            }
            seat++;
        }
        return -1;
    }

    /**
     * {@code isToken} and {@code isCopy} on a zone entry the caller has established the viewer may see. The owner is
     * already on zone entries ({@code owner}, and {@code ownerSeat} in a seat view).
     */
    static void addTokenAndCopy(final JsonObject object, final Card card) {
        object.addProperty("isToken", card.isToken());
        object.addProperty("isCopy", isCopy(card));
        if (card.isToken()) {
            addTokenIdentity(object, card);
        }
    }

    /**
     * The token's printed identity ({@code tokenScript}, {@code tokenColors}, {@code tokenBasePower},
     * {@code tokenBaseToughness}; see the class comment). Only for a token whose zone entry the viewer may see (the
     * caller has established that, and never calls this for a face-down object). Read-only; a field that cannot be
     * read is left out.
     */
    static void addTokenIdentity(final JsonObject object, final Card card) {
        try {
            final String script = tokenScript(card);
            if (script != null) {
                object.addProperty("tokenScript", script);
            }
            CardState printed = card.getState(CardStateName.Original);
            if (printed == null) {
                printed = card.getCurrentState();
            }
            final ColorSet colors = printed.getColor();
            final com.google.gson.JsonArray cs = new com.google.gson.JsonArray();
            for (byte c : MagicColor.WUBRG) {
                if (colors.hasAnyColor(c)) {
                    cs.add(MagicColor.toShortString(c));
                }
            }
            object.add("tokenColors", cs);
            if (printed.getType().isCreature()) {
                object.addProperty("tokenBasePower", printed.getBasePower());
                object.addProperty("tokenBaseToughness", printed.getBaseToughness());
            }
        } catch (RuntimeException e) {
            // a token whose printed state cannot be read gets no identity fields
        }
    }

    /**
     * Forge's token script id of {@code card} (as {@code GameState}'s dump reads it): its image key's token image name
     * up to any {@code |edition} suffix, when the token database has that script; otherwise null. The card's own image
     * key, never {@code PaperToken.getImageKey}, which draws from the game's random stream.
     */
    static String tokenScript(final Card card) {
        final String key = card.getImageKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        final String img = ImageKeys.getTokenImageName(key);
        if (img == null || img.isBlank()) {
            return null;
        }
        final int bar = img.indexOf('|');
        final String script = bar < 0 ? img : img.substring(0, bar);
        return !script.isEmpty() && StaticData.instance().getAllTokens().containsRule(script) ? script : null;
    }

    /** {@code ownerSeat}, {@code isToken} and {@code isCopy}: a stack entry or an event the viewer may see. */
    static void addOwnerTokenAndCopy(final JsonObject object, final Game game, final Card card,
                                     final SpellAbility ability) {
        final int owner = seatOf(game, card.getOwner());
        if (owner >= 0) {
            object.addProperty("ownerSeat", owner);
        }
        object.addProperty("isToken", card.isToken());
        object.addProperty("isCopy", isCopy(card) || ability != null && ability.isCopied());
    }
}
