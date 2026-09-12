package forge.ai;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import java.util.List;

/** Bounded Will/Tendrils resource plan. This is a sequencing policy, not a
 * forced-win certificate: only known own cards, public life/storm and native
 * legal actions are used. Draws/searches and all costs resolve normally. */
public final class CubeStormPlan {
    private static final String WILL = "Yawgmoth's Will", TENDRILS = "Tendrils of Agony";
    // Develop expendable resources before the most valuable replay engine.
    // This order does not inspect whether an opponent holds a counterspell.
    private static final List<String> ROCKS = List.of("Lotus Petal", "Mox Jet", "Mox Sapphire",
            "Mox Ruby", "Mox Emerald", "Mox Pearl", "Mana Crypt", "Black Lotus");
    private static final List<String> DRAWS = List.of("Ancestral Recall", "Ponder", "Preordain", "Brainstorm", "Gitaxian Probe");
    private final Player player;
    private int turn = -1, actions, failedTurn = -1;
    private boolean attemptedWill;
    private SpellAbility selected;
    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeDoomsdayPlan#declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code action-cap},
     * {@code failed-this-turn}, {@code multiplayer},
     * {@code missing=<our own missing half>}, {@code cant-drain},
     * {@code finisher-untargetable}, {@code no-lethal-forecast} (v63) or
     * {@code no-resource-action}. The
     * missing-half token names a card this plan cannot find in OUR OWN hand or
     * graveyard; no token names an opponent zone.</p> */
    private String decline = "other check=storm-plan";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline = reason; return null; }
    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order, after that guard has already
     * decided to decline. */
    private String gateReason() {
        var phase = player.getGame().getPhaseHandler();
        if (actions >= 40) return "action-cap";
        if (failedTurn == turn) return "failed-this-turn";
        if (player.cantWin()) return "cant-win";
        if (!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        if (!(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))) return "phase";
        return "multiplayer";
    }

    public CubeStormPlan(Player player) { this.player = player; }

    /** v49, predicate-only: one hand card this instance must pretend it does
     * not have, so {@link #discardProtectedCards} can ask the plan's own gate
     * what it would say without that card. Set only on the throwaway instances
     * the two static predicates build; the live plan never sets it. */
    private Card excluded;

    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
            if (card != excluded && !card.isFaceDown() && name.equals(card.getName())) return card;
        return null;
    }

    private SpellAbility spell(Card card) {
        if (card == null) return null;
        for (SpellAbility original : card.getAllPossibleAbilities(player, false, null, true)) {
            SpellAbility ability = original.copy(player);
            if (!ability.isSpell() || !CubeComboAi.canPlayNative(ability, player)
                    || !CubeComboAi.canPayCost(ability, player, false)) continue;
            return ability;
        }
        return null;
    }

    private SpellAbility spell(String name) {
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Graveyard)) {
            SpellAbility ability = spell(find(name, zone));
            if (ability != null) return ability;
        }
        return null;
    }

    private boolean target(SpellAbility ability, Player target) {
        if (!ability.usesTargeting()) return true;
        ability.resetTargets();
        if (!ability.canTarget(target)) return false;
        ability.getTargets().add(target);
        return ability.isTargetNumberValid() && StaticAbilityMustTarget.meetsMustTargetRestriction(ability);
    }

    private SpellAbility select(SpellAbility ability) {
        if (ability != null) { selected = ability; actions++; }
        return ability;
    }

    /** Yawgmoth's Will where this plan can cast it: our own hand only. It is
     * not castable from the graveyard without another Will effect. */
    private Card will() { return find(WILL, ZoneType.Hand); }

    /** Tendrils where this plan can reach it: our own hand, else our own
     * graveyard (the Will replays it from there). The asymmetry between these
     * two halves is the gate, and it is defined once, here. */
    private Card finisher() {
        Card finisher = find(TENDRILS, ZoneType.Hand);
        return finisher != null ? finisher : find(TENDRILS, ZoneType.Graveyard);
    }

    /** Which single card, fetched from our own library, would complete this
     * plan's entry gate. Empty when the gate is already open or more than one
     * half is missing. Own hand and own graveyard only; the downstream
     * Will-cast gate (a replayable rock and a spell in our graveyard) is this
     * plan's business on the turn it acts, not the selection's. */
    static java.util.List<String> completingPieceNames(Player player) {
        return new CubeStormPlan(player).missingPieces();
    }

    private java.util.List<String> missingPieces() {
        boolean will = will() != null, finisher = finisher() != null;
        if (will == finisher) return java.util.List.of();
        return java.util.List.of(will ? TENDRILS : WILL);
    }

    /** v49: is this plan's entry gate currently complete - both halves where
     * the plan can use them? The single definition, read by
     * {@link #discardProtectedCards} so the protection cannot drift from
     * {@link #missingPieces}'s own asymmetry (the Will from hand only; Tendrils
     * from hand or graveyard). */
    private boolean gateReady() { return will() != null && finisher() != null; }

    /** v49, the Breach-diagnosis C2 shape: cards in our own hand that are the
     * LAST copy this plan can reach of a half of an entry gate that is
     * otherwise ready. Empty unless the gate is complete right now, and a card
     * is named only when the same gate stops being complete once that card is
     * taken away - which is exactly "last obtainable copy", computed from the
     * plan's own zone definitions rather than a card-name rule.
     *
     * <p>Deliberately narrow, and deliberately NOT "never discard a combo
     * piece": a redundant second copy in a zone the half already accepts is not
     * named, a gate that is already a card short is not defended (breaking a
     * complete gate is the registered shape; widening to a speculative one is
     * not), and a gate that cannot be completed at all names nothing. Own hand
     * and own graveyard only.</p> */
    static forge.game.card.CardCollection discardProtectedCards(Player player) {
        forge.game.card.CardCollection kept = new forge.game.card.CardCollection();
        CubeStormPlan plan = new CubeStormPlan(player);
        if (!plan.gateReady()) return kept;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            plan.excluded = card;
            boolean stillReady = plan.gateReady();
            boolean stillCompletable = !plan.missingPieces().isEmpty();
            plan.excluded = null;
            if (!stillReady && stillCompletable) kept.add(card);
        }
        return kept;
    }

    private boolean knownDraw() {
        for (String name : DRAWS) for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Graveyard))
            if (find(name, zone) != null) return true;
        return false;
    }

    /** v72 R1 - the PRINTED property that makes a rock a SACRIFICE rock: an
     * artifact one of whose own mana abilities pays for itself by sacrificing
     * itself. Black Lotus, Lotus Petal and the Lion's Eye Diamond class all
     * print exactly this; a Mox, Mana Crypt, Chrome Mox or Sol Ring does not.
     *
     * <p>Read off the card's own cost parts ({@code CostSacrifice} whose type
     * is the source itself), never off its name, so a renamed reprint is
     * treated on the same terms and a card that merely shares a name is not.
     * Nothing else about the card is read, and no zone is assumed.</p> */
    private static boolean sacrificeRock(Card card) {
        if (card == null || card.isFaceDown() || !card.isArtifact()) return false;
        for (SpellAbility mana : card.getManaAbilities()) {
            forge.game.cost.Cost cost = mana.getPayCosts();
            if (cost == null) continue;
            for (forge.game.cost.CostPart part : cost.getCostParts())
                if (part instanceof forge.game.cost.CostSacrifice && part.payCostFromSource()) return true;
        }
        return false;
    }

    /** v72 R1 - the battlefield rock this plan's own crack step would sacrifice,
     * in the order it looks for one. Defined once so {@link #crackLotus} and
     * {@link #reachableStormBound}'s battlefield term cannot drift apart: the
     * bound may count a crack only where the build would actually take it. */
    private Card battlefieldSacrificeRock() {
        Card lotus = find("Black Lotus", ZoneType.Battlefield);
        if (lotus == null) lotus = find("Lotus Petal", ZoneType.Battlefield);
        return lotus;
    }

    private SpellAbility crackLotus() {
        Card lotus = battlefieldSacrificeRock();
        if (lotus == null) return null;
        // Preserve the finisher's BB before making optional blue for cantrips.
        // Existing land mana can fund cantrips; a later black-consuming tutor
        // must not strand Tendrils after the Lotus has been exiled by Will.
        byte color = attemptedWill && player.getManaPool().getAmountOfColor(MagicColor.BLACK) >= 2
                && knownDraw() && player.getManaPool().getAmountOfColor(MagicColor.BLUE) < 2
                ? MagicColor.BLUE : MagicColor.BLACK;
        String symbol = color == MagicColor.BLUE ? "U" : "B";
        for (SpellAbility original : lotus.getManaAbilities()) {
            SpellAbility ability = original.copy(player);
            if (CubeComboAi.canPlayNative(ability, player) && ability.getManaPart().canProduce(symbol, ability)
                    && CubeComboAi.canPayCost(ability, player, false)) {
                ability.setManaExpressChoice(ColorSet.fromMask(color));
                return ability;
            }
        }
        return null;
    }

    /** v63 C1, amended by v72 R1 - an own-visible UPPER BOUND on the storm count
     * this plan could reach this turn, counting only spells it can NAME right
     * now, and counting each of them as many times as this plan's OWN BUILD
     * ORDER would actually cast it:
     *
     * <ul>
     * <li>our own HAND: every {@link #ROCKS} name, Dark Ritual, Cabal Ritual,
     *     every {@link #DRAWS} name, and Yawgmoth's Will itself while it is in
     *     hand and has not already been attempted this turn;</li>
     * <li><b>v72 R1:</b> a hand card that is a SACRIFICE ROCK by the printed
     *     property of {@link #sacrificeRock} counts <b>TWICE</b> while the Will
     *     is reachable - once cast from hand, and once replayed out of the
     *     graveyard the crack step put it in. This is not a new line of play:
     *     the action order below is ROCKS, then {@code crackLotus}, then the
     *     Will, then ROCKS again out of the graveyard, and the diagnosis
     *     measured v60's only storm win (`storm-16702371-s1` t6) doing exactly
     *     that - cast Black Lotus, crack it, cast the Will, cast Black Lotus
     *     again. v63's "each physical card at most once" rule contradicted the
     *     plan's own winning line and refused the one feasible lethal in the
     *     v67 read (`storm-16702450-s0` t7: bound 3 against a need of 4);</li>
     * <li><b>v72 R1:</b> a sacrifice rock already on our own BATTLEFIELD counts
     *     ONCE while the Will is reachable - the crack is an activation, not a
     *     cast, but it puts the rock in the graveyard where the Will replays it.
     *     Only the one rock {@link #battlefieldSacrificeRock} would actually
     *     crack is counted, so the bound cannot count a crack the build would
     *     not take;</li>
     * <li>our own GRAVEYARD, and only while the Will is still reachable this
     *     turn, the same rock / ritual / cantrip names, once each - replaying
     *     exactly those is what this plan's own build does once the Will
     *     resolves;</li>
     * <li>never Tendrils of Agony: it is the {@code +1} of the lethal test.</li>
     * </ul>
     *
     * <p>It is a BOUND, not a promise. No mana, no native legality, no
     * targeting and no counterspell risk is priced here - a board that passes
     * may still fail to reach lethal, and that is deliberate: the gate exists
     * to refuse a build that could not reach lethal even if everything worked,
     * not to predict a win. <b>v72 deliberately adds NO mana floor</b>: the
     * diagnosis measured the obvious one ("afford the Will plus Tendrils from
     * current sources") refusing v60's only storm win, which had zero untapped
     * lands and reached lethal through a Lotus and rituals.</p>
     *
     * <p><b>v72 widens no vocabulary.</b> The names counted are v63's exactly;
     * {@link #sacrificeRock} only changes how many times an already-counted
     * hand card counts, and the battlefield term is restricted to the rock the
     * crack step itself looks for. Adding a name to {@link #ROCKS} without
     * teaching the build to cast it is the diagnosis's R2 hazard and is not
     * done here.</p>
     *
     * <p>The bound does not decay through the build, so a build this gate lets
     * start cannot be stranded by it mid-turn: casting a counted spell raises
     * {@code storm} by one and lowers the hand count by one, and a ritual or
     * cantrip then enters the graveyard where it is counted again while the
     * Will is reachable; the Lotus crack is an activation, not a spell, and
     * moves the rock from the battlefield term to the graveyard term at the
     * same value.</p>
     *
     * <p>Own hand, own graveyard, own battlefield and the public storm count
     * only.</p> */
    private int reachableStormBound(int storm) {
        boolean willReachable = will() != null || attemptedWill;
        int countable = will() != null && !attemptedWill ? 1 : 0;
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Graveyard)) {
            if (zone == ZoneType.Graveyard && !willReachable) continue;
            for (Card card : player.getCardsIn(zone)) {
                if (card == excluded || card.isFaceDown()) continue;
                String name = card.getName();
                if (!(ROCKS.contains(name) || DRAWS.contains(name)
                        || name.equals("Dark Ritual") || name.equals("Cabal Ritual"))) continue;
                countable++;
                // v72 R1: cast from hand, cracked into the graveyard, replayed.
                if (zone == ZoneType.Hand && willReachable && sacrificeRock(card)) countable++;
            }
        }
        // v72 R1: the crack step's own rock, replayed once the Will resolves.
        Card onBoard = willReachable ? battlefieldSacrificeRock() : null;
        if (onBoard != null && onBoard != excluded && sacrificeRock(onBoard)) countable++;
        return storm + countable;
    }

    public SpellAbility nextAction() {
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) {
            turn = phase.getTurn(); actions = 0; attemptedWill = false; selected = null;
        }
        if (actions >= 40 || failedTurn == turn || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return decline(gateReason());
        decline = "other check=storm-plan";
        Player opponent = player.getOpponents().get(0);
        Card will = will();
        Card finisher = finisher();
        if (attemptedWill && Boolean.getBoolean("forge.bench.comboTrace"))
            System.err.println("CUBE_STORM_STATE turn=" + turn + " phase=" + phase.getPhase() + " storm="
                    + game.getStack().getSpellsCastThisTurn().size() + " hand=" + player.getCardsIn(ZoneType.Hand)
                    + " manaTotal=" + player.getManaPool().totalMana()
                    + " blue=" + player.getManaPool().getAmountOfColor(MagicColor.BLUE)
                    + " black=" + player.getManaPool().getAmountOfColor(MagicColor.BLACK)
                    + " finisher=" + (finisher == null ? "absent" : finisher.getZone()));
        if (finisher == null || will == null && !attemptedWill || !opponent.canLoseLife())
            return decline(finisher == null ? "missing=" + token(TENDRILS)
                    : will == null && !attemptedWill ? "missing=" + token(WILL) : "cant-drain");
        // Do not commit a storm resource plan toward an untargetable finish.
        if (finisher.getSpellAbilities().stream().noneMatch(a -> a.copy(player).canTarget(opponent))) return decline("finisher-untargetable");
        int storm = game.getStack().getSpellsCastThisTurn().size();
        SpellAbility lethal = spell(finisher);
        if (lethal != null && 2L * (storm + 1) >= opponent.getLife() && target(lethal, opponent)) return select(lethal);
        // v63 C1. Everything below this line SPENDS resources - rocks, the
        // Lotus, rituals, cantrips, a setup spell and finally the deck's single
        // most valuable card - toward the lethal test that has just failed, and
        // before v63 nothing asked whether the build could reach it. Require
        // the plan's OWN condition to be reachable from an own-visible upper
        // bound on this turn's storm count, or decline and leave the ordinary
        // AI's play untouched. The branch above is unchanged, so a position
        // that can already win still wins on the same pass.
        if (2L * (reachableStormBound(storm) + 1) < opponent.getLife()) return decline("no-lethal-forecast");

        for (String name : ROCKS) {
            SpellAbility rock = spell(name);
            if (rock != null) return select(rock);
        }
        // Sacrifice before Will so the same known Lotus can be replayed.
        // Native legality forbids the activation under Null Rod, etc.
        if ((will != null || attemptedWill) && battlefieldSacrificeRock() != null) {
            SpellAbility mana = crackLotus();
            if (mana != null) return select(mana);
        }
        for (String name : List.of("Dark Ritual", "Cabal Ritual")) {
            SpellAbility ritual = spell(name);
            if (ritual != null) return select(ritual);
        }
        // Prefer hand cantrips before Will; then legally replay graveyard ones.
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Graveyard)) for (String name : DRAWS) {
            int draw = name.equals("Ancestral Recall") || name.equals("Brainstorm") ? 3 : 1;
            if (player.getCardsIn(ZoneType.Library).size() <= draw) continue;
            SpellAbility cantrip = spell(find(name, zone));
            if (cantrip != null && target(cantrip, player)) return select(cantrip);
        }
        // Do not let Default defer the final setup spell to MAIN2 and lose
        // floating mana at the intervening phase boundary. One cheap spell
        // short of lethal: preserve BB plus two generic for the actual finish.
        if (lethal != null && 2L * (storm + 2) >= opponent.getLife()
                && player.getManaPool().getAmountOfColor(MagicColor.BLACK) >= 3) {
            for (String name : List.of("Duress", "Thoughtseize", "Imperial Seal")) {
                if (!name.equals("Duress") && player.getLife() <= 2) continue;
                SpellAbility setup = spell(name);
                if (setup == null || !target(setup, opponent)) continue;
                int extra = setup.getPayCosts().getTotalMana().getCMC();
                if (CubeComboAi.canPayManaCost(lethal, player, extra, false)) return select(setup);
            }
        }
        if (!attemptedWill && will != null) {
            // This minimum is a resource-plan reach gate, not a claim of lethal.
            // Unlike Default's count, a mana engine plus spells is recognized.
            boolean engine = ROCKS.stream().anyMatch(n -> find(n, ZoneType.Graveyard) != null);
            int spells = 0;
            for (Card card : player.getCardsIn(ZoneType.Graveyard))
                if (DRAWS.contains(card.getName()) || card.getName().equals(TENDRILS)
                        || card.getName().equals("Dark Ritual") || card.getName().equals("Cabal Ritual")) spells++;
            SpellAbility cast = engine && spells >= 1 ? spell(will) : null;
            if (cast != null) return select(cast);
        }
        return decline("no-resource-action");
    }

    /** A card name as one log token: our own missing half, never an opponent
     * card and never a library read. */
    private static String token(String name) { return name.replace(' ', '_'); }

    // ---------------------------------------------------------------- v72

    /** v72 C2 - the two terminals a graveyard route of this policy can end on,
     * and the two replay engines that reach them out of OUR OWN graveyard.
     * Tendrils is this plan's own finisher; Brain Freeze is
     * {@link CubeBreachPlan}'s. The Will replays from the graveyard once;
     * Underworld Breach grants escape to it for as long as it is in play. */
    private static final List<String> HOLD_TERMINALS = List.of(TENDRILS, "Brain Freeze");
    private static final List<String> HOLD_ENGINES = List.of(WILL, "Underworld Breach");
    private static final ThreadLocal<String> HOLD_STAMP = new ThreadLocal<>();

    /** The PRINTED shape of a graveyard-shuffling wheel: a {@code ChangeZoneAll}
     * whose origin includes the graveyard and whose destination is the library
     * or exile. Echo of Eons, Timetwister and Time Spiral all print exactly
     * this; the card names appear nowhere in this class, so a renamed reprint
     * is held on the same terms and a card that merely shares a name is not.
     *
     * <p>A spell that TARGETS is deliberately refused here: its controller
     * chooses whose zones move, this guard does not read that choice, and the
     * error points at RELEASE.</p> */
    private static boolean graveyardShuffle(SpellAbility spell) {
        if (spell.getApi() != forge.game.ability.ApiType.ChangeZoneAll || spell.usesTargeting()) return false;
        String origin = spell.getParam("Origin"), destination = spell.getParam("Destination");
        return origin != null && destination != null && origin.contains("Graveyard")
                && (destination.equals("Library") || destination.equals("Exile"));
    }

    /** Exactly the cards of OUR OWN graveyard this spell would move, filtered by
     * the spell's own printed {@code ChangeType} through the engine's own
     * filter - the same call {@code ChangeZoneAllAi} already makes to build its
     * {@code computerType} list. Our own graveyard only; no other zone of ours
     * and no zone of the opponent's is read. */
    private static forge.game.card.CardCollectionView movedFromOwnGraveyard(Player player, SpellAbility spell) {
        return forge.game.ability.AbilityUtils.filterListByType(
                player.getCardsIn(ZoneType.Graveyard), spell.getParam("ChangeType"), spell);
    }

    /** A gate piece where this seat can see it and could still use it: our own
     * hand or our own battlefield. A card in the graveyard is deliberately NOT
     * an engine - the Will cannot be cast from there by this plan, and a Breach
     * in the graveyard grants escape to nothing. */
    private static Card ownEngine(Player player, String name) {
        for (ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Hand))
            for (Card card : player.getCardsIn(zone))
                if (!card.isFaceDown() && name.equals(card.getName())) return card;
        return null;
    }

    /** v72 C2 - refuse the ORDINARY AI's cast of OUR OWN graveyard-shuffling
     * wheel (Echo of Eons, Timetwister, Time Spiral - recognised by the printed
     * {@link #graveyardShuffle} shape and never by name) while a storm or Breach
     * route whose gate pieces sit in OUR OWN GRAVEYARD is own-visibly reachable.
     *
     * <p>This is the second half of the diagnosis's one over-conservative
     * decline. On {@code storm-16702450-s0} t7 the v63 gate refused a lethal it
     * could have taken (R1 fixes that), and on the very next pass the ordinary
     * AI cast Echo of Eons and shuffled Tendrils of Agony and Yawgmoth's Will
     * out of our graveyard; from that pass to the end of the game the storm
     * token was {@code missing=Tendrils_of_Agony} and we died on t12. The cast
     * threw away the route, exactly as v68's Breach cast threw away a gate
     * piece, and this guard is v68's {@code holdBreach} in the sorcery/draw
     * decision path.</p>
     *
     * <p>Every clause must hold, and a failed clause means DEFAULT BEHAVIOUR
     * with no log line at all, so a released position is byte-identical:</p>
     * <ol>
     * <li>our own spell, not a copy, our own card, in OUR OWN HAND - a wheel
     *     cast out of the graveyard (v64's escaped Timetwister) is the Breach
     *     route firing and is never refused;</li>
     * <li>the printed graveyard-shuffle shape, and it does not target;</li>
     * <li>stack empty, our own MAIN1/MAIN2, one opponent, and we can still win;</li>
     * <li>a TERMINAL of a graveyard route is among the cards of our own
     *     graveyard this spell would actually move;</li>
     * <li>no route can fire this turn - asked of a FRESH {@link CubeStormPlan}
     *     and a fresh {@link CubeBreachPlan}, whose only asymmetry with the live
     *     ones ({@code failedTurn} unset) can make them propose where the live
     *     plan declined, which RELEASES;</li>
     * <li>an ENGINE is own-visible where it could still be used - in play, or
     *     in our hand and castable within v61 H1's unchanged two land drops.</li>
     * </ol>
     *
     * <p>Clause 4 is checked BEFORE the two plan probes on purpose: on a board
     * with no terminal in our graveyard this predicate returns on pure zone
     * reads and runs no probe at all, which is why it cannot perturb any
     * position it does not refuse.</p>
     *
     * <p>Own hand, own battlefield, own graveyard and both public life totals
     * only. OUR OWN LIBRARY IS NEVER READ - not its contents, not its order,
     * not its size - and neither is our registered decklist; the opponent's
     * hidden zones are never touched and a face-down card is never identified.
     * The probes of clause 5 read exactly what {@code nextAction} already reads
     * on every pass, and every payment query inside them runs through
     * {@link CubeComboAi#probePayment}, which snapshots and restores memory,
     * mana-pool conversion state and ability actor/target state.</p> */
    public static boolean holdWheel(Player player, SpellAbility spell) {
        if (spell == null || !spell.isSpell() || spell.isCopied()) return false;
        Card host = spell.getHostCard();
        if (host == null || host.isFaceDown() || host.getOwner() != player
                || host.getController() != player || !host.isInZone(ZoneType.Hand)) return false;
        if (!graveyardShuffle(spell)) return false;
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (!game.getStack().isEmpty() || player.cantWin() || player.getOpponents().size() != 1
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))) return false;
        Card terminal = null;
        for (Card card : movedFromOwnGraveyard(player, spell))
            if (!card.isFaceDown() && HOLD_TERMINALS.contains(card.getName())) { terminal = card; break; }
        if (terminal == null) return false;
        if (new CubeStormPlan(player).nextAction() != null) return false;
        if (new CubeBreachPlan(player).nextAction() != null) return false;
        for (String name : HOLD_ENGINES) {
            Card engine = ownEngine(player, name);
            if (engine == null) continue;
            String reason = engine.isInZone(ZoneType.Battlefield) ? "engine-in-play"
                    : CubeComboAi.castableWithinTwoDrops(player, engine.getManaCost()) ? "engine-in-hand" : null;
            if (reason == null) continue;
            holdLine(player, "CUBE_STORM_HOLD reason=" + reason + " engine=" + token(engine.getName())
                    + " terminal=" + token(terminal.getName()));
            return true;
        }
        return false;
    }

    /** One {@code CUBE_STORM_HOLD} line per (seat, turn, phase), the v53/v61
     * {@code twinLine} budget reproduced here for the same reason v68's
     * {@code holdLine} reproduced it: {@code CubeComboAi} is not this
     * increment's file to restructure. The budget suppresses the LINE, never
     * the hold. Observability only: the identity stamp is compared, never
     * printed, and no decision reads any of it. There is deliberately NO
     * release line - a release IS the Default action and every preserved log
     * has to stay byte-identical. */
    private static void holdLine(Player player, String line) {
        var phases = player.getGame().getPhaseHandler();
        String stamp = System.identityHashCode(player) + ":" + phases.getTurn() + ":" + phases.getPhase();
        if (stamp.equals(HOLD_STAMP.get())) return;
        HOLD_STAMP.set(stamp);
        System.err.println(line);
    }

    public boolean owns(SpellAbility ability) { return ability == selected; }

    public boolean play(SpellAbility ability) {
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, ability, null,
                current -> new AiCostDecision(player, current, false));
        if (played) {
            if (ability.getHostCard().getName().equals(WILL)) attemptedWill = true;
            System.err.println("CUBE_STORM_PLAN played turn=" + turn + " card=" + ability.getHostCard().getName());
        } else {
            failedTurn = turn;
            System.err.println("CUBE_STORM_PLAN native-payment-failed turn=" + turn + " card=" + ability.getHostCard().getName());
        }
        return played;
    }

    public boolean waitingForOwnSpell() {
        var stack = player.getGame().getStack();
        if (selected == null || turn != player.getGame().getPhaseHandler().getTurn() || stack.isEmpty()) return false;
        var top = stack.peekAbility();
        return top != null && top.getActivatingPlayer() == player
                && (top.getHostCard() == selected.getHostCard() || top.getHostCard().getName().equals(TENDRILS));
    }
}
