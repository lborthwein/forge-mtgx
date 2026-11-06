package forge.ai.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * deck-belief-1105: the solver's deck-aware opponent worlds (solver key deckBelief). Card names appear only here, as
 * test fixtures; the model reads colours, types and costs.
 */
public class DeckBeliefTest extends SimulationTest {

    /** A small five-colour cube: blue answers, red burn, off-colour filler, a few lands. */
    private static final String[] CUBE = {"Counterspell", "Mana Leak", "Remove Soul", "Lightning Bolt", "Shock",
        "Hill Giant", "Giant Growth", "Llanowar Elves", "Wrath of God", "Serra Angel", "Dark Ritual", "Duress",
        "Craw Wurm", "Prodigal Pyromancer", "Tropical Island", "Volcanic Island", "Scrubland", "Divination",
        "Air Elemental", "Raging Goblin", "Savannah Lions", "Doom Blade", "Sengir Vampire", "Unsummon"};
    /** The opponent's real hidden cards: none is in the cube. */
    private static final String[] OPP_HAND = {"Fog", "Opt", "Brainstorm"};
    private static final String OPP_LIB = "Ponder";
    private static final List<String> BLUE_ANSWERS = Arrays.asList("Counterspell", "Mana Leak", "Remove Soul", "Unsummon");

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
        Path p = Files.createTempFile("deck-belief-cube", ".json");
        Files.writeString(p, o.toString());
        p.toFile().deleteOnExit();
        return p.toString();
    }

    /** Our main phase; the opponent shows two untapped Islands and a Forest, holds 3 hidden cards over 12 hidden library cards. */
    private Game board(boolean swapHandLib) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 12; i++) {
            addCardToZone(swapHandLib && i < 3 ? OPP_HAND[i] : OPP_LIB, b, ZoneType.Library);
            addCardToZone("Mountain", a, ZoneType.Library);
        }
        for (int i = 0; i < 3; i++) {
            addCard("Mountain", a);
        }
        addCard("Island", b);
        addCard("Island", b);
        addCard("Forest", b).setTapped(true);
        for (String n : OPP_HAND) {
            addCardToZone(swapHandLib ? OPP_LIB : n, b, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch.Config cfg(String cube) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 3;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1105L;
        c.belief = "uniform";
        c.beliefCube = cube;
        c.beliefBasics = 2;
        return c;
    }

    private static ForcedWinSolver.Config solverCfg(boolean deck) {
        ForcedWinSolver.Config c = new ForcedWinSolver.Config();
        c.deckBelief = deck;
        return c;
    }

    private static List<String> world(LookaheadSearch ls, ForcedWinSolver.Redraw rd, Game game, long seed, ZoneType z) {
        Player a = game.getPlayers().get(0);
        GameCopier c = new GameCopier(game, true);
        Game g = c.makeCopy();
        rd.apply(game, a, g, (Player) c.find(a), new Random(seed));
        List<String> out = new ArrayList<>();
        for (Card x : ((Player) c.find(game.getPlayers().get(1))).getCardsIn(z)) {
            out.add(x.getName());
        }
        return out;
    }

    private static PaperCard paper(String name) {
        return forge.StaticData.instance().getCommonCards().getCard(name);
    }

    @Test
    public void colourSetsContainTheShownColours() {
        final byte ug = (byte) (MagicColor.BLUE | MagicColor.GREEN);
        for (byte m : DeckBelief.candidateSets(ug)) {
            AssertJUnit.assertEquals(ug, (byte) (m & ug));
            AssertJUnit.assertTrue(Integer.bitCount(m) <= 3);
        }
        AssertJUnit.assertEquals(1 + 3, DeckBelief.candidateSets(ug).size()); // UG and the three UGx
        AssertJUnit.assertEquals(25, DeckBelief.candidateSets((byte) 0).size());
        final byte four = (byte) (ug | MagicColor.RED | MagicColor.WHITE);
        AssertJUnit.assertEquals(Arrays.asList(four), DeckBelief.candidateSets(four));
    }

    @Test
    public void declinedPlaysAreLessLikelyInTheHand() {
        DeckBelief.Params p = new DeckBelief.Params();
        DeckBelief.Evidence e = new DeckBelief.Evidence();
        e.offTurn = true;
        e.openMana = 2;
        e.openColors = MagicColor.BLUE;
        e.noLandLastTurn = true;
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Counterspell"), e, p)); // instant
        AssertJUnit.assertEquals(p.decline, DeckBelief.handWeight(paper("Ponder"), e, p)); // castable sorcery, held
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Divination"), e, p)); // could not afford it (3 > 2 open)
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Hill Giant"), e, p)); // off-colour for the open mana
        AssertJUnit.assertEquals(p.decline, DeckBelief.handWeight(paper("Island"), e, p)); // no land drop last turn
        e.offTurn = false; // on its own turn nothing is declined yet
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Ponder"), e, p));
    }

    @Test
    public void worldsAreHonestColourConsistentAndRaiseTheAnswerMass() throws Exception {
        String cube = cubeFile();
        Game game = board(false);
        String before = LookaheadSearch.fingerprint(game);
        forge.util.IdScope.open();
        LookaheadSearch ls = new LookaheadSearch(cfg(cube));
        ForcedWinSolver.Redraw deck = ls.solverRedraw(solverCfg(true));
        ForcedWinSolver.Redraw uni = ls.solverRedraw(solverCfg(false));
        AssertJUnit.assertNotNull(deck);
        int deckAns = 0, uniAns = 0;
        final byte shown = (byte) (MagicColor.BLUE | MagicColor.GREEN);
        for (long s = 0; s < 200; s++) {
            List<String> hand = world(ls, deck, game, s, ZoneType.Hand);
            List<String> lib = world(ls, deck, game, s, ZoneType.Library);
            AssertJUnit.assertEquals(3, hand.size());
            AssertJUnit.assertEquals(12, lib.size());
            byte union = shown;
            for (String n : hand) {
                AssertJUnit.assertFalse("real hidden card: " + n, Arrays.asList(OPP_HAND).contains(n) || OPP_LIB.equals(n));
                union |= paper(n).getRules().getColorIdentity().getColor();
            }
            AssertJUnit.assertTrue("a world's hand fits one deck of at most 3 colours: " + hand, Integer.bitCount(union & 0x1f) <= 3);
            for (String n : hand) {
                deckAns += BLUE_ANSWERS.contains(n) ? 1 : 0;
            }
            for (String n : world(ls, uni, game, s, ZoneType.Hand)) {
                uniAns += BLUE_ANSWERS.contains(n) ? 1 : 0;
            }
        }
        System.out.println("deck-belief-1105 answers in 200 hands: deck " + deckAns + " uniform " + uniAns);
        AssertJUnit.assertTrue("deck " + deckAns + " vs uniform " + uniAns, deckAns > 1.5 * uniAns);
        ls.shutdown();
        forge.util.IdScope.close();
        AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
    }

    @Test
    public void worldsDoNotDependOnTheRealHiddenCards() throws Exception {
        String cube = cubeFile();
        forge.util.IdScope.open();
        Game g1 = board(false);
        Game g2 = board(true); // the same public state, the hidden hand and library contents swapped
        LookaheadSearch l1 = new LookaheadSearch(cfg(cube));
        LookaheadSearch l2 = new LookaheadSearch(cfg(cube));
        for (long s = 0; s < 20; s++) {
            AssertJUnit.assertEquals(world(l1, l1.solverRedraw(solverCfg(true)), g1, s, ZoneType.Hand),
                    world(l2, l2.solverRedraw(solverCfg(true)), g2, s, ZoneType.Hand));
            AssertJUnit.assertEquals(world(l1, l1.solverRedraw(solverCfg(true)), g1, s, ZoneType.Library),
                    world(l2, l2.solverRedraw(solverCfg(true)), g2, s, ZoneType.Library));
        }
        l1.shutdown();
        l2.shutdown();
        forge.util.IdScope.close();
    }

    @Test
    public void offIsTheUniformRedrawAndTheBaseWorldCount() throws Exception {
        ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        AssertJUnit.assertFalse(off.deckBelief);
        AssertJUnit.assertEquals(0, off.deckWorlds);
        ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(k -> "deckBelief".equals(k) ? "true" : "deckWorlds".equals(k) ? "16" : null);
        AssertJUnit.assertTrue(on.deckBelief);
        AssertJUnit.assertEquals(16, on.deckWorlds);
        String cube = cubeFile();
        Game game = board(false);
        forge.util.IdScope.open();
        LookaheadSearch ls = new LookaheadSearch(cfg(cube));
        for (long s = 0; s < 10; s++) {
            AssertJUnit.assertEquals(world(ls, ls.opponentRedraw(), game, s, ZoneType.Hand),
                    world(ls, ls.solverRedraw(off), game, s, ZoneType.Hand));
        }
        Player a = game.getPlayers().get(0);
        AssertJUnit.assertEquals(off.worlds, new ForcedWinSolver(a, off).beliefWorlds(game));
        AssertJUnit.assertEquals(16, new ForcedWinSolver(a, on).beliefWorlds(game)); // hidden hand + open Islands
        ls.shutdown();
        forge.util.IdScope.close();
    }
    @Test
    public void searchWorldsUseTheDeckPosteriorOnlyWithBeliefDeck() throws Exception {
        String cube = cubeFile();
        Game game = board(false);
        Player a = game.getPlayers().get(0);
        forge.util.IdScope.open();
        LookaheadSearch.Config on = cfg(cube);
        on.beliefDeck = true;
        LookaheadSearch lsOn = new LookaheadSearch(on);
        LookaheadSearch lsOff = new LookaheadSearch(cfg(cube));
        LookaheadSearch lsRef = new LookaheadSearch(cfg(cube));
        final byte shown = (byte) (MagicColor.BLUE | MagicColor.GREEN);
        int onAns = 0, offAns = 0;
        for (long s = 0; s < 100; s++) {
            GameCopier c1 = new GameCopier(game, true);
            Game g1 = c1.makeCopy();
            lsOn.redraw(game, a, g1, (Player) c1.find(a), new Random(s));
            byte union = shown;
            for (Card x : ((Player) c1.find(game.getPlayers().get(1))).getCardsIn(ZoneType.Hand)) {
                union |= paper(x.getName()).getRules().getColorIdentity().getColor();
                onAns += BLUE_ANSWERS.contains(x.getName()) ? 1 : 0;
            }
            AssertJUnit.assertTrue(Integer.bitCount(union & 0x1f) <= 3);
            // off: exactly the uniform search redraw
            List<String> h0 = new ArrayList<>(), h1 = new ArrayList<>();
            GameCopier c2 = new GameCopier(game, true);
            Game g2 = c2.makeCopy();
            lsOff.redraw(game, a, g2, (Player) c2.find(a), new Random(s));
            for (Card x : ((Player) c2.find(game.getPlayers().get(1))).getCardsIn(ZoneType.Hand)) {
                h0.add(x.getName());
                offAns += BLUE_ANSWERS.contains(x.getName()) ? 1 : 0;
            }
            GameCopier c3 = new GameCopier(game, true);
            Game g3 = c3.makeCopy();
            lsRef.redraw(game, a, g3, (Player) c3.find(a), new Random(s));
            for (Card x : ((Player) c3.find(game.getPlayers().get(1))).getCardsIn(ZoneType.Hand)) {
                h1.add(x.getName());
            }
            AssertJUnit.assertEquals(h1, h0);
        }
        System.out.println("deck-belief-1105 search worlds, answers in 100 hands: beliefDeck " + onAns + " uniform " + offAns);
        AssertJUnit.assertTrue(onAns > offAns);
        ForcedWinSolver.Config u = ForcedWinSolver.Config.fromProps(k -> "unprovenMs".equals(k) ? "5000" : null);
        AssertJUnit.assertEquals(5000, u.unprovenMs);
        AssertJUnit.assertEquals(0, ForcedWinSolver.Config.fromProps(k -> null).unprovenMs);
        AssertJUnit.assertEquals(0, ForcedWinSolver.Config.fromProps(k -> null).capMs);
        AssertJUnit.assertEquals(4000, ForcedWinSolver.Config.fromProps(k -> "capMs".equals(k) ? "4000" : null).capMs);
        lsOn.shutdown();
        lsOff.shutdown();
        lsRef.shutdown();
        forge.util.IdScope.close();
    }
    @Test
    public void raceWorldsIsOffByDefaultAndParsed() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(0, c.raceWorlds);
        AssertJUnit.assertFalse(c.toJson().has("raceWorlds"));
        c.raceWorlds = 8;
        AssertJUnit.assertEquals(8, c.toJson().get("raceWorlds").getAsInt());
        JsonObject o = new JsonObject();
        o.addProperty("server", "localhost:1");
        AssertJUnit.assertEquals(0, forge.bench.rl.RlSearch.Config.parse(o).raceWorlds);
        AssertJUnit.assertFalse(forge.bench.rl.RlSearch.Config.parse(o).beliefDeck);
        o.addProperty("raceWorlds", 8);
        o.addProperty("beliefDeck", true);
        AssertJUnit.assertEquals(8, forge.bench.rl.RlSearch.Config.parse(o).raceWorlds);
        AssertJUnit.assertTrue(forge.bench.rl.RlSearch.Config.parse(o).beliefDeck);
        // the race's test is the departZ test over the pooled paired worlds: a lead too noisy at 8 worlds passes at 24
        double[][] v8 = new double[2][8], v24 = new double[2][24];
        for (int w = 0; w < 24; w++) {
            double d = (w % 2 == 0 ? 0.25 : -0.1);
            if (w < 8) {
                v8[0][w] = 0.5;
                v8[1][w] = 0.5 + d;
            }
            v24[0][w] = 0.5;
            v24[1][w] = 0.5 + d;
        }
        AssertJUnit.assertFalse(LookaheadSearch.confident(v8, 1, 8, 1.645));
        AssertJUnit.assertTrue(LookaheadSearch.confident(v24, 1, 24, 1.645));
    }
    @Test
    public void archetypeWeightsFollowTheTableAndOffIsUniform() throws Exception {
        Path f = Files.createTempFile("deck-cooc", ".json");
        f.toFile().deleteOnExit();
        Files.writeString(f, "{\"schema\":\"mtgx-deck-cooc/1\",\"table\":{\"Brainstorm\":{\"Counterspell\":1.5,\"Hill Giant\":-1.0}}}");
        DeckBelief.Params p = new DeckBelief.Params();
        List<String> shown = Arrays.asList("Brainstorm", "Unknown Card");
        AssertJUnit.assertEquals(1.0, DeckBelief.coocWeight("Counterspell", shown, p)); // no table: off
        p.cooc = DeckBelief.loadCooc(f.toString(), null);
        AssertJUnit.assertEquals(Math.exp(1.5), DeckBelief.coocWeight("Counterspell", shown, p), 1e-9);
        AssertJUnit.assertEquals(Math.exp(-1.0), DeckBelief.coocWeight("Hill Giant", shown, p), 1e-9);
        AssertJUnit.assertEquals(1.0, DeckBelief.coocWeight("Shock", shown, p), 1e-9);
        try {
            DeckBelief.loadCooc(f.toString(), "00");
            AssertJUnit.fail("a wrong sha256 pin must be refused");
        } catch (IllegalStateException expected) {
        }
        int first = 0;
        for (long s = 0; s < 400; s++) {
            List<String> names = new ArrayList<>(Arrays.asList("Shock", "Counterspell", "Hill Giant", "Serra Angel"));
            DeckBelief.weightedOrder(names, shown, p, new Random(s));
            first += "Counterspell".equals(names.get(0)) ? 1 : 0;
        }
        // weights e^1.5 : 1 : e^-1 : 1, so Counterspell leads about 4.48 / 6.85 of the time
        AssertJUnit.assertTrue("first " + first, first > 220 && first < 300);
        JsonObject o = new JsonObject();
        o.addProperty("server", "localhost:1");
        o.addProperty("beliefPoolMode", "independent");
        AssertJUnit.assertEquals("independent", forge.bench.rl.RlSearch.Config.parse(o).beliefPoolMode);
        JsonObject none = new JsonObject();
        none.addProperty("server", "localhost:1");
        AssertJUnit.assertEquals("draft-pod", forge.bench.rl.RlSearch.Config.parse(none).beliefPoolMode); // today's rule
        AssertJUnit.assertEquals("draft-pod", new LookaheadSearch.Config().beliefPoolMode);
        o.addProperty("beliefPoolMode", "pod");
        try {
            forge.bench.rl.RlSearch.Config.parse(o);
            AssertJUnit.fail("an unknown pool mode must be refused");
        } catch (IllegalArgumentException expected) {
        }
    }
    @Test
    public void theSolverAndTheSearchDealTheSameHandFromTheSameSeed() throws Exception {
        // deck-belief-1105 sharedWorlds: one world set per decision needs one redraw for both consumers
        String cube = cubeFile();
        Game game = board(false);
        Player a = game.getPlayers().get(0);
        forge.util.IdScope.open();
        LookaheadSearch.Config c = cfg(cube);
        c.beliefDeck = true;
        LookaheadSearch ls = new LookaheadSearch(c);
        ForcedWinSolver.Redraw solver = ls.solverRedraw(solverCfg(true));
        for (long s = 0; s < 20; s++) {
            GameCopier c1 = new GameCopier(game, true);
            Game g1 = c1.makeCopy();
            ls.redraw(game, a, g1, (Player) c1.find(a), new Random(s));
            List<String> searchHand = new ArrayList<>();
            for (Card x : ((Player) c1.find(game.getPlayers().get(1))).getCardsIn(ZoneType.Hand)) {
                searchHand.add(x.getName());
            }
            AssertJUnit.assertEquals(world(ls, solver, game, s, ZoneType.Hand), searchHand);
        }
        ls.setSharedWorlds(new long[] {7L, 9L});
        ls.setSharedWorlds(null); // set and cleared around one decision; null and empty are off
        ls.setSharedWorlds(new long[0]);
        ls.shutdown();
        forge.util.IdScope.close();
    }
}
