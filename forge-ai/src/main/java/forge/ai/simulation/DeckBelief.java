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
         * deck-belief-1105 AMEND-4 (null = off: the fitting cards are drawn uniformly): archetype weights from a card
         * co-occurrence table (log-lift of b given a, learned from a generated-deck corpus with the faced decks' lineage
         * held out). A fitting card b is drawn with weight exp(clamp(coocGain * mean over the shown cards a of L[a][b],
         * -2, 2)).
         */
        public java.util.Map<String, java.util.Map<String, Double>> cooc;
        public double coocGain = 1.0;
        /**
         * deck-belief-1105 AMEND-6 (null = off): the open-mana behaviour likelihood. Off its own turn, an opponent that
         * left mana of a colour open is more likely to hold instant-speed interaction it can cast with it. Learned from
         * generated games ({@link OpenLik}); a dealt hand is accepted with probability proportional to
         * P(evidence | hand holds a castable instant or not), the mirror of {@link #decline}.
         */
        public OpenLik openLik;
    }

    /**
     * deck-belief-1105 AMEND-6: P(the opponent's hidden hand holds an instant-speed spell castable with its open mana |
     * open sources bucket, open colours, turn bucket), and its turn-only marginal, from a {@code mtgx-open-mana-lik/1}
     * table. No card names: features are counts and colour masks.
     */
    public static final class OpenLik {
        final java.util.Map<String, Double> q = new java.util.HashMap<>();
        final java.util.Map<String, Double> marg = new java.util.HashMap<>();

        static int openBucket(final int open) {
            return Math.min(open, 3);
        }

        static int turnBucket(final int turn) {
            return turn <= 4 ? 0 : turn <= 8 ? 1 : 2;
        }

        static String key(final int open, final byte colors, final int turn) {
            return openBucket(open) + ":" + (colors & 0x1f) + ":" + turnBucket(turn);
        }

        /** The likelihood ratio for a hand that does ({@code has}) or does not hold a castable instant. */
        double ratio(final Evidence e, final boolean has) {
            final Double qq = q.get(key(e.openMana, e.openColors, e.turn));
            final Double m = marg.get(String.valueOf(turnBucket(e.turn)));
            if (qq == null || m == null || m <= 0 || m >= 1) {
                return 1.0;
            }
            return has ? qq / m : (1 - qq) / (1 - m);
        }
    }

    private static final java.util.Map<String, OpenLik> OPEN_LIK = new java.util.HashMap<>();

    /** Load (once per JVM and path) an open-mana likelihood table, checking its sha256 pin when one is given. */
    public static synchronized OpenLik loadOpenLik(final String path, final String sha256) {
        if (path == null || path.isBlank()) {
            return null;
        }
        final OpenLik have = OPEN_LIK.get(path + "|" + sha256);
        if (have != null) {
            return have;
        }
        try {
            final byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            if (sha256 != null && !sha256.isBlank()) {
                final StringBuilder hex = new StringBuilder();
                for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
                    hex.append(String.format("%02x", b));
                }
                if (!hex.toString().equals(sha256)) {
                    throw new IllegalStateException("open-mana lik " + path + " has sha256 " + hex + ", pinned " + sha256);
                }
            }
            final com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"mtgx-open-mana-lik/1".equals(o.get("schema").getAsString())) {
                throw new IllegalStateException("open-mana lik " + path + ": schema " + o.get("schema"));
            }
            final OpenLik t = new OpenLik();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("q").entrySet()) {
                t.q.put(e.getKey(), e.getValue().getAsDouble());
            }
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("marg").entrySet()) {
                t.marg.put(e.getKey(), e.getValue().getAsDouble());
            }
            OPEN_LIK.put(path + "|" + sha256, t);
            return t;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("open-mana lik " + path + ": " + e, e);
        }
    }

    /** Whether {@code pc} is an instant-speed spell the opponent can cast with its open mana (count and colours). */
    public static boolean castableInstant(final PaperCard pc, final Evidence e) {
        if (pc == null) {
            return false;
        }
        final CardRules r = pc.getRules();
        if (r.getType().isLand() || !(r.getType().isInstant() || r.hasKeyword(forge.game.keyword.Keyword.FLASH.toString()))) {
            return false;
        }
        return r.getManaCost().getCMC() <= e.openMana
                && ColorSet.fromMask(r.getManaCost().getColorProfile()).hasNoColorsExcept(e.openColors);
    }

    private static final java.util.Map<String, java.util.Map<String, java.util.Map<String, Double>>> COOC = new java.util.HashMap<>();

    /**
     * Load (once per JVM and path) a {@code mtgx-deck-cooc/1} table ({@code classpath:} = a jar resource), checking its
     * sha256 pin when one is given.
     */
    @SuppressWarnings("unchecked")
    public static synchronized java.util.Map<String, java.util.Map<String, Double>> loadCooc(final String path, final String sha256) {
        if (path == null || path.isBlank()) {
            return null;
        }
        final java.util.Map<String, java.util.Map<String, Double>> have = COOC.get(path + "|" + sha256);
        if (have != null) {
            return have;
        }
        try {
            final byte[] bytes;
            if (path.startsWith("classpath:")) {
                try (java.io.InputStream in = DeckBelief.class.getResourceAsStream(path.substring("classpath:".length()))) {
                    if (in == null) {
                        throw new IllegalStateException("deck cooc: no resource " + path);
                    }
                    bytes = in.readAllBytes();
                }
            } else {
                bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            }
            if (sha256 != null && !sha256.isBlank()) {
                final StringBuilder hex = new StringBuilder();
                for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
                    hex.append(String.format("%02x", b));
                }
                if (!hex.toString().equals(sha256)) {
                    throw new IllegalStateException("deck cooc " + path + " has sha256 " + hex + ", pinned " + sha256);
                }
            }
            final com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"mtgx-deck-cooc/1".equals(o.get("schema").getAsString())) {
                throw new IllegalStateException("deck cooc " + path + ": schema " + o.get("schema"));
            }
            final java.util.Map<String, java.util.Map<String, Double>> t = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("table").entrySet()) {
                final java.util.Map<String, Double> row = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                    row.put(f.getKey(), f.getValue().getAsDouble());
                }
                t.put(e.getKey(), row);
            }
            COOC.put(path + "|" + sha256, t);
            return t;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("deck cooc " + path + ": " + e, e);
        }
    }

    /** The archetype weight of {@code name} given the shown card names (1 without a table or a shown card in it). */
    public static double coocWeight(final String name, final List<String> shown, final Params p) {
        if (p.cooc == null || shown == null || shown.isEmpty()) {
            return 1.0;
        }
        double s = 0;
        int n = 0;
        for (String a : shown) {
            final java.util.Map<String, Double> row = p.cooc.get(a);
            if (row == null) {
                continue;
            }
            final Double v = row.get(name);
            s += v == null ? 0.0 : v;
            n++;
        }
        if (n == 0) {
            return 1.0;
        }
        return Math.exp(Math.max(-2.0, Math.min(2.0, p.coocGain * s / n)));
    }

    /** A weighted random order without replacement (Efraimidis-Spirakis keys), for the archetype weights. */
    static void weightedOrder(final List<String> names, final List<String> shown, final Params p, final Random rng) {
        final double[] key = new double[names.size()];
        final Integer[] ix = new Integer[names.size()];
        for (int i = 0; i < key.length; i++) {
            final double u = Math.max(1e-300, rng.nextDouble());
            key[i] = -Math.log(u) / coocWeight(names.get(i), shown, p);
            ix[i] = i;
        }
        java.util.Arrays.sort(ix, (a, b) -> Double.compare(key[a], key[b]));
        final List<String> out = new ArrayList<>(names.size());
        for (Integer i : ix) {
            out.add(names.get(i));
        }
        names.clear();
        names.addAll(out);
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
        /** The game turn (the open-mana likelihood's turn bucket). */
        public int turn;
        /** The non-basic names it has shown (the archetype weights' evidence). */
        public final List<String> shownNames = new ArrayList<>();

        public static Evidence read(final Game live, final Player liveOpp, final PlayerView myView) {
            final Evidence e = new Evidence();
            for (Card c : live.getCardsInGame()) {
                if (c.getOwner() != liveOpp || c.isToken() || c.isFaceDown() || c.getPaperCard() == null
                        || c.isInZone(ZoneType.Library) || !c.getView().canBeShownTo(myView)) {
                    continue;
                }
                final CardRules r = c.getPaperCard().getRules();
                e.seen++;
                if (!BeliefSampler.isBasic(c.getPaperCard().getName())) {
                    e.shownNames.add(c.getPaperCard().getName());
                }
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
            e.turn = live.getPhaseHandler().getTurn();
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
     * turn; 1 otherwise (instants, flash, what it could not afford).
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
        if (p.openLik == null || !e.offTurn || need == 0) {
            return deal0(pool, paper, e, need, libNeed, p, rng);
        }
        // AMEND-6: rejection by the open-mana likelihood ratio (at most 16 deals; the last one stands)
        final double rHas = p.openLik.ratio(e, true), rNot = p.openLik.ratio(e, false);
        final double max = Math.max(rHas, rNot);
        Deal d = null;
        for (int t = 0; t < 16; t++) {
            d = deal0(pool, paper, e, need, libNeed, p, rng);
            boolean has = false;
            for (String nm : d.hand) {
                has |= castableInstant(paper.apply(nm), e);
            }
            if (max <= 0 || rng.nextDouble() * max < (has ? rHas : rNot)) {
                break;
            }
        }
        return d;
    }

    private static Deal deal0(final List<String> pool, final Function<String, PaperCard> paper, final Evidence e,
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
        Collections.shuffle(spells, rng);
        Collections.shuffle(offSpells, rng);
        Collections.shuffle(lands, rng);
        if (p.cooc != null) {
            // AMEND-4: the archetype weights order the fitting cards (a weighted draw without replacement)
            weightedOrder(spells, e.shownNames, p, rng);
            weightedOrder(lands, e.shownNames, p, rng);
        }
        Collections.shuffle(offLands, rng);
        // lands: the fitting nonbasics and the basics of the deck's colours, about half each
        int li = 0;
        for (int i = 0; i < hl; i++) {
            final boolean basic = !basics.isEmpty() && (li >= lands.size() || rng.nextBoolean());
            if (basic) {
                dealt.add(basics.get(rng.nextInt(basics.size())));
            } else if (li < lands.size()) {
                dealt.add(lands.get(li++));
            } else {
                dealt.add(BeliefSampler.BASICS[rng.nextInt(BeliefSampler.BASICS.length)]);
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
                dealt.add(BeliefSampler.BASICS[rng.nextInt(BeliefSampler.BASICS.length)]);
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
