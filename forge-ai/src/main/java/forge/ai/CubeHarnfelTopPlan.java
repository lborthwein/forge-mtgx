package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.function.IntPredicate;

/** Finite Harnfel recurrence subordinate to the parent's native Reservoir finish.
 * Library identity/order is never read. A visible snapshot and actual owned
 * resolution/movement receipts carry knowledge between public zones. */
public final class CubeHarnfelTopPlan {
    private static final String TOP = "Sensei's Divining Top", HARNFEL = "Harnfel, Horn of Bounty";
    private final Player player;
    private Card visibleTop, harnfel;
    private SpellAbility selected, pending, lastDig;
    private int knownId = -1, libraryBefore, lifeBefore;
    private boolean subscribed, tracking, disrupted, failed, sawLibrary, sawExile;

    CubeHarnfelTopPlan(Player player) { this.player = player; }
    void reset() {
        visibleTop = harnfel = null; selected = pending = lastDig = null; knownId = -1;
        tracking = disrupted = failed = sawLibrary = sawExile = false;
    }
    Card forecastTop() { return visibleTop; }
    private boolean resolvingOwned() {
        var stack = player.getGame().getStack();
        return pending != null && pending.getActivatingPlayer() == player
                && stack.isResolving(pending.getHostCard()) && stack.peekAbility() == pending;
    }
    @Subscribe public void shuffled(GameEventShuffle event) {
        if (tracking && event.player().getId() == player.getId()) disrupted = true;
    }
    @Subscribe public void moved(GameEventCardChangeZone event) {
        if (!tracking || pending == null) return;
        boolean ownLibrary = false;
        for (var zone : new forge.game.zone.ZoneView[]{event.from(), event.to()})
            if (zone != null && zone.zoneType() == ZoneType.Library && zone.player() != null
                    && zone.player().getId() == player.getId()) ownLibrary = true;
        if (ownLibrary) {
            if (!resolvingOwned()) { disrupted = true; return; }
            if (event.card().getId() == knownId && event.to() != null) {
                if (pending.getApi() == ApiType.Draw && event.to().zoneType() == ZoneType.Library) sawLibrary = true;
                if (pending.getApi() == ApiType.Dig && event.to().zoneType() == ZoneType.Exile) sawExile = true;
            }
        }
        if (event.card().getId() == knownId && event.from() != null && event.from().zoneType() == ZoneType.Exile
                && !(pending.isSpell() && pending == selected && event.to() != null && event.to().zoneType() == ZoneType.Stack))
            disrupted = true;
    }
    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
            if (!card.isFaceDown() && card.getController() == player && name.equals(card.getName())) return card;
        return null;
    }
    private Card currentTop() {
        // Look up only the previously public object's identity and zone. Callers
        // must not inspect its face or copy it while it is in the library.
        return visibleTop == null ? null : player.getGame().getCardState(visibleTop, null);
    }
    private SpellAbility ability(Card card, ApiType api) {
        if (card != null) for (SpellAbility original : card.getSpellAbilities())
            if (original.isActivatedAbility() && original.getApi() == api) return original.copy(player);
        return null;
    }
    private boolean payable(SpellAbility sa) {
        return sa != null && CubeComboAi.canPlayNative(sa, player) && CubeComboAi.canPayCost(sa, player, false);
    }
    private boolean activationDomain(SpellAbility sa, boolean discard) {
        if (sa == null || sa.isSuppressed() || sa.getHostCard().isDetained()
                || !sa.getRestrictions().canPlay(sa.getHostCard(), sa)
                || !sa.isLegalAfterStack() || !sa.checkRestrictions(sa.getHostCard(), player)) return false;
        var adjusted = forge.game.cost.CostAdjustment.adjust(sa.getPayCosts(), sa, false);
        if (adjusted == null) return false;
        for (var part : adjusted.getCostParts()) {
            if (part instanceof CostPartMana) continue;
            if (!discard && part instanceof CostTap) continue;
            if (discard && part instanceof CostDiscard d && "1".equals(d.getAmount()) && "Card".equals(d.getType())) continue;
            return false;
        }
        var mana = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, player, true, 0, false);
        return mana.isPaid(); // Repeat activation taxes require a separate resource model.
    }
    private boolean safeDraws(int draws) {
        if (draws == 0) return true;
        if (!player.canDrawAmount(draws)) return false;
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card source : player.getGame().getCardsIn(zone)) {
                if (source.isFaceDown()) continue;
                for (var effect : source.getReplacementEffects())
                    if (effect.zonesCheck(source.getZone()) && effect.requirementsCheck(player.getGame())
                            && java.util.Set.of("Draw", "DrawCards").contains(effect.getParam("Event"))) return false;
            }
        return true;
    }
    private boolean enough(Card top, IntPredicate castBudget) {
        long life = player.getLife();
        int count = (int)player.getGame().getStack().getSpellsCastThisTurn().stream()
                .filter(sa -> sa.getActivatingPlayer() == player).count();
        int casts = 0;
        while (life <= 50 && casts < 100) { life += ++count; casts++; }
        if (life <= 50 || casts == 0) return false;
        boolean library = top.isInZone(ZoneType.Library);
        boolean immediate = library || top.isInZone(ZoneType.Hand) || top.isInZone(ZoneType.Exile);
        int size = player.getCardsIn(ZoneType.Library).size();
        int possible = library ? (size + 1) / 2 : (size + 1) / 2 + (immediate ? 1 : 0);
        int draws = casts - (immediate ? 1 : 0);
        if (casts > possible || !safeDraws(draws) || !castBudget.test(casts)) return false;
        Card forecast = CardCopyService.getLKICopy(visibleTop);
        forecast.setZone(player.getZone(ZoneType.Exile)); forecast.setCastFrom(player.getZone(ZoneType.Exile));
        forecast.setLastKnownZone(player.getGame().getStackZone());
        SpellAbility spell = forecast.getSpellPermanent().copy(player);
        var adjusted = forge.game.cost.CostAdjustment.adjust(spell.getPayCosts(), spell, false);
        if (adjusted == null || adjusted.getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana))) return false;
        var mana = ComputerUtilMana.calculateManaCost(spell.getPayCosts(), spell, player, true, 0, false);
        if (mana.getXcounter() != 0 || mana.getConvertedManaCost() != mana.getGenericManaAmount()) return false;
        var total = new ManaCostBeingPaid(mana);
        total.increaseGenericMana(mana.getGenericManaAmount() * (casts - 1));
        return CubeComboAi.canPayManaCost(total, spell, player, false);
    }
    private boolean permission(SpellAbility spell, Card card) {
        if (lastDig == null || !spell.isSpell() || spell.getActivatingPlayer() != player
                || spell.getHostCard() != card || spell.getMayPlay() == null || card.getOwner() != player
                || card.isFaceDown() || !card.isInZone(ZoneType.Exile)
                || player.getGame().getCardState(card, null) != card || card.getId() != knownId) return false;
        Card effect = spell.getMayPlay().getHostCard();
        return effect.getController() == player && effect.getEffectSource() == harnfel
                && effect.getEffectSourceAbility() != null
                && effect.getEffectSourceAbility().getRootAbility() == lastDig;
    }
    private SpellAbility cast(Card card) {
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility spell = original.copy(player);
            if (!spell.isSpell() || card.isInZone(ZoneType.Exile) && !permission(spell, card)) continue;
            if (payable(spell)) return spell;
        }
        return null;
    }
    private SpellAbility stop() { failed = true; tracking = false; pending = selected = null; return null; }
    SpellAbility nextAction(IntPredicate castBudget) {
        if (failed || disrupted) return stop();
        if (pending != null) {
            Card actual = currentTop();
            if (actual == null || actual.getOwner() != player) return stop();
            if (pending.getApi() == ApiType.Draw) {
                if (!sawLibrary || !actual.isInZone(ZoneType.Library)
                        || player.getCardsIn(ZoneType.Library).size() != libraryBefore) return stop();
            } else if (pending.getApi() == ApiType.Dig) {
                if (!sawExile || !actual.isInZone(ZoneType.Exile)) return stop();
                lastDig = pending;
            } else if (pending.isSpell() && (!actual.isInZone(ZoneType.Battlefield) || player.getLife() <= lifeBefore)) return stop();
            pending = null;
        }
        Card liveHarnfel = find(HARNFEL, ZoneType.Battlefield);
        if (liveHarnfel == null || liveHarnfel.isPhasedOut() || harnfel != null && harnfel != liveHarnfel) return tracking ? stop() : null;
        harnfel = liveHarnfel;
        SpellAbility dig = ability(harnfel, ApiType.Dig);
        if (dig == null || !"2".equals(dig.getParam("DigNum")) || !"Exile".equals(dig.getParam("DestinationZone"))
                || !"You".equals(dig.getParam("Defined")) || !activationDomain(dig, true)) return null;
        Card top = find(TOP, ZoneType.Battlefield);
        if (top == null) top = find(TOP, ZoneType.Hand);
        if (top != null) {
            if (!TOP.equals(top.getPaperCard().getName())) return null;
            visibleTop = CardCopyService.getLKICopy(top); knownId = top.getId();
        } else if (tracking) top = currentTop();
        if (top == null || !enough(top, castBudget)) return null;
        SpellAbility action;
        if (top.isInZone(ZoneType.Battlefield)) {
            action = ability(top, ApiType.Draw);
            if (!activationDomain(action, false)) return null;
        } else if (top.isInZone(ZoneType.Library)) {
            if (!tracking || !sawLibrary) return null;
            action = dig;
        } else if (top.isInZone(ZoneType.Hand) || top.isInZone(ZoneType.Exile)) action = cast(top);
        else return null;
        if (!payable(action)) return null;
        selected = action; return action;
    }
    void beforePlay(SpellAbility action) {
        if (action == null || action != selected) throw new IllegalStateException("Unowned Harnfel action");
        if (!subscribed) { player.getGame().subscribeToEvents(this); subscribed = true; }
        pending = action; tracking = true; sawLibrary = sawExile = false;
        libraryBefore = player.getCardsIn(ZoneType.Library).size(); lifeBefore = player.getLife();
    }
    void played(boolean success) {
        if (!success) { stop(); return; }
        // MagicStack creates a fresh instance for an activated ability and
        // records the exact selected original. Bind that actual stack object,
        // never an arbitrary ability with a matching host/name/id.
        if (selected.isActivatedAbility()) {
            SpellAbility actual = null;
            for (var entry : player.getGame().getStack()) {
                SpellAbility ability = entry.getSpellAbility();
                if (ability.getOriginalAbility() != selected || ability.isCopied()
                        || ability.getActivatingPlayer() != player) continue;
                if (actual != null) { stop(); return; }
                actual = ability;
            }
            if (actual == null) { stop(); return; }
            pending = actual;
        }
    }
}
