package forge.bench;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.*;
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
    public static final String VERSION = "rules-cost-v1-bounded";
    public enum Status { PAYABLE, UNPAYABLE, UNSUPPORTED }
    public record Result(Status status, String reason) {}
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

    private static Result unknown(String reason) { return new Result(Status.UNSUPPORTED, reason); }
    private static Result answer(boolean yes) { return new Result(yes ? Status.PAYABLE : Status.UNPAYABLE, VERSION); }

    public static Result assess(Player payer, SpellAbility ability) {
        if (payer == null || ability == null || ability.getActivatingPlayer() != payer
                || ability.getPayCosts() == null) return unknown("missing cost/activator");
        if (ability.isTrigger() || ability.isReplacementAbility() || ability.isOffering()
                || ability.isEmerge() || ability.isBestow() || ability.isCastFaceDown()
                || ability.hasParam("Announce") || !ability.getPipsToReduce().isEmpty()
                || ability.hasParam("ReduceCost") || ability.hasParam("RaiseCost")
                || ability.hasParam("TapCreaturesForMana") || ability.getHostCard().isCommander())
            return unknown("complex announcement or payment");
        for (Keyword keyword : new Keyword[]{Keyword.CONVOKE, Keyword.IMPROVISE, Keyword.DELVE, Keyword.ASSIST}) {
            if (ability.getHostCard().hasKeyword(keyword)) return unknown("alternative mana payment: " + keyword);
        }
        if (payer.hasKeyword("PayLifeInsteadOf:B")) return unknown("life substitutes for black mana");
        boolean hostUsed = false;
        int nonMana = 0;
        for (CostPart part : ability.getPayCosts().getCostParts()) {
            if (part instanceof CostPartMana) continue;
            // Multiple nonmana costs can compete for the same resource. Do not prove
            // joint feasibility by checking each independently.
            if (++nonMana > 1) return unknown("joint nonmana costs");
            if (part instanceof CostTap) {
                hostUsed = true;
            } else if (part instanceof CostRemoveCounter remove && remove.payCostFromSource()
                    && remove.convertAmount() != null) {
                // Forge's rule implementation permits spending the last loyalty.
            } else if (part instanceof CostPutCounter put && put.payCostFromSource()
                    && put.convertAmount() != null && ability.isActivatedAbility()) {
                // Non-ETB source counter cost; no temporary static-state query.
            } else return unknown("nonmana cost: " + part.getClass().getSimpleName());
            if (!part.canPay(ability, payer, false)) return answer(false);
        }

        // These effects can change payment feasibility or make source costs dependent.
        for (Card card : payer.getGame().getCardsInGame()) {
            if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Command)
                    && !card.isInZone(ZoneType.Stack) && card != ability.getHostCard()) continue;
            if (card.getStaticAbilities().stream().anyMatch(s -> s.checkMode(StaticAbilityMode.ManaConvert)
                    || s.checkMode(StaticAbilityMode.OptionalCost))) return unknown("mana conversion/optional static cost");
            if (card.getReplacementEffects().stream().filter(r -> r.zonesCheck(payer.getGame().getZoneOf(card)))
                    .anyMatch(r -> r.getMode() == ReplacementType.ProduceMana)) return unknown("mana replacement");
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
        if (shards.isEmpty()) return answer(true);
        List<Integer> tokens = new ArrayList<>();
        for (var mana : payer.getManaPool()) {
            if (mana.isRestricted()) return unknown("restricted pooled mana");
            if (payer.getManaPool().getPossibleColorUses(mana.getColor()) != mana.getColor())
                return unknown("pool color conversion");
            tokens.add((int) mana.getColor());
        }
        List<List<List<Integer>>> sources = new ArrayList<>();
        for (Card card : payer.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command)) {
            if (card.isPhasedOut() || (hostUsed && card == ability.getHostCard())) continue;
            List<List<Integer>> alternatives = new ArrayList<>();
            for (SpellAbility printed : card.getManaAbilities()) {
                if (printed.getRestrictions().getZone() != null
                        && !card.isInZone(printed.getRestrictions().getZone())
                        && !printed.hasParam("AdditionalActivationZone")) continue;
                if (!card.isInZone(ZoneType.Battlefield)) return unknown("nonbattlefield mana source");
                if (printed.isSuppressed() || card.isDetained() || card.isTapped() || card.isAbilitySick()) continue;
                // Restrictions can assign an unset activator; query only a disposable
                // ability copy, never mutate the printed source or its AI memory.
                SpellAbility source = printed.copy();
                source.setActivatingPlayer(payer);
                if (!source.getRestrictions().canPlay(card, source)) continue;
                if (source.getPayCosts() == null || source.getManaPart() == null || source.getSubAbility() != null
                        || source.hasParam("Each")) return unknown("complex mana source");
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
                        alternatives.add(new ArrayList<>(java.util.Collections.nCopies(amount, (int) ManaAtom.fromName(color))));
                } else if (produced.matches("[WUBRGC]( [WUBRGC])*")) {
                    List<Integer> output = new ArrayList<>();
                    for (int i = 0; i < amount; i++) for (String color : produced.split(" ")) output.add((int) ManaAtom.fromName(color));
                    alternatives.add(output);
                } else return unknown("choice/dynamic mana output");
            }
            if (!alternatives.isEmpty()) sources.add(alternatives);
        }
        if (sources.size() > 32) return unknown("source search bound");
        try { return answer(search(shards, tokens, sources, 0, new int[]{0})); }
        catch (Unsupported limit) { return unknown(limit.getMessage()); }
    }

    private static boolean search(List<ManaCostShard> shards, List<Integer> tokens,
                                  List<List<List<Integer>>> sources, int index, int[] visited) {
        if (++visited[0] > SEARCH_LIMIT) throw new Unsupported("source search bound");
        if (matches(shards, tokens)) return true;
        if (index == sources.size()) return false;
        for (List<Integer> output : sources.get(index)) {
            List<Integer> next = new ArrayList<>(tokens);
            next.addAll(output);
            if (search(shards, next, sources, index + 1, visited)) return true;
        }
        // Every supported source is optional and has no cross-source negative cost.
        // Using one extra source cannot destroy a payment; no skip branch is needed.
        return false;
    }

    static boolean matches(List<ManaCostShard> shards, List<Integer> tokens) {
        if (tokens.size() < shards.size()) return false;
        int[] assigned = new int[tokens.size()];
        Arrays.fill(assigned, -1);
        for (int i = 0; i < shards.size(); i++) if (!augment(i, shards, tokens, assigned, new boolean[tokens.size()])) return false;
        return true;
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
