package forge.ai;

/**
 * mtgx lane lessons-forge-jar-1027: a taped seat's controller that keeps its own state across a forced replay. In a
 * FORCED (or resume/restore) tape the {@link AiTapeController} answers each top-level choice from the record and never
 * asks its inner controller; a controller that implements this is told of each such choice first, in the same game state
 * the live decision saw, so a stateful seat (a lesson script's spent steps) is where the live seat was when the record
 * ends. Only a lesson's scripted seat implements it; every other taped seat is never told anything.
 */
public interface ForcedChoiceObserver {
    /** The record answers {@code method} now; the game is in the state the live call saw (nothing decoded yet). */
    void beforeForcedChoice(String method);
}
