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

    public CubeStormPlan(Player player) { this.player = player; }

    private Card find(String name, ZoneType zone) {
        for (Card card : player.getCardsIn(zone))
            if (!card.isFaceDown() && name.equals(card.getName())) return card;
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
                || player.getOpponents().size() != 1) return null;
        Player opponent = player.getOpponents().get(0);
        Card will = find(WILL, ZoneType.Hand);
        Card finisher = find(TENDRILS, ZoneType.Hand);
        if (finisher == null) finisher = find(TENDRILS, ZoneType.Graveyard);
        if (attemptedWill && Boolean.getBoolean("forge.bench.comboTrace"))
            System.err.println("CUBE_STORM_STATE turn=" + turn + " phase=" + phase.getPhase() + " storm="
                    + game.getStack().getSpellsCastThisTurn().size() + " hand=" + player.getCardsIn(ZoneType.Hand)
                    + " manaTotal=" + player.getManaPool().totalMana()
                    + " blue=" + player.getManaPool().getAmountOfColor(MagicColor.BLUE)
                    + " black=" + player.getManaPool().getAmountOfColor(MagicColor.BLACK)
                    + " finisher=" + (finisher == null ? "absent" : finisher.getZone()));
        if (finisher == null || will == null && !attemptedWill || !opponent.canLoseLife()) return null;
        // Do not commit a storm resource plan toward an untargetable finish.
        if (finisher.getSpellAbilities().stream().noneMatch(a -> a.copy(player).canTarget(opponent))) return null;
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
        return null;
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
