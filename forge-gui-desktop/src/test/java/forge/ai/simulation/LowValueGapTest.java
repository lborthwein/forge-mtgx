package forge.ai.simulation;

import static org.testng.Assert.assertEquals;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * low-value-gap-1113 and noop-target-1113 (look-ahead keys lowFloor / lowGap and noopTarget, all off by default), at the
 * two decisions of the owner's replay NA08, turn 7. There the seat, in a position every candidate valued at about 0.011,
 * (b) departed from the policy's pass to a removal spell by a gap of 0.0011, aimed at the opponent's only creature, which
 * the spell cannot destroy (any creature is a legal target; the mana-value test is the spell's resolution condition).
 * Card facts are used as tests only.
 */
public class LowValueGapTest extends SimulationTest {

    private static boolean[][] all(int n, int k) {
        final boolean[][] f = new boolean[n][k];
        for (boolean[] r : f) {
            java.util.Arrays.fill(r, true);
        }
        return f;
    }

    private static int choose(double[][] v, double floor, double gap) {
        return LookaheadSearch.chooseAnytime(v, all(v.length, 8), new boolean[] {true, true}, v.length, 8, 1.645, 0, Double.NaN,
                null, 0.0, null, floor, gap);
    }

    /** The recorded decision: the policy's pass 0.0111 and the removal spell 0.0122, the worlds in agreement. */
    private static final double[][] NA08 = {{.0100, .0115, .0108, .0120, .0105, .0118, .0112, .0110},
        {.0111, .0126, .0119, .0131, .0116, .0129, .0123, .0121}};

    @Test
    public void offTheTestPassesAGapOfAThousandth() {
        assertEquals(choose(NA08, 0, 0), 1, "the departure as recorded: the paired test has no minimum gap");
        assertEquals(LookaheadSearch.chooseAnytime(NA08, all(2, 8), new boolean[] {true, true}, 2, 8, 1.645, 0, Double.NaN), 1);
    }

    @Test
    public void underTheFloorADepartureNeedsTheGap() {
        assertEquals(choose(NA08, 0.10, 0.02), 0, "0.0122 against 0.0111 in a lost position: the policy's answer stays");
        // a real difference under the floor still departs
        final double[][] outs = {{.019, .020, .018, .021, .019, .020, .018, .021}, {.050, .052, .049, .051, .050, .052, .049, .051}};
        assertEquals(choose(outs, 0.10, 0.02), 1, "0.050 against 0.019: a gap above 0.02");
        // above the floor nothing changes: the same small gap departs as before
        final double[][] high = {{.500, .515, .508, .520, .505, .518, .512, .510}, {.501, .516, .509, .521, .506, .519, .513, .511}};
        assertEquals(choose(high, 0.10, 0.02), 1, "a candidate valued 0.51 is not under the floor");
        assertEquals(choose(high, 0, 0), 1);
    }

    private SpellAbility spell(final Game game, final Player a, final String name) {
        final Card c = addCardToZone(name, a, ZoneType.Hand);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        return sa;
    }

    @Test
    public void aTargetTheSpellsOwnConditionMakesANoOpIsOne() {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        addCard("Swamp", a);
        final Card big = addCard("Atraxa, Grand Unifier", b), small = addCard("Grizzly Bears", b);
        game.getAction().checkStateEffects(true);
        Assert.assertTrue(a.getController() instanceof PlayerControllerAi);
        final SpellAbility push = spell(game, a, "Fatal Push");
        // both are legal targets of the spell
        Assert.assertTrue(push.canTarget(big));
        Assert.assertTrue(push.canTarget(small));
        Assert.assertTrue(LookaheadSearch.noopTarget(push, a, "C" + big.getId()), "mana value 7: the condition fails, nothing happens");
        Assert.assertFalse(LookaheadSearch.noopTarget(push, a, "C" + small.getId()), "mana value 2: destroyed");
        // a removal spell with no resolution condition is never one; nor is a missing or non-card target
        final SpellAbility murder = spell(game, a, "Murder");
        Assert.assertFalse(LookaheadSearch.noopTarget(murder, a, "C" + big.getId()));
        Assert.assertFalse(LookaheadSearch.noopTarget(push, a, null));
        Assert.assertFalse(LookaheadSearch.noopTarget(push, a, "P1"));
        Assert.assertFalse(LookaheadSearch.noopTarget(push, a, "N"));
        Assert.assertFalse(LookaheadSearch.noopTarget(null, a, "C" + big.getId()));
        // the check wrote nothing onto the live ability
        Assert.assertTrue(push.getTargets().isEmpty(), String.valueOf(push.getTargets()));
    }
}
