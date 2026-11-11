package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinModel.ObjNeed;
import forge.ai.simulation.ForcedWinModel.ObjSeen;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-probeobj-1111 (solver key {@code probeObjects}; off by default): an ability that needs one object (a card it
 * targets, another permanent sacrificed as its cost) is learned by playing it in the sandbox with objects of ours, and
 * keeps which the engine accepted and what each run did. Card names are test data only.
 */
public class ProbeObjectsTest extends AITest {

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        kv.put("probeObjects", on ? "true" : "false");
        kv.put("engine", "false"); // the width search and its verification only
        kv.put("defense", "false");
        kv.put("act", "false");
        kv.put("budgetMs", "60000");
        kv.put("planMs", "20000");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    static String frame(final String hand, final String battlefield, final int oppLife, final String library) {
        return ForcedWinSolverTest.frame(hand, battlefield, 20, "", "Forest", oppLife, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
    }

    static ForcedWinModel.Reader reader(final boolean on) {
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.probeObjects = on;
        return r;
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

    /** Shape 1: an activated ability that copies a creature it targets. */
    @Test
    public void aCopyActivationIsLearnedWithTheCreatureItNeeds() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("",
                "Mountain;Mountain;Mountain;Mountain;Mountain;Kiki-Jiki, Mirror Breaker", 20,
                "Pestermite;Deceiver Exarch;Emrakul, the Aeons Torn;Island;Island;Island;Island"));
        final Player me = g.getPlayers().get(0);
        final Card kiki = ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Kiki-Jiki, Mirror Breaker");
        // off: no creature of ours is in play for Forge AI to choose: stored as unplayable, as in the census receipts
        final ACard off = reader(false).card(g, me, kiki);
        Assert.assertEquals(off.onBattlefield.size(), 1, off.onBattlefield.toString());
        Assert.assertTrue(off.onBattlefield.get(0).unplayable, off.onBattlefield.toString());
        Assert.assertNull(off.onBattlefield.get(0).obj);
        Assert.assertFalse(off.hasObj);
        // on: played with a stand-in from our library; the requirement is what the engine's own check accepts
        final ACard on = reader(true).card(g, me, kiki);
        Assert.assertEquals(on.onBattlefield.size(), 1, on.onBattlefield.toString());
        final Abil ab = on.onBattlefield.get(0);
        Assert.assertFalse(ab.unplayable, ab.toString());
        Assert.assertTrue(ab.tapSelf && ab.cardTargets, ab.toString());
        final ObjNeed need = ab.obj;
        Assert.assertNotNull(need, ab.toString());
        Assert.assertTrue(on.hasObj);
        Assert.assertEquals(need.role, ObjNeed.TARGET, ab.toString());
        Assert.assertEquals(need.zone, "Battlefield", ab.toString());
        Assert.assertTrue(need.valid.contains("Pestermite") && need.valid.contains("Deceiver Exarch"), need.valid.toString());
        Assert.assertFalse(need.valid.contains("Emrakul, the Aeons Torn"), "a legendary creature is refused: " + need.valid);
        Assert.assertFalse(need.valid.contains("Island") || need.valid.contains("Mountain"), need.valid.toString());
        Assert.assertEquals(need.seen.size(), 1, "one stand-in is enough when no object is in the position: " + need);
        final ObjSeen seen = need.seen.values().iterator().next();
        Assert.assertEquals(seen.to, 0, "the copied creature stays: " + need);
        // what the copy does (a token enters) has no effect kind in the width model: learned, but not a planner move
        Assert.assertTrue(seen.unmapped, need.toString());
        Assert.assertFalse(seen.usable(), need.toString());
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).size(), 7, "the sandbox never touches the live game");
        // the planner makes no move of it (its outcome is not in the model): no line, and no crash
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.probeObjects = true;
        lim.horizon = 1;
        final ForcedWinModel.Reader r = reader(true);
        Assert.assertTrue(ForcedWinPlanner.plan(r.read(g, me, null, true), lim).lines.isEmpty());
    }

    /** A template is learned at first sight: objects that turn up later are tried when a position holds them. */
    @Test
    public void objectsOfALaterPositionAreAddedToTheTemplate() {
        final String lib = "Pestermite;Island;Island;Island;Island";
        final Game g1 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                frame("Kiki-Jiki, Mirror Breaker", "Mountain;Mountain;Mountain;Mountain;Mountain", 20, "Deceiver Exarch;" + lib));
        final Player m1 = g1.getPlayers().get(0);
        final ForcedWinModel.Reader r = reader(true);
        final ACard first = r.card(g1, m1, ForcedWinSolverTest.inZone(m1, ZoneType.Hand, "Kiki-Jiki, Mirror Breaker"));
        final ObjNeed need = first.onBattlefield.get(0).obj;
        Assert.assertNotNull(need, first.onBattlefield.toString());
        Assert.assertEquals(need.seen.size(), 1, need.toString());
        final String standIn = need.seen.keySet().iterator().next();
        final String other = standIn.equals("Pestermite") ? "Deceiver Exarch" : "Pestermite";
        // the same reader, a position (of the same deck) with the other creature in play
        final Game g2 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("",
                "Mountain;Mountain;Mountain;Mountain;Mountain;Kiki-Jiki, Mirror Breaker;" + other, 20, standIn + ";" + lib));
        final Player m2 = g2.getPlayers().get(0);
        final ACard later = r.card(g2, m2, ForcedWinSolverTest.inZone(m2, ZoneType.Battlefield, "Kiki-Jiki, Mirror Breaker"));
        Assert.assertTrue(later.onBattlefield.get(0).obj.seen.containsKey(other), later.onBattlefield.toString());
        Assert.assertEquals(later.onBattlefield.get(0).obj.seen.size(), 2, later.onBattlefield.toString());
        // asked again, nothing more is probed
        final int probes = r.probeStats.probes;
        r.card(g2, m2, ForcedWinSolverTest.inZone(m2, ZoneType.Battlefield, "Kiki-Jiki, Mirror Breaker"));
        Assert.assertEquals(r.probeStats.probes, probes);
    }

    /** Shape 2: an Aura that grants an ability: cast on the creature it enchants. */
    @Test
    public void anAuraIsLearnedWithTheCreatureItEnchants() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Splinter Twin",
                "Mountain;Mountain;Mountain;Mountain", 20, "Deceiver Exarch;Sol Ring;Island;Island;Island;Island"));
        final Player me = g.getPlayers().get(0);
        final Card twin = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Splinter Twin");
        final ACard off = reader(false).card(g, me, twin);
        Assert.assertEquals(off.fromHand.size(), 1, off.fromHand.toString());
        Assert.assertTrue(off.fromHand.get(0).unplayable, off.fromHand.toString());
        final ACard on = reader(true).card(g, me, twin);
        Assert.assertEquals(on.fromHand.size(), 1, on.fromHand.toString());
        final Abil ab = on.fromHand.get(0);
        Assert.assertFalse(ab.unplayable, ab.toString());
        Assert.assertEquals(ab.kind, AbilKind.SPELL);
        Assert.assertEquals(ab.generic, 2, ab.toString());
        Assert.assertEquals(ab.pips[ForcedWinModel.R], 2, ab.toString());
        final ObjNeed need = ab.obj;
        Assert.assertNotNull(need, ab.toString());
        Assert.assertEquals(need.role, ObjNeed.TARGET);
        Assert.assertTrue(need.valid.contains("Deceiver Exarch"), need.valid.toString());
        Assert.assertFalse(need.valid.contains("Sol Ring") || need.valid.contains("Island"), need.valid.toString());
        Assert.assertTrue(need.seen.containsKey("Deceiver Exarch"), need.toString());
        Assert.assertEquals(need.seen.get("Deceiver Exarch").to, 0, need.toString());
        Assert.assertTrue(twin.isInZone(ZoneType.Hand));
    }

    /** Shape 3: a search that sacrifices another permanent as an additional cost. */
    @Test
    public void aSacrificeCostSearchIsLearnedWithThePermanentItSacrifices() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Tinker",
                "Island;Island;Island;Sol Ring", 20, "Blightsteel Colossus;Mox Sapphire;Grizzly Bears;Island;Island;Island"));
        final Player me = g.getPlayers().get(0);
        final Card tinker = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Tinker");
        // off: the cost is outside the model, the card has no ability at all ("learned Tinker role=other hand=[]")
        final ACard off = reader(false).card(g, me, tinker);
        Assert.assertTrue(off.fromHand.isEmpty(), off.fromHand.toString());
        final ACard on = reader(true).card(g, me, tinker);
        Assert.assertEquals(on.fromHand.size(), 1, on.fromHand.toString());
        final Abil ab = on.fromHand.get(0);
        Assert.assertFalse(ab.unplayable, ab.toString());
        Assert.assertEquals(ab.sacN, 1, ab.toString());
        Assert.assertFalse(ab.cardTargets, ab.toString());
        final ObjNeed need = ab.obj;
        Assert.assertNotNull(need, ab.toString());
        Assert.assertEquals(need.role, ObjNeed.SAC);
        Assert.assertTrue(need.valid.contains("Sol Ring") && need.valid.contains("Mox Sapphire")
                && need.valid.contains("Blightsteel Colossus"), need.valid.toString());
        Assert.assertFalse(need.valid.contains("Island") || need.valid.contains("Grizzly Bears"), need.valid.toString());
        // the artifact in play in the position is the object tried first; it ends in our graveyard
        final ObjSeen seen = need.seen.get("Sol Ring");
        Assert.assertNotNull(seen, need.toString());
        Assert.assertEquals(seen.to, Abil.SELF_GRAVEYARD, need.toString());
        // the searched artifact enters the battlefield: no effect kind for that (the census's gap 2), so not a planner move
        Assert.assertTrue(seen.unmapped, need.toString());
        Assert.assertTrue(ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Sol Ring") != null, "live game untouched");
    }

    /** A sacrifice outlet whose effect the width model has: two creatures, two damage. */
    static final String OUTLET = frame("", "Mountain;Goblin Bombardment;Memnite;Ornithopter", 2,
            "Island;Island;Island;Island;Island;Island");

    @Test
    public void aSacrificeOutletIsLearnedPerObjectPlannedAndVerified() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", OUTLET);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        Assert.assertTrue(off.propose(g0).isEmpty(), "key off: no candidate, as before: " + off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", OUTLET);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver on = new ForcedWinSolver(me, cfg(true));
        final ACard outlet = on.readerForTest().card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Goblin Bombardment"));
        Abil atOpp = null;
        for (Abil a : outlet.onBattlefield) {
            atOpp = "opp".equals(a.targetVariant) ? a : atOpp;
        }
        Assert.assertNotNull(atOpp, outlet.onBattlefield.toString());
        Assert.assertNotNull(atOpp.obj, atOpp.toString());
        for (String name : new String[] {"Memnite", "Ornithopter"}) {
            final ObjSeen seen = atOpp.obj.seen.get(name);
            Assert.assertNotNull(seen, atOpp.toString());
            Assert.assertTrue(seen.usable(), atOpp.toString());
            Assert.assertEquals(seen.to, Abil.SELF_GRAVEYARD, atOpp.toString());
            Assert.assertTrue(seen.abil.has(EffKind.DRAIN), atOpp.toString());
        }
        Assert.assertFalse(atOpp.obj.valid.contains("Mountain"), atOpp.toString());
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        int sacs = 0;
        final java.util.Set<Integer> gone = new java.util.HashSet<>();
        for (Step st : ((Line) p.line).steps) {
            if (st.hostName.equals("Goblin Bombardment")) {
                Assert.assertEquals(st.kind, AbilKind.ACTIVATE, p.text);
                Assert.assertEquals(st.sacIds.size(), 1, p.text);
                gone.addAll(st.sacIds);
                sacs++;
            }
        }
        Assert.assertEquals(sacs, 2, p.text);
        Assert.assertEquals(gone.size(), 2, "two different creatures: " + p.text);
        Assert.assertEquals(g.getPlayers().get(1).getLife(), 2, "nothing was played in the live game");
    }

    /**
     * A targeted spell whose outcome the width model has (the creature returns to our hand): bounce the free creature,
     * cast it again, and the storm spell has the two spells before it that it needs.
     */
    static final String BOUNCE = frame("Unsummon;Tendrils of Agony",
            "Underground Sea;Underground Sea;Underground Sea;Underground Sea;Underground Sea;Memnite", 6,
            "Island;Island;Island;Island;Island;Island");

    @Test
    public void aTargetedSpellIsPlannedOnThePermanentItWasObservedWith() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", BOUNCE);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        Assert.assertTrue(off.propose(g0).isEmpty(), "key off: no candidate, as before: " + off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", BOUNCE);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver on = new ForcedWinSolver(me, cfg(true));
        final ACard bounce = on.readerForTest().card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Unsummon"));
        final ObjNeed need = bounce.fromHand.get(0).obj;
        Assert.assertNotNull(need, bounce.fromHand.toString());
        Assert.assertEquals(need.seen.get("Memnite").to, Abil.SELF_HAND, need.toString());
        Assert.assertTrue(need.seen.get("Memnite").usable(), need.toString());
        final int memnite = ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Memnite").getId();
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        boolean targeted = false, recast = false;
        for (Step st : ((Line) p.line).steps) {
            if (st.hostName.equals("Unsummon")) {
                Assert.assertEquals(st.objTarget, memnite, p.text);
                targeted = true;
            }
            recast |= targeted && st.hostName.equals("Memnite") && st.kind == AbilKind.SPELL;
        }
        Assert.assertTrue(targeted && recast, p.text);
    }

    /** A target in our graveyard: the card comes back to our hand, is cast, and is the second spell before the storm spell. */
    static final String REGROW = frame("Regrowth;Tendrils of Agony", "Bayou;Bayou;Bayou;Bayou;Bayou;Bayou", 6,
            "Island;Island;Island;Island;Island;Island").replace("humangraveyard=", "humangraveyard=Lotus Petal;Forest");

    @Test
    public void aGraveyardTargetIsLearnedAndPlanned() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", REGROW);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        Assert.assertTrue(off.propose(g0).isEmpty(), "key off: no candidate, as before: " + off.summary());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", REGROW);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver on = new ForcedWinSolver(me, cfg(true));
        final ACard regrowth = on.readerForTest().card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Regrowth"));
        final ObjNeed need = regrowth.fromHand.get(0).obj;
        Assert.assertNotNull(need, regrowth.fromHand.toString());
        Assert.assertEquals(need.zone, "Graveyard", need.toString());
        Assert.assertEquals(need.seen.get("Lotus Petal").to, Abil.SELF_HAND, need.toString());
        final int petal = ForcedWinSolverTest.inZone(me, ZoneType.Graveyard, "Lotus Petal").getId();
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        boolean targeted = false;
        for (Step st : ((Line) p.line).steps) {
            targeted |= st.hostName.equals("Regrowth") && st.objTarget == petal;
        }
        Assert.assertTrue(targeted, p.text);
    }

    /** ... and a target in our graveyard. */
    @Test
    public void theLiveSeatTargetsTheLinesGraveyardCard() {
        liveSeatWins(REGROW);
    }

    private static void liveSeatWins(final String frame) {
        System.setProperty("forge.ai.forcedWin.spec", "probeObjects=true,engine=false,defense=false,budgetMs=60000,planMs=20000");
        try {
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
            ForcedWinSolverTest.play(g, 7);
            final ForcedWinSolver s = ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
            Assert.assertTrue(s.config().probeObjects);
            Assert.assertTrue(g.isGameOver() && g.getPlayers().get(0).hasWon(), "no win on turn 7: " + s.summary());
            Assert.assertTrue(s.taken >= 1, s.summary());
        } finally {
            System.clearProperty("forge.ai.forcedWin.spec");
        }
    }

    /** The live seat follows the line: each activation's cost is answered with the line's creature. */
    @Test
    public void theLiveSeatSacrificesTheLinesPermanents() {
        liveSeatWins(OUTLET);
    }

    /** ... and a targeted step gets the line's target. */
    @Test
    public void theLiveSeatTargetsTheLinesPermanent() {
        liveSeatWins(BOUNCE);
    }

    /** The key off: a reader's templates and a plan are exactly as before (no need, no object move). */
    @Test
    public void offNothingChanges() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", OUTLET);
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader r = reader(false);
        final ForcedWinModel.Position pos = r.read(g, me, null, true);
        for (ACard a : pos.battlefield) {
            Assert.assertFalse(a.hasObj, a.toString());
            for (Abil ab : a.onBattlefield) {
                Assert.assertNull(ab.obj, ab.toString());
                Assert.assertEquals(ab.sacN, 0, ab.toString());
            }
        }
        Assert.assertTrue(ForcedWinPlanner.plan(pos, new ForcedWinPlanner.Limits()).lines.isEmpty());
    }
}
