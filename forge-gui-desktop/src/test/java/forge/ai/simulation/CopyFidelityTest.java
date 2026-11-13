package forge.ai.simulation;

import java.lang.reflect.Field;
import java.util.Collection;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.base.Supplier;

import forge.game.EngineFixes;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * state-machines-1113: the three copy-fidelity flags (all off by default). Off, a copy is exactly what it was before
 * (the turn's step counters start at zero, phased-out permanents are missing, the first-strike record is empty); on,
 * the copy carries the original's state.
 */
public class CopyFidelityTest extends SimulationTest {

    @AfterMethod
    public void flagsOff() {
        EngineFixes.setCopyFidelityForTest(false, false, false);
    }

    private static Card named(final Iterable<Card> cards, final String name) {
        for (Card c : cards) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- turn counters

    private Game inFirstMainAfterOneMain() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        game.getPhaseHandler().dangerouslySetTurnCounters(new PhaseHandler.TurnCounters(1, 3, 0, 1, 0, 0, false, false));
        return game;
    }

    @Test
    public void turnCountersOffStartAtZero() {
        final Game copy = new GameCopier(inFirstMainAfterOneMain(), true).makeCopy();
        AssertJUnit.assertEquals(PhaseHandler.TurnCounters.FRESH, copy.getPhaseHandler().getTurnCounters());
    }

    @Test
    public void turnCountersOnAreCarried() {
        EngineFixes.setCopyFidelityForTest(false, true, false);
        final Game live = inFirstMainAfterOneMain();
        final Game copy = new GameCopier(live, true).makeCopy();
        AssertJUnit.assertEquals(live.getPhaseHandler().getTurnCounters(), copy.getPhaseHandler().getTurnCounters());
        AssertJUnit.assertEquals(1, copy.getPhaseHandler().getTurnCounters().mainsThisTurn());
    }

    @Test
    public void devResetForPositionIsFresh() {
        final Game live = inFirstMainAfterOneMain();
        live.getPhaseHandler().devResetForPosition();
        AssertJUnit.assertEquals(PhaseHandler.TurnCounters.FRESH, live.getPhaseHandler().getTurnCounters());
    }

    // ---------------------------------------------------------------- phasing

    private Game withPhasedOutBear() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        final Card bear = addCard("Grizzly Bears", me);
        addCard("Runeclaw Bear", me);
        game.getAction().checkStateEffects(true);
        bear.setPhasedOut(me);
        bear.setDirectlyPhasedOut(true);
        return game;
    }

    @Test
    public void phasedOffLeavesThePhasedOutPermanentOut() {
        final Game copy = new GameCopier(withPhasedOutBear(), true).makeCopy();
        AssertJUnit.assertNull(named(copy.getCardsIncludePhasingIn(ZoneType.Battlefield), "Grizzly Bears"));
        AssertJUnit.assertNotNull(named(copy.getCardsIn(ZoneType.Battlefield), "Runeclaw Bear"));
    }

    @Test
    public void phasedOnCarriesItPhasedOut() {
        EngineFixes.setCopyFidelityForTest(false, false, true);
        final Game live = withPhasedOutBear();
        final Game copy = new GameCopier(live, true).makeCopy();
        final Card bear = named(copy.getCardsIncludePhasingIn(ZoneType.Battlefield), "Grizzly Bears");
        AssertJUnit.assertNotNull(bear);
        AssertJUnit.assertTrue(bear.isPhasedOut());
        AssertJUnit.assertTrue(bear.isDirectlyPhasedOut());
        AssertJUnit.assertEquals(copy.getPlayers().get(1), bear.getPhasedOut());
        AssertJUnit.assertNull("still hidden from the phased-in view", named(copy.getCardsIn(ZoneType.Battlefield), "Grizzly Bears"));
    }

    // ---------------------------------------------------------------- combat: the first-strike record

    @SuppressWarnings("unchecked")
    private static Collection<Card> firstStruck(final Combat c) throws ReflectiveOperationException {
        final Field f = Combat.class.getDeclaredField("combatantsThatDealtFirstStrikeDamage");
        f.setAccessible(true);
        return ((Supplier<Collection<Card>>) f.get(c)).get();
    }

    private Game afterFirstStrikeDamage() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card knight = addCard("White Knight", me); // first strike
        knight.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, me);
        game.getAction().checkStateEffects(true);
        final Combat combat = new Combat(me);
        combat.addAttacker(knight, opp);
        combat.getBandOfAttacker(knight).setBlocked(false); // unblocked (blockers declared, none)
        game.getPhaseHandler().setCombat(combat);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, me, false);
        combat.assignCombatDamage(true);
        return game;
    }

    @Test
    public void combatStepOffForgetsWhoStruckFirst() throws ReflectiveOperationException {
        final Game live = afterFirstStrikeDamage();
        AssertJUnit.assertEquals(1, firstStruck(live.getPhaseHandler().getCombat()).size());
        final Game copy = new GameCopier(live, true).makeCopy();
        AssertJUnit.assertTrue(firstStruck(copy.getPhaseHandler().getCombat()).isEmpty());
    }

    @Test
    public void combatStepOnRemembersIt() throws ReflectiveOperationException {
        EngineFixes.setCopyFidelityForTest(true, false, false);
        final Game live = afterFirstStrikeDamage();
        final Game copy = new GameCopier(live, true).makeCopy();
        final Collection<Card> struck = firstStruck(copy.getPhaseHandler().getCombat());
        AssertJUnit.assertEquals(1, struck.size());
        final Card knight = struck.iterator().next();
        AssertJUnit.assertEquals("White Knight", knight.getName());
        AssertJUnit.assertSame(copy, knight.getGame());
    }
}
