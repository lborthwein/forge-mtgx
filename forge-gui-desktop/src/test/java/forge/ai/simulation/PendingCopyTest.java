package forge.ai.simulation;

import java.util.List;

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
 * gamecopier-fidelity-1030 (need N6): the solver's copies carry what the root has pending (GameCopier#setCopyPending),
 * and HonestLabel leaves a position unresolved when they cannot. The four probes of the #154 review (Summoner's Pact's
 * upkeep trigger, an until-end-of-turn pump, Kiki-Jiki's end-step sacrifice, a Bolt on the stack) were false PROVEN
 * labels with every solver key off: the verification copies dropped the delayed trigger or the until command. Each
 * probe here checks the copy itself (the pending object carried, and acting in the copy as in the original) and the
 * label (default kill spec, keys off), against the real game played on by Forge AI.
 */
public class PendingCopyTest extends forge.ai.AITest {

    static Card find(Player p, ZoneType z, String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    static void cast(Game g, Card c, SpellAbility sa) {
        final Card moved = g.getAction().moveToStack(c, sa);
        sa.setHostCard(moved);
        g.getStack().add(sa);
    }

    static Game base(String bf, String hand, String lib, int oppLife) {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("pc", WinConditionCoverageTest.V.NOT_PROVEN, "pc")
                .k("humanbattlefield", bf).k("humanhand", hand).k("humanlibrary", lib).k("ailife", "" + oppLife);
        return ForcedWinSolverTest.game("Default", "Default", p.frame());
    }

    /** Resolve what is on the stack, back to our main phase 1 (the label root). */
    static void settle(Game g, Player me) {
        GameSimulator.resolveStack(g, me.getOpponents().get(0));
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    static GameCopier copier(Game g, boolean pending) {
        final GameCopier c = new GameCopier(g, true);
        c.setCopyFidelity(1);
        c.setCopyPending(pending);
        return c;
    }

    /** The label (default kill spec, keys off) and the real game played on to the end of our next turn. */
    static JsonObject labelAndTruth(String tag, Game g, Player me, boolean[] realWin) {
        final JsonObject r = WinConditionCoverageTest.label(g, me, 42L);
        System.out.println("PC " + tag + " label=" + r);
        final Player opp = me.getOpponents().get(0);
        ForcedWinSolverTest.play(g, g.getPhaseHandler().getTurn() + 2);
        realWin[0] = opp.hasLost() && !me.hasLost();
        System.out.println("PC " + tag + " real: meLost=" + me.hasLost() + " oppLost=" + opp.hasLost() + " oppLife="
                + opp.getLife() + " turn=" + g.getPhaseHandler().getTurn());
        return r;
    }

    static String label(JsonObject r) {
        return WinConditionCoverageTest.str(r, "label");
    }

    // ------------------------------------------------------------------ Summoner's Pact: a delayed trigger at the root

    static Game pact(String bf, boolean castIt) {
        final Game g = base(bf, "Summoner's Pact", "Llanowar Elves;" + WinConditionCoverageTest.n("Island", 9), 2);
        final Player me = g.getPlayers().get(0);
        if (castIt) {
            final Card pact = find(me, ZoneType.Hand, "Summoner's Pact");
            final SpellAbility sa = pact.getFirstSpellAbility();
            sa.setActivatingPlayer(me);
            cast(g, pact, sa);
        }
        settle(g, me);
        return g;
    }

    @Test(timeOut = 900000)
    public void pactTriggerIsCarriedAndActsInTheCopy() {
        final Game g = pact("Grizzly Bears|SummonSick", true);
        final Player me = g.getPlayers().get(0);
        Assert.assertEquals(g.getTriggerHandler().getDelayedTriggersView().size(), 1);
        Assert.assertTrue(GameCopier.hasPending(g));
        Assert.assertNull(GameCopier.unsupportedPending(g), "the Pact's trigger is carried");
        final GameCopier c = copier(g, true);
        final Game cg = c.makeCopy();
        Assert.assertEquals(c.getPendingGaps().size(), 0, String.valueOf(c.getPendingGaps()));
        Assert.assertEquals(cg.getTriggerHandler().getDelayedTriggersView().size(), 1);
        final forge.game.trigger.Trigger t = cg.getTriggerHandler().getDelayedTriggersView().get(0);
        Assert.assertSame(t.getHostCard().getGame(), cg);
        Assert.assertSame(t.getSpawningAbility().getActivatingPlayer(), c.find(me));
        // the copy, played on, loses at our next upkeep (no lands to pay), as the original does
        final Player cm = c.find(me);
        ForcedWinSolverTest.play(cg, cg.getPhaseHandler().getTurn() + 2);
        Assert.assertTrue(cm.hasLost(), "the copy keeps the Pact's 'pay or lose'");
        // the copier without the flag: the original behaviour (the trigger is dropped)
        Assert.assertEquals(copier(g, false).makeCopy().getTriggerHandler().getDelayedTriggersView().size(), 0);
    }

    @Test(timeOut = 900000)
    public void pactUpkeepTrigger() {
        // review probe 1: no lands, the Pact's upkeep trigger loses the game before the Bears can attack (opponent at 2)
        final Game g = pact("Grizzly Bears|SummonSick", true);
        final boolean[] real = new boolean[1];
        final JsonObject r = labelAndTruth("pact", g, g.getPlayers().get(0), real);
        Assert.assertFalse(real[0], "ground truth: we lose at upkeep");
        Assert.assertNotEquals(label(r), "win", "false PROVEN: " + r);
    }

    /** Nothing pending: summoning-sick Bears, the opponent at 2, a win next turn. */
    static Game bearsOnly() {
        final Game g = base("Grizzly Bears|SummonSick", "", WinConditionCoverageTest.n("Island", 10), 2);
        settle(g, g.getPlayers().get(0));
        return g;
    }

    @Test(timeOut = 900000)
    public void pactNoTriggerControl() {
        // review probe 1 control: nothing pending (no Pact), the Bears win next turn; the proof stands
        final Game g = bearsOnly();
        Assert.assertFalse(GameCopier.hasPending(g));
        Assert.assertNull(GameCopier.unsupportedPending(g));
        final boolean[] real = new boolean[1];
        final JsonObject r = labelAndTruth("pact-control", g, g.getPlayers().get(0), real);
        Assert.assertTrue(real[0]);
        Assert.assertEquals(label(r), "win", r.toString());
        Assert.assertFalse(r.has("copy_gap"));
    }

    @Test(timeOut = 900000)
    public void pactHarmlessTriggerProofStands() {
        // a pending delayed trigger that does not change the outcome: four Forests pay the Pact at upkeep, then the
        // Bears attack for the win; the proof must stand (the trigger carried, the copy pays as the real game does)
        final Game g = pact("Grizzly Bears|SummonSick;Forest;Forest;Forest;Forest", true);
        Assert.assertEquals(g.getTriggerHandler().getDelayedTriggersView().size(), 1);
        Assert.assertNull(GameCopier.unsupportedPending(g));
        final boolean[] real = new boolean[1];
        final JsonObject r = labelAndTruth("pact-paid", g, g.getPlayers().get(0), real);
        Assert.assertTrue(real[0], "ground truth: we pay and win");
        Assert.assertEquals(label(r), "win", r.toString());
        Assert.assertFalse(r.has("copy_gap"));
    }

    // ------------------------------------------------------------------ Giant Growth: an until-end-of-turn pump

    static Game pump() {
        final Game g = base("Grizzly Bears|SummonSick", "Giant Growth", WinConditionCoverageTest.n("Island", 10), 5);
        final Player me = g.getPlayers().get(0);
        final Card gg = find(me, ZoneType.Hand, "Giant Growth");
        final Card bears = find(me, ZoneType.Battlefield, "Grizzly Bears");
        final SpellAbility sa = gg.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(bears);
        cast(g, gg, sa);
        settle(g, me);
        return g;
    }

    @Test(timeOut = 900000)
    public void pumpEndsInTheCopy() {
        final Game g = pump();
        final Player me = g.getPlayers().get(0);
        Assert.assertEquals(find(me, ZoneType.Battlefield, "Grizzly Bears").getNetPower(), 5);
        Assert.assertNull(GameCopier.unsupportedPending(g));
        final GameCopier c = copier(g, true);
        final Game cg = c.makeCopy();
        Assert.assertTrue(c.getPendingCopied() >= 1);
        final Card cb = find(c.find(me), ZoneType.Battlefield, "Grizzly Bears");
        Assert.assertEquals(cb.getNetPower(), 5);
        cg.getEndOfTurn().executeUntil();
        Assert.assertEquals(cb.getNetPower(), 2, "the copy ends the pump at cleanup");
        Assert.assertEquals(find(me, ZoneType.Battlefield, "Grizzly Bears").getNetPower(), 5, "the original is untouched");
    }

    @Test(timeOut = 900000)
    public void untilEndOfTurnPump() {
        // review probe 2: the pump ends this turn; next turn the Bears hit for 2 and the opponent (5) survives
        final Game g = pump();
        final boolean[] real = new boolean[1];
        final JsonObject r = labelAndTruth("pump", g, g.getPlayers().get(0), real);
        Assert.assertFalse(real[0]);
        Assert.assertNotEquals(label(r), "win", "false PROVEN: " + r);
    }

    // ------------------------------------------------------------------ Kiki-Jiki: a token sacrificed at the end step

    static Game kiki() {
        final Game g = base("Kiki-Jiki, Mirror Breaker;Grizzly Bears|SummonSick", "", WinConditionCoverageTest.n("Island", 10), 5);
        final Player me = g.getPlayers().get(0);
        final Card kiki = find(me, ZoneType.Battlefield, "Kiki-Jiki, Mirror Breaker");
        final Card bears = find(me, ZoneType.Battlefield, "Grizzly Bears");
        SpellAbility ab = null;
        for (SpellAbility s : kiki.getSpellAbilities()) {
            if (s.isActivatedAbility()) {
                ab = s;
            }
        }
        ab.setActivatingPlayer(me);
        ab.getTargets().add(bears);
        kiki.tap(true, null, null);
        g.getStack().add(ab);
        GameSimulator.resolveStack(g, me.getOpponents().get(0));
        g.getAction().exile(find(me, ZoneType.Battlefield, "Kiki-Jiki, Mirror Breaker"), null, null);
        settle(g, me);
        return g;
    }

    static int bears(Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Grizzly Bears")) {
                n++;
            }
        }
        return n;
    }

    @Test(timeOut = 900000)
    public void kikiTokenIsSacrificedInTheCopy() {
        final Game g = kiki();
        final Player me = g.getPlayers().get(0);
        Assert.assertEquals(bears(me), 2, "the Bears and the hasty token");
        Assert.assertEquals(g.getTriggerHandler().getDelayedTriggersView().size(), 1);
        Assert.assertNull(GameCopier.unsupportedPending(g));
        final GameCopier c = copier(g, true);
        final Game cg = c.makeCopy();
        final Player cm = c.find(me);
        Assert.assertEquals(bears(cm), 2);
        Assert.assertEquals(cg.getTriggerHandler().getDelayedTriggersView().size(), 1);
        // play the copy to our next turn's main phase: the token is gone at the end step
        int steps = 0;
        final int t0 = cg.getPhaseHandler().getTurn();
        while (!cg.isGameOver() && cg.getPhaseHandler().getTurn() == t0 && steps++ < 5000) {
            cg.getPhaseHandler().mainLoopStep();
        }
        Assert.assertEquals(bears(cm), 1, "the copy sacrifices the token at the end step");
        Assert.assertEquals(bears(me), 2, "the original is untouched");
    }

    @Test(timeOut = 900000)
    public void kikiTokenSacrifice() {
        // review probe 3: token 2 now + Bears 2 next turn = 4 < 5; a copy that keeps the token proves 6
        final Game g = kiki();
        final boolean[] real = new boolean[1];
        final JsonObject r = labelAndTruth("kiki", g, g.getPlayers().get(0), real);
        Assert.assertFalse(real[0]);
        Assert.assertNotEquals(label(r), "win", "false PROVEN: " + r);
    }

    // ------------------------------------------------------------------ Bolt on the stack (engineStack control)

    @Test(timeOut = 900000)
    public void boltOnStackControl() {
        // review probe 4: their Bolt at us (3 life) on the stack; Craw Wurm kills them (6) on our turn only if we live
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("pc", WinConditionCoverageTest.V.NOT_PROVEN, "pc")
                .k("activeplayer", "ai").k("activephase", "MAIN1").k("aiputonstack", "Lightning Bolt->human")
                .k("humanlife", "3").k("humanbattlefield", "Craw Wurm;Forest").k("ailife", "6");
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final JsonObject r = WinConditionCoverageTest.label(g, g.getPlayers().get(0), 42L);
        System.out.println("PC bolt label=" + r);
        Assert.assertNotEquals(label(r), "win", r.toString());
    }

    // ------------------------------------------------------------------ the detector: a pending command it cannot carry

    /** A command whose captured state (a map) the copier cannot remap. */
    static final class Opaque implements GameCommand {
        private static final long serialVersionUID = 1L;
        final java.util.Map<String, Card> held = new java.util.HashMap<>();

        @Override
        public void run() {
        }
    }

    @Test(timeOut = 900000)
    public void uncarriedUntilEotRefusesProofsPastThisTurn() {
        // the no-pending control (a next-turn Bears win) with an until-end-of-turn command the copier cannot carry:
        // the next-turn proof is refused (unresolved, the gap named), not PROVEN
        final Game g = bearsOnly();
        g.getEndOfTurn().addUntil(new Opaque());
        final List<String> gaps = GameCopier.unsupportedPending(g);
        Assert.assertNotNull(gaps);
        Assert.assertTrue(gaps.get(0).startsWith("EndOfTurn.until:"), gaps.toString());
        Assert.assertTrue(GameCopier.gapsMatterOnlyAfterThisTurn(gaps));
        final JsonObject r = WinConditionCoverageTest.label(g, g.getPlayers().get(0), 42L);
        System.out.println("PC gap-eot label=" + r);
        Assert.assertNotEquals(label(r), "win", r.toString());
        Assert.assertTrue(r.has("copy_gap"), r.toString());
        Assert.assertTrue(r.get("gap_refused").getAsInt() >= 1, r.toString());
    }

    @Test(timeOut = 900000)
    public void uncarriedThisTurnGapLeavesTheRootUnresolved() {
        final Game g = bearsOnly();
        g.getEndOfCombat().addUntil(new Opaque());
        final List<String> gaps = GameCopier.unsupportedPending(g);
        Assert.assertNotNull(gaps);
        Assert.assertFalse(GameCopier.gapsMatterOnlyAfterThisTurn(gaps));
        final JsonObject r = WinConditionCoverageTest.label(g, g.getPlayers().get(0), 42L);
        System.out.println("PC gap-combat label=" + r);
        Assert.assertEquals(label(r), "unresolved");
        Assert.assertEquals(WinConditionCoverageTest.str(r, "gate"), "copy-gap");
    }

    @Test(timeOut = 900000)
    public void nothingPendingIsANoOp() {
        final Game g = bearsOnly();
        Assert.assertFalse(GameCopier.hasPending(g));
        final GameCopier on = copier(g, true), off = copier(g, false);
        final Game a = on.makeCopy(), b = off.makeCopy();
        Assert.assertEquals(on.getPendingCopied(), 0);
        Assert.assertEquals(on.getPendingGaps().size(), 0);
        Assert.assertEquals(a.getCardsInGame().size(), b.getCardsInGame().size());
        Assert.assertEquals(a.getTimestamp(), b.getTimestamp());
    }

    // ------------------------------------------------------------------ Portable Hole: exiled until it leaves

    @Test(timeOut = 900000)
    public void portableHoleReturnsInTheCopy() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("pc", WinConditionCoverageTest.V.NOT_PROVEN, "pc")
                .k("humanhand", "Portable Hole").k("humanbattlefield", "Plains").k("aibattlefield", "Grizzly Bears");
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card hole = find(me, ZoneType.Hand, "Portable Hole");
        final SpellAbility sa = hole.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        cast(g, hole, sa);
        settle(g, me);
        Assert.assertNull(find(opp, ZoneType.Battlefield, "Grizzly Bears"), "the Hole exiled the Bears");
        final Card h = find(me, ZoneType.Battlefield, "Portable Hole");
        Assert.assertFalse(h.getLeavesPlayCommands().isEmpty());
        Assert.assertNull(GameCopier.unsupportedPending(g), String.valueOf(GameCopier.unsupportedPending(g)));
        final GameCopier c = copier(g, true);
        final Game cg = c.makeCopy();
        final Card ch = c.find(h);
        Assert.assertEquals(ch.getLeavesPlayCommands().size(), h.getLeavesPlayCommands().size());
        cg.getAction().destroy(ch, null, true, null);
        Assert.assertNotNull(find(c.find(opp), ZoneType.Battlefield, "Grizzly Bears"), "the copy returns the Bears");
        Assert.assertNull(find(opp, ZoneType.Battlefield, "Grizzly Bears"), "the original is untouched");
    }

    // ------------------------------------------------------------------ exiled with (Currency Converter)

    @Test(timeOut = 900000)
    public void exiledWithIsCopied() {
        final Game g = base("Currency Converter", "Llanowar Elves", WinConditionCoverageTest.n("Island", 10), 20);
        final Player me = g.getPlayers().get(0);
        final Card conv = find(me, ZoneType.Battlefield, "Currency Converter");
        final Card elves = g.getAction().exile(find(me, ZoneType.Hand, "Llanowar Elves"), null, null);
        elves.setExiledWith(conv);
        elves.setExiledBy(me);
        conv.addExiledCard(elves);
        Assert.assertTrue(elves.isValid("Card.ExiledWithSource", me, conv, null));
        // fidelity 1 (every solver copy): the link survives, so the Converter's second ability has its card
        final GameCopier c = new GameCopier(g, true);
        c.setCopyFidelity(1);
        c.makeCopy();
        final Card ce = c.find(elves), cc = c.find(conv);
        Assert.assertSame(ce.getExiledWith(), cc);
        Assert.assertTrue(cc.hasExiledCard(ce));
        Assert.assertTrue(ce.isValid("Card.ExiledWithSource", c.find(me), cc, null), "exiled with the copy's Converter");
        // fidelity 0 (the copier's default: look-ahead and other non-solver copies) drops it: the documented gap
        final GameCopier c0 = new GameCopier(g, true);
        c0.makeCopy();
        Assert.assertNull(c0.find(elves).getExiledWith());
    }
}
