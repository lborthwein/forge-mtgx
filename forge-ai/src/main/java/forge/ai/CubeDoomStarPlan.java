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
    private SpellAbility selected, pendingDoom, paying, pendingSpell;
    private int starDrawTrigger = -1, oracleEnterTrigger = -1;
    private long starSacrificeStamp = -1;
    private List<Card> chosenPile = List.of();
    private List<CubeDoomStarResources.Payment> currentCost;
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
        selected = pendingDoom = paying = pendingSpell = null;
        starDrawTrigger = oracleEnterTrigger = -1; starSacrificeStamp = -1; currentCost = null; star = doom = land = oracleSnapshot = null;
        chosenPile = List.of();
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
                for (var trigger : c.getTriggers()) if (!trigger.isSuppressed()
                        && Set.of("Taps", "TapsForMana").contains(trigger.getMode().name())
                        && trigger.getParamOrDefault("TriggerZones", "Battlefield").contains(z.name())) return false;
                for (var a : c.getStaticAbilities()) if (a.zonesCheck()
                        && Set.of("RaiseCost", "ReduceCost", "SetCost", "CantBeCast", "CantBeActivated", "DisableTriggers").contains(a.getParamOrDefault("Mode", ""))) return false;
                for (var e : c.getReplacementEffects()) if (e.zonesCheck(c.getZone()) && e.requirementsCheck(player.getGame())
                        && Set.of("Draw", "LifeReduced", "ProduceMana", "DamageDone").contains(e.getParamOrDefault("Event", ""))) return false;
            }
        return true;
    }
    private boolean untappedEntry(Card card) {
        if (card.isLand() && card.isInZone(ZoneType.Hand)
                && (!card.getReplacementEffects().isEmpty() || !card.getTriggers().isEmpty() || !card.getStaticAbilities().isEmpty())) return false;
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
        if (!CubeDoomStarResources.safePool(player) || player.getLife() <= 1 || !staticDomain()
                || find("Thassa's Oracle", ZoneType.Hand) != null || !printedCost(doomSpell, 0, "{B}{B}{B}")) return null;
        Card candidate = find("Chromatic Star", ZoneType.Battlefield);
        castStar = candidate == null;
        if (castStar) candidate = find("Chromatic Star", ZoneType.Hand);
        if (candidate == null || !untappedEntry(candidate) || !starDomain(candidate)) return null;
        if (castStar && (!printedCost(spell(candidate), 1, "{1}") || !payable(spell(candidate)))) return null;
        star = candidate; doom = doomSpell.getHostCard();
        List<String> demands = castStar ? List.of("1", "B", "B", "B", "1", "U") : List.of("B", "B", "B", "1", "U");
        payments = CubeDoomStarResources.assign(player, demands, Set.of(star), null, player.getLife() / 2 - 1);
        if (payments == null) for (Card c : player.getCardsIn(ZoneType.Hand)) {
            if (!c.isLand() || c.isFaceDown() || !untappedEntry(c)) continue;
            for (SpellAbility original : c.getAllPossibleAbilities(player, false, null, true)) {
                SpellAbility a = original.copy(player);
                if (!a.isLandAbility() || !payable(a)) continue;
                var proposal = CubeDoomStarResources.assign(player, demands, Set.of(star), c, player.getLife() / 2 - 1);
                if (proposal != null) { payments = proposal; land = c; selected = a; return a; }
            }
        }
        if (payments == null) return null;
        step = castStar ? 1 : 3;
        return action();
    }
    private SpellAbility mana(CubeDoomStarResources.Payment payment) {
        Card source = current(payment.source());
        if (source == null || !source.isInZone(ZoneType.Battlefield) || source.getController() != player) return null;
        for (SpellAbility original : source.getManaAbilities()) {
            SpellAbility a = original.copy(player);
            if (CubeDoomStarResources.selfDamage(a) != payment.damage() || a.getManaPart() == null || !a.canProduce(payment.color())
                    || !a.getManaPart().getManaRestrictions().isEmpty() || !a.getManaPart().getExtraManaRestriction().isEmpty()
                    || a.amountOfManaGenerated(false) != 1 || payment.damage() >= player.getLife()
                    || !a.getPayCosts().toString().equals(payment.ability().getPayCosts().toString())) continue;
            if (!"C".equals(payment.color())) a.setManaExpressChoice(ColorSet.fromMask(switch (payment.color()) { case "W" -> MagicColor.WHITE; case "U" -> MagicColor.BLUE; case "B" -> MagicColor.BLACK; case "R" -> MagicColor.RED; default -> MagicColor.GREEN; }));
            if (payable(a)) return a;
        }
        return null;
    }
    private SpellAbility action() {
        int phase = step <= 2 ? 1 : step <= 6 ? 3 : step <= 8 ? 7 : 9;
        List<String> demands = phase == 1 ? List.of("1", "B", "B", "B", "1", "U")
                : phase == 3 ? List.of("B", "B", "B", "1", "U")
                : phase == 7 ? List.of("1", "U") : List.of("U", "U");
        int slots = phase == 3 ? 3 : phase == 9 ? 2 : 1;
        if (!CubeDoomStarResources.safePool(player)) return stop();
        // Rebuild from current public sources and actual floating mana. A
        // removed future source invalidates the finish before another resource
        // is spent, and equivalent floating units need no stale object binding.
        payments = CubeDoomStarResources.assign(player, demands, Set.of(star), null,
                (phase <= 3 ? player.getLife() / 2 : player.getLife()) - 1);
        if (payments == null) return stop();
        currentCost = List.copyOf(payments.subList(0, slots));
        for (int i = 0; i < slots; i++) if (currentCost.get(i).floating() == null) {
            SpellAbility mana = mana(currentCost.get(i));
            if (!payable(mana)) return stop();
            step = phase == 3 ? 3 + i : phase;
            selected = mana; return mana;
        }
        SpellAbility a = null;
        if (phase == 1) { step = 2; a = spell(current(star)); }
        else if (phase == 3) { step = 6; a = spell(current(doom)); }
        else if (phase == 7) {
            step = 8;
            Card c = current(star);
            if (c != null && c.isInZone(ZoneType.Battlefield) && c.getController() == player)
                for (SpellAbility original : c.getManaAbilities()) {
                    SpellAbility ability = original.copy(player);
                    if (ability.getManaPart() != null && ability.canProduce("U")) {
                        ability.setManaExpressChoice(ColorSet.fromMask(MagicColor.BLUE));
                        if (payable(ability)) { a = ability; break; }
                    }
                }
        } else {
            step = 10;
            Card c = current(oracleSnapshot);
            if (c != null && c.getId() == oracleId && c.isInZone(ZoneType.Hand)) a = spell(c);
        }
        if (!payable(a)) return stop();
        selected = a; return a;
    }
    forge.game.mana.Mana chooseMana(List<forge.game.mana.Mana> offered) {
        if (paying == null || currentCost == null || CubeComboAi.isPaymentProbeFor(player)) return null;
        for (var option : offered) for (var payment : currentCost)
            if (payment.floating() != null && payment.floating().equals(option)) {
                System.err.println("CUBE_DOOM_STAR_MANA phase=" + step + " nativeOffered=true color="
                        + MagicColor.toShortString(option.getColor()) + " choices=" + offered.size());
                return option;
            }
        return null;
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
        return !player.getGame().getStack().isEmpty() && waitingOn(player.getGame().getStack().peekAbility());
    }
    /** Only the actual stack entry may hold priority for this plan. The Star
     * trigger uses its sacrificed battlefield snapshot, not the newer graveyard
     * card; Oracle's enter trigger uses the actual current battlefield object. */
    boolean waitingOn(SpellAbility top) {
        if (!active() || failed || top == null || player.getGame().getStack().isEmpty()
                || player.getGame().getStack().peekAbility() != top
                || top.getActivatingPlayer() != player || top.isCopied()) return false;
        if (top == pendingDoom || top == pendingSpell) return true;
        if (!top.isWrapper() || !top.isTrigger()) return false;
        Object triggered = top.getTriggeringObject(forge.game.ability.AbilityKey.Card);
        if (!(triggered instanceof Card eventCard)) return false;
        Card host = top.getHostCard();
        if (filterPaid && step == 9 && star != null && top.getApi() == ApiType.Draw
                && top.getSourceTrigger() == starDrawTrigger && starDrawTrigger >= 0)
            return host.getId() == star.getId() && eventCard.getId() == star.getId()
                    && host.getGameTimestamp() == starSacrificeStamp
                    && eventCard.getGameTimestamp() == starSacrificeStamp;
        Card oracle = current(oracleSnapshot);
        return step == 11 && top.getApi() == ApiType.Dig && oracleEnterTrigger >= 0
                && top.getSourceTrigger() == oracleEnterTrigger && oracle != null
                && oracle.isInZone(ZoneType.Battlefield) && oracle.getController() == player
                && host == oracle && eventCard == oracle;
    }
    private int zoneTrigger(Card card, String origin, String destination, String execute) {
        int result = -1;
        for (var trigger : card.getTriggers()) if (trigger.isIntrinsic()
                && trigger.getMode() == forge.game.trigger.TriggerType.ChangesZone
                && origin.equals(trigger.getParam("Origin")) && destination.equals(trigger.getParam("Destination"))
                && "Card.Self".equals(trigger.getParam("ValidCard")) && execute.equals(trigger.getParam("Execute"))) {
            if (result >= 0) return -1;
            result = trigger.getId();
        }
        return result;
    }
    boolean owns(SpellAbility a) { return a != null && a == selected; }
    boolean play(SpellAbility a) {
        if (!owns(a) || !payable(a)) return false;
        if (!subscribed) { player.getGame().subscribeToEvents(this); subscribed = true; }
        active = true;
        if (step == 6) pendingDoom = a;
        if (a.isSpell()) pendingSpell = a;
        if (step == 8) {
            filterPaid = true;
            starSacrificeStamp = a.getHostCard().getGameTimestamp();
            starDrawTrigger = zoneTrigger(a.getHostCard(), "Battlefield", "Graveyard", "TrigDraw");
        }
        boolean success;
        if (a.isLandAbility()) { a.resolve(); success = current(land) != null && current(land).isInZone(ZoneType.Battlefield); }
        else {
            paying = a;
            try { success = ComputerUtil.handlePlayingSpellAbility(player, a, null, current -> new AiCostDecision(player, current, false)); }
            finally { paying = null; }
        }
        // Casting moves Oracle from hand to stack and creates the trigger
        // identity used by its later native battlefield object. Capture after
        // that successful native move, not the older hand-card trigger.
        if (success && step == 10) oracleEnterTrigger = zoneTrigger(a.getHostCard(), "Any", "Battlefield", "TrigDig");
        System.err.println("CUBE_DOOM_STAR step=" + step + " paid=" + success + " card=" + a.getHostCard().getName().replace(' ', '_'));
        if (!success) stop();
        else {
            if (step == 0) step = castStar ? 1 : 3;
            else if (!a.isManaAbility() || step == 8) step++;
            selected = null; currentCost = null;
        }
        return success;
    }
    boolean ownsSearch(SpellAbility source) {
        if (!active() || failed || pendingDoom == null || source == null || source.isCopied()
                || source.getActivatingPlayer() != player || source.getRootAbility() != pendingDoom
                || player.getGame().getStack().isEmpty() || player.getGame().getStack().peekAbility() != pendingDoom) return false;
        // A copied subability may keep its original parent/root without being
        // marked as a copied game object. Only the actual native chain grants
        // this search; equal host, root, parameters or ids are insufficient.
        for (SpellAbility member = pendingDoom; member != null; member = member.getSubAbility())
            if (member == source) return true;
        return false;
    }
    private boolean canonicalSearchCard(Card card) {
        return card != null && card.getOwner() == player
                && player.getGame().getCardState(card, null) == card
                && (card.isInZone(ZoneType.Library) || card.isInZone(ZoneType.Graveyard));
    }
    Card choose(CardCollection choices) {
        if (failed || !active() || chosenPile.size() >= 5) return null;
        for (Card c : choices) if (!canonicalSearchCard(c)
                || chosenPile.stream().anyMatch(prior -> prior == c)) { stop(); return null; }
        Card picked = null;
        if (!searched) for (Card c : choices) if ("Thassa's Oracle".equals(c.getName())) { picked = c; break; }
        if (!searched && picked == null) { stop(); return null; }
        if (picked == null && !choices.isEmpty()) picked = choices.get(0);
        if (picked == null) { stop(); return null; }
        if (!searched) { searched = true; oracleId = picked.getId(); oracleSnapshot = CardCopyService.getLKICopy(picked); }
        var next = new java.util.ArrayList<>(chosenPile); next.add(picked); chosenPile = List.copyOf(next);
        return picked;
    }
    CardCollectionView order(CardCollectionView cards) {
        if (failed || !active() || !searched || cards.size() != 5 || chosenPile.size() != 5
                || cards.stream().anyMatch(c -> !canonicalSearchCard(c)
                    || chosenPile.stream().noneMatch(picked -> picked == c))
                || cards.stream().filter(c -> c.getId() == oracleId).count() != 1) {
            stop(); return cards;
        }
        CardCollection out = new CardCollection();
        for (Card c : cards) if (c.getId() != oracleId) out.add(c);
        for (Card c : cards) if (c.getId() == oracleId) out.add(c);
        ordered = true;
        System.err.println("CUBE_DOOM_STAR_PILE selected=5 canonical=true exactSelected=true oracleLast=true");
        return out;
    }
    @Subscribe public void shuffled(GameEventShuffle event) { if (active() && event.player().getId() == player.getId()) failed = true; }
    @Subscribe public void moved(GameEventCardChangeZone event) {
        if (!active() || failed) return;
        boolean ownLibrary = false;
        for (var z : new forge.game.zone.ZoneView[]{event.from(), event.to()})
            if (z != null && z.zoneType() == ZoneType.Library && z.player() != null && z.player().getId() == player.getId()) ownLibrary = true;
        if (!ownLibrary) return;
        if (pendingDoom != null && !player.getGame().getStack().isEmpty() && player.getGame().getStack().peekAbility() == pendingDoom
                && player.getGame().getStack().isResolving(pendingDoom.getHostCard())) return;
        if (filterPaid && event.card().getId() == oracleId && event.to() != null && event.to().zoneType() == ZoneType.Hand) return;
        failed = true;
    }
}
