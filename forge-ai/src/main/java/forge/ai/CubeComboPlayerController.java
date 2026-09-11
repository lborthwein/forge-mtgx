package forge.ai;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.List;

/** Explicit per-player opt-in; the opposing Default controller is unchanged. */
public final class CubeComboPlayerController extends PlayerControllerAi {
    private final CubeDoomsdayPlan doomsdayPlan;
    private final CubeBreachPlan breachPlan;
    private final CubeStormPlan stormPlan;
    private final CubeMonolithPlan monolithPlan;
    private final CubeKittenPlan kittenPlan;
    private final CubeTopPlan topPlan;
    private int comboSelectionChanges;
    private CubeComboAi.TutorPlan tutorPlan;
    private int comboTutorPlanCasts;
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
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (doomsdayPlan.waitingForOwnSpell() || breachPlan.waitingForOwnSpell() || stormPlan.waitingForOwnSpell() || monolithPlan.waitingForOwnSpell() || kittenPlan.waitingForOwnSpell() || topPlan.waitingForOwnSpell()) return null;
        SpellAbility action = doomsdayPlan.nextAction();
        if (action == null) action = breachPlan.nextAction();
        if (action == null) action = stormPlan.nextAction();
        if (action == null) action = monolithPlan.nextAction();
        if (action == null) action = kittenPlan.nextAction();
        if (action == null) action = topPlan.nextAction();
        if (action == null) {
            tutorPlan = CubeComboAi.planTutor(getPlayer());
            if (tutorPlan != null) action = tutorPlan.tutor();
        }
        return action == null ? super.chooseSpellAbilityToPlay() : List.of(action);
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
