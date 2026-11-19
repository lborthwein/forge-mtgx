package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.zone.ZoneType;

/**
 * sdcc-costtax-1117 (solver key {@code costTax}; off by default): the owner's position at his own turn 4 of session
 * 74d1853a (dd085p). The opponent's permanent makes noncreature spells cost one more. His line is the pass-the-turn pile,
 * then the free draw-two returning two Islands, a free mana artifact and the library-size creature. The planner paid
 * printed costs, so every line it found failed in the engine. Card names are test data only.
 */
public class CostTaxTest extends AITest {

    static final String POSITION = String.join("\n", List.of(
            "[state]",
            "turn=7",
            "activeplayer=human",
            "activephase=MAIN1",
            "humanlife=17",
            "humanlandsplayed=0",
            "humanlandsplayedlastturn=0",
            "humannumringtemptedyou=0",
            "humanspeed=0",
            "humanhand=Swamp;Swamp;Bolas's Citadel;Beseech the Mirror;Doomsday;Gush;Counterspell",
            "humanlibrary=Thassa's Oracle;Echo of Eons;Daze;Timetwister;Chart a Course;Jace, the Mind Sculptor;Island;Darkslick Shores;Swamp;Misty Rainforest;Waterlogged Grove;Overgrown Tomb;Troll of Khazad-dûm;Library of Alexandria;Collective Brutality;Island;Cabal Ritual;Mystical Tutor;Wishclaw Talisman;Stern Scolding;Mind Stone;Raffine's Tower;Remand;Scalding Tarn;Baleful Strix;Memory Jar;Lotus Petal;Chromatic Star;Jace, Wielder of Mysteries",
            "humangraveyard=Verdant Catacombs",
            "humanbattlefield=Urborg, Tomb of Yawgmoth;Xander's Lounge;Island",
            "humanexile=",
            "humancommand=",
            "humansideboard=",
            "ailife=20",
            "ailandsplayed=0",
            "ailandsplayedlastturn=1",
            "ainumringtemptedyou=0",
            "aispeed=0",
            "aihand=Plains;Plains;Underworld Breach;Fable of the Mirror-Breaker;Plains",
            "ailibrary=Wrath of God;Trumpeting Carnosaur;Cathar Commando;Timeless Dragon;Containment Priest;Restoration Angel;Mountain;Figure of Destiny;Zealous Conscripts;The Wandering Emperor;Plains;Sundering Titan;Gideon, Ally of Zendikar;Imperial Recruiter;Winds of Abandon;Plains;Mountain;Giver of Runes;Lightning Greaves;Mountain;Plains;Plains;Mountain;Oliphaunt;Hero of Bladehold;Mountain;Portable Hole;Comet, Stellar Pup;Mountain;Pia and Kiran Nalaar",
            "aigraveyard=",
            "aibattlefield=Mountain|Tapped;Plains|Tapped;Thalia, Guardian of Thraben|Tapped;Mountain|Tapped;Blade Splicer|SummonSick;T:c_3_3_a_phyrexian_golem|Set:BLC|SummonSick",
            "aiexile=",
            "aicommand=",
            "aisideboard="
    )) + "\n";

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        kv.put("costTax", on ? "true" : "false");
        kv.put("paySearch", "true");
        kv.put("planOrder", "7");
        kv.put("hideOwnLibrary", "true");
        kv.put("engine", "false"); // the width search and its verification only
        kv.put("defense", "false");
        kv.put("act", "false");
        kv.put("budgetMs", "60000");
        kv.put("planMs", "20000");
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
    public void theTaxedPassTheTurnPileIsProvenOnlyWithTheKey() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", POSITION);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        final ForcedWinSolver.Proposal pOff = best(off.propose(g0));
        Assert.assertTrue(pOff == null || !pOff.proven(1), "key off: no proven line, as before: " + (pOff == null ? off.summary() : pOff.text));
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", POSITION);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinModel.Position pos = on.readPosition(g, on.knownTop(g));
        Assert.assertNotNull(pos.tax);
        Assert.assertFalse(pos.tax.isEmpty(), "the opponent's tax on our noncreature spells is read");
        for (Map.Entry<String, Integer> e : pos.tax.entrySet()) {
            Assert.assertEquals((int) e.getValue(), 1, e.toString());
        }
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        Assert.assertEquals(g.getPlayers().get(0).getCardsIn(ZoneType.Hand).size(), 7, "nothing was played in the live game");
    }
}
