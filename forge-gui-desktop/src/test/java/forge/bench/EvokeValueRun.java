package forge.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.eventbus.Subscribe;

import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.LeafDump;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.rl.LobbyPlayerPolicy;
import forge.bench.rl.RlLiveSeat;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Diagnostics (lane sdcc-evokevalue-1112): real games of the live policy seat (seat 1, {@link RlLiveSeat} against a real
 * search service) against Forge AI (seat 0), kept only when the seat's opening hand holds every card of {@code ev.need}
 * (and, with {@code ev.onPlay}, the seat starts), and stopped when turn {@code ev.maxTurn} is over. With
 * {@code -Dforge.rl.leafDump=FILE} the search's leaf observations of every searched decision are written out
 * ({@link LeafDump}). Card names come from the command line and deck files only.
 *
 * <pre>java ... -Dev.spec=SPEC -Dev.deck0=A.dck -Dev.deck1=B.dck -Dev.seeds=FROM:TO [-Dev.need="X|Y"] [-Dev.onPlay=true]
 *      [-Dev.maxTurn=3] [-Dev.keep=N] [-Dev.forbid="A|B"] forge.bench.EvokeValueRun</pre>
 */
public final class EvokeValueRun {
    private EvokeValueRun() {
    }

    static final class Gate {
        final Game g;
        final int seat;
        final List<String> need;
        List<String> forbid = new ArrayList<>();
        final boolean onPlay;
        final int maxTurn;
        volatile boolean kept;
        volatile boolean checked;
        volatile String hand = "";

        Gate(final Game g, final int seat, final List<String> need, final boolean onPlay, final int maxTurn) {
            this.g = g;
            this.seat = seat;
            this.need = need;
            this.onPlay = onPlay;
            this.maxTurn = maxTurn;
        }

        @Subscribe
        public void on(final GameEventTurnBegan e) {
            final Player me = g.getRegisteredPlayers().get(seat);
            if (!checked) {
                checked = true;
                final List<String> h = new ArrayList<>();
                for (Card c : me.getCardsIn(ZoneType.Hand)) {
                    h.add(c.getName());
                }
                hand = String.join("|", h);
                boolean ok = h.containsAll(need);
                for (String f : forbid) {
                    ok &= !h.contains(f);
                }
                if (onPlay && g.getPhaseHandler().getPlayerTurn() != me) {
                    ok = false;
                }
                kept = ok;
                if (!ok) {
                    g.setGameOver(GameEndReason.Draw);
                }
                return;
            }
            if (e.turnNumber() > maxTurn) {
                g.setGameOver(GameEndReason.Draw);
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final String spec = System.getProperty("ev.spec");
        final String[] sr = System.getProperty("ev.seeds", "1:20").split(":");
        final long from = Long.parseLong(sr[0]), to = Long.parseLong(sr[1]);
        final String needRaw = System.getProperty("ev.need", "");
        final List<String> need = needRaw.isEmpty() ? new ArrayList<>() : Arrays.asList(needRaw.split("\\|"));
        final boolean onPlay = Boolean.getBoolean("ev.onPlay");
        final int maxTurn = Integer.getInteger("ev.maxTurn", 3);
        final int keep = Integer.getInteger("ev.keep", Integer.MAX_VALUE);
        RlActorBench.ensureBooted();
        int kept = 0;
        for (long seed = from; seed < to && kept < keep; seed++) {
            final RlLiveSeat live = RlLiveSeat.connect(spec, "ev-" + seed);
            if (live == null) {
                throw new IllegalStateException("the policy seat did not bind (service / pin)");
            }
            final LobbyPlayerAi forge = new LobbyPlayerAi("Seat0", null);
            forge.setAiProfile("Default");
            final LobbyPlayerPolicy pol = live.lobby("Default Forge", 1, "Default");
            final RegisteredPlayer r0 = new RegisteredPlayer(RlActorBench.deck(System.getProperty("ev.deck0")));
            r0.setPlayer(forge);
            final RegisteredPlayer r1 = new RegisteredPlayer(RlActorBench.deck(System.getProperty("ev.deck1")));
            r1.setPlayer(pol);
            final GameRules rules = new GameRules(GameType.Constructed);
            rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
            rules.setGamesPerMatch(1);
            final Match match = new Match(rules, Arrays.asList(r0, r1), "evoke-value");
            final AtomicReference<Throwable> err = new AtomicReference<>();
            final AtomicReference<Gate> gate = new AtomicReference<>();
            final long s = seed;
            final Thread t = new Thread(() -> {
                try {
                    MyRandom.setRandom(new Random(s));
                    final Game game = match.createGame();
                    game.AI_TIMEOUT = 60;
                    final LookaheadSearch.Config k8 = new LookaheadSearch.Config();
                    k8.worlds = 2;
                    k8.breadth = 4;
                    k8.horizonTurns = 2;
                    k8.threads = 2;
                    k8.budgetMs = 4000;
                    k8.departZ = 1.645;
                    k8.seed = s * 31 + 1;
                    live.bind(game, 1, s, k8, AiFixes.Mode.ON);
                    final Gate gt = new Gate(game, 1, need, onPlay, maxTurn);
                    final String fr = System.getProperty("ev.forbid", "");
                    if (!fr.isEmpty()) {
                        gt.forbid = Arrays.asList(fr.split("\\|"));
                    }
                    gate.set(gt);
                    game.subscribeToEvents(gt);
                    LeafDump.tag = "seed" + s;
                    match.startGame(game, null);
                } catch (Throwable e) {
                    err.set(e);
                } finally {
                    live.finish();
                }
            }, "Game-evoke-value");
            t.setDaemon(true);
            t.start();
            t.join(15 * 60_000L);
            final Gate gt = gate.get();
            if (gt != null && gt.kept) {
                kept++;
            }
            System.err.println("[evoke-value] seed " + seed + " kept " + (gt != null && gt.kept) + " alive " + t.isAlive()
                    + " degraded " + live.degraded() + " hand " + (gt == null ? "?" : gt.hand)
                    + (err.get() == null ? "" : " error " + err.get()));
            if (err.get() != null) {
                err.get().printStackTrace();
            }
            if (t.isAlive()) {
                break;
            }
        }
        System.err.println("[evoke-value] done kept " + kept);
        System.exit(0);
    }
}
