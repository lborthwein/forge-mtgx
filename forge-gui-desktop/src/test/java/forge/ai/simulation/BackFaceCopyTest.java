package forge.ai.simulation;

import java.util.List;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** Generated transforming permanent: its current face and physical orientation are separate engine fields. */
public class BackFaceCopyTest extends SimulationTest {
    private Game source() {
        Game g = initAndCreateGame(); Player me = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        forge.item.PaperCard paper = new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of(
                "Name:Generated front", "ManaCost:1", "Types:Creature Wizard", "PT:1/1",
                "AlternateMode:Transform", "Oracle:", "ALTERNATE", "Name:Generated back",
                "ManaCost:no cost", "Types:Creature Wizard", "PT:2/2", "Oracle:")),
                "", forge.card.CardRarity.Common);
        Card c = Card.fromPaperCard(paper, me); me.getZone(ZoneType.Battlefield).add(c);
        c.setGameTimestamp(g.getNextTimestamp());
        assertTrue(c.changeCardState("Transform", null, null));
        assertEquals(c.getCurrentStateName(), CardStateName.Backside); assertTrue(c.isBackSide());
        return g;
    }
    private Card card(Game g) { return g.getPlayers().get(1).getCardsIn(ZoneType.Battlefield).get(0); }
    private Game copy(Game live, boolean enabled) {
        GameCopier copier=new GameCopier(live,true); copier.setCopyFidelity(1);
        copier.setCopyBackFace(enabled); return copier.makeCopy();
    }
    @Test public void offLosesOrientationWhileOnTransformsBackAndSurvivesAnotherCopy() {
        Game live=source(); Card old=card(copy(live,false));
        assertEquals(old.getCurrentStateName(), CardStateName.Backside);
        assertFalse(old.isBackSide()); assertFalse(old.isTransformed());
        assertFalse(old.changeCardState("Transform",null,null));
        assertEquals(old.getCurrentStateName(),CardStateName.Backside);
        Game fixed=copy(live,true); Card on=card(fixed);
        assertSame(on.getGame(),fixed); assertTrue(on.isBackSide()); assertTrue(on.isTransformed());
        assertEquals(on.getCurrentStateName(),CardStateName.Backside);
        Game nested=copy(fixed,false); assertTrue(nested.copyBackFace);
        assertTrue(card(nested).isBackSide());
        assertTrue(card(nested).changeCardState("Transform",null,null));
        assertEquals(card(nested).getCurrentStateName(),CardStateName.Original);
        assertFalse(card(nested).isBackSide());
        assertTrue(on.changeCardState("Transform",null,null));
        assertEquals(on.getCurrentStateName(),CardStateName.Original); assertFalse(on.isBackSide());
        assertTrue(card(live).isBackSide()); assertFalse(live.copyBackFace);
        assertTrue(card(live).changeCardState("Transform",null,null));
        assertEquals(card(live).getCurrentStateName(),CardStateName.Original);
    }
    @Test public void preservesFrontTokenAndOffBattlefieldOrientation() {
        Game front=source(); assertTrue(card(front).changeCardState("Transform",null,null));
        assertFalse(card(copy(front,true)).isBackSide());
        Game token=source(); card(token).setGamePieceType(forge.card.GamePieceType.TOKEN);
        Card copiedToken=card(copy(token,true)); assertTrue(copiedToken.isToken());
        assertTrue(copiedToken.isBackSide());
        assertTrue(copiedToken.changeCardState("Transform",null,null));
        assertEquals(copiedToken.getCurrentStateName(),CardStateName.Original);
        Game exiled=source(); Card c=card(exiled); Player owner=c.getOwner();
        owner.getZone(ZoneType.Battlefield).remove(c); owner.getZone(ZoneType.Exile).add(c);
        Card off=copy(exiled,false).getPlayers().get(1).getCardsIn(ZoneType.Exile).get(0);
        assertEquals(off.getCurrentStateName(),CardStateName.Backside); assertFalse(off.isBackSide());
        Game fixed=copy(exiled,true); Card on=fixed.getPlayers().get(1).getCardsIn(ZoneType.Exile).get(0);
        assertEquals(on.getCurrentStateName(),CardStateName.Backside); assertTrue(on.isBackSide());
        assertSame(on.getGame(),fixed);
    }
    @Test public void defaultOffSearchAndSolverConfigurationRoundTrip() {
        org.testng.Assert.assertEquals(new forge.bench.rl.RlSearch.Config().copyBackFace, LookaheadSearch.LIVE_COPY_BACK_FACE); // live-fixes-1107
        org.testng.Assert.assertEquals(new LookaheadSearch.Config().copyBackFace, LookaheadSearch.LIVE_COPY_BACK_FACE); // live-fixes-1107
        assertFalse(new ForcedWinSolver.Config().copyBackFace);
        com.google.gson.JsonObject j=new com.google.gson.JsonObject();
        j.addProperty("server","127.0.0.1:1"); j.addProperty("copyBackFace",true);
        forge.bench.rl.RlSearch.Config c=forge.bench.rl.RlSearch.Config.parse(j);
        assertTrue(c.copyBackFace); assertTrue(c.toJson().get("copyBackFace").getAsBoolean());
        LookaheadSearch.Config l=new LookaheadSearch.Config(); l.copyBackFace=true;
        assertTrue(l.toJson().get("copyBackFace").getAsBoolean());
        assertTrue(ForcedWinSolver.Config.fromProps(k -> "copyBackFace".equals(k)?"true":null).copyBackFace);
    }
}
