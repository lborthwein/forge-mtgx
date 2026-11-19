package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.bench.rl.RlLiveSeat;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-copycast-1114, follow-ups of the copy-side engine review (state-machines, 2026-11-15): (1) the pile planner's
 * copier takes the seat's copy-side keys; (2) a rebuilt waiting trigger is matched by its definition, not its position;
 * (3) the command-zone refusal looks at the game's current object too; (4) the live copy-side keys parse strictly.
 */
public class CopySideReviewTest extends SimulationTest {

    private Game board() {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
            for (int i = 0; i < 4; i++) {
                addCard("Mountain", p);
            }
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static void triggersToStack(final Game game) {
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    /** A waiting "another creature enters" trigger of the opponent's Soul Warden. */
    private Game wardenTrigger() {
        final Game game = board();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        addCard("Soul Warden", b);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToPlay(addCardToZone("Grizzly Bears", a, ZoneType.Hand), null, AbilityKey.newMap());
        triggersToStack(game);
        Assert.assertEquals(game.getStack().size(), 1, "the trigger is on the stack");
        return game;
    }

    private static ChoiceApply.Frames frames(final boolean triggers) {
        return new ChoiceApply.Frames() {
            @Override
            public Object onCopy(final Game copy, final Player me) {
                return null;
            }

            @Override
            public byte[] capture(final Object ctx, final Game g, final Player me) {
                return new byte[0];
            }

            @Override
            public boolean copyTriggers() {
                return triggers;
            }
        };
    }

    /** (1) With the seat's frames the pile planner's copy holds the waiting trigger, as the scorer's other copies do. */
    @Test(timeOut = 600000)
    public void thePilePlannersCopierTakesTheSeatsCopySideKeys() {
        final Game game = wardenTrigger();
        final Game on = ChoiceApply.pileCopier(game, frames(true)).makeCopy();
        Assert.assertEquals(on.getStack().size(), 1, "the trigger is rebuilt in the pile copy");
        Assert.assertTrue(on.getStack().peekAbility() instanceof WrappedAbility);
    }

    /** (2) The copy's trigger is found by its definition; a host without that definition gives none (no wrong binding). */
    @Test(timeOut = 600000)
    public void aRebuiltTriggerIsMatchedByItsDefinitionNotItsPosition() {
        final Game game = wardenTrigger();
        final WrappedAbility w = (WrappedAbility) game.getStack().peekAbility();
        final Trigger lt = w.getTrigger();
        final Game cp = ChoiceApply.pileCopier(game, frames(true)).makeCopy();
        final Card copyHost = cp.findById(w.getHostCard().getId());
        final Trigger t = GameCopier.sameTrigger(w.getHostCard(), lt, copyHost);
        Assert.assertNotNull(t);
        Assert.assertEquals(t.getMode(), lt.getMode());
        Assert.assertEquals(t.getMapParams(), lt.getMapParams());
        // a card whose first trigger has the same mode but another definition is not a match (position would bind it)
        final Card kavu = addCardToZone("Flametongue Kavu", game.getPlayers().get(0), ZoneType.Hand);
        Assert.assertEquals(kavu.getTriggers().iterator().next().getMode(), lt.getMode(), "same mode, first in its list");
        Assert.assertNull(GameCopier.sameTrigger(w.getHostCard(), lt, kavu));
    }

    /** (3) The host moved to the command zone after its trigger went on the stack: refused on the current object. */
    @Test(timeOut = 600000)
    public void aHostNowInTheCommandZoneIsRefusedOnItsCurrentObject() {
        final Game game = wardenTrigger();
        final WrappedAbility w = (WrappedAbility) game.getStack().peekAbility();
        Assert.assertNull(GameCopier.stackUnsupported(game, false, true));
        final Card warden = game.findById(w.getHostCard().getId());
        game.getAction().moveTo(ZoneType.Command, warden, null, AbilityKey.newMap());
        final Card now = game.findById(w.getHostCard().getId());
        Assert.assertNotNull(now);
        Assert.assertTrue(now.isInZone(ZoneType.Command), "the card is in the command zone now");
        Assert.assertEquals(GameCopier.stackUnsupported(game, false, true), "trigger-host");
    }

    /** (4) The live copy-side keys: 1/true/on/yes on, 0/false/off/no off, anything else refuses the spec. */
    @Test(timeOut = 600000)
    public void theLiveCopySideKeysParseStrictly() {
        final String base = "server=127.0.0.1:1,policySha=" + "0".repeat(64) + ",cardIndex=card-index.tsv,worlds=8";
        for (String k : new String[] {"copyCast", "copyDungeon", "copyStaleRefs", "copyTriggers"}) {
            for (String v : new String[] {"1", "true", "on", "yes"}) {
                Assert.assertTrue(flag(RlLiveSeat.Spec.parse(base + "," + k + "=" + v), k), k + "=" + v);
            }
            for (String v : new String[] {"0", "false", "off", "no"}) {
                Assert.assertFalse(flag(RlLiveSeat.Spec.parse(base + "," + k + "=" + v), k), k + "=" + v);
            }
            for (String v : new String[] {"maybe", "2", "TRUE", "enabled"}) {
                try {
                    RlLiveSeat.Spec.parse(base + "," + k + "=" + v);
                    Assert.fail(k + "=" + v + " should be refused");
                } catch (IllegalArgumentException expected) {
                    Assert.assertTrue(expected.getMessage().contains(k), expected.getMessage());
                }
            }
            Assert.assertFalse(flag(RlLiveSeat.Spec.parse(base), k), k + " absent = off");
        }
    }

    private static boolean flag(final RlLiveSeat.Spec s, final String k) {
        switch (k) {
            case "copyCast": return s.search.copyCast;
            case "copyDungeon": return s.search.copyDungeon;
            case "copyStaleRefs": return s.search.copyStaleRefs;
            default: return s.search.copyTriggers;
        }
    }
}
