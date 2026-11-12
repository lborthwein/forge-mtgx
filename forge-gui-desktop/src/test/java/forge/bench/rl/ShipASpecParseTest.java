package forge.bench.rl;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * shipA-on-leakfix-1112: the ship candidate's search spec as a live policy-search string.
 * {@link RlLiveSeat.Spec#parse} must accept every key of the candidate's bench spec (the card scorer, the reach, the
 * planner and engine solver keys, breakOnCounter, hiddenOrderLegacy), give the same search config as
 * {@link RlSearch.Config#parse} gives for the bench spec, and leave today's live string's config as it was.
 * No game, no card database: configs only.
 */
public class ShipASpecParseTest {

    /** The keys a deployment sets for its host (not part of the search). */
    static final String HOST = "server=127.0.0.1:1,policySha=" + "0".repeat(64) + ",cardIndex=card-index.tsv,";

    /** The live string of build b-39496c73302c (2026-11-11), without its three host keys. */
    static final String TODAY = "budgetMs=8000,threads=8,obs=2,maxDecisions=1000,loopGuard=2,plans.samples=4,"
            + "plans.solverCfg.budgetMs=6000,plans.solverCfg.cycles=true,plans.solverCfg.engineMs=4000,"
            + "plans.solverCfg.evalReserveMs=1500,plans.solverCfg.hideOwnLibrary=true,blocks=1,"
            + "plans.defend=true,plans.defendCfg.prior=jar,plans.defendCfg.priorGain=20,"
            + "plans.defendCfg.tieBreakTurns=1,plans.defendCfg.defendMs=6000,plans.defendCfg.sharedReader=true,"
            + "plans.defendCfg.priorFillBasics=true,plans.defendCfg.defenseWorlds=16,"
            + "plans.defendCfg.counterDelta=0.10,plans.defendCfg.seenGain=4,infoState=on";

    /**
     * Behaviour the candidate's own jar has with no key, which this branch keeps behind default-off keys so that
     * today's string plays as before; the ship string turns each on, in the plan solver and in the defender's.
     */
    static final String[] GATED = {"passBlockers"};
    static final String GATED_ADDS = "plans.solverCfg.passBlockers=true,plans.defendCfg.passBlockers=true";

    /** What the candidate adds to it (planner-fixes/ship-merge/mkstring.py: today's string + the spec's other keys). */
    static final String SHIPA_ADDS = "anytime=true,blockSampleSeed=true,choiceScorer.budgetMs=8000,choiceScorer.families=CARDS,"
            + "choiceScorer.gfActive=3,choiceScorer.gfAskNodes=60000,choiceScorer.gfExtra=2,"
            + "choiceScorer.gfGamma=0.8,choiceScorer.gfHorizon=5,choiceScorer.gfLambda=0.6,"
            + "choiceScorer.gfLands=true,choiceScorer.gfMs=60,choiceScorer.gfNodes=1500,"
            + "choiceScorer.gfSamples=0,choiceScorer.margin=0.02,choiceScorer.reachAlpha=0.85,"
            + "choiceScorer.reachNameNodes=1000,choiceScorer.reachOnePass=true,choiceScorer.reachSig=true,"
            + "choiceScorer.reachSkipCapped=true,choiceScorer.screenMs=60,choiceScorer.screenNodes=2000,"
            + "choiceScorer.seqs=2,historyLki=on,plans.defendCfg.readerPerGame=true,plans.lineChoices=forge,"
            + "plans.lineKeep=true,plans.planX=true,plans.replanOnEntry=true,"
            + "plans.solverCfg.breakOnCounter=true,plans.solverCfg.engineAfterPlanFail=true,"
            + "plans.solverCfg.engineLongLoops=true,plans.solverCfg.engineLoopBisect=true,"
            + "plans.solverCfg.engineLoopHoist=true,plans.solverCfg.engineLoopReach=true,"
            + "plans.solverCfg.engineMoves=true,plans.solverCfg.engineNodesPerSearch=true,"
            + "plans.solverCfg.engineOpen=true,plans.solverCfg.engineRerun=true,"
            + "plans.solverCfg.engineTriggerTargets=true,plans.solverCfg.engineVerify=true,"
            + "plans.solverCfg.followPay=true,plans.solverCfg.historyLki=true,"
            + "plans.solverCfg.landEntersProbe=true,plans.solverCfg.loopMarginal=true,"
            + "plans.solverCfg.paySearch=true,plans.solverCfg.planDiscardWall=true,plans.solverCfg.planOrder=7,"
            + "plans.solverCfg.probeSettle=true,plans.solverCfg.readerPerGame=true,"
            + "plans.solverCfg.sourceColorsLive=true,plans.solverCfg.sourceLeaves=true,"
            + "plans.solverCfg.sourceOutput=true,plans.solverCfg.stackHistory=true,"
            + "plans.solverCfg.tutorCast=true,plans.solverCfg.unknownWorlds=true,"
            + "plans.solverCfg.verifyReplan=true,plans.unified=true,reach.alpha=0.85,reach.depart=0.04,"
            + "reach.gamma=0.9,reach.gate=true,reach.maxNames=40,reach.ms=60,reach.nameNodes=1000,"
            + "reach.nameOnePass=true,reach.nodes=2000,reach.oppBeta=0.5,reach.oppDirect=true,reach.sig=true,"
            + "reach.skipCapped=true,stack=true,stackHistory=true,"
            + GATED_ADDS;

    /** The candidate's bench spec (specs/shipA.json, sha256 d831922f3786...; beliefCube is the bench's cube file). */
    static final String SHIPA_JSON = """
{
 "anytime": true,
 "basicsByType": true,
 "belief": "uniform",
 "beliefBasics": 8,
 "beliefCube": "cube.json",
 "beliefCubeSha256": "4aa5b3b82095106c5cc7a0357e913a1ff679cf7ab1cc2bfb14b6ff9b0c8d7acb",
 "beliefPlayerStatics": true,
 "beliefRememberedHand": true,
 "beliefSingleton": true,
 "beliefStatics": true,
 "blockSampleSeed": true,
 "blockSamples": 8,
 "blockTop": 3,
 "blocks": 1,
 "breadth": 4,
 "budgetMs": 8000,
 "choiceScorer": {
  "budgetMs": 8000,
  "families": "CARDS",
  "gfActive": 3,
  "gfAskNodes": 60000,
  "gfExtra": 2,
  "gfGamma": 0.8,
  "gfHorizon": 5,
  "gfLambda": 0.6,
  "gfLands": true,
  "gfMs": 60,
  "gfNodes": 1500,
  "gfSamples": 0,
  "margin": 0.02,
  "reachAlpha": 0.85,
  "reachNameNodes": 1000,
  "reachOnePass": true,
  "reachSig": true,
  "reachSkipCapped": true,
  "screenMs": 60,
  "screenNodes": 2000,
  "seqs": 2
 },
 "connectTimeoutMs": 2000,
 "copyBackFace": true,
 "copyTurnLimits": true,
 "cpuCapMs": 14400000,
 "crewNoop": "on",
 "deadEtb": "on",
 "departZ": 1.645,
 "historyLki": true,
 "honorDrawReason": true,
 "horizon": 2,
 "infoAttach": true,
 "infoState": true,
 "leaf": "value",
 "margin": 0.0,
 "offBattlefieldLimits": true,
 "plans": {
  "defend": true,
  "defendCfg": {
   "counterDelta": "0.10",
   "defendMs": "6000",
   "defenseWorlds": "16",
   "prior": "classpath:/forge/ai/simulation/defend-prior-cobra-mush.json",
   "priorFillBasics": "true",
   "priorGain": "20",
   "readerPerGame": "true",
   "seenGain": "4",
   "sharedReader": "true",
   "tieBreakTurns": "1"
  },
  "lineChoices": "forge",
  "lineKeep": true,
  "planX": true,
  "recordMs": 1500,
  "replanOnEntry": true,
  "samples": 4,
  "solverCfg": {
   "breakOnCounter": "true",
   "budgetMs": "6000",
   "copyBackFace": "true",
   "cycles": "true",
   "engineAfterPlanFail": "true",
   "engineLongLoops": "true",
   "engineLoopBisect": "true",
   "engineLoopHoist": "true",
   "engineLoopReach": "true",
   "engineMoves": "true",
   "engineMs": "4000",
   "engineNodesPerSearch": "true",
   "engineOpen": "true",
   "engineRerun": "true",
   "engineTriggerTargets": "true",
   "engineVerify": "true",
   "evalReserveMs": "1500",
   "followPay": "true",
   "hideOwnLibrary": "true",
   "historyLki": "true",
   "landEntersProbe": "true",
   "lineUncapped": "true",
   "loopMarginal": "true",
   "offBattlefieldLimits": "true",
   "paySearch": "true",
   "planDiscardWall": "true",
   "planExpandCap": "true",
   "planOrder": "7",
   "probeSettle": "true",
   "readerPerGame": "true",
   "sourceColorsLive": "true",
   "sourceLeaves": "true",
   "sourceOutput": "true",
   "stackAbilityCopies": "true",
   "stackHistory": "true",
   "stalePlayerStatics": "true",
   "staleStatics": "true",
   "turnLimits": "true",
   "tutorCast": "true",
   "unknownWorlds": "true",
   "verifyReplan": "true"
  },
  "unified": true
 },
 "playout": "policy",
 "playoutSample": false,
 "reach": {
  "alpha": 0.85,
  "depart": 0.04,
  "gamma": 0.9,
  "gate": true,
  "maxNames": 40,
  "ms": 60,
  "nameNodes": 1000,
  "nameOnePass": true,
  "nodes": 2000,
  "oppBeta": 0.5,
  "oppDirect": true,
  "sig": true,
  "skipCapped": true
 },
 "readTimeoutMs": 8000,
 "stack": true,
 "stackAbilityCopies": true,
 "stackHistory": true,
 "threads": 8,
 "worlds": 8,
 "zeroX": "on"
}
            """;

    /** Bench-only keys, the host's and the seat's (obs: the bench seat's own schema; live names it): not compared. */
    static final Set<String> NOT_LIVE = Set.of("server", "policySha", "beliefCube", "beliefCubeSha256", "beliefSingleton",
            "cpuCapMs", "honorDrawReason", "infoAttach", "basicsByType", "threads", "obs");

    private static JsonObject comparable(final RlSearch.Config c) {
        final JsonObject o = c.toJson();
        for (String k : NOT_LIVE) {
            o.remove(k);
        }
        return o;
    }

    /** The bench spec as the live parse arms it: infoState goes into the plan solver and the defender, as strings. */
    private static RlSearch.Config bench() {
        final boolean basics = forge.ai.simulation.BeliefSampler.BASICS_BY_TYPE;
        try {
            final JsonObject o = JsonParser.parseString(SHIPA_JSON).getAsJsonObject();
            o.addProperty("server", "127.0.0.1:1");
            final RlSearch.Config c = RlSearch.Config.parse(o);
            for (String sub : new String[] {"solverCfg", "defendCfg"}) {
                c.plans.getAsJsonObject(sub).addProperty("infoState", "true");
                for (String g : GATED) {
                    c.plans.getAsJsonObject(sub).addProperty(g, "true"); // unkeyed in the candidate's jar
                }
            }
            return c;
        } finally {
            forge.ai.simulation.BeliefSampler.BASICS_BY_TYPE = basics; // the JSON key is process-wide
        }
    }

    @Test
    public void theShipStringGivesTheBenchSpecsConfig() {
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(HOST + TODAY + "," + SHIPA_ADDS);
        final JsonObject live = comparable(s.search), bench = comparable(bench());
        // the live-fix keys are the jar's defaults on the live line (forge.liveFixes) and explicit in the bench spec
        final JsonObject benchSolver = bench.getAsJsonObject("plans").getAsJsonObject("solverCfg");
        final JsonObject liveSolver = live.getAsJsonObject("plans").getAsJsonObject("solverCfg");
        for (String k : new String[] {"staleStatics", "turnLimits", "lineUncapped", "stalePlayerStatics", "offBattlefieldLimits",
                "copyBackFace", "stackAbilityCopies", "planExpandCap"}) {
            Assert.assertEquals(benchSolver.remove(k).getAsString(), "true", k);
            Assert.assertFalse(liveSolver.has(k), k + " is left to the jar's default");
        }
        Assert.assertTrue(forge.game.EngineFixes.liveFixes(), "the live line's default (this test runs without -Dforge.liveFixes=false)");
        final Set<String> keys = new TreeSet<>(live.keySet());
        keys.addAll(bench.keySet());
        // recordMs: the bench spec names the default
        Assert.assertEquals(bench.getAsJsonObject("plans").remove("recordMs").getAsLong(),
                PlanSearch.Spec.parse(s.search.plans).recordMs);
        Assert.assertFalse(live.getAsJsonObject("plans").has("recordMs"));
        for (String k : keys) {
            Assert.assertEquals(live.get(k), bench.get(k), "search key " + k); // JSON equality: key order does not matter
        }
        Assert.assertEquals(s.maxDecisions, 1000);
        Assert.assertEquals(s.loopGuard, 2);
        Assert.assertEquals(s.search.threads, 8);
        Assert.assertEquals(s.search.budgetMs, 8000L);
        // the solver sees what the string says
        final forge.ai.simulation.ForcedWinSolver.Config sc = forge.ai.simulation.ForcedWinSolver.Config.fromProps(
                k -> liveSolver.has(k) ? liveSolver.get(k).getAsString() : null);
        Assert.assertTrue(sc.breakOnCounter && sc.tutorCast && sc.sourceLeaves && sc.landEntersProbe && sc.hideOwnLibrary);
        Assert.assertFalse(sc.hiddenOrderLegacy || sc.engineFocus || sc.engineLoopCredit, "leak fix on; focus and loop credit off");
        Assert.assertTrue(sc.staleStatics && sc.turnLimits && sc.lineUncapped && sc.planExpandCap, "live fixes by default");
        Assert.assertTrue(sc.passBlockers, "the gated lane behaviour is on in the ship string");
    }

    @Test
    public void todaysStringIsUnchanged() {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(HOST + TODAY).search;
        Assert.assertNull(c.choiceScorer);
        Assert.assertNull(c.reach);
        final forge.ai.simulation.ForcedWinSolver.Config today = forge.ai.simulation.ForcedWinSolver.Config.fromProps(
                k -> c.plans.getAsJsonObject("solverCfg").has(k) ? c.plans.getAsJsonObject("solverCfg").get(k).getAsString() : null);
        Assert.assertFalse(today.passBlockers, "the gated lane behaviour stays off under today's string");
        Assert.assertFalse(c.anytime || c.stack || c.stackHistory || c.historyLki || c.blockSampleSeed || c.infoAttach);
        final JsonObject plans = c.plans;
        Assert.assertEquals(new TreeSet<>(plans.keySet()), new TreeSet<>(Set.of("samples", "solverCfg", "defend", "defendCfg")));
        Assert.assertEquals(new TreeSet<>(plans.getAsJsonObject("solverCfg").keySet()),
                new TreeSet<>(Set.of("budgetMs", "cycles", "engineMs", "evalReserveMs", "hideOwnLibrary", "infoState")));
        Assert.assertEquals(new TreeSet<>(plans.getAsJsonObject("defendCfg").keySet()), new TreeSet<>(Set.of("prior", "priorGain",
                "tieBreakTurns", "defendMs", "sharedReader", "priorFillBasics", "defenseWorlds", "counterDelta", "seenGain", "infoState")));
        // the live-fix look-ahead keys keep the jar's default
        Assert.assertEquals(c.beliefStatics, forge.ai.simulation.LookaheadSearch.LIVE_BELIEF_STATICS);
        Assert.assertEquals(c.copyBackFace, forge.ai.simulation.LookaheadSearch.LIVE_COPY_BACK_FACE);
        Assert.assertEquals(c.stackAbilityCopies, forge.ai.simulation.LookaheadSearch.LIVE_STACK_ABILITY_COPIES);
    }

    @Test
    public void everyAddedKeyParsesAloneAndAGroupedKeyTwiceIsRefused() {
        for (String kv : SHIPA_ADDS.split(",")) {
            RlLiveSeat.Spec.parse(HOST + TODAY + "," + kv);
            if (kv.startsWith("plans.") || kv.startsWith("choiceScorer.") || kv.startsWith("reach.")) {
                refused(TODAY + "," + kv + "," + kv, kv + " twice"); // (a plain look-ahead key twice: the last wins, as before)
            }
        }
        RlLiveSeat.Spec.parse(HOST + TODAY + ",plans.solverCfg.hiddenOrderLegacy=true");
        RlLiveSeat.Spec.parse(HOST + TODAY + ",stackAbilityCopies=off,copyBackFace=off,beliefRememberedHand=false");
    }

    @Test
    public void badValuesAndOtherKeysAreRefused() {
        refused(TODAY + ",choiceScorer.noSuchKey=1", "an unknown card-scorer key");
        refused(TODAY + ",choiceScorer.families=TARGETS", "a family the card scorer does not answer");
        refused(TODAY + ",reach.noSuchKey=1", "an unknown reach key");
        refused(TODAY + ",plans.solverCfg.breakOnCounter=1", "a flag that is not true|false");
        refused(TODAY + ",plans.solverCfg.planOrder=true", "a number that is not a number");
        refused(TODAY + ",plans.solverCfg.engineFocus=true", "engineFocus is not a live key");
        refused(TODAY + ",plans.solverCfg.engineLoopCredit=true", "engineLoopCredit is not a live key");
        refused(TODAY + ",plans.lineChoices=nonsense", "an unknown lineChoices value");
        refused(TODAY + ",plans.planX=7", "a plan flag that is not true|false");
        refused(TODAY + ",stack=1", "a flag that is not true|false");
        refused(TODAY + ",plans.defendCfg.readerPerGame=yes", "a defend flag that is not true|false");
        refused(TODAY + ",workUnits=true", "the bench clock is not a live key");
    }

    private static void refused(final String keys, final String what) {
        try {
            RlLiveSeat.Spec.parse(HOST + keys);
        } catch (IllegalArgumentException e) {
            return;
        }
        Assert.fail("accepted: " + what);
    }

    /** Guards the embedded spec against drift: the keys this test was written for. */
    @Test
    public void theEmbeddedSpecIsShipA() {
        final JsonObject o = JsonParser.parseString(SHIPA_JSON).getAsJsonObject();
        Assert.assertEquals(o.keySet().size(), 43);
        Assert.assertEquals(o.getAsJsonObject("choiceScorer").keySet().size(), 21);
        Assert.assertEquals(o.getAsJsonObject("reach").keySet().size(), 13);
        Assert.assertEquals(o.getAsJsonObject("plans").getAsJsonObject("solverCfg").keySet().size(), 41);
        int n = 0;
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("plans").getAsJsonObject("solverCfg").entrySet()) {
            n += "true".equals(e.getValue().getAsString()) ? 1 : 0;
        }
        Assert.assertEquals(n, 37, "solver flags on (the rest: budgetMs, engineMs, evalReserveMs, planOrder)");
    }
}
