package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-copycast-1114 ({@link LookaheadSearch.Config#copyDungeon}): a dungeon card (the initiative's) in the search's
 * copies. Off (today): no copy holds the dungeon, so the card scorer cannot rebuild a room's trigger ("tutor copy:
 * trigger host ... missing") and a venture in a copy starts the dungeon again at its first room. On: the copy holds
 * the dungeon in its room, the room's trigger is rebuilt from it, and a venture in the copy goes on to a next room.
 */
public class CopierDungeonTest extends SimulationTest {

    static final String FRAME = "[state]\nturn=5\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\n"
            + "humanlandsplayed=1\nailandsplayed=0\naihand=\nailibrary=" + ForcedWinSolverTest.LIB20
            + "\naigraveyard=\naiexile=\naibattlefield=Forest;Forest\n"
            + "humanhand=Swamp\nhumanbattlefield=Island;Island;Mountain;Grizzly Bears\n"
            + "humangraveyard=\nhumanexile=\n"
            + "humanlibrary=Swamp;Island;Swamp;Duress;Night's Whisper;Grave Titan;Swamp;Mountain\n";

    static Card dungeonOf(final Player p) {
        for (Card c : p.getCardsIn(ZoneType.Command)) {
            if (c.getType().isDungeon()) {
                return c;
            }
        }
        return null;
    }

    static void venture(final Game g, final Player p) {
        final SpellAbility v = forge.game.ability.AbilityFactory.getAbility("DB$ Venture | Dungeon$ Undercity | Defined$ You",
                p.getCardsIn(ZoneType.Battlefield).get(0));
        v.setActivatingPlayer(p);
        v.resolve();
    }

    static LookaheadSearch search(final boolean copyDungeon) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 1;
        c.threads = 1;
        c.copyDungeon = copyDungeon;
        return new LookaheadSearch(c);
    }

    @Test(timeOut = 600000)
    public void theDungeonIsInTheCopyOnlyWithTheKey() {
        initAndCreateGame();
        final Game g = ForcedWinSolverTest.game("Default", "Default", FRAME);
        final Player me = g.getPlayers().get(0);
        venture(g, me);
        final Card d = dungeonOf(me);
        Assert.assertNotNull(d, "the seat entered the dungeon");
        final String room = d.getCurrentRoom();
        Assert.assertNotNull(room);

        final LookaheadSearch off = search(false);
        try {
            final Game cp = off.copierOf(g).makeCopy();
            Assert.assertNull(cp.findById(d.getId()), "off (today): the copy has no dungeon");
            Assert.assertNull(dungeonOf(cp.getPlayers().get(0)));
        } finally {
            off.shutdown();
        }

        final LookaheadSearch on = search(true);
        try {
            final Game cp = on.copierOf(g).makeCopy();
            final Player cme = cp.getPlayers().get(0);
            final Card cd = cp.findById(d.getId());
            Assert.assertNotNull(cd, "on: the copy holds the dungeon");
            Assert.assertTrue(cd.getType().isDungeon());
            Assert.assertFalse(cd.isToken(), "a dungeon, not a token");
            Assert.assertEquals(cd.getGamePieceType(), d.getGamePieceType());
            Assert.assertTrue(cd.isInZone(ZoneType.Command));
            Assert.assertEquals(cd.getCurrentRoom(), room);
            Assert.assertEquals(cd.getTriggers().size(), d.getTriggers().size(), "its rooms");
            Assert.assertSame(dungeonOf(cme), cd);
            // it survives the copy's state checks, and a venture in the copy goes on from the room
            cp.getAction().checkStateEffects(true);
            Assert.assertNotNull(cp.findById(d.getId()), "still there after state-based actions");
            venture(cp, cme);
            final Card after = dungeonOf(cme);
            Assert.assertNotNull(after);
            Assert.assertEquals(after.getId(), d.getId(), "the same dungeon, not a new one");
            Assert.assertNotEquals(after.getCurrentRoom(), room, "the copy's venture advanced to a next room");
            // a copy of the copy keeps it (the key is inherited by copies)
            final Game cp2 = new GameCopier(cp, true).makeCopy();
            Assert.assertNotNull(cp2.findById(d.getId()), "a copy of the copy holds the dungeon");
            // the live game is untouched
            Assert.assertEquals(d.getCurrentRoom(), room);
            Assert.assertFalse(g.copyDungeon);
        } finally {
            on.shutdown();
        }
    }

    /** The card scorer's path: a room trigger's ability is rebuilt in the copy from the copied dungeon. */
    @Test(timeOut = 600000)
    public void theRoomTriggerIsRebuiltFromTheCopiedDungeon() {
        initAndCreateGame();
        final Game g = ForcedWinSolverTest.game("Default", "Default", FRAME);
        final Player me = g.getPlayers().get(0);
        venture(g, me);
        final Card d = dungeonOf(me);
        // the first room's trigger as Forge wraps it for the stack
        final forge.game.trigger.Trigger t = d.getTriggers().get(0);
        final forge.game.trigger.WrappedAbility w = new forge.game.trigger.WrappedAbility(t, t.getOverridingAbility(), me);
        Assert.assertEquals(w.getHostCard().getId(), d.getId());
        for (boolean key : new boolean[] {false, true}) {
            final LookaheadSearch s = search(key);
            try {
                final GameCopier copier = s.copierOf(g);
                final Game cp = copier.makeCopy();
                final Player cme = cp.getPlayers().get(0);
                if (!key) {
                    try {
                        LookaheadSearch.rebuildTrigger(cp, cme, w);
                        Assert.fail("off: the trigger's host is not in the copy");
                    } catch (IllegalStateException e) {
                        Assert.assertTrue(e.getMessage().contains("trigger host") && e.getMessage().contains("missing"), e.getMessage());
                    }
                } else {
                    final SpellAbility sa = LookaheadSearch.rebuildTrigger(cp, cme, w);
                    Assert.assertNotNull(sa);
                    Assert.assertEquals(sa.getHostCard().getId(), d.getId());
                    Assert.assertEquals(sa.getParam("RoomName"), d.getCurrentRoom(), "the room the seat stands in");
                    Assert.assertSame(sa.getHostCard(), cp.findById(d.getId()));
                }
            } finally {
                s.shutdown();
            }
        }
    }

    @Test
    public void theKeyIsOffByDefaultAndWrittenOnlyWhenOn() {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        Assert.assertFalse(c.copyDungeon);
        Assert.assertFalse(c.toJson().has("copyDungeon"));
        c.copyDungeon = true;
        Assert.assertTrue(c.toJson().get("copyDungeon").getAsBoolean());
        final com.google.gson.JsonObject base = BvwStep3PlayoutTest.block(1, "").getAsJsonObject("rlsearch");
        Assert.assertFalse(forge.bench.rl.RlSearch.Config.parse(base).toJson().has("copyDungeon"));
        final forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(
                BvwStep3PlayoutTest.block(1, "\"copyDungeon\":true").getAsJsonObject("rlsearch"));
        Assert.assertTrue(on.copyDungeon);
        Assert.assertTrue(on.toJson().get("copyDungeon").getAsBoolean());
        final String live = "server=127.0.0.1:1,cardIndex=/x,policySha=" + "0".repeat(64);
        Assert.assertFalse(forge.bench.rl.RlLiveSeat.Spec.parse(live).search.copyDungeon);
        Assert.assertTrue(forge.bench.rl.RlLiveSeat.Spec.parse(live + ",copyDungeon=1").search.copyDungeon);
    }
}
