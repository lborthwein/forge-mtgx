package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.bench.rl.CardIndex;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The look-ahead's per-decision wall budget (interactive play): past the budget the search plays Forge AI's own
 * answer and counts the decision as capped; a budget that is not reached changes nothing; the live game is never
 * touched either way. Lane sc-wallguard-1009: a play-out stuck inside ONE main-loop step is stopped by its policy
 * seat's next service request past the budget (the cooperative stop) or, if it neither steps nor asks, by the hard wall
 * at the budget plus {@link LookaheadSearch#WALL_GRACE_MS}; either way the decision is capped and plays the default, for
 * the K8 path ({@link LookaheadSearch#decide}) and the S1 path ({@link LookaheadSearch#decideGiven}); a straggler never
 * writes into the search's counters after the cut.
 */
public class LookaheadBudgetTest extends SimulationTest {

    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
            for (int i = 0; i < 4; i++) {
                addCard("Mountain", p);
                addCard("Forest", p);
            }
            addCard("Grizzly Bears", p);
            addCard("Hill Giant", p);
        }
        addCardToZone("Forest", a, ZoneType.Hand);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        addCardToZone("Hill Giant", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch search(long budgetMs) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 8;
        c.breadth = 4;
        c.horizonTurns = 2;
        c.threads = 2;
        c.seed = 719_000_001L;
        c.budgetMs = budgetMs;
        return new LookaheadSearch(c);
    }

    private static String label(List<SpellAbility> answer) {
        return answer == null || answer.isEmpty() ? "pass" : answer.get(0).getHostCard().getName() + "::" + answer.get(0).getDescription();
    }

    @Test
    public void overBudgetPlaysForgesAnswer() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = search(1);
        try {
            List<SpellAbility> answer = s.decide(ctrl, def);
            AssertJUnit.assertSame("capped search must play Forge's own answer", def, answer);
            AssertJUnit.assertEquals(1, s.getStats().searched);
            AssertJUnit.assertEquals(1, s.getStats().capped);
            AssertJUnit.assertEquals(0, s.getStats().departed);
            AssertJUnit.assertTrue(s.getStats().rolloutsAborted > 0);
            AssertJUnit.assertEquals(0, s.getStats().rolloutFailures);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void unreachedBudgetChangesNothing() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch none = search(0);
        LookaheadSearch wide = search(600_000);
        try {
            String x = label(none.decide(ctrl, def));
            String y = label(wide.decide(ctrl, def));
            AssertJUnit.assertEquals(x, y);
            AssertJUnit.assertEquals(0, none.getStats().capped);
            AssertJUnit.assertEquals(0, wide.getStats().capped);
            AssertJUnit.assertEquals(0, wide.getStats().rolloutsAborted);
            AssertJUnit.assertEquals(none.getStats().rollouts, wide.getStats().rollouts);
            AssertJUnit.assertEquals(none.getStats().steps, wide.getStats().steps);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        } finally {
            none.shutdown();
            wide.shutdown();
        }
    }

    // ------------------------------------------------------------------ sc-wallguard-1009

    private static final long BUDGET = 1500L;
    /** Scheduling slack on a loaded host for the wall-time assertions (a test lease, 4 CPUs). */
    private static final long SLACK = 1500L;

    /** Two worlds (few copies to prepare, so the budget is not spent before the play-outs start), two threads. */
    private static LookaheadSearch given(long budgetMs) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 2;
        c.threads = 2;
        c.seed = 721_580_901L;
        c.budgetMs = budgetMs;
        return new LookaheadSearch(c);
    }

    /** The given set: pass first (the default), then every distinct legal play of seat a, in Forge's order. */
    private static List<SpellAbility> givenSet(Player a) {
        final List<SpellAbility> g = new ArrayList<>();
        g.add(null);
        g.addAll(new SpellAbilityPicker(a).getCandidateSpellsAndAbilities());
        return g;
    }

    /** Wait (at most 30 s) until no stuck seat or step hook is running, then let the stopped play-outs unwind. */
    private static void awaitIdle(AtomicInteger running) throws InterruptedException {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (running.get() > 0 && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        AssertJUnit.assertEquals("the stuck play-outs ended once released", 0, running.get());
        Thread.sleep(500);
    }

    /**
     * Policy play-out hooks whose seat keeps its play-out inside ONE main-loop step at its first priority. With
     * {@code asks} it sends a "service request" every 5 ms and, as RlSearch's play-out endpoint, refuses one once its
     * decision is past the budget ({@link LookaheadSearch.FirstAction#overBudget}); it then ends its copy as RlSeat does
     * on a transport fault (game over, a seat failure). Without {@code asks} it neither steps nor asks until
     * {@link #release} (a loop inside one Forge step that sends no request).
     */
    static final class StuckSeatHooks implements LookaheadSearch.SearchHooks {
        final boolean asks;
        final AtomicInteger seats = new AtomicInteger(), requests = new AtomicInteger(), refused = new AtomicInteger(),
                running = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);

        StuckSeatHooks(boolean asks) {
            this.asks = asks;
        }

        @Override
        public Object onCopy(Game copy, Player me) {
            return null;
        }

        @Override
        public Object captureLeaf(Object ctx, Game g, Player me) {
            throw new IllegalStateException("no value leaf in this test");
        }

        @Override
        public double[] leafValues(List<Object> payloads) {
            throw new IllegalStateException("no value leaf in this test");
        }

        @Override
        public LookaheadSearch.PlayoutSeat playoutSeat(Object ctx, Game g, Player me, LookaheadSearch.FirstAction first,
                long seed, LookaheadSearch.LeafProbe probe) {
            seats.incrementAndGet();
            final String[] failure = {null};
            final PlayerControllerAi c = new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
                @Override
                public List<SpellAbility> chooseSpellAbilityToPlay() {
                    running.incrementAndGet();
                    try {
                        while (true) {
                            if (asks) {
                                if (first.overBudget()) {
                                    refused.incrementAndGet();
                                    failure[0] = "transport: java.io.IOException: budget";
                                    getGame().setGameOver(GameEndReason.Draw);
                                    return null;
                                }
                                requests.incrementAndGet();
                            } else if (release.getCount() == 0) {
                                return null;
                            }
                            Thread.sleep(asks ? 5 : 20);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    } finally {
                        running.decrementAndGet();
                    }
                }
            };
            return new LookaheadSearch.PlayoutSeat() {
                @Override
                public PlayerControllerAi controller() {
                    return c;
                }

                @Override
                public String failure() {
                    return failure[0];
                }
            };
        }
    }

    /** K8 path: one play-out stuck inside a main-loop step (no step, no request) is cut by the hard wall. */
    @Test
    public void k8PlayoutStuckInOneStepIsCutByTheWall() throws Exception {
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = given(BUDGET);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean once = new AtomicBoolean();
        final AtomicInteger running = new AtomicInteger();
        s.playoutStepHook = g -> {
            if (once.compareAndSet(false, true)) {
                running.incrementAndGet();
                try {
                    release.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
            }
        };
        try {
            final long t0 = System.nanoTime();
            List<SpellAbility> answer = s.decide(ctrl, def);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            AssertJUnit.assertSame("a cut search plays Forge's own answer", def, answer);
            AssertJUnit.assertEquals(1, s.getStats().capped);
            AssertJUnit.assertEquals(1, s.getStats().wallCuts);
            AssertJUnit.assertTrue(s.getStats().wallStragglers >= 1);
            AssertJUnit.assertTrue("the wall waits out its grace: " + ms + " ms",
                    ms >= BUDGET + LookaheadSearch.WALL_GRACE_MS - 100);
            AssertJUnit.assertTrue("the wall bounds the decision: " + ms + " ms",
                    ms < BUDGET + LookaheadSearch.WALL_GRACE_MS + SLACK);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            // the straggler, released, stops at its next step and never writes into the search's counters
            final String stats = s.getStats().toJson().toString();
            release.countDown();
            awaitIdle(running);
            AssertJUnit.assertEquals(stats, s.getStats().toJson().toString());
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertTrue(s.getStats().toJson().toString().contains("\"wallCuts\":1"));
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    /**
     * K8 path with a budget: a play-out worker that dies (an Error, which play() does not catch) fails the decision as
     * before the lane (Forge's answer is played), and its sibling play-outs are told to stop and never write afterwards.
     */
    @Test
    public void k8WorkerFailureWithABudgetPlaysForgesAnswerAndStopsTheOthers() throws Exception {
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = given(60_000);
        final AtomicBoolean once = new AtomicBoolean();
        s.playoutStepHook = g -> {
            if (once.compareAndSet(false, true)) {
                throw new AssertionError("test: a dying play-out worker");
            }
        };
        try {
            final long t0 = System.nanoTime();
            List<SpellAbility> answer = s.decide(ctrl, def);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            AssertJUnit.assertSame("a failed search plays Forge's own answer", def, answer);
            AssertJUnit.assertEquals(1, s.getStats().departFallback);
            AssertJUnit.assertTrue("no wait for the budget: " + ms + " ms", ms < 30_000);
            final String stats = s.getStats().toJson().toString();
            Thread.sleep(1500);
            AssertJUnit.assertEquals("the stopped siblings never write after the failure", stats, s.getStats().toJson().toString());
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        } finally {
            s.shutdown();
        }
    }

    /** S1 path: policy seats looping in one step with requests stop at their first request past the budget. */
    @Test
    public void givenPlayoutSeatLoopingWithRequestsStopsAtItsNextRequest() throws Exception {
        Game game = board();
        Player a = game.getPlayers().get(0);
        List<SpellAbility> g = givenSet(a);
        AssertJUnit.assertTrue("the board needs at least 3 legal plays", g.size() >= 4);
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = given(BUDGET);
        StuckSeatHooks h = new StuckSeatHooks(true);
        s.setHooks(h, false, true);
        try {
            final long t0 = System.nanoTime();
            LookaheadSearch.GivenResult r = s.decideGiven(game, a, g, 4);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            AssertJUnit.assertEquals("capped", r.outcome);
            AssertJUnit.assertEquals("a capped search plays the default (the policy's own choice)", 0, r.chosen);
            AssertJUnit.assertFalse("the cooperative stop, not the wall", r.wall);
            AssertJUnit.assertEquals(0, s.getStats().wallCuts);
            AssertJUnit.assertEquals(1, s.getStats().capped);
            AssertJUnit.assertTrue(s.getStats().rolloutsAborted > 0);
            AssertJUnit.assertEquals("a stop by the budget is no seat failure", 0, s.getStats().playoutSeatFailures);
            AssertJUnit.assertTrue(h.requests.get() > 0 && h.refused.get() > 0);
            AssertJUnit.assertTrue("stopped before the wall: " + ms + " ms", ms < BUDGET + LookaheadSearch.WALL_GRACE_MS);
            AssertJUnit.assertTrue("not before the budget: " + ms + " ms", ms >= BUDGET - 50);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        } finally {
            s.shutdown();
        }
    }

    /** S1 path: policy seats stuck in one step without any request are cut by the hard wall. */
    @Test
    public void givenPlayoutSeatStuckWithoutRequestsIsCutByTheWall() throws Exception {
        Game game = board();
        Player a = game.getPlayers().get(0);
        List<SpellAbility> g = givenSet(a);
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = given(BUDGET);
        StuckSeatHooks h = new StuckSeatHooks(false);
        s.setHooks(h, false, true);
        try {
            final long t0 = System.nanoTime();
            LookaheadSearch.GivenResult r = s.decideGiven(game, a, g, 4);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            AssertJUnit.assertEquals("capped", r.outcome);
            AssertJUnit.assertEquals(0, r.chosen);
            AssertJUnit.assertEquals(-1, r.chosenMacro);
            AssertJUnit.assertTrue(r.wall);
            AssertJUnit.assertEquals(1, s.getStats().wallCuts);
            AssertJUnit.assertEquals("both pool threads were stuck", 2, s.getStats().wallStragglers);
            AssertJUnit.assertEquals("the queued play-outs never started", 2, h.seats.get());
            AssertJUnit.assertTrue("the wall waits out its grace: " + ms + " ms",
                    ms >= BUDGET + LookaheadSearch.WALL_GRACE_MS - 100);
            AssertJUnit.assertTrue("the wall bounds the decision: " + ms + " ms",
                    ms < BUDGET + LookaheadSearch.WALL_GRACE_MS + SLACK);
            for (double[] v : r.values) {
                for (double x : v) {
                    AssertJUnit.assertEquals(Double.NEGATIVE_INFINITY, x);
                }
            }
            final String stats = s.getStats().toJson().toString();
            h.release.countDown();
            awaitIdle(h.running);
            AssertJUnit.assertEquals("stragglers never write after the cut", stats, s.getStats().toJson().toString());
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            // the next decision has its own budget: the wall's stop of the last one never leaks into it (its play-outs
            // would all stop at once); one that ends within its budget stopped none
            final long ab = s.getStats().rolloutsAborted;
            LookaheadSearch.GivenResult r2 = s.decideGiven(game, a, g, 4);
            AssertJUnit.assertFalse(r2.wall);
            if (r2.ms < BUDGET) {
                AssertJUnit.assertFalse(r2.outcome, "capped".equals(r2.outcome));
                AssertJUnit.assertEquals(ab, s.getStats().rolloutsAborted);
            }
        } finally {
            h.release.countDown();
            s.shutdown();
        }
    }

    /**
     * The S-c play-out endpoint itself ({@link RlSearch#playoutSeat}): a policy play-out seat's DECIDE goes to the
     * service while its decision is within budget, and is refused (never sent) once the decision is past it; the seat
     * then ends its copy with a failure that names the budget, and the search counts the stop.
     */
    @Test
    public void policyPlayoutEndpointRefusesRequestsPastTheBudget() throws Exception {
        final String sha = "7f7d160785e58bd1da37435f8ed71e145d761fb34f9d45b40809880982388465";
        final Path idx = Files.createTempFile("wallguard-index-", ".tsv");
        Files.write(idx, "<pad>\t0\n<unk>\t1\nMountain\t2\nForest\t3\nGrizzly Bears\t4\nHill Giant\t5\nLightning Bolt\t6\n"
                .getBytes(StandardCharsets.UTF_8));
        try (FakeSearchService svc = new FakeSearchService(sha)) {
            final JsonObject spec = new JsonObject();
            spec.addProperty("server", svc.address());
            spec.addProperty("playout", "policy");
            spec.addProperty("leaf", "value");
            spec.addProperty("policySha", sha);
            spec.addProperty("budgetMs", 8000);
            final RlSearch rs = new RlSearch(RlSearch.Config.parse(spec), 1L, 2L, CardIndex.load(idx), null, "test", "t");
            try {
                for (boolean over : new boolean[] {false, true}) {
                    // a fresh board each time: the refused seat ends the game it plays in
                    final Game game = board();
                    final Player a = game.getPlayers().get(0);
                    final LookaheadSearch.FirstAction first = new LookaheadSearch.FirstAction(new LookaheadSearch.Cand(null, false));
                    first.budget = new LookaheadSearch.Budget(System.nanoTime() + (over ? -1L : 60_000_000_000L));
                    AssertJUnit.assertEquals(over, first.overBudget());
                    final LookaheadSearch.PlayoutSeat seat = rs.playoutSeat(rs.onCopy(game, a), game, a, first, 7L, null);
                    final PlayerControllerAi pc = seat.controller();
                    final long d0 = svc.decides.get();
                    AssertJUnit.assertNull("the forced first action (pass) sends nothing", pc.chooseSpellAbilityToPlay());
                    AssertJUnit.assertEquals(d0, svc.decides.get());
                    pc.chooseSpellAbilityToPlay(); // the policy's own next priority decision: a DECIDE
                    if (!over) {
                        AssertJUnit.assertTrue("within budget the seat asks the service", svc.decides.get() > d0);
                        AssertJUnit.assertNull(seat.failure());
                        AssertJUnit.assertFalse(game.isGameOver());
                    } else {
                        AssertJUnit.assertEquals("past the budget nothing is sent", d0, svc.decides.get());
                        AssertJUnit.assertNotNull(seat.failure());
                        AssertJUnit.assertTrue(seat.failure(), seat.failure().contains("budget"));
                        AssertJUnit.assertTrue("the seat ends its copy", game.isGameOver());
                    }
                }
                AssertJUnit.assertTrue(rs.summary().toString().contains("\"playout_budget_stops\":1"));
            } finally {
                rs.close();
            }
        } finally {
            Files.deleteIfExists(idx);
        }
    }
}
