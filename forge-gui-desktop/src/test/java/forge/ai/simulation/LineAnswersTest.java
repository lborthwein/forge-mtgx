package forge.ai.simulation;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * line-answers-1114: solverCfg lineAnswers (default off). The NA05 turn-16 shape (live 2026-11-14, sessions 51aa319c
 * and 2b39bb16): the followed line's Chandra, Torch of Defiance +1 was to deal 2 damage to an opponent at 2 life; the
 * effect then offered to cast the exiled card (Mox Sapphire), Forge AI said yes, and no damage was dealt. With the key
 * the line answers that offer "no" (no step of the line casts that card), so the damage resolves; a recorded conf token
 * wins over the default; a confirmation that is no offer to cast stays Forge AI's. No game, no card database.
 */
public class LineAnswersTest {

    @Test
    public void offIsForgeAi() {
        Assert.assertNull(ForcedWinChoices.lineConfirm(false, null, true));
        Assert.assertNull(ForcedWinChoices.lineConfirm(false, ForcedWinChoices.confTok(true), true));
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).lineAnswers, "off by default");
    }

    @Test
    public void turn16ShapeTheLineSaysNoSoTheDamageResolves() {
        Assert.assertEquals(ForcedWinChoices.lineConfirm(true, null, true), Boolean.FALSE,
                "an offer to cast a card the line has no step for: no (Chandra +1 deals its 2 damage)");
        Assert.assertEquals(ForcedWinChoices.lineConfirm(true, ForcedWinChoices.confTok(true), true), Boolean.TRUE,
                "a recorded yes is played as recorded");
        Assert.assertEquals(ForcedWinChoices.lineConfirm(true, ForcedWinChoices.confTok(false), false), Boolean.FALSE);
        Assert.assertNull(ForcedWinChoices.lineConfirm(true, null, false), "another ask without a token: Forge AI's");
        Assert.assertNull(ForcedWinChoices.lineConfirm(true, "pick:Mox Sapphire", false), "a token of another kind is ignored");
    }

    @Test
    public void theKeyParses() {
        final Map<String, String> m = Map.of("lineAnswers", "true");
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(m::get);
        Assert.assertTrue(c.lineAnswers);
        Assert.assertTrue(c.toString().contains("lineAnswers"), c.toString());
    }
}
