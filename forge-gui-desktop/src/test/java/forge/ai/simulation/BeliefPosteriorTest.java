package forge.ai.simulation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.LobbyPlayerAi;
import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlSearch;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * belief-jar-1027: the two-stage basics ({@code basics=twoStage}) and the opponent-deck posterior ({@code belief=posterior})
 * in the look-ahead's honest worlds. The new config fields are set by reflection, so this class also runs on a jar
 * without them, where every keyed test fails (red on the live jar, green on the fix).
 */
public class BeliefPosteriorTest extends SimulationTest {

    /** The test pool (all resolve in Forge); the opponent shows Shock, so a singleton pool drops it. */
    private static final String[] POOL = {"Counterspell", "Lightning Bolt", "Hill Giant", "Shock", "Giant Growth",
        "Grizzly Bears", "Wrath of God", "Mana Leak", "Prodigal Pyromancer", "Serra Angel", "Llanowar Elves", "Dark Ritual"};

    private static void set(Object o, String field, Object v) {
        try {
            o.getClass().getField(field).set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("this jar has no config field " + field + " (" + e + ")", e);
        }
    }

    private static String sha(byte[] b) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /**
     * A synthetic posterior over {@link #POOL}: all static terms 0, co-draft weights 0, human counts N = 10 with Shock and
     * Lightning Bolt in 9 decks together (every other card in 1), human PMI weight {@code wPmi}, popularity weight 0.
     * Returns {path, sha256}.
     */
    private static String[] posteriorFile(double wPmi, double share, List<String> deckHashes, List<String> groups) throws Exception {
        final int n = POOL.length;
        final JsonObject o = new JsonObject();
        o.addProperty("schema", "mtgx-belief-posterior/1");
        o.addProperty("name", "test");
        final JsonArray voc = new JsonArray();
        for (String x : POOL) {
            voc.add(x);
        }
        o.add("vocab", voc);
        o.addProperty("basicsShare", share);
        o.addProperty("unknownLogit", -5.0);
        final JsonObject st = new JsonObject();
        final JsonArray base = new JsonArray();
        for (int i = 0; i < n; i++) {
            base.add(0.0);
        }
        st.add("base", base);
        st.addProperty("pairs_f32le_b64", Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(4 * n * n).order(ByteOrder.LITTLE_ENDIAN).array()));
        o.add("static", st);
        final JsonObject h = new JsonObject();
        h.addProperty("N", 10);
        h.addProperty("alpha", 1.0);
        h.addProperty("lam", 3.0);
        final JsonArray cnt = new JsonArray();
        for (String x : POOL) {
            cnt.add("Shock".equals(x) || "Lightning Bolt".equals(x) ? 9 : 1);
        }
        h.add("count", cnt);
        final JsonArray pairs = new JsonArray();
        final int shock = Arrays.asList(POOL).indexOf("Shock"), bolt = Arrays.asList(POOL).indexOf("Lightning Bolt");
        pairs.add(Math.min(shock, bolt));
        pairs.add(Math.max(shock, bolt));
        pairs.add(9);
        h.add("pairs", pairs);
        h.addProperty("wPmi", wPmi);
        h.addProperty("wPop", 0.0);
        o.add("human", h);
        final JsonObject cd = new JsonObject();
        cd.addProperty("resource", "classpath:/forge/ai/simulation/defend-prior-cobra-mush.json");
        cd.addProperty("sha256", "70521d1fdc88fb134fb34e61e9c41fbf0bcabb6538a7d96c93aacbc205967613");
        cd.addProperty("gain", 20.0);
        cd.addProperty("wMax", 0.0);
        cd.addProperty("wMean", 0.0);
        o.add("codraft", cd);
        final JsonObject man = new JsonObject();
        final JsonObject dk = new JsonObject();
        final JsonArray dh = new JsonArray();
        for (String x : deckHashes) {
            dh.add(x);
        }
        dk.add("human", dh);
        man.add("decks", dk);
        final JsonObject gr = new JsonObject();
        final JsonArray gh = new JsonArray();
        for (String x : groups) {
            gh.add(BeliefPosterior.groupHash(x));
        }
        gr.add("human", gh);
        man.add("groups", gr);
        o.add("manifest", man);
        final byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        final Path p = Files.createTempFile("belief-posterior", ".json");
        Files.write(p, b);
        p.toFile().deleteOnExit();
        return new String[] {p.toString(), sha(b)};
    }

    /** Seat 0's main phase; seat 1 (the opponent, 16 cards): 3 hidden hand cards, 11 hidden library cards, a Forest, a Shock in the graveyard. */
    private Game board(Game game) {
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
        for (String x : new String[] {"Craw Wurm", "Craw Wurm", "Craw Wurm"}) {
            addCardToZone(x, b, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** As SimulationTest's game, but each seat registers its own deck. */
    private Game gameWithDecks(Deck d0, Deck d1) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(d0).setPlayer(new LobbyPlayerAi("p2", null)));
        players.add(new RegisteredPlayer(d1).setPlayer(new LobbyPlayerAi("p1", new HashSet<>())));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        game.AI_TIMEOUT = FModel.getPreferences().getPrefInt(FPref.MATCH_AI_TIMEOUT);
        return game;
    }

    private static LookaheadSearch.Config cfg(String belief) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 3;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1027L;
        c.belief = belief;
        c.beliefPool = Arrays.asList(POOL);
        c.beliefSingleton = true;
        return c;
    }

    /** The jar's live pool (the bundled mush cube, 540 cards), as a singleton cube. */
    private static LookaheadSearch.Config cfgCube(String belief) {
        LookaheadSearch.Config c = cfg(belief);
        c.beliefPool = null;
        c.beliefCube = BeliefSampler.LIVE_CUBE;
        c.beliefCubeSha256 = BeliefSampler.LIVE_CUBE_SHA256;
        return c;
    }

    /** One world: a fresh copy re-drawn through {@code rd}; the copy's opponent hidden cards (hand then library). */
    private static List<String> world(Game game, ForcedWinSolver.Redraw rd, Random rng) {
        Player a = game.getPlayers().get(0);
        GameCopier c = new GameCopier(game, true);
        Game g = c.makeCopy();
        rd.apply(game, a, g, (Player) c.find(a), rng);
        Player o = (Player) c.find(game.getPlayers().get(1));
        List<String> out = new ArrayList<>();
        for (Card x : o.getCardsIn(ZoneType.Hand)) {
            out.add(x.getName());
        }
        for (Card x : o.getCardsIn(ZoneType.Library)) {
            out.add(x.getName());
        }
        AssertJUnit.assertEquals(3, o.getCardsIn(ZoneType.Hand).size());
        AssertJUnit.assertEquals(11, o.getCardsIn(ZoneType.Library).size());
        return out;
    }

    private static boolean isLand(String name) {
        return BeliefSampler.isBasic(name);
    }

    /**
     * The land share of drawn worlds (the jar's live pool): with the keys off a world's 14 hidden cards hold ~1 basic
     * (8 slots per basic among ~580), far below a real deck's share; with basics=twoStage they hold exactly the deck's
     * expected hidden basics (here 0.5 x 16 cards - 1 seen Forest = 7), through every entry point (main worlds, the
     * solver's re-draw, the defender's prior), and the jar's default share gives 0.29636 x 16 - 1 on average.
     */
    @Test
    public void twoStageWorldsHoldTheDecksShareOfBasics() throws Exception {
        Game game = board(initAndCreateGame());
        forge.util.IdScope.open();
        // control (keys off): the live jar's worlds
        LookaheadSearch off = new LookaheadSearch(cfgCube("uniform"));
        double offBasics = 0;
        for (int i = 0; i < 40; i++) {
            final ForcedWinSolver.Redraw rd = off::redraw;
            offBasics += world(game, rd, new Random(i)).stream().filter(BeliefPosteriorTest::isLand).count();
        }
        offBasics /= 40;
        AssertJUnit.assertTrue("keys off: few basics (" + offBasics + ")", offBasics < 4.0);
        // twoStage
        LookaheadSearch.Config c = cfgCube("uniform");
        set(c, "basics", "twoStage");
        set(c, "basicsShare", 0.5);
        LookaheadSearch on = new LookaheadSearch(c);
        final Map<String, ForcedWinSolver.Redraw> entries = new java.util.LinkedHashMap<>();
        entries.put("main worlds", on::redraw);
        entries.put("solver", on.opponentRedraw());
        entries.put("defender prior", on.opponentRedrawPrior(Collections.emptyMap(), 20.0, false, 0));
        for (Map.Entry<String, ForcedWinSolver.Redraw> e : entries.entrySet()) {
            for (int i = 0; i < 30; i++) {
                List<String> w = world(game, e.getValue(), new Random(100 + i));
                long basics = w.stream().filter(BeliefPosteriorTest::isLand).count();
                AssertJUnit.assertEquals(e.getKey() + ": 7 hidden basics per world", 7, basics);
                AssertJUnit.assertFalse(e.getKey() + ": the singleton pool keeps Shock out", w.contains("Shock"));
                AssertJUnit.assertFalse(e.getKey() + ": never their real cards", w.contains("Craw Wurm") || w.contains("Raging Goblin"));
                AssertJUnit.assertEquals(e.getKey() + ": the nonbasic draw is without repeats", 7, new HashSet<>(
                        w.stream().filter(x -> !isLand(x)).collect(java.util.stream.Collectors.toList())).size());
            }
        }
        AssertJUnit.assertTrue(on.getStats().twoStageWorlds >= 90);
        // the jar's default share (no basicsShare): the human decks' 11.85 of 40
        LookaheadSearch.Config c2 = cfgCube("uniform");
        set(c2, "basics", "twoStage");
        LookaheadSearch dflt = new LookaheadSearch(c2);
        double mean = 0;
        for (int i = 0; i < 200; i++) {
            final ForcedWinSolver.Redraw rd = dflt::redraw;
            mean += world(game, rd, new Random(500 + i)).stream().filter(BeliefPosteriorTest::isLand).count();
        }
        mean /= 200;
        final double want = LookaheadSearch.BASICS_SHARE_HUMAN * 16 - 1;
        AssertJUnit.assertEquals("default share x deck size - seen basics", want, mean, 0.15);
        off.shutdown();
        on.shutdown();
        dflt.shutdown();
        forge.util.IdScope.close();
    }

    /**
     * The posterior's sampling distribution: the opponent showed Shock, and the posterior's human decks hold Shock and
     * Lightning Bolt together, so Bolt's odds weight is exp(wPmi x (PMI(Shock, Bolt) - PMI(Shock, other))). Every nonbasic
     * card's frequency over the worlds matches the conditional Bernoulli marginals of those weights (7 of the 11 pool
     * cards per world), and Bolt is drawn far above uniform.
     */
    @Test
    public void posteriorWorldsFollowItsWeights() throws Exception {
        final String[] f = posteriorFile(2.0, 0.5, Collections.emptyList(), Collections.emptyList());
        Game game = board(initAndCreateGame());
        forge.util.IdScope.open();
        LookaheadSearch.Config c = cfg("posterior");
        set(c, "basics", "twoStage");
        set(c, "beliefPosterior", f[0]);
        set(c, "beliefPosteriorSha256", f[1]);
        LookaheadSearch on = new LookaheadSearch(c);
        // the expected weights, by hand: PMI(s, c) = log(((C + lam p_c) / (n_s + lam)) / p_c), p = (n + 1) / (N + 2)
        final double lam = 3, bigN = 10;
        final double pBolt = 10 / (bigN + 2), pOther = 2 / (bigN + 2);
        final double pmiBolt = Math.log(((9 + lam * pBolt) / (9 + lam)) / pBolt);
        final double pmiOther = Math.log(((0 + lam * pOther) / (9 + lam)) / pOther);
        final List<String> nb = new ArrayList<>(Arrays.asList(POOL));
        nb.remove("Shock");
        final double[] w = new double[nb.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = Math.exp(2.0 * ("Lightning Bolt".equals(nb.get(i)) ? pmiBolt - pmiOther : 0.0));
        }
        double mx = 0;
        for (double x : w) {
            mx = Math.max(mx, x);
        }
        for (int i = 0; i < w.length; i++) {
            w[i] /= mx;
        }
        final double[] pi = new BeliefSampler.Dist(nb, w, 0, 7, 0).marginals();
        // the jar's own weights agree with the hand computation
        final double[] jw = BeliefSampler.weights(on.posterior().logits(nb, Collections.singleton("Shock")));
        for (int i = 0; i < w.length; i++) {
            AssertJUnit.assertEquals(nb.get(i), w[i], jw[i], 1e-9);
        }
        final int worlds = 1200;
        final Map<String, Integer> count = new HashMap<>();
        final ForcedWinSolver.Redraw rd = on::redraw;
        for (int i = 0; i < worlds; i++) {
            for (String x : world(game, rd, new Random(7000 + i))) {
                if (!isLand(x)) {
                    count.merge(x, 1, Integer::sum);
                }
            }
        }
        for (int i = 0; i < nb.size(); i++) {
            final double got = count.getOrDefault(nb.get(i), 0) / (double) worlds;
            final double sd = Math.sqrt(pi[i] * (1 - pi[i]) / worlds);
            AssertJUnit.assertEquals(nb.get(i) + " frequency vs its marginal " + pi[i], pi[i], got, 4 * sd + 1e-3);
        }
        final double bolt = count.getOrDefault("Lightning Bolt", 0) / (double) worlds;
        AssertJUnit.assertTrue("Bolt " + bolt + " far above uniform 7/11", bolt > 0.9 && pi[nb.indexOf("Lightning Bolt")] > 0.9);
        // the solver's and the defender's re-draws read the same posterior
        for (ForcedWinSolver.Redraw r2 : new ForcedWinSolver.Redraw[] {on.opponentRedraw(),
                on.opponentRedrawPrior(Collections.emptyMap(), 20.0, false, 0)}) {
            int hits = 0;
            for (int i = 0; i < 200; i++) {
                if (world(game, r2, new Random(9000 + i)).contains("Lightning Bolt")) {
                    hits++;
                }
            }
            AssertJUnit.assertTrue("posterior in every entry point: Bolt " + hits + "/200", hits > 170);
        }
        AssertJUnit.assertEquals(0, on.getStats().posteriorRefused);
        AssertJUnit.assertEquals(f[1], on.getStats().toJson().get("posteriorSha256").getAsString());
        on.shutdown();
        forge.util.IdScope.close();
    }

    /**
     * HONESTY: a posterior whose training manifest holds this game's deck (or a group the host names: its pod) is refused
     * for the game; its worlds then draw uniformly (two-stage), and the refusal is counted.
     */
    @Test
    public void aPosteriorTrainedOnThisGamesDeckOrPodIsRefused() throws Exception {
        Deck mine = new Deck();
        mine.getMain().add("Mountain", 20);
        mine.getMain().add("Hill Giant", 20);
        Deck theirs = new Deck();
        theirs.getMain().add("Forest", 16);
        theirs.getMain().add("Craw Wurm", 12);
        theirs.getMain().add("Raging Goblin", 11);
        theirs.getMain().add("Shock", 1);
        final List<String> theirNames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            theirNames.add("Craw Wurm");
        }
        for (int i = 0; i < 11; i++) {
            theirNames.add("Raging Goblin");
        }
        theirNames.add("Shock");
        for (int i = 0; i < 16; i++) {
            theirNames.add("Forest");
        }
        final String theirHash = BeliefPosterior.deckHash(theirNames);
        // (1) the deck in the manifest
        final String[] fd = posteriorFile(2.0, 0.5, Collections.singletonList(theirHash), Collections.emptyList());
        // (2) the pod in the manifest (the host names it)
        final String[] fp = posteriorFile(2.0, 0.5, Collections.singletonList("0000000000000000"),
                Collections.singletonList("pod:d2p-0042"));
        // (3) neither
        final String[] fn = posteriorFile(2.0, 0.5, Collections.singletonList("0000000000000000"),
                Collections.singletonList("pod:other"));
        for (int t = 0; t < 3; t++) {
            Game game = board(gameWithDecks(mine, theirs));
            forge.util.IdScope.open();
            final String[] f = t == 0 ? fd : t == 1 ? fp : fn;
            LookaheadSearch.Config c = cfg("posterior");
            set(c, "basics", "twoStage");
            set(c, "beliefPosterior", f[0]);
            set(c, "beliefPosteriorSha256", f[1]);
            set(c, "beliefGroups", Arrays.asList("pod:d2p-0042", "game:7"));
            LookaheadSearch on = new LookaheadSearch(c);
            final String why = on.checkPosteriorHonest(game);
            int bolt = 0;
            final ForcedWinSolver.Redraw rd = on::redraw;
            for (int i = 0; i < 300; i++) {
                if (world(game, rd, new Random(300 + i)).contains("Lightning Bolt")) {
                    bolt++;
                }
            }
            if (t < 2) {
                AssertJUnit.assertNotNull("refused (" + t + ")", why);
                AssertJUnit.assertTrue(why, why.contains("training manifest") && why.contains(t == 0 ? "seat 1" : "pod:d2p-0042"));
                AssertJUnit.assertEquals(1, on.getStats().posteriorRefused);
                // uniform two-stage: Bolt in 7 of 11 nonbasic slots' worth, about 0.64 of worlds
                AssertJUnit.assertTrue("refused: Bolt about uniform (" + bolt + "/300)", bolt > 150 && bolt < 240);
            } else {
                AssertJUnit.assertNull(why);
                AssertJUnit.assertEquals(0, on.getStats().posteriorRefused);
                AssertJUnit.assertTrue("served: Bolt " + bolt + "/300", bolt > 270);
            }
            on.shutdown();
            forge.util.IdScope.close();
        }
        // the canonical deck hash: basics left out, order and split-card spelling ignored
        AssertJUnit.assertEquals(BeliefPosterior.deckHash(Arrays.asList("Shock", "Fire // Ice", "Forest")),
                BeliefPosterior.deckHash(Arrays.asList("Fire", "Shock")));
    }

    /**
     * Keys off = the live jar: the config JSON gains nothing, and with beliefSeat = 0 the other seat's worlds are the
     * keys-off draw, card for card (the asymmetric do-no-harm game's control seat).
     */
    @Test
    public void keysOffAndTheOtherSeatDrawAsBefore() throws Exception {
        AssertJUnit.assertEquals(cfg("uniform").toJson().toString(), cfg("uniform").toJson().toString());
        LookaheadSearch.Config plain = cfg("uniform");
        final String plainJson = plain.toJson().toString();
        AssertJUnit.assertFalse(plainJson, plainJson.contains("basics\"") || plainJson.contains("Posterior") || plainJson.contains("beliefSeat"));
        Game game = board(initAndCreateGame());
        forge.util.IdScope.open();
        LookaheadSearch off = new LookaheadSearch(cfg("uniform"));
        LookaheadSearch.Config c = cfg("uniform");
        set(c, "basics", "twoStage");
        set(c, "beliefSeat", 0);
        LookaheadSearch seat0 = new LookaheadSearch(c);
        AssertJUnit.assertTrue(c.toJson().toString().contains("\"beliefSeat\":0"));
        // seat 1 searching (its opponent is seat 0): the keys-off draw exactly
        final Player p1 = game.getPlayers().get(1);
        for (int i = 0; i < 20; i++) {
            List<String> a = worldOf(game, p1, off, new Random(40 + i));
            List<String> b = worldOf(game, p1, seat0, new Random(40 + i));
            AssertJUnit.assertEquals("seat 1 draws as with the keys off", a, b);
        }
        // seat 0 searching: two-stage
        AssertJUnit.assertTrue(seat0.getStats().twoStageWorlds == 0);
        final ForcedWinSolver.Redraw rd = seat0::redraw;
        world(game, rd, new Random(1));
        AssertJUnit.assertEquals(1, seat0.getStats().twoStageWorlds);
        off.shutdown();
        seat0.shutdown();
        forge.util.IdScope.close();
    }

    /** Seat {@code me}'s world: its opponent's hidden hand and library after a main-world re-draw. */
    private static List<String> worldOf(Game game, Player me, LookaheadSearch s, Random rng) {
        GameCopier c = new GameCopier(game, true);
        Game g = c.makeCopy();
        s.redraw(game, me, g, (Player) c.find(me), rng);
        Player o = (Player) c.find(me.getOpponents().get(0));
        List<String> out = new ArrayList<>();
        for (Card x : o.getCardsIn(ZoneType.Hand)) {
            out.add(x.getName());
        }
        for (Card x : o.getCardsIn(ZoneType.Library)) {
            out.add(x.getName());
        }
        return out;
    }

    /**
     * The live seat's spec keys: absent = off (belief uniform, fixed basics); belief=posterior needs its pin and
     * basics=twoStage; beliefSeat is a bench key only; the jar's posterior loads with its pin and carries its manifest.
     */
    @Test
    public void liveSpecKeysAndTheJarsPosterior() throws Exception {
        final String base = "server=127.0.0.1:5420,policySha=" + "3".repeat(64) + ",cardIndex=/x,budgetMs=8000,threads=8,obs=2"
                + ",plans.samples=4,plans.solverCfg.hideOwnLibrary=true,plans.defend=true,plans.defendCfg.prior=jar";
        RlLiveSeat.Spec s0 = RlLiveSeat.Spec.parse(base);
        AssertJUnit.assertEquals("uniform", s0.search.belief);
        AssertJUnit.assertEquals("fixed", RlSearch.Config.class.getField("basics").get(s0.search));
        AssertJUnit.assertFalse(s0.search.toJson().toString().contains("basics\""));
        RlLiveSeat.Spec s1 = RlLiveSeat.Spec.parse(base + ",belief=posterior,beliefPosteriorSha256=" + BeliefPosterior.LIVE_SHA256
                + ",basics=twoStage");
        AssertJUnit.assertEquals("posterior", s1.search.belief);
        final String j = s1.search.toJson().toString();
        AssertJUnit.assertTrue(j, j.contains("\"basics\":\"twoStage\"") && j.contains(BeliefPosterior.LIVE_SHA256));
        for (String bad : new String[] {",belief=posterior,basics=twoStage", ",belief=posterior,beliefPosteriorSha256="
                + BeliefPosterior.LIVE_SHA256, ",basics=twoStage,beliefSeat=0", ",belief=off", ",basics=many"}) {
            try {
                RlLiveSeat.Spec.parse(base + bad);
                AssertJUnit.fail("accepted " + bad);
            } catch (IllegalArgumentException e) {
                // refused
            }
        }
        final BeliefPosterior p = BeliefPosterior.load(BeliefPosterior.LIVE, BeliefPosterior.LIVE_SHA256);
        AssertJUnit.assertEquals(540, p.vocab.size());
        AssertJUnit.assertTrue("manifest decks " + p.manifestDecks, p.manifestDecks > 90_000);
        AssertJUnit.assertTrue("manifest groups " + p.manifestGroups, p.manifestGroups > 40_000);
        AssertJUnit.assertEquals(LookaheadSearch.BASICS_SHARE_HUMAN, p.basicsShare, 1e-4);
        try {
            BeliefPosterior.load(BeliefPosterior.LIVE, "0".repeat(64));
            AssertJUnit.fail("a wrong pin loads");
        } catch (IllegalStateException e) {
            AssertJUnit.assertTrue(e.getMessage(), e.getMessage().contains("pinned"));
        }
        // the posterior moves mass: a shown Reanimate lifts Entomb and Animate Dead well above their unconditional odds
        final double[] z0 = p.vocabLogits(Collections.emptySet());
        final double[] z1 = p.vocabLogits(Collections.singleton("Reanimate"));
        final int ent = p.indexOf("Entomb");
        final int bolt = p.indexOf("Lightning Bolt");
        if (ent >= 0 && bolt >= 0) {
            AssertJUnit.assertTrue("Entomb lift " + (z1[ent] - z0[ent]) + " vs Bolt " + (z1[bolt] - z0[bolt]),
                    z1[ent] - z0[ent] > z1[bolt] - z0[bolt] + 0.5);
        }
        // RlSearch's bench JSON keys
        final JsonObject o = new JsonObject();
        o.addProperty("belief", "posterior");
        o.addProperty("beliefCube", BeliefSampler.LIVE_CUBE);
        o.addProperty("beliefCubeSha256", BeliefSampler.LIVE_CUBE_SHA256);
        o.addProperty("beliefPosteriorSha256", BeliefPosterior.LIVE_SHA256);
        o.addProperty("basics", "twoStage");
        o.addProperty("beliefSeat", 1);
        final RlSearch.Config rc = RlSearch.Config.parse(o);
        AssertJUnit.assertTrue(rc.toJson().toString().contains("\"beliefSeat\":1"));
        final Set<String> keysOff = new HashSet<>(RlSearch.Config.parse(new JsonObject()).toJson().keySet());
        AssertJUnit.assertFalse(keysOff.contains("basics") || keysOff.contains("beliefSeat"));
    }
}
