package forge.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.CardIndex;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFamilyChecks;
import forge.bench.rl.RlGoldens;
import forge.bench.rl.RlWire;

/**
 * Test-scope launcher (lane rl-r0-b1-1005): an in-JVM {@link FakeRlServer} plus {@link RlActorBench} in one process,
 * for the measurements and smokes. {@code java -cp jar:test-classes forge.bench.RlFakeRun <spec.json>}, cwd =
 * forge-gui with res/. Spec keys: {@code actor} (RlActorBench config without {@code server}), {@code bank} (a TRAIN
 * bank dir with MANIFEST.json), {@code games}, {@code seed} (game i = seed + i), {@code controllers}
 * (["rl:M","rl:M"] ...), {@code alternate} (swap the two controllers on odd games), {@code priv}, {@code serverSeed},
 * {@code out} (stats JSON), {@code rlsimCfg} (optional: write the RlSimBench policy=forge config of the same games),
 * {@code goldens} (optional dir: keep the first frame of each golden kind).
 *
 * <p>Pairs: the bank's pods in name order; game i plays pod (i mod pods) p0 vs p1 (seat 0 = p0), as RlSimBench's
 * r14 configuration did.
 */
public final class RlFakeRun {

    private RlFakeRun() {
    }

    public static List<String[]> pairs(final Path bank) throws IOException {
        final JsonObject m = JsonParser.parseString(new String(Files.readAllBytes(bank.resolve("MANIFEST.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        final TreeMap<String, List<String>> byPod = new TreeMap<>();
        for (JsonElement e : m.getAsJsonArray("decks")) {
            final JsonObject d = e.getAsJsonObject();
            final String pod = d.has("pod") ? d.get("pod").getAsString() : d.get("id").getAsString();
            byPod.computeIfAbsent(pod, k -> new ArrayList<>()).add(bank.resolve(d.get("file").getAsString()).toString());
        }
        final List<String[]> out = new ArrayList<>();
        for (List<String> l : byPod.values()) {
            l.sort(null);
            if (l.size() >= 2) {
                out.add(new String[] {l.get(0), l.get(1)});
            }
        }
        return out;
    }

    public static List<JsonObject> schedule(final Path bank, final int games, final long seed, final String[] ctl,
            final boolean alternate, final boolean priv, final long uidBase) throws IOException {
        final List<String[]> pairs = pairs(bank);
        final List<JsonObject> out = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            final String[] p = pairs.get(i % pairs.size());
            final JsonObject g = new JsonObject();
            g.addProperty("game_uid", Long.toUnsignedString(uidBase + i));
            g.addProperty("seed", seed + i);
            final JsonArray decks = new JsonArray(), shas = new JsonArray(), c = new JsonArray();
            for (int s = 0; s < 2; s++) {
                decks.add(p[s]);
                shas.add(CardIndex.sha256(Files.readAllBytes(Paths.get(p[s]))));
            }
            final boolean swap = alternate && (i % 2 == 1);
            c.add(ctl[swap ? 1 : 0]);
            c.add(ctl[swap ? 0 : 1]);
            g.add("decks", decks);
            g.add("deck_sha", shas);
            g.add("controllers", c);
            g.addProperty("priv", priv);
            out.add(g);
        }
        return out;
    }

    public static void main(final String[] args) throws Exception {
        final JsonObject spec = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[0])),
                StandardCharsets.UTF_8)).getAsJsonObject();
        final RlActorBench.Cfg cfg = RlActorBench.parse(spec.getAsJsonObject("actor"));
        if (cfg.cardIndex == null) {
            cfg.cardIndex = cfg.rlRoot + "/data/card-index.tsv";
        }
        final Path bank = Paths.get(spec.get("bank").getAsString());
        final JsonArray cj = spec.getAsJsonArray("controllers");
        final String[] ctl = {cj.get(0).getAsString(), cj.get(1).getAsString()};
        final int games = spec.get("games").getAsInt();
        final long seed = spec.get("seed").getAsLong();
        final List<JsonObject> sched = schedule(bank, games, seed, ctl,
                spec.has("alternate") && spec.get("alternate").getAsBoolean(),
                spec.has("priv") && spec.get("priv").getAsBoolean(),
                spec.has("uidBase") ? spec.get("uidBase").getAsLong() : 1L);
        if (spec.has("rlsimCfg")) {
            final JsonObject rs = new JsonObject();
            final JsonArray pa = new JsonArray();
            for (String[] p : pairs(bank)) {
                final JsonArray x = new JsonArray();
                x.add(p[0]);
                x.add(p[1]);
                pa.add(x);
            }
            rs.add("pairs", pa);
            rs.addProperty("games", games);
            rs.addProperty("seed", seed);
            rs.addProperty("policy", "forge");
            rs.addProperty("threads", 1);
            rs.addProperty("out", spec.get("rlsimOut").getAsString());
            Files.write(Paths.get(spec.get("rlsimCfg").getAsString()),
                    RlWire.canonical(rs));
        }
        final String idxSha = CardIndex.load(Paths.get(cfg.cardIndex)).sha();
        RlGoldens goldens = null;
        if (spec.has("goldens")) {
            goldens = new RlGoldens();
            if (spec.has("goldensWanted") && "B".equals(spec.get("goldensWanted").getAsString())) {
                goldens.wanted = RlGoldens.WANTED_B; // lane rl-r0-b4-1006: families 8-24
            }
            goldens.acceptRecord = spec.has("goldensRecord") && spec.get("goldensRecord").getAsBoolean();
            RlActorBench.LISTENER = goldens;
        }
        // lane rl-r0-b4-1006: round-trip every sent frame's answer through the record-mode mapper
        RlFamilyChecks checks = null;
        if (spec.has("checks") && spec.get("checks").getAsBoolean()) {
            checks = new RlFamilyChecks();
            checks.goldens = goldens;
            RlActorBench.LISTENER = checks;
        }
        final int rc;
        final JsonObject stats;
        try (FakeRlServer srv = new FakeRlServer(0, cfg.mode, spec.has("serverSeed") ? spec.get("serverSeed").getAsLong()
                : 7L, idxSha, sched)) {
            cfg.server = "127.0.0.1:" + srv.port();
            rc = RlActorBench.run(cfg);
            stats = srv.stats();
            final JsonArray ends = new JsonArray();
            synchronized (srv.ends) {
                for (JsonObject e : srv.ends) ends.add(e);
            }
            stats.addProperty("actor_exit", rc);
            if (spec.has("endsOut")) {
                final StringBuilder sb = new StringBuilder();
                for (JsonElement e : ends) sb.append(RlWire.canonicalString(e)).append('\n');
                Files.write(Paths.get(spec.get("endsOut").getAsString()), sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        if (goldens != null) {
            goldens.write(Paths.get(spec.get("goldens").getAsString()));
            stats.addProperty("goldens", String.join(",", goldens.kept.keySet()));
        }
        if (checks != null) {
            stats.add("roundtrip", checks.toJson());
        }
        Files.write(Paths.get(spec.get("out").getAsString()), (RlWire.canonicalString(stats) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        System.err.println("[rlfake] " + RlWire.canonicalString(stats));
        System.exit(rc);
    }
}
