package forge.ai.simulation;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.AiCache;
import forge.ai.AiFixes;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CounterType;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-step look-ahead over belief-sampled worlds, run INSIDE Forge (the mtgx PIMC recipe).
 *
 * <p>At a contested priority decision of the searching seat, take Forge AI's own answer plus
 * "pass" plus the next legal candidates in Forge's own ordering (breadth B in all). For each
 * of K worlds -- the live position copied with {@link GameCopier}, the opponent's unseen
 * cards (hand + library) and our own library order re-drawn from a seeded belief -- apply each
 * candidate in its own copy and let Forge's AI play BOTH seats until the start of turn
 * {@code now + horizonTurns}. Score with Forge's static {@link GameStateEvaluator} (a result
 * is +/-{@link #TERMINAL}), average over worlds (expected value), play the argmax. Ties go to
 * Forge's own answer.
 *
 * <p>Every world uses common random numbers across candidates (the same sampled world and the
 * same play-out stream), so the comparison between candidates is paired. Every random draw a
 * copy makes comes from a per-thread stream ({@link MyRandom#setThreadRandom}) and every AI
 * cache it touches is per-thread ({@link AiCache#openScope}); the live game's RNG and caches are
 * never advanced by the search. The decision is a pure function of (seed, decision index,
 * position), independent of thread scheduling.
 *
 * <p>Known fidelity limits of the copy (inherited from GameCopier; see the lane's plan.md):
 * the stack is not copied (the search is skipped when the stack is not empty), end-of-turn
 * "until" commands and delayed triggers are not copied, per-turn AI memory is fresh in copies.
 */
public final class LookaheadSearch {
    /**
     * live-fixes-1107: the house look-ahead keys' default ({@code -Dforge.house.<key>} wins, else forge.liveFixes). Read once;
     * {@link Config} and RlSearch.Config start from these, so a spec key still sets them per seat.
     */
    public static final boolean LIVE_BELIEF_STATICS = forge.game.EngineFixes.liveFlag("forge.house.beliefStatics");
    public static final boolean LIVE_BELIEF_REMEMBERED_HAND = forge.game.EngineFixes.liveFlag("forge.house.beliefRememberedHand");
    public static final boolean LIVE_STACK_ABILITY_COPIES = forge.game.EngineFixes.liveFlag("forge.house.stackAbilityCopies");
    public static final boolean LIVE_COPY_BACK_FACE = forge.game.EngineFixes.liveFlag("forge.house.copyBackFace");
    public static final boolean LIVE_OFF_BATTLEFIELD_LIMITS = forge.game.EngineFixes.liveFlag("forge.house.offBattlefieldLimits");
    public static final boolean LIVE_COPY_TURN_LIMITS = forge.game.EngineFixes.liveFlag("forge.house.copyTurnLimits");
    public static final boolean LIVE_BELIEF_PLAYER_STATICS = forge.game.EngineFixes.liveFlag("forge.house.beliefPlayerStatics");

    public static final double TERMINAL = 100000.0;
    private static final AtomicInteger FAILURE_TRACES = new AtomicInteger();

    public static final class Config {
        public int worlds = 1;
        public int breadth = 4;
        /**
         * endstep-rows-1103 (N12, live; 0 = off, the default, and then nothing changes): at a priority outside our own
         * main phases (their turn, our upkeep/combat/end, a response), every distinct castable spell or ability past the
         * breadth-B cut is also a candidate, up to this many candidates in all, so a flash creature or an instant tutor at
         * their end step is never cut by Forge's own ordering.
         */
        public int offTurnBreadth = 0;
        public int horizonTurns = 2;
        public int maxSteps = 5000;
        public long seed = 0L;
        /** Search and record, but always play Forge's own answer. */
        public boolean shadow = false;
        public boolean resample = true;
        /** Play-out worker threads; 0 = auto (min(worlds x breadth, available processors, 8)). Decisions do not depend on it. */
        public int threads = 0;
        /**
         * Identical-position dedup (lane forge-search-speed-0927): the candidates of one world are played in order, and a
         * play-out that reaches a position (exact {@link PlayoutKeys#stateKey}) an earlier candidate of the same world
         * passed through takes that candidate's value instead of playing on.
         */
        public boolean dedup = false;
        /** Dedup check: compute the keys and record would-be dedup hits, but play every play-out out and compare values. */
        public boolean dedupVerify = false;
        /** Dedup: take state keys only after the first this-many main-loop steps of a play-out (0 = every step). */
        public int dedupSteps = 0;
        /**
         * Reuse across decisions (owner's world filter): worlds whose kept play-out of the move actually played met the
         * live position carry over (hidden cards and play-out stream); where Forge AI chose there what Forge's answer is
         * now, within the same turn, the kept play-out's value stands in for candidate 0. Changes decisions (the worlds
         * are the previous search's survivors, not fresh draws).
         */
        public boolean reuse = false;
        /** Reuse check: also play candidate 0 fresh in every reused world and count decisions the reused values change. */
        public boolean reuseVerify = false;
        /** Reuse check: also run the fresh A8S search (fresh worlds) at every decision and count decisions that differ. */
        public boolean reuseShadowFresh = false;
        /** A departure must beat Forge's answer's EV by more than this. */
        public double margin = 0.0;
        /**
         * Departure confidence (lane forge-ai-misplays-0928; 0 = off, the default: decisions unchanged). A candidate may
         * replace Forge's answer only if its mean paired difference to Forge's answer over the K worlds (common random
         * numbers) exceeds departZ standard errors of that difference. Keeps the prior (Forge's answer) when the
         * play-outs cannot tell the candidates apart -- one divergent or terminal world must not decide.
         */
        public double departZ = 0.0;
        /**
         * sdcc-c1-priogf-1117 (C1; 0 = off, the default, and then nothing of it runs: no copy, no RNG draw): at our own
         * main phase with an empty stack, a goldfish tie-break among the given candidates whose mean leaf value is within
         * {@link #prioGfMargin} of the best ({@link #prioGfRule}); the goldfish plans this many of our turns. Only while our
         * own-turn count is at most {@link #prioGfTurns} (0 = any turn).
         */
        public int prioGf = 0;
        public double prioGfMargin = 0.01;
        public int prioGfTurns = 4;
        /**
         * sdcc-c1-priogf-1117 (C1 hold; false = off, the default, and then nothing changes): inside the prioGf rule, among
         * candidates that tie on the goldfish score, prefer one that spends no card (pass, or our hand size plus our
         * permanents not lower on its copy after it was applied); and the pass candidate is searched at the rule's
         * decisions even when the breadth cut it ({@link #prioGfSelect(double[], boolean[], double[], int, boolean[])}).
         */
        public boolean prioGfHold = false;
        /**
         * unified-search-1106 (off by default; proof decisions only): one anytime comparison. Each candidate is compared with
         * the default on the worlds both finished, by the departZ test on those paired differences. A failed or unfinished
         * world drops that world, not the candidate or the decision; a default with no finished world is estimated by
         * the root's value. With every world finished this chooses exactly as the plain gate.
         */
        public boolean anytime = false;
        /**
         * Target variants (lane forge-ai-misplays-0928; 0 = off, the default: candidates unchanged). Up to this many
         * extra candidates per decision: a single-target spell or ability among the candidates played with a target
         * other than the one Forge AI would choose (or with none, when "up to one"), so the play-outs, not Forge AI's
         * targeting heuristic, decide e.g. burn to the face vs a creature. Taken in candidate order (Forge's answer
         * first); per ability: opponents, opponents' cards, "no target", own player, own cards, in game order.
         */
        public int targetVariants = 0;
        /**
         * Dead-ETB guard (lane misplay-portablehole-1003; off|shadow|on in the aiFixes0928 style, OFF by default:
         * candidates and decisions unchanged). A non-creature permanent spell whose every enters-the-battlefield trigger
         * needs a target and has no legal one now ({@link #deadEtbPermanent}; Portable Hole against a board with no
         * opponent's nonland permanent of mana value 2 or less) is not a departure candidate: the static evaluator
         * scores any such permanent at 50 + 30 * MV against 5 for the card in hand, a constant gain in every world that
         * the departZ gate cannot tell from a real one. ON drops it from the candidates (its breadth slot goes to the
         * next legal candidate, as for a duplicate); SHADOW keeps every candidate and decision and counts the
         * decisions where it would have dropped one, and the departures to one. Forge's own answer is never dropped.
         */
        public AiFixes.Mode deadEtb = AiFixes.Mode.OFF;
        /**
         * Zero-X guard (lane misplay-portablehole-1003, owner report 2026-10-04T06:10Z; off|shadow|on, OFF by default:
         * candidates and decisions unchanged). A spell with {X} in its mana cost whose largest payable X is 0 now and that
         * Forge's own AI would not cast ({@link #zeroXSpell}; Pest Infestation with two mana) is not a departure
         * candidate. Casting it does nothing but spend mana and a card; the search preferred it because the spent mana
         * changes Forge AI's own follow-up in the play-outs (Thraben Inspector + Clue, the token scored at the flat 50,
         * instead of Reclamation Sage). ON drops it as deadEtb drops a dead-ETB permanent; SHADOW counts; Forge's own
         * answer is never dropped.
         */
        public AiFixes.Mode zeroX = AiFixes.Mode.OFF;
        /**
         * Crew no-op guard (lane misplays-1005, owner report 2026-10-05T05:15Z; off|shadow|on, OFF by default: candidates
         * and decisions unchanged). A Crew activation of a Vehicle at a step where the Vehicle can no longer attack or
         * block this turn ({@link #crewNoop}: its own turn at or after declare attackers, the opponent's turn at or after
         * declare blockers, no extra combat pending, no crew/tap trigger in play) is not a departure candidate. It does
         * nothing until it ends at cleanup, yet the play-outs scored it: Smuggler's Copter crewed twice in the owner's
         * second main phase, carried by a few worlds where the opponent seat's Forge AI aimed Dack Fayden at the crewed
         * creature instead of Esika's Chariot, or where the extra action changed the play-out's random path (median
         * paired difference 0). ON drops it as deadEtb drops a dead-ETB permanent; SHADOW counts; Forge's own answer is
         * never dropped.
         */
        public AiFixes.Mode crewNoop = AiFixes.Mode.OFF;
        /**
         * Dead alternative-cost guard (lane evoke-empty-board-1111, owner report 2026-11-12T03:09Z; off|shadow|on, OFF by
         * default: candidates and decisions unchanged). A spell whose cost spends cards beyond itself (evoke, whose body is
         * sacrificed as it enters; a cost that exiles or discards a card from hand) is not a departure candidate when the
         * effect those cards pay for buys nothing on this board ({@link #deadAltCost}: Forge's AI declines every such effect
         * as an optional trigger). The value leaf scored an evoked damage elemental above a land on turn 1 against an empty
         * board in all 8 worlds (+0.06 mean), two cards spent for nothing. ON drops it as deadEtb drops a dead-ETB
         * permanent; SHADOW counts; Forge's own answer is never dropped.
         */
        public AiFixes.Mode deadAltCost = AiFixes.Mode.OFF;
        /**
         * Dead pump guard (lane temp-pump-wrong-turn-1117, report 2026-11-18T01:55Z; off|shadow|on, OFF by default:
         * candidates and decisions unchanged). A spell or activated ability whose whole resolution is a change to power until
         * end of turn is not a departure candidate when no creature it changes can deal combat damage before this turn's
         * cleanup and nothing can react to paying its cost or to playing it ({@link #deadPump}). On its own draw step, with
         * no creatures, the policy seat discarded two lands to give the opponent's Oona's Prowler -2/-0: the play-outs end
         * after the effect has expired and the value leaf priced the discarded excess lands at about 0, so departZ took it
         * (+0.011 and +0.014 mean). ON drops it as deadEtb drops a dead-ETB permanent; SHADOW counts; Forge's own answer
         * and the policy's default are never dropped.
         */
        public AiFixes.Mode deadPump = AiFixes.Mode.OFF;
        /**
         * Dead turn-face-up guard (lane turn-face-up-timing-1118, report 2026-11-18T14:33Z; off|shadow|on, OFF by default:
         * candidates and decisions unchanged). Turning one of the seat's face-down permanents face up is not a departure
         * candidate when its only payoff is a "when this is turned face up" trigger that has no legal target now, nothing
         * else on the card needs it face up now, the seat gets priority again before the body could matter in combat, and
         * paying now gains nothing over paying later ({@link #deadFaceUpWhy}). At the end of its own combat the policy seat
         * turned Stratus Dancer face up with an empty stack (+0.031 mean, 4 of 8 worlds better): its counter trigger had no
         * target, and it gave up countering a spell on the opponent's turn with the same mana. ON drops it as deadEtb drops a
         * dead-ETB permanent; SHADOW counts; Forge's own answer and the policy's default are never dropped.
         */
        public AiFixes.Mode deadFaceUp = AiFixes.Mode.OFF;
        /**
         * Own-harm guard (lane line-own-target-1119, owner reports 2026-11-19T01-05-37 and 01-14-57; off|shadow|on, OFF by
         * default: nothing runs, candidates and decisions unchanged). A candidate whose harmful effect (damage, destroy,
         * -N/-N with no power gain, removal from the battlefield that does not come back) can land only on permanents the
         * seat controls with no payoff (a trigger of ours, or the ability's own other benefit for us), or that is a destroy
         * doing nothing at all (only indestructible targets, no other effect), is refused ({@link LineOwnTarget#refuseOpen}:
         * the rule the live line follower reads under {@link ForcedWinSolver.Config#lineOwnTarget}; every target open, a
         * modal spell refused only when every mode it could take is; "ours" by controller; player and stack targets never
         * refused; when unsure, kept; read-only). Review 3: a refusal DEMOTES, it never vetoes. A refused candidate stays
         * among the candidates and is played out as usual; it is played only if, against the best candidate the rule did
         * not refuse, it passes the departZ test and its value is higher by {@link #OWN_HARM_FLOOR} ({@link
         * LookaheadSearch#ownHarmBetter}). The policy seat ({@code forge.bench.rl.RlSearch}) marks its refused given
         * entries ({@link LookaheadSearch#ownHarmDemote}); its own choice refused, the best entry it did not refuse takes
         * its place unless the refused one is better so. It still breaks a policy plan before a refused step and drops a
         * sampled plan that would play one. The K8 seat demotes refused departures the same way, Forge's own answer
         * staying the baseline (it is counted, never demoted). SHADOW computes the demotion and logs it, the decision
         * unchanged.
         */
        public AiFixes.Mode ownHarm = AiFixes.Mode.OFF;
        /**
         * ownHarm's floor (review 3, not a spec key): a refused candidate replaces the best one the rule did not refuse only
         * if its value is higher by at least this much (absolute, on the win-probability scale of the value leaf), on top
         * of the departZ test. In a saturated position (value near 1) no real payoff can show a 0.01 gain, and none
         * matters; in a contested one a real payoff the play-outs see (lifelink at 2 life, a return, a death trigger) shows
         * a gap far above it. So the demotion only overrides noise. Review 4: it acts only when every value it compares is
         * on that scale (the policy seat's value leaf, or K8's model leaf, with no fallback); over Forge's static score (a
         * static leaf, K8 without a model, or any leaf fallback) ownHarm takes no action in the decision
         * ({@link #OWN_HARM_NO_SCALE}).
         */
        public static final double OWN_HARM_FLOOR = 0.01;
        /**
         * Review 4: the reason recorded when ownHarm takes no action because the decision's values are not all on the
         * win-probability scale (a static leaf or a leaf fallback): key-off behavior, no demotion and no veto.
         */
        public static final String OWN_HARM_NO_SCALE = "ownHarm-noverdict-scale";
        /**
         * The ownHarm key's value: exactly off|shadow|on (the host validator takes no aliases), else an error naming
         * {@code k}.
         */
        public static AiFixes.Mode ownHarmKey(final String k, final String v) {
            final String t = v == null ? "" : v.trim();
            if (!"off".equals(t) && !"shadow".equals(t) && !"on".equals(t)) {
                throw new IllegalArgumentException(k + ": expected off|shadow|on, got '" + v + "'");
            }
            return AiFixes.Mode.parse(t);
        }
        /**
         * Lethal-block guard (lane block-vs-open-pump-1118, report 2026-11-18T05:34Z; off|shadow|on, OFF by default: values
         * and decisions unchanged). In the block search ({@link #decideBlocks}), a candidate declaration whose worst case of
         * this combat's damage to the searching seat is lethal ({@link #lethalBlocks}: what the attackers' controller can add
         * with the mana it has, through abilities the seat can see) scores as a lost play-out when another candidate's worst
         * case is not lethal. On turn 22 at 8 life the search departed from the policy's double block to no blocks (0.322 vs
         * 0.088, 8/8 worlds): no play-out had the opponent activate Polukranos's Monstrosity X = 3 with its seven open lands,
         * which made it 8/8, unblocked, exactly lethal. ON re-scores; SHADOW counts and flags and decides as OFF; with every
         * candidate lethal, or none, the values are unchanged.
         */
        public AiFixes.Mode lethalBlock = AiFixes.Mode.OFF;
        /**
         * Dud-cast guard (lane live-ai-quality-1030, owner 2026-10-30; off|shadow|on, OFF by default: nothing runs and every
         * decision is unchanged). Before the live seat commits a non-permanent spell or a non-mana activated ability (Forge's
         * answer or a departure), {@link DudCast} resolves it in {@link #dudWorlds} belief-redrawn copies; when no copy's state
         * changes apart from the card and mana spent, ON vetoes it and asks Forge AI again; SHADOW counts and logs.
         */
        public AiFixes.Mode dudCast = AiFixes.Mode.OFF;
        /** Dud-cast guard: copies per check (a dud must do nothing in every one). */
        public int dudWorlds = 2;
        /**
         * Until-end-of-turn copy fidelity (lane misplays-1005; off|shadow|on, OFF by default: copies and decisions
         * unchanged). The copier did not carry the live game's pending "until end of turn" / "until end of combat"
         * commands, so a copy made after a live Crew (or pump, or animation) kept the change for good: a crewed Vehicle
         * stayed an artifact creature through the whole play-out. With reuse, a world whose candidate-0 value came from
         * the previous search's kept play-out (where the change ended at cleanup) was compared against fresh copies
         * (where it never ends), a constant offset in every world: decision 86 of session 486d4d1b departed to a second
         * Crew at a flat +61 (Esika's Chariot scored as a 4/4 creature, 231, instead of 170). ON carries every command
         * {@link GameCopier#setCopyUntilEot} can remap (cards, players, game, primitives); SHADOW only counts, per
         * searched decision, the live commands pending and how many would be carried.
         */
        public AiFixes.Mode copyEot = AiFixes.Mode.OFF;
        /**
         * Copy fidelity (lane copy-fidelity-1010; 0 = off, the default: copies and decisions unchanged). 1 = every copy
         * the search makes carries the live game's per-turn and history state the copier otherwise drops
         * ({@link GameCopier#setCopyFidelity}): "attacked this turn" (boast), activation counts, zone-entry turns
         * (foretell, plot), mulligans, spells cast this turn (storm), cards put into zones this turn, and so on. Without it a
         * searched candidate that is legal only through that state (Usher of the Fallen's boast after it attacked) is not
         * in the copy's menu and its play-outs fail.
         * 2 ("repair", given-candidate searches only: S-c / S-t) = copies as at 0, and a decision where a searched
         * candidate is missing from those copies (a play-out failed as candidate_not_in_menu, candidate_not_in_copy or
         * copy_error) is searched again, every candidate in every world (the same seeds), on level-1 copies; every other
         * decision is exactly level 0's. In the Forge-play-out search (K8) 2 is 0.
         */
        public int copyFidelity = 0;

        /** A copy-fidelity level from a K8 spec: 0 or 1, else IllegalArgumentException. */
        public static int copyFidelityLevel(int level) {
            if (level != 0 && level != 1) {
                throw new IllegalArgumentException("copyFidelity must be 0 or 1, not " + level);
            }
            return level;
        }
        /**
         * Median departure gate (lane misplays-1005; off|shadow|on, OFF by default: decisions unchanged). A candidate may
         * replace Forge's answer only if the LOWER MEDIAN of its paired differences to Forge's answer over the K worlds is
         * above zero, i.e. it is strictly better in more than half the worlds -- on top of departZ. The departZ test assumes roughly
         * normal differences; a no-op or near-no-op whose play-outs are mostly identical to Forge's line, with a few worlds
         * where the opponent seat's Forge AI took a different path, gives zeros plus a few large values and passes it
         * (session 486d4d1b: Manamorphose [-10,575,-10,136,483,-10,-10,-10], Crew [249,0,0,249,0,0,0,178]). A statistical
         * rule over the same play-outs, not a game rule. SHADOW decides as OFF and counts the decisions ON would change.
         */
        public AiFixes.Mode departMedian = AiFixes.Mode.OFF;
        public int maxDeparturesPerTurn = 12;
        /**
         * Given-candidate searches only ({@link #decideGiven}; lane cm-choice-search-1009): departures to one candidate key
         * at one phase and stack size per turn before the loop guard keeps the default. 2 = K8's rule (K8's own paths keep
         * their fixed 2). A combo loop repeats one action (Kiki-Jiki's activation with the same untap) more than twice.
         * Not in the config JSON unless changed.
         */
        public int maxSameDepartures = 2;
        /**
         * S1 value leaf (lane s1-search-1007; used only with the hooks' value leaf): main-loop steps a play-out may run
         * past the start of the horizon turn waiting for the searching seat's first priority there. Not in the config JSON.
         */
        public int leafExtraSteps = 400;
        /** Probe instrumentation (copy timing, copy fidelity, determinism, sim-AI cost). */
        public boolean probe = false;
        /** C2: also search attack and block declarations. */
        public boolean combat = false;
        /** C1: also search priority decisions with a non-empty stack (spells only; see GameCopier.stackUnsupported). */
        public boolean stack = false;
        /** C1 fidelity probe: take the probes (and the one live-continuation fidelity check) at stack decisions only. */
        public boolean probeStack = false;
        /** Probe instrumentation on at most this many searched decisions per game. */
        public int probeMax = 6;
        /** Probe: once per game, put the live game on a known stream and compare it with a truth rollout. */
        public boolean fidelity = true;
        /** C5: base URL of the foundation-model leaf service (null = Forge's static evaluator as the leaf). */
        public String modelUrl = null;
        /** C5: per-request timeout; a failed request falls back to the static evaluator for that decision. */
        public int modelTimeoutMs = 2000;
        /**
         * Wall-clock budget per searched decision, in ms (0 = none, the default: reads and gates are
         * unchanged). When a search runs past it, its play-outs stop at their next main-loop step (a policy
         * play-out seat also at its next service request, {@link FirstAction#overBudget}) and the seat plays
         * Forge AI's own answer, or the policy's ("capped"). A hard wall bounds the wait for the play-outs at
         * the budget plus {@link #WALL_GRACE_MS} (lane sc-wallguard-1009). Timing-dependent by design
         * (interactive play only).
         */
        public long budgetMs = 0L;
        /** Interactive play: one JSON line per searched decision on stderr ({@code [lookahead-decision] {...}}). */
        public boolean decisionLog = false;
        /**
         * Read HX (human-likely extras): at a searched decision where the seat is the active player, in a main phase,
         * with an empty stack, ask the 17Lands policy prior ({@link #priorUrl}) about Forge's FULL candidate list and add
         * its top-n candidates (by served p) that are not already in the breadth-B set. 0 (the default) = off: no call,
         * nothing added, the config JSON unchanged.
         */
        public int priorExtra = 0;
        /** HX: base URL of the policy-prior service (mtgx serve.py). */
        public String priorUrl = null;
        /** HX shadow: make the prior call and log which extras would be added, but never add them. */
        public boolean priorShadow = false;
        /** HX: per-request timeout; a failed or late call keeps Forge's B set for that decision and is counted. */
        public int priorTimeoutMs = 2000;
        /** HX: the checkpoint the service must serve (GET /v1/health checkpointSha256), checked once per JVM. */
        public String priorCheckpointSha256 = null;

        /** HX is on (a prior call at qualifying decisions, added or shadow). */
        public boolean priorOn() {
            return priorExtra > 0 || priorShadow;
        }

        /**
         * Tutor ranking (lane tutor-ranking-0928; 0 = off, the default: no call, nothing searched, the config JSON
         * unchanged). At a single-card library search of the searching seat in the live game (a choice made during
         * resolution: Demonic Tutor, Tinker, Vampiric Tutor, Wishclaw, a fetchland...), the tutor ranker
         * ({@link #tutorUrl}) scores the distinct names Forge offers; Forge AI's own pick plus the top-{@code tutorRank}
         * other names by score are each played out from a copy of the position with the search's resolution replayed
         * and that card forced; the best by expected value is taken (ties and failures to Forge AI's pick).
         */
        public int tutorRank = 0;
        /** Tutor ranking: base URL of the ranker service (mtgx tools/ml/tutorrank/serve_tutor.py). */
        public String tutorUrl = null;
        /** Tutor ranking shadow: rank and play out, never change the pick. */
        public boolean tutorShadow = false;
        /** Tutor ranking: also searches whose every option is a land (fetchlands, Golos, Expedition Map). */
        public boolean tutorLands = false;
        /** Tutor ranking: per-request timeout; a failed or late call keeps Forge AI's pick and is counted. */
        public int tutorTimeoutMs = 2000;
        /** Tutor ranking: the checkpoint the service must serve (GET /v1/health checkpointSha256), checked once per JVM. */
        public String tutorCheckpointSha256 = null;

        /**
         * r2-choose-a-card-1029 (research; null = off, the default, and then nothing is built and the config JSON is
         * unchanged): the choose-a-card spec ({@link ChooseACard.Config#fromJson}). The look-ahead seat's controller
         * installs a {@link ChooseACard} on itself in the live game; its value is the search's value leaf
         * ({@link SearchHooks}) when the search has one, else Forge's static evaluator (report only).
         */
        public JsonObject cac = null;

        /** Tutor ranking is on (a ranker call and play-outs at qualifying searches, acting or shadow). */
        public boolean tutorOn() {
            return tutorRank > 0 || tutorShadow;
        }

        /**
         * Belief (lane belief-sampling-0928): how a world re-draws the opponent's hidden hand and library.
         * "off" (the default) = {@link #resample}: the opponent's registered deck remainder, reshuffled, config JSON
         * unchanged. "human" = hand drawn from the human-trained hand model ({@link #beliefUrl}) over an
         * observation-only pool (the cube minus our deck minus every opponent card we can see, plus
         * {@link #beliefBasics} copies of each basic), library uniform over the rest of the pool; a failed call samples
         * that decision uniformly over the same pool (counted). "uniform" = the same pool, every weight 1.
         */
        public String belief = "off";
        /** Belief shadow (do-no-harm gate): compute the belief and build its worlds in throwaway copies, play the default worlds. */
        public boolean beliefShadow = false;
        /**
         * order-memory-1102 layer 1, live (false = off, the default: every world exactly as before). Set only by the live
         * K8 spec's or the policy spec's {@code infoState=on} key. The world re-draw reads the live game's
         * {@link InfoStates}: our library keeps every card InfoState(me) knows at its index and permutes each set-known run
         * within its slots ({@link InfoStates#keepOwnLibrary}); the opponent's library keeps every card InfoState(me) knows
         * of it at its index (a revealed top that outlived Courser), in resample and the uniform belief re-draw; every
         * unknown slot is drawn from a canonical order.
         */
        public boolean infoState = false;
        /**
         * attack-holdback-1118 (report 2026-11-18T14-37-11; false = off, the default: every world exactly as before). Set
         * only by the policy spec's {@code knownHand=on} (with infoState). An opponent hand card the seat saw enter that
         * hand ({@link InfoStates#knowsInHand}: the human's Kolaghan, returned to hand at end of turn after a dash attack)
         * stays where it is in every world dealer that keeps the hand cards Forge shows us ({@link #seatMayKnowHand}).
         * Review #248 round 3: with it on, the seat's live tracker (RlKnowledge, {@link #beliefRememberedHand}) is not
         * consulted by any dealer, the uniform belief included: InfoStates' continuous identity alone decides.
         */
        public boolean knownHand = false;
        /** order-memory-1102: the version an armed seat's lines carry (its boot line, each K8 decision line). */
        public static final String INFO_STATE_VERSION = "infostate-live-1";

        /** A live spec's {@code infoState} value: on or off, anything else refused. */
        public static boolean infoStateKey(final String k, final String v) {
            if ("on".equals(v)) {
                return true;
            }
            if ("off".equals(v)) {
                return false;
            }
            throw new IllegalArgumentException(k + " must be on or off, not " + v);
        }
        public String beliefUrl = null;
        public int beliefTimeoutMs = 2000;
        public String beliefCheckpointSha256 = null;
        /** The cube list file ({"cards":[{"name","tags"}]}) and its sha256 pin. */
        public String beliefCube = null;
        public String beliefCubeSha256 = null;
        /** Copies of each basic land type in the pool. */
        public int beliefBasics = 8;
        /** Default off: later engine copies tolerate static effects of hidden cards replaced in belief worlds. */
        public boolean beliefStatics = LIVE_BELIEF_STATICS; // live-fixes-1107 default
        /** Default off: carry player-only stale-static repair into resampled look-ahead copies. */
        public boolean beliefPlayerStatics = LIVE_BELIEF_PLAYER_STATICS; // live-fixes-1107 default
        /** Default off: preserve opponent-hand identities remembered by the searching seat's observation tracker. */
        public boolean beliefRememberedHand = LIVE_BELIEF_REMEMBERED_HAND; // live-fixes-1107 default
        /** Default off: preserve this turn's use limits in look-ahead copies, as p9 does for solver copies. */
        public boolean copyTurnLimits = LIVE_COPY_TURN_LIMITS; // live-fixes-1107 default
        /** Default off: retain turn-use limits outside the battlefield. */
        public boolean offBattlefieldLimits = LIVE_OFF_BATTLEFIELD_LIMITS; // live-fixes-1107 default
        public boolean historyLki = false;
        public boolean copyBackFace = LIVE_COPY_BACK_FACE; // live-fixes-1107 default
        public boolean stackAbilityCopies = LIVE_STACK_ABILITY_COPIES; // live-fixes-1107 default
        public boolean stackHistory = false;
        /**
         * honest-belief-1020: the event's card pool as names (the host's {@code beliefPool}); when set it is the belief's
         * pool instead of {@link #beliefCube}. Not a spec key: the interactive server sets it per game.
         */
        public java.util.List<String> beliefPool = null;
        /**
         * honest-belief-1020: the pool is a singleton cube (true, the default and belief-sampling-0928's rule): our own
         * deck's names and every opponent card we can see leave the pool. False (a pool whose decks may repeat a card,
         * e.g. random constructed decks): the pool stays whole.
         */
        public boolean beliefSingleton = true;
        /**
         * deck-guess-1021 (null = none, the default, and then nothing changes): the card names the opponent showed in
         * the EARLIER games of this match (public zones and casts only; the host's {@code beliefSeen}). Read only by the
         * defender's deck prior ({@link #opponentRedrawPrior} with a seen gain above 0). Not a spec key: the interactive
         * server sets it per game.
         */
        public java.util.List<String> beliefSeen = null;
        /**
         * deck-belief-1105 (off by default, and then nothing changes): with a belief on, every world deals the
         * opponents' hidden cards from {@link DeckBelief} (a deck of the colours they have shown, the hand weighted by
         * what they declined to do) instead of the uniform or decision belief. Spec key {@code beliefDeck}.
         */
        public boolean beliefDeck = false;

        // ---- bvw-step2-fork-1027 (S2T, the symmetric teacher; every field off by default, and then nothing changes)
        /**
         * S2T, belief "codraft": the co-draft table ({@code syn[a][b]}: public co-draft data, never their real cards) of
         * the deck prior every world draws the opponent's hidden cards with ({@link #opponentRedrawPrior}: hand and
         * library together, weight {@code 1 + gain * max_s syn[s][card]^2} over the cards they showed in THIS game). Not
         * a JSON key (RlSearch's spec pins the table by its sha256).
         */
        public java.util.Map<String, java.util.Map<String, Double>> beliefPriorTable = null;
        /** S2T, belief "codraft": the prior's gain. */
        public double beliefPriorGain = 20.0;
        /**
         * S2T: the opponents' play-out seats are the hooks' policy seats ({@link SearchHooks#opponentSeat}, the same
         * bridged policy and service as the searching seat) instead of Forge AI ({@link RolloutAi}). Policy play-outs only.
         */
        public boolean opponentPolicy = false;
        /** S2T: a value leaf also takes the opponent's own frame at the same moment (its v_o). */
        public boolean leafOpp = false;
        /** S2T: a value leaf is (v_s + 1 - v_o) / 2 (needs {@link #leafOpp}); terminal play-outs stay 1 / 0 / 0.5. */
        public boolean leafSym = false;
        /**
         * step3-playout-fixes-1028 (false = off, and then nothing changes): the re-plays ({@link #replayCandidate},
         * {@link #reevaluate}) copy at level 0, where a face-up exiled may-play card's description names the effect card
         * instead of its source; they also match such a candidate by its base description ({@link #sameMayPlay}), as the
         * level-2 pass does, after the exact and prefix matches failed.
         */
        public boolean replayMayPlayLoose = false;
        /**
         * Lane sdcc-copycast-1114 (false = off, and then nothing changes): a candidate whose cast or land play is
         * permitted by an effect (a may-play grant: from the graveyard, from exile, from the top of the library) carries
         * the grant's structure ({@link #maySig}): the granting card's id, how its mana cost is paid, its alternative
         * cost and the ability's own text. A copy prints such an ability with another source name than the live game
         * does, so the description match fails (candidate_not_in_copy); with the key the copies ({@link #locate}) and
         * the play-out seat's menu ({@link FirstAction#matchesMaySig}) match it by that structure, after the
         * description matches failed. The first pass itself finds it: no second search of the decision.
         */
        public boolean copyCast = false;
        /**
         * Lane sdcc-copycast-1114 (false = off, and then nothing changes): the search's copies carry a dungeon card with
         * its current room ({@link GameCopier#setCopyDungeon}). Without it no copy holds the dungeon.
         */
        public boolean copyDungeon = false;
        /**
         * Lane sdcc-copycast-1114 (false = off, and then nothing changes): the search's copies leave out a stack entry's
         * stale card target or gone paid token instead of failing ({@link GameCopier#setCopyStaleRefs}; today such a
         * play-out fails as copy_error "Couldn't map ..." / "stack copy: paid ... not in the copy").
         */
        public boolean copyStaleRefs = false;
        /**
         * Lane sdcc-copycast-1114 (false = off, and then nothing changes): a priority decision made while a triggered
         * ability waits on the stack is searched; the copies rebuild the trigger from its host
         * ({@link GameCopier#setCopyTriggers}). Without it such a decision is refused ("trigger") and the default stands.
         */
        public boolean copyTriggers = false;

        /** S2T: the belief is the co-draft deck prior. */
        public boolean codraft() {
            return "codraft".equals(belief);
        }

        /**
         * belief-n11-1030 (N11), belief "deck": the learned opponent-deck model every world (and the plans' and
         * choose-a-card's solver worlds) draws the opponent's hidden hand and library from, given the cards they showed
         * in this game ({@link #redrawOpponentsDeck}). Not a JSON key (RlSearch's spec pins the export by its sha256).
         */
        public DeckBeliefModel beliefDeckModel = null;

        /** belief-n11-1030: the belief is the learned deck model. */
        public boolean deck() {
            return "deck".equals(belief);
        }

        /** A belief is computed at searched decisions (acting or shadow). */
        public boolean beliefOn() {
            return !"off".equals(belief) || beliefShadow;
        }

        /** The belief whose weights come from the service. */
        public boolean beliefHuman() {
            return "human".equals(belief);
        }

        /**
         * L2 step 2 (lane l2-fork-1001; false = off, the default, config JSON unchanged): our seat's play-outs piloted by
         * policy P1 ({@link PolicyRolloutAi}). Wired but not built: a search with it set refuses to start until step 2
         * has an L2 PASS and its own predeclaration.
         */
        public boolean policyRollouts = false;

        /**
         * EI ranker seat (lane ei-1004; null = off, the default: the config JSON is unchanged). No play-outs: at every
         * priority decision with at least two candidates in the enumeration's B set (Forge's answer, pass, the next legal
         * candidates; exactly the search's), the ranker service ({@link RankerClient}) gets the seat's ForgeState, the
         * decision context and the candidates with the targets each would be played with (Forge AI's choices in the
         * search's world-0 copy), and its argmax is played; Forge AI keeps every other choice. A failed call plays
         * Forge's answer and is counted.
         */
        public String rankerUrl = null;
        public String rankerCheckpointSha256 = null;
        public int rankerTimeoutMs = 2000;
        /** Ranker shadow (do-no-harm gate): call and count, always play Forge's answer. */
        public boolean rankerShadow = false;
        /**
         * EI matched placebo (gate-3 arm PZ; 0 = off): no service; at each such decision, with this probability, a
         * uniformly random non-Forge candidate of B (draws from a private stream of the seat seed and decision index).
         */
        public double placeboRate = 0.0;

        /** The seat ranks instead of searching (ranker or placebo). */
        public boolean rankerOn() {
            return rankerUrl != null || placeboRate > 0;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("worlds", worlds);
            o.addProperty("breadth", breadth);
            if (infoState) {
                o.addProperty("infoState", true); // order-memory-1102: only when on
            }
            if (knownHand) {
                o.addProperty("knownHand", true); // attack-holdback-1118: only when on
            }
            if (offTurnBreadth > 0) {
                o.addProperty("offTurnBreadth", offTurnBreadth); // endstep-rows-1103: only when on
            }
            o.addProperty("horizonTurns", horizonTurns);
            o.addProperty("maxSteps", maxSteps);
            o.addProperty("seed", seed);
            o.addProperty("shadow", shadow);
            o.addProperty("resample", resample);
            o.addProperty("threads", threads);
            o.addProperty("threadsEffective", effectiveThreads(this));
            if (dedup) {
                o.addProperty("dedup", true);
            }
            if (dedupVerify) {
                o.addProperty("dedupVerify", true);
            }
            if (dedupSteps > 0) {
                o.addProperty("dedupSteps", dedupSteps);
            }
            if (reuse) {
                o.addProperty("reuse", true);
            }
            if (reuseVerify) {
                o.addProperty("reuseVerify", true);
            }
            if (reuseShadowFresh) {
                o.addProperty("reuseShadowFresh", true);
            }
            o.addProperty("margin", margin);
            if (targetVariants > 0) {
                o.addProperty("targetVariants", targetVariants);
            }
            if (departZ > 0) {
                o.addProperty("departZ", departZ);
            }
            if (prioGf > 0) {
                // sdcc-c1-priogf-1117: only when on, so every other spec's JSON is unchanged
                o.addProperty("prioGf", prioGf);
                o.addProperty("prioGfMargin", prioGfMargin);
                o.addProperty("prioGfTurns", prioGfTurns);
                if (prioGfHold) {
                    o.addProperty("prioGfHold", true);
                }
            }
            if (deadEtb != AiFixes.Mode.OFF) {
                o.addProperty("deadEtb", deadEtb.key());
            }
            if (zeroX != AiFixes.Mode.OFF) {
                o.addProperty("zeroX", zeroX.key());
            }
            if (crewNoop != AiFixes.Mode.OFF) {
                o.addProperty("crewNoop", crewNoop.key());
            }
            if (deadAltCost != AiFixes.Mode.OFF) {
                o.addProperty("deadAltCost", deadAltCost.key());
            }
            if (deadPump != AiFixes.Mode.OFF) {
                o.addProperty("deadPump", deadPump.key());
            }
            if (deadFaceUp != AiFixes.Mode.OFF) {
                o.addProperty("deadFaceUp", deadFaceUp.key());
            }
            if (ownHarm != AiFixes.Mode.OFF) {
                o.addProperty("ownHarm", ownHarm.key()); // line-own-target-1119 (only when not off)
            }
            if (lethalBlock != AiFixes.Mode.OFF) {
                o.addProperty("lethalBlock", lethalBlock.key());
            }
            if (dudCast != AiFixes.Mode.OFF) {
                o.addProperty("dudCast", dudCast.key());
                o.addProperty("dudWorlds", dudWorlds);
            }
            if (copyEot != AiFixes.Mode.OFF) {
                o.addProperty("copyEot", copyEot.key());
            }
            if (copyTurnLimits) o.addProperty("copyTurnLimits", true);
            if (beliefRememberedHand) o.addProperty("beliefRememberedHand", true);
            if (offBattlefieldLimits) o.addProperty("offBattlefieldLimits", true);
            if (historyLki) o.addProperty("historyLki", true);
            if (copyBackFace) o.addProperty("copyBackFace", true);
            if (stackAbilityCopies) o.addProperty("stackAbilityCopies", true);
            if (stackHistory) o.addProperty("stackHistory", true);
            if (copyFidelity != 0) {
                o.addProperty("copyFidelity", copyFidelity);
            }
            if (departMedian != AiFixes.Mode.OFF) {
                o.addProperty("departMedian", departMedian.key());
            }
            o.addProperty("maxDeparturesPerTurn", maxDeparturesPerTurn);
            if (maxSameDepartures != 2) {
                o.addProperty("maxSameDepartures", maxSameDepartures);
            }
            o.addProperty("probe", probe);
            o.addProperty("combat", combat);
            if (stack) {
                o.addProperty("stack", true);
            }
            if (probeStack) {
                o.addProperty("probeStack", true);
            }
            o.addProperty("probeMax", probeMax);
            o.addProperty("fidelity", fidelity);
            if (budgetMs > 0) {
                o.addProperty("budgetMs", budgetMs);
            }
            if (modelUrl != null) {
                o.addProperty("modelUrl", modelUrl);
                o.addProperty("modelTimeoutMs", modelTimeoutMs);
            }
            if (priorOn()) {
                o.addProperty("priorExtra", priorExtra);
                o.addProperty("priorShadow", priorShadow);
                o.addProperty("priorUrl", priorUrl);
                o.addProperty("priorTimeoutMs", priorTimeoutMs);
                o.addProperty("priorCheckpointSha256", priorCheckpointSha256);
            }
            if (cac != null) {
                o.add("chooseACard", cac);
            }
            if (tutorOn()) {
                o.addProperty("tutorRank", tutorRank);
                o.addProperty("tutorShadow", tutorShadow);
                o.addProperty("tutorLands", tutorLands);
                o.addProperty("tutorUrl", tutorUrl);
                o.addProperty("tutorTimeoutMs", tutorTimeoutMs);
                o.addProperty("tutorCheckpointSha256", tutorCheckpointSha256);
            }
            if (beliefOn()) {
                o.addProperty("belief", belief);
                o.addProperty("beliefShadow", beliefShadow);
                o.addProperty("beliefUrl", beliefUrl);
                o.addProperty("beliefTimeoutMs", beliefTimeoutMs);
                o.addProperty("beliefCheckpointSha256", beliefCheckpointSha256);
                o.addProperty("beliefCube", beliefCube);
                o.addProperty("beliefCubeSha256", beliefCubeSha256);
                o.addProperty("beliefBasics", beliefBasics);
                if (beliefStatics) o.addProperty("beliefStatics", true);
                if (beliefPlayerStatics) o.addProperty("beliefPlayerStatics", true);
                if (beliefPool != null) {
                    o.addProperty("beliefPoolSize", beliefPool.size());
                }
                if (!beliefSingleton) {
                    o.addProperty("beliefSingleton", false);
                }
                if (beliefDeck) {
                    o.addProperty("beliefDeck", true); // deck-belief-1105: only when on
                }
                if (codraft()) {
                    o.addProperty("beliefPriorGain", beliefPriorGain); // bvw-step2-fork-1027: only with codraft
                }
                if (deck() && beliefDeckModel != null) {
                    o.addProperty("beliefDeckSha256", beliefDeckModel.sha256); // belief-n11-1030: only with deck
                }
            }
            // bvw-step2-fork-1027 (S2T): only when on, so every other config's JSON is unchanged
            if (opponentPolicy) {
                o.addProperty("opponentPolicy", true);
            }
            if (leafOpp) {
                o.addProperty("leafOpp", true);
            }
            if (leafSym) {
                o.addProperty("leafSym", true);
            }
            if (replayMayPlayLoose) {
                o.addProperty("replayMayPlayLoose", true); // step3-playout-fixes-1028: only when on
            }
            if (copyCast) {
                o.addProperty("copyCast", true); // sdcc-copycast-1114: only when on
            }
            if (copyDungeon) {
                o.addProperty("copyDungeon", true); // sdcc-copycast-1114: only when on
            }
            if (copyStaleRefs) {
                o.addProperty("copyStaleRefs", true); // sdcc-copycast-1114: only when on
            }
            if (copyTriggers) {
                o.addProperty("copyTriggers", true); // sdcc-copycast-1114: only when on
            }
            if (rankerOn()) {
                final JsonObject r = new JsonObject();
                r.addProperty("url", rankerUrl);
                r.addProperty("checkpointSha256", rankerCheckpointSha256);
                r.addProperty("timeoutMs", rankerTimeoutMs);
                r.addProperty("shadow", rankerShadow);
                r.addProperty("placeboRate", placeboRate);
                o.add("ranker", r);
            }
            return o;
        }
    }

    /** Worker threads actually used for a config (0 = auto). */
    public static int effectiveThreads(Config c) {
        if (c.threads > 0) {
            return c.threads;
        }
        final int tasks = Math.max(1, c.worlds) * Math.max(1, c.breadth);
        return Math.max(1, Math.min(Math.min(tasks, Runtime.getRuntime().availableProcessors()), 8));
    }

    /** Per-seat, per-game counters. */
    public static final class Stats {
        public long decisions, stackSkipped, uncontested, searched, departed, departFallback, loopGuard;
        /** departZ: candidates (with a finished play-out in every world) set aside for lack of confidence. */
        public long departGated;
        /** targetVariants: variant candidates offered; departures to a variant. */
        public long targetVariantCands, targetVariantDepartures;
        /**
         * deadEtb guard (null = off): the mode; dead-ETB candidates met in enumeration (Forge's answer excluded), decisions
         * with at least one, of those dropped (ON), searched decisions whose best candidate was one (SHADOW: departures the
         * guard would have stopped), and decisions where Forge's own answer was one (never dropped).
         */
        public String deadEtbMode;
        public long deadEtbCands, deadEtbDecisions, deadEtbDropped, deadEtbShadowBest, deadEtbForge;
        /** zeroX guard (null = off): the same counters as deadEtb, for zero-X spells. */
        public String zeroXMode;
        public long zeroXCands, zeroXDecisions, zeroXDropped, zeroXShadowBest, zeroXForge;
        /** crewNoop guard (null = off): the same counters as deadEtb, for Crew activations that cannot matter this turn. */
        public String crewNoopMode;
        public long crewNoopCands, crewNoopDecisions, crewNoopDropped, crewNoopShadowBest, crewNoopForge;
        /** deadAltCost guard (null = off): the same counters as deadEtb, for card-spending casts that buy nothing. */
        public String deadAltCostMode;
        public long deadAltCostCands, deadAltCostDecisions, deadAltCostDropped, deadAltCostShadowBest, deadAltCostForge;
        /** deadPump guard (null = off): the same counters as deadEtb, for power-only pumps that cannot matter this turn. */
        public String deadPumpMode;
        public long deadPumpCands, deadPumpDecisions, deadPumpDropped, deadPumpShadowBest, deadPumpForge;
        /**
         * deadFaceUp guard (null = off): the same counters as deadEtb, for turn-ups that gain nothing now; plus the turn-up
         * candidates it checked, its stand-asides by reason, and the time spent in the check.
         */
        public String deadFaceUpMode;
        public long deadFaceUpCands, deadFaceUpDecisions, deadFaceUpDropped, deadFaceUpShadowBest, deadFaceUpForge,
                deadFaceUpChecked, deadFaceUpNanos;
        public final Map<String, Long> deadFaceUpWhy = new TreeMap<>();
        /**
         * ownHarm guard (null = off; line-own-target-1119): refused candidates seen, decisions with one, decisions the
         * demotion moved off a refused candidate (dropped; SHADOW: would have), the seat's own choice refused (the policy's
         * or Forge's), the policy's own choice replaced by an entry it did not refuse / by pass, sampled plans refused and
         * live policy plans broken before a refused step; review 3: the demotion's tests (a refused pick against the best
         * candidate not refused) and those the refused pick passed (better by the departZ test and the floor); refusals by
         * card.
         */
        public String ownHarmMode;
        public long ownHarmCands, ownHarmDecisions, ownHarmDropped, ownHarmForge, ownHarmFallback, ownHarmPass,
                ownHarmPlanRefused, ownHarmPlanBreaks, ownHarmTests, ownHarmPassed, ownHarmNoScale, ownHarmGuard;
        public final Map<String, Long> ownHarmCards = new TreeMap<>();
        /**
         * lethalBlock guard (null = off): block decisions checked; stood aside (the bound was not trusted); with a candidate
         * the bound flags; re-scored (ON); with every searched candidate witnessed (unchanged); whose played declaration
         * was witnessed lethal; the candidates the bound flagged; the default among the witnessed; failed checks (stood
         * aside). Round 6: the candidates the engine witnessed lethal, the witness lines played, their time, and the
         * decisions whose witness ran out of budget (the rest not witnessed).
         */
        public String lethalBlockMode;
        public long lethalBlockChecked, lethalBlockAside, lethalBlockFlagged, lethalBlockRescored, lethalBlockAllLethal,
                lethalBlockPlayedLethal, lethalBlockCands, lethalBlockDefault, lethalBlockErrors;
        public long lethalBlockWitnessed, lethalBlockWitnessRuns, lethalBlockWitnessNanos, lethalBlockWitnessCapped;
        /** Round 7: decisions whose witness hit the wall-clock safety cap; witness runs voided, by reason. */
        public long lethalBlockWitnessTimedOut;
        public final Map<String, Long> lethalBlockWitnessVoid = new TreeMap<>();
        public final Map<String, Long> lethalBlockWhy = new TreeMap<>();
        /** dudCast guard (null = off): answers checked, duds found, vetoed (re-asked), passed, unknown; time; dud cards. */
        public String dudCastMode;
        public long dudChecks, dudFound, dudVetoed, dudPassed, dudUnknown, dudNanos;
        public final Map<String, Long> dudCards = new TreeMap<>();
        /** copyEot (null = off): searched decisions with live until commands pending, those commands, how many remap. */
        public String copyEotMode;
        public long copyEotDecisions, copyEotCommands, copyEotRemappable, copyEotDepartures;
        /** departMedian (null = off): candidates the median gate set aside; decisions where it changed (ON) or would change (SHADOW) the choice. */
        public String departMedianMode;
        public long departMedianGated, departMedianChanged;
        public long rollouts, rolloutFailures, rolloutCapped, candidatesDropped, steps;
        /**
         * Lane copy-fidelity-1010: the searched candidates' failed or stopped play-outs by class (always reported, whatever
         * the options; the classes of the priority and combat searches sum to rolloutFailures + rolloutsAborted):
         * candidate_not_in_menu (a policy play-out seat's first PRIORITY menu lacks the searched candidate),
         * candidate_not_in_copy (the candidate's ability could not be found or set up in the copy), max_decisions (the
         * play-out seat's decision cap), budget (the decision's wall budget stopped it, or the seat's CPU cap),
         * copy_error (making the copy threw), exception (the play-out threw), other (anything else: a service error).
         */
        public static final String[] PLAYOUT_FAILURE_CLASSES = {"candidate_not_in_menu", "candidate_not_in_copy",
                "max_decisions", "budget", "copy_error", "exception", "other"};
        public final long[] playoutFailures = new long[PLAYOUT_FAILURE_CLASSES.length];
        /** The same failures by "class kind host-card" (the first {@link #FAILURE_CANDS_CAP} keys; reported when any). */
        public final Map<String, Long> playoutFailureCands = new TreeMap<>();
        static final int FAILURE_CANDS_CAP = 32;
        /** The last copy failure's detail (copy_error / candidate_not_in_copy; reported when any). */
        public String playoutLastCopyFailure;
        /**
         * Copy fidelity (reported only when the option is on): its level; at level 2, the decisions searched again on
         * level-1 copies and the play-outs of their first, discarded pass (their failures are not in playoutFailures).
         */
        public int copyFidelityLevel;
        public long fidelityRedos, fidelityRedoRollouts;

        void countPlayoutFailure(String cls, String cand) {
            int k = PLAYOUT_FAILURE_CLASSES.length - 1;
            for (int i = 0; i < PLAYOUT_FAILURE_CLASSES.length; i++) {
                if (PLAYOUT_FAILURE_CLASSES[i].equals(cls)) {
                    k = i;
                    break;
                }
            }
            playoutFailures[k]++;
            if (cand != null) {
                final String key = PLAYOUT_FAILURE_CLASSES[k] + " " + cand;
                if (playoutFailureCands.containsKey(key) || playoutFailureCands.size() < FAILURE_CANDS_CAP) {
                    playoutFailureCands.merge(key, 1L, Long::sum);
                }
            }
        }
        public long searchNanos, maxSearchNanos;
        public long attackDecisions, attackSearched, attackDeparted, blockDecisions, blockSearched, blockDeparted;
        public long combatNanos;
        /** Wall budget: searched decisions that ran past it (and played Forge's answer); play-outs stopped by it. */
        public long capped, rolloutsAborted;
        /** search-bones-1015 (S-b): decisions over the budget decided on the worlds every candidate finished in. */
        public long partialDecisions;
        /**
         * Hard wall (lane sc-wallguard-1009): searched decisions whose wait for their play-outs the wall cut short
         * (budget + {@link #WALL_GRACE_MS}), and the play-outs still running at the cut (told to stop; their results are
         * never read). Both 0 unless the wall fired.
         */
        public long wallCuts, wallStragglers;
        /** C1: priority decisions with a non-empty stack; of those, refused (by reason), searched, departed. */
        public long stackDecisions, stackUnsupported, stackSearched, stackDeparted;
        public final Map<String, Long> stackWhy = new TreeMap<>();
        /** C5 model leaf: requests, leaves scored, decisions that fell back to the static evaluator. */
        public long modelCalls, modelLeaves, modelFallbacks, modelNanos, modelUnknownCards;
        /** Latency of every searched priority decision (ms, wall), in order. */
        public final List<Double> searchMsEach = new ArrayList<>();
        /** Dedup: play-outs cut short (or, in verify mode, that would have been), main-loop steps saved, value mismatches found by verify, key time. */
        public long dedupHits, dedupStepsSaved, dedupVerifyMismatch, dedupKeyNanos, dedupKeys;
        /** Reuse probe: kept worlds checked at the next search, still consistent, whose kept choice equals Forge's answer; steps a reuse could skip. */
        public long reuseChecked, reuseAlive, reuseSameChoice, reuseHiddenMismatch, reuseHits, reuseStepsSaved;
        /** Reuse checks: decisions with a carried world; decisions whose choice the reused values changed (vs fresh play-outs
         * of candidate 0 in the same worlds); reused values that differ from those fresh play-outs; decisions that differ
         * from the fresh A8S search. */
        public long reuseDecisionsCarried, reuseDiffSameWorlds, reuseValueDiff, reuseCompared, reuseDiffVsFresh, reuseFreshCompared, freshVsFreshDiff;
        public final Map<String, Long> dedupHitStep = new TreeMap<>();
        public final Map<String, Long> reuseAliveBy = new TreeMap<>();

        /** Verify mode: a play-out that would have been cut short, saving {@code remaining} steps. */
        synchronized void dedupWould(int remaining, int step) {
            dedupHits++;
            dedupStepsSaved += remaining;
            dedupHitStep.merge(step <= 3 ? "s" + step : step <= 10 ? "s4-10" : "s11+", 1L, Long::sum);
        }
        public String modelDigest, modelCheckpoint, modelLastError;
        public final JsonArray probes = new JsonArray();
        /**
         * HX prior (reported only when the option is on). Searched decisions that qualified (active, main phase, empty
         * stack) or did not; qualifying ones with no candidate outside B (no call); requests made; of those, failed
         * (voids the game in a read); call wall time; decisions that got extras, extras added; decisions whose search
         * choice was an extra, and of those the ones actually played; shadow: decisions / extras that would have been
         * added.
         */
        public boolean priorOn;
        public long priorQualifying, priorNotQualifying, priorNoRoom, priorCalls, priorFailures, priorNanos, priorMaxNanos;
        public long decisionsWithExtras, extrasAdded, extraBest, departuresToExtra, shadowDecisionsWithExtras, shadowExtras;
        public long priorUnknownCards;
        public String priorDigest, priorCheckpoint, priorLastError;
        /** One entry per qualifying decision: B size, full list size, extras (would-be in shadow) with p, choice, ms. */
        public final JsonArray priorDecisions = new JsonArray();
        /**
         * Tutor ranking (reported only when the option is on). Library-search choices of this seat in the live game
         * (seen); of those: land-only searches left to Forge (tutorLands off), one distinct name, Forge declined an
         * optional search, not searchable (by reason); ranker calls and failures; searched; searched choices whose
         * pick differs from Forge AI's (departed, or would in shadow); the ranker's top name equals Forge AI's pick;
         * play-outs and failed play-outs (a forced pick the copy could not make counts as failed).
         */
        public boolean tutorOn;
        public long tutorSeen, tutorLandOnly, tutorSingle, tutorForgeNone, tutorUnsupported, tutorCalls, tutorFailures, tutorNanos;
        public long tutorSearched, tutorDeparted, tutorShadowWouldDepart, tutorTop1IsForge, tutorRollouts, tutorRolloutFailures, tutorForcedMissed;
        public long tutorUnknownCards;
        public String tutorDigest, tutorCheckpoint, tutorLastError;
        public final Map<String, Long> tutorWhy = new TreeMap<>();
        /** One entry per searched (or refused) choice: source, destination, names, Forge's pick, ranked, EVs, choice. */
        public final JsonArray tutorDecisions = new JsonArray();
        /** r2-choose-a-card-1029: the seat's choose-a-card decisions (only when the option is on), and those past 512. */
        public final JsonArray cacDecisions = new JsonArray();
        public long cacDropped;
        /**
         * Belief (reported only when the option is on): searched decisions with a belief; skipped (nothing hidden, not
         * two players); service calls, failures (= uniform fallbacks), call time; worlds drawn from a belief (acting or
         * shadow), cards created for them and the time; hidden-count mismatches (a world fell back to the default
         * re-draw); the pool's mean slot count; the running digest of the served weights; one coverage entry per belief
         * decision.
         */
        /** Label dump (reported only when it is on): lines emitted, emissions that failed, roots that failed to encode. */
        public boolean labelsOn;
        public long labels, labelFailures, labelRootFailures;
        public String labelLastError;
        /**
         * EI ranker seat (reported only when it is on): ranked decisions (>= 2 candidates), service calls, failures
         * (Forge's answer played), call time, decisions whose pick was not Forge's answer (would-depart, also in
         * shadow), placebo draws that changed the pick, the running digest of the service's answers.
         */
        public boolean rankerOn;
        public long rankerDecisions, rankerCalls, rankerFailures, rankerNanos, rankerMaxNanos, rankerNonDefault, placeboDraws;
        public long rankerUnknownCards, rankerPrepFailures;
        public String rankerDigest, rankerLastError;
        public final Map<String, Long> rankerDepartKinds = new TreeMap<>();
        public boolean beliefOn;
        public long beliefDecisions, beliefSkipped, beliefCalls, beliefFailures, beliefNanos, beliefMaxNanos;
        public long beliefWorlds, beliefCards, beliefCreateNanos, beliefMismatch, beliefPoolSlots, beliefUnknownCards;
        public long beliefStaticRefreshes;
        public String beliefDigest, beliefCheckpoint, beliefLastError;
        public final JsonArray beliefCoverage = new JsonArray();
        /**
         * S1 (reported only when {@link #decideGiven} ran): its decisions (given candidate sets), those with a non-empty
         * stack it skipped, the uncontested ones (under two candidates), the value-leaf calls, leaves, fallbacks to the
         * static values, leaves taken late (no priority of the seat within the extra steps, taken at the cap) and call
         * time, policy play-outs failed by their seat, and the CPU time of every searched given decision (decision
         * thread plus pool threads).
         */
        public boolean s1On;
        public long givenDecisions, givenStackSkipped, givenUncontested, leafCalls, leafLeaves, leafFallbacks, leafLate,
                leafNanos, playoutSeatFailures, searchCpuNanos;
        /** bvw-step2-fork-1027 (S2T): logging-only opponent leaf calls that failed (reported only when > 0). */
        public long leafOppFailures;
        /** solver-distill-cc-1109 (REACH): value leaves that carried a reach, and those whose own reach was above 0. */
        public long reachLeaves, reachHits, reachNaN;
        public double reachSum;
        /** solver-distill-cc-1109 (report only): reach cost: planner nodes, capped no-new-card screens, skipped, per-card screens and capped. */
        public long reachNodes, reachS0Capped, reachSkipped, reachNameScreens, reachNameCapped;
        /** solver-distill-cc-1109: departures the reach rule allowed past a failed z test. */
        public int reachDeparts;

        /** One leaf's reach (the hooks' array: R_me, R_opp, then cost counters when present). */
        synchronized void noteReach(final double[] rr) {
            reachLeaves++;
            if (rr[0] > 0) {
                reachHits++;
                reachSum += rr[0];
            } else if (Double.isNaN(rr[0])) {
                reachNaN++;
            }
            if (rr.length >= 7) {
                reachNodes += (long) rr[2];
                reachS0Capped += (long) rr[3];
                reachSkipped += (long) rr[4];
                reachNameScreens += (long) rr[5];
                reachNameCapped += (long) rr[6];
            }
        }
        /** Step-3 hygiene: true-world re-plays refused because fair naming was not on for every player. Always 0 on
         *  research/bvw-step2-keys-1028: the refusal itself is in the excluded hygiene commit 8043ba81bf3; the field
         *  exists only so the S3-3 report (b387c8c1318) compiles. */
        public long trueFairNamingRefusals;
        /** S-t (lane cm-choice-search-1009; reported only when a decision expanded choices): decisions with macro
         *  candidates, macro candidates played, macro departures. */
        public long givenMacroDecisions, givenMacros, givenMacroDepartures, givenMacroRedundant;
        public String leafLastError, playoutLastFailure;
        public final List<Double> searchCpuMsEach = new ArrayList<>();

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("decisions", decisions);
            o.addProperty("stackSkipped", stackSkipped);
            o.addProperty("uncontested", uncontested);
            o.addProperty("searched", searched);
            o.addProperty("departed", departed);
            o.addProperty("departFallback", departFallback);
            o.addProperty("loopGuard", loopGuard);
            if (departGated > 0) {
                o.addProperty("departGated", departGated);
            }
            if (targetVariantCands > 0) {
                o.addProperty("targetVariantCands", targetVariantCands);
                o.addProperty("targetVariantDepartures", targetVariantDepartures);
            }
            if (deadEtbMode != null) {
                final JsonObject d = new JsonObject();
                d.addProperty("mode", deadEtbMode);
                d.addProperty("cands", deadEtbCands);
                d.addProperty("decisions", deadEtbDecisions);
                d.addProperty("dropped", deadEtbDropped);
                d.addProperty("shadowBest", deadEtbShadowBest);
                d.addProperty("forgeAnswer", deadEtbForge);
                o.add("deadEtb", d);
            }
            if (zeroXMode != null) {
                final JsonObject d = new JsonObject();
                d.addProperty("mode", zeroXMode);
                d.addProperty("cands", zeroXCands);
                d.addProperty("decisions", zeroXDecisions);
                d.addProperty("dropped", zeroXDropped);
                d.addProperty("shadowBest", zeroXShadowBest);
                d.addProperty("forgeAnswer", zeroXForge);
                o.add("zeroX", d);
            }
            if (crewNoopMode != null) {
                JsonObject d = new JsonObject();
                d.addProperty("mode", crewNoopMode);
                d.addProperty("cands", crewNoopCands);
                d.addProperty("decisions", crewNoopDecisions);
                d.addProperty("dropped", crewNoopDropped);
                d.addProperty("shadowBest", crewNoopShadowBest);
                d.addProperty("forgeAnswer", crewNoopForge);
                o.add("crewNoop", d);
            }
            if (deadAltCostMode != null) {
                final JsonObject d = new JsonObject();
                d.addProperty("mode", deadAltCostMode);
                d.addProperty("cands", deadAltCostCands);
                d.addProperty("decisions", deadAltCostDecisions);
                d.addProperty("dropped", deadAltCostDropped);
                d.addProperty("shadowBest", deadAltCostShadowBest);
                d.addProperty("forgeAnswer", deadAltCostForge);
                o.add("deadAltCost", d);
            }
            if (deadPumpMode != null) {
                final JsonObject d = new JsonObject();
                d.addProperty("mode", deadPumpMode);
                d.addProperty("cands", deadPumpCands);
                d.addProperty("decisions", deadPumpDecisions);
                d.addProperty("dropped", deadPumpDropped);
                d.addProperty("shadowBest", deadPumpShadowBest);
                d.addProperty("forgeAnswer", deadPumpForge);
                o.add("deadPump", d);
            }
            if (deadFaceUpMode != null) {
                final JsonObject d = new JsonObject();
                d.addProperty("mode", deadFaceUpMode);
                d.addProperty("cands", deadFaceUpCands);
                d.addProperty("decisions", deadFaceUpDecisions);
                d.addProperty("dropped", deadFaceUpDropped);
                d.addProperty("shadowBest", deadFaceUpShadowBest);
                d.addProperty("forgeAnswer", deadFaceUpForge);
                d.addProperty("checked", deadFaceUpChecked);
                final JsonObject why = new JsonObject();
                deadFaceUpWhy.forEach(why::addProperty);
                d.add("aside", why);
                d.addProperty("ms", Math.round(deadFaceUpNanos / 1e5) / 10.0);
                o.add("deadFaceUp", d);
            }
            if (ownHarmMode != null) {
                final JsonObject d = new JsonObject();
                d.addProperty("mode", ownHarmMode);
                d.addProperty("cands", ownHarmCands);
                d.addProperty("decisions", ownHarmDecisions);
                d.addProperty("dropped", ownHarmDropped);
                d.addProperty("ownChoice", ownHarmForge);
                d.addProperty("fallback", ownHarmFallback);
                d.addProperty("pass", ownHarmPass);
                d.addProperty("planRefused", ownHarmPlanRefused);
                d.addProperty("planBreaks", ownHarmPlanBreaks);
                d.addProperty("tests", ownHarmTests);
                d.addProperty("passed", ownHarmPassed);
                d.addProperty("noScale", ownHarmNoScale); // review 4: no action, the values not win probabilities
                d.addProperty("guard", ownHarmGuard); // review 4: stand-ins the loop guard blocked
                final JsonObject by = new JsonObject();
                ownHarmCards.forEach(by::addProperty);
                d.add("cards", by);
                o.add("ownHarm", d);
            }
            if (lethalBlockMode != null) {
                final JsonObject d = new JsonObject();
                d.addProperty("mode", lethalBlockMode);
                d.addProperty("checked", lethalBlockChecked);
                d.addProperty("aside", lethalBlockAside);
                d.addProperty("flagged", lethalBlockFlagged);
                d.addProperty("rescored", lethalBlockRescored);
                d.addProperty("allLethal", lethalBlockAllLethal);
                d.addProperty("playedLethal", lethalBlockPlayedLethal);
                d.addProperty("cands", lethalBlockCands);
                d.addProperty("default", lethalBlockDefault);
                d.addProperty("errors", lethalBlockErrors);
                d.addProperty("witnessed", lethalBlockWitnessed);
                d.addProperty("witnessRuns", lethalBlockWitnessRuns);
                d.addProperty("witnessMs", lethalBlockWitnessNanos / 1_000_000L);
                d.addProperty("witnessCapped", lethalBlockWitnessCapped);
                d.addProperty("witnessTimedOut", lethalBlockWitnessTimedOut);
                final JsonObject voids = new JsonObject();
                lethalBlockWitnessVoid.forEach(voids::addProperty);
                d.add("witnessVoid", voids);
                final JsonObject why = new JsonObject();
                lethalBlockWhy.forEach(why::addProperty);
                d.add("why", why);
                o.add("lethalBlock", d);
            }
            if (dudCastMode != null) {
                JsonObject d = new JsonObject();
                d.addProperty("mode", dudCastMode);
                d.addProperty("checks", dudChecks);
                d.addProperty("found", dudFound);
                d.addProperty("vetoed", dudVetoed);
                d.addProperty("passed", dudPassed);
                d.addProperty("unknown", dudUnknown);
                d.addProperty("ms", Math.round(dudNanos / 1e5) / 10.0);
                JsonObject cards = new JsonObject();
                for (Map.Entry<String, Long> e : dudCards.entrySet()) {
                    cards.addProperty(e.getKey(), e.getValue());
                }
                d.add("cards", cards);
                o.add("dudCast", d);
            }
            if (copyEotMode != null) {
                JsonObject d = new JsonObject();
                d.addProperty("mode", copyEotMode);
                d.addProperty("decisions", copyEotDecisions);
                d.addProperty("commands", copyEotCommands);
                d.addProperty("remappable", copyEotRemappable);
                d.addProperty("departures", copyEotDepartures);
                o.add("copyEot", d);
            }
            if (departMedianMode != null) {
                JsonObject d = new JsonObject();
                d.addProperty("mode", departMedianMode);
                d.addProperty("gated", departMedianGated);
                d.addProperty("changed", departMedianChanged);
                o.add("departMedian", d);
            }
            o.addProperty("rollouts", rollouts);
            o.addProperty("rolloutFailures", rolloutFailures);
            o.addProperty("rolloutCapped", rolloutCapped);
            // copy-fidelity-1010: always, so a smoke check can read every class
            final JsonObject pf = new JsonObject();
            for (int i = 0; i < PLAYOUT_FAILURE_CLASSES.length; i++) {
                pf.addProperty(PLAYOUT_FAILURE_CLASSES[i], playoutFailures[i]);
            }
            o.add("playoutFailures", pf);
            if (!playoutFailureCands.isEmpty()) {
                final JsonObject pc = new JsonObject();
                playoutFailureCands.forEach(pc::addProperty);
                o.add("playoutFailureCands", pc);
            }
            if (playoutLastCopyFailure != null) {
                o.addProperty("playoutLastCopyFailure", playoutLastCopyFailure);
            }
            if (copyFidelityLevel != 0) {
                final JsonObject cf = new JsonObject();
                cf.addProperty("level", copyFidelityLevel);
                cf.addProperty("redos", fidelityRedos);
                cf.addProperty("redoRollouts", fidelityRedoRollouts);
                o.add("copyFidelity", cf);
            }
            o.addProperty("candidatesDropped", candidatesDropped);
            o.addProperty("steps", steps);
            o.addProperty("searchMs", searchNanos / 1e6);
            o.addProperty("maxSearchMs", maxSearchNanos / 1e6);
            o.addProperty("msPerSearch", searched == 0 ? 0 : searchNanos / 1e6 / searched);
            if (capped > 0 || rolloutsAborted > 0) {
                o.addProperty("capped", capped);
                o.addProperty("rolloutsAborted", rolloutsAborted);
                if (wallCuts > 0) {
                    // sc-wallguard-1009: only when the wall fired, so a capped game's JSON is otherwise unchanged
                    o.addProperty("wallCuts", wallCuts);
                    o.addProperty("wallStragglers", wallStragglers);
                }
            }
            if (partialDecisions > 0) {
                // search-bones-1015: only when S-b decided a capped search on its finished worlds
                o.addProperty("partialDecisions", partialDecisions);
            }
            o.addProperty("attackDecisions", attackDecisions);
            o.addProperty("attackSearched", attackSearched);
            o.addProperty("attackDeparted", attackDeparted);
            o.addProperty("blockDecisions", blockDecisions);
            o.addProperty("blockSearched", blockSearched);
            o.addProperty("blockDeparted", blockDeparted);
            o.addProperty("combatMs", combatNanos / 1e6);
            final JsonArray each = new JsonArray();
            for (Double d : searchMsEach) {
                each.add(Math.round(d * 10) / 10.0);
            }
            o.add("searchMsEach", each);
            if (dedupKeys > 0) {
                o.addProperty("dedupHits", dedupHits);
                o.addProperty("dedupStepsSaved", dedupStepsSaved);
                o.addProperty("dedupVerifyMismatch", dedupVerifyMismatch);
                o.addProperty("dedupKeys", dedupKeys);
                o.addProperty("dedupKeyMs", dedupKeyNanos / 1e6);
            }
            if (!dedupHitStep.isEmpty()) {
                JsonObject hs = new JsonObject();
                dedupHitStep.forEach(hs::addProperty);
                o.add("dedupHitStep", hs);
            }
            if (reuseChecked > 0) {
                o.addProperty("reuseChecked", reuseChecked);
                o.addProperty("reuseAlive", reuseAlive);
                o.addProperty("reuseSameChoice", reuseSameChoice);
                o.addProperty("reuseHiddenMismatch", reuseHiddenMismatch);
                o.addProperty("reuseHits", reuseHits);
                o.addProperty("reuseStepsSaved", reuseStepsSaved);
                o.addProperty("reuseDecisionsCarried", reuseDecisionsCarried);
                o.addProperty("reuseCompared", reuseCompared);
                o.addProperty("reuseDiffSameWorlds", reuseDiffSameWorlds);
                o.addProperty("reuseValueDiff", reuseValueDiff);
                o.addProperty("reuseFreshCompared", reuseFreshCompared);
                o.addProperty("reuseDiffVsFresh", reuseDiffVsFresh);
                o.addProperty("freshVsFreshDiff", freshVsFreshDiff);
                JsonObject by = new JsonObject();
                reuseAliveBy.forEach(by::addProperty);
                o.add("reuseAliveBy", by);
            }
            if (stackDecisions > 0 && (stackSearched > 0 || stackUnsupported > 0)) {
                o.addProperty("stackDecisions", stackDecisions);
                o.addProperty("stackUnsupported", stackUnsupported);
                o.addProperty("stackSearched", stackSearched);
                o.addProperty("stackDeparted", stackDeparted);
                JsonObject why = new JsonObject();
                stackWhy.forEach(why::addProperty);
                o.add("stackWhy", why);
            }
            if (modelCalls > 0 || modelFallbacks > 0) {
                o.addProperty("modelCalls", modelCalls);
                o.addProperty("modelLeaves", modelLeaves);
                o.addProperty("modelFallbacks", modelFallbacks);
                o.addProperty("modelMs", modelNanos / 1e6);
                o.addProperty("modelUnknownCards", modelUnknownCards);
                o.addProperty("modelDigest", modelDigest);
                o.addProperty("modelCheckpoint", modelCheckpoint);
                if (modelLastError != null) {
                    o.addProperty("modelLastError", modelLastError);
                }
            }
            if (probes.size() > 0) {
                o.add("probes", probes);
            }
            if (priorOn) {
                o.addProperty("priorQualifying", priorQualifying);
                o.addProperty("priorNotQualifying", priorNotQualifying);
                o.addProperty("priorNoRoom", priorNoRoom);
                o.addProperty("priorCalls", priorCalls);
                o.addProperty("priorFailures", priorFailures);
                o.addProperty("priorMs", priorNanos / 1e6);
                o.addProperty("priorMaxMs", priorMaxNanos / 1e6);
                o.addProperty("decisionsWithExtras", decisionsWithExtras);
                o.addProperty("extrasAdded", extrasAdded);
                o.addProperty("extraBest", extraBest);
                o.addProperty("departuresToExtra", departuresToExtra);
                o.addProperty("shadowDecisionsWithExtras", shadowDecisionsWithExtras);
                o.addProperty("shadowExtras", shadowExtras);
                o.addProperty("priorUnknownCards", priorUnknownCards);
                o.addProperty("priorDigest", priorDigest);
                o.addProperty("priorCheckpointSha256", priorCheckpoint);
                if (priorLastError != null) {
                    o.addProperty("priorLastError", priorLastError);
                }
                o.add("priorDecisions", priorDecisions);
            }
            if (tutorOn) {
                o.addProperty("tutorSeen", tutorSeen);
                o.addProperty("tutorLandOnly", tutorLandOnly);
                o.addProperty("tutorSingle", tutorSingle);
                o.addProperty("tutorForgeNone", tutorForgeNone);
                o.addProperty("tutorUnsupported", tutorUnsupported);
                JsonObject why = new JsonObject();
                tutorWhy.forEach(why::addProperty);
                o.add("tutorWhy", why);
                o.addProperty("tutorCalls", tutorCalls);
                o.addProperty("tutorFailures", tutorFailures);
                o.addProperty("tutorMs", tutorNanos / 1e6);
                o.addProperty("tutorSearched", tutorSearched);
                o.addProperty("tutorDeparted", tutorDeparted);
                o.addProperty("tutorShadowWouldDepart", tutorShadowWouldDepart);
                o.addProperty("tutorTop1IsForge", tutorTop1IsForge);
                o.addProperty("tutorRollouts", tutorRollouts);
                o.addProperty("tutorRolloutFailures", tutorRolloutFailures);
                o.addProperty("tutorForcedMissed", tutorForcedMissed);
                o.addProperty("tutorUnknownCards", tutorUnknownCards);
                o.addProperty("tutorDigest", tutorDigest);
                o.addProperty("tutorCheckpointSha256", tutorCheckpoint);
                if (tutorLastError != null) {
                    o.addProperty("tutorLastError", tutorLastError);
                }
                o.add("tutorDecisions", tutorDecisions);
            }
            if (cacDecisions.size() > 0 || cacDropped > 0) {
                o.add("cacDecisions", cacDecisions);
                o.addProperty("cacDropped", cacDropped);
            }
            if (beliefOn) {
                o.addProperty("beliefDecisions", beliefDecisions);
                o.addProperty("beliefSkipped", beliefSkipped);
                o.addProperty("beliefCalls", beliefCalls);
                o.addProperty("beliefFailures", beliefFailures);
                o.addProperty("beliefMs", beliefNanos / 1e6);
                o.addProperty("beliefMaxMs", beliefMaxNanos / 1e6);
                o.addProperty("beliefWorlds", beliefWorlds);
                o.addProperty("beliefCards", beliefCards);
                if (beliefStaticRefreshes > 0) o.addProperty("beliefStaticRefreshes", beliefStaticRefreshes); // live-fixes-1107: absent when 0, as before
                o.addProperty("beliefCreateMs", beliefCreateNanos / 1e6);
                o.addProperty("beliefMismatch", beliefMismatch);
                o.addProperty("beliefPoolSlotsMean", beliefDecisions == 0 ? 0.0 : (double) beliefPoolSlots / beliefDecisions);
                o.addProperty("beliefUnknownCards", beliefUnknownCards);
                o.addProperty("beliefDigest", beliefDigest);
                o.addProperty("beliefCheckpointSha256", beliefCheckpoint);
                if (beliefLastError != null) {
                    o.addProperty("beliefLastError", beliefLastError);
                }
                o.add("beliefCoverage", beliefCoverage);
            }
            if (labelsOn) {
                final JsonObject l = new JsonObject();
                l.addProperty("schema", LABEL_SCHEMA);
                l.addProperty("lines", labels);
                l.addProperty("failures", labelFailures);
                l.addProperty("rootFailures", labelRootFailures);
                if (labelLastError != null) {
                    l.addProperty("lastError", labelLastError);
                }
                o.add("labels", l);
            }
            if (s1On) {
                final JsonObject s = new JsonObject();
                s.addProperty("givenDecisions", givenDecisions);
                s.addProperty("stackSkipped", givenStackSkipped);
                s.addProperty("uncontested", givenUncontested);
                s.addProperty("leafCalls", leafCalls);
                s.addProperty("leafLeaves", leafLeaves);
                s.addProperty("leafFallbacks", leafFallbacks);
                s.addProperty("leafLate", leafLate);
                if (reachLeaves > 0) {
                    s.addProperty("reachLeaves", reachLeaves);
                    s.addProperty("reachHits", reachHits);
                    s.addProperty("reachNaN", reachNaN);
                    s.addProperty("reachSum", Math.round(reachSum * 1000) / 1000.0);
                    if (reachNodes > 0 || reachNameScreens > 0) {
                        s.addProperty("reachNodes", reachNodes);
                        s.addProperty("reachS0Capped", reachS0Capped);
                        s.addProperty("reachSkipped", reachSkipped);
                        s.addProperty("reachNameScreens", reachNameScreens);
                        s.addProperty("reachNameCapped", reachNameCapped);
                    }
                    if (reachDeparts > 0) {
                        s.addProperty("reachDeparts", reachDeparts);
                    }
                }
                s.addProperty("leafMs", leafNanos / 1e6);
                s.addProperty("playoutSeatFailures", playoutSeatFailures);
                s.addProperty("searchCpuMs", searchCpuNanos / 1e6);
                if (leafOppFailures > 0) {
                    s.addProperty("leafOppFailures", leafOppFailures); // bvw-step2-fork-1027: only when one failed
                }
                final JsonArray cpu = new JsonArray();
                for (Double d : searchCpuMsEach) {
                    cpu.add(Math.round(d * 10) / 10.0);
                }
                s.add("searchCpuMsEach", cpu);
                if (leafLastError != null) {
                    s.addProperty("leafLastError", leafLastError);
                }
                if (playoutLastFailure != null) {
                    s.addProperty("playoutLastFailure", playoutLastFailure);
                }
                if (givenMacroDecisions > 0) {
                    s.addProperty("macroDecisions", givenMacroDecisions);
                    s.addProperty("macros", givenMacros);
                    s.addProperty("macroDepartures", givenMacroDepartures);
                    s.addProperty("macroRedundant", givenMacroRedundant);
                }
                o.add("s1", s);
            }
            if (rankerOn) {
                final JsonObject r = new JsonObject();
                r.addProperty("decisions", rankerDecisions);
                r.addProperty("calls", rankerCalls);
                r.addProperty("failures", rankerFailures);
                r.addProperty("ms", rankerNanos / 1e6);
                r.addProperty("maxMs", rankerMaxNanos / 1e6);
                r.addProperty("nonDefault", rankerNonDefault);
                r.addProperty("placeboDraws", placeboDraws);
                r.addProperty("prepFailures", rankerPrepFailures);
                r.addProperty("unknownCards", rankerUnknownCards);
                r.addProperty("digest", rankerDigest);
                final JsonObject dk = new JsonObject();
                rankerDepartKinds.forEach(dk::addProperty);
                r.add("departKinds", dk);
                if (rankerLastError != null) {
                    r.addProperty("lastError", rankerLastError);
                }
                o.add("ranker", r);
            }
            return o;
        }
    }

    /** A candidate, addressable in any copy by the host card's id (ids survive the copy). */
    static final class Cand {
        final boolean pass;
        final int hostId;
        final String desc;
        final boolean land;
        final boolean isDefault;
        final String label;
        /** The prior service's candidate kind: pass | land | cast (a spell) | activate (an activated ability) | other. */
        final String kind;
        /** Target variant: the first single-target slot's target ("P<player index>", "C<card id>", "N" = none); null = Forge AI's. */
        final String tgt;
        /** S-t macro (lane cm-choice-search-1009): the policy play-out seat's forced choice answers (null = none). */
        final Object schedule;
        /** S-t macro: the schedule's label (part of the key), or null. */
        final String macro;
        /**
         * Lane search-bones-1015 (S-b): a turn-plan candidate (opaque here: the policy play-out seat follows it,
         * {@link FirstAction#plan}), or null. A plan candidate has no first action of its own.
         */
        final Object plan;
        /**
         * Lane block-search-1019: a block assignment candidate ({@link BlockPlan}), or null. It has no first priority action:
         * its play-out rewinds the copy to the end of declare attackers and the play-out seat declares it.
         */
        final BlockPlan block;
        /**
         * attack-holdback-1118: an attack declaration candidate ({@link AttackPlan}), or null. It has no first priority
         * action: its play-out rewinds the copy to the end of beginning of combat and the play-out seat declares it.
         */
        final AttackPlan attack;
        /**
         * Lane sdcc-copycast-1114 ({@link Config#copyCast}; null = off or not a may-play ability): the structure of the
         * may-play grant behind this cast or land play ({@link LookaheadSearch#maySig}), and its paid optional costs.
         * Never part of {@link #key}.
         */
        String maySig, mayOpt;

        /** block-search-1019: a block assignment candidate; {@code label} names it, its blocks are its key. */
        Cand(BlockPlan block, String label, boolean isDefault) {
            this.pass = false;
            this.hostId = -1;
            this.desc = "";
            this.land = false;
            this.isDefault = isDefault;
            this.label = "blocks " + label;
            this.kind = "block";
            this.tgt = null;
            this.schedule = null;
            this.macro = null;
            this.plan = null;
            this.block = block;
            this.attack = null;
        }

        /** attack-holdback-1118: an attack declaration candidate; {@code label} names it, its attackers are its key. */
        Cand(AttackPlan attack, String label, boolean isDefault) {
            this.pass = false;
            this.hostId = -1;
            this.desc = "";
            this.land = false;
            this.isDefault = isDefault;
            this.label = "attacks " + label;
            this.kind = "attack";
            this.tgt = null;
            this.schedule = null;
            this.macro = null;
            this.plan = null;
            this.block = null;
            this.attack = attack;
        }

        /** S-b: a turn-plan candidate; {@code label} names it (and is its key). */
        Cand(Object plan, String label) {
            this.pass = false;
            this.hostId = -1;
            this.desc = "";
            this.land = false;
            this.isDefault = false;
            this.label = "plan " + label;
            this.kind = "plan";
            this.tgt = null;
            this.schedule = null;
            this.macro = null;
            this.plan = plan;
            this.block = null;
            this.attack = null;
        }

        private Cand(Cand base, String tgt, String tgtLabel) {
            this.pass = false;
            this.hostId = base.hostId;
            this.desc = base.desc;
            this.land = false;
            this.isDefault = false;
            this.label = base.label + " -> " + tgtLabel;
            this.kind = base.kind;
            this.tgt = tgt;
            this.schedule = null;
            this.macro = null;
            this.plan = null;
            this.block = null;
            this.attack = null;
            this.maySig = base.maySig;
            this.mayOpt = base.mayOpt;
        }

        /** S-t: {@code base} played out with a forced-answer schedule for its choice asks (a macro candidate). */
        Cand(Cand base, Object schedule, String macro) {
            this.pass = base.pass;
            this.hostId = base.hostId;
            this.desc = base.desc;
            this.land = base.land;
            this.isDefault = false;
            this.label = base.label + " + " + macro;
            this.kind = base.kind;
            this.tgt = base.tgt;
            this.schedule = schedule;
            this.macro = macro;
            this.plan = null;
            this.block = null;
            this.attack = null;
            this.maySig = base.maySig;
            this.mayOpt = base.mayOpt;
        }

        Cand(SpellAbility sa, boolean isDefault) {
            this.pass = sa == null;
            this.hostId = sa == null ? -1 : sa.getHostCard().getId();
            this.desc = sa == null ? "" : sa.getDescription();
            this.land = sa != null && sa.isLandAbility();
            this.isDefault = isDefault;
            this.label = sa == null ? "pass" : (sa.getHostCard().getName() + " :: " + trim(sa.toString()));
            this.kind = sa == null ? "pass" : land ? "land" : sa.isSpell() ? "cast" : sa.isActivatedAbility() ? "activate" : "other";
            this.tgt = null;
            this.schedule = null;
            this.macro = null;
            this.plan = null;
            this.block = null;
            this.attack = null;
        }

        /** The prior request's candidate: {id, kind, fid} (fid = the host card's id, absent for pass). */
        JsonObject toPriorJson(int id) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", kind);
            if (!pass) {
                o.addProperty("fid", hostId);
            }
            return o;
        }

        String key() {
            if (plan != null) {
                return label;
            }
            if (block != null) {
                return "block|" + block.key();
            }
            if (attack != null) {
                return "attack|" + attack.key(); // attack-holdback-1118
            }
            return (pass ? "pass" : hostId + "|" + (land ? "L" : "S") + "|" + desc + (tgt == null ? "" : "|T:" + tgt))
                    + (macro == null ? "" : "|M:" + macro);
        }

        private static String trim(String s) {
            return s.length() > 60 ? s.substring(0, 60) : s;
        }
    }

    /** Diagnostics: one "[lookahead-explain]" JSON line per searched decision (candidates, choices, per-world values). */
    private static final boolean EXPLAIN = Boolean.getBoolean("lookahead.explain");
    /** EXPLAIN only: the current decision's candidates' choices as prepared in world 0. */
    private String[] explainChoices;

    /** Schema of one label line ({@link #setLabelSink}). */
    public static final String LABEL_SCHEMA = "mtgx-ei-label/1";
    /**
     * Label dump (lane ei-1004, expert iteration; null = off, the default: nothing is computed and the row schema is
     * unchanged). One JSON object per searched decision: the candidate set in search order (candidate 0 is Forge AI's
     * own answer), every candidate's per-world play-out value, the targets each candidate was played out with (world 0),
     * the deadEtb/zeroX shadow flags, the search's choice and outcome, and the seat's ForgeState
     * ({@link forge.bench.StateEncoder#encode}) taken in the enumeration copy. The search never reads what it writes and
     * the live game is never encoded, so a game with the dump on plays exactly as with it off.
     */
    private java.util.function.Consumer<JsonObject> labelSink = null;
    /** Label dump only: the current decision's candidates' choices and target refs as prepared in world 0. */
    private String[] labelChoices;
    private JsonArray[] labelTargets;
    /** Label dump only: distinct legal candidates of the enumeration past the breadth-B set. */
    private int labelTail = -1;
    private String labelRootError = null;

    /** Turn the label dump on ({@code sink} receives one object per searched decision, on the game thread). */
    public void setLabelSink(java.util.function.Consumer<JsonObject> sink) {
        labelSink = sink;
        stats.labelsOn = sink != null;
    }

    /** EXPLAIN only: an ability's targets (and its sub-abilities'), as Forge AI chose them. */
    static String describeChoices(List<SpellAbility> first) {
        if (first == null || first.isEmpty() || first.get(0) == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (SpellAbility s = first.get(0); s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                sb.append(sb.length() == 0 ? "" : " / ").append(s.getTargets());
            }
        }
        if (first.get(0).isKicked()) {
            sb.append(" kicked");
        }
        return sb.toString();
    }

    private final Config cfg;
    private final Stats stats = new Stats();
    private final ExecutorService pool;
    private final ModelClient model;
    private final PriorClient prior;
    private final TutorRankClient tutor;
    /** Tutor ranking: its own decision counter (the priority search's seeds do not move when it is on). */
    private int tutorIndex = 0;
    private final BeliefClient beliefClient;
    private final BeliefSampler.Cube beliefCube;
    /** EI ranker seat: the service client (null for the placebo or when off) and the seat's registered forty. */
    private final RankerClient ranker;
    private JsonObject rankerDeck = null;
    /** The current searched decision's belief (acting mode), read by {@link #prepare} on the decision thread. */
    private BeliefSampler.Dist decisionBelief = null;
    /** The last searched decision's belief (acting or shadow); tests read it. */
    BeliefSampler.Dist lastBelief = null;
    /** The candidate list played out at the last searched decision (B plus any HX extras); tests read it. */
    List<Cand> lastCandidates = null;
    /** deadEtb SHADOW: keys of the last enumeration's dead-ETB candidates (kept in the list); tests read it. */
    final Set<String> deadEtbKeys = new HashSet<>();
    /** zeroX SHADOW: keys of the last enumeration's zero-X candidates (kept in the list); tests read it. */
    final Set<String> zeroXKeys = new HashSet<>();
    /** crewNoop SHADOW: keys of the last enumeration's no-op Crew candidates (kept in the list); tests read it. */
    final Set<String> crewNoopKeys = new HashSet<>();
    /** deadAltCost SHADOW: keys of the last enumeration's dead alternative-cost candidates (kept); tests read it. */
    final Set<String> deadAltCostKeys = new HashSet<>();
    /** deadPump SHADOW: keys of the last enumeration's dead pump candidates (kept); tests read it. */
    final Set<String> deadPumpKeys = new HashSet<>();
    /** deadFaceUp SHADOW: keys of the last enumeration's dead turn-up candidates (kept); tests read it. */
    final Set<String> deadFaceUpKeys = new HashSet<>();
    /** line-own-target-1119 (ownHarm on or shadow): this K8 decision's refused departures ({@link #enumerate}), demoted. */
    final Set<String> ownHarmKeys = new HashSet<>();
    /**
     * deadFaceUp (null = off, or no turn-up candidate at the last guarded enumeration): that enumeration's turn-up
     * candidates, dropped (ON) or flagged (SHADOW), stand-asides by reason and ms ({@link DfuTally#finish}); the given search puts it
     * in its result and explain row.
     */
    JsonObject deadFaceUpLast = null;
    private JsonObject modelDeck = null;
    private int decisionIndex = 0;
    private int departuresTurn = -1;
    private int departuresThisTurn = 0;
    private final Map<String, Integer> departureCounts = new TreeMap<>();
    private boolean fidelityArmed = true;
    /**
     * The current searched decision's wall budget (null = none: no budget, or no decision being searched). Each
     * decision gets its own {@link Budget}; a play-out holds the one of the decision it belongs to.
     */
    private volatile Budget budget = null;
    /**
     * Tests only (null by default, and then nothing is called): run on the play-out's thread before each main-loop step
     * of {@link #play}. A hook that blocks stands for a play-out stuck inside one step.
     */
    java.util.function.Consumer<Game> playoutStepHook = null;
    /** Forge AI's own time for the current decision (set by the controller before {@link #decide}), for the decision log. */
    private long forgeNanos = -1L;

    /** Fidelity probe: the live game's fingerprint at the watched turn, compared with a truth rollout. */
    private FidelityWatch liveWatch = null;

    // ------------------------------------------------------------------ S1 hooks (lane s1-search-1007)

    /**
     * S1: the first action of a play-out as a play-out seat must take it (the candidate searched): pass, or the ability
     * of the host card {@code hostId} whose description is {@code desc} (a land play when {@code land}).
     */
    public static final class FirstAction {
        public final boolean pass;
        public final int hostId;
        public final String desc;
        public final boolean land;
        /** S-t (lane cm-choice-search-1009): a macro candidate's forced choice answers (null = none). */
        public final Object schedule;
        /**
         * S-t: set only while {@link #decideGiven(Game, Player, List, int, ChoiceExpander)} expands choices: the
         * candidate's index in the searched list, the world, and whether this play-out is the candidate's probe (world 0
         * of the base candidates: its choice asks are recorded).
         */
        public int candIndex = -1;
        public int world = -1;
        public boolean probe = false;
        /** Lane sc-wallguard-1009: the budget of the decision this play-out belongs to (null = none). */
        Budget budget;
        /** Copy fidelity: a may-play spell may also match by its base description ({@link #matchesMayPlay}). */
        public boolean mayPlayLoose = false;
        /** step3-playout-fixes-1028: this play-out is an OPR roll-out ({@link #WORLD_OPR}; its seats keep their cap). */
        public boolean oprRollout = false;
        /**
         * step3-playout-fixes-1028 (review #130 S3-2; set only by {@link Config#replayMayPlayLoose} outside a fidelity
         * pass): the loose match compares the text before the LAST " by " exactly ({@link #sameMayPlayText}), and the
         * play-out seat accepts it only when exactly one menu entry matches.
         */
        public boolean mayPlayUnique = false;
        /** Lane sdcc-copycast-1114: the candidate's may-play structure and optional costs (null = off / not may-play). */
        public final String maySig, mayOpt;
        /** Lane search-bones-1015 (S-b): the turn-plan this play-out follows (null = a single first action). */
        public final Object plan;
        /** S-b: what became of the plan in this play-out: 0 = not finished, 1 = played to its end, 2 = broke. */
        public volatile int planState = 0;
        /**
         * S-b proposals: this play-out records a sampled plan (opaque: the play-out seat's recorder), plays until our
         * turn ends, and is not a searched candidate. Null otherwise.
         */
        public Object recorder;
        /**
         * Lane block-search-1019: the block assignment this play-out's seat declares at the copy's declare-blockers step
         * (opaque: {@link LookaheadSearch#applyBlocks}); null = a priority candidate.
         */
        public final Object block;
        /**
         * attack-holdback-1118: the attack declaration this play-out's seat declares at the copy's declare-attackers step
         * (opaque: {@link LookaheadSearch#applyAttacks}); null = not an attack candidate.
         */
        public final Object attack;

        FirstAction(Cand c) {
            this.pass = c.pass;
            this.hostId = c.hostId;
            this.desc = c.desc;
            this.land = c.land;
            this.schedule = c.schedule;
            this.plan = c.plan;
            this.block = c.block;
            this.attack = c.attack;
            this.maySig = c.maySig;
            this.mayOpt = c.mayOpt;
        }

        /**
         * Lane sdcc-copycast-1114 (only with {@link Config#copyCast}): {@code sa} is the same card's may-play ability
         * with this candidate's grant structure; with {@code withOpt} its paid optional costs are the candidate's too.
         */
        public boolean matchesMaySig(SpellAbility sa, boolean withOpt) {
            return maySig != null && sa != null && !pass && sa.getHostCard() != null && sa.getHostCard().getId() == hostId
                    && sa.isLandAbility() == land && maySig.equals(LookaheadSearch.maySig(sa))
                    && (!withOpt || java.util.Objects.equals(mayOpt, LookaheadSearch.mayOpt(sa)));
        }

        /**
         * Lane sc-wallguard-1009: this play-out's decision is past its wall budget (or the wall stopped it). A policy
         * play-out seat checks it before every service request and stops the play-out there (the play-out then counts
         * as stopped by the budget); always false without a budget.
         */
        public boolean overBudget() {
            final Budget b = budget;
            return b != null && b.over();
        }

        /** The ability of {@code sa}'s menu entry is this action (host id, land flag, description). */
        public boolean matches(SpellAbility sa) {
            if (sa == null) {
                return pass;
            }
            return !pass && sa.getHostCard() != null && sa.getHostCard().getId() == hostId && sa.isLandAbility() == land
                    && desc.equals(sa.getDescription());
        }

        /**
         * Copy fidelity (only when {@link #mayPlayLoose}): {@code sa} is a may-play spell or land of the same card whose
         * description differs only in the may-play suffix ({@link LookaheadSearch#sameMayPlay}).
         */
        public boolean matchesMayPlay(SpellAbility sa) {
            return mayPlayLoose && sa != null && !pass && sa.getHostCard() != null && sa.getHostCard().getId() == hostId
                    && sa.isLandAbility() == land && (mayPlayUnique ? sa.getMayPlay() != null
                    && sameMayPlayText(desc, sa.getDescription()) : sameMayPlay(desc, sa));
        }
    }

    /**
     * S1: one play-out's horizon probe. With a value leaf the play-out runs past the start of the horizon turn to the
     * searching seat's first priority in it, where {@link SearchHooks#captureLeaf} takes the leaf observation (a decision
     * point, as the value head's training rows are); the play-out seat (Forge AI or a policy seat) calls
     * {@link #onPriority} whenever the seat gets priority.
     */
    public static final class LeafProbe {
        private final SearchHooks hooks;
        private final Object ctx;
        TurnWatch watch;
        Object payload;
        volatile boolean done;
        /**
         * bvw-step2-fork-1027 (S2T, {@link Config#leafOpp}; null otherwise): the opponent whose own frame is taken at the
         * same moment as the searching seat's, and that frame.
         */
        Player opp;
        Object payloadOpp;
        /** solver-distill-cc-1109: {@link SearchHooks#leafReach} at the leaf (null = off or failed). */
        double[] reach;
        /** reach-verify-1115: the play-out's decision budget (null = none); reach.verify's engine checks stop at it. */
        Budget budget;

        LeafProbe(SearchHooks hooks, Object ctx) {
            this.hooks = hooks;
            this.ctx = ctx;
        }

        /**
         * S2T: the opponent's frame, or null when taking it failed (never a failure of the play-out: without leafSym the
         * play-out and its value are step 1's whether v_o is logged or not; with it a missing frame is a fallback).
         */
        Object captureOpp(Game g) {
            try {
                return hooks.captureLeaf(ctx, g, opp);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** solver-distill-cc-1109: the reach at the leaf, never a failure of the play-out. */
        void takeReach(Game g, Player me) {
            final Budget prev = ForcedWinSolver.VERIFY_BUDGET.get();
            ForcedWinSolver.VERIFY_BUDGET.set(budget); // reach-verify-1115: read only by reach.verify's checks
            try {
                reach = hooks.leafReach(ctx, g, me);
            } catch (RuntimeException | StackOverflowError e) {
                reach = null;
            } finally {
                ForcedWinSolver.VERIFY_BUDGET.set(prev);
            }
        }

        /** The searching seat has priority in play-out {@code g}: take the leaf there if the horizon turn has begun. */
        public void onPriority(Game g, Player me) {
            if (!done && watch != null && watch.reached) {
                payload = hooks.captureLeaf(ctx, g, me);
                takeReach(g, me);
                if (opp != null) {
                    // S2T: the opponent's own observation at the same state (its seat's frame, never ours)
                    payloadOpp = captureOpp(g);
                }
                done = true;
            }
        }
    }

    /** S1: a policy-piloted searching seat inside one play-out. */
    public interface PlayoutSeat {
        PlayerControllerAi controller();

        /** Why the seat could not play this play-out (the play-out then fails), or null. */
        String failure();
    }

    /**
     * S1 (lane s1-search-1007; all null by default, and then nothing below runs): what a learned policy and value
     * supply to {@link #decideGiven}. Every method is called only when its option is set with {@link #setHooks}.
     */
    public interface SearchHooks {
        /** Only recorded observation knowledge, never an inference from the opponent's actual hidden cards. */
        default boolean remembersHandCard(Game live, Player observer, Card card) { return false; }

        /** Decision thread, inside the copy's preparation (after the world draw): a per-copy context, or null. */
        Object onCopy(Game copy, Player me);

        /** Play-out thread: the leaf payload (an encoded observation of {@code me}) at the probe point. */
        Object captureLeaf(Object copyCtx, Game g, Player me);

        /** Decision thread: one value per leaf payload, P(the seat wins) in [0, 1], in order; null = failed. */
        double[] leafValues(List<Object> payloads);

        /**
         * solver-distill-cc-1109 (REACH; null = off, the default, and then nothing changes): play-out thread, at the leaf,
         * {@code [reach of me, reach of the opponent]} ({@link ForcedWinSolver#reach}; NaN where not computed).
         */
        default double[] leafReach(Object copyCtx, Game g, Player me) {
            return null;
        }

        /** solver-distill-cc-1109: the leaf value given its reach (identity by default). */
        default double combineReach(double v, double[] reach) {
            return v;
        }

        /**
         * solver-distill-cc-1109 (report only; nothing by default): decision thread, one value leaf with its frame, its
         * value before the reach and its reach, for a sink of (leaf frame, reach) rows.
         */
        default void noteLeaf(Object payload, double v, double[] reach) {
        }

        /** solver-distill-cc-1109: the part of a leaf's value its own reach contributes (0 by default). */
        default double reachPart(double[] reach) {
            return 0.0;
        }

        /** solver-distill-cc-1109: the mean reach-part gain that departs without the z test (0 = off, the default). */
        default double reachDepart() {
            return 0.0;
        }

        /**
         * sdcc-c1-priogf-1117 (called only with {@link Config#prioGf} > 0): decision thread, the goldfish score of copy
         * {@code g} for {@code me} ({@link ForcedWinSolver#goldfish}: gamma^turn of its soonest unopposed line, over
         * {@code horizon} of our turns; 0 = no line; NaN = not computed). {@code copyCtx} is this hook's
         * {@link #onCopy} context (the seat's knowledge: only its known library top is read), {@code seed} the sampled
         * orders' seed (the same for every candidate of one decision), {@code share} the number of positions the
         * decision scores (a per-decision node allowance is shared among them).
         */
        default double prioGoldfish(Object copyCtx, Game g, Player me, int horizon, long seed, int share) {
            return Double.NaN;
        }

        /** Decision thread: why the last {@link #leafValues} returned null (the static-fallback line names it), or null. */
        default String leafError() {
            return null;
        }

        /** Play-out thread: the searching seat's controller for a policy play-out that starts with {@code first}. */
        PlayoutSeat playoutSeat(Object copyCtx, Game g, Player me, FirstAction first, long seed, LeafProbe probe);

        /**
         * bvw-step2-fork-1027 (S2T, {@link Config#opponentPolicy}; never called otherwise): play-out thread, the policy
         * seat of opponent {@code opp} in play-out {@code g} (its own observation only), or null (none: the play-out
         * fails).
         */
        default PlayoutSeat opponentSeat(Object copyCtx, Game g, Player opp, long seed) {
            return null;
        }

        /**
         * step3-playout-fixes-1028: as {@link #opponentSeat(Object, Game, Player, long)}, with the play-out's own first
         * action (its decision's budget, whether it is an OPR roll-out). The default ignores it.
         */
        default PlayoutSeat opponentSeat(Object copyCtx, Game g, Player opp, long seed, FirstAction first) {
            return opponentSeat(copyCtx, g, opp, seed);
        }

        /**
         * Review #123 S2-1 (decision thread, true-world re-plays only): which library cards of {@code live} some seat
         * knows (its seat knowledge, e.g. after a scry), or null (Forge's visibility only).
         */
        default java.util.function.Predicate<Card> knownToAnySeat(Game live) {
            return null;
        }
    }

    private SearchHooks hooks = null;

    /**
     * J1 (lane block-search-1019; null = none, the default): the label of the candidate the next given / block decision
     * must play when the search would play another ({@link AiReplay}). Taken (cleared) by the decision it is set for.
     */
    public volatile String forcePlayed = null;
    /** The force of the decision in progress (set from {@link #forcePlayed} at its start). */
    private String decisionForce = null;
    /**
     * J1: the index the caller's next decision line will carry (RlLiveSeat's searchedLines), written on every given and
     * block explain row as {@code line}; null = none (the row is unchanged).
     */
    public volatile java.util.function.IntSupplier explainLine = null;
    /**
     * Tests only (J1; null = none): at the decision whose {@link #explainLine} value it accepts, the candidate to play
     * instead of the search's choice (an artificial "cut" that changed a live choice). Never set outside tests.
     */
    static volatile java.util.function.IntUnaryOperator testOverride = null;
    /**
     * Tests only (J1; null = none): before the given decision whose {@link #explainLine} value it accepts, the search's
     * own decision counter advances once more, as an uncontested, skipped or failed search advances it live without a
     * decision line. Never set outside tests.
     */
    static volatile java.util.function.IntPredicate testBump = null;

    /** J1: the candidate this search would play for {@code best}, without changing anything (overBudget / shadow / guard). */
    private int wouldPlay(final List<Cand> cands, final int best, final boolean overBudget, final int provenPick,
            final PhaseHandler ph, final Game live) {
        if (overBudget || best == 0 || cfg.shadow) {
            return 0;
        }
        final String key = cands.get(best).key() + "@" + ph.getPhase() + "#" + live.getStack().size();
        final int seenN = departureCounts.getOrDefault(key, 0);
        if (provenPick < 0 && (departuresThisTurn >= cfg.maxDeparturesPerTurn || seenN >= cfg.maxSameDepartures)) {
            return 0;
        }
        return best;
    }

    /** J1: the K8 seat's live decision at line {@code index} ({@link AiReplay#current()}), or null. */
    private static AiReplay.Mark replayMark(final int index) {
        final AiReplay r = AiReplay.current();
        return r == null ? null : r.at(index);
    }

    /** J1: the one candidate with this label; -1 when none has it OR when more than one does (an ambiguous key). */
    private static int indexOfLabel(final List<Cand> cands, final String label) {
        int found = -1;
        for (int c = 0; c < cands.size(); c++) {
            if (label.equals(cands.get(c).label)) {
                if (found >= 0) {
                    return -1; // review S2: an ambiguous key is never forced (the caller counts a forcedMiss)
                }
                found = c;
            }
        }
        return found;
    }

    /** J1: how many candidates carry this label. */
    private static int labelCount(final List<Cand> cands, final String label) {
        int n = 0;
        for (Cand c : cands) {
            n += label.equals(c.label) ? 1 : 0;
        }
        return n;
    }
    private boolean hookLeaf = false, hookPlayout = false;
    /** S1: the CPU time of pool tasks (play-outs on worker threads) is summed here while it is non-null. */
    private volatile java.util.concurrent.atomic.AtomicLong poolCpu = null;
    private static final java.lang.management.ThreadMXBean TMX = java.lang.management.ManagementFactory.getThreadMXBean();

    /**
     * S1: install the learned hooks. {@code valueLeaf}: non-terminal play-outs are scored by
     * {@link SearchHooks#leafValues} at the seat's first priority in the horizon turn (terminal ones 1 / 0 / 0.5);
     * {@code policyPlayouts}: the searching seat's play-out controller comes from {@link SearchHooks#playoutSeat}.
     */
    public void setHooks(SearchHooks h, boolean valueLeaf, boolean policyPlayouts) {
        if (h == null && (valueLeaf || policyPlayouts)) {
            throw new IllegalArgumentException("S1 hooks: a value leaf or policy play-outs need hooks");
        }
        this.hooks = h;
        this.hookLeaf = h != null && valueLeaf;
        this.hookPlayout = h != null && policyPlayouts;
    }

    public LookaheadSearch(Config cfg) {
        this(cfg, null);
    }

    /** Tests: {@code testRanker} stands in for the ranker service client (no health check). */
    LookaheadSearch(Config cfg, RankerClient testRanker) {
        if (cfg.policyRollouts) {
            throw new IllegalStateException("policyRollouts (L2 step 2) is not built: it needs an L2 PASS and its own predeclaration");
        }
        this.cfg = cfg;
        final int nThreads = effectiveThreads(cfg);
        if (nThreads > 1) {
            final AtomicInteger n = new AtomicInteger();
            pool = Executors.newFixedThreadPool(nThreads, r -> {
                // "Game" prefix: ThreadUtil.isGameThread() must hold so GameAction.invoke runs inline.
                Thread t = new Thread(r, "Game-lookahead-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        } else {
            pool = null;
        }
        model = cfg.modelUrl == null ? null : new ModelClient(cfg.modelUrl, cfg.modelTimeoutMs);
        if (cfg.deadEtb != AiFixes.Mode.OFF) {
            stats.deadEtbMode = cfg.deadEtb.key();
        }
        if (cfg.zeroX != AiFixes.Mode.OFF) {
            stats.zeroXMode = cfg.zeroX.key();
        }
        if (cfg.crewNoop != AiFixes.Mode.OFF) {
            stats.crewNoopMode = cfg.crewNoop.key();
        }
        if (cfg.deadAltCost != AiFixes.Mode.OFF) {
            stats.deadAltCostMode = cfg.deadAltCost.key();
        }
        if (cfg.deadPump != AiFixes.Mode.OFF) {
            stats.deadPumpMode = cfg.deadPump.key();
        }
        if (cfg.deadFaceUp != AiFixes.Mode.OFF) {
            stats.deadFaceUpMode = cfg.deadFaceUp.key();
        }
        if (cfg.ownHarm != AiFixes.Mode.OFF) {
            stats.ownHarmMode = cfg.ownHarm.key(); // line-own-target-1119
        }
        if (cfg.lethalBlock != AiFixes.Mode.OFF) {
            stats.lethalBlockMode = cfg.lethalBlock.key();
        }
        if (cfg.dudCast != AiFixes.Mode.OFF) {
            stats.dudCastMode = cfg.dudCast.key();
        }
        if (cfg.copyEot != AiFixes.Mode.OFF) {
            stats.copyEotMode = cfg.copyEot.key();
        }
        stats.copyFidelityLevel = cfg.copyFidelity;
        if (cfg.departMedian != AiFixes.Mode.OFF) {
            stats.departMedianMode = cfg.departMedian.key();
        }
        if (cfg.priorOn()) {
            if (cfg.priorExtra < 1) {
                throw new IllegalStateException("priorShadow needs priorExtra >= 1 (how many extras would be added)");
            }
            // The pin: refuse to search against any checkpoint but the configured one (once per JVM and service).
            checkPriorService(cfg);
            prior = new PriorClient(cfg.priorUrl, cfg.priorTimeoutMs);
            stats.priorOn = true;
        } else {
            prior = null;
        }
        if (cfg.cac != null) {
            // review #143 S3: refuse a choose-a-card spec when the config is read, never at the first priority
            final ChooseACard.Config cc = ChooseACard.Config.fromJson(cfg.cac);
            if (cc.mode == ChooseACard.Config.Mode.ON && !cc.testUnsafe) {
                throw new IllegalArgumentException("chooseACard: mode on needs an RL search seat (the value tower and the "
                        + "solver check); a look-ahead (K8) seat runs it in shadow only");
            }
            if (cc.mode != ChooseACard.Config.Mode.OFF && !cfg.beliefOn()) {
                throw new IllegalArgumentException("chooseACard: needs a belief (the copies re-draw the opponent's hidden "
                        + "cards); set belief");
            }
        }
        if (cfg.tutorOn()) {
            if (cfg.tutorRank < 1) {
                throw new IllegalStateException("tutorShadow needs tutorRank >= 1 (how many ranked names are played out)");
            }
            checkTutorService(cfg);
            tutor = new TutorRankClient(cfg.tutorUrl, cfg.tutorTimeoutMs);
            stats.tutorOn = true;
        } else {
            tutor = null;
        }
        if (cfg.rankerOn()) {
            if (cfg.rankerUrl != null && cfg.placeboRate > 0) {
                throw new IllegalStateException("ranker: a seat is the ranker or its placebo, not both");
            }
            if (cfg.placeboRate < 0 || cfg.placeboRate > 1) {
                throw new IllegalStateException("ranker: placeboRate must be in [0, 1]");
            }
            if (cfg.rankerUrl != null && testRanker != null) {
                ranker = testRanker;
            } else if (cfg.rankerUrl != null) {
                RankerClient.checkHealth(cfg.rankerUrl, cfg.rankerCheckpointSha256, cfg.rankerTimeoutMs);
                ranker = new RankerClient(cfg.rankerUrl, cfg.rankerTimeoutMs);
            } else {
                ranker = null;
            }
            stats.rankerOn = true;
        } else {
            ranker = null;
        }
        if (cfg.beliefOn()) {
            if (!"off".equals(cfg.belief) && !"human".equals(cfg.belief) && !"uniform".equals(cfg.belief)
                    && !cfg.codraft() && !cfg.deck()) {
                throw new IllegalStateException("belief must be off, human, uniform, codraft or deck: " + cfg.belief);
            }
            if (cfg.deck() && cfg.beliefDeckModel == null) {
                // belief-n11-1030: the learned deck belief needs its model
                throw new IllegalStateException("belief=deck needs its model (beliefDeckModel)");
            }
            if (cfg.codraft() && (cfg.beliefPriorTable == null || !(cfg.beliefPriorGain >= 0))) {
                // bvw-step2-fork-1027: the co-draft prior needs its table (and a gain >= 0)
                throw new IllegalStateException("belief=codraft needs its prior table and a gain >= 0");
            }
            if (cfg.reuse) {
                // A carried world keeps live card ids; a belief world holds cards the live game does not have.
                throw new IllegalStateException("belief does not combine with reuse (carried worlds hold live card ids)");
            }
            if (cfg.beliefShadow && cfg.deck()) {
                // review #150 S2-4: the shadow builds uniform worlds, so it would never exercise the deck model
                throw new IllegalStateException("beliefShadow does not combine with belief=deck");
            }
            if (cfg.beliefShadow && "off".equals(cfg.belief)) {
                throw new IllegalStateException("beliefShadow needs belief=human or uniform (the belief to build and discard)");
            }
            beliefCube = checkBelief(cfg);
            beliefClient = cfg.beliefHuman() ? new BeliefClient(cfg.beliefUrl, cfg.beliefTimeoutMs) : null;
            stats.beliefOn = true;
        } else {
            beliefCube = null;
            beliefClient = null;
        }
    }

    /**
     * Belief: load the pinned cube file (resolving every name up front) and, for belief=human, check the service's
     * checkpoint pin (once per JVM and service). Throws {@link IllegalStateException}; a runner calls it at start.
     */
    public static BeliefSampler.Cube checkBelief(Config c) {
        final BeliefSampler.Cube cube = c.beliefPool != null ? BeliefSampler.cubeOf(c.beliefPool, c.beliefBasics)
                : BeliefSampler.loadCube(c.beliefCube, c.beliefCubeSha256, c.beliefBasics);
        if (c.beliefHuman()) {
            BeliefClient.checkHealth(c.beliefUrl, c.beliefCheckpointSha256, c.beliefTimeoutMs);
        }
        return cube;
    }

    /** Tutor ranking: check the ranker's {@code /v1/health} checkpoint against the pin (once per JVM and service). */
    public static String checkTutorService(Config c) {
        return TutorRankClient.checkHealth(c.tutorUrl, c.tutorCheckpointSha256, c.tutorTimeoutMs);
    }

    /**
     * HX: check the prior service's {@code /v1/health} checkpoint against {@link Config#priorCheckpointSha256} (once
     * per JVM and service). Throws {@link IllegalStateException} on a missing pin, an unreachable service or a
     * mismatch; a runner calls it at start and exits non-zero.
     */
    public static String checkPriorService(Config c) {
        return PriorClient.checkHealth(c.priorUrl, c.priorCheckpointSha256, c.priorTimeoutMs);
    }

    /** r2-choose-a-card-1029: the seat's choose-a-card engine (null unless {@link Config#cac}); built on first use. */
    private ChooseACard cacEngine = null;

    public synchronized ChooseACard chooseACard() {
        if (cfg.cac == null) {
            return null;
        }
        if (cacEngine == null) {
            final ChooseACard.Config c = ChooseACard.Config.fromJson(cfg.cac);
            cacEngine = new ChooseACard(c, hooks != null ? ChooseACard.of(hooks) : StaticValuer.INSTANCE, null);
            // review S1-2: the copies' opponent hidden cards come from this seat's belief (null = no belief: install refuses)
            cacEngine.setRedraw(solverRedraw());
            // review S2-2: every decision entry goes to this game's stats (the bench row), bounded
            cacEngine.setSink(e -> {
                synchronized (stats) {
                    if (stats.cacDecisions.size() < 512) {
                        stats.cacDecisions.add(e);
                    } else {
                        stats.cacDropped++;
                    }
                }
            });
        } else if (hooks != null) {
            cacEngine.setValuer(ChooseACard.of(hooks));
        }
        return cacEngine;
    }

    public Config getConfig() {
        return cfg;
    }

    /**
     * line-own-target-1119 ({@link Config#ownHarm}): why {@code me} must not play the candidate {@code sa} now ({@link
     * LineOwnTarget#refuseOpen}: its harm can land only on permanents {@code me} controls, or its destroy only on
     * indestructible ones); null = it may, and for pass. Never throws.
     */
    public static String ownHarmWhy(final Player me, final SpellAbility sa) {
        if (me == null || sa == null) {
            return null;
        }
        if (sa.getActivatingPlayer() != null) {
            return LineOwnTarget.refuseOpen(me, sa);
        }
        // no activator yet: set it for the check, then put every part's own back (the check changes nothing; review 1, S1 7)
        final List<SpellAbility> parts;
        final List<Player> was = new ArrayList<>();
        try {
            parts = LineOwnTarget.tree(sa);
            for (SpellAbility p : parts) {
                was.add(p.getActivatingPlayer());
            }
        } catch (RuntimeException e) {
            return null;
        }
        try {
            sa.setActivatingPlayer(me);
            return LineOwnTarget.refuseOpen(me, sa);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                // pre-order: each part's own value overrides what its parent trickled down
                for (int i = 0; i < parts.size(); i++) {
                    parts.get(i).setActivatingPlayer(was.get(i));
                }
            } catch (RuntimeException e) {
                // a restore that fails leaves the activator set, as the check before review 1 did
            }
        }
    }

    /** ownHarm: one refusal of {@code sa} counted in this search's stats, by its card's name. */
    public void ownHarmCounted(final SpellAbility sa) {
        final String n = sa == null || sa.getHostCard() == null ? "?" : sa.getHostCard().getName();
        synchronized (stats) {
            stats.ownHarmCards.merge(n, 1L, Long::sum);
        }
    }

    /** ownHarm (K8): a candidate's spell or ability, whatever its target ({@link #targetVariants} keeps host and text). */
    private static String ownHarmHost(final Cand c) {
        return c.hostId + "|" + c.land + "|" + c.desc;
    }

    /** ownHarm (review 3; null = none): the next given decision's refused entries, and whether ON applies the demotion. */
    private Set<Integer> demoteNext;
    private boolean demoteApply;
    /** The decision in progress's (taken from {@link #demoteNext} at its start). */
    private Set<Integer> decisionDemote;
    private boolean decisionDemoteApply;

    /**
     * ownHarm (review 3): the entries of the next {@link #decideGiven} call's given list (indices into it) the rule
     * refused. That decision demotes them ({@link Config#ownHarm}): a refused pick is played only if it is better than
     * the best candidate not refused by the departZ test and {@link Config#OWN_HARM_FLOOR}; {@code apply} false
     * (SHADOW) computes and records it, the decision unchanged. Taken by that decision (a skipped one too).
     */
    public void ownHarmDemote(final Set<Integer> givenIdx, final boolean apply) {
        demoteNext = givenIdx == null || givenIdx.isEmpty() ? null : new HashSet<>(givenIdx);
        demoteApply = apply;
    }

    /**
     * ownHarm (review 4): the stand-in the policy seat plays for its refused own choice when the search gave no verdict
     * (RlSearch) is a departure for the loop guard, with the same per-turn and identical-departure limits as any
     * departure ({@link Config#maxDeparturesPerTurn}, {@link Config#maxSameDepartures}). True = admitted and counted;
     * false = the guard blocks it (counted as a loop-guard hit).
     */
    public boolean ownHarmStandInAdmit(final Game live, final String label) {
        final PhaseHandler ph = live.getPhaseHandler();
        if (ph.getTurn() != departuresTurn) {
            departuresTurn = ph.getTurn();
            departuresThisTurn = 0;
            departureCounts.clear();
        }
        final String key = "standIn|" + label + "@" + ph.getPhase() + "#" + live.getStack().size();
        final int seen = departureCounts.getOrDefault(key, 0);
        if (departuresThisTurn >= cfg.maxDeparturesPerTurn || seen >= cfg.maxSameDepartures) {
            stats.loopGuard++;
            stats.ownHarmGuard++;
            return false;
        }
        departuresThisTurn++;
        departureCounts.put(key, seen + 1);
        return true;
    }

    /**
     * Tests only (null = none; never set outside tests): overrides whether this decision's values count as being on the
     * win-probability scale for ownHarm (review 4), so a test with stubbed values ({@link #testValues}) can drive the
     * demotion in a seat whose leaf is static.
     */
    public static volatile Boolean testScale = null;

    /**
     * ownHarm's test (review 3): is candidate {@code a} (refused) better than candidate {@code b} (not refused)? Over the
     * worlds both finished ({@code f} null = every world; a non-finite value never counts), the mean paired difference
     * a - b must pass the departZ test (above zero and above z standard errors; with z off, above zero; with one world
     * in a one-world decision, above zero; with one paired world in a larger decision, never) and be at least {@link
     * Config#OWN_HARM_FLOOR}, as must the difference of their means {@code ev}. Returns {mean, se, worlds, confident,
     * floor} (1 = true).
     */
    public static double[] ownHarmBetter(final double[][] v, final boolean[][] f, final double[] ev, final int a, final int b,
            final int k, final double z) {
        double sum = 0;
        int n = 0;
        for (int w = 0; w < k; w++) {
            if ((f == null || f[a][w] && f[b][w]) && Double.isFinite(v[a][w]) && Double.isFinite(v[b][w])) {
                sum += v[a][w] - v[b][w];
                n++;
            }
        }
        final double mean = n > 0 ? sum / n : Double.NaN;
        double se = Double.NaN;
        if (n > 1) {
            double ss = 0;
            for (int w = 0; w < k; w++) {
                if ((f == null || f[a][w] && f[b][w]) && Double.isFinite(v[a][w]) && Double.isFinite(v[b][w])) {
                    final double d = v[a][w] - v[b][w] - mean;
                    ss += d * d;
                }
            }
            se = Math.sqrt(ss / (n - 1)) / Math.sqrt(n);
        }
        final boolean confident = n > 0 && mean > 0
                && (!(z > 0) || (n > 1 ? mean > z * se : k < 2));
        final double evGap = ev == null ? mean : ev[a] - ev[b];
        final boolean floor = n > 0 && mean >= Config.OWN_HARM_FLOOR && evGap >= Config.OWN_HARM_FLOOR;
        return new double[] {mean, se, n, confident ? 1 : 0, floor ? 1 : 0};
    }

    /** ownHarm: one demotion test's note (the refused pick, the candidate it was tested against, the result). */
    private static JsonObject ownHarmTestNote(final String refused, final double refusedEv, final String against,
            final double againstEv, final double[] t, final double z) {
        final JsonObject o = new JsonObject();
        o.addProperty("refused", refused);
        o.addProperty("refusedEv", refusedEv);
        o.addProperty("against", against);
        o.addProperty("againstEv", againstEv);
        if (Double.isFinite(t[0])) {
            o.addProperty("gain", t[0]);
        }
        if (Double.isFinite(t[1])) {
            o.addProperty("se", t[1]);
        }
        o.addProperty("worlds", (int) t[2]);
        o.addProperty("z", z);
        o.addProperty("floor", Config.OWN_HARM_FLOOR);
        o.addProperty("confident", t[3] > 0);
        o.addProperty("aboveFloor", t[4] > 0);
        o.addProperty("better", t[3] > 0 && t[4] > 0);
        return o;
    }

    /**
     * ownHarm's demotion in a given decision (review 3): the decision, given the refused candidates {@code dem} (indices
     * among the searched ones) and the search's own pick {@code best}. Nothing changes unless the pick or the default is
     * refused. The best candidate not refused: with the default not refused, the normal rule over the candidates not
     * refused (a proven win among them, else the same chooser and gates, the default the baseline); with the default
     * refused, the candidate not refused with the largest mean (a proven win first; ties to the earlier). A refused pick
     * stays only if it is {@link #ownHarmBetter} than that one, which otherwise is the decision; with none (every other
     * candidate refused or failed) it stays and the caller decides. The default refused and the pick not, the best
     * candidate not refused is the decision (the search measured its departure against the refused default). Counts the
     * tests and records {@link GivenResult#ownHarmDemotion}; reads the play-outs only.
     */
    private int ownHarmDemotion(final GivenResult res, final List<Cand> cands, final List<Integer> at, final double[][] values,
            final boolean[][] finished, final double[] ev, final boolean[] okArg, final boolean[] dem, final int n, final int k,
            final int best, final int provenPick, final boolean[] provenWin, final double[][] rpArg, final double rootP) {
        if (!dem[best] && !dem[0]) {
            return best;
        }
        // review 4: the floor and the test read win probabilities: with a static leaf or any leaf fallback in this
        // decision, ownHarm takes no action (key-off behavior: no demotion, and RlSearch vetoes nothing either)
        final boolean scale = testScale != null ? testScale : hookLeaf && !res.leafFallback;
        if (!scale) {
            final JsonObject o = new JsonObject();
            o.addProperty("natural", at.get(best));
            o.addProperty("reason", Config.OWN_HARM_NO_SCALE);
            o.addProperty("leafFallback", res.leafFallback);
            o.addProperty("decision", at.get(best));
            o.addProperty("decisionLabel", cands.get(best).label);
            o.addProperty("changed", false);
            o.addProperty("applied", decisionDemoteApply);
            stats.ownHarmNoScale++;
            res.ownHarmDemotion = o;
            return best;
        }
        final boolean[] okNr = okArg.clone();
        for (int c = 0; c < n; c++) {
            if (dem[c]) {
                okNr[c] = false;
            }
        }
        int alt = -1;
        if (!dem[0]) {
            for (int c = 0; provenPick >= 0 && c < n && alt < 0; c++) {
                if (provenWin[c] && okNr[c]) {
                    alt = c; // a proven win not refused (the default first), as the search's own proven pick
                }
            }
            if (alt < 0) {
                final long gated = stats.departGated, medGated = stats.departMedianGated; // the rerun counts nothing
                alt = finished != null ? chooseAnytime(values, finished, okNr, n, k, cfg.departZ, cfg.margin, rootP, rpArg,
                        hooks.reachDepart(), null) : argmax(values, okNr, n, k);
                if (cfg.departMedian == AiFixes.Mode.ON && finished == null) {
                    alt = argmaxMedian(values, okNr, n, k);
                }
                stats.departGated = gated;
                stats.departMedianGated = medGated;
            }
        } else {
            for (int c = 1; c < n; c++) {
                if (!okNr[c] || Double.isNaN(ev[c])) {
                    continue;
                }
                if (alt < 0 || provenWin[c] && !provenWin[alt] || provenWin[c] == provenWin[alt] && ev[c] > ev[alt]) {
                    alt = c;
                }
            }
        }
        final JsonObject o = new JsonObject();
        o.addProperty("natural", at.get(best));
        int dec = best;
        if (dem[best]) {
            if (alt < 0) {
                o.addProperty("noAlternative", true);
            } else {
                final double[] t = ownHarmBetter(values, finished, ev, best, alt, k, cfg.departZ);
                final boolean better = t[3] > 0 && t[4] > 0;
                stats.ownHarmTests++;
                if (better) {
                    stats.ownHarmPassed++;
                }
                o.add("test", ownHarmTestNote(cands.get(best).label, ev[best], cands.get(alt).label, ev[alt], t, cfg.departZ));
                dec = better ? best : alt;
            }
        } else {
            o.addProperty("defaultRefused", true);
            if (alt >= 0) {
                dec = alt;
            }
        }
        o.addProperty("decision", at.get(dec));
        o.addProperty("decisionLabel", cands.get(dec).label);
        if (Double.isFinite(ev[dec])) {
            o.addProperty("decisionEv", ev[dec]);
        }
        o.addProperty("changed", dec != best);
        o.addProperty("applied", decisionDemoteApply);
        if (dec != best) {
            stats.ownHarmDropped++;
        }
        res.ownHarmDemotion = o;
        return dec;
    }

    /**
     * Tests only (null = none; never set outside tests): after a decision's play-outs (and leaf values), each searched
     * candidate's value in each world is this function of its label and the world, NaN keeping the value played out (a
     * failed play-out stays failed).
     */
    public static volatile java.util.function.ToDoubleBiFunction<String, Integer> testValues = null;

    private static void stubValues(final List<Cand> cands, final double[][] values, final boolean[] ok, final int n, final int k) {
        final java.util.function.ToDoubleBiFunction<String, Integer> tv = testValues;
        if (tv == null) {
            return;
        }
        for (int c = 0; c < n; c++) {
            for (int w = 0; ok[c] && w < k; w++) {
                final double x = tv.applyAsDouble(cands.get(c).label, w);
                if (!Double.isNaN(x)) {
                    values[c][w] = x;
                }
            }
        }
    }

    public Stats getStats() {
        return stats;
    }

    /** The controller's measured time for Forge AI's own answer to the decision about to be searched (log only). */
    public void noteForgeNanos(long nanos) {
        forgeNanos = nanos;
    }

    private boolean pastDeadline() {
        final Budget b = budget;
        return b != null && b.over();
    }

    /**
     * The hard wall's grace (lane sc-wallguard-1009): with a budget (and a pool: threads > 1), the decision waits for
     * its play-outs at most until the deadline plus this, then plays its default ("capped"). The cooperative stops (a
     * play-out's next main-loop step, a policy seat's next service request) end a play-out within one step or one
     * request of the deadline, so the wall acts only on a play-out that does neither for a second (a loop inside one
     * Forge step that sends no request, or a service request that hangs). 1 s is ~40x the service's DECIDE p99 on the
     * Studio (13-28 ms) and well above an ordinary step, so the cooperative stops end first in every case seen; and it
     * keeps a player's worst wait for one searched decision at the budget + 1 s.
     */
    public static final long WALL_GRACE_MS = 1000L;

    /**
     * One searched decision's wall budget (lane sc-wallguard-1009): its deadline (System.nanoTime) and a stop flag the
     * hard wall sets. Play-outs read the budget of their own decision, so a play-out still running after its decision
     * was cut (a straggler) keeps seeing its own stop, never the next decision's budget.
     */
    public static final class Budget {
        final long deadline;
        private volatile boolean stopped = false;
        /** Set by the decision thread when the wall cut its wait (read after the decision). */
        volatile boolean wallCut = false;

        Budget(long deadline) {
            this.deadline = deadline;
        }

        /**
         * Past the deadline, or stopped by the wall. work-units-1107: the deadline is on {@link forge.game.WorkClock}'s
         * time (a play-out reads its lane's clock; without a clock it is System.nanoTime, as before).
         */
        public boolean over() {
            return stopped || forge.game.WorkClock.nanoTime() - deadline > 0;
        }

        void stop() {
            stopped = true;
        }
    }

    /**
     * The current searched decision is past its wall budget (false without a budget or outside a decision). Play-outs
     * read their own decision's budget instead ({@link FirstAction#overBudget}).
     */
    public boolean overBudget() {
        return pastDeadline();
    }

    public void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ decision

    /**
     * @param ctrl  the live seat's controller (Forge AI already asked)
     * @param def   Forge AI's own answer (null = pass)
     * @return what to play (null = pass)
     */
    public List<SpellAbility> decide(PlayerControllerAi ctrl, List<SpellAbility> def) {
        final Game live = ctrl.getGame();
        final Player me = ctrl.getPlayer();
        stats.decisions++;
        final int index = decisionIndex++;

        final boolean onStack = !live.getStack().isEmpty();
        if (onStack) {
            stats.stackDecisions++;
            if (!cfg.stack) {
                stats.stackSkipped++;
                return def;
            }
            final String why = GameCopier.stackUnsupported(live, false, cfg.copyTriggers);
            if (why != null) {
                stats.stackSkipped++;
                stats.stackUnsupported++;
                stats.stackWhy.merge(why, 1L, Long::sum);
                return def;
            }
        }
        final PhaseHandler ph = live.getPhaseHandler();
        final int turn = ph.getTurn();
        if (turn != departuresTurn) {
            departuresTurn = turn;
            departuresThisTurn = 0;
            departureCounts.clear();
        }

        final long t0 = System.nanoTime();
        // work-units-1107: the budget's start on the work clock (no clock: t0, as before)
        final long v0 = forge.game.WorkClock.active() ? forge.game.WorkClock.nanoTime() : t0;
        // lane forge-continue-1011: a "Continue from here" rebuild (forge.ai.WallClock) stretches the deadline so it never
        // binds; the budgeted/unbudgeted test here and every failure path stay the configured ones
        budget = cfg.budgetMs > 0 ? new Budget(v0 + forge.ai.WallClock.budgetMs(cfg.budgetMs) * 1_000_000L) : null;
        try {
            return decideSearched(ctrl, def, live, me, index, onStack, ph, turn, t0);
        } catch (RuntimeException e) {
            if (cfg.budgetMs <= 0) {
                throw e;
            }
            // Interactive play (budgeted): a failed search must not end the player's game; play Forge's answer.
            stats.departFallback++;
            System.err.println("[lookahead] search failed at decision " + index + ", playing Forge's answer: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
            return def;
        } finally {
            budget = null;
            forgeNanos = -1L;
        }
    }

    private List<SpellAbility> decideSearched(PlayerControllerAi ctrl, List<SpellAbility> def, Game live, Player me, int index,
                                              boolean onStack, PhaseHandler ph, int turn, long t0) {
        final long decisionSeed = mix(cfg.seed, 0x5eedL + index);
        final SpellAbility defSa = def == null || def.isEmpty() ? null : def.get(0);

        final boolean check = Boolean.getBoolean("lookahead.checkLive");
        final String before = check ? liveProbe(live, defSa) : null;
        // HX: the prior applies where the seat is the active player, in a main phase, with an empty stack.
        final PriorView pv = prior != null && !onStack && ph.getPlayerTurn() == me && ph.getPhase() != null && ph.getPhase().isMain()
                ? new PriorView() : null;
        // Candidates, enumerated in a copy so the live game is never touched by enumeration.
        final JsonObject[] beliefRoot = beliefCube != null && beliefClient != null ? new JsonObject[1] : null;
        // Label dump: the root ForgeState comes from the same enumeration copy (never the live game).
        final JsonObject[] labelRoot = labelSink != null || ranker != null ? new JsonObject[1] : null;
        final List<Cand> base = enumerate(live, me, defSa, decisionSeed, pv, beliefRoot, labelRoot);
        final JsonObject faceUpNote = cfg.deadFaceUp != AiFixes.Mode.OFF ? deadFaceUpLast : null; // this decision's (null = none)
        if (check) {
            checkLive("enumerate", before, live, defSa, index);
        }
        if (base.size() < 2) {
            stats.uncontested++;
            return def;
        }
        stats.searched++;
        if (onStack) {
            stats.stackSearched++;
        }
        if (cfg.rankerOn()) {
            return decideRanked(ctrl, def, live, me, index, ph, turn, t0, base, decisionSeed, defSa,
                    labelRoot == null ? null : labelRoot[0]);
        }
        final int bSize = base.size();
        JsonObject priorEntry = null;
        List<Cand> withExtras = base;
        if (prior != null) {
            if (pv == null) {
                stats.priorNotQualifying++;
            } else {
                priorEntry = new JsonObject();
                withExtras = addPriorExtras(live, me, base, pv, priorEntry, index, turn, ph);
            }
        }
        final List<Cand> cands = withExtras;
        lastCandidates = cands;

        final int k = Math.max(1, cfg.worlds);
        final int n = cands.size();
        final double[][] values = new double[n][k];
        final Rollout[][] outs = new Rollout[n][k];
        final boolean[] ok = new boolean[n];
        Arrays.fill(ok, true);
        explainChoices = EXPLAIN ? new String[n] : null;
        labelChoices = labelSink != null ? new String[n] : null;
        labelTargets = labelSink != null ? new JsonArray[n] : null;
        final Carried[] carried = cfg.reuse ? carry(live, me, cands, turn, k) : new Carried[k];
        final Rollout[] freshDef = cfg.reuseVerify ? new Rollout[k] : null;
        if (beliefCube != null && !cfg.beliefShadow && cfg.deck() && cfg.beliefDeckModel != null) {
            // review #155 S1: gate every opponent at the decision, outside the per-world try blocks
            for (Player o : me.getOpponents()) {
                cfg.beliefDeckModel.requireHeld(o.getRegisteredPlayer());
            }
        }
        final BeliefSampler.Dist bd = beliefCube == null ? null
                : belief(live, me, beliefRoot == null ? null : beliefRoot[0], index, turn, onStack);
        lastBelief = bd;
        if (bd != null && cfg.beliefShadow) {
            shadowBeliefWorlds(live, me, bd, decisionSeed, k);
        }
        decisionBelief = cfg.beliefShadow ? null : bd;
        final int abortedHere;
        try {
            abortedHere = playAll(live, me, cands, defSa, decisionSeed, carried, values, outs, ok, freshDef);
        } finally {
            decisionBelief = null;
        }
        if (cfg.dedupVerify) {
            dedupVerify(outs, n, k);
        }
        if (check) {
            checkLive("rollouts " + n + "x" + k + " def=" + cands.get(0).label, before, live, defSa, index);
        }
        final String stressSpec = System.getProperty("lookahead.stress");
        if (stressSpec != null) {
            stress(stressSpec, index, live, me, cands, defSa, decisionSeed);
        }
        // Label dump with a learned leaf (EI cycle 2): keep the static per-world values beside the leaf's.
        double[][] staticValues = null;
        boolean leafFallback = false;
        if (model != null) {
            if (labelSink != null) {
                staticValues = new double[n][];
                for (int c = 0; c < n; c++) {
                    staticValues[c] = values[c].clone();
                }
            }
            final long fb = stats.modelFallbacks;
            modelLeaves(live, me, values, outs, ok, k);
            leafFallback = stats.modelFallbacks != fb;
        }

        stubValues(cands, values, ok, n, k); // tests only (null: nothing)
        // Over budget: a play-out was stopped (or never started) by the wall budget, so the search did not finish
        // in time; play Forge AI's own answer.
        final boolean overBudget = abortedHere > 0;
        final double[] ev = new double[n];
        for (int c = 0; c < n; c++) {
            double s = 0;
            for (int w = 0; w < k; w++) {
                s += values[c][w];
            }
            ev[c] = ok[c] ? s / k : Double.NEGATIVE_INFINITY;
            if (!ok[c]) {
                stats.candidatesDropped++;
            }
        }
        // Forge's own answer is candidate 0; if it could not be played out, or the budget ran out, never depart.
        final int plainBest = overBudget ? 0 : argmax(values, ok, n, k);
        int best = plainBest;
        if (cfg.departMedian != AiFixes.Mode.OFF && !overBudget) {
            final int medBest = argmaxMedian(values, ok, n, k);
            if (medBest != plainBest) {
                stats.departMedianChanged++;
                if (cfg.departMedian == AiFixes.Mode.ON) {
                    best = medBest;
                }
            }
        }
        // line-own-target-1119 (ownHarm's demotion, review 3; off: nothing runs): a refused departure stays only if it is
        // better than the normal rule's choice among the candidates not refused (Forge's own answer, never demoted, the baseline)
        JsonObject ownHarmNote = null;
        // review 4: K8's values are win probabilities only with its model leaf and no fallback; over Forge's static score
        // (the live K8) ownHarm takes no action here
        final boolean ownHarmScale = testScale != null ? testScale : model != null && !leafFallback;
        if (cfg.ownHarm != AiFixes.Mode.OFF && !overBudget && best != 0 && ownHarmKeys.contains(cands.get(best).key())
                && !ownHarmScale) {
            stats.ownHarmNoScale++;
            final JsonObject o = new JsonObject();
            o.addProperty("mode", cfg.ownHarm.key());
            o.addProperty("seat", "k8");
            o.addProperty("turn", turn);
            o.addProperty("phase", String.valueOf(ph.getPhase()));
            o.addProperty("natural", cands.get(best).label);
            o.addProperty("reason", Config.OWN_HARM_NO_SCALE);
            o.addProperty("leafFallback", leafFallback);
            o.addProperty("changed", false);
            System.err.println("[own-harm] " + o);
        } else if (cfg.ownHarm != AiFixes.Mode.OFF && !overBudget && best != 0 && ownHarmKeys.contains(cands.get(best).key())) {
            final boolean[] okNr = ok.clone();
            for (int c = 1; c < n; c++) {
                if (ownHarmKeys.contains(cands.get(c).key())) {
                    okNr[c] = false;
                }
            }
            final long gated = stats.departGated, medGated = stats.departMedianGated;
            int alt = argmax(values, okNr, n, k);
            if (cfg.departMedian == AiFixes.Mode.ON) {
                alt = argmaxMedian(values, okNr, n, k);
            }
            stats.departGated = gated;
            stats.departMedianGated = medGated;
            final double[] t = ownHarmBetter(values, null, ev, best, alt, k, cfg.departZ);
            final boolean better = t[3] > 0 && t[4] > 0;
            stats.ownHarmTests++;
            if (better) {
                stats.ownHarmPassed++;
            } else {
                stats.ownHarmDropped++;
            }
            ownHarmNote = new JsonObject();
            ownHarmNote.addProperty("mode", cfg.ownHarm.key());
            ownHarmNote.addProperty("seat", "k8");
            ownHarmNote.addProperty("turn", turn);
            ownHarmNote.addProperty("phase", String.valueOf(ph.getPhase()));
            ownHarmNote.add("test", ownHarmTestNote(cands.get(best).label, ev[best], cands.get(alt).label, ev[alt], t,
                    cfg.departZ));
            ownHarmNote.addProperty("decision", cands.get(better ? best : alt).label);
            ownHarmNote.addProperty("changed", !better);
            ownHarmNote.addProperty("applied", cfg.ownHarm == AiFixes.Mode.ON);
            System.err.println("[own-harm] " + ownHarmNote);
            if (!better && cfg.ownHarm == AiFixes.Mode.ON) {
                best = alt;
            } else {
                ownHarmNote = null; // the decision line's field: only when the demotion changed the decision
            }
        }
        if (cfg.deadEtb == AiFixes.Mode.SHADOW && best != 0 && deadEtbKeys.contains(cands.get(best).key())) {
            stats.deadEtbShadowBest++;
        }
        if (cfg.zeroX == AiFixes.Mode.SHADOW && best != 0 && zeroXKeys.contains(cands.get(best).key())) {
            stats.zeroXShadowBest++;
        }
        if (cfg.crewNoop == AiFixes.Mode.SHADOW && best != 0 && crewNoopKeys.contains(cands.get(best).key())) {
            stats.crewNoopShadowBest++;
        }
        if (cfg.deadAltCost == AiFixes.Mode.SHADOW && best != 0 && deadAltCostKeys.contains(cands.get(best).key())) {
            stats.deadAltCostShadowBest++;
        }
        if (cfg.deadPump == AiFixes.Mode.SHADOW && best != 0 && deadPumpKeys.contains(cands.get(best).key())) {
            stats.deadPumpShadowBest++;
        }
        if (cfg.deadFaceUp == AiFixes.Mode.SHADOW && best != 0 && deadFaceUpKeys.contains(cands.get(best).key())) {
            stats.deadFaceUpShadowBest++;
        }
        if (cfg.copyEot != AiFixes.Mode.OFF) {
            final int[] u;
            synchronized (live) {
                u = GameCopier.untilCounts(live);
            }
            if (u[0] > 0) {
                stats.copyEotDecisions++;
                stats.copyEotCommands += u[0];
                stats.copyEotRemappable += u[1];
                if (best != 0) {
                    stats.copyEotDepartures++;
                }
            }
        }
        if (cfg.reuseVerify && !overBudget) {
            reuseVerify(live, me, cands, defSa, decisionSeed, carried, values, ok, freshDef, best);
        }

        // J1 (block-search-1019): an interactive K8 seat replaying its live choices (AiReplay, keyed by this decision
        // line's own index): when the live game played another candidate and it is one here, play that one
        final AiReplay.Mark mark = cfg.decisionLog ? replayMark(index) : null;
        boolean forcing = false, forcedMiss = false;
        if (mark != null && mark.played != null && labelCount(cands, mark.played) > 1) {
            forcedMiss = true; // review S2: an ambiguous key
        } else if (mark != null && mark.played != null) {
            int natural = overBudget || best == 0 || cfg.shadow ? 0 : best;
            if (natural != 0) {
                final String nk = cands.get(natural).key() + "@" + ph.getPhase() + "#" + live.getStack().size();
                if (departuresThisTurn >= cfg.maxDeparturesPerTurn || departureCounts.getOrDefault(nk, 0) >= 2) {
                    natural = 0;
                }
            }
            if (!mark.played.equals(cands.get(natural).label)) {
                final int idx = indexOfLabel(cands, mark.played);
                if (idx >= 0) {
                    best = idx;
                    forcing = true;
                } else {
                    forcedMiss = true;
                }
            }
        } else if (mark != null) {
            forcedMiss = true; // a mark with no label: nothing to match
        }
        List<SpellAbility> answer = def;
        String outcome = "kept";
        if (best != 0 && !cfg.shadow) {
            final Cand chosen = cands.get(best);
            final String key = chosen.key() + "@" + ph.getPhase() + "#" + live.getStack().size();
            final int seen = departureCounts.getOrDefault(key, 0);
            if (departuresThisTurn >= cfg.maxDeparturesPerTurn || seen >= 2) {
                stats.loopGuard++;
                outcome = "loop-guard";
            } else {
                List<SpellAbility> mapped = mapToLive(ctrl, live, me, chosen);
                if (mapped == null && !chosen.pass) {
                    stats.departFallback++;
                    outcome = "map-failed";
                } else {
                    answer = mapped;
                    stats.departed++;
                    if (chosen.tgt != null) {
                        stats.targetVariantDepartures++;
                    }
                    if (onStack) {
                        stats.stackDeparted++;
                    }
                    departuresThisTurn++;
                    departureCounts.put(key, seen + 1);
                    outcome = "departed";
                }
            }
        } else if (best != 0) {
            outcome = "shadow-would-depart";
        }
        if (overBudget && !forcing) {
            stats.capped++;
            outcome = "capped";
        }
        final String played = cands.get("departed".equals(outcome) ? best : 0).label;
        if (forcing && !played.equals(mark.played)) {
            forcing = false;
            forcedMiss = true;
        }
        if (priorEntry != null) {
            final boolean extra = best >= bSize;
            priorEntry.addProperty("best", best);
            priorEntry.addProperty("chosenExtra", extra);
            priorEntry.addProperty("outcome", outcome);
            if (extra) {
                stats.extraBest++;
                if ("departed".equals(outcome)) {
                    stats.departuresToExtra++;
                }
            }
        }

        if (Boolean.getBoolean("lookahead.trace")) {
            // One line per searched decision: every candidate's per-world values, bit-exact, to find where two runs part.
            StringBuilder tb = new StringBuilder("[ltrace] d=").append(index).append(" T").append(turn).append(' ').append(ph.getPhase())
                    .append(" best=").append(best).append(" out=").append(outcome);
            for (int c = 0; c < cands.size(); c++) {
                tb.append(" | ").append(cands.get(c).label.replace('|', '/')).append(ok[c] ? "" : " FAIL");
                for (int w = 0; w < k; w++) {
                    tb.append(' ').append(Double.doubleToLongBits(values[c][w]));
                }
            }
            System.err.println(tb);
        }
        if (EXPLAIN) {
            // Diagnostics (lane forge-ai-misplays-0928): every candidate, the choices it was played out with (targets as
            // Forge AI chose them in world 0), its per-world values and EV, the paired difference to Forge's answer.
            final JsonObject x = new JsonObject();
            x.addProperty("decision", index);
            x.addProperty("turn", turn);
            x.addProperty("phase", String.valueOf(ph.getPhase()));
            x.addProperty("active", ph.getPlayerTurn() == me);
            x.addProperty("stack", live.getStack().size());
            x.addProperty("best", best);
            x.addProperty("outcome", outcome);
            x.addProperty("line", index); // J1: the K8 decision line's own index (its "decision")
            x.addProperty("played", played);
            if (faceUpNote != null) {
                x.add("deadFaceUp", faceUpNote); // deadFaceUp on or shadow, with a turn-up among the candidates
            }
            final JsonArray ca = new JsonArray();
            for (int c = 0; c < n; c++) {
                final JsonObject co = new JsonObject();
                co.addProperty("label", cands.get(c).label);
                co.addProperty("choices", explainChoices != null && explainChoices[c] != null ? explainChoices[c] : "");
                co.addProperty("ok", ok[c]);
                co.addProperty("ev", ok[c] ? ev[c] : null);
                if (cfg.deadEtb == AiFixes.Mode.SHADOW && deadEtbKeys.contains(cands.get(c).key())) {
                    co.addProperty("deadEtb", true);
                }
                if (cfg.zeroX == AiFixes.Mode.SHADOW && zeroXKeys.contains(cands.get(c).key())) {
                    co.addProperty("zeroX", true);
                }
                if (cfg.crewNoop == AiFixes.Mode.SHADOW && crewNoopKeys.contains(cands.get(c).key())) {
                    co.addProperty("crewNoop", true);
                }
                if (cfg.deadAltCost == AiFixes.Mode.SHADOW && deadAltCostKeys.contains(cands.get(c).key())) {
                    co.addProperty("deadAltCost", true);
                }
                if (cfg.deadPump == AiFixes.Mode.SHADOW && deadPumpKeys.contains(cands.get(c).key())) {
                    co.addProperty("deadPump", true);
                }
                if (cfg.deadFaceUp == AiFixes.Mode.SHADOW && deadFaceUpKeys.contains(cands.get(c).key())) {
                    co.addProperty("deadFaceUp", true);
                }
                final JsonArray vs = new JsonArray();
                final JsonArray ds = new JsonArray();
                for (int w = 0; w < k; w++) {
                    vs.add(values[c][w]);
                    ds.add(values[c][w] - values[0][w]);
                }
                co.add("values", vs);
                if (c > 0) {
                    co.add("diffVsForge", ds);
                }
                ca.add(co);
            }
            x.add("candidates", ca);
            System.err.println("[lookahead-explain] " + x);
        }
        if (Boolean.getBoolean("lookahead.debug") && best != 0) {
            System.err.println("[lookahead] decision " + index + " T" + turn + " " + ph.getPhase() + " stack=" + live.getStack().size()
                    + " def=" + cands.get(0).label + " best=" + cands.get(best).label + " outcome=" + outcome
                    + " answer=" + (answer == null ? "pass" : answer.isEmpty() ? "[]" : answer.get(0) + " targets=" + answer.get(0).getAllTargetChoices()));
        }
        final long dt = System.nanoTime() - t0;
        stats.searchNanos += dt;
        stats.maxSearchNanos = Math.max(stats.maxSearchNanos, dt);
        stats.searchMsEach.add(dt / 1e6);
        if (cfg.reuse) {
            keep(outs, "departed".equals(outcome) ? best : 0, turn, k);
        }
        if (cfg.decisionLog) {
            // One line per searched decision for the interactive host to log (latency tail, caps, departures).
            final JsonObject d = new JsonObject();
            d.addProperty("decision", index);
            d.addProperty("turn", turn);
            d.addProperty("phase", String.valueOf(ph.getPhase()));
            d.addProperty("stack", live.getStack().size());
            d.addProperty("candidates", cands.size());
            d.addProperty("worlds", k);
            d.addProperty("searchMs", Math.round(dt / 1e5) / 10.0);
            if (forgeNanos >= 0) {
                d.addProperty("forgeMs", Math.round(forgeNanos / 1e5) / 10.0);
            }
            d.addProperty("capped", overBudget);
            final Budget wb = budget;
            if (wb != null && wb.wallCut) {
                // sc-wallguard-1009: only when the hard wall cut the wait, so the line is otherwise unchanged
                d.addProperty("wall", true);
            }
            d.addProperty("outcome", outcome);
            d.addProperty("departed", "departed".equals(outcome));
            if (ownHarmNote != null) {
                d.add("ownHarm", ownHarmNote); // line-own-target-1119: only when the demotion changed the decision (ON)
            }
            if (forcing) {
                d.addProperty("forced", true); // J1: only when replaying (aiReplay), so a live line is unchanged
            }
            if (forcedMiss) {
                d.addProperty("forcedMiss", true);
            }
            if (cfg.infoState) {
                d.addProperty("infoState", Config.INFO_STATE_VERSION); // order-memory-1102: only when armed
            }
            d.addProperty("searched", stats.searched);
            d.addProperty("departedTotal", stats.departed);
            d.addProperty("cappedTotal", stats.capped);
            if (forge.ai.WallClock.rebuilding()) {
                // lane forge-continue-1011: a "Continue from here" rebuild's decision (wall clocks off); only then, so a
                // normal game's line is unchanged
                d.addProperty("rebuild", true);
            }
            System.err.println("[lookahead-decision] " + d);
        }
        if (labelSink != null) {
            emitLabel(live, me, def, index, turn, ph, cands, bSize, values, ok, k, best, outcome, overBudget, abortedHere, dt,
                    labelRoot == null ? null : labelRoot[0], staticValues, leafFallback);
        }

        if (cfg.probe && turn >= 3 && stats.probes.size() < cfg.probeMax && (!cfg.probeStack || onStack)) {
            JsonObject p = new JsonObject();
            p.addProperty("decision", index);
            p.addProperty("turn", turn);
            p.addProperty("phase", String.valueOf(ph.getPhase()));
            p.addProperty("activePlayer", ph.getPlayerTurn() == me);
            p.addProperty("cardsInGame", live.getCardsInGame().size());
            p.addProperty("searchMs", dt / 1e6);
            p.addProperty("outcome", outcome);
            JsonArray ca = new JsonArray();
            for (int c = 0; c < cands.size(); c++) {
                JsonObject co = new JsonObject();
                co.addProperty("label", cands.get(c).label);
                co.addProperty("ok", ok[c]);
                co.addProperty("ev", ok[c] ? ev[c] : null);
                JsonArray vs = new JsonArray();
                for (int w = 0; w < k; w++) {
                    vs.add(values[c][w]);
                }
                co.add("values", vs);
                ca.add(co);
            }
            p.add("candidates", ca);
            probeExtras(p, live, me, cands, defSa, decisionSeed, turn);
            stats.probes.add(p);
        }
        return answer;
    }

    // ------------------------------------------------------------------ S1: a given candidate set (lane s1-search-1007)

    /** S1: what one {@link #decideGiven} call did, for the caller's per-decision log. */
    public static final class GivenResult {
        /** Index into the given list to play: 0 = the default; -1 = not searched (the default stands). */
        public int chosen = -1;
        /** unsearched | stack | uncontested | kept | departed | loop-guard | capped | failed. */
        public String outcome = "unsearched";
        /** Measurement only (-Dsdcc.stackNote=true; null otherwise): the stack this decision was made on. */
        public JsonObject stackNote;
        /** The searched candidates as indices into the given list (default first), and their mean values (NaN = a world failed). */
        public int[] givenIndex = new int[0];
        public double[] ev = new double[0];
        /** Every searched candidate's per-world value (the leaf's scale; -inf = a failed play-out), for diagnostics. */
        public double[][] values = new double[0][];
        /** Candidates whose play-out failed in some world (never chosen). */
        public int failed;
        public int worlds;
        public boolean leafFallback;
        /** Wall and CPU time of the call (CPU = the calling thread's plus the pool threads'), and the pool part. */
        public double ms, cpuMs, poolCpuMs;
        /**
         * S-t (lane cm-choice-search-1009): per searched candidate, the index of its macro in the expander's list (-1 =
         * a base candidate; empty without an expander), and the chosen candidate's (-1 = none, or a base candidate).
         */
        public int[] macro = new int[0];
        public int chosenMacro = -1;
        /** Lane sc-wallguard-1009: the hard wall cut this decision's wait for its play-outs (outcome "capped"). */
        public boolean wall;
        /**
         * Lane search-bones-1015 (S-b; empty otherwise): per searched candidate, a terminal win in every world with its
         * plan unbroken (a proven win) / a terminal loss in every world (a proven loss), and how many worlds its plan
         * broke in.
         */
        public boolean[] provenWin = new boolean[0], provenLoss = new boolean[0];
        public int[] planBroken = new int[0];
        /** S-b: the choice was a proven win (no departZ gate, no loop guard). */
        public boolean proven;
        /** S-b: over the budget, the number of worlds every candidate finished in that decided (0 = all, or capped). */
        public int partialWorlds;
        /** J1 (block-search-1019): the label of the candidate this decision played (the default's unless it departed). */
        public String played;
        /** J1: an {@link AiReplay} mark made this decision play the live choice / named a choice that was not a candidate. */
        public boolean forced, forcedMiss;
        /**
         * bvw-step2-fork-1027 (S2T, {@link Config#leafOpp}; null otherwise): per searched candidate and world, {v_s, v_o,
         * terminal} as {@code pairs[0..2][candidate][world]} (P(win) of each seat; terminal 1 / 0 / 0.5, NaN = a leaf).
         */
        public double[][][] pairs;
        /**
         * lethalBlock (block search; null = the guard is off): per searched candidate, its worst case is lethal; empty
         * when the guard stood aside ({@link #lethalAside} says why).
         */
        public boolean[] lethal;
        /** lethalBlock: why the guard stood aside (null = it checked, or it is off). */
        public String lethalAside;
        /**
         * deadFaceUp (null = the guard is off, or no given candidate turns a face-down permanent up): the turn-up candidates
         * checked, dropped (ON) or flagged (SHADOW), the default's own dead turn-up, stand-asides by reason, and ms.
         */
        public JsonObject deadFaceUp;
        /**
         * line-own-target-1119 (null = ownHarm is off, or it did not change this decision): the policy seat's refused
         * entries and the demotion that moved the decision off one ({@link #ownHarmDemotion}, or the entry that stood in
         * for a refused own choice the search gave no verdict on), set by {@code RlSearch}.
         */
        public JsonObject ownHarm;
        /**
         * ownHarm's demotion (review 3; null = no refused candidate was the pick, nor the default): the natural pick and
         * the decision (indices into the given list), the test of the refused one against the best candidate not refused
         * ({@link LookaheadSearch#ownHarmBetter}), whether it changed the decision and whether it was applied (ON).
         */
        public JsonObject ownHarmDemotion;
        /** lethalBlock ON: the witnessed candidates were re-scored as lost play-outs. */
        public boolean lethalRescored;
        /**
         * lethalBlock round 6 (with {@link #lethal}, null otherwise): per candidate, the engine witnessed it lethal, and
         * the witnessing line (null = none); the witness's time in ms.
         */
        public boolean[] witnessed;
        public String[] witnessLine;
        public double witnessMs;
        /**
         * sdcc-c1-priogf-1117 (null unless the prioGf rule ran on this decision): "cands=[i:label:leaf:gf,...] base=i
         * pick=i changed=true|false" (searched indices; gf -1 = not computed).
         */
        public String prioGf;
    }

    /**
     * S-t (lane cm-choice-search-1009): one macro candidate, a searched base candidate (index into the searched list)
     * played out with a forced-answer schedule for its choice asks (opaque here: the policy play-out seat reads it from
     * {@link FirstAction#schedule}).
     */
    public static final class Macro {
        public final int base;
        public final Object schedule;
        public final String label;

        public Macro(int base, Object schedule, String label) {
            this.base = base;
            this.schedule = schedule;
            this.label = label;
        }
    }

    /**
     * S-t: after the base candidates' play-outs (whose world-0 play-outs were probes: {@link FirstAction#probe}), the
     * macro candidates to add. {@code givenIndex[c]} is searched candidate c's index in the given list.
     */
    public interface ChoiceExpander {
        List<Macro> expand(int[] givenIndex);

        /** Copy fidelity 2 (copy-fidelity-1010): the decision is searched again; forget this decision's probes and macros. */
        default void restart() {
        }
    }

    // ------------------------------------------------------------------ search-bones-1015 (S-b): turn-plans

    /**
     * S-b (d): a check inside a play-out, at the opponent's first main-phase priority with an empty stack: does the
     * opponent have a line that wins there? Yes = the play-out is a proven loss for our candidate (value 0).
     */
    public interface OppCheck {
        boolean theyWin(Game g, Player me);
    }

    /** S-b (d): the check of the decision in progress (null = none: S-c's play-outs). */
    private volatile OppCheck oppCheck = null;

    /** S-b (d): play-outs the opponent check ended as losses, and checks made (per game). */
    public final java.util.concurrent.atomic.AtomicLong oppChecks = new java.util.concurrent.atomic.AtomicLong(),
            oppLosses = new java.util.concurrent.atomic.AtomicLong();

    /** S-b: the plan candidates of the decision in progress ({@link #decideGivenPlans}; null otherwise). */
    private List<Object> pendingPlans = null;
    private List<String> pendingPlanLabels = null;
    /** S-b: proven-win / proven-loss flags take part in the choice (off = S-c's choice exactly). */
    private boolean proof = false;
    /**
     * S-b: when the decision began (System.nanoTime), if a stage before the play-outs (the proposals) already spent part
     * of its budget; 0 = the play-outs' own start (S-c).
     */
    private volatile long decisionStartNanos = 0L;
    /** unified-search-1106 ({@link Config#anytime}): the root's P(win) from the value head, NaN = none. */
    private volatile double rootP = Double.NaN;

    public void setRootP(double p) {
        this.rootP = p;
    }

    /**
     * line-adoption-1107 (plans.lineHorizon; 0 = none, the default): the absolute turn at whose start (with a value leaf:
     * the seat's first priority in it) every play-out of the decision in progress ends, when later than now + horizon. Set
     * by a plan point whose solver line wins past the search's horizon, so the line is scored by its own outcome and its
     * alternatives by the same leaf, in the same worlds. Cleared when the decision ends.
     */
    private volatile int planHorizonTurn = 0;

    public void setPlanHorizonTurn(int turn) {
        this.planHorizonTurn = turn;
    }
    /**
     * S-b: cards on top of our library the seat placed itself (pile memory, ForcedWinSolver.knownTopCount); every world
     * keeps them in place. 0 = S-c's worlds (only the cards we can see stay).
     */
    private volatile int ownKnownTop = 0;

    /**
     * S-b: {@link #decideGiven} with turn-plan candidates appended after the given ones (index given.size() + i in
     * {@link GivenResult#givenIndex} and {@link GivenResult#chosen}), the proof flags in the choice, our known top kept
     * in every world, and the budget counted from {@code startNanos}.
     */
    public GivenResult decideGivenPlans(Game live, Player me, List<SpellAbility> given, int breadth, List<Object> plans,
            List<String> labels, int knownTop, long startNanos) {
        return decideGivenPlans(live, me, given, breadth, plans, labels, knownTop, startNanos, null);
    }

    /** As above, with (d)'s opponent check in every play-out ({@code opp}; null = none). */
    public GivenResult decideGivenPlans(Game live, Player me, List<SpellAbility> given, int breadth, List<Object> plans,
            List<String> labels, int knownTop, long startNanos, OppCheck opp) {
        return decideGivenPlans(live, me, given, breadth, plans, labels, knownTop, startNanos, opp, null);
    }

    /**
     * As above, with S-t's choice expansion of the given (non-plan) candidates ({@code expander}; null = none): (d)'s
     * discard choices, among others, are searched as macros and priced by the opponent check.
     */
    public GivenResult decideGivenPlans(Game live, Player me, List<SpellAbility> given, int breadth, List<Object> plans,
            List<String> labels, int knownTop, long startNanos, OppCheck opp, ChoiceExpander expander) {
        pendingPlans = plans == null || plans.isEmpty() ? null : plans;
        pendingPlanLabels = labels;
        proof = true;
        ownKnownTop = Math.max(0, knownTop);
        decisionStartNanos = startNanos;
        oppCheck = opp;
        try {
            return decideGiven(live, me, given, breadth, expander);
        } finally {
            pendingPlans = null;
            pendingPlanLabels = null;
            proof = false;
            ownKnownTop = 0;
            decisionStartNanos = 0L;
            oppCheck = null;
            rootP = Double.NaN;
            planHorizonTurn = 0;
        }
    }

    /**
     * bvw-step1-build-1024 (ruling 4; the step-1 labeller only, nothing in play calls it): the last given search's
     * candidate {@code cand} (its index among the searched candidates) played out again in K FRESH worlds seeded by
     * {@code salt} (never the decision's own world seeds), with the same play-outs and leaf values, our known top kept as
     * {@code knownTop}. A departure's own K-world mean won a max over noisy means, so it is biased up; these worlds are
     * not. Returns the per-world values, or null (a failed play-out, a budget stop, a leaf fallback).
     */
    public double[] reevaluate(Game live, Player me, int cand, long salt, int knownTop) {
        final List<Cand> cs = lastCandidates;
        if (cs == null || cand < 0 || cand >= cs.size()) {
            return null;
        }
        final int k = Math.max(1, cfg.worlds);
        final double[][] values = new double[1][k];
        final Rollout[][] outs = new Rollout[1][k];
        final boolean[] ok = {true};
        final int prevTop = ownKnownTop;
        final boolean prevReplaying = replaying;
        ownKnownTop = Math.max(0, knownTop);
        replaying = true;
        try {
            final long fb = stats.leafFallbacks;
            final int aborted = playAll(live, me, java.util.Collections.singletonList(cs.get(cand)), null, mix(cfg.seed, salt),
                    new Carried[k], values, outs, ok, null);
            if (aborted > 0 || !ok[0]) {
                return null;
            }
            if (hookLeaf) {
                valueLeaves(values, outs, ok, k);
                if (stats.leafFallbacks != fb) {
                    return null;
                }
            }
            return values[0];
        } finally {
            ownKnownTop = prevTop;
            replaying = prevReplaying;
        }
    }

    // ------------------------------------------------------------------ bvw-step2-fork-1027 (S2T): re-plays

    /** S2T: how {@link #prepare} draws a world: the belief (every search world), or a TRUE world (T re-play, OPR). */
    public static final int WORLD_BELIEF = 0, WORLD_TRUE = 1, WORLD_OPR = 2;
    /** The world mode of the re-play in progress (decision thread only; WORLD_BELIEF outside {@link #replayCandidate}). */
    private volatile int worldMode = WORLD_BELIEF;
    /** step3-playout-fixes-1028: a re-play ({@link #replayCandidate}, {@link #reevaluate}) is in progress (decision thread). */
    private volatile boolean replaying = false;
    /** OPR: the cap of the roll-outs in progress (turns past the start, main-loop steps). */
    private volatile int oprTurns = 0, oprSteps = 0;

    /** S2T: one candidate's re-play ({@link #replayCandidate}). */
    public static final class Replay {
        /** Per world: its value (P(win) of the searching seat); NaN = failed, or an OPR roll-out at its cap. */
        public double[] values;
        /** With {@link Config#leafOpp} (T, belief): {v_s, v_o, terminal} per world ({@code pairs[0..2][world]}), else null. */
        public double[][] pairs;
        public int worlds, failed, capped;
        /** Null = usable; else why not: no_candidate, aborted, failed (a world failed), leaf_fallback. */
        public String why;
        /**
         * Review #123 S3-1 (set by the hooks' owner; null otherwise): the asks Forge AI answered in these play-outs, by
         * family, and the controller calls that never reached a policy seat, by method.
         */
        public com.google.gson.JsonObject forge;
    }

    /**
     * bvw-step2-fork-1027 (S2T; the step-2 labeller only, nothing in play calls it): the last given search's candidate
     * {@code cand} (its index among the searched candidates) played again in {@code worlds} worlds seeded by {@code salt}
     * (never the decision's own world seeds), with the same seats and leaves:
     * <ul>
     *   <li>{@link #WORLD_BELIEF}: fresh belief worlds (as {@link #reevaluate}, plus the leaf pairs);</li>
     *   <li>{@link #WORLD_TRUE}: the T re-play, copies of the REAL position (both hands kept, every library shuffled below
     *       its known top, {@link #trueShuffle}), valued at the horizon by the same leaf;</li>
     *   <li>{@link #WORLD_OPR}: the own-policy roll-out reference, true worlds as T, no leaf: each roll-out plays to the
     *       end of the game, at most {@code capTurns} turns and {@code capSteps} main-loop steps; its value is the game's
     *       result (1 / 0 / 0.5), a roll-out at its cap is counted, not scored (NaN).</li>
     * </ul>
     * The real hidden cards only seed a true world's starting state: every decision in it is a seat's own (the policy
     * seats read their own observation; with {@link Config#opponentPolicy} there is no Forge AI seat), and the candidate
     * itself was chosen in belief worlds. The decision's own state (its candidates, index, departures) is not changed.
     */
    public Replay replayCandidate(Game live, Player me, int cand, long salt, int knownTop, int worlds, int mode,
            int capTurns, int capSteps) {
        final Replay out = new Replay();
        final List<Cand> cs = lastCandidates;
        final int k = Math.max(1, worlds);
        out.worlds = k;
        if (cs == null || cand < 0 || cand >= cs.size()) {
            out.why = "no_candidate";
            return out;
        }
        if (mode != WORLD_BELIEF && !(hookPlayout && cfg.opponentPolicy)) {
            // the honesty refusal: a true world needs every seat to be a policy seat on its own observation
            throw new IllegalStateException("refused: a true-world re-play needs policy seats on both sides");
        }
        final double[][] values = new double[1][k];
        final Rollout[][] outs = new Rollout[1][k];
        final boolean[] ok = {true};
        final int prevTop = ownKnownTop;
        final int prevMode = worldMode;
        final boolean prevReplaying = replaying;
        ownKnownTop = Math.max(0, knownTop);
        worldMode = mode;
        replaying = true;
        oprTurns = mode == WORLD_OPR ? capTurns : 0;
        oprSteps = mode == WORLD_OPR ? capSteps : 0;
        trueKnown = mode == WORLD_BELIEF ? null : hooks.knownToAnySeat(live);
        try {
            final long fb = stats.leafFallbacks;
            final int aborted = playAll(live, me, java.util.Collections.singletonList(cs.get(cand)), null, mix(cfg.seed, salt),
                    new Carried[k], values, outs, ok, null);
            out.values = new double[k];
            Arrays.fill(out.values, Double.NaN);
            for (int w = 0; w < k; w++) {
                if (outs[0][w] == null || !outs[0][w].ok) {
                    out.failed++;
                }
            }
            if (aborted > 0) {
                out.why = "aborted";
                return out;
            }
            if (mode == WORLD_OPR) {
                for (int w = 0; w < k; w++) {
                    final Rollout r = outs[0][w];
                    if (r != null && r.ok) {
                        if (Double.isNaN(r.pTerminal)) {
                            out.capped++;
                        } else {
                            out.values[w] = r.pTerminal;
                        }
                    }
                }
                return out;
            }
            if (!ok[0]) {
                // review #123 S3-6: which world failed and why
                out.why = "failed";
                for (int w = 0; w < k; w++) {
                    final Rollout r = outs[0][w];
                    if (r != null && !r.ok) {
                        final String f = r.seatFailure != null ? r.seatFailure : r.failDetail;
                        out.why = "failed: world " + w + " " + failureClass(r)
                                + (f == null ? "" : " " + (f.length() > 120 ? f.substring(0, 120) : f));
                        break;
                    }
                }
                return out;
            }
            if (hookLeaf) {
                if (cfg.leafOpp) {
                    final double[][][][] po = new double[1][][][];
                    valueLeavesS2t(values, outs, ok, k, po);
                    if (po[0] != null) {
                        out.pairs = new double[][] {po[0][0][0], po[0][1][0], po[0][2][0]};
                    }
                } else {
                    valueLeaves(values, outs, ok, k);
                }
                if (stats.leafFallbacks != fb) {
                    out.why = "leaf_fallback";
                    out.pairs = null;
                    return out;
                }
            }
            out.values = values[0];
            return out;
        } finally {
            ownKnownTop = prevTop;
            worldMode = prevMode;
            replaying = prevReplaying;
            oprTurns = 0;
            oprSteps = 0;
            trueKnown = null;
        }
    }

    /**
     * bvw-step2-fork-1027 (S2T): a TRUE world (the T re-play, OPR) in copy {@code g} of the real position: both hands as
     * they are; every library keeps its known top (the cards at its top that any player may see in the live game, and,
     * for our own, the top we placed ourselves: {@link #ownKnownTop}) and the rest is shuffled with {@code rng}. Players
     * in the copy's order. The real hidden cards seed only this starting state.
     */
    void trueShuffle(Game live, Player liveMe, Game g, Player me, Random rng) {
        trueShuffle(live, liveMe, g, me, rng, trueKnown);
    }

    /** As above with {@code knownBySeat} (null = Forge's visibility only): the library cards some seat knows. */
    void trueShuffle(Game live, Player liveMe, Game g, Player me, Random rng, java.util.function.Predicate<Card> knownBySeat) {
        final List<PlayerView> views = new ArrayList<>();
        for (Player lp : live.getRegisteredPlayers()) {
            views.add(lp.getView());
        }
        for (Player q : g.getRegisteredPlayers()) {
            final List<Card> lib = new ArrayList<>(q.getCardsIn(ZoneType.Library));
            // review #123 S2-1: every library card SOME seat knows (Forge shows it to a player, the seat-knowledge
            // tracker knows it: scry, Brainstorm, a pile), and our own placed top, stays where it is; the others are
            // shuffled among the other positions (so the forked seat knowledge still agrees with the order)
            final boolean[] keep = new boolean[lib.size()];
            final List<Card> rest = new ArrayList<>();
            for (int i = 0; i < lib.size(); i++) {
                final Card lc = live.findById(lib.get(i).getId());
                boolean k = q == me && i < ownKnownTop;
                if (!k && lc != null) {
                    for (PlayerView v : views) {
                        if (lc.getView().canBeShownTo(v)) {
                            k = true;
                            break;
                        }
                    }
                    if (!k && knownBySeat != null && knownBySeat.test(lc)) {
                        k = true;
                    }
                }
                keep[i] = k;
                if (!k) {
                    rest.add(lib.get(i));
                }
            }
            Collections.shuffle(rest, rng);
            final List<Card> nl = new ArrayList<>(lib.size());
            int r = 0;
            for (int i = 0; i < lib.size(); i++) {
                nl.add(keep[i] ? lib.get(i) : rest.get(r++));
            }
            q.getZone(ZoneType.Library).setCards(nl);
        }
    }

    /**
     * Review #123 S2-1: during a true-world re-play, whether some seat knows a library card of the live position (the
     * hooks' seat knowledge, {@link SearchHooks#knownToAnySeat}); null = Forge's visibility only.
     */
    private volatile java.util.function.Predicate<Card> trueKnown = null;

    /** S-b: the next searched decision's world-0 seed (its recordings play in the same world 0 as its valuation). */
    public long nextWorld0Seed() {
        return mix(mix(cfg.seed, 0x5eedL + decisionIndex), 1000);
    }

    /**
     * S-b proposals: one recording play-out per recorder, each a copy of the next decision's world 0, its policy seat
     * driven by the recorder (it samples and records our priority answers) until our turn ends; the play-outs run on the
     * search's pool and stop at {@code deadlineNanos}. Returns, per recorder, whether its play-out ran to the end of our
     * turn without failing.
     */
    public boolean[] recordPlans(Game live, Player me, List<Object> recorders, int knownTop, long deadlineNanos) {
        final int n = recorders.size();
        final boolean[] done = new boolean[n];
        if (n == 0 || !hookPlayout) {
            return done;
        }
        final long ws = nextWorld0Seed();
        final Budget b = new Budget(deadlineNanos);
        final Budget prevBudget = budget;
        budget = b;
        ownKnownTop = Math.max(0, knownTop);
        final Prepared[] prep = new Prepared[n];
        try {
            for (int i = 0; i < n; i++) {
                prep[i] = prepare(live, me, new Cand((SpellAbility) null, true), null, ws, cfg.resample, null);
                if (prep[i].firstAction != null) {
                    prep[i].firstAction.recorder = recorders.get(i);
                }
            }
            final List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int ii = i;
                tasks.add(() -> {
                    final Rollout r = play(prep[ii], null);
                    prep[ii] = null;
                    done[ii] = r.ok && !r.aborted;
                });
            }
            if (!runAll(tasks, b)) {
                b.stop();
            }
        } finally {
            budget = prevBudget;
            ownKnownTop = 0;
        }
        return done;
    }

    /** S-b: put the live library's top {@code k} cards (by id) back on top of the copy's library, in order. */
    static void restoreOwnTop(Player liveMe, Player me, int k) {
        final List<Card> liveLib = new ArrayList<>(liveMe.getCardsIn(ZoneType.Library));
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final List<Card> top = new ArrayList<>();
        for (int i = 0; i < Math.min(k, liveLib.size()); i++) {
            final int id = liveLib.get(i).getId();
            for (Card c : lib) {
                if (c.getId() == id) {
                    top.add(c);
                    break;
                }
            }
        }
        lib.removeAll(top);
        top.addAll(lib);
        me.getZone(ZoneType.Library).setCards(top);
    }

    /** S-t: tag the policy play-outs' first actions (candidate index, world, probe), set only inside an expansion. */
    private boolean choiceTag = false, choiceProbe = false;
    private int choiceIndexBase = 0;

    /**
     * S1 (lane s1-search-1007): search a GIVEN candidate set at a priority decision of {@code me} in the live game,
     * instead of Forge's own (Forge's answer, pass, the next legal). {@code given} is in the caller's order of
     * preference (a learned policy's prior): index 0 is the default the search departs from (the base policy's own
     * answer); null is pass. Candidates are taken in that order, duplicates (same host, land flag and description)
     * skipped, guarded ones (deadEtb / zeroX / crewNoop ON) dropped except the default, until {@code breadth} are taken.
     * Every other part is the K8 recipe: K worlds with common random numbers, play-outs to the start of turn now +
     * horizon (with a value leaf: the seat's first priority in it), argmax with the departZ / median / margin gates, the
     * per-turn departure guards. Forge AI targets each candidate in its copy (with policy play-outs the play-out seat
     * does). The live game is only read. With no hooks set and no guard on, nothing here touches the other paths.
     */
    public GivenResult decideGiven(Game live, Player me, List<SpellAbility> given, int breadth) {
        return decideGiven(live, me, given, breadth, null);
    }

    /**
     * S-t (lane cm-choice-search-1009): as {@link #decideGiven(Game, Player, List, int)}; with an {@code expander} (policy
     * play-outs only), the base candidates' world-0 play-outs are probes, and the macro candidates the expander returns
     * are played out in the same K worlds (common random numbers) and compete with the base candidates in the same
     * argmax and departZ gate. A null expander is exactly the S1 search.
     */
    public GivenResult decideGiven(Game live, Player me, List<SpellAbility> given, int breadth, ChoiceExpander expander) {
        if (expander != null && !hookPlayout) {
            throw new IllegalStateException("S-t: expanding choices needs policy play-outs");
        }
        final GivenResult res = new GivenResult();
        decisionForce = forcePlayed;
        forcePlayed = null;
        decisionDemote = demoteNext; // line-own-target-1119 (null = none): this decision's, a skipped one's too
        decisionDemoteApply = demoteApply;
        demoteNext = null;
        final java.util.function.IntPredicate bump = testBump;
        if (bump != null && explainLine != null && bump.test(explainLine.getAsInt())) {
            decisionIndex++; // tests only (J1)
        }
        stats.s1On = true;
        stats.decisions++;
        stats.givenDecisions++;
        final int index = decisionIndex++;
        final boolean onStack = !live.getStack().isEmpty();
        if (onStack) {
            stats.stackDecisions++;
            if (!cfg.stack) {
                stats.stackSkipped++;
                stats.givenStackSkipped++;
                res.outcome = "stack";
                return res;
            }
            final String why = GameCopier.stackUnsupported(live, false, cfg.copyTriggers);
            if (why != null) {
                stats.stackSkipped++;
                stats.stackUnsupported++;
                stats.givenStackSkipped++;
                stats.stackWhy.merge(why, 1L, Long::sum);
                res.outcome = "stack";
                if (STACK_NOTE) {
                    res.stackNote = stackNote(live, me, why);
                }
                return res;
            }
            if (STACK_NOTE && cfg.copyTriggers) {
                res.stackNote = stackNote(live, me, "searched");
            }
        }
        final PhaseHandler ph = live.getPhaseHandler();
        final int turn = ph.getTurn();
        if (turn != departuresTurn) {
            departuresTurn = turn;
            departuresThisTurn = 0;
            departureCounts.clear();
        }
        final long t0 = System.nanoTime();
        final long c0 = TMX.getCurrentThreadCpuTime();
        final java.util.concurrent.atomic.AtomicLong pc = new java.util.concurrent.atomic.AtomicLong();
        poolCpu = pc;
        // the decision's account: the pool threads' CPU and every Forge AI eval thread the decision starts (measurement)
        final java.util.concurrent.atomic.AtomicLong[] prevAcc = forge.ai.CpuAccount.get();
        forge.ai.CpuAccount.set(forge.ai.CpuAccount.plus(prevAcc, pc));
        // lane forge-continue-1011: as in decide, a rebuild's deadline never binds (forge.ai.WallClock); the test is unchanged
        // search-bones-1015: S-b's proposal stage spent part of the decision's budget before this call (0 = none)
        // work-units-1107: decisionStartNanos is on the work clock when one is installed; the own start too
        final long start = decisionStartNanos != 0L ? decisionStartNanos
                : forge.game.WorkClock.active() ? forge.game.WorkClock.nanoTime() : t0;
        final Budget b = cfg.budgetMs > 0 ? new Budget(start + forge.ai.WallClock.budgetMs(cfg.budgetMs) * 1_000_000L) : null;
        budget = b;
        boolean searched = false;
        try {
            searched = decideGiven0(live, me, given, Math.max(1, breadth), index, onStack, ph, turn, res, expander);
        } catch (RuntimeException e) {
            stats.departFallback++;
            res.chosen = 0;
            res.outcome = "failed";
            System.err.println("[lookahead] given search failed at decision " + index + ", playing the default: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
        } finally {
            budget = null;
            if (b != null && b.wallCut) {
                res.wall = true;
            }
            poolCpu = null;
            forge.ai.CpuAccount.set(prevAcc);
            final long dt = System.nanoTime() - t0;
            final long cpu = TMX.getCurrentThreadCpuTime() - c0 + pc.get();
            res.ms = dt / 1e6;
            res.cpuMs = cpu / 1e6;
            res.poolCpuMs = pc.get() / 1e6;
            if (searched) {
                stats.searchNanos += dt;
                stats.maxSearchNanos = Math.max(stats.maxSearchNanos, dt);
                stats.searchMsEach.add(dt / 1e6);
                stats.searchCpuNanos += cpu;
                stats.searchCpuMsEach.add(cpu / 1e6);
            }
        }
        return res;
    }

    private boolean decideGiven0(Game live, Player me, List<SpellAbility> given, int breadth, int index, boolean onStack,
            PhaseHandler ph, int turn, GivenResult res, ChoiceExpander expander) {
        final long decisionSeed = mix(cfg.seed, 0x5eedL + index);
        final List<Cand> all = new ArrayList<>();
        final List<Integer> allAt = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < given.size(); i++) {
            final Cand c = cand(given.get(i), false);
            if (seen.add(c.key())) {
                all.add(c);
                allAt.add(i);
            }
        }
        List<Cand> cands = new ArrayList<>();
        final List<Integer> at = new ArrayList<>();
        guardGiven(live, me, all, allAt, breadth, decisionSeed, cands, at);
        if (cfg.deadFaceUp != AiFixes.Mode.OFF) {
            res.deadFaceUp = deadFaceUpLast; // this decision's turn-up checks (null = none)
        }
        if (cfg.prioGfHold && prioGfGates(live, me, onStack, ph)) {
            // sdcc-c1-priogf-1117 (C1 hold): the pass candidate is searched at the rule's decisions, so the tie set can be
            // the default plus pass even when the breadth cut pass (appended last; the other candidates are unchanged)
            boolean has = false;
            for (Cand c : cands) {
                has |= c.pass;
            }
            for (int i = 0; i < all.size() && !has; i++) {
                if (all.get(i).pass) {
                    cands.add(all.get(i));
                    at.add(allAt.get(i));
                    has = true;
                }
            }
        }
        final List<Object> plans = pendingPlans;
        if (plans != null) {
            // search-bones-1015 (S-b): the turn-plan candidates, after the given ones (their index: given.size() + i)
            for (int i = 0; i < plans.size(); i++) {
                cands.add(new Cand(plans.get(i), pendingPlanLabels.get(i)));
                at.add(given.size() + i);
            }
        }
        if (cands.size() < 2) {
            stats.uncontested++;
            stats.givenUncontested++;
            res.outcome = "uncontested";
            return false;
        }
        stats.searched++;
        if (onStack) {
            stats.stackSearched++;
        }
        lastCandidates = cands;
        int k = Math.max(1, cfg.worlds);
        // copy-fidelity-1010, level 2: a first pass on level-0 copies; if a searched candidate was missing from them, the
        // play-outs again (base candidates, probes, macros) on level-1 copies with the same seeds, the first pass's counts
        // restored. Every other level plays one pass.
        final List<Cand> baseCands = cands;
        final List<Integer> baseAt = new ArrayList<>(at);
        final StatsMark mark = cfg.copyFidelity == 2 ? new StatsMark(stats) : null;
        int n;
        double[][] values;
        Rollout[][] outs;
        boolean[] ok;
        int aborted;
        int[] macroOf;
        try {
            for (int pass = 0; ; pass++) {
                cands = baseCands;
                if (pass > 0) {
                    at.clear();
                    at.addAll(baseAt);
                }
                lastCandidates = cands;
                n = cands.size();
                values = new double[n][k];
                outs = new Rollout[n][k];
                ok = new boolean[n];
                Arrays.fill(ok, true);
                explainChoices = EXPLAIN ? new String[n] : null;
                labelChoices = null;
                labelTargets = null;
                macroOf = null;
                if (expander == null) {
                    aborted = playAll(live, me, cands, null, decisionSeed, new Carried[k], values, outs, ok, null);
                } else {
                    // S-t: the base candidates first (their world-0 play-outs record the choice asks), then the macros
                    choiceTag = true;
                    choiceProbe = true;
                    choiceIndexBase = 0;
                    try {
                        aborted = playAll(live, me, cands, null, decisionSeed, new Carried[k], values, outs, ok, null);
                    } finally {
                        choiceTag = false;
                        choiceProbe = false;
                    }
                    final List<Macro> ms = aborted == 0 ? expander.expand(toArray(at)) : null;
                    if (ms != null && !ms.isEmpty()) {
                        final int nm = ms.size();
                        final List<Cand> mc = new ArrayList<>(nm);
                        for (Macro m : ms) {
                            mc.add(new Cand(cands.get(m.base), m.schedule, m.label));
                        }
                        final double[][] mv = new double[nm][k];
                        final Rollout[][] mo = new Rollout[nm][k];
                        final boolean[] mok = new boolean[nm];
                        Arrays.fill(mok, true);
                        final String[] baseExplain = explainChoices;
                        explainChoices = EXPLAIN ? new String[nm] : null;
                        choiceTag = true;
                        choiceIndexBase = n;
                        try {
                            aborted += playAll(live, me, mc, null, decisionSeed, new Carried[k], mv, mo, mok, null);
                        } finally {
                            choiceTag = false;
                            choiceIndexBase = 0;
                        }
                        if (EXPLAIN) {
                            final String[] ex = Arrays.copyOf(baseExplain, n + nm);
                            System.arraycopy(explainChoices, 0, ex, n, nm);
                            explainChoices = ex;
                        }
                        final List<Cand> merged = new ArrayList<>(cands);
                        merged.addAll(mc);
                        cands = merged;
                        values = Arrays.copyOf(values, n + nm);
                        outs = Arrays.copyOf(outs, n + nm);
                        ok = Arrays.copyOf(ok, n + nm);
                        macroOf = new int[n + nm];
                        Arrays.fill(macroOf, -1);
                        for (int j = 0; j < nm; j++) {
                            values[n + j] = mv[j];
                            outs[n + j] = mo[j];
                            ok[n + j] = mok[j];
                            macroOf[n + j] = j;
                            at.add(at.get(ms.get(j).base));
                        }
                        n += nm;
                        lastCandidates = cands;
                        stats.givenMacroDecisions++;
                        stats.givenMacros += nm;
                    }
                }
                if (pass == 0 && mark != null && aborted == 0 && !pastDeadline() && candidateMissing(outs)) {
                    mark.restore(stats);
                    stats.fidelityRedos++;
                    if (expander != null) {
                        expander.restart();
                    }
                    forceFidelity = true;
                    continue;
                }
                break;
            }
        } finally {
            forceFidelity = false;
        }
        // unified-search-1106 (anytime): which play-outs finished; null = the plain path
        final boolean[][] finished = proof && cfg.anytime ? finishedWorlds(outs, n, k) : null;
        if (finished != null) {
            int otherWorlds = 0;
            for (int c = 0; c < n; c++) {
                boolean any = false;
                for (int w = 0; w < k; w++) {
                    any |= finished[c][w];
                    otherWorlds += c > 0 && finished[c][w] ? 1 : 0;
                }
                ok[c] = any;
            }
            if (aborted > 0 && otherWorlds > 0) {
                aborted = 0;
                stats.partialDecisions++;
                res.partialWorlds = otherWorlds;
            }
        } else if (proof && aborted > 0) {
            // search-bones-1015 (S-b): anytime. Over the budget, the worlds every candidate finished in decide (common
            // random numbers kept), when there are at least two; otherwise the decision is capped as S-c's
            final List<Integer> full = new ArrayList<>();
            for (int w = 0; w < k; w++) {
                boolean every = true;
                for (int c = 0; c < n && every; c++) {
                    every = outs[c][w] != null && !outs[c][w].aborted;
                }
                if (every) {
                    full.add(w);
                }
            }
            if (full.size() >= 2) {
                final int k2 = full.size();
                final double[][] v2 = new double[n][k2];
                final Rollout[][] o2 = new Rollout[n][k2];
                final boolean[] ok2 = new boolean[n];
                for (int c = 0; c < n; c++) {
                    ok2[c] = true;
                    for (int j = 0; j < k2; j++) {
                        v2[c][j] = values[c][full.get(j)];
                        o2[c][j] = outs[c][full.get(j)];
                        ok2[c] &= o2[c][j].ok;
                    }
                }
                values = v2;
                outs = o2;
                ok = ok2;
                k = k2;
                aborted = 0;
                stats.partialDecisions++;
                res.partialWorlds = k2;
            }
        }
        // live-sc-1009: over the wall budget the default is played whatever the values say, so the leaf call is skipped
        // (aborted is always 0 without a budget: the S1 read's path is unchanged)
        if (hookLeaf && aborted == 0) {
            final long fb = stats.leafFallbacks;
            if (cfg.leafOpp) {
                // bvw-step2-fork-1027 (S2T): both seats' frames at every leaf; the pairs go to the result
                final double[][][][] po = new double[1][][][];
                valueLeavesS2t(values, outs, ok, k, po);
                res.pairs = po[0];
            } else {
                valueLeaves(values, outs, ok, k);
            }
            res.leafFallback = stats.leafFallbacks != fb;
        }
        stubValues(cands, values, ok, n, k); // tests only (null: nothing)
        final boolean overBudget = aborted > 0;
        final double[] ev = new double[n];
        for (int c = 0; c < n; c++) {
            double s = 0;
            int kc = 0;
            for (int w = 0; w < k; w++) {
                if (finished == null || finished[c][w]) {
                    s += values[c][w];
                    kc++;
                }
            }
            ev[c] = ok[c] ? s / kc : Double.NaN;
            if (!ok[c]) {
                stats.candidatesDropped++;
                res.failed++;
            }
        }
        // S-t: a macro whose value equals an earlier searched candidate's in every world (typically its base: its
        // forced answer changed nothing the leaf sees) is out of the choice. An exact tie would otherwise be broken by
        // floating-point noise in the service's batching, and the same units would not replay the same game (lane
        // cm-choice-search-1009). Without macros (the S1 path) okArg is ok itself.
        boolean[] okArg = ok;
        if (macroOf != null) {
            okArg = ok.clone();
            for (int c = 0; c < n; c++) {
                if (macroOf[c] < 0 || !ok[c]) {
                    continue;
                }
                for (int i = 0; i < c; i++) {
                    if (okArg[i] && sameValues(values[c], values[i], k)) {
                        okArg[c] = false;
                        stats.givenMacroRedundant++;
                        break;
                    }
                }
            }
        }
        // search-bones-1015 (S-b, proof on): proven wins and losses over the worlds (MCTS-Solver flags)
        final boolean[] provenWin = new boolean[n], provenLoss = new boolean[n];
        final int[] planBroken = new int[n];
        int provenPick = -1;
        if (proof && !overBudget && (finished != null || k >= Math.min(3, Math.max(1, cfg.worlds)))) {
            for (int c = 0; c < n; c++) {
                boolean win = ok[c], loss = ok[c];
                for (int w = 0; w < k; w++) {
                    final Rollout r = outs[c][w];
                    if (finished != null && !finished[c][w]) {
                        loss = false; // unified-search-1106: a world not finished is not evidence for a win
                        continue;
                    }
                    if (r == null || !r.ok) {
                        win = false;
                        loss = false;
                        continue;
                    }
                    if (r.planState == 2) {
                        planBroken[c]++;
                    }
                    if (!(r.pTerminal == 1.0) || r.planState == 2) {
                        win = false;
                    }
                    if (!(r.pTerminal == 0.0)) {
                        loss = false;
                    }
                }
                provenWin[c] = win;
                provenLoss[c] = loss;
            }
            if (provenWin[0]) {
                provenPick = 0;
            } else {
                for (int c = 1; c < n && provenPick < 0; c++) {
                    if (provenWin[c] && okArg[c]) {
                        provenPick = c;
                    }
                }
            }
            if (provenPick < 0 && !provenLoss[0]) {
                okArg = okArg.clone();
                for (int c = 1; c < n; c++) {
                    if (provenLoss[c]) {
                        okArg[c] = false;
                    }
                }
            }
            res.provenWin = provenWin;
            res.provenLoss = provenLoss;
            res.planBroken = planBroken;
        }
        // read only for the anytime chooser, as before review 3 (hooks may be null outside it)
        final double[][] rpArg = finished != null && hooks.reachDepart() > 0 && reachPart != null && reachPart.length == n
                ? reachPart : null;
        final int plainBest = overBudget ? 0 : provenPick >= 0 ? provenPick
                : finished != null ? chooseAnytime(values, finished, okArg, n, k, cfg.departZ, cfg.margin, rootP,
                        rpArg, hooks.reachDepart(), stats)
                : argmax(values, okArg, n, k);
        reachPart = null; // this decision's only
        int best = plainBest;
        if (cfg.departMedian != AiFixes.Mode.OFF && !overBudget && provenPick < 0 && finished == null) {
            final int medBest = argmaxMedian(values, okArg, n, k);
            if (medBest != plainBest) {
                stats.departMedianChanged++;
                if (cfg.departMedian == AiFixes.Mode.ON) {
                    best = medBest;
                }
            }
        }
        // sdcc-c1-priogf-1117 (C1; prioGf 0 = off, the default: this block is skipped before anything is read, copied or
        // drawn). At our own main phase with an empty stack, while our own-turn count is <= prioGfTurns (0 = any), after
        // the argmax and its gates (departZ, median, margin) chose `best`: the eligible candidates whose mean leaf value is
        // within prioGfMargin of the best mean form the tie set. With two or more, each of them and the default is applied
        // on a copy and scored by the goldfish (prioGfRule). Interaction with departZ: the gated `best` stands unless the
        // goldfish winner w of the tie set differs from it AND either w is the default (playing the default needs no gate)
        // or w's goldfish score beats the default's by > 0; then w replaces `best`, exempt from departZ (and from the
        // margin / median gates), but the loop guard, shadow mode, the over-budget cap and a J1 forced replay below still
        // apply. With no line in the tie set, or a tie with the default, nothing changes.
        if (!overBudget && provenPick < 0 && prioGfGates(live, me, onStack, ph)) {
            best = prioGfRule(live, me, cands, ev, okArg, macroOf, best, decisionSeed, res);
        }
        // line-own-target-1119 (ownHarm's demotion, review 3; null = off or nothing refused: unchanged). After C1's
        // goldfish tie-break, so a refused candidate it picks is demoted as well
        final Set<Integer> demote = decisionDemote;
        decisionDemote = null;
        boolean standIn = false;
        if (demote != null && !overBudget) {
            final boolean[] dem = new boolean[n];
            for (int c = 0; c < n; c++) {
                dem[c] = demote.contains(at.get(c)); // a macro is its base's entry; a plan is never refused here
            }
            final int dec = ownHarmDemotion(res, cands, at, values, finished, ev, okArg, dem, n, k, best, provenPick,
                    provenWin, rpArg, rootP);
            if (decisionDemoteApply && dec != best) {
                // the policy's own entry was refused: its stand-in is a departure for the loop guard like any (review 4)
                standIn = dem[0] && dec != 0;
                best = dec;
            }
        }
        if (cfg.deadEtb == AiFixes.Mode.SHADOW && best != 0 && deadEtbKeys.contains(cands.get(best).key())) {
            stats.deadEtbShadowBest++;
        }
        if (cfg.zeroX == AiFixes.Mode.SHADOW && best != 0 && zeroXKeys.contains(cands.get(best).key())) {
            stats.zeroXShadowBest++;
        }
        if (cfg.crewNoop == AiFixes.Mode.SHADOW && best != 0 && crewNoopKeys.contains(cands.get(best).key())) {
            stats.crewNoopShadowBest++;
        }
        if (cfg.deadAltCost == AiFixes.Mode.SHADOW && best != 0 && deadAltCostKeys.contains(cands.get(best).key())) {
            stats.deadAltCostShadowBest++;
        }
        if (cfg.deadPump == AiFixes.Mode.SHADOW && best != 0 && deadPumpKeys.contains(cands.get(best).key())) {
            stats.deadPumpShadowBest++;
        }
        if (cfg.deadFaceUp == AiFixes.Mode.SHADOW && best != 0 && deadFaceUpKeys.contains(cands.get(best).key())) {
            stats.deadFaceUpShadowBest++;
        }
        final java.util.function.IntUnaryOperator to = testOverride;
        final java.util.function.IntSupplier lineOf = explainLine;
        if (to != null && lineOf != null) {
            final int o = to.applyAsInt(lineOf.getAsInt());
            if (o >= 0 && o < n && okArg[o]) {
                best = o; // tests only (J1)
            }
        }
        // J1 (block-search-1019): the live game played another candidate than this search would: play that one (the
        // departure bookkeeping below then follows the live choice, as it did live)
        final String want = decisionForce;
        decisionForce = null;
        boolean forcing = false;
        if (want != null && labelCount(cands, want) > 1) {
            res.forcedMiss = true; // review S2: an ambiguous key
        } else if (want != null) {
            final int natural = wouldPlay(cands, best, overBudget, provenPick, ph, live);
            if (!want.equals(cands.get(natural).label)) {
                final int idx = indexOfLabel(cands, want);
                if (idx >= 0) {
                    best = idx;
                    forcing = true;
                } else {
                    res.forcedMiss = true;
                }
            }
        }
        res.chosen = 0;
        String outcome = "kept";
        if (best != 0 && !cfg.shadow) {
            final Cand chosen = cands.get(best);
            final String key = chosen.key() + "@" + ph.getPhase() + "#" + live.getStack().size();
            final int seenN = departureCounts.getOrDefault(key, 0);
            if (provenPick < 0 && (departuresThisTurn >= cfg.maxDeparturesPerTurn || seenN >= cfg.maxSameDepartures)) {
                stats.loopGuard++;
                outcome = "loop-guard";
                if (standIn) {
                    // line-own-target-1119 (review 4): the guard blocked ownHarm's stand-in. Never the stand-in again
                    // here: RlSearch plays pass in a priority decision with pass legal, else the policy's own choice
                    stats.ownHarmGuard++;
                    res.ownHarmDemotion.addProperty("guardBlocked", chosen.label);
                }
            } else {
                res.chosen = at.get(best);
                res.proven = provenPick >= 0 && best == provenPick;
                stats.departed++;
                if (onStack) {
                    stats.stackDeparted++;
                }
                if (macroOf != null && macroOf[best] >= 0) {
                    // S-t: a macro departure (its base action, then its scheduled choice answers)
                    res.chosenMacro = macroOf[best];
                    stats.givenMacroDepartures++;
                }
                departuresThisTurn++;
                departureCounts.put(key, seenN + 1);
                outcome = "departed";
            }
        } else if (best != 0) {
            outcome = "shadow-would-depart";
        }
        if (overBudget && !forcing) {
            stats.capped++;
            res.chosen = 0;
            res.chosenMacro = -1;
            outcome = "capped";
        }
        res.outcome = outcome;
        res.played = cands.get("departed".equals(outcome) ? best : 0).label;
        if (forcing) {
            res.forced = res.played.equals(want);
            res.forcedMiss = !res.forced;
        }
        res.worlds = k;
        res.ev = ev;
        res.values = values;
        res.givenIndex = new int[n];
        for (int c = 0; c < n; c++) {
            res.givenIndex[c] = at.get(c);
        }
        if (macroOf != null) {
            res.macro = macroOf;
        }
        if (Boolean.getBoolean("lookahead.trace")) {
            final StringBuilder tb = new StringBuilder("[ltrace] given d=").append(index).append(" T").append(turn).append(' ')
                    .append(ph.getPhase()).append(" best=").append(best).append(" out=").append(outcome);
            for (int c = 0; c < n; c++) {
                tb.append(" | ").append(cands.get(c).label.replace('|', '/')).append(ok[c] ? "" : " FAIL");
                for (int w = 0; w < k; w++) {
                    tb.append(' ').append(Double.doubleToLongBits(values[c][w]));
                }
            }
            System.err.println(tb);
        }
        if (EXPLAIN) {
            final JsonObject x = new JsonObject();
            x.addProperty("given", true);
            x.addProperty("decision", index);
            if (lineOf != null) {
                x.addProperty("line", lineOf.getAsInt()); // J1: the decision line's index
            }
            x.addProperty("played", res.played);
            x.addProperty("turn", turn);
            x.addProperty("phase", String.valueOf(ph.getPhase()));
            x.addProperty("best", best);
            x.addProperty("outcome", outcome);
            if (res.deadFaceUp != null) {
                x.add("deadFaceUp", res.deadFaceUp); // deadFaceUp on or shadow, with a turn-up among the given candidates
            }
            final JsonArray ca = new JsonArray();
            for (int c = 0; c < n; c++) {
                final JsonObject co = new JsonObject();
                co.addProperty("label", cands.get(c).label);
                co.addProperty("choices", explainChoices != null && explainChoices[c] != null ? explainChoices[c] : "");
                co.addProperty("ok", ok[c]);
                co.addProperty("ev", ok[c] ? ev[c] : null);
                if (cfg.deadFaceUp == AiFixes.Mode.SHADOW && deadFaceUpKeys.contains(cands.get(c).key())) {
                    co.addProperty("deadFaceUp", true);
                }
                final JsonArray vs = new JsonArray();
                for (int w = 0; w < k; w++) {
                    vs.add(values[c][w]);
                }
                co.add("values", vs);
                ca.add(co);
            }
            x.add("candidates", ca);
            System.err.println("[lookahead-explain] " + x);
        }
        return true;
    }

    // ------------------------------------------------------------------ C1: the goldfish tie-break (sdcc-c1-priogf-1117)

    /**
     * C1: the rule's decision gates (prioGf on, the hooks set, our own main phase with an empty stack, our own-turn count
     * at most prioGfTurns, 0 = any). False at once with prioGf 0 (nothing else is read).
     */
    private boolean prioGfGates(Game live, Player me, boolean onStack, PhaseHandler ph) {
        return cfg.prioGf > 0 && hooks != null && !onStack && ph.isPlayerTurn(me) && ph.getPhase() != null
                && ph.getPhase().isMain() && (cfg.prioGfTurns <= 0 || me.getTurn() <= cfg.prioGfTurns);
    }

    /** C1: main-loop steps a candidate's copy may take to reach our next priority with an empty stack. */
    static final int PRIO_GF_SETTLE = 400;

    /**
     * C1: the tie set. Candidate c is in it when eligible, ok, and its mean leaf value is within {@code margin} of the
     * best ok mean (ev NaN = failed, never in it).
     */
    public static boolean[] prioGfTieSet(double[] ev, boolean[] ok, boolean[] eligible, double margin) {
        final int n = ev.length;
        double top = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < n; c++) {
            if (ok[c] && !Double.isNaN(ev[c]) && ev[c] > top) {
                top = ev[c];
            }
        }
        final boolean[] in = new boolean[n];
        for (int c = 0; c < n; c++) {
            in[c] = ok[c] && eligible[c] && !Double.isNaN(ev[c]) && ev[c] >= top - margin;
        }
        return in;
    }

    /**
     * C1: the selection rule (pure). {@code gf[c]} is candidate c's goldfish score (NaN = none, read as 0), computed for
     * the tie set {@code in} and the default (index 0); {@code current} is the gated argmax. The winner w is the tie-set
     * candidate with the largest score; ties go to {@code current}, then the larger ev, then the lower index. Returns -1
     * (nothing changes) when the tie set has fewer than two candidates, no tie-set candidate has a line (score > 0), or
     * w == current; else w when w is the default (index 0) or score(w) > score(default); else -1.
     */
    public static int prioGfSelect(double[] ev, boolean[] in, double[] gf, int current) {
        return prioGfSelect(ev, in, gf, current, null);
    }

    /**
     * C1 with hold ({@code hold} null = off: exactly the rule above). {@code hold[c]}: candidate c spends no card. Among
     * equal scores a holding candidate goes first, then the order above (current pick, larger ev, lower index). A
     * holding winner that ties the default's score while the default spends a card also replaces the choice (the hold
     * is the tie-break against the default); everything else as above.
     */
    public static int prioGfSelect(double[] ev, boolean[] in, double[] gf, int current, boolean[] hold) {
        int m = 0;
        int w = -1;
        double wg = 0;
        for (int c = 0; c < in.length; c++) {
            if (!in[c]) {
                continue;
            }
            m++;
            final double g = Double.isNaN(gf[c]) ? 0.0 : gf[c];
            if (w < 0 || g > wg || g == wg && (hold != null && hold[c] != hold[w] ? hold[c]
                    : c == current || w != current && ev[c] > ev[w])) {
                w = c;
                wg = g;
            }
        }
        if (m < 2 || !(wg > 0) || w == current) {
            return -1;
        }
        if (w == 0) {
            return 0;
        }
        final double g0 = Double.isNaN(gf[0]) ? 0.0 : gf[0];
        if (wg > g0) {
            return w;
        }
        return hold != null && wg == g0 && hold[w] && !hold[0] ? w : -1;
    }

    /** C1: the rule at one decision (see the call site for the gates); returns the candidate to play (searched index). */
    private int prioGfRule(Game live, Player me, List<Cand> cands, double[] ev, boolean[] ok, int[] macroOf, int best,
            long decisionSeed, GivenResult res) {
        final int n = cands.size();
        final boolean[] eligible = new boolean[n];
        for (int c = 0; c < n; c++) {
            final Cand cd = cands.get(c);
            // base candidates only: no S-t macro (a forced-answer schedule), S-b plan or block candidate
            eligible[c] = (macroOf == null || macroOf[c] < 0) && cd.plan == null && cd.block == null && cd.schedule == null;
        }
        final boolean[] in = prioGfTieSet(ev, ok, eligible, cfg.prioGfMargin);
        int m = 0;
        for (boolean b : in) {
            m += b ? 1 : 0;
        }
        if (m < 2) {
            return best;
        }
        final double[] gf = new double[n];
        Arrays.fill(gf, Double.NaN);
        final boolean[] hold = cfg.prioGfHold ? new boolean[n] : null;
        final long seed = mix(decisionSeed, 0xC1F15L);
        final int share = m + (in[0] ? 0 : 1);
        for (int c = 0; c < n; c++) {
            if ((in[c] || c == 0) && eligible[c]) {
                final boolean[] noSpend = {false};
                gf[c] = prioGfOf(live, me, cands.get(c), seed, share, noSpend);
                if (hold != null) {
                    hold[c] = cands.get(c).pass || noSpend[0];
                }
            }
        }
        final int pick = prioGfSelect(ev, in, gf, best, hold);
        final int played = pick < 0 ? best : pick;
        final StringBuilder sb = new StringBuilder("cands=[");
        boolean first = true;
        for (int c = 0; c < n; c++) {
            if (!in[c] && c != 0) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(c).append(':').append(cands.get(c).label.replaceAll("[\\[\\],:]", "_")).append(':')
                    .append(Double.isNaN(ev[c]) ? "nan" : String.format(java.util.Locale.ROOT, "%.4f", ev[c])).append(':')
                    .append(Double.isNaN(gf[c]) ? "-1" : String.format(java.util.Locale.ROOT, "%.3f", gf[c]));
            if (hold != null && hold[c]) {
                sb.append(":hold"); // C1 hold: spends no card
            }
            if (!in[c]) {
                sb.append(":out"); // the default outside the tie set: scored for the comparison only
            }
        }
        sb.append("] base=").append(best).append(" pick=").append(played).append(" changed=").append(played != best);
        res.prioGf = sb.toString();
        return played;
    }

    /**
     * C1: candidate {@code c} applied on one copy of the live game (a world drawn as the search's own: the belief
     * re-draw of the hidden cards, our library below its known top), settled to our next priority with an empty stack
     * (no opponent acts: a goldfish), and scored there by {@link SearchHooks#prioGoldfish}. The candidate's choices are
     * Forge AI's or its target variant ({@link #prepare(Game, Player, Cand, SpellAbility, GameCopier)}). A terminal
     * position scores 1 (we won) or 0; NaN = the candidate could not be applied or did not settle.
     */
    private double prioGfOf(Game live, Player liveMe, Cand c, long seed, int share, boolean[] noSpend) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player me;
            final Object ctx;
            SpellAbility sa = null;
            synchronized (live) {
                final GameCopier copier = copierOf(live);
                copier.setCopyStack(cfg.stack);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                if (worldMode == WORLD_BELIEF) {
                    redraw(live, liveMe, g, me, new Random(mix(seed, 2)));
                    if (ownKnownTop > 0) {
                        restoreOwnTop(liveMe, me, ownKnownTop);
                    }
                    if (beliefWorldDecks()) {
                        worldDecks(g, me);
                    }
                }
                MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 3)));
                ctx = hooks.onCopy(g, me);
                if (!c.pass) {
                    sa = prepare(g, me, c, null, copier);
                    if (sa == null) {
                        return Double.NaN;
                    }
                }
            }
            final double[] score = {Double.NaN};
            final boolean[] reached = {false};
            // C1 hold: our cards in hand plus the permanents we control, before the candidate (the copy as drawn)
            final int cards0 = me.getCardsIn(ZoneType.Hand).size() + me.getCardsIn(ZoneType.Battlefield).size();
            final List<SpellAbility> firstList = sa == null ? null : new ArrayList<>(List.of(sa));
            final int horizon = cfg.prioGf;
            me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
                private boolean used = false;

                @Override
                public List<SpellAbility> chooseSpellAbilityToPlay() {
                    if (!used) {
                        used = true;
                        return firstList; // the candidate (null: the pass candidate passes)
                    }
                    if (!getGame().getStack().isEmpty()) {
                        return null; // let our own spell and its triggers resolve
                    }
                    if (!reached[0]) {
                        reached[0] = true;
                        final Player p = getPlayer();
                        noSpend[0] = p.getCardsIn(ZoneType.Hand).size() + p.getCardsIn(ZoneType.Battlefield).size() >= cards0;
                        score[0] = hooks.prioGoldfish(ctx, getGame(), p, horizon, seed, share);
                    }
                    return null;
                }
            });
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    // a goldfish: the opponents never act (their other answers stay Forge AI's)
                    o.dangerouslySetController(new PlayerControllerAi(g, o, o.getController().getLobbyPlayer()) {
                        @Override
                        public List<SpellAbility> chooseSpellAbilityToPlay() {
                            return null;
                        }
                    });
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            givePriority(ph, me);
            int steps = 0;
            while (!g.isGameOver() && !reached[0] && steps < PRIO_GF_SETTLE) {
                ph.mainLoopStep();
                steps++;
            }
            if (!reached[0] && (g.isGameOver() || !me.isInGame())) {
                return me.hasWon() ? 1.0 : 0.0;
            }
            return score[0];
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("[lookahead] prioGf copy failed: " + e);
            return Double.NaN;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** S-t: values within 1e-6 of each other in every world (P(win) scale; the leaf is float32). */
    static boolean sameValues(double[] a, double[] b, int k) {
        for (int w = 0; w < k; w++) {
            if (!(Math.abs(a[w] - b[w]) <= 1e-6)) {
                return false;
            }
        }
        return true;
    }

    private static int[] toArray(List<Integer> xs) {
        final int[] a = new int[xs.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = xs.get(i);
        }
        return a;
    }

    /**
     * S1: the given candidates in order, up to {@code breadth}, with the guards applied as {@link #enumerate} applies them
     * (in an enumeration copy; ON drops a guarded candidate other than the default and the next one takes its slot,
     * SHADOW keeps it and records its key). Never throws: on a copy failure the first {@code breadth} are taken unguarded.
     */
    private void guardGiven(Game live, Player liveMe, List<Cand> all, List<Integer> allAt, int breadth, long decisionSeed,
            List<Cand> out, List<Integer> outAt) {
        final boolean guard = cfg.deadEtb != AiFixes.Mode.OFF;
        final boolean zguard = cfg.zeroX != AiFixes.Mode.OFF;
        final boolean cguard = cfg.crewNoop != AiFixes.Mode.OFF;
        final boolean aguard = cfg.deadAltCost != AiFixes.Mode.OFF;
        final boolean pguard = cfg.deadPump != AiFixes.Mode.OFF;
        final boolean dguard = cfg.deadFaceUp != AiFixes.Mode.OFF;
        if (!guard && !zguard && !cguard && !aguard && !pguard && !dguard) {
            for (int i = 0; i < all.size() && out.size() < breadth; i++) {
                out.add(all.get(i));
                outAt.add(allAt.get(i));
            }
            return;
        }
        deadEtbKeys.clear();
        zeroXKeys.clear();
        crewNoopKeys.clear();
        deadAltCostKeys.clear();
        deadPumpKeys.clear();
        deadFaceUpKeys.clear();
        deadFaceUpLast = null;
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 7)));
        final Object prevAiCache1 = AiCache.captureScope(); // restored below, never removed (k8-determinism-1008)
        AiCache.openScope();
        final Object prevIds = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        int deadHere = 0, zeroHere = 0, crewHere = 0, altHere = 0, pumpHere = 0, faceHere = 0;
        final List<Cand> o = new ArrayList<>();
        final List<Integer> oAt = new ArrayList<>();
        final DfuTally dfu = dguard ? new DfuTally(() -> pumpKnownIds(live, liveMe), () -> dfuLivePending(live)) : null;
        try {
            final Set<Integer> pumpKnown = pguard ? pumpKnownIds(live, liveMe) : null; // the seat's knowledge, from the live game
            final GameCopier copier = copierOf(live);
            copier.setCopyStack(cfg.stack);
            final Game g = copier.makeCopy();
            final Player me = (Player) copier.find(liveMe);
            for (int i = 0; i < all.size() && o.size() < breadth; i++) {
                final Cand c = all.get(i);
                final SpellAbility sa = c.pass ? null : locate(g, me, c);
                if (sa != null) {
                    sa.setActivatingPlayer(me);
                }
                final boolean dead = guard && sa != null && deadEtbPermanent(sa, me);
                final boolean zero = zguard && sa != null && zeroXSpell(sa, me);
                final boolean crew = cguard && sa != null && crewNoop(sa, me);
                final boolean alt = aguard && sa != null && deadAltCost(sa, me);
                final boolean pump = pguard && sa != null && deadPump(sa, me, pumpKnown);
                final boolean face = dguard && DFU_DEAD.equals(dfu.check(sa, me));
                if (i == 0) {
                    // the default is never dropped (as Forge's answer in enumerate)
                    if (dead) {
                        stats.deadEtbForge++;
                    }
                    if (zero) {
                        stats.zeroXForge++;
                    }
                    if (crew) {
                        stats.crewNoopForge++;
                    }
                    if (alt) {
                        stats.deadAltCostForge++;
                    }
                    if (pump) {
                        stats.deadPumpForge++;
                    }
                    if (face) {
                        stats.deadFaceUpForge++;
                        dfu.defaultDead++;
                    }
                } else {
                    if (dead) {
                        deadHere++;
                        stats.deadEtbCands++;
                        if (cfg.deadEtb == AiFixes.Mode.ON) {
                            stats.deadEtbDropped++;
                            continue;
                        }
                        deadEtbKeys.add(c.key());
                    }
                    if (zero) {
                        zeroHere++;
                        stats.zeroXCands++;
                        if (cfg.zeroX == AiFixes.Mode.ON) {
                            stats.zeroXDropped++;
                            continue;
                        }
                        zeroXKeys.add(c.key());
                    }
                    if (crew) {
                        crewHere++;
                        stats.crewNoopCands++;
                        if (cfg.crewNoop == AiFixes.Mode.ON) {
                            stats.crewNoopDropped++;
                            continue;
                        }
                        crewNoopKeys.add(c.key());
                    }
                    if (alt) {
                        altHere++;
                        stats.deadAltCostCands++;
                        if (cfg.deadAltCost == AiFixes.Mode.ON) {
                            stats.deadAltCostDropped++;
                            continue;
                        }
                        deadAltCostKeys.add(c.key());
                    }
                    if (pump) {
                        pumpHere++;
                        stats.deadPumpCands++;
                        if (cfg.deadPump == AiFixes.Mode.ON) {
                            stats.deadPumpDropped++;
                            continue;
                        }
                        deadPumpKeys.add(c.key());
                    }
                    if (face) {
                        faceHere++;
                        dfu.dead++;
                        stats.deadFaceUpCands++;
                        if (cfg.deadFaceUp == AiFixes.Mode.ON) {
                            stats.deadFaceUpDropped++;
                            continue;
                        }
                        deadFaceUpKeys.add(c.key());
                    }
                }
                o.add(c);
                oAt.add(allAt.get(i));
            }
            if (deadHere > 0) {
                stats.deadEtbDecisions++;
            }
            if (zeroHere > 0) {
                stats.zeroXDecisions++;
            }
            if (crewHere > 0) {
                stats.crewNoopDecisions++;
            }
            if (altHere > 0) {
                stats.deadAltCostDecisions++;
            }
            if (pumpHere > 0) {
                stats.deadPumpDecisions++;
            }
            if (faceHere > 0) {
                stats.deadFaceUpDecisions++;
            }
            if (dfu != null) {
                deadFaceUpLast = dfu.finish(stats, cfg.deadFaceUp);
            }
        } catch (RuntimeException e) {
            o.clear();
            oAt.clear();
            for (int i = 0; i < all.size() && o.size() < breadth; i++) {
                o.add(all.get(i));
                oAt.add(allAt.get(i));
            }
        } finally {
            AiCache.installScope(prevAiCache1);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        out.addAll(o);
        outAt.addAll(oAt);
    }

    /**
     * S1 value leaf: replace one decision's static leaf values by the hooks' P(win) (terminal play-outs 1 / 0 / 0.5). One
     * call carries every non-terminal leaf, world-major then candidate-minor. On a failure the decision keeps its static
     * values (all of them: a decision never mixes the two scales) and the fallback is counted. Every fallback is said on
     * stderr with its cause ("[lookahead] value leaf failed, static fallback: ...", bvw-p3-batch-1023).
     */
    /** solver-distill-cc-1109: the last valued decision's reach part per candidate and world (null = the rule is off). */
    private double[][] reachPart;

    private void valueLeaves(double[][] values, Rollout[][] outs, boolean[] ok, int k) {
        if (cfg.leafOpp) {
            valueLeavesS2t(values, outs, ok, k, null);
            return;
        }
        final int n = values.length;
        reachPart = hooks.reachDepart() > 0 ? new double[n][k] : null;
        final List<Object> leaves = new ArrayList<>();
        final List<int[]> where = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && Double.isNaN(r.pTerminal)) {
                    if (r.leafPayload == null) {
                        stats.leafFallbacks++;
                        stats.leafLastError = "a non-terminal play-out has no leaf payload";
                        staticFallbackLine(-1);
                        return;
                    }
                    leaves.add(r.leafPayload);
                    where.add(new int[] {c, w});
                }
            }
        }
        double[] p = null;
        if (!leaves.isEmpty()) {
            final long t = System.nanoTime();
            boolean threw = false;
            try {
                p = hooks.leafValues(leaves);
            } catch (RuntimeException e) {
                p = null;
                threw = true;
                stats.leafLastError = String.valueOf(e);
            }
            stats.leafNanos += System.nanoTime() - t;
            if (p == null || p.length != leaves.size()) {
                stats.leafFallbacks++;
                if (p != null) {
                    stats.leafLastError = "leaf values: " + p.length + " for " + leaves.size() + " leaves";
                } else if (!threw) {
                    // bvw-p3-batch-1023: the hook's own reason (it used to be lost: "static fallback: null")
                    final String why = hooks.leafError();
                    stats.leafLastError = why != null ? why : "the value hook returned no values";
                }
                staticFallbackLine(leaves.size());
                return;
            }
            stats.leafCalls++;
            stats.leafLeaves += leaves.size();
        }
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && !Double.isNaN(r.pTerminal)) {
                    values[c][w] = r.pTerminal;
                }
            }
        }
        for (int i = 0; i < where.size(); i++) {
            final int c = where.get(i)[0], w = where.get(i)[1];
            final double[] rr = outs[c][w].leafReach;
            values[c][w] = rr == null ? p[i] : hooks.combineReach(p[i], rr);
            if (rr != null) {
                stats.noteReach(rr);
                hooks.noteLeaf(outs[c][w].leafPayload, p[i], rr);
                if (reachPart != null && c < reachPart.length && w < reachPart[c].length) {
                    reachPart[c][w] = hooks.reachPart(rr);
                }
            }
        }
    }

    /**
     * bvw-step2-fork-1027 (S2T, {@link Config#leafOpp}): the value leaves with both seats' frames. Every non-terminal leaf
     * has the searching seat's frame (v_s) and the opponent's own frame at the same moment (v_o). With
     * {@link Config#leafSym} the leaf is (v_s + 1 - v_o) / 2 and both go in ONE call (the searching seat's frames first,
     * then the opponent's, world-major then candidate-minor); without it the leaf is v_s from exactly step 1's call, and
     * v_o comes from a second call afterwards (logging only: a failure there is counted, never a fallback). Terminal
     * play-outs stay 1 / 0 / 0.5 (v_o = 1 - v_s). {@code pairs} (null = none): [v_s, v_o, terminal (NaN = a leaf)] per
     * candidate and world, NaN where unknown. A missing frame or a failed decision call is the static fallback.
     */
    private void valueLeavesS2t(double[][] values, Rollout[][] outs, boolean[] ok, int k, double[][][][] pairsOut) {
        final int n = values.length;
        reachPart = hooks.reachDepart() > 0 ? new double[n][k] : null;
        final double[][][] pairs = new double[3][n][k];
        for (double[][] a : pairs) {
            for (double[] b : a) {
                Arrays.fill(b, Double.NaN);
            }
        }
        if (pairsOut != null) {
            pairsOut[0] = pairs;
        }
        final List<Object> leaves = new ArrayList<>();
        final List<Object> opp = new ArrayList<>();
        final List<int[]> where = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && Double.isNaN(r.pTerminal)) {
                    if (r.leafPayload == null || cfg.leafSym && r.leafPayloadOpp == null) {
                        stats.leafFallbacks++;
                        stats.leafLastError = r.leafPayload == null ? "a non-terminal play-out has no leaf payload"
                                : "a non-terminal play-out has no opponent leaf payload";
                        staticFallbackLine(-1);
                        return;
                    }
                    leaves.add(r.leafPayload);
                    opp.add(r.leafPayloadOpp); // null (no v_o) only without leafSym
                    where.add(new int[] {c, w});
                }
            }
        }
        final int m = leaves.size();
        double[] ps = null, po = null;
        if (m > 0) {
            final List<Object> batch = cfg.leafSym ? new ArrayList<>(leaves) : leaves;
            if (cfg.leafSym) {
                batch.addAll(opp);
            }
            final long t = System.nanoTime();
            double[] p = null;
            boolean threw = false;
            try {
                p = hooks.leafValues(batch);
            } catch (RuntimeException e) {
                p = null;
                threw = true;
                stats.leafLastError = String.valueOf(e);
            }
            stats.leafNanos += System.nanoTime() - t;
            if (p == null || p.length != batch.size()) {
                stats.leafFallbacks++;
                if (p != null) {
                    stats.leafLastError = "leaf values: " + p.length + " for " + batch.size() + " leaves";
                } else if (!threw) {
                    final String why = hooks.leafError();
                    stats.leafLastError = why != null ? why : "the value hook returned no values";
                }
                staticFallbackLine(batch.size());
                return;
            }
            stats.leafCalls++;
            stats.leafLeaves += batch.size();
            ps = cfg.leafSym ? Arrays.copyOfRange(p, 0, m) : p;
            if (cfg.leafSym) {
                po = Arrays.copyOfRange(p, m, 2 * m);
            } else {
                // logging only: the opponent's frames after the decision's values are fixed (a missing one: no v_o)
                final List<Object> have = new ArrayList<>();
                for (Object x : opp) {
                    if (x != null) {
                        have.add(x);
                    }
                }
                double[] got = null;
                if (!have.isEmpty()) {
                    try {
                        got = hooks.leafValues(have);
                    } catch (RuntimeException e) {
                        got = null;
                    }
                }
                if (have.size() < m) {
                    stats.leafOppFailures++;
                }
                if (!have.isEmpty() && (got == null || got.length != have.size())) {
                    stats.leafOppFailures++;
                } else if (!have.isEmpty()) {
                    stats.leafCalls++;
                    stats.leafLeaves += have.size();
                    po = new double[m];
                    for (int i = 0, j = 0; i < m; i++) {
                        po[i] = opp.get(i) == null ? Double.NaN : got[j++];
                    }
                }
            }
        }
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && !Double.isNaN(r.pTerminal)) {
                    values[c][w] = r.pTerminal;
                    pairs[0][c][w] = r.pTerminal;
                    pairs[1][c][w] = 1.0 - r.pTerminal;
                    pairs[2][c][w] = r.pTerminal;
                }
            }
        }
        for (int i = 0; i < m; i++) {
            final int c = where.get(i)[0], w = where.get(i)[1];
            final double vs = ps[i];
            final double vo = po == null ? Double.NaN : po[i];
            values[c][w] = cfg.leafSym ? (vs + 1.0 - vo) / 2.0 : vs;
            final double[] rr = outs[c][w].leafReach;
            if (rr != null) {
                hooks.noteLeaf(outs[c][w].leafPayload, values[c][w], rr);
                values[c][w] = hooks.combineReach(values[c][w], rr);
                stats.noteReach(rr);
                if (reachPart != null && c < reachPart.length && w < reachPart[c].length) {
                    reachPart[c][w] = hooks.reachPart(rr);
                }
            }
            pairs[0][c][w] = vs;
            pairs[1][c][w] = vo;
        }
    }

    /**
     * Every value-leaf static fallback, loud (bvw-p3-batch-1023): the cause, the leaves that keep static values (-1 =
     * not collected) and the running count. The line's prefix is the one Stage A's leafq.py counts.
     */
    private void staticFallbackLine(int leaves) {
        System.err.println("[lookahead] value leaf failed, static fallback: " + stats.leafLastError + " [STATIC leaf values"
                + (leaves >= 0 ? " for " + leaves + " leaves" : "") + "; leafFallbacks " + stats.leafFallbacks + "]");
    }

    /**
     * Label dump: one searched decision as a JSON object for the sink. Reads only the search's own arrays, the
     * enumeration copy's root state, Forge AI's answer and turn/phase/stack/mulligan counters of the live game. Never
     * throws into the game: a failure is counted and the decision stands.
     */
    private void emitLabel(Game live, Player me, List<SpellAbility> def, int index, int turn, PhaseHandler ph, List<Cand> cands,
            int bSize, double[][] values, boolean[] ok, int k, int best, String outcome, boolean overBudget, int aborted, long dt,
            JsonObject root, double[][] staticValues, boolean leafFallback) {
        try {
            final JsonObject o = new JsonObject();
            o.addProperty("schema", LABEL_SCHEMA);
            if (model != null) {
                // EI cycle 2: v = the learned leaf's per-world values (a terminal play-out 1 / 0 / 0.5), vs = Forge's
                // static values of the same play-outs; leafFallback = the service failed and v is static here too.
                o.addProperty("leaf", "model");
                o.addProperty("leafFallback", leafFallback);
            }
            o.addProperty("decision", index);
            o.addProperty("turn", turn);
            o.addProperty("phase", String.valueOf(ph.getPhase()));
            o.addProperty("active", ph.getPlayerTurn() == me);
            o.addProperty("stack", live.getStack().size());
            o.addProperty("seat", forge.bench.StateEncoder.playerIndex(live, me));
            final JsonArray mull = new JsonArray();
            for (Player pl : live.getRegisteredPlayers()) {
                mull.add(pl.getStats().getMulliganCount());
            }
            o.add("mulligans", mull);
            o.addProperty("worlds", k);
            o.addProperty("bSize", bSize);
            o.addProperty("tail", labelTail);
            o.addProperty("best", best);
            o.addProperty("outcome", outcome);
            o.addProperty("capped", overBudget);
            o.addProperty("aborted", aborted);
            o.addProperty("searchMs", Math.round(dt / 1e5) / 10.0);
            if (forgeNanos >= 0) {
                o.addProperty("forgeMs", Math.round(forgeNanos / 1e5) / 10.0);
            }
            final JsonArray ca = new JsonArray();
            for (int c = 0; c < cands.size(); c++) {
                final Cand cd = cands.get(c);
                final JsonObject co = new JsonObject();
                co.addProperty("key", cd.key());
                co.addProperty("label", cd.label);
                co.addProperty("kind", cd.kind);
                co.addProperty("hostId", cd.hostId);
                co.addProperty("isDefault", cd.isDefault);
                if (cd.tgt != null) {
                    co.addProperty("tgt", cd.tgt);
                }
                JsonArray tg = labelTargets[c];
                String ch = labelChoices[c];
                if (tg == null && c == 0 && def != null && !def.isEmpty()) {
                    // A reused world 0 never prepared Forge's answer: its targets are the live answer's own.
                    tg = targetRefs(live, def);
                    ch = describeChoices(def);
                }
                co.addProperty("choices", ch == null ? "" : ch);
                co.add("targets", tg == null ? new JsonArray() : tg);
                co.addProperty("ok", ok[c]);
                final JsonArray vs = new JsonArray();
                for (int w = 0; w < k; w++) {
                    final double v = values[c][w];
                    if (Double.isFinite(v)) {
                        vs.add(v);
                    } else {
                        vs.add(com.google.gson.JsonNull.INSTANCE);
                    }
                }
                co.add("v", vs);
                if (staticValues != null) {
                    final JsonArray ss = new JsonArray();
                    for (int w = 0; w < k; w++) {
                        final double v = staticValues[c][w];
                        if (Double.isFinite(v)) {
                            ss.add(v);
                        } else {
                            ss.add(com.google.gson.JsonNull.INSTANCE);
                        }
                    }
                    co.add("vs", ss);
                }
                if (cfg.deadEtb == AiFixes.Mode.SHADOW && deadEtbKeys.contains(cd.key())) {
                    co.addProperty("deadEtb", true);
                }
                if (cfg.zeroX == AiFixes.Mode.SHADOW && zeroXKeys.contains(cd.key())) {
                    co.addProperty("zeroX", true);
                }
                if (cfg.deadAltCost == AiFixes.Mode.SHADOW && deadAltCostKeys.contains(cd.key())) {
                    co.addProperty("deadAltCost", true);
                }
                if (cfg.deadPump == AiFixes.Mode.SHADOW && deadPumpKeys.contains(cd.key())) {
                    co.addProperty("deadPump", true);
                }
                if (cfg.deadFaceUp == AiFixes.Mode.SHADOW && deadFaceUpKeys.contains(cd.key())) {
                    co.addProperty("deadFaceUp", true);
                }
                ca.add(co);
            }
            o.add("cands", ca);
            if (root == null) {
                stats.labelRootFailures++;
                o.add("root", com.google.gson.JsonNull.INSTANCE);
                if (labelRootError != null) {
                    o.addProperty("rootError", labelRootError);
                }
            } else {
                o.add("root", root);
            }
            labelSink.accept(o);
            stats.labels++;
        } catch (RuntimeException e) {
            stats.labelFailures++;
            stats.labelLastError = String.valueOf(e);
        }
    }

    /**
     * EI ranker seat (Config#rankerUrl / #placeboRate): rank the enumeration's B set without play-outs and play the
     * pick. The departure bookkeeping (per-turn cap, the same-action loop guard, mapping into the live game) is the
     * search's own.
     */
    private List<SpellAbility> decideRanked(PlayerControllerAi ctrl, List<SpellAbility> def, Game live, Player me, int index,
            PhaseHandler ph, int turn, long t0, List<Cand> cands, long decisionSeed, SpellAbility defSa, JsonObject root) {
        stats.rankerDecisions++;
        final int n = cands.size();
        int pick = 0;
        if (cfg.placeboRate > 0) {
            final Random pr = new Random(mix(cfg.seed, 0x9a1aceb0L + index));
            if (pr.nextDouble() < cfg.placeboRate) {
                pick = 1 + pr.nextInt(n - 1);
                stats.placeboDraws++;
            }
        } else {
            final JsonObject req = root == null ? null : rankRequest(live, me, ph, turn, cands, decisionSeed, defSa, root);
            if (req == null) {
                stats.rankerFailures++;
                stats.rankerLastError = root == null ? "root: " + labelRootError : "request";
            } else {
                final long a = System.nanoTime();
                final double[] p = ranker.rank(req, n);
                final long dt = System.nanoTime() - a;
                stats.rankerCalls++;
                stats.rankerNanos += dt;
                stats.rankerMaxNanos = Math.max(stats.rankerMaxNanos, dt);
                if (p == null) {
                    stats.rankerFailures++;
                    stats.rankerLastError = ranker.lastError;
                    System.err.println("[ranker] call failed at decision " + index + ", playing Forge's answer: " + ranker.lastError);
                } else {
                    for (int c = 1; c < n; c++) {
                        if (p[c] > p[pick]) {   // ties to the lower index: Forge's answer first
                            pick = c;
                        }
                    }
                }
                stats.rankerDigest = ranker.digestHex();
                stats.rankerUnknownCards = ranker.unknownCards;
            }
        }
        List<SpellAbility> answer = def;
        String outcome = "kept";
        if (pick != 0) {
            stats.rankerNonDefault++;
            if (cfg.rankerShadow) {
                outcome = "shadow-would-depart";
            } else {
                final Cand chosen = cands.get(pick);
                final String key = chosen.key() + "@" + ph.getPhase() + "#" + live.getStack().size();
                final int seen = departureCounts.getOrDefault(key, 0);
                if (departuresThisTurn >= cfg.maxDeparturesPerTurn || seen >= 2) {
                    stats.loopGuard++;
                    outcome = "loop-guard";
                } else {
                    final List<SpellAbility> mapped = mapToLive(ctrl, live, me, chosen);
                    if (mapped == null && !chosen.pass) {
                        stats.departFallback++;
                        outcome = "map-failed";
                    } else {
                        answer = mapped;
                        stats.departed++;
                        stats.rankerDepartKinds.merge(chosen.kind, 1L, Long::sum);
                        departuresThisTurn++;
                        departureCounts.put(key, seen + 1);
                        outcome = "departed";
                    }
                }
            }
        }
        final long dt = System.nanoTime() - t0;
        stats.searchNanos += dt;
        stats.maxSearchNanos = Math.max(stats.maxSearchNanos, dt);
        stats.searchMsEach.add(dt / 1e6);
        if (cfg.decisionLog) {
            final JsonObject d = new JsonObject();
            d.addProperty("decision", index);
            d.addProperty("turn", turn);
            d.addProperty("phase", String.valueOf(ph.getPhase()));
            d.addProperty("candidates", n);
            d.addProperty("pick", pick);
            d.addProperty("outcome", outcome);
            d.addProperty("ms", Math.round(dt / 1e5) / 10.0);
            System.err.println("[ranker-decision] " + d);
        }
        return answer;
    }

    /**
     * EI ranker request (schema {@link RankerClient#REQUEST_SCHEMA}): the decision as the label dump records it -- each
     * candidate prepared in the search's world-0 copy (the same seed and re-draw as a K-world search's world 0) for the
     * targets Forge AI would play it with; null if a preparation throws.
     */
    private JsonObject rankRequest(Game live, Player me, PhaseHandler ph, int turn, List<Cand> cands, long decisionSeed,
            SpellAbility defSa, JsonObject root) {
        try {
            final JsonObject req = new JsonObject();
            req.addProperty("schema", RankerClient.REQUEST_SCHEMA);
            req.addProperty("seat", forge.bench.StateEncoder.playerIndex(live, me));
            req.addProperty("startingSeat", live.getStartingPlayer() == null ? -1
                    : forge.bench.StateEncoder.playerIndex(live, live.getStartingPlayer()));
            final JsonArray mull = new JsonArray();
            for (Player pl : live.getRegisteredPlayers()) {
                mull.add(pl.getStats().getMulliganCount());
            }
            req.add("mulligans", mull);
            req.add("deck", rankerDeckOf(me));
            final JsonObject d = new JsonObject();
            d.addProperty("phase", String.valueOf(ph.getPhase()));
            d.addProperty("stack", live.getStack().size());
            d.addProperty("active", ph.getPlayerTurn() == me);
            d.addProperty("turn", turn);
            d.addProperty("bSize", cands.size());
            req.add("decision", d);
            req.add("root", root);
            final JsonArray ca = new JsonArray();
            for (Cand cd : cands) {
                final Prepared p = prepare(live, me, cd, defSa, mix(decisionSeed, 1000), cfg.resample, null);
                final JsonArray tg = p.failed ? new JsonArray() : targetRefs(p.g, p.first);
                if (p.failed && !cd.pass) {
                    stats.rankerPrepFailures++;
                }
                p.g = null;
                final JsonObject co = new JsonObject();
                co.addProperty("kind", cd.kind);
                co.addProperty("hostId", cd.hostId);
                co.addProperty("label", cd.label);
                co.add("targets", tg);
                co.addProperty("isDefault", cd.isDefault);
                ca.add(co);
            }
            req.add("cands", ca);
            return req;
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /** The seat's registered main deck, {name: copies} (PolicyPilot's deckOf), once per seat. */
    private JsonObject rankerDeckOf(Player me) {
        if (rankerDeck == null) {
            final JsonObject d = new JsonObject();
            final forge.deck.Deck dk = me.getRegisteredPlayer() == null ? null : me.getRegisteredPlayer().getDeck();
            if (dk != null && dk.has(forge.deck.DeckSection.Main)) {
                final TreeMap<String, Integer> byName = new TreeMap<>();
                for (Map.Entry<forge.item.PaperCard, Integer> e : dk.get(forge.deck.DeckSection.Main)) {
                    byName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
                }
                byName.forEach(d::addProperty);
            }
            rankerDeck = d;
        }
        return rankerDeck;
    }

    /**
     * EI turn-start dump (cycle 2): the seat's ForgeState taken in a fresh GameCopier copy of {@code g}, in its own id
     * scope, AI-cache scope and random stream (seeded by {@code seed}), so the game it is called on is never encoded
     * or advanced. Null if the copy or the encoding throws.
     */
    public static JsonObject encodeInCopy(Game g, Player p, long seed) {
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(seed));
        final Object prevAiCache2 = AiCache.captureScope(); // restored below, never removed (k8-determinism-1008)
        AiCache.openScope();
        final Object prevIds = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            final Game c;
            final Player cp;
            synchronized (g) {
                final GameCopier copier = new GameCopier(g, true);
                copier.setCopyStack(false);
                c = copier.makeCopy();
                cp = (Player) copier.find(p);
            }
            return forge.bench.StateEncoder.encode(c, cp);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        } finally {
            AiCache.installScope(prevAiCache2);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** Label dump: the target refs ("P<i>" / "C<id>", {@link #targetRef}) of an ability chain, in sub-ability order. */
    static JsonArray targetRefs(Game g, List<SpellAbility> first) {
        final JsonArray a = new JsonArray();
        if (g == null || first == null || first.isEmpty() || first.get(0) == null) {
            return a;
        }
        for (SpellAbility s = first.get(0); s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                for (forge.game.GameObject t : s.getTargets()) {
                    final String r = targetRef(g, t);
                    if (r != null) {
                        a.add(r);
                    }
                }
            }
        }
        return a;
    }

    /**
     * sc-wallguard-1009: {@link #record} unless the decision's wall sealed its arrays (then nothing is written and false
     * is returned: the play-out's decision was already cut).
     */
    private boolean record(double[][] values, Rollout[][] outs, boolean[] ok, int cc, int ww, Rollout r, Cand cand,
            boolean[] sealed) {
        synchronized (values) {
            if (sealed[0]) {
                return false;
            }
            record(values, outs, ok, cc, ww, r, cand);
            return true;
        }
    }

    private void record(double[][] values, Rollout[][] outs, boolean[] ok, int cc, int ww, Rollout r, Cand cand) {
        synchronized (values) {
            if (!r.ok) {
                stats.countPlayoutFailure(failureClass(r), failureName(cand));
                if (r.failDetail != null) {
                    stats.playoutLastCopyFailure = failureName(cand) + ": " + r.failDetail;
                }
            }
            values[cc][ww] = r.value;
            outs[cc][ww] = r;
            if (!r.ok) {
                ok[cc] = false;
            }
            stats.rollouts++;
            stats.steps += r.steps;
            if (r.aborted) {
                stats.rolloutsAborted++;
            } else if (!r.ok) {
                stats.rolloutFailures++;
            }
            if (r.capped) {
                stats.rolloutCapped++;
            }
            stats.dedupKeys += r.keysComputed;
            stats.dedupKeyNanos += r.keyNanos;
            if (r.dedupFrom >= 0) {
                stats.dedupHits++;
                stats.dedupStepsSaved += r.dedupStepsSaved;
            }
            if (r.reused) {
                stats.reuseHits++;
                stats.reuseStepsSaved += r.reusedSteps;
            }
            if (r.leafLate) {
                stats.leafLate++;
            }
            if (r.seatFailure != null) {
                stats.playoutSeatFailures++;
                stats.playoutLastFailure = r.seatFailure;
            }
        }
    }

    /** The positions one world's earlier candidates passed through: key -> where it leads. */
    static final class WorldMemo {
        static final class Entry {
            final int cand;
            final Rollout src;
            /** Main-loop steps from the keyed position to the end of the play-out. */
            final int remaining;

            Entry(int cand, Rollout src, int remaining) {
                this.cand = cand;
                this.src = src;
                this.remaining = remaining;
            }
        }

        final Map<String, Entry> seen = new java.util.HashMap<>();

        /** The entry whose continuation equals this play-out's from step {@code step} on, honouring the step cap. */
        Entry match(String key, int step, int maxSteps) {
            final Entry e = seen.get(key);
            if (e == null || !e.src.ok) {
                return null;
            }
            // The earlier play-out's end must be where this one would end: the horizon or game end within the cap,
            // or the cap itself at exactly the same step count.
            if (e.src.capped ? step + e.remaining != maxSteps : step + e.remaining > maxSteps) {
                return null;
            }
            return e;
        }

        void add(int cand, Rollout r) {
            if (r.keys == null || !r.ok) {
                return;
            }
            final int total = r.steps + r.dedupStepsSaved;
            for (int i = 0; i < r.keys.size(); i++) {
                seen.putIfAbsent(r.keys.get(i), new Entry(cand, r, total - (i + 1)));
            }
        }
    }

    /** Dedup verify mode: every would-be hit's full play-out must equal its source's value bit for bit. */
    private void dedupVerify(Rollout[][] outs, int n, int k) {
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final Rollout r = outs[c][w];
                if (r == null || r.wouldDedupFrom < 0) {
                    continue;
                }
                final Rollout src = outs[r.wouldDedupFrom][w];
                if (src == null || Double.doubleToLongBits(src.value) != Double.doubleToLongBits(r.value)
                        || !java.util.Objects.equals(src.endKey, r.endKey)) {
                    stats.dedupVerifyMismatch++;
                    System.err.println("[lookahead] dedup verify MISMATCH world " + w + " cand " + c + " vs " + r.wouldDedupFrom
                            + " at step " + r.wouldDedupStep + ": " + r.value + " vs " + (src == null ? "null" : src.value));
                }
            }
        }
    }

    // ------------------------------------------------------------------ reuse across decisions (world filter)

    /**
     * One own-seat priority point (empty stack) of a play-out: what the seat saw (hash of {@link PlayoutKeys#observable}),
     * what its Forge AI chose there, after how many main-loop steps of the play-out, and the world at that point: the
     * hidden cards (opponents' hands and libraries, our library, in order) and the play-out's RNG state.
     */
    static final class TrajPoint {
        final long sig;
        final String choice;
        final int step;
        final int[] myLib;
        final int[][] oppHand, oppLib;
        final int[] oppIds;
        final long[] rng;

        TrajPoint(long sig, String choice, int step, int[] myLib, int[] oppIds, int[][] oppHand, int[][] oppLib, long[] rng) {
            this.sig = sig;
            this.choice = choice;
            this.step = step;
            this.myLib = myLib;
            this.oppIds = oppIds;
            this.oppHand = oppHand;
            this.oppLib = oppLib;
            this.rng = rng;
        }

        /** The hidden cards of this point are the live game's hidden cards (as sets): the world can be put into a copy. */
        boolean sameHidden(Game live, Player liveMe) {
            if (!sameSet(myLib, ids(liveMe.getCardsIn(ZoneType.Library)))) {
                return false;
            }
            for (int i = 0; i < oppIds.length; i++) {
                final Player o = live.getPlayer(oppIds[i]);
                if (o == null) {
                    return false;
                }
                final int[] h = ids(o.getCardsIn(ZoneType.Hand)), l = ids(o.getCardsIn(ZoneType.Library));
                if (h.length != oppHand[i].length || !sameSet(concat(oppHand[i], oppLib[i]), concat(h, l))) {
                    return false;
                }
            }
            return true;
        }
    }

    static int[] ids(Iterable<Card> cs) {
        final List<Integer> l = new ArrayList<>();
        for (Card c : cs) {
            l.add(c.getId());
        }
        final int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    private static int[] concat(int[] a, int[] b) {
        final int[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    private static boolean sameSet(int[] a, int[] b) {
        if (a.length != b.length) {
            return false;
        }
        final int[] x = a.clone(), y = b.clone();
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    static long sigHash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h ^ ((long) s.hashCode() << 32);
    }

    /** Record the searching seat's priority point in a play-out (called on the play-out thread, before its AI chooses). */
    static TrajPoint trajPoint(Game g, Player me, String choice, int step, long sig) {
        final List<Player> opps = new ArrayList<>();
        for (Player o : me.getOpponents()) {
            opps.add(o);
        }
        final int[] oppIds = new int[opps.size()];
        final int[][] oh = new int[opps.size()][], ol = new int[opps.size()][];
        for (int i = 0; i < opps.size(); i++) {
            oppIds[i] = opps.get(i).getId();
            oh[i] = ids(opps.get(i).getCardsIn(ZoneType.Hand));
            ol[i] = ids(opps.get(i).getCardsIn(ZoneType.Library));
        }
        final Random r = MyRandom.getRandom();
        final long[] rng = r instanceof PlayoutKeys.TrackedRandom tr ? tr.snapshot() : null;
        return new TrajPoint(sig, choice, step, ids(me.getCardsIn(ZoneType.Library)), oppIds, oh, ol, rng);
    }

    /** Put a carried world's hidden cards into a fresh copy (the ids were checked against the live game). */
    static boolean transplant(Game g, Player me, TrajPoint tp) {
        final List<Card> lib = cards(g, tp.myLib);
        if (lib == null) {
            return false;
        }
        for (int i = 0; i < tp.oppIds.length; i++) {
            final Player o = g.getPlayer(tp.oppIds[i]);
            final List<Card> h = cards(g, tp.oppHand[i]), l = cards(g, tp.oppLib[i]);
            if (o == null || h == null || l == null) {
                return false;
            }
            o.getZone(ZoneType.Hand).setCards(h);
            o.getZone(ZoneType.Library).setCards(l);
        }
        me.getZone(ZoneType.Library).setCards(lib);
        return true;
    }

    private static List<Card> cards(Game g, int[] ids) {
        final List<Card> l = new ArrayList<>(ids.length);
        for (int id : ids) {
            final Card c = g.findById(id);
            if (c == null) {
                return null;
            }
            l.add(c);
        }
        return l;
    }

    /** A world carried into this search: where its kept play-out met reality, and (same horizon, same choice) its value. */
    static final class Carried {
        final TrajPoint at;
        final Rollout reusable;

        Carried(TrajPoint at, Rollout reusable) {
            this.at = at;
            this.reusable = reusable;
        }
    }

    /** The previous search's play-outs of the move actually played, one per world, and that search's turn. */
    private Rollout[] kept = null;
    private int keptTurn = -1;

    /**
     * The owner's filter: a kept world survives if its play-out of the move actually played reached the live position
     * (same observable position, same hidden cards as sets); its hidden cards and play-out stream at that point become
     * this search's world. If Forge AI chose there what Forge's answer is now and the horizon is the same turn, the kept
     * play-out's value stands in for candidate 0's play-out in that world. Other worlds are drawn fresh (as A8S).
     */
    private Carried[] carry(Game live, Player me, List<Cand> cands, int turn, int k) {
        final Carried[] out = new Carried[k];
        if (kept == null) {
            return out;
        }
        final long sig = sigHash(PlayoutKeys.observable(live, me));
        final String defKey = cands.get(0).key();
        int alive = 0;
        for (int w = 0; w < Math.min(k, kept.length); w++) {
            final Rollout kr = kept[w];
            stats.reuseChecked++;
            if (kr == null || kr.trajectory == null) {
                continue;
            }
            for (int i = 0; i < kr.trajectory.size(); i++) {
                final TrajPoint tp = kr.trajectory.get(i);
                if (tp.sig != sig) {
                    continue;
                }
                if (tp.rng == null || !tp.sameHidden(live, me)) {
                    stats.reuseHiddenMismatch++;
                    break;
                }
                alive++;
                stats.reuseAlive++;
                Rollout reusable = null;
                if (turn == keptTurn && kr.ok && tp.choice.equals(defKey)) {
                    stats.reuseSameChoice++;
                    reusable = new Rollout();
                    reusable.reused = true;
                    reusable.value = kr.value;
                    reusable.capped = kr.capped;
                    reusable.pTerminal = kr.pTerminal;
                    reusable.leaf = kr.leaf;
                    reusable.totalSteps = kr.totalSteps;
                    reusable.reusedSteps = kr.totalSteps - tp.step;
                    reusable.trajectory = new ArrayList<>(kr.trajectory.subList(i + 1, kr.trajectory.size()));
                }
                out[w] = new Carried(tp, reusable);
                break;
            }
        }
        stats.reuseAliveBy.merge((turn == keptTurn ? "sameTurn:" : "laterTurn:") + alive, 1L, Long::sum);
        return out;
    }

    private void keep(Rollout[][] outs, int played, int turn, int k) {
        kept = new Rollout[k];
        for (int w = 0; w < k; w++) {
            final Rollout r = outs[played][w];
            kept[w] = r == null || !r.ok ? null : r;
        }
        keptTurn = turn;
    }

    /** The search's choice from its values: the argmax, ties and a failed Forge answer to Forge's answer (index 0). */
    private static boolean[][] finishedWorlds(Rollout[][] outs, int n, int k) {
        final boolean[][] f = new boolean[n][k];
        for (int c = 0; c < n; c++) {
            for (int w = 0; w < k; w++) {
                final Rollout r = outs[c][w];
                f[c][w] = r != null && r.ok && !r.aborted;
            }
        }
        return f;
    }

    /**
     * unified-search-1106 ({@link Config#anytime}): the candidate with the largest mean gain over the default, among those
     * whose gain passes the z test. Pairs are the worlds both finished; where the default has none, its mean, or with no
     * finished world at all {@code rootP}. A candidate with fewer worlds than the decision's uses the larger of its own and
     * the decision's pooled variance, so a thin sample carries the uncertainty the rest of the decision shows.
     * With every world finished this is {@link #argmax}.
     */
    static int chooseAnytime(double[][] v, boolean[][] f, boolean[] ok, int n, int k, double z, double margin, double rootP) {
        return chooseAnytime(v, f, ok, n, k, z, margin, rootP, null, 0.0, null);
    }

    /**
     * solver-distill-cc-1109: is candidate c's reach part never below the default's in a world both finished, and above
     * it by {@code depart} on average? Such a candidate replaces the default without the z test.
     */
    static boolean reachExempt(double[][] rp, boolean[][] f, int c, int k, double depart) {
        if (rp == null || c >= rp.length || rp[c].length < k || rp[0].length < k || !(depart > 0)) {
            return false;
        }
        double sum = 0;
        int cnt = 0;
        for (int w = 0; w < k; w++) {
            if (f[c][w] && f[0][w]) {
                final double d = rp[c][w] - rp[0][w];
                if (d < -1e-9) {
                    return false;
                }
                sum += d;
                cnt++;
            }
        }
        return cnt > 0 && sum / cnt >= depart;
    }

    /** {@link #chooseAnytime} with the reach departure rule ({@code rp}: each candidate's reach part per world, or null). */
    static int chooseAnytime(double[][] v, boolean[][] f, boolean[] ok, int n, int k, double z, double margin, double rootP,
            double[][] rp, double depart, Stats st) {
        double defSum = 0;
        int defN = 0;
        for (int w = 0; w < k; w++) {
            if (f[0][w]) {
                defSum += v[0][w];
                defN++;
            }
        }
        final double defAll = defN > 0 ? defSum / defN : rootP;
        if (Double.isNaN(defAll)) {
            return 0;
        }
        final double[] mean = new double[n], var = new double[n];
        final int[] kc = new int[n];
        double poolSs = 0;
        int poolDof = 0;
        for (int c = 1; c < n; c++) {
            if (!ok[c]) {
                continue;
            }
            double sum = 0;
            for (int w = 0; w < k; w++) {
                if (f[c][w]) {
                    sum += v[c][w] - (f[0][w] ? v[0][w] : defAll);
                    kc[c]++;
                }
            }
            if (kc[c] == 0) {
                continue;
            }
            mean[c] = sum / kc[c];
            double ss = 0;
            for (int w = 0; w < k; w++) {
                if (f[c][w]) {
                    final double d = v[c][w] - (f[0][w] ? v[0][w] : defAll) - mean[c];
                    ss += d * d;
                }
            }
            var[c] = kc[c] > 1 ? ss / (kc[c] - 1) : Double.NaN;
            poolSs += ss;
            poolDof += Math.max(0, kc[c] - 1);
        }
        final double pooled = poolDof > 0 ? poolSs / poolDof : Double.NaN;
        int best = 0;
        double bestGain = margin;
        boolean bestByReach = false;
        for (int c = 1; c < n; c++) {
            if (!ok[c] || kc[c] == 0 || !(mean[c] > 0)) {
                continue;
            }
            boolean byReach = false;
            if (z > 0 && k >= 2) {
                final double s2;
                boolean pass;
                if (kc[c] == k) {
                    s2 = var[c];
                    pass = mean[c] > z * Math.sqrt(s2 / kc[c]);
                } else if (Double.isNaN(pooled)) {
                    pass = false; // nothing to measure a thin sample's uncertainty by
                } else {
                    s2 = Double.isNaN(var[c]) ? pooled : Math.max(var[c], pooled);
                    pass = mean[c] > z * Math.sqrt(s2 / kc[c]);
                }
                if (!pass) {
                    if (!reachExempt(rp, f, c, k, depart)) {
                        continue;
                    }
                    byReach = true;
                }
            }
            if (mean[c] > bestGain) {
                best = c;
                bestGain = mean[c];
                bestByReach = byReach;
            }
        }
        if (bestByReach && st != null) {
            st.reachDeparts++;
        }
        return best;
    }

    int argmax(double[][] values, boolean[] ok, int n, int k) {
        if (!ok[0]) {
            return 0;
        }
        int best = 0;
        final double[] ev = new double[n];
        for (int c = 0; c < n; c++) {
            double s = 0;
            for (int w = 0; w < k; w++) {
                s += values[c][w];
            }
            ev[c] = ok[c] ? s / k : Double.NEGATIVE_INFINITY;
        }
        for (int c = 1; c < n; c++) {
            if (cfg.departZ > 0 && ok[c] && !confident(values, c, k, cfg.departZ)) {
                stats.departGated++;
                continue;
            }
            if (ev[c] > ev[best] + (best == 0 ? cfg.margin : 0)) {
                best = c;
            }
        }
        return best;
    }

    /**
     * Departure confidence: the mean over worlds of (candidate - Forge's answer) is positive and exceeds z standard
     * errors of that paired difference (sample standard deviation / sqrt(k)). With one world there is no error
     * estimate, so only a strictly positive difference counts; identical differences in every world (zero spread) count
     * when positive.
     */
    /**
     * As {@link #argmax}, with the median gate: a candidate is eligible only if its lower median paired difference to
     * Forge's answer is above zero, i.e. it is strictly better in more than half the worlds (and it passes departZ). Counts the candidates the gate alone sets aside.
     */
    int argmaxMedian(double[][] values, boolean[] ok, int n, int k) {
        if (!ok[0]) {
            return 0;
        }
        int best = 0;
        final double[] ev = new double[n];
        for (int c = 0; c < n; c++) {
            double s = 0;
            for (int w = 0; w < k; w++) {
                s += values[c][w];
            }
            ev[c] = ok[c] ? s / k : Double.NEGATIVE_INFINITY;
        }
        for (int c = 1; c < n; c++) {
            if (cfg.departZ > 0 && ok[c] && !confident(values, c, k, cfg.departZ)) {
                continue;
            }
            if (ok[c] && !(medianDiff(values, c, k) > 0)) {
                stats.departMedianGated++;
                continue;
            }
            if (ev[c] > ev[best] + (best == 0 ? cfg.margin : 0)) {
                best = c;
            }
        }
        return best;
    }

    /**
     * The lower median over worlds of candidate c's paired difference to candidate 0 (Forge's answer): the
     * ceil(k/2)-th smallest. It is above zero exactly when the candidate is strictly better in more than half the worlds
     * (with k = 8: in at least 5), so ties at zero -- worlds where the play-outs did not differ -- never count for it.
     */
    static double medianDiff(double[][] values, int c, int k) {
        final double[] d = new double[k];
        for (int w = 0; w < k; w++) {
            d[w] = values[c][w] - values[0][w];
        }
        Arrays.sort(d);
        return d[(k - 1) / 2];
    }

    static boolean confident(double[][] values, int c, int k, double z) {
        double sum = 0;
        for (int w = 0; w < k; w++) {
            sum += values[c][w] - values[0][w];
        }
        final double mean = sum / k;
        if (!(mean > 0)) {
            return false;
        }
        if (k < 2) {
            return true;
        }
        double ss = 0;
        for (int w = 0; w < k; w++) {
            final double d = values[c][w] - values[0][w] - mean;
            ss += d * d;
        }
        final double se = Math.sqrt(ss / (k - 1)) / Math.sqrt(k);
        return mean > z * se;
    }

    /**
     * Every candidate x world play-out of one decision. Copies (and world draws) first, all on this thread in a fixed
     * order; then the play-outs, on the pool when threads > 1. A carried world uses its kept hidden cards and stream; a
     * reusable carried world does not play candidate 0 (its kept value stands in) unless {@code freshDef} asks for a
     * fresh play-out of it too (reuse verify).
     */
    private int playAll(Game live, Player me, List<Cand> cands, SpellAbility defSa, long decisionSeed, Carried[] carried,
            double[][] values, Rollout[][] outs, boolean[] ok, Rollout[] freshDef) {
        final int n = cands.size(), k = values[0].length;
        final Prepared[][] prep = new Prepared[n][k];
        final Prepared[] prepFresh = new Prepared[k];
        boolean any = false;
        prepare:
        for (int w = 0; w < k; w++) {
            final Carried cw = carried[w];
            any |= cw != null;
            for (int c = 0; c < n; c++) {
                if (pastDeadline()) {
                    break prepare;
                }
                if (c == 0 && cw != null && cw.reusable != null) {
                    outs[0][w] = cw.reusable;
                    if (freshDef != null) {
                        prepFresh[w] = prepare(live, me, cands.get(0), defSa, mix(decisionSeed, 1000 + w), cfg.resample, cw.at);
                    }
                    continue;
                }
                prep[c][w] = prepare(live, me, cands.get(c), defSa, mix(decisionSeed, 1000 + w), cfg.resample, cw == null ? null : cw.at);
                prep[c][w].trajectory = cfg.reuse ? new ArrayList<>() : null;
                if (choiceTag && prep[c][w].firstAction != null) {
                    // S-t (lane cm-choice-search-1009): which candidate and world this policy play-out is, and whether it
                    // is the candidate's probe (world 0 of the base candidates)
                    final FirstAction fa = prep[c][w].firstAction;
                    fa.candIndex = choiceIndexBase + c;
                    fa.world = w;
                    fa.probe = choiceProbe && w == 0;
                }
                if (EXPLAIN && w == 0) {
                    explainChoices[c] = describeChoices(prep[c][w].first);
                }
                if (labelTargets != null && w == 0) {
                    // Label dump: what this candidate is played out with (Forge AI's choices in world 0's copy).
                    try {
                        labelChoices[c] = describeChoices(prep[c][w].first);
                        labelTargets[c] = targetRefs(prep[c][w].g, prep[c][w].first);
                    } catch (RuntimeException e) {
                        labelChoices[c] = "?";
                        labelTargets[c] = null;
                    }
                }
            }
        }
        if (any) {
            stats.reuseDecisionsCarried++;
        }
        final List<Runnable> tasks = new ArrayList<>();
        final boolean keyed = cfg.dedup || cfg.dedupVerify;
        // sc-wallguard-1009: once the hard wall cut the wait, a play-out still running (a straggler) never writes into
        // this decision's arrays or the counters (set and read under the values lock)
        final boolean[] sealed = {false};
        for (int w = 0; w < k; w++) {
            if (keyed) {
                // One task per world: its candidates in index order, so which play-out may reuse which is fixed.
                final int ww = w;
                tasks.add(() -> {
                    final WorldMemo memo = new WorldMemo();
                    for (int cc = 0; cc < n; cc++) {
                        final Rollout r = prep[cc][ww] != null ? play(prep[cc][ww], null, memo, cc)
                                : outs[cc][ww] != null ? outs[cc][ww] : aborted();
                        prep[cc][ww] = null;
                        if (!record(values, outs, ok, cc, ww, r, cands.get(cc), sealed)) {
                            return;
                        }
                    }
                });
            } else {
                for (int c = 0; c < n; c++) {
                    final int ww = w, cc = c;
                    if (prep[cc][ww] == null) {
                        // A reused value, or (budget) a play-out that never started.
                        record(values, outs, ok, cc, ww, outs[cc][ww] != null ? outs[cc][ww] : aborted(), cands.get(cc));
                        continue;
                    }
                    tasks.add(() -> {
                        Rollout r = play(prep[cc][ww], null);
                        prep[cc][ww] = null;
                        record(values, outs, ok, cc, ww, r, cands.get(cc), sealed);
                    });
                }
            }
            if (prepFresh[w] != null) {
                final int ww = w;
                tasks.add(() -> {
                    freshDef[ww] = play(prepFresh[ww], null);
                    prepFresh[ww] = null;
                });
            }
        }
        final Budget wall = budget;
        boolean waited = false;
        try {
            waited = runAll(tasks, wall);
        } finally {
            if (!waited && wall != null) {
                // sc-wallguard-1009: the hard wall cut the wait (or, with a budget, a worker failed). Every play-out
                // without a result is stopped by the budget (its decision is capped); from here on no straggler writes
                // into these arrays or the counters.
                wall.stop();
                synchronized (values) {
                    sealed[0] = true;
                    for (int c = 0; c < n; c++) {
                        for (int w = 0; w < k; w++) {
                            if (outs[c][w] == null) {
                                record(values, outs, ok, c, w, aborted(), cands.get(c));
                            }
                        }
                    }
                }
            }
        }
        int aborted = 0;
        for (Rollout[] row : outs) {
            for (Rollout r : row) {
                if (r != null && r.aborted) {
                    aborted++;
                }
            }
        }
        return aborted;
    }

    /**
     * Reuse checks (TRAIN only; they cost extra play-outs). (a) Same worlds: replace every reused value of candidate 0
     * by its fresh play-out in the same carried world and recompute the choice. (b) Fresh search: run the A8S search
     * (fresh worlds, nothing carried) and compare its choice. Nothing here changes the decision.
     */
    private void reuseVerify(Game live, Player me, List<Cand> cands, SpellAbility defSa, long decisionSeed, Carried[] carried,
            double[][] values, boolean[] ok, Rollout[] freshDef, int best) {
        final int n = cands.size(), k = values[0].length;
        boolean anyReused = false;
        final double[][] v2 = new double[n][];
        for (int c = 0; c < n; c++) {
            v2[c] = values[c].clone();
        }
        final boolean[] ok2 = ok.clone();
        for (int w = 0; w < k; w++) {
            if (freshDef[w] == null) {
                continue;
            }
            anyReused = true;
            if (Double.doubleToLongBits(freshDef[w].value) != Double.doubleToLongBits(values[0][w])) {
                stats.reuseValueDiff++;
            }
            v2[0][w] = freshDef[w].value;
            if (!freshDef[w].ok) {
                ok2[0] = false;
            }
        }
        if (anyReused) {
            stats.reuseCompared++;
            if (argmax(v2, ok2, n, k) != best) {
                stats.reuseDiffSameWorlds++;
            }
        }
        if (cfg.reuseShadowFresh && carried != null) {
            boolean anyCarried = false;
            for (Carried c : carried) {
                anyCarried |= c != null;
            }
            if (anyCarried) {
                final double[][] v3 = new double[n][k];
                final Rollout[][] o3 = new Rollout[n][k];
                final boolean[] ok3 = new boolean[n];
                Arrays.fill(ok3, true);
                playAllQuiet(live, me, cands, defSa, decisionSeed, v3, o3, ok3);
                stats.reuseFreshCompared++;
                final int bestFresh = argmax(v3, ok3, n, k);
                if (bestFresh != best) {
                    stats.reuseDiffVsFresh++;
                }
                // The noise floor: a second fresh search on other world draws, against the first.
                final double[][] v4 = new double[n][k];
                final boolean[] ok4 = new boolean[n];
                Arrays.fill(ok4, true);
                playAllQuiet(live, me, cands, defSa, mix(decisionSeed, 0xf2e5L), v4, new Rollout[n][k], ok4);
                if (argmax(v4, ok4, n, k) != bestFresh) {
                    stats.freshVsFreshDiff++;
                }
            }
        }
    }

    /** The A8S play-outs of one decision (fresh worlds), without touching the per-game counters. */
    private void playAllQuiet(Game live, Player me, List<Cand> cands, SpellAbility defSa, long decisionSeed,
            double[][] values, Rollout[][] outs, boolean[] ok) {
        final int n = cands.size(), k = values[0].length;
        final Prepared[][] prep = new Prepared[n][k];
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                prep[c][w] = prepare(live, me, cands.get(c), defSa, mix(decisionSeed, 1000 + w), cfg.resample, null);
            }
        }
        final List<Runnable> tasks = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final int ww = w, cc = c;
                tasks.add(() -> {
                    final Rollout r = play(prep[cc][ww], null);
                    prep[cc][ww] = null;
                    synchronized (values) {
                        values[cc][ww] = r.value;
                        outs[cc][ww] = r;
                        if (!r.ok) {
                            ok[cc] = false;
                        }
                    }
                });
            }
        }
        runAll(tasks);
    }

    private static Rollout aborted() {
        final Rollout r = new Rollout();
        r.ok = false;
        r.aborted = true;
        r.value = Double.NEGATIVE_INFINITY;
        return r;
    }

    private void runAll(List<Runnable> tasks) {
        runAll(tasks, null);
    }

    /**
     * Run the tasks (on the pool when threads > 1) and wait for them. With a {@code wall} budget and a pool
     * (sc-wallguard-1009), the wait ends at the budget's deadline plus {@link #WALL_GRACE_MS} at the latest: then the
     * budget is stopped (every play-out of the decision sees it at its next step or service request), the tasks not yet
     * started are cancelled, and false is returned; the caller must not let a task that is still running write into what
     * it reads. Before the wall (and always without a budget, or without a pool) it is the plain wait and returns true.
     */
    private boolean runAll(List<Runnable> tasks, Budget wall) {
        if (pool == null) {
            for (Runnable r : tasks) {
                r.run();
            }
            return true;
        }
        if (forge.game.WorkClock.active()) {
            return runLanes(tasks, wall); // work-units-1107: a work clock on the deciding thread
        }
        List<Future<?>> fs = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicLong[] acc = poolCpu == null ? null : forge.ai.CpuAccount.get();
        // the wall only: how many tasks are running (to count the stragglers at a cut)
        final AtomicInteger running = wall == null ? null : new AtomicInteger();
        for (Runnable task : tasks) {
            final Runnable r = running == null ? task : () -> {
                running.incrementAndGet();
                try {
                    task.run();
                } finally {
                    running.decrementAndGet();
                }
            };
            if (acc == null) {
                fs.add(pool.submit(r));
            } else {
                // S1: a worker thread's CPU, and its Forge AI eval threads', go to the deciding thread's accounts
                // (the decision's and the game's; measurement only)
                fs.add(pool.submit(() -> {
                    final long c0 = forge.ai.CpuAccount.now();
                    forge.ai.CpuAccount.set(acc);
                    try {
                        r.run();
                    } finally {
                        forge.ai.CpuAccount.set(null);
                        forge.ai.CpuAccount.charge(acc, forge.ai.CpuAccount.now() - c0);
                    }
                }));
            }
        }
        if (wall == null) {
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    throw new RuntimeException("look-ahead worker failed", e);
                }
            }
            return true;
        }
        final long cut = wall.deadline + WALL_GRACE_MS * 1_000_000L;
        for (int i = 0; i < fs.size(); i++) {
            try {
                fs.get(i).get(Math.max(0L, cut - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                wall.stop();
                wall.wallCut = true;
                for (int j = i; j < fs.size(); j++) {
                    fs.get(j).cancel(false);
                }
                stats.wallCuts++;
                stats.wallStragglers += running.get();
                System.err.println("[lookahead] wall: the play-outs ran " + WALL_GRACE_MS + " ms past the budget; "
                        + running.get() + " still running were told to stop, the decision is capped");
                return false;
            } catch (Exception e) {
                // a failed worker fails the decision (as without the wall); the others are told to stop first
                wall.stop();
                for (int j = i + 1; j < fs.size(); j++) {
                    fs.get(j).cancel(false);
                }
                throw new RuntimeException("look-ahead worker failed", e);
            }
        }
        return true;
    }

    /**
     * work-units-1107 (a work clock on the deciding thread; never without one): the tasks on fixed virtual lanes. Task
     * i runs on lane i mod L (L = the configured threads, 8 when auto, so the lanes never depend on the host), each lane
     * runs its tasks in order on one pool thread under a fork of the deciding thread's clock, which starts at the
     * decision's virtual now; a play-out's budget reads its lane's clock. Which play-outs a budget cuts is then a
     * function of their work, not of the host's speed or the pool's scheduling. After the join the deciding clock is at
     * the latest lane's end. The hard wall is the clock's real-time safety wall (none armed: the plain wait) plus
     * {@link #WALL_GRACE_MS}; a cut there is a safety-wall hit (nondeterministic, counted), as in {@link #runAll}.
     */
    private boolean runLanes(List<Runnable> tasks, Budget wall) {
        final forge.game.WorkClock.Clock parent = forge.game.WorkClock.current();
        final int lanesCfg = cfg.threads > 0 ? cfg.threads : 8;
        final int nl = Math.max(1, Math.min(lanesCfg, tasks.size()));
        final forge.game.WorkClock.Clock[] lanes = new forge.game.WorkClock.Clock[nl];
        final List<List<Runnable>> per = new ArrayList<>();
        for (int i = 0; i < nl; i++) {
            lanes[i] = parent.fork();
            per.add(new ArrayList<>());
        }
        for (int i = 0; i < tasks.size(); i++) {
            per.get(i % nl).add(tasks.get(i));
        }
        final java.util.concurrent.atomic.AtomicLong[] acc = poolCpu == null ? null : forge.ai.CpuAccount.get();
        final AtomicInteger running = new AtomicInteger();
        final List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < nl; i++) {
            final forge.game.WorkClock.Clock lane = lanes[i];
            final List<Runnable> mine = per.get(i);
            fs.add(pool.submit(() -> {
                final forge.game.WorkClock.Clock prev = forge.game.WorkClock.install(lane);
                final long c0 = acc == null ? 0L : forge.ai.CpuAccount.now();
                if (acc != null) {
                    forge.ai.CpuAccount.set(acc);
                }
                running.incrementAndGet();
                try {
                    for (Runnable r : mine) {
                        r.run();
                    }
                } finally {
                    running.decrementAndGet();
                    if (acc != null) {
                        forge.ai.CpuAccount.set(null);
                        forge.ai.CpuAccount.charge(acc, forge.ai.CpuAccount.now() - c0);
                    }
                    forge.game.WorkClock.install(prev);
                }
            }));
        }
        final long safety = parent.safetyWall();
        final boolean cutOk = wall != null && safety != 0;
        final long cut = safety + WALL_GRACE_MS * 1_000_000L;
        for (int i = 0; i < fs.size(); i++) {
            try {
                if (cutOk) {
                    fs.get(i).get(Math.max(0L, cut - System.nanoTime()), TimeUnit.NANOSECONDS);
                } else {
                    fs.get(i).get();
                }
            } catch (TimeoutException e) {
                wall.stop();
                wall.wallCut = true;
                for (int j = i; j < fs.size(); j++) {
                    fs.get(j).cancel(false);
                }
                stats.wallCuts++;
                stats.wallStragglers += running.get();
                parent.markWallHit();
                System.err.println("[lookahead] work-units safety wall: lanes ran " + WALL_GRACE_MS + " ms past it; "
                        + running.get() + " still running were told to stop, the decision is capped");
                return false;
            } catch (Exception e) {
                if (wall != null) {
                    wall.stop();
                }
                for (int j = i + 1; j < fs.size(); j++) {
                    fs.get(j).cancel(false);
                }
                throw new RuntimeException("look-ahead worker failed", e);
            }
        }
        parent.join(lanes);
        return true;
    }

    // ------------------------------------------------------------------ candidates

    /** HX: what the enumeration copy gives the prior call beside the B set (filled only at qualifying decisions). */
    static final class PriorView {
        /** Forge's full candidate list past B, in Forge's order (saEvaluator sort, deduplicated by {@link Cand#key()}). */
        final List<Cand> tail = new ArrayList<>();
        /** The decision's ForgeState ({@link forge.bench.StateEncoder#encode}) of the searching seat; null if it failed. */
        JsonObject root;
        String error;
    }

    /**
     * @param pv null = the base enumeration (Forge's answer, pass, then the next legal candidates up to breadth B);
     *           otherwise, after B is fixed exactly as in the base, the rest of Forge's list and the root state are put
     *           into it from the same copy (nothing of B depends on it; a failure there leaves B unchanged).
     */
    List<Cand> enumerate(Game live, Player liveMe, SpellAbility defSa, long decisionSeed, PriorView pv) {
        return enumerate(live, liveMe, defSa, decisionSeed, pv, null);
    }

    List<Cand> enumerate(Game live, Player liveMe, SpellAbility defSa, long decisionSeed, PriorView pv,
                         JsonObject[] beliefRoot) {
        return enumerate(live, liveMe, defSa, decisionSeed, pv, beliefRoot, null);
    }

    /**
     * @param labelRoot label dump (null = off): after B is fixed, the seat's ForgeState from this copy goes into
     *                  {@code labelRoot[0]} (null if encoding fails), and {@link #labelTail} counts the distinct legal
     *                  candidates past B. Nothing of B depends on it.
     */
    List<Cand> enumerate(Game live, Player liveMe, SpellAbility defSa, long decisionSeed, PriorView pv,
                         JsonObject[] beliefRoot, JsonObject[] labelRoot) {
        final List<Cand> out = new ArrayList<>();
        out.add(cand(defSa, true));
        if (defSa != null) {
            out.add(new Cand(null, false));
        }
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 7)));
        final Object prevAiCache3 = AiCache.captureScope(); // restored below, never removed (k8-determinism-1008)
        AiCache.openScope();
        final Object prevIds1 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            GameCopier copier = copierOf(live);
            copier.setCopyStack(cfg.stack);
            Game g = copier.makeCopy();
            Player me = (Player) copier.find(liveMe);
            List<SpellAbility> legal = new SpellAbilityPicker(me).getCandidateSpellsAndAbilities();
            try {
                legal.sort(ComputerUtilAbility.saEvaluator);
            } catch (IllegalArgumentException ignored) {
                // Forge's comparator is not always transitive; keep enumeration order.
            }
            Set<String> seen = new HashSet<>();
            for (Cand c : out) {
                seen.add(c.key());
            }
            final boolean guard = cfg.deadEtb != AiFixes.Mode.OFF;
            final boolean zguard = cfg.zeroX != AiFixes.Mode.OFF;
            final boolean cguard = cfg.crewNoop != AiFixes.Mode.OFF;
            final boolean aguard = cfg.deadAltCost != AiFixes.Mode.OFF;
            final boolean pguard = cfg.deadPump != AiFixes.Mode.OFF;
            final boolean dguard = cfg.deadFaceUp != AiFixes.Mode.OFF;
            final boolean oguard = cfg.ownHarm != AiFixes.Mode.OFF; // line-own-target-1119 (off: nothing runs)
            int deadHere = 0, zeroHere = 0, crewHere = 0, altHere = 0, pumpHere = 0, faceHere = 0, ownHere = 0;
            final Set<Integer> pumpKnown = pguard ? pumpKnownIds(live, liveMe) : null; // the seat's knowledge, from the live game
            ownHarmKeys.clear();
            final Set<String> ownHarmHosts = new HashSet<>(); // review 3: a refused spell's target variants are refused too
            if (oguard && defSa != null) {
                final SpellAbility d = locate(g, me, out.get(0));
                if (d != null && ownHarmWhy(me, d) != null) {
                    stats.ownHarmForge++; // Forge's own answer (its targets Forge AI's): counted, never demoted
                    ownHarmCounted(d);
                    ownHere++;
                    ownHarmHosts.add(ownHarmHost(out.get(0)));
                }
            }
            final DfuTally dfu = dguard ? new DfuTally(() -> pumpKnownIds(live, liveMe), () -> dfuLivePending(live)) : null;
            if (dguard) {
                deadFaceUpKeys.clear();
                deadFaceUpLast = null;
                if (defSa != null && DFU_DEAD.equals(dfu.check(locate(g, me, out.get(0)), me))) {
                    stats.deadFaceUpForge++; // Forge's own answer: never dropped
                    dfu.defaultDead++;
                }
            }
            if (pguard) {
                deadPumpKeys.clear();
                if (defSa != null) {
                    final SpellAbility d = locate(g, me, out.get(0));
                    if (d != null && deadPump(d, me, pumpKnown)) {
                        stats.deadPumpForge++;
                    }
                }
            }
            if (aguard) {
                deadAltCostKeys.clear();
                if (defSa != null) {
                    final SpellAbility d = locate(g, me, out.get(0));
                    if (d != null && deadAltCost(d, me)) {
                        stats.deadAltCostForge++;
                    }
                }
            }
            if (cguard) {
                crewNoopKeys.clear();
                if (defSa != null) {
                    final SpellAbility d = locate(g, me, out.get(0));
                    if (d != null && crewNoop(d, me)) {
                        stats.crewNoopForge++;
                    }
                }
            }
            if (zguard) {
                zeroXKeys.clear();
                if (defSa != null) {
                    final SpellAbility d = locate(g, me, out.get(0));
                    if (d != null && zeroXSpell(d, me)) {
                        stats.zeroXForge++;
                    }
                }
            }
            if (guard) {
                deadEtbKeys.clear();
                if (defSa != null) {
                    final SpellAbility d = locate(g, me, out.get(0));
                    if (d != null && deadEtbPermanent(d, me)) {
                        stats.deadEtbForge++;
                    }
                }
            }
            final PhaseHandler eph = g.getPhaseHandler();
            // endstep-rows-1103 (offTurnBreadth): outside our main phases the cut is the wider one
            final int cut = cfg.offTurnBreadth > cfg.breadth && !(eph.isPlayerTurn(me) && eph.getPhase() != null
                    && eph.getPhase().isMain()) ? cfg.offTurnBreadth : cfg.breadth;
            for (SpellAbility sa : legal) {
                if (out.size() >= cut) {
                    break;
                }
                Cand c = new Cand(sa, false);
                if (guard && !seen.contains(c.key()) && deadEtbPermanent(sa, me)) {
                    deadHere++;
                    stats.deadEtbCands++;
                    if (cfg.deadEtb == AiFixes.Mode.ON) {
                        // Dropped like a duplicate: the next legal candidate takes its breadth slot, and the prior
                        // tail and target variants below never see it.
                        seen.add(c.key());
                        stats.deadEtbDropped++;
                        continue;
                    }
                    deadEtbKeys.add(c.key());
                }
                if (zguard && !seen.contains(c.key()) && zeroXSpell(sa, me)) {
                    zeroHere++;
                    stats.zeroXCands++;
                    if (cfg.zeroX == AiFixes.Mode.ON) {
                        seen.add(c.key());
                        stats.zeroXDropped++;
                        continue;
                    }
                    zeroXKeys.add(c.key());
                }
                if (cguard && !seen.contains(c.key()) && crewNoop(sa, me)) {
                    crewHere++;
                    stats.crewNoopCands++;
                    if (cfg.crewNoop == AiFixes.Mode.ON) {
                        seen.add(c.key());
                        stats.crewNoopDropped++;
                        continue;
                    }
                    crewNoopKeys.add(c.key());
                }
                if (aguard && !seen.contains(c.key()) && deadAltCost(sa, me)) {
                    altHere++;
                    stats.deadAltCostCands++;
                    if (cfg.deadAltCost == AiFixes.Mode.ON) {
                        seen.add(c.key());
                        stats.deadAltCostDropped++;
                        continue;
                    }
                    deadAltCostKeys.add(c.key());
                }
                if (pguard && !seen.contains(c.key()) && deadPump(sa, me, pumpKnown)) {
                    pumpHere++;
                    stats.deadPumpCands++;
                    if (cfg.deadPump == AiFixes.Mode.ON) {
                        seen.add(c.key());
                        stats.deadPumpDropped++;
                        continue;
                    }
                    deadPumpKeys.add(c.key());
                }
                if (dguard && !seen.contains(c.key()) && DFU_DEAD.equals(dfu.check(sa, me))) {
                    faceHere++;
                    dfu.dead++;
                    stats.deadFaceUpCands++;
                    if (cfg.deadFaceUp == AiFixes.Mode.ON) {
                        seen.add(c.key());
                        stats.deadFaceUpDropped++;
                        continue;
                    }
                    deadFaceUpKeys.add(c.key());
                }
                if (oguard && !seen.contains(c.key())) {
                    final String why = ownHarmWhy(me, sa);
                    if (why != null) {
                        ownHere++;
                        stats.ownHarmCands++;
                        ownHarmCounted(sa);
                        ownHarmKeys.add(c.key()); // review 3: kept and played out; the choice demotes it (decideSearched)
                        ownHarmHosts.add(ownHarmHost(c));
                    }
                }
                if (seen.add(c.key())) {
                    out.add(c);
                }
            }
            if (deadHere > 0) {
                stats.deadEtbDecisions++;
            }
            if (zeroHere > 0) {
                stats.zeroXDecisions++;
            }
            if (crewHere > 0) {
                stats.crewNoopDecisions++;
            }
            if (altHere > 0) {
                stats.deadAltCostDecisions++;
            }
            if (pumpHere > 0) {
                stats.deadPumpDecisions++;
            }
            if (faceHere > 0) {
                stats.deadFaceUpDecisions++;
            }
            if (ownHere > 0) {
                stats.ownHarmDecisions++;
            }
            if (dfu != null) {
                deadFaceUpLast = dfu.finish(stats, cfg.deadFaceUp);
            }
            if (pv != null) {
                priorView(pv, g, me, legal, seen);
            }
            if (beliefRoot != null) {
                // Belief: the seat's ForgeState, from this copy (encoding reads lazily built views; never the live game).
                try {
                    beliefRoot[0] = forge.bench.StateEncoder.encode(g, me);
                } catch (RuntimeException e) {
                    beliefRoot[0] = null;
                }
            }
            if (labelRoot != null) {
                // Label dump: the tail count and the seat's ForgeState, from this copy, after B is fixed.
                // Never throws: a failure here must not reach the catch below, which would make the decision uncontested.
                labelTail = -1;
                try {
                    final Set<String> tailSeen = new HashSet<>(seen);
                    int tail = 0;
                    for (SpellAbility sa : legal) {
                        if (tailSeen.add(new Cand(sa, false).key())) {
                            tail++;
                        }
                    }
                    labelTail = tail;
                } catch (RuntimeException | StackOverflowError e) {
                    labelTail = -1;
                }
                labelRootError = null;
                try {
                    labelRoot[0] = forge.bench.StateEncoder.encode(g, me);
                } catch (RuntimeException | StackOverflowError e) {
                    labelRoot[0] = null;
                    labelRootError = String.valueOf(e);
                }
            }
            if (cfg.targetVariants > 0) {
                final int before = out.size();
                targetVariants(g, me, out, defSa, seen, cfg.targetVariants);
                stats.targetVariantCands += out.size() - before;
                for (int i = before; oguard && i < out.size(); i++) {
                    if (ownHarmHosts.contains(ownHarmHost(out.get(i)))) {
                        ownHarmKeys.add(out.get(i).key()); // line-own-target-1119: every target of a refused spell is ours
                        stats.ownHarmCands++;
                    }
                }
            }
        } catch (RuntimeException e) {
            // Enumeration failure: search nothing, play Forge's answer.
            if (cfg.stack && !live.getStack().isEmpty()) {
                stats.stackWhy.merge("copyfail", 1L, Long::sum);
                if (Boolean.getBoolean("lookahead.debug")) {
                    System.err.println("[lookahead] stack copy failed: " + e);
                }
            }
            return out.subList(0, 1);
        } finally {
            AiCache.installScope(prevAiCache3);
            forge.util.IdScope.install(prevIds1);
            MyRandom.setThreadRandom(prev);
        }
        return out;
    }

    /**
     * HX: the rest of Forge's list past B (the same sorted legal list, deduplicated by key against B and itself) and
     * the root state, from the enumeration copy, after B is fixed. Never throws: a failure is recorded in {@code pv}
     * and the decision keeps B.
     */
    private static void priorView(PriorView pv, Game g, Player me, List<SpellAbility> legal, Set<String> seenB) {
        final Set<String> seen = new HashSet<>(seenB);
        try {
            for (SpellAbility sa : legal) {
                Cand c = new Cand(sa, false);
                if (seen.add(c.key())) {
                    pv.tail.add(c);
                }
            }
        } catch (RuntimeException e) {
            pv.tail.clear();
            pv.error = "tail: " + e;
            return;
        }
        if (pv.tail.isEmpty()) {
            return;
        }
        try {
            pv.root = forge.bench.StateEncoder.encode(g, me);
        } catch (RuntimeException e) {
            pv.root = null;
            pv.error = "encode: " + e;
        }
    }

    /**
     * HX: one prior call over Forge's full list L = B + tail; B plus the top-{@code priorExtra} tail candidates by p
     * (null p never; ties to Forge's order), appended in Forge's order. In shadow mode, or on any failure, B itself.
     */
    private List<Cand> addPriorExtras(Game live, Player me, List<Cand> b, PriorView pv, JsonObject entry, int index, int turn,
            PhaseHandler ph) {
        stats.priorQualifying++;
        entry.addProperty("decision", index);
        entry.addProperty("turn", turn);
        entry.addProperty("phase", String.valueOf(ph.getPhase()));
        entry.addProperty("b", b.size());
        entry.addProperty("l", b.size() + pv.tail.size());
        stats.priorDecisions.add(entry);
        if (pv.tail.isEmpty() && pv.error == null) {
            // Nothing outside B: no extra is possible, no call.
            stats.priorNoRoom++;
            entry.addProperty("call", false);
            return b;
        }
        final List<Cand> all = new ArrayList<>(b);
        all.addAll(pv.tail);
        Double[] p = null;
        String error = pv.error;
        if (error == null) {
            final JsonObject req = forgeRequest(live, me);
            req.add("leaves", new JsonArray());
            req.add("root", pv.root);
            final JsonArray cj = new JsonArray();
            for (int i = 0; i < all.size(); i++) {
                cj.add(all.get(i).toPriorJson(i));
            }
            req.add("candidates", cj);
            final long t = System.nanoTime();
            p = prior.prior(req, all.size());
            final long dt = System.nanoTime() - t;
            stats.priorCalls++;
            stats.priorNanos += dt;
            stats.priorMaxNanos = Math.max(stats.priorMaxNanos, dt);
            entry.addProperty("ms", Math.round(dt / 1e4) / 100.0);
            if (p == null) {
                error = prior.lastError;
            }
        }
        entry.addProperty("call", error == null);
        if (error != null) {
            stats.priorFailures++;
            stats.priorLastError = error;
            entry.addProperty("error", error);
            System.err.println("[lookahead] prior call failed at decision " + index + ", keeping Forge's B set: " + error);
            return b;
        }
        stats.priorDigest = prior.digestHex();
        stats.priorCheckpoint = prior.checkpointSha256;
        stats.priorUnknownCards = prior.unknownCards;
        final int[] pick = selectExtras(p, b.size(), cfg.priorExtra);
        final JsonArray ex = new JsonArray();
        for (int i : pick) {
            final JsonObject e = new JsonObject();
            e.addProperty("i", i);
            e.addProperty("kind", all.get(i).kind);
            e.addProperty("label", all.get(i).label);
            e.addProperty("p", p[i]);
            ex.add(e);
        }
        final JsonArray pB = new JsonArray();
        for (int i = 0; i < b.size(); i++) {
            pB.add(p[i]);
        }
        entry.add("pB", pB);
        if (cfg.priorShadow) {
            entry.add("wouldAdd", ex);
            if (pick.length > 0) {
                stats.shadowDecisionsWithExtras++;
                stats.shadowExtras += pick.length;
            }
            return b;
        }
        entry.add("extras", ex);
        if (pick.length == 0) {
            return b;
        }
        stats.decisionsWithExtras++;
        stats.extrasAdded += pick.length;
        final List<Cand> out = new ArrayList<>(b);
        for (int i : pick) {
            out.add(all.get(i));
        }
        return out;
    }

    /**
     * HX selection: the indices {@code >= bSize} with non-null p, the top {@code n} by p (ties to the lower index, Forge's
     * order), returned in ascending index order.
     */
    static int[] selectExtras(Double[] p, int bSize, int n) {
        final List<Integer> idx = new ArrayList<>();
        for (int i = bSize; i < p.length; i++) {
            if (p[i] != null && !p[i].isNaN()) {
                idx.add(i);
            }
        }
        idx.sort((x, y) -> {
            final int c = Double.compare(p[y], p[x]);
            return c != 0 ? c : Integer.compare(x, y);
        });
        final int m = Math.max(0, Math.min(n, idx.size()));
        final int[] out = new int[m];
        for (int j = 0; j < m; j++) {
            out[j] = idx.get(j);
        }
        Arrays.sort(out);
        return out;
    }

    /** Lane copy-fidelity-1010 (diagnostics): why {@link #locate} (or Forge's preparation) found no candidate in the copy. */
    static String locateMiss(Game g, Player p, Cand c) {
        try {
            final Card host = g.findById(c.hostId);
            if (host == null) {
                return "no card " + c.hostId + " in the copy";
            }
            final StringBuilder sb = new StringBuilder(host.getName()).append(" in ")
                    .append(host.getZone() == null ? "no zone" : host.getZone().getZoneType().name());
            final SpellAbility sa = locate(g, p, c);
            if (sa != null) {
                return sb.append(": found, preparation failed").toString();
            }
            sb.append(" (").append(host.getCurrentStateName()).append("): no '").append(c.desc.length() > 100 ? c.desc.substring(0, 100) : c.desc)
                    .append("' among [");
            int n = 0;
            for (SpellAbility x : ComputerUtilAbility.getSpellAbilities(new CardCollection(host), p)) {
                if (n++ > 0) {
                    sb.append(" | ");
                }
                final String d = x.getDescription();
                sb.append(d.length() > 100 ? d.substring(0, 100) : d);
                if (x.getMayPlay() != null && x.getMayPlay().getHostCard() != null) {
                    final Card mh = x.getMayPlay().getHostCard();
                    final Card es = mh.getEffectSource();
                    sb.append(" {may-play host ").append(mh.getName()).append(mh.isImmutable() ? " effect" : "")
                            .append(", source ").append(es == null ? "none" : es + "/" + es.getCurrentStateName()
                                    + (es.getZone() == null ? " no zone" : " " + es.getZone().getZoneType()))
                            .append(mh.getEffectSourceAbility() != null ? ", by ability" : "").append('}');
                }
            }
            return sb.append(']').toString();
        } catch (RuntimeException e) {
            return "locate diagnostics failed: " + e;
        }
    }

    static SpellAbility locate(Game g, Player p, Cand c) {
        return locate(g, p, c, false);
    }

    /** As {@link #locate(Game, Player, Cand)}; with {@code mayPlayLoose} (copy fidelity), last a may-play match. */
    static SpellAbility locate(Game g, Player p, Cand c, boolean mayPlayLoose) {
        return locate(g, p, c, mayPlayLoose, false);
    }

    /**
     * As above; with {@code unique} (step3-playout-fixes-1028, review #130 S3-2: the re-plays' key) the loose match is
     * {@link #sameMayPlayText} and is accepted only when exactly one ability matches (else null: candidate_not_in_copy).
     */
    static SpellAbility locate(Game g, Player p, Cand c, boolean mayPlayLoose, boolean unique) {
        final Card host = g.findById(c.hostId);
        if (host == null) {
            return null;
        }
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(new CardCollection(host), p);
        all = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, p);
        for (SpellAbility sa : all) {
            if (sa.isLandAbility() == c.land && c.desc.equals(sa.getDescription())) {
                return sa;
            }
        }
        for (SpellAbility sa : all) {
            if (sa.isLandAbility() == c.land && c.desc.startsWith(sa.getDescription())) {
                return sa;
            }
        }
        if (mayPlayLoose && unique) {
            SpellAbility one = null;
            int n = 0;
            for (SpellAbility sa : all) {
                if (sa.isLandAbility() == c.land && sa.getMayPlay() != null && sameMayPlayText(c.desc, sa.getDescription())) {
                    one = sa;
                    n++;
                }
            }
            return n == 1 ? one : null;
        }
        if (mayPlayLoose) {
            for (SpellAbility sa : all) {
                if (sa.isLandAbility() == c.land && sameMayPlay(c.desc, sa)) {
                    return sa;
                }
            }
        }
        if (c.maySig != null) {
            // sdcc-copycast-1114 (copyCast): the same may-play grant on the same card, whatever its source prints as
            for (SpellAbility sa : all) {
                if (sa.isLandAbility() == c.land && c.maySig.equals(maySig(sa))) {
                    return sa;
                }
            }
        }
        return null;
    }

    /**
     * Lane sdcc-copycast-1114: the structure of a may-play ability (a cast or land play an effect permits: from the
     * graveyard, from exile, ...), equal in the live game and in every copy of it: the granting card's id, how the mana
     * cost is paid (as printed, none, another cost), flash, the ability's alternative cost, and its own text up to the
     * may-play suffix (the suffix names the grant's source, which a copy prints differently). Null if {@code sa} is not
     * a may-play ability.
     */
    static String maySig(SpellAbility sa) {
        if (sa == null) {
            return null;
        }
        final forge.game.card.CardPlayOption o = sa.getMayPlayOption();
        if (o == null || o.getHost() == null) {
            return null;
        }
        final String d = String.valueOf(sa.getDescription());
        final int i = d.indexOf(" by ");
        return o.getHost().getId() + "|" + o.getPayManaCost() + "|" + o.getAltManaCost() + "|" + o.isWithFlash() + "|"
                + sa.getAlternativeCost() + "|" + (sa.isLandAbility() ? "L" : "S") + "|" + (i >= 0 ? d.substring(0, i) : d);
    }

    /** sdcc-copycast-1114: the paid optional costs of {@code sa}, in a fixed order ("" = none). */
    static String mayOpt(SpellAbility sa) {
        final java.util.TreeSet<String> s = new java.util.TreeSet<>();
        if (sa != null) {
            for (forge.game.spellability.OptionalCost c : sa.getOptionalCosts()) {
                s.add(c.name());
            }
        }
        return String.join(",", s);
    }

    /** A candidate of this search: with {@link Config#copyCast} it carries its may-play structure. */
    Cand cand(SpellAbility sa, boolean isDefault) {
        final Cand c = new Cand(sa, isDefault);
        if (cfg.copyCast && sa != null) {
            c.maySig = maySig(sa);
            c.mayOpt = c.maySig == null ? null : mayOpt(sa);
        }
        return c;
    }

    /**
     * step3-playout-fixes-1028 (review #130 S3-2): the may-play suffix starts at the LAST " by " (rules text may contain
     * " by " itself: "dealt damage by", "reduced by"). True when {@code copy} and the searched {@code desc} have the same
     * text before their last " by " and differ after it (the effect's source as printed).
     */
    static boolean sameMayPlayText(String desc, String copy) {
        final int i = copy.lastIndexOf(" by ");
        return i > 0 && desc.lastIndexOf(" by ") == i && desc.regionMatches(0, copy, 0, i + 4) && !desc.equals(copy);
    }

    /**
     * Copy fidelity: a may-play spell's description is its base description plus " by " and the effect's source as that
     * card object printed when the menu was built (an adventurer's source prints as its adventure in the live game and as
     * the creature in a copy: "... by Stomp (40) by Stomp (40)" against "... by Bonecrusher Giant (40)"). True when
     * {@code sa} is a may-play ability and both descriptions are its base description followed by " by ".
     */
    static boolean sameMayPlay(String desc, SpellAbility sa) {
        if (sa.getMayPlay() == null) {
            return false;
        }
        final String d = sa.getDescription();
        final int i = d.indexOf(" by ");
        if (i <= 0) {
            return false;
        }
        final String base = d.substring(0, i + 4);
        return desc.startsWith(base) && !desc.equals(d);
    }

    /** Prepare a candidate's choices in a copy: Forge's answer copies its live choices, others ask Forge AI. */
    private static SpellAbility prepare(Game g, Player me, Cand c, SpellAbility defSa, GameCopier copier) {
        SpellAbility sa = locate(g, me, c);
        if (sa == null) {
            return null;
        }
        sa.setActivatingPlayer(me);
        if (c.land) {
            return sa;
        }
        if (c.isDefault && defSa != null) {
            SpellAbility d = SpellAbilityChoiceCopier.copyCastChoices(defSa, sa, me);
            if (d == null) {
                return null;
            }
            // Never carry a target over unmapped: start clean, then map Forge's live choices in.
            for (SpellAbility sub = d; sub != null; sub = sub.getSubAbility()) {
                if (sub.usesTargeting()) {
                    sub.resetTargets();
                }
            }
            SpellAbilityChoiceCopier.copyTargets(defSa, d, copier::findWithStack);
            if (!targetsInGame(d, g)) {
                return null;
            }
            return d;
        }
        for (SpellAbility sub = sa; sub != null; sub = sub.getSubAbility()) {
            if (sub.usesTargeting()) {
                sub.resetTargets();
            }
        }
        AiPlayDecision dec = ((PlayerControllerAi) me.getController()).getAi().canPlaySa(sa);
        if (c.tgt != null) {
            if (!applyTarget(sa, c.tgt, g)) {
                return null;
            }
        } else if (dec != AiPlayDecision.WillPlay && needsTargets(sa)) {
            return null;
        }
        if (!targetsInGame(sa, g)) {
            return null;
        }
        return sa;
    }

    /** The first targeting (sub-)ability whose target slot takes exactly one target at most; null if none. */
    static SpellAbility singleTargetSlot(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                return s.getMaxTargets() == 1 ? s : null;
            }
        }
        return null;
    }

    /** Target reference of a game object: "P<player index>" or "C<card id>". */
    static String targetRef(Game g, forge.game.GameObject o) {
        if (o instanceof Player) {
            return "P" + g.getPlayers().indexOf(o);
        }
        if (o instanceof Card) {
            return "C" + ((Card) o).getId();
        }
        return null;
    }

    /** Put target {@code ref} into the ability's single-target slot in game {@code g}; false if it is not legal there. */
    static boolean applyTarget(SpellAbility sa, String ref, Game g) {
        final SpellAbility slot = singleTargetSlot(sa);
        if (slot == null) {
            return false;
        }
        slot.resetTargets();
        if ("N".equals(ref)) {
            return slot.getMinTargets() == 0;
        }
        forge.game.GameObject o = null;
        if (ref.startsWith("P")) {
            final int i = Integer.parseInt(ref.substring(1));
            o = i >= 0 && i < g.getPlayers().size() ? g.getPlayers().get(i) : null;
        } else if (ref.startsWith("C")) {
            o = g.findById(Integer.parseInt(ref.substring(1)));
        }
        if (o == null || !slot.canTarget(o)) {
            return false;
        }
        slot.getTargets().add(o);
        return true;
    }

    /**
     * Target variants: for each non-pass, non-land candidate (in order) with a single-target slot, the other legal
     * targets than Forge AI's own choice (known for Forge's answer; asked of Forge AI in the enumeration copy for the
     * others), ordered opponents, opponents' cards, none (if allowed), own player, own cards; at most {@code max} in all.
     */
    private static void targetVariants(Game g, Player me, List<Cand> out, SpellAbility defSa, Set<String> seen, int max) {
        final List<Cand> base = new ArrayList<>(out);
        int added = 0;
        for (Cand c : base) {
            if (added >= max) {
                return;
            }
            if (c.pass || c.land) {
                continue;
            }
            final SpellAbility sa = locate(g, me, c);
            if (sa == null) {
                continue;
            }
            sa.setActivatingPlayer(me);
            String chosen = null;
            if (c.isDefault && defSa != null) {
                final SpellAbility live = singleTargetSlot(defSa);
                if (live == null) {
                    continue;
                }
                chosen = live.getTargets().isEmpty() ? "N" : targetRef(defSa.getHostCard().getGame(), live.getTargets().get(0));
            } else {
                for (SpellAbility sub = sa; sub != null; sub = sub.getSubAbility()) {
                    if (sub.usesTargeting()) {
                        sub.resetTargets();
                    }
                }
                ((PlayerControllerAi) me.getController()).getAi().canPlaySa(sa);
            }
            final SpellAbility slot = singleTargetSlot(sa);
            if (slot == null) {
                continue;
            }
            if (chosen == null) {
                chosen = slot.getTargets().isEmpty() ? "N" : targetRef(g, slot.getTargets().get(0));
            }
            slot.resetTargets();
            final List<forge.game.GameEntity> all = slot.getTargetRestrictions().getAllCandidates(slot);
            final List<String[]> refs = new ArrayList<>();
            for (int pass = 0; pass < 5; pass++) {
                if (pass == 2) {
                    if (slot.getMinTargets() == 0) {
                        refs.add(new String[] {"N", "no target"});
                    }
                    continue;
                }
                for (forge.game.GameEntity e : all) {
                    final boolean opp = e instanceof Player ? ((Player) e).isOpponentOf(me) : ((Card) e).getController().isOpponentOf(me);
                    final boolean player = e instanceof Player;
                    final int bucket = opp ? (player ? 0 : 1) : (player ? 3 : 4);
                    if (bucket == pass) {
                        refs.add(new String[] {targetRef(g, e), e instanceof Card ? ((Card) e).getName() + " (" + ((Card) e).getId() + ")" : ((Player) e).getName()});
                    }
                }
            }
            for (String[] r : refs) {
                if (added >= max) {
                    return;
                }
                if (r[0] == null || r[0].equals(chosen)) {
                    continue;
                }
                final Cand v = new Cand(c, r[0], r[1]);
                if (seen.add(v.key())) {
                    out.add(v);
                    added++;
                }
            }
        }
    }

    /** Every chosen target of the ability (and its sub-abilities) lives in game {@code g}. */
    static boolean targetsInGame(SpellAbility sa, Game g) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (!s.usesTargeting()) {
                continue;
            }
            for (forge.game.GameObject o : s.getTargets()) {
                if (o instanceof Card && ((Card) o).getGame() != g) {
                    return false;
                }
                if (o instanceof Player && ((Player) o).getGame() != g) {
                    return false;
                }
                if (o instanceof SpellAbility && ((SpellAbility) o).getHostCard().getGame() != g) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean needsTargets(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && !s.isTargetNumberValid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * crewNoop guard: true for a Crew activation of a Vehicle that can no longer attack or block this turn, so crewing it
     * does nothing before the effect ends at cleanup: on its controller's own turn at or after the declare-attackers step,
     * on another player's turn at or after the declare-blockers step. Not flagged (left to the search): any extra phase
     * pending this turn (another combat may follow), and any trigger in play that watches crewing or tapping (Crewed,
     * BecomesCrewed, Taps), since those make the activation itself do something. Reads only.
     */
    static boolean crewNoop(SpellAbility sa, Player me) {
        if (sa == null || !sa.isCrew()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null || !host.getType().hasSubtype("Vehicle")) {
            return false;
        }
        final Game game = me.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        final forge.game.phase.PhaseType phase = ph.getPhase();
        if (phase == null || extraPhasePending(ph)) {
            return false;
        }
        final boolean own = ph.isPlayerTurn(host.getController());
        final forge.game.phase.PhaseType last = own ? forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS
                : forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS;
        if (phase.isBefore(last)) {
            return false;
        }
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            for (Trigger t : c.getTriggers()) {
                final TriggerType m = t.getMode();
                if (m == TriggerType.Crewed || m == TriggerType.BecomesCrewed || m == TriggerType.Taps) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True when PhaseHandler has an extra phase queued this turn (an extra combat may follow). */
    static boolean extraPhasePending(PhaseHandler ph) {
        return !ph.getExtraPhasesView().isEmpty(); // state-machines-1113: the public view, not reflection
    }

    /**
     * zeroX guard: true for a spell (any type) with {X} in its mana cost whose largest X payable now is 0
     * ({@link forge.ai.ComputerUtilMana#determineLeftoverMana}) and that Forge's own AI would not cast now
     * ({@code canPlaySa} is not WillPlay) and that has no target slot at X = 0 -- e.g. Pest Infestation (X X G) with two
     * mana: destroy up to 0 targets, create 0 tokens. Not flagged: a zero-X spell Forge's AI does cast (a deliberate
     * X = 0, e.g. Chalice of the Void), and one that still targets at X = 0 (Unexpectedly Absent). Runs in the
     * enumeration copy only (the checks set X on that copy's ability).
     */
    static boolean zeroXSpell(SpellAbility sa, Player me) {
        if (sa == null || !sa.isSpell() || sa.isLandAbility() || !sa.costHasManaX()) {
            return false;
        }
        // Work on a copy: X and the AI check's choices are written onto the ability, and an enumeration copy's
        // ability is not guaranteed to be private to the copy (a shadow replay diverged when the original was used).
        final SpellAbility x = sa.copy(me);
        if (forge.ai.ComputerUtilMana.determineLeftoverMana(x, me, false) > 0) {
            return false;
        }
        // At X = 0 the spell must have no target slot left (Pest Infestation: "up to X targets"). A spell that still
        // targets at X = 0 has an effect (Unexpectedly Absent puts its target on top of the library) and is left alone.
        x.setXManaCostPaid(0);
        for (SpellAbility s = x; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && s.getMaxTargets() > 0) {
                return false;
            }
        }
        return ((PlayerControllerAi) me.getController()).getAi().canPlaySa(x) != AiPlayDecision.WillPlay;
    }

    /**
     * deadAltCost guard: true for a spell whose cost spends cards beyond the spell itself, cast where the effect those cards
     * pay for buys nothing on this board. The costs: evoke (the body is sacrificed as it enters, so the cast is worth only
     * its enters-the-battlefield triggers) and any cost that exiles or discards a card from hand. The effect: for an evoked
     * permanent, each of its own enters-the-battlefield triggers (ChangesZone to the battlefield, ValidCard Card.Self; the
     * evoke keyword's own sacrifice trigger is the cost and is skipped); for
     * an instant or sorcery, the spell's own ability. Each is asked of Forge's AI as an optional trigger without its cost
     * ({@code doTriggerNoCostWithSubs} on a copy), which weighs the targets this board offers: damage or removal with no
     * opposing target, or with only the caster's own, is declined. Dead when every such effect uses targeting and is
     * declined. Not dead: a permanent that keeps its body (cast without evoke); an evoked permanent with no such trigger or
     * one this check cannot read (another ValidCard, an unmet condition, an origin other than hand or stack); an effect
     * without targeting; anything that throws. Reads only: copies are asked, under a fixed thread random that is restored.
     */
    /** deadAltCost: the ValidCard of the trigger the evoke keyword adds (CardFactoryUtil): sacrifice it if evoked. */
    static final String EVOKE_SACRIFICE = "Card.Self+evoked";

    static boolean deadAltCost(SpellAbility sa, Player me) {
        if (sa == null || !sa.isSpell() || sa.isLandAbility()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        final boolean evoke = sa.isEvoke();
        if (!evoke && !spendsHandCard(sa)) {
            return false;
        }
        final CardState state = sa.getCardState() != null ? sa.getCardState() : host.getCurrentState();
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(0x1111_0312L));
        try {
            if (!state.getType().isPermanent()) {
                return effectDeclined(sa.copy(me), me);
            }
            if (!evoke) {
                return false;
            }
            final Game game = me.getGame();
            int declined = 0;
            for (final Trigger tr : state.getTriggers()) {
                if (tr.getMode() != TriggerType.ChangesZone || !ZoneType.Battlefield.toString().equals(tr.getParam("Destination"))) {
                    continue;
                }
                final String valid = tr.getParam("ValidCard");
                if (valid == null || !valid.contains("Self")) {
                    continue;
                }
                if (EVOKE_SACRIFICE.equals(valid)) {
                    continue; // the evoke keyword's own sacrifice: part of the cost, not an effect it buys
                }
                if (!"Card.Self".equals(valid) || tr.hasParam("Origin") && !"Any".equals(tr.getParam("Origin"))
                        && !"Hand".equals(tr.getParam("Origin")) && !"Stack".equals(tr.getParam("Origin"))) {
                    return false;
                }
                if (!tr.requirementsCheck(game)) {
                    return false;
                }
                final SpellAbility ex = tr.ensureAbility();
                if (ex == null || !effectDeclined(ex.copy(me), me)) {
                    return false;
                }
                declined++;
            }
            return declined > 0;
        } catch (RuntimeException e) {
            return false;
        } finally {
            MyRandom.setThreadRandom(prev);
        }
    }

    /** deadAltCost: the spell's pay cost exiles or discards a card from hand (a card spent beyond the spell itself). */
    static boolean spendsHandCard(SpellAbility sa) {
        final forge.game.cost.Cost cost = sa.getPayCosts();
        if (cost == null) {
            return false;
        }
        for (final forge.game.cost.CostPart p : cost.getCostParts()) {
            if (p instanceof forge.game.cost.CostDiscard) {
                return true;
            }
            if (p instanceof forge.game.cost.CostExile && ((forge.game.cost.CostExile) p).getFrom().contains(ZoneType.Hand)) {
                return true;
            }
        }
        return false;
    }

    /**
     * deadAltCost: Forge's AI declines this effect (a copy) as an optional trigger without its cost. An effect without
     * targeting is never declined here (its value is not a target this board must offer).
     */
    static boolean effectDeclined(SpellAbility ab, Player me) {
        if (ab == null || !ab.usesTargeting() || ab.getApi() == null) {
            return false;
        }
        ab.setActivatingPlayer(me);
        return !forge.ai.SpellApiToAi.Converter.get(ab).doTriggerNoCostWithSubs(me, ab, false).willingToPlay();
    }

    /**
     * deadPump guard: true for a spell or activated ability (never a mana ability) whose whole resolution changes only power
     * until end of turn, when that change cannot matter: the window closes at this turn's cleanup, and a power change acts
     * only through the damage the creature deals. All three must hold, and anything this check cannot read leaves the
     * candidate to the search:
     * <ol>
     * <li>The ability and each sub-ability is Pump or PumpAll with a non-zero NumAtt and NumDef absent or 0, and carries
     * only parameters that say whom it changes, how it targets, when it may be played and how it reads
     * ({@link #DEAD_PUMP_PARAMS}): no keyword, no Duration (so until end of turn), no condition, no other effect. No optional
     * cost, no additional ability, and a cost of mana, discard, life, sacrifice or exile from hand only ({@link #pumpCost}).
     * </li>
     * <li>No extra phase is pending, and no card it changes can still deal combat damage this turn ({@link #pumpLive}).
     * The cards are its Defined cards, every legal target (the target is chosen only in the play-out), or PumpAll's current
     * valid set.</li>
     * <li>Nothing can react to paying its cost or to playing it ({@link #pumpReacts}).</li>
     * <li>Nothing the seat can see reads power (review S1 2, 3: {@link #pumpReadsPower}): no static, trigger or replacement
     * working where its card is, no activated ability on the battlefield, no spell in a hand it sees, whose parameters (or the
     * SVars they name) read power. An Ensnaring Bridge makes the changed power decide an attack; a Zilortha makes it decide
     * lethal damage now.</li>
     * <li>The active player can get no new attacker this turn (review S1 4: {@link #pumpAttackersMayAppear}): an animation
     * (Mutavault), a Vehicle, a haste or untap source among its permanents, or in the seat's own hand when it is the active
     * player, keeps every changed creature live while attackers are not declared.</li>
     * </ol>
     * <b>What the seat may know</b> (review S1 1): every read above goes through the activating seat's information: public
     * zones, its own hand, and cards revealed to it ({@code known}; {@link #pumpKnows} when null). The guard runs on an exact
     * copy of the live game (the enumeration copy), before determinization, where a hidden card still holds its true
     * identity: an opponent's unrevealed hand or library card, or a face-down card, is never read.
     * <p>
     * Out of scope: power readers the seat cannot see (an opponent's hidden hand), spells cast from a graveyard or exile, and
     * the seat's later choices; an instant-speed pump stays available later in the turn, and the policy's own choice and
     * Forge's answer are never dropped. Reads only: it asks a copy of the ability.
     */
    static boolean deadPump(SpellAbility sa, Player me) {
        return deadPump(sa, me, null);
    }

    /**
     * deadPump with the seat's knowledge given as card ids ({@link #pumpKnownIds}, read in the live game: the enumeration
     * copy keeps card ids but not who may look at what); null reads it from {@code me}'s own game ({@link #pumpKnows}).
     */
    static boolean deadPump(SpellAbility sa, Player me, Set<Integer> known) {
        if (sa == null || me == null || sa.getHostCard() == null || sa.isManaAbility() || sa.isLandAbility()
                || !(sa.isSpell() || sa.isActivatedAbility())
                || sa.getApi() != forge.game.ability.ApiType.Pump && sa.getApi() != forge.game.ability.ApiType.PumpAll) {
            return false;
        }
        try {
            final SpellAbility x = sa.copy(sa.getHostCard(), me, false, true); // its parameters as they read now (text changes kept)
            final List<SpellAbility> chain = new ArrayList<>();
            for (SpellAbility s = x; s != null; s = s.getSubAbility()) {
                if (!powerOnlyPump(s) || !s.getAdditionalAbilities().isEmpty() || !s.getAdditionalAbilityLists().isEmpty()) {
                    return false;
                }
                chain.add(s);
            }
            if (x.getOptionalCosts().iterator().hasNext()) {
                return false;
            }
            final PumpCost pay = pumpCost(x, me);
            if (pay == null) {
                return false;
            }
            final PhaseHandler ph = me.getGame().getPhaseHandler();
            if (ph.getPhase() == null || ph.getPlayerTurn() == null || extraPhasePending(ph)) {
                return false;
            }
            // the cards the seat may know of; nothing below reads any other card (review S1 1)
            final List<Card> seen = new ArrayList<>();
            for (Card c : me.getGame().getCardsInGame()) {
                if (known == null ? pumpKnows(me, c) : known.contains(c.getId())) {
                    seen.add(c);
                }
            }
            if (pumpReadsPower(seen, me)) {
                return false;
            }
            final boolean newAttackers = pumpAttackersMayAppear(seen, ph.getPlayerTurn(), me);
            for (SpellAbility s : chain) {
                final List<Card> changed = pumpAffected(s, me);
                if (changed == null) {
                    return false;
                }
                for (Card c : changed) {
                    if (pumpLive(c, ph, newAttackers)) {
                        return false;
                    }
                }
            }
            return !pumpReacts(x, chain, me, pay, seen);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * deadPump: the parameters a power-only pump may carry. They say whom it changes, how it targets, when it may be played
     * and how it reads. Any other parameter (KW, KWChoice, Duration, AtEOT, PumpZone, Radiance, a Condition, Optional,
     * UnlessCost, RememberPumped, Planeswalker, ...) is an effect or a condition, and the pump is left to the search.
     */
    static final Set<String> DEAD_PUMP_PARAMS = new HashSet<>(Arrays.asList("AB", "SP", "DB", "Cost", "CostDesc",
            "PrecostDesc", "NumAtt", "NumDef", "Defined", "ValidTgts", "TgtPrompt", "TargetMin", "TargetMax", "TargetUnique",
            "ValidTgtsDesc", "AITgts", "ValidCards", "IsCurse", "AILogic", "SpellDescription", "StackDescription", "SubAbility",
            "Activator", "ActivationLimit", "GameActivationLimit", "ActivationPhases", "SorcerySpeed", "PlayerTurn"));

    /** deadPump: {@code s} is a Pump or PumpAll that changes only power (non-zero NumAtt, NumDef absent or 0), until end of turn. */
    static boolean powerOnlyPump(SpellAbility s) {
        if (s.getApi() != forge.game.ability.ApiType.Pump && s.getApi() != forge.game.ability.ApiType.PumpAll) {
            return false;
        }
        for (String k : s.getMapParams().keySet()) {
            if (!DEAD_PUMP_PARAMS.contains(k)) {
                return false;
            }
        }
        final String att = s.getParam("NumAtt");
        final String def = s.getParam("NumDef");
        if (att == null || "Double".equals(att) || "Triple".equals(att) || "Double".equals(def) || "Triple".equals(def)) {
            return false;
        }
        final Card host = s.getHostCard();
        return forge.game.ability.AbilityUtils.calculateAmount(host, att, s, true) != 0
                && (def == null || forge.game.ability.AbilityUtils.calculateAmount(host, def, s, true) == 0);
    }

    /** deadPump: what paying a cost does: the zones it may take cards from, and the kinds of payment it makes. */
    static final class PumpCost {
        boolean mana, discard, sacrifice, life, exile;
        final java.util.EnumSet<ZoneType> from = java.util.EnumSet.noneOf(ZoneType.class);
    }

    /**
     * deadPump: the payment of {@code sa}'s cost, or null when a part of it may do something by itself (untapping,
     * counters, returning, milling, tapping, revealing, exiling from a graveyard, ...). Mana may be paid by sacrificing or
     * discarding (a Treasure, Lion's Eye Diamond) or with life (Phyrexian mana), so a mana part counts as all three. A spell
     * also moves itself, from its zone to the stack and then to the graveyard.
     */
    static PumpCost pumpCost(SpellAbility sa, Player me) {
        final PumpCost pay = new PumpCost();
        final forge.game.cost.Cost cost = sa.getPayCosts();
        if (cost != null) {
            for (final forge.game.cost.CostPart p : cost.getCostParts()) {
                if (p instanceof forge.game.cost.CostPartMana) {
                    if (!((forge.game.cost.CostPartMana) p).getMana().isZero()) {
                        pay.mana = true;
                        pay.from.add(ZoneType.Battlefield);
                        pay.from.add(ZoneType.Hand);
                    }
                } else if (p instanceof forge.game.cost.CostDiscard) {
                    pay.discard = true;
                    pay.from.add(ZoneType.Hand);
                } else if (p instanceof forge.game.cost.CostPayLife) {
                    pay.life = true;
                } else if (p instanceof forge.game.cost.CostSacrifice) {
                    pay.sacrifice = true;
                    pay.from.add(ZoneType.Battlefield);
                } else if (p.getClass() == forge.game.cost.CostExile.class
                        && Collections.singletonList(ZoneType.Hand).equals(((forge.game.cost.CostExile) p).getFrom())) {
                    pay.exile = true;
                    pay.from.add(ZoneType.Hand);
                } else {
                    return null;
                }
            }
        }
        if (sa.isSpell()) {
            final forge.game.zone.Zone z = me.getGame().getZoneOf(sa.getHostCard());
            if (z == null) {
                return null;
            }
            pay.from.add(z.getZoneType());
            pay.from.add(ZoneType.Stack);
        }
        return pay;
    }

    /**
     * deadPump: the cards {@code s} would change, or null when this check cannot read them. For PumpAll, its current valid
     * set on the battlefield (whatever it targets: a superset). For a targeted Pump, every legal target, since the target is
     * chosen only in the play-out (null if a player is a legal target or nothing is). Otherwise its Defined cards (default
     * Self; null if there are none).
     */
    static List<Card> pumpAffected(SpellAbility s, Player me) {
        if (s.getApi() == forge.game.ability.ApiType.PumpAll) {
            final String valid = s.getParam("ValidCards");
            if (valid == null) {
                return null;
            }
            return new ArrayList<>(forge.game.ability.AbilityUtils.filterListByType(
                    me.getGame().getCardsIn(ZoneType.Battlefield), valid, s));
        }
        final List<Card> out = new ArrayList<>();
        if (s.usesTargeting()) {
            final forge.game.spellability.TargetRestrictions tr = s.getTargetRestrictions();
            if (tr == null) {
                return null;
            }
            for (forge.game.GameEntity e : tr.getAllCandidates(s, false)) {
                if (!(e instanceof Card)) {
                    return null;
                }
                out.add((Card) e);
            }
        } else {
            for (String d : s.getParamOrDefault("Defined", "Self").split(" & ")) {
                out.addAll(forge.game.ability.AbilityUtils.getDefinedCards(s.getHostCard(), d, s));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * deadPump: {@code c} may still deal combat damage this turn (the caller has checked that no extra phase is pending).
     * Live if any of these hold:
     * <ul>
     * <li>it is attacking or blocking, before the combat damage step;</li>
     * <li>its controller is the active player, attackers are not declared yet, and it can attack ({@code CombatUtil});</li>
     * <li>it is another player's untapped creature, blockers are not declared yet, and the active player has a creature
     * attacking or able to attack.</li>
     * </ul>
     * A card off the battlefield, phased out or not a creature is not read here (live). {@code newAttackers}
     * ({@link #pumpAttackersMayAppear}): the active player may get an attacker that cannot attack now, so before attackers
     * are declared every changed creature is live, its own and the other side's.
     */
    static boolean pumpLive(Card c, PhaseHandler ph, boolean newAttackers) {
        if (c == null || !c.isInPlay() || c.isPhasedOut() || !c.isCreature()) {
            return true;
        }
        final forge.game.phase.PhaseType phase = ph.getPhase();
        final forge.game.combat.Combat combat = ph.getCombat();
        if (combat != null && (combat.isAttacking(c) || combat.isBlocking(c))
                && phase.isBefore(forge.game.phase.PhaseType.COMBAT_DAMAGE)) {
            return true;
        }
        final boolean beforeAttackers = phase.isBefore(forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS);
        if (ph.isPlayerTurn(c.getController())) {
            return beforeAttackers && (newAttackers || forge.game.combat.CombatUtil.canAttack(c));
        }
        if (!phase.isBefore(forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS) || c.isTapped()) {
            return false;
        }
        if (beforeAttackers && newAttackers) {
            return true;
        }
        for (Card a : ph.getPlayerTurn().getCreaturesInPlay()) {
            if (combat != null && combat.isAttacking(a)
                    || phase.isBefore(forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS) && forge.game.combat.CombatUtil.canAttack(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * deadPump: something may react to paying {@code sa}'s cost or to playing it, so the guard stands aside. Any of:
     * <ul>
     * <li>anything waiting on the stack (it may read what the payment changes: a hand size, a sacrificed target, a power);
     * </li>
     * <li>a madness card in the activator's hand, when the cost discards;</li>
     * <li>a trigger of a mode this payment and play can fire ({@link #pumpModes}), on a card in any zone where it works
     * ({@code zonesCheck}) or among the delayed triggers;</li>
     * <li>a zone-change replacement (Moved, which covers discarding) that works where its card is, when the payment moves
     * cards.</li>
     * </ul>
     * {@link #pumpSees} leaves out what this payment cannot reach. Only the cards in {@code seen} are read (the seat's
     * knowledge, review S1 1): an opponent's hidden discard trigger is not, so it cannot change the decision.
     */
    static boolean pumpReacts(SpellAbility sa, List<SpellAbility> chain, Player me, PumpCost pay, List<Card> seen) {
        final Game game = me.getGame();
        if (!game.getStack().isEmpty()) {
            return true;
        }
        if (pay.discard) {
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (c.hasKeyword(forge.game.keyword.Keyword.MADNESS)) {
                    return true;
                }
            }
        }
        final Set<TriggerType> modes = pumpModes(sa, chain, pay);
        final boolean moves = !pay.from.isEmpty();
        for (Card c : seen) {
            final forge.game.zone.Zone z = game.getZoneOf(c);
            final ZoneType zt = z == null ? null : z.getZoneType();
            for (Trigger t : c.getTriggers()) {
                if (modes.contains(t.getMode()) && t.zonesCheck(z) && pumpSees(t, zt, pay)) {
                    return true;
                }
            }
            if (moves) {
                for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                    if (re.getMode() == forge.game.replacement.ReplacementType.Moved && re.zonesCheck(z) && pumpSees(re, zt, pay)) {
                        return true;
                    }
                }
            }
        }
        final forge.game.trigger.TriggerHandler th = game.getTriggerHandler();
        for (Trigger t : th.getDelayedTriggersView()) {
            if (modes.contains(t.getMode()) && pumpSees(t, null, pay)) {
                return true;
            }
        }
        for (Trigger t : th.getPlayerDefinedDelayedTriggersView().values()) {
            if (modes.contains(t.getMode()) && pumpSees(t, null, pay)) {
                return true;
            }
        }
        return false;
    }

    /**
     * deadPump: the trigger modes that paying {@code sa}'s cost and playing it can fire. SpellAbilityCast always; SpellCast
     * and SpellCastOrCopy for a spell, AbilityCast for an ability; BecomesTarget(Once) when it targets; Discarded(All),
     * Sacrificed(Once), PayLife, LifeLost(All) and Exiled for those payments; the mana payment's own (Taps, TapAll,
     * TapsForMana, ManaAdded, ManaExpend, and discard, sacrifice and life as above); ChangesZone(All) when a card moves.
     */
    static Set<TriggerType> pumpModes(SpellAbility sa, List<SpellAbility> chain, PumpCost pay) {
        final Set<TriggerType> m = java.util.EnumSet.of(TriggerType.SpellAbilityCast);
        if (sa.isSpell()) {
            m.add(TriggerType.SpellCast);
            m.add(TriggerType.SpellCastOrCopy);
        } else {
            m.add(TriggerType.AbilityCast);
        }
        for (SpellAbility s : chain) {
            if (s.usesTargeting()) {
                m.add(TriggerType.BecomesTarget);
                m.add(TriggerType.BecomesTargetOnce);
            }
        }
        if (pay.discard || pay.mana) {
            m.add(TriggerType.Discarded);
            m.add(TriggerType.DiscardedAll);
        }
        if (pay.sacrifice || pay.mana) {
            m.add(TriggerType.Sacrificed);
            m.add(TriggerType.SacrificedOnce);
        }
        if (pay.life || pay.mana) {
            m.add(TriggerType.PayLife);
            m.add(TriggerType.LifeLost);
            m.add(TriggerType.LifeLostAll);
        }
        if (pay.exile) {
            m.add(TriggerType.Exiled);
        }
        if (pay.mana) {
            m.add(TriggerType.Taps);
            m.add(TriggerType.TapAll);
            m.add(TriggerType.TapsForMana);
            m.add(TriggerType.ManaAdded);
            m.add(TriggerType.ManaExpend);
        }
        if (!pay.from.isEmpty()) {
            m.add(TriggerType.ChangesZone);
            m.add(TriggerType.ChangesZoneAll);
        }
        return m;
    }

    /**
     * deadPump: whether trigger or replacement {@code x}, on a card in zone {@code zt} (null = unknown or delayed), could see
     * this payment. Two cases are left out, and only these:
     * <ul>
     * <li>one off the battlefield and the command zone that watches only its own card (ValidCard Self), unless the payment
     * may take cards from that card's zone (a creature's dies or cast trigger in a library does not see a discard);</li>
     * <li>a zone change (a ChangesZone(All) trigger or a Moved replacement) whose Destination is only the battlefield
     * (nothing here enters it: an enters trigger, an enters-tapped replacement), or whose Origin is no zone the payment
     * takes cards from.</li>
     * </ul>
     */
    static boolean pumpSees(forge.game.TriggerReplacementBase x, ZoneType zt, PumpCost pay) {
        if (zt != null && zt != ZoneType.Battlefield && zt != ZoneType.Command && !pay.from.contains(zt)
                && selfOnly(x.getParam("ValidCard"))) {
            return false;
        }
        final boolean zoneChange = x instanceof forge.game.replacement.ReplacementEffect
                || x instanceof Trigger && (((Trigger) x).getMode() == TriggerType.ChangesZone
                        || ((Trigger) x).getMode() == TriggerType.ChangesZoneAll);
        if (!zoneChange) {
            return true;
        }
        final String dest = x.getParam("Destination");
        if (dest != null && onlyBattlefield(dest)) {
            return false;
        }
        final String origin = x.getParam("Origin");
        return origin == null || anyZone(origin, pay.from);
    }

    /** deadPump: a ValidCard that names only the card itself ("Card.Self", "Card.Self+evoked", ...; each comma part). */
    static boolean selfOnly(String valid) {
        if (valid == null || valid.isEmpty()) {
            return false;
        }
        for (String p : valid.split(",")) {
            final int dot = p.indexOf('.');
            final String rest = dot < 0 ? "" : p.substring(dot + 1).trim();
            if (!(rest.equals("Self") || rest.startsWith("Self+"))) {
                return false;
            }
        }
        return true;
    }

    /** deadPump: a zone list that names only the battlefield. */
    static boolean onlyBattlefield(String zones) {
        for (String z : zones.split(",")) {
            if (!"Battlefield".equals(z.trim())) {
                return false;
            }
        }
        return true;
    }

    /** deadPump: a zone list that names any zone of {@code from} (Any, All or an unreadable name count as any zone). */
    static boolean anyZone(String zones, Set<ZoneType> from) {
        for (String z : zones.split(",")) {
            final String n = z.trim();
            if ("Any".equals(n) || "All".equals(n)) {
                return true;
            }
            try {
                final ZoneType t = ZoneType.smartValueOf(n);
                if (t == null || from.contains(t)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * deadPump (review S1 1): {@code c} is a card {@code me} may know of in its own game: a face-up card in a public zone,
     * its own hand, or a card it may look at ({@code CardView.canBeShownTo}); a face-down card only if its face may be shown
     * to it. Used where the predicate runs on the game it was given (tests); the live sites pass {@link #pumpKnownIds}.
     */
    static boolean pumpKnows(Player me, Card c) {
        final forge.game.player.PlayerView v = me.getView();
        return c.getView().canBeShownTo(v) && (!c.isFaceDown() || c.getView().canFaceDownBeShownTo(v));
    }

    /**
     * deadPump (review S1 1): the ids of the cards the seat may know of, read in the live game ({@link #pumpKnows}, plus an
     * opponent's hand card it saw and remembers, as the belief does). The enumeration copy keeps card ids but not the
     * look permissions, so the sites read this before copying.
     */
    private Set<Integer> pumpKnownIds(Game live, Player liveMe) {
        final Set<Integer> ids = new HashSet<>();
        for (Card c : live.getCardsInGame()) {
            if (pumpKnows(liveMe, c) || rememberedHand(live, liveMe, c)) {
                ids.add(c.getId());
            }
        }
        return ids;
    }

    /**
     * deadPump (review S1 2, 3): some card the seat sees reads power, so a power change may matter outside combat damage
     * (an attack restriction by power, lethal damage by power, an end-step trigger counting power, a fling on the
     * battlefield, a mana ability whose amount is its power). Read: each static ability, trigger and replacement effect
     * that works where its card is, each activated ability (mana abilities included, re-review S1) on the battlefield or
     * in the command zone, each spell in a hand the seat sees, and each keyword's
     * text. Any of their parameters, or the SVars those name (an Execute, a SubAbility, an X), counts.
     */
    static boolean pumpReadsPower(List<Card> seen, Player me) {
        final Game game = me.getGame();
        for (Card c : seen) {
            final forge.game.zone.Zone z = game.getZoneOf(c);
            final ZoneType zt = z == null ? null : z.getZoneType();
            for (forge.game.staticability.StaticAbility st : c.getStaticAbilities()) {
                if (st.zonesCheck() && readsPower(st)) {
                    return true;
                }
            }
            for (Trigger t : c.getTriggers()) {
                if (t.zonesCheck(z) && readsPower(t)) {
                    return true;
                }
            }
            for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                if (re.zonesCheck(z) && readsPower(re)) {
                    return true;
                }
            }
            if (zt == ZoneType.Battlefield || zt == ZoneType.Command || zt == ZoneType.Hand) {
                // mana abilities too: one whose amount reads power makes more or less mana after the change
                for (SpellAbility sa : c.getSpellAbilities()) {
                    if (readsPower(sa)) {
                        return true;
                    }
                }
                for (SpellAbility sa : c.getManaAbilities()) {
                    if (readsPower(sa)) {
                        return true;
                    }
                }
            }
            for (forge.game.keyword.KeywordInterface k : c.getKeywords()) {
                final String o = k.getOriginal();
                if (o != null && o.toLowerCase(java.util.Locale.ROOT).contains("power")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** deadPump: parameters that set or change power and do not read it; their values are still read. */
    static final Set<String> POWER_SETTERS = new HashSet<>(Arrays.asList("AddPower", "SetPower", "Power", "TokenPower",
            "FaceDownPower", "BasePower"));

    /** deadPump: a parameter that only describes or hints (its text is never read). */
    static boolean descriptiveParam(String k) {
        return k.endsWith("Description") || k.endsWith("Desc") || k.startsWith("AI") || "TgtPrompt".equals(k);
    }

    /**
     * deadPump: {@code t} reads power: a parameter key names power other than a setter (LethalDamageByPower), or a value or a
     * named SVar does (Creature.powerGTX, Count$CardPower, greatestPower, ...). Unreadably deep SVar chains count.
     */
    static boolean readsPower(forge.game.CardTraitBase t) {
        return paramsMatch(t.getMapParams(), t, new HashSet<>(), LookaheadSearch::powerKey, LookaheadSearch::powerText);
    }

    static boolean powerKey(String k, String v) {
        return !POWER_SETTERS.contains(k) && k.toLowerCase(java.util.Locale.ROOT).contains("power");
    }

    static boolean powerText(String v) {
        return v.toLowerCase(java.util.Locale.ROOT).contains("power");
    }

    /**
     * deadPump: a parameter map (or an SVar's pieces) matches {@code keyTest} on a key and its value, or {@code textTest} on a
     * value; each SVar a value names (a word that is an SVar of {@code t} or its card) is read the same way, once. More than
     * 64 SVars counts as a match.
     */
    static boolean paramsMatch(Map<String, String> params, forge.game.CardTraitBase t, Set<String> svars,
            java.util.function.BiPredicate<String, String> keyTest, java.util.function.Predicate<String> textTest) {
        for (Map.Entry<String, String> e : params.entrySet()) {
            final String k = e.getKey();
            final String v = e.getValue() == null ? "" : e.getValue();
            if (descriptiveParam(k)) {
                continue;
            }
            if (keyTest.test(k, v) || textMatch(v, t, svars, keyTest, textTest)) {
                return true;
            }
        }
        return false;
    }

    static boolean textMatch(String v, forge.game.CardTraitBase t, Set<String> svars,
            java.util.function.BiPredicate<String, String> keyTest, java.util.function.Predicate<String> textTest) {
        if (v.isEmpty()) {
            return false;
        }
        if (textTest.test(v)) {
            return true;
        }
        for (String w : v.split("[^A-Za-z0-9_]+")) {
            if (w.isEmpty() || svars.contains(w) || !t.hasSVar(w)) {
                continue;
            }
            if (svars.size() >= 64) {
                return true;
            }
            svars.add(w);
            final Map<String, String> m = new java.util.LinkedHashMap<>();
            for (String piece : t.getSVar(w).split("\\|")) {
                final int d = piece.indexOf('$');
                if (d < 0) {
                    if (textMatch(piece.trim(), t, svars, keyTest, textTest)) {
                        return true;
                    }
                } else {
                    m.put(piece.substring(0, d).trim(), piece.substring(d + 1).trim());
                }
            }
            if (paramsMatch(m, t, svars, keyTest, textTest)) {
                return true;
            }
        }
        return false;
    }

    /** deadPump: the APIs that can make an attacker this turn (a creature from a permanent, or a tapped creature untapped). */
    static final Set<String> ATTACKER_APIS = new HashSet<>(Arrays.asList("Animate", "AnimateAll", "Untap", "UntapAll"));

    /**
     * deadPump (review S1 4): the active player may get an attacker this turn that cannot attack now. Among its
     * permanents, and in the seat's own hand when the seat is the active player (an opponent's hidden hand is not read): a
     * Vehicle or Spacecraft, a card with Crew or Station, a creature card with haste in that hand, or any ability (spell,
     * activated, static, trigger, replacement, with the SVars it names) that animates (Animate, AnimateAll, an added
     * Creature type), untaps (Untap, UntapAll) or grants haste.
     */
    static boolean pumpAttackersMayAppear(List<Card> seen, Player active, Player me) {
        if (active == null) {
            return true;
        }
        final Game game = me.getGame();
        for (Card c : seen) {
            final forge.game.zone.Zone z = game.getZoneOf(c);
            final ZoneType zt = z == null ? null : z.getZoneType();
            final boolean field = zt == ZoneType.Battlefield && active.equals(c.getController());
            final boolean hand = zt == ZoneType.Hand && active.equals(me) && me.equals(c.getOwner());
            if (!field && !hand) {
                continue;
            }
            if (c.getType().hasSubtype("Vehicle") || c.getType().hasSubtype("Spacecraft")
                    || c.hasKeyword(forge.game.keyword.Keyword.CREW) || c.hasKeyword(forge.game.keyword.Keyword.STATION)
                    || hand && c.isCreature() && c.hasKeyword(forge.game.keyword.Keyword.HASTE)) {
                return true;
            }
            final List<forge.game.CardTraitBase> traits = new ArrayList<>();
            traits.addAll(c.getSpellAbilities());
            traits.addAll(c.getStaticAbilities());
            traits.addAll(c.getTriggers());
            traits.addAll(c.getReplacementEffects());
            for (forge.game.CardTraitBase t : traits) {
                if (paramsMatch(t.getMapParams(), t, new HashSet<>(), LookaheadSearch::attackerKey, LookaheadSearch::hasteText)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean attackerKey(String k, String v) {
        return ("AB".equals(k) || "SP".equals(k) || "DB".equals(k)) && ATTACKER_APIS.contains(v.trim())
                || ("Types".equals(k) || "AddType".equals(k)) && v.contains("Creature");
    }

    static boolean hasteText(String v) {
        return v.contains("Haste");
    }

    // ------------------------------------------------------------------ deadFaceUp (turn-face-up-timing-1118)

    /** deadFaceUp: {@link #deadFaceUpWhy}'s answer for a turn-up that is dead (dropped when ON). */
    static final String DFU_DEAD = "dead";

    /** deadFaceUp: the parameters a "when this is turned face up" trigger may carry (anything else is a condition). */
    static final Set<String> DFU_TRIGGER_PARAMS = new HashSet<>(Arrays.asList("Mode", "ValidCard", "Execute", "TriggerZones",
            "TriggerDescription", "OptionalDecider", "Secondary"));

    /** deadFaceUp: the zones a wasted trigger's target may come from (public zones the seat sees in full). */
    static final Set<ZoneType> DFU_TARGET_ZONES = java.util.EnumSet.of(ZoneType.Battlefield, ZoneType.Stack, ZoneType.Graveyard);

    /** deadFaceUp: keywords that matter only in combat, and the turn-up keywords themselves (condition 2). */
    static final Set<forge.game.keyword.Keyword> DFU_KEYWORDS = java.util.EnumSet.of(forge.game.keyword.Keyword.FLYING,
            forge.game.keyword.Keyword.REACH, forge.game.keyword.Keyword.FIRST_STRIKE, forge.game.keyword.Keyword.DOUBLE_STRIKE,
            forge.game.keyword.Keyword.TRAMPLE, forge.game.keyword.Keyword.VIGILANCE, forge.game.keyword.Keyword.MENACE,
            forge.game.keyword.Keyword.DEATHTOUCH, forge.game.keyword.Keyword.LIFELINK, forge.game.keyword.Keyword.INTIMIDATE,
            forge.game.keyword.Keyword.FEAR, forge.game.keyword.Keyword.SHADOW, forge.game.keyword.Keyword.HORSEMANSHIP,
            forge.game.keyword.Keyword.SKULK, forge.game.keyword.Keyword.LANDWALK, forge.game.keyword.Keyword.MORPH,
            forge.game.keyword.Keyword.MEGAMORPH, forge.game.keyword.Keyword.DISGUISE);

    /** deadFaceUp: the turn-up keywords (their own abilities are the turn-up itself, the face-down cast and megamorph's counter). */
    static final Set<forge.game.keyword.Keyword> DFU_TURN_KEYWORDS = java.util.EnumSet.of(forge.game.keyword.Keyword.MORPH,
            forge.game.keyword.Keyword.MEGAMORPH, forge.game.keyword.Keyword.DISGUISE);

    /**
     * deadFaceUp guard (lane turn-face-up-timing-1118): true for a candidate that turns one of the seat's face-down
     * permanents face up when doing it now gains nothing over doing it at a later priority. See {@link #deadFaceUpWhy}.
     */
    static boolean deadFaceUp(SpellAbility sa, Player me) {
        return DFU_DEAD.equals(deadFaceUpWhy(sa, me, null));
    }

    /**
     * deadFaceUp guard: null when {@code sa} is not a turn-up of a face-down permanent (not this guard's candidate),
     * {@link #DFU_DEAD} when it is dead, otherwise why the guard stands aside. Dead only when all five hold, and anything
     * this check cannot read leaves the candidate to the search:
     * <ol>
     * <li><b>Wasted trigger</b> ("trigger", "untargeted", "targetZone", "target", "filter", "noTrigger"): the face-up card
     * has at least one "when this is turned face up" trigger (ValidCard Self, no condition), and every one targets (its root
     * ability, at least one target) with no possible target now, from the battlefield, the stack and the graveyards only:
     * neither the engine's own target candidates nor any object of a card type the filter names, with every
     * source-dependent restriction ignored ({@link #dfuTypeCandidates}; a filter that is not card types stands aside).
     * Rule 603.3d: such a trigger is removed from the stack, so it does nothing.</li>
     * <li><b>Nothing else on the face-up card needs it face up now</b> ("card", "ability", "keyword"): it is a creature of
     * one face, and besides those triggers and its morph, megamorph or disguise keyword it has only keywords that matter only
     * in combat ({@link #DFU_KEYWORDS}): no static, other trigger, activated or mana ability, replacement or other keyword.
     * </li>
     * <li><b>The seat gets priority again before the body could matter, and before its own untap step</b> ("stack",
     * "combat", "phase", "timing", "untap", "cleanup"): the stack is empty (otherwise the top item resolves before the seat's next
     * priority and the body may matter to it), the permanent is not attacking or blocking, no extra turn or phase is
     * pending, and the next combat declaration or damage it could take part in comes after the seat's next priority: on its
     * own turn, before combat only if it cannot attack this turn (tapped, or summoning sick with no haste source the seat
     * sees), from declare attackers through main phase 2 (not in combat), never the end step (cleanup comes first);
     * on another player's turn, upkeep through beginning of combat and declare blockers through main phase 2 only (never
     * declare attackers: tapped does not prove it cannot block; never the end step: the seat's untap step may come first,
     * and waiting would spend next turn's mana; never untap or cleanup).</li>
     * <li><b>Paying now gains nothing over paying later</b> ("cost", "pool", "watcher", "other", "offBattlefield"): the
     * turn-up cost is mana
     * only (no X), the seat's mana pool is empty, no card the seat sees other than this one has a trigger or replacement on a
     * permanent turning face up, or a battlefield or command-zone ability whose text names face-down or face-up status,
     * morph, manifest, cloak or disguise; and no other battlefield or command-zone object, on either side, has a static or
     * triggered ability, a keyword or a replacement effect (but its own entering), and no delayed trigger is pending
     * ({@link #dfuOtherAbilities}: anything that could read the changed characteristics or the megamorph counter); and no
     * card in a graveyard, exile, the command zone or the seat's own hand has a trigger, static or replacement (but its own
     * entering) active where it is ("offBattlefield", {@link #dfuActiveOffBattlefield}).</li>
     * <li><b>What the seat may know</b>: only its own face-down card (whose face it knows) and public state are read;
     * {@code known} (card ids, {@link #pumpKnownIds}, read in the live game) or {@link #pumpKnows} when null. An opponent's
     * hand or library, or an opponent's face-down card, is never read.</li>
     * </ol>
     * Never the policy's default or Forge's answer (the sites keep them). Reads only: the trigger's ability is copied before
     * its targets are counted.
     */
    static String deadFaceUpWhy(SpellAbility sa, Player me, Set<Integer> known) {
        if (sa == null || me == null || sa.getHostCard() == null || !sa.isTurnFaceUp()) {
            return null;
        }
        try {
            return dfuWhy(sa, sa.getHostCard(), me, known);
        } catch (RuntimeException e) {
            return "error";
        }
    }

    private static String dfuWhy(SpellAbility sa, Card host, Player me, Set<Integer> known) {
        final Game game = me.getGame();
        // condition 2 (the card): the seat's own face-down permanent, which turns to its one original face, a creature
        if (!host.isFaceDown() || !host.isInPlay() || host.isPhasedOut() || !me.equals(host.getController())
                || host.getCurrentStateName() != forge.card.CardStateName.FaceDown || host.hasMergedCard()) {
            return "card";
        }
        for (forge.card.CardStateName s : host.getStates()) {
            if (s != forge.card.CardStateName.Original && s != forge.card.CardStateName.FaceDown) {
                return "card";
            }
        }
        final CardState up = host.getState(forge.card.CardStateName.Original);
        if (up == null || !up.getType().isCreature()) {
            return "card";
        }
        // condition 1: every "when this is turned face up" trigger targets and has no legal target now
        int wasted = 0;
        for (Trigger t : up.getTriggers()) {
            if (dfuKeywordTrait(t)) {
                continue;
            }
            if (t.getMode() != TriggerType.TurnFaceUp || !selfOnly(t.getParam("ValidCard"))) {
                return "ability"; // another trigger (condition 2)
            }
            for (String k : t.getMapParams().keySet()) {
                if (!DFU_TRIGGER_PARAMS.contains(k)) {
                    return "trigger";
                }
            }
            final SpellAbility ex = t.ensureAbility();
            if (ex == null) {
                return "trigger";
            }
            final SpellAbility x = ex.copy(me);
            x.setActivatingPlayer(me);
            if (!x.usesTargeting() || x.getMinTargets() < 1 || x.getTargetRestrictions() == null) {
                return "untargeted";
            }
            final forge.game.spellability.TargetRestrictions tr = x.getTargetRestrictions();
            if (tr.getZone().isEmpty() || !DFU_TARGET_ZONES.containsAll(tr.getZone())) {
                return "targetZone";
            }
            if (tr.getNumCandidates(x) > 0) {
                return "target";
            }
            // review round 2 (S1 2): the engine counted with the face-down source; count again with no source-dependent
            // restriction at all (any object of the filter's card type in the target zones), and stand aside on any
            final int loose = dfuTypeCandidates(tr, game);
            if (loose < 0) {
                return "filter";
            }
            if (loose > 0) {
                return "target";
            }
            wasted++;
        }
        if (wasted == 0) {
            return "noTrigger";
        }
        // condition 2: nothing else on the face-up card
        for (forge.game.staticability.StaticAbility st : up.getStaticAbilities()) {
            if (!dfuKeywordTrait(st)) {
                return "ability";
            }
        }
        for (forge.game.replacement.ReplacementEffect re : up.getReplacementEffects(false)) {
            if (!dfuKeywordTrait(re)) {
                return "ability";
            }
        }
        for (SpellAbility a : up.getSpellAbilities()) {
            // its own casts (from a hand only) and turn-ups are not abilities of the permanent
            if (!(a.isSpell() || a.isTurnFaceUp() || dfuKeywordTrait(a))) {
                return "ability";
            }
        }
        for (forge.game.keyword.KeywordInterface k : up.getIntrinsicKeywords()) {
            if (k.getKeyword() == null || !DFU_KEYWORDS.contains(k.getKeyword())) {
                return "keyword";
            }
        }
        // condition 4 (cost): mana only, no X, nothing floating
        final forge.game.cost.Cost cost = sa.getPayCosts();
        if (cost == null || !cost.isOnlyManaCost() || cost.hasXInAnyCostPart()) {
            return "cost";
        }
        if (!me.getManaPool().isEmpty()) {
            return "pool";
        }
        // condition 3: the seat's next priority comes before the body could matter
        if (!game.getStack().isEmpty()) {
            return "stack";
        }
        final PhaseHandler ph = game.getPhaseHandler();
        final forge.game.phase.PhaseType phase = ph.getPhase();
        final Player active = ph.getPlayerTurn();
        if (phase == null || active == null || ph.hasExtraTurnsOrPhases()) {
            return "phase"; // review round 2: an extra turn may bring the seat's untap step before its next priority
        }
        final forge.game.combat.Combat combat = ph.getCombat();
        if (combat != null && (combat.isAttacking(host) || combat.isBlocking(host))) {
            return "combat";
        }
        final List<Card> seen = new ArrayList<>();
        for (Card c : game.getCardsInGame()) {
            if (c != host && (known == null ? pumpKnows(me, c) : known.contains(c.getId()))) {
                seen.add(c);
            }
        }
        final String timing = dfuTiming(host, me, ph, phase, active, combat, seen);
        if (timing != null) {
            return timing;
        }
        // condition 4 (others): nothing the seat sees reacts to a permanent turning face up or reads face-down status
        if (dfuWatched(seen, game)) {
            return "watcher";
        }
        // review round 2 (S1 3, 4): no other battlefield or command-zone object, on either side, has anything that could read
        // the changed characteristics or the megamorph counter (a static or triggered ability, a keyword, a replacement)
        if (dfuOtherAbilities(host, game)) {
            return "other";
        }
        // review round 3 (S1 1): nor any card in a graveyard, exile (as the seat sees it), the command zone or the seat's own
        // hand with a trigger, static or replacement active in the zone it is in (Flamewake Phoenix in a graveyard)
        return dfuActiveOffBattlefield(host, me, game, known) ? "offBattlefield" : DFU_DEAD;
    }

    /**
     * deadFaceUp: one enumeration's checks. The seat's knowledge is read from the live game only when a turn-up candidate
     * comes up ({@code knownOf}); {@link #finish} adds the tally to the stats and gives the decision's note, or null when no
     * candidate was a turn-up.
     */
    static final class DfuTally {
        private final java.util.function.Supplier<Set<Integer>> knownOf;
        private final java.util.function.BooleanSupplier pendingOf;
        private Set<Integer> known;
        private Boolean pending;
        int checked, dead, defaultDead;
        long nanos;
        final Map<String, Long> aside = new TreeMap<>();

        DfuTally(java.util.function.Supplier<Set<Integer>> knownOf, java.util.function.BooleanSupplier pendingOf) {
            this.knownOf = knownOf;
            this.pendingOf = pendingOf;
        }

        /**
         * {@link #deadFaceUpWhy} for {@code sa} (null when it is not a turn-up), timed and tallied; a dead one stands aside
         * ("pending") when the live game has what the copy leaves out ({@link #dfuLivePending}).
         */
        String check(SpellAbility sa, Player me) {
            if (sa == null || !sa.isTurnFaceUp()) {
                return null;
            }
            final long t0 = System.nanoTime();
            try {
                if (known == null) {
                    known = knownOf.get();
                }
                String why = deadFaceUpWhy(sa, me, known);
                if (DFU_DEAD.equals(why)) {
                    if (pending == null) {
                        pending = pendingOf.getAsBoolean();
                    }
                    if (pending) {
                        why = "pending";
                    }
                }
                if (why != null) {
                    checked++;
                    if (!DFU_DEAD.equals(why)) {
                        aside.merge(why, 1L, Long::sum);
                    }
                }
                return why;
            } finally {
                nanos += System.nanoTime() - t0;
            }
        }

        JsonObject finish(Stats stats, AiFixes.Mode mode) {
            stats.deadFaceUpChecked += checked;
            stats.deadFaceUpNanos += nanos;
            aside.forEach((k, v) -> stats.deadFaceUpWhy.merge(k, v, Long::sum));
            if (checked == 0) {
                return null;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("checked", checked);
            o.addProperty(mode == AiFixes.Mode.ON ? "dropped" : "flagged", dead);
            if (defaultDead > 0) {
                o.addProperty("forgeAnswer", defaultDead); // the default's own turn-up: dead, never dropped
            }
            final JsonObject a = new JsonObject();
            aside.forEach(a::addProperty);
            o.add("aside", a);
            o.addProperty("ms", Math.round(nanos / 1e4) / 100.0);
            return o;
        }
    }

    /**
     * deadFaceUp (review round 2): read in the live game, because the look-ahead's copies leave pending state out
     * ({@link GameCopier#setCopyPending} is off at the live sites): an extra turn or extra phase, or any delayed trigger.
     * All of them come from resolved abilities (public).
     */
    static boolean dfuLivePending(Game live) {
        final forge.game.trigger.TriggerHandler th = live.getTriggerHandler();
        return live.getPhaseHandler().hasExtraTurnsOrPhases() || !th.getDelayedTriggersView().isEmpty()
                || !th.getPlayerDefinedDelayedTriggersView().isEmpty();
    }

    /** deadFaceUp: a trait of a turn-up keyword or of a combat-only keyword ({@link #DFU_KEYWORDS}) of the card itself. */
    static boolean dfuKeywordTrait(forge.game.CardTraitBase t) {
        final forge.game.keyword.KeywordInterface k = t.getKeyword();
        return k != null && k.getKeyword() != null && DFU_KEYWORDS.contains(k.getKeyword());
    }

    /**
     * deadFaceUp, condition 3: null when the seat gets priority again before the face-down permanent {@code host} could
     * take part in a combat declaration or combat damage, else why not ("timing", or "phase" for a step this check does not
     * classify: untap, cleanup).
     */
    static String dfuTiming(Card host, Player me, PhaseHandler ph, forge.game.phase.PhaseType phase, Player active,
            forge.game.combat.Combat combat, List<Card> seen) {
        if (active.equals(me)) {
            switch (phase) {
                case UPKEEP: case DRAW: case MAIN1: case COMBAT_BEGIN:
                    // its own turn before attackers: only if it cannot attack this turn
                    return dfuMayAttack(host, seen, me.getGame()) ? "timing" : null;
                case COMBAT_DECLARE_ATTACKERS: case COMBAT_DECLARE_BLOCKERS: case COMBAT_FIRST_STRIKE_DAMAGE: case COMBAT_DAMAGE:
                case COMBAT_END: case MAIN2:
                    return null; // attackers are declared and it is not attacking
                case END_OF_TURN:
                    // review round 3 (S1 2): always: cleanup (until-end-of-turn effects ending, damage wearing off) comes
                    // before the seat's next priority and may change the body's survival; and if the next turn is the
                    // seat's own, its untap step does too (review round 2, S1 1)
                    return "cleanup";
                default:
                    return "phase";
            }
        }
        switch (phase) {
            case UPKEEP: case DRAW: case MAIN1: case COMBAT_BEGIN:
                return null; // it gets priority again after attackers are declared, before blockers
            case COMBAT_DECLARE_ATTACKERS:
                // review round 2 (S1 5): the next priority comes after blockers, and tapped does not prove it cannot block
                return "timing";
            case COMBAT_DECLARE_BLOCKERS: case COMBAT_FIRST_STRIKE_DAMAGE: case COMBAT_DAMAGE:
            case COMBAT_END: case MAIN2:
                return null; // blockers are declared and it is not blocking
            case END_OF_TURN:
                // review round 2 (S1 1): another player's end step: the seat's untap step may come before its next priority,
                // so waiting spends next turn's mana instead of mana about to untap
                return "untap";
            default:
                return "phase";
        }
    }

    /**
     * deadFaceUp: the face-down permanent may still attack this turn: it is untapped, and it is not summoning sick or some
     * battlefield or command-zone ability the seat sees names haste (the face-up creature might get it).
     */
    static boolean dfuMayAttack(Card host, List<Card> seen, Game game) {
        if (host.isTapped()) {
            return false;
        }
        if (!host.hasSickness()) {
            return true;
        }
        for (Card c : seen) {
            final forge.game.zone.Zone z = game.getZoneOf(c);
            final ZoneType zt = z == null ? null : z.getZoneType();
            if (zt != ZoneType.Battlefield && zt != ZoneType.Command) {
                continue;
            }
            final List<forge.game.CardTraitBase> traits = new ArrayList<>();
            traits.addAll(c.getStaticAbilities());
            traits.addAll(c.getTriggers());
            traits.addAll(c.getSpellAbilities());
            for (forge.game.CardTraitBase t : traits) {
                if (paramsMatch(t.getMapParams(), t, new HashSet<>(), (k, v) -> false, LookaheadSearch::hasteText)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * deadFaceUp, condition 4: some card the seat sees (other than the one turning up) may react to a permanent turning face
     * up or reads face-down status: a TurnFaceUp trigger or replacement that works where its card is and does not watch
     * only its own card, a delayed TurnFaceUp trigger, or a battlefield or command-zone static, trigger, replacement or
     * ability (with the SVars it names) whose text names face-down or face-up status, morph, manifest, cloak or disguise.
     * A face-up card's own morph keyword (its turn-up, its face-down cast, megamorph's counter) watches only itself.
     */
    static boolean dfuWatched(List<Card> seen, Game game) {
        for (Card c : seen) {
            final forge.game.zone.Zone z = game.getZoneOf(c);
            final ZoneType zt = z == null ? null : z.getZoneType();
            for (Trigger t : c.getTriggers()) {
                if (t.getMode() == TriggerType.TurnFaceUp && t.zonesCheck(z) && !selfOnly(t.getParam("ValidCard"))) {
                    return true;
                }
            }
            for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                if (re.getMode() == forge.game.replacement.ReplacementType.TurnFaceUp && re.zonesCheck(z)
                        && !selfOnly(re.getParam("ValidCard"))) {
                    return true;
                }
            }
            if (zt != ZoneType.Battlefield && zt != ZoneType.Command) {
                continue;
            }
            final List<forge.game.CardTraitBase> traits = new ArrayList<>();
            traits.addAll(c.getStaticAbilities());
            for (Trigger t : c.getTriggers()) {
                if (!(t.getMode() == TriggerType.TurnFaceUp && selfOnly(t.getParam("ValidCard")))) {
                    traits.add(t);
                }
            }
            for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                if (!(re.getMode() == forge.game.replacement.ReplacementType.TurnFaceUp && selfOnly(re.getParam("ValidCard")))) {
                    traits.add(re);
                }
            }
            for (SpellAbility a : c.getSpellAbilities()) {
                if (!a.isTurnFaceUp() && !a.isCastFaceDown()) {
                    traits.add(a);
                }
            }
            for (forge.game.CardTraitBase t : traits) {
                if (paramsMatch(t.getMapParams(), t, new HashSet<>(), LookaheadSearch::faceKey, LookaheadSearch::faceText)) {
                    return true;
                }
            }
        }
        final forge.game.trigger.TriggerHandler th = game.getTriggerHandler();
        for (Trigger t : th.getDelayedTriggersView()) {
            if (t.getMode() == TriggerType.TurnFaceUp) {
                return true;
            }
        }
        for (Trigger t : th.getPlayerDefinedDelayedTriggersView().values()) {
            if (t.getMode() == TriggerType.TurnFaceUp) {
                return true;
            }
        }
        return false;
    }

    /** deadFaceUp (review round 2): the card types a source-independent target filter may name (anything else stands aside). */
    static final Set<String> DFU_TARGET_TYPES = new HashSet<>(Arrays.asList("Card", "Permanent", "Creature", "Artifact",
            "Enchantment", "Land", "Planeswalker", "Battle", "Instant", "Sorcery"));

    /**
     * deadFaceUp, condition 1 (review round 2, S1 2): the trigger's possible targets with every source-dependent
     * restriction ignored: each object in its target zones whose card type is one its valid-target filter names, whatever
     * the filter's properties (power, controller, color ...), protection, hexproof, shroud, ward or "can't be the target
     * of". Any object on the stack counts. -1 when the filter is not a list of card types ({@link #DFU_TARGET_TYPES}),
     * e.g. it can target a player: the guard stands aside.
     */
    static int dfuTypeCandidates(forge.game.spellability.TargetRestrictions tr, Game game) {
        final String[] valid = tr.getValidTgts();
        if (valid == null || valid.length == 0 || tr.canTgtPlayer()) {
            return -1;
        }
        final Set<String> types = new HashSet<>();
        for (String v : valid) {
            String t = v == null ? "" : v.trim();
            final int dot = t.indexOf('.');
            if (dot >= 0) {
                t = t.substring(0, dot);
            }
            if (!DFU_TARGET_TYPES.contains(t)) {
                return -1;
            }
            types.add(t);
        }
        int n = 0;
        for (ZoneType z : tr.getZone()) {
            if (z == ZoneType.Stack) {
                n += game.getStack().size();
                continue;
            }
            for (Card c : game.getCardsIn(z)) {
                if (dfuTypeMatch(c, types)) {
                    n++;
                }
            }
        }
        return n;
    }

    static boolean dfuTypeMatch(Card c, Set<String> types) {
        if (types.contains("Card") || types.contains("Permanent")) {
            return true; // any card (a permanent card is any card but an instant or sorcery: count them all)
        }
        final forge.card.CardTypeView t = c.getType();
        return types.contains("Creature") && t.isCreature() || types.contains("Artifact") && t.isArtifact()
                || types.contains("Enchantment") && t.isEnchantment() || types.contains("Land") && t.isLand()
                || types.contains("Planeswalker") && t.isPlaneswalker() || types.contains("Battle") && t.isBattle()
                || types.contains("Instant") && t.isInstant() || types.contains("Sorcery") && t.isSorcery();
    }

    /**
     * deadFaceUp (review round 2, S1 3 and 4): some other battlefield or command-zone object, on either side, could read
     * the face-up card's changed characteristics or its megamorph counter: it has a static or triggered ability, a keyword,
     * or a replacement effect other than one for its own entering the battlefield (a shockland's or a tapped land's, which
     * cannot apply to a card already there); or any delayed trigger is pending. Mana and other activated abilities do not
     * count. A face-down object is read as its face-down state (public). This replaces any finer reading of other cards.
     */
    static boolean dfuOtherAbilities(Card host, Game game) {
        for (ZoneType zt : new ZoneType[] {ZoneType.Battlefield, ZoneType.Command}) {
            for (Card c : game.getCardsIn(zt)) {
                if (c == host || c.getId() == host.getId()) {
                    continue;
                }
                if (!c.getStaticAbilities().isEmpty() || !c.getTriggers().isEmpty() || !c.getKeywords().isEmpty()) {
                    return true;
                }
                for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                    if (!dfuOwnEntry(re)) {
                        return true;
                    }
                }
            }
        }
        final forge.game.trigger.TriggerHandler th = game.getTriggerHandler();
        return !th.getDelayedTriggersView().isEmpty() || !th.getPlayerDefinedDelayedTriggersView().isEmpty();
    }

    /**
     * deadFaceUp (review round 3, S1 1): some card other than {@code host} in a graveyard, in exile (only a card the seat may
     * see: {@code known}, or {@link #pumpKnows} when null), in the command zone, or in the seat's own hand, has a trigger,
     * a static ability or a replacement effect that is active in the zone it is in, by Forge's own checks
     * ({@code TriggerZones} / {@code ActiveZones}: {@link forge.game.TriggerReplacementBase#zonesCheck}, which counts one
     * without a zone list as active everywhere; {@code EffectZone}: {@link forge.game.staticability.StaticAbility#zonesCheck},
     * which counts a characteristic-defining one as active everywhere). An opponent's hand and the libraries are not read.
     * Review round 3b: a replacement for the card's own entering the battlefield ({@link #dfuOwnEntry}) is exempt, as on
     * the battlefield: it changes only how that card enters, and off the battlefield the card can enter before the seat's
     * next priority only through a trigger (which stands aside) or a spell or ability on the stack (the seat gets priority
     * first, and a non-empty stack stands aside).
     */
    static boolean dfuActiveOffBattlefield(Card host, Player me, Game game, Set<Integer> known) {
        for (Card c : game.getCardsInGame()) {
            if (c == host || c.getId() == host.getId()) {
                continue;
            }
            final forge.game.zone.Zone z = game.getZoneOf(c);
            if (z == null) {
                continue;
            }
            final boolean read;
            switch (z.getZoneType()) {
                case Graveyard: case Command:
                    read = true;
                    break;
                case Exile:
                    read = known == null ? pumpKnows(me, c) : known.contains(c.getId());
                    break;
                case Hand:
                    read = me.equals(z.getPlayer());
                    break;
                default:
                    read = false;
            }
            if (!read) {
                continue;
            }
            for (Trigger t : c.getTriggers()) {
                if (t.zonesCheck(z)) {
                    return true;
                }
            }
            for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                // review round 3b: a replacement for the card's own entering the battlefield is exempt here too
                if (re.zonesCheck(z) && !dfuOwnEntry(re)) {
                    return true;
                }
            }
            for (forge.game.staticability.StaticAbility st : c.getStaticAbilities()) {
                if (st.zonesCheck()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * deadFaceUp (review round 3b): the only parameters an exempt own-entry replacement may have. Any other parameter (a
     * condition such as {@code CheckSVar}, {@code Origin}, {@code ValidCause}, {@code Optional}, {@code Layer},
     * {@code ActiveZones}, ...) is not classified, so the replacement is not exempt.
     */
    static final Set<String> DFU_OWN_ENTRY_PARAMS = new HashSet<>(Arrays.asList(
            "Event", "ValidCard", "Destination", "ReplaceWith", "ReplacementResult", "Description", "Secondary"));

    /**
     * deadFaceUp: a replacement for its own card entering the battlefield only: event Moved, {@code ValidCard$ Card.Self},
     * {@code Destination$ Battlefield}, and since review round 3b no parameter outside {@link #DFU_OWN_ENTRY_PARAMS}. That
     * includes the keyword-generated ones ({@code etbCounter}: loyalty, +1/+1 counters, ...) when they carry no condition.
     */
    static boolean dfuOwnEntry(forge.game.replacement.ReplacementEffect re) {
        if (re.getMode() != forge.game.replacement.ReplacementType.Moved || !"Card.Self".equals(re.getParam("ValidCard"))
                || !"Battlefield".equals(re.getParam("Destination"))) {
            return false;
        }
        for (String k : re.getMapParams().keySet()) {
            if (!DFU_OWN_ENTRY_PARAMS.contains(k)) {
                return false;
            }
        }
        return true;
    }

    /** deadFaceUp: words that name face-down or face-up status, or a way to be face down. */
    static final String[] DFU_FACE_WORDS = {"facedown", "faceup", "face down", "face up", "morph", "manifest", "cloak",
            "disguise", "turnface"};

    static boolean faceText(String v) {
        final String l = v.toLowerCase(java.util.Locale.ROOT);
        for (String w : DFU_FACE_WORDS) {
            if (l.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static boolean faceKey(String k, String v) {
        return faceText(k);
    }

    /**
     * deadEtb guard: true for a spell that would put a non-creature permanent onto the battlefield whose enters-the-
     * battlefield triggers (ChangesZone to the battlefield, ValidCard Card.Self) all need at least one target and have
     * no legal target now, so the card would do nothing on entering (Portable Hole, Oblivion Ring, Banishing Light ...
     * with nothing to exile). A card with no such trigger, any trigger this check cannot read (kicker-only, another
     * ValidCard, a condition that is not met), or any trigger with a legal target or no targeting is not dead.
     * Creatures are left to the search: their body is scored as a creature, not at the flat permanent value.
     * Reads only (the trigger's ability is copied before its targets are counted).
     */
    static boolean deadEtbPermanent(SpellAbility sa, Player me) {
        if (sa == null || !sa.isSpell() || sa.isLandAbility()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        final CardState state = sa.getCardState() != null ? sa.getCardState() : host.getCurrentState();
        if (!state.getType().isPermanent() || state.getType().isCreature() || state.getType().isLand()) {
            return false;
        }
        final Game game = me.getGame();
        int dead = 0;
        for (final Trigger tr : state.getTriggers()) {
            if (tr.getMode() != TriggerType.ChangesZone || !ZoneType.Battlefield.toString().equals(tr.getParam("Destination"))) {
                continue;
            }
            final String valid = tr.getParam("ValidCard");
            if (valid == null || !valid.contains("Self")) {
                continue;
            }
            if (!"Card.Self".equals(valid) || tr.hasParam("Origin") && !"Any".equals(tr.getParam("Origin"))
                    && !"Hand".equals(tr.getParam("Origin")) && !"Stack".equals(tr.getParam("Origin"))) {
                return false;
            }
            if (!tr.requirementsCheck(game)) {
                return false;
            }
            final SpellAbility ex = tr.ensureAbility();
            if (ex == null) {
                return false;
            }
            final SpellAbility exSA = ex.copy(me);
            if (!exSA.usesTargeting() || exSA.getMinTargets() < 1) {
                return false;
            }
            if (exSA.getTargetRestrictions().getNumCandidates(exSA) >= exSA.getMinTargets()) {
                return false;
            }
            dead++;
        }
        return dead > 0;
    }

    List<SpellAbility> mapToLive(PlayerControllerAi ctrl, Game live, Player me, Cand c) {
        if (c.pass) {
            return null;
        }
        SpellAbility sa = locate(live, me, c);
        if (sa == null) {
            return null;
        }
        sa.setActivatingPlayer(me);
        if (!c.land) {
            AiPlayDecision dec = ctrl.getAi().canPlaySa(sa);
            if (c.tgt != null) {
                if (!applyTarget(sa, c.tgt, live)) {
                    return null;
                }
            } else if (dec != AiPlayDecision.WillPlay && needsTargets(sa)) {
                return null;
            }
        }
        List<SpellAbility> l = new ArrayList<>();
        l.add(sa);
        return l;
    }

    // ------------------------------------------------------------------ rollout

    static final class Rollout {
        boolean ok = true;
        boolean capped = false;
        /** Stopped by the decision's wall budget (never started, or stopped between main-loop steps). */
        boolean aborted = false;
        double value;
        /** C5: the seat's ForgeState at a non-terminal horizon (null if terminal or no model). */
        JsonObject leaf;
        /** C5: P(seat wins) of a terminal play-out (1, 0, or 0.5 for a draw); NaN if not terminal. */
        double pTerminal = Double.NaN;
        int steps;
        String fingerprint;
        long copyNanos, rolloutNanos;
        /** Dedup: the state key after every main-loop step (null when keys are off), and the last one. */
        List<String> keys;
        String endKey;
        long keysComputed, keyNanos;
        /** Dedup: the earlier candidate whose value this play-out took (-1 = played out), and the steps that saved. */
        int dedupFrom = -1;
        int dedupStepsSaved;
        /** Dedup verify: the earlier candidate this play-out would have taken its value from, and at which step. */
        int wouldDedupFrom = -1;
        int wouldDedupStep = -1;
        /** Reuse: the searching seat's priority points (null when reuse is off). */
        List<TrajPoint> trajectory;
        /** Reuse: steps of the underlying play-out from its own start; for a reused value, the steps it spared. */
        int totalSteps;
        int reusedSteps;
        boolean reused;
        /** S1 value leaf: the hooks' payload for a non-terminal play-out (null otherwise), and whether it came late. */
        Object leafPayload;
        boolean leafLate;
        /** bvw-step2-fork-1027 (S2T, leafOpp): the opponent's own leaf frame at the same moment, or null. */
        Object leafPayloadOpp;
        /** solver-distill-cc-1109: the leaf's reach ({@link SearchHooks#leafReach}), or null. */
        double[] leafReach;
        /** S1 policy play-out: why its seat could not play it (the play-out failed), or null. */
        String seatFailure;
        /** Lane copy-fidelity-1010: a failure's class when the play-out itself knows it ({@link #failureClass}), or null. */
        String failClass;
        /** Lane copy-fidelity-1010: a copy failure's detail (the copy's exception, or why the candidate was not found). */
        String failDetail;
        /** Lane search-bones-1015 (S-b): the plan's fate in this play-out ({@link FirstAction#planState}). */
        int planState;
    }

    /**
     * Lane copy-fidelity-1010, level 2: the play-out counters of a decision's first pass, restored when the decision is
     * searched again on level-1 copies (so every count is the final pass's; the discarded play-outs are counted apart).
     */
    static final class StatsMark {
        final long rollouts, steps, rolloutFailures, rolloutsAborted, rolloutCapped, leafLate, playoutSeatFailures,
                givenMacroDecisions, givenMacros;
        final long[] playoutFailures;
        final Map<String, Long> playoutFailureCands;
        final String playoutLastFailure, playoutLastCopyFailure;

        StatsMark(Stats s) {
            rollouts = s.rollouts;
            steps = s.steps;
            rolloutFailures = s.rolloutFailures;
            rolloutsAborted = s.rolloutsAborted;
            rolloutCapped = s.rolloutCapped;
            leafLate = s.leafLate;
            playoutSeatFailures = s.playoutSeatFailures;
            givenMacroDecisions = s.givenMacroDecisions;
            givenMacros = s.givenMacros;
            playoutFailures = s.playoutFailures.clone();
            playoutFailureCands = new TreeMap<>(s.playoutFailureCands);
            playoutLastFailure = s.playoutLastFailure;
            playoutLastCopyFailure = s.playoutLastCopyFailure;
        }

        void restore(Stats s) {
            s.fidelityRedoRollouts += s.rollouts - rollouts;
            s.rollouts = rollouts;
            s.steps = steps;
            s.rolloutFailures = rolloutFailures;
            s.rolloutsAborted = rolloutsAborted;
            s.rolloutCapped = rolloutCapped;
            s.leafLate = leafLate;
            s.playoutSeatFailures = playoutSeatFailures;
            s.givenMacroDecisions = givenMacroDecisions;
            s.givenMacros = givenMacros;
            System.arraycopy(playoutFailures, 0, s.playoutFailures, 0, playoutFailures.length);
            s.playoutFailureCands.clear();
            s.playoutFailureCands.putAll(playoutFailureCands);
            s.playoutLastFailure = playoutLastFailure;
            s.playoutLastCopyFailure = playoutLastCopyFailure;
        }
    }

    /**
     * Lane copy-fidelity-1010: some searched candidate's play-out failed because the candidate was missing from its copy
     * (not in the copy's menu, not found in the copy) or the copy itself could not be made.
     */
    static boolean candidateMissing(Rollout[][] outs) {
        for (Rollout[] row : outs) {
            for (Rollout r : row) {
                if (r != null && !r.ok) {
                    final String c = failureClass(r);
                    if ("candidate_not_in_menu".equals(c) || "candidate_not_in_copy".equals(c) || "copy_error".equals(c)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Lane copy-fidelity-1010, level 2: this decision's second pass, on level-1 copies (decision thread only). */
    private volatile boolean forceFidelity = false;

    /** Copy fidelity: the current pass's copies carry the turn state (level 1, or level 2's second pass). */
    public boolean fidelityPass() {
        return cfg.copyFidelity == 1 || forceFidelity;
    }

    /** Lane copy-fidelity-1010: the class of a failed or stopped play-out ({@link Stats#PLAYOUT_FAILURE_CLASSES}). */
    static String failureClass(Rollout r) {
        if (r.aborted) {
            return "budget";
        }
        if (r.failClass != null) {
            return r.failClass;
        }
        final String f = r.seatFailure;
        if (f != null) {
            if (f.contains("the searched candidate is not in the play-out's menu")) {
                return "candidate_not_in_menu";
            }
            if (f.equals("max_decisions")) {
                return "max_decisions";
            }
            if (f.equals("cpu_cap")) {
                return "budget";
            }
        }
        return "other";
    }

    /** Lane copy-fidelity-1010: a candidate's name in the failure census: "kind host-card", or "pass". */
    static String failureName(Cand c) {
        if (c == null) {
            return null;
        }
        if (c.pass) {
            return "pass";
        }
        final int i = c.label.indexOf(" :: ");
        String n = c.kind + " " + (i >= 0 ? c.label.substring(0, i) : c.label);
        if (c.macro != null) {
            n += " [macro]";
        } else if (c.tgt != null) {
            n += " [target variant]";
        }
        return n;
    }

    /**
     * Forge AI for a play-out, with a loop breaker: after {@link #WINDOW_CAP} actions inside one
     * priority window (same turn, phase and stack size) it passes. Forge's own guard is 999
     * iterations of a full-game LKI copy each; a copy in which an AI action keeps failing to go on
     * the stack (seen: a planeswalker ability whose target is lost in the copy) otherwise spends
     * minutes in one play-out.
     */
    static class RolloutAi extends PlayerControllerAi {
        static final int WINDOW_CAP = 25;
        private String window = "";
        private int actions = 0;
        int breaks = 0;

        RolloutAi(Game g, Player p, forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        /** This controller's own state, for {@link PlayoutKeys#stateKey}. */
        String loopState() {
            return " w=" + window + " a=" + actions + " b=" + breaks;
        }

        protected List<SpellAbility> capped(List<SpellAbility> chosen) {
            final Game g = getGame();
            final String w = g.getPhaseHandler().getTurn() + ":" + g.getPhaseHandler().getPhase() + ":" + g.getStack().size();
            if (!w.equals(window)) {
                window = w;
                actions = 0;
            }
            if (chosen != null && ++actions > WINDOW_CAP) {
                breaks++;
                return null;
            }
            return chosen;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return capped(super.chooseSpellAbilityToPlay());
        }
    }

    /** Plays the first action it was given, then is Forge AI (with the play-out loop breaker). */
    static final class ScriptedFirst extends RolloutAi {
        private List<SpellAbility> first;
        private boolean used = false;
        /** Reuse probe: where to record this seat's priority points, and the play-out's step counter. */
        List<TrajPoint> trajectory;
        int[] stepRef;
        /** S1 value leaf: told whenever this seat gets priority (null = off). */
        LeafProbe probe;

        ScriptedFirst(Game g, Player p, forge.LobbyPlayer lp, List<SpellAbility> first) {
            super(g, p, lp);
            this.first = first;
        }

        @Override
        String loopState() {
            if (!used) {
                // Not reached by keys (they are taken after a step, and the first step consumes the scripted action).
                return super.loopState() + " unused" + System.identityHashCode(this);
            }
            return super.loopState() + " used";
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (probe != null) {
                probe.onPriority(getGame(), getPlayer());
            }
            if (!used) {
                used = true;
                return first;
            }
            if (trajectory == null) {
                return super.chooseSpellAbilityToPlay();
            }
            if (!getGame().getStack().isEmpty()) {
                return super.chooseSpellAbilityToPlay();
            }
            // The world as it stands before this seat's Forge AI chooses (hidden cards, stream), then the choice.
            final long sig = sigHash(PlayoutKeys.observable(getGame(), getPlayer()));
            final TrajPoint pre = trajPoint(getGame(), getPlayer(), null, stepRef[0], sig);
            final List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
            final SpellAbility sa = chosen == null || chosen.isEmpty() ? null : chosen.get(0);
            trajectory.add(new TrajPoint(sig, new Cand(sa, false).key(), pre.step, pre.myLib, pre.oppIds, pre.oppHand, pre.oppLib, pre.rng));
            return chosen;
        }
    }

    public static final class TurnWatch {
        final int turn;
        volatile boolean reached = false;
        final Player fpFor;
        String fingerprint;

        TurnWatch(int turn, Player fpFor) {
            this.turn = turn;
            this.fpFor = fpFor;
        }

        @Subscribe
        public void on(GameEventTurnBegan e) {
            if (e.turnNumber() >= turn && !reached) {
                reached = true;
                if (fpFor != null) {
                    fingerprint = LookaheadSearch.fingerprint(fpFor.getGame());
                }
            }
        }
    }

    /** A play-out's copy, made on the decision thread; the play-out itself may run on a pool thread. */
    static final class Prepared {
        Game g;
        Player me;
        List<SpellAbility> first;
        boolean failed;
        Throwable error;
        long copyNanos;
        /** C1: with spells on the stack, the copied player who keeps "first priority" (null otherwise). */
        Player firstPriority;
        /** The copy's scopes (ids, AI cache, RNG), installed on whichever thread plays it out. */
        Object ids, cache;
        Random rnd;
        /** Diagnostics only (lookahead.stress): one entry per main-loop step, the position and the new log lines. */
        List<String> stepLog;
        /** Reuse probe: where the searching seat's priority points go (null = off). */
        List<TrajPoint> trajectory;
        /** Tutor ranking: the card id the replayed search must take (-1 = not a tutor play-out). */
        int forcedId = -1;
        /** Tutor ranking: a triggered search's ability rebuilt in the copy (null = the search is on the copy's stack). */
        SpellAbility trigSa;
        /** S1: the hooks' per-copy context; a policy play-out's first action and seed (null / 0 otherwise). */
        Object hookCtx;
        FirstAction firstAction;
        long playoutSeed;
        /** Lane sc-wallguard-1009: the wall budget of the decision this copy was made for (null = none). */
        Budget budget;
        /** Lane copy-fidelity-1010: why the candidate could not be found in the copy (diagnostics), or null. */
        String missWhy;
        /**
         * Lane block-search-1019: the copy was rewound to the end of declare attackers (a block candidate): its next
         * main-loop step enters declare blockers, so priority is not given to the seat.
         */
        boolean rewound;
        /**
         * bvw-step2-fork-1027 (S2T, an OPR roll-out; 0 / false otherwise = the config's): turns past the start (the cap),
         * main-loop steps at most, and no value leaf (the roll-out plays to the end of the game or its cap).
         */
        int capTurns, capSteps;
        boolean noLeaf;
    }

    Rollout rollout(Game live, Player liveMe, Cand c, SpellAbility defSa, long worldSeed, boolean resample, Boolean wantFp) {
        return play(prepare(live, liveMe, c, defSa, worldSeed, resample, null), wantFp);
    }

    /**
     * Copy the live game for one play-out (and resample the world, and map the candidate into the copy).
     * Everything that READS the live game happens here, on the decision thread, in a fixed order: many of
     * Forge's "getters" build lists and views lazily (they write), and when copies were made on pool threads
     * the order in which workers reached the live game varied with load, so a threaded game could differ from
     * its replay (A8T audit 39/48, all on the loaded host).
     */
    Prepared prepare(Game live, Player liveMe, Cand c, SpellAbility defSa, long worldSeed, boolean resample, TrajPoint carried) {
        final Prepared p = new Prepared();
        p.budget = budget;
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(worldSeed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            long a = System.nanoTime();
            synchronized (live) {
                final GameCopier copier = copierOf(live);
                copier.setCopyStack(cfg.stack);
                p.g = copier.makeCopy();
                p.me = (Player) copier.find(liveMe);
                if (cfg.stack && !live.getStack().isEmpty()) {
                    // With spells on the stack, the copy must resolve them as the live game would: the player who acted
                    // last keeps "first priority", so our pass lets the top spell resolve.
                    final Player lf = firstPriority(live.getPhaseHandler());
                    p.firstPriority = lf == null ? null : (Player) copier.find(lf);
                }
                if (carried != null) {
                    // A carried world: its hidden cards where its kept play-out had them, its play-out stream from there.
                    if (!transplant(p.g, p.me, carried)) {
                        throw new IllegalStateException("carried world does not fit the copy");
                    }
                    MyRandom.setThreadRandom(PlayoutKeys.TrackedRandom.ofSnapshot(carried.rng));
                } else {
                    final int wm = worldMode;
                    if (wm != WORLD_BELIEF) {
                        // bvw-step2-fork-1027 (S2T: the T re-play, OPR): a TRUE world, the real position's hands kept,
                        // every library shuffled below its known top. Its hidden cards only seed this starting state.
                        trueShuffle(live, liveMe, p.g, p.me, new Random(mix(worldSeed, 2)));
                        if (wm == WORLD_OPR) {
                            p.capTurns = oprTurns;
                            p.capSteps = oprSteps;
                            p.noLeaf = true;
                        }
                    } else if (resample) {
                        redraw(live, liveMe, p.g, p.me, new Random(mix(worldSeed, 2)));
                        if (ownKnownTop > 0) {
                            // search-bones-1015: the top of our library we placed ourselves (pile memory) stays in place
                            restoreOwnTop(liveMe, p.me, ownKnownTop);
                        }
                        if (beliefWorldDecks()) {
                            // review #123 S1-1: the opponent's registered decklist is a hidden card list too
                            worldDecks(p.g, p.me);
                        }
                    }
                    MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(worldSeed, 3)));
                }
                if (c.block != null) {
                    // block-search-1019: a block candidate's copy resumes at the end of declare attackers
                    rewindToBlocks(p.g, p.me);
                    p.rewound = true;
                }
                if (c.attack != null) {
                    // attack-holdback-1118: an attack candidate's copy resumes at the end of beginning of combat
                    rewindToAttacks(p.g, p.me);
                    p.rewound = true;
                }
                if (hookLeaf || hookPlayout) {
                    // S1: the hooks' per-copy context (a seat-knowledge tracker forked into the copy, attached to it)
                    p.hookCtx = hooks.onCopy(p.g, p.me);
                }
                if (hookPlayout) {
                    // S1 policy play-out: the play-out seat takes the candidate itself (and chooses its targets); here
                    // only that the candidate exists in the copy. The seed is the world's: common random numbers.
                    p.firstAction = new FirstAction(c);
                    p.firstAction.budget = p.budget;
                    // step3-playout-fixes-1028 (replayMayPlayLoose): a re-play's level-0 copy matches may-play loosely too
                    final boolean unique = !fidelityPass() && cfg.replayMayPlayLoose && replaying;
                    final boolean loose = fidelityPass() || unique;
                    p.firstAction.mayPlayLoose = loose;
                    p.firstAction.mayPlayUnique = unique;
                    p.firstAction.oprRollout = p.noLeaf;
                    p.playoutSeed = mix(worldSeed, 5);
                    if (!c.pass && c.plan == null && c.block == null && c.attack == null
                            && locate(p.g, p.me, c, loose, unique) == null) {
                        p.failed = true;
                        p.missWhy = locateMiss(p.g, p.me, c);
                    }
                } else if (c.block != null) {
                    // block-search-1019: block candidates are searched with policy play-outs only
                    p.failed = true;
                    p.missWhy = "a block candidate needs policy play-outs";
                } else if (c.attack != null) {
                    // attack-holdback-1118: so are attack candidates
                    p.failed = true;
                    p.missWhy = "an attack candidate needs policy play-outs";
                } else if (!c.pass) {
                    SpellAbility sa = prepare(p.g, p.me, c, defSa, copier);
                    if (sa == null) {
                        p.failed = true;
                        p.missWhy = locateMiss(p.g, p.me, c);
                    } else {
                        p.first = new ArrayList<>();
                        p.first.add(sa);
                    }
                }
            }
            p.copyNanos = System.nanoTime() - a;
        } catch (RuntimeException | StackOverflowError e) {
            p.failed = true;
            p.error = e;
        } finally {
            p.ids = forge.util.IdScope.capture();
            p.cache = AiCache.captureScope();
            p.rnd = MyRandom.getThreadRandom();
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
        return p;
    }

    /** A copier of the live game for this search: carries until-EOT commands when copyEot is ON (lane misplays-1005). */
    GameCopier copierOf(Game live) {
        final GameCopier c = new GameCopier(live, true);
        if (cfg.copyTurnLimits) c.setTurnLimits(true);
        if (cfg.offBattlefieldLimits) c.setOffBattlefieldLimits(true);
        if (cfg.historyLki) c.setHistoryLki(true);
        if (cfg.copyBackFace) c.setCopyBackFace(true);
        if (cfg.copyDungeon) c.setCopyDungeon(true); // sdcc-copycast-1114
        if (cfg.copyStaleRefs) c.setCopyStaleRefs(true); // sdcc-copycast-1114
        if (cfg.copyTriggers) c.setCopyTriggers(true); // sdcc-copycast-1114
        if (cfg.stackAbilityCopies) c.setStackAbilityCopies(true);
        if (cfg.stackHistory) c.setStackHistory(true);
        if (cfg.copyEot == AiFixes.Mode.ON) {
            c.setCopyUntilEot(true);
        }
        if (cfg.copyFidelity == 1 || forceFidelity) {
            c.setCopyFidelity(1);
        }
        return c;
    }

    /** Play a prepared copy out to the horizon, on the calling thread, inside the copy's own scopes. */
    Rollout play(Prepared p, Boolean wantFp) {
        return play(p, wantFp, null, -1);
    }

    /**
     * As {@link #play(Prepared, Boolean)}; with a memo (dedup or dedup verify), a state key is taken after every main-loop
     * step, and a position an earlier candidate of this world already passed through ends the play-out with that
     * candidate's value (verify mode: is only recorded).
     */
    Rollout play(Prepared p, Boolean wantFp, WorldMemo memo, int cand) {
        final Rollout r = new Rollout();
        r.trajectory = p.trajectory;
        if (p.failed) {
            r.ok = false;
            r.value = Double.NEGATIVE_INFINITY;
            r.failClass = p.error != null ? "copy_error" : "candidate_not_in_copy";
            r.failDetail = p.error != null ? String.valueOf(p.error) : p.missWhy;
            if (p.error != null) {
                System.err.println("[lookahead] rollout failed: " + p.error);
                if (FAILURE_TRACES.getAndIncrement() < 20) {
                    p.error.printStackTrace();
                }
            }
            return r;
        }
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(p.rnd);
        AiCache.installScope(p.cache);
        forge.util.IdScope.install(p.ids);
        try {
            final Game g = p.g;
            final Player me = p.me;
            r.copyNanos = p.copyNanos;
            // S1 (null unless the hooks are set): the value-leaf probe, and a policy-piloted searching seat
            // search-bones-1015: a recording play-out (S-b's sampled plans) has no leaf and ends with our turn
            final boolean recording = p.firstAction != null && p.firstAction.recorder != null;
            // bvw-step2-fork-1027: an OPR roll-out (noLeaf) plays to the end of the game or its cap, without a leaf
            final LeafProbe probe = hookLeaf && !recording && !p.noLeaf ? new LeafProbe(hooks, p.hookCtx) : null;
            if (probe != null) {
                probe.budget = p.budget; // reach-verify-1115
            }
            if (probe != null && cfg.leafOpp && me.getOpponents().size() == 1) {
                probe.opp = me.getOpponents().get(0); // S2T: the opponent's own frame at the leaf too
            }
            List<PlayoutSeat> oppSeats = null;
            PlayoutSeat seat = null;
            final int[] stepRef = new int[1];
            if (hookPlayout) {
                seat = hooks.playoutSeat(p.hookCtx, g, me, p.firstAction, p.playoutSeed, probe);
                me.dangerouslySetController(seat.controller());
            } else {
                final ScriptedFirst sf = new ScriptedFirst(g, me, me.getController().getLobbyPlayer(), p.first);
                sf.trajectory = p.trajectory;
                sf.stepRef = stepRef;
                sf.probe = probe;
                me.dangerouslySetController(sf);
            }
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    if (cfg.opponentPolicy) {
                        // bvw-step2-fork-1027 (S2T): the opponent is the same policy seat (its own observation only)
                        final PlayoutSeat os = hookPlayout ? hooks.opponentSeat(p.hookCtx, g, o, p.playoutSeed, p.firstAction) : null;
                        if (os == null) {
                            throw new IllegalStateException("S2T: no policy seat for the play-out's opponent");
                        }
                        o.dangerouslySetController(os.controller());
                        if (oppSeats == null) {
                            oppSeats = new ArrayList<>(1);
                        }
                        oppSeats.add(os);
                    } else {
                        o.dangerouslySetController(new RolloutAi(g, o, o.getController().getLobbyPlayer()));
                    }
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            if (!p.rewound) {
                givePriority(ph, me);
                if (p.firstPriority != null) {
                    setFirstPriority(ph, p.firstPriority);
                }
            }
            // line-adoption-1107: planHorizonTurn 0 unless plans.lineHorizon
            final TurnWatch watch = new TurnWatch(recording ? ph.getTurn() + 1 : Math.max(ph.getTurn()
                    + (p.capTurns > 0 ? p.capTurns : cfg.horizonTurns), planHorizonTurn), Boolean.TRUE.equals(wantFp) ? me : null);
            final int maxSteps = p.capSteps > 0 ? p.capSteps : cfg.maxSteps;
            g.subscribeToEvents(watch);
            if (probe != null) {
                probe.watch = watch;
            }
            long b = System.nanoTime();
            int steps = 0;
            int extra = 0;
            final List<String> sl = p.stepLog;
            int logSeen = sl == null ? 0 : g.getGameLog().getAllEntries().size();
            if (memo != null) {
                r.keys = new ArrayList<>();
            }
            WorldMemo.Entry hit = null;
            // search-bones-1015 (d): the opponent check of this play-out (once, at their first main-phase priority)
            final OppCheck oc = recording ? null : oppCheck;
            boolean oppDone = oc == null, oppWon = false;
            // Without a probe: to the start of the horizon turn (K8). With one: on to the seat's first priority in it,
            // at most leafExtraSteps main-loop steps past its start.
            while (!g.isGameOver() && !(probe == null ? watch.reached : probe.done || watch.reached && extra >= cfg.leafExtraSteps)
                    && steps < maxSteps) {
                if (p.budget != null && p.budget.over()) {
                    r.aborted = true;
                    break;
                }
                final java.util.function.Consumer<Game> hook = playoutStepHook;
                if (hook != null) {
                    hook.accept(g);
                }
                if (!oppDone && !ph.isPlayerTurn(me) && g.getStack().isEmpty()
                        && (ph.is(forge.game.phase.PhaseType.MAIN1) || ph.is(forge.game.phase.PhaseType.MAIN2))
                        && ph.getPriorityPlayer() != null && ph.getPriorityPlayer() != me) {
                    oppDone = true;
                    oppChecks.incrementAndGet();
                    if (oc.theyWin(g, me)) {
                        oppWon = true;
                        oppLosses.incrementAndGet();
                        break;
                    }
                }
                ph.mainLoopStep();
                steps++;
                if (probe != null && watch.reached) {
                    extra++;
                }
                stepRef[0] = steps;
                if (memo != null && (cfg.dedupSteps <= 0 || steps <= cfg.dedupSteps)) {
                    final long ka = System.nanoTime();
                    final String key = PlayoutKeys.stateKey(g);
                    r.keyNanos += System.nanoTime() - ka;
                    r.keysComputed++;
                    r.keys.add(key);
                    if (hit == null && r.wouldDedupFrom < 0) {
                        final WorldMemo.Entry e = memo.match(key, steps, cfg.maxSteps);
                        if (e != null) {
                            if (cfg.dedupVerify) {
                                r.wouldDedupFrom = e.cand;
                                r.wouldDedupStep = steps;
                                stats.dedupWould(e.remaining, steps);
                            } else {
                                hit = e;
                                break;
                            }
                        }
                    }
                }
                if (sl != null) {
                    final List<forge.game.GameLogEntry> all = g.getGameLog().getAllEntries();
                    final StringBuilder e = new StringBuilder(fingerprint(g)).append("RNG ").append(peekSeed(MyRandom.getRandom())).append("\nLOG");
                    for (int i = logSeen; i < all.size(); i++) {
                        e.append(" || ").append(all.get(i).message());
                    }
                    logSeen = all.size();
                    sl.add(e.toString());
                }
            }
            r.rolloutNanos = System.nanoTime() - b;
            r.steps = steps;
            r.totalSteps = steps + (hit == null ? 0 : hit.remaining);
            if (!r.aborted && seat != null && p.budget != null && p.budget.over() && seat.failure() != null) {
                // sc-wallguard-1009: a policy seat that failed past the deadline (it stops at its next service request
                // once the budget is out, ending its copy) was stopped by the budget: the decision is capped, as for a
                // play-out stopped between steps
                r.aborted = true;
            }
            if (r.aborted) {
                r.ok = false;
                r.value = Double.NEGATIVE_INFINITY;
                return r;
            }
            if (oppWon) {
                // search-bones-1015 (d): the opponent's verified line wins this world: a terminal loss for the candidate
                r.capped = false;
                r.value = -TERMINAL;
                r.pTerminal = 0.0;
            } else if (hit != null) {
                // Same position as an earlier candidate of this world (exact key): its continuation is this one's.
                final Rollout src = hit.src;
                r.dedupFrom = hit.cand;
                r.dedupStepsSaved = hit.remaining;
                r.capped = src.capped;
                r.value = src.value;
                r.pTerminal = src.pTerminal;
                r.leaf = src.leaf;
                r.endKey = src.endKey;
                r.fingerprint = src.fingerprint;
            } else {
                r.capped = !g.isGameOver() && !watch.reached;
                if (p.noLeaf) {
                    // bvw-step2-fork-1027 (OPR): only the game's result counts; a roll-out at its cap is not scored
                    r.pTerminal = terminalP(g, me);
                    r.value = Double.isNaN(r.pTerminal) ? 0 : (2 * r.pTerminal - 1) * TERMINAL;
                } else {
                    r.value = value(g, me);
                }
                if (model != null) {
                    r.pTerminal = terminalP(g, me);
                    if (Double.isNaN(r.pTerminal)) {
                        r.leaf = forge.bench.StateEncoder.encode(g, me);
                    }
                }
                if (probe != null) {
                    r.pTerminal = terminalP(g, me);
                    if (Double.isNaN(r.pTerminal)) {
                        if (!probe.done) {
                            // no priority of the seat within the extra steps (or a capped play-out): the leaf is here
                            probe.payload = hooks.captureLeaf(p.hookCtx, g, me);
                            probe.takeReach(g, me);
                            if (probe.opp != null) {
                                probe.payloadOpp = probe.captureOpp(g); // S2T
                            }
                            probe.done = true;
                            r.leafLate = true;
                        }
                        r.leafPayload = probe.payload;
                        r.leafPayloadOpp = probe.payloadOpp;
                        r.leafReach = probe.reach;
                    }
                }
                r.fingerprint = watch.fingerprint;
                if (memo != null) {
                    // The end state's key (verify compares it): the state after the last step, whatever the key window.
                    r.endKey = cfg.dedupVerify ? PlayoutKeys.stateKey(g) : null;
                }
            }
            if (memo != null) {
                memo.add(cand, r);
            }
            if (seat != null && seat.failure() != null) {
                r.ok = false;
                r.value = Double.NEGATIVE_INFINITY;
                r.seatFailure = seat.failure();
            }
            if (oppSeats != null && r.ok) {
                // bvw-step2-fork-1027 (S2T): an opponent policy seat that could not play (a fault ends its copy as a
                // draw) fails the play-out as our seat's would
                for (PlayoutSeat os : oppSeats) {
                    if (os.failure() != null) {
                        r.ok = false;
                        r.value = Double.NEGATIVE_INFINITY;
                        r.seatFailure = "opponent seat: " + os.failure();
                        break;
                    }
                }
            }
            if (p.firstAction != null) {
                r.planState = p.firstAction.planState;
            }
        } catch (RuntimeException | StackOverflowError e) {
            r.ok = false;
            r.value = Double.NEGATIVE_INFINITY;
            r.failClass = "exception";
            if (p.budget != null && p.budget.over()) {
                // sc-wallguard-1009: a play-out that failed past the deadline counts as stopped by the budget
                r.aborted = true;
            }
            System.err.println("[lookahead] rollout failed: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
        } finally {
            p.g = null;
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    /** The Random's internal seed without drawing from it (needs --add-opens java.base/java.util=ALL-UNNAMED), or "?". */
    static String peekSeed(Random r) {
        try {
            Field f = Random.class.getDeclaredField("seed");
            f.setAccessible(true);
            return Long.toHexString(((java.util.concurrent.atomic.AtomicLong) f.get(r)).get());
        } catch (Exception | Error e) {
            return "?";
        }
    }

    /**
     * Diagnostics (-Dlookahead.stress=D:W:C:N:T[:exit]): at searched decision D, play candidate C of world W out N more
     * times on T threads, each with a per-step log, and report whether every repeat equals the first. A repeat that
     * differs prints the first differing step of both. With ":exit" the JVM halts afterwards (probe runs only).
     */
    private void stress(String spec, int index, Game live, Player me, List<Cand> cands, SpellAbility defSa, long decisionSeed) {
        final String[] f = spec.split(":");
        if (Integer.parseInt(f[0]) != index) {
            return;
        }
        final int w = Integer.parseInt(f[1]), c = Math.min(Integer.parseInt(f[2]), cands.size() - 1);
        final int n = Integer.parseInt(f[3]), t = Integer.parseInt(f[4]);
        final Prepared[] ps = new Prepared[n];
        for (int i = 0; i < n; i++) {
            ps[i] = prepare(live, me, cands.get(c), defSa, mix(decisionSeed, 1000 + w), cfg.resample, null);
            ps[i].stepLog = new ArrayList<>();
        }
        final double[] vals = new double[n];
        final String[] names = new String[n];
        final AtomicInteger tn = new AtomicInteger();
        final ExecutorService ex = Executors.newFixedThreadPool(t, r -> {
            Thread th = new Thread(r, "Game-stress-" + tn.incrementAndGet());
            th.setDaemon(true);
            return th;
        });
        final List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int ii = i;
            fs.add(ex.submit(() -> {
                names[ii] = Thread.currentThread().getName();
                vals[ii] = play(ps[ii], null).value;
            }));
        }
        for (Future<?> fu : fs) {
            try {
                fu.get();
            } catch (Exception e) {
                System.err.println("[lstress] worker failed: " + e);
            }
        }
        ex.shutdownNow();
        final List<String> ref = ps[0].stepLog;
        int odd = 0;
        for (int i = 0; i < n; i++) {
            final List<String> x = ps[i].stepLog;
            int k = 0;
            while (k < Math.min(ref.size(), x.size()) && ref.get(k).equals(x.get(k))) {
                k++;
            }
            final boolean same = k == ref.size() && k == x.size() && Double.compare(vals[i], vals[0]) == 0;
            System.err.println("[lstress] d=" + index + " w=" + w + " c=" + c + " run=" + i + " thread=" + names[i] + " value="
                    + Double.doubleToLongBits(vals[i]) + " steps=" + x.size() + (same ? " SAME" : " DIFF at step " + k));
            if (!same && odd++ < 3) {
                for (int j = Math.max(0, k - 1); j <= k; j++) {
                    System.err.println("[lstress]   ref step " + j + ":\n" + (j < ref.size() ? ref.get(j) : "(end)"));
                    System.err.println("[lstress]   run step " + j + ":\n" + (j < x.size() ? x.get(j) : "(end)"));
                }
            }
        }
        System.err.println("[lstress] d=" + index + " summary: " + (n - odd) + "/" + n + " same as run 0");
        if (f.length > 5 && "exit".equals(f[5])) {
            System.err.flush();
            Runtime.getRuntime().halt(0);
        }
    }

    /** P(me wins) of a finished play-out, in the model's scale; NaN if the play-out did not end the game. */
    static double terminalP(Game g, Player me) {
        if (g.isGameOver() || !me.isInGame()) {
            if (me.hasWon()) {
                return 1.0;
            }
            if (me.hasLost() || !me.isInGame()) {
                return 0.0;
            }
            return 0.5;
        }
        for (Player o : me.getOpponents()) {
            if (o.hasLost()) {
                return 1.0;
            }
        }
        return Double.NaN;
    }

    /**
     * C5: replace the static leaf values of one decision by the served P(win). One request carries every
     * non-terminal leaf, world-major then candidate-minor (a fixed order, so the service is bit-identical). Terminal
     * play-outs score 1 / 0 / 0.5. On any failure the decision keeps Forge's static values (all of them, so a
     * decision never mixes the two scales) and the fallback is counted.
     */
    private void modelLeaves(Game live, Player me, double[][] values, Rollout[][] outs, boolean[] ok, int k) {
        final int n = values.length;
        final JsonArray leaves = new JsonArray();
        final List<int[]> at = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && r.leaf != null) {
                    leaves.add(r.leaf);
                    at.add(new int[] {c, w});
                }
            }
        }
        double[] p = null;
        if (leaves.size() > 0) {
            final JsonObject req = forgeRequest(live, me);
            req.add("leaves", leaves);
            final long t = System.nanoTime();
            p = model.score(req, leaves.size());
            stats.modelNanos += System.nanoTime() - t;
            if (p == null) {
                stats.modelFallbacks++;
                stats.modelLastError = model.lastError;
                System.err.println("[lookahead] model leaf failed, static fallback: " + model.lastError);
                return;
            }
            stats.modelCalls++;
            stats.modelLeaves += leaves.size();
            stats.modelDigest = model.digestHex();
            stats.modelCheckpoint = model.checkpointSha256;
            stats.modelUnknownCards = model.unknownCards;
        }
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && !Double.isNaN(r.pTerminal)) {
                    values[c][w] = r.pTerminal;
                }
            }
        }
        for (int i = 0; i < at.size(); i++) {
            values[at.get(i)[0]][at.get(i)[1]] = p[i];
        }
    }

    /** The service request's common fields (C5 leaves and the HX prior): schema, seat, startingSeat, mulligans, deck. */
    private JsonObject forgeRequest(Game live, Player me) {
        final JsonObject req = new JsonObject();
        req.addProperty("schema", ModelClient.REQUEST_SCHEMA);
        req.addProperty("seat", forge.bench.StateEncoder.playerIndex(live, me));
        req.addProperty("startingSeat", live.getStartingPlayer() == null ? -1
                : forge.bench.StateEncoder.playerIndex(live, live.getStartingPlayer()));
        final JsonArray mull = new JsonArray();
        for (Player pl : live.getPlayers()) {
            mull.add(pl.getStats().getMulliganCount());
        }
        req.add("mulligans", mull);
        req.add("deck", deckOf(me));
        return req;
    }

    /** The seat's registered main deck as {name: copies} (fixed for the game). */
    private JsonObject deckOf(Player me) {
        if (modelDeck == null) {
            final JsonObject d = new JsonObject();
            forge.deck.Deck deck = me.getRegisteredPlayer() == null ? null : me.getRegisteredPlayer().getDeck();
            if (deck != null && deck.has(forge.deck.DeckSection.Main)) {
                final java.util.TreeMap<String, Integer> byName = new java.util.TreeMap<>();
                for (Map.Entry<forge.item.PaperCard, Integer> e : deck.get(forge.deck.DeckSection.Main)) {
                    byName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
                }
                for (Map.Entry<String, Integer> e : byName.entrySet()) {
                    d.addProperty(e.getKey(), e.getValue());
                }
            }
            modelDeck = d;
        }
        return modelDeck;
    }

    static double value(Game g, Player me) {
        if (g.isGameOver() || !me.isInGame()) {
            if (me.hasWon()) {
                return TERMINAL;
            }
            if (me.hasLost() || !me.isInGame()) {
                return -TERMINAL;
            }
            return 0;
        }
        for (Player o : me.getOpponents()) {
            if (o.hasLost()) {
                return TERMINAL;
            }
        }
        return new GameStateEvaluator().getStaticScore(g, me).value;
    }

    // state-machines-1113: the priority round is PhaseHandler's explicit PriorityState now (no reflection). Same writes
    // in the same order as the reflective helpers they replace, so every caller stays byte-identical.

    /** The player who holds "first priority" (acted last) in {@code ph}, i.e. whose turn it is to see the stack resolve. */
    static Player firstPriority(PhaseHandler ph) {
        return ph.getFirstPriorityPlayer();
    }

    static void setFirstPriority(PhaseHandler ph, Player p) {
        ph.dangerouslySetFirstPriorityPlayer(p);
    }

    /** Whether {@code ph} gives priority on its next main-loop step (read-only; state keys). */
    static boolean givePriorityFlag(PhaseHandler ph) {
        return ph.givesPriorityThisStep();
    }

    /** The copy resumes exactly where the live seat stands: it holds priority, it acted first. */
    /** bvw-step1-build-1024: {@link #givePriority} for the step-1 labeller's root copy (forge.bench.rl.StepSearch). */
    public static void givePriorityTo(PhaseHandler ph, Player p) {
        givePriority(ph, p);
    }

    static void givePriority(PhaseHandler ph, Player p) {
        ph.dangerouslySetPriorityState(PhaseHandler.PriorityState.resumeWith(p));
    }


    // ------------------------------------------------------------------ combat (C2)

    /** An attack plan: attacker card id -> defender (player id, or card id for a planeswalker/battle). */
    static final class AttackPlan {
        final String label;
        final Map<Integer, Integer> attackers = new TreeMap<>();
        final Map<Integer, Boolean> defenderIsPlayer = new TreeMap<>();

        AttackPlan(String label) {
            this.label = label;
        }

        String key() {
            return attackers.toString() + defenderIsPlayer;
        }

        static AttackPlan of(String label, forge.game.combat.Combat c) {
            AttackPlan a = new AttackPlan(label);
            for (Card at : c.getAttackers()) {
                forge.game.GameEntity d = c.getDefenderByAttacker(at);
                if (d instanceof Player pl) {
                    a.attackers.put(at.getId(), pl.getId());
                    a.defenderIsPlayer.put(at.getId(), true);
                } else if (d instanceof Card dc) {
                    a.attackers.put(at.getId(), dc.getId());
                    a.defenderIsPlayer.put(at.getId(), false);
                }
            }
            return a;
        }

        /** Replace the combat's attackers with this plan (objects looked up in the combat's game). */
        boolean applyTo(Game g, forge.game.combat.Combat c) {
            c.clearAttackers();
            for (Map.Entry<Integer, Integer> e : attackers.entrySet()) {
                Card at = g.findById(e.getKey());
                forge.game.GameEntity d = defenderIsPlayer.get(e.getKey()) ? g.getPlayer(e.getValue()) : g.findById(e.getValue());
                if (at == null || d == null) {
                    return false;
                }
                c.addAttacker(at, d);
            }
            return true;
        }
    }

    /** A block plan: blocker card id -> attacker card ids it blocks. */
    static final class BlockPlan {
        final String label;
        final Map<Integer, List<Integer>> blocks = new TreeMap<>();

        BlockPlan(String label) {
            this.label = label;
        }

        String key() {
            return blocks.toString();
        }

        static BlockPlan of(String label, forge.game.combat.Combat c, Player defender) {
            BlockPlan b = new BlockPlan(label);
            for (Card bl : c.getAllBlockers()) {
                if (bl.getController() != defender) {
                    continue;
                }
                List<Integer> ats = new ArrayList<>();
                for (Card at : c.getAttackersBlockedBy(bl)) {
                    ats.add(at.getId());
                }
                b.blocks.put(bl.getId(), ats);
            }
            return b;
        }

        static void clear(forge.game.combat.Combat c, Player defender) {
            for (Card bl : new ArrayList<>(c.getAllBlockers())) {
                if (bl.getController() == defender) {
                    c.undoBlockingAssignment(bl);
                }
            }
        }

        boolean applyTo(Game g, forge.game.combat.Combat c, Player defender) {
            clear(c, defender);
            for (Map.Entry<Integer, List<Integer>> e : blocks.entrySet()) {
                Card bl = g.findById(e.getKey());
                if (bl == null) {
                    return false;
                }
                for (Integer aid : e.getValue()) {
                    Card at = g.findById(aid);
                    if (at == null) {
                        return false;
                    }
                    c.addBlocker(at, bl);
                }
            }
            return true;
        }
    }

    /** Play-out Forge AI whose first attack (or block) declaration is scripted. */
    static final class ScriptedCombat extends RolloutAi {
        private final AttackPlan attack;
        private final BlockPlan block;
        private boolean used = false;

        ScriptedCombat(Game g, Player p, forge.LobbyPlayer lp, AttackPlan attack, BlockPlan block) {
            super(g, p, lp);
            this.attack = attack;
            this.block = block;
        }

        @Override
        public void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
            if (attack != null && !used) {
                used = true;
                attack.applyTo(getGame(), combat);
                return;
            }
            super.declareAttackers(attacker, combat);
        }

        @Override
        public void declareBlockers(Player defender, forge.game.combat.Combat combat) {
            if (block != null && !used) {
                used = true;
                block.applyTo(getGame(), combat, defender);
                return;
            }
            super.declareBlockers(defender, combat);
        }
    }

    private static void setPriorityState(PhaseHandler ph, Player prio, Player first, boolean give) {
        ph.dangerouslySetPriorityState(new PhaseHandler.PriorityState(prio, first, give));
    }

    /**
     * One combat play-out: copy the live game, rewind the copy to the end of the step before the declaration
     * (both players passed), and let it advance into the declaration step, where the searching seat's
     * controller declares the plan. Everything after that is Forge AI on both seats.
     */
    Rollout combatRollout(Game live, Player liveMe, AttackPlan attack, BlockPlan block, long worldSeed, boolean resample) {
        final Rollout r = new Rollout();
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(worldSeed, 1)));
        final Object prevAiCache4 = AiCache.captureScope(); // restored below, never removed (k8-determinism-1008)
        AiCache.openScope();
        final Object prevIds = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player me;
            synchronized (live) {
                GameCopier copier = copierOf(live);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                if (resample) {
                    redraw(live, liveMe, g, me, new Random(mix(worldSeed, 2)));
                }
            }
            MyRandom.setThreadRandom(new Random(mix(worldSeed, 3)));
            final PhaseHandler ph = g.getPhaseHandler();
            final forge.game.combat.Combat c = ph.getCombat();
            if (c == null) {
                r.ok = false;
                r.value = Double.NEGATIVE_INFINITY;
                return r;
            }
            final Player active = ph.getPlayerTurn();
            final Player other = g.getNextPlayerAfter(active);
            if (attack != null) {
                c.clearAttackers();
                ph.devModeSet(forge.game.phase.PhaseType.COMBAT_BEGIN, active, false, ph.getTurn());
            } else {
                BlockPlan.clear(c, me);
                ph.devModeSet(forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS, active, false, ph.getTurn());
            }
            // both players have passed in the rewound step: the next main-loop step advances into the declaration
            setPriorityState(ph, other, active, false);
            me.dangerouslySetController(new ScriptedCombat(g, me, me.getController().getLobbyPlayer(), attack, block));
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new RolloutAi(g, o, o.getController().getLobbyPlayer()));
                }
            }
            final TurnWatch watch = new TurnWatch(ph.getTurn() + cfg.horizonTurns, null);
            g.subscribeToEvents(watch);
            long b = System.nanoTime();
            int steps = 0;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.rolloutNanos = System.nanoTime() - b;
            r.steps = steps;
            r.capped = !g.isGameOver() && !watch.reached;
            r.value = value(g, me);
        } catch (RuntimeException | StackOverflowError e) {
            r.ok = false;
            r.value = Double.NEGATIVE_INFINITY;
            r.failClass = "exception";
            System.err.println("[lookahead] combat rollout failed: " + e);
        } finally {
            AiCache.installScope(prevAiCache4);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    /** Pick the best of the candidate plans by EV over K worlds (index 0 = Forge's own, ties to it). */
    private int bestCombatPlan(Game live, Player me, int n, java.util.function.BiFunction<Integer, Long, Rollout> run, long seed) {
        final int k = Math.max(1, cfg.worlds);
        final double[][] values = new double[n][k];
        final boolean[] ok = new boolean[n];
        Arrays.fill(ok, true);
        final List<Runnable> tasks = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final int ww = w, cc = c;
                tasks.add(() -> {
                    Rollout r = run.apply(cc, mix(seed, 1000 + ww));
                    synchronized (values) {
                        values[cc][ww] = r.value;
                        if (!r.ok) {
                            ok[cc] = false;
                        }
                        stats.rollouts++;
                        stats.steps += r.steps;
                        if (!r.ok) {
                            stats.rolloutFailures++;
                            stats.countPlayoutFailure(failureClass(r), null);
                        }
                    }
                });
            }
        }
        runAll(tasks);
        if (!ok[0]) {
            return 0;
        }
        int best = 0;
        double bestEv = 0;
        for (int c = 0; c < n; c++) {
            double s2 = 0;
            for (int w = 0; w < k; w++) {
                s2 += values[c][w];
            }
            double ev = ok[c] ? s2 / k : Double.NEGATIVE_INFINITY;
            if (c == 0) {
                bestEv = ev;
            } else if (ev > bestEv + cfg.margin) {
                best = c;
                bestEv = ev;
            }
        }
        return best;
    }

    /** Called after Forge AI has declared its attackers into the live combat. */
    public void decideAttack(PlayerControllerAi ctrl, forge.game.combat.Combat combat) {
        final Game live = ctrl.getGame();
        final Player me = ctrl.getPlayer();
        stats.attackDecisions++;
        final int index = decisionIndex++;
        final long t0 = System.nanoTime();
        final List<AttackPlan> plans = new ArrayList<>();
        final AttackPlan forge0 = AttackPlan.of("forge", combat);
        plans.add(forge0);
        AttackPlan none = new AttackPlan("none");
        // alpha: every creature that can attack Forge's defender (or the opponent) without an attack cost
        forge.game.GameEntity def = forge0.attackers.isEmpty() ? null : combat.getDefenderByAttacker(combat.getAttackers().get(0));
        if (def == null) {
            for (Player o : me.getOpponents()) {
                def = o;
                break;
            }
        }
        AttackPlan alpha = new AttackPlan("alpha");
        if (def != null) {
            for (Card cr : me.getCreaturesInPlay()) {
                if (forge.game.combat.CombatUtil.canAttack(cr, def)
                        && forge.game.combat.CombatUtil.getAttackCost(live, cr, def) == null) {
                    alpha.attackers.put(cr.getId(), def instanceof Player ? ((Player) def).getId() : ((Card) def).getId());
                    alpha.defenderIsPlayer.put(cr.getId(), def instanceof Player);
                }
            }
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        keys.add(forge0.key());
        for (AttackPlan a : new AttackPlan[] {none, alpha}) {
            if (keys.add(a.key())) {
                plans.add(a);
            }
        }
        if (plans.size() < 2 || live.getStack().size() > 0) {
            return;
        }
        stats.attackSearched++;
        int best = bestCombatPlan(live, me, plans.size(),
                (c, ws) -> combatRollout(live, me, plans.get(c), null, ws, cfg.resample), mix(cfg.seed, 0xa77aL + index));
        if (best != 0 && !cfg.shadow) {
            if (plans.get(best).applyTo(live, combat) && forge.game.combat.CombatUtil.validateAttackers(combat)) {
                stats.attackDeparted++;
            } else {
                forge0.applyTo(live, combat);
            }
        }
        stats.combatNanos += System.nanoTime() - t0;
    }

    /** Called after Forge AI has declared its blockers into the live combat. */
    public void decideBlock(PlayerControllerAi ctrl, Player defender, forge.game.combat.Combat combat) {
        final Game live = ctrl.getGame();
        final Player me = ctrl.getPlayer();
        if (defender != me) {
            return;
        }
        stats.blockDecisions++;
        final int index = decisionIndex++;
        final long t0 = System.nanoTime();
        final BlockPlan forge0 = BlockPlan.of("forge", combat, me);
        final BlockPlan none = new BlockPlan("none");
        final List<BlockPlan> plans = new ArrayList<>();
        plans.add(forge0);
        if (!none.key().equals(forge0.key())) {
            plans.add(none);
        }
        if (plans.size() < 2) {
            return;
        }
        stats.blockSearched++;
        int best = bestCombatPlan(live, me, plans.size(),
                (c, ws) -> combatRollout(live, me, null, plans.get(c), ws, cfg.resample), mix(cfg.seed, 0xb10cL + index));
        if (best != 0 && !cfg.shadow) {
            if (plans.get(best).applyTo(live, combat, me) && forge.game.combat.CombatUtil.validateBlocks(combat, me) == null) {
                stats.blockDeparted++;
            } else {
                forge0.applyTo(live, combat, me);
            }
        }
        stats.combatNanos += System.nanoTime() - t0;
    }

    // ------------------------------------------------------------------ block search (lane block-search-1019)

    /**
     * Block search: rewind a copy made at the searching seat's declare-blockers ask to the end of declare attackers (its
     * blockers cleared, both players passed), so that its next main-loop step enters declare blockers and the seat's
     * play-out controller declares there (as {@link #combatRollout} rewinds for C2).
     */
    static void rewindToBlocks(Game g, Player me) {
        final PhaseHandler ph = g.getPhaseHandler();
        final forge.game.combat.Combat c = ph.getCombat();
        if (c == null || ph.getPhase() != forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS) {
            throw new IllegalStateException("block search: the copy is not at declare blockers (" + ph.getPhase()
                    + (c == null ? ", no combat)" : ")"));
        }
        final Player active = ph.getPlayerTurn();
        final Player other = g.getNextPlayerAfter(active);
        BlockPlan.clear(c, me);
        ph.devModeSet(forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS, active, false, ph.getTurn());
        setPriorityState(ph, other, active, false);
    }

    /** Block search: an assignment (blocker card id -> attacker card ids) as the opaque plan of a candidate. */
    public static Object blockPlan(String label, Map<Integer, List<Integer>> blocks) {
        final BlockPlan b = new BlockPlan(label);
        for (Map.Entry<Integer, List<Integer>> e : blocks.entrySet()) {
            b.blocks.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return b;
    }

    /**
     * Block search: declare {@code block} (a {@link #blockPlan}) into {@code combat} for {@code defender}, as the bridge
     * declares an answer (each pair must be a legal block, then the whole declaration must validate). Null when declared;
     * otherwise why not (and nothing of it stays declared).
     */
    public static String applyBlocks(Object block, Game g, Player defender, forge.game.combat.Combat combat) {
        if (!(block instanceof BlockPlan)) {
            return "not a block plan";
        }
        final BlockPlan b = (BlockPlan) block;
        BlockPlan.clear(combat, defender);
        String bad = null;
        for (Map.Entry<Integer, List<Integer>> e : b.blocks.entrySet()) {
            final Card bl = g.findById(e.getKey());
            for (Integer aid : e.getValue()) {
                final Card at = g.findById(aid);
                if (bl == null || at == null) {
                    bad = "blocker " + e.getKey() + " or attacker " + aid + " is not in the copy";
                    break;
                }
                if (!forge.game.combat.CombatUtil.canBlock(at, bl, combat)) {
                    bad = bl.getName() + " cannot block " + at.getName();
                    break;
                }
                combat.addBlocker(at, bl);
            }
            if (bad != null) {
                break;
            }
        }
        if (bad == null) {
            final String problem = forge.game.combat.CombatUtil.validateBlocks(combat, defender);
            if (problem != null) {
                bad = "validateBlocks: " + problem;
            }
        }
        if (bad != null) {
            BlockPlan.clear(combat, defender);
        }
        return bad;
    }

    /**
     * Block search: Forge AI's own block declaration ({@code AiBlockController}, through a play-out Forge AI controller)
     * at the searching seat's declare-blockers ask, made in a copy of the live game (its scopes isolated; the live game
     * is only read). Null when it cannot be made.
     */
    public Map<Integer, List<Integer>> forgeBlocks(Game live, Player liveMe) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new Random(mix(cfg.seed, 0xf0b10cL + decisionIndex)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player me;
            synchronized (live) {
                final GameCopier copier = copierOf(live);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
            }
            final forge.game.combat.Combat c = g.getPhaseHandler().getCombat();
            if (c == null) {
                return null;
            }
            BlockPlan.clear(c, me);
            final RolloutAi ai = new RolloutAi(g, me, me.getController().getLobbyPlayer());
            me.dangerouslySetController(ai);
            ai.declareBlockers(me, c);
            return BlockPlan.of("forge", c, me).blocks;
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("[lookahead] block search: Forge's blocks failed: " + e);
            return null;
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * Block search (lane block-search-1019): the S1 recipe over a GIVEN set of block assignments at the searching seat's
     * declare-blockers ask. {@code plans} (blocker card id -> attacker card ids) are in the caller's order; index 0 is the
     * default the search departs from (the policy's own declaration). Duplicates are dropped. Each candidate is played out
     * in K worlds (common random numbers) by the policy play-out seat, which declares the candidate at the copy's declare
     * blockers (the copy is rewound to the end of declare attackers) and is the policy afterwards; the value leaf, the
     * departZ gate and the wall budget (counted from {@code startNanos}, 0 = this call's start) are the priority search's.
     * Needs policy play-outs. The live game is only read.
     */
    public GivenResult decideBlocks(Game live, Player me, List<Map<Integer, List<Integer>>> plans, List<String> labels,
            long startNanos) {
        final GivenResult res = new GivenResult();
        decisionForce = forcePlayed;
        forcePlayed = null;
        stats.s1On = true;
        stats.decisions++;
        stats.blockDecisions++;
        if (!hookPlayout) {
            res.outcome = "unsupported";
            return res;
        }
        final int index = decisionIndex++;
        final List<Cand> cands = new ArrayList<>();
        final List<Integer> at = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < plans.size(); i++) {
            final Cand c = new Cand((BlockPlan) blockPlan(labels.get(i), plans.get(i)), labels.get(i), i == 0);
            if (seen.add(c.key())) {
                cands.add(c);
                at.add(i);
            }
        }
        if (cands.size() < 2) {
            stats.uncontested++;
            res.outcome = "uncontested";
            return res;
        }
        final long t0 = System.nanoTime();
        final long c0 = TMX.getCurrentThreadCpuTime();
        final java.util.concurrent.atomic.AtomicLong pc = new java.util.concurrent.atomic.AtomicLong();
        poolCpu = pc;
        final java.util.concurrent.atomic.AtomicLong[] prevAcc = forge.ai.CpuAccount.get();
        forge.ai.CpuAccount.set(forge.ai.CpuAccount.plus(prevAcc, pc));
        // work-units-1107: startNanos is on the work clock when one is installed; the own start too
        final long start = startNanos != 0L ? startNanos
                : forge.game.WorkClock.active() ? forge.game.WorkClock.nanoTime() : t0;
        final Budget b = cfg.budgetMs > 0 ? new Budget(start + forge.ai.WallClock.budgetMs(cfg.budgetMs) * 1_000_000L) : null;
        budget = b;
        boolean searched = false;
        try {
            searched = decideBlocks0(live, me, cands, at, index, res);
        } catch (RuntimeException e) {
            stats.departFallback++;
            res.chosen = 0;
            res.outcome = "failed";
            System.err.println("[lookahead] block search failed at decision " + index + ", playing the default: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
        } finally {
            budget = null;
            if (b != null && b.wallCut) {
                res.wall = true;
            }
            poolCpu = null;
            forge.ai.CpuAccount.set(prevAcc);
            final long dt = System.nanoTime() - t0;
            final long cpu = TMX.getCurrentThreadCpuTime() - c0 + pc.get();
            res.ms = dt / 1e6;
            res.cpuMs = cpu / 1e6;
            res.poolCpuMs = pc.get() / 1e6;
            if (searched) {
                stats.searchNanos += dt;
                stats.maxSearchNanos = Math.max(stats.maxSearchNanos, dt);
                stats.searchMsEach.add(dt / 1e6);
                stats.searchCpuNanos += cpu;
                stats.searchCpuMsEach.add(cpu / 1e6);
                stats.combatNanos += dt;
            }
        }
        return res;
    }

    private boolean decideBlocks0(Game live, Player me, List<Cand> cands, List<Integer> at, int index, GivenResult res) {
        final long decisionSeed = mix(cfg.seed, 0xb10c5eedL + index);
        stats.searched++;
        stats.blockSearched++;
        lastCandidates = cands;
        final int k = Math.max(1, cfg.worlds);
        final int n = cands.size();
        final double[][] values = new double[n][k];
        final Rollout[][] outs = new Rollout[n][k];
        final boolean[] ok = new boolean[n];
        Arrays.fill(ok, true);
        explainChoices = EXPLAIN ? new String[n] : null;
        labelChoices = null;
        labelTargets = null;
        final int aborted = playAll(live, me, cands, null, decisionSeed, new Carried[k], values, outs, ok, null);
        if (hookLeaf && aborted == 0) {
            final long fb = stats.leafFallbacks;
            valueLeaves(values, outs, ok, k);
            res.leafFallback = stats.leafFallbacks != fb;
        }
        final boolean overBudget = aborted > 0;
        if (cfg.lethalBlock != AiFixes.Mode.OFF && !overBudget) {
            // block-vs-open-pump-1118: a worst-case lethal declaration scores as a lost play-out (ON), in the scale the
            // values are in: the value leaf's P(win) of a loss is 0, the static value's is -TERMINAL
            lethalBlockGuard(live, me, cands, values, ok, k, hookLeaf && !res.leafFallback ? 0.0 : -TERMINAL, res);
        }
        final double[] ev = new double[n];
        for (int c = 0; c < n; c++) {
            double s = 0;
            for (int w = 0; w < k; w++) {
                s += values[c][w];
            }
            ev[c] = ok[c] ? s / k : Double.NaN;
            if (!ok[c]) {
                stats.candidatesDropped++;
                res.failed++;
            }
        }
        int best = overBudget ? 0 : argmax(values, ok, n, k);
        final java.util.function.IntUnaryOperator to = testOverride;
        final java.util.function.IntSupplier lineOf = explainLine;
        if (to != null && lineOf != null) {
            final int o = to.applyAsInt(lineOf.getAsInt());
            if (o >= 0 && o < n && ok[o]) {
                best = o; // tests only (J1)
            }
        }
        // J1: the live game's block declaration, when the search would play another and it is a candidate
        final String want = decisionForce;
        decisionForce = null;
        boolean forcing = false;
        if (want != null && labelCount(cands, want) > 1) {
            res.forcedMiss = true; // review S2: an ambiguous key
        } else if (want != null) {
            final int natural = overBudget || cfg.shadow ? 0 : best;
            if (!want.equals(cands.get(natural).label)) {
                final int idx = indexOfLabel(cands, want);
                if (idx >= 0) {
                    best = idx;
                    forcing = true;
                } else {
                    res.forcedMiss = true;
                }
            }
        }
        res.chosen = 0;
        String outcome = "kept";
        if (best != 0 && !cfg.shadow) {
            res.chosen = at.get(best);
            stats.departed++;
            stats.blockDeparted++;
            outcome = "departed";
        } else if (best != 0) {
            outcome = "shadow-would-depart";
        }
        if (overBudget && !forcing) {
            stats.capped++;
            res.chosen = 0;
            outcome = "capped";
        }
        res.outcome = outcome;
        res.played = cands.get("departed".equals(outcome) ? best : 0).label;
        if (forcing) {
            res.forced = res.played.equals(want);
            res.forcedMiss = !res.forced;
        }
        if (res.witnessed != null && res.witnessed.length == n && res.witnessed["departed".equals(outcome) ? best : 0]) {
            stats.lethalBlockPlayedLethal++; // lethalBlock: the declaration played was witnessed lethal
        }
        res.worlds = k;
        res.ev = ev;
        res.values = values;
        res.givenIndex = new int[n];
        for (int c = 0; c < n; c++) {
            res.givenIndex[c] = at.get(c);
        }
        final PhaseHandler ph = live.getPhaseHandler();
        if (Boolean.getBoolean("lookahead.trace")) {
            final StringBuilder tb = new StringBuilder("[ltrace] blocks d=").append(index).append(" T").append(ph.getTurn())
                    .append(' ').append(ph.getPhase()).append(" best=").append(best).append(" out=").append(outcome);
            for (int c = 0; c < n; c++) {
                tb.append(" | ").append(cands.get(c).label.replace('|', '/')).append(ok[c] ? "" : " FAIL");
                for (int w = 0; w < k; w++) {
                    tb.append(' ').append(Double.doubleToLongBits(values[c][w]));
                }
            }
            System.err.println(tb);
        }
        if (EXPLAIN) {
            final JsonObject x = new JsonObject();
            x.addProperty("given", true);
            x.addProperty("kind", "blocks");
            x.addProperty("decision", index);
            if (lineOf != null) {
                x.addProperty("line", lineOf.getAsInt());
            }
            x.addProperty("played", res.played);
            x.addProperty("turn", ph.getTurn());
            x.addProperty("phase", String.valueOf(ph.getPhase()));
            x.addProperty("best", best);
            x.addProperty("outcome", outcome);
            if (res.lethal != null) {
                // lethalBlock (guard on or shadow only): checked (re-scored or not) or why it stood aside
                x.addProperty("lethalBlock", res.lethalAside != null ? "aside: " + res.lethalAside
                        : res.lethalRescored ? "rescored" : "checked");
                if (res.witnessed != null) {
                    x.addProperty("lethalWitnessMs", res.witnessMs);
                }
            }
            final JsonArray ca = new JsonArray();
            for (int c = 0; c < n; c++) {
                final JsonObject co = new JsonObject();
                co.addProperty("label", cands.get(c).label);
                co.addProperty("ok", ok[c]);
                co.addProperty("ev", ok[c] ? ev[c] : null);
                if (res.lethal != null && res.lethal.length == n && res.lethal[c]) {
                    co.addProperty("lethalBlock", true); // the bound flags it (round 6: a prefilter)
                    final boolean w = res.witnessed != null && c < res.witnessed.length && res.witnessed[c];
                    co.addProperty("lethalWitnessed", w);
                    if (w && res.witnessLine != null && c < res.witnessLine.length) {
                        co.addProperty("lethalLine", res.witnessLine[c]);
                    }
                }
                final JsonArray vs = new JsonArray();
                for (int w = 0; w < k; w++) {
                    vs.add(values[c][w]);
                }
                co.add("values", vs);
                ca.add(co);
            }
            x.add("candidates", ca);
            System.err.println("[lookahead-explain] " + x);
        }
        return true;
    }

    // ------------------------------------------------------------------ attack hold-back search (attack-holdback-1118)

    /**
     * Attack search: rewind a copy made at the searching seat's declare-attackers ask to the end of beginning of combat (its
     * attackers cleared, both players passed, the combat kept), so that its next main-loop step enters declare attackers
     * and the seat's play-out controller declares there (as {@link #combatRollout} rewinds for C2).
     */
    static void rewindToAttacks(Game g, Player me) {
        final PhaseHandler ph = g.getPhaseHandler();
        final forge.game.combat.Combat c = ph.getCombat();
        if (c == null || ph.getPhase() != forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS || ph.getPlayerTurn() != me) {
            throw new IllegalStateException("attack search: the copy is not at our declare attackers (" + ph.getPhase()
                    + (c == null ? ", no combat)" : ")"));
        }
        final Player other = g.getNextPlayerAfter(me);
        c.clearAttackers();
        ph.devModeSet(forge.game.phase.PhaseType.COMBAT_BEGIN, me, false, ph.getTurn());
        setPriorityState(ph, other, me, false);
    }

    /**
     * Attack search: a declaration (attacker card id -> defender key, {@code player:<id>} or {@code card:<id>}) as the
     * opaque plan of a candidate.
     */
    public static Object attackPlan(String label, Map<Integer, String> attackers) {
        final AttackPlan a = new AttackPlan(label);
        for (Map.Entry<Integer, String> e : attackers.entrySet()) {
            final String d = e.getValue();
            final int colon = d.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("attack search: a defender key is player:<id> or card:<id>, not " + d);
            }
            a.attackers.put(e.getKey(), Integer.parseInt(d.substring(colon + 1)));
            a.defenderIsPlayer.put(e.getKey(), d.startsWith("player:"));
        }
        return a;
    }

    /**
     * Attack search: declare {@code attack} (an {@link #attackPlan}) into {@code combat}, as the bridge declares an answer
     * (each pair must be a legal attack, then the whole declaration must validate). Null when declared; otherwise why not
     * (and nothing of it stays declared).
     */
    public static String applyAttacks(Object attack, Game g, forge.game.combat.Combat combat) {
        if (!(attack instanceof AttackPlan)) {
            return "not an attack plan";
        }
        final AttackPlan a = (AttackPlan) attack;
        combat.clearAttackers();
        String bad = null;
        for (Map.Entry<Integer, Integer> e : a.attackers.entrySet()) {
            final Card at = g.findById(e.getKey());
            final forge.game.GameEntity d = Boolean.TRUE.equals(a.defenderIsPlayer.get(e.getKey()))
                    ? g.getPlayer(e.getValue()) : g.findById(e.getValue());
            if (at == null || d == null) {
                bad = "attacker " + e.getKey() + " or defender " + e.getValue() + " is not in the copy";
                break;
            }
            if (!forge.game.combat.CombatUtil.canAttack(at, d)) {
                bad = at.getName() + " cannot attack " + d;
                break;
            }
            combat.addAttacker(at, d);
        }
        if (bad == null && !forge.game.combat.CombatUtil.validateAttackers(combat)) {
            bad = "validateAttackers rejected the declaration";
        }
        if (bad != null) {
            combat.clearAttackers();
        }
        return bad;
    }

    /**
     * Attack search: which of {@code plans} (attacker card id -> defender key) the live game would accept as the seat's
     * answer ({@link #applyAttacks}), checked in one copy of the live game (its scopes isolated; the live game is only
     * read). Null when the check cannot be made.
     */
    public boolean[] legalAttacks(Game live, Player liveMe, List<Map<Integer, String>> plans) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new Random(mix(cfg.seed, 0xa77ac1L + decisionIndex)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            synchronized (live) {
                g = copierOf(live).makeCopy();
            }
            final forge.game.combat.Combat c = g.getPhaseHandler().getCombat();
            if (c == null) {
                return null;
            }
            final boolean[] ok = new boolean[plans.size()];
            for (int i = 0; i < plans.size(); i++) {
                ok[i] = applyAttacks(attackPlan("check", plans.get(i)), g, c) == null;
                c.clearAttackers();
            }
            return ok;
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("[lookahead] attack search: the legality check failed: " + e);
            return null;
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * Attack search (attack-holdback-1118): the S1 recipe over a GIVEN set of attack declarations at the searching seat's
     * declare-attackers ask, as {@link #decideBlocks}. {@code plans} (attacker card id -> defender key) are in the caller's
     * order; index 0 is the default the search departs from (the policy's own declaration). Duplicates are dropped. Each
     * candidate is played out in K worlds (common random numbers) by the policy play-out seat, which declares the candidate
     * at the copy's declare attackers (the copy is rewound to the end of beginning of combat) and is the policy afterwards;
     * the value leaf, the departZ gate and the wall budget are the priority search's. Needs policy play-outs. The live game
     * is only read.
     */
    public GivenResult decideAttacks(Game live, Player me, List<Map<Integer, String>> plans, List<String> labels,
            long startNanos) {
        final GivenResult res = new GivenResult();
        decisionForce = forcePlayed;
        forcePlayed = null;
        stats.s1On = true;
        stats.decisions++;
        stats.attackDecisions++;
        if (!hookPlayout) {
            res.outcome = "unsupported";
            return res;
        }
        final int index = decisionIndex++;
        final List<Cand> cands = new ArrayList<>();
        final List<Integer> at = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < plans.size(); i++) {
            final Cand c = new Cand((AttackPlan) attackPlan(labels.get(i), plans.get(i)), labels.get(i), i == 0);
            if (seen.add(c.key())) {
                cands.add(c);
                at.add(i);
            }
        }
        if (cands.size() < 2) {
            stats.uncontested++;
            res.outcome = "uncontested";
            return res;
        }
        final long t0 = System.nanoTime();
        final long c0 = TMX.getCurrentThreadCpuTime();
        final java.util.concurrent.atomic.AtomicLong pc = new java.util.concurrent.atomic.AtomicLong();
        poolCpu = pc;
        final java.util.concurrent.atomic.AtomicLong[] prevAcc = forge.ai.CpuAccount.get();
        forge.ai.CpuAccount.set(forge.ai.CpuAccount.plus(prevAcc, pc));
        final long start = startNanos != 0L ? startNanos
                : forge.game.WorkClock.active() ? forge.game.WorkClock.nanoTime() : t0;
        final Budget b = cfg.budgetMs > 0 ? new Budget(start + forge.ai.WallClock.budgetMs(cfg.budgetMs) * 1_000_000L) : null;
        budget = b;
        boolean searched = false;
        try {
            searched = decideAttacks0(live, me, cands, at, index, res);
        } catch (RuntimeException e) {
            stats.departFallback++;
            res.chosen = 0;
            res.outcome = "failed";
            System.err.println("[lookahead] attack search failed at decision " + index + ", playing the default: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
        } finally {
            budget = null;
            if (b != null && b.wallCut) {
                res.wall = true;
            }
            poolCpu = null;
            forge.ai.CpuAccount.set(prevAcc);
            final long dt = System.nanoTime() - t0;
            final long cpu = TMX.getCurrentThreadCpuTime() - c0 + pc.get();
            res.ms = dt / 1e6;
            res.cpuMs = cpu / 1e6;
            res.poolCpuMs = pc.get() / 1e6;
            if (searched) {
                stats.searchNanos += dt;
                stats.maxSearchNanos = Math.max(stats.maxSearchNanos, dt);
                stats.searchMsEach.add(dt / 1e6);
                stats.searchCpuNanos += cpu;
                stats.searchCpuMsEach.add(cpu / 1e6);
                stats.combatNanos += dt;
            }
        }
        return res;
    }

    private boolean decideAttacks0(Game live, Player me, List<Cand> cands, List<Integer> at, int index, GivenResult res) {
        final long decisionSeed = mix(cfg.seed, 0xa77ac5eedL + index);
        stats.searched++;
        stats.attackSearched++;
        lastCandidates = cands;
        final int k = Math.max(1, cfg.worlds);
        final int n = cands.size();
        final double[][] values = new double[n][k];
        final Rollout[][] outs = new Rollout[n][k];
        final boolean[] ok = new boolean[n];
        Arrays.fill(ok, true);
        explainChoices = EXPLAIN ? new String[n] : null;
        labelChoices = null;
        labelTargets = null;
        final int aborted = playAll(live, me, cands, null, decisionSeed, new Carried[k], values, outs, ok, null);
        if (hookLeaf && aborted == 0) {
            final long fb = stats.leafFallbacks;
            valueLeaves(values, outs, ok, k);
            res.leafFallback = stats.leafFallbacks != fb;
        }
        final boolean overBudget = aborted > 0;
        final double[] ev = new double[n];
        for (int c = 0; c < n; c++) {
            double s = 0;
            for (int w = 0; w < k; w++) {
                s += values[c][w];
            }
            ev[c] = ok[c] ? s / k : Double.NaN;
            if (!ok[c]) {
                stats.candidatesDropped++;
                res.failed++;
            }
        }
        int best = overBudget ? 0 : argmax(values, ok, n, k);
        final java.util.function.IntUnaryOperator to = testOverride;
        final java.util.function.IntSupplier lineOf = explainLine;
        if (to != null && lineOf != null) {
            final int o = to.applyAsInt(lineOf.getAsInt());
            if (o >= 0 && o < n && ok[o]) {
                best = o; // tests only (J1)
            }
        }
        // J1: the live game's attack declaration, when the search would play another and it is a candidate
        final String want = decisionForce;
        decisionForce = null;
        boolean forcing = false;
        if (want != null && labelCount(cands, want) > 1) {
            res.forcedMiss = true;
        } else if (want != null) {
            final int natural = overBudget || cfg.shadow ? 0 : best;
            if (!want.equals(cands.get(natural).label)) {
                final int idx = indexOfLabel(cands, want);
                if (idx >= 0) {
                    best = idx;
                    forcing = true;
                } else {
                    res.forcedMiss = true;
                }
            }
        }
        res.chosen = 0;
        String outcome = "kept";
        if (best != 0 && !cfg.shadow) {
            res.chosen = at.get(best);
            stats.departed++;
            stats.attackDeparted++;
            outcome = "departed";
        } else if (best != 0) {
            outcome = "shadow-would-depart";
        }
        if (overBudget && !forcing) {
            stats.capped++;
            res.chosen = 0;
            outcome = "capped";
        }
        res.outcome = outcome;
        res.played = cands.get("departed".equals(outcome) ? best : 0).label;
        if (forcing) {
            res.forced = res.played.equals(want);
            res.forcedMiss = !res.forced;
        }
        res.worlds = k;
        res.ev = ev;
        res.values = values;
        res.givenIndex = new int[n];
        for (int c = 0; c < n; c++) {
            res.givenIndex[c] = at.get(c);
        }
        final PhaseHandler ph = live.getPhaseHandler();
        if (Boolean.getBoolean("lookahead.trace")) {
            final StringBuilder tb = new StringBuilder("[ltrace] attacks d=").append(index).append(" T").append(ph.getTurn())
                    .append(' ').append(ph.getPhase()).append(" best=").append(best).append(" out=").append(outcome);
            for (int c = 0; c < n; c++) {
                tb.append(" | ").append(cands.get(c).label.replace('|', '/')).append(ok[c] ? "" : " FAIL");
                for (int w = 0; w < k; w++) {
                    tb.append(' ').append(Double.doubleToLongBits(values[c][w]));
                }
            }
            System.err.println(tb);
        }
        if (EXPLAIN) {
            final JsonObject x = new JsonObject();
            x.addProperty("given", true);
            x.addProperty("kind", "attacks");
            x.addProperty("decision", index);
            if (lineOf != null) {
                x.addProperty("line", lineOf.getAsInt());
            }
            x.addProperty("played", res.played);
            x.addProperty("turn", ph.getTurn());
            x.addProperty("phase", String.valueOf(ph.getPhase()));
            x.addProperty("best", best);
            x.addProperty("outcome", outcome);
            final JsonArray ca = new JsonArray();
            for (int c = 0; c < n; c++) {
                final JsonObject co = new JsonObject();
                co.addProperty("label", cands.get(c).label);
                co.addProperty("ok", ok[c]);
                co.addProperty("ev", ok[c] ? ev[c] : null);
                final JsonArray vs = new JsonArray();
                for (int w = 0; w < k; w++) {
                    vs.add(values[c][w]);
                }
                co.add("values", vs);
                ca.add(co);
            }
            x.add("candidates", ca);
            System.err.println("[lookahead-explain] " + x);
        }
        return true;
    }

    // ------------------------------------------------------------------ lethalBlock guard (block-vs-open-pump-1118)

    /**
     * lethalBlock guard at the block search. Round 6: the bound ({@link #lethalBlocks}) is a prefilter only; a candidate
     * counts as lethal when the engine witnesses it ({@link #lethalWitness}: a single-ability line played in a sandboxed
     * copy kills the seat through combat damage). ON, when at least one searched candidate is not witnessed, each
     * witnessed one gets the value of a lost play-out ({@code loss}) in every world, before the argmax. With every
     * searched candidate witnessed, or none, the values are unchanged; SHADOW only counts and flags; a check that fails or
     * is not trusted stands aside (nothing changes).
     */
    private void lethalBlockGuard(Game live, Player me, List<Cand> cands, double[][] values, boolean[] ok, int k, double loss,
            GivenResult res) {
        stats.lethalBlockChecked++;
        LethalCheck lc;
        try {
            lc = lethalBlocks(live, me, cands, ok);
        } catch (RuntimeException | StackOverflowError e) {
            stats.lethalBlockErrors++;
            lc = LethalCheck.aside("error: " + e);
            final StackTraceElement[] at = e.getStackTrace();
            System.err.println("[lookahead] lethalBlock check failed, standing aside: " + e
                    + (at.length > 0 ? " at " + at[0] : "") + (at.length > 1 ? " < " + at[1] : "") + (at.length > 2 ? " < " + at[2] : ""));
        }
        if (lc.aside == null && (lc.lethal == null || lc.lethal.length != cands.size())) {
            lc = LethalCheck.aside("candidate count");
        }
        lethalBlockApply(lc, values, ok, k, loss, res);
    }

    /**
     * lethalBlock: apply a check to the candidates' values (the guard's decision, apart from the check itself; tests drive
     * it directly). Stood aside: nothing changes, {@code res.lethal} is empty. Otherwise {@code res.lethal} holds the
     * bound's flags and {@code res.witnessed} the engine's witnesses; ON, with some searched candidate not witnessed, each
     * searched witnessed candidate's values become {@code loss} in all {@code k} worlds. Only a witnessed candidate is ever
     * re-scored (a check without witnesses re-scores nothing). No candidate is removed (the default included: it keeps
     * its place and its {@code ok}).
     */
    void lethalBlockApply(LethalCheck lc, double[][] values, boolean[] ok, int k, double loss, GivenResult res) {
        if (lc.aside != null) {
            stats.lethalBlockAside++;
            stats.lethalBlockWhy.merge(lc.aside.startsWith("error") ? "error" : lc.aside, 1L, Long::sum);
            res.lethal = new boolean[0];
            res.lethalAside = lc.aside;
            return;
        }
        final int n = lc.lethal.length;
        res.lethal = lc.lethal.clone();
        final boolean[] wit = new boolean[n];
        if (lc.witnessed != null) {
            for (int c = 0; c < n && c < lc.witnessed.length; c++) {
                wit[c] = lc.witnessed[c] && lc.lethal[c];
            }
        }
        res.witnessed = wit;
        res.witnessLine = lc.witnessLine == null ? new String[n] : Arrays.copyOf(lc.witnessLine, n);
        res.witnessMs = lc.witnessNanos / 1e6;
        stats.lethalBlockWitnessRuns += lc.witnessRuns;
        stats.lethalBlockWitnessNanos += lc.witnessNanos;
        if (lc.witnessCapped) {
            stats.lethalBlockWitnessCapped++;
        }
        if (lc.witnessTimedOut) {
            stats.lethalBlockWitnessTimedOut++;
        }
        lc.witnessVoid.forEach((why, count) -> stats.lethalBlockWitnessVoid.merge(why, (long) count, Long::sum));
        int lethalN = 0, witN = 0;
        boolean safeOk = false;
        for (int c = 0; c < n; c++) {
            if (lc.lethal[c]) {
                lethalN++;
            }
            if (wit[c]) {
                witN++;
            } else if (ok[c]) {
                safeOk = true;
            }
        }
        if (lethalN == 0) {
            return;
        }
        stats.lethalBlockFlagged++;
        stats.lethalBlockCands += lethalN;
        stats.lethalBlockWitnessed += witN;
        if (witN == 0) {
            return;
        }
        if (wit[0]) {
            stats.lethalBlockDefault++;
        }
        if (!safeOk) {
            stats.lethalBlockAllLethal++;
            return;
        }
        if (cfg.lethalBlock != AiFixes.Mode.ON) {
            return;
        }
        for (int c = 0; c < n; c++) {
            if (wit[c] && ok[c]) {
                Arrays.fill(values[c], 0, k, loss);
            }
        }
        res.lethalRescored = true;
        stats.lethalBlockRescored++;
    }

    /** lethalBlock: the check of one block decision. */
    static final class LethalCheck {
        /** Why the guard stands aside (null = the bound is trusted and {@link #lethal} holds). */
        String aside;
        /** Per candidate: the bound (the prefilter) flags its worst case lethal. */
        boolean[] lethal;
        /** Per candidate: the worst-case life the player loses, and the poison counters it ends with (diagnostics, tests). */
        int[] life, poison;
        /** The mana the attackers' controller has, and the abilities counted for its boost (diagnostics, tests). */
        int mana, abilities;
        /** Round 6: the abilities the bound counted, as lines the witness can play in its copies. */
        final List<LbLine> lines = new ArrayList<>();
        /**
         * Round 6: per candidate, the engine witnessed it lethal ({@link #lethalWitness}), and the line that did (null =
         * not witnessed); null arrays = no witness ran (nothing flagged). The witness's time, lines played, and whether it
         * ran out of budget (the candidates left are not witnessed).
         */
        boolean[] witnessed;
        String[] witnessLine;
        long witnessNanos;
        int witnessRuns;
        boolean witnessCapped;
        /** Round 7: the wall-clock safety cap stopped the witness (else any cap was the line budget); voided runs by reason. */
        boolean witnessTimedOut;
        final Map<String, Integer> witnessVoid = new TreeMap<>();

        static LethalCheck aside(String why) {
            final LethalCheck c = new LethalCheck();
            c.aside = why;
            return c;
        }

        /** Round 7: count a voided witness run by its reason. */
        void noteVoid(WitnessRun r) {
            if (r.voided != null) {
                witnessVoid.merge(r.voided, 1, Integer::sum);
            }
        }
    }

    /**
     * lethalBlock: the check made in copies of the live game (the live game is only read). The seat's knowledge is read
     * in the live game first ({@link #pumpKnownIds}: the copy keeps card ids but not who may look at what); the copies'
     * random, id and AI-cache scopes are their own, as for {@link #forgeBlocks}. {@code ok}: the searched candidates (only
     * those are witnessed; null = all).
     */
    LethalCheck lethalBlocks(Game live, Player liveMe, List<Cand> cands, boolean[] ok) {
        final List<Map<Integer, List<Integer>>> plans = new ArrayList<>(cands.size());
        for (Cand c : cands) {
            if (c.block == null) {
                return LethalCheck.aside("not a block candidate");
            }
            plans.add(c.block.blocks);
        }
        return lethalBlocksOf(live, liveMe, plans, ok);
    }

    /** lethalBlock: {@link #lethalBlocks} for declarations given as blocker card id -> attacker card ids (tests). */
    LethalCheck lethalBlocksOf(Game live, Player liveMe, List<Map<Integer, List<Integer>>> plans) {
        return lethalBlocksOf(live, liveMe, plans, null);
    }

    /** lethalBlock: the bound in a copy, then the engine's witness for each searched candidate it flags. */
    LethalCheck lethalBlocksOf(Game live, Player liveMe, List<Map<Integer, List<Integer>>> plans, boolean[] ok) {
        final Set<Integer> known = pumpKnownIds(live, liveMe);
        final LethalCheck lc;
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new Random(mix(cfg.seed, 0x1e7a1b10cL + decisionIndex)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player me;
            synchronized (live) {
                final GameCopier copier = copierOf(live);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
            }
            final LethalCheck bound = lethalBlocks(g, me, plans, known);
            String liveRepl = null;
            if (bound.aside == null && bound.lethal != null) {
                // round 9: the replacement prefilter on the live game as well
                synchronized (live) {
                    final forge.game.combat.Combat lcmb = live.getPhaseHandler().getCombat();
                    if (lcmb != null && !lcmb.getAttackersOf(liveMe).isEmpty()) {
                        liveRepl = lbReplacement(live, liveMe, new ArrayList<>(lcmb.getAttackersOf(liveMe)), known, true);
                    }
                }
            }
            lc = liveRepl != null ? LethalCheck.aside(liveRepl) : bound;
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        if (lc.aside == null && lc.lethal != null) {
            boolean any = false;
            for (int p = 0; p < lc.lethal.length; p++) {
                any |= lc.lethal[p] && (ok == null || p < ok.length && ok[p]);
            }
            if (any) {
                // round 7: the witness must not depend on, or touch, anything the seat cannot see; where the sandbox cannot
                // guarantee that, there is no witness: the whole check stands aside
                final String fd = lbHiddenFaceDown(live, known);
                if (fd != null) {
                    return LethalCheck.aside(fd);
                }
                final List<String> gaps;
                synchronized (live) {
                    gaps = GameCopier.unsupportedPending(live);
                }
                if (gaps != null) {
                    return LethalCheck.aside("pending state the copy cannot carry");
                }
                lethalWitness(live, liveMe, plans, known, lc, ok);
            } else {
                lc.witnessed = new boolean[lc.lethal.length];
                lc.witnessLine = new String[lc.lethal.length];
            }
        }
        return lc;
    }

    /**
     * lethalBlock (tests): the witness alone, for {@code lines} given by the test (as if the bound had counted them), on
     * every candidate (as if the bound had flagged each). The live game is only read.
     */
    LethalCheck lethalWitnessOf(Game live, Player liveMe, List<Map<Integer, List<Integer>>> plans, List<LbLine> lines) {
        final LethalCheck lc = new LethalCheck();
        lc.lethal = new boolean[plans.size()];
        Arrays.fill(lc.lethal, true);
        lc.lines.addAll(lines);
        lethalWitness(live, liveMe, plans, pumpKnownIds(live, liveMe), lc, null);
        return lc;
    }

    // ------------------------------------------------------------------ lethalBlock round 6: the engine's witness

    /**
     * lethalBlock witness: the budget per decision is {@link #WITNESS_LINES} runs (each line in its own copy, the
     * placeholder confirmation included), so a position gives the same witnesses wherever it runs; {@link #WITNESS_MS}
     * is only a wall-clock safety cap (round 7, review S2), checked before each run and after each engine step of a run.
     * An ability's most repeats.
     */
    static final int WITNESS_LINES = 32;
    static final long WITNESS_MS = 1000;
    static final int WITNESS_REPEATS = 30;
    /** This search's witness budget (the defaults above; tests that try every ability may raise it, never the guard). */
    int witnessLineCap = WITNESS_LINES;
    long witnessWallMs = WITNESS_MS;

    /**
     * lethalBlock: one ability the bound counted, as the witness plays it in a copy: its host (card id, which the copies
     * keep), its place among {@code getAllPossibleAbilities} of the attackers' controller and its description (checked
     * when it is found again), whether it has X, how often it may repeat, and the attackers it may target (one line per
     * target; none = it does not target).
     */
    static final class LbLine {
        int hostId, ordinal, cap = 1;
        String host, desc;
        boolean x, targeted;
        final List<Integer> targets = new ArrayList<>();

        String label(int target, Game g, int xv, int times) {
            final Card t = target < 0 || g == null ? null : g.findById(target);
            return host + ": " + desc + (x ? " [X=" + xv + "]" : "") + (times > 1 ? " x" + times : "")
                    + (t != null ? " -> " + t.getName() + " (" + target + ")" : "");
        }
    }

    /** lethalBlock: {@code sa} of {@code host} (at {@code ordinal} of its possible abilities) as a witness line. */
    static LbLine lbLine(Card host, int ordinal, SpellAbility sa, LbAbility ab, List<Card> atk) {
        final LbLine l = new LbLine();
        l.hostId = host.getId();
        l.ordinal = ordinal;
        l.host = host.getName();
        l.desc = String.valueOf(sa.getDescription());
        l.x = ab.xPer > 0;
        l.cap = Math.max(1, Math.min(ab.cap, WITNESS_REPEATS));
        l.targeted = singleTargetSlot(sa) != null;
        if (l.targeted) {
            for (int i = 0; i < atk.size(); i++) {
                boolean hit = ab.ds[i] || ab.trample[i] || ab.deathtouch[i];
                for (LbPart part : ab.parts) {
                    hit |= part.hit != null && i < part.hit.length && part.hit[i];
                }
                if (hit) {
                    l.targets.add(atk.get(i).getId());
                }
            }
        }
        return l;
    }

    /**
     * lethalBlock (tests): every non-mana ability of {@code host} that {@code opp} may play now, as lines that may target
     * {@code targets} (none: untargeted), each repeated up to {@code cap} times while payable (X: once, the most the engine
     * can pay).
     */
    static List<LbLine> lbLinesFor(Card host, Player opp, List<Card> targets, int cap) {
        final List<LbLine> out = new ArrayList<>();
        final List<SpellAbility> all = host.getAllPossibleAbilities(opp, true);
        for (int j = 0; j < all.size(); j++) {
            final SpellAbility sa = all.get(j);
            if (sa.isManaAbility() || sa.isLandAbility()) {
                continue;
            }
            final LbLine l = new LbLine();
            l.hostId = host.getId();
            l.ordinal = j;
            l.host = host.getName();
            l.desc = String.valueOf(sa.getDescription());
            l.cap = Math.max(1, cap);
            l.x = sa.getPayCosts() != null && sa.getPayCosts().hasManaCost() && sa.getPayCosts().getTotalMana().countX() > 0;
            l.targeted = singleTargetSlot(sa) != null;
            if (l.targeted) {
                for (Card t : targets) {
                    l.targets.add(t.getId());
                }
            }
            out.add(l);
        }
        return out;
    }

    /** lethalBlock (tests): the first of {@link #lbLinesFor} for {@code host}, played once. */
    static LbLine lbLineFor(Card host, Player opp, List<Card> targets) {
        final List<LbLine> all = lbLinesFor(host, opp, targets, 1);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("no ability on " + host);
        }
        return all.get(0);
    }

    /**
     * lethalBlock round 6: for each candidate the bound flags (and {@code ok} searched), search sandboxed copies of the
     * live game for a line that kills the seat: (a) nothing; (b) each ability the bound counted ({@link LethalCheck#lines})
     * alone, once per attacker it may target (or once), X at the most the engine can pay (one less again if the payment
     * fails), a repeatable one repeated while payable up to the bound's cap. Single abilities only: a combo the bound
     * needed is not witnessed (an undercount, never a false lethal). Each line runs in its own copy ({@link #witnessRun}).
     *
     * <p>Round 7 (review S2): the budget is at most {@link #witnessLineCap} runs per decision, the primary budget, so a
     * position gives the same witnesses wherever it runs; {@link #witnessWallMs} is only a safety cap, checked before each
     * run and after each engine step of a run (a run it stops is not witnessed). The candidates left are not witnessed. A
     * run can still overrun the cap by one copy, one placeholder setup, or one engine step (an AI decision, a payment or a
     * resolution), none of which is interrupted.
     */
    void lethalWitness(Game live, Player liveMe, List<Map<Integer, List<Integer>>> plans, Set<Integer> known, LethalCheck lc,
            boolean[] ok) {
        final int n = plans.size();
        lc.witnessed = new boolean[n];
        lc.witnessLine = new String[n];
        final long t0 = System.nanoTime();
        final long deadline = t0 + witnessWallMs * 1_000_000L;
        final String liveKey = lbFidelityKeyOfLive(live);
        int runs = 0;
        for (int p = 0; p < n && !lc.witnessCapped; p++) {
            if (!lc.lethal[p] || ok != null && (p >= ok.length || !ok[p])) {
                continue;
            }
            final List<Object[]> lines = new ArrayList<>(); // {LbLine or null, target id}
            lines.add(new Object[] {null, -1});
            for (LbLine l : lc.lines) {
                if (l.targeted) {
                    for (int t : l.targets) {
                        lines.add(new Object[] {l, t});
                    }
                } else {
                    lines.add(new Object[] {l, -1});
                }
            }
            for (Object[] line : lines) {
                if (lc.witnessed[p]) {
                    break;
                }
                int xCap = Integer.MAX_VALUE;
                while (true) {
                    if (runs >= witnessLineCap) {
                        lc.witnessCapped = true;
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        lc.witnessCapped = true;
                        lc.witnessTimedOut = true;
                        break;
                    }
                    final WitnessRun r = witnessRun(live, liveMe, plans.get(p), known, (LbLine) line[0], (Integer) line[1], xCap,
                            mix(cfg.seed, 0x5717e55L + 1009L * decisionIndex + runs), liveKey, deadline);
                    runs++;
                    lc.noteVoid(r);
                    if (r.witnessed) {
                        lc.witnessed[p] = true;
                        lc.witnessLine[p] = r.label;
                        break;
                    }
                    if (r.timedOut) {
                        lc.witnessCapped = true;
                        lc.witnessTimedOut = true;
                        break;
                    }
                    if (!r.payFailed || r.x <= 0) {
                        break;
                    }
                    xCap = r.x - 1; // the engine could not pay this X after all: one less, in a fresh copy
                }
                if (lc.witnessCapped) {
                    break;
                }
            }
        }
        lc.witnessRuns = runs;
        lc.witnessNanos = System.nanoTime() - t0;
    }

    /** lethalBlock: one witness line's outcome. {@code voided}: why the run cannot witness (null = it ran faithfully). */
    static final class WitnessRun {
        boolean witnessed, payFailed, timedOut;
        int x = -1, played;
        String label, error, voided;
    }

    /**
     * lethalBlock: the opaque card the witness puts where the seat cannot see (unseen hand cards, every library), built on
     * first use (nothing is built while the guard is off): "hidden", no types, no cost, no colors, no abilities. No
     * placeholder is neutral for every reader, so a run that moves, reveals or offers one is void ({@link WitnessWatch}).
     */
    private static final class LbPlaceholder {
        static final forge.item.PaperCard CARD = new forge.item.PaperCard(
                forge.card.CardRules.fromScript(java.util.Arrays.asList("Name:hidden", "Types:", "Oracle:")), "",
                forge.card.CardRarity.Common);
    }

    /**
     * lethalBlock: the copy {@code g} forgets what the seat cannot know before anything is played in it. Round 7: each
     * hand card the seat does not know ({@code known}; the attacking player's, and any other player's) becomes a
     * placeholder in its place (the hand size, a public count, stays). Round 8 (review S1 1): the same in every library,
     * card by card, so a card the seat knows there (a revealed top) stays itself. Every placeholder is recorded in the
     * returned watch, in its zone order.
     */
    static WitnessWatch lbHide(Game g, Set<Integer> known) {
        final WitnessWatch w = new WitnessWatch();
        for (Player p : g.getPlayers()) {
            lbHideZone(g, p, ZoneType.Hand, known, w);
            lbHideZone(g, p, ZoneType.Library, known, w);
        }
        w.snapLibraries(g);
        return w;
    }

    private static void lbHideZone(Game g, Player p, ZoneType z, Set<Integer> known, WitnessWatch w) {
        final List<Card> cards = new ArrayList<>();
        final List<Card> blanks = new ArrayList<>();
        for (Card c : p.getCardsIn(z)) {
            if (known.contains(c.getId())) {
                cards.add(c);
            } else {
                final Card b = forge.game.card.CardFactory.getCard(LbPlaceholder.CARD, p, g.nextCardId(), g);
                cards.add(b);
                blanks.add(b);
            }
        }
        if (!blanks.isEmpty()) {
            p.getZone(z).setCards(cards);
            for (Card b : blanks) {
                w.add(p, z, b);
            }
        }
    }

    /**
     * lethalBlock: a face-down card on the battlefield or in exile whose face the seat cannot see (round 7, review S1 3):
     * its identity can change what a line does (sacrificed, it is a Forest or Emrakul), and the witness does not scrub
     * it. Null when there is none.
     */
    static String lbHiddenFaceDown(Game live, Set<Integer> known) {
        for (Player p : live.getPlayers()) {
            for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Exile}) {
                for (Card c : p.getCardsIn(z)) {
                    if (c.isFaceDown() && !known.contains(c.getId())) {
                        return "a face-down card the seat cannot identify";
                    }
                }
            }
        }
        return null;
    }

    /**
     * lethalBlock: what a faithful copy keeps of the live game (round 7, review S1 4), compared between the live game and
     * each fresh witness copy before anything changes in it: turn, step, the active player; each player's life, poison,
     * counters, mana pool and per-turn counts (lands, spells, draws, life lost and gained); each zone's cards in order (id, name, state, tapped, sickness, face-down,
     * phased out, token, controller, owner; in play: power, toughness, damage, counters, attachment); the combat; the
     * stack; the delayed triggers. Reads plain fields only (no AI or lazy computation on the live game).
     */
    static String lbFidelityKey(Game g) {
        final StringBuilder sb = new StringBuilder(4096);
        final PhaseHandler ph = g.getPhaseHandler();
        final List<Player> ps = g.getPlayers();
        sb.append('T').append(ph.getTurn()).append(' ').append(ph.getPhase()).append(" pt").append(ps.indexOf(ph.getPlayerTurn()))
                .append(" over").append(g.isGameOver()).append('\n');
        for (Player p : ps) {
            sb.append('P').append(ps.indexOf(p)).append(" life").append(p.getLife()).append(" poison").append(p.getPoisonCounters())
                    .append(" lost").append(p.hasLost()).append(" lands").append(p.getLandsPlayedThisTurn())
                    .append(" spells").append(p.getSpellsCastThisTurn()).append(" drawn").append(p.getNumDrawnThisTurn())
                    .append(" lost").append(p.getLifeLostThisTurn()).append(" gained").append(p.getLifeGainedThisTurn())
                    .append(" n").append(new TreeMap<>(counterMap(p)));
            final List<String> mana = new ArrayList<>();
            for (forge.game.mana.Mana m : p.getManaPool()) {
                mana.add(m.toString() + "@" + (m.getSourceCard() == null ? -1 : m.getSourceCard().getId()));
            }
            Collections.sort(mana);
            sb.append(" mana").append(mana).append('\n');
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Battlefield, ZoneType.Command}) {
                sb.append(' ').append(z).append(':');
                for (Card c : p.getCardsIn(z)) {
                    sb.append(" {").append(c.getId()).append(' ').append(c.getName()).append(' ').append(c.getCurrentStateName())
                            .append(c.isTapped() ? " T" : " U").append(c.isFirstTurnControlled() ? " s" : "").append(c.isFaceDown() ? " fd" : "")
                            .append(c.isPhasedOut() ? " po" : "").append(c.isToken() ? " tok" : "")
                            .append(" c").append(ps.indexOf(c.getController())).append(" o").append(ps.indexOf(c.getOwner()));
                    if (z == ZoneType.Battlefield) {
                        sb.append(" pt").append(c.getNetPower()).append('/').append(c.getNetToughness()).append(" d").append(c.getDamage());
                        if (c.getEntityAttachedTo() instanceof Card) {
                            sb.append(" at").append(((Card) c.getEntityAttachedTo()).getId());
                        } else if (c.getEntityAttachedTo() instanceof Player) {
                            sb.append(" atP").append(ps.indexOf(c.getEntityAttachedTo()));
                        }
                    }
                    final Map<String, Integer> cn = counterMap(c);
                    if (!cn.isEmpty()) {
                        sb.append(" n").append(new TreeMap<>(cn));
                    }
                    sb.append('}');
                }
                sb.append('\n');
            }
        }
        final forge.game.combat.Combat combat = ph.getCombat();
        if (combat != null) {
            sb.append("combat");
            for (Card a : combat.getAttackers()) {
                final forge.game.GameEntity d = combat.getDefenderByAttacker(a);
                sb.append(' ').append(a.getId()).append('>').append(d instanceof Card ? "C" + ((Card) d).getId()
                        : d instanceof Player ? "P" + ps.indexOf(d) : String.valueOf(d));
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
        sb.append("stack").append(g.getStack().size());
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
            sb.append(' ').append(si.getSourceCard() == null ? -1 : si.getSourceCard().getId());
        }
        final forge.game.trigger.TriggerHandler th = g.getTriggerHandler();
        sb.append(" delayed").append(th.getDelayedTriggersView().size()).append('/').append(th.getPlayerDefinedDelayedTriggersView().size());
        return sb.toString();
    }

    /** Separates {@link #lbFidelityKey} from {@link #lbPublicKey} in the live game's key. */
    static final String LB_PUBLIC = "\n#public\n";

    /**
     * {@link #lbFidelityKey} and {@link #lbPublicKey} of the live game (joined by {@link #LB_PUBLIC}), read under its lock
     * in scratch id, AI-cache and random scopes.
     */
    static String lbFidelityKeyOfLive(Game live) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new Random(0x5717e55L));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            synchronized (live) {
                return lbFidelityKey(live) + LB_PUBLIC + lbPublicKey(live);
            }
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * lethalBlock round 8 (review S1 1-2): every public characteristic of the objects in play, compared between the live
     * game and each witness copy after it hid what the seat cannot see and recomputed static abilities: each battlefield
     * permanent's name, state, types, controller, tapped, face-down, net power and toughness, damage marked and assigned,
     * counters, attachment, keywords (static grants included), loyalty, whether it attacks (and whom) or blocks (and
     * what); each public command-zone and face-up exile object's name, state, types and counters; and, between each
     * attacker and each creature of another player, protection either way and whether it can block. A copier that drops
     * a choice (a chosen parity, and the protection it gave) or a static ability that reads a placeholder shows here.
     * Nothing hidden is read (no hand or library), so a difference never depends on a hidden identity. Reads cached and
     * computed characteristics only (no lazily built traits).
     */
    static String lbPublicKey(Game g) {
        final StringBuilder sb = new StringBuilder(4096);
        final List<Player> ps = g.getPlayers();
        final forge.game.combat.Combat combat = g.getPhaseHandler().getCombat();
        final List<Card> creatures = new ArrayList<>();
        for (Player p : ps) {
            sb.append('P').append(ps.indexOf(p));
            for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Command, ZoneType.Exile}) {
                sb.append(' ').append(z).append(':');
                for (Card c : p.getCardsIn(z)) {
                    if (z != ZoneType.Battlefield && c.isFaceDown()) {
                        continue;
                    }
                    sb.append(" {").append(c.getId()).append(' ').append(c.getName()).append(' ').append(c.getCurrentStateName())
                            .append(" [").append(c.getType()).append("] c").append(ps.indexOf(c.getController()));
                    if (z != ZoneType.Exile) {
                        sb.append(lbChoiceKey(c, ps));
                    }
                    if (z == ZoneType.Battlefield) {
                        sb.append(c.isTapped() ? " T" : " U").append(c.isFaceDown() ? " fd" : "").append(" pt").append(c.getNetPower())
                                .append('/').append(c.getNetToughness()).append(" d").append(c.getDamage()).append('+')
                                .append(c.getTotalAssignedDamage());
                        if (c.isPlaneswalker()) {
                            sb.append(" loy").append(c.getCurrentLoyalty());
                        }
                        if (c.getEntityAttachedTo() instanceof Card) {
                            sb.append(" at").append(((Card) c.getEntityAttachedTo()).getId());
                        } else if (c.getEntityAttachedTo() instanceof Player) {
                            sb.append(" atP").append(ps.indexOf(c.getEntityAttachedTo()));
                        }
                        final List<String> kws = new ArrayList<>();
                        for (forge.game.keyword.KeywordInterface k : c.getKeywords()) {
                            kws.add(k.getOriginal());
                        }
                        Collections.sort(kws);
                        sb.append(" kw").append(kws);
                        if (combat != null) {
                            final forge.game.GameEntity d = combat.getDefenderByAttacker(c);
                            if (d != null) {
                                sb.append(" atk>").append(d instanceof Card ? "C" + ((Card) d).getId() : "P" + ps.indexOf(d));
                                for (Card b : combat.getBlockers(c)) {
                                    sb.append(" by").append(b.getId());
                                }
                            }
                            for (Card a : combat.getAttackersBlockedBy(c)) {
                                sb.append(" blk").append(a.getId());
                            }
                        }
                        if (c.isCreature()) {
                            creatures.add(c);
                        }
                    }
                    final Map<String, Integer> cn = counterMap(c);
                    if (!cn.isEmpty()) {
                        sb.append(" n").append(new TreeMap<>(cn));
                    }
                    sb.append('}');
                }
            }
            sb.append('\n');
        }
        if (combat != null) {
            sb.append("vs");
            for (Card a : combat.getAttackers()) {
                for (Card b : creatures) {
                    if (b.getController() == a.getController()) {
                        continue;
                    }
                    sb.append(' ').append(a.getId()).append('/').append(b.getId()).append(lbProtected(b, a) ? 'p' : '-')
                            .append(lbProtected(a, b) ? 'q' : '-').append(forge.game.combat.CombatUtil.canBlock(a, b) ? 'b' : '-');
                }
            }
        }
        return sb.toString();
    }

    /**
     * lethalBlock round 9 (review S1 1-2): the choices announced for a public object (a permanent, a command-zone effect
     * or emblem) and the counts of its abilities, for {@link #lbPublicKey}: chosen type(s), noted types, colors, marked
     * colors, named cards, number, player, promised gift, protected player, even or odd, direction, mode, room, sectors,
     * sprocket, chosen cards and per-player chosen cards, imprinted and remembered objects; for a command-zone object its
     * source; and the number of its static abilities, triggers and replacement effects (static grants included). The
     * replacement effects are counted only where reading them builds nothing ({@link #lbReplacementsReadable}); else
     * {@code re?}, the same in every game.
     */
    static String lbChoiceKey(Card c, List<Player> ps) {
        final StringBuilder sb = new StringBuilder();
        lbKv(sb, "type", c.getChosenType());
        lbKv(sb, "type2", c.getChosenType2());
        lbKv(sb, "noted", String.join(",", c.getNotedTypes()));
        lbKv(sb, "colors", String.join(",", c.getChosenColors()));
        final forge.card.ColorSet marked = c.getMarkedColors();
        lbKv(sb, "marked", marked == null || marked.isColorless() ? "" : marked.toString());
        lbKv(sb, "named", String.join(",", c.getNamedCards()));
        lbKv(sb, "number", c.getChosenNumber() == null ? "" : String.valueOf(c.getChosenNumber()));
        lbKv(sb, "player", lbWho(c.getChosenPlayer(), ps));
        lbKv(sb, "gift", lbWho(c.getPromisedGift(), ps));
        lbKv(sb, "protecting", lbWho(c.getProtectingPlayer(), ps));
        lbKv(sb, "evenodd", c.getChosenEvenOdd() == null ? "" : c.getChosenEvenOdd().toString());
        lbKv(sb, "direction", c.getChosenDirection() == null ? "" : c.getChosenDirection().toString());
        lbKv(sb, "mode", c.getChosenMode());
        lbKv(sb, "room", c.getCurrentRoom());
        lbKv(sb, "sector", c.getSector());
        lbKv(sb, "chosenSector", c.getChosenSector());
        lbKv(sb, "sprocket", c.getSprocket() == 0 ? "" : String.valueOf(c.getSprocket()));
        lbKv(sb, "cards", lbObjects(c.getChosenCards(), ps));
        if (!c.getChosenMap().isEmpty()) {
            final Map<Integer, String> m = new TreeMap<>();
            for (Map.Entry<Player, forge.game.card.CardCollection> e : c.getChosenMap().entrySet()) {
                m.put(ps.indexOf(e.getKey()), lbObjects(e.getValue(), ps));
            }
            lbKv(sb, "map", m.toString());
        }
        lbKv(sb, "imprinted", lbObjects(c.getImprintedCards(), ps));
        lbKv(sb, "remembered", lbObjects(c.getRemembered(), ps));
        if (c.isInZone(ZoneType.Command)) {
            final Card src = c.getEffectSource();
            lbKv(sb, "source", src == null ? "" : src.getId() + " " + src.getName());
        }
        sb.append(" st").append(c.getStaticAbilities().size()).append(" tr").append(c.getTriggers().size()).append(" re")
                .append(lbReplacementsReadable(c) ? String.valueOf(c.getCurrentState().getReplacementEffects(false).size()) : "?");
        return sb.toString();
    }

    private static void lbKv(StringBuilder sb, String k, String v) {
        if (v != null && !v.isEmpty()) {
            sb.append(' ').append(k).append('=').append(v);
        }
    }

    private static String lbWho(Player p, List<Player> ps) {
        return p == null ? "" : "P" + ps.indexOf(p);
    }

    /** Objects as ids (cards), seat indexes (players) or kinds (anything else), sorted. */
    private static String lbObjects(Iterable<?> objects, List<Player> ps) {
        if (objects == null) {
            return "";
        }
        final List<String> out = new ArrayList<>();
        for (Object o : objects) {
            out.add(o instanceof Card ? "C" + ((Card) o).getId() : o instanceof Player ? "P" + ps.indexOf(o)
                    : o == null ? "null" : o.getClass().getSimpleName());
        }
        Collections.sort(out);
        return String.join(",", out);
    }

    /**
     * Reading {@code c}'s replacement effects without the rules-host ones builds nothing: it is not a planeswalker, a battle
     * or a saga (whose loyalty, defense and lore replacements are built on first read).
     */
    static boolean lbReplacementsReadable(Card c) {
        final forge.card.CardTypeView t = c.getCurrentState().getTypeWithChanges();
        return !t.isPlaneswalker() && !t.isBattle() && !t.isSaga();
    }

    /** {@code c} has protection from {@code source}: one of its protection keywords prevents {@code source}'s damage. */
    static boolean lbProtected(Card c, Card source) {
        for (forge.game.keyword.KeywordInterface k : c.getKeywords(forge.game.keyword.Keyword.PROTECTION)) {
            for (forge.game.replacement.ReplacementEffect re : k.getReplacements()) {
                if (re.matchesValidParam("ValidSource", source)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first line where two fidelity keys differ (a diagnostic). */
    static String lbFirstDiff(String a, String b) {
        final String[] x = a.split("\n"), y = b.split("\n");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final String u = i < x.length ? x[i] : "", v = i < y.length ? y[i] : "";
            if (!u.equals(v)) {
                return "live[" + u + "] copy[" + v + "]";
            }
        }
        return "";
    }

    private static Map<String, Integer> counterMap(forge.game.GameEntity e) {
        final Map<String, Integer> m = new java.util.HashMap<>();
        for (com.google.common.collect.Multiset.Entry<forge.game.card.CounterType> x : e.getCounters().entrySet()) {
            m.put(String.valueOf(x.getElement()), x.getCount());
        }
        return m;
    }

    /**
     * lethalBlock: one witness line in its own copy of {@code live} (own id scope, AI-cache scope and random stream, all
     * restored; the live game is only read, under its lock). Round 7: the copy carries this turn's state (copy fidelity 1:
     * activations and once-per-turn limits, spells cast, zone entries; a record it leaves out voids the run) and everything
     * pending, strictly (a delayed trigger or command it cannot carry fails the copy: no witness), and must match the live
     * game on {@link #lbFidelityKey} before anything changes in it (else no witness). The copy forgets what the seat cannot know
     * ({@link #lbHide}), is rewound to the end of declare attackers (as a block play-out), and advances into declare
     * blockers, where the seat declares {@code plan}. Assumption: the seat then only passes (it does not respond). The
     * attackers' controller plays {@code line} with its first priority there (Forge AI pays the costs and makes the choices
     * on resolution), then passes, and assigns combat damage by the rules alone ({@link WitnessAi#assignCombatDamage}).
     * The engine runs through combat damage. Witnessed: the seat has lost, by life or poison, the attackers' controller
     * has not, and no placeholder was moved, revealed or offered as a choice ({@link WitnessWatch}).
     */
    WitnessRun witnessRun(Game live, Player liveMe, Map<Integer, List<Integer>> plan, Set<Integer> known, LbLine line,
            int target, int xCap, long seed) {
        final String liveKey = lbFidelityKeyOfLive(live);
        return witnessRun(live, liveMe, plan, known, line, target, xCap, seed, liveKey,
                System.nanoTime() + witnessWallMs * 1_000_000L);
    }

    WitnessRun witnessRun(Game live, Player liveMe, Map<Integer, List<Integer>> plan, Set<Integer> known, LbLine line,
            int target, int xCap, long seed, String liveKey, long deadline) {
        final WitnessRun r = new WitnessRun();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new Random(seed));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player me;
            final int leftOut;
            try {
                synchronized (live) {
                    final GameCopier copier = copierOf(live);
                    copier.setCopyFidelity(1);
                    copier.setCopyPending(true);
                    copier.setPendingStrict(true);
                    g = copier.makeCopy();
                    me = (Player) copier.find(liveMe);
                    leftOut = copier.getFidelityLeftOut();
                }
            } catch (IllegalStateException e) {
                if (!String.valueOf(e.getMessage()).startsWith("pending gap")) {
                    throw e;
                }
                r.voided = "pending gap";
                r.error = String.valueOf(e);
                return r;
            }
            if (leftOut > 0) {
                r.voided = "turn state the copy left out";
                return r;
            }
            final PhaseHandler ph = g.getPhaseHandler();
            final forge.game.combat.Combat c = ph.getCombat();
            if (c == null || me == null) {
                r.voided = "no combat in the copy";
                return r;
            }
            final int cut = liveKey.indexOf(LB_PUBLIC);
            final String liveState = cut < 0 ? liveKey : liveKey.substring(0, cut);
            final String livePublic = cut < 0 ? null : liveKey.substring(cut + LB_PUBLIC.length());
            final String copyKey = lbFidelityKey(g);
            if (!copyKey.equals(liveState)) {
                r.voided = "copy differs from the live game";
                r.error = lbFirstDiff(liveState, copyKey);
                return r;
            }
            final Player opp = c.getAttackingPlayer();
            final WitnessWatch watch = lbHide(g, known);
            // round 8: with what the seat cannot see hidden and static abilities recomputed, every public characteristic
            // must still be the live game's (a dropped choice, or a static reading a placeholder, differs)
            g.getAction().checkStaticAbilities(false);
            final String copyPublic = lbPublicKey(g);
            if (livePublic == null || !copyPublic.equals(livePublic)) {
                r.voided = "a public characteristic differs";
                r.error = livePublic == null ? "no public key" : lbFirstDiff(livePublic, copyPublic);
                return r;
            }
            final BlockPlan bp = new BlockPlan("witness");
            bp.blocks.putAll(plan);
            BlockPlan.clear(c, me);
            final Player active = ph.getPlayerTurn();
            final Player other = g.getNextPlayerAfter(active);
            ph.devModeSet(forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS, active, false, ph.getTurn());
            setPriorityState(ph, other, active, false);
            final WitnessSeat seat = new WitnessSeat(g, me, me.getController().getLobbyPlayer(), bp, watch);
            me.dangerouslySetController(seat);
            final WitnessAi wa = new WitnessAi(g, opp, opp.getController().getLobbyPlayer(), line, target, xCap, watch);
            opp.dangerouslySetController(wa);
            for (Player o : g.getPlayers()) {
                if (o != me && o != opp) {
                    o.dangerouslySetController(new WitnessPass(g, o, o.getController().getLobbyPlayer(), watch));
                }
            }
            g.subscribeToEvents(watch);
            final int turn = ph.getTurn();
            for (int steps = 0; steps < 400 && !g.isGameOver(); steps++) {
                if (ph.getTurn() != turn || ph.getPhase() == null
                        || ph.getPhase().ordinal() > forge.game.phase.PhaseType.COMBAT_DAMAGE.ordinal()) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    r.timedOut = true;
                    r.voided = "time";
                    break;
                }
                ph.mainLoopStep();
            }
            if (!g.isGameOver()) {
                g.getAction().checkStateEffects(true);
            }
            watch.verifyZones();
            r.played = wa.played;
            r.x = wa.x;
            r.payFailed = wa.failed;
            if (r.voided == null && watch.touched != null) {
                r.voided = watch.touched;
            }
            if (r.voided == null && wa.voided != null) {
                r.voided = wa.voided;
            }
            if (r.voided == null && wa.illegalTarget) {
                r.voided = "illegal target";
            }
            final forge.game.player.PlayerOutcome mo = me.getOutcome();
            final boolean byDamage = mo != null && (mo.lossState == forge.game.player.GameLossReason.LifeReachedZero
                    || mo.lossState == forge.game.player.GameLossReason.Poisoned);
            r.witnessed = r.voided == null && seat.applied && me.hasLost() && byDamage && !opp.hasLost()
                    && (line == null || wa.played > 0);
            r.label = line == null ? "nothing" : line.label(target, g, wa.x, wa.played);
        } catch (RuntimeException | StackOverflowError e) {
            r.error = String.valueOf(e);
            r.voided = "error";
            r.witnessed = false;
            final StackTraceElement[] at = e.getStackTrace();
            System.err.println("[lookahead] lethalBlock witness line failed, not witnessed: " + e + (at.length > 0 ? " at " + at[0] : ""));
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    /**
     * lethalBlock (round 7, review S1 1-2): the placeholders of one witness copy and whether the run touched one. A
     * placeholder moved out of its zone or reordered in it, a shuffled, scried or surveilled library, a placeholder
     * revealed to a player or offered to one as a choice (a search, a look, a discard): each voids the run, since the
     * real card there may differ.
     */
    public static final class WitnessWatch {
        /** Round 9 (review S1 3): each library's size and top card at the start of the run. */
        private final Map<forge.game.zone.Zone, String> libraries = new java.util.IdentityHashMap<>();
        private final Set<Integer> ids = new HashSet<>();
        private final Map<forge.game.zone.Zone, List<Card>> order = new java.util.IdentityHashMap<>();
        private final Map<forge.game.zone.Zone, Player> owners = new java.util.IdentityHashMap<>();
        /** Why the run touched a placeholder (the first reason), or null. */
        String touched;

        void add(Player p, ZoneType z, Card c) {
            ids.add(c.getId());
            final forge.game.zone.Zone zone = p.getZone(z);
            order.computeIfAbsent(zone, x -> new ArrayList<>()).add(c);
            owners.put(zone, p);
        }

        boolean isPlaceholder(int id) {
            return ids.contains(id);
        }

        /** Records each player's library (its size and top card) as the run starts. */
        void snapLibraries(Game g) {
            for (Player p : g.getPlayers()) {
                final forge.game.zone.Zone z = p.getZone(ZoneType.Library);
                libraries.put(z, libraryState(z));
            }
        }

        private static String libraryState(forge.game.zone.Zone z) {
            final CardCollectionView cards = z.getCards();
            return cards.size() + ":" + (cards.isEmpty() ? -1 : cards.get(0).getId());
        }

        void touch(String why) {
            if (touched == null) {
                touched = why;
            }
        }

        /** Some of {@code cards} are placeholders: {@code why}. */
        void check(Iterable<?> cards, String why) {
            if (cards == null) {
                return;
            }
            for (Object o : cards) {
                final int id = o instanceof Card ? ((Card) o).getId() : o instanceof forge.game.card.CardView
                        ? ((forge.game.card.CardView) o).getId() : Integer.MIN_VALUE;
                if (ids.contains(id)) {
                    touch(why);
                    return;
                }
            }
        }

        /** Each library is as it was (its size and top card), and each zone keeps its placeholders, in their order. */
        void verifyZones() {
            for (Map.Entry<forge.game.zone.Zone, String> e : libraries.entrySet()) {
                if (!libraryState(e.getKey()).equals(e.getValue())) {
                    touch("a library changed");
                    return;
                }
            }
            for (Map.Entry<forge.game.zone.Zone, List<Card>> e : order.entrySet()) {
                final List<Card> now = new ArrayList<>();
                for (Card c : e.getKey().getCards()) {
                    if (ids.contains(c.getId())) {
                        now.add(c);
                    }
                }
                if (!now.equals(e.getValue())) {
                    touch("a placeholder moved");
                    return;
                }
            }
        }

        @Subscribe
        public void on(forge.game.event.GameEventCardChangeZone e) {
            if (e.from() != null && e.from().zoneType() == ZoneType.Library || e.to() != null && e.to().zoneType() == ZoneType.Library) {
                touch("a library changed"); // round 9: any card into or out of any library (a draw, a mill, a put)
            } else if (e.card() != null && ids.contains(e.card().getId())) {
                touch("a placeholder moved");
            }
        }

        @Subscribe
        public void on(forge.game.event.GameEventShuffle e) {
            touch("a library was shuffled");
        }

        @Subscribe
        public void on(forge.game.event.GameEventScry e) {
            touch("a library was looked at");
        }

        @Subscribe
        public void on(forge.game.event.GameEventSurveil e) {
            touch("a library was looked at");
        }
    }

    /**
     * lethalBlock: a witness copy's controller (Forge AI) that reports to its {@link WitnessWatch} each placeholder it is
     * shown or offered: reveals, effect choices, scry and surveil, zone-change choices, discards, move orders.
     */
    static class WitnessController extends PlayerControllerAi {
        final WitnessWatch watch;

        WitnessController(Game g, Player p, forge.LobbyPlayer lp, WitnessWatch watch) {
            super(g, p, lp);
            this.watch = watch;
        }

        private void seen(Iterable<?> cards, String why) {
            if (watch != null) {
                watch.check(cards, why);
            }
        }

        @Override
        public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
            seen(cards, "a placeholder was revealed");
            super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        }

        @Override
        public void reveal(List<forge.game.card.CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix,
                boolean addSuffix) {
            seen(cards, "a placeholder was revealed");
            super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        }

        @Override
        public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min,
                int max, boolean isOptional, Map<String, Object> params) {
            seen(sourceList, "a placeholder was offered");
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }

        @Override
        public <T extends forge.game.GameEntity> T chooseSingleEntityForEffect(forge.util.collect.FCollectionView<T> optionList,
                forge.game.player.DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional,
                Player targetedPlayer, Map<String, Object> params) {
            seen(optionList, "a placeholder was offered");
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }

        @Override
        public <T extends forge.game.GameEntity> List<T> chooseEntitiesForEffect(forge.util.collect.FCollectionView<T> optionList,
                int min, int max, forge.game.player.DelayedReveal delayedReveal, SpellAbility sa, String title,
                Player targetedPlayer, Map<String, Object> params) {
            seen(optionList, "a placeholder was offered");
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
            seen(topN, "a library was looked at");
            return super.arrangeForScry(topN);
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
            seen(topN, "a library was looked at");
            return super.arrangeForSurveil(topN);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
            seen(cards, "a placeholder was offered");
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }

        @Override
        public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards,
                int min, int max, CardCollectionView visibleToChooser) {
            seen(validCards, "a placeholder was offered");
            return super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                CardCollection fetchList, forge.game.player.DelayedReveal delayedReveal, String selectPrompt, boolean isOptional,
                Player decider) {
            seen(fetchList, "a placeholder was offered");
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional,
                    decider);
        }

        @Override
        public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                CardCollection fetchList, int min, int max, forge.game.player.DelayedReveal delayedReveal, String selectPrompt,
                Player decider) {
            seen(fetchList, "a placeholder was offered");
            return super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
        }
    }

    /** lethalBlock witness: a player other than the seat and the attacking player only passes. */
    static final class WitnessPass extends WitnessController {
        WitnessPass(Game g, Player p, forge.LobbyPlayer lp, WitnessWatch watch) {
            super(g, p, lp, watch);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return null;
        }
    }

    /** lethalBlock witness: the seat in a copy declares the candidate's blocks, then only passes. */
    static final class WitnessSeat extends WitnessController {
        private final BlockPlan block;
        private boolean used;
        /**
         * The candidate's blocks were declared as given (else nothing is witnessed). No blocks needs no declaration (Forge
         * does not ask a seat that cannot block).
         */
        boolean applied;

        WitnessSeat(Game g, Player p, forge.LobbyPlayer lp, BlockPlan block) {
            this(g, p, lp, block, null);
        }

        WitnessSeat(Game g, Player p, forge.LobbyPlayer lp, BlockPlan block, WitnessWatch watch) {
            super(g, p, lp, watch);
            this.block = block;
            this.applied = block.blocks.isEmpty();
        }

        @Override
        public void declareBlockers(Player defender, forge.game.combat.Combat combat) {
            if (!used) {
                used = true;
                applied = block.applyTo(getGame(), combat, defender);
            }
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return null;
        }
    }

    /**
     * lethalBlock witness: the attackers' controller in a copy. At its priority in declare blockers with an empty stack it
     * plays its line (found again in the copy; the target set; X at the most the engine can pay, at most {@code xCap};
     * repeated while payable up to the line's cap; X once), and otherwise passes. Forge AI makes its other choices, except
     * combat damage ({@link #assignCombatDamage}).
     */
    static final class WitnessAi extends WitnessController {
        private final LbLine line;
        private final int target, xCap;
        int played, x = -1;
        boolean failed, done, illegalTarget;
        /** Why the run cannot witness (round 8: damage that tramples over a planeswalker), or null. */
        String voided;

        WitnessAi(Game g, Player p, forge.LobbyPlayer lp, LbLine line, int target, int xCap) {
            this(g, p, lp, line, target, xCap, null);
        }

        WitnessAi(Game g, Player p, forge.LobbyPlayer lp, LbLine line, int target, int xCap, WitnessWatch watch) {
            super(g, p, lp, watch);
            this.line = line;
            this.target = target;
            this.xCap = xCap;
            this.done = line == null;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            if (done || !g.getPhaseHandler().is(forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS) || !g.getStack().isEmpty()) {
                return null;
            }
            if (played >= line.cap || line.x && played > 0) {
                done = true;
                return null;
            }
            final Player me = getPlayer();
            final SpellAbility sa = lbLocate(g, me, line);
            if (sa == null) {
                done = true;
                return null;
            }
            sa.setActivatingPlayer(me);
            if (line.x) {
                int xv = Math.min(xCap, forge.ai.ComputerUtilMana.determineLeftoverMana(sa, me, false));
                xv = Math.max(0, xv);
                sa.getRootAbility().setXManaCostPaid(xv);
                while (xv > 0 && !forge.ai.ComputerUtilCost.canPayCost(sa, me, false)) {
                    xv--;
                    sa.getRootAbility().setXManaCostPaid(xv);
                }
                x = xv;
            }
            if (target >= 0 && !applyTarget(sa, "C" + target, g)) {
                done = true;
                return null;
            }
            if (!lbTargetsLegal(sa)) {
                done = true;
                illegalTarget = true;
                return null;
            }
            if (!sa.canPlay() || !forge.ai.ComputerUtilCost.canPayCost(sa, me, false)) {
                done = true;
                return null;
            }
            return Collections.singletonList(sa);
        }

        @Override
        public boolean playChosenSpellAbility(SpellAbility sa) {
            if (forge.ai.ComputerUtil.handlePlayingSpellAbility(getPlayer(), sa, null)) {
                played++;
            } else {
                failed = true;
                done = true;
            }
            return true;
        }

        /**
         * Round 7 (review S1 5): combat damage by the rules alone, not Forge AI's assignment. Each blocker in damage order
         * gets lethal damage (its toughness less the damage already marked and assigned this step, 1 with deathtouch),
         * ignoring prevention and replacement, while damage lasts; with trample the rest goes to the defender it attacks
         * (the engine passes it: the player, or the planeswalker or battle, which then takes all of it), else to the last
         * blocker. Round 8 (review S1 4): a planeswalker or battle in the damage order (trample over planeswalkers: only
         * then may excess reach its controller) voids the run; it is given its loyalty or defense less the damage already
         * assigned to it, never by toughness, and deathtouch's one damage counts for creatures only.
         */
        @Override
        public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                int damageDealt, forge.game.GameEntity defender, boolean overrideOrder) {
            final Map<Card, Integer> out = new java.util.LinkedHashMap<>();
            int left = Math.max(0, damageDealt);
            final boolean deathtouch = attacker.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH);
            Card last = null;
            for (Card b : blockers) {
                last = b;
                int need;
                if (b.isPlaneswalker() || b.isBattle()) {
                    // round 9 (review S1 4): a planeswalker or battle in the order, whatever its other types (a creature
                    // planeswalker too), voids the run; it takes its loyalty or defense
                    voided = "trample over planeswalkers";
                    need = Math.max(0, (b.isPlaneswalker() ? b.getCurrentLoyalty()
                            : b.getCounters(forge.game.card.CounterEnumType.DEFENSE)) - b.getTotalAssignedDamage());
                } else if (b.isCreature()) {
                    need = Math.max(0, b.getLethalDamage());
                    if (deathtouch) {
                        need = Math.min(1, need);
                    }
                } else {
                    voided = "trample over planeswalkers"; // neither: not modeled
                    need = left;
                }
                final int give = Math.min(need, left);
                if (give > 0) {
                    out.merge(b, give, Integer::sum);
                    left -= give;
                }
            }
            if (left > 0) {
                if (attacker.hasKeyword(forge.game.keyword.Keyword.TRAMPLE) && defender != null) {
                    out.merge(null, left, Integer::sum);
                } else if (last != null) {
                    out.merge(last, left, Integer::sum);
                } else {
                    out.merge(null, left, Integer::sum);
                }
            }
            return out;
        }
    }

    /**
     * lethalBlock round 8 (review S1 3): the targets of {@code sa} (every targeting part, sub-abilities included) are
     * legal the engine's full way before anything is paid: each part has a valid number of targets, each target is one
     * the engine offers (its zone and {@code canTarget}), and the whole choice meets every must-target restriction
     * ({@code StaticAbilityMustTarget}, a Flagbearer), as {@code SpellAbility.setupTargets} checks.
     */
    static boolean lbTargetsLegal(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (!s.usesTargeting()) {
                continue;
            }
            if (!s.isTargetNumberValid()) {
                return false;
            }
            final List<forge.game.GameEntity> offered = s.getTargetRestrictions().getAllCandidates(s, false);
            for (forge.game.GameObject o : s.getTargets()) {
                if (!offered.contains(o)) {
                    return false;
                }
            }
        }
        return forge.game.staticability.StaticAbilityMustTarget.meetsMustTargetRestriction(sa);
    }

    /** lethalBlock: {@code line}'s ability in game {@code g} (its place first, else by description), or null. */
    static SpellAbility lbLocate(Game g, Player opp, LbLine line) {
        final Card host = g.findById(line.hostId);
        if (host == null || host.getZone() == null) {
            return null;
        }
        final List<SpellAbility> all = host.getAllPossibleAbilities(opp, true);
        if (line.ordinal >= 0 && line.ordinal < all.size() && line.desc.equals(String.valueOf(all.get(line.ordinal).getDescription()))) {
            return all.get(line.ordinal);
        }
        for (SpellAbility sa : all) {
            if (!sa.isManaAbility() && line.desc.equals(String.valueOf(sa.getDescription()))) {
                return sa;
            }
        }
        return null;
    }

    /** lethalBlock: the mana budget is capped here (the knapsack's size); more mana only makes the bound larger. */
    static final int LB_MANA_CAP = 60;
    /** lethalBlock: no limit on an ability's activations (the mana bounds them). */
    static final int LB_UNLIMITED = Integer.MAX_VALUE;

    /**
     * lethalBlock: per candidate declaration (blocker card id -> attacker card ids), an upper bound on the combat damage
     * {@code me}, the defending seat, takes this combat if the declaration stands and the attackers' controller does its
     * best with what it can afford, and whether that bound is lethal. Runs on {@code g}, a copy of the live game: it sets
     * activating players and X on the copy's abilities and drops the controller's hand cards the seat does not know
     * ({@code known}: card ids it may know of, read in the live game). Engine primitives only:
     * <ul>
     * <li>An unblocked attacker (or one that assigns its damage as though unblocked) deals its combat damage now plus its
     * boost, twice with double strike. A blocked trampler deals what is left after the lethal damage its blockers need
     * (1 each against deathtouch; a blocker counted once): at most max(0, s * power - lethal) + s * boost for s strikes. A
     * blocked attacker without trample deals none. Infect damage counts as poison, kept apart from life, with toxic and
     * combat-damage poison triggers.</li>
     * <li>The boost is the most power the attackers' controller can add before combat damage with its mana
     * ({@code ComputerUtilMana.getAvailableManaEstimate}: its untapped sources, each once, and its pool), through the
     * spells and abilities it may play now ({@code Card.getAllPossibleAbilities}: timing, zone, activator and limits are the
     * engine's) on the cards the seat knows: permanents, its own hand only where the seat knows the card, its graveyard,
     * exile and command zone. Counted: Pump with a positive NumAtt and PumpAll on an attacker, and +1/+1-style counters
     * (PutCounter / PutCounterAll, Monstrosity once and only if not monstrous, Adapt only without +1/+1 counters), in the
     * whole resolution chain and its modes. Each counted ability must be payable on its own at X = 0
     * ({@code ComputerUtilCost.canPayCost}: colors). X takes any value the mana allows; a repeatable ability repeats while
     * mana and its other costs last (tap, a limit, a spell card: once); the mana is split among the abilities for the most
     * damage (a small knapsack, colors ignored: an upper bound). A grant of double strike, trample or deathtouch the
     * controller can pay for counts as given for free.</li>
     * <li>Knowledge: every amount the bound uses (NumAtt, CounterNum and so Monstrosity / Adapt / Bolster, activation
     * limits, target counts, mana abilities' Amount, poison Num) is read from its text alone ({@link #lbAmount}: an
     * integer, or the X paid), never evaluated; filters that compare against a named amount are not evaluated either. So no
     * hidden card (a library's top, a hidden hand) and no board count can change the bound: such an ability stands aside
     * for the whole decision, as does an amount that reads power (which repeated boosts would compound).</li>
     * <li>Joint mana: a cost that taps or takes a mana source (its host's {T} or sacrifice, or other permanents tapped,
     * sacrificed, returned or exiled) gives up that source's mana in the same option ({@link LbAbility#extra}, the cheapest
     * choices), so a source never both pays and is spent. The abilities that tap or take one host are one knapsack group.</li>
     * <li>Lethal: the life total at or below 0 when the player can lose from life, or the poison count at the rules'
     * limit when it can lose to poison. Damage to planeswalkers and battles is not life.</li>
     * </ul>
     * Stands aside ({@link LethalCheck#aside}; nothing re-scored) when the bound is not trusted: not two players, not the
     * declare-blockers step, an extra phase pending, an attacker the seat cannot see (face-down), an attacker that assigns
     * damage by toughness, a damage replacement or prevention that applies to the attackers' damage to the player, a
     * life-loss replacement, a playable ability that changes power another way (Animate, an Effect with a power static,
     * ...) or puts a creature onto the battlefield attacking, a cost it cannot count, a free unlimited pump, an amount /
     * target count / filter not read from its text, a trigger a boost or block sets off that adds power (prowess, heroic,
     * bushido, counter payoffs) or a counter replacement ({@link #lbReactive}), and costs of two abilities that may take the
     * same mana sources ({@link #lbSharedCosts}).
     * <p>
     * Out of scope (they can only lower the true damage, or are not combat damage): the controller's hidden cards,
     * removal of blockers before damage, blockers' first strike, and life loss that is not combat damage.
     */
    static LethalCheck lethalBlocks(Game g, Player me, List<Map<Integer, List<Integer>>> plans, Set<Integer> known) {
        final PhaseHandler ph = g.getPhaseHandler();
        final forge.game.combat.Combat combat = ph.getCombat();
        if (combat == null || ph.getPhase() != forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS) {
            return LethalCheck.aside("not at declare blockers");
        }
        if (g.getPlayers().size() != 2) {
            return LethalCheck.aside("not two players");
        }
        if (extraPhasePending(ph)) {
            return LethalCheck.aside("extra phase pending");
        }
        final Player opp = combat.getAttackingPlayer();
        if (opp == null || opp.equals(me) || !me.isInGame()) {
            return LethalCheck.aside("no attacking opponent");
        }
        final int n = plans.size();
        final LethalCheck out = new LethalCheck();
        out.lethal = new boolean[n];
        out.life = new int[n];
        out.poison = new int[n];
        final List<Card> atk = new ArrayList<>(combat.getAttackersOf(me));
        if (atk.isEmpty()) {
            return out; // nothing attacks the player
        }
        for (Card a : combat.getAttackers()) {
            if (!known.contains(a.getId()) || a.isFaceDown()) {
                return LethalCheck.aside("an attacker the seat cannot see");
            }
        }
        for (Card a : atk) {
            if (a.toughnessAssignsDamage()) {
                return LethalCheck.aside("an attacker assigns damage by toughness");
            }
        }
        final String repl = lbReplacement(g, me, atk, known);
        if (repl != null) {
            return LethalCheck.aside(repl);
        }
        // the controller's hand cards the seat does not know are out of the copy: no read below may use them (mana
        // payments from a hidden hand included); their number is public
        final int oppHand = opp.getCardsIn(ZoneType.Hand).size();
        final List<Card> keepHand = new ArrayList<>();
        for (Card c : opp.getCardsIn(ZoneType.Hand)) {
            if (known.contains(c.getId())) {
                keepHand.add(c);
            }
        }
        if (keepHand.size() != oppHand) {
            opp.getZone(ZoneType.Hand).setCards(keepHand);
        }
        final int hiddenHand = oppHand - keepHand.size();
        // each source's mana, kept per card: a cost that taps or takes a source spends that mana too (joint allocation)
        final Map<Integer, Integer> sources = new TreeMap<>();
        final int made = lbMana(opp, sources, atk);
        if (made == -2) {
            return LethalCheck.aside("a mana ability whose cost takes another permanent or a shared pool");
        }
        if (made < 0) {
            return LethalCheck.aside("a mana ability whose amount is not a number");
        }
        final int mana = Math.max(0, Math.min(LB_MANA_CAP, made));
        out.mana = mana;
        final String react = lbReactive(opp, known);
        if (react != null) {
            return LethalCheck.aside(react);
        }
        final int na = atk.size();
        final boolean[] ds = new boolean[na], trample = new boolean[na], deathtouch = new boolean[na];
        final List<List<LbAbility>> items = new ArrayList<>();
        final Map<Integer, List<LbAbility>> byCard = new TreeMap<>();
        final Map<Integer, List<LbAbility>> byHost = new TreeMap<>();
        final List<LbAbility> counted = new ArrayList<>();
        for (Card c : new ArrayList<>(g.getCardsInGame())) {
            if (!known.contains(c.getId())) {
                continue;
            }
            final forge.game.zone.Zone z = g.getZoneOf(c);
            if (z == null) {
                continue;
            }
            final ZoneType zt = z.getZoneType();
            final boolean field = zt == ZoneType.Battlefield;
            if (!field && !(opp.equals(c.getOwner())
                    && (zt == ZoneType.Hand || zt == ZoneType.Graveyard || zt == ZoneType.Exile || zt == ZoneType.Command))) {
                continue;
            }
            final List<SpellAbility> possible = c.getAllPossibleAbilities(opp, true);
            for (int j = 0; j < possible.size(); j++) {
                final SpellAbility sa = possible.get(j);
                if (sa.isManaAbility() || sa.isLandAbility()) {
                    continue;
                }
                final LbAbility ab = lbAbility(sa, opp, atk, mana, hiddenHand, sources);
                if (ab == null) {
                    continue;
                }
                if (ab.unsure != null) {
                    return LethalCheck.aside(ab.unsure);
                }
                out.abilities++;
                counted.add(ab);
                out.lines.add(lbLine(c, j, sa, ab, atk)); // round 6: the witness plays it alone
                for (int i = 0; i < na; i++) {
                    ds[i] |= ab.ds[i];
                    trample[i] |= ab.trample[i];
                    deathtouch[i] |= ab.deathtouch[i];
                }
                if (field && ab.hostTaken) {
                    byHost.computeIfAbsent(c.getId(), x -> new ArrayList<>()).add(ab); // one cost that taps or takes the host
                } else if (field) {
                    items.add(Collections.singletonList(ab)); // each activated ability of a permanent on its own
                } else {
                    byCard.computeIfAbsent(c.getId(), x -> new ArrayList<>()).add(ab); // one use of the card
                }
            }
        }
        final String shared = lbSharedCosts(counted, sources);
        if (shared != null) {
            return LethalCheck.aside(shared);
        }
        items.addAll(byHost.values());
        items.addAll(byCard.values());
        final boolean canLoseLife = me.canLoseLife() && !me.cantLoseForZeroOrLessLife();
        final boolean canPoison = me.canReceiveCounters(forge.game.card.CounterEnumType.POISON)
                && !me.cantLoseCheck(forge.game.player.GameLossReason.Poisoned);
        final int poisonLimit = g.getRules().getPoisonCountersToLose();
        for (int p = 0; p < n; p++) {
            final int[] wLife = new int[na], wPoison = new int[na];
            int life = 0, poison = me.getPoisonCounters();
            final Set<Integer> usedBlockers = new HashSet<>();
            for (int i = 0; i < na; i++) {
                final Card a = atk.get(i);
                final List<Card> blockers = new ArrayList<>();
                for (Map.Entry<Integer, List<Integer>> e : plans.get(p).entrySet()) {
                    if (e.getValue().contains(a.getId())) {
                        final Card b = g.findById(e.getKey());
                        if (b == null) {
                            return LethalCheck.aside("a blocker is not in the copy");
                        }
                        blockers.add(b);
                    }
                }
                if (a.assignNoCombatDamage()) {
                    continue;
                }
                final int strikes = a.hasDoubleStrike() || ds[i] ? 2 : 1;
                final int power = Math.max(0, a.getNetCombatDamage());
                int base = 0, w = 0;
                if (blockers.isEmpty()
                        || forge.game.staticability.StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(a)) {
                    base = strikes * power;
                    w = strikes;
                } else if (a.hasKeyword(forge.game.keyword.Keyword.TRAMPLE) || trample[i]) {
                    final boolean dt = a.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH) || deathtouch[i];
                    int lethal = 0;
                    for (Card b : blockers) {
                        if (usedBlockers.add(b.getId())) {
                            final int need = Math.max(0, b.getLethalDamage());
                            lethal += dt ? Math.min(1, need) : need;
                        }
                    }
                    base = Math.max(0, strikes * power - lethal);
                    w = strikes;
                }
                if (w == 0) {
                    continue;
                }
                if (a.isInfectDamage(me)) {
                    poison += base;
                    wPoison[i] = w;
                } else {
                    life += base;
                    wLife[i] = w;
                }
                final int extraPoison = lbExtraPoison(a, opp, known);
                if (extraPoison < 0) {
                    return LethalCheck.aside("a combat-damage poison trigger whose amount is not a number");
                }
                poison += strikes * extraPoison;
            }
            life += lbBoost(items, wLife, mana);
            poison += lbBoost(items, wPoison, mana);
            out.life[p] = life;
            out.poison[p] = poison;
            out.lethal[p] = canLoseLife && life >= me.getLife() || canPoison && poison >= poisonLimit;
        }
        return out;
    }

    /**
     * lethalBlock: the mana {@code p} can make now, each source once: per untapped permanent it controls, the most one of
     * its playable mana abilities makes (Produced symbols x Amount, a "Combo" choice one symbol, less its own mana cost),
     * plus its mana pool. As {@code ComputerUtilMana.getAvailableManaEstimate}, except that one counts the words of a
     * "Combo R G" choice (3 mana for a land that makes 1). Each source's mana goes into {@code bySource} (card id -> mana)
     * when it makes any. -1 when a playable mana ability's Amount is not a number ({@link #lbLiteral}): its value may read
     * what the seat cannot see, so the bound does not use it. -2 when a playable mana ability has a cost part other than
     * mana, its own tap or its own sacrifice (re-review S1: Springleaf Drum taps another creature, which a pump's cost
     * may also tap; re-review 2 S1: Workhorse removes its own counter, which Power Conduit's cost may also remove; life
     * likewise): the per-source split cannot see a shared permanent or pool, so the guard stands aside. A mana ability that
     * sacrifices its source is not counted when the source is one of {@code attackers} (re-review 3 S1: Wild Cantor's
     * damage stays counted, so its mana is not).
     */
    static int lbMana(Player p, Map<Integer, Integer> bySource, java.util.Collection<Card> attackers) {
        int mana = p.getManaPool().totalMana();
        for (Card src : p.getCardsIn(ZoneType.Battlefield)) {
            int best = 0;
            for (SpellAbility ma : src.getManaAbilities()) {
                ma.setActivatingPlayer(p);
                if (!ma.canPlay()) {
                    continue;
                }
                final String produced = ma.getParamOrDefault("Produced", "").trim();
                final int symbols = produced.isEmpty() ? 1 : produced.startsWith("Combo") ? 1 : produced.split("\\s+").length;
                final Integer amount = lbLiteral(ma.getParamOrDefault("Amount", "1"), ma);
                if (amount == null) {
                    return -1;
                }
                boolean selfSac = false;
                if (ma.getPayCosts() != null) {
                    for (forge.game.cost.CostPart part : ma.getPayCosts().getCostParts()) {
                        selfSac |= part instanceof forge.game.cost.CostSacrifice && part.payCostFromSource();
                        // re-review S1: a cost that takes another permanent (tap an untapped creature, sacrifice one) is
                        // not this source's alone, and a pump's cost may take the same permanent; not bounded jointly
                        if (!(part instanceof forge.game.cost.CostPartMana || part instanceof forge.game.cost.CostTap
                                || part instanceof forge.game.cost.CostSacrifice && part.payCostFromSource())) {
                            return -2; // re-review 2 S1: life and the source's counters are pools a pump may share too
                        }
                    }
                }
                if (selfSac && attackers != null && attackers.contains(src)) {
                    continue; // re-review 3 S1: an attacker sacrificed for mana deals nothing; its damage stays counted, so its mana is not
                }
                final forge.game.cost.CostPartMana cm = ma.getPayCosts() == null ? null : ma.getPayCosts().getCostMana();
                final Integer pay = cm == null ? Integer.valueOf(0) : cm.convertAmount();
                best = Math.max(best, symbols * Math.max(0, amount) - (pay == null ? 0 : pay));
            }
            mana += best;
            if (best > 0 && bySource != null) {
                bySource.put(src.getId(), best);
            }
        }
        return mana;
    }

    /**
     * lethalBlock: an amount read from its text alone, as {constant, per X}, or null when its text is not one the bound
     * reads. Read: an integer ("3", "+3", "-1"); a name (or raw value) that is one ("Number$3"); or the X paid
     * ("Count$xPaid": the root ability's X, as the engine reads it). Anything else is never evaluated: a count of the board,
     * a card's power, the top of a library... may read what the seat cannot see, or change as boosts resolve (a power that
     * doubles), so the guard stands aside instead.
     */
    static int[] lbAmount(String text, forge.game.CardTraitBase s) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        int sign = 1;
        if (t.startsWith("+") || t.startsWith("-")) {
            sign = t.startsWith("-") ? -1 : 1;
            t = t.substring(1);
        }
        if (lbDigits(t)) {
            return new int[] {sign * Integer.parseInt(t), 0};
        }
        if (t.isEmpty()) {
            return null;
        }
        String sv = t.indexOf('$') > 0 ? t : s == null ? "" : s.getSVar(t);
        if ((sv == null || sv.isEmpty()) && s != null && s.getHostCard() != null && t.indexOf('$') < 0) {
            sv = s.getHostCard().getSVar(t);
        }
        sv = sv == null ? "" : sv.trim();
        if (lbDigits(sv)) {
            return new int[] {sign * Integer.parseInt(sv), 0};
        }
        if (sv.startsWith("Number$") && lbDigits(sv.substring(7))) {
            return new int[] {sign * Integer.parseInt(sv.substring(7)), 0};
        }
        if ("Count$xPaid".equals(sv)) {
            return new int[] {0, sign};
        }
        return null;
    }

    /** lethalBlock: an amount {@link #lbAmount} reads as a plain number (no X), or null. */
    static Integer lbLiteral(String text, forge.game.CardTraitBase s) {
        final int[] a = lbAmount(text, s);
        return a == null || a[1] != 0 ? null : a[0];
    }

    private static boolean lbDigits(String t) {
        if (t.isEmpty() || t.length() > 6) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** lethalBlock: a comparison in a valid-card filter against a name ("powerLEX", "cmcGEY"), not a number. */
    private static final java.util.regex.Pattern LB_COMPARE_NAME = java.util.regex.Pattern.compile("(?:LT|LE|EQ|NE|GE|GT)[A-Za-z]");

    /**
     * lethalBlock: a valid-card filter (or Defined) the bound may evaluate: no comparison against a named amount, which
     * the engine would evaluate as an SVar (it may read what the seat cannot see). Null and empty are fine.
     */
    static boolean lbPublicFilter(String valid) {
        return valid == null || !LB_COMPARE_NAME.matcher(valid).find();
    }

    /**
     * lethalBlock: a replacement's conditions are ones the bound may evaluate: no CheckSVar condition, and no Valid /
     * Defined / IsPresent filter that compares against a named amount ({@link #lbPublicFilter}).
     */
    static boolean lbTextOnly(Map<String, String> params) {
        for (Map.Entry<String, String> e : params.entrySet()) {
            final String k = e.getKey();
            if (k.startsWith("CheckSVar") || k.startsWith("CheckSecondSVar")) {
                return false;
            }
            if ((k.startsWith("Valid") || k.startsWith("Defined") || k.startsWith("IsPresent") || k.startsWith("Present"))
                    && !lbPublicFilter(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** lethalBlock: trigger modes a boost or the declaration itself may set off before combat damage. */
    static final Set<TriggerType> LB_REACT_MODES = java.util.EnumSet.of(TriggerType.SpellCast, TriggerType.SpellCastOrCopy,
            TriggerType.SpellAbilityCast, TriggerType.AbilityCast, TriggerType.SpellCopy, TriggerType.SpellAbilityCopy,
            TriggerType.BecomesTarget, TriggerType.BecomesTargetOnce, TriggerType.CounterAdded, TriggerType.CounterAddedOnce,
            TriggerType.CounterAddedAll, TriggerType.CounterTypeAddedAll, TriggerType.AttackerBlocked,
            TriggerType.AttackerBlockedOnce, TriggerType.AttackerBlockedByCreature);

    /**
     * lethalBlock: why boosts would compose beyond the bound, or null. A known permanent (or command-zone card) of the
     * attackers' controller with a trigger that a spell or ability, a target, a counter or a block sets off
     * ({@link #LB_REACT_MODES}: prowess, heroic, bushido, a +1/+1-counter payoff...) whose resolution changes power or adds
     * counters; or a known counter replacement (AddCounter: more or fewer counters than the text says), any controller.
     */
    static String lbReactive(Player opp, Set<Integer> known) {
        final Game g = opp.getGame();
        for (Card c : g.getCardsInGame()) {
            if (!known.contains(c.getId())) {
                continue;
            }
            final forge.game.zone.Zone z = g.getZoneOf(c);
            if (z == null || z.getZoneType() != ZoneType.Battlefield && z.getZoneType() != ZoneType.Command) {
                continue;
            }
            for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                if (re.getMode() == forge.game.replacement.ReplacementType.AddCounter && re.zonesCheck(z)) {
                    return "a counter replacement";
                }
            }
            if (!opp.equals(c.getController())) {
                continue;
            }
            for (Trigger t : c.getTriggers()) {
                if (!LB_REACT_MODES.contains(t.getMode()) || !t.zonesCheck(z)) {
                    continue;
                }
                final java.util.ArrayDeque<SpellAbility> todo = new java.util.ArrayDeque<>();
                final Set<SpellAbility> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                if (t.getOverridingAbility() != null) {
                    todo.add(t.getOverridingAbility());
                }
                while (!todo.isEmpty()) {
                    final SpellAbility s = todo.poll();
                    if (s == null || !seen.add(s)) {
                        continue;
                    }
                    final String api = s.getApi() == null ? "" : s.getApi().name();
                    if ("Pump".equals(api) || "PumpAll".equals(api) || "PutCounter".equals(api) || "PutCounterAll".equals(api)
                            || LB_POWER_APIS.contains(api)) {
                        return "a trigger that adds power (" + t.getMode() + ")";
                    }
                    final List<SpellAbility> next = new ArrayList<>();
                    next.add(s.getSubAbility());
                    next.addAll(s.getAdditionalAbilities().values());
                    for (List<? extends SpellAbility> l : s.getAdditionalAbilityLists().values()) {
                        next.addAll(l);
                    }
                    for (SpellAbility a : next) {
                        if (a != null) {
                            todo.add(a); // ArrayDeque takes no null
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * lethalBlock: why the costs that take other permanents (tap, sacrifice, return or exile from the battlefield) cannot
     * be charged one ability at a time, or null: two counted abilities with such costs when any permanent either may take
     * makes mana (each was charged its cheapest choice alone), or one whose cost may take another counted ability's host
     * when that ability taps or takes its host.
     */
    static String lbSharedCosts(List<LbAbility> counted, Map<Integer, Integer> sources) {
        int pools = 0;
        for (LbAbility t : counted) {
            if (t.pool && ++pools > 1) {
                // re-review 2: each was capped by the whole pool (life, energy, creatures to sacrifice) on its own
                return "two abilities whose costs draw on the same pool";
            }
        }
        for (LbAbility t : counted) {
            if (t.taken == null) {
                continue;
            }
            for (LbAbility o : counted) {
                if (o == t) {
                    continue;
                }
                if (o.taken != null && (lbAnySource(t.taken, sources) || lbAnySource(o.taken, sources))) {
                    return "two abilities whose costs may take the same mana sources";
                }
                if (o.hostTaken && o.host != null && t.taken.contains(o.host)) {
                    return "a cost that may take another ability's host";
                }
            }
        }
        return null;
    }

    private static boolean lbAnySource(List<Card> cards, Map<Integer, Integer> sources) {
        for (Card c : cards) {
            if (sources.getOrDefault(c.getId(), 0) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * lethalBlock: the permanents a cost part may tap, sacrifice, return or exile from the battlefield besides its own
     * host (whose part is counted apart), as the part's own {@code getMaxAmountX} lists them; null for a part that takes
     * none.
     */
    static List<Card> lbCostCards(forge.game.cost.CostPart part, SpellAbility sa, Player payer) {
        if (part.payCostFromSource()) {
            return null;
        }
        final Card host = sa.getHostCard();
        final CardCollectionView field = payer.getCardsIn(ZoneType.Battlefield);
        if (part instanceof forge.game.cost.CostTapType) {
            final CardCollection l = forge.game.card.CardLists.getValidCards(field, part.getType().split(";"), payer, host, sa);
            if (!((forge.game.cost.CostTapType) part).canTapSource) {
                l.remove(host);
            }
            return forge.game.card.CardLists.filter(l, forge.game.card.CardPredicates.CAN_TAP);
        }
        if (part instanceof forge.game.cost.CostSacrifice) {
            final String type = part.getType().replace("+WithDifferentNames", "");
            final CardCollectionView l = type.contains("X") ? field
                    : forge.game.card.CardLists.getValidCards(field, type.split(";"), payer, host, sa);
            return forge.game.card.CardLists.filter(l, forge.game.card.CardPredicates.canBeSacrificedBy(sa, false));
        }
        if (part instanceof forge.game.cost.CostReturn || part instanceof forge.game.cost.CostExile
                && ((forge.game.cost.CostExile) part).getFrom() != null
                && ((forge.game.cost.CostExile) part).getFrom().contains(ZoneType.Battlefield)) {
            return forge.game.card.CardLists.getValidCards(field, part.getType().split(";"), payer, host, sa);
        }
        return null;
    }

    /** lethalBlock: a cost part paid with hand cards (discard, reveal, exile or put from the hand, or from anywhere). */
    static boolean lbFromHand(forge.game.cost.CostPart part) {
        if (part.payCostFromSource()) {
            return false;
        }
        if (part instanceof forge.game.cost.CostDiscard || part instanceof forge.game.cost.CostReveal) {
            return true;
        }
        if (part instanceof forge.game.cost.CostExile) {
            final List<ZoneType> from = ((forge.game.cost.CostExile) part).getFrom();
            return from == null || from.isEmpty() || from.contains(ZoneType.Hand);
        }
        if (part instanceof forge.game.cost.CostPutCardToLib) {
            return ((forge.game.cost.CostPutCardToLib) part).getFrom() == ZoneType.Hand;
        }
        return false;
    }

    /**
     * lethalBlock: why the bound would not hold, or null. A replacement effect on a card the seat knows (an effect in the
     * command zone included), working where it is: a DamageDone replacement that applies to an attacker's combat damage to
     * the player (prevention, doubling, redirection), or a LifeReduced replacement.
     */
    static String lbReplacement(Game g, Player me, List<Card> atk, Set<Integer> known) {
        return lbReplacement(g, me, atk, known, false);
    }

    /**
     * {@link #lbReplacement}; {@code live}: {@code g} is the live game (round 9, review S1 1: a copy may have lost a choice
     * a replacement reads), so only what reading builds nothing is read: no rules-host replacements, and none of a
     * planeswalker, battle or saga ({@link #lbReplacementsReadable}; the copy's pass reads those).
     */
    static String lbReplacement(Game g, Player me, List<Card> atk, Set<Integer> known, boolean live) {
        for (Card c : g.getCardsInGame()) {
            if (!known.contains(c.getId())) {
                continue;
            }
            final forge.game.zone.Zone z = g.getZoneOf(c);
            if (z == null) {
                continue;
            }
            if (live && !lbReplacementsReadable(c)) {
                continue;
            }
            for (forge.game.replacement.ReplacementEffect re : live ? c.getCurrentState().getReplacementEffects(false)
                    : c.getReplacementEffects()) {
                final forge.game.replacement.ReplacementType m = re.getMode();
                if (m != forge.game.replacement.ReplacementType.DamageDone && m != forge.game.replacement.ReplacementType.LifeReduced
                        || !re.zonesCheck(z)) {
                    continue;
                }
                if (!lbTextOnly(re.getMapParams())) {
                    return "a damage replacement it does not evaluate"; // its conditions may read what the seat cannot see
                }
                if (!re.requirementsCheck(g)) {
                    continue;
                }
                if (m == forge.game.replacement.ReplacementType.LifeReduced) {
                    return "a life-loss replacement";
                }
                for (Card a : atk) {
                    final Map<forge.game.ability.AbilityKey, Object> rp = forge.game.ability.AbilityKey.mapFromAffected(me);
                    rp.put(forge.game.ability.AbilityKey.DamageSource, a);
                    rp.put(forge.game.ability.AbilityKey.DamageAmount, Math.max(1, a.getNetCombatDamage()));
                    rp.put(forge.game.ability.AbilityKey.IsCombat, true);
                    try {
                        if (re.canReplace(rp)) {
                            return "a damage replacement";
                        }
                    } catch (RuntimeException e) {
                        return "a damage replacement";
                    }
                }
            }
        }
        return null;
    }

    /**
     * lethalBlock: poison counters {@code a} gives with each hit of combat damage on the player besides infect: its toxic,
     * and the poison triggers on combat damage of the controller's permanents the seat knows.
     */
    static int lbExtraPoison(Card a, Player opp, Set<Integer> known) {
        int poison = a.getKeywordMagnitude(forge.game.keyword.Keyword.TOXIC);
        for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
            if (!known.contains(c.getId())) {
                continue;
            }
            for (Trigger t : c.getTriggers()) {
                if (t.getMode() != TriggerType.DamageDone || "False".equals(t.getParam("CombatDamage"))) {
                    continue;
                }
                final SpellAbility ab = t.getOverridingAbility();
                if (ab == null || ab.getApi() != forge.game.ability.ApiType.Poison) {
                    continue;
                }
                if (!lbPublicFilter(t.getParam("ValidSource"))) {
                    return -1;
                }
                if (t.matchesValidParam("ValidSource", a)) {
                    final Integer num = lbLiteral(ab.getParamOrDefault("Num", "1"), ab);
                    if (num == null) {
                        return -1; // read from the text only (lbAmount)
                    }
                    poison += Math.max(0, num);
                }
            }
        }
        return poison;
    }

    /** lethalBlock: one spell or ability the attackers' controller may play now that can add power to an attacker. */
    static final class LbAbility {
        /** Why the bound cannot count it (the guard stands aside), or null. */
        String unsure;
        /** Mana per activation at X = 0 (cost adjustments applied), mana per X, activations at most, largest X. */
        int cost, xPer, cap, xMax;
        /**
         * Mana given up by j activations' other costs (extra[j]): the mana of the sources they tap or take, the host's
         * and the cheapest choices among the permanents a cost takes. Null: none.
         */
        int[] extra;
        /** Its host, and whether its cost taps the host or takes it (sacrifice, exile, return...). */
        Card host;
        boolean hostTaken;
        /** The other permanents its cost may take ({@link #lbCostCards}), or null. */
        List<Card> taken;
        /** Its cost draws on a pool counted for it alone (life, energy, other permanents): two such abilities stand aside. */
        boolean pool;
        final List<LbPart> parts = new ArrayList<>();
        /** Per attacker: the ability may give it double strike / trample / deathtouch. */
        boolean[] ds, trample, deathtouch;

        int extra(int j) {
            return extra == null || j <= 0 ? 0 : extra[Math.min(j, extra.length - 1)];
        }
    }

    /** lethalBlock: one part of an ability's resolution that adds power to attackers. */
    static final class LbPart {
        /** The part of the resolution chain this reads. */
        SpellAbility owner;
        /** It picks up to {@link #maxTargets} of the attackers it may hit; otherwise it hits all of them. */
        boolean pick;
        int maxTargets = 1;
        /** Per attacker: it may hit it. */
        boolean[] hit;
        /** Power added per attacker, by X: amount[x][attacker]. */
        int[][] amount;
        /** The amount's text (read by {@link #lbAmount} only), and power per counter (0: a pump's NumAtt). */
        String num;
        int perUnit;
        /** Why whom it may hit cannot be read (a filter or target count that is not a number), or null. */
        String unsure;
    }

    /** lethalBlock: unsure marker for an ability. */
    private static LbAbility lbUnsure(String why) {
        final LbAbility u = new LbAbility();
        u.unsure = why;
        return u;
    }

    /**
     * lethalBlock: {@code sa} as {@code opp} may play it now, or null when it adds no power to an attacker or cannot be
     * paid for. Unsure (the guard stands aside) when a payable ability changes power another way or makes a new attacker,
     * has a cost this cannot count, or an amount, target count or filter that is not read from its text alone
     * ({@link #lbAmount}). {@code sources}: each mana source's mana ({@link #lbMana}); a cost that taps or takes a source
     * gives up that mana ({@link LbAbility#extra}), so a source never pays twice.
     */
    static LbAbility lbAbility(SpellAbility sa, Player opp, List<Card> atk, int mana, int hiddenHand,
            Map<Integer, Integer> sources) {
        final int na = atk.size();
        final LbAbility ab = new LbAbility();
        ab.ds = new boolean[na];
        ab.trample = new boolean[na];
        ab.deathtouch = new boolean[na];
        final Card host = sa.getHostCard();
        ab.host = host;
        String unsure = null;
        final List<SpellAbility> chain = new ArrayList<>();
        final java.util.ArrayDeque<SpellAbility> todo = new java.util.ArrayDeque<>();
        final Set<SpellAbility> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        todo.add(sa);
        while (!todo.isEmpty()) {
            final SpellAbility s = todo.poll();
            if (s == null || !seen.add(s)) {
                continue;
            }
            chain.add(s);
            if (s.getSubAbility() != null) {
                todo.add(s.getSubAbility());
            }
            for (SpellAbility a : s.getAdditionalAbilities().values()) {
                if (a != null) {
                    todo.add(a);
                }
            }
            for (List<? extends SpellAbility> l : s.getAdditionalAbilityLists().values()) {
                for (SpellAbility a : l) {
                    if (a != null) {
                        todo.add(a);
                    }
                }
            }
        }
        for (SpellAbility s : chain) {
            for (String key : s.getMapParams().keySet()) {
                if (key.endsWith("Attacking") || "Ninjutsu".equals(key)) {
                    unsure = "a playable ability puts a creature onto the battlefield attacking";
                }
            }
            final forge.game.ability.ApiType api = s.getApi();
            if (api == forge.game.ability.ApiType.Pump || api == forge.game.ability.ApiType.PumpAll) {
                final LbPart part = lbHits(s, atk, api == forge.game.ability.ApiType.PumpAll ? "ValidCards" : null);
                if (part == null) {
                    continue;
                }
                if (part.unsure != null) {
                    unsure = part.unsure;
                    continue;
                }
                final String kw = s.getParamOrDefault("KW", "");
                for (int i = 0; i < na; i++) {
                    if (part.hit[i]) {
                        ab.ds[i] |= kw.contains("Double Strike");
                        ab.trample[i] |= kw.contains("Trample");
                        ab.deathtouch[i] |= kw.contains("Deathtouch");
                    }
                }
                part.num = s.getParam("NumAtt");
                if (part.num != null) {
                    ab.parts.add(part);
                }
            } else if (api == forge.game.ability.ApiType.PutCounter || api == forge.game.ability.ApiType.PutCounterAll) {
                if (s.hasParam("Monstrosity") && host.isMonstrous()
                        || s.hasParam("Adapt") && host.getCounters(forge.game.card.CounterEnumType.P1P1) > 0
                        && !forge.game.staticability.StaticAbilityAdapt.anyWithAdapt(s, host)) {
                    continue; // the engine puts no counter
                }
                int per = 0;
                boolean gDs = false, gTr = false, gDt = false;
                for (String t : s.getParamOrDefault("CounterType", "").split(",")) {
                    final String name = t.trim();
                    final java.util.regex.Matcher pm = java.util.regex.Pattern.compile("P(\\d+)P(\\d+)").matcher(name);
                    if (pm.matches()) {
                        per = Math.max(per, Integer.parseInt(pm.group(1)));
                    }
                    gDs |= "Double Strike".equalsIgnoreCase(name) || "DOUBLE_STRIKE".equalsIgnoreCase(name);
                    gTr |= "Trample".equalsIgnoreCase(name) || "TRAMPLE".equalsIgnoreCase(name);
                    gDt |= "Deathtouch".equalsIgnoreCase(name) || "DEATHTOUCH".equalsIgnoreCase(name);
                }
                if (per == 0 && !gDs && !gTr && !gDt) {
                    continue;
                }
                final LbPart part = lbHits(s, atk, api == forge.game.ability.ApiType.PutCounterAll ? "ValidCards" : null);
                if (part == null) {
                    continue;
                }
                if (part.unsure != null) {
                    unsure = part.unsure;
                    continue;
                }
                for (int i = 0; i < na; i++) {
                    if (part.hit[i]) {
                        ab.ds[i] |= gDs;
                        ab.trample[i] |= gTr;
                        ab.deathtouch[i] |= gDt;
                    }
                }
                if (per > 0) {
                    part.num = s.getParamOrDefault("CounterNum", "1");
                    part.perUnit = per;
                    ab.parts.add(part);
                }
                if (s.hasParam("Monstrosity") || s.hasParam("Adapt")) {
                    ab.cap = 1;
                }
            } else if (lbOtherPower(s, atk)) {
                unsure = "a playable ability changes power another way (" + api + ")";
            }
        }
        boolean grants = false;
        for (int i = 0; i < na; i++) {
            grants |= ab.ds[i] || ab.trample[i] || ab.deathtouch[i];
        }
        if (ab.parts.isEmpty() && !grants && unsure == null) {
            return null;
        }
        final forge.game.cost.Cost cost = sa.getPayCosts();
        if (hiddenHand > 0 && cost != null) {
            for (forge.game.cost.CostPart part : cost.getCostParts()) {
                if (lbFromHand(part)) {
                    // payable or not with cards the seat has not seen: the bound cannot tell
                    return lbUnsure("a cost paid from a hidden hand (" + part.getClass().getSimpleName() + ")");
                }
            }
        }
        final SpellAbility root = sa.getRootAbility();
        final Integer xWas = root.getXManaCostPaid();
        try {
            root.setXManaCostPaid(0);
            if (!forge.ai.ComputerUtilCost.canPayCost(sa, opp, false)) {
                return null; // not payable on its own (colors), at X = 0
            }
            if (unsure != null) {
                return lbUnsure(unsure);
            }
            // the cost: mana at X = 0 with the engine's cost adjustments, mana per X, and how often the other parts allow it
            int cap = ab.cap > 0 ? ab.cap : sa.isSpell() ? 1 : LB_UNLIMITED;
            boolean selfGone = false;
            // the other permanents a cost part takes (one part only), and how many per activation
            List<Card> taken = null;
            int takenPer = 0, takingParts = 0;
            if (cost != null) {
                if (cost.hasManaCost()) {
                    ab.cost = Math.max(0, forge.ai.ComputerUtilMana.calculateManaCost(cost, sa, opp, true, 0, false)
                            .getConvertedManaCost());
                    ab.xPer = cost.getTotalMana().countX();
                }
                for (forge.game.cost.CostPart part : cost.getCostParts()) {
                    if (part instanceof forge.game.cost.CostPartMana) {
                        continue;
                    }
                    if (part instanceof forge.game.cost.CostTap) {
                        cap = Math.min(cap, 1);
                        ab.hostTaken = true; // tapped: its own mana is spent (charged below)
                        continue;
                    }
                    if (part instanceof forge.game.cost.CostUntap || part instanceof forge.game.cost.CostExert) {
                        cap = Math.min(cap, 1);
                        continue;
                    }
                    if (part.payCostFromSource() && (part instanceof forge.game.cost.CostSacrifice
                            || part instanceof forge.game.cost.CostExile || part instanceof forge.game.cost.CostReturn
                            || part instanceof forge.game.cost.CostDiscard || part instanceof forge.game.cost.CostPutCardToLib)) {
                        cap = Math.min(cap, 1); // the host leaves: once, and not a beneficiary
                        selfGone = true;
                        ab.hostTaken = true; // and its mana is spent (charged below)
                        continue;
                    }
                    final Integer amt = part.convertAmount();
                    if (amt == null) {
                        return lbUnsure("a cost it cannot count (" + part.getClass().getSimpleName() + ")");
                    }
                    if (amt <= 0) {
                        continue;
                    }
                    // re-review 2 S1: counters are a pool a mana ability, another pump or an attacker's own +1/+1
                    // counters may share; the bound does not split them
                    if (part instanceof forge.game.cost.CostRemoveCounter
                            || part instanceof forge.game.cost.CostRemoveAnyCounter) {
                        return lbUnsure("a cost that removes counters");
                    }
                    if (!lbPublicFilter(part.getType())) {
                        return lbUnsure("a cost filter that compares against a named amount");
                    }
                    final Integer m = part.getMaxAmountX(sa, opp, false);
                    if (m == null) {
                        return lbUnsure("a cost it cannot count (" + part.getClass().getSimpleName() + ")");
                    }
                    cap = Math.min(cap, Math.max(0, m) / amt);
                    ab.pool = true; // life, energy, permanents...: counted against this ability alone
                    final List<Card> cards = lbCostCards(part, sa, opp);
                    if (cards != null) {
                        takingParts++;
                        taken = new ArrayList<>(cards);
                        if (!(part instanceof forge.game.cost.CostTapType)) {
                            // an attacker sacrificed, returned or exiled leaves combat and deals nothing: never fodder
                            // here (its damage stays counted), so the list caps the activations below
                            taken.removeAll(atk);
                        }
                        takenPer = amt;
                    }
                }
            }
            if (selfGone && atk.contains(host)) {
                return null; // re-review 3: an attacker that leaves to pay its own cost deals nothing; its damage stays counted, so this is not
            }
            if (!sa.isSpell()) {
                // activation limits: read from the text only (lbAmount)
                final forge.game.spellability.SpellAbilityRestriction r = sa.getRestrictions();
                if (r != null && r.getLimitToCheck() != null) {
                    final Integer lim = lbLiteral(r.getLimitToCheck(), sa);
                    if (lim == null) {
                        return lbUnsure("an activation limit that is not a number");
                    }
                    cap = Math.min(cap, Math.max(0, lim - sa.getActivationsThisTurn()));
                }
                if (r != null && r.getGameLimitToCheck() != null) {
                    final Integer lim = lbLiteral(r.getGameLimitToCheck(), sa);
                    if (lim == null) {
                        return lbUnsure("an activation limit that is not a number");
                    }
                    cap = Math.min(cap, Math.max(0, lim - sa.getActivationsThisGame()));
                }
            }
            // joint allocation: the mana of the sources the other costs tap or take is spent with them
            final int hostMana = ab.hostTaken && host != null ? sources.getOrDefault(host.getId(), 0) : 0;
            int[] takenMana = new int[0];
            if (taken != null) {
                if (ab.hostTaken) {
                    taken.remove(host); // charged as the host
                }
                takenMana = new int[taken.size()];
                for (int i = 0; i < takenMana.length; i++) {
                    takenMana[i] = sources.getOrDefault(taken.get(i).getId(), 0);
                }
                Arrays.sort(takenMana); // the cheapest choices first
                cap = Math.min(cap, takenMana.length / Math.max(1, takenPer));
                if (takingParts > 1) {
                    return lbUnsure("several costs that may take the same permanents"); // each part was capped alone
                }
                ab.taken = taken;
            }
            if (cap <= 0) {
                return null;
            }
            ab.cap = cap;
            if (hostMana > 0 || takenMana.length > 0 && takenMana[takenMana.length - 1] > 0) {
                final int top = Math.min(cap, Math.max(1, takenMana.length)); // cap is finite here (a host or a list)
                ab.extra = new int[top + 1];
                for (int j = 1; j <= top; j++) {
                    int lost = hostMana;
                    for (int u = 0; u < Math.min(takenMana.length, j * takenPer); u++) {
                        lost += takenMana[u];
                    }
                    ab.extra[j] = lost;
                }
            }
            if (selfGone) {
                // the cost takes the host itself: it is not there to be pumped
                for (LbPart part : ab.parts) {
                    for (int i = 0; i < na; i++) {
                        part.hit[i] &= atk.get(i) != host;
                    }
                }
                for (int i = 0; i < na; i++) {
                    if (atk.get(i) == host) {
                        ab.ds[i] = ab.trample[i] = ab.deathtouch[i] = false;
                    }
                }
            }
            ab.xMax = ab.xPer > 0 ? Math.max(0, (mana - ab.cost - ab.extra(1)) / ab.xPer) : 0;
            // the power each part adds, for every X the mana allows: from the amount's text only (an integer, or the X
            // paid: the root's X, as the engine reads it); never an evaluated value
            for (LbPart part : ab.parts) {
                final int[] coef = lbAmount(part.num, part.owner);
                if (coef == null) {
                    return lbUnsure("an amount that is not a number or X (" + part.num + ")");
                }
                part.amount = new int[ab.xMax + 1][na];
                for (int x = 0; x <= ab.xMax; x++) {
                    final long v = ((long) coef[0] + (long) coef[1] * x) * (part.perUnit == 0 ? 1 : part.perUnit);
                    for (int i = 0; i < na; i++) {
                        if (part.hit[i]) {
                            part.amount[x][i] = (int) Math.max(0, Math.min(1 << 20, v));
                        }
                    }
                }
            }
        } finally {
            root.setXManaCostPaid(xWas);
        }
        if (ab.xPer == 0 && ab.cost == 0 && ab.cap == LB_UNLIMITED) {
            for (LbPart part : ab.parts) {
                for (int i = 0; i < na; i++) {
                    if (part.amount[0][i] > 0) {
                        return lbUnsure("a free pump with no limit");
                    }
                }
            }
        }
        return ab;
    }

    /**
     * lethalBlock: which attackers part {@code s} may hit, or null for none. Targeted: every attacker it can target, up to
     * its most targets. All (ValidCards, or a Choices list): its current valid set (a choice picks one). Defined (default
     * Self): those cards; a Defined that names a parent's target picks among the attackers that parent can target, and one
     * that names nothing yet (Remembered, Chosen...) picks any attacker. Its {@link LbPart#unsure} is set (whoever it
     * hits) when a target count is not a number or a filter compares against a named amount ({@link #lbPublicFilter}).
     */
    static LbPart lbHits(SpellAbility s, List<Card> atk, String validKey) {
        final int na = atk.size();
        final LbPart part = new LbPart();
        part.owner = s;
        part.hit = new boolean[na];
        final Card host = s.getHostCard();
        if (s.hasParam("Bolster")) {
            // the creature with the least toughness among its controller's: any attacker of it
            part.pick = true;
            for (int i = 0; i < na; i++) {
                part.hit[i] = atk.get(i).getController().equals(s.getActivatingPlayer());
            }
        } else if (s.usesTargeting()) {
            part.pick = true;
            final Integer most = lbTargets(s);
            if (most == null) {
                part.unsure = "a target count or target filter that is not read from its text";
                return part;
            }
            part.maxTargets = Math.max(1, most);
            for (int i = 0; i < na; i++) {
                part.hit[i] = s.canTarget(atk.get(i));
            }
        } else if (validKey != null && s.hasParam(validKey) || s.hasParam("Choices")) {
            final String valid = validKey != null && s.hasParam(validKey) ? s.getParam(validKey) : s.getParam("Choices");
            if (!lbPublicFilter(valid)) {
                part.unsure = "a filter that compares against a named amount";
                return part;
            }
            final CardCollectionView set = forge.game.ability.AbilityUtils.filterListByType(
                    host.getGame().getCardsIn(ZoneType.Battlefield), valid, s);
            part.pick = !(validKey != null && s.hasParam(validKey));
            for (int i = 0; i < na; i++) {
                part.hit[i] = set.contains(atk.get(i));
            }
        } else {
            final String def = s.getParamOrDefault("Defined", "Self");
            if (!lbPublicFilter(def)) {
                part.unsure = "a filter that compares against a named amount";
                return part;
            }
            if (def.contains("Target")) {
                SpellAbility t = s.getParent();
                while (t != null && !t.usesTargeting()) {
                    t = t.getParent();
                }
                part.pick = true;
                final Integer most = t == null ? Integer.valueOf(1) : lbTargets(t);
                if (most == null) {
                    part.unsure = "a target count or target filter that is not read from its text";
                    return part;
                }
                part.maxTargets = Math.max(1, most);
                for (int i = 0; i < na; i++) {
                    part.hit[i] = t == null || t.canTarget(atk.get(i));
                }
            } else {
                final List<Card> cards = new ArrayList<>();
                for (String d : def.split(" & ")) {
                    cards.addAll(forge.game.ability.AbilityUtils.getDefinedCards(host, d, s));
                }
                if (cards.isEmpty() && !"Self".equals(def)) {
                    part.pick = true;
                    Arrays.fill(part.hit, true);
                } else {
                    for (int i = 0; i < na; i++) {
                        part.hit[i] = cards.contains(atk.get(i));
                    }
                }
            }
        }
        for (boolean h : part.hit) {
            if (h) {
                return part;
            }
        }
        return null;
    }

    /**
     * lethalBlock: the most targets of {@code s} read from its text (TargetMax, default 1), or null when that is not a
     * number or a target filter compares against a named amount ({@link #lbPublicFilter}).
     */
    static Integer lbTargets(SpellAbility s) {
        final forge.game.spellability.TargetRestrictions tr = s.getTargetRestrictions();
        if (tr == null) {
            return 1;
        }
        if (tr.getValidTgts() != null) {
            for (String v : tr.getValidTgts()) {
                if (!lbPublicFilter(v)) {
                    return null;
                }
            }
        }
        return tr.getMaxTargets() == null ? Integer.valueOf(1) : lbLiteral(tr.getMaxTargets(), s);
    }

    /** lethalBlock: APIs that change power or characteristics other than Pump, PumpAll and counters. */
    static final Set<String> LB_POWER_APIS = new HashSet<>(Arrays.asList("Animate", "AnimateAll", "SetState", "Clone",
            "ExchangePower", "SwitchPT", "Mutate", "Effect", "Attach"));

    /**
     * lethalBlock: part {@code s} may change an attacker's power another way: an API of {@link #LB_POWER_APIS} whose
     * parameters, or the SVars they name, set or add power (Power, AddPower, SetPower, a power static of an Effect), on an
     * attacker it may hit (an Effect's statics are not read for whom they affect: any attacker); or one that copies,
     * transforms, exchanges or mutates onto an attacker it may hit.
     */
    static boolean lbOtherPower(SpellAbility s, List<Card> atk) {
        final String api = s.getApi() == null ? "" : s.getApi().name();
        if (!LB_POWER_APIS.contains(api)) {
            return false;
        }
        final boolean setsPower = paramsMatch(s.getMapParams(), s, new HashSet<>(),
                (k, v) -> k.contains("Power") || k.equals("StaticAbilities") && v.length() > 0 && lbStaticsAddPower(s, v),
                v -> v.contains("AddPower") || v.contains("SetPower"));
        boolean characteristics = "SetState".equals(api) || "Clone".equals(api) || "ExchangePower".equals(api)
                || "SwitchPT".equals(api) || "Mutate".equals(api);
        if ("Attach".equals(api)) {
            // an Aura or Equipment attached now: its own statics may add power to what it is attached to
            for (forge.game.staticability.StaticAbility st : s.getHostCard().getStaticAbilities()) {
                characteristics |= st.hasParam("AddPower") || st.hasParam("SetPower");
            }
        }
        if (!setsPower && !characteristics) {
            return false;
        }
        if ("Effect".equals(api)) {
            return true;
        }
        final LbPart hit = lbHits(s, atk, "ValidCards");
        return hit != null;
    }

    /** lethalBlock: an Effect's StaticAbilities (SVar names) carry a power change. */
    private static boolean lbStaticsAddPower(SpellAbility s, String names) {
        for (String nm : names.split(",")) {
            final String sv = s.hasSVar(nm.trim()) ? s.getSVar(nm.trim()) : "";
            if (sv.contains("AddPower") || sv.contains("SetPower")) {
                return true;
            }
        }
        return false;
    }

    /**
     * lethalBlock: the most damage per point of weight the abilities add with {@code mana}: a group knapsack (one option
     * per item: an ability's activation count, or its X, or one of a card's spells), damage = power x weight (strikes; a
     * pick hits its best attackers).
     */
    static int lbBoost(List<List<LbAbility>> items, int[] w, int mana) {
        boolean any = false;
        for (int x : w) {
            any |= x > 0;
        }
        if (!any || items.isEmpty()) {
            return 0;
        }
        int[] best = new int[mana + 1];
        for (List<LbAbility> item : items) {
            final List<int[]> opts = new ArrayList<>(); // {mana, damage}
            for (LbAbility ab : item) {
                if (ab.parts.isEmpty()) {
                    continue;
                }
                // an option's mana: its mana cost plus the mana its other costs give up (ab.extra: a source never pays twice)
                if (ab.xPer > 0) {
                    for (int x = 0; x <= ab.xMax; x++) {
                        final int c = ab.cost + x * ab.xPer + ab.extra(1);
                        final int d = lbGain(ab, x, w);
                        if (c <= mana && d > 0) {
                            opts.add(new int[] {c, d});
                        }
                    }
                } else {
                    final int d = lbGain(ab, 0, w);
                    if (d <= 0) {
                        continue;
                    }
                    if (ab.cost == 0 && ab.extra == null) {
                        // free: all of its activations at once
                        opts.add(new int[] {0, (int) Math.min(1 << 24, (long) Math.min(ab.cap, 1 << 16) * d)});
                        continue;
                    }
                    final int most = Math.min(ab.cap, ab.cost == 0 ? 1 << 16 : mana / ab.cost);
                    for (int j = 1; j <= most; j++) {
                        final long c = (long) j * ab.cost + ab.extra(j);
                        if (c > mana) {
                            break;
                        }
                        opts.add(new int[] {(int) c, (int) Math.min(1 << 24, (long) j * d)});
                    }
                }
            }
            if (opts.isEmpty()) {
                continue;
            }
            final int[] next = best.clone();
            for (int b = 0; b <= mana; b++) {
                for (int[] o : opts) {
                    if (o[0] <= b) {
                        next[b] = Math.max(next[b], (int) Math.min(1 << 26, (long) best[b - o[0]] + o[1]));
                    }
                }
            }
            best = next;
        }
        return best[mana];
    }

    /** lethalBlock: one activation's damage at X = {@code x}: per part, power x weight over what it hits (a pick: its best). */
    static int lbGain(LbAbility ab, int x, int[] w) {
        long sum = 0;
        for (LbPart part : ab.parts) {
            final int[] am = part.amount[Math.min(x, part.amount.length - 1)];
            if (part.pick) {
                final List<Integer> vs = new ArrayList<>();
                for (int i = 0; i < w.length; i++) {
                    if (part.hit[i] && am[i] * w[i] > 0) {
                        vs.add(am[i] * w[i]);
                    }
                }
                vs.sort(Collections.reverseOrder());
                for (int j = 0; j < Math.min(part.maxTargets, vs.size()); j++) {
                    sum += vs.get(j);
                }
            } else {
                for (int i = 0; i < w.length; i++) {
                    if (part.hit[i]) {
                        sum += (long) am[i] * w[i];
                    }
                }
            }
        }
        return (int) Math.min(1 << 24, sum);
    }

    // ------------------------------------------------------------------ tutor ranking (lane tutor-ranking-0928)

    /** Tutor ranking: the last searched choice's candidates (Forge AI's pick first) and per-candidate play-out ok (tests). */
    List<String> lastTutorCandidates = null;
    boolean[] lastTutorOk = null;
    double[] lastTutorEv = null;

    /** Play-out Forge AI whose first single-card library search takes the card with a given id (if offered). */
    static final class ScriptedTutor extends RolloutAi {
        private final int forcedId;
        boolean tried = false;
        boolean used = false;

        ScriptedTutor(Game g, Player p, forge.LobbyPlayer lp, int forcedId) {
            super(g, p, lp);
            this.forcedId = forcedId;
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
                forge.game.player.DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
            if (!tried) {
                tried = true;
                for (Card c : fetchList) {
                    if (c.getId() == forcedId) {
                        used = true;
                        return c;
                    }
                }
            }
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        }
    }

    /**
     * A single-card library search of this seat, asked while the live game resolves {@code sa} (Forge AI already chose
     * {@code def}). Forge AI's pick and the top-{@code tutorRank} other names by the ranker's score are each played out
     * over K worlds from a copy of the position in which the search's resolution is replayed with that card forced; the
     * best by expected value is returned (ties, failures and unsupported positions: Forge AI's pick). Never throws.
     */
    public Card decideTutor(PlayerControllerAi ctrl, ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, Card def, Player decider) {
        if (tutor == null) {
            return def;
        }
        final Game live = ctrl.getGame();
        final Player me = ctrl.getPlayer();
        if (decider != me || sa == null || origin == null || origin.size() != 1 || origin.get(0) != ZoneType.Library
                || fetchList == null || fetchList.isEmpty()) {
            return def;
        }
        final CardCollectionView myLib = me.getCardsIn(ZoneType.Library);
        for (Card c : fetchList) {
            if (!myLib.contains(c)) {
                return def;
            }
        }
        final long t0 = System.nanoTime();
        stats.tutorSeen++;
        final int index = tutorIndex++;
        final JsonObject entry = new JsonObject();
        try {
            return decideTutor0(live, me, destination, sa, fetchList, def, index, entry);
        } catch (RuntimeException | StackOverflowError e) {
            stats.tutorUnsupported++;
            stats.tutorWhy.merge("error", 1L, Long::sum);
            stats.tutorLastError = String.valueOf(e);
            entry.addProperty("outcome", "error");
            entry.addProperty("error", String.valueOf(e));
            System.err.println("[lookahead] tutor search failed at choice " + index + ", keeping Forge's pick: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
            return def;
        } finally {
            final long dt = System.nanoTime() - t0;
            stats.tutorNanos += dt;
            if (entry.size() > 0) {
                entry.addProperty("ms", Math.round(dt / 1e4) / 100.0);
                stats.tutorDecisions.add(entry);
            }
        }
    }

    private Card decideTutor0(Game live, Player me, ZoneType destination, SpellAbility sa, CardCollection fetchList, Card def,
            int index, JsonObject entry) {
        // Distinct names in card-id order; a name's representative is its lowest id.
        final List<Card> sorted = new ArrayList<>(fetchList);
        sorted.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        final Map<String, Card> rep = new java.util.LinkedHashMap<>();
        boolean landsOnly = true;
        for (Card c : sorted) {
            rep.putIfAbsent(c.getName(), c);
            landsOnly &= c.isLand();
        }
        final PhaseHandler ph = live.getPhaseHandler();
        final String dest = destName(destination, sa);
        entry.addProperty("i", index);
        entry.addProperty("turn", ph.getTurn());
        entry.addProperty("phase", String.valueOf(ph.getPhase()));
        entry.addProperty("active", ph.getPlayerTurn() == me);
        entry.addProperty("src", sa.getHostCard().getName());
        entry.addProperty("dest", dest);
        entry.addProperty("names", rep.size());
        entry.addProperty("landsOnly", landsOnly);
        entry.addProperty("forge", def == null ? null : def.getName());
        if (landsOnly && !cfg.tutorLands) {
            stats.tutorLandOnly++;
            entry.addProperty("outcome", "landsOnly");
            return def;
        }
        if (rep.size() < 2) {
            stats.tutorSingle++;
            entry.addProperty("outcome", "single");
            return def;
        }
        if (def == null || !rep.containsKey(def.getName())) {
            stats.tutorForgeNone++;
            entry.addProperty("outcome", "forgeNone");
            return def;
        }
        String why = GameCopier.stackUnsupported(live, true);
        if (why == null) {
            final SpellAbility top = live.getStack().isEmpty() ? null : live.getStack().peekAbility();
            if (!live.getStack().isResolving() || top == null) {
                why = "notResolving";
            } else if (sa.getRootAbility() != sa) {
                // Effects before the search in the same ability would be replayed with it.
                why = "subAbility";
            } else if (top != sa) {
                if (top instanceof forge.game.trigger.WrappedAbility wa && wa.getWrappedAbility() == sa) {
                    final forge.game.trigger.Trigger t = wa.getTrigger();
                    final int ti = t == null ? -1 : triggerIndex(wa.getHostCard(), t);
                    if (t == null || t.isStatic() || ti < 0) {
                        why = "triggerForm";
                    }
                } else {
                    why = "notTop";
                }
            }
        }
        if (why != null) {
            stats.tutorUnsupported++;
            stats.tutorWhy.merge(why, 1L, Long::sum);
            entry.addProperty("outcome", "unsupported:" + why);
            return def;
        }

        // The ranker: one call over every distinct name.
        final List<String> names = new ArrayList<>(rep.keySet());
        final JsonObject req = tutorRequest(live, me, sa, dest, names);
        final double[] sc = tutor.rank(req, names.size());
        stats.tutorCalls++;
        if (sc == null) {
            stats.tutorFailures++;
            stats.tutorLastError = tutor.lastError;
            entry.addProperty("outcome", "rankFailed");
            entry.addProperty("error", tutor.lastError);
            System.err.println("[lookahead] tutor ranker failed at choice " + index + ", keeping Forge's pick: " + tutor.lastError);
            return def;
        }
        stats.tutorDigest = tutor.digestHex();
        stats.tutorCheckpoint = tutor.checkpointSha256;
        stats.tutorUnknownCards = tutor.unknownCards;
        final Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> {
            final int c = Double.compare(sc[y], sc[x]);
            return c != 0 ? c : Integer.compare(x, y);
        });
        final String forgeName = def.getName();
        if (names.get(order[0]).equals(forgeName)) {
            stats.tutorTop1IsForge++;
        }
        final List<String> cands = new ArrayList<>();
        cands.add(forgeName);
        final JsonArray ranked = new JsonArray();
        for (Integer o : order) {
            final String nm = names.get(o);
            if (ranked.size() < Math.max(cfg.tutorRank, 5)) {
                final JsonObject r = new JsonObject();
                r.addProperty("name", nm);
                r.addProperty("score", Math.round(sc[o] * 1e4) / 1e4);
                ranked.add(r);
            }
            if (!nm.equals(forgeName) && cands.size() < 1 + cfg.tutorRank) {
                cands.add(nm);
            }
        }
        entry.add("ranked", ranked);
        final int forgeRank = Arrays.asList(order).indexOf(names.indexOf(forgeName));
        entry.addProperty("forgeRank", forgeRank);
        final JsonArray cj = new JsonArray();
        cands.forEach(cj::add);
        entry.add("cands", cj);

        // Play-outs: every candidate x world, copies made here in a fixed order, then played (pool if threads > 1).
        stats.tutorSearched++;
        final int k = Math.max(1, cfg.worlds);
        final int n = cands.size();
        final long seed = mix(cfg.seed, 0x7a70000L + index);
        final Prepared[][] prep = new Prepared[n][k];
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                prep[c][w] = prepareTutor(live, me, rep.get(cands.get(c)).getId(), mix(seed, 1000 + w), sa);
            }
        }
        final double[][] values = new double[n][k];
        final boolean[] ok = new boolean[n];
        Arrays.fill(ok, true);
        final List<Runnable> tasks = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                final int ww = w, cc = c;
                tasks.add(() -> {
                    final Rollout r = playTutor(prep[cc][ww]);
                    prep[cc][ww] = null;
                    synchronized (values) {
                        values[cc][ww] = r.value;
                        if (!r.ok) {
                            ok[cc] = false;
                            stats.tutorRolloutFailures++;
                        }
                        stats.tutorRollouts++;
                    }
                });
            }
        }
        runAll(tasks);
        final double[] ev = new double[n];
        int best = 0;
        for (int c = 0; c < n; c++) {
            double s = 0;
            for (int w = 0; w < k; w++) {
                s += values[c][w];
            }
            ev[c] = ok[c] ? s / k : Double.NEGATIVE_INFINITY;
        }
        if (ok[0]) {
            for (int c = 1; c < n; c++) {
                if (!ok[c] || (cfg.departZ > 0 && !confident(values, c, k, cfg.departZ))) {
                    continue;
                }
                if (ev[c] > ev[best] + (best == 0 ? cfg.margin : 0)) {
                    best = c;
                }
            }
        }
        lastTutorCandidates = cands;
        lastTutorOk = ok.clone();
        lastTutorEv = ev.clone();
        final JsonArray evj = new JsonArray();
        for (int c = 0; c < n; c++) {
            evj.add(ok[c] ? Math.round(ev[c] * 100) / 100.0 : null);
        }
        entry.add("ev", evj);
        entry.addProperty("best", best);
        if (best == 0) {
            entry.addProperty("outcome", "kept");
            return def;
        }
        if (cfg.tutorShadow) {
            stats.tutorShadowWouldDepart++;
            entry.addProperty("outcome", "shadow-would-depart");
            return def;
        }
        stats.tutorDeparted++;
        entry.addProperty("outcome", "departed");
        return rep.get(cands.get(best));
    }

    /** Measurement only (-Dsdcc.stackNote=true): a decision row for a non-empty stack says what was on it. */
    static final boolean STACK_NOTE = Boolean.getBoolean("sdcc.stackNote");

    /**
     * Measurement only: the stack at a priority decision, top first (kind, host, whose), whose turn it is, and the
     * copier's reason for refusing it (or "searched").
     */
    static JsonObject stackNote(Game live, Player me, String why) {
        final JsonObject o = new JsonObject();
        o.addProperty("why", why);
        o.addProperty("active", live.getPhaseHandler().getPlayerTurn() == me ? "me" : "opp");
        final JsonArray es = new JsonArray();
        for (forge.game.spellability.SpellAbilityStackInstance si : live.getStack()) {
            final SpellAbility sa = si.getSpellAbility();
            final JsonObject e = new JsonObject();
            e.addProperty("kind", si.isTrigger() || sa.isTrigger() || sa.isWrapper() ? "trigger" : sa.isSpell() ? "spell" : "ability");
            e.addProperty("host", sa.getHostCard() == null ? "?" : sa.getHostCard().getName());
            e.addProperty("who", sa.getActivatingPlayer() == me ? "me" : "opp");
            if (sa instanceof forge.game.trigger.WrappedAbility w) {
                final forge.game.trigger.Trigger t = w.getTrigger();
                e.addProperty("mode", t == null ? "?" : String.valueOf(t.getMode()));
                e.addProperty("own_trigger", t != null && triggerIndex(w.getHostCard(), t) >= 0);
                e.addProperty("optional", sa.isOptionalTrigger());
                e.addProperty("targets", w.getWrappedAbility() != null && w.getWrappedAbility().usesTargeting());
            }
            es.add(e);
        }
        o.add("stack", es);
        o.addProperty("would", String.valueOf(GameCopier.stackUnsupported(live, false, true)));
        return o;
    }

    /** The trigger's position among its host's triggers (-1 if absent). */
    static int triggerIndex(Card host, forge.game.trigger.Trigger t) {
        int i = 0;
        for (forge.game.trigger.Trigger x : host.getTriggers()) {
            if (x == t) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** The resolving trigger's ability, built in the copy from the copied host's trigger at the same position. */
    static SpellAbility rebuildTrigger(Game g, Player me, forge.game.trigger.WrappedAbility liveWrap) {
        final forge.game.trigger.Trigger lt = liveWrap.getTrigger();
        final int idx = triggerIndex(liveWrap.getHostCard(), lt);
        final Card host = g.findById(liveWrap.getHostCard().getId());
        if (host == null || idx < 0) {
            throw new IllegalStateException("tutor copy: trigger host " + liveWrap.getHostCard() + " missing");
        }
        forge.game.trigger.Trigger t = null;
        int i = 0;
        for (forge.game.trigger.Trigger x : host.getTriggers()) {
            if (i++ == idx) {
                t = x;
                break;
            }
        }
        if (t == null || t.getMode() != lt.getMode() || !java.util.Objects.equals(t.getParam("Execute"), lt.getParam("Execute"))) {
            throw new IllegalStateException("tutor copy: trigger " + idx + " of " + host + " does not match");
        }
        SpellAbility sa = t.getOverridingAbility();
        if (sa == null) {
            sa = forge.game.ability.AbilityFactory.getAbility(host, t.getParam("Execute"));
            sa.setActivatingPlayer(me);
            if (t.isIntrinsic()) {
                sa.setIntrinsic(true);
                sa.changeText();
            }
        } else {
            sa = sa.copy(host, me, false, true);
        }
        sa.setTrigger(t);
        sa.setActivatingPlayer(me);
        return sa;
    }

    private static String destName(ZoneType destination, SpellAbility sa) {
        if (destination == ZoneType.Hand) {
            return "hand";
        }
        if (destination == ZoneType.Battlefield) {
            return "battlefield";
        }
        if (destination == ZoneType.Graveyard) {
            return "graveyard";
        }
        if (destination == ZoneType.Library) {
            return "0".equals(sa.getParamOrDefault("LibraryPosition", "0")) ? "top" : "library";
        }
        return String.valueOf(destination).toLowerCase();
    }

    /** The ranker request: the search's source, destination, the distinct names, the deck and a small state. */
    private JsonObject tutorRequest(Game live, Player me, SpellAbility sa, String dest, List<String> names) {
        final JsonObject r = new JsonObject();
        r.addProperty("schema", TutorRankClient.SCHEMA);
        r.addProperty("tutor", sa.getHostCard().getName());
        r.add("cls", com.google.gson.JsonNull.INSTANCE);
        r.addProperty("dest", dest);
        final JsonArray cj = new JsonArray();
        names.forEach(cj::add);
        r.add("candidates", cj);
        r.add("deck", deckOf(me));
        final JsonObject st = new JsonObject();
        final PhaseHandler ph = live.getPhaseHandler();
        final boolean onPlay = live.getStartingPlayer() == me;
        // 17Lands counts each player's own turns; Forge counts both.
        st.addProperty("turn", onPlay ? (ph.getTurn() + 1) / 2 : ph.getTurn() / 2);
        st.addProperty("globalTurn", ph.getTurn());
        st.addProperty("onPlay", onPlay);
        st.addProperty("active", ph.getPlayerTurn() == me);
        st.add("hand", namesOf(me.getCardsIn(ZoneType.Hand)));
        final JsonArray lands = new JsonArray(), creatures = new JsonArray(), other = new JsonArray();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            (c.isLand() ? lands : c.isCreature() ? creatures : other).add(c.getName());
        }
        st.add("lands", lands);
        st.add("creatures", creatures);
        st.add("noncreatures", other);
        Player opp = null;
        for (Player o : me.getOpponents()) {
            opp = o;
            break;
        }
        if (opp != null) {
            int ol = 0;
            final JsonArray oc = new JsonArray(), on = new JsonArray();
            for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
                if (c.isLand()) {
                    ol++;
                } else if (c.isCreature()) {
                    oc.add(c.getName());
                } else {
                    on.add(c.getName());
                }
            }
            st.addProperty("oppLands", ol);
            st.add("oppCreatures", oc);
            st.add("oppNoncreatures", on);
            st.addProperty("oppHand", opp.getCardsIn(ZoneType.Hand).size());
            st.addProperty("oppLife", opp.getLife());
        }
        st.addProperty("life", me.getLife());
        final TreeMap<String, Integer> lib = new TreeMap<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            lib.merge(c.getName(), 1, Integer::sum);
        }
        final JsonObject lj = new JsonObject();
        lib.forEach(lj::addProperty);
        st.add("library", lj);
        r.add("state", st);
        return r;
    }

    private static JsonArray namesOf(Iterable<Card> cs) {
        final JsonArray a = new JsonArray();
        for (Card c : cs) {
            a.add(c.getName());
        }
        return a;
    }

    /**
     * One tutor play-out's copy: the live position with its whole stack (the searching ability on top, not yet
     * resolved in the copy), the world resampled, and the card to force.
     */
    Prepared prepareTutor(Game live, Player liveMe, int forcedId, long worldSeed, SpellAbility searching) {
        final Prepared p = new Prepared();
        p.forcedId = forcedId;
        final SpellAbility liveTop = live.getStack().peekAbility();
        final forge.game.trigger.WrappedAbility liveWrap = liveTop instanceof forge.game.trigger.WrappedAbility w ? w : null;
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(worldSeed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final long a = System.nanoTime();
            synchronized (live) {
                final GameCopier copier = copierOf(live);
                copier.setCopyStack(true);
                copier.setSkipResolvingTrigger(true); // (only acts on a resolving top trigger and on gone paid tokens)
                p.g = copier.makeCopy();
                p.me = (Player) copier.find(liveMe);
                if (liveWrap != null) {
                    // The resolving trigger is rebuilt in the copy as Forge builds it (TriggerHandler.runSingleTriggerInternal).
                    p.trigSa = rebuildTrigger(p.g, p.me, liveWrap);
                }
                if (cfg.resample) {
                    redraw(live, liveMe, p.g, p.me, new Random(mix(worldSeed, 2)));
                }
                MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(worldSeed, 3)));
            }
            if ((liveWrap == null && p.g.getStack().isEmpty()) || p.g.findById(forcedId) == null) {
                throw new IllegalStateException("tutor copy: empty stack or card " + forcedId + " missing");
            }
            p.copyNanos = System.nanoTime() - a;
        } catch (RuntimeException | StackOverflowError e) {
            p.failed = true;
            p.error = e;
        } finally {
            p.ids = forge.util.IdScope.capture();
            p.cache = AiCache.captureScope();
            p.rnd = MyRandom.getThreadRandom();
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
        return p;
    }

    /** Resolve the copy's top (the search, with the forced card), then Forge AI on both seats to the horizon. */
    Rollout playTutor(Prepared p) {
        final Rollout r = new Rollout();
        if (p.failed) {
            r.ok = false;
            r.value = Double.NEGATIVE_INFINITY;
            if (p.error != null) {
                System.err.println("[lookahead] tutor copy failed: " + p.error);
                if (FAILURE_TRACES.getAndIncrement() < 20) {
                    p.error.printStackTrace();
                }
            }
            return r;
        }
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(p.rnd);
        AiCache.installScope(p.cache);
        forge.util.IdScope.install(p.ids);
        try {
            final Game g = p.g;
            final Player me = p.me;
            final ScriptedTutor st = new ScriptedTutor(g, me, me.getController().getLobbyPlayer(), p.forcedId);
            me.dangerouslySetController(st);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new RolloutAi(g, o, o.getController().getLobbyPlayer()));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            final TurnWatch watch = new TurnWatch(ph.getTurn() + cfg.horizonTurns, null);
            g.subscribeToEvents(watch);
            final long b = System.nanoTime();
            // Both players had passed in the live game: the search's ability resolves now (as mainLoopStep would).
            if (p.trigSa != null) {
                // A triggered search: resolve its ability as WrappedAbility.resolve does (the optional "you may" was
                // already accepted in the live game), then what MagicStack.finishResolving does for the priority.
                me.getController().playSpellAbilityNoStack(p.trigSa, false);
                g.getAction().checkStaticAbilities();
                ph.resetPriority();
                ph.onStackResolved();
            } else {
                g.getStack().resolveStack();
            }
            if (!st.used) {
                synchronized (stats) {
                    stats.tutorForcedMissed++;
                }
                r.ok = false;
                r.value = Double.NEGATIVE_INFINITY;
                return r;
            }
            int steps = 0;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.rolloutNanos = System.nanoTime() - b;
            r.steps = steps;
            r.capped = !g.isGameOver() && !watch.reached;
            r.value = value(g, me);
        } catch (RuntimeException | StackOverflowError e) {
            r.ok = false;
            r.value = Double.NEGATIVE_INFINITY;
            System.err.println("[lookahead] tutor rollout failed: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
        } finally {
            p.g = null;
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    // ------------------------------------------------------------------ belief

    /**
     * Re-draw what the searching seat cannot see: each opponent's hand (keeping the cards it
     * can see) together with their library, and our own library order. Sizes are preserved.
     * The belief is uniform over the opponent's registered deck remainder, the same prior
     * the mtgx belief sampler starts from.
     */
    /**
     * order-memory-1102 (Config#infoState): as {@link #resample(Game, Player, Game, Player, Random)}, the opponent-library
     * cards InfoState(me) knows kept at their indices (not only the visible top), every hidden card drawn from a
     * canonical order (by id), and our library through {@link InfoStates#keepOwnLibrary}.
     */
    static void resample(Game live, Player liveMe, Game g, Player me, Random rng, InfoStates is) {
        resample(live, liveMe, g, me, rng, is, null);
    }

    /**
     * attack-holdback-1118: as above; {@code known} (null = what Forge shows us, as before) is the dealers' rule for a live
     * opponent hand card ({@link #seatMayKnowHand}): a card it accepts stays in the hand.
     */
    static void resample(Game live, Player liveMe, Game g, Player me, Random rng, InfoStates is,
            java.util.function.Predicate<Card> known) {
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            Player liveOpp = (Player) live.getPlayer(opp.getId());
            List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            List<Card> lib = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
            List<Card> keep = new ArrayList<>();
            List<Card> pool = new ArrayList<>(lib);
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && (known != null ? known.test(lc) : lc.getView().canBeShownTo(myView))) {
                    keep.add(c);
                } else {
                    pool.add(c);
                }
            }
            final Map<Integer, Card> pins = liveOpp == null ? new java.util.TreeMap<>() : InfoStates.oppPins(is, liveMe, liveOpp, g);
            pool.removeAll(pins.values());
            pool.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
            Collections.shuffle(pool, rng);
            int need = hand.size() - keep.size();
            List<Card> newHand = new ArrayList<>(keep);
            newHand.addAll(pool.subList(0, need));
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(InfoStates.assemble(lib.size(), pins, pool.subList(need, pool.size())));
        }
        InfoStates.keepOwnLibrary(is, liveMe, me, rng);
    }

    static void resample(Game live, Player liveMe, Game g, Player me, Random rng) {
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            Player liveOpp = (Player) live.getPlayer(opp.getId());
            List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            List<Card> lib = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
            List<Card> keep = new ArrayList<>();
            List<Card> pool = new ArrayList<>(lib);
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && lc.getView().canBeShownTo(myView)) {
                    keep.add(c);
                } else {
                    pool.add(c);
                }
            }
            // Known library cards (revealed tops) stay where they are.
            List<Card> libKnownTop = new ArrayList<>();
            List<Card> liveLib = liveOpp == null ? Collections.emptyList() : new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library));
            for (Card lc : liveLib) {
                if (lc.getView().canBeShownTo(myView)) {
                    Card cc = g.findById(lc.getId());
                    if (cc != null) {
                        libKnownTop.add(cc);
                        pool.remove(cc);
                        continue;
                    }
                }
                break;
            }
            Collections.shuffle(pool, rng);
            int need = hand.size() - keep.size();
            List<Card> newHand = new ArrayList<>(keep);
            newHand.addAll(pool.subList(0, need));
            List<Card> newLib = new ArrayList<>(libKnownTop);
            newLib.addAll(pool.subList(need, pool.size()));
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(newLib);
        }
        shuffleOwnLibrary(liveMe, me, rng);
    }

    /** Our own library: keep the known top (scry/reveal), shuffle the rest. */
    static void shuffleOwnLibrary(Player liveMe, Player me, Random rng) {
        final PlayerView myView = liveMe.getView();
        List<Card> myLib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        List<Card> liveMyLib = new ArrayList<>(liveMe.getCardsIn(ZoneType.Library));
        int known = 0;
        for (Card lc : liveMyLib) {
            if (lc.getView().canBeShownTo(myView)) {
                known++;
            } else {
                break;
            }
        }
        List<Card> rest = new ArrayList<>(myLib.subList(known, myLib.size()));
        Collections.shuffle(rest, rng);
        List<Card> nl = new ArrayList<>(myLib.subList(0, known));
        nl.addAll(rest);
        me.getZone(ZoneType.Library).setCards(nl);
    }


    // ------------------------------------------------------------------ belief (lane belief-sampling-0928)

    private boolean rememberedHand(Game live, Player observer, Card card) {
        return cfg.beliefRememberedHand && hooks != null && card != null
                && card.getGame() == live && observer.getGame() == live
                && card.isInZone(ZoneType.Hand) && card.getController() != observer
                && !card.isFaceDown() && hooks.remembersHandCard(live, observer, card);
    }

    private boolean handKnown(Game live, Player observer, Card card) {
        return seatMayKnowHand(live, observer, card, true);
    }

    /**
     * attack-holdback-1118: the world dealers' one rule, "the seat may know this hand card" ({@code lc} in the live game):
     * Forge shows it to the seat, or {@link #knownHidden}. {@code remembered}: the dealer kept the seat's live tracker's
     * remembered cards before knownHand ({@link Config#beliefRememberedHand}); with knownHand off every dealer is
     * exactly as before, with it on every dealer keeps the same cards: {@code canBeShownTo || InfoStates.knowsInHand}.
     */
    boolean seatMayKnowHand(Game live, Player liveMe, Card lc, boolean remembered) {
        return lc.getView().canBeShownTo(liveMe.getView()) || knownHidden(live, liveMe, lc, remembered);
    }

    /**
     * attack-holdback-1118: a hand card the seat knows without Forge showing it. With {@link Config#knownHand}: only
     * InfoState(me) saw it enter that hand and kept its identity since ({@link InfoStates#knowsInHand}); review #248
     * round 3: the live tracker ({@link #rememberedHand}) is not consulted, so it cannot restore what InfoStates forgot
     * (RlKnowledge keeps a known card after a public exit of another card with its name). Without knownHand: the
     * tracker's card where the dealer counted it before ({@code remembered}), byte for byte as before. Also a solver
     * re-draw's {@link ForcedWinSolver.Redraw#remembersHandCard}, so its world filter keeps what the re-draw kept.
     */
    private boolean knownHidden(Game live, Player liveMe, Card lc, boolean remembered) {
        if (cfg.knownHand) {
            return InfoStates.knownInHand(InfoStates.find(live), liveMe, lc);
        }
        return remembered && rememberedHand(live, liveMe, lc);
    }

    /** The opponent's hidden hand cards / known library top, as the seat sees them in the live game. */
    private int[] hiddenCounts(Game live, Player liveOpp, Player liveMe) {
        final PlayerView myView = liveMe.getView();
        int keep = 0;
        final List<Card> hand = new ArrayList<>(liveOpp.getCardsIn(ZoneType.Hand));
        for (Card c : hand) {
            if (handKnown(live, liveMe, c)) {
                keep++;
            }
        }
        final List<Card> lib = new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library));
        int top = 0;
        for (Card c : lib) {
            if (c.getView().canBeShownTo(myView)) {
                top++;
            } else {
                break;
            }
        }
        return new int[] {hand.size() - keep, lib.size() - top};
    }

    /**
     * The decision's belief over the opponent's hidden cards, or null (belief skipped: not a two-player game, or
     * nothing hidden). Pool = cube names (file order) minus our registered deck minus every non-token card the
     * opponent owns that the seat can see, plus the basics. belief=human asks the service (one call); a failure
     * samples uniformly over the same pool.
     */
    private BeliefSampler.Dist belief(Game live, Player liveMe, JsonObject root, int index, int turn, boolean onStack) {
        final long t0 = System.nanoTime();
        final List<Player> opps = new ArrayList<>(liveMe.getOpponents());
        if (opps.size() != 1) {
            stats.beliefSkipped++;
            return null;
        }
        final Player liveOpp = opps.get(0);
        final PlayerView myView = liveMe.getView();
        final int[] hid = hiddenCounts(live, liveOpp, liveMe);
        if (hid[0] == 0 && hid[1] == 0) {
            stats.beliefSkipped++;
            return null;
        }
        int openMana = 0;
        for (Card c : liveOpp.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                openMana++;
            }
        }
        final List<String> pool = beliefPoolFor(live, liveMe, liveOpp);
        final double[] ones = new double[pool.size()];
        Arrays.fill(ones, 1.0);
        double[] w = ones;
        boolean fallback = false;
        String error = null;
        if (beliefClient != null) {
            final JsonObject req = forgeRequest(live, liveMe);
            req.addProperty("schema", BeliefClient.REQUEST_SCHEMA);
            req.addProperty("target", forge.bench.StateEncoder.playerIndex(live, liveOpp));
            req.addProperty("n", hid[0]);
            req.addProperty("openMana", openMana);
            final JsonArray pj = new JsonArray();
            for (String n : pool) {
                pj.add(n);
            }
            req.add("pool", pj);
            if (root == null) {
                error = "no root state";
            } else {
                req.add("root", root);
                final long c0 = System.nanoTime();
                final double[] lw = beliefClient.logWeights(req, pool.size());
                final long dt = System.nanoTime() - c0;
                stats.beliefCalls++;
                stats.beliefNanos += dt;
                stats.beliefMaxNanos = Math.max(stats.beliefMaxNanos, dt);
                if (lw == null) {
                    error = beliefClient.lastError;
                } else {
                    w = BeliefSampler.weights(lw);
                    stats.beliefDigest = beliefClient.digestHex();
                    stats.beliefCheckpoint = beliefClient.checkpointSha256;
                    stats.beliefUnknownCards = beliefClient.unknownCards;
                }
            }
            if (error != null) {
                fallback = true;
                stats.beliefFailures++;
                stats.beliefLastError = error;
                System.err.println("[lookahead] belief call failed at decision " + index + ", sampling uniformly: " + error);
            }
        }
        final BeliefSampler.Dist bd = new BeliefSampler.Dist(pool, w, basicsFor(pool, hid[0], hid[1]), hid[0], hid[1]);
        bd.uniform = beliefClient == null || fallback;
        bd.fallback = fallback;
        bd.oppId = liveOpp.getId();
        stats.beliefDecisions++;
        stats.beliefPoolSlots += bd.slots();
        // Coverage: how far the belief moves hand mass from uniform, and onto what.
        if (stats.beliefCoverage.size() < 400) {
            final JsonObject e = new JsonObject();
            e.addProperty("d", index);
            e.addProperty("turn", turn);
            e.addProperty("oppActive", live.getPhaseHandler().getPlayerTurn() == liveOpp);
            e.addProperty("stack", onStack);
            e.addProperty("n", hid[0]);
            e.addProperty("slots", bd.slots());
            e.addProperty("open", openMana);
            if (hid[0] > 0) {
                final double[] pm = bd.marginals();
                final double pu = (double) hid[0] / bd.slots();
                double tvd = 0;
                for (double x : pm) {
                    tvd += Math.abs(x - pu);
                }
                e.addProperty("tvd", Math.round(tvd / 2 / hid[0] * 1e4) / 1e4);
                for (String tag : new String[] {"counter", "removal", "creature", "land"}) {
                    double eu = 0;
                    for (int i = 0; i < bd.slots(); i++) {
                        final Set<String> t = beliefCube.tags.get(bd.slotName[i]);
                        if (t != null && t.contains(tag)) {
                            eu += pu;
                        }
                    }
                    final JsonArray a = new JsonArray();
                    a.add(Math.round(BeliefSampler.expectedTagged(bd, pm, beliefCube, tag) * 1e4) / 1e4);
                    a.add(Math.round(eu * 1e4) / 1e4);
                    e.add(tag, a);
                }
                e.add("top", BeliefSampler.top(bd, pm, 3));
            }
            if (fallback) {
                e.addProperty("fallback", true);
            }
            e.addProperty("ms", Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
            stats.beliefCoverage.add(e);
        }
        return bd;
    }

    /**
     * The belief pool for {@code liveOpp} as the seat sees the live game: the cube's (or the event pool's) names, minus,
     * for a singleton pool ({@link Config#beliefSingleton}), our registered deck and every non-token card the opponent
     * owns that the seat can see; then the basics (M slots each in the distribution).
     */
    private List<String> beliefPoolFor(Game live, Player liveMe, Player liveOpp) {
        final PlayerView myView = liveMe.getView();
        final Set<String> known = new HashSet<>();
        if (cfg.beliefSingleton) {
            known.addAll(ownDeckNames(liveMe));
            for (Card c : live.getCardsInGame()) {
                if (c.getOwner() == liveOpp && !c.isToken() && !c.isFaceDown() && c.getPaperCard() != null
                        && (c.getView().canBeShownTo(myView) || knownHidden(live, liveMe, c, true))) {
                    known.add(c.getPaperCard().getName());
                }
            }
        }
        final List<String> pool = new ArrayList<>();
        for (String name : beliefCube.names) {
            if (!known.contains(name)) {
                pool.add(name);
            }
        }
        pool.addAll(Arrays.asList(BeliefSampler.basics()));
        return pool;
    }

    /** Our registered main deck's card names (a player knows their own deck), once. */
    private Set<String> ownDeckNames(Player liveMe) {
        Set<String> d = ownDeckNames;
        if (d == null) {
            d = new HashSet<>();
            final forge.deck.Deck deck = liveMe.getRegisteredPlayer() == null ? null : liveMe.getRegisteredPlayer().getDeck();
            if (deck != null && deck.has(forge.deck.DeckSection.Main)) {
                for (Map.Entry<forge.item.PaperCard, Integer> e : deck.get(forge.deck.DeckSection.Main)) {
                    d.add(e.getKey().getName());
                }
            }
            ownDeckNames = d;
        }
        return d;
    }

    private volatile Set<String> ownDeckNames;

    /**
     * honest-belief-1020: the basics multiplicity M: {@link Config#beliefBasics}, raised only when the pool would have
     * fewer slots than the hidden cards it must fill (so a hidden library is never cut short).
     */
    private int basicsFor(List<String> pool, int hand, int lib) {
        final int nonBasic = pool.size() - BeliefSampler.basics().length;
        final int need = hand + lib;
        int m = cfg.beliefBasics;
        if (nonBasic + m * BeliefSampler.basics().length < need) {
            m = (need - nonBasic + BeliefSampler.basics().length - 1) / BeliefSampler.basics().length;
        }
        return m;
    }

    /**
     * The world re-draw of a search copy (honest-belief-1020: the one entry point). With a belief on (acting), every
     * opponent's hidden cards come from the belief pool ({@link #resampleBelief}), never from their real cards; with
     * belief off or in shadow mode, {@link #resample} as before.
     */
    void redraw(Game live, Player liveMe, Game g, Player me, Random rng) {
        if (cfg.beliefStatics) g.staleStatics = true;
        if (cfg.beliefPlayerStatics) g.stalePlayerStatics = true;
        if (beliefCube != null && !cfg.beliefShadow && cfg.beliefDeck) {
            // deck-belief-1105: the opponents' hidden cards from the deck posterior, then our own library as before
            redrawOpponentsDeck(live, liveMe, g, me, rng, DECK_PARAMS);
            ownLibrary(live, liveMe, me, rng);
        } else if (beliefCube != null && !cfg.beliefShadow && cfg.deck()) {
            // belief-n11-1030: the learned deck model over the cards they showed in this game, then our own library
            redrawOpponentsDeck(live, liveMe, g, me, rng, cfg.beliefDeckModel);
            ownLibrary(live, liveMe, me, rng);
        } else if (beliefCube != null && !cfg.beliefShadow && cfg.codraft()) {
            // bvw-step2-fork-1027 (S2T): the co-draft deck prior over the cards they showed in this game (honest: public
            // co-draft data and public cards), then our own library below its known top
            redrawOpponentsPrior(live, liveMe, g, me, rng, cfg.beliefPriorTable, cfg.beliefPriorGain, false, null, 0);
            ownLibrary(live, liveMe, me, rng);
        } else if (beliefCube != null && !cfg.beliefShadow) {
            resampleBelief(live, liveMe, g, me, rng, decisionBelief);
        } else if (cfg.infoState) {
            // review #172 S2: find only; attack-holdback-1118: knownHand keeps the hand cards the seat may know
            resample(live, liveMe, g, me, rng, InfoStates.find(live), cfg.knownHand ? lc -> seatMayKnowHand(live, liveMe, lc, false) : null);
        } else {
            resample(live, liveMe, g, me, rng);
        }
    }

    /**
     * order-memory-1102: our own library in a world: the visible top (the default) or, with {@link Config#infoState},
     * every position InfoState(me) knows ({@link InfoStates#keepOwnLibrary}).
     */
    private void ownLibrary(Game live, Player liveMe, Player me, Random rng) {
        if (cfg.infoState) {
            InfoStates.keepOwnLibrary(InfoStates.find(live), liveMe, me, rng); // review #172 S2: find only
        } else {
            shuffleOwnLibrary(liveMe, me, rng);
        }
    }

    /**
     * bvw-step2-fork-1027: the solver's re-draw that matches this search's belief: the co-draft prior's
     * ({@link #opponentRedrawPrior}, this game's shown cards only) with belief "codraft", else {@link #opponentRedraw}.
     */
    public ForcedWinSolver.Redraw solverRedraw() {
        final ForcedWinSolver.Redraw base = cfg.deck() ? opponentRedrawDeck(cfg.beliefDeckModel)
                : cfg.codraft() ? opponentRedrawPrior(cfg.beliefPriorTable, cfg.beliefPriorGain)
                : opponentRedraw();
        if (base == null || !beliefWorldDecks()) {
            return base;
        }
        if (cfg.knownHand) {
            // attack-holdback-1118: the wrapper keeps the base re-draw's hand rule too (its world filter keeps what it kept)
            return new ForcedWinSolver.Redraw() {
                @Override public void apply(Game live, Player liveMe, Game g, Player me, Random rng) {
                    base.apply(live, liveMe, g, me, rng);
                    worldDecks(g, me);
                }
                @Override public boolean remembersHandCard(Game live, Player observer, Card card) {
                    return base.remembersHandCard(live, observer, card);
                }
            };
        }
        // review #123 S1-1 (S2T): the solver's belief worlds carry the world's own decklist for the opponent too
        return (live, liveMe, g, me, rng) -> {
            base.apply(live, liveMe, g, me, rng);
            worldDecks(g, me);
        };
    }

    /**
     * Review #123 S1-1 (S2T only: {@link Config#opponentPolicy}; step 1's worlds are unchanged): a belief world gives each
     * opponent the world's own decklist ({@link #worldDecks}). Off in the T re-play and OPR, whose real decklist is
     * that seat's own knowledge.
     */
    boolean beliefWorldDecks() {
        return cfg.opponentPolicy && beliefCube != null && !cfg.beliefShadow && !TEST_REAL_DECKS;
    }

    /** Tests only (the honesty test's deck mutant; false otherwise): belief worlds keep the real decklists. */
    public static volatile boolean TEST_REAL_DECKS = false;

    private static volatile java.lang.reflect.Field rpDeck;

    /**
     * Review #123 S1-1: in belief world {@code g}, each opponent's registered deck (the decklist its own frames, NAME
     * candidates and Forge-decided asks read) becomes the world's own: one main section of every non-token card it owns
     * in this world (its visible cards and its redrawn hand and library), no other section, no key cards or hints. The
     * real decklist names cards we have not seen; it never reaches a belief-world decision or frame.
     */
    static void worldDecks(Game g, Player me) {
        for (Player opp : me.getOpponents()) {
            final forge.game.player.RegisteredPlayer rp = opp.getRegisteredPlayer();
            if (rp == null) {
                continue;
            }
            final forge.deck.Deck d = new forge.deck.Deck("belief-world");
            final forge.deck.CardPool main = d.getOrCreate(forge.deck.DeckSection.Main);
            for (Card c : g.getCardsInGame()) {
                if (c.getOwner() == opp && !c.isToken() && c.getPaperCard() instanceof forge.item.PaperCard) {
                    main.add((forge.item.PaperCard) c.getPaperCard());
                }
            }
            try {
                java.lang.reflect.Field f = rpDeck;
                if (f == null) {
                    f = forge.game.player.RegisteredPlayer.class.getDeclaredField("currentDeck");
                    f.setAccessible(true);
                    rpDeck = f;
                }
                f.set(rp, d);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // refuse the world rather than let the real decklist through
                throw new IllegalStateException("S2T: cannot give the belief world its own decklist: " + e, e);
            }
        }
    }

    /**
     * honest-belief-1020: the solver's re-draw ({@link ForcedWinSolver#redraw}): the opponents' hidden cards from the
     * belief pool, uniformly; our own library is left to the solver (its known-top rule). Null when no belief is on.
     */
    public ForcedWinSolver.Redraw opponentRedraw() {
        if (beliefCube == null || cfg.beliefShadow) {
            return null;
        }
        return new ForcedWinSolver.Redraw() {
            @Override public void apply(Game live, Player liveMe, Game g, Player me, Random rng) {
                redrawOpponents(live, liveMe, g, me, rng, null);
            }
            @Override public boolean remembersHandCard(Game live, Player observer, Card card) {
                return knownHidden(live, observer, card, true); // attack-holdback-1118: knownHand off = rememberedHand
            }
        };
    }

    /**
     * deck-belief-1105 (solver key {@code deckBelief}): as {@link #opponentRedraw}, but each world's hidden hand and
     * library are dealt by {@link DeckBelief#deal} from the same pool: a deck of colours consistent with what the
     * opponent has shown, its hand weighted by what it declined to do. Null when no belief is on.
     */
    /** deck-belief-1105: the search worlds' deck-belief parameters (the model defaults). */
    private static final DeckBelief.Params DECK_PARAMS = new DeckBelief.Params();

    public ForcedWinSolver.Redraw opponentRedrawDeck(final DeckBelief.Params p) {
        if (beliefCube == null || cfg.beliefShadow) {
            return null;
        }
        return new ForcedWinSolver.Redraw() {
            @Override public void apply(Game live, Player liveMe, Game g, Player me, Random rng) {
                redrawOpponentsDeck(live, liveMe, g, me, rng, p);
            }
            @Override public boolean remembersHandCard(Game live, Player observer, Card card) {
                return knownHidden(live, observer, card, true); // attack-holdback-1118: knownHand off = rememberedHand
            }
        };
    }

    /** The solver's re-draw for a solver config: {@link #opponentRedrawDeck} with {@code deckBelief}, else as before. */
    public ForcedWinSolver.Redraw solverRedraw(final ForcedWinSolver.Config sc) {
        return sc != null && sc.deckBelief ? opponentRedrawDeck(sc.deckParams()) : opponentRedraw();
    }

    private void redrawOpponentsDeck(Game live, Player liveMe, Game g, Player me, Random rng, DeckBelief.Params p) {
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            final Player liveOpp = (Player) live.getPlayer(opp.getId());
            if (liveOpp == null) {
                throw new IllegalStateException("belief: no live player for opponent " + opp.getId());
            }
            final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            final List<Card> keep = new ArrayList<>();
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && handKnown(live, liveMe, lc)) {
                    keep.add(c);
                }
            }
            final Map<Integer, Card> pins = cfg.infoState ? InfoStates.oppPins(InfoStates.find(live), liveMe, liveOpp, g) : null;
            final List<Card> libKnownTop = new ArrayList<>();
            if (pins == null) {
                for (Card lc : new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library))) {
                    if (lc.getView().canBeShownTo(myView)) {
                        Card cc = g.findById(lc.getId());
                        if (cc != null) {
                            libKnownTop.add(cc);
                            continue;
                        }
                    }
                    break;
                }
            }
            final int need = hand.size() - keep.size();
            final int libNeed = opp.getCardsIn(ZoneType.Library).size() - (pins != null ? pins.size() : libKnownTop.size());
            if (need == 0 && libNeed == 0) {
                continue;
            }
            final List<String> pool = beliefPoolFor(live, liveMe, liveOpp);
            if (pins != null) {
                for (Card pc : pins.values()) {
                    if (!liveOpp.getGame().findById(pc.getId()).getView().canBeShownTo(myView)) {
                        pool.remove(pc.getName());
                    }
                }
            }
            final long c0 = System.nanoTime();
            final DeckBelief.Evidence ev = DeckBelief.Evidence.read(live, liveOpp, myView);
            final DeckBelief.Deal d = DeckBelief.deal(pool, beliefCube.paper::get, ev, need, Math.max(0, libNeed), p, rng);
            final List<Card> newHand = new ArrayList<>(keep);
            for (String nm : d.hand) {
                newHand.add(beliefCard(nm, opp, g));
            }
            final List<Card> newLib = new ArrayList<>(libKnownTop);
            for (String nm : d.library) {
                newLib.add(beliefCard(nm, opp, g));
            }
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(pins == null ? newLib
                    : InfoStates.assemble(opp.getCardsIn(ZoneType.Library).size(), pins, newLib));
            synchronized (stats) {
                stats.beliefWorlds++;
                stats.beliefCards += d.hand.size() + d.library.size();
                stats.beliefCreateNanos += System.nanoTime() - c0;
            }
        }
    }

    /**
     * strong-opp-1020: the defender's re-draw with a DECK PRIOR: as {@link #opponentRedraw}, but the opponent's hidden
     * cards (hand and library together: their deck) are drawn from the belief pool with weights
     * {@code 1 + gain * max_s syn[s][card]^2} over the cards {@code s} they have shown (battlefield, graveyard, exile,
     * their spells on the stack, the hand cards we see): a deck that shows Doomsday likely runs what Doomsday decks run.
     * {@code syn} is a co-draft table from public data (never their real cards). Null when no belief is on.
     */
    public ForcedWinSolver.Redraw opponentRedrawPrior(final Map<String, Map<String, Double>> syn, final double gain) {
        return opponentRedrawPrior(syn, gain, false);
    }

    /**
     * As above; {@code fillBasics}: a drawn card the prior does not boost (weight 1: no co-draft link to anything they
     * showed) enters the copy as a basic land instead. The defender's threat check then reads (and probes) only the
     * cards their shown cards point to; an unlinked card rarely makes a forced win, and every new card name costs an
     * affordance probe (strong-opp-1020: about 50 per game, the defense's main cost).
     */
    public ForcedWinSolver.Redraw opponentRedrawPrior(final Map<String, Map<String, Double>> syn, final double gain,
            final boolean fillBasics) {
        return opponentRedrawPrior(syn, gain, fillBasics, 0);
    }

    /**
     * deck-guess-1021: as above, plus the cards they showed in EARLIER games of this match ({@link #beliefSeenOf}; honest:
     * they were public then). With {@code seenGain > 0}: (a) those cards join the shown set, so their co-draft partners
     * are boosted as for a card shown now; (b) each such card still in the pool (not seen in this game) gets weight
     * {@code seenGain * (1 + gain)}, i.e. seenGain times the strongest co-draft link, and counts as linked (never a
     * basic under fillBasics). A seen card stays a weight, never a certainty (sideboarding may have taken it out).
     * {@code seenGain = 0} (or no seen list) = exactly the 3-argument prior.
     */
    public ForcedWinSolver.Redraw opponentRedrawPrior(final Map<String, Map<String, Double>> syn, final double gain,
            final boolean fillBasics, final double seenGain) {
        if (beliefCube == null || cfg.beliefShadow || syn == null) {
            return null;
        }
        if (cfg.knownHand) {
            // attack-holdback-1118: the defender's worlds keep the hand cards the seat may know, and its filter agrees
            return new ForcedWinSolver.Redraw() {
                @Override public void apply(Game live, Player liveMe, Game g, Player me, Random rng) {
                    redrawOpponentsPrior(live, liveMe, g, me, rng, syn, gain, fillBasics,
                            seenGain > 0 ? beliefSeenOf(live) : null, seenGain);
                }
                @Override public boolean remembersHandCard(Game live, Player observer, Card card) {
                    return knownHidden(live, observer, card, false);
                }
            };
        }
        return (live, liveMe, g, me, rng) -> redrawOpponentsPrior(live, liveMe, g, me, rng, syn, gain, fillBasics,
                seenGain > 0 ? beliefSeenOf(live) : null, seenGain);
    }

    /**
     * deck-guess-1021, EVAL harnesses only (null = off, the default): the earlier-games list per game, for a JVM that
     * plays many games (a bank of positions from different matches). Live has one game per JVM and uses
     * {@link Config#beliefSeen}.
     */
    public static volatile java.util.function.Function<Game, java.util.Collection<String>> BELIEF_SEEN_HOOK;

    /** deck-guess-1021: the opponent's cards shown in earlier games of this match: the EVAL hook's, else the config's. */
    java.util.Collection<String> beliefSeenOf(final Game live) {
        final java.util.function.Function<Game, java.util.Collection<String>> h = BELIEF_SEEN_HOOK;
        if (h != null) {
            final java.util.Collection<String> s = h.apply(live);
            if (s != null) {
                return s;
            }
        }
        return cfg.beliefSeen;
    }

    private void redrawOpponentsPrior(Game live, Player liveMe, Game g, Player me, Random rng,
            Map<String, Map<String, Double>> syn, double gain, boolean fillBasics,
            java.util.Collection<String> seenBefore, double seenGain) {
        boolean changed = false;
        final Set<String> seen = seenBefore == null || seenGain <= 0 ? Collections.emptySet() : new HashSet<>(seenBefore);
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            final Player liveOpp = (Player) live.getPlayer(opp.getId());
            if (liveOpp == null) {
                throw new IllegalStateException("belief: no live player for opponent " + opp.getId());
            }
            final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            final List<Card> keep = new ArrayList<>();
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && seatMayKnowHand(live, liveMe, lc, false)) { // attack-holdback-1118 (off: canBeShownTo)
                    keep.add(c);
                }
            }
            final List<Card> libKnownTop = new ArrayList<>();
            for (Card lc : new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library))) {
                if (lc.getView().canBeShownTo(myView)) {
                    Card cc = g.findById(lc.getId());
                    if (cc != null) {
                        libKnownTop.add(cc);
                        continue;
                    }
                }
                break;
            }
            final int need = hand.size() - keep.size();
            final int libNeed = opp.getCardsIn(ZoneType.Library).size() - libKnownTop.size();
            if (need == 0 && libNeed == 0) {
                continue;
            }
            // what they have shown (attack-holdback-1118, knownHand: a hand card the seat may know was shown too)
            final Set<String> shown = new HashSet<>();
            for (Card c : live.getCardsInGame()) {
                if (c.getOwner() == liveOpp && !c.isToken() && !c.isFaceDown() && c.getPaperCard() != null
                        && (c.getView().canBeShownTo(myView) || cfg.knownHand && knownHidden(live, liveMe, c, false))
                        && !c.isInZone(ZoneType.Library)) {
                    shown.add(c.getPaperCard().getName());
                }
            }
            for (forge.game.spellability.SpellAbilityStackInstance si : live.getStack()) {
                // review #123 S3-4: a face-down spell (a morph cast face down) or one we cannot see shows nothing
                final Card sc = si.getSourceCard();
                if (si.getActivatingPlayer() == liveOpp && sc != null && sc.getPaperCard() != null
                        && (!forge.game.EngineFixes.researchFixes() || !sc.isFaceDown() && sc.getView().canBeShownTo(myView))) {
                    shown.add(sc.getPaperCard().getName());
                }
            }
            // deck-guess-1021: the cards of their earlier games link their co-draft partners too
            shown.addAll(seen);
            final List<String> pool = beliefPoolFor(live, liveMe, liveOpp);
            final double[] w = new double[pool.size()];
            final boolean[] linked = new boolean[pool.size()];
            double max = 0;
            for (int i = 0; i < w.length; i++) {
                double best = 0;
                for (String a : shown) {
                    final Map<String, Double> row = syn.get(a);
                    final Double v = row == null ? null : row.get(pool.get(i));
                    if (v != null && v > best) {
                        best = v;
                    }
                }
                w[i] = 1.0 + gain * best * best;
                linked[i] = best > 0;
                if (!seen.isEmpty() && seen.contains(pool.get(i)) && !BeliefSampler.isBasic(pool.get(i))) {
                    // deck-guess-1021: in their deck in an earlier game, not seen yet in this one
                    w[i] = Math.max(w[i], seenGain * (1.0 + gain));
                    linked[i] = true;
                }
                max = Math.max(max, w[i]);
            }
            for (int i = 0; i < w.length; i++) {
                w[i] /= max;
            }
            final int total = need + libNeed;
            final BeliefSampler.Dist d = new BeliefSampler.Dist(pool, w, basicsFor(pool, total, 0), total, 0);
            final int[] slots = d.sampleHand(rng);
            final List<Integer> all = new ArrayList<>();
            for (int i : slots) {
                all.add(i);
            }
            Collections.shuffle(all, rng);
            final List<Card> newHand = new ArrayList<>(keep);
            final List<Card> newLib = new ArrayList<>(libKnownTop);
            final Map<String, Integer> itemOf = new java.util.HashMap<>();
            for (int i = 0; i < pool.size(); i++) {
                itemOf.put(pool.get(i), i);
            }
            for (int k = 0; k < all.size(); k++) {
                String nm = d.slotName[all.get(k)];
                if (fillBasics && !BeliefSampler.isBasic(nm)) {
                    final Integer it = itemOf.get(nm);
                    if (it == null || !linked[it]) {
                        nm = BeliefSampler.basics()[rng.nextInt(BeliefSampler.basics().length)];
                    }
                }
                final Card nc = beliefCard(nm, opp, g);
                if (k < need) {
                    newHand.add(nc);
                } else {
                    newLib.add(nc);
                }
            }
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(newLib);
            changed = true;
        }
        if (changed) refreshBeliefStatics(g);
    }

    /**
     * Belief re-draw of one world (in a copy): each opponent's hidden hand from the belief, the hidden library uniform
     * over the pool slots left (known cards stay where they are), then our own library as {@link #resample}. The
     * opponent's hidden cards in the copy are replaced by new cards of the drawn names. {@code bd} (or null) is the
     * decision's belief; an opponent it does not describe (another opponent, or a copy whose hidden counts differ from
     * the decision's) is drawn uniformly from its own pool: never from their real cards (honest-belief-1020).
     */
    void resampleBelief(Game live, Player liveMe, Game g, Player me, Random rng, BeliefSampler.Dist bd) {
        redrawOpponents(live, liveMe, g, me, rng, bd);
        ownLibrary(live, liveMe, me, rng);
    }

    private void redrawOpponents(Game live, Player liveMe, Game g, Player me, Random rng, BeliefSampler.Dist bd) {
        boolean changed = false;
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            final Player liveOpp = (Player) live.getPlayer(opp.getId());
            final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            final List<Card> keep = new ArrayList<>();
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && handKnown(live, liveMe, lc)) {
                    keep.add(c);
                }
            }
            final List<Card> libKnownTop = new ArrayList<>();
            // order-memory-1102 (infoState): every opponent-library card InfoState(me) knows, at its index
            final Map<Integer, Card> pins = cfg.infoState && liveOpp != null
                    ? InfoStates.oppPins(InfoStates.find(live), liveMe, liveOpp, g) : null; // review #172 S2: find only
            final List<Card> liveLib = liveOpp == null || pins != null ? Collections.emptyList() : new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library));
            for (Card lc : liveLib) {
                if (lc.getView().canBeShownTo(myView)) {
                    Card cc = g.findById(lc.getId());
                    if (cc != null) {
                        libKnownTop.add(cc);
                        continue;
                    }
                }
                break;
            }
            final int need = hand.size() - keep.size();
            final int libNeed = opp.getCardsIn(ZoneType.Library).size() - (pins != null ? pins.size() : libKnownTop.size());
            if (need == 0 && libNeed == 0) {
                continue;
            }
            BeliefSampler.Dist d = bd;
            if (d == null || opp.getId() != d.oppId || need != d.n || libNeed != d.libHidden) {
                if (d != null && opp.getId() == d.oppId) {
                    // The copy does not show what the decision saw: a uniform draw over this opponent's pool.
                    synchronized (stats) {
                        stats.beliefMismatch++;
                    }
                }
                if (liveOpp == null) {
                    throw new IllegalStateException("belief: no live player for opponent " + opp.getId());
                }
                final List<String> pool = beliefPoolFor(live, liveMe, liveOpp);
                if (pins != null) {
                    // order-memory-1102: a pinned card we know but cannot see is not drawn again
                    for (Card pc : pins.values()) {
                        if (!liveOpp.getGame().findById(pc.getId()).getView().canBeShownTo(myView)) {
                            pool.remove(pc.getName());
                        }
                    }
                }
                final double[] ones = new double[pool.size()];
                Arrays.fill(ones, 1.0);
                d = new BeliefSampler.Dist(pool, ones, basicsFor(pool, need, libNeed), need, libNeed);
                d.uniform = true;
                d.oppId = opp.getId();
            }
            final int[] handSlots = d.sampleHand(rng);
            final List<Integer> libSlots = d.library(handSlots, rng);
            final List<Integer> hs = new ArrayList<>();
            for (int i : handSlots) {
                hs.add(i);
            }
            Collections.shuffle(hs, rng);
            final long c0 = System.nanoTime();
            final List<Card> newHand = new ArrayList<>(keep);
            for (int i : hs) {
                newHand.add(beliefCard(d.slotName[i], opp, g));
            }
            final List<Card> newLib = new ArrayList<>(libKnownTop);
            for (int i : libSlots) {
                newLib.add(beliefCard(d.slotName[i], opp, g));
            }
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(pins == null ? newLib
                    : InfoStates.assemble(opp.getCardsIn(ZoneType.Library).size(), pins, newLib));
            changed = true;
            synchronized (stats) {
                stats.beliefWorlds++;
                stats.beliefCards += hs.size() + libSlots.size();
                stats.beliefCreateNanos += System.nanoTime() - c0;
            }
        }
        if (changed) refreshBeliefStatics(g);
    }

    /**
     * belief-n11-1030 (N11): the solver's re-draw with the learned deck model ({@link #redrawOpponentsDeck}). Null when
     * no belief is on or no model is given.
     */
    public ForcedWinSolver.Redraw opponentRedrawDeck(final DeckBeliefModel model) {
        if (beliefCube == null || cfg.beliefShadow || model == null) {
            return null;
        }
        if (cfg.knownHand) {
            // attack-holdback-1118: the deck model's worlds keep the hand cards the seat may know, and the filter agrees
            return new ForcedWinSolver.Redraw() {
                @Override public void apply(Game live, Player liveMe, Game g, Player me, Random rng) {
                    redrawOpponentsDeck(live, liveMe, g, me, rng, model);
                }
                @Override public boolean remembersHandCard(Game live, Player observer, Card card) {
                    return knownHidden(live, observer, card, false);
                }
            };
        }
        return (live, liveMe, g, me, rng) -> redrawOpponentsDeck(live, liveMe, g, me, rng, model);
    }

    /**
     * belief-n11-1030 (N11): each opponent's hidden hand and library (together: the rest of their deck) drawn from the
     * learned deck model given the card instances they have shown in this game (battlefield, graveyard, exile, command,
     * their spells on the stack, hand cards we can see), never from their real cards. Kept hand cards and the known top
     * of their library stay. Hidden counts are matched exactly; the drawn cards are shuffled and dealt hand first.
     */
    private void redrawOpponentsDeck(Game live, Player liveMe, Game g, Player me, Random rng, DeckBeliefModel model) {
        boolean changed = false;
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            final Player liveOpp = (Player) live.getPlayer(opp.getId());
            if (liveOpp == null) {
                throw new IllegalStateException("belief: no live player for opponent " + opp.getId());
            }
            final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            final List<Card> keep = new ArrayList<>();
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && seatMayKnowHand(live, liveMe, lc, false)) { // attack-holdback-1118 (off: canBeShownTo)
                    keep.add(c);
                }
            }
            final List<Card> libKnownTop = new ArrayList<>();
            for (Card lc : new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library))) {
                if (lc.getView().canBeShownTo(myView)) {
                    Card cc = g.findById(lc.getId());
                    if (cc != null) {
                        libKnownTop.add(cc);
                        continue;
                    }
                }
                break;
            }
            final int need = hand.size() - keep.size();
            final int libNeed = opp.getCardsIn(ZoneType.Library).size() - libKnownTop.size();
            if (need == 0 && libNeed == 0) {
                continue;
            }
            // the card instances they have shown (a name per card, so basics repeat), by card id order
            // (attack-holdback-1118, knownHand: a hand card the seat may know was shown too)
            final List<Card> shownCards = new ArrayList<>();
            for (Card c : live.getCardsInGame()) {
                if (c.getOwner() == liveOpp && !c.isToken() && !c.isFaceDown() && !c.isCopiedSpell()
                        && c.getPaperCard() != null
                        && (c.getView().canBeShownTo(myView) || cfg.knownHand && knownHidden(live, liveMe, c, false))) {
                    shownCards.add(c);
                }
            }
            for (forge.game.spellability.SpellAbilityStackInstance si : live.getStack()) {
                final Card sc = si.getSourceCard();
                if (si.getActivatingPlayer() == liveOpp && sc != null && sc.getPaperCard() != null && !sc.isFaceDown()
                        && !sc.isToken() && !sc.isCopiedSpell() && sc.getView().canBeShownTo(myView)
                        && !shownCards.contains(sc)) {
                    shownCards.add(sc);
                }
            }
            shownCards.sort(java.util.Comparator.comparingInt(Card::getId));
            final List<String> shown = new ArrayList<>();
            for (Card c : shownCards) {
                shown.add(c.getPaperCard().getName());
            }
            // review #1530 S1 ruling: the per-deck gate (cached in the model; sticky on refusal)
            model.requireHeld(liveOpp.getRegisteredPlayer());
            final List<String> pool = beliefPoolFor(live, liveMe, liveOpp);
            final long c0 = System.nanoTime();
            final List<String> drawn = new ArrayList<>(model.sample(shown, pool, need + libNeed, rng));
            Collections.shuffle(drawn, rng);
            final List<Card> newHand = new ArrayList<>(keep);
            final List<Card> newLib = new ArrayList<>(libKnownTop);
            for (int k = 0; k < drawn.size(); k++) {
                final Card nc = beliefCard(drawn.get(k), opp, g);
                if (k < need) {
                    newHand.add(nc);
                } else {
                    newLib.add(nc);
                }
            }
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(newLib);
            changed = true;
            synchronized (stats) {
                stats.beliefWorlds++;
                stats.beliefCards += drawn.size();
                stats.beliefCreateNanos += System.nanoTime() - c0;
            }
        }
        if (changed) refreshBeliefStatics(g);
    }

    /** Rebuild derived effects from the sampled zones, not discarded actual hidden-card objects. */
    private void refreshBeliefStatics(Game g) {
        if (!forge.game.EngineFixes.researchFixes()) {
            return; // unified-jar-1106: research's, keyed on this line
        }
        // setCards does not update statics. Hidden CDAs (Pack Rat, Grist, ...) otherwise leave their old source in
        // StaticEffects, leaking a reference outside the sampled zones and breaking the next GameCopier mapping.
        // Recompute after all opponents are replaced, retaining effects of public and kept/revealed cards.
        g.getAction().checkStaticAbilities(false);
        synchronized (stats) {
            stats.beliefStaticRefreshes++;
        }
    }

    private Card beliefCard(String name, Player owner, Game g) {
        final forge.item.PaperCard pc = beliefCube.paper.get(name);
        if (pc == null) {
            throw new IllegalStateException("belief: no paper card for " + name);
        }
        return forge.game.card.CardFactory.getCard(pc, owner, g);
    }

    /**
     * Belief shadow (do-no-harm gate): build every world of the decision with the belief, in throwaway copies inside
     * the play-out scopes (ids, AI cache, thread random), and discard them. The search then plays the default worlds.
     */
    private void shadowBeliefWorlds(Game live, Player liveMe, BeliefSampler.Dist bd, long decisionSeed, int k) {
        for (int w = 0; w < k; w++) {
            final long ws = mix(decisionSeed, 1000 + w);
            final Random prev = MyRandom.getThreadRandom();
            final Object prevIds = forge.util.IdScope.capture();
            final Object prevCache = AiCache.captureScope();
            MyRandom.setThreadRandom(new Random(mix(ws, 11)));
            AiCache.openScope();
            forge.util.IdScope.open();
            try {
                synchronized (live) {
                    final GameCopier copier = copierOf(live);
                    copier.setCopyStack(cfg.stack);
                    final Game g = copier.makeCopy();
                    resampleBelief(live, liveMe, g, (Player) copier.find(liveMe), new Random(mix(ws, 12)), bd);
                }
            } catch (RuntimeException e) {
                synchronized (stats) {
                    stats.beliefLastError = "shadow: " + e;
                }
            } finally {
                forge.util.IdScope.install(prevIds);
                AiCache.installScope(prevCache);
                MyRandom.setThreadRandom(prev);
            }
        }
    }

    // ------------------------------------------------------------------ probe

    private void probeExtras(JsonObject p, Game live, Player liveMe, List<Cand> cands, SpellAbility defSa, long decisionSeed, int turn) {
        // (1) Copy cost and copy fidelity, no resampling.
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 99)));
        final Object prevAiCache5 = AiCache.captureScope(); // restored below, never removed (k8-determinism-1008)
        AiCache.openScope();
        final Object prevIds3 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            JsonArray copyMs = new JsonArray();
            String liveFp = fingerprint(live);
            boolean fpSame = true;
            boolean scoreSame = true;
            GameStateEvaluator ev = new GameStateEvaluator();
            int liveScore = ev.getStaticScore(live, liveMe).value;
            for (int i = 0; i < 3; i++) {
                long a = System.nanoTime();
                GameCopier copier = copierOf(live);
                copier.setCopyStack(cfg.stack);
                Game g = copier.makeCopy();
                copyMs.add((System.nanoTime() - a) / 1e6);
                Player me = (Player) copier.find(liveMe);
                fpSame &= liveFp.equals(fingerprint(g));
                scoreSame &= liveScore == ev.getStaticScore(g, me).value;
                if (i == 0 && !liveFp.equals(fingerprint(g))) {
                    p.addProperty("copyFpDiff", diff(liveFp, fingerprint(g)));
                }
            }
            p.add("copyMs", copyMs);
            p.addProperty("copyFingerprintSame", fpSame);
            p.addProperty("copyStaticScoreSame", scoreSame);
            // Resampling cost.
            long a = System.nanoTime();
            GameCopier copier = copierOf(live);
            Game g = copier.makeCopy();
            long b = System.nanoTime();
            resample(live, liveMe, g, (Player) copier.find(liveMe), new Random(1));
            p.addProperty("resampleMs", (System.nanoTime() - b) / 1e6);
            p.addProperty("copyPlusResampleMs", (System.nanoTime() - a) / 1e6);
            Player gm = (Player) copier.find(liveMe);
            for (Player o : gm.getOpponents()) {
                Player lo = live.getPlayer(o.getId());
                p.addProperty("oppHandChangedByResample", !names(lo.getCardsIn(ZoneType.Hand)).equals(names(o.getCardsIn(ZoneType.Hand))));
            }
        } catch (RuntimeException e) {
            p.addProperty("copyProbeError", e.toString());
        } finally {
            AiCache.installScope(prevAiCache5);
            forge.util.IdScope.install(prevIds3);
            MyRandom.setThreadRandom(prev);
        }

        // (2) Determinism: candidate 0, world 0, twice.
        Rollout r1 = rollout(live, liveMe, cands.get(0), defSa, mix(decisionSeed, 1000), cfg.resample, true);
        Rollout r2 = rollout(live, liveMe, cands.get(0), defSa, mix(decisionSeed, 1000), cfg.resample, true);
        p.addProperty("detValueSame", r1.value == r2.value);
        p.addProperty("detFingerprintSame", r1.fingerprint != null && r1.fingerprint.equals(r2.fingerprint));
        p.addProperty("rolloutMs", r1.rolloutNanos / 1e6);
        p.addProperty("rolloutCopyMs", r1.copyNanos / 1e6);
        p.addProperty("rolloutSteps", r1.steps);

        // (3) Forge's own simulation AI, same position, its cost per decision (depth/budget as configured).
        final Random prev2 = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 98)));
        final Object prevAiCache6 = AiCache.captureScope(); // restored below, never removed (k8-determinism-1008)
        AiCache.openScope();
        final Object prevIds4 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            GameCopier copier = copierOf(live);
            Game g = copier.makeCopy();
            Player me = (Player) copier.find(liveMe);
            long sims0 = SimSearchBudget.getLifetimeSimulations();
            long a = System.nanoTime();
            SpellAbility simSa = new SpellAbilityPicker(me).chooseSpellAbilityToPlay(null);
            p.addProperty("simAiMs", (System.nanoTime() - a) / 1e6);
            p.addProperty("simAiSims", SimSearchBudget.getLifetimeSimulations() - sims0);
            p.addProperty("simAiChoice", simSa == null ? "pass" : new Cand(simSa, false).label);
        } catch (RuntimeException e) {
            p.addProperty("simAiError", e.toString());
        } finally {
            AiCache.installScope(prevAiCache6);
            forge.util.IdScope.install(prevIds4);
            MyRandom.setThreadRandom(prev2);
        }

        // (4) Fidelity vs the live continuation (shadow mode, once per game, our own turn >= 3):
        // a TRUTH rollout (no resampling) of Forge's answer with stream s; then the live game is
        // put on stream s and watched to the same turn. Same code, same stream: any difference
        // is copy infidelity (or per-turn AI memory the copy does not carry).
        if (cfg.shadow && cfg.fidelity && fidelityArmed && turn >= 3 && liveWatch == null) {
            fidelityArmed = false;
            long s = mix(decisionSeed, 4242);
            Rollout tr = rolloutTruthWithGlobalStream(live, liveMe, cands.get(0), defSa, s);
            p.addProperty("fidelityRolloutOk", tr.ok);
            p.addProperty("fidelityTurn", live.getPhaseHandler().getTurn() + cfg.horizonTurns);
            liveWatch = new FidelityWatch(live.getPhaseHandler().getTurn() + cfg.horizonTurns, live, tr.fingerprint, p);
            live.subscribeToEvents(liveWatch);
            MyRandom.setRandom(new Random(s));
        }
    }

    /** A truth rollout that mimics the live game's RNG use: the copy runs on stream s from the first step. */
    private Rollout rolloutTruthWithGlobalStream(Game live, Player liveMe, Cand c, SpellAbility defSa, long s) {
        final Rollout r = new Rollout();
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(s, 1)));
        final Object prevAiCache7 = AiCache.captureScope(); // restored below, never removed (k8-determinism-1008)
        AiCache.openScope();
        final Object prevIds5 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            GameCopier copier = copierOf(live);
            copier.setCopyStack(cfg.stack);
            Game g = copier.makeCopy();
            Player me = (Player) copier.find(liveMe);
            final Player lf = cfg.stack && !live.getStack().isEmpty() ? firstPriority(live.getPhaseHandler()) : null;
            final Player firstPriority = lf == null ? null : (Player) copier.find(lf);
            List<SpellAbility> first = null;
            if (!c.pass) {
                SpellAbility sa = prepare(g, me, c, defSa, copier);
                if (sa == null) {
                    r.ok = false;
                    return r;
                }
                first = new ArrayList<>();
                first.add(sa);
            }
            me.dangerouslySetController(new ScriptedFirst(g, me, me.getController().getLobbyPlayer(), first));
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new RolloutAi(g, o, o.getController().getLobbyPlayer()));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            givePriority(ph, me);
            if (firstPriority != null) {
                setFirstPriority(ph, firstPriority);
            }
            final TurnWatch watch = new TurnWatch(ph.getTurn() + cfg.horizonTurns, me);
            g.subscribeToEvents(watch);
            MyRandom.setThreadRandom(new Random(s));
            int steps = 0;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.fingerprint = watch.reached ? watch.fingerprint : ("END:" + fingerprint(g));
        } catch (RuntimeException e) {
            r.ok = false;
        } finally {
            AiCache.installScope(prevAiCache7);
            forge.util.IdScope.install(prevIds5);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    public static final class FidelityWatch {
        final int turn;
        final Game live;
        final String expected;
        final JsonObject into;
        boolean done = false;

        FidelityWatch(int turn, Game live, String expected, JsonObject into) {
            this.turn = turn;
            this.live = live;
            this.expected = expected;
            this.into = into;
        }

        @Subscribe
        public void on(GameEventTurnBegan e) {
            if (!done && e.turnNumber() >= turn) {
                done = true;
                String got = fingerprint(live);
                into.addProperty("fidelitySame", got.equals(expected));
                if (expected != null && !got.equals(expected)) {
                    into.addProperty("fidelityDiff", diff(got, expected));
                }
            }
        }
    }

    /** Called by the runner at game end: a fidelity watch that never fired is recorded as such. */
    public void finishGame(Game live) {
        if (liveWatch != null && !liveWatch.done) {
            liveWatch.done = true;
            String got = "END:" + fingerprint(live);
            liveWatch.into.addProperty("fidelitySame", got.equals(liveWatch.expected));
            liveWatch.into.addProperty("fidelityEndedBeforeTurn", true);
            if (!got.equals(liveWatch.expected)) {
                liveWatch.into.addProperty("fidelityDiff", diff(got, liveWatch.expected));
            }
        }
    }

    // ------------------------------------------------------------------ util

    private static String liveProbe(Game live, SpellAbility defSa) {
        StringBuilder sb = new StringBuilder(fingerprint(live));
        if (defSa != null) {
            for (SpellAbility s = defSa; s != null; s = s.getSubAbility()) {
                sb.append("defTargets=").append(s.usesTargeting() ? s.getTargets().toString() : "-").append('\n');
            }
        }
        return sb.toString();
    }

    private static void checkLive(String where, String before, Game live, SpellAbility defSa, int index) {
        String after = liveProbe(live, defSa);
        if (!after.equals(before)) {
            System.err.println("[lookahead] LIVE MUTATED after " + where + " at decision " + index + ":\n" + diff(before, after));
        }
    }

    static List<String> names(Iterable<Card> cs) {
        List<String> l = new ArrayList<>();
        for (Card c : cs) {
            l.add(c.getName());
        }
        Collections.sort(l);
        return l;
    }

    /** A position fingerprint: public and private zones by name, life, counters, board state. */
    public static String fingerprint(Game g) {
        StringBuilder sb = new StringBuilder();
        sb.append("T").append(g.getPhaseHandler().getTurn()).append(' ').append(g.getPhaseHandler().getPhase()).append('\n');
        for (Player p : g.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife())
                    .append(" poison=").append(p.getPoisonCounters())
                    .append(" lib=").append(p.getCardsIn(ZoneType.Library).size())
                    .append(" lost=").append(p.hasLost()).append('\n');
            sb.append(" hand=").append(names(p.getCardsIn(ZoneType.Hand))).append('\n');
            sb.append(" gy=").append(names(p.getCardsIn(ZoneType.Graveyard))).append('\n');
            sb.append(" exile=").append(names(p.getCardsIn(ZoneType.Exile))).append('\n');
            List<String> bf = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                StringBuilder cb = new StringBuilder(c.getName());
                cb.append(c.isTapped() ? "/T" : "/U");
                if (c.isCreature()) {
                    cb.append('/').append(c.getNetPower()).append('/').append(c.getNetToughness()).append("/d").append(c.getDamage());
                }
                Map<String, Integer> cn = new TreeMap<>();
                for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                    cn.put(String.valueOf(e.getElement()), e.getCount());
                }
                if (!cn.isEmpty()) {
                    cb.append(cn);
                }
                bf.add(cb.toString());
            }
            Collections.sort(bf);
            sb.append(" bf=").append(bf).append('\n');
        }
        return sb.toString();
    }

    static String diff(String a, String b) {
        if (a == null || b == null) {
            return "null fingerprint";
        }
        String[] la = a.split("\n"), lb = b.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(la.length, lb.length); i++) {
            String x = i < la.length ? la[i] : "", y = i < lb.length ? lb[i] : "";
            if (!x.equals(y)) {
                sb.append("- ").append(x).append("\n+ ").append(y).append('\n');
            }
        }
        return sb.length() > 1500 ? sb.substring(0, 1500) : sb.toString();
    }

    public static long mix(long a, long b) {
        long x = a * 0x9E3779B97F4A7C15L + b;
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }
}
