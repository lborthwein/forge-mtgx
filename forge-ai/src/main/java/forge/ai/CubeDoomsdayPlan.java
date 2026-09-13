package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.GameActionUtil;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostReturn;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.replacement.ReplacementType;
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
 * exposes. Other piles are not yet supported by this planner.
 *
 * <p>v51 adds route J, a devotion-free finish that needs no pile decision at
 * all: a printed "you win instead of drawing from an empty library"
 * replacement live on our own battlefield, plus an ability that draws past a
 * five-card pile without shuffling the library back first. It is evaluated
 * after every route above it.</p> */
public final class CubeDoomsdayPlan {
    private enum Stage { NONE, RITUAL, ENABLER, DOOMSDAY, STAR, DRAW, ORACLE, JACE, FINISH }
    private final Player player;
    private Stage stage = Stage.NONE;
    private int turn = -1, doomsdayId = -1, ritualId = -1, ritualTurn = -1, finisherId = -1;
    /** v78: the enabler spell currently on the stack, and the turn one was last
     * proposed on. Exactly {@link #ritualId}/{@link #ritualTurn}'s shape, for
     * exactly their reason: the stage keeps the plan from acting while our own
     * enabler is unresolved, and one enabler per turn means a countered one is
     * not chased with a second card. */
    private int enablerId = -1, enablerTurn = -1;
    private boolean oracleSelected, gushSelected, gushRoute;
    private Card reservedStar;
    private Card delayedHandOracle;
    private int delayedHandTurn = -1, delayedDoomId = -1;
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
     * a second bridge in one turn reports {@code other check=ritual-spent}.</p>
     *
     * <p>v51 adds exactly one token, {@code other check=jace-finisher}, and it
     * is reachable only from route J's own {@link Stage#JACE}/{@link
     * Stage#FINISH} passes. Route J is evaluated after every other route, so on
     * a board with no live empty-draw win replacement the token the routes above
     * set is what is printed, unchanged; the only other token route J can set is
     * the pre-existing {@code better-attack}.</p>
     *
     * <p>v78 adds exactly two tokens, {@code other check=enabler-spent} and
     * {@code other check=enabler-uncastable}, and both are reachable only from
     * {@link #enablerAction}, which is evaluated after every route above it
     * INCLUDING route J and which returns without touching this field on every
     * structural clause. So a board that offers no pile enabler in our own hand
     * keeps whatever token the routes above set, byte for byte. The one
     * pre-existing token v78 can newly set is {@code oracle-etb-disabled}, on an
     * enabler board alone, for the reason {@link #naturalRoute} sets it.</p> */
    private String decline = "other check=doomsday-plan";
    public String declineReason() { return decline; }
    /** Diagnostic only, never read by a decision: how many times the ritual
     * bridge actually proposed a bridge card. Test-visible static, read and
     * reset reflectively by the fixture, exactly like
     * {@link CubeComboPlayerController#guardRejections}. */
    static int ritualBridges;
    /** Diagnostic only, never read by a decision: how many times v51's route J
     * actually proposed an action (a Doomsday it committed for the Jace finish,
     * or the finisher itself). Test-visible static, read and reset reflectively
     * by the fixture, exactly like {@link #ritualBridges}. */
    static int jaceFinishes;
    /** Diagnostic only, never read by a decision: how many times v78's enabler
     * action actually proposed a pile enabler from our own hand. Test-visible
     * static, read and reset reflectively by the fixture, exactly like
     * {@link #ritualBridges} and {@link #jaceFinishes}. */
    static int pileEnablers;

    public CubeDoomsdayPlan(Player player) { this.player = player; }

    /** v74: when non-null, {@link #inHand} reads THIS collection instead of our
     * own live hand. Set by {@link #wouldConvert} alone, on a throwaway plan
     * object it constructs itself, so every live decision path - every
     * {@link #nextAction}, every route, every bridge - sees {@code null} here
     * and reads the real hand exactly as v43..v73 did. */
    private java.util.Collection<Card> handOverride;

    private Card inHand(String name) {
        if (handOverride != null) {
            for (Card card : handOverride) if (card.getName().equals(name)) return card;
            return null;
        }
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
        CardCollection blockers = new CardCollection();
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (card.isCreature() && card.isUntapped()) blockers.add(card);
        // Attackers by descending power, so the greedy pass below takes the
        // largest absorbable one first.
        java.util.List<Card> ordered = new java.util.ArrayList<>(attackers);
        ordered.sort(java.util.Comparator.comparingInt((Card card) -> Math.max(0, card.getNetPower())).reversed());
        // assignment[b] = index in `ordered` of the attacker blocker b is
        // currently assigned to, or -1.
        int[] assignment = new int[blockers.size()];
        java.util.Arrays.fill(assignment, -1);
        int clock = 0;
        for (int index = 0; index < ordered.size(); index++) {
            Card attacker = ordered.get(index);
            int power = Math.max(0, attacker.getNetPower());
            if (!absorb(index, ordered, blockers, assignment, new boolean[blockers.size()])) clock += power;
        }
        return clock < player.getLife() - (player.getLife() + 1) / 2;
    }

    /** v63 C4 (the v47 analysis section 4 R1). One augmenting-path step of
     * Kuhn's algorithm: can this attacker be given a blocker, moving already
     * assigned blockers along legal edges only? An edge exists iff native
     * {@link CombatUtil#canBlock} permits that blocker against that attacker -
     * the same native predicate v55's forecast uses, so "can't be blocked by
     * creatures with power 2 or less", flying/reach and every other CantBlockBy
     * static is honoured by the engine rather than restated here - and iff the
     * attacker does not demand more than one blocker (menace), because this
     * model assigns exactly one.
     *
     * <p>{@link #clockSurvivable} calls this with the attackers in descending
     * power. The attacker sets that can be matched simultaneously form a
     * transversal matroid, so greedy by weight with an independence test is the
     * MAXIMUM absorbable power: the guard stays as optimistic as it can
     * honestly be rather than over-declining.</p>
     *
     * <p>This is a strict tightening of v49 R1, which credited the {@code k}
     * largest attackers to any {@code k} blockers that could block SOMETHING.
     * The absorbed set has size {@code m <= k} (a matched blocker could block
     * at least one attacker, so v49 R1 already counted it), and the sum of its
     * powers is at most the sum of the {@code m} largest overall, hence at most
     * the sum of the {@code k} largest. So the new clock is never smaller than
     * the old one: no board v49 R1 refused can be newly committed, and with
     * {@code k = 0} the two are identical. Everything v49 R1 ignores - trample,
     * multiple blocks, removal, combat tricks - stays ignored.</p>
     *
     * <p>Public battlefields only; power and creature type are public even for
     * a face-down permanent, so no hidden identity is read.</p> */
    private boolean absorb(int attackerIndex, java.util.List<Card> ordered, CardCollection blockers,
            int[] assignment, boolean[] visited) {
        Card attacker = ordered.get(attackerIndex);
        if (CombatUtil.getMinNumBlockersForAttacker(attacker, player) > 1) return false;
        for (int b = 0; b < blockers.size(); b++) {
            if (visited[b] || !CombatUtil.canBlock(attacker, blockers.get(b))) continue;
            visited[b] = true;
            if (assignment[b] < 0 || absorb(assignment[b], ordered, blockers, assignment, visited)) {
                assignment[b] = attackerIndex;
                return true;
            }
        }
        return false;
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

    /** Float a survivable painful blue only to finish Oracle from the current
     * public board. Default's mana willingness rejects even one damage at low
     * life; the residual native payment check must not count this source twice.
     * Execution still pays the real activation and damage before reconsidering
     * Oracle. Unsupported costs and subability chains remain with Default. */
    private SpellAbility oraclePainMana(int librarySize) {
        if (librarySize > 5 || librarySize > oracleThreshold(false) || oracleTriggerDisabled()) return null;
        SpellAbility oracle = handSpell("Thassa's Oracle");
        if (oracle == null || !CubeComboAi.canPlayNative(oracle, player)
                || !oracle.getPayCosts().isOnlyManaCost()
                || !oracle.getPayCosts().getTotalMana().toString().equals("{U}{U}")) return null;
        var memory = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.isUntapped() || AiCardMemory.isRememberedCard(player, card, memory)) continue;
            for (SpellAbility original : card.getManaAbilities()) {
                SpellAbility mana = original.copy(player);
                SpellAbility pain = mana.getSubAbility();
                if (mana.getManaPart() == null || !mana.canProduce("U")
                        || !mana.getManaPart().meetsManaRestrictions(oracle)
                        || !mana.getManaPart().meetsManaShardRestrictions(ManaCostShard.BLUE, MagicColor.BLUE)
                        || mana.getPayCosts().getCostParts().stream().noneMatch(p -> p instanceof forge.game.cost.CostTap)
                        || mana.getPayCosts().getTotalMana().getCMC() != 0
                        || !mana.getPayCosts().getCostParts().stream().allMatch(p -> p instanceof forge.game.cost.CostTap
                            || p instanceof forge.game.cost.CostPartMana)
                        || pain == null || pain.getApi() != ApiType.DealDamage || pain.getSubAbility() != null
                        || !"You".equals(pain.getParam("Defined")) || !"1".equals(pain.getParam("NumDmg"))
                        || pain.usesTargeting() || !CubeComboAi.canPlayNative(mana, player)
                        || !CubeComboAi.canPayCost(mana, player, false)) continue;
                int damage = ComputerUtilCombat.predictDamageTo(player, 1, card, false);
                if (player.getLife() <= damage) continue;
                boolean residualPayable;
                AiCardMemory.rememberCard(player, card, memory);
                try { residualPayable = CubeComboAi.canPayCost(new Cost("U", false), oracle, player, false); }
                finally { AiCardMemory.forgetCard(player, card, memory); }
                if (!residualPayable) continue;
                mana.setManaExpressChoice(ColorSet.fromMask(MagicColor.BLUE));
                stage = Stage.NONE;
                return mana;
            }
        }
        return null;
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
        SpellAbility painMana = oraclePainMana(librarySize);
        if (painMana != null) return painMana;
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
                // v78: the enabler, by the spell's own object id, exactly as
                // the ritual bridge above. Both stages are cleared by the next
                // priority pass, which re-derives from live state.
                || stage == Stage.ENABLER && top.getHostCard().getId() == enablerId
                || (stage == Stage.DOOMSDAY && top.getHostCard().getId() == doomsdayId)
                || stage == Stage.STAR && reservedStar != null && top.getHostCard().getId() == reservedStar.getId()
                || stage == Stage.DRAW && top.getHostCard().getName().equals(gushRoute ? "Gush" : "Ancestral Recall")
                || stage == Stage.ORACLE && top.getHostCard().getName().equals("Thassa's Oracle")
                // v51 route J: the object, not the name. Both stages are cleared
                // by the next priority pass, which re-derives from live state.
                || stage == Stage.JACE && top.getHostCard().getId() == doomsdayId
                || stage == Stage.FINISH && top.getHostCard().getId() == finisherId);
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
            if (threshold >= 5 && CubeComboAi.canPayCost(new Cost("B B B U U", false), doom, player, false)) {
                if (lethalOnBoard(true)) { decline = "better-attack"; return null; }
                return commitDoomsday(doom);
            }
            if (threshold < 4 || !player.canDrawAmount(1) || ownBlueSources() < 2) return null;
            if (lethalOnBoard(false)) { decline = "better-attack"; return null; }
            if (!clockSurvivable()) { decline = "clock"; return null; }
            SpellAbility action = commitDoomsday(doom);
            delayedHandOracle = inHand("Thassa's Oracle");
            delayedHandTurn = player.getGame().getPhaseHandler().getTurn();
            delayedDoomId = doom.getHostCard().getId();
            return action;
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

    /** v51 route J, section A of design v51. Is a "you win instead of drawing
     * from an empty library" replacement live on a permanent we control right
     * now? Recognised by printed property the way {@link CubeTopPlan} already
     * recognises its life-gain replacements and its cast-from-top permissions,
     * never by card name: this matches Jace, Wielder of Mysteries and
     * Laboratory Maniac, whose {@code R:} lines are identical, and matches
     * nothing else.
     *
     * <p>Only unhidden battlefield permanents are examined - a face-down
     * permanent has no abilities - and no hand, library or hidden face is
     * touched.</p>
     *
     * <p>Neither {@code canReplace} nor {@code requirementsCheck} may be used
     * here, and that is a measured fact rather than a caution: both run
     * {@code meetsCommonRequirements}, so both evaluate this effect's own
     * {@code IsPresent$ Card.YouOwn | PresentZone$ Library | PresentCompare$
     * EQ0} - which is precisely the empty library route J is engineering, and
     * is therefore false while the library still has cards in it. Asked before
     * the pile is drawn through, both answer "no" on a live Jace. The
     * diagnostic that established this is recorded in this run's
     * {@code diag/} folder.</p>
     *
     * <p>What replaces them is the v50 allow-list idiom
     * ({@link CubeTopPlan#drainPerOwnDraw}): the effect's whole parameter map
     * must be a subset of the printed set below, so an effect carrying any
     * requirement this plan cannot forecast - {@code PlayerTurn},
     * {@code ActivePhases}, a second {@code IsPresent}, a condition SVar - is
     * refused outright rather than approximated. Native {@link
     * forge.game.TriggerReplacementBase#zonesCheck} and {@code isSuppressed}
     * still gate the effect.</p> */
    private static final java.util.Set<String> WIN_REPLACEMENT_PARAMS = java.util.Set.of(
            "Event", "ActiveZones", "ValidPlayer", "IsPresent", "PresentZone", "PresentCompare",
            "ReplaceWith", "Description");

    private boolean emptyDrawWinLive() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (var replacement : card.getReplacementEffects()) {
                if (replacement.isSuppressed() || replacement.getMode() != ReplacementType.Draw) continue;
                if (!WIN_REPLACEMENT_PARAMS.containsAll(replacement.getMapParams().keySet())) continue;
                if (!"You".equals(replacement.getParam("ValidPlayer"))
                        || !"Card.YouOwn".equals(replacement.getParam("IsPresent"))
                        || !"Library".equals(replacement.getParam("PresentZone"))
                        || !"EQ0".equals(replacement.getParam("PresentCompare"))) continue;
                SpellAbility win = replacement.getOverridingAbility();
                if (win == null || win.getApi() != ApiType.WinsGame || !"You".equals(win.getParam("Defined"))) continue;
                if (win.getSubAbility() != null || !replacement.zonesCheck(card.getZone())) continue;
                return true;
            }
        }
        return false;
    }

    /** How many cards this ability would draw for us in one resolution, or 0
     * when it is not a draw-past-the-pile finisher. The whole
     * {@link SpellAbility#getSubAbility} chain is walked and the chain is
     * refused outright if any link carries {@code Destination$ Library} or
     * {@code Shuffle$ True} - that one clause is what separates Wheel of
     * Fortune and Memory Jar, which draw past the pile, from Timetwister, Time
     * Spiral and Echo of Eons, which refill the library first and win nothing.
     *
     * <p>A draw link counts only with a {@code Defined} that includes us
     * ({@code You}, or {@code Player} for the symmetric draw-sevens) and a
     * literal positive integer {@code NumCards}. A computed or absent amount is
     * refused rather than guessed, which is also what keeps Jace's own
     * {@code +1} - a targeted mill with an implicit single draw - out of this
     * route.</p> */
    private int drawsInChain(SpellAbility ability) {
        int draws = 0;
        for (SpellAbility link = ability; link != null; link = link.getSubAbility()) {
            if ("True".equals(link.getParam("Shuffle")) || "Library".equals(link.getParam("Destination"))) return 0;
            if (link.getApi() != ApiType.Draw) continue;
            String defined = link.getParamOrDefault("Defined", "You");
            if (!defined.equals("You") && !defined.equals("Player")) return 0;
            String amount = link.getParamOrDefault("NumCards", "");
            if (!amount.chars().allMatch(Character::isDigit) || amount.isEmpty()) return 0;
            int cards = Integer.parseInt(amount);
            if (cards <= 0) return 0;
            draws += cards;
        }
        return draws;
    }

    /** A cost that pays life must leave us alive: state-based actions would
     * kill us before any of the draws resolved. Property-based - every
     * {@link forge.game.cost.CostPayLife} part of the ability's own printed
     * cost, never a card name. When Doomsday is part of the route the life
     * total is read after Doomsday's own loss, using the same
     * {@code life - ceil(life / 2)} formula {@link #clockSurvivable} uses. A
     * non-literal amount is refused rather than evaluated. */
    private boolean survivesLifeCost(SpellAbility ability, boolean afterDoomsday) {
        int life = player.getLife();
        if (afterDoomsday) life -= (life + 1) / 2;
        int paid = 0;
        for (var part : ability.getPayCosts().getCostParts()) {
            if (!(part instanceof forge.game.cost.CostPayLife)) continue;
            Integer amount = part.convertAmount();
            if (amount == null) return false;
            paid += amount;
        }
        return life > paid;
    }

    /** Would Doomsday and this finisher both be payable out of one board? One
     * native probe over the combined mana, exactly the {@link
     * #payableAfterBridge} idiom, so a single source cannot pay for both
     * halves. The finisher's own full cost is checked separately because its
     * non-mana parts - Memory Jar's tap and self-sacrifice, Griselbrand's life
     * - are not mana at all; the two results are ANDed, so this can only ever
     * decline more than the combined probe alone, never commit where it
     * refuses. Nothing is cast, tapped or changed. */
    private boolean bothPayable(SpellAbility doom, SpellAbility finisher) {
        ManaCost extra = finisher.getPayCosts().getTotalMana();
        ManaCostBeingPaid combined = new ManaCostBeingPaid(doom.getPayCosts().getTotalMana());
        for (ManaCostShard shard : extra) combined.increaseShard(shard, 1);
        combined.increaseGenericMana(extra.getGenericCost());
        return CubeComboAi.canPayManaCost(combined, doom, player, false);
    }

    /** The finisher this board offers right now, or null. Hand spells first,
     * then activated abilities of permanents we control, in zone order.
     * {@code libraryAfter} is the library size the finisher would face: the
     * current size when it is cast alone, and exactly five behind a Doomsday
     * ({@code doomsday.txt ChangeNum$ 5}). We need one more draw than that, and
     * native {@link Player#canDrawAmount} is asked for exactly that many - the
     * gate that refuses an opposing Narset, Parter of Veils. */
    private SpellAbility pickFinisher(int libraryAfter, SpellAbility doom) {
        if (!player.canDrawAmount(libraryAfter + 1)) return null;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            SpellAbility found = finisherOn(card, true, libraryAfter, doom);
            if (found != null) return found;
        }
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            SpellAbility found = finisherOn(card, false, libraryAfter, doom);
            if (found != null) return found;
        }
        return null;
    }

    private SpellAbility finisherOn(Card card, boolean spell, int libraryAfter, SpellAbility doom) {
        if (card.isFaceDown() || !spell && card.getController() != player) return null;
        for (SpellAbility original : card.getSpellAbilities()) {
            if (original.isSpell() != spell) continue;
            if (!spell && original.isManaAbility()) continue;
            SpellAbility ability = original.copy(player);
            // Route J makes no targeting decision, so an ability that needs one
            // is not one it may take.
            if (ability.usesTargeting()) continue;
            if (drawsInChain(ability) <= libraryAfter) continue;
            if (!survivesLifeCost(ability, doom != null)) continue;
            if (!CubeComboAi.canPlayNative(ability, player) || !CubeComboAi.canPayCost(ability, player, false)) continue;
            if (doom != null && !bothPayable(doom, ability)) continue;
            return ability;
        }
        return null;
    }

    /** Design v51 section B, route J: reach an empty-library draw while a
     * printed "you win instead" replacement is live on our own battlefield.
     * Either the library is already small enough and the finisher is cast
     * alone, or Doomsday cuts it to five first.
     *
     * <p>Evaluated LAST, after every existing route has declined, so each
     * pre-existing decline keeps its own token and every currently-green board
     * still takes its current route. The first check is
     * {@link #emptyDrawWinLive}, so a board with no such replacement leaves the
     * caller's receipt byte for byte.</p>
     *
     * <p>No pile-content decision is made: {@link #ownsPileDecision} still
     * answers only for {@link Stage#DOOMSDAY}, so the ordinary AI picks the
     * five and {@link #orderPile} is never consulted. Route J passes no turn,
     * so {@link #clockSurvivable} is not the right guard and
     * {@link #lethalOnBoard}'s same-turn form is. Own-visible information only:
     * our own battlefield and hand, our library SIZE, our life, and both public
     * battlefields.</p> */
    private SpellAbility jaceRoute(int librarySize) {
        if (!emptyDrawWinLive() || player.cantWin()) return null;
        SpellAbility doom = null;
        SpellAbility finisher = pickFinisher(librarySize, null);
        if (finisher == null) {
            // Doomsday's ChangeNum$ 5 is the only thing it would change here,
            // so a library already at five or fewer gains nothing from it.
            if (librarySize <= 5 || player.getLife() <= 1) return null;
            doom = playable("Doomsday");
            if (doom == null) return null;
            finisher = pickFinisher(5, doom);
            if (finisher == null) return null;
        }
        if (lethalOnBoard(true)) { decline = "better-attack"; return null; }
        turn = player.getGame().getPhaseHandler().getTurn();
        jaceFinishes++;
        System.err.println("CUBE_COMBO jace-finish card=" + finisher.getHostCard().getName()
                + " draws=" + drawsInChain(finisher) + " library=" + librarySize
                + " doomsday=" + (doom != null));
        if (doom == null) {
            finisherId = finisher.getHostCard().getId();
            stage = Stage.FINISH;
            return finisher;
        }
        gushRoute = false;
        reservedStar = null;
        doomsdayId = doom.getHostCard().getId();
        oracleSelected = false;
        gushSelected = false;
        stage = Stage.JACE;
        return doom;
    }

    private SpellAbility commitDoomsday(SpellAbility doom) {
        delayedHandOracle = null;
        delayedHandTurn = -1;
        delayedDoomId = -1;
        turn = player.getGame().getPhaseHandler().getTurn();
        doomsdayId = doom.getHostCard().getId();
        oracleSelected = false;
        gushSelected = false;
        stage = Stage.DOOMSDAY;
        return doom;
    }


    /** v60 - the one card, fetched from our own library, that would complete
     * this plan's entry gate. That gate is Doomsday itself in our own HAND
     * ({@link #playable} reads no other zone for it) with an Oracle this plan
     * can still reach, a library big enough for a pile, a life total the pile's
     * own cost leaves us alive at, and Doomsday's printed BBB payable from our
     * own visible black sources.
     *
     * <p>Thassa's Oracle is never the completing piece: {@link
     * #availableInOwnDeck} already counts the library and the graveyard, which
     * are exactly the zones Doomsday searches, so an Oracle a tutor could find
     * is one this plan already calls available, and an Oracle that is not in
     * our own deck at all is one no search can find either.</p>
     *
     * <p>Doomsday's printed BBB is deliberately NOT tested here. It is a
     * RESOURCE gate, and this predicate tests the entry gate only, exactly as
     * {@code CubeStormPlan.completingPieceNames} states for its own downstream
     * gate; the mana is priced twice downstream anyway - by
     * {@code CubeComboAi.planFor}'s second-cast forecast and disjoint-source
     * reserve before the tutor is ever cast, and by {@link #playable} on the
     * turn this plan acts. There is a second, concrete reason: {@link
     * #ownVisibleBlack} asks a LIVE mana ability {@code canProduce}, and a live
     * ability whose activating player is null makes native Forge print a
     * "Did not have activator set" line. That is acceptable once inside
     * {@code pileAction}; it is not acceptable in a predicate consulted for
     * every tutor candidate of every priority pass.</p>
     *
     * <p>Own hand, own graveyard, our own registered deck composition, our own
     * library SIZE and our own life only. Entry gate only: which pile, and the
     * Gush/Star/Recall routes, are this plan's business on the turn it acts.</p> */
    static java.util.List<String> completingPieceNames(Player player) {
        CubeDoomsdayPlan plan = new CubeDoomsdayPlan(player);
        if (plan.inHand("Doomsday") != null || !plan.availableInOwnDeck("Thassa's Oracle")
                || player.getCardsIn(ZoneType.Library).size() <= 5 || player.getLife() <= 1)
            return java.util.List.of();
        return java.util.List.of("Doomsday");
    }

    /** v74 - the CONVERTIBILITY test {@link #completingPieceNames} is not and
     * was never meant to be. The entry gate above answers "is this family one
     * named card short"; this answers "with that card in our hand, would this
     * plan's own act-time logic still refuse, for a reason a later turn does
     * not fix". It exists because v63's C2 hand steering consulted only the
     * entry gate - which in a Doomsday deck is true on essentially every turn -
     * and so spent 11 of 11 measured Demonic Tutors on a Doomsday, of which 7
     * never produced a cast and 7 displaced a body the deck needed. See
     * runs/2026-09-12-doom-worsened-pair-v67/diagnosis.md sections 2, 5 and 6.
     *
     * <p>The routes below are {@link #pileAction}'s and {@link #naturalRoute}'s
     * own, in {@link #pileAction}'s own evaluation order, with exactly ONE
     * class of gate dropped: every MANA gate. A mana shortfall really is the
     * one thing a later turn fixes on its own - and the measured declines were
     * not mana ({@code mana:} appeared once, transiently) but
     * {@code no-pile-route}, a devotion and route-enabler shortfall that
     * waiting does not fix. So a board that is short only {@code BBB} still
     * accepts the steering, exactly as C2 intended; a board with no live route
     * no longer does.</p>
     *
     * <p>Concretely, and reusing this class's own predicates: Ancestral Recall
     * in hand with three draws left (the Recall route); or Gush in our own deck
     * with two Island-subtype lands on our own battlefield and then either Gush
     * itself in hand at a pile-sized library or a Chromatic Star of ours on the
     * battlefield (the Gush route - note that the Star must be IN PLAY, which
     * is exactly the shortfall the measured game had); or
     * {@link #oracleThreshold} >= 5 with Thassa's Oracle in hand, or >= 4
     * without it plus a draw and {@link #ownBlueSources} >= 2 (the two natural
     * routes).</p>
     *
     * <p>Three deliberate scoping decisions, each recorded rather than
     * discovered later:</p>
     * <ul>
     * <li>No payment probe is run. {@link #playable}, {@link #starAbility} and
     *     {@link #alternateGush} all call {@code canPayCost}, and
     *     {@link #ownVisibleBlack} asks a LIVE mana ability {@code canProduce};
     *     {@link #completingPieceNames}' own javadoc records why that is not
     *     acceptable in a predicate consulted per tutor candidate. Every read
     *     here is a structural one, and {@link #ownBlueSources} copies the
     *     ability with us as the activator for the same reason it already
     *     does.</li>
     * <li>The turn-local act-time gates are NOT part of this test:
     *     {@code better-attack}, {@code clock}, {@code stack-not-empty} and
     *     {@code phase}. They describe this turn, not the route, and the
     *     selection site has already applied its own window and its own
     *     {@code lethalOrdinaryAttackNow} test. {@code oracle-etb-disabled} IS
     *     applied, on the two natural routes alone, because that is where
     *     {@link #naturalRoute} applies it.</li>
     * <li>The library size read is the LIVE one, matching
     *     {@link #completingPieceNames}, not the post-fetch size, so this
     *     predicate can never contradict the gate it filters.</li>
     * </ul>
     *
     * <p>Own hand (the hypothetical one), own battlefield, own graveyard, our
     * own library SIZE and our own registered deck composition. Nothing
     * hidden, no RNG, no state change, and nothing printed.</p>
     *
     * @param hypotheticalHand our own hand as it would be AFTER the search
     * resolves - the live hand plus the card being considered. */
    static boolean wouldConvert(Player player, java.util.Collection<Card> hypotheticalHand) {
        CubeDoomsdayPlan plan = new CubeDoomsdayPlan(player);
        plan.handOverride = hypotheticalHand;
        return plan.routeLiveApartFromMana();
    }

    /** {@link #wouldConvert}'s body, an instance method so it reads through
     * this class's own predicates and the {@link #handOverride}. */
    private boolean routeLiveApartFromMana() {
        // The entry gate, re-read against the hypothetical hand.
        if (inHand("Doomsday") == null || player.getLife() <= 1 || !availableInOwnDeck("Thassa's Oracle")
                || player.getCardsIn(ZoneType.Library).size() <= 5) return false;
        // pileAction's Recall route, without its B B B U U U payment.
        if (player.canDrawAmount(3) && inHand("Ancestral Recall") != null) return true;
        // pileAction's Gush route, without its B B B U U payment: the deck
        // prerequisite, the two Islands, and then either the direct Gush or the
        // battlefield Chromatic Star the reservation needs.
        long islands = player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(card -> card.getType().hasSubtype("Island")).count();
        // v78 adds the third disjunct, and ONLY the third: an enabler in the
        // hypothetical hand that v78's own enablerAction would play onto our
        // battlefield. v74 required the Star to be IN PLAY, which is exactly the
        // shortfall the measured game had and exactly what this increment fixes;
        // the two pre-existing disjuncts are untouched, so no board that
        // converts today stops converting. canDrawAmount(3) matches
        // pileAction's own gate on the Star branch. NO payment probe is run
        // here - enablerShape is structural, for the reason this method's
        // javadoc and completingPieceNames' already record.
        if (availableInOwnDeck("Gush") && islands >= 2
                && (inHand("Gush") != null && gushReachesOracle(5) || ownStarInPlay() != null
                    || player.canDrawAmount(3) && enablerInHand() != null)) return true;
        // naturalRoute's two routes, with its own thresholds and its own
        // trigger-disabled gate, without its B B B U U / B B B payment.
        if (oracleTriggerDisabled()) return false;
        int threshold = oracleThreshold(false);
        if (inHand("Thassa's Oracle") != null)
            return threshold >= 5 || threshold >= 4 && player.canDrawAmount(1) && ownBlueSources() >= 2;
        return threshold >= 4 && player.canDrawAmount(1) && ownBlueSources() >= 2;
    }

    /** The Chromatic Star {@link #pileAction} would reserve for the Gush route,
     * found by the same battlefield scan it uses, minus the {@link
     * #starAbility} payment probe. Own public battlefield only. */
    private Card ownStarInPlay() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && card.getName().equals("Chromatic Star")) return card;
        return null;
    }

    /** Our own hand, or - inside {@link #wouldConvert} alone - the hypothetical
     * one. The same rule {@link #inHand} applies, as an iteration rather than a
     * name lookup. Every live decision path sees the real hand. */
    private Iterable<Card> ownHand() {
        return handOverride != null ? handOverride : player.getCardsIn(ZoneType.Hand);
    }

    /** v78 - the FIRST pile enabler in our own hand, or null. Structural only:
     * no {@code getSpellPermanent}, no {@link CubeComboAi#canPlayNative}, no
     * payment probe, nothing printed and no state changed, because
     * {@link #wouldConvert} consults this per tutor candidate per priority pass
     * and {@link #completingPieceNames}' javadoc records why a probe there is
     * unacceptable. {@link #enablerAction} adds the legality and payment on top.
     *
     * <p>Hand order is the scan order, which is our own zone's order and is
     * deterministic; the predicate below admits at most one shape of card, so a
     * second candidate would be an equivalent one.</p> */
    private Card enablerInHand() {
        for (Card card : ownHand()) if (!card.isFaceDown() && enablerShape(card) != null) return card;
        return null;
    }

    /** v78 - is this hand card the Gush route's own draw source, by PRINTED
     * property rather than by card name? Returns the mana ability that makes it
     * one, or null. Both halves are load-bearing:
     *
     * <ol>
     * <li><b>The mana shape {@link #starAbility} activates.</b> A mana ability
     *     able to produce BLUE (that method sets
     *     {@code setManaExpressChoice(BLUE)}) whose payment SACRIFICES the
     *     permanent itself and carries nothing besides a tap, mana and that
     *     self-sacrifice - the same cost allow-list {@link #floatBlue} and
     *     {@link #permanentManaAbility} already use, minus their zero-mana
     *     clause, because the Star's {@code {1}, {T}, Sacrifice} deliberately
     *     costs one.</li>
     * <li><b>The sacrifice-draw the route counts on.</b> After Doomsday the
     *     library is exactly five ({@code doomsday.txt ChangeNum$ 5}); the
     *     sacrifice draw takes it to four, and {@link #gushReachesOracle}(4)
     *     then asks {@code 4 - 2 <= oracleThreshold(true)}. Without the draw the
     *     same board would need a threshold of three, i.e. a different route.
     *     Recognised either as a printed
     *     {@code ChangesZone / Battlefield -> Graveyard / Card.Self} trigger
     *     drawing us one card, or as a {@code Draw} link in the mana ability's
     *     own sub-ability chain.</li>
     * </ol>
     *
     * <p>Chromatic Star matches on the trigger form and Chromatic Sphere on the
     * sub-ability form. Lion's Eye Diamond does NOT, and is refused by the
     * general rule rather than by name: its activation cost is
     * {@code Sac<1/CARDNAME> Discard<0/Hand>}, and a discard is neither a tap
     * nor a self-sacrifice - the same objection {@link #bridgeFrom}'s javadoc
     * already records for it, for the same reason (an honest model would have to
     * account for discarding Doomsday itself out of the same hand).</p>
     *
     * <p>Abilities are copied with us as the activator exactly as
     * {@link #ownBlueSources} does, and for its reason: asking a non-producing
     * intrinsic ability whether it could make U makes the engine fall back to
     * the host's controller and log it.</p> */
    private SpellAbility enablerShape(Card card) {
        for (SpellAbility original : card.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (ability.getManaPart() == null || !ability.canProduce("U")) continue;
            boolean sacrificesSelf = false, foreign = false;
            for (var part : ability.getPayCosts().getCostParts()) {
                if (part instanceof forge.game.cost.CostSacrifice sacrifice
                        && sacrifice.getType().equals("CARDNAME")) sacrificesSelf = true;
                else if (!(part instanceof forge.game.cost.CostTap
                        || part instanceof forge.game.cost.CostPartMana)) foreign = true;
            }
            if (!sacrificesSelf || foreign) continue;
            if (enablerDraw(ability) == 1 || sacrificeDrawsOne(card)) return ability;
        }
        return null;
    }

    /** How many cards the ability's own sub-ability chain draws for us, read the
     * way native {@code DrawEffect} reads it: an absent {@code NumCards} is one
     * and an absent {@code Defined} is {@code You}. Deliberately NOT
     * {@link #drawsInChain}, which is route J's and refuses an absent amount
     * outright; route J's receipts may not move for this. A computed amount, a
     * {@code Defined} that is not ours, or any shuffle/library destination is
     * refused rather than guessed. */
    private int enablerDraw(SpellAbility ability) {
        int draws = 0;
        for (SpellAbility link = ability; link != null; link = link.getSubAbility()) {
            if ("True".equals(link.getParam("Shuffle")) || "Library".equals(link.getParam("Destination"))) return 0;
            if (link.getApi() != ApiType.Draw) continue;
            String defined = link.getParamOrDefault("Defined", "You");
            if (!defined.equals("You") && !defined.equals("TriggeredCardController")) return 0;
            String amount = link.getParamOrDefault("NumCards", "1");
            if (amount.isEmpty() || !amount.chars().allMatch(Character::isDigit)) return 0;
            draws += Integer.parseInt(amount);
        }
        return draws;
    }

    /** The printed "when this is put into a graveyard from the battlefield,
     * draw a card" trigger, as a property. The executed ability lives in an
     * SVar, so it is parsed through the native ability factory with a cheap
     * {@code contains} pre-filter - {@code CubeComboAi.etbLibrarySearch}'s
     * idiom, and deliberately NOT {@code Trigger.ensureAbility()}, which caches
     * the parsed ability onto the live trigger and is therefore a state change
     * this forecast may not make. */
    private boolean sacrificeDrawsOne(Card card) {
        for (forge.game.trigger.Trigger trigger : card.getTriggers()) {
            if (trigger.isSuppressed() || trigger.getMode() != forge.game.trigger.TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParam("Origin"))
                    || !"Graveyard".equals(trigger.getParam("Destination"))) continue;
            String valid = trigger.getParam("ValidCard"), svar = trigger.getParam("Execute");
            if (valid == null || !valid.contains("Card.Self") || svar == null) continue;
            String printed = card.getSVar(svar);
            if (printed == null || !printed.contains("Draw")) continue;
            SpellAbility draw = forge.game.ability.AbilityFactory.getAbility(card, svar);
            if (draw != null && enablerDraw(draw) == 1) return true;
        }
        return false;
    }

    /** v78 - the enabler's own cast, or null. {@code CubeThopterPlan}'s
     * {@code assembleFromHand} pattern verbatim: the card's printed permanent
     * spell, copied with us as the activator, then native legality and native
     * payment. Never a name, never a forged cost. */
    private SpellAbility enablerCast(Card card) {
        var original = card.getSpellPermanent();
        if (original == null) return null;
        SpellAbility cast = original.copy(player);
        return CubeComboAi.canPlayNative(cast, player) && CubeComboAi.canPayCost(cast, player, false) ? cast : null;
    }

    /** Design v78 section 2, the pile-enabler action: put the Gush route's own
     * draw source onto our own battlefield, from our own hand.
     *
     * <p>The defect this closes is diagnosis section 6's second one -
     * {@code runs/2026-09-12-doom-worsened-pair-v67/diagnosis.md}: "Chromatic
     * Star was in hand from turn 2 to turn 15 and was never played, while the
     * Gush route the plan wants requires it on the battlefield. The plan has no
     * action that sets up its own enabler, and the ordinary AI had no reason to
     * cast it." The ordinary AI's reason is a printed one and is checked, not
     * assumed: {@code chromatic_star.txt} carries {@code AI:RemoveDeck:All} and
     * {@code AiController.getSpellAbilityToPlay} removes every candidate whose
     * host carries it, so there is no pass on which Default would have cast it.
     * The action therefore reaches the stack through the registered plan-action
     * bypass {@code CubeBombPlan.audit} documents, and still passes
     * {@code CubeComboPlayerController.playChosenSpellAbility}'s native-legality
     * guard and native payment. The receipt names the hint.</p>
     *
     * <p><b>Evaluated LAST</b>, after {@link #pileAction} and after v51's route
     * J, for route J's own reason: every decline token the routes above set is
     * preserved byte for byte on every board with no enabler in hand, and the
     * action can only ever spend mana on a pass where NO route above it wanted
     * any.</p>
     *
     * <p>The gates are the Gush route's own, read from {@link #pileAction}: the
     * pre-pile library size, Doomsday in our own hand, a life total the pile
     * leaves us alive at, an Oracle this plan can still reach, Gush in our own
     * deck, two Island-subtype lands of ours, three draws available, no Star
     * already in play, and no DIRECT Gush route (which needs no Star). Then the
     * printed enabler shape, its own legality and payment, and
     * {@link #oracleTriggerDisabled}, which is the one abstention that applies:
     * with creature ETB triggers switched off no pile route can win, so the mana
     * is wasted. {@link #lethalOnBoard} and {@link #clockSurvivable} are NOT
     * applied - they price route 2's turn-pass and Doomsday's half-life, and
     * this action passes no turn and pays no life.</p>
     *
     * <p>No whole-route mana forecast, on v74's registered reasoning: a mana
     * shortfall is the one thing a later turn fixes on its own, and the measured
     * declines were {@code no-pile-route}, not {@code mana:}. The only mana gate
     * is the enabler's own cast.</p>
     *
     * <p>At most ONE enabler per turn, {@link #ritualBridge}'s own rule, so a
     * countered enabler is not chased with a second card. Own-visible
     * information only: our own hand, our own battlefield, our own graveyard,
     * our own registered deck composition, our own library SIZE and our own
     * life.</p> */
    private SpellAbility enablerAction(int librarySize) {
        // The PRE-pile position only. After Doomsday the pile is exactly five
        // and the enabler is too late; recoverSmallLibrary's own battlefield
        // scan is a different decision and is a registered follow-up.
        if (librarySize <= 5) return null;
        if (inHand("Doomsday") == null || player.getLife() <= 1
                || !availableInOwnDeck("Thassa's Oracle")) return null;
        if (!availableInOwnDeck("Gush") || !player.canDrawAmount(3)) return null;
        if (player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(card -> card.getType().hasSubtype("Island")).count() < 2) return null;
        // The route already HAS its enabler, or does not need one.
        if (ownStarInPlay() != null) return null;
        if (inHand("Gush") != null && gushReachesOracle(5)) return null;
        Card enabler = enablerInHand();
        if (enabler == null) return null;
        // From here the board really does offer one, so a refusal is named.
        if (enablerTurn == player.getGame().getPhaseHandler().getTurn()) {
            decline = "other check=enabler-spent";
            return null;
        }
        if (oracleTriggerDisabled()) { decline = "oracle-etb-disabled"; return null; }
        SpellAbility cast = enablerCast(enabler);
        if (cast == null) { decline = "other check=enabler-uncastable"; return null; }
        gushRoute = false;
        reservedStar = null;
        turn = player.getGame().getPhaseHandler().getTurn();
        enablerTurn = turn;
        enablerId = cast.getHostCard().getId();
        stage = Stage.ENABLER;
        pileEnablers++;
        System.err.println("CUBE_COMBO pile-enabler card=" + enabler.getName().replace(' ', '_')
                + " route=gush remAIDeck=" + ComputerUtilCard.isCardRemAIDeck(enabler)
                + " turn=" + turn + " phase=" + player.getGame().getPhaseHandler().getPhase());
        return cast;
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
        // v78's enabler commits to nothing either. Its only job was to put a
        // pile enabler on our own battlefield, so the plan starts over here from
        // live state and the ordinary entry gates decide again; a countered
        // enabler leaves the plan declining rather than holding a memory.
        if (stage == Stage.RITUAL || stage == Stage.ENABLER) stage = Stage.NONE;
        // v51 route J owns its own two stages. Both re-derive every gate from
        // live state, exactly as the ritual bridge does: a countered Doomsday
        // or a removed Jace leaves the plan declining rather than holding a
        // remembered pile, and the pile itself is never remembered at all.
        if (stage == Stage.JACE || stage == Stage.FINISH) {
            stage = Stage.NONE;
            SpellAbility finish = jaceRoute(librarySize);
            if (finish == null) decline = "other check=jace-finisher";
            return finish;
        }
        SpellAbility pile = pileAction(librarySize);
        if (pile != null) return pile;
        // v51 route J, evaluated LAST so that every decline token the routes
        // above set is preserved byte for byte on every board that has no live
        // empty-draw win replacement.
        SpellAbility finish = jaceRoute(librarySize);
        if (finish != null) return finish;
        // v78's enabler, evaluated after route J for the same reason route J is
        // evaluated after the piles: it may only spend mana on a pass where no
        // route above it wanted any, and it must leave their tokens untouched.
        return enablerAction(librarySize);
    }

    /** Every pre-v51 route, unchanged. Split out of {@link #nextAction} only so
     * that route J can be evaluated strictly after all of them. */
    private SpellAbility pileAction(int librarySize) {
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
            if (oracle == null) {
                SpellAbility painMana = oraclePainMana(librarySize);
                if (painMana != null) return painMana;
                decline = "other check=oracleThreshold";
            }
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

    /** Own the exact hand Oracle only during this delayed route's waiting
     * period. The real resolved Doomsday, public library size and current
     * devotion establish the hold; a countered spell cannot establish it.
     * No next-turn draw means expiration, not an indefinite card-name hold. */
    public boolean holdDelayedOracle(SpellAbility spell) {
        if (delayedHandOracle == null || spell.getHostCard() != delayedHandOracle || !spell.isSpell()
                || !delayedHandOracle.isInZone(ZoneType.Hand)
                || delayedHandOracle.getController() != player) return false;
        int library = player.getCardsIn(ZoneType.Library).size();
        int now = player.getGame().getPhaseHandler().getTurn();
        if (now > delayedHandTurn + 1 || library > 5 || library <= oracleThreshold(false)
                || oracleThreshold(false) < 4 || oracleTriggerDisabled() || !player.canDrawAmount(1)
                || player.cantWin() || player.getOpponents().stream().anyMatch(p -> p.cantLose())) return false;
        return player.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c -> c.getId() == delayedDoomId);
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
