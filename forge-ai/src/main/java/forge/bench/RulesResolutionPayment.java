package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Exact native resolution authority, not a substitute spell/activation cost. */
sealed interface RulesResolutionPayment permits OptionalManaTriggerExecution.Resolution, EchoManaPayment {
    void requireQuote(Player payer, SpellAbility actual);
    void requirePayment(Player payer, SpellAbility actual);
    ManaCost manaCost(Player payer, SpellAbility actual);
    Cost cost(Player payer, SpellAbility actual);
    boolean repeated();
}
