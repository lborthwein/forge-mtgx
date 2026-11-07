package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.game.EngineFixes;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane chaos-bugs-1106, {@code forge.copyRobust}: three exceptions that blanked or killed look-ahead rollouts and one in
 * the classic AI. Key off: each still throws (decisions are as before). Key on: no throw.
 */
public class CopyRobustTest extends SimulationTest {

    @AfterMethod
    public void off() {
        EngineFixes.setCopyRobustForTest(false);
    }

    private static boolean throwsOn(final Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private Game base() {
        final Game game = initAndCreateGame();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0));
        return game;
    }

    /** A token copy of a card with an SVar-reading keyword: the copier made the token before it had the SVar. */
    @Test
    public void tokenCopyOfAnSvarKeyword() {
        final Game game = base();
        final Player p = game.getPlayers().get(0);
        final Card source = addCard("True-Name Nemesis", p);
        final Card token = createToken("c_1_1_a_thopter_flying", p);
        new CardCopyService(source).copyCopiableCharacteristics(token, null, null);
        p.getZone(ZoneType.Battlefield).add(token);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(token.isToken());
        AssertJUnit.assertTrue("key off still throws", throwsOn(() -> new GameCopier(game).makeCopy()));
        EngineFixes.setCopyRobustForTest(true);
        final Game copy = new GameCopier(game).makeCopy();
        AssertJUnit.assertEquals(2, copy.getPlayers().get(0).getCardsIn(ZoneType.Battlefield).size());
    }

    /** A card remembering a token that has left every zone but kept its zone pointer. */
    @Test
    public void staleRememberedToken() {
        final Game game = base();
        final Player p = game.getPlayers().get(0);
        final Card host = addCard("Grizzly Bears", p);
        final Card token = addToken("c_1_1_a_thopter_flying", p);
        host.addRemembered(token);
        p.getZone(ZoneType.Battlefield).remove(token);
        AssertJUnit.assertNotNull(token.getZone());
        AssertJUnit.assertTrue("key off still throws", throwsOn(() -> new GameCopier(game).makeCopy()));
        EngineFixes.setCopyRobustForTest(true);
        final Game copy = new GameCopier(game).makeCopy();
        AssertJUnit.assertFalse(copy.getCardsIn(ZoneType.Battlefield).get(0).hasRemembered());
    }

    /** MayPlayPlayer with no such player: no one may play the card, and checking statics does not throw. */
    @Test
    public void mayPlayWithNoDefinedPlayer() {
        final Game game = base();
        final Player p = game.getPlayers().get(0);
        final Card host = addCard("Grizzly Bears", p);
        host.addStaticAbility("Mode$ Continuous | MayPlay$ True | MayPlayPlayer$ EffectSourceController"
                + " | AffectedDefined$ Self | AffectedZone$ Battlefield");
        AssertJUnit.assertTrue("key off still throws", throwsOn(() -> game.getAction().checkStaticAbilities()));
        EngineFixes.setCopyRobustForTest(true);
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertTrue(host.mayPlay(p).isEmpty());
    }

    /** A hidden change-zone ability with a known origin and no ChangeType, on the classic AI's own check. */
    @Test
    public void hiddenChangeZoneWithNoType() {
        final Game game = base();
        final Player p = game.getPlayers().get(0);
        addCardToZone("Grizzly Bears", p, ZoneType.Graveyard);
        addCard("Mountain", p);
        final Card spell = addCardToZone("Fossil Find", p, ZoneType.Hand);
        final SpellAbility sa = spell.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        final AiController ai = ((PlayerControllerAi) p.getController()).getAi();
        AssertJUnit.assertTrue("key off still throws", throwsOn(() -> ai.canPlaySa(sa)));
        EngineFixes.setCopyRobustForTest(true);
        ai.canPlaySa(sa);
    }
}
