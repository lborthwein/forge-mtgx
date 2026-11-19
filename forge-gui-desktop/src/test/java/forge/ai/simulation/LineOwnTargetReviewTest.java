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
        // review 2 of #251, S1 1: the first-step filter (ForcedWinSolver#ownTargetAtStart, a dry run of the live
        // follower's first step) leaves the live ability as it found it: no activator set (the reviewer's repro), the
        // CharmOrder, the chosen modes, the targets. Abrade's damage mode at our own Bears, recorded as mode 0.
        ours("Mountain");
        ours("Mountain");
        final Card bears = ours("Grizzly Bears");
        final Line line = modeLine(abrade, sa, "mode:0", bears);
        sa.setActivatingPlayer(null);
        final String fresh2 = state(sa);
        final List<String> order2 = charmOrder(sa);
        final String chosen2 = chosenModes(abrade, sa);
        for (String key : new String[] {"off", "on, not the live seat", "on"}) {
            final String why = lineSolver(!"off".equals(key), "on".equals(key)).ownTargetAtStart(g, line);
            System.out.println("[own-target-review] first-step filter key " + key + " => " + why + "; live activator="
                    + sa.getActivatingPlayer());
            Assert.assertEquals(why != null, "on".equals(key), "key " + key + ": " + why);
            Assert.assertEquals(state(sa), fresh2, "key " + key + ": the live ability, its modes and targets");
            Assert.assertEquals(charmOrder(sa), order2, "key " + key + ": the live CharmOrder");
            Assert.assertEquals(chosenModes(abrade, sa), chosen2, "key " + key + ": the live chosen modes");
            // the search's check (ownHarm on and shadow read the same) after it: unchanged as well
            LookaheadSearch.ownHarmWhy(me, sa);
            Assert.assertEquals(state(sa), fresh2, "key " + key + ": after ownHarmWhy");
        }
        Assert.assertTrue(lineSolver(true, true).ownTargetAtStart(g, line).contains("damage"), "the damage mode breaks");
    }

    /** A one-step line: {@code host}'s spell {@code sa} with its mode {@code mode} recorded at {@code target}. */
    private static Line modeLine(final Card host, final SpellAbility sa, final String mode, final Card target) {
        final Step st = Step.engine(AbilKind.ENGINE, host.getId(), host.getName(), sa.getDescription(),
                sa.getPayCosts().toString(), true);
        if (mode != null) {
            st.cac = new ArrayList<>(List.of(mode));
            st.cacPlayed = new ArrayList<>(List.of(mode));
        }
        st.targetRefs.add("0:C" + target.getId());
        return new Line(new ArrayList<>(List.of(st)), List.of(), 0, 20, 0, 0, List.of(), "review");
    }

    /** A solver with lineOwnTarget {@code on} and engineModeChoice (as live); {@code live}: the live seat's driver. */
    private ForcedWinSolver lineSolver(final boolean on, final boolean live) {
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("act", "false");
        cfg.addProperty("engineModeChoice", "true");
        cfg.addProperty("lineOwnTarget", String.valueOf(on));
        return new SolverDriver(me, SolverDriver.config(cfg), live).solver();
    }

    private static String chosenModes(final Card host, final SpellAbility sa) {
        return host.getChosenModes(sa, "ThisTurn") + "/" + host.getChosenModes(sa, "ThisGame");
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
        game1Rest();
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

    /**
     * The rest of game 1's board at turn 15 (the replay's view): our lands, and Kolaghan's Command, Dauthi Voidwalker,
     * Dismember and Valki in our graveyard. Review 2 of #251: no trigger or replacement of ours here could fire from the
     * damage (Harvester's enters trigger is Destination Battlefield; Dauthi's replacement is active on the battlefield
     * only; Valki's is an enters trigger, in the graveyard).
     */
    private void game1Rest() {
        for (String n : new String[] {"Mountain", "Swamp", "Badlands", "Swamp", "Swamp"}) {
            ours(n);
        }
        for (String n : new String[] {"Kolaghan's Command", "Dauthi Voidwalker", "Dismember", "Valki, God of Lies"}) {
            addCardToZone(n, me, ZoneType.Graveyard);
        }
    }

    /**
     * Game 2 (38a8a0e4, turn 10): Snuff Out at our own indestructible Ulamog, a destroy that is all the spell does. Review
     * 2 of #251: on the rest of game 2's board (the replay's view at turn 10) no trigger of ours could fire from the cast:
     * Ulamog's cast trigger is ValidCard Card.Self, Fallen Shinobi's is combat damage to a player, Valki's an enters
     * trigger; nor, once the line has cast it, Faerie Mastermind's (a draw trigger).
     */
    @Test
    public void game2SnuffOutAtOurIndestructibleUlamogIsRefused() {
        board();
        final Card ulamog = addCard("Ulamog, the Ceaseless Hunger", opp);
        ulamog.addTempController(me, g.getNextTimestamp());
        g.getAction().controllerChangeZoneCorrection(ulamog);
        g.getAction().checkStateEffects(true);
        Assert.assertEquals(ulamog.getController(), me);
        ours("Swamp");
        for (String n : new String[] {"Underground Sea", "Valki, God of Lies", "Watery Grave", "Island", "Fallen Shinobi",
                "Island"}) {
            ours(n);
        }
        addCardToZone("Inquisition of Kozilek", me, ZoneType.Graveyard);
        addCardToZone("Prismatic Vista", me, ZoneType.Graveyard);
        final SpellAbility sa = ability("Snuff Out", ApiType.Destroy);
        final String open = LookaheadSearch.ownHarmWhy(me, sa);
        Assert.assertEquals(open, "destroy has no effect: every legal target is indestructible"); // Valki, Shinobi: black
        Assert.assertTrue(recordedAt(sa, ulamog).startsWith("destroy has no effect on Ulamog"));
        ours("Faerie Mastermind");
        final String open2 = LookaheadSearch.ownHarmWhy(me, sa);
        System.out.println("[own-target-review] game 2 Snuff Out with Faerie Mastermind open => " + open2);
        Assert.assertNotNull(open2);
        Assert.assertTrue(open2.startsWith("destroy with only our own to target: [Faerie Mastermind"), open2);
        Assert.assertTrue(recordedAt(sa, ulamog).startsWith("destroy has no effect on Ulamog"));
    }

    /** The line check's reason for {@code sa} with {@code target} recorded (asserted not null). */
    private String recordedAt(final SpellAbility sa, final Card target) {
        sa.getTargets().add(target);
        try {
            final String recorded = LineOwnTarget.refuse(me, sa, null, null);
            Assert.assertNotNull(recorded, "recorded at " + target);
            return recorded;
        } finally {
            sa.resetTargets();
        }
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

    // ------------------------------------------------------------------ review 2 of #251: one conservative payoff test

    /**
     * The reviewer's S1 2 (Reveillark is our only creature, Bears and Mulldrifter in our graveyard): its leaves trigger
     * returns both, so Unsummon at it is kept by both keys.
     */
    @Test
    public void r2ABounceWithALeavesTriggerIsKept() {
        board();
        final Card lark = ours("Reveillark");
        addCardToZone("Grizzly Bears", me, ZoneType.Graveyard);
        addCardToZone("Mulldrifter", me, ZoneType.Graveyard);
        keptTarget("Unsummon our Reveillark (its leaves trigger)", ability("Unsummon", ApiType.ChangeZone), lark);
    }

    /**
     * The reviewer's S1 3 (Guttersnipe is our only creature, the opponent at 2): its cast trigger wins before Flame Slash
     * resolves, so Flame Slash at it is kept; so is a destroy that destroys nothing, cast while it is in play.
     */
    @Test
    public void r2ACastTriggerOfOursKeepsTheSpell() {
        board();
        final Card snipe = ours("Guttersnipe");
        ours("Mountain");
        opp.setLife(2, null);
        keptTarget("Flame Slash at our Guttersnipe (its cast trigger)", ability("Flame Slash", ApiType.DealDamage), snipe);
        board();
        ours("Guttersnipe");
        final Card citadel = ours("Darksteel Citadel");
        keptTarget("Beast Within at our Citadel with Guttersnipe (its cast trigger)", ability("Beast Within", ApiType.Destroy),
                citadel);
    }

    /**
     * The reviewer's S1 4 (Bears is our only creature, Bridge from Below in our graveyard): Bridge's death trigger, active
     * in the graveyard, makes a Zombie, so Doom Blade at the Bears is kept. Bridge on the battlefield (where its trigger is
     * not active) keeps nothing.
     */
    @Test
    public void r2ADeathTriggerInOurGraveyardKeepsTheSpell() {
        board();
        final Card bears = ours("Grizzly Bears");
        addCardToZone("Bridge from Below", me, ZoneType.Graveyard);
        keptTarget("Doom Blade at our Bears with Bridge from Below in our graveyard", ability("Doom Blade", ApiType.Destroy),
                bears);
        board();
        final Card bears2 = ours("Grizzly Bears");
        ours("Bridge from Below");
        final String why = refusedTarget("Doom Blade at our Bears with Bridge from Below in play",
                ability("Doom Blade", ApiType.Destroy), bears2);
        Assert.assertTrue(why.startsWith("destroy with only our own to target"), why);
    }

    /**
     * The reviewer's S1 5 (our Hazoret, 1 damage marked): Spatial Contortion makes it an 8/1 that survives its damage, as
     * it is indestructible: kept. Zero toughness kills an indestructible creature: Darksteel Myr (0/1) is refused.
     */
    @Test
    public void r2AnIndestructiblePumpThatSurvivesItsDamageIsKept() {
        board();
        final Card hazoret = ours("Hazoret the Fervent");
        hazoret.setDamage(1);
        Assert.assertFalse(hazoret.canBeDestroyed());
        Assert.assertEquals(hazoret.getNetToughness(), 4);
        keptTarget("Spatial Contortion at our damaged Hazoret (an 8/1 indestructible)",
                ability("Spatial Contortion", ApiType.Pump), hazoret);
        board();
        final Card myr = ours("Darksteel Myr");
        final String why = refusedTarget("Spatial Contortion at our Darksteel Myr (toughness -2)",
                ability("Spatial Contortion", ApiType.Pump), myr);
        Assert.assertTrue(why.startsWith("shrink with only our own to target"), why);
    }

    /**
     * Triggers that this action cannot set off keep nothing: enters-only triggers (Harvester of Misery, Valki), a combat
     * damage to a player trigger (Fallen Shinobi), a draw trigger (Faerie Mastermind), an opponent's-cast trigger (Esper
     * Sentinel). Flame Slash with only these creatures of ours to hit is refused open and recorded.
     */
    @Test
    public void r2IrrelevantTriggersStillRefuse() {
        board();
        final Card harvester = ours("Harvester of Misery");
        for (String n : new String[] {"Valki, God of Lies", "Fallen Shinobi", "Faerie Mastermind", "Esper Sentinel"}) {
            ours(n);
        }
        final String why = refusedTarget("Flame Slash with only our triggered creatures to hit",
                ability("Flame Slash", ApiType.DealDamage), harvester);
        Assert.assertTrue(why.startsWith("damage with only our own to target"), why);
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            Assert.assertFalse(LineOwnTarget.couldFire(me, ability("Flame Slash", ApiType.DealDamage), c, LineOwnTarget.Harm.DAMAGE),
                    c.getName());
        }
    }

    /**
     * The reviewer's S1 1 repro (our Bears and Plains, Swords in hand, its live ability's activator unset): the first-step
     * filter refuses the Swords line with the key on at the live seat and leaves the live activator unset, as the key-off
     * and non-live controls do.
     */
    @Test
    public void r2TheFirstStepFilterDoesNotSetTheLiveActivator() {
        board();
        final Card bears = ours("Grizzly Bears");
        ours("Plains");
        final SpellAbility sa = ability("Swords to Plowshares", ApiType.ChangeZone);
        final Line line = modeLine(sa.getHostCard(), sa, null, bears);
        sa.setActivatingPlayer(null);
        Assert.assertNull(lineSolver(false, true).ownTargetAtStart(g, line));
        Assert.assertNull(sa.getActivatingPlayer(), "key-off control");
        Assert.assertNull(lineSolver(true, false).ownTargetAtStart(g, line));
        Assert.assertNull(sa.getActivatingPlayer(), "non-live control");
        final String why = lineSolver(true, true).ownTargetAtStart(g, line);
        System.out.println("[own-target-review] first step reason=" + why + " liveActivator=" + sa.getActivatingPlayer());
        Assert.assertNotNull(why);
        Assert.assertTrue(why.contains("remove on our own Grizzly Bears"), why);
        Assert.assertNull(sa.getActivatingPlayer(), "the first-step check leaves the live ability unchanged");
    }

    /** The first-step filter out of budget (its deadline passed, or its work budget spent) keeps the line unfiltered. */
    @Test
    public void r2TheFirstStepFilterOutOfBudgetKeepsTheLine() {
        board();
        final Card bears = ours("Grizzly Bears");
        ours("Plains");
        final SpellAbility sa = ability("Swords to Plowshares", ApiType.ChangeZone);
        final Line line = modeLine(sa.getHostCard(), sa, null, bears);
        final ForcedWinSolver s = lineSolver(true, true);
        Assert.assertNotNull(s.ownTargetAtStart(g, line, null, 0L), "in budget: filtered");
        Assert.assertNull(s.ownTargetAtStart(g, line, null, forge.game.WorkClock.nanoTime() - 1), "past its deadline: kept");
        final forge.ai.AiWork.Budget outer = forge.ai.AiWork.open(1);
        final forge.ai.AiWork.Budget spent = forge.ai.AiWork.current();
        try {
            try {
                forge.ai.AiWork.tick("test", 2);
            } catch (forge.ai.AiWork.Exhausted expected) {
                // the budget is spent
            }
        } finally {
            forge.ai.AiWork.restore(outer);
        }
        Assert.assertTrue(spent.spent());
        Assert.assertNull(s.ownTargetAtStart(g, line, spent, 0L), "its work budget spent: kept");
        Assert.assertEquals(s.ownTargetUnchecked, 2);
        Assert.assertNull(forge.ai.AiWork.current(), "the caller's budget scope is restored");
    }

    /**
     * architect-o, review 2 (option A): a trigger Forge flags static ({@code Static$ True}, rules bookkeeping) is not a
     * payoff. Tear Asunder at our own Chrome Mox (its imprint-cleanup leaves trigger is static), imprinted or not, is
     * refused by both keys, as at Mox Jet. The leaves, cast and death keeps above (Reveillark, Guttersnipe, Bridge from
     * Below) are ordinary triggers and still hold.
     */
    @Test
    public void r2TearAsunderAtOurChromeMoxIsRefused() {
        for (boolean imprinted : new boolean[] {false, true}) {
            board();
            final Card mox = ours("Chrome Mox");
            if (imprinted) {
                mox.addImprintedCard(addCardToZone("Lightning Bolt", me, ZoneType.Exile));
                Assert.assertEquals(mox.getImprintedCards().size(), 1);
            }
            for (String n : new String[] {"Swamp", "Swamp", "Forest", "Forest"}) {
                ours(n);
            }
            boolean staticLeaves = false;
            for (forge.game.trigger.Trigger t : mox.getTriggers()) {
                staticLeaves |= t.isStatic() && t.getMode() == forge.game.trigger.TriggerType.ChangesZone
                        && "Battlefield".equals(t.getParam("Origin"));
            }
            Assert.assertTrue(staticLeaves, "Chrome Mox has its static leaves (cleanup) trigger");
            final String why = refusedTarget("Tear Asunder at our Chrome Mox, imprinted=" + imprinted,
                    ability("Tear Asunder", ApiType.ChangeZone), mox);
            Assert.assertTrue(why.startsWith("remove with only our own to target: [Chrome Mox"), why);
        }
    }

    /**
     * Serra Paragon's own trigger is static (skipped), but the rider it grants a permanent cast through it ("When this
     * permanent is put into a graveyard from the battlefield, exile it and you gain 2 life", as its Animate adds it) is an
     * ordinary trigger of that permanent: Doom Blade at our Bears carrying it is kept by both keys; without it, refused.
     */
    @Test
    public void r2SerraParagonsGrantedRiderIsAnOrdinaryTrigger() {
        board();
        final Card paragon = ours("Serra Paragon");
        boolean staticCast = false;
        for (forge.game.trigger.Trigger t : paragon.getTriggers()) {
            staticCast |= t.isStatic() && t.getMode() == forge.game.trigger.TriggerType.SpellCast;
        }
        Assert.assertTrue(staticCast, "Serra Paragon's cast trigger is static");
        final Card bears = ours("Grizzly Bears");
        final String why = refusedTarget("Doom Blade with Serra Paragon and plain Bears", ability("Doom Blade", ApiType.Destroy),
                bears);
        Assert.assertTrue(why.startsWith("destroy with only our own to target"), why);
        // the rider, from Serra Paragon's script (SVar TrigDieExile), granted to the Bears
        bears.setSVar("TrigExile", "DB$ ChangeZone | Defined$ TriggeredNewCardLKICopy | Origin$ Graveyard | Destination$ Exile"
                + " | SubAbility$ DBGainLife");
        bears.setSVar("DBGainLife", "DB$ GainLife | LifeAmount$ 2");
        final forge.game.trigger.Trigger rider = bears.addTrigger(forge.game.trigger.TriggerHandler.parseTrigger(
                "Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self | Execute$ TrigExile"
                        + " | TriggerDescription$ When this permanent is put into a graveyard from the battlefield, exile it and"
                        + " you gain 2 life.", bears, false));
        Assert.assertFalse(rider.isStatic());
        keptTarget("Doom Blade at our Bears with Serra Paragon's rider", ability("Doom Blade", ApiType.Destroy), bears);
    }
}
