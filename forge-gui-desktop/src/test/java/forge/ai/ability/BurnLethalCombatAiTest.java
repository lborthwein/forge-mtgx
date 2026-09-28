package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCombat;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Owner-friend report 2026-09-28T02-19-05: the player at 9 blocked Caves of Chaos Adventurer (5/3 trample) with
 * Walking Ballista, then removed Ballista's last counter to ping Oona's Prowler. With its blocker gone, the trampler
 * assigns all 5 to the player (CR 702.19e), so a kicked Burst Lightning (4) to the face was lethal; Forge AI shot Urza.
 * Forge's burn-to-face check (DamageAiBase.shouldTgtP) never counted the combat damage still to come.
 *
 * <p>Both fixes are behind the per-player option aiFixes0928 of the deciding AI (lane yardstick-0928): ON for the AI
 * here; the {@code optionOff...} tests pin the upstream choices with it off (the default).
 */
public class BurnLethalCombatAiTest extends AITest {

    private Player ai, opp;
    private Card adventurer, prowler, ballista, urza, burst;
    private Combat combat;

    private AiFixes.Counters counters;

    private Game setup(String attackerName) {
        return setup(attackerName, AiFixes.Mode.ON);
    }

    private Game setup(String attackerName, AiFixes.Mode mode) {
        Game game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();
        lobby.setAiFixes0928(mode);
        counters = AiFixes.count(lobby, game);
        opp.setLife(9, null);
        urza = addCard("Urza, Lord High Artificer", opp);
        ballista = addCard("Walking Ballista", opp);
        ballista.setCounters(CounterEnumType.P1P1, 1);
        adventurer = addCard(attackerName, ai);
        adventurer.setSickness(false);
        prowler = addCard("Oona's Prowler", ai);
        prowler.setSickness(false);
        for (int i = 0; i < 3; i++) {
            addCard("Swamp", ai);
        }
        addCard("Mountain", ai);
        addCard("Mountain", ai);
        burst = addCardToZone("Burst Lightning", ai, ZoneType.Hand);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);

        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        combat = new Combat(ai);
        combat.addAttacker(adventurer, opp);
        combat.addAttacker(prowler, opp);
        game.getPhaseHandler().setCombat(combat);
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, ai, false);
        combat.addBlocker(adventurer, ballista);
        combat.getBandOfAttacker(adventurer).setBlocked(true);
        combat.getBandOfAttacker(prowler).setBlocked(false);
        combat.orderBlockersForDamageAssignment();
        combat.orderAttackersForDamageAssignment();
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** The blocker leaves combat (Ballista removed its last counter and died). */
    private void blockerDies(Game game) {
        game.getAction().moveToGraveyard(ballista, null, null);
        combat.removeFromCombat(ballista);
        game.getAction().checkStateEffects(true);
    }

    private SpellAbility burstSa() {
        SpellAbility sa = burst.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    @Test
    public void tramplerWithBlockerGoneCountsForLethal() {
        Game game = setup("Caves of Chaos Adventurer");
        blockerDies(game);
        AssertJUnit.assertTrue(combat.isBlocked(adventurer));
        // 5 trample + 3 flying = 8 still to come
        AssertJUnit.assertEquals(1, ComputerUtilCombat.lifeThatWouldRemain(opp, combat, ai));
    }

    @Test
    public void nonTramplerWithBlockerGoneDealsNothing() {
        Game game = setup("Craw Wurm");
        blockerDies(game);
        // the blocked Craw Wurm deals no combat damage; only Oona's Prowler's 3
        AssertJUnit.assertEquals(6, ComputerUtilCombat.lifeThatWouldRemain(opp, combat, ai));
    }

    @Test
    public void burnGoesToTheFaceWhenCombatMakesItLethal() {
        Game game = setup("Caves of Chaos Adventurer");
        blockerDies(game);
        SpellAbility sa = burstSa();
        AiPlayDecision d = ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, d);
        AssertJUnit.assertSame("burn the player: the combat damage still to come makes it lethal", opp,
                sa.getTargets().getFirstTargetedPlayer());
    }

    @Test
    public void burstToTheFaceThenCombatDamageWins() {
        // The report's line, played out by the engine: kicked Burst to the face resolves first (the ping kills the
        // Prowler), then combat damage: the blocked trampler whose blocker is gone assigns all 5 to the player.
        Game game = setup("Caves of Chaos Adventurer");
        blockerDies(game);
        game.getAction().moveToGraveyard(prowler, null, null);
        combat.removeFromCombat(prowler);
        SpellAbility sa = burstSa();
        sa.addOptionalCost(OptionalCost.Kicker1);
        sa.getTargets().add(opp);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(5, opp.getLife());
        playUntilPhase(game, PhaseType.COMBAT_END);
        AssertJUnit.assertTrue("the player is dead after combat damage", opp.getLife() <= 0 || game.isGameOver());
    }

    @Test
    public void optionOffCountsTheBlockedNonTramplerAsUnblocked() {
        Game game = setup("Craw Wurm", AiFixes.Mode.OFF);
        blockerDies(game);
        // upstream: the blocked Craw Wurm (6) with no blocker left is counted as unblocked: 9 - 6 - 3 = 0
        AssertJUnit.assertEquals(0, ComputerUtilCombat.lifeThatWouldRemain(opp, combat, ai));
        AssertJUnit.assertEquals(0, counters.fired(AiFixes.Kind.BLOCKER_LEFT));
    }

    @Test
    public void optionOnIsTheDecidersNotTheSubjects() {
        // The prediction follows the deciding AI's option, not the attacked player's.
        Game game = setup("Craw Wurm", AiFixes.Mode.OFF);
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setAiFixes0928(AiFixes.Mode.ON);
        blockerDies(game);
        AssertJUnit.assertEquals(0, ComputerUtilCombat.lifeThatWouldRemain(opp, combat, ai));
        AssertJUnit.assertEquals(6, ComputerUtilCombat.lifeThatWouldRemain(opp, combat, opp));
    }

    @Test
    public void optionOffBurnShootsTheCreatureAsUpstream() {
        Game game = setup("Caves of Chaos Adventurer", AiFixes.Mode.OFF);
        blockerDies(game);
        SpellAbility sa = burstSa();
        ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa);
        AssertJUnit.assertNotSame("upstream: burn does not go to the face (9 - 4 >= 5)", opp,
                sa.getTargets().getFirstTargetedPlayer());
        AssertJUnit.assertEquals(0, counters.fired(AiFixes.Kind.BURN_FACE));
    }

    @Test
    public void shadowBurnDecidesAsOffAndCountsTheSpot() {
        Game game = setup("Caves of Chaos Adventurer", AiFixes.Mode.SHADOW);
        blockerDies(game);
        SpellAbility sa = burstSa();
        ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa);
        AssertJUnit.assertNotSame(opp, sa.getTargets().getFirstTargetedPlayer());
        AssertJUnit.assertTrue("the lethal-with-combat spot is counted", counters.fired(AiFixes.Kind.BURN_FACE) >= 1);
        AssertJUnit.assertTrue(counters.changed(AiFixes.Kind.BURN_FACE) >= 1);
    }
}
