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

    /** The position with {@code tutor} in place of Mystical Tutor, the opponent's battlefield, and the library. */
    static Game root(final String tutor, final String oppBf, final String lib) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(HAND.replace("Mystical Tutor", tutor), BF, 20,
                "", oppBf, 20, "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib));
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
        /** The asks of the resolution, and those {@link RlSearch#nestedAnswer} answered. */
        int asks, answered;
        long answerNanos;
        double[] leaf;
    }

    /**
     * A play-out copy of {@code live} for {@code search}: the tutor is cast and resolved there. Its menu is first answered
     * by "the policy" (the first card that is not Doomsday), then {@link RlSearch#nestedAnswer} may replace that answer,
     * exactly as RlSeat does it for a play-out seat. Then the leaf is read.
     */
    static Playout playout(final RlSearch search, final Game live) {
        return playout(search, live, "Mystical Tutor");
    }

    static Playout playout(final RlSearch search, final Game live, final String tutor) {
        return playout(search, live, tutor, null);
    }

    /** {@code beforeLeaf}: what else happens in the play-out between the tutor and its leaf (null = nothing). */
    static Playout playout(final RlSearch search, final Game live, final String tutor,
            final java.util.function.Consumer<Playout> beforeLeaf) {
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
                po.asks++;
                if (po.nested != null) {
                    po.answered++;
                }
                final Card c = fetchList.get(po.nested == null ? own : po.nested[0]);
                po.picked = c.getName();
                return c;
            }
        });
        final SpellAbility sa = find(me, ZoneType.Hand, tutor).getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(sa), "cast the tutor");
        MyRandom.setRandom(new Random(42));
        g.getStack().resolveStack();
        Assert.assertNotNull(po.picked, "the tutor's menu was asked");
        if (beforeLeaf != null) {
            beforeLeaf.accept(po);
        }
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
        on.setNestedPlan(np, live); // what decide0 does for the play-outs of this root decision
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

    /**
     * The search casts the tutor because its play-outs valued the cast with the plan's pick. When the tutor then resolves in
     * the LIVE game, its menu is answered with that pick too; another turn's ask, or a card the plan did not cast, is not.
     */
    @Test
    public void theLivePickOfATutorThePlanCastIsThePlans() {
        liveFollow("Mystical Tutor", HAND, REST);
    }

    /**
     * Report only: the same hand with a tutor for ANY card in place of Mystical Tutor. The plan must search the pick among
     * the whole library; how many planner nodes does it need? (No assertion: the numbers go to RESULT.md.)
     */
    @Test
    public void allowanceForAnAnyCardTutor() {
        for (String nested : new String[] {"{}", "{\"nodes\":20000,\"maxNodes\":80000}", "{\"nodes\":40000,\"maxNodes\":160000}"}) {
            final RlSearch on = search(nested);
            final Game live = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(
                    HAND.replace("Mystical Tutor", "Vampiric Tutor"), BF, 20, "", "Island", 20, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + REST));
            final long t0 = System.nanoTime();
            final RlSearch.NestedPlan np = on.planNested(live, live.getPlayers().get(0), new ArrayList<>());
            System.out.println("NESTED any-card tutor " + nested + ": " + (np == null ? "no plan" : "gf " + np.gf + " turn+" + np.winTurn + " | "
                    + np.text.substring(0, Math.min(200, np.text.length()))) + "; nodes " + on.nestedNodes.get() + " wall ms "
                    + (System.nanoTime() - t0) / 1_000_000);
        }
    }

    private static void liveFollow(final String tutor, final String hand, final String lib) {
        final RlSearch on = search("{}");
        final Game live = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(hand, BF, 20, "", "Island", 20,
                "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib));
        final Player me = live.getPlayers().get(0);
        final RlSearch.NestedPlan np = on.planNested(live, me, new ArrayList<>());
        Assert.assertNotNull(np);
        on.setNestedPlan(np, live);
        final String[] picked = {null};
        final int[] hits = {-9, -9};
        final short[][] scorerAlt = {null};
        final double[] scorerDd = {Double.NaN}, scorerMax = {0.0};
        me.dangerouslySetController(new PlayerControllerAi(live, me, me.getController().getLobbyPlayer()) {
            @Override
            public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
                    final SpellAbility sa, final CardCollection fetchList, final forge.game.player.DelayedReveal delayedReveal,
                    final String selectPrompt, final boolean isOptional, final Player decider) {
                final JsonObject body = new JsonObject();
                body.addProperty("min", isOptional ? 0 : 1);
                body.addProperty("max", 1);
                final RlCandidates.Menu m = RlCandidates.build(live, me, "chooseSingleCardForZoneChange", "zoneChange", body, fetchList);
                // the choice scorer alone on this real ask, with the pilot arm's block (gfNodes 1500 a screen): what
                // answers it without the key. The policy's pick is the first card that is not Doomsday; flat leaf values.
                short own = -1;
                for (int i = 0; i < fetchList.size() && own < 0; i++) {
                    if (!fetchList.get(i).getName().equals("Doomsday")) {
                        own = (short) i;
                    }
                }
                final forge.bench.rl.ChoiceScorer sc = ReachDiscardsTest.scorer(false);
                final forge.bench.rl.RlKnowledge know = new forge.bench.rl.RlKnowledge(live);
                know.v2 = true;
                know.attach();
                final long t0 = System.nanoTime();
                final forge.bench.rl.ChoiceScorer.Scored s = sc.enumerate(live, me, m, new short[] {own}, know, 4242L, null);
                final double[] v = new double[sc.payloads(s).size()];
                java.util.Arrays.fill(v, 0.5);
                scorerAlt[0] = s.unsupported == null ? sc.choose(s, v, new short[] {own}) : null;
                for (forge.bench.rl.ChoiceScorer.Option o : s.options) {
                    if (o.label.equals("Doomsday")) {
                        scorerDd[0] = o.screen == null || Double.isNaN(o.screen.reach) ? 0.0 : o.screen.reach;
                    }
                    scorerMax[0] = Math.max(scorerMax[0], o.screen == null || Double.isNaN(o.screen.reach) ? 0.0 : o.screen.reach);
                }
                System.out.println("NESTED live ask of " + tutor + ": the choice scorer alone (gfNodes 1500): options " + s.options.size()
                        + " unsupported " + s.unsupported + " Doomsday's reach part " + scorerDd[0] + " best reach part " + scorerMax[0]
                        + " answer " + (scorerAlt[0] == null ? "the policy's (" + fetchList.get(own).getName() + ")"
                                : fetchList.get(scorerAlt[0][0]).getName()) + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                hits[0] = on.nestedLivePick(live, m);
                hits[1] = on.nestedLivePick(live, m); // the plan has one pick for this card: a second ask is not its
                picked[0] = hits[0] < 0 ? null : fetchList.get(hits[0]).getName();
                return fetchList.get(Math.max(0, hits[0]));
            }
        });
        Assert.assertEquals(np.picks.get(find(me, ZoneType.Hand, tutor).getId()),
                List.of(find(me, ZoneType.Library, "Doomsday").getId()), "the plan casts " + tutor + " for Doomsday: " + np.text);
        final SpellAbility sa = find(me, ZoneType.Hand, tutor).getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(sa), "cast the tutor");
        MyRandom.setRandom(new Random(42));
        live.getStack().resolveStack();
        Assert.assertEquals(picked[0], "Doomsday", "the live pick is the plan's");
        Assert.assertEquals(hits[1], -1, "one pick per step");
        Assert.assertEquals(live.getPlayers().get(0).getCardsIn(ZoneType.Library).get(0).getName(), "Doomsday", "on top of the live library");
        // key off: no plan is kept, the real ask is the choice scorer's as today
        final RlSearch off = search(null);
        final Game live2 = root(REST);
        off.setNestedPlan(off.planNested(live2, live2.getPlayers().get(0), new ArrayList<>()), live2);
        Assert.assertNull(off.nestedPlan());
        Assert.assertEquals(off.nestedLivePick(live2, null), -1);
    }

    @Test
    public void thePlanIsComputedOncePerRootDecisionAndEveryWorldReadsIt() {
        final RlSearch on = search("{}");
        final Game live = root(REST);
        final RlSearch.NestedPlan np = on.planNested(live, live.getPlayers().get(0), new ArrayList<>());
        Assert.assertNotNull(np);
        on.setNestedPlan(np, live);
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
        on.setNestedPlan(np, live);
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

    // ------------------------------------------------------------------ 4c: a library search the plan does not answer

    /** The read's keys (kit spec ugold4n2): the plan at one screen, the next-draw table's allowance. */
    static final long ASK_NODES = 24_000;
    static final String ASK = "{\"maxNodes\":12000,\"askNodes\":" + ASK_NODES + "}";
    /** The R4 harness's work-clock weight of a planner node (kit spec ALL-BEST.jvm), ns: a table reads the position only. */
    static final double NODE_NS = 34_963;

    private static String table(final ForcedWinSolver.NextDraws nd) {
        if (nd == null) {
            return "no table";
        }
        final StringBuilder sb = new StringBuilder("blank " + nd.blank + ", lines:");
        for (String n : nd.order) {
            if (nd.byName.get(n) > 0) {
                sb.append(' ').append(n).append('=').append(Math.round(nd.byName.get(n) * 1000) / 1000.0);
            }
        }
        return sb.append("; names planned ").append(nd.byName.size()).append(" in ").append(nd.groups).append(" screens' groups, nodes ")
                .append(nd.nodes).append(nd.cut ? " (cut)" : "").append(", work clock ").append(Math.round(nd.nodes * NODE_NS / 1e6))
                .append(" ms").toString();
    }

    /**
     * An any-card tutor (Vampiric Tutor in place of Mystical Tutor on the same position): the root plan finds nothing;
     * the root's next-draw table answers the play-out's menu, and the cast is worth the table's pick.
     */
    @Test
    public void anAnyCardTutorsMenuReadsTheRootsNextDrawTable() {
        // ---- key off: the pilot arm today
        final RlSearch off = search(null);
        final Game liveOff = root("Vampiric Tutor", "Island", REST);
        Assert.assertNull(off.openNestedDraws(liveOff, liveOff.getPlayers().get(0)), "no key, no table");
        final Playout a = playout(off, liveOff, "Vampiric Tutor");
        Assert.assertNull(a.nested);
        Assert.assertNotEquals(a.picked, "Doomsday");
        final double tutorOff = off.combineReach(V_TUTOR, a.leaf), passOff = off.combineReach(V_PASS, passLeaf(off, liveOff));
        Assert.assertTrue(tutorOff < passOff, "key off: cast the tutor below pass: " + tutorOff + " < " + passOff);
        // ---- the plan alone (ugold4n): nothing for an any-card tutor
        final RlSearch planOnly = search("{\"maxNodes\":12000}");
        final Game liveP = root("Vampiric Tutor", "Island", REST);
        planOnly.setNestedPlan(planOnly.planNested(liveP, liveP.getPlayers().get(0), new ArrayList<>()), liveP);
        Assert.assertNull(planOnly.nestedPlan(), "the root plan does not find an any-card tutor's line");
        Assert.assertNull(planOnly.openNestedDraws(liveP, liveP.getPlayers().get(0)), "askNodes 0: no table");
        final Playout p0 = playout(planOnly, liveP, "Vampiric Tutor");
        Assert.assertEquals(p0.picked, p0.policyPick);
        // ---- key on with askNodes
        final RlSearch on = search(ASK);
        final Game live = root("Vampiric Tutor", "Island", REST);
        final Player me = live.getPlayers().get(0);
        on.setNestedPlan(on.planNested(live, me, new ArrayList<>()), live);
        Assert.assertNull(on.nestedPlan());
        final long planNodes = on.nestedNodes.get();
        final long t0 = System.nanoTime();
        final ForcedWinSolver.NextDraws nd = on.openNestedDraws(live, me); // what decide0 does for this root decision
        final long tableMs = (System.nanoTime() - t0) / 1_000_000;
        Assert.assertNotNull(nd, "the root's table");
        Assert.assertTrue(nd.byName.get("Doomsday") > 0, table(nd));
        final Playout b = playout(on, live, "Vampiric Tutor");
        Assert.assertEquals(b.picked, "Doomsday", table(nd));
        final double pot = RlSearch.nestedPotOf(b.ctx);
        Assert.assertEquals(pot, nd.byName.get("Doomsday"), 1e-12, "the play-out carries the pick's value (floorW 1)");
        Assert.assertEquals(b.leaf[0], pot * (1 - b.leaf[1]), 1e-12, "the floor at the leaf, less the opponent's direct line");
        final double tutorOn = on.combineReach(V_TUTOR, b.leaf), passOn = on.combineReach(V_PASS, passLeaf(on, live));
        Assert.assertEquals(passOn, passOff, 1e-12, "pass is not touched by the key");
        Assert.assertTrue(tutorOn > passOn, "key on: cast the tutor above pass: " + tutorOn + " > " + passOn);
        // ---- the second world of the same root decision: a read, no planner node
        final long nodes = on.nestedDrawNodes.get();
        final Playout c = playout(on, live, "Vampiric Tutor");
        Assert.assertEquals(c.picked, "Doomsday");
        Assert.assertEquals(on.nestedDrawTables.get(), 1, "planned once");
        Assert.assertEquals(on.nestedDrawNodes.get(), nodes, "0 new planner nodes");
        Assert.assertEquals(RlSearch.nestedPotOf(c.ctx), pot, 1e-12);
        Assert.assertEquals(on.nestedDrawAnswered.get(), 2);
        // ---- the next decision at the same root position reads the same table
        Assert.assertSame(on.openNestedDraws(live, me), nd);
        Assert.assertEquals(on.nestedDrawReuses.get(), 1);
        Assert.assertEquals(on.nestedDrawNodes.get(), nodes);
        System.out.println("NESTED4C any-card (Vampiric Tutor) off: pass " + passOff + " cast-tutor " + tutorOff + " (pick " + a.picked
                + ") | on: pass " + passOn + " cast-tutor " + tutorOn + " (pick " + b.picked + ", floor " + pot + ", leaf potential "
                + b.leaf[0] + ", opp reach " + b.leaf[1] + ") | table: " + table(nd) + ", wall " + tableMs + " ms | root plan nodes "
                + planNodes + " | play-out ask: first " + b.answerNanos / 1000 + " us, second world " + c.answerNanos / 1000 + " us");
    }

    /** Report: the table by allowance and screen size, for the two tutors' root positions. */
    @Test
    public void whatTheNextDrawTableCosts() {
        for (String tutor : new String[] {"Mystical Tutor", "Vampiric Tutor"}) {
            for (String v : new String[] {"\"askNodes\":12000", "\"askNodes\":24000", "\"askNodes\":48000", "\"askNodes\":96000",
                "\"askNodes\":48000,\"askScreen\":3000", "\"askNodes\":96000,\"askScreen\":6000"}) {
                final RlSearch on = search("{\"nodes\":1,\"maxNodes\":1," + v + "}"); // the plan shut: the table alone
                final Game live = root(tutor, "Island", REST);
                final long t0 = System.nanoTime();
                final ForcedWinSolver.NextDraws nd = on.openNestedDraws(live, live.getPlayers().get(0));
                final long wall = (System.nanoTime() - t0) / 1_000_000;
                final Playout b = nd == null ? null : playout(on, live, tutor);
                System.out.println("NESTED4C cost " + tutor + " {" + v + "}: pick " + (b == null ? "-" : b.picked) + " | " + table(nd)
                        + " (nodes " + on.nestedDrawNodes.get() + "), wall " + wall + " ms");
            }
        }
    }

    /** No card stands out (the library's kill card is gone): the policy's answer, no floor; the root pays once. */
    @Test
    public void aMenuWithNoSeparationKeepsThePolicysAnswerAndIsPaidOnce() {
        final RlSearch on = search(ASK);
        final Game live = root("Vampiric Tutor", "Island", REST.replace("Doomsday;", ""));
        final Player me = live.getPlayers().get(0);
        Assert.assertNull(on.openNestedDraws(live, me), "no card of the library makes a line as the next draw");
        final long nodes = on.nestedDrawNodes.get();
        final Playout a = playout(on, live, "Vampiric Tutor");
        Assert.assertNull(a.nested);
        Assert.assertEquals(a.picked, a.policyPick);
        Assert.assertEquals(RlSearch.nestedPotOf(a.ctx), 0.0, 1e-12, "no floor");
        Assert.assertEquals(on.nestedDrawAnswered.get(), 0);
        Assert.assertNull(on.openNestedDraws(live, me), "the next decision at this position");
        Assert.assertEquals(on.nestedDrawTables.get(), 1, "the outcome is kept: planned once");
        Assert.assertEquals(on.nestedDrawReuses.get(), 1);
        Assert.assertEquals(on.nestedDrawNodes.get(), nodes);
        // and a deck with no card that ends the game pays nothing at all
        final RlSearch fair = search(ASK);
        final Game liveF = root("Vampiric Tutor", "Island", "Grizzly Bears;Grizzly Bears;Balduvian Bears;Swamp;Swamp;Swamp;Swamp;Island;Island;Island");
        Assert.assertNull(fair.openNestedDraws(liveF, liveF.getPlayers().get(0)));
        // nor does a seat that holds no card that searches its library (the same hand without the tutor)
        final RlSearch none = search(ASK);
        // (Troll of Khazad-dum's swampcycling and Wishclaw Talisman are searches in the planner's model: they go too)
        final Game liveN = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("Chart a Course;Island;Counterspell",
                BF, 20, "", "Island", 20, "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + REST));
        Assert.assertNull(none.openNestedDraws(liveN, liveN.getPlayers().get(0)));
        Assert.assertEquals(none.nestedDrawNodes.get(), 0, "no search will ask: no table");
        System.out.println("NESTED4C no separation: table nodes " + nodes + " (work clock " + Math.round(nodes * NODE_NS / 1e6)
                + " ms), once per root position; a library with no card that ends the game: " + fair.nestedDrawNodes.get() + " nodes");
        Assert.assertEquals(fair.nestedDrawNodes.get(), 0);
    }

    /**
     * Doomsday's own five picks (library and graveyard, five cards) are not a tutor's menu: with a table at the root (the
     * seat also holds a card that searches) and an empty graveyard, none of them is answered from it and no floor is set.
     */
    @Test
    public void doomsdaysPicksAreNotReadFromTheNextDrawTable() {
        final RlSearch on = search("{\"nodes\":1,\"maxNodes\":1,\"askNodes\":" + ASK_NODES + "}"); // the plan shut: the table alone
        final Game live = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(
                HAND.replace("Mystical Tutor", "Doomsday").replace("Wishclaw Talisman", "Thassa's Oracle"), BF + ";Swamp", 20, "", "Island", 20,
                "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + REST));
        final Player me = live.getPlayers().get(0);
        final ForcedWinSolver.NextDraws nd = on.openNestedDraws(live, me);
        System.out.println("NESTED4C Doomsday in hand, the root's table: " + table(nd));
        final Playout a = playout(on, live, "Doomsday");
        Assert.assertEquals(a.asks, 5, "Doomsday asks for five cards, one at a time");
        Assert.assertEquals(a.answered, 0, "none is answered from the table");
        Assert.assertEquals(RlSearch.nestedPotOf(a.ctx), 0.0, 1e-12, "and no floor is set");
        Assert.assertEquals(on.nestedDrawAnswered.get(), 0);
    }

    /** A library search that does not give the seat the card (Entomb: to the graveyard) is not read from a table of draw values. */
    @Test
    public void aSearchThatDoesNotGiveTheCardKeepsThePolicysAnswer() {
        final RlSearch on = search(ASK);
        final Game live = root("Entomb", "Island", REST);
        Assert.assertNotNull(on.openNestedDraws(live, live.getPlayers().get(0)));
        final Playout a = playout(on, live, "Entomb");
        Assert.assertNull(a.nested, "Entomb's menu is not a tutor's");
        Assert.assertEquals(a.picked, a.policyPick);
        Assert.assertEquals(RlSearch.nestedPotOf(a.ctx), 0.0, 1e-12);
    }

    /**
     * The floor does not survive the opponent's kill. (1) Lethal on the board against our life at the root: the planner's
     * own rule already finds no line (no plan, no table), so nothing is carried and the cast stays below pass. (2) Lethal
     * only after the play: the play-out carries the floor, and at a leaf where the opponent's creatures deal our remaining
     * life the floor is gone; one life more and it stands. "Before" is the floor as jar 0a6467fe579 applied it.
     */
    @Test
    public void theFloorDoesNotSurviveTheOpponentsKill() {
        for (String tutor : new String[] {"Mystical Tutor", "Vampiric Tutor"}) {
            // (1) 24 power against our 20 life at the root
            final RlSearch on = search(ASK);
            final Game live = root(tutor, "Island;Phyrexian Dreadnought;Phyrexian Dreadnought", REST);
            final Player me = live.getPlayers().get(0);
            on.setNestedPlan(on.planNested(live, me, new ArrayList<>()), live);
            on.openNestedDraws(live, me);
            final Playout b = playout(on, live, tutor);
            final double t1 = on.combineReach(V_TUTOR, b.leaf), p1 = on.combineReach(V_PASS, passLeaf(on, live));
            Assert.assertNull(on.nestedPlan(), "the planner's lines do not survive the opponent's clock");
            Assert.assertNull(on.nestedDraws());
            Assert.assertEquals(RlSearch.nestedPotOf(b.ctx), 0.0, 1e-12);
            Assert.assertTrue(t1 <= p1, "cast the tutor does not rise above pass: " + t1 + " <= " + p1);
            // (2) 2 power on their board: a line at the root; the leaf at 2 life (dead to the next attack) and at 3 life
            for (int life : new int[] {2, 3}) {
                final RlSearch on2 = search(ASK);
                final Game live2 = root(tutor, "Island;Grizzly Bears", REST);
                final Player me2 = live2.getPlayers().get(0);
                on2.setNestedPlan(on2.planNested(live2, me2, new ArrayList<>()), live2);
                on2.openNestedDraws(live2, me2);
                final Playout c = playout(on2, live2, tutor, po -> po.me.setLife(life, null));
                final double pot = RlSearch.nestedPotOf(c.ctx);
                final GameCopier copier = new GameCopier(live2, true);
                final Game gp = copier.makeCopy();
                final Player mp = (Player) copier.find(me2);
                mp.setLife(life, null);
                final double[] pass = on2.leafReach(on2.onCopy(gp, mp), gp, mp);
                final double tutorOn = on2.combineReach(V_TUTOR, c.leaf), passOn = on2.combineReach(V_PASS, pass);
                final double before = on2.combineReach(V_TUTOR, new double[] {pot, c.leaf[1]});
                System.out.println("NESTED4C opponent clock, " + tutor + ", leaf at " + life + " life against 2 power: pick " + c.picked
                        + ", floor carried " + pot + ", leaf potential " + c.leaf[0] + ", opp reach " + c.leaf[1] + " | cast-tutor "
                        + tutorOn + " (before, jar 0a6467fe579's floor: " + before + ") vs pass " + passOn + " | lethal on board at the"
                        + " root: cast-tutor " + t1 + " vs pass " + p1 + " (no plan, no table)");
                Assert.assertEquals(c.picked, "Doomsday");
                Assert.assertTrue(pot > 0, "the play-out carries a floor");
                Assert.assertTrue(before > passOn, "the unopposed floor raised the cast above pass: " + before);
                if (life == 2) {
                    Assert.assertEquals(c.leaf[0], 0.0, 1e-12, "the floor is gone: their next attack kills");
                    Assert.assertTrue(tutorOn <= passOn, "cast the tutor does not rise above pass: " + tutorOn + " <= " + passOn);
                    Assert.assertTrue(on2.nestedFloorsVoid.get() >= 1);
                } else {
                    Assert.assertEquals(c.leaf[0], pot * (1 - c.leaf[1]), 1e-12, "one life more: the floor stands");
                    Assert.assertTrue(tutorOn > passOn);
                }
            }
        }
    }

    /** The live ask of an any-card tutor cast at the last root decision reads the table its play-outs read; another turn's does not. */
    @Test
    public void theLiveAskOfAnAnyCardTutorReadsTheSameTable() {
        final RlSearch on = search(ASK);
        final Game live = root("Vampiric Tutor", "Island", REST);
        final Player me = live.getPlayers().get(0);
        Assert.assertNotNull(on.openNestedDraws(live, me));
        final int[] hit = {-9};
        final String[] picked = {null};
        me.dangerouslySetController(new PlayerControllerAi(live, me, me.getController().getLobbyPlayer()) {
            @Override
            public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
                    final SpellAbility sa, final CardCollection fetchList, final forge.game.player.DelayedReveal delayedReveal,
                    final String selectPrompt, final boolean isOptional, final Player decider) {
                final JsonObject body = new JsonObject();
                body.addProperty("min", isOptional ? 0 : 1);
                body.addProperty("max", 1);
                final RlCandidates.Menu m = RlCandidates.build(live, me, "chooseSingleCardForZoneChange", "zoneChange", body, fetchList);
                short own = -1;
                for (int i = 0; i < fetchList.size() && own < 0; i++) {
                    if (!fetchList.get(i).getName().equals("Doomsday")) {
                        own = (short) i;
                    }
                }
                hit[0] = on.nestedLiveDraw(live, me, m, new short[] {own});
                picked[0] = hit[0] < 0 ? null : fetchList.get(hit[0]).getName();
                return fetchList.get(hit[0] < 0 ? own : hit[0]);
            }
        });
        final SpellAbility sa = find(me, ZoneType.Hand, "Vampiric Tutor").getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(sa), "cast the tutor");
        MyRandom.setRandom(new Random(42));
        live.getStack().resolveStack();
        Assert.assertEquals(picked[0], "Doomsday", "the live pick is the table's");
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).get(0).getName(), "Doomsday", "on top of the live library");
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
        Assert.assertEquals(RlSearch.NestedCfg.parse(new JsonObject()).askNodes, 0, "the next-draw table is off unless asked for");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.NestedCfg.parse(
                JsonParser.parseString("{\"askNodes\":-1}").getAsJsonObject()));
    }
}
