package forge.ai.simulation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
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
 * The dead-ETB guard (Config.deadEtb, lane misplay-portablehole-1003). The owner's report of 2026-10-04T03:09Z: the
 * look-ahead seat cast Portable Hole in its second main phase while the owner controlled only a Swamp. Forge's own AI
 * passed (its ETB check refuses a trigger with no target); the search departed because the static evaluator scores the
 * artifact on the battlefield at 50 + 30 * MV against 5 in hand, the same +75 in every world, which departZ accepts.
 * OFF (the default) reproduces that departure; ON removes the candidate; SHADOW decides as OFF and counts it.
 */
public class LookaheadDeadEtbTest extends SimulationTest {

    /** Seat a: Plains (untapped), Portable Hole in hand, second main phase. Seat b: a Swamp, and {@code bExtra}. */
    private Game board(String bExtra, String aHand) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? (p == a ? "Plains" : "Swamp") : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        addCard("Plains", a);
        addCard("Swamp", b);
        if (bExtra != null) {
            addCard(bExtra, b);
        }
        addCardToZone(aHand, a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private Game board(String bExtra) {
        return board(bExtra, "Portable Hole");
    }

    private static LookaheadSearch search(AiFixes.Mode mode) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 4;
        c.breadth = 4;
        c.horizonTurns = 2;
        c.threads = 1;
        c.seed = 719_000_003L;
        c.departZ = 1.645;
        c.deadEtb = mode;
        return new LookaheadSearch(c);
    }

    private static SpellAbility castOf(Player p, String name) {
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals(name)) {
                SpellAbility sa = c.getFirstSpellAbility();
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        return null;
    }

    private static Set<String> labels(List<LookaheadSearch.Cand> cands) {
        Set<String> out = new HashSet<>();
        for (LookaheadSearch.Cand c : cands) {
            out.add(c.label);
        }
        return out;
    }

    private static boolean hasHole(List<LookaheadSearch.Cand> cands) {
        for (LookaheadSearch.Cand c : cands) {
            if (c.label.startsWith("Portable Hole")) {
                return true;
            }
        }
        return false;
    }

    private static boolean castsHole(List<SpellAbility> answer) {
        return answer != null && !answer.isEmpty() && answer.get(0).getHostCard().getName().equals("Portable Hole");
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.deadEtb);
        AssertJUnit.assertFalse(c.toJson().has("deadEtb"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("deadEtb"));
        } finally {
            s.shutdown();
        }
        c.deadEtb = AiFixes.Mode.SHADOW;
        AssertJUnit.assertEquals("shadow", c.toJson().get("deadEtb").getAsString());
    }

    @Test
    public void detector() {
        Game game = board(null);
        Player a = game.getPlayers().get(0);
        AssertJUnit.assertTrue("Portable Hole vs a lone Swamp", LookaheadSearch.deadEtbPermanent(castOf(a, "Portable Hole"), a));
        addCardToZone("Banishing Light", a, ZoneType.Hand);
        AssertJUnit.assertTrue("Banishing Light (Origin Any) vs a lone Swamp",
                LookaheadSearch.deadEtbPermanent(castOf(a, "Banishing Light"), a));
        addCardToZone("Sol Ring", a, ZoneType.Hand);
        AssertJUnit.assertFalse("no ETB trigger", LookaheadSearch.deadEtbPermanent(castOf(a, "Sol Ring"), a));
        addCardToZone("Fiend Hunter", a, ZoneType.Hand);
        AssertJUnit.assertFalse("a creature is left to the search", LookaheadSearch.deadEtbPermanent(castOf(a, "Fiend Hunter"), a));
        AssertJUnit.assertFalse(LookaheadSearch.deadEtbPermanent(null, a));

        Game live = board("Llanowar Elves");
        Player a2 = live.getPlayers().get(0);
        AssertJUnit.assertFalse("an MV 1 permanent to exile", LookaheadSearch.deadEtbPermanent(castOf(a2, "Portable Hole"), a2));
        Game big = board("Serra Angel");
        Player a3 = big.getPlayers().get(0);
        AssertJUnit.assertTrue("an MV 5 creature is no target", LookaheadSearch.deadEtbPermanent(castOf(a3, "Portable Hole"), a3));
    }

    @Test
    public void offReproducesTheDeparture() {
        Game game = board(null);
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        AssertJUnit.assertFalse("Forge's own AI does not cast it", castsHole(def));
        LookaheadSearch s = search(AiFixes.Mode.OFF);
        try {
            AssertJUnit.assertTrue(hasHole(s.enumerate(game, a, null, 1L, null)));
            String before = LookaheadSearch.fingerprint(game);
            List<SpellAbility> got = s.decide(ctrl, def);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertTrue("the live misplay: the search departs to Portable Hole", castsHole(got));
            AssertJUnit.assertEquals(1, s.getStats().departed);
            AssertJUnit.assertFalse(s.getStats().toJson().has("deadEtb"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void onDropsTheCandidateAndKeepsForgesPass() {
        Game game = board(null);
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            String before = LookaheadSearch.fingerprint(game);
            AssertJUnit.assertFalse(hasHole(s.enumerate(game, a, null, 1L, null)));
            List<SpellAbility> got = s.decide(ctrl, def);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertFalse(castsHole(got));
            AssertJUnit.assertEquals(0, s.getStats().departed);
            AssertJUnit.assertEquals(2, s.getStats().deadEtbDropped);
            AssertJUnit.assertEquals(2, s.getStats().deadEtbDecisions);
            AssertJUnit.assertEquals("on", s.getStats().toJson().getAsJsonObject("deadEtb").get("mode").getAsString());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void shadowDecidesAsOffAndCounts() {
        Game g1 = board(null);
        Player a1 = g1.getPlayers().get(0);
        PlayerControllerAi c1 = (PlayerControllerAi) a1.getController();
        LookaheadSearch off = search(AiFixes.Mode.OFF);
        Game g2 = board(null);
        Player a2 = g2.getPlayers().get(0);
        PlayerControllerAi c2 = (PlayerControllerAi) a2.getController();
        LookaheadSearch shadow = search(AiFixes.Mode.SHADOW);
        try {
            AssertJUnit.assertEquals(labels(off.enumerate(g1, a1, null, 1L, null)), labels(shadow.enumerate(g2, a2, null, 1L, null)));
            List<SpellAbility> d1 = off.decide(c1, c1.chooseSpellAbilityToPlay());
            List<SpellAbility> d2 = shadow.decide(c2, c2.chooseSpellAbilityToPlay());
            AssertJUnit.assertEquals(castsHole(d1), castsHole(d2));
            AssertJUnit.assertTrue(castsHole(d2));
            AssertJUnit.assertEquals(off.getStats().departed, shadow.getStats().departed);
            AssertJUnit.assertEquals(1, shadow.getStats().deadEtbShadowBest);
            AssertJUnit.assertEquals(0, shadow.getStats().deadEtbDropped);
            AssertJUnit.assertEquals(2, shadow.getStats().deadEtbCands);
        } finally {
            off.shutdown();
            shadow.shutdown();
        }
    }

    @Test
    public void onKeepsItWithALegalTarget() {
        Game game = board("Llanowar Elves");
        Player a = game.getPlayers().get(0);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            AssertJUnit.assertTrue(hasHole(s.enumerate(game, a, null, 1L, null)));
            AssertJUnit.assertEquals(0, s.getStats().deadEtbDropped);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void forgesOwnAnswerIsNeverDropped() {
        Game game = board(null);
        Player a = game.getPlayers().get(0);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            List<LookaheadSearch.Cand> cands = s.enumerate(game, a, castOf(a, "Portable Hole"), 1L, null);
            AssertJUnit.assertTrue(cands.get(0).isDefault);
            AssertJUnit.assertTrue(cands.get(0).label.startsWith("Portable Hole"));
            AssertJUnit.assertEquals(1, s.getStats().deadEtbForge);
            AssertJUnit.assertEquals(0, s.getStats().deadEtbDropped);
        } finally {
            s.shutdown();
        }
    }
}
