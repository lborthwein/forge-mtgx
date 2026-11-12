package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * r3-inline-choices-1030 (FLAG-R3-5): the choose-a-card kinds for choices Forge AI made inside our lines - PAYMENT,
 * COLOR, COST_CARDS, LIFE, SACRIFICE, ORDER_TRIGGERS, PAY_TO_PREVENT. Each test plays the position twice: plain Forge AI
 * (no hook), whose answer must LOSE under the value, and the hooked seat (mode on), whose searched answer must WIN.
 * The value is a stub function of the copy's state (as in ChooseACardTest), except the trigger-order test, whose win
 * is a real terminal (Thassa's Oracle).
 */
public class InlineChoicesTest extends SimulationTest {

    static ChooseACard.Config on(final String kinds) {
        final JsonObject o = new JsonObject();
        o.addProperty("mode", "on");
        o.addProperty("topM", 0);
        o.addProperty("kinds", kinds);
        final ChooseACard.Config c = ChooseACard.Config.fromJson(o);
        c.testUnsafe = true; // stub values, no solver check, no belief re-draw: tests only
        return c;
    }

    static PlayerControllerAi seat(final Game game, final int i) {
        final Player a = game.getPlayers().get(i);
        final PlayerControllerAi c = new PlayerControllerAi(game, a, a.getController().getLobbyPlayer());
        a.dangerouslySetController(c);
        return c;
    }

    static ChooseACard hook(final PlayerControllerAi c, final String kinds, final BiFunction<Game, Player, Double> v) {
        final ChooseACard cac = new ChooseACard(on(kinds), new ChooseACardTest.Stub(v), null);
        cac.install(c);
        return cac;
    }

    static Card find(final Player p, final ZoneType z, final String name) {
        return ChooseACardTest.find(p, z, name);
    }

    static boolean untapped(final Player p, final String name) {
        for (Card k : p.getCardsIn(ZoneType.Battlefield)) {
            if (k.getName().equals(name) && !k.isTapped()) return true;
        }
        return false;
    }

    static boolean inHand(final Player p, final String name) {
        return ChooseACardTest.inZone(p, ZoneType.Hand, name);
    }

    /** Play what the seat's hooked priority returns (floats first, then the cast), as the game loop does. */
    static void playAll(final PlayerControllerAi c, final List<SpellAbility> chosen) {
        for (SpellAbility s : chosen) {
            c.playChosenSpellAbility(s);
        }
    }

    static void resolveAll(final Game g) {
        int guard = 0;
        while (!g.getStack().isEmpty() && guard++ < 12) {
            g.getStack().resolveStack();
            g.getAction().checkStateEffects(true);
            g.getAction().checkGameOverCondition();
            if (g.isGameOver()) return;
            g.getStack().addAllTriggeredAbilitiesToStack();
        }
    }

    static SpellAbility spell(final Player p, final String name) {
        final SpellAbility sa = find(p, ZoneType.Hand, name).getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        return sa;
    }

    static String kindOf(final ChooseACard cac, final int i) {
        return cac.log.get(i).getAsJsonObject().get("kind").getAsString();
    }

    // ------------------------------------------------------------------------------------------------ PAYMENT

    /**
     * Think Twice ({1}{U}) with Island, Mountain, Tundra and a Bolt for an opponent at 3: Forge AI pays the generic with
     * the least flexible source, the Mountain, and strands the Bolt. The search keeps the Mountain (taps the Tundra).
     */
    private Game paymentBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        addCard("Island", a);
        addCard("Mountain", a);
        addCard("Tundra", a);
        addCardToZone("Think Twice", a, ZoneType.Hand);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        b.setLife(3, null);
        g.getAction().checkStateEffects(true);
        return g;
    }

    static final BiFunction<Game, Player, Double> BOLT_READY = (g, p) -> untapped(p, "Mountain") && inHand(p, "Lightning Bolt")
            ? 0.9 : 0.1;

    @Test
    public void paymentKeepsTheMountainForTheBolt() {
        final Game g0 = paymentBoard();
        final PlayerControllerAi c0 = seat(g0, 0);
        c0.playChosenSpellAbility(spell(g0.getPlayers().get(0), "Think Twice"));
        AssertJUnit.assertFalse("precondition: Forge AI's own payment taps the Mountain", untapped(g0.getPlayers().get(0), "Mountain"));

        final Game g = paymentBoard();
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "PAYMENT", BOLT_READY);
        final Player a = g.getPlayers().get(0);
        final SpellAbility tt = spell(a, "Think Twice");
        playAll(c, cac.priority(c, Collections.singletonList(tt)));
        AssertJUnit.assertTrue(cac.log + " " + cac.counts, untapped(a, "Mountain"));
        AssertJUnit.assertTrue(cac.log + " " + cac.counts, find(a, ZoneType.Battlefield, "Tundra").isTapped());
        AssertJUnit.assertEquals(1, g.getStack().size());
        AssertJUnit.assertEquals("PAYMENT", kindOf(cac, 0));
        AssertJUnit.assertEquals("departed", cac.log.get(0).getAsJsonObject().get("outcome").getAsString());
    }

    @Test
    public void offAndShadowAreByteIdenticalToNoHook() {
        final Game g0 = paymentBoard();
        final PlayerControllerAi c0 = seat(g0, 0);
        MyRandom.setRandom(new java.util.Random(7));
        c0.playChosenSpellAbility(spell(g0.getPlayers().get(0), "Think Twice"));
        resolveAll(g0);
        final String fp0 = LookaheadSearch.fingerprint(g0);
        final long rng0 = MyRandom.getRandom().nextLong();
        final int id0 = new Card(g0.nextCardId(), g0).getId();

        final String all = "PAYMENT,COLOR,COST_CARDS,LIFE,SACRIFICE,ORDER_TRIGGERS,PAY_TO_PREVENT";
        for (String mode : new String[] {"off", "shadow"}) {
            final Game g = paymentBoard();
            final PlayerControllerAi c = seat(g, 0);
            final JsonObject o = new JsonObject();
            o.addProperty("mode", mode);
            o.addProperty("topM", 0);
            o.addProperty("kinds", all);
            final ChooseACard.Config cfg = ChooseACard.Config.fromJson(o);
            cfg.testUnsafe = true;
            final ChooseACard cac = new ChooseACard(cfg, new ChooseACardTest.Stub(BOLT_READY), null);
            cac.install(c);
            MyRandom.setRandom(new java.util.Random(7));
            final SpellAbility tt = spell(g.getPlayers().get(0), "Think Twice");
            final List<SpellAbility> chosen = c.getCacHook() == null ? Collections.singletonList(tt)
                    : cac.priority(c, Collections.singletonList(tt));
            AssertJUnit.assertEquals(mode, Collections.singletonList(tt), chosen);
            playAll(c, chosen);
            resolveAll(g);
            AssertJUnit.assertEquals(mode, fp0, LookaheadSearch.fingerprint(g));
            AssertJUnit.assertEquals(mode, rng0, MyRandom.getRandom().nextLong());
            AssertJUnit.assertEquals(mode, id0, new Card(g.nextCardId(), g).getId());
            if (mode.equals("shadow")) {
                AssertJUnit.assertTrue(cac.log + " " + cac.counts, cac.log.toString().contains("shadow-would-depart"));
            } else {
                AssertJUnit.assertEquals(0, cac.log.size());
            }
        }
        // an old config (no new kinds) prints exactly as before: no payCap / orderCap keys, the #143 kinds
        final ChooseACard.Config old = ChooseACard.Config.fromJson(com.google.gson.JsonParser.parseString("{\"mode\":\"shadow\"}")
                .getAsJsonObject());
        AssertJUnit.assertFalse(old.toJson().has("payCap"));
        AssertJUnit.assertEquals("SEARCH,MULTI,PILE,DIG,DIGPICK,DISCARD,SCRY,SURVEIL,ORDER,MULLIGAN,LAND,RETURN_COST",
                old.toJson().get("kinds").getAsString());
    }

    @Test
    public void sameSeedSameDecision() {
        final List<String> first = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            final Game g = paymentBoard();
            final PlayerControllerAi c = seat(g, 0);
            final ChooseACard cac = hook(c, "PAYMENT,COLOR", BOLT_READY);
            cac.priority(c, Collections.singletonList(spell(g.getPlayers().get(0), "Think Twice")));
            final JsonObject e = cac.log.get(0).getAsJsonObject().deepCopy();
            e.remove("ms");
            if (r == 0) first.add(e.toString());
            else AssertJUnit.assertEquals(first.get(0), e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------ COLOR

    /** Lotus Petal cracked alone: Forge AI names its hand's most prominent colour (blue); the Bolt needs red. */
    private Game petalBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        addCard("Lotus Petal", a);
        for (String n : new String[] {"Lightning Bolt", "Brainstorm", "Ponder", "Counterspell"}) addCardToZone(n, a, ZoneType.Hand);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        b.setLife(3, null);
        return g;
    }

    static SpellAbility petal(final Player a) {
        final SpellAbility m = find(a, ZoneType.Battlefield, "Lotus Petal").getManaAbilities().get(0);
        m.setActivatingPlayer(a);
        return m;
    }

    @Test
    public void colourOfAnyColourManaIsSearched() {
        final Game g0 = petalBoard();
        final PlayerControllerAi c0 = seat(g0, 0);
        final Player a0 = g0.getPlayers().get(0);
        c0.playChosenSpellAbility(petal(a0));
        AssertJUnit.assertEquals("precondition: Forge AI does not float red", 0, a0.getManaPool().getAmountOfColor(MagicColor.RED));

        final Game g = petalBoard();
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "COLOR", (x, p) -> p.getManaPool().getAmountOfColor(MagicColor.RED) > 0 ? 0.9 : 0.1);
        final Player a = g.getPlayers().get(0);
        playAll(c, cac.priority(c, Collections.singletonList(petal(a))));
        AssertJUnit.assertEquals(cac.log + " " + cac.counts, 1, a.getManaPool().getAmountOfColor(MagicColor.RED));
        AssertJUnit.assertEquals("COLOR", kindOf(cac, 0));
    }

    // ------------------------------------------------------------------------------------------------ LIFE

    /** Gitaxian Probe ({U/P}) with one Island and Brainstorm in hand: Forge AI pays the Island; the search pays 2 life. */
    private Game probeBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        addCard("Island", a);
        addCardToZone("Gitaxian Probe", a, ZoneType.Hand);
        addCardToZone("Brainstorm", a, ZoneType.Hand);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        addCardToZone("Grizzly Bears", b, ZoneType.Hand);
        return g;
    }

    static SpellAbility probe(final Game g) {
        final Player a = g.getPlayers().get(0);
        final SpellAbility sa = spell(a, "Gitaxian Probe");
        sa.getTargets().add(g.getPlayers().get(1));
        return sa;
    }

    @Test
    public void phyrexianPipPaidWithLifeKeepsTheIsland() {
        final Game g0 = probeBoard();
        final PlayerControllerAi c0 = seat(g0, 0);
        c0.playChosenSpellAbility(probe(g0));
        AssertJUnit.assertFalse("precondition: Forge AI pays the pip with the Island", untapped(g0.getPlayers().get(0), "Island"));

        final Game g = probeBoard();
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "LIFE", (x, p) -> untapped(p, "Island") && inHand(p, "Brainstorm") ? 0.9 : 0.1);
        final Player a = g.getPlayers().get(0);
        playAll(c, cac.priority(c, Collections.singletonList(probe(g))));
        AssertJUnit.assertTrue(cac.log + " " + cac.counts, untapped(a, "Island"));
        AssertJUnit.assertEquals(18, a.getLife());
        AssertJUnit.assertEquals(1, g.getStack().size());
        AssertJUnit.assertEquals("LIFE", kindOf(cac, 0));
    }

    // ------------------------------------------------------------------------------------------------ COST_CARDS

    /**
     * Force of Will by its alternative cost against the opponent's Divination, with Brainstorm and Thassa's Oracle as
     * blue cards: the value says the card Forge AI pitches is the one the line needs; the search pitches the other.
     */
    private Game fowBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, b);
        for (String n : new String[] {"Force of Will", "Brainstorm", "Thassa's Oracle"}) addCardToZone(n, a, ZoneType.Hand);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        for (int i = 0; i < 3; i++) addCard("Island", b);
        addCardToZone("Divination", b, ZoneType.Hand);
        final PlayerControllerAi cb = seat(g, 1);
        cb.playChosenSpellAbility(spell(b, "Divination"));
        AssertJUnit.assertEquals(1, g.getStack().size());
        return g;
    }

    static SpellAbility fowAlt(final Game g) {
        final Player a = g.getPlayers().get(0);
        for (SpellAbility s : find(a, ZoneType.Hand, "Force of Will").getAllPossibleAbilities(a, true)) {
            if (s.getPayCosts() != null && s.getPayCosts().getCostParts().stream()
                    .anyMatch(p -> p instanceof forge.game.cost.CostExile)) {
                s.setActivatingPlayer(a);
                s.getTargets().add(g.getStack().peekAbility());
                return s;
            }
        }
        throw new AssertionError("no alternative cost");
    }

    @Test
    public void pitchKeepsTheCardTheLineNeeds() {
        final Game g0 = fowBoard();
        final PlayerControllerAi c0 = seat(g0, 0);
        final Player a0 = g0.getPlayers().get(0);
        c0.playChosenSpellAbility(fowAlt(g0));
        final String pitched = inHand(a0, "Brainstorm") ? "Thassa's Oracle" : "Brainstorm";
        AssertJUnit.assertFalse("precondition: Forge AI pitched one of them", inHand(a0, pitched));

        final Game g = fowBoard();
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "COST_CARDS", (x, p) -> inHand(p, pitched) ? 0.9 : 0.1);
        final Player a = g.getPlayers().get(0);
        playAll(c, cac.priority(c, Collections.singletonList(fowAlt(g))));
        AssertJUnit.assertTrue(cac.log + " " + cac.counts, inHand(a, pitched));
        AssertJUnit.assertEquals("COST_CARDS", kindOf(cac, 0));
        AssertJUnit.assertEquals(2, g.getStack().size());
    }

    // ------------------------------------------------------------------------------------------------ SACRIFICE

    /** Diabolic Edict on us with Llanowar Elves and Craw Wurm: Forge AI sacrifices the Elves, the combo's mana. */
    private Game edictBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, b);
        addCard("Llanowar Elves", a);
        addCard("Craw Wurm", a);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        for (int i = 0; i < 2; i++) addCard("Swamp", b);
        addCardToZone("Diabolic Edict", b, ZoneType.Hand);
        final PlayerControllerAi cb = seat(g, 1);
        final SpellAbility e = spell(b, "Diabolic Edict");
        e.getTargets().add(a);
        cb.playChosenSpellAbility(e);
        AssertJUnit.assertEquals(1, g.getStack().size());
        return g;
    }

    @Test
    public void edictKeepsTheElves() {
        final Game g0 = edictBoard();
        seat(g0, 0);
        resolveAll(g0);
        AssertJUnit.assertFalse("precondition: Forge AI sacrifices the Elves",
                ChooseACardTest.inZone(g0.getPlayers().get(0), ZoneType.Battlefield, "Llanowar Elves"));

        final Game g = edictBoard();
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "SACRIFICE",
                (x, p) -> ChooseACardTest.inZone(p, ZoneType.Battlefield, "Llanowar Elves") ? 0.9 : 0.1);
        resolveAll(g);
        final Player a = g.getPlayers().get(0);
        AssertJUnit.assertTrue(cac.log + " " + cac.counts, ChooseACardTest.inZone(a, ZoneType.Battlefield, "Llanowar Elves"));
        AssertJUnit.assertFalse(ChooseACardTest.inZone(a, ZoneType.Battlefield, "Craw Wurm"));
        AssertJUnit.assertEquals("SACRIFICE", kindOf(cac, 0));
    }

    /** The legend rule: Forge AI keeps the tapped copy with a counter; the value wants the untapped one. */
    private Game legendBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        final Card t = addCard("Isamaru, Hound of Konda", a);
        t.setTapped(true);
        t.addCounterInternal(CounterEnumType.P1P1, 2, a, false, null, null);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        return g;
    }

    @Test
    public void legendRuleKeepsTheUntappedCopy() {
        final Game g0 = legendBoard();
        seat(g0, 0);
        final Player a0 = g0.getPlayers().get(0);
        addCard("Isamaru, Hound of Konda", a0).setSickness(false);
        g0.getAction().checkStateEffects(true);
        AssertJUnit.assertFalse("precondition: Forge AI keeps the tapped copy", untapped(a0, "Isamaru, Hound of Konda"));

        final Game g = legendBoard();
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "SACRIFICE", (x, p) -> untapped(p, "Isamaru, Hound of Konda") ? 0.9 : 0.1);
        final Player a = g.getPlayers().get(0);
        addCard("Isamaru, Hound of Konda", a).setSickness(false);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(cac.log + " " + cac.counts, untapped(a, "Isamaru, Hound of Konda"));
        AssertJUnit.assertEquals("SACRIFICE", kindOf(cac, 0));
    }

    // ------------------------------------------------------------------------------------------------ ORDER_TRIGGERS

    /**
     * Thassa's Oracle enters with Guardian Project out and an EMPTY library: if Guardian Project's draw resolves first
     * we lose to the empty-library draw at the next state-based check; the Oracle's trigger must resolve first. A real
     * terminal win (and loss), no stub needed.
     */
    private Game oracleBoard() {
        return oracleBoard(0);
    }

    private Game oracleBoard(final int library) {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        addCard("Guardian Project", a);
        addCard("Island", a);
        addCard("Island", a);
        addCardToZone("Thassa's Oracle", a, ZoneType.Hand);
        for (int i = 0; i < library; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        return g;
    }

    @Test
    public void triggerOrderLetsTheOracleWin() {
        // Forge AI's order is a fixed function of the abilities' APIs (orderPlaySa: draws resolve first), so it loses on
        // one of the two libraries: with 0 cards the Oracle must resolve first (Guardian Project's draw from the empty
        // library loses at the next state-based check), with 3 the draw must (devotion 2 then sees 2 cards). Both games
        // are played by the real game loop to the end of the turn. (A player who lost leaves getPlayers(): the seat is
        // read from getRegisteredPlayers.)
        int lib = -1;
        final StringBuilder seen = new StringBuilder();
        for (int n : new int[] {0, 3}) {
            final Game g0 = oracleBoard(n);
            final PlayerControllerAi c0 = seat(g0, 0);
            c0.playChosenSpellAbility(spell(g0.getPlayers().get(0), "Thassa's Oracle"));
            loopTurn(g0);
            seen.append(n).append(':').append(g0.getRegisteredPlayers().get(0).hasWon()).append(' ');
            if (!g0.getRegisteredPlayers().get(0).hasWon() && lib < 0) lib = n;
        }
        AssertJUnit.assertTrue("precondition: Forge AI's order loses on one library " + seen, lib >= 0);

        final Game g = oracleBoard(lib);
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "ORDER_TRIGGERS", (x, p) -> 0.1);
        c.playChosenSpellAbility(spell(g.getPlayers().get(0), "Thassa's Oracle"));
        loopTurn(g);
        AssertJUnit.assertTrue(cac.log + " " + cac.counts + " lib " + lib + " " + seen, g.getRegisteredPlayers().get(0).hasWon());
        AssertJUnit.assertEquals("ORDER_TRIGGERS", kindOf(cac, 0));
        AssertJUnit.assertEquals("departed", cac.log.get(0).getAsJsonObject().get("outcome").getAsString());
    }

    /** The real game loop until the game ends or the turn passes (at most 400 steps). */
    static void loopTurn(final Game g) {
        final int t = g.getPhaseHandler().getTurn();
        for (int i = 0; i < 400 && !g.isGameOver() && g.getPhaseHandler().getTurn() == t; i++) {
            g.getPhaseHandler().mainLoopStep();
        }
    }

    // ------------------------------------------------------------------------------------------------ PAY_TO_PREVENT

    /** Force Spike on our Grizzly Bears with the Mountain left and a Bolt for 3: Forge AI pays; the search declines. */
    private Game spikeBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        addCard("Forest", a);
        addCard("Forest", a);
        addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        addCard("Island", b);
        addCardToZone("Force Spike", b, ZoneType.Hand);
        b.setLife(3, null);
        final PlayerControllerAi ca = seat(g, 0);
        ca.playChosenSpellAbility(spell(a, "Grizzly Bears"));
        addCard("Mountain", a);
        final PlayerControllerAi cb = seat(g, 1);
        final SpellAbility fs = spell(b, "Force Spike");
        fs.getTargets().add(g.getStack().peekAbility());
        cb.playChosenSpellAbility(fs);
        AssertJUnit.assertEquals(2, g.getStack().size());
        return g;
    }

    @Test
    public void payToPreventDeclinesAndKeepsTheBolt() {
        final Game g0 = spikeBoard();
        g0.getStack().resolveStack();
        AssertJUnit.assertFalse("precondition: Forge AI pays Force Spike with the Mountain", untapped(g0.getPlayers().get(0), "Mountain"));

        final Game g = spikeBoard();
        final PlayerControllerAi c = (PlayerControllerAi) g.getPlayers().get(0).getController();
        final ChooseACard cac = hook(c, "PAY_TO_PREVENT", BOLT_READY);
        g.getStack().resolveStack();
        AssertJUnit.assertTrue(cac.log + " " + cac.counts, untapped(g.getPlayers().get(0), "Mountain"));
        AssertJUnit.assertEquals("PAY_TO_PREVENT", kindOf(cac, 0));
    }

    // ------------------------------------------------------------------------------------------------ review #147 S1-1

    interface Play {
        void run(Game g, PlayerControllerAi c, ChooseACard cac);
    }

    /** One SpellAbility id from the counter in force (scoped or global): a probe of where the counter stands. */
    static int saId(final Game g) {
        return new SpellAbility.EmptySa(forge.game.ability.ApiType.InternalLegendaryRule, new Card(-1, g),
                g.getPlayers().get(0)).getId();
    }

    static final String ALL = "PAYMENT,COLOR,COST_CARDS,LIFE,SACRIFICE,ORDER_TRIGGERS,PAY_TO_PREVENT,TARGET";

    /** Play {@code play} with no hook and with a SHADOW hook of every new kind: nothing live may differ. */
    void identity(final String name, final java.util.function.Supplier<Game> board, final Play play) {
        final String[] fp = new String[2];
        final long[] rng = new long[2];
        final int[] sa = new int[2], card = new int[2];
        for (int arm = 0; arm < 2; arm++) {
            final Game g = board.get();
            final Player a = g.getPlayers().get(0);
            final PlayerControllerAi c = a.getController() instanceof PlayerControllerAi pc ? pc : seat(g, 0);
            ChooseACard cac = null;
            if (arm == 1) {
                final JsonObject o = new JsonObject();
                o.addProperty("mode", "shadow");
                o.addProperty("topM", 0);
                o.addProperty("kinds", ALL);
                final ChooseACard.Config cfg = ChooseACard.Config.fromJson(o);
                cfg.testUnsafe = true;
                cac = new ChooseACard(cfg, new ChooseACardTest.Stub((x, p) -> 0.5 + 0.01 * p.getCardsIn(ZoneType.Hand).size()), null);
                cac.install(c);
            }
            MyRandom.setRandom(new java.util.Random(7));
            final int s0 = saId(g), c0 = g.nextCardId();
            play.run(g, c, cac);
            fp[arm] = LookaheadSearch.fingerprint(g);
            rng[arm] = MyRandom.getRandom().nextLong();
            sa[arm] = saId(g) - s0;
            card[arm] = g.nextCardId() - c0;
        }
        AssertJUnit.assertEquals(name + " fingerprint", fp[0], fp[1]);
        AssertJUnit.assertEquals(name + " random stream", rng[0], rng[1]);
        AssertJUnit.assertEquals(name + " SpellAbility ids", sa[0], sa[1]);
        AssertJUnit.assertEquals(name + " card ids", card[0], card[1]);
    }

    static List<SpellAbility> hooked(final PlayerControllerAi c, final ChooseACard cac, final SpellAbility sa) {
        return cac == null ? Collections.singletonList(sa) : cac.priority(c, Collections.singletonList(sa));
    }

    @Test
    public void shadowMovesNoIdsOnAnyKind() {
        identity("payment", this::paymentBoard, (g, c, cac) -> {
            playAll(c, hooked(c, cac, spell(g.getPlayers().get(0), "Think Twice")));
            resolveAll(g);
        });
        identity("pitch", this::fowBoard, (g, c, cac) -> {
            playAll(c, hooked(c, cac, fowAlt(g)));
            resolveAll(g);
        });
        identity("life", this::probeBoard, (g, c, cac) -> {
            playAll(c, hooked(c, cac, probe(g)));
            resolveAll(g);
        });
        identity("colour", this::petalBoard, (g, c, cac) -> playAll(c, hooked(c, cac, petal(g.getPlayers().get(0)))));
        identity("edict", this::edictBoard, (g, c, cac) -> resolveAll(g));
        identity("legend", this::legendBoard, (g, c, cac) -> {
            addCard("Isamaru, Hound of Konda", g.getPlayers().get(0)).setSickness(false);
            g.getAction().checkStateEffects(true);
        });
        identity("order", () -> oracleBoard(0), (g, c, cac) -> {
            c.playChosenSpellAbility(spell(g.getPlayers().get(0), "Thassa's Oracle"));
            loopTurn(g);
        });
        identity("payToPrevent", this::spikeBoard, (g, c, cac) -> g.getStack().resolveStack());
        identity("target", this::freezeBoard, (g, c, cac) -> {
            playAll(c, hooked(c, cac, freeze(g)));
            resolveAll(g);
        });
        identity("triggerTarget", this::witnessBoard, (g, c, cac) -> {
            c.playChosenSpellAbility(spell(g.getPlayers().get(0), "Eternal Witness"));
            resolveAll(g);
        });
    }

    // ------------------------------------------------------------------------------------------------ TARGET (r3-target-choices-1030)

    /**
     * Brain Freeze at storm 1 (Ornithopter cast first) with Thassa's Oracle in hand and 6 cards left: milling ourselves
     * moves toward the Oracle (the value falls with our library). The storm copy settles on the copy (the storm-trigger
     * fix) and chooses its own new target as Forge AI does. Forge AI's own target (canPlaySa, as when it chooses the
     * play) is the opponent.
     */
    private Game freezeBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 4; i++) addCard("Island", a);
        addCardToZone("Ornithopter", a, ZoneType.Hand);
        addCardToZone("Brain Freeze", a, ZoneType.Hand);
        addCardToZone("Thassa's Oracle", a, ZoneType.Hand);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Plains", b, ZoneType.Library);
        final PlayerControllerAi c = seat(g, 0);
        c.playChosenSpellAbility(spell(a, "Ornithopter"));
        resolveAll(g);
        AssertJUnit.assertEquals(1, a.getSpellsCastThisTurn());
        return g;
    }

    /**
     * Brain Freeze with Forge AI's own targets: its play decision (canPlaySa) when it would cast it, else its targeting
     * for the spell as a must-target ability (SpellAbilityAi.doTrigger mandatory), the choice Forge makes when a
     * line casts it.
     */
    static SpellAbility freeze(final Game g) {
        final Player a = g.getPlayers().get(0);
        final SpellAbility sa = spell(a, "Brain Freeze");
        ((PlayerControllerAi) a.getController()).getAi().canPlaySa(sa);
        if (sa.getTargets().isEmpty()) {
            forge.ai.SpellApiToAi.Converter.get(sa).doTrigger(a, sa, true);
        }
        return sa;
    }

    static int ownLibrary(final Game g) {
        return g.getRegisteredPlayers().get(0).getCardsIn(ZoneType.Library).size();
    }

    @Test
    public void brainFreezeOnOurselvesSetsUpTheOracle() {
        final Game g0 = freezeBoard();
        final PlayerControllerAi c0 = (PlayerControllerAi) g0.getPlayers().get(0).getController();
        final SpellAbility f0 = freeze(g0);
        AssertJUnit.assertEquals("precondition: Forge AI targets one player", 1, f0.getTargets().size());
        c0.playChosenSpellAbility(f0);
        resolveAll(g0);
        AssertJUnit.assertEquals("precondition: Forge AI mills the opponent (storm 1: two Brain Freezes)", 6, ownLibrary(g0));

        final Game g = freezeBoard();
        final PlayerControllerAi c = (PlayerControllerAi) g.getPlayers().get(0).getController();
        final ChooseACard cac = hook(c, "TARGET", (x, p) -> inHand(p, "Thassa's Oracle")
                ? 0.9 - 0.1 * p.getCardsIn(ZoneType.Library).size() : 0.0);
        playAll(c, cac.priority(c, Collections.singletonList(freeze(g))));
        resolveAll(g);
        // the spell itself mills us (3); its storm copy's new target stays Forge AI's choice (stated gap in #151),
        // which here is the opponent
        AssertJUnit.assertEquals(cac.log + " " + cac.counts, 3, ownLibrary(g));
        AssertJUnit.assertEquals("TARGET", kindOf(cac, 0));
        AssertJUnit.assertEquals("departed", cac.log.get(0).getAsJsonObject().get("outcome").getAsString());
    }

    /** Eternal Witness with Time Warp and a Forest in the graveyard: the value wants the card Forge AI does not return. */
    private Game witnessBoard() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 3; i++) addCard("Forest", a);
        addCardToZone("Eternal Witness", a, ZoneType.Hand);
        addCardToZone("Time Warp", a, ZoneType.Graveyard);
        addCardToZone("Forest", a, ZoneType.Graveyard);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", a, ZoneType.Library);
        for (int i = 0; i < 10; i++) addCardToZone("Plains", b, ZoneType.Library);
        return g;
    }

    @Test
    public void witnessReturnsTheCardTheLineNeeds() {
        final Game g0 = witnessBoard();
        final PlayerControllerAi c0 = seat(g0, 0);
        c0.playChosenSpellAbility(spell(g0.getPlayers().get(0), "Eternal Witness"));
        resolveAll(g0);
        final Player a0 = g0.getRegisteredPlayers().get(0);
        final String forgeTook = inHand(a0, "Time Warp") ? "Time Warp" : "Forest";
        final String want = forgeTook.equals("Time Warp") ? "Forest" : "Time Warp";
        AssertJUnit.assertTrue("precondition: Forge AI returned one card", inHand(a0, forgeTook));

        final Game g = witnessBoard();
        final PlayerControllerAi c = seat(g, 0);
        final ChooseACard cac = hook(c, "TARGET", (x, p) -> inHand(p, want) ? 0.9 : 0.1);
        c.playChosenSpellAbility(spell(g.getPlayers().get(0), "Eternal Witness"));
        resolveAll(g);
        AssertJUnit.assertTrue(cac.log + " " + cac.counts + " forge took " + forgeTook,
                inHand(g.getRegisteredPlayers().get(0), want));
        AssertJUnit.assertEquals("TARGET", kindOf(cac, 0));
    }
}
