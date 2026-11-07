package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane next-turn-1107: a followed line's put-back of cards it did not know when it was planned (putBack[-1, -1]) was
 * answered by Forge AI, which put back the line's own remaining pieces (owner dd3 T5: the second draw-3-put-back-2 returned
 * Thassa's Oracle and Lotus Petal, the line broke at "missing: Lotus Petal" in every world). Key off: the follower has no
 * answer. Key on: it puts back cards the rest of the line does not use.
 */
public class PutBackKeepTest extends SimulationTest {
    private static ForcedWinPlanner.Step step(final AbilKind kind, final Card c) {
        final ForcedWinModel.ACard a = new ForcedWinModel.ACard();
        a.id = c.getId();
        a.name = c.getName();
        return new ForcedWinPlanner.Step(kind, a, null, -1);
    }

    private static ForcedWinSolver.Follower follower(final ForcedWinPlanner.Step putBack, final ForcedWinPlanner.Step petal,
            final ForcedWinPlanner.Step oracle, final boolean keep) {
        final ForcedWinPlanner.Line line = new ForcedWinPlanner.Line(List.of(putBack, petal, oracle), List.of(), 0, 20, 0, 0,
                List.of(), "test");
        final ForcedWinSolver.Follower f = new ForcedWinSolver.Follower(line);
        f.putBackKeep = keep;
        f.cursor = 1; // the put-back step is resolving; Petal and Oracle are still to come
        f.executed.put(putBack.hostId, putBack);
        return f;
    }

    @Test
    public void unnamedPutBackAvoidsTheLinesCards() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        final Card brainstorm = addCardToZone("Brainstorm", me, ZoneType.Stack);
        final Card oracle = addCardToZone("Thassa's Oracle", me, ZoneType.Hand);
        final Card petal = addCardToZone("Lotus Petal", me, ZoneType.Hand);
        final Card island = addCardToZone("Island", me, ZoneType.Hand);
        final Card swamp = addCardToZone("Swamp", me, ZoneType.Hand);
        final ForcedWinPlanner.Step pb = step(AbilKind.SPELL, brainstorm);
        pb.putBack.add(-1);
        pb.putBack.add(-1);
        final ForcedWinPlanner.Step ps = step(AbilKind.SPELL, petal);
        final ForcedWinPlanner.Step os = step(AbilKind.SPELL, oracle);
        // Forge offers the hand with the line's pieces first, as the dd3 worlds did
        final CardCollection hand = new CardCollection(List.of(oracle, petal, island, swamp));

        final ForcedWinSolver.Follower off = follower(pb, ps, os, false);
        AssertJUnit.assertNull("key off: no answer, Forge AI chooses", off.pick(brainstorm.getFirstSpellAbility(), hand));

        final ForcedWinSolver.Follower on = follower(pb, ps, os, true);
        final Card first = on.pick(brainstorm.getFirstSpellAbility(), hand);
        AssertJUnit.assertNotNull("key on: answered", first);
        AssertJUnit.assertTrue("key on: not a card the line still uses", first == island || first == swamp);
        final CardCollection rest = new CardCollection(hand);
        rest.remove(first);
        final Card second = on.pick(brainstorm.getFirstSpellAbility(), rest);
        AssertJUnit.assertTrue("key on: the second is not the line's either", second == island || second == swamp);
        AssertJUnit.assertTrue(second != first);
        AssertJUnit.assertEquals(2, on.putBackKept);

        // a pile or tutor pick is unchanged by the key: an unnamed slot there is still Forge AI's
        final ForcedWinPlanner.Step tutor = step(AbilKind.SPELL, brainstorm);
        tutor.picks.add(-1);
        final ForcedWinSolver.Follower t = follower(tutor, ps, os, true);
        AssertJUnit.assertNull("picks: unchanged", t.pick(brainstorm.getFirstSpellAbility(), hand));
    }
}
