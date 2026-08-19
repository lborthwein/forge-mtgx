/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gamemodes.limited;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Maps;

import forge.item.PaperCard;
import forge.item.SealedTemplate;
import forge.item.generation.UnOpenedProduct;
import forge.model.FModel;
import forge.util.ItemPool;

/**
 * A {@link BoosterDraft} with no human seat and no GUI, for the mtgx benchmark.
 *
 * <p>{@code BoosterDraft.setupCustomDraft} is private and the {@code Custom} pool type
 * reaches for {@code SGuiChoose} to pick a cube off disk, so this replicates the product
 * setup against a {@link CustomLimited} the caller has already built in memory. Lives in
 * this package because {@code BoosterDraft}'s constructors and its {@code product} list
 * are protected.
 *
 * <p>Usage: construct, then {@link #runToCompletion()}, then {@link #buildDecks()}.
 */
public class HeadlessCubeDraft extends BoosterDraft {

    private final Map<Integer, List<PaperCard>> picksInOrder = Maps.newLinkedHashMap();

    public HeadlessCubeDraft(final CustomLimited cube) {
        super(LimitedPoolType.Custom, clampPlayers(cube.getNumPlayers()));

        final ItemPool<PaperCard> dPool = cube.getCardPool();
        if (dPool == null || dPool.isEmpty()) {
            throw new IllegalArgumentException("HeadlessCubeDraft: empty card pool");
        }
        final SealedTemplate tpl = cube.getSealedProductTemplate();
        final UnOpenedProduct toAdd = new UnOpenedProduct(tpl, dPool);
        // A cube is singleton: the booster generator must respect pool multiplicities.
        toAdd.setLimitedPool(true);
        for (int i = 0; i < cube.getNumPacks(); i++) {
            this.product.add(toAdd);
        }

        IBoosterDraft.LAND_SET_CODE[0] = FModel.getMagicDb().getEditions().get(cube.getLandSetCode());
        IBoosterDraft.CUSTOM_RANKINGS_FILE[0] = cube.getCustomRankingsFileName();

        // No human seat: every seat, including seat 0, drafts with LimitedPlayerAI.
        setHumanSeats(Collections.emptySet());
        initializeBoosters();

        for (LimitedPlayer pl : getAllPlayers()) {
            picksInOrder.put(pl.order, new java.util.ArrayList<>());
        }
    }

    private static int clampPlayers(final int n) {
        if (n < 2) {
            return 8;
        }
        return Math.min(n, 8);
    }

    /**
     * Drive every seat through every pack.
     *
     * <p>Deliberately iterates {@link #getAllPlayers()}: {@code getPlayer(int)} is
     * off-by-one for a headless draft (it special-cases seat 0 to the local player and
     * then indexes the AI list from 1), and {@code getComputerDecks()} skips seat 0
     * entirely.
     */
    public void runToCompletion() {
        int guard = 0;
        final int maxSteps = 64 * getPodSize();
        while (guard++ < maxSteps) {
            for (LimitedPlayer pl : getAllPlayers()) {
                if (pl.shouldSkipThisPick()) {
                    continue;
                }
                Boolean passPack;
                do {
                    final PaperCard pick = pl.chooseCard();
                    if (pick != null) {
                        picksInOrder.get(pl.order).add(pick);
                    }
                    passPack = pl.draftCard(pick);
                } while (passPack != null && !passPack);
            }
            passPacks();
            if (isRoundOver() && !startRound()) {
                break;
            }
        }
        postDraftActions();
    }

    /** Pick order for one seat (index into {@link #getAllPlayers()}). */
    public List<PaperCard> getPicks(final int seat) {
        return picksInOrder.getOrDefault(seat, Collections.emptyList());
    }

    /** Run Forge's limited deck builder for every seat, in seat order. */
    public forge.deck.Deck[] buildDecks() {
        final String landSetCode = IBoosterDraft.LAND_SET_CODE[0] != null
                ? IBoosterDraft.LAND_SET_CODE[0].getCode() : null;
        final List<LimitedPlayer> players = getAllPlayers();
        final forge.deck.Deck[] decks = new forge.deck.Deck[players.size()];
        for (int i = 0; i < players.size(); i++) {
            decks[i] = ((LimitedPlayerAI) players.get(i)).buildDeck(landSetCode);
        }
        return decks;
    }
}
