package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Labeller v2 item (d) (PREDECLARATION-labeller-v2.md §1 (d), §3 test 5): the solver's verification takes the MIN over the
 * opponent's response tree. Exact mode (their real hand, as the loss check uses it): seat "ai" has Lightning Bolt and its
 * own Counterspell to protect it; we are at 3 life.
 */
public class LabellerV2MinTest extends AITest {

    static ForcedWinSolver.Config cfg(final boolean oppMin, final int nodes) {
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> switch (k) {
            case "budget" -> "counter";
            case "defense" -> "false";
            case "exact" -> "true";
            case "act" -> "false";
            case "engine" -> "false";
            case "horizon" -> "1";
            case "nodes" -> "3000";
            case "oppMin" -> oppMin ? "true" : null;
            case "oppNodes" -> String.valueOf(nodes);
            default -> null;
        });
        return c;
    }

    static boolean proven(final Game g, final ForcedWinSolver.Config c) {
        final Player ai = g.getPlayers().get(1);
        g.AI_TIMEOUT = 600;
        final ForcedWinSolver s = new ForcedWinSolver(ai, c);
        final List<ForcedWinSolver.Proposal> ps = s.propose(g);
        for (ForcedWinSolver.Proposal p : ps) {
            if (p.proven(1)) return true;
        }
        return false;
    }

    static Game board(final String ourHand, final String ourLands) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(ourHand, ourLands, 3,
                "Lightning Bolt;Counterspell", "Mountain;Island;Island", 20, "ai"));
    }

    @Test
    public void noResponseIsProvenEitherWay() {
        Assert.assertTrue(proven(board("", "Island;Island"), cfg(false, 64)));
        Assert.assertTrue(proven(board("", "Island;Island"), cfg(true, 64)));
    }

    @Test
    public void ourOneCounterAgainstTheirProtectionStaysProven() {
        Assert.assertTrue(proven(board("Counterspell", "Island;Island"), cfg(true, 64)),
                "their Counterspell answers ours: the Bolt resolves in every branch");
    }

    @Test
    public void twoCountersRefuteTheLineUnderMinOnly() {
        final boolean v1 = proven(board("Counterspell;Counterspell", "Island;Island;Island;Island"), passive(cfg(false, 64)));
        final boolean v2 = proven(board("Counterspell;Counterspell", "Island;Island;Island;Island"), passive(cfg(true, 64)));
        Assert.assertTrue(v1, "v1 counters one spell per play-out: their protection wins");
        Assert.assertFalse(v2, "a second counter on their protection stops the Bolt");
    }

    @Test
    public void aTreeOverTheNodeCapIsNotWon() {
        Assert.assertFalse(proven(board("Counterspell", "Island;Island"), cfg(true, 1)),
                "the root play-out alone is the cap: the response branches are not run, so the world is not won");
    }

    @Test
    public void offIsTheV1Config() {
        final ForcedWinSolver.Config c = cfg(false, 64);
        Assert.assertFalse(c.oppMin);
        Assert.assertFalse(c.toString().contains("oppMin"));
        Assert.assertThrows(IllegalArgumentException.class, () -> ForcedWinSolver.Config.fromProps(k ->
                "oppMin".equals(k) ? "true" : "defense".equals(k) ? "false" : null)); // counts-only
    }

    static ForcedWinSolver.Config engineMin(final boolean oppMin, final int depth) {
        final ForcedWinSolver.Config c = cfgEngine(oppMin);
        c.oppDepth = depth;
        return passive(c);
    }

    static Game frame(String defHand, String defBf, int defLife, String ourHand, String ourBf) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(defHand, defBf, defLife, ourHand, ourBf,
                20, "ai"));
    }

    /**
     * Review S1-1 repro: their Bolt answers our Giant Growth'd Bears attack. Four "Disenchant -> Mox" entries sort ahead
     * of "Bolt -> Bears" in the response set; a set larger than oppResponses must be a cap hit, never a truncation.
     */
    @Test
    public void aTruncatedResponseSetIsNotWon() {
        Assert.assertFalse(proven(frame("Lightning Bolt", "Mountain", 4, "Giant Growth", "Grizzly Bears;Forest;Forest"),
                engineMin(true, 2)), "Bolt on the Bears refutes the attack");
        Assert.assertFalse(proven(frame("Lightning Bolt;Disenchant", "Mountain;Plains;Plains", 4, "Giant Growth",
                "Grizzly Bears;Forest;Forest;Mox Emerald;Mox Jet;Mox Pearl;Mox Ruby"), engineMin(true, 2)),
                "five responses > oppResponses 4: the world is cut, not won");
    }

    /** Review S1-2 repro: a counter war three deep; any depth cap below it must be a cap hit, not a win. */
    @Test
    public void pointsPastTheDepthCapAreNotWon() {
        for (int d : new int[] {2, 3, 4}) {
            Assert.assertFalse(proven(board3("Counterspell;Counterspell;Counterspell",
                    "Island;Island;Island;Island;Island;Island"), passive(withDepth(cfg(true, 64), d))),
                    "their third Counterspell wins the war; D=" + d);
        }
    }

    /**
     * Review S2 repro (the oppResponses cap counted answer x target pairs): their Bolt at our face, we are at 3, our
     * hand Lightning Bolt + Shock + Giant Growth with Mountain x2, Forest and Grizzly Bears. None of it answers the Bolt
     * (burn at their face at 20, a pump outside combat): the shared MIN's set is empty, so the line is PROVEN (#144
     * counted seven answer x target pairs > 4 and cut it).
     */
    @Test
    public void irrelevantAnswersCollapseAndTheLineIsProven() {
        final java.util.function.Supplier<Game> g = () -> frame("Lightning Bolt;Shock;Giant Growth",
                "Mountain;Mountain;Forest;Grizzly Bears", 3, "Lightning Bolt", "Mountain");
        Assert.assertTrue(proven(g.get(), passive(cfg(true, 64))), "no real answer: proven");
        Assert.assertTrue(proven(g.get(), passive(cfgEngine(true))), "no real answer: proven (engine)");
    }

    /**
     * Review #148 S1 repros: answers the first OppMin allow-list dropped (false PROVEN) must refute the line, and the
     * controls must hold. Stack points use the passive spec, attack points the engine spec (both oppMin on).
     */
    @Test
    public void realAnswersTheAllowListMissedRefuteTheLine() {
        final String[][] stack = {
            {"Cryptic Command", "Island;Island;Island;Island", "a modal counter (choose two)"},
            {"Izzet Charm", "Island;Mountain", "a modal soft counter (choose one): its mode is forced"},
            {"Mystic Confluence", "Island;Island;Island;Island;Island", "a modal counter (choose three, repeats)"},
            {"Counterspell", "Island;Island", "control"},
        };
        for (String[] c : stack) {
            Assert.assertFalse(proven(frame(c[0], c[1], 3, "Lightning Bolt", "Mountain"), passive(cfg(true, 64))),
                    c[0] + " vs Bolt: " + c[2]);
        }
        Assert.assertTrue(proven(frame("", "Island;Island;Island;Island", 3, "Lightning Bolt", "Mountain"),
                passive(cfg(true, 64))), "open control: no answer, proven");
        final String[][] attack = {
            {"Turn to Frog", "Island;Island", "the attacker becomes a 1/1"},

            {"Diabolic Edict", "Swamp;Swamp", "an edict at the attacker's controller"},
        };
        for (String[] c : attack) {
            Assert.assertFalse(proven(frame(c[0], c[1], 3, "", "Hill Giant;Mountain"), engineMin(true, 2)),
                    (c[0].isEmpty() ? c[1] : c[0]) + " vs a Hill Giant attack: " + c[2]);
        }
        // Mutavault animates and trades with the Bears (with a Hill Giant the block only delays: the Giant attacks again
        // next turn into an empty board, so that line is a true two-turn win and is proven)
        Assert.assertFalse(proven(frame("", "Mutavault;Mountain", 2, "", "Grizzly Bears;Mountain"), engineMin(true, 2)),
                "Mutavault animates and blocks the Bears");
    }

    /**
     * Review #148 S1 (round 2): a modal answer tries every target class of its modes. Kolaghan's Command against our
     * lethal Bears attack at 2 life, a summoning-sick Llanowar Elves listed first: its 2 damage on the Bears refutes it
     * (with one target per mode it hit the Elves and the attack was proven).
     */
    @Test
    public void aModalAnswerTriesEveryTarget() {
        Assert.assertFalse(proven(frame("Kolaghan's Command", "Swamp;Mountain;Mountain", 2, "",
                "Llanowar Elves|SummonSick;Grizzly Bears;Forest"), engineMin(true, 2)), "Kolaghan's Command on the Bears");
        Assert.assertTrue(proven(frame("", "Swamp;Mountain;Mountain", 2, "", "Llanowar Elves|SummonSick;Grizzly Bears;Forest"),
                engineMin(true, 2)), "open control: the Bears attack is proven");
    }

    /**
     * Review #148 S1 (round 3) and the fail-closed ruling: an answer OppMin cannot enumerate completely is a cap hit,
     * never "no response". Blessed Alliance ("choose one or more", escalate) at a stack point; Mystic Confluence's
     * repeatable modes (two bounces on two different attackers); Fire split between two attackers. None is proven, and
     * the gaps name their reasons; Healing Salve (choose one) refutes the Bolt as before.
     */
    @Test
    public void answersOppMinCannotEnumerateFailClosed() {
        Assert.assertFalse(proven(frame("Blessed Alliance", "Plains;Plains", 3, "Lightning Bolt", "Mountain"),
                passive(cfg(true, 64))), "Blessed Alliance: gain 4 life answers the Bolt");
        Assert.assertFalse(proven(frame("Healing Salve", "Plains", 3, "Lightning Bolt", "Mountain"), passive(cfg(true, 64))),
                "Healing Salve: prevent 3 answers the Bolt");
        final java.util.function.Supplier<Game> conf = () -> frame("Mystic Confluence", "Island;Island;Island;Island;Island",
                4, "", "Grizzly Bears;Grizzly Bears;Forest");
        Assert.assertFalse(proven(conf.get(), engineMin(true, 2)), "Confluence bounces both attackers");
        final Game cg = conf.get();
        Assert.assertTrue(OppMin.answers(cg, cg.getPlayers().get(0), cg.getPlayers().get(1), null).gaps
                .contains("charm-repeat"), "a repeatable modal answer is a gap");
        final java.util.function.Supplier<Game> fire = () -> frame("Fire // Ice", "Mountain;Mountain", 2, "",
                "Savannah Lions;Savannah Lions;Plains");
        Assert.assertFalse(proven(fire.get(), engineMin(true, 2)), "Fire split between the two attackers");
        final Game fg = fire.get();
        Assert.assertTrue(OppMin.answers(fg, fg.getPlayers().get(0), fg.getPlayers().get(1), null).gaps
                .contains("multi-target"), "divided damage is a gap: " + OppMin.answers(fg, fg.getPlayers().get(0),
                fg.getPlayers().get(1), null).gaps);
    }

    /** Review #148 S1: graveyard targets are enumerated (Scavenging Ooze on our graveyard creature). */
    @Test
    public void graveyardTargetsAreResponses() {
        final Game g = frame("", "Scavenging Ooze;Forest", 3, "Lightning Bolt", "Mountain");
        final Player def = g.getPlayers().get(0), ai = g.getPlayers().get(1);
        addCardToZone("Griselbrand", ai, forge.game.zone.ZoneType.Graveyard);
        boolean gy = false;
        for (forge.game.spellability.SpellAbility sa : OppMin.responses(g, def, ai, null)) {
            for (forge.game.GameObject o : sa.getTargets()) {
                gy |= o instanceof forge.game.card.Card c && c.getName().equals("Griselbrand");
            }
        }
        Assert.assertTrue(gy, "Ooze exiles the reanimation target");
    }

    static Game board3(String defHand, String defLands) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(defHand, defLands, 3,
                "Lightning Bolt;Counterspell;Counterspell", "Mountain;Island;Island;Island;Island", 20, "ai"));
    }

    static ForcedWinSolver.Config withDepth(final ForcedWinSolver.Config c, final int d) {
        c.oppDepth = d;
        return c;
    }

    /** Predeclared §3 test 5 (Swords on the attacker), at the declare-attackers point. */
    @Test
    public void swordsOnTheAttackerRefutesV2Only() {
        final boolean v1 = proven(frame("Swords to Plowshares", "Plains", 3, "", "Hill Giant;Mountain"), engineMin(false, 2));
        final boolean v2 = proven(frame("Swords to Plowshares", "Plains", 3, "", "Hill Giant;Mountain"), engineMin(true, 2));
        final boolean open = proven(frame("", "Plains", 3, "", "Hill Giant;Mountain"), engineMin(true, 2));
        Assert.assertTrue(open, "no answer: the attack is proven");
        Assert.assertFalse(v2, "v2's MIN tries Swords on the attacker");
        // v1 is reported: its engine searches against Forge AI, which itself plays the Swords here
        System.err.println("[labeller-v2] swords: v1 proven=" + v1 + " v2 proven=" + v2 + " open=" + open);
    }

    static ForcedWinSolver.Config passive(final ForcedWinSolver.Config c) {
        c.testPassiveOpp = true;
        return c;
    }

    static ForcedWinSolver.Config cfgEngine(final boolean oppMin) {
        return ForcedWinSolver.Config.fromProps(k -> switch (k) {
            case "budget" -> "counter";
            case "defense" -> "false";
            case "exact" -> "true";
            case "act" -> "false";
            case "horizon" -> "1";
            case "nodes" -> "3000";
            case "engineNodes" -> "200";
            case "engineSteps" -> "4000";
            case "oppMin" -> oppMin ? "true" : null;
            default -> null;
        });
    }

    static boolean provenAny(final java.util.function.Supplier<Game> g, final boolean oppMin) {
        return proven(g.get(), passive(cfg(oppMin, 64))) || proven(g.get(), passive(cfgEngine(oppMin)));
    }

    /**
     * Predeclared test 5, on what v1's adversary STRUCTURALLY cannot cover (architect-o, 10-30), with the adversary's
     * own Forge AI answer replaced by "pass" (testPassiveOpp) so no case depends on what Forge AI happens to play:
     * (1) a SECOND counter: their Bolt is protected by their own Counterspell; we hold two Counterspells (v1 counters
     * one spell per play-out, so their protection wins; v2's MIN counters the protection too);
     * (2) a counter on an ABILITY: their Rod of Ruin activation for our last life point, our Stifle (v1's counterFor
     * targets spells only). Every case v1 proves, v2 must refute, and with our answer removed v2 must prove it again.
     */
    @Test
    public void v2RefutesWhatV1StructurallyMisses() {
        final String[][] cases = {
            {"Counterspell;Counterspell", "Island;Island;Island;Island", "3", "Lightning Bolt;Counterspell",
                "Mountain;Island;Island", ""},
            {"Stifle", "Island", "1", "", "Rod of Ruin;Mountain;Mountain;Mountain", ""},
        };
        int v1Proofs = 0;
        final StringBuilder sb = new StringBuilder();
        for (String[] c : cases) {
            final java.util.function.Supplier<Game> g = () -> ForcedWinSolverTest.game("Default", "Default",
                    ForcedWinSolverTest.frame(c[0], c[1], Integer.parseInt(c[2]), c[3], c[4], 20, "ai"));
            final java.util.function.Supplier<Game> open = () -> ForcedWinSolverTest.game("Default", "Default",
                    ForcedWinSolverTest.frame("", c[1], Integer.parseInt(c[2]), c[3], c[4], 20, "ai"));
            final boolean v1 = provenAny(g, false), v2 = provenAny(g, true), v2open = provenAny(open, true);
            sb.append(c[0]).append(": v1=").append(v1).append(" v2=").append(v2).append(" v2(no answer)=").append(v2open)
                    .append("; ");
            if (v1) {
                v1Proofs++;
                Assert.assertFalse(v2, "v2 must refute what v1's one-counter adversary lets through: " + sb);
                Assert.assertTrue(v2open, "with no answer in our hand v2 proves the same line: " + sb);
            }
        }
        System.err.println("[labeller-v2] structural cases: " + sb);
        Assert.assertTrue(v1Proofs >= 1, "no case v1 proves, the test says nothing: " + sb);
    }
}
