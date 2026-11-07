package forge.ai.simulation;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.StaticEffect;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/** Copy/redraw correctness only. These synthetic fixtures never become corpus rows. */
public class HonestRedrawStaticsTest extends SimulationTest {
    private Path pool;

    private String pool() throws Exception {
        if (pool == null) {
            pool = Files.createTempFile("redraw-statics-pool-", ".json");
            pool.toFile().deleteOnExit();
            Files.writeString(pool, "{\"schema\":\"mtgx-belief-cube/1\",\"cards\":["
                    + "{\"name\":\"Counterspell\",\"tags\":[]},"
                    + "{\"name\":\"Shock\",\"tags\":[]},"
                    + "{\"name\":\"Grizzly Bears\",\"tags\":[]}]}");
        }
        return pool.toString();
    }

    @DataProvider(name = "paths")
    public Object[][] paths() {
        return new Object[][] {{"uniform"}, {"prior"}, {"label"}};
    }

    @DataProvider(name = "hiddenStatics")
    public Object[][] hiddenStatics() {
        List<Object[]> rows = new ArrayList<>();
        for (String name : new String[] {"Pack Rat", "Adeline, Resplendent Cathar", "Necromancy", "Grist, the Hunger Tide"}) {
            for (ZoneType zone : new ZoneType[] {ZoneType.Hand, ZoneType.Library}) {
                for (Object[] path : paths()) rows.add(new Object[] {name, zone, path[0]});
            }
        }
        return rows.toArray(new Object[0][]);
    }

    private Game board(String hidden, ZoneType zone) {
        Game game = initAndCreateGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 12; i++) {
            addCardToZone("Island", me, ZoneType.Library);
            addCardToZone("Raging Goblin", opp, ZoneType.Library);
        }
        addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        addCardToZone("Craw Wurm", opp, ZoneType.Hand);
        addCardToZone(hidden, opp, zone);
        addCard("Glorious Anthem", me);
        addCard("Grizzly Bears", me);
        addCard("Hill Giant", opp);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private Game world(Game live, String path) throws Exception {
        Player me = live.getPlayers().get(0), opp = live.getPlayers().get(1);
        if (path.equals("label")) {
            HonestLabel.Spec spec = new HonestLabel.Spec();
            spec.redraw = "belief";
            spec.solverCfg.addProperty("hideOwnLibrary", "true");
            spec.lossCfg.addProperty("hideOwnLibrary", "true");
            spec.beliefCube = pool();
            spec.beliefCubeSha256 = null; // test-owned pool, no corpus configuration
            spec.beliefBasics = 2;
            spec.beliefSingleton = false;
            HonestLabel.Seat seat = new HonestLabel.Seat(spec);
            Method method = HonestLabel.Seat.class.getDeclaredMethod("world", Game.class, Player.class,
                    Player.class, Random.class);
            method.setAccessible(true);
            return (Game) ((Object[]) method.invoke(seat, live, me, opp, new Random(1023)))[0];
        }
        LookaheadSearch.Config cfg = new LookaheadSearch.Config();
        cfg.belief = "uniform";
        cfg.beliefCube = pool();
        cfg.beliefBasics = 2;
        cfg.beliefSingleton = false;
        cfg.threads = 1;
        LookaheadSearch search = new LookaheadSearch(cfg);
        try {
            GameCopier copier = new GameCopier(live, true);
            copier.setCopyFidelity(1);
            Game copy = copier.makeCopy();
            ForcedWinSolver.Redraw redraw = path.equals("prior")
                    ? search.opponentRedrawPrior(Collections.emptyMap(), 1.0) : search.opponentRedraw();
            redraw.apply(live, me, copy, (Player) copier.find(me), new Random(1023));
            return copy;
        } finally {
            search.shutdown();
        }
    }

    private static void assertStaticReferencesCurrent(Game game) {
        Set<Card> cards = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Card card : game.getCardsInGame()) cards.add(card);
        for (StaticEffect effect : game.getStaticEffects().getEffects()) {
            Assert.assertTrue(cards.contains(effect.getSource()), "discarded source: " + effect.getSource());
            for (Card affected : effect.getAffectedCards()) {
                Assert.assertTrue(cards.contains(affected), "discarded affected card: " + affected);
            }
        }
    }

    private static List<String> signature(Game game) {
        List<String> result = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            result.add("life=" + p.getLife());
            for (ZoneType zone : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Battlefield}) {
                result.add(zone.toString());
                for (Card c : p.getCardsIn(zone)) result.add(c.getName() + ":" + c.getNetPower() + ":" + c.getNetToughness());
            }
        }
        return result;
    }

    @Test(dataProvider = "hiddenStatics")
    public void redrawDropsDiscardedStaticSourcesAndCanBeCopied(String hidden, ZoneType zone, String path) throws Exception {
        Game live = board(hidden, zone);
        String before = LookaheadSearch.fingerprint(live);
        IdScope.open();
        try {
            Game root = world(live, path);
            assertStaticReferencesCurrent(root);
            for (Card card : root.getPlayers().get(1).getCardsIn(zone)) Assert.assertNotEquals(card.getName(), hidden);
            GameCopier copier = new GameCopier(root, true);
            copier.setCopyFidelity(1);
            Game copy = copier.makeCopy();
            assertStaticReferencesCurrent(copy);
            Assert.assertEquals(signature(copy), signature(root));
            Assert.assertEquals(findCardWithName(copy, "Grizzly Bears").getNetPower(), 3);
        } finally {
            IdScope.close();
        }
        Assert.assertEquals(LookaheadSearch.fingerprint(live), before);
    }

    @Test(dataProvider = "paths")
    public void revealedCardsAndTheirStaticsSurvive(String path) throws Exception {
        Game live = board("Pack Rat", ZoneType.Hand);
        Player me = live.getPlayers().get(0), opp = live.getPlayers().get(1);
        Card seenHand = addCardToZone("Adeline, Resplendent Cathar", opp, ZoneType.Hand);
        seenHand.addMayLookAt(live.getNextTimestamp(), List.of(me));
        Card seenTop = addCardToZone("Grist, the Hunger Tide", opp, ZoneType.Library);
        seenTop.addMayLookAt(live.getNextTimestamp(), List.of(me));
        List<Card> library = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
        library.remove(seenTop);
        library.add(0, seenTop);
        opp.getZone(ZoneType.Library).setCards(library);
        live.getAction().checkStateEffects(true);
        String before = LookaheadSearch.fingerprint(live);
        IdScope.open();
        try {
            Game root = world(live, path);
            assertStaticReferencesCurrent(root);
            Player sampled = root.getPlayers().get(1);
            Assert.assertTrue(sampled.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getId() == seenHand.getId()));
            Assert.assertEquals(sampled.getCardsIn(ZoneType.Library).get(0).getId(), seenTop.getId());
            Set<Integer> staticSources = new java.util.HashSet<>();
            for (StaticEffect effect : root.getStaticEffects().getEffects()) staticSources.add(effect.getSource().getId());
            Assert.assertTrue(staticSources.contains(seenHand.getId()));
            Assert.assertTrue(staticSources.contains(seenTop.getId()));
            Game copy = new GameCopier(root, true).makeCopy();
            assertStaticReferencesCurrent(copy);
            Assert.assertEquals(signature(copy), signature(root));
        } finally {
            IdScope.close();
        }
        Assert.assertEquals(LookaheadSearch.fingerprint(live), before);
    }

    @Test(dataProvider = "paths")
    public void sameVisiblePositionAndRngProduceSameSampleRegardlessOfRealHiddenCard(String path) throws Exception {
        Game ordinary = board("Craw Wurm", ZoneType.Hand), characteristic = board("Pack Rat", ZoneType.Hand);
        IdScope.open();
        try {
            Game first = world(ordinary, path), second = world(characteristic, path);
            assertStaticReferencesCurrent(first);
            assertStaticReferencesCurrent(second);
            Assert.assertEquals(signature(second), signature(first));
        } finally {
            IdScope.close();
        }
    }
}
