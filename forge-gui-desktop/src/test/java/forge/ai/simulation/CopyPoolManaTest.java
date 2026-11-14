package forge.ai.simulation;

import java.lang.reflect.Field;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.game.EngineFixes;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;

/**
 * copy-pool-mana-1113: a game copy's floating mana is the copy's own. Before, a copy shared the original's
 * {@code Mana} objects, so a play-out that spent floating mana raised the ORIGINAL player's mana-spent count and held a
 * ManaExpend trigger on the ORIGINAL game's waiting list (MagicStack.add groups the paying mana by its player), from
 * the play-out's thread: the "wt is null" crash when two play-outs did it at once.
 */
public class CopyPoolManaTest extends SimulationTest {
    private boolean was;

    @BeforeMethod
    public void save() {
        was = EngineFixes.copyPoolMana();
    }

    @AfterMethod
    public void restore() {
        EngineFixes.setCopyPoolManaForTest(was);
    }

    private static int waiting(final Game g) throws ReflectiveOperationException {
        final Field f = TriggerHandler.class.getDeclaredField("waitingTriggers");
        f.setAccessible(true);
        return ((List<?>) f.get(g.getTriggerHandler())).size();
    }

    /** The "original": a game with BBB floating from Dark Ritual and Dark Confidant in hand. */
    private Game[] ritualFloating() {
        final Game game = initAndCreateGame();
        final Player p1 = game.getPlayers().get(1);
        addCard("Swamp", p1);
        final Card ritual = addCardToZone("Dark Ritual", p1, ZoneType.Hand);
        addCardToZone("Dark Confidant", p1, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p1);
        game.getAction().checkStateEffects(true);
        final GameSimulator sim = createSimulator(p1);
        sim.simulateSpellAbility(ritual.getSpellAbilities().get(0));
        final Game orig = sim.getSimulatedGameState();
        AssertJUnit.assertEquals(3, orig.getPlayer(p1.getId()).getManaPool().totalMana());
        return new Game[] { game, orig };
    }

    /** A play-out from {@code orig} casts Dark Confidant from the floating mana; returns its game. */
    private Game castConfidantFrom(final Game orig, final int playerId) {
        final Player origP = orig.getPlayer(playerId);
        final Card confidant = origP.getCardsIn(ZoneType.Hand).stream()
                .filter(c -> c.getName().equals("Dark Confidant")).findFirst().orElseThrow();
        final GameSimulator sim = createSimulator(origP);
        sim.simulateSpellAbility(confidant.getSpellAbilities().get(0));
        final Game copy = sim.getSimulatedGameState();
        AssertJUnit.assertEquals("paid from the pool", 1, copy.getPlayer(playerId).getManaPool().totalMana());
        return copy;
    }

    @Test
    public void aPlayOutSpendingFloatingManaLeavesTheOriginalAlone() throws ReflectiveOperationException {
        EngineFixes.setCopyPoolManaForTest(true);
        final Game[] g = ritualFloating();
        final int id = g[0].getPlayers().get(1).getId();
        final Player origP = g[1].getPlayer(id);
        final int spentBefore = origP.getExpentThisTurn();
        final int waitingBefore = waiting(g[1]);

        final Game copy = castConfidantFrom(g[1], id);

        AssertJUnit.assertEquals("the original player's mana-spent count", spentBefore, origP.getExpentThisTurn());
        AssertJUnit.assertEquals("the original game's waiting triggers", waitingBefore, waiting(g[1]));
        AssertJUnit.assertEquals("the original pool", 3, origP.getManaPool().totalMana());
        AssertJUnit.assertEquals("the copy counts its own spend", 2, copy.getPlayer(id).getExpentThisTurn());
    }

    @Test
    public void offTheOldCopyWritesTheOriginal() throws ReflectiveOperationException {
        EngineFixes.setCopyPoolManaForTest(false); // forge.liveFixes=false: the old copier, byte for byte
        final Game[] g = ritualFloating();
        final int id = g[0].getPlayers().get(1).getId();
        final Player origP = g[1].getPlayer(id);
        final int spentBefore = origP.getExpentThisTurn();
        final int waitingBefore = waiting(g[1]);

        final Game copy = castConfidantFrom(g[1], id);

        AssertJUnit.assertEquals("the leak this fix closes", spentBefore + 2, origP.getExpentThisTurn());
        AssertJUnit.assertTrue("a ManaExpend trigger held on the original", waiting(g[1]) > waitingBefore);
        AssertJUnit.assertEquals(0, copy.getPlayer(id).getExpentThisTurn());
    }

    @Test
    public void theCopysPoolManaIsTheCopysOwn() {
        EngineFixes.setCopyPoolManaForTest(true);
        final Game[] g = ritualFloating();
        final int id = g[0].getPlayers().get(1).getId();
        final Game copy = new GameCopier(g[1]).makeCopy();
        final Player copyP = copy.getPlayer(id);
        AssertJUnit.assertEquals(3, copyP.getManaPool().totalMana());
        for (final Mana m : copyP.getManaPool()) {
            AssertJUnit.assertSame("player", copyP, m.getPlayer());
            AssertJUnit.assertSame("source card's game", copy, m.getSourceCard().getGame());
            AssertJUnit.assertEquals("Dark Ritual", m.getSourceCard().getName());
            AssertJUnit.assertNotNull(m.getManaAbility());
            AssertJUnit.assertSame("mana ability's card's game", copy, m.getManaAbility().getSourceCard().getGame());
        }
        for (final Mana m : g[1].getPlayer(id).getManaPool()) {
            AssertJUnit.assertSame("the original's mana is untouched", g[1], m.getPlayer().getGame());
            AssertJUnit.assertSame(g[1], m.getManaAbility().getSourceCard().getGame());
        }
    }
}
