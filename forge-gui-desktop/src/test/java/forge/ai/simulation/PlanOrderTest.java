package forge.ai.simulation;

import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * planner-fixes-1110 (solverCfg planOrder): where the width search spends its nodes. The frames are card facts used as
 * tests only. The first test's position is the owner's (2026-10-10T22-07-51, the first decision of his kill turn): a
 * pile spell now, a free draw-two and a win condition on our next turn, four moves, which the depth-first search
 * misses at 30,000 nodes and finds at 430,657.
 */
public class PlanOrderTest extends forge.ai.AITest {

    private static final String OWNER_2207 = "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=18\n"
            + "humanlandsplayed=0\nailandsplayed=0\n"
            + "aihand=Rotting Regisaur;Grave Titan;Dauthi Voidwalker;Retrofitter Foundry;Seasoned Dungeoneer\n"
            + "ailibrary=Custodi Lich;Lurrus of the Dream-Den;Damn;Imperial Seal;Pack Rat;Esper Sentinel;Dismember;"
            + "Reckoner Bankbuster;From the Catacombs;Liliana of the Veil;Entomb;Lingering Souls;Deep-Cavern Bat;Marsh Flats;"
            + "Scrubland;Plains;Plains;Plains;Plains;Plains;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp\n"
            + "aigraveyard=Thoughtseize\naiexile=\naibattlefield=Plains;Skullclamp;Swamp|Tapped;Smuggler's Copter;Swamp|Tapped\n"
            + "humanhand=Thassa's Oracle;Gush;Scalding Tarn;Doomsday;Baleful Strix\n"
            + "humanbattlefield=Island;Raffine's Tower;Urborg, Tomb of Yawgmoth;Lotus Petal;Wishclaw Talisman\n"
            + "humangraveyard=Troll of Khazad-dûm;Cabal Ritual\nhumanexile=\n"
            + "humanlibrary=Counterspell;Jace, Wielder of Mysteries;Verdant Catacombs;Chart a Course;Memory Jar;Echo of Eons;"
            + "Waterlogged Grove;Darkslick Shores;Xander's Lounge;Timetwister;Misty Rainforest;Beseech the Mirror;Daze;Remand;"
            + "Bolas's Citadel;Mystical Tutor;Library of Alexandria;Jace, the Mind Sculptor;Mind Stone;Overgrown Tomb;"
            + "Collective Brutality;Stern Scolding;Chromatic Star;Island;Island;Swamp;Swamp;Swamp\n";

    /** The planner keys of the pilot's solver config (what the width search reads), and the given planOrder. */
    private static ForcedWinSolver.Config pilot(final int order) {
        final Map<String, String> kv = new HashMap<>();
        for (String k : new String[] {"planDiscardWall", "planExpandCap", "sourceColorsLive", "sourceOutput", "paySearch"}) {
            kv.put(k, "true");
        }
        kv.put("planOrder", String.valueOf(order));
        kv.put("act", "false");
        kv.put("defense", "false");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    /**
     * The offense's width searches of one decision at {@code order}: this turn, then (no line) by our next turn; the
     * limits are the solver's own, as {@link ForcedWinSolver} sets them before its first search. {@code ms < 0}: no wall.
     */
    static ForcedWinPlanner.Result[] offensePlans(final ForcedWinSolver s, final ForcedWinModel.Position pos, final int order,
            final long ms) {
        final ForcedWinSolver.Config c = s.config();
        final ForcedWinPlanner.Limits lim = s.limits();
        lim.order = order;
        lim.nodes = c.nodes;
        lim.ms = ms;
        lim.payPolicies = 1;
        lim.planScry = c.planScry;
        lim.planDiscard = c.planDiscard;
        lim.planScryMax = c.planScryMax;
        lim.payRecord = c.payRecord;
        lim.payVariants = c.payVariants;
        lim.horizon = 1;
        final ForcedWinPlanner.Result r1 = ForcedWinPlanner.plan(pos, lim);
        ForcedWinPlanner.Result r2 = null;
        if (r1.lines.isEmpty() && c.horizon >= 2) {
            lim.horizon = c.horizon;
            r2 = ForcedWinPlanner.plan(pos, lim);
        }
        return new ForcedWinPlanner.Result[] {r1, r2};
    }

    private static String cut(final Object o, final int n) {
        final String s = String.valueOf(o).replaceAll("\\s+", " ");
        return s.substring(0, Math.min(n, s.length()));
    }

    private static String row(final ForcedWinPlanner.Result[] rs) {
        final StringBuilder sb = new StringBuilder();
        int nodes = 0;
        long nanos = 0;
        ForcedWinPlanner.Result last = null;
        for (ForcedWinPlanner.Result r : rs) {
            if (r == null) {
                continue;
            }
            nodes += r.nodes;
            nanos += r.nanos;
            last = r;
            sb.append(" [").append(r.summary()).append(']');
        }
        final int firstAt = last == null || last.firstLineAt < 0 ? -1 : nodes - last.nodes + last.firstLineAt;
        return "lines=" + (last == null ? 0 : last.lines.size()) + " nodes=" + nodes + " firstLineAtTotal=" + firstAt + " ms="
                + nanos / 1_000_000 + sb + (last == null || last.lines.isEmpty() ? "" : " best=" + cut(last.lines.get(0), 420));
    }

    @Test
    public void aFourMoveNextTurnKillIsFoundInsideTheDefaultAllowance() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", OWNER_2207);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, pilot(7));
        final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
        Assert.assertNotNull(pos);
        final ForcedWinPlanner.Result[] off = offensePlans(s, pos, 0, -1L);
        System.out.println("PLANORDER owner-2207 order=0 " + row(off));
        final ForcedWinPlanner.Result[] on = offensePlans(s, pos, 7, -1L);
        System.out.println("PLANORDER owner-2207 order=7 " + row(on));
        // this turn has no kill: the whole same-turn tree is searched, well inside the allowance
        Assert.assertTrue(on[0].lines.isEmpty());
        Assert.assertFalse(on[0].exhausted, on[0].summary());
        Assert.assertTrue(on[0].nodes < 5000, on[0].summary());
        // by our next turn: found by a depth-limited pass, a small share of the allowance
        Assert.assertNotNull(on[1]);
        Assert.assertFalse(on[1].lines.isEmpty(), on[1].summary());
        Assert.assertTrue(on[1].firstLineAt >= 0 && on[1].firstLineAt < 3000, on[1].summary());
        Assert.assertTrue(on[1].nodes <= 30000, on[1].summary());
        final ForcedWinPlanner.Line best = on[1].lines.get(0);
        Assert.assertEquals(best.winTurn, 1, String.valueOf(best));
        Assert.assertEquals(best.pileNames.size(), 5, String.valueOf(best));
        Assert.assertEquals(new HashSet<>(best.pileNames).size(), 5, String.valueOf(best));
        // the drawn cards the line never plays are filled in as protection (at most two counters), no placeholder left
        Assert.assertEquals(best.protection, 2, String.valueOf(best));
        for (ForcedWinPlanner.Line l : on[1].lines) {
            for (ForcedWinPlanner.Step st : l.steps) {
                for (int id : st.putBack) {
                    Assert.assertTrue(id >= -1, String.valueOf(l));
                }
                Assert.assertEquals(new HashSet<>(st.pile).size(), st.pile.size(), String.valueOf(l));
            }
        }
        // each part of the key alone is the search it names (and none of them needs more than the allowance)
        for (int order = 1; order < 7; order++) {
            final ForcedWinPlanner.Result[] r = offensePlans(s, pos, order, -1L);
            System.out.println("PLANORDER owner-2207 order=" + order + " " + row(r));
            Assert.assertTrue(r[0].nodes <= 30000 && (r[1] == null || r[1].nodes <= 30000), row(r));
        }
    }

    @Test
    public void theSolverTakesTheKillWithTheKeyOn() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", OWNER_2207);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, pilot(7));
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        System.out.println("PLANORDER owner-2207 propose lines=" + props.size() + (best == null ? " none"
                : " pUnopposed=" + best.pUnopposed + " pBelief=" + best.pBelief + " winTurn=" + best.winTurn + " | "
                        + cut(best.text, 400)) + " || " + s.summary());
        Assert.assertNotNull(best, s.summary());
        Assert.assertEquals(best.pUnopposed, 1.0, cut(best.text, 400));
        Assert.assertTrue(best.pBelief >= 0.8, cut(best.text, 400));
    }

    private static final String PILE_LIB = "Counterspell;Daze;Lotus Petal;Jace, Wielder of Mysteries;Ponder;Island;Island;Swamp;"
            + "Swamp;Memory Jar;Mind Stone;Remand;Dark Ritual;Cabal Ritual;Chromatic Star;Bolas's Citadel;Collective Brutality";

    private static Game pileGame(final String hand, final String battlefield) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "Grizzly Bears;Forest", "Forest;Forest", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + PILE_LIB);
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    private static ForcedWinSolver.Proposal bestOf(final ForcedWinSolver s, final Game g, final String tag) {
        ForcedWinSolver.Proposal best = null;
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        System.out.println("PLANORDER " + tag + " propose lines=" + props.size() + (best == null ? " none" : " pUnopposed="
                + best.pUnopposed + " pBelief=" + best.pBelief + " winTurn=" + best.winTurn + " | " + cut(best.text, 500))
                + " || " + s.summary());
        return best;
    }

    /**
     * A draw-three that puts two back, on a pile: the three drawn cards stay unchosen, two go back unchosen (the line
     * needs neither), a draw-two takes them again, and the line names real cards for the put-back when it is recorded.
     * The engine plays it: the put-back ask is answered with those cards.
     */
    @Test
    public void unchosenCardsArePutBackAndNamedWhenTheLineIsRecorded() {
        final Game g = pileGame("Doomsday;Brainstorm;Gush;Thassa's Oracle", "Island;Island;Swamp;Swamp;Swamp");
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, pilot(7));
        final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
        final ForcedWinPlanner.Result[] off = offensePlans(s, pos, 0, -1L);
        System.out.println("PLANORDER putback order=0 " + row(off));
        final ForcedWinPlanner.Result[] on = offensePlans(s, pos, 7, -1L);
        System.out.println("PLANORDER putback order=7 " + row(on));
        Assert.assertFalse(on[0].lines.isEmpty(), on[0].summary());
        Assert.assertTrue(on[0].nodes <= off[0].nodes, row(on) + " vs " + row(off));
        boolean putBack = false;
        for (ForcedWinPlanner.Line l : on[0].lines) {
            Set<Integer> pile = new HashSet<>();
            for (ForcedWinPlanner.Step st : l.steps) {
                pile.addAll(st.pile);
            }
            for (ForcedWinPlanner.Step st : l.steps) {
                for (int id : st.putBack) {
                    Assert.assertTrue(id >= 0, String.valueOf(l)); // a real card of ours: no placeholder, none unknown
                    putBack = true;
                }
                Assert.assertEquals(new HashSet<>(st.pile).size(), st.pile.size(), String.valueOf(l));
            }
            Assert.assertEquals(pile.size(), 5, String.valueOf(l));
        }
        Assert.assertTrue(putBack, row(on));
        final ForcedWinSolver.Proposal best = bestOf(new ForcedWinSolver(me, pilot(7)), g, "putback");
        Assert.assertNotNull(best);
        Assert.assertEquals(best.pUnopposed, 1.0, cut(best.text, 500));
        Assert.assertEquals(best.winTurn, 0, cut(best.text, 500));
    }

    /**
     * A draw-two that discards one, on a pile: the discard takes an unchosen card (the least useful card left for the
     * pile, named in the line's discard answer) or a card of the hand; the engine plays the line either way.
     */
    @Test
    public void anUnchosenCardIsDiscardedAsTheLeastUsefulCardLeft() {
        final Game g = pileGame("Doomsday;Chart a Course;Gush;Thassa's Oracle", "Island;Island;Swamp;Swamp;Swamp;Lotus Petal");
        final Player me = g.getPlayers().get(0);
        for (boolean choose : new boolean[] {true, false}) {
            final ForcedWinSolver.Config c = pilot(7);
            c.planDiscard = choose;
            final ForcedWinSolver s = new ForcedWinSolver(me, c);
            final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
            final ForcedWinPlanner.Result[] on = offensePlans(s, pos, 7, -1L);
            System.out.println("PLANORDER discard planDiscard=" + choose + " order=7 " + row(on));
            final ForcedWinPlanner.Result last = on[1] != null ? on[1] : on[0];
            Assert.assertFalse(last.lines.isEmpty(), row(on));
            for (ForcedWinPlanner.Line l : last.lines) {
                final Set<Integer> pile = new HashSet<>();
                for (ForcedWinPlanner.Step st : l.steps) {
                    pile.addAll(st.pile);
                    Assert.assertEquals(new HashSet<>(st.pile).size(), st.pile.size(), String.valueOf(l));
                }
                Assert.assertEquals(pile.size(), 5, String.valueOf(l));
            }
            final ForcedWinSolver.Proposal best = bestOf(new ForcedWinSolver(me, c), g, "discard planDiscard=" + choose);
            Assert.assertNotNull(best);
            Assert.assertEquals(best.pUnopposed, 1.0, cut(best.text, 500));
        }
    }

    @Test
    public void theKeyIsOffByDefaultAndASumOfItsParts() {
        Assert.assertEquals(new ForcedWinPlanner.Limits().order, 0);
        if (System.getProperty("forge.ai.forcedWin.planOrder") == null) {
            Assert.assertEquals(ForcedWinSolver.Config.fromProps(k -> null).planOrder, 0);
        }
        Assert.assertEquals(ForcedWinSolver.Config.fromProps(k -> "planOrder".equals(k) ? "7" : null).planOrder, 7);
        Assert.assertEquals(ForcedWinPlanner.Limits.ORDER_LAZY | ForcedWinPlanner.Limits.ORDER_WIN_FIRST
                | ForcedWinPlanner.Limits.ORDER_SHALLOW, 7);
        Assert.assertThrows(IllegalArgumentException.class,
                () -> ForcedWinSolver.Config.fromProps(k -> "planOrder".equals(k) ? "8" : null));
    }

    /**
     * Bench (no assertion; does nothing without {@code -Downer.positions=FILE}, the JSON of OwnerLinesTest): the offense's
     * width searches alone, no engine and no wall clock, at each planOrder of {@code -Dplan.orders} (default 0,7).
     */
    @Test
    public void ownerPositionsPlannerOnly() throws Exception {
        final String path = System.getProperty("owner.positions");
        if (path == null || path.isEmpty()) {
            System.out.println("PLANORDER bench skipped: no -Downer.positions");
            return;
        }
        final JsonObject root;
        try (FileReader r = new FileReader(path)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        final JsonObject solverCfg = root.getAsJsonObject("solverCfg").deepCopy();
        solverCfg.addProperty("act", "false");
        solverCfg.addProperty("defense", "false");
        final String[] orders = System.getProperty("plan.orders", "0,7").split(",");
        final JsonArray ps = root.getAsJsonArray("positions");
        final Map<String, int[]> totals = new java.util.TreeMap<>();
        for (JsonElement e : ps) {
            final JsonObject o = e.getAsJsonObject();
            final String id = o.get("game").getAsString();
            try {
                final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", o.get("state").getAsString());
                final Player me = g.getPlayers().get(0);
                final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(solverCfg));
                final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
                if (pos == null) {
                    System.out.println("PLANORDER " + id + " no position");
                    continue;
                }
                final Set<String> seen = new HashSet<>();
                for (String os : orders) {
                    final int order = Integer.parseInt(os.trim());
                    final ForcedWinPlanner.Result[] rs = offensePlans(s, pos, order, -1L);
                    System.out.println("PLANORDER " + id + " order=" + order + " " + row(rs));
                    final int[] t = totals.computeIfAbsent(os.trim(), k -> new int[4]);
                    final ForcedWinPlanner.Result last = rs[1] != null ? rs[1] : rs[0];
                    t[0] += last.lines.isEmpty() ? 0 : 1;
                    t[1] += rs[0].nodes + (rs[1] == null ? 0 : rs[1].nodes);
                    t[2] += (int) ((rs[0].nanos + (rs[1] == null ? 0 : rs[1].nanos)) / 1_000_000);
                    t[3] += rs[0].exhausted || rs[1] != null && rs[1].exhausted ? 1 : 0;
                    seen.add(os);
                }
            } catch (RuntimeException | Error ex) {
                System.out.println("PLANORDER " + id + " failed: " + cut(ex, 300));
                ex.printStackTrace(System.out);
            }
        }
        for (Map.Entry<String, int[]> t : totals.entrySet()) {
            System.out.println("PLANORDER total order=" + t.getKey() + " positionsWithLines=" + t.getValue()[0] + " nodes="
                    + t.getValue()[1] + " ms=" + t.getValue()[2] + " exhausted=" + t.getValue()[3] + " of " + ps.size());
        }
    }
}
