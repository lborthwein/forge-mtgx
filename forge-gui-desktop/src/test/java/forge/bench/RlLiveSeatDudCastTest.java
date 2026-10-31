package forge.bench;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.DudCast;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.LobbyPlayerPolicy;
import forge.bench.rl.PlayerControllerPolicy;
import forge.bench.rl.RlLiveSeat;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * Lane live-ai-quality-1030 (owner 2026-10-31, "Hold, extend to policy AI"): the dud-cast guard in the live policy seat.
 * Live Hard games are mostly the policy seat's (2,631 of the last 3,000 live decisions), which neither the K8 seat's guard
 * nor the policy seat's degraded K8 fallback reached. Gated by the live K8 spec's dudCast: OFF, the seat has no guard and
 * its answers are unchanged.
 */
public class RlLiveSeatDudCastTest {

    @BeforeClass
    public static void setUp() throws Exception {
        RlLiveSeatTest.setUp();
    }

    private static LookaheadSearch.Config k8(final AiFixes.Mode dud) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1031L;
        c.dudCast = dud;
        // the live config (review of #159, A3): InteractiveMain.parseLookahead ends in honest(): belief uniform over the
        // jar's live pool here (no event pool), reuse off
        forge.interactive.InteractiveMain.honest(c);
        return c;
    }

    /** A bound policy seat (seat 1) in its first main phase, with a Forge AI opponent; neither seat started a game loop. */
    private static PlayerControllerPolicy seat(final RlLiveSeat live, final AiFixes.Mode dud) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("opp", null)));
        final LobbyPlayerPolicy pol = live.lobby("Default Forge", 1, "Default");
        players.add(new RegisteredPlayer(new Deck()).setPlayer(pol));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Game game = new Game(players, rules, new Match(rules, players, "dudcast policy"));
        game.setAge(GameStage.Play);
        final Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        game.getPhaseHandler().onStackResolved();
        live.bind(game, 1, 1031L, k8(dud), AiFixes.Mode.ON);
        for (Player p : game.getPlayers()) {
            for (int i = 0; i < 20; i++) {
                add(i % 2 == 0 ? "Swamp" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        for (int i = 0; i < 4; i++) {
            add("Swamp", me, ZoneType.Battlefield);
            add("Mountain", me, ZoneType.Battlefield);
            add("Island", me, ZoneType.Battlefield);
        }
        add("Island", game.getPlayers().get(0), ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        return (PlayerControllerPolicy) me.getController();
    }

    static Card add(final String name, final Player p, final ZoneType z) {
        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        final Card c = Card.fromPaperCard(pc, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(z).add(c);
        if (z == ZoneType.Battlefield) {
            c.setSickness(false);
        }
        return c;
    }

    private static SpellAbility first(final Card c, final java.util.function.Predicate<SpellAbility> p) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (p.test(sa)) {
                sa.setActivatingPlayer(c.getController() != null ? c.getController() : c.getOwner());
                return sa;
            }
        }
        throw new AssertionError("no ability on " + c);
    }

    private static List<SpellAbility> one(final SpellAbility sa) {
        final List<SpellAbility> l = new ArrayList<>();
        l.add(sa);
        return l;
    }

    /** The policy seat's guard with the answer handed in as the policy's. */
    private static List<SpellAbility> guard(final RlLiveSeat live, final PlayerControllerPolicy c, final SpellAbility sa) {
        final LookaheadSearch g = liveGuard(live);
        Assert.assertNotNull(g, "dudCast on: the seat has a guard");
        return DudCast.guard(g, c, one(sa));
    }

    static Object why() {
        try {
            final java.lang.reflect.Field f = DudCast.class.getDeclaredField("lastWhy");
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            return e;
        }
    }

    static LookaheadSearch liveGuard(final RlLiveSeat live) {
        try {
            final java.lang.reflect.Method m = RlLiveSeat.class.getDeclaredMethod("dudGuard");
            m.setAccessible(true);
            return (LookaheadSearch) m.invoke(live);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void offTheSeatHasNoGuard() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(RlLiveSeatTest.SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(RlLiveSeatTest.spec(svc.address(), "worlds=2,threads=1,budgetMs=4000"), "t");
            seat(live, AiFixes.Mode.OFF);
            Assert.assertNull(liveGuard(live), "dudCast off: no guard, the policy's answers unchanged");
            live.finish();
        }
    }

    /** True duds the policy chose: vetoed, Forge AI asked again (bounded), never the dud. */
    @Test
    public void thePolicySeatVetoesTrueDuds() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(RlLiveSeatTest.SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(RlLiveSeatTest.spec(svc.address(), "worlds=2,threads=1,budgetMs=4000"), "t");
            final PlayerControllerPolicy c = seat(live, AiFixes.Mode.ON);
            final Player me = c.getPlayer();
            final Player opp = c.getGame().getPlayers().get(0);
            final Card cc = add("Currency Converter", me, ZoneType.Battlefield);
            final Card exhume = add("Exhume", me, ZoneType.Hand);
            final Card push = add("Fatal Push", me, ZoneType.Hand);
            final Card seeker = add("Spellseeker", opp, ZoneType.Battlefield);
            c.getGame().getAction().checkStateEffects(true);
            final SpellAbility ccx = first(cc, sa -> sa.isActivatedAbility() && sa.getDescription().contains("exiled with"));
            final SpellAbility ex = first(exhume, SpellAbility::isSpell);
            final SpellAbility fp = first(push, SpellAbility::isSpell);
            fp.getTargets().add(seeker);
            for (SpellAbility dud : new SpellAbility[] {ccx, ex, fp}) {
                if (dud == fp) {
                    // the earlier re-asks ran Forge AI over every hand card, which re-targets Fatal Push's ability object
                    fp.resetTargets();
                    fp.getTargets().add(seeker);
                }
                final List<SpellAbility> a = guard(live, c, dud);
                System.err.println("[dud-test] policy seat " + dud.getHostCard().getName() + " -> "
                        + (a == null || a.isEmpty() ? "pass" : a.get(0).getHostCard().getName()) + " why " + why());
                Assert.assertFalse(a != null && !a.isEmpty() && a.get(0) == dud, "vetoed: " + dud);
            }
            Assert.assertEquals(liveGuard(live).getStats().dudVetoed, 3);
            live.finish();
        }
    }

    /** Real plays the policy chose are never vetoed: Plea for Power, Polygraph Orb, Sundial of the Infinite with Sneak Attack. */
    @Test
    public void thePolicySeatNeverVetoesRealPlays() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(RlLiveSeatTest.SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(RlLiveSeatTest.spec(svc.address(), "worlds=2,threads=1,budgetMs=4000"), "t");
            final PlayerControllerPolicy c = seat(live, AiFixes.Mode.ON);
            final Player me = c.getPlayer();
            final Player opp = c.getGame().getPlayers().get(0);
            add("Lightning Bolt", opp, ZoneType.Hand);
            add("Grizzly Bears", opp, ZoneType.Battlefield);
            final Card plea = add("Plea for Power", me, ZoneType.Hand);
            final Card orb = add("Polygraph Orb", me, ZoneType.Battlefield);
            for (int i = 0; i < 3; i++) {
                add("Grizzly Bears", me, ZoneType.Graveyard);
            }
            final Card sundial = add("Sundial of the Infinite", me, ZoneType.Battlefield);
            add("Sneak Attack", me, ZoneType.Battlefield);
            c.getGame().getAction().checkStateEffects(true);
            for (SpellAbility real : new SpellAbility[] {first(plea, SpellAbility::isSpell),
                    first(orb, sa -> sa.getApi() == ApiType.GenericChoice), first(sundial, sa -> sa.getApi() == ApiType.EndTurn)}) {
                final List<SpellAbility> a = guard(live, c, real);
                Assert.assertTrue(a != null && a.size() == 1 && a.get(0) == real, "not vetoed: " + real);
            }
            Assert.assertEquals(liveGuard(live).getStats().dudVetoed, 0);
            live.finish();
        }
    }

    /** Whole games, normal path and degraded K8 fallback: dudCast on, the guard runs in each; off, no trace of it. */
    @Test
    public void wholeGamesNormalAndDegradedPaths() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(RlLiveSeatTest.SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(RlLiveSeatTest.spec(svc.address(), "worlds=2,threads=2,budgetMs=4000"), "t");
            final String err = RlLiveSeatTest.captureErr(() -> RlLiveSeatTest.play(live, 722159001L, null));
            Assert.assertFalse(err.contains("\"dudCast\""), "OFF: no guard stats");
            Assert.assertFalse(err.contains("[dud-cast]"), "OFF: no guard lines");
        }
        try (FakeSearchService svc = new FakeSearchService(RlLiveSeatTest.SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(RlLiveSeatTest.spec(svc.address(), "worlds=2,threads=2,budgetMs=4000"), "t");
            final String err = RlLiveSeatTest.captureErr(() -> RlLiveSeatTest.play(live, 722159001L, k -> { forge.interactive.InteractiveMain.honest(k); k.dudCast = AiFixes.Mode.ON; }));
            final String end = err.lines().filter(l -> l.startsWith("[policy-seat] {\"end\":true")).findFirst().orElse("");
            System.err.println("[dud-test] policy normal end: " + end);
            Assert.assertFalse(live.degraded());
            Assert.assertTrue(end.contains("\"dudCast\":{\"seat\":"), "the normal path ran the guard: " + end);
            // review of #159, S2-a: what the session, journal and tape show after a veto
            final long vetoedLines = err.lines().filter(l -> l.startsWith("[lookahead-decision] ")
                    && l.contains("\"vetoed\":true")).count();
            final long keptLines = err.lines().filter(l -> l.startsWith("[lookahead-decision] ")
                    && l.contains("\"vetoed\":false,\"playedBy\":\"policy\"")).count();
            final long vetoLines = err.lines().filter(l -> l.startsWith("[dud-cast] veto ")).count();
            System.err.println("[dud-test] policy lines: vetoed " + vetoedLines + " kept " + keptLines + " veto " + vetoLines);
            Assert.assertTrue(vetoedLines > 0, "a vetoed decision says so on its decision line (vetoed, playedBy)");
            Assert.assertTrue(err.lines().filter(l -> l.contains("\"vetoed\":true")).allMatch(
                    l -> l.contains("\"playedBy\":\"forge\"") || l.contains("\"playedBy\":\"pass\"")), "playedBy on a veto");
            Assert.assertTrue(keptLines > 0, "an answer the guard kept says playedBy policy");
            // the seat's answerer marked each veto overridden (its GAME_END and tape list): the end line's count
            Assert.assertTrue(end.contains("\"overrides\":" + vetoedLines), "overrides == vetoed lines: " + end);
        }
        try (FakeSearchService svc = new FakeSearchService(RlLiveSeatTest.SHA)) {
            svc.failAfterDecides = 150;
            final RlLiveSeat live = RlLiveSeat.connect(RlLiveSeatTest.spec(svc.address(), "worlds=2,threads=2,budgetMs=4000"), "t");
            final String err = RlLiveSeatTest.captureErr(() -> RlLiveSeatTest.play(live, 722159002L, k -> { forge.interactive.InteractiveMain.honest(k); k.dudCast = AiFixes.Mode.ON; }));
            final String end = err.lines().filter(l -> l.startsWith("[policy-seat] {\"end\":true")).findFirst().orElse("");
            System.err.println("[dud-test] policy degraded end: " + end);
            Assert.assertTrue(live.degraded());
            Assert.assertTrue(end.contains("\"k8\":{\"mode\":\"on\""), "the degraded K8 fallback ran the guard: " + end);
        }
    }
}
