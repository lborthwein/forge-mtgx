package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.List;

/** Top-search assembly using own-visible resources and native search selection.
 * The library is never enumerated or inspected. Public library changes cancel
 * a remembered search result instead of assuming it is still the next draw. */
public final class CubeTopTutorPlan {
    private final Player player;
    private SpellAbility selected, tutor, draw;
    private CardCollection reserved = new CardCollection(), pieceReserved = new CardCollection();
    private String expected;
    private int turn = -1, failedTurn = -1, attempts;
    private boolean playedTutor, selectedPiece, disrupted;

    public CubeTopTutorPlan(Player player) {
        this.player = player;
        player.getGame().subscribeToEvents(this);
    }

    private boolean resolvingOwnedTutor() {
        var stack = player.getGame().getStack();
        return tutor != null && stack.isResolving(tutor.getHostCard())
                && stack.peekAbility() == tutor && tutor.getActivatingPlayer() == player;
    }
    @Subscribe public void shuffled(GameEventShuffle event) {
        if (playedTutor && event.player().getId() == player.getId() && !resolvingOwnedTutor()) disrupted = true;
    }
    @Subscribe public void libraryChanged(GameEventCardChangeZone event) {
        if (!playedTutor || resolvingOwnedTutor()) return;
        for (var zone : new forge.game.zone.ZoneView[]{event.from(), event.to()}) {
            if (zone != null && zone.zoneType() == ZoneType.Library && zone.player() != null
                    && zone.player().getId() == player.getId()) { disrupted = true; return; }
        }
    }

    /** Called only at the controller's actual native top-search selection. */
    void selectedFromSearch(SpellAbility source, Card choice) {
        if (playedTutor && source == tutor && resolvingOwnedTutor() && choice != null
                && choice.getOwner() == player && !choice.isFaceDown() && choice.isInZone(ZoneType.Library)
                // A granted identity must still be the native game object,
                // not a detached forecast or last-known-information copy.
                && player.getGame().getCardState(choice, null) == choice
                && choice.getName().equals(expected)) selectedPiece = true;
    }

    private void reset() {
        selected = tutor = draw = null; expected = null;
        reserved = new CardCollection(); pieceReserved = new CardCollection();
        playedTutor = selectedPiece = disrupted = false;
    }
    public SpellAbility nextAction() {
        var phases = player.getGame().getPhaseHandler();
        if (turn != phases.getTurn()) { reset(); turn = phases.getTurn(); attempts = 0; }
        if (!CubeComboAi.enabled(player) || failedTurn == turn || attempts >= 2
                || !player.getGame().getStack().isEmpty() || player.cantWin()
                || !(phases.is(PhaseType.MAIN1, player) || phases.is(PhaseType.MAIN2, player))) return null;
        if (playedTutor) {
            if (disrupted || !selectedPiece || draw == null || !safeDraw(draw)
                    || pieceReserved.stream().anyMatch(c -> c.getController() != player || !c.isInZone(ZoneType.Battlefield) || c.isTapped())
                    || !CubeComboAi.withReservedSources(player, pieceReserved,
                            () -> CubeComboAi.canPayCost(draw, player, false))) {
                failedTurn = turn; reset(); return null;
            }
            selected = draw; return selected;
        }
        if (CubeComboAi.hasImmediateKikiRoute(player) || !player.getManaPool().isEmpty()) return null;
        // Existing native family and hand-tutor actions are consulted first.
        for (Card hand : player.getCardsIn(ZoneType.Hand)) {
            if (hand.isFaceDown()) continue;
            for (SpellAbility original : hand.getSpellAbilities()) {
                var action = original.copy(player);
                if (!topSearch(action) || !safeTutor(action) || !player.canSearchLibraryWith(action, player)) continue;
                SpellAbility immediate = drawFromHand();
                if (immediate == null && !phases.is(PhaseType.MAIN2, player)) continue;
                var forecast = CubeComboAi.topTutorForecast(player, action, immediate);
                if (forecast == null) continue;
                tutor = selected = action; draw = immediate; expected = forecast.partner();
                reserved = forecast.allReserved(); pieceReserved = forecast.pieceReserved();
                return selected;
            }
        }
        return null;
    }
    private static boolean topSearch(SpellAbility sa) {
        return sa.isSpell() && sa.getApi() == ApiType.ChangeZone && !sa.usesTargeting()
                && "Library".equals(sa.getParam("Origin")) && "Library".equals(sa.getParam("Destination"))
                && "0".equals(sa.getParamOrDefault("LibraryPosition", "0"))
                && "1".equals(sa.getParamOrDefault("ChangeNum", "1"))
                && "You".equals(sa.getParamOrDefault("Defined", "You"))
                && !sa.getParamOrDefault("ChangeType", "Card").startsWith("EACH")
                && CubeComboAi.manaOnly(sa);
    }
    private boolean safeTutor(SpellAbility sa) {
        int lifeLoss = 0;
        for (SpellAbility sub = sa.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            if (sub.getApi() != ApiType.LoseLife || sub.usesTargeting()
                    || !"You".equals(sub.getParamOrDefault("Defined", "You"))
                    || !sub.getParamOrDefault("LifeAmount", "").matches("[0-9]+")) return false;
            lifeLoss += Integer.parseInt(sub.getParam("LifeAmount"));
        }
        if (player.canLoseLife() && !player.cantLoseForZeroOrLessLife()
                && player.getLife() <= lifeLoss + ComputerUtil.getDamageForPlaying(player, sa)) return false;
        // Unmodelled public replacements must not make a promised resource
        // or life payment appear safe. Actual native effects remain final.
        for (Card card : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (var replacement : card.getReplacementEffects())
                if (!replacement.isSuppressed() && ("LoseLife".equals(replacement.getParam("Event"))
                        || "Draw".equals(replacement.getParam("Event")) || "DrawCards".equals(replacement.getParam("Event")))) return false;
        }
        return CubeComboAi.canPlayNative(sa, player) && CubeComboAi.canPayCost(sa, player, false);
    }
    private SpellAbility drawFromHand() {
        for (String name : List.of("Ponder", "Preordain", "Brainstorm", "Gitaxian Probe", "Ancestral Recall"))
            for (Card card : player.getCardsIn(ZoneType.Hand)) {
                if (card.isFaceDown() || !card.getName().equals(name)) continue;
                for (var original : card.getSpellAbilities()) {
                    var action = original.copy(player);
                    if (action.usesTargeting()) {
                        if (!action.canTarget(player)) continue;
                        action.resetTargets(); action.getTargets().add(player);
                    }
                    if (safeDraw(action)) return action;
                }
            }
        return null;
    }
    private boolean safeDraw(SpellAbility action) {
        if (action == null || !action.isSpell() || action.getHostCard().getOwner() != player
                || !action.getHostCard().isInZone(ZoneType.Hand) || !player.canDraw()) return false;
        int amount = List.of("Brainstorm", "Ancestral Recall").contains(action.getHostCard().getName()) ? 3 : 1;
        return player.canDrawAmount(amount) && player.getCardsIn(ZoneType.Library).size() >= amount
                && CubeComboAi.canPlayNative(action, player) && CubeComboAi.canPayCost(action, player, false);
    }
    boolean owns(SpellAbility sa) { return sa != null && sa == selected; }
    boolean waitingForOwnSpell() {
        return selected != null && !player.getGame().getStack().isEmpty()
                && player.getGame().getStack().peekAbility() == selected;
    }
    boolean play(SpellAbility sa) {
        if (!owns(sa)) return false;
        boolean searching = sa == tutor;
        // Arm before native execution: the actual search callback owns the
        // result even if an engine path resolves synchronously.
        if (searching) { playedTutor = true; selectedPiece = false; disrupted = false; }
        boolean played = CubeComboAi.withReservedSources(player, searching ? reserved : pieceReserved,
                () -> ComputerUtil.handlePlayingSpellAbility(player, sa, null,
                        current -> new AiCostDecision(player, current, false)));
        attempts++;
        System.err.println("CUBE_TOP_TUTOR " + (played ? "played" : "native-payment-failed")
                + " card=" + sa.getHostCard().getName().replace(' ', '_') + " partner=" + expected);
        if (!played || !searching) { failedTurn = turn; reset(); }
        return played;
    }
}
