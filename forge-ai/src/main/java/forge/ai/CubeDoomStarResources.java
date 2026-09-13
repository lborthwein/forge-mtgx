package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Disjoint current-resource assignments for the Star finish. This is a
 * resource proposal, not permission to cast a future spell or play a land.
 * The caller still checks actual adjusted spell costs and executes every
 * selected ability through native legality and payment. No hidden zone is
 * inspected, and no source is tapped or floating mana reserved by a query. */
public final class CubeDoomStarResources {
    private CubeDoomStarResources() { }
    public record Payment(Card source, SpellAbility ability, Mana floating, String color) { }
    private record Option(Object identity, Payment payment) { }
    private static final List<String> COLORS = List.of("C", "W", "U", "B", "R", "G");

    /** Each demand is W/U/B/R/G/C, or 1 for an unrestricted generic unit.
     * A null result means this deliberately bounded assignment domain could
     * not establish disjoint resources; it does not mean native unpayability.
     * Multiple abilities on one permanent never create multiple resources. */
    public static List<Payment> assign(Player player, List<String> demands, Set<Card> excluded) {
        if (demands.size() > 12 || demands.stream().anyMatch(x -> !COLORS.contains(x) && !"1".equals(x)))
            return null;
        List<Option> options = new ArrayList<>();
        for (Mana mana : player.getManaPool()) {
            if (mana.getPlayer() != player || mana.isRestricted() || mana.isCombatMana()
                    || mana.triggersWhenSpent() || mana.addsKeywordsType() || mana.addsKeywordsUntil()) continue;
            String color = color(mana.getColor());
            if (color != null) options.add(new Option(mana, new Payment(null, null, mana, color)));
        }
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (excluded.contains(card) || card.getController() != player || card.isFaceDown()
                    || card.isPhasedOut() || card.isTapped()
                    || player.getGame().getCardState(card, null) != card) continue;
            for (SpellAbility original : card.getManaAbilities()) {
                SpellAbility ability = original.copy(player);
                if (ability.getManaPart() == null || !ability.getManaPart().getManaRestrictions().isEmpty()
                        || !ability.getManaPart().getExtraManaRestriction().isEmpty()
                        || ability.amountOfManaGenerated(false) != 1 || ability.getSubAbility() != null) continue;
                var costs = forge.game.cost.CostAdjustment.adjust(ability.getPayCosts(), ability, false);
                if (costs == null || costs.getCostParts().stream().anyMatch(p -> !(p instanceof CostTap) && !(p instanceof CostPartMana))
                        || costs.getCostParts().stream().filter(p -> p instanceof CostTap).count() != 1
                        || !ComputerUtilMana.calculateManaCost(ability.getPayCosts(), ability, player, true, 0, false).isPaid()
                        || !CubeComboAi.canPlayNative(ability, player) || !CubeComboAi.canPayCost(ability, player, false)) continue;
                for (String color : COLORS) if (ability.canProduce(color)) {
                    SpellAbility chosen = ability.copy(player);
                    if (!"C".equals(color)) chosen.setManaExpressChoice(ColorSet.fromMask(mask(color)));
                    options.add(new Option(card, new Payment(card, chosen, null, color)));
                }
            }
        }
        // Constrained colors first prevents an early generic payment from
        // needlessly consuming the only blue source. Backtracking also handles
        // dual sources without counting their alternative colors twice.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < demands.size(); i++) if (!"1".equals(demands.get(i))) order.add(i);
        for (int i = 0; i < demands.size(); i++) if ("1".equals(demands.get(i))) order.add(i);
        List<Payment> result = new ArrayList<>(Collections.nCopies(demands.size(), null));
        Set<Object> used = Collections.newSetFromMap(new IdentityHashMap<>());
        return match(0, order, demands, options, used, result, new int[]{10000}) ? List.copyOf(result) : null;
    }
    private static boolean match(int at, List<Integer> order, List<String> demands, List<Option> options,
            Set<Object> used, List<Payment> result, int[] budget) {
        if (--budget[0] < 0) return false;
        if (at == order.size()) return true;
        int slot = order.get(at); String demand = demands.get(slot);
        for (Option option : options) {
            if (used.contains(option.identity()) || !"1".equals(demand) && !demand.equals(option.payment().color())) continue;
            used.add(option.identity()); result.set(slot, option.payment());
            if (match(at + 1, order, demands, options, used, result, budget)) return true;
            used.remove(option.identity()); result.set(slot, null);
        }
        return false;
    }
    private static byte mask(String color) {
        return switch (color) { case "W" -> MagicColor.WHITE; case "U" -> MagicColor.BLUE;
            case "B" -> MagicColor.BLACK; case "R" -> MagicColor.RED; case "G" -> MagicColor.GREEN;
            default -> MagicColor.COLORLESS; };
    }
    private static String color(byte mask) {
        for (String color : COLORS) if (mask(color) == mask) return color;
        return null;
    }
}
