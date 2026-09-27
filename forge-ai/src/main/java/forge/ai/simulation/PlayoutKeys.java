package forge.ai.simulation;

import com.google.common.collect.Multiset;
import forge.ai.AiCache;
import forge.ai.AiCardMemory;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Keys over play-out copies, for the look-ahead speed work (lane forge-search-speed-0927).
 *
 * <p>{@link #stateKey} is a digest of everything a play-out's continuation can read that we can reach: every zone in
 * order with each card's id, state and timestamps, the stack, the priority state, the fresh-id and timestamp
 * counters, the play-out's RNG state ({@link TrackedRandom}), the id-scope counters, the AI controllers' memory and
 * loop-breaker state. Two play-outs of one world with equal keys are treated as the same position (identical-position
 * dedup); the verify mode plays both anyway and counts value mismatches, so the key's completeness is measured, not
 * assumed.
 *
 * <p>{@link #observable} is what the searching seat can see (the reuse probe's world filter).
 */
public final class PlayoutKeys {
    private PlayoutKeys() {
    }

    /**
     * {@link java.util.Random} with a readable state. {@code next(bits)} and {@code nextGaussian} are Random's own
     * algorithms (the stream is identical to {@code new Random(seed)}); the state lives here so a key can include it.
     */
    public static final class TrackedRandom extends Random {
        private static final long MULT = 0x5DEECE66DL, ADD = 0xBL, MASK = (1L << 48) - 1;
        // No initializers: Random's constructor calls setSeed before this class's initializers would run.
        private long s;
        private double nextNextGaussian;
        private boolean haveNextNextGaussian;

        public TrackedRandom(long seed) {
            super(seed);
        }

        @Override
        public synchronized void setSeed(long seed) {
            s = (seed ^ MULT) & MASK;
            haveNextNextGaussian = false;
        }

        @Override
        protected synchronized int next(int bits) {
            s = (s * MULT + ADD) & MASK;
            return (int) (s >>> (48 - bits));
        }

        @Override
        public synchronized double nextGaussian() {
            if (haveNextNextGaussian) {
                haveNextNextGaussian = false;
                return nextNextGaussian;
            }
            double v1, v2, q;
            do {
                v1 = 2 * nextDouble() - 1;
                v2 = 2 * nextDouble() - 1;
                q = v1 * v1 + v2 * v2;
            } while (q >= 1 || q == 0);
            double multiplier = StrictMath.sqrt(-2 * StrictMath.log(q) / q);
            nextNextGaussian = v2 * multiplier;
            haveNextNextGaussian = true;
            return v1 * multiplier;
        }

        /** The full state: {seed, haveNextNextGaussian ? 1 : 0, bits of nextNextGaussian}. */
        public synchronized long[] snapshot() {
            return new long[] {s, haveNextNextGaussian ? 1 : 0, Double.doubleToRawLongBits(nextNextGaussian)};
        }

        /** A stream that continues exactly where {@link #snapshot()} was taken. */
        public static TrackedRandom ofSnapshot(long[] snap) {
            final TrackedRandom r = new TrackedRandom(0L);
            synchronized (r) {
                r.s = snap[0];
                r.haveNextNextGaussian = snap[1] != 0;
                r.nextNextGaussian = Double.longBitsToDouble(snap[2]);
            }
            return r;
        }

        public synchronized String state() {
            return Long.toHexString(s) + (haveNextNextGaussian ? "g" + Double.doubleToLongBits(nextNextGaussian) : "");
        }
    }

    static String rngState() {
        final Random r = MyRandom.getRandom();
        if (r instanceof TrackedRandom tr) {
            return tr.state();
        }
        // Not a tracked stream: never let two such positions compare equal.
        return "untracked@" + System.identityHashCode(r) + "#" + System.nanoTime();
    }

    // ------------------------------------------------------------------ exact state key

    /** A 128-bit digest (hex) of the play-out state; see the class comment. Call on the play-out's own thread. */
    public static String stateKey(Game g) {
        final StringBuilder sb = new StringBuilder(8192);
        final PhaseHandler ph = g.getPhaseHandler();
        sb.append("T").append(ph.getTurn()).append(' ').append(ph.getPhase())
                .append(" pt=").append(id(ph.getPlayerTurn()))
                .append(" pr=").append(id(ph.getPriorityPlayer()))
                .append(" fp=").append(id(LookaheadSearch.firstPriority(ph)))
                .append(" gp=").append(LookaheadSearch.givePriorityFlag(ph))
                .append(" over=").append(g.isGameOver())
                .append(" ts=").append(g.getTimestamp());
        final int[] ids = g.peekCardIdCounters();
        sb.append(" cid=").append(ids[0]).append('/').append(ids[1]);
        sb.append(" rng=").append(rngState());
        final Object scope = forge.util.IdScope.capture();
        if (scope instanceof java.util.concurrent.atomic.AtomicInteger[] a) {
            sb.append(" ids=");
            for (java.util.concurrent.atomic.AtomicInteger x : a) {
                sb.append(x.get()).append(',');
            }
        }
        final Object cache = AiCache.captureScope();
        if (cache instanceof com.google.common.collect.Multimap<?, ?> m) {
            sb.append(" aic=").append(m.size());
        }
        sb.append('\n');
        final Combat combat = ph.getCombat();
        if (combat != null) {
            sb.append("combat");
            for (Card a : combat.getAttackers()) {
                sb.append(' ').append(a.getId()).append('>').append(entityId(combat.getDefenderByAttacker(a)));
            }
            sb.append(" |");
            for (Card b : combat.getAllBlockers()) {
                sb.append(' ').append(b.getId()).append(':');
                for (Card a : combat.getAttackersBlockedBy(b)) {
                    sb.append(a.getId()).append(',');
                }
            }
            sb.append('\n');
        }
        for (SpellAbilityStackInstance si : g.getStack()) {
            sb.append("stack ").append(si.getId()).append(' ').append(id(si.getSourceCard())).append(' ')
                    .append(id(si.getActivatingPlayer())).append(' ');
            final SpellAbility sa = si.getSpellAbility();
            if (sa != null) {
                sb.append(sa.getDescription());
                for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                    if (s.usesTargeting()) {
                        sb.append(" t[");
                        for (GameObject o : s.getTargets()) {
                            sb.append(objId(o)).append(',');
                        }
                        sb.append(']');
                    }
                }
            }
            sb.append('\n');
        }
        for (Player p : g.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife()).append(" poison=").append(p.getPoisonCounters())
                    .append(" lost=").append(p.hasLost()).append(" won=").append(p.hasWon())
                    .append(" lands=").append(p.getLandsPlayedThisTurn()).append(" spells=").append(p.getSpellsCastThisTurn())
                    .append(" drawn=").append(p.getNumDrawnThisTurn())
                    .append(" lifeLost=").append(p.getLifeLostThisTurn()).append(" lifeGained=").append(p.getLifeGainedThisTurn());
            counters(sb, p);
            sb.append(" mana=");
            final List<String> mana = new ArrayList<>();
            for (Mana m : p.getManaPool()) {
                mana.add(m.toString() + "@" + id(m.getSourceCard()));
            }
            Collections.sort(mana);
            sb.append(mana);
            controller(sb, p);
            sb.append('\n');
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Battlefield, ZoneType.Command}) {
                sb.append(' ').append(z).append(':');
                for (Card c : p.getCardsIn(z)) {
                    card(sb, c);
                }
                sb.append('\n');
            }
        }
        return digest(sb);
    }

    private static void controller(StringBuilder sb, Player p) {
        final PlayerController pc = p.getController();
        sb.append(" ctl=").append(pc.getClass().getSimpleName());
        if (pc instanceof LookaheadSearch.RolloutAi ra) {
            sb.append(ra.loopState());
        }
        if (pc instanceof PlayerControllerAi ai) {
            final AiCardMemory mem = ai.getAi().getCardMemory();
            for (AiCardMemory.MemorySet set : AiCardMemory.MemorySet.values()) {
                if (mem.isMemorySetEmpty(set)) {
                    continue;
                }
                final List<Integer> ids = new ArrayList<>();
                for (Card c : AiCardMemory.getMemorySet(p, set)) {
                    ids.add(c.getId());
                }
                Collections.sort(ids);
                sb.append(" mem.").append(set).append(ids);
            }
            sb.append(" aiState=").append(ai.getAi().lookaheadStateKey());
        }
    }

    private static void card(StringBuilder sb, Card c) {
        sb.append(" {").append(c.getId()).append(' ').append(c.getName()).append(' ').append(c.getCurrentStateName());
        sb.append(c.isTapped() ? " T" : " U");
        if (c.isSick()) {
            sb.append(" sick");
        }
        if (c.isFaceDown()) {
            sb.append(" fd");
        }
        if (c.isPhasedOut()) {
            sb.append(" po");
        }
        if (c.isToken()) {
            sb.append(" tok");
        }
        sb.append(" c").append(id(c.getController())).append(" o").append(id(c.getOwner()));
        sb.append(" gts").append(c.getGameTimestamp()).append(" lts").append(c.getLayerTimestamp()).append(" tiz").append(c.getTurnInZone());
        if (c.isInPlay()) {
            sb.append(" pt").append(c.getNetPower()).append('/').append(c.getNetToughness()).append(" d").append(c.getDamage());
        }
        counters(sb, c);
        if (c.getEntityAttachedTo() != null) {
            sb.append(" at").append(entityId(c.getEntityAttachedTo()));
        }
        if (c.getExiledWith() != null) {
            sb.append(" xw").append(c.getExiledWith().getId());
        }
        boolean first = true;
        for (Object o : c.getRemembered()) {
            sb.append(first ? " rem[" : ",").append(objId(o));
            first = false;
        }
        if (!first) {
            sb.append(']');
        }
        if (!c.getImprintedCards().isEmpty()) {
            sb.append(" imp").append(ids(c.getImprintedCards()));
        }
        if (!c.getChosenCards().isEmpty()) {
            sb.append(" ch").append(ids(c.getChosenCards()));
        }
        if (c.getChosenType() != null && !c.getChosenType().isEmpty()) {
            sb.append(" ct").append(c.getChosenType());
        }
        if (c.getNamedCard() != null && !c.getNamedCard().isEmpty()) {
            sb.append(" nc").append(c.getNamedCard());
        }
        if (c.getChosenColors() != null) {
            final StringBuilder cc = new StringBuilder();
            for (String col : c.getChosenColors()) {
                cc.append(col).append(',');
            }
            if (cc.length() > 0) {
                sb.append(" cc").append(cc);
            }
        }
        if (c.getChosenNumber() != null) {
            sb.append(" cn").append(c.getChosenNumber());
        }
        if (c.getChosenPlayer() != null) {
            sb.append(" cp").append(c.getChosenPlayer().getId());
        }
        sb.append('}');
    }

    private static void counters(StringBuilder sb, GameEntity e) {
        final Map<String, Integer> cn = new TreeMap<>();
        for (Multiset.Entry<CounterType> x : e.getCounters().entrySet()) {
            cn.put(String.valueOf(x.getElement()), x.getCount());
        }
        if (!cn.isEmpty()) {
            sb.append(" n").append(cn);
        }
    }

    private static List<Integer> ids(Iterable<Card> cs) {
        final List<Integer> l = new ArrayList<>();
        for (Card c : cs) {
            l.add(c.getId());
        }
        return l;
    }

    private static String id(Card c) {
        return c == null ? "-" : String.valueOf(c.getId());
    }

    private static String id(Player p) {
        return p == null ? "-" : String.valueOf(p.getId());
    }

    private static String entityId(GameEntity e) {
        if (e == null) {
            return "-";
        }
        return (e instanceof Player ? "p" : "c") + e.getId();
    }

    private static String objId(Object o) {
        if (o instanceof Card c) {
            return "c" + c.getId();
        }
        if (o instanceof Player p) {
            return "p" + p.getId();
        }
        if (o instanceof SpellAbility sa) {
            return "s" + sa.getId();
        }
        return o == null ? "-" : o.getClass().getSimpleName() + ":" + o;
    }

    static String digest(CharSequence s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(s.toString().getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((d[i] >> 4) & 0xf, 16)).append(Character.forDigit(d[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ observable signature

    /**
     * What {@code me} can see of {@code g}: public zones by name and state, its own hand by name, the opponents'
     * hand sizes, library sizes, life, counters, the stack, and the priority state. Names, not ids: a copy's new
     * objects (tokens, stack instances) get different ids than the live game's.
     */
    public static String observable(Game g, Player me) {
        final StringBuilder sb = new StringBuilder(2048);
        final PhaseHandler ph = g.getPhaseHandler();
        sb.append("T").append(ph.getTurn()).append(' ').append(ph.getPhase()).append(" pt=").append(id(ph.getPlayerTurn()))
                .append(" pr=").append(id(ph.getPriorityPlayer())).append('\n');
        for (SpellAbilityStackInstance si : g.getStack()) {
            sb.append("stack ").append(si.getSourceCard() == null ? "-" : si.getSourceCard().getName()).append(' ')
                    .append(si.getSpellAbility() == null ? "" : si.getSpellAbility().getDescription()).append('\n');
        }
        for (Player p : g.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife()).append(" poison=").append(p.getPoisonCounters())
                    .append(" lib=").append(p.getCardsIn(ZoneType.Library).size())
                    .append(" hand=").append(p == me ? LookaheadSearch.names(p.getCardsIn(ZoneType.Hand)).toString()
                            : String.valueOf(p.getCardsIn(ZoneType.Hand).size()))
                    .append(" mana=").append(p.getManaPool().totalMana());
            counters(sb, p);
            sb.append('\n');
            final List<String> gy = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
                gy.add(c.getName());
            }
            sb.append(" gy=").append(gy).append('\n');
            final List<String> ex = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Exile)) {
                ex.add(c.isFaceDown() ? "?" : c.getName());
            }
            Collections.sort(ex);
            sb.append(" ex=").append(ex).append('\n');
            final List<String> bf = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                final StringBuilder cb = new StringBuilder(c.isFaceDown() ? "?" : c.getName());
                cb.append(c.isTapped() ? "/T" : "/U");
                if (c.isCreature()) {
                    cb.append('/').append(c.getNetPower()).append('/').append(c.getNetToughness()).append("/d").append(c.getDamage());
                }
                counters(cb, c);
                if (c.getEntityAttachedTo() != null) {
                    cb.append("@").append(c.getEntityAttachedTo() instanceof Card a ? a.getName() : "P" + c.getEntityAttachedTo().getId());
                }
                bf.add(cb.toString());
            }
            Collections.sort(bf);
            sb.append(" bf=").append(bf).append('\n');
        }
        return sb.toString();
    }
}
