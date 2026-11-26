package forge.ai.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * defense-other-cubes-1126: the defender's deck prior ({@link LookaheadSearch#opponentRedrawPrior} with fillBasics) fills
 * unlinked draws with basics only on a belief pool the co-draft prior covers ({@link LookaheadSearch#PRIOR_COVERAGE_MIN}).
 * A covered pool (the jar's own pool with the jar's prior) draws exactly as before: the same names and the same RNG state
 * after, pinned by a digest recorded on the live jar. An uncovered pool draws as fillBasics=false, so an unlinked card
 * stays the card drawn.
 */
public class DefensePriorCoverageTest extends SimulationTest {

    /** A toy pool (Forge names); the toy priors below give it rows for some of these names. */
    private static final String[] TOY = {"Counterspell", "Lightning Bolt", "Hill Giant", "Shock", "Giant Growth",
        "Grizzly Bears", "Wrath of God", "Mana Leak", "Prodigal Pyromancer", "Serra Angel"};

    /**
     * The covered draws' digest (names and the RNG's next value after each draw; {@link #coveredDraws}), recorded on the
     * live jar d3cb8fa0 (fork 053797d90ad). The fix must reproduce it exactly.
     */
    private static final String COVERED_DIGEST = "1cec1031f22715a4aefdb41384762a04dd5cbb34ae332ca457f8a898d1048a7d";

    private static String cubeFile(final String[] names) throws IOException {
        final JsonArray cards = new JsonArray();
        for (String n : names) {
            final JsonObject c = new JsonObject();
            c.addProperty("name", n);
            c.add("tags", new JsonArray());
            cards.add(c);
        }
        final JsonObject o = new JsonObject();
        o.addProperty("schema", "mtgx-belief-cube/1");
        o.add("cards", cards);
        final Path p = Files.createTempFile("coverage-cube", ".json");
        Files.writeString(p, o.toString());
        p.toFile().deleteOnExit();
        return p.toString();
    }

    /** Seat b shows {@code shown} (graveyard) and hides 3 hand cards and 12 library cards. */
    private Game board(final List<String> shown) {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0);
        final Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 12; i++) {
            addCardToZone("Raging Goblin", b, ZoneType.Library);
            addCardToZone("Mountain", a, ZoneType.Library);
        }
        for (String n : shown) {
            addCardToZone(n, b, ZoneType.Graveyard);
        }
        for (String n : new String[] {"Llanowar Elves", "Dark Ritual", "Craw Wurm"}) {
            addCardToZone(n, b, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** A search over the pool file {@code cube} ({@code pool} null) or the event pool {@code pool}, as live sets them. */
    private static LookaheadSearch search(final String cube, final String sha, final List<String> pool, final List<String> seen) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.threads = 1;
        c.seed = 1126L;
        c.belief = "uniform";
        if (pool != null) {
            c.beliefPool = pool;
            c.beliefSingleton = true;
        } else {
            c.beliefCube = cube;
            c.beliefCubeSha256 = sha;
            c.beliefSingleton = BeliefSampler.LIVE_CUBE_SINGLETON;
        }
        c.beliefSeen = seen;
        return new LookaheadSearch(c);
    }

    /** One re-drawn copy: the opponent's hidden names (hand, then library) and the RNG's next value after the draw. */
    private static List<String> draw(final Game game, final ForcedWinSolver.Redraw rd, final long seed) {
        final Player a = game.getPlayers().get(0);
        final GameCopier cp = new GameCopier(game, true);
        final Game g = cp.makeCopy();
        final Random rng = new Random(seed);
        rd.apply(game, a, g, (Player) cp.find(a), rng);
        final Player o = (Player) cp.find(game.getPlayers().get(1));
        final List<String> out = new ArrayList<>();
        for (Card x : o.getCardsIn(ZoneType.Hand)) {
            out.add("H:" + x.getName());
        }
        for (Card x : o.getCardsIn(ZoneType.Library)) {
            out.add("L:" + x.getName());
        }
        out.add("rng:" + rng.nextLong());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Double>> jarPrior() throws Exception {
        final java.lang.reflect.Method m = forge.bench.rl.PlanSearch.class.getDeclaredMethod("loadSyn", String.class);
        m.setAccessible(true);
        return (Map<String, Map<String, Double>>) m.invoke(null, forge.bench.rl.PlanSearch.DEFEND_PRIOR);
    }

    /** The first {@code n} names of {@code names} (in order) with ({@code row}) or without a row in {@code syn}. */
    private static List<String> firstWith(final List<String> names, final Map<String, Map<String, Double>> syn,
            final boolean row, final int n) {
        final List<String> out = new ArrayList<>();
        for (String s : names) {
            if (out.size() < n && !BeliefSampler.isBasic(s) && syn.containsKey(s) == row) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Whether {@code name} has a co-draft link from one of {@code shown} or {@code seen} (earlier-game cards join the shown
     * set), or is one of {@code seen}.
     */
    private static boolean linked(final String name, final List<String> shown, final List<String> seen,
            final Map<String, Map<String, Double>> syn) {
        final List<String> from = new ArrayList<>(shown);
        if (seen != null) {
            if (seen.contains(name)) {
                return true;
            }
            from.addAll(seen);
        }
        for (String s : from) {
            final Map<String, Double> r = syn.get(s);
            if (r != null && r.get(name) != null && r.get(name) > 0) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(final List<String> lines) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String l : lines) {
            md.update((l + "\n").getBytes(StandardCharsets.UTF_8));
        }
        final StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    @Test
    public void coverageIsTheShareOfNonBasicNamesWithARow() throws Exception {
        final Map<String, Map<String, Double>> syn = new HashMap<>();
        syn.put("Counterspell", Map.of("Mana Leak", 0.5));
        syn.put("Shock", Map.of());
        syn.put("Island", Map.of("Counterspell", 0.9)); // a basic: never counted
        syn.put("Black Lotus", Map.of()); // not in the pool
        AssertJUnit.assertEquals(0.5, LookaheadSearch.coverageOf(
                Arrays.asList("Counterspell", "Shock", "Hill Giant", "Mana Leak", "Island", "Forest"), syn), 1e-12);
        AssertJUnit.assertEquals(1.0, LookaheadSearch.coverageOf(Arrays.asList("Island", "Swamp"), syn), 1e-12);
        AssertJUnit.assertEquals(0.0, LookaheadSearch.coverageOf(Arrays.asList(TOY), new HashMap<>()), 1e-12);
        // the threshold sits strictly between "nothing" and "everything"
        AssertJUnit.assertTrue(LookaheadSearch.PRIOR_COVERAGE_MIN > 0.2 && LookaheadSearch.PRIOR_COVERAGE_MIN < 0.9);

        // per (pool, prior) pair, by identity, the same from many threads at once
        final BeliefSampler.Cube cube = BeliefSampler.cubeOf(Arrays.asList(TOY), 2);
        AssertJUnit.assertEquals(TOY.length, cube.names.size());
        final Map<String, Map<String, Double>> two = new HashMap<>();
        two.put("Shock", Map.of("Lightning Bolt", 0.8));
        two.put("Serra Angel", Map.of());
        final ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            final List<Future<Double>> fs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                fs.add(ex.submit(() -> LookaheadSearch.priorCoverage(cube, two)));
            }
            for (Future<Double> f : fs) {
                AssertJUnit.assertEquals(0.2, f.get(), 1e-12);
            }
        } finally {
            ex.shutdownNow();
        }
        // another prior object over the same pool is its own entry
        final Map<String, Map<String, Double>> all = new HashMap<>();
        for (String n : TOY) {
            all.put(n, Map.of());
        }
        AssertJUnit.assertEquals(1.0, LookaheadSearch.priorCoverage(cube, all), 1e-12);
        AssertJUnit.assertEquals(0.2, LookaheadSearch.priorCoverage(cube, two), 1e-12);
    }

    /** At the threshold the pool is covered (fillBasics applies); one row fewer and it is not. */
    @Test
    public void theThresholdIsInclusive() throws Exception {
        final String cube = cubeFile(TOY);
        final int need = (int) Math.ceil(LookaheadSearch.PRIOR_COVERAGE_MIN * TOY.length - 1e-9);
        final Map<String, Map<String, Double>> at = new HashMap<>();
        at.put("Shock", Map.of("Lightning Bolt", 0.8));
        for (int i = 0; at.size() < need; i++) {
            at.putIfAbsent(TOY[i], Map.of());
        }
        final Map<String, Map<String, Double>> below = new HashMap<>(at);
        for (String n : TOY) {
            if (below.size() == need && !"Shock".equals(n) && below.containsKey(n)) {
                below.remove(n);
            }
        }
        AssertJUnit.assertEquals(need - 1, below.size());
        final Game game = board(List.of("Shock"));
        forge.util.IdScope.open();
        final LookaheadSearch s = search(cube, null, null, null);
        try {
            int keptBelow = 0;
            for (long seed = 0; seed < 10; seed++) {
                for (String x : draw(game, s.opponentRedrawPrior(at, 20, true, 4), seed)) {
                    final String n = x.substring(x.indexOf(':') + 1);
                    if (!x.startsWith("rng:")) {
                        AssertJUnit.assertTrue("at the threshold only linked cards and basics: " + n,
                                BeliefSampler.isBasic(n) || "Lightning Bolt".equals(n));
                    }
                }
                AssertJUnit.assertEquals(draw(game, s.opponentRedrawPrior(below, 20, false, 4), seed),
                        draw(game, s.opponentRedrawPrior(below, 20, true, 4), seed));
                for (String x : draw(game, s.opponentRedrawPrior(below, 20, true, 4), seed)) {
                    final String n = x.substring(x.indexOf(':') + 1);
                    if (!x.startsWith("rng:") && !BeliefSampler.isBasic(n) && !"Lightning Bolt".equals(n)) {
                        keptBelow++;
                    }
                }
            }
            AssertJUnit.assertTrue("below the threshold an unlinked card stays", keptBelow > 0);
        } finally {
            s.shutdown();
            forge.util.IdScope.close();
        }
    }

    /**
     * The jar's own pool with the jar's prior (the live defense on mush): fillBasics applies, and the draws (names and the
     * RNG state after) are exactly the live jar's, both on the bundled pool file and on the same names sent as an event
     * pool, with and without earlier-game cards.
     */
    @Test
    public void aCoveredPoolDrawsExactlyAsBefore() throws Exception {
        final Map<String, Map<String, Double>> syn = jarPrior();
        final List<String> names = BeliefSampler.loadCube(BeliefSampler.LIVE_CUBE, BeliefSampler.LIVE_CUBE_SHA256, 8).names;
        final List<String> shown = firstWith(names, syn, true, 3);
        final List<String> seen = firstWith(names.subList(names.size() / 2, names.size()), syn, true, 2);
        AssertJUnit.assertEquals(3, shown.size());
        final Game game = board(shown);
        forge.util.IdScope.open();
        final List<LookaheadSearch> searches = List.of(
                search(BeliefSampler.LIVE_CUBE, BeliefSampler.LIVE_CUBE_SHA256, null, null),
                search(BeliefSampler.LIVE_CUBE, BeliefSampler.LIVE_CUBE_SHA256, null, seen),
                search(null, null, names, null));
        final List<String> lines = new ArrayList<>();
        try {
            for (int k = 0; k < searches.size(); k++) {
                final ForcedWinSolver.Redraw rd = searches.get(k).opponentRedrawPrior(syn, 20, true, 4);
                for (long seed = 0; seed < 16; seed++) {
                    final List<String> got = draw(game, rd, seed);
                    AssertJUnit.assertEquals(16, got.size());
                    for (String x : got) {
                        final String n = x.substring(x.indexOf(':') + 1);
                        if (!x.startsWith("rng:")) {
                            AssertJUnit.assertTrue("fillBasics on the covered pool: " + n,
                                    BeliefSampler.isBasic(n) || linked(n, shown, k == 1 ? seen : null, syn));
                        }
                    }
                    lines.add(k + "|" + seed + "|" + String.join(",", got));
                }
            }
        } finally {
            for (LookaheadSearch s : searches) {
                s.shutdown();
            }
            forge.util.IdScope.close();
        }
        AssertJUnit.assertEquals("covered draws digest (" + lines.size() + " draws; first " + lines.get(0) + ")",
                COVERED_DIGEST, sha256(lines));
    }

    /**
     * A pool the prior does not cover draws exactly as fillBasics=false (names and the RNG state after), so an unlinked
     * card stays the card drawn; on a toy prior and on the jar's prior over names it has no row for.
     */
    @Test
    public void anUncoveredPoolKeepsUnlinkedCards() throws Exception {
        final Map<String, Map<String, Double>> toy = new HashMap<>();
        toy.put("Shock", Map.of("Lightning Bolt", 0.8));
        final Map<String, Map<String, Double>> jar = jarPrior();
        final List<String> rows = firstWith(
                BeliefSampler.loadCube(BeliefSampler.LIVE_CUBE, BeliefSampler.LIVE_CUBE_SHA256, 8).names, jar, true, 2);
        final List<String> offPrior = firstWith(Arrays.asList(TOY), jar, false, TOY.length);
        AssertJUnit.assertTrue("the toy names are mostly off the jar's prior: " + offPrior, offPrior.size() >= 6);
        final List<String> mixed = new ArrayList<>(rows);
        mixed.addAll(offPrior);
        // (no new method here: on the live jar this test fails on what it draws)
        AssertJUnit.assertTrue("at most 2 of " + mixed.size() + " names have a row", 2 * 2 < mixed.size());

        forge.util.IdScope.open();
        final LookaheadSearch toyS = search(cubeFile(TOY), null, null, null);
        final LookaheadSearch jarS = search(null, null, mixed, null);
        try {
            int kept = 0;
            final Game toyGame = board(List.of("Shock"));
            final Game jarGame = board(rows);
            for (long seed = 0; seed < 12; seed++) {
                final List<String> a = draw(toyGame, toyS.opponentRedrawPrior(toy, 20, true, 4), seed);
                AssertJUnit.assertEquals("toy prior, seed " + seed, draw(toyGame, toyS.opponentRedrawPrior(toy, 20, false, 4), seed), a);
                final List<String> b = draw(jarGame, jarS.opponentRedrawPrior(jar, 20, true, 4), seed);
                AssertJUnit.assertEquals("jar prior, seed " + seed, draw(jarGame, jarS.opponentRedrawPrior(jar, 20, false, 4), seed), b);
                for (String x : a) {
                    final String n = x.substring(x.indexOf(':') + 1);
                    if (!x.startsWith("rng:") && !BeliefSampler.isBasic(n) && !"Lightning Bolt".equals(n)) {
                        kept++;
                    }
                }
                for (String x : b) {
                    final String n = x.substring(x.indexOf(':') + 1);
                    if (!x.startsWith("rng:") && offPrior.contains(n)) {
                        kept++;
                    }
                }
            }
            AssertJUnit.assertTrue("unlinked cards stay as drawn", kept > 0);
        } finally {
            toyS.shutdown();
            jarS.shutdown();
            forge.util.IdScope.close();
        }
    }
}
