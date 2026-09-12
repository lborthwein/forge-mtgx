package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayLife;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;

/** v73 - the reanimator SELECTION plan. Designed in
 * {@code 2026-09-12-reanimator-v73/design.md} against the reanimation census in
 * {@code 2026-09-12-cubecobra-synergy-census/synergy-census.md} sections 4 and 6.
 *
 * <p><b>What this plan owns.</b> Three selections and one sequencing decision:
 * the Entomb / Unmarked Grave target, the looting discard, the reanimation
 * TARGET ranking for the spell form, and the window in which the search is
 * cast. It does NOT own the reanimation CAST: census section 6.5 records
 * Default casting Reanimate on Griselbrand and Animate Dead on Emrakul
 * correctly in prepared positions (v52 section 1, 4/4 rows each), and nothing
 * in this round contradicts it.</p>
 *
 * <p><b>Why the plan exists at all.</b> {@code ComputerUtil.isPlayingReanimator}
 * counts cards carrying {@code SVar:IsReanimatorCard}; exactly seven scripts in
 * the whole pinned cardsfolder carry it and NONE of the seven is among this
 * cube's 546 cards (verified against {@code inputs/cube.json} before this file
 * was written). The predicate is therefore unconditionally FALSE here and every
 * consumer of it - Survival of the Fittest's two branches, the
 * {@code ChangeZoneAllAi} branch, and the {@code AiController} /
 * {@code DiscardAi} protection guards - is dead. {@link #reanimationLive}
 * replaces it with an OWN-VISIBLE test by printed property.</p>
 *
 * <p><b>Information boundary.</b> Our own hand, our own battlefield, our own
 * graveyard, our own mana, plus PUBLIC graveyards, battlefields and life
 * totals. Our own LIBRARY is never iterated: the Entomb decision is made
 * without looking at it, and the Entomb selection happens at resolution over
 * the list the effect itself offers, which is Forge's own - the same boundary
 * {@code CubeTopPlan} (v50) and the v71 Tinker brief both state. No opponent
 * hand, library content or library order is read anywhere.</p>
 *
 * <p><b>Duplicated value terms.</b> The six v62 property tests live in
 * {@code CubeBombPlan} as {@code private}/{@code private static} members and
 * that file is owned by another increment this round, so they are duplicated
 * below word for word - the same choice, for the same reason, that
 * {@code CubeDrawOutPlan} made for v51's win-replacement detectors.</p> */
public final class CubeReanimatorPlan {
    /** Per-turn action cap, matching the other plans' bounded-action discipline. */
    private static final int ACTION_CAP = 2;
    /** How far a printed {@code SubAbility$} chain is followed. Bounded exactly
     * as {@code CubeBombPlan.CHAIN_DEPTH} bounds its own. */
    private static final int CHAIN_DEPTH = 8;
    /** v62's {@code VALUE_APIS}, duplicated. */
    private static final List<String> VALUE_APIS = List.of(
            "Sacrifice", "SacrificeAll", "Discard", "LoseLife", "Draw", "Destroy", "DestroyAll", "Token");
    /** v62's {@code VALUE_ZONE_APIS}, duplicated: admitted only when the move is
     * off a BATTLEFIELD, which is what separates Ashen Rider's exile from
     * Emrakul's graveyard shuffle. */
    private static final List<String> VALUE_ZONE_APIS = List.of("ChangeZone", "ChangeZoneAll");

    private final Player player;
    private SpellAbility selected;
    private int turn = -1, actions, failedTurn = -1;

    /** Diagnostic only, never read by a decision: how many searches this plan
     * proposed, how often it changed an Entomb selection, a discard and a
     * reanimation target. Test-visible statics, read and reset reflectively by
     * the fixture, exactly like {@code CubeDrawOutPlan.drawOutActions}. */
    static int planActions, entombSelections, discardSwaps, targetChanges, symmetryDeclines, holds;

    /** v76. Diagnostic only, never read by a decision: how often the AURA form's
     * target ranking replaced the ordinary answer. Kept SEPARATE from
     * {@link #targetChanges} so that v73's spell-form rows keep their registered
     * values unchanged and the two paths stay distinguishable in a receipt. */
    static int auraTargets;

    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@code CubeMonolithPlan.declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code action-cap},
     * {@code failed-this-turn}, {@code no-opponent},
     * {@code no-reanimation-spell}, {@code payload-ready}, {@code no-search},
     * {@code unpayable:search}, {@code search-window}, {@code no-value-target},
     * {@code exhume-symmetry}, or the default
     * {@code other check=reanimator-plan}. Every one names our own zones, our
     * own mana, a PUBLIC graveyard or a phase; none names an opponent hand,
     * library content, library order or decklist fact.</p> */
    private String decline = "other check=reanimator-plan";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline = reason; return null; }

    public CubeReanimatorPlan(Player player) { this.player = player; }

    // ----------------------------------------- the duplicated v62 value terms

    /** One {@code Key$ value} field of a printed ability script, or null. */
    private static String scriptParam(String body, String key) {
        for (String part : body.split("\\|")) {
            String field = part.trim();
            if (!field.startsWith(key + " ")) continue;
            return field.substring(key.length() + 1).trim();
        }
        return null;
    }

    /** Is this one printed ability body a value effect? */
    private static boolean valueEffect(String body) {
        String api = scriptParam(body, "DB$");
        if (api == null) return false;
        if (VALUE_APIS.contains(api)) return true;
        return VALUE_ZONE_APIS.contains(api) && "Battlefield".equals(scriptParam(body, "Origin$"));
    }

    /** Does this value effect have something to resolve against? Coarse by
     * design: the restriction string is never instantiated. */
    private boolean valueTargetAvailable(String body) {
        String targets = scriptParam(body, "ValidTgts$");
        if (targets == null) return true;
        if (targets.contains("Player") || targets.contains("Opponent")) return true;
        for (Player opponent : player.getOpponents())
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
                if (!card.isFaceDown()) return true;
        return false;
    }

    private boolean chainHasValue(Card card, String svar) {
        for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
            String body = card.getSVar(svar);
            if (body == null || body.isEmpty()) break;
            if (valueEffect(body) && valueTargetAvailable(body)) return true;
            svar = scriptParam(body, "SubAbility$");
        }
        return false;
    }

    /** v62 term 3. A printed enters-the-battlefield trigger of the card itself
     * that acts on the public board or on the opponent. */
    private boolean entersWithValue(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParam("Destination"))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            if (chainHasValue(card, trigger.getParam("Execute"))) return true;
        }
        return false;
    }

    /** v62 term 4. A printed trigger a sacrifice of the card will fire.
     * {@code Origin$ Battlefield} is REQUIRED, which is what excludes the
     * {@code Origin$ Any} graveyard-shuffle triggers. */
    private boolean diesWithValue(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            if (trigger.getMode() == TriggerType.Sacrificed) {
                if (chainHasValue(card, trigger.getParam("Execute"))) return true;
                continue;
            }
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParamOrDefault("Origin", ""))) continue;
            String destination = trigger.getParam("Destination");
            if (destination != null && !"Graveyard".equals(destination) && !"Any".equals(destination)) continue;
            if (chainHasValue(card, trigger.getParam("Execute"))) return true;
        }
        return false;
    }

    /** v62 term 5's precondition, and the census's headline refusal. Both
     * printed shapes: Blightsteel Colossus's {@code R:Event$ Moved} REPLACEMENT
     * of the move to the graveyard, and the self trigger on reaching the
     * graveyard whose chain moves it back OUT again (Emrakul, Ulamog the
     * Infinite Gyre, Worldspine Wurm). Our own card's printed text only; public
     * graveyard hate is NOT read. */
    private static boolean staysInGraveyard(Card card) {
        for (var replacement : card.getReplacementEffects()) {
            if (replacement.getMode() != forge.game.replacement.ReplacementType.Moved) continue;
            if (!"Graveyard".equals(replacement.getParam("Destination"))) continue;
            if (!replacement.getParamOrDefault("ValidCard", "").startsWith("Card.Self")) continue;
            return false;
        }
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Graveyard".equals(trigger.getParamOrDefault("Destination", ""))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            String svar = trigger.getParam("Execute");
            for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
                String body = card.getSVar(svar);
                if (body == null || body.isEmpty()) break;
                String api = scriptParam(body, "DB$");
                if (VALUE_ZONE_APIS.contains(api)
                        && "Graveyard".equals(scriptParam(body, "Origin$"))
                        && !"Graveyard".equals(scriptParam(body, "Destination$"))) return false;
                svar = scriptParam(body, "SubAbility$");
            }
        }
        return true;
    }

    /** v62 term 6. A printed activated ability that draws for a literal life
     * payment we can afford - Griselbrand's {@code PayLife<7>: Draw 7}. */
    private boolean drawsForLife(Card payload) {
        for (SpellAbility ability : payload.getSpellAbilities()) {
            if (!ability.isActivatedAbility() || ability.getApi() != ApiType.Draw) continue;
            Cost cost = ability.getPayCosts();
            if (cost == null) continue;
            for (CostPart part : cost.getCostParts()) {
                if (!(part instanceof CostPayLife)) continue;
                Integer amount = literalAmount(part);
                if (amount != null && player.getLife() > amount) return true;
            }
        }
        return false;
    }

    /** A cost amount that is a printed literal, or null when it is an SVar or
     * X. {@code CubeBombPlan.literalAmount}, duplicated. */
    private static Integer literalAmount(CostPart part) {
        try { return part.convertAmount(); } catch (RuntimeException notLiteral) { return null; }
    }

    /** What our own board produces on its next untap. Duplicated from
     * {@code CubeBombPlan.nextUntapMana}: the reanimation follow-up is cast on a
     * LATER turn, so an untapped-only count would understate it. Own-visible
     * only, and not a colour-aware payment. */
    private int nextUntapMana() {
        int sources = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            for (SpellAbility ability : card.getManaAbilities())
                if (ability.getPayCosts() != null && ability.getPayCosts().getTotalMana().getCMC() == 0) { sources++; break; }
        }
        return sources;
    }

    // ------------------------------------------ the own-visible reanimator test

    /** The SPELL form of a reanimation: a creature card moved from a graveyard
     * onto the battlefield. Reanimate, Death (Life // Death), Persist, Exhume,
     * Shallow Grave, Corpse Dance, From the Catacombs, and Recurring
     * Nightmare's activated ability all match by printed property. */
    private static boolean reanimationShape(SpellAbility sa) {
        return sa != null && sa.getApi() == ApiType.ChangeZone
                && "Graveyard".equals(sa.getParam("Origin"))
                && "Battlefield".equals(sa.getParam("Destination"))
                && (sa.getParamOrDefault("ChangeType", "").startsWith("Creature")
                        || sa.getParamOrDefault("ValidTgts", "").startsWith("Creature"));
    }

    /** Does this printed zone-change name a creature card? v62's wording, kept
     * because the {@code Defined$ Enchanted} clause is what admits Animate Dead
     * and the creature clause is what excludes Titania's land return. */
    private static boolean namesACreature(Card card, String body) {
        String type = scriptParam(body, "ChangeType$"), targets = scriptParam(body, "ValidTgts$");
        if (type != null && type.startsWith("Creature") || targets != null && targets.startsWith("Creature")) return true;
        return "Enchanted".equals(scriptParam(body, "Defined$")) && card.hasStartOfKeyword("Enchant:Creature");
    }

    /** The AURA form: a permanent whose own enters-trigger chain reanimates a
     * creature. Animate Dead, Necromancy. */
    private static boolean entersReanimates(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParam("Destination"))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            String svar = trigger.getParam("Execute");
            for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
                String body = card.getSVar(svar);
                if (body == null || body.isEmpty()) break;
                if ("ChangeZone".equals(scriptParam(body, "DB$"))
                        && "Graveyard".equals(scriptParam(body, "Origin$"))
                        && "Battlefield".equals(scriptParam(body, "Destination$"))
                        && namesACreature(card, body)) return true;
                svar = scriptParam(body, "SubAbility$");
            }
        }
        return false;
    }

    /** Census section 2 item 5: a STANDING reanimation engine already on our own
     * battlefield - Recurring Nightmare, Virtue of Persistence - needs no spell
     * in hand and no horizon, and v62's term 5 cannot see it. Recognised by the
     * same printed shape, as an ACTIVATED ability of a permanent we control. */
    private static boolean standingEngine(Card card) {
        for (SpellAbility ability : card.getSpellAbilities())
            if (ability.isActivatedAbility() && reanimationShape(ability)) return true;
        return false;
    }

    /** The own-visible replacement for {@code isPlayingReanimator}: a
     * reanimation spell in our own HAND whose mana value our own board will
     * produce on its next untap, an Aura in our hand with the same horizon, or a
     * standing engine already on our own battlefield. Our hand, our battlefield
     * and our mana only. */
    public boolean reanimationLive() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && standingEngine(card)) return true;
        int mana = nextUntapMana();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || card.getCMC() > mana) continue;
            for (SpellAbility ability : card.getSpellAbilities())
                if (ability.isSpell() && reanimationShape(ability)) return true;
            if (card.isPermanent() && entersReanimates(card)) return true;
        }
        return false;
    }

    // --------------------------------------------------- the payload ranking

    /** The one ranking all three selections ask. {@code -1} is a HARD refusal,
     * not a low tier: a body that leaves the graveyard on arrival is worth
     * nothing to entomb, to pitch or to return, and the census names four of
     * them in this cube (Emrakul, Blightsteel Colossus, Worldspine Wurm, Ulamog
     * the Infinite Gyre).
     *
     * <p>Tier 3 is v62's terms 3 and 4 - the board the body changes whether or
     * not it connects, which is exactly what {@code evaluateCreature} and
     * {@code getMostExpensivePermanentAI} cannot see. Tier 2 is term 6. Tier 1
     * is a plain body that stays. Every tier is a printed property, so the
     * census's measured order (Griselbrand, Archon of Cruelty, Grave Titan,
     * Atraxa, Woodfall Primus, Ashen Rider) falls out of the terms rather than
     * being written down as a name list.</p> */
    private int payloadTier(Card card) {
        if (card == null || !card.isCreature()) return -1;
        if (!staysInGraveyard(card)) return -1;
        if (entersWithValue(card) || diesWithValue(card)) return 3;
        if (drawsForLife(card)) return 2;
        return 1;
    }

    /** Tier first, then the native creature evaluation inside a tier - the same
     * two-key ordering {@code CubeBombPlan.bestValuePayload} uses. */
    private Card bestPayload(List<Card> pool, int floor) {
        Card best = null;
        int bestTier = floor - 1, bestRank = -1;
        for (Card candidate : pool) {
            int tier = payloadTier(candidate);
            if (tier < floor) continue;
            int value = ComputerUtilCard.evaluateCreature(candidate);
            if (tier < bestTier || tier == bestTier && value <= bestRank) continue;
            best = candidate; bestTier = tier; bestRank = value;
        }
        return best;
    }

    /** The best body already sitting in our OWN graveyard, or null. */
    private Card graveyardPayload(int floor) {
        return bestPayload(new ArrayList<>(player.getCardsIn(ZoneType.Graveyard)), floor);
    }

    // ------------------------------------- selection 1: the Entomb/Grave target

    /** The printed shape of a graveyard tutor: our own library searched for one
     * card that goes to a graveyard. Entomb and Unmarked Grave; never a name. */
    private static boolean searchShape(SpellAbility sa) {
        return sa != null && sa.getApi() == ApiType.ChangeZone
                && "Library".equals(sa.getParam("Origin"))
                && "Graveyard".equals(sa.getParam("Destination"))
                && !sa.usesTargeting();
    }

    /** Is this resolving ability a graveyard tutor of ours whose selection this
     * plan may answer? Called only from
     * {@link CubeComboPlayerController#chooseSingleCardForZoneChange}, which has
     * already checked that the chooser is us, the ability is ours and every
     * offered card is one of ours in our own library. */
    public boolean ownsSearchSelection(SpellAbility source) {
        return searchShape(source) && reanimationLive();
    }

    /** The card this search should put into our graveyard, or null to keep the
     * ordinary answer. The offered list is the effect's own; our library is
     * never iterated by this plan. */
    public Card chooseSearchPayload(List<Card> options) {
        return bestPayload(options, 1);
    }

    // --------------------------------------- selection 2: the looting discard

    /** The payload to pitch instead of one ordinary pick, or null to leave the
     * ordinary choice alone. Only a tier-2-or-better body is worth spending a
     * discard on: a plain tier-1 body is not what makes holding a reanimation
     * spell pay, and swapping for one would move receipts for nothing.
     *
     * <p>{@code ordinary} is the ordinary AI's own choice and is never resized;
     * a card already in it, a card the reanimation line itself needs, and any
     * card that is not a legal discard are all refused. v49's contract
     * verbatim.</p> */
    public Card chooseDiscardPayload(List<Card> valid, List<Card> ordinary) {
        if (!reanimationLive()) return null;
        List<Card> pool = new ArrayList<>();
        for (Card card : valid) {
            if (ordinary.contains(card) || card.isFaceDown()) continue;
            boolean reanimation = false;
            for (SpellAbility ability : card.getSpellAbilities())
                if (ability.isSpell() && reanimationShape(ability)) reanimation = true;
            if (reanimation || card.isPermanent() && entersReanimates(card)) continue;
            pool.add(card);
        }
        return bestPayload(pool, 2);
    }

    /** Which of the ordinary AI's own picks the payload replaces: the one this
     * plan values least, by the same two keys the ranking uses, first wins ties.
     * Deterministic over the list the ordinary AI produced, so the swap cannot
     * depend on iteration order anywhere else. */
    public Card leastValuedDiscard(List<Card> ordinary) {
        Card worst = null;
        int worstTier = Integer.MAX_VALUE, worstRank = Integer.MAX_VALUE;
        for (Card card : ordinary) {
            int tier = payloadTier(card), value = ComputerUtilCard.evaluateCreature(card);
            if (tier > worstTier || tier == worstTier && value >= worstRank) continue;
            worst = card; worstTier = tier; worstRank = value;
        }
        return worst;
    }

    /** Observability + the diagnostic counter for a swap the controller applied. */
    public void recordDiscardSwap(Card payload, Card instead, String source) {
        discardSwaps++;
        System.err.println("CUBE_REANIMATOR discard=" + payload.getName().replace(' ', '_')
                + " instead=" + instead.getName().replace(' ', '_')
                + " source=" + source.replace(' ', '_')
                + " turn=" + player.getGame().getPhaseHandler().getTurn());
    }

    /** Observability + the diagnostic counter for an Entomb selection the
     * controller applied. */
    public void recordSearchSelection(Card payload, Card instead, String source) {
        entombSelections++;
        System.err.println("CUBE_REANIMATOR entomb=" + payload.getName().replace(' ', '_')
                + " instead=" + (instead == null ? "none" : instead.getName().replace(' ', '_'))
                + " source=" + source.replace(' ', '_')
                + " turn=" + player.getGame().getPhaseHandler().getTurn());
    }

    // ------------------------------ selection 3: the reanimation target ranking

    /** The reanimation target this plan prefers over the ordinary answer, or
     * null to keep it. {@code list} is the NATIVE candidate list, already
     * filtered by the printed {@code ValidTgts$} - which is how Persist's
     * {@code Creature.nonLegendary+YouOwn} restriction is honoured without this
     * class knowing it exists - and {@code ordinary} is the answer
     * {@code getMostExpensivePermanentAI} already produced.
     *
     * <p>Static and guarded on {@link CubeComboAi#enabled} because the one place
     * Forge chooses this target is inside {@code ChangeZoneAi.isPreferredTarget},
     * where no controller hook exists. A Default seat can never reach the
     * ranking.</p>
     *
     * <p><b>v77 D1. The SHAPE gate, which v73 omitted.</b>
     * {@code ChangeZoneAi.isPreferredTarget} reaches its target chooser for
     * EVERY known-origin zone change whose {@code destination == Battlefield}
     * OR whose {@code origin} contains {@code Battlefield}, and it picks by
     * {@code getBestRemovalTargetAI} on the second of those - a BOUNCE, an
     * EXILE removal, a tuck or a blink. v73 hooked the line AFTER the ternary,
     * so {@link #payloadTier} - a ranking whose whole content is "prefer a body
     * that STAYS in the graveyard" - was overriding the removal chooser on
     * abilities that are not reanimation at all. The v74 pooled read measured
     * it: eleven {@code CUBE_REANIMATOR target=… instead=…} lines with
     * {@code target != instead} on the Thopter deck, every one of them sourced
     * from Jace, the Mind Sculptor's {@code -1} ({@code Origin$ Battlefield |
     * Destination$ Hand}), bouncing Tough Cookie over Vorinclex and
     * Reclamation Sage over Tireless Tracker. Those outcomes did not move, but
     * they are CHANGED DECISIONS outside this hook's registered scope.
     *
     * <p>The gate is {@link #reanimationShape}, the same printed predicate the
     * plan's entry test, its hidden-form chooser and its discard filter already
     * use: {@code ApiType.ChangeZone}, {@code Origin$ Graveyard},
     * {@code Destination$ Battlefield}, a creature {@code ChangeType}/
     * {@code ValidTgts}. It admits every form v73 and amendment 1 registered -
     * the SPELL (Reanimate, Persist, From the Catacombs), Necromancy's targeted
     * {@code RaiseDead} ChangesZone trigger, and Portal to Phyrexia's upkeep
     * PHASE trigger - and refuses every shape whose target is chosen by
     * {@code getBestRemovalTargetAI}, because such an ability necessarily has
     * {@code Origin$ Battlefield} and so cannot have {@code Origin$
     * Graveyard}. {@code ChangeZoneAi} carries the matching structural guard at
     * the call site.</p> */
    public static Card preferReanimationTarget(Player ai, SpellAbility sa, Iterable<Card> list, Card ordinary) {
        if (!CubeComboAi.enabled(ai) || sa == null || sa.getActivatingPlayer() != ai) return null;
        if (!reanimationShape(sa)) return null; // v77 D1: reanimation only, never bounce/exile/tuck.
        CubeReanimatorPlan plan = ((CubeComboPlayerController) ai.getController()).reanimatorPlan();
        return plan == null ? null : plan.preferTarget(sa, list, ordinary);
    }

    private Card preferTarget(SpellAbility sa, Iterable<Card> list, Card ordinary) {
        List<Card> pool = new ArrayList<>();
        for (Card card : list) if (sa.canTarget(card)) pool.add(card);
        if (pool.size() < 2) return null;
        Card best = bestPayload(pool, 1);
        if (best == null || best == ordinary) return null;
        targetChanges++;
        System.err.println("CUBE_REANIMATOR target=" + best.getName().replace(' ', '_')
                + " instead=" + (ordinary == null ? "none" : ordinary.getName().replace(' ', '_'))
                + " source=" + sa.getHostCard().getName().replace(' ', '_')
                + " turn=" + player.getGame().getPhaseHandler().getTurn());
        return best;
    }

    // ------------------------- v76 selection 4: the AURA form's target ranking

    /** v76. A body whose OWN enters-the-battlefield chain moves permanents WE
     * control off the battlefield - the lock piece.
     *
     * <p>v62's terms 3 and 4, which {@link #auraTier} inherits through
     * {@link #entersWithValue}, score a {@code ChangeZone}/{@code ChangeZoneAll}
     * off a battlefield as VALUE without reading WHOSE battlefield. Measured
     * against the pinned cardsfolder, exactly two creatures carry that shape on a
     * self enters-trigger: Worldgorger Dragon ({@code ChangeType$
     * Permanent.YouCtrl+StrictlyOther | Origin$ Battlefield | Destination$
     * Exile}, a 7/7) and Denizen of the Deep ({@code ChangeType$
     * Creature.Other+YouCtrl | Origin$ Battlefield | Destination$ Hand}, an
     * 11/11). Both would score tier 3 and both would outrank every honest
     * payload on the second key, so this is a refusal the ranking cannot do
     * without.</p>
     *
     * <p>For Animate Dead the guard is REDUNDANT with that card's printed
     * {@code SVar:AttachAITgts:Creature.!namedWorldgorger Dragon}, which
     * {@code ComputerUtil.filterAITgts} applies with {@code alwaysStrict} before
     * the list this class ever sees; the {@code aura-worldgorger} fixture row
     * measures that. It earns its place on Denizen of the Deep, which no
     * {@code AttachAITgts} names, and on any aura script carrying none.</p>
     *
     * <p>Our own card's printed text only. Deliberately NOT applied to
     * {@link #payloadTier}: widening that would change v73's shipped entomb,
     * discard and spell-form behaviour, which is a different increment with
     * different evidence (design.md section 3.2 registers the exposure).</p> */
    private static boolean selfHarmingArrival(Card card) {
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone) continue;
            if (!"Battlefield".equals(trigger.getParam("Destination"))) continue;
            if (!"Card.Self".equals(trigger.getParamOrDefault("ValidCard", ""))) continue;
            String svar = trigger.getParam("Execute");
            for (int hop = 0; hop < CHAIN_DEPTH && svar != null && !svar.isEmpty(); hop++) {
                String body = card.getSVar(svar);
                if (body == null || body.isEmpty()) break;
                String type = scriptParam(body, "ChangeType$");
                if (VALUE_ZONE_APIS.contains(scriptParam(body, "DB$"))
                        && "Battlefield".equals(scriptParam(body, "Origin$"))
                        && type != null && type.contains("YouCtrl")) return true;
                svar = scriptParam(body, "SubAbility$");
            }
        }
        return false;
    }

    /** v76. {@link #payloadTier} for the AURA form, and the ONE term that
     * differs is {@code staysInGraveyard}, which is DROPPED.
     *
     * <p>{@code payloadTier} answers {@code -1} for Emrakul, Ulamog the Infinite
     * Gyre, Worldspine Wurm and Blightsteel Colossus because a body that leaves
     * the graveyard on arrival is worth nothing to ENTOMB, to PITCH or to set up.
     * The aura form runs in the opposite direction: the card is ALREADY in a
     * graveyard and the aura is taking it OUT. Reusing the refusal here would
     * make the plan pass over a 15/15 and reanimate a Grizzly Bears - a strict
     * regression against Default. The {@code aura-shuffler} fixture row is that
     * negative.</p>
     *
     * <p>Everything else is {@code payloadTier} verbatim: v62's terms 3 and 4 at
     * tier 3, term 6 at tier 2, a plain body at tier 1, plus the lock-piece
     * refusal of {@link #selfHarmingArrival}.</p> */
    private int auraTier(Card card) {
        if (card == null || !card.isCreature()) return -1;
        if (selfHarmingArrival(card)) return -1;
        if (entersWithValue(card) || diesWithValue(card)) return 3;
        if (drawsForLife(card)) return 2;
        return 1;
    }

    /** {@link #bestPayload}'s two-key ordering over {@link #auraTier}: tier
     * first, then the native creature evaluation inside a tier, first wins ties.
     * Deterministic over the list the native code produced. */
    private Card bestAuraTarget(List<Card> pool) {
        Card best = null;
        int bestTier = 0, bestRank = -1;
        for (Card candidate : pool) {
            int tier = auraTier(candidate);
            if (tier < 1) continue;
            int value = ComputerUtilCard.evaluateCreature(candidate);
            if (tier < bestTier || tier == bestTier && value <= bestRank) continue;
            best = candidate; bestTier = tier; bestRank = value;
        }
        return best;
    }

    /** v76 - census section 6.4 and v73's registered limitation 1: the target of
     * our own reanimation AURA.
     *
     * <p>The one place Forge chooses it is
     * {@code AttachAi.attachAIReanimatePreference}, whose answer is
     * {@code ComputerUtilCard.getBestCreatureAI} - a BODY SCORE that cannot see
     * that Ashen Rider's enters-trigger exiles a permanent or that Archon of
     * Cruelty's is a three-part Sacrifice + Discard + LoseLife. No controller
     * hook exists for it, so this entry point is static and guarded on
     * {@link CubeComboAi#enabled}, exactly as {@link #preferReanimationTarget}
     * is for the spell form. A Default seat can never reach the ranking.</p>
     *
     * <p>{@code list} is the NATIVE candidate list -
     * {@code AttachAi}'s {@code betterList}, already cut by the aura's printed
     * {@code Enchant} restriction, by {@code canBeAttached} under the aura's own
     * animated LKI, and by {@code ComputerUtil.filterAITgts} applying the printed
     * {@code AttachAITgts}. Consuming that list rather than rebuilding one is how
     * every printed restriction is honoured without this class knowing any of
     * them exists.</p>
     *
     * <p>Answers null - so the ordinary answer stands byte-identically - for
     * every seat that is not this policy, every ability that is not ours, every
     * attach source that is not a reanimation aura by printed shape, every list
     * of fewer than two legal candidates, and whenever the ranking agrees with
     * {@code ordinary}.</p> */
    public static Card preferAuraTarget(Player ai, SpellAbility sa, Card aura, Iterable<Card> list, Card ordinary) {
        if (!CubeComboAi.enabled(ai) || sa == null || aura == null
                || sa.getActivatingPlayer() != ai || !entersReanimates(aura)) return null;
        CubeReanimatorPlan plan = ((CubeComboPlayerController) ai.getController()).reanimatorPlan();
        return plan == null ? null : plan.preferAura(sa, aura, list, ordinary);
    }

    private Card preferAura(SpellAbility sa, Card aura, Iterable<Card> list, Card ordinary) {
        List<Card> ours = new ArrayList<>(), theirs = new ArrayList<>();
        for (Card card : list) {
            if (card == null) continue;
            (card.getOwner() == player ? ours : theirs).add(card);
        }
        if (ours.size() + theirs.size() < 2) return null;
        // Our own graveyard first. A PUBLIC opponent graveyard is read only to
        // ask whether it holds a STRICTLY better answer than ours, and a tie
        // keeps ours - the same public-graveyard boundary the v73 Exhume
        // symmetry refusal already works inside.
        Card best = bestAuraTarget(ours), rival = bestAuraTarget(theirs);
        boolean fromOpponent = rival != null && (best == null
                || auraTier(rival) > auraTier(best)
                || auraTier(rival) == auraTier(best)
                        && ComputerUtilCard.evaluateCreature(rival) > ComputerUtilCard.evaluateCreature(best));
        if (fromOpponent) best = rival;
        if (best == null || best == ordinary) return null;
        auraTargets++;
        System.err.println("CUBE_REANIMATOR aura-target=" + best.getName().replace(' ', '_')
                + " instead=" + (ordinary == null ? "none" : ordinary.getName().replace(' ', '_'))
                + " source=" + aura.getName().replace(' ', '_')
                + " from=" + (fromOpponent ? "opponent" : "own")
                + " turn=" + player.getGame().getPhaseHandler().getTurn());
        return best;
    }

    /** Exhume's symmetry, and the only place a PUBLIC graveyard is read. Exhume
     * returns a creature for EACH player; the opponent's graveyard is public, so
     * a position where their best body beats ours is one this plan must not
     * improve. It declines to own the choice and the ordinary answer stands. */
    private boolean symmetricAndLosing(SpellAbility source, int ourTier) {
        if (!"True".equals(source.getParamOrDefault("Mandatory", ""))
                || !source.hasParam("DefinedPlayer")) return false;
        for (Player opponent : player.getOpponents())
            for (Card card : opponent.getCardsIn(ZoneType.Graveyard))
                if (payloadTier(card) > ourTier) return true;
        return false;
    }

    /** The hidden form (Exhume, Shallow Grave, Corpse Dance): no target, the
     * choice is made at RESOLUTION over a graveyard. Answered through the v56
     * controller hook, which has already checked that the chooser is us, the
     * ability is ours, the origin is a graveyard and the destination is the
     * battlefield.
     *
     * <p>{@link #reanimationLive} is deliberately NOT asked here: the
     * reanimation spell is on the stack resolving, so it is no longer in our
     * hand, and the shape of the RESOLVING ability is the own-visible test that
     * replaces it.</p> */
    public Card chooseGraveyardReturn(SpellAbility source, List<Card> options) {
        if (!reanimationShape(source)) return null;
        Card best = bestPayload(options, 1);
        if (best == null) return null;
        if (symmetricAndLosing(source, payloadTier(best))) {
            // A WITNESSED refusal: the plan had a strictly better answer than
            // the ordinary chooser's and declined to take it, because the same
            // spell hands the opponent a body this one cannot beat. Without the
            // line the row would be indistinguishable from "the plan was never
            // consulted", which is the weaker claim.
            decline = "exhume-symmetry"; symmetryDeclines++;
            System.err.println("CUBE_REANIMATOR exhume-symmetry declined=" + best.getName().replace(' ', '_')
                    + " source=" + source.getHostCard().getName().replace(' ', '_')
                    + " turn=" + player.getGame().getPhaseHandler().getTurn());
            return null;
        }
        targetChanges++;
        System.err.println("CUBE_REANIMATOR return=" + best.getName().replace(' ', '_')
                + " source=" + source.getHostCard().getName().replace(' ', '_')
                + " turn=" + player.getGame().getPhaseHandler().getTurn());
        return best;
    }

    // ------------------------------------------------ sequencing: the search

    private String gateReason() {
        var phase = player.getGame().getPhaseHandler();
        if (failedTurn == turn) return "failed-this-turn";
        if (actions >= ACTION_CAP) return "action-cap";
        if (player.cantWin()) return "cant-win";
        if (!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        if (player.getOpponents().size() != 1) return "no-opponent";
        return "phase";
    }

    /** Our own main phase, or the end step of a turn that is not ours - the two
     * windows in which a graveyard tutor is worth casting. */
    private boolean ownMain() {
        var phase = player.getGame().getPhaseHandler();
        return phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player);
    }

    private boolean opponentEndStep() {
        var phase = player.getGame().getPhaseHandler();
        return phase.getPhase() == PhaseType.END_OF_TURN && !phase.isPlayerTurn(player);
    }

    /** The missing phase rule the census names, exposed for
     * {@code ChangeZoneAi.checkPhaseRestrictions}. That method has phase rules
     * only for {@code Hand <- Graveyard} and {@code Library <- Graveyard}, so an
     * INSTANT graveyard tutor has no end-of-turn preference at all and the
     * ordinary AI casts it at the first opportunity it is offered - putting the
     * payload in the graveyard a whole opponent turn before the reanimation
     * spell it is setting up, and handing them a graveyard-hate window for
     * nothing.
     *
     * <p>A plan DECLINE is not enough on its own: declining only means
     * {@code chooseSpellAbilityToPlay} falls through to the ordinary AI, which
     * then casts the search anyway. That was measured, not assumed, on
     * {@code probe-2}'s {@code entomb-endstep} row, where the plan held and
     * Default cast Entomb in MAIN2 regardless. This is the hold.</p>
     *
     * <p>Answers false for every seat that is not this policy, for every
     * ability that is not a graveyard tutor, and for every board on which the
     * plan is not actively sequencing one - so an ordinary decision reaches the
     * caller unchanged.</p> */
    public static boolean holdSearch(Player ai, SpellAbility sa) {
        if (!CubeComboAi.enabled(ai) || sa == null || !searchShape(sa)) return false;
        CubeReanimatorPlan plan = ((CubeComboPlayerController) ai.getController()).reanimatorPlan();
        return plan != null && plan.holding(sa);
    }

    private boolean holding(SpellAbility search) {
        if (!reanimationLive() || graveyardPayload(2) != null) return false;
        Card host = search.getHostCard();
        if (host == null || inWindow(host, search)) return false;
        holds++;
        return true;
    }

    /** The sequencing rule, and the reason this plan exists at the propose
     * layer at all. {@code entomb.txt} is an INSTANT and
     * {@code ChangeZoneAi.checkPhaseRestrictions} has phase rules only for
     * {@code Hand <- Graveyard} and {@code Library <- Graveyard}, so a
     * {@code Library -> Graveyard} instant has no end-of-turn preference and
     * Default casts it at the first opportunity it is offered.
     *
     * <p>Two windows, and nothing else:</p>
     * <ul>
     * <li><b>our own main</b>, when the reanimation spell can follow THIS turn -
     *     i.e. our own board pays for BOTH halves. Spending the search now and
     *     leaving the payload in the graveyard for a whole opponent turn hands
     *     them a graveyard-hate window for nothing;</li>
     * <li><b>the opponent's end step</b>, for an INSTANT search, when it cannot.
     *     {@link #reanimationLive} has already checked that our own board
     *     produces the spell's mana value on its NEXT untap, which is the turn
     *     the reanimation is cast.</li>
     * </ul>
     *
     * <p>A sorcery-speed search (Unmarked Grave) has only the first window.</p> */
    private boolean inWindow(Card card, SpellAbility search) {
        if (ownMain()) return bothPayable(search);
        return card.isInstant() && opponentEndStep();
    }

    /** Would our own board pay for the search AND the reanimation spell out of
     * one turn? One native probe over the combined printed mana, the
     * {@code CubeDrawOutPlan.bothPayable} idiom, so a single source cannot pay
     * for both halves. Nothing is cast, tapped or changed. */
    private boolean bothPayable(SpellAbility search) {
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) continue;
            for (SpellAbility ability : card.getSpellAbilities()) {
                if (!ability.isSpell() || !reanimationShape(ability) && !(card.isPermanent() && entersReanimates(card))) continue;
                if (ability.getPayCosts() == null || search.getPayCosts() == null) continue;
                forge.card.mana.ManaCost extra = ability.getPayCosts().getTotalMana();
                forge.game.mana.ManaCostBeingPaid combined =
                        new forge.game.mana.ManaCostBeingPaid(search.getPayCosts().getTotalMana());
                for (forge.card.mana.ManaCostShard shard : extra) combined.increaseShard(shard, 1);
                combined.increaseGenericMana(extra.getGenericCost());
                if (CubeComboAi.canPayManaCost(combined, search, player, false)) return true;
            }
        }
        return false;
    }

    /** The plan proposes the SEARCH and nothing else. The reanimation cast stays
     * Default's, per census section 6.5. */
    public SpellAbility nextAction() {
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) { turn = phase.getTurn(); actions = 0; selected = null; }
        if (failedTurn == turn || actions >= ACTION_CAP || player.cantWin() || !game.getStack().isEmpty()
                || player.getOpponents().size() != 1 || !(ownMain() || opponentEndStep()))
            return decline(gateReason());
        decline = "other check=reanimator-plan";
        // Pure reads only, so a board with no reanimation card at all leaves the
        // caller's receipt byte for byte: no payment probe, no RNG, no mana-pool
        // touch and no stderr line can happen before this decline.
        if (!reanimationLive()) return decline("no-reanimation-spell");
        if (graveyardPayload(2) != null) return decline("payload-ready");
        SpellAbility search = null;
        boolean sawSearch = false, payable = false;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) continue;
            for (SpellAbility ability : card.getSpellAbilities()) {
                if (!ability.isSpell() || !searchShape(ability)) continue;
                sawSearch = true;
                if (!CubeComboAi.canPlayNative(ability, player) || !CubeComboAi.canPayCost(ability, player, false)) continue;
                payable = true;
                if (search == null && inWindow(card, ability)) search = ability;
            }
        }
        if (!sawSearch) return decline(graveyardPayload(1) == null ? "no-value-target" : "no-search");
        if (!payable) return decline("unpayable:search");
        if (search == null) return decline("search-window");
        selected = search; actions++; planActions++;
        System.err.println("CUBE_REANIMATOR search=" + search.getHostCard().getName().replace(' ', '_')
                + " turn=" + phase.getTurn() + " phase=" + phase.getPhase()
                + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size()
                + " life=" + player.getLife());
        return search;
    }

    // ------------------------------------------------------------- execution

    public boolean owns(SpellAbility ability) { return ability == selected; }

    public boolean play(SpellAbility ability) {
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, ability, null,
                current -> new AiCostDecision(player, current, false));
        if (!played) failedTurn = turn;
        System.err.println("CUBE_REANIMATOR " + (played ? "played" : "native-payment-failed")
                + " turn=" + turn + " card=" + ability.getHostCard().getName().replace(' ', '_')
                + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size());
        return played;
    }

    public boolean waitingForOwnSpell() {
        var stack = player.getGame().getStack();
        if (selected == null || turn != player.getGame().getPhaseHandler().getTurn() || stack.isEmpty()) return false;
        var top = stack.peekAbility();
        return top != null && top.getActivatingPlayer() == player && top.getHostCard() == selected.getHostCard();
    }
}
