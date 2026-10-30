package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AiFixes;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.BenchSession;
import forge.bench.CallCounter;
import forge.bench.JsonRpcChannel;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * S1 (lane s1-search-1007): Forge's look-ahead ({@link LookaheadSearch}) over a learned policy's own priority decisions,
 * for one game's RL seat. GOAL rev 5's search over learned nets, built for the read after R1.
 *
 * <p>At a contested PRIORITY ask of the RL seat (empty stack unless {@code stack}), after the server's greedy DECISION:
 * <ol>
 *   <li><b>Prior.</b> SCORE (search service) gives the policy's probabilities over the ask's candidates. X variants of
 *       one menu entry are pooled (their sum is the entry's prior; the most likely variant is the entry's X). The
 *       searched set is the policy's own choice first (the default), then the other entries by prior, up to
 *       {@code breadth} after the guards.</li>
 *   <li><b>Play-outs.</b> K worlds (the opponent's hidden cards and our library order re-drawn, as K8) x the set, to the
 *       start of turn now + {@code horizon}. {@code playout=forge}: Forge AI plays both seats after the candidate (it
 *       also targets the candidate), as K8. {@code playout=policy}: the searching seat is the policy itself (a bridged
 *       seat in the copy, its asks answered by the search service, greedy or sampled), the opponent Forge AI.</li>
 *   <li><b>Leaf.</b> {@code leaf=static}: Forge's static evaluator (K8). {@code leaf=value}: the policy's
 *       observation-only value head v(o) of the seat at its first priority in the horizon turn, as P(win) = (v + 1) / 2;
 *       terminal play-outs 1 / 0 / 0.5.</li>
 *   <li><b>Choice.</b> K8's: argmax of the mean over worlds, a departure from the default only past the departZ gate,
 *       the per-turn guards. A departure answers the chosen entry's most likely candidate.</li>
 * </ol>
 * Every other ask of the seat is the policy's (greedy), as for the pure policy.
 *
 * <p><b>Observation schema</b> (lane search-v2-1009): the search speaks the seat's own schema, obs-v1 (wire/1, the
 * default) or obs-v2 (wire/2): its HELLO names that schema's sha (the service refuses another), the copies' featurizers
 * and forked seat knowledge run it, and the leaf frames are that schema's PRIORITY frames, so v(o), the prior and the
 * play-out seat read the observation the policy was trained on. The spec key {@code obs} (absent = the seat's) pins it:
 * a seat of another schema refuses the spec, and a service whose HELLO_ACK names another schema is refused. Under
 * obs-v1 every frame, HELLO and log row is byte-identical to the S1 read's.
 *
 * <p><b>S-t</b> (lane cm-choice-search-1009; spec key {@code choices} = m > 0, policy play-outs only; with m = 0, the
 * default, nothing below the S1 search runs): each searched candidate's world-0 play-out is also a probe that records
 * the seat's choice asks inside the candidate's resolution window ({@link ChoiceWindow}: until the seat's next priority
 * with an empty stack, or the end of the turn) with the policy's prior. For the first m asks of a candidate that have an
 * alternative (and, with {@code choiceMaxProb} < 1, that the policy answers with prior below it), the top
 * {@code choiceAlts} other answers by prior each make a <i>macro candidate</i>: the candidate with that answer forced (a
 * schedule matched by the ask's identity and ordinal and the answer's identity, {@link ChoiceWindow}). Macros, in search
 * order (or by joint prior: {@code choiceRank}) up to {@code choiceCap} searched candidates in all, are played out in the
 * same K worlds and compete in the same argmax and departZ gate. A macro departure plays its base action and installs its schedule in the live seat's window; a
 * scheduled live ask whose answer is not a candidate falls back to the policy (counted).
 *
 * <p><b>Label sink</b> (lane r3-distill-1010, R3-CM D2; spec key {@code labelSink}, set by the harness like
 * {@code decisionLog}, never part of the teacher's spec; absent = off, and nothing below writes or calls anything): per
 * searched decision a ROOT record (the DECIDE payload SCORE saw, the policy's prior, each candidate's pooled entry, every
 * searched candidate's per-world values and mean, the macros, the teacher's choice), and after a macro departure a
 * CHOICE record per scheduled live ask the schedule answered (the ask's frame re-encoded SINGLE as the probe scores it,
 * SCORE's prior on it, the policy's own and the scheduled answer, each candidate's match to a searched answer). One
 * gzip member of {@code MXL1} records per game, appended at game end ({@link #flushLog}); format
 * {@code mtgx-rl-labelsink/1}, read by mtgx {@code tools/ml/rl/labels.py}. The sink only records: every decision is
 * the same with it on or off.
 *
 * <p><b>Search seat</b> (r3-distill-1010): {@link #searchSeat} >= 0 (the GAME message's {@code search_seat}) limits the
 * look-ahead to that seat (RlSeat checks it); the summary then echoes {@code seat}. -1 (the default) = every RL seat.
 */
public final class RlSearch implements RlSeat.PrioritySearch, LookaheadSearch.SearchHooks, LookaheadSearch.ChoiceExpander {

    /** The arm's search spec (RlActorBench config key {@code search}). */
    public static final class Config {
        public int worlds = 8;
        public int breadth = 4;
        public int horizon = 2;
        public int threads = 0;
        public int maxSteps = 5000;
        public int leafExtraSteps = 400;
        public double departZ = 1.645;
        public double margin = 0.0;
        /** static | value */
        public String leaf = "static";
        /** forge | policy */
        public String playout = "forge";
        public boolean playoutSample = false;
        public String deadEtb = "off", zeroX = "off", crewNoop = "off", departMedian = "off";
        public boolean stack = false;
        /** Also search pass (second, after the default), as K8's candidate set does, whatever its prior. */
        public boolean includePass = false;
        /** host:port of tools/ml/rl/search_server.py. */
        public String server;
        public int readTimeoutMs = 120_000;
        /**
         * Per game, in ms: the game thread's CPU plus its account (the search's workers and every Forge AI eval thread,
         * forge.ai.CpuAccount); past it the game is void (cpu_cap). 0 = none.
         */
        public long cpuCapMs = 0;
        public long seedSalt = 0x51L;
        /** One JSONL row per searched decision (this JVM appends; "{actor}" = the actor id), or null. */
        public String decisionLog;
        /**
         * R3-CM D2 (lane r3-distill-1010): the label sink file ("{actor}" = the actor id, "{pid}" = this JVM's pid), or
         * null = off. Set by the harness, like {@link #decisionLog}; not in {@link #toJson}.
         */
        public String labelSink;
        /** The checkpoint the service must serve (HELLO_ACK policy_sha), or null = not checked. */
        public String policySha;
        /**
         * Live play (lane live-sc-1009; 0 = none, the default: decisions do not depend on the host): the look-ahead's
         * wall-clock budget per searched decision ({@link LookaheadSearch.Config#budgetMs}); past it the default (the
         * policy's own choice) is played ("capped"), as the live K8 plays Forge's answer. A policy play-out seat stops at
         * its next service request past it, and the wait for the play-outs ends at the budget plus
         * {@link LookaheadSearch#WALL_GRACE_MS} at the latest (lane sc-wallguard-1009).
         */
        public long budgetMs = 0L;
        /** Connect timeout of each search-service connection, in ms (live play shortens it; default as S1). */
        public int connectTimeoutMs = 10_000;
        /**
         * S-t (lane cm-choice-search-1009): the contested choice asks expanded per searched candidate (m); 0 = off (the
         * S1 search exactly). Policy play-outs only.
         */
        public int choices = 0;
        /** S-t: alternative answers per expanded ask (b), by the policy's prior. */
        public int choiceAlts = 2;
        /**
         * S-t: an ask is expanded when the policy's largest prior over its candidates is below this; 1 (the default) =
         * every ask with an alternative, however sure the policy is (lane cm-choice-search-1009: the policy picks Woodfall
         * Primus over Craterhoof for Natural Order with prior 1.0, so a confidence filter never searches that pick).
         */
        public double choiceMaxProb = 1.0;
        /** S-t: searched candidates in all (base + macro). */
        public int choiceCap = 10;
        /**
         * S-t: which macros fill the cap. "base" (the default): the searched candidates in search order (the policy's own
         * choice first, then by prior), each candidate's asks in window order, each ask's alternatives by prior.
         * "joint": by joint prior (candidate's x answer's; this lane's first spec, which ranks a confidently wrong
         * policy's needed answer last).
         */
        public String choiceRank = "base";
        /**
         * The loop guard (lane cm-choice-search-1009): departures to one candidate at one phase and stack size per turn.
         * 2 = the S1 read's (K8's) rule; a combo loop (Kiki-Jiki + Zealous Conscripts) repeats one action more often.
         */
        public int sameDepartures = 2;
        /** S-t: the families a probe records and expands. */
        public String choiceFamilies = ChoiceWindow.DEFAULT_FAMILIES;
        java.util.Set<Integer> choiceFamilySet = null;
        /**
         * The observation schema the search's policy speaks (lane search-v2-1009): 1 (obs-v1, wire/1) or 2 (obs-v2,
         * wire/2); 0 (the default) = the seat's. Set, it is a pin: a seat of another schema refuses the spec.
         */
        public int obs = 0;
        /**
         * Copy fidelity (lane copy-fidelity-1010; {@link LookaheadSearch.Config#copyFidelity}): 0 (the default) = the
         * search's copies as before; 1 = they carry the live game's per-turn and history state (attacked this turn,
         * activation counts, zone-entry turns, mulligans, spells cast this turn...), so a candidate legal only through it
         * (a boast) is in the play-out's menu; 2 = as 0, and a decision where a searched candidate is missing from those
         * copies is searched again on level-1 copies (the only decisions that can change).
         */
        public int copyFidelity = 0;
        /**
         * Lane search-bones-1015, the baseline "S-c + ForcedWin" (null = off, the default, and then nothing changes): the
         * forced-win solver in front of the RL seat ({@link SolverDriver}), configured by these keys (string values, the
         * keys of {@link forge.ai.simulation.ForcedWinSolver.Config#fromProps}; {} = the solver's defaults).
         */
        public JsonObject forcedWin = null;
        /**
         * Lane search-bones-1015, S-b (null = off, the default, and then nothing changes): turn-plans over S-c
         * ({@link PlanSearch.Spec}).
         */
        public JsonObject plans = null;
        /**
         * r2-choose-a-card-1029 (null = off, the default, and then nothing changes, the spec's JSON included): choose-a-card
         * on the search seat's bridged controller ({@link forge.ai.simulation.ChooseACard.Config#fromJson}); its value is
         * this search's leaf ({@code LEAVES}), its re-draw the search's belief.
         */
        public JsonObject cac = null;
        /**
         * Lane block-search-1019 (0 = off, the default, and then nothing changes): 1 = the look-ahead also searches the seat's
         * declare-blockers asks ({@link #decideBlocks}): the policy's own declaration (the default), its next most sampled
         * ones, no blocks and Forge AI's declaration, played out as the priority candidates are. Policy play-outs only.
         */
        public int blocks = 0;
        /** Block search: policy samples (sampled DECIDEs of the ask) whose distinct declarations are ranked by count. */
        public int blockSamples = 8;
        /** Block search: the policy's declarations searched in all, its own (greedy) one included. */
        public int blockTop = 3;
        /**
         * honest-belief-1020 ("off" = the default, and then nothing changes): "uniform" = every world, solver line check and
         * engine root draws the opponent's hidden hand and library from an observation-only pool
         * ({@link LookaheadSearch.Config#belief}): the cube list ({@link #beliefCube}, or the event's pool
         * {@link #beliefPool}) minus our deck minus every opponent card we can see, plus basics. The opponent's real
         * remainder is never read.
         */
        public String belief = "off";
        /** honest-belief-1020: the cube list file ({@code mtgx-belief-cube/1}; {@code classpath:} = a jar resource) and its pin. */
        public String beliefCube;
        public String beliefCubeSha256;
        /** honest-belief-1020: copies of each basic in the pool. */
        public int beliefBasics = 8;
        /** honest-belief-1020: the pool is a singleton cube (our deck and the cards we saw leave it). */
        public boolean beliefSingleton = true;
        /** honest-belief-1020: the event's card pool (names), set per game by the live seat; not a JSON key. */
        public List<String> beliefPool;
        /** deck-guess-1021: the opponent's cards from earlier games of this match, set per game by the live seat; not a JSON key. */
        public List<String> beliefSeen;
        // ---- bvw-step2-fork-1027 (S2T, the symmetric teacher; every key off by default, and then nothing changes)
        /**
         * S2T: "forge" (the default) = every play-out's opponent is Forge AI; "policy" = the same policy seat as ours
         * (bridged, this service, its own observation only). Policy play-outs only.
         */
        public String opponent = "forge";
        /** S2T: the value leaf is (v_s + 1 - v_o) / 2 at the same state, both seats' own frames (leaf "value" only). */
        public boolean leafSym = false;
        /**
         * S2T, belief "codraft": the co-draft table file ({@code {"syn": {a: {b: score}}}}; {@code classpath:} = a jar
         * resource), its sha256 pin and the prior's gain. Every world (and the plans' solver worlds) draws the opponent's
         * hidden cards from the belief pool with weights {@code 1 + gain * max_s syn[s][card]^2} over the cards they showed
         * in this game.
         */
        public String beliefPrior;
        public String beliefPriorSha256;
        public double beliefPriorGain = 20.0;
        // ---- step3-playout-fixes-1028 (BVW step 3; every key off by default, and then nothing changes)
        /**
         * The "Cancel Search?" loop guard ({@link RlLoopGuard}) on every search play-out seat (ours and, with opponent
         * "policy", the opponent's): K no-answers in a row to the same one-at-a-time zone-change card ask and its confirm,
         * then the guard answers the confirm yes. 0 = off (the seats keep no guard, as before).
         */
        public int playoutLoopGuard = 0;
        /**
         * The backstop: each search play-out seat (ours and the opponent's) ends its copy after this many sent decisions
         * instead of the seat default 3,000 (the play-out fails, {@code max_decisions}, as before); OPR roll-outs keep
         * the default. Also puts the decision's budget stop on the opponent seat's service requests. 0 = off.
         */
        public int playoutMaxDecisions = 0;
        /**
         * The T and fresh re-plays ({@link LookaheadSearch#replayCandidate}, {@link LookaheadSearch#reevaluate}) locate a
         * may-play candidate by its base description too ({@link LookaheadSearch#sameMayPlay}), as the main search's
         * level-2 pass does; only after the exact and prefix matches failed. false = off.
         */
        public boolean replayMayPlayLoose = false;

        static final java.util.Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("worlds", "breadth",
                "horizon", "threads", "maxSteps", "leafExtraSteps", "departZ", "margin", "leaf", "playout",
                "playoutSample", "deadEtb", "zeroX", "crewNoop", "departMedian", "stack", "server", "readTimeoutMs",
                "cpuCapMs", "seedSalt", "decisionLog", "policySha", "includePass", "budgetMs", "connectTimeoutMs",
                "choices", "choiceAlts", "choiceMaxProb", "choiceCap", "choiceFamilies", "choiceRank", "sameDepartures",
                "obs", "labelSink", "copyFidelity", "forcedWin", "plans", "blocks", "blockSamples", "blockTop", "belief",
                "beliefCube", "beliefCubeSha256", "beliefBasics", "beliefSingleton", "opponent", "leafSym", "beliefPrior",
                "beliefPriorSha256", "beliefPriorGain", "playoutLoopGuard", "playoutMaxDecisions", "replayMayPlayLoose",
                "chooseACard"));

        public static Config parse(final JsonObject o) {
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("search: unknown key " + k);
                }
            }
            final Config c = new Config();
            if (o.has("worlds")) c.worlds = o.get("worlds").getAsInt();
            if (o.has("breadth")) c.breadth = o.get("breadth").getAsInt();
            if (o.has("horizon")) c.horizon = o.get("horizon").getAsInt();
            if (o.has("threads")) c.threads = o.get("threads").getAsInt();
            if (o.has("maxSteps")) c.maxSteps = o.get("maxSteps").getAsInt();
            if (o.has("leafExtraSteps")) c.leafExtraSteps = o.get("leafExtraSteps").getAsInt();
            if (o.has("departZ")) c.departZ = o.get("departZ").getAsDouble();
            if (o.has("margin")) c.margin = o.get("margin").getAsDouble();
            if (o.has("leaf")) c.leaf = o.get("leaf").getAsString();
            if (o.has("playout")) c.playout = o.get("playout").getAsString();
            if (o.has("playoutSample")) c.playoutSample = o.get("playoutSample").getAsBoolean();
            if (o.has("deadEtb")) c.deadEtb = o.get("deadEtb").getAsString();
            if (o.has("zeroX")) c.zeroX = o.get("zeroX").getAsString();
            if (o.has("crewNoop")) c.crewNoop = o.get("crewNoop").getAsString();
            if (o.has("departMedian")) c.departMedian = o.get("departMedian").getAsString();
            if (o.has("stack")) c.stack = o.get("stack").getAsBoolean();
            if (o.has("includePass")) c.includePass = o.get("includePass").getAsBoolean();
            if (o.has("server") && !o.get("server").isJsonNull()) c.server = o.get("server").getAsString();
            if (o.has("readTimeoutMs")) c.readTimeoutMs = o.get("readTimeoutMs").getAsInt();
            if (o.has("cpuCapMs")) c.cpuCapMs = o.get("cpuCapMs").getAsLong();
            if (o.has("seedSalt")) c.seedSalt = o.get("seedSalt").getAsLong();
            if (o.has("decisionLog") && !o.get("decisionLog").isJsonNull()) c.decisionLog = o.get("decisionLog").getAsString();
            if (o.has("policySha") && !o.get("policySha").isJsonNull()) c.policySha = o.get("policySha").getAsString();
            if (o.has("budgetMs")) c.budgetMs = o.get("budgetMs").getAsLong();
            if (o.has("connectTimeoutMs")) c.connectTimeoutMs = o.get("connectTimeoutMs").getAsInt();
            if (o.has("choices")) c.choices = o.get("choices").getAsInt();
            if (o.has("choiceAlts")) c.choiceAlts = o.get("choiceAlts").getAsInt();
            if (o.has("choiceMaxProb")) c.choiceMaxProb = o.get("choiceMaxProb").getAsDouble();
            if (o.has("choiceCap")) c.choiceCap = o.get("choiceCap").getAsInt();
            if (o.has("choiceFamilies")) c.choiceFamilies = o.get("choiceFamilies").getAsString();
            if (o.has("choiceRank")) c.choiceRank = o.get("choiceRank").getAsString();
            if (o.has("sameDepartures")) c.sameDepartures = o.get("sameDepartures").getAsInt();
            if (o.has("obs")) c.obs = o.get("obs").getAsInt();
            if (o.has("labelSink") && !o.get("labelSink").isJsonNull()) c.labelSink = o.get("labelSink").getAsString();
            if (o.has("copyFidelity")) c.copyFidelity = o.get("copyFidelity").getAsInt();
            if (o.has("forcedWin") && !o.get("forcedWin").isJsonNull()) c.forcedWin = o.getAsJsonObject("forcedWin");
            if (o.has("plans") && !o.get("plans").isJsonNull()) c.plans = o.getAsJsonObject("plans");
            if (o.has("chooseACard") && !o.get("chooseACard").isJsonNull()) {
                c.cac = o.getAsJsonObject("chooseACard");
                forge.ai.simulation.ChooseACard.Config.fromJson(c.cac); // refuse a bad spec at parse time
            }
            if (o.has("blocks")) c.blocks = o.get("blocks").getAsInt();
            if (o.has("blockSamples")) c.blockSamples = o.get("blockSamples").getAsInt();
            if (o.has("blockTop")) c.blockTop = o.get("blockTop").getAsInt();
            if (o.has("belief")) c.belief = o.get("belief").getAsString();
            if (o.has("beliefCube") && !o.get("beliefCube").isJsonNull()) c.beliefCube = o.get("beliefCube").getAsString();
            if (o.has("beliefCubeSha256") && !o.get("beliefCubeSha256").isJsonNull()) {
                c.beliefCubeSha256 = o.get("beliefCubeSha256").getAsString();
            }
            if (o.has("beliefBasics")) c.beliefBasics = o.get("beliefBasics").getAsInt();
            if (o.has("beliefSingleton")) c.beliefSingleton = o.get("beliefSingleton").getAsBoolean();
            if (o.has("opponent")) c.opponent = o.get("opponent").getAsString();
            if (o.has("leafSym")) c.leafSym = o.get("leafSym").getAsBoolean();
            if (o.has("beliefPrior") && !o.get("beliefPrior").isJsonNull()) c.beliefPrior = o.get("beliefPrior").getAsString();
            if (o.has("beliefPriorSha256") && !o.get("beliefPriorSha256").isJsonNull()) {
                c.beliefPriorSha256 = o.get("beliefPriorSha256").getAsString();
            }
            if (o.has("beliefPriorGain")) c.beliefPriorGain = o.get("beliefPriorGain").getAsDouble();
            if (o.has("playoutLoopGuard")) c.playoutLoopGuard = o.get("playoutLoopGuard").getAsInt();
            if (o.has("playoutMaxDecisions")) c.playoutMaxDecisions = o.get("playoutMaxDecisions").getAsInt();
            if (o.has("replayMayPlayLoose")) c.replayMayPlayLoose = o.get("replayMayPlayLoose").getAsBoolean();
            c.check();
            return c;
        }

        void check() {
            if (!"static".equals(leaf) && !"value".equals(leaf)) {
                throw new IllegalArgumentException("search.leaf must be static or value, not " + leaf);
            }
            if (!"forge".equals(playout) && !"policy".equals(playout)) {
                throw new IllegalArgumentException("search.playout must be forge or policy, not " + playout);
            }
            if (worlds < 1 || breadth < 2 || horizon < 1) {
                throw new IllegalArgumentException("search: worlds >= 1, breadth >= 2, horizon >= 1");
            }
            if (budgetMs < 0 || connectTimeoutMs < 1 || readTimeoutMs < 1) {
                throw new IllegalArgumentException("search: budgetMs >= 0, connectTimeoutMs >= 1, readTimeoutMs >= 1");
            }
            if (server == null) {
                throw new IllegalArgumentException("search.server (host:port of the search service) is required");
            }
            if (choices < 0 || choiceAlts < 1 || choiceCap < 2 || !(choiceMaxProb > 0 && choiceMaxProb <= 1)) {
                throw new IllegalArgumentException("search: choices >= 0, choiceAlts >= 1, choiceCap >= 2, 0 < choiceMaxProb <= 1");
            }
            if (sameDepartures < 1) {
                throw new IllegalArgumentException("search.sameDepartures >= 1");
            }
            if (obs != 0 && obs != 1 && obs != 2) {
                throw new IllegalArgumentException("search.obs must be 1 or 2 (or absent: the seat's), not " + obs);
            }
            if (copyFidelity < 0 || copyFidelity > 2) {
                throw new IllegalArgumentException("search.copyFidelity must be 0, 1 or 2, not " + copyFidelity);
            }
            if (!"base".equals(choiceRank) && !"joint".equals(choiceRank)) {
                throw new IllegalArgumentException("search.choiceRank must be base or joint, not " + choiceRank);
            }
            if (choices > 0 && !"policy".equals(playout)) {
                throw new IllegalArgumentException("search.choices (S-t) needs playout=policy");
            }
            if (plans != null) {
                // search-bones-1015: S-b plays plans with the policy seat; it is not combined with S-t, the label sink or
                // the forced-win baseline
                if (!"policy".equals(playout) || labelSink != null || forcedWin != null) {
                    throw new IllegalArgumentException("search.plans (S-b) needs playout=policy and no labelSink "
                            + "or forcedWin");
                }
                PlanSearch.Spec.parse(plans);
            }
            if (blocks != 0 && blocks != 1) {
                throw new IllegalArgumentException("search.blocks must be 0 (off) or 1, not " + blocks);
            }
            if (blocks > 0 && !"policy".equals(playout)) {
                throw new IllegalArgumentException("search.blocks (block search) needs playout=policy");
            }
            if (blockSamples < 0 || blockSamples > 64 || blockTop < 1 || blockTop > 8) {
                throw new IllegalArgumentException("search: 0 <= blockSamples <= 64, 1 <= blockTop <= 8");
            }
            if (!"off".equals(belief) && !"uniform".equals(belief) && !"codraft".equals(belief)) {
                throw new IllegalArgumentException("search.belief must be off, uniform or codraft, not " + belief);
            }
            if (("uniform".equals(belief) || "codraft".equals(belief)) && beliefCube == null && beliefPool == null) {
                throw new IllegalArgumentException("search.belief=" + belief + " needs beliefCube (or the event's pool)");
            }
            // bvw-step2-fork-1027 (S2T)
            if ("codraft".equals(belief) && (beliefPrior == null || beliefPriorSha256 == null || !(beliefPriorGain >= 0))) {
                throw new IllegalArgumentException("search.belief=codraft needs beliefPrior, its beliefPriorSha256 pin and "
                        + "beliefPriorGain >= 0");
            }
            if (!"codraft".equals(belief) && (beliefPrior != null || beliefPriorSha256 != null)) {
                throw new IllegalArgumentException("search.beliefPrior is for belief=codraft only");
            }
            if (!"forge".equals(opponent) && !"policy".equals(opponent)) {
                throw new IllegalArgumentException("search.opponent must be forge or policy, not " + opponent);
            }
            if ("policy".equals(opponent) && !"policy".equals(playout)) {
                throw new IllegalArgumentException("search.opponent=policy needs playout=policy");
            }
            if (leafSym && !"value".equals(leaf)) {
                throw new IllegalArgumentException("search.leafSym needs leaf=value");
            }
            if (beliefBasics < 0 || beliefBasics > 64) {
                throw new IllegalArgumentException("search.beliefBasics must be 0..64, not " + beliefBasics);
            }
            // step3-playout-fixes-1028
            if (playoutLoopGuard < 0 || playoutLoopGuard > 16) {
                throw new IllegalArgumentException("search.playoutLoopGuard must be 0 (off) .. 16, not " + playoutLoopGuard);
            }
            if (playoutMaxDecisions != 0 && (playoutMaxDecisions < 100 || playoutMaxDecisions > 3000)) {
                throw new IllegalArgumentException("search.playoutMaxDecisions must be 0 (off) or 100..3000, not "
                        + playoutMaxDecisions);
            }
            if ((playoutLoopGuard > 0 || playoutMaxDecisions > 0 || replayMayPlayLoose) && !"policy".equals(playout)) {
                throw new IllegalArgumentException("search.playoutLoopGuard / playoutMaxDecisions / replayMayPlayLoose "
                        + "need playout=policy");
            }
            choiceFamilySet = ChoiceWindow.parseFamilies(choiceFamilies);
            AiFixes.Mode.parse(deadEtb);
            AiFixes.Mode.parse(zeroX);
            AiFixes.Mode.parse(crewNoop);
            AiFixes.Mode.parse(departMedian);
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("worlds", worlds);
            o.addProperty("breadth", breadth);
            o.addProperty("horizon", horizon);
            o.addProperty("threads", threads);
            o.addProperty("maxSteps", maxSteps);
            o.addProperty("leafExtraSteps", leafExtraSteps);
            o.addProperty("departZ", departZ);
            o.addProperty("margin", margin);
            o.addProperty("leaf", leaf);
            o.addProperty("playout", playout);
            o.addProperty("playoutSample", playoutSample);
            o.addProperty("deadEtb", deadEtb);
            o.addProperty("zeroX", zeroX);
            o.addProperty("crewNoop", crewNoop);
            o.addProperty("departMedian", departMedian);
            o.addProperty("stack", stack);
            o.addProperty("includePass", includePass);
            o.addProperty("cpuCapMs", cpuCapMs);
            o.addProperty("seedSalt", seedSalt);
            if (policySha != null) {
                o.addProperty("policySha", policySha);
            }
            if (budgetMs > 0) {
                // live-sc-1009: only when set, so a bench spec's JSON is unchanged
                o.addProperty("budgetMs", budgetMs);
            }
            if (sameDepartures != 2) {
                // cm-choice-search-1009: only when changed, so an S1 spec's JSON is unchanged
                o.addProperty("sameDepartures", sameDepartures);
            }
            if (obs != 0) {
                // search-v2-1009: only when pinned, so an S1 spec's JSON is unchanged
                o.addProperty("obs", obs);
            }
            if (copyFidelity != 0) {
                // copy-fidelity-1010: only when on, so every other spec's JSON is unchanged
                o.addProperty("copyFidelity", copyFidelity);
            }
            if (forcedWin != null) {
                // search-bones-1015: only when on, so every other spec's JSON is unchanged
                o.add("forcedWin", forcedWin);
            }
            if (plans != null) {
                o.add("plans", plans);
            }
            if (cac != null) {
                o.add("chooseACard", cac); // r2-choose-a-card-1029: only when on
            }
            if (blocks > 0) {
                // block-search-1019: only when on, so every other spec's JSON is unchanged
                o.addProperty("blocks", blocks);
                o.addProperty("blockSamples", blockSamples);
                o.addProperty("blockTop", blockTop);
            }
            if (!"off".equals(belief)) {
                // honest-belief-1020: only when on, so every other spec's JSON is unchanged
                o.addProperty("belief", belief);
                if (beliefPool == null) {
                    // an event pool is named by the server's per-game [belief-pool] line, not here
                    o.addProperty("beliefCube", beliefCube);
                    o.addProperty("beliefCubeSha256", beliefCubeSha256);
                }
                o.addProperty("beliefBasics", beliefBasics);
                o.addProperty("beliefSingleton", beliefSingleton);
                if ("codraft".equals(belief)) {
                    // bvw-step2-fork-1027: only with codraft
                    o.addProperty("beliefPrior", beliefPrior);
                    o.addProperty("beliefPriorSha256", beliefPriorSha256);
                    o.addProperty("beliefPriorGain", beliefPriorGain);
                }
            }
            if (!"forge".equals(opponent)) {
                o.addProperty("opponent", opponent); // bvw-step2-fork-1027: only when on
            }
            if (leafSym) {
                o.addProperty("leafSym", true); // bvw-step2-fork-1027: only when on
            }
            // step3-playout-fixes-1028: only when on, so every other spec's JSON is unchanged
            if (playoutLoopGuard > 0) {
                o.addProperty("playoutLoopGuard", playoutLoopGuard);
            }
            if (playoutMaxDecisions > 0) {
                o.addProperty("playoutMaxDecisions", playoutMaxDecisions);
            }
            if (replayMayPlayLoose) {
                o.addProperty("replayMayPlayLoose", true);
            }
            if (choices > 0) {
                // cm-choice-search-1009: only when S-t is on, so an S1 spec's JSON is unchanged
                o.addProperty("choices", choices);
                o.addProperty("choiceAlts", choiceAlts);
                o.addProperty("choiceMaxProb", choiceMaxProb);
                o.addProperty("choiceCap", choiceCap);
                o.addProperty("choiceFamilies", choiceFamilies);
                o.addProperty("choiceRank", choiceRank);
            }
            return o;
        }
    }

    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();

    private final Config cfg;
    private final LookaheadSearch ls;
    private final long uid;
    private final CardIndex index;
    private final RlKnowledge liveKnow;
    /** The observation schema of the seat, its copies and the service (search-v2-1009): 1 or 2. */
    private final int obsVersion;
    private final String jarSha;
    private final String actorId;
    private final long cpu0;
    /** The game's CPU account (RlActorBench sets it on the game thread): the look-ahead's and Forge AI's other threads. */
    private final java.util.concurrent.atomic.AtomicLong[] gameAcc;
    private double poolCpuMs = 0, searchCpuMs = 0, scoreMs = 0;
    private int scoreCalls = 0, scoreFailures = 0, priorityAsks = 0, searchedAsks = 0, departures = 0,
            searchStackSkipped = 0, playoutFirstAmbiguous = 0;
    /** Lane sc-wallguard-1009: play-out service requests refused because their decision was past its budget. */
    private final java.util.concurrent.atomic.AtomicInteger budgetStops = new java.util.concurrent.atomic.AtomicInteger();
    /** Search-service connections, one per thread (the game thread and the look-ahead's pool threads). */
    private final ThreadLocal<RlSearchClient> client = new ThreadLocal<>();
    private final List<RlSearchClient> clients = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonObject> rows = new ArrayList<>();
    public String voided;

    // ---- search-bones-1015 (untouched while cfg.forcedWin and cfg.plans are null)
    /** The forced-win solver in front of each RL seat (the S-c + ForcedWin baseline), by player id. */
    private final Map<Integer, SolverDriver> solverDrivers = new TreeMap<>();
    /** S-b: the turn-plan search of each RL seat, by player id. */
    private final Map<Integer, PlanSearch> planSearches = new TreeMap<>();

    /**
     * Lane search-bones-1015: install the plan hooks on the RL seats' bridged controllers (the search seat, or every RL
     * seat): the forced-win solver in front ({@code forcedWin}) and S-b ({@code plans}). A no-op unless one of the two
     * keys is set, so every other spec's game is unchanged.
     */
    public void attach(final Game g, final boolean[] rlSeat, final RlSeat seat) {
        if (cfg.forcedWin == null && cfg.plans == null && cfg.cac == null) {
            return;
        }
        final List<Player> ps = g.getRegisteredPlayers();
        for (int s = 0; s < ps.size() && s < rlSeat.length; s++) {
            if (!rlSeat[s] || searchSeat >= 0 && s != searchSeat) {
                continue;
            }
            final Player p = ps.get(s);
            if (!(p.getController() instanceof forge.bench.PlayerControllerBridge)) {
                continue;
            }
            final forge.bench.PlayerControllerBridge b = (forge.bench.PlayerControllerBridge) p.getController();
            SolverDriver d = null;
            if (cfg.forcedWin != null) {
                d = new SolverDriver(p, SolverDriver.config(cfg.forcedWin));
                d.solver().redraw = ls.solverRedraw(); // honest-belief-1020 (null when belief is off); S2T: codraft
                solverDrivers.put(p.getId(), d);
                b.planHook = d;
            }
            if (cfg.plans != null) {
                final PlanSearch ps1 = new PlanSearch(PlanSearch.Spec.parse(cfg.plans), this, p, seat, d);
                planSearches.put(p.getId(), ps1);
                b.planHook = ps1;
            }
            if (cfg.cac != null) {
                // r2-choose-a-card-1029: the seat's assembly choices, valued by this search's leaf in one LEAVES batch
                final forge.ai.simulation.ChooseACard c = new forge.ai.simulation.ChooseACard(
                        forge.ai.simulation.ChooseACard.Config.fromJson(cfg.cac), forge.ai.simulation.ChooseACard.of(this), null);
                c.setRedraw(ls.solverRedraw());
                c.install(b);
                chooseACards.put(p.getId(), c);
            }
        }
    }

    /** r2-choose-a-card-1029: the seats' choose-a-card engines (their logs), by player id; empty unless the key is set. */
    public final Map<Integer, forge.ai.simulation.ChooseACard> chooseACards = new TreeMap<>();

    // ---- S-t (lane cm-choice-search-1009; untouched while cfg.choices == 0)
    /** One macro candidate's forced answers, and the counts of its schedule across its play-outs. */
    static final class MacroSpec {
        final List<ChoiceWindow.Entry> entries;
        final ChoiceWindow.Counters playout = new ChoiceWindow.Counters();
        final int baseGiven;
        final double jointPrior;
        final String label;
        /** r3-distill-1010: the probe policy's own answer's identity at this ask (loose key), or null. */
        final String ownLoose;

        MacroSpec(final List<ChoiceWindow.Entry> entries, final int baseGiven, final double jointPrior, final String label) {
            this(entries, baseGiven, jointPrior, label, null);
        }

        MacroSpec(final List<ChoiceWindow.Entry> entries, final int baseGiven, final double jointPrior, final String label,
                final String ownLoose) {
            this.entries = entries;
            this.baseGiven = baseGiven;
            this.jointPrior = jointPrior;
            this.label = label;
            this.ownLoose = ownLoose;
        }
    }

    private final java.util.Set<Integer> choiceFams;
    /** The current decision's probe records (searched candidate index -> its world-0 play-out's asks). */
    private final Map<Integer, List<ChoiceWindow.Ask>> probeAsks = new java.util.concurrent.ConcurrentHashMap<>();
    /** The current decision's probe windows (diagnostics: what each probe saw). */
    private final List<ChoiceWindow> probeWindows = Collections.synchronizedList(new ArrayList<>());
    /** Over the game: every ask the probes saw, by "FAMILY:what happened". */
    private final Map<String, Integer> probeSeen = new TreeMap<>();
    /** The current decision's prior per given candidate (the pooled entries, given order). */
    private double[] givenPrior = new double[0];
    /** The current decision's macros (expander order). */
    private List<MacroSpec> macroList = new ArrayList<>();
    /** A macro departure's window, taken by the live seat right after {@link #decide}. */
    private ChoiceWindow pendingWindow = null;
    /** The live seat's schedule counts over the game, and the play-outs' (every macro's, summed at the end). */
    private final ChoiceWindow.Counters liveCounters = new ChoiceWindow.Counters();
    private final ChoiceWindow.Counters playoutCounters = new ChoiceWindow.Counters();
    private int probeAsksTotal = 0, contestedAsks = 0, macroDecisions = 0, macrosSearched = 0, macroDepartures = 0,
            macroDefaultDepartures = 0;

    // ---- the label sink (lane r3-distill-1010; untouched while cfg.labelSink == null)
    /** The look-ahead's seat (the GAME's search_seat), or -1 = every RL seat. RlActorBench sets it. */
    public int searchSeat = -1;
    private final long gameSeed;
    /** The checkpoint the service serves (its HELLO_ACK policy_sha), for the label records. */
    private volatile String servedPolicySha;
    /** This game's label records (encoded), written at game end. */
    private final List<byte[]> labelRecs = new ArrayList<>();
    /** The live macro departure whose schedule the seat answers (set by decide, read by scheduledAsk). */
    private LiveMacro liveMacro = null;
    private int labelRoots = 0, labelChoices = 0, labelFailures = 0;

    /** A macro departure's context for its scheduled live asks' choice records. */
    static final class LiveMacro {
        final int rootDecIdx;
        final int macro;
        final List<MacroSpec> macros;

        LiveMacro(final int rootDecIdx, final int macro, final List<MacroSpec> macros) {
            this.rootDecIdx = rootDecIdx;
            this.macro = macro;
            this.macros = macros;
        }
    }

    /** The sink record magic (format mtgx-rl-labelsink/1). */
    static final byte[] LABEL_MAGIC = {'M', 'X', 'L', '1'};

    /**
     * One game's search for its RL seat. {@code gameSeed} fixes the world draws (decisions are a function of the
     * position, the seed and the service's answers); {@code liveKnow} is the game's seat-knowledge tracker.
     */
    public RlSearch(final Config cfg, final long gameSeed, final long uid, final CardIndex index, final RlKnowledge liveKnow,
            final String jarSha, final String actorId) {
        this(cfg, gameSeed, uid, index, liveKnow, jarSha, actorId, 1);
    }

    /**
     * As above for a seat of observation schema {@code obsVersion} (1 or 2; lane search-v2-1009). A spec that pins
     * another schema ({@link Config#obs}) is refused.
     */
    public RlSearch(final Config cfg, final long gameSeed, final long uid, final CardIndex index, final RlKnowledge liveKnow,
            final String jarSha, final String actorId, final int obsVersion) {
        if (obsVersion != 1 && obsVersion != 2) {
            throw new IllegalArgumentException("search: observation schema " + obsVersion);
        }
        if (cfg.obs != 0 && cfg.obs != obsVersion) {
            throw new IllegalArgumentException("search.obs pins obs-v" + cfg.obs + ", the seat is obs-v" + obsVersion);
        }
        this.obsVersion = obsVersion;
        this.cfg = cfg;
        this.uid = uid;
        this.gameSeed = gameSeed;
        this.index = index;
        this.liveKnow = liveKnow;
        this.jarSha = jarSha;
        this.actorId = actorId;
        final LookaheadSearch.Config lc = new LookaheadSearch.Config();
        lc.worlds = cfg.worlds;
        lc.breadth = cfg.breadth;
        lc.horizonTurns = cfg.horizon;
        lc.maxSteps = cfg.maxSteps;
        lc.leafExtraSteps = cfg.leafExtraSteps;
        lc.seed = splitmix(gameSeed ^ splitmix(cfg.seedSalt));
        lc.threads = cfg.threads;
        lc.departZ = cfg.departZ;
        lc.margin = cfg.margin;
        lc.deadEtb = AiFixes.Mode.parse(cfg.deadEtb);
        lc.zeroX = AiFixes.Mode.parse(cfg.zeroX);
        lc.crewNoop = AiFixes.Mode.parse(cfg.crewNoop);
        lc.departMedian = AiFixes.Mode.parse(cfg.departMedian);
        lc.stack = cfg.stack;
        lc.budgetMs = cfg.budgetMs;
        lc.maxSameDepartures = cfg.sameDepartures;
        lc.copyFidelity = cfg.copyFidelity;
        if (!"off".equals(cfg.belief)) {
            // honest-belief-1020: the observation-only pool for every world (and, below, the solver's copies)
            lc.belief = cfg.belief;
            lc.beliefCube = cfg.beliefCube;
            lc.beliefCubeSha256 = cfg.beliefCubeSha256;
            lc.beliefBasics = cfg.beliefBasics;
            lc.beliefSingleton = cfg.beliefSingleton;
            lc.beliefPool = cfg.beliefPool;
            lc.beliefSeen = cfg.beliefSeen; // deck-guess-1021
            if ("codraft".equals(cfg.belief)) {
                // bvw-step2-fork-1027 (S2T): the co-draft deck prior, its table pinned by sha256
                lc.beliefPriorTable = PlanSearch.loadSynPinned(cfg.beliefPrior, cfg.beliefPriorSha256);
                lc.beliefPriorGain = cfg.beliefPriorGain;
            }
        }
        // bvw-step2-fork-1027 (S2T): the opponent's play-out seat and the symmetric leaf
        lc.opponentPolicy = "policy".equals(cfg.opponent);
        lc.leafSym = cfg.leafSym;
        lc.leafOpp = cfg.leafSym;
        lc.replayMayPlayLoose = cfg.replayMayPlayLoose; // step3-playout-fixes-1028
        if (cfg.plans != null && PlanSearch.Spec.parse(cfg.plans).opp) {
            // search-bones-1015 (d): S-b's respond points are stack decisions (copies carry the stack)
            lc.stack = true;
        }
        this.ls = new LookaheadSearch(lc);
        ls.setHooks(this, "value".equals(cfg.leaf), "policy".equals(cfg.playout));
        this.choiceFams = cfg.choices > 0 ? (cfg.choiceFamilySet != null ? cfg.choiceFamilySet
                : ChoiceWindow.parseFamilies(cfg.choiceFamilies)) : null;
        this.cpu0 = TMX.getCurrentThreadCpuTime();
        this.gameAcc = forge.ai.CpuAccount.get();
    }

    /** search-bones-1015: the look-ahead (S-b's recordings and play-outs). */
    LookaheadSearch ls() {
        return ls;
    }

    /** search-bones-1015: the S-b solver's follower config (copies follow lines with it). */
    forge.ai.simulation.ForcedWinSolver.Config followConfig() {
        for (PlanSearch ps1 : planSearches.values()) {
            return ps1.followCfg;
        }
        return PlanSearch.Spec.parse(cfg.plans).solverConfig();
    }

    /** search-bones-1015: the decision budget, ms (0 = none). */
    long budgetMs() {
        return cfg.budgetMs;
    }

    static long splitmix(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------------------------------------ service

    private RlSearchClient client() throws IOException {
        RlSearchClient c = client.get();
        if (c == null) {
            c = new RlSearchClient(cfg.server, cfg.connectTimeoutMs, cfg.readTimeoutMs);
            final JsonObject h = new JsonObject();
            h.addProperty("proto", RlSearchClient.PROTO);
            h.addProperty("schema_sha", schemaSha(obsVersion));
            h.addProperty("card_index_sha", index.sha());
            h.addProperty("jar_sha", jarSha);
            h.addProperty("actor_id", actorId);
            h.addProperty("thread", Thread.currentThread().getName());
            h.addProperty("pid", ProcessHandle.current().pid());
            h.addProperty("mode", "search");
            try {
                c.hello(h);
                checkObs(c.ack, obsVersion);
            } catch (IOException e) {
                c.close();
                throw e;
            }
            if (c.ack != null && c.ack.has("policy_sha") && !c.ack.get("policy_sha").isJsonNull()) {
                servedPolicySha = c.ack.get("policy_sha").getAsString();
            }
            if (c.ack != null) {
                servedAck = c.ack.deepCopy(); // bvw-step1-build-1024: the service's identity (value backend) for label rows
            }
            if (cfg.policySha != null) {
                final String got = c.ack.has("policy_sha") ? c.ack.get("policy_sha").getAsString() : null;
                if (!cfg.policySha.equals(got)) {
                    c.close();
                    throw new RlClient.ServerError("policy_sha", "search service serves " + got + ", the spec pins "
                            + cfg.policySha);
                }
            }
            client.set(c);
            clients.add(c);
        }
        return c;
    }

    /**
     * Drop this thread's connection after a failed call (cm-choice-search-1009): the service closes a connection on any
     * error it answers, so the next call on this thread connects again instead of failing on a dead socket. Nothing
     * changes while every call succeeds.
     */
    private void drop() {
        final RlSearchClient c = client.get();
        if (c != null) {
            client.remove();
            clients.remove(c);
            c.close();
            synchronized (this) {
                reconnects++;
            }
        }
    }

    private int reconnects = 0;

    /** The HELLO's {@code schema_sha} for observation schema {@code v} (search-v2-1009; obs-v1: the S1 read's). */
    public static String schemaSha(final int v) {
        return v == 2 ? RlSchemaV2.schemaSha() : RlSchema.schemaSha();
    }

    /**
     * The service's observation schema against the seat's (search-v2-1009): a HELLO_ACK that names another schema
     * ({@code obs_schema}) is refused; under obs-v2 the ACK must name it (a service that does not say which schema it
     * decodes with is not trusted with v2 frames). An obs-v1 seat accepts an ACK without the key (the S1 read's service).
     */
    public static void checkObs(final JsonObject ack, final int v) throws RlClient.ServerError {
        final Integer got = ack != null && ack.has("obs_schema") && !ack.get("obs_schema").isJsonNull()
                ? ack.get("obs_schema").getAsInt() : null;
        if (got == null ? v != 1 : got != v) {
            throw new RlClient.ServerError("obs_schema", "the service decodes obs-v" + (got == null ? "? (not stated)" : got)
                    + ", the seat speaks obs-v" + v);
        }
    }

    public int obsVersion() {
        return obsVersion;
    }

    /** Close every connection and the look-ahead's pool (call at game end). */
    public void close() {
        ls.shutdown();
        synchronized (clients) {
            for (RlSearchClient c : clients) {
                c.close();
            }
            clients.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------ the decision

    /** S1: one action's identity across identical copies (card name, zone, land flag, ability text); pass = "pass". */
    static String actionKey(final SpellAbility sa) {
        if (sa == null) {
            return "pass";
        }
        final forge.game.card.Card h = sa.getHostCard();
        final String zone = h == null || h.getZone() == null ? "?" : String.valueOf(h.getZone().getZoneType());
        return (h == null ? "?" : h.getName()) + "|" + zone + "|" + (sa.isLandAbility() ? "L" : "S") + "|"
                + sa.getDescription();
    }

    /** The bridge menu entry (1-based; 0 = pass) and the announced X (-1 = none) of a PRIORITY candidate. */
    static int choiceOf(final RlCandidates.Menu m, final int i) {
        final JsonElement f = m.cands.get(i).frag;
        return f != null && f.isJsonObject() && f.getAsJsonObject().has("choice") ? f.getAsJsonObject().get("choice").getAsInt()
                : -1;
    }

    // ---- J1 (lane block-search-1019; both null by default, and then nothing below changes)
    /** The live choices to replay ({@link forge.ai.simulation.AiReplay}), or null. */
    public forge.ai.simulation.AiReplay replay;
    /** The index the next decision line will carry (RlLiveSeat's searchedLines), or null. */
    public java.util.function.IntSupplier nextLine;
    /** The last decision's replay flags (for its decision line; false without a replay). */
    public boolean lastForced, lastForcedMiss;

    /** J1: the live decision for the next decision line, or null. */
    private forge.ai.simulation.AiReplay.Mark mark() {
        final forge.ai.simulation.AiReplay r = replay;
        final java.util.function.IntSupplier nl = nextLine;
        return r == null || nl == null ? null : r.at(nl.getAsInt());
    }

    /** J1: before a given / block search, the label the live game played there (null = none, or a proven line). */
    private void armForce(final forge.ai.simulation.AiReplay.Mark mk) {
        if (mk == null) {
            return;
        }
        if ("proven".equals(mk.outcome) || mk.played == null) {
            // live took a proven solver line here (or the mark has no label): nothing among the candidates to force
            lastForcedMiss = true;
            return;
        }
        ls.forcePlayed = mk.played;
    }

    /** J1: the result's replay flags into the decision line's. */
    private void tookForce(final LookaheadSearch.GivenResult r) {
        lastForced |= r.forced;
        lastForcedMiss |= r.forcedMiss;
        ls.forcePlayed = null;
    }

    @Override
    public int decide(final Game g, final Player me, final RlCandidates.Menu m, final RlWire.Decide frame, final int greedy,
            final List<SpellAbility> menuObjs) {
        pendingWindow = null;
        liveMacro = null;
        lastForced = false;
        lastForcedMiss = false;
        priorityAsks++;
        if (cfg.cpuCapMs > 0 && gameCpuMs() > cfg.cpuCapMs) {
            voided = "cpu_cap";
            tap("cpu_cap", null, null, 0, VOID);
            return VOID;
        }
        final PlanSearch sb = planSearches.isEmpty() ? null : planSearches.get(me.getId());
        // search-bones-1015 (d): S-b searches a respond point (their spell on top, a counter in our menu)
        final boolean respond = sb != null && !g.getStack().isEmpty() && sb.respondPoint(g, menuObjs);
        if (!cfg.stack && !g.getStack().isEmpty() && !respond) {
            searchStackSkipped++;
            tap("stack", null, null, 0, -1);
            return -1;
        }
        if (sb != null) {
            // search-bones-1015 (S-b): the live policy plan's next step, no search
            final int f = sb.follow(g, me, m, menuObjs);
            if (f >= 0) {
                tap("follow", null, null, 0, f);
                return f;
            }
        }
        final long t0 = System.nanoTime();
        final RlSearchClient.Scores sc;
        final byte[] payload = RlWire.encodeDecide(frame);
        try {
            sc = client().score(payload);
        } catch (IOException | RuntimeException e) {
            drop();
            scoreFailures++;
            System.err.println("[rlsearch] SCORE failed, keeping the policy's choice: " + e);
            tap("score_failed", null, null, 0, -1);
            return -1;
        }
        scoreCalls++;
        scoreMs += (System.nanoTime() - t0) / 1e6;
        final int c = m.C();
        if (sc.status != RlWire.ST_OK || sc.probs.length != c) {
            scoreFailures++;
            tap("score_status", null, null, 0, -1);
            return -1;
        }
        // Pool the candidates into entries: X variants of one menu entry, and menu entries that are the same action
        // (the same card name, zone, land flag and ability text: three Islands in hand are one land play). An entry's
        // prior is its candidates' summed probability; its menu choice and candidate are its most likely candidate's,
        // except the default entry's, which are the policy's own (greedy) choice.
        final int def0 = choiceOf(m, greedy);
        final Map<String, Integer> entryOf = new java.util.HashMap<>();
        final Map<Integer, double[]> by = new TreeMap<>();   // entry choice -> {prior, candidate, best p}
        for (int i = 0; i < c; i++) {
            if (m.cands.get(i).kind <= 0) {
                continue;
            }
            final int ch = choiceOf(m, i);
            if (ch < 0 || ch > menuObjs.size()) {
                continue;
            }
            final String key = actionKey(ch == 0 ? null : menuObjs.get(ch - 1));
            Integer e = entryOf.get(key);
            if (e == null) {
                e = ch;
                entryOf.put(key, e);
            }
            final double[] a = by.computeIfAbsent(e, k -> new double[] {0.0, -1, -1.0});
            a[0] += sc.probs[i];
            if (sc.probs[i] > a[2]) {
                a[1] = i;
                a[2] = sc.probs[i];
            }
        }
        final Integer defEntry = entryOf.get(actionKey(def0 == 0 ? null : menuObjs.get(def0 - 1)));
        if (defEntry == null || !by.containsKey(defEntry)) {
            tap("no_default", null, null, 0, -1);
            return -1;
        }
        if (defEntry != def0) {
            // the default entry stands for the policy's own choice
            final double[] a = by.remove(defEntry);
            a[1] = greedy;
            by.put(def0, a);
            entryOf.replaceAll((k, v) -> v.equals(defEntry) ? def0 : v);
        } else {
            by.get(def0)[1] = greedy;
        }
        final int def = def0;
        final List<Integer> order = new ArrayList<>();
        order.add(def);
        final List<Integer> rest = new ArrayList<>(by.keySet());
        rest.remove(Integer.valueOf(def));
        rest.sort((x, y) -> {
            final int k = Double.compare(by.get(y)[0], by.get(x)[0]);
            return k != 0 ? k : Integer.compare(x, y);
        });
        if (respond) {
            // (d): the counters right after the default, so the breadth holds them
            final List<Integer> counters = new ArrayList<>();
            for (int ch : rest) {
                if (ch > 0 && PlanSearch.isCounter(menuObjs.get(ch - 1))) {
                    counters.add(ch);
                }
            }
            rest.removeAll(counters);
            rest.addAll(0, counters);
        }
        if (cfg.includePass && def != 0 && by.containsKey(0)) {
            // K8's candidate shape: the default, then pass, then the prior's next entries
            order.remove(Integer.valueOf(0));
            rest.remove(Integer.valueOf(0));
            order.add(0);
        }
        order.addAll(rest);
        final List<SpellAbility> given = new ArrayList<>(order.size());
        for (int ch : order) {
            given.add(ch == 0 ? null : menuObjs.get(ch - 1));
        }
        final LookaheadSearch.GivenResult r;
        PlanSearch.Stage stage = null;
        if (sb != null && sb.planPoint(g)) {
            // search-bones-1015 (S-b): a plan point: the proposals, then S-c's worlds over the given candidates and plans
            stage = sb.propose(g, me, m, menuObjs, t0);
            if (stage.hook) {
                // J1: a proven line taken at once; live must have taken one here too
                final forge.ai.simulation.AiReplay.Mark mk = mark();
                if (mk != null && !"proven".equals(mk.outcome)) {
                    lastForcedMiss = true;
                }
                planRow(g, frame, stage, null, order, -1, t0);
                planDecided(g, stage, null, t0);
                tap("proven_line", stage, null, order.size(), RlSeat.PrioritySearch.HOOK);
                return RlSeat.PrioritySearch.HOOK;
            }
        }
        armForce(mark());
        if (sb != null) {
            // S-b: every searched decision has the proof flags; a plan point also has its plans (and its budget counts
            // from the decision's start, the proposals included)
            final int kt = stage != null ? stage.knownTop : sb.knownTop(g);
            LookaheadSearch.ChoiceExpander ex = null;
            if (cfg.choices > 0) {
                // S-b with S-t's choice expansion of the given candidates (plans are not expanded)
                probeAsks.clear();
                probeWindows.clear();
                macroList = new ArrayList<>();
                restartMark = new int[] {probeAsksTotal, contestedAsks};
                givenPrior = new double[order.size()];
                for (int i = 0; i < order.size(); i++) {
                    givenPrior[i] = by.get(order.get(i))[0];
                }
                expandLimit = order.size();
                ex = this;
            }
            r = stage != null ? ls.decideGivenPlans(g, me, given, cfg.breadth, stage.plans, stage.labels, kt, t0, sb.threat, ex)
                    : ls.decideGivenPlans(g, me, given, cfg.breadth, null, null, kt, 0L, sb.threat, ex);
        } else if (cfg.choices > 0) {
            // S-t: the given candidates' priors (the expander's joint prior), then the search with the expander
            probeAsks.clear();
            probeWindows.clear();
            macroList = new ArrayList<>();
            restartMark = new int[] {probeAsksTotal, contestedAsks};
            givenPrior = new double[order.size()];
            for (int i = 0; i < order.size(); i++) {
                givenPrior[i] = by.get(order.get(i))[0];
            }
            r = ls.decideGiven(g, me, given, cfg.breadth, this);
        } else {
            r = ls.decideGiven(g, me, given, cfg.breadth);
        }
        tookForce(r);
        poolCpuMs += r.poolCpuMs;
        int answer = -1;
        if (r.givenIndex.length > 0) {
            searchedAsks++;
            searchCpuMs += r.cpuMs;
        }
        if (stage != null) {
            final int pa = sb.apply(stage, r, order.size(), m, menuObjs, g, (System.nanoTime() - t0) / 1e6);
            planRow(g, frame, stage, r, order, pa, t0);
            if (pa != -2) {
                departures++;
                planDecided(g, stage, r, t0);
                tap("plan", stage, r, order.size(), pa);
                return pa;
            }
        }
        if (r.chosen > 0 && r.chosen < order.size()) {
            answer = (int) by.get(order.get(r.chosen))[1];
            departures++;
        }
        if (r.chosenMacro >= 0) {
            // S-t: a macro departure: its base action (the default's when r.chosen == 0), then its schedule
            final MacroSpec ms = macroList.get(r.chosenMacro);
            pendingWindow = ChoiceWindow.scheduled(ms.entries, liveCounters);
            macroDepartures++;
            if (cfg.labelSink != null) {
                liveMacro = new LiveMacro(frame.decIdx, r.chosenMacro, new ArrayList<>(macroList));
            }
            if (r.chosen == 0) {
                macroDefaultDepartures++;
                departures++;
            }
        }
        if (cfg.choices > 0 && r.macro.length > 0) {
            macroDecisions++;
            macrosSearched += macroList.size();
            for (MacroSpec ms : macroList) {
                ms.playout.addTo(playoutCounters);
            }
        }
        final Map<String, Integer> seenNow = new TreeMap<>();
        if (cfg.choices > 0) {
            synchronized (probeWindows) {
                for (ChoiceWindow w : probeWindows) {
                    for (Map.Entry<String, Integer> e : w.seen().entrySet()) {
                        seenNow.merge(e.getKey(), e.getValue(), Integer::sum);
                        probeSeen.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
        }
        final Searched hook = onSearched;
        lastProposeMs = stage != null ? stage.ms : -1; // sb-live-1018: S-b only (stage is null without plans)
        if (hook != null && r.givenIndex.length > 0) {
            try {
                hook.searched(g, r, by.size(), (System.nanoTime() - t0) / 1e6 - r.ms);
            } catch (RuntimeException e) {
                System.err.println("[rlsearch] onSearched failed: " + e);
            }
        }
        if (cfg.labelSink != null && r.givenIndex.length > 0) {
            try {
                labelRecs.add(labelRecord(rootHeader(g, m, frame, sc, greedy, menuObjs, entryOf, order, def, r, answer),
                        payload));
                labelRoots++;
            } catch (RuntimeException e) {
                labelFailures++;
                System.err.println("[rlsearch] label root record failed: " + e);
            }
        }
        if (cfg.decisionLog != null || rowsWanted) {
            final JsonObject row = new JsonObject();
            row.addProperty("game_uid", Long.toUnsignedString(uid));
            row.addProperty("dec_idx", frame.decIdx);
            row.addProperty("turn", g.getPhaseHandler().getTurn());
            row.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
            row.addProperty("C", c);
            row.addProperty("entries", by.size());
            row.addProperty("v_root", sc.valueObs);
            final JsonArray pr = new JsonArray();
            final JsonArray ix = new JsonArray();
            final JsonArray ev = new JsonArray();
            for (int j = 0; j < r.givenIndex.length; j++) {
                if (r.givenIndex[j] >= order.size()) {
                    // search-bones-1015: a plan candidate (no menu entry of its own)
                    pr.add(0.0);
                    ix.add(-1 - (r.givenIndex[j] - order.size()));
                    ev.add(Double.isNaN(r.ev[j]) ? null : r.ev[j]);
                    continue;
                }
                final int ch = order.get(r.givenIndex[j]);
                pr.add(Math.round(by.get(ch)[0] * 1e5) / 1e5);
                ix.add(ch);
                ev.add(Double.isNaN(r.ev[j]) ? null : r.ev[j]);
            }
            row.add("prior", pr);
            row.add("entry", ix);
            row.add("ev", ev);
            // per-world values (diagnostics: paired differences, sign consistency across worlds)
            final JsonArray vw = new JsonArray();
            final double scale = "value".equals(cfg.leaf) ? 1e4 : 10;
            for (int j = 0; j < r.values.length; j++) {
                final JsonArray w = new JsonArray();
                for (double x : r.values[j]) {
                    if (Double.isInfinite(x) || Double.isNaN(x)) {
                        w.add((Number) null);
                    } else {
                        w.add(Math.round(x * scale) / scale);
                    }
                }
                vw.add(w);
            }
            row.add("values", vw);
            if (cfg.choices > 0) {
                // S-t: per searched candidate, its macro (-1 = a base candidate), and every macro's schedule
                final JsonArray mi = new JsonArray();
                for (int j = 0; j < r.givenIndex.length; j++) {
                    mi.add(j < r.macro.length ? r.macro[j] : -1);
                }
                row.add("macro", mi);
                final JsonArray md = new JsonArray();
                for (MacroSpec ms : macroList) {
                    final JsonObject x = new JsonObject();
                    x.addProperty("base", order.get(ms.baseGiven));
                    x.addProperty("joint_prior", Math.round(ms.jointPrior * 1e5) / 1e5);
                    final JsonArray es = new JsonArray();
                    for (ChoiceWindow.Entry e : ms.entries) {
                        es.add(e.label());
                    }
                    x.add("schedule", es);
                    x.addProperty("playout_hits", ms.playout.hits());
                    x.addProperty("playout_miss", ms.playout.miss.get());
                    x.addProperty("playout_unused", ms.playout.unused.get());
                    md.add(x);
                }
                row.add("macros", md);
                row.addProperty("chosen_macro", r.chosenMacro);
                int asks = 0;
                final JsonArray pa = new JsonArray();
                for (Map.Entry<Integer, List<ChoiceWindow.Ask>> e : new TreeMap<>(probeAsks).entrySet()) {
                    synchronized (e.getValue()) {
                        for (ChoiceWindow.Ask a : e.getValue()) {
                            asks++;
                            // [searched candidate, ask, ordinal, C, the largest prior, the policy's own answer's prior]
                            final JsonArray x = new JsonArray();
                            x.add(e.getKey());
                            x.add(a.ask);
                            x.add(a.ordinal);
                            x.add(a.C);
                            x.add(Math.round(a.maxPrior() * 1e4) / 1e4);
                            x.add(a.greedy >= 0 && a.greedy < a.C ? Math.round(a.prior[a.greedy] * 1e4) / 1e4 : -1.0);
                            pa.add(x);
                        }
                    }
                }
                row.addProperty("probe_asks", asks);
                row.add("probe", pa);
                final JsonObject ps = new JsonObject();
                for (Map.Entry<String, Integer> e : seenNow.entrySet()) {
                    ps.addProperty(e.getKey(), e.getValue());
                }
                row.add("probe_seen", ps);
            }
            row.addProperty("p_default", Math.round(by.get(def)[0] * 1e5) / 1e5);
            final SolverDriver sd = solverDrivers.get(me.getId());
            if (sd != null) {
                // search-bones-1015: the solver's time at this priority, before the search (it had no opinion)
                row.addProperty("fw_ms", Math.round(sd.lastMs * 10) / 10.0);
            }
            row.addProperty("outcome", r.outcome);
            row.addProperty("chosen", r.chosen <= 0 ? 0 : r.chosen < order.size() ? order.get(r.chosen) : -1);
            row.addProperty("failed", r.failed);
            row.addProperty("leaf_fallback", r.leafFallback);
            if (r.partialWorlds > 0 || r.proven) {
                // search-bones-1015 (S-b only)
                row.addProperty("partial_worlds", r.partialWorlds);
                row.addProperty("proven", r.proven);
            }
            row.addProperty("ms", Math.round(r.ms * 10) / 10.0);
            row.addProperty("cpu_ms", Math.round(r.cpuMs * 10) / 10.0);
            row.addProperty("score_ms", Math.round((System.nanoTime() - t0) / 1e5 - r.ms * 10) / 10.0);
            rows.add(row);
        }
        tap(r.givenIndex.length > 0 ? "searched" : String.valueOf(r.outcome), stage, r, order.size(), answer);
        return answer;
    }

    /**
     * Lane bvw-step1-build-1024 (null = off, the default, and then nothing changes): told how each priority decision
     * ended, for the step-1 labeller ({@link StepSearch}): why ({@code searched}, {@code plan}, {@code proven_line}, or
     * why there was no search), the S-b stage (null without plans or off a plan point), the look-ahead's result (null
     * when none ran), the given candidates' count and the answer.
     */
    public interface Tap {
        void decided(String why, PlanSearch.Stage stage, LookaheadSearch.GivenResult r, int given, int answer);
    }

    public Tap tap;

    private void tap(final String why, final PlanSearch.Stage stage, final LookaheadSearch.GivenResult r, final int given,
            final int answer) {
        final Tap t = tap;
        if (t != null) {
            t.decided(why, stage, r, given, answer);
        }
    }

    /** bvw-step1-build-1024: this seat's S-b plan search (null without plans), for the step-1 labeller. */
    PlanSearch planSearchOf(final Player p) {
        return planSearches.get(p.getId());
    }

    /** bvw-step1-build-1024: the policy's greedy DECISION for a frame, on this thread's search-service connection. */
    RlWire.Decision policyDecide(final byte[] payload) throws IOException {
        try {
            return client().decide(payload, false);
        } catch (IOException | RuntimeException e) {
            drop();
            throw e;
        }
    }

    /**
     * bvw-step1-build-1024 (review #9): open this thread's connection (HELLO; the policySha pin is checked there) and
     * return the service's HELLO_ACK; throws when there is no service or a pin fails.
     */
    JsonObject connectAck() throws IOException {
        final RlSearchClient c = client();
        return c.ack == null ? null : c.ack.deepCopy();
    }

    /**
     * bvw-step1-build-1024 (ruling 4): the last search's candidate {@code cand} (its index among the searched candidates)
     * played out again in K fresh worlds seeded by {@code salt}, valued by the same leaves: its per-world values, or null.
     */
    double[] reevaluate(final Game g, final Player me, final int cand, final long salt, final int knownTop) {
        return ls.reevaluate(g, me, cand, salt, knownTop);
    }

    /** bvw-step1-build-1024: the served checkpoint (HELLO_ACK policy_sha) once a connection is open, or null. */
    String servedPolicySha() {
        return servedPolicySha;
    }

    private volatile JsonObject servedAck;

    /** bvw-step1-build-1024: the service's HELLO_ACK once a connection is open (a copy), or null. */
    JsonObject servedAck() {
        return servedAck;
    }

    /** search-bones-1015: one JSON row per S-b plan point (decision log / rows, as the S-c rows). */
    private void planRow(final Game g, final RlWire.Decide frame, final PlanSearch.Stage st,
            final LookaheadSearch.GivenResult r, final List<Integer> order, final int answer, final long t0) {
        if (cfg.decisionLog == null && !rowsWanted) {
            return;
        }
        final JsonObject row = new JsonObject();
        row.addProperty("game_uid", Long.toUnsignedString(uid));
        row.addProperty("dec_idx", frame.decIdx);
        row.addProperty("turn", g.getPhaseHandler().getTurn());
        row.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        row.addProperty("kind", "plan");
        row.addProperty("propose_ms", Math.round(st.ms * 10) / 10.0);
        row.addProperty("note", st.note);
        final JsonArray labels = new JsonArray();
        for (String l : st.labels) {
            labels.add(l);
        }
        row.add("plans", labels);
        row.addProperty("known_top", st.knownTop);
        if (r != null) {
            row.addProperty("outcome", r.outcome);
            row.addProperty("proven", r.proven);
            row.addProperty("chosen_given", r.chosen);
            row.addProperty("given", order.size());
            final JsonArray ev = new JsonArray();
            final JsonArray pw = new JsonArray();
            final JsonArray pl = new JsonArray();
            final JsonArray br = new JsonArray();
            for (int j = 0; j < r.givenIndex.length; j++) {
                ev.add(Double.isNaN(r.ev[j]) ? null : Math.round(r.ev[j] * 1e4) / 1e4);
                pw.add(j < r.provenWin.length && r.provenWin[j]);
                pl.add(j < r.provenLoss.length && r.provenLoss[j]);
                br.add(j < r.planBroken.length ? r.planBroken[j] : 0);
            }
            row.add("ev", ev);
            row.add("proven_win", pw);
            row.add("proven_loss", pl);
            row.add("plan_broken", br);
            final JsonArray gi = new JsonArray();
            for (int x : r.givenIndex) {
                gi.add(x);
            }
            row.add("searched", gi);
            row.addProperty("failed", r.failed);
            row.addProperty("partial_worlds", r.partialWorlds);
            row.addProperty("search_ms", Math.round(r.ms * 10) / 10.0);
        }
        row.addProperty("answer", answer);
        row.addProperty("ms", Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
        rows.add(row);
    }

    // ------------------------------------------------------------------------------------------------ block search

    private int blockAsks = 0, blockSearched = 0, blockDepartures = 0, blockCapped = 0, blockForgeMissing = 0,
            blockSampleFailures = 0;

    /**
     * Lane block-search-1019 (spec key {@code blocks}; off = null at once, nothing else runs): the look-ahead over the
     * seat's block declarations at a declare-blockers ask, after the server's greedy DECISION ({@code greedy}). The
     * candidates, deduplicated, in this order: the policy's own declaration (the default), its next most frequent ones
     * among {@code blockSamples} sampled DECIDEs of the same frame (up to {@code blockTop} policy declarations in all),
     * no blocks, and Forge AI's own declaration ({@code AiBlockController}, in a copy). Forge's declaration and "no
     * blocks" are candidates like the others: they are played only when the search values them past the departZ gate.
     * The play-outs, worlds, value leaf, gate and wall budget are the priority search's (one budget for the whole
     * decision, sampling included). Returns the steps to play instead, or null to keep the policy's own.
     */
    @Override
    public short[] decideBlocks(final Game g, final Player me, final RlCandidates.Menu m, final RlWire.Decide frame,
            final short[] greedy, final Object objs) {
        if (cfg.blocks <= 0) {
            return null;
        }
        final long t0 = System.nanoTime();
        blockAsks++;
        lastProposeMs = -1;
        if (m.family != RlSchema.F_BLOCK || greedy == null || greedy.length != m.S() || m.S() == 0
                || m.validate(greedy) != null) {
            return null;
        }
        if (cfg.cpuCapMs > 0 && gameCpuMs() > cfg.cpuCapMs) {
            return null;
        }
        final List<short[]> cs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final java.util.Set<String> keys = new java.util.HashSet<>();
        cs.add(greedy);
        labels.add("policy");
        keys.add(java.util.Arrays.toString(greedy));
        // the policy's own distribution: distinct sampled declarations by count (ties: first drawn)
        final Map<String, int[]> count = new java.util.LinkedHashMap<>();
        final Map<String, short[]> stepsOf = new java.util.HashMap<>();
        if (cfg.blockSamples > 0 && cfg.blockTop > 1) {
            final long uid0 = frame.gameUid;
            try {
                for (int j = 0; j < cfg.blockSamples; j++) {
                    // the service seeds a sampled DECISION by (game uid, seat, dec_idx): one uid per sample
                    frame.gameUid = splitmix(uid0 ^ (0xb10c0000L + j));
                    final RlWire.Decision d = client().decide(RlWire.encodeDecide(frame), true);
                    if (d.status != RlWire.ST_OK || d.steps == null || m.validate(d.steps) != null) {
                        continue;
                    }
                    final String k = java.util.Arrays.toString(d.steps);
                    count.computeIfAbsent(k, x -> new int[1])[0]++;
                    stepsOf.putIfAbsent(k, d.steps);
                }
            } catch (IOException | RuntimeException e) {
                drop();
                blockSampleFailures++;
                System.err.println("[rlsearch] block samples failed, searching without them: " + e);
            } finally {
                frame.gameUid = uid0;
            }
            final List<String> byCount = new ArrayList<>(count.keySet());
            byCount.sort((x, y) -> Integer.compare(count.get(y)[0], count.get(x)[0]));
            for (String k : byCount) {
                if (cs.size() >= cfg.blockTop) {
                    break;
                }
                if (keys.add(k)) {
                    cs.add(stepsOf.get(k));
                    labels.add("sample" + count.get(k)[0]);
                }
            }
        }
        final short[] none = m.noBlockSteps();
        if (none != null && keys.add(java.util.Arrays.toString(none))) {
            cs.add(none);
            labels.add("none");
        }
        final Map<Integer, List<Integer>> fb = ls.forgeBlocks(g, me);
        final short[] forge = fb == null ? null : m.blockSteps(fb);
        if (forge == null) {
            blockForgeMissing++;
        } else if (keys.add(java.util.Arrays.toString(forge))) {
            cs.add(forge);
            labels.add("forge");
        }
        final List<Map<Integer, List<Integer>>> plans = new ArrayList<>(cs.size());
        // review S2: each candidate's identity (its label in the search, its explain row and the J1 replay key) is its
        // canonical declaration, never its source label: two distinct sampled declarations may share a count
        final List<String> decls = new ArrayList<>(cs.size());
        for (short[] st : cs) {
            final Map<Integer, List<Integer>> bm = m.blockMap(st);
            plans.add(bm);
            decls.add(declarationKey(bm));
        }
        final double prepMs = (System.nanoTime() - t0) / 1e6;
        lastForced = false;
        lastForcedMiss = false;
        armForce(mark());
        final LookaheadSearch.GivenResult r = ls.decideBlocks(g, me, plans, decls, t0);
        tookForce(r);
        if (r.givenIndex.length > 0) {
            blockSearched++;
            searchCpuMs += r.cpuMs;
        }
        poolCpuMs += r.poolCpuMs;
        if ("capped".equals(r.outcome)) {
            blockCapped++;
        }
        final short[] answer = r.chosen > 0 && r.chosen < cs.size() ? cs.get(r.chosen) : null;
        if (answer != null) {
            blockDepartures++;
        }
        final double ms = (System.nanoTime() - t0) / 1e6;
        final BlocksDecided hook = onBlocksDecided;
        if (hook != null) {
            try {
                hook.decided(g, r, cs.size(), labels, r.chosen > 0 ? labels.get(r.chosen) : "policy", ms, prepMs);
            } catch (RuntimeException e) {
                System.err.println("[rlsearch] onBlocksDecided failed: " + e);
            }
        }
        if (cfg.decisionLog != null || rowsWanted) {
            final JsonObject row = new JsonObject();
            row.addProperty("game_uid", Long.toUnsignedString(uid));
            row.addProperty("dec_idx", frame.decIdx);
            row.addProperty("turn", g.getPhaseHandler().getTurn());
            row.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
            row.addProperty("kind", "blocks");
            row.addProperty("C", m.C());
            row.addProperty("S", m.S());
            final JsonArray lb = new JsonArray();
            for (String l : labels) {
                lb.add(l);
            }
            row.add("cands", lb);
            final JsonArray dk = new JsonArray();
            for (String d : decls) {
                dk.add(d);
            }
            row.add("decls", dk);
            final JsonArray ix = new JsonArray();
            final JsonArray ev = new JsonArray();
            for (int j = 0; j < r.givenIndex.length; j++) {
                ix.add(r.givenIndex[j]);
                ev.add(Double.isNaN(r.ev[j]) ? null : r.ev[j]);
            }
            row.add("searched", ix);
            row.add("ev", ev);
            final JsonArray vw = new JsonArray();
            for (int j = 0; j < r.values.length; j++) {
                final JsonArray w = new JsonArray();
                for (double x : r.values[j]) {
                    if (Double.isInfinite(x) || Double.isNaN(x)) {
                        w.add((Number) null);
                    } else {
                        w.add(Math.round(x * 1e4) / 1e4);
                    }
                }
                vw.add(w);
            }
            row.add("values", vw);
            row.addProperty("outcome", r.outcome);
            row.addProperty("chosen", r.chosen <= 0 ? 0 : r.chosen);
            row.addProperty("failed", r.failed);
            row.addProperty("leaf_fallback", r.leafFallback);
            row.addProperty("prep_ms", Math.round(prepMs * 10) / 10.0);
            row.addProperty("ms", Math.round(ms * 10) / 10.0);
            row.addProperty("cpu_ms", Math.round(r.cpuMs * 10) / 10.0);
            rows.add(row);
        }
        return answer;
    }

    /**
     * Review S2: a block declaration's canonical identity: its attacker/blocker pairs ({@code a<attacker id>>b<blocker
     * id>}) sorted by attacker, then blocker, comma-separated; {@code none} for no blocks. Distinct declarations have
     * distinct keys; the same declaration always the same key.
     */
    static String declarationKey(final Map<Integer, List<Integer>> blocks) {
        final List<long[]> pairs = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : blocks.entrySet()) {
            for (Integer a : e.getValue()) {
                pairs.add(new long[] {a, e.getKey()});
            }
        }
        if (pairs.isEmpty()) {
            return "none";
        }
        pairs.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        final StringBuilder sb = new StringBuilder();
        for (long[] pr : pairs) {
            sb.append(sb.length() == 0 ? "" : ",").append('a').append(pr[0]).append(">b").append(pr[1]);
        }
        return sb.toString();
    }

    /**
     * Live play (block-search-1019; null by default, and never called without {@code blocks}): every block decision the
     * search made, searched or not. {@code ms} is the whole decision (samples, Forge's declaration, play-outs);
     * {@code prepMs} its part before the play-outs; {@code chosen} the label of the declaration played.
     */
    public interface BlocksDecided {
        void decided(Game g, LookaheadSearch.GivenResult r, int candidates, List<String> labels, String chosen, double ms,
                double prepMs);
    }

    public BlocksDecided onBlocksDecided;

    @Override
    public ChoiceWindow takeWindow() {
        final ChoiceWindow w = pendingWindow;
        pendingWindow = null;
        return w;
    }

    // ------------------------------------------------------------------------------------------------ the label sink

    /** One sink record: "MXL1", u32le header length, the header's canonical JSON, u32le payload length, the payload. */
    static byte[] labelRecord(final JsonObject header, final byte[] payload) {
        final byte[] h = RlWire.canonicalString(header).getBytes(StandardCharsets.UTF_8);
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + 4 + h.length + 4 + payload.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(LABEL_MAGIC).putInt(h.length).put(h).putInt(payload.length).put(payload);
        return b.array();
    }

    /** Append one game's records to {@code path} as ONE gzip member (a crash mid-write tears only that member). */
    static void appendGame(final String path, final List<byte[]> recs) throws IOException {
        final File f = new File(path);
        final File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(buf)) {
            for (byte[] r : recs) {
                z.write(r);
            }
        }
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(buf.toByteArray());
            out.getFD().sync();
        }
    }

    private static JsonArray probsJson(final float[] p) {
        final JsonArray a = new JsonArray();
        for (float x : p) {
            a.add((double) x);
        }
        return a;
    }

    private void provenance(final JsonObject h, final Game g, final RlWire.Decide frame, final long policyVersion) {
        h.addProperty("game_uid", Long.toUnsignedString(uid));
        h.addProperty("seed", gameSeed);
        h.addProperty("seat", frame.seat);
        h.addProperty("dec_idx", frame.decIdx);
        h.addProperty("turn", g.getPhaseHandler().getTurn());
        h.addProperty("obs", obsVersion);
        h.addProperty("policy_sha", servedPolicySha);
        h.addProperty("policy_version", policyVersion);
        h.addProperty("jar_sha", jarSha);
        h.addProperty("actor", actorId);
    }

    /** The ROOT record's header of one searched decision (schema mtgx-rl-labelsink/1; mtgx labels.py reads it). */
    private JsonObject rootHeader(final Game g, final RlCandidates.Menu m, final RlWire.Decide frame,
            final RlSearchClient.Scores sc, final int greedy, final List<SpellAbility> menuObjs,
            final Map<String, Integer> entryOf, final List<Integer> order, final int def,
            final LookaheadSearch.GivenResult r, final int answer) {
        final JsonObject h = new JsonObject();
        h.addProperty("kind", "root");
        provenance(h, g, frame, sc.policyVersion);
        h.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        h.addProperty("C", m.C());
        h.add("prior", probsJson(sc.probs));
        final JsonArray ce = new JsonArray();
        for (int i = 0; i < m.C(); i++) {
            final int ch = m.cands.get(i).kind <= 0 ? -1 : choiceOf(m, i);
            final Integer e = ch < 0 || ch > menuObjs.size() ? null
                    : entryOf.get(actionKey(ch == 0 ? null : menuObjs.get(ch - 1)));
            ce.add(e == null ? -1 : e);
        }
        h.add("cand_entry", ce);
        h.addProperty("default_cand", greedy);
        h.addProperty("default_entry", def);
        final JsonArray se = new JsonArray();
        for (int j = 0; j < r.givenIndex.length; j++) {
            final JsonObject x = new JsonObject();
            x.addProperty("entry", order.get(r.givenIndex[j]));
            x.addProperty("macro", cfg.choices > 0 && j < r.macro.length ? r.macro[j] : -1);
            if (j < r.ev.length && !Double.isNaN(r.ev[j]) && !Double.isInfinite(r.ev[j])) {
                x.addProperty("ev", r.ev[j]);
            } else {
                x.add("ev", com.google.gson.JsonNull.INSTANCE);
            }
            final JsonArray w = new JsonArray();
            if (j < r.values.length) {
                for (double v : r.values[j]) {
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        w.add(com.google.gson.JsonNull.INSTANCE);
                    } else {
                        w.add(v);
                    }
                }
            }
            x.add("values", w);
            se.add(x);
        }
        h.add("searched", se);
        final JsonArray ma = new JsonArray();
        if (cfg.choices > 0) {
            for (MacroSpec ms : macroList) {
                final ChoiceWindow.Entry e = ms.entries.get(0);
                final JsonObject x = new JsonObject();
                x.addProperty("base_entry", order.get(ms.baseGiven));
                x.addProperty("ask", e.ask);
                x.addProperty("ordinal", e.ordinal);
                x.addProperty("exact", e.exact);
                x.addProperty("loose", e.loose);
                x.addProperty("own_loose", ms.ownLoose);
                x.addProperty("joint_prior", ms.jointPrior);
                ma.add(x);
            }
        }
        h.add("macros", ma);
        h.addProperty("chosen_cand", answer >= 0 ? answer : greedy);
        h.addProperty("chosen_entry", r.chosen <= 0 ? def : order.get(r.chosen));
        h.addProperty("chosen_macro", r.chosenMacro);
        h.addProperty("departed", r.chosen > 0 || r.chosenMacro >= 0);
        h.addProperty("outcome", r.outcome);
        h.addProperty("failed", r.failed);
        h.addProperty("leaf_fallback", r.leafFallback);
        return h;
    }

    /**
     * The CHOICE record of a scheduled live ask (r3-distill-1010): called by the live seat after its window's schedule
     * answered ask {@code askKey}#{@code ordinal} with {@code scheduled} (the policy's own answer was {@code mine}). Each
     * candidate's {@code match}: m = macro m's answer at this ask (same ask, ordinal and base candidate as the departed
     * macro), -2 = the policy's own (live) answer's identity (its value is the base candidate's: the base's play-out
     * answered with the policy), -1 = unsearched. A candidate that is both a macro's answer and the policy's takes the
     * macro (its value is that answer's, forced).
     */
    @Override
    public void scheduledAsk(final Game g, final Player me, final RlCandidates.Menu m, final RlWire.Decide frame,
            final String askKey, final int ordinal, final int scheduled, final int mine) {
        final LiveMacro lm = liveMacro;
        if (cfg.labelSink == null || lm == null) {
            return;
        }
        final int mo = frame.mode, mi = frame.minPick, ma = frame.maxPick;
        final byte[] sp;
        try {
            frame.mode = RlSchema.M_SINGLE;
            frame.minPick = 1;
            frame.maxPick = 1;
            sp = RlWire.encodeDecide(frame);
        } finally {
            frame.mode = mo;
            frame.minPick = mi;
            frame.maxPick = ma;
        }
        try {
            final RlSearchClient.Scores ps;
            try {
                ps = client().score(sp);
            } catch (IOException | RuntimeException e) {
                drop();
                throw new IOException(e);
            }
            if (ps.status != RlWire.ST_OK || ps.probs.length != m.C()) {
                labelFailures++;
                return;
            }
            final MacroSpec m0 = lm.macros.get(lm.macro);
            // the policy's own live answer stands for the base candidate (its play-out answered with the policy)
            final String own = mine >= 0 && mine < m.C() && m.cands.get(mine).kind > 0 ? ChoiceWindow.looseKey(m, mine, me)
                    : null;
            final JsonArray match = new JsonArray();
            for (int i = 0; i < m.C(); i++) {
                if (m.cands.get(i).kind <= 0) {
                    match.add(-1);
                    continue;
                }
                final String l = ChoiceWindow.looseKey(m, i, me);
                int k = -1;
                for (int q = 0; q < lm.macros.size(); q++) {
                    final MacroSpec ms = lm.macros.get(q);
                    final ChoiceWindow.Entry e = ms.entries.get(0);
                    if (ms.baseGiven == m0.baseGiven && e.ordinal == ordinal && e.ask.equals(askKey) && e.loose.equals(l)
                            && (k < 0 || q == lm.macro)) {
                        k = q;
                    }
                }
                if (k < 0 && own != null && own.equals(l)) {
                    k = -2;
                }
                match.add(k);
            }
            final JsonObject h = new JsonObject();
            h.addProperty("kind", "choice");
            provenance(h, g, frame, ps.policyVersion);
            h.addProperty("family", frame.family);
            h.addProperty("ask", askKey);
            h.addProperty("ordinal", ordinal);
            h.addProperty("root_dec_idx", lm.rootDecIdx);
            h.addProperty("macro", lm.macro);
            h.addProperty("C", m.C());
            h.add("prior", probsJson(ps.probs));
            h.addProperty("policy_answer", mine);
            h.addProperty("scheduled", scheduled);
            h.add("match", match);
            labelRecs.add(labelRecord(h, sp));
            labelChoices++;
        } catch (IOException | RuntimeException e) {
            labelFailures++;
            System.err.println("[rlsearch] label choice record failed: " + e);
        }
    }

    /** Copy fidelity 2: the expander counters at this decision's start, restored when it is searched again. */
    private int[] restartMark = new int[] {0, 0};

    /** Copy fidelity 2 (copy-fidelity-1010): the decision is searched again; forget its probes and macros. */
    @Override
    public void restart() {
        probeAsks.clear();
        probeWindows.clear();
        macroList = new ArrayList<>();
        probeAsksTotal = restartMark[0];
        contestedAsks = restartMark[1];
    }

    /**
     * S-t: the macro candidates of the current decision. Per searched candidate (its probe's asks in order), the first
     * {@code choices} expandable asks (an alternative exists; with {@code choiceMaxProb} < 1, also the largest prior below
     * it) each give their top {@code choiceAlts} alternatives ({@link ChoiceWindow.Ask#alternatives}: other identities
     * than the policy's own answer, by prior). The first {@code choiceCap} minus the base count are kept, in the order
     * found ({@code choiceRank} base) or by joint prior (joint), ties in the order found.
     */
    /** search-bones-1015: given indices at or past this are S-b's plans, never expanded (MAX = every candidate). */
    private int expandLimit = Integer.MAX_VALUE;

    @Override
    public List<LookaheadSearch.Macro> expand(final int[] givenIndex) {
        final List<MacroSpec> found = new ArrayList<>();
        for (int c = 0; c < givenIndex.length; c++) {
            if (givenIndex[c] >= expandLimit) {
                continue; // search-bones-1015: an S-b plan candidate
            }
            final List<ChoiceWindow.Ask> asks = probeAsks.get(c);
            if (asks == null) {
                continue;
            }
            final List<ChoiceWindow.Ask> copy;
            synchronized (asks) {
                copy = new ArrayList<>(asks);
            }
            probeAsksTotal += copy.size();
            int used = 0;
            for (ChoiceWindow.Ask a : copy) {
                if (used >= cfg.choices) {
                    break;
                }
                if (cfg.choiceMaxProb < 1.0 && a.maxPrior() >= cfg.choiceMaxProb) {
                    continue;
                }
                final List<Integer> alts = a.alternatives();
                if (alts.isEmpty()) {
                    continue;
                }
                used++;
                contestedAsks++;
                for (int t = 0; t < Math.min(cfg.choiceAlts, alts.size()); t++) {
                    final int alt = alts.get(t);
                    final ChoiceWindow.Entry e = new ChoiceWindow.Entry(a.ask, a.ordinal, a.exact[alt], a.loose[alt]);
                    final int gi = givenIndex[c];
                    final double jp = (gi < givenPrior.length ? givenPrior[gi] : 0.0) * a.prior[alt];
                    found.add(new MacroSpec(java.util.Collections.singletonList(e), gi, jp, e.label(),
                            a.greedy >= 0 && a.greedy < a.C ? a.loose[a.greedy] : null));
                }
            }
        }
        final int room = cfg.choiceCap - givenIndex.length;
        final List<LookaheadSearch.Macro> out = new ArrayList<>();
        if (room <= 0 || found.isEmpty()) {
            return out;
        }
        final List<Integer> rank = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            rank.add(i);
        }
        if ("joint".equals(cfg.choiceRank)) {
            rank.sort((x, y) -> {
                final int k = Double.compare(found.get(y).jointPrior, found.get(x).jointPrior);
                return k != 0 ? k : Integer.compare(x, y);
            });
        }
        final java.util.Map<Integer, Integer> baseOf = new java.util.HashMap<>();
        for (int c = 0; c < givenIndex.length; c++) {
            baseOf.putIfAbsent(givenIndex[c], c);
        }
        for (int i = 0; i < Math.min(room, rank.size()); i++) {
            final MacroSpec ms = found.get(rank.get(i));
            macroList.add(ms);
            out.add(new LookaheadSearch.Macro(baseOf.get(ms.baseGiven), ms, ms.label));
        }
        return out;
    }

    /** Keep the per-decision rows in memory (tests) even without a log file. */
    public boolean rowsWanted = false;

    /** Live play (lane live-sc-1009): told about every searched decision (null by default: nothing is called). */
    public interface Searched {
        /** {@code entries}: the pooled menu entries; {@code scoreMs}: the SCORE round trip and pooling before the search. */
        void searched(Game g, LookaheadSearch.GivenResult r, int entries, double scoreMs);
    }

    public Searched onSearched;

    /**
     * Live play, S-b only (lane sb-live-1018; null by default, and never called without {@code plans}): a plan point the
     * seat decided without an S-c decision line, i.e. a proven solver line taken at once ({@code r} null) or a plan or
     * line adopted after the play-outs. {@code ms}: the whole decision from its start (SCORE, proposals and play-outs).
     */
    public interface PlanDecided {
        void decided(Game g, LookaheadSearch.GivenResult r, String note, int plans, double ms);
    }

    public PlanDecided onPlanDecided;

    /** sb-live-1018: the proposals' ms of the decision {@link #onSearched} is told about (S-b plan points), else -1. */
    public double lastProposeMs = -1;

    private void planDecided(final Game g, final PlanSearch.Stage st, final LookaheadSearch.GivenResult r, final long t0) {
        final PlanDecided h = onPlanDecided;
        if (h == null) {
            return;
        }
        try {
            h.decided(g, r, st.note, st.plans.size(), (System.nanoTime() - t0) / 1e6);
        } catch (RuntimeException e) {
            System.err.println("[rlsearch] onPlanDecided failed: " + e);
        }
    }

    /** The search's per-game counters (live play: the decision lines' running totals). */
    public LookaheadSearch.Stats lookaheadStats() {
        return ls.getStats();
    }

    public List<JsonObject> rows() {
        return rows;
    }

    /**
     * The game's CPU since this search was made, in ms: the game thread's, plus every thread charged to the game's
     * account (the look-ahead's workers and all Forge AI eval threads), or, without an account, the workers' only.
     */
    public double gameCpuMs() {
        final double other = gameAcc != null && gameAcc.length > 0 ? gameAcc[0].get() / 1e6 : poolCpuMs;
        return (TMX.getCurrentThreadCpuTime() - cpu0) / 1e6 + other;
    }

    public double poolCpuMs() {
        return poolCpuMs;
    }

    /** Per-game summary for the tape and GAME_END. */
    public JsonObject summary() {
        final JsonObject o = new JsonObject();
        o.add("spec", cfg.toJson());
        o.addProperty("priority_asks", priorityAsks);
        o.addProperty("searched", searchedAsks);
        o.addProperty("departures", departures);
        o.addProperty("stack_skipped", searchStackSkipped);
        o.addProperty("score_calls", scoreCalls);
        o.addProperty("score_failures", scoreFailures);
        o.addProperty("score_ms", Math.round(scoreMs * 10) / 10.0);
        o.addProperty("search_cpu_ms", Math.round(searchCpuMs));
        o.addProperty("pool_cpu_ms", Math.round(poolCpuMs));
        o.addProperty("playout_first_ambiguous", playoutFirstAmbiguous);
        if (budgetStops.get() > 0) {
            // sc-wallguard-1009: only when the budget stopped a play-out seat, so an unbudgeted summary is unchanged
            o.addProperty("playout_budget_stops", budgetStops.get());
        }
        if (ls.getStats().trueFairNamingRefusals > 0) {
            // review #130 S3-3: only when a true-world re-play was refused, so every other summary is unchanged
            o.addProperty("true_fair_naming_refusals", ls.getStats().trueFairNamingRefusals);
        }
        if (cfg.playoutLoopGuard > 0 || cfg.playoutMaxDecisions > 0) {
            // step3-playout-fixes-1028: only with a step-3 play-out key, so every other summary is unchanged
            final long[] ps = playoutSeatStats();
            final JsonObject po = new JsonObject();
            po.addProperty("loop_guard_hits", ps[0]);
            po.addProperty("cap_ends", ps[1]);
            po.addProperty("default_cap_ends", ps[2]);
            po.addProperty("max_sent_finished", ps[3]);
            o.add("playout_seats", po);
        }
        if (voided != null) {
            o.addProperty("voided", voided);
        }
        synchronized (this) {
            if (bigLeafBatches > 0) {
                // bvw-p3-batch-1023: only when a leaf batch needed more than one frame, so every other summary is unchanged
                final JsonObject lb = new JsonObject();
                lb.addProperty("batches", bigLeafBatches);
                lb.addProperty("frames", bigLeafFrames);
                lb.addProperty("messages", bigLeafMessages);
                lb.addProperty("max_leaves", bigLeafMax);
                o.add("leaf_big", lb);
            }
        }
        if (obsVersion != 1) {
            // search-v2-1009: only for an obs-v2 seat, so an S1 game's summary is unchanged
            o.addProperty("obs", obsVersion);
        }
        if (searchSeat >= 0) {
            // r3-distill-1010: only with a GAME search_seat, so every other game's summary is unchanged
            o.addProperty("seat", searchSeat);
        }
        if (cfg.labelSink != null) {
            // r3-distill-1010: only with the label sink on
            final JsonObject lb = new JsonObject();
            lb.addProperty("roots", labelRoots);
            lb.addProperty("choices", labelChoices);
            lb.addProperty("failures", labelFailures);
            o.add("labels", lb);
        }
        if (cfg.choices > 0) {
            // S-t (only when on, so an S1 game's summary is unchanged)
            final JsonObject st = new JsonObject();
            st.addProperty("probe_asks", probeAsksTotal);
            st.addProperty("reconnects", reconnects);
            st.addProperty("expanded_asks", contestedAsks);
            st.addProperty("macro_decisions", macroDecisions);
            st.addProperty("macros", macrosSearched);
            st.addProperty("macro_departures", macroDepartures);
            st.addProperty("macro_default_departures", macroDefaultDepartures);
            final JsonObject ps = new JsonObject();
            for (Map.Entry<String, Integer> e : probeSeen.entrySet()) {
                ps.addProperty(e.getKey(), e.getValue());
            }
            st.add("probe_seen", ps);
            st.add("live", liveCounters.toJson());
            st.add("playout", playoutCounters.toJson());
            o.add("st", st);
        }
        o.add("lookahead", ls.getStats().toJson());
        if (!solverDrivers.isEmpty()) {
            // search-bones-1015: only with forcedWin on
            final JsonObject fw = new JsonObject();
            solverDrivers.forEach((id, d) -> fw.add(String.valueOf(id), d.summary()));
            o.add("fw", fw);
        }
        if (!planSearches.isEmpty()) {
            final JsonObject pl = new JsonObject();
            planSearches.forEach((id, d) -> pl.add(String.valueOf(id), d.summary()));
            o.add("plans", pl);
        }
        if (cfg.blocks > 0) {
            // block-search-1019: only when on
            final JsonObject bl = new JsonObject();
            bl.addProperty("asks", blockAsks);
            bl.addProperty("searched", blockSearched);
            bl.addProperty("departures", blockDepartures);
            bl.addProperty("capped", blockCapped);
            bl.addProperty("forge_missing", blockForgeMissing);
            bl.addProperty("sample_failures", blockSampleFailures);
            o.add("blocks", bl);
        }
        return o;
    }

    /**
     * Append the per-decision rows to {@code cfg.decisionLog} (one writer per JVM, synchronized), and this game's label
     * records to {@code cfg.labelSink} as one gzip member (r3-distill-1010).
     */
    public void flushLog() {
        if (cfg.labelSink != null && !labelRecs.isEmpty()) {
            final String path = cfg.labelSink.replace("{actor}", actorId == null ? "actor" : actorId)
                    .replace("{pid}", String.valueOf(ProcessHandle.current().pid()));
            synchronized (RlSearch.class) {
                try {
                    appendGame(path, labelRecs);
                } catch (IOException e) {
                    labelFailures++;
                    System.err.println("[rlsearch] label sink failed: " + e);
                }
            }
            labelRecs.clear();
        }
        if (cfg.decisionLog == null || rows.isEmpty()) {
            return;
        }
        final String path = cfg.decisionLog.replace("{actor}", actorId == null ? "actor" : actorId);
        synchronized (RlSearch.class) {
            try (PrintWriter w = new PrintWriter(new FileWriter(path, StandardCharsets.UTF_8, true))) {
                for (JsonObject r : rows) {
                    w.println(RlWire.canonicalString(r));
                }
            } catch (IOException e) {
                System.err.println("[rlsearch] decision log failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ hooks

    /** One copy's seat knowledge (forked from the live game's) and featurizer. */
    static final class CopyCtx {
        final RlKnowledge know;
        final RlFeaturizer feat;

        CopyCtx(final RlKnowledge know, final RlFeaturizer feat) {
            this.know = know;
            this.feat = feat;
        }
    }

    @Override
    public Object onCopy(final Game copy, final Player me) {
        final RlKnowledge k;
        if (liveKnow == null) {
            k = new RlKnowledge(copy);
            k.v2 = obsVersion == 2;
        } else {
            k = liveKnow.forkFor(copy);   // carries the live tracker's v2 extras (seen ids, tail facts and targets)
        }
        k.attach();
        final RlFeaturizer f = new RlFeaturizer(index);
        f.setVersion(obsVersion);
        f.setKnowledge(k);
        return new CopyCtx(k, f);
    }

    /**
     * A PRIORITY frame of the seat with only PASS as candidate: v(o) reads the observation alone. Its observation part
     * is the seat's own frame's ({@code RlSeat.frame}) in the observation's schema: obs-v1 as the S1 read's, obs-v2 with
     * its widths, bits, relations, facts and remaining multiset (search-v2-1009).
     */
    public static RlWire.Decide leafFrame(final RlFeaturizer.Obs o, final long uid, final int seat, final int turn) {
        final RlWire.Decide f = o.version == 2 ? RlWire.Decide.v2() : new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = 0;
        f.seat = seat;
        f.family = RlSchema.F_PRIORITY;
        f.mode = RlSchema.M_SINGLE;
        f.flags = (o.truncated ? RlWire.F_TRUNC_TOKENS : 0) | (o.version == 2 && o.droppedRefs ? RlWire.F_DROPPED_REFS : 0);
        f.minPick = 1;
        f.maxPick = 1;
        f.turn = Math.min(0xffff, turn);
        f.L = o.L;
        f.D = o.D;
        f.C = 1;
        f.S = 0;
        f.P = 0;
        f.tokCard = o.tokCard;
        f.tokZone = o.tokZone;
        f.tokAttr = o.tokAttr;
        f.deckCard = o.deckCard;
        f.deckCnt = o.deckCnt;
        System.arraycopy(o.scal, 0, f.scal, 0, RlSchema.N_SCAL);
        System.arraycopy(o.ctx, 0, f.ctx, 0, f.nCtx());
        f.candKind = new byte[] {(byte) RlSchema.K_PASS};
        f.candTok = new short[] {-1};
        f.candCard = new int[] {0};
        f.candTgt = new short[] {-1, -1};
        f.candSlot = new short[] {-1};
        f.candNum = new short[] {-1};
        f.candAbility = new byte[] {0};
        f.candFlags = new byte[] {0};
        f.slotTok = new short[0];
        if (o.version == 2) {
            f.R = o.R;
            f.F = o.F;
            f.Dr = o.Dr;
            f.tokBits = o.tokBits;
            f.relSrc = o.relSrc;
            f.relDst = o.relDst;
            f.relType = o.relType;
            f.relArg = o.relArg;
            f.relNum = o.relNum;
            f.factTok = o.factTok;
            f.factId = o.factId;
            f.factArg = o.factArg;
            f.factNum = o.factNum;
            f.restCard = o.restCard;
            f.restCnt = o.restCnt;
        }
        return f;
    }

    @Override
    public Object captureLeaf(final Object copyCtx, final Game g, final Player me) {
        final CopyCtx c = (CopyCtx) copyCtx;
        final RlFeaturizer.Obs o = c.feat.observe(g, me, 0, false, null);
        return RlWire.encodeDecide(leafFrame(o, uid, g.getRegisteredPlayers().indexOf(me), g.getPhaseHandler().getTurn()));
    }

    @Override
    public double[] leafValues(final List<Object> payloads) {
        final List<byte[]> ps = new ArrayList<>(payloads.size());
        for (Object p : payloads) {
            ps.add((byte[]) p);
        }
        final float[] v;
        leafError.remove();
        try {
            final RlSearchClient c = client();
            final long bb = c.bigBatches, bf = c.bigFrames, bm = c.bigMessages;
            v = c.values(ps);
            if (c.bigBatches != bb) {
                // bvw-p3-batch-1023: a batch above one frame, scored in several frames / messages
                synchronized (this) {
                    bigLeafBatches++;
                    bigLeafFrames += c.bigFrames - bf;
                    bigLeafMessages += c.bigMessages - bm;
                    bigLeafMax = Math.max(bigLeafMax, ps.size());
                }
            }
        } catch (IOException | RuntimeException e) {
            drop();
            // bvw-p3-batch-1023: the cause also goes to the look-ahead, whose static-fallback line names it
            final String why = "LEAVES failed: " + e + " (" + ps.size() + " leaves, "
                    + RlSearchClient.leavesBytes(ps, 0, ps.size()) + " leaf bytes)";
            leafError.set(why);
            System.err.println("[rlsearch] " + why);
            return null;
        }
        final double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (Math.max(-1.0, Math.min(1.0, v[i])) + 1.0) / 2.0;
        }
        return out;
    }

    /** Why this thread's last {@link #leafValues} returned null (bvw-p3-batch-1023), or null. */
    private final ThreadLocal<String> leafError = new ThreadLocal<>();
    /** Leaf batches above one frame (bvw-p3-batch-1023): batches, frames, messages, the largest batch's leaves. */
    private long bigLeafBatches = 0, bigLeafFrames = 0, bigLeafMessages = 0, bigLeafMax = 0;

    @Override
    public String leafError() {
        return leafError.get();
    }

    /** The policy-piloted seat of one play-out: a bridged controller whose asks the search service answers. */
    static final class PlayoutController extends PlayerControllerBridge {
        private final LookaheadSearch.LeafProbe probe;
        /**
         * Copy fidelity (copy-fidelity-1010): the first priority (the searched candidate's) keeps a planeswalker
         * ultimate in the menu ({@link forge.ai.ComputerUtilCost#setKeepPwUltimates}); false = as before.
         */
        private boolean keepUltimatesFirst;

        PlayoutController(final Game g, final Player p, final forge.LobbyPlayer lp, final BenchSession session,
                final int seat, final LookaheadSearch.LeafProbe probe) {
            this(g, p, lp, session, seat, probe, false);
        }

        PlayoutController(final Game g, final Player p, final forge.LobbyPlayer lp, final BenchSession session,
                final int seat, final LookaheadSearch.LeafProbe probe, final boolean keepUltimatesFirst) {
            this(g, p, lp, session, seat, probe, keepUltimatesFirst, new CallCounter());
        }

        private PlayoutController(final Game g, final Player p, final forge.LobbyPlayer lp, final BenchSession session,
                final int seat, final LookaheadSearch.LeafProbe probe, final boolean keepUltimatesFirst,
                final CallCounter calls) {
            super(g, p, lp, session, BenchSession.Mode.BRIDGE, seat, calls);
            this.probe = probe;
            this.keepUltimatesFirst = keepUltimatesFirst;
            this.calls = calls;
        }

        /** Review #123 S3-1: every controller entry point of this seat, by method (the true-world census). */
        final CallCounter calls;

        /**
         * Block search (block-search-1019): the searched declaration, declared at this copy's first declare-blockers step
         * (null = none), and why it could not be (the play-out then fails).
         */
        Object forcedBlocks;
        private boolean forcedUsed = false;
        volatile String forcedFailure;

        @Override
        public void declareBlockers(final Player defender, final forge.game.combat.Combat combat) {
            if (forcedBlocks != null && !forcedUsed) {
                forcedUsed = true;
                final String why = LookaheadSearch.applyBlocks(forcedBlocks, getGame(), defender, combat);
                if (why == null) {
                    return;
                }
                forcedFailure = "the searched blocks could not be declared in the play-out: " + why;
            }
            super.declareBlockers(defender, combat);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (forcedBlocks != null && !forcedUsed && forcedFailure == null) {
                // block-search-1019: the copy reached a priority before its declare-blockers step
                forcedFailure = "the play-out reached a priority before declaring the searched blocks";
            }
            if (probe != null) {
                probe.onPriority(getGame(), getPlayer());
            }
            if (keepUltimatesFirst) {
                keepUltimatesFirst = false;
                forge.ai.ComputerUtilCost.setKeepPwUltimates(true);
                try {
                    return super.chooseSpellAbilityToPlay();
                } finally {
                    forge.ai.ComputerUtilCost.setKeepPwUltimates(false);
                }
            }
            return super.chooseSpellAbilityToPlay();
        }
    }

    /**
     * Lane sc-wallguard-1009: a policy play-out's service request past its decision's wall budget is refused here, before
     * it is sent (the connection stays as it is). The seat ends its copy as on any transport fault, and the look-ahead
     * counts the play-out as stopped by the budget (the decision is capped and plays the policy's own choice). A seat
     * looping inside one Forge step (the SCL read's two overruns: max_decisions in one step) so stops within one request
     * of the deadline. Never without a budget.
     */
    private void budgetStop(final LookaheadSearch.FirstAction first) throws IOException {
        if (first != null && first.overBudget()) {
            budgetStops.incrementAndGet();
            throw new IOException("budget: the search's wall budget ran out");
        }
    }

    /** step3-playout-fixes-1028: every entry of {@code match} is an exact match (not the loose may-play path). */
    static boolean looseMatchExact(final List<Integer> match, final RlCandidates.Menu m, final List<SpellAbility> objs,
            final LookaheadSearch.FirstAction first) {
        for (int i : match) {
            final int ch = choiceOf(m, i);
            if (!(ch == 0 ? first.pass : ch > 0 && ch <= objs.size() && first.matches(objs.get(ch - 1)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lane copy-fidelity-1010 (diagnostics, appended to the failure): what the play-out's first menu offered from the
     * searched candidate's card, and where the copy stood.
     */
    static String menuMiss(final Game g, final RlCandidates.Menu m, final List<SpellAbility> objs,
            final LookaheadSearch.FirstAction first) {
        try {
            final StringBuilder sb = new StringBuilder("; wanted '");
            sb.append(first.desc.length() > 50 ? first.desc.substring(0, 50) : first.desc).append("' of card ").append(first.hostId);
            final forge.game.card.Card host = g.findById(first.hostId);
            sb.append(host == null ? " (not in the copy)" : " (" + host.getName() + " in "
                    + (host.getZone() == null ? "no zone" : host.getZone().getZoneType().name()) + ", counters "
                    + host.getCounters() + ", pw activated " + host.getPlaneswalkerAbilityActivated() + ")");
            sb.append(" turn ").append(g.getPhaseHandler().getTurn());
            sb.append(" at ").append(g.getPhaseHandler().getPhase()).append(" stack ").append(g.getStack().size());
            sb.append("; its entries [");
            int n = 0;
            for (int i = 0; i < m.C(); i++) {
                final int ch = choiceOf(m, i);
                if (ch > 0 && ch <= objs.size() && objs.get(ch - 1).getHostCard() != null
                        && objs.get(ch - 1).getHostCard().getId() == first.hostId) {
                    final String d = objs.get(ch - 1).getDescription();
                    sb.append(n++ > 0 ? " | " : "").append(d.length() > 40 ? d.substring(0, 40) : d);
                }
            }
            return sb.append(']').toString();
        } catch (RuntimeException e) {
            return "; (" + e + ")";
        }
    }

    @Override
    public LookaheadSearch.PlayoutSeat playoutSeat(final Object copyCtx, final Game g, final Player me,
            final LookaheadSearch.FirstAction first, final long seed, final LookaheadSearch.LeafProbe probe) {
        final CopyCtx cc = (CopyCtx) copyCtx;
        final int seat = g.getRegisteredPlayers().indexOf(me);
        final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),
                OutputStream.nullOutputStream()));
        session.setEncodeState(false);
        session.setLiveGame(g);
        session.setKnowledgeObserver(cc.know);
        final String[] ctl = new String[g.getRegisteredPlayers().size()];
        for (int s = 0; s < ctl.length; s++) {
            ctl[s] = s == seat ? "rl:playout" : "forge";
        }
        final RlSeat.Config sc = playoutSeatConfig(first);
        final RlSeat.Endpoint ep = new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                budgetStop(first);
                try {
                    return client().decide(p, cfg.playoutSample);
                } catch (IOException | RuntimeException e) {
                    drop();
                    throw e;
                }
            }

            @Override
            public void record(final byte[] p) throws IOException {
                throw new IOException("a play-out seat never records");
            }

            @Override
            public RlWire.Decision decideSampled(final byte[] p, final RlWire.Decide frame) throws IOException {
                // search-bones-1015 (S-b proposals): the recorder samples from the policy's SCORE distribution
                if (!(first.recorder instanceof PlanSearch.Recorder)) {
                    return decide(p);
                }
                budgetStop(first);
                final RlSearchClient.Scores s;
                try {
                    s = client().score(p);
                } catch (IOException | RuntimeException e) {
                    drop();
                    throw e;
                }
                if (s.status != RlWire.ST_OK || s.probs.length == 0) {
                    return decide(p);
                }
                return ((PlanSearch.Recorder) first.recorder).sample(frame, s);
            }
        };
        final RlSeat rs = new RlSeat(cc.feat, ep, sc, seed, ctl, TEST_LEAK_PRIV);
        rs.setGame(g);
        rs.seenNames = cc.know::opponentSeen;
        final String[] why = {null};
        // search-bones-1015 (S-b): a recording play-out or a plan candidate has no forced first action
        // block-search-1019: a block candidate has no forced priority action either (its seat declares the blocks)
        final boolean planned = first.recorder != null || first.plan != null || first.block != null;
        if (first.recorder instanceof PlanSearch.Recorder) {
            final PlanSearch.Recorder rec = (PlanSearch.Recorder) first.recorder;
            rec.seatPlayer = me;
            rs.sampleIf = rec::sampleIf;
            rs.priorityTrace = rec;
        } else if (first.plan instanceof PlanSearch.PolicyPlan) {
            rs.planCursor = new PlanSearch.Cursor((PlanSearch.PolicyPlan) first.plan, first, 0);
        }
        if (!planned) rs.playoutFirst = new RlSeat.PlayoutFirst() {
            @Override
            public int choose(final Game gg, final Player p, final RlCandidates.Menu m, final List<SpellAbility> objs,
                    final java.util.function.Supplier<RlWire.Decide> frame) {
                final List<Integer> match = new ArrayList<>();
                for (int i = 0; i < m.C(); i++) {
                    if (m.cands.get(i).kind <= 0) {
                        continue;
                    }
                    final int ch = choiceOf(m, i);
                    if (ch == 0 ? first.pass : ch > 0 && ch <= objs.size() && first.matches(objs.get(ch - 1))) {
                        match.add(i);
                    }
                }
                if (match.isEmpty() && first.mayPlayLoose) {
                    // copy fidelity: a may-play spell whose description differs only in its may-play suffix
                    for (int i = 0; i < m.C(); i++) {
                        final int ch = m.cands.get(i).kind <= 0 ? -1 : choiceOf(m, i);
                        if (ch > 0 && ch <= objs.size() && first.matchesMayPlay(objs.get(ch - 1))) {
                            match.add(i);
                        }
                    }
                }
                if (match.size() > 1 && first.mayPlayUnique && !looseMatchExact(match, m, objs, first)) {
                    // step3-playout-fixes-1028 (review #130 S3-2): a re-play's loose may-play match must be unique
                    why[0] = "the searched candidate is ambiguous in the play-out's menu (" + match.size()
                            + " may-play entries)" + menuMiss(gg, m, objs, first);
                    return -1;
                }
                if (match.isEmpty()) {
                    why[0] = "the searched candidate is not in the play-out's menu" + menuMiss(gg, m, objs, first);
                    return -1;
                }
                if (match.size() == 1) {
                    return match.get(0);
                }
                // X variants (or a duplicated entry): the policy's most likely of them
                synchronized (RlSearch.this) {
                    playoutFirstAmbiguous++;
                }
                try {
                    final RlSearchClient.Scores s;
                    budgetStop(first);
                    try {
                        s = client().score(RlWire.encodeDecide(frame.get()));
                    } catch (IOException | RuntimeException e) {
                        drop();
                        throw e;
                    }
                    int best = match.get(0);
                    for (int i : match) {
                        if (i < s.probs.length && s.probs[i] > s.probs[best]) {
                            best = i;
                        }
                    }
                    return best;
                } catch (IOException | RuntimeException e) {
                    why[0] = "SCORE for the first action failed: " + e;
                    return -1;
                }
            }

            @Override
            public String why() {
                return why[0];
            }
        };
        if (cfg.choices > 0) {
            // S-t: a macro's play-out answers its schedule; a base candidate's world-0 play-out records its choice asks
            if (first.schedule instanceof MacroSpec) {
                final MacroSpec ms = (MacroSpec) first.schedule;
                rs.window = ChoiceWindow.scheduled(ms.entries, ms.playout);
            } else if (first.probe && first.candIndex >= 0) {
                final List<ChoiceWindow.Ask> into = probeAsks.computeIfAbsent(first.candIndex,
                        k -> Collections.synchronizedList(new ArrayList<>()));
                rs.window = ChoiceWindow.recorder(choiceFams, payload -> {
                    final RlSearchClient.Scores ps;
                    budgetStop(first);
                    try {
                        ps = client().score(payload);
                    } catch (IOException | RuntimeException e) {
                        drop();
                        throw e;
                    }
                    if (ps.status != RlWire.ST_OK) {
                        return null;
                    }
                    final double[] d = new double[ps.probs.length];
                    for (int i = 0; i < d.length; i++) {
                        d[i] = ps.probs[i];
                    }
                    return d;
                }, into);
                probeWindows.add(rs.window);
            }
        }
        session.setLocalAnswerer(rs);
        final PlayoutController pc = new PlayoutController(g, me, me.getController().getLobbyPlayer(), session, seat, probe,
                (cfg.copyFidelity == 1 || cfg.copyFidelity == 2 && ls.fidelityPass()) && !first.pass && !planned);
        census(rs, pc); // review #123 S3-1: true-world re-plays only (a no-op otherwise)
        if (first.plan instanceof PlanSearch.LinePlan) {
            // search-bones-1015 (S-b): the solver line's follower in front of the copy's policy seat
            pc.planHook = PlanSearch.copyLineHook(me, followConfig(), (PlanSearch.LinePlan) first.plan, first);
        }
        pc.forcedBlocks = first.block;
        return new LookaheadSearch.PlayoutSeat() {
            @Override
            public PlayerControllerAi controller() {
                return pc;
            }

            @Override
            public String failure() {
                noteSeatEnd(rs, sc);
                if (rs.fatal != null) {
                    return rs.fatal;
                }
                if (pc.forcedFailure != null) {
                    return pc.forcedFailure;
                }
                return rs.voidReason;
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ S2T (step 2)

    /**
     * Tests only (bvw-step2-fork-1027, the honesty test's mutant; false otherwise): the play-out policy seats' frames carry
     * the privileged block (the hidden cards). The honesty test must FAIL with it on. Never set outside tests.
     */
    public static volatile boolean TEST_LEAK_PRIV = false;

    /**
     * bvw-step2-fork-1027 (S2T, spec key {@code opponent} "policy"): the play-out opponent's seat, the same policy and
     * service as ours: a bridged controller whose asks go to the service as DECIDE frames of ITS OWN observation (its
     * seat's knowledge in the copy's tracker, its own featurizer), greedy like our play-out seat (or sampled with
     * {@code playoutSample}), no first action, no plan, no leaf probe, no solver. Its faults fail the play-out.
     */
    @Override
    public LookaheadSearch.PlayoutSeat opponentSeat(final Object copyCtx, final Game g, final Player opp, final long seed) {
        return opponentSeat(copyCtx, g, opp, seed, null);
    }

    /**
     * As above; {@code first} is the play-out's own first action (its decision's budget and whether it is an OPR
     * roll-out), null = none (the step-2 seat exactly).
     */
    @Override
    public LookaheadSearch.PlayoutSeat opponentSeat(final Object copyCtx, final Game g, final Player opp, final long seed,
            final LookaheadSearch.FirstAction first) {
        final CopyCtx cc = (CopyCtx) copyCtx;
        final int seat = g.getRegisteredPlayers().indexOf(opp);
        if (seat < 0) {
            return null;
        }
        final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),
                OutputStream.nullOutputStream()));
        session.setEncodeState(false);
        session.setLiveGame(g);
        session.setKnowledgeObserver(cc.know);
        final String[] ctl = new String[g.getRegisteredPlayers().size()];
        for (int s = 0; s < ctl.length; s++) {
            ctl[s] = s == seat ? "rl:playout-opp" : "forge";
        }
        final RlSeat.Config sc = playoutSeatConfig(first);
        // step3-playout-fixes-1028 (playoutMaxDecisions; off: no stop, as step 2): the decision's budget stop
        final boolean stopOnBudget = cfg.playoutMaxDecisions > 0 && first != null;
        final RlFeaturizer feat = new RlFeaturizer(index);
        feat.setVersion(obsVersion);
        feat.setKnowledge(cc.know);
        final RlSeat.Endpoint ep = new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                if (stopOnBudget) {
                    budgetStop(first);
                }
                try {
                    return client().decide(p, cfg.playoutSample);
                } catch (IOException | RuntimeException e) {
                    drop();
                    throw e;
                }
            }

            @Override
            public void record(final byte[] p) throws IOException {
                throw new IOException("a play-out seat never records");
            }
        };
        final RlSeat rs = new RlSeat(feat, ep, sc, seed, ctl, TEST_LEAK_PRIV);
        rs.setGame(g);
        rs.seenNames = cc.know::opponentSeen;
        session.setLocalAnswerer(rs);
        final PlayoutController pc = new PlayoutController(g, opp, opp.getController().getLobbyPlayer(), session, seat,
                null, false);
        census(rs, pc); // review #123 S3-1
        synchronized (this) {
            opponentSeats++;
        }
        return new LookaheadSearch.PlayoutSeat() {
            @Override
            public PlayerControllerAi controller() {
                return pc;
            }

            @Override
            public String failure() {
                noteSeatEnd(rs, sc);
                return rs.fatal != null ? rs.fatal : rs.voidReason;
            }
        };
    }

    /** S2T: opponent policy seats built (play-outs whose opponent was the policy). */
    private int opponentSeats = 0;

    // ------------------------------------------------------------------------------------------------ step 3 play-out seats

    /**
     * step3-playout-fixes-1028: a search play-out seat's run-level knobs (ours and the opponent's). With every key off
     * these are the step-2 seat's exactly ({@code new RlSeat.Config()}, mode eval: no loop guard, 3,000 decisions).
     * {@code playoutLoopGuard} K puts the "Cancel Search?" guard on the seat; {@code playoutMaxDecisions} N caps the
     * seat at N sent decisions, except in an OPR roll-out (12 turns to the end of a game; its own turn and step caps).
     */
    RlSeat.Config playoutSeatConfig(final LookaheadSearch.FirstAction first) {
        final RlSeat.Config sc = new RlSeat.Config();
        sc.mode = "eval";
        if (cfg.playoutLoopGuard > 0) {
            sc.loopGuard = cfg.playoutLoopGuard;
            sc.loopGuardLog = false; // counted in the summary instead of one stderr line per hit
        }
        if (cfg.playoutMaxDecisions > 0 && (first == null || !first.oprRollout)) {
            sc.maxDecisions = cfg.playoutMaxDecisions;
        }
        return sc;
    }

    /** step3-playout-fixes-1028: loop-guard answers, reduced-cap ends, and the most decisions a finished seat sent. */
    private final java.util.concurrent.atomic.AtomicLong playoutGuardHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong playoutCapEnds = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong playoutDefaultCapEnds = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger playoutMaxSent = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<RlSeat> playoutSeatsNoted = java.util.Collections.newSetFromMap(
            new java.util.WeakHashMap<>());

    /** Once per seat (failure() may be read more than once): its counts into the step-3 play-out statistics. */
    private void noteSeatEnd(final RlSeat rs, final RlSeat.Config sc) {
        synchronized (playoutSeatsNoted) {
            if (!playoutSeatsNoted.add(rs)) {
                return;
            }
        }
        playoutGuardHits.addAndGet(rs.loopGuardHits());
        if ("max_decisions".equals(rs.voidReason)) {
            (sc.maxDecisions < new RlSeat.Config().maxDecisions ? playoutCapEnds : playoutDefaultCapEnds).incrementAndGet();
        } else if (rs.voidReason == null && rs.fatal == null) {
            playoutMaxSent.accumulateAndGet(rs.totalSent(), Math::max);
        }
    }

    /** This search's spec. */
    public Config config() {
        return cfg;
    }

    /** Tests and the summary: {guard hits, reduced-cap ends, default-cap ends, most decisions of a finished seat}. */
    public long[] playoutSeatStats() {
        return new long[] {playoutGuardHits.get(), playoutCapEnds.get(), playoutDefaultCapEnds.get(), playoutMaxSent.get()};
    }

    /** S2T: the opponent policy seats built so far (tests). */
    public synchronized int opponentSeats() {
        return opponentSeats;
    }

    /**
     * bvw-step2-fork-1027 (S2T logging): the leaves also take the opponent's own frame (v_o) for the leaf pairs; with
     * {@code leafSym} off the decision's leaves are step 1's exactly and v_o comes from a second call afterwards.
     */
    void setLeafPairs(final boolean on) {
        ls.getConfig().leafOpp = on || cfg.leafSym;
    }

    /**
     * bvw-step2-fork-1027: {@link LookaheadSearch#replayCandidate} of the last search's candidate {@code cand}; a
     * true-world re-play (T, OPR) also records the census of its play-out seats (review #123 S3-1).
     */
    LookaheadSearch.Replay replayCandidate(final Game g, final Player me, final int cand, final long salt, final int knownTop,
            final int worlds, final int mode, final int capTurns, final int capSteps) {
        if (mode == LookaheadSearch.WORLD_BELIEF) {
            return ls.replayCandidate(g, me, cand, salt, knownTop, worlds, mode, capTurns, capSteps);
        }
        final java.util.Map<String, Integer> forge = new TreeMap<>(), asked = new TreeMap<>();
        final List<CallCounter> counters = new ArrayList<>();
        censusForge = forge;
        censusAsked = asked;
        censusCounters = counters;
        final LookaheadSearch.Replay r;
        try {
            r = ls.replayCandidate(g, me, cand, salt, knownTop, worlds, mode, capTurns, capSteps);
        } finally {
            censusForge = null;
            censusAsked = null;
            censusCounters = null;
        }
        // the controller entry points that never asked a policy seat (Forge AI answered them): calls minus asks
        final java.util.Map<String, Integer> calls = new TreeMap<>(), refused = new TreeMap<>();
        synchronized (counters) {
            for (CallCounter cc : counters) {
                final JsonObject cj = cc.toJson();
                for (Map.Entry<String, JsonElement> e : cj.getAsJsonObject("calls").entrySet()) {
                    calls.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
                }
                // a policy answer the bridge refused: Forge AI answered it
                for (Map.Entry<String, JsonElement> e : cj.getAsJsonObject("delegatedRefused").entrySet()) {
                    refused.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
                }
            }
        }
        final JsonObject fa = new JsonObject(), fc = new JsonObject();
        synchronized (forge) {
            for (Map.Entry<String, Integer> e : forge.entrySet()) {
                fa.addProperty(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : calls.entrySet()) {
                final int n = e.getValue() - asked.getOrDefault(e.getKey(), 0);
                if (n > 0) {
                    fc.addProperty(e.getKey(), n);
                }
            }
        }
        final JsonObject fr = new JsonObject();
        for (Map.Entry<String, Integer> e : refused.entrySet()) {
            fr.addProperty(e.getKey(), e.getValue());
        }
        final JsonObject o = new JsonObject();
        o.add("asks", fa);
        o.add("calls", fc);
        o.add("refused", fr);
        r.forge = o;
        return r;
    }

    /** Review #123 S3-1: the census of the true-world re-play in progress (null otherwise; decision thread sets it). */
    private volatile java.util.Map<String, Integer> censusForge, censusAsked;
    private volatile List<CallCounter> censusCounters;

    private void census(final RlSeat rs, final PlayoutController pc) {
        final java.util.Map<String, Integer> f = censusForge, a = censusAsked;
        final List<CallCounter> cs = censusCounters;
        if (f == null || a == null || cs == null) {
            return;
        }
        rs.forgeLog = f;
        rs.askLog = a;
        synchronized (cs) {
            cs.add(pc.calls);
        }
    }

    /**
     * Review #123 S2-1: some seat knows this library card of the live position (a fork of the replay's seat knowledge,
     * so the live tracker is never settled from here), for the T re-play's library shuffle.
     */
    @Override
    public java.util.function.Predicate<forge.game.card.Card> knownToAnySeat(final Game live) {
        if (liveKnow == null) {
            return null;
        }
        final RlKnowledge k = liveKnow.forkFor(live);
        return c -> k.knows(0, c) || k.knows(1, c);
    }
}
