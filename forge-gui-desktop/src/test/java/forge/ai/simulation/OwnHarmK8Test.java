package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * line-own-target-1119: the K8 seat's candidates under {@link LookaheadSearch.Config#ownHarm}. Its departures are Forge's
 * legal spells and abilities, their targets left to Forge AI as they are cast, as the policy seat's are. Review 3: a refused
 * one (Snuff Out with only our own indestructible Ulamog to hit) is enumerated and played out as before, and demoted: it
 * departs only if, against the normal rule's choice among the candidates not refused (Forge's own answer the baseline),
 * it passes the departZ test and is better by {@link LookaheadSearch.Config#OWN_HARM_FLOOR}.
 */
public class OwnHarmK8Test extends SimulationTest {

    private static List<String> labels(final AiFixes.Mode mode, final Game g, final Player me) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 1;
        c.breadth = 8;
        c.threads = 1;
        c.ownHarm = mode;
        final LookaheadSearch ls = new LookaheadSearch(c);
        try {
            final List<String> out = new ArrayList<>();
            for (LookaheadSearch.Cand x : ls.enumerate(g, me, null, 1119L, null)) {
                out.add(x.label);
            }
            if (mode != AiFixes.Mode.OFF) {
                Assert.assertEquals(ls.getStats().ownHarmMode, mode.key());
            }
            System.out.println("[own-harm-k8] " + mode + " " + out + " stats " + ls.getStats().toJson().get("ownHarm"));
            return out;
        } finally {
            ls.shutdown();
        }
    }

    /** Snuff Out in our hand, castable both ways, the opponent's indestructible Ulamog under our control. */
    private Game snuffBoard() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(1);
        final Player opp = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (String n : new String[] {"Swamp", "Swamp", "Mountain", "Mountain"}) {
            addCard(n, me);
        }
        addCardToZone("Snuff Out", me, ZoneType.Hand);
        final Card ulamog = addCard("Ulamog, the Ceaseless Hunger", opp);
        ulamog.addTempController(me, g.getNextTimestamp());
        g.getAction().controllerChangeZoneCorrection(ulamog);
        g.getAction().checkStateEffects(true);
        Assert.assertEquals(ulamog.getController(), me);
        return g;
    }

    @Test
    public void theK8SeatKeepsARefusedDepartureAmongItsCandidates() {
        final Game g = snuffBoard();
        final Player me = g.getPlayers().get(1);
        final List<String> off = labels(AiFixes.Mode.OFF, g, me);
        Assert.assertTrue(off.stream().anyMatch(l -> l.contains("Snuff Out")), "off: Snuff Out is a candidate: " + off);
        Assert.assertEquals(labels(AiFixes.Mode.ON, g, me), off, "on: the candidates as off (played out, demoted later)");
        Assert.assertEquals(labels(AiFixes.Mode.SHADOW, g, me), off, "shadow: the candidates as off");
    }

    private static LookaheadSearch k8(final AiFixes.Mode mode) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 8;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1119L;
        c.departZ = 1.645;
        c.targetVariants = 4; // Snuff Out aimed at our Ulamog explicitly (Forge AI would not aim it there itself)
        c.ownHarm = mode;
        return new LookaheadSearch(c);
    }

    /** One K8 decision on the Snuff Out board with stubbed play-out values: did it cast Snuff Out? */
    private boolean castsSnuffOut(final AiFixes.Mode mode, final double gain) {
        LookaheadSearch.testScale = Boolean.TRUE; // the stubbed values stand for a model leaf's win probabilities
        try {
            return castsSnuffOut(mode, gain, true);
        } finally {
            LookaheadSearch.testScale = null;
        }
    }

    /** As above; {@code scale} false: K8's own static leaf (no model), so ownHarm must take no action (review 4). */
    private boolean castsSnuffOut(final AiFixes.Mode mode, final double gain, final boolean scale) {
        final Game g = snuffBoard();
        final Player me = g.getPlayers().get(1);
        final PlayerControllerAi ctrl = (PlayerControllerAi) me.getController();
        final List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        Assert.assertFalse(def != null && !def.isEmpty() && def.get(0) != null && def.get(0).getHostCard() != null
                && "Snuff Out".equals(def.get(0).getHostCard().getName()), "Forge's own answer is not Snuff Out");
        final LookaheadSearch ls = k8(mode);
        LookaheadSearch.testValues = (label, w) -> {
            final double j = (w % 2 == 0 ? 1 : -1) * 1e-4;
            return label.startsWith("Snuff Out") ? 0.5 + gain + (gain == 0 ? j : 2 * j) : 0.5 + j;
        };
        try {
            final List<SpellAbility> got = ls.decide(ctrl, def);
            final LookaheadSearch.Stats st = ls.getStats();
            System.out.println("[own-harm-k8] decide " + mode + " gain " + gain + " -> " + got + " stats "
                    + st.toJson().get("ownHarm"));
            final boolean snuff = got != null && !got.isEmpty() && got.get(0) != null && got.get(0).getHostCard() != null
                    && "Snuff Out".equals(got.get(0).getHostCard().getName());
            if (mode != AiFixes.Mode.OFF && gain > 0 && !scale) {
                Assert.assertEquals(st.ownHarmTests, 0, "static scores: no test");
                Assert.assertEquals(st.ownHarmNoScale, 1, "static scores: no action, counted");
                Assert.assertEquals(st.ownHarmDropped, 0, "static scores: nothing demoted");
            } else if (mode != AiFixes.Mode.OFF && gain > 0) {
                Assert.assertEquals(st.ownHarmTests, 1, "the refused departure was tested");
                Assert.assertEquals(st.ownHarmPassed, gain >= LookaheadSearch.Config.OWN_HARM_FLOOR ? 1 : 0);
                Assert.assertEquals(st.ownHarmDropped, gain >= LookaheadSearch.Config.OWN_HARM_FLOOR ? 0 : 1,
                        "demoted (SHADOW: would have been)");
            }
            return snuff;
        } finally {
            LookaheadSearch.testValues = null;
            ls.shutdown();
        }
    }

    /**
     * Review 3, the K8 seat (Forge's own answer the baseline): a refused Snuff Out better by 0.05 (past the departZ test)
     * departs; better by 0.005 (confident, under the floor) or tied, it does not, though the key off takes the 0.005
     * departure. With target variants on, the variant aiming it at our Ulamog is refused as its spell is.
     */
    @Test
    public void theK8SeatDemotesARefusedDepartureUnlessBetterByTheFloor() {
        Assert.assertTrue(castsSnuffOut(AiFixes.Mode.ON, 0.05), "better by 0.05: departs");
        Assert.assertFalse(castsSnuffOut(AiFixes.Mode.ON, 0.005), "better by 0.005: demoted");
        Assert.assertFalse(castsSnuffOut(AiFixes.Mode.ON, 0.0), "a tie: demoted (the search keeps Forge's answer)");
        Assert.assertTrue(castsSnuffOut(AiFixes.Mode.OFF, 0.005), "key off: the confident 0.005 departure is taken");
        Assert.assertTrue(castsSnuffOut(AiFixes.Mode.SHADOW, 0.005), "shadow: decides as off");
    }

    /**
     * Review 4 of #251, S1 2: K8's leaf without a model is Forge's static score, not a win probability, so ownHarm takes no
     * action there: the confident 0.005 Snuff Out departure the floor would demote is taken, as with the key off, and the
     * decision is counted as {@link LookaheadSearch.Config#OWN_HARM_NO_SCALE}.
     */
    @Test
    public void theK8SeatTakesNoActionOverStaticScores() {
        Assert.assertTrue(castsSnuffOut(AiFixes.Mode.ON, 0.005, false), "static scores: key-off behavior, departs");
        Assert.assertTrue(castsSnuffOut(AiFixes.Mode.OFF, 0.005, false), "key off: departs");
    }

    /**
     * Review 4 of #251, S1 1 (the search's side): the stand-in the demotion picks for a refused policy entry is a departure
     * for the loop guard. Our refused Abrade (its damage mode aimed only at our own creatures: Seeker of Skybreak and a
     * tapped Grizzly Bears) is the given list's first entry; Seeker's untap ability is the only other one, and pass is not
     * among them (a mandatory choice). With the
     * identical-departure limit spent, the guard blocks the stand-in: the search keeps the policy's own entry (key-off
     * behavior; RlSearch would play pass instead where the menu has it) and records the block, never the stand-in.
     */
    @Test
    public void aStandInTheLoopGuardBlocksIsNeverPlayedInAMandatoryChoice() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(1);
        final Player opp = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        addCard("Mountain", me);
        addCard("Mountain", me);
        final Card seeker = addCard("Seeker of Skybreak", me);
        seeker.setSickness(false);
        addCard("Grizzly Bears", me).setTapped(true); // something for Forge AI to untap in the play-outs
        final Card abrade = addCardToZone("Abrade", me, ZoneType.Hand);
        addCard("Plains", opp);
        g.getAction().checkStateEffects(true);
        final List<SpellAbility> given = new ArrayList<>();
        final List<SpellAbility> legal = new SpellAbilityPicker(me).getCandidateSpellsAndAbilities();
        legal.stream().filter(sa -> sa.getHostCard() == abrade).findFirst().ifPresent(given::add);
        legal.stream().filter(sa -> sa.getHostCard() == seeker).findFirst().ifPresent(given::add);
        Assert.assertEquals(given.size(), 2, "Abrade and Seeker's untap: " + given);
        Assert.assertNotNull(LookaheadSearch.ownHarmWhy(me, given.get(0)), "the guard refuses our Abrade");
        for (int limit : new int[] {2, 0}) {
            final LookaheadSearch.Config c = new LookaheadSearch.Config();
            c.worlds = 2;
            c.breadth = 4;
            c.horizonTurns = 1;
            c.threads = 1;
            c.seed = 1119L;
            c.departZ = 1.645;
            c.ownHarm = AiFixes.Mode.ON;
            c.maxSameDepartures = limit;
            final LookaheadSearch ls = new LookaheadSearch(c);
            LookaheadSearch.testScale = Boolean.TRUE;
            LookaheadSearch.testValues = (label, w) -> (label.startsWith("Abrade") ? 0.505 : 0.5) + (w % 2 == 0 ? 1 : -1) * 1e-4;
            try {
                ls.ownHarmDemote(java.util.Set.of(0), true);
                final LookaheadSearch.GivenResult r = ls.decideGiven(g, me, given, 4);
                System.out.println("[own-harm-guard] limit " + limit + " outcome " + r.outcome + " chosen " + r.chosen
                        + " demotion " + r.ownHarmDemotion);
                Assert.assertNotNull(r.ownHarmDemotion, "the demotion ran");
                Assert.assertTrue(r.ownHarmDemotion.get("changed").getAsBoolean(), "the stand-in: " + r.ownHarmDemotion);
                if (limit == 2) {
                    Assert.assertEquals(r.outcome, "departed", "the stand-in departs within the limit");
                    Assert.assertEquals(r.chosen, 1, "Seeker's untap stands in");
                    Assert.assertFalse(r.ownHarmDemotion.has("guardBlocked"), r.ownHarmDemotion.toString());
                } else {
                    Assert.assertEquals(r.outcome, "loop-guard", "the guard blocks the stand-in");
                    Assert.assertEquals(r.chosen, 0, "key-off behavior: the policy's own entry, never the stand-in");
                    Assert.assertTrue(r.ownHarmDemotion.get("guardBlocked").getAsString().startsWith("Seeker of Skybreak"),
                            r.ownHarmDemotion.toString());
                    Assert.assertEquals(ls.getStats().ownHarmGuard, 1);
                }
            } finally {
                LookaheadSearch.testScale = null;
                LookaheadSearch.testValues = null;
                ls.shutdown();
            }
        }
    }

    /**
     * Review 4 of #251, S1 1 (the no-verdict stand-in): {@link LookaheadSearch#ownHarmStandInAdmit} applies the loop
     * guard's limits to the stand-in RlSearch plays when the search gave no verdict: the same stand-in twice at one
     * priority, then blocked; another one is admitted until the per-turn limit; a new turn starts over.
     */
    @Test
    public void theNoVerdictStandInCountsTowardTheLoopGuard() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me, 5);
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.threads = 1;
        c.maxDeparturesPerTurn = 3;
        final LookaheadSearch ls = new LookaheadSearch(c);
        try {
            Assert.assertTrue(ls.ownHarmStandInAdmit(g, "3:Seeker"));
            Assert.assertTrue(ls.ownHarmStandInAdmit(g, "3:Seeker"));
            Assert.assertFalse(ls.ownHarmStandInAdmit(g, "3:Seeker"), "the identical-departure limit (2)");
            Assert.assertTrue(ls.ownHarmStandInAdmit(g, "4:Island"));
            Assert.assertFalse(ls.ownHarmStandInAdmit(g, "5:Bears"), "the per-turn limit (3)");
            Assert.assertEquals(ls.getStats().ownHarmGuard, 2);
            g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me, 6);
            Assert.assertTrue(ls.ownHarmStandInAdmit(g, "3:Seeker"), "a new turn");
        } finally {
            ls.shutdown();
        }
    }

    /**
     * Review 1 of #251, S1 7: enumerating with the key off, shadow or on leaves the live Abrade as it was (the reviewer's
     * board: the opponent's Sol Ring, no creature, so a CharmOrder renumbering would show), and Abrade, its destroy mode
     * live, is a candidate each time.
     */
    @Test
    public void theK8GuardLeavesTheLiveAbilityUnchanged() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(1);
        final Player opp = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        addCard("Mountain", me);
        addCard("Mountain", me);
        final Card abrade = addCardToZone("Abrade", me, ZoneType.Hand);
        addCard("Sol Ring", opp);
        g.getAction().checkStateEffects(true);
        final StringBuilder before = new StringBuilder();
        for (forge.game.spellability.SpellAbility sa : abrade.getSpellAbilities()) {
            before.append(LineOwnTargetReviewTest.state(sa));
        }
        for (AiFixes.Mode mode : new AiFixes.Mode[] {AiFixes.Mode.OFF, AiFixes.Mode.SHADOW, AiFixes.Mode.ON}) {
            final List<String> l = labels(mode, g, me);
            Assert.assertTrue(l.stream().anyMatch(x -> x.contains("Abrade")), mode + ": Abrade is a candidate: " + l);
            final StringBuilder after = new StringBuilder();
            for (forge.game.spellability.SpellAbility sa : abrade.getSpellAbilities()) {
                after.append(LineOwnTargetReviewTest.state(sa));
            }
            Assert.assertEquals(after.toString(), before.toString(), mode + ": the live Abrade after enumerate");
        }
    }
}
