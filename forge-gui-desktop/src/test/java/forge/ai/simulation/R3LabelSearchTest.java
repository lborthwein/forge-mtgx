package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * r3-labelsearch-1030 (E1; DESIGN-R3 §3, PREDECLARATION-R3-step1 §3-4): the R3 label search on scripted boards. Lines are
 * played by plain Forge AI seats (profile Default, no solver) unless a test says otherwise, so each test isolates one
 * mechanism: off identity, a choose-a-card row (Doomsday + Gush, the blue land), the roll-out leaf and its caps, the
 * stratified first draw, a costed draw (The One Ring), oppMin in label mode, the row log, determinism.
 */
public class R3LabelSearchTest extends SimulationTest {

    // ------------------------------------------------------------------------------------------------ fixtures

    static JsonObject base() {
        final JsonObject o = new JsonObject();
        o.addProperty("profile", "Default");
        o.add("solverSpec", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("proofLeaf", false);
        o.addProperty("selectWorlds", 2);
        o.addProperty("targetWorlds", 4);
        o.addProperty("maxTurns", 4);
        o.addProperty("maxSteps", 4000);
        o.addProperty("inline", false);
        o.addProperty("oppMin", false);
        final JsonObject cac = new JsonObject();
        cac.addProperty("topM", 0);
        o.add("cac", cac);
        return o;
    }

    static R3LabelSearch.Spec spec(final JsonObject o) {
        final R3LabelSearch.Spec s = R3LabelSearch.Spec.parse(o);
        s.testUnsafe = true; // stub ranker, no proof check, no belief re-draw: tests only
        return s;
    }

    static R3LabelSearch.Env env(final ChooseACard.Valuer v, final Map<Integer, Set<Integer>> known) {
        return new R3LabelSearch.Env() {
            @Override
            public ChooseACard.Valuer ranker() {
                return v;
            }

            @Override
            public HonestLabel.Seat proof() {
                return null;
            }

            @Override
            public ForcedWinSolver.Redraw redraw() {
                return null;
            }

            @Override
            public Set<Integer> known(final Player p) {
                return known == null ? new HashSet<>() : known.getOrDefault(p.getId(), new HashSet<>());
            }
        };
    }

    static ChooseACardTest.Stub flat(final double x) {
        return new ChooseACardTest.Stub((g, p) -> x);
    }

    private static PlayerControllerAi seat(final Game game) {
        final Player a = game.getPlayers().get(0);
        final PlayerControllerAi c = new PlayerControllerAi(game, a, a.getController().getLobbyPlayer());
        a.dangerouslySetController(c);
        return c;
    }

    private static void castAndResolve(final Game game, final String name) {
        final Player a = game.getPlayers().get(0);
        final Card c = ChooseACardTest.find(a, ZoneType.Hand, name);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        AssertJUnit.assertTrue("cast " + name, ((PlayerControllerAi) a.getController()).playChosenSpellAbility(sa));
        MyRandom.setRandom(new Random(42));
        int guard = 0;
        while (!game.getStack().isEmpty() && guard++ < 10) {
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(true);
        }
    }

    static SpellAbility firstSpell(final Player p, final String name) {
        final SpellAbility sa = ChooseACardTest.find(p, ZoneType.Hand, name).getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        return sa;
    }

    static JsonObject withoutSchema(final JsonObject o) {
        final JsonObject c = o.deepCopy();
        c.remove("schema");
        return c;
    }

    static JsonObject noMs(final JsonObject o) {
        final JsonObject c = o.deepCopy();
        c.remove("ms");
        return c;
    }

    static int count(final JsonArray worlds, final String end) {
        int n = 0;
        for (JsonElement e : worlds) {
            if (e.getAsJsonObject().get("end").getAsString().equals(end)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ boards

    /** Our main 1: Lightning Bolt and a Mountain; the opponent at {@code oppLife}; both libraries 20 lands. */
    private Game boltBoard(final int oppLife, final int myLife, final boolean counter, final boolean bears) {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 20; i++) addCardToZone("Forest", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Plains", b, ZoneType.Library);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        addCard("Mountain", a);
        if (counter) {
            addCardToZone("Counterspell", b, ZoneType.Hand);
            addCard("Island", b);
            addCard("Island", b);
        }
        if (bears) addCard("Grizzly Bears", b).setSickness(false);
        b.setLife(oppLife, null);
        a.setLife(myLife, null);
        g.getAction().checkStateEffects(true);
        return g;
    }

    /** DESIGN-r2 §10's Doomsday + Gush board, one blue source short; the land drop is spent. */
    private Game doomsdayBoard() {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (String n : new String[] {"Thassa's Oracle", "Force of Will", "Island", "Lotus Petal", "Brainstorm",
                "Craw Wurm", "Swamp", "Grizzly Bears", "Ponder"}) {
            addCardToZone(n, a, ZoneType.Library);
        }
        for (int i = 0; i < 20; i++) addCardToZone("Mountain", b, ZoneType.Library);
        for (int i = 0; i < 4; i++) addCard("Swamp", a);
        addCard("Island", a);
        a.addLandPlayedThisTurn();
        addCardToZone("Demonic Tutor", a, ZoneType.Hand);
        addCardToZone("Doomsday", a, ZoneType.Hand);
        addCardToZone("Gush", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    // ------------------------------------------------------------------------------------------------ off identity

    @Test
    public void offIsUnchanged() {
        // no probe is registered by default; a choose-a-card engine without a row search is the shadow engine
        AssertJUnit.assertTrue(forge.ai.AiTapeController.PROBES.isEmpty());
        // the proof spec (and its sha) does not see the r3 host keys
        final JsonObject l = new JsonObject();
        l.addProperty("redraw", "belief");
        l.addProperty("sequentialAi", true);
        l.addProperty("aiTimeoutSec", 600);
        final JsonObject sc = new JsonObject();
        sc.addProperty("hideOwnLibrary", "true");
        sc.addProperty("budget", "counter");
        l.add("solverCfg", sc);
        l.add("lossCfg", sc.deepCopy());
        final String sha0 = HonestLabel.Spec.parse(l).sha();
        final JsonObject l2 = l.deepCopy();
        l2.add("r3", base());
        l2.add("r3Ranker", new JsonObject());
        AssertJUnit.assertEquals(sha0, HonestLabel.Spec.parse(l2).sha());
        // clock keys are refused; the identity makes every default explicit
        for (String k : new String[] {"budgetMs", "cpuCapMs", "wallMs", "clock"}) {
            final JsonObject o = base();
            o.addProperty(k, 1);
            AssertJUnit.assertTrue(k, ChooseACardTest.expectIae(() -> R3LabelSearch.Spec.parse(o)));
        }
        final JsonObject bad = base();
        bad.getAsJsonObject("cac").addProperty("mode", "on");
        AssertJUnit.assertTrue(ChooseACardTest.expectIae(() -> R3LabelSearch.Spec.parse(bad)));
        AssertJUnit.assertEquals(R3LabelSearch.Spec.parse(new JsonObject()).sha(),
                R3LabelSearch.Spec.parse(withoutSchema(R3LabelSearch.Spec.parse(new JsonObject()).identity())).sha());
        // the live game never moves: a searched row leaves the fingerprint and the random stream as they were
        final Game g = boltBoard(20, 20, false, false);
        final Player a = g.getPlayers().get(0);
        final String fp = LookaheadSearch.fingerprint(g);
        final R3LabelSearch r3 = new R3LabelSearch(spec(base()), env(flat(0.5), null));
        r3.priorityRow(g, a, r3.candidates(g, a, null), 3L);
        AssertJUnit.assertEquals(fp, LookaheadSearch.fingerprint(g));
    }

    // ------------------------------------------------------------------------------------------------ choice row

    @Test
    public void choiceRowDoomsdayGushTutorsTheBlueLandAsItsMax() {
        final BiFunction<Game, Player, Double> killAvailable = (g, p) -> {
            if (!ChooseACardTest.inZone(p, ZoneType.Hand, "Doomsday") || !ChooseACardTest.inZone(p, ZoneType.Hand, "Gush")) {
                return 0.1;
            }
            int islands = 0;
            for (Card x : p.getCardsIn(ZoneType.Battlefield)) if (x.getType().hasSubtype("Island")) islands++;
            if (ChooseACardTest.inZone(p, ZoneType.Hand, "Island")) islands++;
            return islands >= 2 ? 0.8 : 0.4;
        };
        final JsonObject o = base();
        o.addProperty("leaf", "value");
        o.addProperty("horizon", 2); // our next turn's first empty-stack priority: the opponent's turn is played
        o.addProperty("targetWorlds", 3);
        final Game g = doomsdayBoard();
        final PlayerControllerAi c = seat(g);
        final ChooseACardTest.Stub stub = new ChooseACardTest.Stub(killAvailable);
        final R3LabelSearch r3 = new R3LabelSearch(spec(o), env(stub, null));
        final R3LabelSearch.Result[] got = {null};
        final ChooseACard cac = new ChooseACard(ChooseACardTest.cfg("shadow"), stub, null);
        cac.setRowSearch(r3.rowSearch(row -> 11L, (row, res) -> got[0] = res));
        cac.install(c);
        castAndResolve(g, "Demonic Tutor");
        AssertJUnit.assertFalse("shadow: the live answer is Forge's own", ChooseACardTest.inZone(g.getPlayers().get(0),
                ZoneType.Hand, "Island"));
        final R3LabelSearch.Result res = got[0];
        AssertJUnit.assertNotNull(cac.log.toString(), res);
        final JsonObject j = res.toJson();
        System.err.println("[r3-test] doomsday row " + j);
        AssertJUnit.assertEquals("SEARCH", res.kind);
        AssertJUnit.assertEquals("forge", res.cands.get(0).src);
        AssertJUnit.assertTrue("Forge's answer plus at most m", res.cands.size() >= 2 && res.cands.size() <= 7);
        AssertJUnit.assertTrue(j.toString(), res.cands.get(res.pick).desc.contains("Island"));
        AssertJUnit.assertTrue(res.departed);
        AssertJUnit.assertEquals("the max: the blue land", 0.8, res.target, 1e-9);
        AssertJUnit.assertEquals(0.4, res.cands.get(0).mean, 1e-9);
        double best = Double.NEGATIVE_INFINITY;
        for (R3LabelSearch.CandOut co : res.cands) {
            if (!Double.isNaN(co.mean)) best = Math.max(best, co.mean);
        }
        AssertJUnit.assertEquals("the pick is the max of the played answers", best, res.cands.get(res.pick).mean, 1e-12);
        AssertJUnit.assertTrue(j.getAsJsonArray("ranked").size() >= res.cands.size());
    }

    // ------------------------------------------------------------------------------------------------ roll-out leaf

    @Test
    public void rolloutLeafEndsAtTheTerminalOutcome() {
        final Game g = boltBoard(3, 20, false, false);
        final Player a = g.getPlayers().get(0);
        final R3LabelSearch r3 = new R3LabelSearch(spec(base()), env(flat(0.5), null));
        final List<R3LabelSearch.PCand> cands = r3.candidates(g, a,
                Collections.singletonList(firstSpell(a, "Lightning Bolt")));
        AssertJUnit.assertEquals("recorded", cands.get(0).src);
        AssertJUnit.assertTrue(cands.get(0).label.contains("Lightning Bolt"));
        AssertJUnit.assertTrue(cands.get(1).pass);
        final R3LabelSearch.Result res = r3.priorityRow(g, a, cands, 5L);
        final JsonObject j = res.toJson();
        System.err.println("[r3-test] bolt row " + j);
        AssertJUnit.assertEquals(j.toString(), 1.0, res.target, 1e-9);
        final JsonArray c0 = j.getAsJsonArray("cands").get(0).getAsJsonObject().getAsJsonArray("worlds");
        AssertJUnit.assertEquals(2, c0.size());
        AssertJUnit.assertEquals("the Bolt line ends the game", 2, count(c0, "terminal"));
        AssertJUnit.assertEquals(4, count(j.getAsJsonArray("target_worlds"), "terminal"));
        AssertJUnit.assertEquals(0, res.targetCapped);
    }

    @Test
    public void rolloutCapsAreUnresolvedAndWithholdTheTarget() {
        final JsonObject o = base();
        o.addProperty("maxTurns", 1);
        final Game g = boltBoard(20, 20, false, false);
        final Player a = g.getPlayers().get(0);
        final R3LabelSearch r3 = new R3LabelSearch(spec(o), env(flat(0.5), null));
        final R3LabelSearch.Result res = r3.priorityRow(g, a, r3.candidates(g, a, null), 6L);
        final JsonObject j = res.toJson();
        AssertJUnit.assertTrue(j.toString(), Double.isNaN(res.target));
        AssertJUnit.assertTrue(j.toString(), res.why.equals("no_resolved_candidate") || res.why.startsWith("target_"));
        for (R3LabelSearch.CandOut co : res.cands) {
            AssertJUnit.assertEquals(co.worlds.size(), co.capped);
            for (R3LabelSearch.WorldOut w : co.worlds) AssertJUnit.assertEquals("cap:turns", w.end);
        }
    }

    // ------------------------------------------------------------------------------------------------ N5b strata

    @Test
    public void stratifiedFirstDrawHasTheCountWeights() {
        final int[][] cases = {{1, 9, 16}, {10, 30, 16}, {1, 39, 16}, {3, 5, 16}};
        for (int[] cs : cases) {
            final Game g = initAndCreateGame();
            final Player a = g.getPlayers().get(0);
            for (int i = 0; i < cs[1]; i++) addCardToZone("Forest", a, ZoneType.Library);
            for (int i = 0; i < cs[0]; i++) addCardToZone("Lightning Bolt", a, ZoneType.Library);
            final int U = cs[0] + cs[1];
            final int W = Math.min(U, cs[2]);
            int bolts = 0;
            for (int w = 0; w < W; w++) {
                final String n = R3LabelSearch.stratify(a, Collections.emptySet(), w, W, new Random(w));
                AssertJUnit.assertEquals(n, a.getCardsIn(ZoneType.Library).get(0).getName());
                if ("Lightning Bolt".equals(n)) bolts++;
            }
            final double weight = (double) bolts / W, want = (double) cs[0] / U;
            AssertJUnit.assertTrue("bolts " + cs[0] + "/" + U + ": " + weight, Math.abs(weight - want) <= 1.0 / W + 1e-12);
            if (U <= cs[2]) {
                AssertJUnit.assertEquals("U <= W: exact", want, weight, 1e-12);
            }
        }
        // a known position stays; the first UNKNOWN slot gets the stratified card
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0);
        final Card top = addCardToZone("Island", a, ZoneType.Library);
        for (int i = 0; i < 9; i++) addCardToZone("Forest", a, ZoneType.Library);
        addCardToZone("Lightning Bolt", a, ZoneType.Library);
        final List<Card> lib = new ArrayList<>(a.getCardsIn(ZoneType.Library));
        lib.remove(top);
        lib.add(0, top);
        a.getZone(ZoneType.Library).setCards(lib);
        final Set<Integer> known = Collections.singleton(top.getId());
        int bolts = 0;
        for (int w = 0; w < 10; w++) {
            final String n = R3LabelSearch.stratify(a, known, w, 10, new Random(w));
            AssertJUnit.assertEquals(top.getId(), a.getCardsIn(ZoneType.Library).get(0).getId());
            AssertJUnit.assertEquals(n, a.getCardsIn(ZoneType.Library).get(1).getName());
            if ("Lightning Bolt".equals(n)) bolts++;
        }
        AssertJUnit.assertEquals(1, bolts);
        // a row's target worlds carry the first draw and the weight 1/W
        final Game g2 = boltBoard(20, 20, false, false);
        final Player a2 = g2.getPlayers().get(0);
        final JsonObject o = base();
        o.addProperty("maxTurns", 1);
        o.addProperty("maxUnresolvedShare", 1.0);
        o.addProperty("leaf", "value");
        o.addProperty("horizon", 0);
        final R3LabelSearch r3 = new R3LabelSearch(spec(o), env(flat(0.5), null));
        final R3LabelSearch.Result res = r3.priorityRow(g2, a2, r3.candidates(g2, a2, null), 9L);
        final JsonArray tw = res.toJson().getAsJsonArray("target_worlds");
        AssertJUnit.assertEquals(4, tw.size());
        for (JsonElement e : tw) {
            AssertJUnit.assertEquals("Forest", e.getAsJsonObject().get("draw").getAsString());
            AssertJUnit.assertEquals(0.25, e.getAsJsonObject().get("w").getAsDouble(), 1e-9);
        }
    }

    // ------------------------------------------------------------------------------------------------ costed draw

    @Test
    public void oneRingIsACandidateAndItsTargetIsTheDrawExpectation() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        addCardToZone("Lightning Bolt", a, ZoneType.Library);
        for (int i = 0; i < 9; i++) addCardToZone("Forest", a, ZoneType.Library);
        for (int i = 0; i < 20; i++) addCardToZone("Plains", b, ZoneType.Library);
        final Card ring = addCard("The One Ring", a);
        ring.addCounterInternal(CounterEnumType.BURDEN, 1, a, false, null, null);
        addCard("Mountain", a);
        addCard("Grizzly Bears", b).setSickness(false);
        a.setLife(1, null);
        b.setLife(3, null);
        g.getAction().checkStateEffects(true);
        final JsonObject o = base();
        o.addProperty("maxTurns", 3);
        final R3LabelSearch r3 = new R3LabelSearch(spec(o), env(flat(0.5), null));
        final List<R3LabelSearch.PCand> cands = r3.candidates(g, a, null);
        R3LabelSearch.PCand ringCand = null;
        for (R3LabelSearch.PCand c : cands) {
            if (c.label.startsWith("The One Ring")) ringCand = c;
        }
        AssertJUnit.assertNotNull(String.valueOf(cands.size()), ringCand);
        AssertJUnit.assertEquals("draw", ringCand.src);
        AssertJUnit.assertTrue(R3LabelSearch.hasDraw(cands));
        // the costed draw played alone: its target is the expectation over the next two draws (exact)
        final R3LabelSearch.Result res = r3.priorityRow(g, a, Collections.singletonList(ringCand), 21L);
        final JsonObject j = res.toJson();
        System.err.println("[r3-test] ring row " + j);
        AssertJUnit.assertEquals("draws:exact", res.targetKind);
        AssertJUnit.assertEquals(3, j.getAsJsonObject("draws").get("sequences").getAsInt());
        double boltW = 0, wsum = 0;
        for (R3LabelSearch.WorldOut w : res.targetWorlds) {
            wsum += w.weight;
            if (w.draw.contains("Lightning Bolt")) {
                boltW += w.weight;
                AssertJUnit.assertEquals("a drawn out wins now: " + j, 1.0, w.v, 1e-9);
            } else {
                AssertJUnit.assertEquals("no out: the bears kill us: " + j, 0.0, w.v, 1e-9);
            }
        }
        AssertJUnit.assertEquals(1.0, wsum, 1e-12);
        AssertJUnit.assertEquals("one out in ten, two draws: exactly 2/10", 0.2, boltW, 1e-12);
        AssertJUnit.assertEquals(0.2, res.target, 1e-6);
    }

    // ------------------------------------------------------------------------------------------------ oppMin

    @Test
    public void oppMinTakesTheMinOverTheirResponses() {
        final JsonObject o = base();
        o.addProperty("selectWorlds", 1);
        o.addProperty("targetWorlds", 2);
        final java.util.function.Function<JsonObject, R3LabelSearch.Result> run = js -> {
            final Game g = boltBoard(3, 2, true, true);
            final Player a = g.getPlayers().get(0);
            final R3LabelSearch.Spec sp = spec(js);
            sp.testPassiveOpp = true; // their own play is a pass: only the MIN's branches answer the Bolt
            final R3LabelSearch r3 = new R3LabelSearch(sp, env(flat(0.5), null));
            final List<R3LabelSearch.PCand> cands = new ArrayList<>(r3.candidates(g, a,
                    Collections.singletonList(firstSpell(a, "Lightning Bolt"))));
            return r3.priorityRow(g, a, cands.subList(0, 1), 31L);
        };
        final R3LabelSearch.Result off = run.apply(o);
        AssertJUnit.assertEquals(off.toJson().toString(), 1.0, off.target, 1e-9);
        final JsonObject on = o.deepCopy();
        on.addProperty("oppMin", true);
        final R3LabelSearch.Result min = run.apply(on);
        final JsonObject j = min.toJson();
        System.err.println("[r3-test] oppMin row " + j);
        AssertJUnit.assertEquals("their Counterspell, then the bears: " + j, 0.0, min.target, 1e-9);
        AssertJUnit.assertTrue(j.toString(), min.work.oppNodes >= 3);
        AssertJUnit.assertTrue(j.toString(), j.getAsJsonArray("target_worlds").get(0).getAsJsonObject().get("min")
                .getAsString().contains("2"));
        // caps count as not won: a truncated response set leaves a world whose min is above 0 unresolved
        final JsonObject trunc = on.deepCopy();
        trunc.addProperty("oppResponses", 0);
        final R3LabelSearch.Result t = run.apply(trunc);
        AssertJUnit.assertTrue(t.toJson().toString(), Double.isNaN(t.target));
        for (R3LabelSearch.WorldOut w : t.targetWorlds) {
            AssertJUnit.assertEquals("min-cap:truncated", w.end);
        }
        // the node cap: the root alone, then cut
        final JsonObject nodes = on.deepCopy();
        nodes.addProperty("oppNodes", 1);
        final R3LabelSearch.Result n = run.apply(nodes);
        AssertJUnit.assertTrue(n.toJson().toString(), Double.isNaN(n.target));
        AssertJUnit.assertTrue(n.work.oppCuts >= 1);
    }

    // ------------------------------------------------------------------------------------------------ log, determinism

    @Test
    public void rowLogIsComplete() {
        final Game g = boltBoard(3, 20, false, false);
        final Player a = g.getPlayers().get(0);
        final R3LabelSearch r3 = new R3LabelSearch(spec(base()), env(flat(0.5), null));
        final JsonObject j = r3.priorityRow(g, a, r3.candidates(g, a,
                Collections.singletonList(firstSpell(a, "Lightning Bolt"))), 5L).toJson();
        for (String k : new String[] {"row", "why", "cands", "pick", "departed", "target", "target_kind", "target_resolved",
                "target_capped", "target_failed", "target_worlds", "work", "ms"}) {
            AssertJUnit.assertTrue(k, j.has(k));
        }
        for (JsonElement e : j.getAsJsonArray("cands")) {
            final JsonObject c = e.getAsJsonObject();
            for (String k : new String[] {"i", "desc", "src", "key", "mean", "resolved", "capped", "failed", "worlds"}) {
                AssertJUnit.assertTrue(k + " " + c, c.has(k));
            }
            AssertJUnit.assertEquals(2, c.getAsJsonArray("worlds").size());
            for (JsonElement w : c.getAsJsonArray("worlds")) {
                AssertJUnit.assertTrue(w.getAsJsonObject().has("v") && w.getAsJsonObject().has("end"));
            }
        }
        for (JsonElement w : j.getAsJsonArray("target_worlds")) {
            for (String k : new String[] {"v", "end", "draw", "w"}) {
                AssertJUnit.assertTrue(k + " " + w, w.getAsJsonObject().has(k));
            }
        }
        for (String k : new String[] {"lines", "steps", "copies", "proofs", "inline_asks", "opp_min_nodes"}) {
            AssertJUnit.assertTrue(k, j.getAsJsonObject("work").has(k));
        }
    }

    @Test
    public void inlineChooseACardAnswersOurAsksInTheLine() {
        // a priority row whose line casts Demonic Tutor: the in-line engine (mode on) answers it by the ranker
        final Game g = doomsdayBoard();
        final Player a = g.getPlayers().get(0);
        final JsonObject o = base();
        o.addProperty("inline", true);
        o.addProperty("leaf", "value");
        o.addProperty("horizon", 2);
        o.addProperty("targetWorlds", 1);
        o.addProperty("selectWorlds", 1);
        final ChooseACardTest.Stub stub = new ChooseACardTest.Stub((x, p) ->
                ChooseACardTest.inZone(p, ZoneType.Hand, "Island") ? 0.8 : 0.4);
        final R3LabelSearch r3 = new R3LabelSearch(spec(o), env(stub, null));
        final List<R3LabelSearch.PCand> cands = r3.candidates(g, a,
                Collections.singletonList(firstSpell(a, "Demonic Tutor")));
        final R3LabelSearch.Result res = r3.priorityRow(g, a, cands.subList(0, 1), 41L);
        final JsonObject j = res.toJson();
        System.err.println("[r3-test] inline row " + j);
        AssertJUnit.assertTrue(j.toString(), res.work.inlineAsks >= 1);
        AssertJUnit.assertTrue(j.toString(), res.work.inlineDeparted >= 1);
        AssertJUnit.assertEquals(j.toString(), 0.8, res.target, 1e-9);
    }

    @Test
    public void rowsAreDeterministic() throws Exception {
        final String a1 = determinismRow(), a2 = determinismRow();
        AssertJUnit.assertEquals(a1, a2);
        final String out = System.getProperty("r3.determinism.out");
        if (out != null) {
            java.nio.file.Files.write(java.nio.file.Paths.get(out), a1.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private String determinismRow() {
        final Game g = boltBoard(6, 20, false, true);
        final Player a = g.getPlayers().get(0);
        final JsonObject o = base();
        o.addProperty("selectWorlds", 3);
        o.addProperty("targetWorlds", 5);
        o.addProperty("maxTurns", 6);
        final R3LabelSearch r3 = new R3LabelSearch(spec(o), env(flat(0.5), null));
        final StringBuilder sb = new StringBuilder();
        sb.append(noMs(r3.priorityRow(g, a, r3.candidates(g, a, null), 77L).toJson())).append('\n');
        // and a choice row
        final Game d = doomsdayBoard();
        final PlayerControllerAi c = seat(d);
        final ChooseACardTest.Stub stub = new ChooseACardTest.Stub((x, p) ->
                ChooseACardTest.inZone(p, ZoneType.Hand, "Island") ? 0.8 : 0.4);
        final JsonObject o2 = base();
        o2.addProperty("leaf", "value");
        o2.addProperty("horizon", 2);
        final R3LabelSearch r3d = new R3LabelSearch(spec(o2), env(stub, null));
        final ChooseACard cac = new ChooseACard(ChooseACardTest.cfg("shadow"), stub, null);
        cac.setRowSearch(r3d.rowSearch(row -> 13L, (row, res) -> sb.append(noMs(res.toJson())).append('\n')));
        cac.install(c);
        castAndResolve(d, "Demonic Tutor");
        return sb.toString();
    }

    static Map<Integer, Set<Integer>> knownOf(final Player p, final Card... cs) {
        final Map<Integer, Set<Integer>> m = new HashMap<>();
        final Set<Integer> s = new HashSet<>();
        for (Card c : cs) s.add(c.getId());
        m.put(p.getId(), s);
        return m;
    }
}
