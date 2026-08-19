/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.bench;

import java.util.List;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;

/**
 * Seat-visible JSON encoding of a Forge game state (wire protocol v1).
 *
 * <p>Every card that leaves this class is filtered through
 * {@link CardView#canBeShownTo(PlayerView)}. That makes the bridged seat <em>stricter</em>
 * than Forge's own AI, which peeks at hidden information in a handful of places
 * ({@code ChangeZoneAi}, {@code BalanceAi}); the asymmetry is deliberate and documented
 * in the architecture note.
 *
 * <p>Zone rules: own hand in full; opponent hand as a count plus whatever is individually
 * revealed; libraries never (size only); battlefield / graveyard / exile / stack / command
 * in full where visible.
 */
public final class StateEncoder {

    private StateEncoder() {
    }

    /** Full seat-visible state for {@code seat}. */
    public static JsonObject encode(final Game game, final Player seat) {
        final PlayerView viewer = seat.getView();
        final JsonObject st = new JsonObject();

        st.addProperty("turn", game.getPhaseHandler().getTurn());
        st.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
        st.addProperty("activePlayer", playerIndex(game, game.getPhaseHandler().getPlayerTurn()));
        st.addProperty("priorityPlayer", playerIndex(game, game.getPhaseHandler().getPriorityPlayer()));
        st.addProperty("seat", playerIndex(game, seat));
        st.addProperty("gameOver", game.isGameOver());

        final JsonArray players = new JsonArray();
        final JsonArray life = new JsonArray();
        for (Player p : game.getPlayers()) {
            players.add(encodePlayer(game, p, viewer));
            life.add(p.getLife());
        }
        st.add("players", players);
        st.add("life", life);

        st.add("stack", encodeStack(game, viewer));
        st.add("combat", encodeCombat(game, viewer));
        return st;
    }

    private static JsonObject encodePlayer(final Game game, final Player p, final PlayerView viewer) {
        final JsonObject o = new JsonObject();
        o.addProperty("index", playerIndex(game, p));
        o.addProperty("name", p.getName());
        o.addProperty("life", p.getLife());
        o.addProperty("poison", p.getPoisonCounters());
        o.addProperty("landsPlayed", p.getLandsPlayedThisTurn());
        o.addProperty("librarySize", p.getCardsIn(ZoneType.Library).size());
        o.addProperty("handSize", p.getCardsIn(ZoneType.Hand).size());
        o.add("manaPool", encodeManaPool(p.getManaPool()));

        o.add("hand", encodeZone(p, ZoneType.Hand, viewer));
        o.add("battlefield", encodeZone(p, ZoneType.Battlefield, viewer));
        o.add("graveyard", encodeZone(p, ZoneType.Graveyard, viewer));
        o.add("exile", encodeZone(p, ZoneType.Exile, viewer));
        o.add("command", encodeZone(p, ZoneType.Command, viewer));
        // libraries are never enumerated, in either direction
        return o;
    }

    private static JsonArray encodeZone(final Player owner, final ZoneType zone, final PlayerView viewer) {
        final JsonArray arr = new JsonArray();
        for (Card c : owner.getCardsIn(zone)) {
            JsonObject cj = encodeCard(c, viewer);
            if (cj != null) {
                arr.add(cj);
            }
        }
        return arr;
    }

    private static JsonObject encodeManaPool(final ManaPool pool) {
        final JsonObject o = new JsonObject();
        for (byte color : MagicColor.WUBRGC) {
            o.addProperty(MagicColor.toShortString(color), pool.getAmountOfColor(color));
        }
        o.addProperty("total", pool.totalMana());
        return o;
    }

    /**
     * Encode one card, or null if it is not visible to {@code viewer}.
     * A hidden card contributes only to the zone's count.
     */
    public static JsonObject encodeCard(final Card c, final PlayerView viewer) {
        if (c == null) {
            return null;
        }
        final CardView cv = c.getView();
        if (viewer != null && !cv.canBeShownTo(viewer)) {
            return null;
        }
        return encodeCardUnchecked(c);
    }

    /** Encode a card the caller has already established is legal to show. */
    public static JsonObject encodeCardUnchecked(final Card c) {
        final JsonObject o = new JsonObject();
        o.addProperty("fid", c.getId());
        o.addProperty("name", c.getName());
        o.addProperty("zone", c.getZone() == null ? "None" : String.valueOf(c.getZone().getZoneType()));
        o.addProperty("controller", c.getController() == null ? -1 : c.getController().getId());
        o.addProperty("owner", c.getOwner() == null ? -1 : c.getOwner().getId());
        o.addProperty("tapped", c.isTapped());
        o.addProperty("faceDown", c.isFaceDown());
        o.addProperty("sick", c.isSick());
        o.addProperty("types", c.getType().toString());
        o.addProperty("manaCost", String.valueOf(c.getManaCost()));
        o.addProperty("cmc", c.getCMC());
        if (c.isCreature()) {
            o.addProperty("power", c.getNetPower());
            o.addProperty("toughness", c.getNetToughness());
            o.addProperty("damage", c.getDamage());
        }
        final Multiset<CounterType> counters = c.getCounters();
        if (counters != null && !counters.isEmpty()) {
            final JsonObject cj = new JsonObject();
            for (Multiset.Entry<CounterType> e : counters.entrySet()) {
                cj.addProperty(e.getElement().toString(), e.getCount());
            }
            o.add("counters", cj);
        }
        final CardCollectionView attached = c.getAttachedCards();
        if (attached != null && !attached.isEmpty()) {
            final JsonArray a = new JsonArray();
            for (Card at : attached) {
                a.add(at.getId());
            }
            o.add("attachments", a);
        }
        final GameEntity attachedTo = c.getEntityAttachedTo();
        if (attachedTo != null) {
            o.addProperty("attachedTo", attachedTo.getId());
        }
        return o;
    }

    private static JsonArray encodeStack(final Game game, final PlayerView viewer) {
        final JsonArray arr = new JsonArray();
        for (SpellAbilityStackInstance si : game.getStack()) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", si.getId());
            final Card src = si.getSourceCard();
            o.addProperty("name", src == null ? "?" : src.getName());
            o.addProperty("fid", src == null ? -1 : src.getId());
            o.addProperty("controller", si.getActivatingPlayer() == null
                    ? -1 : si.getActivatingPlayer().getId());
            o.addProperty("description", String.valueOf(si.getStackDescription()));
            final TargetChoices tc = si.getTargetChoices();
            o.addProperty("targets", tc == null ? "" : tc.toString());
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject encodeCombat(final Game game, final PlayerView viewer) {
        final JsonObject o = new JsonObject();
        final Combat combat = game.getCombat();
        o.addProperty("inCombat", combat != null);
        if (combat == null) {
            return o;
        }
        final JsonArray atk = new JsonArray();
        for (Card a : combat.getAttackers()) {
            final JsonObject e = new JsonObject();
            e.addProperty("fid", a.getId());
            e.addProperty("name", a.getName());
            final GameEntity def = combat.getDefenderByAttacker(a);
            e.addProperty("defenderId", def == null ? -1 : def.getId());
            final JsonArray blockers = new JsonArray();
            for (Card b : combat.getBlockers(a)) {
                blockers.add(b.getId());
            }
            e.add("blockedBy", blockers);
            atk.add(e);
        }
        o.add("attackers", atk);
        return o;
    }

    /** Compact summary of a spell/ability for a {@code priority} menu entry. */
    public static JsonObject encodeSpellAbility(final SpellAbility sa) {
        final JsonObject o = new JsonObject();
        if (sa == null) {
            o.addProperty("pass", true);
            o.addProperty("description", "(pass)");
            return o;
        }
        final Card host = sa.getHostCard();
        o.addProperty("fid", host == null ? -1 : host.getId());
        o.addProperty("source", host == null ? "?" : host.getName());
        o.addProperty("api", sa.getApi() == null ? "" : sa.getApi().toString());
        o.addProperty("isSpell", sa.isSpell());
        o.addProperty("isAbility", sa.isAbility());
        o.addProperty("isManaAbility", sa.isManaAbility());
        o.addProperty("payCosts", sa.getPayCosts() == null ? "" : sa.getPayCosts().toString());
        o.addProperty("description", String.valueOf(sa.toString()));
        o.addProperty("stackDescription", String.valueOf(sa.getStackDescription()));
        o.addProperty("usesTargeting", sa.usesTargeting());
        return o;
    }

    /** Compact summary of any game entity (card or player) for a target/entity menu. */
    public static JsonObject encodeEntity(final GameEntity ge) {
        final JsonObject o = new JsonObject();
        if (ge == null) {
            o.addProperty("id", -1);
            o.addProperty("kind", "none");
            return o;
        }
        o.addProperty("id", ge.getId());
        o.addProperty("name", ge.getName());
        if (ge instanceof Card) {
            final Card c = (Card) ge;
            o.addProperty("kind", "card");
            o.addProperty("fid", c.getId());
            o.addProperty("zone", c.getZone() == null ? "None" : String.valueOf(c.getZone().getZoneType()));
            o.addProperty("controller", c.getController() == null ? -1 : c.getController().getId());
            o.addProperty("types", c.getType().toString());
            if (c.isCreature()) {
                o.addProperty("power", c.getNetPower());
                o.addProperty("toughness", c.getNetToughness());
            }
        } else if (ge instanceof Player) {
            o.addProperty("kind", "player");
            o.addProperty("life", ((Player) ge).getLife());
        } else {
            o.addProperty("kind", ge.getClass().getSimpleName());
        }
        return o;
    }

    public static JsonArray encodeCards(final Iterable<Card> cards) {
        final JsonArray a = new JsonArray();
        if (cards != null) {
            for (Card c : cards) {
                a.add(encodeCardUnchecked(c));
            }
        }
        return a;
    }

    public static JsonArray encodeEntities(final Iterable<? extends GameEntity> entities) {
        final JsonArray a = new JsonArray();
        if (entities != null) {
            for (GameEntity ge : entities) {
                a.add(encodeEntity(ge));
            }
        }
        return a;
    }

    public static JsonArray encodeSpellAbilities(final List<SpellAbility> sas) {
        final JsonArray a = new JsonArray();
        if (sas != null) {
            for (SpellAbility sa : sas) {
                a.add(encodeSpellAbility(sa));
            }
        }
        return a;
    }

    /** Turn-order index of a player within the game (the harness's seat number). */
    public static int playerIndex(final Game game, final Player p) {
        if (p == null) {
            return -1;
        }
        final List<Player> ps = game.getRegisteredPlayers();
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i) == p) {
                return i;
            }
        }
        return -1;
    }
}
