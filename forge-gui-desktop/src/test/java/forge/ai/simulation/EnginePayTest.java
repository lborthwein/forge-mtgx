package forge.ai.simulation;

import java.util.HashMap;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;

/**
 * Lane ledger-sweep-1107, ERROR-LEDGER row 60. Engine moves leave the payment to Forge AI; on wall budgets nothing
 * searched it (payRecord, labeller-v2 (e), refuses ms budgets; paySearch is the planner's; followPay only pays Phyrexian
 * pips with life). The position is LabellerV2ChoicesTest's: Impulse ({1}{U}) finds Psionic Blast ({2}{U}, 4 damage) at 4
 * life; Forge AI pays Impulse with both Islands, so no blue is left for the Blast. The planner is starved (1 node) and
 * Exhume puts the engine on. Key {@code enginePay} off: no proven line on a wall budget. On: the engine's payment variant
 * keeps an Island, and the line records its sources (" pay[").
 */
public class EnginePayTest extends AITest {

    static ForcedWinSolver.Config wall(boolean enginePay) {
        final Map<String, String> m = new HashMap<>();
        m.put("defense", "false");
        m.put("exact", "true");
        m.put("act", "false");
        m.put("horizon", "1");
        m.put("nodes", "1");
        m.put("frameMemory", "true");
        m.put("engineNodes", "200");
        m.put("budgetMs", "30000");
        m.put("engineMs", "25000");
        m.put("evalReserveMs", "4000");
        if (enginePay) {
            m.put("enginePay", "true");
        }
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(m::get);
        Assert.assertFalse(c.counter, "a wall budget");
        Assert.assertFalse(c.payRecord, "payRecord (counts only) stays off");
        return c;
    }

    @Test
    public void keyOffForgeAisPaymentLeavesNoBlue() {
        final ForcedWinSolver.Proposal p = LabellerV2ChoicesTest.best(LabellerV2ChoicesTest.blast(), wall(false));
        Assert.assertNull(p, "Forge AI's payment taps both Islands: " + (p == null ? "" : p.text));
    }

    @Test
    public void keyOnTheEnginePaysToKeepAnIsland() {
        final ForcedWinSolver.Config c = wall(true);
        Assert.assertTrue(c.enginePay);
        final ForcedWinSolver.Proposal p = LabellerV2ChoicesTest.best(LabellerV2ChoicesTest.blast(), c);
        Assert.assertNotNull(p, "a payment variant keeps an Island");
        Assert.assertTrue(p.text.contains(" pay["), "the line floats its recorded sources: " + p.text);
    }
}
