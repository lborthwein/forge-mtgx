package forge.ai.simulation;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.ChoiceScorer;
import forge.bench.rl.RlCandidates;
import forge.bench.rl.RlKnowledge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * sdcc-gycast-1114 (solver key planGraveCasts): a permanent that lets its controller cast cards from the graveyard is
 * read as a grant (shape of the script, then one sandbox per card of ours), and the planner screens plan casts from the
 * graveyard with the graveyard as a counted resource. The frames are card facts used as tests only (Underworld Breach
 * gives each nonland card in the graveyard escape for its mana cost plus three other graveyard cards and is sacrificed
 * at the end step; Brain Freeze mills three cards per spell cast this turn; Lion's Eye Diamond costs nothing and is
 * sacrificed with a discard of the hand for three mana). No card names in the code under test. The owner's positions
 * are guard-build replays NC06 (turn 4) and NC05 (turn 9), from their transcripts and deck lists.
 */
public class GraveCastTest extends forge.ai.AITest {

    static final String BREACH = "Underworld Breach";
    static final String FREEZE = "Brain Freeze";
    static final String LED = "Lion's Eye Diamond";
    static final String STOCK = "Grizzly Bears;Grizzly Bears;Grizzly Bears;Grizzly Bears;Grizzly Bears;Grizzly Bears;"
            + "Grizzly Bears;Grizzly Bears;Brainstorm";
    static final String LIB40 = "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;"
            + "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;"
            + "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;"
            + "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island";

    static ForcedWinSolver solver(final Player me, final boolean on) {
        // the live string's solver keys (shipA-guard.spec, plans.solverCfg), without the clocks
        final Map<String, String> p = new java.util.HashMap<>();
        for (String k : new String[] {"cycles", "hideOwnLibrary", "breakOnCounter", "engineAfterPlanFail", "engineLongLoops",
                "engineLoopBisect", "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen",
                "engineRerun", "engineTriggerTargets", "engineVerify", "followPay", "historyLki", "landEntersProbe", "loopMarginal",
                "paySearch", "planDiscardWall", "probeSettle", "readerPerGame", "sourceColorsLive", "sourceLeaves", "sourceOutput",
                "stackHistory", "tutorCast", "unknownWorlds", "verifyReplan", "passBlockers", "planLoops", "engineModeChoice"}) {
            p.put(k, "true");
        }
        p.put("planOrder", "7");
        if (on) {
            p.put("planGraveCasts", "true");
        }
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(p::get);
        c.sharedReader = false;
        c.honestProbes = false;
        return ForcedWinSolver.withReader(me, c, ForcedWinSolver.newSharedReader());
    }

    static Game game(final String hand, final String battlefield, final String graveyard, final String library,
            final String oppBattlefield, final int life, final int oppLife) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, life, "", oppBattlefield, oppLife, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library)
                .replace("humangraveyard=", "humangraveyard=" + graveyard);
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    static ForcedWinModel.ACard model(final ForcedWinSolver s, final Game g, final String name) {
        final Player me = g.getPlayers().get(0);
        for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Library}) {
            for (Card c : me.getCardsIn(z)) {
                if (c.getName().equals(name)) {
                    return s.readerForTest().card(g, me, c);
                }
            }
        }
        throw new AssertionError("no " + name);
    }

    static ForcedWinSolver.Reach reach(final ForcedWinSolver s, final Game g, final int nodes) {
        final ForcedWinSolver.ReachOpts o = new ForcedWinSolver.ReachOpts();
        o.nodes = nodes;
        o.ms = -1L;
        o.nameOnePass = true;
        final ForcedWinSolver.Reach r = s.reach(g, s.knownTop(g), o);
        Assert.assertFalse(r.failed);
        return r;
    }

    static ForcedWinSolver.Goldfish goldfish(final ForcedWinSolver s, final Game g, final int samples) {
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.ms = -1L;
        o.samples = samples;
        o.seed = 11L;
        o.maxNodes = samples > 0 ? 4L * o.nodes * samples : 0;
        final ForcedWinSolver.Goldfish gf = s.goldfish(g, s.knownTop(g), o);
        Assert.assertFalse(gf.failed);
        return gf;
    }

    // ------------------------------------------------------------------ the reader

    @Test
    public void theReaderLearnsTheGrantOnlyWithTheKey() {
        final Game g = game(BREACH + ";" + FREEZE + ";" + LED, "Volcanic Island;Volcanic Island;Mountain", STOCK, LIB40, "", 20, 20);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false);
        Assert.assertFalse(off.config().planGraveCasts, "off by default");
        Assert.assertNull(model(off, g, BREACH).grave, "key off: the card's model is as before");
        Assert.assertNotNull(off.readPosition(g, off.knownTop(g)));
        Assert.assertNull(model(off, g, BREACH).grave);
        final long probes0 = ForcedWinModel.Reader.GRAVE_PROBES.get();
        final ForcedWinSolver on = solver(me, true);
        final ForcedWinModel.Grave gr = model(on, g, BREACH).grave;
        Assert.assertNotNull(gr, "a permanent whose static ability gives graveyard cards a way to be cast");
        Assert.assertTrue(gr.endStep, "sacrificed at the end step");
        Assert.assertEquals(gr.perTurn, 0);
        Assert.assertTrue(gr.names.isEmpty(), "nothing observed before a position is read");
        final ForcedWinModel.Position pos = on.readPosition(g, on.knownTop(g));
        System.out.println("GYCAST grant " + gr + " not=" + gr.tried + " probes=" + (ForcedWinModel.Reader.GRAVE_PROBES.get() - probes0));
        Assert.assertEquals(pos.graveyard.size(), 9, "the graveyard's cards are read when a grant is in the position");
        for (String n : new String[] {FREEZE, "Brainstorm"}) {
            Assert.assertNotNull(gr.names.get(n), n);
            Assert.assertEquals(gr.names.get(n).exile, 3, n + ": three other graveyard cards");
            Assert.assertEquals(gr.names.get(n).to, ForcedWinModel.Abil.SELF_GRAVEYARD, n + ": back in the graveyard");
        }
        Assert.assertNotNull(gr.names.get(LED), "a permanent is cast from the graveyard too");
        Assert.assertEquals(gr.names.get(LED).to, 0, "and enters the battlefield");
        Assert.assertEquals(gr.names.get(LED).exile, 3);
        Assert.assertFalse(gr.names.containsKey("Volcanic Island"), "lands are not cast");
        final long probes1 = ForcedWinModel.Reader.GRAVE_PROBES.get();
        on.readPosition(g, on.knownTop(g));
        solver(me, true).readPosition(g, on.knownTop(g));
        Assert.assertEquals(ForcedWinModel.Reader.GRAVE_PROBES.get(), probes1, "one sandbox per card name and game, shared by the seat's readers");
        // the other models of a gc reader are the plain reader's
        Assert.assertEquals(ForcedWinModel.signature(model(on, g, FREEZE)), ForcedWinModel.signature(model(off, g, FREEZE)));
        Assert.assertNull(model(on, g, FREEZE).grave);
        Assert.assertNull(model(on, g, LED).grave);
    }

    @Test
    public void otherShapes() {
        // a per-turn limit and a filter (permanents of mana value two or less), no end-step sacrifice; a land-only grant;
        // a sorcery with the same static text is not a permanent's grant
        final Game g = game("Lurrus of the Dream-Den;Crucible of Worlds;Yawgmoth's Will;" + LED,
                "Scrubland;Scrubland;Scrubland", STOCK, LIB40, "", 20, 20);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver on = solver(me, true);
        on.readPosition(g, on.knownTop(g));
        final ForcedWinModel.Grave cat = model(on, g, "Lurrus of the Dream-Den").grave;
        Assert.assertNotNull(cat);
        System.out.println("GYCAST other " + cat + " not=" + cat.tried + " | " + model(on, g, "Crucible of Worlds").grave);
        Assert.assertEquals(cat.perTurn, 1);
        Assert.assertFalse(cat.endStep);
        Assert.assertNotNull(cat.names.get(LED));
        Assert.assertEquals(cat.names.get(LED).exile, 0);
        Assert.assertFalse(cat.names.containsKey("Brainstorm"), "not a permanent: the engine does not offer it");
        // a sorcery that opens the graveyard for the turn: a spell grant; its casts end in exile and cost no cards
        final ForcedWinModel.Grave will = model(on, g, "Yawgmoth's Will").grave;
        Assert.assertNotNull(will, "a spell whose effect lets graveyard cards be played");
        System.out.println("GYCAST spell grant " + will + " not=" + will.tried);
        Assert.assertTrue(will.spell);
        Assert.assertNotNull(will.names.get("Brainstorm"));
        Assert.assertEquals(will.names.get("Brainstorm").exile, 0);
        Assert.assertEquals(will.names.get("Brainstorm").to, ForcedWinModel.Abil.SELF_EXILE);
        Assert.assertNotNull(will.names.get(LED));
        final ForcedWinModel.Grave lands = model(on, g, "Crucible of Worlds").grave;
        Assert.assertTrue(lands == null || lands.names.isEmpty(), "a grant of land plays casts nothing");
        Assert.assertNull(model(on, g, "Grizzly Bears").grave);
    }

    // ------------------------------------------------------------------ the planner

    /** The probe of the diagnosis: the engine, a mill-yourself storm finisher and a zero-cost mana artifact, a stocked graveyard. */
    @Test
    public void theProbeFindsTheKill() {
        final String lands = "Volcanic Island;Volcanic Island;Mountain";
        final Game g = game(BREACH + ";" + FREEZE + ";" + LED, lands, STOCK, LIB40, "", 20, 20);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false), on = solver(me, true);
        Assert.assertEquals(reach(off, g, 2000).p, 0.0, 1e-9, "key off: the engine is a spell with no effect");
        Assert.assertEquals(goldfish(off, g, 0).g, 0.0, 1e-9);
        for (int nodes : new int[] {1000, 2000, 30000}) {
            final ForcedWinSolver.Screen x = solver(me, true).screenAt(g, on.knownTop(g), null, nodes, -1L, true, true);
            System.out.println("GYCAST probe screen cap=" + nodes + " lines=" + x.lines + " nodes=" + x.nodes + " best=" + x.best);
        }
        final ForcedWinSolver.Screen sc = on.screenAt(g, on.knownTop(g), null, 2000, -1L, true, true);
        System.out.println("GYCAST probe screen lines=" + sc.lines + " nodes=" + sc.nodes + " exhausted=" + sc.exhausted + " best=" + sc.best);
        final ForcedWinSolver.Reach r = reach(on, g, 2000);
        final ForcedWinSolver.Goldfish gf = goldfish(on, g, 0);
        System.out.println("GYCAST probe reach=" + r.p + " direct=" + r.direct + " goldfish=" + gf.g + " nodes=" + gf.nodes);
        Assert.assertTrue(sc.lines > 0, "a line through casts from the graveyard");
        Assert.assertEquals(r.p, 1.0, 1e-9);
        Assert.assertTrue(gf.g > 0);
        Assert.assertTrue(on.hasEnder(g));
        // without the engine the same hand has nothing; without fuel neither
        final Game noEngine = game(FREEZE + ";" + LED, lands, STOCK, LIB40, "", 20, 20);
        Assert.assertEquals(reach(solver(noEngine.getPlayers().get(0), true), noEngine, 30000).p, 0.0, 1e-9, "no engine");
        final Game noFuel = game(BREACH + ";" + FREEZE, lands, "", "Island;Island", "", 20, 20);
        final ForcedWinSolver.Screen bare = solver(noFuel.getPlayers().get(0), true).screenAt(noFuel,
                List.of(), null, 30000, -1L, true, false);
        Assert.assertEquals(bare.lines, 0, "an empty graveyard and two library cards: nothing to exile");
    }

    /**
     * A spell grant (the owner's G17 class): three rituals already cast from hand this turn, the sorcery that opens the
     * graveyard in hand with a storm drain. Key on, the rituals are cast again from the graveyard and the drain is lethal.
     */
    @Test
    public void aSpellGrantReplaysTheGraveyard() {
        final String hand = "Yawgmoth's Will;Tendrils of Agony";
        final String lands = "Swamp;Swamp;Swamp;Swamp;Swamp";
        final String gy = "Dark Ritual;Dark Ritual;Dark Ritual;Cabal Ritual;Grizzly Bears";
        final Game g = game(hand, lands, gy, LIB40, "", 20, 12);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver off = solver(me, false), on = solver(me, true);
        final ForcedWinSolver.Screen a = off.screenAt(g, off.knownTop(g), null, 2000, -1L, true, true);
        final ForcedWinSolver.Screen b = on.screenAt(g, on.knownTop(g), null, 2000, -1L, true, true);
        System.out.println("GYCAST spell off lines=" + a.lines + " nodes=" + a.nodes + " | on lines=" + b.lines + " nodes=" + b.nodes
                + " best=" + b.best);
        Assert.assertEquals(a.lines, 0, "key off: five lands do not make a lethal drain (opponent at 12)");
        Assert.assertTrue(b.lines > 0, "key on: the graveyard's rituals are cast again");
        Assert.assertEquals(reach(on, g, 2000).p, 1.0, 1e-9);
    }

    /** The key's cost where it applies and where it does not: planner nodes of one screen, key off and on. */
    @Test
    public void nodeCounts() {
        final String lands = "Volcanic Island;Volcanic Island;Mountain";
        final String[][] frames = {
            {"engine in hand", BREACH + ";" + FREEZE + ";" + LED, lands, STOCK},
            {"no grant in the position", "Brainstorm;" + FREEZE + ";" + LED, lands, STOCK},
            {"engine in hand, no finisher", BREACH + ";Brainstorm;Ponder", lands, STOCK}};
        for (String[] f : frames) {
            final Game g = game(f[1], f[2], f[3], LIB40, "", 20, 20);
            final Player me = g.getPlayers().get(0);
            final ForcedWinSolver off = solver(me, false), on = solver(me, true);
            final ForcedWinSolver.Screen a = off.screenAt(g, off.knownTop(g), null, 30000, -1L, true, true);
            final ForcedWinSolver.Screen b = on.screenAt(g, on.knownTop(g), null, 30000, -1L, true, true);
            System.out.println("GYCAST nodes " + f[0] + ": off " + a.nodes + (a.exhausted ? "(cut)" : "") + " lines " + a.lines
                    + " | on " + b.nodes + (b.exhausted ? "(cut)" : "") + " lines " + b.lines);
            if (f[0].startsWith("no grant")) {
                Assert.assertEquals(b.nodes, a.nodes, "no grant in the position: the search is the key-off search");
                Assert.assertEquals(b.lines, a.lines);
            }
        }
    }

    // ------------------------------------------------------------------ NC06, turn 4

    /** NC06's library at turn 4: the list (cur-breach-storm-0019) without the nine cards seen by then. */
    static final String NC06_LIB = "Brain Freeze;Brazen Borrower;Forest;Forest;Forest;Forest;Frantic Search;Fyndhorn Elves;"
            + "Glen Elendra Archmage;High Tide;Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;"
            + "Jace, the Mind Sculptor;Mana Vault;Old One Eye;Pentad Prism;Prismatic Vista;Time Spiral;Tireless Tracker;"
            + "Undermountain Adventurer;Urza, Lord High Artificer;Vendilion Clique;Wild Growth";

    /**
     * NC06, the breach-storm seat's turn 4 (main phase 1): hand Treachery, Island, the engine; an Island and Mox Diamond
     * in play; four cards in the graveyard. The recorded play cast the engine and one Brainstorm from the graveyard; the
     * engine was sacrificed at the end step. Holding it: the screens' potential with it in hand. Spent: the position the
     * recorded play left (the engine and the Brainstorm in the graveyard, three cards exiled, one more card in hand).
     */
    @Test
    public void nc06AsRecordedIsMeasured() {
        final String gy = "Llanowar Elves;Mountain;Elvish Mystic;Brainstorm";
        final Game hold = game("Treachery;Island;" + BREACH, "Island;Mox Diamond", gy, NC06_LIB, "Thieving Skydiver", 20, 16);
        final Game spent = game("Treachery;Island", "Island;Island;Mox Diamond", "Brainstorm;" + BREACH, NC06_LIB,
                "Thieving Skydiver", 20, 16);
        final double[] g = new double[4];
        int i = 0;
        for (boolean on : new boolean[] {false, true}) {
            for (Game x : new Game[] {hold, spent}) {
                final ForcedWinSolver s = solver(x.getPlayers().get(0), on);
                final ForcedWinSolver.Goldfish gf = goldfish(s, x, 16);
                final ForcedWinSolver.Reach r = reach(s, x, 4000);
                System.out.println("GYCAST NC06 key=" + on + " " + (x == hold ? "hold" : "spent") + " goldfish g=" + gf.g + " p=" + gf.p
                        + " samples=" + gf.samples + " nodes=" + gf.nodes + " cut=" + gf.cut + " reach=" + r.p + " gate="
                        + s.potential(x, s.knownTop(x), new ForcedWinSolver.GoldfishOpts(), 40, true, 12_000L));
                g[i++] = gf.g;
            }
        }
        // Measured 2026-11-14: 0.0 in all four. This list's kill needs mana the width model does not hold (a spell that
        // untaps lands, one that makes lands tap for more): with the grant modelled there is still no line in five turns,
        // so the recorded position itself gets no credit from this key. Asserted: never worse for holding.
        Assert.assertEquals(g[0], g[1], 1e-9, "key off: holding and spending the engine look the same to the screens");
        Assert.assertTrue(g[2] >= g[3] - 1e-9, "key on: holding is never worth less than having spent it: " + g[2] + " vs " + g[3]);
    }

    /**
     * NC06's board and graveyard with a kill the model holds in sight (the storm finisher and a zero-cost mana artifact
     * in hand too): holding the engine has credit; the position the recorded play leaves (the engine spent on one
     * cantrip and sacrificed) has none, and key off neither has.
     */
    @Test
    public void nc06ShapeHoldingTheEngineHasCreditAndSpendingItForACantripDoesNot() {
        final String gy = "Llanowar Elves;Mountain;Elvish Mystic;Brainstorm";
        final String rest = ";" + FREEZE + ";" + LED;
        final Game hold = game("Treachery;Island;" + BREACH + rest, "Island;Mox Diamond", gy, NC06_LIB.replace("Brain Freeze;", ""),
                "Thieving Skydiver", 20, 16);
        final Game spent = game("Treachery;Island" + rest, "Island;Island;Mox Diamond", "Brainstorm;" + BREACH,
                NC06_LIB.replace("Brain Freeze;", ""), "Thieving Skydiver", 20, 16);
        final double offHold = reach(solver(hold.getPlayers().get(0), false), hold, 2000).p;
        final double offSpent = reach(solver(spent.getPlayers().get(0), false), spent, 2000).p;
        final double onHold = reach(solver(hold.getPlayers().get(0), true), hold, 2000).p;
        final double onSpent = reach(solver(spent.getPlayers().get(0), true), spent, 2000).p;
        System.out.println("GYCAST NC06 shape reach: off hold=" + offHold + " spent=" + offSpent + " | on hold=" + onHold + " spent=" + onSpent);
        Assert.assertEquals(offHold, 0.0, 1e-9);
        Assert.assertEquals(offSpent, 0.0, 1e-9);
        Assert.assertTrue(onHold > 0, "key on: holding the engine has credit");
        Assert.assertEquals(onSpent, 0.0, 1e-9, "key on: the engine spent for one cantrip leaves no line");
    }

    // ------------------------------------------------------------------ NC05, turn 9

    /** NC05's library at turn 9 (cur-breach-storm-0035 without the cards seen by then); its top card is the loot's draw. */
    static final String NC05_LIB = "Mountain;Black Lotus;Brain Freeze;Ancient Tomb;Coveted Jewel;Goldspan Dragon;Grim Lavamancer;"
            + "Gush;Island;Island;Kolaghan's Command;Marsh Flats;Memory Lapse;Mountain;Mountain;Mountain;Mox Emerald;Persist;"
            + "Phyrexian Revoker;Reckoner Bankbuster;Snuff Out;Stern Scolding;Swamp;Talisman of Progress";
    static final String NC05_BOARD = "Xander's Lounge;Island;Mountain;Mountain;Young Pyromancer;Palantír of Orthanc;Merfolk Looter";
    static final String NC05_GY = "Ancestral Recall;Firebolt;Frantic Search";

    /**
     * One scored discard at NC05's position. The recorded ask was the loot spell's (draw two, discard two); the card
     * scorer does not score a discard of two cards at once (unsupported "simultaneous"), so the ask here is a
     * draw-one-discard-one from a creature's tap ability on the same hand, board and graveyard, the loot spell already
     * in the graveyard. The policy discards the engine, as in the recorded game.
     */
    private static ReachDiscardsTest.Ask nc05(final boolean on, final String hand, final String library) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(hand, NC05_BOARD, 12, "",
                "Wall of Roots;Esika's Chariot;Questing Beast", 16, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library)
                .replace("humangraveyard=", "humangraveyard=" + NC05_GY).replace("activephase=MAIN1", "activephase=MAIN2"));
        final Player me = g.getPlayers().get(0);
        final ChoiceScorer sc = ReachDiscardsTest.scorer(true, 4, on ? new String[] {"planGraveCasts"} : null);
        final RlKnowledge know = ReachDiscardsTest.know(g);
        final ReachDiscardsTest.Ask[] out = new ReachDiscardsTest.Ask[1];
        me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa,
                    final CardCollection validCards, final int min, final int max, final CardCollectionView visible) {
                final RlCandidates.Menu m = RlCandidates.build(g, me, "chooseCardsToDiscardFrom", "discardFrom",
                        ReachDiscardsTest.body(min, max), new Object[] {validCards, sa, p});
                final short[] greedy = ReachDiscardsTest.steps(validCards, BREACH);
                sc.setAskPlayer(p);
                out[0] = ReachDiscardsTest.ask(sc, g, me, m, greedy, know, sa, validCards, null);
                final CardCollection live = new CardCollection();
                live.add(ReachDiscardsTest.find(me, ZoneType.Hand, BREACH));
                return live;
            }
        });
        SpellAbility loot = null;
        for (SpellAbility sa : ReachDiscardsTest.find(me, ZoneType.Battlefield, "Merfolk Looter").getSpellAbilities()) {
            if (sa.getApi() == forge.game.ability.ApiType.Draw) {
                loot = sa;
            }
        }
        Assert.assertNotNull(loot);
        loot.setActivatingPlayer(me);
        Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(loot), "activate the loot");
        MyRandom.setRandom(new Random(42));
        g.getStack().resolveStack();
        Assert.assertNotNull(out[0], "the discard was asked");
        return out[0];
    }

    /**
     * NC05's real ask: the loot spell itself is cast (draw two, discard two) with the token maker in play, its trigger put
     * on the stack and resolved first, as in a game. Measured and printed: whether the card scorer scores a discard of
     * two cards at once ({@code seqs}), and what it answers, key off and on.
     */
    @Test
    public void nc05RealAskDiscardTwoIsMeasured() {
        for (boolean on : new boolean[] {false, true}) {
            final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(
                    "Swamp;Wheel of Fortune;Island;" + BREACH + ";Island;Frantic Search",
                    "Xander's Lounge;Island;Mountain;Mountain;Young Pyromancer;Palantír of Orthanc", 12, "",
                    "Wall of Roots;Esika's Chariot;Questing Beast", 16, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + NC05_LIB)
                    .replace("humangraveyard=", "humangraveyard=Ancestral Recall;Firebolt"));
            final Player me = g.getPlayers().get(0);
            final ChoiceScorer sc = ReachDiscardsTest.scorer(true, 4, on ? new String[] {"planGraveCasts"} : null);
            final RlKnowledge know = ReachDiscardsTest.know(g);
            final ReachDiscardsTest.Ask[] out = new ReachDiscardsTest.Ask[1];
            me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
                @Override
                public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa,
                        final CardCollection validCards, final int min, final int max, final CardCollectionView visible) {
                    final RlCandidates.Menu m = RlCandidates.build(g, me, "chooseCardsToDiscardFrom", "discardFrom",
                            ReachDiscardsTest.body(min, max), new Object[] {validCards, sa, p});
                    final short[] greedy = ReachDiscardsTest.steps(validCards, "Mountain", BREACH);
                    sc.setAskPlayer(p);
                    out[0] = ReachDiscardsTest.ask(sc, g, me, m, greedy, know, sa, validCards, null);
                    final CardCollection live = new CardCollection();
                    live.add(ReachDiscardsTest.find(me, ZoneType.Hand, "Mountain"));
                    live.add(ReachDiscardsTest.find(me, ZoneType.Hand, BREACH));
                    return live;
                }
            });
            SpellAbility loot = null;
            for (SpellAbility sa : ReachDiscardsTest.find(me, ZoneType.Hand, "Frantic Search").getSpellAbilities()) {
                if (sa.isSpell()) {
                    loot = sa;
                }
            }
            Assert.assertNotNull(loot);
            loot.setActivatingPlayer(me);
            Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(loot), "cast the loot spell");
            MyRandom.setRandom(new Random(42));
            for (int i = 0; i < 6 && (g.getStack().addAllTriggeredAbilitiesToStack() || !g.getStack().isEmpty()); i++) {
                g.getStack().resolveStack();
            }
            Assert.assertNotNull(out[0], "the discard was asked");
            System.out.println("GYCAST NC05 real ask key=" + on + " unsupported=" + out[0].s.unsupported + " options="
                    + out[0].s.options.size() + " alt=" + out[0].altNames);
        }
    }

    /** The best reach among the options that keep the engine (their label, the discarded card, is another card), or discard it. */
    private static double bestReach(final ReachDiscardsTest.Ask a, final boolean engineKept) {
        double best = -1;
        for (ChoiceScorer.Option o : a.s.options) {
            if (o.label != null && o.label.contains(BREACH) != engineKept) {
                best = Math.max(best, ReachDiscardsTest.reachOf(o));
            }
        }
        return best;
    }

    /**
     * NC05, turn 9, with fuel in sight: the engine, the storm finisher and a zero-cost mana artifact in hand at the
     * loot's discard. Key off, no option's reach tells keeping the engine from discarding it. Key on, the options that
     * keep it have reach and the answer no longer discards it.
     */
    @Test
    public void nc05TheLootKeepsTheEngineWhenFuelExists() {
        final String hand = "Swamp;Wheel of Fortune;" + BREACH + ";" + FREEZE + ";Black Lotus";
        final ReachDiscardsTest.Ask off = nc05(false, hand, NC05_LIB.replace("Black Lotus;Brain Freeze;", ""));
        Assert.assertNull(off.s.unsupported, off.dump());
        Assert.assertEquals(bestReach(off, true), bestReach(off, false), 1e-9, "key off: no reach for keeping the engine: " + off.dump());
        final ReachDiscardsTest.Ask on = nc05(true, hand, NC05_LIB.replace("Black Lotus;Brain Freeze;", ""));
        Assert.assertNull(on.s.unsupported, on.dump());
        Assert.assertTrue(bestReach(on, true) > 0, "key on: keeping the engine has reach: " + on.dump());
        Assert.assertTrue(bestReach(on, true) > bestReach(on, false), on.dump());
        Assert.assertNotNull(on.alt, "the answer moves: " + on.dump());
        Assert.assertFalse(on.altNames.contains(BREACH), "the engine is kept: " + on.dump());
    }

    /** NC05's recorded hand at the discard (no finisher, no fast mana in hand): measured and printed, key off and on. */
    @Test
    public void nc05AsRecordedIsMeasured() {
        final String hand = "Swamp;Wheel of Fortune;Island;" + BREACH + ";Island";
        final ReachDiscardsTest.Ask off = nc05(false, hand, NC05_LIB), on = nc05(true, hand, NC05_LIB);
        System.out.println("GYCAST NC05 recorded: key off keep=" + bestReach(off, true) + " discard=" + bestReach(off, false)
                + " | key on keep=" + bestReach(on, true) + " discard=" + bestReach(on, false) + " alt=" + on.altNames);
        Assert.assertTrue(bestReach(on, true) >= bestReach(on, false) - 1e-9, "keeping the engine is never the worse reach: " + on.dump());
    }

    // ------------------------------------------------------------------ the key

    @Test
    public void theLiveSeatAcceptsTheKeyAndItIsOffWhenAbsent() {
        final java.util.function.Function<String, String> none = k -> null;
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(none).planGraveCasts);
        Assert.assertTrue(forge.bench.rl.RlLiveSeat.Spec.LIVE_SOLVER_KEYS.contains("planGraveCasts"));
        Assert.assertFalse(new ForcedWinPlanner.Limits().graveCasts);
        Assert.assertFalse(new ForcedWinModel.Reader().graveCasts);
    }
}
