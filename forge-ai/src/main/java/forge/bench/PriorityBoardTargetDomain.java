package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import java.util.List;
import java.util.Set;

/** Rules-only board/player target membership for one priority action; never selects or ranks a target. */
public final class PriorityBoardTargetDomain {
    public static final String VERSION = "host-priority-board-targets-v1";
    private PriorityBoardTargetDomain() {}

    private static JsonObject kind(String kind) { JsonObject out = new JsonObject(); out.addProperty("kind", kind); return out; }
    private static JsonObject unsupported(String reason) { JsonObject out = kind("unsupported"); out.addProperty("reason", reason); return out; }

    /** DelayedTrigger's Execute is copied into a future trigger, not chosen as
     * part of this activation (DelayedTriggerEffect.resolve). Its future target
     * decision must still go through the controller when that trigger fires. */
    static boolean hasCurrentAdditionalBranches(SpellAbility ability) {
        return !ability.getAdditionalAbilityLists().isEmpty()
                || (!ability.getAdditionalAbilities().isEmpty()
                    && !(ability.getApi() == ApiType.DelayedTrigger
                        && ability.getAdditionalAbilities().keySet().equals(Set.of("Execute"))));
    }

    public static JsonObject encode(SpellAbility root) {
        JsonObject modal = PriorityModalTargetDomain.encode(root, (parent, branch) -> encodeAtX(parent, false, branch));
        if (modal != null) return modal;
        if (root == null || root.getPayCosts() == null || root.getPayCosts().getTotalMana().countX() == 0)
            return encodeAtX(root, false);
        // Keep every existing structural refusal, including no-target -> none.
        JsonObject initial = encodeAtX(root, true);
        if (!"exact".equals(initial.get("kind").getAsString())) return initial;
        final RulesCostFeasibility.XRange range;
        try {
            range = RulesCostFeasibility.announcementRange(root.getActivatingPlayer(), root);
        } catch (RuntimeException failure) {
            return unsupported("unsupported-x-announcement-range");
        }
        if (range.min() < 0 || range.max() < range.min() || range.max() > 128)
            return unsupported("unsupported-x-announcement-range");
        JsonObject baseline = null;
        // Native boardTargetsFor evaluates at X=0. Include that value even when
        // it is below the payable minimum; compare every payable X, not samples.
        for (int x = 0; x <= range.max(); x++) {
            SpellAbility trial = root.copyForEnumeration(root.getActivatingPlayer());
            trial.setXManaCostPaid(x);
            JsonObject current = encodeAtX(trial, true);
            if (!"exact".equals(current.get("kind").getAsString()))
                return unsupported("x-dependent-target-domain");
            if (baseline == null) baseline = current;
            else if (!baseline.equals(current)) return unsupported("x-dependent-target-domain");
        }
        JsonObject proof = new JsonObject();
        proof.addProperty("version", "enumerated-x-board-invariant-v1");
        proof.addProperty("min", range.min()); proof.addProperty("max", range.max());
        proof.addProperty("checkedFrom", 0); proof.addProperty("checkedThrough", range.max());
        baseline.add("xInvariant", proof);
        return baseline;
    }

    private static JsonObject encodeAtX(SpellAbility root, boolean ordinaryX) {
        return encodeAtX(root, ordinaryX, root);
    }

    private static JsonObject encodeAtX(SpellAbility root, boolean ordinaryX, SpellAbility start) {
        if (root == null || root.isLandAbility()) return kind("none");
        SpellAbility group = null;
        int targetGroups = 0;
        for (SpellAbility cur = start; cur != null; cur = cur.getSubAbility()) {
            if (cur.getApi() == ApiType.Charm || hasCurrentAdditionalBranches(cur))
                return unsupported("modal-or-additional-branch");
            if (!cur.usesTargeting()) continue;
            if (cur.getTargetRestrictions() == null || cur.getTargetRestrictions().getZone() == null
                    || cur.getTargetRestrictions().getValidTgts() == null)
                return unsupported("missing-target-restrictions-or-zone");
            targetGroups++;
            if (boardOrPlayerTarget(cur)) group = cur;
        }
        if (group == null) return kind("none");
        if (targetGroups != 1) return unsupported("multiple-dependent-target-groups");
        // X/announcements only alter a board/player declaration. A no-target X spell
        // has no board target domain and must remain none.
        for (SpellAbility cur = start; cur != null; cur = cur.getSubAbility()) {
            if ((!ordinaryX && cur.costHasX()) || cur.hasParam("Announce")) return unsupported("announcement-dependent");
            if (cur.hasParam("TargetingPlayer") || (cur.getTargetingPlayer() != null
                    && cur.getTargetingPlayer() != cur.getActivatingPlayer())) return unsupported("alternate-targeting-player");
        }
        if (root.getHostCard() == null || root.getHostCard().getGame() == null || group.getActivatingPlayer() == null)
            return unsupported("missing-game-or-actor");
        if (!group.getTargets().isEmpty()) return unsupported("already-selected-targets");
        var restrictions = group.getTargetRestrictions();
        if (restrictions == null) return unsupported("missing-target-restrictions");
        if (!Set.of("0", "1").contains(restrictions.getMinTargets()) || !"1".equals(restrictions.getMaxTargets()))
            return unsupported("non-single-or-dynamic-target-count");
        for (String parameter : List.of("TargetUnique", "TargetsWithDefinedController", "TargetsWithoutSameCreatureType",
                "TargetsWithSameCreatureType", "TargetsWithSameCardType", "TargetsWithSharedCardType", "TargetsWithSharedTypes",
                "TargetsWithSameController", "TargetsWithDifferentControllers", "TargetsForEachPlayer", "TargetsWithDifferentCMC",
                "TargetsWithDifferentNames", "TargetsWithEqualToughness", "TargetsAtRandom", "RandomNumTargets",
                "MaxTotalTargetCMC", "MaxTotalTargetPower", "TargetsWithRelatedProperty", "TargetingPlayerControls", "TargetValidTargeting"))
            if (group.hasParam(parameter)) return unsupported("linked-or-random-target-restriction:" + parameter);
        Game game = root.getHostCard().getGame();
        // MustTarget is a whole-choice constraint, not a per-object canTarget predicate.
        for (Card card : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) for (var ability : card.getStaticAbilities())
            if (ability.checkConditions(StaticAbilityMode.MustTarget) && ability.matchesValidParam("ValidSA", root))
                return unsupported("active-must-target-constraint");
        JsonObject out = kind("exact");
        out.addProperty("sourceFid", root.getHostCard().getId());
        out.addProperty("actor", game.getPlayers().indexOf(group.getActivatingPlayer()));
        JsonArray rows = new JsonArray();
        for (Player player : game.getPlayers()) for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", "card"); row.addProperty("id", card.getId()); row.addProperty("allowed", group.canTarget(card)); rows.add(row);
        }
        for (int seat = 0; seat < game.getPlayers().size(); seat++) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", "player"); row.addProperty("id", seat); row.addProperty("allowed", group.canTarget(game.getPlayers().get(seat))); rows.add(row);
        }
        out.add("rows", rows);
        return out;
    }

    private static boolean boardOrPlayerTarget(SpellAbility ability) {
        var restrictions = ability.getTargetRestrictions();
        if (restrictions == null) return true; // malformed target declarations must not be hidden as none
        var zones = restrictions.getZone();
        // Player validity is independent of TgtZone: a script may constrain its card
        // branch to a non-battlefield zone while still targeting players.
        if (restrictions.canTgtPlayer()) return true;
        if (zones != null && zones.contains(ZoneType.Battlefield)) return true;
        // Native default is explicitly [Battlefield], not null. Null is refused
        // by encode before this classifier is reached.
        return false;
    }
}
