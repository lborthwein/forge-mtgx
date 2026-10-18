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

import forge.game.Game;
import forge.game.card.Card;
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
