package forge.bench;

import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Benchmark observation compatibility only, NOT exact payment feasibility.
 * Mirrors ComputerUtilMana.getAvailableManaEstimate's arithmetic and traversal,
 * including its filter-source approximation. Queries detached ability copies;
 * never installs an actor on a live mana ability. Default AI is unchanged.
 */
final class LegacyManaEstimate {
    private LegacyManaEstimate() { }

    static int available(final Player player) {
        return available(player, true);
    }

    static int available(final Player player, final boolean checkPlayable) {
        if (player == null) throw new IllegalArgumentException("Legacy mana estimate requires a player");
        return BenchRandomAudit.withoutRandomUse("legacy mana estimate", () -> estimate(player, checkPlayable));
    }

    private static int estimate(final Player player, final boolean checkPlayable) {
        int available = 0;
        int producedWithCost = 0;
        boolean hasSourcesWithNoManaCost = false;
        for (var source : player.getCardsIn(ZoneType.Battlefield)) {
            int maxProduced = 0;
            for (var live : source.getManaAbilities()) {
                final var ability = live.copyForEnumeration(player);
                if (ability == null || ability == live)
                    throw new IllegalStateException("Cannot detach mana ability " + live.getId());
                if (!checkPlayable || ability.canPlay()) {
                    int cost = ability.getPayCosts().getCostMana() != null
                            ? ability.getPayCosts().getCostMana().convertAmount() : 0;
                    int producedMana = ability.getParamOrDefault("Produced", "").split(" ").length;
                    int amount = AbilityUtils.calculateAmount(source, ability.getParamOrDefault("Amount", "1"), ability);
                    int total = producedMana * amount - cost;
                    if (cost > 0) producedWithCost += total;
                    else if (!hasSourcesWithNoManaCost) hasSourcesWithNoManaCost = true;
                    if (total > maxProduced) maxProduced = total;
                }
            }
            available += maxProduced;
        }
        available += player.getManaPool().totalMana();
        if (producedWithCost > 0 && !hasSourcesWithNoManaCost) available -= producedWithCost;
        return available;
    }
}
