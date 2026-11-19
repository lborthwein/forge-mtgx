package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * line-own-target-1119 (plans.solverCfg lineOwnTarget; off by default): the live seat's follower breaks a line before a
 * step whose harmful effect would land on a permanent it CONTROLS that the line did not plan, or whose destroy cannot
 * destroy its target; the seat then searches the position as after any broken line.
 *
 * <p>Owner reports 2026-11-19T01-05-37 (game 589277be: the proven line {@code [Valki, Harvester of Misery, PASS, Chain
 * Lightning->[P0], Abrade cac[mode:0], ATTACK]}; live, the opponent's copied Chain Lightning had marked 3 damage on our
 * Harvester, the only creature, and Abrade's 3 damage, its target picked by Forge AI as it was cast, killed it) and
 * 2026-11-19T01-14-57 (game 38a8a0e4: {@code [Valki, Snuff Out->[Ulamog], Faerie Mastermind, PASS, ATTACK]}; Snuff Out
 * paid 4 life to destroy our own indestructible Ulamog, which the opponent owned and we controlled). Only APIs of the
 * baseline tree are used (the key goes in as a spec string): on the baseline every key-on test that expects a break
 * fails by assertion, and every key-off test passes.
 */
public class LineOwnTargetTest extends SimulationTest {

    private Game game;
    private Player me, opp;

    private void setUp() {
        game = initAndCreateGame();
        me = game.getPlayers().get(0);
        opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private Card ours(final String name) {
        return addCard(name, me);
    }

    private Card theirs(final String name) {
        return addCard(name, opp);
    }

    /** {@code c} (owned by its player) comes under {@code to}'s control, as Fallen Shinobi's or Control Magic's does. */
    private void control(final Card c, final Player to) {
        c.addTempController(to, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(c);
        game.getAction().checkStateEffects(true);
        Assert.assertEquals(c.getController(), to);
        Assert.assertNotEquals(c.getOwner(), to);
    }

    /** The card's spell ability whose cost names {@code costHas} (null: its last one), as an ENGINE step. */
    private static Step engine(final Card c, final Player me, final String costHas, final String... refs) {
        SpellAbility sa = null;
        for (SpellAbility x : c.getAllPossibleAbilities(me, true)) {
            final String ck = x.getPayCosts() == null ? "" : x.getPayCosts().toString();
            if (costHas == null || ck.contains(costHas)) {
                sa = x;
            }
        }
        Assert.assertNotNull(sa, c.getName() + " has a spell ability (cost " + costHas + ")");
        final Step st = Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), sa.getDescription(),
                sa.getPayCosts() == null ? "" : sa.getPayCosts().toString(), sa.isSpell());
        for (String r : refs) {
            st.targetRefs.add(r);
        }
        return st;
    }

    private static Step withMode(final Step st, final String mode) {
        st.cac = new ArrayList<>(List.of(mode));
        st.cacPlayed = new ArrayList<>(List.of(mode));
        return st;
    }

    private static Line line(final Step... steps) {
        return new Line(new ArrayList<>(List.of(steps)), List.of(), 0, 20, 0, 0, List.of(), "test");
    }

    /** The live seat's solver (a scoped driver), as the plan search's: follows only; engineModeChoice as live. */
    private ForcedWinSolver follower(final boolean keyOn) {
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("act", "false");
        cfg.addProperty("engineModeChoice", "true"); // live: plans.solverCfg.engineModeChoice=true
        if (keyOn) {
            cfg.addProperty("lineOwnTarget", "true");
        }
        final ForcedWinSolver solver = new SolverDriver(me, SolverDriver.config(cfg)).solver();
        solver.knownTopCount(game);
        return solver;
    }

    /** The follower's first decision on {@code l}: the action it plays (null = none: the line broke or ended). */
    private SpellAbility first(final boolean keyOn, final Line l, final int[] replans) {
        final ForcedWinSolver solver = follower(keyOn);
        solver.adopt(l);
        final ForcedWinSolver.Decision d = solver.decide(game);
        replans[0] = solver.replans;
        return d.action;
    }

    private static boolean plays(final SpellAbility a, final Card host) {
        return a != null && a.getHostCard() != null && a.getHostCard().getId() == host.getId();
    }

    // ------------------------------------------------------------------ report 1: Abrade at our damaged Harvester

    /** Game 589277be at turn 15: Harvester (ours, 3 damage marked by the copy), no creature of theirs, an artifact of theirs. */
    private Card abradeReport() {
        setUp();
        final Card harvester = ours("Harvester of Misery");
        harvester.setDamage(3);
        ours("Mountain");
        ours("Mountain");
        theirs("Sol Ring"); // two modes offered, as live ("line-modes card=Abrade modes=1/2")
        final Card abrade = addCardToZone("Abrade", me, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        Assert.assertTrue(harvester.isInPlay());
        return abrade;
    }

    @Test
    public void reportAbradeKeyOffCastsItWithOnlyOurCreatureToHit() {
        final Card abrade = abradeReport();
        final int[] rp = {0};
        final SpellAbility a = first(false, line(withMode(engine(abrade, me, null), "mode:0")), rp);
        Assert.assertTrue(plays(a, abrade), "key off: the follower casts Abrade as today: " + a);
        // the bug: its recorded mode (3 damage to target creature) has one legal target, our own Harvester
        final List<AbilitySub> modes = CharmEffect.makePossibleOptions(a);
        Assert.assertEquals(modes.size(), 2, String.valueOf(modes));
        final List<GameEntity> cands = modes.get(0).getTargetRestrictions().getAllCandidates(modes.get(0));
        Assert.assertEquals(cands.size(), 1, String.valueOf(cands));
        Assert.assertEquals(((Card) cands.get(0)).getName(), "Harvester of Misery");
        Assert.assertEquals(((Card) cands.get(0)).getController(), me);
        Assert.assertEquals(rp[0], 0);
    }

    @Test
    public void reportAbradeKeyOnBreaksTheLine() {
        final Card abrade = abradeReport();
        final int[] rp = {0};
        final SpellAbility a = first(true, line(withMode(engine(abrade, me, null), "mode:0")), rp);
        Assert.assertFalse(plays(a, abrade), "key on: Abrade is not cast at our own creature: " + a);
        Assert.assertEquals(rp[0], 1, "the line broke (the seat searches the position)");
    }

    /** Forge AI chooses the mode (none recorded): refused only when every mode is; here destroy Sol Ring is fine. */
    @Test
    public void keyOnAnOpenModeWithAnOpposingTargetStillPlays() {
        final Card abrade = abradeReport();
        final int[] rp = {0};
        Assert.assertTrue(plays(first(true, line(engine(abrade, me, null)), rp), abrade));
        Assert.assertEquals(rp[0], 0);
    }

    // ------------------------------------------------------------------ report 2: Snuff Out at our indestructible Ulamog

    /** Game 38a8a0e4 at turn 10: Ulamog owned by the opponent, controlled by us; Snuff Out by its 4-life cost. */
    private Card snuffReport() {
        setUp();
        final Card ulamog = theirs("Ulamog, the Ceaseless Hunger");
        control(ulamog, me);
        ours("Swamp");
        return ulamog;
    }

    @Test
    public void reportSnuffOutKeyOffDestroysNothing() {
        final Card ulamog = snuffReport();
        final Card snuff = addCardToZone("Snuff Out", me, ZoneType.Hand);
        final int[] rp = {0};
        final SpellAbility a = first(false, line(engine(snuff, me, "life", "0:C" + ulamog.getId())), rp);
        Assert.assertTrue(plays(a, snuff), "key off: the follower casts Snuff Out as today: " + a);
        Assert.assertEquals(a.getTargets().getFirstTargetedCard(), ulamog);
        Assert.assertFalse(ulamog.canBeDestroyed(), "indestructible: the destroy does nothing");
    }

    @Test
    public void reportSnuffOutKeyOnBreaksTheLine() {
        final Card ulamog = snuffReport();
        final Card snuff = addCardToZone("Snuff Out", me, ZoneType.Hand);
        final int[] rp = {0};
        final SpellAbility a = first(true, line(engine(snuff, me, "life", "0:C" + ulamog.getId())), rp);
        Assert.assertFalse(plays(a, snuff), "key on: no Snuff Out at our own indestructible Ulamog: " + a);
        Assert.assertEquals(rp[0], 1);
    }

    /** A destroy that cannot destroy breaks whoever controls the target (their indestructible creature). */
    @Test
    public void keyOnADestroyOfTheirIndestructibleCreatureBreaks() {
        setUp();
        final Card myr = theirs("Darksteel Myr");
        ours("Swamp");
        final Card snuff = addCardToZone("Snuff Out", me, ZoneType.Hand);
        final int[] rp = {0};
        final Line l = line(engine(snuff, me, "life", "0:C" + myr.getId()));
        Assert.assertTrue(plays(first(false, l, rp), snuff), "key off: cast as today");
        Assert.assertFalse(plays(first(true, l, rp), snuff), "key on: a destroy with no effect is not cast");
        Assert.assertEquals(rp[0], 1);
    }

    /** An open destroy (Abrade's artifact mode) whose only legal target is indestructible breaks. */
    @Test
    public void keyOnAnOpenDestroyWithOnlyIndestructibleTargetsBreaks() {
        setUp();
        theirs("Grizzly Bears"); // the damage mode is offered too, so the recorded mode set is replayed
        theirs("Darksteel Citadel");
        ours("Mountain");
        ours("Mountain");
        final Card abrade = addCardToZone("Abrade", me, ZoneType.Hand);
        final int[] rp = {0};
        final Line l = line(withMode(engine(abrade, me, null), "mode:1"));
        Assert.assertTrue(plays(first(false, l, rp), abrade), "key off: cast as today");
        Assert.assertFalse(plays(first(true, l, rp), abrade), "key on: destroy target artifact can destroy nothing");
        Assert.assertEquals(rp[0], 1);
    }

    // ------------------------------------------------------------------ "ours" is by controller, never owner

    @Test
    public void keyOnAStolenCreatureWeControlIsOurs() {
        setUp();
        final Card bears = theirs("Grizzly Bears");
        control(bears, me);
        theirs("Sol Ring");
        ours("Mountain");
        ours("Mountain");
        final Card abrade = addCardToZone("Abrade", me, ZoneType.Hand);
        final int[] rp = {0};
        final Line l = line(withMode(engine(abrade, me, null), "mode:0"));
        Assert.assertTrue(plays(first(false, l, rp), abrade), "key off: cast as today");
        Assert.assertFalse(plays(first(true, l, rp), abrade), "key on: 3 damage with only the creature we control to hit");
        Assert.assertEquals(rp[0], 1);
    }

    @Test
    public void keyOnACreatureWeOwnThatTheyControlIsTheirs() {
        setUp();
        final Card bears = ours("Grizzly Bears");
        control(bears, opp);
        theirs("Sol Ring");
        ours("Mountain");
        ours("Mountain");
        final Card abrade = addCardToZone("Abrade", me, ZoneType.Hand);
        final int[] rp = {0};
        Assert.assertTrue(plays(first(true, line(withMode(engine(abrade, me, null), "mode:0")), rp), abrade),
                "key on: the creature they control is theirs: Abrade is cast");
        Assert.assertEquals(rp[0], 0);
    }

    /**
     * A recorded (ENGINE) target on a permanent we control with no payoff breaks: Reaver Ambush (exile target creature
     * with power 3 or less, nothing else) at our own Esper Sentinel. Swords to Plowshares at it (the fc8c3611 line) now
     * plays: its life gain goes to the target's controller, us, and the ability's own benefit is a payoff (review 1 of
     * #251, S1 2; the seat decides such a line).
     */
    @Test
    public void keyOnARecordedRemovalOfOurOwnCreatureBreaks() {
        setUp();
        final Card sentinel = ours("Esper Sentinel");
        theirs("Grizzly Bears");
        ours("Plains");
        ours("Swamp");
        ours("Swamp");
        ours("Swamp");
        final Card ambush = addCardToZone("Reaver Ambush", me, ZoneType.Hand);
        final int[] rp = {0};
        final Line l = line(engine(ambush, me, null, "0:C" + sentinel.getId()));
        Assert.assertTrue(plays(first(false, l, rp), ambush), "key off: cast as today");
        Assert.assertFalse(plays(first(true, l, rp), ambush), "key on: no exile of our own Esper Sentinel");
        Assert.assertEquals(rp[0], 1);
        // a stolen creature we control counts as ours the same way
        final Card bears = theirs("Grizzly Bears");
        control(bears, me);
        Assert.assertFalse(plays(first(true, line(engine(ambush, me, null, "0:C" + bears.getId())), rp), ambush));
        Assert.assertEquals(rp[0], 1);
        // Swords to Plowshares at our own Sentinel gains us its power in life: the line plays
        final Card swords = addCardToZone("Swords to Plowshares", me, ZoneType.Hand);
        final SpellAbility a = first(true, line(engine(swords, me, null, "0:C" + sentinel.getId())), rp);
        Assert.assertTrue(plays(a, swords), "key on: Swords' life gain is its own payoff: " + a);
        Assert.assertEquals(rp[0], 0);
    }

    // ------------------------------------------------------------------ intended self-targets still play

    /** A death payoff: Lightning Bolt at our own Memnite with Blood Artist out is the line's plan. */
    @Test
    public void keyOnADeathPayoffOnOurOwnCreatureStillPlays() {
        setUp();
        final Card memnite = ours("Memnite");
        ours("Blood Artist");
        ours("Mountain");
        final Card bolt = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        final int[] rp = {0};
        final SpellAbility a = first(true, line(engine(bolt, me, null, "0:C" + memnite.getId())), rp);
        Assert.assertTrue(plays(a, bolt), "key on: Bolt at our own Memnite with Blood Artist out is cast: " + a);
        Assert.assertEquals(a.getTargets().getFirstTargetedCard(), memnite);
        Assert.assertEquals(rp[0], 0);
    }

    /** A blink of our own creature (Ephemerate: target creature you control, then back). */
    @Test
    public void keyOnABlinkOfOurOwnCreatureStillPlays() {
        setUp();
        final Card memnite = ours("Memnite");
        ours("Plains");
        final Card eph = addCardToZone("Ephemerate", me, ZoneType.Hand);
        final int[] rp = {0};
        Assert.assertTrue(plays(first(true, line(engine(eph, me, null, "0:C" + memnite.getId())), rp), eph));
        Assert.assertEquals(rp[0], 0);
    }

    /**
     * sdcc-probeobj-1111's proven line (ProbeObjectsTest BOUNCE): Unsummon on our own Memnite (the planner's object,
     * {@link Step#objTarget}), recast it, then the storm spell. Followed by the live seat with the key on, the Unsummon
     * step is still cast at Memnite.
     */
    @Test
    public void keyOnAProbeObjectsLineOnOurOwnPermanentStillPlays() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ProbeObjectsTest.BOUNCE);
        final Player p0 = g.getPlayers().get(0);
        final ForcedWinSolver planner = new ForcedWinSolver(p0, ProbeObjectsTest.cfg(true));
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : planner.propose(g)) {
            best = best == null || p.pBelief > best.pBelief ? p : best;
        }
        Assert.assertNotNull(best, "a proven line: " + planner.summary());
        final Card memnite = ForcedWinSolverTest.inZone(p0, ZoneType.Battlefield, "Memnite");
        final Map<String, String> kv = new HashMap<>();
        kv.put("probeObjects", "true");
        kv.put("act", "false");
        kv.put("lineOwnTarget", "true");
        final ForcedWinSolver live = new ForcedWinSolver(p0, ForcedWinSolver.Config.fromProps(kv::get));
        live.liveSeat = true;
        live.adopt(best.line);
        SpellAbility unsummon = null;
        for (int i = 0; i < 8 && unsummon == null; i++) {
            final ForcedWinSolver.Decision d = live.decide(g);
            Assert.assertNotNull(d.action, "the line goes on to Unsummon (" + best.text + "), replans=" + live.replans);
            if (d.action.getHostCard().getName().equals("Unsummon")) {
                unsummon = d.action;
            } else {
                Assert.assertTrue(ForcedWinProbe.play(g, p0, d.action), String.valueOf(d.action));
                ForcedWinProbe.resolveAll(g);
            }
        }
        Assert.assertNotNull(unsummon, best.text);
        Assert.assertEquals(unsummon.getTargets().getFirstTargetedCard(), memnite, "at the planned object");
        Assert.assertEquals(live.replans, 0);
    }

    // ------------------------------------------------------------------ a planned target that is gone breaks

    /** The line's opposing target died before its step: the line breaks (key on or off); it never retargets ours. */
    @Test
    public void aPlannedOpposingTargetThatIsGoneBreaks() {
        for (boolean on : new boolean[] {false, true}) {
            setUp();
            final Card bears = theirs("Grizzly Bears");
            final Card memnite = ours("Memnite");
            ours("Mountain");
            final Card bolt = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
            final Line l = line(engine(bolt, me, null, "0:C" + bears.getId()));
            game.getAction().moveTo(ZoneType.Graveyard, bears, null, null);
            final int[] rp = {0};
            final SpellAbility a = first(on, l, rp);
            Assert.assertFalse(plays(a, bolt), "key " + on + ": no Bolt at another target (" + memnite + "): " + a);
            Assert.assertEquals(rp[0], 1, "key " + on + ": the line broke");
        }
    }
}
