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

    public static final double TERMINAL = 100000.0;
    private static final AtomicInteger FAILURE_TRACES = new AtomicInteger();

    public static final class Config {
        public int worlds = 1;
        public int breadth = 4;
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
         * unchanged). When a search runs past it, its play-outs stop at their next main-loop step and the
         * seat plays Forge AI's own answer ("capped"). Timing-dependent by design (interactive play only).
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
        public String beliefUrl = null;
        public int beliefTimeoutMs = 2000;
        public String beliefCheckpointSha256 = null;
        /** The cube list file ({"cards":[{"name","tags"}]}) and its sha256 pin. */
        public String beliefCube = null;
        public String beliefCubeSha256 = null;
        /** Copies of each basic land type in the pool. */
        public int beliefBasics = 8;

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

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("worlds", worlds);
            o.addProperty("breadth", breadth);
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
            if (deadEtb != AiFixes.Mode.OFF) {
                o.addProperty("deadEtb", deadEtb.key());
            }
            if (zeroX != AiFixes.Mode.OFF) {
                o.addProperty("zeroX", zeroX.key());
            }
            if (crewNoop != AiFixes.Mode.OFF) {
                o.addProperty("crewNoop", crewNoop.key());
            }
            if (copyEot != AiFixes.Mode.OFF) {
                o.addProperty("copyEot", copyEot.key());
            }
            if (departMedian != AiFixes.Mode.OFF) {
                o.addProperty("departMedian", departMedian.key());
            }
            o.addProperty("maxDeparturesPerTurn", maxDeparturesPerTurn);
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
        /** copyEot (null = off): searched decisions with live until commands pending, those commands, how many remap. */
        public String copyEotMode;
        public long copyEotDecisions, copyEotCommands, copyEotRemappable, copyEotDepartures;
        /** departMedian (null = off): candidates the median gate set aside; decisions where it changed (ON) or would change (SHADOW) the choice. */
        public String departMedianMode;
        public long departMedianGated, departMedianChanged;
        public long rollouts, rolloutFailures, rolloutCapped, candidatesDropped, steps;
        public long searchNanos, maxSearchNanos;
        public long attackDecisions, attackSearched, attackDeparted, blockDecisions, blockSearched, blockDeparted;
        public long combatNanos;
        /** Wall budget: searched decisions that ran past it (and played Forge's answer); play-outs stopped by it. */
        public long capped, rolloutsAborted;
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
        public boolean beliefOn;
        public long beliefDecisions, beliefSkipped, beliefCalls, beliefFailures, beliefNanos, beliefMaxNanos;
        public long beliefWorlds, beliefCards, beliefCreateNanos, beliefMismatch, beliefPoolSlots, beliefUnknownCards;
        public String beliefDigest, beliefCheckpoint, beliefLastError;
        public final JsonArray beliefCoverage = new JsonArray();

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
            o.addProperty("candidatesDropped", candidatesDropped);
            o.addProperty("steps", steps);
            o.addProperty("searchMs", searchNanos / 1e6);
            o.addProperty("maxSearchMs", maxSearchNanos / 1e6);
            o.addProperty("msPerSearch", searched == 0 ? 0 : searchNanos / 1e6 / searched);
            if (capped > 0 || rolloutsAborted > 0) {
                o.addProperty("capped", capped);
                o.addProperty("rolloutsAborted", rolloutsAborted);
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
            if (beliefOn) {
                o.addProperty("beliefDecisions", beliefDecisions);
                o.addProperty("beliefSkipped", beliefSkipped);
                o.addProperty("beliefCalls", beliefCalls);
                o.addProperty("beliefFailures", beliefFailures);
                o.addProperty("beliefMs", beliefNanos / 1e6);
                o.addProperty("beliefMaxMs", beliefMaxNanos / 1e6);
                o.addProperty("beliefWorlds", beliefWorlds);
                o.addProperty("beliefCards", beliefCards);
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

        private Cand(Cand base, String tgt, String tgtLabel) {
            this.pass = false;
            this.hostId = base.hostId;
            this.desc = base.desc;
            this.land = false;
            this.isDefault = false;
            this.label = base.label + " -> " + tgtLabel;
            this.kind = base.kind;
            this.tgt = tgt;
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
            return pass ? "pass" : hostId + "|" + (land ? "L" : "S") + "|" + desc + (tgt == null ? "" : "|T:" + tgt);
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
    private JsonObject modelDeck = null;
    private int decisionIndex = 0;
    private int departuresTurn = -1;
    private int departuresThisTurn = 0;
    private final Map<String, Integer> departureCounts = new TreeMap<>();
    private boolean fidelityArmed = true;
    /** The current searched decision's wall deadline (System.nanoTime), 0 = none. Read by play-outs on any thread. */
    private volatile long deadline = 0L;
    /** Forge AI's own time for the current decision (set by the controller before {@link #decide}), for the decision log. */
    private long forgeNanos = -1L;

    /** Fidelity probe: the live game's fingerprint at the watched turn, compared with a truth rollout. */
    private FidelityWatch liveWatch = null;

    public LookaheadSearch(Config cfg) {
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
        if (cfg.copyEot != AiFixes.Mode.OFF) {
            stats.copyEotMode = cfg.copyEot.key();
        }
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
        if (cfg.beliefOn()) {
            if (!"off".equals(cfg.belief) && !"human".equals(cfg.belief) && !"uniform".equals(cfg.belief)) {
                throw new IllegalStateException("belief must be off, human or uniform: " + cfg.belief);
            }
            if (cfg.reuse) {
                // A carried world keeps live card ids; a belief world holds cards the live game does not have.
                throw new IllegalStateException("belief does not combine with reuse (carried worlds hold live card ids)");
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
        final BeliefSampler.Cube cube = BeliefSampler.loadCube(c.beliefCube, c.beliefCubeSha256, c.beliefBasics);
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

    public Config getConfig() {
        return cfg;
    }

    public Stats getStats() {
        return stats;
    }

    /** The controller's measured time for Forge AI's own answer to the decision about to be searched (log only). */
    public void noteForgeNanos(long nanos) {
        forgeNanos = nanos;
    }

    private boolean pastDeadline() {
        final long d = deadline;
        return d != 0L && System.nanoTime() - d > 0;
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
            final String why = GameCopier.stackUnsupported(live);
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
        deadline = cfg.budgetMs > 0 ? t0 + cfg.budgetMs * 1_000_000L : 0L;
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
            deadline = 0L;
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
        final JsonObject[] labelRoot = labelSink != null ? new JsonObject[1] : null;
        final List<Cand> base = enumerate(live, me, defSa, decisionSeed, pv, beliefRoot, labelRoot);
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
        if (model != null) {
            modelLeaves(live, me, values, outs, ok, k);
        }

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
        if (cfg.deadEtb == AiFixes.Mode.SHADOW && best != 0 && deadEtbKeys.contains(cands.get(best).key())) {
            stats.deadEtbShadowBest++;
        }
        if (cfg.zeroX == AiFixes.Mode.SHADOW && best != 0 && zeroXKeys.contains(cands.get(best).key())) {
            stats.zeroXShadowBest++;
        }
        if (cfg.crewNoop == AiFixes.Mode.SHADOW && best != 0 && crewNoopKeys.contains(cands.get(best).key())) {
            stats.crewNoopShadowBest++;
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
        if (overBudget) {
            stats.capped++;
            outcome = "capped";
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
            d.addProperty("outcome", outcome);
            d.addProperty("departed", "departed".equals(outcome));
            d.addProperty("searched", stats.searched);
            d.addProperty("departedTotal", stats.departed);
            d.addProperty("cappedTotal", stats.capped);
            System.err.println("[lookahead-decision] " + d);
        }
        if (labelSink != null) {
            emitLabel(live, me, def, index, turn, ph, cands, bSize, values, ok, k, best, outcome, overBudget, abortedHere, dt,
                    labelRoot == null ? null : labelRoot[0]);
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

    /**
     * Label dump: one searched decision as a JSON object for the sink. Reads only the search's own arrays, the
     * enumeration copy's root state, Forge AI's answer and turn/phase/stack/mulligan counters of the live game. Never
     * throws into the game: a failure is counted and the decision stands.
     */
    private void emitLabel(Game live, Player me, List<SpellAbility> def, int index, int turn, PhaseHandler ph, List<Cand> cands,
            int bSize, double[][] values, boolean[] ok, int k, int best, String outcome, boolean overBudget, int aborted, long dt,
            JsonObject root) {
        try {
            final JsonObject o = new JsonObject();
            o.addProperty("schema", LABEL_SCHEMA);
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
                if (cfg.deadEtb == AiFixes.Mode.SHADOW && deadEtbKeys.contains(cd.key())) {
                    co.addProperty("deadEtb", true);
                }
                if (cfg.zeroX == AiFixes.Mode.SHADOW && zeroXKeys.contains(cd.key())) {
                    co.addProperty("zeroX", true);
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

    private void record(double[][] values, Rollout[][] outs, boolean[] ok, int cc, int ww, Rollout r) {
        synchronized (values) {
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
                        record(values, outs, ok, cc, ww, r);
                    }
                });
            } else {
                for (int c = 0; c < n; c++) {
                    final int ww = w, cc = c;
                    if (prep[cc][ww] == null) {
                        // A reused value, or (budget) a play-out that never started.
                        record(values, outs, ok, cc, ww, outs[cc][ww] != null ? outs[cc][ww] : aborted());
                        continue;
                    }
                    tasks.add(() -> {
                        Rollout r = play(prep[cc][ww], null);
                        prep[cc][ww] = null;
                        record(values, outs, ok, cc, ww, r);
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
        runAll(tasks);
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
        if (pool == null) {
            for (Runnable r : tasks) {
                r.run();
            }
            return;
        }
        List<Future<?>> fs = new ArrayList<>();
        for (Runnable r : tasks) {
            fs.add(pool.submit(r));
        }
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException("look-ahead worker failed", e);
            }
        }
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
        out.add(new Cand(defSa, true));
        if (defSa != null) {
            out.add(new Cand(null, false));
        }
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 7)));
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
            int deadHere = 0, zeroHere = 0, crewHere = 0;
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
            for (SpellAbility sa : legal) {
                if (out.size() >= cfg.breadth) {
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
            AiCache.closeScope();
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

    static SpellAbility locate(Game g, Player p, Cand c) {
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
        return null;
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

    private static Field fExtraPhases;

    /** True when PhaseHandler has an extra phase queued this turn (an extra combat may follow); unknown = true. */
    static boolean extraPhasePending(PhaseHandler ph) {
        try {
            synchronized (LookaheadSearch.class) {
                if (fExtraPhases == null) {
                    fExtraPhases = PhaseHandler.class.getDeclaredField("extraPhases");
                    fExtraPhases.setAccessible(true);
                }
            }
            final Object m = fExtraPhases.get(ph);
            return !(m instanceof Map) || !((Map<?, ?>) m).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
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
                    if (resample) {
                        final BeliefSampler.Dist bd = decisionBelief;
                        if (bd != null) {
                            resampleBelief(live, liveMe, p.g, p.me, new Random(mix(worldSeed, 2)), bd);
                        } else {
                            resample(live, liveMe, p.g, p.me, new Random(mix(worldSeed, 2)));
                        }
                    }
                    MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(worldSeed, 3)));
                }
                if (!c.pass) {
                    SpellAbility sa = prepare(p.g, p.me, c, defSa, copier);
                    if (sa == null) {
                        p.failed = true;
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
        if (cfg.copyEot == AiFixes.Mode.ON) {
            c.setCopyUntilEot(true);
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
            final ScriptedFirst sf = new ScriptedFirst(g, me, me.getController().getLobbyPlayer(), p.first);
            final int[] stepRef = new int[1];
            sf.trajectory = p.trajectory;
            sf.stepRef = stepRef;
            me.dangerouslySetController(sf);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new RolloutAi(g, o, o.getController().getLobbyPlayer()));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            givePriority(ph, me);
            if (p.firstPriority != null) {
                setFirstPriority(ph, p.firstPriority);
            }
            final TurnWatch watch = new TurnWatch(ph.getTurn() + cfg.horizonTurns, Boolean.TRUE.equals(wantFp) ? me : null);
            g.subscribeToEvents(watch);
            long b = System.nanoTime();
            int steps = 0;
            final List<String> sl = p.stepLog;
            int logSeen = sl == null ? 0 : g.getGameLog().getAllEntries().size();
            if (memo != null) {
                r.keys = new ArrayList<>();
            }
            WorldMemo.Entry hit = null;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                if (pastDeadline()) {
                    r.aborted = true;
                    break;
                }
                ph.mainLoopStep();
                steps++;
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
            if (r.aborted) {
                r.ok = false;
                r.value = Double.NEGATIVE_INFINITY;
                return r;
            }
            if (hit != null) {
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
                r.value = value(g, me);
                if (model != null) {
                    r.pTerminal = terminalP(g, me);
                    if (Double.isNaN(r.pTerminal)) {
                        r.leaf = forge.bench.StateEncoder.encode(g, me);
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
        } catch (RuntimeException | StackOverflowError e) {
            r.ok = false;
            r.value = Double.NEGATIVE_INFINITY;
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

    private static Field fPrio, fFirst, fGive;

    static synchronized void initFields() {
        if (fPrio != null) {
            return;
        }
        try {
            fPrio = PhaseHandler.class.getDeclaredField("pPlayerPriority");
            fFirst = PhaseHandler.class.getDeclaredField("pFirstPriority");
            fGive = PhaseHandler.class.getDeclaredField("givePriorityToPlayer");
            fPrio.setAccessible(true);
            fFirst.setAccessible(true);
            fGive.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The player who holds "first priority" (acted last) in {@code ph}, i.e. whose turn it is to see the stack resolve. */
    static Player firstPriority(PhaseHandler ph) {
        initFields();
        try {
            return (Player) fFirst.get(ph);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setFirstPriority(PhaseHandler ph, Player p) {
        initFields();
        try {
            fFirst.set(ph, p);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether {@code ph} gives priority on its next main-loop step (read-only; state keys). */
    static boolean givePriorityFlag(PhaseHandler ph) {
        initFields();
        try {
            return fGive.getBoolean(ph);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The copy resumes exactly where the live seat stands: it holds priority, it acted first. */
    static void givePriority(PhaseHandler ph, Player p) {
        initFields();
        try {
            fPrio.set(ph, p);
            fFirst.set(ph, p);
            fGive.setBoolean(ph, true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
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
        initFields();
        try {
            fPrio.set(ph, prio);
            fFirst.set(ph, first);
            fGive.setBoolean(ph, give);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
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
                    resample(live, liveMe, g, me, new Random(mix(worldSeed, 2)));
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
            System.err.println("[lookahead] combat rollout failed: " + e);
        } finally {
            AiCache.closeScope();
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
                    resample(live, liveMe, p.g, p.me, new Random(mix(worldSeed, 2)));
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

    /** The opponent's hidden hand cards / known library top, as the seat sees them in the live game. */
    private static int[] hiddenCounts(Game live, Player liveOpp, PlayerView myView) {
        int keep = 0;
        final List<Card> hand = new ArrayList<>(liveOpp.getCardsIn(ZoneType.Hand));
        for (Card c : hand) {
            if (c.getView().canBeShownTo(myView)) {
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
        final int[] hid = hiddenCounts(live, liveOpp, myView);
        if (hid[0] == 0 && hid[1] == 0) {
            stats.beliefSkipped++;
            return null;
        }
        final Set<String> known = new HashSet<>(deckOf(liveMe).keySet());
        int openMana = 0;
        for (Card c : live.getCardsInGame()) {
            if (c.getOwner() == liveOpp && !c.isToken() && !c.isFaceDown() && c.getPaperCard() != null
                    && c.getView().canBeShownTo(myView)) {
                known.add(c.getPaperCard().getName());
            }
        }
        for (Card c : liveOpp.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                openMana++;
            }
        }
        final List<String> pool = new ArrayList<>();
        for (String name : beliefCube.names) {
            if (!known.contains(name)) {
                pool.add(name);
            }
        }
        pool.addAll(Arrays.asList(BeliefSampler.BASICS));
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
        final BeliefSampler.Dist bd = new BeliefSampler.Dist(pool, w, cfg.beliefBasics, hid[0], hid[1]);
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
     * Belief re-draw of one world (in a copy): the opponent's hidden hand from the belief, the hidden library uniform
     * over the pool slots left (known cards stay where they are), then our own library as {@link #resample}. The
     * opponent's hidden cards in the copy are replaced by new cards of the drawn names.
     */
    void resampleBelief(Game live, Player liveMe, Game g, Player me, Random rng, BeliefSampler.Dist bd) {
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            if (opp.getId() != bd.oppId) {
                continue;
            }
            final Player liveOpp = (Player) live.getPlayer(opp.getId());
            final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            final List<Card> keep = new ArrayList<>();
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && lc.getView().canBeShownTo(myView)) {
                    keep.add(c);
                }
            }
            final List<Card> libKnownTop = new ArrayList<>();
            final List<Card> liveLib = liveOpp == null ? Collections.emptyList() : new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library));
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
            final int libNeed = opp.getCardsIn(ZoneType.Library).size() - libKnownTop.size();
            if (need != bd.n || libNeed != bd.libHidden) {
                // The copy does not show what the decision saw: keep the default re-draw for this world.
                synchronized (stats) {
                    stats.beliefMismatch++;
                }
                resample(live, liveMe, g, me, rng);
                return;
            }
            final int[] handSlots = bd.sampleHand(rng);
            final List<Integer> libSlots = bd.library(handSlots, rng);
            final List<Integer> hs = new ArrayList<>();
            for (int i : handSlots) {
                hs.add(i);
            }
            Collections.shuffle(hs, rng);
            final long c0 = System.nanoTime();
            final List<Card> newHand = new ArrayList<>(keep);
            for (int i : hs) {
                newHand.add(beliefCard(bd.slotName[i], opp, g));
            }
            final List<Card> newLib = new ArrayList<>(libKnownTop);
            for (int i : libSlots) {
                newLib.add(beliefCard(bd.slotName[i], opp, g));
            }
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(newLib);
            synchronized (stats) {
                stats.beliefWorlds++;
                stats.beliefCards += hs.size() + libSlots.size();
                stats.beliefCreateNanos += System.nanoTime() - c0;
            }
        }
        shuffleOwnLibrary(liveMe, me, rng);
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
            AiCache.closeScope();
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
            AiCache.closeScope();
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
            AiCache.closeScope();
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

    static long mix(long a, long b) {
        long x = a * 0x9E3779B97F4A7C15L + b;
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }
}
