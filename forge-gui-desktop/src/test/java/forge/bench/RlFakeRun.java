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
        return schedule(bank, 0, games, seed, ctl, alternate, priv, uidBase);
    }

    /** Games start .. start+games-1: game i plays pair (i mod pairs) with seed + i and uid uidBase + i. */
    public static List<JsonObject> schedule(final Path bank, final int start, final int games, final long seed,
            final String[] ctl, final boolean alternate, final boolean priv, final long uidBase) throws IOException {
        final List<String[]> pairs = pairs(bank);
        final List<JsonObject> out = new ArrayList<>();
        for (int i = start; i < start + games; i++) {
            final String[] p = pairs.get(i % pairs.size());
            final JsonObject g = new JsonObject();
            g.addProperty("game_uid", Long.toUnsignedString(uidBase + i));
            g.addProperty("seed", seed + i);
            final JsonArray decks = new JsonArray(), shas = new JsonArray(), c = new JsonArray();
            for (int s = 0; s < 2; s++) {
                decks.add(p[s]);
                shas.add(CardIndex.sha256(Files.readAllBytes(Paths.get(p[s]))));
            }
            final String[] pairCtl = CYCLE.isEmpty() ? ctl : CYCLE.get(i % CYCLE.size());
            final boolean swap = alternate && (i % 2 == 1);
            c.add(pairCtl[swap ? 1 : 0]);
            c.add(pairCtl[swap ? 0 : 1]);
            g.add("decks", decks);
            g.add("deck_sha", shas);
            g.add("controllers", c);
            g.addProperty("priv", priv);
            out.add(g);
        }
        return out;
    }

    /** Tokens per zone per frame (p50 / p99), and the frames whose token list was truncated (obs census). */
    static final class ZoneCensus {
        long frames, truncated, tokens;
        // R-TRUNC (actor side, pre-cap): frames truncated, by what they dropped; tokens dropped per zone
        long obsFrames, truncEventOnly, truncCardOrZone;
        final long[] droppedByZone = new long[forge.bench.rl.RlSchema.ZONES.size()];
        // C4': per game, the command-zone objects that stayed <unk> (designations), by name -> games
        final java.util.Map<String, java.util.Set<Long>> commandUnknownGames = new java.util.TreeMap<>();
        final java.util.Set<Long> games = new java.util.HashSet<>();
        // knowledge census: learn / forget reasons, and the resolving source of each library learn
        final java.util.Map<String, Long> learned = new java.util.TreeMap<>();
        final java.util.Map<String, Long> forgot = new java.util.TreeMap<>();
        final java.util.Map<String, Long> libLearnBySource = new java.util.HashMap<>();

        forge.bench.rl.RlKnowledge.Log log(final forge.game.Game game) {
            return new forge.bench.rl.RlKnowledge.Log() {
                @Override
                public void learned(final int seat, final forge.game.card.Card card, final String how) {
                    String src = "-";
                    try {
                        final forge.game.spellability.SpellAbility top = game.getStack().isEmpty() ? null
                                : game.getStack().peekAbility();
                        src = top == null ? "(no stack)" : top.getHostCard().getName();
                    } catch (RuntimeException e) {
                        src = "?";
                    }
                    synchronized (ZoneCensus.this) {
                        learned.merge(how, 1L, Long::sum);
                        if (card.isInZone(forge.game.zone.ZoneType.Library) || how.endsWith("Library")
                                || how.equals("look")) {
                            libLearnBySource.merge(how + " @ " + src, 1L, Long::sum);
                        }
                    }
                }

                @Override
                public void forgot(final int seat, final int cardId, final String why) {
                    synchronized (ZoneCensus.this) {
                        forgot.merge(why.startsWith("moved ") && !why.contains("random") ? "moved unseen" : why, 1L,
                                Long::sum);
                    }
                }
            };
        }

        synchronized void observe(final RlWire.Decide f, final forge.bench.rl.RlFeaturizer.Obs o) {
            if (o == null) {
                return;
            }
            obsFrames++;
            games.add(f.gameUid);
            if (o.truncated) {
                boolean card = false;
                for (int z = 0; z < o.droppedByZone.length; z++) {
                    droppedByZone[z] += o.droppedByZone[z];
                    if (o.droppedByZone[z] > 0 && z != forge.bench.rl.RlSchema.Z_U_EVENT
                            && z != forge.bench.rl.RlSchema.Z_O_EVENT) {
                        card = true;
                    }
                }
                if (card) {
                    truncCardOrZone++;
                } else {
                    truncEventOnly++;
                }
            }
            for (String n : o.commandUnknown) {
                commandUnknownGames.computeIfAbsent(n, k -> new java.util.HashSet<>()).add(f.gameUid);
            }
        }
        final java.util.Map<Integer, List<Integer>> perZone = new java.util.TreeMap<>();
        final List<Integer> perFrame = new ArrayList<>();

        synchronized void add(final forge.bench.rl.RlWire.Decide d) {
            frames++;
            if ((d.flags & forge.bench.rl.RlWire.F_TRUNC_TOKENS) != 0) {
                truncated++;
            }
            tokens += d.L;
            perFrame.add(d.L);
            final int[] n = new int[25];
            for (int i = 0; i < d.L; i++) {
                n[d.tokZone[i] & 0xff]++;
            }
            for (int z = 1; z < 25; z++) {
                perZone.computeIfAbsent(z, k -> new ArrayList<>()).add(n[z]);
            }
        }

        static int pct(final List<Integer> l, final double p) {
            final List<Integer> s = new ArrayList<>(l);
            java.util.Collections.sort(s);
            return s.isEmpty() ? 0 : s.get(Math.min(s.size() - 1, (int) (p * s.size())));
        }

        synchronized JsonObject json() {
            final JsonObject o = new JsonObject();
            o.addProperty("frames", frames);
            o.addProperty("truncated", truncated);
            o.addProperty("truncated_rate", frames == 0 ? 0 : (double) truncated / frames);
            o.addProperty("L_p50", pct(perFrame, 0.5));
            o.addProperty("L_p99", pct(perFrame, 0.99));
            o.addProperty("L_max", pct(perFrame, 1.0));
            final JsonObject z = new JsonObject();
            for (java.util.Map.Entry<Integer, List<Integer>> e : perZone.entrySet()) {
                long sum = 0;
                long nonzero = 0;
                for (int v : e.getValue()) {
                    sum += v;
                    nonzero += v > 0 ? 1 : 0;
                }
                if (sum == 0) {
                    continue;
                }
                final JsonObject zz = new JsonObject();
                zz.addProperty("mean", (double) sum / frames);
                zz.addProperty("p50", pct(e.getValue(), 0.5));
                zz.addProperty("p99", pct(e.getValue(), 0.99));
                zz.addProperty("frames_with", nonzero);
                z.add(forge.bench.rl.RlSchema.ZONES.get(e.getKey()), zz);
            }
            o.add("zones", z);
            if (obsFrames > 0) {
                final JsonObject t = new JsonObject();
                t.addProperty("frames", obsFrames);
                t.addProperty("truncated_event_tail_only", truncEventOnly);
                t.addProperty("truncated_card_or_zone", truncCardOrZone);
                t.addProperty("event_only_rate", (double) truncEventOnly / obsFrames);
                t.addProperty("card_or_zone_rate", (double) truncCardOrZone / obsFrames);
                final JsonObject d = new JsonObject();
                for (int zi = 0; zi < droppedByZone.length; zi++) {
                    if (droppedByZone[zi] > 0) {
                        d.addProperty(forge.bench.rl.RlSchema.ZONES.get(zi), droppedByZone[zi]);
                    }
                }
                t.add("dropped_tokens_by_zone", d);
                o.add("truncation_by_class", t);
                final JsonObject cu = new JsonObject();
                cu.addProperty("games", games.size());
                for (java.util.Map.Entry<String, java.util.Set<Long>> e : commandUnknownGames.entrySet()) {
                    cu.addProperty(e.getKey(), e.getValue().size());
                }
                o.add("command_unknown_games", cu);
                final JsonObject kl = new JsonObject();
                for (java.util.Map.Entry<String, Long> e : learned.entrySet()) kl.addProperty(e.getKey(), e.getValue());
                final JsonObject kf = new JsonObject();
                for (java.util.Map.Entry<String, Long> e : forgot.entrySet()) kf.addProperty(e.getKey(), e.getValue());
                final JsonObject ks = new JsonObject();
                libLearnBySource.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(25)
                        .forEach(e -> ks.addProperty(e.getKey(), e.getValue()));
                final JsonObject k = new JsonObject();
                k.add("learned", kl);
                k.add("forgot", kf);
                k.add("library_learns_by_source_top25", ks);
                o.add("knowledge_reasons", k);
            }
            return o;
        }
    }

    /** Optional per-game controller pairs, cycled (multi-arm eval in one JVM): spec key controllersCycle. */
    static final List<String[]> CYCLE = new ArrayList<>();

    public static void main(final String[] args) throws Exception {
        final JsonObject spec = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[0])),
                StandardCharsets.UTF_8)).getAsJsonObject();
        final RlActorBench.Cfg cfg = RlActorBench.parse(spec.getAsJsonObject("actor"));
        if (cfg.cardIndex == null) {
            cfg.cardIndex = cfg.rlRoot + "/data/card-index.tsv";
        }
        final Path bank = Paths.get(spec.get("bank").getAsString());
        if (spec.has("controllersCycle")) {
            for (JsonElement e : spec.getAsJsonArray("controllersCycle")) {
                final JsonArray a = e.getAsJsonArray();
                CYCLE.add(new String[] {a.get(0).getAsString(), a.get(1).getAsString()});
            }
        }
        final JsonArray cj = spec.getAsJsonArray("controllers");
        final String[] ctl = {cj.get(0).getAsString(), cj.get(1).getAsString()};
        final int games = spec.get("games").getAsInt();
        final long seed = spec.get("seed").getAsLong();
        final List<JsonObject> sched = schedule(bank, spec.has("start") ? spec.get("start").getAsInt() : 0, games, seed, ctl,
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
            final ZoneCensus zc = new ZoneCensus();
            if (spec.has("zoneStats") && spec.get("zoneStats").getAsBoolean()) {
                srv.capture = (type, frame, d, steps) -> zc.add(d);
                RlActorBench.KNOWLEDGE_LOG = zc::log;
                final forge.bench.rl.RlSeat.FrameListener prev = RlActorBench.LISTENER;
                RlActorBench.LISTENER = (g, player, f, m, ob, st, ans) -> {
                    if (prev != null) {
                        prev.onFrame(g, player, f, m, ob, st, ans);
                    }
                    zc.observe(f, ob);
                };
            }
            rc = RlActorBench.run(cfg);
            stats = srv.stats();
            final JsonArray ends = new JsonArray();
            synchronized (srv.ends) {
                for (JsonObject e : srv.ends) ends.add(e);
            }
            stats.addProperty("actor_exit", rc);
            if (zc.frames > 0) {
                stats.add("zone_census", zc.json());
            }
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
