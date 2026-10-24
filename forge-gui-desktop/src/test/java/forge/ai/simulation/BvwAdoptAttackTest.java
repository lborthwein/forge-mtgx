package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;

/**
 * Lane bvw-step1-build-1024: the generator's adopted line whose first step is our attack (lane bvw-label-audit-1024,
 * DIAGNOSIS cases B and O1). Off (the default and the step-0 generator): {@code decide} drops the line at adoption and
 * Forge AI plays the turn. On ({@code adoptAttack=true}): the line is kept, the seat passes to combat, and
 * {@code answerAttack} declares the alpha strike. Fixture position only.
 */
public class BvwAdoptAttackTest extends SimulationTest {

    /** Our MAIN1, a lethal attacker against an empty board (their life 6, no cards in hand, no mana). */
    static Game lethalBoard() {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("", "Craw Wurm;Forest", 20, "", "",
                6, "human"));
    }

    static ForcedWinSolver solver(final Player me, final boolean adoptAttack) {
        final ForcedWinSolver.Config c = HonestGeneratorTest.config();
        c.adoptAttack = adoptAttack;
        return HonestGenerator.solver(me, c);
    }

    @Test(timeOut = 300000)
    public void anAttackFirstLineIsDroppedWithoutTheFlag() {
        final Game g = lethalBoard();
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, false);
        final ForcedWinSolver.Decision d = s.decide(g);
        Assert.assertEquals(s.taken, 1, "the solver must take the attack line: " + s.summary());
        Assert.assertFalse(d.owned, "the step-0 generator drops the attack-first line at adoption");
        Assert.assertFalse(s.lineActive());
        final Combat combat = new Combat(me);
        Assert.assertFalse(s.answerAttack(me, combat), "no line, so the alpha strike is not declared");
        Assert.assertTrue(combat.getAttackers().isEmpty());
    }

    @Test(timeOut = 300000)
    public void anAttackFirstLineIsDeclaredWithTheFlag() {
        final Game g = lethalBoard();
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, true);
        final ForcedWinSolver.Decision d = s.decide(g);
        Assert.assertEquals(s.taken, 1, s.summary());
        Assert.assertTrue(d.owned && d.action == null, "the seat passes priority to combat, keeping the line");
        Assert.assertTrue(s.lineActive());
        // later priorities before combat keep passing (the follower waits for the attack)
        final ForcedWinSolver.Decision again = s.decide(g);
        Assert.assertTrue(again.owned && again.action == null);
        final Combat combat = new Combat(me);
        Assert.assertTrue(s.answerAttack(me, combat), "the adopted line declares its alpha strike");
        boolean wurm = false;
        for (Card c : combat.getAttackers()) {
            wurm |= c.getName().equals("Craw Wurm");
        }
        Assert.assertTrue(wurm, "Craw Wurm attacks");
    }

    @Test(timeOut = 300000)
    public void theFlaggedLinePlaysTheLethalAttack() {
        // end to end: the honest generator seat with the flag kills on this turn through its own declaration
        final Game g = lethalBoard();
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, true);
        final Player opp = g.getPlayers().get(1);
        me.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public java.util.List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
                final ForcedWinSolver.Decision dd = s.decide(getGame());
                if (dd.owned) {
                    return dd.action == null ? null : java.util.List.of(dd.action);
                }
                return super.chooseSpellAbilityToPlay();
            }

            @Override
            void declareFirst(final Player attacker, final Combat combat) {
                if (!s.answerAttack(attacker, combat)) {
                    super.declareFirst(attacker, combat);
                }
            }
        });
        ForcedWinSolverTest.play(g, 7);
        Assert.assertTrue(g.isGameOver() && opp.hasLost(), "the alpha strike kills on turn 7: " + s.summary());
        Assert.assertEquals(g.getPhaseHandler().getTurn(), 7);
    }
}
