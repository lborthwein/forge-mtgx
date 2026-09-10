package forge.bench;

import com.google.common.collect.ArrayListMultimap;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.ArrayList;
import java.util.List;

/** Rules variants only. No controller optional-cost selection and no AI pricing. */
final class BenchmarkAbilityEnumeration {
    private BenchmarkAbilityEnumeration() {}

    static List<SpellAbility> spells(Iterable<Card> cards, Player player) {
        List<SpellAbility> originals = new ArrayList<>();
        for (Card card : cards) {
            var hidden = ArrayListMultimap.<SpellAbility, SpellAbility>create();
            var possible = card.getAllPossibleAbilities(player, false, hidden, true);
            for (SpellAbility base : hidden.keySet()) if (possible.stream().anyMatch(candidate -> candidate == base))
                possible.removeIf(candidate -> hidden.get(base).stream().anyMatch(alt -> alt == candidate));
            originals.addAll(possible);
        }
        List<SpellAbility> result = new ArrayList<>();
        for (SpellAbility original : originals) {
            for (SpellAbility base : GameActionUtil.getAdditionalCostSpell(original, true)) {
                var alternatives = GameActionUtil.getAlternativeCosts(base, player, false, true);
                // Retain the old ordering while removing its optional-cost AI call.
                List<SpellAbility> ordered = new ArrayList<>();
                List<SpellAbility> other = new ArrayList<>();
                for (SpellAbility alt : alternatives) {
                    if (base.getPayCosts().isOnlyManaCost() && alt.getPayCosts().isOnlyManaCost()
                            && base.getPayCosts().getTotalMana().compareTo(alt.getPayCosts().getTotalMana()) == 1) ordered.add(alt);
                    else other.add(alt);
                }
                ordered.add(base);
                ordered.addAll(other);
                result.addAll(ordered);
            }
        }
        List<SpellAbility> withOptional = new ArrayList<>(result);
        for (SpellAbility base : result) {
            // These bases are already private enumeration copies. Discovery clears
            // stale optional pips on that copy, never on the printed ability.
            var options = GameActionUtil.getOptionalCostValues(base, true);
            withOptional.addAll(BenchmarkOptionalCosts.variants(base, options, player));
        }
        return withOptional;
    }
}
