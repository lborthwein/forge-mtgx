package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.CubeComboAi;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class TapOrUntapAi extends TapAiBase {

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (selectComboUntap(ai, sa)) return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        return super.doTriggerNoCost(ai, sa, mandatory);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
        if (selectComboUntap(ai, sa)) return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        return super.chkDrawback(ai, sa);
    }

    private boolean selectComboUntap(Player ai, SpellAbility sa) {
        Card source = CubeComboAi.untapSource(ai, sa);
        return source != null && CubeComboAi.selectSingleTarget(sa, source);
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        if (selectComboUntap(ai, sa)) return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        final Card source = sa.getHostCard();

        if (!sa.usesTargeting()) {
            // assume we are looking to tap human's stuff
            // TODO - check for things with untap abilities, and don't tap those.

            boolean bFlag = false;
            for (final Card c : AbilityUtils.getDefinedCards(source, sa.getParam("Defined"), sa)) {
                bFlag |= c.isUntapped();
            }

            if (!bFlag) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        } else {
            sa.resetTargets();
            if (!tapPrefTargeting(ai, source, sa, false)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

}
