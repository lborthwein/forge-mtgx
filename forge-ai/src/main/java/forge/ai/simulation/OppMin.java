package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * The opponent's MIN over its responses to a line (architect-o ruling 10-30, the storm question): ONE implementation for
 * the solver's proof check ({@link ForcedWinSolver#minWorld}) and the R3 label search's lines ({@link R3LabelSearch}).
 *
 * <ul>
 * <li><b>Response points</b>: one of our spells, activated abilities or triggers on top of the stack, or our
 * declare-attackers step with attackers declared ({@link #pointKey}).</li>
 * <li><b>Response set</b> ({@link #answers}): every answer the opponent can cast or activate now and pay for is a
 * response (review #148 rulings: there is no incidental set): from their hand, battlefield, graveyard, exile and command
 * zone, and from any card they may play or activate elsewhere (our battlefield and stack objects with "any player may
 * activate", our hand, graveyard and exile, either library top, our command zone; casting from our hand is blocked by the
 * engine's owner check in this build). The only non-answers are pure mana
 * abilities (only mana and tapping themselves, no rider). Equivalent responses collapse only by the same source card (by
 * id), effect, cost and target, where a permanent target is keyed by its card id (re-check 7 ruling: no state collapse)
 * and a stack target by its stack entry.</li>
 * <li><b>Fail closed</b>: an answer OppMin cannot enumerate faithfully is a gap that makes the point a cap hit (never
 * "no response"), logged by reason ({@link Answers#gaps}).</li>
 * <li><b>Budget</b>: the responses the opponent MAKES are bounded, not the points. Every point is eligible. The budget is
 * min(k, the answers it holds that it can cast and pay for): 1, or 2 when it holds two or more castable answers with the
 * mana for both (k max = min(2, depth)). A point met with the budget spent and a non-empty set is a cap hit.</li>
 * <li><b>Tree</b>: the root is Forge AI's own play at every point; then "pass everywhere"; then, depth first, every
 * script of up to budget responses in point order, the highest-impact point (the top object's mana value) first and
 * counters first within a point. Scripts are re-played from the world's start (same seeds, so a shared prefix replays
 * the same); a subtree is expanded once per (state at the point, budget left) (memo). The first 0 stops the tree.</li>
 * <li><b>Caps count as not won</b>: the node cap, a set larger than the per-point cap, or a spent budget facing a
 * non-empty set make a world whose min is above 0 unresolved.</li>
 * </ul>
 */
public final class OppMin {

    private OppMin() {
    }

    /** The MIN's version (review #148 S2-1): in the proof spec's identity and the solver config string. */
    public static final int VERSION = 2;

    /** -Dforge.oppmin.debug=true: one stderr line per branch and per answered point (diagnostics only). */
    public static final boolean DEBUG = Boolean.getBoolean("forge.oppmin.debug");

    // ------------------------------------------------------------------------------------------------ config

    /** The counts (no clock). {@code depth} caps the budget (k max = min(2, depth)). */
    public static final class Cfg {
        public int nodes = 64, perPoint = 4, depth = 2, k = 1;
        public boolean memo = true;
        /**
         * covfix-a-1102 (oppMinRev 3, ARCH-PROPOSAL-1102 layer 3; false = the default, and then nothing changes): a
         * branch's response budget is {@link #depth} itself, by search, instead of the hand-written resource reader
         * (budgetHint: 2 only when their two cheapest answers fit their mana estimate) and the min(2, depth) clamp. The
         * node budget still bounds the tree: over it is the oppNodes cap (fail closed).
         */
        public boolean budgetByDepth;
        /**
         * solver-gaps-1104 (oppMinLoopMemo; false = off, the default, and then nothing changes): a point is expanded once
         * per loop state. A later point whose {@link Point#loopKey} (the position with the monotone counters out, the
         * stack by name) equals an earlier expanded one's at the same budget left, and whose counters
         * ({@link ForcedWinEngine#progress}) dominate that one's for us, is a memo hit: the same answer earlier, before
         * our loop gained, is no worse for the opponent. A loop's points then cost one iteration's, not n.
         */
        public boolean loopMemo;

        public Cfg(final int nodes, final int perPoint, final int depth) {
            if (perPoint > CHARM_CAP) {
                // review #148 (fail closed): a per-point cap at or above the modal enumeration cap would let a cut
                // modal set pass as complete
                throw new IllegalArgumentException("oppResponses " + perPoint + " > " + CHARM_CAP);
            }
            this.nodes = nodes;
            this.perPoint = perPoint;
            this.depth = depth;
        }

        int kMax() {
            return budgetByDepth ? Math.max(0, depth) : Math.max(0, Math.min(2, depth));
        }
    }

    // ------------------------------------------------------------------------------------------------ scripts

    /** One branch: the responses made, by point index (ascending) and response index; {@code forge}: Forge AI's own play. */
    public static final class Script {
        final boolean forge;
        final int[] pts, resp;
        /** The budget of this subtree (-1: read it from the play-out's first non-empty point). */
        final int budget;
        /**
         * opp-belief-1104 (null = a script by indices, as before): an information-set policy, point signature to answer
         * signature ({@link #pointSig}, {@link #answerSig}); a point it does not name is passed.
         */
        final Map<String, String> policy;

        Script(final boolean forge, final int[] pts, final int[] resp, final int budget) {
            this(forge, pts, resp, budget, null);
        }

        private Script(final boolean forge, final int[] pts, final int[] resp, final int budget,
                final Map<String, String> policy) {
            this.forge = forge;
            this.pts = pts;
            this.resp = resp;
            this.budget = budget;
            this.policy = policy;
        }

        /** opp-belief-1104: the policy {@code p} (empty = pass everywhere). */
        public static Script policy(final Map<String, String> p) {
            return new Script(false, new int[0], new int[0], 0, Collections.unmodifiableMap(new java.util.LinkedHashMap<>(p)));
        }

        /** opp-belief-1104: the policy's key ("forge", or its signature map). */
        public String policyKey() {
            return forge ? "forge" : policy != null ? policy.toString() : toString();
        }

        static Script forgeRoot() {
            return new Script(true, new int[0], new int[0], 0);
        }

        static Script passAll() {
            return new Script(false, new int[0], new int[0], -1);
        }

        Script with(final int pt, final int r, final int b) {
            final int[] p = Arrays.copyOf(pts, pts.length + 1), q = Arrays.copyOf(resp, resp.length + 1);
            p[pts.length] = pt;
            q[resp.length] = r;
            return new Script(false, p, q, b);
        }

        int respAt(final int pt) {
            for (int i = 0; i < pts.length; i++) {
                if (pts[i] == pt) return resp[i];
            }
            return -1;
        }

        int last() {
            return pts.length == 0 ? -1 : pts[pts.length - 1];
        }

        @Override
        public String toString() {
            if (forge) return "forge";
            if (policy != null) return "policy" + policy;
            final StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(pts[i]).append(':').append(resp[i]);
            }
            return sb.append(']').toString();
        }
    }

    /** One response point a play-out met. */
    public static final class Point {
        public final int size;
        final String state;
        final int impact;
        final int budgetHint;
        /** Review #148 (fail closed): why the set is not complete (an answer OppMin cannot enumerate), or null. */
        public final String gap;
        /**
         * coverage-fix-1102 (diagnostics only, never read to decide): the source cards of each gap reason, and the
         * point's answers (host names, set order), for the meter's per-row cap read-out.
         */
        java.util.Map<String, java.util.TreeSet<String>> gapCards = java.util.Collections.emptyMap();
        List<String> answerNames = java.util.Collections.emptyList();
        /** solver-gaps-1104 (loopMemo only; null otherwise): the point's loop state and our counters there. */
        String loopKey;
        long[] pv;

        Point(final int size, final String state, final int impact, final int budgetHint) {
            this(size, state, impact, budgetHint, null);
        }

        Point(final int size, final String state, final int impact, final int budgetHint, final String gap) {
            this.size = size;
            this.state = state;
            this.impact = impact;
            this.budgetHint = budgetHint;
            this.gap = gap;
        }
    }

    /** One branch's play-out: its value (1 won, 0 lost, NaN unresolved), end and the points it met. */
    public static final class Leaf {
        public final double v;
        public final String end;
        public final List<Point> points;
        /** opp-belief-1104: the responses this branch made, point signature to answer signature (null: not recorded). */
        public Map<String, String> realized;

        public Leaf(final double v, final String end, final List<Point> points) {
            this.v = v;
            this.end = end;
            this.points = points == null ? Collections.<Point>emptyList() : points;
        }

        public Leaf realized(final Map<String, String> r) {
            this.realized = r;
            return this;
        }
    }

    /** The world's MIN. */
    public static final class Out {
        public double v = Double.NaN;
        public String end;
        /** The cap that withheld a win (null: none). */
        public String cap;
        /** The script of the min branch. */
        public String why;
        public int nodes, memoHits, maxResponses, budget;
        /** solver-gaps-1104 (loopMemo): points skipped as a dominated repeat of a loop state. */
        public int loopMemoHits;
        public Leaf firstLeaf;
        /** opp-belief-1104: the min branch's script and realized responses; every explored branch by policy key. */
        public Script minScript;
        public Map<String, String> minRealized;
        public final java.util.LinkedHashMap<String, Double> explored = new java.util.LinkedHashMap<>();
        /** opp-belief-1104 (beliefMin only): the pick's policy key, each candidate's Q, the candidates' play-outs. */
        public String beliefPick;
        public String beliefQ;
        public int beliefNodes, beliefCands;
        /** Every cap event of the tree by reason (review #148: the recall cost of failing closed). */
        public final java.util.TreeMap<String, Integer> caps = new java.util.TreeMap<>();
        /** coverage-fix-1102 (diagnostics only): the answer cards behind each cap reason (gaps, truncated, budget). */
        public final java.util.TreeMap<String, java.util.TreeSet<String>> capCards = new java.util.TreeMap<>();

        void cap(final String why) {
            if (cap == null) cap = why;
            caps.merge(why, 1, Integer::sum);
        }

        void capCards(final String why, final java.util.Collection<String> names) {
            if (names != null && !names.isEmpty()) capCards.computeIfAbsent(why, k -> new java.util.TreeSet<>()).addAll(names);
        }
    }

    // ------------------------------------------------------------------------------------------------ the tree

    /** The MIN over the opponent's budgeted response tree; {@code play} runs one branch (same world, same seeds). */
    public static Out min(final Cfg cfg, final java.util.function.Function<Script, Leaf> play) {
        final Out out = new Out();
        final java.util.ArrayDeque<Script> stack = new java.util.ArrayDeque<>();
        stack.push(Script.passAll());
        stack.push(Script.forgeRoot());
        final Set<String> memo = new HashSet<>();
        final java.util.Map<String, List<long[]>> loopSeen = new java.util.HashMap<>(); // solver-gaps-1104 (loopMemo)
        double lo = Double.POSITIVE_INFINITY;
        while (!stack.isEmpty()) {
            final Script s = stack.pop();
            if (out.nodes >= cfg.nodes) {
                out.cap("nodes");
                break;
            }
            out.nodes++;
            final Leaf l = play.apply(s);
            if (out.firstLeaf == null) out.firstLeaf = l;
            if (Double.isNaN(l.v)) {
                out.cap("branch:" + l.end);
                continue;
            }
            if (l.realized != null) {
                out.explored.putIfAbsent(s.forge ? "forge" : l.realized.toString(), l.v); // opp-belief-1104
            }
            if (l.v < lo) {
                lo = l.v;
                out.end = l.end;
                out.why = s.toString();
                out.minScript = s;
                out.minRealized = l.realized;
            }
            if (lo <= 0.0) {
                break; // the floor: no branch goes lower
            }
            if (s.forge) continue;
            final List<Point> pts = l.points;
            int b = s.budget;
            if (b < 0) {
                b = 0;
                for (Point p : pts) {
                    if (p.size > 0) {
                        b = cfg.budgetByDepth ? cfg.kMax() : Math.min(cfg.kMax(), Math.max(0, p.budgetHint));
                        break;
                    }
                }
                out.budget = Math.max(out.budget, b);
            }
            final int left = b - s.pts.length;
            final List<Script> kids = new ArrayList<>();
            final List<Integer> kidImpact = new ArrayList<>();
            for (int j = s.last() + 1; j < pts.size(); j++) {
                final Point p = pts.get(j);
                if (p.gap != null) {
                    // fail closed: an answer this point's set could not enumerate completely makes it a cap hit
                    out.cap("gap:" + p.gap);
                    for (java.util.Map.Entry<String, java.util.TreeSet<String>> e : p.gapCards.entrySet()) {
                        out.capCards("gap:" + e.getKey(), e.getValue());
                    }
                }
                if (p.size == 0) continue;
                out.maxResponses = Math.max(out.maxResponses, p.size);
                if (p.size > cfg.perPoint) {
                    out.cap("truncated");
                    out.capCards("truncated", p.answerNames);
                    continue;
                }
                if (left <= 0) {
                    out.cap("budget");
                    out.capCards("budget", p.answerNames);
                    continue;
                }
                if (cfg.memo && !memo.add(p.state + "|" + left)) {
                    out.memoHits++;
                    continue;
                }
                if (cfg.loopMemo && p.loopKey != null && p.pv != null) {
                    // solver-gaps-1104: an earlier point of the same loop state that this one's counters dominate
                    final List<long[]> seen = loopSeen.computeIfAbsent(p.loopKey + "|" + left, k -> new ArrayList<>());
                    boolean dominated = false;
                    for (long[] e : seen) {
                        if (ForcedWinEngine.dominates(p.pv, e)) {
                            dominated = true;
                            break;
                        }
                    }
                    if (dominated) {
                        out.memoHits++;
                        out.loopMemoHits++;
                        continue;
                    }
                    seen.add(p.pv);
                }
                for (int r = 0; r < p.size; r++) {
                    kids.add(s.with(j, r, b));
                    kidImpact.add(p.impact);
                }
            }
            // depth first: the highest-impact point first, then point order, counters (lower index) first
            final Integer[] order = new Integer[kids.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (x, y) -> {
                final int c = Integer.compare(kidImpact.get(y), kidImpact.get(x));
                return c != 0 ? c : Integer.compare(x, y);
            });
            for (int i = order.length - 1; i >= 0; i--) stack.push(kids.get(order[i]));
        }
        if (lo <= 0.0) {
            out.v = 0.0;
            out.cap = null;
        } else if (out.cap != null || lo == Double.POSITIVE_INFINITY) {
            out.v = Double.NaN;
            out.end = "min-cap:" + (out.cap == null ? "none" : out.cap);
        } else {
            out.v = lo;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ belief (A2)

    /**
     * opp-belief-1104 (ARCH-APPENDIX-1104 A2, L3; used only with oppBelief K >= 1): the opponent's reply from its OWN
     * belief worlds, one reply per information set. {@code play(u, script)} plays one branch in world u: u = 0 is our
     * world (the one valued), u = 1..K the opponent's worlds (built from ours with our cards it cannot see re-dealt from
     * a canonical pool, so they never depend on where those cards really are).
     * <ol>
     * <li>Candidates, in order: Forge AI's own play everywhere, pass everywhere, then the min branch of {@link #min}'s
     * ordinary tree in each u (as a policy of public signatures), deduplicated, at most {@code maxCands}.</li>
     * <li>Q(pi) = the mean of our result over the u where pi resolved (values found by the trees reused).</li>
     * <li>pi* = argmin Q; ties to the candidate order (Forge AI's natural play first).</li>
     * <li>pi* is played once in world 0: its result is the value. Fail closed: pi* capped there, or a win there while a
     * tree of some u was capped (a better reply may be missing), is unresolved (cap {@code belief:<reason>}).</li>
     * </ol>
     */
    public static Out beliefMin(final Cfg cfg, final int k, final int maxCands,
            final java.util.function.BiFunction<Integer, Script, Leaf> play) {
        final Out out = new Out();
        final java.util.LinkedHashMap<String, Script> cands = new java.util.LinkedHashMap<>();
        cands.put("forge", Script.forgeRoot());
        cands.put("{}", Script.policy(Collections.emptyMap()));
        final java.util.Map<String, double[]> vals = new java.util.HashMap<>();
        final java.util.Map<String, boolean[]> seen = new java.util.HashMap<>(); // played (resolved or not)
        String uCap = null;
        for (int u = 1; u <= k; u++) {
            final int uu = u;
            final Out m = min(cfg, s -> play.apply(uu, s));
            out.nodes += m.nodes;
            out.memoHits += m.memoHits;
            out.loopMemoHits += m.loopMemoHits;
            out.maxResponses = Math.max(out.maxResponses, m.maxResponses);
            out.budget = Math.max(out.budget, m.budget);
            for (Map.Entry<String, Integer> e : m.caps.entrySet()) out.caps.merge("u:" + e.getKey(), e.getValue(), Integer::sum);
            if (Double.isNaN(m.v) && uCap == null) uCap = m.cap == null ? "none" : m.cap;
            for (Map.Entry<String, Double> e : m.explored.entrySet()) {
                vals.computeIfAbsent(e.getKey(), x -> nanRow(k))[u] = e.getValue();
                seen.computeIfAbsent(e.getKey(), x -> new boolean[k + 1])[u] = true;
            }
            if (m.minScript != null && m.minRealized != null && cands.size() < Math.max(2, maxCands)) {
                final String key = m.minScript.forge ? "forge" : m.minRealized.toString();
                if (!cands.containsKey(key)) cands.put(key, Script.policy(m.minRealized));
            }
        }
        // every candidate in every u it has not met yet
        String pick = null;
        double best = Double.POSITIVE_INFINITY;
        final StringBuilder q = new StringBuilder();
        for (Map.Entry<String, Script> c : cands.entrySet()) {
            final double[] row = vals.computeIfAbsent(c.getKey(), x -> nanRow(k));
            final boolean[] met = seen.computeIfAbsent(c.getKey(), x -> new boolean[k + 1]);
            double sum = 0;
            int n = 0;
            for (int u = 1; u <= k; u++) {
                if (!met[u]) {
                    met[u] = true;
                    out.beliefNodes++;
                    final Leaf l = play.apply(u, c.getValue());
                    row[u] = l.v;
                }
                if (!Double.isNaN(row[u])) {
                    sum += row[u];
                    n++;
                }
            }
            final double qv = n == 0 ? Double.POSITIVE_INFINITY : sum / n;
            q.append(q.length() == 0 ? "" : " ").append(c.getKey()).append('=').append(n == 0 ? "na" : String.format("%.3f", qv));
            if (qv < best) {
                best = qv;
                pick = c.getKey();
            }
        }
        if (pick == null) pick = "forge";
        out.beliefCands = cands.size();
        out.beliefPick = pick;
        out.beliefQ = q.toString();
        final Script ps = cands.get(pick);
        out.beliefNodes++;
        final Leaf l = play.apply(0, ps);
        out.firstLeaf = l;
        out.minScript = ps;
        out.minRealized = l.realized;
        out.why = ps.toString();
        out.end = l.end;
        if (Double.isNaN(l.v)) {
            out.cap("belief:branch:" + l.end);
            out.v = Double.NaN;
            out.end = "min-cap:" + out.cap;
        } else if (l.v > 0.0 && uCap != null) {
            out.cap("belief:" + uCap);
            out.v = Double.NaN;
            out.end = "min-cap:" + out.cap;
        } else {
            out.v = l.v;
            out.cap = null;
        }
        return out;
    }

    private static double[] nanRow(final int k) {
        final double[] r = new double[k + 1];
        Arrays.fill(r, Double.NaN);
        return r;
    }

    // ------------------------------------------------------------------------------------------------ the adversary

    /**
     * The opponent's side of one branch: at each response point (first visit), the response set, the point's record and
     * the scripted choice (the script's response, else pass; Forge AI's own play under the root script).
     */
    public static final class Driver {
        final Script script;
        final Player opp, victim;
        final int onlyTurn;
        public final List<Point> points = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        public String answered;

        /** {@code onlyTurn}: points only in that turn (-1: any). */
        /**
         * covfix-a-1102 (ARCH-PROPOSAL-1102 layer 3; false = off, the default, and then nothing changes): the opponent's
         * mana abilities with a non-mana part (a cost beyond mana and tapping itself, a rider, or a mana trigger on the
         * battlefield) are responses like any other: activated on the copy, their costs and effects resolved, the MIN
         * continuing from there. Pure mana abilities with no trigger are the "no response" branch.
         */
        public boolean manaSearch;
        /** solver-gaps-1104 (Cfg#loopMemo): record each point's loop state and counters. */
        public boolean loopMemo;
        /**
         * solver-gaps-1104 (oppMinRevealPoints; false = off, the default, and then nothing changes): the information the
         * opponent has seen is a decision point of its own. Its priority in its own main phase with an empty stack is a
         * response point while we have a nonland permanent that entered the battlefield since its previous turn (a card
         * it has seen enter, {@link #revealKey}). Its choices there: Forge AI's own play, pass (hold its mana and cards
         * for our turn), and each castable answer that targets one of those permanents or us. A setup cast before its
         * turn is answerable there; one flashed in at its end step is not seen until then. The answers that do not
         * target the revealed cards stay Forge AI's (this point is an addition to the base, never a removal).
         */
        public boolean reveal;

        /**
         * opp-belief-1104 (oppHoldPoints, false = off): the opponent's own main-phase priority with an empty stack is a
         * point even when none of our permanents is new (its hold decision: Forge AI's play, pass, or an answer aimed at
         * us or a permanent of ours that entered since its previous turn). Read with {@link #reveal}.
         */
        public boolean hold;
        /** opp-belief-1104: the responses made, point signature to answer signature (every script mode). */
        public final java.util.LinkedHashMap<String, String> realized = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Integer> sigCount = new java.util.HashMap<>();

        /** The point key at this priority: {@link OppMin#pointKey}, else (reveal only) {@link #revealKey}. */
        private String key(final Game g) {
            final String pk = pointKey(g, victim);
            return pk != null || !reveal ? pk : revealKey(g, opp, victim, hold);
        }
        /** covfix-a-1102: a scripted mana-ability response could not be activated on the copy (fail closed: a cap). */
        public boolean manaSimFailed;
        /** Review #169 S1-2: the point key whose priority reopens as a new point after a mana activation, and a counter. */
        private String reopen;
        private int reopenSeq;

        public Driver(final Script script, final Player opp, final Player victim, final int onlyTurn) {
            this.script = script;
            this.opp = opp;
            this.victim = victim;
            this.onlyTurn = onlyTurn;
        }

        /** solver-gaps-1104 (loopMemo): the point's loop state (the coarse position, the stack by name) and counters. */
        private Point loop(final Point p, final Game g) {
            if (!loopMemo) return p;
            try {
                final StringBuilder sb = new StringBuilder(ForcedWinEngine.coarseKey(g, victim));
                for (SpellAbilityStackInstance si : g.getStack()) {
                    final SpellAbility sa = si.getSpellAbility();
                    sb.append("|st:").append(sa == null ? "?" : (sa.getHostCard() == null ? "" : sa.getHostCard().getName())
                            + "/" + sa.getDescription() + "/" + (sa.getActivatingPlayer() == victim ? "v" : "o"));
                }
                p.loopKey = sb.toString();
                p.pv = ForcedWinEngine.progress(g, victim);
            } catch (RuntimeException e) {
                p.loopKey = null; // no loop state: the exact memo only
            }
            return p;
        }

        /** Whether this priority is a new response point (and records it if so). */
        public boolean isPoint(final Game g) {
            final String pk = key(g);
            return pk != null && (onlyTurn < 0 || g.getPhaseHandler().getTurn() == onlyTurn) && !seen.contains(pk);
        }

        /**
         * The opponent's choice at this priority: null = pass; {@code own} = Forge AI's own play. Not a point: {@code own}.
         */
        public List<SpellAbility> choose(final Game g, final java.util.function.Supplier<List<SpellAbility>> own) {
            String pk = key(g);
            if (pk != null && pk.equals(reopen)) {
                // review #169 S1-2: after a mana activation the same priority is a NEW point, its answers read again from
                // the post-activation state (a counter castable only now), "pass" included
                pk = pk + "|mana" + (++reopenSeq);
            }
            reopen = null;
            if (pk == null || (onlyTurn >= 0 && g.getPhaseHandler().getTurn() != onlyTurn) || !seen.add(pk)) {
                return own.get();
            }
            final SpellAbility top = g.getStack().isEmpty() ? null : g.getStack().peek().getSpellAbility();
            final int idx = points.size();
            if (DEBUG) {
                final StringBuilder b = new StringBuilder();
                for (Card c : opp.getCardsIn(ZoneType.Battlefield)) b.append(c.getName()).append(c.isTapped() ? "/T" : "/U")
                        .append(c.isCreature() ? "/cr " : " ");
                System.err.println("[oppmin] point " + idx + " " + pk + " " + g.getPhaseHandler().getPhase() + " opp bf: " + b
                        + " stack " + g.getStack().size());
            }
            final String state = stateKey(g);
            final boolean rev = pk.startsWith("rev:");
            final Answers as = rev ? revealAnswers(g, opp, victim) : answers(g, opp, victim, top, manaSearch);
            final List<SpellAbility> rs = as.list;
            // review S1 (safety net, the base's behaviour): Forge AI's own answer at this point is a candidate too
            final List<SpellAbility> mine = own.get();
            if (script.forge) {
                points.add(loop(diag(new Point(rs.size(), state, impact(top), budgetHint(opp, rs), as.gap()), as, rs), g));
                return mine;
            }
            final SpellAbility fa = mine == null || mine.isEmpty() ? null : mine.get(0);
            if (fa != null && fa.getActivatingPlayer() == opp && !fa.isManaAbility() && !sameAsAny(fa, rs)) {
                rs.add(fa);
            }
            points.add(loop(diag(new Point(rs.size(), state, impact(top), budgetHint(opp, rs), as.gap()), as, rs), g));
            if (DEBUG && as.gap() != null) System.err.println("[oppmin] point " + idx + " gap " + as.gap());
            // opp-belief-1104: the point's public signature (n-th occurrence in this play-out)
            final String sig0 = pointSig(g, victim, pk);
            final int occ = sigCount.merge(sig0, 1, Integer::sum);
            final String psig = sig0 + "#" + occ;
            int r = -1;
            if (script.policy != null) {
                final String want = script.policy.get(psig);
                if (want != null) {
                    for (int i = 0; i < rs.size() && r < 0; i++) {
                        if (want.equals(answerSig(rs.get(i), victim))) r = i;
                    }
                }
            } else {
                r = script.respAt(idx);
            }
            if (r < 0 || r >= rs.size()) return null;
            final SpellAbility sa = rs.get(r);
            realized.put(psig, answerSig(sa, victim));
            if (DEBUG) System.err.println("[oppmin] point " + idx + " answer " + answerKey(sa) + " of " + rs.size());
            final Card host = sa.getHostCard();
            if (host != null && host.isInZone(ZoneType.Battlefield) && sa.getApi() == ApiType.Animate
                    && (!sa.hasParam("Defined") || "Self".equals(sa.getParam("Defined")))) {
                // a manland animating itself to block must not pay with itself (Forge AI would tap it for mana)
                forge.ai.AiCardMemory.rememberCard(opp, host, forge.ai.AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
            answered = (top == null ? "attack" : top.getHostCard().getName()) + " answered by " + sa.getHostCard().getName();
            if (sa.isManaAbility()) {
                // covfix-a-1102: a mana-ability response is activated here (mana abilities do not use the stack): its
                // costs and riders resolve now. Review #169 S1-1: the cost's choices are the response's own (forced),
                // never Forge AI's. S3: an activation that changed nothing (refused, unpaid) is mana-sim, a cap.
                final List<List<Integer>> forced = as.forced.get(sa);
                final String before = manaState(g, opp);
                boolean ok;
                try {
                    ok = forge.ai.ComputerUtil.handlePlayingSpellAbility(opp, sa, null, ab -> new ForcedCost(opp, ab, forced));
                } catch (RuntimeException e) {
                    ok = false;
                }
                if (!ok || before.equals(manaState(g, opp))) manaSimFailed = true;
                // review #169 S1-2: the follow-up is the MIN's, not Forge AI's: this priority again, as a new point
                reopen = key(g);
                return choose(g, own);
            }
            return new ArrayList<>(Collections.singletonList(sa));
        }
    }

    /**
     * solver-gaps-1104 (Driver#reveal): a reveal point at the opponent's own main-phase priority with an empty stack,
     * while {@link #revealed} is not empty; null otherwise. Keyed by turn, phase and the revealed cards.
     */
    public static String revealKey(final Game g, final Player opp, final Player victim) {
        return revealKey(g, opp, victim, false);
    }

    /** opp-belief-1104: as above; {@code hold}: also with nothing revealed (the opponent's hold decision). */
    public static String revealKey(final Game g, final Player opp, final Player victim, final boolean hold) {
        final PhaseHandler ph = g.getPhaseHandler();
        if (!g.getStack().isEmpty() || !ph.isPlayerTurn(opp) || !(ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2))) return null;
        final List<Card> r = revealed(g, victim);
        if (r.isEmpty() && !hold) return null;
        final StringBuilder sb = new StringBuilder("rev:").append(ph.getTurn()).append(':').append(ph.getPhase());
        for (Card c : r) sb.append(':').append(c.getId());
        return sb.toString();
    }

    /** The victim's nonland permanents that entered the battlefield since the opponent's previous turn (by turn in zone). */
    static List<Card> revealed(final Game g, final Player victim) {
        final List<Card> out = new ArrayList<>();
        final int turn = g.getPhaseHandler().getTurn();
        for (Card c : victim.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isLand() && c.getTurnInZone() >= turn - 1) out.add(c);
        }
        return out;
    }

    /**
     * solver-gaps-1104: the answers at a reveal point: the castable answers ({@link #answers}, every zone) that target a
     * revealed card or the victim; no gap (the set is an addition: the other plays stay Forge AI's).
     */
    static Answers revealAnswers(final Game g, final Player opp, final Player victim) {
        final Answers all = answers(g, opp, victim, null, false);
        final Set<Integer> ids = new HashSet<>();
        for (Card c : revealed(g, victim)) ids.add(c.getId());
        final List<SpellAbility> keep = new ArrayList<>();
        for (SpellAbility sa : all.list) {
            boolean hits = false;
            for (SpellAbility x = sa; x != null && !hits; x = x.getSubAbility()) {
                if (!x.usesTargeting() || x.getTargets() == null) continue;
                for (GameObject o : x.getTargets()) {
                    hits |= o instanceof Card c && ids.contains(c.getId()) || o == victim;
                }
            }
            if (hits) keep.add(sa);
        }
        return new Answers(keep, new java.util.TreeSet<>());
    }

    /** Whether {@code fa} is already in {@code rs} (same card, same ability, same targets). */
    static boolean sameAsAny(final SpellAbility fa, final List<SpellAbility> rs) {
        final String k = answerKey(fa);
        for (SpellAbility sa : rs) {
            if (answerKey(sa).equals(k)) return true;
        }
        return false;
    }

    static String answerKey(final SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        sb.append(sa.getHostCard() == null ? -1 : sa.getHostCard().getId()).append('|').append(sa.getDescription());
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && s.getTargets() != null) {
                for (GameObject o : s.getTargets()) sb.append('|').append(targetId(o));
            }
        }
        if (sa.hasSVar(MODES)) sb.append("|m:").append(sa.getSVar(MODES));
        return sb.toString();
    }

    static String targetId(final Object o) {
        if (o instanceof Card c) return "c" + c.getId();
        if (o instanceof Player p) return "p" + p.getId();
        if (o instanceof SpellAbility s) return "s" + (s.getHostCard() == null ? -1 : s.getHostCard().getId()) + "x"
                + Integer.toHexString(String.valueOf(s.getDescription()).hashCode());
        return String.valueOf(o);
    }

    /**
     * opp-belief-1104: a point's PUBLIC signature, the same in every world that looks the same to the opponent: the
     * point kind, turn and phase, the stack by (source name, text, side, targets), the attackers by name, the revealed
     * cards by name at a reveal or hold point. No card ids.
     */
    static String pointSig(final Game g, final Player victim, final String pk) {
        final StringBuilder sb = new StringBuilder();
        final int colon = pk.indexOf(':');
        sb.append(colon < 0 ? pk : pk.substring(0, colon)).append(':').append(g.getPhaseHandler().getTurn()).append(':')
                .append(g.getPhaseHandler().getPhase());
        if (pk.contains("|mana")) sb.append(":mana");
        for (SpellAbilityStackInstance si : g.getStack()) {
            final SpellAbility sa = si.getSpellAbility();
            sb.append("|st:").append(sa == null ? "?" : answerSig(sa, victim));
        }
        final forge.game.combat.Combat cb = g.getCombat();
        if (cb != null && pk.startsWith("atk")) {
            final List<String> a = new ArrayList<>();
            for (Card c : cb.getAttackers()) a.add(c.getName());
            Collections.sort(a);
            sb.append("|atk:").append(a);
        }
        if (pk.startsWith("rev")) {
            final List<String> a = new ArrayList<>();
            for (Card c : revealed(g, victim)) a.add(c.getName());
            Collections.sort(a);
            sb.append("|rev:").append(a);
        }
        return sb.toString();
    }

    /** opp-belief-1104: an ability's public signature: source name, text, side, targets by name and side, modes. */
    static String answerSig(final SpellAbility sa, final Player victim) {
        final StringBuilder sb = new StringBuilder();
        final Card h = sa.getHostCard();
        sb.append(h == null ? "?" : h.getName()).append('|').append(sa.getDescription()).append('|')
                .append(sa.getActivatingPlayer() == victim ? "v" : "o");
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && s.getTargets() != null) {
                for (GameObject o : s.getTargets()) sb.append('|').append(targetSig(o, victim));
            }
        }
        if (sa.hasSVar(MODES)) sb.append("|m:").append(sa.getSVar(MODES));
        return sb.toString();
    }

    static String targetSig(final Object o, final Player victim) {
        if (o instanceof Card c) return c.getName() + "@" + (c.getController() == victim ? "v" : "o")
                + (c.getZone() == null ? "" : c.getZone().getZoneType().name().substring(0, 2));
        if (o instanceof Player p) return p == victim ? "pv" : "po";
        if (o instanceof SpellAbility s) return "s" + (s.getHostCard() == null ? "?" : s.getHostCard().getName()) + "x"
                + Integer.toHexString(String.valueOf(s.getDescription()).hashCode());
        return String.valueOf(o);
    }

    /**
     * A response point at this priority, or null: one of the victim's spells, activated abilities or triggers on top of
     * the stack (not a mana ability), or the victim's declare-attackers step with attackers declared.
     */
    public static String pointKey(final Game g, final Player victim) {
        if (g.getStack().isEmpty()) {
            final PhaseHandler ph = g.getPhaseHandler();
            final forge.game.combat.Combat cb = g.getCombat();
            return ph.is(PhaseType.COMBAT_DECLARE_ATTACKERS) && ph.isPlayerTurn(victim) && cb != null
                    && !cb.getAttackers().isEmpty() ? "atk:" + ph.getTurn() : null;
        }
        final SpellAbilityStackInstance si = g.getStack().peek();
        final SpellAbility top = si.getSpellAbility();
        return top == null || top.getActivatingPlayer() != victim || top.isManaAbility() ? null : "s:" + si.getId();
    }

    static int impact(final SpellAbility top) {
        if (top == null) return 0;
        try {
            return top.getHostCard() == null ? 0 : top.getHostCard().getCMC();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * The point's state (review S2-3, a full key): the position fingerprint; every card by id with its zone, tapped,
     * sickness, counters, damage, attachment and combat role; the stack with its targets and modes; spells cast this
     * turn, land drops and mana pools by colour per player; the phase; and the random stream's state.
     */
    static String stateKey(final Game g) {
        final StringBuilder sb = new StringBuilder(LookaheadSearch.fingerprint(g));
        final forge.game.combat.Combat cb = g.getCombat();
        for (Player p : g.getPlayers()) {
            sb.append("|P").append(p.getId()).append(":lands").append(p.getLandsPlayedThisTurn()).append(":pool");
            for (byte c : forge.card.MagicColor.WUBRGC) sb.append(p.getManaPool().getAmountOfColor(c)).append(',');
            for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Library)) {
                sb.append("|").append(z.name().charAt(0));
                for (Card c : p.getCardsIn(z)) {
                    sb.append(c.getId());
                    if (z == ZoneType.Battlefield) {
                        sb.append(c.isTapped() ? "T" : "U").append(c.isSick() ? "s" : "").append(c.getCounters())
                                .append("d").append(c.getDamage());
                        if (c.getEntityAttachedTo() != null) sb.append("@").append(targetId(c.getEntityAttachedTo()));
                        if (cb != null && cb.isAttacking(c)) sb.append("A").append(targetId(cb.getDefenderByAttacker(c)));
                        if (cb != null && cb.isBlocking(c)) sb.append("B");
                    }
                    sb.append(',');
                }
            }
        }
        for (SpellAbilityStackInstance si : g.getStack()) {
            final SpellAbility sa = si.getSpellAbility();
            sb.append("|st:").append(sa == null ? "?" : answerKey(sa));
        }
        sb.append("|cast").append(g.getStack().getSpellsCastThisTurn().size());
        sb.append("|ph").append(g.getPhaseHandler().getPhase()).append(g.getPhaseHandler().getPlayerTurn() == null ? ""
                : g.getPhaseHandler().getPlayerTurn().getId());
        sb.append("|rng").append(PlayoutKeys.rngState());
        return PlayoutKeys.digest(sb);
    }

    /**
     * The budget hint at a point: 2 when the opponent holds two or more cards behind the set's answers and the mana for
     * the two cheapest, else min(1, answers).
     */
    static int budgetHint(final Player opp, final List<SpellAbility> rs) {
        if (rs.isEmpty()) return 0;
        final Map<String, Integer> cost = new TreeMap<>();
        for (SpellAbility sa : rs) {
            final String n = sa.getHostCard().getName();
            int c;
            try {
                c = sa.getPayCosts() == null ? 0 : sa.getPayCosts().getTotalMana().getCMC();
            } catch (RuntimeException e) {
                c = 0;
            }
            cost.merge(n, c, Math::min);
        }
        final List<Integer> each = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cost.entrySet()) {
            int copies = 0;
            for (ZoneType z : List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile)) {
                for (Card c : opp.getCardsIn(z)) {
                    if (c.getName().equals(e.getKey())) copies++;
                }
            }
            for (int i = 0; i < Math.max(1, copies); i++) each.add(e.getValue());
        }
        if (each.size() < 2) return 1;
        Collections.sort(each);
        int mana;
        try {
            mana = ComputerUtilMana.getAvailableManaEstimate(opp, false);
        } catch (RuntimeException e) {
            mana = 0;
        }
        return each.get(0) + each.get(1) <= mana ? 2 : 1;
    }

    // ------------------------------------------------------------------------------------------------ the response set

    /** Categories, in exploration order. */
    static final int COUNTER = 0, REMOVAL = 1, DISCARD = 2, BLOCKER = 3, SHIELD = 4, OTHER = 5;

    /**
     * The opponent's real responses to {@code top} (null: an attack point), collapsed and in a fixed order (category,
     * card name, target class), each castable or activatable now and payable. Sources: their hand, every permanent
     * (tapped too), graveyard and exile. Targets: our top stack object, our and their permanents by name, us, themselves.
     */
    public static List<SpellAbility> responses(final Game g, final Player opp, final Player victim, final SpellAbility top) {
        return answers(g, opp, victim, top).list;
    }

    /** coverage-fix-1102 (diagnostics only): a point's gap cards and answer names, for the meter. */
    static Point diag(final Point p, final Answers as, final List<SpellAbility> rs) {
        p.gapCards = as.gapCards;
        final List<String> names = new ArrayList<>();
        for (SpellAbility sa : rs) {
            final String n = sa.getHostCard() == null ? "?" : sa.getHostCard().getName();
            if (!names.contains(n)) names.add(n);
        }
        p.answerNames = names;
        return p;
    }

    /** A point's response set and why it is not complete (review #148, fail closed: each reason is a cap hit). */
    public static final class Answers {
        public final List<SpellAbility> list;
        public final java.util.TreeSet<String> gaps;
        /** coverage-fix-1102 (diagnostics only): the source cards of each gap reason. */
        public final java.util.TreeMap<String, java.util.TreeSet<String>> gapCards = new java.util.TreeMap<>();
        /** Review #169 S1-1: each mana-ability response's forced cost choices (card ids per choosing cost part). */
        public final java.util.IdentityHashMap<SpellAbility, List<List<Integer>>> forced = new java.util.IdentityHashMap<>();

        Answers(final List<SpellAbility> list, final java.util.TreeSet<String> gaps) {
            this.list = list;
            this.gaps = gaps;
        }

        String gap() {
            return gaps.isEmpty() ? null : String.join("+", gaps);
        }
    }

    /** {@link #responses} with the reasons the set is not complete (answers OppMin cannot enumerate fully). */
    public static Answers answers(final Game g, final Player opp, final Player victim, final SpellAbility top) {
        return answers(g, opp, victim, top, false);
    }

    /**
     * Review #169 re-check S1: whether the mana an ability produces is a choice (any colour, a combination, a chosen or
     * reflected colour, more than one option); fail closed: an unreadable ability counts as a choice.
     */
    static boolean producedChoice(final SpellAbility sa) {
        try {
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                if (s.getApi() == ApiType.ManaReflected) return true;
                if (s.getApi() != ApiType.Mana) continue;
                final String p = s.getParamOrDefault("Produced", "");
                if (p.isEmpty() || p.contains("Any") || p.contains("Combo") || p.contains("Chosen") || p.contains(" ")
                        || s.hasParam("Express") || s.hasParam("ReplaceType")) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Review #169 S1-1: the most cost-choice combinations one mana ability may have (more: a cap, never one picked). */
    static final int MANA_CHOICE_CAP = 24;

    /**
     * Review #169 S1-1: the legal choices of a mana ability's choosing cost parts, one list of card ids per part (in cost
     * order), as every combination; an empty combination when nothing is chosen (mana, tapping or sacrificing itself,
     * life). Null when a part's choice cannot be enumerated (discard, exile, return, another card's counters, an X
     * amount, an unreadable part) or the combinations exceed {@link #MANA_CHOICE_CAP}: the caller fails closed.
     */
    static List<List<List<Integer>>> costChoices(final SpellAbility sa, final Player opp, final Card source) {
        List<List<List<Integer>>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        if (sa.getPayCosts() == null) return combos;
        try {
            for (forge.game.cost.CostPart part : sa.getPayCosts().getCostParts()) {
                if (part instanceof forge.game.cost.CostPartMana || part instanceof forge.game.cost.CostTap
                        || part instanceof forge.game.cost.CostPayLife || part instanceof forge.game.cost.CostUntap) {
                    continue;
                }
                if (part.payCostFromSource()) continue; // sacrifice, exile or counters of itself: no choice
                final boolean sac = part instanceof forge.game.cost.CostSacrifice;
                final boolean tap = part instanceof forge.game.cost.CostTapType;
                if (!sac && !tap) return null;
                final Integer n = part.convertAmount();
                if (n == null || n < 1) return null;
                List<Card> cands = forge.game.card.CardLists.getValidCards(opp.getCardsIn(ZoneType.Battlefield),
                        part.getType().split(";"), opp, source, sa);
                if (tap) {
                    cands = forge.game.card.CardLists.filter(cands, x -> !x.isTapped() && x != source);
                }
                final List<List<Integer>> picks = new ArrayList<>();
                pick(cands, n, 0, new ArrayList<>(), picks);
                if (picks.isEmpty()) return null;
                final List<List<List<Integer>>> next = new ArrayList<>();
                for (List<List<Integer>> base : combos) {
                    for (List<Integer> p : picks) {
                        final List<List<Integer>> x = new ArrayList<>(base);
                        x.add(p);
                        next.add(x);
                        if (next.size() > MANA_CHOICE_CAP) return null;
                    }
                }
                combos = next;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return combos;
    }

    private static void pick(final List<Card> cands, final int n, final int from, final List<Integer> cur,
            final List<List<Integer>> out) {
        if (out.size() > MANA_CHOICE_CAP) return;
        if (cur.size() == n) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (int i = from; i < cands.size(); i++) {
            cur.add(cands.get(i).getId());
            pick(cands, n, i + 1, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    /** Review #169 S1-1: Forge AI's payment, with each choosing cost part's cards forced to the response's own choice. */
    static final class ForcedCost extends forge.ai.AiCostDecision {
        private final java.util.Iterator<List<Integer>> it;

        ForcedCost(final Player ai, final SpellAbility sa, final List<List<Integer>> forced) {
            super(ai, sa, false);
            this.it = forced == null ? java.util.Collections.<List<Integer>>emptyIterator() : forced.iterator();
        }

        @Override
        public forge.game.cost.PaymentDecision review(final forge.game.cost.CostPart part,
                final forge.game.cost.PaymentDecision d) {
            if ((part instanceof forge.game.cost.CostSacrifice || part instanceof forge.game.cost.CostTapType)
                    && !part.payCostFromSource() && it.hasNext()) {
                final List<Card> cards = new ArrayList<>();
                for (int id : it.next()) {
                    final Card x = player.getGame().findById(id);
                    if (x != null) cards.add(x);
                }
                return forge.game.cost.PaymentDecision.card(cards);
            }
            return d;
        }
    }

    /** Review #169 S3: what a mana activation changes (life, pool, permanents, their tapped state and counters, zones). */
    static String manaState(final Game g, final Player opp) {
        final StringBuilder sb = new StringBuilder();
        sb.append(opp.getLife()).append('|').append(opp.getManaPool().totalMana()).append('|');
        for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
            sb.append(c.getId()).append(c.isTapped() ? 't' : 'u').append(c.getCounters()).append(',');
        }
        sb.append('|').append(opp.getCardsIn(ZoneType.Graveyard).size()).append('|').append(g.getStack().size());
        return sb.toString();
    }

    /** covfix-a-1102: whether a mana trigger (taps for mana, mana added) is on either player's battlefield. */
    static boolean manaTriggers(final Player opp, final Player victim) {
        for (Player p : List.of(opp, victim)) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                for (forge.game.trigger.Trigger t : c.getTriggers()) {
                    final forge.game.trigger.TriggerType m = t.getMode();
                    if (m == forge.game.trigger.TriggerType.TapsForMana || m == forge.game.trigger.TriggerType.ManaAdded
                            || m == forge.game.trigger.TriggerType.Taps) {
                        return true; // review #169 S3: Taps triggers too (fail closed)
                    }
                }
                for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                    if (re.getMode() == forge.game.replacement.ReplacementType.ProduceMana) {
                        return true; // review #169 S3: a mana replacement (Mana Reflection): no mana ability is pure
                    }
                }
            }
        }
        return false;
    }

    /** As above; {@code manaSearch} (covfix-a-1102): mana abilities with a non-mana part are answers, not gaps. */
    public static Answers answers(final Game g, final Player opp, final Player victim, final SpellAbility top,
            final boolean manaSearch) {
        final boolean manaTrig = manaSearch && manaTriggers(opp, victim);
        final Answers out0 = new Answers(new ArrayList<>(), new java.util.TreeSet<>());
        final java.util.TreeSet<String> allGaps = new java.util.TreeSet<>();
        final java.util.TreeMap<String, java.util.TreeSet<String>> gapCards = new java.util.TreeMap<>();
        final TreeMap<String, SpellAbility> byKey = new TreeMap<>();
        final List<Card> sources = new ArrayList<>();
        for (ZoneType z : List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
                ZoneType.Command)) {
            sources.addAll(opp.getCardsIn(z));
        }
        // review #148 re-check 6 (S1-b): cards they may play from elsewhere: our exile (Thief of Sanity), both library
        // tops (Future Sight, Bolas's Citadel), our command zone; only abilities they can play are kept below
        sources.addAll(victim.getCardsIn(ZoneType.Exile));
        sources.addAll(victim.getCardsIn(ZoneType.Command));
        // review #148 re-check 7 (S1-2): "any player may activate" abilities on our permanents (Ribbon Snake) and on our
        // stack objects' host cards (Lightning Storm)
        sources.addAll(victim.getCardsIn(ZoneType.Battlefield));
        // review #148 re-check 8 (S2): our hand and graveyard, behind the same castable gate (their effects that cast or
        // activate our cards from there; in this build the engine's owner check in checkZoneRestrictions blocks casting
        // from our hand, so Sen Triplets is not a real source here, but a cross-zone effect that works will be read)
        sources.addAll(victim.getCardsIn(ZoneType.Hand));
        sources.addAll(victim.getCardsIn(ZoneType.Graveyard));
        for (SpellAbilityStackInstance si : g.getStack()) {
            final SpellAbility x = si.getSpellAbility();
            if (x != null && x.getHostCard() != null && !sources.contains(x.getHostCard())) sources.add(x.getHostCard());
        }
        for (Player who : List.of(opp, victim)) {
            final List<Card> lib = new ArrayList<>(who.getCardsIn(ZoneType.Library));
            if (!lib.isEmpty()) sources.add(lib.get(0));
        }
        sources.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
        final boolean attack = top == null;
        for (Card c : sources) {
            // coverage-fix-1102: each card's gaps apart (the same reasons, merged below), to name the cards behind them
            final java.util.TreeSet<String> gaps = new java.util.TreeSet<>();
            try {
            final boolean foreign = c.getController() != opp || c.isInZone(ZoneType.Library) || c.isInZone(ZoneType.Command);
            final List<SpellAbility> abilities;
            try {
                abilities = c.getAllPossibleAbilities(opp, true);
            } catch (RuntimeException e) {
                gaps.add("unreadable");
                continue;
            }
            for (SpellAbility sa0 : abilities) {
                if (sa0.isLandAbility()) continue;
                if (foreign) {
                    // a card of another zone or player: an answer only if they may play or activate it now (a MayPlay
                    // effect, an "any player may activate" ability)
                    sa0.setActivatingPlayer(opp);
                    if (!castable(sa0, opp, gaps)) continue;
                }
                if (sa0.isManaAbility() && manaSearch) {
                    // covfix-a-1102: a response like any other, one per source name and cost (copies of one card are
                    // one outcome); a pure mana ability with no mana trigger in play is the "no response" branch
                    if (manaTrig && producedChoice(sa0) && castable(sa0, opp, gaps)) {
                        // review #169 re-check S1: with a mana trigger, replacement or static out, the colour the
                        // activation produces decides what is castable next; Forge AI would pick it. Fail closed until
                        // layer 2's DecisionPoint CHOICE kind enumerates the colours
                        gaps.add("mana-color-choice");
                        continue;
                    }
                    if ((manaAbilityWithCost(sa0) || manaTrig) && castable(sa0, opp, gaps)) {
                        // review #169 S1-1: one response per legal cost choice (the permanent sacrificed or tapped);
                        // S2: keyed by the source card's id (its own tap or sacrifice is the choice of that card)
                        final List<List<List<Integer>>> choices = costChoices(sa0, opp, c);
                        if (choices == null) {
                            gaps.add("mana-cost-choice"); // a choice OppMin cannot enumerate, or over the cap
                            continue;
                        }
                        final String mcost = sa0.getPayCosts() == null ? "-" : sa0.getPayCosts().toSimpleString();
                        for (List<List<Integer>> ch : choices) {
                            final SpellAbility cs = sa0.copy(opp);
                            cs.setActivatingPlayer(opp);
                            final String k = OTHER + "|mana|" + c.getName() + "#" + c.getId() + "|" + mcost + "|"
                                    + sa0.getDescription() + "|" + ch;
                            if (byKey.putIfAbsent(k, cs) == null) out0.forced.put(cs, ch);
                        }
                    }
                    continue;
                }
                if (sa0.isManaAbility()) {
                    // review #148 re-check 4: a mana ability with a non-mana cost (a sac outlet) answers a line; it is
                    // not played by OppMin, so a castable one is a gap
                    if (manaAbilityWithCost(sa0) && !selfDamageOnly(sa0, opp) && castable(sa0, opp, gaps)) {
                        gaps.add("mana-ability");
                    }
                    continue;
                }
                if (sa0.isSpell() && c.isInZone(ZoneType.Battlefield)) continue;
                // review S3: alternative costs are separate answers (Force of Will hard-cast vs pitched)
                final String cost = sa0.getPayCosts() == null ? "-" : sa0.getPayCosts().toSimpleString();
                if (sa0.getApi() == ApiType.Charm) {
                    charmResponses(byKey, gaps, c, sa0, cost, top, victim, opp, attack);
                    continue;
                }
                final List<Object> targets = new ArrayList<>();
                final List<String> keys = new ArrayList<>();
                try {
                    targetsOf(sa0, top, victim, opp, targets, keys);
                } catch (RuntimeException e) {
                    gaps.add("unreadable");
                    continue;
                }
                if (sa0.usesTargeting() && (sa0.getMinTargets() > 1 || ownStackTarget(sa0, opp))) {
                    // review #148 re-check 6 (S1-a, S2): more than one target at once (Ghostly Flicker), or their own
                    // stack object (Reverberate on their own removal), is not enumerated: a gap when castable
                    if (castable(sa0, opp, gaps)) gaps.add(sa0.getMinTargets() > 1 ? "multi-target" : "own-stack-target");
                    if (sa0.getMinTargets() > 1) continue;
                }
                if (sa0.usesTargeting() && sa0.getMinTargets() == 0) {
                    // review #148 re-check 9: an optional target (TargetMin 0) may be cast with none, its untargeted
                    // rider still resolving (Turn the Earth's life gain): the zero-target answer is enumerated too
                    targets.add(null);
                    keys.add("none");
                }
                if (targets.isEmpty() && sa0.usesTargeting()) {
                    // a targeted answer with no enumerated target: a gap when it has a legal one and is castable
                    boolean legal;
                    try {
                        sa0.setActivatingPlayer(opp);
                        legal = sa0.getTargetRestrictions() == null || sa0.getTargetRestrictions().getNumCandidates(sa0) > 0;
                    } catch (RuntimeException e) {
                        legal = true;
                    }
                    if (legal && castable(sa0, opp, gaps)) gaps.add("no-target-enum");
                    continue;
                }
                for (int ti = 0; ti < targets.size(); ti++) {
                    final Object t = targets.get(ti);
                    final int cat;
                    try {
                        cat = category(sa0, t, top, victim, opp, attack);
                    } catch (RuntimeException e) {
                        gaps.add("unreadable");
                        continue;
                    }
                    final SpellAbility cs = sa0.copy(opp);
                    cs.setActivatingPlayer(opp);
                    if (t == null && sa0.usesTargeting()) cs.resetTargets(); // the zero-target answer
                    if (t != null) {
                        cs.resetTargets();
                        if (t instanceof SpellAbility ts) cs.getTargets().add(ts);
                        else cs.getTargets().add((GameObject) t);
                        if (!cs.isTargetNumberValid()) {
                            if (castable(sa0, opp, gaps)) gaps.add("multi-target"); // never a silent drop
                            continue;
                        }
                    }
                    if (!castable(cs, opp, gaps)) continue;
                    final String gap = gapOf(cs);
                    if (gap != null) gaps.add(gap);
                    // review #148 re-check 8: keyed by the source card's id, so two copies in different states (a 1/1 and a 3/3
                    // Spikeshot Elder) are two answers
                    byKey.putIfAbsent(cat + "|" + c.getName() + "#" + c.getId() + "|" + sa0.getApi() + "|" + cost + "|"
                            + keys.get(ti), cs);
                }
            }
            } finally {
                allGaps.addAll(gaps);
                for (String r : gaps) gapCards.computeIfAbsent(r, k -> new java.util.TreeSet<>()).add(c.getName());
            }
        }
        final Answers out = new Answers(new ArrayList<>(byKey.values()), allGaps);
        out.gapCards.putAll(gapCards);
        out.forced.putAll(out0.forced);
        return out;
    }

    /** Whether {@code sa} can be played and paid now; an exception is a gap ("unreadable"), not a silent drop. */
    static boolean castable(final SpellAbility sa, final Player opp, final java.util.Set<String> gaps) {
        try {
            if (TEST_THROW != null && TEST_THROW.test(sa)) throw new IllegalStateException("test: canPlay throws");
            return sa.canPlay() && ComputerUtilCost.canPayCost(sa, opp, false);
        } catch (RuntimeException e) {
            gaps.add("unreadable");
            return false;
        }
    }

    /** Tests only: make {@link #castable} throw for the answers it matches (a synthetic unreadable answer). */
    static java.util.function.Predicate<SpellAbility> TEST_THROW;

    /**
     * Review #148 (fail closed): why OppMin cannot enumerate this castable answer's options completely, or null. Unknown
     * effect (no API), a choice made at resolution (GenericChoice), an X cost, more than one target or divided damage, a
     * target zone the enumeration does not cover (hand, library, exile, command), a target in a sub-ability.
     */
    static String gapOf(final SpellAbility sa) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
            final ApiType api = x.getApi();
            if (api == null) return "unknown-api";
            if (api == ApiType.GenericChoice) return "resolution-choice";
            if (x.usesTargeting()) {
                if (x != sa && !(x instanceof forge.game.spellability.AbilitySub as && as.getParent() != null
                        && as.getParent().getApi() == ApiType.Charm)) {
                    return "sub-target";
                }
                if (x.isDividedAsYouChoose() || x.getMaxTargets() > 1) return "multi-target";
                final forge.game.spellability.TargetRestrictions tr = x.getTargetRestrictions();
                if (tr != null && tr.getZone() != null) {
                    for (ZoneType z : tr.getZone()) {
                        if (z != ZoneType.Battlefield && z != ZoneType.Graveyard && z != ZoneType.Stack) return "target-zone";
                    }
                }
            }
        }
        try {
            if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null
                    && sa.getPayCosts().getTotalMana().countX() > 0) {
                return "x-cost";
            }
        } catch (RuntimeException e) {
            return "cost-unreadable";
        }
        return null;
    }

    /**
     * {@code sa}'s target candidates: our top stack object; both players' permanents by name (only names it can target:
     * review S3); both graveyards' cards by name (review S1: Ooze, Deathrite, Relic); both players. No target: null.
     */
    static void targetsOf(final SpellAbility sa, final SpellAbility top, final Player victim, final Player opp,
            final List<Object> targets, final List<String> keys) {
        if (!sa.usesTargeting()) {
            targets.add(null);
            keys.add("-");
            return;
        }
        if (top != null && sa.canTargetSpellAbility(top)) {
            targets.add(top);
            keys.add("stack");
        }
        // review #148 (fail closed): our other objects on the stack too (a counter on the spell below the top)
        // review #148 re-check 7 (S2-1): keyed by stack entry, claimed only once targetable
        for (SpellAbilityStackInstance si : victim.getGame().getStack()) {
            final SpellAbility x = si.getSpellAbility();
            if (x == null || x == top || x.getActivatingPlayer() != victim || x.getHostCard() == null) continue;
            if (sa.canTargetSpellAbility(x)) {
                targets.add(x);
                keys.add("stack:" + si.getId());
            }
        }
        for (Player who : List.of(victim, opp)) {
            for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Graveyard)) {
                // review #148 re-check 6 (S1-c): collapsed by name and state, so a Shock on the unequipped Bears does
                // not hide the Bears carrying the Bonesplitter
                final Set<String> classes = new HashSet<>();
                for (Card v : who.getCardsIn(z)) {
                    // re-check 7 ruling (S1-3): a permanent is keyed by its card id (granted keywords, sickness, token,
                    // shields: any state can change the outcome); a graveyard card by name
                    final String cls = z == ZoneType.Battlefield ? v.getName() + "#" + v.getId() : v.getName();
                    if (!classes.contains(cls) && sa.canTarget(v)) {
                        classes.add(cls);
                        targets.add(v);
                        keys.add((who == opp ? "own" : "card") + (z == ZoneType.Graveyard ? "-gy:" : ":") + cls);
                    }
                }
            }
            if (sa.canTarget(who)) {
                targets.add(who);
                keys.add(who == opp ? "self" : "player");
            }
        }
    }

    /** Whether {@code sa} can target one of its own controller's objects on the stack (Reverberate, Misdirection). */
    static boolean ownStackTarget(final SpellAbility sa, final Player opp) {
        try {
            for (SpellAbilityStackInstance si : opp.getGame().getStack()) {
                final SpellAbility x = si.getSpellAbility();
                if (x != null && x.getActivatingPlayer() == opp && sa.canTargetSpellAbility(x)) return true;
            }
        } catch (RuntimeException e) {
            return true;
        }
        return false;
    }

    /** The SVar a modal response carries: its forced modes (CharmOrder values, comma separated). */
    public static final String MODES = "OppMinModes";

    /**
     * Review S1: a modal spell's responses, one per mode combination (CharmNum of the possible modes, repeats when the
     * card allows them; at most 24 combinations), each with its modes forced ({@link #forcedModes}).
     */
    static void charmResponses(final TreeMap<String, SpellAbility> byKey, final java.util.TreeSet<String> gaps,
            final Card c, final SpellAbility sa0, final String cost, final SpellAbility top, final Player victim,
            final Player opp, final boolean attack) {
        final List<forge.game.spellability.AbilitySub> modes;
        final int num, min;
        try {
            sa0.setActivatingPlayer(opp);
            modes = forge.game.ability.effects.CharmEffect.makePossibleOptions(sa0);
            num = Math.max(1, AbilityUtils.calculateAmount(c, sa0.getParamOrDefault("CharmNum", "1"), sa0));
            min = sa0.hasParam("MinCharmNum") ? AbilityUtils.calculateAmount(c, sa0.getParam("MinCharmNum"), sa0) : num;
        } catch (RuntimeException e) {
            gaps.add("charm-unreadable");
            return;
        }
        if (modes.isEmpty()) return;
        // the extra cost of each mode past the first (Escalate); another per-mode cost keyword is not modelled
        int extra = 0;
        for (forge.game.keyword.KeywordInterface kw : c.getKeywords()) {
            final String k = kw.getOriginal();
            if (k.startsWith("Escalate")) {
                try {
                    extra = new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser(k.substring(k.indexOf(':') + 1)))
                            .getCMC();
                } catch (RuntimeException e) {
                    gaps.add("charm-cost");
                    return;
                }
            } else if (k.startsWith("Spree") || k.startsWith("Tiered")) {
                gaps.add("charm-cost");
            }
        }
        int mana;
        int base;
        try {
            mana = ComputerUtilMana.getAvailableManaEstimate(opp, false);
            base = sa0.getPayCosts() == null || sa0.getPayCosts().getTotalMana() == null ? 0
                    : sa0.getPayCosts().getTotalMana().getCMC();
        } catch (RuntimeException e) {
            mana = Integer.MAX_VALUE;
            base = 0;
        }
        final boolean repeat = sa0.hasParam("CanRepeatModes");
        final int hi = repeat ? num : Math.min(num, modes.size());
        final List<int[]> combos = new ArrayList<>();
        for (int k = Math.max(1, min); k <= hi; k++) {
            combo(modes.size(), k, repeat, 0, new int[k], 0, combos, CHARM_CAP + 1);
        }
        if (combos.size() > CHARM_CAP) gaps.add("charm-overflow");
        int added = 0;
        boolean real = false;
        for (int[] cmb : combos) {
            if (added > CHARM_CAP) break;
            if (extra > 0 && base + (cmb.length - 1) * extra > mana) continue; // not payable with its escalate cost
            // every mode's distinct target classes (collapsed by name), every product of them
            final List<List<Object>> optT = new ArrayList<>();
            final List<List<Integer>> optC = new ArrayList<>();
            final List<String> modeGaps = new ArrayList<>();
            boolean ok = true;
            for (int m : cmb) {
                final forge.game.spellability.AbilitySub sub = modes.get(m);
                sub.setActivatingPlayer(opp);
                final String mg = gapOf(sub);
                if (mg != null) modeGaps.add(mg);
                if (sub.usesTargeting() && sub.getMinTargets() > 1) modeGaps.add("multi-target");
                if (sub.usesTargeting() && ownStackTarget(sub, opp)) modeGaps.add("own-stack-target");
                final List<Object> ts = new ArrayList<>();
                targetsOf(sub, top, victim, opp, ts, new ArrayList<>());
                final List<Object> oT = new ArrayList<>();
                final List<Integer> oC = new ArrayList<>();
                for (Object t : ts) {
                    int x;
                    try {
                        x = category(sub, t, top, victim, opp, attack);
                    } catch (RuntimeException e) {
                        gaps.add("unreadable");
                        x = -1;
                    }
                    if (x >= 0 || !sub.usesTargeting()) {
                        oT.add(t);
                        oC.add(x);
                    }
                }
                if (oT.isEmpty()) {
                    if (ts.isEmpty() && sub.usesTargeting() && sub.getMinTargets() > 0) {
                        // review #148 re-check 7 (S1-1): a target we did not enumerate (their own stack object, a hand,
                        // library or exile card) is a gap, not a mode that cannot be cast
                        boolean legal;
                        try {
                            legal = sub.getTargetRestrictions() == null || sub.getTargetRestrictions().getNumCandidates(sub) > 0;
                        } catch (RuntimeException e) {
                            legal = true;
                        }
                        if (legal || ownStackTarget(sub, opp)) modeGaps.add(ownStackTarget(sub, opp) ? "own-stack-target"
                                : "no-target-enum");
                        ok = false;
                        break;
                    }
                    // a mode with no real target: its first legal one, or none (TargetMin 0): a bonus mode
                    oT.add(ts.isEmpty() ? null : ts.get(0));
                    oC.add(-1);
                }
                optT.add(oT);
                optC.add(oC);
            }
            // modeGaps are merged on every path once the card itself is castable
            if (!modeGaps.isEmpty() && castable(sa0, opp, gaps)) gaps.addAll(modeGaps);
            if (!ok) continue;
            final int[] pick = new int[cmb.length];
            while (added <= CHARM_CAP) {
                int cat = -1;
                final StringBuilder key = new StringBuilder();
                for (int q = 0; q < cmb.length; q++) {
                    final int x = optC.get(q).get(pick[q]);
                    if (x >= 0 && (cat < 0 || x < cat)) cat = x;
                    final Object t = optT.get(q).get(pick[q]);
                    key.append(modes.get(cmb[q]).getSVar("CharmOrder")).append('@').append(t == null ? "-" : targetId(t))
                            .append(',');
                }
                if (cat < 0) cat = OTHER; // review #148 S2: modes with no legal target still make an answer
                {
                    final SpellAbility cs = sa0.copy(opp);
                    cs.setActivatingPlayer(opp);
                    cs.setSVar(MODES, key.toString());
                    final boolean play = castable(cs, opp, gaps);
                    if (play) {
                        real = true;
                        if (byKey.putIfAbsent(cat + "|" + c.getName() + "#" + c.getId() + "|Charm|" + cost + "|modes:" + key, cs) == null) {
                            added++;
                        }
                    }
                }
                int q = cmb.length - 1;
                while (q >= 0 && ++pick[q] >= optT.get(q).size()) pick[q--] = 0;
                if (q < 0) break;
            }
        }
        if (added > CHARM_CAP) gaps.add("charm-overflow");
        // repeated modes share one sub (a forced "A,B" would play as "B,B"): fail closed on a real repeatable answer
        if (repeat && real) gaps.add("charm-repeat");
    }

    /** At most this many answers per modal card (one more than the default per-point cap would already be a cap hit). */
    static final int CHARM_CAP = 24;

    static void combo(final int n, final int k, final boolean repeat, final int from, final int[] cur, final int depth,
            final List<int[]> out, final int cap) {
        if (out.size() >= cap) return;
        if (depth == k) {
            out.add(cur.clone());
            return;
        }
        for (int i = from; i < n; i++) {
            cur[depth] = i;
            combo(n, k, repeat, repeat ? i : i + 1, cur, depth + 1, out, cap);
        }
    }

    /**
     * The forced modes of a modal response ({@link #MODES}), picked from {@code possible} by CharmOrder (repeats
     * included), or null: not one of ours, or a mode no longer possible (the controller then chooses).
     */
    public static List<forge.game.spellability.AbilitySub> forcedModes(final SpellAbility sa,
            final List<forge.game.spellability.AbilitySub> possible) {
        if (sa == null || !sa.hasSVar(MODES) || possible == null) return null;
        final List<forge.game.spellability.AbilitySub> out = new ArrayList<>();
        final Game g = sa.getHostCard() == null ? null : sa.getHostCard().getGame();
        for (String o : sa.getSVar(MODES).split(",")) {
            if (o.isEmpty()) continue;
            final String[] ot = o.split("@", 2);
            forge.game.spellability.AbilitySub hit = null;
            for (forge.game.spellability.AbilitySub sub : possible) {
                if (ot[0].equals(sub.getSVar("CharmOrder"))) hit = sub;
            }
            if (hit == null) return null;
            if (hit.usesTargeting() && ot.length > 1 && !"-".equals(ot[1]) && g != null) {
                final GameObject t = findTarget(g, ot[1]);
                if (t == null) return null;
                // a repeated mode gets its own copy (chainAbilities clones each chosen sub)
                hit.resetTargets();
                hit.getTargets().add(t);
            }
            out.add(hit);
        }
        return out.isEmpty() ? null : out;
    }

    /** The object {@link #targetId} names in {@code g} (ids survive copies), or null. */
    public static GameObject findTarget(final Game g, final String t) {
        if (t.startsWith("c")) return g.findById(Integer.parseInt(t.substring(1)));
        if (t.startsWith("p")) {
            for (Player pl : g.getPlayers()) if (("p" + pl.getId()).equals(t)) return pl;
            return null;
        }
        if (t.startsWith("s")) {
            for (SpellAbilityStackInstance si : g.getStack()) {
                final SpellAbility x = si.getSpellAbility();
                if (x != null && targetId(x).equals(t)) return x;
            }
        }
        return null;
    }

    /**
     * The response's category (its exploration order: counters first). Every answer is a response (re-check 5 ruling);
     * {@code t}: its target (a stack object, a card, a player or null).
     */
    static int category(final SpellAbility sa, final Object t, final SpellAbility top, final Player victim,
            final Player opp, final boolean attack) {
        final ApiType api = sa.getApi();
        if (t instanceof SpellAbility) {
            return api == ApiType.Counter ? COUNTER : OTHER; // a counter, a stack bounce, a redirect: all answer it
        }
        if (t instanceof Card c) {
            if (c.getController() == victim || c.isInZone(ZoneType.Graveyard)) return REMOVAL;
            return SHIELD; // their own permanent: a pump, protection, a sacrifice, anything
        }
        if (t instanceof Player p) {
            if (p == victim) return api == ApiType.Discard ? DISCARD : REMOVAL;
            return SHIELD;
        }
        if (sa.isSpell() && sa.getHostCard() != null && sa.getHostCard().isCreature()) return BLOCKER;
        if (api == ApiType.Animate || api == ApiType.Token || api == ApiType.CopyPermanent || api == ApiType.Dig) {
            return BLOCKER;
        }
        if (api == ApiType.Counter) return COUNTER;
        return OTHER;
    }

    /**
     * Whether a mana ability is an answer (review #148 re-check 4 and 6): its cost has a part beyond mana and tapping
     * itself (a sacrifice outlet such as Ashnod's Altar, a discard, life, tapping another permanent), or its effect has
     * a rider beyond adding mana (Pristine Talisman gains life, Selvala). OppMin does not play mana abilities, so a
     * castable one is a gap. Only pure mana abilities are non-answers.
     */
    static boolean manaAbilityWithCost(final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() != ApiType.Mana && s.getApi() != ApiType.ManaReflected) return true; // a rider
        }
        if (sa.getPayCosts() == null) return false;
        for (forge.game.cost.CostPart part : sa.getPayCosts().getCostParts()) {
            if (part instanceof forge.game.cost.CostPartMana || part instanceof forge.game.cost.CostTap) continue;
            return true;
        }
        return false;
    }

    /**
     * Ruling 2026-11-01 (#148 follow-up): a mana ability whose cost is mana and tapping itself and whose only rider is
     * damage to, or life loss by, its own controller (Ancient Tomb, the Talismans, painlands) is not an answer, but only
     * when nothing that player controls or may play references their life total or damage or life loss dealt to them
     * (Death's Shadow, "whenever you lose life", "N or less life"). An undecidable check keeps the gap.
     */
    static boolean selfDamageOnly(final SpellAbility sa, final Player opp) {
        if (sa.getPayCosts() != null) {
            for (forge.game.cost.CostPart part : sa.getPayCosts().getCostParts()) {
                if (!(part instanceof forge.game.cost.CostPartMana || part instanceof forge.game.cost.CostTap)) return false;
            }
        }
        boolean rider = false;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            final ApiType api = s.getApi();
            if (api == ApiType.Mana || api == ApiType.ManaReflected) continue;
            if (api != ApiType.DealDamage && api != ApiType.LoseLife) return false;
            if (s.usesTargeting()) return false;
            // LoseLife defaults to its controller; DealDamage must name it (fail closed otherwise)
            final String who = s.hasParam("Defined") ? s.getParam("Defined") : api == ApiType.LoseLife ? "You" : "";
            if (!"You".equals(who)) return false; // damage to anything but its controller
            rider = true;
        }
        return rider && !lifeSensitive(opp);
    }

    /** Words that make a card's text depend on its controller's life or the damage or life loss dealt to them. */
    static final String[] LIFE_WORDS = {"life", "damage to you", "dealt damage", "deals damage to you", "loses life",
            "lose life"};

    /**
     * Whether anything {@code p} controls or may play reads their life total or damage or life loss to them: the text of
     * every card in their battlefield, hand, graveyard, exile and command zone, and of their items on the stack (fail closed: any mention of "life" or of
     * damage to them counts; an unreadable card counts).
     */
    static boolean lifeSensitive(final Player p) {
        // review #161 S2-3: their spells and abilities on the stack (by the controller of the item)
        final List<Card> stack = new java.util.ArrayList<>();
        try {
            for (forge.game.spellability.SpellAbilityStackInstance si : p.getGame().getStack()) {
                if (si.getActivatingPlayer() == p && si.getSourceCard() != null) {
                    stack.add(si.getSourceCard());
                }
            }
        } catch (RuntimeException e) {
            return true;
        }
        for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
                ZoneType.Command, ZoneType.Stack)) {
            for (Card c : z == ZoneType.Stack ? stack : p.getCardsIn(z)) {
                final String text;
                try {
                    text = String.valueOf(c.getOracleText()).toLowerCase(java.util.Locale.ROOT);
                } catch (RuntimeException e) {
                    return true;
                }
                if (c.isFaceDown() || text.isEmpty()) return true;
                for (String w : LIFE_WORDS) {
                    if (text.contains(w)) {
                        // a card's own rider text ("deals 2 damage to you") is the ability under test: skip lands and
                        // artifacts whose only life text is their own mana rider
                        if (c.isLand() || c.isArtifact() && !c.isCreature()) {
                            if (ownManaRiderOnly(c)) continue;
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether every ability of {@code c} that is not a pure mana ability is a self-damage mana rider. */
    static boolean ownManaRiderOnly(final Card c) {
        try {
            for (SpellAbility a : c.getSpellAbilities()) {
                if (!a.isManaAbility() && !a.isLandAbility()) return false; // a land's play ability is not text
            }
            return c.getTriggers().isEmpty() && c.getStaticAbilities().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean negative(final SpellAbility sa) {
        for (String k : new String[] {"NumDef", "NumAtt"}) {
            if (sa.hasParam(k) && sa.getParam(k).trim().startsWith("-")) return true;
        }
        return false;
    }

    static boolean lethal(final SpellAbility sa, final Player victim) {
        final String k = sa.hasParam("NumDmg") ? "NumDmg" : sa.hasParam("LifeAmount") ? "LifeAmount" : null;
        if (k == null) return false;
        try {
            return AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam(k), sa) >= victim.getLife();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
