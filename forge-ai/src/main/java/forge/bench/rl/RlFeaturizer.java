package forge.bench.rl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import forge.card.mana.ManaAtom;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * Game + seat → the observation {@code mtgx-rl-obs/1} (interfaces.md §3; lane rl-r0-b1-1005): tokens in the §3.1
 * order (truncated at {@link RlSchema#L_MAX}), the seat's deck, the 18 pol-2M-n1 scalars, the 32 {@code ctx} features
 * and, for training only, the privileged block.
 *
 * <p>Visibility: every token passes {@code CardView.canBeShownTo(seat)}; the opponent's hidden hand is never a
 * token (only the {@code handOpp} scalar); face-down cards are index 1 ({@code <unk>}) with {@code face_down} set;
 * libraries are tokens only where Forge lets the seat look ({@code mayPlayerLook}). Forge ids are never written:
 * {@link Obs#posByCardId} maps them to token positions for the candidate builder and stays in-process.
 *
 * <p>Privileged block (critic only, §3.4 + clarification C1): (card, zone, count) for the opponent's hidden hand
 * (22), own library (23) and opponent library (24), grouped into multisets and sorted by (zone, card). It carries no
 * order, RNG or seed information by construction (R-11).
 */
public final class RlFeaturizer {

    /** One observation, in wire arrays. */
    public static final class Obs {
        public int L;
        public int[] tokCard;
        public byte[] tokZone;
        public float[] tokAttr;
        public boolean truncated;
        public int tokensBeforeCap;
        public int D;
        public int[] deckCard;
        public byte[] deckCnt;
        public final float[] scal = new float[RlSchema.N_SCAL];
        public final float[] ctx = new float[RlSchema.N_CTX];
        public boolean hasPriv;
        public int P;
        public int[] privCard = new int[0];
        public byte[] privZone = new byte[0];
        public byte[] privCnt = new byte[0];
        /** Forge card id → token position (kept tokens only). In-process only. */
        public final Map<Integer, Integer> posByCardId = new HashMap<>();
        /** Stack instance id → token position. In-process only. */
        public final Map<Integer, Integer> posByStackId = new HashMap<>();
        /** Unknown-name lookups in this frame's tokens and deck. */
        public int unknownNames;

        public int pos(final Card c) {
            if (c == null) {
                return -1;
            }
            final Integer p = posByCardId.get(c.getId());
            return p == null ? -1 : p;
        }
    }

    private final CardIndex index;
    /** Per (game, seat) deck arrays: constant through a game. */
    private final Map<Player, int[][]> deckCache = new java.util.IdentityHashMap<>();

    public RlFeaturizer(final CardIndex index) {
        this.index = index;
    }

    public CardIndex index() {
        return index;
    }

    /** Forget per-game caches (call between games). */
    public void reset() {
        deckCache.clear();
    }

    /** The card index of a visible card, honouring face-down; counts unknown names into {@code o}. */
    public int cardIndex(final Card c, final Obs o) {
        if (c == null) {
            return CardIndex.PAD;
        }
        if (c.isFaceDown()) {
            return CardIndex.UNK;
        }
        final int i = index.lookup(c.getName());
        if (i == CardIndex.UNK && o != null) {
            o.unknownNames++;
        }
        return i;
    }

    /** Card index for a host card that is not (necessarily) a token, e.g. a candidate's card. */
    public int cardIndexOf(final Card c) {
        return cardIndex(c, null);
    }

    // ------------------------------------------------------------------------------------------------ tokens

    private static final class Tok {
        final int card;
        final int zone;
        final float[] attr = new float[RlSchema.N_ATTR];
        final Card src;
        final SpellAbilityStackInstance si;

        Tok(final int card, final int zone, final Card src, final SpellAbilityStackInstance si) {
            this.card = card;
            this.zone = zone;
            this.src = src;
            this.si = si;
        }
    }

    /**
     * @param mullK cards to bottom on a MULLIGAN_BOTTOM ask, else 0
     * @param withPriv build the privileged block (train and record modes only)
     */
    public Obs observe(final Game game, final Player seat, final int mullK, final boolean withPriv) {
        final Obs o = new Obs();
        final Player opp = opponentOf(game, seat);
        final PlayerView viewer = seat.getView();
        final List<Tok> toks = new ArrayList<>(64);
        final int turn = game.getPhaseHandler().getTurn();
        final Combat combat = game.getCombat();

        // 1. own hand
        for (Card c : seat.getCardsIn(ZoneType.Hand)) {
            addCard(toks, c, RlSchema.Z_U_HAND, viewer, combat, turn, o);
        }
        // 2. own creatures, own other permanents, opponent creatures, opponent other permanents
        battlefield(toks, seat, true, RlSchema.Z_U_CRE, viewer, combat, turn, o);
        battlefield(toks, seat, false, RlSchema.Z_U_OTH, viewer, combat, turn, o);
        if (opp != null) {
            battlefield(toks, opp, true, RlSchema.Z_O_CRE, viewer, combat, turn, o);
            battlefield(toks, opp, false, RlSchema.Z_O_OTH, viewer, combat, turn, o);
        }
        // 3. the stack, top first
        int sp = 0;
        for (SpellAbilityStackInstance si : game.getStack()) {
            final Card src = si.getSourceCard();
            if (src != null && !src.getView().canBeShownTo(viewer)) {
                sp++;
                continue;
            }
            final Player act = si.getActivatingPlayer();
            final int zone = act == seat ? RlSchema.Z_U_STACK : RlSchema.Z_O_STACK;
            final Tok t = new Tok(src == null ? CardIndex.UNK : cardIndex(src, o), zone, null, si);
            final SpellAbility sa = si.getSpellAbility();
            t.attr[RlSchema.A_IS_ABILITY] = sa != null && !sa.isSpell() ? 1f : 0f;
            t.attr[RlSchema.A_STACK_POS] = sp / 10f;
            if (src != null) {
                t.attr[RlSchema.A_FACE_DOWN] = src.isFaceDown() ? 1f : 0f;
                t.attr[RlSchema.A_IS_TOKEN] = src.isToken() ? 1f : 0f;
            }
            toks.add(t);
            sp++;
        }
        // 4. own lands, opponent lands
        lands(toks, seat, RlSchema.Z_U_LAND, viewer, combat, turn, o);
        if (opp != null) {
            lands(toks, opp, RlSchema.Z_O_LAND, viewer, combat, turn, o);
        }
        // 5. revealed opponent-hand cards; known own-library positions; known opponent-library positions
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                if (c.getView().canBeShownTo(viewer)) {
                    addCard(toks, c, RlSchema.Z_O_HAND_KNOWN, viewer, combat, turn, o);
                }
            }
        }
        library(toks, seat, RlSchema.Z_U_LIB_KNOWN, viewer, combat, turn, o);
        if (opp != null) {
            library(toks, opp, RlSchema.Z_O_LIB_KNOWN, viewer, combat, turn, o);
        }
        // 6. own graveyard, opponent graveyard, own exile, opponent exile
        for (Card c : seat.getCardsIn(ZoneType.Graveyard)) {
            addCard(toks, c, RlSchema.Z_U_GONE, viewer, combat, turn, o);
        }
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Graveyard)) {
                addCard(toks, c, RlSchema.Z_O_GONE, viewer, combat, turn, o);
            }
        }
        for (Card c : seat.getCardsIn(ZoneType.Exile)) {
            addCard(toks, c, RlSchema.Z_U_EXILE, viewer, combat, turn, o);
        }
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Exile)) {
                addCard(toks, c, RlSchema.Z_O_EXILE, viewer, combat, turn, o);
            }
        }
        // 7. command zone and emblems
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Command)) {
                addCard(toks, c, RlSchema.Z_COMMAND, viewer, combat, turn, o);
            }
        }
        // 8. event tail: Phase B

        o.tokensBeforeCap = toks.size();
        o.truncated = toks.size() > RlSchema.L_MAX;
        o.L = Math.min(toks.size(), RlSchema.L_MAX);
        o.tokCard = new int[o.L];
        o.tokZone = new byte[o.L];
        o.tokAttr = new float[o.L * RlSchema.N_ATTR];
        for (int i = 0; i < o.L; i++) {
            final Tok t = toks.get(i);
            o.tokCard[i] = t.card;
            o.tokZone[i] = (byte) t.zone;
            System.arraycopy(t.attr, 0, o.tokAttr, i * RlSchema.N_ATTR, RlSchema.N_ATTR);
            if (t.src != null) {
                o.posByCardId.putIfAbsent(t.src.getId(), i);
            } else if (t.si != null) {
                o.posByStackId.putIfAbsent(t.si.getId(), i);
                if (t.si.getSourceCard() != null && t.si.getSpellAbility() != null && t.si.getSpellAbility().isSpell()) {
                    // a spell on the stack IS its card: pointers to the card resolve to its stack token
                    o.posByCardId.putIfAbsent(t.si.getSourceCard().getId(), i);
                }
            }
        }

        deck(seat, o);
        scalars(game, seat, opp, mullK, o);
        context(game, seat, opp, o);
        if (withPriv) {
            privileged(seat, opp, viewer, o);
        }
        return o;
    }

    private void battlefield(final List<Tok> toks, final Player p, final boolean creatures, final int zone,
            final PlayerView viewer, final Combat combat, final int turn, final Obs o) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            final boolean land = c.isLand();
            if (land) {
                continue;
            }
            if (c.isCreature() == creatures) {
                addCard(toks, c, zone, viewer, combat, turn, o);
            }
        }
    }

    private void lands(final List<Tok> toks, final Player p, final int zone, final PlayerView viewer,
            final Combat combat, final int turn, final Obs o) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand()) {
                addCard(toks, c, zone, viewer, combat, turn, o);
            }
        }
    }

    private void library(final List<Tok> toks, final Player p, final int zone, final PlayerView viewer,
            final Combat combat, final int turn, final Obs o) {
        int i = 0;
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            if (c.getView().canBeShownTo(viewer)) {
                final Tok t = addCard(toks, c, zone, viewer, combat, turn, o);
                if (t != null) {
                    t.attr[RlSchema.A_LIB_POS] = i / 10f;
                }
            }
            i++;
        }
    }

    private Tok addCard(final List<Tok> toks, final Card c, final int zone, final PlayerView viewer,
            final Combat combat, final int turn, final Obs o) {
        if (c == null || !c.getView().canBeShownTo(viewer)) {
            return null;
        }
        final Tok t = new Tok(cardIndex(c, o), zone, c, null);
        final float[] a = t.attr;
        a[RlSchema.A_TAPPED] = c.isTapped() ? 1f : 0f;
        a[RlSchema.A_IS_TOKEN] = c.isToken() ? 1f : 0f;
        a[RlSchema.A_FACE_DOWN] = c.isFaceDown() ? 1f : 0f;
        final boolean onField = c.isInZone(ZoneType.Battlefield);
        if (onField) {
            if (c.isCreature()) {
                a[RlSchema.A_SICK] = c.isSick() ? 1f : 0f;
                a[RlSchema.A_POWER] = c.getNetPower() / 10f;
                a[RlSchema.A_TOUGHNESS] = c.getNetToughness() / 10f;
                a[RlSchema.A_DAMAGE] = c.getDamage() / 10f;
            }
            if (combat != null) {
                a[RlSchema.A_ATTACKING] = combat.isAttacking(c) ? 1f : 0f;
                a[RlSchema.A_BLOCKING] = combat.isBlocking(c) ? 1f : 0f;
            }
            if (c.isPlaneswalker()) {
                a[RlSchema.A_LOYALTY] = c.getCurrentLoyalty() / 10f;
            }
            a[RlSchema.A_ATTACHED] = c.getEntityAttachedTo() != null ? 1f : 0f;
            a[RlSchema.A_ENTERED_THIS_TURN] = c.getTurnInZone() == turn ? 1f : 0f;
            a[RlSchema.A_CONTROLLER_DIFFERS_OWNER] = c.getController() != c.getOwner() ? 1f : 0f;
        }
        int p1 = 0, m1 = 0, other = 0;
        try {
            for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                final CounterType ct = e.getElement();
                if (ct.is(CounterEnumType.P1P1)) {
                    p1 += e.getCount();
                } else if (ct.is(CounterEnumType.M1M1)) {
                    m1 += e.getCount();
                } else if (!ct.is(CounterEnumType.LOYALTY)) {
                    other += e.getCount();
                }
            }
        } catch (RuntimeException e) {
            // counters unreadable: leave zero
        }
        a[RlSchema.A_PLUS1] = p1 / 5f;
        a[RlSchema.A_MINUS1] = m1 / 5f;
        a[RlSchema.A_OTHER_COUNTERS] = other / 5f;
        toks.add(t);
        return t;
    }

    // ------------------------------------------------------------------------------------------------ deck

    private void deck(final Player seat, final Obs o) {
        int[][] d = deckCache.get(seat);
        if (d == null) {
            final TreeMap<Integer, Integer> m = new TreeMap<>();
            final RegisteredPlayer rp = seat.getRegisteredPlayer();
            final Deck deck = rp == null ? null : rp.getDeck();
            final CardPool main = deck == null ? null : deck.getMain();
            if (main != null) {
                for (Map.Entry<PaperCard, Integer> e : main) {
                    final int idx = index.lookup(e.getKey().getName());
                    m.merge(idx, e.getValue(), Integer::sum);
                }
            }
            final int n = Math.min(m.size(), RlSchema.D_MAX);
            d = new int[2][n];
            int i = 0;
            for (Map.Entry<Integer, Integer> e : m.entrySet()) {
                if (i >= n) {
                    break;
                }
                d[0][i] = e.getKey();
                d[1][i] = Math.min(255, e.getValue());
                i++;
            }
            deckCache.put(seat, d);
        }
        o.D = d[0].length;
        o.deckCard = d[0].clone();
        o.deckCnt = new byte[o.D];
        for (int i = 0; i < o.D; i++) {
            o.deckCnt[i] = (byte) d[1][i];
        }
    }

    // ------------------------------------------------------------------------------------------------ scalars, ctx

    /** As tools/ml/foundation/forge_state.py + serve.py encode_states (format TradDraft, game 1, mana spent 0). */
    private static void scalars(final Game game, final Player seat, final Player opp, final int mullK, final Obs o) {
        final float[] s = o.scal;
        final PhaseHandler ph = game.getPhaseHandler();
        final int turn = ph.getTurn() == 0 ? 1 : ph.getTurn();
        s[0] = turn / 20f;
        s[1] = ph.getPlayerTurn() == seat ? 1f : 0f;
        s[2] = game.getStartingPlayer() == seat ? 1f : 0f;
        s[3] = seat.getLife() / 20f;
        s[4] = (opp == null ? 20 : opp.getLife()) / 20f;
        s[5] = seat.getCardsIn(ZoneType.Hand).size() / 7f;
        s[6] = (opp == null ? 0 : opp.getCardsIn(ZoneType.Hand).size()) / 7f;
        s[7] = 0f;
        s[8] = 0f;
        s[9] = mulligans(seat) / 2f;
        s[10] = (opp == null ? 0 : mulligans(opp)) / 2f;
        s[11] = 1f / 3f;
        s[12] = mullK / 3f;
        s[13 + 1] = 1f; // fmt one-hot: 'TradDraft' (forge_state default) = FORMATS index 1
    }

    private static int mulligans(final Player p) {
        try {
            return p.getStats().getMulliganCount();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void context(final Game game, final Player seat, final Player opp, final Obs o) {
        final float[] x = o.ctx;
        final PhaseHandler ph = game.getPhaseHandler();
        final PhaseType phase = ph.getPhase();
        if (phase != null) {
            final int step;
            switch (phase) {
                case UNTAP: step = 0; break;
                case UPKEEP: step = 1; break;
                case DRAW: step = 2; break;
                case MAIN1: step = 3; break;
                case COMBAT_BEGIN: step = 4; break;
                case COMBAT_DECLARE_ATTACKERS: step = 5; break;
                case COMBAT_DECLARE_BLOCKERS: step = 6; break;
                case COMBAT_FIRST_STRIKE_DAMAGE:
                case COMBAT_DAMAGE: step = 7; break;
                case COMBAT_END: step = 8; break;
                case MAIN2: step = 9; break;
                case END_OF_TURN: step = 10; break;
                case CLEANUP: step = 11; break;
                default: step = -1;
            }
            if (step >= 0) {
                x[RlSchema.C_STEP0 + step] = 1f;
            }
        }
        x[RlSchema.C_PRIORITY] = ph.getPriorityPlayer() == seat ? 1f : 0f;
        x[RlSchema.C_STACK] = game.getStack().size() / 5f;
        x[RlSchema.C_POISON_U] = seat.getPoisonCounters() / 10f;
        x[RlSchema.C_POISON_O] = (opp == null ? 0 : opp.getPoisonCounters()) / 10f;
        x[RlSchema.C_LIB_U] = seat.getCardsIn(ZoneType.Library).size() / 40f;
        x[RlSchema.C_LIB_O] = (opp == null ? 0 : opp.getCardsIn(ZoneType.Library).size()) / 40f;
        final int played = seat.getLandsPlayedThisTurn();
        x[RlSchema.C_LANDS_PLAYED] = played / 2f;
        final int left = seat.getMaxLandPlaysInfinite() ? 2 : Math.max(0, seat.getMaxLandPlays() - played);
        x[RlSchema.C_LAND_PLAYS_LEFT] = left / 2f;
        final byte[] colors = {(byte) ManaAtom.WHITE, (byte) ManaAtom.BLUE, (byte) ManaAtom.BLACK,
                (byte) ManaAtom.RED, (byte) ManaAtom.GREEN, (byte) ManaAtom.COLORLESS};
        for (int i = 0; i < 6; i++) {
            x[RlSchema.C_POOL0 + i] = seat.getManaPool().getAmountOfColor(colors[i]) / 5f;
        }
        x[RlSchema.C_SPELLS_U] = seat.getSpellsCastThisTurn() / 5f;
        x[RlSchema.C_SPELLS_O] = (opp == null ? 0 : opp.getSpellsCastThisTurn()) / 5f;
        final Player monarch = game.getMonarch();
        x[RlSchema.C_MONARCH_U] = monarch != null && monarch == seat ? 1f : 0f;
        x[RlSchema.C_MONARCH_O] = monarch != null && monarch == opp ? 1f : 0f;
        final Player init = game.getHasInitiative();
        x[RlSchema.C_INITIATIVE_U] = init != null && init == seat ? 1f : 0f;
        x[RlSchema.C_INITIATIVE_O] = init != null && init == opp ? 1f : 0f;
    }

    // ------------------------------------------------------------------------------------------------ privileged

    /**
     * The critic-only block: multisets, sorted by (zone, card). Order-free by construction: only zone membership
     * and card identity enter it (R-11).
     */
    private void privileged(final Player seat, final Player opp, final PlayerView viewer, final Obs o) {
        final List<Card> hidden = new ArrayList<>();
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                if (!c.getView().canBeShownTo(viewer)) {
                    hidden.add(c);
                }
            }
        }
        final int[][] b = privBlock(index, hidden, seat.getCardsIn(ZoneType.Library),
                opp == null ? java.util.Collections.<Card>emptyList() : opp.getCardsIn(ZoneType.Library));
        o.hasPriv = true;
        o.P = b[0].length;
        o.privCard = b[0];
        o.privZone = new byte[o.P];
        o.privCnt = new byte[o.P];
        for (int i = 0; i < o.P; i++) {
            o.privZone[i] = (byte) b[1][i];
            o.privCnt[i] = (byte) b[2][i];
        }
    }

    /**
     * The privileged block of three card collections: {card[], zone[], count[]}, grouped into multisets and sorted by
     * (zone, card), at most P_MAX entries. A pure function of zone membership and card names: iteration order,
     * RNG and seed cannot reach it (R-11).
     */
    public static int[][] privBlock(final CardIndex index, final Iterable<Card> oppHiddenHand,
            final Iterable<Card> ownLibrary, final Iterable<Card> oppLibrary) {
        final TreeMap<Long, Integer> m = new TreeMap<>();
        for (Card c : oppHiddenHand) {
            m.merge(key(RlSchema.Z_PRIV_O_HAND, index.resolve(c.getName())), 1, Integer::sum);
        }
        for (Card c : ownLibrary) {
            m.merge(key(RlSchema.Z_PRIV_U_LIB, index.resolve(c.getName())), 1, Integer::sum);
        }
        for (Card c : oppLibrary) {
            m.merge(key(RlSchema.Z_PRIV_O_LIB, index.resolve(c.getName())), 1, Integer::sum);
        }
        final int n = Math.min(m.size(), RlSchema.P_MAX);
        final int[][] b = new int[3][n];
        int i = 0;
        for (Map.Entry<Long, Integer> e : m.entrySet()) {
            if (i >= n) {
                break;
            }
            b[1][i] = (int) (e.getKey() >>> 32);
            b[0][i] = (int) (e.getKey() & 0xffffffffL);
            b[2][i] = Math.min(255, e.getValue());
            i++;
        }
        return b;
    }

    private static long key(final int zone, final int card) {
        return ((long) zone << 32) | (card & 0xffffffffL);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    public static Player opponentOf(final Game game, final Player seat) {
        for (Player p : game.getPlayers()) {
            if (p != seat) {
                return p;
            }
        }
        return null;
    }
}
