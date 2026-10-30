package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * r2-choose-a-card-1029 (DESIGN-r2.md): off / shadow identity, enumeration per kind, the batched value pass, the solver
 * check, the Doomsday + Gush tutor test, the route-guided pile beam, and the costed-draw expectation (The One Ring).
 * Every value here is a STUB function of the copy's state (no service), so each test isolates the mechanism.
 */
public class ChooseACardTest extends SimulationTest {

    /** A stub tower: v = f(copy, seat), one values() call counted per batch. */
    static final class Stub implements ChooseACard.Valuer {
        final BiFunction<Game, Player, Double> f;
        int calls = 0, leaves = 0;

        Stub(final BiFunction<Game, Player, Double> f) {
            this.f = f;
        }

        @Override
        public Object onCopy(final Game copy, final Player me) {
            return null;
        }

        @Override
        public Object capture(final Object ctx, final Game g, final Player me) {
            return f.apply(g, me);
        }

        @Override
        public double[] values(final List<Object> payloads) {
            calls++;
            leaves += payloads.size();
            final double[] out = new double[payloads.size()];
            for (int i = 0; i < out.length; i++) out[i] = (Double) payloads.get(i);
            return out;
        }
    }

    static boolean inZone(final Player p, final ZoneType z, final String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) return true;
        }
        return false;
    }

    static List<String> names(final Player p, final ZoneType z) {
        final List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(z)) out.add(c.getName());
        return out;
    }

    static Card find(final Player p, final ZoneType z, final String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) return c;
        }
        throw new AssertionError("no " + name + " in " + z);
    }

    static ChooseACard.Config cfg(final String mode) {
        final JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        o.addProperty("topM", 0);
        return ChooseACard.Config.fromJson(o);
    }

    private static PlayerControllerAi seat(final Game game) {
        final Player a = game.getPlayers().get(0);
        final PlayerControllerAi c = new PlayerControllerAi(game, a, a.getController().getLobbyPlayer());
        a.dangerouslySetController(c);
        return c;
    }

    /** Cast {@code name} from a's hand (Forge AI pays) and resolve the whole stack. */
    private static void castAndResolve(final Game game, final String name) {
        final Player a = game.getPlayers().get(0);
        final Card c = find(a, ZoneType.Hand, name);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        AssertJUnit.assertTrue("cast " + name, ((PlayerControllerAi) a.getController()).playChosenSpellAbility(sa));
        MyRandom.setRandom(new Random(42));
        int guard = 0;
        while (!game.getStack().isEmpty() && guard++ < 10) {
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(true);
        }
        AssertJUnit.assertTrue(game.getStack().isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ boards

    /** Demonic Tutor board (tutorRank's): the library holds Lightning Bolt, creatures, lands; the opponent at oppLife. */
    private Game tutorBoard(final int oppLife) {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (String n : new String[] {"Hill Giant", "Grizzly Bears", "Lightning Bolt", "Swamp", "Craw Wurm", "Mountain", "Giant Growth", "Swamp"}) {
            addCardToZone(n, a, ZoneType.Library);
        }
        for (int i = 0; i < 20; i++) addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", b, ZoneType.Library);
        addCard("Swamp", a);
        addCard("Swamp", a);
        addCard("Mountain", a);
        addCardToZone("Demonic Tutor", a, ZoneType.Hand);
        for (int i = 0; i < 3; i++) {
            addCard("Grizzly Bears", b).setSickness(false);
            addCard("Mountain", b);
        }
        b.setLife(oppLife, null);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static String handOf(final Game g) {
        return String.join(",", names(g.getPlayers().get(0), ZoneType.Hand));
    }

    // ------------------------------------------------------------------------------------------------ identity

    @Test
    public void offAndShadowAreByteIdenticalToNoHook() {
        // no hook
        final Game g0 = tutorBoard(3);
        seat(g0);
        castAndResolve(g0, "Demonic Tutor");
        final String hand0 = handOf(g0), fp0 = LookaheadSearch.fingerprint(g0);
        final long rng0 = MyRandom.getRandom().nextLong();
        final int id0 = new forge.game.card.Card(g0.nextCardId(), g0).getId();

        // OFF: install does nothing
        final Game g1 = tutorBoard(3);
        final PlayerControllerAi c1 = seat(g1);
        final ChooseACard off = new ChooseACard(cfg("off"), new Stub((g, p) -> 0.5), null);
        off.install(c1);
        AssertJUnit.assertNull(c1.getCacHook());
        castAndResolve(g1, "Demonic Tutor");
        AssertJUnit.assertEquals(hand0, handOf(g1));
        AssertJUnit.assertEquals(fp0, LookaheadSearch.fingerprint(g1));
        AssertJUnit.assertEquals(rng0, MyRandom.getRandom().nextLong());
        AssertJUnit.assertEquals(0, off.log.size());

        // SHADOW with a value that prefers the Bolt: everything runs, the live game is the hook-less one
        final Game g2 = tutorBoard(3);
        final PlayerControllerAi c2 = seat(g2);
        final Stub stub = new Stub((g, p) -> inZone(p, ZoneType.Hand, "Lightning Bolt") ? 0.9 : 0.1);
        final ChooseACard sh = new ChooseACard(cfg("shadow"), stub, null);
        sh.install(c2);
        castAndResolve(g2, "Demonic Tutor");
        AssertJUnit.assertEquals(hand0, handOf(g2));
        AssertJUnit.assertEquals(fp0, LookaheadSearch.fingerprint(g2));
        AssertJUnit.assertEquals(rng0, MyRandom.getRandom().nextLong());
        AssertJUnit.assertEquals(id0, new forge.game.card.Card(g2.nextCardId(), g2).getId());
        AssertJUnit.assertEquals(1, sh.log.size());
        AssertJUnit.assertTrue(sh.log.toString(), sh.log.toString().contains("shadow-would-depart")
                || hand0.contains("Lightning Bolt"));
        AssertJUnit.assertEquals("one batched value pass per decision", 1, stub.calls);

        // a look-ahead config without the key: no "chooseACard" in its JSON
        AssertJUnit.assertFalse(new LookaheadSearch.Config().toJson().toString().contains("chooseACard"));
    }

    // ------------------------------------------------------------------------------------------------ SEARCH

    @Test
    public void searchEnumeratesDistinctNamesInOneBatchAndTakesTheBestValue() {
        final Game g0 = tutorBoard(3);
        seat(g0);
        castAndResolve(g0, "Demonic Tutor");
        final String plain = handOf(g0);
        AssertJUnit.assertFalse("Forge's own pick must not be the Craw Wurm here: " + plain, plain.contains("Craw Wurm"));

        final Game g = tutorBoard(3);
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub((x, p) -> inZone(p, ZoneType.Hand, "Craw Wurm") ? 0.8 : 0.2);
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        castAndResolve(g, "Demonic Tutor");
        AssertJUnit.assertTrue(handOf(g), handOf(g).contains("Craw Wurm"));
        // Forge's answer + 7 distinct names (two Swamps are one answer)
        AssertJUnit.assertEquals(String.valueOf(cac.lastCands), 8, cac.lastCands.size());
        AssertJUnit.assertEquals(1, stub.calls);
        AssertJUnit.assertEquals("SEARCH", cac.log.get(0).getAsJsonObject().get("kind").getAsString());
        AssertJUnit.assertEquals("departed", cac.log.get(0).getAsJsonObject().get("outcome").getAsString());
        // every candidate and its score is in the log
        AssertJUnit.assertEquals(8, cac.log.get(0).getAsJsonObject().getAsJsonArray("cands").size());
        // the live game moved only the chosen card
        AssertJUnit.assertEquals(7, g.getPlayers().get(0).getCardsIn(ZoneType.Library).size());
        AssertJUnit.assertEquals(3, g.getPlayers().get(1).getLife());
    }

    @Test
    public void solverCheckPromotesAProvenWin() {
        final Game g = tutorBoard(3);
        final PlayerControllerAi c = seat(g);
        final JsonObject o = new JsonObject();
        o.addProperty("mode", "on");
        o.addProperty("topM", 10);
        final Stub flat = new Stub((x, p) -> 0.5);
        final int[] checks = {0};
        final ChooseACard cac = new ChooseACard(ChooseACard.Config.fromJson(o), flat, (copy, me, known, key) -> {
            checks[0]++;
            final ChooseACard.Verdict v = new ChooseACard.Verdict();
            v.win = inZone(me, ZoneType.Hand, "Lightning Bolt");
            v.label = v.win ? "win" : "unresolved";
            v.turnsToEnd = v.win ? 0 : -1;
            return v;
        });
        cac.install(c);
        castAndResolve(g, "Demonic Tutor");
        AssertJUnit.assertTrue(handOf(g), handOf(g).contains("Lightning Bolt"));
        AssertJUnit.assertTrue(checks[0] >= 2);
        AssertJUnit.assertTrue(cac.lastScores[cac.lastPick] > 1.0);
        AssertJUnit.assertTrue(cac.log.toString().contains("\"label\":\"win\""));
    }

    /**
     * GOAL-PROMPT's worked example: Doomsday and Gush in hand, one blue source short of the kill. The stub v rewards an
     * available kill next turn (Doomsday + Gush in hand and two Islands: on the battlefield or one in hand for next
     * turn's land drop; this turn's land drop is spent). Forge AI's own Demonic Tutor pick is something else; choose-a-card takes the Island.
     */
    @Test
    public void doomsdayGushOneBlueShortTutorsTheBlueSource() {
        final BiFunction<Game, Player, Double> killAvailable = (g, p) -> {
            if (!inZone(p, ZoneType.Hand, "Doomsday") || !inZone(p, ZoneType.Hand, "Gush")) return 0.1;
            int islands = 0;
            for (Card x : p.getCardsIn(ZoneType.Battlefield)) if (x.getType().hasSubtype("Island")) islands++;
            if (inZone(p, ZoneType.Hand, "Island")) islands++; // next turn's land drop
            return islands >= 2 ? 0.8 : 0.4;
        };
        final java.util.function.Supplier<Game> board = () -> {
            final Game game = initAndCreateGame();
            final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
            for (String n : new String[] {"Thassa's Oracle", "Force of Will", "Island", "Lotus Petal", "Brainstorm",
                    "Craw Wurm", "Swamp", "Grizzly Bears", "Ponder"}) {
                addCardToZone(n, a, ZoneType.Library);
            }
            for (int i = 0; i < 20; i++) addCardToZone("Mountain", b, ZoneType.Library);
            for (int i = 0; i < 4; i++) addCard("Swamp", a);
            addCard("Island", a);
            a.addLandPlayedThisTurn(); // the land drop is spent: the blue source tutored now is next turn's
            addCardToZone("Demonic Tutor", a, ZoneType.Hand);
            addCardToZone("Doomsday", a, ZoneType.Hand);
            addCardToZone("Gush", a, ZoneType.Hand);
            game.getAction().checkStateEffects(true);
            return game;
        };
        final Game g0 = board.get();
        seat(g0);
        castAndResolve(g0, "Demonic Tutor");
        final List<String> plain = names(g0.getPlayers().get(0), ZoneType.Hand);
        AssertJUnit.assertFalse("Forge AI's own pick must not be the Island for this test to mean anything: " + plain,
                plain.contains("Island"));

        final Game g = board.get();
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub(killAvailable);
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        castAndResolve(g, "Demonic Tutor");
        AssertJUnit.assertTrue(String.valueOf(names(g.getPlayers().get(0), ZoneType.Hand)) + " " + cac.log,
                inZone(g.getPlayers().get(0), ZoneType.Hand, "Island"));
        AssertJUnit.assertEquals(1, stub.calls);
        AssertJUnit.assertEquals(0.8, cac.lastScores[cac.lastPick], 1e-9);
        AssertJUnit.assertEquals(0.4, cac.lastScores[0], 1e-9);
    }

    // ------------------------------------------------------------------------------------------------ DIG, ORDER, SCRY, DISCARD

    @Test
    public void brainstormPutBackFollowsThePlanAndTheLibraryKnowsIt() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 5; i++) addCardToZone("Forest", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Mountain", g.getPlayers().get(1), ZoneType.Library);
        addCard("Island", a);
        addCardToZone("Brainstorm", a, ZoneType.Hand);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        addCardToZone("Island", a, ZoneType.Hand);
        addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        g.getAction().checkStateEffects(true);
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub((x, p) -> {
            final List<String> lib = names(p, ZoneType.Library);
            return (lib.get(0).equals("Lightning Bolt") ? 0.6 : 0.0) + (lib.get(1).equals("Island") ? 0.3 : 0.0);
        });
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        castAndResolve(g, "Brainstorm");
        final List<String> lib = names(a, ZoneType.Library);
        AssertJUnit.assertEquals(String.valueOf(cac.log), "Lightning Bolt", lib.get(0));
        AssertJUnit.assertEquals("Island", lib.get(1));
        AssertJUnit.assertEquals("DIG", cac.log.get(0).getAsJsonObject().get("kind").getAsString());
        // ordered pairs of distinct-name hand cards: {Bolt, Island, Bears, Forest}: 4*3 + (Forest, Forest) = 13, + Forge's
        AssertJUnit.assertEquals(13, cac.log.get(0).getAsJsonObject().get("n_enum").getAsInt());
        AssertJUnit.assertEquals(1, stub.calls);
        // N5b: the put-back cards are known positions (a shuffle forgets them)
        final Set<Integer> known = cac.memory().known(a);
        AssertJUnit.assertTrue(known.contains(a.getCardsIn(ZoneType.Library).get(0).getId()));
        AssertJUnit.assertTrue(known.contains(a.getCardsIn(ZoneType.Library).get(1).getId()));
        a.shuffle(null);
        AssertJUnit.assertTrue(cac.memory().known(a).isEmpty());
    }

    private Game libraryBoard(final String spell, final String land, final String... topFirst) {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        final List<Card> lib = new ArrayList<>();
        for (String n : topFirst) lib.add(createCard(n, a));
        a.getZone(ZoneType.Library).setCards(lib);
        for (int i = 0; i < 10; i++) addCardToZone("Mountain", g.getPlayers().get(1), ZoneType.Library);
        addCard(land, a);
        addCardToZone(spell, a, ZoneType.Hand);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(topFirst[0], a.getCardsIn(ZoneType.Library).get(0).getName());
        return g;
    }

    @Test
    public void ponderOrdersTheBoltOnTopAndDoesNotShuffle() {
        final Game g = libraryBoard("Ponder", "Island", "Mountain", "Forest", "Lightning Bolt", "Swamp", "Swamp", "Swamp");
        final Player a = g.getPlayers().get(0);
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub((x, p) -> inZone(p, ZoneType.Hand, "Lightning Bolt") ? 0.9 : 0.2);
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        castAndResolve(g, "Ponder");
        AssertJUnit.assertTrue(String.valueOf(cac.log), inZone(a, ZoneType.Hand, "Lightning Bolt"));
        final JsonObject e = cac.log.get(0).getAsJsonObject();
        AssertJUnit.assertEquals("ORDER", e.get("kind").getAsString());
        // Forge's, 6 orders, the shuffle
        AssertJUnit.assertEquals(8, e.getAsJsonArray("cands").size());
        AssertJUnit.assertEquals(1, stub.calls);
        // the two cards left on top are in the order the plan chose and known; no shuffle happened
        AssertJUnit.assertEquals(0, cac.memory().shuffles);
    }

    @Test
    public void preordainScriesTheForestAwayAndDrawsTheBolt() {
        final Game g = libraryBoard("Preordain", "Island", "Forest", "Lightning Bolt", "Swamp", "Swamp", "Swamp");
        final Player a = g.getPlayers().get(0);
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub((x, p) -> (inZone(p, ZoneType.Hand, "Lightning Bolt") ? 0.9 : 0.2)
                + (names(p, ZoneType.Library).get(0).equals("Forest") ? 0.0 : 0.05));
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        castAndResolve(g, "Preordain");
        AssertJUnit.assertTrue(String.valueOf(cac.log), inZone(a, ZoneType.Hand, "Lightning Bolt"));
        AssertJUnit.assertEquals("SCRY", cac.log.get(0).getAsJsonObject().get("kind").getAsString());
        AssertJUnit.assertEquals("Forest", names(a, ZoneType.Library).get(names(a, ZoneType.Library).size() - 1));
    }

    @Test
    public void faithlessLootingDiscardsTheReanimationTarget() {
        final Game g = libraryBoard("Faithless Looting", "Mountain", "Forest", "Forest", "Forest", "Forest");
        final Player a = g.getPlayers().get(0);
        addCardToZone("Griselbrand", a, ZoneType.Hand);
        addCardToZone("Island", a, ZoneType.Hand);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub((x, p) -> (inZone(p, ZoneType.Graveyard, "Griselbrand") ? 0.7 : 0.0)
                + (inZone(p, ZoneType.Hand, "Lightning Bolt") ? 0.2 : 0.0));
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        castAndResolve(g, "Faithless Looting");
        AssertJUnit.assertTrue(String.valueOf(cac.log), inZone(a, ZoneType.Graveyard, "Griselbrand"));
        AssertJUnit.assertTrue(inZone(a, ZoneType.Hand, "Lightning Bolt"));
        AssertJUnit.assertEquals("DISCARD", cac.log.get(0).getAsJsonObject().get("kind").getAsString());
    }

    // ------------------------------------------------------------------------------------------------ LAND, MULLIGAN

    @Test
    public void landDropAndMulliganBottomAreScored() {
        final Game g = libraryBoard("Lightning Bolt", "Mountain", "Forest", "Forest", "Forest");
        final Player a = g.getPlayers().get(0);
        addCardToZone("Swamp", a, ZoneType.Hand);
        addCardToZone("Island", a, ZoneType.Hand);
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub((x, p) -> (inZone(p, ZoneType.Battlefield, "Island") ? 0.6 : 0.1)
                + (inZone(p, ZoneType.Hand, "Lightning Bolt") ? 0.3 : 0.0));
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        SpellAbility swamp = null;
        for (SpellAbility s : find(a, ZoneType.Hand, "Swamp").getAllPossibleAbilities(a, true)) {
            if (s.isLandAbility()) swamp = s;
        }
        AssertJUnit.assertNotNull(swamp);
        final List<SpellAbility> got = cac.priority(c, Collections.singletonList(swamp));
        AssertJUnit.assertEquals("Island", got.get(0).getHostCard().getName());
        AssertJUnit.assertEquals("LAND", cac.log.get(0).getAsJsonObject().get("kind").getAsString());

        final CardCollection hand = new CardCollection(a.getCardsIn(ZoneType.Hand));
        final CardCollection forgeBottom = new CardCollection(find(a, ZoneType.Hand, "Lightning Bolt"));
        final forge.game.card.CardCollectionView tucked = cac.tuck(c, hand, 1, forgeBottom);
        AssertJUnit.assertEquals(1, tucked.size());
        AssertJUnit.assertFalse(tucked.get(0).getName().equals("Lightning Bolt"));
        AssertJUnit.assertEquals("MULLIGAN", cac.log.get(1).getAsJsonObject().get("kind").getAsString());
        // the live game did not move
        AssertJUnit.assertTrue(inZone(a, ZoneType.Hand, "Swamp"));
        AssertJUnit.assertTrue(inZone(a, ZoneType.Hand, "Lightning Bolt"));
    }

    // ------------------------------------------------------------------------------------------------ PILE

    @Test
    public void doomsdayPileByTheRouteGuidedBeam() {
        final Game g = libraryBoard("Doomsday", "Swamp", "Craw Wurm", "Gush", "Grizzly Bears", "Thassa's Oracle", "Island",
                "Force of Will", "Lotus Petal", "Brainstorm", "Hill Giant");
        final Player a = g.getPlayers().get(0);
        addCard("Swamp", a);
        addCard("Swamp", a);
        final PlayerControllerAi c = seat(g);
        final Stub stub = new Stub((x, p) -> {
            final List<String> lib = names(p, ZoneType.Library);
            if (lib.size() != 5) return 0.0;
            return (lib.get(0).equals("Gush") ? 0.5 : 0) + (lib.get(1).equals("Thassa's Oracle") ? 0.3 : 0)
                    + (lib.contains("Force of Will") ? 0.1 : 0);
        });
        final ChooseACard cac = new ChooseACard(cfg("on"), stub, null);
        cac.install(c);
        castAndResolve(g, "Doomsday");
        final List<String> lib = names(a, ZoneType.Library);
        AssertJUnit.assertEquals(String.valueOf(cac.log) + cac.counts, 5, lib.size());
        AssertJUnit.assertEquals(String.valueOf(cac.log), "Gush", lib.get(0));
        AssertJUnit.assertEquals(lib + " " + cac.counts + " " + cac.log, "Thassa's Oracle", lib.get(1));
        AssertJUnit.assertTrue(lib.contains("Force of Will"));
        final JsonObject e = cac.log.get(cac.log.size() - 1).getAsJsonObject();
        AssertJUnit.assertEquals("PILE", e.get("kind").getAsString());
        AssertJUnit.assertTrue(e.has("beam_evaluated"));
        // the beam is bounded by counts: proposals + 5 depths x width x branch
        AssertJUnit.assertTrue(e.get("beam_evaluated").getAsInt() <= 6 + 5 * 6 * 10);
        AssertJUnit.assertTrue("one batched pass per beam depth, plus the final", stub.calls <= 1 + 5 + 1);
    }

    // ------------------------------------------------------------------------------------------------ N5b

    @Test
    public void keepKnownRestoresKnownPositionsOnly() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0);
        for (String n : new String[] {"Lightning Bolt", "Forest", "Island", "Swamp", "Mountain", "Plains", "Hill Giant"}) {
            addCardToZone(n, a, ZoneType.Library);
        }
        final List<Integer> before = OwnLibraryMemory.order(a);
        final Set<Integer> known = new HashSet<>();
        known.add(before.get(0));
        known.add(before.get(before.size() - 1)); // a bottomed card
        final Set<List<Integer>> seen = new HashSet<>();
        for (int s = 0; s < 8; s++) {
            OwnLibraryMemory.shuffleUnknown(a, known, new Random(s));
            final List<Integer> now = OwnLibraryMemory.order(a);
            AssertJUnit.assertEquals(before.get(0), now.get(0));
            AssertJUnit.assertEquals(before.get(before.size() - 1), now.get(now.size() - 1));
            AssertJUnit.assertEquals(new HashSet<>(before), new HashSet<>(now));
            seen.add(now);
        }
        AssertJUnit.assertTrue("the unknown slots do shuffle", seen.size() > 1);
    }

    // ------------------------------------------------------------------------------------------------ costed draws

    /**
     * The One Ring with one burden counter and one out left (Lightning Bolt, 1 of 10 unknown cards; the opponent at 3).
     * Activating draws two: the exact expectation over the next two cards weights the Bolt by exactly 2/10.
     */
    private Game ringBoard(final int myLife, final boolean redMana, final boolean oppAttacker) {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        addCardToZone("Lightning Bolt", a, ZoneType.Library);
        for (int i = 0; i < 9; i++) addCardToZone("Forest", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Plains", b, ZoneType.Library);
        final Card ring = addCard("The One Ring", a);
        ring.addCounterInternal(CounterEnumType.BURDEN, 1, a, false, null, null);
        if (redMana) addCard("Mountain", a);
        if (oppAttacker) addCard("Grizzly Bears", b).setSickness(false);
        a.setLife(myLife, null);
        b.setLife(3, null);
        g.getAction().checkStateEffects(true);
        return g;
    }

    private static DrawExpectation.Result ring(final Game g, final double flat) {
        final Player a = g.getPlayers().get(0);
        final Card ring = find(a, ZoneType.Battlefield, "The One Ring");
        int idx = -1, i = 0;
        for (SpellAbility s : ring.getSpellAbilities()) {
            if (s.isActivatedAbility() && idx < 0) idx = i;
            i++;
        }
        AssertJUnit.assertTrue(idx >= 0);
        final List<DrawExpectation.Action> acts = new ArrayList<>();
        acts.add(DrawExpectation.pass(ring.getId()));
        acts.add(DrawExpectation.activate(ring.getId(), idx, "ring"));
        final String fp = LookaheadSearch.fingerprint(g);
        final DrawExpectation.Result r = new DrawExpectation(new DrawExpectation.Config()).evaluate(g, a, acts,
                Collections.emptySet(), null, new Stub((x, p) -> flat));
        AssertJUnit.assertEquals("the live game is never moved", fp, LookaheadSearch.fingerprint(g));
        AssertJUnit.assertTrue(r.exact);
        AssertJUnit.assertEquals(1, r.valueCalls);
        double boltWeight = 0;
        for (int s = 0; s < r.sequences.size(); s++) {
            if (r.sequences.get(s).contains("Lightning Bolt")) boltWeight += r.weights[s];
        }
        AssertJUnit.assertEquals("one out in ten, two draws: exactly 2/10", 0.2, boltWeight, 1e-12);
        AssertJUnit.assertEquals(3, r.sequences.size()); // (Bolt, Forest), (Forest, Bolt), (Forest, Forest)
        return r;
    }

    @Test
    public void oneRingActivationWinsWhenTheOutWins() {
        final DrawExpectation.Result r = ring(ringBoard(20, true, false), 0.3);
        AssertJUnit.assertTrue(r.log.toString(), r.ev[1] > r.ev[0]);
        AssertJUnit.assertEquals(0.3, r.ev[0], 1e-9);
    }

    @Test
    public void oneRingActivationLosesWhenTheBurdenLosesToTheClock() {
        // 4 life, a 2/2 attacking, no red mana: activating (burden 2) dies at our upkeep; passing (burden 1) survives
        final DrawExpectation.Result r = ring(ringBoard(4, false, true), 0.3);
        AssertJUnit.assertTrue(r.log.toString(), r.ev[1] < r.ev[0]);
    }

    /** N5b opt-in: HonestLabel's known-library overload with no known cards is the plain call; with some, they stay put. */
    @Test
    public void honestLabelKnownOwnOverloadDefaultsToThePlainPath() throws Exception {
        final Game live = initAndCreateGame();
        final Player me = live.getPlayers().get(0), opp = live.getPlayers().get(1);
        live.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (String n : new String[] {"Island", "Mountain", "Forest", "Plains", "Swamp", "Shock", "Hill Giant", "Craw Wurm"}) {
            addCardToZone(n, me, ZoneType.Library);
        }
        addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        addCard("Mountain", me);
        for (int i = 0; i < 12; i++) addCardToZone("Raging Goblin", opp, ZoneType.Library);
        opp.setLife(3, null);
        final com.google.gson.JsonArray cards = new com.google.gson.JsonArray();
        for (String name : new String[] {"Counterspell", "Giant Growth", "Hill Giant"}) {
            final JsonObject card = new JsonObject();
            card.addProperty("name", name);
            card.add("tags", new com.google.gson.JsonArray());
            cards.add(card);
        }
        final JsonObject cube = new JsonObject();
        cube.addProperty("schema", "mtgx-belief-cube/1");
        cube.add("cards", cards);
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("cac-cube-", ".json");
        java.nio.file.Files.writeString(file, cube.toString());
        final java.util.function.Supplier<HonestLabel.Seat> seat = () -> {
            final HonestLabel.Spec spec = new HonestLabel.Spec();
            spec.redraw = "belief";
            spec.worlds = 2;
            spec.solverCfg.addProperty("hideOwnLibrary", "true");
            spec.lossCfg.addProperty("hideOwnLibrary", "true");
            spec.beliefCube = file.toString();
            spec.beliefCubeSha256 = null;
            spec.beliefBasics = 2;
            return new HonestLabel.Seat(spec);
        };
        final String fp = LookaheadSearch.fingerprint(live);
        forge.util.IdScope.open();
        final JsonObject plain = seat.get().label(live, me, 7L).toJson();
        final JsonObject none = seat.get().label(live, me, 7L, null).toJson();
        final JsonObject empty = seat.get().label(live, me, 7L, new HashSet<>()).toJson();
        plain.remove("ms");
        none.remove("ms");
        empty.remove("ms");
        AssertJUnit.assertEquals(plain.toString(), none.toString());
        AssertJUnit.assertEquals(plain.toString(), empty.toString());
        AssertJUnit.assertEquals(fp, LookaheadSearch.fingerprint(live));
        // with a known card the root world keeps it at its index (private world(), as HonestLabelWorldTest reads it)
        final HonestLabel.Seat s = seat.get();
        final java.lang.reflect.Field kf = HonestLabel.Seat.class.getDeclaredField("knownOwn");
        kf.setAccessible(true);
        final Card second = me.getCardsIn(ZoneType.Library).get(1);
        kf.set(s, java.util.Set.of(second.getId()));
        final java.lang.reflect.Method world = HonestLabel.Seat.class.getDeclaredMethod("world", Game.class, Player.class,
                Player.class, Random.class);
        world.setAccessible(true);
        for (int seed = 0; seed < 6; seed++) {
            final Object[] w = (Object[]) world.invoke(s, live, me, opp, new Random(seed));
            AssertJUnit.assertEquals(second.getId(), ((Player) w[1]).getCardsIn(ZoneType.Library).get(1).getId());
        }
    }
}
