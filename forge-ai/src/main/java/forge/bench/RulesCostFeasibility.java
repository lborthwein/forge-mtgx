package forge.bench;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bounded rules-only cost feasibility. This is deliberately NOT an AI payment
 * simulation and NOT the browser's optimistic presentation filter. Every unknown
 * invalidates benchmark collection; unknown never means legal, illegal or delegate.
 * Version 1 supports ordinary mana and source tap/loyalty costs. Coupled sources
 * (e.g. Lotus) retain their single-color choice, rather than three rainbow tokens.
 * Full action enumeration, exact payment execution and observation conformance are
 * separate certification gates; this class makes no whole-bridge purity claim.
 */
public final class RulesCostFeasibility {
    public static final String VERSION = "rules-cost-v3-fixed-life";
    public static final String PAYMENT_VERSION = "rules-payment-v2-fixed-life";
    public static final String LIFE_VERSION = "rules-fixed-life-v1";
    public enum Status { PAYABLE, UNPAYABLE, UNSUPPORTED }
    public record Result(Status status, String reason, PaymentWitness witness, PaymentSpace space) {}
    public record PaymentSpace(ManaCost cost, List<ManaCostShard> shards, List<Token> pool, List<List<SourceChoice>> sources, int life) {
        public PaymentSpace(ManaCost cost, List<ManaCostShard> shards, List<Token> pool, List<List<SourceChoice>> sources) {
            this(cost, shards, pool, sources, 0);
        }
    }
    public record SourceChoice(SpellAbility ability, String choice, List<Integer> output) {}
    public record Token(int color, Mana floating, SourceChoice source, int outputIndex) {}
    public record Allocation(ManaCostShard shard, Token token) {}
    public record PaymentWitness(ManaCost cost, List<SourceChoice> sources, List<Allocation> allocations, int life) {
        public PaymentWitness(ManaCost cost, List<SourceChoice> sources, List<Allocation> allocations) {
            this(cost, sources, allocations, 0);
        }
    }
    private static final int SEARCH_LIMIT = 100_000;
    private RulesCostFeasibility() {}

    public static final class Unsupported extends IllegalStateException {
        public Unsupported(String reason) { super("BENCH_INTEGRITY_UNSUPPORTED: " + reason); }
    }

    public static boolean requirePayable(Player payer, SpellAbility ability) {
        Result result = assess(payer, ability);
        if (result.status == Status.UNSUPPORTED) throw new Unsupported(result.reason);
        return result.status == Status.PAYABLE;
    }

    private static Result unknown(String reason) { return new Result(Status.UNSUPPORTED, reason, null, null); }
    private static Result answer(boolean yes) { return answer(yes, 0); }
    private static Result answer(boolean yes, int life) { return new Result(yes ? Status.PAYABLE : Status.UNPAYABLE, VERSION,
            yes ? new PaymentWitness(ManaCost.ZERO, List.of(), List.of(), life) : null,
            yes ? new PaymentSpace(ManaCost.ZERO, List.of(), List.of(), List.of(), life) : null); }

    public static Result assess(Player payer, SpellAbility ability) {
        if (payer == null || ability == null || ability.getActivatingPlayer() != payer
                || ability.getPayCosts() == null) return unknown("missing cost/activator");
        if (ability.isTrigger() || ability.isReplacementAbility() || ability.isOffering()
                || ability.isEmerge() || ability.isBestow() || ability.isCastFaceDown()
                || ability.hasParam("Announce") || ability.hasSVar("NumTimes") || !ability.getPipsToReduce().isEmpty()
                || ability.hasParam("ReduceCost") || ability.hasParam("RaiseCost")
                || ability.hasParam("TapCreaturesForMana") || ability.hasParam("ManaRestriction")
                || ability.hasParam("ManaConversion")
                || ability.getHostCard().isCommander())
            return unknown("complex announcement or payment");
        var permission = ability.getMayPlayOption();
        if (permission != null) {
            if (permission.getPlayer() != payer || !permission.getAbility().checkConditions()
                    || ability.getHostCard().mayPlay(payer).stream().noneMatch(p -> p.getAbility() == permission.getAbility())
                    || !forge.game.staticability.StaticAbilityContinuous.getAffectedCards(permission.getAbility(),
                        new forge.game.card.CardCollection(ability.getHostCard())).contains(ability.getHostCard())) return answer(false);
            if (permission.isIgnoreManaCostColor() || permission.isIgnoreManaCostType() || permission.isIgnoreSnowSourceManaCostColor()
                    || permission.getPayManaCost() != forge.game.card.CardPlayOption.PayManaCost.YES)
                return unknown("unsupported permission payment conversion");
        }
        for (Keyword keyword : new Keyword[]{Keyword.CONVOKE, Keyword.IMPROVISE, Keyword.DELVE, Keyword.ASSIST}) {
            if (ability.getHostCard().hasKeyword(keyword)) return unknown("alternative mana payment: " + keyword);
        }
        if (payer.hasKeyword("PayLifeInsteadOf:B")) return unknown("life substitutes for black mana");
        if (payer.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.hasKeyword(Keyword.SPLICE)))
            return unknown("splice may alter selected spell during execution");
        boolean hostUsed = false;
        int nonMana = 0;
        int life = 0;
        for (CostPart part : ability.getPayCosts().getCostParts()) {
            if (part instanceof CostPartMana) continue;
            // Multiple nonmana costs can compete for the same resource. Do not prove
            // joint feasibility by checking each independently.
            if (++nonMana > 1) return unknown("joint nonmana costs");
            if (part instanceof CostTap) {
                hostUsed = true;
            } else if (part instanceof CostRemoveCounter remove && remove.payCostFromSource()
                    && remove.convertAmount() != null && remove.counter != null && remove.counter.is(CounterEnumType.LOYALTY)) {
                // Forge's rule implementation permits spending the last loyalty.
            } else if (part instanceof CostPutCounter put && put.payCostFromSource()
                    && put.convertAmount() != null && put.getCounter().is(CounterEnumType.LOYALTY) && ability.isActivatedAbility()) {
                // Non-ETB source counter cost; no temporary static-state query.
            } else if (part instanceof CostPayLife payLife && payLife.convertAmount() != null) {
                life = payLife.convertAmount();
                if (life < 0 || life > 1_000_000) return unknown("fixed life cost outside bounded scope");
            } else return unknown("nonmana cost: " + part.getClass().getSimpleName());
            if (!part.canPay(ability, payer, false)) return answer(false);
        }
        if (life > 0 && !payer.canLoseLife()) return unknown("life payment without ordinary life loss");

        // These effects can change payment feasibility or make source costs dependent.
        for (Card card : payer.getGame().getCardsInGame()) {
            if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Command)
                    && !card.isInZone(ZoneType.Stack) && card != ability.getHostCard()) continue;
            if (card.getStaticAbilities().stream().anyMatch(s -> s.checkMode(StaticAbilityMode.ManaConvert)
                    || s.checkMode(StaticAbilityMode.OptionalCost)
                    || s.checkMode(StaticAbilityMode.CantBeActivated))) return unknown("mana conversion/optional cost/conditional activation");
            if (card.getReplacementEffects().stream().filter(r -> r.zonesCheck(payer.getGame().getZoneOf(card)))
                    .anyMatch(r -> r.getMode() == ReplacementType.ProduceMana
                            || r.getMode() == ReplacementType.Tap || r.getMode() == ReplacementType.Untap
                            || (lifeCostPresent(ability) && (r.getMode() == ReplacementType.PayLife || r.getMode() == ReplacementType.LifeReduced))))
                return unknown("mana/tap/life replacement");
            if (card.getTriggers().stream().filter(t -> t.getSpawningAbility() != null
                    || t.zonesCheck(payer.getGame().getZoneOf(card)))
                    .anyMatch(t -> t.getMode() == TriggerType.TapsForMana || t.getMode() == TriggerType.ManaAdded))
                return unknown("mana production trigger");
        }
        ManaCost cost = CostAdjustment.benchmarkManaCost(ability);
        if (cost == null) return unknown("unverified cost adjustment");
        if (cost.isNoCost()) return answer(false);
        List<ManaCostShard> shards = new ArrayList<>();
        for (ManaCostShard shard : cost) {
            if (shard == ManaCostShard.X || shard.isPhyrexian() || shard.isSnow() || shard.isOr2Generic())
                return unknown("complex mana shard: " + shard);
            shards.add(shard);
        }
        for (int i = 0; i < cost.getGenericCost(); i++) shards.add(ManaCostShard.GENERIC);
        if (shards.isEmpty()) return answer(true, life);
        List<Token> tokens = new ArrayList<>();
        for (byte color : ManaAtom.MANATYPES) if (payer.getManaPool().getPossibleColorUses(color) != color)
            return unknown("pool/source color conversion");
        for (var mana : payer.getManaPool()) {
            if (mana.isRestricted() || mana.triggersWhenSpent() || mana.addsCounters(ability)
                    || mana.addsKeywords(ability) || mana.addsNoCounterMagic(ability)) return unknown("restricted/effectful pooled mana");
            if (payer.getManaPool().getPossibleColorUses(mana.getColor()) != mana.getColor())
                return unknown("pool color conversion");
            tokens.add(new Token(mana.getColor(), mana, null, tokens.size()));
        }
        List<List<SourceChoice>> sources = new ArrayList<>();
        for (Card card : payer.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command)) {
            if (card.isPhasedOut() || (hostUsed && card == ability.getHostCard())) continue;
            List<SourceChoice> alternatives = new ArrayList<>();
            for (SpellAbility printed : card.getManaAbilities()) {
                if (printed.getRestrictions().getZone() != null
                        && !card.isInZone(printed.getRestrictions().getZone())
                        && !printed.hasParam("AdditionalActivationZone")) continue;
                if (!card.isInZone(ZoneType.Battlefield)) return unknown("nonbattlefield mana source");
                if (printed.isSuppressed() || card.isDetained()) continue;
                // A tapped/sick Wall of Roots still has a usable non-tap ability.
                // Never silently drop it while proving an empty mana budget.
                if (printed.getPayCosts() != null && printed.getPayCosts().hasTapCost()
                        && (card.isTapped() || card.isAbilitySick())) continue;
                // Restrictions can assign an unset activator; query only a disposable
                // ability copy, never mutate the printed source or its AI memory.
                // LKI=true also avoids advancing global ability IDs / tracker views.
                SpellAbility source = printed.copy(card, payer, true);
                if (source.hasSVar("NumTimes") || source.getMapParams().keySet().stream().anyMatch(k -> k.startsWith("Condition")
                        || (k.startsWith("Activation") && !"ActivationZone".equals(k)) || "CheckSVar".equals(k))
                        || (source.hasParam("Defined") && !"You".equals(source.getParam("Defined"))))
                    return unknown("conditional mana-source availability");
                if (!source.getRestrictions().canPlay(card, source)) continue;
                if (source.getPayCosts() == null || source.getManaPart() == null || source.getSubAbility() != null
                        || source.hasParam("Each")) return unknown("complex mana source");
                if (!java.util.Set.of("AB", "Cost", "Produced", "Amount", "AILogic", "SpellDescription", "Description",
                        "ActivationZone", "Secondary").containsAll(source.getMapParams().keySet()))
                    return unknown("unverified mana-source parameters");
                ManaCost sourceMana = CostAdjustment.benchmarkManaCost(source);
                if (sourceMana == null || !sourceMana.isZero()) return unknown("adjusted mana source cost");
                boolean tap = false;
                for (CostPart part : source.getPayCosts().getCostParts()) {
                    if (part instanceof CostTap) tap = true;
                    else if (part instanceof CostPartMana mana && mana.getMana().isZero()) { }
                    else if (part instanceof CostSacrifice sac && sac.payCostFromSource()
                            && Integer.valueOf(1).equals(sac.convertAmount())) { }
                    else return unknown("mana source activation cost");
                    if (!(part instanceof CostPartMana) && !part.canPay(source, payer, false)) {
                        tap = false;
                        break;
                    }
                }
                if (!tap) return unknown("repeatable/non-tap mana source");
                if (!source.getManaPart().getManaRestrictions().isEmpty()
                        || !source.getManaPart().getExtraManaRestriction().isEmpty()) return unknown("restricted source mana");
                String produced = source.getManaPart().getOrigProduced();
                String count = source.getParamOrDefault("Amount", "1");
                if (!count.matches("[1-9][0-9]?")) return unknown("dynamic mana amount");
                int amount = Integer.parseInt(count);
                if ("Any".equals(produced)) {
                    for (String color : List.of("W", "U", "B", "R", "G"))
                        alternatives.add(new SourceChoice(printed, color,
                                List.copyOf(java.util.Collections.nCopies(amount, (int) ManaAtom.fromName(color)))));
                } else if (produced.matches("[WUBRGC]( [WUBRGC])*")) {
                    List<Integer> output = new ArrayList<>();
                    for (int i = 0; i < amount; i++) for (String color : produced.split(" ")) output.add((int) ManaAtom.fromName(color));
                    alternatives.add(new SourceChoice(printed, "", List.copyOf(output)));
                } else return unknown("choice/dynamic mana output");
            }
            if (!alternatives.isEmpty()) sources.add(alternatives);
        }
        if (sources.size() > 32) return unknown("source search bound");
        try {
            List<Token> found = search(shards, tokens, sources, 0, new int[]{0});
            if (found == null) return answer(false);
            int[] matching = matching(shards, found.stream().map(Token::color).toList());
            List<SourceChoice> used = new ArrayList<>();
            List<Allocation> allocations = new ArrayList<>();
            for (int i = 0; i < matching.length; i++) if (matching[i] >= 0) {
                Token token = found.get(i);
                allocations.add(new Allocation(shards.get(matching[i]), token));
                if (token.source() != null && !used.contains(token.source())) used.add(token.source());
            }
            return new Result(Status.PAYABLE, VERSION, new PaymentWitness(cost, List.copyOf(used), List.copyOf(allocations), life),
                    new PaymentSpace(cost, List.copyOf(shards), List.copyOf(tokens),
                            sources.stream().map(List::copyOf).toList(), life));
        }
        catch (Unsupported limit) { return unknown(limit.getMessage()); }
    }

    private static boolean lifeCostPresent(SpellAbility ability) {
        return ability.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostPayLife);
    }

    private static List<Token> search(List<ManaCostShard> shards, List<Token> tokens,
                                  List<List<SourceChoice>> sources, int index, int[] visited) {
        if (++visited[0] > SEARCH_LIMIT) throw new Unsupported("source search bound");
        if (matches(shards, tokens.stream().map(Token::color).toList())) return tokens;
        if (index == sources.size()) return null;
        for (SourceChoice source : sources.get(index)) {
            List<Token> next = new ArrayList<>(tokens);
            for (int i = 0; i < source.output().size(); i++) next.add(new Token(source.output().get(i), null, source, i));
            List<Token> found = search(shards, next, sources, index + 1, visited);
            if (found != null) return found;
        }
        // Every supported source is optional and has no cross-source negative cost.
        // Using one extra source cannot destroy a payment; no skip branch is needed.
        return null;
    }

    static boolean matches(List<ManaCostShard> shards, List<Integer> tokens) {
        return matching(shards, tokens) != null;
    }
    private static int[] matching(List<ManaCostShard> shards, List<Integer> tokens) {
        if (tokens.size() < shards.size()) return null;
        int[] assigned = new int[tokens.size()];
        Arrays.fill(assigned, -1);
        for (int i = 0; i < shards.size(); i++) if (!augment(i, shards, tokens, assigned, new boolean[tokens.size()])) return null;
        return assigned;
    }
    private static boolean augment(int shard, List<ManaCostShard> shards, List<Integer> tokens, int[] assigned, boolean[] seen) {
        for (int token = 0; token < tokens.size(); token++) {
            if (seen[token] || !shards.get(shard).canBePaidWithManaOfColor(tokens.get(token).byteValue())) continue;
            seen[token] = true;
            if (assigned[token] < 0 || augment(assigned[token], shards, tokens, assigned, seen)) {
                assigned[token] = shard;
                return true;
            }
        }
        return false;
    }
}
