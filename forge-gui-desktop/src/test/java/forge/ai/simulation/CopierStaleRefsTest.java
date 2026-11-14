package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-copycast-1114 ({@link GameCopier#setCopyStaleRefs}, search key copyStaleRefs): a stack entry that still refers
 * to an object the game no longer holds. Off (today) the copy fails ("Couldn't map <card>", "stack copy: paid <token> ...
 * not in the copy": the look-ahead's copy_error play-outs); on, the stale target or the gone paid token is left out and
 * the copy is made.
 */
public class CopierStaleRefsTest extends SimulationTest {

    static SpellAbility ability(final Card c, final String text) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.getDescription().contains(text)) {
                return sa;
            }
        }
        throw new AssertionError("no ability '" + text + "' on " + c);
    }

    static Game copy(final Game g, final boolean key) {
        final GameCopier copier = new GameCopier(g, true);
        copier.setCopyStack(true);
        copier.setCopyStaleRefs(key);
        return copier.makeCopy();
    }

    @Test
    public void aTargetThatLeftTheGameIsLeftOutOfTheCopiedEntry() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1), opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card pinger = addCard("Prodigal Sorcerer", p);
        final Card servo = addToken("c_1_1_a_servo", opp);
        game.getAction().checkStateEffects(true);
        final SpellAbility ping = ability(pinger, "deals 1 damage");
        ping.setActivatingPlayer(p);
        ping.getTargets().add(servo);
        game.getStack().add(ping);
        Assert.assertEquals(copy(game, false).getStack().peekAbility().getTargets().size(), 1, "a live target copies as before");
        // the targeted token is sacrificed in response: it exists in no zone any more
        final java.util.Map<forge.game.ability.AbilityKey, Object> params = forge.game.ability.AbilityKey.newMap();
        params.put(forge.game.ability.AbilityKey.LastStateBattlefield, game.copyLastStateBattlefield());
        game.getAction().sacrifice(new CardCollection(servo), null, false, params);
        game.getAction().checkStateEffects(true);
        Assert.assertNull(game.findById(servo.getId()));
        try {
            copy(game, false);
            Assert.fail("off: the copy of a stack with a gone target fails");
        } catch (RuntimeException e) {
            Assert.assertTrue(String.valueOf(e.getMessage()).startsWith("Couldn't map"), e.toString());
        }
        final Game cp = copy(game, true);
        Assert.assertEquals(cp.getStack().size(), 1, "on: the stack is copied");
        final SpellAbility csa = cp.getStack().peekAbility();
        Assert.assertEquals(csa.getHostCard().getId(), pinger.getId());
        Assert.assertEquals(csa.getTargets().size(), 0, "the gone target is left out");
        // the live stack entry is untouched: it still names its (gone) target
        Assert.assertEquals(game.getStack().peekAbility().getTargets().size(), 1);
    }

    /** A target that only changed zones still maps (by its id, to the card's current object), key off and on. */
    @Test
    public void aTargetThatChangedZonesMapsAsBefore() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1), opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card pinger = addCard("Prodigal Sorcerer", p);
        final Card bears = addCard("Grizzly Bears", opp);
        game.getAction().checkStateEffects(true);
        final SpellAbility ping = ability(pinger, "deals 1 damage");
        ping.setActivatingPlayer(p);
        ping.getTargets().add(bears);
        game.getStack().add(ping);
        game.getAction().moveToGraveyard(bears, null);
        Assert.assertTrue(game.findById(bears.getId()).isInZone(ZoneType.Graveyard));
        for (boolean key : new boolean[] {false, true}) {
            final SpellAbility csa = copy(game, key).getStack().peekAbility();
            Assert.assertEquals(csa.getTargets().size(), 1, "key " + key);
            Assert.assertEquals(csa.getTargets().getFirstTargetedCard().getId(), bears.getId());
        }
    }

    @Test
    public void aLiveTargetIsNeverSkippedWithTheKey() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1), opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card pinger = addCard("Prodigal Sorcerer", p);
        final Card bears = addCard("Grizzly Bears", opp);
        game.getAction().checkStateEffects(true);
        final SpellAbility ping = ability(pinger, "deals 1 damage");
        ping.setActivatingPlayer(p);
        ping.getTargets().add(bears);
        game.getStack().add(ping);
        final Game cp = copy(game, true);
        Assert.assertEquals(cp.getStack().peekAbility().getTargets().size(), 1);
        Assert.assertEquals(cp.getStack().peekAbility().getTargets().getFirstTargetedCard().getId(), bears.getId());
        Assert.assertNotSame(cp.getStack().peekAbility().getTargets().getFirstTargetedCard(), bears);
    }

    @Test
    public void aPaidTokenThatCeasedToExistIsLeftOutOfThePaidList() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card foundry = addCard("Retrofitter Foundry", p);
        final Card servo = addToken("c_1_1_a_servo", p);
        game.getAction().checkStateEffects(true);
        final SpellAbility make = ability(foundry, "Thopter");
        make.setActivatingPlayer(p);
        make.addCostToHashList(servo, "Sacrificed", true);
        game.getStack().add(make);
        final java.util.Map<forge.game.ability.AbilityKey, Object> params = forge.game.ability.AbilityKey.newMap();
        params.put(forge.game.ability.AbilityKey.LastStateBattlefield, game.copyLastStateBattlefield());
        game.getAction().sacrifice(new CardCollection(servo), make, false, params);
        game.getAction().checkStateEffects(true);
        Assert.assertNull(game.findById(servo.getId()), "a sacrificed token exists in no zone");
        try {
            copy(game, false);
            Assert.fail("off: the copy fails on the gone paid token");
        } catch (RuntimeException e) {
            Assert.assertTrue(String.valueOf(e.getMessage()).contains("not in the copy"), e.toString());
        }
        final Game cp = copy(game, true);
        Assert.assertEquals(cp.getStack().size(), 1);
        Assert.assertEquals(cp.getStack().peekAbility().getHostCard().getId(), foundry.getId());
    }

    @Test
    public void theKeyIsOffByDefaultAndWrittenOnlyWhenOn() {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        Assert.assertFalse(c.copyStaleRefs);
        Assert.assertFalse(c.toJson().has("copyStaleRefs"));
        c.copyStaleRefs = true;
        Assert.assertTrue(c.toJson().get("copyStaleRefs").getAsBoolean());
        final com.google.gson.JsonObject base = BvwStep3PlayoutTest.block(1, "").getAsJsonObject("rlsearch");
        Assert.assertFalse(forge.bench.rl.RlSearch.Config.parse(base).toJson().has("copyStaleRefs"));
        final forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(
                BvwStep3PlayoutTest.block(1, "\"copyStaleRefs\":true").getAsJsonObject("rlsearch"));
        Assert.assertTrue(on.copyStaleRefs && on.toJson().get("copyStaleRefs").getAsBoolean());
        final String live = "server=127.0.0.1:1,cardIndex=/x,policySha=" + "0".repeat(64);
        Assert.assertFalse(forge.bench.rl.RlLiveSeat.Spec.parse(live).search.copyStaleRefs);
        Assert.assertTrue(forge.bench.rl.RlLiveSeat.Spec.parse(live + ",copyStaleRefs=1").search.copyStaleRefs);
    }
}
