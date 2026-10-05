package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Until-end-of-turn copy fidelity (GameCopier#setCopyUntilEot, LookaheadSearch.Config.copyEot, lane misplays-1005).
 * Session 486d4d1b: after the live game crewed Esika's Chariot, every copy kept the Chariot an artifact creature for
 * good (the command that ends the crew at cleanup was not copied), so a reused world's candidate-0 value (from a kept
 * play-out where the crew ended) sat 61 below every fresh candidate in every world, and the search departed to a second
 * Crew at a flat +61. With the option on, the copy carries the command and the crew ends at the copy's cleanup.
 */
public class LookaheadCopyEotTest extends SimulationTest {

    /** A game in which p has just crewed Smuggler's Copter (resolved in a simulator copy, which is then "live"). */
    private Game crewed() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card copter = addCard("Smuggler's Copter", p);
        addCard("Grizzly Bears", p).setSickness(false);
        game.getAction().checkStateEffects(true);
        SpellAbility crew = findSAWithPrefix(copter, "Crew");
        AssertJUnit.assertNotNull(crew);
        GameSimulator sim = createSimulator(p);
        sim.simulateSpellAbility(crew);
        Game live = sim.getSimulatedGameState();
        AssertJUnit.assertTrue(findCardWithName(live, "Smuggler's Copter").isCreature());
        return live;
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.copyEot);
        AssertJUnit.assertFalse(c.toJson().has("copyEot"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("copyEot"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void crewEndsAtTheCopysCleanupOnlyWhenCarried() {
        Game live = crewed();
        Card liveCopter = findCardWithName(live, "Smuggler's Copter");
        int[] u = GameCopier.untilCounts(live);
        AssertJUnit.assertTrue("the crew left an until-end-of-turn command", u[0] >= 1);
        AssertJUnit.assertEquals("and it can be remapped", u[0], u[1]);

        // Off (the copier as before): the copy never ends the crew.
        GameCopier off = new GameCopier(live, true);
        Game a = off.makeCopy();
        AssertJUnit.assertEquals(0, off.getUntilCopied());
        a.getEndOfTurn().executeUntil();
        AssertJUnit.assertTrue(findCardWithName(a, "Smuggler's Copter").isCreature());

        // On: the copy carries the command; its cleanup ends the crew in the copy only.
        GameCopier on = new GameCopier(live, true);
        on.setCopyUntilEot(true);
        Game b = on.makeCopy();
        AssertJUnit.assertEquals(u[0], on.getUntilCopied());
        AssertJUnit.assertEquals(0, on.getUntilRefused());
        Card copyCopter = findCardWithName(b, "Smuggler's Copter");
        AssertJUnit.assertTrue(copyCopter.isCreature());
        b.getEndOfTurn().executeUntil();
        AssertJUnit.assertFalse(copyCopter.isCreature());

        // The live game is untouched: still crewed, its command still pending.
        AssertJUnit.assertTrue(liveCopter.isCreature());
        AssertJUnit.assertEquals(u[0], GameCopier.untilCounts(live)[0]);
        live.getEndOfTurn().executeUntil();
        AssertJUnit.assertFalse(liveCopter.isCreature());
    }

    @Test
    public void pumpEndsAtTheCopysCleanupOnlyWhenCarried() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Forest", p);
        Card bear = addCard("Grizzly Bears", p);
        bear.setSickness(false);
        Card growth = addCardToZone("Giant Growth", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        SpellAbility gg = growth.getFirstSpellAbility();
        gg.setActivatingPlayer(p);
        gg.getTargets().add(bear);
        GameSimulator sim = createSimulator(p);
        sim.simulateSpellAbility(gg);
        Game live = sim.getSimulatedGameState();
        AssertJUnit.assertEquals(5, findCardWithName(live, "Grizzly Bears").getNetPower());

        GameCopier off = new GameCopier(live, true);
        Game a = off.makeCopy();
        a.getEndOfTurn().executeUntil();
        AssertJUnit.assertEquals("off: the pump never ends in the copy", 5, findCardWithName(a, "Grizzly Bears").getNetPower());

        GameCopier on = new GameCopier(live, true);
        on.setCopyUntilEot(true);
        Game b = on.makeCopy();
        AssertJUnit.assertTrue(on.getUntilCopied() >= 1);
        b.getEndOfTurn().executeUntil();
        AssertJUnit.assertEquals("on: the pump ends at the copy's cleanup", 2, findCardWithName(b, "Grizzly Bears").getNetPower());
        AssertJUnit.assertEquals("live untouched", 5, findCardWithName(live, "Grizzly Bears").getNetPower());
    }

    @Test
    public void searchCopiersFollowTheOption() {
        Game live = crewed();
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.copyEot = AiFixes.Mode.ON;
        LookaheadSearch on = new LookaheadSearch(c);
        LookaheadSearch.Config c2 = new LookaheadSearch.Config();
        c2.copyEot = AiFixes.Mode.SHADOW;
        LookaheadSearch sh = new LookaheadSearch(c2);
        try {
            GameCopier g1 = on.copierOf(live);
            g1.makeCopy();
            AssertJUnit.assertTrue(g1.getUntilCopied() >= 1);
            GameCopier g2 = sh.copierOf(live);
            g2.makeCopy();
            AssertJUnit.assertEquals("shadow copies as before", 0, g2.getUntilCopied());
        } finally {
            on.shutdown();
            sh.shutdown();
        }
    }
}
