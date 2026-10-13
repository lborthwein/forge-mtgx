package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CubeComboPlayerController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

/**
 * Lane doomsday-solver-1012: the general forced-win solver (off by default; the built-in profiles "ForcedWin" and
 * "CubeComboSolver" turn it on). Positions here are non-Doomsday families: Doomsday is the solver's held-out test.
 */
public class ForcedWinSolverTest extends AITest {

    /** A GameState applied on the calling thread (the test's own). */
    static final class NowState extends GameState {
        void applyNow(final Game g) {
            applyGameOnThread(g);
        }
    }

    /** A two-seat game: seat 0 = "human" of a frame, seat 1 = "ai"; each seat's AI profile as given. */
    static Game game(String profile0, String profile1, String frame) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        final LobbyPlayerAi a = new LobbyPlayerAi("Seat0", null);
        a.setAiProfile(profile0);
        final LobbyPlayerAi b = new LobbyPlayerAi("Seat1", null);
        b.setAiProfile(profile1);
        players.add(new RegisteredPlayer(new Deck()).setPlayer(a));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(b));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Match match = new Match(rules, players, "ForcedWinTest");
        final Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        final NowState gs = new NowState();
        gs.parse(Arrays.asList(frame.split("\\R")));
        gs.applyNow(game);
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    /** Play until the game ends or turn {@code lastTurn} is over. */
    static void play(Game g, int lastTurn) {
        int steps = 0;
        while (!g.isGameOver() && g.getPhaseHandler().getTurn() <= lastTurn && steps++ < 20000) {
            g.getPhaseHandler().mainLoopStep();
        }
    }

    static final String LIB20 = "Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;"
            + "Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest";
    static final String OURLIB = "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island";

    static String frame(String humanHand, String humanBattlefield, int humanLife, String aiHand, String aiBattlefield,
            int aiLife, String active) {
        return "[state]\nturn=7\nactiveplayer=" + active + "\nactivephase=MAIN1\nhumanlife=" + humanLife + "\nailife="
                + aiLife + "\nhumanlandsplayed=0\nailandsplayed=0\naihand=" + aiHand + "\nailibrary=" + LIB20
                + "\naigraveyard=\naiexile=\naibattlefield=" + aiBattlefield + "\nhumanhand=" + humanHand
                + "\nhumanbattlefield=" + humanBattlefield + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + OURLIB
                + "\n";
    }

    static Card inZone(Player p, ZoneType z, String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ off by default

    @Test
    public void solverIsOffInEveryShippedProfileAndOnlyInTheBuiltIns() {
        Assert.assertEquals(AiProps.FORCED_WIN_SOLVER.getDefault(), "false");
        Assert.assertEquals(AiProps.CUBE_COMBO_LINEAGE.getDefault(), "true");
        for (String p : AiProfileUtil.getAvailableProfiles()) {
            final LobbyPlayerAi l = new LobbyPlayerAi("x", null);
            l.setAiProfile(p);
            Assert.assertEquals(AiProfileUtil.getAIProp(l, AiProps.FORCED_WIN_SOLVER), "false", p);
        }
        Assert.assertFalse(AiProfileUtil.getAvailableProfiles().contains(AiProfileUtil.FORCED_WIN_PROFILE));
        Assert.assertTrue(AiProfileUtil.isKnownProfile(AiProfileUtil.FORCED_WIN_PROFILE));
        Assert.assertTrue(AiProfileUtil.isKnownProfile(AiProfileUtil.CUBE_COMBO_SOLVER_PROFILE));
        final LobbyPlayerAi combo = new LobbyPlayerAi("c", null);
        combo.setAiProfile(AiProfileUtil.CUBE_COMBO_PROFILE);
        Assert.assertEquals(AiProfileUtil.getAIProp(combo, AiProps.FORCED_WIN_SOLVER), "false");
        final LobbyPlayerAi fw = new LobbyPlayerAi("f", null);
        fw.setAiProfile(AiProfileUtil.FORCED_WIN_PROFILE);
        Assert.assertEquals(AiProfileUtil.getAIProp(fw, AiProps.FORCED_WIN_SOLVER), "true");
        Assert.assertEquals(AiProfileUtil.getAIProp(fw, AiProps.CUBE_COMBO_LINEAGE), "false");
        final LobbyPlayerAi cs = new LobbyPlayerAi("s", null);
        cs.setAiProfile(AiProfileUtil.CUBE_COMBO_SOLVER_PROFILE);
        Assert.assertEquals(AiProfileUtil.getAIProp(cs, AiProps.FORCED_WIN_SOLVER), "true");
        Assert.assertEquals(AiProfileUtil.getAIProp(cs, AiProps.CUBE_COMBO_LINEAGE), "true");
        // the built-in profiles are Default in every other property
        final LobbyPlayerAi def = new LobbyPlayerAi("d", null);
        def.setAiProfile("Default");
        for (AiProps prop : AiProps.values()) {
            if (prop == AiProps.CUBE_COMBO_PLANS || prop == AiProps.FORCED_WIN_SOLVER || prop == AiProps.CUBE_COMBO_LINEAGE) {
                continue;
            }
            Assert.assertEquals(AiProfileUtil.getAIProp(fw, prop), AiProfileUtil.getAIProp(def, prop), prop.name());
        }
    }

    @Test
    public void defaultSeatsKeepTheirControllersAndCubeComboHasNoSolver() {
        final Game g = game("Default", AiProfileUtil.CUBE_COMBO_PROFILE, frame("", "Island", 20, "", "Island", 20, "human"));
        Assert.assertSame(g.getPlayers().get(0).getController().getClass(), PlayerControllerAi.class);
        final CubeComboPlayerController combo = (CubeComboPlayerController) g.getPlayers().get(1).getController();
        Assert.assertNull(combo.cubeCombo().forcedWin());
        Assert.assertTrue(combo.cubeCombo().lineage());
        final Game h = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("", "Island", 20, "", "Island", 20, "human"));
        final CubeComboPlayerController fw = (CubeComboPlayerController) h.getPlayers().get(0).getController();
        Assert.assertNotNull(fw.cubeCombo().forcedWin());
        Assert.assertFalse(fw.cubeCombo().lineage());
    }

    // ------------------------------------------------------------------ affordances are learned by probing

    @Test
    public void probesLearnWhatCardsDo() {
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(
                "Dark Ritual;Brainstorm;Tendrils of Agony;Lotus Petal;Laboratory Maniac", "Swamp;Island", 20, "", "Island",
                20, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
        final ACard ritual = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Dark Ritual"), 1, st);
        final Abil r = ritual.fromHand.get(0);
        Assert.assertEquals(r.kind, AbilKind.SPELL);
        Assert.assertEquals(r.pips[ForcedWinModel.B], 1);
        Assert.assertEquals(r.effects.get(0).kind, EffKind.MANA, r.toString());
        Assert.assertEquals(r.effects.get(0).n, 3, r.toString());
        Assert.assertEquals(r.effects.get(0).colors, 1 << ForcedWinModel.B, r.toString());

        final ACard bs = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Brainstorm"), 1, st);
        final Abil b = bs.fromHand.get(0);
        Assert.assertEquals(b.effects.get(0).kind, EffKind.DRAW, b.toString());
        Assert.assertEquals(b.effects.get(0).n, 3, b.toString());
        Assert.assertEquals(b.effects.get(1).kind, EffKind.PUTBACK, b.toString());
        Assert.assertEquals(b.effects.get(1).n, 2, b.toString());
        Assert.assertTrue(b.instant);

        final ACard tendrils = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Tendrils of Agony"), 1, st);
        Abil atOpp = null;
        for (Abil a : tendrils.fromHand) {
            if ("opp".equals(a.targetVariant)) {
                atOpp = a;
            }
        }
        Assert.assertNotNull(atOpp, tendrils.fromHand.toString());
        Assert.assertTrue(atOpp.storm);
        Assert.assertTrue(atOpp.has(EffKind.DRAIN), atOpp.toString());

        final ACard petal = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Lotus Petal"), 1, st);
        Assert.assertEquals(petal.sourceColors & 0b11111, 0b11111);
        Assert.assertTrue(petal.sourceSac);

        // a "win instead of drawing from an empty library" permanent, recognised by drawing with an empty library
        final ACard maniac = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Laboratory Maniac"), 1, st);
        Assert.assertTrue(maniac.emptyDrawWin);
        Assert.assertTrue(st.probes > 5);
    }

    // ------------------------------------------------------------------ offense: width search, engine, live seat

    @Test
    public void widthSearchFindsTheStormKill() {
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(
                "Dark Ritual;Dark Ritual;Tendrils of Agony;Lotus Petal;Mox Jet", "Swamp;Swamp;Swamp", 20, "",
                "Forest;Forest;Forest;Forest;Grizzly Bears", 10, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader reader = new ForcedWinModel.Reader();
        final ForcedWinModel.Position pos = reader.read(g, me, null, true);
        final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, new ForcedWinPlanner.Limits());
        Assert.assertFalse(res.lines.isEmpty(), res.summary());
        Assert.assertTrue(res.sameTurn);
        Assert.assertEquals(res.lines.get(0).how, "storm");
        // a hand that cannot win has no line
        final Game h = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(
                "Dark Ritual;Tendrils of Agony", "Swamp", 20, "", "Forest", 20, "human"));
        final ForcedWinModel.Position p2 = new ForcedWinModel.Reader().read(h, h.getPlayers().get(0), null, true);
        Assert.assertTrue(ForcedWinPlanner.plan(p2, new ForcedWinPlanner.Limits()).lines.isEmpty());
    }

    @Test
    public void theSolverSeatWinsTheStormTurn() {
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(
                "Dark Ritual;Dark Ritual;Tendrils of Agony;Lotus Petal;Mox Jet", "Swamp;Swamp;Swamp", 20, "",
                "Forest;Forest;Forest;Forest;Grizzly Bears", 10, "human"));
        play(g, 7);
        Assert.assertTrue(g.isGameOver());
        Assert.assertTrue(g.getPlayers().get(0).hasWon());
        final ForcedWinSolver s = ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
        Assert.assertTrue(s.taken >= 1, s.summary());
    }

    @Test
    public void theEngineSearchFindsAKikiLoopAndTheSeatPlaysIt() {
        // Kiki-Jiki and Pestermite: copy Pestermite, its trigger untaps Kiki-Jiki, again; then attack (opponent at 6)
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Pestermite",
                "Kiki-Jiki, Mirror Breaker;Volcanic Island;Volcanic Island;Mountain;Mountain", 20, "",
                "Forest;Forest;Forest;Forest", 6, "human"));
        play(g, 7);
        Assert.assertTrue(g.isGameOver(), "no win on turn 7");
        Assert.assertTrue(g.getPlayers().get(0).hasWon());
        final ForcedWinSolver s = ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
        Assert.assertTrue(s.engineFound >= 1, s.summary());
        // Forge AI alone taps instead of untapping: no win on that turn
        final Game d = game("Default", "Default", frame("Pestermite",
                "Kiki-Jiki, Mirror Breaker;Volcanic Island;Volcanic Island;Mountain;Mountain", 20, "",
                "Forest;Forest;Forest;Forest", 6, "human"));
        play(d, 7);
        Assert.assertFalse(d.isGameOver() && d.getPlayers().get(0).hasWon());
    }

    // ------------------------------------------------------------------ defense: the same search from their seat

    @Test
    public void aDiscardTakesTheCardThatStopsTheirForcedWin() {
        // our Thoughtseize on their storm hand: their forced win (by their next turn) needs Tendrils
        final Game g = game("Default", AiProfileUtil.FORCED_WIN_PROFILE, frame(
                "Dark Ritual;Dark Ritual;Tendrils of Agony;Lotus Petal;Mox Jet;Grizzly Bears", "Swamp;Swamp;Swamp", 20,
                "", "Swamp;Swamp;Swamp", 10, "ai"));
        final Player opp = g.getPlayers().get(0), me = g.getPlayers().get(1);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final Card seize = createCard("Thoughtseize", me);
        final forge.game.spellability.SpellAbility sa = seize.getFirstSpellAbility().copy(me);
        sa.setActivatingPlayer(me);
        final CardCollection valid = new CardCollection();
        for (Card c : opp.getCardsIn(ZoneType.Hand)) {
            if (!c.isLand() && !c.isCreature()) {
                valid.add(c);
            }
        }
        final CardCollection take = s.answerDiscard(opp, sa, valid, 1, 1);
        Assert.assertNotNull(take, "no opinion");
        Assert.assertEquals(take.get(0).getName(), "Tendrils of Agony");
    }
}
