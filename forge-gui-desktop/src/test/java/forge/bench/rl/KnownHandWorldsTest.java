package forge.bench.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonObject;

import forge.ai.simulation.BeliefSampler;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.InfoStates;
import forge.ai.simulation.LookaheadSearch;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * attack-holdback-1118 (report 2026-11-18T14-37-11): an opponent card that publicly returned to its owner's hand (the
 * human's Kolaghan, the Storm's Fury: cast with dash from a public return, back to hand at end of turn). The probe
 * records, with the knownHand key absent, which world dealers keep it where it is; the other tests show the knownHand
 * key ({@link InfoStates#trackHands}, {@link LookaheadSearch.Config#knownHand}): every dealer keeps a card the seat saw
 * enter the hand, never an unseen draw, forgets a hand shuffled into the library or changed without an event, and with
 * the key off deals every world exactly as before.
 */
public class KnownHandWorldsTest extends SimulationTest {

    static final String DRAGON = "Kolaghan, the Storm's Fury";

    static JsonObject beliefSpec() {
        final JsonObject spec = new JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        spec.addProperty("belief", "uniform");
        spec.addProperty("beliefCube", BeliefSampler.LIVE_CUBE);
        spec.addProperty("beliefCubeSha256", BeliefSampler.LIVE_CUBE_SHA256);
        return spec;
    }

    /** The number of seeds (of {@code n}) whose world keeps card {@code id} (the same card) in the opponent's hand. */
    static int kept(final Game live, final Player liveMe, final int id, final int n, final Dealer d) throws Exception {
        final String name = live.findById(id).getName();
        int k = 0;
        for (int s = 0; s < n; s++) {
            final GameCopier copier = new GameCopier(live, true);
            final Game copy = copier.makeCopy();
            final Player me = (Player) copier.find(liveMe);
            d.deal(live, liveMe, copy, me, new Random(1000 + s));
            final Card c = copy.findById(id);
            if (c != null && c.isInZone(ZoneType.Hand) && name.equals(c.getName())) {
                k++;
            }
        }
        return k;
    }

    interface Dealer {
        void deal(Game live, Player liveMe, Game g, Player me, Random rng) throws Exception;
    }

    static java.lang.reflect.Method staticResample(final boolean info) throws Exception {
        final java.lang.reflect.Method m = info
                ? LookaheadSearch.class.getDeclaredMethod("resample", Game.class, Player.class, Game.class, Player.class,
                        Random.class, InfoStates.class)
                : LookaheadSearch.class.getDeclaredMethod("resample", Game.class, Player.class, Game.class, Player.class,
                        Random.class);
        m.setAccessible(true);
        return m;
    }

    /**
     * The verification asked for first: a card that moved from the opponent's battlefield (or graveyard) to their hand
     * keeps its id, and {@code canBeShownTo} does not show it to us. Recorded per dealer, the key absent.
     */
    @Test
    public void probePubliclyReturnedCardWithTheKeyAbsent() throws Exception {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        fillLibrary(opp, 30);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Island", opp, ZoneType.Hand);
        }
        final RlKnowledge knowledge = new RlKnowledge(game);
        knowledge.v2 = true;
        knowledge.attach();
        final InfoStates is = InfoStates.attach(game);
        final Card dragon = addCard(DRAGON, opp);
        final int id = dragon.getId();
        game.getAction().moveToHand(dragon, null);
        final Card inHand = game.findById(id);
        assertNotNull(inHand, "the id survives battlefield -> hand");
        assertTrue(inHand.isInZone(ZoneType.Hand));
        assertEquals(inHand.getName(), DRAGON);
        final Card angel = addCardToZone("Serra Angel", opp, ZoneType.Graveyard);
        final int angelId = angel.getId();
        game.getAction().moveToHand(angel, null);
        assertNotNull(game.findById(angelId), "the id survives graveyard -> hand");
        assertTrue(game.findById(angelId).isInZone(ZoneType.Hand));
        assertFalse(inHand.getView().canBeShownTo(me.getView()), "canBeShownTo does not show a publicly returned card");
        assertTrue(knowledge.knows(knowledge.seatOf(me), inHand), "the live seat's tracker (RlKnowledge) learnt it");

        final int n = 16;
        final int plain = kept(game, me, id, n, (l, lm, g, m, r) -> staticResample(false).invoke(null, l, lm, g, m, r));
        final int info = kept(game, me, id, n, (l, lm, g, m, r) -> staticResample(true).invoke(null, l, lm, g, m, r, is));
        final RlSearch search = new RlSearch(RlSearch.Config.parse(beliefSpec()), 1, 2, null, knowledge, "jar", "probe", 2);
        try {
            final int uniform = kept(game, me, id, n, (l, lm, g, m, r) -> search.ls().opponentRedraw().apply(l, lm, g, m, r));
            final Map<String, Map<String, Double>> syn = Map.of(DRAGON, Map.of("Island", 0.5));
            final int prior = kept(game, me, id, n,
                    (l, lm, g, m, r) -> search.ls().opponentRedrawPrior(syn, 20, false, 4).apply(l, lm, g, m, r));
            System.err.println("[knownhand-probe] key absent, beliefRememberedHand=" + search.config().beliefRememberedHand
                    + ": kept in " + n + " worlds: resample=" + plain + " resample(infoState)=" + info
                    + " uniformBelief=" + uniform + " defendPrior=" + prior);
            assertTrue(plain < n, "resample (canBeShownTo only) deals it away in some world");
            assertTrue(info < n, "resample with infoState (canBeShownTo only) deals it away in some world");
            assertTrue(prior < n, "the defend prior (canBeShownTo only) deals it away in some world");
            assertEquals(uniform, search.config().beliefRememberedHand ? n : uniform,
                    "the uniform belief re-draw keeps it through beliefRememberedHand (the live tracker)");
        } finally {
            search.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ knownHand on

    static JsonObject knownSpec(final boolean belief, final boolean known) {
        final JsonObject spec = belief ? beliefSpec() : new JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        spec.addProperty("infoState", true);
        if (known) {
            spec.addProperty("knownHand", true);
        }
        return spec;
    }

    static final class Pos {
        Game game;
        Player me, opp;
        InfoStates is;
        int dragon, angel, drawn;
    }

    /**
     * The opponent's hand: three Islands it held before tracking began (unknown), Kolaghan returned from its battlefield
     * and Serra Angel from its graveyard (both seen entering), and a Lightning Bolt drawn unseen from its library.
     */
    private Pos position(final boolean track) {
        final Pos p = new Pos();
        p.game = initAndCreateGame();
        p.me = p.game.getPlayers().get(0);
        p.opp = p.game.getPlayers().get(1);
        p.game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.me);
        fillLibrary(p.opp, 30);
        fillLibrary(p.me, 30);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Island", p.opp, ZoneType.Hand);
        }
        addCardToZone("Lightning Bolt", p.opp, ZoneType.Library); // placed before tracking begins
        p.is = InfoStates.attach(p.game);
        if (track) {
            p.is.trackHands();
        }
        final Card dragon = addCard(DRAGON, p.opp);
        p.dragon = dragon.getId();
        p.game.getAction().moveToHand(dragon, null);
        final Card angel = addCardToZone("Serra Angel", p.opp, ZoneType.Graveyard);
        p.angel = angel.getId();
        p.game.getAction().moveToHand(angel, null);
        // the bolt to the top, then drawn: nobody but its owner saw it
        final Card bolt = p.opp.getCardsIn(ZoneType.Library).stream().filter(c -> c.getName().equals("Lightning Bolt"))
                .findFirst().orElseThrow();
        p.game.getAction().moveToLibrary(bolt, 0, null);
        p.drawn = bolt.getId();
        p.opp.drawCard();
        assertTrue(p.game.findById(p.drawn).isInZone(ZoneType.Hand), "the bolt was drawn");
        return p;
    }

    /** One seed's world: the opponent's hand ids and library ids in order (the world's fingerprint). */
    static String world(final Game live, final Player liveMe, final Random rng, final Dealer d) throws Exception {
        final GameCopier copier = new GameCopier(live, true);
        final Game copy = copier.makeCopy();
        final Player me = (Player) copier.find(liveMe);
        d.deal(live, liveMe, copy, me, rng);
        final StringBuilder sb = new StringBuilder();
        for (Player o : copy.getPlayers()) {
            if (o.getId() == me.getId()) {
                continue;
            }
            sb.append("H");
            for (Card c : o.getCardsIn(ZoneType.Hand)) {
                sb.append(' ').append(c.getId()).append(':').append(c.getName());
            }
            sb.append(" L");
            for (Card c : o.getCardsIn(ZoneType.Library)) {
                sb.append(' ').append(c.getId()).append(':').append(c.getName());
            }
        }
        return sb.toString();
    }

    static Dealer redrawOf(final RlSearch s) throws Exception {
        final java.lang.reflect.Method m = LookaheadSearch.class.getDeclaredMethod("redraw", Game.class, Player.class,
                Game.class, Player.class, Random.class);
        m.setAccessible(true);
        return (l, lm, g, me, r) -> m.invoke(s.ls(), l, lm, g, me, r);
    }

    static final Map<String, Map<String, Double>> SYN = Map.of(DRAGON, Map.of("Island", 0.5));

    /** The dealers of the worlds, by name, for search {@code s} (belief on) and {@code r} (no belief: resample). */
    static Map<String, Dealer> dealers(final RlSearch s, final RlSearch r) throws Exception {
        final Map<String, Dealer> m = new java.util.LinkedHashMap<>();
        m.put("uniform", (l, lm, g, me, x) -> s.ls().opponentRedraw().apply(l, lm, g, me, x));
        m.put("defendPrior", (l, lm, g, me, x) -> s.ls().opponentRedrawPrior(SYN, 20, true, 4).apply(l, lm, g, me, x));
        m.put("deckBelief", (l, lm, g, me, x) -> s.ls().opponentRedrawDeck(new forge.ai.simulation.DeckBelief.Params())
                .apply(l, lm, g, me, x));
        m.put("resample(infoState)", redrawOf(r));
        return m;
    }

    /** The solver's re-draws (their world filter keeps what {@code remembersHandCard} says), by name. */
    static Map<String, forge.ai.simulation.ForcedWinSolver.Redraw> solverRedraws(final RlSearch s) {
        final Map<String, forge.ai.simulation.ForcedWinSolver.Redraw> m = new java.util.LinkedHashMap<>();
        m.put("uniform", s.ls().opponentRedraw());
        m.put("defendPrior", s.ls().opponentRedrawPrior(SYN, 20, true, 4));
        m.put("deckBelief", s.ls().opponentRedrawDeck(new forge.ai.simulation.DeckBelief.Params()));
        m.put("solver", s.ls().solverRedraw());
        return m;
    }

    /**
     * Key on: the returned Kolaghan and Serra Angel stay in the opponent's hand in every world of every dealer (and the
     * solver's re-draws remember them); the unseen draw is dealt away in some world of every dealer. No live tracker
     * (RlKnowledge) is attached: InfoState alone knows them.
     */
    @Test
    public void knownHandKeepsAPubliclyReturnedCardInEveryWorld() throws Exception {
        final Pos p = position(true);
        final Card dragon = p.game.findById(p.dragon);
        assertNotNull(dragon, "the id survives battlefield -> hand");
        assertEquals(dragon.getName(), DRAGON);
        assertTrue(dragon.isInZone(ZoneType.Hand));
        assertTrue(p.is.knowsInHand(p.me, dragon), "InfoState(me) saw Kolaghan enter the hand");
        assertTrue(p.is.knowsInHand(p.me, p.game.findById(p.angel)), "and Serra Angel");
        assertFalse(p.is.knowsInHand(p.me, p.game.findById(p.drawn)), "not the unseen draw");
        assertFalse(p.is.knowsInHand(p.opp, dragon), "an owner's own hand is not tracked");
        assertEquals(p.is.knownHand(p.me, p.opp), new java.util.TreeSet<>(List.of(p.dragon, p.angel)));
        final RlSearch s = new RlSearch(RlSearch.Config.parse(knownSpec(true, true)), 1, 2, null, null, "jar", "known", 2);
        final RlSearch r = new RlSearch(RlSearch.Config.parse(knownSpec(false, true)), 1, 2, null, null, "jar", "known", 2);
        try {
            assertTrue(s.ls().getConfig().knownHand && r.ls().getConfig().knownHand, "the key reaches the look-ahead");
            final int n = 16;
            final List<String> rows = new ArrayList<>();
            for (Map.Entry<String, Dealer> e : dealers(s, r).entrySet()) {
                final int kd = kept(p.game, p.me, p.dragon, n, e.getValue());
                final int ka = kept(p.game, p.me, p.angel, n, e.getValue());
                final int kb = kept(p.game, p.me, p.drawn, n, e.getValue());
                rows.add(e.getKey() + ": dragon " + kd + " angel " + ka + " drawn " + kb);
                assertEquals(kd, n, e.getKey() + " keeps Kolaghan in every world");
                assertEquals(ka, n, e.getKey() + " keeps Serra Angel in every world");
                assertTrue(kb < n, e.getKey() + " deals the unseen draw away in some world (" + kb + "/" + n + ")");
            }
            for (Map.Entry<String, forge.ai.simulation.ForcedWinSolver.Redraw> e : solverRedraws(s).entrySet()) {
                assertTrue(e.getValue().remembersHandCard(p.game, p.me, dragon), e.getKey() + " remembers Kolaghan");
                assertFalse(e.getValue().remembersHandCard(p.game, p.me, p.game.findById(p.drawn)),
                        e.getKey() + " does not remember the unseen draw");
            }
            System.err.println("[knownhand-on] kept of " + n + " worlds: " + rows);
        } finally {
            s.close();
            r.close();
        }
    }

    /** The hand shuffled into the library (each card leaves the hand, the library is shuffled, a new hand drawn). */
    @Test
    public void aHandShuffledIntoTheLibraryIsForgotten() throws Exception {
        final Pos p = position(true);
        assertEquals(p.is.knownHand(p.me, p.opp).size(), 2);
        final int size = p.opp.getCardsIn(ZoneType.Hand).size();
        for (Card c : new ArrayList<>(p.opp.getCardsIn(ZoneType.Hand))) {
            p.game.getAction().moveToLibrary(c, null);
        }
        p.opp.shuffle(null);
        assertTrue(p.is.knownHand(p.me, p.opp).isEmpty(), "every card left the hand");
        p.opp.drawCards(size);
        // whatever was drawn (Kolaghan included, if it came back), nothing of the new hand is known
        assertTrue(p.is.knownHand(p.me, p.opp).isEmpty(), "the new hand is unknown: " + p.is.knownHand(p.me, p.opp));
        for (Card c : p.opp.getCardsIn(ZoneType.Hand)) {
            assertFalse(p.is.knowsInHand(p.me, c), c.getName());
        }
    }

    /** A hand that changed with no event (a card added straight to the zone) is forgotten whole: fail closed. */
    @Test
    public void anUnexplainedHandChangeForgetsTheHand() throws Exception {
        final Pos p = position(true);
        assertEquals(p.is.knownHand(p.me, p.opp).size(), 2);
        addCardToZone("Shock", p.opp, ZoneType.Hand); // no zone-change event
        assertFalse(p.is.knowsInHand(p.me, p.game.findById(p.dragon)), "Kolaghan's identity is no longer trusted");
        assertTrue(p.is.knownHand(p.me, p.opp).isEmpty());
        assertTrue(p.is.handForgot > 0);
        // what it sees enter afterwards is learnt again
        final Card again = addCard("Serra Angel", p.opp);
        p.game.getAction().moveToHand(again, null);
        assertTrue(p.is.knowsInHand(p.me, p.game.findById(again.getId())), "a later public return is learnt");
    }

    /** A hand card revealed to us (an effect that reveals it) is learnt; a look by the owner alone is not ours. */
    @Test
    public void aRevealedHandCardIsKnown() throws Exception {
        final Pos p = position(true);
        final Card drawn = p.game.findById(p.drawn);
        assertFalse(p.is.knowsInHand(p.me, drawn));
        p.game.getAction().revealTo(new forge.game.card.CardCollection(drawn), List.of(p.opp));
        assertFalse(p.is.knowsInHand(p.me, drawn), "revealed to its owner only");
        p.game.getAction().reveal(new forge.game.card.CardCollection(drawn), p.opp);
        assertTrue(p.is.knowsInHand(p.me, drawn), "revealed to every player");
    }

    /**
     * Key off (absent): every dealer deals exactly the same worlds whether or not InfoState tracks the hands, and the
     * returned card is dealt as before (the uniform belief through the live tracker only, so not here; resample and the
     * defend prior through canBeShownTo only). The look-ahead's config JSON has no knownHand.
     */
    @Test
    public void keyOffDealsEveryWorldAsBefore() throws Exception {
        final Pos untracked = position(false);
        final Pos tracked = position(true);
        assertTrue(tracked.is.knowsInHand(tracked.me, tracked.game.findById(tracked.dragon)), "tracking is on");
        final RlSearch s = new RlSearch(RlSearch.Config.parse(knownSpec(true, false)), 1, 2, null, null, "jar", "off", 2);
        final RlSearch r = new RlSearch(RlSearch.Config.parse(knownSpec(false, false)), 1, 2, null, null, "jar", "off", 2);
        try {
            assertFalse(s.ls().getConfig().knownHand);
            assertFalse(s.ls().getConfig().toJson().has("knownHand"), "the look-ahead config JSON is unchanged while off");
            assertFalse(RlSearch.Config.parse(knownSpec(true, false)).toJson().has("knownHand"));
            assertFalse(RlSearch.Config.parse(knownSpec(true, false)).toJson().has("attacks"));
            final int n = 8;
            for (Map.Entry<String, Dealer> e : dealers(s, r).entrySet()) {
                for (int i = 0; i < n; i++) {
                    final String a = world(untracked.game, untracked.me, new Random(77 + i), e.getValue());
                    final String b = world(tracked.game, tracked.me, new Random(77 + i), e.getValue());
                    assertEquals(b, a, e.getKey() + " world " + i + " is the same with tracking on and the key off");
                }
                assertTrue(kept(tracked.game, tracked.me, tracked.dragon, n, e.getValue()) < n,
                        e.getKey() + ": without the key (and no live tracker) the returned card is dealt as before");
            }
            for (Map.Entry<String, forge.ai.simulation.ForcedWinSolver.Redraw> e : solverRedraws(s).entrySet()) {
                assertFalse(e.getValue().remembersHandCard(tracked.game, tracked.me, tracked.game.findById(tracked.dragon)),
                        e.getKey() + ": the key off remembers nothing new");
            }
        } finally {
            s.close();
            r.close();
        }
    }

    /** The bench spec: knownHand needs infoState; attacks is 0 or 1 and needs policy play-outs. */
    @Test
    public void benchSpecKeysAreChecked() {
        final JsonObject noInfo = new JsonObject();
        noInfo.addProperty("server", "127.0.0.1:1");
        noInfo.addProperty("knownHand", true);
        expectThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(noInfo));
        final JsonObject forge = new JsonObject();
        forge.addProperty("server", "127.0.0.1:1");
        forge.addProperty("attacks", 1);
        expectThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(forge));
        final JsonObject two = new JsonObject();
        two.addProperty("server", "127.0.0.1:1");
        two.addProperty("playout", "policy");
        two.addProperty("attacks", 2);
        expectThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(two));
        final JsonObject on = new JsonObject();
        on.addProperty("server", "127.0.0.1:1");
        on.addProperty("playout", "policy");
        on.addProperty("attacks", 1);
        on.addProperty("infoState", true);
        on.addProperty("knownHand", true);
        final RlSearch.Config c = RlSearch.Config.parse(on);
        assertEquals(c.attacks, 1);
        assertTrue(c.knownHand);
        assertEquals(c.toJson().get("attacks").getAsInt(), 1);
        assertTrue(c.toJson().get("knownHand").getAsBoolean());
    }
}
