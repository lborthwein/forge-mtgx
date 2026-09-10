package forge.player;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostTap;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Exact bounded X choices for a deliberately finite, read-only payment subset.
 * Unknown is explicit: optimistic affordability hints must never masquerade as
 * an exact maximum. Copies are LKI copies, avoiding new engine ability IDs/views.
 */
public final class HumanManaX {
    public record Range(int min, int max, boolean exact, String reason) {}
    record Token(int colors, int life) {}
    private static final int INF = 1_000_000;
    private static final int SEARCH_LIMIT = 128;
    private HumanManaX() {}
    private static Range unknown(int min, int max, String reason) { return new Range(min, max, false, reason); }

    public static Range range(Player player, SpellAbility ability, int min, int max) {
        if (player == null || ability == null || ability.getPayCosts() == null)
            return unknown(min, max, "No mana-cost context");
        final var part = ability.getPayCosts().getCostMana();
        if (part == null || part.getMana().countX() == 0 || part.isExiledCreatureCost()
                || part.isEnchantedCreatureCost() || part.getMaxWaterbend() != null
                || ability.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana)))
            return unknown(min, max, "Additional or variable non-mana costs");
        if (ability.isOffering() || ability.isEmerge() || ability.isBestow() || ability.isCastFaceDown()
                || ability.hasParam("ReduceCost") || ability.hasParam("RaiseCost")
                || ability.hasParam("TapCreaturesForMana") || ability.hasParam("ManaRestriction")
                || !ability.getPipsToReduce().isEmpty() || player.hasKeyword("PayLifeInsteadOf:B"))
            return unknown(min, max, "Alternative payment mechanics");
        if (ability.hasParam("XColor") && !ability.getParam("XColor").matches("[WUBRGC](,[WUBRGC])*"))
            return unknown(min, max, "Special X color constraint");
        for (Keyword keyword : List.of(Keyword.CONVOKE, Keyword.IMPROVISE, Keyword.DELVE, Keyword.ASSIST))
            if (ability.getHostCard().hasKeyword(keyword)) return unknown(min, max, "Alternative payment mechanics");
        if (ability.getHostCard().isCommander() && ability.getHostCard().isInZone(ZoneType.Command))
            return unknown(min, max, "Commander tax");

        final Set<String> modifierKeys = Set.of("Mode", "ValidCard", "ValidSpell", "Type", "Activator", "Amount",
                "Cost", "Description", "EffectZone", "AffectedZone", "Secondary", "RaiseTo", "IsPresent");
        for (Card card : player.getGame().getCardsInGame()) {
            if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Command)
                    && !card.isInZone(ZoneType.Stack) && card != ability.getHostCard()) continue;
            // Replacement effects can alter life costs, source reuse, or mana output.
            if (card.getReplacementEffects().stream().anyMatch(r -> List.of(ReplacementType.ProduceMana,
                    ReplacementType.PayLife, ReplacementType.LifeReduced, ReplacementType.Tap,
                    ReplacementType.Untap).contains(r.getMode()))) return unknown(min, max, "Mana or payment replacement effects");
            if (card.getTriggers().stream().anyMatch(t -> t.getMode() == TriggerType.ManaAdded
                    || t.getMode() == TriggerType.TapsForMana)) return unknown(min, max, "Triggered mana");
            for (var st : card.getStaticAbilities()) {
                if (st.checkMode(StaticAbilityMode.CantBeActivated) || st.checkMode(StaticAbilityMode.CantPayLife))
                    return unknown(min, max, "Conditional activation or life-payment restrictions");
                if (st.checkMode(StaticAbilityMode.ManaConvert) || st.checkMode(StaticAbilityMode.OptionalCost)
                        || (st.hasParam("AddKeyword") && (st.getParamOrDefault("AffectedZone", "").contains("Stack")
                        || st.getParam("AddKeyword").matches("(?s).*(Convoke|Delve|Improvise|Assist|Emerge|Offering|Waterbend|Affinity).*"))))
                    return unknown(min, max, "Conditional mana or payment conversion");
                if (st.checkMode(StaticAbilityMode.RaiseCost) || st.checkMode(StaticAbilityMode.ReduceCost)
                        || st.checkMode(StaticAbilityMode.SetCost)) {
                    if (!modifierKeys.containsAll(st.getMapParams().keySet())
                            || st.getParamOrDefault("ValidCard", "").matches("(?i).*(cmc|mana|cast|chosen|kicked|paid).*"))
                        return unknown(min, max, "X-dependent or conditional cost adjustment");
                    if (st.hasParam("ValidSpell") && !List.of("Spell", "Activated.!ManaAbility").contains(st.getParam("ValidSpell")))
                        return unknown(min, max, "Conditional spell-cost adjustment");
                    if (st.hasParam("IsPresent") && !"Card.Self+untapped".equals(st.getParam("IsPresent")))
                        return unknown(min, max, "Conditional cost adjustment");
                }
            }
        }
        for (byte color : ManaAtom.MANATYPES)
            if (player.getManaPool().getPossibleColorUses(color) != color || player.getManaPool().isSnowForColor())
                return unknown(min, max, "Mana conversion");

        final List<Token> tokens = new ArrayList<>();
        for (var mana : player.getManaPool()) {
            if (mana.isRestricted()) return unknown(min, max, "Restricted floating mana");
            tokens.add(new Token(mana.getColor(), 0));
        }
        for (Card card : player.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command)) {
            if (card.isPhasedOut()) continue;
            final List<Token> cardTokens = new ArrayList<>();
            int options = 0;
            for (SpellAbility source : card.getSpellAbilities()) {
                if (!source.isManaAbility()) continue;
                if (source.getRestrictions().getZone() != null && !card.isInZone(source.getRestrictions().getZone())
                        && !source.hasParam("AdditionalActivationZone")) continue;
                if (!card.isInZone(ZoneType.Battlefield) || source.getPayCosts() == null)
                    return unknown(min, max, "Non-battlefield mana sources");
                if (source.getPayCosts().getCostParts().stream().noneMatch(p -> p instanceof CostTap)
                        || source.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostTap)
                        && !(p instanceof CostPartMana) && !(p instanceof CostPayLife)))
                    return unknown(min, max, "Non-tap or additional-cost mana sources");
                if (card.isTapped() || card.isAbilitySick()) continue;
                final var sourceManaCost = source.getPayCosts().getCostMana();
                if (sourceManaCost != null && !sourceManaCost.getMana().isZero()) return unknown(min, max, "Mana-filter activation cost");
                if (source.getSubAbility() != null || source.getManaPart() == null || source.hasParam("Each")
                        || !source.getManaPart().getManaRestrictions().isEmpty()
                        || !source.getManaPart().getExtraManaRestriction().isEmpty()) return unknown(min, max, "Conditional mana output");
                if (source.getMapParams().keySet().stream().anyMatch(k -> k.startsWith("Condition")
                        || (k.startsWith("Activation") && !"ActivationZone".equals(k)) || "CheckSVar".equals(k))
                        || (source.hasParam("Defined") && !"You".equals(source.getParam("Defined"))))
                    return unknown(min, max, "Conditional mana-source availability");
                // Validate the actual source rules on a private ability copy. Supported
                // costs are only tap, zero mana, and literal life; no controller prompts.
                int life = 0;
                for (var cost : source.getPayCosts().getCostParts()) if (cost instanceof CostPayLife) {
                    if (!cost.getAmount().matches("[0-9]{1,3}")) return unknown(min, max, "Variable life payment");
                    life += Integer.parseInt(cost.getAmount());
                }
                final SpellAbility sourceCopy = source.copy(card, player, true);
                if (!sourceCopy.canPlay() || !sourceCopy.metConditions()) continue;
                final String amountText = source.getParamOrDefault("Amount", "1");
                if (!amountText.matches("[0-9]{1,2}")) return unknown(min, max, "Variable mana output");
                int amount = Integer.parseInt(amountText);
                final String output = source.getManaPart().getOrigProduced();
                if (++options > 1) return unknown(min, max, "Multiple mana modes on one source");
                if ("Any".equals(output) && amount == 1) cardTokens.add(new Token(ManaAtom.WHITE | ManaAtom.BLUE
                        | ManaAtom.BLACK | ManaAtom.RED | ManaAtom.GREEN, life));
                else if (output.matches("[WUBRGC]( [WUBRGC])*")) {
                    final String[] colors = output.split(" ");
                    if (life > 0 && amount * colors.length != 1) return unknown(min, max, "Shared multi-mana life cost");
                    for (int n = 0; n < amount; n++) for (String color : colors) cardTokens.add(new Token(ManaAtom.fromName(color), life));
                } else return unknown(min, max, "Variable or linked-color output");
            }
            tokens.addAll(cardTokens);
        }
        if (tokens.size() > 64) return unknown(min, max, "Large mana-source search");

        min = Math.max(min, part.getXMin());
        if (min > SEARCH_LIMIT) return unknown(min, max, "Large X search");
        int last = min - 1;
        for (int x = min; x <= Math.min(max, SEARCH_LIMIT); x++) {
            final ManaCost cost = priceAtX(ability, player, x);
            if (cost == null) return unknown(min, max, "Cost adjustment not supported for exact X");
            final List<ManaCostShard> shards = new ArrayList<>();
            for (ManaCostShard shard : cost) {
                if (shard == ManaCostShard.X || shard.isPhyrexian() || shard.isSnow() || shard.isOr2Generic()
                        || shard == ManaCostShard.COLORED_X) return unknown(min, max, "Special mana pips");
                shards.add(shard);
            }
            for (int n = 0; n < cost.getGenericCost(); n++) shards.add(ManaCostShard.GENERIC);
            final int requiredLife = minimumLife(shards, tokens);
            if (requiredLife >= INF || requiredLife > Math.max(0, player.getLife()))
                return new Range(min, last, true, "Complete mana payment");
            last = x;
        }
        return last == max ? new Range(min, max, true, "Complete mana payment") : unknown(min, max, "Large X search");
    }

    static ManaCost priceAtX(SpellAbility ability, Player player, int x) {
        final SpellAbility copy = ability.copy(ability.getHostCard(), player, true);
        final ManaCostBeingPaid expanded = new ManaCostBeingPaid(ability.getPayCosts().getCostMana().getMana());
        expanded.setXManaCostPaid(x, ability.getXColor()); // applies EVERY printed X, on the private cost only
        copy.setPayCosts(copy.getPayCosts().copyWithDefinedMana(expanded.toManaCost()));
        copy.setXManaCostPaid(x);
        return CostAdjustment.presentationManaCost(copy);
    }

    /** Minimum-cost bipartite matching: every mana unit is used at most once and
     * its source's literal life payment is charged only if that unit is needed. */
    static int minimumLife(List<ManaCostShard> shards, List<Token> tokens) {
        int n = shards.size(), m = tokens.size();
        if (n > m) return INF;
        int[] u = new int[n + 1], v = new int[m + 1], p = new int[m + 1], way = new int[m + 1];
        for (int row = 1; row <= n; row++) {
            p[0] = row; int column = 0;
            int[] best = new int[m + 1]; Arrays.fill(best, INF);
            boolean[] used = new boolean[m + 1];
            do {
                used[column] = true; int current = p[column], delta = INF, next = 0;
                for (int j = 1; j <= m; j++) if (!used[j]) {
                    Token token = tokens.get(j - 1);
                    int edge = shards.get(current - 1).canBePaidWithManaOfColor((byte) token.colors()) ? token.life() : INF;
                    int cost = edge - u[current] - v[j];
                    if (cost < best[j]) { best[j] = cost; way[j] = column; }
                    if (best[j] < delta) { delta = best[j]; next = j; }
                }
                if (delta >= INF / 2) return INF;
                for (int j = 0; j <= m; j++) if (used[j]) { u[p[j]] += delta; v[j] -= delta; } else best[j] -= delta;
                column = next;
            } while (p[column] != 0);
            do { int prior = way[column]; p[column] = p[prior]; column = prior; } while (column != 0);
        }
        return -v[0];
    }
}
