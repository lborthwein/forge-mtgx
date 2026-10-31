package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.GameCommand;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Review #156 (S1-1, S1-2, S2): every pending object the copier reports as carried must act in the copy as it does in
 * the original. Each test plays the original and its pending-carrying copy on with Forge AI and compares the outcome
 * the pending object decides (Rv156ProbeTest prints the same probes, this class asserts them); the original must not
 * change when it is copied or when the copy is played. Opponent extra turns are carried (a Time Walk at the root).
 */
public class PendingCopyReviewTest extends forge.ai.AITest {

    /** Seat 0 / seat 1 (registered: a player who lost leaves getPlayers()). */
    static Player me(Game g) {
        return g.getRegisteredPlayers().get(0);
    }

    static Player opp(Game g) {
        return g.getRegisteredPlayers().get(1);
    }

    static int count(Player p, ZoneType z, String name) {
        return Rv156ProbeTest.count(p, z, name);
    }

    /**
     * Copy (pending on), check no gap and the original untouched, play the original and the copy to the end of turn
     * {@code start + turns - 1} (or {@code until} after it), and assert they agree on {@code state}.
     */
    static void sameOutcome(String tag, Game g, Function<Game, String> state, int turns, PhaseType until) {
        Assert.assertTrue(GameCopier.hasPending(g), tag + ": nothing pending");
        Assert.assertNull(GameCopier.unsupportedPending(g), tag + " gaps");
        final String before = Rv156ProbeTest.fp(g);
        final GameCopier c = PendingCopyTest.copier(g, true);
        final Game cg = c.makeCopy();
        Assert.assertTrue(c.getPendingGaps().isEmpty(), tag + " copy gaps " + c.getPendingGaps());
        Assert.assertEquals(Rv156ProbeTest.fp(g), before, tag + ": the copy changed the original");
        final int t0 = g.getPhaseHandler().getTurn();
        Rv156ProbeTest.through(cg, t0 + turns - 1, until);
        Assert.assertEquals(Rv156ProbeTest.fp(g), before, tag + ": playing the copy changed the original");
        Rv156ProbeTest.through(g, t0 + turns - 1, until);
        final String real = state.apply(g), copy = state.apply(cg);
        System.out.println("PCR " + tag + " real=" + real + " copy=" + copy);
        Assert.assertEquals(copy, real, tag + ": the carried object did not act in the copy as in the original");
    }

    static Game frame(String humanHand, String humanBf, String aiBf, int aiLife) {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("pcr", WinConditionCoverageTest.V.NOT_PROVEN, "pcr")
                .k("humanhand", humanHand).k("humanbattlefield", humanBf).k("aibattlefield", aiBf).k("ailife", "" + aiLife)
                .k("humanlibrary", WinConditionCoverageTest.n("Island", 10)).k("ailibrary", WinConditionCoverageTest.n("Island", 10));
        return ForcedWinSolverTest.game("Default", "Default", p.frame());
    }

    static void castAt(Game g, Player who, String name, Card target) {
        final Card c = PendingCopyTest.find(who, ZoneType.Hand, name);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(who);
        if (target != null) {
            sa.getTargets().add(target);
        }
        PendingCopyTest.cast(g, c, sa);
        PendingCopyTest.settle(g, me(g));
    }

    // ------------------------------------------------------------------ S1-1: Threaten (temporary controller)

    static Game threaten(int aiLife) {
        final Game g = frame("Threaten", "Mountain", "Craw Wurm", aiLife);
        castAt(g, me(g), "Threaten", PendingCopyTest.find(opp(g), ZoneType.Battlefield, "Craw Wurm"));
        Assert.assertEquals(count(me(g), ZoneType.Battlefield, "Craw Wurm"), 1, "we control the Wurm this turn");
        return g;
    }

    @Test(timeOut = 900000)
    public void threatenControlReturnsInTheCopy() {
        final Game g = threaten(20);
        sameOutcome("threaten", g, a -> "me=" + count(me(a), ZoneType.Battlefield, "Craw Wurm") + " opp="
                + count(opp(a), ZoneType.Battlefield, "Craw Wurm"), 1, null);
    }

    @Test(timeOut = 900000)
    public void threatenLabel() {
        // the stolen Wurm hits for 6 this turn (12 -> 6) and goes home at cleanup: no kill next turn
        final Game g = threaten(12);
        final boolean[] real = new boolean[1];
        final JsonObject r = PendingCopyTest.labelAndTruth("threaten", g, me(g), real);
        Assert.assertFalse(real[0]);
        Assert.assertNotEquals(PendingCopyTest.label(r), "win", "false PROVEN: " + r);
    }

    // ------------------------------------------------------------------ AtEOT, Flickerwisp, Flickerform, Pact, pump, Hole

    @Test(timeOut = 900000)
    public void unearthExileInTheCopy() {
        // AtEOT through a keyword (Unearth: exile at the next end step), the Sneak Attack / Kiki-Jiki family
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("pcr", WinConditionCoverageTest.V.NOT_PROVEN, "pcr")
                .k("humanbattlefield", "Swamp").k("humangraveyard", "Dregscape Zombie")
                .k("humanlibrary", WinConditionCoverageTest.n("Island", 10)).k("ailibrary", WinConditionCoverageTest.n("Island", 10));
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Card z = PendingCopyTest.find(me(g), ZoneType.Graveyard, "Dregscape Zombie");
        SpellAbility ab = null;
        for (SpellAbility s : z.getSpellAbilities()) {
            if (s.isActivatedAbility()) {
                ab = s;
            }
        }
        Assert.assertNotNull(ab);
        ab.setActivatingPlayer(me(g));
        g.getStack().add(ab);
        PendingCopyTest.settle(g, me(g));
        Assert.assertEquals(count(me(g), ZoneType.Battlefield, "Dregscape Zombie"), 1, "unearthed");
        sameOutcome("unearth", g, a -> "bf=" + count(me(a), ZoneType.Battlefield, "Dregscape Zombie") + " exile="
                + count(me(a), ZoneType.Exile, "Dregscape Zombie"), 1, null);
    }

    @Test(timeOut = 900000)
    public void flickerwispReturnsInTheCopy() {
        final Game g = frame("Flickerwisp", "Plains", "Grizzly Bears;Grizzly Bears", 20);
        castAt(g, me(g), "Flickerwisp", null);
        sameOutcome("flickerwisp", g, a -> "oppBears=" + count(opp(a), ZoneType.Battlefield, "Grizzly Bears") + " meBears="
                + count(me(a), ZoneType.Battlefield, "Grizzly Bears") + " exile=" + a.getCardsIn(ZoneType.Exile).size(), 1, null);
    }

    @Test(timeOut = 900000)
    public void flickerformReturnsInTheCopy() {
        final Game g = PendingCopyTest.base("Grizzly Bears;Plains;Plains;Plains;Plains", "Flickerform",
                WinConditionCoverageTest.n("Island", 10), 20);
        castAt(g, me(g), "Flickerform", PendingCopyTest.find(me(g), ZoneType.Battlefield, "Grizzly Bears"));
        SpellAbility ab = null;
        for (SpellAbility s : PendingCopyTest.find(me(g), ZoneType.Battlefield, "Flickerform").getSpellAbilities()) {
            if (s.isActivatedAbility()) {
                ab = s;
            }
        }
        Assert.assertNotNull(ab);
        ab.setActivatingPlayer(me(g));
        g.getStack().add(ab);
        PendingCopyTest.settle(g, me(g));
        sameOutcome("flickerform", g, a -> "bears=" + count(me(a), ZoneType.Battlefield, "Grizzly Bears") + " ff="
                + count(me(a), ZoneType.Battlefield, "Flickerform") + " exile=" + a.getCardsIn(ZoneType.Exile).size(), 1, null);
    }

    @Test(timeOut = 900000)
    public void pactLosesInTheCopy() {
        final Game g = PendingCopyTest.pact("Grizzly Bears|SummonSick", true);
        final Player m = me(g);
        sameOutcome("pact", g, a -> "meLost=" + me(a).hasLost(), 3, PhaseType.MAIN1);
        Assert.assertTrue(m.hasLost(), "the Pact's upkeep trigger was reached");
    }

    @Test(timeOut = 900000)
    public void pumpEndsInTheCopyPlayed() {
        sameOutcome("pump", PendingCopyTest.pump(), a -> {
            final Card b = PendingCopyTest.find(me(a), ZoneType.Battlefield, "Grizzly Bears");
            return "power=" + (b == null ? -1 : b.getNetPower());
        }, 1, null);
    }

    @Test(timeOut = 900000)
    public void kikiSacrificeInTheCopyPlayed() {
        sameOutcome("kiki", PendingCopyTest.kiki(), a -> "bears=" + PendingCopyTest.bears(me(a)), 1, null);
    }

    @Test(timeOut = 900000)
    public void ephemerateIsCarriedOrAGap() {
        // Ephemerate's rebound is a replacement effect: either carried and acting, or reported as a gap
        final Game g = PendingCopyTest.base("Grizzly Bears;Plains;Plains", "Ephemerate", WinConditionCoverageTest.n("Island", 10), 20);
        castAt(g, me(g), "Ephemerate", PendingCopyTest.find(me(g), ZoneType.Battlefield, "Grizzly Bears"));
        if (GameCopier.unsupportedPending(g) != null) {
            System.out.println("PCR ephemerate gap " + GameCopier.unsupportedPending(g));
            return;
        }
        sameOutcome("ephemerate", g, a -> "exile=" + count(me(a), ZoneType.Exile, "Ephemerate") + " gy="
                + count(me(a), ZoneType.Graveyard, "Ephemerate"), 2, PhaseType.MAIN1);
    }

    // ------------------------------------------------------------------ S1-2: their extra turn

    static Game theirTimeWalk() {
        // their end step, their Time Walk resolved; we are at 6 facing two Craw Wurms; our Bears would kill them (2)
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("pcr", WinConditionCoverageTest.V.NOT_PROVEN, "pcr")
                .k("activeplayer", "ai").k("activephase", "END_OF_TURN").k("humanlife", "6").k("ailife", "2")
                .k("humanbattlefield", "Grizzly Bears").k("aibattlefield", "Craw Wurm;Craw Wurm").k("aihand", "Time Walk")
                .k("humanlibrary", WinConditionCoverageTest.n("Island", 10)).k("ailibrary", WinConditionCoverageTest.n("Island", 10));
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Card tw = PendingCopyTest.find(opp(g), ZoneType.Hand, "Time Walk");
        final SpellAbility sa = tw.getFirstSpellAbility();
        sa.setActivatingPlayer(opp(g));
        PendingCopyTest.cast(g, tw, sa);
        GameSimulator.resolveStack(g, me(g));
        g.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp(g));
        Assert.assertTrue(g.getPhaseHandler().hasExtraTurnsOrPhases(), "Time Walk resolved");
        return g;
    }

    @Test(timeOut = 900000)
    public void theirExtraTurnIsCarried() {
        final Game g = theirTimeWalk();
        Assert.assertNull(GameCopier.unsupportedPending(g));
        final GameCopier c = PendingCopyTest.copier(g, true);
        final Game cg = c.makeCopy();
        Assert.assertSame(cg.getPhaseHandler().getNextTurn(), c.find(opp(g)), "the copy's next turn is theirs");
        Assert.assertSame(g.getPhaseHandler().getNextTurn(), opp(g));
        Assert.assertSame(PendingCopyTest.copier(g, false).makeCopy().getPhaseHandler().getNextTurn().getId(), me(g).getId(),
                "the copier without the flag drops it (the original behaviour)");
    }

    @Test(timeOut = 900000)
    public void theirTimeWalkNoFalseProof() {
        final Game g = theirTimeWalk();
        final JsonObject r = WinConditionCoverageTest.label(g, me(g), 42L);
        System.out.println("PCR timewalk label=" + r);
        Assert.assertNotEquals(PendingCopyTest.label(r), "win", "false PROVEN: " + r);
        final Player m = me(g);
        ForcedWinSolverTest.play(g, g.getPhaseHandler().getTurn() + 2);
        Assert.assertTrue(m.hasLost(), "ground truth: their extra turn's Wurm kills us");
    }

    // ------------------------------------------------------------------ S2: commands the mapper cannot know

    static final class SetHolder implements GameCommand {
        private static final long serialVersionUID = 1L;
        final java.util.Set<Card> s = new java.util.HashSet<>();

        @Override
        public void run() {
        }
    }

    static final class Box implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        Card c;
    }

    @Test(timeOut = 900000)
    public void unknownCapturesAreGapsAndNothingTouchesTheOriginal() {
        final Game g = PendingCopyTest.bearsOnly();
        final Card bears = PendingCopyTest.find(me(g), ZoneType.Battlefield, "Grizzly Bears");
        final Player foreign = me(PendingCopyTest.bearsOnly());
        final java.util.Map<String, Card> m = new java.util.HashMap<>();
        m.put("x", bears);
        final Box box = new Box();
        box.c = bears;
        final java.util.concurrent.atomic.AtomicReference<Card> ref = new java.util.concurrent.atomic.AtomicReference<>(bears);
        final Runnable nonSer = () -> bears.setTapped(true);
        final int[] counter = new int[1];
        final Object[][] refused = {
                {"map", (GameCommand) () -> m.get("x").tap(true, null, null)},
                {"box", (GameCommand) () -> box.c.tap(true, null, null)},
                {"atomicref", (GameCommand) () -> ref.get().tap(true, null, null)},
                {"nonSerRunnableRef", (GameCommand) nonSer::run},
                {"foreignPlayer", (GameCommand) () -> foreign.gainLife(1, null, null)},
                {"intArrayShared", (GameCommand) () -> counter[0]++},
                {"setHolder", new SetHolder()},
                {"nestedMap", (GameCommand) () -> ((GameCommand) () -> m.clear()).run()},
        };
        for (Object[] cs : refused) {
            g.getEndOfCombat().getUntilCommands().clear();
            g.getEndOfCombat().addUntil((GameCommand) cs[1]);
            final List<String> gaps = GameCopier.unsupportedPending(g);
            final GameCopier c = PendingCopyTest.copier(g, true);
            final Game cg = c.makeCopy();
            System.out.println("PCR capture " + cs[0] + " gaps=" + gaps);
            Assert.assertNotNull(gaps, cs[0] + ": not reported");
            Assert.assertTrue(cg.getEndOfCombat().getUntilCommands().isEmpty(), cs[0] + ": carried");
            final GameCopier strict = PendingCopyTest.copier(g, true);
            strict.setPendingStrict(true);
            Assert.assertThrows(IllegalStateException.class, strict::makeCopy);
        }
        // a card of the original in no zone: carried as a stand-in of the copy; running it leaves the original alone
        final Card loose = new Card(987654, g);
        loose.setName("Loose");
        g.getEndOfCombat().getUntilCommands().clear();
        g.getEndOfCombat().addUntil((GameCommand) () -> loose.setTapped(true));
        final Game cg = PendingCopyTest.copier(g, true).makeCopy();
        final List<GameCommand> carried = new ArrayList<>(cg.getEndOfCombat().getUntilCommands());
        Assert.assertEquals(carried.size(), 1);
        carried.get(0).run();
        Assert.assertFalse(loose.isTapped(), "the copy's command tapped the original's card");
        g.getEndOfCombat().getUntilCommands().clear();
    }
}
