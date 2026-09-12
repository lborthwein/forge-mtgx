package forge.ai;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit per-player opt-in; the opposing Default controller is unchanged. */
public final class CubeComboPlayerController extends PlayerControllerAi {
    private final CubeDoomsdayPlan doomsdayPlan;
    private final CubeBreachPlan breachPlan;
    private final CubeStormPlan stormPlan;
    private final CubeMonolithPlan monolithPlan;
    private final CubeKittenPlan kittenPlan;
    private final CubeTopPlan topPlan;
    private final CubeThopterPlan thopterPlan;
    private final CubeBombPlan bombPlan;
    private int comboSelectionChanges;
    private CubeComboAi.TutorPlan tutorPlan;
    private int comboTutorPlanCasts;
    /** The action this policy proposed on the current priority pass, or null when
     * the ordinary AI's own choice was taken. The native-legality guard applies to
     * this object only; see {@link #playChosenSpellAbility}. */
    private SpellAbility planAction;
    /** Diagnostic only, never read by a decision: how often the guard rejected a
     * plan action, and how often it would have disagreed with an ordinary land
     * choice. Test-visible statics, read and reset reflectively by the fixture. */
    static int guardRejections, ordinaryGuardDisagreements;
    /** Diagnostic only, never read by a decision: how often v49's discard
     * ownership actually swapped one card of an ordinary discard choice for
     * another. Test-visible static, read and reset reflectively by the fixture,
     * exactly like {@link #guardRejections}. */
    static int comboDiscardSwaps;
    /** Observability state only: rate limits for the stderr decision log. */
    private static final int DECISION_LINE_CAP = 40;
    /** v54 observability: the order in which {@link #chooseSpellAbilityToPlay}
     * consults the plans. It is a READ of that method's existing short-circuit
     * chain, never a driver of it: the chain below is unchanged, and this list
     * only tells {@link #logDecision} which plans were actually asked on this
     * pass. A plan at index i was consulted exactly when no plan before it
     * produced an action, so the winning plan's index is the length of the
     * consulted prefix. */
    private static final List<String> PLAN_ORDER =
            List.of("doomsday", "breach", "storm", "monolith", "kitten", "top", "thopter", "bomb");
    private int decisionTurn = -1, decisionLines;
    private PhaseType decisionPhase;
    private boolean declinedDoomsday, declinedTutor;
    /** One decline line per family per (turn, phase), for the seven families
     * v54 adds. Index matches {@link #PLAN_ORDER}; slot 0 (doomsday) is unused
     * because {@link #declinedDoomsday} already owns that line. */
    private final boolean[] declinedFamily = new boolean[PLAN_ORDER.size()];
    public int getComboSelectionChanges() { return comboSelectionChanges; }
    public int getComboTutorPlanCasts() { return comboTutorPlanCasts; }
    public CubeComboPlayerController(Game game, Player player, LobbyPlayer lobby) {
        super(game, player, lobby);
        if (game.getRules().getAiInformationPolicy() != GameRules.AiInformationPolicy.CLOSED_REPAIR)
            throw new IllegalArgumentException("Cube combo AI requires closed-decklist-repair-v1");
        doomsdayPlan = new CubeDoomsdayPlan(player);
        breachPlan = new CubeBreachPlan(player);
        stormPlan = new CubeStormPlan(player);
        monolithPlan = new CubeMonolithPlan(player);
        kittenPlan = new CubeKittenPlan(player);
        topPlan = new CubeTopPlan(player);
        thopterPlan = new CubeThopterPlan(player);
        bombPlan = new CubeBombPlan(player);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        planAction = null;
        if (doomsdayPlan.waitingForOwnSpell() || breachPlan.waitingForOwnSpell() || stormPlan.waitingForOwnSpell() || monolithPlan.waitingForOwnSpell() || kittenPlan.waitingForOwnSpell() || topPlan.waitingForOwnSpell() || thopterPlan.waitingForOwnSpell() || bombPlan.waitingForOwnSpell()) return null;
        // `plan` records which plan produced the action for the decision log
        // only; the selection order and every call below are unchanged.
        String plan = "none";
        boolean tutorConsulted = false;
        SpellAbility action = doomsdayPlan.nextAction();
        if (action != null) plan = "doomsday";
        if (action == null && (action = breachPlan.nextAction()) != null) plan = "breach";
        if (action == null && (action = stormPlan.nextAction()) != null) plan = "storm";
        if (action == null && (action = monolithPlan.nextAction()) != null) plan = "monolith";
        if (action == null && (action = kittenPlan.nextAction()) != null) plan = "kitten";
        if (action == null && (action = topPlan.nextAction()) != null) plan = "top";
        if (action == null && (action = thopterPlan.nextAction()) != null) plan = "thopter";
        // v52 bomb family last of the plans, before the tutor forecast: it
        // owns no piece any earlier plan can want, and placing it here keeps
        // every existing family's selection order byte-identical.
        if (action == null && (action = bombPlan.nextAction()) != null) plan = "bomb";
        if (action == null) {
            tutorConsulted = true;
            tutorPlan = CubeComboAi.planTutor(getPlayer());
            if (tutorPlan != null) {
                action = tutorPlan.tutor();
                if (action != null) plan = "tutor";
            }
        }
        List<SpellAbility> chosen = action == null ? super.chooseSpellAbilityToPlay() : List.of(action);
        planAction = action;
        logDecision(plan, action, chosen, tutorConsulted);
        return chosen;
    }

    /** Observability only. Emits at most one line per stack-empty priority pass
     * in our own MAIN1/MAIN2, capped per (turn, phase), after the decision has
     * already been made. Reads only information this seat legitimately sees:
     * our own hand and battlefield, both public life totals, our untapped lands
     * and our own floating mana. Face-down cards are never identified, and the
     * opponent's hand, library order and library contents are never touched. */
    private void logDecision(String plan, SpellAbility action, List<SpellAbility> chosen, boolean tutorConsulted) {
        var phases = getGame().getPhaseHandler();
        Player me = getPlayer();
        if (!phases.is(PhaseType.MAIN1, me) && !phases.is(PhaseType.MAIN2, me)) return;
        if (!getGame().getStack().isEmpty()) return;
        int turn = phases.getTurn();
        PhaseType phase = phases.getPhase();
        if (turn != decisionTurn || phase != decisionPhase) {
            decisionTurn = turn; decisionPhase = phase; decisionLines = 0;
            declinedDoomsday = false; declinedTutor = false;
            java.util.Arrays.fill(declinedFamily, false);
        }
        if (++decisionLines > DECISION_LINE_CAP) return;
        int oppLife = 0;
        for (Player opponent : me.getOpponents()) { oppLife = opponent.getLife(); break; }
        int lands = 0;
        for (Card card : me.getCardsIn(ZoneType.Battlefield)) if (card.isLand() && card.isUntapped()) lands++;
        System.err.println("CUBE_DECISION turn=" + turn + " phase=" + phase + " life=" + me.getLife()
                + " oppLife=" + oppLife + " lands=" + lands + " pool=" + me.getManaPool().totalMana()
                + " hand=[" + ownNames(ZoneType.Hand, false) + "] battlefield=[" + ownNames(ZoneType.Battlefield, true) + "]"
                + " plan=" + plan + " chosen=" + describe(action, chosen));
        if (!declinedDoomsday && !"doomsday".equals(plan)) {
            declinedDoomsday = true;
            System.err.println("CUBE_PLAN_DECLINE family=doomsday reason=" + doomsdayPlan.declineReason());
        }
        if (tutorConsulted && !declinedTutor && tutorPlan == null) {
            declinedTutor = true;
            System.err.println("CUBE_PLAN_DECLINE family=kiki-tutor reason=" + CubeComboAi.lastTutorDecline());
        }
        logFamilyDeclines(plan);
    }

    /** v54: the same line, same cap and same condition, for the seven families
     * that used to decline silently. Appended after the two pre-existing
     * decline lines, so removing these lines restores the pre-v54 log exactly.
     *
     * <p>Only a plan that was CONSULTED on this very pass may be reported, so
     * no stale token can reach the log: {@link #chooseSpellAbilityToPlay}
     * short-circuits, and a plan after the winner was never asked. The winner's
     * index in {@link #PLAN_ORDER} is therefore the consulted prefix; when no
     * plan won (the label is {@code none} or {@code tutor}) every plan was
     * consulted. Each reported plan set its token inside the {@code nextAction}
     * call this pass already made.</p> */
    private void logFamilyDeclines(String plan) {
        int winner = PLAN_ORDER.indexOf(plan);
        int consulted = winner < 0 ? PLAN_ORDER.size() : winner + 1;
        for (int family = 1; family < consulted; family++) {
            if (family == winner || declinedFamily[family]) continue;
            declinedFamily[family] = true;
            System.err.println("CUBE_PLAN_DECLINE family=" + PLAN_ORDER.get(family)
                    + " reason=" + familyDeclineReason(family));
        }
    }

    /** The token each plan already recorded for the {@code nextAction} call
     * this pass made. Pure accessor; never consulted by a decision. */
    private String familyDeclineReason(int family) {
        switch (family) {
            case 1: return breachPlan.declineReason();
            case 2: return stormPlan.declineReason();
            case 3: return monolithPlan.declineReason();
            case 4: return kittenPlan.declineReason();
            case 5: return topPlan.declineReason();
            case 6: return thopterPlan.declineReason();
            case 7: return bombPlan.declineReason();
            default: return "other check=unknown-family";
        }
    }

    /** Our own zone, sorted for stable comparison. A face-down card is counted
     * but never identified, so no hidden identity can reach the log. */
    private String ownNames(ZoneType zone, boolean nonLandOnly) {
        List<String> names = new ArrayList<>();
        for (Card card : getPlayer().getCardsIn(zone)) {
            if (nonLandOnly && card.isLand()) continue;
            names.add(card.isFaceDown() ? "face-down" : card.getName());
        }
        Collections.sort(names);
        return String.join(";", names);
    }

    private static String describe(SpellAbility action, List<SpellAbility> chosen) {
        if (action != null) return action.getHostCard().getName() + "/" + action.getApi();
        if (chosen == null || chosen.isEmpty() || chosen.get(0) == null) return "pass";
        SpellAbility ordinary = chosen.get(0);
        return "ordinary:" + ordinary.getHostCard().getName() + "/" + ordinary.getApi();
    }

    /** The native-legality guard belongs to the actions this policy proposes. An
     * ordinary Default choice must reach the unchanged native path: for a land
     * ability {@link PlayerControllerAi#playChosenSpellAbility} reports the choice
     * as handled even when it cannot resolve, so returning false makes PhaseHandler
     * ask again, the ordinary AI re-chooses the same best land every priority pass,
     * and the land is never played. Observed in the v42 do-no-harm whole-game panel,
     * game dnh-wide-mtgx-s3066-16701373-o0-treat-p0-r0: 5,994
     * native-legality-rejected lines for Fabled Passage and a turn-16 loss where the
     * Default baseline of the same pairing and orientation won on turn 18.
     *
     * The guard is keyed to the proposed action object rather than to
     * isLandAbility(), so no other ordinary choice can reach it either; the plan
     * action covers the tutor plan and every *Plan.owns case, including
     * CubeDoomsdayPlan, which exposes no owns() accessor. The extra breakdown
     * diagnostic below is limited to land abilities because that is the only class
     * whose rejection is silently non-fatal in PlayerControllerAi and therefore the
     * only class whose disagreement needs naming. */
    @Override
    public boolean playChosenSpellAbility(SpellAbility ability) {
        if (ability == planAction && !CubeComboAi.canPlayNative(ability, getPlayer())) {
            guardRejections++;
            System.err.println("CUBE_COMBO native-legality-rejected card=" + ability.getHostCard().getName());
            return false;
        }
        if (ability != planAction && ability.isLandAbility() && !CubeComboAi.canPlayNative(ability, getPlayer())) {
            ordinaryGuardDisagreements++;
            Player me = getPlayer();
            Card host = ability.getHostCard();
            System.err.println("CUBE_ORDINARY_GUARD_DISAGREE card=" + host.getName()
                    + " zone=" + (host.getZone() == null ? "none" : host.getZone().getZoneType())
                    + " actor=" + (ability.getActivatingPlayer() == null ? "null" : "set")
                    + " mayPlay=" + (ability.getMayPlay() != null)
                    + " canPlay=" + ability.canPlay()
                    + " legalAfterStack=" + ability.isLegalAfterStack()
                    + " restrictions=" + ability.checkRestrictions(host, me)
                    + " canPlayLand=" + me.canPlayLand(host, false, ability)
                    + " landsPlayed=" + me.getLandsPlayedThisTurn() + "/" + me.getMaxLandPlays()
                    + " phase=" + getGame().getPhaseHandler().getPhase());
        }
        if (tutorPlan != null && tutorPlan.tutor() == ability) {
            boolean played = CubeComboAi.withReservedSources(getPlayer(), tutorPlan.reservedSources(),
                    () -> super.playChosenSpellAbility(ability));
            if (played) comboTutorPlanCasts++;
            System.err.println("CUBE_COMBO_TUTOR " + (played ? "played" : "native-payment-failed")
                    + " card=" + ability.getHostCard().getName() + " phase=" + getGame().getPhaseHandler().getPhase()
                    + " plannedPartner=" + tutorPlan.plannedPartner());
            tutorPlan = null;
            return played;
        }
        if (breachPlan.owns(ability)) return breachPlan.play(ability);
        if (stormPlan.owns(ability)) return stormPlan.play(ability);
        if (monolithPlan.owns(ability)) return monolithPlan.play(ability);
        if (kittenPlan.owns(ability)) return kittenPlan.play(ability);
        if (topPlan.owns(ability)) return topPlan.play(ability);
        if (thopterPlan.owns(ability)) return thopterPlan.play(ability);
        if (bombPlan.owns(ability)) return bombPlan.play(ability);
        return doomsdayPlan.withReservedDrawSource(ability, () -> super.playChosenSpellAbility(ability));
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility ability) {
        return kittenPlan.chooseBlink(ability) || breachPlan.chooseCopyTarget(ability) || super.chooseTargetsFor(ability);
    }

    public boolean chooseKittenBlink(SpellAbility ability) { return kittenPlan.chooseBlink(ability); }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility source,
            CardCollection choices, DelayedReveal delayedReveal, String prompt, boolean optional, Player decider) {
        if (decider == getPlayer() && destination == ZoneType.Library) {
            if (doomsdayPlan.ownsPileDecision(source)) {
                // Doomsday keeps priority on this API: no plan may steal another
                // plan's decision, so the search branch below is only reached
                // when the pile decision is not ours.
                if (delayedReveal != null) reveal(delayedReveal);
                Card selected = doomsdayPlan.choosePileCard(choices);
                if (selected != null) return selected;
            } else if (ownsSearchToTopSelection(source, origin, choices)) {
                if (delayedReveal != null) reveal(delayedReveal);
                Card partner = CubeComboAi.chooseTutorPartner(getPlayer(), source, new CardCollection(choices));
                if (partner != null) {
                    comboSelectionChanges++;
                    System.err.println("CUBE_COMBO_SELECTION changed-search-selection source=" + source.getHostCard().getName()
                            + " phase=" + getGame().getPhaseHandler().getPhase() + " partner=" + partner.getName());
                    return partner;
                }
            }
        } else if (destination == ZoneType.Battlefield && ownsPayloadChoice(source, origin, choices, decider)) {
            // v56. A hidden-origin ChangeZone to the battlefield picks its card
            // HERE, at resolution - which is why v55's bomb plan could see that
            // a different own-visible payload was lethal and could not make the
            // chooser take it. The Library branch above is untouched: a plan
            // never steals another plan's decision, and these two destinations
            // are disjoint.
            //
            // The ordinary answer is computed FIRST and kept unless the plan
            // actually prefers a different card. That is deliberate: the native
            // chooser shuffles the offered list, so asking it either way leaves
            // the random stream exactly as the matched control left it, and a
            // position where the plan agrees with it stays byte-identical.
            List<Card> options = new ArrayList<>(choices);
            Card ordinary = super.chooseSingleCardForZoneChange(destination, origin, source, choices, delayedReveal,
                    prompt, optional, decider);
            Card payload = bombPlan.choosePayload(source, options);
            if (payload != null && payload != ordinary) {
                System.err.println("CUBE_BOMB_PLAN payload=" + payload.getName().replace(' ', '_')
                        + " instead=" + (ordinary == null ? "none" : ordinary.getName().replace(' ', '_'))
                        + " source=" + source.getHostCard().getName().replace(' ', '_')
                        + " turn=" + getGame().getPhaseHandler().getTurn()
                        + " phase=" + getGame().getPhaseHandler().getPhase());
                return payload;
            }
            return ordinary;
        }
        return super.chooseSingleCardForZoneChange(destination, origin, source, choices, delayedReveal, prompt, optional, decider);
    }

    /** May the bomb plan answer this zone-change choice?
     *
     * <p>Every clause is a restriction, and all of them must hold: the chooser
     * is US, the resolving ability is OURS, the offered cards are OUR OWN cards
     * in OUR OWN hand, and the bomb plan proposed that very ability on this turn
     * ({@link CubeBombPlan#ownsPayloadChoice}). Show and Tell lets EACH player
     * put in a permanent; the opponent's half is decided by the opponent's own
     * controller with {@code decider} set to the opponent, so it never reaches
     * this class - and if it somehow did, the first clause refuses it. No
     * opponent hand, library or decklist is read here or in the plan.</p> */
    private boolean ownsPayloadChoice(SpellAbility source, List<ZoneType> origin, CardCollection choices, Player decider) {
        return decider == getPlayer() && source != null && source.getActivatingPlayer() == getPlayer()
                && origin != null && origin.size() == 1 && origin.contains(ZoneType.Hand)
                && choices != null && !choices.isEmpty()
                && choices.stream().allMatch(c -> c.getOwner() == getPlayer() && c.getController() == getPlayer()
                        && c.isInZone(ZoneType.Hand))
                && bombPlan.ownsPayloadChoice(source);
    }

    /** Our own search of our own library that writes the top of it - Imperial
     * Seal, Vampiric Tutor. The card is drawn on our next turn, so the
     * selection is the whole decision; v42 left it to the ordinary AI, which
     * fetches the most expensive valid card (the behaviour Forge's own
     * imperial_seal.txt documents). Restricted to LibraryPosition 0 because a
     * search to the bottom or to a random position tells us nothing we could
     * act on. The offered list is the only library information read, and every
     * card in it must be one of ours that the effect already lets us see. */
    private boolean ownsSearchToTopSelection(SpellAbility source, List<ZoneType> origin, CardCollection choices) {
        return source != null && source.getActivatingPlayer() == getPlayer()
                && source.getApi() == forge.game.ability.ApiType.ChangeZone
                && origin != null && origin.size() == 1 && origin.contains(ZoneType.Library)
                && "0".equals(source.getParam("LibraryPosition"))
                && choices != null && !choices.isEmpty()
                && choices.stream().allMatch(c -> c.getOwner() == getPlayer() && c.isInZone(ZoneType.Library));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destination, SpellAbility source) {
        if (destination == ZoneType.Library && source != null && doomsdayPlan.ownsPileDecision(source))
            return doomsdayPlan.orderPile(cards);
        CardCollectionView ordinary = super.orderMoveToZoneList(cards, destination, source);
        // RearrangeTopOfLibrary supplies the exact cards this effect permits
        // us to look at. Reuse the native tutor's feasible missing-piece choice;
        // never search an unrevealed library or inspect a future draw here.
        // Keep all other ordering decisions unchanged (including Doomsday).
        if (destination == ZoneType.Library && source != null
                && source.getApi() == forge.game.ability.ApiType.RearrangeTopOfLibrary
                && source.getActivatingPlayer() == getPlayer()
                && cards.stream().allMatch(c -> c.getOwner() == getPlayer() && c.isInZone(ZoneType.Library))) {
            Card partner = CubeComboAi.chooseTutorPartner(getPlayer(), source, new CardCollection(cards));
            if (partner != null) {
                CardCollection ordered = new CardCollection(ordinary);
                int top = orderedMoveToTopOfLibrary(destination, source) ? ordered.size() - 1 : 0;
                if (ordered.indexOf(partner) != top) {
                    ordered.remove(partner); ordered.add(top, partner);
                    comboSelectionChanges++;
                    System.err.println("CUBE_COMBO_SELECTION changed-revealed-order source=" + source.getHostCard().getName()
                            + " partner=" + partner.getName());
                    return ordered;
                }
            }
        }
        return ordinary;
    }

    /** v49 discard ownership. Which cards in our own hand a plan is currently
     * relying on, from the plans' own stage/gate predicates - never a card-name
     * rule and never an always-preserve rule:
     *
     * <ul>
     * <li>Doomsday: only while the plan is still holding a pile it built and
     *     waiting for the Oracle that pile put on top
     *     ({@link CubeDoomsdayPlan#discardProtectedCards}). This is the v47
     *     analysis's 16701482-s0 mode: our own looter drew the pile's Oracle
     *     and the ordinary discard policy pitched it, with no gate reading a
     *     hazard that was own-visible the whole time.</li>
     * <li>Breach and Storm: only the Breach-diagnosis C2 shape - the gate is
     *     already exactly one card short, and this hand card is the last copy
     *     of the OTHER half that the plan's own zone definitions can reach, so
     *     discarding it would put the gate two short. Deliberately NOT extended
     *     to "never discard a combo piece".</li>
     * </ul>
     *
     * Empty when no plan is in such a state, which is the common case: then
     * this class returns the ordinary AI's own choice object untouched. */
    private CardCollection reservedDiscardCards() {
        CardCollection reserved = new CardCollection();
        reserved.addAll(doomsdayPlan.discardProtectedCards());
        reserved.addAll(CubeBreachPlan.discardProtectedCards(getPlayer()));
        reserved.addAll(CubeStormPlan.discardProtectedCards(getPlayer()));
        return reserved;
    }

    /** Swap each reserved card out of the ordinary discard choice for a legal
     * alternative, keeping the choice's size exactly as the ordinary AI set it.
     * Only the CHOICE of what to discard is owned: the loot activation itself
     * is an ordinary decision and is never blocked, and where the discard is
     * forced - no unreserved valid card left to take instead - the reserved
     * card is kept in the choice and native rules proceed. */
    private CardCollection ownDiscardChoice(CardCollectionView validCards, CardCollectionView ordinary,
            CardCollection reserved, String source) {
        CardCollection result = new CardCollection();
        for (Card chosen : ordinary) {
            if (!reserved.contains(chosen)) { result.add(chosen); continue; }
            Card alternative = null;
            for (Card candidate : validCards) {
                if (reserved.contains(candidate) || result.contains(candidate) || ordinary.contains(candidate)) continue;
                alternative = candidate;
                break;
            }
            if (alternative == null) { result.add(chosen); continue; }
            result.add(alternative);
            comboDiscardSwaps++;
            System.err.println("CUBE_COMBO_DISCARD kept=" + chosen.getName() + " discarded=" + alternative.getName()
                    + " source=" + source + " phase=" + getGame().getPhaseHandler().getPhase());
        }
        return result;
    }

    /** Our own effect asking us which of our own cards to discard - a loot, a
     * Frantic Search, a rummage. The ordinary AI still makes the choice; this
     * only re-picks the cards a plan is relying on. Another player's effect and
     * another player's hand are never touched, so no opponent decision and no
     * hidden zone is read. */
    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max,
            CardCollectionView visibleToChooser) {
        CardCollection ordinary = super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
        if (p != getPlayer() || sa == null || sa.getActivatingPlayer() != getPlayer()
                || ordinary == null || ordinary.isEmpty() || validCards == null) return ordinary;
        CardCollection reserved = reservedDiscardCards();
        if (reserved.isEmpty()) return ordinary;
        return ownDiscardChoice(validCards, ordinary, reserved, sa.getHostCard().getName());
    }

    /** The cleanup-step discard to maximum hand size: our own choice over our
     * own hand, with no spell ability behind it. Same ownership, same limits. */
    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollectionView ordinary = super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        if (ordinary == null || ordinary.isEmpty()) return ordinary;
        CardCollection reserved = reservedDiscardCards();
        if (reserved.isEmpty()) return ordinary;
        return ownDiscardChoice(new CardCollection(getPlayer().getCardsIn(ZoneType.Hand)), ordinary, reserved, "cleanup");
    }

    @Override
    public boolean chooseBinary(SpellAbility ability, String question, BinaryChoiceType choice, Boolean defaultValue) {
        if (choice == BinaryChoiceType.TapOrUntap) {
            var source = CubeComboAi.untapSource(getPlayer(), ability);
            if (source != null && ability.getTargets().getTargetCards().contains(source)) return false;
        }
        return super.chooseBinary(ability, question, choice, defaultValue);
    }
}
