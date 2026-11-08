package forge.ai.simulation;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * pr209-followups: #212's copy sites that build their own belief world (the solver's honest probe root, the honest
 * label's world, the R3 label search's belief world) flag it with {@code staleStatics} when their spec's key is on, so a
 * later copy of the world skips static effects naming the swapped-out cards. Key off: the world is unflagged, as before,
 * and a copy of it still throws "Couldn't map". Synthetic fixtures; they never become corpus rows.
 */
public class StaleStaticsSitesTest extends SimulationTest {

    static final String STATIC_CARD = "Grist, the Hunger Tide"; // a static that works from every zone (as StaleStaticsTest)

    private Game live() {
        final Game game = initAndCreateGame();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0));
        final Player me = game.getPlayers().get(0);
        final Player opp = game.getPlayers().get(1);
        for (String c : new String[] {"Island", "Mountain", "Forest"}) {
            addCardToZone(c, me, ZoneType.Library);
        }
        addCardToZone(STATIC_CARD, opp, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** The belief re-draw's effect on a copy: the opponent's hidden library leaves the zone lists. */
    static final ForcedWinSolver.Redraw SWAP = (l, m, g, cm, rng) -> {
        for (Player o : cm.getOpponents()) {
            o.getZone(ZoneType.Library).setCards(new ArrayList<Card>());
        }
    };

    /** A later copy of {@code world} (a solver node or play-out): null = it copied, else the error. */
    static String copyOf(final Game world) {
        try {
            new GameCopier(world, true).makeCopy();
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    static void offThrowsOnCopies(final Game world) {
        Assert.assertFalse(world.staleStatics, "key off: the world is not flagged");
        final String why = copyOf(world);
        Assert.assertNotNull(why, "key off: a copy of the world still throws");
        Assert.assertTrue(why.startsWith("Couldn't map Grist"), why);
    }

    static void onCopies(final Game world) {
        Assert.assertTrue(world.staleStatics, "key on: the world is flagged");
        Assert.assertNull(copyOf(world), "key on: a copy of the world skips the swapped-out card's statics");
    }

    @Test
    public void specKeyIsReadAsTheSeatsSolverReadsIt() {
        Assert.assertFalse(ForcedWinSolver.Config.staleStaticsIn(null));
        Assert.assertFalse(ForcedWinSolver.Config.staleStaticsIn(""));
        Assert.assertFalse(ForcedWinSolver.Config.staleStaticsIn("budget=counter,staleStatics=false"));
        Assert.assertTrue(ForcedWinSolver.Config.staleStaticsIn("budget=counter, staleStatics = true"));
        Assert.assertFalse(ForcedWinSolver.Config.staleStaticsIn("staleStatics=true,staleStatics=false"), "the last wins");
        Assert.assertTrue(ForcedWinSolver.Config.fromProps(k -> "staleStatics".equals(k) ? "true" : null).staleStatics);
    }

    private Game probeRoot(final boolean key) {
        final Game live = live();
        final ForcedWinSolver.Config cfg = new ForcedWinSolver.Config();
        cfg.hideOwnLibrary = true;
        cfg.staleStatics = key;
        final ForcedWinSolver s = new ForcedWinSolver(live.getPlayers().get(0), cfg);
        s.redraw = SWAP;
        return s.honestProbeRoot(live, null);
    }

    @Test
    public void honestProbeRootFlagsItsWorldOnlyWithTheKey() {
        offThrowsOnCopies(probeRoot(false));
        onCopies(probeRoot(true));
    }

    private Game honestLabelWorld(final boolean key) throws Exception {
        final Game live = live();
        final Player me = live.getPlayers().get(0), opp = live.getPlayers().get(1);
        final Path file = Files.createTempFile("stale-sites-cube-", ".json");
        file.toFile().deleteOnExit();
        Files.writeString(file, "{\"schema\":\"mtgx-belief-cube/1\",\"cards\":["
                + "{\"name\":\"Counterspell\",\"tags\":[]},{\"name\":\"Shock\",\"tags\":[]},"
                + "{\"name\":\"Grizzly Bears\",\"tags\":[]}]}");
        final HonestLabel.Spec spec = new HonestLabel.Spec();
        spec.redraw = "belief";
        spec.solverCfg.addProperty("hideOwnLibrary", "true");
        spec.lossCfg.addProperty("hideOwnLibrary", "true");
        if (key) {
            spec.solverCfg.addProperty("staleStatics", "true");
        }
        spec.beliefCube = file.toString();
        spec.beliefCubeSha256 = null; // a test-owned pool, never a corpus configuration
        spec.beliefBasics = 2;
        spec.beliefSingleton = false;
        final HonestLabel.Seat seat = new HonestLabel.Seat(spec);
        final Method world = HonestLabel.Seat.class.getDeclaredMethod("world", Game.class, Player.class, Player.class,
                Random.class);
        world.setAccessible(true);
        IdScope.open();
        try {
            final Game g = (Game) ((Object[]) world.invoke(seat, live, me, opp, new Random(7)))[0];
            for (Card c : g.getPlayers().get(1).getCardsIn(ZoneType.Library)) {
                Assert.assertNotEquals(c.getName(), STATIC_CARD, "the belief re-drew the opponent's library");
            }
            return g;
        } finally {
            IdScope.close();
        }
    }

    @Test
    public void honestLabelWorldFlagsItsWorldOnlyWithTheKey() throws Exception {
        offThrowsOnCopies(honestLabelWorld(false));
        onCopies(honestLabelWorld(true));
    }

    private Game r3World(final String solverSpec) {
        final Game live = live();
        final Player me = live.getPlayers().get(0);
        final com.google.gson.JsonObject o = R3LabelSearchTest.base();
        final R3LabelSearch.Spec spec = R3LabelSearchTest.spec(o);
        spec.solverSpec = solverSpec;
        final R3LabelSearch.Env base = R3LabelSearchTest.env(R3LabelSearchTest.flat(0.5), null);
        final R3LabelSearch.Env env = new R3LabelSearch.Env() {
            @Override
            public ChooseACard.Valuer ranker() {
                return base.ranker();
            }

            @Override
            public HonestLabel.Seat proof() {
                return null;
            }

            @Override
            public ForcedWinSolver.Redraw redraw() {
                return SWAP;
            }

            @Override
            public Set<Integer> known(final Player p) {
                return new HashSet<>();
            }
        };
        final R3LabelSearch r3 = new R3LabelSearch(spec, env);
        final R3LabelSearch.World w = r3.new World(false, 11L, -1, -1, null, null, new HashSet<>(), new HashSet<>());
        final GameCopier copier = new GameCopier(live, true);
        copier.setCopyFidelity(1);
        final Game g = copier.makeCopy();
        w.make(live, me, g, (Player) copier.find(me));
        return g;
    }

    @Test
    public void r3BeliefWorldFlagsItsWorldOnlyWithTheSeatsKey() {
        offThrowsOnCopies(r3World(null));
        offThrowsOnCopies(r3World(forge.ai.AiProfileUtil.HONEST_FORCED_WIN_COUNTED_SPEC));
        onCopies(r3World(forge.ai.AiProfileUtil.HONEST_FORCED_WIN_COUNTED_SPEC + ",staleStatics=true"));
    }

    /** The R3 truth world keeps the real cards (only libraries shuffle): never flagged, whatever the key. */
    @Test
    public void r3TruthWorldIsNeverFlagged() {
        final Game live = live();
        final Player me = live.getPlayers().get(0);
        final R3LabelSearch.Spec spec = R3LabelSearchTest.spec(R3LabelSearchTest.base());
        spec.solverSpec = "staleStatics=true";
        final R3LabelSearch r3 = new R3LabelSearch(spec, R3LabelSearchTest.env(R3LabelSearchTest.flat(0.5), null));
        final R3LabelSearch.World w = r3.new World(true, 11L, -1, -1, null, null, new HashSet<>(), new HashSet<>());
        final GameCopier copier = new GameCopier(live, true);
        copier.setCopyFidelity(1);
        final Game g = copier.makeCopy();
        w.make(live, me, g, (Player) copier.find(me));
        Assert.assertFalse(g.staleStatics);
        Assert.assertNull(copyOf(g));
        Assert.assertEquals(g.getPlayers().get(1).getCardsIn(ZoneType.Library).size(), 1);
    }
}
