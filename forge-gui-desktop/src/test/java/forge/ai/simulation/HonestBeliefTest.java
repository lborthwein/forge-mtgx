package forge.ai.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AiFixes;
import forge.ai.FairNaming;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * honest-belief-1020 (owner, 2026-10-20: "fix the decklist leak"): with a belief on, no copy the search or the solver
 * plays holds the opponent's real hidden cards: worlds, the mismatch fallback, the solver's verification worlds and
 * engine root all draw from the observation-only pool; an event pool replaces the cube; the live seat and every
 * interactive look-ahead seat are honest by default; Forge AI's colour/type choices under fairNaming=on read only seen
 * opponent cards. Off stays the base search (LookaheadBeliefTest covers that).
 */
public class HonestBeliefTest extends SimulationTest {

    /** The cube: none of the opponent's real hidden cards (OPP_HAND, OPP_LIB) is in it. */
    private static final String[] CUBE = {"Counterspell", "Lightning Bolt", "Hill Giant", "Shock", "Giant Growth",
        "Grizzly Bears", "Wrath of God", "Mana Leak", "Prodigal Pyromancer", "Serra Angel"};
    private static final String[] OPP_HAND = {"Llanowar Elves", "Dark Ritual", "Craw Wurm"};
    private static final String OPP_LIB = "Raging Goblin";

    private static String cubeFile() throws IOException {
        JsonArray cards = new JsonArray();
        for (String n : CUBE) {
            JsonObject c = new JsonObject();
            c.addProperty("name", n);
            c.add("tags", new JsonArray());
            cards.add(c);
        }
        JsonObject o = new JsonObject();
        o.addProperty("schema", "mtgx-belief-cube/1");
        o.add("cards", cards);
        Path p = Files.createTempFile("honest-cube", ".json");
        Files.writeString(p, o.toString());
        p.toFile().deleteOnExit();
        return p.toString();
    }

    /** Seat a's main phase; seat b: 3 hidden hand cards + 1 revealed-free hand card, 12 hidden library cards, all outside the cube. */
    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 12; i++) {
            addCardToZone(OPP_LIB, b, ZoneType.Library);
            addCardToZone("Mountain", a, ZoneType.Library);
        }
        for (int i = 0; i < 3; i++) {
            addCard("Mountain", a);
            addCard("Forest", b);
        }
        addCard("Hill Giant", b).setSickness(false);
        addCardToZone("Shock", b, ZoneType.Graveyard);
        for (String n : OPP_HAND) {
            addCardToZone(n, b, ZoneType.Hand);
        }
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch.Config cfg(String belief, String cube) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 3;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1020L;
        c.belief = belief;
        c.beliefCube = cube;
        c.beliefBasics = 2;
        return c;
    }

    private static Set<String> names(List<Card> cs) {
        Set<String> s = new HashSet<>();
        for (Card c : cs) {
            s.add(c.getName());
        }
        return s;
    }

    private static void assertHonest(Player copyOpp, int hand, int lib, Set<String> pool) {
        AssertJUnit.assertEquals(hand, copyOpp.getCardsIn(ZoneType.Hand).size());
        AssertJUnit.assertEquals(lib, copyOpp.getCardsIn(ZoneType.Library).size());
        List<Card> hidden = new ArrayList<>(copyOpp.getCardsIn(ZoneType.Hand));
        hidden.addAll(copyOpp.getCardsIn(ZoneType.Library));
        for (Card c : hidden) {
            AssertJUnit.assertFalse("real hidden card in a world: " + c.getName(),
                    Arrays.asList(OPP_HAND).contains(c.getName()) || OPP_LIB.equals(c.getName()));
            AssertJUnit.assertTrue("not a pool card: " + c.getName(), pool.contains(c.getName()) || BeliefSampler.isBasic(c.getName()));
        }
    }

    private static Set<String> poolNames() {
        Set<String> s = new HashSet<>(Arrays.asList(CUBE));
        s.remove("Hill Giant"); // seen on the battlefield
        s.remove("Shock"); // seen in the graveyard
        return s;
    }

    @Test
    public void beliefWorldsAndTheirFallbacksNeverHoldTheRealCards() throws Exception {
        String cube = cubeFile();
        Game game = board();
        Player a = game.getPlayers().get(0);
        String before = LookaheadSearch.fingerprint(game);
        forge.util.IdScope.open(); // copies draw their object ids from a scope, as the search's do
        // Belief off: the base re-draw keeps the real remainder (the leak this lane closes), as a control.
        LookaheadSearch off = new LookaheadSearch(cfg("off", null));
        GameCopier c0 = new GameCopier(game, true);
        Game g0 = c0.makeCopy();
        off.redraw(game, a, g0, (Player) c0.find(a), new Random(1));
        Player o0 = (Player) c0.find(game.getPlayers().get(1));
        AssertJUnit.assertTrue(names(new ArrayList<>(o0.getCardsIn(ZoneType.Hand))).stream().allMatch(
                n -> Arrays.asList(OPP_HAND).contains(n) || OPP_LIB.equals(n)));
        AssertJUnit.assertNull(off.opponentRedraw());
        // Belief on, no decision belief (the null-belief path): honest.
        LookaheadSearch on = new LookaheadSearch(cfg("uniform", cube));
        for (int seed = 0; seed < 6; seed++) {
            GameCopier c = new GameCopier(game, true);
            Game g = c.makeCopy();
            on.redraw(game, a, g, (Player) c.find(a), new Random(seed));
            assertHonest((Player) c.find(game.getPlayers().get(1)), 3, 12, poolNames());
        }
        // A decision belief that does not fit the copy (the old fallback to the real remainder): honest, counted.
        BeliefSampler.Dist wrong = new BeliefSampler.Dist(Arrays.asList("Counterspell", "Island"), new double[] {1, 1}, 4, 1, 3);
        wrong.oppId = game.getPlayers().get(1).getId();
        GameCopier c = new GameCopier(game, true);
        Game g = c.makeCopy();
        on.resampleBelief(game, a, g, (Player) c.find(a), new Random(9), wrong);
        assertHonest((Player) c.find(game.getPlayers().get(1)), 3, 12, poolNames());
        AssertJUnit.assertEquals(1, on.getStats().beliefMismatch);
        // The solver's re-draw (verification worlds, engine root), then the solver's own hide-or-sample: still honest.
        ForcedWinSolver.Redraw rd = on.opponentRedraw();
        AssertJUnit.assertNotNull(rd);
        for (boolean hide : new boolean[] {false, true}) {
            GameCopier cs = new GameCopier(game, true);
            Game gs = cs.makeCopy();
            Player csa = (Player) cs.find(a);
            Player cso = (Player) cs.find(game.getPlayers().get(1));
            List<Card> myLib = new ArrayList<>(csa.getCardsIn(ZoneType.Library));
            rd.apply(game, a, gs, csa, new Random(3));
            AssertJUnit.assertEquals("the solver keeps its own library rule", myLib, new ArrayList<>(csa.getCardsIn(ZoneType.Library)));
            ForcedWinSolver.hideOrSample(game, a, game.getPlayers().get(1), cso, hide, new Random(4));
            List<Card> hidden = new ArrayList<>(cso.getCardsIn(ZoneType.Hand));
            hidden.addAll(cso.getCardsIn(ZoneType.Library));
            hidden.addAll(cso.getCardsIn(ZoneType.Exile));
            for (Card x : hidden) {
                AssertJUnit.assertFalse(x.getName(), Arrays.asList(OPP_HAND).contains(x.getName()) || OPP_LIB.equals(x.getName()));
            }
            AssertJUnit.assertEquals(12, cso.getCardsIn(ZoneType.Library).size());
        }
        on.shutdown();
        off.shutdown();
        forge.util.IdScope.close();
        AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
    }

    @Test
    public void theEventPoolReplacesTheCube() throws Exception {
        Game game = board();
        Player a = game.getPlayers().get(0);
        LookaheadSearch.Config c = cfg("uniform", null);
        c.beliefPool = Arrays.asList("Shivan Dragon", "Shock", "Island");
        c.beliefSingleton = false; // e.g. random constructed decks: seen cards stay possible
        LookaheadSearch on = new LookaheadSearch(c);
        GameCopier cp = new GameCopier(game, true);
        Game g = cp.makeCopy();
        on.redraw(game, a, g, (Player) cp.find(a), new Random(2));
        Player o = (Player) cp.find(game.getPlayers().get(1));
        Set<String> got = new HashSet<>();
        for (Card x : o.getCardsIn(ZoneType.Hand)) {
            got.add(x.getName());
        }
        for (Card x : o.getCardsIn(ZoneType.Library)) {
            got.add(x.getName());
        }
        got.removeIf(BeliefSampler::isBasic);
        AssertJUnit.assertTrue(got.toString(), new HashSet<>(Arrays.asList("Shivan Dragon", "Shock")).containsAll(got));
        AssertJUnit.assertTrue(got.toString(), got.contains("Shock")); // seen in their graveyard, but not a singleton pool
        AssertJUnit.assertEquals(3, c.toJson().get("beliefPoolSize").getAsInt());
        on.shutdown();
    }

    @Test
    public void theLivePoolShipsInTheJarAndResolves() {
        initAndCreateGame();
        BeliefSampler.Cube cube = BeliefSampler.loadCube(BeliefSampler.LIVE_CUBE, BeliefSampler.LIVE_CUBE_SHA256, 8);
        AssertJUnit.assertEquals(BeliefSampler.LIVE_CUBE_SHA256, cube.sha256);
        AssertJUnit.assertTrue(cube.names.size() + " names", cube.names.size() >= 500);
        AssertJUnit.assertTrue(cube.unresolved.toString(), cube.unresolved.size() <= 5);
    }

    @Test
    public void fairChoicesReadOnlySeenOpponentCards() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        LobbyPlayerAi lobby = (LobbyPlayerAi) a.getLobbyPlayer();
        CardCollection all = new CardCollection(b.getAllCards());
        lobby.setFairNaming(AiFixes.Mode.OFF);
        AssertJUnit.assertEquals(all.size(), FairNaming.fair(a, all).size());
        lobby.setFairNaming(AiFixes.Mode.ON);
        Set<String> fair = names(FairNaming.fair(a, all));
        AssertJUnit.assertTrue(fair.containsAll(Arrays.asList("Hill Giant", "Shock", "Forest")));
        for (String n : OPP_HAND) {
            AssertJUnit.assertFalse(n, fair.contains(n));
        }
        AssertJUnit.assertFalse(fair.contains(OPP_LIB));
        AssertJUnit.assertFalse(FairNaming.mayRead(a, b.getCardsIn(ZoneType.Library).get(0)));
        AssertJUnit.assertTrue(FairNaming.mayRead(a, a.getCardsIn(ZoneType.Library).get(0)));
        lobby.setFairNaming(AiFixes.Mode.OFF);
    }
}
