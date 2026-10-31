package forge.game.cost;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public abstract class CostDecisionMakerBase implements ICostVisitor<PaymentDecision> {

    protected final Player player;
    protected final SpellAbility ability;
    protected final Card source;
    private boolean effect;

    public CostDecisionMakerBase(Player player0, boolean effect0, SpellAbility ability0, Card source0) {
        player = player0;
        effect = effect0;
        ability = ability0;
        source = source0;
    }

    public Player getPlayer() { return player; }

    /**
     * r3-inline-choices-1030 (research): a decision maker may review the decision just made for {@code part} (the AI's
     * choose-a-card hook). The default returns it unchanged, so every other decision maker is byte for byte as before.
     */
    public PaymentDecision review(final CostPart part, final PaymentDecision decision) {
        return decision;
    }
    public abstract boolean paysRightAfterDecision();
    public boolean isEffect() {
        return effect;
    }
}
