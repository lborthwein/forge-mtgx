package forge.bench;

import forge.game.GameActionUtil;
import forge.game.player.Player;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import java.util.ArrayList;
import java.util.List;

/** Enumerate each finite independent optional-cost subset; never truncate a menu. */
final class BenchmarkOptionalCosts {
    private BenchmarkOptionalCosts() {}
    static List<SpellAbility> variants(SpellAbility base, List<OptionalCostValue> options, Player player) {
        if (options.size() > 8) throw new RulesCostFeasibility.Unsupported("optional-cost subset bound");
        List<SpellAbility> out = new ArrayList<>();
        for (int mask = 1; mask < (1 << options.size()); mask++) {
            List<OptionalCostValue> subset = new ArrayList<>();
            for (int bit = 0; bit < options.size(); bit++) if ((mask & (1 << bit)) != 0) subset.add(options.get(bit));
            SpellAbility variant = GameActionUtil.addOptionalCosts(base, subset, true);
            if (variant == null || variant == base) throw new RulesCostFeasibility.Unsupported("optional-cost variant not constructed");
            variant.setActivatingPlayer(player);
            out.add(variant);
        }
        return out;
    }
}
