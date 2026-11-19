package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * line-own-target-1119: the K8 seat's candidates under {@link LookaheadSearch.Config#ownHarm}. Its departures are Forge's
 * legal spells and abilities, their targets left to Forge AI as they are cast, as the policy seat's are: with the key on, a
 * refused one (Snuff Out with only our own indestructible Ulamog to hit) is not enumerated; off, the candidates are as
 * before.
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

    @Test
    public void theK8SeatDropsARefusedDepartureOnlyWithTheKeyOn() {
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
        final List<String> off = labels(AiFixes.Mode.OFF, g, me);
        Assert.assertTrue(off.stream().anyMatch(l -> l.contains("Snuff Out")), "off: Snuff Out is a candidate: " + off);
        final List<String> on = labels(AiFixes.Mode.ON, g, me);
        Assert.assertFalse(on.stream().anyMatch(l -> l.contains("Snuff Out")), "on: never a candidate: " + on);
        final List<String> shadow = labels(AiFixes.Mode.SHADOW, g, me);
        Assert.assertEquals(shadow, off, "shadow: the candidates as off");
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
