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

    // ---- untested-keys-1113: the held-mana term and the deck-inclusion prior (both off by default)

    private static JsonObject portFixture() throws IOException {
        try (java.io.InputStream in = DeckBeliefTest.class.getResourceAsStream("/forge/ai/simulation/deckbelief-port-1113.json")) {
            AssertJUnit.assertNotNull("the port fixture (planner-fixes/untested-keys/answers/portfixture.py)", in);
            return com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The Python port (heldmana.py, the offline fit) and DeckBelief.handWeight agree on 60 random cube cards and evidence. */
    @Test
    public void heldTermMatchesTheOfflinePort() throws IOException {
        final JsonObject fx = portFixture();
        final DeckBelief.Params p = new DeckBelief.Params();
        p.held = new double[] {1, 1.25, 1.5};
        int n = 0, moved = 0;
        for (com.google.gson.JsonElement el : fx.getAsJsonArray("handWeight")) {
            final JsonObject r = el.getAsJsonObject();
            final DeckBelief.Evidence e = new DeckBelief.Evidence();
            e.offTurn = r.get("offTurn").getAsBoolean();
            e.openMana = r.get("openMana").getAsInt();
            e.openColors = (byte) r.get("openColors").getAsInt();
            e.noLandLastTurn = r.get("noLandLastTurn").getAsBoolean();
            final PaperCard pc = BeliefSampler.resolve(r.get("name").getAsString());
            AssertJUnit.assertNotNull(r.get("name").getAsString(), pc);
            AssertJUnit.assertEquals(r.toString(), r.get("weight").getAsDouble(), DeckBelief.handWeight(pc, e, p), 1e-9);
            final double w0 = DeckBelief.handWeight(pc, e, new DeckBelief.Params());
            moved += w0 != DeckBelief.handWeight(pc, e, p) ? 1 : 0;
            n++;
        }
        AssertJUnit.assertEquals(60, n);
        AssertJUnit.assertTrue("the term moves some instant-speed cards: " + moved, moved > 0);
    }

    @Test
    public void heldTermWeighsCastableInstantsOffTurnOnly() {
        final DeckBelief.Params p = new DeckBelief.Params();
        p.held = DeckBelief.parseHeld("1/1.25/1.5");
        final DeckBelief.Evidence e = new DeckBelief.Evidence();
        e.offTurn = true;
        e.openColors = MagicColor.BLUE;
        e.openMana = 2;
        AssertJUnit.assertEquals(1.25, DeckBelief.handWeight(paper("Counterspell"), e, p)); // castable with 2 held
        e.openMana = 4;
        AssertJUnit.assertEquals(1.5, DeckBelief.handWeight(paper("Counterspell"), e, p)); // h 3+
        e.openMana = 1;
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Counterspell"), e, p)); // not castable with 1
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Unsummon"), e, p)); // h = 1: factor 1
        e.openMana = 3;
        e.openColors = MagicColor.RED;
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Counterspell"), e, p)); // off-colour for the held mana
        AssertJUnit.assertEquals(1.5, DeckBelief.handWeight(paper("Lightning Bolt"), e, p));
        e.offTurn = false;
        AssertJUnit.assertEquals(1.0, DeckBelief.handWeight(paper("Lightning Bolt"), e, p)); // its own turn: no evidence
        AssertJUnit.assertEquals("1/1.25/1.5", DeckBelief.heldString(p.held));
        for (String bad : new String[] {"1/1.25", "1/0.5/1.5", "0/1/1", "a/b/c", "1/1/Infinity", "1/1/NaN", "1,1.25,1.5"}) {
            try {
                DeckBelief.parseHeld(bad);
                AssertJUnit.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // three positive, non-decreasing factors
            }
        }
    }

    /** The jar's inclusion table is the pinned one, and its rates are the offline fit's (30 fixture rows). */
    @Test
    public void inclusionTableIsPinnedAndMatchesTheOfflineRates() throws IOException {
        final DeckBelief.Inclusion t = DeckBelief.Inclusion.live();
        AssertJUnit.assertTrue(t.sha256, t.sha256.startsWith(DeckBelief.Inclusion.LIVE_SHA256));
        for (com.google.gson.JsonElement el : portFixture().getAsJsonArray("rates")) {
            final JsonObject r = el.getAsJsonObject();
            AssertJUnit.assertEquals(r.toString(), r.get("rate").getAsDouble(),
                    t.rate(r.get("name").getAsString(), (byte) r.get("colors").getAsInt()), 1e-9);
        }
    }

    /** With the inclusion prior, a world deals the cards decks of its colours play more often; off, the deal is unchanged. */
    @Test
    public void inclusionPriorShiftsTheDealAndOffIsUnchanged() throws IOException {
        final DeckBelief.Inclusion t = DeckBelief.Inclusion.live();
        final List<String> pool = new ArrayList<>(t.global.keySet());
        java.util.Collections.sort(pool);
        final DeckBelief.Evidence e = new DeckBelief.Evidence();
        e.shown = (byte) (MagicColor.BLUE | MagicColor.RED);
        e.offTurn = true;
        final DeckBelief.Params off = new DeckBelief.Params(), on = new DeckBelief.Params();
        on.inclusion = t;
        final DeckBelief.Params heldOnly = new DeckBelief.Params();
        heldOnly.held = new double[] {1, 1, 1}; // factor 1 everywhere: the same deal as off
        double rOn = 0, rOff = 0;
        for (long s = 0; s < 200; s++) {
            final DeckBelief.Deal a = DeckBelief.deal(pool, BeliefSampler::resolve, e, 5, 30, off, new Random(s));
            final DeckBelief.Deal b = DeckBelief.deal(pool, BeliefSampler::resolve, e, 5, 30, on, new Random(s));
            final DeckBelief.Deal c = DeckBelief.deal(pool, BeliefSampler::resolve, e, 5, 30, heldOnly, new Random(s));
            AssertJUnit.assertEquals(a.hand, c.hand);
            AssertJUnit.assertEquals(a.library, c.library);
            for (String n : a.hand) rOff += t.rate(n, a.colors);
            for (String n : b.hand) rOn += t.rate(n, b.colors);
        }
        System.out.println("untested-keys-1113 mean inclusion rate of dealt hand cards: off " + rOff / 1000 + " on " + rOn / 1000);
        AssertJUnit.assertTrue("on " + rOn + " off " + rOff, rOn > 1.2 * rOff);
    }

    /** LookaheadSearch: the two keys off = the default params object; on = the term and the jar's table. */
    @Test
    public void searchParamsFollowTheKeys() throws Exception {
        String cube = cubeFile();
        forge.util.IdScope.open();
        LookaheadSearch.Config c0 = cfg(cube);
        c0.beliefDeck = true;
        LookaheadSearch l0 = new LookaheadSearch(c0);
        DeckBelief.Params p0 = l0.searchDeckParams();
        AssertJUnit.assertNull(p0.held);
        AssertJUnit.assertNull(p0.inclusion);
        AssertJUnit.assertFalse(c0.toJson().has("beliefDeckHeld") || c0.toJson().has("beliefDeckInclusion"));
        LookaheadSearch.Config c1 = cfg(cube);
        c1.beliefDeck = true;
        c1.beliefDeckHeld = new double[] {1, 1.25, 1.5};
        c1.beliefDeckInclusion = true;
        LookaheadSearch l1 = new LookaheadSearch(c1);
        DeckBelief.Params p1 = l1.searchDeckParams();
        AssertJUnit.assertEquals(1.5, p1.held[2]);
        AssertJUnit.assertSame(DeckBelief.Inclusion.live(), p1.inclusion);
        AssertJUnit.assertEquals("1/1.25/1.5", c1.toJson().get("beliefDeckHeld").getAsString());
        AssertJUnit.assertTrue(c1.toJson().get("beliefDeckInclusion").getAsBoolean());
        l0.shutdown();
        l1.shutdown();
        forge.util.IdScope.close();
    }
}
