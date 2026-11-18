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
 * sdcc-piletc-1117 (solver key {@code pileTutorCast}, with the other solver keys of the branch on): the owner's own turn 5
 * start of session 825eb256 (dd011d), his own view. He killed that turn: the pile spell, the draw engine's two cards, the
 * land-sacrifice mana, then the search-and-cast (bargaining the engine) into the pile's empty-library winner. Without the
 * key the search could not take a card the pile still holds. Card names are test data only.
 */
public class PileTutorCastTest extends AITest {

    static final String POSITION = String.join("\n", List.of(
            "[state]",
            "turn=9",
            "activeplayer=human",
            "activephase=MAIN1",
            "humanlife=9",
            "ailife=10",
            "humanlandsplayed=0",
            "ailandsplayed=0",
            "aihand=Skyclave Apparition;Tropical Island;Kaldra Compleat",
            "ailibrary=Teferi, Hero of Dominaria;Soulherder;Thieving Skydiver;Mana Vault;Spell Queller;The Wandering Emperor;Chrome Mox;Narset, Parter of Veils;Island;Island;Island;Island;Island;Snapcaster Mage;Swords to Plowshares;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Deceiver Exarch;Fractured Identity;Mana Leak;Urza's Saga;Hero of Bladehold",
            "aigraveyard=",
            "aiexile=",
            "aibattlefield=Plains;Mana Crypt|Tapped;Syr Ginger, the Meal Ender|Tapped;Plains;Palace Jailer|Tapped;Island|Tapped;Cathar Commando|Tapped;Talisman of Curiosity|Tapped;Plains;True-Name Nemesis|SummonSick",
            "humanhand=Beseech the Mirror;Doomsday;Library of Alexandria;Time Spiral;Raffine's Tower",
            "humanbattlefield=Urborg, Tomb of Yawgmoth;Island;Darkslick Shores;Sensei's Divining Top;Scalding Tarn;The One Ring|Counters:BURDEN=1",
            "humangraveyard=Imperial Seal;Time Walk;Demonic Tutor",
            "humanexile=",
            "humanlibrary=Flooded Strand;Karakas;Swamp;Swamp;Swamp;Force of Will;Miscalculation;Breeding Pool;Lórien Revealed;Ponder;Verdant Catacombs;Vampiric Tutor;Jace, the Mind Sculptor;Dark Ritual;Jace, Wielder of Mysteries;Urza, Lord High Artificer;Island;Island;Mind Stone;Mystical Tutor;Wishclaw Talisman;Preordain;Hullbreacher;Prismatic Vista;Ancestral Recall;Rain of Filth"
    )) + "\n";

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        // the live seat's solver keys (owner-line-coverage live-solverCfg) and the other keys of this branch
        for (String k : List.of("breakOnCounter", "copyDungeon", "cycles", "engineAfterPlanFail", "engineLongLoops", "engineLoopBisect", "engineLoopHoist", "engineLoopReach", "engineModeChoice", "engineMoves", "engineNodesPerSearch", "engineOpen", "engineRerun", "engineTriggerTargets", "engineVerify", "followPay", "hideOwnLibrary", "historyLki", "landEntersProbe", "loopMarginal", "passBlockers", "paySearch", "planDiscardWall", "planLoops", "probeSettle", "readerPerGame", "sourceColorsLive", "sourceLeaves", "sourceOutput", "stackHistory", "tutorCast", "unknownWorlds", "verifyReplan",
                "fetchThin", "payKeepUse", "protectUntilNext", "drawGrowth", "costTax", "grantSacMana")) {
            kv.put(k, "true");
        }
        kv.put("pileTutorCast", on ? "true" : "false");
        kv.put("planOrder", "7");
        kv.put("horizon", "1");
        kv.put("defense", "false");
        kv.put("act", "false");
        kv.put("budgetMs", "60000");
        kv.put("planMs", "20000");
        kv.put("engineMs", "8000");
        kv.put("evalReserveMs", "1500");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

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
    public void hisKillTurnIsProvenOnlyWithTheKey() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", POSITION);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        final ForcedWinSolver.Proposal pOff = best(off.propose(g0));
        Assert.assertTrue(pOff == null || !pOff.proven(1), "key off: no proven line: " + (pOff == null ? off.summary() : pOff.text));
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", POSITION);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        Assert.assertEquals(p.winTurn, 0, p.text);
        boolean free = false;
        for (Step st : ((Line) p.line).steps) {
            free |= st.free;
        }
        Assert.assertTrue(free, "a card searched from the pile and cast for free: " + p.text);
        Assert.assertEquals(g.getPlayers().get(0).getCardsIn(ZoneType.Hand).size(), 5, "nothing was played in the live game");
    }
}
