package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.cost.CostReturn;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.function.Supplier;

/** Native Doomsday plans using Recall or Chromatic Star/Gush to reach Oracle. The search and
 * ordering hooks see only choices the resolving spell legally exposes. Other
 * piles are not yet supported by this planner. */
public final class CubeDoomsdayPlan {
    private enum Stage { NONE, DOOMSDAY, STAR, DRAW, ORACLE }
    private final Player player;
    private Stage stage = Stage.NONE;
    private int turn = -1, doomsdayId = -1;
    private boolean oracleSelected, gushSelected, gushRoute;
    private Card reservedStar;

    public CubeDoomsdayPlan(Player player) { this.player = player; }

    private Card inHand(String name) {
        for (Card card : player.getCardsIn(ZoneType.Hand)) if (card.getName().equals(name)) return card;
        return null;
    }

    private SpellAbility playable(String name) {
        Card card = inHand(name);
        if (card == null) return null;
        for (SpellAbility original : card.getSpellAbilities()) {
            if (!original.isSpell()) continue;
            SpellAbility spell = original.copy(player);
            if (!CubeComboAi.canPlayNative(spell, player) || !CubeComboAi.canPayCost(spell, player, false)) continue;
            if (name.equals("Ancestral Recall")) {
                if (!spell.canTarget(player)) continue;
                spell.resetTargets();
                spell.getTargets().add(player);
                if (!spell.isTargetNumberValid()) continue;
            }
            return spell;
        }
        return null;
    }

    private boolean availableInOwnDeck(String name) {
        if (inHand(name) != null) return true;
        for (Card card : player.getCardsIn(ZoneType.Graveyard))
            if (!card.isFaceDown() && card.getName().equals(name)) return true;
        // Doomsday can search our graveyard, unlike ordinary draw recovery.
        return CubeComboAi.ownCopyOutside(player, name,
                ZoneType.Battlefield, ZoneType.Exile, ZoneType.Command, ZoneType.Stack);
    }

    /** Reserve the pile's draw source during native cost planning/payment only.
     * Preserve any pre-existing Default reservation; never clear another plan's memory. */
    public <T> T withReservedDrawSource(SpellAbility spell, Supplier<T> action) {
        if (stage != Stage.DOOMSDAY || !spell.getHostCard().getName().equals("Doomsday")) return action.get();
        return withStarReserved(action);
    }

    private <T> T withStarReserved(Supplier<T> action) {
        if (!gushRoute || reservedStar == null) return action.get();
        var memory = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        boolean already = AiCardMemory.isRememberedCard(player, reservedStar, memory);
        if (!already) AiCardMemory.rememberCard(player, reservedStar, memory);
        try { return action.get(); }
        finally { if (!already) AiCardMemory.forgetCard(player, reservedStar, memory); }
    }

    private SpellAbility starAbility() {
        if (reservedStar == null || !reservedStar.isInPlay() || reservedStar.getController() != player) return null;
        for (SpellAbility original : reservedStar.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (CubeComboAi.canPlayNative(ability, player) && CubeComboAi.canPayCost(ability, player, false)) {
                ability.setManaExpressChoice(ColorSet.fromMask(MagicColor.BLUE));
                return ability;
            }
        }
        return null;
    }

    private SpellAbility alternateGush() {
        Card card = inHand("Gush");
        if (card == null) return null;
        for (SpellAbility original : card.getSpellAbilities()) {
            if (!original.isSpell()) continue;
            for (SpellAbility alternative : GameActionUtil.getAlternativeCosts(original.copy(player), player, false, true)) {
                if (alternative.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostReturn)
                        && CubeComboAi.canPlayNative(alternative, player) && CubeComboAi.canPayCost(alternative, player, false)) return alternative;
            }
        }
        return null;
    }

    /** Float Oracle's blue before Gush returns Islands. Select native, presently
     * payable zero-mana activations, not virtual mana or a forged cost receipt. */
    private SpellAbility floatBlue() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card == reservedStar) continue;
            for (SpellAbility original : card.getManaAbilities()) {
                SpellAbility ability = original.copy(player);
                if (ability.getPayCosts().getTotalMana().getCMC() != 0 || !CubeComboAi.canPlayNative(ability, player)
                        || ability.getManaPart() == null || !ability.canProduce("U")
                        || !ability.getPayCosts().getCostParts().stream().allMatch(p -> p instanceof forge.game.cost.CostTap
                            || p instanceof forge.game.cost.CostPartMana
                            || p instanceof forge.game.cost.CostSacrifice sacrifice && sacrifice.getType().equals("CARDNAME"))
                        || !CubeComboAi.canPayCost(ability, player, false)) continue;
                ability.setManaExpressChoice(ColorSet.fromMask(MagicColor.BLUE));
                return ability;
            }
        }
        return null;
    }

    /** Unlike a Doomsday search, drawing cannot recover a graveyard copy. */
    private boolean reachableByDrawing(String name) {
        if (inHand(name) != null) return true;
        return CubeComboAi.ownCopyOutside(player, name, UNAVAILABLE_DRAW_ZONES);
    }

    private static final ZoneType[] UNAVAILABLE_DRAW_ZONES = {
        ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Stack
    };

    private SpellAbility advanceGush(int librarySize) {
        SpellAbility gush = librarySize >= 2 && librarySize <= 4 && player.canDrawAmount(2) ? alternateGush() : null;
        if (gush == null) { stage = Stage.NONE; return null; }
        if (player.getManaPool().getAmountOfColor(MagicColor.BLUE) < 2) {
            SpellAbility mana = floatBlue();
            if (mana == null) stage = Stage.NONE;
            return mana;
        }
        stage = Stage.DRAW;
        return gush;
    }

    /** Recover from public resources without pretending to remember a pile.
     * A draw is speculative until it reveals the next piece in our hand. Never
     * assume Gush/Oracle is on top merely because the library has five cards. */
    private SpellAbility recoverSmallLibrary(int librarySize) {
        if (librarySize > 5 || !reachableByDrawing("Thassa's Oracle")) return null;
        gushRoute = false;
        reservedStar = null;
        SpellAbility oracle = librarySize <= 2 ? playable("Thassa's Oracle") : null;
        if (oracle != null) { stage = Stage.ORACLE; return oracle; }
        SpellAbility recall = librarySize >= 3 && player.canDrawAmount(3) ? playable("Ancestral Recall") : null;
        if (recall != null && CubeComboAi.canPayCost(new Cost("U U U", false), recall, player, false)) {
            stage = Stage.DRAW;
            return recall;
        }
        SpellAbility gush = librarySize >= 2 && librarySize <= 4 && player.canDrawAmount(2) ? alternateGush() : null;
        if (gush != null && CubeComboAi.canPayCost(new Cost("U U", false), gush, player, false)) {
            gushRoute = true;
            stage = Stage.STAR;
            return advanceGush(librarySize);
        }
        if (librarySize != 5 || !player.canDrawAmount(3) || !reachableByDrawing("Gush")
                || player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getType().hasSubtype("Island")).count() < 2)
            return null;
        gushRoute = true;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (!card.getName().equals("Chromatic Star")) continue;
            reservedStar = card;
            SpellAbility star = starAbility();
            if (star != null && withStarReserved(() -> CubeComboAi.canPayCost(new Cost("U U", false), star, player, false))) {
                stage = Stage.STAR;
                return star;
            }
        }
        gushRoute = false;
        reservedStar = null;
        return null;
    }

    /** Don't spend the plan's draw in response to our own unresolved combo spell.
     * An opposing spell/trigger still goes to Default's response policy. */
    public boolean waitingForOwnSpell() {
        if (stage == Stage.NONE || player.getGame().getStack().isEmpty()) return false;
        SpellAbility top = player.getGame().getStack().peekAbility();
        return top.getActivatingPlayer() == player && ((stage == Stage.DOOMSDAY && top.getHostCard().getId() == doomsdayId)
                || stage == Stage.STAR && reservedStar != null && top.getHostCard().getId() == reservedStar.getId()
                || stage == Stage.DRAW && top.getHostCard().getName().equals(gushRoute ? "Gush" : "Ancestral Recall")
                || stage == Stage.ORACLE && top.getHostCard().getName().equals("Thassa's Oracle"));
    }

    public SpellAbility nextAction() {
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)
                && !player.getGame().getPhaseHandler().is(PhaseType.MAIN2, player)) { stage = Stage.NONE; return null; }
        if (turn != player.getGame().getPhaseHandler().getTurn()) stage = Stage.NONE;
        if (!player.getGame().getStack().isEmpty()) return null;
        if (player.cantWin()) { stage = Stage.NONE; return null; }
        int librarySize = player.getCardsIn(ZoneType.Library).size(); // count, never identities/order
        if (stage == Stage.DOOMSDAY) {
            if (librarySize > 5 || inHand("Doomsday") != null || inHand("Thassa's Oracle") == null && !oracleSelected) {
                stage = Stage.NONE; return null;
            }
            if (gushRoute) {
                SpellAbility star = player.canDrawAmount(3) && librarySize == 5 ? starAbility() : null;
                if (star != null) { stage = Stage.STAR; return star; }
                stage = Stage.NONE; return null;
            }
            SpellAbility draw = playable("Ancestral Recall");
            if (librarySize >= 3 && player.canDrawAmount(3) && draw != null
                    && CubeComboAi.canPayCost(new Cost("U U U", false), draw, player, false)) {
                stage = Stage.DRAW; return draw;
            }
            stage = Stage.NONE; return null;
        }
        if (stage == Stage.STAR) {
            return advanceGush(librarySize);
        }
        if (stage == Stage.DRAW) {
            SpellAbility oracle = librarySize <= 2 ? playable("Thassa's Oracle") : null;
            stage = oracle == null ? Stage.NONE : Stage.ORACLE;
            return oracle;
        }
        if (stage == Stage.ORACLE) { stage = Stage.NONE; return null; }
        if (librarySize <= 5) {
            SpellAbility recovery = recoverSmallLibrary(librarySize);
            if (recovery != null) {
                turn = player.getGame().getPhaseHandler().getTurn();
                System.err.println("CUBE_COMBO recovered-small-library card=" + recovery.getHostCard().getName());
            }
            return recovery;
        }
        // No unnecessary Doomsday on a library already small enough for the draw.
        if (player.getLife() <= 1 || !player.canDrawAmount(3)
                || !availableInOwnDeck("Thassa's Oracle")) return null;
        gushRoute = false; reservedStar = null;
        SpellAbility doom = playable("Doomsday");
        if (doom == null) return null;
        if (inHand("Ancestral Recall") == null
                || !CubeComboAi.canPayCost(new Cost("B B B U U U", false), doom, player, false)) {
            if (!availableInOwnDeck("Gush") || player.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> c.getType().hasSubtype("Island")).count() < 2) return null;
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) if (card.getName().equals("Chromatic Star")) {
                reservedStar = card;
                if (starAbility() != null) break;
                reservedStar = null;
            }
            if (reservedStar == null) return null;
            gushRoute = true;
            if (!withStarReserved(() -> CubeComboAi.canPayCost(new Cost("B B B U U", false), doom, player, false))) {
                gushRoute = false; reservedStar = null; return null;
            }
        }
        turn = player.getGame().getPhaseHandler().getTurn();
        doomsdayId = doom.getHostCard().getId();
        oracleSelected = false;
        gushSelected = false;
        stage = Stage.DOOMSDAY;
        return doom;
    }

    public boolean ownsPileDecision(SpellAbility source) {
        return stage == Stage.DOOMSDAY && source.getHostCard().getId() == doomsdayId;
    }

    public Card choosePileCard(CardCollection legalChoices) {
        if (gushRoute && inHand("Gush") == null && !gushSelected) {
            for (Card card : legalChoices) if (card.getName().equals("Gush")) { gushSelected = true; return card; }
            stage = Stage.NONE; return null;
        }
        if (inHand("Thassa's Oracle") == null && !oracleSelected) {
            for (Card card : legalChoices) if (card.getName().equals("Thassa's Oracle")) {
                oracleSelected = true;
                return card;
            }
            // Search disproved the pre-search deck-composition belief. Do not
            // manufacture Oracle or label the resulting game a combo success.
            stage = Stage.NONE;
            return null;
        }
        return legalChoices.isEmpty() ? null : legalChoices.get(0);
    }

    public CardCollectionView orderPile(CardCollectionView revealedCards) {
        CardCollection moveOrder = new CardCollection();
        for (Card card : revealedCards) {
            if (!card.getName().equals("Thassa's Oracle") && !(gushRoute && card.getName().equals("Gush"))) moveOrder.add(card);
        }
        // Native RearrangeTopOfLibrary inserts each card at index zero: the
        // final card in the returned move order is the top of the library.
        for (Card card : revealedCards) if (card.getName().equals("Thassa's Oracle")) moveOrder.add(card);
        if (gushRoute) for (Card card : revealedCards) if (card.getName().equals("Gush")) moveOrder.add(card);
        return moveOrder;
    }
}
