package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-bundle8-1117: the live policySearch keys {@code mullKeep=on|off} and {@code alwaysKeep=on|off}. On, the live
 * string carries the same RlSearch config as the bench spec's {@code "mullKeep": {}} / {@code "alwaysKeep": true} and
 * answers the same mulligan asks; absent or off, the config (and its JSON) is unchanged. Card names are test data only.
 */
public class MullKeepLiveKeyTest extends AITest {
    /** A live string as the gate's arms carry (policy, belief, budget, plans, scorer); the keys under test appended. */
    private static final String BASE = "server=h:1,cardIndex=x,policySha=" + "a".repeat(64) + ",obs=2,budgetMs=8000,"
            + "threads=8,maxDecisions=4000,loopGuard=3,svcMemo=on,infoState=on,plans.samples=4,"
            + "plans.solverCfg.hideOwnLibrary=true,choiceScorer.budgetMs=1500";

    /** The bar-4 gate's CTL arm (live 5dedba3e): the full live string, cardIndex and policy as shipped. */
    private static final String LIVE_CTL = "server=127.0.0.1:5420,policySha=3b6747940fe2d6d4e0921fd30d90c1bfa7bb27434ebd83a08ff9d3bb51b39071"
            + ",cardIndex=/Users/channel/mtgx-data/rl/data/card-index.tsv,budgetMs=10000,threads=20,obs=2"
            + ",maxDecisions=1000,loopGuard=2,plans.samples=4,plans.solverCfg.budgetMs=6000"
            + ",plans.solverCfg.cycles=true,plans.solverCfg.engineMs=4000,plans.solverCfg.evalReserveMs=1500"
            + ",plans.solverCfg.hideOwnLibrary=true,blocks=1,plans.defend=true,plans.defendCfg.prior=jar"
            + ",plans.defendCfg.priorGain=20,plans.defendCfg.tieBreakTurns=1,plans.defendCfg.defendMs=6000"
            + ",plans.defendCfg.sharedReader=true,plans.defendCfg.priorFillBasics=true"
            + ",plans.defendCfg.defenseWorlds=16,plans.defendCfg.counterDelta=0.10,plans.defendCfg.seenGain=4"
            + ",infoState=on,anytime=true,blockSampleSeed=true,choiceScorer.budgetMs=10000"
            + ",choiceScorer.families=CARDS,choiceScorer.gfActive=3,choiceScorer.gfAskNodes=60000"
            + ",choiceScorer.gfExtra=2,choiceScorer.gfGamma=0.8,choiceScorer.gfHorizon=5,choiceScorer.gfLambda=0.6"
            + ",choiceScorer.gfLands=true,choiceScorer.gfMs=1000,choiceScorer.gfNodes=1500,choiceScorer.gfSamples=0"
            + ",choiceScorer.margin=0.02,choiceScorer.reachAlpha=0.85,choiceScorer.reachNameNodes=1000"
            + ",choiceScorer.reachOnePass=true,choiceScorer.reachSig=true,choiceScorer.reachSkipCapped=true"
            + ",choiceScorer.screenMs=1000,choiceScorer.screenNodes=2000,choiceScorer.seqs=2,historyLki=on"
            + ",plans.defendCfg.readerPerGame=true,plans.lineChoices=forge,plans.lineKeep=true,plans.planX=true"
            + ",plans.replanOnEntry=true,plans.solverCfg.breakOnCounter=true"
            + ",plans.solverCfg.engineAfterPlanFail=true,plans.solverCfg.engineLongLoops=true"
            + ",plans.solverCfg.engineLoopBisect=true,plans.solverCfg.engineLoopHoist=true"
            + ",plans.solverCfg.engineLoopReach=true,plans.solverCfg.engineMoves=true"
            + ",plans.solverCfg.engineNodesPerSearch=true,plans.solverCfg.engineOpen=true"
            + ",plans.solverCfg.engineRerun=true,plans.solverCfg.engineTriggerTargets=true"
            + ",plans.solverCfg.engineVerify=true,plans.solverCfg.followPay=true,plans.solverCfg.historyLki=true"
            + ",plans.solverCfg.landEntersProbe=true,plans.solverCfg.loopMarginal=true"
            + ",plans.solverCfg.paySearch=true,plans.solverCfg.planDiscardWall=true,plans.solverCfg.planOrder=7"
            + ",plans.solverCfg.probeSettle=true,plans.solverCfg.readerPerGame=true"
            + ",plans.solverCfg.sourceColorsLive=true,plans.solverCfg.sourceLeaves=true"
            + ",plans.solverCfg.sourceOutput=true,plans.solverCfg.stackHistory=true,plans.solverCfg.tutorCast=true"
            + ",plans.solverCfg.unknownWorlds=true,plans.solverCfg.verifyReplan=true,plans.unified=true"
            + ",reach.alpha=0.85,reach.depart=0.04,reach.gamma=0.9,reach.gate=true,reach.maxNames=40,reach.ms=1000"
            + ",reach.nameNodes=1000,reach.nameOnePass=true,reach.nodes=2000,reach.oppBeta=0.5,reach.oppDirect=true"
            + ",reach.sig=true,reach.skipCapped=true,stack=true,stackHistory=true,plans.solverCfg.passBlockers=true"
            + ",plans.defendCfg.passBlockers=true,deadAltCost=on,plans.solverCfg.planLoops=true"
            + ",plans.solverCfg.engineModeChoice=true,choiceScorer.reachDiscards=true,choiceScorer.redealOwn=4"
            + ",plans.lookFloorMs=6000,choiceScorer.departScored=1,choiceScorer.departFloor=0.1,svcMemo=1"
            + ",opponent=policy,copyCast=1,copyDungeon=1,copyStaleRefs=1,copyTriggers=1"
            + ",plans.solverCfg.copyDungeon=true,reach.verify=sampled,choiceScorer.redealOpp=true"
            + ",choiceScorer.redealOwnOrder=true";

    private static RlLiveSeat.Spec live(final String extra) {
        return RlLiveSeat.Spec.parse(BASE + extra);
    }

    /** The bench route: the spec JSON through RlSearch.Config.parse (toJson carries no service address). */
    private static RlSearch.Config bench(final JsonObject spec) {
        final JsonObject o = spec.deepCopy();
        o.addProperty("server", "h:1");
        return RlSearch.Config.parse(o);
    }

    @Test
    public void mullKeepOnIsTheBenchSpec() {
        final JsonObject off = live("").search.toJson();
        final RlSearch.Config on = live(",mullKeep=on").search;
        Assert.assertNotNull(on.mullKeep);
        Assert.assertEquals(on.mullKeep.size(), 0);
        Assert.assertFalse(on.alwaysKeep);
        // the live config = the keys-off live config plus "mullKeep": {}, and nothing else
        final JsonObject want = off.deepCopy();
        want.add("mullKeep", new JsonObject());
        Assert.assertEquals(on.toJson(), want);
        // the bench spec with "mullKeep": {} parses to the same config
        Assert.assertEquals(bench(want).toJson(), on.toJson());
        Assert.assertEquals(bench(want).mullKeep, on.mullKeep);
        // "true" is the same key
        Assert.assertEquals(live(",mullKeep=true").search.toJson(), on.toJson());
    }

    @Test
    public void fullLiveStringMullKeepOnIsTheBenchSpec() {
        final JsonObject off = RlLiveSeat.Spec.parse(LIVE_CTL).search.toJson();
        final RlSearch.Config on = RlLiveSeat.Spec.parse(LIVE_CTL + ",mullKeep=on").search;
        final JsonObject want = off.deepCopy();
        want.add("mullKeep", new JsonObject());
        Assert.assertEquals(on.toJson(), want);
        Assert.assertEquals(bench(want).toJson(), on.toJson());
        Assert.assertEquals(RlLiveSeat.Spec.parse(LIVE_CTL + ",mullKeep=off").search.toJson().toString(), off.toString());
    }

    @Test
    public void alwaysKeepOnIsTheBenchSpec() {
        final JsonObject off = live("").search.toJson();
        final RlSearch.Config on = live(",alwaysKeep=on").search;
        Assert.assertTrue(on.alwaysKeep);
        Assert.assertNull(on.mullKeep);
        final JsonObject want = off.deepCopy();
        want.addProperty("alwaysKeep", true);
        Assert.assertEquals(on.toJson(), want);
        Assert.assertEquals(bench(want).toJson(), on.toJson());
        Assert.assertTrue(bench(want).alwaysKeep);
    }

    @Test
    public void absentOrOffLeavesTheConfigUnchanged() {
        final RlSearch.Config absent = live("").search;
        Assert.assertNull(absent.mullKeep);
        Assert.assertFalse(absent.alwaysKeep);
        final String json = absent.toJson().toString();
        Assert.assertFalse(absent.toJson().has("mullKeep"));
        Assert.assertFalse(absent.toJson().has("alwaysKeep"));
        for (String extra : new String[] {",mullKeep=off", ",mullKeep=false", ",alwaysKeep=off", ",alwaysKeep=false",
                ",mullKeep=off,alwaysKeep=off"}) {
            final RlSearch.Config c = live(extra).search;
            Assert.assertNull(c.mullKeep, extra);
            Assert.assertFalse(c.alwaysKeep, extra);
            Assert.assertEquals(c.toJson().toString(), json, extra); // byte-identical spec JSON
        }
        // the bench's keys-off spec is the same config too
        Assert.assertEquals(bench(absent.toJson()).toJson().toString(), json);
        // a keys-off search does not ask at mulligans (the policy answers them, as before)
        Assert.assertFalse(new RlSearch(absent, 1L, 2L, null, null, "jar", "t", 2).choiceAsk(RlSchema.F_MULLIGAN));
        Assert.assertTrue(new RlSearch(live(",mullKeep=on").search, 1L, 2L, null, null, "jar", "t", 2)
                .choiceAsk(RlSchema.F_MULLIGAN));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void badMullKeepValueIsRefused() {
        live(",mullKeep=yes");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void badAlwaysKeepValueIsRefused() {
        live(",alwaysKeep=1");
    }

    // ---- the mulligan decision on fixed hands

    /** A game whose seat 1 holds {@code hand} (seven cards) over a 30-card library of 10 lands. */
    private Game game(final String... hand) {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(1);
        for (int i = 0; i < 10; i++) {
            addCardToZone("Island", me, ZoneType.Library);
        }
        for (int i = 0; i < 20; i++) {
            addCardToZone("Counterspell", me, ZoneType.Library);
        }
        for (String n : hand) {
            addCardToZone(n, me, ZoneType.Hand);
        }
        return g;
    }

    private static RlCandidates.Menu keepMenu() {
        final RlCandidates.Menu m = new RlCandidates.Menu();
        m.family = RlSchema.F_MULLIGAN;
        m.mode = RlSchema.M_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        for (int k : new int[] {RlSchema.K_KEEP, RlSchema.K_MULLIGAN}) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = k;
            m.cands.add(c);
        }
        return m;
    }

    private static short[] ask(final RlSearch.Config cfg, final Game g, final short[] policy) {
        final RlSearch s = new RlSearch(cfg, 7L, 11L, null, null, "jar", "t", 2);
        final Player me = g.getPlayers().get(1);
        final RlCandidates.Menu m = keepMenu();
        Assert.assertTrue(s.choiceAsk(m.family));
        final RlWire.Decide frame = new RlWire.Decide();
        frame.decIdx = 0;
        frame.seat = 1;
        frame.family = m.family;
        return s.decideChoice(g, me, m, frame, policy);
    }

    private static final short[] KEEP = {0}, MULL = {1};

    /** Live mullKeep=on and the bench "mullKeep": {} give the same answer; it is {@code want} (null = the policy's). */
    private void same(final short[] want, final short[] policy, final String... hand) {
        final RlSearch.Config lv = live(",mullKeep=on").search;
        final JsonObject spec = live("").search.toJson();
        spec.add("mullKeep", new JsonObject());
        final RlSearch.Config bn = bench(spec);
        final short[] a = ask(lv, game(hand), policy);
        final short[] b = ask(bn, game(hand), policy);
        Assert.assertEquals(a, b, String.join(";", hand));
        Assert.assertEquals(a, want, String.join(";", hand));
    }

    @Test
    public void sameDecisionOnFixedHands() {
        // three lands, four spells: the stock rule keeps; the policy's mulligan is turned into a keep
        same(KEEP, MULL, "Island", "Island", "Swamp", "Brainstorm", "Ponder", "Dark Ritual", "Doomsday");
        // the same hand, the policy keeps: its answer stands (never a keep turned into a mulligan)
        same(null, KEEP, "Island", "Island", "Swamp", "Brainstorm", "Ponder", "Dark Ritual", "Doomsday");
        // one land: the stock rule mulligans too; the policy's mulligan stands
        same(null, MULL, "Island", "Brainstorm", "Ponder", "Dark Ritual", "Doomsday", "Counterspell", "Force of Will");
        // seven lands (flood): the stock rule mulligans too; the policy's mulligan stands
        same(null, MULL, "Island", "Island", "Island", "Swamp", "Swamp", "Swamp", "Island");
    }

    @Test
    public void alwaysKeepKeepsEveryHandLiveAsOnTheBench() {
        final JsonObject spec = live("").search.toJson();
        spec.addProperty("alwaysKeep", true);
        for (String[] hand : new String[][] {
                {"Island", "Brainstorm", "Ponder", "Dark Ritual", "Doomsday", "Counterspell", "Force of Will"},
                {"Island", "Island", "Island", "Swamp", "Swamp", "Swamp", "Island"}}) {
            final short[] a = ask(live(",alwaysKeep=on").search, game(hand), MULL);
            final short[] b = ask(bench(spec), game(hand), MULL);
            Assert.assertEquals(a, b);
            Assert.assertEquals(a, KEEP);
        }
    }
}
