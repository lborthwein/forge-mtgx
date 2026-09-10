package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.*;

/** Complete finite witness surface for the explicitly supported payment domain.
 * Source-order permutations are selected explicitly in the answer. No truncation:
 * exhausting a bound throws before any menu is sent to the host.
 */
public final class RulesPaymentChoices {
    private static final int MAX_NODES = 200_000;
    private static final int MAX_PLANS = 4096;
    private final RulesCostFeasibility.PaymentSpace space;
    private final List<RulesCostFeasibility.PaymentWitness> plans = new ArrayList<>();
    private final Map<RulesCostFeasibility.SourceChoice, String> sourceIds = new LinkedHashMap<>();
    private int nodes;

    public RulesPaymentChoices(Player player, SpellAbility ability) {
        var assessment = RulesCostFeasibility.assess(player, ability);
        if (assessment.status() != RulesCostFeasibility.Status.PAYABLE || assessment.space() == null)
            throw new RulesCostFeasibility.Unsupported("payment space unavailable: " + assessment.reason());
        space = assessment.space();
        for (var group : space.sources()) for (var source : group) {
            sourceIds.put(source, "s" + source.ability().getHostCard().getId() + "-a" + source.ability().getId() + "-o" + sourceIds.size());
        }
        enumerateSources(0, new ArrayList<>(space.pool()), new ArrayList<>());
        if (plans.isEmpty()) throw new RulesCostFeasibility.Unsupported("payable cost generated no complete witness");
    }

    private void visit() { if (++nodes > MAX_NODES) throw new RulesCostFeasibility.Unsupported("complete payment enumeration node bound"); }
    private void enumerateSources(int index, List<RulesCostFeasibility.Token> tokens, List<RulesCostFeasibility.SourceChoice> chosen) {
        visit();
        if (index == space.sources().size()) {
            var shards = new ArrayList<>(space.shards());
            shards.sort(Comparator.comparingInt(Enum::ordinal));
            allocate(shards, 0, tokens, new boolean[tokens.size()], new ArrayList<>(), chosen, -1);
            return;
        }
        enumerateSources(index + 1, tokens, chosen);
        for (var source : space.sources().get(index)) {
            var next = new ArrayList<>(tokens);
            for (int i = 0; i < source.output().size(); i++) next.add(new RulesCostFeasibility.Token(source.output().get(i), null, source, i));
            var nextChosen = new ArrayList<>(chosen);
            nextChosen.add(source);
            enumerateSources(index + 1, next, nextChosen);
        }
    }
    private void allocate(List<ManaCostShard> shards, int index, List<RulesCostFeasibility.Token> tokens, boolean[] used,
                          List<RulesCostFeasibility.Allocation> allocations, List<RulesCostFeasibility.SourceChoice> chosen, int previousToken) {
        visit();
        if (index == shards.size()) {
            if (plans.size() >= MAX_PLANS) throw new RulesCostFeasibility.Unsupported("complete payment witness bound");
            plans.add(new RulesCostFeasibility.PaymentWitness(space.cost(), List.copyOf(chosen), List.copyOf(allocations)));
            return;
        }
        ManaCostShard shard = shards.get(index);
        for (int i = 0; i < tokens.size(); i++) {
            // Identical shard ordinals are not different decisions. Different token
            // provenance IS preserved; never collapse it into an arbitrary payer.
            if (used[i] || (index > 0 && shard == shards.get(index - 1) && i <= previousToken)
                    || !shard.canBePaidWithManaOfColor((byte) tokens.get(i).color())) continue;
            // The bounded source has one fixed activation, unrestricted/effectless
            // output. Equal-colored units from THAT SAME activation have identical
            // Forge Mana semantics, so their unit-index permutations are not choices.
            // Never apply this to floating tokens or to two different activations.
            boolean interchangeableEarlier = false;
            for (int j = 0; j < i; j++) if (!used[j] && tokens.get(i).source() != null
                    && tokens.get(i).source() == tokens.get(j).source() && tokens.get(i).color() == tokens.get(j).color()) {
                interchangeableEarlier = true;
                break;
            }
            if (interchangeableEarlier) continue;
            used[i] = true;
            allocations.add(new RulesCostFeasibility.Allocation(shard, tokens.get(i)));
            allocate(shards, index + 1, tokens, used, allocations, chosen, i);
            allocations.remove(allocations.size() - 1);
            used[i] = false;
        }
    }

    public JsonObject request() {
        var out = new JsonObject();
        out.addProperty("paymentVersion", "rules-payment-v1");
        out.addProperty("complete", true);
        out.addProperty("sourceOrderRequired", true);
        out.addProperty("domain", "fixed-cost-supported-tap-source-activations-and-token-shard-allocations");
        var cost = new JsonObject();
        cost.addProperty("mana", space.cost().toString());
        cost.addProperty("x", 0);
        var shards = new JsonArray();
        for (var shard : space.shards()) shards.add(shard.name());
        cost.add("shards", shards);
        out.add("cost", cost);
        var pool = new JsonArray();
        for (int i = 0; i < space.pool().size(); i++) {
            var token = space.pool().get(i);
            var item = new JsonObject();
            item.addProperty("id", "pool" + i);
            item.addProperty("color", MagicColor.toShortString((byte) token.color()));
            item.addProperty("sourceFid", token.floating().getSourceCard().getId());
            pool.add(item);
        }
        out.add("pool", pool);
        var options = new JsonArray();
        for (var entry : sourceIds.entrySet()) {
            var source = entry.getKey();
            var item = new JsonObject();
            item.addProperty("id", entry.getValue());
            item.addProperty("fid", source.ability().getHostCard().getId());
            item.addProperty("abilityId", source.ability().getId());
            item.addProperty("abilityIndex", source.ability().getHostCard().getManaAbilities().indexOf(source.ability()));
            item.addProperty("choice", source.choice());
            item.addProperty("tap", true);
            item.addProperty("sacrificeSelf", source.ability().getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostSacrifice));
            var output = new JsonArray();
            for (int color : source.output()) output.add(MagicColor.toShortString((byte) color));
            item.add("output", output);
            options.add(item);
        }
        out.add("sourceOptions", options);
        var menu = new JsonArray();
        for (int index = 0; index < plans.size(); index++) {
            var witness = plans.get(index);
            var item = new JsonObject();
            item.addProperty("id", "p" + index);
            var sources = new JsonArray();
            for (var source : witness.sources()) sources.add(sourceIds.get(source));
            item.add("sources", sources);
            var spend = new JsonArray();
            for (var allocation : witness.allocations()) {
                var token = allocation.token();
                var payment = new JsonObject();
                payment.addProperty("token", token.source() == null ? "pool" + token.outputIndex()
                        : sourceIds.get(token.source()) + ":" + token.outputIndex());
                payment.addProperty("color", MagicColor.toShortString((byte) token.color()));
                payment.addProperty("shard", allocation.shard().name());
                spend.add(payment);
            }
            item.add("spend", spend);
            item.addProperty("lifePaid", 0);
            menu.add(item);
        }
        out.add("menu", menu);
        return out;
    }

    public RulesCostFeasibility.PaymentWitness select(JsonObject answer) {
        try {
            if (answer == null || answer.has("delegate") || !answer.has("choice") || !answer.has("sourceOrder"))
                throw new IllegalArgumentException("explicit choice and sourceOrder required");
            String number = answer.get("choice").getAsString();
            if (!number.matches("0|[1-9][0-9]{0,6}")) throw new IllegalArgumentException("payment choice must be an integer");
            int choice = Integer.parseInt(number);
            if (choice >= plans.size()) throw new IllegalArgumentException("payment choice out of range");
            var witness = plans.get(choice);
            var order = answer.getAsJsonArray("sourceOrder");
            var remaining = new LinkedHashMap<String, RulesCostFeasibility.SourceChoice>();
            for (var source : witness.sources()) remaining.put(sourceIds.get(source), source);
            if (order.size() != remaining.size()) throw new IllegalArgumentException("source order length mismatch");
            List<RulesCostFeasibility.SourceChoice> ordered = new ArrayList<>();
            for (var id : order) {
                var source = remaining.remove(id.getAsString());
                if (source == null) throw new IllegalArgumentException("unknown/duplicate source in sourceOrder");
                ordered.add(source);
            }
            return new RulesCostFeasibility.PaymentWitness(witness.cost(), List.copyOf(ordered), witness.allocations());
        } catch (RuntimeException invalid) {
            throw new RulesCostFeasibility.Unsupported("invalid complete-payment answer: " + invalid.getMessage());
        }
    }
}
