package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiCache;
import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.IdScope;
import forge.util.MyRandom;

/**
 * state-machines-1113: {@link ForcedWinModel.Reader#card} holds one monitor per template key, not the whole reader. The
 * same cards learned by four threads at once (each on its own copy of the position, in its own id scope, AI-cache scope
 * and random stream, as a play-out worker is) give the templates and the probe count that one thread learning them in
 * turn gives: every key is learned exactly once. Card names are test data only.
 */
public class ReaderKeyLockTest extends AITest {

    private static final String[] HAND = {"Lightning Bolt", "Counterspell", "Brainstorm", "Llanowar Elves", "Grizzly Bears",
            "Giant Growth"};

    private static Game position() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                String.join(";", HAND), "Mountain;Mountain;Island;Island;Forest;Forest", 20, "", "Forest", 20, "human"));
        return g;
    }

    /** A copy of {@code g} and its seat, made on the calling thread (the deciding thread makes every live copy). */
    private static Object[] copyOf(final Game g) {
        final GameCopier copier = new GameCopier(g, true);
        final Game c = copier.makeCopy();
        return new Object[] {c, copier.find(g.getPlayers().get(0))};
    }

    /** Learns every hand card of {@code me} in {@code c}, starting at {@code offset}, inside a worker's scopes. */
    private static void learnAll(final ForcedWinModel.Reader r, final Game c, final Player me, final int offset) {
        final Random prevRandom = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(7L));
        final Object prevAiCache = AiCache.captureScope();
        AiCache.openScope();
        final Object prevIds = IdScope.capture();
        IdScope.open();
        try {
            final List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
            for (int i = 0; i < hand.size(); i++) {
                r.card(c, me, hand.get((i + offset) % hand.size()));
            }
        } finally {
            AiCache.installScope(prevAiCache);
            IdScope.install(prevIds);
            MyRandom.setThreadRandom(prevRandom);
        }
    }

    @Test
    public void fourThreadsLearnWhatOneThreadLearns() throws Exception {
        final Game g = position();
        final Player me0 = g.getPlayers().get(0);
        Assert.assertEquals(me0.getCardsIn(ZoneType.Hand).size(), HAND.length);

        final ForcedWinModel.Reader one = new ForcedWinModel.Reader();
        final Object[] solo = copyOf(g);
        learnAll(one, (Game) solo[0], (Player) solo[1], 0);

        final int threads = 4;
        final ForcedWinModel.Reader shared = new ForcedWinModel.Reader();
        final List<Object[]> copies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            copies.add(copyOf(g));
        }
        final CyclicBarrier start = new CyclicBarrier(threads);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final Object[] cp = copies.get(i);
                final int offset = i * 2;
                done.add(pool.submit(() -> {
                    start.await();
                    learnAll(shared, (Game) cp[0], (Player) cp[1], offset);
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get(5, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        Assert.assertEquals(shared.templates.keySet(), one.templates.keySet());
        // the same templates, key by key (a HashMap's iteration order follows its insertion history)
        Assert.assertEquals(com.google.gson.JsonParser.parseString(shared.exportJson()),
                com.google.gson.JsonParser.parseString(one.exportJson()), "the same templates");
        Assert.assertTrue(one.probeStats.probes > 0, "the position's cards were probed");
        Assert.assertEquals(shared.probeStats.probes, one.probeStats.probes, "each key learned once, its probes all counted");
        Assert.assertEquals(shared.probeStats.failures, one.probeStats.failures);
    }
}
