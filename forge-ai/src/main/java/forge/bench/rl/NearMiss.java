package forge.bench.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * nearmiss-gen-1109 (off unless {@code -Dforge.bench.label.nearMiss=K} with K &gt; 0): generic near-miss copies of a
 * labelled position. At a position of the replayed game, for the seat to move, copy k (1..K) gets ONE edit drawn from
 * generic engine moves, never chosen by card name:
 * <ul>
 *   <li>{@code move:A>B}: one card of the seat from zone A to zone B, A and B among library (L), hand (H) and graveyard
 *       (G); a card going to the library goes to a uniform position;</li>
 *   <li>{@code land:add}: one land card the seat owns off the battlefield (library, hand or graveyard) enters the
 *       battlefield, untapped; {@code land:remove}: one untapped land the seat controls goes to the graveyard;</li>
 *   <li>{@code tap} / {@code untap}: one land the seat controls changes its tapped state;</li>
 *   <li>{@code life}: the seat's life changes by +-N, N in 1..{@link #LIFE_MAX} (never to 0 or less).</li>
 * </ul>
 * The draw is weighted (coordinator 11-09: the moves that turn an on-board win into a draw-dependent one, and back, get
 * most of the mass): {@code move:H>L} and {@code move:L>H} 35 % each, land add/remove 6 % each, tap, untap and life 4 %
 * each, the other four moves 1.5 % each; a kind with nothing to act on is left out and the rest renormalised.
 *
 * <p>The edit is chosen once from the live position with its own random stream, {@code Random(mix(SEED, game seed,
 * seat, dec_idx, k))}, so copies are reproducible from the seed. It names cards by id (GameCopier copies keep card and
 * player ids) and is applied to any copy of the live game through Forge's zone-move primitives ({@code
 * GameAction.moveTo}, {@code moveToPlay}, {@code moveToGraveyard}) with triggers suppressed while it runs, then the
 * copy's static abilities are re-applied. The live game is never edited.
 */
public final class NearMiss {
    /** K copies per selected position; 0 (the default) = off: nothing in the labeller changes. */
    public static final int K = Integer.getInteger("forge.bench.label.nearMiss", 0);
    /** The edit seed (registry: nearmiss-gen-1109 offset 2,000 = 747,633,000). */
    public static final long SEED = Long.getLong("forge.bench.label.nearMissSeed", 0L);
    /** Largest life change. */
    public static final int LIFE_MAX = Integer.getInteger("forge.bench.label.nearMissLife", 5);
    /** Worlds of the second check (0 = none): run on copies whose edit touched the library or whose verdict was fractional. */
    public static final int WORLDS2 = Integer.getInteger("forge.bench.label.nearMissWorlds2", 0);
    /** Positions to edit (and the only positions labelled when on): lines "seed:seat:dec_idx"; absent = every position. */
    static final Set<String> ONLY = RlReplayLabeller.loadLabelOnly(System.getProperty("forge.bench.label.nearMissOnly"));

    private NearMiss() {
    }

    public static boolean on() {
        return K > 0;
    }

    /** Whether the position is one to label and edit (nearMissOnly; every position when that list is absent). */
    public static boolean selected(final long seed, final int seat, final int ord) {
        return ONLY == null || ONLY.contains(seed + ":" + seat + ":" + ord);
    }

    /** Kinds and their weights (per 200), in draw order. */
    static final String[] KINDS = {"move:H>L", "move:L>H", "land:add", "land:remove", "tap", "untap", "life",
            "move:H>G", "move:L>G", "move:G>H", "move:G>L"};
    static final int[] WEIGHTS = {70, 70, 12, 12, 8, 8, 8, 3, 3, 3, 3};

    /** One edit of one copy: what it does, to which card (by id), and its description. */
    public static final class Edit {
        public final String kind;
        public final int seat;
        public final int cardId;
        public final ZoneType from, to;
        /** Library position for a card going to the library (0 = top), else -1. */
        public final int libPos;
        public final int lifeDelta;
        public final String desc;

        Edit(final String kind, final int seat, final int cardId, final ZoneType from, final ZoneType to, final int libPos,
                final int lifeDelta, final String desc) {
            this.kind = kind;
            this.seat = seat;
            this.cardId = cardId;
            this.from = from;
            this.to = to;
            this.libPos = libPos;
            this.lifeDelta = lifeDelta;
            this.desc = desc;
        }

        /** Whether the edit moved a card into or out of the seat's library (coordinator 11-09: those get WORLDS2). */
        public boolean touchesLibrary() {
            return from == ZoneType.Library || to == ZoneType.Library;
        }

        /** The edit's top-level kind for reports: move:H>L ... land:add, land:remove, tap, untap, life. */
        public String family() {
            return kind;
        }

        /**
         * Apply to {@code g}, a copy of the live game the edit was chosen in. Returns null when applied, else why not
         * (the card is not where the live game had it: the copy is left as it was).
         */
        public String apply(final Game g) {
            final List<Player> ps = g.getRegisteredPlayers();
            if (seat < 0 || seat >= ps.size()) {
                return "no seat " + seat;
            }
            final Player me = ps.get(seat);
            final TriggerHandler th = g.getTriggerHandler();
            final List<TriggerType> added = new ArrayList<>();
            for (TriggerType t : new TriggerType[] {TriggerType.ChangesZone, TriggerType.ChangesZoneAll,
                    TriggerType.Taps, TriggerType.Untaps, TriggerType.LifeGained, TriggerType.LifeLost,
                    TriggerType.LifeLostAll, TriggerType.Drawn, TriggerType.Discarded, TriggerType.DiscardedAll}) {
                if (!th.isTriggerSuppressed(t)) {
                    th.suppressMode(t);
                    added.add(t);
                }
            }
            try {
                if ("life".equals(kind)) {
                    me.setLife(me.getLife() + lifeDelta, null);
                    return null;
                }
                final Card c = g.findById(cardId);
                if (c == null) {
                    return "card " + cardId + " not in the copy";
                }
                final ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
                if (z != from) {
                    return "card " + cardId + " is in " + z + ", not " + from;
                }
                switch (kind) {
                    case "tap":
                        c.setTapped(true);
                        break;
                    case "untap":
                        c.setTapped(false);
                        break;
                    case "land:add": {
                        final Card nc = g.getAction().moveToPlay(c, me, null, AbilityKey.newMap());
                        if (nc != null) {
                            nc.setTapped(false);
                        }
                        break;
                    }
                    case "land:remove":
                        g.getAction().moveToGraveyard(c, null, AbilityKey.newMap());
                        break;
                    default: // move:A>B
                        if (to == ZoneType.Library) {
                            g.getAction().moveToLibrary(c, Math.min(libPos, me.getCardsIn(ZoneType.Library).size()), null,
                                    AbilityKey.newMap());
                        } else {
                            g.getAction().moveTo(to, c, null, AbilityKey.newMap());
                        }
                }
                return null;
            } finally {
                for (TriggerType t : added) {
                    th.clearSuppression(t);
                }
                g.getAction().checkStaticAbilities(false);
            }
        }
    }

    static long mix(final long a, final long b) {
        return RlSearch.splitmix(a * 0x9E3779B97F4A7C15L ^ RlSearch.splitmix(b));
    }

    /** The random stream of copy {@code k} of position (game seed, seat, ord). */
    static Random rng(final long gameSeed, final int seat, final int ord, final int k) {
        return new Random(mix(mix(mix(mix(SEED, gameSeed), seat), ord), k));
    }

    static boolean isLand(final Card c) {
        return c.isLand();
    }

    /** Choose copy {@code k}'s edit for {@code me} in the live game (read only). Never null: life always applies. */
    public static Edit choose(final Game live, final Player me, final long gameSeed, final int ord, final int k) {
        final int seat = live.getRegisteredPlayers().indexOf(me);
        final Random r = rng(gameSeed, seat, ord, k);
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        final List<Card> gy = new ArrayList<>(me.getCardsIn(ZoneType.Graveyard));
        final List<Card> untapped = new ArrayList<>(), tapped = new ArrayList<>(), off = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (isLand(c) && c.getController() == me) {
                (c.isTapped() ? tapped : untapped).add(c);
            }
        }
        for (List<Card> z : java.util.Arrays.asList(lib, hand, gy)) {
            for (Card c : z) {
                if (isLand(c)) {
                    off.add(c);
                }
            }
        }
        final int[] w = WEIGHTS.clone();
        for (int i = 0; i < KINDS.length; i++) {
            final String kd = KINDS[i];
            final boolean ok;
            switch (kd) {
                case "land:add": ok = !off.isEmpty(); break;
                case "land:remove": case "tap": ok = !untapped.isEmpty(); break;
                case "untap": ok = !tapped.isEmpty(); break;
                case "life": ok = true; break;
                default: ok = !zoneList(kd.charAt(5), lib, hand, gy).isEmpty();
            }
            if (!ok) {
                w[i] = 0;
            }
        }
        int tot = 0;
        for (int x : w) {
            tot += x;
        }
        int pick = r.nextInt(tot), i = 0;
        while (pick >= w[i]) {
            pick -= w[i];
            i++;
        }
        final String kd = KINDS[i];
        switch (kd) {
            case "life": {
                final int n = 1 + r.nextInt(Math.max(1, LIFE_MAX));
                int d = r.nextBoolean() ? n : -n;
                if (me.getLife() + d < 1) {
                    d = n;
                }
                return new Edit(kd, seat, -1, null, null, -1, d, kd + " " + (d > 0 ? "+" : "") + d + " (" + me.getLife()
                        + " -> " + (me.getLife() + d) + ")");
            }
            case "land:add": {
                final Card c = off.get(r.nextInt(off.size()));
                final ZoneType z = c.getZone().getZoneType();
                return new Edit(kd, seat, c.getId(), z, ZoneType.Battlefield, -1, 0, kd + " " + c.getName() + " (" + c.getId()
                        + ") from " + z);
            }
            case "land:remove": {
                final Card c = untapped.get(r.nextInt(untapped.size()));
                return new Edit(kd, seat, c.getId(), ZoneType.Battlefield, ZoneType.Graveyard, -1, 0, kd + " " + c.getName()
                        + " (" + c.getId() + ")");
            }
            case "tap": case "untap": {
                final List<Card> src = "tap".equals(kd) ? untapped : tapped;
                final Card c = src.get(r.nextInt(src.size()));
                return new Edit(kd, seat, c.getId(), ZoneType.Battlefield, ZoneType.Battlefield, -1, 0, kd + " "
                        + c.getName() + " (" + c.getId() + ")");
            }
            default: {
                final List<Card> src = zoneList(kd.charAt(5), lib, hand, gy);
                final int at = r.nextInt(src.size());
                final Card c = src.get(at);
                final ZoneType from = zoneOf(kd.charAt(5)), to = zoneOf(kd.charAt(7));
                final int pos = to == ZoneType.Library ? r.nextInt(lib.size() + 1) : -1;
                return new Edit(kd, seat, c.getId(), from, to, pos, 0, kd + " " + c.getName() + " (" + c.getId() + ")"
                        + (from == ZoneType.Library ? " from library index " + at + "/" + lib.size() : "")
                        + (pos >= 0 ? " to library index " + pos + "/" + (lib.size() + 1) : ""));
            }
        }
    }

    static List<Card> zoneList(final char z, final List<Card> lib, final List<Card> hand, final List<Card> gy) {
        return z == 'L' ? lib : z == 'H' ? hand : gy;
    }

    static ZoneType zoneOf(final char z) {
        return z == 'L' ? ZoneType.Library : z == 'H' ? ZoneType.Hand : ZoneType.Graveyard;
    }
}
