package forge.ai.simulation;

import java.util.List;
import java.util.function.Predicate;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPart;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The dead alternative-cost guard (Config.deadAltCost, lane evoke-empty-board-1111). The owner's report of
 * 2026-11-12T03:09Z: on turn 1 the live Hard seat exiled a red card to evoke Fury against an empty board. Both triggers
 * resolved with nothing to hit, so two cards bought nothing. The search departed from the policy's land because the value
 * leaf scored the evoke line higher in all 8 worlds. The class: a cost that spends cards beyond the spell (evoke, which
 * sacrifices the body; exiling or discarding a card from hand) is priced by the effect it buys on this board, and a cast
 * whose every such effect Forge's AI declines is not a departure candidate.
 */
public class LookaheadDeadAltCostTest extends SimulationTest {

    /** Seat a's first main phase. a: {@code aField} on the battlefield, {@code aHand} in hand; b: {@code bField}. */
    private Game board(String[] aField, String[] aHand, String[] bField) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? (p == a ? "Mountain" : "Island") : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        for (String n : aField) {
            addCard(n, a);
        }
        for (String n : bField) {
            addCard(n, b);
        }
        for (String n : aHand) {
            addCardToZone(n, a, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** The reported turn 1: no permanents on either side; Fury, a red card to exile for it, and a land in hand. */
    private Game turnOne(String... bField) {
        return board(new String[0], new String[] {"Fury", "Lightning Bolt", "Plains"}, bField);
    }

    private static final Predicate<SpellAbility> EVOKE = SpellAbility::isEvoke;
    private static final Predicate<SpellAbility> DISCARDS = sa -> {
        if (sa.getPayCosts() == null) {
            return false;
        }
        for (CostPart p : sa.getPayCosts().getCostParts()) {
            if (p instanceof CostDiscard) {
                return true;
            }
        }
        return false;
    };

    /** The candidate (as the search enumerates them) for {@code name} that matches {@code which}, or null. */
    private static SpellAbility cand(Player p, String name, Predicate<SpellAbility> which) {
        for (SpellAbility sa : new SpellAbilityPicker(p).getCandidateSpellsAndAbilities()) {
            if (sa.getHostCard() != null && sa.getHostCard().getName().equals(name) && which.test(sa)) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        return null;
    }

    private static LookaheadSearch search(AiFixes.Mode mode) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 4;
        c.breadth = 4;
        c.horizonTurns = 2;
        c.threads = 1;
        c.seed = 1_111_000_003L;
        c.departZ = 1.645;
        c.deadAltCost = mode;
        return new LookaheadSearch(c);
    }

    private static boolean hasFury(List<LookaheadSearch.Cand> cands) {
        for (LookaheadSearch.Cand c : cands) {
            if (c.label.startsWith("Fury")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.deadAltCost);
        AssertJUnit.assertFalse(c.toJson().has("deadAltCost"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("deadAltCost"));
        } finally {
            s.shutdown();
        }
        c.deadAltCost = AiFixes.Mode.SHADOW;
        AssertJUnit.assertEquals("shadow", c.toJson().get("deadAltCost").getAsString());
        RlSearch.Config r = new RlSearch.Config();
        AssertJUnit.assertEquals("off", r.deadAltCost);
        AssertJUnit.assertFalse("policy-seat spec JSON unchanged while off", r.toJson().has("deadAltCost"));
    }

    @Test
    public void evokeIntoAnEmptyBoardIsDead() {
        Game game = turnOne();
        Player a = game.getPlayers().get(0);
        SpellAbility evoke = cand(a, "Fury", EVOKE);
        AssertJUnit.assertNotNull("the evoke line is a legal candidate on turn 1", evoke);
        AssertJUnit.assertTrue("two cards for an ETB with nothing to hit", LookaheadSearch.deadAltCost(evoke, a));
        AssertJUnit.assertFalse(LookaheadSearch.deadAltCost(null, a));
    }

    @Test
    public void evokeWithAnOpposingTargetIsAlive() {
        Game game = turnOne("Grizzly Bears");
        Player a = game.getPlayers().get(0);
        AssertJUnit.assertFalse("the ETB kills an opposing creature", LookaheadSearch.deadAltCost(cand(a, "Fury", EVOKE), a));
    }

    @Test
    public void evokeWithOnlyOwnTargetsIsDead() {
        Game game = board(new String[] {"Grizzly Bears"}, new String[] {"Fury", "Lightning Bolt"}, new String[0]);
        Player a = game.getPlayers().get(0);
        AssertJUnit.assertTrue("damage that can only hit the caster's own creature", LookaheadSearch.deadAltCost(cand(a, "Fury", EVOKE), a));
    }

    @Test
    public void evokeWithoutTargetingIsLeftToTheSearch() {
        Game game = board(new String[] {"Island", "Island", "Island"}, new String[] {"Mulldrifter"}, new String[0]);
        Player a = game.getPlayers().get(0);
        SpellAbility evoke = cand(a, "Mulldrifter", EVOKE);
        AssertJUnit.assertNotNull(evoke);
        AssertJUnit.assertFalse("an untargeted ETB (draw two) is not priced here", LookaheadSearch.deadAltCost(evoke, a));
    }

    @Test
    public void aBodyThatStaysIsNotThisGuardsBusiness() {
        Game game = board(new String[] {"Mountain", "Mountain", "Mountain", "Mountain", "Mountain"},
                new String[] {"Fury"}, new String[0]);
        Player a = game.getPlayers().get(0);
        SpellAbility hard = cand(a, "Fury", sa -> !sa.isEvoke());
        AssertJUnit.assertNotNull("the hard cast is a candidate with five lands", hard);
        AssertJUnit.assertFalse(LookaheadSearch.deadAltCost(hard, a));
    }

    @Test
    public void aDiscardCostIsPricedTheSameWay() {
        Game own = board(new String[] {"Mountain", "Grizzly Bears"}, new String[] {"Lightning Axe", "Island"}, new String[0]);
        Player a = own.getPlayers().get(0);
        SpellAbility discard = cand(a, "Lightning Axe", DISCARDS);
        AssertJUnit.assertNotNull("the discard variant is a candidate", discard);
        AssertJUnit.assertTrue("a card discarded to hit only the caster's own creature", LookaheadSearch.deadAltCost(discard, a));

        Game opp = board(new String[] {"Mountain"}, new String[] {"Lightning Axe", "Island"}, new String[] {"Grizzly Bears"});
        Player a2 = opp.getPlayers().get(0);
        AssertJUnit.assertFalse("an opposing creature to kill", LookaheadSearch.deadAltCost(cand(a2, "Lightning Axe", DISCARDS), a2));
    }

    @Test
    public void onDropsTheEvokeCandidateAndOffKeepsIt() {
        Game g1 = turnOne();
        Player a1 = g1.getPlayers().get(0);
        LookaheadSearch off = search(AiFixes.Mode.OFF);
        Game g2 = turnOne();
        Player a2 = g2.getPlayers().get(0);
        LookaheadSearch on = search(AiFixes.Mode.ON);
        try {
            AssertJUnit.assertTrue("OFF: the evoke line is searched, as it was live", hasFury(off.enumerate(g1, a1, null, 1L, null)));
            String before = LookaheadSearch.fingerprint(g2);
            AssertJUnit.assertFalse("ON: the evoke line is not a candidate", hasFury(on.enumerate(g2, a2, null, 1L, null)));
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g2));
            AssertJUnit.assertEquals(1, on.getStats().deadAltCostDropped);
            AssertJUnit.assertEquals(1, on.getStats().deadAltCostDecisions);
            AssertJUnit.assertEquals("on", on.getStats().toJson().getAsJsonObject("deadAltCost").get("mode").getAsString());
            AssertJUnit.assertFalse(off.getStats().toJson().has("deadAltCost"));
        } finally {
            off.shutdown();
            on.shutdown();
        }
    }

    @Test
    public void shadowKeepsTheCandidateAndCountsIt() {
        Game game = turnOne();
        Player a = game.getPlayers().get(0);
        LookaheadSearch s = search(AiFixes.Mode.SHADOW);
        try {
            List<LookaheadSearch.Cand> cands = s.enumerate(game, a, null, 1L, null);
            AssertJUnit.assertTrue(hasFury(cands));
            AssertJUnit.assertEquals(1, s.getStats().deadAltCostCands);
            AssertJUnit.assertEquals(0, s.getStats().deadAltCostDropped);
            AssertJUnit.assertEquals(1, s.deadAltCostKeys.size());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void onKeepsTheEvokeLineWithARealTarget() {
        Game game = turnOne("Grizzly Bears");
        Player a = game.getPlayers().get(0);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            AssertJUnit.assertTrue(hasFury(s.enumerate(game, a, null, 1L, null)));
            AssertJUnit.assertEquals(0, s.getStats().deadAltCostDropped);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void forgesOwnAnswerIsNeverDropped() {
        Game game = turnOne();
        Player a = game.getPlayers().get(0);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            List<LookaheadSearch.Cand> cands = s.enumerate(game, a, cand(a, "Fury", EVOKE), 1L, null);
            AssertJUnit.assertTrue(cands.get(0).isDefault);
            AssertJUnit.assertTrue(cands.get(0).label.startsWith("Fury"));
            AssertJUnit.assertEquals(1, s.getStats().deadAltCostForge);
            AssertJUnit.assertEquals(0, s.getStats().deadAltCostDropped);
        } finally {
            s.shutdown();
        }
    }
}
