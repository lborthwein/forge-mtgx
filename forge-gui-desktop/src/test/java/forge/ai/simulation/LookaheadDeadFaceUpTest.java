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

    /** A target restriction from a ValidTgts string (battlefield), as a script's would be. */
    private static forge.game.spellability.TargetRestrictions tgts(String valid) {
        final java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("ValidTgts", valid);
        m.put("TgtPrompt", "test");
        return new forge.game.spellability.TargetRestrictions(m);
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

    /**
     * Review round 3b: the reported board exactly as it was, with Xenagos, the Reveler in b's graveyard, is dead and drops.
     * Xenagos's planeswalker loyalty replacement ("enters with three loyalty counters": Event Moved, ValidCard Card.Self,
     * Destination Battlefield, no ActiveZones) is active in every zone by Forge's {@code zonesCheck}, and it is exempt: it
     * is a replacement for its own entering the battlefield ({@code dfuOwnEntry}).
     */
    @Test
    public void theReportedBoardExactlyDropsWithXenagos() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        Card xenagos = game.getPlayers().get(1).getCardsIn(ZoneType.Graveyard).stream()
                .filter(c -> c.getName().equals("Xenagos, the Reveler")).findFirst().get();
        AssertJUnit.assertFalse(xenagos.getReplacementEffects().isEmpty());
        for (forge.game.replacement.ReplacementEffect re : xenagos.getReplacementEffects()) {
            AssertJUnit.assertTrue("Forge counts it active in the graveyard", re.zonesCheck(game.getZoneOf(xenagos)));
            AssertJUnit.assertTrue("its own entering: exempt", LookaheadSearch.dfuOwnEntry(re));
        }
        AssertJUnit.assertEquals(LookaheadSearch.DFU_DEAD, why(a));
        AssertJUnit.assertTrue("the live path drops it", droppedLive(game, a, AiFixes.Mode.ON));
    }

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

    /**
     * The class beyond the reported card: Ainok Survivalist ("destroy target artifact or enchantment an opponent controls")
     * with no artifact or enchantment anywhere. Review round 2: the count ignores every restriction but the card type, so
     * the seat's own Mox Ruby (an artifact it does not control as an opponent) is already a possible target.
     */
    @Test
    public void anotherCardOfTheClassIsDeadUntilItHasATarget() {
        Game g = board(PhaseType.COMBAT_END, true, "Ainok Survivalist");
        Player a = g.getPlayers().get(0);
        addCard("Forest", a);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(LookaheadSearch.DFU_DEAD, why(a));
        AssertJUnit.assertTrue("ON drops it on the live path", droppedLive(g, a, AiFixes.Mode.ON));
        addCard("Mox Ruby", a);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("an artifact, though not an opponent's", "target", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
    }

    /**
     * Review round 2: a creature-targeting trigger always has a possible target once the card type alone counts (the
     * face-down creature itself is one); a filter that is not card types (a player) stands aside.
     */
    @Test
    public void theTargetCountIgnoresEverythingButTheCardType() {
        Game g = board(PhaseType.COMBAT_END, true, "Hidden Dragonslayer");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertEquals("no power-4 creature an opponent controls, but a creature: its own body", "target", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
        Game s2 = board(PhaseType.COMBAT_END, true, "Silumgar Assassin");
        AssertJUnit.assertEquals("target", why(s2.getPlayers().get(0)));
        for (String valid : new String[] {"Player", "Opponent", "Any", "Creature,Player", "Emblem", "Card.YouOwn,Player"}) {
            AssertJUnit.assertEquals(valid, -1, LookaheadSearch.dfuTypeCandidates(tgts(valid), g));
        }
        AssertJUnit.assertEquals("the reported board: no artifact or enchantment anywhere", 0,
                LookaheadSearch.dfuTypeCandidates(tgts("Artifact.OppCtrl,Enchantment.OppCtrl"), g));
        AssertJUnit.assertTrue(LookaheadSearch.dfuTypeCandidates(tgts("Land.nonBasic+OppCtrl"), g) > 0);
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
        AssertJUnit.assertEquals("review round 2: any object on the stack counts as a possible target", "target", why(a2));
    }

    @Test
    public void anUntargetedTurnedUpTriggerStandsAside() {
        Game g = board(PhaseType.COMBAT_END, true, "Dog Walker");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertEquals("two Dogs whenever it turns up", "untargeted", why(a));
    }

    /**
     * A static ability on the face-up side (added to this test game's Stratus Dancer: no card of the class has one whose
     * trigger can lack a target now), and an activated one (Mischievous Quanar).
     */
    @Test
    public void aStaticOrActivatedAbilityOnTheFaceUpSideStandsAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0);
        final Card dancer = faceDown(a);
        final forge.game.card.CardState up = dancer.getState(forge.card.CardStateName.Original);
        up.addStaticAbility(forge.game.staticability.StaticAbility.create(
                "Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddPower$ 1 | Description$ Other creatures you control get +1/+0.",
                dancer, up, true));
        AssertJUnit.assertEquals("its counter trigger has no target, its anthem is a static ability", "ability", why(a));
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
        AssertJUnit.assertEquals("review round 2: tapped does not prove it cannot block (Masako)", "timing", why(a));
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
        for (PhaseType p : new PhaseType[] {PhaseType.COMBAT_END, PhaseType.MAIN2}) {
            Game g = board(p, true, "Stratus Dancer");
            Player a = g.getPlayers().get(0);
            faceDown(a).setSickness(false);
            AssertJUnit.assertEquals("own " + p, LookaheadSearch.DFU_DEAD, why(a));
        }
        // review round 3: a's own end step always stands aside (cleanup comes before a's next priority)
        Game ownEot = board(PhaseType.END_OF_TURN, true, "Stratus Dancer");
        faceDown(ownEot.getPlayers().get(0)).setSickness(false);
        AssertJUnit.assertEquals("own end step", "cleanup", why(ownEot.getPlayers().get(0)));
        for (PhaseType p : new PhaseType[] {PhaseType.UPKEEP, PhaseType.DRAW, PhaseType.MAIN1, PhaseType.COMBAT_BEGIN,
                PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.COMBAT_END, PhaseType.MAIN2}) {
            Game g = board(p, false, "Stratus Dancer");
            Player a = g.getPlayers().get(0);
            faceDown(a).setSickness(false);
            AssertJUnit.assertEquals("b's " + p, LookaheadSearch.DFU_DEAD, why(a));
        }
        // review round 2: b's declare attackers always stands aside; b's end step comes just before a's untap step
        for (boolean tapped : new boolean[] {false, true}) {
            Game g = board(PhaseType.COMBAT_DECLARE_ATTACKERS, false, "Stratus Dancer");
            Player a = g.getPlayers().get(0);
            faceDown(a).setSickness(false);
            faceDown(a).setTapped(tapped);
            AssertJUnit.assertEquals("b's declare attackers, no attacker, tapped " + tapped, "timing", why(a));
        }
        Game eot = board(PhaseType.END_OF_TURN, false, "Stratus Dancer");
        AssertJUnit.assertEquals("b's end step", "untap", why(eot.getPlayers().get(0)));
        for (boolean own : new boolean[] {true, false}) {
            Game g = board(PhaseType.CLEANUP, own, "Stratus Dancer");
            AssertJUnit.assertEquals("cleanup: not classified", "phase", why(g.getPlayers().get(0)));
        }
    }

    // ---------------------------------------------------------------- review round 2 (Astra, HOLD at cd940f48a01)
    // Each repro (review/247/ReviewScratchDeadFaceUpTest.java) copied in as a test that must not drop; the reason is the
    // stand-aside round 2 gives it.

    /** S1 1: b's end step. Turning up now spends mana that untaps before a's next priority; waiting spends next turn's. */
    @Test
    public void opponentEndStepManaWillUntap() {
        Game g = board(PhaseType.END_OF_TURN, false, "Stratus Dancer");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertTrue(a.getManaPool().isEmpty());
        AssertJUnit.assertEquals("untap", why(a));
        AssertJUnit.assertFalse("Spend 1U now, untap all lands; waiting spends next turn's mana", droppedLive(g, a, AiFixes.Mode.ON));
    }

    /**
     * Round 2, rule 1 on a's own turn: an extra turn of a's own is pending, so its untap step comes before its next priority.
     * Since round 3 a's own end step stands aside anyway ("cleanup"); the pending extra turn still answers first ("phase").
     */
    @Test
    public void ownEndStepBeforeAnExtraTurnStandsAside() {
        Game g = board(PhaseType.END_OF_TURN, true, "Stratus Dancer");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertEquals("a's own end step, b's turn next (review round 3)", "cleanup", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
        g.getPhaseHandler().addExtraTurn(a);
        AssertJUnit.assertEquals(a, g.getPhaseHandler().getNextTurn());
        AssertJUnit.assertEquals("an extra turn pending", "phase", why(a));
        // the look-ahead's copies leave extra turns out: the sites read them in the live game ("pending")
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
        AssertJUnit.assertTrue(LookaheadSearch.dfuLivePending(g));
    }

    /** Round 2: a delayed trigger in the live game (the copies leave it out) stands aside on the live path. */
    @Test
    public void aDelayedTriggerInTheLiveGameStandsAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertFalse(LookaheadSearch.dfuLivePending(g));
        final Card island = a.getCardsIn(ZoneType.Battlefield).get(3);
        final forge.game.trigger.Trigger t = forge.game.trigger.TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ End of Turn | TriggerDescription$ At the beginning of the next end step, test.", island, false);
        g.getTriggerHandler().registerDelayedTrigger(t);
        AssertJUnit.assertTrue(LookaheadSearch.dfuLivePending(g));
        AssertJUnit.assertEquals("other", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
    }

    /** S1 2: protection from colorless counted with the face-down source; the white face-up Dragonslayer can target it. */
    @Test
    public void colorlessProtectionIsNotProtectionFromFaceUpSource() {
        Game g = board(PhaseType.COMBAT_END, true, "Hidden Dragonslayer");
        Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        addCard("Plains", a);
        addCard("Plains", a);
        Card target = addCard("Polukranos, World Eater", b);
        Card giver = addCard("Giver of Runes", b);
        giver.setTapped(true);
        target.addChangedCardKeywords(Arrays.asList("Protection from colorless"), null, false, g.getNextTimestamp(), null);
        g.getAction().checkStateEffects(true);
        SpellAbility turn = turnUp(a);
        Card host = turn.getHostCard();
        AssertJUnit.assertEquals("a creature of the filter's type is a possible target", "target", LookaheadSearch.deadFaceUpWhy(turn, a, null));
        boolean wasDropped = droppedLive(g, a, AiFixes.Mode.ON);
        host.turnFaceUp(false, turn);
        g.getAction().checkStateEffects(true);
        forge.game.trigger.Trigger t = host.getState(forge.card.CardStateName.Original).getTriggers().iterator().next();
        SpellAbility trigger = t.ensureAbility().copy(a);
        trigger.setActivatingPlayer(a);
        AssertJUnit.assertTrue("White Dragonslayer can destroy the protected 5/5", trigger.canTarget(target));
        AssertJUnit.assertFalse("A legal turned-up trigger is dropped", wasDropped);
    }

    /** S1 3a: a's own declare blockers; turning up makes another Monk, and Coat of Arms grows the attacking Mentor before damage. */
    @Test
    public void typeChangeBuffsAnAttackerBeforeDamage() {
        Game g = board(PhaseType.COMBAT_DECLARE_BLOCKERS, true, "Stratus Dancer");
        Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        Card bird = addCard("Monastery Mentor", a);
        bird.setSickness(false);
        addCard("Coat of Arms", a);
        Combat combat = new Combat(a);
        combat.addAttacker(bird, b);
        g.getPhaseHandler().setCombat(combat);
        g.getAction().checkStateEffects(true);
        SpellAbility turn = turnUp(a);
        AssertJUnit.assertEquals(2, bird.getNetPower());
        AssertJUnit.assertEquals("other", LookaheadSearch.deadFaceUpWhy(turn, a, null));
        boolean wasDropped = droppedLive(g, a, AiFixes.Mode.ON);
        turn.getHostCard().turnFaceUp(false, turn);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("Another Monk increases this combat's damage", 3, bird.getNetPower());
        AssertJUnit.assertFalse("Host is not attacking, but turning up changes an attacker's damage", wasDropped);
    }

    /** S1 4: the megamorph counter triggers Shalai and Hallar for lethal damage now. */
    @Test
    public void megamorphCounterHasImmediateDamagePayoff() {
        Game g = board(PhaseType.COMBAT_END, true, "Stratus Dancer");
        Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        addCard("Shalai and Hallar", a);
        b.setLife(1, null);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("other", why(a));
        AssertJUnit.assertFalse("Megamorph's counter triggers Shalai and Hallar for lethal damage now", droppedLive(g, a, AiFixes.Mode.ON));
    }

    /** S1 3b: a's own main phase 2; the 5/5 face up turns on Drumhunter's end-step draw before a's next priority. */
    @Test
    public void phaseTriggerNeedsPowerBeforeEndStep() {
        Game g = board(PhaseType.MAIN2, true, "Vengeful Creeper");
        Player a = g.getPlayers().get(0);
        for (int i = 0; i < 5; i++) {
            addCard("Forest", a);
        }
        addCard("Drumhunter", a);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("other", why(a));
        AssertJUnit.assertFalse("Turning up the 5/5 now enables Drumhunter's end-step draw before next priority",
                droppedLive(g, a, AiFixes.Mode.ON));
    }

    /** S1 5: b's declare attackers; Masako lets the tapped face-up Dancer block the flyer, only if turned up before blocks. */
    @Test
    public void tappedCreatureCanStillBlockWithMasako() {
        Game g = board(PhaseType.COMBAT_DECLARE_ATTACKERS, false, "Stratus Dancer");
        Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        addCard("Masako the Humorless", a);
        Card attacker = addCard("Nightveil Specter", b);
        attacker.setSickness(false);
        Combat combat = new Combat(b);
        combat.addAttacker(attacker, a);
        g.getPhaseHandler().setCombat(combat);
        SpellAbility turn = turnUp(a);
        turn.getHostCard().setTapped(true);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("timing", LookaheadSearch.deadFaceUpWhy(turn, a, null));
        boolean wasDropped = droppedLive(g, a, AiFixes.Mode.ON);
        turn.getHostCard().turnFaceUp(false, turn);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(forge.game.combat.CombatUtil.canBlock(attacker, turn.getHostCard(), combat));
        AssertJUnit.assertFalse("Masako lets tapped Dancer block the flyer only if turned up before blocks", wasDropped);
    }

    /**
     * Round 2, rule 3: any other battlefield object, on either side, with a static or triggered ability, a keyword or a
     * replacement effect stands aside; one with only activated or mana abilities, or none, does not. The reported board's
     * lands pass: basic lands have no abilities but mana, and Stomping Ground's and Cinder Glade's only other trait is a
     * replacement for their own entering the battlefield.
     */
    @Test
    public void otherCardsWithAnyStaticTriggerKeywordOrReplacementStandAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        final Card dancer = faceDown(a);
        AssertJUnit.assertFalse("the reported board", LookaheadSearch.dfuOtherAbilities(dancer, g));
        int ownEntry = 0;
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
            if (c == dancer) {
                continue;
            }
            AssertJUnit.assertTrue(c.getName(), c.isLand());
            AssertJUnit.assertTrue(c.getName(), c.getStaticAbilities().isEmpty() && c.getTriggers().isEmpty() && c.getKeywords().isEmpty());
            for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
                AssertJUnit.assertTrue(c.getName(), LookaheadSearch.dfuOwnEntry(re));
                ownEntry++;
            }
        }
        AssertJUnit.assertEquals("Stomping Ground's and Cinder Glade's own-entry replacements", 2, ownEntry);
        addCard("Grizzly Bears", b);
        addCard("Llanowar Elves", a);
        addCard("Mox Ruby", b);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("a vanilla creature, mana abilities", LookaheadSearch.DFU_DEAD, why(a));
        AssertJUnit.assertTrue(droppedLive(g, a, AiFixes.Mode.ON));
        final String[][] cases = {{"Urborg, Tomb of Yawgmoth", "b"}, {"Serra Angel", "b"}, {"Hardened Scales", "a"},
                {"Drumhunter", "b"}, {"Masako the Humorless", "a"}};
        for (String[] k : cases) {
            Game h = reported();
            Player ha = h.getPlayers().get(0);
            addCard(k[0], "a".equals(k[1]) ? ha : h.getPlayers().get(1));
            h.getAction().checkStateEffects(true);
            AssertJUnit.assertEquals(k[0], "other", why(ha));
            AssertJUnit.assertFalse(k[0], droppedLive(h, ha, AiFixes.Mode.ON));
        }
        // a face-down disguise permanent of b's: ward:2 on its face-down state (public, as a disguise cast gives it) is a keyword
        Game d = reported();
        Player da = d.getPlayers().get(0);
        final Card walker = addCard("Dog Walker", d.getPlayers().get(1));
        walker.turnFaceDown(true);
        walker.getFaceDownState().addIntrinsicKeyword("Ward:2", true);
        walker.updateKeywordsCache();
        d.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("other", why(da));
    }

    // ---------------------------------------------------------------- review round 3 (Astra, HOLD at 720634b1461)
    // Each repro (review/247/r2/ReviewScratchDeadFaceUpTest.java) copied in as a test that must not drop; the reason is the
    // stand-aside round 3 gives it.

    /**
     * S1 1: Flamewake Phoenix in a's graveyard. On a's own main phase 1 the summoning-sick face-down Vengeful Creeper (2/2)
     * cannot attack, but turning it up (5/5) turns on Phoenix's ferocious trigger at the beginning of this combat, before
     * a's next priority. Rule: a trigger, static or replacement active in a graveyard, exile, the command zone or a's own
     * hand stands aside ("offBattlefield").
     */
    @Test
    public void graveyardFerociousNeedsBodyBeforeCombat() {
        Game g = board(PhaseType.MAIN1, true, "Vengeful Creeper");
        Player a = g.getPlayers().get(0);
        for (int i = 0; i < 6; i++) {
            addCard("Forest", a);
        }
        addCard("Mountain", a);
        Card phoenix = addCardToZone("Flamewake Phoenix", a, ZoneType.Graveyard);
        g.getAction().checkStateEffects(true);
        SpellAbility turn = turnUp(a);
        AssertJUnit.assertTrue(turn.canPlay());
        AssertJUnit.assertTrue(ComputerUtilCost.canPayCost(turn, a, false));
        AssertJUnit.assertEquals(2, turn.getHostCard().getNetPower());
        forge.game.trigger.Trigger trigger = phoenix.getTriggers().iterator().next();
        AssertJUnit.assertFalse("Ferocious is false before turning up", trigger.requirementsCheck(g));
        AssertJUnit.assertEquals("offBattlefield", why(a));
        boolean wasDropped = droppedLive(g, a, AiFixes.Mode.ON);
        turn.getHostCard().turnFaceUp(false, turn);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(5, turn.getHostCard().getNetPower());
        AssertJUnit.assertTrue("Turning up enables Phoenix at beginning of combat", trigger.requirementsCheck(g));
        AssertJUnit.assertFalse("MAIN1 pass misses Phoenix's upcoming ferocious trigger", wasDropped);
    }

    /**
     * Round 3, rule 1 by zone: Loxodon Smiter's discard replacement is active in a hand. In a's own hand it stands aside; in
     * b's hand (hidden) it is not read. Phoenix in b's graveyard (public) stands aside; in a library or in exile (its trigger
     * is active in a graveyard only) it does not; Misthollow Griffin's static, active in exile, does.
     */
    @Test
    public void abilitiesActiveOffTheBattlefieldStandAsideByZone() {
        Game g = reported();
        Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        AssertJUnit.assertEquals(LookaheadSearch.DFU_DEAD, why(a));
        addCardToZone("Loxodon Smiter", b, ZoneType.Hand);
        AssertJUnit.assertEquals("b's hand is not read", LookaheadSearch.DFU_DEAD, why(a));
        addCardToZone("Flamewake Phoenix", a, ZoneType.Library);
        AssertJUnit.assertEquals("libraries are not read", LookaheadSearch.DFU_DEAD, why(a));
        AssertJUnit.assertTrue(droppedLive(g, a, AiFixes.Mode.ON));
        addCardToZone("Loxodon Smiter", a, ZoneType.Hand);
        AssertJUnit.assertEquals("a's own hand", "offBattlefield", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
        Game g2 = reported();
        Player a2 = g2.getPlayers().get(0);
        addCardToZone("Flamewake Phoenix", g2.getPlayers().get(1), ZoneType.Graveyard);
        AssertJUnit.assertEquals("b's graveyard", "offBattlefield", why(a2));
        Game g3 = reported();
        Player a3 = g3.getPlayers().get(0);
        addCardToZone("Flamewake Phoenix", a3, ZoneType.Exile);
        AssertJUnit.assertEquals("Phoenix's trigger is active in a graveyard only", LookaheadSearch.DFU_DEAD, why(a3));
        addCardToZone("Misthollow Griffin", g3.getPlayers().get(1), ZoneType.Exile);
        AssertJUnit.assertEquals("Griffin's static is active in exile", "offBattlefield", why(a3));
    }

    /**
     * S1 2: a's own end step. Face-down Vengeful Creeper with two -1/-1 counters lives on a resolved Giant Growth; cleanup
     * ends the Growth and kills the face-down 0/0, while turning up first leaves a 3/3. The expiry is a pending command, not
     * a delayed trigger, so the live pending check does not see it: a's own end step always stands aside ("cleanup").
     */
    @Test
    public void cleanupRemovesPumpBeforeNextPriority() {
        boolean wasDropped = false;
        for (boolean turnNow : new boolean[] {false, true}) {
            Game g = board(PhaseType.END_OF_TURN, true, "Vengeful Creeper");
            Player a = g.getPlayers().get(0);
            for (int i = 0; i < 6; i++) {
                addCard("Forest", a);
            }
            Card host = turnUp(a).getHostCard();
            Card growth = addCardToZone("Giant Growth", a, ZoneType.Graveyard);
            SpellAbility pump = growth.getFirstSpellAbility();
            pump.setActivatingPlayer(a);
            pump.getTargets().add(host);
            forge.game.ability.AbilityUtils.resolve(pump);
            host.setCounters(forge.game.card.CounterEnumType.M1M1, 2);
            g.getAction().checkStateEffects(true);
            AssertJUnit.assertEquals(3, host.getNetToughness());
            AssertJUnit.assertTrue("Giant Growth scheduled its actual expiry", g.getEndOfTurn().hasPendingCommands());
            SpellAbility turn = turnUp(a);
            AssertJUnit.assertTrue(turn.canPlay());
            AssertJUnit.assertTrue(ComputerUtilCost.canPayCost(turn, a, false));
            AssertJUnit.assertEquals("cleanup", why(a));
            AssertJUnit.assertFalse("Expiry commands are not delayed triggers", LookaheadSearch.dfuLivePending(g));
            wasDropped |= droppedLive(g, a, AiFixes.Mode.ON);
            if (turnNow) {
                host.turnFaceUp(false, turn);
            }
            g.getPhaseHandler().devModeSet(PhaseType.CLEANUP, a);
            g.getEndOfTurn().executeUntil();
            g.getAction().checkStateEffects(true);
            AssertJUnit.assertEquals("Waiting kills Creeper at cleanup; turning up before cleanup saves it", turnNow, host.isInPlay());
            if (turnNow) {
                AssertJUnit.assertEquals(3, host.getNetToughness());
            } else {
                AssertJUnit.assertTrue(host.isInZone(ZoneType.Graveyard));
            }
        }
        AssertJUnit.assertFalse("Own end step passes the last chance to save the creature", wasDropped);
    }

    /**
     * Round 3b: only a replacement for the card's own entering the battlefield, with no parameter beyond
     * {@code DFU_OWN_ENTRY_PARAMS}, is exempt off the battlefield. A graveyard card with a non-self replacement (New Master of
     * Arms: prevent combat damage by tapped blockers, no ActiveZones), a self replacement for another event (Darksteel
     * Colossus: moved to a graveyard) or an own-entry replacement with conditions (Fetching Garden: Origin, ValidCause) still
     * stands aside. A keyword-generated own-entry replacement without a condition (Endless One's etbCounter) and a
     * planeswalker's loyalty replacement in a's own hand are exempt.
     */
    @Test
    public void aGraveyardCardWithANonSelfReplacementStandsAside() {
        Game g = reported();
        Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        Card master = addCardToZone("New Master of Arms", b, ZoneType.Graveyard);
        for (forge.game.replacement.ReplacementEffect re : master.getReplacementEffects()) {
            AssertJUnit.assertTrue(re.zonesCheck(g.getZoneOf(master)));
            AssertJUnit.assertFalse(LookaheadSearch.dfuOwnEntry(re));
        }
        AssertJUnit.assertEquals("a non-self replacement in b's graveyard", "offBattlefield", why(a));
        AssertJUnit.assertFalse(droppedLive(g, a, AiFixes.Mode.ON));
        final String[][] cases = {{"Darksteel Colossus", "a self replacement for another event"},
                {"Fetching Garden", "an own-entry replacement with conditions"}};
        for (String[] c : cases) {
            Game h = reported();
            Player ha = h.getPlayers().get(0);
            Card card = addCardToZone(c[0], ha, ZoneType.Graveyard);
            for (forge.game.replacement.ReplacementEffect re : card.getReplacementEffects()) {
                AssertJUnit.assertFalse(c[0], LookaheadSearch.dfuOwnEntry(re));
            }
            AssertJUnit.assertEquals(c[1], "offBattlefield", why(ha));
        }
        Game e = reported();
        Player ea = e.getPlayers().get(0);
        Card endless = addCardToZone("Endless One", ea, ZoneType.Graveyard);
        AssertJUnit.assertFalse("the fixture: etbCounter makes a replacement", endless.getReplacementEffects().isEmpty());
        AssertJUnit.assertEquals("a keyword-generated own-entry replacement", LookaheadSearch.DFU_DEAD, why(ea));
        addCardToZone("Xenagos, the Reveler", ea, ZoneType.Hand);
        AssertJUnit.assertEquals("a planeswalker in a's own hand", LookaheadSearch.DFU_DEAD, why(ea));
        AssertJUnit.assertTrue(droppedLive(e, ea, AiFixes.Mode.ON));
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
