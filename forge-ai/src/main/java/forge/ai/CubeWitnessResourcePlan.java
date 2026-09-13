package forge.ai;

import forge.card.ColorSet;
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

/** Native Snap/Petal/auxiliary-creature recurrence. All cards are own-visible.
 * The existing Reservoir terminal takes over once life is actually available. */
final class CubeWitnessResourcePlan {
    private final Player player;
    private int turn = -1;
    private Card snap, petal, auxiliary, witness, returning;
    private SpellAbility selected;
    private long witnessBefore;
    private boolean active, blinkChosen;

    CubeWitnessResourcePlan(Player player) { this.player = player; }
    void reset() { active = false; blinkChosen = false; selected = null; returning = null; }
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
    private boolean target(SpellAbility sa, Card card) {
        if (sa == null || card == null || !sa.canTarget(card)) return false;
        sa.resetTargets(); sa.getTargets().add(card);
        return sa.isTargetNumberValid() && StaticAbilityMustTarget.meetsMustTargetRestriction(sa);
    }
    private ManaCostBeingPaid cost(Card card) {
        SpellAbility sa = spell(card);
        if (sa == null) return null;
        Card preview = CardCopyService.getLKICopy(card);
        preview.setLastKnownZone(player.getGame().getStackZone());
        preview.setCastFrom(player.getZone(ZoneType.Hand)); sa.setHostCard(preview);
        var adjusted = forge.game.cost.CostAdjustment.adjust(sa.getPayCosts(), sa, false);
        if (adjusted == null || adjusted.getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana))) return null;
        var mana = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, player, true, 0, false);
        return mana.getXcounter() == 0 ? mana : null;
    }
    private Card auxiliary() {
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Hand})
            for (Card card : player.getCardsIn(zone)) {
                if (card.isFaceDown() || !card.isCreature() || card == witness
                        || "Displacer Kitten".equals(card.getName()) || card.getNetToughness() <= 0
                        || !card.getTriggers().isEmpty() || !card.getReplacementEffects().isEmpty()
                        || !card.getStaticAbilities().isEmpty()) continue;
                var mana = cost(card);
                if (mana != null && mana.getConvertedManaCost() == 1 && mana.getGenericManaAmount() == 0
                        && Integer.bitCount(mana.getUnpaidColors()) == 1) return card;
            }
        return null;
    }
    private record Land(int amount, boolean blue) {}
    private boolean renewableMana(int need) {
        List<Land> lands = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (!card.isLand() || !card.canUntap(null, true)) continue;
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
            }
        }
        return false;
    }
    private boolean petalReturnsUntapped() {
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card source : player.getGame().getCardsIn(zone)) {
                if (source.isFaceDown()) continue;
                for (var re : source.getReplacementEffects()) {
                    if (!re.zonesCheck(source.getZone()) || !re.requirementsCheck(player.getGame())
                            || !"Moved".equals(re.getParam("Event")) || !"Battlefield".equals(re.getParam("Destination"))
                            || !re.matchesValidParam("ValidCard", petal)) continue;
                    String script = source.getSVar(re.getParamOrDefault("ReplaceWith", ""));
                    if (script.matches("(?s).*DB\\$\\s*Tap(?:\\s*\\|.*|\\s*)") && script.contains("ETB$ True")) return false;
                }
            }
        return true;
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
    private boolean restorationAvailable(Card kitten) {
        var castParams = AbilityKey.newMap(); castParams.put(AbilityKey.Card, snap); castParams.put(AbilityKey.SpellAbility, spell(snap));
        SpellAbility blink = triggerAbility(kitten, "SpellCast", castParams);
        if (!target(blink, witness)) return false;
        var entry = AbilityKey.mapFromCard(witness); entry.put(AbilityKey.CardLKI, witness);
        entry.put(AbilityKey.Origin, "Exile"); entry.put(AbilityKey.Destination, "Battlefield");
        SpellAbility restore = triggerAbility(witness, "ChangesZone", entry);
        if (restore == null) return false;
        Card futureReturn = CardCopyService.getLKICopy(petal);
        futureReturn.setLastKnownZone(player.getZone(ZoneType.Graveyard));
        return target(restore, futureReturn);
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
    private boolean castBudget(int cycles) {
        List<Card> future = new ArrayList<>();
        for (Card card : List.of(snap, petal, auxiliary)) {
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
    SpellAbility nextAction(int remainingActions) {
        int now = player.getGame().getPhaseHandler().getTurn();
        if (now != turn) { reset(); turn = now; }
        active = false; blinkChosen = false; returning = null;
        if (player.getLife() > 50 || !finishAvailable()) return null;
        Card kitten = find("Displacer Kitten", ZoneType.Battlefield);
        witness = find("Eternal Witness", ZoneType.Battlefield);
        if (kitten == null || witness == null || kitten.getTriggers().stream().noneMatch(t -> !t.isSuppressed()
                && "SpellCast".equals(t.getParam("Mode"))) || witness.getTriggers().stream().noneMatch(t -> !t.isSuppressed()
                && "ChangesZone".equals(t.getParam("Mode")))) return null;
        snap = find("Snap", ZoneType.Hand, ZoneType.Graveyard);
        petal = find("Lotus Petal", ZoneType.Hand, ZoneType.Graveyard, ZoneType.Battlefield);
        auxiliary = auxiliary();
        if (snap == null || petal == null || auxiliary == null || !restorationAvailable(kitten)) return null;
        var snapCost = cost(snap); var petalCost = cost(petal); var creatureCost = cost(auxiliary);
        if (snapCost == null || petalCost == null || creatureCost == null || petalCost.getConvertedManaCost() != 0
                || snapCost.getUnpaidColors() != MagicColor.BLUE || snapCost.getConvertedManaCost() - snapCost.getGenericManaAmount() != 1
                || !renewableMana(snapCost.getConvertedManaCost()) || !petalReturnsUntapped()) return null;
        int count = (int) player.getGame().getStack().getSpellsCastThisTurn().stream().filter(a -> a.getActivatingPlayer() == player).count();
        long life = player.getLife(); int needed = 0;
        while (life <= 50 && needed < 100) { life += ++count; needed++; }
        int cycles = (needed + 2) / 3;
        if (life <= 50 || cycles * 4 + 1 > remainingActions || !castBudget(cycles)) return null;
        SpellAbility action = null;
        if (snap.isInZone(ZoneType.Hand) && petal.isInZone(ZoneType.Graveyard) && auxiliary.isInPlay()) {
            action = spell(snap); if (!target(action, auxiliary)) return null; returning = petal;
        } else if (snap.isInZone(ZoneType.Graveyard) && petal.isInZone(ZoneType.Hand) && auxiliary.isInZone(ZoneType.Hand)) {
            action = spell(petal); returning = snap;
        } else if (snap.isInZone(ZoneType.Hand) && petal.isInPlay() && auxiliary.isInZone(ZoneType.Hand)) {
            for (SpellAbility original : petal.getManaAbilities()) {
                SpellAbility mana = original.copy(player);
                byte color = creatureCost.getUnpaidColors();
                if (mana.getManaPart() != null && mana.getManaPart().canProduce(MagicColor.toShortString(color), mana) && payable(mana)) {
                    mana.setManaExpressChoice(ColorSet.fromMask(color)); action = mana; break;
                }
            }
        } else if (snap.isInZone(ZoneType.Hand) && petal.isInZone(ZoneType.Graveyard) && auxiliary.isInZone(ZoneType.Hand)) {
            action = spell(auxiliary);
            ManaCostBeingPaid joint = new ManaCostBeingPaid(creatureCost); joint.addManaCost(snapCost.toManaCost());
            if (!CubeComboAi.canPayManaCost(joint, action, player, false)
                    || !CubeComboAi.canPayManaCost(joint, spell(snap), player, false)) return null;
        }
        if (!payable(action)) return null;
        active = true; selected = action; witnessBefore = witness.getGameTimestamp();
        System.err.println("CUBE_WITNESS_RESOURCE select card=" + action.getHostCard().getName().replace(' ', '_') + " api=" + action.getApi() + " turn=" + turn);
        return action;
    }
    private Card current(Card tracked, ZoneType zone) {
        if (tracked != null) for (Card card : player.getCardsIn(zone)) if (card.getId() == tracked.getId()) return card;
        return null;
    }
    boolean stalled(SpellAbility played) {
        if (played != selected) return true;
        if (played.getHostCard().getId() == snap.getId()) return current(auxiliary, ZoneType.Hand) == null || current(petal, ZoneType.Hand) == null;
        if (played.getHostCard().getId() == petal.getId() && played.isSpell()) return current(petal, ZoneType.Battlefield) == null || current(snap, ZoneType.Hand) == null;
        return played.isSpell() && current(auxiliary, ZoneType.Battlefield) == null;
    }
    private boolean ownsReturn(SpellAbility sa) {
        Card present = current(witness, ZoneType.Battlefield);
        if (!active || !blinkChosen || returning == null || !returning.isInZone(ZoneType.Graveyard)
                || selected == null || !selected.isSpell() || present == null
                || present.getGameTimestamp() == witnessBefore || sa.getActivatingPlayer() != player
                || sa.getHostCard().getId() != witness.getId() || sa.getApi() != ApiType.ChangeZone
                || !"Graveyard".equals(sa.getParam("Origin")) || !"Hand".equals(sa.getParam("Destination"))) return false;
        Object entered = sa.getRootAbility().getTriggeringObject(AbilityKey.Card);
        return entered instanceof Card card && card.getId() == witness.getId() && card.getGameTimestamp() == present.getGameTimestamp();
    }
    boolean chooseTargets(SpellAbility sa) {
        if (!active || turn != player.getGame().getPhaseHandler().getTurn() || sa == null || sa.getActivatingPlayer() != player) return false;
        if (sa.isSpell() && sa.getHostCard().getId() == snap.getId() && selected.getHostCard().getId() == snap.getId()) return target(sa, auxiliary);
        if ("Displacer Kitten".equals(sa.getHostCard().getName()) && sa.getApi() == ApiType.ChangeZone && "Exile".equals(sa.getParam("Destination"))) {
            Object cause = sa.getRootAbility().getTriggeringObject(AbilityKey.SpellAbility);
            if (!(cause instanceof SpellAbility cast) || cast.getActivatingPlayer() != player || cast.getHostCard() != selected.getHostCard()
                    || !selected.isSpell() || returning == null || !target(sa, witness)) return false;
            blinkChosen = true; return true;
        }
        return ownsReturn(sa) && target(sa, returning);
    }
    Boolean confirm(SpellAbility sa) { return sa != null && turn == player.getGame().getPhaseHandler().getTurn() && ownsReturn(sa) ? Boolean.TRUE : null; }
}
