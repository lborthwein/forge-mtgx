package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostReturn;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane doomsday-solver-1012: the abstract model the forced-win solver's cheap width search runs on.
 *
 * <p><b>Affordances are computed, not tagged.</b> What a card DOES is learned by {@link ForcedWinProbe}: the card's
 * ability is played in a sandbox Forge copy of the position and the engine's own events are read back (cards drawn,
 * put back, milled, discarded, mana produced by colour, life on both sides, permanents entered, choice asks raised,
 * wins). Only the printed COST is read from the card (mana pips, Phyrexian pips, life, returned permanents, tap,
 * sacrifice, loyalty), because a cost is what the player sees before acting. There are no per-card or per-combo
 * tables anywhere: a Doomsday pile, a Brainstorm put-back, a storm drain and a ritual are all just observed effects.</p>
 *
 * <p>Information: our own hand, battlefield, graveyard and library contents (the seat's own deck), our life, both
 * public battlefields and the opponent's life. The library ORDER is read only when the seat built it. Nothing reads the
 * opponent's hand or library.</p>
 */
public final class ForcedWinModel {
    private ForcedWinModel() {
    }

    // colour indices: W U B R G C; ANY is a floating wildcard only
    static final int W = 0, U = 1, B = 2, R = 3, G = 4, C = 5, ANY = 6;
    static final byte[] FORGE_COLOR = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED,
            MagicColor.GREEN, MagicColor.COLORLESS};
    static final String[] SHORT = {"W", "U", "B", "R", "G", "C"};
    static final int ALL_COLORS = 0b111111;

    /**
     * Observed effect kinds. PILE: "choose n cards of our library and graveyard, put them on top in any order, exile
     * the rest" (an ordered library choice). TUTOR / TUTOR_TOP: choose a library card to hand / to the top.
     */
    enum EffKind {
        DRAW, MANA, PUTBACK, MILL, MILL_OPP, LIFE, DRAIN, DISCARD, PILE, TUTOR, TUTOR_TOP, WIN, SHUFFLE,
        /** solver-gaps-1015: our whole hand / graveyard goes into our library (Timetwister, Echo of Eons); our hand is exiled (Memory Jar). */
        HAND_TO_LIBRARY, GRAVEYARD_TO_LIBRARY, EXILE_HAND
    }

    /** solver-gaps-1015: an effect count meaning "all of them" (a whole hand discarded, shuffled in or exiled). */
    static final int ALL = 99;

    /** One effect of an ability's resolution, in observed order. */
    static final class Eff {
        final EffKind kind;
        final int n;
        /** MANA: the colours it can make (bitmask over W U B R G C). */
        final int colors;
        /**
         * LIFE (solver-gaps-1015): 0 = a fixed change of {@code n}; otherwise the change is this share of our life total
         * (observed at two life totals in the sandbox: "lose half your life, rounded up" is -0.5).
         */
        final double perLife;
        /**
         * TUTOR / TUTOR_TOP (solver-gaps-1015): the card names the engine offered in the sandbox (its search filter, as
         * observed: Trinket Mage finds a small artifact, a landcycler a land); null = any card.
         */
        final java.util.Set<String> names;

        Eff(EffKind kind, int n, int colors) {
            this(kind, n, colors, 0, null);
        }

        Eff(EffKind kind, int n, int colors, double perLife) {
            this(kind, n, colors, perLife, null);
        }

        Eff(EffKind kind, int n, int colors, double perLife, java.util.Set<String> names) {
            this.kind = kind;
            this.n = n;
            this.colors = colors;
            this.perLife = perLife;
            this.names = names;
        }

        /** LIFE: the change at life total {@code life}. */
        int lifeChange(int life) {
            if (perLife == 0) {
                return n;
            }
            final double x = perLife * life;
            return (int) (x < 0 ? Math.floor(x) : Math.ceil(x));
        }

        @Override
        public String toString() {
            return kind + "" + (perLife != 0 ? perLife + "*life" : n == ALL ? "all" : String.valueOf(n))
                    + (kind == EffKind.MANA ? ":" + colorString(colors) : "") + (names == null ? "" : names.size() + "names");
        }
    }

    /** ENGINE: a step found by the engine search (any legal action, targets recorded); ATTACK: attack with everything. */
    /** REPEAT (search-bones-1015): a loop body found by the engine search's cycle table, repeated a number of times. */
    enum AbilKind { SPELL, CYCLE, LAND, ACTIVATE, FLOAT, ENGINE, ATTACK, REPEAT }

    /** One executable ability of a card: its printed cost and its observed effects. */
    static final class Abil {
        AbilKind kind;
        /** To locate it again in a game copy: the ability's description and its cost text. */
        String desc = "";
        String costKey = "";
        final int[] pips = new int[6];
        final int[] phyrexian = new int[6];
        /** Hybrid pips (solver-gaps-1015): each a colour mask, paid by one mana of any colour in it. */
        final List<Integer> hybrid = new ArrayList<>();
        int generic;
        boolean usesX;
        int life;
        /** Returned permanents of a subtype (Gush's Islands): how many, and which subtype. */
        int returnN;
        String returnType;
        boolean tapSelf, sacSelf, discardHand;
        boolean loyalty;
        int loyaltyDelta;
        /** Playable at instant speed (an instant, flash, cycling, a non-sorcery-speed activation). */
        boolean instant;
        /** The probe's player-target variant: "self", "opp" or null (no player target). */
        String targetVariant;
        /** It targets a card (not a player): the cheap search cannot plan the target. */
        boolean cardTargets;
        final List<Eff> effects = new ArrayList<>();
        /** Storm (printed keyword): DRAIN / MILL_OPP repeat once per spell cast before it this turn. */
        boolean storm;
        /** Observed: a counterspell (it needs a spell to target). */
        boolean counter;
        /**
         * Legal hand sizes (solver-gaps-1015): bit h set = the ability may be activated with h cards in our hand (bit 11:
         * 11 or more); -1 = no hand-size condition. Observed by probing the ability's legality at each hand size.
         */
        int handMask = -1;
        /** The ability could not be played in the sandbox (illegal, unaffordable, or the probe failed). */
        boolean unplayable;
        String probeNote = "";

        boolean has(EffKind k) {
            for (Eff e : effects) {
                if (e.kind == k) {
                    return true;
                }
            }
            return false;
        }

        String costString() {
            final StringBuilder sb = new StringBuilder();
            if (generic > 0) {
                sb.append(generic);
            }
            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < pips[i]; k++) {
                    sb.append(SHORT[i]);
                }
                for (int k = 0; k < phyrexian[i]; k++) {
                    sb.append(SHORT[i]).append("/P");
                }
            }
            for (int h : hybrid) {
                sb.append('{').append(colorString(h)).append('}');
            }
            if (life > 0) {
                sb.append(" life").append(life);
            }
            if (returnN > 0) {
                sb.append(" return").append(returnN).append(returnType);
            }
            if (tapSelf) {
                sb.append(" T");
            }
            if (sacSelf) {
                sb.append(" sac");
            }
            if (discardHand) {
                sb.append(" discardHand");
            }
            if (loyalty) {
                sb.append(" loyalty").append(loyaltyDelta);
            }
            if (handMask != -1) {
                sb.append(" hand").append(Integer.toBinaryString(handMask));
            }
            return sb.length() == 0 ? "0" : sb.toString().trim();
        }

        Abil copyCost() {
            final Abil a = new Abil();
            a.kind = kind;
            a.desc = desc;
            a.costKey = costKey;
            System.arraycopy(pips, 0, a.pips, 0, 6);
            System.arraycopy(phyrexian, 0, a.phyrexian, 0, 6);
            a.hybrid.addAll(hybrid);
            a.handMask = handMask;
            a.generic = generic;
            a.usesX = usesX;
            a.life = life;
            a.returnN = returnN;
            a.returnType = returnType;
            a.tapSelf = tapSelf;
            a.sacSelf = sacSelf;
            a.discardHand = discardHand;
            a.loyalty = loyalty;
            a.loyaltyDelta = loyaltyDelta;
            a.storm = storm;
            a.instant = instant;
            return a;
        }

        @Override
        public String toString() {
            return kind + (targetVariant != null ? "@" + targetVariant : "") + "[" + costString() + " -> " + effects
                    + (storm ? " storm" : "") + (unplayable ? " UNPLAYABLE " + probeNote : "") + "]";
        }
    }

    /** A card as the cheap search sees it. A template per card name, instantiated per card id. */
    static final class ACard {
        int id;
        String name;
        boolean land;
        /** Observed (solver-gaps-1015): playing it as our land drop, it entered tapped; and the life that cost us. */
        boolean entersTapped;
        int landLife;
        /** Subtypes, for returned-permanent costs (Gush returns Islands). */
        final List<String> subtypes = new ArrayList<>();
        /** Blue pips in its mana cost (printed): our devotion while it is on the battlefield. */
        int bluePips;
        boolean permanent;
        boolean creature;
        int startLoyalty;
        /** Observed: casting it (a permanent) won outright at the probe position. */
        boolean winsOnEntry;
        /**
         * Observed: entering wins while our library holds at most this many cards (-1 = never), measured at our blue
         * devotion {@link #winDevotion} (with it on the battlefield). The search extrapolates with devotion.
         */
        int winIfLibraryAtMost = -1;
        int winDevotion;
        /** Observed: with it on the battlefield, drawing from an empty library wins. */
        boolean emptyDrawWin;
        /** Observed effects when it goes from the battlefield to our graveyard (a sacrifice: Chromatic Star). */
        final List<Eff> dies = new ArrayList<>();
        boolean counter;
        final List<Abil> fromHand = new ArrayList<>();
        final List<Abil> onBattlefield = new ArrayList<>();
        /** On the battlefield: a plain mana source (engine metadata of its mana abilities). */
        int sourceColors;
        int sourceAmount;
        boolean sourceSac;
        boolean sourceSick;
        /** It has a castable ability the width model cannot express (card targets, a cost outside the model). */
        boolean unmodeled;
        String note = "";

        ACard instance(int newId) {
            final ACard a = new ACard();
            a.id = newId;
            a.name = name;
            a.land = land;
            a.entersTapped = entersTapped;
            a.landLife = landLife;
            a.subtypes.addAll(subtypes);
            a.bluePips = bluePips;
            a.permanent = permanent;
            a.creature = creature;
            a.startLoyalty = startLoyalty;
            a.winsOnEntry = winsOnEntry;
            a.winIfLibraryAtMost = winIfLibraryAtMost;
            a.winDevotion = winDevotion;
            a.emptyDrawWin = emptyDrawWin;
            a.dies.addAll(dies);
            a.counter = counter;
            a.fromHand.addAll(fromHand);
            a.onBattlefield.addAll(onBattlefield);
            a.sourceColors = sourceColors;
            a.sourceAmount = sourceAmount;
            a.sourceSac = sourceSac;
            a.unmodeled = unmodeled;
            a.note = note;
            return a;
        }

        boolean wins() {
            return winsOnEntry || winIfLibraryAtMost >= 0 || emptyDrawWin
                    || fromHand.stream().anyMatch(a -> a.has(EffKind.WIN) || a.storm && (a.has(EffKind.DRAIN)
                            || a.has(EffKind.MILL_OPP)));
        }

        boolean draws() {
            for (Abil a : fromHand) {
                if (a.has(EffKind.DRAW)) {
                    return true;
                }
            }
            for (Abil a : onBattlefield) {
                if (a.has(EffKind.DRAW)) {
                    return true;
                }
            }
            return dies.stream().anyMatch(e -> e.kind == EffKind.DRAW);
        }

        boolean mana() {
            if (sourceColors != 0) {
                return true;
            }
            for (Abil a : fromHand) {
                if (a.has(EffKind.MANA)) {
                    return true;
                }
            }
            for (Abil a : onBattlefield) {
                if (a.has(EffKind.MANA)) {
                    return true;
                }
            }
            return false;
        }

        boolean useful() {
            return land || wins() || draws() || mana() || counter
                    || fromHand.stream().anyMatch(a -> !a.unplayable && (a.has(EffKind.PILE) || a.has(EffKind.TUTOR)
                            || a.has(EffKind.TUTOR_TOP)));
        }

        /** A coarse class for ordering only (never a rule): win, draw, mana, land, protection, other. */
        int order() {
            if (wins()) {
                return 0;
            }
            if (draws()) {
                return 1;
            }
            if (mana() && !land) {
                return 2;
            }
            if (land) {
                return 3;
            }
            if (counter) {
                return 4;
            }
            return 5;
        }

        String role() {
            final StringBuilder sb = new StringBuilder();
            if (wins()) {
                sb.append("win");
            }
            if (draws()) {
                sb.append(sb.length() > 0 ? "+" : "").append("draw");
            }
            if (mana()) {
                sb.append(sb.length() > 0 ? "+" : "").append(land ? "land" : "mana");
            }
            if (counter) {
                sb.append(sb.length() > 0 ? "+" : "").append("counter");
            }
            return sb.length() == 0 ? "other" : sb.toString();
        }

        @Override
        public String toString() {
            return name + "#" + id + "(" + role() + ")";
        }
    }

    static String colorString(int mask) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if ((mask & (1 << i)) != 0) {
                sb.append(SHORT[i]);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ printed costs

    /** The printed cost of an ability, or null when it is outside the model (exile a card, sacrifice another, X). */
    static Abil readCost(SpellAbility sa, AbilKind kind) {
        final Abil ab = new Abil();
        ab.kind = kind;
        ab.desc = sa.getDescription();
        final Cost cost = sa.getPayCosts();
        ab.costKey = cost == null ? "" : cost.toString();
        ab.storm = sa.getHostCard() != null && sa.getHostCard().hasKeyword(Keyword.STORM) && sa.isSpell();
        final forge.game.card.Card host = sa.getHostCard();
        if (sa.isSpell()) {
            ab.instant = host != null && (host.isInstant() || sa.getActivatingPlayer() != null && sa.withFlash(host, sa.getActivatingPlayer()));
        } else {
            ab.instant = !sa.getRestrictions().isSorcerySpeed() && !sa.isPwAbility();
        }
        if (cost == null) {
            return ab;
        }
        for (CostPart p : cost.getCostParts()) {
            if (p instanceof CostPartMana) {
                continue;
            } else if (p instanceof CostPayLife pl) {
                final Integer n = pl.convertAmount();
                if (n == null) {
                    return null;
                }
                ab.life += n;
            } else if (p instanceof CostReturn r && !r.payCostFromSource()) {
                final Integer n = r.convertAmount();
                if (n == null || r.getType() == null || r.getType().contains(".") || r.getType().contains(",")) {
                    return null;
                }
                ab.returnN += n;
                ab.returnType = r.getType();
            } else if (p instanceof CostTap) {
                ab.tapSelf = true;
            } else if (p instanceof CostSacrifice s && s.payCostFromSource()) {
                ab.sacSelf = true;
            } else if (p instanceof CostDiscard d && d.payCostFromSource()) {
                continue; // cycling: discard this card
            } else if (p instanceof CostDiscard d && "Hand".equals(d.getType())) {
                ab.discardHand = true;
            } else if (p instanceof CostPutCounter pc && pc.getCounter().is(CounterEnumType.LOYALTY)) {
                ab.loyalty = true;
                final Integer n = pc.convertAmount();
                ab.loyaltyDelta = n == null ? 0 : n;
            } else if (p instanceof CostRemoveCounter rc && rc.counter.is(CounterEnumType.LOYALTY)) {
                ab.loyalty = true;
                final Integer n = rc.convertAmount();
                if (n == null) {
                    return null;
                }
                ab.loyaltyDelta = -n;
            } else {
                return null;
            }
        }
        readMana(ab, cost.getTotalMana());
        return ab.usesX ? null : ab;
    }

    static void readMana(Abil ab, ManaCost mc) {
        if (mc == null) {
            return;
        }
        ab.generic += mc.getGenericCost();
        for (ManaCostShard s : mc) {
            if (s.isOfKind(ManaAtom.IS_X)) {
                ab.usesX = true;
                continue;
            }
            if (s.isGeneric() && !s.isOr2Generic()) {
                continue;
            }
            int colour = -1, n = 0;
            for (int i = 0; i < 5; i++) {
                if (s.isColor(FORGE_COLOR[i])) {
                    colour = i;
                    n++;
                }
            }
            if (n == 0 && s.isColorless()) {
                ab.pips[C]++;
            } else if (n == 1 && s.isPhyrexian()) {
                ab.phyrexian[colour]++;
            } else if (n == 1 && !s.isOr2Generic()) {
                ab.pips[colour]++;
            } else if (n >= 2 && !s.isOr2Generic() && !s.isPhyrexian()) {
                // solver-gaps-1015: a hybrid pip is one mana of one of its colours, not any mana
                int mask = 0;
                for (int i = 0; i < 5; i++) {
                    if (s.isColor(FORGE_COLOR[i])) {
                        mask |= 1 << i;
                    }
                }
                ab.hybrid.add(mask);
            } else {
                ab.generic += s.isOr2Generic() ? 2 : 1;
            }
        }
    }

    static int bluePips(Card card) {
        int n = 0;
        for (ManaCostShard shard : card.getManaCost()) {
            if (shard.isColor(MagicColor.BLUE)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ positions

    /** The starting position of a search. */
    static final class Position {
        int life, oppLife, turn;
        int oppLibrary;
        boolean landDrop;
        boolean sorcerySpeed;
        int storm;
        final int[] pool = new int[6];
        final List<ACard> hand = new ArrayList<>();
        final List<ACard> battlefield = new ArrayList<>();
        final List<Boolean> tapped = new ArrayList<>();
        /** solver-gaps-1015: a planeswalker that has already used its loyalty ability this turn (parallel to battlefield). */
        final List<Boolean> used = new ArrayList<>();
        /**
         * solver-gaps-1015: the opponent's visible clock, their creatures' combat damage, largest first: a line that passes
         * the turn must survive it (our creatures each block one attacker).
         */
        final List<Integer> oppPowers = new ArrayList<>();
        int devotionBlue;
        boolean emptyDrawWinLive;
        /**
         * Our library contents (ids sorted; for tutors and ordered library choices) and our graveyard. solver-gaps-1015:
         * the library list leaves out the known top ({@link #knownTop}).
         */
        final List<ACard> library = new ArrayList<>();
        final List<ACard> graveyard = new ArrayList<>();
        /** solver-gaps-1015: our graveyard's size (its cards are read only when something could choose them). */
        int graveyardSize;
        /**
         * Our library's known top, top first (solver-gaps-1015: pile memory, a prefix of the library whose cards the seat
         * put there or ordered itself); null = the whole order is unknown.
         */
        List<Integer> knownOrder;
        final List<ACard> knownTop = new ArrayList<>();
        int librarySize;
        boolean canDraw = true;
        /** Defense: the card of the spell on the stack whose resolution is asked about (cast and paid: free), or -1. */
        int pendingId = -1;

        String describe() {
            return "life=" + life + " opp=" + oppLife + " oppLib=" + oppLibrary + " landDrop=" + landDrop + " storm="
                    + storm + " devotion=" + devotionBlue + " pool=" + java.util.Arrays.toString(pool) + " hand=" + hand
                    + " bf=" + battlefield + " lib=" + librarySize + "/" + library.size()
                    + (knownOrder != null ? " knownTop=" + knownTop : "")
                    + " gy=" + graveyard.size();
        }
    }

    /**
     * Reads positions, probing each distinct card name once (cached for the reader's lifetime: one seat, one game).
     */
    static final class Reader {
        final Map<String, ACard> templates = new HashMap<>();
        final ForcedWinProbe.Stats probeStats = new ForcedWinProbe.Stats();
        long seed = 20261012L;

        /** strong-opp-1020: the learned templates as JSON (card name -> template), for a precomputed cache. */
        synchronized String exportJson() {
            return new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(templates);
        }

        /**
         * strong-opp-1020: preload templates learned offline (a name already learned here is kept). Affordances depend
         * only on the card (each is learned in a sandbox copy with rich floating mana), so a cache learned once serves
         * every game; it saves the defense's first-call probes (about 0.15 s per new card name).
         */
        synchronized int preload(String json) {
            final java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<Map<String, ACard>>() { }.getType();
            final Map<String, ACard> m = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create()
                    .fromJson(json, t);
            int n = 0;
            for (Map.Entry<String, ACard> e : m.entrySet()) {
                if (e.getValue() != null && templates.putIfAbsent(e.getKey(), e.getValue()) == null) {
                    n++;
                }
            }
            return n;
        }

        /** search-bones-1015: synchronized (a reader may be shared by the play-outs' threat checks, (d)). */
        synchronized ACard card(Game live, Player me, Card c) {
            ACard t = templates.get(c.getName());
            if (t == null) {
                t = ForcedWinProbe.learn(live, me, c, seed, probeStats);
                templates.put(c.getName(), t);
            }
            return t.instance(c.getId());
        }

        /**
         * Read the seat's position; null when it is outside the model (more than one opponent). The library and
         * graveyard cards are learned only when {@code libraryIfUseful} and something in our hand or on our
         * battlefield can choose library cards (an observed PILE or TUTOR effect), or when the order is known.
         */
        Position read(Game live, Player me, List<Integer> knownOrder, boolean libraryIfUseful) {
            return read(live, me, knownOrder, libraryIfUseful, null, null);
        }

        /**
         * As {@link #read(Game, Player, List, boolean)}, from {@code viewer}'s knowledge when it is not {@code me}
         * (defense: the opponent's position as we see it): hand cards {@code viewer} cannot see are left out, and
         * {@code pending} (a spell on the stack) is added to the hand as the line's forced first step.
         */
        Position read(Game live, Player me, List<Integer> knownOrder, boolean libraryIfUseful, Player viewer, Card pending) {
            final Position p = new Position();
            p.life = me.getLife();
            Player opp = null;
            for (Player o : me.getOpponents()) {
                if (opp != null) {
                    return null;
                }
                opp = o;
            }
            if (opp == null) {
                return null;
            }
            p.oppLife = opp.getLife();
            p.oppLibrary = opp.getCardsIn(ZoneType.Library).size();
            for (Card c : opp.getCreaturesInPlay()) {
                final int dmg = Math.max(0, c.getNetCombatDamage());
                if (dmg > 0) {
                    p.oppPowers.add(dmg);
                }
            }
            p.oppPowers.sort(java.util.Collections.reverseOrder());
            p.turn = live.getPhaseHandler().getTurn();
            p.sorcerySpeed = me.canCastSorcery();
            p.landDrop = live.getPhaseHandler().isPlayerTurn(me) && me.getLandsPlayedThisTurn() < me.getMaxLandPlays();
            p.storm = live.getStack().getSpellsCastThisTurn().size();
            for (int i = 0; i < 6; i++) {
                p.pool[i] = me.getManaPool().getAmountOfColor(FORGE_COLOR[i]);
            }
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (viewer != null && viewer != me && !c.getView().canBeShownTo(viewer.getView())) {
                    continue;
                }
                p.hand.add(card(live, me, c));
            }
            if (pending != null) {
                p.hand.add(card(live, me, pending));
                p.pendingId = pending.getId();
            }
            p.devotionBlue = me.getDevotionMod();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (c.isFaceDown()) {
                    continue;
                }
                final ACard a = card(live, me, c);
                a.sourceSick = c.isCreature() && c.isSick();
                if (a.sourceSick) {
                    a.sourceColors = 0;
                }
                if (c.isPlaneswalker()) {
                    a.startLoyalty = c.getCounters(CounterEnumType.LOYALTY);
                }
                p.battlefield.add(a);
                p.tapped.add(c.isTapped());
                p.used.add(c.isPlaneswalker() && c.getPlaneswalkerAbilityActivated() > 0);
                p.devotionBlue += a.bluePips;
                p.emptyDrawWinLive |= a.emptyDrawWin;
            }
            final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
            p.librarySize = lib.size();
            if (knownOrder != null && !knownOrder.isEmpty() && knownOrder.size() <= lib.size()) {
                // solver-gaps-1015: a known PREFIX of the library (the seat's pile memory)
                boolean prefix = true;
                for (int i = 0; i < knownOrder.size() && prefix; i++) {
                    prefix = lib.get(i).getId() == knownOrder.get(i);
                }
                if (prefix) {
                    p.knownOrder = new ArrayList<>(knownOrder);
                    for (int i = 0; i < knownOrder.size(); i++) {
                        p.knownTop.add(card(live, me, lib.get(i)));
                    }
                }
            }
            boolean chooses = false;
            if (viewer != null && viewer != me) {
                // defense: the opponent's library contents (their registered deck), as the belief sampler assumes
                chooses = true;
            }
            for (ACard a : p.hand) {
                for (Abil ab : a.fromHand) {
                    chooses |= !ab.unplayable && (ab.has(EffKind.PILE) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP));
                }
            }
            for (ACard a : p.battlefield) {
                for (Abil ab : a.onBattlefield) {
                    chooses |= !ab.unplayable && (ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP));
                }
            }
            if (libraryIfUseful && chooses) {
                final List<Card> rest = new ArrayList<>(lib.subList(p.knownTop.size(), lib.size()));
                rest.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
                for (Card c : rest) {
                    p.library.add(card(live, me, c));
                }
                final List<Card> gy = new ArrayList<>(me.getCardsIn(ZoneType.Graveyard));
                gy.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
                for (Card c : gy) {
                    p.graveyard.add(card(live, me, c));
                }
            }
            p.graveyardSize = me.getCardsIn(ZoneType.Graveyard).size();
            p.canDraw = me.canDraw();
            return p;
        }
    }
}
