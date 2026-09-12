package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostReturn;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import java.util.function.Supplier;

/** Native Doomsday plans using Recall, Star/Gush, Gush with public devotion, or
 * Doomsday's own BBB plus public devotion alone, to reach Oracle, optionally
 * bridged by a mana-only card in hand when black is the only thing missing. The
 * search and ordering hooks see only choices the resolving spell legally
 * exposes. Other piles are not yet supported by this planner. */
public final class CubeDoomsdayPlan {
    private enum Stage { NONE, RITUAL, DOOMSDAY, STAR, DRAW, ORACLE }
    private final Player player;
    private Stage stage = Stage.NONE;
    private int turn = -1, doomsdayId = -1, ritualId = -1, ritualTurn = -1;
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
     * route's cost string and a colour-blind {@code <have>}.</p>
     *
     * <p>v46 adds no token. The ritual bridge is entered only where
     * {@code mana:BBB/<black>} was already the answer, so that stays the token
     * whenever no bridge is found; when a bridge exists but a gate refuses it,
     * the bridge reports that gate's existing token
     * ({@code oracle-etb-disabled}, {@code better-attack}, {@code clock}), and
     * a second bridge in one turn reports {@code other check=ritual-spent}.</p> */
    private String decline = "other check=doomsday-plan";
    public String declineReason() { return decline; }
    /** Diagnostic only, never read by a decision: how many times the ritual
     * bridge actually proposed a bridge card. Test-visible static, read and
     * reset reflectively by the fixture, exactly like
     * {@link CubeComboPlayerController#guardRejections}. */
    static int ritualBridges;

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

    /** The printed spell of a hand card, playable or not. Cost reads only:
     * unlike {@link #playable} this deliberately skips legality and payment, so
     * it must never be returned as an action. */
    private SpellAbility handSpell(String name) {
        Card card = inHand(name);
        if (card == null) return null;
        for (SpellAbility original : card.getSpellAbilities()) if (original.isSpell()) return original.copy(player);
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
     *   attackers = every creature the opponent controls, tapped included,
     *               because a tapped creature untaps in its controller's untap
     *               step and can still attack
     *   k         = our untapped creatures that native CombatUtil.canBlock
     *               permits against at least one of those attackers; ours only,
     *               because our creatures do not untap before their attack step
     *   clock     = sum of the attacker powers left after the k LARGEST are
     *               absorbed, one per blocker
     *   survivable iff clock &lt; lifeAfter
     * </pre>
     *
     * <p>v49 R1 replaces v44's {@code blockers &gt; 0} short-circuit, which
     * credited any single untapped creature with full immunity: two 1/1s were
     * treated as an answer to four attackers, the position the v47 analysis
     * recorded as the 16701484-s0 loss. An absorption count is the same public
     * read, counted. It is still deliberately optimistic - trample, evasion,
     * multiple blocks, removal and combat tricks are all ignored and every
     * blocker is credited with eating a whole attacker - so it can only ever
     * decline a position the old short-circuit already allowed, never commit
     * one it refused: with {@code k = 0} the arithmetic is identical to v44's.
     * </p>
     *
     * Power and creature type are public even for a face-down permanent, so no
     * hidden identity is read. */
    private boolean clockSurvivable() {
        CardCollection attackers = new CardCollection();
        for (Player opponent : player.getOpponents())
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
                if (card.isCreature()) attackers.add(card);
        int blockers = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (card.isCreature() && card.isUntapped() && CombatUtil.canBlockAtLeastOne(card, attackers)) blockers++;
        java.util.List<Integer> powers = new java.util.ArrayList<>();
        for (Card card : attackers) powers.add(Math.max(0, card.getNetPower()));
        powers.sort(java.util.Comparator.reverseOrder());
        int clock = 0;
        for (int i = blockers; i < powers.size(); i++) clock += powers.get(i);
        return clock < player.getLife() - (player.getLife() + 1) / 2;
    }

    /** v49 R2: own battlefield permanents that could pay one of Oracle's blue
     * pips on our next turn. Exactly the two native reads the plan already
     * makes elsewhere, combined - {@link #ownVisibleBlack}'s
     * {@code getManaPart() != null && canProduce(...)} and
     * {@link #oracleThreshold}'s exclusion of a mana ability whose cost
     * contains a {@link forge.game.cost.CostSacrifice} part - and nothing else.
     *
     * <p>Tapped-ness is deliberately ignored: route 2 casts Oracle after our
     * own untap step, so a tapped land is a source then. Sources are counted,
     * never amounts, so one double-blue source under-promises rather than
     * over-promising, and a sacrifice source (a Lotus Petal that Doomsday's own
     * payment may consume this turn) is not counted at all. Pure reads of our
     * own public battlefield: no payment probe, no RNG, no state change.</p> */
    private int ownBlueSources() {
        int sources = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            for (SpellAbility original : card.getManaAbilities()) {
                // Copy with us as the activator, exactly as floatBlue and
                // permanentManaAbility already do. An intrinsic ability carries
                // no activating player, and asking a non-producing one whether
                // it could make U makes the engine fall back to the host's
                // controller and log it; the copy is the same native read
                // without that side effect.
                SpellAbility ability = original.copy(player);
                if (ability.getManaPart() == null || !ability.canProduce("U")) continue;
                if (ability.getPayCosts().getCostParts().stream()
                        .anyMatch(cost -> cost instanceof forge.game.cost.CostSacrifice)) continue;
                sources++;
                break;
            }
        }
        return sources;
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
        return top.getActivatingPlayer() == player && ((stage == Stage.RITUAL && top.getHostCard().getId() == ritualId)
                || (stage == Stage.DOOMSDAY && top.getHostCard().getId() == doomsdayId)
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
        // v49 R2, evaluated LAST so that every pre-existing decline keeps its
        // own token: route 2 buys an Oracle it must still be able to cast next
        // turn, and v44 never checked the UU it is buying. Two own visible blue
        // sources is the gate; the decline stays the caller's `fallback` token
        // (`no-pile-route`), so the grammar is unchanged.
        if (ownBlueSources() < 2) return null;
        return commitDoomsday(doom);
    }

    /** A mana-only bridge card in hand: a ritual spell that adds mana as it
     * resolves (Dark Ritual, Cabal Ritual), or a permanent spell whose own mana
     * ability needs no mana to activate (Lotus Petal). Cost, amount and
     * producible colours are read from the card's own script through native
     * accessors - {@link forge.game.cost.Cost#getTotalMana},
     * {@link SpellAbility#amountOfManaGenerated} and
     * {@link SpellAbility#canProduce} - never from a table of card names.
     *
     * <p>Lion's Eye Diamond is excluded by that general rule rather than by
     * name: its activation cost is {@code Discard<0/Hand>}, which is neither a
     * tap nor a self-sacrifice, and an honest model of it would have to account
     * for discarding Doomsday itself out of the same hand.</p> */
    private record Bridge(SpellAbility spell, ManaCost cost, int amount, java.util.List<Byte> colours) { }

    /** The mana ability the permanent would offer once it has resolved: no mana
     * in its activation cost, and nothing besides tap or sacrificing itself -
     * the same cost shape {@link #floatBlue} already requires. A creature's tap
     * ability is refused, because summoning sickness would hold it until our
     * next turn and the forecast would be a lie. */
    private SpellAbility permanentManaAbility(Card card) {
        for (SpellAbility original : card.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (ability.getPayCosts().getTotalMana().getCMC() != 0) continue;
            if (!ability.getPayCosts().getCostParts().stream().allMatch(p -> p instanceof forge.game.cost.CostTap
                    || p instanceof forge.game.cost.CostPartMana
                    || p instanceof forge.game.cost.CostSacrifice sacrifice && sacrifice.getType().equals("CARDNAME"))) continue;
            if (card.isCreature() && ability.getPayCosts().hasTapCost()) continue;
            return ability;
        }
        return null;
    }

    /** Read one hand card as a bridge, or decline it. Special, combo and
     * persistent mana are refused: their produced mana is not a plain amount of
     * one colour, and resolving what they would make can touch game state. */
    private Bridge bridgeFrom(Card card) {
        for (SpellAbility original : card.getSpellAbilities()) {
            if (!original.isSpell()) continue;
            SpellAbility spell = original.copy(player);
            if (spell.usesTargeting() || !CubeComboAi.canPlayNative(spell, player)
                    || !CubeComboAi.canPayCost(spell, player, false)) continue;
            SpellAbility production = spell.getManaPart() != null ? spell : permanentManaAbility(card);
            if (production == null || production.getManaPart() == null) continue;
            var part = production.getManaPart();
            if (part.isSpecialMana() || part.isComboMana() || part.isPersistentMana()) continue;
            int amount = production.amountOfManaGenerated(true);
            if (amount <= 0) continue;
            java.util.List<Byte> colours = new java.util.ArrayList<>();
            for (byte colour : MagicColor.WUBRG)
                if (production.canProduce(MagicColor.toShortString(colour))) colours.add(colour);
            if (colours.isEmpty()) continue;
            return new Bridge(spell, spell.getPayCosts().getTotalMana(), amount, colours);
        }
        return null;
    }

    /** Forecast only: would {@code route} be payable if this bridge resolved
     * first? The bridge makes {@code amount} mana of one colour, so for each
     * colour it can make, that many matching pips are struck off the route and
     * the remainder is asked of native payment <em>together with the bridge's
     * own cost</em>. Paying both out of one native check is what stops a single
     * source being counted twice. Nothing is cast or tapped and no state
     * changes: {@link CubeComboAi#canPayManaCost} runs inside the payment
     * probe. A forecast is never a commitment - the next priority pass
     * re-derives everything from live state. */
    private boolean payableAfterBridge(Bridge bridge, ManaCost route) {
        for (byte colour : bridge.colours()) {
            ManaCostBeingPaid combined = new ManaCostBeingPaid(bridge.cost());
            int left = bridge.amount();
            for (ManaCostShard shard : route) {
                if (left > 0 && shard.isColor(colour)) { left--; continue; }
                combined.increaseShard(shard, 1);
            }
            combined.increaseGenericMana(Math.max(0, route.getGenericCost() - left));
            if (CubeComboAi.canPayManaCost(combined, bridge.spell(), player, false)) return true;
        }
        return false;
    }

    /** Which already-registered route the bridge would make payable, evaluated
     * in the order {@link #nextAction} evaluates them so the bridge can never
     * prefer a route the plan itself would not take. Every non-mana gate is
     * read from live state, because a bridge changes only our mana.
     *
     * <p>The Chromatic Star sub-route is deliberately not forecast: its
     * payability depends on the reservation {@link #withStarReserved} applies
     * during real payment, which a forecast cannot model honestly. A Star board
     * therefore simply gets no bridge.</p> */
    private String routeAfterBridge(Bridge bridge, ManaCost doomCost) {
        ManaCost recallRoute = new Cost("B B B U U U", false).getTotalMana();
        ManaCost gushRouteCost = new Cost("B B B U U", false).getTotalMana();
        if (player.canDrawAmount(3) && inHand("Ancestral Recall") != null
                && payableAfterBridge(bridge, recallRoute)) return "recall";
        long islands = player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getType().hasSubtype("Island")).count();
        if (availableInOwnDeck("Gush") && islands >= 2 && gushReachesOracle(5) && alternateGush() != null
                && payableAfterBridge(bridge, gushRouteCost)) return "gush";
        if (inHand("Thassa's Oracle") != null)
            return oracleThreshold(false) >= 5 && payableAfterBridge(bridge, gushRouteCost) ? "route1" : null;
        // v49 R2 applies to the forecast too: a bridge must not buy a route 2
        // whose Oracle we could not pay for. Where it refuses, no bridge is
        // found and the decline stays `mana:BBB/<black>` byte for byte.
        return oracleThreshold(false) >= 4 && player.canDrawAmount(1) && ownBlueSources() >= 2
                && payableAfterBridge(bridge, doomCost) ? "route2" : null;
    }

    /** Design v43 section A route 3, the ritual bridge. Doomsday is in hand and
     * unplayable for want of black: {@code ComputerUtilMana} will not chain a
     * ritual <em>spell</em> into a cost payment, so {@link #playable} cannot see
     * the Dark Ritual that would pay for it. That is the shortfall the
     * fresh-seed v45 panel recorded 66 times as {@code mana:BBB/0..2}.
     *
     * <p>The bridge casts the ritual as the plan's own action and commits to
     * nothing else. {@link Stage#RITUAL} exists only to keep the plan from
     * acting while the ritual is on the stack; the next priority pass clears it
     * and re-derives every gate, so a countered ritual leaves the plan
     * declining rather than holding a remembered pile. At most one bridge per
     * turn, so a countered bridge is not chased with a second card.</p>
     *
     * <p>Reached only where {@code mana:BBB/<black>} was already the decline, so
     * a board with no bridge keeps that receipt byte for byte. Own-visible
     * information only: our hand, our own deck composition, our own public
     * battlefield and both public battlefields.</p> */
    private SpellAbility ritualBridge() {
        SpellAbility doomSpell = handSpell("Doomsday");
        if (doomSpell == null) return null;
        if (ritualTurn == player.getGame().getPhaseHandler().getTurn()) {
            decline = "other check=ritual-spent";
            return null;
        }
        ManaCost doomCost = doomSpell.getPayCosts().getTotalMana();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.getName().equals("Doomsday")) continue;
            Bridge bridge = bridgeFrom(card);
            if (bridge == null) continue;
            String route = routeAfterBridge(bridge, doomCost);
            if (route == null) continue;
            // The v44 abstentions, applied to the bridge itself: a ritual is a
            // card and half our life is the price of the route it buys.
            if (oracleTriggerDisabled()) { decline = "oracle-etb-disabled"; return null; }
            if (lethalOnBoard(!route.equals("route2"))) { decline = "better-attack"; return null; }
            if (route.equals("route2") && !clockSurvivable()) { decline = "clock"; return null; }
            turn = player.getGame().getPhaseHandler().getTurn();
            ritualTurn = turn;
            ritualId = bridge.spell().getHostCard().getId();
            stage = Stage.RITUAL;
            ritualBridges++;
            System.err.println("CUBE_COMBO ritual-bridge card=" + card.getName() + " route=" + route
                    + " produces=" + bridge.amount());
            return bridge.spell();
        }
        return null;
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
        // A bridge commits to nothing. Its only job was to put mana in the pool
        // (or a Lotus Petal on the battlefield), so the plan starts over here
        // from live state and the ordinary entry gates decide again.
        if (stage == Stage.RITUAL) stage = Stage.NONE;
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
            if (inHand("Doomsday") == null) { decline = "no-doomsday-in-hand"; return null; }
            if (ownVisibleBlack() >= 3) { decline = "other check=playable:Doomsday"; return null; }
            // Black is the only thing missing, which is the one shortfall a
            // mana-only card in hand can answer. The decline stays exactly what
            // v45 printed unless a bridge is actually found.
            decline = "mana:BBB/" + ownVisibleBlack();
            return ritualBridge();
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

    /** v49: is the plan still holding a pile it built, waiting for the Oracle
     * that pile put on top? {@link #oracleSelected} is set only by
     * {@link #choosePileCard}, i.e. only by our own resolving Doomsday, and is
     * cleared only by {@link #commitDoomsday}, so it is the durable record of
     * "the last Doomsday we cast searched Oracle into the pile". The rest is
     * re-derived from live state each time, never remembered: the library is
     * still the pile (or smaller), and Oracle is still somewhere a draw can
     * reach it.
     *
     * <p>{@link #stage} deliberately is NOT the marker. The hold outlives it:
     * the plan's own post-Doomsday pass ends with {@code stage = NONE} as soon
     * as it cannot act this turn ({@code other check=post-doomsday-draw}), and
     * the hazard this guards - the v47 analysis's 16701482-s0 - arrived on a
     * later priority pass of that same turn and could equally arrive on a later
     * turn. Reads our own hand, our own deck composition and our own library
     * SIZE only.</p> */
    private boolean holdingPile() {
        return oracleSelected && player.getCardsIn(ZoneType.Library).size() <= 5
                && reachableByDrawing("Thassa's Oracle");
    }

    /** v49: which cards in our own hand this plan is currently relying on, for
     * the controller's discard-choice ownership. Empty unless
     * {@link #holdingPile()} - so a plan that never cast Doomsday, or whose
     * pile is spent or broken, owns no discard and the ordinary AI's choice
     * stands untouched.
     *
     * <p>The route pieces are the pile's own: Thassa's Oracle always, and Gush
     * additionally when {@link #choosePileCard} actually put a Gush in the pile
     * ({@link #gushSelected}). This is not a preserve rule for a card name - it
     * names only cards the plan put into a pile it is still holding, it expires
     * with that pile, and it never blocks the discard itself when no legal
     * alternative exists. Own-visible information only.</p> */
    public CardCollection discardProtectedCards() {
        CardCollection kept = new CardCollection();
        if (!holdingPile()) return kept;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.getName().equals("Thassa's Oracle") || gushSelected && card.getName().equals("Gush")) kept.add(card);
        }
        return kept;
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
