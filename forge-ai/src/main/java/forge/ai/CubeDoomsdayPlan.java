package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostReturn;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import java.util.function.Supplier;

/** Native Doomsday plans using Recall, Star/Gush, Gush with public devotion, or
 * Doomsday's own BBB plus public devotion alone, to reach Oracle. The search and
 * ordering hooks see only choices the resolving spell legally exposes. Other
 * piles are not yet supported by this planner. */
public final class CubeDoomsdayPlan {
    private enum Stage { NONE, DOOMSDAY, STAR, DRAW, ORACLE }
    private final Player player;
    private Stage stage = Stage.NONE;
    private int turn = -1, doomsdayId = -1;
    private boolean oracleSelected, gushSelected, gushRoute;
    private Card reservedStar;
    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code no-doomsday-in-hand},
     * {@code mana:<cost>/<have>} where {@code <cost>} is {@code BBB} for
     * Doomsday's own printed cost measured against own-visible black sources
     * and {@code 5} is the Gush/Star route's {@code B B B U U} measured against
     * own-visible total mana, {@code no-pile-route}, {@code oracle-etb-disabled},
     * {@code better-attack}, {@code clock}, or {@code other check=<name>}. The
     * older {@code mana:6/<have>} token was a mislabel: it was emitted where
     * Doomsday itself was unplayable, with a constant borrowed from the Recall
     * route's cost string and a colour-blind {@code <have>}.</p> */
    private String decline = "other check=doomsday-plan";
    public String declineReason() { return decline; }

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

    /** Observability only: own-visible sources that could pay Doomsday's {B}.
     * Untapped battlefield permanents carrying a native mana ability able to
     * make black, plus black already floating. Pure reads of our own public
     * battlefield and our own pool; no payment probe, no RNG, no state change. */
    private int ownVisibleBlack() {
        int sources = player.getManaPool().getAmountOfColor(MagicColor.BLACK);
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (!card.isUntapped()) continue;
            for (SpellAbility ability : card.getManaAbilities())
                if (ability.getManaPart() != null && ability.canProduce("B")) { sources++; break; }
        }
        return sources;
    }

    /** Oracle wins from an enters-the-battlefield trigger, so while a visible
     * permanent switches creature-ETB triggers off (Torpor Orb) no pile route
     * can win and no natural route may start. Gated by native
     * {@link StaticAbility#checkConditions(StaticAbilityMode)} and the same
     * parameter shape {@code StaticAbilityDisableTriggers.isDisabled} reads for
     * a ChangesZone trigger. Only unhidden battlefield permanents are examined:
     * a face-down permanent has no abilities, and no hand, library or hidden
     * face is touched. When Oracle is not legitimately in view (route 2 leaves
     * it in the library) the ValidCause test is skipped, which can only add an
     * abstention and never a commitment. */
    private boolean oracleTriggerDisabled() {
        Card oracle = inHand("Thassa's Oracle");
        for (Card card : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (StaticAbility stat : card.getStaticAbilities()) {
                if (!stat.checkConditions(StaticAbilityMode.DisableTriggers)) continue;
                if (stat.hasParam("ValidCard") || stat.hasParam("ValidTrigger")) continue;
                if (stat.hasParam("ValidMode")
                        && !java.util.Arrays.asList(stat.getParam("ValidMode").split(",")).contains("ChangesZone")) continue;
                if (!stat.matchesValidParam("Destination", ZoneType.Battlefield.toString())) continue;
                if (oracle != null && !stat.matchesValidParam("ValidCause", oracle)) continue;
                return true;
            }
        }
        return false;
    }

    /** A better ordinary alternative, read from the public battlefield only:
     * with unblocked lethal already on board there is no reason to pay half our
     * life and exile our library. A same-turn route is only dominated while an
     * attack step is still ahead of us, so that form is MAIN1-only and uses
     * native {@link CombatUtil#canAttack}; a route that passes the turn is
     * dominated in either main, because a free attack on our next turn wins no
     * later, so that form uses {@link CombatUtil#canAttackNextTurn}. Any
     * untapped creature the opponent controls counts as a potential blocker and
     * suppresses the gate. */
    private boolean lethalOnBoard(boolean sameTurn) {
        if (sameTurn && !player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return false;
        for (Player opponent : player.getOpponents()) {
            boolean blocker = false;
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
                if (card.isCreature() && card.isUntapped()) { blocker = true; break; }
            if (blocker) continue;
            int power = 0;
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                if (!card.isCreature()) continue;
                if (sameTurn ? CombatUtil.canAttack(card, opponent) : CombatUtil.canAttackNextTurn(card, opponent))
                    power += Math.max(0, card.getNetPower());
            }
            if (power > 0 && power >= opponent.getLife()) return true;
        }
        return false;
    }

    /** Route 2 hands the opponent a turn, so decline when their public board
     * already kills us across it. Public battlefield only, exact formula:
     * <pre>
     *   lifeAfter = life - ceil(life / 2)   // doomsday.txt Y = YourLifeTotal/HalfUp
     *   clock     = sum of getNetPower() over every creature the opponent
     *               controls, tapped included, because a tapped creature untaps
     *               in its controller's untap step and can still attack
     *   blockers  = our untapped creatures only, because ours do not untap
     *               before the opponent's attack step
     *   survivable iff blockers &gt; 0 || clock &lt; lifeAfter
     * </pre>
     * Power and creature type are public even for a face-down permanent, so no
     * hidden identity is read. */
    private boolean clockSurvivable() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (card.isCreature() && card.isUntapped()) return true;
        int clock = 0;
        for (Player opponent : player.getOpponents())
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
                if (card.isCreature()) clock += Math.max(0, card.getNetPower());
        return clock < player.getLife() - (player.getLife() + 1) / 2;
    }

    /** Unlike a Doomsday search, drawing cannot recover a graveyard copy. */
    private boolean reachableByDrawing(String name) {
        if (inHand(name) != null) return true;
        return CubeComboAi.ownCopyOutside(player, name, UNAVAILABLE_DRAW_ZONES);
    }

    private static final ZoneType[] UNAVAILABLE_DRAW_ZONES = {
        ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Stack
    };

    /** Oracle adds UU. Count only current public mana symbols, not a card's
     * printed back face or hidden identity. Before Gush, conservatively exclude
     * every returnable Island, and sources our blue float could sacrifice. The
     * threshold is recomputed after responses rather than cached with the pile. */
    private int oracleThreshold(boolean beforeGush) {
        int devotion = 2 + player.getDevotionMod();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (beforeGush && (card.getType().hasSubtype("Island")
                    || card.getManaAbilities().stream().anyMatch(sa -> sa.getPayCosts().getCostParts().stream()
                        .anyMatch(cost -> cost instanceof forge.game.cost.CostSacrifice)))) continue;
            for (var shard : card.getManaCost()) if (shard.isColor(MagicColor.BLUE)) devotion++;
        }
        return Math.max(0, devotion);
    }

    private boolean gushReachesOracle(int librarySize) {
        return librarySize >= 2 && librarySize <= 5 && librarySize - 2 <= oracleThreshold(true)
                && player.canDrawAmount(2);
    }

    private SpellAbility advanceGush(int librarySize) {
        SpellAbility gush = gushReachesOracle(librarySize) ? alternateGush() : null;
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
        SpellAbility oracle = librarySize <= oracleThreshold(false) ? playable("Thassa's Oracle") : null;
        if (oracle != null) { stage = Stage.ORACLE; return oracle; }
        SpellAbility recall = librarySize >= 3 && player.canDrawAmount(3) ? playable("Ancestral Recall") : null;
        if (recall != null && CubeComboAi.canPayCost(new Cost("U U U", false), recall, player, false)) {
            stage = Stage.DRAW;
            return recall;
        }
        SpellAbility gush = gushReachesOracle(librarySize) ? alternateGush() : null;
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

    /** Pile routes needing no Recall, Gush or Star: Doomsday's own BBB plus
     * public blue devotion. Route 1 casts Oracle from hand the same turn at the
     * pile's five cards. Route 2 leaves Oracle in the pile, where
     * {@link #orderPile} already puts it on top, and casts it after our next
     * draw step at four; because Oracle stays in the library the ordinary AI
     * cannot cast it as a body, so no reservation or preserve rule is needed.
     * Doomsday's {@code ChangeNum$ 5} fixes both library sizes and this is only
     * reached with {@code librarySize > 5}, so the pile is exactly five.
     * Own-visible information only: our hand, our own deck composition, our own
     * public battlefield and both public battlefields.
     *
     * <p>This is a fallback: {@code fallback} is the decline token the caller
     * would have printed, so declining here leaves every pre-existing receipt
     * unchanged and every currently-green case still takes its current route.</p> */
    private SpellAbility naturalRoute(SpellAbility doom, String fallback) {
        decline = fallback;
        gushRoute = false;
        reservedStar = null;
        if (oracleTriggerDisabled()) { decline = "oracle-etb-disabled"; return null; }
        int threshold = oracleThreshold(false);
        if (inHand("Thassa's Oracle") != null) {
            if (threshold < 5) return null;
            if (!CubeComboAi.canPayCost(new Cost("B B B U U", false), doom, player, false)) {
                decline = "mana:5/" + CubeComboAi.ownVisibleMana(player);
                return null;
            }
            if (lethalOnBoard(true)) { decline = "better-attack"; return null; }
            return commitDoomsday(doom);
        }
        // Oracle is not in hand, so the availability already checked above means
        // library or graveyard: exactly the zones Doomsday searches.
        if (threshold < 4 || !player.canDrawAmount(1)) return null;
        if (lethalOnBoard(false)) { decline = "better-attack"; return null; }
        if (!clockSurvivable()) { decline = "clock"; return null; }
        return commitDoomsday(doom);
    }

    private SpellAbility commitDoomsday(SpellAbility doom) {
        turn = player.getGame().getPhaseHandler().getTurn();
        doomsdayId = doom.getHostCard().getId();
        oracleSelected = false;
        gushSelected = false;
        stage = Stage.DOOMSDAY;
        return doom;
    }

    public SpellAbility nextAction() {
        decline = "other check=doomsday-plan";
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)
                && !player.getGame().getPhaseHandler().is(PhaseType.MAIN2, player)) { stage = Stage.NONE; decline = "phase"; return null; }
        if (turn != player.getGame().getPhaseHandler().getTurn()) stage = Stage.NONE;
        if (!player.getGame().getStack().isEmpty()) { decline = "stack-not-empty"; return null; }
        if (player.cantWin()) { stage = Stage.NONE; decline = "cant-win"; return null; }
        int librarySize = player.getCardsIn(ZoneType.Library).size(); // count, never identities/order
        if (stage == Stage.DOOMSDAY) {
            if (librarySize > 5 || inHand("Doomsday") != null || inHand("Thassa's Oracle") == null && !oracleSelected) {
                stage = Stage.NONE; decline = "other check=pile-not-resolved"; return null;
            }
            if (gushRoute) {
                if (reservedStar == null) {
                    stage = Stage.STAR;
                    return advanceGush(librarySize);
                }
                SpellAbility star = player.canDrawAmount(3) && librarySize == 5 ? starAbility() : null;
                if (star != null) { stage = Stage.STAR; return star; }
                stage = Stage.NONE; decline = "other check=starAbility"; return null;
            }
            SpellAbility draw = playable("Ancestral Recall");
            if (librarySize >= 3 && player.canDrawAmount(3) && draw != null
                    && CubeComboAi.canPayCost(new Cost("U U U", false), draw, player, false)) {
                stage = Stage.DRAW; return draw;
            }
            // B4: Recall is gone, so this priority pass goes to the plan's own
            // recovery instead of super, which would otherwise spend the UU
            // route 1 kept for Oracle. recoverSmallLibrary resets gushRoute and
            // reservedStar, which is safe because the gushRoute case returned
            // above, and it recomputes the threshold from current state.
            SpellAbility recovered = recoverSmallLibrary(librarySize);
            if (recovered == null) { stage = Stage.NONE; decline = "other check=post-doomsday-draw"; return null; }
            System.err.println("CUBE_COMBO recovered-small-library card=" + recovered.getHostCard().getName());
            return recovered;
        }
        if (stage == Stage.STAR) {
            decline = "other check=advanceGush";
            return advanceGush(librarySize);
        }
        if (stage == Stage.DRAW) {
            SpellAbility oracle = librarySize <= oracleThreshold(false) ? playable("Thassa's Oracle") : null;
            stage = oracle == null ? Stage.NONE : Stage.ORACLE;
            if (oracle == null) decline = "other check=oracleThreshold";
            return oracle;
        }
        if (stage == Stage.ORACLE) { stage = Stage.NONE; decline = "other check=oracle-resolved"; return null; }
        if (librarySize <= 5) {
            SpellAbility recovery = recoverSmallLibrary(librarySize);
            if (recovery != null) {
                turn = player.getGame().getPhaseHandler().getTurn();
                System.err.println("CUBE_COMBO recovered-small-library card=" + recovery.getHostCard().getName());
            } else decline = "no-pile-route";
            return recovery;
        }
        // No unnecessary Doomsday on a library already small enough for the draw.
        if (player.getLife() <= 1 || !availableInOwnDeck("Thassa's Oracle")) {
            decline = player.getLife() <= 1 ? "cant-win" : "no-pile-route";
            return null;
        }
        gushRoute = false; reservedStar = null;
        SpellAbility doom = playable("Doomsday");
        if (doom == null) {
            // Doomsday's own printed cost is ManaCost:B B B, so report black
            // sources rather than a total-mana count borrowed from the Recall
            // route's B B B U U U string.
            decline = inHand("Doomsday") == null ? "no-doomsday-in-hand"
                    : ownVisibleBlack() < 3 ? "mana:BBB/" + ownVisibleBlack()
                    : "other check=playable:Doomsday";
            return null;
        }
        if (!player.canDrawAmount(3) || inHand("Ancestral Recall") == null
                || !CubeComboAi.canPayCost(new Cost("B B B U U U", false), doom, player, false)) {
            if (!availableInOwnDeck("Gush") || player.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> c.getType().hasSubtype("Island")).count() < 2) return naturalRoute(doom, "no-pile-route");
            gushRoute = true;
            boolean direct = gushReachesOracle(5) && alternateGush() != null;
            if (!direct) {
                if (!player.canDrawAmount(3)) { gushRoute = false; return naturalRoute(doom, "no-pile-route"); }
                for (Card card : player.getCardsIn(ZoneType.Battlefield)) if (card.getName().equals("Chromatic Star")) {
                    reservedStar = card;
                    if (starAbility() != null) break;
                    reservedStar = null;
                }
                if (reservedStar == null) { gushRoute = false; return naturalRoute(doom, "no-pile-route"); }
            }
            if (!withStarReserved(() -> CubeComboAi.canPayCost(new Cost("B B B U U", false), doom, player, false))) {
                gushRoute = false; reservedStar = null;
                return naturalRoute(doom, "mana:5/" + CubeComboAi.ownVisibleMana(player));
            }
        }
        return commitDoomsday(doom);
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
