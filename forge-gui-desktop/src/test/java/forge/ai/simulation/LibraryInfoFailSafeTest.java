package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.LibraryInfoListener;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Review #172 S1 / S2, on the research branch. A library-info listener that throws never takes the game down: the game
 * catches the exception, tells the listener, and the effect goes on. InfoStates fails closed to knowing nothing. A
 * consumer never attaches InfoStates (absent means the visible top only), and the listener list tolerates a change while
 * it is being fired.
 */
public class LibraryInfoFailSafeTest extends forge.ai.AITest {

    static Player me(final Game g) {
        return KnownLibTest.me(g);
    }

    /** A listener that throws: Brainstorm still resolves, the listener is told, the other listener (InfoStates) is unaffected. */
    @Test
    public void aThrowingListenerDoesNotStopTheEffect() {
        final Game g = KnownLibTest.game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final InfoStates s = InfoStates.attach(g);
        final int[] told = {0};
        g.addLibraryInfoListener(new LibraryInfoListener() {
            @Override
            public void onLibraryInfo(final Kind kind, final List<Player> observers, final List<Card> cards, final ZoneType zone) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onListenerFailure(final RuntimeException failure) {
                told[0]++;
            }
        });
        final int hand0 = me(g).getCardsIn(ZoneType.Hand).size();
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        Assert.assertTrue(g.getStack().isEmpty(), "Brainstorm resolved");
        Assert.assertEquals(me(g).getCardsIn(ZoneType.Hand).size(), hand0 - 1 + 3 - 2, "drew three, put two back");
        Assert.assertTrue(told[0] > 0, "the failing listener was told");
        Assert.assertEquals(s.known(me(g), me(g)).size(), 2, "InfoStates itself did not fail: the put-backs are known");
    }

    /** InfoStates' own failure: caught inside, never rethrown; every library is forgotten (fails closed). */
    @Test
    public void anInfoStatesFailureForgetsEverything() {
        final Game g = KnownLibTest.game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final InfoStates s = InfoStates.attach(g);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        Assert.assertEquals(s.known(me(g), me(g)).size(), 2);
        // a malformed observation (no observer list) makes the update throw inside: caught, everything forgotten
        s.onLibraryInfo(LibraryInfoListener.Kind.ARRANGE, null, new ArrayList<>(me(g).getCardsIn(ZoneType.Library)),
                ZoneType.Library);
        Assert.assertEquals(s.failures, 1);
        Assert.assertTrue(s.known(me(g), me(g)).isEmpty(), "fails closed to knowing nothing");
        // the game goes on: a draw works
        me(g).drawCards(1);
    }

    /** S2: a consumer never attaches; with none attached the redraw keeps only what Forge shows (no exception). */
    @Test
    public void aConsumerNeverAttaches() {
        final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        Assert.assertNull(InfoStates.forConsumer(g));
        Assert.assertFalse(g.hasLibraryInfoListeners(), "nothing was attached");
        final Game c = new GameCopier(g, true).makeCopy();
        final Player cm = c.getPlayer(me(g).getId());
        InfoStates.keepOwnLibrary(null, me(g), cm, new Random(3));
        Assert.assertNull(InfoStates.oppPins(null, me(g), KnownLibTest.opp(g), c));
        LookaheadSearch.resample(g, me(g), c, cm, new Random(4), null);
        Assert.assertEquals(cm.getCardsIn(ZoneType.Library).size(), me(g).getCardsIn(ZoneType.Library).size());
    }

    /** S2: a listener added while the list is being fired (another thread, in production) causes no exception. */
    @Test
    public void theListenerListToleratesAChangeWhileFiring() {
        final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        final int[] calls = {0};
        g.addLibraryInfoListener((kind, observers, cards, zone) -> {
            calls[0]++;
            g.addLibraryInfoListener((k2, o2, c2, z2) -> { });
        });
        g.fireLibraryInfo(LibraryInfoListener.Kind.PEEK, List.of(me(g)), List.of(me(g).getCardsIn(ZoneType.Library).get(0)),
                ZoneType.Library);
        Assert.assertEquals(calls[0], 1);
        Assert.assertEquals(g.getLibraryInfoListeners().size(), 2);
    }
    // ------------------------------------------------------------------ #173: infoState on => attached at setup

    /**
     * Every config that turns an InfoStates consumer on (the look-ahead's key, the solver's key through plans.solverCfg,
     * plans.defendCfg or forcedWin) attaches the game's InfoStates at setup; with none on, nothing is attached (so a
     * key can never silently behave as off, and an off spec stays byte-identical).
     */
    @Test
    public void infoStateOnMeansAttachedAtSetup() {
        final com.google.gson.JsonObject on = new com.google.gson.JsonObject();
        on.addProperty("infoState", "true");
        for (int k = 0; k < 5; k++) {
            final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Island;Swamp", 20);
            final forge.bench.rl.RlSearch.Config c = new forge.bench.rl.RlSearch.Config();
            switch (k) {
                case 0: break; // nothing on
                case 1: c.infoState = true; break;
                case 2: c.plans = new com.google.gson.JsonObject(); c.plans.add("solverCfg", on.deepCopy()); break;
                case 3: c.plans = new com.google.gson.JsonObject(); c.plans.add("defendCfg", on.deepCopy()); break;
                default: c.forcedWin = on.deepCopy(); break;
            }
            final InfoStates s = forge.bench.rl.RlSearch.attachInfoStates(c, g);
            Assert.assertEquals(s != null, k != 0, "case " + k);
            Assert.assertEquals(InfoStates.find(g) != null, k != 0, "case " + k + ": attached iff a consumer is on");
            Assert.assertSame(InfoStates.forConsumer(g), s, "the consumers find it");
        }
    }

    /** Choose-a-card's live memory (and R3's) attaches at bind when its key is on: it reads the game's shared instance. */
    @Test
    public void chooseACardMemoryAttachesAtBind() {
        final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Island;Swamp", 20);
        final ChooseACard.Config cfg = new ChooseACard.Config();
        cfg.infoState = true;
        final ChooseACard c = new ChooseACard(cfg, StaticValuer.INSTANCE, null);
        c.bind(g, me(g));
        Assert.assertNotNull(InfoStates.find(g), "attached at bind");
        Assert.assertSame(c.memory().infoStates(), InfoStates.find(g));
    }
}
