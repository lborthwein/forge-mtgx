package forge.ai.simulation;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.card.mana.ManaAtom;
import forge.game.EngineFixes;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
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
        // what a copy starts from (the copier does not carry the count unless copy fidelity is on)
        final int copyStart = new GameCopier(g[1]).makeCopy().getPlayer(id).getExpentThisTurn();

        final Game copy = castConfidantFrom(g[1], id);

        AssertJUnit.assertEquals("the original player's mana-spent count", spentBefore, origP.getExpentThisTurn());
        AssertJUnit.assertEquals("the original game's waiting triggers", waitingBefore, waiting(g[1]));
        AssertJUnit.assertEquals("the original pool", 3, origP.getManaPool().totalMana());
        AssertJUnit.assertEquals("the copy counts its own spend", copyStart + 2, copy.getPlayer(id).getExpentThisTurn());
    }

    @Test
    public void offTheOldCopyWritesTheOriginal() throws ReflectiveOperationException {
        EngineFixes.setCopyPoolManaForTest(false); // forge.liveFixes=false: the old copier, byte for byte
        final Game[] g = ritualFloating();
        final int id = g[0].getPlayers().get(1).getId();
        final Player origP = g[1].getPlayer(id);
        final int spentBefore = origP.getExpentThisTurn();
        final int waitingBefore = waiting(g[1]);

        final int copyStart = new GameCopier(g[1]).makeCopy().getPlayer(id).getExpentThisTurn();
        final Game copy = castConfidantFrom(g[1], id);

        AssertJUnit.assertEquals("the leak this fix closes", spentBefore + 2, origP.getExpentThisTurn());
        AssertJUnit.assertTrue("a ManaExpend trigger held on the original", waiting(g[1]) > waitingBefore);
        AssertJUnit.assertEquals("the copy never sees its own spend", copyStart, copy.getPlayer(id).getExpentThisTurn());
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
    /** A game with a Swamp and Dark Ritual for p1 in main 2; returns p1. */
    private Player swampGame(final Game game) {
        final Player p1 = game.getPlayers().get(1);
        addCard("Swamp", p1);
        addCardToZone("Dark Ritual", p1, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p1);
        game.getAction().checkStateEffects(true);
        return p1;
    }

    private static Card swampOf(final Player p) {
        return p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Swamp")).findFirst().orElseThrow();
    }

    /** Floats {B} from the Swamp's own ability (a copy of it when {@code piracy}: the extra "Spell" restriction on it). */
    private static void floatSwamp(final Player p1, final boolean piracy) {
        SpellAbility sa = swampOf(p1).getManaAbilities().get(0);
        if (piracy) {
            sa = sa.copy(p1);
            sa.getManaPart().setExtraManaRestriction("Spell");
        }
        sa.setActivatingPlayer(p1);
        sa.getManaPart().produceMana(sa);
        AssertJUnit.assertEquals(1, p1.getManaPool().totalMana());
    }

    @Test
    public void landManaIsTheCopysOwnAndSpendsThere() {
        EngineFixes.setCopyPoolManaForTest(true);
        final Game game = initAndCreateGame();
        final Player p1 = swampGame(game);
        floatSwamp(p1, false);
        final int spentBefore = p1.getExpentThisTurn();

        final Game copy = new GameCopier(game).makeCopy();
        final Player copyP = copy.getPlayer(p1.getId());
        final Mana m = copyP.getManaPool().iterator().next();
        AssertJUnit.assertSame(copyP, m.getPlayer());
        AssertJUnit.assertSame("a last-known copy of the copy's Swamp", copy, m.getSourceCard().getGame());
        AssertJUnit.assertSame("the copy's Swamp's own ability", swampOf(copyP).getManaAbilities().get(0).getManaPart(), m.getManaAbility());

        final Card ritual = p1.getCardsIn(ZoneType.Hand).get(0);
        final GameSimulator sim = createSimulator(p1);
        sim.simulateSpellAbility(ritual.getSpellAbilities().get(0));
        AssertJUnit.assertEquals("cast from the floating {B}", 3, sim.getSimulatedGameState().getPlayer(p1.getId()).getManaPool().totalMana());
        AssertJUnit.assertEquals("the original player's count", spentBefore, p1.getExpentThisTurn());
        AssertJUnit.assertEquals("the original pool", 1, p1.getManaPool().totalMana());
    }

    @Test
    public void anExtraRestrictionTravels() {
        EngineFixes.setCopyPoolManaForTest(true);
        final Game game = initAndCreateGame();
        final Player p1 = swampGame(game);
        floatSwamp(p1, true);
        final Game copy = new GameCopier(game).makeCopy();
        final Mana m = copy.getPlayer(p1.getId()).getManaPool().iterator().next();
        AssertJUnit.assertEquals("Spell", m.getManaAbility().getExtraManaRestriction());
        AssertJUnit.assertSame(copy, m.getManaAbility().getSourceCard().getGame());
    }

    private static Map<String, String> noCounterParams() {
        final Map<String, String> params = new HashMap<>();
        params.put("Produced", "C");
        params.put("RestrictValid", "Instant,Sorcery");
        params.put("AddsNoCounter", "True");
        return params;
    }

    @Test
    public void anUnmappedAbilitysSpendEffectLandsInTheCopy() {
        EngineFixes.setCopyPoolManaForTest(true);
        final Game game = initAndCreateGame();
        final Player p1 = swampGame(game);
        // a mana ability that is none of the Swamp's own (as a trigger's or a decorated spell's): findManaPart misses it
        final AbilityManaPart part = new AbilityManaPart(swampOf(p1), noCounterParams());
        p1.getManaPool().addManaNoEvent(new Mana((byte) ManaAtom.COLORLESS, swampOf(p1), part, p1));
        final int origCommand = game.getCardsIn(ZoneType.Command).size();

        final Game copy = new GameCopier(game).makeCopy();
        final Player copyP = copy.getPlayer(p1.getId());
        final Mana m = copyP.getManaPool().iterator().next();
        AssertJUnit.assertNotSame(part, m.getManaAbility());
        AssertJUnit.assertSame(copy, m.getManaAbility().getSourceCard().getGame());
        AssertJUnit.assertTrue("restriction kept", m.isRestricted());
        final SpellAbility spell = copyP.getCardsIn(ZoneType.Hand).get(0).getSpellAbilities().get(0);
        AssertJUnit.assertTrue("can't-be-countered kept", m.addsNoCounterMagic(spell));
        final int copyCommand = copy.getCardsIn(ZoneType.Command).size();
        m.getManaAbility().addNoCounterEffect(spell);
        AssertJUnit.assertEquals("the effect lands in the copy", copyCommand + 1, copy.getCardsIn(ZoneType.Command).size());
        AssertJUnit.assertEquals("never in the original", origCommand, game.getCardsIn(ZoneType.Command).size());
    }

    @Test
    public void aSourceNotInTheCopyDropsItsSpendEffects() {
        EngineFixes.setCopyPoolManaForTest(true);
        final Game game = initAndCreateGame();
        final Player p1 = swampGame(game);
        // a source in no zone (a token that ceased to exist): nothing in the copy to map it to
        final Card gone = new Card(game.nextCardId(), game);
        gone.setOwner(p1);
        final AbilityManaPart part = new AbilityManaPart(gone, noCounterParams());
        p1.getManaPool().addManaNoEvent(new Mana((byte) ManaAtom.COLORLESS, gone, part, p1));

        final Game copy = new GameCopier(game).makeCopy();
        final Player copyP = copy.getPlayer(p1.getId());
        final Mana m = copyP.getManaPool().iterator().next();
        AssertJUnit.assertSame(copyP, m.getPlayer());
        AssertJUnit.assertNotSame("never the original's ability", part, m.getManaAbility());
        AssertJUnit.assertTrue("restriction kept", m.isRestricted());
        final SpellAbility spell = copyP.getCardsIn(ZoneType.Hand).get(0).getSpellAbilities().get(0);
        AssertJUnit.assertFalse("its effect would act on the original game: dropped", m.addsNoCounterMagic(spell));
        AssertJUnit.assertTrue("the original's mana keeps it", p1.getManaPool().iterator().next().addsNoCounterMagic(spell));
    }

    @Test
    public void oneProductionStaysOneObject() {
        EngineFixes.setCopyPoolManaForTest(true);
        final Game[] g = ritualFloating();
        final int id = g[0].getPlayers().get(1).getId();
        final java.util.Set<Mana> orig = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        g[1].getPlayer(id).getManaPool().forEach(orig::add);
        final java.util.Set<Mana> copied = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        new GameCopier(g[1]).makeCopy().getPlayer(id).getManaPool().forEach(copied::add);
        AssertJUnit.assertEquals("as many distinct Mana objects as the original", orig.size(), copied.size());
    }
}
