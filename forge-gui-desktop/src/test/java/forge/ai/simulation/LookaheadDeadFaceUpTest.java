package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiFixes;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.bench.rl.RlSearch;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The dead turn-face-up guard (Config.deadFaceUp, lane turn-face-up-timing-1118). The report of 2026-11-18T14:33Z: on turn
 * 16, at the end of its own combat, the policy seat departed from pass to turning its face-down Stratus Dancer face up with
 * Megamorph {1}{U} (0.569 to 0.601, 4 of 8 worlds better, none worse). Its only payoff, "When this is turned face up,
 * counter target instant or sorcery spell", had no target on the empty stack; the 3/2 flyer could not attack or block before
 * the seat's next priority, and the seat gave up countering a spell on the opponent's turn with the same open mana. The
 * class: a turn-up whose turned-face-up triggers all target with no legal target, on a card with nothing else that needs it
 * face up now, when the seat gets priority again before the body could matter and paying now gains nothing, is not a
 * departure candidate.
 */
public class LookaheadDeadFaceUpTest extends SimulationTest {

    /**
     * The reported board (evidence/state.json, rebuilt at turn 16, the end of a's combat), at {@code phase} of {@code aiActive
     * ? a : b}'s turn. a, the policy seat (Hank_247, 8 life): three tapped Swamps (they paid for the face-down cast), four
     * untapped Islands, and {@code faceDown} face down, cast this turn (summoning sick); Crush of Tentacles and Ruinous Path in
     * hand; Nightveil Specter, Silumgar's Command, Rewind, Desecration Demon and Hero's Downfall in the graveyard. b, the human
     * (14 life): Stomping Ground, Cinder Glade, Mountain and two Swamps, no creatures; {@code bHand} in hand; Bloodstained
     * Mire, Brimstone Volley, Xenagos, Kolaghan, Courser of Kruphix, Dreadbore and Elvish Mystic in the graveyard. Libraries:
     * 25 cards each ({@code bLibrary} for b).
     */
    private Game board(PhaseType phase, boolean aiActive, String faceDown, String[] bHand, String bLibrary) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(phase, aiActive ? a : b);
        for (int i = 0; i < 25; i++) {
            addCardToZone(i % 2 == 0 ? "Island" : "Swamp", a, ZoneType.Library);
            addCardToZone(bLibrary, b, ZoneType.Library);
        }
        a.setLife(8, null);
        b.setLife(14, null);
        for (int i = 0; i < 3; i++) {
            addCard("Swamp", a).setTapped(true);
        }
        for (int i = 0; i < 4; i++) {
            addCard("Island", a);
        }
        final Card fd = addCard(faceDown, a);
        fd.turnFaceDown(true);
        fd.setSickness(true);
        for (String n : new String[] {"Crush of Tentacles", "Ruinous Path"}) {
            addCardToZone(n, a, ZoneType.Hand);
        }
        for (String n : new String[] {"Nightveil Specter", "Silumgar's Command", "Rewind", "Desecration Demon", "Hero's Downfall"}) {
            addCardToZone(n, a, ZoneType.Graveyard);
        }
        for (String n : new String[] {"Stomping Ground", "Cinder Glade", "Swamp", "Mountain", "Swamp"}) {
            addCard(n, b);
        }
        for (String n : bHand) {
            addCardToZone(n, b, ZoneType.Hand);
        }
        for (String n : new String[] {"Bloodstained Mire", "Brimstone Volley", "Xenagos, the Reveler", "Kolaghan, the Storm's Fury",
                "Courser of Kruphix", "Dreadbore", "Elvish Mystic"}) {
            addCardToZone(n, b, ZoneType.Graveyard);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static final String[] B_HAND = {"Master of Cruelties", "Kolaghan's Command", "Polukranos, World Eater"};

    private Game board(PhaseType phase, boolean aiActive, String faceDown) {
        return board(phase, aiActive, faceDown, B_HAND, "Mountain");
    }

    /** The reported decision: a's own end of combat, Stratus Dancer face down. */
    private Game reported() {
        return board(PhaseType.COMBAT_END, true, "Stratus Dancer");
    }

    /** a's face-down permanent. */
    private static Card faceDown(Player a) {
        for (Card c : a.getCardsIn(ZoneType.Battlefield)) {
            if (c.isFaceDown()) {
                return c;
            }
        }
        return null;
    }

    /** The turn-up of a's face-down permanent, as a would play it (it must exist: a fixture never passes on a missing one). */
    private static SpellAbility turnUp(Player a) {
        final Card c = faceDown(a);
        AssertJUnit.assertNotNull("the fixture has a face-down permanent", c);
        for (SpellAbility sa : c.getAllPossibleAbilities(a, false)) {
            if (sa.isTurnFaceUp()) {
                sa.setActivatingPlayer(a);
                return sa;
            }
        }
        AssertJUnit.fail("the face-down permanent has a turn-up");
        return null;
    }

    private static String why(Player a) {
        return LookaheadSearch.deadFaceUpWhy(turnUp(a), a, null);
    }

    private static LookaheadSearch search(AiFixes.Mode mode) {
        return search(mode, 4);
    }

    private static LookaheadSearch search(AiFixes.Mode mode, int breadth) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 4;
        c.breadth = breadth;
        c.horizonTurns = 2;
        c.threads = 1;
        c.seed = 1_118_000_016L;
        c.departZ = 1.645;
        c.deadFaceUp = mode;
        return new LookaheadSearch(c);
    }

    /**
     * The policy seat's given set as the live seat sends it: pass first (the default), then every legal play of its menu
     * (live, at the reported step: pass and the turn-up).
     */
    private static List<SpellAbility> given(Player a) {
        final List<SpellAbility> g = new ArrayList<>();
        g.add(null);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(ComputerUtilAbility.getAvailableCards(a.getGame(), a), a)) {
            if (sa.isManaAbility()) {
                continue;
            }
            sa.setActivatingPlayer(a);
            if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, a, false)) {
                g.add(sa);
            }
        }
        return g;
    }

    private static int turnUpAt(List<SpellAbility> g) {
        for (int i = 0; i < g.size(); i++) {
            if (g.get(i) != null && g.get(i).isTurnFaceUp()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean searched(LookaheadSearch.GivenResult r, int idx) {
        for (int i : r.givenIndex) {
            if (i == idx) {
                return true;
            }
        }
        return false;
    }

    /** A turn-up candidate: the face-down permanent's only ability ("Megamorph {1}{U} (Turn this face up any time ...)"). */
    private static boolean isTurnUp(LookaheadSearch.Cand c, Player a) {
        return !c.pass && c.hostId == faceDown(a).getId() && c.desc.contains("Turn this face up");
    }

    private static boolean hasTurnUp(List<LookaheadSearch.Cand> cands, Player a) {
        for (LookaheadSearch.Cand c : cands) {
            if (isTurnUp(c, a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The policy seat's live path (decideGiven, guardGiven) on [pass, turn-up] in {@code mode}: true when the turn-up was
     * dropped (ON's counter and the searched set agree). The live game is never changed.
     */
    private static boolean droppedLive(Game g, Player a, AiFixes.Mode mode) {
        final List<SpellAbility> giv = new ArrayList<>(Arrays.asList(null, turnUp(a)));
        final LookaheadSearch s = search(mode);
        try {
            final String before = LookaheadSearch.fingerprint(g);
            final LookaheadSearch.GivenResult r = s.decideGiven(g, a, giv, 4);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g));
            final boolean dropped = s.getStats().deadFaceUpDropped > 0;
            AssertJUnit.assertEquals("the counter and the searched set agree", dropped, !searched(r, 1) && "uncontested".equals(r.outcome));
            return dropped;
        } finally {
            s.shutdown();
        }
    }

    // ---------------------------------------------------------------- the key

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.deadFaceUp);
        AssertJUnit.assertFalse(c.toJson().has("deadFaceUp"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("deadFaceUp"));
        } finally {
            s.shutdown();
        }
        c.deadFaceUp = AiFixes.Mode.SHADOW;
        AssertJUnit.assertEquals("shadow", c.toJson().get("deadFaceUp").getAsString());
        RlSearch.Config r = new RlSearch.Config();
        AssertJUnit.assertEquals("off", r.deadFaceUp);
        AssertJUnit.assertFalse("policy-seat spec JSON unchanged while off", r.toJson().has("deadFaceUp"));
    }

    // ---------------------------------------------------------------- the reported board

    @Test
    public void theReportedBoardIsDead() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        final Card dancer = faceDown(a);
        AssertJUnit.assertTrue("the fixture: a 2/2 face down, summoning sick", dancer.isFaceDown() && dancer.isSick());
        AssertJUnit.assertTrue(game.getStack().isEmpty());
        AssertJUnit.assertEquals(LookaheadSearch.DFU_DEAD, why(a));
        AssertJUnit.assertTrue(LookaheadSearch.deadFaceUp(turnUp(a), a));
        AssertJUnit.assertNull("not a turn-up: not this guard's candidate", LookaheadSearch.deadFaceUpWhy(null, a, null));
        AssertJUnit.assertTrue("the check reads only", dancer.isFaceDown());
    }

    /** The live path: the misplay came from the policy seat's given set (decideGiven, guardGiven). ON passes. */
    @Test
    public void onDropsTheTurnUpAndPasses() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        List<SpellAbility> giv = given(a);
        AssertJUnit.assertEquals("the live menu at the reported step: pass and the turn-up", 2, giv.size());
        int at = turnUpAt(giv);
        AssertJUnit.assertEquals(1, at);
        LookaheadSearch on = search(AiFixes.Mode.ON);
        try {
            String before = LookaheadSearch.fingerprint(game);
            LookaheadSearch.GivenResult r = on.decideGiven(game, a, giv, 4);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertFalse("ON: the turn-up is not searched", searched(r, at));
            AssertJUnit.assertEquals("only pass is left", "uncontested", r.outcome);
            AssertJUnit.assertTrue("the seat passes (the default stands)", r.chosen <= 0);
            AssertJUnit.assertEquals(1, on.getStats().deadFaceUpDropped);
            AssertJUnit.assertEquals(1, on.getStats().deadFaceUpCands);
            AssertJUnit.assertEquals(1, on.getStats().deadFaceUpDecisions);
            AssertJUnit.assertEquals(1, on.getStats().deadFaceUpChecked);
            AssertJUnit.assertEquals(0, on.getStats().deadFaceUpForge);
            final JsonObject st = on.getStats().toJson().getAsJsonObject("deadFaceUp");
            AssertJUnit.assertEquals("on", st.get("mode").getAsString());
            AssertJUnit.assertEquals(1, st.get("dropped").getAsInt());
            AssertJUnit.assertEquals(0, st.getAsJsonObject("aside").size());
            AssertJUnit.assertTrue(st.has("ms"));
            AssertJUnit.assertNotNull("the decision's note", r.deadFaceUp);
            AssertJUnit.assertEquals(1, r.deadFaceUp.get("checked").getAsInt());
            AssertJUnit.assertEquals(1, r.deadFaceUp.get("dropped").getAsInt());
            AssertJUnit.assertTrue(r.deadFaceUp.get("ms").getAsDouble() >= 0);
        } finally {
            on.shutdown();
        }
    }

    /** OFF searches the turn-up as live did; SHADOW records it and decides exactly as OFF. */
    @Test
    public void offIsUnchangedAndShadowRecordsIt() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        List<SpellAbility> giv = given(a);
        int at = turnUpAt(giv);
        LookaheadSearch off = search(AiFixes.Mode.OFF);
        LookaheadSearch s = search(AiFixes.Mode.SHADOW);
        try {
            LookaheadSearch.GivenResult o = off.decideGiven(game, a, giv, 4);
            LookaheadSearch.GivenResult r = s.decideGiven(game, a, giv, 4);
            AssertJUnit.assertTrue("OFF: the turn-up is searched, as it was live", searched(o, at));
            AssertJUnit.assertNull("OFF: no note", o.deadFaceUp);
            AssertJUnit.assertFalse(off.getStats().toJson().has("deadFaceUp"));
            AssertJUnit.assertEquals(0, off.getStats().deadFaceUpChecked);
            AssertJUnit.assertTrue("SHADOW searches the turn-up", searched(r, at));
            AssertJUnit.assertEquals("SHADOW chooses what OFF chooses", o.chosen, r.chosen);
            AssertJUnit.assertEquals(o.outcome, r.outcome);
            AssertJUnit.assertTrue(Arrays.equals(o.givenIndex, r.givenIndex));
            AssertJUnit.assertTrue(Arrays.equals(o.ev, r.ev));
            AssertJUnit.assertEquals(1, s.getStats().deadFaceUpCands);
            AssertJUnit.assertEquals(1, s.getStats().deadFaceUpDecisions);
            AssertJUnit.assertEquals(0, s.getStats().deadFaceUpDropped);
            AssertJUnit.assertEquals("a would-be departure to it is counted", "departed".equals(o.outcome) ? 1 : 0,
                    s.getStats().deadFaceUpShadowBest);
            AssertJUnit.assertEquals(1, s.deadFaceUpKeys.size());
            AssertJUnit.assertEquals(1, r.deadFaceUp.get("flagged").getAsInt());
            AssertJUnit.assertEquals("shadow", s.getStats().toJson().getAsJsonObject("deadFaceUp").get("mode").getAsString());
        } finally {
            off.shutdown();
            s.shutdown();
        }
    }

    /** The K8 seat's site (enumerate), on the same board: the same class, the same rule. Breadth 10 holds every legal play. */
    @Test
    public void theK8EnumerationDropsItWhenOn() {
        Game g1 = reported();
        Player a1 = g1.getPlayers().get(0);
        LookaheadSearch off = search(AiFixes.Mode.OFF, 10);
        Game g2 = reported();
        Player a2 = g2.getPlayers().get(0);
        LookaheadSearch on = search(AiFixes.Mode.ON, 10);
        try {
            AssertJUnit.assertTrue("OFF: the turn-up is a candidate", hasTurnUp(off.enumerate(g1, a1, null, 1L, null), a1));
            String before = LookaheadSearch.fingerprint(g2);
            AssertJUnit.assertFalse("ON: the turn-up is not a candidate", hasTurnUp(on.enumerate(g2, a2, null, 1L, null), a2));
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g2));
            AssertJUnit.assertEquals(1, on.getStats().deadFaceUpDropped);
            AssertJUnit.assertEquals(1, on.getStats().deadFaceUpDecisions);
        } finally {
            off.shutdown();
            on.shutdown();
        }
    }

    /** The policy's own choice to turn up (the default), and Forge's own answer at the K8 site, are never dropped. */
    @Test
    public void theDefaultIsNeverDropped() {
        Game g1 = reported();
        Player a1 = g1.getPlayers().get(0);
        List<SpellAbility> giv = given(a1);
        int at = turnUpAt(giv);
        giv.add(0, giv.remove(at)); // the policy's own answer is the turn-up
        LookaheadSearch s = search(AiFixes.Mode.ON);
        Game g2 = reported();
        Player a2 = g2.getPlayers().get(0);
        LookaheadSearch k8 = search(AiFixes.Mode.ON, 10);
        try {
            AssertJUnit.assertTrue("the policy's default is searched", searched(s.decideGiven(g1, a1, giv, 4), 0));
            AssertJUnit.assertEquals(1, s.getStats().deadFaceUpForge);
            AssertJUnit.assertEquals(0, s.getStats().deadFaceUpDropped);
            List<LookaheadSearch.Cand> cands = k8.enumerate(g2, a2, turnUp(a2), 1L, null);
            AssertJUnit.assertTrue(cands.get(0).isDefault);
            AssertJUnit.assertTrue("Forge's own answer is kept", isTurnUp(cands.get(0), a2));
            AssertJUnit.assertEquals(1, k8.getStats().deadFaceUpForge);
            AssertJUnit.assertEquals(0, k8.getStats().deadFaceUpDropped);
        } finally {
            s.shutdown();
            k8.shutdown();
        }
    }

    /** The class beyond the reported card: Hidden Dragonslayer (lifelink) with no opposing creature of power 4 or more. */
    @Test
    public void anotherCardOfTheClassIsDeadUntilItHasATarget() {
        Game g = board(PhaseType.COMBAT_END, true, "Hidden Dragonslayer");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertEquals(LookaheadSearch.DFU_DEAD, why(a));
        AssertJUnit.assertTrue("ON drops it on the live path", droppedLive(g, a, AiFixes.Mode.ON));
        addCard("Polukranos, World Eater", g.getPlayers().get(1));
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("a 5/5 to destroy", "target", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
    }

    // ---------------------------------------------------------------- stand-asides

    /** A spell on the stack the trigger can target; and with only a creature spell there, the top item resolves first. */
    @Test
    public void aSpellOnTheStackStandsAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0);
        Player b = g.getPlayers().get(1);
        final Card bolt = addCardToZone("Lightning Bolt", b, ZoneType.Hand);
        final SpellAbility boltSa = bolt.getFirstSpellAbility();
        boltSa.setActivatingPlayer(b);
        boltSa.getTargets().add(a);
        g.getStackZone().add(bolt);
        g.getStack().add(boltSa);
        AssertJUnit.assertEquals("an instant to counter", "target", why(a));
        AssertJUnit.assertFalse("ON keeps it on the live path", droppedLive(g, a, AiFixes.Mode.ON));

        Game g2 = reported();
        Player a2 = g2.getPlayers().get(0);
        Player b2 = g2.getPlayers().get(1);
        final Card bears = addCardToZone("Grizzly Bears", b2, ZoneType.Hand);
        final SpellAbility bearsSa = bears.getFirstSpellAbility();
        bearsSa.setActivatingPlayer(b2);
        g2.getStackZone().add(bears);
        g2.getStack().add(bearsSa);
        AssertJUnit.assertEquals("no target, but the top item resolves before the seat's next priority", "stack", why(a2));
    }

    @Test
    public void anUntargetedTurnedUpTriggerStandsAside() {
        Game g = board(PhaseType.COMBAT_END, true, "Dog Walker");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertEquals("two Dogs whenever it turns up", "untargeted", why(a));
    }

    @Test
    public void aStaticOrActivatedAbilityOnTheFaceUpSideStandsAside() {
        Game g = board(PhaseType.COMBAT_END, true, "Silumgar Assassin");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertEquals("its trigger has no target (b has no creature), its blocking static still reads its power", "ability", why(a));
        Game q = board(PhaseType.COMBAT_END, true, "Mischievous Quanar");
        Player a2 = q.getPlayers().get(0);
        AssertJUnit.assertEquals("its copy trigger has no target, its turn-face-down ability is an activated ability", "ability", why(a2));
    }

    /** a's own first main phase: a face-down creature that can attack (not summoning sick, untapped) could attack as a flyer. */
    @Test
    public void ownMain1WithACreatureThatCanAttackStandsAside() {
        Game g = board(PhaseType.MAIN1, true, "Stratus Dancer");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertEquals("summoning sick: it cannot attack this turn", LookaheadSearch.DFU_DEAD, why(a));
        faceDown(a).setSickness(false);
        AssertJUnit.assertEquals("it can attack this turn", "timing", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
        faceDown(a).setTapped(true);
        AssertJUnit.assertEquals("tapped: it cannot", LookaheadSearch.DFU_DEAD, why(a));
    }

    /** b's declare-attackers step: blockers come before the seat's next priority, so an untapped face-down creature stands aside. */
    @Test
    public void theOpponentsDeclareAttackersWithACreatureThatCanBlockStandsAside() {
        Game g = board(PhaseType.COMBAT_DECLARE_ATTACKERS, false, "Stratus Dancer");
        Player a = g.getPlayers().get(0);
        Player b = g.getPlayers().get(1);
        final Card bird = addCard("Nightveil Specter", b);
        bird.setSickness(false);
        final Combat combat = new Combat(b);
        combat.addAttacker(bird, a);
        g.getPhaseHandler().setCombat(combat);
        faceDown(a).setSickness(false);
        AssertJUnit.assertEquals("a face-up flyer could block the Specter", "timing", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
        faceDown(a).setTapped(true);
        AssertJUnit.assertEquals("tapped: it cannot block", LookaheadSearch.DFU_DEAD, why(a));
    }

    @Test
    public void thePermanentInCombatStandsAside() {
        Game g = board(PhaseType.COMBAT_DECLARE_ATTACKERS, true, "Stratus Dancer");
        Player a = g.getPlayers().get(0);
        Player b = g.getPlayers().get(1);
        final Card dancer = faceDown(a);
        dancer.setSickness(false);
        final Combat combat = new Combat(a);
        combat.addAttacker(dancer, b);
        g.getPhaseHandler().setCombat(combat);
        AssertJUnit.assertEquals("attacking", "combat", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));

        Game g2 = board(PhaseType.COMBAT_DECLARE_BLOCKERS, false, "Stratus Dancer");
        Player a2 = g2.getPlayers().get(0);
        Player b2 = g2.getPlayers().get(1);
        final Card bears = addCard("Grizzly Bears", b2);
        bears.setSickness(false);
        final Combat c2 = new Combat(b2);
        c2.addAttacker(bears, a2);
        c2.addBlocker(bears, faceDown(a2));
        g2.getPhaseHandler().setCombat(c2);
        AssertJUnit.assertEquals("blocking", "combat", why(a2));
    }

    /** The same turn-up with a cost that is not mana (this test game only). */
    @Test
    public void aNonManaTurnUpCostStandsAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0);
        final SpellAbility sa = turnUp(a);
        sa.setPayCosts(new Cost("PayLife<2>", true));
        AssertJUnit.assertEquals("cost", LookaheadSearch.deadFaceUpWhy(sa, a, null));
    }

    @Test
    public void floatingManaStandsAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0);
        final Card island = a.getCardsIn(ZoneType.Battlefield).get(3);
        a.getManaPool().addMana(new Mana((byte) ManaAtom.BLUE, island, null, a));
        AssertJUnit.assertEquals("pool", why(a));
    }

    @Test
    public void anotherPermanentWithATurnFaceUpTriggerStandsAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0);
        addCard("Aven Farseer", g.getPlayers().get(1));
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("b's Aven Farseer grows when any permanent turns face up", "watcher", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));

        Game g2 = reported();
        Player a2 = g2.getPlayers().get(0);
        addCard("Secret Plans", a2);
        g2.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("a's own Secret Plans draws when it turns up", "watcher", why(a2));
    }

    /** Condition 3's table on both turns, with a face-down creature that could attack (own turn) or block (b's turn). */
    @Test
    public void theTimingTable() {
        for (PhaseType p : new PhaseType[] {PhaseType.UPKEEP, PhaseType.DRAW, PhaseType.MAIN1, PhaseType.COMBAT_BEGIN}) {
            Game g = board(p, true, "Stratus Dancer");
            Player a = g.getPlayers().get(0);
            faceDown(a).setSickness(false);
            AssertJUnit.assertEquals("own " + p + ": it can attack this turn", "timing", why(a));
        }
        for (PhaseType p : new PhaseType[] {PhaseType.COMBAT_END, PhaseType.MAIN2, PhaseType.END_OF_TURN}) {
            Game g = board(p, true, "Stratus Dancer");
            Player a = g.getPlayers().get(0);
            faceDown(a).setSickness(false);
            AssertJUnit.assertEquals("own " + p, LookaheadSearch.DFU_DEAD, why(a));
        }
        for (PhaseType p : new PhaseType[] {PhaseType.UPKEEP, PhaseType.DRAW, PhaseType.MAIN1, PhaseType.COMBAT_BEGIN,
                PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.COMBAT_END, PhaseType.MAIN2, PhaseType.END_OF_TURN}) {
            Game g = board(p, false, "Stratus Dancer");
            Player a = g.getPlayers().get(0);
            faceDown(a).setSickness(false);
            AssertJUnit.assertEquals("b's " + p, LookaheadSearch.DFU_DEAD, why(a));
        }
        for (boolean own : new boolean[] {true, false}) {
            Game g = board(PhaseType.CLEANUP, own, "Stratus Dancer");
            AssertJUnit.assertEquals("cleanup: not classified", "phase", why(g.getPlayers().get(0)));
        }
    }

    // ---------------------------------------------------------------- hidden information

    /**
     * The answer reads only a's own face-down card and public state: b's hidden hand and library, and b's face-down
     * permanent (an Aven Farseer, a watcher once face up), never change it; the same Farseer face up does.
     */
    @Test
    public void theOpponentsHiddenCardsAreNotRead() {
        final String[][] hands = {B_HAND, {"Swamp", "Swamp", "Swamp"}, {"Counterspell", "Aven Farseer", "Secret Plans"}};
        final String[] libraries = {"Mountain", "Lightning Bolt", "Secret Plans"};
        for (String[] hand : hands) {
            for (String lib : libraries) {
                Game g = board(PhaseType.COMBAT_END, true, "Stratus Dancer", hand, lib);
                Player a = g.getPlayers().get(0);
                AssertJUnit.assertEquals(Arrays.toString(hand) + " / " + lib, LookaheadSearch.DFU_DEAD, why(a));
                AssertJUnit.assertTrue("the live path drops it: " + Arrays.toString(hand) + " / " + lib,
                        droppedLive(g, a, AiFixes.Mode.ON));
            }
        }
        Game g = reported();
        Player a = g.getPlayers().get(0);
        final Card farseer = addCard("Aven Farseer", g.getPlayers().get(1));
        farseer.turnFaceDown(true);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertFalse("the fixture: a cannot see its face", farseer.getView().canFaceDownBeShownTo(a.getView()));
        AssertJUnit.assertEquals("b's face-down Farseer has no abilities and is not read", LookaheadSearch.DFU_DEAD, why(a));
        AssertJUnit.assertTrue(droppedLive(g, a, AiFixes.Mode.ON));
    }
}
