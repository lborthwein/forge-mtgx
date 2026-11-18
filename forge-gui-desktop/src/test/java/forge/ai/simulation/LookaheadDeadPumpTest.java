package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The dead pump guard (Config.deadPump, lane temp-pump-wrong-turn-1117). The report of 2026-11-18T01:55Z: on turn 6, in
 * its own draw step and with no creatures, the policy seat discarded Library of Alexandria and then a Swamp to give the
 * opponent's Oona's Prowler -2/-0 until end of turn. The play-outs end after the effect has expired, the value leaf priced
 * the excess lands at about 0, and departZ accepted the activation twice. The class: a spell or ability whose whole effect
 * is a power change until end of turn, on creatures that can no longer deal combat damage this turn, with nothing that
 * reacts to its cost or its play, is not a departure candidate.
 */
public class LookaheadDeadPumpTest extends SimulationTest {

    /**
     * The reported scene, at {@code phase} of {@code aiActive ? a : b}'s turn. a, the policy seat: Island and Mox Ruby, a
     * land-heavy hand, two lands in the graveyard. b: tapped Forest, Mountain and Robber of the Rich, Mox Opal, Oona's
     * Prowler; Endurance (an enters trigger, off the battlefield) and Wasteland in the graveyard, Augur of Autumn in exile.
     */
    private Game reported(PhaseType phase, boolean aiActive) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(phase, aiActive ? a : b);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Island" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        addCard("Island", a);
        addCard("Mox Ruby", a);
        for (String n : new String[] {"Library of Alexandria", "Swamp", "Swamp", "Island", "Plains", "Plains", "Jace, the Mind Sculptor"}) {
            addCardToZone(n, a, ZoneType.Hand);
        }
        addCardToZone("Underground Sea", a, ZoneType.Graveyard);
        addCardToZone("Scalding Tarn", a, ZoneType.Graveyard);
        for (String n : new String[] {"Forest", "Mountain", "Robber of the Rich"}) {
            addCard(n, b).setTapped(true);
        }
        addCard("Mox Opal", b);
        addCard("Oona's Prowler", b).setSickness(false);
        for (String n : new String[] {"Mountain", "Fiery Confluence", "Paradise Druid"}) {
            addCardToZone(n, b, ZoneType.Hand);
        }
        addCardToZone("Endurance", b, ZoneType.Graveyard);
        addCardToZone("Wasteland", b, ZoneType.Graveyard);
        addCardToZone("Augur of Autumn", b, ZoneType.Exile);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private Game reported() {
        return reported(PhaseType.DRAW, true);
    }

    /** a's own second main phase, after it did not attack: {@code creature} (not summoning sick) and {@code lands}. */
    private Game ownMain2(String creature, String... lands) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        addCard(creature, a).setSickness(false);
        for (String l : lands) {
            addCard(l, a);
        }
        addCard("Forest", b);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /**
     * The pump ability of the permanent {@code name} as {@code p} would activate it, or null when it is not legal for p now.
     * (Forge's own enumeration holds instants back in the draw step, so the live misplay came from the policy seat's menu,
     * not from it.)
     */
    private static SpellAbility pump(Player p, String name) {
        for (Card c : p.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (!c.getName().equals(name)) {
                continue;
            }
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() == ApiType.Pump) {
                    sa.setActivatingPlayer(p);
                    return sa.canPlay() && ComputerUtilCost.canPayCost(sa, p, false) ? sa : null;
                }
            }
        }
        return null;
    }

    /** The Prowler's ability for {@code p}; it must be legal (a counter-fixture must not pass on a missing candidate). */
    private static SpellAbility prowler(Player p) {
        final SpellAbility sa = pump(p, "Oona's Prowler");
        AssertJUnit.assertNotNull("the Prowler's ability is legal for the seat", sa);
        return sa;
    }

    private static Card find(Player p, String name) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static LookaheadSearch search(AiFixes.Mode mode) {
        return search(mode, 4);
    }

    private static LookaheadSearch search(AiFixes.Mode mode, int breadth) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 4;
        c.breadth = breadth;
        c.horizonTurns = 2;
        c.threads = 1;
        c.seed = 1_117_000_006L;
        c.departZ = 1.645;
        c.deadPump = mode;
        return new LookaheadSearch(c);
    }

    private static boolean hasProwler(List<LookaheadSearch.Cand> cands) {
        for (LookaheadSearch.Cand c : cands) {
            if (c.label.startsWith("Oona's Prowler")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The policy seat's given set as the live seat sends it: pass first (the default), then every legal play of its menu
     * (live, on the reported step: pass and the Prowler activation).
     */
    private static List<SpellAbility> given(Player a) {
        final List<SpellAbility> g = new ArrayList<>();
        g.add(null);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(ComputerUtilAbility.getAvailableCards(a.getGame(), a), a)) {
            if (sa.isManaAbility()) {
                continue;
            }
            sa.setActivatingPlayer(a);
            if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, a, false)) {
                g.add(sa);
            }
        }
        return g;
    }

    private static int prowlerAt(List<SpellAbility> g) {
        for (int i = 0; i < g.size(); i++) {
            final SpellAbility sa = g.get(i);
            if (sa != null && sa.getHostCard().getName().equals("Oona's Prowler")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean searched(LookaheadSearch.GivenResult r, int idx) {
        for (int i : r.givenIndex) {
            if (i == idx) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.deadPump);
        AssertJUnit.assertFalse(c.toJson().has("deadPump"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("deadPump"));
        } finally {
            s.shutdown();
        }
        c.deadPump = AiFixes.Mode.SHADOW;
        AssertJUnit.assertEquals("shadow", c.toJson().get("deadPump").getAsString());
        RlSearch.Config r = new RlSearch.Config();
        AssertJUnit.assertEquals("off", r.deadPump);
        AssertJUnit.assertFalse("policy-seat spec JSON unchanged while off", r.toJson().has("deadPump"));
    }

    @Test
    public void theReportedBoardIsDead() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        SpellAbility sa = pump(a, "Oona's Prowler");
        AssertJUnit.assertNotNull("the opponent's Prowler ability is a legal candidate for the seat (any player may activate it)", sa);
        AssertJUnit.assertTrue("-2/-0 on a creature that cannot deal combat damage this turn, paid with a card", LookaheadSearch.deadPump(sa, a));
        AssertJUnit.assertFalse(LookaheadSearch.deadPump(null, a));
    }

    /** The live path: the misplay came from the policy seat's given set (decideGiven, guardGiven). */
    @Test
    public void thePolicySeatsGivenSetDropsItWhenOn() {
        Game g1 = reported();
        Player a1 = g1.getPlayers().get(0);
        List<SpellAbility> giv1 = given(a1);
        int at1 = prowlerAt(giv1);
        AssertJUnit.assertTrue("the activation is in the given set, after the default (pass)", at1 > 0);
        LookaheadSearch off = search(AiFixes.Mode.OFF);
        Game g2 = reported();
        Player a2 = g2.getPlayers().get(0);
        List<SpellAbility> giv2 = given(a2);
        int at2 = prowlerAt(giv2);
        LookaheadSearch on = search(AiFixes.Mode.ON);
        try {
            AssertJUnit.assertTrue("OFF: the activation is searched, as it was live", searched(off.decideGiven(g1, a1, giv1, 4), at1));
            String before = LookaheadSearch.fingerprint(g2);
            LookaheadSearch.GivenResult r = on.decideGiven(g2, a2, giv2, 4);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g2));
            AssertJUnit.assertFalse("ON: the activation is not searched", searched(r, at2));
            AssertJUnit.assertTrue(r.chosen != at2);
            AssertJUnit.assertEquals(1, on.getStats().deadPumpDropped);
            AssertJUnit.assertEquals(1, on.getStats().deadPumpDecisions);
            AssertJUnit.assertEquals("on", on.getStats().toJson().getAsJsonObject("deadPump").get("mode").getAsString());
            AssertJUnit.assertEquals(0, off.getStats().deadPumpDropped);
            AssertJUnit.assertFalse(off.getStats().toJson().has("deadPump"));
        } finally {
            off.shutdown();
            on.shutdown();
        }
    }

    /**
     * The K8 seat's site (enumerate), in the seat's first main phase of the same turn (Forge's enumeration holds instants
     * back until then): the same class, the same rule. Breadth 10 holds every legal play.
     */
    @Test
    public void theK8EnumerationDropsItWhenOn() {
        Game g1 = reported(PhaseType.MAIN1, true);
        Player a1 = g1.getPlayers().get(0);
        LookaheadSearch off = search(AiFixes.Mode.OFF, 10);
        Game g2 = reported(PhaseType.MAIN1, true);
        Player a2 = g2.getPlayers().get(0);
        LookaheadSearch on = search(AiFixes.Mode.ON, 10);
        try {
            AssertJUnit.assertTrue("OFF: the activation is a candidate", hasProwler(off.enumerate(g1, a1, null, 1L, null)));
            String before = LookaheadSearch.fingerprint(g2);
            AssertJUnit.assertFalse("ON: the activation is not a candidate", hasProwler(on.enumerate(g2, a2, null, 1L, null)));
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g2));
            AssertJUnit.assertEquals(1, on.getStats().deadPumpDropped);
            AssertJUnit.assertEquals(1, on.getStats().deadPumpDecisions);
        } finally {
            off.shutdown();
            on.shutdown();
        }
    }

    @Test
    public void shadowKeepsTheCandidateAndCountsIt() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        List<SpellAbility> giv = given(a);
        int at = prowlerAt(giv);
        LookaheadSearch s = search(AiFixes.Mode.SHADOW);
        try {
            AssertJUnit.assertTrue("SHADOW decides as OFF", searched(s.decideGiven(game, a, giv, 4), at));
            AssertJUnit.assertEquals(1, s.getStats().deadPumpCands);
            AssertJUnit.assertEquals(1, s.getStats().deadPumpDecisions);
            AssertJUnit.assertEquals(0, s.getStats().deadPumpDropped);
            AssertJUnit.assertEquals(1, s.deadPumpKeys.size());
            AssertJUnit.assertEquals("shadow", s.getStats().toJson().getAsJsonObject("deadPump").get("mode").getAsString());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void theDefaultIsNeverDropped() {
        Game g1 = reported();
        Player a1 = g1.getPlayers().get(0);
        List<SpellAbility> giv = given(a1);
        int at = prowlerAt(giv);
        giv.add(0, giv.remove(at)); // the policy's own answer is the activation
        LookaheadSearch s = search(AiFixes.Mode.ON);
        Game g2 = reported(PhaseType.MAIN1, true);
        Player a2 = g2.getPlayers().get(0);
        LookaheadSearch k8 = search(AiFixes.Mode.ON, 10);
        try {
            AssertJUnit.assertTrue("the policy's default is searched", searched(s.decideGiven(g1, a1, giv, 4), 0));
            AssertJUnit.assertEquals(1, s.getStats().deadPumpForge);
            AssertJUnit.assertEquals(0, s.getStats().deadPumpDropped);
            List<LookaheadSearch.Cand> cands = k8.enumerate(g2, a2, pump(a2, "Oona's Prowler"), 1L, null);
            AssertJUnit.assertTrue(cands.get(0).isDefault);
            AssertJUnit.assertTrue("Forge's own answer is kept", cands.get(0).label.startsWith("Oona's Prowler"));
            AssertJUnit.assertEquals(1, k8.getStats().deadPumpForge);
            AssertJUnit.assertEquals(0, k8.getStats().deadPumpDropped);
        } finally {
            s.shutdown();
            k8.shutdown();
        }
    }

    @Test
    public void theOpponentsAttackerInItsDeclareAttackersStepStays() {
        Game game = reported(PhaseType.COMBAT_DECLARE_ATTACKERS, false);
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        Combat combat = new Combat(b);
        combat.addAttacker(find(b, "Oona's Prowler"), a);
        game.getPhaseHandler().setCombat(combat);
        AssertJUnit.assertFalse("-2/-0 on an attacker before damage is two fewer damage", LookaheadSearch.deadPump(prowler(a), a));
    }

    @Test
    public void aCreatureOnTheActiveSideKeepsTheBlockerLive() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        addCard("Grizzly Bears", a).setSickness(false);
        AssertJUnit.assertFalse("the Prowler could block an attacker this turn", LookaheadSearch.deadPump(prowler(a), a));
    }

    @Test
    public void aToughnessChangeStaysAndAPowerOnlyOneIsDead() {
        Game shade = ownMain2("Frozen Shade", "Swamp");
        Player a = shade.getPlayers().get(0);
        SpellAbility sa = pump(a, "Frozen Shade");
        AssertJUnit.assertNotNull(sa);
        AssertJUnit.assertFalse("+1/+1: toughness is not this guard's business", LookaheadSearch.deadPump(sa, a));

        Game dragon = ownMain2("Shivan Dragon", "Mountain");
        Player a2 = dragon.getPlayers().get(0);
        SpellAbility fire = pump(a2, "Shivan Dragon");
        AssertJUnit.assertNotNull(fire);
        AssertJUnit.assertTrue("+1/+0 for mana after the seat's own combat is dead", LookaheadSearch.deadPump(fire, a2));
    }

    @Test
    public void aKeywordGrantStays() {
        Game game = ownMain2("Flame-Chain Mauler", "Mountain", "Mountain");
        Player a = game.getPlayers().get(0);
        SpellAbility sa = pump(a, "Flame-Chain Mauler");
        AssertJUnit.assertNotNull(sa);
        AssertJUnit.assertFalse("+1/+0 and menace", LookaheadSearch.deadPump(sa, a));
    }

    @Test
    public void aSubAbilityWithAnotherEffectStays() {
        Game game = ownMain2("Electric Eel", "Mountain", "Mountain");
        Player a = game.getPlayers().get(0);
        SpellAbility sa = pump(a, "Electric Eel");
        AssertJUnit.assertNotNull(sa);
        AssertJUnit.assertFalse("+2/+0 and 1 damage to you", LookaheadSearch.deadPump(sa, a));
    }

    @Test
    public void aPermanentDurationStays() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        SpellAbility sa = prowler(a);
        sa.putParam("Duration", "Permanent"); // the same ability with a lasting effect (this test game only)
        AssertJUnit.assertFalse("a lasting power change outlives the turn", LookaheadSearch.deadPump(sa, a));
    }

    @Test
    public void aDiscardTriggerInPlayStays() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        addCard("Archfiend of Ifnir", a).setSickness(true); // it cannot attack this turn, so the Prowler stays inert
        AssertJUnit.assertFalse("the discard itself does something", LookaheadSearch.deadPump(prowler(a), a));
    }

    @Test
    public void madnessInHandStays() {
        Game game = reported();
        Player a = game.getPlayers().get(0);
        addCardToZone("Fiery Temper", a, ZoneType.Hand);
        AssertJUnit.assertFalse("a discard outlet casts a madness card", LookaheadSearch.deadPump(prowler(a), a));
    }
}
