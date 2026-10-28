package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * belief-jar-1027 (research port of the live fix, review #126 S2): one search serving both seats (RlActorBench with
 * search_seat -1: BVW labels, the H2H) keeps each seat's OWN deck out of its singleton belief pool. Before, the first
 * searching seat's deck was cached for both, so the other seat never drew its opponent's real card names and drew its
 * own. Red on research/bvw-step1-1024, green on the fix.
 */
public class BeliefOwnDeckSeatTest extends SimulationTest {

    private static final String[] POOL = {"Counterspell", "Lightning Bolt", "Hill Giant", "Shock", "Giant Growth",
        "Grizzly Bears", "Wrath of God", "Mana Leak", "Prodigal Pyromancer", "Serra Angel", "Llanowar Elves", "Dark Ritual"};

    private Game gameWithDecks(Deck d0, Deck d1) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(d0).setPlayer(new LobbyPlayerAi("p2", null)));
        players.add(new RegisteredPlayer(d1).setPlayer(new LobbyPlayerAi("p1", new HashSet<>())));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        game.AI_TIMEOUT = FModel.getPreferences().getPrefInt(FPref.MATCH_AI_TIMEOUT);
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 11; i++) {
            addCardToZone("Raging Goblin", b, ZoneType.Library);
            addCardToZone("Mountain", a, ZoneType.Library);
        }
        addCard("Mountain", a);
        addCard("Forest", b);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Craw Wurm", b, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static List<String> worldOf(Game game, Player me, LookaheadSearch s, Random rng) {
        GameCopier c = new GameCopier(game, true);
        Game g = c.makeCopy();
        s.redraw(game, me, g, (Player) c.find(me), rng);
        Player o = (Player) c.find(me.getOpponents().get(0));
        List<String> out = new ArrayList<>();
        for (Card x : o.getCardsIn(ZoneType.Hand)) {
            out.add(x.getName());
        }
        for (Card x : o.getCardsIn(ZoneType.Library)) {
            out.add(x.getName());
        }
        return out;
    }

    @Test
    public void eachSeatKeepsItsOwnDeckOutOfItsPool() throws Exception {
        Deck d0 = new Deck();
        d0.getMain().add("Hill Giant", 1);
        d0.getMain().add("Mountain", 39);
        Deck d1 = new Deck();
        d1.getMain().add("Serra Angel", 1);
        d1.getMain().add("Forest", 39);
        Game game = gameWithDecks(d0, d1);
        forge.util.IdScope.open();
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 3;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1027L;
        c.belief = "uniform";
        c.beliefPool = java.util.Arrays.asList(POOL);
        c.beliefSingleton = true;
        LookaheadSearch s = new LookaheadSearch(c);
        final Player p0 = game.getPlayers().get(0), p1 = game.getPlayers().get(1);
        worldOf(game, p0, s, new Random(1)); // seat 0 searches first
        boolean giant = false;
        for (int i = 0; i < 60; i++) {
            List<String> w = worldOf(game, p1, s, new Random(60 + i));
            AssertJUnit.assertFalse("seat 1's own Serra Angel in its opponent's world", w.contains("Serra Angel"));
            giant |= w.contains("Hill Giant");
        }
        AssertJUnit.assertTrue("seat 1 may draw its opponent's Hill Giant", giant);
        for (int i = 0; i < 30; i++) {
            AssertJUnit.assertFalse("seat 0's own Hill Giant", worldOf(game, p0, s, new Random(200 + i)).contains("Hill Giant"));
        }
        s.shutdown();
        forge.util.IdScope.close();
    }
}
