package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.cost.CostPartMana;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Current-turn Star finish, subordinate to established Recall/Gush routes.
 * The resource proposal is committed only by actual native play. */
final class CubeDoomStarPlan {
    private final Player player;
    private SpellAbility selected, pendingDoom;
    private List<CubeDoomStarResources.Payment> payments;
    private Card star, doom, land, oracleSnapshot;
    private int turn = -1, step, oracleId = -1;
    private boolean active, failed, subscribed, searched, ordered, filterPaid;
    private boolean castStar;

    CubeDoomStarPlan(Player player) { this.player = player; }
    boolean active() {
        if (turn != player.getGame().getPhaseHandler().getTurn()) return false;
        return active;
    }
    private void reset() {
        active = failed = searched = ordered = filterPaid = false;
        selected = pendingDoom = null; star = doom = land = oracleSnapshot = null;
        payments = null; oracleId = -1; step = 0;
        turn = player.getGame().getPhaseHandler().getTurn();
    }
    private SpellAbility stop() { failed = true; selected = null; return null; }
    private Card find(String name, ZoneType zone) {
        for (Card c : player.getCardsIn(zone)) if (!c.isFaceDown() && c.getOwner() == player
                && c.getController() == player && name.equals(c.getName())) return c;
        return null;
    }
    private Card current(Card card) { return card == null ? null : player.getGame().getCardState(card, null); }
    private boolean payable(SpellAbility a) {
        return a != null && CubeComboAi.canPlayNative(a, player)
                && (a.isLandAbility() || CubeComboAi.canPayCost(a, player, false));
    }
    private SpellAbility spell(Card card) {
        if (card == null || !card.isInZone(ZoneType.Hand)) return null;
        for (SpellAbility a : card.getSpellAbilities()) if (a.isSpell()) return a.copy(player);
        return null;
    }
    private boolean printedCost(SpellAbility a, int generic, String text) {
        if (a == null || a.getPayCosts() == null || a.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana))) return false;
        var cost = ComputerUtilMana.calculateManaCost(a.getPayCosts(), a, player, true, 0, false);
        return cost.getGenericManaAmount() == generic && cost.toString().equals(text);
    }
    private boolean staticDomain() {
        // Future Oracle is not inspected in the library. Unsupported future
        // spell costs/count limits are declined rather than simulated by moving
        // or constructing a hidden card. Native checks still apply at each step.
        for (ZoneType z : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card c : player.getGame().getCardsIn(z)) if (!c.isFaceDown()) {
                for (var a : c.getStaticAbilities()) if (a.zonesCheck()
                        && Set.of("RaiseCost", "ReduceCost", "SetCost", "CantBeCast", "CantBeActivated", "DisableTriggers").contains(a.getParamOrDefault("Mode", ""))) return false;
                for (var e : c.getReplacementEffects()) if (e.zonesCheck(c.getZone()) && e.requirementsCheck(player.getGame())
                        && Set.of("Draw", "LifeReduced", "ProduceMana").contains(e.getParamOrDefault("Event", ""))) return false;
            }
        return true;
    }
    private boolean untappedEntry(Card card) {
        for (ZoneType z : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card c : player.getGame().getCardsIn(z)) if (!c.isFaceDown())
                for (var e : c.getReplacementEffects()) if (e.zonesCheck(c.getZone()) && e.requirementsCheck(player.getGame())
                        && "Moved".equals(e.getParam("Event")) && "Battlefield".equals(e.getParam("Destination"))
                        && e.matchesValidParam("ValidCard", card)) return false;
        return true;
    }
    private boolean starDomain(Card card) {
        if (!"Chromatic Star".equals(card.getPaperCard().getName()) || card.isPhasedOut()
                || card.isInZone(ZoneType.Battlefield) && !card.canTap()) return false;
        boolean activation = false;
        for (var original : card.getManaAbilities()) {
            var a = original.copy(player);
            if (a.getManaPart() == null || !a.canProduce("U") || a.amountOfManaGenerated(false) != 1 || a.getSubAbility() != null) continue;
            var costs = forge.game.cost.CostAdjustment.adjust(a.getPayCosts(), a, false);
            if (costs == null || costs.getCostParts().stream().anyMatch(p -> !(p instanceof forge.game.cost.CostTap)
                    && !(p instanceof CostPartMana) && !(p instanceof forge.game.cost.CostSacrifice c && "CARDNAME".equals(c.getType()) && "1".equals(c.getAmount())))) continue;
            if (!"{1}".equals(ComputerUtilMana.calculateManaCost(a.getPayCosts(), a, player, true, 0, false).toString())) continue;
            if (card.isInZone(ZoneType.Battlefield) && !CubeComboAi.canPlayNative(a, player)) continue;
            activation = true;
        }
        if (!activation) return false;
        for (ZoneType z : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card c : player.getGame().getCardsIn(z)) if (!c.isFaceDown())
                for (var e : c.getReplacementEffects()) if (e.zonesCheck(c.getZone()) && e.requirementsCheck(player.getGame())
                        && "Moved".equals(e.getParam("Event")) && e.matchesValidParam("ValidCard", card)) return false;
        return true;
    }
    SpellAbility begin(SpellAbility doomSpell) {
        if (active()) return null;
        reset();
        if (!player.getManaPool().isEmpty() || player.getLife() <= 1 || !staticDomain()
                || find("Thassa's Oracle", ZoneType.Hand) != null || !printedCost(doomSpell, 0, "{B}{B}{B}")) return null;
        Card candidate = find("Chromatic Star", ZoneType.Battlefield);
        castStar = candidate == null;
        if (castStar) candidate = find("Chromatic Star", ZoneType.Hand);
        if (candidate == null || !untappedEntry(candidate) || !starDomain(candidate)) return null;
        if (castStar && (!printedCost(spell(candidate), 1, "{1}") || !payable(spell(candidate)))) return null;
        star = candidate; doom = doomSpell.getHostCard();
        List<String> demands = castStar ? List.of("1", "B", "B", "B", "1", "U") : List.of("B", "B", "B", "1", "U");
        payments = CubeDoomStarResources.assign(player, demands, Set.of(star));
        if (payments == null) for (Card c : player.getCardsIn(ZoneType.Hand)) {
            if (!c.isBasicLand() || c.isFaceDown() || !untappedEntry(c)) continue;
            for (SpellAbility original : c.getAllPossibleAbilities(player, false, null, true)) {
                SpellAbility a = original.copy(player);
                if (!a.isLandAbility() || !payable(a)) continue;
                var proposal = CubeDoomStarResources.assign(player, demands, Set.of(star), c);
                if (proposal != null) { payments = proposal; land = c; selected = a; return a; }
            }
        }
        if (payments == null) return null;
        step = castStar ? 1 : 3;
        return action();
    }
    private int paymentSlot() {
        int slot = switch (step) { case 1 -> 0; case 3 -> 1; case 4 -> 2; case 5 -> 3; case 7 -> 4; case 9 -> 5; default -> -1; };
        return slot < 0 ? -1 : slot - (castStar ? 0 : 1);
    }
    private SpellAbility mana(CubeDoomStarResources.Payment payment) {
        Card source = current(payment.source());
        if (source == null || !source.isInZone(ZoneType.Battlefield) || source.getController() != player) return null;
        for (SpellAbility original : source.getManaAbilities()) {
            SpellAbility a = original.copy(player);
            if (a.getSubAbility() != null || a.getManaPart() == null || !a.canProduce(payment.color())
                    || !a.getPayCosts().toString().equals(payment.ability().getPayCosts().toString())) continue;
            if (!"C".equals(payment.color())) a.setManaExpressChoice(ColorSet.fromMask(switch (payment.color()) { case "W" -> MagicColor.WHITE; case "U" -> MagicColor.BLUE; case "B" -> MagicColor.BLACK; case "R" -> MagicColor.RED; default -> MagicColor.GREEN; }));
            if (payable(a)) return a;
        }
        return null;
    }
    private SpellAbility action() {
        SpellAbility a = null;
        int slot = paymentSlot();
        if (slot >= 0) a = mana(payments.get(slot));
        else if (step == 2) a = spell(current(star));
        else if (step == 6) a = spell(current(doom));
        else if (step == 8) {
            Card c = current(star);
            if (c != null && c.isInZone(ZoneType.Battlefield) && c.getController() == player)
                for (SpellAbility original : c.getManaAbilities()) {
                    SpellAbility ability = original.copy(player);
                    if (ability.getManaPart() != null && ability.canProduce("U")) {
                        ability.setManaExpressChoice(ColorSet.fromMask(MagicColor.BLUE));
                        if (payable(ability)) { a = ability; break; }
                    }
                }
        } else if (step == 10) {
            Card c = current(oracleSnapshot);
            if (c != null && c.getId() == oracleId && c.isInZone(ZoneType.Hand)) a = spell(c);
        }
        if (!payable(a)) return stop();
        selected = a; return a;
    }
    SpellAbility nextAction(BooleanSupplier finishLegal) {
        if (!active() || failed || !finishLegal.getAsBoolean() || !staticDomain()) return stop();
        if (step == 1 || step == 3 && !castStar) {
            if (land != null && (current(land) == null || !current(land).isInZone(ZoneType.Battlefield))) return stop();
        }
        if (step >= 3 && (step <= 8) && (current(star) == null || !current(star).isInZone(ZoneType.Battlefield) || !starDomain(current(star)))) return stop();
        if (step >= 7 && (!searched || !ordered || player.getCardsIn(ZoneType.Library).size() != (step >= 9 ? 4 : 5))) return stop();
        if (step >= 9 && (current(oracleSnapshot) == null || !current(oracleSnapshot).isInZone(ZoneType.Hand))) return stop();
        if (step > 10) return stop();
        return action();
    }
    boolean waiting() {
        if (!active() || failed || player.getGame().getStack().isEmpty()) return false;
        var top = player.getGame().getStack().peekAbility();
        return top.getActivatingPlayer() == player && !top.isCopied()
                && (top == pendingDoom || star != null && top.getHostCard().getId() == star.getId()
                    || oracleId >= 0 && top.getHostCard().getId() == oracleId);
    }
    boolean owns(SpellAbility a) { return a != null && a == selected; }
    boolean play(SpellAbility a) {
        if (!owns(a) || !payable(a)) return false;
        if (!subscribed) { player.getGame().subscribeToEvents(this); subscribed = true; }
        active = true;
        if (step == 6) pendingDoom = a;
        if (step == 8) filterPaid = true;
        boolean success;
        if (a.isLandAbility()) { a.resolve(); success = current(land) != null && current(land).isInZone(ZoneType.Battlefield); }
        else success = ComputerUtil.handlePlayingSpellAbility(player, a, null, current -> new AiCostDecision(player, current, false));
        System.err.println("CUBE_DOOM_STAR step=" + step + " paid=" + success + " card=" + a.getHostCard().getName().replace(' ', '_'));
        if (!success) stop();
        else { step = step == 0 ? (castStar ? 1 : 3) : step + 1; selected = null; }
        return success;
    }
    boolean ownsSearch(SpellAbility source) {
        return active() && !failed && pendingDoom != null && source != null && !source.isCopied()
                && source.getActivatingPlayer() == player && source.getRootAbility() == pendingDoom
                && player.getGame().getStack().peekAbility() == pendingDoom;
    }
    Card choose(CardCollection choices) {
        for (Card c : choices) if (c.getOwner() != player || player.getGame().getCardState(c, null) != c) return null;
        if (!searched) for (Card c : choices) if ("Thassa's Oracle".equals(c.getName())) {
            searched = true; oracleId = c.getId(); oracleSnapshot = CardCopyService.getLKICopy(c); return c;
        }
        return choices.isEmpty() ? null : choices.get(0);
    }
    CardCollectionView order(CardCollectionView cards) {
        CardCollection out = new CardCollection();
        for (Card c : cards) if (c.getId() != oracleId) out.add(c);
        for (Card c : cards) if (c.getId() == oracleId) { out.add(c); ordered = true; }
        return out;
    }
    @Subscribe public void shuffled(GameEventShuffle event) { if (active() && event.player().getId() == player.getId()) failed = true; }
    @Subscribe public void moved(GameEventCardChangeZone event) {
        if (!active() || failed) return;
        boolean ownLibrary = false;
        for (var z : new forge.game.zone.ZoneView[]{event.from(), event.to()})
            if (z != null && z.zoneType() == ZoneType.Library && z.player() != null && z.player().getId() == player.getId()) ownLibrary = true;
        if (!ownLibrary) return;
        if (pendingDoom != null && player.getGame().getStack().peekAbility() == pendingDoom
                && player.getGame().getStack().isResolving(pendingDoom.getHostCard())) return;
        if (filterPaid && event.card().getId() == oracleId && event.to() != null && event.to().zoneType() == ZoneType.Hand) return;
        failed = true;
    }
}
