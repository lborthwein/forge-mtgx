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
        /** R-TRUNC census: tokens dropped by the cap, by zone id (diagnostics; not on the wire). */
        public int[] droppedByZone = new int[0];
        /** C4' census: names of command-zone objects (zone 19) that resolved to {@code <unk>} (diagnostics). */
        public List<String> commandUnknown = new ArrayList<>();
        public int D;
        public int[] deckCard;
        public byte[] deckCnt;
        public final float[] scal = new float[RlSchema.N_SCAL];
        /** N_CTX of the observation's schema (v1 32, v2 69). */
        public float[] ctx = new float[RlSchema.N_CTX];
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
        /** Debug only (K8 converter parity, B6): the bridge's ForgeState of this seat at the same moment. */
        public com.google.gson.JsonObject forgeState;

        // ---- observation v2 (lane rl-obs-v2-1006; ICR obs-v2-1006 note N1); empty in v1
        public int version = 1;
        public long[] tokBits = new long[0];
        public int R, F, Dr;
        public short[] relSrc = new short[0];
        public short[] relDst = new short[0];
        public byte[] relType = new byte[0];
        public byte[] relArg = new byte[0];
        public short[] relNum = new short[0];
        public short[] factTok = new short[0];
        public short[] factId = new short[0];
        public int[] factArg = new int[0];
        public short[] factNum = new short[0];
        public int[] restCard = new int[0];
        public byte[] restCnt = new byte[0];
        /** v2: a relation or fact was dropped because its token was truncated (wire flags bit3). */
        public boolean droppedRefs;
        /** v2 census: relations whose endpoint is not a token in this frame; facts that hit a vocabulary's OTHER. */
        public int relsUnresolved, factsOther, relsCapped, factsCapped;
        /** v2 census: o_seen tokens in this frame. */
        public int oSeen;
        /** v2, in-process only (witness tests): the card ids behind the o_seen tokens, in token order. */
        public final List<Integer> oSeenIds = new ArrayList<>();

        public int pos(final Card c) {
            if (c == null) {
                return -1;
            }
            final Integer p = posByCardId.get(c.getId());
            return p == null ? -1 : p;
        }
    }

    private final CardIndex index;
    /**
     * Debug only (off by default; RlActorBench config {@code debugForgeStateOut}): also capture
     * {@code StateEncoder.encode(game, seat)} with every observation, for the K8 converter parity check. The encoder
     * reads mana abilities, so never set it in a run whose digests must match Forge.
     */
    public boolean captureForgeState = false;
    /** Observation v1 seat knowledge for the current game (Appendix B.2); null = Phase A behaviour (visibility only). */
    private RlKnowledge knowledge;
    /** Per (game, seat) deck arrays: constant through a game. */
    private final Map<Player, int[][]> deckCache = new java.util.IdentityHashMap<>();
    /** Observation schema version: 1 (default, mtgx-rl-obs/1) or 2 (mtgx-rl-obs/2, RlObsV2). */
    private int version = 1;

    public int version() {
        return version;
    }

    public void setVersion(final int v) {
        if (v != 1 && v != 2) {
            throw new IllegalArgumentException("observation schema version " + v);
        }
        this.version = v;
    }

    public String schemaSha() {
        return version == 2 ? RlSchemaV2.schemaSha() : RlSchema.schemaSha();
    }

    public String proto() {
        return version == 2 ? RlWire.PROTO_V2 : RlWire.PROTO;
    }

    public RlFeaturizer(final CardIndex index) {
        this.index = index;
    }

    public CardIndex index() {
        return index;
    }

    /** Forget per-game caches (call between games). */
    public void reset() {
        deckCache.clear();
        knowledge = null;
    }

    /** The current game's seat-knowledge tracker (observation v1). */
    public void setKnowledge(final RlKnowledge k) {
        this.knowledge = k;
    }

    public RlKnowledge knowledge() {
        return knowledge;
    }

    boolean knows(final Player seat, final Card c) {
        return knowledge != null && knowledge.knows(knowledge.seatOf(seat), c);
    }

    /** The card index of a visible card, honouring face-down; counts unknown names into {@code o}. */
    public int cardIndex(final Card c, final Obs o) {
        if (c == null) {
            return CardIndex.PAD;
        }
        if (c.isFaceDown()) {
            return CardIndex.UNK;
        }
        final int i = lookupCard(index, c);
        if (i == CardIndex.UNK && o != null) {
            o.unknownNames++;
        }
        return i;
    }

    /**
     * Clarification C3: a face of a multi-face card (a split half, either face of a DFC / MDFC, an adventure's spell
     * half) resolves to the FULL card's entry. Forge's full card name is the card's PaperCard name ("A // B" for split
     * cards, the main face for every other layout), tried first, then the §3.2 rules on the face's own name; the same
     * order as {@code tools/ml/rl/cardindex.py lookup} with its face map. Counts an unknown name.
     */
    public static int lookupCard(final CardIndex index, final Card card) {
        final Card c = shownHost(card); // C4': an "X's Effect" object (a stack item's or candidate's host) is X
        if (isEmblemName(c.getName())) {
            final int r = resolveEmblem(index, c.getName(), emblemWalkerFullName(c));
            return r != CardIndex.UNK ? r : index.lookup(c.getName());
        }
        final String full = fullName(c);
        if (full != null && !full.equals(c.getName())) {
            final int r = index.resolve(full);
            if (r != CardIndex.UNK) {
                return r;
            }
        }
        return index.lookup(c.getName());
    }

    /** As {@link #lookupCard} without counting. */
    public static int resolveCard(final CardIndex index, final Card card) {
        final Card c = shownHost(card);
        if (isEmblemName(c.getName())) {
            return resolveEmblem(index, c.getName(), emblemWalkerFullName(c));
        }
        final String full = fullName(c);
        if (full != null && !full.equals(c.getName())) {
            final int r = index.resolve(full);
            if (r != CardIndex.UNK) {
                return r;
            }
        }
        return index.resolve(c.getName());
    }

    /** Forge's emblem names: "Emblem — <walker>" (the card table's rows are "<walker> Emblem"). */
    public static final String EMBLEM_PREFIX = "Emblem \u2014 ";

    public static boolean isEmblemName(final String name) {
        return name != null && name.startsWith(EMBLEM_PREFIX);
    }

    /**
     * Clarification C4' (supersedes C4): an emblem resolves to its own {@code "<walker> Emblem"} row; else to its
     * walker's full card (C3: {@code walkerFullName}, the effect source's PaperCard name, when known), else to the
     * walker by name. The same rule as the k8 converter / cardindex.py: strip "Emblem — ", look up
     * "&lt;rest&gt; Emblem", else C3 on &lt;rest&gt;. UNK when nothing resolves (not counted).
     */
    public static int resolveEmblem(final CardIndex index, final String name, final String walkerFullName) {
        final String walker = name.substring(EMBLEM_PREFIX.length());
        int r = index.resolve(walker + " Emblem");
        if (r == CardIndex.UNK && walkerFullName != null) {
            r = index.resolve(walkerFullName);
        }
        if (r == CardIndex.UNK) {
            r = index.resolve(walker);
        }
        return r;
    }

    /** An emblem's walker: the full card name of its effect source, or null. */
    public static String emblemWalkerFullName(final Card emblem) {
        final Card s = emblem.getEffectSource();
        return s == null || s == emblem ? null : fullName(s);
    }

    /**
     * C4': the card a host stands for. A Forge "Effect" object that is not itself a command-zone game object (an
     * "X's Effect" / "X's Boon" holding a delayed trigger) stands for its effect source's card, wherever it appears:
     * the event tail, a stack item's host, a candidate's host. Everything else stands for itself. (Name rule for the
     * converter: "X (id)'s Effect" is X.)
     */
    public static Card shownHost(final Card host) {
        if (host != null && host.getGamePieceType() == forge.card.GamePieceType.EFFECT && !commandObject(host)) {
            final Card src = host.getEffectSource();
            if (src != null && src != host) {
                return src;
            }
        }
        return host;
    }

    /** Forge's full card name (PaperCard), or null. */
    public static String fullName(final Card c) {
        try {
            final forge.item.IPaperCard pc = c.getPaperCard();
            return pc == null ? null : pc.getName();
        } catch (RuntimeException e) {
            return null;
        }
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
        return observe(game, seat, mullK, withPriv, null);
    }

    /** As above; {@code menu} (v2 only: PILE membership facts) is the ask being posed, or null. */
    public Obs observe(final Game game, final Player seat, final int mullK, final boolean withPriv,
            final RlCandidates.Menu menu) {
        if (version == 2) {
            return RlObsV2.observe(this, game, seat, mullK, withPriv, menu);
        }
        final Obs o = new Obs();
        if (captureForgeState) {
            try {
                o.forgeState = forge.bench.StateEncoder.encode(game, seat);
            } catch (RuntimeException e) {
                o.forgeState = null;
            }
        }
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
        //    (visible now, or known to this seat from what it observed: Appendix B.2)
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                if (c.getView().canBeShownTo(viewer)) {
                    addCard(toks, c, RlSchema.Z_O_HAND_KNOWN, viewer, combat, turn, o);
                } else if (knows(seat, c)) {
                    addKnown(toks, c, RlSchema.Z_O_HAND_KNOWN, o);
                }
            }
        }
        library(toks, seat, seat, RlSchema.Z_U_LIB_KNOWN, viewer, combat, turn, o);
        if (opp != null) {
            library(toks, seat, opp, RlSchema.Z_O_LIB_KNOWN, viewer, combat, turn, o);
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
        // 7. command zone: emblems, the monarch / initiative markers, dungeons, the ring (Forge's effect cards are not)
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Command)) {
                if (commandObject(c)) {
                    final Tok t = addCard(toks, c, RlSchema.Z_COMMAND, viewer, combat, turn, o);
                    if (t != null && t.card == CardIndex.UNK) {
                        o.commandUnknown.add(c.getName());
                    }
                }
            }
        }
        // 8. event tail: the last 16 spells and abilities put on the stack, newest first (zones 20 / 21)
        if (knowledge != null) {
            final int me = knowledge.seatOf(seat);
            int rank = 0;
            for (RlKnowledge.StackEvent e : knowledge.tail()) {
                int card = CardIndex.UNK;
                if (!e.faceDown && e.name != null) {
                    if (isEmblemName(e.name)) {
                        card = resolveEmblem(index, e.name, e.fullName); // C4': fullName is the walker's
                    } else {
                        card = e.fullName != null && !e.fullName.equals(e.name) ? index.resolve(e.fullName)
                                : CardIndex.UNK;
                    }
                    if (card == CardIndex.UNK) {
                        card = index.lookup(e.name);
                    }
                }
                if (card == CardIndex.UNK && !e.faceDown && e.name != null) {
                    o.unknownNames++;
                }
                final Tok t = new Tok(card, e.controllerSeat == me ? RlSchema.Z_U_EVENT : RlSchema.Z_O_EVENT, null,
                        null);
                t.attr[RlSchema.A_IS_ABILITY] = e.ability ? 1f : 0f;
                t.attr[RlSchema.A_FACE_DOWN] = e.faceDown ? 1f : 0f;
                t.attr[RlSchema.A_EVENT_AGE] = rank / 16f;
                toks.add(t);
                rank++;
            }
        }

        o.tokensBeforeCap = toks.size();
        o.truncated = toks.size() > RlSchema.L_MAX;
        if (o.truncated) {
            o.droppedByZone = new int[RlSchema.ZONES.size()];
            for (int i = RlSchema.L_MAX; i < toks.size(); i++) {
                o.droppedByZone[toks.get(i).zone]++;
            }
        }
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

    private void library(final List<Tok> toks, final Player seat, final Player p, final int zone,
            final PlayerView viewer, final Combat combat, final int turn, final Obs o) {
        int i = 0;
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            Tok t = null;
            if (c.getView().canBeShownTo(viewer)) {
                t = addCard(toks, c, zone, viewer, combat, turn, o);
            } else if (knows(seat, c)) {
                t = addKnown(toks, c, zone, o);
            }
            if (t != null) {
                t.attr[RlSchema.A_LIB_POS] = i / 10f;
            }
            i++;
        }
    }

    /** A hidden card the seat knows (Appendix B.2): its identity, no live attributes. */
    private Tok addKnown(final List<Tok> toks, final Card c, final int zone, final Obs o) {
        final int idx = lookupCard(index, c);
        if (idx == CardIndex.UNK) {
            o.unknownNames++;
        }
        final Tok t = new Tok(idx, zone, c, null);
        toks.add(t);
        return t;
    }

    /** Command-zone objects a person sees as game objects: emblems, dungeons, the monarch, initiative, the ring. */
    static boolean commandObject(final Card c) {
        if (c == null) {
            return false;
        }
        if (c.isEmblem() || c.getType().isDungeon() || c.isCommander()) {
            return true;
        }
        final String n = c.getName();
        return "The Monarch".equals(n) || "The Initiative".equals(n) || "The Ring".equals(n);
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

    void deck(final Player seat, final Obs o) {
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
    static void scalars(final Game game, final Player seat, final Player opp, final int mullK, final Obs o) {
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

    static void context(final Game game, final Player seat, final Player opp, final Obs o) {
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
                if (!c.getView().canBeShownTo(viewer) && !knows(seat, c)) {
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
        return privBlock(index, oppHiddenHand, ownLibrary, oppLibrary, RlSchema.Z_PRIV_O_HAND, RlSchema.Z_PRIV_U_LIB,
                RlSchema.Z_PRIV_O_LIB);
    }

    /** As above with the schema's three critic-only zone ids (v1 22-24, v2 23-25). */
    public static int[][] privBlock(final CardIndex index, final Iterable<Card> oppHiddenHand,
            final Iterable<Card> ownLibrary, final Iterable<Card> oppLibrary, final int zHand, final int zOwnLib,
            final int zOppLib) {
        final TreeMap<Long, Integer> m = new TreeMap<>();
        for (Card c : oppHiddenHand) {
            m.merge(key(zHand, resolveCard(index, c)), 1, Integer::sum);
        }
        for (Card c : ownLibrary) {
            m.merge(key(zOwnLib, resolveCard(index, c)), 1, Integer::sum);
        }
        for (Card c : oppLibrary) {
            m.merge(key(zOppLib, resolveCard(index, c)), 1, Integer::sum);
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
