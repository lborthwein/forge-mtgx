package forge.ai.simulation;

import java.util.List;

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
 * The zero-X guard (Config.zeroX, lane misplay-portablehole-1003). The owner's report of 2026-10-04T06:10Z: the
 * look-ahead seat cast Pest Infestation (X X G) with two mana, so X = 0: nothing destroyed, no tokens. Forge's own AI
 * played its land; the search departed because spending the Forest changed Forge AI's own follow-up in the play-outs.
 * OFF (the default) keeps the candidate; ON drops it; SHADOW keeps it and counts it.
 */
public class LookaheadZeroXTest extends SimulationTest {

    private Game board(int forests) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Forest" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        for (int i = 0; i < forests; i++) {
            addCard("Forest", a);
        }
        addCard("Swamp", b);
        addCardToZone("Pest Infestation", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch search(AiFixes.Mode mode) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_000_004L;
        c.zeroX = mode;
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

    private static boolean hasPest(List<LookaheadSearch.Cand> cands) {
        for (LookaheadSearch.Cand c : cands) {
            if (c.label.startsWith("Pest Infestation")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.zeroX);
        AssertJUnit.assertFalse(c.toJson().has("zeroX"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("zeroX"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void detector() {
        Game g1 = board(1);
        Player a1 = g1.getPlayers().get(0);
        AssertJUnit.assertTrue("one Forest: X = 0", LookaheadSearch.zeroXSpell(castOf(a1, "Pest Infestation"), a1));
        Game g3 = board(3);
        Player a3 = g3.getPlayers().get(0);
        AssertJUnit.assertFalse("three Forests: X = 1 is payable", LookaheadSearch.zeroXSpell(castOf(a3, "Pest Infestation"), a3));
        addCardToZone("Grizzly Bears", a1, ZoneType.Hand);
        AssertJUnit.assertFalse("no X in the cost", LookaheadSearch.zeroXSpell(castOf(a1, "Grizzly Bears"), a1));
        AssertJUnit.assertFalse(LookaheadSearch.zeroXSpell(null, a1));
        Game ua = board(0);
        Player au = ua.getPlayers().get(0);
        addCard("Plains", au);
        addCard("Plains", au);
        addCard("Grizzly Bears", ua.getPlayers().get(1));
        addCardToZone("Unexpectedly Absent", au, ZoneType.Hand);
        AssertJUnit.assertFalse("X = 0 still targets: Unexpectedly Absent is left alone",
                LookaheadSearch.zeroXSpell(castOf(au, "Unexpectedly Absent"), au));
    }

    @Test
    public void offKeepsOnDropsShadowCounts() {
        Game g = board(1);
        Player a = g.getPlayers().get(0);
        LookaheadSearch off = search(AiFixes.Mode.OFF), on = search(AiFixes.Mode.ON), sh = search(AiFixes.Mode.SHADOW);
        try {
            String before = LookaheadSearch.fingerprint(g);
            AssertJUnit.assertTrue(hasPest(off.enumerate(g, a, null, 1L, null)));
            AssertJUnit.assertFalse(hasPest(on.enumerate(g, a, null, 1L, null)));
            AssertJUnit.assertTrue(hasPest(sh.enumerate(g, a, null, 1L, null)));
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g));
            AssertJUnit.assertEquals(1, on.getStats().zeroXDropped);
            AssertJUnit.assertEquals(1, sh.getStats().zeroXCands);
            AssertJUnit.assertEquals(0, sh.getStats().zeroXDropped);
            AssertJUnit.assertTrue(sh.zeroXKeys.size() == 1);
        } finally {
            off.shutdown();
            on.shutdown();
            sh.shutdown();
        }
    }

    @Test
    public void onNeverCastsItAndLeavesLiveUntouched() {
        Game g = board(1);
        Player a = g.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(g);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            List<SpellAbility> got = s.decide(ctrl, def);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g));
            AssertJUnit.assertFalse(got != null && !got.isEmpty() && got.get(0).getHostCard().getName().equals("Pest Infestation"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void forgesOwnAnswerIsNeverDropped() {
        Game g = board(1);
        Player a = g.getPlayers().get(0);
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            List<LookaheadSearch.Cand> cands = s.enumerate(g, a, castOf(a, "Pest Infestation"), 1L, null);
            AssertJUnit.assertTrue(cands.get(0).isDefault);
            AssertJUnit.assertTrue(cands.get(0).label.startsWith("Pest Infestation"));
            AssertJUnit.assertEquals(0, s.getStats().zeroXDropped);
        } finally {
            s.shutdown();
        }
    }
}
