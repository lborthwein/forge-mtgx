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

import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCost;
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
        o.add("keywords", encodeKeywords(c));
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

    /**
     * The card's keywords <em>as they currently apply</em>.
     *
     * <p>{@code Card.getKeywords()} walks the live keyword state through
     * {@code visitKeywords}, so continuous effects, granted keywords and keywords removed
     * by an effect are all reflected — this is not the printed card script. Emitted as
     * Forge's own keyword strings ("Flying", "Menace", "Protection from red",
     * "Bushido 1"), which is what the host matches on.
     *
     * <p>Protocol v2. Added because the host could not see menace on an attacking
     * <em>token</em> (a token has no cube entry to read the keyword off), and Forge
     * validates a block declaration as a whole: one blocker on a menacing attacker
     * refused the entire step. Measured at 6 refusals per 120 games.
     */
    public static JsonArray encodeKeywords(final Card c) {
        final JsonArray a = new JsonArray();
        try {
            for (KeywordInterface kw : c.getKeywords()) {
                final String s = kw.getOriginal();
                a.add(s == null || s.isEmpty() ? String.valueOf(kw.getKeyword()) : s);
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("keyword enumeration failed for " + c, e);
        }
        return a;
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
            // `targets` is Forge's own rendering, kept for compatibility with protocol v1.
            o.addProperty("targets", tc == null ? "" : tc.toString());
            // Protocol v2: the same thing structured, so the opponent model can see what a
            // spell on the stack is aiming at without parsing a rendering that is not a
            // contract. Spell targets (counterspells) are carried separately because their
            // ids live in the stack-instance space, not the card/player space.
            final JsonArray tIds = new JsonArray();
            final JsonArray tDetail = new JsonArray();
            final JsonArray tSpells = new JsonArray();
            if (tc != null) {
                try {
                    for (GameEntity ge : tc.getTargetEntities()) {
                        tIds.add(ge.getId());
                        tDetail.add(encodeEntity(ge));
                    }
                    for (SpellAbility tsa : tc.getTargetSpells()) {
                        final JsonObject ts = new JsonObject();
                        ts.addProperty("stackId", tsa.getId());
                        final Card th = tsa.getHostCard();
                        ts.addProperty("fid", th == null ? -1 : th.getId());
                        ts.addProperty("name", th == null ? "?" : th.getName());
                        tSpells.add(ts);
                    }
                } catch (RuntimeException e) {
                    JsonRpcChannel.logErr("stack target enumeration failed", e);
                }
            }
            o.add("targetIds", tIds);
            o.add("targetsDetail", tDetail);
            o.add("targetSpells", tSpells);
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
        // ---- protocol v2: structured cost / X / modes / discriminator ----------------
        // Two menu entries that differ only in an alternative or optional cost render
        // almost identically in `description`, so a scorer sees undifferentiated casts of
        // the same card. These fields say what actually differs.
        o.addProperty("optionKey", optionKey(sa));
        o.add("cost", encodeCost(sa));
        o.add("x", encodeXRange(sa));
        o.add("modes", encodeModes(sa));
        if (sa.usesTargeting()) {
            o.addProperty("minTargets", sa.getMinTargets());
            o.addProperty("maxTargets", sa.getMaxTargets());
        }
        return o;
    }

    /**
     * A stable discriminator for one priority option. Two entries for the same card that
     * differ only in which alternative/optional costs are baked in get different keys.
     */
    private static String optionKey(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        final StringBuilder sb = new StringBuilder();
        sb.append(host == null ? -1 : host.getId()).append('|');
        sb.append(sa.getApi() == null ? "" : sa.getApi()).append('|');
        sb.append(sa.getPayCosts() == null ? "" : sa.getPayCosts().toString()).append('|');
        try {
            for (OptionalCost oc : sa.getOptionalCosts()) {
                sb.append(oc).append(',');
            }
        } catch (RuntimeException e) {
            // an option key is a hint, never a decision -- degrade rather than throw
        }
        sb.append('|').append(sa.isLandAbility() ? "land" : sa.isSpell() ? "spell" : "ability");
        return sb.toString();
    }

    /** Structured breakdown of what an option costs, alongside the rendered string. */
    private static JsonObject encodeCost(final SpellAbility sa) {
        final JsonObject o = new JsonObject();
        final Cost cost = sa.getPayCosts();
        if (cost == null) {
            o.addProperty("mana", "");
            o.add("parts", new JsonArray());
            return o;
        }
        o.addProperty("rendered", cost.toString());
        o.addProperty("mana", cost.hasNoManaCost() ? "" : String.valueOf(cost.getTotalMana()));
        o.addProperty("cmc", cost.hasNoManaCost() ? 0 : cost.getTotalMana().getCMC());
        o.addProperty("onlyMana", cost.isOnlyManaCost());
        final JsonArray parts = new JsonArray();
        try {
            for (CostPart cp : cost.getCostParts()) {
                final JsonObject p = new JsonObject();
                p.addProperty("kind", cp.getClass().getSimpleName());
                p.addProperty("rendered", String.valueOf(cp));
                parts.add(p);
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("cost part enumeration failed", e);
        }
        o.add("parts", parts);
        final JsonArray optional = new JsonArray();
        try {
            for (OptionalCost oc : sa.getOptionalCosts()) {
                optional.add(String.valueOf(oc));
            }
        } catch (RuntimeException e) {
            // leave the list empty
        }
        o.add("optionalPaid", optional);
        return o;
    }

    /**
     * The X range this option may be cast for. {@code max} is an estimate of what the
     * activating player can actually pay for right now, not an unbounded integer — an X
     * spell with no ceiling is not a priceable option.
     */
    private static JsonObject encodeXRange(final SpellAbility sa) {
        final JsonObject o = new JsonObject();
        boolean hasX = false;
        try {
            hasX = sa.costHasX() || (sa.getPayCosts() != null && sa.getPayCosts().hasXInAnyCostPart());
        } catch (RuntimeException e) {
            // treat as no X
        }
        o.addProperty("has", hasX);
        if (!hasX) {
            return o;
        }
        o.addProperty("min", 0);
        int max = 0;
        try {
            final Player p = sa.getActivatingPlayer();
            if (p != null) {
                final int available = ComputerUtilMana.getAvailableManaEstimate(p);
                final int fixed = sa.getPayCosts() == null || sa.getPayCosts().hasNoManaCost()
                        ? 0 : sa.getPayCosts().getTotalMana().getCMC();
                max = Math.max(0, available - fixed);
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("X ceiling estimate failed", e);
        }
        o.addProperty("max", max);
        return o;
    }

    /**
     * The modes of a modal ability, with how many must be chosen.
     *
     * <p>Forge picks modes inside {@code handlePlayingSpellAbility}, i.e. after the host
     * has already answered the priority ask, so without this the host is pricing "cast
     * Charm" with no idea which halves are even legal. {@code makePossibleOptions} already
     * drops modes whose targets do not exist.
     */
    private static JsonArray encodeModes(final SpellAbility sa) {
        final JsonArray a = new JsonArray();
        if (sa.getApi() != ApiType.Charm) {
            return a;
        }
        try {
            final List<AbilitySub> options = CharmEffect.makePossibleOptions(sa);
            for (int i = 0; i < options.size(); i++) {
                final AbilitySub sub = options.get(i);
                final JsonObject m = new JsonObject();
                m.addProperty("index", i);
                m.addProperty("api", sub.getApi() == null ? "" : sub.getApi().toString());
                m.addProperty("description", String.valueOf(sub.getDescription()));
                m.addProperty("usesTargeting", sub.usesTargeting());
                a.add(m);
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("mode enumeration failed for " + sa, e);
        }
        return a;
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
