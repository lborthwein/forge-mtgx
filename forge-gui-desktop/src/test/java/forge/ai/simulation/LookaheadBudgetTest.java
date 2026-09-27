package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The look-ahead's per-decision wall budget (interactive play): past the budget the search plays Forge AI's own
 * answer and counts the decision as capped; a budget that is not reached changes nothing; the live game is never
 * touched either way.
 */
public class LookaheadBudgetTest extends SimulationTest {

    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
            for (int i = 0; i < 4; i++) {
                addCard("Mountain", p);
                addCard("Forest", p);
            }
            addCard("Grizzly Bears", p);
            addCard("Hill Giant", p);
        }
        addCardToZone("Forest", a, ZoneType.Hand);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        addCardToZone("Hill Giant", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch search(long budgetMs) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 8;
        c.breadth = 4;
        c.horizonTurns = 2;
        c.threads = 2;
        c.seed = 719_000_001L;
        c.budgetMs = budgetMs;
        return new LookaheadSearch(c);
    }

    private static String label(List<SpellAbility> answer) {
        return answer == null || answer.isEmpty() ? "pass" : answer.get(0).getHostCard().getName() + "::" + answer.get(0).getDescription();
    }

    @Test
    public void overBudgetPlaysForgesAnswer() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = search(1);
        try {
            List<SpellAbility> answer = s.decide(ctrl, def);
            AssertJUnit.assertSame("capped search must play Forge's own answer", def, answer);
            AssertJUnit.assertEquals(1, s.getStats().searched);
            AssertJUnit.assertEquals(1, s.getStats().capped);
            AssertJUnit.assertEquals(0, s.getStats().departed);
            AssertJUnit.assertTrue(s.getStats().rolloutsAborted > 0);
            AssertJUnit.assertEquals(0, s.getStats().rolloutFailures);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void unreachedBudgetChangesNothing() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch none = search(0);
        LookaheadSearch wide = search(600_000);
        try {
            String x = label(none.decide(ctrl, def));
            String y = label(wide.decide(ctrl, def));
            AssertJUnit.assertEquals(x, y);
            AssertJUnit.assertEquals(0, none.getStats().capped);
            AssertJUnit.assertEquals(0, wide.getStats().capped);
            AssertJUnit.assertEquals(0, wide.getStats().rolloutsAborted);
            AssertJUnit.assertEquals(none.getStats().rollouts, wide.getStats().rollouts);
            AssertJUnit.assertEquals(none.getStats().steps, wide.getStats().steps);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        } finally {
            none.shutdown();
            wide.shutdown();
        }
    }
}
