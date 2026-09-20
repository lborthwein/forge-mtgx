package forge.bench;

import com.google.gson.Gson;
import forge.ai.AiCardMemory;
import forge.game.Game;
import forge.game.spellability.SpellAbility;
import java.util.Map;
import java.util.TreeMap;

/** Explicit internal-state tripwire, not a proof of every reachable engine field.
 * Whole-game stock/null/probe controls remain required. Never restores state.
 */
final class BenchMenuStateAudit {
    private static final Gson JSON = new Gson();
    private BenchMenuStateAudit() {}
    private static int abilitySequence() {
        try {
            var field = SpellAbility.class.getDeclaredField("maxId");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("BENCH_INTEGRITY_FAILURE: cannot inspect ability sequence", error); }
    }
    static Map<String, String> capture(Game game) {
        Map<String, String> result = new TreeMap<>();
        int sequence = abilitySequence();
        long timestamp = game.getTimestamp();
        for (var player : game.getPlayers()) {
            result.put("view/" + player.getId(), StateEncoder.encode(game, player).toString());
            for (var set : AiCardMemory.MemorySet.values())
                result.put("memory/" + player.getId() + "/" + set, String.valueOf(AiCardMemory.getMemorySet(player, set)));
        }
        for (var card : game.getCardsInGame()) {
            result.put("card/" + card.getId(), card.getCurrentStateName() + "/" + card.isFaceDown() + "/" + card.getZone()
                    + "/" + card.getCounters() + "/" + card.isTapped() + "/" + card.getMayPlay());
            for (var ability : card.getAllSpellAbilities()) captureAbility(result, "ability/" + card.getId() + "/" + ability.getId(), ability);
        }
        if (sequence != abilitySequence()) throw new IllegalStateException("BENCH_INTEGRITY_FAILURE: state inspection initialized ability IDs");
        if (timestamp != game.getTimestamp()) throw new IllegalStateException("BENCH_INTEGRITY_FAILURE: state inspection advanced game timestamp");
        result.put("abilitySequence", Integer.toString(sequence));
        result.put("gameTimestamp", Long.toString(timestamp));
        return result;
    }
    private static void captureAbility(Map<String, String> result, String key, SpellAbility sa) {
        result.put(key, sa.getActivatingPlayer() + "/" + sa.getXManaCostPaid() + "/" + sa.getTargets() + "/" + sa.getTargetingPlayer()
                + "/" + sa.getPayCosts() + "/" + (sa.getPayCosts() == null ? "" : sa.getPayCosts().getTotalMana())
                + "/" + sa.getPipsToReduce() + "/" + sa.getPayingMana() + "/" + sa.getOptionalCosts()
                + "/" + new TreeMap<>(sa.getMapParams()) + "/" + new TreeMap<>(sa.getSVars())
                + "/" + JSON.toJson(sa.getRestrictions()) + "/" + JSON.toJson(sa.getConditions()));
        if (sa.getSubAbility() != null) captureAbility(result, key + "/sub", sa.getSubAbility());
        for (var child : sa.getAdditionalAbilities().entrySet()) captureAbility(result, key + "/" + child.getKey(), child.getValue());
        for (var children : sa.getAdditionalAbilityLists().entrySet())
            for (int i = 0; i < children.getValue().size(); i++) captureAbility(result, key + "/" + children.getKey() + "/" + i, children.getValue().get(i));
    }
    static void assertUnchanged(Map<String, String> before, Game game) {
        Map<String, String> after = capture(game);
        if (before.equals(after)) return;
        for (String key : before.keySet()) if (!java.util.Objects.equals(before.get(key), after.get(key)))
            throw new IllegalStateException("BENCH_INTEGRITY_FAILURE: priority enumeration mutated " + key);
        throw new IllegalStateException("BENCH_INTEGRITY_FAILURE: priority enumeration changed internal-state key set");
    }
}
