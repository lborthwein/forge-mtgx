package forge.ai.simulation;

import java.util.HashMap;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.util.IdScope;

/**
 * Lane bvw-step1-build-1024: counter budgets (budget=counter) read no wall clock, and the ms path's specs and the
 * generator's recorded spec are unchanged byte for byte. Fixture positions only; none is part of a corpus.
 */
public class BvwCounterBudgetTest extends SimulationTest {

    /** The frozen step-0 pilot label spec (astra/redraw-validation-1023, cfg/pilot-label-spec.json). */
    static final String PILOT_SPEC = "{\"lossCfg\":{\"budgetMs\":\"2000\",\"cycles\":\"true\",\"engineMs\":\"750\","
            + "\"evalReserveMs\":\"500\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"500\"},\"lossWallMs\":12000,"
            + "\"out\":\"/x\",\"redraw\":\"belief\",\"seed\":747021000,\"solverCfg\":{\"budgetMs\":\"4000\",\"cycles\":"
            + "\"true\",\"engineMs\":\"2000\",\"evalReserveMs\":\"1000\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":"
            + "\"1000\"},\"turns\":3,\"worlds\":8}";
    /** Its spec_sha in every pilot label row (jar 30dae214). */
    static final String PILOT_SPEC_SHA = "d78b0293295ca7e2c39fbfa41f2a18b450827e35b0852d7b4232d07a7d56f72e";
    /** The generator's solverCfg as every step-0 pilot tape records it (jar 30dae214, profile HonestForcedWin). */
    static final String PILOT_GENERATOR_CFG = "{\"act\":true,\"adversary\":true,\"budgetMs\":8000,\"counterDelta\":0.25,"
            + "\"cycles\":true,\"defendMs\":0,\"defense\":false,\"defenseWorlds\":4,\"engine\":true,\"engineMs\":4000,"
            + "\"engineNodes\":250,\"evalReserveMs\":2000,\"exact\":false,\"forced\":true,\"frameMemory\":false,"
            + "\"hideOwnLibrary\":true,\"hold\":false,\"honestProbes\":true,\"horizon\":2,\"likely\":false,"
            + "\"likelyCapTurns\":16,\"likelyReserveMs\":2000,\"likelyTop\":2,\"likelyWorlds\":4,\"maxSteps\":4000,"
            + "\"nodes\":30000,\"paySearch\":false,\"planMs\":1500,\"seed\":20261012,\"sharedReader\":false,"
            + "\"threatExtraTurns\":0,\"threshold\":1.0,\"tieBreakTurns\":0,\"top\":3,\"wallGraceMs\":2000,\"worlds\":8}";

    static ForcedWinSolver.Config fromSpec(final String spec) {
        final Map<String, String> kv = new HashMap<>();
        for (String part : spec.split(",")) {
            final int eq = part.indexOf('=');
            if (eq > 0) kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(kv::get);
        c.forced = true;
        return c;
    }

    static final String GENERATOR_SPEC = "hideOwnLibrary=true,defense=false,worlds=8,cycles=true,threshold=1.0";

    @Test
    public void theMsPathSpecsAreUnchanged() {
        final JsonObject o = JsonParser.parseString(PILOT_SPEC).getAsJsonObject();
        final HonestLabel.Spec s = HonestLabel.Spec.parse(o);
        Assert.assertEquals(s.sha(), PILOT_SPEC_SHA, "the frozen pilot spec's sha moved");
        Assert.assertFalse(s.counterBudgets());
        final JsonObject m = o.deepCopy();
        m.addProperty("meter", true);
        Assert.assertNotEquals(HonestLabel.Spec.parse(m).sha(), PILOT_SPEC_SHA, "a metered spec is its own spec");
        final JsonObject off = o.deepCopy();
        off.addProperty("meter", false);
        Assert.assertEquals(HonestLabel.Spec.parse(off).sha(), PILOT_SPEC_SHA, "meter=false is the frozen spec");
        // the generator's recorded spec: the HonestForcedWin profile's config, as the pilot tapes hold it
        final Game g = initAndCreateGame();
        g.AI_TIMEOUT = 600; // review #3: a counter generator refuses a binding Forge AI timeout
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver gen = HonestGenerator.solver(me, fromSpec(GENERATOR_SPEC));
        Assert.assertEquals(HonestGenerator.solverCfgJson(gen.config()), JsonParser.parseString(PILOT_GENERATOR_CFG));
        final ForcedWinSolver counted = HonestGenerator.solver(me, fromSpec(GENERATOR_SPEC
                + ",budget=counter,engineSteps=900,evalSteps=5000,adoptAttack=true"));
        final JsonObject cj = HonestGenerator.solverCfgJson(counted.config());
        Assert.assertTrue(cj.get("counter").getAsBoolean());
        Assert.assertEquals(cj.get("engineSteps").getAsInt(), 900);
        Assert.assertEquals(cj.get("evalSteps").getAsInt(), 5000);
        Assert.assertTrue(cj.get("adoptAttack").getAsBoolean());
        Assert.assertTrue(AiProfileUtil.isKnownProfile(AiProfileUtil.HONEST_FORCED_WIN_PROFILE));
        // the step-1 lineage's counted generator profile: the same seat on counter budgets with adoptAttack
        Assert.assertTrue(AiProfileUtil.isKnownProfile(AiProfileUtil.HONEST_FORCED_WIN_COUNTED_PROFILE));
        final forge.ai.LobbyPlayerAi lp = new forge.ai.LobbyPlayerAi("c", null);
        lp.setAiProfile(AiProfileUtil.HONEST_FORCED_WIN_COUNTED_PROFILE);
        Assert.assertEquals(AiProfileUtil.getAIProp(lp, forge.ai.AiProps.FORCED_WIN_HONEST), "true");
        final String spec = AiProfileUtil.getAIProp(lp, forge.ai.AiProps.FORCED_WIN_SPEC);
        Assert.assertEquals(spec, AiProfileUtil.HONEST_FORCED_WIN_COUNTED_SPEC);
        final ForcedWinSolver.Config cc = HonestGenerator.solver(me, fromSpec(spec)).config();
        Assert.assertTrue(cc.counter && cc.adoptAttack && cc.engineSteps > 0 && cc.evalSteps > 0);
        final forge.ai.LobbyPlayerAi old = new forge.ai.LobbyPlayerAi("o", null);
        old.setAiProfile(AiProfileUtil.HONEST_FORCED_WIN_PROFILE);
        Assert.assertEquals(AiProfileUtil.getAIProp(old, forge.ai.AiProps.FORCED_WIN_SPEC), GENERATOR_SPEC,
                "the step-0 generator profile is unchanged");
    }

    @Test
    public void counterBudgetsRefuseTheWallClockFeatures() {
        Assert.assertThrows(IllegalArgumentException.class, () -> fromSpec("budget=counter")); // defense defaults to on
        Assert.assertThrows(IllegalArgumentException.class, () -> fromSpec("budget=counter,defense=false,likely=true"));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> fromSpec("budget=counter,defense=false,defendMs=100"));
        Assert.assertThrows(IllegalArgumentException.class, () -> fromSpec("budget=wall,defense=false"));
        Assert.assertThrows(IllegalArgumentException.class, () -> fromSpec("budget=counter,defense=false,evalSteps=-1"));
        Assert.assertTrue(fromSpec("budget=counter,defense=false").counter);
        Assert.assertFalse(fromSpec("budget=ms,defense=false").counter);
        Assert.assertFalse(fromSpec("defense=false").counter);
        final JsonObject o = JsonParser.parseString(PILOT_SPEC).getAsJsonObject();
        final JsonObject one = o.deepCopy();
        one.getAsJsonObject("solverCfg").addProperty("budget", "counter");
        Assert.assertThrows(IllegalArgumentException.class, () -> HonestLabel.Spec.parse(one));
        final JsonObject both = one.deepCopy();
        both.getAsJsonObject("lossCfg").addProperty("budget", "counter");
        Assert.assertThrows(IllegalArgumentException.class, () -> HonestLabel.Spec.parse(both)); // lossWallMs 12000
        both.addProperty("lossWallMs", 0);
        // review #3: a counter spec pins its Forge AI settings (in its sha), and refuses a run that differs
        Assert.assertThrows(IllegalArgumentException.class, () -> HonestLabel.Spec.parse(both));
        both.addProperty("sequentialAi", true);
        Assert.assertThrows(IllegalArgumentException.class, () -> HonestLabel.Spec.parse(both));
        both.addProperty("aiTimeoutSec", 300);
        Assert.assertThrows(IllegalArgumentException.class, () -> HonestLabel.Spec.parse(both));
        both.addProperty("aiTimeoutSec", 600);
        final HonestLabel.Spec cs = HonestLabel.Spec.parse(both);
        Assert.assertTrue(cs.counterBudgets());
        Assert.assertNull(cs.runtimeRefusal(600), "this JVM runs -Dforge.bench.sequentialAi=true");
        Assert.assertNotNull(cs.runtimeRefusal(5));
        Assert.assertNotNull(cs.runtimeRefusal(900), "the pinned value, not any larger one");
        final JsonObject other = both.deepCopy();
        other.addProperty("aiTimeoutSec", 900);
        Assert.assertNotEquals(HonestLabel.Spec.parse(other).sha(), cs.sha(), "the settings are in the sha");
        Assert.assertNull(ForcedWinSolver.counterRuntimeRefusal(600));
        Assert.assertNotNull(ForcedWinSolver.counterRuntimeRefusal(5));
        // the generator refuses a counter config in a game whose Forge AI timeout binds
        final Game g5 = initAndCreateGame();
        g5.AI_TIMEOUT = 5;
        Assert.assertThrows(IllegalArgumentException.class, () -> HonestGenerator.solver(g5.getPlayers().get(0),
                fromSpec(GENERATOR_SPEC + ",budget=counter")));
    }

    /** A counter spec whose ms keys are {@code ms} (they must not matter), metered. */
    static JsonObject counterSpec(final String ms) {
        final JsonObject o = new JsonObject();
        o.addProperty("redraw", "belief");
        o.addProperty("worlds", 8);
        o.addProperty("seed", 7);
        o.addProperty("meter", true);
        o.addProperty("lossWallMs", 0);
        o.addProperty("sequentialAi", true);
        o.addProperty("aiTimeoutSec", 600);
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            final JsonObject c = new JsonObject();
            c.addProperty("hideOwnLibrary", "true");
            c.addProperty("cycles", "true");
            c.addProperty("budget", "counter");
            c.addProperty("budgetMs", ms);
            c.addProperty("engineMs", ms);
            c.addProperty("planMs", ms);
            c.addProperty("evalReserveMs", "0");
            c.addProperty("wallGraceMs", "0");
            o.add(k, c);
        }
        return o;
    }

    static JsonObject msSpec(final String ms) {
        final JsonObject o = counterSpec(ms);
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            o.getAsJsonObject(k).remove("budget");
        }
        o.remove("sequentialAi");
        o.remove("aiTimeoutSec");
        return o;
    }

    static JsonObject label(final Game g, final Player p, final JsonObject spec) {
        final HonestLabel.Seat seat = new HonestLabel.Seat(HonestLabel.Spec.parse(spec));
        final JsonObject r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> seat.label(g, p, 42L)))
                .toJson();
        r.remove("ms");
        return r;
    }

    @Test(timeOut = 300000)
    public void counterLabelsReadNoWallClock() {
        // Thassa's Oracle with a two-card library: a proven win whose worlds the ms path cuts at a 1 ms budget
        final String frame = ForcedWinSolverTest.frame("Thassa's Oracle", "Island;Island", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island");
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        final Player me = g.getPlayers().get(0);
        final String before = LookaheadSearch.fingerprint(g);
        final JsonObject tiny = label(g, me, counterSpec("1")), huge = label(g, me, counterSpec("600000"));
        Assert.assertEquals(tiny, huge, "counter labels depend on the ms keys");
        Assert.assertEquals(tiny.get("label").getAsString(), "win", tiny.toString());
        Assert.assertEquals(tiny.get("worlds_win").getAsInt(), 8, tiny.toString());
        final JsonObject meter = tiny.getAsJsonObject("meter");
        Assert.assertFalse(meter.get("ms_bound").getAsBoolean(), meter.toString());
        Assert.assertTrue(meter.get("playout_steps").getAsLong() > 0, meter.toString());
        Assert.assertEquals(label(g, me, counterSpec("1")), tiny, "counter labels repeat");
        // the ms path at the same 1 ms budget is cut by its clock (fewer worlds: no proof), and its meter says so
        // review #16e: a counter evalSteps cap stops the line's worlds early: fewer than K worlds, no proof
        final JsonObject capped = counterSpec("1");
        capped.getAsJsonObject("solverCfg").addProperty("evalSteps", "1");
        final JsonObject cappedRow = label(g, me, capped);
        Assert.assertNotEquals(cappedRow.get("label").getAsString(), "win", cappedRow.toString());
        Assert.assertTrue(cappedRow.get("worlds_win").getAsInt() < 8, cappedRow.toString());
        Assert.assertTrue(cappedRow.getAsJsonObject("meter").get("eval_cap_stops").getAsLong() >= 1, cappedRow.toString());
        Assert.assertEquals(label(g, me, capped), cappedRow, "the cut is deterministic");
        final JsonObject msTiny = label(g, me, msSpec("1"));
        Assert.assertTrue(msTiny.getAsJsonObject("meter").get("ms_bound").getAsBoolean(), msTiny.toString());
        Assert.assertNotEquals(msTiny.get("label").getAsString(), "win", msTiny.toString());
        Assert.assertEquals(LookaheadSearch.fingerprint(g), before, "labelling moved the game");
    }

    @Test(timeOut = 300000)
    public void counterCapsStopTheEngineDeterministically() {
        // a lethal attacker against an empty board: the engine finds [ATTACK]; a 1-card budget stops it before any move
        final Game g = BvwAdoptAttackTest.lethalBoard();
        final Player me = g.getPlayers().get(0);
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        final ForcedWinPlanner.Line free = ForcedWinEngine.search(g, me, lim, st, Long.MAX_VALUE, 7L);
        Assert.assertNotNull(free, st.summary());
        Assert.assertTrue(st.cards > 0 && st.steps > 0, "cards " + st.cards + " steps " + st.steps);
        Assert.assertEquals(st.deadlineStops, 0);
        final ForcedWinEngine.Limits capped = new ForcedWinEngine.Limits();
        capped.totalCards = 1; // the root copy alone exceeds it
        final ForcedWinEngine.Stats st2 = new ForcedWinEngine.Stats();
        Assert.assertNull(ForcedWinEngine.search(g, me, capped, st2, Long.MAX_VALUE, 7L), st2.summary());
        Assert.assertEquals(st2.capStops, 1);
        Assert.assertEquals(st2.nodes, 0);
        // the memory guard: a path whose copies would exceed pathCards is not entered (the root, here)
        final ForcedWinEngine.Limits shallow = new ForcedWinEngine.Limits();
        shallow.pathCards = 1;
        final ForcedWinEngine.Stats st3 = new ForcedWinEngine.Stats();
        Assert.assertNull(ForcedWinEngine.search(g, me, shallow, st3, Long.MAX_VALUE, 7L), st3.summary());
        Assert.assertTrue(st3.pathPrunes >= 1 && st3.nodes == 0, "prunes " + st3.pathPrunes);
        // review #10: a pass stopped by nodes an EARLIER search spent is counted apart from this search's own caps
        final ForcedWinEngine.Stats st4 = new ForcedWinEngine.Stats();
        st4.nodes = 200; // a solver whose earlier engine searches used 200 nodes
        ForcedWinEngine.search(g, me, new ForcedWinEngine.Limits(), st4, Long.MAX_VALUE, 7L);
        Assert.assertEquals(st4.lifetimeStops, 1, st4.summary());
        Assert.assertTrue(st4.nodes > 200, "the pass-the-turn pass still runs: " + st4.summary());
        // a seat's calls past the cap end the move as the wall did
        final ForcedWinEngine.GuardedAi ai = new ForcedWinEngine.GuardedAi(g, me, me.getController().getLobbyPlayer());
        ai.callLimited(2);
        ai.chooseSpellAbilityToPlay();
        ai.chooseSpellAbilityToPlay();
        Assert.assertThrows(ForcedWinEngine.OutOfBudget.class, ai::chooseSpellAbilityToPlay);
        Assert.assertTrue(fromSpec("budget=counter,defense=false,engineCards=900").engineCards == 900);
    }

    static ForcedWinEngine.Limits loopLimits(final long pathCards) {
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        lim.cycles = true;
        lim.nodes = 60;
        lim.passTurn = false;
        lim.pathCards = pathCards;
        return lim;
    }

    @Test(timeOut = 900000)
    public void aFastForwardsLiveCopiesCountAgainstThePathCap() {
        // review #11: ForcedWinSolverTest's Kiki-Jiki loop (fast-forwarded to the kill). The loop's live copies count
        // against pathCards with the path: at the free run's peak nothing is cut; below it the cap binds, nothing live
        // ever exceeds it, and at some cap it is the fast-forward itself that stops (the copies the old rule missed)
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("Pestermite",
                ForcedWinSolverTest.KIKI_BF, 20, "", "Forest", 20, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinEngine.Stats free = new ForcedWinEngine.Stats();
        final ForcedWinPlanner.Line line = ForcedWinEngine.search(g, me, loopLimits(0), free, Long.MAX_VALUE, 7L);
        Assert.assertNotNull(line, free.summary());
        Assert.assertTrue(free.loops >= 1 && free.peakLive > 0, free.summary());
        final ForcedWinEngine.Stats at = new ForcedWinEngine.Stats();
        Assert.assertEquals(String.valueOf(ForcedWinEngine.search(g, me, loopLimits(free.peakLive), at, Long.MAX_VALUE,
                7L)), String.valueOf(line));
        Assert.assertEquals(at.pathPrunes, 0, at.summary());
        boolean loopStop = false;
        final long step = Math.max(1, free.peakLive / 40);
        for (long cap = free.peakLive - 1; cap > 0 && !loopStop; cap -= step) {
            final ForcedWinEngine.Stats s = new ForcedWinEngine.Stats();
            ForcedWinEngine.search(g, me, loopLimits(cap), s, Long.MAX_VALUE, 7L);
            Assert.assertTrue(s.peakLive <= cap, "live " + s.peakLive + " > cap " + cap);
            Assert.assertTrue(s.pathPrunes >= 1, "cap " + cap + ": " + s.summary());
            loopStop = s.loopCardStops > 0;
            System.err.println("[bvw-ff-cards test] cap " + cap + " peak " + s.peakLive + " loopStops " + s.loopCardStops
                    + " prunes " + s.pathPrunes);
        }
        Assert.assertTrue(loopStop, "no cap stopped a fast-forward (free peak " + free.peakLive + ")");
    }

    @Test
    public void engineStepsCapAndMeterAreReadOnly() {
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        Assert.assertEquals(lim.totalSteps, 0, "no engine step cap by default");
        final ForcedWinSolver.Meter a = new ForcedWinSolver.Meter();
        a.playoutSteps = 10;
        a.worlds = 3;
        final ForcedWinSolver.Meter b = a.copy();
        b.playoutSteps = 25;
        final JsonElement d = b.since(a).toJson();
        Assert.assertEquals(d.getAsJsonObject().get("playout_steps").getAsLong(), 15);
        Assert.assertEquals(d.getAsJsonObject().get("worlds").getAsLong(), 0);
    }
}
