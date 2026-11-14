package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-copycast-1114 ({@link GameCopier#setCopyTriggers}, search key copyTriggers): a triggered ability waiting on
 * the stack. Off (today) {@link GameCopier#stackUnsupported} refuses the stack ("trigger") and the look-ahead leaves the
 * decision to the default; on, the copy rebuilds the trigger from its host's trigger at the same position, with its
 * controller, triggering objects and targets mapped, and the decision is searched. A trigger that cannot be rebuilt
 * (host gone, not one of the host's own triggers, a triggering object of an unmapped kind) is still refused.
 */
public class CopierTriggersTest extends SimulationTest {

    /** Seat a (index 0) at its first main phase with lands and a burn spell in hand; libraries for both seats. */
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
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** Pending triggers go on the stack, as at the next priority. */
    private static void triggersToStack(final Game game) {
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    private static Card enter(final Game game, final Card c) {
        final Card moved = game.getAction().moveToPlay(c, null, AbilityKey.newMap());
        triggersToStack(game);
        return moved;
    }

    private static Game copy(final Game g, final boolean key) {
        final GameCopier copier = new GameCopier(g, true);
        copier.setCopyStack(true);
        copier.setCopyTriggers(key);
        return copier.makeCopy();
    }

    private static LookaheadSearch.Config config(final boolean key) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 2;
        c.threads = 1;
        c.seed = 748_600_900L;
        c.stack = true;
        c.copyTriggers = key;
        return c;
    }

    private static List<SpellAbility> given(final Player a) {
        final List<SpellAbility> g = new ArrayList<>();
        g.add(null);
        g.addAll(new SpellAbilityPicker(a).getCandidateSpellsAndAbilities());
        return g;
    }

    /** The opponent's "whenever another creature enters" trigger waits on the stack; our seat may respond. */
    @Test(timeOut = 600000)
    public void aResponseWithAnOpponentsTriggerOnTheStackIsCopiedAndSearched() {
        final Game game = board();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        final Card warden = addCard("Soul Warden", b);
        game.getAction().checkStateEffects(true);
        final Card bears = enter(game, addCardToZone("Grizzly Bears", a, ZoneType.Hand));
        Assert.assertEquals(game.getStack().size(), 1, "the life-gain trigger is on the stack");
        final SpellAbility top = game.getStack().peekAbility();
        Assert.assertTrue(top instanceof WrappedAbility && top.getActivatingPlayer() == b);
        Assert.assertEquals(GameCopier.stackUnsupported(game), "trigger", "today: refused");
        Assert.assertEquals(GameCopier.stackUnsupported(game, false, false), "trigger");
        Assert.assertNull(GameCopier.stackUnsupported(game, false, true), "with the key: accepted");
        try {
            copy(game, false);
            Assert.fail("off: the copier cannot rebuild a trigger on the stack");
        } catch (RuntimeException e) {
            // expected: callers never copy a stack stackUnsupported refuses
        }
        final Game cp = copy(game, true);
        final Player ca = cp.getPlayers().get(0), cb = cp.getPlayers().get(1);
        Assert.assertEquals(cp.getStack().size(), 1);
        final SpellAbility ctop = cp.getStack().peekAbility();
        Assert.assertTrue(ctop instanceof WrappedAbility, "a wrapped trigger in the copy");
        Assert.assertNotSame(ctop, top);
        Assert.assertSame(ctop.getHostCard(), cp.findById(warden.getId()), "its host is the copy's card");
        Assert.assertSame(ctop.getActivatingPlayer(), cb, "its controller is the copy's opponent");
        Assert.assertSame(((WrappedAbility) ctop).getTrigger().getHostCard().getGame(), cp);
        final Object tc = ctop.getTriggeringObject(AbilityKey.Card);
        Assert.assertTrue(tc instanceof Card && ((Card) tc).getGame() == cp && ((Card) tc).getId() == bears.getId(),
                "the creature that entered, as the copy's card: " + tc);
        for (Object v : ctop.getTriggeringObjects().values()) {
            Assert.assertFalse(v instanceof Card && ((Card) v).getGame() == game, "no live card in the copy's trigger");
            Assert.assertFalse(v instanceof Player && ((Player) v).getGame() == game, "no live player in the copy's trigger");
        }
        // it resolves in the copy only
        final int life = b.getLife();
        cp.getStack().resolveStack();
        Assert.assertEquals(cb.getLife(), life + 1, "the copy's opponent gained the life");
        Assert.assertEquals(b.getLife(), life, "the live game is untouched");
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertSame(game.getStack().peekAbility(), top);
        Assert.assertEquals(ca.getLife(), a.getLife());

        // the look-ahead: off the decision is refused ("trigger"), on it is searched
        final List<SpellAbility> g = given(a);
        Assert.assertTrue(g.size() >= 2, "pass and the burn spell: " + g);
        final String before = LookaheadSearch.fingerprint(game);
        final LookaheadSearch off = new LookaheadSearch(config(false));
        try {
            final LookaheadSearch.GivenResult r = off.decideGiven(game, a, g, 4);
            Assert.assertEquals(r.outcome, "stack");
            Assert.assertEquals(r.givenIndex.length, 0);
            Assert.assertEquals(off.getStats().stackWhy.get("trigger"), Long.valueOf(1L));
            Assert.assertEquals(off.getStats().stackSearched, 0);
        } finally {
            off.shutdown();
        }
        final LookaheadSearch on = new LookaheadSearch(config(true));
        try {
            final LookaheadSearch.GivenResult r = on.decideGiven(game, a, g, 4);
            Assert.assertNotEquals(r.outcome, "stack");
            Assert.assertTrue(r.givenIndex.length >= 2, "searched: " + r.outcome);
            Assert.assertEquals(on.getStats().stackSearched, 1);
            Assert.assertTrue(on.getStats().stackWhy.isEmpty(), String.valueOf(on.getStats().stackWhy));
            // no play-out failed on the copy itself (Forge AI may still decline to aim the burn spell in a world)
            final long[] pf = on.getStats().playoutFailures;
            Assert.assertEquals(pf[4] + pf[5], 0, "copy_error / exception: " + on.getStats().playoutFailureCands + " "
                    + on.getStats().playoutLastCopyFailure);
            Assert.assertEquals(LookaheadSearch.Stats.PLAYOUT_FAILURE_CLASSES[4], "copy_error");
            Assert.assertEquals(LookaheadSearch.Stats.PLAYOUT_FAILURE_CLASSES[5], "exception");
        } finally {
            on.shutdown();
        }
        Assert.assertEquals(LookaheadSearch.fingerprint(game), before, "the live game is only read");
    }

    /** Our own targeted enters trigger waits on the stack: its target is mapped and it resolves in the copy. */
    @Test(timeOut = 600000)
    public void ourOwnTargetedTriggerKeepsItsTargetInTheCopy() {
        final Game game = board();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        final Card bears = addCard("Grizzly Bears", b);
        game.getAction().checkStateEffects(true);
        final Card kavu = enter(game, addCardToZone("Flametongue Kavu", a, ZoneType.Hand));
        Assert.assertEquals(game.getStack().size(), 1, "the damage trigger is on the stack");
        final WrappedAbility top = (WrappedAbility) game.getStack().peekAbility();
        Assert.assertSame(top.getActivatingPlayer(), a);
        final Card target = top.getWrappedAbility().getTargets().getFirstTargetedCard();
        Assert.assertNotNull(target, "the trigger has its target");
        Assert.assertNull(GameCopier.stackUnsupported(game, false, true));
        final Game cp = copy(game, true);
        final SpellAbility ctop = cp.getStack().peekAbility();
        Assert.assertSame(ctop.getHostCard(), cp.findById(kavu.getId()));
        Assert.assertSame(ctop.getActivatingPlayer(), cp.getPlayers().get(0));
        final Card ctarget = ((WrappedAbility) ctop).getWrappedAbility().getTargets().getFirstTargetedCard();
        Assert.assertNotNull(ctarget);
        Assert.assertSame(ctarget, cp.findById(target.getId()), "the copy's card of the same id");
        Assert.assertNotSame(ctarget, target);
        cp.getStack().resolveStack();
        cp.getAction().checkStateEffects(true);
        Assert.assertFalse(cp.findById(target.getId()).isInZone(ZoneType.Battlefield), "4 damage in the copy");
        Assert.assertTrue(game.findById(target.getId()).isInZone(ZoneType.Battlefield), "the live target stands");
        Assert.assertTrue(target == bears || target == kavu);
        // the look-ahead searches our own response window
        final LookaheadSearch on = new LookaheadSearch(config(true));
        try {
            final LookaheadSearch.GivenResult r = on.decideGiven(game, a, given(a), 4);
            Assert.assertNotEquals(r.outcome, "stack");
            Assert.assertEquals(on.getStats().stackSearched, 1);
        } finally {
            on.shutdown();
        }
    }

    /** A spell with a cast trigger above it: the trigger's triggering spell is the copy's stack entry. */
    @Test(timeOut = 600000)
    public void aCastTriggerAboveItsSpellRefersToTheCopiedSpell() {
        final Game game = board();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        addCard("Young Pyromancer", a);
        game.getAction().checkStateEffects(true);
        final Card bolt = a.getCardsIn(ZoneType.Hand).getFirst();
        final SpellAbility boltSa = bolt.getFirstSpellAbility();
        boltSa.setActivatingPlayer(a);
        boltSa.getTargets().add(b);
        game.getStackZone().add(bolt);
        game.getStack().add(boltSa);
        triggersToStack(game);
        Assert.assertEquals(game.getStack().size(), 2, "the spell and the cast trigger above it");
        Assert.assertTrue(game.getStack().peekAbility() instanceof WrappedAbility);
        Assert.assertEquals(GameCopier.stackUnsupported(game), "trigger");
        Assert.assertNull(GameCopier.stackUnsupported(game, false, true));
        final GameCopier copier = new GameCopier(game, true);
        copier.setCopyStack(true);
        copier.setCopyTriggers(true);
        final Game cp = copier.makeCopy();
        Assert.assertEquals(cp.getStack().size(), 2);
        final SpellAbility ctop = cp.getStack().peekAbility();
        Assert.assertTrue(ctop instanceof WrappedAbility);
        final SpellAbility cbolt = (SpellAbility) copier.findWithStack(boltSa);
        Assert.assertNotNull(cbolt);
        for (Map.Entry<AbilityKey, Object> e : ctop.getTriggeringObjects().entrySet()) {
            final Object v = e.getValue();
            if (v instanceof SpellAbility) {
                Assert.assertSame(((SpellAbility) v).getHostCard().getGame(), cp, e.getKey() + " is of the copy");
            }
            if (v instanceof SpellAbilityStackInstance) {
                Assert.assertSame(((SpellAbilityStackInstance) v).getSpellAbility(), cbolt, "the copy's stack entry");
            }
            Assert.assertFalse(v instanceof Card && ((Card) v).getGame() == game, e.getKey() + " holds a live card");
        }
        final int tokens = cp.getPlayers().get(0).getCardsIn(ZoneType.Battlefield).size();
        final int life = cp.getPlayers().get(1).getLife();
        cp.getStack().resolveStack();
        Assert.assertEquals(cp.getPlayers().get(0).getCardsIn(ZoneType.Battlefield).size(), tokens + 1, "the trigger's token");
        cp.getStack().resolveStack();
        Assert.assertEquals(cp.getPlayers().get(1).getLife(), life - 3, "then the spell");
        Assert.assertEquals(game.getStack().size(), 2, "the live stack is untouched");
        Assert.assertEquals(b.getLife(), life);
    }

    /** The host has left the game while its trigger waits (as a token that died has): refused, as today. */
    @Test(timeOut = 600000)
    public void aTriggerWhoseHostIsGoneIsRefused() {
        final Game game = board();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        final Card warden = addCard("Soul Warden", b);
        game.getAction().checkStateEffects(true);
        enter(game, addCardToZone("Grizzly Bears", a, ZoneType.Hand));
        Assert.assertEquals(game.getStack().size(), 1, "the trigger is on the stack");
        Assert.assertNull(GameCopier.stackUnsupported(game, false, true));
        game.getAction().ceaseToExist(warden, true);
        Assert.assertNull(game.findById(warden.getId()), "the host exists in no zone");
        Assert.assertEquals(GameCopier.stackUnsupported(game), "trigger");
        Assert.assertEquals(GameCopier.stackUnsupported(game, false, true), "trigger-host");
        final LookaheadSearch on = new LookaheadSearch(config(true));
        try {
            final LookaheadSearch.GivenResult r = on.decideGiven(game, a, given(a), 4);
            Assert.assertEquals(r.outcome, "stack");
            Assert.assertEquals(on.getStats().stackWhy.get("trigger-host"), Long.valueOf(1L));
            Assert.assertEquals(on.getStats().stackSearched, 0);
        } finally {
            on.shutdown();
        }
    }

    /** A trigger that is not one of its host's own (a delayed trigger) and an unmapped triggering object: refused. */
    @Test(timeOut = 600000)
    public void aDelayedTriggerAndAnUnmappedTriggeringObjectAreRefused() {
        final Game game = board();
        final Player a = game.getPlayers().get(0), b = game.getPlayers().get(1);
        final Card warden = addCard("Soul Warden", b);
        game.getAction().checkStateEffects(true);
        // a delayed trigger: built for its host, never one of the host's own triggers
        final Trigger delayed = forge.game.trigger.TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ End of Turn | TriggerDescription$ At the beginning of the end step, draw a card.", warden, true);
        final SpellAbility draw = forge.game.ability.AbilityFactory.getAbility("DB$ Draw | Defined$ You", warden);
        draw.setActivatingPlayer(b);
        delayed.setOverridingAbility(draw);
        game.getTriggerHandler().registerDelayedTrigger(delayed);
        Assert.assertTrue(LookaheadSearch.triggerIndex(warden, delayed) < 0);
        game.getStack().dangerouslyPushCopy(new WrappedAbility(delayed, draw, null));
        Assert.assertEquals(GameCopier.stackUnsupported(game, false, true), "trigger-kind");
        game.getStack().clear();

        enter(game, addCardToZone("Grizzly Bears", a, ZoneType.Hand));
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertNull(GameCopier.stackUnsupported(game, false, true));
        game.getStack().peekAbility().setTriggeringObject(AbilityKey.DamageMap, new java.util.HashMap<String, Integer>());
        Assert.assertEquals(GameCopier.stackUnsupported(game, false, true), "trigger-object");
        // the resolving-top form (the card scorer's check) refuses the same way, and without the key as today
        Assert.assertEquals(GameCopier.stackUnsupported(game, true, true), "trigger-object");
        Assert.assertEquals(GameCopier.stackUnsupported(game, true, false), "trigger");
        Assert.assertEquals(ChoiceApply.unsupported(game, null), "trigger");
    }

    @Test
    public void theKeyIsOffByDefaultAndWrittenOnlyWhenOn() {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        Assert.assertFalse(c.copyTriggers);
        Assert.assertFalse(c.toJson().has("copyTriggers"));
        c.copyTriggers = true;
        Assert.assertTrue(c.toJson().get("copyTriggers").getAsBoolean());
        final com.google.gson.JsonObject base = BvwStep3PlayoutTest.block(1, "").getAsJsonObject("rlsearch");
        Assert.assertFalse(forge.bench.rl.RlSearch.Config.parse(base).toJson().has("copyTriggers"));
        final forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(
                BvwStep3PlayoutTest.block(1, "\"copyTriggers\":true").getAsJsonObject("rlsearch"));
        Assert.assertTrue(on.copyTriggers && on.toJson().get("copyTriggers").getAsBoolean());
        final String live = "server=127.0.0.1:1,cardIndex=/x,policySha=" + "0".repeat(64);
        Assert.assertFalse(forge.bench.rl.RlLiveSeat.Spec.parse(live).search.copyTriggers);
        Assert.assertTrue(forge.bench.rl.RlLiveSeat.Spec.parse(live + ",copyTriggers=1").search.copyTriggers);
    }
}
