package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

/** Finite, own-visible Emry/Kitten recurrence towards a live Reservoir finish.
 * Every cast permission, equipment move and sacrifice is executed by Forge. */
final class CubeEmryPlan {
    private static final String EMRY = "Emry, Lurker of the Loch", KITTEN = "Displacer Kitten";
    private final Player player;
    private int turn = -1, actions, failedTurn = -1;
    private SpellAbility selected;
    private Card blinkPartner;
    private long blinkTimestamp;
    private boolean pendingCast;
    private int lifeBeforeCast;
    CubeEmryPlan(Player player) { this.player = player; }
    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
            if (!card.isFaceDown() && !card.isPhasedOut() && name.equals(card.getName())) return card;
        return null;
    }
    private SpellAbility ability(Card card, ApiType api) {
        if (card != null) for (SpellAbility sa : card.getSpellAbilities())
            if (sa.getApi() == api) return sa.copy(player);
        return null;
    }
    private boolean payable(SpellAbility sa) {
        return sa != null && CubeComboAi.canPlayNative(sa, player)
                && sa.getPayCosts() != null && CubeComboAi.canPayCost(sa, player, false);
    }
    private SpellAbility target(SpellAbility sa, forge.game.GameEntity target) {
        if (sa == null || !sa.canTarget(target)) return null;
        sa.resetTargets(); sa.getTargets().add(target);
        return sa.isTargetNumberValid() && StaticAbilityMustTarget.meetsMustTargetRestriction(sa) ? sa : null;
    }
    private SpellAbility cast(Card card) {
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility sa = original.copy(player);
            if (sa.isSpell() && payable(sa)) return sa;
        }
        return null;
    }
    private SpellAbility select(SpellAbility sa) {
        if (!payable(sa)) return null;
        selected = sa; blinkPartner = null; actions++;
        return sa;
    }
    SpellAbility nextAction() {
        var game = player.getGame(); var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) { turn = phase.getTurn(); actions = 0; selected = null; pendingCast = false; }
        if (failedTurn == turn || actions >= 128 || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return null;
        if (pendingCast) {
            pendingCast = false;
            if (player.getLife() <= lifeBeforeCast) {
                failedTurn = turn;
                System.err.println("CUBE_EMRY_PLAN stopped-no-life-progress turn=" + turn);
                return null;
            }
        }
        Card emry = find(EMRY, ZoneType.Battlefield), kitten = find(KITTEN, ZoneType.Battlefield);
        Card reservoir = find("Aetherflux Reservoir", ZoneType.Battlefield);
        if (emry == null || kitten == null || reservoir == null
                || kitten.getTriggers().stream().noneMatch(t -> !t.isSuppressed() && "SpellCast".equals(t.getParam("Mode")))) return null;
        Card greaves = find("Lightning Greaves", ZoneType.Battlefield), partner = greaves;
        if (partner == null) for (String name : new String[]{"Pestermite", "Deceiver Exarch", "Zealous Conscripts"}) {
            partner = find(name, ZoneType.Battlefield); if (partner != null) break;
        }
        if (partner == null) return null;
        Player opponent = player.getOpponents().get(0);
        SpellAbility shot = target(ability(reservoir, ApiType.DealDamage), opponent);
        if (shot == null || opponent.getLife() <= 0 || opponent.cantLoseForZeroOrLessLife() || !opponent.canLoseLife()
                || !"50".equals(shot.getParam("NumDmg"))
                || ComputerUtilCombat.predictDamageTo(opponent, 50, reservoir, false) < opponent.getLife()
                || shot.isSuppressed() || reservoir.isDetained()
                || !shot.getRestrictions().canPlay(reservoir, shot) || !shot.isLegalAfterStack()
                || !shot.checkRestrictions(reservoir, player)) return null;
        if (player.getLife() > 50 && payable(shot)) return select(shot);
        if (!knownLifeGainWorks(reservoir) || reservoir.getTriggers().stream().noneMatch(t -> !t.isSuppressed()
                && "SpellCast".equals(t.getParam("Mode")))) return null;
        for (String name : new String[]{"Lotus Petal", "Mishra's Bauble", "Lion's Eye Diamond"}) {
            Card artifact = find(name, ZoneType.Graveyard);
            if (artifact != null) {
                SpellAbility spell = cast(artifact);
                if (spell != null) {
                    if (greaves != null && greaves.getAttachedTo() == emry)
                        return select(target(ability(greaves, ApiType.Attach), kitten));
                    return select(spell);
                }
                SpellAbility permission = target(ability(emry, ApiType.Effect), artifact);
                if (payable(permission)) return select(permission);
                if (greaves != null && emry.isSick() && !emry.isTapped() && greaves.getAttachedTo() != emry)
                    return select(target(ability(greaves, ApiType.Attach), emry));
            }
            artifact = find(name, ZoneType.Battlefield);
            if (artifact == null) continue;
            // Do not discard a held spell for an unproved resource loop. The
            // initial supported LED route has its engine and terminal in play.
            if ("Lion's Eye Diamond".equals(name) && player.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> !c.isLand())) continue;
            SpellAbility sacrifice = ability(artifact, "Mishra's Bauble".equals(name) ? ApiType.PeekAndReveal : ApiType.Mana);
            if ("Mishra's Bauble".equals(name)) sacrifice = target(sacrifice, player);
            if (sacrifice != null && sacrifice.getManaPart() != null && sacrifice.getManaPart().isAnyMana())
                sacrifice.getManaPart().setExpressChoice("U");
            if (payable(sacrifice)) return select(sacrifice);
        }
        return null;
    }
    /** Do not traverse hidden zones to forecast life-gain replacements. */
    private boolean knownLifeGainWorks(Card reservoir) {
        if (!player.canGainLife()) return false;
        var params = forge.game.ability.AbilityKey.mapFromAffected(player);
        params.put(forge.game.ability.AbilityKey.LifeGained, 1);
        params.put(forge.game.ability.AbilityKey.Source, reservoir);
        for (ZoneType zone : new ZoneType[]{ZoneType.Battlefield, ZoneType.Command})
            for (Card source : player.getGame().getCardsIn(zone)) {
                if (source.isFaceDown()) continue;
                for (var re : source.getReplacementEffects()) {
                    if (java.util.Set.of("NoLife", "LoseLife", "LichDraw").contains(re.getParamOrDefault("AILogic", ""))
                            && re.modeCheck(forge.game.replacement.ReplacementType.GainLife, params)
                            && re.zonesCheck(source.getZone()) && re.requirementsCheck(player.getGame()) && re.canReplace(params)) return false;
                }
            }
        return true;
    }
    boolean chooseBlink(SpellAbility sa) {
        if (selected == null || !selected.isSpell() || turn != player.getGame().getPhaseHandler().getTurn()
                || sa.getActivatingPlayer() != player || !KITTEN.equals(sa.getHostCard().getName())
                || sa.getApi() != ApiType.ChangeZone || !"Exile".equals(sa.getParam("Destination"))) return false;
        Object cause = sa.getRootAbility().getTriggeringObject(forge.game.ability.AbilityKey.SpellAbility);
        if (!(cause instanceof SpellAbility cast) || cast.getActivatingPlayer() != player
                || cast.getHostCard() != selected.getHostCard()) return false;
        Card partner = find("Lightning Greaves", ZoneType.Battlefield) == null ? null : find(EMRY, ZoneType.Battlefield);
        if (partner == null) for (String name : new String[]{"Pestermite", "Deceiver Exarch", "Zealous Conscripts"}) {
            partner = find(name, ZoneType.Battlefield); if (partner != null) break;
        }
        if (partner == null || target(sa, partner) == null) return false;
        blinkPartner = partner; blinkTimestamp = partner.getGameTimestamp();
        return true;
    }
    Card untapSource(SpellAbility sa) {
        if (selected == null || !selected.isSpell() || blinkPartner == null
                || turn != player.getGame().getPhaseHandler().getTurn() || sa.getActivatingPlayer() != player
                || sa.getHostCard().getController() != player || sa.getHostCard().getId() != blinkPartner.getId()
                || sa.getHostCard().getGameTimestamp() == blinkTimestamp
                || !(sa.getApi() == ApiType.TapOrUntap || sa.getApi() == ApiType.Untap
                    || sa.getApi() == ApiType.GainControl && sa.hasParam("Untap"))) return null;
        Card emry = find(EMRY, ZoneType.Battlefield);
        return emry != null && emry.isTapped() && emry.canUntap(null, true) && sa.canTarget(emry) ? emry : null;
    }
    boolean owns(SpellAbility sa) { return sa == selected; }
    boolean play(SpellAbility sa) {
        int life = player.getLife();
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, sa, null,
                current -> new AiCostDecision(player, current, false));
        if (!played) failedTurn = turn;
        if (played && sa.isSpell()) { pendingCast = true; lifeBeforeCast = life; }
        System.err.println("CUBE_EMRY_PLAN " + (played ? "played" : "native-payment-failed")
                + " turn=" + turn + " card=" + sa.getHostCard().getName().replace(' ', '_')
                + " api=" + sa.getApi() + " actions=" + actions);
        return played;
    }
    boolean waitingForOwnSpell() {
        var stack = player.getGame().getStack();
        if (selected == null || turn != player.getGame().getPhaseHandler().getTurn() || stack.isEmpty()) return false;
        var top = stack.peekAbility();
        return top != null && top.getActivatingPlayer() == player && (top.getHostCard() == selected.getHostCard()
                || KITTEN.equals(top.getHostCard().getName()) || EMRY.equals(top.getHostCard().getName()));
    }
}
