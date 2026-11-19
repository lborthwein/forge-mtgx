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
            if (mode != AiFixes.Mode.OFF && gain > 0) {
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
