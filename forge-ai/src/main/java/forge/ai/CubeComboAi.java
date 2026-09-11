package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

/** Versioned, seat-visible execution policy. Not a general combo solver.
 * The finite token budget is a combat heuristic, not a proof of a forced win.
 * Costs, legality, triggers, and response windows remain native Forge's. */
public final class CubeComboAi {
    public static final String VERSION = "cube-combo-execution-v43";
    private static final ThreadLocal<Player> PAYMENT_PROBE = new ThreadLocal<>();
    private CubeComboAi() { }

    /** Observability only: own-visible mana breadth, used by the decision log
     * and by decline reasons. Pure reads of our own public battlefield and our
     * own floating mana; no payment probe, no RNG, no state change. */
    static int ownVisibleMana(Player player) {
        int lands = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) if (card.isLand() && card.isUntapped()) lands++;
        return lands + player.getManaPool().totalMana();
    }

    /** Observability only: why the most recent {@link #planTutor} call on this
     * thread produced no plan. Set from checks planTutor already performs, read
     * by the controller immediately after the call. Never consulted by a decision. */
    private static final ThreadLocal<String> TUTOR_DECLINE = new ThreadLocal<>();
    private static void tutorDecline(String reason) { TUTOR_DECLINE.set(reason); }
    static String lastTutorDecline() {
        String reason = TUTOR_DECLINE.get();
        return reason == null ? "other check=planTutor" : reason;
    }

    /** Only the current actor's speculative payment, never a real activation
     * or another player's choice. No RNG state is read, copied or rewound. */
    public static boolean isPaymentProbeFor(Player player) { return PAYMENT_PROBE.get() == player; }

    /** Native affordability helpers are stateful even in test mode. Their scratch
     * payment memory/conversion must not escape a speculative combo check into
     * Default's next choice. This does not wrap or roll back actual payment. */
    public static <T> T probePayment(Player player, java.util.function.Supplier<T> probe, SpellAbility... queried) {
        var memory = new java.util.EnumMap<AiCardMemory.MemorySet, java.util.Set<Card>>(AiCardMemory.MemorySet.class);
        for (var set : AiCardMemory.MemorySet.values()) memory.put(set, new java.util.HashSet<>(AiCardMemory.getMemorySet(player, set)));
        var conversion = player.getManaPool().copyConversionState();
        var abilities = new java.util.ArrayList<ProbeAbilityState>();
        // Native source discovery examines hand mana too (e.g. Spirit Guides),
        // and sets actors on live abilities. Copies passed by a planner also
        // need restoration: a null actor is itself meaningful native state.
        for (Card card : CardCollection.combine(player.getCardsIn(ZoneType.Battlefield), player.getCardsIn(ZoneType.Hand)))
            for (SpellAbility ability : card.getSpellAbilities()) abilities.add(new ProbeAbilityState(ability));
        for (SpellAbility ability : queried) if (ability != null) abilities.add(new ProbeAbilityState(ability.getRootAbility()));
        Player previousProbe = PAYMENT_PROBE.get();
        PAYMENT_PROBE.set(player);
        try { return probe.get(); }
        finally {
            if (previousProbe == null) PAYMENT_PROBE.remove(); else PAYMENT_PROBE.set(previousProbe);
            for (var entry : memory.entrySet()) {
                var live = AiCardMemory.getMemorySet(player, entry.getKey());
                live.clear(); live.addAll(entry.getValue());
            }
            player.getManaPool().restoreConversionState(conversion);
            for (var ability : abilities) ability.restore();
        }
    }

    /** Setter propagates to descendants, so restore parents first and then
     * each child's exact original actor (which may differ from its parent). */
    private static final class ProbeAbilityState {
        private final SpellAbility ability;
        private final Player actor;
        private final String express;
        private final java.util.List<ProbeAbilityState> children = new java.util.ArrayList<>();
        ProbeAbilityState(SpellAbility ability) {
            this.ability = ability; actor = ability.getActivatingPlayer();
            express = ability.getManaPart() == null ? null : ability.getManaPart().getExpressChoice();
            if (ability.getSubAbility() != null) children.add(new ProbeAbilityState(ability.getSubAbility()));
            for (SpellAbility child : ability.getAdditionalAbilities().values()) children.add(new ProbeAbilityState(child));
            for (var list : ability.getAdditionalAbilityLists().values())
                for (SpellAbility child : list) children.add(new ProbeAbilityState(child));
        }
        void restore() {
            ability.setActivatingPlayer(actor);
            if (ability.getManaPart() != null) ability.getManaPart().setExpressChoice(express);
            for (var child : children) child.restore();
        }
    }

    public static boolean canPayCost(SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilCost.canPayCost(ability, player, effect), ability);
    }
    public static boolean canPayCost(forge.game.cost.Cost cost, SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilCost.canPayCost(cost, ability, player, effect), ability);
    }
    public static boolean canPayManaCost(forge.game.mana.ManaCostBeingPaid cost, SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilMana.canPayManaCost(cost, ability, player, effect), ability);
    }
    public static boolean canPayManaCost(SpellAbility ability, Player player, int extra, boolean effect) {
        return probePayment(player, () -> ComputerUtilMana.canPayManaCost(ability, player, extra, effect), ability);
    }
    public static CardCollection getManaSourcesToPayCost(forge.game.mana.ManaCostBeingPaid cost, SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilMana.getManaSourcesToPayCost(cost, ability, player, effect), ability);
    }

    public static boolean enabled(Player player) {
        return player.getController() instanceof CubeComboPlayerController;
    }

    /** Conservative availability outside named unusable zones. Unknown owned
     * face-down cards consume possible copies; their identity is never read.
     * Native permission, not ownership alone, authorizes looking at a face.
     * This uses own deck composition, never library contents or order. */
    public static boolean ownCopyOutside(Player player, String name, ZoneType... unusable) {
        int remaining = player.getRegisteredPlayer().getDeck().getMain().countByName(name);
        for (ZoneType zone : unusable) {
            for (Card card : player.getGame().getCardsIn(zone)) {
                if (card.getOwner() != player) continue;
                if (card.isFaceDown()) {
                    if (!card.getView().canFaceDownBeShownTo(player.getView())) {
                        remaining--; // Could be the needed card; no face access.
                        continue;
                    }
                    if (card.getOriginalState(forge.card.CardStateName.Original).getName().equals(name)) remaining--;
                } else if (card.getName().equals(name)) remaining--;
            }
        }
        return remaining > 0;
    }

    /** Forge separates timing/cost availability from static prohibitions.
     * Match AiController's prospective-stack host for cast-from/CMC checks;
     * canPlay() alone does not enforce Null Rod or Rule of Law. */
    public static boolean canPlayNative(SpellAbility ability, Player player) {
        if (!ability.canPlay() || !ability.isLegalAfterStack()) return false;
        Card host = ability.getHostCard();
        if (ability.isSpell()) {
            host = CardCopyService.getLKICopy(host);
            host.setLKICMC(-1);
            host.setLastKnownZone(player.getGame().getStackZone());
            host.setCastFrom(ability.getHostCard().getZone());
        }
        return ability.checkRestrictions(host, player);
    }

    private static boolean untapBody(Card card) {
        return card.getName().equals("Pestermite") || card.getName().equals("Deceiver Exarch")
                || card.getName().equals("Zealous Conscripts");
    }

    public static Card copyPartner(Player player, SpellAbility ability) {
        if (!enabled(player) || !copyEngine(ability)) return null;
        Card source = ability.getHostCard();
        if (source.getController() != player || !source.isInPlay()) return null;
        if (source.getName().equals("Kiki-Jiki, Mirror Breaker") && ability.usesTargeting()) {
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                if ((untapBody(card) || card.getName().equals("Restoration Angel")) && ability.canTarget(card)) return card;
            }
        } else if (untapBody(source) && "Self".equals(ability.getParam("Defined"))
                && "Haste".equals(ability.getParam("AddKeywords"))) {
            return source;
        }
        return null;
    }

    private static boolean copyEngine(SpellAbility ability) {
        return ability.getApi() == ApiType.CopyPermanent && ability.isActivatedAbility()
                && ability.getPayCosts() != null && ability.getPayCosts().hasTapCost()
                && ability.hasParam("AtEOT") && "Haste".equals(ability.getParam("AddKeywords"));
    }

    public static boolean selectSingleTarget(SpellAbility ability, Card target) {
        if (!ability.canTarget(target)) return false;
        ability.resetTargets();
        ability.getTargets().add(target);
        if (ability.isTargetNumberValid() && StaticAbilityMustTarget.meetsMustTargetRestriction(ability)) return true;
        ability.resetTargets();
        return false;
    }

    public static boolean needsMoreCopies(Player player, SpellAbility ability) {
        Card partner = copyPartner(player, ability);
        if (partner == null || !player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)
                || !player.getGame().getStack().isEmpty()) return false;
        int power = partner.getNetPower();
        if (power <= 0) return false;
        long budget = 0;
        for (Player opponent : player.getOpponents()) {
            budget += 2L + opponent.getCreaturesInPlay().size()
                    + (Math.max(0L, opponent.getLife()) + power - 1) / power;
        }
        budget = Math.min(64, budget);
        long readyCopies = player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.isToken() && c.getName().equals(partner.getName())
                        && c.isUntapped() && !c.isSick() && c.getNetPower() > 0).count();
        return readyCopies < budget && ability.getActivationsThisTurn() < 64;
    }

    public static Card untapSource(Player player, SpellAbility trigger) {
        if (!enabled(player) || !untapBody(trigger.getHostCard()) || !trigger.usesTargeting()) return null;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            // This is an effect untap, not that player's untap step (exert and
            // "doesn't untap next step" must not prohibit an effect untap).
            if (!card.isTapped() || !card.canUntap(null, true) || !trigger.canTarget(card)) continue;
            for (SpellAbility ability : card.getSpellAbilities()) {
                // The dormant card ability has no activating player after
                // native stack cleanup; canTarget(YouCtrl) on it would reject
                // every partner. Recognize the public engine here, and leave
                // target/cost legality to the next actual activation.
                if (copyEngine(ability) && (card.getName().equals("Kiki-Jiki, Mirror Breaker") && ability.usesTargeting()
                        || card.getName().equals(trigger.getHostCard().getName()) && "Self".equals(ability.getParam("Defined")))) return card;
            }
        }
        return null;
    }

    /** Restoration Angel resets a non-token Kiki through an actual zone change. */
    public static boolean selectBlinkSource(Player player, SpellAbility trigger) {
        if (!enabled(player) || !trigger.getHostCard().getName().equals("Restoration Angel")
                || !trigger.usesTargeting() || trigger.getApi() != ApiType.ChangeZone
                || !"Battlefield".equals(trigger.getParam("Origin")) || !"Exile".equals(trigger.getParam("Destination"))
                || trigger.getSubAbility() == null || trigger.getSubAbility().getApi() != ApiType.ChangeZone
                || !"Battlefield".equals(trigger.getSubAbility().getParam("Destination"))) return false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (!card.getName().equals("Kiki-Jiki, Mirror Breaker") || card.isToken() || !card.isTapped()
                    || !trigger.canTarget(card) || card.getSpellAbilities().stream().noneMatch(CubeComboAi::copyEngine)) continue;
            if (selectSingleTarget(trigger, card)) return true;
        }
        return false;
    }

    public static boolean selectConscriptsSource(Player player, SpellAbility trigger) {
        if (!trigger.getHostCard().getName().equals("Zealous Conscripts") || trigger.getApi() != ApiType.GainControl
                || !trigger.hasParam("Untap")) return false;
        Card source = untapSource(player, trigger);
        if (source == null) return false;
        return selectSingleTarget(trigger, source);
    }

    /** Complete an available Kiki pair with a castable creature. Only invoked
     * during a native search of our own library, using its legal fetch list.
     * No opponent decklist or future library order is inspected. */
    public static Card chooseTutorPartner(Player player, SpellAbility tutor, CardCollection legalChoices) {
        if (!enabled(player) || tutor.getActivatingPlayer() != player) return null;
        // Kiki's haste route can finish this combat; the new Thopter bodies
        // normally need the next turn. Preserve the available faster route.
        Card immediate = chooseKikiTutorPartner(player, legalChoices);
        return immediate != null ? immediate : CubeThopterPlan.chooseAssemblyCard(player, legalChoices);
    }

    /** A Kiki pair that can finish this combat: the copy engine is on our
     * battlefield with a partner body on the battlefield or castable from
     * hand, or vice versa. Used so a slower plan does not spend the mana that
     * the faster available route needs. MAIN1 only. */
    static boolean hasImmediateKikiRoute(Player player) {
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return false;
        boolean haveKiki = false, havePartner = false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            if (card.getName().equals("Kiki-Jiki, Mirror Breaker")
                    && card.getSpellAbilities().stream().anyMatch(sa -> copyEngine(sa) && !sa.isSuppressed()
                        && sa.copy(player).checkRestrictions(card, player))) haveKiki = true;
            if (untapBody(card) || card.getName().equals("Restoration Angel")) havePartner = true;
        }
        if (haveKiki && havePartner) return true;
        if (haveKiki == havePartner) return false;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) continue;
            if ((haveKiki && (untapBody(card) || card.getName().equals("Restoration Angel"))
                    || havePartner && card.getName().equals("Kiki-Jiki, Mirror Breaker"))
                    && feasiblePartnerAfterSelection(player, card)) return true;
        }
        return false;
    }

    private static Card chooseKikiTutorPartner(Player player, CardCollection legalChoices) {
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return null;
        boolean haveKiki = false, havePartner = false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            if (card.getName().equals("Kiki-Jiki, Mirror Breaker")
                    && card.getSpellAbilities().stream().anyMatch(sa -> copyEngine(sa) && !sa.isSuppressed()
                        && sa.copy(player).checkRestrictions(card, player))) haveKiki = true;
            if (untapBody(card) || card.getName().equals("Restoration Angel")) havePartner = true;
        }
        if (haveKiki == havePartner) return null;
        // A different interchangeable partner in hand also completes the pair.
        // Do not spend a selection on a redundant body merely because its name differs.
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) continue;
            if ((haveKiki && (untapBody(card) || card.getName().equals("Restoration Angel"))
                    || havePartner && card.getName().equals("Kiki-Jiki, Mirror Breaker"))
                    && feasiblePartnerAfterSelection(player, card)) return null;
        }
        Card best = null;
        for (Card card : legalChoices) {
            if (card.getOwner() != player || card.isFaceDown() || !card.isInZone(ZoneType.Library)) continue;
            boolean partner = untapBody(card) || card.getName().equals("Restoration Angel");
            if (!(haveKiki && partner || havePartner && card.getName().equals("Kiki-Jiki, Mirror Breaker"))) continue;
            if (player.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getName().equals(card.getName()))) continue;
            if (!feasiblePartnerAfterSelection(player, card)) continue;
            if (best == null || card.getCMC() < best.getCMC()) best = card;
        }
        return best;
    }

    /** Forecast a normal hand cast after the resolving selection spell leaves
     * the stack. canPlay() now would wrongly reject every sorcery-speed creature
     * during Ponder resolution. Use native static restrictions and native cost
     * feasibility consistently for both hand alternatives and revealed cards.
     * The real later cast still passes the full native legality/payment path. */
    static boolean feasiblePartnerAfterSelection(Player player, Card card) {
        SpellAbility original=card.getSpellPermanent();
        if(original==null)return false;
        SpellAbility spell=original.copy(player);
        Card prospective=CardCopyService.getLKICopy(card);
        prospective.setLKICMC(-1);
        prospective.setLastKnownZone(player.getGame().getStackZone());
        prospective.setCastFrom(player.getZone(ZoneType.Hand));
        return spell.isLegalAfterStack()&&spell.checkRestrictions(prospective,player)
                &&CubeComboAi.canPayCost(spell,player,false);
    }

    public record TutorPlan(SpellAbility tutor, CardCollection reservedSources, String plannedPartner) { }

    /** Plan a tutor followed by the missing creature before combat. This uses
     * own registered composition, not a peek at the search's future choices.
     * Native payment finds disjoint sources for the two casts, preserving the
     * creature's sources during the real tutor payment. Shared floating mana
     * and non-mana costs need a richer allocation forecast; decline those
     * plans rather than counting the same resources twice. */
    public static TutorPlan planTutor(Player player) {
        boolean main1 = player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player);
        String thopterMissing = CubeThopterPlan.missingPiece(player);
        if (!enabled(player) || !(main1 || thopterMissing != null && player.getGame().getPhaseHandler().is(PhaseType.MAIN2, player))
                || !player.getGame().getStack().isEmpty() || !player.getManaPool().isEmpty()
                || player.cantWin() || player.hasKeyword("LimitSearchLibrary")) {
            // Observability only: name the guard that already rejected the plan.
            tutorDecline(!player.getGame().getStack().isEmpty() ? "stack-not-empty"
                    : player.cantWin() ? "cant-win"
                    : !main1 ? "phase"
                    : !player.getManaPool().isEmpty() ? "other check=floating-mana"
                    : player.hasKeyword("LimitSearchLibrary") ? "other check=LimitSearchLibrary"
                    : "other check=planTutor-guard");
            return null;
        }
        tutorDecline("other check=no-tutor-in-hand");
        for (Card hand : player.getCardsIn(ZoneType.Hand)) {
            if (hand.isFaceDown()) continue;
            for (SpellAbility original : hand.getSpellAbilities()) {
                SpellAbility tutor = original.copy(player);
                if (!tutor.isSpell() || tutor.getApi() != ApiType.ChangeZone || tutor.usesTargeting()
                        || !"Library".equals(tutor.getParam("Origin"))
                        || !"Hand".equals(tutor.getParam("Destination"))
                        || !"1".equals(tutor.getParamOrDefault("ChangeNum", "1"))
                        || !"You".equals(tutor.getParamOrDefault("Defined", "You"))
                        || tutor.getSubAbility() != null || !manaOnly(tutor)
                        || !canPlayNative(tutor, player) || !player.canSearchLibraryWith(tutor, player)) continue;
                tutorDecline("other check=no-partner-route");
                for (var entry : player.getRegisteredPlayer().getDeck().getMain()) {
                    String name = entry.getKey().getName();
                    if (!(name.equals(thopterMissing) || main1 && java.util.Set.of("Kiki-Jiki, Mirror Breaker", "Pestermite", "Deceiver Exarch",
                            "Restoration Angel", "Zealous Conscripts").contains(name))
                            || !ownCopyOutside(player, name, ZoneType.Hand, ZoneType.Battlefield,
                                ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Stack)) continue;
                    // A detached prototype: no game ID allocation or zone insertion.
                    Card forecast = forge.game.card.CardFactory.getCard(entry.getKey(), player, -1, player.getGame());
                    if(name.equals(thopterMissing) && !CubeThopterPlan.addAssemblyPreviewRules(forecast,entry.getKey()))continue;
                    forecast.setZone(player.getZone(ZoneType.Library));
                    if (!forecast.isValid(tutor.getParamOrDefault("ChangeType", "Card").split(","), player, hand, tutor)
                            || chooseTutorPartner(player, tutor, new CardCollection(forecast)) == null) continue;
                    forecast.setZone(player.getZone(ZoneType.Hand));
                    SpellAbility creature = forecast.getSpellPermanent().copy(player);
                    if (!manaOnly(creature) || !castFitsAfter(player, tutor, creature)) continue;
                    var cost = ComputerUtilMana.calculateManaCost(creature.getPayCosts(), creature, player, true, 0, false);
                    CardCollection reserve = CubeComboAi.getManaSourcesToPayCost(cost, creature, player, false);
                    tutorDecline("mana:" + (tutor.getHostCard().getCMC() + forecast.getCMC()) + "/" + ownVisibleMana(player));
                    if (reserve != null && withReservedSources(player, reserve,
                            () -> CubeComboAi.canPayCost(tutor, player, false))) return new TutorPlan(tutor, reserve, name);
                }
            }
        }
        return null;
    }

    static boolean manaOnly(SpellAbility sa) {
        return sa.getPayCosts() != null && sa.getPayCosts().getCostParts().stream()
                .allMatch(part -> part instanceof forge.game.cost.CostPartMana);
    }

    /** Account for public cast-count prohibitions after the first spell.
     * Both actual casts still pass native legality; this is a forecast only.
     * Shared by planTutor and by the two-piece hand-assembly forecast, so a
     * single prohibition rule covers every two-cast plan. */
    static boolean castFitsAfter(Player player, SpellAbility first, SpellAbility second) {
        Card next = CardCopyService.getLKICopy(second.getHostCard());
        next.setLastKnownZone(player.getGame().getStackZone());
        Card prior = CardCopyService.getLKICopy(first.getHostCard());
        prior.setLastKnownZone(player.getGame().getStackZone());
        for (Card source : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (source.isFaceDown()) continue;
            for (var st : source.getStaticAbilities()) {
                if (!st.hasParam("NumLimitEachTurn")
                        || !st.checkConditions(forge.game.staticability.StaticAbilityMode.CantBeCast)
                        || !st.matchesValidParam("ValidCard", next) || !st.matchesValidParam("Caster", player)
                        || st.getIgnoreEffectPlayers().contains(player)) continue;
                if (!java.util.Set.of("Mode", "ValidCard", "Caster", "NumLimitEachTurn", "Description")
                        .containsAll(st.getMapParams().keySet())) return false;
                int used = forge.game.card.CardLists.filterControlledByAsList(
                        forge.game.card.CardUtil.getThisTurnCast(st.getParamOrDefault("ValidCard", "Card"), next, st, player), player).size();
                if (used + (st.matchesValidParam("ValidCard", prior) ? 1 : 0)
                        >= Integer.parseInt(st.getParam("NumLimitEachTurn"))) return false;
            }
        }
        return true;
    }

    public static <T> T withReservedSources(Player player, CardCollection sources, java.util.function.Supplier<T> task) {
        CardCollection added = new CardCollection();
        var memory = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        for (Card source : sources) if (!AiCardMemory.isRememberedCard(player, source, memory)) {
            AiCardMemory.rememberCard(player, source, memory); added.add(source);
        }
        try { return task.get(); }
        finally { for (Card source : added) AiCardMemory.forgetCard(player, source, memory); }
    }
}
