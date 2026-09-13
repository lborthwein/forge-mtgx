package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import java.util.function.IntPredicate;

/** Subordinate recurrence sequencer for CubeTopPlan. The parent owns actions,
 * finite finish checks and the terminal. No hidden library identity is read:
 * library casts require native look/play permission; recovery uses one native
 * draw/dig after our actual Top activation, then checks our own hand. */
final class CubeTopKittenPlan {
    private static final String TOP = "Sensei's Divining Top", KITTEN = "Displacer Kitten";
    private final Player player;
    private int turn = -1;
    private Card knownTop, partner;
    private String route;
    private boolean recoveryNeeded, recoveryAttempted, active;
    private SpellAbility selected;

    CubeTopKittenPlan(Player player) { this.player = player; }
    void reset() { knownTop = null; partner = null; route = null; selected = null; active = false; recoveryNeeded = false; recoveryAttempted = false; }
    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone)) if (!card.isFaceDown() && name.equals(card.getName())) return card;
        return null;
    }
    private SpellAbility ability(Card card, ApiType api) {
        if (card != null) for (SpellAbility original : card.getSpellAbilities()) if (original.getApi() == api) return original.copy(player);
        return null;
    }
    private boolean payable(SpellAbility sa) {
        return sa != null && CubeComboAi.canPlayNative(sa, player) && CubeComboAi.canPayCost(sa, player, false);
    }
    private SpellAbility cast(Card card, boolean library) {
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility sa = original.copy(player);
            if (!sa.isSpell() || library && sa.getMayPlay() == null) continue;
            if (payable(sa)) return sa;
        }
        return null;
    }
    private ManaCostBeingPaid recastCost(Card top, boolean library) {
        Card copy = CardCopyService.getLKICopy(top);
        copy.setLastKnownZone(player.getGame().getStackZone());
        copy.setCastFrom(player.getZone(library ? ZoneType.Library : ZoneType.Hand));
        SpellAbility spell = top.getSpellPermanent().copy(player); spell.setHostCard(copy);
        var adjusted = forge.game.cost.CostAdjustment.adjust(spell.getPayCosts(), spell, false);
        if (adjusted == null || adjusted.getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana))) return null;
        var cost = ComputerUtilMana.calculateManaCost(spell.getPayCosts(), spell, player, true, 0, false);
        if (cost.getXcounter() != 0 || cost.getConvertedManaCost() != cost.getGenericManaAmount()
                || !CubeComboAi.canPayManaCost(cost, spell, player, false)) return null;
        return cost;
    }
    private boolean refunded(ManaCostBeingPaid cost) {
        if (cost.isPaid()) return true;
        Card birgi = find("Birgi, God of Storytelling", ZoneType.Battlefield);
        return cost.getConvertedManaCost() == 1 && birgi != null && birgi.getTriggers().stream().anyMatch(t -> !t.isSuppressed()
                && "SpellCast".equals(t.getParam("Mode")) && "You".equals(t.getParam("ValidActivatingPlayer")));
    }
    private boolean returnsUntapped(Card card) {
        // Public ETB-tapped replacements invalidate both the mana restoration
        // and Top's next draw activation. No prospective move is executed.
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card source : player.getGame().getCardsIn(zone)) {
                if (source.isFaceDown()) continue;
                for (var re : source.getReplacementEffects()) {
                    if (!re.zonesCheck(source.getZone()) || !re.requirementsCheck(player.getGame())
                            || !"Moved".equals(re.getParam("Event")) || !"Battlefield".equals(re.getParam("Destination"))
                            || !re.matchesValidParam("ValidCard", card)) continue;
                    String script = source.getSVar(re.getParamOrDefault("ReplaceWith", ""));
                    if (script.matches("(?s).*DB\\$\\s*Tap(?:\\s*\\|.*|\\s*)") && script.contains("ETB$ True")) return false;
                }
            }
        return true;
    }
    private Card manaPartner(int need) {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (!card.isArtifact() || card.isCreature() || card.isFaceDown() || card.isPhasedOut()
                    || !card.getReplacementEffects().isEmpty()) continue;
            for (SpellAbility original : card.getManaAbilities()) {
                if (original.getApi() != ApiType.Mana || original.getSubAbility() != null || original.usesTargeting()
                        || !original.getPayCosts().hasTapCost()
                        || original.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostTap)
                            && (!(p instanceof CostPartMana mana) || mana.getManaCostFor(original).getCMC() != 0))) continue;
                int amount;
                try { amount = Integer.parseInt(original.getParamOrDefault("Amount", "1")); }
                catch (NumberFormatException ignored) { continue; }
                if (amount < need) continue;
                Card fresh = CardCopyService.getLKICopy(card); fresh.setTapped(false);
                SpellAbility mana = original.copy(player); mana.setHostCard(fresh);
                if (payable(mana)) return card;
            }
        }
        return null;
    }
    private boolean enough(Card top, boolean recovering, int need, IntPredicate castBudget) {
        int casts = need;
        if (need == 0) {
            long life = player.getLife();
            int count = (int)player.getGame().getStack().getSpellsCastThisTurn().stream()
                    .filter(sa -> sa.getActivatingPlayer() == player).count();
            for (casts = 0; life <= 50 && casts < 100; casts++) life += ++count;
            if (life <= 50) return false;
        }
        boolean inHand = top.isInZone(ZoneType.Hand);
        boolean inLibrary = recovering;
        int freshDraws = Math.max(0, casts - (inHand || inLibrary ? 1 : 0));
        int available = player.getCardsIn(ZoneType.Library).size() - (inLibrary ? 1 : 0);
        int actualDraws = freshDraws * ("ring".equals(route) ? 2 : 1) + (inLibrary && "ring".equals(route) ? 1 : 0);
        return available >= freshDraws && player.canDrawAmount(actualDraws) && castBudget.test(casts);
    }
    SpellAbility nextAction(int need, IntPredicate castBudget) {
        int now = player.getGame().getPhaseHandler().getTurn();
        if (turn != now) { reset(); turn = now; }
        active = false;
        Card kitten = find(KITTEN, ZoneType.Battlefield);
        if (kitten == null || kitten.isPhasedOut() || kitten.getTriggers().stream().noneMatch(t -> !t.isSuppressed()
                && "SpellCast".equals(t.getParam("Mode")))) return null;
        Card top = find(TOP, ZoneType.Battlefield);
        if (top == null) top = find(TOP, ZoneType.Hand);
        if (top != null) { knownTop = top; recoveryNeeded = false; recoveryAttempted = false; }
        else if (knownTop == null || !recoveryNeeded || recoveryAttempted) return null;
        boolean recovering = top == null;
        top = knownTop;
        Card mystic = find("Mystic Forge", ZoneType.Battlefield);
        if (mystic != null && mystic.getStaticAbilities().stream().noneMatch(st -> !st.isSuppressed()
                && "True".equals(st.getParam("MayPlay"))
                && st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous))) mystic = null;
        ManaCostBeingPaid cost = recastCost(top, mystic != null);
        if (cost == null) return null;
        if (mystic != null && (partner = manaPartner(cost.getConvertedManaCost())) != null) route = "mana";
        else if ((cost = recastCost(top, false)) != null && refunded(cost) && (partner = find("The One Ring", ZoneType.Battlefield)) != null
                && partner.getCounters(forge.game.card.CounterEnumType.BURDEN) == 0) route = "ring";
        else if (cost != null && refunded(cost) && (partner = find("Narset, Parter of Veils", ZoneType.Battlefield)) != null) route = "narset";
        else return null;
        if (!returnsUntapped(top) || !returnsUntapped(partner) || !enough(top, recovering, need, castBudget)) return null;
        SpellAbility action;
        if (!recovering && top.isInZone(ZoneType.Battlefield)) {
            if (!"mana".equals(route)) {
                SpellAbility recovery = ability(partner, "ring".equals(route) ? ApiType.PutCounter : ApiType.Dig);
                if (!payable(recovery)) return null;
            }
            action = ability(top, ApiType.Draw);
        } else if (!recovering && top.isInZone(ZoneType.Hand)) action = cast(top, false);
        else if ("mana".equals(route)) {
            // The tracked Top is known from our earlier public activation. A
            // native look permission is still required before a library cast.
            if (player.getCardsIn(ZoneType.Library).isEmpty()) return null;
            Card visible = player.getCardsIn(ZoneType.Library).get(0);
            if (!visible.mayPlayerLook(player) || visible.isFaceDown() || visible.getId() != knownTop.getId()) return null;
            action = cast(visible, true);
        } else action = ability(partner, "ring".equals(route) ? ApiType.PutCounter : ApiType.Dig);
        if (!payable(action)) return null;
        active = true; selected = action;
        System.err.println("CUBE_TOP_KITTEN select route=" + route + " card=" + action.getHostCard().getName().replace(' ', '_')
                + " api=" + action.getApi() + " turn=" + turn);
        return action;
    }
    void played(SpellAbility sa, boolean success) {
        if (sa != selected) return;
        if (!success) { reset(); return; }
        if (sa.getHostCard() == knownTop && sa.getApi() == ApiType.Draw) recoveryNeeded = true;
        else if (sa.getHostCard() == partner && !sa.isSpell()) recoveryAttempted = true;
    }
    boolean chooseBlink(SpellAbility sa) {
        if (!active || turn != player.getGame().getPhaseHandler().getTurn() || selected == null || !selected.isSpell()
                || sa.getActivatingPlayer() != player || !KITTEN.equals(sa.getHostCard().getName())
                || sa.getApi() != ApiType.ChangeZone || !"Exile".equals(sa.getParam("Destination"))) return false;
        Object cause = sa.getRootAbility().getTriggeringObject(forge.game.ability.AbilityKey.SpellAbility);
        if (!(cause instanceof SpellAbility cast) || cast.getHostCard() != selected.getHostCard()
                || cast.getActivatingPlayer() != player || partner == null || !partner.isInPlay()
                || partner.getController() != player || !sa.canTarget(partner)) return false;
        sa.resetTargets(); sa.getTargets().add(partner);
        if (!sa.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(sa)) return false;
        System.err.println("CUBE_TOP_KITTEN blink route=" + route + " target=" + partner.getName().replace(' ', '_') + " turn=" + turn);
        return true;
    }
    boolean ownsRecovery(SpellAbility sa, Player target) {
        return active && recoveryNeeded && turn == player.getGame().getPhaseHandler().getTurn() && "narset".equals(route)
                && sa != null && sa.getApi() == ApiType.Dig && sa.getActivatingPlayer() == player
                && target == player && partner != null && sa.getHostCard().getId() == partner.getId()
                && "4".equals(sa.getParam("DigNum")) && "1".equals(sa.getParam("ChangeNum"));
    }
    Card knownTop() { return knownTop; }
    boolean isRecoveryCard(Card card) { return knownTop != null && card.getId() == knownTop.getId(); }
    boolean waitingForOwnSpell() {
        if (!active || turn != player.getGame().getPhaseHandler().getTurn() || player.getGame().getStack().isEmpty()) return false;
        var top = player.getGame().getStack().peekAbility();
        return top != null && top.getActivatingPlayer() == player && (KITTEN.equals(top.getHostCard().getName())
                || partner != null && top.getHostCard().getId() == partner.getId());
    }
}
