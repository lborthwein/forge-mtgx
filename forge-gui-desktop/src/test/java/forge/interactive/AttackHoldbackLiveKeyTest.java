package forge.interactive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.bench.rl.RlLiveSeat;

/**
 * attack-holdback-1118: the knownHand and attacks keys in the policy seat's search spec ({@link RlLiveSeat.Spec#parse}).
 * Today's live string (mtgx tests/forge/fixtures/policy-search-live-deadpump-1117.txt, its card index a temp file) with
 * the keys absent parses to the same search JSON, byte for byte (its sha256 pinned from the base jar
 * forge-lethalblock-584dffccf2a), and so with them explicitly off; on arms both. The K8 look-ahead spec has neither key.
 */
public class AttackHoldbackLiveKeyTest extends forge.ai.AITest {

    /** The live policy-search string of 2026-11-17 after server, policySha and cardIndex. */
    static final String LIVE_1117_REST = ""
            + "budgetMs=10000,threads=20,obs=2,maxDecisions=1000,loopGuard=2,plans.samples=4,"
            + "plans.solverCfg.budgetMs=6000,plans.solverCfg.cycles=true,plans.solverCfg.engineMs=4000,"
            + "plans.solverCfg.evalReserveMs=1500,plans.solverCfg.hideOwnLibrary=true,blocks=1,plans.defend=true,"
            + "plans.defendCfg.prior=jar,plans.defendCfg.priorGain=20,plans.defendCfg.tieBreakTurns=1,"
            + "plans.defendCfg.defendMs=6000,plans.defendCfg.sharedReader=true,"
            + "plans.defendCfg.priorFillBasics=true,plans.defendCfg.defenseWorlds=16,"
            + "plans.defendCfg.counterDelta=0.10,plans.defendCfg.seenGain=4,infoState=on,anytime=true,"
            + "blockSampleSeed=true,choiceScorer.budgetMs=10000,choiceScorer.families=CARDS,"
            + "choiceScorer.gfActive=3,choiceScorer.gfAskNodes=60000,choiceScorer.gfExtra=2,"
            + "choiceScorer.gfGamma=0.8,choiceScorer.gfHorizon=5,choiceScorer.gfLambda=0.6,"
            + "choiceScorer.gfLands=true,choiceScorer.gfMs=1000,choiceScorer.gfNodes=1500,choiceScorer.gfSamples=0,"
            + "choiceScorer.margin=0.02,choiceScorer.reachAlpha=0.85,choiceScorer.reachNameNodes=1000,"
            + "choiceScorer.reachOnePass=true,choiceScorer.reachSig=true,choiceScorer.reachSkipCapped=true,"
            + "choiceScorer.screenMs=1000,choiceScorer.screenNodes=2000,choiceScorer.seqs=2,historyLki=on,"
            + "plans.defendCfg.readerPerGame=true,plans.lineChoices=forge,plans.lineKeep=true,plans.planX=true,"
            + "plans.replanOnEntry=true,plans.solverCfg.breakOnCounter=true,"
            + "plans.solverCfg.engineAfterPlanFail=true,plans.solverCfg.engineLongLoops=true,"
            + "plans.solverCfg.engineLoopBisect=true,plans.solverCfg.engineLoopHoist=true,"
            + "plans.solverCfg.engineLoopReach=true,plans.solverCfg.engineMoves=true,"
            + "plans.solverCfg.engineNodesPerSearch=true,plans.solverCfg.engineOpen=true,"
            + "plans.solverCfg.engineRerun=true,plans.solverCfg.engineTriggerTargets=true,"
            + "plans.solverCfg.engineVerify=true,plans.solverCfg.followPay=true,plans.solverCfg.historyLki=true,"
            + "plans.solverCfg.landEntersProbe=true,plans.solverCfg.loopMarginal=true,"
            + "plans.solverCfg.paySearch=true,plans.solverCfg.planDiscardWall=true,plans.solverCfg.planOrder=7,"
            + "plans.solverCfg.probeSettle=true,plans.solverCfg.readerPerGame=true,"
            + "plans.solverCfg.sourceColorsLive=true,plans.solverCfg.sourceLeaves=true,"
            + "plans.solverCfg.sourceOutput=true,plans.solverCfg.stackHistory=true,plans.solverCfg.tutorCast=true,"
            + "plans.solverCfg.unknownWorlds=true,plans.solverCfg.verifyReplan=true,plans.unified=true,"
            + "reach.alpha=0.85,reach.depart=0.04,reach.gamma=0.9,reach.gate=true,reach.maxNames=40,reach.ms=1000,"
            + "reach.nameNodes=1000,reach.nameOnePass=true,reach.nodes=2000,reach.oppBeta=0.5,reach.oppDirect=true,"
            + "reach.sig=true,reach.skipCapped=true,stack=true,stackHistory=true,plans.solverCfg.passBlockers=true,"
            + "plans.defendCfg.passBlockers=true,deadAltCost=on,plans.solverCfg.planLoops=true,"
            + "plans.solverCfg.engineModeChoice=true,choiceScorer.reachDiscards=true,choiceScorer.redealOwn=4,"
            + "plans.lookFloorMs=6000,choiceScorer.departScored=1,choiceScorer.departFloor=0.1,svcMemo=1,"
            + "opponent=policy,copyCast=1,copyDungeon=1,copyStaleRefs=1,copyTriggers=1,"
            + "plans.solverCfg.copyDungeon=true,reach.verify=sampled,choiceScorer.redealOpp=true,"
            + "choiceScorer.redealOwnOrder=true,deadPump=on";

    /** sha256 of the search JSON of {@link #live} with no extra key, on the base jar forge-lethalblock-584dffccf2a. */
    static final String BASE_JSON_SHA256 = "c43a6f204b2b22270f963afa6609f569b61a402f8bb4efa18d7c97958ea6af14";

    static String live(final String extra) throws Exception {
        final java.nio.file.Path index = java.nio.file.Files.createTempFile("ahb-index-", ".tsv");
        return "server=127.0.0.1:5420,policySha=3b6747940fe2d6d4e0921fd30d90c1bfa7bb27434ebd83a08ff9d3bb51b39071,cardIndex="
                + index + "," + LIVE_1117_REST + (extra.isEmpty() ? "" : "," + extra);
    }

    static String sha256(final String s) throws Exception {
        final byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        final StringBuilder sb = new StringBuilder();
        for (byte b : h) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Keys absent: the live string's search JSON is the base jar's, byte for byte. */
    @Test
    public void theLiveStringParsesAsOnTheBaseJar() throws Exception {
        final String json = RlLiveSeat.Spec.parse(live("")).search.toJson().toString();
        final String sha = sha256(json);
        System.out.println("[attack-holdback-identity] live 1117 search JSON sha256 " + sha + " (" + json.length() + " chars)");
        Assert.assertEquals(sha, BASE_JSON_SHA256, "the keys absent leave the live search JSON unchanged");
    }

    @Test
    public void thePolicySeatSpecKeysAreOffUnlessSet() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(live(""));
        Assert.assertEquals(off.search.attacks, 0);
        Assert.assertFalse(off.search.knownHand);
        final RlLiveSeat.Spec explicitOff = RlLiveSeat.Spec.parse(live("attacks=0,knownHand=off"));
        Assert.assertEquals(explicitOff.search.toJson().toString(), off.search.toJson().toString());
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(live("attacks=1,knownHand=on"));
        Assert.assertEquals(on.search.attacks, 1);
        Assert.assertTrue(on.search.knownHand);
        Assert.assertEquals(on.search.toJson().get("attacks").getAsInt(), 1);
        Assert.assertTrue(on.search.toJson().get("knownHand").getAsBoolean());
        for (String bad : new String[] {"attacks=2", "attacks=on", "knownHand=true", "knownHand=1", ""}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(live(bad.isEmpty()
                    ? "knownHand=" : bad)));
        }
    }

    /** The K8 parser ignores keys it does not know: neither key arms anything there or changes its JSON. */
    @Test
    public void theK8SpecHasNoSuchKeys() {
        final InteractiveMain.ParsedLookahead k8 = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        final InteractiveMain.ParsedLookahead with = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8
                + ",knownHand=on,attacks=1");
        Assert.assertFalse(with.config.knownHand);
        Assert.assertEquals(with.config.toJson().toString(), k8.config.toJson().toString());
    }
}
