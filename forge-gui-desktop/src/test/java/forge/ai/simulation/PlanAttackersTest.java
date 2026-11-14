package forge.ai.simulation;

import java.util.Arrays;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.TgtNeed;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane plantargets-1114 (solver key planTargets, slice "attackers" = slice 1 of the target-choice design; card names
 * are test data only). An ability of ours that takes one target which may be an opposing creature is also learned on a
 * stand-in the opponent owns: what the engine did to it (gone, bounced, damaged) becomes the ability's target need, the
 * same whatever the stand-in. The width search then plays it on an attacker of the opponent, the target chosen when the
 * turn is passed. A card that gains such a need still opens the engine-search gate; an ability that cannot take an
 * opposing creature is unchanged; with the key off nothing changes.
 */
public class PlanAttackersTest extends AITest {
    static final String DESTROY = "Murder", BOUNCE = "Unsummon", DAMAGE = "Flame Slash", ANY = "Shock";
    /** Target creature you control; a spell on the stack; a pump (nothing the clock sees). */
    static final String[] UNCHANGED = {"Cloudshift", "Counterspell", "Giant Growth"};
    static final String LANDS = "Mountain;Swamp;Swamp;Swamp;Island;Plains;Forest";
    static final String BOARD_A = "Plains;Grizzly Bears;Hill Giant", BOARD_B = "Plains;Llanowar Elves;Craw Wurm";

    private static String shape(final ACard a) {
        return new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(a.instance(0));
    }

    private static Game game(final String hand, final String ourField, final int life, final String oppField, final int oppLife) {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame(hand, ourField, life, "", oppField, oppLife, "human"));
    }

    private static ACard learn(final Game g, final String name, final boolean key) {
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.planAttackers = key;
        final Card c = ForcedWinSolverTest.inZone(me, ZoneType.Hand, name);
        Assert.assertNotNull(c, name);
        final ACard a = r.card(g, me, c);
        Assert.assertEquals(r.templates.containsKey("pta|" + name), key, "the key's own template key");
        Assert.assertEquals(r.templates.containsKey(name), !key, "the plain template key");
        return a;
    }

    private static TgtNeed need(final ACard a) {
        TgtNeed out = null;
        for (Abil ab : a.fromHand) {
            if (ab.tgt != null) {
                Assert.assertNull(out, "one attacker form a card");
                Assert.assertTrue(ab.cardTargets && !ab.unplayable, "the attacker form is a playable card-target ability");
                out = ab.tgt;
            }
        }
        return out;
    }

    /** The engine-search gate's reading of one card (ForcedWinSolver: unmodeled, or with engineGateWide any unplayable ability). */
    private static boolean[] gate(final ACard a) {
        boolean un = false;
        for (Abil ab : a.fromHand) {
            un |= ab.unplayable;
        }
        for (Abil ab : a.onBattlefield) {
            un |= ab.unplayable;
        }
        return new boolean[] {a.unmodeled, a.unmodeled || un};
    }

    @Test
    public void theSliceIsOffByDefault() {
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).planTargets("attackers"));
        Assert.assertFalse(new ForcedWinModel.Reader().planAttackers);
        Assert.assertFalse(ForcedWinProbe.PLAN_ATTACKERS.get());
        final Game g = game(DESTROY, LANDS, 20, BOARD_A, 20);
        final Player me = g.getPlayers().get(0);
        Assert.assertFalse(new ForcedWinSolver(me, ForcedWinSolver.Config.fromProps(k -> null)).reader.planAttackers);
        final ForcedWinSolver face = new ForcedWinSolver(me, ForcedWinSolver.Config.fromProps(k -> "planTargets".equals(k) ? "face" : null));
        Assert.assertFalse(face.reader.planAttackers, "slice face alone does not turn it on");
        final ForcedWinSolver on = new ForcedWinSolver(me,
                ForcedWinSolver.Config.fromProps(k -> "planTargets".equals(k) ? "face,attackers" : null));
        Assert.assertTrue(on.reader.planAttackers && on.reader.planFace);
        Assert.assertFalse(on.oppReader.planAttackers, "our own cards and positions only");
        // without the key: no template holds a target need, no position holds masks
        for (String name : new String[] {DESTROY, BOUNCE, DAMAGE, ANY}) {
            final ACard off = learn(game(name, LANDS, 20, BOARD_A, 20), name, false);
            Assert.assertNull(need(off), name);
            Assert.assertFalse(shape(off).contains("\"tgt\""), name + ": the template's text is as before the key");
        }
        final ForcedWinModel.Position pos = new ForcedWinModel.Reader().read(g, me, null, true);
        Assert.assertNull(pos.tgtMask);
        Assert.assertNull(pos.oppIds);
        Assert.assertEquals(pos.oppPowers, Arrays.asList(3, 2));
    }

    @Test
    public void whatTheEngineDoesToAStandInBecomesTheNeedWhateverTheStandIn() {
        final Object[][] want = {{DESTROY, TgtNeed.GONE, 0}, {BOUNCE, TgtNeed.BOUNCE, 0}, {DAMAGE, TgtNeed.DAMAGE, 4}, {ANY, TgtNeed.DAMAGE, 2}};
        for (Object[] w : want) {
            final String name = (String) w[0];
            final ACard a = learn(game(name, LANDS, 20, BOARD_A, 20), name, true);
            final ACard b = learn(game(name, LANDS, 20, BOARD_B, 20), name, true);
            final ACard one = learn(game(name, LANDS, 20, "Plains;Hill Giant", 20), name, true);
            System.err.println("PA on  " + name + " " + shape(a));
            final TgtNeed n = need(a);
            Assert.assertNotNull(n, name + ": a target need");
            Assert.assertEquals(n.kind, ((Integer) w[1]).intValue(), name + ": " + n);
            Assert.assertEquals(n.n, ((Integer) w[2]).intValue(), name + ": " + n);
            Assert.assertEquals(n.link, 0, name);
            Assert.assertEquals(shape(a), shape(b), name + ": the model does not depend on the opponent's creatures");
            Assert.assertEquals(shape(a), shape(one), name + ": nor on how many there are");
            // no opposing creature to stand in: no need is learned, and the model is the one without the key
            final ACard none = learn(game(name, LANDS, 20, "Plains", 20), name, true);
            Assert.assertNull(need(none), name + ": nothing to learn it on");
            Assert.assertEquals(shape(none), shape(learn(game(name, LANDS, 20, "Plains", 20), name, false)), name);
        }
        // a spell that may also hit a player keeps its player variants; the attacker form is one more ability
        final ACard any = learn(game(ANY, LANDS, 20, BOARD_A, 20), ANY, true);
        final ACard anyOff = learn(game(ANY, LANDS, 20, BOARD_A, 20), ANY, false);
        Assert.assertEquals(any.fromHand.size(), anyOff.fromHand.size() + 1, shape(any));
    }

    @Test
    public void aLiftedCardStillOpensTheEngineSearchGate() {
        for (String name : new String[] {DESTROY, BOUNCE, DAMAGE, ANY}) {
            for (String board : new String[] {BOARD_A, "Plains"}) {
                final boolean[] on = gate(learn(game(name, LANDS, 20, board, 20), name, true));
                final boolean[] off = gate(learn(game(name, LANDS, 20, board, 20), name, false));
                System.err.println("PA gate " + name + " / " + board + " off " + Arrays.toString(off) + " on " + Arrays.toString(on));
                Assert.assertEquals(on[0], off[0], name + ": the card-level unmodeled flag (the gate reads it) is the same with the key");
                Assert.assertTrue(on[1] || !off[1], name + ": the wide gate (unmodeled or an unplayable ability) is not closed by the key");
            }
        }
        // the three whose only target is a card are unmodeled today and stay so: the engine search keeps its positions
        for (String name : new String[] {DESTROY, BOUNCE, DAMAGE}) {
            Assert.assertTrue(learn(game(name, LANDS, 20, BOARD_A, 20), name, true).unmodeled, name);
        }
    }

    @Test
    public void anAbilityThatCannotTakeAnOpposingCreatureIsUnchanged() {
        for (String name : UNCHANGED) {
            for (String board : new String[] {BOARD_A, "Plains"}) {
                final Game g = game(name, LANDS + ";Runeclaw Bear", 20, board, 20);
                final Game h = game(name, LANDS + ";Runeclaw Bear", 20, board, 20);
                final ACard on = learn(g, name, true), off = learn(h, name, false);
                Assert.assertNull(need(on), name + " against " + board);
                Assert.assertEquals(shape(on), shape(off), name + " against " + board);
            }
        }
    }

    @Test
    public void theReaderAddsThePublicAttackersAndTheEnginesLegalTargets() {
        final Game g = game(DESTROY + ";" + DAMAGE, LANDS, 20, "Plains;Grizzly Bears;Hill Giant;Craw Wurm;Llanowar Elves", 20);
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.planAttackers = true;
        final ForcedWinModel.Position pos = r.read(g, me, null, true);
        Assert.assertEquals(pos.oppPowers, Arrays.asList(6, 3, 2, 1));
        Assert.assertEquals(pos.oppTough, Arrays.asList(4, 3, 2, 1));
        Assert.assertEquals(pos.oppIds.get(0).intValue(), ForcedWinSolverTest.inZone(opp, ZoneType.Battlefield, "Craw Wurm").getId());
        Assert.assertEquals(pos.tgtMask.size(), 2, String.valueOf(pos.tgtMask));
        for (int m : pos.tgtMask.values()) {
            Assert.assertEquals(m, 0b0111, "the three largest attackers the engine accepts (the cap)");
        }
        // the plain reader's position is the one before the key
        final ForcedWinModel.Position plain = new ForcedWinModel.Reader().read(g, me, null, true);
        Assert.assertEquals(plain.oppPowers, pos.oppPowers);
        Assert.assertNull(plain.tgtMask);
    }

    /** Opponent at 6, we at 3 with one red source: a burn spell now, one next turn; their 3-power attacker kills us in between. */
    private static ForcedWinPlanner.Result plan(final boolean key, final String removal, final int[] giantId) {
        final Game g = game("Lightning Bolt;Lightning Bolt;" + removal, "Mountain;Swamp;Swamp;Swamp;Island", 3, "Plains;Hill Giant", 6);
        final Player me = g.getPlayers().get(0);
        giantId[0] = ForcedWinSolverTest.inZone(g.getPlayers().get(1), ZoneType.Battlefield, "Hill Giant").getId();
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.planAttackers = key;
        final ForcedWinModel.Position pos = r.read(g, me, null, true);
        final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, new ForcedWinPlanner.Limits());
        System.err.println("PA plan key=" + key + " " + removal + " lines=" + res.lines.size() + " nodes=" + res.nodes + " tgtMoves="
                + res.tgtMoves + " tgtPasses=" + res.tgtPasses + (res.lines.isEmpty() ? "" : " first=" + res.lines.get(0).steps));
        return res;
    }

    @Test
    public void theWidthSearchRemovesAnAttackerToSurviveTheOpposingTurn() {
        for (String removal : new String[] {DESTROY, BOUNCE}) {
            final int[] giant = {-1};
            final ForcedWinPlanner.Result off = plan(false, removal, giant);
            Assert.assertTrue(off.lines.isEmpty(), removal + ": without the key the line that passes the turn dies to the attacker");
            Assert.assertEquals(off.tgtMoves, 0);
            final ForcedWinPlanner.Result on = plan(true, removal, giant);
            Assert.assertFalse(on.lines.isEmpty(), removal + ": with the key the attacker is removed first");
            Assert.assertTrue(on.tgtMoves > 0 && on.tgtPasses > 0);
            boolean aimed = false;
            for (ForcedWinPlanner.Step st : on.lines.get(0).steps) {
                aimed |= st.objTarget == giant[0];
            }
            Assert.assertTrue(aimed, removal + ": the line's removal step names the attacker for the follower: " + on.lines.get(0).steps);
        }
    }

    @Test
    public void damageRemovesOnlyWhatItKills() {
        // Shock (2 damage) cannot take a 3-toughness attacker off the clock: no line, with or without the key
        final int[] giant = {-1};
        Assert.assertTrue(plan(true, ANY, giant).lines.isEmpty(), "2 damage does not remove a 3-toughness attacker");
        // Flame Slash (4 damage, one red: it needs the Mountain the first burn spell needs) is not affordable beside it;
        // against a Grizzly Bears alone (2 power, we are at 3) no removal is needed and none is forced
        final Game g = game("Lightning Bolt;Lightning Bolt;" + DESTROY, "Mountain;Swamp;Swamp;Swamp;Island", 3, "Plains;Grizzly Bears", 6);
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.planAttackers = true;
        final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(r.read(g, me, null, true), new ForcedWinPlanner.Limits());
        Assert.assertFalse(res.lines.isEmpty(), "the line survives 2 damage at 3 life with or without the removal");
    }

    /**
     * Measurement (printed; the only assertions are that the key-off search plays no removal and that the key never
     * loses a line): the width search's nodes with the slice off and on, on unit positions with 0 to 4 opposing
     * attackers, one to three removal spells in hand beside cantrips and burn, at a safe and at a threatened life total.
     */
    @Test
    public void nodeCostOnUnitPositions() {
        final String[] hands = {
            "Brainstorm;Ponder;Preordain;Lightning Bolt;" + DESTROY,
            "Brainstorm;Ponder;Preordain;Lightning Bolt;Lightning Bolt;" + DESTROY + ";" + BOUNCE,
            "Brainstorm;Ponder;Gitaxian Probe;Lightning Bolt;" + DESTROY + ";" + BOUNCE + ";" + DAMAGE,
        };
        final String[] boards = {"Plains", "Plains;Hill Giant", "Plains;Hill Giant;Grizzly Bears;Craw Wurm",
            "Plains;Hill Giant;Grizzly Bears;Craw Wurm;Llanowar Elves"};
        final String field = "Mountain;Mountain;Swamp;Swamp;Swamp;Island;Island;Island";
        for (int life : new int[] {20, 5}) {
            for (String hand : hands) {
                for (String board : boards) {
                    final int[] nodes = new int[2], lines = new int[2], moves = new int[2];
                    final long[] ms = new long[2];
                    for (int key = 0; key < 2; key++) {
                        final Game g = game(hand, field, life, board, 9);
                        final Player me = g.getPlayers().get(0);
                        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
                        r.planAttackers = key == 1;
                        final ForcedWinModel.Position pos = r.read(g, me, null, true);
                        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
                        lim.ms = 60_000; // the node limit, not the clock, ends a search here
                        final long t0 = System.nanoTime();
                        final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, lim);
                        ms[key] = (System.nanoTime() - t0) / 1_000_000L;
                        nodes[key] = res.nodes;
                        lines[key] = res.lines.size();
                        moves[key] = res.tgtMoves;
                    }
                    System.err.println("PA nodes life=" + life + " removal=" + (hand.split(";").length - 4) + " attackers=" + (board.split(";").length - 1)
                            + " off nodes=" + nodes[0] + " lines=" + lines[0] + " ms=" + ms[0] + " | on nodes=" + nodes[1] + " lines=" + lines[1]
                            + " ms=" + ms[1] + " tgtMoves=" + moves[1]);
                    Assert.assertEquals(moves[0], 0);
                    Assert.assertTrue(lines[1] > 0 || lines[0] == 0, "the key never loses a line: " + hand + " / " + board);
                }
            }
        }
    }

    @Test
    public void thePlanRowsBoardIsCountsOnlyAndOffByDefault() {
        Assert.assertFalse(forge.bench.rl.RlSearch.PLAN_BOARD_LOG, "-Dsdcc.planBoardLog is off unless asked: the plan row is as before");
        final Game g = game(DESTROY + ";" + BOUNCE, LANDS + ";Sol Ring", 17, "Plains;Grizzly Bears;Hill Giant", 9);
        final Player me = g.getPlayers().get(0);
        final com.google.gson.JsonObject b = forge.bench.rl.RlSearch.boardCounts(g, me);
        final java.util.Set<String> keys = new java.util.TreeSet<>(Arrays.asList("creatures", "creatures_untapped", "artifacts", "enchantments",
                "planeswalkers", "lands", "hand", "graveyard", "life"));
        Assert.assertEquals(b.keySet(), new java.util.TreeSet<>(Arrays.asList("me", "opp")));
        for (String side : new String[] {"me", "opp"}) {
            final com.google.gson.JsonObject o = b.getAsJsonObject(side);
            Assert.assertEquals(new java.util.TreeSet<>(o.keySet()), keys, side);
            for (String k : keys) {
                Assert.assertTrue(o.get(k).getAsJsonPrimitive().isNumber(), side + "." + k + " is a count");
            }
        }
        Assert.assertEquals(b.getAsJsonObject("me").get("hand").getAsInt(), 2);
        Assert.assertEquals(b.getAsJsonObject("me").get("lands").getAsInt(), 7);
        Assert.assertEquals(b.getAsJsonObject("me").get("artifacts").getAsInt(), 1);
        Assert.assertEquals(b.getAsJsonObject("me").get("life").getAsInt(), 17);
        Assert.assertEquals(b.getAsJsonObject("opp").get("creatures").getAsInt(), 2);
        Assert.assertEquals(b.getAsJsonObject("opp").get("creatures_untapped").getAsInt(), 2);
        Assert.assertEquals(b.getAsJsonObject("opp").get("hand").getAsInt(), 0);
        Assert.assertEquals(b.getAsJsonObject("opp").get("life").getAsInt(), 9);
        Assert.assertFalse(b.toString().contains("Giant") || b.toString().contains("Murder"), "no card names");
    }

    @Test
    public void theTableGeneratesTheVariantUnderItsOwnKeys() {
        final ForcedWinCanon.Table tb = ForcedWinCanon.newTable(Arrays.asList(DESTROY, BOUNCE, DAMAGE, ANY, "Cloudshift", "Counterspell",
                "Grizzly Bears", "Hill Giant", "Llanowar Elves", "Craw Wurm", "Sol Ring", "Brainstorm", "Lightning Bolt"));
        try {
            for (String name : new String[] {DESTROY, BOUNCE, DAMAGE, ANY}) {
                Assert.assertTrue(ForcedWinCanon.generate(tb, "", name), name);
                Assert.assertTrue(ForcedWinCanon.generate(tb, "pta|", name), "pta|" + name);
                Assert.assertTrue(ForcedWinCanon.generate(tb, "pta|ptf|", name), "pta|ptf|" + name);
                final ACard plain = tb.entries.get(name).card, pa = tb.entries.get("pta|" + name).card;
                System.err.println("PA table " + name + " " + need(pa));
                Assert.assertNull(need(plain), name);
                Assert.assertNotNull(need(pa), name + ": the canonical sandbox gives the opponent a creature to stand in");
                Assert.assertEquals(shape(tb.entries.get("pta|ptf|" + name).card), shape(pa), name + ": slice face changes nothing here");
            }
            Assert.assertTrue(ForcedWinCanon.generate(tb, "", "Cloudshift"));
            Assert.assertTrue(ForcedWinCanon.generate(tb, "pta|", "Cloudshift"));
            Assert.assertEquals(shape(tb.entries.get("pta|Cloudshift").card), shape(tb.entries.get("Cloudshift").card));
        } finally {
            ForcedWinCanon.forgetForTest();
        }
    }
}
