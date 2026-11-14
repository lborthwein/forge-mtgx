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
 * <pre>usage: CardTableGen --out TABLE.json|DIR/ [--cube PATH|classpath:...[,PATH...]] [--variants ",sl|tc|settle|"]
 *        [--spec BENCH-SPEC.json | --solver "k=v,k=v"] [--no-loops] [--only NAME;NAME]</pre>
 * {@code --variants}: comma-separated template-key flag prefixes (empty = the plain keys of the defender's reader).
 * {@code --spec}: a bench search spec whose plans.solverCfg gives the loop probes' engine settings.
 * ct2-live-1114: {@code --cube} takes a comma-separated list; with more than one the pool is their union, sorted by name
 * (so the order the lists are given in does not matter). The table's note names each list by its sha256, not its path,
 * so the same jar, flags and lists give the same file bytes on any host. {@code --out DIR/} (a trailing slash) writes the
 * file under the name {@code cardTable=auto} loads ({@link ForcedWinCanon#autoName}).
 * ct2-live-1114 (the condition filter, on unless {@code --no-filter}): every learned card is learned again in each of
 * {@link ForcedWinCanon#CONDITIONS} (empty graveyard, one land, low life, empty hand) under every variant; a card whose
 * model differs anywhere gets no entry and is listed as refused (learned from the real position at run time). The note
 * records the counts and each refusal with the first condition and fields that differed. Loop probes are generated for
 * kept cards only, and none naming a refused card is kept.
 */
public final class CardTableGen {
    private CardTableGen() {
    }

    public static void main(final String[] argv) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        String out = null, cube = BeliefSampler.LIVE_CUBE, variants = ",sl|tc|settle|", spec = null, solver = null, only = null;
        boolean loops = true, filter = true;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--out": out = argv[++i]; break;
                case "--cube": cube = argv[++i]; break;
                case "--variants": variants = argv[++i]; break;
                case "--spec": spec = argv[++i]; break;
                case "--solver": solver = argv[++i]; break;
                case "--only": only = argv[++i]; break;
                case "--no-loops": loops = false; break;
                case "--no-filter": filter = false; break;
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
        // ct2-live-1114: a work clock for the whole run, so every planner and probe budget (and the loop probes' window with
        // forge.loopWorkClock) counts work, not wall time: the same jar, flags and pool give the same file on any host
        forge.game.WorkClock.install(forge.game.WorkClock.newClock(1.0));
        final long t0 = System.nanoTime();
        RlActorBench.boot();
        final List<String> pool = new ArrayList<>();
        final String[] cubes = cube.split(",");
        final List<String> cubeShas = new ArrayList<>();
        for (String cp : cubes) {
            final byte[] bytes = BeliefSampler.readCubeBytes(cp);
            cubeShas.add(sha256(bytes));
            final JsonObject c = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : c.getAsJsonArray("cards")) {
                final String n = e.getAsJsonObject().get("name").getAsString();
                if (cubes.length == 1 || !pool.contains(n)) {
                    pool.add(n);
                }
            }
        }
        if (cubes.length > 1) {
            java.util.Collections.sort(pool); // ct2-live-1114: the union's order does not depend on the lists' order
            java.util.Collections.sort(cubeShas);
        }
        if (out.endsWith("/")) {
            final String an = ForcedWinCanon.autoName();
            if (an == null) {
                throw new IllegalStateException("--out DIR/: the generator's classes are not in a jar (no auto name)");
            }
            out = out + an;
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
        String filterNote = "; filter off";
        if (filter) {
            filterNote = "; " + filterNote(tb, names, pick, variants);
        }
        final byte[] file = tb.toJson("cube sha256 " + String.join(",", cubeShas) + "; variants '" + variants + "'; solver "
                + new java.util.TreeMap<>(props) + filterNote).getBytes(StandardCharsets.UTF_8);
        Files.write(Paths.get(out), file);
        System.err.println("[card-table-gen] DONE learned=" + ok + " notLearned=" + miss + " loopProbes=" + lp + " ms="
                + (System.nanoTime() - t0) / 1_000_000 + " -> " + out + " sha256 " + sha256(file) + " auto name "
                + ForcedWinCanon.autoName());
        System.exit(0);
    }

    /** ct2-live-1114: the condition filter over the learned table (loop probes included); returns the note's text. */
    private static String filterNote(final ForcedWinCanon.Table tb, final List<String> names, final java.util.Set<String> pick,
            final String variants) {
        final long tf = System.nanoTime();
        final java.util.TreeMap<String, String> refusedWhy = new java.util.TreeMap<>();
        final java.util.TreeMap<String, Integer> byCond = new java.util.TreeMap<>();
        int checked = 0;
        for (String n : names) {
            if (pick != null && !pick.contains(n)) {
                continue;
            }
            checked++;
            for (String v : variants.split(",", -1)) {
                final String why = ForcedWinCanon.varies(tb, v, n);
                if (why != null) {
                    refusedWhy.put(n, "'" + v + "' " + why);
                    byCond.merge(why.substring(0, why.indexOf(':')), 1, Integer::sum);
                    break;
                }
            }
        }
        for (String n : refusedWhy.keySet()) {
            ForcedWinCanon.refuse(tb, n);
            System.err.println("[card-table-gen] refused " + n + ": " + refusedWhy.get(n));
        }
        final StringBuilder sb = new StringBuilder("filter conditions " + String.join("/", ForcedWinCanon.CONDITIONS) + ": checked "
                + checked + ", kept " + (checked - refusedWhy.size()) + ", refused " + refusedWhy.size() + " " + byCond);
        refusedWhy.forEach((n, w) -> sb.append("; refused ").append(n).append(" (").append(w).append(')'));
        System.err.println("[card-table-gen] filter done in " + (System.nanoTime() - tf) / 1_000_000 + " ms: checked " + checked + ", kept "
                + (checked - refusedWhy.size()) + ", refused " + refusedWhy.size() + " " + byCond);
        return sb.toString();
    }

    private static String sha256(final byte[] b) throws java.security.NoSuchAlgorithmException {
        final StringBuilder sb = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
