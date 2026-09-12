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
     * {@code finisher-untargetable} or {@code no-resource-action}. The
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

    private SpellAbility crackLotus() {
        Card lotus = find("Black Lotus", ZoneType.Battlefield);
        if (lotus == null) lotus = find("Lotus Petal", ZoneType.Battlefield);
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

        for (String name : ROCKS) {
            SpellAbility rock = spell(name);
            if (rock != null) return select(rock);
        }
        // Sacrifice before Will so the same known Lotus can be replayed.
        // Native legality forbids the activation under Null Rod, etc.
        if ((will != null || attemptedWill) && (find("Black Lotus", ZoneType.Battlefield) != null
                || find("Lotus Petal", ZoneType.Battlefield) != null)) {
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
