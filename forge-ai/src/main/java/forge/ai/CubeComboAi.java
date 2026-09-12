package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

/** Versioned, seat-visible execution policy. Not a general combo solver.
 * The finite token budget is a combat heuristic, not a proof of a forced win.
 * Costs, legality, triggers, and response windows remain native Forge's. */
public final class CubeComboAi {
    public static final String VERSION = "cube-combo-execution-v61";
    private static final ThreadLocal<Player> PAYMENT_PROBE = new ThreadLocal<>();
    private CubeComboAi() { }

    /** Observability only: own-visible mana breadth, used by the decision log
     * and by decline reasons. Pure reads of our own public battlefield and our
     * own floating mana; no payment probe, no RNG, no state change. */
    static int ownVisibleMana(Player player) {
        int lands = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) if (card.isLand() && card.isUntapped()) lands++;
        return lands + player.getManaPool().totalMana();
    }

    /** Observability only: why the most recent {@link #planTutor} call on this
     * thread produced no plan. Set from checks planTutor already performs, read
     * by the controller immediately after the call. Never consulted by a decision. */
    private static final ThreadLocal<String> TUTOR_DECLINE = new ThreadLocal<>();
    private static void tutorDecline(String reason) { TUTOR_DECLINE.set(reason); }
    static String lastTutorDecline() {
        String reason = TUTOR_DECLINE.get();
        return reason == null ? "other check=planTutor" : reason;
    }

    /** Only the current actor's speculative payment, never a real activation
     * or another player's choice. No RNG state is read, copied or rewound. */
    public static boolean isPaymentProbeFor(Player player) { return PAYMENT_PROBE.get() == player; }

    /** Native affordability helpers are stateful even in test mode. Their scratch
     * payment memory/conversion must not escape a speculative combo check into
     * Default's next choice. This does not wrap or roll back actual payment. */
    public static <T> T probePayment(Player player, java.util.function.Supplier<T> probe, SpellAbility... queried) {
        var memory = new java.util.EnumMap<AiCardMemory.MemorySet, java.util.Set<Card>>(AiCardMemory.MemorySet.class);
        for (var set : AiCardMemory.MemorySet.values()) memory.put(set, new java.util.HashSet<>(AiCardMemory.getMemorySet(player, set)));
        var conversion = player.getManaPool().copyConversionState();
        var abilities = new java.util.ArrayList<ProbeAbilityState>();
        // Native source discovery examines hand mana too (e.g. Spirit Guides),
        // and sets actors on live abilities. Copies passed by a planner also
        // need restoration: a null actor is itself meaningful native state.
        for (Card card : CardCollection.combine(player.getCardsIn(ZoneType.Battlefield), player.getCardsIn(ZoneType.Hand)))
            for (SpellAbility ability : card.getSpellAbilities()) abilities.add(new ProbeAbilityState(ability));
        for (SpellAbility ability : queried) if (ability != null) abilities.add(new ProbeAbilityState(ability.getRootAbility()));
        Player previousProbe = PAYMENT_PROBE.get();
        PAYMENT_PROBE.set(player);
        try { return probe.get(); }
        finally {
            if (previousProbe == null) PAYMENT_PROBE.remove(); else PAYMENT_PROBE.set(previousProbe);
            for (var entry : memory.entrySet()) {
                var live = AiCardMemory.getMemorySet(player, entry.getKey());
                live.clear(); live.addAll(entry.getValue());
            }
            player.getManaPool().restoreConversionState(conversion);
            for (var ability : abilities) ability.restore();
        }
    }

    /** Setter propagates to descendants, so restore parents first and then
     * each child's exact original actor (which may differ from its parent).
     *
     * <p><b>v59 R2.</b> The probed ability's TARGET LIST is snapshotted and
     * restored alongside the actor and the mana express choice. Seven plan sites
     * call {@code resetTargets()} / {@code getTargets().add(...)} on live
     * abilities; the ordinary-path divergence diagnosis rules them out as the
     * cause of any observed divergence but names them as real unrestored state,
     * inert today only because those families decline at their entry gates on the
     * decks measured. Restoring here makes the probe window's purity guarantee
     * independent of which deck is loaded. Registered scope: the snapshot is
     * taken at {@link #probePayment} ENTRY, so residue a plan creates BEFORE it
     * calls a probe is outside this guarantee.</p> */
    private static final class ProbeAbilityState {
        private final SpellAbility ability;
        private final Player actor;
        private final String express;
        /** The live target list as found, and a copy of its contents. Every
         * policy mutation site detaches first ({@code resetTargets()} installs a
         * fresh object), so re-seating this reference restores identity AND
         * contents; the copy covers a bare in-place {@code getTargets().add}. */
        private final forge.game.spellability.TargetChoices targets;
        private final forge.game.spellability.TargetChoices targetsAsFound;
        private final java.util.List<ProbeAbilityState> children = new java.util.ArrayList<>();
        ProbeAbilityState(SpellAbility ability) {
            this.ability = ability; actor = ability.getActivatingPlayer();
            express = ability.getManaPart() == null ? null : ability.getManaPart().getExpressChoice();
            targets = ability.getTargets();
            targetsAsFound = targets == null ? null : targets.clone();
            if (ability.getSubAbility() != null) children.add(new ProbeAbilityState(ability.getSubAbility()));
            for (SpellAbility child : ability.getAdditionalAbilities().values()) children.add(new ProbeAbilityState(child));
            for (var list : ability.getAdditionalAbilityLists().values())
                for (SpellAbility child : list) children.add(new ProbeAbilityState(child));
        }
        void restore() {
            ability.setActivatingPlayer(actor);
            if (ability.getManaPart() != null) ability.getManaPart().setExpressChoice(express);
            if (targets != null) {
                // The probe swapped the object in (resetTargets installs a fresh
                // one): re-seat the original, which restores identity and, because
                // the mutations then went to the NEW object, its contents too.
                if (ability.getTargets() != targets) ability.setTargets(targets);
                // A bare getTargets().add with no preceding reset mutates the very
                // object held here. Refill it IN PLACE so the live TargetChoices
                // identity is preserved in every case.
                if (!sameTargets(targets, targetsAsFound)) refill(targets, targetsAsFound);
            }
            for (var child : children) child.restore();
        }
        /** Element-by-element by IDENTITY. {@code TargetChoices.contains} compares
         * cards by game timestamp and {@code ForwardingList.equals} delegates to an
         * {@code FCollection}, so neither answers "is this the same list". */
        private static boolean sameTargets(forge.game.spellability.TargetChoices live,
                forge.game.spellability.TargetChoices found) {
            if (live.size() != found.size()) return false;
            for (int index = 0; index < live.size(); index++) if (live.get(index) != found.get(index)) return false;
            return true;
        }
        /** {@code removeAll} is the override that also clears the divided and
         * card-controller maps; {@code add} re-populates the controller map. */
        private static void refill(forge.game.spellability.TargetChoices live,
                forge.game.spellability.TargetChoices found) {
            live.removeAll(new java.util.ArrayList<>(live));
            for (forge.game.GameObject object : found) {
                live.add(object);
                Integer divided = found.getDividedValue(object);
                if (divided != null) live.addDividedAllocation(object, divided);
            }
        }
    }

    public static boolean canPayCost(SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilCost.canPayCost(ability, player, effect), ability);
    }
    public static boolean canPayCost(forge.game.cost.Cost cost, SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilCost.canPayCost(cost, ability, player, effect), ability);
    }
    public static boolean canPayManaCost(forge.game.mana.ManaCostBeingPaid cost, SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilMana.canPayManaCost(cost, ability, player, effect), ability);
    }
    public static boolean canPayManaCost(SpellAbility ability, Player player, int extra, boolean effect) {
        return probePayment(player, () -> ComputerUtilMana.canPayManaCost(ability, player, extra, effect), ability);
    }
    public static CardCollection getManaSourcesToPayCost(forge.game.mana.ManaCostBeingPaid cost, SpellAbility ability, Player player, boolean effect) {
        return probePayment(player, () -> ComputerUtilMana.getManaSourcesToPayCost(cost, ability, player, effect), ability);
    }

    public static boolean enabled(Player player) {
        return player.getController() instanceof CubeComboPlayerController;
    }

    /** Conservative availability outside named unusable zones. Unknown owned
     * face-down cards consume possible copies; their identity is never read.
     * Native permission, not ownership alone, authorizes looking at a face.
     * This uses own deck composition, never library contents or order. */
    public static boolean ownCopyOutside(Player player, String name, ZoneType... unusable) {
        int remaining = player.getRegisteredPlayer().getDeck().getMain().countByName(name);
        for (ZoneType zone : unusable) {
            for (Card card : player.getGame().getCardsIn(zone)) {
                if (card.getOwner() != player) continue;
                if (card.isFaceDown()) {
                    if (!card.getView().canFaceDownBeShownTo(player.getView())) {
                        remaining--; // Could be the needed card; no face access.
                        continue;
                    }
                    if (card.getOriginalState(forge.card.CardStateName.Original).getName().equals(name)) remaining--;
                } else if (card.getName().equals(name)) remaining--;
            }
        }
        return remaining > 0;
    }

    /** Forge separates timing/cost availability from static prohibitions.
     * Match AiController's prospective-stack host for cast-from/CMC checks;
     * canPlay() alone does not enforce Null Rod or Rule of Law. */
    public static boolean canPlayNative(SpellAbility ability, Player player) {
        if (!ability.canPlay() || !ability.isLegalAfterStack()) return false;
        Card host = ability.getHostCard();
        if (ability.isSpell()) {
            host = CardCopyService.getLKICopy(host);
            host.setLKICMC(-1);
            host.setLastKnownZone(player.getGame().getStackZone());
            host.setCastFrom(ability.getHostCard().getZone());
        }
        return ability.checkRestrictions(host, player);
    }

    /** The three untap bodies of the Kiki/Twin family, as ONE list so the
     * battlefield/hand recogniser and v61's H2 reach test cannot drift apart.
     * Restoration Angel blinks rather than untaps and is deliberately absent:
     * it is a Kiki partner only, never a Twin partner. */
    static final java.util.List<String> TWIN_PARTNERS =
            java.util.List.of("Pestermite", "Deceiver Exarch", "Zealous Conscripts");

    private static boolean untapBody(Card card) {
        return TWIN_PARTNERS.contains(card.getName());
    }

    public static Card copyPartner(Player player, SpellAbility ability) {
        if (!enabled(player) || !copyEngine(ability)) return null;
        Card source = ability.getHostCard();
        if (source.getController() != player || !source.isInPlay()) return null;
        if (source.getName().equals("Kiki-Jiki, Mirror Breaker") && ability.usesTargeting()) {
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                if ((untapBody(card) || card.getName().equals("Restoration Angel")) && ability.canTarget(card)) return card;
            }
        } else if (untapBody(source) && "Self".equals(ability.getParam("Defined"))
                && "Haste".equals(ability.getParam("AddKeywords"))) {
            return source;
        }
        return null;
    }

    private static boolean copyEngine(SpellAbility ability) {
        return ability.getApi() == ApiType.CopyPermanent && ability.isActivatedAbility()
                && ability.getPayCosts() != null && ability.getPayCosts().hasTapCost()
                && ability.hasParam("AtEOT") && "Haste".equals(ability.getParam("AddKeywords"));
    }

    public static boolean selectSingleTarget(SpellAbility ability, Card target) {
        if (!ability.canTarget(target)) return false;
        ability.resetTargets();
        ability.getTargets().add(target);
        if (ability.isTargetNumberValid() && StaticAbilityMustTarget.meetsMustTargetRestriction(ability)) return true;
        ability.resetTargets();
        return false;
    }

    public static boolean needsMoreCopies(Player player, SpellAbility ability) {
        Card partner = copyPartner(player, ability);
        if (partner == null || !player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)
                || !player.getGame().getStack().isEmpty()) return false;
        int power = partner.getNetPower();
        if (power <= 0) return false;
        long budget = 0;
        for (Player opponent : player.getOpponents()) {
            budget += 2L + opponent.getCreaturesInPlay().size()
                    + (Math.max(0L, opponent.getLife()) + power - 1) / power;
        }
        budget = Math.min(64, budget);
        long readyCopies = player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.isToken() && c.getName().equals(partner.getName())
                        && c.isUntapped() && !c.isSick() && c.getNetPower() > 0).count();
        return readyCopies < budget && ability.getActivationsThisTurn() < 64;
    }

    /** v53 D1/D2 - the engine Aura, recognised by printed property rather than
     * by name: an Aura that enchants a creature and whose continuous static
     * grants that creature an activated CopyPermanent with a tap cost,
     * {@code Defined$ Self}, {@code AddKeywords$ Haste} and {@code AtEOT$} -
     * the same {@link #copyEngine} contract the battlefield recogniser already
     * applies to the granted ability once it is live. Splinter Twin is the only
     * such card in the cube today; the property test is still the contract.
     *
     * The granted ability lives in an SVar, so it has to be parsed through the
     * native ability factory to be tested at all. The cheap {@code contains}
     * pre-filter is there so no unrelated SVar is ever handed to that parser;
     * the parsed ability, not the string, is the actual test. Printed
     * characteristics only - no zone, controller or game state is read here. */
    static boolean engineAura(Card card) {
        if (card == null || card.isFaceDown() || !card.isAura()) return false;
        boolean enchantsCreature = false;
        for (forge.game.keyword.KeywordInterface keyword : card.getKeywords(forge.game.keyword.Keyword.ENCHANT)) {
            String[] parts = keyword.getOriginal().split(":");
            if (parts.length > 1 && parts[1].contains("Creature")) { enchantsCreature = true; break; }
        }
        if (!enchantsCreature) return false;
        for (forge.game.staticability.StaticAbility statik : card.getStaticAbilities()) {
            if (!statik.checkMode(forge.game.staticability.StaticAbilityMode.Continuous)
                    || !statik.hasParam("AddAbility")) continue;
            for (String svar : statik.getParam("AddAbility").split(" & ")) {
                String printed = card.getSVar(svar);
                if (printed == null || !printed.contains("CopyPermanent")) continue;
                SpellAbility granted = forge.game.ability.AbilityFactory.getAbility(card, svar);
                if (granted != null && copyEngine(granted) && "Self".equals(granted.getParam("Defined"))) return true;
            }
        }
        return false;
    }

    /** Our own cast of the engine Aura from our own hand. Shared entry guard of
     * both v53 decisions, so neither can fire on an opponent's spell, on a
     * copy already on the battlefield, or on any other Aura. */
    private static boolean ownEngineAuraCast(Player player, SpellAbility aura) {
        if (!enabled(player) || aura == null || !aura.isSpell()) return false;
        Card source = aura.getHostCard();
        return source != null && source.getOwner() == player && source.isInZone(ZoneType.Hand)
                && engineAura(source);
    }

    /** v53 D1 - the creature the engine Aura should enchant: a Twin partner we
     * control, taken from the native candidate list the ordinary AI already
     * built. A Twin partner is an untap body (Pestermite, Deceiver Exarch,
     * Zealous Conscripts); Restoration Angel blinks rather than untaps, so it
     * is a Kiki partner only and is deliberately not accepted here.
     *
     * Preference is a partner that could actually tap for the granted ability
     * on the turn the Aura lands - untapped, and either not summoning sick or
     * hasty - then any legal partner. Reads our own battlefield through the
     * candidate list; a creature we do not control is refused, never chosen, so
     * the opponent's board is only ever used to say no. Returning null leaves
     * the unchanged native choice in place. */
    public static Card twinAuraTarget(Player player, SpellAbility aura, Iterable<Card> candidates) {
        if (!ownEngineAuraCast(player, aura)) return null;
        Card ready = null, any = null;
        for (Card card : candidates) {
            if (card == null || card.isFaceDown() || card.getController() != player || !untapBody(card)) continue;
            if (any == null) any = card;
            if (ready == null && card.isUntapped()
                    && (!card.isSick() || card.hasKeyword(forge.game.keyword.Keyword.HASTE))) ready = card;
        }
        return ready != null ? ready : any;
    }

    /** v53 D2, conditioned by v61's H1 and H2 - hold the engine Aura only while
     * the partner it needs is one we can still DEPLOY. An Aura spent on a
     * non-partner is destroyed for the rest of the game, which is what the v50
     * drafted read observed; declining the cast costs a turn of a Pump aura
     * instead. v53 conditioned that decline on the partner's PRESENCE in our
     * own hand alone, and named both gaps in its own limitations:
     *
     * <p><b>H1 (castability).</b> A partner in our own hand earns the hold only
     * if {@link #castableWithinTwoDrops} says we could cast it in principle from
     * own-visible resources within two land drops. Otherwise the hold does not
     * fire at all and the ordinary AI proceeds unchanged (Default behaviour),
     * with one {@code CUBE_TWIN_RELEASE} line naming the partner it would have
     * been held for. The hand scan continues past an uncastable partner, so a
     * hand holding one of each still holds, for the castable one.</p>
     *
     * <p><b>H2 (tutor).</b> With NO partner on our battlefield and NONE in our
     * hand - the position the enriched read observed, Imperial Recruiter beside
     * Splinter Twin - the Aura is held when a tutor we could cast or activate
     * could NAME a Twin partner under its printed {@code ChangeType}. H1 and H2
     * can never both fire on one call: H2 is reached only when the hand scan
     * found no partner at all.</p>
     *
     * <p>Conditioned on our own hand and our own battlefield: the opponent's
     * hand, library and decklist are never touched, a face-down card is never
     * identified, and OUR OWN LIBRARY IS NEVER READ - not its contents, not its
     * order, not its size, and not our registered decklist. It does not fire
     * when a partner is already on our battlefield (D1 repairs the target
     * instead) or when the only creature held is a Kiki-only partner such as
     * Restoration Angel.</p>
     *
     * <p>Observability: at most one stderr line per (turn, phase) for this seat,
     * exactly v53's budget. The line names a card in our own hand or a permanent
     * we control, never a card in any hidden zone.</p> */
    public static boolean holdTwinAura(Player player, SpellAbility aura) {
        if (!ownEngineAuraCast(player, aura)) return false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && untapBody(card)) return false;
        Card uncastable = null;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || !untapBody(card)) continue;
            if (castableWithinTwoDrops(player, card.getManaCost())) {
                twinLine(player, "CUBE_TWIN_HOLD reason=partner-in-hand partner=" + token(card.getName()));
                return true;
            }
            if (uncastable == null) uncastable = card;
        }
        if (uncastable != null) {
            // H1 release: this is exactly the position v53/v60 held in.
            twinLine(player, "CUBE_TWIN_RELEASE reason=partner-uncastable partner=" + token(uncastable.getName()));
            return false;
        }
        String tutor = twinTutorOwnVisible(player);
        if (tutor == null) return false;
        twinLine(player, "CUBE_TWIN_HOLD reason=tutor-in-hand tutor=" + token(tutor));
        return true;
    }

    /** Card names reach the log with spaces replaced, so one stderr line stays
     * one whitespace-separated record for the readout's multiset comparison. */
    private static String token(String name) { return name.replace(' ', '_'); }

    /** One CUBE_TWIN_* line per (turn, phase) for this seat, hold and release
     * sharing the single v53 budget so the line COUNT of a phase cannot move.
     * Observability bookkeeping only; the identity stamp is compared, never
     * printed, and no decision reads it. */
    private static void twinLine(Player player, String line) {
        var phases = player.getGame().getPhaseHandler();
        String stamp = System.identityHashCode(player) + ":" + phases.getTurn() + ":" + phases.getPhase();
        if (stamp.equals(TWIN_HOLD_STAMP.get())) return;
        TWIN_HOLD_STAMP.set(stamp);
        System.err.println(line);
    }

    private static final ThreadLocal<String> TWIN_HOLD_STAMP = new ThreadLocal<>();

    /** v61 H1 - could we pay this cost in principle, from own-visible resources,
     * within two land drops? Two independent tests, both deliberately coarse:
     *
     * <p><b>Colour.</b> Every shard that names a colour must have a producer -
     * a permanent we control with a mana ability, or a LAND IN OUR OWN HAND. A
     * hybrid shard is satisfied by either of its colours; generic, colourless
     * and snow shards impose nothing. Tapped state is NOT read here: a source
     * tapped now untaps before the partner is cast, and reading it would make
     * the answer depend on which phase we happen to be asked in.</p>
     *
     * <p><b>Quantity.</b> {@code CMC <= untapped mana permanents we control + 2},
     * the two land drops of the contract. A source that produces two mana counts
     * once and a tapped-but-usable source counts zero: both errors point the
     * same way as the colour test, at a release rather than a hold.</p>
     *
     * <p>This is a forecast, not a payment: no native affordability probe is
     * run, nothing is reserved, and no ability is activated. Our own
     * battlefield and our own hand only.</p> */
    static boolean castableWithinTwoDrops(Player player, forge.card.mana.ManaCost cost) {
        if (cost == null || cost.isNoCost()) return false;
        for (forge.card.mana.ManaCostShard shard : cost) {
            byte colors = shard.getColorMask();
            if (colors != 0 && !ownColorSource(player, colors)) return false;
        }
        return cost.getCMC() <= untappedManaSources(player) + 2;
    }

    /** A producer of any one of these colours among our own lands and rocks in
     * play - any permanent we control carrying a mana ability - or among the
     * lands in our own hand. */
    private static boolean ownColorSource(Player player, byte colors) {
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && producesAny(card, colors)) return true;
        for (Card card : player.getCardsIn(ZoneType.Hand))
            if (!card.isFaceDown() && card.isLand() && producesAny(card, colors)) return true;
        return false;
    }

    /** PRINTED mana production only: the ability's {@code Produced} text as
     * written. {@code Any} produces every colour and a {@code Combo} list
     * produces the colours it names. A colour chosen or computed at resolution
     * ({@code Chosen}, {@code Special}, {@code ManaReflected}) reads as
     * producing nothing - deliberately, because the alternative,
     * {@code AbilityManaPart.mana(sa)}, writes an express choice through
     * {@code ManaEffect.handleSpecialMana}, and this must stay a forecast. The
     * error can only release a hold a richer forecast would keep. */
    private static boolean producesAny(Card card, byte colors) {
        for (SpellAbility mana : card.getManaAbilities()) {
            var part = mana.getManaPart();
            String produced = part == null ? null : part.getOrigProduced();
            if (produced == null) continue;
            if (produced.contains("Any")) return true;
            for (byte color : forge.card.MagicColor.WUBRG)
                if ((colors & color) != 0 && produced.contains(forge.card.MagicColor.toShortString(color))) return true;
        }
        return false;
    }

    /** Untapped permanents we control that carry at least one mana ability -
     * the cube's lands and rocks. Own public battlefield only. */
    private static int untappedManaSources(Player player) {
        int sources = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (!card.isFaceDown() && card.isUntapped() && !card.getManaAbilities().isEmpty()) sources++;
        return sources;
    }

    /** v61 H2 - the name of an admitted tutor, own-visible and affordable under
     * H1's test, whose printed {@code ChangeType} could name a Twin partner.
     *
     * <p>The shapes are v57/v60's, reused rather than restated: a card in our
     * own hand whose printed ETB trigger is a one-card
     * {@link #librarySearchToHand} and whose own cast is mana-only; a plain
     * search spell in our own hand; and an activated search of a permanent we
     * control whose cost {@link #admittedActivationCost} admits. Hand order then
     * battlefield order, the order {@link #widenedShapes} already uses.</p>
     *
     * <p>Native timing ({@code canPlayNative}) is deliberately NOT consulted:
     * the question is whether the tutor is deployable in principle, the same
     * question H1 asks of a partner, not whether it could be cast in this exact
     * window.</p> */
    private static String twinTutorOwnVisible(Player player) {
        for (Card hand : player.getCardsIn(ZoneType.Hand)) {
            if (hand.isFaceDown()) continue;
            SpellAbility own = ownSpellOf(hand);
            SpellAbility etb = etbLibrarySearch(player, hand);
            if (etb != null && own != null && own.isSpell() && manaOnly(own)
                    && reachesTwinPartner(player, etb)
                    && castableWithinTwoDrops(player, hand.getManaCost())) return hand.getName();
            for (SpellAbility search : hand.getSpellAbilities()) {
                if (!search.isSpell() || !librarySearchToHand(search) || search.getSubAbility() != null
                        || !manaOnly(search) || !reachesTwinPartner(player, search)) continue;
                if (castableWithinTwoDrops(player, hand.getManaCost())) return hand.getName();
            }
        }
        for (Card permanent : player.getCardsIn(ZoneType.Battlefield)) {
            if (permanent.isFaceDown() || permanent.getController() != player) continue;
            for (SpellAbility search : permanent.getSpellAbilities()) {
                if (!search.isActivatedAbility() || !librarySearchToHand(search)) continue;
                if (search.getSubAbility() != null && !controlTransferSub(search)) continue;
                if (search.getPayCosts() == null || !admittedActivationCost(player, search)
                        || !reachesTwinPartner(player, search)) continue;
                if (castableWithinTwoDrops(player, search.getPayCosts().getTotalMana())) return permanent.getName();
            }
        }
        return null;
    }

    /** Whether this search's PRINTED {@code ChangeType} admits any Twin partner
     * NAME, tested the way {@link #planFor} tests a fetch candidate: a detached
     * preview card (id -1, never inserted into a zone, never activated) built
     * from the global card database BY NAME, not from our library and not from
     * our registered decklist. Imperial Recruiter's {@code Creature.powerLE2}
     * therefore admits Pestermite and Deceiver Exarch and refuses the 3-power
     * Zealous Conscripts - exactly the restriction v57 registered - and Trinket
     * Mage's {@code Artifact.cmcLE1} admits none of the three.
     *
     * <p>A name the card database has not loaded is skipped rather than
     * guessed, which can only withhold a hold, never invent one.</p> */
    private static boolean reachesTwinPartner(Player player, SpellAbility search) {
        String[] types = search.getParamOrDefault("ChangeType", "Card").split(",");
        Card host = search.getHostCard();
        for (String name : TWIN_PARTNERS) {
            forge.item.PaperCard paper = forge.StaticData.instance().getCommonCards().getCard(name);
            if (paper == null) continue;
            Card preview = forge.game.card.CardFactory.getCard(paper, player, -1, player.getGame());
            preview.setZone(player.getZone(ZoneType.Library));
            if (preview.isValid(types, player, host, search)) return true;
        }
        return false;
    }

    public static Card untapSource(Player player, SpellAbility trigger) {
        if (!enabled(player) || !untapBody(trigger.getHostCard()) || !trigger.usesTargeting()) return null;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            // This is an effect untap, not that player's untap step (exert and
            // "doesn't untap next step" must not prohibit an effect untap).
            if (!card.isTapped() || !card.canUntap(null, true) || !trigger.canTarget(card)) continue;
            for (SpellAbility ability : card.getSpellAbilities()) {
                // The dormant card ability has no activating player after
                // native stack cleanup; canTarget(YouCtrl) on it would reject
                // every partner. Recognize the public engine here, and leave
                // target/cost legality to the next actual activation.
                if (copyEngine(ability) && (card.getName().equals("Kiki-Jiki, Mirror Breaker") && ability.usesTargeting()
                        || card.getName().equals(trigger.getHostCard().getName()) && "Self".equals(ability.getParam("Defined")))) return card;
            }
        }
        return null;
    }

    /** Restoration Angel resets a non-token Kiki through an actual zone change. */
    public static boolean selectBlinkSource(Player player, SpellAbility trigger) {
        if (!enabled(player) || !trigger.getHostCard().getName().equals("Restoration Angel")
                || !trigger.usesTargeting() || trigger.getApi() != ApiType.ChangeZone
                || !"Battlefield".equals(trigger.getParam("Origin")) || !"Exile".equals(trigger.getParam("Destination"))
                || trigger.getSubAbility() == null || trigger.getSubAbility().getApi() != ApiType.ChangeZone
                || !"Battlefield".equals(trigger.getSubAbility().getParam("Destination"))) return false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (!card.getName().equals("Kiki-Jiki, Mirror Breaker") || card.isToken() || !card.isTapped()
                    || !trigger.canTarget(card) || card.getSpellAbilities().stream().noneMatch(CubeComboAi::copyEngine)) continue;
            if (selectSingleTarget(trigger, card)) return true;
        }
        return false;
    }

    public static boolean selectConscriptsSource(Player player, SpellAbility trigger) {
        if (!trigger.getHostCard().getName().equals("Zealous Conscripts") || trigger.getApi() != ApiType.GainControl
                || !trigger.hasParam("Untap")) return false;
        Card source = untapSource(player, trigger);
        if (source == null) return false;
        return selectSingleTarget(trigger, source);
    }

    /** Complete an available Kiki pair with a castable creature. Only invoked
     * during a native search of our own library, using its legal fetch list.
     * No opponent decklist or future library order is inspected. */
    public static Card chooseTutorPartner(Player player, SpellAbility tutor, CardCollection legalChoices) {
        Card chosen = chooseTutorPartnerUngated(player, tutor, legalChoices);
        // v60 - v57 section 8's first limitation, closed. A search whose
        // SubAbility chain hands the searching permanent to an OPPONENT
        // (Wishclaw Talisman) gives them the next activation, so steering it is
        // only worth the trade when the fetched card wins this very turn. This
        // is the same test - fetchWinsThisTurn - that planTutor has applied to
        // its own control-transfer plan since v57, applied now to the
        // ordinary-AI-initiated activation as well, which is the site v57
        // deliberately left open.
        //
        // planFor calls the UNGATED variant, so planTutor's own
        // `subability:not-same-turn` token and its candidate loop are
        // unchanged; and a search with no such SubAbility - every other tutor
        // in the cube, the search-to-top selections and the
        // RearrangeTopOfLibrary ordering - never reaches this clause.
        if (chosen != null && tutor != null && controlTransferSub(tutor)
                && !fetchWinsThisTurn(player, chosen)) return null;
        return chosen;
    }

    /** The v42..v57 body of {@link #chooseTutorPartner}, verbatim. Split out so
     * that planTutor's own forecast keeps its own control-transfer gate and its
     * own decline token. */
    private static Card chooseTutorPartnerUngated(Player player, SpellAbility tutor, CardCollection legalChoices) {
        if (!enabled(player) || tutor == null || tutor.getActivatingPlayer() != player) return null;
        // Kiki's haste route can finish this combat; the new Thopter bodies
        // normally need the next turn. Preserve the available faster route.
        Card immediate = chooseKikiTutorPartner(player, tutor, legalChoices);
        if (immediate != null) return immediate;
        // Then the two families with a native plan that acts in either of our
        // own main phases, in the order this controller already runs their
        // actions (breach before storm). Thopter stays last, unchanged.
        Card piece = choosePlanTutorPiece(player, legalChoices);
        return piece != null ? piece : CubeThopterPlan.chooseAssemblyCard(player, legalChoices);
    }

    /** The single card that would complete the entry gate of a family with a
     * native plan, when that gate is otherwise exactly one card short. Each
     * family's own gate logic reports its own completing names - no threshold
     * is restated here - and only the native offered list is read, so a piece
     * an opponent's search restriction kept out of that list is simply not
     * chosen. Never a library enumeration, never an opponent zone.
     *
     * Family order: the Doomsday pile decision owns the controller API outright
     * and never reaches this method; the Kiki pair is tried first by
     * {@link #chooseTutorPartner} because its haste copies can finish this very
     * combat; then Breach, then Storm, matching the action order
     * CubeComboPlayerController.chooseSpellAbilityToPlay already uses; then the
     * Thopter assembly last, because its bodies need the next turn. */
    private static Card choosePlanTutorPiece(Player player, CardCollection legalChoices) {
        if (!ownPlanSelectionWindow(player) || lethalOrdinaryAttackNow(player)) return null;
        java.util.List<java.util.List<String>> families = planCompletingFamilies(player);
        for (int index = 0; index < families.size(); index++) {
            // The thopter family is deliberately skipped: chooseAssemblyCard
            // already runs as this method's caller's tail and is strictly
            // stricter (supportsAssembly plus a hand-copy check), so a looser
            // thopter family ahead of it could select a piece the assembly
            // refuses. See CubeThopterPlan.completingPieceNames.
            if (index == THOPTER_FAMILY) continue;
            java.util.List<String> family = families.get(index);
            if (family.isEmpty()) continue;
            Card best = null;
            for (Card card : legalChoices) {
                if (card.getOwner() != player || card.isFaceDown() || !card.isInZone(ZoneType.Library)) continue;
                if (!family.contains(card.getName())) continue;
                // The same payability forecast the Kiki halves use: a piece we
                // could not cast after this selection resolves - an
                // unreachable colour above all - must not consume the choice.
                //
                // v60: a completing piece that is a LAND is PLAYED, not cast,
                // so that forecast finds no spell for it at all and would
                // refuse every land outright. The land-drop forecast planFor
                // uses is the right test for one, and it is the same method.
                // No v45..v57 family names a land, so this branch cannot move
                // a v57 receipt.
                if (!(card.isLand() ? landDropFitsAfter(player, card)
                        : feasibleHalfAfterSelection(player, card))) continue;
                if (best == null || card.getCMC() < best.getCMC()) best = card;
            }
            // Deliberately silent. chooseTutorPartner is also the forecast
            // planTutor runs before it casts anything, so a line printed here
            // would announce selections that never happen. The controller
            // already logs the one real site it owns (a search-to-top), and a
            // search of our own library emits no public event either way.
            if (best != null) return best;
        }
        return null;
    }

    /** Index of the thopter family in {@link #planCompletingFamilies}. */
    private static final int THOPTER_FAMILY = 3;

    /** v60 - every family's completing names, in ONE fixed order, built once
     * and shared by the two consumers so they cannot drift: Breach, Storm,
     * Kiki, Thopter, Bomb, Monolith, Kitten, Top, Doomsday.
     *
     * <p>Breach and Storm stay first and in that order - the order
     * {@code CubeComboPlayerController.chooseSpellAbilityToPlay} already runs
     * their actions and the order v45's {@link #choosePlanTutorPiece} already
     * used. Every family added here is strictly later, and every one of them
     * returns an EMPTY list unless its own gate is exactly one role short, so
     * on a board where only a Breach or Storm piece is missing both consumers
     * make the identical choice and set the identical decline token as v57.</p>
     *
     * <p>Each family computes its own answer from its own zone definitions -
     * own hand, own battlefield, own graveyard, our own library SIZE, our own
     * registered deck composition and PUBLIC zones. No library contents or
     * order, no opponent hand, and a face-down card is never identified.</p> */
    private static java.util.List<java.util.List<String>> planCompletingFamilies(Player player) {
        return java.util.List.of(
                CubeBreachPlan.completingPieceNames(player),
                CubeStormPlan.completingPieceNames(player),
                kikiCompletingNames(player),
                CubeThopterPlan.completingPieceNames(player),
                CubeBombPlan.completingPieceNames(player),
                CubeMonolithPlan.completingPieceNames(player),
                CubeKittenPlan.completingPieceNames(player),
                CubeTopPlan.completingPieceNames(player),
                CubeDoomsdayPlan.completingPieceNames(player));
    }

    /** Every name a family with a native plan currently reports as its one
     * missing piece, in {@link #planCompletingFamilies}' order. Used by
     * {@link #planTutor} to decide whether casting a tutor for a piece is worth
     * forecasting at all, and by {@link #protectedPieceNames}. */
    static java.util.List<String> planCompletingNames(Player player) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (java.util.List<String> family : planCompletingFamilies(player)) names.addAll(family);
        return names;
    }

    /** v60 - the ONE Kiki/Twin name {@link #chooseKikiTutorPartner} cannot
     * reach, and deliberately nothing else. That method already answers this
     * family for every name it accepts and runs FIRST inside
     * {@link #chooseTutorPartner}; its fetch test for the engine half is
     * {@code Kiki-Jiki} by name, while {@link #engineHalf} already accepts
     * Splinter Twin for a card in our own hand. The gap is therefore exactly
     * Splinter Twin as a card to fetch, and this method is additive only.
     *
     * <p>Conditions: our own MAIN1 - the Kiki route's priority rests on haste
     * copies finishing THIS combat, which is why {@link #chooseKikiTutorPartner}
     * keeps v45's MAIN1 selection window and {@link #needsMoreCopies} is
     * MAIN1-only, and not relaxing it here keeps the phase semantics
     * identical; an UNTAP BODY own-visible on our own battlefield or castable
     * from our own hand (Restoration Angel blinks rather than untaps and is a
     * Kiki-only partner, exactly as {@link #twinAuraTarget} documents, so a
     * Twin spent on it would be destroyed for the rest of the game); and NO
     * engine half own-visible at all - no live copy ability on our own
     * battlefield, which also covers a creature already enchanted by a Twin,
     * and no Kiki-Jiki or Splinter Twin feasible in our own hand.</p>
     *
     * <p>Own battlefield and own hand only.</p> */
    private static java.util.List<String> kikiCompletingNames(Player player) {
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return java.util.List.of();
        boolean body = false, engine = false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            if (untapBody(card)) body = true;
            if (card.getSpellAbilities().stream().anyMatch(sa -> copyEngine(sa) && !sa.isSuppressed()
                    && sa.copy(player).checkRestrictions(card, player))) engine = true;
        }
        if (engine) return java.util.List.of();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) continue;
            boolean half = engineHalf(card), untap = untapBody(card);
            // The feasibility probe is the expensive half, so it runs only for
            // a card that is actually one of the two roles - the same ordering
            // chooseKikiTutorPartner already uses.
            if (!half && !untap || !feasibleHalfAfterSelection(player, card)) continue;
            if (half) return java.util.List.of();
            body = true;
        }
        return body ? java.util.List.of("Splinter Twin") : java.util.List.of();
    }

    /** A Kiki pair that can finish this combat: the copy engine is on our
     * battlefield with a partner body on the battlefield or castable from
     * hand, or vice versa. Used so a slower plan does not spend the mana that
     * the faster available route needs. MAIN1 only. */
    static boolean hasImmediateKikiRoute(Player player) {
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return false;
        boolean haveKiki = false, havePartner = false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            if (card.getName().equals("Kiki-Jiki, Mirror Breaker")
                    && card.getSpellAbilities().stream().anyMatch(sa -> copyEngine(sa) && !sa.isSuppressed()
                        && sa.copy(player).checkRestrictions(card, player))) haveKiki = true;
            if (untapBody(card) || card.getName().equals("Restoration Angel")) havePartner = true;
        }
        if (haveKiki && havePartner) return true;
        if (haveKiki == havePartner) return false;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) continue;
            if ((haveKiki && (untapBody(card) || card.getName().equals("Restoration Angel"))
                    || havePartner && card.getName().equals("Kiki-Jiki, Mirror Breaker"))
                    && feasiblePartnerAfterSelection(player, card)) return true;
        }
        return false;
    }

    /** A search of our own library that writes its top: Imperial Seal, Vampiric
     * Tutor. The fetched card is drawn on our next turn whichever main phase
     * the search resolved in, and native ChangeZoneAi refuses to cast such a
     * tutor before MAIN2 at all, so MAIN1-only makes this route unreachable. */
    private static boolean searchToTop(SpellAbility source) {
        return source != null && source.getApi() == ApiType.ChangeZone
                && "Library".equals(source.getParam("Destination"))
                && "0".equals(source.getParam("LibraryPosition"));
    }

    /** Our own main phase with nothing pending but this very selection.
     *
     * MAIN2 is admitted for a search-to-top selection only. Everywhere else -
     * a hand-destination tutor, a revealed-order effect, the planTutor
     * forecast - the gate stays MAIN1, exactly as v42. The reason is that the
     * Kiki route takes priority over a slower one because its haste copies can
     * finish *this* combat, which is only true from MAIN1 (needsMoreCopies is
     * MAIN1-only too). In MAIN2 no route is faster, so the phase relaxation has
     * no business changing which card a hand-destination search finds.
     *
     * This is not "any time": a selection made while another item waits on the
     * stack can be answered before we ever draw the card - the pending item may
     * shuffle, draw or remove the half we are pairing with - so the stack must
     * hold at most our own single item (the resolving search itself). An empty
     * stack is the planTutor forecast, which independently requires one. */
    private static boolean ownSelectionWindow(Player player, SpellAbility source) {
        var phases = player.getGame().getPhaseHandler();
        if (!phases.is(PhaseType.MAIN1, player)
                && !(searchToTop(source) && phases.is(PhaseType.MAIN2, player))) return false;
        return ownStackWindow(player);
    }

    /** v45's stack condition, factored out so both windows share one
     * definition: nothing pending but our own single item (the resolving
     * search itself). A selection made while another item waits can be
     * answered before we ever use the card. */
    private static boolean ownStackWindow(Player player) {
        var stack = player.getGame().getStack();
        if (stack.size() > 1) return false;
        for (var item : stack) if (item.getSpellAbility().getActivatingPlayer() != player) return false;
        return true;
    }

    /** The window for a Breach or Storm completing piece: our own MAIN1 or our
     * own MAIN2, for every destination, with v45's stack condition unchanged.
     *
     * This is the stated exception to v45's destination-aware gate, and the
     * reason is the same reason that gate exists. v45 restricted MAIN2 to a
     * search-to-top because the Kiki route's priority rests on haste copies
     * finishing *this* combat, which is a MAIN1 fact (needsMoreCopies is
     * MAIN1-only). CubeBreachPlan.nextAction and CubeStormPlan.nextAction both
     * admit our own MAIN1 and our own MAIN2, so a piece that opens one of
     * their gates is worth the same selection in either phase whatever the
     * tutor's destination: a piece fetched to hand in MAIN2 is usable by that
     * plan in the very same MAIN2, and a piece put on top in MAIN2 is drawn
     * next turn exactly as it would have been from MAIN1. No other consumer's
     * gate moves; chooseKikiTutorPartner keeps {@link #ownSelectionWindow}. */
    private static boolean ownPlanSelectionWindow(Player player) {
        var phases = player.getGame().getPhaseHandler();
        if (!phases.is(PhaseType.MAIN1, player) && !phases.is(PhaseType.MAIN2, player)) return false;
        return ownStackWindow(player);
    }

    /** An unambiguous ordinary win already available this combat: our own
     * attack-ready creatures out-power every opponent, and no opponent
     * controls a creature or a planeswalker that could block or absorb. This
     * exists so a selection never takes credit for a game the ordinary AI was
     * about to win; it is deliberately narrow and is not a combat evaluator.
     * MAIN1 only - after combat there is no attack left this turn. Own
     * battlefield and both public life totals only. */
    private static boolean lethalOrdinaryAttackNow(Player player) {
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return false;
        int power = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.isCreature() || card.isTapped()
                    || card.isSick() && !card.hasKeyword(forge.game.keyword.Keyword.HASTE)
                    || card.hasKeyword(forge.game.keyword.Keyword.DEFENDER)) continue;
            power += Math.max(0, card.getNetPower());
        }
        if (power <= 0 || player.getOpponents().isEmpty()) return false;
        for (Player opponent : player.getOpponents()) {
            if (opponent.getLife() > power || !opponent.getCreaturesInPlay().isEmpty()) return false;
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) if (card.isPlaneswalker()) return false;
        }
        return true;
    }

    /** The engine half of a Kiki pair as a card we could still deploy:
     * Kiki-Jiki itself, or Splinter Twin, which grants the copy ability to the
     * creature it enchants. Recognised by name only for a card in our own
     * hand; a battlefield engine is recognised by its actual copy ability. */
    private static boolean engineHalf(Card card) {
        return card.getName().equals("Kiki-Jiki, Mirror Breaker") || card.getName().equals("Splinter Twin");
    }

    private static boolean partnerHalf(Card card) {
        return untapBody(card) || card.getName().equals("Restoration Angel");
    }

    /** Complete a Kiki pair that is one card short. Each half may be on our
     * own battlefield or in our own hand; a hand half counts only when it is
     * castable after this selection resolves (colour included), so a pair we
     * could not actually cast never consumes the selection. v42 read the
     * battlefield only, which is why the diagnosis found this route firing 0
     * times in 8 natural games while a half sat in hand. Only the offered
     * choice list is read from the library. */
    private static Card chooseKikiTutorPartner(Player player, SpellAbility source, CardCollection legalChoices) {
        if (!ownSelectionWindow(player, source) || lethalOrdinaryAttackNow(player)) return null;
        boolean haveKiki = false, havePartner = false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown()) continue;
            if (card.getName().equals("Kiki-Jiki, Mirror Breaker")
                    && card.getSpellAbilities().stream().anyMatch(sa -> copyEngine(sa) && !sa.isSuppressed()
                        && sa.copy(player).checkRestrictions(card, player))) haveKiki = true;
            if (partnerHalf(card)) havePartner = true;
        }
        // A half in our own hand is just as real as one on the battlefield, but
        // only if we can pay for it. This also subsumes the v42 redundancy
        // check: a complementary half already held makes both flags true and
        // the selection is declined rather than spent on a duplicate.
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown()) continue;
            boolean engine = engineHalf(card), partner = partnerHalf(card);
            if (!engine && !partner || !feasibleHalfAfterSelection(player, card)) continue;
            haveKiki |= engine; havePartner |= partner;
        }
        if (haveKiki == havePartner) return null;
        Card best = null;
        for (Card card : legalChoices) {
            if (card.getOwner() != player || card.isFaceDown() || !card.isInZone(ZoneType.Library)) continue;
            if (!(haveKiki && partnerHalf(card) || havePartner && card.getName().equals("Kiki-Jiki, Mirror Breaker"))) continue;
            if (player.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getName().equals(card.getName()))) continue;
            if (!feasiblePartnerAfterSelection(player, card)) continue;
            if (best == null || card.getCMC() < best.getCMC()) best = card;
        }
        return best;
    }

    /** Forecast a normal hand cast after the resolving selection spell leaves
     * the stack. canPlay() now would wrongly reject every sorcery-speed creature
     * during Ponder resolution. Use native static restrictions and native cost
     * feasibility consistently for both hand alternatives and revealed cards.
     * The real later cast still passes the full native legality/payment path. */
    static boolean feasiblePartnerAfterSelection(Player player, Card card) {
        return feasibleCastAfterSelection(player, card, card.getSpellPermanent());
    }

    /** Splinter Twin is an Aura: its cast is CardState.getAuraSpell(), not a
     * SpellPermanent, so the permanent-only resolution above finds nothing for
     * it. Resolve the half's own spell first, then run the identical forecast.
     * Targeting is deliberately not forecast here - the creature this Aura
     * will enchant is exactly the body the selection is about to fetch - and
     * the real later cast still passes the full native legality path. */
    private static boolean feasibleHalfAfterSelection(Player player, Card card) {
        return feasibleCastAfterSelection(player, card, ownSpellOf(card));
    }

    /** A card's own cast: the permanent spell where there is one, otherwise the
     * card's first spell ability. An Aura casts through CardState.getAuraSpell
     * and an instant or sorcery has no SpellPermanent at all, so the
     * permanent-only resolution finds nothing for either. One definition,
     * shared by the half forecast and by planTutor's second-cast forecast. */
    private static SpellAbility ownSpellOf(Card card) {
        SpellAbility original = card.getSpellPermanent();
        if (original == null)
            for (SpellAbility candidate : card.getSpellAbilities()) if (candidate.isSpell()) { original = candidate; break; }
        return original;
    }

    /** A negative-ID CardFactory preview carries no printed script at all, so a
     * plan piece that is an instant or a sorcery has no spell for the forecast
     * to price. Restore just the printed variables and abilities, through the
     * native parsers, for a detached preview of a name one of the plans named.
     * Deliberately not restored: replacement effects, static abilities,
     * triggers and intrinsic keywords - no check in this forecast reads them,
     * and Brain Freeze's storm copies are not part of a cost. The preview
     * stays detached: it is never inserted into a zone and never activated. */
    private static boolean addPlanPiecePreviewRules(Card card, forge.item.PaperCard paper, java.util.List<String> names) {
        if (card.getId() != -1 || !names.contains(card.getName())) return false;
        var face = paper.getRules().getMainPart();
        for (var variable : face.getVariables()) card.setSVar(variable.getKey(), variable.getValue());
        forge.game.card.CardFactoryUtil.addAbilityFactoryAbilities(card, face.getAbilities());
        return true;
    }

    private static boolean feasibleCastAfterSelection(Player player, Card card, SpellAbility original) {
        if(original==null)return false;
        SpellAbility spell=original.copy(player);
        Card prospective=CardCopyService.getLKICopy(card);
        prospective.setLKICMC(-1);
        prospective.setLastKnownZone(player.getGame().getStackZone());
        prospective.setCastFrom(player.getZone(ZoneType.Hand));
        return spell.isLegalAfterStack()&&spell.checkRestrictions(prospective,player)
                &&CubeComboAi.canPayCost(spell,player,false);
    }

    public record TutorPlan(SpellAbility tutor, CardCollection reservedSources, String plannedPartner) { }

    /** Plan a tutor followed by the missing creature before combat. This uses
     * own registered composition, not a peek at the search's future choices.
     * Native payment finds disjoint sources for the two casts, preserving the
     * creature's sources during the real tutor payment. Shared floating mana
     * and non-mana costs need a richer allocation forecast; decline those
     * plans rather than counting the same resources twice. */
    public static TutorPlan planTutor(Player player) {
        boolean main1 = player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player);
        String thopterMissing = CubeThopterPlan.missingPiece(player);
        // A Breach or Storm piece is worth casting a tutor for in either of our
        // own main phases, for the reason ownPlanSelectionWindow states: both
        // plans act in MAIN2 as well. The mana-only and disjoint-source
        // discipline below is unchanged, floating mana still declines, and the
        // v45 candidate set is still MAIN1-only.
        java.util.List<String> planPieces = planCompletingNames(player);
        // v60: the MAIN2 relaxation stays exactly v45/v57's set. It was
        // registered for the thopter piece and for the Breach and Storm gates,
        // whose plans act in MAIN2 and whose pieces are usable in that very
        // MAIN2. Widening it to all nine families would change the decline
        // token of every MAIN2 board where ANY family is one role short -
        // including suites that hold no tutor at all and are not about
        // tutoring. That is a separate decision and is deliberately not taken
        // in this increment; the wider piece set applies in our own MAIN1,
        // where this guard already passed.
        boolean main2Route = thopterMissing != null
                || !CubeBreachPlan.completingPieceNames(player).isEmpty()
                || !CubeStormPlan.completingPieceNames(player).isEmpty();
        if (!enabled(player) || !(main1 || main2Route && player.getGame().getPhaseHandler().is(PhaseType.MAIN2, player))
                || !player.getGame().getStack().isEmpty() || !player.getManaPool().isEmpty()
                || player.cantWin() || player.hasKeyword("LimitSearchLibrary")) {
            // Observability only: name the guard that already rejected the plan.
            tutorDecline(!player.getGame().getStack().isEmpty() ? "stack-not-empty"
                    : player.cantWin() ? "cant-win"
                    : !main1 ? "phase"
                    : !player.getManaPool().isEmpty() ? "other check=floating-mana"
                    : player.hasKeyword("LimitSearchLibrary") ? "other check=LimitSearchLibrary"
                    : "other check=planTutor-guard");
            return null;
        }
        tutorDecline("other check=no-tutor-in-hand");
        // Phase 1 - the v42 plain-spell shape, over our own hand in zone order
        // and each card's abilities in order, verbatim. A position that already
        // had a qualifying tutor spell therefore takes exactly the v56 path,
        // makes exactly the v56 calls and sets exactly the v56 decline token.
        for (Card hand : player.getCardsIn(ZoneType.Hand)) {
            if (hand.isFaceDown()) continue;
            for (SpellAbility original : hand.getSpellAbilities()) {
                SpellAbility tutor = original.copy(player);
                if (!tutor.isSpell() || !librarySearchToHand(tutor)
                        || tutor.getSubAbility() != null || !manaOnly(tutor)
                        || !canPlayNative(tutor, player) || !player.canSearchLibraryWith(tutor, player)) continue;
                TutorPlan plan = planFor(player, tutor, tutor, false, main1, thopterMissing, planPieces);
                if (plan != null) return plan;
            }
        }
        // Phase 2 - v57's widened shapes, consulted only after the plain-spell
        // pass found nothing, so a v56-reachable position never even builds this
        // list. Each shape's own gate is applied while the list is built.
        for (TutorShape shape : widenedShapes(player)) {
            TutorPlan plan = planFor(player, shape.action(), shape.search(), shape.controlTransfer(),
                    main1, thopterMissing, planPieces);
            if (plan != null) return plan;
        }
        return null;
    }

    /** One tutor candidate: the ability we would actually play, and the search
     * that filters and forecasts. For the v42 plain-spell shape they are the
     * same object. {@code controlTransfer} marks a search whose SubAbility hands
     * the permanent to an opponent (Wishclaw Talisman). */
    private record TutorShape(SpellAbility action, SpellAbility search, boolean controlTransfer) { }

    /** The printed shape of a one-card search of our OWN library that puts the
     * card in our OWN hand, shared by every tutor shape v57 admits. Printed
     * parameters only - no zone, controller or game state is read here.
     *
     * <p>{@code EACH} is refused deliberately. ChangeZoneEffect skips its own
     * ChangeType filter for an {@code EACH} list and fetches one card per
     * clause, so such a search is not a one-card search at all even though it
     * carries no {@code ChangeNum} and would otherwise take the default of 1.
     * Yasharn, Implacable Earth is the cube's example; Vorinclex is refused one
     * step earlier by its explicit {@code ChangeNum$ 2}.</p> */
    private static boolean librarySearchToHand(SpellAbility search) {
        return search != null && search.getApi() == ApiType.ChangeZone && !search.usesTargeting()
                && "Library".equals(search.getParam("Origin"))
                && "Hand".equals(search.getParam("Destination"))
                && "1".equals(search.getParamOrDefault("ChangeNum", "1"))
                && "You".equals(search.getParamOrDefault("Defined", "You"))
                && !search.getParamOrDefault("ChangeType", "Card").startsWith("EACH");
    }

    /** v57 - the tutor shapes beyond the plain spell, in one fixed order: our
     * own hand's creature ETB searches first, then the activated searches of
     * permanents we control. Own hand and own battlefield only; a face-down
     * card is never identified and a permanent we do not control is skipped. */
    private static java.util.List<TutorShape> widenedShapes(Player player) {
        java.util.List<TutorShape> shapes = new java.util.ArrayList<>();
        for (Card hand : player.getCardsIn(ZoneType.Hand)) {
            if (hand.isFaceDown()) continue;
            SpellAbility search = etbLibrarySearch(player, hand);
            if (search == null) continue;
            SpellAbility own = ownSpellOf(hand);
            if (own == null) continue;
            SpellAbility action = own.copy(player);
            if (!action.isSpell() || !manaOnly(action) || !canPlayNative(action, player)
                    || !player.canSearchLibraryWith(search, player)) continue;
            shapes.add(new TutorShape(action, search, false));
        }
        for (Card permanent : player.getCardsIn(ZoneType.Battlefield)) {
            if (permanent.isFaceDown() || permanent.getController() != player) continue;
            for (SpellAbility original : permanent.getSpellAbilities()) {
                if (!original.isActivatedAbility() || !librarySearchToHand(original)) continue;
                boolean transfer = controlTransferSub(original);
                if (original.getSubAbility() != null && !transfer) continue;
                SpellAbility action = original.copy(player);
                if (!admittedActivationCost(player, action) || !canPlayNative(action, player)
                        || !player.canSearchLibraryWith(action, player)) continue;
                shapes.add(new TutorShape(action, action, transfer));
            }
        }
        return shapes;
    }

    /** v57 - a card's own "when this enters, search your library" trigger, as a
     * detached ability. Imperial Recruiter, Recruiter of the Guard, Spellseeker,
     * Trinket Mage, Stoneforge Mystic and Ranger-Captain of Eos are the cube's
     * members; the printed property, not the name, is the contract.
     *
     * The granted ability lives in an SVar, so it has to be parsed through the
     * native ability factory to be tested at all. This deliberately does NOT use
     * {@code Trigger.ensureAbility()}: that caches the parsed ability onto the
     * live trigger, which is a state change, and this is a forecast. The cheap
     * {@code contains} pre-filter keeps unrelated SVars away from the parser,
     * exactly as {@link #engineAura} does for the engine Aura. */
    private static SpellAbility etbLibrarySearch(Player player, Card card) {
        for (forge.game.trigger.Trigger trigger : card.getTriggers()) {
            if (trigger.getMode() != forge.game.trigger.TriggerType.ChangesZone
                    || !"Battlefield".equals(trigger.getParam("Destination"))) continue;
            String valid = trigger.getParam("ValidCard");
            String svar = trigger.getParam("Execute");
            if (valid == null || !valid.contains("Card.Self") || svar == null) continue;
            String printed = card.getSVar(svar);
            if (printed == null || !printed.contains("ChangeZone")) continue;
            SpellAbility search = forge.game.ability.AbilityFactory.getAbility(card, svar);
            if (search == null || !librarySearchToHand(search) || search.getSubAbility() != null) continue;
            // The parsed ability is detached - it is not the trigger's own - so
            // naming the actor here cannot disturb the live trigger, and
            // chooseTutorPartner's actor gate needs it set.
            search.setActivatingPlayer(player);
            return search;
        }
        return null;
    }

    /** v57 - the Wishclaw shape, by printed property rather than by name: the
     * search's SubAbility chain reaches a GainControl of the searching permanent
     * itself. Any other SubAbility is refused by the caller. */
    private static boolean controlTransferSub(SpellAbility search) {
        for (SpellAbility sub = search.getSubAbility(); sub != null; sub = sub.getSubAbility())
            if (sub.getApi() == ApiType.GainControl && "Self".equals(sub.getParam("Defined"))) return true;
        return false;
    }

    /** v57 - the cost shapes an activated search may carry. Mana, a tap, a
     * sacrifice OF THE SEARCHING PERMANENT ITSELF (Expedition Map), and a
     * counter removal (Wishclaw's wish counter) are admitted outright. A discard
     * cost (Survival of the Fittest) is admitted only when EVERY card in our own
     * hand that could pay it is safe to lose: the native AI, not this policy,
     * picks the card discarded, so "a safe discard exists" is not good enough.
     * Every other cost part refuses. Actual payability stays the existing
     * canPayCost / reserved-source discipline, which this does not replace. */
    private static boolean admittedActivationCost(Player player, SpellAbility ability) {
        if (ability.getPayCosts() == null) return false;
        for (var part : ability.getPayCosts().getCostParts()) {
            if (part instanceof forge.game.cost.CostPartMana || part instanceof forge.game.cost.CostTap
                    || part instanceof forge.game.cost.CostRemoveCounter) continue;
            if (part instanceof forge.game.cost.CostSacrifice && part.payCostFromSource()) continue;
            if (part instanceof forge.game.cost.CostDiscard && safeDiscardCost(player, ability, part)) continue;
            return false;
        }
        return true;
    }

    private static boolean safeDiscardCost(Player player, SpellAbility ability, forge.game.cost.CostPart part) {
        String[] types = part.getType().split(";");
        java.util.Set<String> pieces = protectedPieceNames(player);
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || !card.isValid(types, player, ability.getHostCard(), ability)) continue;
            if (pieces.contains(card.getName())) return false;
        }
        return true;
    }

    /** Every name a cost must not be allowed to eat: the Breach and Storm gates'
     * current completing pieces, the Kiki halves and partners, and the thopter
     * family's one missing piece. Own-visible reads only. */
    private static java.util.Set<String> protectedPieceNames(Player player) {
        java.util.Set<String> names = new java.util.HashSet<>(planCompletingNames(player));
        names.addAll(java.util.Set.of("Kiki-Jiki, Mirror Breaker", "Pestermite", "Deceiver Exarch",
                "Restoration Angel", "Zealous Conscripts", "Splinter Twin"));
        String thopter = CubeThopterPlan.missingPiece(player);
        if (thopter != null) names.add(thopter);
        return names;
    }

    /** v57 - the gate on a control-transfer search. Wishclaw Talisman hands the
     * permanent to an opponent, who gets the NEXT activation, so the fetch has
     * to pay off before they ever use it.
     *
     * The one route this policy can honestly forecast as a same-turn win is the
     * Kiki route: our own MAIN1, an engine already on our own battlefield whose
     * copy ability passes its restrictions, and a PARTNER body fetched. Fetching
     * the engine itself does not qualify - it enters summoning sick and cannot
     * tap this turn. A fetch that merely opens a Breach or Storm gate does not
     * qualify either; those plans act this turn but are not forecast here as a
     * kill, and handing an opponent a repeatable tutor for a maybe is exactly
     * the trade this gate exists to refuse.
     *
     * Own battlefield and the printed halves only. */
    private static boolean fetchWinsThisTurn(Player player, Card forecast) {
        if (!player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player) || !partnerHalf(forecast)) return false;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.getName().equals("Kiki-Jiki, Mirror Breaker")) continue;
            if (card.getSpellAbilities().stream().anyMatch(sa -> copyEngine(sa) && !sa.isSuppressed()
                    && sa.copy(player).checkRestrictions(card, player))) return true;
        }
        return false;
    }

    /** The forecast body, one tutor shape at a time. This is v56's inner loop
     * verbatim except for three things: the ChangeType and the offered-list
     * forecast now read the SEARCH rather than the played ability (the same
     * object for a plain spell, so the v42 path cannot move), the second cast is
     * priced against the played ability, and a control-transfer search must also
     * pass {@link #fetchWinsThisTurn}.
     *
     * The candidate names are unchanged and so is how the forecast decides a
     * piece is "in library": our own registered deck composition minus our own
     * visible zones, through {@link #ownCopyOutside}. The library is never
     * enumerated, ordered or read. The preview card is detached (id -1) and is
     * never inserted into a real zone. */
    private static TutorPlan planFor(Player player, SpellAbility tutor, SpellAbility search, boolean controlTransfer,
            boolean main1, String thopterMissing, java.util.List<String> planPieces) {
        tutorDecline("other check=no-partner-route");
        Card host = search.getHostCard();
        // Pass 0 is the v42/v45 candidate set, enumerated in registered
        // deck order exactly as before, so no existing forecast can
        // move. The plan pieces are a strictly later pass: where both a
        // Kiki pair and a plan gate are one short, the faster route is
        // still the one that gets the tutor.
        for (int pass = 0; pass < 2; pass++)
        for (var entry : player.getRegisteredPlayer().getDeck().getMain()) {
            String name = entry.getKey().getName();
            boolean legacy = name.equals(thopterMissing) || main1 && java.util.Set.of("Kiki-Jiki, Mirror Breaker", "Pestermite", "Deceiver Exarch",
                    "Restoration Angel", "Zealous Conscripts").contains(name);
            if ((pass == 0 ? !legacy : legacy || !planPieces.contains(name))
                    || !ownCopyOutside(player, name, ZoneType.Hand, ZoneType.Battlefield,
                        ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Stack)) continue;
            // A detached prototype: no game ID allocation or zone insertion.
            Card forecast = forge.game.card.CardFactory.getCard(entry.getKey(), player, -1, player.getGame());
            if(name.equals(thopterMissing) && !CubeThopterPlan.addAssemblyPreviewRules(forecast,entry.getKey()))continue;
            if(pass == 1 && !addPlanPiecePreviewRules(forecast, entry.getKey(), planPieces))continue;
            forecast.setZone(player.getZone(ZoneType.Library));
            if (!forecast.isValid(search.getParamOrDefault("ChangeType", "Card").split(","), player, host, search)
                    || chooseTutorPartnerUngated(player, search, new CardCollection(forecast)) == null) continue;
            if (controlTransfer && !fetchWinsThisTurn(player, forecast)) {
                tutorDecline("subability:not-same-turn");
                continue;
            }
            forecast.setZone(player.getZone(ZoneType.Hand));
            SpellAbility piece = ownSpellOf(forecast);
            if (piece == null) {
                // v60: a completing piece that is a LAND is PLAYED, not cast -
                // ownSpellOf finds nothing for it and v57 reached this line only
                // to `continue`. The second-cast forecast becomes a land-drop
                // forecast. A land drop costs no mana, so NOTHING is reserved
                // and the tutor's own payment is unchanged; a cast-count
                // prohibition does not apply to a land play, so castFitsAfter is
                // not the right test and is deliberately not called. The drop
                // itself is proposed on a later pass by
                // CubeBombPlan.depthsLandAction and passes full native legality
                // through its own landPlay. Every pass-0 legacy name and every
                // Breach/Storm piece is a nonland, so no v57 receipt can move.
                if (!landDropFitsAfter(player, forecast)) continue;
                tutorDecline("mana:" + (tutor.getHostCard().getCMC() + forecast.getCMC()) + "/" + ownVisibleMana(player));
                if (CubeComboAi.canPayCost(tutor, player, false))
                    return new TutorPlan(tutor, new CardCollection(), name);
                continue;
            }
            SpellAbility creature = piece.copy(player);
            if (!manaOnly(creature) || !castFitsAfter(player, tutor, creature)) continue;
            var cost = ComputerUtilMana.calculateManaCost(creature.getPayCosts(), creature, player, true, 0, false);
            CardCollection reserve = CubeComboAi.getManaSourcesToPayCost(cost, creature, player, false);
            tutorDecline("mana:" + (tutor.getHostCard().getCMC() + forecast.getCMC()) + "/" + ownVisibleMana(player));
            if (reserve != null && withReservedSources(player, reserve,
                    () -> CubeComboAi.canPayCost(tutor, player, false))) return new TutorPlan(tutor, reserve, name);
        }
        return null;
    }

    /** v60 - the land-drop half of the second-piece forecast: what has to fit
     * for a completing piece that is a LAND is our own land drop, not a mana
     * cost. Deliberately COARSER than {@link #castFitsAfter}: it does not run
     * native legality on a detached preview, because the real land play still
     * passes the full native path at the moment it is proposed. Our own public
     * land-play counters only. */
    private static boolean landDropFitsAfter(Player player, Card forecast) {
        return forecast.isLand() && (player.getMaxLandPlaysInfinite()
                || player.getLandsPlayedThisTurn() < player.getMaxLandPlays());
    }

    static boolean manaOnly(SpellAbility sa) {
        return sa.getPayCosts() != null && sa.getPayCosts().getCostParts().stream()
                .allMatch(part -> part instanceof forge.game.cost.CostPartMana);
    }

    /** Account for public cast-count prohibitions after the first spell.
     * Both actual casts still pass native legality; this is a forecast only.
     * Shared by planTutor and by the two-piece hand-assembly forecast, so a
     * single prohibition rule covers every two-cast plan. */
    static boolean castFitsAfter(Player player, SpellAbility first, SpellAbility second) {
        Card next = CardCopyService.getLKICopy(second.getHostCard());
        next.setLastKnownZone(player.getGame().getStackZone());
        Card prior = CardCopyService.getLKICopy(first.getHostCard());
        prior.setLastKnownZone(player.getGame().getStackZone());
        for (Card source : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (source.isFaceDown()) continue;
            for (var st : source.getStaticAbilities()) {
                if (!st.hasParam("NumLimitEachTurn")
                        || !st.checkConditions(forge.game.staticability.StaticAbilityMode.CantBeCast)
                        || !st.matchesValidParam("ValidCard", next) || !st.matchesValidParam("Caster", player)
                        || st.getIgnoreEffectPlayers().contains(player)) continue;
                if (!java.util.Set.of("Mode", "ValidCard", "Caster", "NumLimitEachTurn", "Description")
                        .containsAll(st.getMapParams().keySet())) return false;
                int used = forge.game.card.CardLists.filterControlledByAsList(
                        forge.game.card.CardUtil.getThisTurnCast(st.getParamOrDefault("ValidCard", "Card"), next, st, player), player).size();
                if (used + (st.matchesValidParam("ValidCard", prior) ? 1 : 0)
                        >= Integer.parseInt(st.getParam("NumLimitEachTurn"))) return false;
            }
        }
        return true;
    }

    public static <T> T withReservedSources(Player player, CardCollection sources, java.util.function.Supplier<T> task) {
        CardCollection added = new CardCollection();
        var memory = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        for (Card source : sources) if (!AiCardMemory.isRememberedCard(player, source, memory)) {
            AiCardMemory.rememberCard(player, source, memory); added.add(source);
        }
        try { return task.get(); }
        finally { for (Card source : added) AiCardMemory.forgetCard(player, source, memory); }
    }
}
