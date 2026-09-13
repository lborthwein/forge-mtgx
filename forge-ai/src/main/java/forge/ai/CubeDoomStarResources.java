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
    public record Payment(Card source, SpellAbility ability, Mana floating, String color, int damage) { }
    private record Option(Object identity, Payment payment) { }
    private static final List<String> COLORS = List.of("C", "W", "U", "B", "R", "G");

    /** Each demand is W/U/B/R/G/C, or 1 for an unrestricted generic unit.
     * A null result means this deliberately bounded assignment domain could
     * not establish disjoint resources; it does not mean native unpayability.
     * Multiple abilities on one permanent never create multiple resources. */
    public static List<Payment> assign(Player player, List<String> demands, Set<Card> excluded) {
        return assign(player, demands, excluded, null);
    }
    /** Only the caller's independently checked native land action may
     * supply this prospective resource. No hypothetical zone move occurs. */
    static List<Payment> assign(Player player, List<String> demands, Set<Card> excluded, Card futureLand) {
        return assign(player, demands, excluded, futureLand, 0);
    }
    static List<Payment> assign(Player player, List<String> demands, Set<Card> excluded, Card futureLand, int damageBudget) {
        if (damageBudget < 0) return null;
        if (futureLand != null && (!futureLand.isLand() || !futureLand.isInZone(ZoneType.Hand)
                || futureLand.getOwner() != player || player.getGame().getCardState(futureLand, null) != futureLand)) return null;
        if (demands.size() > 12 || demands.stream().anyMatch(x -> !COLORS.contains(x) && !"1".equals(x)))
            return null;
        List<Option> options = new ArrayList<>();
        for (Mana mana : player.getManaPool()) {
            if (mana.getPlayer() != player || mana.isRestricted() || mana.isCombatMana()
                    || mana.triggersWhenSpent() || mana.addsKeywordsType() || mana.addsKeywordsUntil()) continue;
            String color = color(mana.getColor());
            if (color != null) options.add(new Option(mana, new Payment(null, null, mana, color, 0)));
        }
        List<Card> sources = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) sources.add(card);
        if (futureLand != null) sources.add(futureLand);
        for (Card card : sources) {
            if (excluded.contains(card) || card.getController() != player || card.isFaceDown()
                    || card.isPhasedOut() || card.isTapped()
                    || player.getGame().getCardState(card, null) != card) continue;
            for (SpellAbility original : card.getManaAbilities()) {
                SpellAbility ability = original.copy(player);
                if (ability.getManaPart() == null || !ability.getManaPart().getManaRestrictions().isEmpty()
                        || !ability.getManaPart().getExtraManaRestriction().isEmpty()
                        || ability.amountOfManaGenerated(false) != 1 || selfDamage(ability) < 0 || selfDamage(ability) > damageBudget) continue;
                var costs = forge.game.cost.CostAdjustment.adjust(ability.getPayCosts(), ability, false);
                if (costs == null || costs.getCostParts().stream().anyMatch(p -> !(p instanceof CostTap) && !(p instanceof CostPartMana))
                        || costs.getCostParts().stream().filter(p -> p instanceof CostTap).count() != 1
                        || !ComputerUtilMana.calculateManaCost(ability.getPayCosts(), ability, player, true, 0, false).isPaid()
                        || card != futureLand && (!CubeComboAi.canPlayNative(ability, player) || !CubeComboAi.canPayCost(ability, player, false))) continue;
                for (String color : COLORS) if (ability.canProduce(color)) {
                    SpellAbility chosen = ability.copy(player);
                    if (!"C".equals(color)) chosen.setManaExpressChoice(ColorSet.fromMask(mask(color)));
                    options.add(new Option(card, new Payment(card, chosen, null, color, selfDamage(chosen))));
                }
            }
        }
        // Constrained colors first prevents an early generic payment from
        // needlessly consuming the only blue source. Backtracking also handles
        // dual sources without counting their alternative colors twice.
        options.sort(java.util.Comparator.comparingInt(x -> x.payment().damage()));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < demands.size(); i++) if (!"1".equals(demands.get(i))) order.add(i);
        for (int i = 0; i < demands.size(); i++) if ("1".equals(demands.get(i))) order.add(i);
        List<Payment> result = new ArrayList<>(Collections.nCopies(demands.size(), null));
        Set<Object> used = Collections.newSetFromMap(new IdentityHashMap<>());
        return match(0, order, demands, options, used, result, new int[]{10000}, damageBudget) ? List.copyOf(result) : null;
    }
    private static boolean match(int at, List<Integer> order, List<String> demands, List<Option> options,
            Set<Object> used, List<Payment> result, int[] budget, int damageLeft) {
        if (--budget[0] < 0) return false;
        if (at == order.size()) return true;
        int slot = order.get(at); String demand = demands.get(slot);
        for (Option option : options) {
            if (option.payment().damage() > damageLeft || used.contains(option.identity()) || !"1".equals(demand) && !demand.equals(option.payment().color())) continue;
            used.add(option.identity()); result.set(slot, option.payment());
            if (match(at + 1, order, demands, options, used, result, budget, damageLeft - option.payment().damage())) return true;
            used.remove(option.identity()); result.set(slot, null);
        }
        return false;
    }
    /** Only a fixed native self-damage tail, with no further effect, is
     * budgeted. Damage replacement/tap triggers remain the caller's domain. */
    static int selfDamage(SpellAbility ability) {
        var tail = ability.getSubAbility();
        if (tail == null) return 0;
        if (tail.getApi() != forge.game.ability.ApiType.DealDamage || tail.getSubAbility() != null
                || !"You".equals(tail.getParam("Defined")) || !"1".equals(tail.getParam("NumDmg"))
                || tail.getMapParams().keySet().stream().anyMatch(k -> !Set.of("DB", "Defined", "NumDmg", "SpellDescription").contains(k))) return -1;
        return 1;
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
