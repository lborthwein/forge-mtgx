package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPayLife;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

/** Finite Top recursion turn plan. Every draw, cast, payment, trigger and shot
 * is a native action with ordinary opposing priority.
 * Only a natively viewable top card may be inspected, never the hidden tail.
 *
 * Two things are recognized by property rather than by card name: permission to
 * cast the recursion object from the top of our own library ({@link #permissions})
 * and the win outlet. The outlet is either the life-shot line (Aetherflux
 * Reservoir, still by name) or a draw-drain permanent ({@link #drainPerOwnDraw}),
 * whose trigger shape decides membership. */
public final class CubeTopPlan {
    private static final String TOP = "Sensei's Divining Top",
            BIRGI = "Birgi, God of Storytelling", RESERVOIR = "Aetherflux Reservoir";
    /** The cube's two top-of-library play permissions, as FETCH CANDIDATES
     * only. {@link #permissions} still decides, by static shape on the live
     * card, whether a permission is actually granted. */
    private static final java.util.List<String> PERMISSION_NAMES =
            java.util.List.of("Bolas's Citadel", "Mystic Forge");
    private final Player player;
    private int turn = -1, actions, failedTurn = -1;
    private SpellAbility selected, pending;
    private int libraryBefore, lifeBefore, opposingLifeBefore;
    /** Set when the selected action belongs to the drain route, so the
     * no-progress check measures the right public quantity. */
    private boolean drain;
    /** The drain permanent the last drain-route pass recognized, if any. */
    private Card drainOutlet;
    /** Observability only: the tokens for the checks that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeDoomsdayPlan#declineReason}. This plan has TWO routes, both of
     * which are consulted on a declining pass, so both are reported: the
     * combined reason is {@code shot:<token>,drain:<token>}. A decline before
     * either route is entered is a single plain token instead.
     *
     * <p>Grammar. Plain tokens: {@code phase}, {@code stack-not-empty},
     * {@code cant-win}, {@code action-cap}, {@code failed-this-turn},
     * {@code multiplayer}, {@code stopped-no-progress}. Per-route tokens:
     * {@code no-outlet}, {@code outlet-unusable}, {@code no-life-gain},
     * {@code no-loop-action}. Every one names our own battlefield, our own
     * hand, our own library SIZE, our own life or the public opposing life;
     * none reads an opponent's hand, library contents or library order.</p> */
    private String decline = "other check=top-plan";
    private String shotDecline = "other check=shot-route", drainDecline = "other check=drain-route";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline = reason; return null; }
    private SpellAbility declineShot(String reason) { shotDecline = reason; return null; }
    private SpellAbility declineDrain(String reason) { drainDecline = reason; return null; }
    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order, after that guard has already
     * decided to decline. */
    private String gateReason() {
        var phase = player.getGame().getPhaseHandler();
        if (failedTurn == turn) return "failed-this-turn";
        if (actions >= 200) return "action-cap";
        if (player.cantWin()) return "cant-win";
        if (!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        if (!(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))) return "phase";
        return "multiplayer";
    }

    /** A native permission to play the top card of our own library, found by
     * the shape of a continuous static ability we control. {@code lifeCost} is
     * true when that permission substitutes a life payment for the mana cost
     * (Bolas's Citadel's {@code MayPlayAltManaCost$ PayLife<ConvertedManaCost>}). */
    private record Permission(Card card, boolean lifeCost) {}

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

    private SpellAbility cast(Card card, boolean fromLibrary, Permission permission) {
        if (card == null) return null;
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility sa = original.copy(player);
            if (!sa.isSpell()) continue;
            // Select an actual native permission/alternative, not a base
            // spell copied into an unauthorized zone or stripped of costs.
            // The permission is identified by the permanent that granted it,
            // and its life payment by that permission's own alternative cost.
            if (fromLibrary && (sa.getMayPlay() == null
                    || sa.getMayPlay().getHostCard() != permission.card()
                    || permission.lifeCost() != sa.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostPayLife))) continue;
            if (payable(sa)) return sa;
        }
        return null;
    }

    /** Every permanent we control that natively grants play permission for the
     * top card of our own library, cheapest-payment first: a permission that
     * substitutes a life payment is tried only after the ones that do not.
     * Membership is the static's shape (MayPlay for our own top card, in the
     * library zone, live and unsuppressed), never the permanent's name. */
    private java.util.List<Permission> permissions() {
        java.util.List<Permission> free = new java.util.ArrayList<>(), paid = new java.util.ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (var st : card.getStaticAbilities()) {
                if (st.isSuppressed() || !"True".equals(st.getParamOrDefault("MayPlay", ""))
                        || !st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous)
                        || !java.util.List.of(st.getParamOrDefault("AffectedZone", "").split(",")).contains("Library")) continue;
                String affected = st.getParamOrDefault("Affected", "");
                if (affected.isEmpty() || java.util.Arrays.stream(affected.split(","))
                        .anyMatch(alt -> !alt.contains("TopLibrary") || !alt.contains("YouCtrl"))) continue;
                // A permission with an alternative cost this plan cannot
                // forecast is not a permission it may use.
                boolean lifeCost = st.getParamOrDefault("MayPlayAltManaCost", "").startsWith("PayLife");
                if (st.hasParam("MayPlayAltManaCost") && !lifeCost) continue;
                (lifeCost ? paid : free).add(new Permission(card, lifeCost));
                break;
            }
        }
        free.addAll(paid);
        return free;
    }

    /** How much life each opponent loses every time we draw a card, from a
     * permanent we control, or 0 when this permanent is not such an outlet.
     * Only an unconditional trigger on our own draws with an untargeted fixed
     * life loss for opponents qualifies: the parameter set is allow-listed, so
     * a per-turn count, a condition, a target or an opponent-draw trigger
     * (Sheoldred, the Apocalypse's drain half) is not mistaken for this shape. */
    private int drainPerOwnDraw(Card card) {
        if (card.isFaceDown()) return 0;
        for (var trigger : card.getTriggers()) {
            if (trigger.isSuppressed()
                    || !java.util.Set.of("Mode", "ValidCard", "TriggerZones", "Execute", "TriggerDescription")
                        .containsAll(trigger.getMapParams().keySet())
                    || !"Drawn".equals(trigger.getParam("Mode"))
                    || !java.util.List.of("Card.YouCtrl", "Card.YouOwn").contains(trigger.getParamOrDefault("ValidCard", ""))
                    || !java.util.List.of(trigger.getParamOrDefault("TriggerZones", "").split(",")).contains("Battlefield")) continue;
            java.util.Map<String, String> effect = new java.util.HashMap<>();
            for (String piece : card.getSVar(trigger.getParamOrDefault("Execute", "")).split("\\|")) {
                String[] pair = piece.trim().split("\\$", 2);
                if (pair.length == 2) effect.put(pair[0].trim(), pair[1].trim());
            }
            if (!"LoseLife".equals(effect.get("DB")) || effect.containsKey("ValidTgts")
                    || effect.containsKey("UnlessCost")
                    || !java.util.List.of("Player.Opponent", "Opponent").contains(effect.getOrDefault("Defined", ""))) continue;
            try {
                int amount = Integer.parseInt(effect.getOrDefault("LifeAmount", ""));
                if (amount > 0) return amount;
            } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    private SpellAbility select(SpellAbility sa, boolean drainRoute) {
        selected = sa; drain = drainRoute; actions++; return sa;
    }

    /** Count-only reach estimate, not a claim of a forced win. Real triggers,
     * replacements and responses must still resolve; failures stop the plan. */
    private boolean enoughDraws(Card top, int draws, boolean immediateRecast, Permission permission) {
        int lifeCost = permission.lifeCost() ? 1 : 0;
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


    /** v60 - the three roles this plan's entry gate needs: Sensei's Divining
     * Top own-visible (our own battlefield or our own hand - {@link
     * #nextLoopAction} reads both), a top-of-library play permission on our own
     * battlefield ({@link #permissions}), and a win outlet on our own
     * battlefield (the Reservoir's life shot, or a draw-drain permanent by
     * {@link #drainPerOwnDraw}). Empty unless exactly one role is missing.
     *
     * <p>The permission is recognised by the SHAPE of a static on a live card,
     * which is exactly why a card we do not have cannot be recognised that way.
     * When the permission is the missing role, and only then, this method falls
     * back to the cube's two members by name. That is the one place in this
     * family where a name list stands in for a property test, and it is
     * confined to naming a fetch candidate: whether the fetched card actually
     * grants the permission is still decided by {@link #permissions} on the
     * live card, after it is on the battlefield.</p>
     *
     * <p>Own battlefield, own hand and our own library SIZE only; no library
     * contents or order, no opponent zone, no face-down identity. Entry gate
     * only - the {@code enoughDraws} / {@code enoughDrain} reach forecasts are
     * this plan's business on the turn it acts.</p> */
    static java.util.List<String> completingPieceNames(Player player) {
        CubeTopPlan plan = new CubeTopPlan(player);
        boolean top = plan.find(TOP, ZoneType.Battlefield) != null || plan.find(TOP, ZoneType.Hand) != null;
        boolean permission = !plan.permissions().isEmpty();
        boolean outlet = plan.find(RESERVOIR, ZoneType.Battlefield) != null;
        if (!outlet) for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (plan.drainPerOwnDraw(card) > 0) { outlet = true; break; }
        if ((top ? 1 : 0) + (permission ? 1 : 0) + (outlet ? 1 : 0) != 2) return java.util.List.of();
        if (!top) return java.util.List.of(TOP);
        return permission ? java.util.List.of(RESERVOIR) : PERMISSION_NAMES;
    }

    public SpellAbility nextAction() {
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) {
            turn = phase.getTurn(); actions = 0; selected = null; pending = null; drain = false;
        }
        if (failedTurn == turn || actions >= 200 || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return decline(gateReason());
        shotDecline = "other check=shot-route"; drainDecline = "other check=drain-route";
        Player opponent = player.getOpponents().get(0);
        if (pending != null) {
            // Every iteration must make public progress. On the drain route the
            // draw itself is the damage, so the opponent's life is what must
            // move, and our own life is spent by design rather than refunded.
            boolean stalled = pending.getApi() == ApiType.Draw
                    && (find(TOP, ZoneType.Battlefield) != null
                        || player.getCardsIn(ZoneType.Library).size() != libraryBefore
                        || drain && opponent.getLife() >= opposingLifeBefore)
                    || pending.isSpell() && (find(TOP, ZoneType.Battlefield) == null
                        || !drain && player.getLife() < lifeBefore)
                    || pending.getApi() == ApiType.DealDamage && opponent.getLife() >= opposingLifeBefore;
            pending = null;
            if (stalled) {
                failedTurn = turn;
                System.err.println("CUBE_TOP_PLAN stopped-no-progress turn=" + turn);
                return decline("stopped-no-progress");
            }
        }
        SpellAbility shot = shotRoute(opponent);
        if (shot != null) return shot;
        SpellAbility drainAction = drainRoute(opponent);
        if (drainAction == null) decline = "shot:" + shotDecline + ",drain:" + drainDecline;
        return drainAction;
    }

    /** The life-shot outlet, unchanged: accumulate life with the Reservoir's
     * own cast trigger and fire the fifty-life shot. */
    private SpellAbility shotRoute(Player opponent) {
        Card reservoir = find(RESERVOIR, ZoneType.Battlefield);
        SpellAbility shot = ability(reservoir, ApiType.DealDamage);
        if (shot == null || opponent.getLife() <= 0 || opponent.getLife() > 50
                || opponent.cantLoseForZeroOrLessLife() || !opponent.canLoseLife() || !shot.canTarget(opponent))
            return declineShot(shot == null ? "no-outlet" : "outlet-unusable");
        shot.resetTargets(); shot.getTargets().add(opponent);
        // Forecast only activation restrictions here: canPlay() also checks
        // the fifty-life payment that this plan has not accumulated yet.
        // Full native legality and affordability are required for the shot.
        if (!shot.isTargetNumberValid() || !StaticAbilityMustTarget.meetsMustTargetRestriction(shot)
                || shot.isSuppressed() || reservoir.isDetained()
                || !shot.getRestrictions().canPlay(reservoir, shot)
                || !shot.isLegalAfterStack() || !shot.checkRestrictions(reservoir, player)) return declineShot("outlet-unusable");
        if (player.getLife() > 50 && payable(shot)) return select(shot, false);
        if (!knownLifeGainWorks(reservoir)) return declineShot("no-life-gain");
        // A native zero-cost/refunded recast preserves life for the finish.
        // This is a preference among feasible complete-loop plans, not a
        // blanket Mystic gate: an absent/suppressed reducer or unpaid seed
        // still falls through to the life-paid Citadel route.
        for (Permission permission : permissions()) {
            SpellAbility next = nextLoopAction(permission, 0);
            if (next != null) return select(next, false);
        }
        return declineShot("no-loop-action");
    }

    /** The draw-drain outlet, reached only when no life-shot line is available.
     * The win condition is entirely public: the opponent's life total against
     * the draws this loop can still make, times the drain each draw deals. The
     * budget discipline is the shot route's — a finite forecast re-taken every
     * iteration, no progress means stop — and our own life gains are not
     * counted, so the forecast declines lines it may in fact be able to finish
     * rather than starting one it cannot. */
    private SpellAbility drainRoute(Player opponent) {
        drainOutlet = null;
        int amount = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            amount = drainPerOwnDraw(card);
            if (amount > 0) { drainOutlet = card; break; }
        }
        if (drainOutlet == null || opponent.getLife() <= 0
                || opponent.cantLoseForZeroOrLessLife() || !opponent.canLoseLife())
            return declineDrain(drainOutlet == null ? "no-outlet" : "outlet-unusable");
        int need = (opponent.getLife() + amount - 1) / amount;
        for (Permission permission : permissions()) {
            SpellAbility next = nextLoopAction(permission, need);
            if (next != null) return select(next, true);
        }
        return declineDrain("no-loop-action");
    }

    /** Count-only reach estimate for the drain outlet, not a claim of a forced
     * win: the draws must exist, be legal to take, and the repeat payment must
     * leave us alive for all of them. */
    private boolean enoughDrain(Card top, int draws, boolean immediateRecast, Permission permission, int need) {
        int casts = need + (immediateRecast ? 1 : 0);
        if (!permission.lifeCost() && !sustainableManaRecast(top)) return false;
        if (need > draws || !player.canDrawAmount(need)) return false;
        if (permission.lifeCost() && player.getLife() - (long) casts <= 0) return false;
        return castBudgetFits(top, casts);
    }

    /** {@code need == 0} selects the shot route's reach test; any other value
     * is the number of further draws the drain route still has to make. */
    private boolean enough(Card top, int draws, boolean immediateRecast, Permission permission, int need) {
        return need == 0 ? enoughDraws(top, draws, immediateRecast, permission)
                : enoughDrain(top, draws, immediateRecast, permission, need);
    }

    private SpellAbility nextLoopAction(Permission permission, int need) {
        Card top = find(TOP, ZoneType.Battlefield);
        int librarySize = player.getCardsIn(ZoneType.Library).size();
        if (top != null) {
            SpellAbility draw = ability(top, ApiType.Draw);
            if (librarySize > 0 && enough(top, librarySize, false, permission, need) && payable(draw)) return draw;
            return null;
        }
        top = find(TOP, ZoneType.Hand);
        if (top != null && enough(top, librarySize, true, permission, need)) {
            SpellAbility spell = cast(top, false, permission);
            if (spell != null) return spell;
        }
        if (librarySize == 0) return null;
        // Taking the top object is not permission to inspect its identity.
        Card visibleTop = player.getCardsIn(ZoneType.Library).get(0);
        if (!visibleTop.mayPlayerLook(player) || visibleTop.isFaceDown()
                || !TOP.equals(visibleTop.getName()) || !enough(visibleTop, librarySize - 1, true, permission, need)) return null;
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
                && (top.getHostCard() == selected.getHostCard() || top.getHostCard() == drainOutlet
                    || drain && top.isTrigger()
                    || RESERVOIR.equals(top.getHostCard().getName())
                    || BIRGI.equals(top.getHostCard().getName()));
    }
}
