/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardState;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostReturn;
import forge.game.cost.CostReveal;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordWithCostAndType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import forge.player.HumanManaX;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * ACTION PREVIEW (lane action-preview-1006): what a click on a priority {@code card:<fid>}
 * control will ask, published as {@code control.value.preview} with the control.
 *
 * <p>Forge asks its casting questions one at a time, each a round trip for the browser, and
 * some of them while the card is still in the hand (which ability, optional costs, a charm's
 * modes, a land's "as it enters" choice), others after it has left the hand for no listed zone
 * (targets, X, additional costs, mana). The preview lets the browser open its own chooser on
 * the frame of the tap, draw the card where Forge will hold it, and say up front when Forge
 * would silently refuse the cast (too few targets).</p>
 *
 * <p><b>It is a read and nothing else.</b> It never decides, never answers, and never changes
 * the game: it calls no Forge path that moves an object, takes an id or a timestamp, fires an
 * event, re-applies static abilities, asks a controller or caches anything on a live object.
 * Where Forge's own helper would (optional costs re-apply statics for an alternate host; a
 * charm's option list writes {@code CharmOrder}; a keyword cost may ask), the same facts are
 * read from the scripts instead, or the field is left out. Everything runs inside a detached
 * id scope, so a throwaway object could not move a live id counter even if one were made.
 * Every field is optional: absent means "unknown", which the browser treats as today's
 * behaviour. The whole pass is skipped with {@code -Dforge.interactive.preview=off}.</p>
 *
 * <p>Cost is bounded by a wall-clock budget per request ({@code -Dforge.interactive.previewBudgetMs},
 * default 5). Cards are previewed hand first; a card the budget did not reach, or one cut short,
 * carries {@code partial: true}. The budget makes the preview's extent timing-dependent, so the
 * bridge leaves it out of the request fingerprint: whether and when a request is published never
 * depends on it.</p>
 */
final class InteractivePreview {
    static final int VERSION = 1;
    static final String PROPERTY = "forge.interactive.preview";
    static final String BUDGET_PROPERTY = "forge.interactive.previewBudgetMs";
    static final double DEFAULT_BUDGET_MS = 5.0;

    private InteractivePreview() {
    }

    /** What the preview needs from the bridge: labels and hidden-information checks for this seat. */
    interface Seat {
        /** The label Forge's "Choose an ability" modal would give this ability (sanitized). */
        String abilityLabel(SpellAbility ability);
        /** Sanitize engine text for this seat. */
        String text(String text);
        /** This seat may be told this card exists by id (it may see it). */
        boolean mayList(Card card);
        /** The seat index of a player (registered order), or -1. */
        int seatOf(Player player);
    }

    /** Off only when the system property says so. */
    static boolean enabled() {
        final String v = System.getProperty(PROPERTY);
        if (v == null) {
            return true;
        }
        final String s = v.trim().toLowerCase();
        return !(s.equals("off") || s.equals("false") || s.equals("0") || s.equals("no"));
    }

    static long budgetNanos() {
        double ms = DEFAULT_BUDGET_MS;
        final String v = System.getProperty(BUDGET_PROPERTY);
        if (v != null) {
            try {
                ms = Double.parseDouble(v.trim());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        return (long) (Math.max(0, ms) * 1_000_000L);
    }

    /** One request's preview pass: the budget its cards share, and what it did (for the log line). */
    static final class Pass {
        private final long started = System.nanoTime();
        private final long deadline;
        int cards;
        int complete;
        int partial;

        Pass(final long budgetNanos) {
            deadline = started + budgetNanos;
        }

        boolean expired() {
            return System.nanoTime() > deadline;
        }

        long elapsedMicros() {
            return (System.nanoTime() - started) / 1_000L;
        }
    }

    /** A card whose control the budget never reached. */
    static JsonObject unreached() {
        final JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("partial", true);
        return o;
    }

    /**
     * The preview for one {@code card:<fid>} priority control.
     *
     * @param possible the card's {@code getAllPossibleAbilities(human, true)}, in the order a
     *                 click re-lists them: index {@code i} is {@code ability:<i>} in the follow-up modal
     * @param offered  whether the "Choose an ability" modal would list the ability (its affordability)
     */
    static JsonObject forCard(final Pass pass, final Player human, final Card card,
                              final List<SpellAbility> possible, final Predicate<SpellAbility> offered,
                              final Seat seat) {
        pass.cards++;
        final JsonObject preview = new JsonObject();
        preview.addProperty("v", VERSION);
        boolean cut = false;
        try {
            final JsonArray abilities = new JsonArray();
            // InputPassPriority.onCardSelected -> getAbilityToPlay: one ability is played without a
            // prompt; two or more open "Choose an ability" with ability:<i> over the whole list.
            if (possible.size() > 1) {
                preview.addProperty("asks", "ability");
            }
            for (int i = 0; i < possible.size(); i++) {
                if (pass.expired()) {
                    cut = true;
                    break;
                }
                abilities.add(ability(i, possible.get(i), offered.test(possible.get(i)), possible.size() > 1,
                        human, card, seat, pass));
            }
            preview.add("abilities", abilities);
        } catch (RuntimeException failure) {
            // A read that Forge could not answer is "unknown", never a failed publish.
            preview.remove("abilities");
            preview.remove("asks");
            preview.addProperty("error", failure.getClass().getSimpleName());
            cut = true;
        }
        if (cut) {
            preview.addProperty("partial", true);
            pass.partial++;
        } else {
            pass.complete++;
        }
        return preview;
    }

    private static JsonObject ability(final int index, final SpellAbility sa, final boolean offered,
                                      final boolean chooser, final Player human, final Card card,
                                      final Seat seat, final Pass pass) {
        final JsonObject o = new JsonObject();
        o.addProperty("i", index);
        o.addProperty("kind", sa.isLandAbility() ? "land" : sa.isManaAbility() ? "mana"
                : sa.isSpell() ? "spell" : "activated");
        if (chooser) {
            // Only a chooser needs the words; one ability is played without a prompt.
            final String label = seat.abilityLabel(sa);
            o.addProperty("label", label == null || label.isBlank()
                    ? (sa.isLandAbility() ? "Play land" : "Ability") : label);
        }
        if (!offered) {
            // The modal lists it (it keeps the index), the browser cannot choose it.
            o.addProperty("offered", false);
        }
        final AlternativeCost alt = sa.getAlternativeCost();
        if (alt != null) {
            o.addProperty("alt", alt.name());
        }
        if (sa.getCardState() != null && card.getCurrentState() != null
                && sa.getCardStateName() != card.getCurrentStateName()) {
            o.addProperty("face", String.valueOf(sa.getCardStateName()));
        }
        if (sa.isManaAbility()) {
            return o;
        }
        if (sa.isLandAbility()) {
            final JsonObject etb = landEntry(sa, human, card);
            if (etb != null) {
                o.add("etb", etb);
            }
            return o;
        }

        final List<String> inHand = new ArrayList<>();
        if (sa.isSpell()) {
            final JsonArray altAdditional = alternateAdditionalCosts(sa);
            if (altAdditional.size() > 1) {
                inHand.add("altAdditionalCost");
                o.add("altAdditionalCosts", altAdditional);
            }
            final JsonArray optional = optionalCosts(sa, card);
            if (optional == null) {
                o.addProperty("optionalCostsUnknown", true);
            } else if (optional.size() > 0) {
                // PlayerControllerHuman.chooseOptionalCosts asks whenever the list is non-empty.
                inHand.add("optionalCosts");
                o.add("optionalCosts", optional);
            }
            final JsonArray extra = extraKeywordCosts(sa);
            if (extra.size() > 0) {
                o.add("extraCosts", extra);
            }
        }
        if (sa.getApi() == ApiType.Charm) {
            final JsonObject modes = modes(sa, seat);
            if (modes != null) {
                o.add("modes", modes);
                if (modes.has("asks") && modes.get("asks").getAsBoolean()) {
                    inHand.add("modes");
                }
                if (modes.has("refusal")) {
                    o.addProperty("refusal", modes.get("refusal").getAsString());
                }
            }
        }
        if (sa.isSpell() && splices(sa, human, card)) {
            inHand.add("splice");
        }
        if (!inHand.isEmpty()) {
            final JsonArray a = new JsonArray();
            inHand.forEach(a::add);
            o.add("inHand", a);
        }
        if (pass.expired()) {
            o.addProperty("partial", true);
            return o;
        }

        final JsonObject x = xRange(sa, human);
        if (x != null) {
            o.add("x", x);
        }
        final JsonArray targets = new JsonArray();
        String refusal = o.has("refusal") ? o.get("refusal").getAsString() : null;
        boolean targetsUnknown = false;
        if (sa.getApi() != ApiType.Charm) {
            for (SpellAbility node = sa; node != null; node = node.getSubAbility()) {
                if (!node.usesTargeting()) {
                    continue;
                }
                final JsonObject slot = targetSlot(node, human, seat);
                if (slot == null) {
                    targetsUnknown = true;
                    break;
                }
                if (refusal == null && slot.has("refusal")) {
                    refusal = slot.get("refusal").getAsString();
                    slot.remove("refusal");
                }
                targets.add(slot);
            }
        }
        if (targetsUnknown) {
            o.addProperty("targetsUnknown", true);
        } else if (targets.size() > 0) {
            o.add("targets", targets);
        }
        final JsonArray costs = additionalCosts(sa, human, seat);
        if (costs.size() > 0) {
            o.add("costs", costs);
        }
        final JsonObject mana = mana(sa, x != null);
        if (mana != null) {
            o.add("mana", mana);
        }
        if (refusal == null && sa.isSpell() && x == null && !anyTargets(sa) && !sa.isLegalAfterStack()) {
            // ValidAfterStack ("Spell.cmcLE3" and kin): with no X and no target to depend on, the
            // check Forge makes after the move gives the same answer now.
            refusal = "valid-after-stack";
        }
        if (refusal != null) {
            o.addProperty("refusal", refusal);
        }
        if ((sa.isSpell() || sa.isActivatedAbility()) && !sa.canCastTiming(human)) {
            // canPlay already passed, so a flash grant that depends on the targets or X decides.
            o.addProperty("timing", "depends-on-choices");
        }
        return o;
    }

    private static boolean anyTargets(final SpellAbility sa) {
        for (SpellAbility node = sa; node != null; node = node.getSubAbility()) {
            if (node.usesTargeting()) {
                return true;
            }
        }
        return false;
    }

    // ---- asked while the card is still in the hand ------------------------------------------

    /** GameActionUtil.getAdditionalCostSpell's AlternateAdditionalCost options, from the keyword text (no copies). */
    private static JsonArray alternateAdditionalCosts(final SpellAbility sa) {
        final JsonArray a = new JsonArray();
        for (KeywordInterface inst : sa.getHostCard().getKeywords()) {
            final String keyword = inst.getOriginal();
            if (keyword == null || !keyword.startsWith("AlternateAdditionalCost")) {
                continue;
            }
            for (String s : keyword.split(":", 2)[1].split(":")) {
                a.add(new Cost(s, false).toSimpleString());
            }
        }
        return a;
    }

    /**
     * GameActionUtil.getOptionalCostValues, read without it: that method clears the ability's
     * pips to reduce and, for an alternate host, re-applies every static ability. Null (unknown)
     * for an alternate host; otherwise the same static and keyword scan.
     */
    private static JsonArray optionalCosts(final SpellAbility sa, final Card card) {
        final JsonArray a = new JsonArray();
        final Card source = sa.getHostCard();
        if (source.isInPlay()) {
            return a;
        }
        if (sa.getAlternateHost(source) != null) {
            return null;
        }
        final Game game = source.getGame();
        final List<Card> costSources = new ArrayList<>();
        costSources.add(source);
        game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).forEach(costSources::add);
        for (Card ca : costSources) {
            for (StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.OptionalCost)
                        || !stAb.matchesValidParam("ValidCard", source)
                        || !stAb.matchesValidParam("ValidSA", sa)
                        || !stAb.matchesValidParam("Activator", sa.getActivatingPlayer())) {
                    continue;
                }
                a.add(optional(stAb.hasParam("ReduceColor") ? "Reduce" + stAb.getParam("ReduceColor") : "Generic",
                        stAb.getParam("Cost")));
            }
        }
        for (KeywordInterface inst : source.getKeywords()) {
            final String keyword = inst.getOriginal();
            if (keyword == null) {
                continue;
            }
            if (keyword.equals("Bargain")) {
                a.add(optional("Bargain", "Sac<1/Artifact;Enchantment;Card.token/artifact, enchantment or token>"));
            } else if (keyword.startsWith("Buyback")) {
                a.add(optional("Buyback", keyword.substring(8)));
            } else if (keyword.startsWith("Entwine")) {
                a.add(optional("Entwine", keyword.split(":")[1]));
            } else if (keyword.startsWith("Teamwork")) {
                a.add(optional("Teamwork", "Teamwork<" + keyword.split(":")[1] + ">"));
            } else if (keyword.startsWith("Gift")) {
                a.add(optional("PromiseGift", "PromiseGift"));
            } else if (keyword.startsWith("Kicker")) {
                final String[] costs = forge.util.TextUtil.split(keyword.substring(6), ':');
                for (int j = 0; j < costs.length; j++) {
                    a.add(optional(j == 0 ? "Kicker1" : "Kicker2", costs[j]));
                }
            } else if (keyword.equals("Retrace")) {
                if (source.isInZone(ZoneType.Graveyard)) {
                    a.add(optional("Retrace", "Discard<1/Land>"));
                }
            } else if (keyword.equals("Jump-start")) {
                if (source.isInZone(ZoneType.Graveyard)) {
                    a.add(optional("Jumpstart", "Discard<1/Card>"));
                }
            } else if (keyword.startsWith("MayFlashCost")) {
                a.add(optional("Flash", keyword.split(":")[1]));
            } else if (keyword.startsWith("Offering")) {
                a.add(optional("Offering", "Sac<1/" + keyword.split(":")[1] + ">"));
            }
        }
        return a;
    }

    private static JsonObject optional(final String type, final String cost) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", type);
        String rendered;
        try {
            rendered = cost == null ? "" : new Cost(cost, false).toSimpleString();
        } catch (RuntimeException e) {
            rendered = String.valueOf(cost);
        }
        o.addProperty("cost", rendered);
        return o;
    }

    /** Multikicker, Casualty and kin: asked after the card leaves the hand (GameActionUtil.addExtraKeywordCost). Text only. */
    private static JsonArray extraKeywordCosts(final SpellAbility sa) {
        final JsonArray a = new JsonArray();
        if (sa.isCopied()) {
            return a;
        }
        for (KeywordInterface kw : sa.getHostCard().getKeywords()) {
            final String original = kw.getOriginal();
            if (original == null) {
                continue;
            }
            for (String name : List.of("Multikicker", "Casualty", "Conspire", "Offspring", "Harmonize",
                    "Replicate", "Squad")) {
                if (original.startsWith(name)) {
                    final JsonObject o = new JsonObject();
                    o.addProperty("keyword", name);
                    final int colon = original.indexOf(':');
                    o.addProperty("cost", colon >= 0 ? original.substring(colon + 1) : "");
                    a.add(o);
                    break;
                }
            }
        }
        return a;
    }

    /**
     * A charm's modes as CharmEffect.makeChoices will offer them, read without
     * {@code makePossibleOptions} (it writes each mode's {@code CharmOrder}).
     */
    private static JsonObject modes(final SpellAbility sa, final Seat seat) {
        final Card source = sa.getHostCard();
        List<String> restriction = null;
        if (sa.hasParam("ChoiceRestriction")) {
            restriction = source.getChosenModes(sa, sa.getParam("ChoiceRestriction"));
        }
        final JsonArray options = new JsonArray();
        int index = 0;
        int available = 0;
        for (AbilitySub ch : sa.getAdditionalAbilityList("Choices")) {
            final JsonObject m = new JsonObject();
            m.addProperty("i", index++);
            m.addProperty("label", seat.text(ch.getDescription()));
            boolean possible = true;
            if (ch.usesTargeting()) {
                m.addProperty("targets", true);
                if (ch.getActivatingPlayer() != null && ch.getMinTargets() > 0
                        && ch.getTargetRestrictions().getNumCandidates(ch) == 0) {
                    possible = false;
                }
            }
            if (restriction != null && restriction.contains(ch.getDescription())) {
                possible = false;
            }
            if (!possible) {
                m.addProperty("possible", false);
            } else {
                available++;
            }
            options.add(m);
        }
        final JsonObject o = new JsonObject();
        o.add("options", options);
        if (sa.isEntwine()) {
            o.addProperty("asks", false);
            return o;
        }
        final String numParam = sa.getParamOrDefault("CharmNum", "1");
        final boolean repeat = sa.hasParam("CanRepeatModes");
        if (repeat) {
            o.addProperty("repeat", true);
        }
        if (sa.hasParam("Optional")) {
            o.addProperty("optional", true);
        }
        if (sa.hasParam("Random") || "Opponent".equals(sa.getParam("Chooser"))) {
            o.addProperty("asks", false);
            return o;
        }
        if (!numParam.matches("\\d+") || (sa.hasParam("MinCharmNum") && !sa.getParam("MinCharmNum").matches("\\d+"))) {
            // X or a count: known only once announced.
            o.addProperty("asks", true);
            return o;
        }
        int num = Integer.parseInt(numParam);
        final int min = sa.hasParam("MinCharmNum") ? Integer.parseInt(sa.getParam("MinCharmNum")) : num;
        if (!repeat && min > available) {
            // CharmEffect.makeChoices returns false before the card moves: a silent refusal.
            o.addProperty("refusal", "no-modes");
        }
        if (!repeat) {
            num = Math.min(num, available);
        }
        o.addProperty("min", min);
        o.addProperty("max", num);
        // PlayerControllerHuman.chooseModeForAbility skips the prompt when min == num == the legal count.
        o.addProperty("asks", repeat || !(min == num && num == available));
        return o;
    }

    /** AbilityUtils.addSpliceEffects asks when another card in hand can be spliced onto this spell. */
    private static boolean splices(final SpellAbility sa, final Player human, final Card card) {
        if (card.isCopiedSpell()) {
            return false;
        }
        for (Card other : human.getCardsIn(ZoneType.Hand)) {
            if (other == card) {
                continue;
            }
            for (KeywordInterface inst : other.getKeywords(Keyword.SPLICE)) {
                if (inst instanceof KeywordWithCostAndType splice
                        && card.isValid(splice.getValidType().split(","), human, other, sa)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- asked after the card leaves the hand -----------------------------------------------

    /**
     * X as PlaySpellAbility.announceValuesLikeX will ask it, with the range the live prompt
     * uses ({@code HumanManaX.range} over {@code AbilityUtils.getAnnouncementBounds}). Null when
     * no X is announced. Computed with the card where it is now, before it moves.
     */
    private static JsonObject xRange(final SpellAbility sa, final Player human) {
        final Cost cost = sa.getPayCosts();
        boolean announced = false;
        final String announce = sa.getParam("Announce");
        if (announce != null && sa.costHasX()) {
            for (String v : announce.split(",")) {
                if ("X".equals(v.trim())) {
                    announced = true;
                }
            }
        }
        if (!announced && sa.costHasX() && cost != null && cost.hasXInAnyCostPart()) {
            final String sVar = sa.getParamOrDefault("XAlternative", sa.getSVar("X"));
            final boolean replacedXshard = sa.isSpell() && sa.getHostCard().getManaCost().countX() > 0
                    && !cost.hasXInAnyCostPart();
            announced = ("Count$xPaid".equals(sVar) && !replacedXshard) || sVar.isEmpty();
        }
        if (!announced) {
            return null;
        }
        final JsonObject o = new JsonObject();
        if (sa.getApi() == ApiType.Charm) {
            o.addProperty("inHand", true);
        }
        final var bounds = AbilityUtils.getAnnouncementBounds(sa, "X");
        int min = bounds.getMinimum();
        int max = bounds.getMaximum();
        final Integer costX = cost == null ? null : cost.getMaxForNonManaX(sa, human, false);
        if (costX != null) {
            max = Math.min(max, costX);
        }
        final boolean manaX = cost != null && cost.getCostMana() != null
                && cost.getCostMana().getMana().countX() > 0;
        if (manaX) {
            // PlayerControllerHuman.announceRequirements -> chooseManaX: a `number` request,
            // control "mana-x", min/max exactly this range.
            o.addProperty("control", "mana-x");
            final HumanManaX.Range range = HumanManaX.range(human, sa, min, max);
            o.addProperty("min", range.min());
            o.addProperty("max", Math.min(range.max(), Integer.MAX_VALUE));
            o.addProperty("exact", range.exact());
            if (!range.exact()) {
                o.addProperty("reason", range.reason());
            }
            if (range.min() > range.max()) {
                // announceRequirements refuses ("You cannot pay the cost for any allowed value of X").
                o.addProperty("refusal", true);
            }
        } else {
            o.addProperty("control", "number");
            o.addProperty("min", min);
            o.addProperty("max", max);
        }
        return o;
    }

    /** One targeting ability of the chain, as TargetSelection will ask it. Null when it cannot be read. */
    private static JsonObject targetSlot(final SpellAbility node, final Player human, final Seat seat) {
        if (node.getActivatingPlayer() == null) {
            return null;
        }
        final TargetRestrictions tgt = node.getTargetRestrictions();
        final JsonObject o = new JsonObject();
        final String minText = String.valueOf(tgt.getMinTargets());
        final String maxText = String.valueOf(tgt.getMaxTargets());
        final boolean variable = !minText.matches("\\d+") || !maxText.matches("\\d+");
        final int min = node.getMinTargets();
        final int max = node.getMaxTargets();
        o.addProperty("min", min);
        o.addProperty("max", max);
        if (variable) {
            // X, a count, an announced number: known only after that is chosen.
            o.addProperty("variable", true);
        }
        if (node.hasParam("TargetingPlayer")) {
            o.addProperty("targetingPlayer", true);
        }
        if (node.isDividedAsYouChoose()) {
            o.addProperty("divided", true);
        }
        if (tgt.isRandomTarget()) {
            o.addProperty("random", true);
        }
        final boolean conditional = node.getMapParams().keySet().stream().anyMatch(k -> k.startsWith("Condition"));
        if (conditional) {
            o.addProperty("conditional", true);
        }
        final List<ZoneType> zones = tgt.getZone();
        if (!(zones.size() == 1 && zones.get(0) == ZoneType.Battlefield)) {
            // Absent: the battlefield (and players), by far the most common.
            final JsonArray zoneNames = new JsonArray();
            zones.forEach(z -> zoneNames.add(z.name()));
            o.add("zones", zoneNames);
        }
        final boolean stackOnly = zones.size() == 1 && zones.get(0) == ZoneType.Stack;

        final Game game = human.getGame();
        int count = 0;
        final JsonArray stack = new JsonArray();
        if (zones.contains(ZoneType.Stack)) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (node.canTargetSpellAbility(si.getSpellAbility())) {
                    stack.add(si.getId());
                    count++;
                }
            }
        }
        final JsonArray players = new JsonArray();
        final JsonArray cards = new JsonArray();
        int hidden = 0;
        if (!stackOnly) {
            for (Player player : game.getPlayers()) {
                if (node.canTarget(player)) {
                    players.add(seat.seatOf(player));
                    count++;
                }
            }
            tgt.applyTargetTextChanges(node);
            for (Card c : game.getCardsIn(zones)) {
                if (c.isInZone(ZoneType.Stack) || !node.canTarget(c)) {
                    continue;
                }
                count++;
                if (seat.mayList(c)) {
                    cards.add(c.getId());
                } else {
                    hidden++;
                }
            }
        }
        if (cards.size() > 0) o.add("cards", cards);
        if (players.size() > 0) o.add("players", players);
        if (stack.size() > 0) o.add("stack", stack);
        if (hidden > 0) o.addProperty("hidden", hidden);
        // How Forge asks (TargetSelection.chooseTargetsInner): stack-only and mixed lists are a
        // `choice` list; otherwise InputSelectTargets (`target`), even with one candidate. With no
        // card and exactly one non-card candidate a required target is chosen without asking.
        if (stackOnly || zones.contains(ZoneType.Stack)) {
            o.addProperty("ask", "choice");
        } else if (cards.isEmpty() && hidden == 0 && players.size() == 1 && min > 0 && !tgt.isRandomTarget()) {
            o.addProperty("ask", "auto");
        } else if (!tgt.isRandomTarget()) {
            o.addProperty("ask", "target");
        }
        if (!variable && !conditional && !node.hasParam("TargetingPlayer") && !tgt.isDifferentControllers()
                && !tgt.isForEachPlayer() && min > 0 && count < min) {
            // TargetSelection: "Cancel ability if there aren't any valid Candidates" -> silent rollback.
            o.addProperty("refusal", "no-targets");
        }
        return o;
    }

    /** Non-mana parts of the cost, in Forge's payment order, with the cards that could pay simple ones. */
    private static JsonArray additionalCosts(final SpellAbility sa, final Player human, final Seat seat) {
        final JsonArray a = new JsonArray();
        final Cost cost = sa.getPayCosts();
        if (cost == null) {
            return a;
        }
        final Card host = sa.getHostCard();
        for (CostPart part : cost.getCostParts()) {
            final String type;
            List<ZoneType> from = null;
            if (part instanceof CostSacrifice) {
                type = "sacrifice";
                from = List.of(ZoneType.Battlefield);
            } else if (part instanceof CostDiscard) {
                type = "discard";
                from = List.of(ZoneType.Hand);
            } else if (part instanceof CostExile exile) {
                type = "exile";
                from = exile.getFrom();
            } else if (part instanceof CostReturn) {
                type = "return";
                from = List.of(ZoneType.Battlefield);
            } else if (part instanceof CostTapType) {
                type = "tap";
                from = List.of(ZoneType.Battlefield);
            } else if (part instanceof CostReveal) {
                type = "reveal";
                from = List.of(ZoneType.Hand);
            } else if (part instanceof CostPayLife) {
                type = "life";
            } else if (part instanceof CostPartMana || part instanceof forge.game.cost.CostTap
                    || part instanceof forge.game.cost.CostUntap) {
                continue;
            } else {
                type = part.getClass().getSimpleName();
            }
            final JsonObject o = new JsonObject();
            o.addProperty("type", type);
            o.addProperty("amount", part.getAmount());
            o.addProperty("label", seat.text(part.toString()));
            if (part.payCostFromSource()) {
                o.addProperty("self", true);
            } else if (from != null && part.getAmount().matches("\\d+") && simpleCostType(part.getType())) {
                final CardCollectionView pool = human.getCardsIn(from);
                final JsonArray cards = new JsonArray();
                for (Card c : CardLists.getValidCards(pool, part.getType().split(";"), human, host, sa)) {
                    if (c != host || !(sa.isSpell())) {
                        if (seat.mayList(c)) {
                            cards.add(c.getId());
                        }
                    }
                }
                o.add("cards", cards);
                if (from.size() == 1) {
                    o.addProperty("from", from.get(0).name());
                }
            }
            a.add(o);
        }
        return a;
    }

    private static boolean simpleCostType(final String type) {
        return type != null && !type.isEmpty() && !type.contains("+With") && !type.contains("+with")
                && !type.contains("X") && !type.equals("All") && !type.equals("Hand") && !type.equals("Random")
                && !type.equals("LastDrawn") && !type.equals("OriginalHost") && !type.contains("FromTopGrave")
                && !type.contains("ChosenColor");
    }

    /**
     * Whether a `mana` request follows. The bridge never auto-pays, so it does exactly when the
     * adjusted cost is not zero. Null (unknown) when Forge's presentation price cannot say.
     */
    private static JsonObject mana(final SpellAbility sa, final boolean hasX) {
        final Cost cost = sa.getPayCosts();
        if (cost == null) {
            return null;
        }
        final JsonObject o = new JsonObject();
        final forge.card.mana.ManaCost price = CostAdjustment.presentationManaCost(sa);
        if (price != null) {
            o.addProperty("asks", !price.isZero());
            o.addProperty("cost", price.getSimpleString());
            return o;
        }
        final CostPartMana part = cost.getCostMana();
        if (part == null) {
            // No mana part at all: only a cost-raising effect could add one.
            if (anyCostStatics(sa)) {
                return null;
            }
            o.addProperty("asks", false);
            return o;
        }
        if (hasX && part.getMana().getCMC() > 0) {
            // The fixed part alone already needs paying, whatever X is.
            o.addProperty("asks", true);
            o.addProperty("cost", part.getMana().getSimpleString());
            return o;
        }
        return null;
    }

    private static boolean anyCostStatics(final SpellAbility sa) {
        final Game game = sa.getHostCard().getGame();
        final List<Card> active = new ArrayList<>();
        game.getCardsIn(ZoneType.Battlefield).forEach(active::add);
        game.getCardsIn(ZoneType.Command).forEach(active::add);
        active.add(sa.getHostCard());
        for (Card c : active) {
            for (StaticAbility st : c.getStaticAbilities()) {
                if (st.checkMode(StaticAbilityMode.RaiseCost) || st.checkMode(StaticAbilityMode.SetCost)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- lands ------------------------------------------------------------------------------

    /**
     * What a land asks as it enters, while it is still in the hand: its own "Moved to the
     * battlefield" replacement effects (GameAction.changeZone runs them before the card leaves
     * the hand). Read from the script parameters; the replacement's ability is not built here.
     * Prompts from OTHER permanents' replacement effects are not covered.
     */
    private static JsonObject landEntry(final SpellAbility sa, final Player human, final Card card) {
        final CardState state = card.getState(sa.getCardStateName());
        if (state == null) {
            return null;
        }
        final Set<String> asks = new LinkedHashSet<>();
        final Map<ReplacementLayer, Set<String>> byLayer = new EnumMap<>(ReplacementLayer.class);
        for (ReplacementEffect re : state.getReplacementEffects()) {
            if (re.getMode() != ReplacementType.Moved
                    || !re.getParamOrDefault("Destination", "").contains("Battlefield")
                    || !re.getParamOrDefault("ValidCard", "").contains("Card.Self")) {
                continue;
            }
            byLayer.computeIfAbsent(re.getLayer(), k -> new LinkedHashSet<>()).add(String.valueOf(re.getDescription()));
            if (re.hasParam("Optional")) {
                asks.add("optional");
            }
            String api;
            String unless;
            final SpellAbility effect = re.getOverridingAbility();
            if (effect != null) {
                api = effect.getApi() == null ? "" : effect.getApi().name();
                unless = effect.getParam("UnlessCost");
            } else {
                final String svar = re.getParam("ReplaceWith");
                final String script = svar == null ? "" : card.getSVar(svar);
                api = scriptParam(script, "DB");
                unless = scriptParam(script, "UnlessCost");
            }
            if (unless != null && !unless.isEmpty()) {
                if (unless.startsWith("PayLife<")) {
                    final String amount = unless.substring(8, unless.indexOf('>') > 8 ? unless.indexOf('>') : unless.length());
                    // payCostDuringAbilityResolve checks canPay first: no prompt when the life cannot be paid.
                    if (!amount.matches("\\d+") || human.canPayLife(Integer.parseInt(amount), false, null)) {
                        asks.add("pay-life");
                    }
                } else if (unless.startsWith("Reveal<")) {
                    // An optional list of matching cards in hand; skipped when there are none.
                    final String inner = unless.substring(7, Math.max(7, unless.lastIndexOf('>')));
                    final String[] parts = inner.split("/");
                    final String types = parts.length > 1 ? parts[1] : "Card";
                    final List<Card> hand = new ArrayList<>();
                    human.getCardsIn(ZoneType.Hand).forEach(c -> { if (c != card) hand.add(c); });
                    if (!CardLists.getValidCards(hand, types.split(";"), human, card, sa).isEmpty()) {
                        asks.add("reveal");
                    }
                } else {
                    asks.add("unless-cost");
                }
            }
            switch (api == null ? "" : api) {
                case "ChooseColor" -> asks.add("choose-color");
                case "ChooseType" -> asks.add("choose-type");
                case "Clone" -> asks.add("copy");
                case "ChooseCard" -> asks.add("choose-card");
                case "ChoosePlayer" -> asks.add("choose-player");
                case "ChooseNumber" -> asks.add("choose-number");
                default -> { }
            }
        }
        for (Map.Entry<ReplacementLayer, Set<String>> e : byLayer.entrySet()) {
            if (e.getKey() != ReplacementLayer.CantHappen && e.getValue().size() > 1) {
                // ReplacementHandler asks which replacement applies first (an `order` modal).
                asks.add("order");
            }
        }
        if (asks.isEmpty()) {
            return null;
        }
        final JsonObject o = new JsonObject();
        final JsonArray a = new JsonArray();
        asks.forEach(a::add);
        o.add("asks", a);
        return o;
    }

    /** The value of {@code Key$ Value} in a script line, trimmed; null when absent. */
    static String scriptParam(final String script, final String key) {
        if (script == null) {
            return null;
        }
        for (String part : script.split("\\|")) {
            final String p = part.trim();
            final int dollar = p.indexOf('$');
            if (dollar > 0 && p.substring(0, dollar).trim().equals(key)) {
                return p.substring(dollar + 1).trim();
            }
        }
        return null;
    }
}
