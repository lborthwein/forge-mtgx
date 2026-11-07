package forge.ai.dp;

/**
 * decisionpoint-1102 (layer 2, CENSUS.md §5.1): the five structural kinds of a choice. A kind says what an answer IS;
 * why the engine asked travels separately as the point's ctx (the controller method or seam, plus its ApiType or cost
 * part), so a new card adds ctx values, never kinds.
 */
public enum Kind {
    /** One legal action, or pass (null). */
    PRIORITY,
    /** A subset of a finite list of game objects with min/max (a single pick is min = max = 1). */
    SELECT,
    /** Items assigned to bins, ordered where the bin is (trigger order, scry, zone order, attackers, blockers). */
    ARRANGE,
    /** An integer in a range or from a list (X, announce, divided amounts, combat damage per recipient). */
    NUMBER,
    /** One label from a finite list (yes/no, colour, type, name, replacement choice, mulligan keep). */
    CHOICE
}
