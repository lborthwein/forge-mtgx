package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CubeComboPlayerController;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Correctness fixtures only; no fixture position or game is part of the natural-start pilot. */
public class HonestGeneratorTest extends SimulationTest {
    static ForcedWinSolver.Config config() {
        ForcedWinSolver.Config c = new ForcedWinSolver.Config();
        c.hideOwnLibrary = true;
        c.defense = false;
        c.threshold = 1.0;
        c.worlds = 8;
        return c;
    }

    @Test
    public void optInProfileAndDishonestOverridesRefuse() {
        Game g = initAndCreateGame();
        Player me = g.getPlayers().get(0);
        for (String profile : new String[] {"Default", "CubeCombo", "ForcedWin", "CubeComboSolver"}) {
            LobbyPlayerAi lobby = new LobbyPlayerAi("test", null);
            lobby.setAiProfile(profile);
            Assert.assertEquals(AiProfileUtil.getAIProp(lobby, AiProps.FORCED_WIN_HONEST), "false");
        }
        Assert.assertTrue(AiProfileUtil.isKnownProfile(AiProfileUtil.HONEST_FORCED_WIN_PROFILE));
        ForcedWinSolver s = HonestGenerator.solver(me, config());
        Assert.assertNotNull(s.redraw);
        Assert.assertTrue(s.config().honestProbes);
        for (String bad : new String[] {"hide", "exact", "memory", "defense", "act", "likely", "adversary", "threshold"}) {
            ForcedWinSolver.Config c = config();
            switch (bad) {
                case "hide": c.hideOwnLibrary = false; break;
                case "exact": c.exact = true; break;
                case "memory": c.frameMemory = true; break;
                case "defense": c.defense = true; break;
                case "act": c.act = false; break;
                case "likely": c.likely = true; break;
                case "adversary": c.adversary = false; break;
                case "threshold": c.threshold = 0.5; break;
                default: throw new AssertionError(bad);
            }
            Assert.expectThrows(IllegalArgumentException.class, () -> HonestGenerator.solver(me, c));
        }
    }

    @Test
    public void affordanceProbeRootIsBeliefSampledAndKeepsOnlyKnownOwnPrefix() {
        Game g = initAndCreateGame();
        Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, me);
        for (String name : new String[] {"Island", "Mountain", "Forest", "Plains", "Swamp", "Shock"}) {
            addCardToZone(name, me, ZoneType.Library);
        }
        for (int i = 0; i < 12; i++) addCardToZone("Craw Wurm", opp, ZoneType.Library);
        addCardToZone("Craw Wurm", opp, ZoneType.Hand);
        Assert.assertFalse(BeliefSampler.loadCube(BeliefSampler.LIVE_CUBE, BeliefSampler.LIVE_CUBE_SHA256, 8)
                .names.contains("Craw Wurm"), "fixture sentinel entered the pinned cube");
        ForcedWinSolver s = HonestGenerator.solver(me, config());
        List<Integer> ownIds = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) ownIds.add(c.getId());
        String before = LookaheadSearch.fingerprint(g);
        Game root = forge.bench.PlayerControllerBridge.isolated(g,
                () -> forge.util.IdScope.detached(() -> s.honestProbeRoot(g, ownIds.subList(0, 1))));
        Player cm = root.getPlayer(me.getId()), co = root.getPlayer(opp.getId());
        List<Integer> got = new ArrayList<>();
        for (Card c : cm.getCardsIn(ZoneType.Library)) got.add(c.getId());
        Assert.assertEquals(got.get(0), ownIds.get(0));
        Assert.assertEquals(Set.copyOf(got), Set.copyOf(ownIds));
        Assert.assertNotEquals(got, ownIds, "unknown suffix stayed in real order");
        for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library}) {
            Assert.assertEquals(co.getCardsIn(z).size(), opp.getCardsIn(z).size());
            for (Card c : co.getCardsIn(z)) Assert.assertNotEquals(c.getName(), "Craw Wurm");
        }
        Assert.assertEquals(LookaheadSearch.fingerprint(g), before);
    }

    @Test
    public void profileExecutesAnHonestOracleLine() {
        final String frame = ForcedWinSolverTest.frame("Thassa's Oracle", "Island;Island", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island");
        Game g = ForcedWinSolverTest.game(AiProfileUtil.HONEST_FORCED_WIN_PROFILE, "Default", frame);
        Player me = g.getPlayers().get(0);
        Assert.assertTrue(me.getController() instanceof CubeComboPlayerController);
        CubeComboPlayerController controller = (CubeComboPlayerController) me.getController();
        ForcedWinSolver solver = controller.cubeCombo().forcedWin();
        Assert.assertNotNull(solver.redraw);
        Assert.assertTrue(solver.config().honestProbes);
        Assert.assertFalse(controller.cubeCombo().lineage());
        Assert.assertEquals(((LobbyPlayerAi) me.getLobbyPlayer()).getFairNaming(), AiFixes.Mode.ON);
        ForcedWinSolverTest.play(g, 7);
        Assert.assertTrue(g.isGameOver() && me.hasWon(), solver.summary());
        Assert.assertTrue(solver.taken >= 1, "ordinary AI won without an executed solver line");
        Assert.assertEquals(controller.cubeCombo().honestGeneratorSummary().getAsJsonObject("spec")
                .get("beliefCubeSha256").getAsString(), BeliefSampler.LIVE_CUBE_SHA256);
    }
}
