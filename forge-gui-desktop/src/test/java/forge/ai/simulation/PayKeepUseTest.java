package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.zone.ZoneType;

/**
 * sdcc-paykeep-1117 (solver key {@code payKeepUse}; off by default): the payment search also taps last the sources that
 * have another use in play. The owner's kill at his own turn 4 of session 10b592b8 (dd126d): a ritual paid with the
 * tri-land and the Island, the pile spell, then the colourless land's draw (which needs exactly seven cards in hand), a
 * free draw-two returning two Islands, a free mana artifact and the creature that wins on a small library. Every
 * earlier payment order tapped the colourless land for the ritual's generic mana. Card names are test data only.
 */
public class PayKeepUseTest extends AITest {

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        kv.put("payKeepUse", on ? "true" : "false");
        kv.put("paySearch", "true");
        kv.put("planOrder", "7");
        kv.put("horizon", "1"); // this turn's kill only (the opponent's board in his game made passing the turn lose)
        kv.put("hideOwnLibrary", "true");
        kv.put("engine", "false"); // the width search and its verification only
        kv.put("defense", "false");
        kv.put("act", "false");
        kv.put("budgetMs", "60000");
        kv.put("planMs", "20000");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    static final String LIBRARY = "Gush;Overgrown Tomb;Wishclaw Talisman;Urborg, Tomb of Yawgmoth;Baleful Strix;Collective Brutality;"
            + "Mind Stone;Swamp;Island;Jace, Wielder of Mysteries;Raffine's Tower;Chromatic Star;Counterspell;Swamp;Swamp;"
            + "Scalding Tarn;Thassa's Oracle;Island;Mystical Tutor;Lotus Petal;Misty Rainforest;Jace, the Mind Sculptor;Stern Scolding";

    static final String KILL = ForcedWinSolverTest.frame(
            "Beseech the Mirror;Doomsday;Cabal Ritual;Waterlogged Grove;Timetwister;Daze;Echo of Eons;Troll of Khazad-dûm;Remand",
            "Library of Alexandria;Xander's Lounge;Island", 9, "", "Forest", 20, "human")
            .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + LIBRARY)
            .replace("humangraveyard=\n", "humangraveyard=Verdant Catacombs;Bolas's Citadel;Memory Jar;Chart a Course;Darkslick Shores\n");

    private static ForcedWinSolver.Proposal best(final List<ForcedWinSolver.Proposal> props) {
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        return best;
    }

    @Test
    public void theRitualIsPaidWithoutTheLandThatDraws() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", KILL);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        final ForcedWinSolver.Proposal pOff = best(off.propose(g0));
        Assert.assertTrue(pOff == null || !pOff.proven(1), "key off: no proven line, as before: " + (pOff == null ? off.summary() : pOff.text));
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", KILL);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        Assert.assertEquals(p.winTurn, 0, p.text);
        boolean drew = false, floatedIt = false;
        for (Step st : ((Line) p.line).steps) {
            drew |= st.kind == ForcedWinModel.AbilKind.ACTIVATE && "Library of Alexandria".equals(st.hostName);
            floatedIt |= st.kind == ForcedWinModel.AbilKind.FLOAT && "Library of Alexandria".equals(st.hostName);
        }
        Assert.assertTrue(drew && !floatedIt, p.text);
        Assert.assertEquals(g.getPlayers().get(0).getCardsIn(ZoneType.Hand).size(), 9, "nothing was played in the live game");
    }
}
