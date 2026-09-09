package forge.game.mulligan;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class LondonMulligan extends AbstractMulligan {
    public LondonMulligan(Player p, boolean firstMullFree) {
        super(p, firstMullFree);
    }

    @Override
    public boolean canMulligan() {
        if (player.getController().defersLondonMulliganTuckUntilKeep()) {
            // This flow retains a full hand until Keep, so hand emptiness cannot
            // terminate redraws. Stop when the eventual kept hand would be zero.
            return !kept && tuckCardsDuringMulligan() < player.getMaxHandSize();
        }
        return !kept && tuckCardsDuringMulligan() <= player.getMaxHandSize();
    }

    @Override
    public int handSizeAfterNextMulligan() {
        return player.getMaxHandSize();
    }

    @Override
    public void mulliganDraw() {
        player.drawCards(handSizeAfterNextMulligan());
        if (!player.getController().defersLondonMulliganTuckUntilKeep()) {
            tuckCards();
        }
    }

    @Override
    public void keep() {
        if (!kept && player.getController().defersLondonMulliganTuckUntilKeep()
                && tuckCardsDuringMulligan() > 0) {
            tuckCards();
        }
        super.keep();
    }

    private void tuckCards() {
        int tuckingCards = tuckCardsDuringMulligan();
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));

        for (final Card c : player.getController().tuckCardsViaMulligan(hand, tuckingCards)) {
            player.getGame().getAction().moveToLibrary(c, -1, null);
        }
    }

    @Override
    public int tuckCardsDuringMulligan() {
        if (timesMulliganed == 0) {
            return 0;
        }

        int extraCard = firstMulliganFree ? 1 : 0;
        return timesMulliganed - extraCard;
    }
}
