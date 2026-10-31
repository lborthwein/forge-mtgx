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
 * <li><b>Response set</b> ({@link #responses}): only real interaction with the line (counters, also on abilities and
 * Stifle effects; removal of our permanents; discard; flash or ambush blockers; lifegain, prevention and protection aimed
 * at the line; lethal burn), each castable or activatable now and payable. Incidental instant-speed abilities (a
 * fetchland crack, a draw, a pump outside combat) are not responses. Equivalent responses collapse to one: the same card
 * name, effect and target class (copies of a card, and the same answer on same-named targets, are one response).</li>
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

    // ------------------------------------------------------------------------------------------------ config

    /** The counts (no clock). {@code depth} caps the budget (k max = min(2, depth)). */
    public static final class Cfg {
        public int nodes = 64, perPoint = 4, depth = 2, k = 1;
        public boolean memo = true;

        public Cfg(final int nodes, final int perPoint, final int depth) {
            this.nodes = nodes;
            this.perPoint = perPoint;
            this.depth = depth;
        }

        int kMax() {
            return Math.max(0, Math.min(2, depth));
        }
    }

    // ------------------------------------------------------------------------------------------------ scripts

    /** One branch: the responses made, by point index (ascending) and response index; {@code forge}: Forge AI's own play. */
    public static final class Script {
        final boolean forge;
        final int[] pts, resp;
        /** The budget of this subtree (-1: read it from the play-out's first non-empty point). */
        final int budget;

        Script(final boolean forge, final int[] pts, final int[] resp, final int budget) {
            this.forge = forge;
            this.pts = pts;
            this.resp = resp;
            this.budget = budget;
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

        Point(final int size, final String state, final int impact, final int budgetHint) {
            this.size = size;
            this.state = state;
            this.impact = impact;
            this.budgetHint = budgetHint;
        }
    }

    /** One branch's play-out: its value (1 won, 0 lost, NaN unresolved), end and the points it met. */
    public static final class Leaf {
        public final double v;
        public final String end;
        public final List<Point> points;

        public Leaf(final double v, final String end, final List<Point> points) {
            this.v = v;
            this.end = end;
            this.points = points == null ? Collections.<Point>emptyList() : points;
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
        public Leaf firstLeaf;
    }

    // ------------------------------------------------------------------------------------------------ the tree

    /** The MIN over the opponent's budgeted response tree; {@code play} runs one branch (same world, same seeds). */
    public static Out min(final Cfg cfg, final java.util.function.Function<Script, Leaf> play) {
        final Out out = new Out();
        final java.util.ArrayDeque<Script> stack = new java.util.ArrayDeque<>();
        stack.push(Script.passAll());
        stack.push(Script.forgeRoot());
        final Set<String> memo = new HashSet<>();
        double lo = Double.POSITIVE_INFINITY;
        while (!stack.isEmpty()) {
            final Script s = stack.pop();
            if (out.nodes >= cfg.nodes) {
                out.cap = out.cap == null ? "nodes" : out.cap;
                break;
            }
            out.nodes++;
            final Leaf l = play.apply(s);
            if (out.firstLeaf == null) out.firstLeaf = l;
            if (Double.isNaN(l.v)) {
                out.cap = out.cap == null ? "branch:" + l.end : out.cap;
                continue;
            }
            if (l.v < lo) {
                lo = l.v;
                out.end = l.end;
                out.why = s.toString();
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
                        b = Math.min(cfg.kMax(), Math.max(0, p.budgetHint));
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
                if (p.size == 0) continue;
                out.maxResponses = Math.max(out.maxResponses, p.size);
                if (p.size > cfg.perPoint) {
                    out.cap = out.cap == null ? "truncated" : out.cap;
                    continue;
                }
                if (left <= 0) {
                    out.cap = out.cap == null ? "budget" : out.cap;
                    continue;
                }
                if (cfg.memo && !memo.add(p.state + "|" + left)) {
                    out.memoHits++;
                    continue;
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
        public Driver(final Script script, final Player opp, final Player victim, final int onlyTurn) {
            this.script = script;
            this.opp = opp;
            this.victim = victim;
            this.onlyTurn = onlyTurn;
        }

        /** Whether this priority is a new response point (and records it if so). */
        public boolean isPoint(final Game g) {
            final String pk = pointKey(g, victim);
            return pk != null && (onlyTurn < 0 || g.getPhaseHandler().getTurn() == onlyTurn) && !seen.contains(pk);
        }

        /**
         * The opponent's choice at this priority: null = pass; {@code own} = Forge AI's own play. Not a point: {@code own}.
         */
        public List<SpellAbility> choose(final Game g, final java.util.function.Supplier<List<SpellAbility>> own) {
            final String pk = pointKey(g, victim);
            if (pk == null || (onlyTurn >= 0 && g.getPhaseHandler().getTurn() != onlyTurn) || !seen.add(pk)) {
                return own.get();
            }
            final SpellAbility top = g.getStack().isEmpty() ? null : g.getStack().peek().getSpellAbility();
            final int idx = points.size();
            final List<SpellAbility> rs = responses(g, opp, victim, top);
            points.add(new Point(rs.size(), stateKey(g), impact(top), budgetHint(opp, rs)));
            if (script.forge) return own.get();
            final int r = script.respAt(idx);
            if (r < 0 || r >= rs.size()) return null;
            final SpellAbility sa = rs.get(r);
            answered = (top == null ? "attack" : top.getHostCard().getName()) + " answered by " + sa.getHostCard().getName();
            return new ArrayList<>(Collections.singletonList(sa));
        }
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

    /** The point's state class: the position fingerprint, the stack by name and the mana pools. */
    static String stateKey(final Game g) {
        final StringBuilder sb = new StringBuilder(LookaheadSearch.fingerprint(g));
        for (SpellAbilityStackInstance si : g.getStack()) {
            final SpellAbility sa = si.getSpellAbility();
            sb.append("|st:").append(sa == null || sa.getHostCard() == null ? "?" : sa.getHostCard().getName())
                    .append(sa == null ? "" : ":" + sa.getDescription());
        }
        for (Player p : g.getPlayers()) {
            sb.append("|pool").append(p.getId()).append(':').append(p.getManaPool().totalMana());
        }
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
    static final int COUNTER = 0, REMOVAL = 1, DISCARD = 2, BLOCKER = 3, SHIELD = 4;

    /**
     * The opponent's real responses to {@code top} (null: an attack point), collapsed and in a fixed order (category,
     * card name, target class), each castable or activatable now and payable. Sources: their hand, every permanent
     * (tapped too), graveyard and exile. Targets: our top stack object, our and their permanents by name, us, themselves.
     */
    public static List<SpellAbility> responses(final Game g, final Player opp, final Player victim, final SpellAbility top) {
        final TreeMap<String, SpellAbility> byKey = new TreeMap<>();
        final List<Card> sources = new ArrayList<>();
        for (ZoneType z : List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile)) {
            sources.addAll(opp.getCardsIn(z));
        }
        sources.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
        final boolean attack = top == null;
        for (Card c : sources) {
            for (SpellAbility sa0 : c.getAllPossibleAbilities(opp, true)) {
                if (sa0.isManaAbility() || sa0.isLandAbility()) continue;
                if (sa0.isSpell() && c.isInZone(ZoneType.Battlefield)) continue;
                final List<Object> targets = new ArrayList<>();
                final List<String> keys = new ArrayList<>();
                if (sa0.usesTargeting()) {
                    if (top != null && sa0.canTargetSpellAbility(top)) {
                        targets.add(top);
                        keys.add("stack");
                    }
                    for (Player who : List.of(victim, opp)) {
                        final Set<String> names = new HashSet<>();
                        for (Card v : who.getCardsIn(ZoneType.Battlefield)) {
                            if (names.add(v.getName()) && sa0.canTarget(v)) {
                                targets.add(v);
                                keys.add((who == opp ? "own:" : "card:") + v.getName());
                            }
                        }
                        if (sa0.canTarget(who)) {
                            targets.add(who);
                            keys.add(who == opp ? "self" : "player");
                        }
                    }
                } else {
                    targets.add(null);
                    keys.add("-");
                }
                for (int ti = 0; ti < targets.size(); ti++) {
                    final Object t = targets.get(ti);
                    final int cat;
                    try {
                        cat = category(sa0, t, top, victim, opp, attack);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (cat < 0) continue;
                    final SpellAbility cs = sa0.copy(opp);
                    cs.setActivatingPlayer(opp);
                    if (t != null) {
                        cs.resetTargets();
                        if (t instanceof SpellAbility ts) cs.getTargets().add(ts);
                        else cs.getTargets().add((GameObject) t);
                        if (!cs.isTargetNumberValid()) continue;
                    }
                    try {
                        if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, opp, false)) continue;
                    } catch (RuntimeException e) {
                        continue;
                    }
                    byKey.putIfAbsent(cat + "|" + c.getName() + "|" + sa0.getApi() + "|" + keys.get(ti), cs);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * The response's category, or -1 when it is not real interaction with the line. {@code t}: its target (a stack
     * object, a card, a player or null).
     */
    static int category(final SpellAbility sa, final Object t, final SpellAbility top, final Player victim,
            final Player opp, final boolean attack) {
        ApiType api = sa.getApi();
        for (SpellAbility s = sa; api == null && s != null; s = s.getSubAbility()) api = s.getApi();
        if (api == null) return -1;
        if (t instanceof SpellAbility) {
            // a counter (spells and abilities: Stifle), or a stack bounce (Remand)
            return api == ApiType.Counter || api == ApiType.ChangeZone ? COUNTER : -1;
        }
        if (t instanceof Card c) {
            if (c.getController() == victim) {
                switch (api) {
                    case Destroy: case DealDamage: case ChangeZone: case Sacrifice: case Tap: case GainControl:
                    case Debuff: case Fight:
                        return REMOVAL;
                    case Pump: case PumpAll:
                        return negative(sa) ? REMOVAL : -1;
                    default:
                        return -1;
                }
            }
            // their own permanent: protection, regeneration or a pump is aimed at the line only in combat
            if (attack && c.isCreature() && (api == ApiType.Pump || api == ApiType.Protection
                    || api == ApiType.Regenerate)) {
                return SHIELD;
            }
            return -1;
        }
        if (t instanceof Player p) {
            if (p == victim) {
                if (api == ApiType.Discard) return DISCARD;
                if ((api == ApiType.DealDamage || api == ApiType.LoseLife) && lethal(sa, victim)) return REMOVAL;
                return -1;
            }
            return api == ApiType.GainLife || api == ApiType.PreventDamage || api == ApiType.Protection ? SHIELD : -1;
        }
        // no target
        if (sa.isSpell() && sa.getHostCard() != null && sa.getHostCard().isCreature()) return BLOCKER;
        switch (api) {
            case Counter:
                return top != null ? COUNTER : -1;
            case Discard:
                return DISCARD;
            case DestroyAll: case DamageAll: case ChangeZoneAll: case SacrificeAll:
                return victim.getCreaturesInPlay().isEmpty() && !attack ? -1 : REMOVAL;
            case Fog: case PreventDamage: case GainLife: case ProtectionAll:
                return SHIELD;
            default:
                return -1;
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
