package forge.player;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Read-only presentation filter. False proves insufficient mana; true is NOT a legality claim.
 * Complex costs/sources deliberately remain visible. Never invokes AI payment simulation,
 * changes the activating player, chooses X, taps a source, or mutates the mana pool.
 */
public final class HumanManaAffordability {
    private HumanManaAffordability() {}

    public static boolean mayAfford(final Player player, final SpellAbility ability) {
        if (player == null || ability == null || ability.isSpell() || ability.isManaAbility()
                || ability.isTrigger() || ability.isReplacementAbility() || ability.isPowerUp()
                || ability.getPayCosts() == null || ability.hasParam("ReduceCost")
                || ability.hasParam("TapCreaturesForMana") || ability.hasParam("Announce")
                || ability.getParamOrDefault("Cost", "").contains("\\")
                || ability.hasSVar("NumTimes") || player.hasKeyword("PayLifeInsteadOf:B")) {
            return true;
        }
        final CostPartMana part = ability.getPayCosts().getCostMana();
        if (part == null || part.isExiledCreatureCost() || part.isEnchantedCreatureCost()
                || part.getMaxWaterbend() != null) return true;
        // Non-mana costs can themselves change the mana supply (e.g. untapping a source).
        if (ability.getPayCosts().getCostParts().stream()
                .anyMatch(p -> !(p instanceof CostPartMana) && !(p instanceof CostTap))) return true;
        final ManaCost cost = part.getMana();
        if (cost == null || cost.countX() != 0) return true;
        final List<ManaCostShard> shards = new ArrayList<>();
        for (ManaCostShard shard : cost) {
            if (shard.isPhyrexian() || shard.isOr2Generic() || shard.isSnow()) return true;
            shards.add(shard);
        }
        for (int i = 0; i < cost.getGenericCost(); i++) shards.add(ManaCostShard.GENERIC);
        if (shards.isEmpty()) return true;

        // Potential cost reductions, mana replacement/trigger generation, or conversion
        // invalidate the simple-source proof. Check public active zones, not hidden cards.
        for (Card card : player.getGame().getCardsInGame()) {
            if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Command)
                    && !card.isInZone(ZoneType.Stack) && card != ability.getHostCard()) continue;
            if (card.getStaticAbilities().stream().anyMatch(s -> s.checkMode(StaticAbilityMode.ReduceCost)
                    || s.checkMode(StaticAbilityMode.SetCost) || s.checkMode(StaticAbilityMode.ManaConvert))) return true;
            if (card.getReplacementEffects().stream().anyMatch(r -> r.getMode() == ReplacementType.ProduceMana)) return true;
            if (card.getTriggers().stream().anyMatch(t -> t.getMode() == TriggerType.TapsForMana
                    || t.getMode() == TriggerType.ManaAdded)) return true;
        }

        final List<Integer> tokens = new ArrayList<>();
        for (var mana : player.getManaPool()) {
            // Ignore spend restrictions optimistically; they can only remove payments.
            tokens.add((int) (mana.getColor() | player.getManaPool().getPossibleColorUses(mana.getColor())));
        }
        for (Card card : player.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command)) {
            if (card.isPhasedOut()) continue;
            int maxAmount = 0;
            int colors = 0;
            for (SpellAbility source : card.getSpellAbilities()) {
                if (!source.isManaAbility()) continue;
                // Ignore printed mana abilities outside their activation zone.
                if (source.getRestrictions().getZone() != null
                        && !card.isInZone(source.getRestrictions().getZone())
                        && !source.hasParam("AdditionalActivationZone")) continue;
                if (!card.isInZone(ZoneType.Battlefield)) return true;
                if (source.getPayCosts() == null || source.getPayCosts().getCostParts().stream()
                        .noneMatch(p -> p instanceof CostTap)) return true;
                if (source.getPayCosts().getCostParts().stream()
                        .anyMatch(p -> !(p instanceof CostTap) && !(p instanceof CostPartMana))) return true;
                // All admitted sources consume the same physical tap. Alternative abilities
                // are unioned, not counted as separate lands. Ignore other costs/restrictions.
                if (card.isTapped() || card.isAbilitySick()) continue;
                if (source.getSubAbility() != null || source.getManaPart() == null) return true;
                final String amountText = source.getParamOrDefault("Amount", "1");
                final String produced = source.getManaPart().getOrigProduced();
                if (!amountText.matches("[0-9]{1,3}") || !produced.matches("[WUBRGC]( [WUBRGC])*")) return true;
                final String[] symbols = produced.split(" ");
                maxAmount = Math.max(maxAmount, Integer.parseInt(amountText) * symbols.length);
                for (String symbol : symbols) {
                    byte color = ManaAtom.fromName(symbol);
                    colors |= color | player.getManaPool().getPossibleColorUses(color);
                }
            }
            // Unioning alternatives and each token's colors overestimates mixed output,
            // ensuring that a failed matching is still a sound impossibility certificate.
            for (int i = 0; i < Math.min(maxAmount, shards.size()); i++) tokens.add(colors);
        }
        return canMatch(shards, tokens);
    }

    static boolean canMatch(final List<ManaCostShard> shards, final List<Integer> tokens) {
        if (tokens.size() < shards.size()) return false;
        final int[] assigned = new int[tokens.size()];
        Arrays.fill(assigned, -1);
        for (int shard = 0; shard < shards.size(); shard++) {
            if (!augment(shard, shards, tokens, assigned, new boolean[tokens.size()])) return false;
        }
        return true;
    }

    private static boolean augment(int shard, List<ManaCostShard> shards, List<Integer> tokens,
                                   int[] assigned, boolean[] visited) {
        for (int token = 0; token < tokens.size(); token++) {
            if (visited[token] || !shards.get(shard).canBePaidWithManaOfColor(tokens.get(token).byteValue())) continue;
            visited[token] = true;
            if (assigned[token] == -1 || augment(assigned[token], shards, tokens, assigned, visited)) {
                assigned[token] = shard;
                return true;
            }
        }
        return false;
    }
}
