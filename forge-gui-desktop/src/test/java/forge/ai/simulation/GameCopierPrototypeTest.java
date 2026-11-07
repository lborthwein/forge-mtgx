package forge.ai.simulation;

import java.lang.ref.WeakReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * void-causes-1104 (P-GEN void 2989505186, kiki vs fair, held its JVM; base jar d6236e761aa and every later jar): a
 * copy token's prototype ({@code getCopiedPermanent}) was copied by GameCopier into the ORIGINAL game, so each copied
 * token kept a card of the game it was copied from, and through it that whole game. The forced-win search copies
 * copies: a creature-copy loop's repetitions and the DFS path chained every ancestor copy alive. The heap dump at the
 * OutOfMemoryError: 184 games alive, 17 held by the search's nodes, the rest only through Card.copiedPermanent (30,276
 * cross-game references into 174 games).
 *
 * <p>The prototype now belongs to the copy. These tests pin: the copy's prototype is the copy's card (game and owner),
 * and a copy of a copy of a copy no longer keeps its ancestors alive.
 */
public class GameCopierPrototypeTest extends forge.ai.AITest {

    /** A game with a creature and a token copy of it (made as a copy effect makes one). */
    static Game withCopyToken() {
        return withCopyToken("Grizzly Bears");
    }

    /** As above, with a token copy of {@code creature}. */
    static Game withCopyToken(final String creature) {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("cp", WinConditionCoverageTest.V.NOT_PROVEN, "cp")
                .k("humanbattlefield", creature).k("ailife", "20");
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        Card bears = null;
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(creature) && !c.isToken()) {
                bears = c;
            }
        }
        Assert.assertNotNull(bears, creature);
        final Player me = bears.getController();
        final SpellAbility copy = AbilityFactory.getAbility("AB$ CopyPermanent | Cost$ 0 | Defined$ Self", bears);
        copy.setActivatingPlayer(me);
        AbilityUtils.resolve(copy);
        Assert.assertNotNull(token(g), "the copy token");
        return g;
    }

    static Card token(final Game g) {
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
            if (c.isToken() && c.getCopiedPermanent() != null) {
                return c;
            }
        }
        return null;
    }

    static Game copy(final Game g) {
        final GameCopier c = new GameCopier(g, true);
        c.setCopyFidelity(1);
        return c.makeCopy();
    }

    @Test
    public void theCopysPrototypeBelongsToTheCopy() {
        final Game g = withCopyToken();
        final Game c = copy(g);
        final Card t = token(c);
        Assert.assertNotNull(t, "the copied token");
        Assert.assertNotNull(t.getCopiedPermanent());
        Assert.assertSame(t.getCopiedPermanent().getGame(), c, "the prototype is a card of the original game");
        Assert.assertSame(t.getCopiedPermanent().getOwner().getGame(), c, "the prototype's owner is a player of the original");
        Assert.assertEquals(t.getCopiedPermanent().getName(), token(g).getCopiedPermanent().getName());
        Assert.assertEquals(t.getName(), token(g).getName());
    }

    @Test
    public void copiesOfCopiesDoNotKeepTheirAncestorsAlive() throws Exception {
        final Game g0 = withCopyToken();
        Game g = copy(g0);
        final WeakReference<Game> first = new WeakReference<>(g);
        g = copy(g);
        final WeakReference<Game> second = new WeakReference<>(g);
        g = copy(g);
        final Game last = g;
        g = null;
        for (int i = 0; i < 20 && (first.get() != null || second.get() != null); i++) {
            System.gc();
            Thread.sleep(50);
        }
        Assert.assertNotNull(token(last));
        Assert.assertNull(second.get(), "the parent copy is kept alive by the last copy");
        Assert.assertNull(first.get(), "the grandparent copy is kept alive by the last copy");
    }

    /**
     * Every trigger of every card of a copy belongs to the copy: its host card and its card state are the copy's (the
     * live heap at the hang: 165 of 184 search copies were held only by another game's TriggerHandler.activeTriggers,
     * through a TriggerChangesZone whose cardState was a card of an older copy). A creature with an enters trigger,
     * and a token copy of it.
     */
    @Test
    public void everyTriggerOfACopyBelongsToTheCopy() {
        final Game g0 = withCopyToken("Zealous Conscripts");
        Game g = g0;
        for (int depth = 1; depth <= 3; depth++) {
            g = copy(g);
            g.getTriggerHandler().resetActiveTriggers();
            for (Card c : g.getCardsInGame()) {
                for (Card pc = c.getCopiedPermanent(); pc != null; pc = pc.getCopiedPermanent()) {
                    Assert.assertSame(pc.getGame(), g, "depth " + depth + ": prototype of " + c);
                    final SpellAbility tsa = pc.getTokenSpawningAbility();
                    if (tsa != null) {
                        Assert.assertTrue(tsa.getHostCard() == null || tsa.getHostCard().getGame() == g,
                                "depth " + depth + ": prototype's spawning ability host is of another game");
                        Assert.assertTrue(tsa.getActivatingPlayer() == null || tsa.getActivatingPlayer().getGame() == g,
                                "depth " + depth + ": prototype's spawning ability activator is of another game");
                    }
                    for (forge.game.trigger.Trigger t : pc.getTriggers()) {
                        Assert.assertTrue(t.getCardState() == null || t.getCardState().getCard().getGame() == g,
                                "depth " + depth + ": a prototype trigger's card state is of another game");
                        if (t.getOverridingAbility() != null && t.getOverridingAbility().getCardState() != null) {
                            Assert.assertSame(t.getOverridingAbility().getCardState().getCard().getGame(), g,
                                    "depth " + depth + ": a prototype trigger's ability's card state is of another game");
                        }
                    }
                    if (pc.getCopiedPermanent() == pc) {
                        break;
                    }
                }
                for (forge.game.trigger.Trigger t : c.getTriggers()) {
                    Assert.assertSame(t.getHostCard().getGame(), g, "depth " + depth + ": host of " + t + " on " + c);
                    if (t.getCardState() != null) {
                        Assert.assertSame(t.getCardState().getCard().getGame(), g, "depth " + depth + ": card state of "
                                + t + " on " + c + " is " + t.getCardState().getCard() + " of another game");
                    }
                }
            }
        }
    }

    /** Make another token copy of {@code creature} in {@code g} (as the search's loop does in each copy). */
    static void copyAgain(final Game g, final String creature) {
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(creature) && !c.isToken()) {
                final SpellAbility sa = AbilityFactory.getAbility("AB$ CopyPermanent | Cost$ 0 | Defined$ Self", c);
                sa.setActivatingPlayer(c.getController());
                AbilityUtils.resolve(sa);
                return;
            }
        }
        Assert.fail("no " + creature + " in the copy");
    }

    /**
     * The search's shape: each copy makes a new token before it is copied again (a copy loop's repetitions). Before
     * the fix each copy's tokens kept the game they were copied from, so the last copy kept every ancestor alive.
     */
    @Test
    public void copiesOfCopiesWithEntersTriggersDoNotKeepTheirAncestorsAlive() throws Exception {
        final String cr = "Zealous Conscripts";
        final Game g0 = withCopyToken(cr);
        Game g = copy(g0);
        copyAgain(g, cr);
        final WeakReference<Game> first = new WeakReference<>(g);
        g = copy(g);
        copyAgain(g, cr);
        final WeakReference<Game> second = new WeakReference<>(g);
        g = copy(g);
        final Game last = g;
        g = null;
        for (int i = 0; i < 20 && (first.get() != null || second.get() != null); i++) {
            System.gc();
            Thread.sleep(50);
        }
        Assert.assertNotNull(token(last));
        Assert.assertNull(second.get(), "the parent copy is kept alive by the last copy");
        Assert.assertNull(first.get(), "the grandparent copy is kept alive by the last copy");
    }
}
