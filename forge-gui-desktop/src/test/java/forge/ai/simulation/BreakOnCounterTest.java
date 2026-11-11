package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Defect: a live followed line whose spell was countered is played on to its end. Seen live: Doomsday countered by
 * Mana Drain, then the rest of the line (Grove, Jace, Gush) was spent for nothing, with no "broken" receipt and no
 * re-plan. Expected with plans.solverCfg breakOnCounter=true: once the stack is empty and the line's last spell did not
 * resolve, the line is broken, so the solver plays no further step of it. Uses only APIs of the baseline tree (the key
 * goes in as a spec string): on the baseline, keyOnStopsAfterCounter fails by assertion because the follower plays on.
 */
public class BreakOnCounterTest extends SimulationTest {

    private Game game;
    private Player me;
    private Card first, second;

    /** A two-spell line on a real game: Divination, then Opt (no targets), with four Islands. */
    private Line line() {
        game = initAndCreateGame();
        me = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 4; i++) {
            addCard("Island", me);
        }
        first = addCardToZone("Divination", me, ZoneType.Hand);
        second = addCardToZone("Opt", me, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final List<Step> steps = new ArrayList<>();
        for (Card c : new Card[] {first, second}) {
            SpellAbility sa = null;
            for (SpellAbility x : c.getAllPossibleAbilities(me, true)) {
                sa = x;
            }
            AssertJUnit.assertNotNull(c.getName() + " has a spell ability", sa);
            steps.add(Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), sa.getDescription(),
                    sa.getPayCosts() == null ? "" : sa.getPayCosts().toString(), true));
        }
        return new Line(steps, List.of(), 0, 20, 0, 0, List.of(), "test");
    }

    /** The live seat's solver follows the line; its first spell is cast and countered; the solver's next decision. */
    private ForcedWinSolver.Decision afterCounter(final boolean keyOn) {
        final Line l = line();
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("act", "false"); // a follower only, as the plan search's solver
        if (keyOn) {
            cfg.addProperty("breakOnCounter", "true");
        }
        final SolverDriver driver = new SolverDriver(me, SolverDriver.config(cfg));
        final ForcedWinSolver solver = driver.solver();
        solver.knownTopCount(game); // the seat's first read, as at a plan point (starts the solver's live watch)
        solver.adopt(l);
        final ForcedWinSolver.Decision d1 = solver.decide(game);
        AssertJUnit.assertNotNull("the line's first spell is played", d1.action);
        AssertJUnit.assertEquals(first.getId(), d1.action.getHostCard().getId());
        // paid with three Islands, then countered: it leaves the stack without resolving
        int tapped = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (tapped < 3 && c.isLand()) {
                c.tap(true, null, null);
                tapped++;
            }
        }
        game.getAction().moveTo(ZoneType.Graveyard, first, null, null);
        return solver.decide(game);
    }

    private boolean playsSecond(final ForcedWinSolver.Decision d) {
        return d.action != null && d.action.getHostCard() != null && d.action.getHostCard().getId() == second.getId();
    }

    @Test
    public void keyOffPlaysOnAfterCounter() {
        AssertJUnit.assertTrue("key off: the follower plays the rest of the countered line (unchanged)",
                playsSecond(afterCounter(false)));
    }

    @Test
    public void keyOnStopsAfterCounter() {
        AssertJUnit.assertFalse("key on: a line whose spell was countered is not played on",
                playsSecond(afterCounter(true)));
    }
}
