package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;

/**
 * solver-gaps-1104: the search gaps from the owner's Kiki/Pestermite tapes, as unit scenarios (test scenarios only:
 * card names appear here, never in the search). (D-1) the turn-crossing pass tries the pass first and may rest at the
 * opponent's end step (enginePassFirst, enginePassToEnd); (C) a targeting trigger branches over every target class with
 * mana valued: an untap of our mana source floats its mana in response (engineTriggerTargets); (Twin) OppMin expands a
 * loop's response points once per loop state (oppMinLoopMemo). Every key off = unchanged and unrecorded.
 */
public class SolverGapsTest extends SimulationTest {

    static List<ForcedWinSolver.Proposal> propose(final Game g, final String keys, final String tag) {
        final java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String kv : keys.split(",")) {
            if (!kv.isEmpty()) m.put(kv.split("=")[0], kv.split("=")[1]);
        }
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(m::get));
        final List<ForcedWinSolver.Proposal> p = s.propose(g);
        System.err.println("[solver-gaps-test] " + tag + " keys=" + keys + " proposals=" + p.size() + " meter=" + s.meter.toJson());
        for (ForcedWinSolver.Proposal x : p) System.err.println("[solver-gaps-test]   " + tag + " p=" + x.pBelief + " " + x.text);
        return p;
    }

    /** A frame: our (human) hand and battlefield, theirs, the phase and active player, their life. */
    static String frame(final String hand, final String bf, final int theirLife, final String theirHand, final String theirBf,
            final String phase, final String active) {
        return "[state]\nturn=8\nactiveplayer=" + active + "\nactivephase=" + phase + "\nhumanlife=20\nailife=" + theirLife
                + "\nhumanlandsplayed=1\nailandsplayed=0\naihand=" + theirHand + "\nailibrary=" + ForcedWinSolverTest.LIB20
                + "\naigraveyard=\naiexile=\naibattlefield=" + theirBf + "\nhumanhand=" + hand + "\nhumanbattlefield=" + bf
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + ForcedWinSolverTest.OURLIB + "\n";
    }

    /** The R3 smoke's label solverCfg f2 (forge-mtgx #177), the keys under test added per scenario. */
    static final String BASE = "budget=counter,budgetMs=4000,cycles=true,engineCards=75000,engineMs=2000,enginePathCards=20000,"
            + "engineSteps=3000,evalReserveMs=1000,evalSteps=4000,wallGraceMs=1000,labellerV2=true,engineAnyWindow=true,"
            + "engineEndStepOpen=true,enginePassBudget=true,engineStack=true,loopMarginal=true,engineEveryWindow=true,"
            + "engineNodes=500";

    @Test
    public void offKeysAreUnrecorded() {
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> null);
        for (String k : new String[] {"enginePassFirst", "enginePassToEnd", "engineTriggerTargets", "oppMinLoopMemo",
                "triggerVariants"}) {
            AssertJUnit.assertFalse(k, HonestGenerator.solverCfgJson(c).has(k));
        }
        AssertJUnit.assertFalse(c.toString().contains("enginePass"));
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(java.util.Map.of("enginePassFirst", "true")::get);
        AssertJUnit.assertTrue(HonestGenerator.solverCfgJson(on).has("enginePassFirst"));
    }

    /** D-1: our main phase, the setup and the payoff in hand among many other castable cards; the kill is next turn. */
    private static String dMinus1() {
        return frame("Pestermite;Kiki-Jiki, Mirror Breaker;Lightning Bolt;Brainstorm;Opt;Ponder;Preordain;Duress;Mountain",
                "Volcanic Island;Volcanic Island;Mountain;Mountain;Mountain", 20, "", "Forest", "MAIN1", "human");
    }

    @Test
    public void dMinus1SetupIsFoundWhenThePassIsTriedFirst() {
        final List<ForcedWinSolver.Proposal> off = propose(ForcedWinSolverTest.game("Default", "Default", dMinus1()), BASE, "d1-off");
        final List<ForcedWinSolver.Proposal> first = propose(ForcedWinSolverTest.game("Default", "Default", dMinus1()),
                BASE + ",enginePassFirst=true", "d1-first");
        final List<ForcedWinSolver.Proposal> end = propose(ForcedWinSolverTest.game("Default", "Default", dMinus1()),
                BASE + ",enginePassFirst=true,enginePassToEnd=true", "d1-end");
        AssertJUnit.assertFalse("pass first: the next-turn line", first.isEmpty());
        AssertJUnit.assertFalse("pass to their end step: a line", end.isEmpty());
        System.err.println("[solver-gaps-test] d1 off found=" + !off.isEmpty());
    }

    @Test
    public void passToTheirEndStepIsAWindow() {
        // only the end-step timing kills next turn: three lands now (the setup), and the payoff's five the turn after
        // needs the land in hand; with enginePassToEnd the line casts the setup in their end step
        final String f = frame("Pestermite;Kiki-Jiki, Mirror Breaker;Mountain", "Volcanic Island;Volcanic Island;Mountain;Mountain",
                20, "", "Forest", "MAIN1", "human");
        final List<ForcedWinSolver.Proposal> end = propose(ForcedWinSolverTest.game("Default", "Default", f),
                BASE + ",enginePassFirst=true,enginePassToEnd=true", "end");
        AssertJUnit.assertFalse(end.isEmpty());
        boolean endLine = false;
        for (ForcedWinSolver.Proposal p : end) endLine |= p.text.contains("PASS>END");
        System.err.println("[solver-gaps-test] end-step line among proposals: " + endLine);
    }

    @Test
    public void untapALandFloatsItsManaInResponse() {
        // owner 11-03 (C): two red sources; the payoff needs three red the turn the enabler enters. Float a red, cast the
        // enabler, untap that source with its enter trigger (or float in response), cast the payoff.
        final String f = frame("Pestermite;Kiki-Jiki, Mirror Breaker", "Mountain;Mountain;Island;Island;Island;Island;Island", 6,
                "", "Forest", "MAIN1", "human");
        final List<ForcedWinSolver.Proposal> off = propose(ForcedWinSolverTest.game("Default", "Default", f), BASE, "c-off");
        final List<ForcedWinSolver.Proposal> on = propose(ForcedWinSolverTest.game("Default", "Default", f),
                BASE + ",engineTriggerTargets=true", "c-on");
        final List<ForcedWinSolver.Proposal> onPay = propose(ForcedWinSolverTest.game("Default", "Default", f),
                BASE + ",engineTriggerTargets=true", "c-on-pay");
        AssertJUnit.assertTrue("off: no line (the known gap)", off.isEmpty());
        AssertJUnit.assertFalse("on: the untap-a-land line", on.isEmpty() && onPay.isEmpty());
        final ForcedWinSolver.Proposal p = on.isEmpty() ? onPay.get(0) : on.get(0);
        AssertJUnit.assertTrue(p.text, p.text.contains("+F"));
    }

    @Test
    public void twinLoopResponsePointsAreExpandedOncePerLoopState() {
        // a Twin loop against an opponent with open mana and instants that do not stop it: every iteration is a response
        // point; off, OppMin's tree grows with the iterations
        final String f = frame("Splinter Twin", "Pestermite;Volcanic Island;Volcanic Island;Mountain;Mountain", 20,
                "Brainstorm;Opt;Consider", "Island;Island;Island", "MAIN1", "human");
        final String keys = BASE;
        final List<ForcedWinSolver.Proposal> off = propose(ForcedWinSolverTest.game("Default", "Default", f), keys, "twin-off");
        final List<ForcedWinSolver.Proposal> on = propose(ForcedWinSolverTest.game("Default", "Default", f),
                keys + ",oppMinLoopMemo=true", "twin-on");
        AssertJUnit.assertFalse("on: the loop line", on.isEmpty());
        AssertJUnit.assertEquals(on.get(0).text, 1.0, on.get(0).pBelief, 1e-9);
        System.err.println("[solver-gaps-test] twin off found=" + !off.isEmpty() + (off.isEmpty() ? "" : " p=" + off.get(0).pBelief));
    }

    @Test
    public void revealingTheSetupEarlyIsCostedByTheOpponentsOwnTurnPoint() {
        // owner 11-03 (B): they tapped out last turn and hold a counter and a creature. Our setup in our main phase is seen
        // on their turn: they may hold the counter for our payoff (reveal point). Flashed in at their end step, it is not
        // seen while they decide (Forge AI casts its creature and is tapped low on our turn).
        final String f = frame("Pestermite;Kiki-Jiki, Mirror Breaker", "Volcanic Island;Volcanic Island;Mountain;Mountain;Mountain",
                20, "Counterspell;Grizzly Bears", "Island|Tapped;Island|Tapped;Forest|Tapped", "MAIN1", "human");
        final String k = BASE + ",enginePassToEnd=true";
        final List<ForcedWinSolver.Proposal> plain = propose(ForcedWinSolverTest.game("Default", "Default", f), k, "b-plain");
        final List<ForcedWinSolver.Proposal> rev = propose(ForcedWinSolverTest.game("Default", "Default", f),
                k + ",oppMinRevealPoints=true", "b-reveal");
        final List<ForcedWinSolver.Proposal> best = propose(ForcedWinSolverTest.game("Default", "Default", f),
                k + ",oppMinRevealPoints=true,enginePassFirst=true", "b-reveal-first");
        System.err.println("[solver-gaps-test] b plain=" + (plain.isEmpty() ? "none" : plain.get(0).pBelief + " " + plain.get(0).text)
                + " | reveal=" + (rev.isEmpty() ? "none" : rev.get(0).pBelief + " " + rev.get(0).text));
        AssertJUnit.assertFalse("reveal + pass first: the end-step line", best.isEmpty());
        AssertJUnit.assertTrue(best.get(0).text, best.get(0).text.contains("PASS>END"));
    }
}
