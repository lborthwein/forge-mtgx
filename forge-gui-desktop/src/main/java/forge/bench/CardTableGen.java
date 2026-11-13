package forge.bench;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.BeliefSampler;
import forge.ai.simulation.ForcedWinCanon;
import forge.ai.simulation.ForcedWinSolver;

/**
 * Lane searchspeed-1113: the generator of a saved card-model table ({@link ForcedWinCanon}; solver key cardTable). Run it
 * with the jar and the {@code forge.*} JVM flags of the JVMs that will read the table: the table is stamped with both and
 * is refused by any other build. Every card of the pool is learned in the canonical sandbox once per template-key
 * variant, then the loop probes of the cards planLoops would probe, with every creature of the pool.
 *
 * <pre>usage: CardTableGen --out TABLE.json [--cube PATH|classpath:...] [--variants ",sl|tc|settle|"]
 *        [--spec BENCH-SPEC.json | --solver "k=v,k=v"] [--no-loops] [--only NAME;NAME]</pre>
 * {@code --variants}: comma-separated template-key flag prefixes (empty = the plain keys of the defender's reader).
 * {@code --spec}: a bench search spec whose plans.solverCfg gives the loop probes' engine settings.
 */
public final class CardTableGen {
    private CardTableGen() {
    }

    public static void main(final String[] argv) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        String out = null, cube = BeliefSampler.LIVE_CUBE, variants = ",sl|tc|settle|", spec = null, solver = null, only = null;
        boolean loops = true;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--out": out = argv[++i]; break;
                case "--cube": cube = argv[++i]; break;
                case "--variants": variants = argv[++i]; break;
                case "--spec": spec = argv[++i]; break;
                case "--solver": solver = argv[++i]; break;
                case "--only": only = argv[++i]; break;
                case "--no-loops": loops = false; break;
                default: throw new IllegalArgumentException(argv[i]);
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out TABLE.json");
        }
        final java.util.Map<String, String> props = new java.util.HashMap<>();
        if (spec != null) {
            final JsonObject o = JsonParser.parseString(Files.readString(Paths.get(spec))).getAsJsonObject();
            final JsonObject sc = o.getAsJsonObject("plans").getAsJsonObject("solverCfg");
            for (java.util.Map.Entry<String, JsonElement> e : sc.entrySet()) {
                props.put(e.getKey(), e.getValue().getAsString());
            }
        }
        if (solver != null) {
            for (String kv : solver.split(",")) {
                final int eq = kv.indexOf('=');
                props.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        props.remove("cardTable");
        final ForcedWinSolver.Config cfg = ForcedWinSolver.Config.fromProps(props::get);
        final long t0 = System.nanoTime();
        RlActorBench.boot();
        final List<String> pool = new ArrayList<>();
        final JsonObject c = JsonParser.parseString(new String(BeliefSampler.readCubeBytes(cube), StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement e : c.getAsJsonArray("cards")) {
            pool.add(e.getAsJsonObject().get("name").getAsString());
        }
        final ForcedWinCanon.Table tb = ForcedWinCanon.newTable(pool);
        final List<String> names = ForcedWinCanon.poolOf(tb);
        System.err.println("[card-table-gen] stamp " + ForcedWinCanon.stamp() + " pool " + names.size() + " of " + pool.size()
                + " names; boot " + (System.nanoTime() - t0) / 1_000_000 + " ms\n[card-table-gen] flags:\n" + ForcedWinCanon.flags());
        final java.util.Set<String> pick = only == null ? null : new java.util.HashSet<>(java.util.Arrays.asList(only.split(";")));
        int ok = 0, miss = 0, lp = 0;
        for (String v : variants.split(",", -1)) {
            final long tv = System.nanoTime();
            for (String n : names) {
                if (pick != null && !pick.contains(n)) {
                    continue;
                }
                if (ForcedWinCanon.generate(tb, v, n)) {
                    ok++;
                } else {
                    miss++;
                    System.err.println("[card-table-gen] not learned: " + v + n);
                }
            }
            System.err.println("[card-table-gen] variant '" + v + "' done in " + (System.nanoTime() - tv) / 1_000_000 + " ms; " + tb.summary());
            if (loops && cfg.planLoops) {
                final long tl = System.nanoTime();
                for (String n : names) {
                    if (pick == null || pick.contains(n)) {
                        final int k = ForcedWinCanon.generateLoops(tb, cfg, v, n);
                        if (k > 0) {
                            lp += k;
                            System.err.println("[card-table-gen] loop probes of " + v + n + ": " + k + " (" + (System.nanoTime() - tl) / 1_000_000 + " ms so far)");
                        }
                    }
                }
            }
        }
        Files.write(Paths.get(out), tb.toJson("cube " + cube + "; variants '" + variants + "'; solver " + new java.util.TreeMap<>(props)).getBytes(StandardCharsets.UTF_8));
        System.err.println("[card-table-gen] DONE learned=" + ok + " notLearned=" + miss + " loopProbes=" + lp + " ms="
                + (System.nanoTime() - t0) / 1_000_000 + " -> " + out);
        System.exit(0);
    }
}
