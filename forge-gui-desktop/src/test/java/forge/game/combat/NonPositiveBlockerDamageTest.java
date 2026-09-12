package forge.game.combat;

import java.lang.reflect.Field;

import org.testng.annotations.Test;

import com.google.common.collect.Table;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardDamageTable;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

/**
 * CR 510.1a: a creature that would assign 0 or less combat damage assigns none.
 *
 * Animate Dead is {@code GainControl$ True} with
 * {@code S:Mode$ Continuous | Affected$ Creature.EnchantedBy | AddPower$ -1}, so
 * reanimating a base 0-power creature such as Wall of Roots produces a -1/5
 * creature. Blocking with it used to reach the blocker damage-assignment path,
 * which -- unlike the attacker path in the same method pair -- had no
 * {@code damage <= 0} guard, and the damage table ended up holding a negative
 * combat damage assignment.
 */
public class NonPositiveBlockerDamageTest extends AITest {

    @SuppressWarnings("unchecked")
    private static CardDamageTable damageTable(Combat combat) throws Exception {
        Field field = Combat.class.getDeclaredField("damageMap");
        field.setAccessible(true);
        return ((com.google.common.base.Supplier<CardDamageTable>) field.get(combat)).get();
    }

    @Test
    public void negativePowerBlockerAssignsNoCombatDamage() throws Exception {
        Game game = initAndCreateGame();
        Player defender = game.getPlayers().get(1);
        Player attackingPlayer = game.getPlayers().get(0);

        Card attacker = addCard("Elder Gargaroth", attackingPlayer);
        Card wall = addCard("Wall of Roots", defender);
        Card animateDead = addCard("Animate Dead", defender);
        // overwrite: the printed "enchant creature card in a graveyard" restriction is
        // what Animate Dead's own resolution replaces before it attaches.
        animateDead.attachToEntity(wall, null, true);

        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, attackingPlayer);
        // Continuous effects only. A state-based check would bin the Aura: its printed
        // Enchant clause names a creature card in a graveyard, and Animate Dead's own
        // resolution is what replaces that clause before it attaches.
        game.getAction().checkStaticAbilities(false);

        assertTrue("the Aura must be attached", wall.isEnchantedBy(animateDead));
        assertEquals("Animate Dead must actually make the blocker -1/5", -1, wall.getNetCombatDamage());
        assertEquals(5, wall.getNetToughness());

        Combat combat = new Combat(attackingPlayer);
        combat.addAttacker(attacker, defender);
        combat.addBlocker(attacker, wall);
        combat.orderBlockersForDamageAssignment();
        combat.orderAttackersForDamageAssignment();
        game.getPhaseHandler().setCombat(combat);

        // Must not throw: the stock AI is never asked to distribute -1 damage.
        combat.assignCombatDamage(false);

        CardDamageTable assigned = damageTable(combat);
        assertNotNull(assigned);
        for (Table.Cell<Card, GameEntity, Integer> cell : assigned.cellSet()) {
            assertTrue("the -1/5 blocker assigned combat damage: " + cell, cell.getRowKey() != wall);
            assertTrue("non-positive combat damage assignment: " + cell, cell.getValue() > 0);
        }
        assertTrue("the attacker still assigns its own combat damage",
                assigned.containsRow(attacker));
    }
}
