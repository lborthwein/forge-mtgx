package forge.ai.simulation;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * belief-jar-1027 (research line, for BVW step 3): the two-stage basics ({@code basics=twoStage}, default off) in the
 * honest worlds. The new config fields are set by reflection, so this class also runs on a jar without them, where the
 * keyed tests fail (red on research/bvw-step1-1024, green on the fix).
 */
public class BeliefTwoStageTest extends SimulationTest {

    private static void set(Object o, String field, Object v) {
        try {
            o.getClass().getField(field).set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("this jar has no config field " + field + " (" + e + ")", e);
        }
    }

    /** Seat 0's main phase; seat 1 (16 cards): 3 hidden hand cards, 11 hidden library cards, a Forest, a Shock in the graveyard. */
    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 11; i++) {
            addCardToZone("Raging Goblin", b, ZoneType.Library);
            addCardToZone("Mountain", a, ZoneType.Library);
        }
        addCard("Mountain", a);
        addCard("Forest", b);
        addCardToZone("Shock", b, ZoneType.Graveyard);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Craw Wurm", b, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch.Config cfg() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 3;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1027L;
        c.belief = "uniform";
        c.beliefCube = BeliefSampler.LIVE_CUBE;
        c.beliefCubeSha256 = BeliefSampler.LIVE_CUBE_SHA256;
        c.beliefSingleton = true;
        return c;
    }

    private static List<String> world(Game game, ForcedWinSolver.Redraw rd, Random rng) {
        Player a = game.getPlayers().get(0);
        GameCopier c = new GameCopier(game, true);
        Game g = c.makeCopy();
        rd.apply(game, a, g, (Player) c.find(a), rng);
        Player o = (Player) c.find(game.getPlayers().get(1));
        AssertJUnit.assertEquals(3, o.getCardsIn(ZoneType.Hand).size());
        AssertJUnit.assertEquals(11, o.getCardsIn(ZoneType.Library).size());
        List<String> out = new java.util.ArrayList<>();
        for (Card x : o.getCardsIn(ZoneType.Hand)) {
            out.add(x.getName());
        }
        for (Card x : o.getCardsIn(ZoneType.Library)) {
            out.add(x.getName());
        }
        return out;
    }

    /**
     * The land share of drawn worlds (the jar's live pool): keys off, ~1 basic among the 14 hidden cards; twoStage,
     * exactly the deck's expected hidden basics (0.5 x 16 - 1 seen Forest = 7) through every entry point, and the line's
     * default share (the bot decks' 14.22 of 40) on average.
     */
    @Test
    public void twoStageWorldsHoldTheDecksShareOfBasics() throws Exception {
        Game game = board();
        forge.util.IdScope.open();
        LookaheadSearch off = new LookaheadSearch(cfg());
        double offBasics = 0;
        for (int i = 0; i < 40; i++) {
            final ForcedWinSolver.Redraw rd = off::redraw;
            offBasics += world(game, rd, new Random(i)).stream().filter(BeliefSampler::isBasic).count();
        }
        AssertJUnit.assertTrue("keys off: few basics (" + offBasics / 40 + ")", offBasics / 40 < 4.0);
        LookaheadSearch.Config c = cfg();
        set(c, "basics", "twoStage");
        set(c, "basicsShare", 0.5);
        LookaheadSearch on = new LookaheadSearch(c);
        final Map<String, ForcedWinSolver.Redraw> entries = new LinkedHashMap<>();
        entries.put("main worlds", on::redraw);
        entries.put("solver", on.opponentRedraw());
        entries.put("defender prior", on.opponentRedrawPrior(Collections.emptyMap(), 20.0, false, 0));
        for (Map.Entry<String, ForcedWinSolver.Redraw> e : entries.entrySet()) {
            for (int i = 0; i < 30; i++) {
                List<String> w = world(game, e.getValue(), new Random(100 + i));
                AssertJUnit.assertEquals(e.getKey() + ": 7 hidden basics per world", 7, w.stream().filter(BeliefSampler::isBasic).count());
                AssertJUnit.assertFalse(e.getKey() + ": never their real cards", w.contains("Craw Wurm") || w.contains("Raging Goblin"));
                AssertJUnit.assertEquals(e.getKey() + ": no repeated nonbasic", 7,
                        new HashSet<>(w.stream().filter(x -> !BeliefSampler.isBasic(x)).collect(Collectors.toList())).size());
            }
        }
        AssertJUnit.assertTrue(on.getStats().twoStageWorlds >= 90);
        AssertJUnit.assertTrue(on.getStats().beliefStaticRefreshes >= 90); // the research line's statics refresh still runs
        LookaheadSearch.Config c2 = cfg();
        set(c2, "basics", "twoStage");
        LookaheadSearch dflt = new LookaheadSearch(c2);
        double mean = 0;
        for (int i = 0; i < 200; i++) {
            final ForcedWinSolver.Redraw rd = dflt::redraw;
            mean += world(game, rd, new Random(500 + i)).stream().filter(BeliefSampler::isBasic).count();
        }
        AssertJUnit.assertEquals("default share x deck size - seen basics", LookaheadSearch.BASICS_SHARE_BOT * 16 - 1, mean / 200, 0.15);
        off.shutdown();
        on.shutdown();
        dflt.shutdown();
        forge.util.IdScope.close();
    }

    /** The keys in the search and label specs: absent = off (JSON and sha unchanged), on = named with the share in use. */
    @Test
    public void specKeys() throws Exception {
        final JsonObject o = new JsonObject();
        o.addProperty("belief", "uniform");
        o.addProperty("beliefCube", BeliefSampler.LIVE_CUBE);
        o.addProperty("beliefCubeSha256", BeliefSampler.LIVE_CUBE_SHA256);
        final String offJson = RlSearch.Config.parse(o.deepCopy()).toJson().toString();
        AssertJUnit.assertFalse(offJson, offJson.contains("basics\""));
        o.addProperty("basics", "twoStage");
        final String onJson = RlSearch.Config.parse(o.deepCopy()).toJson().toString();
        AssertJUnit.assertTrue(onJson, onJson.contains("\"basics\":\"twoStage\"") && onJson.contains("\"basicsShare\":0.3556"));
        final JsonObject bad = o.deepCopy();
        bad.addProperty("belief", "off");
        try {
            RlSearch.Config.parse(bad);
            AssertJUnit.fail("twoStage without the honest pool");
        } catch (IllegalArgumentException e) {
            // refused
        }
        final JsonObject lab = new JsonObject();
        lab.addProperty("redraw", "belief");
        final JsonObject sc = new JsonObject();
        sc.addProperty("hideOwnLibrary", "true");
        lab.add("solverCfg", sc);
        lab.add("lossCfg", sc.deepCopy());
        final String labOff = HonestLabel.Spec.parse(lab.deepCopy()).toJson().toString();
        AssertJUnit.assertFalse(labOff, labOff.contains("basics\""));
        lab.addProperty("basics", "twoStage");
        lab.addProperty("basicsShare", 0.38);
        final HonestLabel.Spec ls = HonestLabel.Spec.parse(lab.deepCopy());
        AssertJUnit.assertTrue(ls.toJson().toString().contains("\"basicsShare\":0.38"));
        final LookaheadSearch.Config bc = ls.beliefConfig();
        AssertJUnit.assertEquals("twoStage", LookaheadSearch.Config.class.getField("basics").get(bc));
    }
}
