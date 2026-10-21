package forge.player;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.card.CardPlayOption;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostAddMana;
import forge.game.cost.CostExile;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostUntap;
import forge.game.cost.CostUntapType;
import forge.game.CardTraitBase;
import forge.game.ability.ApiType;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Read-only presentation filter. False proves insufficient mana; true is NOT a legality claim.
 * Complex costs/sources deliberately remain visible. Never invokes AI payment simulation,
 * changes the activating player, chooses X, taps a source, or mutates the mana pool.
 */
public final class HumanManaAffordability {
    static final int LIFE_PAYMENT = 256;
    public enum Assessment { PROVEN_UNAFFORDABLE, POTENTIALLY_AFFORDABLE, UNKNOWN }
    private HumanManaAffordability() {}

    /**
     * Is there a mana source of {@code player}'s that the proof loops cannot count (lane block-search-1019)? The loops
     * count only plain mana abilities ({@link SpellAbility#isManaAbility()} with a fixed output). An activated ability
     * that makes mana but is no mana ability by the rules is invisible to them: a targeted one (Deathrite Shaman:
     * exile a land card from a graveyard, add one mana), a loyalty one (Chandra, Torch of Defiance's +1, activated
     * before casting), or one whose mana is a sub-ability (Nykthos's devotion ability). With any such ability that
     * could be activated where its card is, no proof is made: "never claim unpayable without proof".
     */
    static boolean unenumeratedManaSource(final Player player) {
        for (Card card : player.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command)) {
            if (card.isPhasedOut()) continue;
            for (SpellAbility sa : card.getSpellAbilities()) {
                if (sa.isSpell() || !activatableWhereItIs(card, sa)) continue;
                // afford-jar-1021: an untap ability can use a mana source twice (Devoted Druid's -1/-1 counter,
                // Voltaic Key); the loops count each {T} once.
                if (card.isInZone(ZoneType.Battlefield) && !sa.isManaAbility() && untaps(sa)) return true;
                if (!makesMana(sa)) continue;
                if (sa.isManaAbility() && sa.getManaPart() != null) continue; // counted by the loops (or UNKNOWN there)
                return true;
            }
        }
        return false;
    }

    /**
     * Can this ability be activated in the zone its card is in now (afford-jar-1021)? Its restriction zone
     * (battlefield by default), or an AdditionalActivationZone it names. Before, any card with an
     * AdditionalActivationZone counted from every zone. A null zone still counts anywhere (optimistic).
     */
    static boolean activatableWhereItIs(final Card card, final SpellAbility sa) {
        final ZoneType zone = sa.getRestrictions() == null ? null : sa.getRestrictions().getZone();
        if (zone == null || card.isInZone(zone)) return true;
        if (!sa.hasParam("AdditionalActivationZone")) return false;
        try {
            return card.isInZone(ZoneType.smartValueOf(sa.getParam("AdditionalActivationZone")));
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    private static boolean untaps(final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.Untap || s.getApi() == ApiType.UntapAll) return true;
        }
        return false;
    }

    private static boolean makesMana(final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.Mana || s.getApi() == ApiType.ManaReflected) return true;
        }
        return false;
    }

    /**
     * A DamageDone replacement that only prevents the damage ({@code Prevent$ True}, nothing put in its place): a
     * player's or permanent's protection keyword card (The Rhinegold's "protection from everything until your next turn"
     * puts one in the command zone), a Fog. Preventing damage never changes the mana a player can make or pay with, so it
     * does not make the proof UNKNOWN (castable-glow-1019: it made every card glow for a turn cycle).
     */
    static boolean preventsDamageOnly(final forge.game.replacement.ReplacementEffect r) {
        return r.getMode() == ReplacementType.DamageDone && "True".equals(r.getParam("Prevent"))
                && !r.hasParam("ReplaceWith");
    }

    /** {@link #activeHooks}: a mana trigger or replacement that acts on mana this player makes (Mana Flare). */
    static final int HOOK_PRODUCTION = 1;
    /** {@link #activeHooks}: a mana conversion that applies to this player and this cost. */
    static final int HOOK_CONVERT = 2;
    /** {@link #activeHooks}: something else in play makes the price or the supply unknown. */
    static final int HOOK_UNKNOWN = -1;

    /**
     * The objects in public active zones that may change the price or the supply, shared by both proofs.
     *
     * <p>afford-jar-1021 (owner reports 2026-10-21T16-49-11, 16-50-10, 16-57-44): a mana trigger, mana replacement
     * or mana conversion made the proof UNKNOWN (= payable) whoever controlled it and whatever it could act on. The
     * AI's Kinnan, Bonder Prodigy ("Whenever YOU tap a nonland permanent for mana", {@code Activator$ You}) made every
     * card of the human glow, with the only land tapped. Now each is asked Forge's own question, with the trait's own
     * parameters ({@link CardTraitBase#matchesValidParam}, the same tests its performTest/canReplace/checkManaConvert
     * make): can it act on this player's mana, from one of this player's mana sources? The answer is a flag, not
     * UNKNOWN: such a hook only acts when mana is made (or spent), so the caller makes the proof UNKNOWN only when the
     * player has a source (or mana) for it to act on. A parameter Forge cannot evaluate here counts as "it acts".</p>
     */
    static int activeHooks(final Player player, final Card host, final boolean spell) {
        final List<Card> sources = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command)) {
            // A source is a card with a mana ability it can activate where it is (not a hand Urza's).
            if (!card.isPhasedOut() && card.getManaAbilities().stream().anyMatch(sa -> activatableWhereItIs(card, sa))) {
                sources.add(card);
            }
        }
        int hooks = 0;
        for (Card card : player.getGame().getCardsInGame()) {
            if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Command)
                    && !card.isInZone(ZoneType.Stack) && card != host) continue;
            for (StaticAbility s : card.getStaticAbilities()) {
                // ManaConvert: only the player is scoped. Forge tests ValidCard at payment, with the spell on the
                // stack under the caster's control (Vizier of the Menagerie's Creature.YouCtrl on an opponent's card
                // cast from exile), so where the host sits now proves nothing: any ValidCard counts as "it acts".
                if (s.checkMode(StaticAbilityMode.ManaConvert)
                        && holds(() -> s.matchesValidParam("ValidPlayer", player))) {
                    hooks |= HOOK_CONVERT;
                }
                if (spell && (s.checkMode(StaticAbilityMode.OptionalCost)
                        || (s.hasParam("AddKeyword") && s.getParam("AddKeyword")
                            .matches("(?s).*(Convoke|Delve|Improvise|Assist|Emerge|Offering|Harmonize|Waterbend|Affinity|Undaunted).*"))
                        || (s.hasParam("AddKeyword") && s.getParamOrDefault("AffectedZone", "").contains("Stack")))) return HOOK_UNKNOWN;
            }
            for (ReplacementEffect r : card.getReplacementEffects()) {
                if (!r.zonesCheck(player.getGame().getZoneOf(card))) continue;
                if (r.getMode() == ReplacementType.ProduceMana) {
                    if (holds(() -> r.matchesValidParam("ValidPlayer", player) && r.matchesValidParam("ValidActivator", player)
                            && sources.stream().anyMatch(c -> r.matchesValidParam("ValidCard", c)))) hooks |= HOOK_PRODUCTION;
                } else if (r.getMode() == ReplacementType.PayLife || r.getMode() == ReplacementType.LifeReduced
                        || r.getMode() == ReplacementType.DamageDone && !preventsDamageOnly(r)
                        || r.getMode() == ReplacementType.DealtDamage) {
                    return HOOK_UNKNOWN;
                }
            }
            for (Trigger t : card.getTriggers()) {
                if (t.getSpawningAbility() == null && !t.zonesCheck(player.getGame().getZoneOf(card))) continue;
                if (t.getMode() == TriggerType.TapsForMana) {
                    if (holds(() -> t.matchesValidParam("Activator", player)
                            && sources.stream().anyMatch(c -> t.matchesValidParam("ValidCard", c)))) hooks |= HOOK_PRODUCTION;
                } else if (t.getMode() == TriggerType.ManaAdded) {
                    if (holds(() -> t.matchesValidParam("Player", player)
                            && sources.stream().anyMatch(c -> t.matchesValidParam("ValidSource", c)))) hooks |= HOOK_PRODUCTION;
                }
            }
        }
        return hooks;
    }

    /** A scope test Forge cannot evaluate here says "it may act": the safe side. */
    private static boolean holds(final BooleanSupplier test) {
        try {
            return test.getAsBoolean();
        } catch (RuntimeException unevaluable) {
            return true;
        }
    }

    /** Does a hook found by {@link #activeHooks} make the proof UNKNOWN, given what the player could make or spend? */
    private static boolean hooksMatter(final int hooks, final boolean anySource, final boolean anyMana) {
        return (hooks & HOOK_PRODUCTION) != 0 && anySource || (hooks & HOOK_CONVERT) != 0 && anyMana;
    }

    public static boolean mayAfford(final Player player, final SpellAbility ability) {
        return assess(player, ability) != Assessment.PROVEN_UNAFFORDABLE;
    }

    public static Assessment assess(final Player player, final SpellAbility ability) {
        if (player == null || ability == null || ability.isManaAbility()
                || ability.isTrigger() || ability.isReplacementAbility() || ability.isPowerUp()
                || ability.getPayCosts() == null || ability.hasParam("ReduceCost")
                || ability.hasParam("TapCreaturesForMana")
                || (ability.hasParam("Announce") && !"X".equals(ability.getParam("Announce")))
                || ability.getParamOrDefault("Cost", "").contains("\\")
                || ability.hasSVar("NumTimes") || player.hasKeyword("PayLifeInsteadOf:B")) {
            return Assessment.UNKNOWN;
        }
        if (ability.isSpell() && (ability.isOffering() || ability.isEmerge() || ability.isBestow()
                || ability.isCastFaceDown() || !ability.getPipsToReduce().isEmpty()
                || ability.getHostCard().hasKeyword(Keyword.CONVOKE)
                || ability.getHostCard().hasKeyword(Keyword.IMPROVISE)
                || ability.getHostCard().hasKeyword(Keyword.DELVE)
                || ability.getHostCard().hasKeyword(Keyword.ASSIST))) return Assessment.UNKNOWN;
        // A may-play grant can convert what mana pays the cost -- Thief of Sanity's
        // "mana of any type can be spent to cast it" is applied by CardPlayOption during
        // payment (PlaySpellAbility). Matching printed pips against printed sources would
        // then prove a payable spell unpayable and hide the only card worth casting.
        final CardPlayOption grant = ability.getMayPlayOption();
        if (grant != null && (grant.isIgnoreManaCostType() || grant.isIgnoreManaCostColor()
                || grant.isIgnoreSnowSourceManaCostColor())) {
            return Assessment.UNKNOWN;
        }
        final CostPartMana part = ability.getPayCosts().getCostMana();
        if (part == null || part.isExiledCreatureCost() || part.isEnchantedCreatureCost()
                || part.getMaxWaterbend() != null || part.getXMin() > 0) return Assessment.UNKNOWN;
        // Non-mana costs can themselves change the mana supply (e.g. untapping a source).
        if (ability.getPayCosts().getCostParts().stream()
                .anyMatch(p -> !(p instanceof CostPartMana) && !(p instanceof CostTap))) return Assessment.UNKNOWN;
        final ManaCost cost = CostAdjustment.presentationManaCost(ability);
        if (cost == null) return Assessment.UNKNOWN;
        final List<ManaCostShard> shards = new ArrayList<>();
        int phyrexian = 0;
        for (ManaCostShard shard : cost) {
            if (shard.isOr2Generic() || shard.isSnow()) return Assessment.UNKNOWN;
            // X=0 is an optimistic lower bound; never choose or mutate the actual X.
            if (shard == ManaCostShard.X) continue;
            if (shard.isPhyrexian()) phyrexian++;
            shards.add(shard);
        }
        for (int i = 0; i < cost.getGenericCost(); i++) shards.add(ManaCostShard.GENERIC);
        if (shards.isEmpty()) return Assessment.POTENTIALLY_AFFORDABLE;

        // Potential cost reductions, mana replacement/trigger generation, or conversion
        // invalidate the simple-source proof. Check public active zones, not hidden cards.
        // afford-jar-1021: only those that can act on this player's mana (activeHooks).
        final int hooks = activeHooks(player, ability.getHostCard(), ability.isSpell());
        if (hooks == HOOK_UNKNOWN) return Assessment.UNKNOWN;

        // block-search-1019: a mana source the loop below cannot count makes any proof unsound
        if (unenumeratedManaSource(player)) return Assessment.UNKNOWN;
        final List<Integer> tokens = new ArrayList<>();
        // Life tokens can match only phyrexian pips, never generic mana. Ignoring
        // source life costs overestimates supply and cannot hide a legal payment.
        for (int i = 1; i <= phyrexian && player.canPayLife(2 * i, false, ability); i++) tokens.add(LIFE_PAYMENT);
        for (var mana : player.getManaPool()) {
            // Ignore spend restrictions optimistically; they can only remove payments.
            tokens.add((int) (mana.getColor() | player.getManaPool().getPossibleColorUses(mana.getColor())));
        }
        boolean anySource = false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command)) {
            if (card.isPhasedOut()) continue;
            int maxAmount = 0;
            int colors = 0;
            for (SpellAbility source : card.getSpellAbilities()) {
                if (!source.isManaAbility()) continue;
                // Ignore printed mana abilities outside their activation zone.
                if (!activatableWhereItIs(card, source)) continue;
                if (!card.isInZone(ZoneType.Battlefield)) return Assessment.UNKNOWN;
                if (source.getPayCosts() == null || source.getPayCosts().getCostParts().stream()
                        .noneMatch(p -> p instanceof CostTap)) return Assessment.UNKNOWN;
                if (source.getPayCosts().getCostParts().stream()
                        .anyMatch(p -> !(p instanceof CostTap) && !(p instanceof CostPartMana)
                                && !(p instanceof CostPayLife)
                                && !(p instanceof CostSacrifice && p.payCostFromSource()))) return Assessment.UNKNOWN;
                // All admitted sources consume the same physical tap. Alternative abilities
                // are unioned, not counted as separate lands. Ignore other costs/restrictions.
                if (card.isTapped() || card.isAbilitySick()) continue;
                if (source.getManaPart() == null) return Assessment.UNKNOWN;
                if (source.hasParam("Each")) return Assessment.UNKNOWN;
                for (SpellAbility tail = source.getSubAbility(); tail != null; tail = tail.getSubAbility()) {
                    if (tail.getApi() != ApiType.DealDamage && tail.getApi() != ApiType.LoseLife) return Assessment.UNKNOWN;
                }
                final String amountText = source.getParamOrDefault("Amount", "1");
                final String produced = source.getManaPart().getOrigProduced();
                if (!amountText.matches("[0-9]{1,3}")) return Assessment.UNKNOWN;
                final boolean choice = "Any".equals(produced) || produced.startsWith("Combo ");
                final String simple = "Any".equals(produced) ? "W U B R G"
                        : produced.startsWith("Combo ") ? produced.substring(6) : produced;
                if (!simple.matches("[WUBRGC]( [WUBRGC])*")) return Assessment.UNKNOWN;
                final String[] symbols = simple.split(" ");
                maxAmount = Math.max(maxAmount, Integer.parseInt(amountText) * (choice ? 1 : symbols.length));
                for (String symbol : symbols) {
                    byte color = ManaAtom.fromName(symbol);
                    colors |= color | player.getManaPool().getPossibleColorUses(color);
                }
            }
            // Unioning alternatives and each token's colors overestimates mixed output,
            // ensuring that a failed matching is still a sound impossibility certificate.
            for (int i = 0; i < Math.min(maxAmount, shards.size()); i++) tokens.add(colors);
            if (maxAmount > 0) anySource = true;
        }
        if (hooksMatter(hooks, anySource, anySource || player.getManaPool().totalMana() > 0)) return Assessment.UNKNOWN;
        return canMatch(shards, tokens) ? Assessment.POTENTIALLY_AFFORDABLE : Assessment.PROVEN_UNAFFORDABLE;
    }

    /**
     * The human priority control's {@code payable} flag (castable-1010): false only when
     * {@link #assess} or {@link #assessPayable} proves that no mana can be found for this
     * ability's cost right now. Both are impossibility certificates, so false is a proof and
     * anything unsure says true.
     */
    public static boolean payable(final Player player, final SpellAbility ability) {
        return assess(player, ability) != Assessment.PROVEN_UNAFFORDABLE
                && assessPayable(player, ability) != Assessment.PROVEN_UNAFFORDABLE;
    }

    /**
     * {@link #assess} for the {@code payable} flag, with more reach and the same soundness.
     * It returns PROVEN_UNAFFORDABLE only when the cost's mana provably cannot come from the
     * mana pool, the mana abilities the player can activate now and Phyrexian life. For a spell
     * with the keyword it also counts creatures to convoke, artifacts to improvise and graveyard
     * cards to delve. X counts as 0.
     *
     * <p>Where it reaches past {@link #assess}:
     * <ul>
     *   <li>The price is {@link CostAdjustment#presentationManaCostLowerBound}. A cost static
     *       that cannot apply to this ability no longer makes the price unknown (Zirda, the
     *       Dawnwaker: live reports 2026-10-10).</li>
     *   <li>Non-mana cost parts are ignored. {@code canPlay} already checked them
     *       ({@code CostPayment.canPayAdditionalCosts}), and ignoring what they use up can only
     *       add mana. The exceptions are parts that could make mana themselves: untap costs and
     *       add-mana costs. Alternative costs reach this check as their own abilities: Force of
     *       Will's pitch, Daze's return, evoke, flashback, escape.</li>
     *   <li>Convoke, improvise and delve count as mana.</li>
     *   <li>{2/C} and snow shards count as one mana of any type.</li>
     *   <li>A tapped or summoning-sick source contributes nothing through its {T} abilities.
     *       Before, it made the whole answer unknown.</li>
     *   <li>More source shapes count: a mana ability limited to once per card by sacrificing or
     *       exiling the card itself (a Spirit Guide from the hand), a counter removed from the card
     *       (Gemstone Mine, counters assumed present), and a source sacrificed afterwards.</li>
     *   <li>afford-jar-1021: a mana ability's OWN mana cost is charged (Chromatic Star, Signets, filters:
     *       star-affordability spec, owner report 2026-10-20T19-46-27). It must be paid from other sources
     *       activated before it, each source used once ({@link #payableWith}); one whose cost cannot be paid
     *       adds nothing, and what counts is the net gain. A mana ability with a mana cost is itself proved
     *       (its control is false when nothing else can pay it).</li>
     *   <li>afford-jar-1021: mana triggers, replacements and conversions count only when they can act on
     *       this player's mana and the player has mana or a source for them to act on ({@link #activeHooks}).</li>
     * </ul></p>
     *
     * <p>Like {@link #assess}, this is a read. It runs no AI payment simulation, calls no
     * controller and draws nothing from the random stream. It copies no ability, so it takes no
     * ids. It taps nothing, chooses no X and leaves the mana pool alone. So it needs none of the
     * observe-only isolation that the bench's priority menu needs around Forge's AI
     * canPayCost (PlayerControllerBridge.observing).</p>
     */
    public static Assessment assessPayable(final Player player, final SpellAbility ability) {
        if (player == null || ability == null || ability.isLandAbility()
                || ability.isTrigger() || ability.isReplacementAbility() || ability.isPowerUp()
                || ability.getPayCosts() == null || ability.hasParam("ReduceCost")
                || ability.hasParam("TapCreaturesForMana")
                || (ability.hasParam("Announce") && !"X".equals(ability.getParam("Announce")))
                || ability.getParamOrDefault("Cost", "").contains("\\")
                || ability.hasSVar("NumTimes") || player.hasKeyword("PayLifeInsteadOf:B")) {
            return Assessment.UNKNOWN;
        }
        final Card host = ability.getHostCard();
        if (host == null) return Assessment.UNKNOWN;
        // afford-jar-1021: a mana ability is proved only for its own mana cost, paid by the OTHER sources
        // (Chromatic Star's {1}). A mana ability with no mana cost stays unknown (= payable), as before.
        final boolean manaAbility = ability.isManaAbility();
        if (manaAbility && ability.getPayCosts().getCostMana() == null) return Assessment.UNKNOWN;
        final boolean spell = ability.isSpell();
        if (spell && (ability.isOffering() || ability.isEmerge() || ability.isBestow()
                || ability.isCastFaceDown() || host.hasKeyword(Keyword.ASSIST))) return Assessment.UNKNOWN;
        // As assess: a may-play grant that converts what mana pays the cost (Thief of Sanity).
        final CardPlayOption grant = ability.getMayPlayOption();
        if (grant != null && (grant.isIgnoreManaCostType() || grant.isIgnoreManaCostColor()
                || grant.isIgnoreSnowSourceManaCostColor())) {
            return Assessment.UNKNOWN;
        }
        final CostPartMana part = ability.getPayCosts().getCostMana();
        if (part != null && (part.isExiledCreatureCost() || part.isEnchantedCreatureCost()
                || part.getMaxWaterbend() != null || part.getXMin() > 0)) return Assessment.UNKNOWN;
        if (ability.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostUntap
                || p instanceof CostUntapType || p instanceof CostAddMana)) return Assessment.UNKNOWN;
        final ManaCost cost = manaAbility ? part.getMana() : CostAdjustment.presentationManaCostLowerBound(ability);
        if (cost == null) return Assessment.UNKNOWN;
        final List<ManaCostShard> shards = new ArrayList<>();
        int phyrexian = 0;
        for (ManaCostShard shard : cost) {
            // X=0 is the lowest price; never choose or mutate the actual X.
            if (shard == ManaCostShard.X) continue;
            if (shard.isPhyrexian()) phyrexian++;
            // {2/C} and snow shards stay: canBePaidWithManaOfColor takes any one mana for them.
            shards.add(shard);
        }
        for (int i = 0; i < cost.getGenericCost(); i++) shards.add(ManaCostShard.GENERIC);
        if (shards.isEmpty()) return Assessment.POTENTIALLY_AFFORDABLE;

        // As assess: mana conversion, an optional cost or granted payment keyword, a mana
        // replacement or a mana trigger in a public active zone may make the supply unknown.
        final int hooks = activeHooks(player, host, spell);
        if (hooks == HOOK_UNKNOWN) return Assessment.UNKNOWN;

        // block-search-1019: a mana source the loop below cannot count makes any proof unsound
        if (unenumeratedManaSource(player)) return Assessment.UNKNOWN;
        final List<Token> base = new ArrayList<>();
        for (int i = 1; i <= phyrexian && player.canPayLife(2 * i, false, ability); i++) {
            base.add(new Token(LIFE_PAYMENT, -1, true));
        }
        boolean anyMana = false;
        for (var mana : player.getManaPool()) {
            // Ignore spend restrictions optimistically; they can only remove payments.
            base.add(new Token((int) (mana.getColor() | player.getManaPool().getPossibleColorUses(mana.getColor())), -1, false));
            anyMana = true;
        }
        final List<int[]> freeGroups = new ArrayList<>();   // {amount, colors}: sources with no mana cost
        final List<Unit> units = new ArrayList<>();          // sources with a mana-costed ability
        int costShards = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command)) {
            if (card.isPhasedOut()) continue;
            // The mana ability being proved cannot pay for itself.
            if (manaAbility && card == host) continue;
            final List<Alt> alts = new ArrayList<>();
            boolean allTap = true;
            for (SpellAbility source : card.getSpellAbilities()) {
                if (!source.isManaAbility()) continue;
                // afford-jar-1021: only where it can be activated now (a hand Urza's artifact-tap ability, a
                // graveyard card's: never).
                if (!activatableWhereItIs(card, source)) continue;
                final forge.game.cost.Cost pay = source.getPayCosts();
                if (pay == null) return Assessment.UNKNOWN;
                final boolean tap = pay.hasTapCost();
                // A {T} ability of a tapped or summoning-sick permanent, or of a card off the battlefield,
                // cannot be activated at all.
                if (tap && (!card.isInZone(ZoneType.Battlefield) || card.isTapped() || card.isAbilitySick())) continue;
                // Cost shapes that make no mana themselves. Each activation is once per card: a tap, or
                // sacrificing or exiling the card itself (Lotus Petal, a Spirit Guide from the hand).
                // Counters a cost removes from the card are assumed present: that only adds mana.
                if (pay.getCostParts().stream().anyMatch(p -> !(p instanceof CostTap) && !(p instanceof CostPartMana)
                        && !(p instanceof CostPayLife)
                        && !((p instanceof CostSacrifice || p instanceof CostExile || p instanceof CostRemoveCounter)
                                && p.payCostFromSource()))) return Assessment.UNKNOWN;
                if (!tap && pay.getCostParts().stream().noneMatch(p -> (p instanceof CostSacrifice
                        || p instanceof CostExile) && p.payCostFromSource())) return Assessment.UNKNOWN;
                if (source.getManaPart() == null) return Assessment.UNKNOWN;
                if (source.hasParam("Each")) return Assessment.UNKNOWN;
                for (SpellAbility tail = source.getSubAbility(); tail != null; tail = tail.getSubAbility()) {
                    // Damage, life loss and sacrificing a source afterwards (Gemstone Mine) make no mana.
                    if (tail.getApi() != ApiType.DealDamage && tail.getApi() != ApiType.LoseLife
                            && tail.getApi() != ApiType.Sacrifice) return Assessment.UNKNOWN;
                }
                final String amountText = source.getParamOrDefault("Amount", "1");
                final String produced = source.getManaPart().getOrigProduced();
                if (!amountText.matches("[0-9]{1,3}")) return Assessment.UNKNOWN;
                final boolean choice = "Any".equals(produced) || produced.startsWith("Combo ");
                final String simple = "Any".equals(produced) ? "W U B R G"
                        : produced.startsWith("Combo ") ? produced.substring(6) : produced;
                if (!simple.matches("[WUBRGC]( [WUBRGC])*")) return Assessment.UNKNOWN;
                final List<ManaCostShard> own = ownManaCost(pay);
                if (own == null) return Assessment.UNKNOWN;
                final String[] symbols = simple.split(" ");
                int colors = 0;
                for (String symbol : symbols) {
                    byte color = ManaAtom.fromName(symbol);
                    colors |= color | player.getManaPool().getPossibleColorUses(color);
                }
                alts.add(new Alt(Integer.parseInt(amountText) * (choice ? 1 : symbols.length), colors, own));
                allTap &= tap;
            }
            if (alts.isEmpty()) continue;
            if (alts.stream().allMatch(a -> a.cost().isEmpty())) {
                // Abilities that all need the one {T} share it; otherwise each may be activated once.
                int max = 0, sum = 0, colors = 0;
                for (Alt a : alts) {
                    max = Math.max(max, a.amount());
                    sum += a.amount();
                    colors |= a.colors();
                }
                freeGroups.add(new int[] {allTap ? max : sum, colors});
                continue;
            }
            // A source with a mana-costed ability is used once, by one of its abilities: they share a {T}, or
            // there is only one. Anything else (a free sacrifice ability beside a costed {T} one) is unknown.
            if (!allTap && alts.size() > 1) return Assessment.UNKNOWN;
            int freeAmount = 0, freeColors = 0;
            final List<Alt> costed = new ArrayList<>();
            for (Alt a : alts) {
                if (a.cost().isEmpty()) {
                    freeAmount = Math.max(freeAmount, a.amount());
                    freeColors |= a.colors();
                } else {
                    costed.add(a);
                    costShards += a.cost().size();
                }
            }
            units.add(new Unit(freeAmount, freeColors, costed));
        }
        // The ordered search below is exponential in the costed sources: past a few, give up (unknown).
        if (units.size() > MAX_COSTED_SOURCES
                || units.stream().mapToInt(u -> u.alts().size()).sum() > MAX_COSTED_ABILITIES) return Assessment.UNKNOWN;
        // The own costs are the printed ones: a cost reduction that may reach a mana ability (Training Grounds)
        // would make them an overestimate, so the proof gives up.
        if ((manaAbility || !units.isEmpty()) && manaAbilityCostsMayFall(player)) return Assessment.UNKNOWN;
        final boolean anySource = freeGroups.stream().anyMatch(g -> g[0] > 0) || !units.isEmpty();
        if (spell) {
            // CR 702.51a convoke: each creature tapped pays {1} or one mana of its colour, sick or not.
            // CR 702.126a improvise: each artifact tapped pays {1}. Either way it must be untapped.
            // CR 702.66a delve: each card exiled from the graveyard pays {1}; the spell is on the stack by then.
            // These pay the spell only, never a mana ability's own cost.
            final boolean convoke = host.hasKeyword(Keyword.CONVOKE);
            final boolean improvise = host.hasKeyword(Keyword.IMPROVISE);
            if (convoke || improvise) {
                for (Card permanent : player.getCardsIn(ZoneType.Battlefield)) {
                    if (permanent.isPhasedOut() || permanent.isTapped()) continue;
                    if (convoke && permanent.isCreature()) {
                        base.add(new Token(permanent.getColor().getColor() | ManaAtom.COLORLESS, -1, true));
                        anyMana = true;
                    } else if (improvise && permanent.isArtifact()) {
                        base.add(new Token(ManaAtom.COLORLESS, -1, true));
                        anyMana = true;
                    }
                }
            }
            if (host.hasKeyword(Keyword.DELVE)) {
                for (Card grave : player.getCardsIn(ZoneType.Graveyard)) {
                    if (grave != host) {
                        base.add(new Token(ManaAtom.COLORLESS, -1, true));
                        anyMana = true;
                    }
                }
            }
        }
        // A mana hook acts only on mana that is made or spent: with no source (and no mana), it cannot help.
        if (hooksMatter(hooks, anySource, anySource || anyMana)) return Assessment.UNKNOWN;
        final int cap = shards.size() + costShards;
        for (int[] group : freeGroups) {
            for (int i = 0; i < Math.min(group[0], cap); i++) base.add(new Token(group[1], -1, false));
        }
        return payableWith(shards, base, units, cap) ? Assessment.POTENTIALLY_AFFORDABLE : Assessment.PROVEN_UNAFFORDABLE;
    }

    /** Is a cost reduction or a set cost in play that is not limited to spells or to non-mana abilities? */
    static boolean manaAbilityCostsMayFall(final Player player) {
        for (Card card : player.getGame().getCardsInGame()) {
            if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Command)
                    && !card.isInZone(ZoneType.Stack)) continue;
            for (StaticAbility s : card.getStaticAbilities()) {
                if (!s.checkMode(StaticAbilityMode.ReduceCost) && !s.checkMode(StaticAbilityMode.SetCost)) continue;
                if ("Spell".equals(s.getParam("Type")) || s.getParamOrDefault("ValidSpell", "").contains("!ManaAbility")) continue;
                return true;
            }
        }
        return false;
    }

    /** {@link #assessPayable} gives up past this many sources with a mana-costed ability on one board. */
    static final int MAX_COSTED_SOURCES = 5;
    /** ... or past this many mana-costed abilities among them. */
    static final int MAX_COSTED_ABILITIES = 6;

    /** One mana: its colour mask (or {@link #LIFE_PAYMENT}), the position of the source activation that made it
     * (-1: available from the start) and whether it can pay the spell only (life, convoke, improvise, delve). */
    record Token(int colors, int rank, boolean spellOnly) {}

    /** One mana ability of a source: what it adds (amount, colour mask) and its own mana cost. */
    record Alt(int amount, int colors, List<ManaCostShard> cost) {}

    /** A source with a mana-costed ability: its free abilities' output (if not used costed) and the costed ones. */
    record Unit(int freeAmount, int freeColors, List<Alt> alts) {}

    /** A mana ability's own mana cost as shards, or null for a cost the proof does not model (X, Phyrexian). */
    static List<ManaCostShard> ownManaCost(final forge.game.cost.Cost pay) {
        final CostPartMana own = pay.getCostMana();
        final List<ManaCostShard> out = new ArrayList<>();
        if (own == null) return out;
        if (own.isExiledCreatureCost() || own.isEnchantedCreatureCost() || own.getAmountOfX() > 0) return null;
        final ManaCost mana = own.getMana();
        if (mana == null) return null;
        for (ManaCostShard shard : mana) {
            if (shard == ManaCostShard.X || shard.isPhyrexian()) return null;
            out.add(shard);
        }
        for (int i = 0; i < mana.getGenericCost(); i++) out.add(ManaCostShard.GENERIC);
        return out;
    }

    /**
     * Can {@code spell} be paid from {@code base} plus some of the costed sources, each activated at most once, in
     * some order, each one's own cost paid from what was available before it (afford-jar-1021)? Tries every ordered
     * choice of costed abilities (few: {@link #MAX_COSTED_SOURCES}); a costed source not chosen adds its free
     * abilities' output. Each try is one bipartite matching of shards to mana.
     */
    static boolean payableWith(final List<ManaCostShard> spell, final List<Token> base, final List<Unit> units, final int cap) {
        final int[] altOf = new int[units.size()];
        Arrays.fill(altOf, -1);
        return payableWith(spell, base, units, cap, altOf, new ArrayList<>());
    }

    private static boolean payableWith(final List<ManaCostShard> spell, final List<Token> base, final List<Unit> units,
                                       final int cap, final int[] altOf, final List<Integer> order) {
        if (matchesInOrder(spell, base, units, cap, altOf, order)) return true;
        for (int u = 0; u < units.size(); u++) {
            if (altOf[u] >= 0) continue;
            for (int a = 0; a < units.get(u).alts().size(); a++) {
                altOf[u] = a;
                order.add(u);
                if (payableWith(spell, base, units, cap, altOf, order)) return true;
                order.remove(order.size() - 1);
                altOf[u] = -1;
            }
        }
        return false;
    }

    private static boolean matchesInOrder(final List<ManaCostShard> spell, final List<Token> base, final List<Unit> units,
                                          final int cap, final int[] altOf, final List<Integer> order) {
        final List<ManaCostShard> needs = new ArrayList<>(spell);
        final List<Integer> needRank = new ArrayList<>();
        for (int i = 0; i < spell.size(); i++) needRank.add(Integer.MAX_VALUE);
        final List<Token> tokens = new ArrayList<>(base);
        for (int u = 0; u < units.size(); u++) {
            if (altOf[u] >= 0) continue;
            for (int i = 0; i < Math.min(units.get(u).freeAmount(), cap); i++) {
                tokens.add(new Token(units.get(u).freeColors(), -1, false));
            }
        }
        for (int position = 0; position < order.size(); position++) {
            final Alt alt = units.get(order.get(position)).alts().get(altOf[order.get(position)]);
            for (ManaCostShard shard : alt.cost()) {
                needs.add(shard);
                needRank.add(position);
            }
            for (int i = 0; i < Math.min(alt.amount(), cap); i++) tokens.add(new Token(alt.colors(), position, false));
        }
        if (tokens.size() < needs.size()) return false;
        final int[] assigned = new int[tokens.size()];
        Arrays.fill(assigned, -1);
        for (int need = 0; need < needs.size(); need++) {
            if (!augmentRanked(need, needs, needRank, tokens, assigned, new boolean[tokens.size()])) return false;
        }
        return true;
    }

    private static boolean augmentRanked(final int need, final List<ManaCostShard> needs, final List<Integer> needRank,
                                         final List<Token> tokens, final int[] assigned, final boolean[] visited) {
        for (int token = 0; token < tokens.size(); token++) {
            if (visited[token]) continue;
            final Token t = tokens.get(token);
            final int rank = needRank.get(need);
            final boolean pays = t.rank() < rank && (!t.spellOnly() || rank == Integer.MAX_VALUE)
                    && (t.colors() == LIFE_PAYMENT ? needs.get(need).isPhyrexian()
                        : needs.get(need).canBePaidWithManaOfColor((byte) t.colors()));
            if (!pays) continue;
            visited[token] = true;
            if (assigned[token] == -1 || augmentRanked(assigned[token], needs, needRank, tokens, assigned, visited)) {
                assigned[token] = need;
                return true;
            }
        }
        return false;
    }

    static boolean canMatch(final List<ManaCostShard> shards, final List<Integer> tokens) {
        if (tokens.size() < shards.size()) return false;
        final int[] assigned = new int[tokens.size()];
        Arrays.fill(assigned, -1);
        for (int shard = 0; shard < shards.size(); shard++) {
            if (!augment(shard, shards, tokens, assigned, new boolean[tokens.size()])) return false;
        }
        return true;
    }

    private static boolean augment(int shard, List<ManaCostShard> shards, List<Integer> tokens,
                                   int[] assigned, boolean[] visited) {
        for (int token = 0; token < tokens.size(); token++) {
            final boolean pays = tokens.get(token) == LIFE_PAYMENT ? shards.get(shard).isPhyrexian()
                    : shards.get(shard).canBePaidWithManaOfColor(tokens.get(token).byteValue());
            if (visited[token] || !pays) continue;
            visited[token] = true;
            if (assigned[token] == -1 || augment(assigned[token], shards, tokens, assigned, visited)) {
                assigned[token] = shard;
                return true;
            }
        }
        return false;
    }
}
