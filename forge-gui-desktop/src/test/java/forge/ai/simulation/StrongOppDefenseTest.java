package forge.ai.simulation;

import java.util.Arrays;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane strong-opp-1020: the forced-win solver's DEFENSE as the live seat's hook ({@link ForcedWinSolver#defend},
 * {@link ForcedWinSolver#answerDiscardP}), on the solver's own read and on belief-style world copies
 * ({@link ForcedWinSolver#redraw}; a no-op re-draw here, so the copies hold the frame's cards).
 */
public class StrongOppDefenseTest extends AITest {

    /** The defender's config as plans.defend builds it (act=false, defense=true, no engine). */
    static ForcedWinSolver.Config defendCfg(String... kv) {
        final java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("budgetMs", "3000");
        m.put("planMs", "1500");
        m.put("defenseWorlds", "2");
        m.put("engine", "false");
        m.put("act", "false");
        m.put("defense", "true");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return ForcedWinSolver.Config.fromProps(m::get);
    }

    /** Seat 0 (us) at {@code life} holding {@code hand} on {@code bf}; seat 1 casts {@code spell} at us (on the stack). */
    static Game boltAtUs(int life, String hand, String bf, String spell) {
        final String f = ForcedWinSolverTest.frame(hand, bf, life, "", "Mountain;Mountain", 20, "ai")
                + "aiputonstack=" + spell + "->human\n";
        return ForcedWinSolverTest.game("Default", "Default", f);
    }

    @Test
    public void itCountersASpellThatKillsUs() {
        for (boolean worlds : new boolean[] {false, true}) {
            final Game g = boltAtUs(3, "Counterspell", "Island;Island", "Lightning Bolt");
            final Player me = g.getPlayers().get(0);
            final ForcedWinSolver s = new ForcedWinSolver(me, defendCfg());
            if (worlds) {
                s.redraw = (live, liveMe, copy, cm, rng) -> { };
            }
            final ForcedWinSolver.Decision d = s.defend(g);
            Assert.assertTrue(d.owned, "worlds=" + worlds + ": the defense owns the decision");
            Assert.assertNotNull(d.action, "worlds=" + worlds);
            Assert.assertEquals(d.action.getHostCard().getName(), "Counterspell", "worlds=" + worlds);
            Assert.assertEquals(s.counters, 1);
            // the same spell is never defended twice
            Assert.assertFalse(s.defend(g).owned, "worlds=" + worlds + ": a stack item is defended once");
        }
    }

    @Test
    public void aHarmlessSpellLeavesTheDecisionToTheSeat() {
        final Game g = boltAtUs(20, "Counterspell", "Island;Island", "Lightning Bolt");
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), defendCfg());
        Assert.assertFalse(s.defend(g).owned, "at 20 life a Bolt is no forced win: no opinion");
        Assert.assertEquals(s.counters, 0);
    }

    @Test
    public void noCounterInHandMeansNoOpinionAndAnEmptyStackToo() {
        final Game g = boltAtUs(3, "Island", "Island;Island", "Lightning Bolt");
        Assert.assertFalse(new ForcedWinSolver(g.getPlayers().get(0), defendCfg()).defend(g).owned);
        final Game quiet = ForcedWinSolverTest.game("Default", "Default",
                ForcedWinSolverTest.frame("Counterspell", "Island;Island", 3, "Lightning Bolt", "Mountain", 20, "ai"));
        Assert.assertFalse(new ForcedWinSolver(quiet.getPlayers().get(0), defendCfg()).defend(quiet).owned);
    }

    @Test
    public void theDiscardTakesTheCardTheirWinNeeds() {
        for (boolean worlds : new boolean[] {false, true}) {
            final Game g = ForcedWinSolverTest.game("Default", "Default",
                    ForcedWinSolverTest.frame("Thoughtseize", "Swamp", 3, "Grizzly Bears;Lightning Bolt", "Mountain;Forest;Forest", 20, "human"));
            final Player me = g.getPlayers().get(0);
            final Player opp = g.getPlayers().get(1);
            final ForcedWinSolver s = new ForcedWinSolver(me, defendCfg());
            if (worlds) {
                s.redraw = (live, liveMe, copy, cm, rng) -> { };
            }
            final Card ts = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Thoughtseize");
            final SpellAbility sa = ts.getFirstSpellAbility();
            sa.setActivatingPlayer(me);
            final CardCollection valid = new CardCollection(opp.getCardsIn(ZoneType.Hand));
            final CardCollection took = s.answerDiscardP(opp, sa, valid, 1, 1, new CardCollection(opp.getCardsIn(ZoneType.Hand)));
            Assert.assertNotNull(took, "worlds=" + worlds + ": their Bolt is lethal, the discard has an opinion");
            Assert.assertEquals(took.get(0).getName(), "Lightning Bolt", "worlds=" + worlds);
        }
    }

    /** Review S2-1: an effect that did not reveal the whole hand gets no opinion (never the real unrevealed cards). */
    @Test
    public void aPartialRevealGetsNoOpinion() {
        final Game g = ForcedWinSolverTest.game("Default", "Default",
                ForcedWinSolverTest.frame("Thoughtseize", "Swamp", 3, "Grizzly Bears;Lightning Bolt", "Mountain;Forest;Forest", 20, "human"));
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        final ForcedWinSolver s = new ForcedWinSolver(me, defendCfg());
        final SpellAbility sa = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Thoughtseize").getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        final CardCollection valid = new CardCollection(opp.getCardsIn(ZoneType.Hand));
        final CardCollection bears = new CardCollection(ForcedWinSolverTest.inZone(opp, ZoneType.Hand, "Grizzly Bears"));
        Assert.assertNull(s.answerDiscardP(opp, sa, valid, 1, 1, bears), "only the Bears were shown: no opinion");
        Assert.assertNull(s.answerDiscardP(opp, sa, valid, 1, 1, null), "nothing said shown: no opinion");
    }

    /** Review S2-2: an Error inside the defense (a deep recursion) never reaches the caller; no opinion. */
    @Test
    public void anErrorInsideTheDefenseIsNoOpinion() {
        final Game g = boltAtUs(3, "Counterspell", "Island;Island", "Lightning Bolt");
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), defendCfg());
        s.redraw = (live, liveMe, copy, cm, rng) -> {
            throw new StackOverflowError("test");
        };
        Assert.assertFalse(s.defend(g).owned);
        final Game d = ForcedWinSolverTest.game("Default", "Default",
                ForcedWinSolverTest.frame("Thoughtseize", "Swamp", 3, "Grizzly Bears;Lightning Bolt", "Mountain;Forest;Forest", 20, "human"));
        final Player me = d.getPlayers().get(0);
        final Player opp = d.getPlayers().get(1);
        final ForcedWinSolver t = new ForcedWinSolver(me, defendCfg());
        t.redraw = (live, liveMe, copy, cm, rng) -> {
            throw new StackOverflowError("test");
        };
        final SpellAbility sa = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Thoughtseize").getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        final CardCollection hand = new CardCollection(opp.getCardsIn(ZoneType.Hand));
        Assert.assertNull(t.answerDiscardP(opp, sa, hand, 1, 1, hand));
    }

    /** Review S2-3: a hard counter before a soft one they can pay; a soft one when they cannot; never a bounce counter. */
    @Test
    public void itPicksTheCounterThatWorks() {
        // they have two untapped Mountains (they can pay Daze's 1): Counterspell, not Daze
        final Game g = boltAtUs(3, "Daze;Counterspell", "Island;Island", "Lightning Bolt");
        final ForcedWinSolver.Decision d = new ForcedWinSolver(g.getPlayers().get(0), defendCfg()).defend(g);
        Assert.assertTrue(d.owned);
        Assert.assertEquals(d.action.getHostCard().getName(), "Counterspell");
        // Daze alone against a player who can pay: no counter that works, no opinion
        final Game g2 = boltAtUs(3, "Daze", "Island;Island", "Lightning Bolt");
        Assert.assertFalse(new ForcedWinSolver(g2.getPlayers().get(0), defendCfg()).defend(g2).owned);
        // Daze alone, their Mountains tapped: Daze works
        final String f = ForcedWinSolverTest.frame("Daze", "Island;Island", 3, "", "Mountain|Tapped;Mountain|Tapped", 20, "ai")
                + "aiputonstack=Lightning Bolt->human\n";
        final Game g3 = ForcedWinSolverTest.game("Default", "Default", f);
        final ForcedWinSolver.Decision d3 = new ForcedWinSolver(g3.getPlayers().get(0), defendCfg()).defend(g3);
        Assert.assertTrue(d3.owned);
        Assert.assertEquals(d3.action.getHostCard().getName(), "Daze");
        // Remand only (the spell comes back): never
        final Game g4 = boltAtUs(3, "Remand", "Island;Island", "Lightning Bolt");
        Assert.assertFalse(new ForcedWinSolver(g4.getPlayers().get(0), defendCfg()).defend(g4).owned);
    }

    /** Review S2-4: a "hold" verdict declines the counter only (no opinion) unless hold is on (then it passes). */
    @Test
    public void aHoldOnlyDeclinesUnlessHoldIsOn() {
        final String lib = "Lightning Bolt;Lightning Bolt;Lightning Bolt;Lightning Bolt;Lightning Bolt;Lightning Bolt;"
                + "Lightning Bolt;Lightning Bolt;Lightning Bolt;Lightning Bolt";
        final String f = "[state]\nturn=7\nactiveplayer=ai\nactivephase=MAIN1\nhumanlife=3\nailife=20\nhumanlandsplayed=0\n"
                + "ailandsplayed=0\naihand=Lightning Bolt\nailibrary=" + lib + "\naigraveyard=\naiexile=\n"
                + "aibattlefield=Mountain;Mountain;Forest;Forest\nhumanhand=Counterspell\nhumanbattlefield=Island;Island\n"
                + "humangraveyard=\nhumanexile=\nhumanlibrary=" + ForcedWinSolverTest.OURLIB + "\naiputonstack=Grizzly Bears\n";
        final Game g = ForcedWinSolverTest.game("Default", "Default", f);
        final ForcedWinSolver off = new ForcedWinSolver(g.getPlayers().get(0), defendCfg());
        Assert.assertFalse(off.defend(g).owned, "hold off: the seat keeps every other response");
        Assert.assertEquals(off.holds, 1, "the verdict was hold");
        final Game g2 = ForcedWinSolverTest.game("Default", "Default", f);
        final ForcedWinSolver.Decision on = new ForcedWinSolver(g2.getPlayers().get(0), defendCfg("hold", "true")).defend(g2);
        Assert.assertTrue(on.owned);
        Assert.assertNull(on.action, "hold on: pass to keep the counter");
    }

    @Test
    public void aCutDefenseHasNoOpinion() {
        final Game g = boltAtUs(3, "Counterspell", "Island;Island", "Lightning Bolt");
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), defendCfg("defendMs", "1"));
        s.redraw = (live, liveMe, copy, cm, rng) -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        final ForcedWinSolver.Decision d = s.defend(g);
        Assert.assertFalse(d.owned, "past the wall the decision goes back to the seat");
        Assert.assertEquals(s.defendCuts, 1);
    }

    @Test
    public void theSpecKeysParseAndDefendIsOffByDefault() {
        final forge.bench.rl.PlanSearch.Spec off = forge.bench.rl.PlanSearch.Spec.parse(new JsonObject());
        Assert.assertFalse(off.defend);
        final JsonObject o = JsonParser.parseString("{\"defend\": true, \"defendCfg\": {\"defendMs\": \"6000\", "
                + "\"tieBreakTurns\": \"1\", \"sharedReader\": \"true\"}}").getAsJsonObject();
        final forge.bench.rl.PlanSearch.Spec on = forge.bench.rl.PlanSearch.Spec.parse(o);
        Assert.assertTrue(on.defend);
        final ForcedWinSolver.Config c = SolverDriverAccess.config(on.defendCfg);
        Assert.assertEquals(c.defendMs, 6000L);
        Assert.assertEquals(c.tieBreakTurns, 1);
        Assert.assertTrue(c.sharedReader);
        Assert.assertEquals(Arrays.asList(c.threatExtraTurns), Arrays.asList(0));
    }

    /** The solver config from spec strings, as {@code plans.defendCfg} is read. */
    static final class SolverDriverAccess {
        static ForcedWinSolver.Config config(JsonObject o) {
            return forge.bench.rl.SolverDriver.config(o);
        }
    }
}
