package forge.ai;

import forge.card.MagicColor;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;

/** Finite Breach/Channeler recurrence; native costs and effects remain authoritative. */
final class CubeBreachChannelerPlan {
    private final Player player;
    private SpellAbility selected;
    private int turn = -1, sourceId = -1, castId = -1;
    private long sourceTimestamp = -1, castTimestamp = -1;
    CubeBreachChannelerPlan(Player player) { this.player = player; }
    void reset() { selected = null; castId = -1; }
    private Card find(String name, ZoneType... zones) {
        for (ZoneType zone : zones) for (Card card : player.getCardsIn(zone))
            if (!card.isFaceDown() && name.equals(card.getName())) return card;
        return null;
    }
    private SpellAbility spell(Card card) {
        if (card != null) for (SpellAbility sa : card.getSpellAbilities()) if (sa.isSpell()) return sa.copy(player);
        return null;
    }
    private boolean payable(SpellAbility sa) {
        return sa != null && CubeComboAi.canPlayNative(sa, player) && CubeComboAi.canPayCost(sa, player, false);
    }
    private record Land(int amount, boolean blue) {}
    private boolean renewableMana(int need) {
        List<Land> lands = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            // canUntap checks permission; native untap instead removes a stun
            // counter without untapping. That source is not renewable yet.
            if (!card.isLand() || !card.canUntap(null, true)
                    || card.getCounters(forge.game.card.CounterEnumType.STUN) > 0) continue;
            for (SpellAbility original : card.getManaAbilities()) {
                if (original.getApi() != ApiType.Mana || original.getSubAbility() != null || original.usesTargeting()
                        || !original.getPayCosts().hasTapCost()
                        || original.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostTap))) continue;
                int amount;
                try { amount = Integer.parseInt(original.getParamOrDefault("Amount", "1")); }
                catch (NumberFormatException ignored) { continue; }
                Card fresh = CardCopyService.getLKICopy(card); fresh.setTapped(false);
                SpellAbility mana = original.copy(player); mana.setHostCard(fresh);
                if (!payable(mana)) continue;
                lands.add(new Land(amount, mana.canProduce("U"))); break;
            }
        }
        for (int i = 0; i < lands.size(); i++) {
            Land a = lands.get(i);
            if (a.blue() && a.amount() >= need) return true;
            for (int j = i + 1; j < lands.size(); j++) {
                Land b = lands.get(j);
                if ((a.blue() || b.blue()) && a.amount() + b.amount() >= need) return true;
                for (int k = j + 1; k < lands.size(); k++) {
                    Land c = lands.get(k);
                    if ((a.blue() || b.blue() || c.blue()) && a.amount() + b.amount() + c.amount() >= need) return true;
                }
            }
        }
        return false;
    }
    private SpellAbility triggerAbility(Card card, String mode, java.util.Map<AbilityKey, Object> params) {
        for (var trigger : card.getTriggers()) {
            if (trigger.isSuppressed() || !mode.equals(trigger.getParam("Mode"))) continue;
            boolean disabled = false;
            for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
                for (Card source : player.getGame().getCardsIn(zone)) {
                    if (source.isFaceDown()) continue;
                    for (var st : source.getStaticAbilities())
                        if (st.checkConditions(StaticAbilityMode.DisableTriggers)
                                && forge.game.staticability.StaticAbilityDisableTriggers.isDisabled(st, trigger, params)) disabled = true;
                }
            if (disabled) continue;
            String script = card.getSVar(trigger.getParamOrDefault("Execute", ""));
            if (script.isEmpty()) continue;
            SpellAbility sa = forge.game.ability.AbilityFactory.getAbility(script, card);
            sa.setActivatingPlayer(player); return sa;
        }
        return null;
    }
    private boolean finishAvailable() {
        Card reservoir = find("Aetherflux Reservoir", ZoneType.Battlefield);
        if (reservoir == null || !player.canGainLife() || player.getOpponents().size() != 1) return false;
        Player opponent = player.getOpponents().get(0);
        if (opponent.getLife() <= 0 || opponent.getLife() > 50 || !opponent.canLoseLife() || opponent.cantLoseForZeroOrLessLife()) return false;
        boolean gain = reservoir.getTriggers().stream().anyMatch(t -> !t.isSuppressed() && "SpellCast".equals(t.getParam("Mode")));
        if (!gain) return false;
        var params = AbilityKey.mapFromAffected(player); params.put(AbilityKey.LifeGained, 1); params.put(AbilityKey.Source, reservoir);
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card source : player.getGame().getCardsIn(zone)) {
                if (source.isFaceDown()) continue;
                for (var re : source.getReplacementEffects())
                    if (java.util.Set.of("NoLife", "LoseLife", "LichDraw").contains(re.getParamOrDefault("AILogic", ""))
                            && re.modeCheck(forge.game.replacement.ReplacementType.GainLife, params)
                            && re.zonesCheck(source.getZone()) && re.requirementsCheck(player.getGame()) && re.canReplace(params)) return false;
            }
        for (SpellAbility original : reservoir.getSpellAbilities()) {
            if (original.getApi() != ApiType.DealDamage) continue;
            SpellAbility shot = original.copy(player);
            if (!shot.canTarget(opponent)) continue;
            shot.resetTargets(); shot.getTargets().add(opponent);
            var adjusted = forge.game.cost.CostAdjustment.adjust(shot.getPayCosts(), shot, false);
            if (adjusted == null || adjusted.getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana)
                    && !(p instanceof forge.game.cost.CostPayLife))) continue;
            var mana = ComputerUtilMana.calculateManaCost(shot.getPayCosts(), shot, player, true, 0, false);
            if (mana.getXcounter() != 0 || !CubeComboAi.canPayManaCost(mana, shot, player, false)) continue;
            if (shot.isTargetNumberValid() && StaticAbilityMustTarget.meetsMustTargetRestriction(shot)
                    && !shot.isSuppressed() && !reservoir.isDetained() && shot.getRestrictions().canPlay(reservoir, shot)
                    && shot.isLegalAfterStack() && shot.checkRestrictions(reservoir, player)) return true;
        }
        return false;
    }
    private boolean castBudget(int cycles, List<Card> cycle) {
        List<Card> future = new ArrayList<>();
        for (Card card : cycle) {
            Card copy = CardCopyService.getLKICopy(card); copy.setLastKnownZone(player.getGame().getStackZone()); future.add(copy);
        }
        for (Card source : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (source.isFaceDown()) continue;
            for (var st : source.getStaticAbilities()) {
                if (!st.hasParam("NumLimitEachTurn") || !st.checkConditions(StaticAbilityMode.CantBeCast)
                        || !st.matchesValidParam("Caster", player) || st.getIgnoreEffectPlayers().contains(player)) continue;
                int each = (int) future.stream().filter(c -> st.matchesValidParam("ValidCard", c)).count();
                if (each == 0) continue;
                int used = forge.game.card.CardLists.filterControlledByAsList(forge.game.card.CardUtil.getThisTurnCast(
                        st.getParamOrDefault("ValidCard", "Card"), future.get(0), st, player), player).size();
                int cap;
                try { cap = Integer.parseInt(st.getParam("NumLimitEachTurn")); }
                catch (NumberFormatException ignored) { return false; }
                if ((long) used + each * cycles > cap) return false;
            }
        }
        return true;
    }

    SpellAbility nextAction(SpellAbility cast, int remainingActions) {
        reset();
        Card breach = find("Underworld Breach", ZoneType.Battlefield);
        Card channeler = find("Dragon's Rage Channeler", ZoneType.Battlefield);
        if (breach == null || channeler == null || cast == null || !cast.isEscape()
                || !"Frantic Search".equals(cast.getHostCard().getName())
                || cast.getActivatingPlayer() != player || !payable(cast) || player.getLife() > 50
                || !finishAvailable()) return null;
        if (player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> !c.isFaceDown() && "Dragon's Rage Channeler".equals(c.getName())).count() != 1) return null;
        SpellAbility discard = cast.getSubAbility(), untap = discard == null ? null : discard.getSubAbility();
        if (cast.getApi() != ApiType.Draw || !"2".equals(cast.getParam("NumCards"))
                || discard == null || discard.getApi() != ApiType.Discard || !"2".equals(discard.getParam("NumCards"))
                || untap == null || untap.getApi() != ApiType.Untap || !"Land".equals(untap.getParam("UntapType"))
                || !"3".equals(untap.getParam("Amount")) || untap.getSubAbility() != null) return null;
        int exiled = 0;
        for (var cost : cast.getPayCosts().getCostParts()) {
            if (cost instanceof forge.game.cost.CostExile exile) {
                if (exile.zoneRestriction != 1 || exile.getFrom().size() != 1
                        || exile.getFrom().get(0) != ZoneType.Graveyard) return null;
                exiled += exile.getAbilityAmount(cast);
            } else if (!(cost instanceof CostPartMana)) return null;
        }
        if (exiled != 3) return null;
        var mana = ComputerUtilMana.calculateManaCost(cast.getPayCosts(), cast, player, true, 0, false);
        if (mana.getXcounter() != 0 || mana.getUnpaidColors() != MagicColor.BLUE
                || mana.getConvertedManaCost() - mana.getGenericManaAmount() != 1
                || !renewableMana(mana.getConvertedManaCost())) return null;
        var params = AbilityKey.newMap(); params.put(AbilityKey.Card, cast.getHostCard()); params.put(AbilityKey.SpellAbility, cast);
        SpellAbility surveil = triggerAbility(channeler, "SpellCast", params);
        if (surveil == null || surveil.getApi() != ApiType.Surveil || !"1".equals(surveil.getParam("Amount"))
                || surveil.getSubAbility() != null
                || forge.game.staticability.StaticAbilitySurveilNum.surveilNumMod(player) != 0) return null;
        int prior = (int) player.getGame().getStack().getSpellsCastThisTurn().stream()
                .filter(a -> a.getActivatingPlayer() == player).count();
        long life = player.getLife(); int needed = 0;
        while (life <= 50 && needed < remainingActions - 1) { life += ++prior; needed++; }
        if (life <= 50 || !castBudget(needed, List.of(cast.getHostCard()))
                || player.getCardsIn(ZoneType.Library).size() < 3 * needed
                || !player.canDrawAmount(2 * needed)) return null;
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card source : player.getGame().getCardsIn(zone)) {
                if (source.isFaceDown()) continue;
                for (var re : source.getReplacementEffects()) {
                    if (re.isSuppressed() || !re.zonesCheck(source.getZone()) || !re.requirementsCheck(player.getGame())) continue;
                    if (re.getMode() == forge.game.replacement.ReplacementType.Draw
                            && re.matchesValidParam("ValidPlayer", player)) return null;
                    // An unknown card could be affected by a graveyard replacement.
                    if (re.getMode() == forge.game.replacement.ReplacementType.Moved
                            && (!re.hasParam("Destination") || "Graveyard".equals(re.getParam("Destination")))) return null;
                }
            }
        selected = cast; turn = player.getGame().getPhaseHandler().getTurn();
        sourceId = channeler.getId(); sourceTimestamp = channeler.getGameTimestamp();
        return cast;
    }
    void played(SpellAbility action) {
        if (action != selected) { reset(); return; }
        for (var item : player.getGame().getStack()) {
            SpellAbility cast = item.getSpellAbility();
            if (cast.isSpell() && cast.getActivatingPlayer() == player && cast.isEscape()
                    && cast.getHostCard().getId() == action.getHostCard().getId()) {
                castId = cast.getHostCard().getId(); castTimestamp = cast.getHostCard().getGameTimestamp(); return;
            }
        }
        reset();
    }
    boolean ownsSurveil(forge.game.card.CardCollection offered) {
        if (selected == null || castId < 0 || turn != player.getGame().getPhaseHandler().getTurn()
                || offered == null || offered.size() != 1
                || offered.stream().anyMatch(c -> c.getOwner() != player || !c.isInZone(ZoneType.Library))) return false;
        SpellAbility top = player.getGame().getStack().peekAbility();
        if (top == null || top.getApi() != ApiType.Surveil || top.getActivatingPlayer() != player
                || top.getHostCard().getId() != sourceId || top.getHostCard().getGameTimestamp() != sourceTimestamp) return false;
        Object cause = top.getTriggeringObject(AbilityKey.SpellAbility);
        if (!(cause instanceof SpellAbility cast) || cast.getActivatingPlayer() != player || !cast.isSpell() || !cast.isEscape()
                || cast.getHostCard().getId() != castId || cast.getHostCard().getGameTimestamp() != castTimestamp) return false;
        for (var item : player.getGame().getStack()) if (item.getSpellAbility() == cast) return true;
        return false;
    }
}
