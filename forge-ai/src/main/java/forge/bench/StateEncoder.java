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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.card.CardStateName;
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
 * than stock Forge in some paths (for example {@code ChangeZoneAi}'s opponent-type
 * heuristic reads hidden identities). Public hand-size comparisons alone are not
 * a hidden-information leak. Default Forge and equal-information comparisons are
 * different estimands and must be named separately.
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
        // Protocol v2.15: how many land plays this player gets THIS TURN. The host reads
        // `landLimit - landsPlayed` and had no wire field for the first half, so it
        // hardcoded 1 -- wrong for every turn a player controls Azusa, Oracle of Mul Daya,
        // Exploration or Dryad of the Ilysian Grove, all of which are in the bench cube.
        // Not derivable host-side: inferring it from whether a land play appears in the
        // priority menu would make a state field a function of a menu, and the menu is
        // built AFTER the limit is consulted. `getMaxLandPlays()` is the engine's own
        // answer (base 1 plus every active adjustment); `maxLandPlaysInfinite` is the
        // separate dev-mode/effect flag that no integer can express.
        o.addProperty("maxLandPlays", p.getMaxLandPlays());
        o.addProperty("maxLandPlaysInfinite", p.getMaxLandPlaysInfinite());
        o.addProperty("librarySize", p.getCardsIn(ZoneType.Library).size());
        o.addProperty("handSize", p.getCardsIn(ZoneType.Hand).size());
        // Protocol v2.19: the STORM COUNT for this player, this turn.
        //
        // `decodeState` builds its TableView off `emptyTableView`, whose `spellsCast` is
        // [0, 0], and no wire field ever overwrote it -- so every bridged frame ever
        // recorded showed the pilot a storm count of zero. Not derivable host-side: the
        // bridge publishes no game log for `TableView.log` to count, and an ask shows a
        // seat only its own questions, so the OPPONENT's casts are invisible between our
        // asks even in principle. `getSpellsCastThisTurn()` is the engine's own count --
        // the stack's spells-cast-this-turn list filtered to this activating player,
        // which is what CR 702.40a (storm) itself reads -- and it resets at the turn
        // boundary the same way the native `spellsCastThisTurn` does.
        o.addProperty("spellsCastThisTurn", p.getSpellsCastThisTurn());
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
        final JsonObject encoded = encodeCardUnchecked(c);
        // Visibility of an object is not visibility of its face. A face-down
        // battlefield object is public, while Thief of Sanity's exiled card is
        // private but its face is known to the player granted permission to look.
        // Keep effective characteristics untouched; publish knowledge separately.
        if (viewer != null && c.isFaceDown() && cv.canFaceDownBeShownTo(viewer)) {
            final var face = c.getState(CardStateName.Original);
            final JsonObject known = new JsonObject();
            known.addProperty("name", face.getName());
            known.addProperty("types", face.getType().toString());
            known.addProperty("manaCost", face.getManaCost().toString());
            known.addProperty("cmc", face.getManaCost().getCMC());
            if (face.getType().isCreature()) {
                known.addProperty("power", face.getBasePower());
                known.addProperty("toughness", face.getBaseToughness());
            }
            final JsonArray keywords = new JsonArray();
            for (final KeywordInterface keyword : face.getIntrinsicKeywords()) keywords.add(keyword.getOriginal());
            known.add("keywords", keywords);
            encoded.add("knownFace", known);
        }
        return encoded;
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
        // Protocol v2.15 sent `producedMana` only when non-empty, and v2.15's own comment
        // here claimed that kept "absent" and "produces nothing" distinguishable. IT DID
        // NOT: absent carried FOUR meanings the host could not separate -- a pre-2.15 jar,
        // no mana ability at all, an ability that currently produces nothing (an
        // un-imprinted Chrome Mox, a level-0 Joraga Treespeaker), and an enumeration that
        // threw -- so the host's only safe reading was "keep the printed-text derivation",
        // and it therefore offered mana that does not exist for every permanent whose
        // ORACLE TEXT says "add" while its mana abilities say nothing (614 phantom taps
        // across 9.5% of frames, measured on the host side).
        //
        // v2.16 states the fact instead of leaving it inferable: `producedManaKnown` is
        // true exactly when the enumeration SUCCEEDED, whatever it found. Absent now means
        // one thing only -- this JVM cannot say -- which is what a 2.15-and-older jar
        // conveys by never sending the key at all. `producedMana` itself keeps its v2.15
        // shape (omitted when empty) so no observation that already carried it changes.
        final JsonArray produced = encodeProducedMana(c);
        if (produced != null) {
            if (produced.size() > 0) {
                o.add("producedMana", produced);
            }
            o.addProperty("producedManaKnown", true);
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
            // Protocol v2.15: which SPACE that id lives in. An Aura may enchant a PLAYER
            // (CR 303.4a), whose id is a seat index, and a Card's id is an fid from 1 --
            // the same two-namespaces-in-one-int shape `entityRef` was introduced for at
            // v2.8 and `targetIds` was repaired for on the host side. A host resolving a
            // bare `attachedTo` through its card map silently gets the wrong object.
            o.addProperty("attachedToKind", attachedTo instanceof Player ? "player"
                    : attachedTo instanceof Card ? "card" : "other");
        }
        return o;
    }

    /**
     * The colours of mana this permanent's own mana abilities can produce (protocol v2.15).
     *
     * <p>The host derived this from PRINTED ORACLE TEXT, which works for a cube card and
     * cannot work at all for a TOKEN: a Treasure, a Blood or a Food has no printed text to
     * read and no cube entry to read it from, so a bridged seat holding three Treasures
     * believed it had no mana. Re-deriving what the engine already knows is also the exact
     * shape of the largest correctness defect this bridge has had — a choice list
     * ("Add {W} or {U}") parsed to its first symbol on 63 of 540 cube cards.
     *
     * <p>Asked through {@link Card#canProduceColorMana}, which is the engine's own answer
     * and already walks reflected mana ({@code ManaReflected}) and combo mana. One colour
     * at a time so the result is the full set rather than a boolean, and colourless is
     * included — {@code COLORS_AND_COLORLESS} is the six-symbol vocabulary the host's
     * {@code ManaType} uses. Read-only: no {@code setActivatingPlayer}, so encoding a state
     * cannot perturb one.
     *
     * <p><b>Protocol v2.16 — the return is now NULLABLE, and that is the whole point.</b>
     * An empty array is a real answer: <i>this object's mana abilities produce nothing
     * right now</i>. {@code null} is the absence of an answer: the enumeration threw and
     * this JVM cannot say. Through v2.15 the two shared one encoding (empty), the caller
     * omitted the key for both, and the host could only ever fall back to printed oracle
     * text — which reads "add" on a loyalty ability (CR 605.1a excludes those from mana
     * abilities by name), on a triggered ability that fires off SOMEONE ELSE'S tap
     * ("whenever you tap a Forest for mana, add an additional {G}"), and on a Chrome Mox
     * that has imprinted nothing. All three are mana the permanent cannot make, and the
     * host offered every one of them.
     *
     * <p>Both of the callers' branches are load-bearing, so neither is folded away:
     * {@code getManaAbilities().isEmpty()} is "no mana ability exists" and the per-colour
     * loop falling through is "the ability exists and answers false to every colour".
     * They mean different things to a rules lawyer and the same thing to a host paying a
     * cost, which is why one empty array serves for both.
     */
    public static JsonArray encodeProducedMana(final Card c) {
        final JsonArray a = new JsonArray();
        try {
            if (c.getManaAbilities().isEmpty()) {
                return a;
            }
            for (final String col : MagicColor.Constant.COLORS_AND_COLORLESS) {
                if (c.canProduceColorMana(ImmutableSet.of(col))) {
                    a.add(MagicColor.toShortString(col));
                }
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("mana production enumeration failed for " + c, e);
            // NOT an empty array: an empty array now ASSERTS "produces nothing", and this
            // path knows nothing at all. The caller omits both keys and the host keeps its
            // printed derivation, exactly as a pre-2.16 jar leaves it.
            return null;
        }
        return a;
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

    /**
     * The one thing a stack entry never said: whether it is a spell or an ability.
     *
     * <p>Protocol v2.14. The vocabulary deliberately mirrors the host engine's own
     * {@code StackEntry.kind} (<code>'spell' | 'activated' | 'triggered'</code>) so the two
     * engines decode into the same three words, with two more for the cases Forge has and
     * the host's cube does not produce.
     *
     * <p>Order is load-bearing. A trigger reaches the stack as a {@code WrappedAbility}
     * whose {@code isActivatedAbility()} may also answer true for the ability it wraps, so
     * the trigger test comes first; a replacement effect is likewise not an activation the
     * player made. Returns {@code null} — and the caller then emits nothing at all — when
     * the instance carries no {@link SpellAbility}, so the host's fallback engages instead
     * of a wrong word being asserted with the full authority of the wire.
     */
    static String stackKind(final SpellAbility sa) {
        if (sa == null) {
            return null;
        }
        try {
            if (sa.isSpell()) {
                return "spell";
            }
            if (sa.isTrigger()) {
                return "triggered";
            }
            if (sa.isReplacementAbility()) {
                return "replacement";
            }
            if (sa.isActivatedAbility()) {
                return "activated";
            }
            // Not a spell, not a trigger, not a replacement, not an activation: a static
            // ability Forge has put on the stack. It is an ABILITY, which is the half the
            // host actually branches on, so say so rather than dropping the field.
            return "other";
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("stack kind classification failed for " + sa, e);
            return null;
        }
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
            // Protocol v2.14: WHAT this entry is. Nothing on the wire distinguished a
            // spell from an ability before, because `fid` is the SOURCE card's id and
            // every entry has one — an activated ability's and a trigger's included.
            // A host deriving "ability" from `fid == null` therefore read `false` for
            // every entry ever sent. `stackKind` is the only honest answer and the JVM
            // is the only place that knows it: `description` cannot carry it, since
            // Firebolt-the-spell and Scavenging Ooze's activation both render
            // "Name (id) - <effect>". Absent = a pre-2.14 jar; the host falls back to
            // its old reading rather than guessing.
            final String kind = stackKind(si.getSpellAbility());
            if (kind != null) {
                o.addProperty("kind", kind);
                o.addProperty("isAbility", !"spell".equals(kind));
            }
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
            // Protocol v2.15. `defenderId` is ONE INT OVER TWO NAMESPACES -- a player's id
            // is its seat index, a planeswalker's or battle's is its fid, and both count
            // from 0/1 -- so the host could not tell an attack on the face from an attack
            // on a walker and recorded every attack as an attack on the FACE. That is not
            // a cosmetic misread: it inflates the perceived clock at exactly the frames
            // where the clock decides whether to block. The `attackers` ASK has carried
            // `legalPairsTyped` since v2.8; the STATE's combat block never did.
            //
            // Emitted only for the two kinds that exist, so ABSENT means "this jar does
            // not say" and a pre-2.15 host keeps the reading it had. `defenderSeat` is the
            // turn-order index, which is the space the host's seat map speaks -- the same
            // reason `encodeStackCandidate` publishes `controllerSeat` beside `controller`.
            if (def instanceof Player) {
                e.addProperty("defenderKind", "player");
                e.addProperty("defenderSeat", playerIndex(game, (Player) def));
            } else if (def instanceof Card) {
                e.addProperty("defenderKind", "card");
            }
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

    /**
     * A description that cannot throw. Returns a placeholder rather than propagating a
     * rendering failure into the game loop.
     */
    private static String safeText(final SpellAbility sa, final boolean stack) {
        if (sa.getHostCard() == null) {
            return stack ? "(no host card)" : "(no host card)";
        }
        try {
            return String.valueOf(stack ? sa.getStackDescription() : sa.toString());
        } catch (RuntimeException | StackOverflowError e) {
            JsonRpcChannel.log("ability description failed to render: " + e);
            return "(undescribable)";
        }
    }

    /** Compact summary of a spell/ability for a {@code priority} menu entry. */
    public static JsonObject encodeSpellAbility(final SpellAbility sa) {
        return encodeSpellAbility(sa, null);
    }

    /** Face knowledge belongs to the receiving viewer, not the ability's actor. */
    public static JsonObject encodeSpellAbility(final SpellAbility sa, final PlayerView viewer) {
        final JsonObject o = new JsonObject();
        if (sa == null) {
            o.addProperty("pass", true);
            o.addProperty("description", "(pass)");
            return o;
        }
        final Card host = sa.getHostCard();
        o.addProperty("fid", host == null ? -1 : host.getId());
        String source = host == null ? "?" : host.getName();
        if (host != null && host.isFaceDown() && host.isInZone(ZoneType.Exile)
                && viewer != null && host.getView().canFaceDownBeShownTo(viewer)) {
            source = host.getState(CardStateName.Original).getName();
        }
        o.addProperty("source", source);
        o.addProperty("api", sa.getApi() == null ? "" : sa.getApi().toString());
        o.addProperty("isSpell", sa.isSpell());
        o.addProperty("isAbility", sa.isAbility());
        o.addProperty("isManaAbility", sa.isManaAbility());
        o.addProperty("isLandAbility", sa.isLandAbility());
        o.addProperty("payCosts", sa.getPayCosts() == null ? "" : sa.getPayCosts().toString());
        // Both renderings walk the HOST CARD, which can be null for an ability that has
        // been detached from its source (seen inside chooseSingleEntityForEffect). Forge's
        // own getStackDescription dereferences it, and the NPE escaped as a crashed game
        // stamped "Draw" -- two of fifteen bridged crashes in one campaign. An ability we
        // cannot describe is still an ability we must publish, so degrade the text rather
        // than the message.
        o.addProperty("description", safeText(sa, false));
        o.addProperty("stackDescription", safeText(sa, true));
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
        o.add("pendingKeywordCosts", pendingKeywordCosts(sa));
        return o;
    }

    /**
     * Optional EXTRA costs the host will be asked about after it commits to this cast
     * (protocol v2.4): Multikicker, Kicker, Casualty, Conspire, Offspring and relatives.
     *
     * <p>These are not {@code OptionalCostValue}s and never appear in {@code optionalPaid}
     * or in the rendered mana cost. Everflowing Chalice reaches the ballot as {@code {0}},
     * {@code cmc} 0, {@code optionalPaid} empty — and then
     * {@code GameActionUtil.addExtraKeywordCost} multikicks it during payment. Publishing
     * them here is what stops a free-looking ballot from being billed ten mana.
     *
     * <p>Read-only and best-effort: the keyword line is republished verbatim rather than
     * re-parsed, so this cannot disagree with the engine about what the cost is.
     */
    private static JsonArray pendingKeywordCosts(final SpellAbility sa) {
        final JsonArray a = new JsonArray();
        try {
            if (!sa.isSpell() || sa.isCopied() || sa.getHostCard() == null) {
                return a;
            }
            for (KeywordInterface kw : sa.getHostCard().getKeywords()) {
                final String original = kw.getOriginal();
                if (original == null) {
                    continue;
                }
                final boolean repeatable = original.startsWith("Multikicker");
                if (!repeatable && !original.startsWith("Kicker") && !original.startsWith("Casualty")
                        && !original.equals("Conspire") && !original.startsWith("Offspring")) {
                    continue;
                }
                final JsonObject o = new JsonObject();
                o.addProperty("keyword", original);
                o.addProperty("title", String.valueOf(kw.getTitle()));
                o.addProperty("repeatable", repeatable);
                final int colon = original.indexOf(':');
                o.addProperty("cost", colon >= 0 ? original.substring(colon + 1) : "");
                a.add(o);
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("pending keyword cost scan failed", e);
        }
        return a;
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
        o.addProperty("max", xManaCeiling(sa));
        // Protocol v2.3: `max` is mana-for-X and is NOT divided by the number of {X}
        // symbols; `symbols` says how many there are, so a host can compute the payable X
        // itself. Both are published because the TS lane's D-1 instrument is calibrated on
        // the undivided figure; `maxAnnounce` is the one the JVM will clamp an announcement
        // to, so a host that trusts it cannot over-announce.
        final int symbols = xSymbolCount(sa);
        o.addProperty("symbols", symbols);
        o.addProperty("maxAnnounce", maxAnnounceableX(sa));
        return o;
    }

    /** Mana available for X: the affordability estimate minus the cost's fixed pips. */
    public static int xManaCeiling(final SpellAbility sa) {
        try {
            final Player p = sa.getActivatingPlayer();
            if (p == null) {
                return 0;
            }
            final int available = ComputerUtilMana.getAvailableManaEstimate(p);
            final int fixed = sa.getPayCosts() == null || sa.getPayCosts().hasNoManaCost()
                    ? 0 : sa.getPayCosts().getTotalMana().getCMC();
            return Math.max(0, available - fixed);
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("X ceiling estimate failed", e);
            return 0;
        }
    }

    /** How many {X} symbols the mana cost carries. Walking Ballista is 2. */
    public static int xSymbolCount(final SpellAbility sa) {
        try {
            if (sa.getPayCosts() == null || sa.getPayCosts().hasNoManaCost()) {
                return 1;
            }
            final int n = sa.getPayCosts().getTotalMana().countX();
            return Math.max(1, n);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * The largest X the activating player can actually announce: mana-for-X divided by the
     * number of {X} symbols. A {X}{X} card at 3 available mana announces X=1, not X=3.
     */
    public static int maxAnnounceableX(final SpellAbility sa) {
        return xManaCeiling(sa) / xSymbolCount(sa);
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

    /**
     * Id-namespace offset for spell targets (protocol v2.1).
     *
     * <p>Forge counts card ids and spell-ability ids on separate sequences, so a stack
     * instance's id can collide with a battlefield card's id. A {@code targets} menu that
     * mixes both would then decode to the wrong object — silently, and only sometimes.
     * Stack candidates are therefore published at {@code SPELL_TARGET_ID_BASE + stackId};
     * ids at or above the base live in the stack namespace and nowhere else. The base is
     * positive so it never collides with the {@code -1} sentinel used elsewhere.
     */
    public static final int SPELL_TARGET_ID_BASE = 1_000_000_000;

    /**
     * One spell/ability on the stack, as a target candidate.
     *
     * <p>Carries {@code controllerSeat} as well as the raw player id: a host deciding
     * whether a counterspell is aimed at its own spell must not have to infer the seat
     * from a player id, and a fallthrough-to-"ours" there is how a pilot ends up
     * countering itself with two spells on the stack.
     */
    public static JsonObject encodeStackCandidate(final Game game, final SpellAbilityStackInstance si) {
        final JsonObject o = new JsonObject();
        final SpellAbility sa = si.getSpellAbility();
        final Card host = si.getSourceCard();
        o.addProperty("id", SPELL_TARGET_ID_BASE + si.getId());
        o.addProperty("stackId", si.getId());
        o.addProperty("kind", "spell");
        o.addProperty("zone", "Stack");
        o.addProperty("name", host == null ? "?" : host.getName());
        o.addProperty("fid", host == null ? -1 : host.getId());
        o.addProperty("isSpell", sa != null && sa.isSpell());
        // The case the bridge was blind to: a creature/artifact/enchantment spell on the
        // stack. isValid("Spell") rejects these, so getAllCandidates never returned them.
        o.addProperty("isPermanentSpell", host != null && sa != null && sa.isSpell() && host.isPermanent());
        o.addProperty("types", host == null ? "" : host.getType().toString());
        o.addProperty("manaCost", host == null ? "" : String.valueOf(host.getManaCost()));
        o.addProperty("cmc", host == null ? 0 : host.getCMC());
        if (host != null && host.isCreature()) {
            o.addProperty("power", host.getNetPower());
            o.addProperty("toughness", host.getNetToughness());
        }
        final Player act = si.getActivatingPlayer();
        o.addProperty("controller", act == null ? -1 : act.getId());
        o.addProperty("activator", act == null ? -1 : act.getId());
        o.addProperty("controllerSeat", playerIndex(game, act));
        o.addProperty("api", sa == null || sa.getApi() == null ? "" : sa.getApi().toString());
        o.addProperty("description", String.valueOf(si.getStackDescription()));
        return o;
    }

    /**
     * A typed, unambiguous reference to one entity (protocol v2.8).
     *
     * <p>A Player's id is its seat index and a Card's id is its fid starting at 1, so the
     * two spaces overlap and a bare int cannot say which is meant. Answers echo this shape.
     */
    public static JsonObject entityRef(final GameEntity ge) {
        final JsonObject o = new JsonObject();
        if (ge instanceof Player) {
            o.addProperty("kind", "player");
        } else if (ge instanceof Card) {
            o.addProperty("kind", "card");
        } else {
            o.addProperty("kind", ge == null ? "none" : ge.getClass().getSimpleName().toLowerCase());
        }
        o.addProperty("id", ge == null ? -1 : ge.getId());
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
