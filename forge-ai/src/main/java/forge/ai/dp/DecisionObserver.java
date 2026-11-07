package forge.ai.dp;

/**
 * decisionpoint-1102: sees every decision point after it was answered. Must not change the game: no RNG draw, no id
 * allocation, no lazy trait build, no AI memory write (CENSUS.md §5.2). {@code by} names who answered: {@code forge}
 * (Forge AI's own answer stood), {@code cac} (the choose-a-card hook replaced it), {@code answerer}, {@code refused}
 * (an answerer's illegal answer was refused: Forge's stood), or a research answerer's name noted by its own code
 * ({@code solver}, {@code plans}, ...).
 */
@FunctionalInterface
public interface DecisionObserver {
    void observe(DecisionPoint dp, Object answer, String by);
}
