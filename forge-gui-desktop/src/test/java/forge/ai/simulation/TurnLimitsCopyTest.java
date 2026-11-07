package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

/**
 * Judge lane. A copied card comes fresh from its script, so this turn's "once each turn" counts read zero in the
 * copy: a may-play cap (Lurrus) and an activation limit. A copy could use the limit again. Key off: the copy forgets them.
 */
public class TurnLimitsCopyTest extends SimulationTest {

    private static StaticAbility mayPlay(final Card c) {
        for (StaticAbility st : c.getStaticAbilities()) {
            if (st.hasParam("MayPlayLimit")) {
                return st;
            }
        }
        throw new AssertionError("no may-play static on " + c);
    }

    private static Card named(final Game g, final String name) {
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(name);
    }

    private Game live() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        addCard("Lurrus of the Dream-Den", me);
        final Card walla = addCard("Rootwalla", me);
        walla.setSickness(false);
        game.getAction().checkStateEffects(true);
        mayPlay(named(game, "Lurrus of the Dream-Den")).incMayPlayTurn(); // the cast from the graveyard this turn
        for (SpellAbility sa : walla.getSpellAbilities()) {
            if (sa.isActivatedAbility()) {
                walla.addAbilityActivated(sa); // the pump this turn
            }
        }
        return game;
    }

    private static int activations(final Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility()) {
                return sa.getActivationsThisTurn();
            }
        }
        return -1;
    }

    @Test
    public void keyOffTheCopyForgetsTheTurnsLimits() {
        final Game copy = new GameCopier(live(), true).makeCopy();
        AssertJUnit.assertEquals(0, mayPlay(named(copy, "Lurrus of the Dream-Den")).getMayPlayTurn());
        AssertJUnit.assertEquals(0, activations(named(copy, "Rootwalla")));
    }

    @Test
    public void keyOnTheCopyKeepsThem() {
        final Game live = live();
        AssertJUnit.assertEquals(1, mayPlay(named(live, "Lurrus of the Dream-Den")).getMayPlayTurn());
        AssertJUnit.assertEquals(1, activations(named(live, "Rootwalla")));
        final GameCopier on = new GameCopier(live, true);
        on.setTurnLimits(true);
        final Game copy = on.makeCopy();
        AssertJUnit.assertEquals(1, mayPlay(named(copy, "Lurrus of the Dream-Den")).getMayPlayTurn());
        AssertJUnit.assertEquals(1, activations(named(copy, "Rootwalla")));
        // a copy of the copy too
        final GameCopier again = new GameCopier(copy, true);
        again.setTurnLimits(true);
        final Game copy2 = again.makeCopy();
        AssertJUnit.assertEquals(1, mayPlay(named(copy2, "Lurrus of the Dream-Den")).getMayPlayTurn());
        AssertJUnit.assertEquals(1, activations(named(copy2, "Rootwalla")));
    }
}
