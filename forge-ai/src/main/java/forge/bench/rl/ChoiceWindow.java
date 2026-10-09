package forge.bench.rl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;

/**
 * S-t (lane cm-choice-search-1009; nothing creates one unless the search spec's {@code choices} is above 0, and then
 * nothing else changes): the choice asks of ONE seat inside ONE priority action's resolution window. The window opens
 * when the seat has answered the priority ask (the root) and closes at the seat's next priority with an empty stack, or
 * when the turn changes.
 *
 * <ul>
 *   <li><b>Recorder</b> (the probe pass): every non-trivial one-pick ask of a searched family in the window, with the
 *       policy's prior over its candidates (SCORE) and the policy's own answer.</li>
 *   <li><b>Schedule</b> (a macro candidate, in a play-out or after a macro departure in the live game): forced answers
 *       to named downstream asks. A scheduled ask is named by its {@link #askKey} and its ordinal among the window's asks
 *       of that key; its answer by the candidate's identity ({@link #exactKey}: object ids, which survive a game copy;
 *       {@link #looseKey}: card name, zone and controller, for objects made after the copy or in the live game). A
 *       scheduled ask whose answer is not among the candidates is a miss: the policy answers it.</li>
 * </ul>
 */
public final class ChoiceWindow {

    /** The families an S-t probe records and expands (design §3.1); every other family is the policy's. */
    public static final String DEFAULT_FAMILIES = "TARGETS,ENTITY,CARDS,MODE,CONFIRM,COST_CARDS,NUMBER,OPTIONAL_TRIGGER";

    /** One forced answer: ask {@code ask}, its {@code ordinal}-th occurrence in the window, answered by a candidate. */
    public static final class Entry {
        public final String ask;
        public final int ordinal;
        public final String exact;
        public final String loose;

        public Entry(final String ask, final int ordinal, final String exact, final String loose) {
            this.ask = ask;
            this.ordinal = ordinal;
            this.exact = exact;
            this.loose = loose;
        }

        public String label() {
            return ask + "#" + ordinal + ":=" + loose;
        }
    }

    /** One recorded ask (probe pass). */
    public static final class Ask {
        public final String ask;
        public final int ordinal;
        public final int family;
        public final int C;
        public final String[] exact;
        public final String[] loose;
        /** The policy's prior per candidate (0 for an illegal one). */
        public final double[] prior;
        /** The policy's own answer (candidate index; -1 = unknown). */
        public final int greedy;

        Ask(final String ask, final int ordinal, final int family, final String[] exact, final String[] loose,
                final double[] prior, final int greedy) {
            this.ask = ask;
            this.ordinal = ordinal;
            this.family = family;
            this.C = exact.length;
            this.exact = exact;
            this.loose = loose;
            this.prior = prior;
            this.greedy = greedy;
        }

        public double maxPrior() {
            double m = 0;
            for (double p : prior) {
                m = Math.max(m, p);
            }
            return m;
        }
    }

    /** Where a window's counts go (shared by every window of one macro, or of one game's live seat). */
    public static final class Counters {
        /** Scheduled asks answered from the schedule (by ids / by name only / by name with several matches). */
        public final AtomicInteger hitExact = new AtomicInteger(), hitLoose = new AtomicInteger(),
                hitAmbiguous = new AtomicInteger();
        /** Scheduled asks whose answer was not a candidate (the policy answered), or that Forge decided (caps). */
        public final AtomicInteger miss = new AtomicInteger();
        /** Scheduled asks that came with a single legal answer (nothing to choose). */
        public final AtomicInteger trivial = new AtomicInteger();
        /** Schedule entries never reached before the window closed. */
        public final AtomicInteger unused = new AtomicInteger();
        /** Answers the schedule changed (the policy's own answer was another candidate). */
        public final AtomicInteger changed = new AtomicInteger();

        public int hits() {
            return hitExact.get() + hitLoose.get() + hitAmbiguous.get();
        }

        /** Add these counts to {@code o}. */
        public void addTo(final Counters o) {
            o.hitExact.addAndGet(hitExact.get());
            o.hitLoose.addAndGet(hitLoose.get());
            o.hitAmbiguous.addAndGet(hitAmbiguous.get());
            o.miss.addAndGet(miss.get());
            o.trivial.addAndGet(trivial.get());
            o.unused.addAndGet(unused.get());
            o.changed.addAndGet(changed.get());
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("hit_exact", hitExact.get());
            o.addProperty("hit_loose", hitLoose.get());
            o.addProperty("hit_ambiguous", hitAmbiguous.get());
            o.addProperty("miss", miss.get());
            o.addProperty("trivial", trivial.get());
            o.addProperty("unused", unused.get());
            o.addProperty("changed", changed.get());
            return o;
        }
    }

    /** The policy's prior over a frame's candidates (the search service's SCORE). */
    public interface Scorer {
        double[] score(byte[] decidePayload) throws IOException;
    }

    private final List<Entry> schedule;
    private final boolean[] used;
    private final Counters counters;
    private final Set<Integer> families;
    private final Scorer scorer;
    /** The recorder's asks, in order (the caller's list; appended as they come), or null when not recording. */
    private final List<Ask> recorded;
    private final Map<String, Integer> ordinals = new HashMap<>();
    /** Every ask the window saw, by "FAMILY:what happened" (diagnostics: why a probe recorded or skipped an ask). */
    private final Map<String, Integer> seen = new java.util.TreeMap<>();
    private boolean open = false;
    private boolean closed = false;
    private int turn = -1;

    private ChoiceWindow(final List<Entry> schedule, final Counters counters, final Set<Integer> families,
            final Scorer scorer, final List<Ask> into) {
        this.schedule = schedule == null ? Collections.emptyList() : schedule;
        this.used = new boolean[this.schedule.size()];
        this.counters = counters == null ? new Counters() : counters;
        this.families = families;
        this.scorer = scorer;
        this.recorded = into;
    }

    /** A window that answers {@code schedule} (a macro candidate's forced answers). */
    public static ChoiceWindow scheduled(final List<Entry> schedule, final Counters counters) {
        return new ChoiceWindow(schedule, counters, null, null, null);
    }

    /** A window that records the asks of {@code families} with the scorer's prior, appending them to {@code into}. */
    public static ChoiceWindow recorder(final Set<Integer> families, final Scorer scorer, final List<Ask> into) {
        if (families == null || scorer == null || into == null) {
            throw new IllegalArgumentException("a recorder needs families, a scorer and a list");
        }
        return new ChoiceWindow(null, null, families, scorer, into);
    }

    public static Set<Integer> parseFamilies(final String csv) {
        final Set<Integer> out = new java.util.TreeSet<>();
        for (String f : csv.split(",")) {
            final String n = f.trim();
            if (n.isEmpty()) {
                continue;
            }
            final int i = RlSchema.FAMILIES.indexOf(n);
            if (i <= 0 || i == RlSchema.F_PRIORITY) {
                throw new IllegalArgumentException("search.choiceFamilies: not a choice family: " + n);
            }
            out.add(i);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("search.choiceFamilies is empty");
        }
        return out;
    }

    public boolean isOpen() {
        return open && !closed;
    }

    public boolean isClosed() {
        return closed;
    }

    public void open(final int turnNow) {
        if (!closed) {
            open = true;
            turn = turnNow;
        }
    }

    public boolean recording() {
        return recorded != null;
    }

    public Counters counters() {
        return counters;
    }

    /** True when the seat's ask at this point ends the window: a new turn, or its priority with an empty stack. */
    public boolean endsAt(final Game g, final int family) {
        return isOpen() && (g.getPhaseHandler().getTurn() != turn || family == RlSchema.F_PRIORITY && g.getStack().isEmpty());
    }

    /** Close: count the schedule entries never reached. Idempotent. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (boolean u : used) {
            if (!u) {
                counters.unused.incrementAndGet();
            }
        }
    }

    /** Diagnostics: count one ask of {@code family} the window saw and what became of it. */
    public void saw(final int family, final String what) {
        seen.merge(RlSchema.familyName(family) + ":" + what, 1, Integer::sum);
    }

    /** Diagnostics: the asks this window saw (a copy). */
    public Map<String, Integer> seen() {
        return new java.util.TreeMap<>(seen);
    }

    /** The next ordinal of {@code askKey} in this window (every ask of the key counts, trivial ones too). */
    public int nextOrdinal(final String askKey) {
        final int n = ordinals.getOrDefault(askKey, 0);
        ordinals.put(askKey, n + 1);
        return n;
    }

    private int entry(final String askKey, final int ordinal) {
        for (int i = 0; i < schedule.size(); i++) {
            final Entry e = schedule.get(i);
            if (!used[i] && e.ordinal == ordinal && e.ask.equals(askKey)) {
                return i;
            }
        }
        return -1;
    }

    /** A scheduled ask that will not be posed to the seat (trivial: one legal answer; delegated: Forge answers it). */
    public void notPosed(final String askKey, final int ordinal, final boolean trivial) {
        final int e = entry(askKey, ordinal);
        if (e >= 0) {
            used[e] = true;
            (trivial ? counters.trivial : counters.miss).incrementAndGet();
        }
    }

    /**
     * The scheduled answer to this ask, or -1 (not scheduled, or a miss: the answer is not among the legal candidates).
     * Only SINGLE asks and SUBSET asks of at most one pick are scheduled.
     */
    public int scheduledAnswer(final String askKey, final int ordinal, final RlCandidates.Menu m, final Player me,
            final int policyAnswer) {
        final int e = entry(askKey, ordinal);
        if (e < 0) {
            return -1;
        }
        used[e] = true;
        final Entry en = schedule.get(e);
        if (!singlePick(m)) {
            counters.miss.incrementAndGet();
            return -1;
        }
        int exactHit = -1;
        final List<Integer> loose = new ArrayList<>();
        for (int i = 0; i < m.C(); i++) {
            if (m.cands.get(i).kind <= 0) {
                continue;
            }
            final String l = looseKey(m, i, me);
            if (!l.equals(en.loose)) {
                continue;
            }
            loose.add(i);
            if (exactHit < 0 && exactKey(m, i).equals(en.exact)) {
                exactHit = i;
            }
        }
        final int pick;
        if (exactHit >= 0) {
            counters.hitExact.incrementAndGet();
            pick = exactHit;
        } else if (loose.size() == 1) {
            counters.hitLoose.incrementAndGet();
            pick = loose.get(0);
        } else if (!loose.isEmpty()) {
            counters.hitAmbiguous.incrementAndGet();
            pick = loose.contains(policyAnswer) ? policyAnswer : loose.get(0);
        } else {
            counters.miss.incrementAndGet();
            return -1;
        }
        if (pick != policyAnswer) {
            counters.changed.incrementAndGet();
        }
        return pick;
    }

    /** Recorder: should this ask be recorded (a searched family, one pick)? */
    public boolean wants(final int family, final RlCandidates.Menu m) {
        return recorded != null && !closed && families.contains(family) && singlePick(m);
    }

    /** Recorder: score the frame and keep the ask. A scorer failure records nothing (the ask is not expanded). */
    public void record(final String askKey, final int ordinal, final int family, final RlCandidates.Menu m,
            final Player me, final byte[] payload, final int policyAnswer) {
        final double[] p;
        try {
            p = scorer.score(payload);
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (p == null || p.length != m.C()) {
            return;
        }
        final String[] ex = new String[m.C()];
        final String[] lo = new String[m.C()];
        final double[] pr = new double[m.C()];
        for (int i = 0; i < m.C(); i++) {
            ex[i] = exactKey(m, i);
            lo[i] = looseKey(m, i, me);
            pr[i] = m.cands.get(i).kind > 0 ? p[i] : 0.0;
        }
        recorded.add(new Ask(askKey, ordinal, family, ex, lo, pr, policyAnswer));
    }

    // ------------------------------------------------------------------------------------------------ identities

    /** SINGLE, or SUBSET with at most one pick: an answer is one candidate. */
    public static boolean singlePick(final RlCandidates.Menu m) {
        return m.mode == RlSchema.M_SINGLE || m.mode == RlSchema.M_SUBSET && m.maxPick == 1;
    }

    /** An ask's identity across copies: family, the bridge method, the asking ability's source card name. */
    public static String askKey(final int family, final String method, final RlCandidates.Menu m, final JsonObject body) {
        String src = null;
        if (m.source != null) {
            src = m.source.getName();
        } else if (body != null && body.has("ability") && body.get("ability").isJsonObject()) {
            final JsonObject a = body.getAsJsonObject("ability");
            if (a.has("source") && !a.get("source").isJsonNull()) {
                src = a.get("source").getAsString();
            }
        }
        return RlSchema.familyName(family) + "|" + method + "|" + (src == null ? "-" : src);
    }

    /** A candidate's identity with object ids (ids survive a game copy). */
    public static String exactKey(final RlCandidates.Menu m, final int i) {
        final RlCandidates.Cand x = m.cands.get(i);
        final StringBuilder b = new StringBuilder();
        b.append(x.kind).append('|').append(x.key);
        if (x.host != null) {
            b.append("|h").append(x.host.getId());
        }
        if (x.tgt0 instanceof Card) {
            b.append("|t").append(((Card) x.tgt0).getId());
        }
        return b.toString();
    }

    /** A candidate's identity without ids: names, zones and controllers (me / opponent), numbers, keys without ids. */
    public static String looseKey(final RlCandidates.Menu m, final int i, final Player me) {
        final RlCandidates.Cand x = m.cands.get(i);
        final StringBuilder b = new StringBuilder();
        b.append(x.kind);
        if (x.name != null) {
            b.append("|n=").append(x.name);
        }
        if (x.host != null) {
            b.append("|h=").append(card(x.host, me));
        }
        if (x.hostStack != null) {
            b.append("|s=").append(stack(x.hostStack, me));
        }
        if (x.tgt0 != null) {
            b.append("|t=").append(x.tgt0 instanceof Player ? (x.tgt0 == me ? "me" : "opp")
                    : x.tgt0 instanceof Card ? card((Card) x.tgt0, me) : "?");
        }
        if (x.tgt1 != null) {
            b.append("|u=").append(x.tgt1 instanceof Player ? (x.tgt1 == me ? "me" : "opp")
                    : x.tgt1 instanceof Card ? card((Card) x.tgt1, me) : "?");
        }
        if (x.slot >= 0) {
            b.append("|sl=").append(x.slot);
        }
        if (x.num >= 0) {
            b.append("|#=").append(x.num);
        }
        if (x.host == null && x.hostStack == null && x.tgt0 == null && x.name == null && x.key != null
                && !x.key.startsWith("card:") && !x.key.startsWith("player:") && !x.key.startsWith("spell:")) {
            b.append("|k=").append(x.key);
        }
        return b.toString();
    }

    static String card(final Card c, final Player me) {
        final String zone = c.getZone() == null ? "?" : String.valueOf(c.getZone().getZoneType());
        final Player ctl = c.getController();
        return c.getName() + "@" + zone + "/" + (ctl == null ? "?" : ctl == me ? "me" : "opp");
    }

    static String stack(final SpellAbilityStackInstance si, final Player me) {
        final Card s = si.getSourceCard();
        final Player a = si.getActivatingPlayer();
        return (s == null ? "?" : s.getName()) + "/" + (a == null ? "?" : a == me ? "me" : "opp");
    }
}
