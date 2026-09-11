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
    private int comboSelectionChanges;
    private CubeComboAi.TutorPlan tutorPlan;
    private int comboTutorPlanCasts;
    /** Observability state only: rate limits for the stderr decision log. */
    private static final int DECISION_LINE_CAP = 40;
    private int decisionTurn = -1, decisionLines;
    private PhaseType decisionPhase;
    private boolean declinedDoomsday, declinedTutor;
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
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (doomsdayPlan.waitingForOwnSpell() || breachPlan.waitingForOwnSpell() || stormPlan.waitingForOwnSpell() || monolithPlan.waitingForOwnSpell() || kittenPlan.waitingForOwnSpell() || topPlan.waitingForOwnSpell() || thopterPlan.waitingForOwnSpell()) return null;
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
        if (action == null) {
            tutorConsulted = true;
            tutorPlan = CubeComboAi.planTutor(getPlayer());
            if (tutorPlan != null) {
                action = tutorPlan.tutor();
                if (action != null) plan = "tutor";
            }
        }
        List<SpellAbility> chosen = action == null ? super.chooseSpellAbilityToPlay() : List.of(action);
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

    @Override
    public boolean playChosenSpellAbility(SpellAbility ability) {
        if (!CubeComboAi.canPlayNative(ability, getPlayer())) {
            System.err.println("CUBE_COMBO native-legality-rejected card=" + ability.getHostCard().getName());
            return false;
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
        if (decider == getPlayer() && destination == ZoneType.Library && doomsdayPlan.ownsPileDecision(source)) {
            if (delayedReveal != null) reveal(delayedReveal);
            Card selected = doomsdayPlan.choosePileCard(choices);
            if (selected != null) return selected;
        }
        return super.chooseSingleCardForZoneChange(destination, origin, source, choices, delayedReveal, prompt, optional, decider);
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

    @Override
    public boolean chooseBinary(SpellAbility ability, String question, BinaryChoiceType choice, Boolean defaultValue) {
        if (choice == BinaryChoiceType.TapOrUntap) {
            var source = CubeComboAi.untapSource(getPlayer(), ability);
            if (source != null && ability.getTargets().getTargetCards().contains(source)) return false;
        }
        return super.chooseBinary(ability, question, choice, defaultValue);
    }
}
