package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.ai.AiCache;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinModel.Position;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Lane doomsday-solver-1012: a general forced-win solver for one Forge seat. Off unless a profile turns it on.
 *
 * <p><b>What it does.</b> At our main-phase priority with an empty stack it asks: can we force a win this turn or by
 * our next turn, and with what chance against an opponent that interacts? Three stages, cheapest first.</p>
 * <ol>
 * <li><b>Affordances</b> ({@link ForcedWinProbe}): what each of our cards does, learned by playing it in a sandbox
 * Forge copy. Cached per card name.</li>
 * <li><b>Width search</b> ({@link ForcedWinPlanner}): an exact-mana depth-first search over cast and draw sequences
 * of the affordance model, horizon this turn plus our next turn, with lazily filled ordered library choices. When it
 * finds no line (the common case) the decision costs a few milliseconds and the seat plays on as before.</li>
 * <li><b>Engine evaluation</b>: the top few lines are played in Forge game copies. World 0 is the opponent acting only
 * through its visible board (its hidden hand removed): P(win | unopposed). Worlds 1..K sample the opponent's hidden
 * hand from its deck remainder; in each, the opponent is Forge AI and, when the sampled hand holds a counterspell, also
 * an adversary that counters each of our spells in turn: the world's value is the worst of those answers, with the
 * stack war played by the engine (our follower counters back with its own counterspells). P(win | belief) is the mean.
 * The line with the best P(win | belief) is taken when it reaches the threshold.</li>
 * </ol>
 * <p>The live seat then follows the line step by step with the same follower its play-outs used, answers the choice
 * asks the line planned (picks, orders, put-backs, targets, no shuffle) and searches again when the line breaks.</p>
 *
 * <p>Determinism: the solver reads the live game and never advances its random stream or id counters (copies run in
 * their own scopes); its own randomness is seeded from the position.</p>
 */
public final class ForcedWinSolver {

    public static final class Config {
        public int worlds = 6;
        public int top = 3;
        public double threshold = 0.5;
        public int nodes = 30_000;
        public int horizon = 2;
        public long budgetMs = 8000;
        public boolean adversary = true;
        public boolean engine = true;
        public boolean defense = true;
        public int defenseWorlds = 4;
        public double counterDelta = 0.25;
        public int engineNodes = 250;
        public long engineMs = 4000;
        /**
         * solver-gaps-1015: the engine search may run until the decision's 8 s budget minus this reserve for its line's
         * evaluation (at least engineMs); the evaluation stops at the budget. A long storm loop (Underworld Breach, Brain
         * Freeze) needed about 140 engine nodes, 6 s under a 4-thread load.
         */
        public long evalReserveMs = 2000;
        public int maxSteps = 4000;
        public long seed = 20261012L;
        /**
         * EVAL only (solver-gaps-1015): a frame that starts mid-pile hands the seat its library as built by the seat (the
         * frame lost the seat's memory of it). Off by default; never on in natural games.
         */
        public boolean frameMemory;
        /**
         * order-memory-1102 layer 1 (solver key {@code infoState}; off by default, and then nothing changes): the pile
         * memory is the game's {@link InfoStates} (InfoState(me) of our library) instead of the {@link LibraryWatch}:
         * random-order blocks and orders the opponent chose are not known, scry and dig bottoms and non-contiguous fixed
         * offsets are (pinned in the play-out worlds), and the solver's own answers no longer mark cards placed (the
         * moves they cause teach the state). Cards noted from outside (HonestLabel's root knowledge) still count.
         * Play-out shuffles of the unknown slots are canonical.
         */
        public boolean infoState;
        /**
         * e1-q8-1101 (DESIGN-R3 ruling 2026-11-01 06:40:01, q^8; false = off, the default, and then nothing changes): each
         * play-out watches which cards hidden from the seat at its start it reads from the seat's own library
         * ({@link HiddenWatch}); evaluations and proposals carry it. Set only by {@link HonestLabel}'s {@code libLabels}
         * (never a props key); it changes labels, never which line is played. Transient: a generator's recorded
         * config JSON (HonestGenerator.solverCfgJson, Gson) does not carry it, so recorded specs are unchanged.
         */
        public transient boolean libWatch;
        /**
         * solver-gaps-1015 (off by default): the width search searches the payment of its own casts (a bounded set of
         * payment orders: greedy, one-shot sources first, most flexible first, keep each colour, Phyrexian in life)
         * instead of paying greedily.
         */
        public boolean paySearch;
        /** The forced-win solver (width search, engine search, defense). False when only Part B is on. */
        public boolean forced = true;
        /**
         * Part B (solver-gaps-1015): likely-win lines, off by default. Engine-search lines that are not forced are scored
         * by P(win) over belief worlds against Forge AI rollouts of both seats, against the seat's own play.
         */
        public boolean likely;
        public int likelyWorlds = 4;
        public int likelyTop = 2;
        /** The rollouts' horizon in turns (both seats' turns counted); a game still running there scores 0.5. */
        public int likelyCapTurns = 16;
        /**
         * With Part B on, the engine search leaves this much of the decision budget (the same reserve as for its line's
         * evaluation by default, so Part B does not shorten the forced-win search); the rollouts use what is left and
         * stop at the budget.
         */
        public long likelyReserveMs = 2000;
        /**
         * solver-gaps-1015: the decision's hard wall = its budget plus this grace. The budget's deadlines stop new work
         * (nodes, worlds); a step already running is cut only at the wall (engine moves, verification play-outs).
         */
        public long wallGraceMs = 2000;
        /**
         * search-bones-1015 (true = as before): false = the solver never acts on its own; it follows a line it was handed
         * ({@link #adopt}), counters back a counter of that line, answers the line's choice asks and proposes lines
         * ({@link #propose}), but runs no offense or defence of its own at a priority.
         */
        public boolean act = true;
        /** search-bones-1015 (c), off by default: the engine search's cycle table and REPEAT moves. */
        public boolean cycles = false;
        /**
         * choice-search-1105 (N10 loop stop, off by default; the live port of endstep-rows-1103's rule): an engine line's
         * first REPEAT count is chosen by marginal P(win). Variants n = 1, 2, 4, ... (to loopMarginalCap, and the found
         * count) are verified as lines in the found line's worlds; the smallest n whose P(belief) is within loopEps of the
         * best is played. A variant the deadline cuts is unresolved (excluded) and stops the schedule. Finite by
         * construction: the variants are at most log2(cap) + 1 evaluations under the decision's deadline.
         */
        public boolean loopMarginal = false;
        /**
         * choice-search-1105 (off by default): the engine search, otherwise run once per turn, runs again at a later main
         * phase decision of the turn when a nonland permanent of ours entered since its last run (a setup piece resolved).
         */
        public boolean engineRerun = false;
        /** choice-search-1105 (CAP-2, off by default): each engine search gets its own engineNodes (#149's key). */
        public boolean engineNodesPerSearch = false;
        /**
         * kill-line-1106 (off by default; port of solver-gaps-1104): an engine move's targeting trigger of ours branches
         * over every legal target class, at most triggerVariants of them, mana valued (ForcedWinEngine.TriggerTargets#wide).
         */
        /**
         * unified-search-1106 (off by default): the engine search is not gated on an unmodeled ability or board power, and runs
         * in either main phase of ours, once per phase and again when a nonland permanent of ours entered.
         */
        public boolean engineOpen = false;
        /** unified-search-1106 (off by default): {@link ForcedWinEngine.Limits#verify}. */
        public boolean engineVerify = false;
        /** unified-search-1106 (off by default): {@link ForcedWinEngine.Limits#longLoops}. */
        public boolean engineLongLoops = false;
        /**
         * engine-reach-1107 (off by default): engine nodes and the solver's play-out copies keep this turn's may-play and
         * activation counts ({@link GameCopier#setTurnLimits}).
         */
        public boolean turnLimits = false;
        /**
         * engine-reach-1107 (off by default): engine moves: single targets on each opponent first, nonland mana floats (tap-only
         * floats first), and the top card of our library.
         */
        public boolean engineMoves = false;
        /** engine-reach-1107 (off by default): loops found by covering an ancestor, and a small outlet search at each doubling. */
        public boolean engineLoopReach = false;
        /** engine-reach-1107: nodes of each outlet search under engineLoopReach ({@link ForcedWinEngine.Limits#outletNodes}). */
        public int engineOutletNodes = 6;
        /**
         * loop-exec-1107 (off by default, with engineLoopReach): a loop that fast-forwards with no win is tried again from
         * each earlier node of its path ({@link ForcedWinEngine.Limits#loopHoist}).
         */
        public boolean engineLoopHoist = false;
        /**
         * loop-exec-1107 (off by default, with engineLoopReach): a loop's count is the smallest count whose outlet search
         * wins, bisected between doublings ({@link ForcedWinEngine.Limits#loopBisect}).
         */
        public boolean engineLoopBisect = false;
        /** engine-reach-1107 (0 = off): {@link ForcedWinEngine.Limits#frontierAfterPct}. */
        public int engineFrontierPct = 0;
        /**
         * kill-line-1106 (off by default): the follower pays as the line recorded. A step the planner paid with life pays
         * its Phyrexian pips with life, not with a mana source Forge AI's payment would pick first.
         */
        public boolean followPay = false;
        /** kill-line-1106 (off by default): planner lines that all fail verification fall through to the engine search. */
        /** kill-line-1106 (off by default): a permanent's mana output is read from the position, not its learned template. */
        public boolean sourceOutput = false;
        /**
         * kill-line-1106 (off by default): a verification world whose next-turn line breaks on our next turn (the opponent's
         * turn changed the board) plans again from that world's position, as the live seat would.
         */
        public boolean verifyReplan = false;
        /** kill-line-1106 (off by default): the planner branches over a discard's hand classes; the line records the choice. */
        public int triggerVariants = 8;
        /** unified-search-1106 (off by default): copies of a belief world skip static effects naming swapped-out cards. */
        public boolean staleStatics = false;
        /**
         * unified-search-1106 (off by default): a line that won unopposed with no belief world verified before the deadline is
         * unknown, not refuted: the position is not cached as having no line, so a later ask can verify it.
         */
        public boolean unknownWorlds = false;
        /**
         * unified-search-1106 (off by default): the position key behind the no-line cache also holds our graveyard and our
         * coloured mana, so a mill or a floated colour is not answered by an old "no line".
         */
        public boolean positionKeyFull = false;
        /**
         * unified-search-1106 (off by default): a followed line's own steps are not cut by the play-out loop breaker. A line is
         * finite; a REPEAT longer than the breaker's window stopped mid-way in verification and read as no win.
         */
        public boolean lineUncapped = false;
        /**
         * next-turn-1107 (off by default): a followed step's put-back of cards the line did not name (cards unknown when it was
         * planned, recorded as -1) puts back cards the rest of the line does not use. Off: Forge AI chooses, and may put back
         * the line's own pieces (dd3 T5: a second Brainstorm-style put-back returned the line's Oracle and Lotus Petal).
         */
        public boolean putBackKeep = false;
        /**
         * ledger-sweep-1107, ledger row 60 (off by default): the engine search plays payment variants of its moves on wall
         * budgets, as payRecord does on counter budgets (labeller-v2 (e): each distinct untapped remainder of our one-tap
         * lands after the move's mana cost, up to payVariants, its sources floated before the move), and a follower floats a
         * step's recorded sources before Forge AI pays. Off: every engine move is paid by Forge AI's own choice of lands
         * (paySearch reaches only the planner, followPay only Phyrexian life), so a loop could tap the only red source for
         * its first Sol Ring and never cast its outlet.
         */
        public boolean enginePay = false;
        /**
         * next-turn-1107 (off by default): the lines verified per decision are distinct in their steps (cards and abilities),
         * not only in pile filler; and a card a failed verification lost during the opponent's turn (a step of ours after the
         * pass found it gone) rules out the remaining candidates that use it after their pass. Off: the first cfg.top
         * distinct shapes (dd2 T9: three lines differing only in the pile's protection card, all tapping Urborg next turn).
         */
        public boolean evalAvoidLost = false;
        /**
         * line-adoption-1107 (off by default): the solver proposes at any priority window of ours with an empty stack, not only
         * our main phases and their end step (the caller decides which windows; PlanSearch asks only where the menu has an
         * action). From our upkeep or draw step the planner's MAIN step goes on to this turn's main phase, and a line planned
         * outside our main phases plays its steps before its first PASS or MAIN in the window it was taken in. The engine
         * search still runs only in our main phases.
         */
        public boolean anyWindow = false;
        /** house releases p7, p12, p13, p15, p16, p17 (off by default): solver copies get the matching GameCopier keys. */
        public boolean stalePlayerStatics = false;
        public boolean offBattlefieldLimits = false;
        public boolean historyLki = false;
        public boolean copyBackFace = false;
        public boolean stackAbilityCopies = false;
        public boolean stackHistory = false;
        /**
         * ledger-sweep2-1107 (off by default): one planner expansion builds at most as many branches as the planner has nodes
         * left (ForcedWinPlanner.Limits#expandCap; ledger row 66).
         */
        public boolean planExpandCap = false;
        /**
         * solver-distill-cc-1109 (planLoops; false = off): the screens (reach, goldfish, the reach gate) count a loop the
         * engine found in a sandbox (a permanent whose tap ability targets a card, with a partner creature: both in play =
         * an unbounded attack) as a line. The width model has no copy or untap effects, so such decks had no reach at all.
         */
        public boolean planLoops = false;
        /**
         * ledger-sweep2-1107 (off by default): battlefield mana sources' colors and amounts are read from the position at every
         * read, not from the template learned at first sight (ForcedWinModel.Reader#readSourceColors; ledger row 15).
         */
        public boolean sourceColorsLive = false;
        public double loopEps = 0.005;
        public int loopMarginalCap = 64;
        /**
         * search-bones-1015, off by default: the engine search's root copy shuffles our library below the known top
         * (pile memory), as the verification worlds do; without it the engine plays on our real library order, hidden
         * information (a line that needs the top card was found on the real order and taken when the worlds agreed).
         */
        public boolean hideOwnLibrary = false;
        /** BVW opt-in generator: affordance probes also start from a belief-redrawn, own-library-shuffled root. */
        public boolean honestProbes = false;
        /** search-bones-1015: the width search's wall-time cap per call, ms (1500 = PF5's). */
        public long planMs = 1500;
        /**
         * search-bones-1015 (d), off by default: lines are verified in the position as it stands (a search's world copy,
         * where every hidden card is a sample): one world, nothing hidden or re-drawn, the adversary (the other seat)
         * countering each of the line's spells in turn. P(unopposed) = the line wins with the other seat as Forge AI;
         * P(belief) = the worst over the adversary's counters.
         */
        public boolean exact = false;
        /**
         * strong-opp-1020 (0 = the default, and then nothing changes): extra turns added to the defense threat window
         * ({@link #threat}: their next 2 turns, or 3 when they cannot act at sorcery speed now).
         */
        public int threatExtraTurns = 0;
        /** strong-opp-1020 (0 = off): a discard choice tied between cards re-checks the tied ones this many turns further. */
        public int tieBreakTurns = 0;
        /**
         * strong-opp-1020 (0 = none, the default): the wall budget of one defense decision ({@link #defend},
         * {@link #answerDiscardP}), ms; past it the threat worlds stop, and a decision that could not finish has no opinion.
         */
        public long defendMs = 0;
        /** strong-opp-1020: the defense's opponent reader is the JVM-wide shared one ({@link #sharedOppReader}). */
        public boolean sharedReader = false;
        /**
         * work-units-1107 (false = off, the default, unrecorded, and then nothing changes; read with sharedReader): the
         * defender's opponent reader starts from the jar's affordance cache in each solver (one per seat and game) instead
         * of the JVM-wide reader, so what one game's probes learn never reaches another game in timing order.
         */
        public boolean readerPerGame = false;
        /**
         * strong-opp-1020 (review S2-4; off by default): the defense may HOLD, i.e. pass priority to keep its counter
         * when a bigger threat is likely still to come. Off: a "hold" verdict only declines to counter (no opinion), so
         * the seat's other responses stay its own.
         */
        public boolean hold = false;
        /**
         * deck-belief-1105 (N11 v1; off by default, and then nothing changes): the verification worlds deal the
         * opponent's hidden hand and library from {@link DeckBelief} (a deck of the colours it has shown, the hand
         * weighted by what it declined to do) through {@link LookaheadSearch#solverRedraw}; the belief worlds then keep
         * that hand ({@link #hideOrSample} re-deals nothing).
         */
        public boolean deckBelief = false;
        /** deck-belief-1105: belief worlds when the opponent has a hidden hand card and an untapped mana source (0 = worlds). */
        public int deckWorlds = 0;
        public double deckDecline = 0.35;
        public double deckLandShare = 0.425;
        /**
         * deck-belief-1105 (0 = off, the default): once the line being verified has lost a belief world (it cannot be
         * proven), no further world or candidate starts past this many ms after the decision's start, so the plan
         * search keeps the rest of the decision's budget for its own candidates. A proven line is never cut by it.
         */
        public int unprovenMs = 0;
        /**
         * deck-belief-1105 (0 = off, the default): the solver's whole budget while it holds no proven line, ms from the
         * decision's start. The engine search stops there (its in-flight move is cut 500 ms later), and an unprovable
         * line's verification starts no world or candidate past it ({@link #unprovenMs} = this when unset). A line
         * still winning every world keeps verifying to the usual deadline. The plan search keeps the rest.
         */
        public int capMs = 0;

        public DeckBelief.Params deckParams() {
            final DeckBelief.Params p = new DeckBelief.Params();
            p.decline = deckDecline;
            p.landShare = deckLandShare;
            return p;
        }
        /**
         * bvw-step1-build-1024 (false = the default, and then nothing changes: every wall clock as before): host-independent
         * budgets (spec key {@code budget=counter}). No wall clock is read: budgetMs, engineMs, planMs, evalReserveMs and
         * wallGraceMs and the play-outs' walls do not bind. The decision's work is bounded by counters only: the width
         * search's {@link #nodes}; the engine's {@link #engineNodes}, its per-move step cap and {@link #engineSteps} (engine
         * move steps per engine search); {@link #top} lines and {@link #worlds} worlds per line; {@link #maxSteps} steps per
         * play-out and {@link #evalSteps} (play-out steps per decision: past it no new line or world starts, as past the
         * deadline). Refused with defense, likely or defendMs, which keep their wall clocks.
         */
        public boolean counter = false;
        /** bvw-step1-build-1024, budget=counter: engine move steps per engine search (0 = no cap beyond nodes x per-move). */
        public int engineSteps = 0;
        /** bvw-step1-build-1024, budget=counter: play-out steps per decision's line evaluations (0 = no cap). */
        public int evalSteps = 0;
        /**
         * bvw-step1-build-1024, budget=counter: the cards the game copies of one engine search may hold in all (0 = no
         * cap): the stand-in for the engine deadline that also bounds its memory.
         */
        public int engineCards = 0;
        /**
         * bvw-step1-build-1024, budget=counter: the memory guard of the engine search (cards of the copies on its current
         * DFS path; 0 = none).
         */
        public int enginePathCards = 0;
        /**
         * bvw-step1-build-1024 (false = the default, and then nothing changes): the live seat keeps an adopted line whose
         * first step is our attack (it passes priority to combat, and {@link ForcedWinSolver#answerAttack} declares the
         * alpha strike). Off, such a line is dropped at adoption and Forge AI plays the turn (lane bvw-label-audit-1024).
         */
        public boolean adoptAttack = false;
        /**
         * solver-cap12-1030 (CAP-2, PREDECLARATION-solver-cap12; false = off, the default, and then nothing changes):
         * each engine search gets its own engineNodes (each pass its share, counted from the search's start). Off, the
         * cap compares the solver's LIFETIME node count, so a seat whose solver lives the whole game (the generator)
         * gets no same-turn engine nodes after its first searches (engine_lifetime_stops), while a label's fresh
         * solver gets the full cap: the generator then misses kills the label proves.
         */
        /**
         * solver-cap12-1030 round 2 (PREDECLARATION-solver-cap12-APPENDIX-round2; all false = off, the default, and then
         * nothing changes). loopMacro (R2-3): the engine's general loop abstraction (loops by state recurrence, N chosen by
         * the search, the outlet search after a loop, the stop rule). engineAfterPlanFail (R2-4 b): the engine also runs
         * when the planner found lines and every one failed verification. engineAnyWindow (R2-4 c): the engine also runs
         * at our priority with an empty stack outside our main phase 1 (main 2, end step, the opponent's turn).
         * engineGateWide (R2-4 appendix): the engine gate also counts infect power against poison and treats a card the
         * effect probe failed to learn as unmodeled.
         */
        public boolean loopMacro = false, engineAfterPlanFail = false, engineAnyWindow = false, engineGateWide = false;
        /**
         * solver-cap12-1030 round 3 (PREDECLARATION-solver-cap12-APPENDIX-round3; all off by default, and then nothing
         * changes). engineLadder (R3-1): a failed, budget-stopped first engine pass on a position with engine structure
         * (a graveyard-castable card, a repeatable untapped mana source, a loop the pass saw, an unmodeled card) runs once
         * more at the ladder budget. engineEvalSteps (R3-2; 0 = off): in label mode an engine line's evaluation gets its
         * own play-out step budget. engineOrder (R3-3; "off", "static", "service"): the engine's moves are applied first
         * and searched in the order of one batched value pass. engineX (R3-4 a): X branches inside engine moves.
         * engineCostChoice (R3-4 b): cost cards branch inside engine moves. engineStack (R3-5): the engine also runs at
         * our priority with a non-empty stack.
         */
        public boolean engineLadder = false, engineX = false, engineCostChoice = false, engineStack = false;
        /**
         * Review #154 S2 (false = off): round 3's loopMacro extensions under their own key, so loopMacro keeps its R2
         * meaning: the follower's own line steps skip RolloutAi's play-out loop breaker (D4), and the loop ceilings count a
         * repeatable mana sink (D5). Read only with loopMacro.
         */
        public boolean loopMacro3 = false;
        /**
         * endstep-rows-1103 (N12; false = off, the default, and then nothing changes; read only with engineAnyWindow): at
         * the opponent's end step with an empty stack the engine's gate is skipped (as after a failed plan). The gate reads
         * our board's power NOW, which at their end step undercounts what our next turn attacks with (our untap, flash
         * creatures cast in this window, haste), so a next-turn line set up at their end step never reached the engine.
         */
        public boolean engineEndStepOpen = false;
        /**
         * endstep-rows-1103 (owner ruling 11-03, the whole flash/instant class; false = off; read only with engineAnyWindow):
         * propose() (the label's win check) runs at EVERY empty-stack priority of ours, not only our main phase and their end
         * step (their upkeep, draw, main, combat; our upkeep, draw, combat, end); the engine rests in the root's window
         * there; and with engineEndStepOpen the board-power gate is skipped at every such off-main window.
         */
        public boolean engineEveryWindow = false;
        /** endstep-rows-1103 (false = off): ForcedWinEngine.Limits#passBudget, pass 2's own step and card budgets. */
        public boolean enginePassBudget = false;
        /**
         * endstep-rows-1103, owner ruling 11-03 on loop termination (false = off): an engine line with a REPEAT step (a loop
         * the engine detected by state recurrence with gaining counters, cycles/loopMacro) is re-valued at n = 1, 2, 4, ...
         * repetitions (up to loopMarginalCap): each variant is verified like any line (played in Forge copies over the
         * belief worlds, oppMin on), and the line taken is the smallest n whose P(belief) is within loopEps of the best
         * over the schedule (a curve that turns down is handled). "Enough copies that the win rate stops improving."
         */
        /** endstep-rows-1103: its keys, recorded only when on (off = the recorded spec unchanged). */
        public static final java.util.Map<String, Object> ENDSTEP_DEFAULTS = java.util.Map.of("engineEndStepOpen", false,
                "engineEveryWindow", false, "enginePassBudget", false,
                "loopMarginal", false, "loopEps", 0.005, "loopMarginalCap", 64); // unified-jar-1106: live's default cap; a label spec sets 256
        /**
         * solver-gaps-1104 (all false = off, the default, and then nothing changes; recorded only when on).
         * enginePassFirst: the engine's turn-crossing pass tries the pass first at each unpassed node (it sat behind the
         * branching cap), each of the root's pass moves searches under a third of that pass's nodes and steps (a share stop
         * without a line is a cap), and at our main phase the engine runs without the board-power gate (the turn before
         * the kill, next turn's attackers are in hand). enginePassScale (0 = off): that pass's budgets times this.
         * enginePassToEnd (read with engineAnyWindow): that pass also offers PASS_END, resting at our
         * priority in the opponent's end step before our turn (a flash setup there, seen by the opponent only then).
         * engineTriggerTargets: an engine move's targeting trigger of ours branches over every legal target class (cards
         * equal by controller, name and tapped state are one class), our own mana sources ranked by the mana they make
         * for our cards, and an untap of our untapped mana source floats its mana in response first (mana valued).
         * oppMinLoopMemo: OppMin expands a response point once per loop state: a later point whose position equals an
         * earlier one with the monotone counters out (ForcedWinEngine#coarseKey, the stack by name) and whose counters
         * dominate it for us is a memo hit (answering earlier, before our loop gained, is no worse for the opponent).
         * oppMinRevealPoints (read with oppMin): the opponent's own main-phase priority is a response point while it has
         * seen a nonland permanent of ours enter since its previous turn (OppMin.Driver#reveal): it may answer that card,
         * or hold its mana for our turn. The cost of revealing a setup early is then searched, not assumed away.
         */
        public boolean oppMinRevealPoints = false;
        public boolean enginePassFirst = false, enginePassToEnd = false, engineTriggerTargets = false, oppMinLoopMemo = false;
        /** solver-gaps-1104: the trigger target classes one engine move branches over (engineTriggerTargets only). */
        /** solver-gaps-1104 (0 = off): ForcedWinEngine.Limits#passScale, the turn-crossing pass's budget multiplier. */
        public int enginePassScale = 0;
        /** solver-gaps-1104 (0 = off): ForcedWinEngine.Limits#altLines, the engine's winning lines verified per search. */
        public int engineAltLines = 0;
        /**
         * opp-belief-1104 (ARCH-APPENDIX-1104 A2; 0 = off, the default, unrecorded, and then nothing changes; read with
         * oppMin): the opponent's reply in each of our worlds comes from K worlds of its OWN belief
         * ({@link OppBeliefWorlds}), one reply per information set ({@link OppMin#beliefMin}), valued in ours.
         * oppBeliefCands: the candidate replies at most. oppHoldPoints (needs oppBelief): its own main-phase priority is
         * a hold decision point even with nothing of ours new ({@link OppMin.Driver#hold}).
         */
        public int oppBelief = 0, oppBeliefCands = 6;
        public boolean oppHoldPoints = false;
        public static final java.util.Map<String, Object> OPPBELIEF_DEFAULTS = java.util.Map.of("oppBelief", 0,
                "oppBeliefCands", 6, "oppHoldPoints", false);
        public static final java.util.Map<String, Object> SOLVERGAPS_DEFAULTS = java.util.Map.of("enginePassFirst", false,
                "enginePassToEnd", false, "engineTriggerTargets", false, "oppMinLoopMemo", false, "triggerVariants", 8,
                "oppMinRevealPoints", false, "enginePassScale", 0, "engineAltLines", 0);
        public int ladderNodes = 2000, ladderSteps = 20000, ladderCards = 400000, ladderPathCards = 60000;
        public int engineEvalSteps = 0, orderCap = 24, xVariants = 8;
        /**
         * rollout-budget-1101 (DESIGN-R3 N4/N9 blocker): Forge AI's own evaluation work in one counted decision, in
         * {@link forge.ai.AiWork} units. 0 (the default, unrecorded) = {@link #DEFAULT_AI_WORK} with budget=counter and
         * none on the ms path; a value below 0 = none; above 0 = that cap. A decision that spends it is a cap hit
         * ({@link forge.ai.AiWork#CAP}): it takes and proposes no line. Below the cap nothing changes.
         */
        public long aiWork = 0;
        /**
         * rollout-budget-1101 calibration: E1's off-identity set (1,892 counted decisions) spends at most 1,364,069 units
         * (62 s), p99 81,528, median 78; the B8 hung decision spends ~75k units per CPU second without end. 8M is ~6x
         * the set's maximum and caps a hung decision in ~2 min of CPU.
         *
         * <p>CONTRACT CONSTANT (review #160 S2-3; docs/heuristic-training-contract.md): frozen. Changing it changes which
         * counted decisions are cap hits, so it makes a NEW label and generator version; record the new value in every
         * manifest. The effective cap is recorded in the generator's spec ({@link HonestGenerator#solverCfgJson}).
         */
        public static final long DEFAULT_AI_WORK = 8_000_000L; // CONTRACT CONSTANT: see above


        /** rollout-budget-1101: the decision's Forge AI work cap in effect (0 = none). */
        public long aiWorkCap() {
            return aiWork > 0 ? aiWork : aiWork == 0 && counter ? DEFAULT_AI_WORK : 0L;
        }
        public String engineOrder = "off";
        /**
         * labeller-v2 item (d) (PREDECLARATION-labeller-v2.md; false = off, the default, and then nothing changes): in
         * each world a line must win against EVERY response in the opponent's response set at each of its priorities
         * while one of our spells or abilities is on top of the stack (pass, Forge AI's own answer, and each distinct
         * instant-speed spell or ability of their hand and untapped permanents they can cast and pay for now, per target
         * class), a MIN over the response tree; the one-counter adversary is its special case. Budgets: oppResponses per
         * point, oppDepth points, oppNodes play-outs per world (cap hit = not won).
         */
        public boolean oppMin = false;
        public int oppResponses = 4, oppDepth = 2, oppNodes = 64;
        /**
         * opp-response-1108 (false = off, the default, and then nothing changes): after a line's verification (every
         * world played against Forge AI, as before, on the same budget), each world it won is checked against the
         * opponent's legal actions: at each priority with one of our objects on the stack, our attack, the opponent's
         * block declaration and (lines that pass the turn) its own main phase, the engine's castable or activatable
         * actions from that world's hand, board and other zones ({@link OppMin#answers}, {@link OppMin#blockOptions}) are
         * played out ({@link OppMin.Cfg#refute}). A world some response sequence beats is lost; P(belief) becomes the share
         * of worlds won against every response tried. The check has its own work allowance per decision
         * (oppResponseMs, work-clock ms), oppResponseNodes play-outs per world and oppResponseDepth responses per
         * branch; worlds it has no time for keep their verified value. Read only without oppMin.
         */
        public boolean oppResponse = false;
        public long oppResponseMs = 1500;
        public int oppResponseNodes = 12, oppResponseDepth = 2;
        /**
         * covfix-a-1102 (ARCH-PROPOSAL-1102 layer 3; false = off, the default, and then nothing changes): the MIN searches
         * the opponent's mana abilities that have a non-mana part (OppMin.Driver#manaSearch) instead of failing closed
         * on them as the mana-ability gap. A mana ability the copy cannot activate is a cap (mana-sim).
         */
        public boolean oppManaSearch = false;
        /**
         * covfix-a-1102 (oppMinRev 3; false = off, the default, and then nothing changes): OppMin.Cfg#budgetByDepth, a
         * branch's response budget is oppDepth (no resource reader, no clamp of 2); the node budget bounds the tree.
         */
        public boolean oppBudgetByDepth = false;
        /**
         * Tests only (never a spec key): the adversary's own Forge AI answer is replaced by "pass", so v1 is exactly "one
         * counterspell at one of our spells" and v2 exactly the MIN over the scripted responses: a test then shows what
         * each covers structurally, not what Forge AI happens to play.
         */
        public transient boolean testPassiveOpp = false;
        /**
         * labeller-v2 items (a), (b), (c), (e) (PREDECLARATION-labeller-v2.md §1; all false = off, the default, and then
         * nothing changes). (a) cacPicks: the engine's moves branch over every own-zone pick, scry, surveil, discard,
         * library order and shuffle choice of our seat in the move's window (choose-a-card's distinct answers, at most
         * cacCap per choice, ranked by the static value of the position after the move, cacVariants per choice with
         * Forge AI's own answer included). (b) cacLine: a verification play-out answers each choice the line recorded as
         * recorded (the fixed answers only for an unrecorded choice). (c) planScry / planDiscard: the width planner's
         * SCRY n / SURVEIL n kinds (exact for n up to planScryMax) and DISCARD branching over hand classes. (e) payRecord:
         * the planner branches over distinct untapped-remainder payments (payVariants) and the engine plays payment
         * variants of a move as recorded sources (LineAi floats them; Forge AI pays only an unrecorded step).
         */
        public boolean cacPicks = false, cacLine = false, planScry = false, planDiscard = false, payRecord = false;
        public int cacVariants = 6, cacCap = 48, payVariants = 3, planScryMax = 3;

        /** bvw-step1-build-1024: the keys added to the config after the step-0 freeze, with their defaults. */
        public static final java.util.Map<String, Object> STEP1_DEFAULTS = java.util.Map.of("counter", false,
                "engineSteps", 0, "evalSteps", 0, "engineCards", 0, "enginePathCards", 0, "adoptAttack", false,
                "engineNodesPerSearch", false);

        /** solver-cap12-1030 round 2: its keys, with their defaults (absent from a recorded config at these). */
        public static final java.util.Map<String, Object> R2_DEFAULTS = java.util.Map.of("loopMacro", false,
                "engineAfterPlanFail", false, "engineAnyWindow", false, "engineGateWide", false);

        /** solver-cap12-1030 round 3: its keys, with their defaults (absent from a recorded config at these). */
        public static final java.util.Map<String, Object> R3_DEFAULTS = java.util.Map.ofEntries(
                java.util.Map.entry("engineLadder", false), java.util.Map.entry("ladderNodes", 2000),
                java.util.Map.entry("ladderSteps", 20000), java.util.Map.entry("ladderCards", 400000),
                java.util.Map.entry("ladderPathCards", 60000), java.util.Map.entry("engineEvalSteps", 0),
                java.util.Map.entry("engineOrder", "off"), java.util.Map.entry("orderCap", 24),
                java.util.Map.entry("engineX", false), java.util.Map.entry("xVariants", 8),
                java.util.Map.entry("engineCostChoice", false), java.util.Map.entry("engineStack", false),
                java.util.Map.entry("loopMacro3", false));

        /** rollout-budget-1101: its key, with its default (absent from a recorded config at it). */
        public static final java.util.Map<String, Object> AIWORK_DEFAULTS = java.util.Map.of("aiWork", 0L);

        /** Labeller v2 (PREDECLARATION-labeller-v2): its keys, with their defaults (absent from a recorded config at these). */
        public static final java.util.Map<String, Object> V2_DEFAULTS = java.util.Map.ofEntries(
                java.util.Map.entry("oppMin", false), java.util.Map.entry("oppResponses", 4),
                java.util.Map.entry("oppDepth", 2), java.util.Map.entry("oppNodes", 64),
                java.util.Map.entry("cacPicks", false), java.util.Map.entry("cacLine", false),
                java.util.Map.entry("planScry", false), java.util.Map.entry("planDiscard", false),
                java.util.Map.entry("payRecord", false), java.util.Map.entry("cacVariants", 6),
                java.util.Map.entry("cacCap", 48), java.util.Map.entry("payVariants", 3),
                java.util.Map.entry("planScryMax", 3), java.util.Map.entry("oppManaSearch", false),
                java.util.Map.entry("oppBudgetByDepth", false));

        /** Labeller v2: any of its items on (each needs budget=counter). */
        public boolean v2() {
            return oppMin || cacPicks || cacLine || planScry || planDiscard || payRecord;
        }

        /**
         * pr209-followups (#212's belief-world sites): the {@code staleStatics} key of a comma spec ({@code k=v,...}) as a
         * seat's solver reads it (CubeComboControl: the last value of a key wins; null or absent = off).
         */
        public static boolean staleStaticsIn(final String spec) {
            boolean on = false;
            for (String part : spec == null ? new String[0] : spec.split(",")) {
                final int eq = part.indexOf('=');
                if (eq > 0 && "staleStatics".equals(part.substring(0, eq).trim())) {
                    on = "true".equals(part.substring(eq + 1).trim());
                }
            }
            return on;
        }

        public static Config fromProps(java.util.function.Function<String, String> get) {
            final Config c = new Config();
            final String budget = get.apply("budget");
            if (budget != null && !budget.isEmpty() && !"ms".equals(budget) && !"counter".equals(budget)) {
                throw new IllegalArgumentException("forced-win budget must be ms or counter, not " + budget);
            }
            c.counter = "counter".equals(budget);
            c.engineSteps = intOr(get.apply("engineSteps"), c.engineSteps);
            c.evalSteps = intOr(get.apply("evalSteps"), c.evalSteps);
            c.engineCards = intOr(get.apply("engineCards"), c.engineCards);
            c.enginePathCards = intOr(get.apply("enginePathCards"), c.enginePathCards);
            c.adoptAttack = "true".equals(get.apply("adoptAttack"));
            c.engineNodesPerSearch = "true".equals(get.apply("engineNodesPerSearch"));
            c.loopMacro = "true".equals(get.apply("loopMacro"));
            c.engineAfterPlanFail = "true".equals(get.apply("engineAfterPlanFail"));
            c.engineAnyWindow = "true".equals(get.apply("engineAnyWindow"));
            c.engineGateWide = "true".equals(get.apply("engineGateWide"));
            c.engineLadder = "true".equals(get.apply("engineLadder"));
            c.ladderNodes = intOr(get.apply("ladderNodes"), c.ladderNodes);
            c.ladderSteps = intOr(get.apply("ladderSteps"), c.ladderSteps);
            c.ladderCards = intOr(get.apply("ladderCards"), c.ladderCards);
            c.ladderPathCards = intOr(get.apply("ladderPathCards"), c.ladderPathCards);
            c.engineEvalSteps = intOr(get.apply("engineEvalSteps"), c.engineEvalSteps);
            final String aiWork = get.apply("aiWork");
            if (aiWork != null && !aiWork.isEmpty()) {
                try {
                    c.aiWork = Long.parseLong(aiWork);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("forced-win aiWork must be an integer, not " + aiWork);
                }
            }
            final String order = get.apply("engineOrder");
            if (order != null && !order.isEmpty()) {
                if (!order.equals("off") && !order.equals("static") && !order.equals("service")) {
                    throw new IllegalArgumentException("engineOrder must be off, static or service, not " + order);
                }
                c.engineOrder = order;
            }
            c.orderCap = intOr(get.apply("orderCap"), c.orderCap);
            c.engineX = "true".equals(get.apply("engineX"));
            c.xVariants = intOr(get.apply("xVariants"), c.xVariants);
            c.engineCostChoice = "true".equals(get.apply("engineCostChoice"));
            c.engineStack = "true".equals(get.apply("engineStack"));
            c.loopMacro3 = "true".equals(get.apply("loopMacro3"));
            c.engineEndStepOpen = "true".equals(get.apply("engineEndStepOpen")); // endstep-rows-1103
            c.engineEveryWindow = "true".equals(get.apply("engineEveryWindow")); // endstep-rows-1103
            c.enginePassBudget = "true".equals(get.apply("enginePassBudget")); // endstep-rows-1103
            c.loopMarginal = "true".equals(get.apply("loopMarginal")); // endstep-rows-1103
            c.loopEps = dblOr(get.apply("loopEps"), c.loopEps);
            c.loopMarginalCap = intOr(get.apply("loopMarginalCap"), c.loopMarginalCap);
            c.enginePassFirst = "true".equals(get.apply("enginePassFirst")); // solver-gaps-1104
            c.enginePassToEnd = "true".equals(get.apply("enginePassToEnd")); // solver-gaps-1104
            c.engineTriggerTargets = "true".equals(get.apply("engineTriggerTargets")); // solver-gaps-1104
            c.oppMinLoopMemo = "true".equals(get.apply("oppMinLoopMemo")); // solver-gaps-1104
            c.oppMinRevealPoints = "true".equals(get.apply("oppMinRevealPoints")); // solver-gaps-1104
            c.triggerVariants = intOr(get.apply("triggerVariants"), c.triggerVariants);
            c.enginePassScale = intOr(get.apply("enginePassScale"), c.enginePassScale); // solver-gaps-1104
            c.engineAltLines = intOr(get.apply("engineAltLines"), c.engineAltLines); // solver-gaps-1104
            c.oppBelief = intOr(get.apply("oppBelief"), c.oppBelief); // opp-belief-1104
            c.oppBeliefCands = intOr(get.apply("oppBeliefCands"), c.oppBeliefCands); // opp-belief-1104
            c.oppHoldPoints = "true".equals(get.apply("oppHoldPoints")); // opp-belief-1104
            if (c.oppBelief < 0 || c.oppBeliefCands < 2 || (c.oppHoldPoints && c.oppBelief == 0)) {
                throw new IllegalArgumentException("oppBelief >= 0, oppBeliefCands >= 2, oppHoldPoints needs oppBelief");
            }
            // PREDECLARATION-labeller-v2 §0: labellerV2=true turns all five items on
            final boolean v2all = "true".equals(get.apply("labellerV2"));
            c.oppMin = v2all || "true".equals(get.apply("oppMin"));
            c.oppResponses = intOr(get.apply("oppResponses"), c.oppResponses);
            if (c.oppResponses > OppMin.CHARM_CAP) {
                throw new IllegalArgumentException("oppResponses " + c.oppResponses + " > " + OppMin.CHARM_CAP
                        + " (review #148: a cut modal set must stay a cap hit)");
            }
            c.oppDepth = intOr(get.apply("oppDepth"), c.oppDepth);
            c.oppResponse = "true".equals(get.apply("oppResponse")); // opp-response-1108
            c.oppResponseMs = intOr(get.apply("oppResponseMs"), (int) c.oppResponseMs);
            c.oppResponseNodes = intOr(get.apply("oppResponseNodes"), c.oppResponseNodes);
            c.oppResponseDepth = intOr(get.apply("oppResponseDepth"), c.oppResponseDepth);
            if (c.oppResponse && (c.oppResponseMs <= 0 || c.oppResponseNodes < 1 || c.oppResponseDepth < 1)) {
                throw new IllegalArgumentException("oppResponseMs > 0, oppResponseNodes >= 1, oppResponseDepth >= 1");
            }
            c.oppNodes = intOr(get.apply("oppNodes"), c.oppNodes);
            c.oppManaSearch = "true".equals(get.apply("oppManaSearch")); // covfix-a-1102
            c.oppBudgetByDepth = "true".equals(get.apply("oppBudgetByDepth")); // covfix-a-1102 (oppMinRev 3)
            c.cacPicks = v2all || "true".equals(get.apply("cacPicks"));
            c.cacLine = v2all || "true".equals(get.apply("cacLine"));
            c.planScry = v2all || "true".equals(get.apply("planScry"));
            c.planDiscard = v2all || "true".equals(get.apply("planDiscard"));
            // consolidate-1107: planDiscardWall = the planner's discard branching on wall budgets (kill-line-1106 #210's pilot
            // use, as the U arm ran it before #212). planDiscard itself stays a labeller v2 key (counts only), as on live.
            final boolean discardWall = "true".equals(get.apply("planDiscardWall"));
            c.payRecord = v2all || "true".equals(get.apply("payRecord"));
            c.cacVariants = intOr(get.apply("cacVariants"), c.cacVariants);
            c.cacCap = intOr(get.apply("cacCap"), c.cacCap);
            c.payVariants = intOr(get.apply("payVariants"), c.payVariants);
            c.planScryMax = intOr(get.apply("planScryMax"), c.planScryMax);
            if (c.v2() && !c.counter) {
                throw new IllegalArgumentException((c.oppMin ? "oppMin" : "a labeller v2 key")
                        + " needs budget=counter (labeller v2 is counts only)");
            }
            c.planDiscard |= discardWall;
            c.frameMemory = "true".equals(get.apply("frameMemory"));
            c.infoState = "true".equals(get.apply("infoState"));
            c.likely = "true".equals(get.apply("likely"));
            c.paySearch = "true".equals(get.apply("paySearch"));
            c.likelyWorlds = intOr(get.apply("likelyWorlds"), c.likelyWorlds);
            c.likelyTop = intOr(get.apply("likelyTop"), c.likelyTop);
            c.likelyCapTurns = intOr(get.apply("likelyCapTurns"), c.likelyCapTurns);
            c.likelyReserveMs = intOr(get.apply("likelyReserveMs"), (int) c.likelyReserveMs);
            c.wallGraceMs = intOr(get.apply("wallGraceMs"), (int) c.wallGraceMs);
            c.worlds = intOr(get.apply("worlds"), c.worlds);
            c.top = intOr(get.apply("top"), c.top);
            c.threshold = dblOr(get.apply("threshold"), c.threshold);
            c.nodes = intOr(get.apply("nodes"), c.nodes);
            c.horizon = intOr(get.apply("horizon"), c.horizon);
            c.budgetMs = intOr(get.apply("budgetMs"), (int) c.budgetMs);
            c.adversary = !"false".equals(get.apply("adversary"));
            c.engine = !"false".equals(get.apply("engine"));
            c.defense = !"false".equals(get.apply("defense"));
            c.defenseWorlds = intOr(get.apply("defenseWorlds"), c.defenseWorlds);
            c.counterDelta = dblOr(get.apply("counterDelta"), c.counterDelta);
            c.engineNodes = intOr(get.apply("engineNodes"), c.engineNodes);
            c.engineMs = intOr(get.apply("engineMs"), (int) c.engineMs);
            c.evalReserveMs = intOr(get.apply("evalReserveMs"), (int) c.evalReserveMs);
            c.act = !"false".equals(get.apply("act"));
            c.cycles = "true".equals(get.apply("cycles"));
            c.loopMarginal = "true".equals(get.apply("loopMarginal")); // choice-search-1105
            c.engineRerun = "true".equals(get.apply("engineRerun")); // choice-search-1105
            c.engineNodesPerSearch = "true".equals(get.apply("engineNodesPerSearch")); // choice-search-1105 (CAP-2)
            c.engineTriggerTargets = "true".equals(get.apply("engineTriggerTargets")); // kill-line-1106
            c.staleStatics = "true".equals(get.apply("staleStatics")); // unified-search-1106
            c.engineOpen = "true".equals(get.apply("engineOpen")); // unified-search-1106
            c.engineVerify = "true".equals(get.apply("engineVerify")); // unified-search-1106
            c.engineLongLoops = "true".equals(get.apply("engineLongLoops")); // unified-search-1106
            c.turnLimits = "true".equals(get.apply("turnLimits")); // engine-reach-1107
            c.engineMoves = "true".equals(get.apply("engineMoves")); // engine-reach-1107
            c.engineLoopReach = "true".equals(get.apply("engineLoopReach")); // engine-reach-1107
            c.engineOutletNodes = intOr(get.apply("engineOutletNodes"), c.engineOutletNodes); // engine-reach-1107
            c.engineLoopHoist = "true".equals(get.apply("engineLoopHoist")); // loop-exec-1107
            c.engineLoopBisect = "true".equals(get.apply("engineLoopBisect")); // loop-exec-1107
            c.engineFrontierPct = intOr(get.apply("engineFrontierPct"), c.engineFrontierPct); // engine-reach-1107
            c.followPay = "true".equals(get.apply("followPay")); // kill-line-1106
            c.engineAfterPlanFail = "true".equals(get.apply("engineAfterPlanFail")); // kill-line-1106
            c.sourceOutput = "true".equals(get.apply("sourceOutput")); // kill-line-1106
            c.verifyReplan = "true".equals(get.apply("verifyReplan")); // kill-line-1106
            c.triggerVariants = intOr(get.apply("triggerVariants"), c.triggerVariants);
            c.staleStatics = "true".equals(get.apply("staleStatics")); // unified-search-1106
            c.unknownWorlds = "true".equals(get.apply("unknownWorlds")); // unified-search-1106
            c.positionKeyFull = "true".equals(get.apply("positionKeyFull")); // unified-search-1106
            c.lineUncapped = "true".equals(get.apply("lineUncapped")); // unified-search-1106
            c.putBackKeep = "true".equals(get.apply("putBackKeep")); // next-turn-1107
            c.enginePay = "true".equals(get.apply("enginePay")); // ledger-sweep-1107 (row 60)
            c.evalAvoidLost = "true".equals(get.apply("evalAvoidLost")); // next-turn-1107
            c.anyWindow = "true".equals(get.apply("anyWindow")); // line-adoption-1107
            c.stalePlayerStatics = "true".equals(get.apply("stalePlayerStatics")); // house p7
            c.offBattlefieldLimits = "true".equals(get.apply("offBattlefieldLimits")); // house p12
            c.historyLki = "true".equals(get.apply("historyLki")); // house p13
            c.copyBackFace = "true".equals(get.apply("copyBackFace")); // house p15
            c.stackAbilityCopies = "true".equals(get.apply("stackAbilityCopies")); // house p16
            c.stackHistory = "true".equals(get.apply("stackHistory")); // house p17
            c.planExpandCap = "true".equals(get.apply("planExpandCap")); // ledger-sweep2-1107
            c.planLoops = "true".equals(get.apply("planLoops")); // solver-distill-cc-1109
            c.sourceColorsLive = "true".equals(get.apply("sourceColorsLive")); // ledger-sweep2-1107
            c.loopEps = dblOr(get.apply("loopEps"), c.loopEps);
            c.loopMarginalCap = intOr(get.apply("loopMarginalCap"), c.loopMarginalCap);
            c.hideOwnLibrary = "true".equals(get.apply("hideOwnLibrary"));
            c.exact = "true".equals(get.apply("exact"));
            c.planMs = intOr(get.apply("planMs"), (int) c.planMs);
            c.threatExtraTurns = intOr(get.apply("threatExtraTurns"), c.threatExtraTurns);
            c.tieBreakTurns = intOr(get.apply("tieBreakTurns"), c.tieBreakTurns);
            c.defendMs = intOr(get.apply("defendMs"), (int) c.defendMs);
            c.sharedReader = "true".equals(get.apply("sharedReader"));
            c.readerPerGame = "true".equals(get.apply("readerPerGame")); // work-units-1107
            c.hold = "true".equals(get.apply("hold"));
            c.deckBelief = "true".equals(get.apply("deckBelief")); // deck-belief-1105
            c.deckWorlds = intOr(get.apply("deckWorlds"), c.deckWorlds);
            c.deckDecline = dblOr(get.apply("deckDecline"), c.deckDecline);
            c.deckLandShare = dblOr(get.apply("deckLandShare"), c.deckLandShare);
            c.unprovenMs = intOr(get.apply("unprovenMs"), c.unprovenMs);
            c.capMs = intOr(get.apply("capMs"), c.capMs);
            if (c.counter && (c.defense || c.likely || c.defendMs > 0)) {
                throw new IllegalArgumentException("refused: budget=counter bounds the offense only; defense, likely and "
                        + "defendMs keep wall clocks (set defense=false, likely=false, defendMs=0)");
            }
            if (c.engineSteps < 0 || c.evalSteps < 0 || c.engineCards < 0 || c.enginePathCards < 0) {
                throw new IllegalArgumentException("forced-win engineSteps, evalSteps and engineCards must be >= 0");
            }
            return c;
        }

        private static int intOr(String s, int d) {
            try {
                return s == null || s.isEmpty() ? d : Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return d;
            }
        }

        private static double dblOr(String s, double d) {
            try {
                return s == null || s.isEmpty() ? d : Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return d;
            }
        }

        @Override
        public String toString() {
            return "worlds=" + worlds + " top=" + top + " threshold=" + threshold + " nodes=" + nodes + " horizon="
                    + horizon + " budgetMs=" + budgetMs + " adversary=" + adversary + " defense=" + defense + " engine=" + engine + " engineNodes="
                + engineNodes + " engineMs=" + engineMs + " forced=" + forced + " likely=" + likely + " likelyWorlds=" + likelyWorlds
                + " likelyTop=" + likelyTop + " likelyCapTurns=" + likelyCapTurns + " paySearch=" + paySearch
                + (oppMin ? " oppMin R=" + oppResponses + " D=" + oppDepth + " N=" + oppNodes + " V=" + OppMin.VERSION : "")
                + (cacPicks ? " cacPicks V=" + cacVariants + " cap=" + cacCap : "") + (cacLine ? " cacLine" : "")
                + (planScry ? " planScry max=" + planScryMax : "") + (planDiscard ? " planDiscard" : "")
                + (payRecord ? " payRecord V=" + payVariants : "") + (enginePay ? " enginePay V=" + payVariants : "")
                + (engineNodesPerSearch ? " engineNodesPerSearch" : "")
                + (loopMacro ? " loopMacro" : "") + (engineAfterPlanFail ? " engineAfterPlanFail" : "")
                + (engineAnyWindow ? " engineAnyWindow" : "") + (engineGateWide ? " engineGateWide" : "")
                + (engineLadder ? " engineLadder N=" + ladderNodes + " S=" + ladderSteps : "")
                + (engineEvalSteps > 0 ? " engineEvalSteps=" + engineEvalSteps : "")
                + (!"off".equals(engineOrder) ? " engineOrder=" + engineOrder : "") + (engineX ? " engineX" : "")
                + (engineCostChoice ? " engineCostChoice" : "") + (engineStack ? " engineStack" : "")
                + (loopMacro3 ? " loopMacro3" : "") + (aiWork != 0 ? " aiWork=" + aiWork : "")
                + (enginePassFirst ? " enginePassFirst" : "") + (enginePassToEnd ? " enginePassToEnd" : "") // solver-gaps-1104
                + (engineTriggerTargets ? " engineTriggerTargets V=" + triggerVariants : "")
                + (oppMinLoopMemo ? " oppMinLoopMemo" : "") + (oppMinRevealPoints ? " oppMinRevealPoints" : "")
                + (enginePassScale > 0 ? " enginePassScale=" + enginePassScale : "")
                + (engineAltLines > 0 ? " engineAltLines=" + engineAltLines : "")
                + (oppBelief > 0 ? " oppBelief=" + oppBelief + " C=" + oppBeliefCands : "") // opp-belief-1104
                + (oppHoldPoints ? " oppHoldPoints" : "")
                + (oppResponse ? " oppResponse ms=" + oppResponseMs + " N=" + oppResponseNodes + " D=" + oppResponseDepth : "");
        }
    }

    /** Receipts to stderr (diagnostics only): -Dforge.ai.forcedWin.receipts=true. */
    static final boolean RECEIPTS = Boolean.getBoolean("forge.ai.forcedWin.receipts");
    /** engine-caps-1103 (diagnostics only): the label row this thread works on, for the engine's diag lines. */
    public static final ThreadLocal<String> DIAG_ROW = new ThreadLocal<>();
    /** Diagnostics only: the follower's every priority answer (-Dforge.ai.forcedWin.followTrace). */
    static final boolean DBG_FOLLOW = Boolean.getBoolean("forge.ai.forcedWin.followTrace");

    static void receipt(String s) {
        if (RECEIPTS) {
            System.err.println(s);
        }
    }

    /** Receipts name the seat's deck (diagnostics: which deck a decision belongs to). */
    private String tag() {
        try {
            return " deck=" + me.getRegisteredPlayer().getDeck().getName().replace(' ', '_') + " seat="
                    + me.getGame().getRegisteredPlayers().indexOf(me.getRegisteredPlayer()) + " thr="
                    + Thread.currentThread().getId();
        } catch (RuntimeException e) {
            return " deck=?";
        }
    }

    static long mix(long a, long b) {
        return LookaheadSearch.mix(a, b);
    }

    // ------------------------------------------------------------------ per-seat state

    private final Player me;
    /**
     * honest-belief-1020 (null = off, the default, and then nothing changes): the re-draw of the opponents' hidden cards
     * in every copy the solver evaluates (verification worlds, the engine's root), from the seat's belief pool, before
     * {@link #hideOrSample}; so no world holds the opponent's real hand or library. Set by the search that owns the
     * belief ({@link LookaheadSearch#opponentRedraw}).
     */
    public Redraw redraw;

    /**
     * deck-belief-1105: the belief worlds' opponent hand as {@link #redraw} dealt it ({@link Config#deckBelief} with a
     * redraw set): {@link #hideOrSample} then re-deals nothing in worlds above 0. World 0 still hides the hand.
     */
    private boolean keepDealtHand() {
        return cfg.deckBelief && redraw != null;
    }

    /**
     * deck-belief-1105: the belief worlds of one evaluation: {@link Config#deckWorlds} when the opponent holds a hidden
     * hand card and controls an untapped mana source (where an answer could matter), else {@link Config#worlds}.
     */
    int beliefWorlds(Game live) {
        if (!cfg.deckBelief || cfg.deckWorlds <= cfg.worlds) {
            return cfg.worlds;
        }
        final forge.game.player.PlayerView myView = me.getView();
        for (Player o : me.getOpponents()) {
            boolean hidden = false;
            for (Card c : o.getCardsIn(ZoneType.Hand)) {
                if (!c.getView().canBeShownTo(myView)) {
                    hidden = true;
                    break;
                }
            }
            if (!hidden) {
                continue;
            }
            for (Card c : o.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isTapped() && !c.getManaAbilities().isEmpty()) {
                    return cfg.deckWorlds;
                }
            }
        }
        return cfg.worlds;
    }

    /** honest-belief-1020: re-draws the opponents' hidden cards of copy {@code g} (called inside {@code synchronized (live)}). */
    @FunctionalInterface
    public interface Redraw {
        void apply(Game live, Player liveMe, Game g, Player me, Random rng);
        /** Observation tracker knowledge retained by the redraw, also respected by the later world hand filter. */
        default boolean remembersHandCard(Game live, Player observer, Card card) { return false; }
    }
    private final Config cfg;

    /**
     * J0 (lane block-search-1019): the decision budget as it binds now ({@link forge.ai.WallClock}): unchanged live, and
     * at least the rebuild budget while a "Continue from here" game is rebuilt, so no S-b wall clock cuts a rebuilt
     * decision differently. engineMs and planMs go through WallClock too. evalReserveMs does not: it is a reservation
     * inside the budget (the engine stops that long before it), and inflating it with the budget would leave the engine
     * no time; wallGraceMs is a grace past the budget, unbound once the budget is.
     */
    private long budgetMs() {
        return forge.ai.WallClock.budgetMs(cfg.budgetMs);
    }
    final ForcedWinModel.Reader reader;
    private Follower active;
    private String noLineKey;
    private int decisionIndex;
    /** Diagnostics: decisions asked, gated off by the width search, engine-evaluated, lines taken. */
    public int decisions, gated, evaluated, taken, replans;
    public long plannerNanos, engineNanos;
    public int copies;

    /**
     * bvw-step1-build-1024 (read-only: nothing reads it to decide): the solver's work, cumulative over its life, in the
     * units the counter budgets bound, and the stops each budget caused. {@link #since} gives one call's share.
     */
    public static final class Meter {
        /** Offense calls (decisions that read the position). */
        public long offenses;
        /** Width-search nodes visited; width searches cut at their wall time (planMs). */
        public long plannerNodes, plannerTimeouts;
        /** Engine searches; their nodes and move steps; searches stopped by the deadline, by a node or step cap. */
        public long engineRuns, engineNodes, engineSteps, engineDeadlineStops, engineCapStops;
        /** Cards in the engine's game copies; the engine's wall time (diagnostics: the ms-to-counter mapping). */
        public long engineCards, engineNanos, enginePathPrunes;
        /** Engine searches that stopped at the node cap with nodes spent by earlier searches of this solver (review #10). */
        public long engineLifetimeStops;
        /** Engine moves cut at the wall. */
        public long engineWallCuts;
        /** Lines evaluated; worlds run (the unopposed world 0 included); play-outs; their steps. */
        public long lines, worlds, playouts, playoutSteps;
        /** Line evaluations that stopped early at the deadline (ms) or at evalSteps (counter): worlds or lines not run. */
        public long evalDeadlineStops, evalCapStops;
        /** Play-outs cut at the wall (ms), at maxSteps, and at the call cap (counter). */
        public long playoutWallCuts, playoutStepCaps, playoutCallCaps;
        /** labeller-v2 (d): MIN play-outs (response branches) and worlds cut at oppNodes; reported only when non-zero. */
        public long oppMinNodes, oppMinCuts;
        /** solver-gaps-1104 (oppMinLoopMemo): OppMin points skipped as a dominated loop repeat; only when non-zero. */
        public long oppMinLoopMemo;
        /** opp-belief-1104 (oppBelief only; non-zero only then): belief MINs, their tree and candidate play-outs, non-Forge picks. */
        public long oppBeliefMins, oppBeliefNodes, oppBeliefNonForge;
        /**
         * opp-response-1108 (oppResponse only; non-zero only then): lines checked, worlds checked, worlds refuted, worlds
         * left unchecked (allowance spent), play-outs, responses enumerated, lines whose P(belief) fell.
         */
        public long respLines, respWorlds, respRefuted, respUnchecked, respNodes, respResponses, respLowered;
        /** solver-gaps-1104 (engineAltLines): alternate engine lines verified, and taken; only when non-zero. */
        public long altLines, altTaken;
        /** Review #148: the shared MIN's cap events by reason (process-local diagnostics; never in a label or sha). */
        public final java.util.Map<String, Long> oppMinCapReasons = new java.util.TreeMap<>();

        void noteMinCaps(final java.util.Map<String, Integer> caps) {
            for (java.util.Map.Entry<String, Integer> e : caps.entrySet()) oppMinCapReasons.merge(e.getKey(), (long) e.getValue(), Long::sum);
            if (OppMin.DEBUG) System.err.println("[oppmin] cut " + caps);
        }

        /**
         * coverage-fix-1102 (meter only; never read to decide, emitted only when non-empty): per cut world, the reasons
         * of the cap that withheld it (the first cap, gap reasons split: multi-target, unreadable, ...; oppNodes,
         * oppResponses, oppDepth, branch), and up to three answer cards behind each reason of the world's tree.
         */
        public final java.util.Map<String, Long> oppMinCutReasons = new java.util.TreeMap<>();
        public final java.util.Map<String, java.util.TreeSet<String>> oppMinCapCards = new java.util.TreeMap<>();
        /** coverage-fix-1102 (meter only): engine cap stops by counter, and the largest value and limit seen there. */
        public final java.util.Map<String, Long> engineCapReasons = new java.util.TreeMap<>();
        /**
         * coverage-fix-1102: emit the reason fields above in {@link #toJson} (false = the default: the row's meter is
         * byte-identical to before; set from the label spec's {@code meterReasons}).
         */
        public boolean reasons;
        public final java.util.Map<String, long[]> engineCapAt = new java.util.TreeMap<>();

        static java.util.List<String> capReasons(final String cap) {
            if (cap == null) return java.util.List.of("oppMin");
            if (cap.startsWith("nodes")) return java.util.List.of("oppNodes");
            if (cap.startsWith("truncated")) return java.util.List.of("oppResponses");
            if (cap.startsWith("budget")) return java.util.List.of("oppDepth");
            if (cap.startsWith("gap:")) return java.util.Arrays.asList(cap.substring(4).split("\\+"));
            if (cap.startsWith("branch:mana-sim")) return java.util.List.of("mana-sim");
            if (cap.startsWith("branch:")) return java.util.List.of("branch");
            return java.util.List.of(cap);
        }

        void noteMinCut(final String cap, final java.util.Map<String, java.util.TreeSet<String>> cards) {
            for (String r : capReasons(cap)) oppMinCutReasons.merge(r, 1L, Long::sum);
            for (java.util.Map.Entry<String, java.util.TreeSet<String>> e : cards.entrySet()) {
                for (String r : capReasons(e.getKey())) {
                    oppMinCapCards.computeIfAbsent(r, k -> new java.util.TreeSet<>()).addAll(e.getValue());
                }
            }
        }

        void noteEngineCap(final String why, final long value, final long limit) {
            engineCapReasons.merge(why, 1L, Long::sum);
            final long[] at = engineCapAt.computeIfAbsent(why, k -> new long[2]);
            at[0] = Math.max(at[0], value);
            at[1] = Math.max(at[1], limit);
        }
        /** labeller-v2 (a): engine moves applied to rank choice variants; reported only when non-zero. */
        public long cacApplies;
        /** solver-cap12 round 3 (R3-1): ladder escalations, the lines they found, their nodes and steps; only when non-zero. */
        public long ladderRuns, ladderFound, ladderNodes, ladderSteps;
        /** endstep-rows-1103 (engineEndStepOpen): end-step engine gates skipped; reported only when non-zero. */
        public long endStepOpens;
        /** endstep-rows-1103 (loopMarginal): lines whose loop count was chosen by marginal value; only when non-zero. */
        public long loopMarginals;
        /** endstep-rows-1103 (loopMarginal): variants whose evaluation capped (unresolved, excluded; not row caps). */
        public long loopVariantCaps;
        /**
         * rollout-budget-1101: decisions that spent their Forge AI work cap ({@link forge.ai.AiWork#CAP}), and play-outs
         * cut by it (not wins); reported only when non-zero.
         */
        public long aiWorkCaps, playoutAiWorkCuts;

        public Meter copy() {
            final Meter m = new Meter();
            m.reasons = reasons;
            m.add(this, 1);
            return m;
        }

        void add(final Meter o, final int sign) {
            offenses += sign * o.offenses;
            plannerNodes += sign * o.plannerNodes;
            plannerTimeouts += sign * o.plannerTimeouts;
            engineRuns += sign * o.engineRuns;
            engineNodes += sign * o.engineNodes;
            engineSteps += sign * o.engineSteps;
            engineCards += sign * o.engineCards;
            engineNanos += sign * o.engineNanos;
            enginePathPrunes += sign * o.enginePathPrunes;
            engineLifetimeStops += sign * o.engineLifetimeStops;
            engineDeadlineStops += sign * o.engineDeadlineStops;
            engineCapStops += sign * o.engineCapStops;
            engineWallCuts += sign * o.engineWallCuts;
            lines += sign * o.lines;
            worlds += sign * o.worlds;
            playouts += sign * o.playouts;
            playoutSteps += sign * o.playoutSteps;
            evalDeadlineStops += sign * o.evalDeadlineStops;
            evalCapStops += sign * o.evalCapStops;
            playoutWallCuts += sign * o.playoutWallCuts;
            playoutStepCaps += sign * o.playoutStepCaps;
            playoutCallCaps += sign * o.playoutCallCaps;
            oppMinNodes += sign * o.oppMinNodes;
            oppMinLoopMemo += sign * o.oppMinLoopMemo;
            oppBeliefMins += sign * o.oppBeliefMins;
            respLines += sign * o.respLines; // opp-response-1108
            respWorlds += sign * o.respWorlds;
            respRefuted += sign * o.respRefuted;
            respUnchecked += sign * o.respUnchecked;
            respNodes += sign * o.respNodes;
            respResponses += sign * o.respResponses;
            respLowered += sign * o.respLowered;
            oppBeliefNodes += sign * o.oppBeliefNodes;
            oppBeliefNonForge += sign * o.oppBeliefNonForge;
            altLines += sign * o.altLines;
            altTaken += sign * o.altTaken;
            oppMinCuts += sign * o.oppMinCuts;
            cacApplies += sign * o.cacApplies;
            ladderRuns += sign * o.ladderRuns;
            ladderFound += sign * o.ladderFound;
            ladderNodes += sign * o.ladderNodes;
            ladderSteps += sign * o.ladderSteps;
            endStepOpens += sign * o.endStepOpens;
            loopMarginals += sign * o.loopMarginals;
            loopVariantCaps += sign * o.loopVariantCaps;
            aiWorkCaps += sign * o.aiWorkCaps;
            playoutAiWorkCuts += sign * o.playoutAiWorkCuts;
            // coverage-fix-1102 (meter only): counts add with the sign; card names and maxima only add (since() keeps them)
            for (java.util.Map.Entry<String, Long> e : o.oppMinCutReasons.entrySet()) oppMinCutReasons.merge(e.getKey(), sign * e.getValue(), Long::sum);
            for (java.util.Map.Entry<String, Long> e : o.engineCapReasons.entrySet()) engineCapReasons.merge(e.getKey(), sign * e.getValue(), Long::sum);
            oppMinCutReasons.values().removeIf(v -> v == 0);
            engineCapReasons.values().removeIf(v -> v == 0);
            if (sign > 0) {
                for (java.util.Map.Entry<String, java.util.TreeSet<String>> e : o.oppMinCapCards.entrySet()) {
                    oppMinCapCards.computeIfAbsent(e.getKey(), k -> new java.util.TreeSet<>()).addAll(e.getValue());
                }
                for (java.util.Map.Entry<String, long[]> e : o.engineCapAt.entrySet()) {
                    final long[] at = engineCapAt.computeIfAbsent(e.getKey(), k -> new long[2]);
                    at[0] = Math.max(at[0], e.getValue()[0]);
                    at[1] = Math.max(at[1], e.getValue()[1]);
                }
            }
        }

        /** This meter minus {@code before} (one call's work), or a copy when {@code before} is null. */
        public Meter since(final Meter before) {
            final Meter m = copy();
            if (before != null) {
                m.add(before, -1);
            }
            return m;
        }

        /** Adds {@code o} into this one (a check that ran several solvers). */
        public void plus(final Meter o) {
            add(o, 1);
        }

        /** Any stop a wall clock caused (the ms budgets bound this work). */
        public boolean msBound() {
            return plannerTimeouts + engineDeadlineStops + engineWallCuts + evalDeadlineStops + playoutWallCuts > 0;
        }

        public com.google.gson.JsonObject toJson() {
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("offenses", offenses);
            o.addProperty("planner_nodes", plannerNodes);
            o.addProperty("planner_timeouts", plannerTimeouts);
            o.addProperty("engine_runs", engineRuns);
            o.addProperty("engine_nodes", engineNodes);
            o.addProperty("engine_steps", engineSteps);
            o.addProperty("engine_cards", engineCards);
            o.addProperty("engine_ms", engineNanos / 1_000_000);
            o.addProperty("engine_path_prunes", enginePathPrunes);
            o.addProperty("engine_lifetime_stops", engineLifetimeStops);
            o.addProperty("engine_deadline_stops", engineDeadlineStops);
            o.addProperty("engine_cap_stops", engineCapStops);
            o.addProperty("engine_wall_cuts", engineWallCuts);
            o.addProperty("lines", lines);
            o.addProperty("worlds", worlds);
            o.addProperty("playouts", playouts);
            o.addProperty("playout_steps", playoutSteps);
            o.addProperty("eval_deadline_stops", evalDeadlineStops);
            o.addProperty("eval_cap_stops", evalCapStops);
            o.addProperty("playout_wall_cuts", playoutWallCuts);
            o.addProperty("playout_step_caps", playoutStepCaps);
            o.addProperty("playout_call_caps", playoutCallCaps);
            o.addProperty("ms_bound", msBound());
            if (altLines != 0) {
                o.addProperty("alt_lines", altLines); // solver-gaps-1104
                o.addProperty("alt_taken", altTaken);
            }
            if (oppBeliefMins != 0) {
                o.addProperty("opp_belief_mins", oppBeliefMins); // opp-belief-1104
                o.addProperty("opp_belief_nodes", oppBeliefNodes);
                o.addProperty("opp_belief_non_forge", oppBeliefNonForge);
            }
            if (respLines != 0) {
                o.addProperty("opp_resp_lines", respLines); // opp-response-1108
                o.addProperty("opp_resp_worlds", respWorlds);
                o.addProperty("opp_resp_refuted", respRefuted);
                o.addProperty("opp_resp_unchecked", respUnchecked);
                o.addProperty("opp_resp_nodes", respNodes);
                o.addProperty("opp_resp_responses", respResponses);
                o.addProperty("opp_resp_lowered", respLowered);
            }
            if (oppMinLoopMemo != 0) {
                o.addProperty("opp_min_loop_memo", oppMinLoopMemo); // solver-gaps-1104
            }
            if (oppMinNodes != 0 || oppMinCuts != 0) {
                o.addProperty("opp_min_nodes", oppMinNodes);
                o.addProperty("opp_min_cuts", oppMinCuts);
            }
            if (cacApplies != 0) {
                o.addProperty("cac_applies", cacApplies);
            }
            if (ladderRuns != 0) {
                o.addProperty("ladder_runs", ladderRuns);
                o.addProperty("ladder_found", ladderFound);
                o.addProperty("ladder_nodes", ladderNodes);
                o.addProperty("ladder_steps", ladderSteps);
            }
            if (endStepOpens != 0) {
                o.addProperty("end_step_opens", endStepOpens); // endstep-rows-1103
            }
            if (loopMarginals != 0) {
                o.addProperty("loop_marginals", loopMarginals); // endstep-rows-1103
                o.addProperty("loop_variant_caps", loopVariantCaps);
            }
            if (aiWorkCaps != 0 || playoutAiWorkCuts != 0) {
                o.addProperty("ai_work_caps", aiWorkCaps);
                o.addProperty("playout_ai_work_cuts", playoutAiWorkCuts);
            }
            if (reasons && !oppMinCutReasons.isEmpty()) {
                // coverage-fix-1102 (meter only): why the cut worlds were cut, and up to three answer cards per reason
                final com.google.gson.JsonObject r = new com.google.gson.JsonObject();
                for (java.util.Map.Entry<String, Long> e : oppMinCutReasons.entrySet()) r.addProperty(e.getKey(), e.getValue());
                o.add("opp_min_cut_reasons", r);
                final com.google.gson.JsonObject c = new com.google.gson.JsonObject();
                for (java.util.Map.Entry<String, java.util.TreeSet<String>> e : oppMinCapCards.entrySet()) {
                    final com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                    for (String n : e.getValue()) {
                        if (a.size() >= 3) break;
                        a.add(n);
                    }
                    c.add(e.getKey(), a);
                }
                o.add("opp_min_cap_cards", c);
                final com.google.gson.JsonObject ev = new com.google.gson.JsonObject();
                for (java.util.Map.Entry<String, Long> e : oppMinCapReasons.entrySet()) ev.addProperty(e.getKey(), e.getValue());
                o.add("opp_min_cap_events", ev);
            }
            if (reasons && !engineCapReasons.isEmpty()) {
                // coverage-fix-1102 (meter only): which engine counter stopped the searches, its largest value and limit
                final com.google.gson.JsonObject r = new com.google.gson.JsonObject();
                for (java.util.Map.Entry<String, Long> e : engineCapReasons.entrySet()) {
                    final com.google.gson.JsonObject x = new com.google.gson.JsonObject();
                    x.addProperty("stops", e.getValue());
                    final long[] at = engineCapAt.get(e.getKey());
                    if (at != null) {
                        x.addProperty("max_value", at[0]);
                        x.addProperty("limit", at[1]);
                    }
                    r.add(e.getKey(), x);
                }
                o.add("engine_cap_reasons", r);
            }
            return o;
        }
    }

    /** bvw-step1-build-1024: the cards in a game (every player's zones and the stack): a copy's size. */
    public static int cardCount(Game g) {
        return ForcedWinEngine.cardCount(g);
    }

    /** bvw-step1-build-1024 (review #3): the least Forge AI timeout a counter-budget run accepts, seconds. */
    public static final int COUNTER_MIN_AI_TIMEOUT_SEC = 600;

    /**
     * bvw-step1-build-1024 (review #3): Forge AI keeps two wall clocks of its own that counter budgets do not replace: the
     * AI timeout of its spell evaluation ({@code Game.AI_TIMEOUT}, copied into every game copy) and the parallel attack
     * planner unless {@code -Dforge.bench.sequentialAi=true}. A counter-budget run refuses to start unless both are
     * non-binding. Null when they are; else the refusal.
     */
    public static String counterRuntimeRefusal(int aiTimeoutSec) {
        if (!Boolean.getBoolean("forge.bench.sequentialAi")) {
            return "refused: a counter-budget run needs -Dforge.bench.sequentialAi=true (Forge AI's parallel attack planner "
                    + "has a wall clock)";
        }
        if (aiTimeoutSec < COUNTER_MIN_AI_TIMEOUT_SEC) {
            return "refused: a counter-budget run needs aiTimeoutSec >= " + COUNTER_MIN_AI_TIMEOUT_SEC + " (Forge AI's spell "
                    + "evaluation has a wall clock), not " + aiTimeoutSec;
        }
        return null;
    }

    /** bvw-step1-build-1024: this solver's work so far (read-only diagnostics; {@link Meter}). */
    public final Meter meter = new Meter();
    /** bvw-step1-build-1024: per-decision receipts of the meter, -Dforge.ai.forcedWin.meter=true (diagnostics only). */
    static final boolean METER_RECEIPTS = Boolean.getBoolean("forge.ai.forcedWin.meter");
    /** bvw-step1-build-1024, budget=counter: the play-out steps when the current offense decision began. */
    private long decisionSteps0;

    /** bvw-step1-build-1024: budget=counter and the decision's evalSteps are spent (never true on the ms path). */
    private boolean evalStepsSpent() {
        if (engineEvalBudget > 0) {
            // R3-2: an engine line's evaluation in label mode counts its own steps against engineEvalSteps
            return cfg.counter && meter.playoutSteps - engineEvalBase >= engineEvalBudget;
        }
        return cfg.counter && cfg.evalSteps > 0 && meter.playoutSteps - decisionSteps0 >= cfg.evalSteps;
    }

    /** R3-2 (engineEvalSteps; 0 = none): the budget and start of the engine line's evaluation in progress. */
    private long engineEvalBudget, engineEvalBase;

    /** bvw-step1-build-1024: a deadline (System.nanoTime) on the ms path; none (Long.MAX_VALUE) with budget=counter. */
    private long deadlineAt(final long msPathDeadline) {
        return cfg.counter ? Long.MAX_VALUE : msPathDeadline;
    }

    public ForcedWinSolver(Player me, Config cfg) {
        this(me, cfg, null);
    }

    /** search-bones-1015 (d): a solver whose affordance reader is shared (null = its own). */
    ForcedWinSolver(Player me, Config cfg, ForcedWinModel.Reader shared) {
        this.me = me;
        this.cfg = cfg;
        this.reader = shared != null ? shared : new ForcedWinModel.Reader();
        this.reader.seed = cfg.seed;
        if (cfg.planLoops && this.reader.loopProbe == null) {
            this.reader.loopProbe = (live, pm, c, t) -> fillLoops(cfg, live, pm, c, t); // solver-distill-cc-1109
        }
        // labeller-v2 (c): a planScry solver learns scry / surveil (own template keys); never the shared v1 reader
        // work-units-1107: readerPerGame gives this solver its own reader preloaded as the shared one starts
        this.oppReader = cfg.sharedReader && !cfg.planScry ? (cfg.readerPerGame ? load() : sharedOppReader())
                : new ForcedWinModel.Reader();
        if (cfg.planScry) {
            this.reader.planScry = true;
            this.oppReader.planScry = true;
        }
    }

    public Config config() {
        return cfg;
    }

    /** Tests only: this solver's affordance reader. */
    ForcedWinModel.Reader readerForTest() {
        return reader;
    }

    /** bvw-step1-build-1024: the seat's game (the generator's recorded runtime). */
    Game seatGame() {
        return me.getGame();
    }

    public boolean lineActive() {
        return active != null && !active.done;
    }

    /** A decision at our priority: act (sa), pass (null with acted), or no opinion. */
    public static final class Decision {
        public final boolean owned;
        public final SpellAbility action;

        Decision(boolean owned, SpellAbility action) {
            this.owned = owned;
            this.action = action;
        }

        static final Decision NONE = new Decision(false, null);
        static final Decision PASS = new Decision(true, null);
    }

    /** The live seat's priority: follow the active line, or look for one. Never throws. */
    public Decision decide(Game live) {
        try {
            return decide0(live);
        } catch (RuntimeException | StackOverflowError e) {
            receipt("FORCED_WIN error " + e);
            active = null;
            return Decision.NONE;
        }
    }

    private Decision decide0(Game live) {
        if (active != null && !active.done && !live.getStack().isEmpty()) {
            // a counter of our line's spell: counter it back when we can
            final SpellAbility guard = protect(live, me);
            if (guard != null) {
                receipt("FORCED_WIN protect turn=" + live.getPhaseHandler().getTurn() + " with=" + guard.getHostCard().getName());
                return new Decision(true, guard);
            }
        }
        if (active != null && !active.done) {
            final Follower.Next n = active.next(live, me);
            if (n.action != null) {
                return new Decision(true, n.action);
            }
            if (n.waiting) {
                return Decision.PASS;
            }
            if (n.broken) {
                receipt("FORCED_WIN broken" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " at=" + active.cursor + " "
                        + n.why);
                replans++;
                active = null;
                noLineKey = null;
            } else {
                // the line is played out: it has given up the turn or is waiting for its win to resolve
                return n.waitingEnd ? Decision.PASS : Decision.NONE;
            }
        }
        if (!cfg.act) {
            // search-bones-1015: a follower only (the search decides when to take a line)
            return Decision.NONE;
        }
        final PhaseHandler ph = live.getPhaseHandler();
        if (!live.getStack().isEmpty()) {
            // defense: the opponent's spell on top, when resolving it would give them a forced win
            return defendCounter(live);
        }
        final boolean ourMain = ph.isPlayerTurn(me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
        final boolean theirEnd = !ph.isPlayerTurn(me) && ph.is(PhaseType.END_OF_TURN);
        if (!ourMain && !theirEnd) {
            return Decision.NONE;
        }
        final Line line = offense(live);
        if (line == null) {
            return Decision.NONE;
        }
        active = follower(line);
        taken++;
        final Follower.Next n = active.next(live, me);
        if (n.action != null) {
            return new Decision(true, n.action);
        }
        if (cfg.adoptAttack && n.waiting && active.attackPending) {
            // bvw-step1-build-1024: the line's first step is our attack. Keep the line and pass to combat, where
            // answerAttack declares its alpha strike (off: the line is dropped here and Forge AI plays the turn)
            receipt("FORCED_WIN adopt-attack" + tag() + " turn=" + ph.getTurn() + " phase=" + ph.getPhase() + " line="
                    + line.steps);
            return Decision.PASS;
        }
        active = null;
        return Decision.NONE;
    }

    // ------------------------------------------------------------------ pile memory (solver-gaps-1015)

    /**
     * Ids of our library cards whose position the seat knows: cards that moved into our library (we put them back, a
     * pile we chose, a card from a public zone) or whose order we chose. A shuffle of our library forgets them all.
     */
    final Set<Integer> placed = new HashSet<>();
    private boolean watching;

    /** Live-game listener (read-only): moves into our library and shuffles of it. */
    public static final class LibraryWatch {
        final ForcedWinSolver solver;

        LibraryWatch(ForcedWinSolver solver) {
            this.solver = solver;
        }

        @com.google.common.eventbus.Subscribe
        public void on(forge.game.event.GameEventShuffle e) {
            if (e.player() != null && e.player().getId() == solver.me.getId()) {
                solver.placed.clear();
            }
        }

        @com.google.common.eventbus.Subscribe
        public void on(forge.game.event.GameEventCardChangeZone e) {
            if (e.card() != null && e.to() != null && e.to().zoneType() == ZoneType.Library && e.to().player() != null
                    && e.to().player().getId() == solver.me.getId()) {
                solver.placed.add(e.card().getId());
            }
        }
    }

    /** order-memory-1102 (infoState): the game's state; the known top and the other known ids at the last read. */
    private InfoStates infoStates;
    private final Set<Integer> isTop = new HashSet<>(), isPinned = new HashSet<>();

    /** Start watching our library (once; the seat's first decision). */
    void watch(Game live) {
        if (watching) {
            return;
        }
        watching = true;
        if (cfg.infoState) {
            infoStates = InfoStates.find(live); // review #172 S2: find only (attached at bind, on the game thread)
        } else {
            live.subscribeToEvents(new LibraryWatch(this));
        }
        if (cfg.frameMemory) {
            // EVAL only (frames mined mid-pile): the seat built its library top before the frame began
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                placed.add(c.getId());
            }
        }
    }

    /** Our seat chose the order of these cards going to our library (a pile, a put-back, a look at our top). */
    public void notePlaced(Iterable<Card> cards) {
        if (cards == null) {
            return;
        }
        for (Card c : cards) {
            if (c != null && c.getOwner() == me) {
                placed.add(c.getId());
            }
        }
    }

    /**
     * known-lib-1102 (empty unless {@link #notePinned} is called: HonestLabel's knownLib only): ids of our library cards
     * whose position the seat knows at the root, beyond the known top (a fixed offset from the top or the bottom, not
     * necessarily contiguous: Teferi's third from the top under two unknown cards). Every world this solver shuffles keeps
     * them at their index ({@link #pinnedIn}).
     */
    final Set<Integer> pinned = new HashSet<>();

    /** known-lib-1102: our seat knows the positions of these library cards (each stays at its index). */
    public void notePinned(Iterable<Card> cards) {
        if (cards == null) {
            return;
        }
        for (Card c : cards) {
            if (c != null && c.getOwner() == me) {
                pinned.add(c.getId());
            }
        }
    }

    /** known-lib-1102: the pinned ids in {@code p}'s library (a copy keeps the ids); empty when none are noted. */
    Set<Integer> pinnedIn(Player p) {
        if (pinned.isEmpty() && isPinned.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<Integer> out = new HashSet<>();
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            if (pinned.contains(c.getId()) || isPinned.contains(c.getId())) {
                out.add(c.getId());
            }
        }
        return out;
    }

    /** The known top of our library: the longest prefix whose cards the seat placed; null when none. */
    List<Integer> knownTop(Game live) {
        watch(live);
        if (cfg.infoState && infoStates == null) {
            infoStates = InfoStates.find(live);
        }
        if (cfg.infoState) {
            // order-memory-1102: the known top = the leading run of (frame memory) + InfoState(me)'s known; the other
            // known cards are pinned at their index in the play-out worlds. Not attached, or a failed read: none known.
            Set<Integer> k = Collections.emptySet();
            if (infoStates != null) {
                try {
                    k = infoStates.known(me, me);
                } catch (RuntimeException e) {
                    infoStates.failClosedRead(e);
                    k = Collections.emptySet();
                }
            }
            isTop.clear();
            isPinned.clear();
            boolean lead = true;
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                final boolean kn = placed.contains(c.getId()) || k.contains(c.getId());
                if (lead && kn) {
                    if (k.contains(c.getId())) {
                        isTop.add(c.getId());
                    }
                } else {
                    lead = false;
                    if (k.contains(c.getId())) {
                        isPinned.add(c.getId());
                    }
                }
            }
        }
        final List<Integer> out = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            if (!placed.contains(c.getId()) && !isTop.contains(c.getId())) {
                break;
            }
            out.add(c.getId());
        }
        return out.isEmpty() ? null : out;
    }

    private int placedCount(Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            if (!placed.contains(c.getId()) && !isTop.contains(c.getId())) {
                break;
            }
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ q^8: hidden own-library reads (e1-q8-1101)

    /**
     * e1-q8-1101 (DESIGN-R3 ruling 2026-11-01 06:40:01, the q^8 blocker; {@link Config#libWatch} only): one play-out's
     * record of the cards hidden from the seat at its start that it READ from the seat's own library. Hidden = every
     * library card but the known top the play-out keeps in place ({@link Line#knownTop}); a card the seat puts into its
     * library where every card above it is known (a put-back, Doomsday's pile, a card searched to the top) becomes known,
     * and a shuffle of the library forgets them all.
     *
     * <p>A READ: a hidden card leaving the library for any zone but exile (hand, stack, battlefield, graveyard, ...); a
     * hidden card moved within the library (a scry kept on top or bottomed, Ponder's or Top's reorder, a dig's rest to
     * the bottom) unless a search of our library picked it (Doomsday's pile); a hidden card exiled from the library
     * (Necropotence, impulse draws, a dig's exile), unless that exile leaves no hidden card in the library (Doomsday's
     * rest: then the order and the identities of what left do not matter), and a hidden exiled card that later leaves
     * exile; a hidden card shown to either seat outside a search (reveals, scry / surveil / reorder choices, temporary
     * shows: Thassa's Oracle's look, Delver, Ponder). Review #161 S1-1. Takes are positional within the top {@link #WINDOW} hidden cards, at the bottom, or after a
     * change of the library the events did not explain, and then a read even when a search took them (a tutor in the
     * window is a false dependence: fail closed). A deeper take is a read unless a search of our library picked that card
     * (an identity search does not read the order).
     *
     * <p>Fails closed: a card the shadow does not know, a card that left the library with no event, or any error while
     * watching makes the play-out library-dependent ({@link #failed}). Read-only: it never changes the copy.
     */
    static final class HiddenWatch {
        /** The positional window below the known top. */
        static final int WINDOW = 12;
        /** {@link Eval#hiddenTakes} of a play-out whose detection failed (more than any exact-draw depth). */
        static final int FAILED = 1 << 20;

        final Player me;
        final int meId;
        /** Library card ids whose position the seat knows. */
        final Set<Integer> known = new HashSet<>();
        /** Hidden cards exiled from the library (a later move out of exile is a read). */
        final Set<Integer> exiled = new HashSet<>();
        /** Hidden cards just exiled from the library: reads unless the exile left no hidden card there ({@link #settle}). */
        final Set<Integer> pendingExile = new java.util.TreeSet<>();
        /** Cards a search of our library picked. */
        final Set<Integer> searched = new HashSet<>();
        /** The hidden cards read, by id. */
        final Set<Integer> reads = new java.util.TreeSet<>();
        /** Exact-draw: the arranged cards (a read of any other hidden card is {@link #uncovered}); null otherwise. */
        final Set<Integer> cover;
        /** The library's order after the last event. */
        List<Integer> snap;
        boolean failed, uncovered;
        /** A search of our library is choosing (its look at the whole library is not a peek). */
        boolean inSearch;
        String why;

        HiddenWatch(Player me, int knownTop, Set<Integer> cover) {
            this(me, knownTop, null, cover);
        }

        /** known-lib-1102: as above, the root's pinned positions ({@code pinnedIds}; empty = none) known too. */
        HiddenWatch(Player me, int knownTop, Set<Integer> pinnedIds, Set<Integer> cover) {
            this.me = me;
            this.meId = me.getId();
            this.cover = cover;
            this.snap = ids();
            for (int i = 0; i < Math.min(Math.max(knownTop, 0), snap.size()); i++) {
                known.add(snap.get(i));
            }
            for (int x : snap) {
                if (pinnedIds != null && pinnedIds.contains(x)) {
                    known.add(x);
                }
            }
        }

        List<Integer> ids() {
            final List<Integer> out = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                out.add(c.getId());
            }
            return out;
        }

        private boolean mine(forge.game.zone.ZoneView z, ZoneType t) {
            return z != null && z.zoneType() == t && z.player() != null && z.player().getId() == meId;
        }

        boolean dependent() {
            return failed || !reads.isEmpty();
        }

        int takes() {
            return failed ? FAILED : reads.size();
        }

        private void read(int id, String how) {
            reads.add(id);
            if (why == null) {
                why = how + ":" + id;
            }
            if (cover != null && !cover.contains(id)) {
                uncovered = true;
            }
        }

        private void fail(String how) {
            failed = true;
            uncovered = true;
            if (why == null) {
                why = "failed:" + how;
            }
        }

        /**
         * Exiles from the library since the last other event: reads, unless no hidden card is left in the library (a
         * whole-library exile, Doomsday's rest).
         */
        private void settle() {
            if (pendingExile.isEmpty()) {
                return;
            }
            boolean hiddenLeft = false;
            for (int x : ids()) {
                hiddenLeft |= !known.contains(x);
            }
            if (hiddenLeft) {
                for (int x : pendingExile) {
                    read(x, "exile");
                }
            }
            pendingExile.clear();
        }

        /** Hidden cards that left the library with no event of their own: reads (fail closed). */
        private void silentLoss(List<Integer> before, List<Integer> after) {
            final Set<Integer> now = new HashSet<>(after);
            for (int x : before) {
                if (!now.contains(x) && !known.contains(x)) {
                    read(x, "silent");
                }
            }
        }

        private int hiddenAbove(List<Integer> order, int i) {
            int n = 0;
            for (int k = 0; k < i; k++) {
                if (!known.contains(order.get(k))) {
                    n++;
                }
            }
            return n;
        }

        @com.google.common.eventbus.Subscribe
        public void on(forge.game.event.GameEventShuffle e) {
            try {
                if (e.player() != null && e.player().getId() == meId) {
                    settle();
                    final List<Integer> now = ids();
                    silentLoss(snap, now);
                    known.clear();
                    snap = now;
                }
            } catch (RuntimeException ex) {
                fail("shuffle " + ex);
            }
        }

        @com.google.common.eventbus.Subscribe
        public void on(forge.game.event.GameEventCardChangeZone e) {
            try {
                change(e);
            } catch (RuntimeException ex) {
                fail("move " + ex);
            }
        }

        private void change(forge.game.event.GameEventCardChangeZone e) {
            final boolean fromLib = mine(e.from(), ZoneType.Library), toLib = mine(e.to(), ZoneType.Library);
            if (e.card() == null) {
                if (fromLib || toLib) {
                    fail("no card");
                }
                return;
            }
            final int id = e.card().getId();
            final ZoneType to = e.to() == null ? null : e.to().zoneType();
            if (!(fromLib && to == ZoneType.Exile)) {
                settle();
            }
            if (!fromLib && !toLib) {
                if (exiled.contains(id) && e.from() != null && e.from().zoneType() == ZoneType.Exile && to != ZoneType.Exile) {
                    exiled.remove(id);
                    read(id, "exile->" + to);
                }
                return;
            }
            final List<Integer> now = ids();
            if (fromLib) {
                final int i = snap.indexOf(id);
                final List<Integer> exp = new ArrayList<>(snap);
                exp.remove(Integer.valueOf(id));
                final List<Integer> cur = new ArrayList<>(now);
                if (toLib) {
                    cur.remove(Integer.valueOf(id));
                }
                final boolean silent = !exp.equals(cur);
                if (silent) {
                    silentLoss(exp, cur);
                }
                final boolean wasKnown = known.remove(id);
                if (!wasKnown && !toLib) {
                    if (i < 0) {
                        read(id, "untracked->" + to);
                    } else if (to == ZoneType.Exile) {
                        exiled.add(id);
                        pendingExile.add(id); // review #161 S3-6: a read unless the whole hidden library goes
                    } else {
                        final boolean window = silent || i == snap.size() - 1 || hiddenAbove(snap, i) < WINDOW;
                        if (window || !searched.contains(id)) {
                            read(id, (window ? "top" : "deep") + "->" + to);
                        }
                    }
                } else if (!wasKnown && !searched.contains(id)) {
                    // review #161 S1-1: a hidden card moved within the library (scry, a reorder, a dig's rest to the
                    // bottom) was seen by the seat: a read unless a search picked it (Doomsday's pile)
                    read(id, i < 0 ? "untracked->Library" : "library->library");
                }
            }
            if (toLib) {
                final int j = now.indexOf(id);
                boolean top = j >= 0;
                for (int k = 0; top && k < j; k++) {
                    top = known.contains(now.get(k));
                }
                if (top) {
                    known.add(id);
                } else {
                    known.remove(id);
                }
                exiled.remove(id);
                if (!fromLib) {
                    final List<Integer> cur = new ArrayList<>(now);
                    cur.remove(Integer.valueOf(id));
                    if (!snap.equals(cur)) {
                        silentLoss(snap, cur);
                    }
                }
            }
            snap = now;
        }

        /** Cards revealed to a seat (either seat's controller): hidden cards of our library seen outside a search. */
        void peek(Iterable<Integer> ids, ZoneType zone, int ownerId) {
            try {
                if (inSearch || zone != ZoneType.Library || ownerId != meId || ids == null) {
                    return;
                }
                settle();
                final List<Integer> now = ids();
                for (int id : ids) {
                    if (!known.contains(id) && now.contains(id)) {
                        read(id, "peek");
                    }
                }
            } catch (RuntimeException ex) {
                fail("peek " + ex);
            }
        }

        /** Our library cards among {@code cards} were shown to a seat (scry, surveil, reorder, a temporary show). */
        void peekOwnLibrary(Iterable<Card> cards) {
            if (cards == null) {
                return;
            }
            final List<Integer> ids = new ArrayList<>();
            for (Card c : cards) {
                if (c != null && c.getOwner() != null && c.getOwner().getId() == meId) {
                    ids.add(c.getId());
                }
            }
            peek(ids, ZoneType.Library, meId);
        }

        void peekCards(Iterable<Card> cards, ZoneType zone, Player owner) {
            if (cards == null || owner == null) {
                return;
            }
            final List<Integer> ids = new ArrayList<>();
            for (Card c : cards) {
                if (c != null) {
                    ids.add(c.getId());
                }
            }
            peek(ids, zone, owner.getId());
        }

        void peekViews(Iterable<forge.game.card.CardView> cards, ZoneType zone, forge.game.player.PlayerView owner) {
            if (cards == null || owner == null) {
                return;
            }
            final List<Integer> ids = new ArrayList<>();
            for (forge.game.card.CardView c : cards) {
                if (c != null) {
                    ids.add(c.getId());
                }
            }
            peek(ids, zone, owner.getId());
        }

        /** A search choosing from {@code origin} picked {@code c}. */
        void searchPick(List<ZoneType> origin, Card c) {
            if (c != null && origin != null && origin.contains(ZoneType.Library) && c.getOwner() != null
                    && c.getOwner().getId() == meId) {
                searched.add(c.getId());
            }
        }

        /** The play-out's end: cards that left the library with no event. */
        void end() {
            try {
                settle();
                silentLoss(snap, ids());
            } catch (RuntimeException ex) {
                fail("end " + ex);
            }
        }
    }

    /** e1-q8-1101: the current evaluation's hidden-library record (every play-out of it adds; {@link #evaluate}). */
    private boolean libDep, libUncov;
    /**
     * Review #161 S2-2: a play-out or MIN of the current exact-draw / resample evaluation was cut (wall, step or call
     * cap, Forge AI cap, error, a MIN cap or gap): its 0 is unknown, not a loss, so the result is not exact.
     */
    private boolean libCut;
    /**
     * Follow-up to #161 (re-check S2; false = off, the default): {@link #libResample}'s evaluation runs every belief
     * world even when its unopposed play-out lost, so k/W is over all W worlds (one lost shuffle is not 0/W).
     */
    private boolean libAllWorlds;
    private int libReads;

    private void libNote(HiddenWatch hw) {
        hw.end();
        if (hw.dependent()) {
            libDep = true;
        }
        if (hw.uncovered) {
            libUncov = true;
        }
        libReads = Math.max(libReads, hw.takes());
        if (RECEIPTS && hw.dependent()) {
            receipt("FORCED_WIN lib-read" + tag() + " reads=" + hw.takes() + " first=" + hw.why);
        }
    }

    /** e1-q8-1101: an exact-draw sequence's arrangement of our library in each play-out copy (null = none, the default). */
    private Arrange libArrange;

    static final class Arrange {
        final List<String> seq;
        final List<Card> byId;
        final List<Integer> slots;
        /** The cards {@link DrawExpectation#arrange} puts at the first hidden slots (it picks by name in id order). */
        final Set<Integer> cover = new HashSet<>();

        Arrange(List<String> seq, List<Card> byId, List<Integer> slots) {
            this.seq = seq;
            this.byId = byId;
            this.slots = slots;
            final List<Card> pool = new ArrayList<>(byId);
            for (String n : seq) {
                for (Card c : pool) {
                    if (c.getName().equals(n)) {
                        cover.add(c.getId());
                        pool.remove(c);
                        break;
                    }
                }
            }
        }

        void apply(Player cm, Random rng) {
            DrawExpectation.arrange(cm, seq, byId, slots, rng);
        }
    }

    /** e1-q8-1101: a library-dependent proposal's exact-draw result. */
    public static final class LibExact {
        /** The fraction is exact (every sequence of the hidden reads enumerated and covered); else {@link #why}. */
        public boolean exact;
        public double fraction = Double.NaN;
        public int seqs;
        /** {@link #libResample}: the fresh worlds W. */
        public int worlds;
        public String why;
    }

    /**
     * e1-q8-1101 (ruling 2026-11-01 06:40:01, N5b): the exact expectation of {@code p}'s line over its {@code
     * p.hiddenTakes} (1 or 2) hidden reads: every distinct name sequence of the first hidden slots of our library in
     * {@code live} (without replacement, weighted by count, DrawExpectation#draws), each evaluated as the proof was (the
     * unopposed world and the belief worlds, MIN when oppMin) with those cards arranged at those slots and the rest
     * shuffled; a sequence's value is its belief share when it won unopposed, else 0. Not exact (the caller labels the
     * row sampled) when the sequences exceed {@code seqCap}, a play-out read a hidden card outside the arranged slots,
     * a sequence ran fewer than its worlds, or the Forge AI work cap was spent. Each sequence has the decision's budgets.
     */
    /**
     * known-lib-1102 (review #163 re-check S2-2): a line's evaluation with the decision's own deadline (as libExact
     * gives each sequence), never Long.MAX_VALUE (which overflows where the evaluation adds its grace).
     */
    Eval evaluateBudgeted(Game live, Line line, long seed) {
        return evaluate(live, line, seed, deadlineAt(forge.game.WorkClock.nanoTime() + budgetMs() * 1_000_000L));
    }

    public LibExact libExact(Game live, Proposal p, int seqCap) {
        return libExact(live, p, seqCap, 0L);
    }

    /** As above; {@code key} (the decision's key) is mixed into every sequence's seed (review #161 S3-4). */
    public LibExact libExact(Game live, Proposal p, int seqCap, long key) {
        final LibExact x = new LibExact();
        if (!cfg.libWatch || !p.libDependent || p.hiddenTakes < 1 || p.hiddenTakes > 2) {
            x.why = "takes";
            return x;
        }
        final Line line = (Line) p.line;
        final Set<Integer> knownIds = new HashSet<>();
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        for (int i = 0; i < Math.min(p.knownTop, lib.size()); i++) {
            knownIds.add(lib.get(i).getId());
        }
        knownIds.addAll(pinnedIn(me)); // known-lib-1102: pinned positions are not drawn over
        final DrawExpectation.Draws d = DrawExpectation.draws(me, knownIds, p.hiddenTakes, seqCap, 0, mix(cfg.seed, key));
        if (!d.exact || d.depth < p.hiddenTakes || d.seqs.isEmpty()) {
            x.why = d.exact ? "library" : "seqCap";
            return x;
        }
        double sum = 0;
        for (int s = 0; s < d.seqs.size(); s++) {
            libArrange = new Arrange(d.seqs.get(s), d.unknownById, d.unknownSlots);
            libCut = false;
            decisionSteps0 = meter.playoutSteps;
            if (p.engine && cfg.engineEvalSteps > 0) {
                engineEvalBudget = cfg.engineEvalSteps;
                engineEvalBase = meter.playoutSteps;
            }
            final long workCap = cfg.aiWorkCap();
            final forge.ai.AiWork.Budget outer = forge.ai.AiWork.open(workCap);
            final forge.ai.AiWork.Budget work = workCap > 0 ? forge.ai.AiWork.current() : null;
            Eval e = null;
            try {
                e = evaluate(live, line, mix(mix(cfg.seed, key), 0x9B800L + s),
                        deadlineAt(forge.game.WorkClock.nanoTime() + budgetMs() * 1_000_000L));
            } catch (RuntimeException ex) {
                x.why = "error:" + ex.getClass().getSimpleName();
            } finally {
                forge.ai.AiWork.restore(outer);
                libArrange = null;
                engineEvalBudget = 0;
            }
            if (work != null && work.spent()) {
                x.why = forge.ai.AiWork.CAP;
                return x;
            }
            if (e == null) {
                return x;
            }
            if (e.libUncovered) {
                x.why = "uncovered";
                return x;
            }
            if (libCut) {
                x.why = "cut"; // review #161 S2-2: a cut play-out's 0 is unknown
                return x;
            }
            if (e.pUnopposed >= 1.0 && !cfg.exact && e.worldsRun < cfg.worlds) {
                x.why = "worlds";
                return x;
            }
            sum += d.weights.get(s) * (e.pUnopposed >= 1.0 ? e.pBelief : 0.0);
            receipt("FORCED_WIN lib-exact" + tag() + " seq=" + d.seqs.get(s) + " w=" + d.weights.get(s) + " " + e);
        }
        x.exact = true;
        x.fraction = Math.min(1.0, sum);
        x.seqs = d.seqs.size();
        return x;
    }

    /**
     * Review #161 S2-1: the sampled k/W of a library-dependent proposal, re-estimated on fresh worlds (seeds keyed by the
     * decision, not the worlds that picked the line, so a proof's k = W is not reported as the value): the line's
     * evaluation as the proof ran it (unopposed world and belief worlds, MIN when oppMin), our library shuffled below
     * its known top in each. {@link LibExact#fraction} = k/W, {@link LibExact#worlds} = W; NaN with {@link LibExact#why}
     * when a play-out was cut, the worlds were short, or the Forge AI cap was spent.
     */
    public LibExact libResample(Game live, Proposal p, long key) {
        final LibExact x = new LibExact();
        libCut = false;
        decisionSteps0 = meter.playoutSteps;
        if (p.engine && cfg.engineEvalSteps > 0) {
            engineEvalBudget = cfg.engineEvalSteps;
            engineEvalBase = meter.playoutSteps;
        }
        final long workCap = cfg.aiWorkCap();
        final forge.ai.AiWork.Budget outer = forge.ai.AiWork.open(workCap);
        final forge.ai.AiWork.Budget work = workCap > 0 ? forge.ai.AiWork.current() : null;
        Eval e = null;
        try {
            libAllWorlds = true;
            e = evaluate(live, (Line) p.line, mix(mix(cfg.seed, key), 0x5A3B1EL),
                    deadlineAt(forge.game.WorkClock.nanoTime() + budgetMs() * 1_000_000L));
        } catch (RuntimeException ex) {
            x.why = "error:" + ex.getClass().getSimpleName();
        } finally {
            libAllWorlds = false;
            forge.ai.AiWork.restore(outer);
            engineEvalBudget = 0;
        }
        if (work != null && work.spent()) {
            x.why = forge.ai.AiWork.CAP;
        } else if (e != null && libCut) {
            x.why = "cut";
        } else if (e != null && !cfg.exact && e.worldsRun < cfg.worlds) {
            x.why = "worlds";
        } else if (e != null) {
            // re-check S2: k/W over every belief world that ran (the unopposed world's own shuffle is one sample, not a veto)
            x.worlds = cfg.exact ? 1 : e.worldsRun;
            x.fraction = cfg.exact ? (e.pUnopposed >= 1.0 ? e.pBelief : 0.0) : e.pBelief;
        }
        receipt("FORCED_WIN lib-resample" + tag() + " " + e + " why=" + x.why);
        return x;
    }

    // ------------------------------------------------------------------ offense: width search, then engine

    /** The position key the width search's verdict is cached under (same key, same verdict). */
    String positionKey(Game live, Player me) {
        final StringBuilder sb = new StringBuilder();
        if (cfg.positionKeyFull) {
            // unified-search-1106
            for (Card c : me.getCardsIn(ZoneType.Graveyard)) {
                sb.append(c.getId()).append(',');
            }
            sb.append('m');
            for (byte b : forge.card.MagicColor.WUBRG) {
                sb.append(me.getManaPool().getAmountOfColor(b)).append(',');
            }
            sb.append('|');
        }
        sb.append(live.getPhaseHandler().getTurn()).append(live.getPhaseHandler().getPhase()).append('|')
                .append(me.getLife()).append('|').append(me.getManaPool().totalMana()).append('|')
                .append(me.getLandsPlayedThisTurn()).append('|').append(live.getStack().getSpellsCastThisTurn().size())
                .append('|').append(me.getCardsIn(ZoneType.Library).size()).append('h');
        sb.append('k').append(placedCount(me)).append('|');
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            sb.append(c.getId()).append(',');
        }
        sb.append('b');
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            sb.append(c.getId()).append(c.isTapped() ? 't' : 'u').append(',');
        }
        for (Player o : me.getOpponents()) {
            sb.append('o').append(o.getLife()).append('/').append(o.getCardsIn(ZoneType.Hand).size()).append('/')
                    .append(o.getCardsIn(ZoneType.Library).size());
            for (Card c : o.getCardsIn(ZoneType.Battlefield)) {
                sb.append(c.getId()).append(c.isTapped() ? 't' : 'u').append(',');
            }
        }
        return sb.toString();
    }

    /** Evaluate the position; the chosen line, or null. */
    Line offense(Game live) {
        lastCap = null; // rollout-budget-1101
        final String key = positionKey(live, me);
        if (key.equals(noLineKey)) {
            if (key.equals(capKey)) {
                lastCap = forge.ai.AiWork.CAP; // review #160 S3-6: the cached "no line" of a capped position is the cap
            }
            return null;
        }
        final long t0 = forge.game.WorkClock.nanoTime();
        final int copies0 = copies + engineStats.copies, probes0 = reader.probeStats.probes, eng0 = engineStats.nodes;
        lastOutcome = "?";
        lastPlannerNodes = 0;
        final Meter m0 = METER_RECEIPTS ? meter.copy() : null;
        if (RECEIPTS && GameCopier.SOLVER_COPY_PENDING && GameCopier.hasPending(live)) {
            // gamecopier-fidelity-1030 (diagnostics only): a root whose copies carry pending triggers or commands
            final List<String> gaps = GameCopier.unsupportedPending(live);
            receipt("FORCED_WIN copy-pending offense" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " phase="
                    + live.getPhaseHandler().getPhase() + (gaps == null ? " carried" : " gap=" + gaps));
        }
        // rollout-budget-1101: the decision's Forge AI work is counted against its cap (none = nothing opened)
        final long workCap = cfg.aiWorkCap();
        final forge.ai.AiWork.Budget outer = forge.ai.AiWork.open(workCap);
        final forge.ai.AiWork.Budget work = workCap > 0 ? forge.ai.AiWork.current() : null;
        Line l;
        try {
            l = offense0(live, key);
        } catch (RuntimeException ex) {
            if (work == null || !work.spent()) {
                throw ex;
            }
            l = null; // review #160 S3-5: any error once the cap is spent is the cap (below), not an error
        } finally {
            forge.ai.AiWork.restore(outer);
        }
        if (work != null && forge.ai.AiWork.TRACE) {
            System.err.println("AIWORK used=" + work.used() + " cap=" + workCap + " spent=" + work.spent() + tag() + " turn="
                    + live.getPhaseHandler().getTurn() + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000);
        }
        if (work != null && work.spent()) {
            // a cap hit, never a proof: no line is taken or proposed, whatever was found before the cap
            l = null;
            lastCap = forge.ai.AiWork.CAP;
            lastOutcome = lastOutcome + "+" + forge.ai.AiWork.CAP;
            noLineKey = key; // review #160 S3-6: the same position is not searched again (it would cap again)
            capKey = key;
            meter.aiWorkCaps++;
            if (evalSink != null) {
                evalSink.clear();
            }
            receipt("FORCED_WIN " + forge.ai.AiWork.CAP + tag() + " turn=" + live.getPhaseHandler().getTurn() + " phase="
                    + live.getPhaseHandler().getPhase() + " used=" + work.used() + " cap=" + workCap + " at=" + work.spentAt());
        }
        receipt("FORCED_WIN cost" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " phase=" + live.getPhaseHandler().getPhase()
                + " outcome=" + lastOutcome + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000 + " plannerNodes="
                + lastPlannerNodes + " engineNodes=" + (engineStats.nodes - eng0) + " copies="
                + (copies + engineStats.copies - copies0) + " probes=" + (reader.probeStats.probes - probes0)
                + (work != null ? " aiWork=" + work.used() : ""));
        if (m0 != null) {
            // bvw-step1-build-1024: the decision's work in counter units (diagnostics only; budget calibration)
            System.err.println("FORCED_WIN meter" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " phase="
                    + live.getPhaseHandler().getPhase() + " outcome=" + lastOutcome + " budget="
                    + (cfg.counter ? "counter" : "ms") + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000 + " "
                    + meter.since(m0).toJson());
        }
        return l;
    }

    /** bvw-step1-build-1024: the width search, metered (the same call as before; the meter only reads its result). */
    private ForcedWinPlanner.Result plan(Position pos, ForcedWinPlanner.Limits lim) {
        final ForcedWinPlanner.Result r = ForcedWinPlanner.plan(pos, lim);
        meter.plannerNodes += r.visited;
        if (r.timedOut) {
            meter.plannerTimeouts++;
        }
        return r;
    }

    private static boolean anyUnplayable(ACard a) {
        for (ForcedWinModel.Abil b : a.fromHand) {
            if (b.unplayable) {
                return true;
            }
        }
        for (ForcedWinModel.Abil b : a.onBattlefield) {
            if (b.unplayable) {
                return true;
            }
        }
        return false;
    }

    private String lastOutcome = "?";
    /** unified-search-1106 (unknownWorlds): the engine's line this decision had no verified world. */
    private boolean engineUnverified;
    private int lastPlannerNodes;
    /** rollout-budget-1101: the last offense's cap hit ({@link forge.ai.AiWork#CAP}), or null. */
    private String lastCap;
    /** review #160 S3-6: the position key whose search hit the cap (its cached no-line answer is the cap). */
    private String capKey;

    /** rollout-budget-1101: the last offense (or proposal) spent its Forge AI work cap: {@link forge.ai.AiWork#CAP}, or null. */
    public String lastCap() {
        return lastCap;
    }
    /** kill-line-1106 (report only): planner searches stopped by their limit; engine lines taken after planner lines failed. */
    public int plannerExhausted, planFailEngineTakes;

    /**
     * unified-search-1106 (unknownWorlds): a gated position (no planner line) is cached as having no line, unless the key
     * is on and the engine's line there had no verified world (unknown, not refuted). Key off: always cached.
     */
    static boolean cachesGated(final Config cfg, final boolean engineUnverified) {
        return !(cfg.unknownWorlds && engineUnverified);
    }

    /**
     * unified-search-1106 (unknownWorlds): a declined position is cached as having no line, unless the key is on and its
     * best line had no verified world (unknown, not refuted). Key off: always cached.
     */
    static boolean cachesDeclined(final Config cfg, final Eval best) {
        return !(cfg.unknownWorlds && best != null && best.unverified());
    }

    private Line offense0(Game live, String key) {
        decisions++;
        final int index = decisionIndex++;
        final long t0 = forge.game.WorkClock.nanoTime();
        // bvw-step1-build-1024: budget=counter reads no wall clock (0 = no wall); unified-search-1106: else the hard deadline
        decisionWall = cfg.counter ? 0L
                : hardDeadline != 0 ? hardDeadline : t0 + (budgetMs() + cfg.wallGraceMs) * 1_000_000L;
        decisionStart = t0;
        respEnd = 0L; // opp-response-1108: the check's allowance opens at its first line of this decision
        decisionSteps0 = meter.playoutSteps;
        meter.offenses++;
        final Position pos = readPosition(live, knownTop(live));
        if (pos == null) {
            noLineKey = key;
            return null;
        }
        if (cfg.engineStack && !live.getStack().isEmpty() && GameCopier.stackUnsupported(live) == null) {
            // R3-5: a response (the opponent's item on the stack): the width model plans from an empty stack, so the
            // engine searches the position itself (its gate skipped, as after a failed plan)
            lastOutcome = "stack";
            return engineFallback(live, pos, index, t0, true);
        }
        if (cfg.anyWindow) {
            markWindow(pos, live.getPhaseHandler()); // line-adoption-1107
        }
        final ForcedWinPlanner.Limits lim = limits();
        lim.nodes = cfg.nodes;
        // bvw-step1-build-1024: budget=counter: no width-search wall time (ms < 0 = none); its nodes bound it
        lim.ms = cfg.counter ? -1L : forge.ai.WallClock.budgetMs(cfg.planMs);
        lim.payPolicies = 1;
        // labeller-v2 (c), (e) (off = v1)
        lim.planScry = cfg.planScry;
        lim.planDiscard = cfg.planDiscard;
        lim.planScryMax = cfg.planScryMax;
        lim.payRecord = cfg.payRecord;
        lim.payVariants = cfg.payVariants;
        boolean payOnly = false;
        // POST-FREEZE 4: same-turn lines first (they are preferred, and a pass-the-turn search can fill the line cap
        // before it reaches one); lines that pass the turn only when this turn has none
        lim.horizon = 1;
        ForcedWinPlanner.Result res = cfg.forced ? planCapped(pos, lim) : new ForcedWinPlanner.Result();
        if (res.lines.isEmpty() && cfg.horizon >= 2 && cfg.forced) {
            final int first = res.nodes;
            lim.horizon = cfg.horizon;
            res = planCapped(pos, lim);
            res.nodes += first;
        }
        if (res.lines.isEmpty() && cfg.forced && cfg.paySearch) {
            // solver-gaps-1015 payment search: only where the greedy payment found no line, search the payment orders
            // (this turn, then by our next turn), within the planner's own node and time caps
            final int before = res.nodes;
            lim.payPolicies = ForcedWinPlanner.PAY_POLICIES;
            lim.horizon = 1;
            res = planCapped(pos, lim);
            if (res.lines.isEmpty() && cfg.horizon >= 2) {
                final int first = res.nodes;
                lim.horizon = cfg.horizon;
                res = planCapped(pos, lim);
                res.nodes += first;
            }
            res.nodes += before;
            if (!res.lines.isEmpty()) {
                paySearchFound++;
                payOnly = true;
            }
        }
        plannerNanos += forge.game.WorkClock.nanoTime() - t0;
        lastPlannerNodes = res.nodes;
        if (res.exhausted) {
            plannerExhausted++; // kill-line-1106 (report only): the planner's node or time limit stopped this search
        }
        if (cfg.followPay) {
            for (Line l : res.lines) {
                for (Step st : l.steps) {
                    st.forceLife = st.phyrexianLife; // kill-line-1106
                }
            }
        }
        engineUnverified = false;
        if (res.lines.isEmpty()) {
            lastOutcome = "gate";
            final Line engine = engineFallback(live, pos, index, t0);
            if (engine != null) {
                if (!"likely-take".equals(lastOutcome)) {
                    lastOutcome = "engine-take";
                }
                return engine;
            }
            gated++;
            if (cachesGated(cfg, engineUnverified)) {
                noLineKey = key;
            }
            receipt("FORCED_WIN gate" + tag() + " turn=" + pos.turn + " none " + res.summary() + " probes=" + reader.probeStats.summary());
            return null;
        }
        evaluated++;
        avoidPos = pos; // next-turn-1107 (evalAvoidLost): a re-plan without the lost cards
        avoidLim = lim;
        receipt("FORCED_WIN candidates" + tag() + " turn=" + pos.turn + " " + res.summary() + " probes=" + reader.probeStats.summary()
                + " best=" + res.lines.get(0));
        if (ForcedWinEngine.TRACE) {
            receipt("FW_POSITION " + pos.describe());
        }
        final long t1 = forge.game.WorkClock.nanoTime();
        final Eval best = evaluateTop(live, res.lines, index, cfg.paySearch || hardDeadline != 0 ? t0 : forge.game.WorkClock.nanoTime());
        engineNanos += forge.game.WorkClock.nanoTime() - t1;
        if (cfg.engineAfterPlanFail && evalSink != null && (best == null || best.pUnopposed < 1.0 || best.pBelief < 1.0
                || best.worldsRun < cfg.worlds)) {
            // R2-4 (b), a label's proposals: no planner line verified as forced (every world): the engine searches the
            // position too, and its verified line joins the proposals (the label judges them all)
            final Line engine = engineFallback(live, pos, index, t0, true);
            receipt("FORCED_WIN engine-after-plan-fail" + tag() + " turn=" + pos.turn + " found=" + (engine != null));
        }
        if (best == null || best.pBelief < cfg.threshold || best.pUnopposed < 1.0
                || cfg.honestProbes && best.worldsRun < cfg.worlds) {
            lastOutcome = "declined";
            if (cfg.engineAfterPlanFail && evalSink == null) {
                // R2-4 (b): every planner line failed verification: the engine searches the position itself (a
                // label's proposals ran it above already; review #152-2)
                final Line engine = engineFallback(live, pos, index, t0, true);
                if (engine != null) {
                    planFailEngineTakes++;
                    if (!"likely-take".equals(lastOutcome)) {
                        lastOutcome = "engine-take";
                    }
                    receipt("FORCED_WIN engine-after-plan-fail" + tag() + " turn=" + pos.turn + " " + engine);
                    return engine;
                }
                lastOutcome = "declined";
            }
            if (cachesDeclined(cfg, best)) {
                noLineKey = key;
            }
            if (best == null) {
                // diagnostics (solver-gaps-1015): a width-model mismatch, with the position the width search saw
                receipt("FORCED_WIN mismatch" + tag() + " turn=" + pos.turn + " phase=" + live.getPhaseHandler().getPhase()
                        + " pos=" + pos.describe());
            }
            receipt("FORCED_WIN declined" + tag() + " turn=" + pos.turn + " best=" + (best == null ? "none" : best)
                    + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000);
            return null;
        }
        receipt("FORCED_WIN take" + tag() + " turn=" + pos.turn + " " + best + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000
                + " copies=" + copies + (payOnly ? " paySearch" : ""));
        lastOutcome = "take";
        return best.line;
    }

    // ------------------------------------------------------------------ search-bones-1015: lines as proposals

    /** search-bones-1015: every line the current offense evaluated (null = not collecting). */
    private List<Eval> evalSink;

    /** A line the solver proposes to a search, with its own engine verification. */
    public static final class Proposal {
        /** The line (opaque outside this package): {@link #adopt} it to follow it. */
        public final Object line;
        public final double pUnopposed, pBelief;
        public final int worldsRun, steps, winTurn;
        public final boolean engine;
        public final String text;
        /** Our library's known top the line relies on (its play-outs keep that many cards in place). */
        public final int knownTop;
        /**
         * Lane bvw-plr-1023 (read-only, nothing reads it to decide): how the line's unopposed play-out ended, the losing
         * opponent's {@code GameLossReason} name (null when it did not win), and the copy's turn when it ended (-1).
         */
        public final String endReason;
        public final int endTurn;
        /** Lane bvw-plr-1023: the line's kind (the width search's win: storm, drain, enters, ...; "engine"). */
        public final String how;
        /**
         * e1-q8-1101 (libWatch only; false otherwise): the line's verification read a hidden own-library card (or could
         * not tell), and the most such cards one play-out read. {@link #proven} is unchanged (N10: play is unchanged).
         */
        public final boolean libDependent;
        public final int hiddenTakes;

        /**
         * solver-distill-cc-1109 (SETUP; false for every offense line): the planner's play for this turn towards a kill
         * that is {@link #winTurn} turns away, unverified ({@link #pUnopposed} = 0: never proven, never taken at once);
         * {@link #gf} is its goldfish value.
         */
        public final boolean setup;
        public final double gf;

        Proposal(Line prefix, Goldfish g, Line whole) {
            this.line = prefix;
            this.pUnopposed = 0.0;
            this.pBelief = 0.0;
            this.worldsRun = 0;
            this.steps = prefix.steps.size();
            this.winTurn = whole.winTurn;
            this.engine = false;
            this.text = "setup gf=" + g.g + " " + whole;
            this.knownTop = prefix.knownTop;
            this.endReason = null;
            this.endTurn = -1;
            this.how = "setup:" + whole.how;
            this.libDependent = false;
            this.hiddenTakes = 0;
            this.setup = true;
            this.gf = g.g;
        }

        Proposal(Eval e) {
            this.setup = false;
            this.gf = Double.NaN;
            this.line = e.line;
            this.pUnopposed = e.pUnopposed;
            this.pBelief = e.pBelief;
            this.worldsRun = e.worldsRun;
            this.steps = e.line.steps.size();
            this.winTurn = e.line.winTurn;
            this.engine = "engine".equals(e.line.how);
            this.text = String.valueOf(e);
            this.knownTop = e.line.knownTop;
            this.endReason = e.endReason;
            this.endTurn = e.endTurn;
            this.how = e.line.how;
            this.libDependent = e.libDependent;
            this.hiddenTakes = e.hiddenTakes;
        }

        /** Verified forced: won unopposed and in every belief world it ran (at least {@code minWorlds}), adversary on. */
        public boolean proven(int minWorlds) {
            return pUnopposed >= 1.0 && pBelief >= 1.0 && worldsRun >= minWorlds;
        }
    }

    /**
     * search-bones-1015: the offense's lines at this position, as proposals (each with the solver's own verification:
     * P(unopposed) and P(belief) over its belief worlds with the adversary), whether or not one would be taken. Only
     * lines that won unopposed are returned. Same gates and caches as the offense (our main phase or their end step,
     * an empty stack, the position's no-line cache, the engine search once per turn in main phase 1). Never throws.
     */
    public List<Proposal> propose(Game live) {
        final List<Proposal> out = new ArrayList<>();
        lastCap = null; // rollout-budget-1101
        final PhaseHandler ph = live.getPhaseHandler();
        final boolean ourMain = ph.isPlayerTurn(me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
        final boolean theirEnd = !ph.isPlayerTurn(me) && ph.is(PhaseType.END_OF_TURN);
        final boolean response = cfg.engineStack && !live.getStack().isEmpty()
                && live.getStack().peek().getActivatingPlayer() != me && GameCopier.stackUnsupported(live) == null; // R3-5
        final boolean every = cfg.engineEveryWindow && cfg.engineAnyWindow; // endstep-rows-1103: any empty-stack window
        if (!response && (!live.getStack().isEmpty() || !ourMain && !theirEnd && !every && !cfg.anyWindow) || lineActive()) {
            return out;
        }
        evalSink = new ArrayList<>();
        receipt("INFO_STATE" + tag() + " turn=" + ph.getTurn() + " attached=" + (InfoStates.find(live) != null) + " solverInfoState="
                + cfg.infoState); // unified-search-1106 (report only)
        try {
            offense(live);
            final Set<String> seen = new HashSet<>();
            for (Eval e : evalSink) {
                if (e.pUnopposed >= 1.0 && seen.add(String.valueOf(e.line))) {
                    out.add(new Proposal(e));
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            receipt("FORCED_WIN propose error " + e);
        } finally {
            evalSink = null;
        }
        return out;
    }

    /**
     * SETUP (report only): calls, those with a goldfish line, those whose line wins this turn (left to the offense, which
     * proposed nothing here), and those that proposed steps for this turn.
     */
    public int setupCalls, setupLines, setupWinNow, setupProposals;

    /**
     * solver-distill-cc-1109 (SETUP): the planner's own play for this turn when its kill is more than a turn away: the
     * steps of the goldfish's best line before its first pass (a land drop, a tutor with its pick, a pile with its
     * cards), as a line the follower plays and answers. The look-ahead plays a tutor or a pile with the policy's picks, so
     * to it casting one is worth what the policy would fetch; this is the same cast with the planner's picks. Not a
     * proof and not verified: the search's play-outs weigh it against the default. null = no line, a line that wins this
     * turn (the offense's own), nothing to play this turn, or not our main phase with an empty stack. Never throws.
     */
    public Proposal setup(final Game live, final GoldfishOpts o) {
        return setup(live, knownTop(live), o);
    }

    /** As above, on the given known top (tests). */
    public Proposal setup(final Game live, final List<Integer> knownTop, final GoldfishOpts o) {
        try {
            final PhaseHandler ph = live.getPhaseHandler();
            if (lineActive() || !live.getStack().isEmpty()
                    || !(ph.isPlayerTurn(me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2)))) {
                return null;
            }
            setupCalls++;
            final Goldfish gf = goldfish(live, knownTop, o);
            if (gf.failed || gf.bestLine == null || !(gf.g > 0)) {
                return null;
            }
            setupLines++;
            final Line whole = gf.bestLine;
            if (whole.winTurn < 1) {
                setupWinNow++;
                receipt("FORCED_WIN setup" + tag() + " turn=" + ph.getTurn() + " winNow (no offense line) " + whole);
                return null; // wins this turn: the offense proposes and verifies that
            }
            final List<Step> pre = new ArrayList<>();
            for (Step st : whole.steps) {
                if (st.pass) {
                    break;
                }
                if (st.hostId >= GRANT_ID) {
                    if (st.kind == AbilKind.LAND) {
                        continue; // the relaxation's land drop: not ours to play
                    }
                    break; // a granted land's mana: what it pays for is not playable now
                }
                pre.add(st);
            }
            while (!pre.isEmpty() && pre.get(pre.size() - 1).kind == AbilKind.FLOAT) {
                pre.remove(pre.size() - 1); // mana floated for a step that was cut
            }
            boolean acts = false;
            for (Step st : pre) {
                acts |= st.kind != AbilKind.MAIN && st.kind != AbilKind.FLOAT;
            }
            if (!acts) {
                return null;
            }
            final Line prefix = new Line(pre, whole.pileNames, 0, whole.lifeLeft, whole.manaLeft, 0, List.of(), "setup");
            prefix.knownTop = whole.knownTop;
            setupProposals++;
            receipt("FORCED_WIN setup" + tag() + " turn=" + ph.getTurn() + " gf=" + gf.g + " winTurn=" + whole.winTurn + " prefix="
                    + pre + " of " + whole);
            return new Proposal(prefix, gf, whole);
        } catch (RuntimeException | StackOverflowError e) {
            receipt("FORCED_WIN setup error " + e);
            return null;
        }
    }

    /** search-bones-1015: follow {@code line} (a {@link Proposal#line}) from the next priority on. */
    public void adopt(Object line) {
        active = follower((Line) line);
        taken++;
    }

    /** search-bones-1015: the active line has been played to its end or broke (or none was adopted). */
    public boolean lineDone() {
        return active == null || active.done || active.broken;
    }

    /** search-bones-1015: the active line broke (a step could not be played). */
    public boolean lineBroken() {
        return active != null && active.broken;
    }

    /** search-bones-1015: our library's known top (pile memory) in the live game, as a card count. */
    public int knownTopCount(Game live) {
        final List<Integer> k = knownTop(live);
        return k == null ? 0 : k.size();
    }

    /** search-bones-1015: a line's text for receipts. */
    public static String describeLine(Object line) {
        return String.valueOf(line);
    }

    // ------------------------------------------------------------------ defense: the same search from their seat

    /** Reads the opponent's cards (probed from their seat), for threat checks. */
    final ForcedWinModel.Reader oppReader;
    /**
     * strong-opp-1020: one opponent-card affordance reader for every defender in this JVM (affordances depend only on the
     * card name; the probe cache then survives from game to game). Its card lookup is synchronized.
     */
    /** strong-opp-1020 (review S3-5): a lazy holder, loaded only by a defender with sharedReader on; never throws. */
    private static final class SharedOppReader {
        static final ForcedWinModel.Reader R = load();
    }

    static ForcedWinModel.Reader sharedOppReader() {
        return SharedOppReader.R;
    }

    /** strong-opp-1020: the jar's precomputed affordances of the mush cube's cards (absent = learn on first use). */
    public static final String AFFORDANCE_CACHE = "/forge/ai/simulation/defend-affordances-mush.json";

    private static ForcedWinModel.Reader load() {
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        try (java.io.InputStream in = ForcedWinSolver.class.getResourceAsStream(AFFORDANCE_CACHE)) {
            if (in != null) {
                final int n = r.preload(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                receipt("FORCED_WIN affordance cache preloaded " + n);
            }
        } catch (Throwable e) {
            receipt("FORCED_WIN affordance cache not loaded: " + e);
        }
        return r;
    }
    private int defendedStackId = -1;
    public int threatChecks, threats, counters, holds, discardChoices;

    /** A threat verdict: the share of belief worlds in which the opponent has a forced win, and one such line. */
    static final class Threat {
        double p;
        int worlds;
        Line line;

        @Override
        public String toString() {
            return String.format("P=%.2f/%d", p, worlds) + (line == null ? "" : " e.g. " + line);
        }
    }

    /**
     * Can the opponent force a win by their next turn if {@code pending} (their spell on the stack; null = none)
     * resolves? The width search from their seat, over belief worlds: the hand cards we can see (all of them when
     * {@code revealed}), the rest sampled from their unseen cards (their registered deck remainder, the belief sampler's
     * convention), their library holding the rest. {@code without}: cards taken out (a discard candidate).
     */
    Threat threat(Game live, Player opp, Card pending, List<Card> without, boolean revealed, int worlds) {
        if (redraw != null) {
            return threatHonest(live, opp, pending, without, revealed, worlds);
        }
        final long t0 = forge.game.WorkClock.nanoTime();
        threatChecks++;
        final Threat t = new Threat();
        final Position base = oppReader.read(live, opp, null, true, revealed ? opp : me, pending);
        if (base == null) {
            return t;
        }
        if (cfg.sourceColorsLive) {
            ForcedWinModel.Reader.readSourceColors(base, opp); // ledger-sweep2-1107: their battlefield is public
        }
        if (without != null) {
            for (Card w : without) {
                base.hand.removeIf(a -> a.id == w.getId());
            }
        }
        final PhaseHandler ph = live.getPhaseHandler();
        base.sorcerySpeed = ph.isPlayerTurn(opp) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
        // the unseen cards: their library plus the hand cards we cannot see
        final List<ACard> unseen = new ArrayList<>(base.library);
        int hidden = 0;
        for (Card c : opp.getCardsIn(ZoneType.Hand)) {
            if (!revealed && !c.getView().canBeShownTo(me.getView())) {
                unseen.add(oppReader.card(live, opp, c));
                hidden++;
            }
        }
        final int n = hidden == 0 ? 1 : Math.max(1, worlds);
        int wins = 0;
        for (int w = 0; w < n; w++) {
            final Position pos = copyPosition(base);
            if (hidden > 0) {
                final List<ACard> pool = new ArrayList<>(unseen);
                Collections.shuffle(pool, new Random(mix(cfg.seed, 31L * w + ph.getTurn())));
                pos.hand.addAll(pool.subList(0, Math.min(hidden, pool.size())));
                pos.library.clear();
                pos.library.addAll(pool.subList(Math.min(hidden, pool.size()), pool.size()));
                pos.library.sort((x, y) -> Integer.compare(x.id, y.id));
            }
            final ForcedWinPlanner.Limits lim = limits();
            lim.nodes = cfg.nodes / 2;
            // POST-FREEZE 3: when they cannot act at sorcery speed now (our turn, or their non-main step), their
            // threat window is their next two turns (a setup spell next turn, the win the turn after: a
            // pass-the-turn Doomsday pile); in their own main phase it is this turn and their next
            lim.horizon = (base.sorcerySpeed ? 2 : 3) + cfg.threatExtraTurns;
            final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, lim);
            if (!res.lines.isEmpty()) {
                wins++;
                if (t.line == null) {
                    t.line = res.lines.get(0);
                }
            }
        }
        t.worlds = n;
        t.p = (double) wins / n;
        plannerNanos += forge.game.WorkClock.nanoTime() - t0;
        return t;
    }

    /**
     * strong-opp-1020: {@link #threat} on the seat's belief instead of the opponent's registered deck. Each world is a
     * copy of the game whose opponent hidden hand and library the search's belief re-draw ({@link #redraw}: the cube
     * pool minus what we know) replaced; their position is read from that copy (all its hand cards: the cards we see
     * plus the world's sample), so no world holds their real hidden cards. {@code revealed}: their whole hand is known
     * (a discard effect looking at it), so only the library is drawn.
     */
    /** strong-opp-1020: the current defense decision's deadline (System.nanoTime), or 0 = none. */
    private long defendDeadline = 0;
    /** strong-opp-1020: set when a threat check stopped at the deadline (its verdict is incomplete). */
    private boolean defendCut = false;
    public int defendCuts;

    private boolean pastDeadline() {
        if (defendDeadline != 0 && forge.game.WorkClock.nanoTime() > defendDeadline) {
            defendCut = true;
            return true;
        }
        return false;
    }

    private Threat threatHonest(Game live, Player opp, Card pending, List<Card> without, boolean revealed, int worlds) {
        final long t0 = forge.game.WorkClock.nanoTime();
        threatChecks++;
        final Threat t = new Threat();
        final PhaseHandler ph = live.getPhaseHandler();
        final int n = Math.max(1, worlds);
        int wins = 0, ran = 0;
        for (int w = 0; w < n; w++) {
            if (pastDeadline()) {
                break;
            }
            final Position pos;
            final Object prevIds = forge.util.IdScope.capture();
            forge.util.IdScope.open();
            try {
                final Game g;
                final Player co;
                synchronized (live) {
                    final GameCopier copier = new GameCopier(live, true);
                    copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING); // gamecopier-fidelity-1030
                copier.setPendingStrict(true); // review #156 S2: a copy with a gap fails this node / world
                    copier.setTurnLimits(cfg.turnLimits); // engine-reach-1107
                    copier.setOffBattlefieldLimits(cfg.offBattlefieldLimits); // house p12
                    copier.setHistoryLki(cfg.historyLki); // house p13
                    copier.setCopyBackFace(cfg.copyBackFace); // house p15
                    copier.setStackAbilityCopies(cfg.stackAbilityCopies); // house p16
                    copier.setStackHistory(cfg.stackHistory); // house p17
                    g = copier.makeCopy();
                    g.staleStatics = cfg.staleStatics;
                    g.stalePlayerStatics = cfg.stalePlayerStatics;
                    final Player cm = (Player) copier.find(me);
                    co = (Player) copier.find(opp);
                    copies++;
                    redraw.apply(live, me, g, cm, new Random(mix(cfg.seed, 7919L * w + 31L * ph.getTurn() + 5)));
                }
                pos = oppReader.read(g, co, null, true, co, pending);
                if (pos != null && cfg.sourceColorsLive) {
                    ForcedWinModel.Reader.readSourceColors(pos, co); // ledger-sweep2-1107
                }
                if (pos != null && revealed) {
                    // their hand is known to us (a discard effect revealed it): the real hand, only the library is drawn
                    final Position real = oppReader.read(live, opp, null, false, opp, pending);
                    if (real != null) {
                        pos.hand.clear();
                        pos.hand.addAll(real.hand);
                    }
                }
            } catch (RuntimeException e) {
                receipt("FORCED_WIN threatHonest copy error " + e);
                continue;
            } finally {
                forge.util.IdScope.install(prevIds);
            }
            if (pos == null) {
                continue;
            }
            if (without != null) {
                for (Card c : without) {
                    pos.hand.removeIf(a -> a.id == c.getId());
                }
            }
            pos.sorcerySpeed = ph.isPlayerTurn(opp) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
            final ForcedWinPlanner.Limits lim = limits();
            lim.nodes = cfg.nodes / 2;
            lim.horizon = (pos.sorcerySpeed ? 2 : 3) + cfg.threatExtraTurns;
            lim.ms = cfg.planMs;
            if (defendDeadline != 0) {
                // review S3-1: a world's width search never runs past the defense's wall
                lim.ms = Math.max(1, Math.min(lim.ms, (defendDeadline - forge.game.WorkClock.nanoTime()) / 1_000_000L));
            }
            final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, lim);
            ran++;
            if (!res.lines.isEmpty()) {
                wins++;
                if (t.line == null) {
                    t.line = res.lines.get(0);
                }
            }
        }
        t.worlds = ran;
        t.p = ran == 0 ? 0 : (double) wins / ran;
        plannerNanos += forge.game.WorkClock.nanoTime() - t0;
        return t;
    }

    static Position copyPosition(Position b) {
        final Position p = new Position();
        p.life = b.life;
        p.oppLife = b.oppLife;
        p.turn = b.turn;
        p.oppLibrary = b.oppLibrary;
        p.landDrop = b.landDrop;
        p.sorcerySpeed = b.sorcerySpeed;
        p.storm = b.storm;
        System.arraycopy(b.pool, 0, p.pool, 0, 6);
        p.hand.addAll(b.hand);
        p.battlefield.addAll(b.battlefield);
        p.tapped.addAll(b.tapped);
        p.used.addAll(b.used);
        p.block.addAll(b.block);
        p.oppPowers.addAll(b.oppPowers);
        p.devotionBlue = b.devotionBlue;
        p.emptyDrawWinLive = b.emptyDrawWinLive;
        p.library.addAll(b.library);
        p.graveyard.addAll(b.graveyard);
        p.knownOrder = b.knownOrder;
        p.knownTop.addAll(b.knownTop);
        p.librarySize = b.librarySize;
        p.canDraw = b.canDraw;
        p.pendingId = b.pendingId;
        return p;
    }

    /** Our counterspell for the opponent's spell on top when resolving it gives them a forced win; else no opinion. */
    private Decision defendCounter(Game live) {
        return defendCounter(live, false);
    }

    /**
     * {@code live}: strong-opp-1020's defense ({@link #defend}): the counter is chosen by {@link #counterForLive} (hard
     * before soft, a soft one only when they cannot pay, no bounce counters, mana cost before an alternative cost); both
     * threat checks must have run a world; a "hold" verdict only declines unless {@link Config#hold}. The ForcedWin
     * profiles' own defense (live false) is unchanged.
     */
    private Decision defendCounter(Game live, boolean strongOpp) {
        if (!cfg.defense || !cfg.forced) {
            return Decision.NONE;
        }
        final SpellAbilityStackInstance si = live.getStack().peek();
        final SpellAbility top = si == null ? null : si.getSpellAbility();
        if (top == null || top.getActivatingPlayer() == me || !top.isSpell() || si.getId() == defendedStackId) {
            return Decision.NONE;
        }
        final SpellAbility counter = strongOpp ? counterForLive(live, top) : counterFor(live, top);
        if (counter == null) {
            return Decision.NONE;
        }
        defendedStackId = si.getId();
        final long t0 = forge.game.WorkClock.nanoTime();
        final Player opp = top.getActivatingPlayer();
        // their forced-win chance if it resolves, and if it is countered (the spell gone)
        final Threat resolves = threat(live, opp, top.getHostCard(), null, false, cfg.defenseWorlds);
        final Threat countered = threat(live, opp, null, List.of(top.getHostCard()), false, cfg.defenseWorlds);
        if (strongOpp && (resolves.worlds == 0 || countered.worlds == 0)) {
            // review S3-4: a check that ran no world says nothing (a failed "countered" check must not read as 0)
            receipt("FORCED_WIN defense" + tag() + " spell=" + top.getHostCard().getName() + " no worlds ran: no opinion");
            return Decision.NONE;
        }
        final double delta = resolves.p - countered.p;
        final String verdict = delta >= cfg.counterDelta ? "counter" : countered.p >= cfg.counterDelta ? "hold" : "native";
        receipt("FORCED_WIN defense" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " spell=" + top.getHostCard().getName()
                + " resolves=" + resolves + " countered=" + countered + " -> " + verdict + " with=" + counter.getHostCard().getName()
                + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000);
        if ("counter".equals(verdict)) {
            threats++;
            counters++;
            return new Decision(true, counter);
        }
        if ("hold".equals(verdict)) {
            // a bigger threat is likely still to come: keep the counter for it
            holds++;
            if (strongOpp && !cfg.hold) {
                return Decision.NONE; // review S2-4: decline the counter only; the seat's other responses stay its own
            }
            return Decision.PASS;
        }
        return Decision.NONE;
    }

    /**
     * strong-opp-1020: the defense alone, whatever {@link Config#act} says: with the opponent's spell on top, counter it
     * when resolving it gives them a forced win ({@link #defendCounter}), pass when a bigger threat is likely still to
     * come, else no opinion. Never throws.
     */
    public Decision defend(Game live) {
        try {
            if (live.getStack().isEmpty() || !cfg.defense) {
                return Decision.NONE;
            }
            startDeadline();
            final Decision d = defendCounter(live, true);
            if (defendCut) {
                defendCuts++;
                receipt("FORCED_WIN defend cut at the deadline: no opinion");
                return Decision.NONE;
            }
            return d;
        } catch (Throwable e) {
            // review S2-2: nothing the defense does may reach the game thread; the seat decides as before
            receipt("FORCED_WIN defend error " + e);
            return Decision.NONE;
        } finally {
            defendDeadline = 0;
        }
    }

    /**
     * strong-opp-1020: our discard choice from the opponent's revealed hand by threat probability: their forced-win
     * chance by their next turns now, and with each candidate removed ({@link Config#defenseWorlds} worlds each); take
     * the card that lowers it most. Null (no opinion) when they have no line now or no card lowers it.
     */
    public CardCollection answerDiscardP(Player p, SpellAbility sa, CardCollection valid, int min, int max) {
        return answerDiscardP(p, sa, valid, min, max, null);
    }

    /**
     * As above; {@code visibleToChooser}: the cards the effect showed us (null = none said). Review S2-1: the threat
     * check reads their real hand only when the effect revealed ALL of it; otherwise no opinion (the policy chooses),
     * so no unrevealed hand card ever reaches the defense.
     */
    public CardCollection answerDiscardP(Player p, SpellAbility sa, CardCollection valid, int min, int max,
            CardCollectionView visibleToChooser) {
        if (!cfg.defense || p == me || sa == null || sa.getActivatingPlayer() != me || valid == null || valid.isEmpty()
                || max != 1) {
            return null;
        }
        try {
            if (visibleToChooser == null || !revealsWholeHand(p, visibleToChooser)) {
                receipt("FORCED_WIN discardP: the effect did not reveal the whole hand: no opinion");
                return null;
            }
        } catch (Throwable e) {
            return null;
        }
        final long t0 = forge.game.WorkClock.nanoTime();
        startDeadline();
        try {
            final Game live = me.getGame();
            final int wn = Math.max(1, cfg.defenseWorlds);
            final Threat nowT = threat(live, p, null, null, true, wn);
            if (nowT.p <= 0) {
                receipt("FORCED_WIN discardP turn=" + live.getPhaseHandler().getTurn() + " threat=none");
                return null;
            }
            discardChoices++;
            Card best = null;
            double bestP = nowT.p;
            final List<String> tried = new ArrayList<>();
            final List<Card> tied = new ArrayList<>();
            for (Card c : valid) {
                final double pa = threat(live, p, null, List.of(c), true, wn).p;
                tried.add(c.getName() + ":" + String.format("%.2f", pa));
                if (pa < bestP - 1e-9) {
                    bestP = pa;
                    best = c;
                    tied.clear();
                    tied.add(c);
                } else if (best != null && Math.abs(pa - bestP) <= 1e-9) {
                    tied.add(c);
                }
            }
            if (tied.size() > 1 && cfg.tieBreakTurns > 0) {
                // strong-opp-1020: several cards stop their line equally; look one window further (a card they can
                // replace from their library stops it only for now)
                final int keepExtra = cfg.threatExtraTurns;
                cfg.threatExtraTurns = keepExtra + cfg.tieBreakTurns;
                try {
                    double bp = Double.MAX_VALUE;
                    for (Card c : tied) {
                        final double pa = threat(live, p, null, List.of(c), true, wn).p;
                        tried.add("tb:" + c.getName() + ":" + String.format("%.2f", pa));
                        if (pa < bp - 1e-9) {
                            bp = pa;
                            best = c;
                        }
                    }
                } finally {
                    cfg.threatExtraTurns = keepExtra;
                }
            }
            receipt("FORCED_WIN discardP turn=" + live.getPhaseHandler().getTurn() + " now=" + nowT + " tried=" + tried
                    + " take=" + (best == null ? "-" : best.getName()) + (defendCut ? " CUT" : "") + " ms="
                    + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000);
            if (defendCut) {
                defendCuts++;
                return null;
            }
            if (best == null) {
                return null;
            }
            final CardCollection out = new CardCollection();
            out.add(best);
            return out;
        } catch (Throwable e) {
            // review S2-2: nothing reaches the game thread; the policy chooses
            receipt("FORCED_WIN discardP error " + e);
        } finally {
            defendDeadline = 0;
        }
        return null;
    }

    /** Review S2-1: every card in their hand is among the cards the effect showed us. */
    private static boolean revealsWholeHand(Player p, CardCollectionView visible) {
        final Set<Integer> shown = new HashSet<>();
        for (Card c : visible) {
            shown.add(c.getId());
        }
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (!shown.contains(c.getId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * strong-opp-1020 (review S2-3): the counter for {@link #defend}: hard counters (no "unless they pay", the spell to
     * the graveyard or exile) first; a soft one ({@code UnlessCost}) only when they cannot pay it from their available
     * mana (not sure = can pay); bounce counters (the spell back to hand or library: Remand, Memory Lapse) never; among
     * equals, a mana cost before an alternative cost (Force of Will's pitch, Daze's Island).
     */
    private SpellAbility counterForLive(Game g, SpellAbility top) {
        SpellAbility best = null;
        int bestRank = Integer.MAX_VALUE;
        final Player opp = top.getActivatingPlayer();
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (sa.getApi() != ApiType.Counter || !sa.usesTargeting()) {
                    continue;
                }
                final String dest = sa.getParamOrDefault("Destination", "Graveyard");
                if (!"Graveyard".equals(dest) && !"Exile".equals(dest)) {
                    continue; // Remand, Memory Lapse: the spell comes back
                }
                int rank = 0;
                if (sa.hasParam("UnlessCost")) {
                    if (opponentCanPay(opp, sa.getParam("UnlessCost"))) {
                        continue;
                    }
                    rank += 10;
                }
                if (sa.getAlternativeCost() != null) {
                    rank += 1;
                }
                if (rank >= bestRank) {
                    continue;
                }
                final SpellAbility cs = sa.copy(me);
                cs.setActivatingPlayer(me);
                if (!cs.canTargetSpellAbility(top)) {
                    continue;
                }
                cs.resetTargets();
                cs.getTargets().add(top);
                if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, me, false)) {
                    continue;
                }
                best = cs;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Review S2-3: can they pay a counter's "unless" cost from their available mana? Not sure = yes. */
    static boolean opponentCanPay(Player opp, String unlessCost) {
        try {
            final int n = Integer.parseInt(unlessCost.trim());
            return forge.ai.ComputerUtilMana.getAvailableManaEstimate(opp, true) >= n; // untapped, activatable sources
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void startDeadline() {
        defendCut = false;
        defendDeadline = cfg.defendMs > 0 ? forge.game.WorkClock.nanoTime() + forge.ai.WallClock.budgetMs(cfg.defendMs) * 1_000_000L : 0;
    }

    private SpellAbility counterFor(Game g, SpellAbility top) {
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (sa.getApi() != ApiType.Counter || !sa.usesTargeting()) {
                    continue;
                }
                final SpellAbility cs = sa.copy(me);
                cs.setActivatingPlayer(me);
                if (!cs.canTargetSpellAbility(top)) {
                    continue;
                }
                cs.resetTargets();
                cs.getTargets().add(top);
                if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, me, false)) {
                    continue;
                }
                return cs;
            }
        }
        return null;
    }

    /**
     * Our choice of the opponent's cards to discard (Thoughtseize, Duress): the card whose loss removes their forced
     * win by their next turn, searched from their seat with each candidate removed; null = no opinion (no candidate
     * changes the verdict, or they have no forced win anyway).
     */
    public CardCollection answerDiscard(Player p, SpellAbility sa, CardCollection valid, int min, int max) {
        if (!cfg.defense || !cfg.forced || p == me || sa == null || sa.getActivatingPlayer() != me || valid == null
                || valid.isEmpty() || max != 1) {
            return null;
        }
        final long t0 = forge.game.WorkClock.nanoTime();
        try {
            final Game live = me.getGame();
            final Threat nowT = threat(live, p, null, null, true, 1);
            final Line now = nowT.line;
            if (now == null) {
                receipt("FORCED_WIN discard turn=" + live.getPhaseHandler().getTurn() + " threat=none");
                return null;
            }
            discardChoices++;
            final List<String> tried = new ArrayList<>();
            for (Card c : valid) {
                final Line after = threat(live, p, null, List.of(c), true, 1).line;
                tried.add(c.getName() + (after == null ? ":stops" : ":still"));
                if (after == null) {
                    receipt("FORCED_WIN discard turn=" + live.getPhaseHandler().getTurn() + " take=" + c.getName()
                            + " threat=" + now + " tried=" + tried + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000);
                    final CardCollection out = new CardCollection();
                    out.add(c);
                    return out;
                }
            }
            receipt("FORCED_WIN discard turn=" + live.getPhaseHandler().getTurn() + " no single card stops it tried=" + tried);
        } catch (RuntimeException e) {
            receipt("FORCED_WIN discard error " + e);
        }
        return null;
    }

    /** Engine search statistics. */
    final ForcedWinEngine.Stats engineStats = new ForcedWinEngine.Stats();
    private int engineTurn = -1;
    public int engineRuns, engineFound;

    /**
     * The engine search, once per turn in main phase 1, only when something the width model cannot express is on our
     * side (an ability that targets a card or has a cost outside the model) or our board threatens lethal.
     */
    private Line engineFallback(Game live, Position pos, int index, long t0) {
        return engineFallback(live, pos, index, t0, false);
    }

    /**
     * R3-3 (engineOrder=service): the value the engine orders its moves by, the caller's (the search service's leaf
     * through {@link ChooseACard#of}); null = none (engineOrder=service then keeps Forge's order).
     */
    public ChooseACard.Valuer engineValuer;
    /** R3-1: the structure bits of the last ladder escalation (G, M, L, U), for receipts and rows ("" = none). */
    public String lastLadder = "";

    /**
     * R3-1: the position's engine structure, as bits: G a graveyard card of ours with a playable non-mana ability, M an
     * untapped nonland permanent of ours with a mana ability that does not sacrifice it, L the first pass saw a loop.
     * "" = none. No card names. (D10: U, a card the width model or the probe could not model, no longer triggers: it was
     * on 123 of 124 escalations of the ladder-frequency sample and flipped no label row.)
     */
    private String ladderStructure(Game live, Position pos, boolean loopSeen) {
        final StringBuilder sb = new StringBuilder();
        boolean g = false, m = false;
        for (Card c : me.getCardsIn(ZoneType.Graveyard)) {
            try {
                for (SpellAbility sa0 : c.getAllPossibleAbilities(me, true)) {
                    if (sa0.isManaAbility() || sa0.isLandAbility()) {
                        continue;
                    }
                    final SpellAbility sa = sa0.copy(me);
                    sa.setActivatingPlayer(me);
                    if (sa.canPlay()) {
                        g = true;
                        break;
                    }
                }
            } catch (RuntimeException e) {
                // not castable
            }
            if (g) {
                break;
            }
        }
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() || c.isTapped()) {
                continue;
            }
            for (SpellAbility ma : c.getManaAbilities()) {
                if (ma.getPayCosts() != null && ma.getPayCosts().getCostParts().stream().noneMatch(
                        p -> p instanceof forge.game.cost.CostSacrifice)) {
                    m = true;
                    break;
                }
            }
        }
        if (g) {
            sb.append('G');
        }
        if (m) {
            sb.append('M');
        }
        if (loopSeen) {
            sb.append('L');
        }
        return sb.toString();
    }

    /** R2-4: the window key of the last engine run (turn and phase; MAIN1 only = the turn, as before). */
    private String engineWindow = null;

    /**
     * As above. {@code planFailed} (R2-4 b, engineAfterPlanFail): the planner's lines all failed verification, so the
     * width model mismatched the position: the unmodeled/power gate is skipped. R2-4 c (engineAnyWindow): the engine also
     * runs at our main 2 and the opponent's end step (once per window). R2-4 appendix (engineGateWide): the gate counts
     * infect power against the opponent's poison and treats a card with an ability the probe could not play as unmodeled.
     */
    /** choice-search-1105 (engineRerun): our nonland permanents at the turn's last engine run, and the reruns. */
    private java.util.Set<Integer> engineBoard;
    public int engineReruns;
    private PhaseType enginePhase;

    private java.util.Set<Integer> ourNonlandIds() {
        final java.util.Set<Integer> out = new java.util.HashSet<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isLand()) {
                out.add(c.getId());
            }
        }
        return out;
    }

    private Line engineFallback(Game live, Position pos, int index, long t0, boolean planFailed) {
        final PhaseHandler eph = live.getPhaseHandler();
        final boolean main1 = eph.is(PhaseType.MAIN1) && eph.isPlayerTurn(me);
        // R3-5 (engineStack): our priority with the opponent's item on top is a window too (any phase)
        final boolean stackWindow = cfg.engineStack && !live.getStack().isEmpty()
                && live.getStack().peek().getActivatingPlayer() != me && GameCopier.stackUnsupported(live) == null;
        final boolean ourMain1 = eph.isPlayerTurn(me) && eph.is(PhaseType.MAIN1);
        final boolean otherWindow = stackWindow || cfg.engineAnyWindow && (eph.isPlayerTurn(me) ? eph.is(PhaseType.MAIN2)
                : eph.is(PhaseType.END_OF_TURN)) || cfg.engineAnyWindow && cfg.engineEveryWindow && !ourMain1; // endstep-rows-1103
        // unified-search-1106 (engineOpen): our MAIN2 is a window too
        final boolean openMain2 = cfg.engineOpen && eph.is(PhaseType.MAIN2) && eph.isPlayerTurn(me);
        if (!cfg.engine || !main1 && !otherWindow && !eph.is(PhaseType.MAIN1) && !openMain2) {
            return null;
        }
        final String window = pos.turn + (main1 && !stackWindow || !otherWindow ? "" : ":" + eph.getPhase())
                + (stackWindow ? ":stack" + live.getStack().size() : "");
        final boolean windowed = otherWindow || cfg.engineAnyWindow;
        if (windowed && window.equals(engineWindow)) {
            return null;
        }
        final java.util.Set<Integer> board = cfg.engineRerun ? ourNonlandIds() : null;
        final boolean newPhase = cfg.engineOpen && eph.getPhase() != enginePhase; // unified-search-1106
        enginePhase = eph.getPhase();
        if (!windowed && engineTurn == pos.turn && !newPhase) {
            // choice-search-1105 (engineRerun): once per turn, unless a nonland permanent of ours entered since that run
            if (board == null || engineBoard == null || engineBoard.containsAll(board)) {
                return null;
            }
            engineReruns++;
        }
        engineBoard = board;
        boolean unmodeled = false;
        for (ACard a : pos.hand) {
            unmodeled |= a.unmodeled || cfg.engineGateWide && anyUnplayable(a);
        }
        for (ACard a : pos.battlefield) {
            unmodeled |= a.unmodeled || cfg.engineGateWide && anyUnplayable(a);
        }
        int power = 0, infect = 0;
        for (Card c : me.getCreaturesInPlay()) {
            power += Math.max(0, c.getNetCombatDamage());
            if (c.hasKeyword(forge.game.keyword.Keyword.INFECT)) {
                infect += Math.max(0, c.getNetCombatDamage());
            }
        }
        int poisonLeft = Integer.MAX_VALUE;
        for (Player o : me.getOpponents()) {
            poisonLeft = Math.min(poisonLeft, 10 - o.getPoisonCounters());
        }
        final boolean poisonLethal = cfg.engineGateWide && infect > 0 && infect >= poisonLeft;
        // endstep-rows-1103 (engineEndStepOpen, with engineAnyWindow): their end step, empty stack: no board-power gate
        final boolean offMain = !(eph.isPlayerTurn(me) && (eph.is(PhaseType.MAIN1) || eph.is(PhaseType.MAIN2)));
        final boolean endStepOpen = cfg.engineEndStepOpen && cfg.engineAnyWindow && !stackWindow && live.getStack().isEmpty()
                && (!eph.isPlayerTurn(me) && eph.is(PhaseType.END_OF_TURN) || cfg.engineEveryWindow && offMain);
        if (endStepOpen) {
            meter.endStepOpens++;
        }
        // solver-gaps-1104 (enginePassFirst): the turn-crossing search also runs at our main phase without the gate (the
        // gate reads our power now; the turn before the kill, next turn's attackers are still in our hand)
        final boolean passOpen = cfg.enginePassFirst && eph.isPlayerTurn(me) && live.getStack().isEmpty()
                && (eph.is(PhaseType.MAIN1) || eph.is(PhaseType.MAIN2));
        if (!planFailed && !unmodeled && power < pos.oppLife && !poisonLethal && !endStepOpen && !passOpen
                && !cfg.engineOpen) { // unified-search-1106: engineOpen drops the board-power gate
            return null;
        }
        if (!cfg.forced && !cfg.likely) {
            return null;
        }
        engineWindow = window;
        engineTurn = pos.turn;
        engineRuns++;
        lastOutcome = "gate+engine";
        final ForcedWinEngine.Limits el = new ForcedWinEngine.Limits();
        el.nodes = cfg.engineNodes;
        el.perSearchNodes = cfg.engineNodesPerSearch;
        el.staleStatics = cfg.staleStatics; // unified-search-1106
        el.loopMacro = cfg.loopMacro; // R2-3
        el.loopMacro3 = cfg.loopMacro3; // review #154 S2
        el.anyWindow = cfg.engineAnyWindow; // R2-4 (c)
        el.everyWindow = cfg.engineAnyWindow && cfg.engineEveryWindow; // endstep-rows-1103
        el.passBudget = cfg.enginePassBudget; // endstep-rows-1103
        el.passFirst = cfg.enginePassFirst; // solver-gaps-1104
        el.passToEnd = cfg.enginePassToEnd && cfg.engineAnyWindow; // solver-gaps-1104
        el.triggerTargets = cfg.engineTriggerTargets; // solver-gaps-1104
        el.triggerVariants = cfg.triggerVariants;
        el.passScale = cfg.enginePassScale;
        el.altLines = "off".equals(cfg.engineOrder) ? cfg.engineAltLines : 0; // the ordered DFS returns its first line
        el.longLoops = cfg.engineLongLoops; // unified-search-1106
        el.turnLimits = cfg.turnLimits; // engine-reach-1107
        if (cfg.engineMoves) {
            el.oppTargets = true; // engine-reach-1107
            el.floatMana = true;
            el.floatFirst = true;
            el.libraryTop = true;
        }
        if (cfg.engineLoopReach) {
            el.loopOutlets = true; // engine-reach-1107
            el.looseCycles = true;
            el.outletNodes = cfg.engineOutletNodes;
            el.loopHoist = cfg.engineLoopHoist; // loop-exec-1107
            el.loopBisect = cfg.engineLoopBisect; // loop-exec-1107
        }
        el.frontierAfterPct = cfg.engineFrontierPct; // engine-reach-1107
        if (cfg.engineVerify) {
            final int[] tries = {0};
            el.verify = path -> {
                final Line l = new Line(path, List.of(), path.contains(Step.PASS) ? 1 : 0, me.getLife(), 0, 0, List.of(), "engine");
                // evaluate's own unopposed world first: a line that fails it would be dropped after the search had stopped
                return playout(live, l, mix(mix(cfg.seed, index * 1000L + 999), 0), 0, -1, decisionWall).won
                        && playout(live, l, mix(cfg.seed, 7_100_000L + 64L * index + tries[0]++), 0, -1, decisionWall).won;
            };
        }
        el.stalePlayerStatics = cfg.stalePlayerStatics; // house p7
        el.offBattlefieldLimits = cfg.offBattlefieldLimits; // house p12
        el.historyLki = cfg.historyLki; // house p13
        el.copyBackFace = cfg.copyBackFace; // house p15
        el.stackAbilityCopies = cfg.stackAbilityCopies; // house p16
        el.stackHistory = cfg.stackHistory; // house p17
        el.cycles = cfg.cycles;
        if (cfg.hideOwnLibrary) {
            final List<Integer> kt = knownTop(live);
            el.ownKnownTop = kt == null ? 0 : kt.size();
            el.ownPinned = pinnedIn(me); // known-lib-1102 (empty unless noted)
        }
        el.wall = decisionWall;
        // bvw-step1-build-1024: budget=counter: the engine's move steps per search are capped (0 = none), no deadline,
        // and each seat's calls in a move are capped (the wall's stand-in)
        el.totalSteps = cfg.counter ? cfg.engineSteps : 0;
        el.totalCards = cfg.counter ? cfg.engineCards : 0;
        el.pathCards = cfg.counter ? cfg.enginePathCards : 0;
        el.callLimit = cfg.counter ? 2L * el.maxSteps : 0L;
        // labeller-v2 (a), (e): choice and payment variants of the engine's moves (off = v1)
        el.cac = cfg.cacPicks;
        el.cacVariants = cfg.cacVariants;
        el.cacCap = cfg.cacCap;
        el.pay = cfg.payRecord || cfg.enginePay; // ledger-sweep-1107 (row 60): enginePay on wall budgets
        el.payVariants = cfg.payVariants;
        // solver-cap12 round 3 (all off by default)
        el.order = "static".equals(cfg.engineOrder) ? StaticValuer.INSTANCE
                : "service".equals(cfg.engineOrder) ? engineValuer : null;
        if ("service".equals(cfg.engineOrder) && engineValuer == null) {
            receipt("FORCED_WIN engine-order service: no valuer attached, Forge's order"); // review #154-7
        }
        el.orderCap = cfg.orderCap;
        el.xChoice = cfg.engineX;
        el.xVariants = cfg.xVariants;
        el.stack = stackWindow;
        el.costChoice = cfg.engineCostChoice;
        final long t1 = forge.game.WorkClock.nanoTime();
        final long reserve = cfg.likely ? Math.max(cfg.evalReserveMs, cfg.likelyReserveMs) : cfg.evalReserveMs;
        long engineDeadline = deadlineAt(Math.max(t1 + Math.min(forge.ai.WallClock.budgetMs(cfg.engineMs),
                budgetMs() - reserve) * 1_000_000L, t0 + (budgetMs() - reserve) * 1_000_000L));
        if (cfg.capMs > 0) {
            // deck-belief-1105: no proven line yet; the engine search ends at the cap
            engineDeadline = Math.min(engineDeadline, t0 + cfg.capMs * 1_000_000L);
            el.wall = Math.min(el.wall, t0 + (cfg.capMs + 500L) * 1_000_000L);
        }
        if (hardDeadline != 0) {
            engineDeadline = Math.min(engineDeadline, hardDeadline - reserve * 1_000_000L); // unified-search-1106
        }
        final List<Line> likelyCands = cfg.likely ? new ArrayList<>() : null;
        el.redraw = redraw;
        final int en0 = engineStats.nodes, ec0 = engineStats.cut, ed0 = engineStats.deadlineStops, ek0 = engineStats.capStops;
        final long es0 = engineStats.steps, ecd0 = engineStats.cards;
        final int ep0 = engineStats.pathPrunes, el0 = engineStats.lifetimeStops, ca0 = engineStats.cacApplies;
        final int lp0 = engineStats.loops, cs0 = engineStats.capStops, ls0 = engineStats.lifetimeStops;
        final int ce0 = engineStats.capEvents.size(); // coverage-fix-1102 (meter only)
        Line line = ForcedWinEngine.search(live, me, el, engineStats, engineDeadline,
                mix(cfg.seed, 7_000_000L + index), likelyCands, cfg.likelyTop);
        if (line == null && cfg.engineLadder && cfg.counter) {
            // R3-1: the budget ladder: a budget-stopped first pass on a position with engine structure runs once more
            final boolean budgetStop = engineStats.capStops > cs0 || engineStats.lifetimeStops > ls0;
            final String why = budgetStop ? ladderStructure(live, pos, engineStats.loops > lp0) : "";
            if (!why.isEmpty()) {
                meter.ladderRuns++;
                lastLadder = why;
                el.nodes = cfg.ladderNodes;
                el.perSearchNodes = true; // the ladder pass's own nodes, not what this solver spent before
                el.totalSteps = cfg.ladderSteps;
                el.totalCards = cfg.ladderCards;
                el.pathCards = cfg.ladderPathCards;
                final int ln0 = engineStats.nodes;
                final long lst0 = engineStats.steps;
                if (likelyCands != null) {
                    likelyCands.clear();
                }
                line = ForcedWinEngine.search(live, me, el, engineStats, engineDeadline,
                        mix(cfg.seed, 7_000_000L + index), likelyCands, cfg.likelyTop);
                meter.ladderNodes += engineStats.nodes - ln0;
                meter.ladderSteps += engineStats.steps - lst0;
                if (line != null) {
                    meter.ladderFound++;
                }
                receipt("FORCED_WIN engine-ladder" + tag() + " turn=" + pos.turn + " why=" + why + " found="
                        + (line != null) + " nodes=" + (engineStats.nodes - ln0));
            }
        }
        if (otherWindow) {
            // R2-4 (c): the follower acts in the window the lines were found in (the forced line and, review #152-1,
            // every likely candidate the engine collected there)
            if (line != null) {
                line.window = eph.getPhase();
                line.windowTurn = eph.getTurn();
                line.stackStart = stackWindow;
                for (Line alt : line.alternates) {
                    alt.window = eph.getPhase(); // solver-gaps-1104
                    alt.windowTurn = eph.getTurn();
                    alt.stackStart = stackWindow;
                }
            }
            if (likelyCands != null) {
                for (Line c : likelyCands) {
                    c.window = eph.getPhase();
                    c.windowTurn = eph.getTurn();
                }
            }
        }
        engineNanos += forge.game.WorkClock.nanoTime() - t1;
        meter.engineRuns++;
        meter.engineNodes += engineStats.nodes - en0;
        meter.engineSteps += engineStats.steps - es0;
        meter.engineCards += engineStats.cards - ecd0;
        meter.enginePathPrunes += engineStats.pathPrunes - ep0;
        meter.cacApplies += engineStats.cacApplies - ca0;
        meter.engineLifetimeStops += engineStats.lifetimeStops - el0;
        meter.engineNanos += forge.game.WorkClock.nanoTime() - t1;
        meter.engineWallCuts += engineStats.cut - ec0;
        meter.engineDeadlineStops += engineStats.deadlineStops - ed0;
        meter.engineCapStops += engineStats.capStops - ek0;
        for (Object[] ev : engineStats.capEvents.subList(ce0, engineStats.capEvents.size())) {
            meter.noteEngineCap((String) ev[0], (Long) ev[1], (Long) ev[2]);
        }
        receipt("FORCED_WIN engine" + tag() + " turn=" + pos.turn + " found=" + (line != null) + " " + engineStats.summary()
                + (line == null ? "" : " " + line) + (likelyCands == null ? "" : " likelyCands=" + likelyCands.size()));
        if (line == null || !cfg.forced) {
            if (line != null) {
                likelyCands.add(0, line);
            }
            return likely(live, likelyCands, index, t0);
        }
        engineFound++;
        evaluated++;
        long evalDeadline = deadlineAt(Math.max(t0 + budgetMs() * 1_000_000L, forge.game.WorkClock.nanoTime() + cfg.evalReserveMs * 1_000_000L / 2));
        if (hardDeadline != 0 && !cfg.counter) {
            evalDeadline = Math.min(evalDeadline, hardDeadline); // unified-search-1106
        }
        final boolean ownEval = cfg.engineEvalSteps > 0 && evalSink != null;
        if (ownEval) {
            engineEvalBudget = cfg.engineEvalSteps; // R3-2: label mode, the engine line's own eval-step budget
            engineEvalBase = meter.playoutSteps;
        }
        Eval e;
        try {
            e = evaluate(live, line, mix(cfg.seed, index * 1000L + 999), evalDeadline);
        } finally {
            if (ownEval) {
                engineEvalBudget = 0;
            }
        }
        receipt("FORCED_WIN eval engine" + tag() + " turn=" + pos.turn + " " + e);
        if (!line.alternates.isEmpty() && !(e.pUnopposed >= 1.0 && e.pBelief >= 1.0)) {
            // solver-gaps-1104 (engineAltLines): the engine's other lines, verified the same way; the best P is kept
            int k = 0;
            for (Line alt : line.alternates) {
                final Eval ea = evaluate(live, alt, mix(cfg.seed, index * 1000L + 999),
                        deadlineAt(Math.max(t0 + budgetMs() * 1_000_000L, forge.game.WorkClock.nanoTime() + cfg.evalReserveMs * 1_000_000L / 2)));
                meter.altLines++;
                receipt("FORCED_WIN eval engine-alt" + tag() + " " + (++k) + " " + ea);
                if ((ea.pUnopposed >= 1.0 ? ea.pBelief : 0) > (e.pUnopposed >= 1.0 ? e.pBelief : 0)) {
                    e = ea;
                    line = alt;
                    meter.altTaken++;
                }
                if (e.pUnopposed >= 1.0 && e.pBelief >= 1.0) {
                    break;
                }
            }
        }
        // unified-jar-1106: one key, two implementations from the split. A label run (evalSink set) keeps research's
        // (endstep-rows-1103); a live seat keeps its own (choice-search-1105), so a pilot's read is unchanged.
        final Eval em = !cfg.loopMarginal ? e : evalSink != null ? loopMarginal(live, line, e, index, t0)
                : loopMarginal(live, e, index, evalDeadline);
        if (em != e) {
            e = em;
            line = em.line;
        }
        if (e.pUnopposed >= 1.0) {
            respond(live, e); // opp-response-1108 (no-op when off)
        }
        final Line taken = e.line;
        if (evalSink != null) {
            evalSink.add(e);
        }
        engineUnverified = e.unverified();
        if (e.pUnopposed < 1.0 || e.pBelief < cfg.threshold || cfg.honestProbes && e.worldsRun < cfg.worlds) {
            if (likelyCands != null) {
                // not forced: a likely-win candidate like the others (Part B)
                likelyCands.add(0, line);
                return likely(live, likelyCands, index, t0);
            }
            return null;
        }
        receipt("FORCED_WIN take" + tag() + " turn=" + pos.turn + " " + e + " ms=" + (forge.game.WorkClock.nanoTime() - t0) / 1_000_000);
        return taken;
    }

    // ------------------------------------------------------------------ choice-search-1105: N10 loop count

    /** choice-search-1105 (loopMarginal): lines whose loop count was chosen, variants evaluated, unresolved variants. */
    public int loopMarginals, loopVariants, loopVariantCuts;
    /** The last curve, "n:p ..." and the n taken (diagnostics). */
    public String lastLoopCurve;

    private Eval loopMarginal(Game live, Eval found, int index, long deadline) {
        final Line line = found.line;
        int at = -1;
        for (int i = 0; i < line.steps.size(); i++) {
            if (line.steps.get(i).kind == ForcedWinModel.AbilKind.REPEAT && line.steps.get(i).body != null) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return found;
        }
        final ForcedWinPlanner.Step rep = line.steps.get(at);
        final List<Integer> sched = new ArrayList<>();
        for (int n = 1; n <= cfg.loopMarginalCap; n *= 2) {
            sched.add(n);
        }
        if (!sched.contains(rep.times)) {
            sched.add(rep.times);
            java.util.Collections.sort(sched);
        }
        final List<Eval> resolved = new ArrayList<>();
        final StringBuilder curve = new StringBuilder();
        try {
            for (final int n : sched) {
                final Eval ev;
                if (n == rep.times) {
                    ev = found;
                } else {
                    if (forge.game.WorkClock.nanoTime() > deadline) {
                        loopVariantCuts++;
                        curve.append(n).append(":cut ");
                        if (n > rep.times) {
                            break;
                        }
                        continue;
                    }
                    final List<ForcedWinPlanner.Step> steps = new ArrayList<>(line.steps);
                    steps.set(at, ForcedWinPlanner.Step.repeat(rep.body, n));
                    final Line v = new Line(steps, line.pileNames, line.winTurn, line.lifeLeft, line.manaLeft,
                            line.protection, line.protectionNames, line.how);
                    v.knownTop = line.knownTop;
                    loopVariants++;
                    ev = evaluate(live, v, mix(cfg.seed, index * 1000L + 999), deadline); // the found line's worlds
                    if (ev.pUnopposed >= 1.0 && ev.worldsRun < beliefWorlds(live) && forge.game.WorkClock.nanoTime() > deadline) {
                        loopVariantCuts++; // cut by the deadline: unresolved
                        curve.append(n).append(":cut ");
                        if (n > rep.times) {
                            break;
                        }
                        continue;
                    }
                }
                final double p = ev.pUnopposed >= 1.0 ? ev.pBelief : 0.0;
                curve.append(n).append(':').append(String.format(java.util.Locale.ROOT, "%.3f", p)).append(' ');
                resolved.add(ev);
            }
        } catch (RuntimeException ex) {
            receipt("FORCED_WIN loop-marginal error " + ex);
            return found;
        }
        double best = 0;
        for (Eval ev : resolved) {
            best = Math.max(best, ev.pUnopposed >= 1.0 ? ev.pBelief : 0.0);
        }
        Eval pick = found;
        if (best > 0) {
            for (Eval ev : resolved) { // in n order: the smallest n within loopEps of the best
                final double p = ev.pUnopposed >= 1.0 ? ev.pBelief : 0.0;
                if (p >= best - cfg.loopEps) {
                    pick = ev;
                    break;
                }
            }
        }
        loopMarginals++;
        lastLoopCurve = curve.toString().trim() + " -> " + pick.line.steps.get(at).times;
        receipt("FORCED_WIN loop-marginal" + tag() + " " + lastLoopCurve);
        return pick;
    }

    // ------------------------------------------------------------------ endstep-rows-1103: loop count by marginal value

    /** endstep-rows-1103 (loopMarginal; diagnostics): the last curve, "n:p n:p ..." and the n taken. */

    /**
     * Owner ruling 11-03: the first REPEAT step's count is chosen by marginal value. Variants at n = 1, 2, 4, ... (to
     * loopMarginalCap, and the found count) are evaluated as lines; the smallest n within loopEps of the best P is taken
     * (the found line when every P is 0). Only forced-win lines reach here: a loop that does not win now is not valued
     * (that needs a value leaf; RESULT.md). Never throws.
     */
    private Eval loopMarginal(Game live, Line line, Eval found, int index, long t0) {
        int at = -1;
        for (int i = 0; i < line.steps.size(); i++) {
            if (line.steps.get(i).kind == ForcedWinModel.AbilKind.REPEAT && line.steps.get(i).body != null) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return found;
        }
        final ForcedWinPlanner.Step rep = line.steps.get(at);
        final List<Integer> sched = new ArrayList<>();
        for (int n = 1; n <= cfg.loopMarginalCap; n *= 2) {
            sched.add(n);
        }
        if (!sched.contains(rep.times)) {
            sched.add(rep.times);
            java.util.Collections.sort(sched);
        }
        // coordinator 11-03: a capped variant is UNRESOLVED (never 0): excluded from the choice, and the schedule stops at
        // it (larger n cost more); its caps are restored out of the row's meter and counted in loop_variant_caps instead
        // (the found line's own evaluation is never touched). Early stop: two consecutive resolved n within loopEps (P > 0).
        final List<Eval> resolved = new ArrayList<>();
        final StringBuilder curve = new StringBuilder();
        Eval prevOk = null;
        try {
            for (final int n : sched) {
                final Eval ev;
                boolean capped;
                if (n == rep.times) {
                    ev = found;
                    capped = false;
                } else {
                    final long ec = meter.evalCapStops, ed = meter.evalDeadlineStops, sc = meter.playoutStepCaps,
                            cc = meter.playoutCallCaps, wc = meter.playoutWallCuts, aw = meter.playoutAiWorkCuts;
                    final List<ForcedWinPlanner.Step> steps = new ArrayList<>(line.steps);
                    steps.set(at, ForcedWinPlanner.Step.repeat(rep.body, n));
                    final Line v = new Line(steps, line.pileNames, line.winTurn, line.lifeLeft, line.manaLeft,
                            line.protection, line.protectionNames, line.how);
                    v.window = line.window;
                    v.windowTurn = line.windowTurn;
                    v.stackStart = line.stackStart;
                    v.knownTop = line.knownTop;
                    ev = evaluate(live, v, mix(cfg.seed, index * 1000L + 999), // the same worlds as the found line
                            deadlineAt(Math.max(t0 + budgetMs() * 1_000_000L,
                                    forge.game.WorkClock.nanoTime() + cfg.evalReserveMs * 1_000_000L / 2)));
                    capped = meter.evalCapStops != ec || meter.evalDeadlineStops != ed || meter.playoutStepCaps != sc
                            || meter.playoutCallCaps != cc || meter.playoutWallCuts != wc || meter.playoutAiWorkCuts != aw
                            || ev.pUnopposed >= 1.0 && ev.worldsRun < cfg.worlds; // a world-0 loss stops the worlds: resolved
                    if (capped) {
                        meter.loopVariantCaps++;
                        meter.evalCapStops = ec;
                        meter.evalDeadlineStops = ed;
                        meter.playoutStepCaps = sc;
                        meter.playoutCallCaps = cc;
                        meter.playoutWallCuts = wc;
                        meter.playoutAiWorkCuts = aw;
                    }
                }
                if (capped) {
                    curve.append(n).append(":cap ");
                    if (n > rep.times) {
                        break; // every larger n costs more: the schedule stops here
                    }
                    continue;
                }
                final double p = ev.pUnopposed >= 1.0 ? ev.pBelief : 0.0;
                curve.append(n).append(':').append(String.format(java.util.Locale.ROOT, "%.3f", p)).append(' ');
                resolved.add(ev);
                if (prevOk != null && n > rep.times) {
                    final double pp = prevOk.pUnopposed >= 1.0 ? prevOk.pBelief : 0.0;
                    if (pp > 0 && Math.abs(p - pp) < cfg.loopEps) {
                        break; // early stop: the curve is flat past the found count
                    }
                }
                prevOk = ev;
            }
        } catch (RuntimeException ex) {
            receipt("FORCED_WIN loop-marginal error " + ex);
            return found;
        }
        // owner addendum 11-03: the smallest n within loopEps of the best resolved P (a curve that turns down is handled)
        double best = 0;
        for (Eval ev : resolved) {
            best = Math.max(best, ev.pUnopposed >= 1.0 ? ev.pBelief : 0.0);
        }
        Eval pick = found;
        for (Eval ev : resolved) {
            if (best > 0 && (ev.pUnopposed >= 1.0 ? ev.pBelief : 0.0) >= best - cfg.loopEps) {
                pick = ev;
                break;
            }
        }
        lastLoopCurve = curve.toString().trim() + " -> n=" + pick.line.steps.get(at).times + " (found " + rep.times + ")";
        meter.loopMarginals++;
        receipt("FORCED_WIN loop-marginal" + tag() + " " + lastLoopCurve);
        return pick;
    }

    // ------------------------------------------------------------------ Part B: likely-win lines (solver-gaps-1015)

    public int likelyRuns, likelyTaken;
    /** solver-gaps-1015: play-outs and rollouts cut at their wall (or after an attack the rules refused twice). */
    public int wallCuts;
    /** solver-gaps-1015: the current offense decision's hard wall (System.nanoTime()): its budget plus the grace. */
    private long decisionWall;
    /**
     * unified-search-1106 (plans.unified; 0 = off): the decision's one deadline. Nothing in the solver runs past it, a line
     * that has won every world so far keeps verifying up to it, and a world not reached is unknown.
     */
    private long hardDeadline;

    public void setHardDeadline(long nanos) {
        this.hardDeadline = nanos;
    }

    /**
     * wu-reads-1107 (solverCfg windowWorkMs; set by PlanSearch only at a window point with the key on, else false): the
     * planner's searches also stop at the hard deadline (each gets at most what is left of it, none when nothing is).
     */
    private boolean windowCap;

    public void setWindowCap(boolean on) {
        this.windowCap = on;
    }

    /** ledger-sweep2-1107: planner limits with this solver's expansion cap (planExpandCap; off = as {@code new Limits()}). */
    private ForcedWinPlanner.Limits limits() {
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.expandCap = cfg.planExpandCap;
        return lim;
    }

    /** planLoops: the probes of the games in progress, by match (weak), then by seat, card and partner names. */
    private static final java.util.Map<Object, java.util.Map<String, java.util.Set<String>>> LOOP_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** planLoops: the most partners one permanent is probed with, and one sandbox search's nodes and counted time. */
    static final int LOOP_PARTNERS = 12, LOOP_NODES = 300;
    static final long LOOP_NANOS = 3_000_000_000L;

    /**
     * planLoops: the card has an ability of Forge's CopyPermanent effect type, its own or one it grants (an Aura's script
     * variable). The probe is a sandbox search per partner; without this filter every permanent with a tap ability that
     * targets a card (a creature that gives protection, say) would pay for it.
     */
    static boolean makesCopies(final Card c) {
        try {
            for (SpellAbility sa : c.getAllSpellAbilities()) {
                if (sa.getApi() == forge.game.ability.ApiType.CopyPermanent) {
                    return true;
                }
            }
            for (String v : c.getSVars().values()) {
                if (v != null && v.contains("CopyPermanent")) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** planLoops: an engine search's limits for the loop sandbox: this turn only, the solver's loop and move options. */
    static ForcedWinEngine.Limits loopLimits(final Config cfg) {
        final ForcedWinEngine.Limits el = new ForcedWinEngine.Limits();
        el.nodes = LOOP_NODES;
        el.perSearchNodes = true;
        el.passTurn = false;
        el.staleStatics = cfg.staleStatics;
        el.loopMacro = cfg.loopMacro;
        el.loopMacro3 = cfg.loopMacro3;
        el.triggerTargets = cfg.engineTriggerTargets;
        el.triggerVariants = cfg.triggerVariants;
        el.longLoops = cfg.engineLongLoops;
        el.turnLimits = cfg.turnLimits;
        if (cfg.engineMoves) {
            el.oppTargets = true;
            el.floatMana = true;
            el.floatFirst = true;
            el.libraryTop = true;
        }
        if (cfg.engineLoopReach) {
            el.loopOutlets = true;
            el.looseCycles = true;
            el.outletNodes = cfg.engineOutletNodes;
            el.loopHoist = cfg.engineLoopHoist;
            el.loopBisect = cfg.engineLoopBisect;
        }
        el.stalePlayerStatics = cfg.stalePlayerStatics;
        el.offBattlefieldLimits = cfg.offBattlefieldLimits;
        el.historyLki = cfg.historyLki;
        el.copyBackFace = cfg.copyBackFace;
        el.stackAbilityCopies = cfg.stackAbilityCopies;
        el.stackHistory = cfg.stackHistory;
        el.cycles = cfg.cycles;
        return el;
    }

    /**
     * planLoops ({@link ForcedWinModel.Reader.LoopProbe}): for a permanent with a tap ability that targets a card, or an
     * Aura, the creature names of ours (in play, in hand, in the library or the graveyard) with which the engine wins this
     * turn from the bare sandbox ({@link ForcedWinProbe#sandboxWin}: the permanent in play, or the Aura in hand with
     * mana); none when the card wins there alone.
     */
    static void fillLoops(final Config cfg, final Game live, final Player me, final Card c, final ACard t) {
        if (!c.isPermanent() || c.isLand() || !makesCopies(c)) {
            return; // only a card whose own or granted ability makes a copy of a permanent is probed (an effect type, no name)
        }
        boolean taps = false;
        for (ForcedWinModel.Abil ab : t.onBattlefield) {
            taps |= ab.tapSelf && ab.cardTargets;
        }
        // an Aura (it targets what it enchants) is probed cast from hand on the partner: what it grants is the partner's
        final boolean aura = !taps && c.isAura();
        if (!taps && !aura) {
            return;
        }
        t.loopFromHand = aura;
        t.loopHaste = c.hasKeyword(forge.game.keyword.Keyword.HASTE);
        final java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(c.getName());
        final List<Card> partners = new ArrayList<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard}) {
            for (Card p : me.getCardsIn(z)) {
                if (p.isCreature() && seen.add(p.getName()) && partners.size() < LOOP_PARTNERS) {
                    partners.add(p);
                }
            }
        }
        // one probe per game, seat, card and partner list: the seat's other readers (the root solver's, the reach's, the
        // choice scorer's) reuse it. The key holds the partner names in a fixed order, so it does not depend on which
        // copy of the game asked.
        final List<String> names = new ArrayList<>();
        for (Card p : partners) {
            names.add(p.getName());
        }
        java.util.Collections.sort(names);
        final String key = me.getId() + "|" + c.getName() + "|" + aura + "|" + names;
        final Object match = live.getMatch() != null ? live.getMatch() : LOOP_CACHE;
        final java.util.Map<String, java.util.Set<String>> byKey = LOOP_CACHE.computeIfAbsent(match, x -> new java.util.HashMap<>());
        final int probed = partners.size();
        synchronized (byKey) {
            final java.util.Set<String> hit = byKey.get(key);
            if (hit != null) {
                t.loopWith.addAll(hit);
                return;
            }
            final long seed = mix(cfg.seed, 0x4C4F4F50L + c.getName().hashCode());
            if (ForcedWinProbe.sandboxWin(live, me, c.getId(), -1, aura, loopLimits(cfg), seed, LOOP_NANOS)) {
                t.note = (t.note.isEmpty() ? "" : t.note + "; ") + "loop probe: wins alone";
                byKey.put(key, new java.util.TreeSet<>());
                return;
            }
            partners.sort((x, y) -> x.getName().compareTo(y.getName()));
            for (Card p : partners) {
                if (ForcedWinProbe.sandboxWin(live, me, c.getId(), p.getId(), aura, loopLimits(cfg), mix(seed, p.getName().hashCode()), LOOP_NANOS)) {
                    t.loopWith.add(p.getName());
                }
            }
            byKey.put(key, new java.util.TreeSet<>(t.loopWith));
        }
        if (RECEIPTS) {
            receipt("FORCED_WIN loop-probe " + c.getName() + " partners=" + t.loopWith + " probed=" + probed + " haste=" + t.loopHaste);
        }
    }

    /** wu-reads-1107: one planner search ({@link #plan}), within the hard deadline when windowCap is on (unchanged otherwise). */
    ForcedWinPlanner.Result planCapped(Position pos, ForcedWinPlanner.Limits lim) {
        if (!windowCap || hardDeadline == 0) {
            return plan(pos, lim);
        }
        final long left = (hardDeadline - forge.game.WorkClock.nanoTime()) / 1_000_000L;
        if (left <= 0) {
            final ForcedWinPlanner.Result r = new ForcedWinPlanner.Result();
            r.exhausted = true;
            r.stoppedBy = "time";
            return r;
        }
        final long ms0 = lim.ms;
        lim.ms = ms0 < 0 ? left : Math.min(ms0, left);
        try {
            return plan(pos, lim);
        } finally {
            lim.ms = ms0;
        }
    }
    /** deck-belief-1105: the current offense decision's start (for {@link Config#unprovenMs}). */
    private long decisionStart;
    public int unprovenStops;

    /** deck-belief-1105: stop starting work on an unprovable line past {@link Config#unprovenMs} (off: never). */
    private boolean unprovenStop(boolean unproven) {
        final int ms = cfg.capMs > 0 ? (cfg.unprovenMs > 0 ? Math.min(cfg.unprovenMs, cfg.capMs) : cfg.capMs) : cfg.unprovenMs;
        if (ms <= 0 || !unproven || forge.game.WorkClock.nanoTime() <= decisionStart + ms * 1_000_000L) {
            return false;
        }
        unprovenStops++;
        return true;
    }
    /** Payment search: decisions where only the searched payment found width lines. */
    public int paySearchFound;

    /**
     * Part B: score the candidate lines and the seat's own play (Forge AI) by P(win) over the same belief worlds, each
     * a rollout of both seats (Forge AI; ours follows the line first) to the game's end or the turn cap. The best line is
     * returned only when it beats the seat's own play; no probability threshold. Null = no opinion.
     */
    Line likely(Game live, List<Line> cands, int index, long t0) {
        if (cands == null || cands.isEmpty()) {
            return null;
        }
        final long l0 = forge.game.WorkClock.nanoTime();
        // stop the rollouts half a second before the decision's budget: measured at the full budget, Part B decisions
        // ended at 8.0-8.4 s (a rollout in flight, the line's set-up)
        final long deadline = t0 + (budgetMs() - 500) * 1_000_000L;
        if (deadline - l0 < 300_000_000L) {
            // no time left in the decision's budget for even one world
            receipt("FORCED_WIN likely" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " no budget left");
            return null;
        }
        likelyRuns++;
        final int k = Math.min(cands.size(), cfg.likelyTop + 1);
        final double[] sum = new double[k];
        double alt = 0;
        int worlds = 0;
        for (int w = 1; w <= cfg.likelyWorlds; w++) {
            if (forge.game.WorkClock.nanoTime() > deadline) {
                break;
            }
            final long ws = mix(cfg.seed, index * 1000L + 500 + w);
            // a world counts only when every arm finished in it before the budget (paired worlds)
            final double a = rollout(live, null, ws, w, deadline);
            final double[] v = new double[k];
            boolean complete = a >= 0;
            for (int i = 0; i < k && complete; i++) {
                v[i] = rollout(live, cands.get(i), ws, w, deadline);
                complete = v[i] >= 0;
            }
            if (!complete) {
                break;
            }
            alt += a;
            for (int i = 0; i < k; i++) {
                sum[i] += v[i];
            }
            worlds++;
        }
        if (worlds == 0) {
            return null;
        }
        int best = -1;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k; i++) {
            sb.append(String.format(" #%d P=%.2f %s;", i, sum[i] / worlds, cands.get(i).steps));
            if (best < 0 || sum[i] > sum[best]) {
                best = i;
            }
        }
        final double pAlt = alt / worlds, pBest = sum[best] / worlds;
        final boolean take = pBest > pAlt + 1e-9;
        receipt("FORCED_WIN likely" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " worlds=" + worlds
                + String.format(" P(own play)=%.2f P(best)=%.2f", pAlt, pBest) + " -> " + (take ? "take #" + best : "keep own play")
                + " ms=" + (forge.game.WorkClock.nanoTime() - l0) / 1_000_000 + sb);
        if (!take) {
            return null;
        }
        likelyTaken++;
        lastOutcome = "likely-take";
        return cands.get(best);
    }

    /**
     * Part B: one rollout in a belief world (world w samples the opponent's hidden hand): our seat follows {@code line}
     * (null = Forge AI's own play) and then plays on as Forge AI; the opponent is Forge AI. 1 = we win, 0 = we lose, 0.5
     * = still running at the turn cap.
     */
    double rollout(Game live, Line line, long seed, int world, long deadline) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player cm;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING); // gamecopier-fidelity-1030
                copier.setPendingStrict(true); // review #156 S2: a copy with a gap fails this node / world
                copier.setCopyFidelity(1);
                copier.setTurnLimits(cfg.turnLimits); // engine-reach-1107
                copier.setOffBattlefieldLimits(cfg.offBattlefieldLimits); // house p12
                copier.setHistoryLki(cfg.historyLki); // house p13
                copier.setCopyBackFace(cfg.copyBackFace); // house p15
                copier.setStackAbilityCopies(cfg.stackAbilityCopies); // house p16
                copier.setStackHistory(cfg.stackHistory); // house p17
                g = copier.makeCopy();
                g.staleStatics = cfg.staleStatics;
                g.stalePlayerStatics = cfg.stalePlayerStatics;
                cm = (Player) copier.find(me);
                copies++;
                final Random rng = new Random(mix(seed, 2));
                if (redraw != null) {
                    redraw.apply(live, me, g, cm, rng); // honest-belief-1020
                }
                for (Player o : cm.getOpponents()) {
                    if (world != 0 && keepDealtHand()) {
                        continue; // deck-belief-1105: the redraw dealt this world's hand
                    }
                    hideOrSample(live, me, (Player) live.getPlayer(o.getId()), o, world == 0, rng, redraw);
                }
                if (cfg.infoState) {
                    // order-memory-1102: the known cards below the top stay at their index; canonical unknown order
                    shuffleUnknownOwnLibrary(cm, line == null ? placedCount(me) : line.knownTop, pinnedIn(cm), rng, true);
                } else {
                    shuffleUnknownOwnLibrary(cm, line == null ? placedCount(me) : line.knownTop, rng);
                }
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 3)));
            if (line != null) {
                final LineAi ai = new LineAi(g, cm, cm.getController().getLobbyPlayer(), follower(line));
                ai.continueAfter = true;
                ai.bounded(deadline);
                cm.dangerouslySetController(ai);
            } else {
                cm.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, cm, cm.getController().getLobbyPlayer()).bounded(deadline));
            }
            for (Player o : g.getPlayers()) {
                if (o != cm) {
                    o.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, o, o.getController().getLobbyPlayer()).bounded(deadline));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            LookaheadSearch.givePriority(ph, cm);
            final int last = ph.getTurn() + cfg.likelyCapTurns;
            int steps = 0;
            while (!g.isGameOver() && ph.getTurn() <= last && steps < 20 * cfg.maxSteps) {
                ph.mainLoopStep();
                steps++;
                if ((steps & 31) == 0 && forge.game.WorkClock.nanoTime() > deadline) {
                    return -1; // out of budget: the world is dropped
                }
            }
            if (cm.hasWon() || cm.getOpponents().stream().anyMatch(Player::hasLost)) {
                return 1;
            }
            return cm.hasLost() ? 0 : 0.5;
        } catch (ForcedWinEngine.OutOfBudget | forge.ai.AiWork.Exhausted ex) {
            if (forge.ai.AiWork.spent()) {
                return 0; // review #160 S3-8: the Forge AI work cap, as the play-out: not a win, not dropped
            }
            wallCuts++;
            return -1; // cut at the wall: the world is dropped
        } catch (RuntimeException | StackOverflowError ex) {
            if (forge.ai.AiWork.spent()) {
                return 0; // review #160 S3-8
            }
            receipt("FORCED_WIN likely rollout error " + ex);
            return 0.5;
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** Read the position; the library's affordances are learned only when something we hold could use them. */
    /**
     * choice-scorer-1106: the ordered library choices (PILE effects) of {@code hostId} in the planner's lines from
     * {@code g}, top first, shortest win first; the cards are whatever the search drew to win. Never throws.
     */
    public List<List<Integer>> pileChoices(final Game g, final int hostId, final int nodes, final long ms, final int top) {
        final List<List<Integer>> out = new ArrayList<>();
        try {
            final Position pos = readPosition(g, new ArrayList<>());
            if (pos == null) {
                return out;
            }
            final ForcedWinPlanner.Limits lim = limits();
            lim.nodes = nodes;
            lim.ms = ms;
            lim.payPolicies = 1;
            lim.horizon = cfg.horizon;
            final List<Line> lines = new ArrayList<>(ForcedWinPlanner.plan(pos, lim).lines);
            lines.sort((a, b) -> a.winTurn != b.winTurn ? Integer.compare(a.winTurn, b.winTurn)
                    : Integer.compare(a.steps.size(), b.steps.size()));
            final Set<List<Integer>> seen = new HashSet<>();
            for (Line l : lines) {
                for (Step st : l.steps) {
                    if (st.hostId == hostId && !st.pile.isEmpty() && seen.add(new ArrayList<>(st.pile))) {
                        out.add(new ArrayList<>(st.pile));
                    }
                }
                if (out.size() >= top) {
                    break;
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            receipt("pileChoices " + e);
        }
        return out;
    }

    /** choice-scorer-1106: an affordance reader shared by the solvers of one decision's copies (its probes are cached). */
    public static Object newSharedReader() {
        return new ForcedWinModel.Reader();
    }

    /** choice-scorer-1106: a solver over a shared reader ({@link #newSharedReader}). */
    public static ForcedWinSolver withReader(final Player me, final Config cfg, final Object reader) {
        return new ForcedWinSolver(me, cfg, (ForcedWinModel.Reader) reader);
    }

    /** choice-scorer-1106: what the cheap width search finds from a position, unverified. */
    public static final class Screen {
        public int lines, nodes, bestTurn = -1, bestSteps = -1;
        public boolean exhausted, failed;
        public long nanos;
        public String best;
        // integrate-1110: both lanes added fields here; they are independent, so both sets stay (solver-distill-cc's first)
        /** solver-distill-cc-1109: set when the screen is a {@link #reach} (NaN otherwise). */
        public double reach = Double.NaN;
        /** solver-distill-cc-1109: the {@link #goldfish} value and its earliest line's turn, when one was planned. */
        public double gf = Double.NaN;
        public int gfTurn = -1;
        /** GOLDFISH's screen: the best line's value and whether it plays no granted card. */
        public double score = Double.NaN;
        public boolean scoreDirect;
        /** GOLDFISH (report only): planner nodes, screens, and whether the call's node cap cut it. */
        public long gfNodes;
        public int gfScreens;
        public boolean gfCut;
        /** SETUP: the best line itself (opaque outside this package; {@link #screenSeq} only). */
        Line bestLine;
        /**
         * choice-search-1109 (set only when a choice scorer's {@code check} ran on this position; NaN / null otherwise): the
         * honest win check's p_win over its belief worlds, its label, worlds run, gate and time.
         */
        public double pWin = Double.NaN;
        public String check;
        public String checkGate;
        public int checkWorlds = -1;
        public double checkMs = Double.NaN;
        /**
         * choice-search-1109 pass 3 (set only when a choice scorer's relax pass screened this position): the relaxed-planning
         * distance, the fewest generic relaxations (an extra untapped land of the seat's own; a card drawn from its unknown
         * library) with which the planner finds a line (-1 = not run, Integer.MAX_VALUE = none within the bound), the share of
         * the draw samples at that distance with a line, which relaxation, and the screens run.
         */
        public int relaxR = -1;
        public double relaxP = Double.NaN;
        public String relaxHow;
        public int relaxScreens;
    }

    /**
     * choice-scorer-1106: the planner alone (this turn, then by our next turn) from {@code g}, our library's known top as
     * given (top first). No game copies, no opponent: a screen, never a proof. Never throws.
     */
    public Screen screen(final Game g, final List<Integer> knownTop, final int nodes, final long ms) {
        final Screen s = new Screen();
        final long t0 = forge.game.WorkClock.nanoTime();
        try {
            final Position pos = readPosition(g, knownTop);
            if (pos == null) {
                s.failed = true;
                return s;
            }
            final ForcedWinPlanner.Limits lim = limits();
            lim.nodes = nodes;
            lim.ms = ms;
            lim.payPolicies = 1;
            lim.horizon = 1;
            ForcedWinPlanner.Result r = ForcedWinPlanner.plan(pos, lim);
            s.nodes = r.nodes;
            s.exhausted = r.exhausted;
            if (r.lines.isEmpty() && cfg.horizon >= 2) {
                lim.horizon = cfg.horizon;
                r = ForcedWinPlanner.plan(pos, lim);
                s.nodes += r.nodes;
                s.exhausted |= r.exhausted;
            }
            s.lines = r.lines.size();
            for (Line l : r.lines) {
                if (s.bestTurn < 0 || l.winTurn < s.bestTurn || l.winTurn == s.bestTurn && l.steps.size() < s.bestSteps) {
                    s.bestTurn = l.winTurn;
                    s.bestSteps = l.steps.size();
                    s.best = String.valueOf(l);
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            s.failed = true;
            s.best = String.valueOf(e);
        } finally {
            s.nanos = forge.game.WorkClock.nanoTime() - t0;
        }
        return s;
    }

    /** solver-distill-cc-1109: {@link #reach}'s result. */
    public static final class Reach {
        /** P(the planner finds an unopposed line by our next turn), over our next unknown draw; NaN = not computed. */
        public double p = Double.NaN;
        /** {@link #p} with each line counted gamma^(its turn: 0 = this turn, 1 = our next turn); = p when gamma is 1. */
        public double pd = Double.NaN;
        /** A line with no new card (the plain {@link #screen} found one). */
        public boolean direct;
        public boolean failed;
        public int screens, names, unknown;
        public long nanos;
        /**
         * Cost (report only): planner nodes over all screens; the no-new-card screen hit its node cap; per-card screens
         * that hit theirs; the per-card screens were skipped (skipCapped); signature groups screened (sig).
         */
        public long nodes;
        public boolean s0Capped, skipped;
        public int nameCapped, groups;
    }

    /**
     * solver-distill-cc-1109: {@link #reach}'s knobs. Defaults = the first REACH build (every per-card screen at the
     * no-new-card screen's cap, both horizon passes, one screen per name).
     */
    public static final class ReachOpts {
        public int nodes = 2000;
        public long ms = 60;
        public int maxNames = 40;
        public boolean window = true;
        /** Node cap of each per-card screen's pass (< 0 = {@link #nodes}). */
        public int nameNodes = -1;
        /** Per-card screens plan once, at the solver's horizon (its line set includes this turn's lines). */
        public boolean nameOnePass;
        /** The no-new-card screen hit its node cap with no line: reach 0, no per-card screens (they would hit it too). */
        public boolean skipCapped;
        /** A line that wins k turns from now counts gamma^k in {@link Reach#pd} (1 = no discount). */
        public double gamma = 1.0;
        /**
         * Names whose planner model is identical (every observed field but id, name and notes) share one screen, their
         * counts summed. A name is kept apart when a card of ours already in the position has it, or a tutor's observed
         * name filter holds it (the planner's only name-dependent paths).
         */
        public boolean sig;
    }

    /**
     * solver-distill-cc-1109 (REACH): the planner screen, in expectation over the seat's NEXT UNKNOWN DRAW. The planner
     * sees an unknown library card as a blank, so {@link #screen} only finds lines whose pieces are already in hand. Here
     * the first unknown library slot is filled, in turn, with each distinct card name the seat's library holds below its
     * known top, weighted by its count: P = sum_c n_c / N * [a line exists with c as the next card]. Honest: the seat
     * knows its library's multiset (deck minus seen) and its known top, never the order of the rest. No card or combo
     * knowledge; count-aware by construction. With {@code window} the phase is read (an upkeep leaf plans through the
     * draw step). A direct line (no new card needed) is P = 1. Names past {@code maxNames} (most copies first) count 0.
     */
    public Reach reach(final Game g, final List<Integer> knownTop, final int nodes, final long ms, final int maxNames,
            final boolean window) {
        final ReachOpts o = new ReachOpts();
        o.nodes = nodes;
        o.ms = ms;
        o.maxNames = maxNames;
        o.window = window;
        return reach(g, knownTop, o);
    }

    /** {@link #reach} with its knobs ({@link ReachOpts}). */
    public Reach reach(final Game g, final List<Integer> knownTop, final ReachOpts o) {
        final Reach out = new Reach();
        final long t0 = forge.game.WorkClock.nanoTime();
        final int nameNodes = o.nameNodes < 0 ? o.nodes : o.nameNodes;
        try {
            final Screen s0 = screenAt(g, knownTop, null, o.nodes, o.ms, o.window, false);
            out.screens++;
            out.nodes += s0.nodes;
            if (s0.failed) {
                out.failed = true;
                return out;
            }
            if (s0.lines > 0) {
                out.p = 1.0;
                out.pd = Math.pow(o.gamma, Math.max(0, s0.bestTurn));
                out.direct = true;
                return out;
            }
            out.s0Capped = s0.exhausted;
            if (o.skipCapped && s0.exhausted) {
                out.p = 0.0;
                out.pd = 0.0;
                out.skipped = true;
                return out;
            }
            final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
            int k = knownTop == null ? 0 : knownTop.size();
            if (k > lib.size()) {
                k = 0;
            }
            for (int i = 0; i < k; i++) {
                if (lib.get(i).getId() != knownTop.get(i)) {
                    k = 0; // not a prefix: the reader ignores it too
                    break;
                }
            }
            final java.util.LinkedHashMap<String, List<Card>> byName = new java.util.LinkedHashMap<>();
            for (int i = k; i < lib.size(); i++) {
                byName.computeIfAbsent(lib.get(i).getName(), x -> new ArrayList<>()).add(lib.get(i));
            }
            out.unknown = lib.size() - k;
            if (out.unknown <= 0) {
                out.p = 0.0;
                out.pd = 0.0;
                return out;
            }
            List<List<Card>> groups = new ArrayList<>(byName.values());
            if (o.sig) {
                groups = sigGroups(g, groups, lib.subList(0, k));
            }
            groups.sort((a, b) -> Integer.compare(b.size(), a.size()));
            double p = 0, pd = 0;
            final List<Integer> top = k == 0 ? new ArrayList<>() : new ArrayList<>(knownTop.subList(0, k));
            for (int i = 0; i < groups.size() && i < o.maxNames; i++) {
                final List<Card> grp = groups.get(i);
                final Screen s = screenAt(g, top, grp.get(0), nameNodes, o.ms, o.window, o.nameOnePass);
                out.screens++;
                out.names++;
                out.groups++;
                out.nodes += s.nodes;
                if (s.exhausted) {
                    out.nameCapped++;
                }
                if (!s.failed && s.lines > 0) {
                    p += grp.size() / (double) out.unknown;
                    pd += grp.size() / (double) out.unknown * Math.pow(o.gamma, Math.max(0, s.bestTurn));
                }
            }
            out.p = Math.min(1.0, p);
            out.pd = Math.min(1.0, pd);
        } catch (RuntimeException | StackOverflowError e) {
            out.failed = true;
        } finally {
            out.nanos = forge.game.WorkClock.nanoTime() - t0;
        }
        return out;
    }

    /** solver-distill-cc-1109 (GOLDFISH): {@link #goldfish}'s knobs. */
    public static final class GoldfishOpts {
        /** Our turns the plan may span: this turn and {@code horizon - 1} more. */
        public int horizon = 5;
        /**
         * Turns of one screen in which every move is open; the turns before them are quiet (a land drop and the pass
         * only). The screens slide this window from now to the horizon's end, earliest first. {@code >= horizon}: one
         * screen, every turn open.
         */
        public int active = 3;
        /**
         * The land-drop relaxation: the plan holds one extra land per later turn (one drop a turn, as always; the
         * library is not changed). That land is the union of the lands of our unknown library: every colour any of
         * them makes and every land type any of them has, untapped if any enters untapped. A line that plays one counts
         * {@link #lambda} of a line that does not.
         */
        public boolean lands = true;
        public double lambda = 0.6;
        /** Sampled orders of our unknown library as the later turns' draws (0 = none; the relaxation needs none). */
        public int samples = 0;
        /** Sampled cards past the one per later turn (what this line's own draw spells would find). */
        public int extra = 2;
        /** Node cap of one screen, and of the whole call (0 = no call cap): past it no further screen starts. */
        public int nodes = 1500;
        public long maxNodes = 0;
        public long ms = 60;
        /** A win k of our turns from now counts {@code gamma^k}. */
        public double gamma = 0.8;
        /** The sampled orders' seed: the options of one menu share it, so they see the same draws. */
        public long seed;
        public boolean window = true;
    }

    /** solver-distill-cc-1109 (GOLDFISH): {@link #goldfish}'s result. */
    public static final class Goldfish {
        /** The value: gamma^turn of the best line (times lambda when it plays a granted land), the mean over samples when sampled; NaN = not computed. */
        public double g = Double.NaN;
        /** Share of samples with a line (1 or 0 when not sampled). */
        public double p;
        /** The best line plays no granted land and needs no sampled card. */
        public boolean direct;
        /** The call's node cap stopped it before every screen ran. */
        public boolean cut;
        public boolean failed;
        public int samples, screens, capped, bestTurn = -1, granted;
        public long nodes, nanos;
        public String best;
        /** SETUP: the best line itself (the earliest window's; null when sampled or none). */
        Line bestLine;
    }

    /**
     * solver-distill-cc-1109 (GOLDFISH): how soon the planner wins unopposed over several of our turns, for decisions
     * whose payoff is more than a turn away (a tutor's target, a discard, a put-back). {@link #reach} asks for a line by
     * our next turn over the one next draw, exactly; this plans up to {@code horizon} turns, in screens of {@code active}
     * open turns after quiet ones (the wait for mana, then the turns that decide it). The later turns' cards: the
     * land-drop relaxation ({@link GoldfishOpts#lands}: a goldfish makes its land drops) or sampled orders of the seat's
     * unknown library (its multiset below the known top; a card's place in a sample is a hash of the seed and its id,
     * so two copies of a position that differ by one moved card see the same draws). Honest: the true order of the
     * library is never read. Optimistic, as a goldfish is: no opponent. No card or combo knowledge.
     */
    public Goldfish goldfish(final Game g, final List<Integer> knownTop, final GoldfishOpts o) {
        final Goldfish out = new Goldfish();
        final long t0 = forge.game.WorkClock.nanoTime();
        try {
            final int h = Math.max(1, o.horizon);
            final int w = Math.max(1, Math.min(h, o.active));
            final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
            int k = knownTop == null ? 0 : knownTop.size();
            if (k > lib.size()) {
                k = 0;
            }
            for (int i = 0; i < k; i++) {
                if (lib.get(i).getId() != knownTop.get(i)) {
                    k = 0; // not a prefix: the reader ignores it too
                    break;
                }
            }
            final List<Integer> top = k == 0 ? new ArrayList<>() : new ArrayList<>(knownTop.subList(0, k));
            final List<Card> rest = new ArrayList<>(lib.subList(k, lib.size()));
            rest.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
            final List<Card> grant = new ArrayList<>();
            if (o.lands && h > 1) {
                final java.util.Set<String> names = new java.util.HashSet<>();
                for (Card c : rest) {
                    if (c.isLand() && names.add(c.getName())) {
                        grant.add(c); // one of each land name: the relaxation's land is their union
                    }
                }
            }
            out.granted = grant.size();
            final int want = Math.min(rest.size(), Math.max(0, h - 1 - k) + Math.max(0, o.extra));
            final int runs = o.samples > 0 && want > 0 ? o.samples : 1;
            double sum = 0;
            int hits = 0;
            boolean allDirect = true;
            runs:
            for (int si = 0; si < runs; si++) {
                List<Card> nexts = List.of();
                if (o.samples > 0 && want > 0) {
                    final long sd = mix(o.seed, 0x51ED270BL + si);
                    final List<Card> order = new ArrayList<>(rest);
                    order.removeAll(grant);
                    order.sort(java.util.Comparator.comparingLong((Card c) -> mix(sd, c.getId()))
                            .thenComparingInt(Card::getId));
                    nexts = new ArrayList<>(order.subList(0, Math.min(order.size(), want)));
                }
                double best = 0;
                for (int j = 0; j + w <= h || j == 0; j++) {
                    if (o.maxNodes > 0 && out.nodes >= o.maxNodes) {
                        out.cut = true;
                        break runs;
                    }
                    final Screen sc = screenSeq(g, top, nexts, grant, h - 1, o.nodes, o.ms, o.window, Math.min(h, j + w), j,
                            o.gamma, o.lambda);
                    out.screens++;
                    out.nodes += sc.nodes;
                    if (sc.exhausted) {
                        out.capped++;
                    }
                    if (sc.failed) {
                        out.failed = true;
                        return out;
                    }
                    if (sc.lines > 0) {
                        best = sc.score;
                        allDirect &= sc.scoreDirect && nexts.isEmpty();
                        if (out.bestTurn < 0 || sc.bestTurn < out.bestTurn) {
                            out.bestTurn = sc.bestTurn;
                            out.best = sc.best;
                            out.bestLine = o.samples > 0 ? null : sc.bestLine;
                        }
                        break; // the earliest window with a line
                    }
                }
                out.samples++;
                if (best > 0) {
                    hits++;
                    sum += best;
                }
            }
            if (out.samples > 0) {
                out.p = hits / (double) out.samples;
                out.g = sum / out.samples;
                out.direct = hits > 0 && allDirect;
            } else {
                out.g = 0.0;
            }
        } catch (RuntimeException | StackOverflowError e) {
            out.failed = true;
        } finally {
            out.nanos = forge.game.WorkClock.nanoTime() - t0;
        }
        return out;
    }

    /**
     * solver-distill-cc-1109 (the reach gate): does this seat have ANY line within the goldfish's horizon, with its land
     * drops, either as it stands or with one card of its unknown library as the next draw? One goldfish with no new
     * card, then one per name (most copies first; names of one planner model share a screen with {@code sig}), each over
     * the same windows, until the first line or {@code maxNodes}. False = no single card makes a line (or none was found
     * inside the allowance): a search from here skips the seat's own reach at its leaves. First, with no planner node at
     * all: a seat none of whose cards (hand, battlefield, graveyard, library) can end the game in the planner's model
     * (wins on entry or on a small library, an empty-library draw win, a WIN / DRAIN / MILL_OPP effect) has no line.
     * Names that can end the game or that search or pile the library are planned first. Never throws (false).
     */
    public boolean potential(final Game g, final List<Integer> knownTop, final GoldfishOpts o, final int maxNames,
            final boolean sig, final long maxNodes) {
        try {
            boolean ender = false;
            final java.util.Set<String> seen = new java.util.HashSet<>();
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Library}) {
                for (Card c : me.getCardsIn(z)) {
                    if (seen.add(c.getName()) && endsGames(reader.card(g, me, c))) {
                        ender = true;
                        break;
                    }
                }
                if (ender) {
                    break;
                }
            }
            if (!ender) {
                return false;
            }
            final GoldfishOpts b = new GoldfishOpts();
            b.horizon = o.horizon;
            b.active = o.active;
            b.lands = o.lands;
            b.lambda = o.lambda;
            b.samples = 0;
            b.nodes = o.nodes;
            b.ms = o.ms;
            b.gamma = o.gamma;
            b.window = o.window;
            b.maxNodes = maxNodes;
            b.maxNodes = Math.max(1, maxNodes / 4);
            final Goldfish blank = goldfish(g, knownTop, b);
            if (blank.failed) {
                return false;
            }
            if (blank.g > 0) {
                return true;
            }
            long nodes = blank.nodes;
            final int h = Math.max(1, o.horizon);
            final int w = Math.max(1, Math.min(h, o.active));
            final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
            int k = knownTop == null ? 0 : knownTop.size();
            if (k > lib.size()) {
                k = 0;
            }
            for (int i = 0; i < k; i++) {
                if (lib.get(i).getId() != knownTop.get(i)) {
                    k = 0;
                    break;
                }
            }
            final List<Integer> top = k == 0 ? new ArrayList<>() : new ArrayList<>(knownTop.subList(0, k));
            final List<Card> rest = new ArrayList<>(lib.subList(k, lib.size()));
            rest.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
            final List<Card> grant = new ArrayList<>();
            final java.util.LinkedHashMap<String, List<Card>> byName = new java.util.LinkedHashMap<>();
            final java.util.Set<String> lands = new java.util.HashSet<>();
            for (Card c : rest) {
                byName.computeIfAbsent(c.getName(), x -> new ArrayList<>()).add(c);
                if (o.lands && h > 1 && c.isLand() && lands.add(c.getName())) {
                    grant.add(c);
                }
            }
            List<List<Card>> groups = new ArrayList<>(byName.values());
            if (sig) {
                groups = sigGroups(g, groups, lib.subList(0, k));
            }
            // names that can end the game or choose from the library first, then the most copies
            final java.util.Map<List<Card>, Integer> rank = new java.util.IdentityHashMap<>();
            for (List<Card> grp : groups) {
                final ACard a = reader.card(g, me, grp.get(0));
                rank.put(grp, endsGames(a) ? 0 : chooses(a) ? 1 : 2);
            }
            groups.sort((a, c) -> rank.get(a) != rank.get(c).intValue() ? Integer.compare(rank.get(a), rank.get(c))
                    : Integer.compare(c.size(), a.size()));
            for (int i = 0; i < groups.size() && i < maxNames; i++) {
                final Card next = groups.get(i).get(0);
                if (next.isLand() && o.lands) {
                    continue; // the relaxation already plays a land a turn
                }
                for (int j = 0; j + w <= h || j == 0; j++) {
                    if (maxNodes > 0 && nodes >= maxNodes) {
                        return false; // no line inside the allowance: closed for now (the next check may find one)
                    }
                    final Screen sc = screenSeq(g, top, List.of(next), grant, h - 1, o.nodes, o.ms, o.window, Math.min(h, j + w), j,
                            o.gamma, o.lambda);
                    nodes += sc.nodes;
                    if (sc.failed) {
                        break;
                    }
                    if (sc.lines > 0) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException | StackOverflowError e) {
            return false;
        }
    }

    /** The planner can end the game with this card: it wins on entry or on a small library, or an ability wins, drains or mills. */
    static boolean endsGames(final ACard a) {
        if (a == null) {
            return false;
        }
        if (a.winsOnEntry || a.winIfLibraryAtMost >= 0 || a.emptyDrawWin || !a.loopWith.isEmpty()) {
            return true;
        }
        for (List<ForcedWinModel.Abil> abs : java.util.Arrays.asList(a.fromHand, a.onBattlefield)) {
            for (ForcedWinModel.Abil ab : abs) {
                if (!ab.unplayable && (ab.has(EffKind.WIN) || ab.has(EffKind.DRAIN) || ab.has(EffKind.MILL_OPP))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The card searches or piles our library. */
    static boolean chooses(final ACard a) {
        if (a == null) {
            return false;
        }
        for (ForcedWinModel.Abil ab : a.fromHand) {
            if (!ab.unplayable && (ab.has(EffKind.PILE) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP))) {
                return true;
            }
        }
        return false;
    }

    /** GOLDFISH: the ids of granted copies start here (above any game's card ids). */
    static final int GRANT_ID = 1_000_000_000;

    /**
     * GOLDFISH's screen: {@link #screenAt} with several hypothetical next cards (drawn in this order after the known top),
     * the land-drop relaxation ({@code grants} union lands of {@code grant}, planned as if in hand), its own horizon
     * and quiet turns, planned once.
     * {@code score} = the best line's gamma^turn, times lambda when it plays a granted card.
     */
    Screen screenSeq(final Game g, final List<Integer> knownTop, final List<Card> nexts, final List<Card> grant,
            final int grants, final int nodes, final long ms, final boolean window, final int horizon, final int quiet,
            final double gamma, final double lambda) {
        final Screen s = new Screen();
        final long t0 = forge.game.WorkClock.nanoTime();
        try {
            final Position pos = readPosition(g, knownTop);
            if (pos == null) {
                s.failed = true;
                return s;
            }
            if (window) {
                markWindow(pos, g.getPhaseHandler());
            }
            final int base = knownTop == null ? 0 : knownTop.size();
            if (pos.knownTop.size() != base) {
                s.failed = true; // the known top was not read as given
                return s;
            }
            if (!nexts.isEmpty()) {
                final List<Integer> order = pos.knownOrder == null ? new ArrayList<>() : new ArrayList<>(pos.knownOrder);
                for (Card next : nexts) {
                    pos.knownTop.add(reader.card(g, me, next));
                    order.add(next.getId());
                }
                pos.knownOrder = order;
            }
            // a card the plan will draw (the known top as given, or a sampled card) may search or pile our library: the
            // reader loads its contents only for a chooser already in hand or in play
            boolean chooses = false;
            for (ACard kc : pos.knownTop) {
                for (ForcedWinModel.Abil ab : kc.fromHand) {
                    chooses |= !ab.unplayable && (ab.has(EffKind.PILE) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP));
                }
            }
            if (chooses && pos.library.isEmpty()) {
                final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
                final List<Card> rest = new ArrayList<>(lib.subList(Math.min(lib.size(), base), lib.size()));
                rest.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
                for (Card c : rest) {
                    pos.library.add(reader.card(g, me, c));
                }
                if (pos.graveyard.isEmpty()) {
                    final List<Card> gy = new ArrayList<>(me.getCardsIn(ZoneType.Graveyard));
                    gy.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
                    for (Card c : gy) {
                        pos.graveyard.add(reader.card(g, me, c));
                    }
                }
            }
            // the land-drop relaxation: `grants` copies, under their own ids and name, of the union of the given lands (the
            // library, a pile's pool and its size, keeps the real ones)
            ACard plain = null;
            int colors = 0;
            boolean untapped = false;
            final java.util.Set<String> types = new java.util.LinkedHashSet<>();
            for (Card c : grant) {
                final ACard a = reader.card(g, me, c);
                if (!a.land) {
                    continue;
                }
                colors |= a.sourceColors;
                untapped |= !a.entersTapped;
                types.addAll(a.subtypes);
                if (plain == null || a.onBattlefield.size() < plain.onBattlefield.size()) {
                    plain = a; // the plainest land carries the copy
                }
            }
            for (int gi = 0; plain != null && gi < grants; gi++) {
                final ACard wl = plain.instance(GRANT_ID + gi);
                wl.name = "(land)";
                wl.sourceColors = colors;
                wl.entersTapped = !untapped;
                wl.landLife = 0;
                wl.sourceSac = false;
                wl.subtypes.clear();
                wl.subtypes.addAll(types);
                pos.hand.add(wl);
            }
            for (Card c : nexts) {
                for (int i = 0; i < pos.library.size(); i++) {
                    if (pos.library.get(i).id == c.getId()) {
                        pos.library.remove(i);
                        break;
                    }
                }
            }
            final ForcedWinPlanner.Limits lim = limits();
            lim.nodes = nodes;
            lim.ms = ms;
            lim.payPolicies = 1;
            lim.horizon = Math.max(1, horizon);
            lim.quietTurns = Math.max(0, quiet);
            lim.loops = cfg.planLoops;
            final ForcedWinPlanner.Result r = ForcedWinPlanner.plan(pos, lim);
            s.nodes = r.nodes;
            s.exhausted = r.exhausted;
            s.lines = r.lines.size();
            s.score = 0;
            for (Line l : r.lines) {
                boolean relaxed = false;
                for (ForcedWinPlanner.Step st : l.steps) {
                    relaxed |= st.hostId >= GRANT_ID;
                }
                final double v = Math.pow(gamma, Math.max(0, l.winTurn)) * (relaxed ? lambda : 1.0);
                if (v > s.score || v == s.score && l.steps.size() < s.bestSteps) {
                    s.score = v;
                    s.scoreDirect = !relaxed;
                    s.bestTurn = l.winTurn;
                    s.bestSteps = l.steps.size();
                    s.best = String.valueOf(l);
                    s.bestLine = l;
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            s.failed = true;
            s.best = String.valueOf(e);
        } finally {
            s.nanos = forge.game.WorkClock.nanoTime() - t0;
        }
        return s;
    }

    /** {@link #screen} with an optional hypothetical next card ({@code next}: placed after the known top) and window. */
    Screen screenAt(final Game g, final List<Integer> knownTop, final Card next, final int nodes, final long ms,
            final boolean window) {
        return screenAt(g, knownTop, next, nodes, ms, window, false);
    }

    /**
     * The name groups merged by planner model ({@link ReachOpts#sig}): names of the same signature share a group (a
     * list of all their cards, the first group's first card screened). Kept apart: a name a card of ours in hand, on the
     * battlefield, in the graveyard or in the known top already has, or one a tutor's observed name filter holds.
     */
    private List<List<Card>> sigGroups(final Game g, final List<List<Card>> byName, final List<Card> knownTopCards) {
        final java.util.Set<String> keep = new java.util.HashSet<>();
        final List<Card> ours = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        ours.addAll(me.getCardsIn(ZoneType.Battlefield));
        ours.addAll(me.getCardsIn(ZoneType.Graveyard));
        ours.addAll(knownTopCards);
        for (Card c : ours) {
            keep.add(c.getName());
        }
        final List<ACard> models = new ArrayList<>();
        for (List<Card> grp : byName) {
            models.add(reader.card(g, me, grp.get(0)));
        }
        for (Card c : ours) {
            addFilterNames(reader.card(g, me, c), keep);
        }
        for (ACard a : models) {
            addFilterNames(a, keep);
        }
        final java.util.LinkedHashMap<String, List<Card>> bySig = new java.util.LinkedHashMap<>();
        for (int i = 0; i < byName.size(); i++) {
            final List<Card> grp = byName.get(i);
            final String name = grp.get(0).getName();
            final String key = keep.contains(name) ? "name:" + name : "sig:" + ForcedWinModel.signature(models.get(i));
            bySig.computeIfAbsent(key, x -> new ArrayList<>()).addAll(grp);
        }
        return new ArrayList<>(bySig.values());
    }

    private static void addFilterNames(final ACard a, final java.util.Set<String> out) {
        if (a == null) {
            return;
        }
        for (List<ForcedWinModel.Abil> abs : java.util.Arrays.asList(a.fromHand, a.onBattlefield)) {
            for (ForcedWinModel.Abil ab : abs) {
                for (ForcedWinModel.Eff e : ab.effects) {
                    if (e.names != null) {
                        out.addAll(e.names);
                    }
                }
            }
        }
    }

    /** {@link #screenAt}; {@code onePass}: plan once at the solver's horizon (its lines include this turn's). */
    Screen screenAt(final Game g, final List<Integer> knownTop, final Card next, final int nodes, final long ms,
            final boolean window, final boolean onePass) {
        final Screen s = new Screen();
        final long t0 = forge.game.WorkClock.nanoTime();
        try {
            final Position pos = readPosition(g, knownTop);
            if (pos == null) {
                s.failed = true;
                return s;
            }
            if (window) {
                markWindow(pos, g.getPhaseHandler());
            }
            if (next != null) {
                if (pos.knownTop.size() != (knownTop == null ? 0 : knownTop.size())) {
                    s.failed = true; // the known top was not read as given
                    return s;
                }
                final ACard nc = reader.card(g, me, next);
                pos.knownTop.add(nc);
                boolean chooses = false;
                for (ForcedWinModel.Abil ab : nc.fromHand) {
                    chooses |= !ab.unplayable && (ab.has(EffKind.PILE) || ab.has(EffKind.TUTOR) || ab.has(EffKind.TUTOR_TOP));
                }
                if (chooses && pos.library.isEmpty()) {
                    // the drawn card searches or piles our library: its contents (as the reader gives them when it chooses)
                    final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
                    final List<Card> rest = new ArrayList<>(lib.subList(Math.min(lib.size(), pos.knownTop.size() - 1), lib.size()));
                    rest.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
                    for (Card c : rest) {
                        pos.library.add(reader.card(g, me, c));
                    }
                    if (pos.graveyard.isEmpty()) {
                        final List<Card> gy = new ArrayList<>(me.getCardsIn(ZoneType.Graveyard));
                        gy.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
                        for (Card c : gy) {
                            pos.graveyard.add(reader.card(g, me, c));
                        }
                    }
                }
                final List<Integer> order = pos.knownOrder == null ? new ArrayList<>() : new ArrayList<>(pos.knownOrder);
                order.add(next.getId());
                pos.knownOrder = order;
                for (int i = 0; i < pos.library.size(); i++) {
                    if (pos.library.get(i).id == next.getId()) {
                        pos.library.remove(i);
                        break;
                    }
                }
            }
            final ForcedWinPlanner.Limits lim = limits();
            lim.nodes = nodes;
            lim.ms = ms;
            lim.payPolicies = 1;
            lim.horizon = onePass && cfg.horizon >= 2 ? cfg.horizon : 1;
            lim.loops = cfg.planLoops;
            ForcedWinPlanner.Result r = ForcedWinPlanner.plan(pos, lim);
            s.nodes = r.nodes;
            s.exhausted = r.exhausted;
            if (r.lines.isEmpty() && cfg.horizon >= 2 && lim.horizon == 1) {
                lim.horizon = cfg.horizon;
                r = ForcedWinPlanner.plan(pos, lim);
                s.nodes += r.nodes;
                s.exhausted |= r.exhausted;
            }
            s.lines = r.lines.size();
            for (Line l : r.lines) {
                if (s.bestTurn < 0 || l.winTurn < s.bestTurn || l.winTurn == s.bestTurn && l.steps.size() < s.bestSteps) {
                    s.bestTurn = l.winTurn;
                    s.bestSteps = l.steps.size();
                    s.best = String.valueOf(l);
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            s.failed = true;
            s.best = String.valueOf(e);
        } finally {
            s.nanos = forge.game.WorkClock.nanoTime() - t0;
        }
        return s;
    }

    /**
     * line-adoption-1107 (anyWindow): where in the turn the position is. Before main phase 1 of our turn (untap, upkeep, draw
     * step) the plan reaches this turn's main phase by a MAIN step, drawing the draw step's card from the upkeep.
     */
    void markWindow(Position pos, PhaseHandler ph) {
        final boolean ours = ph.isPlayerTurn(me);
        // their end step keeps its old follower (steps wait for our main phase): only the windows this key opens change
        pos.offMain = !(ours && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2)) || !ours && ph.is(PhaseType.END_OF_TURN));
        pos.preMain = ours && (ph.is(PhaseType.UNTAP) || ph.is(PhaseType.UPKEEP) || ph.is(PhaseType.DRAW));
        pos.preMainDraw = pos.preMain && !ph.is(PhaseType.DRAW) && ph.getTurn() > 1;
    }

    Position readPosition(Game live, List<Integer> knownOrder) {
        if (cfg.honestProbes) {
            return forge.bench.PlayerControllerBridge.isolated(live, () -> forge.util.IdScope.detached(() -> {
                final Game g = honestProbeRoot(live, knownOrder);
                final Player pg = (Player) g.getPlayer(me.getId());
                final Position hp = reader.read(g, pg, knownOrder, true);
                if (cfg.sourceColorsLive && hp != null) {
                    ForcedWinModel.Reader.readSourceColors(hp, pg); // ledger-sweep2-1107
                }
                return hp;
            }));
        }
        final Position pos = reader.read(live, me, knownOrder, true);
        if (cfg.sourceColorsLive && pos != null) {
            ForcedWinModel.Reader.readSourceColors(pos, me); // ledger-sweep2-1107 (before sourceOutput, which reads amounts only)
        }
        if (cfg.sourceOutput && pos != null) {
            ForcedWinModel.Reader.readSourceOutputs(pos, me); // kill-line-1106
        }
        return pos;
    }

    /** The width model's probes copy this root, so no probe starts on the opponent's real hidden cards. */
    Game honestProbeRoot(Game live, List<Integer> knownOrder) {
        if (redraw == null || !cfg.hideOwnLibrary || cfg.exact || cfg.frameMemory) {
            throw new IllegalStateException("refused: dishonest generator probe root");
        }
        synchronized (live) {
            final GameCopier copier = new GameCopier(live, true);
            copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING); // gamecopier-fidelity-1030
            copier.setCopyFidelity(1);
            final Game g = copier.makeCopy();
            final Player cm = (Player) copier.find(me);
            final Random rng = new Random(mix(cfg.seed, 0x425657L + decisionIndex));
            copies++;
            redraw.apply(live, me, g, cm, rng);
            if (cfg.staleStatics) {
                g.staleStatics = true; // pr209-followups: a belief world; the probes' copies of it skip swapped-out cards' statics
            }
            shuffleUnknownOwnLibrary(cm, knownOrder == null ? 0 : knownOrder.size(), pinnedIn(cm), rng, cfg.infoState);
            return g;
        }
    }

    /** A line's engine result. */
    static final class Eval {
        final Line line;
        double pUnopposed;
        double pBelief;
        int worldsRun;
        String protection = "";
        String failures = "";
        /** Lane bvw-plr-1023: the unopposed play-out's end (Outcome#endReason, Outcome#endTurn). */
        String endReason;
        int endTurn = -1;
        /** e1-q8-1101 (libWatch only): some play-out of this evaluation read a hidden own-library card, or could not tell. */
        boolean libDependent;
        /** The most hidden own-library cards one play-out read (detection failed: {@link HiddenWatch#FAILED}). */
        int hiddenTakes;
        /** Exact-draw evaluations: a play-out read a hidden card outside the arranged slots (the enumeration misses it). */
        boolean libUncovered;
        /** next-turn-1107: the card id a step of ours after the pass found gone in the unopposed play-out (-1: none). */
        int lostHost = -1;
        /** opp-response-1108 (oppResponse only): the evaluation's seed and the belief worlds it won (by world index). */
        long seed;
        List<Integer> wonWorlds;
        /** opp-response-1108: P(belief) before the response check (NaN: not checked), worlds checked and refuted, the refutation. */
        double pBefore = Double.NaN;
        int respChecked, respRefuted, respNodes, respResponses;
        String refutedBy;

        Eval(Line line) {
            this.line = line;
        }

        /** Won unopposed, and no belief world was verified: P(belief) here is unknown, not 0 (unified-search-1106). */
        boolean unverified() {
            return pUnopposed >= 1.0 && worldsRun == 0;
        }

        @Override
        public String toString() {
            return "P(unopposed)=" + pUnopposed + " P(belief)=" + String.format("%.2f", pBelief) + " worlds=" + worldsRun
                    + " " + line + (protection.isEmpty() ? "" : " protectedBy=" + protection)
                    + (failures.isEmpty() ? "" : " fails=" + failures)
                    + (Double.isNaN(pBefore) ? "" : " resp=" + String.format("%.2f", pBefore) + "->" + String.format("%.2f", pBelief)
                            + " checked=" + respChecked + " refuted=" + respRefuted + " nodes=" + respNodes + " responses=" + respResponses
                            + (refutedBy == null ? "" : " by=" + refutedBy)); // opp-response-1108
        }
    }

    /** Engine-evaluate up to {@code cfg.top} distinct candidates; the best, or null. */
    Eval evaluateTop(Game live, List<Line> lines, int index) {
        return evaluateTop(live, lines, index, forge.game.WorkClock.nanoTime());
    }

    /** As above; the evaluation's budget counts from {@code from} (the decision's start with the payment search on). */
    Eval evaluateTop(Game live, List<Line> lines, int index, long from) {
        final long deadline = deadlineAt(from + budgetMs() * 1_000_000L);
        if (cfg.evalAvoidLost) {
            return evaluateAvoidLost(live, lines, index, deadline);
        }
        final List<Line> picked = new ArrayList<>();
        final Set<String> shapes = new HashSet<>();
        for (Line l : lines) {
            final String shape = l.pileNames + "|" + l.steps.size() + "|" + l.winTurn + "|" + l.protectionNames;
            if (shapes.add(shape)) {
                picked.add(l);
            }
            if (picked.size() >= cfg.top) {
                break;
            }
        }
        Eval best = null;
        for (int i = 0; i < picked.size(); i++) {
            if (best != null && forge.game.WorkClock.nanoTime() > deadline) {
                meter.evalDeadlineStops++;
                break;
            }
            if (best != null && evalStepsSpent()) {
                meter.evalCapStops++; // bvw-step1-build-1024: budget=counter, the decision's evalSteps are spent
                break;
            }
            if (best != null && unprovenStop(!(best.pUnopposed >= 1.0 && best.pBelief >= 1.0))) {
                break; // deck-belief-1105
            }
            if (forge.ai.AiWork.spent()) {
                break; // rollout-budget-1101: the decision's Forge AI work is spent (a cap hit; offense drops the result)
            }
            final Eval e = evaluate(live, picked.get(i), mix(cfg.seed, index * 1000L + i), deadline);
            if (e.pUnopposed >= 1.0 && e.pBelief > (best == null ? 0 : best.pBelief + 1e-9)) {
                respond(live, e); // opp-response-1108 (no-op when off)
            }
            receipt("FORCED_WIN eval #" + i + tag() + " turn=" + live.getPhaseHandler().getTurn() + " " + e);
            if (evalSink != null) {
                evalSink.add(e);
            }
            if (e.pUnopposed < 1.0) {
                continue;
            }
            if (best == null || e.pBelief > best.pBelief + 1e-9) {
                best = e;
            }
            if (best.pBelief >= 1.0) {
                break;
            }
        }
        return best;
    }

    /** next-turn-1107 ({@link Config#evalAvoidLost}): a line's steps without pile, pick or protection filler. */
    static String skeleton(Line l) {
        final StringBuilder sb = new StringBuilder().append(l.winTurn);
        for (Step st : l.steps) {
            sb.append('|').append(st.pass ? "PASS" : st.kind + ":" + st.hostId + ":" + st.desc + ":" + st.floatColor);
        }
        return sb.toString();
    }

    /** next-turn-1107: the card ids {@code l} uses after its pass. */
    static Set<Integer> usedAfterPass(Line l) {
        final Set<Integer> out = new HashSet<>();
        boolean passed = false;
        for (Step st : l.steps) {
            if (st.pass) {
                passed = true;
            } else if (passed) {
                out.add(st.hostId);
                if (st.body != null) {
                    for (Step b : st.body) {
                        out.add(b.hostId);
                    }
                }
            }
        }
        return out;
    }

    /** next-turn-1107 (report only): candidates skipped as using a lost card; re-plans without lost cards, and their lines. */
    int avoidSkipped, avoidReplans, avoidReplanLines;
    /** next-turn-1107: the position and limits of the planner call whose lines are being evaluated. */
    private Position avoidPos;
    private ForcedWinPlanner.Limits avoidLim;

    /** next-turn-1107 ({@link Config#evalAvoidLost}): as {@link #evaluateTop}, the candidates chosen as the evaluations go. */
    private Eval evaluateAvoidLost(Game live, List<Line> lines0, int index, long deadline) {
        final Set<String> skeletons = new HashSet<>();
        final Set<Integer> lost = new HashSet<>();
        Eval best = null;
        int i = 0;
        List<Line> lines = lines0;
        boolean replanned = false;
        final int skipped0 = avoidSkipped;
        for (int li = 0;; li++) {
            if (li >= lines.size()) {
                // every candidate left uses a lost card: plan once more with the lost cards gone after our pass
                if (replanned || best != null || lost.isEmpty() || avoidSkipped == skipped0 || avoidPos == null
                        || i >= cfg.top || forge.game.WorkClock.nanoTime() > deadline) {
                    break;
                }
                replanned = true;
                avoidReplans++;
                final ForcedWinPlanner.Limits lim2 = limits();
                lim2.nodes = avoidLim.nodes;
                lim2.ms = avoidLim.ms;
                lim2.payPolicies = avoidLim.payPolicies;
                lim2.planDiscard = avoidLim.planDiscard;
                lim2.horizon = avoidLim.horizon;
                lim2.goneAtPass = new HashSet<>(lost);
                final ForcedWinPlanner.Result r2 = ForcedWinPlanner.plan(avoidPos, lim2);
                if (cfg.followPay) {
                    for (Line l2 : r2.lines) {
                        for (Step st : l2.steps) {
                            st.forceLife = st.phyrexianLife;
                        }
                    }
                }
                avoidReplanLines += r2.lines.size();
                receipt("FORCED_WIN avoid-replan" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " gone=" + lost + " "
                        + r2.summary() + (r2.lines.isEmpty() ? "" : " best=" + r2.lines.get(0)));
                lines = r2.lines;
                li = -1;
                continue;
            }
            final Line l = lines.get(li);
            if (i >= cfg.top) {
                break;
            }
            if (forge.game.WorkClock.nanoTime() > deadline && best != null) {
                break;
            }
            if (best != null && unprovenStop(!(best.pUnopposed >= 1.0 && best.pBelief >= 1.0))) {
                break; // deck-belief-1105
            }
            if (!lost.isEmpty() && l.winTurn > 0 && !java.util.Collections.disjoint(usedAfterPass(l), lost)) {
                avoidSkipped++;
                continue;
            }
            if (!skeletons.add(skeleton(l))) {
                continue;
            }
            final Eval e = evaluate(live, l, mix(cfg.seed, index * 1000L + i), deadline);
            if (e.pUnopposed >= 1.0 && e.pBelief > (best == null ? 0 : best.pBelief + 1e-9)) {
                respond(live, e); // opp-response-1108 (no-op when off)
            }
            receipt("FORCED_WIN eval #" + i + tag() + " turn=" + live.getPhaseHandler().getTurn() + " " + e
                    + (e.lostHost >= 0 ? " lost=" + e.lostHost : "") + " skipped=" + avoidSkipped);
            i++;
            if (e.lostHost >= 0) {
                lost.add(e.lostHost);
            }
            if (evalSink != null) {
                evalSink.add(e);
            }
            if (e.pUnopposed < 1.0) {
                continue;
            }
            if (best == null || e.pBelief > best.pBelief + 1e-9) {
                best = e;
            }
            if (best.pBelief >= 1.0) {
                break;
            }
        }
        return best;
    }

    /**
     * P(win | unopposed) and P(win | belief worlds, adversarial counters) of one line. {@code deadline} stops new worlds;
     * a play-out already running is cut only at the wall (the decision's, and at least the deadline plus the grace).
     */
    Eval evaluate(Game live, Line line, long seed, long deadline) {
        if (!cfg.libWatch) {
            return evaluate0(live, line, seed, deadline);
        }
        // e1-q8-1101: every play-out of this evaluation (unopposed, belief worlds, adversary and MIN branches) adds to it
        libDep = false;
        libUncov = false;
        libReads = 0;
        final Eval e = evaluate0(live, line, seed, deadline);
        e.libDependent = libDep;
        e.hiddenTakes = libReads;
        e.libUncovered = libUncov;
        return e;
    }

    private Eval evaluate0(Game live, Line line, long seed, long deadline) {
        if (cfg.exact) {
            return evaluateExact(live, line, seed, deadline);
        }
        final Eval e = new Eval(line);
        // bvw-step1-build-1024: budget=counter: no wall (0); maxSteps bounds each play-out; unified-search-1106: hard deadline
        final long wall = cfg.counter ? 0L
                : hardDeadline != 0 ? hardDeadline : Math.max(decisionWall, deadline + cfg.wallGraceMs * 1_000_000L);
        meter.lines++;
        meter.worlds++;
        final Outcome u = playout(live, line, mix(seed, 0), 0, -1, wall);
        e.pUnopposed = u.won ? 1 : 0;
        e.endReason = u.endReason;
        e.endTurn = u.endTurn;
        if (!u.won && !libAllWorlds) {
            e.failures = "unopposed:" + u.why;
            e.lostHost = u.lostHost;
            return e;
        }
        int spells = 0;
        for (Step s : line.steps) {
            if (s.isSpell()) {
                spells++;
            }
        }
        double sum = 0;
        int n = 0;
        final Map<String, Integer> fails = new HashMap<>();
        final Set<String> prot = new HashSet<>();
        final int nWorlds = beliefWorlds(live); // deck-belief-1105 (cfg.worlds when off)
        if (cfg.oppResponse && !cfg.oppMin) {
            e.seed = seed; // opp-response-1108
            e.wonWorlds = new ArrayList<>();
        }
        for (int w = 1; w <= nWorlds; w++) {
            // unified-search-1106 (hard deadline set): a line that has won every world so far keeps verifying to it
            if (hardDeadline != 0 ? forge.game.WorkClock.nanoTime() > (sum >= n ? hardDeadline : deadline)
                    : n > 0 && forge.game.WorkClock.nanoTime() > deadline) {
                meter.evalDeadlineStops++;
                break;
            }
            if (n > 0 && sum < n && unprovenStop(true)) {
                break; // deck-belief-1105: unprovable already; leave the budget to the plan search
            }
            if (n > 0 && evalStepsSpent()) {
                meter.evalCapStops++; // bvw-step1-build-1024: budget=counter, the decision's evalSteps are spent
                break;
            }
            if (forge.ai.AiWork.spent()) {
                // rollout-budget-1101: the Forge AI work cap is spent: the world counts as not won (never a proof)
                fails.merge(forge.ai.AiWork.CAP, 1, Integer::sum);
                n++;
                break;
            }
            meter.worlds++;
            final long ws = mix(seed, w);
            Outcome worst = cfg.oppMin ? minWorld(live, line, ws, w, wall) : playout(live, line, ws, w, -1, wall);
            if (worst.protectedBy != null) {
                prot.add(worst.protectedBy);
            }
            if (!cfg.oppMin && worst.won && cfg.adversary && worst.oppHasCounter) {
                for (int j = 0; j < spells && worst.won; j++) {
                    final Outcome o = playout(live, line, ws, w, j, wall);
                    if (o.protectedBy != null) {
                        prot.add(o.protectedBy);
                    }
                    if (!o.won) {
                        worst = o;
                    }
                }
            }
            if ("wall".equals(worst.why)) {
                break; // solver-gaps-1015: a world cut at the wall is dropped, not counted as a loss
            }
            sum += worst.won ? 1 : 0;
            n++;
            if (worst.won && e.wonWorlds != null) {
                e.wonWorlds.add(w); // opp-response-1108
            }
            if (!worst.won) {
                fails.merge(worst.why, 1, Integer::sum);
            }
        }
        e.worldsRun = n;
        e.pBelief = n == 0 ? 0 : sum / n;
        e.protection = String.join(",", new java.util.TreeSet<>(prot));
        e.failures = fails.toString();
        return e;
    }

    /** search-bones-1015 (d): {@link Config#exact}'s evaluation (one world, the position as it stands). */
    Eval evaluateExact(Game live, Line line, long seed, long deadline) {
        final Eval e = new Eval(line);
        // bvw-step1-build-1024: budget=counter: no wall (0)
        final long wall = cfg.counter ? 0L : Math.max(decisionWall, deadline + cfg.wallGraceMs * 1_000_000L);
        meter.lines++;
        meter.worlds++;
        Outcome worst = playout(live, line, mix(seed, 0), -1, -1, wall);
        e.pUnopposed = worst.won ? 1 : 0;
        e.endReason = worst.endReason;
        e.endTurn = worst.endTurn;
        e.worldsRun = 1;
        if (cfg.oppMin && worst.won) {
            worst = minWorld(live, line, mix(seed, 0), -1, wall);
        }
        if (!cfg.oppMin && worst.won && cfg.adversary && worst.oppHasCounter) {
            int spells = 0;
            for (Step s : line.steps) {
                if (s.isSpell()) {
                    spells++;
                }
            }
            for (int j = 0; j < spells && worst.won; j++) {
                if (wall != 0 && forge.game.WorkClock.nanoTime() > wall) {
                    worst = new Outcome();
                    worst.why = "wall";
                    libCut = true;
                    break;
                }
                if (evalStepsSpent()) {
                    // bvw-step1-build-1024 (review #13): budget=counter's stand-in for the wall here: the decision's
                    // evalSteps are spent, so the remaining counter answers are not run and the line is not proven
                    meter.evalCapStops++;
                    worst = new Outcome();
                    worst.why = "evalSteps";
                    libCut = true;
                    break;
                }
                final Outcome o = playout(live, line, mix(seed, 0), -1, j, wall);
                if (!o.won) {
                    worst = o;
                }
            }
        }
        e.pBelief = worst.won ? 1 : 0;
        e.failures = worst.won ? "" : worst.why;
        return e;
    }

    /** One play-out's result. */
    static final class Outcome {
        /** next-turn-1107: a step of ours after the line's pass found its card gone: that card's id (-1: none). */
        int lostHost = -1;
        boolean won;
        boolean oppHasCounter;
        String why = "";
        String protectedBy;
        /** Lane bvw-plr-1023 (read-only): the losing opponent's GameLossReason name when won, and the copy's end turn. */
        String endReason;
        int endTurn = -1;
        /** labeller-v2 (d): the opponent's response-set size at each response point this play-out met. */
        final List<Integer> responseSizes = new ArrayList<>();
        /** The shared MIN's record of each response point this play-out met (OppMin). */
        List<OppMin.Point> points;
        /** covfix-a-1102: a scripted mana-ability response could not be activated on this copy. */
        boolean manaSimFailed;
        /** opp-belief-1104: the responses the opponent made (OppMin.Driver#realized). */
        java.util.Map<String, String> realized;
        /** opp-response-1108: the responses the driver enumerated (summed over its points), and its last answer. */
        int respSeen;
        String answered;
    }

    /**
     * Play {@code line} in a copy of the live game: world 0 removes the opponent's hidden hand; world w > 0 samples it
     * from their deck remainder. {@code counterAt}: the adversary counters our spell of that index (-1: Forge AI only).
     * {@code wall} (System.nanoTime(); 0 = none, solver-gaps-1015): no step of the play-out, combat declarations
     * included, runs past it; a play-out cut there is not a win.
     */
    Outcome playout(Game live, Line line, long seed, int world, int counterAt, long wall) {
        return playout(live, line, seed, world, counterAt, wall, null);
    }

    /** opp-response-1108: the end of this decision's check allowance on the work clock (0 = not opened yet). */
    private long respEnd;
    /** opp-response-1108: set while the check's play-outs run (their opponent driver also takes block points). */
    private boolean respMode;

    /**
     * opp-response-1108 ({@link Config#oppResponse}): the response check of a verified line. Each belief world the line
     * won (same world seed as its verification) is replayed against the opponent's legal actions at its decision points
     * ({@link OppMin.Cfg#refute}, at most oppResponseNodes play-outs, oppResponseDepth responses a branch), cheapest
     * first, stopping at the first sequence that beats the line. The worlds are checked in order until the decision's
     * allowance (oppResponseMs on the work clock, opened at its first check) is spent; an unchecked world keeps its
     * verified win. P(belief) = (worlds won - worlds refuted) / worlds run. No card, deck or answer is named anywhere:
     * the actions are the engine's (castable and payable now, legal blocks), in the sampled world's own zones.
     */
    void respond(Game live, Eval e) {
        if (!cfg.oppResponse || cfg.oppMin || e.wonWorlds == null || e.worldsRun == 0 || e.pUnopposed < 1.0) {
            return;
        }
        final long now = forge.game.WorkClock.nanoTime();
        if (respEnd == 0L) {
            respEnd = now + cfg.oppResponseMs * 1_000_000L;
        }
        meter.respLines++;
        e.pBefore = e.pBelief;
        final OppMin.Cfg oc = new OppMin.Cfg(cfg.oppResponseNodes, OppMin.CHARM_CAP, cfg.oppResponseDepth);
        oc.refute = true;
        oc.budgetByDepth = true;
        oc.memo = true;
        int won = e.wonWorlds.size();
        for (int w : e.wonWorlds) {
            if (forge.game.WorkClock.nanoTime() > respEnd || forge.ai.AiWork.spent()) {
                meter.respUnchecked++;
                continue;
            }
            final long ws = mix(e.seed, w);
            final int[] resp = new int[1];
            final String[] by = new String[1];
            final boolean d0 = libDep, uc0 = libUncov, c0 = libCut;
            final int r0 = libReads;
            final int wallCuts0 = wallCuts;
            respMode = true;
            final OppMin.Out m;
            try {
                m = OppMin.min(oc, script -> {
                    meter.respNodes++;
                    e.respNodes++;
                    final Outcome o = playout(live, e.line, ws, w, -1, respEnd, script);
                    if (o.respSeen > resp[0]) resp[0] = o.respSeen;
                    if (!o.won && by[0] == null && !"wall".equals(o.why) && !String.valueOf(o.why).startsWith("error")) {
                        by[0] = (o.answered == null ? "pass" : o.answered) + " " + script + ":" + o.why;
                    }
                    if (o.manaSimFailed || "wall".equals(o.why) || forge.ai.AiWork.CAP.equals(o.why)
                            || String.valueOf(o.why).startsWith("error")) {
                        return new OppMin.Leaf(Double.NaN, String.valueOf(o.why), o.points); // unresolved, never a refutation
                    }
                    return new OppMin.Leaf(o.won ? 1.0 : 0.0, o.why, o.points);
                });
            } finally {
                respMode = false;
                // the check's play-outs neither mark the evaluation library-dependent nor count as its wall cuts
                libDep = d0;
                libUncov = uc0;
                libCut = c0;
                libReads = r0;
                wallCuts = wallCuts0;
            }
            e.respChecked++;
            e.respResponses += resp[0];
            meter.respWorlds++;
            meter.respResponses += resp[0];
            if (m.v == 0.0) {
                e.respRefuted++;
                meter.respRefuted++;
                won--;
                if (e.refutedBy == null) e.refutedBy = "w" + w + " " + by[0];
            }
        }
        e.pBelief = (double) won / e.worldsRun;
        if (e.pBelief < e.pBefore - 1e-9) {
            meter.respLowered++;
        }
        receipt("FORCED_WIN oppResponse" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " P " + String.format("%.2f", e.pBefore)
                + " -> " + String.format("%.2f", e.pBelief) + " worlds=" + e.worldsRun + " won=" + e.wonWorlds.size() + " checked="
                + e.respChecked + " refuted=" + e.respRefuted + " nodes=" + e.respNodes + " responses=" + e.respResponses
                + (e.refutedBy == null ? "" : " by=" + e.refutedBy) + " line=" + e.line);
    }

    /**
     * labeller-v2 (d): the MIN over the opponent's response tree in one world. The root is Forge AI's own play (as the
     * unopposed world play-out); each explored script fixes the opponent's choice at its first response points (0 =
     * Forge AI, 1 = pass, k >= 2 = the (k-2)-th response of the point's set) and leaves later points to Forge AI. The
     * world is won only if every explored branch wins; a tree larger than oppNodes play-outs is not won (cut).
     */
    Outcome minWorld(Game live, Line line, long ws, int world, long wall) {
        // architect-o ruling 10-30: the shared budgeted MIN (OppMin), one implementation with the R3 label search
        final OppMin.Cfg oc = new OppMin.Cfg(cfg.oppNodes, cfg.oppResponses, cfg.oppDepth);
        oc.budgetByDepth = cfg.oppBudgetByDepth; // covfix-a-1102 (oppMinRev 3)
        oc.loopMemo = cfg.oppMinLoopMemo; // solver-gaps-1104
        if (cfg.oppBelief > 0) {
            return beliefWorld(oc, live, line, ws, world, wall);
        }
        final Outcome[] firstLost = new Outcome[1];
        final Outcome[] firstOut = new Outcome[1];
        final OppMin.Out m = OppMin.min(oc, script -> {
            meter.oppMinNodes++;
            final Outcome o = playout(live, line, ws, world, -1, wall, script);
            if (firstOut[0] == null) firstOut[0] = o;
            if (!o.won && firstLost[0] == null) {
                o.why = "min" + script + ":" + o.why;
                firstLost[0] = o;
            }
            if (OppMin.DEBUG) {
                final StringBuilder sb = new StringBuilder();
                if (o.points != null) for (OppMin.Point pt : o.points) sb.append(pt.size).append(' ');
                System.err.println("[oppmin] world " + world + " " + script + " won=" + o.won + " why=" + o.why
                        + " points=" + sb);
            }
            if (o.manaSimFailed) {
                return new OppMin.Leaf(Double.NaN, "mana-sim", o.points); // covfix-a-1102: fail closed, a cap
            }
            return new OppMin.Leaf(o.won ? 1.0 : 0.0, o.why, o.points);
        });
        meter.oppMinLoopMemo += m.loopMemoHits; // solver-gaps-1104 (0 when off)
        if (m.v == 0.0) {
            return firstLost[0];
        }
        if (Double.isNaN(m.v)) {
            meter.noteMinCaps(m.caps);
            meter.noteMinCut(m.cap, m.capCards); // coverage-fix-1102 (meter only)
            return minCut(m.cap == null ? "oppMin" : m.cap.startsWith("nodes") ? "oppNodes"
                    : m.cap.startsWith("truncated") ? "oppResponses" : m.cap.startsWith("budget") ? "oppDepth" : m.cap);
        }
        return firstOut[0];
    }

    /**
     * opp-belief-1104 (oppBelief): the world's value against the opponent's reply from its own K belief worlds
     * ({@link OppMin#beliefMin}): opponent world u is this world with our cards it cannot see re-dealt
     * ({@link OppBeliefWorlds}); u = 0 is this world.
     */
    Outcome beliefWorld(OppMin.Cfg oc, Game live, Line line, long ws, int world, long wall) {
        final Outcome[] at0 = new Outcome[1];
        final OppMin.Out m = OppMin.beliefMin(oc, cfg.oppBelief, cfg.oppBeliefCands, (u, script) -> {
            meter.oppMinNodes++;
            meter.oppBeliefNodes++;
            // the opponent's worlds are its imagination, not ours: their hidden-library reads and cuts do not mark our
            // evaluation as library-dependent or cut (our world's play-out, u = 0, still does)
            final boolean d0 = libDep, uc0 = libUncov, c0 = libCut;
            final int r0 = libReads;
            final Outcome o = playout(live, line, ws, world, -1, wall, script, u);
            if (u > 0) {
                libDep = d0;
                libUncov = uc0;
                libCut = c0;
                libReads = r0;
            }
            if (u == 0) at0[0] = o;
            final java.util.Map<String, String> r = o.realized;
            if (o.manaSimFailed) return new OppMin.Leaf(Double.NaN, "mana-sim", o.points).realized(r);
            return new OppMin.Leaf(o.won ? 1.0 : 0.0, o.why, o.points).realized(r);
        });
        meter.oppBeliefMins++;
        if (!"forge".equals(m.beliefPick)) meter.oppBeliefNonForge++;
        meter.oppMinLoopMemo += m.loopMemoHits;
        if (beliefPicks != null) beliefPicks.add("w" + world + " " + line.steps + " -> " + m.beliefPick);
        if (OppMin.DEBUG) System.err.println("[oppbelief] world " + world + " pick " + m.beliefPick + " Q " + m.beliefQ + " v " + m.v);
        if (Double.isNaN(m.v)) {
            meter.noteMinCaps(m.caps);
            meter.noteMinCut(m.cap, m.capCards);
            return minCut(m.cap == null ? "oppBelief" : m.cap);
        }
        final Outcome o = at0[0];
        if (o != null && !o.won) o.why = "belief" + m.why + ":" + o.why;
        return o;
    }

    /** opp-belief-1104 (tests, null = off): the belief pick of every world, in order. */
    java.util.List<String> beliefPicks;

    private Outcome minCut(String why) {
        libCut = true; // review #161 S2-2 (read only by libExact / libResample)
        meter.oppMinCuts++;
        final Outcome cut = new Outcome();
        cut.why = why;
        return cut;
    }

    Outcome playout(Game live, Line line, long seed, int world, int counterAt, long wall, OppMin.Script script) {
        return playout(live, line, seed, world, counterAt, wall, script, 0);
    }

    /** opp-belief-1104: {@code oppWorld} u >= 1 = the opponent's belief world u of this world (0 = this world). */
    Outcome playout(Game live, Line line, long seed, int world, int counterAt, long wall, OppMin.Script script, int oppWorld) {
        final Outcome out = new Outcome();
        HiddenWatch hw = null;
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player cm;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING); // gamecopier-fidelity-1030
                copier.setPendingStrict(true); // review #156 S2: a copy with a gap fails this node / world
                copier.setCopyFidelity(1); // storm count, per-turn records
                ForcedWinEngine.copyStackIf(copier, live, cfg.engineStack); // R3-5
                copier.setTurnLimits(cfg.turnLimits); // engine-reach-1107
                copier.setOffBattlefieldLimits(cfg.offBattlefieldLimits); // house p12
                copier.setHistoryLki(cfg.historyLki); // house p13
                copier.setCopyBackFace(cfg.copyBackFace); // house p15
                copier.setStackAbilityCopies(cfg.stackAbilityCopies); // house p16
                copier.setStackHistory(cfg.stackHistory); // house p17
                g = copier.makeCopy();
                g.staleStatics = cfg.staleStatics;
                g.stalePlayerStatics = cfg.stalePlayerStatics;
                cm = (Player) copier.find(me);
                copies++;
                final Random rng = new Random(mix(seed, 2));
                if (world >= 0) {
                    // search-bones-1015 (d): world -1 = the position as it stands (exact)
                    if (redraw != null) {
                        redraw.apply(live, me, g, cm, rng); // honest-belief-1020
                    }
                    for (Player o : cm.getOpponents()) {
                        if (world != 0 && keepDealtHand()) {
                            continue; // deck-belief-1105: the redraw dealt this world's hand
                        }
                        final Player liveOpp = (Player) live.getPlayer(o.getId());
                        hideOrSample(live, me, liveOpp, o, world == 0, rng, redraw);
                    }
                    if (libArrange != null) {
                        libArrange.apply(cm, rng); // e1-q8-1101: an exact-draw sequence at the first hidden slots
                    } else if (cfg.infoState) {
                        shuffleUnknownOwnLibrary(cm, line.knownTop, pinnedIn(cm), rng, true); // order-memory-1102
                    } else {
                        shuffleUnknownOwnLibrary(cm, line, rng);
                    }
                } else if (libArrange != null) {
                    libArrange.apply(cm, rng);
                }
                if (oppWorld > 0) {
                    // opp-belief-1104: the opponent's world: our cards it cannot see re-dealt from the canonical pool
                    for (Player o : cm.getOpponents()) {
                        final Player liveOpp = (Player) live.getPlayer(o.getId());
                        final Set<Integer> known = infoStates != null ? infoStates.known(liveOpp, me) : null;
                        OppBeliefWorlds.redeal(live, liveOpp, cm, known, new Random(OppBeliefWorlds.seed(seed, oppWorld)));
                    }
                }
            }
            if (cfg.libWatch) {
                hw = new HiddenWatch(cm, line.knownTop, pinnedIn(cm), libArrange == null ? null : libArrange.cover);
                g.subscribeToEvents(hw);
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 3)));
            final Follower f = follower(line);
            final LineAi lineAi = new LineAi(g, cm, cm.getController().getLobbyPlayer(), f);
            lineAi.hw = hw;
            // bvw-step1-build-1024: budget=counter: each seat's calls in the play-out are capped (the wall's stand-in)
            final long callLimit = cfg.counter ? 2L * cfg.maxSteps : 0L;
            lineAi.bounded(wall).callLimited(callLimit);
            lineAi.uncapped = cfg.lineUncapped; // unified-search-1106
            if (cfg.verifyReplan && line.winTurn > 0) {
                lineAi.startTurn = g.getPhaseHandler().getTurn();
                lineAi.replanner = old -> replanIn(g, cm, old); // kill-line-1106
            }
            cm.dangerouslySetController(lineAi);
            AdversaryAi adv = null;
            for (Player o : g.getPlayers()) {
                if (o != cm) {
                    adv = new AdversaryAi(g, o, o.getController().getLobbyPlayer(), cm, counterAt);
                    adv.passive = cfg.testPassiveOpp;
                    adv.hw = hw;
                    if (script != null) {
                        adv.driver = new OppMin.Driver(script, o, cm, -1); // labeller-v2 (d), the shared MIN
                        adv.driver.manaSearch = cfg.oppManaSearch; // covfix-a-1102
                        adv.driver.loopMemo = cfg.oppMinLoopMemo; // solver-gaps-1104
                        adv.driver.reveal = cfg.oppMinRevealPoints || cfg.oppHoldPoints; // solver-gaps-1104
                        adv.driver.hold = cfg.oppHoldPoints; // opp-belief-1104
                        if (respMode) {
                            // opp-response-1108: blocks are decision points; a line that passes the turn also meets the
                            // opponent's own main phase (its hold decision)
                            adv.driver.blocks = true;
                            adv.driver.reveal = line.winTurn > 0;
                            adv.driver.hold = line.winTurn > 0;
                            adv.driver.manaSearch = false;
                        }
                        out.points = adv.driver.points;
                        out.realized = adv.driver.realized; // opp-belief-1104
                    }
                    adv.bounded(wall).callLimited(callLimit);
                    o.dangerouslySetController(adv);
                    out.oppHasCounter |= AdversaryAi.holdsCounter(o);
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            LookaheadSearch.givePriority(ph, cm);
            final LookaheadSearch.TurnWatch watch = new LookaheadSearch.TurnWatch(ph.getTurn() + 1 + 2 * line.winTurn, null);
            g.subscribeToEvents(watch);
            int steps = 0;
            meter.playouts++;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                ForcedWinEngine.checkWall(wall, "play-out step");
                ph.mainLoopStep();
                steps++;
                meter.playoutSteps++;
            }
            if (steps >= cfg.maxSteps && !g.isGameOver() && !watch.reached) {
                libCut = true; // review #161 S2-2
                meter.playoutStepCaps++;
            }
            out.won = cm.hasWon() || cm.getOpponents().stream().anyMatch(Player::hasLost);
            out.manaSimFailed = adv != null && adv.driver != null && adv.driver.manaSimFailed; // covfix-a-1102
            if (adv != null && adv.driver != null) {
                out.respSeen = adv.driver.responsesSeen; // opp-response-1108
                out.answered = adv.driver.answered;
            }
            out.protectedBy = lineAi.protectedBy;
            if (out.won) {
                // lane bvw-plr-1023 (read-only): how the copy ended, for label rows
                out.endTurn = g.getPhaseHandler().getTurn();
                // the loser left the game's players when it lost: read it from the registered players
                for (Player o : g.getRegisteredPlayers()) {
                    if (o != cm && o.getOutcome() != null && o.getOutcome().lossState != null) {
                        out.endReason = o.getOutcome().lossState.name();
                    }
                }
                if (out.endReason == null && cm.getOutcome() != null && cm.getOutcome().altWinSourceName != null) {
                    out.endReason = "OpponentWon"; // an alternate win ("you win the game") with no loser recorded yet
                }
            }
            if (ForcedWinEngine.TRACE) {
                final StringBuilder sb = new StringBuilder();
                for (Player o : cm.getOpponents()) {
                    sb.append(" oppLife=").append(o.getLife()).append(" oppLib=").append(o.getCardsIn(ZoneType.Library).size())
                            .append(" oppHand=").append(o.getCardsIn(ZoneType.Hand).size());
                }
                receipt("FW_PLAYOUT world=" + world + " counterAt=" + counterAt + " won=" + out.won + " turn="
                        + g.getPhaseHandler().getTurn() + " cursor=" + f.cursor + "/" + line.steps.size() + " broken=" + f.broken
                        + " " + f.why + " life=" + cm.getLife() + sb + " stormCast=" + g.getStack().getSpellsCastThisTurn().size());
            }
            if (!out.won) {
                out.lostHost = f.broken && f.passTurn >= 0 ? f.missingHost : -1; // next-turn-1107
                out.why = f.broken ? "broken@" + f.cursor + ":" + f.why
                        : adv != null && adv.countered != null ? "countered:" + adv.countered
                        : g.isGameOver() ? "lost" : steps >= cfg.maxSteps ? "steps" : "no-win@" + f.cursor;
                if (!f.broken) {
                    out.why += " {" + endState(g, cm, f) + "}";
                }
            }
        } catch (ForcedWinEngine.OutOfBudget | forge.ai.AiWork.Exhausted ex) {
            libCut = true; // review #161 S2-2
            if (forge.ai.AiWork.spent()) {
                // rollout-budget-1101: the decision's Forge AI work cap is spent: not a win, and not dropped as at the wall
                out.won = false;
                out.why = forge.ai.AiWork.CAP;
                meter.playoutAiWorkCuts++;
                receipt("FORCED_WIN playout cut world=" + world + " " + ex.getMessage());
                return out;
            }
            // a step still running at the wall, or an attack the rules refused twice: not a win
            out.won = false;
            out.why = "wall";
            wallCuts++;
            if (ex.getMessage() != null && ex.getMessage().startsWith("wall")) {
                meter.playoutWallCuts++; // bvw-step1-build-1024: a wall-clock cut (not a refused attack)
            } else if (ex.getMessage() != null && ex.getMessage().startsWith("calls")) {
                meter.playoutCallCaps++; // bvw-step1-build-1024: budget=counter's call cap
            }
            receipt("FORCED_WIN playout cut world=" + world + " " + ex.getMessage());
        } catch (RuntimeException | StackOverflowError ex) {
            libCut = true; // review #161 S2-2
            out.won = false;
            out.why = "error:" + ex.getClass().getSimpleName();
            receipt("FORCED_WIN playout error " + ex);
        } finally {
            if (hw != null) {
                libNote(hw); // e1-q8-1101
            }
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return out;
    }

    /**
     * The opponent's hidden hand: world 0 puts it on the bottom of their library (they interact only through their
     * board); belief worlds draw it again from their hand + library (uniform over their deck remainder, keeping cards
     * we have seen). Their library is shuffled either way.
     */
    static void hideOrSample(Game live, Player liveMe, Player liveOpp, Player opp, boolean hide, Random rng) {
        hideOrSample(live, liveMe, liveOpp, opp, hide, rng, null);
    }

    static void hideOrSample(Game live, Player liveMe, Player liveOpp, Player opp, boolean hide, Random rng, Redraw knowledge) {
        final forge.game.player.PlayerView myView = liveMe.getView();
        final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
        final List<Card> lib = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
        final List<Card> keep = new ArrayList<>();
        final List<Card> pool = new ArrayList<>(lib);
        for (Card c : hand) {
            final Card lc = live.findById(c.getId());
            if (lc != null && (lc.getView().canBeShownTo(myView)
                    || knowledge != null && knowledge.remembersHandCard(live, liveMe, lc))) {
                keep.add(c);
            } else {
                pool.add(c);
            }
        }
        Collections.shuffle(pool, rng);
        final List<Card> newHand = new ArrayList<>(keep);
        final List<Card> hidden = new ArrayList<>();
        if (!hide) {
            final int need = hand.size() - keep.size();
            newHand.addAll(pool.subList(0, need));
            pool.subList(0, need).clear();
        } else {
            // solver-gaps-1015: the hidden hand leaves the game (exile), not into their library: their library keeps its
            // size (a milled-out opponent stays milled out in the unopposed world)
            for (Card c : hand) {
                if (!keep.contains(c)) {
                    hidden.add(c);
                    pool.remove(c);
                }
            }
        }
        opp.getZone(ZoneType.Hand).setCards(newHand);
        opp.getZone(ZoneType.Library).setCards(pool);
        if (!hidden.isEmpty()) {
            final List<Card> ex = new ArrayList<>(opp.getCardsIn(ZoneType.Exile));
            ex.addAll(hidden);
            opp.getZone(ZoneType.Exile).setCards(ex);
        }
    }

    /**
     * Our library order is unknown to us except the top the seat put there (pile memory, solver-gaps-1015): shuffle the
     * rest in each world.
     */
    static void shuffleUnknownOwnLibrary(Player me, Line line, Random rng) {
        shuffleUnknownOwnLibrary(me, line.knownTop, rng);
    }

    static void shuffleUnknownOwnLibrary(Player me, int knownTop, Random rng) {
        shuffleUnknownOwnLibrary(me, knownTop, null, rng);
    }

    /**
     * known-lib-1102: as above, the {@code pinned} cards (null or empty = none: exactly the above, RNG draws included)
     * kept at their index too; the other cards below the known top are shuffled into the other slots.
     */
    static void shuffleUnknownOwnLibrary(Player me, int knownTop, Set<Integer> pinned, Random rng) {
        shuffleUnknownOwnLibrary(me, knownTop, pinned, rng, false);
    }

    /**
     * order-memory-1102: {@code canonical} (the solver's infoState) puts the unknown cards in id order before the shuffle,
     * so a play-out world never depends on the true order of what the seat does not know.
     */
    static void shuffleUnknownOwnLibrary(Player me, int knownTop, Set<Integer> pinned, Random rng, boolean canonical) {
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final int k = Math.min(knownTop, lib.size());
        if (k >= lib.size()) {
            return;
        }
        final List<Card> rest = new ArrayList<>();
        for (int i = k; i < lib.size(); i++) {
            if (pinned == null || !pinned.contains(lib.get(i).getId())) {
                rest.add(lib.get(i));
            }
        }
        if (canonical) {
            rest.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        }
        Collections.shuffle(rest, rng);
        final List<Card> out = new ArrayList<>(lib.subList(0, k));
        int r = 0;
        for (int i = k; i < lib.size(); i++) {
            out.add(pinned != null && pinned.contains(lib.get(i).getId()) ? lib.get(i) : rest.get(r++));
        }
        me.getZone(ZoneType.Library).setCards(out);
    }

    // ------------------------------------------------------------------ diagnostics (solver-gaps-1015)

    private static final String[] RESTRICTION_PARAMS = {"CheckSVar", "SVarCompare", "IsPresent", "PresentCompare",
            "PresentZone", "Activation", "ActivationLimit", "ActivationPhases", "SorcerySpeed", "PlayerTurn",
            "ActivationLifeTotal", "ActivationZone"};

    private static String names(Game g, List<Integer> ids) {
        final List<String> out = new ArrayList<>();
        for (int id : ids) {
            final Card c = id < 0 ? null : g.findById(id);
            out.add(c == null ? "?" : c.getName());
        }
        return out.toString();
    }

    private static String cardNames(Iterable<Card> cs, int max) {
        final List<String> out = new ArrayList<>();
        for (Card c : cs) {
            if (out.size() >= max) {
                out.add("...");
                break;
            }
            out.add(c.getName());
        }
        return out.toString();
    }

    /**
     * Diagnostics only: why a line's step cannot be played in this game copy. Reports where the host card is, which
     * check refuses the ability, and the width model's expected hand and library top before the step against the
     * game's. Never changes the game (read-only checks on a copy of the ability).
     */
    /** next-turn-1107: card {@code id} is no longer ours to use: gone, in a graveyard or exile, or another player's. */
    static boolean gone(Game g, Player me, int id) {
        final Card c = g.findById(id);
        if (c == null) {
            return true;
        }
        final ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
        return z == null || z == ZoneType.Graveyard || z == ZoneType.Exile || z == ZoneType.Battlefield && c.getController() != me;
    }

    static String diagnose(Game g, Player me, Step st) {
        final StringBuilder sb = new StringBuilder();
        try {
            final PhaseHandler ph = g.getPhaseHandler();
            sb.append("t").append(ph.getTurn()).append(' ').append(ph.getPhase());
            final Card host = g.findById(st.hostId);
            if (host == null) {
                sb.append(" host=gone");
            } else {
                final ZoneType z = host.getZone() == null ? null : host.getZone().getZoneType();
                sb.append(" host=").append(z);
                if (z == ZoneType.Library) {
                    sb.append('#').append(me.getCardsIn(ZoneType.Library).indexOf(host));
                }
                final boolean onBf = st.kind == AbilKind.ACTIVATE || st.kind == AbilKind.FLOAT;
                if (onBf ? z == ZoneType.Battlefield : z == ZoneType.Hand || st.kind == AbilKind.ENGINE) {
                    SpellAbility sa = null;
                    if (st.kind == AbilKind.FLOAT) {
                        for (SpellAbility m : host.getManaAbilities()) {
                            if (st.desc.isEmpty() || st.desc.equals(m.getDescription())) {
                                sa = m;
                                break;
                            }
                        }
                    } else if (st.kind == AbilKind.LAND) {
                        sb.append(" landDrop=").append(me.getLandsPlayedThisTurn()).append('/').append(me.getMaxLandPlays());
                    } else {
                        sa = ForcedWinProbe.locate(host, me, st.desc, st.costKey, st.kind == AbilKind.ACTIVATE);
                    }
                    if (sa == null && st.kind != AbilKind.LAND) {
                        sb.append(" abil=none");
                    } else if (sa != null) {
                        sa = sa.copy(me);
                        sa.setActivatingPlayer(me);
                        if (st.targetVariant != null && !ForcedWinProbe.setPlayerTargets(sa, me, st.targetVariant)) {
                            sb.append(" target=no");
                        } else if (!sa.canPlay()) {
                            final forge.game.spellability.SpellAbilityRestriction r = sa.getRestrictions();
                            sb.append(" canPlay=no");
                            if (r != null) {
                                sb.append(!sa.canCastTiming(host, me) ? ":timing" : "")
                                        .append(!r.checkTimingRestrictions(host, sa) ? ":timingR" : "")
                                        .append(!r.checkZoneRestrictions(host, sa) ? ":zone" : "")
                                        .append(!r.checkActivatorRestrictions(host, sa) ? ":activator" : "")
                                        .append(!r.checkOtherRestrictions(host, sa, me) ? ":other" : "");
                            }
                            for (String k : RESTRICTION_PARAMS) {
                                if (sa.hasParam(k)) {
                                    sb.append(' ').append(k).append('=').append(sa.getParam(k));
                                }
                            }
                            sb.append(" activations=").append(sa.getActivationsThisTurn());
                        } else if (!ComputerUtilCost.canPayCost(sa, me, false)) {
                            sb.append(" pay=no");
                        } else {
                            sb.append(" playable");
                        }
                    }
                }
            }
            final List<Integer> hand = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                hand.add(c.getId());
            }
            final List<Integer> lib = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                if (lib.size() >= 6) {
                    break;
                }
                lib.add(c.getId());
            }
            if (st.expHandIdx != null) {
                final List<Integer> missing = new ArrayList<>(), extra = new ArrayList<>(hand);
                int unseen = 0;
                for (int id : st.expHand) {
                    if (id < 0) {
                        unseen++;
                    } else if (!extra.remove(Integer.valueOf(id))) {
                        missing.add(id);
                    }
                }
                sb.append(" handMissing=").append(names(g, missing)).append(" handExtra=").append(names(g, extra))
                        .append(" handUnseen=").append(unseen);
                sb.append(" expLib=").append(names(g, st.expLib));
            }
            sb.append(" hand=").append(names(g, hand)).append(" lib=").append(names(g, lib)).append(" libSize=")
                    .append(me.getCardsIn(ZoneType.Library).size());
            final StringBuilder pool = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                final int n = me.getManaPool().getAmountOfColor(ForcedWinModel.FORGE_COLOR[i]);
                if (n > 0) {
                    pool.append(n).append(ForcedWinModel.SHORT[i]);
                }
            }
            final List<String> untapped = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isTapped() && !c.getManaAbilities().isEmpty()) {
                    untapped.add(c.getName());
                }
            }
            sb.append(" pool=").append(pool).append(" untappedSources=").append(untapped).append(" life=").append(me.getLife())
                    .append(" storm=").append(g.getStack().getSpellsCastThisTurn().size()).append(" gy=")
                    .append(me.getCardsIn(ZoneType.Graveyard).size());
        } catch (RuntimeException e) {
            sb.append(" diag-error=").append(e);
        }
        return sb.toString();
    }

    /** Diagnostics only: the play-out's end state when a line ran without breaking and did not win. */
    static String endState(Game g, Player me, Follower f) {
        final StringBuilder sb = new StringBuilder();
        try {
            final PhaseHandler ph = g.getPhaseHandler();
            int devotion = me.getDevotionMod();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                devotion += ForcedWinModel.bluePips(c);
            }
            sb.append("t").append(ph.getTurn()).append(' ').append(ph.getPhase()).append(" cursor=").append(f.cursor).append('/')
                    .append(f.line.steps.size()).append(f.done ? " done" : "").append(f.attackPending ? " attackPending" : "")
                    .append(" life=").append(me.getLife()).append(" libSize=").append(me.getCardsIn(ZoneType.Library).size())
                    .append(" devotionU=").append(devotion).append(" hand=").append(cardNames(me.getCardsIn(ZoneType.Hand), 10))
                    .append(" lib=").append(cardNames(me.getCardsIn(ZoneType.Library), 4));
            for (Player o : me.getOpponents()) {
                sb.append(" oppLife=").append(o.getLife()).append(" oppLib=").append(o.getCardsIn(ZoneType.Library).size());
            }
            sb.append(" stack=").append(g.getStack().size()).append(" over=").append(g.isGameOver());
        } catch (RuntimeException e) {
            sb.append(" diag-error=").append(e);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ live answers (choice asks of the line)

    public Card answerPick(ZoneType destination, List<ZoneType> origin, SpellAbility source, CardCollection choices,
            Player decider) {
        final Card c = active == null || decider != me ? null : active.pick(source, choices);
        if (c != null && destination == ZoneType.Library && !cfg.infoState) {
            notePlaced(List.of(c));
        }
        return c;
    }

    public CardCollectionView answerOrder(CardCollectionView cards, ZoneType destination, SpellAbility source) {
        if (destination == ZoneType.Library && !cfg.infoState) {
            // pile memory: whichever order is chosen for our library, it is ours (infoState: the moves teach the state)
            notePlaced(cards);
        }
        return active == null ? null : active.order(cards, destination, source);
    }

    public Boolean answerConfirm(SpellAbility sa, String message) {
        if (active == null || active.done || message == null || !message.toLowerCase().contains("shuffle")) {
            return null;
        }
        return Boolean.FALSE;
    }

    public ImmutablePair<CardCollection, CardCollection> answerLook(CardCollection topN) {
        return active == null || active.done ? null : ImmutablePair.of(new CardCollection(topN), new CardCollection());
    }

    /** Our triggers going on the stack: the active line's forced trigger target, if any; the rest for Forge AI. */
    public List<SpellAbility> answerTriggers(List<SpellAbility> abilities) {
        if (active == null || active.triggers.forced == null) {
            return abilities;
        }
        return active.triggers.apply(me.getGame(), me, abilities);
    }

    /** The optional trigger whose target the active line forced: yes. False = not ours. */
    public boolean answerConfirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
        return active != null && active.triggers.confirms(wrapper);
    }

    /** Tap or untap while a line is active: the rules' default (untap our own, tap theirs); null = not ours. */
    public Boolean answerBinary(forge.game.player.PlayerController.BinaryChoiceType kind, Boolean defaultVal) {
        if (active == null || active.done || kind != forge.game.player.PlayerController.BinaryChoiceType.TapOrUntap
                || defaultVal == null) {
            return null;
        }
        return defaultVal;
    }

    /**
     * kill-line-1106 (plans.lineKeep): a discard answered by Forge AI while a line is followed, with the line's reserved
     * cards swapped out, as {@link LineAi} verified it. No line, or nothing reserved: the answer as given.
     */
    public CardCollection keepReserved(CardCollection ordinary, CardCollectionView valid) {
        if (active == null || active.done || ordinary == null) {
            return ordinary;
        }
        final String t = ordinary.isEmpty() || valid.size() <= ordinary.size() ? null : active.choices.next(ForcedWinChoices.DISC);
        final CardCollection rec = t == null ? null : ForcedWinChoices.applyDisc(t, valid, ordinary.size(), ordinary.size());
        if (rec != null) {
            lineKeepSwaps++;
            return rec; // planDiscard: the discard the line recorded
        }
        final CardCollection out = avoid(ordinary, valid, active.reserved());
        if (out != ordinary && !out.equals(ordinary)) {
            lineKeepSwaps++;
        }
        return out;
    }

    /** kill-line-1106 (verifyReplan; report only): worlds that planned again after a line broke, and those that found a line. */
    public int verifyReplans, verifyReplanLines;

    /** kill-line-1106 (verifyReplan): a fresh same-turn line from a world's position; the cards the old line placed stay known. */
    private Line replanIn(Game g, Player p, Line old) {
        verifyReplans++;
        final Set<Integer> placed = new HashSet<>();
        for (Step st : old.steps) {
            placed.addAll(st.pile);
        }
        final List<Integer> known = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            if (!placed.contains(c.getId())) {
                break;
            }
            known.add(c.getId());
        }
        final Position pos;
        synchronized (reader) {
            pos = reader.read(g, p, known, true);
        }
        if (pos == null) {
            return null;
        }
        if (cfg.sourceColorsLive) {
            ForcedWinModel.Reader.readSourceColors(pos, p); // ledger-sweep2-1107
        }
        if (cfg.sourceOutput) {
            ForcedWinModel.Reader.readSourceOutputs(pos, p);
        }
        final ForcedWinPlanner.Limits lim = limits();
        lim.nodes = cfg.nodes;
        lim.ms = forge.ai.WallClock.budgetMs(cfg.planMs);
        lim.payPolicies = 1;
        lim.horizon = 1;
        lim.planDiscard = cfg.planDiscard;
        final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, lim);
        if (res.lines.isEmpty()) {
            return null;
        }
        verifyReplanLines++;
        final Line fresh = res.lines.get(0);
        if (cfg.followPay) {
            for (Step st : fresh.steps) {
                st.forceLife = st.phyrexianLife;
            }
        }
        return fresh;
    }

    /** kill-line-1106 (report only): discards where a reserved card was swapped out. */
    public int lineKeepSwaps;

    /** Card ids the active line still needs in hand (discard choices avoid them). */
    public Set<Integer> reserved() {
        return active == null || active.done ? Collections.emptySet() : active.reserved();
    }

    // ------------------------------------------------------------------ the follower (live seat and play-outs)

    /** A follower of {@code line} with this solver's follower keys (next-turn-1107: {@link Config#putBackKeep}). */
    private Follower follower(Line line) {
        return Follower.of(line, cfg); // consolidate-1107: one place sets the follower's keys
    }

    /** Executes a line step by step in any game where its card ids exist. */
    static final class Follower {
        final Line line;
        int cursor;
        int passTurn = -1;
        boolean done, broken;
        String why = "";
        /** Executed steps by host id: the choice asks of a resolving step are answered from it. */
        final Map<Integer, Step> executed = new HashMap<>();
        final Map<Integer, Integer> pilePicked = new HashMap<>();
        /** next-turn-1107 ({@link Config#putBackKeep}): a put-back of cards the line did not name avoids the line's cards. */
        boolean putBackKeep;
        /** next-turn-1107: the card id of the step found missing when the line broke (-1: none). */
        int missingHost = -1;
        /** next-turn-1107 (report only): put-back picks answered from the unreserved cards. */
        int putBackKept;
        /** kill-line-1106 (planDiscard): the current step's recorded choices. */
        ForcedWinChoices.Cursor choices = new ForcedWinChoices.Cursor(null);
        /** An ATTACK step is waiting for our declare-attackers step. */
        boolean attackPending;
        /** The forced trigger target of the step being resolved (ENGINE steps). */
        ForcedWinEngine.TriggerTargets triggers = new ForcedWinEngine.TriggerTargets(null);
        /** The forced library pick of the step being resolved (ENGINE steps). */
        ForcedWinEngine.LibraryPicks picks = new ForcedWinEngine.LibraryPicks(null);
        /**
         * labeller-v2 (b) (false = v1): the step being resolved answers its recorded choice tokens; (e): a step's recorded
         * payment sources float before its action.
         */
        boolean cacLine, payRecord;
        /** ledger-sweep-1107 ({@link Config#enginePay}): set with payRecord; kept by a verifyReplan follower. */
        boolean enginePay;
        int floatDone;

        Follower(Line line) {
            this.line = line;
        }

        /** solver-gaps-1104 (PASS_END lines only; -1 = none): the turn whose opponent end step the line acts in. */
        int endTurn = -1;

        /** labeller-v2: a play-out follower with the config's (b) and (e) switches. */
        static Follower of(Line line, Config cfg) {
            final Follower fo = new Follower(line);
            fo.cacLine = cfg.cacLine;
            fo.payRecord = cfg.payRecord || cfg.enginePay; // ledger-sweep-1107 (row 60)
            fo.enginePay = cfg.enginePay;
            fo.uncapped = cfg.loopMacro && cfg.loopMacro3; // review #154 S2: its own key
            fo.costLine = cfg.engineCostChoice;
            fo.putBackKeep = cfg.putBackKeep; // line-adoption-1107
            return fo;
        }

        /**
         * solver-cap12 round 3 (R3-4 c; loopMacro only, false = off): the line's own steps are not subject to the play-out
         * loop breaker (RolloutAi's 25 actions per window): a REPEAT's iterations are the line's, bounded by its count.
         * Off, a loop line breaks at the 13th two-step iteration (fastbond-zuran-crucible-aetherflux, Basalt + Kinnan).
         */
        boolean uncapped;
        /** R3-4 (b), engineCostChoice (false = off): the line's recorded COST answers are replayed (LineAi's CacHook). */
        boolean costLine;

        static final class Next {
            SpellAbility action;
            boolean waiting, broken, waitingEnd;
            String why = "";
        }

        Set<Integer> reserved() {
            final Set<Integer> out = new HashSet<>();
            for (int i = cursor; i < line.steps.size(); i++) {
                final Step s = line.steps.get(i);
                if (s.kind == AbilKind.REPEAT) {
                    for (Step b : s.body) {
                        out.add(b.hostId);
                    }
                } else if (!s.pass) {
                    out.add(s.hostId);
                }
            }
            return out;
        }

        /** search-bones-1015 (c): the position inside the current REPEAT step (repetition, body index). */
        int repIter, repPos;
        /** line-adoption-1107: an offMain line has reached its first PASS or MAIN. */
        boolean offMainDone;

        /** line-adoption-1107: play one plain step now (any window), as the main-phase path does. */
        private Next play(Game g, Player me, Next n, Step st) {
            final SpellAbility sa = locate(g, me, st);
            if (sa == null) {
                broken = true;
                missingHost = gone(g, me, st.hostId) ? st.hostId : -1;
                why = "missing:" + st + " {" + diagnose(g, me, st) + "}";
                n.broken = true;
                n.why = why;
                return n;
            }
            cursor++;
            executed.put(st.hostId, st);
            choices = new ForcedWinChoices.Cursor(st.cac);
            triggers = new ForcedWinEngine.TriggerTargets(st.triggerRef);
            picks = new ForcedWinEngine.LibraryPicks(st.pickName);
            n.action = sa;
            return n;
        }

        Next next(Game g, Player me) {
            final Next n = new Next();
            if (!g.getStack().isEmpty() && !(line.stackStart && g.getStack().peek().getActivatingPlayer() != me)) {
                // our own spell resolves first; other stack objects are answered by the caller's protection logic
                // (R3-5: a response line acts on top of the opponent's item)
                n.waiting = true;
                return n;
            }
            final PhaseHandler ph = g.getPhaseHandler();
            if (line.offMain && !offMainDone) {
                // line-adoption-1107 (anyWindow): a line planned outside our main phases plays its steps before its first
                // PASS or MAIN in the window it was taken in; MAIN waits for this turn's main phase 1
                final Step c0 = cursor < line.steps.size() ? line.steps.get(cursor) : null;
                if (c0 != null && c0.kind == AbilKind.MAIN) {
                    if (!(ph.isPlayerTurn(me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2)))) {
                        n.waiting = true;
                        return n;
                    }
                    cursor++;
                    offMainDone = true;
                } else if (c0 != null && !c0.pass && c0.kind != AbilKind.ATTACK && c0.kind != AbilKind.REPEAT) {
                    return play(g, me, n, c0);
                } else {
                    offMainDone = true;
                }
            }
            if (passTurn < 0 && cursor < line.steps.size() && line.steps.get(cursor).pass) {
                // solver-gaps-1015: a PASS ends the turn it is reached in, ours or theirs (a line taken at their end step
                // starts with PASS: it means "to our next turn", not "skip our next turn")
                passTurn = ph.getTurn();
            }
            if (cursor < line.steps.size() && line.steps.get(cursor).toEnd && passTurn >= 0 && ph.getTurn() > passTurn
                    && !ph.isPlayerTurn(me) && ph.is(PhaseType.END_OF_TURN)) {
                // solver-gaps-1104 (PASS_END): the opponent's end step after the pass: the line's next steps act here
                cursor++;
                passTurn = -1;
                endTurn = ph.getTurn();
                if (cursor < line.steps.size() && line.steps.get(cursor).pass) {
                    passTurn = ph.getTurn();
                }
            }
            final boolean inWindow = line.window != null && ph.getTurn() == line.windowTurn && ph.is(line.window)
                    || endTurn >= 0 && ph.getTurn() == endTurn && ph.is(PhaseType.END_OF_TURN) && !ph.isPlayerTurn(me);
            if (!inWindow && (!ph.isPlayerTurn(me) || !(ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2)))) {
                n.waiting = !done;
                if (done) {
                    n.waitingEnd = false;
                }
                return n;
            }
            while (cursor < line.steps.size() && line.steps.get(cursor).pass) {
                if (line.steps.get(cursor).toEnd) {
                    // solver-gaps-1104: PASS_END advances only at the opponent's end step (above)
                    if (passTurn < 0) {
                        passTurn = ph.getTurn();
                    }
                    n.waiting = true;
                    return n;
                }
                if (passTurn < 0) {
                    passTurn = ph.getTurn();
                    n.waiting = true;
                    return n;
                }
                if (ph.getTurn() == passTurn) {
                    n.waiting = true;
                    return n;
                }
                cursor++;
                if (costLine) {
                    choices = new ForcedWinChoices.Cursor(null); // review #154-5: a PASS spends no step's tokens
                }
            }
            if (cursor >= line.steps.size()) {
                done = true;
                n.waitingEnd = false;
                return n;
            }
            final Step st0 = line.steps.get(cursor);
            // search-bones-1015 (c): a REPEAT step plays its body steps in order, its number of times
            final boolean inRepeat = st0.kind == AbilKind.REPEAT;
            final Step st = inRepeat ? st0.body.get(repPos) : st0;
            if (st.kind == AbilKind.MAIN) {
                cursor++; // line-adoption-1107: already in a main phase of ours
                return next(g, me);
            }
            if (st.kind == AbilKind.ATTACK) {
                if (!ph.is(PhaseType.MAIN1)) {
                    // the attack happened (or its chance passed): go on with the line in main phase 2
                    attackPending = false;
                    cursor++;
                    return next(g, me);
                }
                attackPending = true;
                n.waiting = true;
                return n;
            }
            if (payRecord && st.payFloats != null && floatDone < st.payFloats.size()) {
                // labeller-v2 (e): the step's recorded sources float first (Forge AI then pays from the pool)
                final SpellAbility fa = locate(g, me, st.payFloats.get(floatDone));
                if (fa == null) {
                    broken = true;
                    why = "payFloat:" + st.payFloats.get(floatDone) + " of " + st;
                    n.broken = true;
                    n.why = why;
                    return n;
                }
                floatDone++;
                n.action = fa;
                return n;
            }
            final SpellAbility sa = locate(g, me, st);
            if (sa == null) {
                broken = true;
                missingHost = gone(g, me, st.hostId) ? st.hostId : -1; // next-turn-1107
                why = "missing:" + st + " {" + diagnose(g, me, st) + "}";
                n.broken = true;
                n.why = why;
                return n;
            }
            if (inRepeat) {
                repPos++;
                if (repPos >= st0.body.size()) {
                    repPos = 0;
                    repIter++;
                    if (repIter >= st0.times) {
                        repIter = 0;
                        cursor++;
                    }
                }
            } else {
                cursor++;
            }
            executed.put(st.hostId, st);
            choices = new ForcedWinChoices.Cursor(st.cac); // kill-line-1106 (planDiscard; none = empty)
            triggers = new ForcedWinEngine.TriggerTargets(st.triggerRef);
            picks = new ForcedWinEngine.LibraryPicks(st.pickName);
            floatDone = 0;
            if (cacLine || costLine) {
                choices = new ForcedWinChoices.Cursor(st.cacPlayed != null ? st.cacPlayed : st.cac);
            }
            n.action = sa;
            return n;
        }

        /** The live/copy ability for a step, prepared (targets, mana colour), or null when it cannot be played now. */
        static SpellAbility locate(Game g, Player me, Step st) {
            OppBeliefWorlds.materialize(g, me, st.hostId); // opp-belief-1104: no-op outside an opponent world
            final SpellAbility sa = locateAt(g, me, st, st.hostId);
            if (sa != null || !st.byName) {
                return sa;
            }
            // R2-3 (loopMacro): a loop body's step is played by any card of ours with the host's name when the host
            // itself cannot (Forge chose another copy to sacrifice or move inside the loop: the copies are fungible)
            for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Command}) {
                for (Card c : me.getCardsIn(z)) {
                    if (c.getId() != st.hostId && c.getName().equals(st.hostName)) {
                        final SpellAbility alt = locateAt(g, me, st, c.getId());
                        if (alt != null) {
                            return alt;
                        }
                    }
                }
            }
            return null;
        }

        private static SpellAbility locateAt(Game g, Player me, Step st, int hostId) {
            final Card host = g.findById(hostId);
            if (host == null) {
                return null;
            }
            if (st.kind == AbilKind.FLOAT) {
                // solver-gaps-1015: the colour the line spends it on (a returned land's float, or any-colour mana)
                final int colour = st.floatColor >= 0 ? st.floatColor : firstColour(st.anyColors);
                for (SpellAbility m : host.getManaAbilities()) {
                    final SpellAbility a = m.copy(me);
                    a.setActivatingPlayer(me);
                    if (!st.desc.isEmpty() && !st.desc.equals(a.getDescription())) {
                        continue;
                    }
                    if (colour >= 0 && colour < 5) {
                        if (a.getManaPart() == null || !a.canProduce(ForcedWinModel.SHORT[colour])) {
                            continue;
                        }
                        a.setManaExpressChoice(ColorSet.fromMask(ForcedWinModel.FORGE_COLOR[colour]));
                    }
                    if (!a.canPlay() || !ComputerUtilCost.canPayCost(a, me, false)) {
                        continue;
                    }
                    return a;
                }
                return null;
            }
            if (st.kind == AbilKind.LAND) {
                for (SpellAbility sa : host.getAllPossibleAbilities(me, true)) {
                    if (sa.isLandAbility()) {
                        return sa;
                    }
                }
                return null;
            }
            if (st.kind == AbilKind.ENGINE) {
                for (SpellAbility sa0 : host.getAllPossibleAbilities(me, true)) {
                    final String ck = sa0.getPayCosts() == null ? "" : sa0.getPayCosts().toString();
                    if (!st.desc.equals(sa0.getDescription()) || !st.costKey.equals(ck)) {
                        continue;
                    }
                    final SpellAbility sa = sa0.copy(me);
                    sa.setActivatingPlayer(me);
                    if (st.xValue >= 0) {
                        sa.setXManaCostPaid(st.xValue); // R3-4 (a): the line's X, not Forge AI's
                    }
                    if (!st.targetRefs.isEmpty() && !ForcedWinEngine.applyRefs(sa, st.targetRefs, g)) {
                        continue;
                    }
                    if (st.targetRefs.isEmpty() && ForcedWinProbe.targetsAnything(sa)) {
                        continue;
                    }
                    if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false) || !sa.isTargetNumberValid()) {
                        continue;
                    }
                    return sa;
                }
                return null;
            }
            SpellAbility sa = ForcedWinProbe.locate(host, me, st.desc, st.costKey, st.kind == AbilKind.ACTIVATE);
            if (sa == null) {
                return null;
            }
            sa = sa.copy(me);
            sa.setActivatingPlayer(me);
            if (st.targetVariant != null && !ForcedWinProbe.setPlayerTargets(sa, me, st.targetVariant)) {
                return null;
            }
            if (st.phyrexianLife && sa.hasParam("AIPhyrexianPayment")) {
                sa.getMapParams().remove("AIPhyrexianPayment");
            }
            if (st.forceLife) {
                sa.getMapParams().put(forge.ai.ComputerUtilMana.PHYREXIAN_LIFE_PARAM, "true"); // kill-line-1106
            }
            directAnyMana(sa, st);
            if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) {
                return null;
            }
            return sa;
        }

        /** The first coloured choice of a step's any-colour mana, or -1. */
        static int firstColour(List<Integer> cols) {
            for (int c : cols) {
                if (c >= 0 && c < 5) {
                    return c;
                }
            }
            return -1;
        }

        /**
         * solver-gaps-1015: make a step's any-colour mana (Manamorphose, a ritual of any colour) come out in the colours the
         * line spends it on, through the mana part's express choice (as Forge AI's own payment does).
         */
        static void directAnyMana(SpellAbility sa, Step st) {
            if (st.anyColors.isEmpty()) {
                return;
            }
            final int first = firstColour(st.anyColors);
            if (first < 0) {
                return;
            }
            final StringBuilder sb = new StringBuilder();
            for (int c : st.anyColors) {
                sb.append(sb.length() == 0 ? "" : " ").append(ForcedWinModel.SHORT[c >= 0 && c < 5 ? c : first]);
            }
            for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
                if (l.getManaPart() != null) {
                    l.getManaPart().setExpressChoice(sb.toString());
                }
            }
        }

        /** A pick of a resolving step: its pile / tutor / put-back answer among the offered cards. */
        Card pick(SpellAbility source, CardCollection choices) {
            if (source == null || source.getHostCard() == null) {
                return null;
            }
            if (picks.forced != null) {
                final Card forced = picks.pick(source.getActivatingPlayer(), List.of(ZoneType.Library), source, choices,
                        source.getActivatingPlayer());
                if (forced != null) {
                    return forced;
                }
            }
            final Step st = executed.get(source.getHostCard().getId());
            if (st == null) {
                return null;
            }
            final List<Integer> want = !st.pile.isEmpty() ? st.pile : !st.picks.isEmpty() ? st.picks : st.putBack;
            for (int id : want) {
                if (pilePicked.containsKey(id) && pilePicked.get(id) == System.identityHashCode(source)) {
                    continue;
                }
                for (Card c : choices) {
                    if (c.getId() == id) {
                        pilePicked.put(id, System.identityHashCode(source));
                        return c;
                    }
                }
            }
            if (putBackKeep && st.pile.isEmpty() && st.picks.isEmpty() && want.contains(-1)) {
                // next-turn-1107: the line put back cards it did not know; any card the rest of the line does not use will do
                final Set<Integer> keep = reserved();
                keep.addAll(want);
                for (Card c : choices) {
                    if (!keep.contains(c.getId())) {
                        putBackKept++;
                        return c;
                    }
                }
            }
            return null;
        }

        /** An order of a resolving step's cards going to the library: the line's order (first = top). */
        CardCollectionView order(CardCollectionView cards, ZoneType destination, SpellAbility source) {
            if (destination != ZoneType.Library || source == null || source.getHostCard() == null) {
                return null;
            }
            final Step st = executed.get(source.getHostCard().getId());
            if (st == null) {
                return null;
            }
            final List<Integer> want = !st.pile.isEmpty() ? st.pile : st.putBack;
            if (want.isEmpty()) {
                // a look at our own top cards (Ponder): keep them as they are
                final CardCollection keep = new CardCollection(cards);
                Collections.reverse(keep);
                return keep;
            }
            final List<Card> top = new ArrayList<>();
            for (int id : want) {
                for (Card c : cards) {
                    if (c.getId() == id) {
                        top.add(c);
                    }
                }
            }
            final List<Card> rest = new ArrayList<>();
            for (Card c : cards) {
                if (!top.contains(c)) {
                    rest.add(c);
                }
            }
            // Forge moves each card to the top in the returned order: the last one ends on top
            final CardCollection ordered = new CardCollection();
            for (int i = rest.size() - 1; i >= 0; i--) {
                ordered.add(rest.get(i));
            }
            for (int i = top.size() - 1; i >= 0; i--) {
                ordered.add(top.get(i));
            }
            return ordered;
        }
    }

    /** Our counterspell for the opponent's spell on top of the stack when it targets one of our spells, or null. */
    static SpellAbility protect(Game g, Player me) {
        if (g.getStack().isEmpty()) {
            return null;
        }
        final SpellAbility top = g.getStack().peekAbility();
        if (top == null || top.getActivatingPlayer() == me || !targetsOurStack(g, top, me)) {
            return null;
        }
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (sa.getApi() != ApiType.Counter || !sa.usesTargeting()) {
                    continue;
                }
                final SpellAbility cs = sa.copy(me);
                cs.setActivatingPlayer(me);
                if (!cs.canTargetSpellAbility(top)) {
                    continue;
                }
                cs.resetTargets();
                cs.getTargets().add(top);
                if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, me, false)) {
                    continue;
                }
                return cs;
            }
        }
        return null;
    }

    static boolean targetsOurStack(Game g, SpellAbility sa, Player me) {
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            if (!l.usesTargeting()) {
                continue;
            }
            for (SpellAbility t : l.getTargets().getTargetSpells()) {
                if (t.getActivatingPlayer() == me) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ play-out controllers

    /** Our seat in a play-out: the line, our counterspells against counters of it, Forge AI for the rest. */
    static final class LineAi extends ForcedWinEngine.GuardedAi {
        Follower f;
        /** kill-line-1106 (verifyReplan; null = off): a fresh line from this world once the line breaks on a later turn. */
        java.util.function.Function<Line, Line> replanner;
        int startTurn;
        String protectedBy;
        /** Part B rollouts: after the line our seat plays on as Forge AI (forced-win play-outs stop acting). */
        boolean continueAfter;
        /** unified-search-1106 ({@link Config#lineUncapped}). */
        boolean uncapped;

        List<SpellAbility> lineAction(SpellAbility sa) {
            return uncapped || f.uncapped ? List.of(sa) : capped(List.of(sa));
        }

        LineAi(Game g, Player p, forge.LobbyPlayer lp, Follower f) {
            super(g, p, lp);
            this.f = f;
            if (f.costLine) {
                // R3-4 (b): the line's recorded cost cards (a COST token of the step), else Forge AI's
                setCacHook(new forge.ai.CacHook() {
                    @Override
                    public CardCollectionView costCards(forge.ai.PlayerControllerAi c, SpellAbility sa, String part,
                            CardCollectionView valid, int amount, CardCollectionView def) {
                        if (c != LineAi.this || valid == null || valid.size() <= amount || amount < 1) {
                            return def; // review #154-1: the engine records no point here either
                        }
                        final String t = f.choices.next(ForcedWinChoices.COST);
                        if (t == null) {
                            return def;
                        }
                        final CardCollection r = ForcedWinChoices.applyCost(t, valid, amount);
                        if (r == null) {
                            f.choices.misses++;
                            return def;
                        }
                        f.choices.hits++;
                        return r;
                    }
                });
            }
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            final SpellAbility guard = protect(g, getPlayer());
            if (guard != null) {
                protectedBy = guard.getHostCard().getName();
                return capped(List.of(guard));
            }
            if (!f.done && !f.broken) {
                final Follower.Next n = f.next(g, getPlayer());
                if (DBG_FOLLOW) {
                    receipt("FW_FOLLOW t" + g.getPhaseHandler().getTurn() + " " + g.getPhaseHandler().getPhase() + " cursor="
                            + f.cursor + " rep=" + f.repIter + "/" + f.repPos + " action=" + n.action + " waiting=" + n.waiting
                            + " broken=" + n.broken + " stack=" + g.getStack().size() + " pool="
                            + getPlayer().getManaPool().totalMana());
                }
                if (n.action != null) {
                    return lineAction(n.action);
                }
                if (n.waiting) {
                    return null;
                }
                if (n.broken && replanner == null) {
                    return null;
                }
            }
            if (f.broken && replanner != null) {
                // kill-line-1106 (verifyReplan): wait for our next main phase, then once per world a fresh line
                if (g.getPhaseHandler().getTurn() > startTurn && g.getStack().isEmpty()
                        && g.getPhaseHandler().isPlayerTurn(getPlayer()) && g.getPhaseHandler().is(PhaseType.MAIN1)) {
                    final java.util.function.Function<Line, Line> rp = replanner;
                    replanner = null;
                    final Line fresh = rp.apply(f.line);
                    if (fresh != null) {
                        final boolean keep = f.putBackKeep, keepEnginePay = f.enginePay;
                        f = new Follower(fresh);
                        f.putBackKeep = keep; // next-turn-1107
                        if (keepEnginePay) {
                            f.enginePay = true; // ledger-sweep-1107 (row 60): the re-planned line's recorded payments too
                            f.payRecord = true;
                        }
                        final Follower.Next m = f.next(g, getPlayer());
                        return m.action != null ? capped(List.of(m.action)) : null;
                    }
                }
                return null;
            }
            if (f.done && continueAfter) {
                return super.chooseSpellAbilityToPlay();
            }
            if (f.done && !g.getStack().isEmpty()) {
                return null;
            }
            return f.done ? null : super.chooseSpellAbilityToPlay();
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional,
                Player decider) {
            if (f.cacLine && ForcedWinEngine.MoveAi.ownPick(getPlayer(), origin, sa, fetchList, decider)) {
                // labeller-v2 (b): the line's recorded pick
                final String t = f.choices.next(ForcedWinChoices.PICK);
                if (t != null) {
                    final Card[] r = ForcedWinChoices.applyPick(t, fetchList);
                    if (r != null && (r[0] != null || isOptional)) {
                        f.choices.hits++;
                        return r[0];
                    }
                    f.choices.misses++;
                }
            }
            final Card c = decider == getPlayer() ? f.pick(sa, fetchList) : null;
            if (hw == null) {
                return c != null ? c : super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                        selectPrompt, isOptional, decider);
            }
            // e1-q8-1101: a search of our library looks at all of it (not a peek) and picks by identity
            hw.inSearch = true;
            try {
                final Card got = c != null ? c : super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList,
                        delayedReveal, selectPrompt, isOptional, decider);
                hw.searchPick(origin, got);
                return got;
            } finally {
                hw.inSearch = false;
            }
        }

        /** e1-q8-1101 (libWatch only; null otherwise): the play-out's hidden-library watch, told of reveals. */
        HiddenWatch hw;

        @Override
        public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
            if (hw != null) {
                hw.peekCards(cards, zone, owner);
            }
            super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        }

        @Override
        public void reveal(List<forge.game.card.CardView> cards, ZoneType zone, forge.game.player.PlayerView owner,
                String messagePrefix, boolean addSuffix) {
            if (hw != null) {
                hw.peekViews(cards, zone, owner);
            }
            super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        }

        @Override
        public void tempShowCards(Iterable<Card> cards) {
            if (hw != null) {
                hw.peekOwnLibrary(cards); // review #161 S1-1
            }
            super.tempShowCards(cards);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                SpellAbility source) {
            if (hw != null && destinationZone == ZoneType.Library) {
                hw.peekOwnLibrary(cards); // review #161 S1-1: Ponder's / Top's reorder shows the cards
            }
            if (f.cacLine && destinationZone == ZoneType.Library && cards != null && cards.size() >= 2
                    && cards.stream().allMatch(c -> c.getOwner() == getPlayer())) {
                final String t = f.choices.next(ForcedWinChoices.ORDER);
                if (t != null) {
                    final CardCollectionView r = ForcedWinChoices.applyOrder(t, cards);
                    if (r != null) {
                        f.choices.hits++;
                        return r;
                    }
                    f.choices.misses++;
                }
            }
            final CardCollectionView o = f.order(cards, destinationZone, source);
            return o != null ? o : super.orderMoveToZoneList(cards, destinationZone, source);
        }

        @Override
        public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
                Card cardToShow, Map<String, Object> params) {
            if (message != null && message.toLowerCase().contains("shuffle")) {
                if (f.cacLine) {
                    final String t = f.choices.next(ForcedWinChoices.SHUF);
                    if (t != null) {
                        f.choices.hits++;
                        return ForcedWinChoices.applyShuf(t);
                    }
                }
                return false;
            }
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
            if (hw != null) {
                hw.peekOwnLibrary(topN); // review #161 S1-1: a scry looks at the cards
            }
            final ImmutablePair<CardCollection, CardCollection> r = recordedArr(ForcedWinChoices.SCRY, topN);
            return r != null ? r : ImmutablePair.of(new CardCollection(topN), new CardCollection());
        }

        /** labeller-v2 (b): the line's recorded scry / surveil answer, or null (then all on top, as v1). */
        private ImmutablePair<CardCollection, CardCollection> recordedArr(String kind, CardCollection topN) {
            if (!f.cacLine || topN == null || topN.isEmpty()) {
                return null;
            }
            final String t = f.choices.next(kind);
            if (t == null) {
                return null;
            }
            final ImmutablePair<CardCollection, CardCollection> r = ForcedWinChoices.applyArr(t, topN);
            if (r == null) {
                f.choices.misses++;
                return null;
            }
            f.choices.hits++;
            return r;
        }

        @Override
        public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
            super.orderAndPlaySimultaneousSa(f.triggers.forced == null ? abilities : f.triggers.apply(getGame(), getPlayer(), abilities));
        }

        @Override
        public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kind, Boolean defaultVal) {
            if (kind == BinaryChoiceType.TapOrUntap && defaultVal != null) {
                return defaultVal;
            }
            return super.chooseBinary(sa, question, kind, defaultVal);
        }

        @Override
        public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
            if (f.triggers.confirms(wrapper)) {
                return true;
            }
            if (f.costLine && wrapper != null && wrapper.getHostCard() != null) {
                // R3-4 (b): the line's recorded answer to our optional trigger
                final String t = f.choices.next(ForcedWinChoices.TRIG);
                if (t != null && t.startsWith(ForcedWinChoices.TRIG + ":" + ForcedWinChoices.trigHost(wrapper) + "=")) {
                    f.choices.hits++;
                    return t.endsWith("=y");
                }
                if (t != null) {
                    f.choices.misses++;
                }
            }
            return super.confirmTrigger(wrapper);
        }

        /** The line's alpha strike (if the rules refuse it, the guard hands the declaration to Forge AI). */
        @Override
        void declareFirst(Player attacker, forge.game.combat.Combat combat) {
            if (f.attackPending && !f.done) {
                alphaStrike(attacker, combat);
                f.attackPending = false;
                f.cursor++;
                return;
            }
            super.declareFirst(attacker, combat);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
            if (hw != null) {
                hw.peekOwnLibrary(topN); // review #161 S1-1
            }
            final ImmutablePair<CardCollection, CardCollection> r = recordedArr(ForcedWinChoices.SURV, topN);
            return r != null ? r : ImmutablePair.of(new CardCollection(topN), new CardCollection());
        }

        @Override
        public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min,
                int max, CardCollectionView visibleToChooser) {
            if (p == getPlayer() && min == max && min >= 1 && validCards != null && validCards.size() > min) {
                final String t = f.choices.next(ForcedWinChoices.DISC);
                if (t != null) {
                    final CardCollection r = ForcedWinChoices.applyDisc(t, validCards, min, max);
                    if (r != null) {
                        f.choices.hits++;
                        return r;
                    }
                    f.choices.misses++;
                }
            }
            final CardCollection ordinary = super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
            return avoid(ordinary, validCards, f.reserved());
        }
    }

    /** Attack with every creature that can attack the opponent. */
    static void alphaStrike(Player attacker, forge.game.combat.Combat combat) {
        for (Card c : attacker.getCreaturesInPlay()) {
            for (Player o : attacker.getOpponents()) {
                if (forge.game.combat.CombatUtil.canAttack(c, o)) {
                    combat.addAttacker(c, o);
                    break;
                }
            }
        }
    }

    /** The combat of the live seat's last alpha strike (solver-gaps-1015: the rules may refuse it and ask again). */
    private forge.game.combat.Combat alphaCombat;

    /** The live seat's attack: the line's alpha strike when it is waiting for one; null = not ours. */
    public boolean answerAttack(Player attacker, forge.game.combat.Combat combat) {
        if (combat == alphaCombat) {
            // solver-gaps-1015: asked again in the same combat = the rules refused the alpha strike (an attack restriction
            // or requirement); clear it and let the seat declare natively, or the rules ask for ever
            alphaCombat = null;
            combat.clearAttackers();
            receipt("FORCED_WIN attack refused turn=" + me.getGame().getPhaseHandler().getTurn() + " -> native declaration");
            return false;
        }
        if (active == null || active.done || !active.attackPending || attacker != me) {
            return false;
        }
        alphaStrike(attacker, combat);
        alphaCombat = combat;
        active.attackPending = false;
        active.cursor++;
        receipt("FORCED_WIN attack turn=" + me.getGame().getPhaseHandler().getTurn() + " attackers=" + combat.getAttackers().size());
        return true;
    }

    /** Swap reserved cards out of a discard choice for unreserved valid ones (size kept). */
    static CardCollection avoid(CardCollection ordinary, CardCollectionView valid, Set<Integer> reserved) {
        if (ordinary == null || reserved.isEmpty()) {
            return ordinary;
        }
        final CardCollection out = new CardCollection();
        for (Card c : ordinary) {
            if (!reserved.contains(c.getId())) {
                out.add(c);
                continue;
            }
            Card alt = null;
            for (Card v : valid) {
                if (!reserved.contains(v.getId()) && !ordinary.contains(v) && !out.contains(v)) {
                    alt = v;
                    break;
                }
            }
            out.add(alt != null ? alt : c);
        }
        return out;
    }

    /** The opponent in a play-out: Forge AI, plus (when asked) a counter of our spell number {@code counterAt}. */
    static final class AdversaryAi extends ForcedWinEngine.GuardedAi {
        final Player victim;
        final int counterAt;
        final Set<Integer> seenSpells = new HashSet<>();
        int seen;
        String countered;

        AdversaryAi(Game g, Player p, forge.LobbyPlayer lp, Player victim, int counterAt) {
            super(g, p, lp);
            this.victim = victim;
            this.counterAt = counterAt;
        }

        /** e1-q8-1101 (libWatch only; null otherwise): the play-out's hidden-library watch, told of reveals. */
        HiddenWatch hw;

        @Override
        public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
            if (hw != null) {
                hw.peekCards(cards, zone, owner);
            }
            super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        }

        @Override
        public void reveal(List<forge.game.card.CardView> cards, ZoneType zone, forge.game.player.PlayerView owner,
                String messagePrefix, boolean addSuffix) {
            if (hw != null) {
                hw.peekViews(cards, zone, owner);
            }
            super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        }

        @Override
        public void tempShowCards(Iterable<Card> cards) {
            if (hw != null) {
                hw.peekOwnLibrary(cards); // review #161 S1-1
            }
            super.tempShowCards(cards);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                SpellAbility source) {
            if (hw != null && destinationZone == ZoneType.Library) {
                hw.peekOwnLibrary(cards); // review #161 S1-1: they order our library cards (fateseal-like)
            }
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
            if (hw != null) {
                hw.peekOwnLibrary(topN);
            }
            return super.arrangeForScry(topN);
        }

        @Override
        public List<forge.game.spellability.AbilitySub> chooseModeForAbility(SpellAbility sa,
                List<forge.game.spellability.AbilitySub> possible, int min, int num, boolean allowRepeat) {
            final List<forge.game.spellability.AbilitySub> f = OppMin.forcedModes(sa, possible);
            return f != null ? f : super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }

        static boolean holdsCounter(Player p) {
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                for (SpellAbility sa : c.getSpellAbilities()) {
                    if (sa.getApi() == ApiType.Counter) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** labeller-v2 (d): the shared MIN's driver for this branch (null = the one-counter adversary). */
        OppMin.Driver driver;

        /** opp-response-1108: Forge AI declares; a driver with block points may replace the declaration (Driver#blocks). */
        @Override
        public void declareBlockers(Player defender, forge.game.combat.Combat combat) {
            super.declareBlockers(defender, combat);
            if (driver != null && driver.blocks) {
                driver.blocks(getGame(), combat, defender);
                if (driver.answered != null) countered = driver.answered;
            }
        }
        /** Tests only: pass instead of Forge AI's own answer while one of our objects is on the stack. */
        boolean passive;

        private List<SpellAbility> fallback(final Game g) {
            if (passive && pointKey(g) != null) {
                return null;
            }
            return super.chooseSpellAbilityToPlay();
        }

        /**
         * labeller-v2 (d): the response point at this priority, or null: one of our spells, activated abilities or
         * triggers on top of the stack (review S2-1: triggers too, Stifle on an ETB, storm or Doomsday trigger), or our
         * declare-attackers step with attackers declared (Swords on the attacker, a flash blocker).
         */
        private String pointKey(final Game g) {
            return OppMin.pointKey(g, victim);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            if (driver != null) {
                final List<SpellAbility> r = driver.choose(g, () -> fallback(g));
                if (driver.answered != null) countered = driver.answered;
                return r == null ? null : capped(r);
            }
            if (!g.getStack().isEmpty()) {
                final SpellAbilityStackInstance si = g.getStack().peek();
                final SpellAbility top = si.getSpellAbility();
                if (top != null && top.getActivatingPlayer() == victim && top.isSpell() && seenSpells.add(si.getId())) {
                    final int index = seen++;
                    if (index == counterAt) {
                        final SpellAbility cs = counterFor(top);
                        if (cs != null) {
                            countered = top.getHostCard().getName() + " with " + cs.getHostCard().getName();
                            return capped(List.of(cs));
                        }
                    }
                }
            }
            return fallback(g);
        }

        /**
         * labeller-v2 (d): the whole response set at a point, in a fixed order (counters incl. Stifle effects, removal,
         * discard, flash creatures, other), one per (category, name, target class), each castable or activatable now
         * and payable. Sources (review S2-1): their hand, every permanent (tapped too: sacrifice and pay-life abilities),
         * graveyard and exile (flashback, escape). Targets: our top stack object, our permanents by name, us, and their
         * own permanents by name and themselves (lifegain, protection, regeneration).
         */
        List<SpellAbility> responses(final SpellAbility top) {
            return OppMin.responses(getGame(), getPlayer(), victim, top);
        }

        static int category(final SpellAbility sa) {
            final ApiType api = sa.getApi();
            if (api == ApiType.Counter) return 0;
            if (api == ApiType.Destroy || api == ApiType.DealDamage || api == ApiType.ChangeZone || api == ApiType.Sacrifice
                    || api == ApiType.Pump && sa.hasParam("NumDef") || api == ApiType.Tap) return 1;
            if (api == ApiType.Discard) return 2;
            if (sa.isSpell() && sa.getHostCard().isCreature()) return 3;
            return 4;
        }

        private SpellAbility counterFor(SpellAbility top) {
            final Player p = getPlayer();
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                    if (sa.getApi() != ApiType.Counter || !sa.usesTargeting()) {
                        continue;
                    }
                    final SpellAbility cs = sa.copy(p);
                    cs.setActivatingPlayer(p);
                    if (!cs.canTargetSpellAbility(top)) {
                        continue;
                    }
                    cs.resetTargets();
                    cs.getTargets().add(top);
                    if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, p, false)) {
                        continue;
                    }
                    return cs;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ diagnostics

    public String summary() {
        return "decisions=" + decisions + " gated=" + gated + " evaluated=" + evaluated + " taken=" + taken + " likelyRuns="
                + likelyRuns + " likelyTaken=" + likelyTaken + " replans="
                + replans + " plannerMs=" + plannerNanos / 1_000_000 + " engineMs=" + engineNanos / 1_000_000
                + (plannerExhausted == 0 ? "" : " plannerExhausted=" + plannerExhausted)
                + (planFailEngineTakes == 0 ? "" : " planFailEngineTakes=" + planFailEngineTakes)
                + (verifyReplans == 0 ? "" : " verifyReplans=" + verifyReplans + "/" + verifyReplanLines)
                + " copies=" + copies + " " + reader.probeStats.summary()
                + (engineReruns == 0 ? "" : " engineReruns=" + engineReruns) // choice-search-1105 (only when used)
                + (loopMarginals + loopVariantCuts == 0 ? "" : " loopMarginals=" + loopMarginals + " loopVariants="
                        + loopVariants + " loopVariantCuts=" + loopVariantCuts); // choice-search-1105 (only when used)
    }

    /** Effects of a line's steps (for reports). */
    static String describe(Line l) {
        final StringBuilder sb = new StringBuilder();
        for (Step s : l.steps) {
            sb.append(s).append(' ');
            if (!s.effects.isEmpty()) {
                sb.append(s.effects).append(' ');
            }
        }
        return sb.toString().trim();
    }

    static boolean drawsAnything(List<Eff> effs) {
        for (Eff e : effs) {
            if (e.kind == EffKind.DRAW) {
                return true;
            }
        }
        return false;
    }

    static ACard first(List<ACard> xs) {
        return xs.isEmpty() ? null : xs.get(0);
    }

    static PlayerControllerAi ai(Player p) {
        return p.getController() instanceof PlayerControllerAi a ? a : null;
    }
}
