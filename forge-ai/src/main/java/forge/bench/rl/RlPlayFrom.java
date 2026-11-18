package forge.bench.rl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonObject;

import forge.ai.AiTapeController;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-item2-hooks-1115 (item 2 v1, the realization audit; OFFLINE ONLY, default off; MEASUREMENT, NEVER A LABEL):
 * PLAY-FROM-POSITION. In {@code RlActorBench} replay mode (config key {@code playFrom}; no other way to turn it on) a tape
 * game is replayed FORCED from its decision log up to a listed priority decision of a seat (found as in {@link
 * RlReplaySearch}: turn, phase, menu size and the original row's v(o)). There the forced replay ends: every seat's AI
 * tape is detached, the seats' endpoint switches from the tape to the policy service (greedy DECIDE, as the H2H pilot's
 * eval slot), the game's random stream is re-seeded with the continuation seed {@code j}, and each library's cards whose
 * position nobody knows (InfoStates) are re-shuffled among their own positions (the unknown future re-drawn; every card
 * in play, in hand and every known library position is the TRUE state, kept: that is the point of the measurement).
 * The decision is answered with the given candidate (the forced action, {@link PlayerControllerBridge#forcedPriority}),
 * and from then on both seats play as the full live pilot (the given search spec, its seedSalt derived from {@code j})
 * to the end of the game. One result row per (position, action, j): winner, turns, void.
 */
public final class RlPlayFrom implements PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-item2-play-from/1";

    /** One continuation: a position, the forced candidate, the continuation seed. */
    public static final class Job {
        public final RlReplaySearch.Pos pos;
        public final int action;
        public final int j;

        public Job(final RlReplaySearch.Pos pos, final int action, final int j) {
            this.pos = pos;
            this.action = action;
            this.j = j;
        }

        public String key() {
            return pos.posId + "|a" + action + "|j" + j;
        }
    }

    /** Every (position, action, seed) of the spec, in order. */
    public static List<Job> jobs(final RlReplaySearch.Spec s) {
        final List<Job> out = new ArrayList<>();
        for (RlReplaySearch.Pos p : s.positions) {
            for (int a : p.actions) {
                for (int j : p.seeds) {
                    out.add(new Job(p, a, j));
                }
            }
        }
        return out;
    }

    /** Continuation j's search salt (never the live 0x51 by construction). */
    public static long searchSalt(final RlReplaySearch.Spec s, final int j) {
        return RlSearch.splitmix(s.saltBase ^ 0x50F0L ^ (0x9E3779B97F4A7C15L * (j + 1)));
    }

    /** The seats' endpoint: the tape until the fork, then the policy service's greedy DECIDE. */
    public static final class SwitchEndpoint implements RlSeat.Endpoint {
        private final RlSeat.Endpoint tape;
        volatile RlSearch live;

        public SwitchEndpoint(final RlSeat.Endpoint tape) {
            this.tape = tape;
        }

        @Override
        public RlWire.Decision decide(final byte[] p) throws IOException {
            final RlSearch s = live;
            return s == null ? tape.decide(p) : s.policyDecide(p, false);
        }

        @Override
        public void record(final byte[] p) throws IOException {
            tape.record(p);
        }
    }

    private final RlReplaySearch.Spec spec;
    private final Job job;
    private final JsonObject tape;
    private final Game game;
    private final RlSeat liveSeat;
    private final RlKnowledge know;
    private final CardIndex index;
    private final int obs;
    private final SwitchEndpoint ep;
    private final long seed;
    private final RlSearch search;
    private boolean forked = false;
    private String why = "not_reached";
    private final JsonObject fork = new JsonObject();
    private PlayerControllerBridge forcedOn;

    /** At game start (RlActorBench's GameHook): {@code liveSeat.search} is the game's live search (both seats). */
    public RlPlayFrom(final RlReplaySearch.Spec spec, final Job job, final JsonObject tape, final Game game,
            final RlSeat liveSeat, final RlKnowledge know, final CardIndex index, final int obs, final SwitchEndpoint ep) {
        this.spec = spec;
        this.job = job;
        this.tape = tape;
        this.game = game;
        this.liveSeat = liveSeat;
        this.know = know;
        this.index = index;
        this.obs = obs;
        this.ep = ep;
        this.seed = tape.get("seed").getAsLong();
        if (!(liveSeat.search instanceof RlSearch)) {
            throw new IllegalStateException("play-from: the game has no live search (playFrom sets it)");
        }
        this.search = (RlSearch) liveSeat.search;
    }

    @Override
    public void beforeDecision(final Player p) {
        if (forked || game.isGameOver()) {
            return;
        }
        final RlReplaySearch.Pos q = job.pos;
        final int seat = game.getRegisteredPlayers().indexOf(p);
        final int turn = game.getPhaseHandler().getTurn();
        final String phase = String.valueOf(game.getPhaseHandler().getPhase());
        final List<RlReplaySearch.Pos> one = Collections.singletonList(q);
        if (!RlReplaySearch.wanted(one, seat, turn, phase)) {
            if (turn > q.turn) {
                why = "passed_turn";
            }
            return;
        }
        final RlReplaySearch.Probe pr;
        try {
            pr = RlReplaySearch.probe(game, p, seat, liveSeat, know, index, obs, q.decIdx, search);
        } catch (RuntimeException e) {
            why = "probe: " + e;
            return;
        }
        if (pr.m == null || pr.sc.status != RlWire.ST_OK || RlReplaySearch.match(one, seat, turn, phase, pr, spec.tol) == null) {
            return;
        }
        forked = true;
        fork.addProperty("turn", turn);
        fork.addProperty("phase", phase);
        fork.addProperty("v_root_replay", pr.sc.valueObs);
        fork.addProperty("dv", Math.abs(pr.sc.valueObs - q.vRoot));
        for (Player pl : game.getPlayers()) {
            if (pl.getController() instanceof AiTapeController tc && tc.tape().divergence() != null) {
                why = "diverged_before_fork: " + tc.tape().divergence();
                return;
            }
        }
        if (job.action < 0 || job.action >= pr.m.C() || pr.m.validate(new short[] {(short) job.action}) != null) {
            why = "bad_action " + job.action;
            return;
        }
        final PlayerControllerBridge b = RlReplaySearch.bridgeOf(p);
        if (b == null) {
            why = "no_bridge";
            return;
        }
        // the forced replay ends here: the tapes decide nothing more; the seats ask the policy service
        int detached = 0;
        for (Player pl : game.getPlayers()) {
            if (pl.getController() instanceof AiTapeController tc) {
                tc.tape().detach();
                detached++;
            }
        }
        fork.addProperty("tapes_detached", detached);
        ep.live = search;
        // the continuation seed: the game's random stream, and the unknown library positions re-shuffled
        final long cs = RlSearch.splitmix(seed ^ RlSearch.splitmix(0x91A7F0L + job.j));
        forge.util.MyRandom.getThreadRandom().setSeed(cs);
        fork.add("reshuffled", reshuffle(game, new Random(RlSearch.splitmix(cs ^ 0x5A11E5L))));
        final JsonObject ans = pr.m.answer(new short[] {(short) job.action});
        ans.addProperty("menu", pr.objs.size());
        fork.add("forced_answer", ans);
        b.forcedPriorityResult = null;
        b.forcedPriority = ans;
        forcedOn = b;
        why = null;
    }

    /**
     * Each library's cards whose position no seat knows (InfoStates; all of them when it is not attached) re-shuffled
     * among their own positions; the known ones stay at their index. Returns the counts per owner.
     */
    static JsonObject reshuffle(final Game g, final Random rng) {
        final forge.ai.simulation.InfoStates is = forge.ai.simulation.InfoStates.find(g);
        final JsonObject out = new JsonObject();
        for (Player owner : g.getRegisteredPlayers()) {
            final List<Card> lib = new ArrayList<>(owner.getCardsIn(ZoneType.Library));
            final Set<Integer> known = new HashSet<>();
            if (is != null) {
                for (Player obs : g.getRegisteredPlayers()) {
                    known.addAll(is.known(obs, owner));
                }
            }
            final List<Integer> slots = new ArrayList<>();
            final List<Card> free = new ArrayList<>();
            for (int i = 0; i < lib.size(); i++) {
                if (!known.contains(lib.get(i).getId())) {
                    slots.add(i);
                    free.add(lib.get(i));
                }
            }
            Collections.shuffle(free, rng);
            for (int i = 0; i < slots.size(); i++) {
                lib.set(slots.get(i), free.get(i));
            }
            owner.getZone(ZoneType.Library).setCards(lib);
            if (is != null) {
                is.resnapReordered(owner);
            }
            final JsonObject o = new JsonObject();
            o.addProperty("library", lib.size());
            o.addProperty("known_kept", lib.size() - slots.size());
            out.add(String.valueOf(g.getRegisteredPlayers().indexOf(owner)), o);
        }
        return out;
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /** The result row of the continuation (call after the game); {@code end} = the game's tape line (or end record). */
    public JsonObject finish(final JsonObject end) {
        final JsonObject r = new JsonObject();
        r.addProperty("schema", SCHEMA);
        r.addProperty("pos_id", job.pos.posId);
        r.addProperty("game_uid", TrajectoryRef.canonicalUid(tape.get("game_uid").getAsString()));
        r.addProperty("seat", job.pos.seat);
        r.addProperty("dec_idx", job.pos.decIdx);
        r.addProperty("action", job.action);
        r.addProperty("j", job.j);
        r.addProperty("search_salt", searchSalt(spec, job.j));
        r.addProperty("forked", forked && why == null);
        r.add("fork", fork);
        final String fr = forcedOn == null ? null : forcedOn.forcedPriorityResult;
        r.addProperty("forced", fr);
        String v = why != null ? why : !"played".equals(fr) ? "forced_action: " + fr : null;
        if (v == null && end != null && end.has("void") && !end.get("void").isJsonNull()) {
            v = "game_void: " + end.get("void").getAsString();
        }
        r.addProperty("void", v);
        if (end != null) {
            r.add("result", end.get("result"));
            r.add("turns", end.get("turns"));
            r.add("reason", end.get("reason"));
            int winner = -1;
            if (end.has("result") && end.get("result").isJsonArray()) {
                for (int s = 0; s < end.getAsJsonArray("result").size(); s++) {
                    if (end.getAsJsonArray("result").get(s).getAsInt() > 0) {
                        winner = s;
                    }
                }
            }
            r.addProperty("winner", winner);
            r.addProperty("seat_won", winner == job.pos.seat);
        }
        return r;
    }
}
