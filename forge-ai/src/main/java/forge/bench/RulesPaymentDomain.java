package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.card.MagicColor;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.*;

/** Exact finite payment domain, represented without enumerating its Cartesian
 * product. This class neither chooses a payment nor changes source feasibility.
 * One option per source group may be selected (or none), in host-specified order;
 * each indexed cost shard is matched to one distinct advertised mana token.
 * Unspent production is allowed. Equal-colored floating tokens remain distinct.
 * The bridge uses this exact domain for host-selected payment and execution.
 */
public final class RulesPaymentDomain {
    public static final String PAYMENT_VERSION = RulesCostFeasibility.PAYMENT_VERSION;
    public static final String REPRESENTATION = "token-shard-domain-v1";
    private final RulesCostFeasibility.PaymentSpace space;
    private final int lifeAvailable;
    private final Map<String, RulesCostFeasibility.SourceChoice> sources = new LinkedHashMap<>();
    private final Map<String, Integer> groups = new HashMap<>();
    private final Map<String, RulesCostFeasibility.Token> tokens = new LinkedHashMap<>();
    private final IdentityHashMap<RulesCostFeasibility.SourceChoice, String> sourceIds = new IdentityHashMap<>();
    private final JsonObject request;

    public RulesPaymentDomain(Player payer, SpellAbility ability) {
        this(paymentSpace(payer, ability), payer.getLife());
    }

    private static RulesCostFeasibility.PaymentSpace paymentSpace(Player payer, SpellAbility ability) {
        var result = RulesCostFeasibility.assess(payer, ability);
        if (result.status() != RulesCostFeasibility.Status.PAYABLE || result.space() == null)
            throw new RulesCostFeasibility.Unsupported("payment domain unavailable: " + result.reason());
        return result.space();
    }

    // Only trusted rules-assessed spaces enter this representation. Package
    // visibility supports differential fixtures; it is not a host deserializer.
    RulesPaymentDomain(RulesCostFeasibility.PaymentSpace assessed, int lifeAvailable) {
        space = new RulesCostFeasibility.PaymentSpace(assessed.cost(), List.copyOf(assessed.shards()),
                List.copyOf(assessed.pool()), assessed.sources().stream().map(List::copyOf).toList(), assessed.life());
        this.lifeAvailable = lifeAvailable;
        if (space.life() < 0 || (space.life() > 0 && lifeAvailable < space.life()))
            throw new RulesCostFeasibility.Unsupported("invalid assessed payment life budget");
        for (int i = 0; i < space.pool().size(); i++) tokens.put("pool" + i, space.pool().get(i));
        for (int group = 0; group < space.sources().size(); group++) {
            for (var source : space.sources().get(group)) {
                String id = "s" + source.ability().getHostCard().getId() + "-a" + source.ability().getId() + "-o" + sources.size();
                if (sourceIds.put(source, id) != null) throw new RulesCostFeasibility.Unsupported("duplicate assessed source option");
                sources.put(id, source); groups.put(id, group);
                for (int i = 0; i < source.output().size(); i++)
                    tokens.put(id + ":" + i, new RulesCostFeasibility.Token(source.output().get(i), null, source, i));
            }
        }
        request = encode();
    }

    /** A snapshot of the full domain; mutating it cannot alter validation. */
    public JsonObject request() { return request.deepCopy(); }

    private JsonObject encode() {
        var out = new JsonObject();
        out.addProperty("paymentVersion", PAYMENT_VERSION);
        out.addProperty("representation", REPRESENTATION);
        out.addProperty("complete", true);
        out.addProperty("sourceOrderRequired", true);
        out.addProperty("sourceGroupRule", "at-most-one-option-per-group");
        out.addProperty("shardIdentity", "index-in-cost.shards");
        out.addProperty("producedTokenIdentity", "source-option-id:output-index");
        out.addProperty("lifeAvailable", lifeAvailable);
        var cost = new JsonObject();
        cost.addProperty("mana", space.cost().toString()); cost.addProperty("x", 0); cost.addProperty("life", space.life());
        var shards = new JsonArray();
        for (var shard : space.shards()) shards.add(shard.name());
        cost.add("shards", shards); out.add("cost", cost);
        var pool = new JsonArray();
        for (int i = 0; i < space.pool().size(); i++) {
            var token = space.pool().get(i);
            var item = new JsonObject(); item.addProperty("id", "pool" + i);
            item.addProperty("color", MagicColor.toShortString((byte) token.color()));
            item.addProperty("sourceFid", token.floating().getSourceCard().getId());
            item.addProperty("persistent", token.floating().isPersistentMana());
            item.addProperty("combat", token.floating().isCombatMana());
            item.addProperty("snow", token.floating().isSnow());
            pool.add(item);
        }
        out.add("pool", pool);
        var options = new JsonArray();
        for (var entry : sources.entrySet()) {
            var source = entry.getValue();
            var item = new JsonObject(); item.addProperty("id", entry.getKey());
            item.addProperty("group", "g" + groups.get(entry.getKey()));
            item.addProperty("fid", source.ability().getHostCard().getId());
            item.addProperty("abilityId", source.ability().getId());
            item.addProperty("abilityIndex", source.ability().getHostCard().getManaAbilities().indexOf(source.ability()));
            item.addProperty("choice", source.choice()); item.addProperty("tap", true);
            item.addProperty("life", source.life());
            item.addProperty("persistent", source.traits().persistent());
            item.addProperty("combat", source.traits().combat());
            item.addProperty("snow", source.traits().snow());
            item.addProperty("sacrificeSelf", source.ability().getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostSacrifice));
            var output = new JsonArray();
            for (int color : source.output()) output.add(MagicColor.toShortString((byte) color));
            item.add("output", output); options.add(item);
        }
        out.add("sourceOptions", options);
        return out;
    }

    /** Validate the host's complete witness in linear time in the domain plus
     * answer size. No guessed source, allocation, ordering, or payment fallback.
     * Extra selected sources and deliberate surplus are legal in this domain.
     */
    public RulesCostFeasibility.PaymentWitness select(JsonObject answer) {
        try {
            if (answer == null || answer.has("delegate") || answer.has("choice")
                    || !PAYMENT_VERSION.equals(string(answer.get("paymentVersion"))))
                throw new IllegalArgumentException("explicit symbolic payment version required; no delegate/legacy choice");
            var order = array(answer, "sourceOrder");
            var spend = array(answer, "spend");
            if (order.size() > space.sources().size() || spend.size() != space.shards().size())
                throw new IllegalArgumentException("payment source/allocation count mismatch");
            var usedGroups = new HashSet<Integer>();
            var selectedIds = new HashSet<String>();
            var ordered = new ArrayList<RulesCostFeasibility.SourceChoice>();
            long life = space.life();
            for (var rawId : order) {
                String id = string(rawId);
                var source = sources.get(id);
                if (source == null || !selectedIds.add(id) || !usedGroups.add(groups.get(id)))
                    throw new IllegalArgumentException("unknown, duplicate, or mutually exclusive source option");
                ordered.add(source); life += source.life();
            }
            if ((life > 0 && life > lifeAvailable) || life != integer(answer.get("lifePaid")))
                throw new IllegalArgumentException("payment life total/budget mismatch");
            var usedTokens = new HashSet<String>();
            var usedShards = new boolean[space.shards().size()];
            var allocations = new ArrayList<RulesCostFeasibility.Allocation>();
            for (var raw : spend) {
                if (!raw.isJsonObject()) throw new IllegalArgumentException("allocation must be an object");
                var allocation = raw.getAsJsonObject();
                String id = string(allocation.get("token"));
                int shardIndex = integer(allocation.get("shardIndex"));
                var token = tokens.get(id);
                if (token == null || !usedTokens.add(id) || shardIndex >= usedShards.length || usedShards[shardIndex])
                    throw new IllegalArgumentException("unknown/reused token or missing/reused shard");
                if (token.source() != null && !selectedIds.contains(sourceIds.get(token.source())))
                    throw new IllegalArgumentException("token belongs to an unselected source activation");
                var shard = space.shards().get(shardIndex);
                if (!shard.canBePaidWithManaOfColor((byte) token.color()))
                    throw new IllegalArgumentException("token color cannot pay requested shard");
                usedShards[shardIndex] = true;
                allocations.add(new RulesCostFeasibility.Allocation(shard, token));
            }
            return new RulesCostFeasibility.PaymentWitness(space.cost(), List.copyOf(ordered), List.copyOf(allocations), space.life());
        } catch (RuntimeException invalid) {
            throw new RulesCostFeasibility.Unsupported("invalid payment-domain answer: " + invalid.getMessage());
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        return value.getAsJsonArray();
    }
    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("expected string identity");
        return value.getAsString();
    }
    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]{0,9}"))
            throw new IllegalArgumentException("expected nonnegative JSON integer");
        return Integer.parseInt(value.getAsString());
    }
}
