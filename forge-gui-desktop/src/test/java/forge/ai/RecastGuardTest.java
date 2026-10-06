package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * The "Moving spell to Hand" loop (lane forge-spellhand-loop-1006): d1-fb-1004 voided 13 Default-vs-Default games as
 * OutOfMemoryError, all with Displacer Kitten and Venser, Shaper Savant in one deck (DEV-719305189-F-o4-r0-s1, seed
 * 719490653, loops at turn 19 with Mana Crypt). The smallest repro: the AI controls Kitten and Venser and holds Mana
 * Crypt in its first main phase. It casts Mana Crypt; Kitten's cast trigger blinks Venser; Venser's mandatory enter
 * trigger returns the AI's own Mana Crypt from the stack to its hand (not the opponent's creature); the AI casts it
 * again. Upstream Forge has no bound on that, so the step never ends. {@link AiRecastGuard} stops the recast at the
 * limit, and the game moves on.
 */
public class RecastGuardTest extends AITest {

    /** The repro position. Returns the AI's Mana Crypt (in hand). */
    private Card setup(Game game) {
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        addCard("Displacer Kitten", ai);
        addCard("Venser, Shaper Savant", ai);
        addCards("Island", 2, ai);
        addCard("Runeclaw Bear", opp);
        addCards("Swamp", 2, opp);
        Card crypt = addCardToZone("Mana Crypt", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        return crypt;
    }

    /** Run the game loop until the AI's first main phase ends (or the step budget runs out). */
    private int runMain1(Game game, int maxSteps) {
        int steps = 0;
        while (!game.isGameOver() && steps < maxSteps && game.getPhaseHandler().is(PhaseType.MAIN1)) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
        }
        return steps;
    }

    @Test
    public void testRecastLoopStopsAtTheLimit() {
        AiRecastGuard.setLimit(AiRecastGuard.DEFAULT_LIMIT);
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card crypt = setup(game);

        runMain1(game, 5000);

        AssertJUnit.assertFalse("the AI's first main phase must end (it looped forever before the guard)",
                game.getPhaseHandler().is(PhaseType.MAIN1) && game.getPhaseHandler().getPlayerTurn() == ai);
        AssertJUnit.assertEquals("Mana Crypt is cast exactly the limit, then the guard stops the recast",
                AiRecastGuard.DEFAULT_LIMIT, AiRecastGuard.castsThisTurn(ai, crypt.getId()));
        AssertJUnit.assertTrue("Venser returned the AI's own Mana Crypt each time (it stays in hand)",
                ai.getCardsIn(ZoneType.Hand).anyMatch(c -> c.getId() == crypt.getId()));
    }

    @Test
    public void testWithoutTheGuardTheLoopNeverEnds() {
        AiRecastGuard.setLimit(0);
        try {
            Game game = initAndCreateGame();
            Player ai = game.getPlayers().get(1);
            Card crypt = setup(game);

            runMain1(game, 1500);

            AssertJUnit.assertTrue("upstream behaviour: still in the AI's first main phase after the step budget",
                    game.getPhaseHandler().is(PhaseType.MAIN1) && game.getPhaseHandler().getPlayerTurn() == ai);
            AssertJUnit.assertTrue("upstream behaviour: Mana Crypt recast far past the limit",
                    AiRecastGuard.castsThisTurn(ai, crypt.getId()) > 5 * AiRecastGuard.DEFAULT_LIMIT);
        } finally {
            AiRecastGuard.setLimit(AiRecastGuard.DEFAULT_LIMIT);
        }
    }
}
