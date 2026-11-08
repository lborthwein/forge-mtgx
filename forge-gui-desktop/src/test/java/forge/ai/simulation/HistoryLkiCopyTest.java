package forge.ai.simulation;

import java.util.List;
import com.google.gson.JsonObject;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** A generated two-face card exercises history mapping without a named-card special case. */
public class HistoryLkiCopyTest extends SimulationTest {
    private Game source(boolean secondary) {
        Game game = initAndCreateGame(); Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        forge.item.PaperCard paper = new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of(
                "Name:Generated traveler", "ManaCost:1", "Types:Creature Wizard", "PT:1/1",
                "S:Mode$ Continuous | EffectZone$ Exile | Affected$ Card.Self | AffectedZone$ Exile | MayPlay$ True",
                "AlternateMode:Adventure", "Oracle:", "ALTERNATE", "Name:Generated journey",
                "ManaCost:1", "Types:Sorcery Adventure",
                "A:SP$ Draw | Cost$ 1 | NumCards$ 1 | SpellDescription$ Draw a card.", "Oracle:")),
                "", forge.card.CardRarity.Common);
        Card card = Card.fromPaperCard(paper, me); me.getZone(ZoneType.Exile).add(card);
        card.setGameTimestamp(game.getNextTimestamp());
        if (secondary) card.setState(CardStateName.Secondary, false);
        Card history = CardCopyService.getLKICopy(card);
        card.setState(CardStateName.Original, false);
        card.setGameTimestamp(game.getNextTimestamp()); card.setExiledWith(history);
        game.getAction().checkStaticAbilities();
        assertNotSame(history, card); assertTrue(history.equals(card));
        assertNotEquals(history.getGameTimestamp(), card.getGameTimestamp());
        assertEquals(card.isOnAdventure(), secondary);
        return game;
    }
    private Card card(Game g) { return g.getPlayers().get(1).getCardsIn(ZoneType.Exile).get(0); }
    private Game copy(Game g, boolean repair) {
        GameCopier copier = new GameCopier(g, true); copier.setCopyFidelity(1); copier.setHistoryLki(repair);
        return copier.makeCopy();
    }
    private boolean offersAdventure(Card c) {
        return c.getAllPossibleAbilities(c.getOwner(), false).stream().anyMatch(sa -> sa.isAdventure());
    }

    @Test public void oldMapperCollapsesHistoricalFaceAndRepairPreservesItThroughCopies() {
        Game live = source(true); Card original = card(live);
        assertFalse(offersAdventure(original), "the already-used secondary spell is excluded");
        Game oldGame = copy(live, false); Card old = card(oldGame);
        assertSame(old.getExiledWith(), old, "old id lookup returns the current zone object");
        assertFalse(old.isOnAdventure()); assertTrue(offersAdventure(old), "old path offers the used spell again");
        Game fixed = copy(live, true); Card on = card(fixed);
        assertNotSame(on.getExiledWith(), on); assertSame(on.getExiledWith().getGame(), fixed);
        assertEquals(on.getExiledWith().getCurrentStateName(), CardStateName.Secondary);
        assertEquals(on.getExiledWith().getGameTimestamp(), original.getExiledWith().getGameTimestamp());
        assertTrue(on.isOnAdventure()); assertFalse(offersAdventure(on)); assertTrue(fixed.historyLki);
        Game again = copy(fixed, false); Card nested = card(again);
        assertTrue(again.historyLki); assertTrue(nested.isOnAdventure()); assertFalse(offersAdventure(nested));
        assertSame(nested.getExiledWith().getGame(), again); assertNotSame(nested, nested.getExiledWith());
        assertTrue(original.isOnAdventure()); assertFalse(live.historyLki);
    }

    @Test public void sameFaceOldTimestampRemainsHistoricalButCurrentReferenceRemainsCurrent() {
        Game live = source(false); Card original = card(live);
        Card off = card(copy(live, false)); assertSame(off, off.getExiledWith());
        Card on = card(copy(live, true)); assertNotSame(on, on.getExiledWith());
        assertEquals(on.getExiledWith().getCurrentStateName(), CardStateName.Original);
        assertEquals(on.getExiledWith().getGameTimestamp(), original.getExiledWith().getGameTimestamp());
        assertNotEquals(on.getExiledWith().getGameTimestamp(), on.getGameTimestamp());
        original.setExiledWith(original); Card current = card(copy(live, true));
        assertSame(current.getExiledWith(), current, "current objects must not become stale snapshots");
    }

    @Test public void repeatedCopiesDoNotReuseHistoricalObjectsFromEarlierWorlds() {
        Game live = source(true);
        Game first = copy(live, true); Game second = copy(live, true);
        assertNotSame(first, second);
        assertSame(card(first).getExiledWith().getGame(), first);
        assertSame(card(second).getExiledWith().getGame(), second);
        assertNotSame(card(first).getExiledWith(), card(second).getExiledWith());
        assertTrue(card(first).isOnAdventure()); assertTrue(card(second).isOnAdventure());
    }

    @Test public void defaultOffSearchAndSolverConfigurationRoundTrip() {
        assertFalse(new forge.bench.rl.RlSearch.Config().historyLki);
        assertFalse(new LookaheadSearch.Config().historyLki);
        assertFalse(new ForcedWinSolver.Config().historyLki);
        JsonObject j = new JsonObject(); j.addProperty("server", "127.0.0.1:1"); j.addProperty("historyLki", true);
        forge.bench.rl.RlSearch.Config c = forge.bench.rl.RlSearch.Config.parse(j);
        assertTrue(c.historyLki); assertTrue(c.toJson().get("historyLki").getAsBoolean());
        assertTrue(ForcedWinSolver.Config.fromProps(k -> "historyLki".equals(k) ? "true" : null).historyLki);
    }
}
