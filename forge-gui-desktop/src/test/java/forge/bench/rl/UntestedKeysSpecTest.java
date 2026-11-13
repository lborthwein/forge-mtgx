package forge.bench.rl;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinSolver;

/**
 * untested-keys-1113: the solver keys the live policy-search string may now hold, each absent = off: the end-step engine
 * family (engineAnyWindow, engineEndStepOpen, engineEveryWindow, enginePassBudget, enginePassToEnd) and the engine's move
 * order and loop credit (engineFocus, engineLoopCredit, engineLoopCreditCap). Until now only the labeller and the bench
 * could set them. Configs only: no game, no card database.
 */
public class UntestedKeysSpecTest {

    static final String SHIP = OwnerMisplaysSpecTest.SHIP;
    /** The flags, in the order of the solver's own config. */
    static final String[] FLAGS = {"engineAnyWindow", "engineEndStepOpen", "engineEveryWindow", "enginePassBudget",
            "enginePassToEnd", "engineFocus", "engineLoopCredit"};

    private static ForcedWinSolver.Config solver(final String spec) {
        return PlanSearch.Spec.parse(RlLiveSeat.Spec.parse(spec).search.plans).solverConfig();
    }

    private static JsonObject solverCfg(final String spec) {
        return RlLiveSeat.Spec.parse(spec).search.plans.getAsJsonObject("solverCfg");
    }

    /** Every instance field of the solver's config, by name: what the solver reads. */
    static java.util.Map<String, String> fields(final ForcedWinSolver.Config c) {
        final java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (Field f : ForcedWinSolver.Config.class.getFields()) {
            final Class<?> t = f.getType();
            if (Modifier.isStatic(f.getModifiers()) || !(t.isPrimitive() || t == String.class || t.isEnum()
                    || Number.class.isAssignableFrom(t) || t == Boolean.class)) {
                continue; // values only (no object identities)
            }
            try {
                final Object v = f.get(c);
                out.put(f.getName(), String.valueOf(v));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return out;
    }

    @Test
    public void theShipStringHasNoneOfTheKeysAndTheyAreOff() {
        final JsonObject sc = solverCfg(SHIP);
        for (String k : FLAGS) {
            Assert.assertFalse(sc.has(k), k);
        }
        Assert.assertFalse(sc.has("engineLoopCreditCap"));
        final ForcedWinSolver.Config c = solver(SHIP);
        Assert.assertFalse(c.engineAnyWindow || c.engineEndStepOpen || c.engineEveryWindow || c.enginePassBudget
                || c.enginePassToEnd || c.engineFocus || c.engineLoopCredit, c.toString());
        Assert.assertEquals(c.engineLoopCreditCap, 96);
    }

    /** Keys off: the string with every key set to false (and the cap at its default) gives the solver the same config. */
    @Test
    public void keysOffTheSolverConfigIsTheShipStringsFieldForField() {
        final StringBuilder off = new StringBuilder(SHIP);
        for (String k : FLAGS) {
            off.append(",plans.solverCfg.").append(k).append("=false");
        }
        off.append(",plans.solverCfg.engineLoopCreditCap=96");
        final java.util.Map<String, String> a = fields(solver(SHIP)), b = fields(solver(off.toString()));
        Assert.assertTrue(a.size() > 100, "the config's fields: " + a.size());
        Assert.assertEquals(b, a);
        Assert.assertEquals(solver(off.toString()).toString(), solver(SHIP).toString());
    }

    @Test
    public void eachKeyParsesAloneAndReachesItsConfig() {
        for (String k : FLAGS) {
            final ForcedWinSolver.Config c = solver(SHIP + ",plans.solverCfg." + k + "=true");
            final java.util.Map<String, String> on = fields(c), base = fields(solver(SHIP));
            Assert.assertEquals(on.get(k), "true", k);
            on.remove(k);
            base.remove(k);
            Assert.assertEquals(on, base, k + " moves only its own field");
        }
        Assert.assertEquals(solver(SHIP + ",plans.solverCfg.engineLoopCreditCap=48").engineLoopCreditCap, 48);
        // the whole family in one string, with planLoops (the bundle a read uses)
        final ForcedWinSolver.Config all = solver(SHIP + ",plans.solverCfg.planLoops=true,plans.solverCfg.engineAnyWindow=true,"
                + "plans.solverCfg.engineEndStepOpen=true,plans.solverCfg.engineEveryWindow=true,plans.solverCfg.enginePassBudget=true,"
                + "plans.solverCfg.enginePassToEnd=true,plans.solverCfg.engineFocus=true,plans.solverCfg.engineLoopCredit=true");
        Assert.assertTrue(all.planLoops && all.engineAnyWindow && all.engineEndStepOpen && all.engineEveryWindow && all.enginePassBudget
                && all.enginePassToEnd && all.engineFocus && all.engineLoopCredit);
    }

    /**
     * A read kit's bench spec against the live string it stands for (skipped unless {@code -Duk.benchSpec=FILE}):
     * {@code -Duk.liveAdds=k=v,...} are appended to the ship string. Bench-only and live-fix keys as in ShipASpecParseTest.
     */
    @Test
    public void aKitsBenchSpecIsTheLiveStringsConfig() throws Exception {
        final String file = System.getProperty("uk.benchSpec");
        if (file == null) {
            return;
        }
        final String adds = System.getProperty("uk.liveAdds", "");
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(SHIP + (adds.isEmpty() ? "" : "," + adds));
        final JsonObject o = com.google.gson.JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(file)))).getAsJsonObject();
        o.addProperty("server", "127.0.0.1:1");
        final boolean basics = forge.ai.simulation.BeliefSampler.BASICS_BY_TYPE;
        final RlSearch.Config bc;
        try {
            bc = RlSearch.Config.parse(o);
        } finally {
            forge.ai.simulation.BeliefSampler.BASICS_BY_TYPE = basics;
        }
        RlSearch.armInfoState(bc);
        final JsonObject live = s.search.toJson(), bench = bc.toJson();
        for (String k : new String[] {"server", "policySha", "beliefCube", "beliefCubeSha256", "beliefSingleton", "cpuCapMs",
                "honorDrawReason", "infoAttach", "basicsByType", "threads", "obs"}) {
            live.remove(k);
            bench.remove(k);
        }
        final JsonObject bs = bench.getAsJsonObject("plans").getAsJsonObject("solverCfg");
        for (String k : new String[] {"staleStatics", "turnLimits", "lineUncapped", "stalePlayerStatics", "offBattlefieldLimits",
                "copyBackFace", "stackAbilityCopies", "planExpandCap"}) {
            bs.remove(k); // the live line's jar default (forge.liveFixes), explicit in the bench spec
        }
        bench.getAsJsonObject("plans").remove("recordMs");
        final java.util.Set<String> keys = new java.util.TreeSet<>(live.keySet());
        keys.addAll(bench.keySet());
        for (String k : keys) {
            Assert.assertEquals(bench.get(k), live.get(k), "search key " + k + " of " + file);
        }
        System.out.println("UNTESTEDKEYS bench spec " + file + " == ship string + " + adds);
    }

    @Test
    public void badValuesAndTwiceAreRefused() {
        for (String bad : new String[] {"plans.solverCfg.engineAnyWindow=1", "plans.solverCfg.engineFocus=yes",
                "plans.solverCfg.engineLoopCreditCap=true", "plans.solverCfg.engineLoopCreditCap=-3",
                "plans.solverCfg.engineEndStepOpen=true,plans.solverCfg.engineEndStepOpen=true"}) {
            try {
                RlLiveSeat.Spec.parse(SHIP + "," + bad);
                Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // a flag takes true or false, the cap a whole number, a key once
            }
        }
    }
}
