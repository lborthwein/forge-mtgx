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
        HAND_TO_LIBRARY, GRAVEYARD_TO_LIBRARY, EXILE_HAND,
        /** labeller-v2 (c): scry n / surveil n of our library (observed only by a planScry reader). */
        SCRY, SURVEIL,
        /**
         * sdcc-draws-1110 (observed only by a probeSettle reader): the ability's own card goes to our library ("draw a
         * card, then put this on top of your library"); n is where: {@link #LIB_TOP}, {@link #LIB_BOTTOM} or
         * {@link #LIB_SOMEWHERE}.
         */
        SELF_TO_LIBRARY,
        /**
         * sdcc-tutorcast-1110 (observed only by a tutorCast reader): search our library for a card, which its controller
         * then casts at once without paying its mana cost. {@link Eff#names}: the card names the engine offered to cast
         * that way in the sandbox (one run per card name of ours); {@link Eff#n} = 1: a searched card that is not cast
         * goes to our hand (observed), 0: it does not (such a pick is not planned).
         */
        TUTOR_CAST
    }

    /** solver-gaps-1015: an effect count meaning "all of them" (a whole hand discarded, shuffled in or exiled). */
    static final int ALL = 99;

    /** sdcc-draws-1110: where a SELF_TO_LIBRARY card went, as the engine placed it in the sandbox. */
    static final int LIB_TOP = 0, LIB_BOTTOM = 1, LIB_SOMEWHERE = 2;

    /**
     * solver-distill-cc-1109 (REACH sig): a card model's identity for the planner: every observed field of the card, its
     * abilities and their effects, except its id, its name and free-text notes or locators (desc, costKey, notes). Two
     * cards with equal signatures are the same card to the width search, apart from the name-dependent paths (tutor name
     * filters, same-name classes), which the caller keeps apart.
     */
    static String signature(final ACard a) {
        final StringBuilder sb = new StringBuilder();
        sigAppend(sb, a, 0);
        return sb.toString();
    }

    private static final java.util.Set<String> SIG_SKIP = java.util.Set.of("id", "name", "note", "desc", "costKey",
            "probeNote", "again"); // sdcc-bridge-1111: again orders setup lines only, never a card's class
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Field[]> SIG_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static java.lang.reflect.Field[] sigFields(final Class<?> c) {
        return SIG_FIELDS.computeIfAbsent(c, k -> {
            final List<java.lang.reflect.Field> out = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                for (java.lang.reflect.Field f : x.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic()
                            || SIG_SKIP.contains(f.getName())) {
                        continue;
                    }
                    f.setAccessible(true);
                    out.add(f);
                }
            }
            out.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
            return out.toArray(new java.lang.reflect.Field[0]);
        });
    }

    private static void sigAppend(final StringBuilder sb, final Object o, final int depth) {
        if (o == null) {
            sb.append('~');
            return;
        }
        if (depth > 8) {
            sb.append('#');
            return;
        }
        final Class<?> c = o.getClass();
        if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof CharSequence
                || c.isEnum()) {
            sb.append(o);
            return;
        }
        if (c.isArray()) {
            final int n = java.lang.reflect.Array.getLength(o);
            sb.append('[');
            for (int i = 0; i < n; i++) {
                sigAppend(sb, java.lang.reflect.Array.get(o, i), depth + 1);
                sb.append(',');
            }
            sb.append(']');
            return;
        }
        if (o instanceof java.util.Set) {
            final List<String> parts = new ArrayList<>();
            for (Object e : (java.util.Set<?>) o) {
                final StringBuilder x = new StringBuilder();
                sigAppend(x, e, depth + 1);
                parts.add(x.toString());
            }
            java.util.Collections.sort(parts);
            sb.append('{').append(String.join(",", parts)).append('}');
            return;
        }
        if (o instanceof java.util.Map) {
            final List<String> parts = new ArrayList<>();
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) o).entrySet()) {
                final StringBuilder x = new StringBuilder();
                sigAppend(x, e.getKey(), depth + 1);
                x.append('=');
                sigAppend(x, e.getValue(), depth + 1);
                parts.add(x.toString());
            }
            java.util.Collections.sort(parts);
            sb.append('<').append(String.join(",", parts)).append('>');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            for (Object e : (Iterable<?>) o) {
                sigAppend(sb, e, depth + 1);
                sb.append(',');
            }
            sb.append(']');
            return;
        }
        if (!c.getName().startsWith("forge.ai.simulation.ForcedWinModel")) {
            sb.append(c.getSimpleName()).append('?'); // never expected in a model; stays distinct by type only
            return;
        }
        sb.append(c.getSimpleName()).append('(');
        for (java.lang.reflect.Field f : sigFields(c)) {
            sb.append(f.getName()).append('=');
            try {
                sigAppend(sb, f.get(o), depth + 1);
            } catch (IllegalAccessException e) {
                sb.append('!');
            }
            sb.append(';');
        }
        sb.append(')');
    }

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
            if (kind == EffKind.SELF_TO_LIBRARY) {
                return kind + (n == LIB_TOP ? ":top" : n == LIB_BOTTOM ? ":bottom" : ":somewhere");
            }
            return kind + "" + (perLife != 0 ? perLife + "*life" : n == ALL ? "all" : String.valueOf(n))
                    + (kind == EffKind.MANA ? ":" + colorString(colors) : "") + (names == null ? "" : names.size() + "names");
        }
    }

    /**
     * sdcc-probeobj-1111 (set only by a probeObjects reader): what a sandbox saw when an ability was played with one
     * object of ours (a card name) as its target or as the permanent sacrificed for its cost.
     */
    static final class ObjSeen {
        /** The ability as observed with this object: its cost and its effects (what the object's leaving caused included). */
        Abil abil;
        /**
         * Where the object was once everything had resolved: 0 = where it was, {@link Abil#SELF_HAND} our hand,
         * {@link Abil#SELF_GRAVEYARD} our graveyard, {@link Abil#SELF_EXILE} exile, -1 = somewhere the plan has no place for.
         */
        int to;
        /** Other cards moved in ways no effect kind expresses (a token or a library card entered the battlefield). */
        boolean unmapped;
        /**
         * sdcc-bridge-1111 (read only by a planBridge plan, to order its setup lines): the ability taps its source for its
         * cost and the sandbox left that source untapped in play once everything had resolved (what the run put onto the
         * battlefield untapped it), so the same ability was playable again after itself.
         */
        boolean again;

        /**
         * The width search can play the ability on this object: everything it did is in the model, and it did something
         * (an effect other than a shuffle, or the object came back to our hand).
         */
        boolean usable() {
            if (unmapped || to < 0 || abil == null) {
                return false;
            }
            for (Eff e : abil.effects) {
                if (e.kind != EffKind.SHUFFLE) {
                    return true;
                }
            }
            return to == Abil.SELF_HAND;
        }

        @Override
        public String toString() {
            return (abil == null ? "?" : abil.effects.toString()) + (to == 0 ? "" : to == Abil.SELF_HAND ? ">hand"
                    : to == Abil.SELF_GRAVEYARD ? ">graveyard" : to == Abil.SELF_EXILE ? ">exile" : ">?")
                    + (unmapped ? " unmapped" : "") + (again ? " again" : "");
        }
    }

    /**
     * sdcc-probeobj-1111 (set only by a probeObjects reader; null otherwise): the object an ability needs before it can
     * be played, as the engine showed it. Nothing is read from the card's text or name: {@link #valid} holds the names
     * of our cards the engine's own validity check accepts, {@link #seen} those a sandbox then played the ability with
     * (one run per name: objects in play in the position first, then cards in our hand, else a stand-in of ours put
     * into the zone), each with what that run did.
     */
    static final class ObjNeed {
        /** {@link #SAC}: a permanent of ours sacrificed as a cost; {@link #TARGET}: the one card the ability targets. */
        String role = "";
        static final String SAC = "sac", TARGET = "target";
        /** TARGET: which link of the ability's chain targets (0 = the ability itself). */
        int link;
        /** The zone the object is in: "Battlefield" or "Graveyard". */
        String zone = "Battlefield";
        final java.util.Set<String> valid = new java.util.TreeSet<>();
        final Map<String, ObjSeen> seen = new java.util.concurrent.ConcurrentSkipListMap<>();
        /** Names a sandbox could not play it with. */
        final java.util.Set<String> tried = new java.util.concurrent.ConcurrentSkipListSet<>();
        /** A sandbox played it on a card the opponent controls (the width model holds no such card: never planned). */
        boolean opp;

        boolean anyUsable() {
            for (ObjSeen o : seen.values()) {
                if (o.usable()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "needs " + role + ":" + zone + " seen=" + seen + (opp ? " +opp" : "") + " valid=" + valid.size();
        }
    }

    /** ENGINE: a step found by the engine search (any legal action, targets recorded); ATTACK: attack with everything. */
    /** REPEAT (search-bones-1015): a loop body found by the engine search's cycle table, repeated a number of times. */
    enum AbilKind { SPELL, CYCLE, LAND, ACTIVATE, FLOAT, ENGINE, ATTACK, REPEAT, MAIN }

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
        /**
         * sdcc-tutorcast-1110 (set only by a sourceLeaves reader; 0 otherwise, and then nothing changes): where the
         * engine left this activated ability's own source once it had resolved, when that is no longer the battlefield
         * and leaving was not its cost: {@link #SELF_TOP} the top of our library, {@link #SELF_HAND} our hand,
         * {@link #SELF_EXILE} exile, {@link #SELF_GRAVEYARD} our graveyard. Observed in the sandbox.
         */
        int selfTo;
        static final int SELF_TOP = 1, SELF_HAND = 2, SELF_EXILE = 3, SELF_GRAVEYARD = 4;
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
        /**
         * sdcc-tutorcast-1110 (set only by a tutorCast reader; 0 / null / false otherwise, and then nothing changes).
         * sacN: other permanents of ours sacrificed as a cost (1 or 0); sacNames: the names of our cards the engine's
         * own validity check accepts for it. optCosts: the optional additional costs this variant of the spell pays (the
         * engine's type names), by which the follower builds the ability again. alt: an alternative cost of the card,
         * never the spell an effect casts without paying its mana cost.
         */
        int sacN;
        java.util.Set<String> sacNames;
        List<String> optCosts;
        boolean alt;
        /** sdcc-probeobj-1111 (set only by a probeObjects reader; null otherwise, and then nothing changes). */
        ObjNeed obj;

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
            if (sacN > 0) {
                sb.append(" sacOther").append(sacN).append(sacNames == null ? "" : sacNames.toString());
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
            a.sacN = sacN;
            a.sacNames = sacNames;
            a.optCosts = optCosts;
            a.alt = alt;
            return a;
        }

        @Override
        public String toString() {
            return kind + (targetVariant != null ? "@" + targetVariant : "") + "[" + costString() + " -> " + effects
                    + (selfTo == 0 ? "" : " selfTo" + selfTo)
                    + (storm ? " storm" : "") + (unplayable ? " UNPLAYABLE " + probeNote : "")
                    + (obj == null ? "" : " " + obj) + "]";
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
        /**
         * solver-distill-cc-1109 (planLoops; empty unless the solver's planLoops is on): the creature names with which
         * this permanent's tap ability that targets a card makes an unbounded attack, as the engine found it in a sandbox
         * holding only our lands, this permanent and that creature (no card knowledge: the pairs are observed). The
         * probe runs once per reader template; loopHaste = it can tap the turn it enters.
         */
        final java.util.Set<String> loopWith = new java.util.TreeSet<>();
        boolean loopChecked, loopHaste;
        /** planLoops: the loop starts by casting this card (an Aura) on a partner that could already tap. */
        boolean loopFromHand;
        /** sdcc-probeobj-1111: an ability of it holds an {@link ObjNeed} (false unless a probeObjects reader learned it). */
        boolean hasObj;

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
            a.loopWith.addAll(loopWith);
            a.loopChecked = loopChecked;
            a.loopHaste = loopHaste;
            a.loopFromHand = loopFromHand;
            a.hasObj = hasObj;
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
                            || a.has(EffKind.TUTOR_TOP) || a.has(EffKind.TUTOR_CAST)));
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
        return readCost(sa, kind, false);
    }

    /**
     * As above. sdcc-tutorcast-1110, {@code sacOther} (false = exactly the above): a cost part "sacrifice one other
     * permanent" is in the model ({@link Abil#sacN}; the caller fills {@link Abil#sacNames}).
     */
    static Abil readCost(SpellAbility sa, AbilKind kind, boolean sacOther) {
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
            } else if (sacOther && p instanceof CostSacrifice s && ab.sacN == 0 && s.getType() != null
                    && Integer.valueOf(1).equals(s.convertAmount())) {
                ab.sacN = 1;
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

    /**
     * sdcc-etb-1110 (solverCfg landEntersProbe; no reader of a position is given one unless the key is on): how a land
     * of ours enters when a plan from the position plays it as the land drop. A card's template is learned once per name
     * per game, at its first sight, and {@link ACard#entersTapped} / {@link ACard#landLife} with it. A land that enters
     * tapped under a condition (unless we control two or fewer other lands, unless we control a land of some type,
     * unless we pay life or reveal a card) keeps that moment's answer for the rest of the game: later the planner tapped
     * it for mana on the turn it entered tapped, and its line cap filled with variants of that illegal prefix. Asked
     * here, the answer is the engine's: the land is played in a sandbox copy of the position.
     */
    interface LandEntry {
        /**
         * {1 = it is tapped once it has entered, our life change (0 or less), 1 = the same on every battlefield a plan
         * builds}; null = not known for this card on any battlefield (a card that is not in the game, a sandbox that could
         * not play it): its template's answer stands. {@code battlefield}: the card ids of our permanents as the plan has
         * them when the land is played, and {@code hand} those of our hand then (a permanent that left our battlefield
         * went there, or is gone); null = as the position has them. The sandbox's battlefield is made the plan's before
         * the land is played, so a condition on the board is the engine's on the board the line has built (a land played
         * after another land drop and the pass; after a cost returned lands to our hand).
         */
        int[] enters(ACard land, int[] battlefield, int[] hand);
    }

    /** The starting position of a search. */
    static final class Position {
        /** blockers-1109: cannot block at the opponent's next declare-blockers step (not a creature, or prevented). */
        static final byte BLOCK_NO = 0;
        /** blockers-1109: can block there if it is untapped when our turn ends (the default for every creature). */
        static final byte BLOCK_UNTAPPED = 1;
        /** blockers-1109: can block there even if tapped when our turn ends (it untaps in their untap step, or blocks tapped). */
        static final byte BLOCK_TAPPED = 2;

        int life, oppLife, turn;
        int oppLibrary;
        boolean landDrop;
        boolean sorcerySpeed;
        /**
         * line-adoption-1107 (solverCfg anyWindow): the position is our turn before main phase 1 (upkeep or draw step); a
         * MAIN step moves the plan to main phase 1 of this turn, drawing the draw step's card when {@link #preMainDraw}.
         */
        boolean preMain, preMainDraw;
        /** line-adoption-1107 (anyWindow): the position is not in our main phase (lines planned here are Line.offMain). */
        boolean offMain;
        int storm;
        final int[] pool = new int[6];
        final List<ACard> hand = new ArrayList<>();
        final List<ACard> battlefield = new ArrayList<>();
        final List<Boolean> tapped = new ArrayList<>();
        /** solver-gaps-1015: a planeswalker that has already used its loyalty ability this turn (parallel to battlefield). */
        final List<Boolean> used = new ArrayList<>();
        /**
         * blockers-1109 (ledger row 81): whether each permanent can block at the opponent's next declare-blockers step
         * (parallel to battlefield; one of {@link #BLOCK_NO}, {@link #BLOCK_UNTAPPED}, {@link #BLOCK_TAPPED}). The opponent's
         * untap step does not untap our permanents, so a creature tapped when our turn ends (it attacked, or paid a cost)
         * blocks only when it untaps then anyway, or can block while tapped.
         */
        final List<Byte> block = new ArrayList<>();
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
        /** sdcc-etb-1110 (landEntersProbe; null = off): this position's land entries, asked when a plan plays a land. */
        LandEntry landEntry;

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
        /** Learned templates by key; every read and write holds this map's own monitor (state-machines-1113). */
        final Map<String, ACard> templates = new HashMap<>();
        /** Report only; probes count into a per-call Stats that is added here under this object's monitor ({@link #addStats}). */
        final ForcedWinProbe.Stats probeStats = new ForcedWinProbe.Stats();
        /**
         * state-machines-1113: one monitor per template key. {@link #card} learns, extends and loop-probes a key's template
         * under that key's monitor only, so two threads learning different cards no longer wait for each other (the
         * reader shared by the play-outs' leaf reach held one monitor for every card: 9.7 % of play-out capacity, up to a
         * third in short combo games, profile 2026-11-13). One thread does exactly what it did under the reader's monitor.
         * Lock order: key, then {@link #templates} / {@link #probeStats} / the loop cache; nothing under a key monitor
         * asks this reader for another card.
         */
        private final java.util.concurrent.ConcurrentHashMap<String, Object> keyLocks = new java.util.concurrent.ConcurrentHashMap<>();
        long seed = 20261012L;

        /**
         * blockers-1109 (ledger row 81): can {@code c} (ours, on the battlefield) block at the opponent's next
         * declare-blockers step? Engine rules only: a creature the engine lets block (keywords and static abilities, read
         * as for next turn: CombatUtil.canBlock(c, true)); a tapped one only if it untaps in the opponent's untap step
         * (StaticAbilityUntapOtherPlayer, and nothing stops it untapping) or can block while tapped. Read off our own turn
         * (the line's first PASS is then not followed by the opponent's turn first), a tapped creature counts if it
         * untaps in our untap step, as before this lane.
         */
        static byte blockCode(Game live, Player me, Card c) {
            if (!c.isCreature() || !forge.game.combat.CombatUtil.canBlock(c, true)) {
                return Position.BLOCK_NO;
            }
            if (forge.game.staticability.StaticAbilityCantAttackBlock.canBlockTapped(c)) {
                return Position.BLOCK_TAPPED;
            }
            if (!live.getPhaseHandler().isPlayerTurn(me)) {
                return Position.BLOCK_TAPPED;
            }
            for (Player o : me.getOpponents()) {
                if (forge.game.staticability.StaticAbilityUntapOtherPlayer.untap(c, o) && c.canUntap(o, true)) {
                    return Position.BLOCK_TAPPED;
                }
            }
            return Position.BLOCK_UNTAPPED;
        }

        /**
         * kill-line-1106 (sourceOutput): each mana source's output read from the position, over the template learned at
         * the first position that saw the card's name. A source that makes nothing now is no source. Same order as read().
         */
        static void readSourceOutputs(Position p, Player me) {
            int i = 0;
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (c.isFaceDown()) {
                    continue;
                }
                if (i >= p.battlefield.size()) {
                    return;
                }
                final ACard a = p.battlefield.get(i++);
                if (a.sourceColors == 0) {
                    continue;
                }
                int amount = 0;
                for (SpellAbility original : c.getManaAbilities()) {
                    final SpellAbility m = original.copy(me);
                    if (m.getManaPart() == null) {
                        continue;
                    }
                    try {
                        amount = Math.max(amount, m.amountOfManaGenerated(true));
                    } catch (RuntimeException e) {
                        amount = Math.max(amount, 1);
                    }
                }
                a.sourceAmount = amount;
                if (amount == 0) {
                    a.sourceColors = 0;
                }
            }
        }

        /**
         * ledger-sweep2-1107 (solverCfg sourceColorsLive; off by default): each permanent's plain mana output (colors and
         * amount) is read from its mana abilities in the position, as the probe reads them, instead of from the template
         * learned at the card's first sight. A template is learned once per name per game, in that moment's context: a land
         * whose color comes from a static ability (Urborg making itself and every land a Swamp) learned from the hand has no
         * output, and an Island learned before Urborg arrived makes only U (ledger row 15: dd6's T15 kill needs B from both).
         * Sacrifice sources and summoning-sick creatures keep what read() gave them.
         */
        static void readSourceColors(Position p, Player me) {
            int i = 0;
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (c.isFaceDown()) {
                    continue;
                }
                if (i >= p.battlefield.size()) {
                    return;
                }
                final ACard a = p.battlefield.get(i++);
                if (a.sourceSick || a.sourceSac) {
                    continue;
                }
                int colors = 0, amount = 0;
                for (SpellAbility original : c.getManaAbilities()) {
                    final SpellAbility m = original.copy(me);
                    if (m.getManaPart() == null) {
                        continue;
                    }
                    int col = 0;
                    for (int k = 0; k < 6; k++) {
                        if (m.canProduce(SHORT[k])) {
                            col |= 1 << k;
                        }
                    }
                    final Abil cost = col == 0 ? null : readCost(m, AbilKind.FLOAT);
                    if (cost == null || cost.sacSelf || cost.generic != 0 || java.util.Arrays.stream(cost.pips).sum() != 0
                            || cost.life != 0 || cost.discardHand || m.getSubAbility() != null) {
                        continue;
                    }
                    colors |= col;
                    int n;
                    try {
                        n = Math.max(1, m.amountOfManaGenerated(true));
                    } catch (RuntimeException e) {
                        n = 1;
                    }
                    amount = Math.max(amount, n);
                }
                a.sourceColors = colors;
                a.sourceAmount = amount;
            }
        }

        /** strong-opp-1020: the learned templates as JSON (card name -> template), for a precomputed cache. */
        String exportJson() {
            synchronized (templates) {
                return new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(templates);
            }
        }

        /**
         * strong-opp-1020: preload templates learned offline (a name already learned here is kept). Affordances depend
         * only on the card (each is learned in a sandbox copy with rich floating mana), so a cache learned once serves
         * every game; it saves the defense's first-call probes (about 0.15 s per new card name).
         */
        int preload(String json) {
            final java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<Map<String, ACard>>() { }.getType();
            final Map<String, ACard> m = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create()
                    .fromJson(json, t);
            int n = 0;
            synchronized (templates) {
                for (Map.Entry<String, ACard> e : m.entrySet()) {
                    if (e.getValue() != null && templates.putIfAbsent(e.getKey(), e.getValue()) == null) {
                        n++;
                    }
                }
            }
            return n;
        }

        /** state-machines-1113: a call's probe counts into the reader's report. */
        void addStats(final ForcedWinProbe.Stats st) {
            synchronized (probeStats) {
                probeStats.probes += st.probes;
                probeStats.failures += st.failures;
                probeStats.nanos += st.nanos;
            }
        }

        /**
         * sdcc-leak-1111: this reader's sandboxes hide what the seat cannot know ({@link ForcedWinProbe#HIDE}); set by
         * a solver with {@code hideOwnLibrary} and without {@code hiddenOrderLegacy}. False: plain copies, as before.
         */
        volatile boolean hideLibrary;

        /**
         * search-bones-1015: a reader may be shared by the play-outs' threat checks, (d). state-machines-1113: callers of
         * one card wait for each other (its key's monitor); callers of different cards do not.
         */
        ACard card(Game live, Player me, Card c) {
            // labeller-v2 (c): a planScry reader learns scry / surveil as kinds, under its own keys (never a v1 template)
            // sdcc-draws-1110: so does a probeSettle reader (its templates hold what the settled sandbox moved)
            // sdcc-tutorcast-1110: so does a tutorCast reader ("tc|"), whose probes also observe search-and-cast effects
            // sdcc-tutorcast-1110: and a sourceLeaves reader ("sl|"), whose probes also note where an ability's source ends
            // sdcc-probeobj-1111: and a probeObjects reader ("po|"), whose probes give an ability the object it needs
            final String key = (probeObjects ? "po|" : "") + (sourceLeaves ? "sl|" : "") + (tutorCast ? "tc|" : "") + (planScry ? "scry|" : "")
                    + (probeSettle ? "settle|" : "") + c.getName();
            final boolean prevHide = ForcedWinProbe.HIDE.get();
            ForcedWinProbe.HIDE.set(hideLibrary); // sdcc-leak-1111 (the template's probes and its loop probe)
            final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
            try {
                synchronized (keyLocks.computeIfAbsent(key, k -> new Object())) {
                    return card0(live, me, c, key, st);
                }
            } finally {
                ForcedWinProbe.HIDE.set(prevHide);
                addStats(st);
            }
        }

        private ACard card0(Game live, Player me, Card c, String key, ForcedWinProbe.Stats st) {
            ACard t;
            synchronized (templates) {
                t = templates.get(key);
            }
            if (t == null) {
                final boolean prev = ForcedWinProbe.PLAN_SCRY.get(), prevSettle = ForcedWinProbe.PROBE_SETTLE.get();
                final boolean prevTc = ForcedWinProbe.TUTOR_CAST.get(), prevSl = ForcedWinProbe.SOURCE_LEAVES.get();
                final boolean prevPo = ForcedWinProbe.PROBE_OBJECTS.get();
                ForcedWinProbe.PROBE_OBJECTS.set(probeObjects);
                ForcedWinProbe.PLAN_SCRY.set(planScry);
                ForcedWinProbe.PROBE_SETTLE.set(probeSettle);
                ForcedWinProbe.TUTOR_CAST.set(tutorCast);
                ForcedWinProbe.SOURCE_LEAVES.set(sourceLeaves);
                try {
                    t = ForcedWinProbe.learn(live, me, c, seed, st);
                } finally {
                    ForcedWinProbe.PLAN_SCRY.set(prev);
                    ForcedWinProbe.PROBE_SETTLE.set(prevSettle);
                    ForcedWinProbe.TUTOR_CAST.set(prevTc);
                    ForcedWinProbe.SOURCE_LEAVES.set(prevSl);
                    ForcedWinProbe.PROBE_OBJECTS.set(prevPo);
                }
                if (!forge.ai.AiWork.spent()) {
                    synchronized (templates) {
                        templates.put(key, t); // rollout-budget-1101 (review #160 S3-4): a template learned past the cap is not kept
                    }
                }
            } else if (probeObjects && t.hasObj && !forge.ai.AiWork.spent()) {
                // sdcc-probeobj-1111: the template was learned at the card's first sight; objects of ours that are in play
                // or in our hand now and that no sandbox has tried the ability with yet are tried here, once per name
                final boolean prevSettle = ForcedWinProbe.PROBE_SETTLE.get(), prevPo = ForcedWinProbe.PROBE_OBJECTS.get();
                ForcedWinProbe.PROBE_SETTLE.set(probeSettle);
                ForcedWinProbe.PROBE_OBJECTS.set(true);
                try {
                    ForcedWinProbe.extendObjects(t, live, me, c, seed, st);
                } catch (RuntimeException | StackOverflowError e) {
                    t.note = (t.note.isEmpty() ? "" : t.note + "; ") + "object probe failed: " + e;
                } finally {
                    ForcedWinProbe.PROBE_SETTLE.set(prevSettle);
                    ForcedWinProbe.PROBE_OBJECTS.set(prevPo);
                }
            }
            if (loopProbe != null && !t.loopChecked) {
                t.loopChecked = true; // once per template, whatever it finds
                try {
                    loopProbe.fill(live, me, c, t);
                } catch (RuntimeException | StackOverflowError e) {
                    t.note = (t.note.isEmpty() ? "" : t.note + "; ") + "loop probe failed: " + e;
                }
            }
            return t.instance(c.getId());
        }

        /** labeller-v2 (c): observe scry / surveil as SCRY / SURVEIL effects (false = v1). */
        boolean planScry;

        /**
         * sdcc-draws-1110 (solverCfg probeSettle; false = off, the default, and then nothing changes): templates are learned
         * with {@link ForcedWinProbe#PROBE_SETTLE} on (a mana ability with a cost or a trigger is played in the sandbox
         * too, and a card that puts itself into our library is an effect), under their own keys.
         */
        boolean probeSettle;

        /**
         * sdcc-tutorcast-1110 (solver key tutorCast; false = off, and then nothing changes): the probes also observe a
         * spell that searches our library for a card and lets us cast it without paying its mana cost
         * ({@link EffKind#TUTOR_CAST}), with the optional additional costs that unlock it and what the engine accepts
         * as their payment, and a search that puts the card into our hand by way of exile (a TUTOR).
         */
        boolean tutorCast;

        /**
         * sdcc-tutorcast-1110 (solver key sourceLeaves; false = off, and then nothing changes): the probes also note
         * where an activated ability leaves its own source ({@link Abil#selfTo}): an ability that puts its source on top
         * of our library, or returns it to our hand, no longer leaves it on the battlefield in the plan.
         */
        boolean sourceLeaves;

        /**
         * sdcc-probeobj-1111 (solver key probeObjects; false = off, and then nothing changes): an ability that targets
         * one card, or sacrifices another permanent as a cost, is played in the sandbox with an object of ours (one run
         * per card name) and keeps what the engine accepted and did ({@link ObjNeed}), under its own template keys.
         */
        boolean probeObjects;

        /** solver-distill-cc-1109 (planLoops): fills a template's loop partners, once (null = off, the default). */
        interface LoopProbe {
            void fill(Game live, Player me, Card c, ACard template);
        }

        volatile LoopProbe loopProbe;

        /** sdcc-etb-1110 (landEntersProbe): the positions whose land entries are kept (the least recently asked goes first). */
        static final int LAND_POSITIONS = 32;
        /**
         * sdcc-etb-1110: the most sandboxes one position spends on battlefields its plans have changed (each land's entry
         * on the position's own battlefield is always probed). Past it a changed battlefield gets the position's answer.
         */
        static final int LAND_CONTEXTS = 12;
        /** sdcc-etb-1110: a sandbox that could not play the land (kept, so the same position does not probe it again). */
        private static final int[] LAND_UNKNOWN = new int[0];

        /** sdcc-etb-1110: one position's land entries: by land and battlefield change, and the changed battlefields probed. */
        private static final class LandEntries {
            final Map<String, int[]> byKey = new HashMap<>();
            int contexts;
        }

        /**
         * sdcc-etb-1110 (landEntersProbe; empty unless a solver with the key on asked): the land entries probed so far, by
         * position ({@link #landSignature}), then by land (its name; apart for one that is on our battlefield, which is
         * played again only after it has left it) and by what the plan changed on our battlefield before it. One decision
         * reads its position many times (the offense, each screen of a reach or a goldfish): they share the sandboxes.
         */
        private final java.util.LinkedHashMap<String, LandEntries> landEntries = new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, LandEntries> eldest) {
                return size() > LAND_POSITIONS;
            }
        };
        /**
         * sdcc-etb-1110 (report only): land-entry sandboxes on the position's own battlefield, and those whose answer was
         * not the template's; sandboxes on a battlefield the plan changed, and those whose answer was not the position's.
         */
        int landProbes, landDiffers, landContexts, landContextDiffers;
        /** sdcc-etb-1110 (report only): lands answered from their template with no sandbox (plain, and nothing in play watches entries). */
        int landPlain;

        /**
         * sdcc-etb-1110: what a land's entry may depend on, as far as a cache key can tell one position from the next: the
         * turn, whose it is and whether it is our second main phase (the sandbox's phase), the stack, our life, our hand
         * and battlefield (card by card, tapped or not), the sizes of our other zones, and each opponent's life and
         * battlefield. Two reads of one position give one key; a position that differs in anything named here is probed
         * again.
         */
        static String landSignature(final Game live, final Player me) {
            final StringBuilder sb = new StringBuilder(256);
            final forge.game.phase.PhaseHandler ph = live.getPhaseHandler();
            sb.append(ph.getTurn()).append(ph.isPlayerTurn(me) ? (ph.is(forge.game.phase.PhaseType.MAIN2) ? "m2" : "m") : "o")
                    .append(live.getStack().size()).append('|').append(me.getId()).append('|').append(me.getLife()).append('h');
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                sb.append(c.getId()).append(',');
            }
            sb.append('b');
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                sb.append(c.getId()).append(c.isTapped() ? 't' : 'u');
            }
            sb.append('g').append(me.getCardsIn(ZoneType.Graveyard).size()).append('l')
                    .append(me.getCardsIn(ZoneType.Library).size()).append('x').append(me.getCardsIn(ZoneType.Exile).size())
                    .append('c').append(me.getCardsIn(ZoneType.Command).size());
            for (Player o : me.getOpponents()) {
                sb.append('|').append(o.getLife()).append('b');
                for (Card c : o.getCardsIn(ZoneType.Battlefield)) {
                    sb.append(c.getId()).append(c.isTapped() ? 't' : 'u');
                }
                sb.append('c').append(o.getCardsIn(ZoneType.Command).size());
            }
            return sb.toString();
        }

        /**
         * sdcc-etb-1110 (landEntersProbe): the land entries of the position {@code live} stands at, for the plans made from
         * it ({@link Position#landEntry}). A land with no replacement effect of its own beyond the plainest kind, in a game
         * where no card in play replaces another card's entering ({@link ForcedWinProbe#entersUnwatched}), keeps its
         * template's answer and costs no sandbox. Else nothing is probed until a plan plays the land: then it is played as our
         * land drop in a sandbox copy of {@code live} ({@link ForcedWinProbe#landEntry}), once per land name and position;
         * and once more per battlefield the plan has changed by then, for a land with a replacement effect of its own
         * that is not of the plainest kind (engine metadata, {@link ForcedWinProbe#entersPlainly}: what could make its
         * entering depend on the board), at most {@link #LAND_CONTEXTS} times per position. The game must stay as it is
         * while the answers are asked (a solver plans on the game it read, and changes only copies of it).
         */
        LandEntry landEntry(final Game live, final Player me) {
            final String position = landSignature(live, me);
            final java.util.Set<Integer> root = new java.util.HashSet<>();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isFaceDown()) {
                    root.add(c.getId()); // our battlefield as a position reads it
                }
            }
            final Map<Integer, Card> byId = new HashMap<>(); // the game's cards asked about so far (null = not in the game)
            return (land, battlefield, hand) -> {
                if (land == null || !land.land) {
                    return null;
                }
                synchronized (Reader.this) {
                    final Card c = card(live, byId, land.id);
                    if (c == null || c.getOwner() != me) {
                        return null; // not a card of this game (a screen's granted copy): its own values stand
                    }
                    final boolean prevHide = ForcedWinProbe.HIDE.get();
                    ForcedWinProbe.HIDE.set(hideLibrary); // sdcc-leak-1111
                    try {
                        return landEntry(live, me, position, root, byId, land, c, battlefield, hand);
                    } finally {
                        ForcedWinProbe.HIDE.set(prevHide);
                    }
                }
            };
        }

        /** state-machines-1113: one land-entry sandbox, its probe counted into the report ({@link #addStats}). */
        private int[] landProbe(final Game live, final Player me, final Card c, final List<Card> added,
                final List<Card> toHand, final List<Card> gone, final long seed) {
            final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
            try {
                return ForcedWinProbe.landEntry(live, me, c, added, toHand, gone, seed, st);
            } finally {
                addStats(st);
            }
        }

        private static Card card(final Game live, final Map<Integer, Card> byId, final int id) {
            if (!byId.containsKey(id)) {
                byId.put(id, live.findById(id));
            }
            return byId.get(id);
        }

        /** sdcc-etb-1110: {@link LandEntry#enters} for the card {@code c} of {@code live} (the caller holds this reader). */
        private int[] landEntry(final Game live, final Player me, final String position, final java.util.Set<Integer> root,
                final Map<Integer, Card> byId, final ACard land, final Card c, final int[] battlefield, final int[] hand) {
            LandEntries known = landEntries.get(position);
            if (known == null) {
                known = new LandEntries();
                landEntries.put(position, known);
            }
            final String key = c.isInPlay() ? "battlefield|" + c.getName() : c.getName();
            final long seed0 = ForcedWinSolver.mix(seed, 0x4C414E44L * 31 + (position + key).hashCode());
            int[] here = known.byKey.get(key);
            if (here == null && ForcedWinProbe.entersPlainly(c) && ForcedWinProbe.entersUnwatched(live, c)) {
                // nothing of its own and nothing in play can change how it enters: the template's sandbox saw the same
                // (no sandbox here: a position's probes are paid from the decision's own clock)
                here = new int[] {land.entersTapped ? 1 : 0, land.landLife, 1};
                landPlain++;
                known.byKey.put(key, here);
            }
            if (here == null) {
                here = landProbe(live, me, c, null, null, null, seed0);
                landProbes++;
                if (here != null && (here[0] != 0) != land.entersTapped) {
                    landDiffers++;
                }
                if (ForcedWinSolver.RECEIPTS) {
                    ForcedWinSolver.receipt("FORCED_WIN land-entry " + key + " turn=" + live.getPhaseHandler().getTurn()
                            + (here == null ? " unknown" : " tapped=" + (here[0] != 0) + " life=" + here[1]
                                    + (here[2] != 0 ? "" : " board-dependent")) + " template=" + land.entersTapped + "/"
                            + land.landLife);
                }
                if (here == null) {
                    here = LAND_UNKNOWN;
                }
                if (!forge.ai.AiWork.spent()) {
                    known.byKey.put(key, here); // as a template: an answer found past the decision's Forge AI work cap is not kept
                }
            }
            if (here == LAND_UNKNOWN) {
                return null;
            }
            if (here[2] != 0) {
                // nothing of its own makes its entering depend on the board (ForcedWinProbe.entersPlainly): one answer
                return new int[] {here[0], here[1], 1};
            }
            final int[] atPosition = {here[0], here[1], 0};
            if (battlefield == null) {
                return atPosition;
            }
            // what the plan has changed on our battlefield by now: the cards that entered it, went back to our hand, or are gone
            final List<Card> added = new ArrayList<>(), toHand = new ArrayList<>(), gone = new ArrayList<>();
            final java.util.Set<Integer> now = new java.util.HashSet<>();
            for (int id : battlefield) {
                now.add(id);
                if (!root.contains(id) && id != land.id) {
                    final Card x = card(live, byId, id);
                    if (x != null) {
                        added.add(x); // (a screen's granted copy is not in the game: left out)
                    }
                }
            }
            for (int id : root) {
                if (!now.contains(id) && id != land.id) {
                    final Card x = card(live, byId, id);
                    if (x != null) {
                        boolean back = false;
                        for (int h : hand == null ? new int[0] : hand) {
                            back |= h == id;
                        }
                        (back ? toHand : gone).add(x);
                    }
                }
            }
            if (added.isEmpty() && toHand.isEmpty() && gone.isEmpty()) {
                return atPosition;
            }
            final String ctx = key + "@+" + names(added) + "<" + names(toHand) + "-" + names(gone);
            int[] there = known.byKey.get(ctx);
            if (there == null) {
                if (known.contexts >= LAND_CONTEXTS) {
                    return atPosition;
                }
                known.contexts++;
                there = landProbe(live, me, c, added, toHand, gone, ForcedWinSolver.mix(seed0, ctx.hashCode()));
                landContexts++;
                if (there != null && there[0] != here[0]) {
                    landContextDiffers++;
                }
                if (ForcedWinSolver.RECEIPTS) {
                    ForcedWinSolver.receipt("FORCED_WIN land-entry " + ctx + " turn=" + live.getPhaseHandler().getTurn()
                            + (there == null ? " unknown" : " tapped=" + (there[0] != 0) + " life=" + there[1]) + " position="
                            + (here[0] != 0) + "/" + here[1]);
                }
                if (there == null) {
                    there = LAND_UNKNOWN;
                }
                if (!forge.ai.AiWork.spent()) {
                    known.byKey.put(ctx, there);
                }
            }
            return there == LAND_UNKNOWN ? atPosition : new int[] {there[0], there[1], 0};
        }

        private static String names(final List<Card> cards) {
            final List<String> out = new ArrayList<>();
            for (Card c : cards) {
                out.add(c.getName());
            }
            java.util.Collections.sort(out);
            return out.toString();
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
                p.block.add(blockCode(live, me, c));
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
                    chooses |= !ab.unplayable && (ab.has(EffKind.PILE) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP)
                            || ab.has(EffKind.TUTOR_CAST));
                }
            }
            for (ACard a : p.battlefield) {
                for (Abil ab : a.onBattlefield) {
                    chooses |= !ab.unplayable && (ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP));
                }
            }
            // sdcc-probeobj-1111 (a probeObjects reader only): an ability whose object is a card in our graveyard needs the
            // graveyard's cards (not the library's)
            boolean gyObjects = false;
            if (probeObjects && !chooses) {
                for (int side = 0; side < 2; side++) {
                    for (ACard a : side == 0 ? p.hand : p.battlefield) {
                        for (Abil ab : side == 0 ? a.fromHand : a.onBattlefield) {
                            gyObjects |= ab.obj != null && "Graveyard".equals(ab.obj.zone);
                        }
                    }
                }
            }
            if (libraryIfUseful && gyObjects) {
                final List<Card> gy = new ArrayList<>(me.getCardsIn(ZoneType.Graveyard));
                gy.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
                for (Card c : gy) {
                    p.graveyard.add(card(live, me, c));
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
