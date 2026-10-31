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

    /** The MIN's version (review #148 S2-1): in the proof spec's identity and the solver config string. */
    public static final int VERSION = 2;

    /** -Dforge.oppmin.debug=true: one stderr line per branch and per answered point (diagnostics only). */
    public static final boolean DEBUG = Boolean.getBoolean("forge.oppmin.debug");

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
            if (DEBUG) {
                final StringBuilder b = new StringBuilder();
                for (Card c : opp.getCardsIn(ZoneType.Battlefield)) b.append(c.getName()).append(c.isTapped() ? "/T" : "/U")
                        .append(c.isCreature() ? "/cr " : " ");
                System.err.println("[oppmin] point " + idx + " " + pk + " " + g.getPhaseHandler().getPhase() + " opp bf: " + b
                        + " stack " + g.getStack().size());
            }
            final String state = stateKey(g);
            final List<SpellAbility> rs = responses(g, opp, victim, top);
            // review S1 (safety net, the base's behaviour): Forge AI's own answer at this point is a candidate too
            final List<SpellAbility> mine = own.get();
            if (script.forge) {
                points.add(new Point(rs.size(), state, impact(top), budgetHint(opp, rs)));
                return mine;
            }
            final SpellAbility fa = mine == null || mine.isEmpty() ? null : mine.get(0);
            if (fa != null && fa.getActivatingPlayer() == opp && !fa.isManaAbility() && !sameAsAny(fa, rs)) {
                rs.add(fa);
            }
            points.add(new Point(rs.size(), state, impact(top), budgetHint(opp, rs)));
            final int r = script.respAt(idx);
            if (r < 0 || r >= rs.size()) return null;
            final SpellAbility sa = rs.get(r);
            if (DEBUG) System.err.println("[oppmin] point " + idx + " answer " + answerKey(sa) + " of " + rs.size());
            final Card host = sa.getHostCard();
            if (host != null && host.isInZone(ZoneType.Battlefield) && sa.getApi() == ApiType.Animate
                    && (!sa.hasParam("Defined") || "Self".equals(sa.getParam("Defined")))) {
                // a manland animating itself to block must not pay with itself (Forge AI would tap it for mana)
                forge.ai.AiCardMemory.rememberCard(opp, host, forge.ai.AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
            answered = (top == null ? "attack" : top.getHostCard().getName()) + " answered by " + sa.getHostCard().getName();
            return new ArrayList<>(Collections.singletonList(sa));
        }
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
                // review S3: alternative costs are separate answers (Force of Will hard-cast vs pitched)
                final String cost = sa0.getPayCosts() == null ? "-" : sa0.getPayCosts().toSimpleString();
                if (sa0.getApi() == ApiType.Charm) {
                    charmResponses(byKey, c, sa0, cost, top, victim, opp, attack);
                    continue;
                }
                final List<Object> targets = new ArrayList<>();
                final List<String> keys = new ArrayList<>();
                targetsOf(sa0, top, victim, opp, targets, keys);
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
                    byKey.putIfAbsent(cat + "|" + c.getName() + "|" + sa0.getApi() + "|" + cost + "|" + keys.get(ti), cs);
                }
            }
        }
        return new ArrayList<>(byKey.values());
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
        for (Player who : List.of(victim, opp)) {
            for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Graveyard)) {
                final Set<String> names = new HashSet<>();
                for (Card v : who.getCardsIn(z)) {
                    if (!names.contains(v.getName()) && sa.canTarget(v)) {
                        names.add(v.getName());
                        targets.add(v);
                        keys.add((who == opp ? "own" : "card") + (z == ZoneType.Graveyard ? "-gy:" : ":") + v.getName());
                    }
                }
            }
            if (sa.canTarget(who)) {
                targets.add(who);
                keys.add(who == opp ? "self" : "player");
            }
        }
    }

    /** The SVar a modal response carries: its forced modes (CharmOrder values, comma separated). */
    public static final String MODES = "OppMinModes";

    /**
     * Review S1: a modal spell's responses, one per mode combination (CharmNum of the possible modes, repeats when the
     * card allows them; at most 24 combinations), each with its modes forced ({@link #forcedModes}). A combination is a
     * response when any of its modes is (incidental modes alone are not).
     */
    static void charmResponses(final TreeMap<String, SpellAbility> byKey, final Card c, final SpellAbility sa0,
            final String cost, final SpellAbility top, final Player victim, final Player opp, final boolean attack) {
        final List<forge.game.spellability.AbilitySub> modes;
        final int num;
        final boolean repeat;
        try {
            sa0.setActivatingPlayer(opp);
            modes = forge.game.ability.effects.CharmEffect.makePossibleOptions(sa0);
            num = Math.max(1, AbilityUtils.calculateAmount(c, sa0.getParamOrDefault("CharmNum", "1"), sa0));
            repeat = sa0.hasParam("CanRepeatModes");
        } catch (RuntimeException e) {
            return;
        }
        if (modes.isEmpty()) return;
        final int k = repeat ? num : Math.min(num, modes.size());
        final List<int[]> combos = new ArrayList<>();
        combo(modes.size(), k, repeat, 0, new int[k], 0, combos, 24);
        int added = 0;
        for (int[] cmb : combos) {
            // review #148 S1 (round 2): every mode's distinct target classes (collapsed by name as for other answers),
            // and every product of them; past CHARM_CAP answers the point's set is over the per-point cap (a cap hit)
            final List<List<Object>> optT = new ArrayList<>();
            final List<List<Integer>> optC = new ArrayList<>();
            boolean ok = true;
            for (int m : cmb) {
                final forge.game.spellability.AbilitySub sub = modes.get(m);
                sub.setActivatingPlayer(opp);
                final List<Object> ts = new ArrayList<>();
                targetsOf(sub, top, victim, opp, ts, new ArrayList<>());
                final List<Object> oT = new ArrayList<>();
                final List<Integer> oC = new ArrayList<>();
                for (Object t : ts) {
                    int x;
                    try {
                        x = category(sub, t, top, victim, opp, attack);
                    } catch (RuntimeException e) {
                        x = -1; // a mode Forge cannot test here: not a reason to answer
                    }
                    if (x >= 0 || !sub.usesTargeting()) {
                        oT.add(t);
                        oC.add(x);
                    }
                }
                if (oT.isEmpty()) {
                    // a targeted mode with no real target: its first legal one (a bonus mode), else not castable
                    if (ts.isEmpty()) {
                        ok = false;
                        break;
                    }
                    oT.add(ts.get(0));
                    oC.add(-1);
                }
                optT.add(oT);
                optC.add(oC);
            }
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
                if (cat >= 0) {
                    final SpellAbility cs = sa0.copy(opp);
                    cs.setActivatingPlayer(opp);
                    cs.setSVar(MODES, key.toString());
                    boolean play;
                    try {
                        play = cs.canPlay() && ComputerUtilCost.canPayCost(cs, opp, false);
                    } catch (RuntimeException e) {
                        play = false;
                    }
                    if (play && byKey.putIfAbsent(cat + "|" + c.getName() + "|Charm|" + cost + "|modes:" + key, cs) == null) {
                        added++;
                    }
                }
                int q = cmb.length - 1;
                while (q >= 0 && ++pick[q] >= optT.get(q).size()) pick[q--] = 0;
                if (q < 0) break;
            }
            if (added > CHARM_CAP) break;
        }
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
     * Review S1: the response's category, or -1 when it is incidental. A deny-list: only effects known not to answer a
     * line are dropped (a draw, mana, scry or surveil, a library search, non-lethal damage or life loss to us, harm to
     * their own permanents, a pump or counters on their own creature outside combat); anything else, unknown effects
     * included, is a response. {@code t}: its target (a stack object, a card, a player or null).
     */
    static int category(final SpellAbility sa, final Object t, final SpellAbility top, final Player victim,
            final Player opp, final boolean attack) {
        final ApiType api = sa.getApi();
        if (t instanceof SpellAbility) {
            return api == ApiType.Counter ? COUNTER : OTHER; // a counter, a stack bounce, a redirect: all answer it
        }
        if (incidentalChain(sa, t, victim, opp, attack)) return -1;
        if (t instanceof Card c) {
            if (c.getController() == victim || c.isInZone(ZoneType.Graveyard)) return REMOVAL;
            return attack && c.isCreature() ? SHIELD : OTHER;
        }
        if (t instanceof Player p) {
            if (p == victim) return api == ApiType.Discard ? DISCARD : REMOVAL;
            return SHIELD;
        }
        if (sa.isSpell() && sa.getHostCard() != null && sa.getHostCard().isCreature()) return BLOCKER;
        if (api == ApiType.Animate || api == ApiType.Token || api == ApiType.CopyPermanent) return BLOCKER;
        if (api == ApiType.Counter) return top != null ? COUNTER : -1;
        return OTHER;
    }

    /** Effects that never answer a line by themselves. */
    static final Set<ApiType> INCIDENTAL = java.util.EnumSet.of(ApiType.Draw, ApiType.Mana, ApiType.ManaReflected,
            ApiType.Scry, ApiType.Surveil, ApiType.Dig, ApiType.DigUntil, ApiType.RearrangeTopOfLibrary, ApiType.Shuffle,
            ApiType.Investigate, ApiType.Explore);

    /** Whether every part of {@code sa}'s chain is incidental for this target. */
    static boolean incidentalChain(final SpellAbility sa, final Object t, final Player victim, final Player opp,
            final boolean attack) {
        boolean any = false;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            final ApiType api = s.getApi();
            if (api == null) continue;
            any = true;
            if (!incidental(s, api, s == sa ? t : null, victim, opp, attack)) return false;
        }
        return any;
    }

    static boolean incidental(final SpellAbility s, final ApiType api, final Object t, final Player victim,
            final Player opp, final boolean attack) {
        if (INCIDENTAL.contains(api)) return true;
        if (api == ApiType.ChangeZone && !s.usesTargeting() && "Library".equals(s.getParam("Origin"))) {
            return !"Battlefield".equals(s.getParam("Destination")) || !attack; // a search (a fetch, a tutor)
        }
        if (t instanceof Player p && p == victim && (api == ApiType.DealDamage || api == ApiType.LoseLife)) {
            return !lethal(s, victim);
        }
        if (t instanceof Player p && p == opp && (api == ApiType.DealDamage || api == ApiType.LoseLife)) return true;
        if (t instanceof Card c && c.getController() == opp && c.isInZone(ZoneType.Battlefield)) {
            if (api == ApiType.DealDamage || api == ApiType.Destroy) return true; // harm to their own permanent
            if (!attack && (api == ApiType.Pump && !negative(s) || api == ApiType.PutCounter)) return true;
        }
        return false;
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
