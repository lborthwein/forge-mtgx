package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * untested-keys-1113 (lane solver-distill-cc): the end-step engine family at the owner's G15 position (review set G15 =
 * game D048, seat 1). Card facts are test data only.
 *
 * <p>G15: a creature with flash and the Aura that makes it copy itself in hand, five lands, the opponent at 10 with two
 * 7/7s. The pilot cast another creature in its main phase (step 0) and had no mana on the opponent's turn. His line:
 * hold, flash the creature on the opponent's turn, the Aura on the next own turn. Two plan points of the live pilot are
 * here: our main phase (step 0) and the opponent's end step (the pilot's solver runs there; the engine does not, unless
 * engineAnyWindow). The solver is the ship string's (OwnerMisplaysTest.shipSolverCfg); the arms add keys.
 */
public class UntestedKeysG15Test extends forge.ai.AITest {

    static final String PL = "planLoops=true";
    static final String ES = PL + ",engineAnyWindow=true,engineEndStepOpen=true,enginePassBudget=true";
    static final String ESE = ES + ",engineEveryWindow=true";
    static final String EST = ES + ",enginePassToEnd=true";

    static final String OPP_LANDS = "Indatha Triome;Plains;Boseiju, Who Endures;Mishra's Workshop;Forest";

    /** G15 as recorded at step 0: turn 9, our first main phase, everything untapped. */
    static String step0() {
        return "[state]\nturn=9\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=16\nailife=10\n"
                + "humanlandsplayed=1\nailandsplayed=0\naihand=Tear Asunder;Sol Ring;Finale of Devastation\nailibrary=" + ForcedWinSolverTest.LIB20
                + "\naigraveyard=Natural Order;Swords to Plowshares\naiexile=\naibattlefield=Torsten, Founder of Benalia;Titan of Industry;" + OPP_LANDS
                + "\nhumanhand=Pestermite;Splinter Twin;Time Warp;Chaos Defiler\nhumanbattlefield=" + OwnerMisplaysTest.G15_LANDS
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + OwnerMisplaysTest.G15_LIB + "\n";
    }

    /**
     * His line's state one window on: the opponent's turn 10 at {@code phase} (END_OF_TURN, or COMBAT_BEGIN before their
     * attack), our mana held, our life {@code life}. Their hand as recorded on turn 10.
     */
    static String theirTurn(final String phase, final int life) {
        return "[state]\nturn=10\nactiveplayer=ai\nactivephase=" + phase + "\nhumanlife=" + life + "\nailife=10\n"
                + "humanlandsplayed=0\nailandsplayed=1\naihand=Sol Ring;Utopia Sprawl\nailibrary=" + ForcedWinSolverTest.LIB20
                + "\naigraveyard=Natural Order;Swords to Plowshares\naiexile=\naibattlefield=Torsten, Founder of Benalia;Titan of Industry;"
                + OPP_LANDS + "\nhumanhand=Pestermite;Splinter Twin;Time Warp\nhumanbattlefield=" + OwnerMisplaysTest.G15_LANDS
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + OwnerMisplaysTest.G15_LIB + "\n";
    }

    static Game game(final String frame) {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    /** The best proposal (by P(belief)) of the ship solver plus {@code extra}; one report line. */
    static ForcedWinSolver.Proposal propose(final String what, final Game g, final String extra) {
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(OwnerMisplaysTest.shipSolverCfg(extra)));
        final long t0 = System.nanoTime();
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        final String t = best == null ? "" : String.valueOf(best.text).replaceAll("\\s+", " ");
        System.out.println("UNTESTEDKEYS " + what + " [" + extra + "] turn=" + g.getPhaseHandler().getTurn() + " "
                + g.getPhaseHandler().getPhase() + " hand=" + me.getCardsIn(ZoneType.Hand) + " PROPOSE ms="
                + (System.nanoTime() - t0) / 1_000_000 + " lines=" + props.size()
                + (best == null ? " none" : " proven=" + best.proven(1) + " pUnopposed=" + best.pUnopposed + " pBelief=" + best.pBelief
                        + " steps=" + best.steps + " winTurn=" + best.winTurn + " how=" + best.how + " | " + t.substring(0, Math.min(500, t.length())))
                + " cap=" + s.lastCap() + " || " + s.summary());
        return best;
    }

    static String wide(final String keys) {
        return keys + ",budgetMs=60000,engineMs=40000,evalReserveMs=15000";
    }

    /**
     * Their end step, mana held (his line with the flash a window later; life 2 = both 7/7s connected, 9 = one tapped).
     * The ship string's engine does not run at their end step (only the width planner does, and it has no copy model):
     * no line. With engineAnyWindow the engine runs there: the flash now, the Aura on our turn. Wide budget asserted
     * (the question is whether the line is in the search space); the ship budget is reported.
     */
    @Test
    public void theirEndStepTheFamilyFindsTheFlashThenAuraLine() {
        for (int life : new int[] {9, 2}) {
            final String f = theirTurn("END_OF_TURN", life);
            final ForcedWinSolver.Proposal pl = propose("G15 their end step life " + life, game(f), PL);
            Assert.assertTrue(pl == null || !pl.proven(1), "planLoops alone: no engine at their end step: " + (pl == null ? "" : pl.text));
            // the ship budget: the engine finds it in about a second (r2, loaded); the verify replay follows it in this window
            final ForcedWinSolver.Proposal es = propose("G15 their end step life " + life, game(f), ES);
            Assert.assertTrue(es != null && es.proven(1) && es.winTurn == 1 && String.valueOf(es.text).contains("Pestermite"),
                    "the end-step family at the ship budget: flash now, the Aura next turn: " + (es == null ? "none" : es.text));
            propose("G15 their end step life " + life, game(f), ESE);
        }
        final ForcedWinSolver.Proposal w = propose("G15 their end step life 9 wide", game(theirTurn("END_OF_TURN", 9)), wide(ES));
        Assert.assertTrue(w != null && w.pUnopposed >= 1.0, "the end-step family (wide): a line from their end step");
        Assert.assertTrue(String.valueOf(w.text).contains("Pestermite") && String.valueOf(w.text).contains("Splinter Twin"), w.text);
        Assert.assertTrue(w.winTurn >= 1, "the kill is on our next turn: " + w.winTurn);
    }

    /**
     * Their beginning of combat (his exact window): the pilot has no plan point there (PlanSearch plans at our main phase
     * and their end step); engineEveryWindow lets the solver search it when asked. Report only.
     */
    @Test
    public void theirCombatReportOnly() {
        final String f = theirTurn("COMBAT_BEGIN", 16);
        propose("G15 their combat", game(f), PL);
        propose("G15 their combat", game(f), ES);
        propose("G15 their combat", game(f), ESE);
    }

    /**
     * Step 0 (our main phase, the pilot's decision that spent the mana). A line that holds now and flashes in their end
     * step needs enginePassToEnd (the turn-crossing pass may rest in their end step). Report only: which arms propose,
     * at the ship budget and wide.
     */
    @Test
    public void step0ReportOnly() {
        for (String k : new String[] {PL, ES, EST}) {
            propose("G15 step 0", game(step0()), k);
        }
        for (String k : new String[] {PL, EST}) {
            propose("G15 step 0 wide", game(step0()), wide(k));
        }
    }

    /**
     * engineFocus + engineLoopCredit at an owner loop position (G08's board with the copying creature kept: its partner in
     * play, three sources of its colour; our main phase). The ship string's engine is the reference; the keys must not
     * lose a kill it proves. Report: proposal, time, engine nodes.
     */
    @Test
    public void g08FocusAndLoopCreditDoNotLoseTheLoopKill() {
        final String frame = "[state]\nturn=9\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=12\nailife=14\n"
                + "humanlandsplayed=1\nailandsplayed=0\naihand=\nailibrary=" + ForcedWinSolverTest.LIB20
                + "\naigraveyard=\naiexile=\naibattlefield=Elite Spellbinder;Mountain;Mountain;Mountain"
                + "\nhumanhand=Kiki-Jiki, Mirror Breaker\nhumanbattlefield=Plains;Plains;Plains;Mountain;Plateau;Mox Ruby;Mana Vault;Skullclamp;Restoration Angel"
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=Plains;Plains;Island;Swords to Plowshares;Flickerwisp;Stoneforge Mystic;Plains;Mountain\n";
        final ForcedWinSolver.Proposal base = propose("G08 loop", game(frame), PL);
        final ForcedWinSolver.Proposal on = propose("G08 loop", game(frame), PL + ",engineFocus=true,engineLoopCredit=true");
        if (base != null && base.proven(1)) {
            Assert.assertTrue(on != null && on.proven(1), "focus + loop credit keep the ship engine's proven kill");
        }
    }

    /** Keys off: absent and explicitly false give the same proposals and the same solver summary at their end step. */
    @Test
    public void keysOffAbsentAndFalseAreTheSame() {
        final String f = theirTurn("END_OF_TURN", 9);
        final String off = PL + ",engineAnyWindow=false,engineEndStepOpen=false,engineEveryWindow=false,enginePassBudget=false,"
                + "enginePassToEnd=false,engineFocus=false,engineLoopCredit=false,engineLoopCreditCap=96";
        final Player me0 = game(f).getPlayers().get(0);
        Assert.assertEquals(SolverDriver.config(OwnerMisplaysTest.shipSolverCfg(off)).toString(),
                SolverDriver.config(OwnerMisplaysTest.shipSolverCfg(PL)).toString());
        final ForcedWinSolver.Proposal a = propose("G15 keys off (absent)", game(f), PL);
        final ForcedWinSolver.Proposal b = propose("G15 keys off (false)", game(f), off);
        Assert.assertEquals(a == null ? null : a.text, b == null ? null : b.text);
        Assert.assertNotNull(me0);
    }
}
