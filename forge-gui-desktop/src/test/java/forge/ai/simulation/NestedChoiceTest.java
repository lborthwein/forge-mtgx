package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.RlCandidates;
import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * integrate-1110 (search spec key {@code nestedChoice}): the searching seat's card picks inside its play-outs. The tests
 * make the calls a root decision and its play-outs make, in their order, on real copies, without the policy / value
 * service: the root plan ({@link RlSearch#planNested}), a play-out copy's context ({@link RlSearch#onCopy}), the play-out
 * seat's answer to the tutor's menu ({@link RlSearch#nestedAnswer}), and the leaf ({@link RlSearch#leafReach},
 * {@link RlSearch#combineReach}). The position is solver-distill-cc's own (GoldfishTest.tutorHandSetup; set D3, pair 2, our
 * second turn): two lands, Mystical Tutor and Wishclaw Talisman in hand, Doomsday in the library. The leaf values 0.09
 * (pass) and 0.06 (the tutor cast) are that lane's reading of the real game (its notes, 05:51Z), used as stand-ins for
 * the value service.
 */
public class NestedChoiceTest extends AITest {

    static final String HAND = "Chart a Course;Mystical Tutor;Troll of Khazad-dûm;Wishclaw Talisman;Counterspell";
    static final String BF = "Raffine's Tower;Urborg, Tomb of Yawgmoth";
    static final String REST = GoldfishTest.DD1010.replace("Chart a Course;", "").replace("Mystical Tutor;", "")
            .replace("Troll of Khazad-dûm;", "").replace("Wishclaw Talisman;", "").replace("Counterspell;", "") + ";Verdant Catacombs";
    /** The same hand over a library with no kill in it. */
    static final String NOKILL = "Grizzly Bears;Grizzly Bears;Balduvian Bears;Lightning Bolt;Shock;Swamp;Swamp;Swamp;Swamp;Island;"
            + "Island;Island";

    /** The pilot arm's reach and solver blocks (kit spec ugold4), the leaf screen's clock cap opened; {@code nested}: the key. */
    static RlSearch search(final String nested) {
        final String solver = "\"budgetMs\":\"6000\",\"copyBackFace\":\"true\",\"cycles\":\"true\",\"engineAfterPlanFail\":\"true\","
                + "\"engineLongLoops\":\"true\",\"engineLoopBisect\":\"true\",\"engineLoopHoist\":\"true\",\"engineLoopReach\":\"true\","
                + "\"engineMoves\":\"true\",\"engineMs\":\"4000\",\"engineNodesPerSearch\":\"true\",\"engineOpen\":\"true\","
                + "\"engineRerun\":\"true\",\"engineTriggerTargets\":\"true\",\"engineVerify\":\"true\",\"evalReserveMs\":\"1500\","
                + "\"followPay\":\"true\",\"hideOwnLibrary\":\"true\",\"historyLki\":\"true\",\"lineUncapped\":\"true\","
                + "\"loopMarginal\":\"true\",\"offBattlefieldLimits\":\"true\",\"paySearch\":\"true\",\"planDiscardWall\":\"true\","
                + "\"planExpandCap\":\"true\",\"readerPerGame\":\"true\",\"sourceColorsLive\":\"true\",\"sourceOutput\":\"true\","
                + "\"stackAbilityCopies\":\"true\",\"stackHistory\":\"true\",\"stalePlayerStatics\":\"true\",\"staleStatics\":\"true\","
                + "\"turnLimits\":\"true\",\"unknownWorlds\":\"true\",\"verifyReplan\":\"true\"";
        final String spec = "{\"server\":\"127.0.0.1:1\",\"leaf\":\"value\",\"playout\":\"policy\",\"horizon\":2,"
                + "\"reach\":{\"alpha\":0.85,\"depart\":0.04,\"gamma\":0.9,\"gate\":false,\"maxNames\":40,\"ms\":60000,"
                + "\"nameNodes\":1000,\"nameOnePass\":true,\"nodes\":2000,\"oppBeta\":0.5,\"oppDirect\":true,\"sig\":true,"
                + "\"skipCapped\":true},\"plans\":{\"solverCfg\":{" + solver + "}}" + (nested == null ? "" : ",\"nestedChoice\":" + nested)
                + "}";
        return new RlSearch(RlSearch.Config.parse(JsonParser.parseString(spec).getAsJsonObject()), 1L, 77L, null, null, "jar", "t", 2);
    }

    static Game root(final String lib) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(HAND, BF, 20, "", "Island", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib));
    }

    static Card find(final Player p, final ZoneType z, final String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no " + name + " in " + z);
    }

    /** One play-out's part: a copy of the root in which the seat casts the tutor; the menu is answered as the play-out seat does. */
    static final class Playout {
        Game g;
        Player me;
        Object ctx;
        String policyPick, picked;
        short[] nested;
        long answerNanos;
        double[] leaf;
    }

    /**
     * A play-out copy of {@code live} for {@code search}: the tutor is cast and resolved there. Its menu is first answered
     * by "the policy" (the first card that is not Doomsday), then {@link RlSearch#nestedAnswer} may replace that answer,
     * exactly as RlSeat does it for a play-out seat. Then the leaf is read.
     */
    static Playout playout(final RlSearch search, final Game live) {
        final Playout po = new Playout();
        final GameCopier copier = new GameCopier(live, true);
        po.g = copier.makeCopy();
        po.me = (Player) copier.find(live.getPlayers().get(0));
        po.ctx = search.onCopy(po.g, po.me);
        final Game g = po.g;
        final Player me = po.me;
        me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
                    final SpellAbility sa, final CardCollection fetchList, final forge.game.player.DelayedReveal delayedReveal,
                    final String selectPrompt, final boolean isOptional, final Player decider) {
                final JsonObject body = new JsonObject();
                body.addProperty("min", isOptional ? 0 : 1);
                body.addProperty("max", 1);
                final RlCandidates.Menu m = RlCandidates.build(g, me, "chooseSingleCardForZoneChange", "zoneChange", body, fetchList);
                short own = -1;
                for (int i = 0; i < fetchList.size() && own < 0; i++) {
                    if (!fetchList.get(i).getName().equals("Doomsday")) {
                        own = (short) i;
                    }
                }
                po.policyPick = fetchList.get(own).getName();
                final long t0 = System.nanoTime();
                po.nested = search.nestedAnswer(po.ctx, g, me, m, new short[] {own});
                po.answerNanos = System.nanoTime() - t0;
                final Card c = fetchList.get(po.nested == null ? own : po.nested[0]);
                po.picked = c.getName();
                return c;
            }
        });
        final SpellAbility sa = find(me, ZoneType.Hand, "Mystical Tutor").getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(sa), "cast the tutor");
        MyRandom.setRandom(new Random(42));
        g.getStack().resolveStack();
        Assert.assertNotNull(po.picked, "the tutor's menu was asked");
        po.leaf = search.leafReach(po.ctx, g, me);
        return po;
    }

    /** The leaf of the candidate "pass": the root itself, no tutor cast. */
    static double[] passLeaf(final RlSearch search, final Game live) {
        final GameCopier copier = new GameCopier(live, true);
        final Game g = copier.makeCopy();
        final Player me = (Player) copier.find(live.getPlayers().get(0));
        return search.leafReach(search.onCopy(g, me), g, me);
    }

    static final double V_PASS = 0.09, V_TUTOR = 0.06;

    @Test
    public void castTheTutorIsWorthThePlansPickWithTheKeyAndThePolicysPickWithout() {
        // ---- key off: the pilot arm today
        final RlSearch off = search(null);
        final Game liveOff = root(REST);
        Assert.assertNull(off.planNested(liveOff, liveOff.getPlayers().get(0), new ArrayList<>()), "no key, no plan");
        final Playout a = playout(off, liveOff);
        Assert.assertNull(a.nested);
        Assert.assertNotEquals(a.picked, "Doomsday", "the policy's pick stands");
        final double tutorOff = off.combineReach(V_TUTOR, a.leaf), passOff = off.combineReach(V_PASS, passLeaf(off, liveOff));
        // ---- key on
        final RlSearch on = search("{}");
        final Game live = root(REST);
        final Player me = live.getPlayers().get(0);
        final long t0 = System.nanoTime();
        final RlSearch.NestedPlan np = on.planNested(live, me, new ArrayList<>());
        final double planMs = (System.nanoTime() - t0) / 1e6;
        Assert.assertNotNull(np, "the root plan: the tutor with its pick");
        on.setNestedPlan(np); // what decide0 does for the play-outs of this root decision
        Assert.assertEquals(np.picks.size(), 1, np.text);
        final int tutorId = find(me, ZoneType.Hand, "Mystical Tutor").getId();
        Assert.assertEquals(np.picks.get(tutorId), List.of(find(me, ZoneType.Library, "Doomsday").getId()), np.text);
        Assert.assertTrue(np.gf > 0 && np.winTurn >= 1, "a kill more than a turn away: " + np.text);
        final Playout b = playout(on, live);
        Assert.assertNotNull(b.nested, "the play-out's menu is answered from the plan");
        Assert.assertEquals(b.picked, "Doomsday");
        Assert.assertEquals(RlSearch.nestedPotOf(b.ctx), np.pot, 1e-12, "the play-out carries the line's value");
        Assert.assertEquals(b.leaf[0], np.pot, 1e-12, "and its leaf's potential is that value: the one-turn reach alone is 0");
        final double tutorOn = on.combineReach(V_TUTOR, b.leaf), passOn = on.combineReach(V_PASS, passLeaf(on, live));
        System.out.println("NESTED off: pass " + passOff + " cast-tutor " + tutorOff + " (pick " + a.picked + ", leaf potential "
                + a.leaf[0] + ") | on: pass " + passOn + " cast-tutor " + tutorOn + " (pick " + b.picked + ", leaf potential " + b.leaf[0]
                + ") | plan gf " + np.gf + " turn+" + np.winTurn + " nodes " + np.nodes + " wall ms " + Math.round(planMs)
                + " work-clock ms at 34963 ns a node " + Math.round(np.nodes * 34963 / 1e6) + " | " + np.text);
        Assert.assertEquals(a.leaf[0], 0.0, 1e-12, "key off: the leaf sees no line after the policy's pick");
        Assert.assertEquals(tutorOff, V_TUTOR, 1e-12, "key off: cast the tutor is worth the leaf value of the policy's pick");
        Assert.assertTrue(tutorOff < passOff, "key off reproduces the tutor below pass: " + tutorOff + " < " + passOff);
        Assert.assertEquals(passOn, passOff, 1e-12, "pass is not touched by the key");
        Assert.assertTrue(tutorOn > passOn, "key on: cast the tutor above pass: " + tutorOn + " > " + passOn);
        Assert.assertTrue(tutorOn > tutorOff, "strictly higher than with the key off");
        Assert.assertEquals(tutorOn, 0.85 * np.pot + (1 - 0.85 * np.pot) * V_TUTOR, 1e-12, "the reach blend with the floor as its potential");
    }

    @Test
    public void thePlanIsComputedOncePerRootDecisionAndEveryWorldReadsIt() {
        final RlSearch on = search("{}");
        final Game live = root(REST);
        final RlSearch.NestedPlan np = on.planNested(live, live.getPlayers().get(0), new ArrayList<>());
        Assert.assertNotNull(np);
        on.setNestedPlan(np);
        final long nodes = on.nestedNodes.get(), plans = on.nestedPlans.get();
        Assert.assertEquals(plans, 1);
        Assert.assertTrue(nodes > 0);
        final Playout w1 = playout(on, live), w2 = playout(on, live);
        Assert.assertEquals(w1.picked, "Doomsday");
        Assert.assertEquals(w2.picked, "Doomsday");
        Assert.assertEquals(on.nestedPlans.get(), 1, "no second plan");
        Assert.assertEquals(on.nestedNodes.get(), nodes, "the worlds' asks cost no planner node");
        Assert.assertEquals(on.nestedAsks.get(), 2);
        Assert.assertEquals(on.nestedAnswered.get(), 2);
        Assert.assertEquals(on.nestedChanged.get(), 2, "both replaced the policy's pick");
        Assert.assertTrue(on.nestedFloors.get() >= 2, "both leaves took the floor");
        System.out.println("NESTED ask world 1 " + w1.answerNanos / 1000 + " us, world 2 " + w2.answerNanos / 1000
                + " us; plan nodes " + nodes);
        Assert.assertTrue(w2.answerNanos < 5_000_000L, "a lookup: " + w2.answerNanos);
    }

    @Test
    public void withNoLineThePolicysAnswerStandsAndThereIsNoFloor() {
        final RlSearch on = search("{}");
        final Game live = root(NOKILL);
        final RlSearch.NestedPlan np = on.planNested(live, live.getPlayers().get(0), new ArrayList<>());
        Assert.assertNull(np, "no kill in the library: no plan");
        on.setNestedPlan(np);
        final Playout a = playout(on, live);
        Assert.assertNull(a.nested);
        Assert.assertEquals(a.picked, a.policyPick);
        Assert.assertEquals(RlSearch.nestedPotOf(a.ctx), 0.0, 1e-12);
        Assert.assertEquals(on.combineReach(V_TUTOR, a.leaf), V_TUTOR, 1e-12, "the leaf is the pilot arm's own");
        System.out.println("NESTED no line: plan nodes " + on.nestedNodes.get() + " (the cost of a root decision that finds nothing)");
    }

    @Test
    public void aSmallAllowanceFindsNoLineAndChangesNothing() {
        // solver-distill-cc's fact: below 12,000 nodes a screen the goldfish does not find this line
        final RlSearch small = search("{\"nodes\":4000,\"maxNodes\":12000}");
        final Game live = root(REST);
        Assert.assertNull(small.planNested(live, live.getPlayers().get(0), new ArrayList<>()));
        final Playout a = playout(small, live);
        Assert.assertEquals(a.picked, a.policyPick, "allowance exhausted: the policy's answer");
        Assert.assertEquals(a.leaf[0], 0.0, 1e-12);
    }

    @Test
    public void theKeyIsOffByDefaultAndNeedsReach() {
        final JsonObject o = JsonParser.parseString("{\"server\":\"h:1\"}").getAsJsonObject();
        final RlSearch.Config off = RlSearch.Config.parse(o);
        Assert.assertNull(off.nestedChoice);
        Assert.assertFalse(off.toJson().has("nestedChoice"), "an old config prints as before");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(
                JsonParser.parseString("{\"server\":\"h:1\",\"nestedChoice\":{}}").getAsJsonObject()));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(
                JsonParser.parseString("{\"server\":\"h:1\",\"reach\":{},\"nestedChoice\":{\"nope\":1}}").getAsJsonObject()));
        final RlSearch.Config on = RlSearch.Config.parse(
                JsonParser.parseString("{\"server\":\"h:1\",\"reach\":{},\"nestedChoice\":{\"nodes\":16000,\"maxNodes\":48000}}").getAsJsonObject());
        Assert.assertEquals(on.toJson().getAsJsonObject("nestedChoice").get("nodes").getAsInt(), 16000);
    }
}
