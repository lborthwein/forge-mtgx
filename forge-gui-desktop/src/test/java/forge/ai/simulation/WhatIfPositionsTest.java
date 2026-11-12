package forge.ai.simulation;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-whatif-1111: the what-if evaluator ({@link WhatIf}) on stored positions. Input ({@code -Dwhatif.asks=FILE},
 * JSON lines; without it only the unit tests run): one ask per line, {@code {"tag", "options": [{"o": label, "state":
 * Forge puzzle state of the position the option leads to, "knownTop": n, "me": seat}]}}: the dump of
 * {@code -Dforge.ai.whatIf.dump} in a game, or positions built by a tool. For each budget of {@code -Dwhatif.cfgs}
 * ({@code name:worlds,horizon,nodes,probeMs,askMs,dev,pass;...}) it prints one {@code WHATIF {json}} line per ask.
 * {@code -Dwhatif.solverCfg=FILE}: the solver config (a spec's plans.solverCfg object). {@code -Dwhatif.reach=true}:
 * also today's planner reach and goldfish per option (the choice scorer's own keys, {@code -Dwhatif.scorer=FILE}).
 * {@code -Dwhatif.shard=i/n}. Card names are test data only.
 */
public class WhatIfPositionsTest extends forge.ai.AITest {

    static String state(final String hand, final String battlefield, final String library) {
        return "[state]\nturn=5\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\nhumanlandsplayed=0\n"
                + "ailandsplayed=0\naihand=\nailibrary=" + ForcedWinSolverTest.LIB20 + "\naigraveyard=\naiexile=\n"
                + "aibattlefield=\nhumanhand=" + hand + "\nhumanbattlefield=" + battlefield
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + library + "\n";
    }

    static JsonObject cfgOn() {
        final JsonObject o = new JsonObject();
        for (String k : new String[] {"cycles", "engineFocus", "engineLongLoops", "engineLoopBisect", "engineLoopCredit",
                "engineLoopHoist", "engineLoopReach", "engineMoves", "engineTriggerTargets", "turnLimits",
                "offBattlefieldLimits", "historyLki", "copyBackFace", "stackAbilityCopies", "stackHistory"}) {
            o.addProperty(k, "true");
        }
        o.addProperty("engineLoopCreditCap", "96");
        o.addProperty("act", "false");
        o.addProperty("defense", "false");
        return o;
    }

    private static WhatIf.Snapshot snap(final String frame, final int seat, final int knownTop) {
        final Object prev = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
            return WhatIf.snapshot(g, g.getPlayers().get(seat), knownTop, false);
        } finally {
            forge.util.IdScope.install(prev);
        }
    }

    /** Two options of one tutor: the piece that completes a two-card kill, and a land. The piece has the value. */
    @Test
    public void thePieceThatCompletesAKillOutranksALand() {
        final String lib = "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island";
        final String bf = "Mountain;Mountain;Mountain;Mountain;Mountain;Island;Island;Island";
        final List<WhatIf.Snapshot> opts = new ArrayList<>();
        opts.add(snap(state("Kiki-Jiki, Mirror Breaker;Deceiver Exarch", bf, lib), 0, 0));
        opts.add(snap(state("Kiki-Jiki, Mirror Breaker;Island", bf, lib), 0, 0));
        final WhatIf.Opts o = new WhatIf.Opts();
        o.worlds = 2;
        o.horizon = 2;
        o.nodes = 250;
        o.probeMs = 20000;
        o.askMs = 120000;
        o.seed = 7;
        final WhatIf.Ask a = WhatIf.evaluate(opts, SolverDriver.config(cfgOn()), o);
        System.out.println("WHATIF-UNIT piece=" + a.results.get(0).value + " line=" + a.results.get(0).line + " land="
                + a.results.get(1).value + " stages=" + a.stages + " probes=" + a.probes + " nodes=" + a.nodes);
        Assert.assertTrue(a.results.get(0).value > 0.5, "the assembled pair is a kill: " + a.results.get(0).fail);
        Assert.assertEquals(a.results.get(1).value, 0.0, 1e-9, "a land finds no kill in a library of lands");
        Assert.assertNotNull(a.results.get(0).line);
    }

    /** The worlds' order of the unknown library does not depend on its real order, and the known top stays. */
    @Test
    public void theUnknownLibraryIsSampledNeverRead() {
        final String bf = "Island;Island";
        final String a = "Mountain;Forest;Swamp;Plains;Island;Wastes;Mountain;Forest;Swamp;Plains";
        final String b = "Plains;Swamp;Forest;Mountain;Wastes;Island;Plains;Swamp;Forest;Mountain";
        final Game ga = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", state("Island", bf, a));
        final Game gb = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", state("Island", bf, b));
        final Player pa = ga.getPlayers().get(0), pb = gb.getPlayers().get(0);
        // the same cards (ids) in another real order: gb's library is ga's names reversed pairwise, so map by rank of id
        final List<Card> la = new ArrayList<>(pa.getCardsIn(ZoneType.Library));
        final List<Card> shuffled = new ArrayList<>(la);
        java.util.Collections.reverse(shuffled.subList(1, shuffled.size()));
        final List<Integer> first = new ArrayList<>(), second = new ArrayList<>();
        WhatIf.reorder(pa, 1, 99L);
        for (Card c : pa.getCardsIn(ZoneType.Library)) {
            first.add(c.getId());
        }
        pa.getZone(ZoneType.Library).setCards(shuffled);
        WhatIf.reorder(pa, 1, 99L);
        for (Card c : pa.getCardsIn(ZoneType.Library)) {
            second.add(c.getId());
        }
        Assert.assertEquals(second, first, "the same seed gives the same order whatever the real order below the known top");
        Assert.assertEquals((int) first.get(0), la.get(0).getId(), "the known top card stays on top");
        final List<Integer> other = new ArrayList<>();
        WhatIf.reorder(pa, 1, 100L);
        for (Card c : pa.getCardsIn(ZoneType.Library)) {
            other.add(c.getId());
        }
        Assert.assertNotEquals(other, first, "another world, another order");
        Assert.assertNotNull(pb);
    }

    @Test
    public void whatIfKeyDefaultsOffAndParses() {
        final forge.bench.rl.ChoiceScorer.Config off = forge.bench.rl.ChoiceScorer.Config
                .parse(JsonParser.parseString("{\"families\":\"CARDS\"}").getAsJsonObject());
        Assert.assertNull(off.whatIf);
        Assert.assertFalse(off.toJson().has("whatIf"));
        final forge.bench.rl.ChoiceScorer.Config on = forge.bench.rl.ChoiceScorer.Config.parse(JsonParser
                .parseString("{\"families\":\"CARDS\",\"whatIf\":{\"worlds\":2,\"horizon\":3,\"nodes\":40,\"dev\":\"land\"}}")
                .getAsJsonObject());
        Assert.assertEquals(on.whatIf.worlds, 2);
        Assert.assertEquals(on.whatIf.dev, "land");
        Assert.assertTrue(on.toJson().has("whatIf"));
        Assert.assertThrows(IllegalArgumentException.class, () -> forge.bench.rl.ChoiceScorer.Config
                .parse(JsonParser.parseString("{\"whatIf\":{\"nope\":1}}").getAsJsonObject()));
    }

    private static long cpuNanos() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    @Test
    public void storedAsks() throws Exception {
        final String path = System.getProperty("whatif.asks");
        if (path == null || path.isEmpty()) {
            System.out.println("WHATIF skipped: no -Dwhatif.asks");
            return;
        }
        final JsonObject sc;
        final String scPath = System.getProperty("whatif.solverCfg");
        if (scPath != null && !scPath.isEmpty()) {
            try (FileReader r = new FileReader(scPath)) {
                sc = JsonParser.parseReader(r).getAsJsonObject();
            }
            sc.addProperty("act", "false");
            sc.addProperty("defense", "false");
        } else {
            sc = cfgOn();
        }
        final ForcedWinSolver.Config cfg = SolverDriver.config(sc);
        final String[] shard = System.getProperty("whatif.shard", "0/1").split("/");
        final int si = Integer.parseInt(shard[0]), sn = Integer.parseInt(shard[1]);
        final boolean reach = "true".equals(System.getProperty("whatif.reach"));
        JsonObject scorer = null;
        if (reach && System.getProperty("whatif.scorer") != null) {
            try (FileReader r = new FileReader(System.getProperty("whatif.scorer"))) {
                scorer = JsonParser.parseReader(r).getAsJsonObject();
            }
        }
        final List<String[]> cfgs = new ArrayList<>();
        for (String c : System.getProperty("whatif.cfgs", "base:3,4,60,1500,600000,ai,false").split(";")) {
            if (!c.trim().isEmpty()) {
                cfgs.add(c.trim().split("[:,]"));
            }
        }
        int line = -1;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                line++;
                if (ln.trim().isEmpty() || line % sn != si) {
                    continue;
                }
                final JsonObject ask = JsonParser.parseString(ln).getAsJsonObject();
                final JsonArray os = ask.getAsJsonArray("options");
                for (String[] c : cfgs) {
                    final WhatIf.Opts o = new WhatIf.Opts();
                    o.worlds = Integer.parseInt(c[1]);
                    o.horizon = Integer.parseInt(c[2]);
                    o.nodes = Integer.parseInt(c[3]);
                    o.probeMs = Long.parseLong(c[4]);
                    o.askMs = Long.parseLong(c[5]);
                    o.dev = c[6];
                    o.pass = Boolean.parseBoolean(c[7]);
                    o.pool = c.length <= 8 || Boolean.parseBoolean(c[8]);
                    if (c.length > 9) {
                        o.second = Integer.parseInt(c[9]); // name:K,T,nodes,probeMs,askMs,dev,pass,pool,second,secondNodes
                        o.secondNodes = c.length > 10 ? Integer.parseInt(c[10]) : o.secondNodes;
                    }
                    if (c.length > 11) {
                        o.all = Integer.parseInt(c[11]); // ...,second,secondNodes,all,allNodes
                        o.allNodes = c.length > 12 ? Integer.parseInt(c[12]) : o.allNodes;
                    }
                    o.seed = 0x5DCCL * 31 + line;
                    final JsonObject out = new JsonObject();
                    out.addProperty("tag", ask.get("tag").getAsString());
                    out.addProperty("cfg", c[0]);
                    final JsonArray res = new JsonArray();
                    try {
                        final List<WhatIf.Snapshot> snaps = new ArrayList<>();
                        final List<String> labels = new ArrayList<>();
                        for (JsonElement e : os) {
                            final JsonObject x = e.getAsJsonObject();
                            labels.add(x.get("o").getAsString());
                            WhatIf.Snapshot s = null;
                            if (x.has("state") && !x.get("state").isJsonNull()) {
                                try {
                                    s = snap(x.get("state").getAsString(), x.has("me") ? x.get("me").getAsInt() : 0,
                                            x.has("knownTop") ? x.get("knownTop").getAsInt() : 0);
                                } catch (RuntimeException | Error ex) {
                                    s = null;
                                }
                                if (s != null && x.has("mode")) {
                                    s.mode = x.get("mode").getAsInt();
                                    s.card = x.has("card") && !x.get("card").isJsonNull() ? x.get("card").getAsString() : null;
                                }
                            }
                            snaps.add(s);
                        }
                        final long cpu0 = cpuNanos();
                        final WhatIf.Ask a = WhatIf.evaluate(snaps, cfg, o);
                        out.addProperty("cpu_ms", (cpuNanos() - cpu0) / 1_000_000);
                        out.addProperty("ms", a.nanos / 1_000_000);
                        out.addProperty("stages", a.stages);
                        out.addProperty("cut", a.cut);
                        out.addProperty("probes", a.probes);
                        out.addProperty("nodes", a.nodes);
                        out.addProperty("pairs", a.pairs);
                        out.addProperty("cut2", a.cut2);
                        WhatIf.receipt(ask.get("tag").getAsString() + " cfg=" + c[0], labels, a, o);
                        for (int i = 0; i < snaps.size(); i++) {
                            final WhatIf.Result r = a.results.get(i);
                            final JsonObject x = new JsonObject();
                            x.addProperty("o", labels.get(i));
                            if (!Double.isNaN(r.value)) {
                                x.addProperty("w", Math.round(r.value * 1e4) / 1e4);
                                x.addProperty("w_own", Math.round(r.raw * 1e4) / 1e4);
                                if (r.all > 0) {
                                    x.addProperty("all", Math.round(r.all * 1e4) / 1e4);
                                    x.addProperty("all_needs", r.allNeeds);
                                    x.addProperty("line_all", r.lineAll.length() > 300 ? r.lineAll.substring(0, 300) : r.lineAll);
                                }
                                if (r.second > 0) {
                                    x.addProperty("second", Math.round(r.second * 1e4) / 1e4);
                                    x.addProperty("with", labels.get(r.partner));
                                    x.addProperty("line2", r.line2.length() > 300 ? r.line2.substring(0, 300) : r.line2);
                                }
                                x.addProperty("kills", r.kills);
                                x.addProperty("worlds", r.worlds);
                            }
                            if (r.bestTurn >= 0) {
                                x.addProperty("turn", r.bestTurn);
                                x.addProperty("line", r.line.length() > 300 ? r.line.substring(0, 300) : r.line);
                            }
                            if (r.fail != null) {
                                x.addProperty("fail", r.fail);
                            }
                            x.addProperty("probes", r.probes);
                            x.addProperty("n", r.nodes);
                            x.addProperty("capped", r.capped);
                            if (reach && c == cfgs.get(0) && snaps.get(i) != null) {
                                x.addProperty("reach", reachOf(snaps.get(i), cfg, scorer, o.seed, snaps.size()));
                            }
                            res.add(x);
                        }
                    } catch (RuntimeException | Error ex) {
                        out.addProperty("error", String.valueOf(ex));
                    }
                    out.add("options", res);
                    System.out.println("WHATIF " + out);
                }
            }
        }
        System.out.println("WHATIF-DONE shard=" + si + "/" + sn);
    }

    /** Today's scorer's own combo term for the position: the planner's reach, lifted by its goldfish (cpPcaf's keys). */
    private static double reachOf(final WhatIf.Snapshot s, final ForcedWinSolver.Config cfg, final JsonObject sc,
            final long seed, final int menu) {
        final ForcedWinSolver sol = ForcedWinSolver.withReader(s.me, cfg, ForcedWinSolver.newSharedReader());
        final List<Integer> top = new ArrayList<>();
        int k = 0;
        for (Card c : s.me.getCardsIn(ZoneType.Library)) {
            if (k++ >= s.knownTop) {
                break;
            }
            top.add(c.getId());
        }
        final ForcedWinSolver.ReachOpts ro = new ForcedWinSolver.ReachOpts();
        ro.nodes = geti(sc, "screenNodes", 2000);
        ro.ms = geti(sc, "screenMs", 60);
        ro.maxNames = geti(sc, "reachMaxNames", 40);
        ro.window = true;
        ro.nameNodes = geti(sc, "reachNameNodes", 1000);
        ro.nameOnePass = getb(sc, "reachOnePass", true);
        ro.skipCapped = getb(sc, "reachSkipCapped", true);
        ro.sig = getb(sc, "reachSig", true);
        final ForcedWinSolver.Reach r = sol.reach(s.g, top, ro);
        double v = r.failed || Double.isNaN(r.p) ? 0.0 : r.p;
        if (!r.failed && !(r.p >= 1.0)) {
            final ForcedWinSolver.GoldfishOpts go = new ForcedWinSolver.GoldfishOpts();
            go.horizon = geti(sc, "gfHorizon", 5);
            go.samples = geti(sc, "gfSamples", 0);
            go.nodes = geti(sc, "gfNodes", 1500);
            go.extra = geti(sc, "gfExtra", 2);
            go.ms = geti(sc, "gfMs", 60);
            go.gamma = getd(sc, "gfGamma", 0.8);
            go.active = geti(sc, "gfActive", 3);
            go.lambda = getd(sc, "gfLambda", 0.6);
            go.lands = getb(sc, "gfLands", true);
            final long askNodes = geti(sc, "gfAskNodes", 60000);
            go.maxNodes = askNodes <= 0 ? 0 : Math.max(go.nodes, askNodes / Math.max(1, menu));
            go.seed = seed;
            final ForcedWinSolver.Goldfish gf = sol.goldfish(s.g, top, go);
            if (!gf.failed && !Double.isNaN(gf.g) && gf.g > v) {
                v = gf.g;
            }
        }
        return Math.round(v * 1e4) / 1e4;
    }

    private static int geti(final JsonObject o, final String k, final int d) {
        return o != null && o.has(k) ? o.get(k).getAsInt() : d;
    }

    private static double getd(final JsonObject o, final String k, final double d) {
        return o != null && o.has(k) ? o.get(k).getAsDouble() : d;
    }

    private static boolean getb(final JsonObject o, final String k, final boolean d) {
        return o != null && o.has(k) ? o.get(k).getAsBoolean() : d;
    }
}
