package forge.game.player;

import forge.game.card.Card;
import forge.game.card.CardDamageTable;
import forge.game.combat.CombatDamageAssignment;
import java.util.Map;

/** Optional transport boundary. Stock controllers retain their existing API.
 * A successful callback is provisional until the whole assignment is checked. */
public interface ScopedCombatDamageAssignment {
    boolean requiresCombatDamageAssignmentScope();
    Map<Card, Integer> assignCombatDamageInScope(CombatDamageAssignment context);
    void finishCombatDamageAssignment(CombatDamageAssignment context, CardDamageTable table);
    void failCombatDamageAssignment(Throwable failure);
}
