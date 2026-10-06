package forge.game.ability.effects;

import com.google.common.collect.Lists;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.event.GameEventRandomLog;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.Localizer;

public class CleanUpEffect extends SpellAbilityEffect {

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellEffect#resolve(java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    public void resolve(SpellAbility sa) {
        Card source;
        if (sa.hasParam("Defined")) {
            source = getDefinedCardsOrTargeted(sa).get(0);
        } else {
            source = sa.getHostCard();
        }
        final Game game = source.getGame();

        String logMessage = "";
        if (sa.hasParam("Log")) {
            logMessage = logOutput(sa, source);
        }

        if (sa.hasParam("ClearRemembered")) {
            source.clearRemembered();
            final Card current = game.getCardState(source);
            // mtgx: an older object's ability (the last known information of a card that left the battlefield) must
            // not clear what a NEWER battlefield object of the same card remembers (CR 400.7). A Spell Queller blinked
            // by Displacer Kitten: the new object's enter trigger exiles a spell first, then the old object's leaves
            // trigger cleans up, and used to erase the new object's link to that card, so it stayed exiled for good.
            if (current == source || !current.isInPlay() || current.equalsWithGameTimestamp(source)) {
                current.clearRemembered();
            }
        }
        if (sa.hasParam("ForgetDefined")) {
            for (final GameEntity ge : AbilityUtils.getDefinedEntities(source, sa.getParam("ForgetDefined"), sa)) {
                source.removeRemembered(ge);
            }
        }
        if (sa.hasParam("ClearImprinted")) {
            source.clearImprintedCards();
            game.getCardState(source).clearImprintedCards();
        }
        if (sa.hasParam("ClearTriggered")) {
            game.getTriggerHandler().clearDelayedTrigger(source);
        }
        if (sa.hasParam("ClearCoinFlips")) {
            source.clearFlipResult();
        }
        if (sa.hasParam("ClearChosenCard")) {
            source.setChosenCards(null);
        }
        if (sa.hasParam("ClearChosenPlayer")) {
            source.setChosenPlayer(null);
        }
        if (sa.hasParam("ClearChosenType")) {
            source.setChosenType("");
            source.setChosenType2("");
        }
        if (sa.hasParam("ClearChosenColor")) {
            source.setChosenColors(null);
        }
        if (sa.hasParam("ClearNamedCard")) {
            source.setNamedCards(Lists.newArrayList());
        }
        if (sa.hasParam("Log")) {
            source.getController().getGame().fireEvent(new GameEventRandomLog(logMessage));
        }
    }

    protected String logOutput(SpellAbility sa, Card source) {
        final StringBuilder log = new StringBuilder();
        final String name = source.getTranslatedName();
        String linebreak = "\r\n";

        if (sa.hasParam("ClearRemembered") && source.getRememberedCount() != 0) {
            for (Object o : source.getRemembered()) {
                String rem = o.toString();
                if (o instanceof Card) {
                    log.append(log.length() > 0 ? linebreak : "");
                    log.append(Localizer.getInstance().getMessage("lblChosenCard", name, rem));
                } else if (o instanceof Player) {
                    log.append(log.length() > 0 ? linebreak : "");
                    log.append(Localizer.getInstance().getMessage("lblChosenPlayer", name, rem));
                }
            }
        }

        String chCard = sa.hasParam("ClearChosenCard") && source.hasChosenCard() ? source.getChosenCards()
                .toString().replace("[","").replace("]", "") : "";
        if (chCard.length() > 0 && !log.toString().contains(chCard)) {
            log.append(log.length() > 0 ? linebreak : "");
            String message = source.getChosenCards().size() > 1 ? "lblChosenMultiCard" : "lblChosenCard";
            log.append(Localizer.getInstance().getMessage(message, name, chCard));
        }

        String chPlay = sa.hasParam("ClearChosenPlayer") && source.hasChosenPlayer()
                ? source.getChosenPlayer().toString() : "";
        if (chPlay.length() > 0 && !log.toString().contains(chPlay)) {
            log.append(log.length() > 0 ? linebreak : "");
            log.append(Localizer.getInstance().getMessage("lblChosenPlayer", name, chPlay));
        }
        log.append(log.length() > 0 ? "" : Localizer.getInstance().getMessage("lblNoValidChoice", name));

        return log.toString();
    }
}
