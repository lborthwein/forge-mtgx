package forge.ai.simulation;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.util.IdScope;

/**
 * solver-cap12-1030 round 2 (CAP-1, PREDECLARATION-solver-cap12-APPENDIX-round2 R2-2): the Breach label harness. The
 * position is the P-GEN pilot's 2990164462 seat 1 turn 15 frame (ComboTrace frame dump at the kill point): Underworld
 * Breach, Lion's Eye Diamond and Wheel of Fortune in hand, six untapped lands, Lotus Petal and Brain Freeze among eight
 * graveyard cards, the opponent at 10 with 23 library cards. Diagnostic rows print with -Dforge.ai.forcedWin.receipts and
 * -Dforge.ai.forcedWin.engineTrace.
 */
public class SolverCap1BreachTest extends forge.ai.AITest {

    static final String FRAME = """
turn=15
activeplayer=p1
activephase=MAIN1
p0life=10
p0landsplayed=0
p0landsplayedlastturn=1
p0numringtemptedyou=0
p0speed=0
p0hand=Aetherflux Reservoir|Set:BRR|Art:1;Sundering Titan|Set:BRR|Art:1;Bolas's Citadel|Set:MB2|Art:1
p0library=Tinker|Set:MB2|Art:1;Island|Set:TRK|Art:1;Swamp|Set:TRK|Art:1;Dismember|Set:SOA|Art:1;Island|Set:TRK|Art:1;Swamp|Set:TRK|Art:1;Hymn to Tourach|Set:SLC|Art:1;Baleful Strix|Set:SLZ|Art:1;Swamp|Set:TRK|Art:1;Toxic Deluge|Set:MSC|Art:1;Mox Emerald|Set:30A|Art:1;Swamp|Set:TRK|Art:1;Thopter Foundry|Set:MB2|Art:1;Zagoth Triome|Set:IKO|Art:1;Swamp|Set:TRK|Art:1;Triplicate Titan|Set:AA2|Art:1;Island|Set:TRK|Art:1;Deceiver Exarch|Set:PLST|Art:1;Swamp|Set:TRK|Art:1;Trinket Mage|Set:AA2|Art:1;Island|Set:TRK|Art:1;Island|Set:TRK|Art:1;Mox Sapphire|Set:30A|Art:1
p0graveyard=Brain Freeze|Set:SOA|Art:1
p0battlefield=Island|Set:TRK|Art:1|Tapped;Mox Ruby|Set:30A|Art:1|Tapped;Reckoner Bankbuster|Set:NEO|Art:1|Tapped|Counters:CHARGE=2;Island|Set:TRK|Art:1|Tapped;Hullbreacher|Set:MB2|Art:1;Sensei's Divining Top|Set:SLZ|Art:1;Swamp|Set:TRK|Art:1|Tapped;Urza, Lord High Artificer|Set:MB2|Art:1|Id:19;Swamp|Set:TRK|Art:1|Tapped;Island|Set:TRK|Art:1|Tapped;Dark Confidant|Set:FIN|Art:1|SummonSick
p0exile=Tishana's Tidebinder|Set:PJSC|Art:1|ExiledWith:19;Timetwister|Set:30A|Art:1|ExiledWith:19
p0command=
p0sideboard=
p1life=5
p1landsplayed=0
p1landsplayedlastturn=0
p1numringtemptedyou=0
p1speed=0
p1hand=Wheel of Fortune|Set:30A|Art:1;Lion's Eye Diamond|Set:VMA|Art:1;Underworld Breach|Set:PZA|Art:1
p1library=Island|Set:TRK|Art:1;Island|Set:TRK|Art:1;Spellseeker|Set:CMM|Art:1;Bomat Courier|Set:KLR|Art:1;Mountain|Set:TRK|Art:1;Island|Set:TRK|Art:1;Force of Will|Set:SOA|Art:1;Mountain|Set:TRK|Art:1;Island|Set:TRK|Art:1;Chrome Mox|Set:SLZ|Art:1;Ancestral Recall|Set:30A|Art:1;Mountain|Set:TRK|Art:1;Talisman of Curiosity|Set:SOC|Art:1;Island|Set:TRK|Art:1;Frantic Search|Set:TLE|Art:1;Seasoned Pyromancer|Set:SLP|Art:1;Mountain|Set:TRK|Art:1;Spell Pierce|Set:SLZ|Art:1;Mountain|Set:TRK|Art:1;Mountain|Set:TRK|Art:1
p1graveyard=Lotus Petal|Set:MB2|Art:1;Brain Freeze|Set:SOA|Art:1;Mountain|Set:TRK|Art:1;Pyrokinesis|Set:SLZ|Art:1;Sundering Titan|Set:BRR|Art:1;Time Warp|Set:MSC|Art:1;Subtlety|Set:H2R|Art:1;Burst Lightning|Set:SOA|Art:1
p1battlefield=Island|Set:TRK|Art:1;Island|Set:TRK|Art:1;Pestermite|Set:J25|Art:1;Island|Set:TRK|Art:1;Island|Set:TRK|Art:1;Mountain|Set:TRK|Art:1;Goblin Rabblemaster|Set:PIO|Art:1;Mountain|Set:TRK|Art:1;Dragon's Rage Channeler|Set:MB2|Art:1
p1exile=
p1command=
p1sideboard=
""";

    static final String KILL_SPEC = "{\"lossCfg\":{\"budget\":\"counter\",\"budgetMs\":\"2000\",\"cycles\":\"true\",\"engineCards\":\"12500\",\"engineMs\":\"750\",\"enginePathCards\":\"20000\",\"engineSteps\":\"500\",\"evalReserveMs\":\"500\",\"evalSteps\":\"500\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"500\"},\"lossWallMs\":0,\"redraw\":\"belief\",\"worlds\":8,\"sequentialAi\":true,\"aiTimeoutSec\":600,\"solverCfg\":{\"budget\":\"counter\",\"budgetMs\":\"4000\",\"cycles\":\"true\",\"engineCards\":\"25000\",\"engineMs\":\"2000\",\"enginePathCards\":\"20000\",\"engineSteps\":\"1000\",\"evalReserveMs\":\"1000\",\"evalSteps\":\"1250\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"1000\"}}";

    /** The frame as a game: seat 1 (p1) is the Breach seat, at its main phase 1 priority. */
    static Game breach() {
        return ForcedWinSolverTest.game("Default", "Default", "[state]\n" + FRAME);
    }

    static ForcedWinEngine.Limits fullLimits(final int nodes) {
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        lim.nodes = nodes;
        lim.cycles = true;
        lim.passTurn = true;
        lim.loopMacro = Boolean.getBoolean("sc1.loopMacro");
        // round 3 (R3-3, R3-4 a): -Dsc1.order=static orders the engine's moves by Forge's static value; -Dsc1.x=true
        lim.order = "static".equals(System.getProperty("sc1.order")) ? StaticValuer.INSTANCE : null;
        lim.xChoice = Boolean.getBoolean("sc1.x");
        return lim;
    }

    @Test(timeOut = 900000)
    public void engineFullInformation() {
        final Game g = breach();
        final Player me = g.getPlayers().get(1);
        final int nodes = Integer.getInteger("sc1.nodes", 250);
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        final ForcedWinPlanner.Line l = ForcedWinEngine.search(g, me, fullLimits(nodes), st,
                System.nanoTime() + 600_000_000_000L, 7L);
        System.out.println("SC1 engine-full nodes=" + nodes + " found=" + (l != null) + " " + st.summary() + " line=" + l);
    }

    @Test(timeOut = 900000)
    public void honestLabel() {
        final Game g = breach();
        final Player me = g.getPlayers().get(1);
        final HonestLabel.Seat seat = new HonestLabel.Seat(HonestLabel.Spec.parse(WinConditionCoverageTest.spec()));
        final JsonObject r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> seat.label(g, me, 42L)))
                .toJson();
        System.out.println("SC1 label " + r);
    }
}
