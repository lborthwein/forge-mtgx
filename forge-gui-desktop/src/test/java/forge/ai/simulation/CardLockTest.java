package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane searchspeed-1113 (solver key cardLock; card names are test data only). With the key, a learned card model is read
 * without the reader's monitor, so the play-out threads of one search no longer queue behind each other's sandbox learns.
 * The key must change timing only: the templates a reader holds after concurrent reads from eight threads (each on its
 * own copy of the game, as the play-outs are) are the templates a serial reader without the key learns, and every
 * instance a thread was handed equals the serial one.
 */
public class CardLockTest extends AITest {
    static final String HAND = "Chromatic Star;Sensei's Divining Top;Dark Ritual;Brainstorm;Lightning Bolt;Llanowar Elves;"
            + "Sol Ring;Counterspell";
    static final String FIELD = "Island;Swamp;Mountain;Forest;Mox Opal;Grizzly Bears";

    private static Game game() {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame(HAND, FIELD, 20, "", "Plains;Plains;Plains", 20, "human"));
    }

    private static List<Card> cards(final Game g) {
        final Player me = g.getPlayers().get(0);
        final List<Card> out = new ArrayList<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Library}) {
            for (Card c : me.getCardsIn(z)) {
                out.add(c);
            }
        }
        return out;
    }

    private static String shape(final ACard a) {
        return new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(a);
    }

    @Test
    public void theKeyIsOffByDefaultAndSetsBothReaders() {
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).cardLock);
        Assert.assertTrue(ForcedWinSolver.Config.fromProps(k -> "cardLock".equals(k) ? "true" : null).cardLock);
        Assert.assertFalse(new ForcedWinModel.Reader().cardLock);
    }

    @Test
    public void concurrentReadsLearnTheSerialTemplates() throws Exception {
        // the serial reader, key off: every card once, in zone order
        final Game g0 = game();
        final Player me0 = g0.getPlayers().get(0);
        final ForcedWinModel.Reader serial = new ForcedWinModel.Reader();
        final java.util.Map<String, String> want = new java.util.HashMap<>();
        for (Card c : cards(g0)) {
            want.put(c.getName(), shape(serial.card(g0, me0, c).instance(0)));
        }
        Assert.assertTrue(want.size() >= 10, "distinct cards: " + want.keySet());

        // the keyed reader: eight threads, each on its own copy, each reading every card twelve times in its own order
        final ForcedWinModel.Reader keyed = new ForcedWinModel.Reader();
        keyed.cardLock = true;
        final int threads = 8;
        final List<Game> copies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            copies.add(new GameCopier(g0, true).makeCopy());
        }
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final Game g = copies.get(i);
            final long seed = 1000 + i;
            final Thread t = new Thread(() -> {
                try {
                    go.await();
                    final Player me = g.getPlayers().get(0);
                    final List<Card> cs = cards(g);
                    final Random rnd = new Random(seed);
                    for (int round = 0; round < 12; round++) {
                        Collections.shuffle(cs, rnd);
                        for (Card c : cs) {
                            final ACard a = keyed.card(g, me, c);
                            Assert.assertEquals(a.id, c.getId());
                            final String got = shape(a.instance(0));
                            if (!got.equals(want.get(c.getName()))) {
                                throw new AssertionError(c.getName() + ": " + got + " != " + want.get(c.getName()));
                            }
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "cardlock-" + i);
            ts.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : ts) {
            t.join(120_000);
            Assert.assertFalse(t.isAlive(), "a reader thread is stuck");
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent read failed", failure.get());
        }
        // the same templates (a template's own id is the id of the copy that was read first: the threads read in their own
        // orders, and every reader hands out instances with the asked card's id)
        Assert.assertEquals(keyed.templates.keySet(), serial.templates.keySet());
        for (String k : serial.templates.keySet()) {
            Assert.assertEquals(JsonParser.parseString(shape(keyed.templates.get(k).instance(0))),
                    JsonParser.parseString(shape(serial.templates.get(k).instance(0))), k);
        }
        // each card was learned once, as in the serial reader
        Assert.assertEquals(keyed.probeStats.probes, serial.probeStats.probes);
    }
}
