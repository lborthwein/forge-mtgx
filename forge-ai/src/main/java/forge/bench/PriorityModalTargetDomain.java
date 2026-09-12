package forge.bench;

import com.google.gson.*;
import forge.game.ability.ApiType;
import forge.game.spellability.SpellAbility;
import java.util.Set;
import java.util.function.BiFunction;

/** Per-printed-mode masks, not a selected mode or a union that loses multiplicity. */
final class PriorityModalTargetDomain {
    private PriorityModalTargetDomain() {}
    static JsonObject encode(SpellAbility root, BiFunction<SpellAbility, SpellAbility, JsonObject> encodeBranch) {
        if (root == null || root.getApi() != ApiType.Charm) return null;
        if (root.getActivatingPlayer() == null || root.getHostCard() == null || root.usesTargeting()
                || root.getSubAbility() != null || !root.getAdditionalAbilities().isEmpty()
                || !root.getAdditionalAbilityLists().keySet().equals(Set.of("Choices"))
                || !"1".equals(root.getParamOrDefault("CharmNum", "1"))
                || !"1".equals(root.getParamOrDefault("MinCharmNum", "1"))
                || root.costHasX() || root.hasParam("Announce") || root.isEntwine())
            return unsupported("unsupported-modal-target-structure");
        for (String param : Set.of("CanRepeatModes", "Optional", "Random", "Chooser", "ChoiceRestriction", "ChoicesNum",
                "TargetingPlayer", "AdditionalDescription", "CharmOrder"))
            if (root.hasParam(param)) return unsupported("unsupported-modal-target-parameter:" + param);
        // Copy all branches together so each canTarget still sees the real root
        // (spell versus activation, source and actor) without selecting any mode.
        var detached = root.copyForEnumeration(root.getActivatingPlayer());
        var branches = detached.getAdditionalAbilityList("Choices");
        if (branches == null || branches.isEmpty() || branches.size() > 16)
            return unsupported("missing-or-unbounded-modal-branches");
        var out = new JsonObject();out.addProperty("kind", "modes");
        out.addProperty("version", "priority-modal-domains-v1");
        out.addProperty("sourceFid", root.getHostCard().getId());
        out.addProperty("actor", root.getHostCard().getGame().getPlayers().indexOf(root.getActivatingPlayer()));
        out.addProperty("min", 1);out.addProperty("max", 1);
        var encoded = new JsonArray();
        for (int i=0;i<branches.size();i++) {
            var branch=branches.get(i);branch.setParent(detached);
            var row=new JsonObject();row.addProperty("index",i);
            row.add("domain",encodeBranch.apply(detached,branch));encoded.add(row);
        }
        out.add("branches",encoded);return out;
    }
    private static JsonObject unsupported(String reason) {
        var out=new JsonObject();out.addProperty("kind","unsupported");out.addProperty("reason",reason);return out;
    }
}
