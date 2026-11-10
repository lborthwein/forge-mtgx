package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.CardIndex;
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
 * integrate-1110 (choiceScorer {@code reachDiscards}): the reach / goldfish blend answers the seat's own discards. Each
 * test poses the ask in a live game as the bridge would (the menu built by {@link RlCandidates#build}), runs the scorer's
 * own {@code enumerate} and {@code choose} with the pilot arm's choiceScorer block, and gives every option the same leaf
 * value (0.5), so only the reach part can separate them. The frames are card facts used as tests only: with two black
 * sources in play, Doomsday in hand is one land from being cast, so the plain screen has no line and the goldfish (the
 * land-drop relaxation over the next turns) has one. No card names in the code under test.
 */
public class ReachDiscardsTest extends AITest {

    /** The pilot arm's solver config (kit spec ugold4, plans.solverCfg). */
    private static final Map<String, String> SOLVER = new HashMap<>();
    static {
        for (String k : new String[] {"copyBackFace", "cycles", "engineAfterPlanFail", "engineLongLoops", "engineLoopBisect",
            "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen", "engineRerun",
            "engineTriggerTargets", "engineVerify", "followPay", "hideOwnLibrary", "historyLki", "lineUncapped",
            "loopMarginal", "offBattlefieldLimits", "paySearch", "planDiscardWall", "planExpandCap", "readerPerGame",
            "sourceColorsLive", "sourceOutput", "stackAbilityCopies", "stackHistory", "stalePlayerStatics", "staleStatics",
            "turnLimits", "unknownWorlds", "verifyReplan"}) {
            SOLVER.put(k, "true");
        }
        SOLVER.put("budgetMs", "6000");
        SOLVER.put("engineMs", "4000");
        SOLVER.put("evalReserveMs", "1500");
    }

    /**
     * The pilot arm's choiceScorer block (kit spec ugold4). The two per-screen clock caps are opened (a unit test has no
     * work clock, and a wall-clock cap would make the result depend on the machine's load); node caps are the arm's.
     */
    private static final String BLOCK = "{\"budgetMs\":8000,\"families\":\"CARDS\",\"gfActive\":3,\"gfAskNodes\":60000,"
            + "\"gfExtra\":2,\"gfGamma\":0.8,\"gfHorizon\":5,\"gfLambda\":0.6,\"gfLands\":true,\"gfMs\":60000,\"gfNodes\":1500,"
            + "\"gfSamples\":0,\"margin\":0.02,\"reachAlpha\":0.85,\"reachNameNodes\":1000,\"reachOnePass\":true,"
            + "\"reachSig\":true,\"reachSkipCapped\":true,\"screenMs\":60000,\"screenNodes\":2000,\"seqs\":2}";

    /** The kill's cards and enough black lands below them: Doomsday's pile and the land the relaxation grants. */
    private static final String KILL = "Gush;Thassa's Oracle;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp";
    /** The same library with no way to win. */
    private static final String NOKILL = "Grizzly Bears;Grizzly Bears;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp";

    private static CardIndex index() {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : new String[] {"Doomsday", "Gush", "Thassa's Oracle", "Swamp", "Island", "Forest", "Mountain",
            "Grizzly Bears", "Balduvian Bears", "Underground Sea", "Watery Grave", "Bazaar of Baghdad", "Tormenting Voice"}) {
            sb.append(n).append('\t').append(i++).append('\n');
        }
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static ChoiceScorer scorer(final boolean on) {
        final JsonObject o = JsonParser.parseString(BLOCK).getAsJsonObject();
        if (on) {
            o.addProperty("reachDiscards", true);
        }
        final ChoiceScorer.Config cfg = ChoiceScorer.Config.parse(o);
        cfg.solverCfg = ForcedWinSolver.Config.fromProps(SOLVER::get);
        return new ChoiceScorer(cfg, index(), 2, 77L);
    }

    private static RlKnowledge know(final Game g) {
        final RlKnowledge k = new RlKnowledge(g);
        k.v2 = true;
        k.attach();
        return k;
    }

    private static Game game(final String hand, final String battlefield, final String lib, final String phase) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(hand, battlefield, 20, "", "Island", 20,
                "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib)
                .replace("activephase=MAIN1", "activephase=" + phase));
    }

    private static Card find(final Player p, final ZoneType z, final String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no " + name + " in " + z + ": " + ChoiceLineTest.names(p, z));
    }

    /** The menu indices of the first cards of {@code pool} with these names, each card once. */
    private static short[] steps(final Iterable<Card> pool, final String... names) {
        final List<Card> cs = new ArrayList<>();
        for (Card c : pool) {
            cs.add(c);
        }
        final boolean[] used = new boolean[cs.size()];
        final short[] out = new short[names.length];
        for (int k = 0; k < names.length; k++) {
            out[k] = -1;
            for (int i = 0; i < cs.size() && out[k] < 0; i++) {
                if (!used[i] && cs.get(i).getName().equals(names[k])) {
                    used[i] = true;
                    out[k] = (short) i;
                }
            }
            Assert.assertTrue(out[k] >= 0, names[k] + " not on the menu");
        }
        return out;
    }

    private static List<String> namesOf(final Iterable<Card> pool, final short[] st) {
        final List<Card> cs = new ArrayList<>();
        for (Card c : pool) {
            cs.add(c);
        }
        final List<String> out = new ArrayList<>();
        for (short x : st) {
            out.add(cs.get(x).getName());
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static JsonObject body(final int min, final int max) {
        final JsonObject b = new JsonObject();
        b.addProperty("min", min);
        b.addProperty("max", max);
        return b;
    }

    /** One scored ask: what the scorer saw and what it would play (null = the policy's answer stands). */
    static final class Ask {
        ChoiceScorer.Scored s;
        short[] alt;
        List<String> altNames;
        double ms;
        String family;

        String dump() {
            final StringBuilder b = new StringBuilder(family + " unsupported=" + s.unsupported + " options=" + s.options.size()
                    + " ms=" + Math.round(ms));
            for (ChoiceScorer.Option o : s.options) {
                b.append("\n  ").append(o.policy ? "*" : " ").append(o.label).append(" fail=").append(o.fail).append(" reach=")
                        .append(o.screen == null ? "-" : String.valueOf(o.screen.reach)).append(" gf=")
                        .append(o.screen == null ? "-" : o.screen.gf + "@" + o.screen.gfTurn).append(" v=").append(o.value)
                        .append(o == s.chosen ? " <- chosen" : "");
            }
            return b.toString();
        }
    }

    /** enumerate + choose as RlSearch.decideChoice0 runs them; {@code value}: the leaf value of option i (null: 0.5 each). */
    private static Ask ask(final ChoiceScorer sc, final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy,
            final RlKnowledge know, final SpellAbility askSa, final Iterable<Card> pool, final double[] value) {
        final Ask a = new Ask();
        a.family = forge.bench.rl.RlSchema.familyName(m.family);
        final long t0 = System.nanoTime();
        a.s = sc.enumerate(g, me, m, greedy, know, 4242L, sc.mechAsk(m) ? askSa : null);
        final int n = sc.payloads(a.s).size();
        if (a.s.unsupported == null) {
            final double[] v = new double[n];
            int at = 0;
            for (int i = 0; i < a.s.options.size(); i++) {
                for (int j = 0; j < a.s.options.get(i).frames.size(); j++) {
                    v[at++] = value == null || i >= value.length ? 0.5 : value[i];
                }
            }
            a.alt = sc.choose(a.s, v, greedy);
        }
        a.ms = (System.nanoTime() - t0) / 1e6;
        a.altNames = a.alt == null ? null : namesOf(pool, a.alt);
        System.out.println("REACHDISCARDS " + a.dump());
        return a;
    }

    private static double reachOf(final ChoiceScorer.Option o) {
        return o.screen == null || Double.isNaN(o.screen.reach) ? 0.0 : o.screen.reach;
    }

    // ------------------------------------------------------------------ 1. a resolving effect's discard (DISCARD_FROM)

    /** Bazaar of Baghdad's discard, posed in the live resolution; {@code on} = the key. */
    private static Ask bazaar(final boolean on, final String lib, final double[] value) {
        return bazaar(on, "Doomsday;Grizzly Bears;Balduvian Bears", "Bazaar of Baghdad;Underground Sea;Watery Grave",
                "Island;Island;" + lib, new String[] {"Doomsday", "Island", "Island"}, value, "Balduvian Bears;Grizzly Bears");
    }

    /** As above on any position: {@code policy} = the three cards the policy discards, {@code kept} = the live hand after. */
    private static Ask bazaar(final boolean on, final String hand, final String battlefield, final String lib,
            final String[] policy, final double[] value, final String kept) {
        final Game g = game(hand, battlefield, lib, "MAIN1");
        final Player me = g.getPlayers().get(0);
        final ChoiceScorer sc = scorer(on);
        final RlKnowledge know = know(g);
        final Ask[] out = new Ask[1];
        me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa,
                    final CardCollection validCards, final int min, final int max, final CardCollectionView visible) {
                Assert.assertEquals(max, 3);
                final RlCandidates.Menu m = RlCandidates.build(g, me, "chooseCardsToDiscardFrom", "discardFrom", body(min, max),
                        new Object[] {validCards, sa, p});
                // the policy's answer, as in the audit: Doomsday and the two cards just drawn
                final short[] greedy = steps(validCards, policy);
                sc.setAskPlayer(p);
                out[0] = ask(sc, g, me, m, greedy, know, sa, validCards, value);
                final CardCollection live = new CardCollection();
                final List<Card> cs = new ArrayList<>();
                validCards.forEach(cs::add);
                for (short x : greedy) {
                    live.add(cs.get(x));
                }
                return live;
            }
        });
        SpellAbility draw = null;
        for (SpellAbility sa : find(me, ZoneType.Battlefield, "Bazaar of Baghdad").getSpellAbilities()) {
            if (sa.getApi() == forge.game.ability.ApiType.Draw) {
                draw = sa;
            }
        }
        Assert.assertNotNull(draw, "Bazaar's activated ability");
        draw.setActivatingPlayer(me);
        Assert.assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(draw), "activate");
        MyRandom.setRandom(new Random(42));
        g.getStack().resolveStack();
        Assert.assertNotNull(out[0], "the discard was asked");
        // the copies never moved the live game: it took the policy's answer
        Assert.assertEquals(ChoiceLineTest.names(me, ZoneType.Hand), kept);
        return out[0];
    }

    @Test
    public void aBazaarStyleDiscardKeepsDoomsdayWhenTheGoldfishSeesALineThroughIt() {
        final Ask a = bazaar(true, KILL, null);
        Assert.assertNull(a.s.unsupported, a.dump());
        Assert.assertTrue(a.s.reachDiscards);
        ChoiceScorer.Option own = null;
        for (ChoiceScorer.Option o : a.s.options) {
            if (o.policy) {
                own = o;
            }
        }
        Assert.assertNotNull(own, a.dump());
        Assert.assertEquals(reachOf(own), 0.0, 1e-9, "discarding Doomsday leaves no line: " + a.dump());
        Assert.assertNotNull(a.alt, "the answer moves: " + a.dump());
        Assert.assertFalse(a.altNames.contains("Doomsday"), "Doomsday is kept: " + a.altNames);
        Assert.assertEquals(a.altNames.size(), 3);
        // the line is the goldfish's (a land short now: the relaxation's land casts it), not the plain screen's
        Assert.assertTrue(a.s.chosen.screen.gf > 0, a.dump());
        Assert.assertEquals(a.s.chosen.screen.gf, a.s.chosen.screen.reach, 1e-12, "the reach part is the goldfish: " + a.dump());
        Assert.assertEquals(a.s.chosen.screen.lines, 0, "no line by our next turn: " + a.dump());
        // every "keep Doomsday" answer one swap from the policy's set was a candidate
        int keeps = 0;
        for (ChoiceScorer.Option o : a.s.options) {
            if (!o.label.contains("Doomsday")) {
                keeps++;
                Assert.assertTrue(reachOf(o) > 0, "keep Doomsday has a line: " + o.label + "\n" + a.dump());
            } else {
                Assert.assertEquals(reachOf(o), 0.0, 1e-9, o.label);
            }
        }
        Assert.assertEquals(keeps, 2, "Doomsday swapped for either card the policy kept: " + a.dump());
    }

    @Test
    public void withTheKeyOffADiscardIsNotScored() {
        final Ask a = bazaar(false, KILL, null);
        Assert.assertEquals(a.s.unsupported, "family", "the pilot arm today: a DISCARD_FROM ask is not the scorer's");
        Assert.assertTrue(a.s.options.isEmpty());
        Assert.assertNull(a.alt);
        Assert.assertFalse(a.s.reachDiscards);
    }

    @Test
    public void withNoLineAnywhereThePolicysDiscardStandsWhateverTheValueSays() {
        // the same ask with no kill in the library; the leaf value prefers the first swap by 0.4 (far above margin)
        final Ask a = bazaar(true, NOKILL, new double[] {0.3, 0.7, 0.7, 0.7, 0.7, 0.7, 0.7, 0.7});
        Assert.assertNull(a.s.unsupported, a.dump());
        Assert.assertTrue(a.s.options.size() > 1, a.dump());
        for (ChoiceScorer.Option o : a.s.options) {
            Assert.assertEquals(reachOf(o), 0.0, 1e-9, o.label + "\n" + a.dump());
        }
        Assert.assertNull(a.alt, "the value alone never moves a discard: " + a.dump());
    }

    // ------------------------------------------------------------------ 2. a cast's card cost (COST_CARDS)

    /** The live seat: a spell with a card-discard cost; its discard is asked at payment, as the bridge's costCards ask. */
    static final class CostSeat extends PlayerControllerAi implements forge.ai.AiCostDecision.PaymentDecisions {
        final Game g;
        final ChoiceScorer sc;
        final RlKnowledge know;
        Ask out;

        CostSeat(final Game g, final Player p, final ChoiceScorer sc, final RlKnowledge know) {
            super(g, p, p.getController().getLobbyPlayer());
            this.g = g;
            this.sc = sc;
            this.know = know;
        }

        @Override
        public forge.ai.AiCostDecision costDecisionForPayment(final Player payer, final SpellAbility sa, final boolean effect) {
            final Player me = getPlayer();
            return new forge.ai.AiCostDecision(payer, sa, effect) {
                @Override
                public forge.game.cost.PaymentDecision visit(final forge.game.cost.CostDiscard cost) {
                    final CardCollection pool = new CardCollection(me.getCardsIn(ZoneType.Hand));
                    final RlCandidates.Menu m = RlCandidates.build(g, me, "chooseCardsForCost", "costCards", body(1, 1),
                            new Object[] {pool, sa, cost});
                    final short[] greedy = steps(pool, "Doomsday"); // the policy pitches Doomsday
                    sc.setCostPart(cost.getClass().getSimpleName());
                    sc.setAskPlayer(null);
                    out = ask(sc, g, me, m, greedy, know, sa, pool, null);
                    return forge.game.cost.PaymentDecision.card(find(me, ZoneType.Hand, "Doomsday"));
                }
            };
        }
    }

    private static Ask cost(final boolean on) {
        final Game g = game("Tormenting Voice;Doomsday;Grizzly Bears", "Mountain;Mountain;Underground Sea;Watery Grave", KILL,
                "MAIN1");
        final Player me = g.getPlayers().get(0);
        final CostSeat seat = new CostSeat(g, me, scorer(on), know(g));
        me.dangerouslySetController(seat);
        final SpellAbility sa = find(me, ZoneType.Hand, "Tormenting Voice").getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        seat.playChosenSpellAbility(sa);
        Assert.assertNotNull(seat.out, "the discard cost was asked; hand " + ChoiceLineTest.names(me, ZoneType.Hand));
        // the live cast paid with the policy's answer only
        Assert.assertEquals(ChoiceLineTest.names(me, ZoneType.Hand), "Grizzly Bears");
        return seat.out;
    }

    @Test
    public void aCastsCardCostDoesNotPitchDoomsday() {
        final Ask a = cost(true);
        Assert.assertNull(a.s.unsupported, a.dump());
        Assert.assertTrue(a.s.reachDiscards);
        Assert.assertNotNull(a.alt, "the answer moves: " + a.dump());
        Assert.assertEquals(a.altNames, List.of("Grizzly Bears"), a.dump());
        Assert.assertTrue(a.s.chosen.screen.gf > 0, a.dump());
        for (ChoiceScorer.Option o : a.s.options) {
            if (o.policy) {
                Assert.assertEquals(o.label, "Doomsday");
                Assert.assertEquals(reachOf(o), 0.0, 1e-9, a.dump());
            }
        }
        final Ask off = cost(false);
        Assert.assertEquals(off.s.unsupported, "family");
        Assert.assertNull(off.alt);
    }

    // ------------------------------------------------------------------ 3. the cleanup's discard to hand size (a CARDS ask)

    private static Ask cleanup(final boolean on) {
        return cleanup(on, "Doomsday;Grizzly Bears;Balduvian Bears;Forest;Forest;Forest;Forest;Forest;Forest",
                "Underground Sea;Watery Grave", KILL, new String[] {"Doomsday", "Forest"});
    }

    /** As above on any position: {@code policy} = the cards the policy discards (as many as the cleanup asks for). */
    private static Ask cleanup(final boolean on, final String hand0, final String battlefield, final String lib,
            final String[] policy) {
        final Game g = game(hand0, battlefield, lib, "MAIN2");
        final Player me = g.getPlayers().get(0);
        final ChoiceScorer sc = scorer(on);
        final RlKnowledge know = know(g);
        final Ask[] out = new Ask[1];
        me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public List<SpellAbility> chooseSpellAbilityToPlay() {
                return null; // pass to the cleanup
            }

            @Override
            public CardCollectionView chooseCardsToDiscardToMaximumHandSize(final int numDiscard) {
                Assert.assertEquals(numDiscard, policy.length);
                final CardCollectionView hand = me.getCardsIn(ZoneType.Hand);
                // the bridge poses the cleanup discard as a cardsChoice ask over the hand (family CARDS)
                final RlCandidates.Menu m = RlCandidates.build(g, me, "chooseCardsToDiscardToMaximumHandSize", "cardsChoice",
                        body(numDiscard, numDiscard), hand);
                final short[] greedy = steps(hand, policy); // the policy's answer, as in the audit
                sc.setAskPlayer(null);
                out[0] = ask(sc, g, me, m, greedy, know, null, hand, null);
                final CardCollection live = new CardCollection();
                final List<Card> cs = new ArrayList<>();
                hand.forEach(cs::add);
                for (short x : greedy) {
                    live.add(cs.get(x));
                }
                return live;
            }
        });
        int n = 0;
        while (out[0] == null && !g.isGameOver() && n++ < 400) {
            g.getPhaseHandler().mainLoopStep();
        }
        Assert.assertNotNull(out[0], "the cleanup asked");
        Assert.assertTrue(ChoiceLineTest.names(me, ZoneType.Graveyard).contains("Doomsday"), "the live game took the policy's answer");
        return out[0];
    }

    @Test
    public void theCleanupDiscardKeepsDoomsday() {
        final Ask a = cleanup(true);
        Assert.assertNull(a.s.unsupported, a.dump());
        Assert.assertTrue(a.s.reachDiscards);
        Assert.assertNotNull(a.alt, "the answer moves: " + a.dump());
        Assert.assertFalse(a.altNames.contains("Doomsday"), "Doomsday is kept: " + a.altNames);
        Assert.assertEquals(a.altNames.size(), 2);
        Assert.assertTrue(a.s.chosen.screen.gf > 0, a.dump());
        for (ChoiceScorer.Option o : a.s.options) {
            Assert.assertEquals(reachOf(o) > 0, !o.label.contains("Doomsday"), o.label + "\n" + a.dump());
        }
        // the pilot arm today: a cleanup discard of two cards is a multi-pick CARDS ask, which it does not score
        final Ask off = cleanup(false);
        Assert.assertEquals(off.s.unsupported, "multiPick");
        Assert.assertNull(off.alt);
    }

    // ------------------------------------------------------------------ the two audit positions on real 40-card lists

    /** The rest of the R4 Doomsday list (GoldfishTest.DD1010 and its opening hand) below this test's hand and lands. */
    private static final String DD1010_REST = "Beseech the Mirror;Bolas's Citadel;Chart a Course;Chromatic Star;"
            + "Collective Brutality;Counterspell;Daze;Echo of Eons;Gush;Island;Jace, Wielder of Mysteries;Library of Alexandria;"
            + "Lotus Petal;Memory Jar;Misty Rainforest;Mystical Tutor;Scalding Tarn;Stern Scolding;Troll of Khazad-dûm;"
            + "Waterlogged Grove;Xander's Lounge;Verdant Catacombs;Cabal Ritual;Urborg, Tomb of Yawgmoth;Mind Stone;"
            + "Thassa's Oracle;Swamp";

    /**
     * dd-audit-1109, pair 33: the cleanup threw Doomsday with two Swamps from ten cards. The same ask on the list's real
     * cards: 22 options (the policy's set and its one-card swaps) inside the arm's node allowance.
     */
    @Test
    public void theAuditsCleanupOnARealList() {
        final Ask a = cleanup(true, "Doomsday;Swamp;Swamp;Raffine's Tower;Timetwister;Baleful Strix;Wishclaw Talisman;Island;"
                + "Jace, the Mind Sculptor;Remand", "Darkslick Shores;Overgrown Tomb;Island", DD1010_REST,
                new String[] {"Doomsday", "Swamp", "Swamp"});
        Assert.assertNull(a.s.unsupported, a.dump());
        Assert.assertTrue(a.s.options.size() >= 8, a.dump());
        for (ChoiceScorer.Option o : a.s.options) {
            Assert.assertNull(o.fail, o.label + "\n" + a.dump());
            if (o.label.contains("Doomsday")) {
                Assert.assertEquals(reachOf(o), 0.0, 1e-9, "no line without Doomsday: " + o.label + "\n" + a.dump());
            }
        }
        Assert.assertNotNull(a.alt, "the answer moves: " + a.dump());
        Assert.assertFalse(a.altNames.contains("Doomsday"), "Doomsday is kept: " + a.altNames);
        Assert.assertTrue(a.ms < 8000, "inside the ask's budget: " + a.ms);
    }

    /** The rest of the R4 oracle list (GoldfishTest.DECK) below this test's hand, lands and the two cards Bazaar draws. */
    private static final String ORACLE_REST = "Bayou;Botanical Sanctum;Brain Freeze;Brainstorm;Cabal Ritual;Consider;"
            + "Dark Ritual;Echo of Eons;Gush;Island;Island;Jace, the Mind Sculptor;Jace, Vryn's Prodigy;Library of Alexandria;"
            + "Liliana of the Veil;Lotus Petal;Marsh Flats;Mishra's Bauble;Mox Emerald;Raffine's Tower;Spellseeker;Swamp;"
            + "Thassa's Oracle;Tolarian Academy";

    /**
     * dd-audit-1109: Bazaar of Baghdad with Doomsday, Troll, Remand and a fourth card in hand; the pilot discarded
     * Doomsday, Chromatic Star and an Island and kept Troll. The same ask on the oracle list's real cards.
     */
    @Test
    public void theAuditsBazaarOnARealList() {
        final Ask a = bazaar(true, "Doomsday;Troll of Khazad-dûm;Remand;Daze",
                "Bazaar of Baghdad;Underground Sea;Swamp;Urborg, Tomb of Yawgmoth", "Island;Chromatic Star;" + ORACLE_REST,
                new String[] {"Doomsday", "Chromatic Star", "Island"}, null, "Daze;Remand;Troll of Khazad-dûm");
        Assert.assertNull(a.s.unsupported, a.dump());
        for (ChoiceScorer.Option o : a.s.options) {
            Assert.assertNull(o.fail, o.label + "\n" + a.dump());
        }
        Assert.assertNotNull(a.alt, "the answer moves: " + a.dump());
        Assert.assertFalse(a.altNames.contains("Doomsday"), "Doomsday is kept: " + a.altNames);
        Assert.assertTrue(a.ms < 8000, "inside the ask's budget: " + a.ms);
    }

    // ------------------------------------------------------------------ the key

    @Test
    public void theKeyIsOffByDefaultAndNeedsTheBlend() {
        final ChoiceScorer.Config off = ChoiceScorer.Config.parse(JsonParser.parseString(BLOCK).getAsJsonObject());
        Assert.assertFalse(off.reachDiscards);
        Assert.assertEquals(off.families, new java.util.TreeSet<>(Arrays.asList(forge.bench.rl.RlSchema.F_CARDS)));
        Assert.assertFalse(off.toJson().has("reachDiscards"), "an old config prints as before");
        final JsonObject o = JsonParser.parseString(BLOCK).getAsJsonObject();
        o.addProperty("reachDiscards", true);
        final ChoiceScorer.Config on = ChoiceScorer.Config.parse(o);
        Assert.assertTrue(on.reachDiscards);
        Assert.assertTrue(on.families.contains(forge.bench.rl.RlSchema.F_DISCARD_FROM));
        Assert.assertTrue(on.families.contains(forge.bench.rl.RlSchema.F_COST_CARDS));
        Assert.assertTrue(on.families.contains(forge.bench.rl.RlSchema.F_CARDS));
        // what it prints parses back to itself
        final ChoiceScorer.Config again = ChoiceScorer.Config.parse(on.toJson());
        Assert.assertTrue(again.reachDiscards);
        Assert.assertEquals(again.toJson(), on.toJson());
        // without the blend's own key there is nothing to score with
        final JsonObject bare = JsonParser.parseString("{\"screenNodes\":2000,\"reachDiscards\":true}").getAsJsonObject();
        Assert.assertThrows(IllegalArgumentException.class, () -> ChoiceScorer.Config.parse(bare));
        // and the line rule is a different rule for the same asks
        final JsonObject both = JsonParser.parseString(BLOCK).getAsJsonObject();
        both.addProperty("lineOnly", true);
        Assert.assertThrows(IllegalArgumentException.class, () -> ChoiceScorer.Config.parse(both));
    }
}
