package forge.ai.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * deck-guess-1021: the defender's deck prior with the cards the opponent showed in EARLIER games of the match
 * ({@link LookaheadSearch#opponentRedrawPrior} with a seen gain): off (gain 0 or no list) = the strong-opp prior, draw
 * for draw; on, a seen card not seen in this game is boosted and kept under fillBasics; a card seen in this game stays
 * out of a singleton pool; the EVAL hook beats the config's list.
 */
public class BeliefSeenTest extends SimulationTest {

    private static final String[] CUBE = {"Counterspell", "Lightning Bolt", "Hill Giant", "Shock", "Giant Growth",
        "Grizzly Bears", "Wrath of God", "Mana Leak", "Prodigal Pyromancer", "Serra Angel"};

    @AfterMethod
    public void clearHook() {
        LookaheadSearch.BELIEF_SEEN_HOOK = null;
    }

    private static String cubeFile() throws IOException {
        final JsonArray cards = new JsonArray();
        for (String n : CUBE) {
            final JsonObject c = new JsonObject();
            c.addProperty("name", n);
            c.add("tags", new JsonArray());
            cards.add(c);
        }
        final JsonObject o = new JsonObject();
        o.addProperty("schema", "mtgx-belief-cube/1");
        o.add("cards", cards);
        final Path p = Files.createTempFile("seen-cube", ".json");
        Files.writeString(p, o.toString());
        p.toFile().deleteOnExit();
        return p.toString();
    }

    /** Seat b: Shock in the graveyard (seen in this game), 3 hidden hand cards, 12 hidden library cards. */
    private Game board() {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0);
        final Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 12; i++) {
            addCardToZone("Raging Goblin", b, ZoneType.Library);
            addCardToZone("Mountain", a, ZoneType.Library);
        }
        addCardToZone("Shock", b, ZoneType.Graveyard);
        for (String n : new String[] {"Llanowar Elves", "Dark Ritual", "Craw Wurm"}) {
            addCardToZone(n, b, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch search(String cube, List<String> seen) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.threads = 1;
        c.seed = 1021L;
        c.belief = "uniform";
        c.beliefCube = cube;
        c.beliefBasics = 2;
        c.beliefSeen = seen;
        return new LookaheadSearch(c);
    }

    /** The opponent's hidden names in one re-drawn copy (hand then library order). */
    private static List<String> draw(Game game, ForcedWinSolver.Redraw rd, long seed) {
        final Player a = game.getPlayers().get(0);
        final GameCopier cp = new GameCopier(game, true);
        final Game g = cp.makeCopy();
        rd.apply(game, a, g, (Player) cp.find(a), new Random(seed));
        final Player o = (Player) cp.find(game.getPlayers().get(1));
        final List<String> out = new ArrayList<>();
        for (Card x : o.getCardsIn(ZoneType.Hand)) {
            out.add("H:" + x.getName());
        }
        for (Card x : o.getCardsIn(ZoneType.Library)) {
            out.add("L:" + x.getName());
        }
        return out;
    }

    @Test
    public void offIsTheStrongOppPriorDrawForDraw() throws Exception {
        final String cube = cubeFile();
        final Game game = board();
        final Map<String, Map<String, Double>> syn = new HashMap<>();
        syn.put("Shock", Map.of("Lightning Bolt", 0.8));
        forge.util.IdScope.open();
        final LookaheadSearch base = search(cube, null);
        final LookaheadSearch withList = search(cube, Arrays.asList("Serra Angel", "Wrath of God"));
        for (long seed = 0; seed < 5; seed++) {
            final List<String> ref = draw(game, base.opponentRedrawPrior(syn, 20, true), seed);
            // gain 0: the list is never read
            AssertJUnit.assertEquals(ref, draw(game, withList.opponentRedrawPrior(syn, 20, true, 0), seed));
            // no list: gain > 0 changes nothing
            AssertJUnit.assertEquals(ref, draw(game, base.opponentRedrawPrior(syn, 20, true, 4), seed));
        }
        base.shutdown();
        withList.shutdown();
        forge.util.IdScope.close();
    }

    @Test
    public void seenCardsAreBoostedKeptAndNeverTheOnesSeenNow() throws Exception {
        final String cube = cubeFile();
        final Game game = board();
        forge.util.IdScope.open();
        // Shock was seen in game 1 AND is in their graveyard now: a singleton pool has it located, never drawn
        final LookaheadSearch on = search(cube, Arrays.asList("Serra Angel", "Wrath of God", "Shock"));
        final ForcedWinSolver.Redraw rd = on.opponentRedrawPrior(Collections.emptyMap(), 20, true, 4);
        int angel = 0;
        for (long seed = 0; seed < 20; seed++) {
            final List<String> got = draw(game, rd, seed);
            AssertJUnit.assertEquals(15, got.size());
            for (String s : got) {
                final String n = s.substring(2);
                // fillBasics: nothing unlinked survives; the seen cards are linked
                AssertJUnit.assertTrue(got.toString(), BeliefSampler.isBasic(n) || n.equals("Serra Angel") || n.equals("Wrath of God"));
                AssertJUnit.assertFalse("real hidden card " + n, n.equals("Raging Goblin") || n.equals("Craw Wurm"));
            }
            if (got.stream().anyMatch(s -> s.endsWith("Serra Angel"))) {
                angel++;
            }
        }
        AssertJUnit.assertEquals("a seen card (weight 4 x 21 vs basics) is in every world", 20, angel);
        on.shutdown();
        forge.util.IdScope.close();
    }

    @Test
    public void theEvalHookBeatsTheConfigList() throws Exception {
        final String cube = cubeFile();
        final Game game = board();
        forge.util.IdScope.open();
        final LookaheadSearch on = search(cube, Arrays.asList("Serra Angel"));
        LookaheadSearch.BELIEF_SEEN_HOOK = g -> g == game ? Arrays.asList("Mana Leak") : null;
        final ForcedWinSolver.Redraw rd = on.opponentRedrawPrior(Collections.emptyMap(), 20, true, 4);
        final List<String> got = draw(game, rd, 7);
        AssertJUnit.assertTrue(got.toString(), got.stream().anyMatch(s -> s.endsWith("Mana Leak")));
        AssertJUnit.assertFalse(got.toString(), got.stream().anyMatch(s -> s.endsWith("Serra Angel")));
        // a hook that knows nothing about this game falls back to the config's list
        LookaheadSearch.BELIEF_SEEN_HOOK = g -> null;
        final List<String> got2 = draw(game, on.opponentRedrawPrior(Collections.emptyMap(), 20, true, 4), 7);
        AssertJUnit.assertTrue(got2.toString(), got2.stream().anyMatch(s -> s.endsWith("Serra Angel")));
        on.shutdown();
        forge.util.IdScope.close();
    }

    @Test
    public void theLiveKeysAcceptSixteenWorldsAndTheSeenGain() {
        final java.util.Map<String, String> k = forge.bench.rl.RlLiveSeat.Spec.LIVE_DEFEND_KEYS;
        for (String ok : new String[] {"1", "4", "8", "9", "16"}) {
            AssertJUnit.assertTrue(ok, ok.matches(k.get("defenseWorlds")));
        }
        for (String bad : new String[] {"0", "17", "32", "016"}) {
            AssertJUnit.assertFalse(bad, bad.matches(k.get("defenseWorlds")));
        }
        AssertJUnit.assertTrue("4".matches(k.get("seenGain")));
        AssertJUnit.assertTrue("2.5".matches(k.get("seenGain")));
        AssertJUnit.assertFalse("-1".matches(k.get("seenGain")));
        AssertJUnit.assertEquals(11, k.size());
    }
}
