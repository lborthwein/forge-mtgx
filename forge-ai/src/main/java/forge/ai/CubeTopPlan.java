package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPayLife;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

/** Finite Top/Citadel-or-Mystic/Reservoir turn plan. Every draw, cast, payment,
 * trigger and shot is a native action with ordinary opposing priority.
 * Only a natively viewable top card may be inspected, never the hidden tail. */
public final class CubeTopPlan {
    private static final String TOP = "Sensei's Divining Top", CITADEL = "Bolas's Citadel",
            MYSTIC = "Mystic Forge", BIRGI = "Birgi, God of Storytelling", RESERVOIR = "Aetherflux Reservoir";
    private final Player player;
    private int turn = -1, actions, failedTurn = -1;
    private SpellAbility selected, pending;
    private int libraryBefore, lifeBefore, opposingLifeBefore;

    public CubeTopPlan(Player player) { this.player = player; }

    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
            if (!card.isFaceDown() && name.equals(card.getName())) return card;
        return null;
    }

    private SpellAbility ability(Card card, ApiType api) {
        if (card == null) return null;
        for (SpellAbility original : card.getSpellAbilities())
            if (original.getApi() == api) return original.copy(player);
        return null;
    }

    private boolean payable(SpellAbility sa) {
        return sa != null && CubeComboAi.canPlayNative(sa, player)
                && CubeComboAi.canPayCost(sa, player, false);
    }

    private SpellAbility cast(Card card, boolean fromLibrary, String permission) {
        if (card == null) return null;
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility sa = original.copy(player);
            if (!sa.isSpell()) continue;
            // Select an actual native permission/alternative, not a base
            // spell copied into an unauthorized zone or stripped of costs.
            if (fromLibrary && (sa.getMayPlay() == null
                    || !permission.equals(sa.getMayPlay().getHostCard().getName())
                    || CITADEL.equals(permission) != sa.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostPayLife))) continue;
            if (payable(sa)) return sa;
        }
        return null;
    }

    private SpellAbility select(SpellAbility sa) {
        selected = sa; actions++; return sa;
    }

    /** Count-only reach estimate, not a claim of a forced win. Real triggers,
     * replacements and responses must still resolve; failures stop the plan. */
    private boolean enoughDraws(Card top, int draws, boolean immediateRecast, String permission) {
        int lifeCost = CITADEL.equals(permission) ? 1 : 0;
        if (lifeCost == 0 && !sustainableManaRecast(top)) return false;
        long life = player.getLife();
        // Reservoir counts spells we cast, unlike storm's all-player count.
        int storm = (int) player.getGame().getStack().getSpellsCastThisTurn().stream()
                .filter(sa -> sa.getActivatingPlayer() == player).count();
        for (int i = 0; i < Math.min(100, draws + (immediateRecast ? 1 : 0)); i++) {
            if (life <= lifeCost) return false;
            life += ++storm - lifeCost;
            if (life > 50) return player.canDrawAmount(i + 1 - (immediateRecast ? 1 : 0))
                    && castBudgetFits(top, i + 1);
        }
        return false;
    }

    /** Forecast only the repeat payment, not permission to cast this object.
     * A detached LKI host supplies the future library origin to native cost
     * adjustment. It is never inserted into a zone or offered as an action.
     * Actual MayPlay permission is checked when Top is visibly on top.
     * Non-mana additional costs and a positive net mana drain need a richer
     * finite-resource plan; don't pretend those are repeatable for free. */
    private boolean sustainableManaRecast(Card top) {
        Card forecast = forge.game.card.CardCopyService.getLKICopy(top);
        forecast.setZone(player.getZone(ZoneType.Library));
        forecast.setCastFrom(player.getZone(ZoneType.Library));
        SpellAbility spell = forecast.getSpellPermanent().copy(player);
        var adjusted = forge.game.cost.CostAdjustment.adjust(spell.getPayCosts(), spell, false);
        if (adjusted == null || adjusted.getCostParts().stream().anyMatch(p -> !(p instanceof forge.game.cost.CostPartMana))) return false;
        var mana = ComputerUtilMana.calculateManaCost(spell.getPayCosts(), spell, player, true, 0, false);
        if (!CubeComboAi.canPayManaCost(mana, spell, player, false)) return false;
        if (mana.isPaid()) return true;
        Card birgi = find(BIRGI, ZoneType.Battlefield);
        return mana.getXcounter() == 0 && mana.getGenericManaAmount() == 1 && mana.getConvertedManaCost() == 1
                && birgi != null && birgi.getTriggers().stream().anyMatch(t -> !t.isSuppressed()
                    && "SpellCast".equals(t.getParam("Mode")) && "You".equals(t.getParam("ValidActivatingPlayer")));
    }

    /** Recognize unconditional public per-turn cast caps using Forge's own
     * validity/count queries. Conditional caps still face native legality at
     * every actual cast; this is not a general future-state rules evaluator. */
    private boolean castBudgetFits(Card top, int casts) {
        Card prospective = forge.game.card.CardCopyService.getLKICopy(top);
        prospective.setLastKnownZone(player.getGame().getStackZone());
        for (Card source : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (source.isFaceDown()) continue;
            for (var st : source.getStaticAbilities()) {
                if (!st.hasParam("NumLimitEachTurn")
                        || !java.util.Set.of("Mode", "ValidCard", "Caster", "NumLimitEachTurn", "Description")
                            .containsAll(st.getMapParams().keySet())
                        || !st.checkConditions(forge.game.staticability.StaticAbilityMode.CantBeCast)
                        || !st.matchesValidParam("ValidCard", prospective) || !st.matchesValidParam("Caster", player)
                        || st.getIgnoreEffectPlayers().contains(player)) continue;
                int used = forge.game.card.CardLists.filterControlledByAsList(
                        forge.game.card.CardUtil.getThisTurnCast(st.getParamOrDefault("ValidCard", "Card"), prospective, st, player), player).size();
                if ((long) used + casts > Integer.parseInt(st.getParam("NumLimitEachTurn"))) return false;
            }
        }
        return true;
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

    public SpellAbility nextAction() {
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) {
            turn = phase.getTurn(); actions = 0; selected = null; pending = null;
        }
        if (failedTurn == turn || actions >= 200 || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return null;
        Player opponent = player.getOpponents().get(0);
        if (pending != null) {
            boolean stalled = pending.getApi() == ApiType.Draw
                    && (find(TOP, ZoneType.Battlefield) != null
                        || player.getCardsIn(ZoneType.Library).size() != libraryBefore)
                    || pending.isSpell() && (find(TOP, ZoneType.Battlefield) == null
                        || player.getLife() < lifeBefore)
                    || pending.getApi() == ApiType.DealDamage && opponent.getLife() >= opposingLifeBefore;
            pending = null;
            if (stalled) {
                failedTurn = turn;
                System.err.println("CUBE_TOP_PLAN stopped-no-progress turn=" + turn);
                return null;
            }
        }
        Card reservoir = find(RESERVOIR, ZoneType.Battlefield);
        SpellAbility shot = ability(reservoir, ApiType.DealDamage);
        if (shot == null || opponent.getLife() <= 0 || opponent.getLife() > 50
                || opponent.cantLoseForZeroOrLessLife() || !opponent.canLoseLife() || !shot.canTarget(opponent)) return null;
        shot.resetTargets(); shot.getTargets().add(opponent);
        // Forecast only activation restrictions here: canPlay() also checks
        // the fifty-life payment that this plan has not accumulated yet.
        // Full native legality and affordability are required for the shot.
        if (!shot.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(shot)
                || shot.isSuppressed() || reservoir.isDetained()
                || !shot.getRestrictions().canPlay(reservoir, shot)
                || !shot.isLegalAfterStack() || !shot.checkRestrictions(reservoir, player)) return null;
        if (player.getLife() > 50 && payable(shot)) return select(shot);
        if (!knownLifeGainWorks(reservoir)) return null;
        // A native zero-cost/refunded recast preserves life for the finish.
        // This is a preference among feasible complete-loop plans, not a
        // blanket Mystic gate: an absent/suppressed reducer or unpaid seed
        // still falls through to the life-paid Citadel route.
        for (String permission : java.util.List.of(MYSTIC, CITADEL)) {
            Card engine = find(permission, ZoneType.Battlefield);
            if (engine == null || engine.getStaticAbilities().stream().noneMatch(st -> st.hasParam("MayPlay")
                    && !st.isSuppressed() && st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous))) continue;
            SpellAbility next = nextLoopAction(permission);
            if (next != null) return select(next);
        }
        return null;
    }

    private SpellAbility nextLoopAction(String permission) {
        Card top = find(TOP, ZoneType.Battlefield);
        int librarySize = player.getCardsIn(ZoneType.Library).size();
        if (top != null) {
            SpellAbility draw = ability(top, ApiType.Draw);
            if (librarySize > 0 && enoughDraws(top, librarySize, false, permission) && payable(draw)) return draw;
            return null;
        }
        top = find(TOP, ZoneType.Hand);
        if (top != null && enoughDraws(top, librarySize, true, permission)) {
            SpellAbility spell = cast(top, false, permission);
            if (spell != null) return spell;
        }
        if (librarySize == 0) return null;
        // Taking the top object is not permission to inspect its identity.
        Card visibleTop = player.getCardsIn(ZoneType.Library).get(0);
        if (!visibleTop.mayPlayerLook(player) || visibleTop.isFaceDown()
                || !TOP.equals(visibleTop.getName()) || !enoughDraws(visibleTop, librarySize - 1, true, permission)) return null;
        return cast(visibleTop, true, permission);
    }

    public boolean owns(SpellAbility sa) { return sa == selected; }

    public boolean play(SpellAbility sa) {
        libraryBefore = player.getCardsIn(ZoneType.Library).size();
        lifeBefore = player.getLife();
        opposingLifeBefore = player.getOpponents().get(0).getLife();
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, sa, null,
                current -> new AiCostDecision(player, current, false));
        if (played) pending = sa;
        else failedTurn = turn;
        System.err.println("CUBE_TOP_PLAN " + (played ? "played" : "native-payment-failed")
                + " turn=" + turn + " card=" + sa.getHostCard().getName() + " api=" + sa.getApi()
                + " from=" + (sa.getHostCard().getCastFrom() == null ? "none" : sa.getHostCard().getCastFrom().getZoneType())
                + " lifePaid=" + sa.getAmountLifePaid());
        return played;
    }

    public boolean waitingForOwnSpell() {
        var stack = player.getGame().getStack();
        if (selected == null || turn != player.getGame().getPhaseHandler().getTurn() || stack.isEmpty()) return false;
        var top = stack.peekAbility();
        return top != null && top.getActivatingPlayer() == player
                && (top.getHostCard() == selected.getHostCard() || RESERVOIR.equals(top.getHostCard().getName())
                    || BIRGI.equals(top.getHostCard().getName()));
    }
}
