package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * S1 (lane s1-search-1007): {@link LookaheadSearch#decideGiven} over a caller-given candidate set and the learned hooks.
 * The live game is only read; the given order is kept (default first, duplicates and breadth honoured); a value leaf
 * is taken at the seat's first priority in the horizon turn and replaces the static values; a policy play-out seat gets
 * the candidate as its first action, once per play-out, with the world's seed.
 */
public class LookaheadGivenTest extends SimulationTest {

    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
            for (int i = 0; i < 4; i++) {
                addCard("Mountain", p);
                addCard("Forest", p);
            }
            addCard("Grizzly Bears", p).setSickness(false);
        }
        for (String n : new String[] {"Forest", "Hill Giant", "Grizzly Bears", "Lightning Bolt"}) {
            addCardToZone(n, a, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch.Config config() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 2;
        c.threads = 1;
        c.seed = 721_580_900L;
        return c;
    }

    /** The given set: pass first (the default), then every distinct legal play of seat a, in Forge's order. */
    private static List<SpellAbility> given(Player a) {
        final List<SpellAbility> g = new ArrayList<>();
        g.add(null);
        g.addAll(new SpellAbilityPicker(a).getCandidateSpellsAndAbilities());
        return g;
    }

    @Test
    public void givenSetIsSearchedInOrderAndTheLiveGameIsUntouched() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        List<SpellAbility> g = given(a);
        AssertJUnit.assertTrue("the board needs at least 4 legal plays", g.size() >= 5);
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = new LookaheadSearch(config());
        try {
            LookaheadSearch.GivenResult r = s.decideGiven(game, a, g, 3);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertEquals(3, r.givenIndex.length);
            AssertJUnit.assertEquals(0, r.givenIndex[0]);
            AssertJUnit.assertTrue(r.givenIndex[1] > 0 && r.givenIndex[2] > r.givenIndex[1]);
            AssertJUnit.assertEquals(1, s.getStats().searched);
            AssertJUnit.assertEquals(3 * 2, s.getStats().rollouts);
            AssertJUnit.assertTrue(r.chosen >= 0 && r.chosen < g.size());
            AssertJUnit.assertTrue(r.cpuMs > 0);
            // a duplicate of the default takes no slot
            List<SpellAbility> dup = new ArrayList<>(g);
            dup.add(1, null);
            LookaheadSearch.GivenResult r2 = s.decideGiven(game, a, dup, 3);
            AssertJUnit.assertEquals(3, r2.givenIndex.length);
            AssertJUnit.assertEquals(2, r2.givenIndex[1]);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void stackAndUncontestedAreNotSearched() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        LookaheadSearch s = new LookaheadSearch(config());
        try {
            LookaheadSearch.GivenResult r = s.decideGiven(game, a, Collections.singletonList(null), 4);
            AssertJUnit.assertEquals("uncontested", r.outcome);
            AssertJUnit.assertEquals(-1, r.chosen);
            AssertJUnit.assertEquals(0, s.getStats().searched);
        } finally {
            s.shutdown();
        }
    }

    /** Hooks that record where leaves are taken and score every leaf by a fixed function of its candidate's copy. */
    static final class Hooks implements LookaheadSearch.SearchHooks {
        final AtomicInteger copies = new AtomicInteger(), leaves = new AtomicInteger(), seats = new AtomicInteger();
        final List<String> where = Collections.synchronizedList(new ArrayList<>());
        final List<Long> seeds = Collections.synchronizedList(new ArrayList<>());
        final int horizonTurn;

        Hooks(int horizonTurn) {
            this.horizonTurn = horizonTurn;
        }

        @Override
        public Object onCopy(Game copy, Player me) {
            copies.incrementAndGet();
            return new int[] {0};
        }

        @Override
        public Object captureLeaf(Object ctx, Game g, Player me) {
            leaves.incrementAndGet();
            where.add(g.getPhaseHandler().getTurn() + ":" + g.getPhaseHandler().getPhase() + ":"
                    + (g.getPhaseHandler().getPriorityPlayer() == me));
            return me.getCardsIn(ZoneType.Battlefield).size();
        }

        @Override
        public double[] leafValues(List<Object> payloads) {
            final double[] v = new double[payloads.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = Math.min(1.0, ((Integer) payloads.get(i)) / 40.0);
            }
            return v;
        }

        @Override
        public LookaheadSearch.PlayoutSeat playoutSeat(Object ctx, Game g, Player me, LookaheadSearch.FirstAction first,
                long seed, LookaheadSearch.LeafProbe probe) {
            seats.incrementAndGet();
            seeds.add(seed);
            final PlayerControllerAi c = new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
                private boolean used = false;

                @Override
                public List<SpellAbility> chooseSpellAbilityToPlay() {
                    if (probe != null) {
                        probe.onPriority(getGame(), getPlayer());
                    }
                    if (!used) {
                        used = true;
                        if (first.pass) {
                            return null;
                        }
                        for (SpellAbility sa : new SpellAbilityPicker(getPlayer()).getCandidateSpellsAndAbilities()) {
                            if (first.matches(sa)) {
                                final List<SpellAbility> l = new ArrayList<>();
                                l.add(sa);
                                return l;
                            }
                        }
                        return null;
                    }
                    return super.chooseSpellAbilityToPlay();
                }
            };
            return new LookaheadSearch.PlayoutSeat() {
                @Override
                public PlayerControllerAi controller() {
                    return c;
                }

                @Override
                public String failure() {
                    return null;
                }
            };
        }
    }

    @Test
    public void valueLeafIsTakenAtTheSeatsPriorityInTheHorizonTurn() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        int turn = game.getPhaseHandler().getTurn();
        Hooks h = new Hooks(turn + 2);
        LookaheadSearch s = new LookaheadSearch(config());
        s.setHooks(h, true, false);
        try {
            LookaheadSearch.GivenResult r = s.decideGiven(game, a, given(a), 3);
            AssertJUnit.assertEquals(3 * 2, h.copies.get());
            AssertJUnit.assertEquals(0, s.getStats().leafFallbacks);
            AssertJUnit.assertEquals(1, s.getStats().leafCalls);
            AssertJUnit.assertEquals(h.leaves.get(), (int) s.getStats().leafLeaves);
            for (String w : h.where) {
                String[] f = w.split(":");
                AssertJUnit.assertTrue(w, Integer.parseInt(f[0]) >= turn + 2);
                AssertJUnit.assertEquals(w, "true", f[2]);
            }
            for (double e : r.ev) {
                AssertJUnit.assertTrue("a value leaf is in [0, 1]: " + e, e >= 0 && e <= 1);
            }
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void policyPlayoutSeatTakesTheCandidateFirst() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        Hooks h = new Hooks(game.getPhaseHandler().getTurn() + 2);
        LookaheadSearch s = new LookaheadSearch(config());
        s.setHooks(h, true, true);
        String before = LookaheadSearch.fingerprint(game);
        try {
            LookaheadSearch.GivenResult r = s.decideGiven(game, a, given(a), 3);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertEquals(3 * 2, h.seats.get());
            AssertJUnit.assertEquals(0, r.failed);
            AssertJUnit.assertEquals(0, s.getStats().playoutSeatFailures);
            // common random numbers: every candidate of a world gets the same play-out seed
            AssertJUnit.assertEquals(2, new java.util.HashSet<>(h.seeds).size());
        } finally {
            s.shutdown();
        }
    }
}
