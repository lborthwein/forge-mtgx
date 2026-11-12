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
        /**
         * solver-gaps-1104 (enginePassToEnd only; false for every other step): a pass that rests at our priority in the
         * opponent's end step of the next turn (an instant-speed window before our turn), not at our next main phase.
         */
        final boolean toEnd;
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
        /** kill-line-1106 (followPay): the follower pays this step's Phyrexian pips with life. */
        boolean forceLife;
        /** kill-line-1106 (planDiscard; null = none): this step's forced discard tokens ({@link ForcedWinChoices}). */
        List<String> cac;
        /** ENGINE steps: the recorded targets, "link:ref" (ref = "C" + card id or "P" + player id). */
        final List<String> targetRefs = new ArrayList<>();
        /** ENGINE steps: the forced target of the first targeting trigger of ours this step causes (null = Forge AI's). */
        String triggerRef;
        /** ENGINE steps: the forced pick (by name) of the first search of our library this step makes (null = AI's). */
        String pickName;
        /**
         * labeller-v2 (a)/(c) (null = none, v1): the forced choice tokens of this step's window ({@link ForcedWinChoices}):
         * the engine's variant identity, or the planner's scry / surveil / discard answers.
         */
        /** labeller-v2 (a): every choice token the engine's move played (forced and Forge AI's), for the line; null = none. */
        List<String> cacPlayed;
        /** labeller-v2 (e) (null = Forge AI pays): the sources this step floats first, as FLOAT steps (a payment variant). */
        List<Step> payFloats;
        /**
         * Diagnostics (solver-gaps-1015): the width model's expected hand and library top (6 entries) before this step,
         * as planner card indices (filled when the line is recorded into ids; -1 = an unseen card). Shared on copy.
         */
        int[] expHandIdx, expLibIdx;
        List<Integer> expHand = List.of(), expLib = List.of();
        /**
         * solver-gaps-1015: the colours the line spent this step's any-colour mana on, in order (-1 = generic). The
         * follower makes the step produce them (the engine otherwise lets Forge AI choose: Manamorphose made BB where the
         * line needed UU).
         */
        final List<Integer> anyColors = new ArrayList<>();
        /**
         * sdcc-tutorcast-1110 (set only by a tutorCast plan; null / false / -1 / empty otherwise, and then nothing
         * changes). optCosts: the optional additional costs the step's spell is cast with (the engine's type names: the
         * follower adds them to the ability). sacIds: the permanents of ours it sacrifices as a cost (the follower
         * answers the cost with them). castFree: the id of the card this step searches for and casts without paying its
         * mana cost; the cast is the next step, which is {@link #free}: it is never played from priority, the follower
         * answers the engine's offer and then that spell's own asks. A search of such a step with no castFree puts its
         * card into our hand (an offer is declined).
         */
        List<String> optCosts;
        final List<Integer> sacIds = new ArrayList<>();
        int castFree = -1;
        boolean free;
        /**
         * sdcc-probeobj-1111 (set only by a probeObjects plan; -1 otherwise, and then nothing changes): the id of the
         * permanent of ours this step's ability targets, and which link of the ability targets it (the follower sets
         * that target). A permanent sacrificed as the step's cost is in {@link #sacIds}.
         */
        int objTarget = -1, objLink;

        Step(AbilKind kind, ACard host, Abil ab, int floatColor) {
            this.kind = kind;
            this.pass = false;
            this.toEnd = false;
            this.hostId = host.id;
            this.hostName = host.name;
            this.desc = ab == null ? "" : ab.desc;
            this.costKey = ab == null ? "" : ab.costKey;
            this.targetVariant = ab == null ? null : ab.targetVariant;
            this.effects = ab == null ? List.of() : List.copyOf(ab.effects);
            this.floatColor = floatColor;
            this.optCosts = ab == null || ab.optCosts == null ? null : List.copyOf(ab.optCosts);
        }

        private Step() {
            this(false);
        }

        private Step(boolean toEnd) {
            this.kind = null;
            this.pass = true;
            this.toEnd = toEnd;
            this.hostId = -1;
            this.hostName = "";
            this.desc = "";
            this.costKey = "";
            this.targetVariant = null;
            this.effects = List.of();
            this.floatColor = -1;
        }

        static final Step PASS = new Step();
        /** solver-gaps-1104 (enginePassToEnd): pass to the opponent's end step of the next turn (see {@link #toEnd}). */
        static final Step PASS_END = new Step(true);
        /** line-adoption-1107 (solverCfg anyWindow): on to main phase 1 of this turn, from our upkeep or draw step. */
        static final Step MAIN = new Step(AbilKind.MAIN, -1, "main", "main", "", null, List.of(), -1);

        Step copy() {
            if (pass || this == MAIN) {
                return this;
            }
            final Step s = new Step(kind, hostId, hostName, desc, costKey, targetVariant, effects, floatColor);
            s.pile.addAll(pile);
            s.picks.addAll(picks);
            s.putBack.addAll(putBack);
            s.phyrexianLife = phyrexianLife;
            s.forceLife = forceLife;
            s.cac = cac == null ? null : new ArrayList<>(cac);
            s.targetRefs.addAll(targetRefs);
            s.engineSpell = engineSpell;
            s.byName = byName;
            s.xValue = xValue;
            s.wideTarget = wideTarget;
            s.triggerRef = triggerRef;
            s.pickName = pickName;
            s.expHandIdx = expHandIdx;
            s.expLibIdx = expLibIdx;
            s.expHand = expHand;
            s.expLib = expLib;
            s.anyColors.addAll(anyColors);
            s.optCosts = optCosts;
            s.sacIds.addAll(sacIds);
            s.castFree = castFree;
            s.free = free;
            s.objTarget = objTarget;
            s.objLink = objLink;
            s.cacPlayed = cacPlayed == null ? null : new ArrayList<>(cacPlayed);
            s.payFloats = payFloats == null ? null : new ArrayList<>(payFloats);
            if (body != null) {
                s.body = new ArrayList<>(body);
                s.times = times;
            }
            return s;
        }

        private Step(AbilKind kind, int hostId, String hostName, String desc, String costKey, String targetVariant,
                List<Eff> effects, int floatColor) {
            this.kind = kind;
            this.pass = false;
            this.toEnd = false;
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
        /**
         * solver-cap12-1030 R2-3 (loopMacro only; false = off): a loop body's step, located by its host's name when the
         * host itself cannot play it (fungible copies, see Follower.locate). Not part of {@link #loopKey}.
         */
        boolean byName;
        /**
         * solver-cap12-1030 R3-4 (a), engineX only (-1 = off: Forge AI's X): the X this engine step pays (the follower sets
         * it on the ability before paying).
         */
        int xValue = -1;
        /** sdcc-loops-1111 (engineLoopCredit only; transient, not copied): an engine move whose X can only be zero now. */
        boolean xZero;
        /**
         * solver-cap12-1030 R3-4 (a) review fix (engineX in Forge's move order only; false = off): a single-target variant
         * past the first few (Forge AI's target and {@code targetVariants} more), searched after the node's other moves so
         * a widely targetable ability (a land destroyer facing many lands) does not fill the branching cap by itself.
         */
        boolean wideTarget;
        /**
         * REPEAT steps (search-bones-1015, the engine search's cycles with progress): the loop body, played {@link #times}
         * times in order; null for every other step.
         */
        List<Step> body;
        int times;

        /** A REPEAT step: {@code body} (engine steps) played {@code times} times. */
        static Step repeat(List<Step> body, int times) {
            final Step s = new Step(AbilKind.REPEAT, -1, "repeat", "repeat", "", null, List.of(), -1);
            s.body = new ArrayList<>(body);
            s.times = times;
            for (Step b : body) {
                s.engineSpell |= b.isSpell();
            }
            return s;
        }

        /** The step's identity for loop detection: kind, host, ability, cost, targets and forced answers. */
        String loopKey() {
            if (pass) {
                return toEnd ? "PASS_END" : "PASS";
            }
            if (kind == AbilKind.REPEAT) {
                return "REPEAT" + times + body;
            }
            return kind + "|" + hostId + "|" + desc + "|" + costKey + "|" + targetRefs + "|" + triggerRef + "|" + pickName
                    + "|" + floatColor + "|" + targetVariant + (cac == null ? "" : "|cac" + cac)
                    + (payFloats == null ? "" : "|pay" + payFloats) + (xValue >= 0 ? "|X" + xValue : "")
                    + (free ? "|free" : "") + (sacIds.isEmpty() ? "" : "|sac" + sacIds) + (castFree >= 0 ? "|cast" + castFree : "")
                    + (objTarget >= 0 ? "|obj" + objTarget : "");
        }

        boolean isSpell() {
            return kind == AbilKind.SPELL || engineSpell;
        }

        @Override
        public String toString() {
            if (pass) {
                return toEnd ? "PASS>END" : "PASS";
            }
            if (kind == AbilKind.ATTACK) {
                return "ATTACK";
            }
            if (kind == AbilKind.MAIN) {
                return "MAIN";
            }
            if (kind == AbilKind.REPEAT) {
                return "REPEAT" + times + "x" + body;
            }
            return (free ? "FREE-" : "") + kind + ":" + hostName + (floatColor >= 0 ? "->" + ForcedWinModel.SHORT[floatColor] : "")
                    + (optCosts == null ? "" : "+" + optCosts) + (sacIds.isEmpty() ? "" : " sac" + sacIds)
                    + (castFree >= 0 ? " cast" + castFree : "") + (objTarget >= 0 ? " on[" + objTarget + "]" : "")
                    + (targetRefs.isEmpty() ? "" : "->" + targetRefs) + (triggerRef == null ? "" : " trigger->" + triggerRef)
                    + (pickName == null ? "" : " pick=" + pickName)
                    + (targetVariant != null ? "@" + targetVariant : "")
                    + (pile.isEmpty() ? "" : " pile" + pile) + (picks.isEmpty() ? "" : " pick" + picks)
                    + (putBack.isEmpty() ? "" : " putBack" + putBack) + (phyrexianLife ? " phyLife" : "")
                    + (anyColors.isEmpty() ? "" : " as" + anyColors)
                    + (cacPlayed != null ? " cac" + cacPlayed : cac != null ? " cac" + cac : "")
                    + (payFloats == null ? "" : " pay" + payFloats) + (xValue >= 0 ? " X=" + xValue : "");
        }
    }

    /** A winning line of the abstract model. */
    static final class Line {
        final List<Step> steps;
        /**
         * solver-cap12-1030 R2-4 (c), engineAnyWindow only (null = the line starts in our main phase, as before): the
         * window an engine line was found in (the opponent's end step, say) and its turn; the follower also acts there.
         */
        forge.game.phase.PhaseType window;
        /**
         * solver-cap12-1030 R3-5, engineStack only (false = off): the line starts with the opponent's item on the stack
         * (a response): the follower acts while the top of the stack is not ours.
         */
        boolean stackStart;
        int windowTurn = -1;
        /** solver-gaps-1104 (engineAltLines; empty otherwise): the engine's other winning lines, in search order. */
        final List<Line> alternates = new ArrayList<>();
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
        /** The play-outs keep this many cards on top of our library in place (the line was planned on a known top). */
        int knownTop;
        /**
         * line-adoption-1107 (solverCfg anyWindow): planned outside our main phases; its steps before the first PASS or MAIN
         * are played in the window it was taken in.
         */
        boolean offMain;

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
        /**
         * solver-distill-cc-1109 (GOLDFISH; 0 = off, nothing changes): on the first quietTurns turns of the plan the only
         * moves are a land drop and the pass, so a plan of several turns spends its nodes on the turns that decide it.
         */
        int quietTurns = 0;
        /**
         * solver-distill-cc-1109 (planLoops; false = off, nothing changes): a permanent in play whose model holds a loop
         * partner ({@link ForcedWinModel.ACard#loopWith}) that is in play too wins from our main phase (untapped, and
         * not entered this turn unless it has haste). Screens only: such a line names no targets, so the solver's
         * root plan never follows it (the engine finds and plays the real line there).
         */
        boolean loops;
        int classes = 14;

        long ms = 1500;
        /**
         * solver-gaps-1015 (payment search, off by default): how many payment orders each cast is tried with. 1 = the
         * greedy payment ("the way Forge AI pays"); {@link #PAY_POLICIES} = also one-shot sources first, most flexible
         * sources first, keep each colour, Phyrexian pips with life.
         */
        int payPolicies = 1;
        /**
         * labeller-v2 (false = v1): (c) SCRY / SURVEIL n branch over each kept / away split and order of the top
         * min(n, planScryMax) entries (deduped by the known library prefix, and for surveil the graveyard added), and a
         * DISCARD branches over hand classes; (e) each cast branches over its distinct untapped-remainder payments
         * (payVariants, by the payment orders).
         */
        boolean planScry, planDiscard, payRecord;
        int planScryMax = 3, payVariants = 3;
        /** next-turn-1107 (evalAvoidLost): card ids of ours that are gone once we pass the turn (lost in a verification). */
        Set<Integer> goneAtPass = Set.of();
        /**
         * ledger-sweep2-1107 (solverCfg planExpandCap; false = off, the default, and nothing changes): one node's expansion
         * (every successor its abilities' effects branch into, before any is searched) makes at most as many branches as
         * the planner has nodes left. A branch past that could never be visited, but building it costs memory and time
         * the node budget does not see: a draw of k lazily chosen pile positions branches into classes^k states (ledger
         * row 66: 15^5 copies under one Memory Jar activation after Doomsday, then the JVM's heap ran out).
         */
        boolean expandCap;
        /**
         * planner-fixes-1110 (solverCfg planOrder; 0 = off, the default, and nothing changes): where the search spends its
         * nodes. A sum of:
         * <ul>
         * <li>{@link #ORDER_LAZY}: a card drawn from an ordered library choice (a LAZY pile position) stays unchosen in
         * our hand until the line plays it (one move per class it could be and ability of that class we can pay now), is
         * discarded (as the least useful card left for the pile) or is put back (still unchosen). Off, every such draw
         * branches at once over each class (classes^k states for k draws, times the discard choices), whether or not the
         * line ever plays the card. Unchosen cards left in hand at the win are filled in when the line is recorded: up to
         * two counters (cheapest first: the ranking's own protection cap), then by class order.</li>
         * <li>{@link #ORDER_WIN_FIRST}: a node's moves that win at once come first; a win-condition cast that does not win
         * and leaves nothing behind (an entering win whose library condition is not met, a storm drain short of lethal)
         * goes after the node's other moves, not before them.</li>
         * <li>{@link #ORDER_SHALLOW}: before the depth-first search, depth-limited passes (2 moves, 3, ...) on at most
         * {@link #SHALLOW_PCT} percent of the nodes, so a line of a few moves is found whatever a deep subtree ahead of it
         * costs. The depth-first search then runs with the nodes left.</li>
         * </ul>
         * Measured on the owner's 54 Doomsday decisions (planner alone, 30,000 nodes a search): off 18 positions with a
         * line, 762,584 nodes, 15 searches cut by the allowance; 1: 24, 291,722, 4; 3: 24, 237,863, 2; 7: 24, 311,883, 2.
         */
        int order = 0;
        static final int ORDER_LAZY = 1, ORDER_WIN_FIRST = 2, ORDER_SHALLOW = 4;
        /**
         * planOrder (ORDER_SHALLOW): the share of the nodes the depth-limited passes may use, in percent. At 10 the
         * passes reach 4 moves in every measured position and 5 or 6 in most; at 50 they took the nodes a long line's
         * depth-first search needed (a 19-step kill was found at 36,270 nodes instead of 15,170).
         */
        static final int SHALLOW_PCT = 10;
        /**
         * sdcc-tutorcast-1110 (solverCfg tutorCast; false = off, the default, and then nothing changes): an ability with
         * an observed "sacrifice another permanent" cost branches over the permanents the engine accepts, and an
         * observed {@link EffKind#TUTOR_CAST} branches over the library's cards as a tutor does: a card the engine
         * offered to cast for free is cast (its own step, {@link Step#free}, once the searching spell has resolved),
         * any other goes to our hand when that was observed.
         */
        boolean tutorCast;
        /**
         * sdcc-probeobj-1111 (solverCfg probeObjects; false = off, the default, and then nothing changes): an ability
         * with an observed object need ({@link ForcedWinModel.ObjNeed}) is a move when a permanent of ours in play has a
         * name a sandbox played it with and the width model can express what that run did. One branch per such
         * permanent (by name and tapped state): the object is sacrificed or targeted, goes where the sandbox left it,
         * and the effects observed with that object resolve.
         */
        boolean probeObjects;
        /**
         * sdcc-bridge-1111 (solverCfg planBridge; false = off, the default, and then nothing changes): the search also
         * notes its setup lines ({@link Setup}, in {@link Result#setups}): the modeled steps that lead to a position
         * where we can pay for an ability whose outcome the width model cannot express (an opaque step). They are not
         * lines (nothing is won in the model); the solver hands each to the engine search. The search itself, its nodes
         * and its lines are unchanged.
         */
        boolean bridge;
    }

    /**
     * sdcc-bridge-1111 (planBridge): a setup line. {@link #prefix}: the modeled steps (possibly none) after which the
     * card {@link #hostId} has an ability we can pay for whose outcome the width model has no effects for: an ability
     * a sandbox played with an object of ours ({@link #objName}) and saw do something it could not map (a token or a
     * library card entered the battlefield) or nothing it knows (an Aura attached), or a card the probes marked
     * unmodeled. {@link #cls} orders them: 0 = the sandbox saw the ability playable again after itself, or the
     * engine found a loop with that object; 1 = it put a permanent onto the battlefield; 2 = other.
     */
    static final class Setup {
        Line prefix;
        int hostId;
        String hostName, desc, objName;
        int cls, modeled, turnIdx;
        boolean fromHand;

        @Override
        public String toString() {
            return "setup[c" + cls + " " + hostName + (objName == null ? "" : " with " + objName) + (fromHand ? " (hand)" : "")
                    + " after " + prefix.steps + "]";
        }
    }

    /** solver-gaps-1015: the payment orders of the bounded payment search (see {@link #sourceScore}). */
    static final int PAY_POLICIES = 9;

    static final class Result {
        final List<Line> lines = new ArrayList<>();
        int nodes;
        boolean exhausted;
        /** unified-search-1106 (report only): branches cut at the depth limit; which limit stopped the search ("" = none). */
        int depthStops;
        String stoppedBy = "";
        boolean sameTurn, nextTurn;
        long nanos;
        /** bvw-step1-build-1024 (read-only): nodes actually visited, and whether the wall time cut the search. */
        int visited;
        boolean timedOut;
        /** ledger-sweep2-1107 (report only): the most resolve steps one expansion took; expansions cut by planExpandCap. */
        int maxExpand, expandStops;
        /**
         * planner-fixes-1110 (planOrder's ORDER_SHALLOW; report only, 0 when off): the deepest depth-limited pass that ran
         * to its end, the nodes the passes used, and the pass depth and node count at which the first line was found (0 =
         * none, or found by the depth-first search).
         */
        int shallowDepth, shallowNodes, shallowLineDepth, firstLineAt = -1;
        boolean ordered;
        /** sdcc-bridge-1111 (planBridge; empty when off): the setup lines, best first, at most {@link #SETUP_KEEP}. */
        final List<Setup> setups = new ArrayList<>();
        static final int SETUP_KEEP = 12;

        String summary() {
            return "lines=" + lines.size() + " nodes=" + nodes + (exhausted ? "(budget)" : "") + (stoppedBy.isEmpty() ? "" : " stoppedBy=" + stoppedBy)
                    + (depthStops > 0 ? " depthStops=" + depthStops : "") + " sameTurn=" + sameTurn
                    + " nextTurn=" + nextTurn + " ms=" + nanos / 1_000_000 + (expandStops > 0 ? " expandStops=" + expandStops : "")
                    + (shallowNodes > 0 ? " shallow=d" + shallowDepth + "/" + shallowNodes
                            + (shallowLineDepth > 0 ? " lineAtD" + shallowLineDepth : "") : "")
                    + (ordered && firstLineAt >= 0 ? " firstLineAt=" + firstLineAt : "");
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
    /** planOrder (ORDER_LAZY): a put-back of the unchosen card of pile slot k is {@code PUTBACK_LAZY - k} until the line is recorded. */
    private static final int PUTBACK_LAZY = -1000;

    static final class Perm {
        int card;
        boolean tapped;
        boolean activated;
        int loyalty;
        /** blockers-1109: Position.BLOCK_* (a permanent that entered during the line blocks if untapped). */
        byte block = ForcedWinModel.Position.BLOCK_UNTAPPED;
        /** planLoops: it entered during this turn of the line (cleared at the pass). */
        boolean fresh;

        Perm copy() {
            final Perm p = new Perm();
            p.card = card;
            p.tapped = tapped;
            p.activated = activated;
            p.loyalty = loyalty;
            p.block = block;
            p.fresh = fresh;
            return p;
        }
    }

    final class S {
        int turnIdx;
        /** We may cast sorcery-speed things now (our main phase with an empty stack). */
        boolean sorcery;
        /** line-adoption-1107: before main phase 1 of our turn (anyWindow from our upkeep or draw step). */
        boolean preMain;
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
        /** solver-gaps-1015: graveyard cards the position did not read (they count when a graveyard is shuffled in). */
        int gyExtra;
        /** The current ordered library choice: slot -> card (-1 unassigned) and its candidates. */
        int[] pile;
        List<Integer> remaining = new ArrayList<>();
        int pileStep = -1;
        int devotion;
        boolean emptyDrawWin;
        boolean canDraw;
        List<Step> steps = new ArrayList<>();
        /**
         * solver-gaps-1015: each unit of floating multi-colour mana, oldest first: {producing step index, colour mask}
         * (Waterlogged Grove's mana is green or blue, not any colour).
         */
        List<int[]> anyQueue = new ArrayList<>();
        boolean won;
        String how = "";
        boolean dead;
        /** sdcc-tutorcast-1110: the card the ability being resolved casts for free once it has resolved (-1 = none). */
        int pendingFree = -1;

        S copy() {
            final S s = new S();
            s.pendingFree = pendingFree;
            s.turnIdx = turnIdx;
            s.sorcery = sorcery;
            s.preMain = preMain;
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
            s.gyExtra = gyExtra;
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
            s.anyQueue = new ArrayList<>(anyQueue);
            s.won = won;
            s.how = how;
            s.dead = dead;
            return s;
        }

        String key() {
            final StringBuilder sb = new StringBuilder();
            sb.append(turnIdx).append(sorcery ? 'S' : 'i').append(preMain ? "M" : "").append('/').append(life).append('/').append(oppLife).append('/').append(oppLib)
                    .append('/').append(storm).append(landDrop ? 'L' : 'l').append(Arrays.toString(pool)).append('h');
            sb.append(names(hand)).append('b');
            // permutations of the same permanents are one position (order matters only for returned subtypes)
            final List<String> perms = new ArrayList<>();
            for (Perm p : bf) {
                perms.add(cards.get(p.card).name + (p.tapped ? 't' : 'u') + (p.activated ? 'a' : '-') + p.loyalty
                        + (p.block == ForcedWinModel.Position.BLOCK_UNTAPPED ? "" : "b" + p.block));
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
            out.add(x < 0 ? "*" : cards.get(x).name); // planOrder (ORDER_LAZY): an unchosen pile card in hand
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
        final long t0 = forge.game.WorkClock.nanoTime();
        final Result r = new ForcedWinPlanner(pos, lim).run();
        r.nanos = forge.game.WorkClock.nanoTime() - t0;
        return r;
    }

    private long deadline = Long.MAX_VALUE;

    /**
     * planner-fixes-1110 (diagnostics only): -Dforge.ai.forcedWin.planProfile=true prints, per search, where its nodes
     * went: by depth and turn, by the line's first move and by the move that led to each node, the transposition hits
     * among them, and how wide the expansions were.
     */
    static final boolean PROFILE = Boolean.getBoolean("forge.ai.forcedWin.planProfile");
    private final int[] profDepth = new int[64], profHitDepth = new int[64], profTurn = new int[8], profFan = new int[7];
    private final Map<String, int[]> profRoot = new java.util.LinkedHashMap<>(), profLast = new java.util.LinkedHashMap<>();
    private int profHits;

    private static String profLabel(Step st) {
        return st.pass ? "PASS" : st.kind + ":" + st.hostName;
    }

    private void profNode(S s, int depth, boolean hit) {
        profDepth[Math.min(depth, 63)]++;
        profTurn[Math.min(s.turnIdx, 7)]++;
        if (hit) {
            profHits++;
            profHitDepth[Math.min(depth, 63)]++;
        }
        String root = "(root)";
        for (Step st : s.steps) {
            if (st.pass || st.kind != AbilKind.FLOAT) {
                root = profLabel(st);
                break;
            }
        }
        final String last = s.steps.isEmpty() ? "(root)" : profLabel(s.steps.get(s.steps.size() - 1));
        final int[] r = profRoot.computeIfAbsent(root, k -> new int[2]), l = profLast.computeIfAbsent(last, k -> new int[2]);
        r[0]++;
        l[0]++;
        r[1] += hit ? 1 : 0;
        l[1] += hit ? 1 : 0;
    }

    private static String profTop(Map<String, int[]> m, int n) {
        final List<Map.Entry<String, int[]>> es = new ArrayList<>(m.entrySet());
        es.sort((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]));
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, es.size()); i++) {
            sb.append(i == 0 ? "" : "; ").append(es.get(i).getKey()).append('=').append(es.get(i).getValue()[0]).append("(hit")
                    .append(es.get(i).getValue()[1]).append(')');
        }
        return sb.toString();
    }

    private void profPrint() {
        int maxD = 0;
        for (int i = 0; i < 64; i++) {
            maxD = profDepth[i] > 0 ? i : maxD;
        }
        System.err.println("FORCED_WIN planprofile horizon=" + lim.horizon + " order=" + lim.order + " " + result.summary()
                + " visited=" + result.visited + " hits=" + profHits + " maxExpand=" + result.maxExpand
                + " fan(1,2-3,4-15,16-63,64-255,256-1023,1024+)=" + Arrays.toString(profFan) + " byDepth="
                + Arrays.toString(Arrays.copyOf(profDepth, maxD + 1)) + " hitByDepth="
                + Arrays.toString(Arrays.copyOf(profHitDepth, maxD + 1)) + " byTurn=" + Arrays.toString(Arrays.copyOf(profTurn, 3)));
        System.err.println("FORCED_WIN planprofile byFirstMove: " + profTop(profRoot, 12));
        System.err.println("FORCED_WIN planprofile byLastMove: " + profTop(profLast, 30));
    }

    /** blockers-1109: passes whose blocker count differs from the pre-fix count (every creature); diagnostic only. */
    private int blockerDiffs;

    Result run() {
        deadline = lim.ms < 0 ? Long.MAX_VALUE : forge.game.WorkClock.nanoTime() + lim.ms * 1_000_000L;
        final S s = new S();
        s.sorcery = pos.sorcerySpeed;
        s.preMain = pos.preMain;
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
            p.activated = i < pos.used.size() && pos.used.get(i);
            if (i < pos.block.size()) {
                p.block = pos.block.get(i);
            }
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
        s.gyExtra = Math.max(0, pos.graveyardSize - pos.graveyard.size());
        // solver-gaps-1015: the known top (pile memory) is KNOWN entries, the rest of the library UNKNOWN
        for (ACard a : pos.knownTop) {
            s.lib.add(idx(a));
        }
        for (int i = pos.knownTop.size(); i < pos.librarySize; i++) {
            s.lib.add(UNKNOWN);
        }
        nodeCap = lim.nodes;
        depthCap = lim.depth;
        result.ordered = lim.order != 0;
        if ((lim.order & Limits.ORDER_SHALLOW) == 0 || !shallow(s)) {
            dfs(s, 0);
        }
        rank();
        if (lim.bridge) {
            // sdcc-bridge-1111: the most promising first: repetition seen, then a new permanent; this turn before the
            // next; the fewest modeled steps
            final List<Setup> all = new ArrayList<>(setupByKey.values());
            all.sort(Comparator.comparingInt((Setup x) -> x.cls).thenComparingInt(x -> x.turnIdx)
                    .thenComparingInt(x -> x.modeled).thenComparing(x -> x.hostName + "|" + x.desc + "|" + x.objName)
                    .thenComparingInt(x -> x.hostId));
            result.setups.addAll(all.subList(0, Math.min(all.size(), Result.SETUP_KEEP)));
        }
        result.nodes = nodes;
        result.exhausted |= nodes >= lim.nodes; // kill-line-1106: a stop at the node limit counts as exhausted (report only)
        for (Line l : result.lines) {
            l.knownTop = pos.knownTop.size();
        }
        if (PROFILE) {
            profPrint();
        }
        if (blockerDiffs > 0) {
            // blockers-1109 (ledger row 81): this plan saw a pass where a tapped or can't-block creature no longer blocks
            System.err.println("FORCED_WIN blockers1109 diffs=" + blockerDiffs + " turn=" + pos.turn + " life=" + pos.life
                    + " lines=" + result.lines.size());
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
        if (nodes >= nodeCap || result.lines.size() >= lim.maxLines || (nodes & 63) == 0 && forge.game.WorkClock.nanoTime() > deadline) {
            if (shallowPass && nodes < lim.nodes && result.lines.size() < lim.maxLines
                    && forge.game.WorkClock.nanoTime() <= deadline) {
                passCut = true; // planOrder: the pass's share of the nodes is spent, not the search's allowance
                return;
            }
            result.exhausted |= nodes >= lim.nodes || forge.game.WorkClock.nanoTime() > deadline;
            if (result.stoppedBy.isEmpty() && result.exhausted) {
                result.stoppedBy = nodes >= lim.nodes ? "nodes" : "time"; // unified-search-1106
            }
            if (forge.game.WorkClock.nanoTime() > deadline) {
                result.timedOut = true;
                nodes = Math.max(nodes, lim.nodes);
            }
            return;
        }
        if (depth >= depthCap) {
            if (shallowPass) {
                passCut = true; // planOrder: a deeper pass, or the depth-first search, goes on from here
            } else {
                result.depthStops++; // unified-search-1106
            }
            return;
        }
        nodes++;
        forge.game.WorkClock.tick(forge.game.WorkClock.Site.PLAN_NODE); // work-units-1107 (no clock = no-op)
        result.visited++;
        if (shallowPass ? !seenDeeper(s.key(), depthCap - depth) : !seen.add(s.key())) {
            if (PROFILE) {
                profNode(s, depth, true);
            }
            return;
        }
        if (PROFILE) {
            profNode(s, depth, false);
        }
        final List<S> succ = successors(s);
        if (PROFILE) {
            final int n = succ.size();
            profFan[n <= 1 ? 0 : n <= 3 ? 1 : n <= 15 ? 2 : n <= 63 ? 3 : n <= 255 ? 4 : n <= 1023 ? 5 : 6]++;
        }
        for (S next : succ) {
            dfs(next, depth + 1);
            if (nodes >= nodeCap || result.lines.size() >= lim.maxLines) {
                return;
            }
        }
    }

    /**
     * planner-fixes-1110 (planOrder; lim.nodes and lim.depth when off): the node and depth caps of the search in
     * progress. A depth-limited pass ({@link #shallow}) runs under its own.
     */
    private int nodeCap, depthCap;
    /** planOrder (ORDER_SHALLOW): a depth-limited pass is running; one of its branches was cut (depth, share or expansion). */
    private boolean shallowPass, passCut;
    /** planOrder (ORDER_SHALLOW): per position key, the most moves still allowed when a pass searched it. */
    private final Map<String, Integer> seenAt = new HashMap<>();

    /** planOrder: the position was not yet searched with at least {@code left} moves to go in this pass (and now is). */
    private boolean seenDeeper(String key, int left) {
        final Integer prev = seenAt.get(key);
        if (prev != null && prev >= left) {
            return false;
        }
        seenAt.put(key, left);
        return true;
    }

    /**
     * planner-fixes-1110 (planOrder's ORDER_SHALLOW): depth-limited passes from the root, 2 moves deep, then 3, ..., while
     * they stay inside their share of the nodes ({@link Limits#SHALLOW_PCT}). A depth-first search spends its whole
     * allowance under the first moves it tries when their subtrees are large; a line of a few moves elsewhere is then
     * never reached. Each pass is the same search with a depth cap (a position is searched again only with more moves
     * to go than before), so what it finds are the lines of at most that many moves. The passes end at the first one
     * that finds a line; the depth-first search then runs with the nodes left. True = a pass searched the whole tree
     * (no branch cut): nothing is left for the depth-first search.
     */
    private boolean shallow(S root) {
        final int share = (int) ((long) lim.nodes * Limits.SHALLOW_PCT / 100);
        boolean whole = false;
        for (int d = 2; d < lim.depth && nodes < share; d++) {
            final int lines0 = result.lines.size();
            shallowPass = true;
            passCut = false;
            depthCap = d;
            nodeCap = share;
            seenAt.clear();
            dfs(root, 0);
            shallowPass = false;
            if (nodes >= lim.nodes || result.lines.size() >= lim.maxLines) {
                break; // the search's own limit (or its time: nodes is then at the limit)
            }
            if (!passCut) {
                result.shallowDepth = d;
                whole = true;
                break;
            }
            if (nodes >= share) {
                break; // cut by its share: not searched to its depth
            }
            result.shallowDepth = d;
            if (result.lines.size() > lines0) {
                result.shallowLineDepth = d;
                break;
            }
        }
        seenAt.clear();
        result.shallowNodes = nodes;
        nodeCap = lim.nodes;
        depthCap = lim.depth;
        return whole;
    }

    /** ledger-sweep2-1107 (planExpandCap): branches built in the current expansion, and the most it may build. */
    private int expandCalls, expandLimit = Integer.MAX_VALUE;
    private boolean expandCut;

    private List<S> successors(S s) {
        expandCalls = 0;
        expandCut = false;
        expandLimit = lim.expandCap ? Math.max(1, nodeCap - nodes) : Integer.MAX_VALUE;
        final List<S> out0 = successors0(s);
        result.maxExpand = Math.max(result.maxExpand, expandCalls);
        if (expandCut && shallowPass) {
            passCut = true; // planOrder: cut by the pass's share, not by the search's allowance
        } else if (expandCut) {
            result.expandStops++;
            result.exhausted = true;
            if (result.stoppedBy.isEmpty()) {
                result.stoppedBy = "expand";
            }
        }
        return out0;
    }

    private List<S> successors0(S s) {
        final List<S> first = new ArrayList<>(), second = new ArrayList<>(), third = new ArrayList<>();
        // planner-fixes-1110 (planOrder's ORDER_WIN_FIRST; both stay empty when off): the moves that win at once, and the
        // win-condition casts that neither win nor leave a way to win behind
        final List<S> wins = new ArrayList<>(), late = new ArrayList<>();
        final boolean winFirst = (lim.order & Limits.ORDER_WIN_FIRST) != 0;
        final Set<String> tried = new HashSet<>();
        final boolean pending = pos.pendingId >= 0 && s.steps.isEmpty();
        final boolean quiet = !pending && s.turnIdx < lim.quietTurns; // GOLDFISH: lands and the pass only
        if (lim.bridge && !pending && !quiet) {
            bridgeMoves(s); // sdcc-bridge-1111 (notes setup lines; the node's own moves are unchanged)
        }
        int lazyAt = -1;
        for (int hi = 0; hi < s.hand.size(); hi++) {
            final int h = s.hand.get(hi);
            if (h < 0) {
                // planOrder (ORDER_LAZY): an unchosen pile card; its moves follow the hand's own (below)
                lazyAt = lazyAt < 0 ? hi : lazyAt;
                continue;
            }
            final ACard a = cards.get(h);
            if (pending && a.id != pos.pendingId) {
                continue;
            }
            if (lim.loops && !pending && !quiet && a.loopFromHand && !a.loopWith.isEmpty() && s.sorcery) {
                // planLoops: an Aura cast on a partner in play since before this turn (it must tap): pay it, then the loop
                boolean partner = false;
                for (Perm q : s.bf) {
                    partner |= !q.fresh && a.loopWith.contains(cards.get(q.card).name);
                }
                for (Abil ab : a.fromHand) {
                    if (partner && ab.kind == AbilKind.SPELL && tried.add(a.name + "|loop|" + ab.costKey)) {
                        for (S n : apply(s, hi, -1, a, ab)) {
                            if (!n.dead) {
                                n.won = true;
                                n.how = "loop";
                                (winFirst ? wins : first).add(n);
                            }
                        }
                    }
                }
            }
            for (Abil ab : a.fromHand) {
                final boolean objAb = objMove(ab) && !pending; // sdcc-probeobj-1111 (false when off)
                if (ab.unplayable || ab.cardTargets && ab.kind != AbilKind.LAND && !objAb) {
                    continue;
                }
                if (pending && ab.kind != AbilKind.SPELL) {
                    continue;
                }
                if (quiet && ab.kind != AbilKind.LAND) {
                    continue;
                }
                final String k = a.name + "|" + ab.kind + "|" + ab.costKey + "|" + ab.desc + "|" + ab.targetVariant;
                if (!tried.add(k) || !pending && !(objAb ? (s.sorcery || ab.instant) && handOk(s, ab) && hasObject(s, ab, -1)
                        : allowed(s, a, ab))) {
                    continue;
                }
                final List<S> into = isWinny(a, ab) || ab.has(EffKind.PILE) ? first
                        : ab.has(EffKind.DRAW) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP)
                                || ab.has(EffKind.TUTOR_CAST) ? second : third;
                if (winFirst) {
                    place(apply(s, hi, -1, a, ab), into, isWinny(a, ab) && !staysAsWin(a, ab), wins, late);
                } else {
                    into.addAll(apply(s, hi, -1, a, ab));
                }
            }
        }
        if (lazyAt >= 0 && !pending) {
            // planOrder (ORDER_LAZY): an unchosen pile card in hand is played as any class of card still open to the
            // pile, chosen by the move that plays it. The classes are those a draw branches over when the key is off:
            // the first lim.classes, and one more per other unchosen card in hand (off, each of those was drawn as a
            // class of its own first, which let the next draw reach one class further).
            int lazies = 0;
            for (int h : s.hand) {
                lazies += h < 0 ? 1 : 0;
            }
            for (int c : classes(s.remaining, lim.classes + lazies - 1)) {
                final ACard a = cards.get(c);
                for (Abil ab : a.fromHand) {
                    if (ab.unplayable || ab.cardTargets && ab.kind != AbilKind.LAND || quiet && ab.kind != AbilKind.LAND) {
                        continue;
                    }
                    final String k = a.name + "|" + ab.kind + "|" + ab.costKey + "|" + ab.desc + "|" + ab.targetVariant;
                    if (!tried.add(k) || !allowed(s, a, ab) || !affordable(s, ab)) {
                        continue;
                    }
                    final S b = s.copy();
                    bindHand(b, lazyAt, c);
                    final List<S> into = isWinny(a, ab) || ab.has(EffKind.PILE) ? first
                            : ab.has(EffKind.DRAW) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP)
                                || ab.has(EffKind.TUTOR_CAST) ? second : third;
                    final List<S> res = apply(b, s, lazyAt, -1, a, ab);
                    if (winFirst) {
                        place(res, into, isWinny(a, ab) && !staysAsWin(a, ab), wins, late);
                    } else {
                        into.addAll(res);
                    }
                }
            }
        }
        for (int bi = 0; bi < s.bf.size() && !quiet; bi++) {
            final Perm p = s.bf.get(bi);
            final ACard a = cards.get(p.card);
            if (lim.loops && !pending && !a.loopWith.isEmpty() && s.sorcery && !p.tapped && (!p.fresh || a.loopHaste)) {
                for (Perm q : s.bf) {
                    final ACard b = cards.get(q.card);
                    if (q != p && a.loopWith.contains(b.name)) {
                        final S n = s.copy();
                        n.steps.add(Step.engine(AbilKind.ACTIVATE, a.id, a.name, "loop with " + b.name, "", false));
                        n.won = true;
                        n.how = "loop";
                        (winFirst ? wins : first).add(n);
                        break;
                    }
                }
            }
            for (Abil ab : a.onBattlefield) {
                final boolean objAb = objMove(ab); // sdcc-probeobj-1111 (false when off)
                if (ab.unplayable || ab.cardTargets && !objAb) {
                    continue;
                }
                final String k = "bf" + bi + "|" + ab.desc + "|" + ab.costKey + "|" + ab.targetVariant;
                if (!tried.add(k) || !(objAb ? allowedBfCost(s, p, ab) && hasObject(s, ab, bi) : allowedBf(s, p, ab))) {
                    continue;
                }
                final List<S> into = ab.has(EffKind.DRAW) || ab.has(EffKind.WIN) ? second : third;
                if (winFirst) {
                    place(apply(s, -1, bi, a, ab), into, false, wins, late);
                } else {
                    into.addAll(apply(s, -1, bi, a, ab));
                }
            }
        }
        final List<S> out = new ArrayList<>(wins);
        out.addAll(first);
        out.addAll(second);
        out.addAll(third);
        out.addAll(late);
        if (s.preMain && !pending) {
            // line-adoption-1107: from our upkeep or draw step, the turn goes on to its main phase (not to our next turn)
            final S n = s.copy();
            n.steps.add(Step.MAIN);
            n.preMain = false;
            n.sorcery = true;
            Arrays.fill(n.pool, 0);
            n.anyQueue.clear();
            if (pos.preMainDraw) {
                resolve(n, List.of(new Eff(EffKind.DRAW, 1, 0)), 0, 0, null, out);
            } else {
                out.add(n);
            }
            return out;
        }
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

    /**
     * planOrder (ORDER_WIN_FIRST): a win-condition move that leaves a way to win behind even when it does not win at
     * once: a permanent that makes a draw from an empty library a win, a pile, or cards milled from the opponent.
     */
    private static boolean staysAsWin(ACard a, Abil ab) {
        return ab.has(EffKind.PILE) || ab.has(EffKind.MILL_OPP) || ab.kind == AbilKind.SPELL && a.permanent && a.emptyDrawWin;
    }

    /** planOrder (ORDER_WIN_FIRST): one move's results by what they did: won at once, a spent win condition, the rest. */
    private static void place(List<S> res, List<S> into, boolean spentIfNoWin, List<S> wins, List<S> late) {
        for (S n : res) {
            (n.won ? wins : spentIfNoWin ? late : into).add(n);
        }
    }

    /**
     * planOrder (ORDER_LAZY): a necessary condition of {@link #pay}, checked before a move's state is built: the mana in
     * the pool and in the untapped plain sources covers the cost's mana (Phyrexian pips can be life).
     */
    private boolean affordable(S s, Abil ab) {
        int need = ab.generic + ab.hybrid.size();
        for (int c = 0; c < 6; c++) {
            need += ab.pips[c];
        }
        for (int i = 0; i < 7 && need > 0; i++) {
            need -= s.pool[i];
        }
        for (int i = 0; i < s.bf.size() && need > 0; i++) {
            final Perm p = s.bf.get(i);
            final ACard a = cards.get(p.card);
            if (!p.tapped && a.sourceColors != 0 && !a.sourceSick) {
                need -= Math.max(1, a.sourceAmount);
            }
        }
        return need <= 0;
    }

    /** planOrder (ORDER_LAZY): the unchosen card at {@code hi} of the hand is {@code card} (one of {@code s.remaining}). */
    private void bindHand(S s, int hi, int card) {
        assign(s, s.hand.get(hi), card);
        s.hand.set(hi, card);
    }

    /** planOrder (ORDER_LAZY): the first unchosen card of the hand, or -1. */
    private static int lazyIn(List<Integer> hand) {
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i) < 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * planOrder (ORDER_LAZY): an unchosen card of the hand leaves it for the graveyard (a discard): it is the least
     * useful card still open to the pile, as a milled pile position is. False = the hand holds none.
     */
    private boolean discardLazy(S s) {
        final int hi = lazyIn(s.hand);
        if (hi < 0) {
            return false;
        }
        final int f = leftover(s.remaining);
        if (f >= 0) {
            assign(s, s.hand.get(hi), f);
            s.gyPool.add(f);
        }
        s.hand.remove(hi);
        return true;
    }

    /** solver-gaps-1015: an observed hand-size condition (Library of Alexandria's "exactly seven cards"). */
    private static boolean handOk(S s, Abil ab) {
        return ab.handMask == -1 || (ab.handMask >> Math.min(11, s.hand.size()) & 1) != 0;
    }

    private boolean allowed(S s, ACard a, Abil ab) {
        if (ab.kind == AbilKind.LAND) {
            return s.landDrop && s.sorcery;
        }
        if (!s.sorcery && !ab.instant || !handOk(s, ab)) {
            return false;
        }
        if (ab.kind == AbilKind.SPELL && a.permanent) {
            return true;
        }
        return !ab.effects.isEmpty();
    }

    private boolean allowedBf(S s, Perm p, Abil ab) {
        return allowedBfCost(s, p, ab) && !ab.effects.isEmpty();
    }

    private boolean allowedBfCost(S s, Perm p, Abil ab) {
        if (!s.sorcery && !ab.instant && ab.kind != AbilKind.FLOAT || !handOk(s, ab)) {
            return false;
        }
        if (ab.tapSelf && p.tapped) {
            return false;
        }
        if (ab.loyalty && (p.activated || p.loyalty + ab.loyaltyDelta < 0)) {
            return false;
        }
        return true;
    }

    /** sdcc-probeobj-1111: the ability is planned through its observed object need (never with the key off). */
    private boolean objMove(Abil ab) {
        return lim.probeObjects && ab.obj != null;
    }

    /**
     * sdcc-probeobj-1111: the places of {@code s} that hold an object the ability was played with, usably: indices of
     * our battlefield (not {@code selfIdx}), or of our known graveyard for a need there; one per card name (and, in play,
     * tapped state).
     */
    private List<Integer> objects(S s, Abil ab, int selfIdx) {
        final List<Integer> out = new ArrayList<>();
        final Set<String> kinds = new HashSet<>();
        final boolean gy = "Graveyard".equals(ab.obj.zone);
        final int n = gy ? s.gyPool.size() : s.bf.size();
        for (int i = 0; i < n; i++) {
            final int c = gy ? s.gyPool.get(i) : s.bf.get(i).card;
            if (c < 0 || c >= cards.size() || !gy && i == selfIdx) {
                continue;
            }
            final ACard pc = cards.get(c);
            final ForcedWinModel.ObjSeen seen = ab.obj.seen.get(pc.name);
            if (seen != null && seen.usable() && kinds.add(pc.name + (!gy && s.bf.get(i).tapped ? "|t" : "|u"))) {
                out.add(i);
            }
        }
        return out;
    }

    private boolean hasObject(S s, Abil ab, int selfIdx) {
        return !objects(s, ab, selfIdx).isEmpty();
    }

    /**
     * sdcc-probeobj-1111: between the mana payment and {@link #afterPay}: one branch per object of ours the ability was
     * played with in a sandbox (for a sacrifice the cheapest loss first). The object leaves as the sandbox saw it leave
     * (sacrificed, returned to our hand, exiled), and the effects that run observed resolve: they hold what the object's
     * leaving caused, so nothing is added for it.
     */
    private void objBranches(S s, Step step, Perm self, int host, int handIdx, ACard a, Abil ab, List<S> out) {
        final ForcedWinModel.ObjNeed need = ab.obj;
        final boolean sac = ForcedWinModel.ObjNeed.SAC.equals(need.role);
        final boolean gy = "Graveyard".equals(need.zone);
        final int selfIdx = self == null ? -1 : s.bf.indexOf(self);
        final List<Integer> choices = objects(s, ab, selfIdx);
        if (sac) {
            choices.sort(Comparator.comparingInt((Integer i) -> s.bf.get(i).tapped ? 0 : 1)
                    .thenComparingInt(i -> cards.get(s.bf.get(i).card).sourceColors == 0 ? 0 : 1)
                    .thenComparingInt(i -> cards.get(s.bf.get(i).card).onBattlefield.size())
                    .thenComparingInt(i -> cards.get(s.bf.get(i).card).id));
        }
        for (int i : choices.subList(0, Math.min(choices.size(), lim.classes))) {
            final S v = s.copy();
            final Step st = step.copy();
            final Perm vSelf = selfIdx < 0 ? null : v.bf.get(selfIdx);
            final int card = gy ? v.gyPool.get(i) : v.bf.get(i).card;
            final ACard pc = cards.get(card);
            final ForcedWinModel.ObjSeen seen = need.seen.get(pc.name);
            if (sac) {
                st.sacIds.add(pc.id);
            } else {
                st.objTarget = pc.id;
                st.objLink = need.link;
            }
            if (seen.to != 0 && gy) {
                v.gyPool.remove(i);
            } else if (seen.to != 0) {
                v.bf.remove(i);
                v.devotion -= pc.bluePips;
                if (pc.emptyDrawWin) {
                    v.emptyDrawWin = false;
                    for (Perm q : v.bf) {
                        v.emptyDrawWin |= cards.get(q.card).emptyDrawWin;
                    }
                }
                if (seen.to == Abil.SELF_GRAVEYARD) {
                    v.gyPool.add(card);
                }
            }
            if (seen.to == Abil.SELF_HAND) {
                v.hand.add(card);
            }
            afterPay(v, st, vSelf, host, handIdx, a, seen.abil, false, out, null);
        }
    }

    private List<S> apply(S s0, int handIdx, int bfIdx, ACard a, Abil ab) {
        return apply(s0.copy(), s0, handIdx, bfIdx, a, ab);
    }

    /** As above on {@code s}, a copy of the node {@code s0} the caller made (planOrder: with the played card chosen). */
    private List<S> apply(S s, S s0, int handIdx, int bfIdx, ACard a, Abil ab) {
        final List<S> out = new ArrayList<>();
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
        if (!free && lim.payRecord && lim.payPolicies <= 1) {
            payVariants(s, ab, self, step, host, handIdx, a, out);
            return out;
        }
        if (free || lim.payPolicies <= 1) {
            if (!free && !pay(s, ab, self, step)) {
                return out;
            }
            afterCost(s, step, self, host, handIdx, a, ab, free, out);
            return out;
        }
        // solver-gaps-1015: the bounded payment search: the same cast paid in each payment order, distinct results only
        final int selfIdx = self == null ? -1 : s.bf.indexOf(self);
        final Set<String> paid = new HashSet<>();
        for (int pol = 0; pol < lim.payPolicies; pol++) {
            final S v = s.copy();
            final Step st = step.copy();
            final Perm vSelf = selfIdx < 0 ? null : v.bf.get(selfIdx);
            payPolicy = pol;
            final boolean ok = pay(v, ab, vSelf, st);
            payPolicy = 0;
            if (!ok || !paid.add(v.key() + st.phyrexianLife)) {
                continue;
            }
            afterCost(v, st, vSelf, host, handIdx, a, ab, false, out);
        }
        return out;
    }

    /**
     * labeller-v2 (e): the cast paid in each payment order (greedy first), one branch per distinct untapped remainder
     * (the sorted names and colours of the untapped sources left, the pool, life and Phyrexian life), up to payVariants.
     * Each branch's line floats exactly the sources it paid with (FLOAT steps), as the greedy line does.
     */
    private void payVariants(S s, Abil ab, Perm self, Step step, int host, int handIdx, ACard a, List<S> out) {
        final int selfIdx = self == null ? -1 : s.bf.indexOf(self);
        final Set<String> paid = new HashSet<>();
        for (int pol = 0; pol < PAY_POLICIES && paid.size() < lim.payVariants; pol++) {
            final S v = s.copy();
            final Step st = step.copy();
            final Perm vSelf = selfIdx < 0 ? null : v.bf.get(selfIdx);
            payPolicy = pol;
            final boolean ok = pay(v, ab, vSelf, st);
            payPolicy = 0;
            if (!ok || !paid.add(remainder(v) + st.phyrexianLife)) {
                continue;
            }
            afterCost(v, st, vSelf, host, handIdx, a, ab, false, out);
        }
    }

    /** labeller-v2 (e): the untapped sources left (name and colours, sorted), the pool and life. */
    private String remainder(S s) {
        final List<String> left = new ArrayList<>();
        for (Perm p : s.bf) {
            final ACard c = cards.get(p.card);
            if (!p.tapped && c.sourceColors != 0) {
                left.add(c.name + "/" + c.sourceColors);
            }
        }
        java.util.Collections.sort(left);
        return left + Arrays.toString(s.pool) + s.life;
    }

    /**
     * sdcc-tutorcast-1110: between the mana payment and {@link #afterPay}: an observed "sacrifice another permanent"
     * cost branches over the permanents in play whose name the engine accepted (one per name and tapped state; tapped
     * and plain ones first, so a source tapped for this very spell is the first choice). Without such a cost, or with
     * the key off, exactly {@link #afterPay}.
     */
    private void afterCost(S s, Step step, Perm self, int host, int handIdx, ACard a, Abil ab, boolean free, List<S> out) {
        if (objMove(ab) && !free) {
            objBranches(s, step, self, host, handIdx, a, ab, out); // sdcc-probeobj-1111
            return;
        }
        if (!lim.tutorCast || ab.sacN <= 0 || free) {
            afterPay(s, step, self, host, handIdx, a, ab, free, out, null);
            return;
        }
        if (ab.sacNames == null) {
            return;
        }
        final int selfIdx = self == null ? -1 : s.bf.indexOf(self);
        final List<Integer> choices = new ArrayList<>();
        final Set<String> kinds = new HashSet<>();
        for (int i = 0; i < s.bf.size(); i++) {
            final Perm p = s.bf.get(i);
            final ACard pc = cards.get(p.card);
            if (i != selfIdx && ab.sacNames.contains(pc.name) && kinds.add(pc.name + (p.tapped ? "|t" : "|u"))) {
                choices.add(i);
            }
        }
        // the cheapest loss first: a tapped permanent, then one that makes no mana, then one with fewer abilities
        choices.sort(Comparator.comparingInt((Integer i) -> s.bf.get(i).tapped ? 0 : 1)
                .thenComparingInt(i -> cards.get(s.bf.get(i).card).sourceColors == 0 ? 0 : 1)
                .thenComparingInt(i -> cards.get(s.bf.get(i).card).onBattlefield.size())
                .thenComparingInt(i -> cards.get(s.bf.get(i).card).id));
        for (int i : choices.subList(0, Math.min(choices.size(), lim.classes))) {
            final S v = s.copy();
            final Step st = step.copy();
            final Perm vSelf = selfIdx < 0 ? null : v.bf.get(selfIdx);
            final Perm gone = v.bf.remove(i);
            final ACard pc = cards.get(gone.card);
            v.gyPool.add(gone.card);
            v.devotion -= pc.bluePips;
            if (pc.emptyDrawWin) {
                v.emptyDrawWin = false;
                for (Perm q : v.bf) {
                    v.emptyDrawWin |= cards.get(q.card).emptyDrawWin;
                }
            }
            st.sacIds.add(pc.id);
            afterPay(v, st, vSelf, host, handIdx, a, ab, false, out, pc.dies);
        }
    }

    /**
     * The rest of {@link #apply} once the cost is paid: the step is played and its effects resolve into {@code out}.
     * {@code pre} (sdcc-tutorcast-1110; null = none): effects that resolve before the ability's own (what a permanent
     * sacrificed as its cost does on leaving play).
     */
    private void afterPay(S s, Step step, Perm self, int host, int handIdx, ACard a, Abil ab, boolean free, List<S> out,
            List<Eff> pre) {
        s.steps.add(step);
        if (handIdx >= 0) {
            s.hand.remove(Integer.valueOf(host));
        }
        if (ab.kind == AbilKind.LAND) {
            s.landDrop = false;
            final Perm p = new Perm();
            p.card = host;
            // sdcc-etb-1110 (landEntersProbe; null = off, or not known for this card): the engine's answer for this position
            // and the battlefield the line has built, not the template's from the card's first sight. A land that enters
            // tapped makes no mana this turn; the pass untaps it.
            final int[] entry = landEntry(s, host, a);
            p.tapped = entry == null ? a.entersTapped : entry[0] != 0;
            s.bf.add(p);
            // solver-gaps-1015: the life its entering cost (a shock land paid untapped), as observed
            s.life += Math.min(0, entry == null ? a.landLife : entry[1]);
            if (s.life <= 0) {
                return;
            }
            out.add(s);
            return;
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
        // sdcc-tutorcast-1110 (a sourceLeaves reader's template only): the ability takes its own source off the battlefield
        // sdcc-integ-1110: never together with an observed SELF_TO_LIBRARY effect (probeSettle), which moves it itself
        final Perm left = self != null && sacked == null && ab.selfTo != 0 && !ab.has(EffKind.SELF_TO_LIBRARY) ? self : null;
        if (ab.kind == AbilKind.CYCLE) {
            s.gyPool.add(host);
        }
        final List<Eff> effs = new ArrayList<>();
        if (pre != null) {
            effs.addAll(pre);
        }
        if (ab.kind == AbilKind.SPELL && a.permanent) {
            final Perm p = new Perm();
            p.card = host;
            p.loyalty = a.startLoyalty;
            p.fresh = true;
            s.bf.add(p);
            s.devotion += a.bluePips;
            s.emptyDrawWin |= a.emptyDrawWin;
            if (a.winsOnEntry) {
                s.won = true;
                s.how = "enters";
                out.add(s);
                return;
            }
            if (a.winIfLibraryAtMost >= 0) {
                if (s.lib.size() <= a.winIfLibraryAtMost + (s.devotion - a.winDevotion)) {
                    s.won = true;
                    s.how = "enters(library<=" + (a.winIfLibraryAtMost + s.devotion - a.winDevotion) + ")";
                }
                out.add(s);
                return;
            }
            effs.addAll(ab.effects);
        } else {
            effs.addAll(ab.effects);
        }
        // an instant or sorcery goes to our graveyard after it resolves (not while it resolves: Doomsday's own pile)
        // sdcc-draws-1110: unless it was observed going to our library instead (a SELF_TO_LIBRARY effect, probeSettle only)
        final boolean toGraveyard = (handIdx >= 0 || step.free) && ab.kind == AbilKind.SPELL && !a.permanent
                && !ab.has(EffKind.SELF_TO_LIBRARY);
        if (sacked != null) {
            // the ability's observed (or folded-in) effects already hold what the sacrifice itself causes
            s.bf.remove(sacked);
            s.gyPool.add(sacked.card);
        }
        if (left != null) {
            s.bf.remove(left);
        }
        final int before = out.size();
        resolve(s, effs, 0, 0, ab, out);
        if (left != null) {
            // where the engine left it once the ability had resolved (observed): the top of our library, our hand, ...
            for (int i = before; i < out.size(); i++) {
                final S x = out.get(i);
                if (ab.selfTo == ForcedWinModel.Abil.SELF_TOP) {
                    x.lib.add(0, left.card);
                } else if (ab.selfTo == ForcedWinModel.Abil.SELF_HAND) {
                    x.hand.add(left.card);
                } else if (ab.selfTo == ForcedWinModel.Abil.SELF_GRAVEYARD) {
                    x.gyPool.add(left.card);
                }
            }
        }
        if (toGraveyard) {
            for (int i = before; i < out.size(); i++) {
                out.get(i).gyPool.add(host);
            }
        }
        if (lim.tutorCast) {
            // sdcc-tutorcast-1110: the searched card is cast now, the searching spell having resolved and left the stack
            final List<S> done = new ArrayList<>(out.subList(before, out.size()));
            boolean any = false;
            for (S x : done) {
                any |= x.pendingFree >= 0;
            }
            if (any) {
                out.subList(before, out.size()).clear();
                for (S x : done) {
                    if (x.pendingFree < 0 || x.won || x.dead) {
                        x.pendingFree = -1;
                        out.add(x);
                    } else {
                        castFree(x, out);
                    }
                }
            }
        }
    }

    /**
     * sdcc-tutorcast-1110: the card {@link S#pendingFree} is cast without paying its mana cost: its printed spell (never
     * an alternative cost or an optional-cost variant; each player-target variant is a branch), whose other printed
     * costs are still paid. The cast is a step of its own ({@link Step#free}); it counts as a spell cast and its effects
     * resolve as when cast from hand. A card with no such spell is not a branch (the search does not offer it).
     */
    private void castFree(S x, List<S> out) {
        final int c = x.pendingFree;
        x.pendingFree = -1;
        final ACard a = cards.get(c);
        for (Abil fab : freeSpells(a)) {
            final S n = x.copy();
            if (fab.life > 0) {
                if (n.life <= fab.life) {
                    continue;
                }
                n.life -= fab.life;
            }
            if (fab.discardHand) {
                while (discardLazy(n)) {
                    // sdcc-integ-1110: with planOrder (ORDER_LAZY) too, each unchosen card goes as in the paid cast
                }
                n.gyPool.addAll(n.hand);
                n.hand.clear();
            }
            final Step st = new Step(AbilKind.SPELL, a, fab, -1);
            st.free = true;
            afterPay(n, st, null, c, -1, a, fab, false, out, null);
        }
    }

    /** sdcc-tutorcast-1110: the printed spells of a card that an effect can cast for free in this model. */
    private static List<Abil> freeSpells(ACard a) {
        final List<Abil> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (Abil fab : a.fromHand) {
            if (fab.kind == AbilKind.SPELL && !fab.unplayable && !fab.cardTargets && !fab.alt && fab.optCosts == null
                    && fab.sacN == 0 && fab.returnN == 0 && seen.add(fab.costKey + "|" + fab.desc + "|" + fab.targetVariant)) {
                out.add(fab);
            }
        }
        return out;
    }

    /**
     * sdcc-etb-1110 (landEntersProbe): the land entries this plan has asked its position for: by card index where the
     * answer holds on every battlefield (or is unknown), else by card index and battlefield.
     */
    private final Map<Integer, int[]> landAnywhere = new HashMap<>();
    private final Map<String, int[]> landOnBoard = new HashMap<>();

    /**
     * sdcc-etb-1110: how the land {@code a} (card index {@code host}) enters as our land drop in {@code s} (the land is
     * out of the hand, not yet on the battlefield): {1 = tapped, life change} as the position's sandbox observed it on
     * the battlefield the line has now ({@link ForcedWinModel.LandEntry}; asked once per card, and once per battlefield
     * where that matters to the card, in a plan), or null when the position has none (the key is off) or does not know
     * this card.
     */
    private int[] landEntry(S s, int host, ACard a) {
        if (pos.landEntry == null) {
            return null;
        }
        if (landAnywhere.containsKey(host)) {
            return landAnywhere.get(host);
        }
        final int[] bf = new int[s.bf.size()];
        for (int i = 0; i < bf.length; i++) {
            bf[i] = cards.get(s.bf.get(i).card).id;
        }
        Arrays.sort(bf);
        final String key = host + Arrays.toString(bf);
        final int[] known = landOnBoard.get(key);
        if (known != null) {
            return known;
        }
        // sdcc-integ-1110: with planOrder (ORDER_LAZY) the hand may hold unchosen pile cards (h < 0): they are no card yet
        int known2 = 0;
        for (int h : s.hand) {
            known2 += h >= 0 ? 1 : 0;
        }
        final int[] hand = new int[known2];
        int hn = 0;
        for (int h : s.hand) {
            if (h >= 0) {
                hand[hn++] = cards.get(h).id;
            }
        }
        final int[] entry = pos.landEntry.enters(a, bf, hand);
        if (entry == null || entry.length > 2 && entry[2] != 0) {
            landAnywhere.put(host, entry);
        } else {
            landOnBoard.put(key, entry);
        }
        return entry;
    }

    private static int bestFloatColour(int mask) {
        if ((mask & (1 << ForcedWinModel.U)) != 0) {
            return ForcedWinModel.U;
        }
        return Integer.numberOfTrailingZeros(mask);
    }

    /**
     * Our turn ends; the opponent's turn is modelled by their draw (a milled-out opponent loses) and, since
     * solver-gaps-1015, their visible clock (their creatures attack; each of our creatures that can block then blocks
     * one); our turn.
     */
    private List<S> passTurn(S s) {
        final List<S> out = new ArrayList<>();
        if (s.oppLib > 0 && !pos.oppPowers.isEmpty()) {
            final int blockers = blockers(s);
            if (blockers != s.bf.stream().filter(p -> cards.get(p.card).creature).count()) {
                blockerDiffs++; // blockers-1109: a pass where the old count (every creature) differs; logged once per plan
            }
            int damage = 0;
            for (int i = blockers; i < pos.oppPowers.size(); i++) {
                damage += pos.oppPowers.get(i);
            }
            if (damage >= s.life) {
                return out;
            }
            s.life -= damage;
        }
        if (s.oppLib <= 0) {
            // the opponent loses drawing from an empty library in their draw step, before our next turn
            s.turnIdx++;
            s.won = true;
            s.how = "opponent-decked";
            out.add(s);
            return out;
        }
        if (!lim.goneAtPass.isEmpty()) {
            // next-turn-1107: permanents a verification world lost in the opponent's turn are not there on our next turn
            s.bf.removeIf(p -> lim.goneAtPass.contains(cards.get(p.card).id));
        }
        s.turnIdx++;
        s.sorcery = true;
        Arrays.fill(s.pool, 0);
        s.landDrop = true;
        s.storm = 0;
        s.anyQueue.clear();
        for (Perm p : s.bf) {
            p.tapped = false;
            p.activated = false;
            p.fresh = false;
        }
        final List<Eff> draw = List.of(new Eff(EffKind.DRAW, 1, 0));
        resolve(s, draw, 0, 0, null, out);
        return out;
    }

    /**
     * blockers-1109 (ledger row 81): our creatures that can block at the opponent's declare-blockers step after this
     * pass. Their untap step does not untap ours, so a creature tapped now (it attacked this turn, or paid a cost in the
     * line) blocks only if the position read it as able to block tapped or to untap then ({@link Perm#block}).
     */
    int blockers(S s) {
        int n = 0;
        for (Perm p : s.bf) {
            if (cards.get(p.card).creature && p.block != ForcedWinModel.Position.BLOCK_NO
                    && (!p.tapped || p.block == ForcedWinModel.Position.BLOCK_TAPPED)) {
                n++;
            }
        }
        return n;
    }

    private void resolve(S s, List<Eff> effs, int ei, int done, Abil ab, List<S> out) {
        if (s.dead) {
            return;
        }
        // ledger-sweep2-1107 (planExpandCap): every resolve step counts (report only when off); past the cap the branch is
        // dropped, as the node limit drops the branches it never reaches
        if (expandCalls >= expandLimit) {
            expandCut = true;
            return;
        }
        expandCalls++;
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
                if ((lim.order & Limits.ORDER_LAZY) != 0) {
                    // planOrder: the pile position's card is chosen when the line plays it, not when it is drawn
                    s.lib.remove(0);
                    s.hand.add(top);
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
                    for (int i = 0; i < e.n; i++) {
                        s.anyQueue.add(new int[] {s.steps.size() - 1, e.colors});
                    }
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
                        // planOrder (ORDER_LAZY): an unchosen card goes back unchosen; record() names it
                        st.putBack.add(c < 0 ? PUTBACK_LAZY - (LAZY - c) : cards.get(c).id);
                    }
                    resolve(n, effs, ei + 1, 0, ab, out);
                }
                return;
            }
            case PILE: {
                // an ordered library choice: e.n cards of our library and graveyard, the rest exiled
                final List<Integer> cands = new ArrayList<>(s.libPool);
                for (int e2 : s.lib) {
                    if (e2 >= 0) {
                        cands.add(e2);
                    }
                }
                cands.addAll(s.gyPool);
                cands.sort(Integer::compare);
                final int k = Math.min(e.n, cands.size());
                for (int i = 0; i < s.hand.size(); i++) {
                    if (s.hand.get(i) < 0) {
                        s.hand.set(i, unknownCard); // planOrder (ORDER_LAZY): an earlier pile's unchosen card: not ours to choose now
                    }
                }
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
                final List<Integer> pool = new ArrayList<>(s.libPool);
                for (int e2 : s.lib) {
                    if (e2 >= 0) {
                        pool.add(e2);
                    }
                }
                if (e.names != null) {
                    // solver-gaps-1015: only what the engine offered this tutor in the sandbox (its filter, observed)
                    final List<Integer> eligible = new ArrayList<>();
                    for (int c : pool) {
                        if (e.names.contains(cards.get(c).name)) {
                            eligible.add(c);
                        }
                    }
                    if (eligible.isEmpty()) {
                        resolve(s, effs, ei + 1, 0, ab, out);
                        return;
                    }
                    for (int c : classes(eligible)) {
                        final S n = s.copy();
                        n.libPool = new ArrayList<>(pool);
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
                for (int c : classes(pool)) {
                    final S n = s.copy();
                    n.libPool = new ArrayList<>(pool);
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
            case TUTOR_CAST: {
                // sdcc-tutorcast-1110 (only a tutorCast reader observes it): search our library for a card: one the engine
                // offered to cast for free is cast once this ability has resolved (S#pendingFree), any other goes to our
                // hand when that was observed. The library is shuffled, as after a tutor.
                if (!lim.tutorCast || step == null) {
                    resolve(s, effs, ei + 1, 0, ab, out);
                    return;
                }
                final List<Integer> pool = new ArrayList<>(s.libPool);
                for (int e2 : s.lib) {
                    if (e2 >= 0) {
                        pool.add(e2);
                    }
                }
                final List<Integer> cast = new ArrayList<>(), keep = new ArrayList<>();
                for (int c : pool) {
                    final ACard ca = cards.get(c);
                    if (e.names != null && e.names.contains(ca.name) && !freeSpells(ca).isEmpty()) {
                        cast.add(c);
                    } else if (e.n > 0) {
                        keep.add(c);
                    }
                }
                final List<Integer> picks = castClasses(cast);
                final int nCast = picks.size();
                picks.addAll(classes(keep));
                for (int k = 0; k < picks.size(); k++) {
                    final int c = picks.get(k);
                    final S n = s.copy();
                    n.libPool = new ArrayList<>(pool);
                    n.libPool.remove(Integer.valueOf(c));
                    final Step st = n.steps.get(n.steps.size() - 1);
                    st.picks.add(cards.get(c).id);
                    final int size = n.lib.size() - 1;
                    n.lib = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        n.lib.add(UNKNOWN);
                    }
                    if (k < nCast && n.pendingFree < 0) {
                        n.pendingFree = c;
                        st.castFree = cards.get(c).id;
                    } else {
                        n.hand.add(c);
                    }
                    resolve(n, effs, ei + 1, 0, ab, out);
                }
                return;
            }
            case LIFE: {
                s.life += e.lifeChange(s.life);
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
                if (lim.planDiscard && e.n != ForcedWinModel.ALL && s.hand.size() > e.n) {
                    // labeller-v2 (c): each distinct class of hand cards (least useful first), recorded for the line
                    for (List<Integer> ch : discardChoices(s, e.n)) {
                        final S n = s.copy();
                        final List<String> names = new ArrayList<>();
                        for (int c : ch) {
                            if (c < 0) {
                                // planOrder (ORDER_LAZY): an unchosen card is discarded as the least useful card left
                                final int f = leftover(n.remaining);
                                if (f < 0) {
                                    n.hand.remove(Integer.valueOf(c));
                                    continue;
                                }
                                bindHand(n, n.hand.indexOf(c), f);
                                c = f;
                            }
                            n.hand.remove(Integer.valueOf(c));
                            n.gyPool.add(c);
                            names.add(cards.get(c).name);
                        }
                        java.util.Collections.sort(names);
                        token(n, ForcedWinChoices.DISC + ":" + String.join(";", names));
                        resolve(n, effs, ei + 1, 0, ab, out);
                    }
                    return;
                }
                if (lazyIn(s.hand) >= 0) {
                    // planOrder (ORDER_LAZY): each split of the discard between unchosen cards (each the least useful
                    // card left for the pile) and the hand's other cards (least useful first), fewest unchosen first
                    int lazies = 0;
                    for (int h : s.hand) {
                        lazies += h < 0 ? 1 : 0;
                    }
                    final int n = Math.min(e.n, s.hand.size()), others = s.hand.size() - lazies;
                    for (int k = Math.max(0, n - others); k <= Math.min(n, lazies); k++) {
                        final S v = s.copy();
                        for (int i = 0; i < k; i++) {
                            discardLazy(v);
                        }
                        for (int i = 0; i < n - k; i++) {
                            final int d = leastUseful(v.hand);
                            v.hand.remove(Integer.valueOf(d));
                            v.gyPool.add(d);
                        }
                        resolve(v, effs, ei + 1, 0, ab, out);
                    }
                    return;
                }
                for (int i = 0; i < e.n && !s.hand.isEmpty(); i++) {
                    final int d = leastUseful(s.hand);
                    s.hand.remove(Integer.valueOf(d));
                    s.gyPool.add(d);
                }
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case SCRY:
            case SURVEIL: {
                scry(s, e, effs, ei, ab, out);
                return;
            }
            case HAND_TO_LIBRARY: {
                // solver-gaps-1015: our hand goes into our library (a shuffle follows): known contents, unknown places
                for (int h : s.hand) {
                    if (h != unknownCard && h >= 0) { // planOrder (ORDER_LAZY): an unchosen card is not a known content
                        s.libPool.add(h);
                    }
                    s.lib.add(UNKNOWN);
                }
                s.hand.clear();
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case GRAVEYARD_TO_LIBRARY: {
                for (int g : s.gyPool) {
                    s.libPool.add(g);
                    s.lib.add(UNKNOWN);
                }
                for (int i = 0; i < s.gyExtra; i++) {
                    s.lib.add(UNKNOWN);
                }
                s.gyPool.clear();
                s.gyExtra = 0;
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case EXILE_HAND: {
                s.hand.clear();
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            case WIN: {
                s.won = true;
                s.how = "win";
                out.add(s);
                return;
            }
            case SELF_TO_LIBRARY: {
                selfToLibrary(s, step, e.n);
                resolve(s, effs, ei + 1, 0, ab, out);
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
                if (lazyIn(s.hand) < 0) { // planOrder (ORDER_LAZY): the hand's unchosen cards are still ours to choose
                    s.remaining = new ArrayList<>();
                }
                resolve(s, effs, ei + 1, 0, ab, out);
                return;
            }
            default:
                resolve(s, effs, ei + 1, 0, ab, out);
        }
    }

    /**
     * sdcc-draws-1110 (a SELF_TO_LIBRARY effect; only a probeSettle reader's templates hold one): the card of the step
     * being resolved goes to our library from wherever the model holds it (the battlefield for an activation, the
     * graveyard for a cycled or sacrificed card; a resolving spell is in no zone of the model). On top it is a known
     * entry, so the next draw is this card; at the bottom it is a known entry under the rest; anywhere else it is one
     * more unseen entry whose card the library is known to hold.
     */
    private void selfToLibrary(S s, Step step, int place) {
        final Integer host = step == null ? null : indexOfId.get(step.hostId);
        if (host == null) {
            return;
        }
        Perm perm = null;
        for (Perm p : s.bf) {
            if (p.card == host) {
                perm = p;
                break;
            }
        }
        if (perm != null) {
            final ACard a = cards.get(host);
            s.bf.remove(perm);
            s.devotion -= a.bluePips;
            if (a.emptyDrawWin) {
                boolean still = false;
                for (Perm p : s.bf) {
                    still |= cards.get(p.card).emptyDrawWin;
                }
                s.emptyDrawWin = still;
            }
        } else {
            s.gyPool.remove(host);
        }
        if (place == ForcedWinModel.LIB_TOP) {
            s.lib.add(0, host);
        } else if (place == ForcedWinModel.LIB_BOTTOM) {
            s.lib.add(host);
        } else {
            s.libPool.add(host);
            s.lib.add(UNKNOWN);
        }
    }

    /**
     * labeller-v2 (c): scry / surveil n: every kept / away split of the top min(n, planScryMax) library entries and every
     * order of the kept ones (a lazy pile slot is never sent away), deduped by the resulting library entries up to the
     * first unknown one (surveil: and the known cards it puts into the graveyard); the line records each answer.
     */
    private void scry(S s, Eff e, List<Eff> effs, int ei, Abil ab, List<S> out) {
        final int m = Math.min(Math.min(e.n, lim.planScryMax), s.lib.size());
        if (!lim.planScry || m == 0) {
            resolve(s, effs, ei + 1, 0, ab, out);
            return;
        }
        final boolean surveil = e.kind == EffKind.SURVEIL;
        final List<Integer> top = new ArrayList<>(s.lib.subList(0, m));
        final Set<String> keys = new HashSet<>();
        for (int mask = 0; mask < (1 << m); mask++) {
            final List<Integer> keep = new ArrayList<>(), away = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < m; i++) {
                final int x = top.get(i);
                if ((mask & (1 << i)) != 0) {
                    if (x <= LAZY && x != UNKNOWN) {
                        ok = false;
                        break;
                    }
                    away.add(x);
                } else {
                    keep.add(x);
                }
            }
            if (!ok) {
                continue;
            }
            final List<List<Integer>> perms = new ArrayList<>();
            permute(keep, 0, perms);
            for (List<Integer> perm : perms) {
                final List<Integer> lib = new ArrayList<>(perm);
                lib.addAll(s.lib.subList(m, s.lib.size()));
                if (!surveil) {
                    lib.addAll(away);
                }
                final StringBuilder key = new StringBuilder();
                for (int x : lib) {
                    if (x == UNKNOWN) {
                        break;
                    }
                    key.append(x).append(',');
                }
                if (surveil) {
                    final List<Integer> known = new ArrayList<>();
                    for (int x : away) {
                        if (x >= 0) {
                            known.add(x);
                        }
                    }
                    known.sort(Integer::compare);
                    key.append('|').append(known);
                }
                if (!keys.add(key.toString())) {
                    continue;
                }
                final S n = s.copy();
                n.lib = lib;
                if (surveil) {
                    for (int x : away) {
                        if (x >= 0) {
                            n.gyPool.add(x);
                        } else {
                            n.gyExtra++;
                        }
                    }
                }
                token(n, (surveil ? ForcedWinChoices.SURV : ForcedWinChoices.SCRY) + ":" + tokNames(perm) + "|"
                        + tokNames(away));
                resolve(n, effs, ei + 1, 0, ab, out);
            }
        }
    }

    private static void permute(List<Integer> xs, int k, List<List<Integer>> out) {
        if (k >= xs.size()) {
            if (!out.contains(xs)) {
                out.add(new ArrayList<>(xs));
            }
            return;
        }
        for (int i = k; i < xs.size(); i++) {
            java.util.Collections.swap(xs, k, i);
            permute(xs, k + 1, out);
            java.util.Collections.swap(xs, k, i);
        }
    }

    /** Token names of library entries: a known card's name, else "?". */
    private String tokNames(List<Integer> xs) {
        final StringBuilder sb = new StringBuilder();
        for (int x : xs) {
            sb.append(sb.length() == 0 ? "" : ";").append(x >= 0 ? cards.get(x).name : "?");
        }
        return sb.toString();
    }

    /** Record a choice token on the step being resolved. */
    private static void token(S n, String tok) {
        if (n.steps.isEmpty()) {
            return;
        }
        final Step st = n.steps.get(n.steps.size() - 1);
        if (st.cac == null) {
            st.cac = new ArrayList<>();
        }
        st.cac.add(tok);
    }

    /** labeller-v2 (c): the discard choices of {@code n} cards: distinct classes, least useful first (as put-back's). */
    private List<List<Integer>> discardChoices(S s, int n) {
        final List<Integer> hand = new ArrayList<>(s.hand);
        hand.sort(Comparator.comparingInt((Integer h) -> -orderOf(h)).thenComparingInt(this::idOf));
        final List<Integer> distinct = new ArrayList<>();
        final Map<String, Integer> perName = new HashMap<>();
        for (int h : hand) {
            final int k = perName.merge(nameOf(h), 1, Integer::sum);
            if (k <= n) {
                distinct.add(h);
            }
        }
        final List<List<Integer>> raw = new ArrayList<>();
        combos(distinct, n, 0, new ArrayList<>(), raw, lim.classes * 4);
        final List<List<Integer>> out = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        for (List<Integer> c : raw) {
            final List<String> names = new ArrayList<>();
            for (int x : c) {
                names.add(nameOf(x));
            }
            java.util.Collections.sort(names);
            if (seenNames.add(names.toString()) && out.size() < lim.classes) {
                out.add(c);
            }
        }
        return out;
    }

    private void assign(S s, int lazy, int card) {
        s.pile[LAZY - lazy] = card;
        s.remaining.remove(Integer.valueOf(card));
    }

    /** One card per distinct name, ordered by observed affordance (wins, draws, mana, lands, protection), plus one other. */
    private List<Integer> classes(List<Integer> from) {
        return classes(from, lim.classes);
    }

    /** As above with {@code cap} classes at most (and the one other). */
    private List<Integer> classes(List<Integer> from, int cap) {
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
        final List<Integer> out = new ArrayList<>(useful.subList(0, Math.min(useful.size(), cap)));
        if (other >= 0) {
            out.add(other);
        }
        return out;
    }

    /**
     * sdcc-tutorcast-1110: the cards a search may cast for free, one per name, in an order of trial ({@link #castGroup},
     * then observed affordance), at most {@link Limits#classes}.
     */
    private List<Integer> castClasses(List<Integer> from) {
        final List<Integer> out = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        for (int r : from) {
            if (names.add(cards.get(r).name)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingInt((Integer c) -> castGroup(cards.get(c))).thenComparingInt(c -> cards.get(c).order())
                .thenComparingInt(c -> cards.get(c).id));
        return new ArrayList<>(out.subList(0, Math.min(out.size(), lim.classes)));
    }

    /** An order of trial only (never a rule): wins when cast, orders the library, wins on a condition, draws or searches, other. */
    private static int castGroup(ACard a) {
        int g = 4;
        for (Abil ab : freeSpells(a)) {
            if (ab.has(EffKind.WIN) || ab.storm && (ab.has(EffKind.DRAIN) || ab.has(EffKind.MILL_OPP))
                    || a.permanent && a.winsOnEntry) {
                return 0;
            }
            if (ab.has(EffKind.PILE)) {
                g = Math.min(g, 1);
            } else if (isWinny(a, ab)) {
                g = Math.min(g, 2);
            } else if (ab.has(EffKind.DRAW) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP)
                    || ab.has(EffKind.TUTOR_CAST)) {
                g = Math.min(g, 3);
            }
        }
        return g;
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
            if (h < 0) {
                continue; // planOrder (ORDER_LAZY): an unchosen card (the caller discards those itself)
            }
            final int o = cards.get(h).order();
            if (o > bestOrder) {
                best = h;
                bestOrder = o;
            }
        }
        return best;
    }

    /** planOrder (ORDER_LAZY): {@code h}'s class order, name and id for the hand's choice lists (an unchosen card: after the least useful). */
    private int orderOf(int h) {
        return h < 0 ? 5 : cards.get(h).order();
    }

    private String nameOf(int h) {
        return h < 0 ? "*" : cards.get(h).name;
    }

    private int idOf(int h) {
        return h < 0 ? Integer.MAX_VALUE : cards.get(h).id;
    }

    private List<List<Integer>> putBackChoices(S s, int n) {
        final List<Integer> hand = new ArrayList<>(s.hand);
        if (lazyIn(hand) >= 0) {
            return putBackChoicesLazy(hand, n);
        }
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

    /**
     * planOrder (ORDER_LAZY): the put-back choices of a hand that holds unchosen pile cards. As above, with the unchosen
     * cards (up to {@code n} of them; they are alike) after the lands, counters and other cards and before the wins,
     * draws and mana: a card we do not need goes back first, then cards whose choice is still open, the cards the
     * line holds on to last. Choices that differ only in which unchosen card goes back are one choice.
     */
    private List<List<Integer>> putBackChoicesLazy(List<Integer> hand, int n) {
        hand.sort(Comparator.comparingInt((Integer h) -> h < 0 ? -5 : -2 * cards.get(h).order()).thenComparingInt(this::idOf));
        final List<Integer> distinct = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        int lazies = 0;
        for (int h : hand) {
            if (h < 0 ? lazies++ < n : names.add(cards.get(h).name) || distinct.size() < n) {
                distinct.add(h);
            }
        }
        final List<List<Integer>> out = new ArrayList<>();
        if (hand.size() < n) {
            return out;
        }
        final List<List<Integer>> raw = new ArrayList<>();
        combos(distinct, n, 0, new ArrayList<>(), raw, 24);
        final Set<String> seenNames = new HashSet<>();
        for (List<Integer> c : raw) {
            final List<String> key = new ArrayList<>();
            for (int x : c) {
                key.add(x < 0 ? "*" : "#" + x);
            }
            java.util.Collections.sort(key);
            if (seenNames.add(key.toString()) && out.size() < 6) {
                out.add(c);
            }
        }
        return out;
    }

    /** Record a choice token on the step being resolved (kill-line-1106). */
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
            while (discardLazy(s)) {
                // planOrder (ORDER_LAZY): each unchosen card goes as the least useful card left for the pile
            }
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
        for (int mask : ab.hybrid) {
            if (!payHybrid(s, mask, self)) {
                return false;
            }
        }
        for (int col = 0; col < 6; col++) {
            for (int k = 0; k < ab.phyrexian[col]; k++) {
                if (canPayColour(s, col, self) && !(payPolicy == 8 && s.life > 2)) {
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
        return s.pool[col] > 0 || anyCan(s, col) || source(s, 1 << col, self) != null;
    }

    private boolean payColour(S s, int col, Perm self) {
        if (s.pool[col] > 0) {
            s.pool[col]--;
            return true;
        }
        if (anyCan(s, col)) {
            useAny(s, col);
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

    /** solver-gaps-1015: spend one floating any-colour mana as colour {@code col} (-1 generic); its step records it. */
    private void useAny(S s, int col) {
        s.pool[ForcedWinModel.ANY]--;
        final int i = anyUnit(s, col);
        if (i >= 0) {
            final int at = s.anyQueue.remove(i)[0];
            if (at >= 0 && at < s.steps.size()) {
                s.steps.get(at).anyColors.add(col);
            }
        }
    }

    /** The latest floating multi-colour unit that can be colour {@code col} (-1: generic, any unit), or -1. */
    private static int anyUnit(S s, int col) {
        for (int i = s.anyQueue.size() - 1; i >= 0; i--) {
            if (col < 0 || (s.anyQueue.get(i)[1] & (1 << col)) != 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean anyCan(S s, int col) {
        return s.pool[ForcedWinModel.ANY] > 0 && (s.anyQueue.isEmpty() || anyUnit(s, col) >= 0);
    }

    /** solver-gaps-1015: one mana of any colour in {@code mask}: floating first (the most plentiful), then a source. */
    private boolean payHybrid(S s, int mask, Perm self) {
        int best = -1;
        for (int c = 0; c < 5; c++) {
            if ((mask & (1 << c)) != 0 && s.pool[c] > 0 && (best < 0 || s.pool[c] > s.pool[best])) {
                best = c;
            }
        }
        if (best >= 0) {
            s.pool[best]--;
            return true;
        }
        for (int c = 0; c < 5; c++) {
            if ((mask & (1 << c)) != 0 && anyCan(s, c)) {
                useAny(s, c);
                return true;
            }
        }
        final Perm p = source(s, mask, self);
        if (p == null) {
            return false;
        }
        final int col = Integer.numberOfTrailingZeros(cards.get(p.card).sourceColors & mask);
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
        final int keep = payPolicy >= 3 && payPolicy <= 7 ? payPolicy - 3 : -1;
        for (int c = 0; c < 5; c++) {
            if (s.pool[c] > 0 && (best < 0 || best == keep && c != keep || (c != keep || best == keep) && s.pool[c] > s.pool[best])) {
                best = c;
            }
        }
        if (best >= 0) {
            s.pool[best]--;
            return true;
        }
        if (s.pool[ForcedWinModel.ANY] > 0) {
            useAny(s, -1);
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

    /** The payment order in use (0 = greedy; the payment search tries the others). */
    private int payPolicy;

    /**
     * A source's rank in the payment order (lower is tapped first). 0: least flexible first, one-shot sources last (the
     * greedy order); 1: one-shot sources first; 2: most flexible first; 3..7: keep colour W/U/B/R/G (tap other sources
     * first); 8: as 0 with Phyrexian pips paid in life.
     */
    private int sourceScore(ACard a) {
        final int flex = Integer.bitCount(a.sourceColors), sac = a.sourceSac ? 1 : 0;
        switch (payPolicy) {
            case 1:
                return (1 - sac) * 100 + flex * 10;
            case 2:
                return -flex * 10 + sac * 100;
            case 3: case 4: case 5: case 6: case 7:
                return ((a.sourceColors & (1 << (payPolicy - 3))) != 0 ? 1000 : 0) + flex * 10 + sac * 100;
            default:
                return flex * 10 + sac * 100;
        }
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
            final int score = sourceScore(a);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private void tapSource(S s, Perm p, int col) {
        final ACard a = cards.get(p.card);
        // solver-gaps-1015: the line floats exactly the sources the model paid with, in the colour it used, before the
        // step (Forge AI's own payment chose other sources, or would not use a sacrifice source like Black Lotus)
        s.steps.add(new Step(AbilKind.FLOAT, a, null, col));
        p.tapped = true;
        s.pool[col] += Math.max(1, a.sourceAmount);
        if (a.sourceSac) {
            s.bf.remove(p);
            s.gyPool.add(p.card);
        }
    }

    // ------------------------------------------------------------------ recording and ranking

    private void record(S s) {
        final Line line = lineOf(s);
        if (lineKeys.add(line.key())) {
            result.lines.add(line);
            if (result.firstLineAt < 0) {
                result.firstLineAt = nodes; // planner-fixes-1110 (report only)
            }
            if (s.turnIdx == 0) {
                result.sameTurn = true;
            } else {
                result.nextTurn = true;
            }
        }
    }

    /** The steps of {@code s} as a line the follower can play (ids, the pile's cards, the cards put back). */
    private Line lineOf(S s) {
        final List<String> pileNames = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();
        for (Step st : s.steps) {
            steps.add(st.copy());
        }
        int[] slotCard = new int[0];
        final int[] pile = s.pile == null ? null : s.pile.clone();
        if (pile != null && s.pileStep >= 0 && s.pileStep < steps.size()) {
            final List<Integer> left = new ArrayList<>(s.remaining);
            // planOrder (ORDER_LAZY; the hand holds no unchosen card when off): the cards the line drew from the pile and
            // never played are chosen now. Counters first, the cheapest first, while the hand holds fewer than two (the
            // ranking counts at most two); then by class order, as a draw's first branch is when the key is off.
            int counters = 0;
            for (int h : s.hand) {
                counters += h >= 0 && cards.get(h).counter ? 1 : 0;
            }
            for (int h : s.hand) {
                final int slot = LAZY - h;
                if (h >= 0 || slot >= pile.length || pile[slot] >= 0) {
                    continue;
                }
                int best = -1;
                for (int r : left) {
                    if (best < 0 || heldBefore(cards.get(r), cards.get(best), counters < 2)) {
                        best = r;
                    }
                }
                if (best < 0) {
                    break;
                }
                pile[slot] = best;
                left.remove(Integer.valueOf(best));
                counters += cards.get(best).counter ? 1 : 0;
            }
            left.sort(Comparator.comparingInt((Integer r) -> -cards.get(r).order()).thenComparingInt(r -> cards.get(r).id));
            int li = 0;
            final Step ps = steps.get(s.pileStep);
            ps.pile.clear();
            slotCard = new int[pile.length];
            for (int i = 0; i < pile.length; i++) {
                int c = pile[i];
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
        for (Step st : steps) {
            for (int i = 0; i < st.putBack.size(); i++) {
                if (st.putBack.get(i) <= PUTBACK_LAZY) {
                    // planOrder (ORDER_LAZY): a card put back unchosen is the card its pile slot ended up with
                    final int slot = PUTBACK_LAZY - st.putBack.get(i);
                    st.putBack.set(i, slot < slotCard.length && slotCard[slot] >= 0 ? cards.get(slotCard[slot]).id : -1);
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
            // planOrder (ORDER_LAZY): an unchosen card (h < 0) is the card its pile slot was given above
            final int c = h >= 0 ? h : LAZY - h < slotCard.length ? slotCard[LAZY - h] : -1;
            if (c >= 0 && cards.get(c).counter) {
                prot.add(cards.get(c).name);
            }
        }
        final Line line = new Line(steps, pileNames, s.turnIdx, s.life, mana, prot.size(), prot, s.how);
        line.offMain = pos.offMain; // line-adoption-1107
        return line;
    }

    // ------------------------------------------------------------------ sdcc-bridge-1111 (planBridge)

    /** planBridge: the best setup line per opaque step (card name, ability, object name, turn). */
    private final Map<String, Setup> setupByKey = new HashMap<>();

    /**
     * planBridge: the opaque steps we can pay for at {@code s}, each noted as a setup line with the steps of {@code s}
     * as its prefix (kept when it has fewer modeled steps than the one known for the same step). An ability on the
     * battlefield that is open with no step before it is never noted, there or later in the plan: its pieces are all
     * in play, which is the engine search's own ground. A position after a pass with nothing played before it notes
     * none either.
     */
    private void bridgeMoves(S s) {
        int modeled = 0;
        for (Step st : s.steps) {
            if (st.pass && modeled == 0) {
                return; // nothing played before the pass: next turn's plan, made again then on what that turn holds
            }
            modeled += st.kind == AbilKind.FLOAT ? 0 : 1;
        }
        final Set<String> done = new HashSet<>();
        for (int hi = 0; hi < s.hand.size(); hi++) {
            final int h = s.hand.get(hi);
            if (h < 0) {
                continue;
            }
            final ACard a = cards.get(h);
            for (Abil ab : a.fromHand) {
                if (ab.unplayable || ab.kind != AbilKind.SPELL || ab.returnN > 0 || !s.sorcery && !ab.instant || !handOk(s, ab)
                        || !done.add(a.name + "|" + ab.desc + "|" + ab.costKey)) {
                    continue;
                }
                bridgeAbility(s, a, ab, -1, modeled);
            }
        }
        for (int bi = 0; bi < s.bf.size(); bi++) {
            final Perm p = s.bf.get(bi);
            final ACard a = cards.get(p.card);
            for (Abil ab : a.onBattlefield) {
                if (ab.unplayable || ab.kind == AbilKind.FLOAT || ab.returnN > 0 || !allowedBfCost(s, p, ab)
                        || !done.add(a.name + "|" + ab.desc + "|" + ab.costKey + "|" + p.tapped)) {
                    continue;
                }
                bridgeAbility(s, a, ab, bi, modeled);
            }
        }
    }

    /** planBridge: the opaque steps of permanents that are open at the plan's first position (never setup lines). */
    private final Set<String> rootOpaque = new HashSet<>();

    /** planBridge: the ability's opaque uses at {@code s} (one per object name), noted when we can pay for it. */
    private void bridgeAbility(S s, ACard a, Abil ab, int bi, int modeled) {
        final List<String> objs = new ArrayList<>();
        final List<Integer> clss = new ArrayList<>();
        if (ab.obj != null) {
            final boolean gy = "Graveyard".equals(ab.obj.zone);
            final int n = gy ? s.gyPool.size() : s.bf.size();
            final Set<String> names = new HashSet<>();
            for (int i = 0; i < n; i++) {
                final int c = gy ? s.gyPool.get(i) : s.bf.get(i).card;
                if (c < 0 || c >= cards.size() || !gy && i == bi) {
                    continue;
                }
                final String name = cards.get(c).name;
                final ForcedWinModel.ObjSeen seen = ab.obj.seen.get(name);
                if (!names.add(name) || seen == null && !ab.obj.valid.contains(name)
                        || seen != null && seen.usable() && lim.probeObjects) {
                    continue; // not an object of it, or one the width search plays it with itself
                }
                if (seen != null && !gy && seen.to == Abil.SELF_GRAVEYARD
                        && ForcedWinModel.ObjNeed.TARGET.equals(ab.obj.role)) {
                    continue; // the sandbox saw it destroy the permanent of ours it targeted: removal, not a setup
                }
                final int cls = a.loopWith.contains(name) || seen != null && seen.again ? 0
                        : seen != null && seen.unmapped ? 1 : 2;
                // no evidence: only a permanent cast onto the object (an Aura: what it grants is not in the model)
                if (cls == 2 && !(bi < 0 && a.permanent)) {
                    continue;
                }
                objs.add(name);
                clss.add(cls);
            }
        } else if (a.unmodeled && ab.effects.isEmpty() && !ab.cardTargets && !ab.loyalty && (bi >= 0 || !a.permanent)) {
            // the probes saw it do something the model has no effect for (an extra turn, a card put onto the battlefield)
            boolean loop = false;
            for (Perm q : s.bf) {
                loop |= a.loopWith.contains(cards.get(q.card).name);
            }
            objs.add(null);
            clss.add(loop ? 0 : bi >= 0 ? 1 : 2);
        }
        S paid = null;
        for (int k = 0; k < objs.size(); k++) {
            final String step = a.name + "|" + ab.desc + "|" + ab.costKey + "|" + objs.get(k);
            final String key = step + "|" + s.turnIdx;
            final Setup old = setupByKey.get(key);
            if (old != null && old.modeled <= modeled || bi >= 0 && rootOpaque.contains(step)) {
                continue;
            }
            if (paid == null) {
                paid = s.copy();
                if (!pay(paid, ab, bi < 0 ? null : paid.bf.get(bi), new Step(ab.kind, a, ab, -1))) {
                    return;
                }
            }
            if (bi >= 0 && s.steps.isEmpty()) {
                rootOpaque.add(step); // its pieces are in play and paid for now: the engine search's own ground
                continue;
            }
            final Setup x = new Setup();
            x.prefix = lineOf(s);
            x.hostId = a.id;
            x.hostName = a.name;
            x.desc = ab.desc;
            x.objName = objs.get(k);
            x.cls = clss.get(k);
            x.modeled = modeled;
            x.turnIdx = s.turnIdx;
            x.fromHand = bi < 0;
            setupByKey.put(key, x);
        }
    }

    /**
     * planOrder (ORDER_LAZY): of two cards open to the pile, {@code a} is the better one to have drawn and kept: a
     * counter when one is wanted (the one that costs the least mana first), otherwise the earlier class; then the lower id.
     */
    private static boolean heldBefore(ACard a, ACard b, boolean wantCounter) {
        final boolean ca = wantCounter && a.counter, cb = wantCounter && b.counter;
        if (ca != cb) {
            return ca;
        }
        final int ka = ca ? cheapest(a) : a.order(), kb = cb ? cheapest(b) : b.order();
        return ka != kb ? ka < kb : a.id < b.id;
    }

    /** The least mana any of the card's spell costs asks for (an alternative cost without mana: 0). */
    private static int cheapest(ACard a) {
        int best = Integer.MAX_VALUE;
        for (Abil ab : a.fromHand) {
            if (ab.kind != AbilKind.SPELL) {
                continue;
            }
            int n = ab.generic + ab.hybrid.size();
            for (int c = 0; c < 6; c++) {
                n += ab.pips[c] + ab.phyrexian[c];
            }
            best = Math.min(best, n);
        }
        return best;
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
