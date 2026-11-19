package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * line-own-target-1119, review 1 of #251 (Astra, HOLD, 7 S1s): the shared rule ({@link LineOwnTarget}) both keys read,
 * the line follower's ({@code plans.solverCfg.lineOwnTarget}, a recorded target: {@link LineOwnTarget#refuse}) and the
 * policy search's ({@code ownHarm}, every target open: {@link LookaheadSearch#ownHarmWhy}). The coordinator's ruling: the
 * guard refuses only pure, payoff-free harm to a permanent we control; when unsure it keeps the action.
 *
 * <p>The reviewer's eleven scratch assertions (review/251/ReviewScratchOwnTargetTest.java and ReviewScratchReadOnlyTest),
 * each a false refusal or a mutation on ab126fdad63, now pass as "kept" (and "unchanged"); the reviewer's controls stay
 * kept; and what must still be refused is: game 1's Abrade (3 damage, no payoff, at our own creature), game 2's Snuff Out
 * (a destroy at our own indestructible Ulamog, its only effect) and the idle games' Tear Asunder at our own permanents.
 * Review 1b (architect-o): the S1 2 exception (the ability's own follow-up benefit: Swords' and Oust's life, Beast
 * Within's token, Cleansing Wildfire's land and card, and a destroy with other effects) holds for the search only; with a
 * recorded target the line check refuses them.
 */
public class LineOwnTargetReviewTest extends SimulationTest {
    private Game g;
    private Player me, opp;

    private void board() {
        g = initAndCreateGame();
        me = g.getPlayers().get(0);
        opp = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private Card ours(final String n) {
        return addCard(n, me);
    }

    private SpellAbility ability(final String n, final ApiType api) {
        final Card c = addCardToZone(n, me, ZoneType.Hand);
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.getApi() == api) {
                sa.setActivatingPlayer(me);
                return sa;
            }
        }
        throw new AssertionError("No " + api + " for " + n);
    }

    private void kept(final String tag, final SpellAbility sa) {
        final String why = LookaheadSearch.ownHarmWhy(me, sa);
        System.out.println("[own-target-review] " + tag + " => " + why);
        Assert.assertNull(why, tag);
    }

    /** Kept with every target open (ownHarm) and with {@code target} recorded (lineOwnTarget). */
    private void keptTarget(final String tag, final SpellAbility sa, final Card target) {
        final String open = LookaheadSearch.ownHarmWhy(me, sa);
        sa.getTargets().add(target);
        final String recorded = LineOwnTarget.refuse(me, sa, null, null);
        sa.resetTargets();
        System.out.println("[own-target-review] " + tag + " open=" + open + " recorded=" + recorded);
        Assert.assertNull(open, tag + " open");
        Assert.assertNull(recorded, tag + " recorded");
    }

    /** Refused open and with {@code target} recorded; returns the open reason. */
    private String refusedTarget(final String tag, final SpellAbility sa, final Card target) {
        final String open = LookaheadSearch.ownHarmWhy(me, sa);
        sa.getTargets().add(target);
        final String recorded = LineOwnTarget.refuse(me, sa, null, null);
        sa.resetTargets();
        System.out.println("[own-target-review] " + tag + " open=" + open + " recorded=" + recorded);
        Assert.assertNotNull(open, tag + " open");
        Assert.assertNotNull(recorded, tag + " recorded");
        return open;
    }

    // ------------------------------------------------------------------ S1 1: delayed blinks are not harm

    @Test
    public void s1DelayedChannelBlinkIsKept() {
        board();
        final Card c = ours("Mulldrifter");
        keptTarget("Touch the Spirit Realm channel: Mulldrifter back at the next end step",
                ability("Touch the Spirit Realm", ApiType.ChangeZone), c);
    }

    @Test
    public void s1DelayedBlinkSpellIsKept() {
        board();
        final Card c = ours("Mulldrifter");
        keptTarget("Turn to Mist: Mulldrifter back at end of turn", ability("Turn to Mist", ApiType.ChangeZone), c);
    }

    /** The live follower plays a Turn to Mist line at our own Mulldrifter with the key on, as with it off. */
    @Test
    public void s1TheFollowerPlaysADelayedBlinkLine() {
        board();
        final Card target = ours("Mulldrifter");
        ours("Plains");
        ours("Plains");
        final SpellAbility sa = ability("Turn to Mist", ApiType.ChangeZone);
        final Step st = Step.engine(AbilKind.ENGINE, sa.getHostCard().getId(), sa.getHostCard().getName(), sa.getDescription(),
                sa.getPayCosts().toString(), true);
        st.targetRefs.add("0:C" + target.getId());
        final Line line = new Line(new ArrayList<>(List.of(st)), List.of(), 0, 20, 0, 0, List.of(), "review");
        for (boolean on : new boolean[] {false, true}) {
            final JsonObject cfg = new JsonObject();
            cfg.addProperty("act", "false");
            cfg.addProperty("lineOwnTarget", String.valueOf(on));
            final ForcedWinSolver s = new SolverDriver(me, SolverDriver.config(cfg)).solver();
            s.knownTopCount(g);
            s.adopt(line);
            final ForcedWinSolver.Decision d = s.decide(g);
            System.out.println("[own-target-review] follower delayed blink on=" + on + " action=" + d.action + " replans=" + s.replans);
            Assert.assertNotNull(d.action, "the delayed blink is followed with the key " + on);
            Assert.assertEquals(s.replans, 0);
        }
    }

    // ------------------------------------------------------------------ S1 2: the ability's own payoff (the search only)

    /**
     * Review 1b of #251 (architect-o): the ability's own follow-up benefit pays for the harm in the search only. Kept with
     * every target open ({@code ownHarm}), refused with {@code target} recorded ({@code lineOwnTarget}); returns the
     * recorded reason.
     */
    private String searchOnly(final String tag, final SpellAbility sa, final Card target) {
        final String open = LookaheadSearch.ownHarmWhy(me, sa);
        sa.getTargets().add(target);
        final String recorded = LineOwnTarget.refuse(me, sa, null, null);
        sa.resetTargets();
        System.out.println("[own-target-review] " + tag + " open=" + open + " recorded=" + recorded);
        Assert.assertNull(open, tag + " open: the search keeps it");
        Assert.assertNotNull(recorded, tag + " recorded: a line step breaks");
        return recorded;
    }

    @Test
    public void s1SwordsLifeGainKeepsItInTheSearchNotInALine() {
        board();
        me.setLife(2, null);
        final Card c = ours("Grizzly Bears");
        final String why = searchOnly("Swords on our Bears gains us 2 life", ability("Swords to Plowshares", ApiType.ChangeZone), c);
        Assert.assertTrue(why.startsWith("remove on our own Grizzly Bears"), why);
        // at an opposing creature it stays in both
        final Card theirs = addCard("Grizzly Bears", opp);
        final SpellAbility swords = ability("Swords to Plowshares", ApiType.ChangeZone);
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me, swords));
        swords.getTargets().add(theirs);
        Assert.assertNull(LineOwnTarget.refuse(me, swords, null, null));
        swords.resetTargets();
    }

    /** Beast Within at our own indestructible land: kept in the search (its token), breaks a line (it destroys nothing). */
    @Test
    public void s1BeastWithinTokenKeepsItInTheSearchNotInALine() {
        board();
        final Card c = ours("Darksteel Citadel");
        final String why = searchOnly("Beast Within: a Beast for us, the Citadel stays", ability("Beast Within", ApiType.Destroy), c);
        Assert.assertTrue(why.startsWith("destroy has no effect on Darksteel Citadel"), why);
    }

    @Test
    public void s1CleansingWildfireRampKeepsItInTheSearchNotInALine() {
        board();
        final Card c = ours("Darksteel Citadel");
        final String why = searchOnly("Cleansing Wildfire: a land and a card for us",
                ability("Cleansing Wildfire", ApiType.Destroy), c);
        Assert.assertTrue(why.startsWith("destroy has no effect on Darksteel Citadel"), why);
        // a destructible land of ours: the line refuses the destroy itself
        final Card forest = ours("Forest");
        final String why2 = searchOnly("Cleansing Wildfire at our Forest", ability("Cleansing Wildfire", ApiType.Destroy), forest);
        Assert.assertTrue(why2.startsWith("destroy on our own Forest"), why2);
    }

    // ------------------------------------------------------------------ S1 3: +X/-Y that the target survives

    @Test
    public void s1APositivePowerPumpItSurvivesIsKept() {
        board();
        opp.setLife(9, null);
        final Card c = ours("Colossal Dreadmaw");
        keptTarget("Spatial Contortion: a surviving 9/3", ability("Spatial Contortion", ApiType.Pump), c);
    }

    /** The same pump at a creature it kills (our 2/2, or our damaged 6/6) is a shrink: refused. */
    @Test
    public void aPositivePowerPumpThatKillsIsRefused() {
        board();
        final Card bears = ours("Grizzly Bears");
        final String why = refusedTarget("Spatial Contortion at our 2/2", ability("Spatial Contortion", ApiType.Pump), bears);
        Assert.assertTrue(why.startsWith("shrink with only our own to target"), why);
        board();
        final Card maw = ours("Colossal Dreadmaw");
        maw.setDamage(3);
        refusedTarget("Spatial Contortion at our 6/6 with 3 damage", ability("Spatial Contortion", ApiType.Pump), maw);
    }

    // ------------------------------------------------------------------ S1 4: a bounce with another permanent's enters trigger

    @Test
    public void s1ABounceWithImpactTremorsIsKept() {
        board();
        opp.setLife(1, null);
        ours("Impact Tremors");
        final Card c = ours("Memnite");
        keptTarget("Unsummon Memnite, recast it through Impact Tremors", ability("Unsummon", ApiType.ChangeZone), c);
    }

    @Test
    public void aBounceWithNoEntersTriggerIsRefused() {
        board();
        final Card c = ours("Grizzly Bears");
        final String why = refusedTarget("Unsummon our vanilla Bears", ability("Unsummon", ApiType.ChangeZone), c);
        Assert.assertTrue(why.startsWith("bounce with only our own to target"), why);
    }

    // ------------------------------------------------------------------ S1 5: player targets are outside the guard

    @Test
    public void s1APlayerTargetIsNeverRefused() {
        board();
        opp.setLife(1, null);
        addCard("Leyline of Sanctity", opp);
        ours("Zulaport Cutthroat");
        ours("Grizzly Bears");
        g.getAction().checkStateEffects(true);
        final SpellAbility sa = ability("Diabolic Edict", ApiType.Sacrifice);
        Assert.assertTrue(sa.canTarget(me));
        Assert.assertFalse(sa.canTarget(opp));
        kept("Diabolic Edict at us: a sacrifice for Zulaport's drain", sa);
    }

    // ------------------------------------------------------------------ S1 6: a spell on the stack is not our permanent

    @Test
    public void s1OurSpellOnTheStackIsNeverRefused() {
        board();
        addCard("Chalice of the Void", opp);
        final SpellAbility bolt = ability("Lightning Bolt", ApiType.DealDamage);
        bolt.getTargets().add(opp);
        bolt.setHostCard(g.getAction().moveToStack(bolt.getHostCard(), bolt));
        g.getStack().add(bolt);
        final SpellAbility sa = ability("Unsubstantiate", ApiType.ChangeZone);
        Assert.assertTrue(sa.canTarget(bolt.getHostCard()));
        kept("Unsubstantiate our own Bolt (a Chalice counter trigger)", sa);
    }

    // ------------------------------------------------------------------ S1 7: a check changes nothing

    /** The state of an ability and every part under it: activator, targets, SVars (CharmOrder among them), in order. */
    public static String state(final SpellAbility root) {
        final StringBuilder sb = new StringBuilder();
        final Set<SpellAbility> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        state(root, sb, seen, 0);
        return sb.toString();
    }

    private static void state(final SpellAbility p, final StringBuilder sb, final Set<SpellAbility> seen, final int depth) {
        if (p == null || !seen.add(p)) {
            return;
        }
        sb.append(depth).append(' ').append(p.getApi()).append(" act=")
                .append(p.getActivatingPlayer() == null ? "null" : p.getActivatingPlayer().getName())
                .append(" tgt=").append(p.getTargets() == null ? "null" : p.getTargets().toString())
                .append(" svars=").append(new TreeMap<>(p.getSVars())).append('\n');
        state(p.getSubAbility(), sb, seen, depth + 1);
        for (Map.Entry<String, SpellAbility> e : new TreeMap<>(p.getAdditionalAbilities()).entrySet()) {
            sb.append(depth).append(" +").append(e.getKey()).append('\n');
            state(e.getValue(), sb, seen, depth + 1);
        }
        for (Map.Entry<String, List<AbilitySub>> e : new TreeMap<>(p.getAdditionalAbilityLists()).entrySet()) {
            sb.append(depth).append(" *").append(e.getKey()).append(' ').append(e.getValue().size()).append('\n');
            for (AbilitySub x : e.getValue()) {
                state(x, sb, seen, depth + 1);
            }
        }
    }

    private static List<String> charmOrder(final SpellAbility sa) {
        final List<String> out = new ArrayList<>();
        for (AbilitySub a : sa.getAdditionalAbilityList("Choices")) {
            out.add(a.getSVar("CharmOrder"));
        }
        return out;
    }

    /**
     * The reviewer's repro: Abrade in hand, the opponent's Sol Ring, no creature (the damage mode is not offered). The
     * open check, the line checks (no mode recorded, a mode recorded) and ownHarmWhy with no activator set leave the live
     * ability and its modes as they were (CharmOrder stays [1, 2]; the activator, null, is put back).
     */
    @Test
    public void s1TheChecksLeaveTheLiveAbilityUnchanged() {
        board();
        addCard("Sol Ring", opp);
        final Card abrade = addCardToZone("Abrade", me, ZoneType.Hand);
        final SpellAbility sa = abrade.getSpellAbilities().stream().filter(a -> a.getApi() == ApiType.Charm).findFirst()
                .orElseThrow();
        final String fresh = state(sa);
        final List<String> order = charmOrder(sa);
        // no activator yet: ownHarmWhy sets it for the check and puts it back
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me, sa), "destroy Sol Ring is a live mode");
        Assert.assertEquals(state(sa), fresh, "ownHarmWhy with no activator changes nothing");
        sa.setActivatingPlayer(me);
        final String before = state(sa);
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me, sa));
        Assert.assertNull(LineOwnTarget.refuseOpen(me, sa));
        Assert.assertNull(LineOwnTarget.refuse(me, sa, null, null));
        Assert.assertNull(LineOwnTarget.refuse(me, sa, "mode:0", null), "one mode offered: the mode set is not replayed");
        final List<String> after = charmOrder(sa);
        System.out.println("[own-target-review] Abrade CharmOrder before=" + order + " after=" + after);
        Assert.assertEquals(after, order, "the live modes' CharmOrder");
        Assert.assertEquals(state(sa), before, "the live ability and its modes");
        // the modes the check reads are the modes Forge offers, in its order
        Assert.assertEquals(LineOwnTarget.possibleModes(sa).size(), 1);
        Assert.assertEquals(LineOwnTarget.possibleModes(sa).get(0).getApi(), ApiType.Destroy);
    }

    // ------------------------------------------------------------------ the reviewer's controls: still kept

    @Test
    public void ordinarySelfTargetsAreKept() {
        board();
        ours("Mulldrifter");
        kept("Unsummon our Mulldrifter (its own enters trigger)", ability("Unsummon", ApiType.ChangeZone));
        kept("Flicker of Fate (an immediate return)", ability("Flicker of Fate", ApiType.ChangeZone));
        kept("Giant Growth (a pump)", ability("Giant Growth", ApiType.Pump));
        kept("Rancor (an aura)", ability("Rancor", ApiType.Attach));
        kept("Lightning Bolt (the opponent is a target)", ability("Lightning Bolt", ApiType.DealDamage));
        kept("Remand (a counter)", ability("Remand", ApiType.Counter));
    }

    @Test
    public void payoffsAndOpposingTargetsAreKept() {
        board();
        ours("Stuffy Doll");
        kept("Flame Slash at Stuffy Doll (a damage payoff)", ability("Flame Slash", ApiType.DealDamage));
        board();
        ours("Kitchen Finks");
        kept("Doom Blade at Kitchen Finks (persist)", ability("Doom Blade", ApiType.Destroy));
        board();
        ours("Grizzly Bears");
        addCard("Sol Ring", opp);
        kept("Abrade (destroy Sol Ring is a live mode)", ability("Abrade", ApiType.Charm));
        kept("Naturalize (the opponent's Sol Ring)", ability("Naturalize", ApiType.Destroy));
    }

    // ------------------------------------------------------------------ what must still be refused

    /** Game 1 (589277be, turn 15): Abrade's 3 damage with only our damaged Harvester to hit, open and as the recorded mode. */
    @Test
    public void game1AbradeAtOurHarvesterIsRefused() {
        board();
        final Card harvester = ours("Harvester of Misery");
        harvester.setDamage(3);
        final SpellAbility open = ability("Abrade", ApiType.Charm);
        final String why = LookaheadSearch.ownHarmWhy(me, open);
        Assert.assertNotNull(why, "no artifact: every mode refused");
        Assert.assertTrue(why.startsWith("every mode: damage with only our own to target: [Harvester of Misery"), why);
        addCard("Sol Ring", opp); // two modes offered, as live: the recorded mode 0 is replayed
        final String line = LineOwnTarget.refuse(me, open, "mode:0", null);
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("mode 0: damage with only our own to target"), line);
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me, open), "open, the destroy-artifact mode is live: kept");
    }

    /** Game 2 (38a8a0e4, turn 10): Snuff Out at our own indestructible Ulamog, a destroy that is all the spell does. */
    @Test
    public void game2SnuffOutAtOurIndestructibleUlamogIsRefused() {
        board();
        final Card ulamog = addCard("Ulamog, the Ceaseless Hunger", opp);
        ulamog.addTempController(me, g.getNextTimestamp());
        g.getAction().controllerChangeZoneCorrection(ulamog);
        g.getAction().checkStateEffects(true);
        Assert.assertEquals(ulamog.getController(), me);
        ours("Swamp");
        final SpellAbility sa = ability("Snuff Out", ApiType.Destroy);
        final String open = LookaheadSearch.ownHarmWhy(me, sa);
        Assert.assertEquals(open, "destroy has no effect: every legal target is indestructible");
        sa.getTargets().add(ulamog);
        final String recorded = LineOwnTarget.refuse(me, sa, null, null);
        sa.resetTargets();
        Assert.assertNotNull(recorded);
        Assert.assertTrue(recorded.startsWith("destroy has no effect on Ulamog"), recorded);
    }

    /** The idle games' Tear Asunder (dd0142b5: our own Mox Jet), kicked or not, with only our own permanents to exile. */
    @Test
    public void idleTearAsunderAtOurOwnPermanentIsRefused() {
        board();
        final Card mox = ours("Mox Jet");
        ours("Swamp");
        ours("Swamp");
        ours("Forest");
        ours("Forest");
        final Card tear = addCardToZone("Tear Asunder", me, ZoneType.Hand);
        int checked = 0;
        for (SpellAbility sa : tear.getAllPossibleAbilities(me, true)) {
            final String why = LookaheadSearch.ownHarmWhy(me, sa);
            System.out.println("[own-target-review] Tear Asunder kicked=" + sa.isKicked() + " => " + why);
            Assert.assertNotNull(why, "kicked=" + sa.isKicked());
            Assert.assertTrue(why.startsWith("remove with only our own to target: [Mox Jet"), why);
            checked++;
        }
        Assert.assertTrue(checked >= 1, "Tear Asunder is castable");
        // recorded at the Mox: refused as well
        final SpellAbility sa = tear.getAllPossibleAbilities(me, true).get(0);
        sa.getTargets().add(mox);
        final String recorded = LineOwnTarget.refuse(me, sa, null, null);
        sa.resetTargets();
        Assert.assertNotNull(recorded);
        Assert.assertTrue(recorded.startsWith("remove on our own Mox Jet"), recorded);
        // kicked (the menu entry Forge AI's optional-cost choice makes): exile target nonland permanent, ours only: refused
        final List<forge.game.spellability.OptionalCostValue> opts = forge.game.GameActionUtil.getOptionalCostValues(sa);
        Assert.assertFalse(opts.isEmpty(), "Tear Asunder has kicker");
        final SpellAbility kicked = forge.game.GameActionUtil.addOptionalCosts(sa, opts);
        kicked.setActivatingPlayer(me);
        Assert.assertTrue(kicked.isKicked());
        final String k = LookaheadSearch.ownHarmWhy(me, kicked);
        System.out.println("[own-target-review] Tear Asunder kicked=true => " + k);
        Assert.assertNotNull(k);
        Assert.assertTrue(k.startsWith("remove with only our own to target: [Mox Jet"), k);
        // an opposing creature: the kicked spell can exile it (kept); unkicked, only artifacts and enchantments (refused)
        addCard("Grizzly Bears", opp);
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me, kicked), "kicked: the opponent's Bears is a target");
        Assert.assertNotNull(LookaheadSearch.ownHarmWhy(me, sa), "unkicked: still only our own Mox");
    }
}
