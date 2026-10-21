package forge.bench.rl;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiCache;
import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.LookaheadSearch;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * combo-assembly-1021 (B1), behind {@code plans.choose} (off by default: then it is never built). The tutor chooser for
 * this seat's single-card library searches (tutors to hand, top, graveyard or battlefield, ETB searches), one general
 * mechanism with no per-card logic:
 * <ol>
 * <li><b>Forge applies the choice.</b> Every distinct offered name is resolved by Forge in a copy of the position
 * ({@link LookaheadSearch#resolveChoice}): the card's own script moves it, shuffles, reveals and pays.</li>
 * <li><b>A batched value-head pass ranks them.</b> Each copy is featurized from the seat's own knowledge (forked from
 * the live tracker, with B0's own-placement rule inside the copy) and all of them go to the policy's value head in one
 * call ({@link RlSearch#leafValues}).</li>
 * <li><b>The solver checks the top few.</b> The value top-{@code valueTop} plus every offered route piece
 * ({@code route-pieces-1009.json}, at most {@code maxCands}) get the forced-win solver's proposals on their copy, in
 * honest worlds (the search's belief re-draw, {@code hideOwnLibrary}), in parallel, under the wall {@code wallMs}.</li>
 * <li><b>Answer:</b> a candidate with a proven line (unopposed and in every belief world run, at least {@code worlds}),
 * ties by value; else ({@code fallback=value}) the value argmax when it leads by more than {@code valueMargin}; else
 * null: the policy's ask (as without the option).</li>
 * </ol>
 * Never throws; any failure is the policy's ask. Receipts in {@link #summary()}.
 */
final class TutorChooser {
    static final String ROUTE_PIECES = "/forge/ai/simulation/route-pieces-1009.json";

    static final class Cfg {
        int valueTop = 2;
        int maxCands = 8;
        int worlds = 2;
        long wallMs = 6000;
        int threads = 4;
        boolean routes = true;
        String fallback = "value";
        double valueMargin = 0.0;
        /** The solver's per-candidate config (string values; S-b2h's keys, then these defaults where absent). */
        JsonObject solverCfg = new JsonObject();

        static final Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("valueTop", "maxCands", "worlds",
                "wallMs", "threads", "routes", "fallback", "valueMargin", "solverCfg"));

        static Cfg parse(final JsonObject o) {
            final Cfg c = new Cfg();
            if (o == null) {
                return c;
            }
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("plans.chooseCfg: unknown key " + k);
                }
            }
            if (o.has("valueTop")) c.valueTop = Integer.parseInt(o.get("valueTop").getAsString());
            if (o.has("maxCands")) c.maxCands = Integer.parseInt(o.get("maxCands").getAsString());
            if (o.has("worlds")) c.worlds = Integer.parseInt(o.get("worlds").getAsString());
            if (o.has("wallMs")) c.wallMs = Long.parseLong(o.get("wallMs").getAsString());
            if (o.has("threads")) c.threads = Integer.parseInt(o.get("threads").getAsString());
            if (o.has("routes")) c.routes = Boolean.parseBoolean(o.get("routes").getAsString());
            if (o.has("fallback")) c.fallback = o.get("fallback").getAsString();
            if (o.has("valueMargin")) c.valueMargin = Double.parseDouble(o.get("valueMargin").getAsString());
            if (o.has("solverCfg")) c.solverCfg = o.getAsJsonObject("solverCfg");
            if (c.valueTop < 0 || c.maxCands < 1 || c.worlds < 1 || c.wallMs < 500 || c.wallMs > 7000 || c.threads < 1
                    || !("value".equals(c.fallback) || "policy".equals(c.fallback))) {
                throw new IllegalArgumentException("plans.chooseCfg: valueTop >= 0, maxCands >= 1, worlds >= 1, "
                        + "wallMs 500..7000, threads >= 1, fallback value|policy");
            }
            return c;
        }
    }

    private final Cfg cfg;
    private final RlSearch rs;
    private final Player me;
    private final ForcedWinSolver.Config solverCfg;
    private final Set<String> pieces;
    private ExecutorService pool;
    private long seq = 0;

    // receipts
    int calls, skipped, decided, provenPicks, valuePicks, policyFallbacks, errors, copyFailures, solverRuns, solverCut;
    final List<Double> ms = new ArrayList<>();
    final JsonArray decisions = new JsonArray();
    final Map<String, Integer> skipWhy = new java.util.TreeMap<>();

    TutorChooser(final Cfg cfg, final RlSearch rs, final Player me, final JsonObject baseSolverCfg) {
        this.cfg = cfg;
        this.rs = rs;
        this.me = me;
        final JsonObject o = new JsonObject();
        o.addProperty("budgetMs", "3500");
        o.addProperty("engineMs", "2500");
        o.addProperty("evalReserveMs", "800");
        o.addProperty("wallGraceMs", "500");
        o.addProperty("defense", "false");
        if (baseSolverCfg != null) {
            for (Map.Entry<String, JsonElement> e : baseSolverCfg.entrySet()) {
                if (!"budgetMs".equals(e.getKey()) && !"engineMs".equals(e.getKey()) && !"evalReserveMs".equals(e.getKey())) {
                    o.add(e.getKey(), e.getValue());
                }
            }
        }
        for (Map.Entry<String, JsonElement> e : cfg.solverCfg.entrySet()) {
            o.add(e.getKey(), e.getValue());
        }
        o.addProperty("worlds", String.valueOf(cfg.worlds));
        o.addProperty("act", "false");
        this.solverCfg = SolverDriver.config(o);
        this.pieces = cfg.routes ? loadPieces() : Collections.emptySet();
    }

    static Set<String> loadPieces() {
        try (InputStream in = TutorChooser.class.getResourceAsStream(ROUTE_PIECES)) {
            if (in == null) {
                return Collections.emptySet();
            }
            final JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            final Set<String> s = new HashSet<>();
            for (JsonElement e : o.getAsJsonArray("pieces")) {
                s.add(e.getAsString());
            }
            return s;
        } catch (Exception e) {
            System.err.println("[choose] route pieces not loaded: " + e);
            return Collections.emptySet();
        }
    }

    private void skip(final String why) {
        skipped++;
        skipWhy.merge(why, 1, Integer::sum);
    }

    /** One candidate under evaluation. */
    static final class Cand {
        final String name;
        final Card rep;
        LookaheadSearch.ChoiceCopy copy;
        double value = Double.NaN;
        boolean checked, proven, cut;
        double pBelief = 0;
        String line = "";

        Cand(final String name, final Card rep) {
            this.name = name;
            this.rep = rep;
        }
    }

    /** The chooser's answer for a live library search of this seat, or null (the policy's ask). Never throws. */
    Card pick(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa, final CardCollection choices,
            final Player decider) {
        calls++;
        final long t0 = System.nanoTime();
        final JsonObject rec = new JsonObject();
        try {
            final Card c = pick0(destination, origin, sa, choices, decider, t0, rec);
            return c;
        } catch (RuntimeException | StackOverflowError e) {
            errors++;
            rec.addProperty("error", String.valueOf(e));
            System.err.println("[choose] failed, the policy answers: " + e);
            return null;
        } finally {
            if (rec.size() > 0) {
                final double dt = (System.nanoTime() - t0) / 1e6;
                ms.add(dt);
                rec.addProperty("ms", Math.round(dt));
                if (decisions.size() < 64) {
                    decisions.add(rec);
                }
                System.err.println("[choose] " + rec);
            }
        }
    }

    private Card pick0(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa,
            final CardCollection choices, final Player decider, final long t0, final JsonObject rec) {
        final Game live = me.getGame();
        if (decider != me || origin == null || origin.size() != 1 || origin.get(0) != ZoneType.Library || choices == null
                || choices.isEmpty() || live.getStack().isEmpty()) {
            skip("not a library search of ours on the stack");
            return null;
        }
        final CardCollectionView lib = me.getCardsIn(ZoneType.Library);
        for (Card c : choices) {
            if (!lib.contains(c)) {
                skip("an offered card outside our library");
                return null;
            }
        }
        final List<Card> sorted = new ArrayList<>(choices);
        sorted.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        final Map<String, Cand> byName = new LinkedHashMap<>();
        for (Card c : sorted) {
            byName.putIfAbsent(c.getName(), new Cand(c.getName(), c));
        }
        if (byName.size() < 2) {
            skip("one distinct name");
            return null;
        }
        final long deadline = t0 + cfg.wallMs * 1_000_000L;
        rec.addProperty("turn", live.getPhaseHandler().getTurn());
        rec.addProperty("source", sa == null || sa.getHostCard() == null ? "?" : sa.getHostCard().getName());
        rec.addProperty("dest", String.valueOf(destination));
        rec.addProperty("names", byName.size());
        // 1. Forge applies each choice in a copy; 2. one value batch
        final List<Cand> cands = new ArrayList<>(byName.values());
        final List<Object> payloads = new ArrayList<>();
        final List<Cand> withPayload = new ArrayList<>();
        final long seed = RlSearch.splitmix(rs.ls().nextWorld0Seed() ^ (0xC0A5L + seq++));
        for (Cand c : cands) {
            if (System.nanoTime() > deadline) {
                break;
            }
            c.copy = rs.ls().resolveChoice(live, me, c.rep.getId(), seed, new LookaheadSearch.ChoiceHooks() {
                @Override
                public Object onCopy(final Game copy, final Player cm) {
                    return rs.onCopy(copy, cm);
                }

                @Override
                public void placed(final Object ctx, final Game copy, final Player cm, final Card card) {
                    if (ctx instanceof RlSearch.CopyCtx) {
                        ((RlSearch.CopyCtx) ctx).know.onPlaced(copy, cm, card);
                    }
                }
            });
            if (c.copy.failed || c.copy.g == null) {
                copyFailures++;
                continue;
            }
            payloads.add(rs.captureLeaf(c.copy.ctx, c.copy.g, c.copy.me));
            withPayload.add(c);
        }
        rec.addProperty("copies", withPayload.size());
        rec.addProperty("copyMs", Math.round((System.nanoTime() - t0) / 1e6));
        if (withPayload.isEmpty()) {
            skip("no copy resolved");
            return null;
        }
        final double[] v = rs.leafValues(payloads);
        if (v == null || v.length != withPayload.size()) {
            errors++;
            rec.addProperty("error", "value batch failed");
            return null;
        }
        for (int i = 0; i < v.length; i++) {
            withPayload.get(i).value = v[i];
        }
        final List<Cand> byValue = new ArrayList<>(withPayload);
        byValue.sort((a, b) -> Double.compare(b.value, a.value));
        rec.addProperty("valueMs", Math.round((System.nanoTime() - t0) / 1e6));
        // 3. the solver on the value top-k plus the offered route pieces, value order, in parallel, under the wall
        final List<Cand> check = new ArrayList<>();
        for (int i = 0; i < byValue.size() && i < cfg.valueTop; i++) {
            check.add(byValue.get(i));
        }
        for (Cand c : byValue) {
            if (check.size() >= cfg.maxCands) {
                break;
            }
            if (pieces.contains(c.name) && !check.contains(c)) {
                check.add(c);
            }
        }
        if (!check.isEmpty() && System.nanoTime() < deadline) {
            final ExecutorService ex = pool();
            final ForcedWinSolver.Redraw redraw = rs.ls().opponentRedraw();
            final List<Future<?>> fs = new ArrayList<>();
            for (Cand c : check) {
                fs.add(ex.submit(() -> solve(c, redraw, seed)));
            }
            for (int i = 0; i < fs.size(); i++) {
                final long left = deadline - System.nanoTime();
                try {
                    fs.get(i).get(Math.max(1, left), TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    check.get(i).cut = true;
                    solverCut++;
                    fs.get(i).cancel(true);
                } catch (Exception e) {
                    check.get(i).cut = true;
                }
            }
        }
        // 4. the answer
        Cand best = null;
        for (Cand c : byValue) {
            if (c.proven && (best == null || c.value > best.value)) {
                best = c;
            }
        }
        String how;
        if (best != null) {
            how = "proven";
            provenPicks++;
        } else if ("value".equals(cfg.fallback) && (byValue.size() < 2
                || byValue.get(0).value - byValue.get(1).value > cfg.valueMargin)) {
            best = byValue.get(0);
            how = "value";
            valuePicks++;
        } else {
            how = "policy";
            policyFallbacks++;
        }
        final JsonArray cs = new JsonArray();
        for (Cand c : byValue) {
            final JsonObject o = new JsonObject();
            o.addProperty("n", c.name);
            o.addProperty("v", Math.round(c.value * 1000) / 1000.0);
            if (c.checked || c.cut) {
                o.addProperty("proven", c.proven);
                o.addProperty("pB", Math.round(c.pBelief * 100) / 100.0);
                if (c.cut) {
                    o.addProperty("cut", true);
                }
            }
            cs.add(o);
        }
        rec.add("cands", cs);
        rec.addProperty("how", how);
        rec.addProperty("pick", best == null ? null : best.name);
        if (best == null) {
            return null;
        }
        decided++;
        return best.rep;
    }

    private void solve(final Cand c, final ForcedWinSolver.Redraw redraw, final long seed) {
        final java.util.Random prevR = MyRandom.getThreadRandom();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new java.util.Random(RlSearch.splitmix(seed ^ c.rep.getId())));
        AiCache.openScope();
        try {
            final SolverDriver d = new SolverDriver(c.copy.me, solverCfg);
            d.solver().redraw = redraw;
            final List<ForcedWinSolver.Proposal> props = d.probe(() -> d.solver().propose(c.copy.g));
            synchronized (this) {
                solverRuns++;
            }
            for (ForcedWinSolver.Proposal p : props) {
                if (p.pBelief > c.pBelief) {
                    c.pBelief = p.pBelief;
                    c.line = p.text;
                }
                if (p.proven(cfg.worlds)) {
                    c.proven = true;
                }
            }
            c.checked = true;
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("[choose] solver failed on " + c.name + ": " + e);
        } finally {
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prevR);
        }
    }

    private synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(cfg.threads, r -> {
                final Thread t = new Thread(r, "choose-" + me.getId());
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }

    JsonObject summary() {
        final JsonObject o = new JsonObject();
        o.addProperty("chooseCalls", calls);
        o.addProperty("chooseSkipped", skipped);
        o.addProperty("chooseDecided", decided);
        o.addProperty("chooseProvenPicks", provenPicks);
        o.addProperty("chooseValuePicks", valuePicks);
        o.addProperty("choosePolicyFallbacks", policyFallbacks);
        o.addProperty("chooseErrors", errors);
        o.addProperty("chooseCopyFailures", copyFailures);
        o.addProperty("chooseSolverRuns", solverRuns);
        o.addProperty("chooseSolverCut", solverCut);
        final JsonObject w = new JsonObject();
        skipWhy.forEach(w::addProperty);
        o.add("chooseSkipWhy", w);
        double mx = 0, sum = 0;
        for (double x : ms) {
            mx = Math.max(mx, x);
            sum += x;
        }
        o.addProperty("chooseMsMax", Math.round(mx));
        o.addProperty("chooseMsSum", Math.round(sum));
        o.add("chooseDecisions", decisions);
        return o;
    }
}
