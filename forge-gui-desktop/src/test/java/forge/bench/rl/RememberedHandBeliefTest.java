package forge.bench.rl;

import java.util.List;
import java.util.Random;
import com.google.gson.JsonObject;
import forge.ai.simulation.BeliefSampler;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.SimulationTest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** A prior reveal is observable even after the reveal ends; unknown cards must still be redrawn. */
public class RememberedHandBeliefTest extends SimulationTest {
    private Card generated(Player owner, String name, ZoneType zone) {
        PaperCard paper = new PaperCard(CardRules.fromScript(List.of(
                "Name:" + name, "ManaCost:1", "Types:Artifact", "Oracle:")), "", CardRarity.Common);
        Card card = Card.fromPaperCard(paper, owner);
        owner.getZone(zone).add(card);
        return card;
    }

    @Test public void rememberedHandRevealOffLosesAndOnPreservesIt() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) check(enabled);
    }

    private void check(boolean enabled) throws Exception {
        Game game = initAndCreateGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        Card remembered = generated(opp, "Generated remembered object", ZoneType.Hand);
        Card unknown = generated(opp, "Generated unseen object", ZoneType.Hand);
        generated(opp, "Generated library object", ZoneType.Library);
        RlKnowledge knowledge = new RlKnowledge(game); knowledge.v2 = true; knowledge.attach();
        knowledge.onReveal(game, me, List.of(remembered), ZoneType.Hand, opp);
        int seat = knowledge.seatOf(me);
        assertTrue(knowledge.knows(seat, remembered));
        assertFalse(knowledge.knows(seat, unknown));
        assertFalse(remembered.getView().canBeShownTo(me.getView()));

        JsonObject spec = new JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        spec.addProperty("belief", "uniform");
        spec.addProperty("beliefCube", BeliefSampler.LIVE_CUBE);
        spec.addProperty("beliefCubeSha256", BeliefSampler.LIVE_CUBE_SHA256);
        spec.addProperty("beliefRememberedHand", enabled);
        RlSearch search = new RlSearch(RlSearch.Config.parse(spec), 1, 2, null, knowledge, "jar", "probe", 2);
        try {
            assertEquals(search.remembersHandCard(game, me, remembered), enabled);
            assertFalse(search.remembersHandCard(game, me, unknown));
            assertFalse(search.remembersHandCard(game, opp, remembered), "the observer is part of the knowledge key");
            java.lang.reflect.Method counts = forge.ai.simulation.LookaheadSearch.class.getDeclaredMethod(
                    "hiddenCounts", Game.class, Player.class, Player.class);
            counts.setAccessible(true);
            assertEquals((int[]) counts.invoke(search.ls(), game, opp, me), new int[]{enabled ? 1 : 2, 1},
                    "belief distribution size and world draw must agree about remembered cards");
            GameCopier copier = new GameCopier(game, true);
            Game copy = copier.makeCopy();
            Player copyMe = (Player) copier.find(me), copyOpp = (Player) copier.find(opp);
            assertNotNull(copy.findById(remembered.getId()));
            search.ls().opponentRedraw().apply(game, me, copy, copyMe, new Random(51));
            assertEquals(copyOpp.getCardsIn(ZoneType.Hand).size(), 2);
            assertEquals(copy.findById(remembered.getId()) != null, enabled,
                    "off replaces the revealed card; on retains the observed identity");
            assertNull(copy.findById(unknown.getId()), "the genuinely hidden card must still be redrawn");
            assertFalse(search.remembersHandCard(copy, copyMe, (Card) copier.find(remembered)),
                    "same IDs in another game do not grant knowledge from the live tracker");
            if (enabled) {
                Card kept = copy.findById(remembered.getId());
                assertSame(kept.getGame(), copy);
                assertTrue(kept.isInZone(ZoneType.Hand));
                // The solver and engine run this filter after the belief draw.
                java.lang.reflect.Method filter = ForcedWinSolver.class.getDeclaredMethod("hideOrSample",
                        Game.class, Player.class, Player.class, Player.class, boolean.class, Random.class,
                        ForcedWinSolver.Redraw.class);
                filter.setAccessible(true);
                filter.invoke(null, game, me, opp, copyOpp, true, new Random(52), search.ls().opponentRedraw());
                assertEquals(copyOpp.getCardsIn(ZoneType.Hand).size(), 1);
                assertSame(copyOpp.getCardsIn(ZoneType.Hand).get(0), kept);
            }
            assertTrue(knowledge.knows(seat, remembered), "live knowledge is unchanged");
            assertSame(game.findById(remembered.getId()), remembered);
            // An unseen card leaving the hand invalidates earlier hand identity knowledge.
            game.getAction().moveToLibrary(unknown, 0, null);
            assertFalse(knowledge.knows(seat, remembered));
            assertFalse(search.remembersHandCard(game, me, remembered), "forgotten cards are no longer protected");
        } finally { search.close(); }
    }

    @Test public void keyDefaultsOffAndRoundTrips() {
        JsonObject spec = new JsonObject(); spec.addProperty("server", "127.0.0.1:1");
        RlSearch.Config cfg = RlSearch.Config.parse(spec);
        assertFalse(cfg.beliefRememberedHand); assertFalse(cfg.toJson().has("beliefRememberedHand"));
        spec.addProperty("beliefRememberedHand", true); cfg = RlSearch.Config.parse(spec);
        assertTrue(cfg.beliefRememberedHand); assertTrue(cfg.toJson().get("beliefRememberedHand").getAsBoolean());
        forge.ai.simulation.LookaheadSearch.Config look = new forge.ai.simulation.LookaheadSearch.Config();
        assertFalse(look.toJson().has("beliefRememberedHand"));
        look.beliefRememberedHand = true; assertTrue(look.toJson().get("beliefRememberedHand").getAsBoolean());
    }

    @Test public void rememberedSingletonCardIsRemovedFromThePool() throws Exception {
        Game game = initAndCreateGame(); Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        // Arbitrary member of the existing pool, selected without any card-specific behavior.
        PaperCard paper = BeliefSampler.loadCube(BeliefSampler.LIVE_CUBE, BeliefSampler.LIVE_CUBE_SHA256, 0)
                .paper.values().stream().filter(p -> !p.getRules().getType().isBasicLand())
                .sorted(java.util.Comparator.comparing(PaperCard::getName)).findFirst().orElseThrow();
        Card known = Card.fromPaperCard(paper, opp); opp.getZone(ZoneType.Hand).add(known);
        RlKnowledge knowledge = new RlKnowledge(game);
        knowledge.onReveal(game, me, List.of(known), ZoneType.Hand, opp);
        assertFalse(known.getView().canBeShownTo(me.getView()));
        for (boolean enabled : new boolean[]{false, true}) {
            JsonObject spec = new JsonObject(); spec.addProperty("server", "127.0.0.1:1");
            spec.addProperty("belief", "uniform"); spec.addProperty("beliefCube", BeliefSampler.LIVE_CUBE);
            spec.addProperty("beliefCubeSha256", BeliefSampler.LIVE_CUBE_SHA256);
            spec.addProperty("beliefSingleton", true); spec.addProperty("beliefRememberedHand", enabled);
            RlSearch search = new RlSearch(RlSearch.Config.parse(spec), 1, 2, null, knowledge, "jar", "pool", 2);
            try {
                java.lang.reflect.Method pool = forge.ai.simulation.LookaheadSearch.class.getDeclaredMethod(
                        "beliefPoolFor", Game.class, Player.class, Player.class);
                pool.setAccessible(true);
                assertEquals(((List<?>) pool.invoke(search.ls(), game, me, opp)).contains(paper.getName()), !enabled);
            } finally { search.close(); }
        }
    }

    /** Judge condition (1): only the seat's own recorded reveals are protected. (2): a hiding move forgets. */
    @Test public void judgeLeakAndForgettingControls() throws Exception {
        Game game = initAndCreateGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        Card ownerOnly = generated(opp, "Generated owner-only object", ZoneType.Hand);
        Card neverSeen = generated(opp, "Generated never-seen object", ZoneType.Hand);
        Card shown = generated(opp, "Generated shown object", ZoneType.Hand);
        generated(opp, "Generated library object", ZoneType.Library);
        RlKnowledge knowledge = new RlKnowledge(game); knowledge.v2 = true; knowledge.attach();
        knowledge.onReveal(game, opp, List.of(ownerOnly), ZoneType.Hand, opp);
        knowledge.onReveal(game, me, List.of(shown), ZoneType.Hand, opp);
        int seat = knowledge.seatOf(me);
        assertFalse(knowledge.knows(seat, ownerOnly), "a reveal to the opponent alone teaches this seat nothing");
        assertFalse(knowledge.knows(seat, neverSeen));
        assertTrue(knowledge.knows(seat, shown));
        JsonObject spec = new JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        spec.addProperty("belief", "uniform");
        spec.addProperty("beliefCube", BeliefSampler.LIVE_CUBE);
        spec.addProperty("beliefCubeSha256", BeliefSampler.LIVE_CUBE_SHA256);
        spec.addProperty("beliefRememberedHand", true);
        RlSearch search = new RlSearch(RlSearch.Config.parse(spec), 1, 2, null, knowledge, "jar", "probe", 2);
        try {
            assertFalse(search.remembersHandCard(game, me, ownerOnly));
            assertFalse(search.remembersHandCard(game, me, neverSeen));
            assertTrue(search.remembersHandCard(game, me, shown));
            GameCopier copier = new GameCopier(game, true);
            Game copy = copier.makeCopy();
            Player copyMe = (Player) copier.find(me), copyOpp = (Player) copier.find(opp);
            search.ls().opponentRedraw().apply(game, me, copy, copyMe, new Random(53));
            assertEquals(copyOpp.getCardsIn(ZoneType.Hand).size(), 3);
            assertNull(copy.findById(ownerOnly.getId()), "a card revealed only to its owner is still redrawn");
            assertNull(copy.findById(neverSeen.getId()), "a card the seat never saw is still redrawn");
            assertNotNull(copy.findById(shown.getId()), "the seat's own recorded reveal is kept");
            // The shown card goes back into the library unseen by this seat: it is hidden again and forgotten.
            game.getAction().moveToLibrary(shown, 0, null);
            assertFalse(knowledge.knows(seat, shown), "a move into a hidden zone forgets the card");
            assertFalse(search.remembersHandCard(game, me, shown));
        } finally { search.close(); }
    }
}
