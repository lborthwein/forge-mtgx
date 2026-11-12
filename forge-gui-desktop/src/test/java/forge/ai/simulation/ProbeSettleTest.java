package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-draws-1110 (solverCfg probeSettle; card facts are test data only). The width model miscounted the cards an
 * ability moves between our library and hand when the move arrives through a trigger or as a second part of the ability.
 * Two shapes from the owner's winning Doomsday positions: an artifact sacrificed for mana whose "put into a graveyard"
 * trigger draws a card (the model counted no draw: 2026-10-10T21-32-33, every candidate drew from an empty library), and
 * a permanent that draws a card and puts itself on top of the library (the model left it on the battlefield and the
 * library one card short: 2026-10-20T19-51-02, the "winning" draw from an empty library drew that permanent).
 */
public class ProbeSettleTest extends AITest {

    @AfterMethod
    public void reset() {
        ForcedWinProbe.PROBE_SETTLE.set(false);
    }

    private static Game game(final String hand, final String battlefield, final String library) {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(hand,
                battlefield, 20, "", "Plains;Plains;Plains", 20, "human").replace(
                "humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library));
    }

    private static Card card(final Game g, final String name) {
        final Player me = g.getPlayers().get(0);
        for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library}) {
            final Card c = ForcedWinSolverTest.inZone(me, z, name);
            if (c != null) {
                return c;
            }
        }
        throw new IllegalStateException("no " + name);
    }

    private static ACard learn(final Game g, final String name, final boolean settle, final ForcedWinProbe.Stats st) {
        ForcedWinProbe.PROBE_SETTLE.set(settle);
        try {
            final ACard t = ForcedWinProbe.learn(g, g.getPlayers().get(0), card(g, name), 1, st);
            System.out.println("PROBESETTLE " + (settle ? "on " : "off ") + name + " hand=" + t.fromHand + " bf="
                    + t.onBattlefield + " dies=" + t.dies + " role=" + t.role() + " " + st.summary());
            return t;
        } finally {
            ForcedWinProbe.PROBE_SETTLE.set(false);
        }
    }

    private static Abil first(final ACard t, final AbilKind kind, final EffKind with) {
        for (Abil a : t.onBattlefield) {
            if (a.kind == kind && (with == null || a.has(with))) {
                return a;
            }
        }
        Assert.fail("no " + kind + " ability" + (with == null ? "" : " with " + with) + ": " + t.onBattlefield);
        return null;
    }

    private static int count(final Abil a, final EffKind k) {
        int n = 0;
        for (Eff e : a.effects) {
            if (e.kind == k) {
                n += e.n;
            }
        }
        return n;
    }

    private static ForcedWinSolver.Config cfg(final boolean settle) {
        final Map<String, String> kv = settle ? Map.of("probeSettle", "true", "defense", "false")
                : Map.of("defense", "false");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    private static ForcedWinPlanner.Result sameTurn(final ForcedWinSolver s, final Game g) {
        final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 1;
        lim.ms = -1L; // nodes only: the test does not depend on the host's speed
        final ForcedWinPlanner.Result r = ForcedWinPlanner.plan(pos, lim);
        System.out.println("PROBESETTLE plan " + r.summary() + " " + r.lines + " | " + pos.describe());
        return r;
    }

    private static boolean uses(final ForcedWinPlanner.Line l, final AbilKind kind, final String host) {
        return l.steps.stream().anyMatch(st -> st.kind == kind && host.equals(st.hostName));
    }

    // ------------------------------------------------------------------ the key

    @Test
    public void theKeyIsOffByDefaultAndReachesOnlyTheSeatsOwnReader() {
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).probeSettle);
        Assert.assertTrue(cfg(true).probeSettle);
        Assert.assertFalse(new ForcedWinModel.Reader().probeSettle);
        final Game g = game("", "Island", ForcedWinSolverTest.OURLIB);
        final Player me = g.getPlayers().get(0);
        Assert.assertFalse(new ForcedWinSolver(me, cfg(false)).readerForTest().probeSettle);
        final ForcedWinSolver on = new ForcedWinSolver(me, cfg(true));
        Assert.assertTrue(on.readerForTest().probeSettle);
        Assert.assertFalse(on.oppReader.probeSettle, "the defense's model of the opponent is as before");
        Assert.assertFalse(ForcedWinProbe.PROBE_SETTLE.get());
    }

    // ------------------------------------------------------------------ what the probes learn

    @Test
    public void keyOffTheTriggeredDrawAndTheReturnToTheLibraryAreNotLearned() {
        final Game g = game("", "Chromatic Star;Sensei's Divining Top;Island", ForcedWinSolverTest.OURLIB);
        final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
        final ACard star = learn(g, "Chromatic Star", false, st);
        final Abil mana = first(star, AbilKind.FLOAT, null);
        Assert.assertEquals(mana.effects.size(), 1, mana.toString());
        Assert.assertEquals(mana.effects.get(0).kind, EffKind.MANA, mana.toString());
        Assert.assertTrue(mana.sacSelf, mana.toString());
        // the sacrifice probe that was to add the trigger's draw fails in this engine: nothing is folded in
        Assert.assertTrue(star.dies.isEmpty(), star.dies.toString());
        Assert.assertTrue(st.failures >= 1, "the sacrifice probe runs and fails: " + st.summary());
        Assert.assertFalse(star.draws());
        final ACard top = learn(g, "Sensei's Divining Top", false, st);
        final Abil draw = first(top, AbilKind.ACTIVATE, EffKind.DRAW);
        Assert.assertEquals(draw.effects.size(), 1, draw.toString());
        Assert.assertEquals(count(draw, EffKind.DRAW), 1, draw.toString());
    }

    @Test
    public void keyOnTheDrawBehindAManaAbilitysSacrificeIsLearned() {
        final Game g = game("", "Chromatic Star;Chromatic Sphere;Terrarion;Lion's Eye Diamond;Lotus Petal;Island",
                ForcedWinSolverTest.OURLIB);
        final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
        // "sacrifice: add one mana" + "when this is put into a graveyard from the battlefield, draw a card"
        final ACard star = learn(g, "Chromatic Star", true, st);
        final Abil mana = first(star, AbilKind.FLOAT, null);
        Assert.assertEquals(mana.effects.size(), 2, mana.toString());
        Assert.assertEquals(mana.effects.get(0).kind, EffKind.MANA, mana.toString());
        Assert.assertEquals(mana.effects.get(0).n, 1, mana.toString());
        Assert.assertEquals(mana.effects.get(0).colors & 0b11111, 0b11111, mana.toString());
        Assert.assertEquals(mana.effects.get(1).kind, EffKind.DRAW, mana.toString());
        Assert.assertEquals(mana.effects.get(1).n, 1, mana.toString());
        Assert.assertTrue(star.dies.isEmpty(), "the ability's own run holds the draw; nothing is folded in twice");
        Assert.assertTrue(star.draws());
        Assert.assertEquals(st.failures, 0, st.summary());
        Assert.assertEquals(ForcedWinProbe.modelLibrary(mana.effects, 10, 0, 0), 9);
        // the same trigger behind another cost and amount (the permanent enters tapped: the sandbox untaps it)
        final Abil terrarion = first(learn(g, "Terrarion", true, st), AbilKind.FLOAT, null);
        Assert.assertEquals(terrarion.effects.get(0).n, 2, terrarion.toString());
        Assert.assertEquals(count(terrarion, EffKind.DRAW), 1, terrarion.toString());
        // "add one mana, draw a card" is no mana ability to the engine (it moves a library card): an activation, probed
        // as every activation is, the same off and on
        final Abil sphere = first(learn(g, "Chromatic Sphere", true, st), AbilKind.ACTIVATE, EffKind.MANA);
        Assert.assertEquals(count(sphere, EffKind.DRAW), 1, sphere.toString());
        Assert.assertEquals(sphere.effects.size(), 2, sphere.toString());
        Assert.assertEquals(first(learn(g, "Chromatic Sphere", false, st), AbilKind.ACTIVATE, EffKind.MANA).effects.toString(),
                sphere.effects.toString());
        // a cost is not an effect (the discarded hand), and a plain source is still a plain source
        final Abil led = first(learn(g, "Lion's Eye Diamond", true, st), AbilKind.FLOAT, null);
        Assert.assertEquals(led.effects.size(), 1, led.toString());
        Assert.assertTrue(led.discardHand, led.toString());
        final ACard petal = learn(g, "Lotus Petal", true, st);
        Assert.assertTrue(petal.onBattlefield.isEmpty(), petal.onBattlefield.toString());
        Assert.assertTrue(petal.sourceSac);
    }

    @Test
    public void keyOnACardThatPutsItselfIntoOurLibraryIsLearned() {
        final Game g = game("", "Sensei's Divining Top;Elixir of Immortality;Island;Island;Island",
                ForcedWinSolverTest.OURLIB);
        final Player me = g.getPlayers().get(0);
        final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
        // "draw a card, then put this on top of its owner's library": one draw, then the card itself, on top
        final ACard top = learn(g, "Sensei's Divining Top", true, st);
        final Abil draw = first(top, AbilKind.ACTIVATE, EffKind.DRAW);
        Assert.assertEquals(draw.effects.size(), 2, draw.toString());
        Assert.assertEquals(draw.effects.get(0).kind, EffKind.DRAW, draw.toString());
        Assert.assertEquals(draw.effects.get(0).n, 1, draw.toString());
        Assert.assertEquals(draw.effects.get(1).kind, EffKind.SELF_TO_LIBRARY, draw.toString());
        Assert.assertEquals(draw.effects.get(1).n, ForcedWinModel.LIB_TOP, draw.toString());
        Assert.assertEquals(ForcedWinProbe.modelLibrary(draw.effects, 10, 0, 0), 10, "a draw and a return: the same size");
        // "shuffle this and your graveyard into their owner's library": the card leaves the battlefield for the library
        g.getAction().moveToGraveyard(me.getCardsIn(ZoneType.Library).get(0), null);
        g.getAction().moveToGraveyard(me.getCardsIn(ZoneType.Library).get(0), null);
        final ACard elixir = learn(g, "Elixir of Immortality", true, st);
        final Abil shuffle = first(elixir, AbilKind.ACTIVATE, EffKind.SELF_TO_LIBRARY);
        Assert.assertTrue(shuffle.has(EffKind.SHUFFLE), shuffle.toString());
        Assert.assertTrue(shuffle.has(EffKind.GRAVEYARD_TO_LIBRARY), shuffle.toString());
        Assert.assertEquals(ForcedWinProbe.modelLibrary(shuffle.effects, 8, 0, 2), 11, shuffle.toString());
        // off, the same ability leaves the card where it was
        final ACard elixirOff = learn(g, "Elixir of Immortality", false, st);
        Assert.assertFalse(first(elixirOff, AbilKind.ACTIVATE, EffKind.SHUFFLE).has(EffKind.SELF_TO_LIBRARY));
        // a spell that shuffles itself into our library as it resolves: not a card for our graveyard
        final Game h = game("Beacon of Tomorrows", "Island", ForcedWinSolverTest.OURLIB);
        final ACard beacon = learn(h, "Beacon of Tomorrows", true, st);
        Assert.assertFalse(beacon.fromHand.isEmpty());
        for (Abil cast : beacon.fromHand) {
            Assert.assertFalse(cast.unplayable, cast.toString());
            Assert.assertTrue(cast.has(EffKind.SELF_TO_LIBRARY), cast.toString());
            Assert.assertTrue(cast.has(EffKind.SHUFFLE), cast.toString());
            Assert.assertEquals(ForcedWinProbe.modelLibrary(cast.effects, 10, 0, 0), 11, cast.toString());
        }
        for (Abil cast : learn(h, "Beacon of Tomorrows", false, st).fromHand) {
            Assert.assertFalse(cast.has(EffKind.SELF_TO_LIBRARY), cast.toString());
        }
    }

    // ------------------------------------------------------------------ planned lines replay

    /**
     * Thassa's Oracle wins with at most two cards in our library; it holds three, and the only draw is the trigger of the
     * artifact sacrificed for mana. Off, the model sees no draw and no line this turn. On, it plans the line through the
     * artifact, and the line wins in the engine.
     */
    @Test
    public void aLineThroughTheSacrificedArtifactsDrawReplays() {
        final String hand = "Thassa's Oracle", bf = "Chromatic Star;Island;Island", lib = "Island;Island;Island";
        final Game off = game(hand, bf, lib);
        Assert.assertTrue(sameTurn(new ForcedWinSolver(off.getPlayers().get(0), cfg(false)), off).lines.isEmpty(),
                "off: the model has no draw, so no line this turn");
        final Game g = game(hand, bf, lib);
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinPlanner.Result r = sameTurn(s, g);
        Assert.assertFalse(r.lines.isEmpty(), r.summary());
        final ForcedWinPlanner.Line line = r.lines.get(0);
        Assert.assertTrue(uses(line, AbilKind.FLOAT, "Chromatic Star"), line.toString());
        final ForcedWinSolver.Eval e = s.evaluate(g, line, 7L, Long.MAX_VALUE);
        Assert.assertEquals(e.pUnopposed, 1.0, e.toString());
        Assert.assertEquals(e.pBelief, 1.0, e.toString());
    }

    /**
     * The same count from the other side. Thassa's Oracle in hand, three cards in our library, and a permanent that draws
     * a card and puts itself on top. Off, the model counts two cards left after the draw and proposes the Oracle; in the
     * engine the permanent is the third card and the line does not win. On, the model counts three and proposes nothing.
     */
    @Test
    public void keyOnTheLibraryIsNotOneCardShortAfterTheCardReturnsToIt() {
        final String hand = "Thassa's Oracle", bf = "Sensei's Divining Top;Island;Island", lib = "Island;Island;Island";
        final Game off = game(hand, bf, lib);
        final ForcedWinSolver so = new ForcedWinSolver(off.getPlayers().get(0), cfg(false));
        final ForcedWinPlanner.Result ro = sameTurn(so, off);
        Assert.assertFalse(ro.lines.isEmpty(), "off: the model's library is one card short: " + ro.summary());
        Assert.assertTrue(uses(ro.lines.get(0), AbilKind.ACTIVATE, "Sensei's Divining Top"), ro.lines.get(0).toString());
        final ForcedWinSolver.Eval eo = so.evaluate(off, ro.lines.get(0), 7L, Long.MAX_VALUE);
        Assert.assertEquals(eo.pUnopposed, 0.0, "off: the line does not win in the engine: " + eo);
        Assert.assertTrue(eo.failures.contains("no-win"), eo.toString());
        final Game g = game(hand, bf, lib);
        final ForcedWinPlanner.Result r = sameTurn(new ForcedWinSolver(g.getPlayers().get(0), cfg(true)), g);
        Assert.assertTrue(r.lines.isEmpty(), "on: three cards stay in the library: " + r.lines);
    }

    /** On, a line through that permanent replays: it draws the Oracle from a known top and is the second card left. */
    @Test
    public void aLineThroughTheCardThatReturnsToTheLibraryReplays() {
        final Game g = game("", "Sensei's Divining Top;Island;Island", "Thassa's Oracle;Island");
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, cfg(true));
        s.notePlaced(me.getCardsIn(ZoneType.Library)); // the seat knows its top (it put it there): pile memory
        final ForcedWinPlanner.Result r = sameTurn(s, g);
        Assert.assertFalse(r.lines.isEmpty(), r.summary());
        final ForcedWinPlanner.Line line = r.lines.get(0);
        Assert.assertTrue(uses(line, AbilKind.ACTIVATE, "Sensei's Divining Top"), line.toString());
        final ForcedWinSolver.Eval e = s.evaluate(g, line, 7L, Long.MAX_VALUE);
        Assert.assertEquals(e.pUnopposed, 1.0, e.toString());
        Assert.assertEquals(e.pBelief, 1.0, e.toString());
    }

    /**
     * The shape of the owner's position 2026-10-20T19-51-02. A planeswalker in play wins when we would draw from an
     * empty library; its ability "target player mills two cards, draw a card" is aimed at the opponent after the
     * permanent drew our last card and put itself on top. Off, the model's library is empty and that draw "wins"; in the
     * engine the draw is the permanent. On, that line is not planned, and every line that is planned wins in the engine
     * (aimed at ourselves, the ability mills the permanent first).
     */
    @Test
    public void theDrawAfterTheCardReturnedToTheLibraryIsThatCard() {
        final String bf = "Sensei's Divining Top;Jace, Wielder of Mysteries|Counters:LOYALTY=4;Island";
        for (boolean settle : new boolean[] {false, true}) {
            // the models are learned as in a game: when the cards are first seen, with cards in the library
            final ForcedWinModel.Reader reader = new ForcedWinModel.Reader();
            final Game rich = game("", bf, ForcedWinSolverTest.OURLIB);
            new ForcedWinSolver(rich.getPlayers().get(0), cfg(settle), reader).readPosition(rich, null);
            final Game g = game("", bf, "Island");
            final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), cfg(settle), reader);
            final ForcedWinPlanner.Result r = sameTurn(s, g);
            Assert.assertFalse(r.lines.isEmpty(), settle + " " + r.summary());
            final List<String> lost = new ArrayList<>();
            boolean drawsThePermanent = false;
            for (ForcedWinPlanner.Line line : r.lines) {
                final ForcedWinSolver.Eval e = s.evaluate(g, line, 7L, Long.MAX_VALUE);
                System.out.println("PROBESETTLE " + (settle ? "on " : "off ") + e);
                final int n = line.steps.size();
                final boolean topThenOpp = n >= 2 && "Sensei's Divining Top".equals(line.steps.get(n - 2).hostName)
                        && "opp".equals(line.steps.get(n - 1).targetVariant);
                drawsThePermanent |= topThenOpp;
                if (e.pUnopposed < 1.0) {
                    lost.add(line + " " + e.failures);
                    Assert.assertTrue(topThenOpp, "only that line fails: " + line);
                    Assert.assertTrue(e.failures.contains("no-win"), e.toString());
                }
            }
            if (settle) {
                Assert.assertFalse(drawsThePermanent, "on: not planned: " + r.lines);
                Assert.assertTrue(lost.isEmpty(), "on: every planned line wins in the engine: " + lost);
            } else {
                Assert.assertTrue(drawsThePermanent, "off: planned: " + r.lines);
                Assert.assertEquals(lost.size(), 1, "off: and it does not win in the engine: " + lost);
            }
        }
    }
}
