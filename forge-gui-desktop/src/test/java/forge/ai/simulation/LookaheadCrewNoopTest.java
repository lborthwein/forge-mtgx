package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The crew no-op guard (Config.crewNoop, lane misplays-1005). The owner's report of 2026-10-05T05:15Z: in the owner's
 * second main phase, after he did not attack, the look-ahead seat crewed Smuggler's Copter twice. Forge's own answer was
 * to pass; a Vehicle crewed when it can no longer attack or block this turn does nothing before the effect ends.
 * OFF (the default) keeps the candidate; ON drops it; SHADOW keeps it and counts it.
 */
public class LookaheadCrewNoopTest extends SimulationTest {

    /** Player a controls Smuggler's Copter and two Grizzly Bears; it is {@code active}'s turn at {@code phase}. */
    private Game board(PhaseType phase, boolean aActive, String vehicle) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(phase, aActive ? a : b);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Forest" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        addCard(vehicle, a);
        for (Card c : new Card[] {addCard("Grizzly Bears", a), addCard("Grizzly Bears", a)}) {
            c.setSickness(false);
        }
        addCard("Forest", a);
        addCard("Swamp", b);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private Game board(PhaseType phase, boolean aActive) {
        return board(phase, aActive, "Smuggler's Copter");
    }

    private static LookaheadSearch search(AiFixes.Mode mode) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_000_005L;
        c.crewNoop = mode;
        return new LookaheadSearch(c);
    }

    private static SpellAbility crewOf(Player p, String vehicle) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(vehicle)) {
                for (SpellAbility sa : c.getSpellAbilities()) {
                    if (sa.isCrew()) {
                        sa.setActivatingPlayer(p);
                        return sa;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasCrew(List<LookaheadSearch.Cand> cands) {
        for (LookaheadSearch.Cand c : cands) {
            if (c.label.contains(":: Crew ")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.crewNoop);
        AssertJUnit.assertFalse(c.toJson().has("crewNoop"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("crewNoop"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void detectorByStep() {
        // The opponent's turn: a crewed Vehicle can still block until blockers are declared.
        for (PhaseType p : new PhaseType[] {PhaseType.UPKEEP, PhaseType.MAIN1, PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS}) {
            Game g = board(p, false);
            Player a = g.getPlayers().get(0);
            AssertJUnit.assertFalse("opponent's " + p + ": may still block", LookaheadSearch.crewNoop(crewOf(a, "Smuggler's Copter"), a));
        }
        for (PhaseType p : new PhaseType[] {PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.COMBAT_END, PhaseType.MAIN2, PhaseType.END_OF_TURN}) {
            Game g = board(p, false);
            Player a = g.getPlayers().get(0);
            AssertJUnit.assertTrue("opponent's " + p + ": can no longer block", LookaheadSearch.crewNoop(crewOf(a, "Smuggler's Copter"), a));
        }
        // Its own turn: it can still attack until attackers are declared.
        for (PhaseType p : new PhaseType[] {PhaseType.UPKEEP, PhaseType.MAIN1, PhaseType.COMBAT_BEGIN}) {
            Game g = board(p, true);
            Player a = g.getPlayers().get(0);
            AssertJUnit.assertFalse("own " + p + ": may still attack", LookaheadSearch.crewNoop(crewOf(a, "Smuggler's Copter"), a));
        }
        for (PhaseType p : new PhaseType[] {PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_END, PhaseType.MAIN2, PhaseType.END_OF_TURN}) {
            Game g = board(p, true);
            Player a = g.getPlayers().get(0);
            AssertJUnit.assertTrue("own " + p + ": can no longer attack", LookaheadSearch.crewNoop(crewOf(a, "Smuggler's Copter"), a));
        }
        Game g = board(PhaseType.MAIN2, false);
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertFalse(LookaheadSearch.crewNoop(null, a));
        addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        for (Card c : a.getCardsIn(ZoneType.Hand)) {
            AssertJUnit.assertFalse("not a Crew ability", LookaheadSearch.crewNoop(c.getFirstSpellAbility(), a));
        }
    }

    @Test
    public void crewTriggersAreLeftToTheSearch() {
        // Ghost Ark: "Whenever Ghost Ark becomes crewed ..." -- crewing does something even after combat.
        Game g = board(PhaseType.MAIN2, false, "Ghost Ark");
        Player a = g.getPlayers().get(0);
        AssertJUnit.assertFalse(LookaheadSearch.crewNoop(crewOf(a, "Ghost Ark"), a));
        // A Crewed trigger anywhere in play (Gearshift Ace: "Whenever Gearshift Ace crews a Vehicle ...").
        Game g2 = board(PhaseType.MAIN2, false);
        Player a2 = g2.getPlayers().get(0);
        addCard("Gearshift Ace", a2);
        AssertJUnit.assertFalse(LookaheadSearch.crewNoop(crewOf(a2, "Smuggler's Copter"), a2));
    }

    @Test
    public void offKeepsOnDropsShadowCounts() {
        Game g = board(PhaseType.MAIN2, false);
        Player a = g.getPlayers().get(0);
        LookaheadSearch off = search(AiFixes.Mode.OFF), on = search(AiFixes.Mode.ON), sh = search(AiFixes.Mode.SHADOW);
        try {
            String before = LookaheadSearch.fingerprint(g);
            AssertJUnit.assertTrue(hasCrew(off.enumerate(g, a, null, 1L, null)));
            AssertJUnit.assertFalse(hasCrew(on.enumerate(g, a, null, 1L, null)));
            AssertJUnit.assertTrue(hasCrew(sh.enumerate(g, a, null, 1L, null)));
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g));
            AssertJUnit.assertEquals(1, on.getStats().crewNoopDropped);
            AssertJUnit.assertEquals(1, on.getStats().crewNoopDecisions);
            AssertJUnit.assertEquals(1, sh.getStats().crewNoopCands);
            AssertJUnit.assertEquals(0, sh.getStats().crewNoopDropped);
            AssertJUnit.assertEquals(1, sh.crewNoopKeys.size());
            AssertJUnit.assertEquals(0, off.getStats().crewNoopCands);
            AssertJUnit.assertTrue(sh.getStats().toJson().has("crewNoop"));
        } finally {
            off.shutdown();
            on.shutdown();
            sh.shutdown();
        }
    }

    @Test
    public void beforeCombatTheCrewStaysACandidate() {
        Game g = board(PhaseType.MAIN1, true);
        Player a = g.getPlayers().get(0);
        LookaheadSearch on = search(AiFixes.Mode.ON);
        try {
            AssertJUnit.assertTrue(hasCrew(on.enumerate(g, a, null, 1L, null)));
            AssertJUnit.assertEquals(0, on.getStats().crewNoopDropped);
        } finally {
            on.shutdown();
        }
    }

    @Test
    public void onNeverCrewsAfterCombatAndLeavesLiveUntouched() {
        Game g = board(PhaseType.MAIN2, false);
        Player a = g.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(g);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            List<SpellAbility> got = s.decide(ctrl, def);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g));
            AssertJUnit.assertFalse(got != null && !got.isEmpty() && got.get(0).isCrew());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void forgesOwnAnswerIsNeverDropped() {
        Game g = board(PhaseType.MAIN2, false);
        Player a = g.getPlayers().get(0);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            List<LookaheadSearch.Cand> cands = s.enumerate(g, a, crewOf(a, "Smuggler's Copter"), 1L, null);
            AssertJUnit.assertTrue(cands.get(0).isDefault);
            AssertJUnit.assertTrue(cands.get(0).label.contains(":: Crew "));
            AssertJUnit.assertEquals(0, s.getStats().crewNoopDropped);
            AssertJUnit.assertEquals(1, s.getStats().crewNoopForge);
        } finally {
            s.shutdown();
        }
    }
}
