package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.cost.CostPartMana;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Restore the rebound blink, rather than consuming the last extra-turn spell,
 * when our own Witness-style ETB can keep the two-spell recurrence running.
 * This is a preference over native legal targets, not a scripted cast or an
 * assertion of infinity. Every actual cast/trigger still belongs to native AI.
 * Reads only our visible cards, public cast history and public restrictions. */
public final class CubeExtraTurnPlan {
    private CubeExtraTurnPlan() {}

    private static SpellAbility castPreview(Card card, Player player) {
        Card preview = CardCopyService.getLKICopy(card);
        preview.setLastKnownZone(player.getGame().getStackZone());
        // CostAdjustment expects a prospective stack card. Keeping the LKI
        // on the stack also prevents calculateManaCost from replacing our
        // projected hand origin with the detached copy's null current zone.
        preview.setCastFrom(player.getZone(ZoneType.Hand));
        for (SpellAbility original : card.getSpellAbilities()) {
            if (!original.isSpell()) continue;
            SpellAbility spell = original.copy(player);
            spell.setHostCard(preview);
            if (spell.getPayCosts() == null || spell.getPayCosts().hasXInAnyCostPart()
                    || spell.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana))) continue;
            return spell;
        }
        return null;
    }

    private static boolean staticLegal(SpellAbility spell, Player player) {
        Card stack = CardCopyService.getLKICopy(spell.getHostCard());
        stack.setLastKnownZone(player.getGame().getStackZone());
        stack.setCastFrom(player.getZone(ZoneType.Hand));
        // Deliberately no canPlay(): the sorcery is for our following main
        // phase. Restrictions are checked on a detached prospective-stack card.
        return spell.checkRestrictions(stack, player);
    }

    private static boolean reboundBlink(SpellAbility spell, Card partner) {
        if (spell == null || !spell.getHostCard().hasKeyword(Keyword.REBOUND)
                || spell.getApi() != ApiType.ChangeZone || !"Battlefield".equals(spell.getParam("Origin"))
                || !"Exile".equals(spell.getParam("Destination")) || spell.getMinTargets() != 1
                || spell.getMaxTargets() != 1 || !spell.canTarget(partner)) return false;
        SpellAbility back = spell.getSubAbility();
        if (back == null || back.getApi() != ApiType.ChangeZone || !"Exile".equals(back.getParam("Origin"))
                || !"Battlefield".equals(back.getParam("Destination")) || !"Remembered".equals(back.getParam("Defined"))) return false;
        SpellAbility cleanup = back.getSubAbility();
        return cleanup == null || cleanup.getApi() == ApiType.Cleanup && cleanup.getSubAbility() == null;
    }

    private static boolean extraTurn(SpellAbility spell, Player player) {
        if (spell == null || spell.getApi() != ApiType.AddTurn || !"1".equals(spell.getParam("NumTurns"))
                || spell.getSubAbility() != null) return false;
        if (!spell.usesTargeting()) return !spell.hasParam("Defined") || "You".equals(spell.getParam("Defined"));
        if (spell.getMinTargets() != 1 || spell.getMaxTargets() != 1 || !spell.canTarget(player)) return false;
        spell.resetTargets(); spell.getTargets().add(player);
        return spell.isTargetNumberValid();
    }

    public static Card preferRecurrence(Player player, SpellAbility trigger, CardCollectionView choices, Card ordinary) {
        if (!CubeComboAi.enabled(player) || ordinary == null || choices.size() < 2
                || trigger.getActivatingPlayer() != player || !trigger.isTrigger()
                || trigger.getApi() != ApiType.ChangeZone || trigger.getSubAbility() != null
                || !"Graveyard".equals(trigger.getParam("Origin")) || !"Hand".equals(trigger.getParam("Destination"))
                || trigger.getMinTargets() != 1 || trigger.getMaxTargets() != 1
                || player.getGame().getPhaseHandler().getPlayerTurn() != player
                || player.getGame().getPhaseHandler().getPhase() != PhaseType.UPKEEP) return null;
        Card partner = trigger.getHostCard();
        var origin = trigger.getTrigger();
        if (!partner.isCreature() || !partner.isInPlay() || partner.getController() != player
                || partner.isFaceDown() || partner.isPhasedOut()
                || !"ChangesZone".equals(origin.getParam("Mode")) || !"Battlefield".equals(origin.getParam("Destination"))
                || !"Card.Self".equals(origin.getParam("ValidCard"))) return null;
        for (Card blink : choices) {
            if (blink == ordinary || blink.getOwner() != player || !blink.isInZone(ZoneType.Graveyard)
                    || !trigger.canTarget(blink)) continue;
            // Zone changes clear castFrom on the live graveyard card. The
            // public cast-history LKI preserves the actual rebound origin.
            boolean castNow = player.getGame().getStack().getSpellCardsCastThisTurn().stream()
                    .anyMatch(card -> card.getId() == blink.getId() && card.getController() == player
                            && card.getCastFrom() != null && card.getCastFrom().getZoneType() == ZoneType.Exile);
            if (!castNow) continue;
            SpellAbility first = castPreview(blink, player);
            if (!reboundBlink(first, partner) || !staticLegal(first, player)) continue;
            for (ZoneType zone : new ZoneType[] {ZoneType.Hand, ZoneType.Graveyard}) {
                for (Card card : player.getCardsIn(zone)) {
                    SpellAbility second = castPreview(card, player);
                    if (!extraTurn(second, player) || !staticLegal(second, player)
                            || !CubeComboAi.castFitsAfter(player, first, second)) continue;
                    ManaCostBeingPaid combined = ComputerUtilMana.calculateManaCost(first.getPayCosts(), first, player, true, 0, false);
                    ManaCostBeingPaid later = ComputerUtilMana.calculateManaCost(second.getPayCosts(), second, player, true, 0, false);
                    if (combined.getXcounter() != 0 || later.getXcounter() != 0) continue;
                    combined.addManaCost(later.toManaCost());
                    // Check the total against both spell restrictions; this is
                    // deliberately conservative for restricted mana. One joint
                    // cost avoids double-counting floating mana or a source.
                    if (!CubeComboAi.canPayManaCost(combined, first, player, false)
                            || !CubeComboAi.canPayManaCost(combined, second, player, false)) continue;
                    System.out.println("CUBE_EXTRA_TURN return=" + blink.getName().replace(' ', '_')
                            + " instead=" + ordinary.getName().replace(' ', '_') + " source=" + partner.getName().replace(' ', '_')
                            + " turn=" + player.getGame().getPhaseHandler().getTurn());
                    return blink;
                }
            }
        }
        return null;
    }
}
