package forge.ai;

import forge.game.ability.ApiType;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.cost.CostSacrifice;
import forge.game.cost.PaymentDecision;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import java.util.Set;

/** Finite native Orb / land-replay conversion toward a public Reservoir kill.
 * Native payment and resolution are authoritative; no simulated zone changes
 * or virtual life are used. This plan owns only its selected action object. */
final class CubeFastbondPlan {
    private final Player player;
    private Card orb, fastbond, permission, outlet, land;
    private SpellAbility selected, pending;
    private int turn = -1, actions, beforeLife, expectedLife, landPlays, painTrigger = -1;
    private boolean active, failed, replayPending;
    private long landStamp;

    CubeFastbondPlan(Player player) { this.player = player; }
    private SpellAbility stop() { failed = true; selected = null; return null; }
    private boolean window() {
        var ph = player.getGame().getPhaseHandler();
        return ph.getTurn() == turn && (ph.is(PhaseType.MAIN1, player) || ph.is(PhaseType.MAIN2, player));
    }
    private boolean current(Card c, ZoneType zone) {
        return c != null && c == player.getGame().getCardState(c, null) && c.isInZone(zone)
                && c.getOwner() == player && c.getController() == player && !c.isFaceDown() && !c.isPhasedOut();
    }
    private Card find(String name) {
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) if (name.equals(c.getName()) && current(c, ZoneType.Battlefield)) return c;
        return null;
    }
    private SpellAbility ability(Card c, ApiType api) {
        for (SpellAbility original : c.getSpellAbilities()) if (original.isActivatedAbility() && original.getApi() == api
                && original.getSubAbility() == null) return original.copy(player);
        return null;
    }
    private boolean payable(SpellAbility a) {
        return a != null && CubeComboAi.canPlayNative(a, player)
                && (a.isLandAbility() || CubeComboAi.canPayCost(a, player, false));
    }
    private boolean permissionActive() {
        if (!current(permission, ZoneType.Battlefield)) return false;
        for (var s : permission.getStaticAbilities()) if (s.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous)
                && "True".equals(s.getParam("MayPlay")) && "Graveyard".equals(s.getParam("AffectedZone"))
                && "Land.YouOwn".equals(s.getParam("Affected"))) return true;
        return false;
    }
    private boolean board() {
        return current(orb, ZoneType.Battlefield) && current(fastbond, ZoneType.Battlefield)
                && current(outlet, ZoneType.Battlefield) && permissionActive() && player.getMaxLandPlaysInfinite()
                && player.canGainLife() && !player.cantWin() && player.getLife() > 0;
    }
    private boolean domain() {
        // Effects changing the resource cycle need their own native evidence.
        // Public permanents/command/graveyards only; no opponent hidden zones.
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command, ZoneType.Graveyard})
            for (Card c : player.getGame().getCardsIn(zone)) if (!c.isFaceDown()) {
                for (var e : c.getReplacementEffects()) if (e.zonesCheck(c.getZone()) && e.requirementsCheck(player.getGame())
                        && Set.of("Moved", "GainLife", "LifeReduced", "DamageDone", "PayLife").contains(e.getParamOrDefault("Event", ""))) return false;
                for (var s : c.getStaticAbilities()) if (s.zonesCheck() && Set.of("RaiseCost", "ReduceCost", "SetCost", "CantBeCast",
                        "CantBeActivated", "CantSacrifice", "DisableTriggers", "CantPayLife").contains(s.getParamOrDefault("Mode", ""))) return false;
                for (var t : c.getTriggers()) if (!t.isSuppressed() && t.getParamOrDefault("TriggerZones", "Battlefield").contains(zone.name())) {
                    String mode = t.getMode().name();
                    if (c == fastbond && t.getId() == painTrigger && t.isIntrinsic()) continue;
                    if (Set.of("LandPlayed", "ChangesZone", "ChangesZoneAll", "Sacrificed", "SacrificedOnce", "LifeGained", "LifeLost",
                            "LifeLostAll", "PayLife", "DamageDone", "DamageDoneOnce", "DamageAll", "DamageDealtOnce", "AbilityCast",
                            "SpellAbilityCast", "AbilityResolves", "AbilityTriggered", "Always").contains(mode)) return false;
                }
            }
        return true;
    }
    private boolean simpleLand(Card c) {
        return c.isLand() && !c.isToken() && c.getOwner() == player && !c.isFaceDown()
                && c.getTriggers().isEmpty() && c.getReplacementEffects().isEmpty() && c.getStaticAbilities().isEmpty();
    }
    private SpellAbility replay() {
        if (!current(land, ZoneType.Graveyard)) return null;
        for (SpellAbility original : land.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility a = original.copy(player);
            if (a.isLandAbility() && a.getMayPlay() != null && a.getMayPlay().getHostCard() == permission && payable(a)) return a;
        }
        return null;
    }
    private SpellAbility shot() {
        if (player.getOpponents().size() != 1) return null;
        Player op = player.getOpponents().get(0);
        if (op.getLife() <= 0 || op.getLife() > 50 || !op.canLoseLife() || op.cantLose() || op.cantLoseForZeroOrLessLife()) return null;
        SpellAbility a = ability(outlet, ApiType.DealDamage);
        if (a == null || !"50".equals(a.getParam("NumDmg")) || !a.canTarget(op)) return null;
        var parts = a.getPayCosts().getCostParts();
        if (parts.size() != 1 || !(parts.get(0) instanceof forge.game.cost.CostPayLife cost) || cost.getAbilityAmount(a) != 50) return null;
        a.getTargets().add(op);
        if (!a.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(a)) return null;
        return a;
    }
    SpellAbility nextAction() {
        int now = player.getGame().getPhaseHandler().getTurn();
        if (turn != now) { turn = now; active = failed = replayPending = false; actions = 0; selected = pending = null; }
        selected = null;
        if (failed || !window() || !player.getGame().getStack().isEmpty()) return null;
        if (!active) {
            orb = find("Zuran Orb"); fastbond = find("Fastbond"); outlet = find("Aetherflux Reservoir");
            permission = find("Crucible of Worlds"); if (permission == null) permission = find("Ramunap Excavator");
            if (orb == null || fastbond == null || outlet == null || permission == null) return null;
            painTrigger = -1;
            for (var t : fastbond.getTriggers()) if (t.isIntrinsic() && !t.isSuppressed() && t.getMode() == forge.game.trigger.TriggerType.LandPlayed
                    && "True".equals(t.getParam("NotFirstLand")) && "DBPain".equals(t.getParam("Execute"))) painTrigger = t.getId();
            if (painTrigger < 0 || !board() || !domain() || shot() == null) return null;
            land = null;
            for (ZoneType z : new ZoneType[]{ZoneType.Battlefield, ZoneType.Graveyard})
                for (Card c : player.getCardsIn(z)) if (land == null && current(c, z) && simpleLand(c)) land = c;
            if (land == null) return null;
            landStamp = land.getGameTimestamp(); active = true;
        }
        if (actions >= 180 || !board() || !domain() || land.getGameTimestamp() != landStamp) return stop();
        if (pending != null || replayPending) {
            if (player.getLife() != expectedLife || replayPending && player.getLandsPlayedThisTurn() != landPlays + 1) return stop();
            pending = null; replayPending = false;
        }
        SpellAbility shot = shot();
        if (shot == null) return stop();
        if (player.getLife() > 50) return selected = payable(shot) ? shot : null;
        if (current(land, ZoneType.Graveyard)) { if (player.getLife() <= 1 && player.getLandsPlayedThisTurn() > 0) return stop(); selected = replay(); return selected == null ? stop() : selected; }
        if (!current(land, ZoneType.Battlefield)) return stop();
        SpellAbility gain = ability(orb, ApiType.GainLife);
        if (gain == null || !"2".equals(gain.getParam("LifeAmount")) || !land.canBeSacrificedBy(gain, false) || !payable(gain)) return stop();
        var parts = gain.getPayCosts().getCostParts();
        if (parts.size() != 1 || !(parts.get(0) instanceof CostSacrifice cost) || !"Land".equals(cost.getType()) || cost.getAbilityAmount(gain) != 1) return stop();
        return selected = gain;
    }
    boolean owns(SpellAbility a) { return a != null && a == selected; }
    boolean play(SpellAbility a) {
        if (!owns(a) || !window() || !board() || !domain() || land.getGameTimestamp() != landStamp
                || !current(land, a.isLandAbility() ? ZoneType.Graveyard : (a.getApi() == ApiType.GainLife ? ZoneType.Battlefield : land.getZone().getZoneType()))
                || !payable(a)) { stop(); return false; }
        beforeLife = player.getLife(); landPlays = player.getLandsPlayedThisTurn();
        Card paidLand = land;
        boolean played;
        if (a.isLandAbility()) {
            a.resolve(); land = player.getGame().getCardState(paidLand, null);
            played = current(land, ZoneType.Battlefield); replayPending = played;
            expectedLife = beforeLife - (landPlays == 0 ? 0 : 1);
        } else {
            played = ComputerUtil.handlePlayingSpellAbility(player, a, null, current -> new AiCostDecision(player, current, false) {
                @Override public PaymentDecision visit(CostSacrifice cost) {
                    if (a == current && a.getApi() == ApiType.GainLife && "Land".equals(cost.getType()) && cost.getAbilityAmount(a) == 1
                            && CubeFastbondPlan.this.current(paidLand, ZoneType.Battlefield) && paidLand.canBeSacrificedBy(a, false)) return PaymentDecision.card(paidLand);
                    return super.visit(cost);
                }
            });
            if (played && a.getApi() == ApiType.GainLife) {
                int paidCount = 0, trackedCount = 0;
                for (Card paid : a.getPaidList("Sacrificed")) { paidCount++; if (paid.getId() == paidLand.getId()) trackedCount++; }
                played = paidCount == 1 && trackedCount == 1;
                land = player.getGame().getCardState(paidLand, null);
                played &= current(land, ZoneType.Graveyard); expectedLife = beforeLife + 2;
            } else expectedLife = beforeLife - 50;
            if (played) pending = a;
        }
        if (!played) stop();
        else { actions++; landStamp = land.getGameTimestamp(); }
        System.err.println("CUBE_FASTBOND_PLAN played=" + played + " actions=" + actions + " api=" + a.getApi() + " land=" + a.isLandAbility());
        return played;
    }
    boolean waitingForOwnSpell() {
        var stack = player.getGame().getStack();
        if (!active || failed || !window() || stack.isEmpty()) return false;
        SpellAbility top = stack.peekAbility();
        if (top == null || top.isCopied() || top.getActivatingPlayer() != player) return false;
        if (top == pending) return true;
        return replayPending && top.isWrapper() && top.isTrigger() && top.getApi() == ApiType.DealDamage
                && top.getSourceTrigger() == painTrigger && top.getHostCard() == fastbond
                && top.getTriggeringObject(AbilityKey.Card) == land;
    }
}
