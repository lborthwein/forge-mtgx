package forge.ai.dp;

/**
 * decisionpoint-1102: something that answers decision points (a search, a learned policy, a recorded tape, the
 * choose-a-card hook). It sees Forge AI's own answer ({@link DecisionPoint#forgeDefault()}) and the answer so far
 * ({@code current}, which is Forge's unless an earlier answerer departed); returning {@code current} keeps it. An answer
 * that fails {@link DecisionPoints#legal} is refused and Forge's answer is played instead.
 */
@FunctionalInterface
public interface DecisionAnswerer {
    Object answer(DecisionPoint dp, Object current);
}
