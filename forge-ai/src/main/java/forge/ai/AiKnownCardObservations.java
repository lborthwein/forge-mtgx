package forge.ai;

import forge.game.GameRules;
import forge.game.card.*;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.IdentityHashMap;

/** Observation input for repaired AI paths. Never an opponent submitted list.
 * Hand knowledge is captured only by an actual reveal callback, as an LKI
 * snapshot bound to that hand visit. It survives strategic end-turn resets.
 * This is not an enforcement boundary for the rest of Forge's unrestricted AI.
 */
public final class AiKnownCardObservations {
    private record HandFact(Player owner,long timestamp,Card snapshot) {}
    private final Player viewer;
    private final IdentityHashMap<Card,HandFact> hand = new IdentityHashMap<>();
    AiKnownCardObservations(Player viewer) { this.viewer = viewer; }
    public static boolean enabled(Player viewer) {
        return viewer.getGame().getRules().getAiInformationPolicy() == GameRules.AiInformationPolicy.CLOSED_REPAIR;
    }
    void rememberHand(Iterable<Card> cards,Player owner) {
        if (!enabled(viewer)) return;
        if (owner == null || owner.getGame() != viewer.getGame()) throw new IllegalArgumentException("Foreign hand reveal");
        for (Card card : cards) {
            if (card == null || card.getOwner() != owner || card.getGame() != viewer.getGame()
                    || !card.isInZone(ZoneType.Hand) || card.isFaceDown()
                    || owner.getCardsIn(ZoneType.Hand).stream().noneMatch(c -> c == card))
                throw new IllegalArgumentException("Invalid revealed hand visit");
            hand.put(card,new HandFact(owner,card.getGameTimestamp(),CardCopyService.getLKICopy(card)));
        }
    }
    private Card remembered(Card card) {
        HandFact fact = hand.get(card);
        if (fact == null) return null;
        if (card.getOwner() != fact.owner || !card.isInZone(ZoneType.Hand)
                || card.getGameTimestamp() != fact.timestamp
                || fact.owner.getCardsIn(ZoneType.Hand).stream().noneMatch(c -> c == card)) {
            hand.remove(card); return null;
        }
        return fact.snapshot;
    }
    public static CardCollection cardsKnownTo(Player viewer,Player owner) {
        if (!enabled(viewer)) return new CardCollection(owner.getAllCards());
        if (owner.getGame() != viewer.getGame()) throw new IllegalArgumentException("Foreign observation owner");
        final AiKnownCardObservations knowledge = viewer.getController() instanceof PlayerControllerAi ai ? ai.knownCardObservations() : null;
        var result = new CardCollection();
        for (Card card : owner.getAllCards()) {
            // No face/ability reads before visibility or a legitimate snapshot.
            if (card.getView().canBeShownTo(viewer.getView())) result.add(card);
            else if (knowledge != null) {
                Card observed = knowledge.remembered(card);
                if (observed != null) result.add(observed);
            }
        }
        return result;
    }
}
