package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane belief-n11-1030 (N11): the learned deck belief ("deck").
 * <ul>
 *   <li>the model's sampler draws exactly the hidden count, never a shown or repeated non-basic, and is a function of
 *       its inputs and the seed;</li>
 *   <li>deck worlds follow the model over the cards the opponent showed, never their real hidden cards, and the plans'
 *       solver re-draw uses the same model;</li>
 *   <li>the export is pinned by sha256, and a spec without belief=deck carries none of its keys.</li>
 * </ul>
 * The test model is a hand-set 1-d export: showing Esper Sentinel makes Thraben Inspector ~e^10 times likelier.
 */
public class BeliefDeckTest extends SimulationTest {

    @org.testng.annotations.BeforeMethod
    public void resetRefusal() {
        DeckBeliefModel.resetRefusalForTests();
    }

    static String export() {
        // vocab: Esper Sentinel (a = 1), Thraben Inspector (u = 10), Mox Pearl; h = relu(mean a) (d = 1, one hidden unit)
        return export(provenance("\"k0\""));
    }

    /** A valid source block whose held-out deck keys are {@code keys} (JSON strings, comma-joined). */
    static String provenance(final String keys) {
        return "\"source\":{\"mix\":null,\"train\":{\"mix\":null},\"near_jaccard\":0.5,"
                + "\"near_banks\":[\"train/cur-1009\",\"live/human\"],\"excluded_banks\":[\"train/cur-1009\",\"live/human\"],"
                + "\"held_deck_keys\":[" + keys + "]},";
    }

    static final String PROVENANCE = provenance("\"k0\"");

    static String export(final String provenance) {
        return "{\"schema\":\"mtgx-deckbelief-export/1\",\"kind\":\"bag\",\"d\":1," + provenance
                + "\"vocab\":[\"Esper Sentinel\",\"Thraben Inspector\",\"Mox Pearl\"],"
                + "\"a\":[[1.0],[0.0],[0.0]],\"u\":[[0.0],[10.0],[0.0]],\"b\":[0.0,0.0,0.0],"
                + "\"w1\":[[1.0,0.0]],\"b1\":[0.0],\"w2\":[[1.0]],\"b2\":[0.0]}";
    }

    static DeckBeliefModel model(final Path dir) throws Exception {
        return model(dir, "\"k0\"");
    }

    static DeckBeliefModel model(final Path dir, final String keys) throws Exception {
        final Path p = dir.resolve("deck-belief-test-" + Integer.toHexString(keys.hashCode()) + ".json");
        Files.write(p, export(provenance(keys)).getBytes(StandardCharsets.UTF_8));
        return DeckBeliefModel.loadPinned(p.toString(), sha(p));
    }

    /** A frame game registers no deck: give {@code p} a fixed one (as LookaheadSearch.worldDecks does, by reflection). */
    static void registerDeck(final Player p) throws Exception {
        final forge.deck.Deck d = new forge.deck.Deck("fixture");
        final forge.deck.CardPool main = d.getOrCreate(forge.deck.DeckSection.Main);
        main.add(forge.model.FModel.getMagicDb().getCommonCards().getCard("Esper Sentinel"));
        main.add(forge.model.FModel.getMagicDb().getCommonCards().getCard("Plains"), 2);
        final java.lang.reflect.Field f = forge.game.player.RegisteredPlayer.class.getDeclaredField("currentDeck");
        f.setAccessible(true);
        f.set(p.getRegisteredPlayer(), d);
    }

    /** The deck key of {@code p}'s registered main deck (the gate's key). */
    static String keyOf(final Player p) {
        final java.util.Map<String, Integer> main = new java.util.HashMap<>();
        for (java.util.Map.Entry<forge.item.PaperCard, Integer> e : p.getRegisteredPlayer().getDeck()
                .get(forge.deck.DeckSection.Main)) {
            main.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        return DeckBeliefModel.deckKey(main);
    }

    @Test
    public void theDeckKeyMatchesMtgxDataDeckKey() {
        // mtgx deckbelief data.deck_key(Counter({"Island": 2, "Mox Pearl": 1, "Lórien Revealed": 1}))
        final java.util.Map<String, Integer> m = new java.util.HashMap<>();
        m.put("Mox Pearl", 1);
        m.put("Island", 2);
        m.put("Lórien Revealed", 1);
        Assert.assertEquals(DeckBeliefModel.deckKey(m), "ec292eddd94e675ab28e139f2d945e5a8f8d75d1ef905512d2d2fe0f92d401e7");
    }

    static String sha(final Path p) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    @Test
    public void theSamplerDrawsTheHiddenCountWithoutShownOrRepeatedNonBasics() throws Exception {
        final DeckBeliefModel m = model(Files.createTempDirectory("deckbelief"));
        final List<String> pool = new ArrayList<>(Arrays.asList("Esper Sentinel", "Thraben Inspector", "Mox Pearl",
                "Swords to Plowshares", "Plains", "Island"));
        for (int i = 0; i < 50; i++) {
            final List<String> a = m.sample(Arrays.asList("Esper Sentinel", "Plains"), pool, 9, new Random(i));
            final List<String> b = m.sample(Arrays.asList("Esper Sentinel", "Plains"), pool, 9, new Random(i));
            Assert.assertEquals(a, b, "the sampler is a function of its inputs and the seed");
            Assert.assertEquals(a.size(), 9);
            Assert.assertFalse(a.contains("Esper Sentinel"), "a shown non-basic was drawn again: " + a);
            final List<String> nb = new ArrayList<>(a);
            nb.removeIf(DeckBeliefModel.BASICS::contains);
            Assert.assertEquals(new HashSet<>(nb).size(), nb.size(), "a non-basic repeated: " + a);
            Assert.assertTrue(a.contains("Thraben Inspector"), "the model's partner card is missing: " + a);
        }
        // nothing shown: Thraben Inspector is no likelier than the other cards (h = 0)
        // one generator across draws: java.util.Random's first nextDouble() is nearly equal for small consecutive seeds
        int hits = 0;
        final Random r = new Random(12345);
        for (int i = 0; i < 400; i++) {
            if (m.sample(Collections.emptyList(), pool, 1, r).contains("Thraben Inspector")) {
                hits++;
            }
        }
        Assert.assertTrue(hits > 40 && hits < 110, "uniform-ish with nothing shown: " + hits + " / 400");
    }

    @Test
    public void theExportIsPinned() throws Exception {
        final Path dir = Files.createTempDirectory("deckbelief");
        final Path p = dir.resolve("x.json");
        Files.write(p, export().getBytes(StandardCharsets.UTF_8));
        try {
            DeckBeliefModel.loadPinned(p.toString(), "00" + sha(p).substring(2));
            Assert.fail("a wrong pin was accepted");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("sha256"), e.getMessage());
        }
    }

    @Test
    public void anExportWithoutTheLineageHoldoutIsRefused() throws Exception {
        final Path dir = Files.createTempDirectory("deckbelief");
        // each case breaks exactly one rule and must be refused by that rule's own message
        final String[][] bad = {
            {"", "no source provenance"},
            {PROVENANCE.replace("\"mix\":null,\"train\"", "\"mix\":\"0.5=train/cur-\",\"train\""), "mix null"},
            {PROVENANCE.replace("\"train\":{\"mix\":null}", "\"train\":{\"mix\":\"0.5=train/cur-\"}"), "mix null"},
            {PROVENANCE.replace("\"mix\":null,\"train\":{\"mix\":null},", "\"train\":{\"mix\":null},"), "mix null"},
            {PROVENANCE.replace("\"near_jaccard\":0.5", "\"near_jaccard\":0"), "lineage holdout (near_jaccard 0.0)"},
            {PROVENANCE.replace("\"near_jaccard\":0.5", "\"near_jaccard\":0.9"), "lineage holdout (near_jaccard 0.9)"},
            {PROVENANCE.replace("\"held_deck_keys\":[\"k0\"]", "\"held_deck_keys\":[]"), "held_deck_keys"},
            {PROVENANCE.replace("\"near_banks\":[\"train/cur-1009\",\"live/human\"]", "\"near_banks\":[\"train/cur-1009\"]"),
                "lineage-hold-out live/human"},
            {PROVENANCE.replace("\"excluded_banks\":[\"train/cur-1009\",\"live/human\"]", "\"excluded_banks\":[\"live/human\"]"),
                "not both excluded and lineage-held-out"}};
        for (int i = 0; i < bad.length; i++) {
            final Path p = dir.resolve("bad" + i + ".json");
            Files.write(p, export(bad[i][0]).getBytes(StandardCharsets.UTF_8));
            try {
                DeckBeliefModel.loadPinned(p.toString(), sha(p));
                Assert.fail("export " + i + " was accepted");
            } catch (IllegalArgumentException e) {
                Assert.assertTrue(e.getMessage().contains(bad[i][1]), "case " + i + ": " + e.getMessage());
            }
        }
    }

    @Test
    public void aSpecWithoutDeckCarriesNoDeckKeysAndDeckNeedsItsPin() {
        final String base = "{\"server\":\"127.0.0.1:1\",\"worlds\":2,\"belief\":\"uniform\",\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE
                + "\",\"beliefCubeSha256\":\"" + BeliefSampler.LIVE_CUBE_SHA256 + "\"}";
        final RlSearch.Config off = RlSearch.Config.parse(JsonParser.parseString(base).getAsJsonObject());
        final JsonObject j = off.toJson();
        Assert.assertFalse(j.has("beliefDeck") || j.has("beliefDeckSha256"), j.toString());
        try {
            RlSearch.Config.parse(JsonParser.parseString(base.replace("\"uniform\"", "\"deck\"")).getAsJsonObject());
            Assert.fail("belief=deck without beliefDeck was accepted");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("beliefDeck"), e.getMessage());
        }
        try {
            RlSearch.Config.parse(JsonParser.parseString(base.replace("}", ",\"beliefDeck\":\"x\",\"beliefDeckSha256\":\"y\"}"))
                    .getAsJsonObject());
            Assert.fail("beliefDeck without belief=deck was accepted");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("belief=deck"), e.getMessage());
        }
    }

    @Test(timeOut = 1800000)
    public void aRefusalSwallowedInsideThreatStillSurfaces() throws Exception {
        initAndCreateGame();
        final Game g = ForcedWinSolverTest.game("Default", "Default", BvwS2tHonestyTest.frame(BvwS2tHonestyTest.OUR_HAND,
                BvwS2tHonestyTest.OUR_LIB, BvwS2tHonestyTest.OUR_BF, BvwS2tHonestyTest.THEIR_HAND_A,
                BvwS2tHonestyTest.THEIR_LIB_A, "Plains;Plains;Esper Sentinel"));
        registerDeck(g.getPlayers().get(1));
        final LookaheadSearch unlisted = deckSearch(model(Files.createTempDirectory("deckbelief"), "\"k0\""));
        final java.util.Map<String, String> props = new java.util.HashMap<>();
        props.put("budgetMs", "500");
        props.put("engine", "false");
        props.put("act", "false");
        props.put("defense", "true");
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(props::get));
        s.redraw = unlisted.solverRedraw();
        final ForcedWinSolver.Threat t = s.threat(g, g.getPlayers().get(1), null, Collections.emptyList(), false, 4);
        // threat() swallows each world's error (it reports P=0 over 0 worlds); the refusal must surface regardless
        Assert.assertEquals(t.worlds, 0, "no world may run against an unlisted deck: " + t);
        Assert.assertNotNull(DeckBeliefModel.refusal(), "a refusal swallowed inside threat() was lost");
        try {
            DeckBeliefModel.throwIfRefused();
            Assert.fail("throwIfRefused did not throw");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("is not one this export"), e.getMessage());
        }
        try {
            unlisted.getConfig().beliefDeckModel.sample(Collections.emptyList(), Arrays.asList("Plains"), 1, new Random(1));
            Assert.fail("sample drew after a refusal");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("is not one this export"), e.getMessage());
        }
    }

    static LookaheadSearch deckSearch(final DeckBeliefModel m) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 1;
        c.belief = "deck";
        c.beliefCube = BeliefSampler.LIVE_CUBE;
        c.beliefCubeSha256 = BeliefSampler.LIVE_CUBE_SHA256;
        c.beliefSingleton = false;
        c.beliefDeckModel = m;
        return new LookaheadSearch(c);
    }

    @Test(timeOut = 1800000)
    public void deckWorldsFollowTheModelAndNeverTheRealHiddenCards() throws Exception {
        initAndCreateGame();
        final String bf0 = "Plains;Plains;Esper Sentinel";
        final Game g0 = ForcedWinSolverTest.game("Default", "Default", BvwS2tHonestyTest.frame(BvwS2tHonestyTest.OUR_HAND,
                BvwS2tHonestyTest.OUR_LIB, BvwS2tHonestyTest.OUR_BF, BvwS2tHonestyTest.THEIR_HAND_A,
                BvwS2tHonestyTest.THEIR_LIB_A, bf0));
        registerDeck(g0.getPlayers().get(1));
        final String oppKey = keyOf(g0.getPlayers().get(1));
        // the per-deck gate, refusing: an export not held out from this opponent's deck
        final LookaheadSearch refused = deckSearch(model(Files.createTempDirectory("deckbelief"), "\"k0\""));
        try {
            final Object[] c0 = BvwS2tTargetTest.copy(g0, g0.getPlayers().get(0));
            refused.redraw(g0, g0.getPlayers().get(0), (Game) c0[0], (Player) c0[1], new Random(1));
            Assert.fail("the gate let an unlisted opponent deck through");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("is not one this export"), e.getMessage());
        }
        Assert.assertNotNull(DeckBeliefModel.refusal(), "the refusal is sticky");
        DeckBeliefModel.resetRefusalForTests();
        final DeckBeliefModel m = model(Files.createTempDirectory("deckbelief"), "\"" + oppKey + "\"");
        final LookaheadSearch deck = deckSearch(m), uni = BvwS2tTargetTest.belief("uniform", null);
        final String bf = "Plains;Plains;Esper Sentinel";
        final String a = BvwS2tHonestyTest.frame(BvwS2tHonestyTest.OUR_HAND, BvwS2tHonestyTest.OUR_LIB,
                BvwS2tHonestyTest.OUR_BF, BvwS2tHonestyTest.THEIR_HAND_A, BvwS2tHonestyTest.THEIR_LIB_A, bf);
        final String b = BvwS2tHonestyTest.frame(BvwS2tHonestyTest.OUR_HAND, BvwS2tHonestyTest.OUR_LIB,
                BvwS2tHonestyTest.OUR_BF, BvwS2tHonestyTest.THEIR_HAND_B, BvwS2tHonestyTest.THEIR_LIB_B, bf);
        final Game ga = ForcedWinSolverTest.game("Default", "Default", a), gb = ForcedWinSolverTest.game("Default", "Default", b);
        registerDeck(ga.getPlayers().get(1));
        registerDeck(gb.getPlayers().get(1));
        Assert.assertEquals(keyOf(ga.getPlayers().get(1)), oppKey);
        int hitsDeck = 0, hitsUni = 0, hitsSolver = 0;
        final int n = 40;
        for (int i = 0; i < n; i++) {
            final List<String> drawn = new ArrayList<>();
            for (Game live : new Game[] {ga, gb}) {
                final Player liveMe = live.getPlayers().get(0);
                final Object[] c = BvwS2tTargetTest.copy(live, liveMe);
                deck.redraw(live, liveMe, (Game) c[0], (Player) c[1], new Random(1000 + i));
                final Player opp = ((Player) c[1]).getOpponents().get(0);
                final List<String> hid = BvwS2tTargetTest.names(opp, ZoneType.Hand);
                hid.addAll(BvwS2tTargetTest.names(opp, ZoneType.Library));
                Assert.assertEquals(hid.size(), 11);
                drawn.add(String.join(";", hid));
                if (live == ga) {
                    hitsDeck += Collections.frequency(hid, "Thraben Inspector");
                }
            }
            Assert.assertEquals(drawn.get(1), drawn.get(0), "a deck world read the real hidden cards");
            final Player liveMe = ga.getPlayers().get(0);
            final Object[] c = BvwS2tTargetTest.copy(ga, liveMe);
            uni.redraw(ga, liveMe, (Game) c[0], (Player) c[1], new Random(1000 + i));
            final Player uo = ((Player) c[1]).getOpponents().get(0);
            hitsUni += Collections.frequency(BvwS2tTargetTest.names(uo, ZoneType.Hand), "Thraben Inspector")
                    + Collections.frequency(BvwS2tTargetTest.names(uo, ZoneType.Library), "Thraben Inspector");
            final Object[] d = BvwS2tTargetTest.copy(ga, liveMe);
            deck.solverRedraw().apply(ga, liveMe, (Game) d[0], (Player) d[1], new Random(5000 + i));
            final Player so = ((Player) d[1]).getOpponents().get(0);
            hitsSolver += Collections.frequency(BvwS2tTargetTest.names(so, ZoneType.Hand), "Thraben Inspector")
                    + Collections.frequency(BvwS2tTargetTest.names(so, ZoneType.Library), "Thraben Inspector");
        }
        System.err.println("[belief-deck] Thraben Inspector in " + n + " worlds: deck " + hitsDeck + ", solver "
                + hitsSolver + ", uniform " + hitsUni);
        Assert.assertEquals(hitsDeck, n, "every deck world should hold the model's partner card");
        Assert.assertEquals(hitsSolver, n, "the plans' solver worlds do not use the deck model");
        Assert.assertTrue(hitsUni < 8, "uniform worlds: " + hitsUni);
    }
}
