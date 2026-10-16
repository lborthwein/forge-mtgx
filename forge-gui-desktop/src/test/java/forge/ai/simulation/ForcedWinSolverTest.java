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
import forge.ai.simulation.ForcedWinModel.Eff;
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
 * "CubeComboSolver" turn it on). solver-gaps-1015: Doomsday is no longer held out; that lane's held-out families are
 * kitten and thopter-sword, which no test here uses.
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
        Assert.assertFalse(b.has(EffKind.TUTOR_TOP), "a put-back from hand is not a tutor: " + b);
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

    @Test
    public void probesLearnTutorsAndCantripCosts() {
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(
                "Mystical Tutor;Demonic Tutor;Gush;Gitaxian Probe;Street Wraith;Ponder;Timetwister", "Swamp;Island;Island", 20, "",
                "Island", 20, "human"));
        final Player me = g.getPlayers().get(0);
        // the library holds something to find
        for (String n : new String[] {"Tendrils of Agony", "Dark Ritual"}) {
            final Card c = createCard(n, me);
            me.getZone(ZoneType.Library).add(c);
        }
        final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
        final ACard mt = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Mystical Tutor"), 1, st);
        Assert.assertTrue(mt.fromHand.get(0).has(EffKind.TUTOR_TOP), mt.fromHand.toString());
        // search, shuffle, then put on top: the shuffle comes first, the chosen card ends on top (post-freeze 2 and 6)
        final List<Eff> mtEffs = mt.fromHand.get(0).effects;
        final int shuffleAt = indexOf(mtEffs, EffKind.SHUFFLE), topAt = indexOf(mtEffs, EffKind.TUTOR_TOP);
        Assert.assertTrue(shuffleAt < 0 || shuffleAt < topAt, mtEffs.toString());
        final ACard dt = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Demonic Tutor"), 1, st);
        Assert.assertTrue(dt.fromHand.get(0).has(EffKind.TUTOR), dt.fromHand.toString());
        final ACard gush = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Gush"), 1, st);
        boolean returns = false;
        for (Abil a : gush.fromHand) {
            Assert.assertTrue(a.has(EffKind.DRAW), a.toString());
            returns |= a.returnN == 2 && "Island".equals(a.returnType);
        }
        Assert.assertTrue(returns, gush.fromHand.toString());
        final ACard probe = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Gitaxian Probe"), 1, st);
        Assert.assertEquals(probe.fromHand.get(0).phyrexian[ForcedWinModel.U], 1, probe.fromHand.toString());
        Assert.assertTrue(probe.fromHand.get(0).has(EffKind.DRAW), probe.fromHand.toString());
        final ACard wraith = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Street Wraith"), 1, st);
        boolean cycles = false;
        for (Abil a : wraith.fromHand) {
            cycles |= a.kind == AbilKind.CYCLE && a.life == 2 && a.has(EffKind.DRAW) && a.instant;
        }
        Assert.assertTrue(cycles, wraith.fromHand.toString());
        final ACard ponder = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Ponder"), 1, st);
        Assert.assertTrue(ponder.fromHand.get(0).has(EffKind.DRAW), ponder.fromHand.toString());
        // a shuffle of hand and graveyard into the library is no put-back on top (post-freeze 2)
        final ACard twister = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Timetwister"), 1, st);
        Assert.assertTrue(twister.fromHand.get(0).has(EffKind.SHUFFLE), twister.fromHand.toString());
        Assert.assertFalse(twister.fromHand.get(0).has(EffKind.PUTBACK), twister.fromHand.toString());
        Assert.assertFalse(twister.fromHand.get(0).has(EffKind.TUTOR_TOP), twister.fromHand.toString());
        Assert.assertTrue(twister.fromHand.get(0).has(EffKind.DRAW), twister.fromHand.toString());
    }

    private static int indexOf(List<Eff> effs, EffKind k) {
        for (int i = 0; i < effs.size(); i++) {
            if (effs.get(i).kind == k) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ solver-gaps-1015: model fixes, all probed

    @Test
    public void probesLearnConditionsEnteringOptionalCostsHybridAndLifeShares() {
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(
                "Library of Alexandria;Xander's Lounge;Sicarian Infiltrator;Manamorphose;Doomsday", "Swamp;Island", 20, "",
                "Island", 20, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinProbe.Stats st = new ForcedWinProbe.Stats();
        // "draw a card: activate only if you have exactly seven cards in hand": legal at hand size 7 only
        final ACard loa = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Library of Alexandria"), 1, st);
        Abil draw = null;
        for (Abil a : loa.onBattlefield) {
            if (a.has(EffKind.DRAW)) {
                draw = a;
            }
        }
        Assert.assertNotNull(draw, loa.onBattlefield.toString());
        Assert.assertFalse(draw.unplayable, draw.toString());
        Assert.assertEquals(draw.handMask, 1 << 7, draw.toString());
        // a land that enters tapped is learned by playing it
        final ACard lounge = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Xander's Lounge"), 1, st);
        Assert.assertTrue(lounge.entersTapped, lounge.note);
        final ACard swamp = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Battlefield, "Swamp"), 1, st);
        Assert.assertFalse(swamp.entersTapped);
        // ... also when first seen in our library (a pile card)
        final Card triome = createCard("Zagoth Triome", me);
        me.getZone(ZoneType.Library).add(triome);
        Assert.assertTrue(ForcedWinProbe.learn(g, me, triome, 1, st).entersTapped);
        // squad is an optional additional cost: the probe pays the printed cost only (one draw, not one per copy)
        final ACard sic = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Sicarian Infiltrator"), 1, st);
        for (Abil a : sic.fromHand) {
            if (a.has(EffKind.DRAW)) {
                Assert.assertEquals(a.effects.stream().filter(e -> e.kind == EffKind.DRAW).mapToInt(e -> e.n).sum(), 1, a.toString());
            }
        }
        // {R/G} is one red or green mana, not any mana
        final ACard morph = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Manamorphose"), 1, st);
        final Abil m = morph.fromHand.get(0);
        Assert.assertEquals(m.hybrid, List.of((1 << ForcedWinModel.R) | (1 << ForcedWinModel.G)), m.toString());
        Assert.assertEquals(m.generic, 1, m.toString());
        // "lose half your life, rounded up" is a share of the life total, seen at two life totals
        final ACard dd = ForcedWinProbe.learn(g, me, inZone(me, ZoneType.Hand, "Doomsday"), 1, st);
        Eff life = null;
        for (Eff e : dd.fromHand.get(0).effects) {
            if (e.kind == EffKind.LIFE) {
                life = e;
            }
        }
        Assert.assertNotNull(life, dd.fromHand.toString());
        Assert.assertEquals(life.lifeChange(20), -10, life.toString());
        Assert.assertEquals(life.lifeChange(9), -5, life.toString());
    }

    @Test
    public void theWidthSearchKeepsLibraryOfAlexandriasHandCondition() {
        // LoA on the battlefield, Thassa's Oracle next; with 7 cards in hand LoA draws it, with 6 it cannot
        final String lib = "Thassa's Oracle;Island";
        for (int handSize : new int[] {6, 7}) {
            final StringBuilder hand = new StringBuilder();
            for (int i = 0; i < handSize; i++) {
                hand.append(i == 0 ? "" : ";").append("Grizzly Bears");
            }
            final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(hand.toString(),
                    "Library of Alexandria;Island;Island", 20, "", "Island", 20, "human").replace(
                    "humanlibrary=" + OURLIB, "humanlibrary=" + lib));
            final Player me = g.getPlayers().get(0);
            final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
            // the seat knows its top (it put it there): pile memory
            s.notePlaced(me.getCardsIn(ZoneType.Library));
            final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
            Assert.assertNotNull(pos.knownOrder, pos.describe());
            final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
            lim.horizon = 1;
            final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, lim);
            Assert.assertEquals(!res.lines.isEmpty(), handSize == 7, handSize + " " + res.summary() + " " + pos.describe());
        }
    }

    @Test
    public void pileMemoryKnowsWhatTheSeatPutOnTopUntilAShuffle() {
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(
                "Gitaxian Probe;Thassa's Oracle;Grizzly Bears", "Island;Island;Island", 20, "", "Island", 20, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        Assert.assertNull(s.knownTop(g), "an unknown library has no known top");
        // put Thassa's Oracle back on top (a put-back of ours): the seat knows it is there
        final Card oracle = inZone(me, ZoneType.Hand, "Thassa's Oracle");
        g.getAction().moveToLibrary(oracle, 0, null);
        final List<Integer> top = s.knownTop(g);
        Assert.assertNotNull(top);
        Assert.assertEquals(top.get(0).intValue(), oracle.getId());
        // the width search plans on it: Gitaxian Probe draws the Oracle, which wins with a small library
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Library))) {
            if (c.getId() != oracle.getId() && me.getCardsIn(ZoneType.Library).size() > 2) {
                g.getAction().exile(c, null, null);
            }
        }
        final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 1;
        Assert.assertFalse(ForcedWinPlanner.plan(pos, lim).lines.isEmpty(), pos.describe());
        // a shuffle of our library forgets it; the same position then has no line
        me.shuffle(null);
        Assert.assertNull(s.knownTop(g));
        final ForcedWinModel.Position unknown = s.readPosition(g, s.knownTop(g));
        Assert.assertTrue(ForcedWinPlanner.plan(unknown, lim).lines.isEmpty(), unknown.describe());
    }

    @Test
    public void thePaymentSearchKeepsTheColourALaterStepNeeds() {
        // Underground Sea + Island, a 3-card library: Gitaxian Probe draws one, then Thassa's Oracle (UU) wins with 2
        // cards left. The greedy payment pays Probe's U/P with the Island (its least flexible blue source) and leaves no
        // UU for the Oracle; the payment search also pays the Phyrexian pip with life and finds the win.
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Gitaxian Probe;Thassa's Oracle",
                "Underground Sea;Island", 20, "", "Island", 20, "human").replace("humanlibrary=" + OURLIB,
                "humanlibrary=Island;Island;Island"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Position pos = new ForcedWinModel.Reader().read(g, me, null, true);
        final ForcedWinPlanner.Limits greedy = new ForcedWinPlanner.Limits();
        greedy.horizon = 1;
        final ForcedWinPlanner.Limits search = new ForcedWinPlanner.Limits();
        search.horizon = 1;
        search.payPolicies = ForcedWinPlanner.PAY_POLICIES;
        final ForcedWinPlanner.Result rg = ForcedWinPlanner.plan(pos, greedy), rs = ForcedWinPlanner.plan(pos, search);
        Assert.assertTrue(rg.lines.isEmpty(), rg.summary() + " " + (rg.lines.isEmpty() ? "" : rg.lines.get(0)));
        Assert.assertFalse(rs.lines.isEmpty(), rs.summary() + " " + pos.describe());
        Assert.assertTrue(rs.lines.get(0).steps.stream().anyMatch(st -> st.phyrexianLife), rs.lines.get(0).toString());
    }

    // ------------------------------------------------------------------ Part B (solver-gaps-1015): likely-win lines

    @Test
    public void likelyWinLinesAreOffByDefaultAndTheirOwnOption() {
        Assert.assertEquals(AiProps.LIKELY_WIN_LINES.getDefault(), "false");
        for (String p : AiProfileUtil.getAvailableProfiles()) {
            final LobbyPlayerAi l = new LobbyPlayerAi("x", null);
            l.setAiProfile(p);
            Assert.assertEquals(AiProfileUtil.getAIProp(l, AiProps.LIKELY_WIN_LINES), "false", p);
        }
        final LobbyPlayerAi lw = new LobbyPlayerAi("l", null);
        lw.setAiProfile(AiProfileUtil.LIKELY_WIN_PROFILE);
        Assert.assertEquals(AiProfileUtil.getAIProp(lw, AiProps.LIKELY_WIN_LINES), "true");
        Assert.assertEquals(AiProfileUtil.getAIProp(lw, AiProps.FORCED_WIN_SOLVER), "false");
        final Game g = game(AiProfileUtil.LIKELY_WIN_PROFILE, AiProfileUtil.FORCED_WIN_PROFILE,
                frame("", "Island", 20, "", "Island", 20, "human"));
        final ForcedWinSolver likely = ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
        Assert.assertNotNull(likely);
        Assert.assertFalse(likely.config().forced);
        Assert.assertTrue(likely.config().likely);
        final ForcedWinSolver forced = ((CubeComboPlayerController) g.getPlayers().get(1).getController()).cubeCombo().forcedWin();
        Assert.assertTrue(forced.config().forced);
        Assert.assertFalse(forced.config().likely);
    }

    @Test
    public void aLikelyLineIsPlayedWhenItBeatsTheSeatsOwnPlay() {
        // at 2 life against an unblockable 2/2: the seat's own play loses next turn; Show and Tell for Sneak Attack, then
        // Emrakul with haste (opponent at 30: no forced win) survives in the rollouts and is played
        final Game g = game(AiProfileUtil.LIKELY_WIN_PROFILE, "Default", "[state]\nturn=7\nactiveplayer=human\n"
                + "activephase=MAIN1\nhumanlife=2\nailife=30\nhumanlandsplayed=0\nailandsplayed=0\naihand=\nailibrary=" + LIB20
                + "\naigraveyard=\naiexile=\naibattlefield=Forest;Forest;Forest;Forest;Grizzly Bears;Grizzly Bears;"
                + "Invisible Stalker|Tapped|Counters:P1P1=1\nhumanhand=Show and Tell;Sneak Attack;Emrakul, the Aeons Torn\n"
                + "humanbattlefield=Island;Island;Island;Volcanic Island\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + OURLIB
                + "\n");
        play(g, 7);
        final ForcedWinSolver s = ((CubeComboPlayerController) g.getPlayers().get(0).getController()).cubeCombo().forcedWin();
        Assert.assertTrue(s.likelyRuns >= 1, s.summary());
        Assert.assertTrue(s.likelyTaken >= 1, s.summary());
        Assert.assertEquals(s.taken, s.likelyTaken, "no forced line here: " + s.summary());
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

    @Test(timeOut = 120000)
    public void anAttackTheRulesRefuseEndsAndGoesToForgeAi() {
        // exploit-panel-1015: the engine's "attack with everything" broke an attack requirement and the rules asked the same
        // seat again for ever (two games, 110 min in updateCombatForView). Silent Arbiter lets one creature attack: the
        // alpha strike of two is refused, the declaration goes to Forge AI (the unblockable one, lethal), and the search
        // ends within its budget. On the pf6 code this test never ends (the timeOut fails it).
        final Game g = game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("",
                "Grizzly Bears;Invisible Stalker;Forest", 20, "", "Silent Arbiter;Plains", 1, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        final long t0 = System.nanoTime();
        final ForcedWinPlanner.Line line = ForcedWinEngine.search(g, me, new ForcedWinEngine.Limits(), st,
                t0 + 3_000_000_000L, 7L);
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        Assert.assertTrue(st.refusedAttacks >= 1, st.summary());
        Assert.assertTrue(ms < 5_000, "search took " + ms + " ms: " + st.summary());
        Assert.assertNotNull(line, "Forge AI's legal attack wins: " + st.summary());
        Assert.assertEquals(line.steps.get(0).kind, AbilKind.ATTACK, line.toString());
    }

    @Test(timeOut = 120000)
    public void noStepOfAnEngineMoveRunsPastTheWall() {
        // a move still running at the search's deadline is cut (its step loop and its seats' declarations check the
        // wall); the guard ends a combat the rules refuse twice even with no wall
        final ForcedWinEngine.AttackGuard guard = new ForcedWinEngine.AttackGuard();
        final Game g = game("Default", "Default", frame("", "Grizzly Bears", 20, "", "Plains", 20, "human"));
        final forge.game.combat.Combat combat = new forge.game.combat.Combat(g.getPlayers().get(0));
        Assert.assertEquals(guard.next(combat, 0), 0);
        Assert.assertEquals(guard.next(combat, 0), 1);
        Assert.assertThrows(ForcedWinEngine.OutOfBudget.class, () -> guard.next(combat, 0));
        Assert.assertEquals(guard.total, 1);
        final forge.game.combat.Combat next = new forge.game.combat.Combat(g.getPlayers().get(0));
        Assert.assertEquals(guard.next(next, 0), 0, "a new combat starts over");
        Assert.assertThrows(ForcedWinEngine.OutOfBudget.class, () -> guard.next(next, System.nanoTime() - 1));
        Assert.assertThrows(ForcedWinEngine.OutOfBudget.class, () -> ForcedWinEngine.checkWall(System.nanoTime() - 1, "t"));
        ForcedWinEngine.checkWall(0, "no wall");
        ForcedWinEngine.checkWall(System.nanoTime() + 60_000_000_000L, "far wall");
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
