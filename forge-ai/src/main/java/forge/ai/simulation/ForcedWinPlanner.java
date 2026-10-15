package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinModel.Position;

/**
 * Lane doomsday-solver-1012: the forced-win solver's cheap width search. A depth-first search (with a transposition
 * set) over the abstract model's cast and draw sequences: play a land, cast a spell with any of its printed costs,
 * cycle, activate, float mana, pass the turn once. Horizon: this turn and our next turn.
 *
 * <p>The state is exact where a combo turn is decided: floating mana by colour, which sources are tapped, the land
 * drop, life, the storm count, our devotion to blue, the opponent's life and library, and our library as a list of
 * entries that are KNOWN (we put the card there), UNKNOWN (an unseen card) or LAZY. A LAZY entry is a position of an
 * ordered library choice (an observed PILE effect, e.g. Doomsday) whose card is chosen only when a draw reaches it:
 * the search branches there over the distinct cards still available (one per name, ordered by observed affordance).
 * Tutors branch the same way. So no pile is ever written down in advance: it is whatever the search drew to win, in the
 * order it drew it; untouched positions are filled with leftovers afterwards.</p>
 *
 * <p>Mana is paid the way Forge AI pays, as closely as a greedy model can: floating mana first, then the least
 * flexible untapped source; a Phyrexian pip with mana when a source can pay it, else 2 life; returned permanents are
 * the first of their subtype in battlefield order (ComputerUtil.chooseReturnType) after floating their mana.</p>
 *
 * <p>The opponent is not in this model. A line found here is a candidate for {@link ForcedWinSolver}, which plays it
 * in Forge game copies against the opponent's board and sampled hands.</p>
 */
public final class ForcedWinPlanner {

    /** One step of a line, addressable in any game copy by its host card id and ability description. */
    static final class Step {
        final AbilKind kind;
        final boolean pass;
        final int hostId;
        final String hostName;
        final String desc;
        final String costKey;
        final String targetVariant;
        /** The ability's observed effects (for the follower's choice answers and the report). */
        final List<Eff> effects;
        /** Forced answers for the asks this step raises: PILE picks top first, tutor picks, put-back ids. */
        final List<Integer> pile = new ArrayList<>();
        final List<Integer> picks = new ArrayList<>();
        final List<Integer> putBack = new ArrayList<>();
        /** FLOAT of a plain source (a land before a return cost): the colour index; -1 otherwise. */
        final int floatColor;
        boolean phyrexianLife;
        /** ENGINE steps: the recorded targets, "link:ref" (ref = "C" + card id or "P" + player id). */
        final List<String> targetRefs = new ArrayList<>();
        /** ENGINE steps: the forced target of the first targeting trigger of ours this step causes (null = Forge AI's). */
        String triggerRef;
        /** ENGINE steps: the forced pick (by name) of the first search of our library this step makes (null = AI's). */
        String pickName;
        /**
         * Diagnostics (solver-gaps-1015): the width model's expected hand and library top (6 entries) before this step,
         * as planner card indices (filled when the line is recorded into ids; -1 = an unseen card). Shared on copy.
         */
        int[] expHandIdx, expLibIdx;
        List<Integer> expHand = List.of(), expLib = List.of();

        Step(AbilKind kind, ACard host, Abil ab, int floatColor) {
            this.kind = kind;
            this.pass = false;
            this.hostId = host.id;
            this.hostName = host.name;
            this.desc = ab == null ? "" : ab.desc;
            this.costKey = ab == null ? "" : ab.costKey;
            this.targetVariant = ab == null ? null : ab.targetVariant;
            this.effects = ab == null ? List.of() : List.copyOf(ab.effects);
            this.floatColor = floatColor;
        }

        private Step() {
            this.kind = null;
            this.pass = true;
            this.hostId = -1;
            this.hostName = "";
            this.desc = "";
            this.costKey = "";
            this.targetVariant = null;
            this.effects = List.of();
            this.floatColor = -1;
        }

        static final Step PASS = new Step();

        Step copy() {
            if (pass) {
                return this;
            }
            final Step s = new Step(kind, hostId, hostName, desc, costKey, targetVariant, effects, floatColor);
            s.pile.addAll(pile);
            s.picks.addAll(picks);
            s.putBack.addAll(putBack);
            s.phyrexianLife = phyrexianLife;
            s.targetRefs.addAll(targetRefs);
            s.engineSpell = engineSpell;
            s.triggerRef = triggerRef;
            s.pickName = pickName;
            s.expHandIdx = expHandIdx;
            s.expLibIdx = expLibIdx;
            s.expHand = expHand;
            s.expLib = expLib;
            return s;
        }

        private Step(AbilKind kind, int hostId, String hostName, String desc, String costKey, String targetVariant,
                List<Eff> effects, int floatColor) {
            this.kind = kind;
            this.pass = false;
            this.hostId = hostId;
            this.hostName = hostName;
            this.desc = desc;
            this.costKey = costKey;
            this.targetVariant = targetVariant;
            this.effects = effects;
            this.floatColor = floatColor;
        }

        /** An engine step: any ability of {@code hostId}, by description and cost text. */
        static Step engine(AbilKind kind, int hostId, String hostName, String desc, String costKey, boolean spell) {
            final Step s = new Step(kind, hostId, hostName, desc, costKey, null, List.of(), -1);
            s.engineSpell = spell;
            return s;
        }

        /** An engine FLOAT step: activate a mana ability (by description) for one mana of a colour. */
        static Step floatMana(int hostId, String hostName, String desc, int colour) {
            return new Step(AbilKind.FLOAT, hostId, hostName, desc, "", null, List.of(), colour);
        }

        /** ENGINE steps: the ability is a spell (the adversary's counter targets). */
        boolean engineSpell;

        boolean isSpell() {
            return kind == AbilKind.SPELL || engineSpell;
        }

        @Override
        public String toString() {
            if (pass) {
                return "PASS";
            }
            if (kind == AbilKind.ATTACK) {
                return "ATTACK";
            }
            return kind + ":" + hostName + (floatColor >= 0 ? "->" + ForcedWinModel.SHORT[floatColor] : "")
                    + (targetRefs.isEmpty() ? "" : "->" + targetRefs) + (triggerRef == null ? "" : " trigger->" + triggerRef)
                    + (pickName == null ? "" : " pick=" + pickName)
                    + (targetVariant != null ? "@" + targetVariant : "")
                    + (pile.isEmpty() ? "" : " pile" + pile) + (picks.isEmpty() ? "" : " pick" + picks)
                    + (putBack.isEmpty() ? "" : " putBack" + putBack) + (phyrexianLife ? " phyLife" : "");
        }
    }

    /** A winning line of the abstract model. */
    static final class Line {
        final List<Step> steps;
        final List<String> pileNames;
        /** 0: wins this turn; 1: wins by our next turn (after one pass). */
        final int winTurn;
        final int lifeLeft;
        final int manaLeft;
        /** Protection cards (observed counters) in hand at the end, and their names. */
        final int protection;
        final List<String> protectionNames;
        final String how;
        int rank;
        /** The play-outs keep our library order (the line was planned on a known order). */
        boolean knownLibrary;

        Line(List<Step> steps, List<String> pileNames, int winTurn, int lifeLeft, int manaLeft, int protection,
                List<String> protectionNames, String how) {
            this.steps = steps;
            this.pileNames = pileNames;
            this.winTurn = winTurn;
            this.lifeLeft = lifeLeft;
            this.manaLeft = manaLeft;
            this.protection = protection;
            this.protectionNames = protectionNames;
            this.how = how;
        }

        String key() {
            return steps.toString();
        }

        @Override
        public String toString() {
            return "turn+" + winTurn + " " + how + (pileNames.isEmpty() ? "" : " pile=" + pileNames) + " steps="
                    + steps + " life=" + lifeLeft + " mana=" + manaLeft
                    + (protection > 0 ? " protection=" + protectionNames : "");
        }
    }

    static final class Limits {
        int nodes = 30_000;
        int depth = 18;
        int maxLines = 120;
        /** 1: this turn only; 2: this turn and our next turn. */
        int horizon = 2;
        int classes = 14;
        /** POST-FREEZE 5: a wall-time budget (ms); late-game positions with large graveyards made keys slow. */
        long ms = 1500;
    }

    static final class Result {
        final List<Line> lines = new ArrayList<>();
        int nodes;
        boolean exhausted;
        boolean sameTurn, nextTurn;
        long nanos;

        String summary() {
            return "lines=" + lines.size() + " nodes=" + nodes + (exhausted ? "(budget)" : "") + " sameTurn=" + sameTurn
                    + " nextTurn=" + nextTurn + " ms=" + nanos / 1_000_000;
        }
    }

    // ------------------------------------------------------------------ state

    private final Position pos;
    private final Limits lim;
    private final List<ACard> cards = new ArrayList<>();
    private final Map<Integer, Integer> indexOfId = new HashMap<>();
    private final Result result = new Result();
    private final Set<String> seen = new HashSet<>();
    private final Set<String> lineKeys = new HashSet<>();
    private int nodes;
    /** The card index of an unseen card (drawn from an unknown library): no abilities. */
    private final int unknownCard;

    private static final int UNKNOWN = Integer.MIN_VALUE;
    private static final int LAZY = -1;

    static final class Perm {
        int card;
        boolean tapped;
        boolean activated;
        int loyalty;

        Perm copy() {
            final Perm p = new Perm();
            p.card = card;
            p.tapped = tapped;
            p.activated = activated;
            p.loyalty = loyalty;
            return p;
        }
    }

    final class S {
        int turnIdx;
        /** We may cast sorcery-speed things now (our main phase with an empty stack). */
        boolean sorcery;
        int life, oppLife, oppLib, storm;
        boolean landDrop;
        final int[] pool = new int[7];
        List<Integer> hand = new ArrayList<>();
        List<Perm> bf = new ArrayList<>();
        /** Library entries, top first: a card index, UNKNOWN, or LAZY - slot. */
        List<Integer> lib = new ArrayList<>();
        /** Cards known to be in the library (unordered unless entries say so), for tutors and piles. */
        List<Integer> libPool = new ArrayList<>();
        List<Integer> gyPool = new ArrayList<>();
        /** The current ordered library choice: slot -> card (-1 unassigned) and its candidates. */
        int[] pile;
        List<Integer> remaining = new ArrayList<>();
        int pileStep = -1;
        int devotion;
        boolean emptyDrawWin;
        boolean canDraw;
        List<Step> steps = new ArrayList<>();
        boolean won;
        String how = "";
        boolean dead;

        S copy() {
            final S s = new S();
            s.turnIdx = turnIdx;
            s.sorcery = sorcery;
            s.life = life;
            s.oppLife = oppLife;
            s.oppLib = oppLib;
            s.storm = storm;
            s.landDrop = landDrop;
            System.arraycopy(pool, 0, s.pool, 0, pool.length);
            s.hand = new ArrayList<>(hand);
            s.bf = new ArrayList<>();
            for (Perm p : bf) {
                s.bf.add(p.copy());
            }
            s.lib = new ArrayList<>(lib);
            s.libPool = new ArrayList<>(libPool);
            s.gyPool = new ArrayList<>(gyPool);
            s.pile = pile == null ? null : pile.clone();
            s.remaining = new ArrayList<>(remaining);
            s.pileStep = pileStep;
            s.devotion = devotion;
            s.emptyDrawWin = emptyDrawWin;
            s.canDraw = canDraw;
            s.steps = new ArrayList<>();
            for (Step st : steps) {
                s.steps.add(st.copy());
            }
            s.won = won;
            s.how = how;
            s.dead = dead;
            return s;
        }

        String key() {
            final StringBuilder sb = new StringBuilder();
            sb.append(turnIdx).append(sorcery ? 'S' : 'i').append('/').append(life).append('/').append(oppLife).append('/').append(oppLib)
                    .append('/').append(storm).append(landDrop ? 'L' : 'l').append(Arrays.toString(pool)).append('h');
            sb.append(names(hand)).append('b');
            // permutations of the same permanents are one position (order matters only for returned subtypes)
            final List<String> perms = new ArrayList<>();
            for (Perm p : bf) {
                perms.add(cards.get(p.card).name + (p.tapped ? 't' : 'u') + (p.activated ? 'a' : '-') + p.loyalty);
            }
            java.util.Collections.sort(perms);
            sb.append(perms);
            sb.append('L');
            for (int e : lib) {
                sb.append(e == UNKNOWN ? "?" : e < 0 ? "*" : cards.get(e).name).append(',');
            }
            sb.append('R').append(names(remaining)).append('P').append(names(libPool)).append('G').append(names(gyPool));
            return sb.toString();
        }
    }

    private List<String> names(List<Integer> xs) {
        final List<String> out = new ArrayList<>();
        for (int x : xs) {
            out.add(cards.get(x).name);
        }
        java.util.Collections.sort(out);
        return out;
    }

    ForcedWinPlanner(Position pos, Limits lim) {
        this.pos = pos;
        this.lim = lim;
        final ACard unknown = new ACard();
        unknown.id = -1;
        unknown.name = "?";
        cards.add(unknown);
        unknownCard = 0;
    }

    private int idx(ACard a) {
        final Integer i = indexOfId.get(a.id);
        if (i != null) {
            return i;
        }
        cards.add(a);
        indexOfId.put(a.id, cards.size() - 1);
        return cards.size() - 1;
    }

    // ------------------------------------------------------------------ entry

    static Result plan(Position pos, Limits lim) {
        final long t0 = System.nanoTime();
        final Result r = new ForcedWinPlanner(pos, lim).run();
        r.nanos = System.nanoTime() - t0;
        return r;
    }

    private long deadline = Long.MAX_VALUE;

    Result run() {
        deadline = System.nanoTime() + lim.ms * 1_000_000L;
        final S s = new S();
        s.sorcery = pos.sorcerySpeed;
        s.life = pos.life;
        s.oppLife = pos.oppLife;
        s.oppLib = pos.oppLibrary;
        s.storm = pos.storm;
        s.landDrop = pos.landDrop;
        System.arraycopy(pos.pool, 0, s.pool, 0, 6);
        s.canDraw = pos.canDraw;
        for (ACard a : pos.hand) {
            s.hand.add(idx(a));
        }
        for (int i = 0; i < pos.battlefield.size(); i++) {
            final Perm p = new Perm();
            p.card = idx(pos.battlefield.get(i));
            p.tapped = pos.tapped.get(i);
            p.loyalty = pos.battlefield.get(i).startLoyalty;
            s.bf.add(p);
        }
        s.devotion = pos.devotionBlue;
        s.emptyDrawWin = pos.emptyDrawWinLive;
        for (ACard a : pos.library) {
            s.libPool.add(idx(a));
        }
        for (ACard a : pos.graveyard) {
            s.gyPool.add(idx(a));
        }
        if (pos.knownOrder != null) {
            for (int id : pos.knownOrder) {
                s.lib.add(indexOfId.getOrDefault(id, unknownCard));
            }
        } else {
            for (int i = 0; i < pos.librarySize; i++) {
                s.lib.add(UNKNOWN);
            }
        }
        dfs(s, 0);
        rank();
        result.nodes = nodes;
        if (pos.knownOrder != null) {
            for (Line l : result.lines) {
                l.knownLibrary = true;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ search

    private void dfs(S s, int depth) {
        if (s.dead) {
            return;
        }
        if (s.won) {
            record(s);
            return;
        }
        if (nodes >= lim.nodes || result.lines.size() >= lim.maxLines || (nodes & 63) == 0 && System.nanoTime() > deadline) {
            result.exhausted |= nodes >= lim.nodes || System.nanoTime() > deadline;
            if (System.nanoTime() > deadline) {
                nodes = Math.max(nodes, lim.nodes);
            }
            return;
        }
        if (depth >= lim.depth) {
            return;
        }
        nodes++;
        if (!seen.add(s.key())) {
            return;
        }
        for (S next : successors(s)) {
            dfs(next, depth + 1);
            if (nodes >= lim.nodes || result.lines.size() >= lim.maxLines) {
                return;
            }
        }
    }

    private List<S> successors(S s) {
        final List<S> first = new ArrayList<>(), second = new ArrayList<>(), third = new ArrayList<>();
        final Set<String> tried = new HashSet<>();
        final boolean pending = pos.pendingId >= 0 && s.steps.isEmpty();
        for (int hi = 0; hi < s.hand.size(); hi++) {
            final int h = s.hand.get(hi);
            final ACard a = cards.get(h);
            if (pending && a.id != pos.pendingId) {
                continue;
            }
            for (Abil ab : a.fromHand) {
                if (ab.unplayable || ab.cardTargets && ab.kind != AbilKind.LAND) {
                    continue;
                }
                if (pending && ab.kind != AbilKind.SPELL) {
                    continue;
                }
                final String k = a.name + "|" + ab.kind + "|" + ab.costKey + "|" + ab.desc + "|" + ab.targetVariant;
                if (!tried.add(k) || !pending && !allowed(s, a, ab)) {
                    continue;
                }
                final List<S> into = isWinny(a, ab) || ab.has(EffKind.PILE) ? first
                        : ab.has(EffKind.DRAW) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP) ? second : third;
                into.addAll(apply(s, hi, -1, a, ab));
            }
        }
        for (int bi = 0; bi < s.bf.size(); bi++) {
            final Perm p = s.bf.get(bi);
            final ACard a = cards.get(p.card);
            for (Abil ab : a.onBattlefield) {
                if (ab.unplayable || ab.cardTargets) {
                    continue;
                }
                final String k = "bf" + bi + "|" + ab.desc + "|" + ab.costKey + "|" + ab.targetVariant;
                if (!tried.add(k) || !allowedBf(s, p, ab)) {
                    continue;
                }
                (ab.has(EffKind.DRAW) || ab.has(EffKind.WIN) ? second : third).addAll(apply(s, -1, bi, a, ab));
            }
        }
        final List<S> out = new ArrayList<>(first);
        out.addAll(second);
        out.addAll(third);
        if (s.turnIdx < lim.horizon - 1 && !pending) {
            final S n = s.copy();
            n.steps.add(Step.PASS);
            out.addAll(passTurn(n));
        }
        return out;
    }

    private static boolean isWinny(ACard a, Abil ab) {
        return ab.has(EffKind.WIN) || ab.storm && (ab.has(EffKind.DRAIN) || ab.has(EffKind.MILL_OPP))
                || ab.kind == AbilKind.SPELL && a.permanent && (a.winsOnEntry || a.winIfLibraryAtMost >= 0
                        || a.emptyDrawWin);
    }

    private boolean allowed(S s, ACard a, Abil ab) {
        if (ab.kind == AbilKind.LAND) {
            return s.landDrop && s.sorcery;
        }
        if (!s.sorcery && !ab.instant) {
            return false;
        }
        if (ab.kind == AbilKind.SPELL && a.permanent) {
            return true;
        }
        return !ab.effects.isEmpty();
    }

    private boolean allowedBf(S s, Perm p, Abil ab) {
        if (!s.sorcery && !ab.instant && ab.kind != AbilKind.FLOAT) {
            return false;
        }
        if (ab.tapSelf && p.tapped) {
            return false;
        }
        if (ab.loyalty && (p.activated || p.loyalty + ab.loyaltyDelta < 0)) {
            return false;
        }
        return !ab.effects.isEmpty();
    }

    private List<S> apply(S s0, int handIdx, int bfIdx, ACard a, Abil ab) {
        final List<S> out = new ArrayList<>();
        final S s = s0.copy();
        final int host = handIdx >= 0 ? s.hand.get(handIdx) : s.bf.get(bfIdx).card;
        Perm self = bfIdx >= 0 ? s.bf.get(bfIdx) : null;
        final boolean free = pos.pendingId >= 0 && a.id == pos.pendingId && s0.steps.isEmpty();
        // returned permanents (Gush): float their mana, then they go back to our hand
        if (ab.returnN > 0 && !free) {
            final List<Perm> back = new ArrayList<>();
            for (Perm p : s.bf) {
                if (back.size() < ab.returnN && cards.get(p.card).subtypes.contains(ab.returnType)) {
                    back.add(p);
                }
            }
            if (back.size() < ab.returnN) {
                return out;
            }
            for (Perm p : back) {
                final ACard land = cards.get(p.card);
                if (!p.tapped && land.sourceColors != 0) {
                    final int colour = bestFloatColour(land.sourceColors);
                    p.tapped = true;
                    s.pool[colour] += Math.max(1, land.sourceAmount);
                    s.steps.add(new Step(AbilKind.FLOAT, land, null, colour));
                }
            }
            for (Perm p : back) {
                s.bf.remove(p);
                s.hand.add(p.card);
            }
        }
        final Step step = new Step(ab.kind, a, ab, -1);
        step.expHandIdx = s0.hand.stream().mapToInt(Integer::intValue).toArray();
        step.expLibIdx = s0.lib.subList(0, Math.min(6, s0.lib.size())).stream().mapToInt(Integer::intValue).toArray();
        if (!free && !pay(s, ab, self, step)) {
            return out;
        }
        s.steps.add(step);
        if (handIdx >= 0) {
            s.hand.remove(Integer.valueOf(host));
        }
        if (ab.kind == AbilKind.LAND) {
            s.landDrop = false;
            final Perm p = new Perm();
            p.card = host;
            p.tapped = a.entersTapped;
            s.bf.add(p);
            out.add(s);
            return out;
        }
        if (ab.kind == AbilKind.SPELL && !free) {
            s.storm++;
        }
        Perm sacked = null;
        if (self != null) {
            if (ab.tapSelf) {
                self.tapped = true;
            }
            if (ab.loyalty) {
                self.activated = true;
                self.loyalty += ab.loyaltyDelta;
            }
            if (ab.sacSelf) {
                sacked = self;
            }
        }
        if (ab.kind == AbilKind.CYCLE) {
            s.gyPool.add(host);
        }
        final List<Eff> effs = new ArrayList<>();
        if (ab.kind == AbilKind.SPELL && a.permanent) {
            final Perm p = new Perm();
            p.card = host;
            p.loyalty = a.startLoyalty;
            s.bf.add(p);
            s.devotion += a.bluePips;
            s.emptyDrawWin |= a.emptyDrawWin;
            if (a.winsOnEntry) {
                s.won = true;
                s.how = "enters";
                out.add(s);
                return out;
            }
            if (a.winIfLibraryAtMost >= 0) {
                if (s.lib.size() <= a.winIfLibraryAtMost + (s.devotion - a.winDevotion)) {
                    s.won = true;
                    s.how = "enters(library<=" + (a.winIfLibraryAtMost + s.devotion - a.winDevotion) + ")";
                }
                out.add(s);
                return out;
            }
            effs.addAll(ab.effects);
        } else {
            effs.addAll(ab.effects);
        }
        // an instant or sorcery goes to our graveyard after it resolves (not while it resolves: Doomsday's own pile)
        final boolean toGraveyard = handIdx >= 0 && ab.kind == AbilKind.SPELL && !a.permanent;
        if (sacked != null) {
            // the ability's observed (or folded-in) effects already hold what the sacrifice itself causes
            s.bf.remove(sacked);
            s.gyPool.add(sacked.card);
        }
        resolve(s, effs, 0, 0, ab, out);
        if (toGraveyard) {
            for (S n : out) {
                n.gyPool.add(host);
            }
        }
        return out;
    }

    private static int bestFloatColour(int mask) {
        if ((mask & (1 << ForcedWinModel.U)) != 0) {
            return ForcedWinModel.U;
        }
        return Integer.numberOfTrailingZeros(mask);
    }

    /** Our turn ends; the opponent's turn is not modelled except their draw (a milled-out opponent loses); our turn. */
    private List<S> passTurn(S s) {
        final List<S> out = new ArrayList<>();
        if (s.oppLib <= 0) {
            // the opponent loses drawing from an empty library in their draw step, before our next turn
            s.turnIdx++;
            s.won = true;
            s.how = "opponent-decked";
            out.add(s);
            return out;
        }
        s.turnIdx++;
        s.sorcery = true;
        Arrays.fill(s.pool, 0);
        s.landDrop = true;
        s.storm = 0;
        for (Perm p : s.bf) {
            p.tapped = false;
            p.activated = false;
        }
        final List<Eff> draw = List.of(new Eff(EffKind.DRAW, 1, 0));
        resolve(s, draw, 0, 0, null, out);
        return out;
    }

    private void resolve(S s, List<Eff> effs, int ei, int done, Abil ab, List<S> out) {
        if (s.dead) {
            return;
        }
        if (s.won || ei >= effs.size()) {
            out.add(s);
            return;
        }
        final Eff e = effs.get(ei);
        final Step step = s.steps.isEmpty() ? null : s.steps.get(s.steps.size() - 1);
        switch (e.kind) {
            case DRAW: {
                if (done >= e.n) {
                    resolve(s, effs, ei + 1, 0, ab, out);
                    return;
                }
                if (!s.canDraw) {
                    return;
                }
                if (s.lib.isEmpty()) {
                    if (s.emptyDrawWin) {
                        s.won = true;
                        s.how = "empty-draw";
                        out.add(s);
                    }
                    return;
                }
                final int top = s.lib.get(0);
                if (top == UNKNOWN || top >= 0) {
                    s.lib.remove(0);
                    s.hand.add(top == UNKNOWN ? unknownCard : top);
                    resolve(s, effs, ei, done + 1, ab, out);
                    return;
                }
                for (int c : classes(s.remaining)) {
                    final S n = s.copy();
                    assign(n, top, c);
                    n.lib.remove(0);
                    n.hand.add(c);
                    resolve(n, effs, ei, done + 1, ab, out);
                }
                return;
            }
            case MILL: {
                for (int i = 0; i < e.n && !s.lib.isEmpty(); i++) {
                    final int top = s.lib.remove(0);
                    if (top <= LAZY && top != UNKNOWN) {
                        final int f = leftover(s.remaining);
                        if (f >= 0) {
                            assign(s, top, f);
                            s.gyPool.add(f);
                        }
                    } else if (top >= 0) {
                        s.gyPool.add(top);
                    }
                }
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case MILL_OPP: {
                final int instances = ab != null && ab.storm ? Math.max(1, s.storm) : 1;
                s.oppLib -= e.n * instances;
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case MANA: {
                if (Integer.bitCount(e.colors) == 1) {
                    s.pool[Integer.numberOfTrailingZeros(e.colors)] += e.n;
                } else {
                    s.pool[ForcedWinModel.ANY] += e.n;
                }
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case PUTBACK: {
                for (List<Integer> ch : putBackChoices(s, e.n)) {
                    final S n = s.copy();
                    final Step st = n.steps.get(n.steps.size() - 1);
                    for (int i = ch.size() - 1; i >= 0; i--) {
                        n.hand.remove(Integer.valueOf(ch.get(i)));
                        n.lib.add(0, ch.get(i));
                    }
                    for (int c : ch) {
                        st.putBack.add(cards.get(c).id);
                    }
                    resolve(n, effs, ei + 1, 0, ab, out);
                }
                return;
            }
            case PILE: {
                // an ordered library choice: e.n cards of our library and graveyard, the rest exiled
                final List<Integer> cands = new ArrayList<>(s.libPool);
                cands.addAll(s.gyPool);
                cands.sort(Integer::compare);
                final int k = Math.min(e.n, cands.size());
                s.pile = new int[k];
                Arrays.fill(s.pile, -1);
                s.remaining = cands;
                s.pileStep = s.steps.size() - 1;
                s.lib = new ArrayList<>();
                for (int i = 0; i < k; i++) {
                    s.lib.add(LAZY - i);
                }
                s.libPool = new ArrayList<>();
                s.gyPool = new ArrayList<>();
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case TUTOR:
            case TUTOR_TOP: {
                for (int c : classes(s.libPool)) {
                    final S n = s.copy();
                    n.libPool.remove(Integer.valueOf(c));
                    n.steps.get(n.steps.size() - 1).picks.add(cards.get(c).id);
                    final int size = n.lib.size() - 1;
                    n.lib = new ArrayList<>();
                    if (e.kind == EffKind.TUTOR_TOP) {
                        n.lib.add(c);
                    } else {
                        n.hand.add(c);
                    }
                    for (int i = 0; i < size; i++) {
                        n.lib.add(UNKNOWN);
                    }
                    resolve(n, effs, ei + 1, 0, ab, out);
                }
                return;
            }
            case LIFE: {
                s.life += e.n;
                if (s.life <= 0) {
                    return;
                }
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case DRAIN: {
                s.oppLife -= e.n * (ab != null && ab.storm ? Math.max(1, s.storm) : 1);
                if (s.oppLife <= 0) {
                    s.won = true;
                    s.how = ab != null && ab.storm ? "storm" : "drain";
                    out.add(s);
                    return;
                }
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case DISCARD: {
                for (int i = 0; i < e.n && !s.hand.isEmpty(); i++) {
                    final int d = leastUseful(s.hand);
                    s.hand.remove(Integer.valueOf(d));
                    s.gyPool.add(d);
                }
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case WIN: {
                s.won = true;
                s.how = "win";
                out.add(s);
                return;
            }
            case SHUFFLE: {
                // POST-FREEZE 2: our library is shuffled; every entry becomes an unseen card (known cards go back to
                // the library's contents; a lazy pile position is no longer ours to choose)
                final int size = s.lib.size();
                for (int e2 : s.lib) {
                    if (e2 >= 0) {
                        s.libPool.add(e2);
                    }
                }
                s.lib = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    s.lib.add(UNKNOWN);
                }
                s.remaining = new ArrayList<>();
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            default:
                resolve(s, effs, ei + 1, 0, ab, out);
        }
    }

    private void assign(S s, int lazy, int card) {
        s.pile[LAZY - lazy] = card;
        s.remaining.remove(Integer.valueOf(card));
    }

    /** One card per distinct name, ordered by observed affordance (wins, draws, mana, lands, protection), plus one other. */
    private List<Integer> classes(List<Integer> from) {
        final List<Integer> useful = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        int other = -1;
        for (int r : from) {
            final ACard a = cards.get(r);
            if (!names.add(a.name)) {
                continue;
            }
            if (a.useful() && !(a.land && a.sourceColors == 0)) {
                useful.add(r);
            } else if (other < 0) {
                other = r;
            }
        }
        useful.sort(Comparator.comparingInt((Integer c) -> cards.get(c).order()).thenComparingInt(c -> cards.get(c).id));
        final List<Integer> out = new ArrayList<>(useful.subList(0, Math.min(useful.size(), lim.classes)));
        if (other >= 0) {
            out.add(other);
        }
        return out;
    }

    private int leftover(List<Integer> from) {
        int best = -1, bestOrder = -1;
        for (int r : from) {
            final int o = cards.get(r).order();
            if (o > bestOrder) {
                best = r;
                bestOrder = o;
            }
        }
        return best;
    }

    private int leastUseful(List<Integer> hand) {
        int best = hand.get(0), bestOrder = -1;
        for (int h : hand) {
            final int o = cards.get(h).order();
            if (o > bestOrder) {
                best = h;
                bestOrder = o;
            }
        }
        return best;
    }

    private List<List<Integer>> putBackChoices(S s, int n) {
        final List<Integer> hand = new ArrayList<>(s.hand);
        hand.sort(Comparator.comparingInt((Integer h) -> -cards.get(h).order()).thenComparingInt(h -> cards.get(h).id));
        final List<Integer> distinct = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        for (int h : hand) {
            if (names.add(cards.get(h).name) || distinct.size() < n) {
                distinct.add(h);
            }
        }
        final List<List<Integer>> out = new ArrayList<>();
        if (hand.size() < n) {
            return out;
        }
        combos(distinct, n, 0, new ArrayList<>(), out, 6);
        return out;
    }

    private static void combos(List<Integer> xs, int n, int from, List<Integer> cur, List<List<Integer>> out, int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (cur.size() == n) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (int i = from; i < xs.size(); i++) {
            cur.add(xs.get(i));
            combos(xs, n, i + 1, cur, out, cap);
            cur.remove(cur.size() - 1);
        }
    }

    // ------------------------------------------------------------------ paying

    private boolean pay(S s, Abil ab, Perm self, Step step) {
        if (ab.kind == AbilKind.LAND) {
            return true;
        }
        if (ab.life > 0) {
            if (s.life <= ab.life) {
                return false;
            }
            s.life -= ab.life;
        }
        if (ab.discardHand) {
            for (int h : s.hand) {
                s.gyPool.add(h);
            }
            s.hand.clear();
        }
        for (int col = 0; col < 6; col++) {
            for (int k = 0; k < ab.pips[col]; k++) {
                if (!payColour(s, col, self)) {
                    return false;
                }
            }
        }
        for (int col = 0; col < 6; col++) {
            for (int k = 0; k < ab.phyrexian[col]; k++) {
                if (canPayColour(s, col, self)) {
                    payColour(s, col, self);
                } else {
                    if (s.life <= 2) {
                        return false;
                    }
                    s.life -= 2;
                    step.phyrexianLife = true;
                }
            }
        }
        for (int k = 0; k < ab.generic; k++) {
            if (!payGeneric(s, self)) {
                return false;
            }
        }
        return true;
    }

    private boolean canPayColour(S s, int col, Perm self) {
        return s.pool[col] > 0 || s.pool[ForcedWinModel.ANY] > 0 || source(s, 1 << col, self) != null;
    }

    private boolean payColour(S s, int col, Perm self) {
        if (s.pool[col] > 0) {
            s.pool[col]--;
            return true;
        }
        if (s.pool[ForcedWinModel.ANY] > 0) {
            s.pool[ForcedWinModel.ANY]--;
            return true;
        }
        final Perm p = source(s, 1 << col, self);
        if (p == null) {
            return false;
        }
        tapSource(s, p, col);
        s.pool[col]--;
        return true;
    }

    private boolean payGeneric(S s, Perm self) {
        if (s.pool[ForcedWinModel.C] > 0) {
            s.pool[ForcedWinModel.C]--;
            return true;
        }
        int best = -1;
        for (int c = 0; c < 5; c++) {
            if (s.pool[c] > 0 && (best < 0 || s.pool[c] > s.pool[best])) {
                best = c;
            }
        }
        if (best >= 0) {
            s.pool[best]--;
            return true;
        }
        if (s.pool[ForcedWinModel.ANY] > 0) {
            s.pool[ForcedWinModel.ANY]--;
            return true;
        }
        final Perm p = source(s, ForcedWinModel.ALL_COLORS, self);
        if (p == null) {
            return false;
        }
        final ACard a = cards.get(p.card);
        final int col = (a.sourceColors & (1 << ForcedWinModel.C)) != 0 ? ForcedWinModel.C
                : Integer.numberOfTrailingZeros(a.sourceColors);
        tapSource(s, p, col);
        s.pool[col]--;
        return true;
    }

    private Perm source(S s, int mask, Perm self) {
        Perm best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Perm p : s.bf) {
            if (p == self || p.tapped) {
                continue;
            }
            final ACard a = cards.get(p.card);
            if ((a.sourceColors & mask) == 0 || a.sourceSick) {
                continue;
            }
            final int score = Integer.bitCount(a.sourceColors) * 10 + (a.sourceSac ? 100 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private void tapSource(S s, Perm p, int col) {
        final ACard a = cards.get(p.card);
        p.tapped = true;
        s.pool[col] += Math.max(1, a.sourceAmount);
        if (a.sourceSac) {
            s.bf.remove(p);
            s.gyPool.add(p.card);
        }
    }

    // ------------------------------------------------------------------ recording and ranking

    private void record(S s) {
        final List<String> pileNames = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();
        for (Step st : s.steps) {
            steps.add(st.copy());
        }
        int[] slotCard = new int[0];
        if (s.pile != null && s.pileStep >= 0 && s.pileStep < steps.size()) {
            final List<Integer> left = new ArrayList<>(s.remaining);
            left.sort(Comparator.comparingInt((Integer r) -> -cards.get(r).order()).thenComparingInt(r -> cards.get(r).id));
            int li = 0;
            final Step ps = steps.get(s.pileStep);
            ps.pile.clear();
            slotCard = new int[s.pile.length];
            for (int i = 0; i < s.pile.length; i++) {
                int c = s.pile[i];
                if (c < 0) {
                    c = li < left.size() ? left.get(li++) : -1;
                }
                slotCard[i] = c;
                if (c >= 0) {
                    ps.pile.add(cards.get(c).id);
                    pileNames.add(cards.get(c).name);
                }
            }
        }
        // diagnostics: the expected hand and library top of each step, as card ids (-1 = unseen)
        for (Step st : steps) {
            if (st.expHandIdx != null) {
                st.expHand = expIds(st.expHandIdx, slotCard);
                st.expLib = expIds(st.expLibIdx, slotCard);
            }
        }
        int mana = 0;
        for (int i = 0; i < 7; i++) {
            mana += s.pool[i];
        }
        for (Perm p : s.bf) {
            if (!p.tapped && cards.get(p.card).sourceColors != 0) {
                mana++;
            }
        }
        final List<String> prot = new ArrayList<>();
        for (int h : s.hand) {
            if (cards.get(h).counter) {
                prot.add(cards.get(h).name);
            }
        }
        final Line line = new Line(steps, pileNames, s.turnIdx, s.life, mana, prot.size(), prot, s.how);
        if (lineKeys.add(line.key())) {
            result.lines.add(line);
            if (s.turnIdx == 0) {
                result.sameTurn = true;
            } else {
                result.nextTurn = true;
            }
        }
    }

    private List<Integer> expIds(int[] idx, int[] slotCard) {
        final List<Integer> out = new ArrayList<>(idx.length);
        for (int e : idx) {
            if (e == UNKNOWN || e == unknownCard) {
                out.add(-1);
            } else if (e >= 0) {
                out.add(cards.get(e).id);
            } else {
                final int slot = LAZY - e;
                out.add(slot < slotCard.length && slotCard[slot] >= 0 ? cards.get(slotCard[slot]).id : -1);
            }
        }
        return out;
    }

    private void rank() {
        result.lines.sort(Comparator.comparingInt((Line l) -> l.winTurn)
                .thenComparingInt(l -> -Math.min(l.protection, 2))
                .thenComparingInt(l -> -Math.min(l.manaLeft, 3))
                .thenComparingInt(l -> -l.lifeLeft)
                .thenComparingInt(l -> l.steps.size())
                .thenComparing(Line::key));
        for (int i = 0; i < result.lines.size(); i++) {
            result.lines.get(i).rank = i;
        }
    }
}
