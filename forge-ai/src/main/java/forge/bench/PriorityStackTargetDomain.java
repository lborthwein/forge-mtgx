package forge.bench;

import com.google.gson.*;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import java.util.*;

/** Rules-only metadata for one priority action, never a target selection or AI filter.
 * Initial exact scope is deliberately explicit; unsupported shapes are coverage debt.
 */
public final class PriorityStackTargetDomain {
    public static final String VERSION = "host-priority-stack-targets-v1";
    private PriorityStackTargetDomain() {}
    private static JsonObject kind(String kind) {var out=new JsonObject();out.addProperty("kind",kind);return out;}
    private static JsonObject unsupported(String reason) {var out=kind("unsupported");out.addProperty("reason",reason);return out;}
    public static JsonObject encode(SpellAbility root) {
        var modal=PriorityModalTargetDomain.encode(root, PriorityStackTargetDomain::encodeSingle);
        return modal==null?encodeSingle(root,root):modal;
    }
    private static JsonObject encodeSingle(SpellAbility root, SpellAbility start) {
        if(root==null||root.isLandAbility())return kind("none");
        SpellAbility stackGroup=null;int targetGroups=0;
        for(SpellAbility cur=start;cur!=null;cur=cur.getSubAbility()) {
            if(cur.getApi()==forge.game.ability.ApiType.Charm||PriorityBoardTargetDomain.hasCurrentAdditionalBranches(cur))
                return unsupported("modal-or-additional-branch");
            if(!cur.usesTargeting())continue;
            targetGroups++;
            if(cur.getTargetRestrictions()==null||cur.getTargetRestrictions().getZone()==null)return unsupported("missing-target-zone");
            if(cur.getTargetRestrictions().getZone().contains(ZoneType.Stack))stackGroup=cur;
        }
        if(stackGroup==null)return kind("none"); // native absent, not exact-empty
        if(targetGroups!=1)return unsupported("multiple-dependent-target-groups");
        if(root.getHostCard()==null||root.getHostCard().getGame()==null||stackGroup.getActivatingPlayer()==null)
            return unsupported("missing-game-or-actor");
        for(SpellAbility cur=start;cur!=null;cur=cur.getSubAbility()) {
            if(cur.costHasX()||cur.hasParam("Announce"))return unsupported("announcement-dependent");
            if(cur.hasParam("TargetingPlayer") || (cur.getTargetingPlayer()!=null && cur.getTargetingPlayer()!=cur.getActivatingPlayer()))return unsupported("alternate-targeting-player");
        }
        if(!stackGroup.getTargets().isEmpty())return unsupported("already-selected-targets");
        var restrictions=stackGroup.getTargetRestrictions();
        if(!Set.of("0","1").contains(restrictions.getMinTargets())||!"1".equals(restrictions.getMaxTargets()))
            return unsupported("non-single-or-dynamic-target-count");
        for(String parameter:List.of("TargetUnique","TargetsWithDefinedController","TargetsWithoutSameCreatureType",
                "TargetsWithSameCreatureType","TargetsWithSameCardType","TargetsWithSameController",
                "TargetsWithDifferentControllers","TargetsForEachPlayer","TargetsWithDifferentCMC",
                "TargetsWithDifferentNames","TargetsWithEqualToughness","TargetsAtRandom","RandomNumTargets",
                "MaxTotalTargetCMC","MaxTotalTargetPower"))
            if(stackGroup.hasParam(parameter))return unsupported("linked-or-random-target-restriction:"+parameter);
        var game=root.getHostCard().getGame();
        // The complete chosen-set MustTarget rule is not a canTarget predicate.
        // Do not pretend its coupling has been proved by individual membership.
        for(var card:game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES))for(var ability:card.getStaticAbilities())
            if(ability.checkConditions(StaticAbilityMode.MustTarget)&&ability.matchesValidParam("ValidSA",root))
                return unsupported("active-must-target-constraint");
        var out=kind("exact");var rows=new JsonArray();
        for(var entry:game.getStack()) {
            var row=new JsonObject();row.addProperty("stackId",entry.getId());row.addProperty("spellAbilityId",entry.getSpellAbility().getId());
            // A spell that cannot be countered may still be legally targeted.
            // Ownership and what Default AI wants are never filters here.
            row.addProperty("allowed",stackGroup.canTargetSpellAbility(entry.getSpellAbility()));rows.add(row);
        }
        out.add("rows",rows);return out;
    }
}
