package forge.ai.simulation;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.staticability.StaticAbility;

/** A generated rule-defined ability, with no dependency on a named card. */
public class OffBattlefieldLimitsTest extends SimulationTest {
    private static SpellAbility ability(Card card) {
        for (SpellAbility sa : card.getSpellAbilities()) if (sa.isActivatedAbility()) {
            sa.setActivatingPlayer(card.getOwner());
            return sa;
        }
        throw new AssertionError("missing activated ability");
    }
    private static StaticAbility limitedStatic(Card card) {
        for (StaticAbility st : card.getStaticAbilities()) if (st.hasParam("MayPlayLimit")) return st;
        throw new AssertionError("missing may-play static");
    }
    private Game source(ZoneType zone) {
        Game game = initAndCreateGame();
        Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        forge.item.PaperCard paper = new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of(
                "Name:Test off-battlefield limit", "ManaCost:0", "Types:Artifact", "Oracle:",
                "A:AB$ GainLife | Cost$ 0 | LifeAmount$ 1 | ActivationZone$ " + zone.name()
                + " | ActivationLimit$ 1 | SpellDescription$ Gain 1 life. Activate only once each turn.",
                "S:Mode$ Continuous | EffectZone$ " + zone.name()
                + " | Affected$ Card.YouOwn | AffectedZone$ Graveyard | MayPlay$ True | MayPlayLimit$ 1")),
                "", forge.card.CardRarity.Common);
        Card card = Card.fromPaperCard(paper, me);
        card.setGameTimestamp(game.getNextTimestamp());
        me.getZone(zone).add(card);
        game.getAction().checkStateEffects(true);
        Assert.assertTrue(ability(card).canPlay(), "Unused ability is legal in " + zone);
        card.addAbilityActivated(ability(card));
        limitedStatic(card).incMayPlayTurn();
        Assert.assertEquals(ability(card).getActivationsThisTurn(), 1);
        Assert.assertFalse(ability(card).canPlay(), "The turn's use was spent");
        return game;
    }
    @Test public void handLimitsReproduceOffAndRepairOn() { reproduce(ZoneType.Hand); }
    @Test public void graveyardLimitsReproduceOffAndRepairOn() { reproduce(ZoneType.Graveyard); }
    @Test public void exileLimitsReproduceOffAndRepairOn() { reproduce(ZoneType.Exile); }
    @Test public void commandLimitsReproduceOffAndRepairOn() { reproduce(ZoneType.Command); }
    private void reproduce(ZoneType zone) {
        Game live = source(zone);
        GameCopier copier = new GameCopier(live, true);
        copier.setTurnLimits(true); // published p9 enabled
        Game old = copier.makeCopy();
        Card copy = old.getPlayers().get(1).getCardsIn(zone).get(0);
        Assert.assertEquals(ability(copy).getActivationsThisTurn(), 0);
        Assert.assertEquals(limitedStatic(copy).getMayPlayTurn(), 0);
        Assert.assertTrue(ability(copy).canPlay(), "The copy illegally permits the second activation");
        GameCopier repair = new GameCopier(live, true);
        repair.setTurnLimits(true);
        repair.setOffBattlefieldLimits(true);
        Game fixed = repair.makeCopy();
        assertSpent(fixed, zone);
        Assert.assertTrue(fixed.offBattlefieldLimits);
        Game child = new GameCopier(fixed, true).makeCopy();
        assertSpent(child, zone);
        Assert.assertTrue(child.offBattlefieldLimits);
        assertSpent(live, zone);
        Assert.assertFalse(live.offBattlefieldLimits);
    }
    private static void assertSpent(Game game, ZoneType zone) {
        Card c = game.getPlayers().get(1).getCardsIn(zone).get(0);
        Assert.assertEquals(ability(c).getActivationsThisTurn(), 1);
        Assert.assertEquals(limitedStatic(c).getMayPlayTurn(), 1);
        Assert.assertFalse(ability(c).canPlay());
    }
    @Test public void battlefieldIsNotCountedTwice() {
        Game live = source(ZoneType.Battlefield);
        for (boolean on : new boolean[]{false, true}) {
            GameCopier copier = new GameCopier(live, true);
            copier.setTurnLimits(true);
            copier.setOffBattlefieldLimits(on);
            assertSpent(copier.makeCopy(), ZoneType.Battlefield);
        }
    }
    @Test public void searchAndSolverKeysAreDefaultOffAndParseOn() {
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        forge.bench.rl.RlSearch.Config off = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertFalse(off.offBattlefieldLimits);
        Assert.assertFalse(off.toJson().has("offBattlefieldLimits"));
        spec.addProperty("offBattlefieldLimits", true);
        forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertTrue(on.offBattlefieldLimits);
        Assert.assertTrue(on.toJson().get("offBattlefieldLimits").getAsBoolean());
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).offBattlefieldLimits);
        Assert.assertTrue(ForcedWinSolver.Config.fromProps(k -> "offBattlefieldLimits".equals(k) ? "true" : null).offBattlefieldLimits);
    }
}
