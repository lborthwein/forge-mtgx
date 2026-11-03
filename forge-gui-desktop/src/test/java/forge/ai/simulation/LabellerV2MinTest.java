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
    public void theS2BoardIsACapHitNow() {
        final java.util.function.Supplier<Game> g = () -> frame("Lightning Bolt;Shock;Giant Growth",
                "Mountain;Mountain;Forest;Grizzly Bears", 3, "Lightning Bolt", "Mountain");
        // review #148 re-check 4 ruling: non-lethal damage to us, harm to their own permanents and a pump on their own
        // creature are responses now; this board's set is larger than oppResponses, so it is a cap hit, not a proof
        Assert.assertFalse(proven(g.get(), passive(cfg(true, 64))), "a cap hit (truncated): not proven");
        final Game x = g.get();
        Assert.assertTrue(OppMin.responses(x, x.getPlayers().get(0), x.getPlayers().get(1), null).size() > 4);
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

    /**
     * Review #148 re-check 4: answers the old deny-list dropped (a pump or protection on their own creature, a Dig to the
     * battlefield, a sac outlet) answer the line; an unreadable answer is a gap. Our Flame Slash on their blocker, then a
     * Hill Giant attack, against them at 3.
     */
    @Test
    public void answersOnTheirOwnPermanentsAnswerTheLine() {
        for (String c : new String[] {"Giant Growth", "Blossoming Defense"}) {
            Assert.assertFalse(proven(frame(c, "Grizzly Bears;Forest", 3, "Flame Slash", "Hill Giant;Mountain"),
                    engineMin(true, 2)), c + " saves the blocker");
        }
        Assert.assertTrue(proven(frame("", "Grizzly Bears;Forest", 3, "Flame Slash", "Hill Giant;Mountain"),
                engineMin(true, 2)), "open control: Flame Slash, then the attack, proven");
        // Collected Company at the attack point: two Bears from their library block (their library is Bears)
        final String bears = "Grizzly Bears;Grizzly Bears;Grizzly Bears;Grizzly Bears;Grizzly Bears;Grizzly Bears;"
                + "Grizzly Bears;Grizzly Bears;Grizzly Bears;Grizzly Bears";
        final String st = ForcedWinSolverTest.frame("Collected Company", "Forest;Forest;Forest;Forest", 3, "",
                "Hill Giant;Mountain", 20, "ai").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + bears);
        Assert.assertFalse(proven(ForcedWinSolverTest.game("Default", "Default", st), engineMin(true, 2)),
                "Collected Company puts blockers in");
        // Ashnod's Altar: sacrificing their creature fizzles our Act of Treason (a mana ability with a sacrifice cost)
        final Game alt = frame("", "Ashnod's Altar;Hill Giant", 3, "Act of Treason", "Mountain;Mountain;Mountain");
        Assert.assertTrue(OppMin.answers(alt, alt.getPlayers().get(0), alt.getPlayers().get(1), null).gaps
                .contains("mana-ability"), "a sac outlet is a gap");
        Assert.assertFalse(proven(frame("", "Ashnod's Altar;Hill Giant", 3, "Act of Treason", "Mountain;Mountain;Mountain"),
                engineMin(true, 2)), "Ashnod's Altar: not proven");
        // a synthetic exception in canPlay: the answer is a gap ("unreadable"), not a silent drop
        OppMin.TEST_THROW = sa -> sa.getHostCard() != null && "Healing Salve".equals(sa.getHostCard().getName());
        try {
            final Game cs = frame("Healing Salve", "Plains", 3, "Lightning Bolt", "Mountain");
            Assert.assertTrue(OppMin.answers(cs, cs.getPlayers().get(0), cs.getPlayers().get(1), null).gaps
                    .contains("unreadable"), "a canPlay exception is the gap unreadable");
            Assert.assertFalse(proven(frame("Healing Salve", "Plains", 3, "Lightning Bolt", "Mountain"),
                    passive(cfg(true, 64))), "an unreadable answer: not proven");
        } finally {
            OppMin.TEST_THROW = null;
        }
    }

    /**
     * Review #148 re-check 5: no incidental set. A cantrip can trigger their permanents: Opt with Sheoldred out gains
     * them 2 against our Bolt at 3; Opt with Young Pyromancer (tapped) makes a token that blocks our Hill Giant.
     */
    @Test
    public void triggersOnTheirCantripsAnswerTheLine() {
        Assert.assertFalse(proven(frame("Opt", "Sheoldred, the Apocalypse;Island", 3, "Lightning Bolt", "Mountain"),
                passive(cfg(true, 64))), "Opt draws: Sheoldred gains them 2");
        Assert.assertTrue(proven(frame("", "Sheoldred, the Apocalypse;Island", 3, "Lightning Bolt", "Mountain"),
                passive(cfg(true, 64))), "open control: no cantrip, the Bolt is proven");
        Assert.assertFalse(proven(frame("Opt", "Young Pyromancer|Tapped;Island", 3, "", "Hill Giant;Mountain"),
                engineMin(true, 2)), "Opt makes a Young Pyromancer token that blocks");
    }

    /**
     * Review #148 re-check 6 (fail closed): Ghostly Flicker's two targets (Man-o'-War bounces our attacker), a Counterspell
     * they may cast from their library top under Future Sight, and Pristine Talisman's life gain (a mana ability with a
     * rider) are not proven; each open control is. Same-named targets in different states (a +1/+1 counter, a
     * Bonesplitter) are separate answers.
     */
    @Test
    public void twoTargetAnswersFailClosed() {
        Assert.assertFalse(proven(frame("Ghostly Flicker", "Man-o'-War|Tapped;Island;Island;Island", 3, "",
                "Hill Giant;Mountain"), engineMin(true, 2)), "Ghostly Flicker on Man-o'-War and a land bounces the Giant");
        Assert.assertTrue(proven(frame("", "Man-o'-War|Tapped;Island;Island;Island", 3, "", "Hill Giant;Mountain"),
                engineMin(true, 2)), "open control: no Flicker, the attack is proven");
    }

    @Test
    public void cardsTheyMayPlayFromElsewhereAreAnswers() {
        final String top = "Counterspell;Island;Island;Island;Island;Island;Island;Island;Island;Island";
        final String fs = ForcedWinSolverTest.frame("", "Future Sight;Island;Island", 3, "Lightning Bolt", "Mountain", 20,
                "ai").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + top);
        Assert.assertFalse(proven(ForcedWinSolverTest.game("Default", "Default", fs), passive(cfg(true, 64))),
                "Future Sight: the Counterspell on their library top answers the Bolt");
        final String nofs = ForcedWinSolverTest.frame("", "Island;Island", 3, "Lightning Bolt", "Mountain", 20, "ai")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + top);
        Assert.assertTrue(proven(ForcedWinSolverTest.game("Default", "Default", nofs), passive(cfg(true, 64))),
                "open control: no Future Sight, the library top is not playable");
    }

    @Test
    public void sameNamedTargetsInDifferentStatesAreSeparate() {
        // same-named targets in different states are separate answers (the name collapse kept one Shock for both)
        for (String bf : new String[] {"Grizzly Bears;Grizzly Bears|Counters:P1P1=1;Forest",
                "Grizzly Bears|Id:901;Bonesplitter|AttachedTo:901;Grizzly Bears;Forest"}) {
            final Game sg = frame("Shock", "Mountain", 20, "", bf);
            final Player def = sg.getPlayers().get(0), ai = sg.getPlayers().get(1);
            int bears = 0;
            for (forge.game.spellability.SpellAbility sa : OppMin.responses(sg, def, ai, null)) {
                for (forge.game.GameObject o : sa.getTargets()) {
                    bears += o instanceof forge.game.card.Card c && c.getName().equals("Grizzly Bears") ? 1 : 0;
                }
            }
            Assert.assertEquals(bears, 2, "a Shock on each Bears: " + bf);
        }
    }

    @Test
    public void manaAbilitiesWithARiderAreAnswers() {
        Assert.assertFalse(proven(frame("", "Pristine Talisman", 3, "Lightning Bolt", "Mountain"), passive(cfg(true, 64))),
                "Pristine Talisman gains them 1");
        Assert.assertTrue(proven(frame("", "Sol Ring", 3, "Lightning Bolt", "Mountain"), passive(cfg(true, 64))),
                "open control: a pure mana ability is not an answer");
    }

    /** Put {@code p}'s spell or ability on the stack with its target set (no resolution). */
    static forge.game.spellability.SpellAbility put(final Game g, final Player p, final String name,
            final forge.game.zone.ZoneType z, final forge.game.GameObject target) {
        forge.game.card.Card host = null;
        for (forge.game.card.Card c : p.getCardsIn(z)) if (c.getName().equals(name)) host = c;
        Assert.assertNotNull(host, name);
        forge.game.spellability.SpellAbility sa = null;
        for (forge.game.spellability.SpellAbility x : host.getSpellAbilities()) {
            if (!x.isManaAbility() && (z != forge.game.zone.ZoneType.Battlefield || !x.isSpell())) {
                sa = x;
                break;
            }
        }
        sa.setActivatingPlayer(p);
        sa.resetTargets();
        sa.getTargets().add(target);
        Assert.assertTrue(((forge.ai.PlayerControllerAi) p.getController()).playChosenSpellAbility(sa), name);
        return g.getStack().peekAbility();
    }

    /** Their Lightning Bolt (at us) below our Rod of Ruin activation (at them): their answer set at our ability. */
    static OppMin.Answers theirBoltBelowOurRod(final String answer) {
        final Game g = frame("Lightning Bolt;" + answer, "Mountain;Mountain;Mountain;Island;Island;Island;Island", 20, "",
                "Rod of Ruin;Mountain;Mountain;Mountain");
        final Player def = g.getPlayers().get(0), ai = g.getPlayers().get(1);
        put(g, def, "Lightning Bolt", forge.game.zone.ZoneType.Hand, ai);
        final forge.game.spellability.SpellAbility rod = put(g, ai, "Rod of Ruin", forge.game.zone.ZoneType.Battlefield, def);
        Assert.assertEquals(g.getStack().size(), 2);
        return OppMin.answers(g, def, ai, rod);
    }

    /**
     * Review #148 re-check 7 (S1-1): a modal answer whose only targets are their own stack objects (Insidious Will:
     * counter or copy their Bolt below our ability) is a gap, not "no response"; S2: Reverberate (non-modal) likewise.
     */
    @Test
    public void targetsOnTheirOwnStackAreGaps() {
        final OppMin.Answers iw = theirBoltBelowOurRod("Insidious Will");
        Assert.assertTrue(iw.gaps.contains("own-stack-target"), "Insidious Will: " + iw.gaps);
        final OppMin.Answers rv = theirBoltBelowOurRod("Reverberate");
        Assert.assertTrue(rv.gaps.contains("own-stack-target"), "Reverberate: " + rv.gaps);
    }

    /**
     * Review #148 re-check 7 (S1-2): an "any player may activate" ability on our permanent is their answer. Ribbon Snake
     * loses flying for their {2}, and their Bears block it; without the {2} the attack is proven.
     */
    @Test
    public void abilitiesTheyMayActivateOnOurPermanentsAreAnswers() {
        final Game rs = frame("", "Grizzly Bears;Mountain;Mountain", 2, "", "Ribbon Snake;Island");
        boolean snake = false;
        for (forge.game.spellability.SpellAbility sa : OppMin.answers(rs, rs.getPlayers().get(0), rs.getPlayers().get(1),
                null).list) {
            snake |= sa.getHostCard().getName().equals("Ribbon Snake");
        }
        Assert.assertTrue(snake, "the Ribbon Snake activation is their answer");
        Assert.assertFalse(proven(frame("", "Grizzly Bears;Mountain;Mountain", 2, "", "Ribbon Snake;Island"),
                engineMin(true, 2)), "they pay {2}: the Snake loses flying and is blocked");
        Assert.assertTrue(proven(frame("", "Grizzly Bears", 2, "", "Ribbon Snake;Island"), engineMin(true, 2)),
                "open control: no {2}, the flyer is unblockable for them");
    }

    /**
     * Review #148 re-check 7 (S1-3): permanents are keyed by card id. Two attacking Bears, one made indestructible by our
     * Sheltering Light, against them at 4 with one Murder: Murder on the other Bears refutes the attack.
     */
    @Test
    public void permanentsAreKeyedByCardId() {
        final java.util.function.Function<String, Game> board = h -> {
            final Game g = frame(h, "Swamp;Swamp;Swamp", 4, "Sheltering Light", "Grizzly Bears;Grizzly Bears;Plains");
            final Player ai = g.getPlayers().get(1);
            forge.game.card.Card first = null;
            for (forge.game.card.Card c : ai.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (first == null && c.getName().equals("Grizzly Bears")) first = c;
            }
            put(g, ai, "Sheltering Light", forge.game.zone.ZoneType.Hand, first);
            int guard = 0;
            while (!g.getStack().isEmpty() && guard++ < 5) {
                g.getStack().resolveStack();
                g.getAction().checkStateEffects(true);
            }
            Assert.assertTrue(first.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE), "the first Bears is indestructible");
            return g;
        };
        final Game mg = board.apply("Murder");
        int murders = 0;
        for (forge.game.spellability.SpellAbility sa : OppMin.responses(mg, mg.getPlayers().get(0), mg.getPlayers().get(1),
                null)) {
            murders += sa.getHostCard().getName().equals("Murder") ? 1 : 0;
        }
        Assert.assertEquals(murders, 2, "a Murder on each Bears (not one for both)");
        Assert.assertFalse(proven(board.apply("Murder"), engineMin(true, 2)), "Murder on the other Bears");
        Assert.assertTrue(proven(board.apply(""), engineMin(true, 2)), "open control: no Murder, the Bears are lethal");
    }

    /**
     * Review #148 re-check 8: answers are keyed by their source card's id. Two tapped Spikeshot Elders, a 1/1 and a 3/3,
     * with mana for one activation, against our lethal Hill Giant: the 3/3's shot kills it. Only the 1/1: proven. Our own
     * hand is a source only behind the castable gate: our Bolt is not their answer.
     */
    @Test
    public void answersAreKeyedBySourceCard() {
        final String two = "Spikeshot Elder|Tapped;Spikeshot Elder|Tapped|Counters:P1P1=2;Mountain;Mountain;Mountain";
        final Game eg = frame("", two, 3, "", "Hill Giant;Mountain");
        boolean big = false;
        for (forge.game.spellability.SpellAbility sa : OppMin.answers(eg, eg.getPlayers().get(0), eg.getPlayers().get(1),
                null).list) {
            big |= sa.getHostCard().getNetPower() == 3;
        }
        Assert.assertTrue(big, "the 3/3 Elder (the higher id) is a source too");
        Assert.assertFalse(proven(frame("", two, 3, "", "Hill Giant;Mountain"), engineMin(true, 2)), "the 3/3 Elder's shot");
        Assert.assertTrue(proven(frame("", "Spikeshot Elder|Tapped;Mountain;Mountain;Mountain", 3, "", "Hill Giant;Mountain"),
                engineMin(true, 2)), "open control: only the 1/1 Elder, proven");
        final Game g = frame("", "Mountain;Mountain", 20, "Lightning Bolt", "Mountain");
        for (forge.game.spellability.SpellAbility sa : OppMin.answers(g, g.getPlayers().get(0), g.getPlayers().get(1),
                null).list) {
            Assert.assertFalse(sa.getHostCard().getOwner() == g.getPlayers().get(1)
                    && sa.getHostCard().isInZone(forge.game.zone.ZoneType.Hand), "our hand card as their answer: " + sa);
        }
    }

    /**
     * Review #148 re-check 9: an optional-target answer (TargetMin 0) is castable with no target and its untargeted rider
     * still resolves. Turn the Earth with both graveyards empty gains them 2 against our Hill Giant attack at 3: not
     * proven. Without the Forests to cast it: proven.
     */
    @Test
    public void optionalTargetAnswersWithNoCandidateAreAnswers() {
        // at our attack point (no stack object to target): our Hill Giant for their last 3
        Assert.assertFalse(proven(frame("Turn the Earth", "Forest;Forest", 3, "", "Hill Giant;Mountain"), engineMin(true, 2)),
                "Turn the Earth with no target gains them 2");
        Assert.assertTrue(proven(frame("Turn the Earth", "", 3, "", "Hill Giant;Mountain"), engineMin(true, 2)),
                "open control: no mana for it, proven");
    }

    /**
     * Ruling 2026-11-01 (self-damage mana riders): Ancient Tomb's rider only damages its controller, so with nothing of
     * theirs reading their life it is not an answer and our Bolt line is proven; with their Death's Shadow out (its size
     * reads their life) it stays a gap and the line is not proven.
     */
    @Test
    public void selfDamageManaRidersAreNotAnswersUnlessLifeMatters() {
        Assert.assertTrue(proven(frame("", "Ancient Tomb", 3, "Lightning Bolt", "Mountain"), passive(cfg(true, 64))),
                "Ancient Tomb alone: proven");
        Assert.assertFalse(proven(frame("", "Ancient Tomb;Death's Shadow", 3, "Lightning Bolt", "Mountain"),
                passive(cfg(true, 64))), "with Death's Shadow: a gap, not proven");
        Assert.assertFalse(proven(frame("", "Pristine Talisman", 3, "Lightning Bolt", "Mountain"), passive(cfg(true, 64))),
                "a life-gain rider is still an answer");
    }

    /**
     * Review #148 re-check 10 S3: a TargetMax-1 optional answer (TargetMin 0) enumerated and actually played with zero
     * targets. Courageous Resolve ("up to one target creature you control gains protection from your opponents; draw a
     * card; fateful hour: with 5 or less life you can't lose life or the game this turn") with no creature of theirs to
     * target, at their 3 life against our Bolt: enumerated with no target, played (the line's failure is a lost MIN
     * branch, not a cap or gap), and the line is not proven. With no mana for it: proven.
     */
    @Test
    public void optionalTargetMax1AnswerIsPlayedWithZeroTargets() {
        final Game g = frame("Courageous Resolve", "Plains;Plains;Plains", 3, "Lightning Bolt", "Mountain");
        final Player def = g.getPlayers().get(0), ai = g.getPlayers().get(1);
        boolean zero = false;
        for (forge.game.spellability.SpellAbility sa : OppMin.responses(g, def, ai, null)) {
            zero |= sa.getHostCard().getName().equals("Courageous Resolve") && sa.getTargets().isEmpty();
        }
        Assert.assertTrue(zero, "Courageous Resolve is enumerated with zero targets");
        final Game g2 = frame("Courageous Resolve", "Plains;Plains;Plains", 3, "Lightning Bolt", "Mountain");
        g2.AI_TIMEOUT = 600;
        final List<ForcedWinSolver.Proposal> ps = new ForcedWinSolver(g2.getPlayers().get(1), passive(cfg(true, 64))).propose(g2);
        Assert.assertFalse(ps.isEmpty(), "the Bolt line wins unopposed");
        for (ForcedWinSolver.Proposal p : ps) {
            Assert.assertFalse(p.proven(1), p.text);
            Assert.assertTrue(p.text.contains("fails=min"), "a played and lost MIN branch, not a cap: " + p.text);
        }
        Assert.assertTrue(proven(frame("Courageous Resolve", "", 3, "Lightning Bolt", "Mountain"), passive(cfg(true, 64))),
                "no mana for it: proven");
    }

    /**
     * Review #161 S2-3: their life-reading spell on the stack makes their Ancient Tomb's rider an answer again (a gap):
     * lifeSensitive reads their items on the stack.
     */
    @Test
    public void aLifeReadingSpellOnTheStackKeepsTheRiderAnAnswer() {
        final Game quiet = frame("", "Ancient Tomb", 3, "Lightning Bolt", "Mountain");
        Assert.assertFalse(OppMin.lifeSensitive(quiet.getPlayers().get(0)), "Ancient Tomb alone reads no life");
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("", "Ancient Tomb", 3,
                "Lightning Bolt", "Mountain", 20, "ai") + "humanputonstack=Death's Shadow\n");
        final Player def = g.getPlayers().get(0);
        Assert.assertFalse(g.getStack().isEmpty(), "Death's Shadow is on the stack");
        Assert.assertTrue(OppMin.lifeSensitive(def), "their Death's Shadow on the stack reads their life");
        final forge.game.card.Card tomb = ForcedWinSolverTest.inZone(def, forge.game.zone.ZoneType.Battlefield, "Ancient Tomb");
        for (forge.game.spellability.SpellAbility sa : tomb.getManaAbilities()) {
            Assert.assertFalse(OppMin.selfDamageOnly(sa, def), "the rider stays an answer: " + sa);
        }
    }

    /**
     * coverage-fix-1102 (meter only): a world cut by a gap records its reason and the answer card behind it in the row
     * meter when Meter#reasons is on, and nothing new when it is off. Pristine Talisman (a rider beyond mana) is the
     * mana-ability gap against our Bolt.
     */
    @Test
    public void cutReasonsAndCardsAreMeteredOnlyWhenAsked() {
        for (boolean on : new boolean[] {false, true}) {
            final Game g = frame("", "Pristine Talisman", 3, "Lightning Bolt", "Mountain");
            g.AI_TIMEOUT = 600;
            final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(1), passive(cfg(true, 64)));
            s.meter.reasons = on;
            for (ForcedWinSolver.Proposal p : s.propose(g)) {
                Assert.assertFalse(p.proven(1), p.text);
            }
            final com.google.gson.JsonObject m = s.meter.toJson();
            Assert.assertTrue(m.get("opp_min_cuts").getAsLong() > 0, m.toString());
            Assert.assertEquals(m.has("opp_min_cut_reasons"), on, m.toString());
            if (on) {
                Assert.assertTrue(m.getAsJsonObject("opp_min_cut_reasons").get("mana-ability").getAsLong() > 0, m.toString());
                Assert.assertEquals(m.getAsJsonObject("opp_min_cap_cards").getAsJsonArray("mana-ability").get(0).getAsString(),
                        "Pristine Talisman", m.toString());
            }
        }
    }

    static ForcedWinSolver.Config manaSearch() {
        final ForcedWinSolver.Config c = cfg(true, 64);
        c.oppManaSearch = true;
        return passive(c);
    }

    /** The proposals' texts and the meter of one solver run (covfix-a-1102). */
    static Object[] run(final Game g, final ForcedWinSolver.Config c) {
        g.AI_TIMEOUT = 600;
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(1), c);
        s.meter.reasons = true;
        boolean proven = false;
        final StringBuilder texts = new StringBuilder();
        for (ForcedWinSolver.Proposal p : s.propose(g)) {
            proven |= p.proven(1);
            texts.append(p.text).append(' ');
        }
        return new Object[] {proven, texts.toString(), s.meter.toJson()};
    }

    /**
     * covfix-a-1102 (ARCH-PROPOSAL-1102 layer 3): with oppManaSearch the MIN plays the opponent's mana abilities that
     * have a non-mana part, instead of failing closed on them. Our Bolt at their 3 life:
     * - Pristine Talisman (gain 1 life) is a real answer: a lost MIN branch, not a cap;
     * - Ancient Tomb's damage to themselves, even with their Death's Shadow out, and Lotus Petal's sacrifice answer nothing:
     *   proven (where the readers' rule kept Death's Shadow a gap);
     * - no mana-ability cut reason is left in the meter.
     */
    @Test
    public void manaAbilitiesAreSearchedNotGapped() {
        final Object[] tal = run(frame("", "Pristine Talisman", 3, "Lightning Bolt", "Mountain"), manaSearch());
        Assert.assertFalse((Boolean) tal[0], (String) tal[1]);
        Assert.assertTrue(((String) tal[1]).contains("fails=min"), "a played and lost MIN branch: " + tal[1]);
        Assert.assertFalse(((com.google.gson.JsonObject) tal[2]).has("opp_min_cut_reasons"), "no cut: " + tal[2]);
        for (String bf : new String[] {"Ancient Tomb", "Ancient Tomb;Death's Shadow", "Lotus Petal"}) {
            final Object[] r = run(frame("", bf, 3, "Lightning Bolt", "Mountain"), manaSearch());
            Assert.assertTrue((Boolean) r[0], bf + ": " + r[1] + " " + r[2]);
        }
        // the reader path is unchanged with the key off: Death's Shadow keeps Ancient Tomb a gap
        Assert.assertFalse((Boolean) run(frame("", "Ancient Tomb;Death's Shadow", 3, "Lightning Bolt", "Mountain"),
                passive(cfg(true, 64)))[0]);
    }

    static ForcedWinSolver.Config manaSearchEngine() {
        final ForcedWinSolver.Config c = cfgEngine(true);
        c.oppDepth = 2;
        c.oppManaSearch = true;
        return passive(c);
    }

    /**
     * Review #169 S1-1: the permanent a mana ability's cost sacrifices is the MIN's choice, one response per legal
     * choice, never Forge AI's. Our Act of Treason on their Craw Wurm, attacking for their last 6: their Ashnod's Altar
     * can sacrifice the Wurm in response (the line fails) or their Grizzly Bears (it does not). Not proven; with no Altar,
     * proven.
     */
    @Test
    public void aSacrificeCostIsTheMinsChoice() {
        final Object[] alt = run(frame("", "Ashnod's Altar;Craw Wurm;Grizzly Bears", 6, "Act of Treason",
                "Mountain;Mountain;Mountain"), manaSearchEngine());
        final Object[] none = run(frame("", "Craw Wurm;Grizzly Bears", 6, "Act of Treason", "Mountain;Mountain;Mountain"),
                manaSearchEngine());
        Assert.assertTrue((Boolean) none[0], "control: the steal-and-attack line is proven with no Altar: " + none[1]);
        Assert.assertFalse((Boolean) alt[0], "sacrificing the stolen Wurm is a response: " + alt[1] + " " + alt[2]);
    }

    /**
     * Review #169 S1-2: after a mana activation the same priority is a new point, its answers read again. Their Mana
     * Flare and two Islands, Cancel (1UU) in hand: not castable before a tap, castable after one (Mana Flare doubles
     * it). Our Bolt at their 3: not proven. With no Mana Flare, Cancel is never castable: proven.
     */
    @Test
    public void aCounterCastableOnlyAfterAManaTapIsSearched() {
        final Object[] flare = run(frame("Cancel", "Mana Flare;Island;Island", 3, "Lightning Bolt", "Mountain"), manaSearch());
        final Object[] none = run(frame("Cancel", "Island;Island", 3, "Lightning Bolt", "Mountain"), manaSearch());
        Assert.assertTrue((Boolean) none[0], "control: Cancel is never castable: " + none[1]);
        Assert.assertFalse((Boolean) flare[0], "the tap, then Cancel: " + flare[1] + " " + flare[2]);
    }

    /**
     * Review #169 re-check S1: with a mana trigger out, the colour a mana ability produces decides what is castable next.
     * Their Mana Flare, one land and Counterspell (UU), against our Bolt at their 3:
     * - a Volcanic Island's colours are separate abilities (U, R): the U branch makes UU and Counterspell answers: not
     *   proven (a played MIN branch);
     * - Mana Confluence ("any colour", Forge AI's pick) is the gap mana-color-choice in the answer set, never a Forge pick:
     *   not proven;
     * - without Mana Flare, Counterspell is never castable from one land: proven either way.
     */
    @Test
    public void producedColoursUnderAManaTriggerAreNotForgesPick() {
        final Object[] vol = run(frame("Counterspell", "Mana Flare;Volcanic Island", 3, "Lightning Bolt", "Mountain"), manaSearch());
        Assert.assertFalse((Boolean) vol[0], "Volcanic for U, then Counterspell: " + vol[1] + " " + vol[2]);
        final Object[] conf = run(frame("Counterspell", "Mana Flare;Mana Confluence", 3, "Lightning Bolt", "Mountain"), manaSearch());
        Assert.assertFalse((Boolean) conf[0], conf[1] + " " + conf[2]);
        // the answer set itself carries the gap (here Forge's payment check also finds Counterspell castable at once, so the
        // MIN stops at that real answer before the gap matters)
        final Game cg = frame("Counterspell", "Mana Flare;Mana Confluence", 3, "Lightning Bolt", "Mountain");
        Assert.assertTrue(OppMin.answers(cg, cg.getPlayers().get(0), cg.getPlayers().get(1), null, true).gaps
                .contains("mana-color-choice"), "the colour choice is a gap");
        final Game ng = frame("Counterspell", "Mana Confluence", 3, "Lightning Bolt", "Mountain");
        Assert.assertFalse(OppMin.answers(ng, ng.getPlayers().get(0), ng.getPlayers().get(1), null, true).gaps
                .contains("mana-color-choice"), "no mana trigger: no colour gap");
        for (String bf : new String[] {"Volcanic Island", "Mana Confluence"}) {
            final Object[] r = run(frame("Counterspell", bf, 3, "Lightning Bolt", "Mountain"), manaSearch());
            Assert.assertTrue((Boolean) r[0], "no Flare, " + bf + ": " + r[1] + " " + r[2]);
        }
    }

    /** covfix-a-1102: oppMinRev 2 is a recorded change (its own sha); rev 1 (absent or explicit) is the old spec. */
    @Test
    public void oppMinRevTwoIsARecordedChange() {
        final com.google.gson.JsonObject o = WinConditionCoverageTest.spec();
        final HonestLabel.Spec v1 = HonestLabel.Spec.parse(o);
        Assert.assertEquals(v1.winConfig().oppResponses, 4);
        Assert.assertFalse(v1.winConfig().oppManaSearch || v1.lossConfig().oppManaSearch);
        final com.google.gson.JsonObject r1 = o.deepCopy();
        r1.addProperty("oppMinRev", 1);
        Assert.assertEquals(HonestLabel.Spec.parse(r1).sha(), v1.sha(), "rev 1 is the old spec");
        final com.google.gson.JsonObject r2 = o.deepCopy();
        r2.addProperty("oppMinRev", 2);
        final HonestLabel.Spec v2 = HonestLabel.Spec.parse(r2);
        Assert.assertNotEquals(v2.sha(), v1.sha());
        Assert.assertEquals(v2.winConfig().oppResponses, 16);
        Assert.assertEquals(v2.lossConfig().oppResponses, 16);
        Assert.assertTrue(v2.winConfig().oppManaSearch && v2.lossConfig().oppManaSearch);
        r2.getAsJsonObject("solverCfg").addProperty("oppResponses", "8");
        Assert.assertEquals(HonestLabel.Spec.parse(r2).winConfig().oppResponses, 8, "an explicit key wins");
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
