package forge.player;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostAdjustment;
import forge.game.ability.ApiType;
import forge.game.keyword.Keyword;
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
    static final int LIFE_PAYMENT = 256;
    public enum Assessment { PROVEN_UNAFFORDABLE, POTENTIALLY_AFFORDABLE, UNKNOWN }
    private HumanManaAffordability() {}

    public static boolean mayAfford(final Player player, final SpellAbility ability) {
        return assess(player, ability) != Assessment.PROVEN_UNAFFORDABLE;
    }

    public static Assessment assess(final Player player, final SpellAbility ability) {
        if (player == null || ability == null || ability.isManaAbility()
                || ability.isTrigger() || ability.isReplacementAbility() || ability.isPowerUp()
                || ability.getPayCosts() == null || ability.hasParam("ReduceCost")
                || ability.hasParam("TapCreaturesForMana")
                || (ability.hasParam("Announce") && !"X".equals(ability.getParam("Announce")))
                || ability.getParamOrDefault("Cost", "").contains("\\")
                || ability.hasSVar("NumTimes") || player.hasKeyword("PayLifeInsteadOf:B")) {
            return Assessment.UNKNOWN;
        }
        if (ability.isSpell() && (ability.isOffering() || ability.isEmerge() || ability.isBestow()
                || ability.isCastFaceDown() || !ability.getPipsToReduce().isEmpty()
                || ability.getHostCard().hasKeyword(Keyword.CONVOKE)
                || ability.getHostCard().hasKeyword(Keyword.IMPROVISE)
                || ability.getHostCard().hasKeyword(Keyword.DELVE)
                || ability.getHostCard().hasKeyword(Keyword.ASSIST))) return Assessment.UNKNOWN;
        final CostPartMana part = ability.getPayCosts().getCostMana();
        if (part == null || part.isExiledCreatureCost() || part.isEnchantedCreatureCost()
                || part.getMaxWaterbend() != null || part.getXMin() > 0) return Assessment.UNKNOWN;
        // Non-mana costs can themselves change the mana supply (e.g. untapping a source).
        if (ability.getPayCosts().getCostParts().stream()
                .anyMatch(p -> !(p instanceof CostPartMana) && !(p instanceof CostTap))) return Assessment.UNKNOWN;
        final ManaCost cost = CostAdjustment.presentationManaCost(ability);
        if (cost == null) return Assessment.UNKNOWN;
        final List<ManaCostShard> shards = new ArrayList<>();
        int phyrexian = 0;
        for (ManaCostShard shard : cost) {
            if (shard.isOr2Generic() || shard.isSnow()) return Assessment.UNKNOWN;
            // X=0 is an optimistic lower bound; never choose or mutate the actual X.
            if (shard == ManaCostShard.X) continue;
            if (shard.isPhyrexian()) phyrexian++;
            shards.add(shard);
        }
        for (int i = 0; i < cost.getGenericCost(); i++) shards.add(ManaCostShard.GENERIC);
        if (shards.isEmpty()) return Assessment.POTENTIALLY_AFFORDABLE;

        // Potential cost reductions, mana replacement/trigger generation, or conversion
        // invalidate the simple-source proof. Check public active zones, not hidden cards.
        for (Card card : player.getGame().getCardsInGame()) {
            if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Command)
                    && !card.isInZone(ZoneType.Stack) && card != ability.getHostCard()) continue;
            if (card.getStaticAbilities().stream().anyMatch(s -> s.checkMode(StaticAbilityMode.ManaConvert))) return Assessment.UNKNOWN;
            if (ability.isSpell() && card.getStaticAbilities().stream().anyMatch(s ->
                    s.checkMode(StaticAbilityMode.OptionalCost)
                    || (s.hasParam("AddKeyword") && s.getParam("AddKeyword")
                        .matches("(?s).*(Convoke|Delve|Improvise|Assist|Emerge|Offering|Harmonize|Waterbend|Affinity|Undaunted).*"))
                    || (s.hasParam("AddKeyword") && s.getParamOrDefault("AffectedZone", "").contains("Stack")))) return Assessment.UNKNOWN;
            if (card.getReplacementEffects().stream().filter(r -> r.zonesCheck(player.getGame().getZoneOf(card)))
                    .anyMatch(r -> r.getMode() == ReplacementType.ProduceMana
                    || r.getMode() == ReplacementType.PayLife || r.getMode() == ReplacementType.LifeReduced
                    || r.getMode() == ReplacementType.DamageDone || r.getMode() == ReplacementType.DealtDamage)) return Assessment.UNKNOWN;
            if (card.getTriggers().stream().filter(t -> t.getSpawningAbility() != null
                    || t.zonesCheck(player.getGame().getZoneOf(card)))
                    .anyMatch(t -> t.getMode() == TriggerType.TapsForMana
                    || t.getMode() == TriggerType.ManaAdded)) return Assessment.UNKNOWN;
        }

        final List<Integer> tokens = new ArrayList<>();
        // Life tokens can match only phyrexian pips, never generic mana. Ignoring
        // source life costs overestimates supply and cannot hide a legal payment.
        for (int i = 1; i <= phyrexian && player.canPayLife(2 * i, false, ability); i++) tokens.add(LIFE_PAYMENT);
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
                if (!card.isInZone(ZoneType.Battlefield)) return Assessment.UNKNOWN;
                if (source.getPayCosts() == null || source.getPayCosts().getCostParts().stream()
                        .noneMatch(p -> p instanceof CostTap)) return Assessment.UNKNOWN;
                if (source.getPayCosts().getCostParts().stream()
                        .anyMatch(p -> !(p instanceof CostTap) && !(p instanceof CostPartMana)
                                && !(p instanceof CostPayLife)
                                && !(p instanceof CostSacrifice && p.payCostFromSource()))) return Assessment.UNKNOWN;
                // All admitted sources consume the same physical tap. Alternative abilities
                // are unioned, not counted as separate lands. Ignore other costs/restrictions.
                if (card.isTapped() || card.isAbilitySick()) continue;
                if (source.getManaPart() == null) return Assessment.UNKNOWN;
                if (source.hasParam("Each")) return Assessment.UNKNOWN;
                for (SpellAbility tail = source.getSubAbility(); tail != null; tail = tail.getSubAbility()) {
                    if (tail.getApi() != ApiType.DealDamage && tail.getApi() != ApiType.LoseLife) return Assessment.UNKNOWN;
                }
                final String amountText = source.getParamOrDefault("Amount", "1");
                final String produced = source.getManaPart().getOrigProduced();
                if (!amountText.matches("[0-9]{1,3}")) return Assessment.UNKNOWN;
                final boolean choice = "Any".equals(produced) || produced.startsWith("Combo ");
                final String simple = "Any".equals(produced) ? "W U B R G"
                        : produced.startsWith("Combo ") ? produced.substring(6) : produced;
                if (!simple.matches("[WUBRGC]( [WUBRGC])*")) return Assessment.UNKNOWN;
                final String[] symbols = simple.split(" ");
                maxAmount = Math.max(maxAmount, Integer.parseInt(amountText) * (choice ? 1 : symbols.length));
                for (String symbol : symbols) {
                    byte color = ManaAtom.fromName(symbol);
                    colors |= color | player.getManaPool().getPossibleColorUses(color);
                }
            }
            // Unioning alternatives and each token's colors overestimates mixed output,
            // ensuring that a failed matching is still a sound impossibility certificate.
            for (int i = 0; i < Math.min(maxAmount, shards.size()); i++) tokens.add(colors);
        }
        return canMatch(shards, tokens) ? Assessment.POTENTIALLY_AFFORDABLE : Assessment.PROVEN_UNAFFORDABLE;
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
            final boolean pays = tokens.get(token) == LIFE_PAYMENT ? shards.get(shard).isPhyrexian()
                    : shards.get(shard).canBePaidWithManaOfColor(tokens.get(token).byteValue());
            if (visited[token] || !pays) continue;
            visited[token] = true;
            if (assigned[token] == -1 || augment(assigned[token], shards, tokens, assigned, visited)) {
                assigned[token] = shard;
                return true;
            }
        }
        return false;
    }
}
