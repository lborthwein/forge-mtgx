package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * deck-belief-1105 (N11 v1, solver key {@code deckBelief}; off by default): the opponent's hidden hand and library dealt
 * from a posterior over the decks it plausibly plays, instead of uniformly from the pool.
 * <ol>
 * <li><b>Public evidence</b> ({@link Evidence#read}): the colours it has shown (colour identity of its lands, the
 * colours of its spells, everywhere the seat can see them), its untapped mana sources and their colours, its seen card
 * count and seen land count, and whether it made a land drop on its last turn.</li>
 * <li><b>The deck's colours</b> ({@link #sampleColors}): a colour set drawn per world from every 1-, 2- and 3-colour set
 * that contains the shown colours (the shown set itself past three), weighted by a size prior.</li>
 * <li><b>The deck</b> ({@link #deal}): the hidden cards are dealt from the pool cards whose colour identity fits that
 * set (colourless always fits), with lands at the deck's land share minus the lands it has shown.</li>
 * <li><b>The hand</b> ({@link #handWeight}): which dealt cards are in the hand follows a likelihood of what it declined
 * to do. Off its own turn, a sorcery-speed spell it could have cast with the mana it left open, or a land when it made no
 * land drop last turn, is less likely to be in its hand.</li>
 * </ol>
 * Everything reads public state and card rules (types, costs, colours), never a card name, never the real hidden
 * cards; a pure function of (evidence, pool, the world's Random).
 */
public final class DeckBelief {
    private DeckBelief() {
    }

    /** The model's parameters (solver keys {@code deckDecline}, {@code deckLandShare}; the size prior is fixed in v1). */
    public static final class Params {
        /** The likelihood factor of a hand card the opponent declined to play (see {@link #handWeight}). */
        public double decline = 0.35;
        /** The deck's land share (17 of 40). */
        public double landShare = 0.425;
        /** Prior weight of a deck colour set by its size (index 1..3), before the shown-colour constraint. */
        public double[] sizePrior = {0, 0.15, 0.6, 0.25};
        /**
         * untested-keys-1113 (null = off, the default, and then nothing changes): the held-mana term, the likelihood
         * factor for h = 1, 2, 3+ of an instant-speed nonland card it could cast with the mana it holds off its turn (see
         * {@link #handWeight}). Spec key {@code beliefDeckHeld}.
         */
        public double[] held = null;
        /**
         * untested-keys-1113 (null = off, the default, and then nothing changes): the deck-inclusion prior; the hidden
         * spells and nonbasic lands are dealt by weighted draw by how often each card makes a main deck of the world's
         * colours (see {@link #deal}). Spec key {@code beliefDeckInclusion}.
         */
        public Inclusion inclusion = null;
    }

    /**
     * untested-keys-1113: the deck-inclusion table ({@code deck-inclusion-mush-1113.json}: built 2026-11-13 from the
     * walk-mix-1111-ho4 deck bank and the train half of the owner-review recordings' decklists; source, build date and the
     * offline test are in the file). A card's rate in a deck of colours C is
     * {@code max(floor, (counts[C][card] + shrink * global[card]) / (decks[C] + shrink))}. Data only: no roles, no classes.
     */
    public static final class Inclusion {
        public static final String LIVE = "classpath:/forge/ai/simulation/deck-inclusion-mush-1113.json";
        public static final String LIVE_SHA256 = "12d9b429753f";
        private static volatile Inclusion live;
        public final String sha256;
        final double shrink, floor;
        final java.util.Map<Integer, Integer> decks = new java.util.HashMap<>();
        final java.util.Map<String, Double> global = new java.util.HashMap<>();
        final java.util.Map<Integer, java.util.Map<String, Integer>> counts = new java.util.HashMap<>();

        Inclusion(final byte[] bytes) {
            this.sha256 = sha256(bytes);
            final com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"deck-inclusion/1".equals(o.get("schema").getAsString())) {
                throw new IllegalArgumentException("deck inclusion: schema " + o.get("schema"));
            }
            shrink = o.get("shrink").getAsDouble();
            floor = o.get("floor").getAsDouble();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("colorSets").entrySet()) {
                decks.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt());
            }
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("global").entrySet()) {
                global.put(e.getKey(), e.getValue().getAsDouble());
            }
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("counts").entrySet()) {
                final java.util.Map<String, Integer> m = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> x : e.getValue().getAsJsonObject().entrySet()) {
                    m.put(x.getKey(), x.getValue().getAsInt());
                }
                counts.put(Integer.parseInt(e.getKey()), m);
            }
        }

        static String sha256(final byte[] b) {
            try {
                final StringBuilder sb = new StringBuilder();
                for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) {
                    sb.append(String.format("%02x", x));
                }
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The table shipped in the jar, checked against its pinned sha256 prefix. */
        public static Inclusion live() {
            Inclusion t = live;
            if (t == null) {
                synchronized (Inclusion.class) {
                    t = live;
                    if (t == null) {
                        try {
                            t = new Inclusion(BeliefSampler.readCube(LIVE));
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("deck inclusion: " + LIVE, e);
                        }
                        if (!t.sha256.startsWith(LIVE_SHA256)) {
                            throw new IllegalStateException("deck inclusion: " + LIVE + " sha256 " + t.sha256 + ", pinned " + LIVE_SHA256);
                        }
                        live = t;
                    }
                }
            }
            return t;
        }

        /** How often {@code name} makes a main deck of colours {@code colors}, shrunk to its rate over every deck it fits. */
        public double rate(final String name, final byte colors) {
            final int c = colors & 0x1f;
            final double g = global.getOrDefault(name, 0.01);
            final java.util.Map<String, Integer> m = counts.get(c);
            final int k = m == null ? 0 : m.getOrDefault(name, 0);
            return Math.max(floor, (k + shrink * g) / (decks.getOrDefault(c, 0) + shrink));
        }
    }

    /** What the seat has seen of one opponent. */
    public static final class Evidence {
        /** Colours shown (MagicColor mask). */
        public byte shown;
        /** Untapped mana sources the opponent controls, and the colours they produce (identity mask). */
        public int openMana;
        public byte openColors;
        /** Seen non-token cards the opponent owns (outside its library), and how many of them are lands. */
        public int seen, seenLands;
        /** True when it is not the opponent's turn: what it left undone on its own turn is evidence. */
        public boolean offTurn;
        /** The opponent made no land drop on its last turn. */
        public boolean noLandLastTurn;

        public static Evidence read(final Game live, final Player liveOpp, final PlayerView myView) {
            final Evidence e = new Evidence();
            for (Card c : live.getCardsInGame()) {
                if (c.getOwner() != liveOpp || c.isToken() || c.isFaceDown() || c.getPaperCard() == null
                        || c.isInZone(ZoneType.Library) || !c.getView().canBeShownTo(myView)) {
                    continue;
                }
                final CardRules r = c.getPaperCard().getRules();
                e.seen++;
                if (r.getType().isLand()) {
                    e.seenLands++;
                    e.shown |= r.getColorIdentity().getColor();
                } else {
                    e.shown |= r.getColor().getColor();
                }
                if (c.isInZone(ZoneType.Battlefield) && c.getController() == liveOpp && !c.isTapped()
                        && !c.getManaAbilities().isEmpty()) {
                    e.openMana++;
                    e.openColors |= r.getColorIdentity().getColor();
                }
            }
            for (forge.game.spellability.SpellAbilityStackInstance si : live.getStack()) {
                final Card c = si.getSourceCard();
                if (si.getActivatingPlayer() == liveOpp && c != null && c.getPaperCard() != null) {
                    e.shown |= c.getPaperCard().getRules().getColor().getColor();
                }
            }
            e.offTurn = live.getPhaseHandler().getPlayerTurn() != liveOpp;
            e.noLandLastTurn = liveOpp.getLandsPlayedLastTurn() == 0;
            return e;
        }
    }

    /** Every colour set of size 1..3 that contains {@code shown} (or {@code shown} itself past three). */
    static List<Byte> candidateSets(final byte shown) {
        final List<Byte> out = new ArrayList<>();
        final int k = Integer.bitCount(shown & 0x1f);
        if (k > 3) {
            out.add(shown);
            return out;
        }
        for (int m = 1; m < 32; m++) {
            if ((m & shown) == shown && Integer.bitCount(m) <= 3) {
                out.add((byte) m);
            }
        }
        return out;
    }

    /** One world's deck colours from the posterior over {@link #candidateSets} (the size prior, renormalized). */
    public static byte sampleColors(final byte shown, final Params p, final Random rng) {
        final List<Byte> c = candidateSets(shown);
        final double[] w = new double[c.size()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            final int k = Integer.bitCount(c.get(i) & 0x1f);
            w[i] = k < p.sizePrior.length ? p.sizePrior[k] : p.sizePrior[p.sizePrior.length - 1];
            sum += w[i];
        }
        double u = rng.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            u -= w[i];
            if (u < 0) {
                return c.get(i);
            }
        }
        return c.get(c.size() - 1);
    }

    /** The card fits a deck of {@code colors}: its colour identity is inside them (colourless always fits). */
    public static boolean fits(final PaperCard pc, final byte colors) {
        return pc != null && pc.getRules().getColorIdentity().hasNoColorsExcept(colors);
    }

    /**
     * The likelihood factor of the opponent holding {@code pc} given what it left undone (off its own turn only):
     * {@code decline} for a sorcery-speed nonland spell it could have paid with the mana it left open (mana value at
     * most its open sources, every coloured pip among their colours), and for a land when it made no land drop last
     * turn; 1 otherwise (instants, flash, what it could not afford). With {@link Params#held} (untested-keys-1113): an
     * instant-speed nonland card it could cast with the mana it holds (h = its untapped sources, mana value at most h, every
     * coloured pip among their colours) gets {@code held[min(h, 3) - 1]}.
     */
    public static double handWeight(final PaperCard pc, final Evidence e, final Params p) {
        if (pc == null || !e.offTurn) {
            return 1.0;
        }
        final CardRules r = pc.getRules();
        if (r.getType().isLand()) {
            return e.noLandLastTurn ? p.decline : 1.0;
        }
        if (r.getType().isInstant() || r.hasKeyword(forge.game.keyword.Keyword.FLASH.toString())) {
            if (p.held != null && e.openMana > 0 && r.getManaCost().getCMC() <= e.openMana
                    && ColorSet.fromMask(r.getManaCost().getColorProfile()).hasNoColorsExcept(e.openColors)) {
                return p.held[Math.min(e.openMana, 3) - 1];
            }
            return 1.0;
        }
        final int mv = r.getManaCost().getCMC();
        if (mv > e.openMana) {
            return 1.0;
        }
        final byte pips = r.getManaCost().getColorProfile();
        return ColorSet.fromMask(pips).hasNoColorsExcept(e.openColors) ? p.decline : 1.0;
    }

    /** One world's hidden cards: {@code hand} names for the hand, {@code library} for the hidden library (top first). */
    public static final class Deal {
        public final List<String> hand = new ArrayList<>();
        public final List<String> library = new ArrayList<>();
        public byte colors;
        public int lands;
    }

    /**
     * Deal one world: {@code need} hidden hand cards and {@code libNeed} hidden library cards from {@code pool} (unique
     * names; basics may repeat), for a deck of the sampled colours.
     */
    public static Deal deal(final List<String> pool, final Function<String, PaperCard> paper, final Evidence e,
            final int need, final int libNeed, final Params p, final Random rng) {
        final Deal d = new Deal();
        d.colors = sampleColors(e.shown, p, rng);
        final int total = need + libNeed;
        final List<String> spells = new ArrayList<>(), lands = new ArrayList<>(), offSpells = new ArrayList<>(),
                offLands = new ArrayList<>();
        final List<String> basics = new ArrayList<>();
        for (String nm : pool) {
            if (BeliefSampler.isBasic(nm)) {
                final PaperCard pc = paper.apply(nm);
                if (fits(pc, d.colors)) {
                    basics.add(nm);
                }
                continue;
            }
            final PaperCard pc = paper.apply(nm);
            if (pc == null) {
                continue;
            }
            final boolean land = pc.getRules().getType().isLand();
            if (fits(pc, d.colors)) {
                (land ? lands : spells).add(nm);
            } else {
                (land ? offLands : offSpells).add(nm);
            }
        }
        // the hidden lands: the deck's land share of everything it owns, minus the lands it has shown
        int hl = (int) Math.round(p.landShare * (e.seen + total)) - e.seenLands;
        hl = Math.max(0, Math.min(total, hl));
        final List<String> dealt = new ArrayList<>();
        if (p.inclusion == null) {
            Collections.shuffle(spells, rng);
            Collections.shuffle(offSpells, rng);
            Collections.shuffle(lands, rng);
            Collections.shuffle(offLands, rng);
        } else {
            // untested-keys-1113: weighted order without replacement by the inclusion rate in a deck of these colours
            weightedOrder(spells, p.inclusion, d.colors, rng);
            weightedOrder(offSpells, p.inclusion, d.colors, rng);
            weightedOrder(lands, p.inclusion, d.colors, rng);
            weightedOrder(offLands, p.inclusion, d.colors, rng);
        }
        // lands: the fitting nonbasics and the basics of the deck's colours, about half each
        int li = 0;
        for (int i = 0; i < hl; i++) {
            final boolean basic = !basics.isEmpty() && (li >= lands.size() || rng.nextBoolean());
            if (basic) {
                dealt.add(basics.get(rng.nextInt(basics.size())));
            } else if (li < lands.size()) {
                dealt.add(lands.get(li++));
            } else {
                dealt.add(BeliefSampler.basics()[rng.nextInt(BeliefSampler.basics().length)]);
            }
        }
        d.lands = hl;
        // spells: the fitting ones, without replacement; past them the rest of the pool, then basics (never short)
        int si = 0, oi = 0, ol = 0;
        while (dealt.size() < total) {
            if (si < spells.size()) {
                dealt.add(spells.get(si++));
            } else if (oi < offSpells.size()) {
                dealt.add(offSpells.get(oi++));
            } else if (li < lands.size()) {
                dealt.add(lands.get(li++));
            } else if (ol < offLands.size()) {
                dealt.add(offLands.get(ol++));
            } else {
                dealt.add(BeliefSampler.basics()[rng.nextInt(BeliefSampler.basics().length)]);
            }
        }
        Collections.shuffle(dealt, rng);
        // the hand: a weighted draw without replacement by the declined-action likelihood
        final List<String> rest = new ArrayList<>(dealt);
        final double[] w = new double[rest.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = handWeight(paper.apply(rest.get(i)), e, p);
        }
        final boolean[] used = new boolean[rest.size()];
        for (int k = 0; k < need; k++) {
            double sum = 0;
            for (int i = 0; i < w.length; i++) {
                sum += used[i] ? 0 : w[i];
            }
            double u = rng.nextDouble() * sum;
            int pick = -1;
            for (int i = 0; i < w.length; i++) {
                if (used[i]) {
                    continue;
                }
                pick = i;
                u -= w[i];
                if (u < 0) {
                    break;
                }
            }
            used[pick] = true;
            d.hand.add(rest.get(pick));
        }
        for (int i = 0; i < rest.size(); i++) {
            if (!used[i]) {
                d.library.add(rest.get(i));
            }
        }
        return d;
    }

    /**
     * untested-keys-1113: the held-mana factors from a spec value "a/b/c" (h = 1, 2, 3+; '/' because the live string is
     * comma-separated): three finite numbers, each > 0 and non-decreasing (the fitted table is monotone).
     */
    public static double[] parseHeld(final String v) {
        final String[] t = v.split("/", -1);
        if (t.length != 3) {
            throw new IllegalArgumentException("beliefDeckHeld=" + v + " (want three factors a/b/c for h = 1, 2, 3+)");
        }
        final double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Double.parseDouble(t[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("beliefDeckHeld=" + v + " (want numbers)");
            }
            if (!(out[i] > 0) || Double.isInfinite(out[i]) || (i > 0 && out[i] < out[i - 1])) {
                throw new IllegalArgumentException("beliefDeckHeld=" + v + " (want positive, non-decreasing factors)");
            }
        }
        return out;
    }

    /** The spec value of {@link #parseHeld}'s result. */
    public static String heldString(final double[] h) {
        final StringBuilder sb = new StringBuilder();
        for (double x : h) {
            sb.append(sb.length() == 0 ? "" : "/").append(x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x));
        }
        return sb.toString();
    }

    /** Weighted order without replacement (Efraimidis-Spirakis: sort by u^(1/w), largest first). */
    static void weightedOrder(final List<String> names, final Inclusion inc, final byte colors, final Random rng) {
        final double[] key = new double[names.size()];
        final Integer[] idx = new Integer[names.size()];
        for (int i = 0; i < key.length; i++) {
            key[i] = Math.pow(rng.nextDouble(), 1.0 / inc.rate(names.get(i), colors));
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(key[b], key[a]));
        final List<String> out = new ArrayList<>(names.size());
        for (Integer i : idx) {
            out.add(names.get(i));
        }
        names.clear();
        names.addAll(out);
    }

    /** For tests and receipts: the colour mask as letters. */
    public static String colors(final byte m) {
        final StringBuilder sb = new StringBuilder();
        for (byte c : MagicColor.WUBRG) {
            if ((m & c) != 0) {
                sb.append(MagicColor.toShortString(c));
            }
        }
        return sb.length() == 0 ? "C" : sb.toString();
    }
}
