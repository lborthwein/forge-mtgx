package forge.ai.simulation;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * sdcc-untaps-1114 (solver key planUntaps): the reader observes that a spell untaps lands or makes lands tap for more
 * this turn, and the planner's mana counts it. The frames are card facts used as tests only (High Tide: Islands tap
 * for one more blue this turn; Frantic Search: draw two, discard two, untap up to three lands; Time Spiral untaps up
 * to six; Brain Freeze mills three per spell cast this turn). No card names in the code under test.
 */
public class UntapTest extends forge.ai.AITest {

    static ForcedWinSolver solver(final Player me, final boolean untaps, final boolean grave) {
        return solver(me, untaps, grave, null);
    }

    static ForcedWinSolver solver(final Player me, final boolean untaps, final boolean grave, final String extraKey) {
        final Map<String, String> p = new java.util.HashMap<>();
        if (extraKey != null) {
            p.put(extraKey, "true");
        }
        for (String k : new String[] {"cycles", "hideOwnLibrary", "breakOnCounter", "engineAfterPlanFail", "engineLongLoops",
                "engineLoopBisect", "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen",
                "engineRerun", "engineTriggerTargets", "engineVerify", "followPay", "historyLki", "landEntersProbe", "loopMarginal",
                "paySearch", "planDiscardWall", "probeSettle", "readerPerGame", "sourceColorsLive", "sourceLeaves", "sourceOutput",
                "stackHistory", "tutorCast", "unknownWorlds", "verifyReplan", "passBlockers", "planLoops", "engineModeChoice"}) {
            p.put(k, "true");
        }
        p.put("planOrder", "7");
        if (untaps) {
            p.put("planUntaps", "true");
        }
        if (grave) {
            p.put("planGraveCasts", "true");
        }
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(p::get);
        c.sharedReader = false;
        c.honestProbes = false;
        return ForcedWinSolver.withReader(me, c, ForcedWinSolver.newSharedReader());
    }

    static Game game(final String hand, final String battlefield, final String graveyard, final String library, final String oppLibrary) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library)
                .replace("humangraveyard=", "humangraveyard=" + graveyard)
                .replace("ailibrary=" + ForcedWinSolverTest.LIB20, "ailibrary=" + oppLibrary);
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    private static ForcedWinModel.Eff eff(final ForcedWinModel.ACard a, final ForcedWinModel.EffKind k) {
        for (ForcedWinModel.Abil ab : a.fromHand) {
            for (ForcedWinModel.Eff e : ab.effects) {
                if (e.kind == k) {
                    return e;
                }
            }
        }
        return null;
    }

    static final String NINE = "Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest";

    @Test
    public void theReaderObservesUntapsAndBoostsOnlyWithTheKey() {
        final Game g = game("High Tide;Frantic Search;Time Spiral;Brainstorm", "Island;Island;Island;Island;Mountain", "",
                GraveCastTest.LIB40, NINE);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false, false), on = solver(me, true, false);
        Assert.assertFalse(off.config().planUntaps);
        for (String n : new String[] {"High Tide", "Frantic Search", "Time Spiral"}) {
            Assert.assertNull(eff(GraveCastTest.model(off, g, n), ForcedWinModel.EffKind.UNTAP_LANDS), n);
            Assert.assertNull(eff(GraveCastTest.model(off, g, n), ForcedWinModel.EffKind.LAND_BOOST), n);
            System.out.println("UNTAP model " + n + " " + GraveCastTest.model(on, g, n).fromHand);
        }
        final ForcedWinModel.Eff fs = eff(GraveCastTest.model(on, g, "Frantic Search"), ForcedWinModel.EffKind.UNTAP_LANDS);
        Assert.assertNotNull(fs, "untap up to three lands");
        Assert.assertEquals(fs.n, 3);
        final ForcedWinModel.Eff ts = eff(GraveCastTest.model(on, g, "Time Spiral"), ForcedWinModel.EffKind.UNTAP_LANDS);
        Assert.assertNotNull(ts);
        Assert.assertEquals(ts.n, 6);
        final ForcedWinModel.Eff ht = eff(GraveCastTest.model(on, g, "High Tide"), ForcedWinModel.EffKind.LAND_BOOST);
        Assert.assertNotNull(ht, "Islands tap for one more");
        Assert.assertEquals(ht.n, 1);
        Assert.assertEquals(ht.colors, 1 << ForcedWinModel.U);
        Assert.assertTrue(ht.names.contains("Island") && !ht.names.contains("Mountain"), String.valueOf(ht.names));
        Assert.assertEquals(ForcedWinModel.signature(GraveCastTest.model(on, g, "Brainstorm")),
                ForcedWinModel.signature(GraveCastTest.model(off, g, "Brainstorm")), "a card with neither shape is the plain model");
    }

    /**
     * Four Islands, the boost, the untap spell and a storm mill against a nine-card library: the boost first, then the
     * untap spell, then the mill as the third spell. Key off there is no line; key on there is one, and it casts the
     * boost before the untap spell.
     */
    @Test
    public void theBoostBeforeTheUntapMakesTheMana() {
        final Game g = game("High Tide;Frantic Search;Brain Freeze;Mountain", "Island;Island;Island;Island", "", GraveCastTest.LIB40, NINE);
        // (a fourth hand card: with two other cards in hand the probe reads the loot's discard as "the whole hand", a
        // first-sight model defect that exists without this key)
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false, false), on = solver(me, true, false);
        final ForcedWinSolver.Screen a = off.screenAt(g, off.knownTop(g), null, 2000, -1L, true, true);
        final ForcedWinSolver.Screen b = on.screenAt(g, on.knownTop(g), null, 2000, -1L, true, true);
        System.out.println("UNTAP storm pos " + on.readPosition(g, on.knownTop(g)).describe());
        System.out.println("UNTAP storm off lines=" + a.lines + " nodes=" + a.nodes + " | on lines=" + b.lines + " nodes=" + b.nodes
                + " best=" + b.best);
        Assert.assertEquals(a.lines, 0);
        Assert.assertTrue(b.lines > 0, "the boosted, untapped lands pay for the third spell");
        Assert.assertTrue(b.best.indexOf("High Tide") >= 0 && b.best.indexOf("High Tide") < b.best.indexOf("Frantic Search"), b.best);
        // no boost or untap card in the position: the same search
        final Game plain = game("Brainstorm;Brain Freeze", "Island;Island;Island;Island", "", GraveCastTest.LIB40, NINE);
        final Player pm = plain.getPlayers().get(0);
        final ForcedWinSolver.Screen p0 = solver(pm, false, false).screenAt(plain, java.util.List.of(), null, 2000, -1L, true, true);
        final ForcedWinSolver.Screen p1 = solver(pm, true, false).screenAt(plain, java.util.List.of(), null, 2000, -1L, true, true);
        System.out.println("UNTAP nodes plain: off " + p0.nodes + " on " + p1.nodes);
        Assert.assertEquals(p1.nodes, p0.nodes);
        Assert.assertEquals(p1.lines, p0.lines);
    }

    /** NC06's list and turn-4 position (see GraveCastTest), both keys: measured and printed; holding is never worth less. */
    @Test
    public void nc06WithBothKeysIsMeasured() {
        final String gy = "Llanowar Elves;Mountain;Elvish Mystic;Brainstorm";
        final Game hold = GraveCastTest.game("Treachery;Island;" + GraveCastTest.BREACH, "Island;Mox Diamond", gy, GraveCastTest.NC06_LIB,
                "Thieving Skydiver", 20, 16);
        final Game spent = GraveCastTest.game("Treachery;Island", "Island;Island;Mox Diamond", "Brainstorm;" + GraveCastTest.BREACH,
                GraveCastTest.NC06_LIB, "Thieving Skydiver", 20, 16);
        final double[] g = new double[2];
        int i = 0;
        for (Game x : new Game[] {hold, spent}) {
            final ForcedWinSolver s = solver(x.getPlayers().get(0), true, true);
            final ForcedWinSolver.Goldfish gf = GraveCastTest.goldfish(s, x, 16);
            System.out.println("UNTAP NC06 both keys " + (x == hold ? "hold" : "spent") + " goldfish g=" + gf.g + " p=" + gf.p
                    + " nodes=" + gf.nodes + " cut=" + gf.cut + " reach=" + GraveCastTest.reach(s, x, 2000).p);
            g[i++] = gf.g;
        }
        Assert.assertTrue(g[0] >= g[1] - 1e-9, g[0] + " vs " + g[1]);
    }

    @Test
    public void theKeyIsOffWhenAbsent() {
        final java.util.function.Function<String, String> none = k -> null;
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(none).planUntaps);
        Assert.assertTrue(forge.bench.rl.RlLiveSeat.Spec.LIVE_SOLVER_KEYS.contains("planUntaps"));
        Assert.assertFalse(new ForcedWinModel.Reader().planUntaps);
    }

    // ------------------------------------------------------------------ the follower binds the untapped lands

    /**
     * The root plan, verified by play-outs: two Islands and a colourless land untapped, three more colourless lands
     * tapped. The boost, then the untap spell paid with the second Island and the colourless land, then the storm mill,
     * which needs blue: at least one Island must be among the three lands untapped. The plan records the lands it
     * untapped on the step and the follower answers the engine's "untap up to three" with them. Control run:
     * -Dforge.ai.forcedWin.untapBind=false leaves the choice to Forge AI (printed, not asserted).
     */
    @Test
    public void aFollowedLineUntapsThePlannedLands() {
        final Game g = game("High Tide;Frantic Search;Brain Freeze;Mountain", "Wastes|Tapped;Wastes|Tapped;Wastes|Tapped;Wastes|Tapped;Wastes;Island;Island",
                "", GraveCastTest.LIB40, NINE);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(OwnerMisplaysTest.shipSolverCfg("planUntaps=true")));
        final int answered = ForcedWinSolver.UNTAP_ANSWERS.get();
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : s.propose(g)) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        final boolean bind = !"false".equals(System.getProperty("forge.ai.forcedWin.untapBind"));
        final int answers = ForcedWinSolver.UNTAP_ANSWERS.get() - answered;
        System.out.println("UNTAP bind=" + bind + " untapAnswers=" + answers + " " + (best == null ? "no line" : "pUnopposed=" + best.pUnopposed + " pBelief=" + best.pBelief
                + " worlds=" + best.worldsRun + " | " + best.text) + " || " + s.summary());
        if (!bind) {
            Assert.assertEquals(answers, 0);
        }
        if (bind) {
            Assert.assertNotNull(best, "a line");
            Assert.assertTrue(answers > 0, "the play-outs' untap asks were answered by the line");
            Assert.assertTrue(best.pUnopposed >= 1.0, "the play-outs untap the planned lands: " + best.pUnopposed);
            Assert.assertTrue(String.valueOf(best.text).contains(" untap["), "the untap step records its lands: " + best.text);
        }
    }

    // ------------------------------------------------------------------ probeDiscardCount

    /**
     * A "draw two, discard two" spell first seen with two other cards in hand: key off the probe reads the discard as
     * the whole hand (the live head's defect); with probeDiscardCount it is a discard of two. A spell that discards the
     * hand before it draws has the same model with and without the key.
     */
    @Test
    public void aLootSeenWithTwoOtherCardsDiscardsTwoWithTheKey() {
        final Game g = game("Frantic Search;Island;Mountain", "Island;Island;Island;Island", "", GraveCastTest.LIB40, NINE);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false, false), on = solver(me, false, false, "probeDiscardCount");
        Assert.assertFalse(off.config().probeDiscardCount);
        Assert.assertTrue(forge.bench.rl.RlLiveSeat.Spec.LIVE_SOLVER_KEYS.contains("probeDiscardCount"));
        final ForcedWinModel.Eff a = eff(GraveCastTest.model(off, g, "Frantic Search"), ForcedWinModel.EffKind.DISCARD);
        final ForcedWinModel.Eff b = eff(GraveCastTest.model(on, g, "Frantic Search"), ForcedWinModel.EffKind.DISCARD);
        System.out.println("UNTAP discard off n=" + (a == null ? null : a.n) + " on n=" + (b == null ? null : b.n));
        Assert.assertNotNull(a);
        Assert.assertNotNull(b);
        Assert.assertEquals(a.n, ForcedWinModel.ALL, "the live head reads the whole hand");
        Assert.assertEquals(b.n, 2);
        final Game w = game("Wheel of Fortune;Island;Mountain", "Mountain;Mountain;Mountain;Island", "", GraveCastTest.LIB40, NINE);
        final Player wm = w.getPlayers().get(0);
        Assert.assertEquals(ForcedWinModel.signature(GraveCastTest.model(solver(wm, false, false, "probeDiscardCount"), w, "Wheel of Fortune")),
                ForcedWinModel.signature(GraveCastTest.model(solver(wm, false, false), w, "Wheel of Fortune")), "discard first, then draw: unchanged");
    }

    // ------------------------------------------------------------------ G21 (game C232, seat 0, turn 14, first main phase)

    static final String G21_HAND = "Brain Freeze;High Tide;Time Spiral;Frantic Search;Island";
    static final String G21_BF = "Underground Sea;Swamp;Island;Island;Island;Swamp;Raffine's Tower";
    static final String G21_GY = "Prismatic Vista;Polluted Delta;Demonic Tutor;Time Walk;Preordain";
    /** The list (cb-classic-storm-0118) without the 17 cards seen by then. */
    static final String G21_LIB = "Ancestral Recall;Bitterblossom;Chromatic Star;Chrome Mox;Creeping Tar Pit;Dark Confidant;Dark Ritual;"
            + "Force of Will;Imperial Seal;Island;Island;Lotus Petal;Mana Confluence;Mana Drain;Mana Vault;Remand;Rain of Filth;Sol Ring;"
            + "Swamp;Swamp;Tendrils of Agony;Timetwister;Vampiric Tutor";

    static Game g21(final int oppLibrary) {
        final StringBuilder lib = new StringBuilder();
        for (int i = 0; i < oppLibrary; i++) {
            lib.append(i == 0 ? "" : ";").append("Plains");
        }
        final String frame = ForcedWinSolverTest.frame(G21_HAND, G21_BF, 18, "", "Plains;Plains;Forest;Plains", 18, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + G21_LIB)
                .replace("humangraveyard=", "humangraveyard=" + G21_GY)
                .replace("ailibrary=" + ForcedWinSolverTest.LIB20, "ailibrary=" + lib);
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    /**
     * G21 as recorded (opponent at 18 life, 31 cards in library): the models at that position (a dual land with the
     * boosted land type is boosted too), and the sampled goldfish key off and on (measured, printed; never lower on).
     * No kill is provable there through the draw-seven, whose cards the seat does not know.
     */
    @Test
    public void g21TheRecordedPositionIsMeasured() {
        final Game g = g21(31);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false, false), on = solver(me, true, false);
        final ForcedWinModel.Eff ht = eff(GraveCastTest.model(on, g, "High Tide"), ForcedWinModel.EffKind.LAND_BOOST);
        Assert.assertNotNull(ht);
        System.out.println("UNTAP G21 boost names " + ht.names);
        Assert.assertTrue(ht.names.contains("Island") && ht.names.contains("Underground Sea") && !ht.names.contains("Swamp"),
                String.valueOf(ht.names));
        Assert.assertEquals(eff(GraveCastTest.model(on, g, "Time Spiral"), ForcedWinModel.EffKind.UNTAP_LANDS).n, 6);
        final ForcedWinSolver.Goldfish a = GraveCastTest.goldfish(off, g, 16), b = GraveCastTest.goldfish(on, g, 16);
        System.out.println("UNTAP G21 goldfish off g=" + a.g + " p=" + a.p + " nodes=" + a.nodes + " cut=" + a.cut + " | on g=" + b.g
                + " p=" + b.p + " nodes=" + b.nodes + " cut=" + b.cut);
        Assert.assertTrue(b.g >= a.g - 1e-9, b.g + " vs " + a.g);
    }

    /**
     * G21's hand, lands and graveyard with the kill in sight before the draw-seven (opponent's library nine or twelve
     * cards): the boost is cast before the untap spell, and with twelve cards the draw spell that untaps is cast
     * between the boost and the mill (three spells before it). Key off there is no line.
     */
    @Test
    public void g21ShapeTheBoostComesBeforeTheUntapSpells() {
        for (int lib : new int[] {9, 12}) {
            final Game g = g21(lib);
            final Player me = g.getPlayers().get(0);
            final ForcedWinSolver off = solver(me, false, false), on = solver(me, true, false);
            final ForcedWinSolver.Screen a = off.screenAt(g, off.knownTop(g), null, 4000, -1L, true, true);
            final ForcedWinSolver.Screen b = on.screenAt(g, on.knownTop(g), null, 4000, -1L, true, true);
            System.out.println("UNTAP G21 shape oppLib=" + lib + " off lines=" + a.lines + " nodes=" + a.nodes + " | on lines=" + b.lines
                    + " nodes=" + b.nodes + " best=" + b.best);
            if (lib == 9) {
                Assert.assertEquals(a.lines, 0);
                Assert.assertTrue(b.lines > 0, "boost, untap spell, mill");
                Assert.assertTrue(b.best.indexOf("High Tide") >= 0 && b.best.indexOf("High Tide") < b.best.indexOf("Frantic Search"), b.best);
            }
        }
    }

    // ------------------------------------------------------------------ NC06's list, a later turn

    /**
     * NC06's list (cur-breach-storm-0019) on a later turn with the untap kill in sight: six Islands and the any-colour
     * mana artifact in play, the boost, the untap loot and the storm mill in hand, eight cards in the graveyard, the
     * opponent's library twenty cards. Holding the graveyard engine: boost, engine, loot (untap), loot again from the
     * graveyard, the mill twice. With the engine already spent (in the graveyard) the three spells mill nine: no line.
     * Both keys: holding has credit, the spent position has none; keys off neither has.
     */
    @Test
    public void nc06LaterTurnHoldingTheEngineHasCreditWithTheUntapKillInSight() {
        final String bf = "Island;Island;Island;Island;Island;Island;Mox Diamond";
        final String gy = "Llanowar Elves;Mountain;Elvish Mystic;Brainstorm;Forest;Forest;Wild Growth;Pentad Prism";
        final String lib = GraveCastTest.NC06_LIB.replace("Brain Freeze;", "").replace("Frantic Search;", "").replace("High Tide;", "");
        final String rest = "High Tide;Frantic Search;Brain Freeze;Island";
        final Game hold = GraveCastTest.game(rest + ";" + GraveCastTest.BREACH, bf, gy, lib, "Thieving Skydiver", 20, 16);
        final Game spent = GraveCastTest.game(rest, bf, gy + ";" + GraveCastTest.BREACH, lib, "Thieving Skydiver", 20, 16);
        final double[] r = new double[4];
        final long[] n = new long[4];
        int i = 0;
        for (boolean keys : new boolean[] {false, true}) {
            for (Game x : new Game[] {hold, spent}) {
                final ForcedWinSolver s = solver(x.getPlayers().get(0), keys, keys);
                final ForcedWinSolver.Screen sc = s.screenAt(x, s.knownTop(x), null, 6000, -1L, true, true);
                n[i] = sc.nodes;
                r[i++] = GraveCastTest.reach(s, x, 6000).p;
                System.out.println("UNTAP NC06 later keys=" + keys + " " + (x == hold ? "hold" : "spent") + " screen lines=" + sc.lines + " nodes="
                        + sc.nodes + " best=" + sc.best + " reach=" + r[i - 1]);
            }
        }
        Assert.assertEquals(r[0], 0.0, 1e-9, "keys off, hold");
        Assert.assertEquals(r[1], 0.0, 1e-9, "keys off, spent");
        Assert.assertTrue(r[2] > 0, "both keys: holding the engine has credit");
        Assert.assertEquals(r[3], 0.0, 1e-9, "both keys: the engine spent leaves no line");
    }
}
