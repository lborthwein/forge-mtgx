package forge.bench.rl;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.bench.BenchSession;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * The RL seat (lanes rl-r0-b1-1005, rl-r0-b4-1006): a {@link BenchSession.LocalAnswerer} for the bridged seats of ONE
 * game, which turns every ask of families 1-24 into a DECIDE frame (record mode: a RECORD frame with Forge's answer).
 *
 * <ul>
 *   <li><b>Trivial asks</b> (a pass-only priority menu, or exactly one legal answer) are answered here, counted in
 *       the census, and never sent (§2.3).</li>
 *   <li>Asks over the caps ({@code C > C_MAX}; {@code S > S_MAX}) are delegated to Forge and counted
 *       ({@code forge_decided["capC:<family>"]}).</li>
 *   <li><b>Environment rules</b> (as RlSimBench): after {@code capActions} non-pass priority actions by one player in
 *       one turn the seat passes ({@code cap_hits}); a game is void after {@code maxDecisions} sent decisions.</li>
 *   <li><b>Refusals:</b> when the bridge (or {@code CombatUtil}) refuses a decoded answer, Forge decides; the dec_idx
 *       goes into {@code overridden} (GAME_END and the tape), counted per family in {@code census.refused}.</li>
 *   <li><b>Record mode:</b> the seat delegates EVERY ask, so the game is Forge Default's own; at each non-trivial
 *       Phase A ask it builds the DECIDE layout, and when the bridge echoes Forge's answer it maps the answer to
 *       candidate steps and sends a RECORD frame. A priority echo also yields one TARGETS row per targeting ability of
 *       the spell Forge chose (Forge's AI targets inside canPlayAI and is never asked). Unmappable answers are counted
 *       {@code census.map_failed[family]} and not sent.</li>
 * </ul>
 */
public final class RlSeat implements BenchSession.LocalAnswerer {

    /** The server side of one game thread's connection. */
    public interface Endpoint {
        RlWire.Decision decide(byte[] decidePayload) throws IOException;

        void record(byte[] recordPayload) throws IOException;
    }

    public enum Role { RL, RECORD, FORGE }

    /** Run-level knobs. */
    public static final class Config {
        public String mode = "train";
        public int capActions = 40;
        public int maxDecisions = 3000;
    }

    /** Thrown out of the game thread's answer path never; the runner reads {@link #fatal} instead. */
    private final RlFeaturizer feat;
    private final Endpoint ep;
    private final Config cfg;
    private final long uid;
    private final String[] controllers;
    private final Role[] roles;
    private final boolean priv;
    private Game game;

    // ---- per-game record
    private int decIdx = 0;
    private final int[] sent = new int[2];
    public final List<JsonArray> dec = new ArrayList<>();
    public final List<Integer> overridden = new ArrayList<>();
    public final JsonArray versions = new JsonArray();
    private final String[] lastSlot = new String[2];
    private final long[] lastVersion = {-1, -1};
    public int capHits = 0;
    public String voidReason = null;
    public String fatal = null;
    public final Map<String, Integer> forgeDecidedExtra = new TreeMap<>();
    /** Answers the seat gave (sent, trivial or forced) and the bridge did not refuse, per method. */
    public final Map<String, Integer> okAnswers = new HashMap<>();
    private final Map<Integer, Census> census = new TreeMap<>();
    private final Map<Integer, Integer> mapFailed = new TreeMap<>();
    public int truncTokens = 0;
    public int unkNames = 0;
    public int synthTargets = 0;
    /** TARGETS frames sent with cand_tgt[1] = −1 (clarification C2). */
    public int srcMissing = 0;
    private final int[][] turnActs = {{-1, 0}, {-1, 0}};
    /** method → dec_idx of its latest unsettled answer (−1 = trivial / forced); two-frame asks add their second. */
    private final Map<String, Integer> lastAnswered = new HashMap<>();
    private final Map<String, Integer> lastAnswered2 = new HashMap<>();
    /** Census notes from the bridge (trigger targeting, effect casts, …) and per-origin TARGETS counts. */
    private final Map<String, Integer> notes = new TreeMap<>();
    /**
     * NAME candidates: the opponent cards the seat has seen this game, by name, first-seen order (B5's
     * RlKnowledge.opponentSeen, set by the runner). Default: the opponent cards visible to the seat now.
     */
    public java.util.function.IntFunction<List<String>> seenNames = null;
    private final Map<String, Integer> lastFamily = new HashMap<>();
    /** Record mode: frames waiting for Forge's echo. */
    private final Deque<Pending> pending = new ArrayDeque<>();
    /** Optional observer of sent frames (goldens, visibility tests). */
    public FrameListener listener;

    /**
     * S1 (lane s1-search-1007; null by default, and then nothing changes): the look-ahead over the policy's own priority
     * decisions. After the server's DECISION at a PRIORITY ask of an RL seat, the search may replace the chosen
     * candidate; the tape row and the answer carry the candidate actually played (so a tape replays without the search).
     */
    public PrioritySearch search;
    /** S1: priority decisions whose candidate the search replaced. */
    public int searchDepartures = 0;

    /** S1: the search hook (implemented by {@link RlSearch}). */
    public interface PrioritySearch {
        /**
         * @return the candidate index to play instead of {@code greedy}, -1 to keep it, or {@link #VOID} to end the game
         *         (the search's CPU cap)
         */
        int decide(Game g, Player p, RlCandidates.Menu m, RlWire.Decide frame, int greedy, List<SpellAbility> menuObjs);

        int VOID = -2;
    }

    /**
     * S1 policy play-outs (null by default): the first PRIORITY ask of this seat is answered with this choice and never
     * sent (the searched candidate a look-ahead play-out starts with); then this seat is the policy as usual.
     */
    public PlayoutFirst playoutFirst;

    /** S1: the forced first priority answer of a play-out seat. */
    public interface PlayoutFirst {
        /** The candidate index to answer with, or -1 (the play-out fails; {@link #why} says why). */
        int choose(Game g, Player p, RlCandidates.Menu m, List<SpellAbility> menuObjs,
                java.util.function.Supplier<RlWire.Decide> frame);

        String why();
    }

    /** Diagnosis switch (-Drlseat.recordBare=true): recorder seats delegate before building any candidate or
     *  observation, isolating the bridge's BRIDGE-mode work in a do-no-harm bisect. Off by default. */
    static final boolean RECORD_BARE = Boolean.getBoolean("rlseat.recordBare");

    public interface FrameListener {
        void onFrame(Game game, Player seat, RlWire.Decide frame, RlCandidates.Menu menu, RlFeaturizer.Obs obs,
                short[] steps, JsonObject answer);
    }

    private static final class Census {
        int asks, trivial, sent;
        final Map<String, Integer> menuHist = new TreeMap<>();
        final Map<String, Integer> slotHist = new TreeMap<>();
        int refused, autoRefused;
    }

    private static final class Pending {
        final String method;
        final int seat;
        final Player player;
        final RlCandidates.Menu menu;
        final RlFeaturizer.Obs obs;

        Pending(final String method, final int seat, final Player player, final RlCandidates.Menu menu,
                final RlFeaturizer.Obs obs) {
            this.method = method;
            this.seat = seat;
            this.player = player;
            this.menu = menu;
            this.obs = obs;
        }
    }

    public RlSeat(final RlFeaturizer feat, final Endpoint ep, final Config cfg, final long uid,
            final String[] controllers, final boolean priv) {
        this.feat = feat;
        this.ep = ep;
        this.cfg = cfg;
        this.uid = uid;
        this.controllers = controllers.clone();
        this.priv = priv;
        this.roles = new Role[controllers.length];
        for (int s = 0; s < controllers.length; s++) {
            roles[s] = roleOf(controllers[s]);
            if (roles[s] != Role.RL) {
                lastSlot[s] = controllers[s];
                lastVersion[s] = 0;
                final JsonArray v = new JsonArray();
                v.add(0);
                v.add(s);
                v.add(controllers[s]);
                v.add(0);
                versions.add(v);
            }
        }
    }

    public static Role roleOf(final String controller) {
        if (controller == null) {
            throw new IllegalArgumentException("null controller");
        }
        if (controller.startsWith("rl:") && controller.length() > 3) {
            return Role.RL;
        }
        if ("record".equals(controller)) {
            return Role.RECORD;
        }
        if ("forge".equals(controller)) {
            return Role.FORGE;
        }
        throw new IllegalArgumentException("unknown controller " + controller);
    }

    /** Record-only games (every bridged seat a recorder) are pure observers (see the bridge's observing()). */
    @Override
    public boolean observeOnly() {
        if (NO_ISOLATE) {
            return false;
        }
        for (Role r : roles) {
            if (r == Role.RL) {
                return false;
            }
        }
        return true;
    }

    /** Diagnosis switch (-Drlseat.noIsolate=true): record mode without the observer isolation. Off by default. */
    static final boolean NO_ISOLATE = Boolean.getBoolean("rlseat.noIsolate");

    public void setGame(final Game g) {
        this.game = g;
    }

    public int decisions(final int seat) {
        return sent[seat];
    }

    public int totalSent() {
        return decIdx;
    }

    private int seatOf(final Game g, final Player p) {
        final int i = g.getRegisteredPlayers().indexOf(p);
        return i;
    }

    private Census c(final int family) {
        return census.computeIfAbsent(family, k -> new Census());
    }

    private void endGame(final String reason) {
        if (voidReason == null) {
            voidReason = reason;
        }
        if (game != null && !game.isGameOver()) {
            game.setGameOver(GameEndReason.Draw);
        }
    }

    private void ok(final String method) {
        okAnswers.merge(method, 1, Integer::sum);
    }

    // ------------------------------------------------------------------------------------------------ asks

    @Override
    public JsonObject answer(final Game g, final Player player, final String kind, final JsonObject body) {
        return answer(g, player, null, kind, body, null);
    }

    @Override
    public JsonObject answer(final Game g, final Player player, final String method, final String kind,
            final JsonObject body, final Object objs) {
        if (g != game || g.isGameOver() || voidReason != null) {
            return null;
        }
        final int seat = seatOf(g, player);
        if (seat < 0 || seat >= roles.length || roles[seat] == Role.FORGE) {
            return null;
        }
        final int family = RlSchema.familyOf(kind, method);
        if (family == RlSchema.F_PRIORITY) {
            lastAnswered.clear();
            lastAnswered2.clear();
        } else if (method != null) {
            lastAnswered.remove(method);
            lastAnswered2.remove(method);
        }
        if (family > 0) {
            c(family).asks++;
        }
        if (!RlSchema.isLearned(family)) {
            return null; // the mechanical kinds: Forge decides (forge_decided by method)
        }
        if (RECORD_BARE && roles[seat] == Role.RECORD) {
            return null; // diagnosis only: the bridge's own BRIDGE-mode work, none of the seat's
        }
        final RlCandidates.Menu m = RlCandidates.build(g, player, method, kind, body, objs,
                family == RlSchema.F_NAME ? seen(g, player, seat) : null);
        if (family == RlSchema.F_TARGETS && m != null) {
            note("targets." + m.origin + ".asks");
        }
        if (m == null || m.unposable != null) {
            forgeDecidedExtra.merge("unposed:" + RlSchema.familyName(family), 1, Integer::sum);
            return null;
        }
        if (playoutFirst != null && family == RlSchema.F_PRIORITY && roles[seat] == Role.RL) {
            return forcedFirst(g, player, seat, method, m, objs);
        }
        if (m.trivial) {
            c(family).trivial++;
            if (family == RlSchema.F_TARGETS) {
                note("targets." + m.origin + ".trivial");
            }
            if (roles[seat] == Role.RECORD) {
                return null;
            }
            final JsonObject ta = RlSchema.isTwoFrame(family) ? m.answerTwoFrame(m.trivialSteps, null, null)
                    : m.answer(m.trivialSteps);
            if (isDelegate(ta)) {
                forgeDecidedExtra.merge("forgeChoice:" + RlSchema.familyName(family), 1, Integer::sum);
                return ta;
            }
            lastAnswered.put(method, -1);
            lastFamily.put(method, family);
            ok(method);
            return ta;
        }
        if (m.C() > RlSchema.C_MAX || m.S() > RlSchema.S_MAX) {
            forgeDecidedExtra.merge((m.C() > RlSchema.C_MAX ? "capC:" : "capS:") + RlSchema.familyName(family), 1,
                    Integer::sum);
            return null;
        }
        if (roles[seat] == Role.RL && family == RlSchema.F_PRIORITY && cfg.capActions > 0) {
            final int turn = g.getPhaseHandler().getTurn();
            final int[] ta = turnActs[seat];
            if (ta[0] != turn) {
                ta[0] = turn;
                ta[1] = 0;
            }
            if (ta[1] >= cfg.capActions) {
                capHits++;
                lastAnswered.put(method, -1);
                lastFamily.put(method, family);
                ok(method);
                final JsonObject pass = new JsonObject();
                pass.addProperty("choice", 0);
                return pass;
            }
        }
        if (decIdx >= cfg.maxDecisions) {
            endGame("max_decisions");
            return null;
        }
        if (roles[seat] == Role.RECORD) {
            final java.util.Random live = observeOnly() ? forge.util.MyRandom.getThreadRandom() : null;
            final int[] snap = observeOnly() ? idValues() : null;
            if (snap != null) {
                forge.util.MyRandom.setThreadRandom(new java.util.Random(0x0B5E47EL));
            }
            try {
                final RlFeaturizer.Obs ro = feat.observe(g, player, m.mullK, priv, m);
                unkNames += ro.unknownNames;
                m.bind(ro, feat, player);
                pending.push(new Pending(method, seat, player, m, ro));
            } finally {
                if (snap != null) {
                    forge.util.MyRandom.setThreadRandom(live);
                    restoreIds(snap);
                }
            }
            while (pending.size() > 32) {
                pending.removeLast();
            }
            return null;
        }
        final RlFeaturizer.Obs obs = feat.observe(g, player, m.mullK, priv, m);
        unkNames += obs.unknownNames;
        m.bind(obs, feat, player);
        // ---- RL seat: DECIDE → DECISION
        final RlWire.Decide frame = frame(seat, g, m, obs);
        final RlWire.Decision d = decide(frame);
        if (d == null) {
            return null;
        }
        final String bad = d.status == RlWire.ST_OK ? m.validate(d.steps) : null;
        if (bad != null) {
            System.err.println("[rlseat] invalid DECISION steps for dec " + frame.decIdx + ": " + bad);
            endGame("server_error");
            return null;
        }
        if (search != null && family == RlSchema.F_PRIORITY && d.status == RlWire.ST_OK && objs instanceof List) {
            // S1: the look-ahead over the policy's own priority decision (the policy's choice is its default)
            @SuppressWarnings("unchecked")
            final List<SpellAbility> menuObjs = (List<SpellAbility>) objs;
            final int alt = search.decide(g, player, m, frame, d.steps[0], menuObjs);
            if (alt == PrioritySearch.VOID) {
                endGame("cpu_cap");
                return null;
            }
            if (alt >= 0 && alt != d.steps[0]) {
                if (m.validate(new short[] {(short) alt}) != null) {
                    System.err.println("[rlseat] search answered an invalid candidate " + alt + " for dec " + frame.decIdx);
                    endGame("server_error");
                    return null;
                }
                d.steps = new short[] {(short) alt};
                searchDepartures++;
            }
        }
        noteSent(seat, family, m, frame, d.steps);
        noteVersion(seat, controllers[seat].substring(3), d.policyVersion);
        if (family == RlSchema.F_TARGETS) {
            note("targets." + m.origin + ".sent");
        }
        if (d.status == RlWire.ST_DELEGATE) {
            overridden.add(frame.decIdx);
            forgeDecidedExtra.merge("serverDelegate:" + RlSchema.familyName(family), 1, Integer::sum);
            return null;
        }
        // two-frame families (SCRY, SURVEIL): the PERMUTE frame over the cards kept on top, if two or more
        RlCandidates.Menu m2 = null;
        RlWire.Decide frame2 = null;
        short[] steps2 = null;
        if (RlSchema.isTwoFrame(family)) {
            m2 = m.secondFrame(d.steps);
            if (m2 != null && m2.unposable == null && !m2.trivial) {
                m2.bind(obs, feat, player);
                frame2 = frame(seat, g, m2, obs);
                final RlWire.Decision d2 = decide(frame2);
                if (d2 == null) {
                    return null;
                }
                final String bad2 = d2.status == RlWire.ST_OK ? m2.validate(d2.steps) : null;
                if (d2.status != RlWire.ST_OK || bad2 != null) {
                    System.err.println("[rlseat] invalid second-frame DECISION for dec " + frame2.decIdx + ": "
                            + (bad2 != null ? bad2 : "status " + d2.status));
                    endGame("server_error");
                    return null;
                }
                noteSent(seat, family, m2, frame2, d2.steps);
                noteVersion(seat, controllers[seat].substring(3), d2.policyVersion);
                steps2 = d2.steps;
            } else if (m2 != null && m2.trivial) {
                c(family).trivial++;
                steps2 = m2.trivialSteps;
            }
        }
        final JsonObject ans = RlSchema.isTwoFrame(family) ? m.answerTwoFrame(d.steps, m2, steps2) : m.answer(d.steps);
        if (listener != null) {
            listener.onFrame(g, player, frame, m, obs, d.steps, ans);
            if (frame2 != null) {
                listener.onFrame(g, player, frame2, m2, obs, steps2, ans);
            }
        }
        if (isDelegate(ans)) {
            // NAME's "Forge's choice": the seat's decision is to let Forge name (delegated and counted)
            forgeDecidedExtra.merge("forgeChoice:" + RlSchema.familyName(family), 1, Integer::sum);
            return ans;
        }
        lastAnswered.put(method, frame.decIdx);
        if (frame2 != null) {
            lastAnswered2.put(method, frame2.decIdx);
        }
        lastFamily.put(method, family);
        ok(method);
        if (family == RlSchema.F_PRIORITY && ans.get("choice").getAsInt() > 0) {
            final int turn = g.getPhaseHandler().getTurn();
            final int[] ta = turnActs[seat];
            if (ta[0] != turn) {
                ta[0] = turn;
                ta[1] = 0;
            }
            ta[1]++;
        }
        return ans;
    }

    /**
     * S1 policy play-outs: the first PRIORITY ask of the play-out seat, answered with the searched candidate (never sent).
     * No match ends the play-out game as {@code server_error} with {@link #fatal} set (the look-ahead counts the
     * play-out as failed).
     */
    private JsonObject forcedFirst(final Game g, final Player player, final int seat, final String method,
            final RlCandidates.Menu m, final Object objs) {
        final PlayoutFirst pf = playoutFirst;
        playoutFirst = null;
        @SuppressWarnings("unchecked")
        final List<SpellAbility> menuObjs = objs instanceof List ? (List<SpellAbility>) objs : java.util.Collections.emptyList();
        final int idx;
        try {
            idx = pf.choose(g, player, m, menuObjs, () -> {
                final RlFeaturizer.Obs o = feat.observe(g, player, m.mullK, priv, m);
                m.bind(o, feat, player);
                return frame(seat, g, m, o);
            });
        } catch (RuntimeException e) {
            fatal = "play-out first action: " + e;
            endGame("server_error");
            return null;
        }
        if (idx < 0 || idx >= m.C()) {
            fatal = "play-out first action: " + pf.why();
            endGame("server_error");
            return null;
        }
        final JsonObject ans = m.answer(new short[] {(short) idx});
        lastAnswered.put(method, -1);
        lastFamily.put(method, RlSchema.F_PRIORITY);
        ok(method);
        if (ans.get("choice").getAsInt() > 0) {
            final int turn = g.getPhaseHandler().getTurn();
            final int[] ta = turnActs[seat];
            if (ta[0] != turn) {
                ta[0] = turn;
                ta[1] = 0;
            }
            ta[1]++;
        }
        return ans;
    }

    /** DECIDE → DECISION for one frame; null (the game is ended as server_error) on any transport or protocol fault. */
    private RlWire.Decision decide(final RlWire.Decide frame) {
        final RlWire.Decision d;
        try {
            d = ep.decide(RlWire.encodeDecide(frame));
        } catch (RlClient.ServerError e) {
            fatal = "server: " + e.getMessage();
            endGame("server_error");
            return null;
        } catch (IOException | RuntimeException e) {
            fatal = "transport: " + e;
            endGame("server_error");
            return null;
        }
        if (d.gameUid != uid || d.decIdx != frame.decIdx) {
            fatal = "DECISION for " + Long.toUnsignedString(d.gameUid) + "/" + d.decIdx + ", expected "
                    + Long.toUnsignedString(uid) + "/" + frame.decIdx;
            endGame("server_error");
            return null;
        }
        if (d.status == RlWire.ST_ERROR) {
            endGame("server_error");
            return null;
        }
        return d;
    }

    private static boolean isDelegate(final JsonObject a) {
        return a != null && a.has("delegate") && a.get("delegate").getAsBoolean();
    }

    /** NAME candidates' seen set (see {@link #seenNames}). */
    private List<String> seen(final Game g, final Player player, final int seat) {
        if (seenNames != null) {
            try {
                return seenNames.apply(seat);
            } catch (RuntimeException e) {
                // fall back below
            }
        }
        final List<String> out = new ArrayList<>();
        final Player opp = RlFeaturizer.opponentOf(g, player);
        if (opp == null) {
            return out;
        }
        final forge.game.player.PlayerView viewer = player.getView();
        for (forge.game.zone.ZoneType z : new forge.game.zone.ZoneType[] {forge.game.zone.ZoneType.Battlefield,
                forge.game.zone.ZoneType.Graveyard, forge.game.zone.ZoneType.Exile, forge.game.zone.ZoneType.Hand,
                forge.game.zone.ZoneType.Command}) {
            for (forge.game.card.Card c : opp.getCardsIn(z)) {
                if (!c.isFaceDown() && c.getView().canBeShownTo(viewer) && !out.contains(c.getName())) {
                    out.add(c.getName());
                }
            }
        }
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
            final forge.game.card.Card c = si.getSourceCard();
            if (c != null && si.getActivatingPlayer() == opp && !c.isFaceDown()
                    && c.getView().canBeShownTo(viewer) && !out.contains(c.getName())) {
                out.add(c.getName());
            }
        }
        return out;
    }

    private void note(final String key) {
        notes.merge(key, 1, Integer::sum);
    }

    /** The bridge's census notes (see BenchSession.LocalAnswerer#note). */
    @Override
    public void note(final Game g, final Player player, final String key) {
        if (g == game) {
            note(key);
        }
    }

    private RlWire.Decide frame(final int seat, final Game g, final RlCandidates.Menu m, final RlFeaturizer.Obs o) {
        final RlWire.Decide f = o.version == 2 ? RlWire.Decide.v2() : new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = decIdx;
        f.seat = seat;
        f.family = m.family;
        f.mode = m.mode;
        f.flags = (o.hasPriv ? RlWire.F_HAS_PRIV : 0) | (o.truncated ? RlWire.F_TRUNC_TOKENS : 0)
                | (o.droppedRefs ? RlWire.F_DROPPED_REFS : 0);
        f.minPick = m.minPick;
        f.maxPick = m.maxPick;
        f.turn = Math.min(0xffff, g.getPhaseHandler().getTurn());
        f.L = o.L;
        f.D = o.D;
        f.C = m.C();
        f.S = m.S();
        f.P = o.hasPriv ? o.P : 0;
        f.tokCard = o.tokCard;
        f.tokZone = o.tokZone;
        f.tokAttr = o.tokAttr;
        f.deckCard = o.deckCard;
        f.deckCnt = o.deckCnt;
        System.arraycopy(o.scal, 0, f.scal, 0, RlSchema.N_SCAL);
        System.arraycopy(o.ctx, 0, f.ctx, 0, f.nCtx());
        f.candKind = m.kindA;
        f.candTok = m.tok;
        f.candCard = m.card;
        f.candTgt = m.tgt;
        f.candSlot = m.slot;
        f.candNum = m.num;
        f.candAbility = m.ability;
        f.candFlags = m.flagsA;
        f.slotTok = m.slotTok;
        if (o.version == 2) {
            f.R = o.R;
            f.F = o.F;
            f.Dr = o.Dr;
            f.tokBits = o.tokBits;
            f.relSrc = o.relSrc;
            f.relDst = o.relDst;
            f.relType = o.relType;
            f.relArg = o.relArg;
            f.relNum = o.relNum;
            f.factTok = o.factTok;
            f.factId = o.factId;
            f.factArg = o.factArg;
            f.factNum = o.factNum;
            f.restCard = o.restCard;
            f.restCnt = o.restCnt;
        }
        if (o.hasPriv) {
            f.privCard = o.privCard;
            f.privZone = o.privZone;
            f.privCnt = o.privCnt;
        }
        return f;
    }

    private void noteSent(final int seat, final int family, final RlCandidates.Menu m, final RlWire.Decide f,
            final short[] steps) {
        final Census cs = c(family);
        cs.sent++;
        if (m.sourceMissing) {
            srcMissing++;
        }
        cs.menuHist.merge(bucket(m.C(), false), 1, Integer::sum);
        if (m.mode == RlSchema.M_ASSIGN) {
            cs.slotHist.merge(bucket(m.S(), true), 1, Integer::sum);
        }
        if ((f.flags & RlWire.F_TRUNC_TOKENS) != 0) {
            truncTokens++;
        }
        final JsonArray row = new JsonArray();
        row.add(f.decIdx);
        row.add(seat);
        row.add(family);
        row.add(m.C());
        final JsonArray st = new JsonArray();
        for (short s : steps) {
            st.add(s);
        }
        row.add(st);
        dec.add(row);
        sent[seat]++;
        decIdx++;
    }

    private void noteVersion(final int seat, final String slot, final long version) {
        if (!slot.equals(lastSlot[seat]) || version != lastVersion[seat]) {
            lastSlot[seat] = slot;
            lastVersion[seat] = version;
            final JsonArray v = new JsonArray();
            v.add(decIdx - 1);
            v.add(seat);
            v.add(slot);
            v.add(version);
            versions.add(v);
        }
    }

    static String bucket(final int n, final boolean zero) {
        if (n <= 0) {
            return zero ? "0" : "1";
        }
        if (n <= 2) {
            return String.valueOf(n);
        }
        if (n <= 4) {
            return "3-4";
        }
        if (n <= 8) {
            return "5-8";
        }
        return "9+";
    }

    // ------------------------------------------------------------------------------------------------ refusals

    @Override
    public void onRefused(final Game g, final Player player, final String method, final String why) {
        if (g != game || method == null) {
            return;
        }
        final Integer d = lastAnswered.remove(method);
        final Integer d2 = lastAnswered2.remove(method);
        if (d == null) {
            return;
        }
        final Integer fam = lastFamily.get(method);
        okAnswers.merge(method, -1, Integer::sum);
        if (d2 != null) {
            overridden.add(d2);
        }
        if (d >= 0) {
            overridden.add(d);
            if (fam != null) {
                c(fam).refused++;
            }
        } else if (fam != null) {
            c(fam).autoRefused++;
        }
    }

    // ------------------------------------------------------------------------------------------------ record mode

    @Override
    public void onEcho(final Game g, final Player player, final String method, final String kind,
            final JsonObject forgeAnswer, final Object forgeDecision) {
        if (g != game || voidReason != null || pending.isEmpty()) {
            return;
        }
        Pending p = null;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
            final Pending x = it.next();
            if (x.method.equals(method) && x.player == player) {
                p = x;
                break;
            }
        }
        if (p == null) {
            return;
        }
        while (!pending.isEmpty()) {
            if (pending.pop() == p) {
                break;
            }
        }
        if (!observeOnly()) {
            echoed(g, p, forgeAnswer, forgeDecision);
            return;
        }
        // the seat's own echo-time work (TARGETS synthesis reads the chosen spell's targets) is isolated as the
        // bridge isolates its menu: scratch random stream, id counters put back
        final java.util.Random live = forge.util.MyRandom.getThreadRandom();
        final int[] snap = idValues();
        forge.util.MyRandom.setThreadRandom(new java.util.Random(0x0B5E47EL));
        try {
            echoed(g, p, forgeAnswer, forgeDecision);
        } finally {
            forge.util.MyRandom.setThreadRandom(live);
            restoreIds(snap);
        }
    }

    static int[] idValues() {
        final Object cap = forge.util.IdScope.capture();
        if (!(cap instanceof java.util.concurrent.atomic.AtomicInteger[])) {
            return null;
        }
        final java.util.concurrent.atomic.AtomicInteger[] a = (java.util.concurrent.atomic.AtomicInteger[]) cap;
        final int[] v = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            v[i] = a[i].get();
        }
        return v;
    }

    static void restoreIds(final int[] v) {
        final Object cap = forge.util.IdScope.capture();
        if (v == null || !(cap instanceof java.util.concurrent.atomic.AtomicInteger[])) {
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger[] a = (java.util.concurrent.atomic.AtomicInteger[]) cap;
        for (int i = 0; i < a.length && i < v.length; i++) {
            a[i].set(v[i]);
        }
    }

    private void echoed(final Game g, final Pending p, final JsonObject forgeAnswer, final Object forgeDecision) {
        final RlCandidates.Menu m = p.menu;
        if (RlSchema.isTwoFrame(m.family)) {
            final Object[] two = m.fromEchoTwoFrame(forgeAnswer);
            if (two == null) {
                mapFailed.merge(m.family, 1, Integer::sum);
                return;
            }
            if (!sendRecord(p.seat, g, m, p.obs, (short[]) two[0])) {
                return;
            }
            final RlCandidates.Menu m2 = (RlCandidates.Menu) two[1];
            if (m2 != null && !m2.trivial && m2.unposable == null) {
                m2.bind(p.obs, feat, p.player);
                sendRecord(p.seat, g, m2, p.obs, (short[]) two[2]);
            } else if (m2 != null && m2.trivial) {
                c(m.family).trivial++;
            }
            return;
        }
        final short[] steps = m.fromEcho(forgeAnswer);
        if (steps == null) {
            mapFailed.merge(m.family, 1, Integer::sum);
        } else if (!sendRecord(p.seat, g, m, p.obs, steps)) {
            return;
        }
        if (m.family == RlSchema.F_PRIORITY && forgeDecision instanceof List && !((List<?>) forgeDecision).isEmpty()
                && ((List<?>) forgeDecision).get(0) instanceof SpellAbility) {
            final SpellAbility chosen = (SpellAbility) ((List<?>) forgeDecision).get(0);
            if (!recordTargets(p.seat, g, p.player, p.obs, chosen, "cast")) {
                return;
            }
        }
    }

    /** Record mode: one TARGETS row per targeting ability of {@code chosen}'s chain (Forge's targets). */
    private boolean recordTargets(final int seat, final Game g, final Player player, final RlFeaturizer.Obs obs,
            final SpellAbility chosen, final String origin) {
        final List<Object[]> rows = RlCandidates.targetsFromChosen(g, chosen);
        if (!rows.isEmpty() && !RlCandidates.stackAccepts(g, chosen)) {
            // Forge's stack will refuse this activation (some ability's targets break its own target count): a
            // per-game census note, so a reader can find the games where Forge's AI made an illegal activation
            note("forgeRefused." + origin);
        }
        for (Object[] t : rows) {
            final RlCandidates.Menu tm = (RlCandidates.Menu) t[0];
            final short[] ts = (short[]) t[1];
            tm.origin = origin;
            synthTargets++;
            c(RlSchema.F_TARGETS).asks++;
            note("targets." + origin + ".asks");
            if (tm.forgeIllegal != null) {
                // Forge's own answer is outside the legal action space (too few targets for the ability's own
                // count; Forge refuses the activation at the stack): no label exists, and the mapper did not fail
                note("targets." + origin + ".forgeIllegal");
                continue;
            }
            if (tm.offTargets) {
                note("targets." + origin + ".offTargets");
            }
            if (tm.unposable != null || ts == null) {
                mapFailed.merge(RlSchema.F_TARGETS, 1, Integer::sum);
                final String why = tm.unposable == null ? "other" : tm.unposable.startsWith("Forge's targets are not")
                        ? "nomatch" : tm.unposable.startsWith("Forge's targets break") ? "rules"
                        : tm.unposable.startsWith("SUBSET min") ? "fewOptions" : "other";
                note("targets." + origin + ".mapFail." + why);
                continue;
            }
            if (tm.trivial) {
                c(RlSchema.F_TARGETS).trivial++;
                note("targets." + origin + ".trivial");
                continue;
            }
            if (tm.C() > RlSchema.C_MAX) {
                forgeDecidedExtra.merge("capC:TARGETS", 1, Integer::sum);
                continue;
            }
            tm.bind(obs, feat, player);
            if (!sendRecord(seat, g, tm, obs, ts)) {
                return false;
            }
            note("targets." + origin + ".sent");
        }
        return true;
    }

    /**
     * Record mode: Forge's AI chose the targets of a triggered ability it put on the stack (no ask was raised). One
     * TARGETS row per targeting ability, observed now (isolated as the echo-time work is).
     */
    @Override
    public void onForgeTargeted(final Game g, final Player player, final String origin, final SpellAbility sa) {
        if (g != game || voidReason != null || g.isGameOver()) {
            return;
        }
        final int seat = seatOf(g, player);
        if (seat < 0 || seat >= roles.length || roles[seat] != Role.RECORD || sa == null) {
            return;
        }
        final boolean iso = observeOnly();
        final java.util.Random live = iso ? forge.util.MyRandom.getThreadRandom() : null;
        final int[] snap = iso ? idValues() : null;
        if (iso) {
            forge.util.MyRandom.setThreadRandom(new java.util.Random(0x0B5E47EL));
        }
        try {
            final RlFeaturizer.Obs ro = feat.observe(g, player, 0, priv);
            unkNames += ro.unknownNames;
            recordTargets(seat, g, player, ro, sa, origin);
        } finally {
            if (iso) {
                forge.util.MyRandom.setThreadRandom(live);
                restoreIds(snap);
            }
        }
    }

    private boolean sendRecord(final int seat, final Game g, final RlCandidates.Menu m, final RlFeaturizer.Obs obs,
            final short[] steps) {
        if (decIdx >= cfg.maxDecisions) {
            endGame("max_decisions");
            return false;
        }
        final RlWire.Decide f = frame(seat, g, m, obs);
        f.teacher = steps;
        try {
            ep.record(RlWire.encodeDecide(f));
        } catch (IOException | RuntimeException e) {
            fatal = "record: " + e;
            endGame("server_error");
            return false;
        }
        if (listener != null) {
            listener.onFrame(g, g.getRegisteredPlayers().get(seat), f, m, obs, steps, m.answer(steps));
        }
        noteSent(seat, m.family, m, f, steps);
        noteVersion(seat, "record", 0);
        return true;
    }

    // ------------------------------------------------------------------------------------------------ reports

    public JsonObject censusJson() {
        final JsonObject o = new JsonObject();
        for (Map.Entry<Integer, Census> e : census.entrySet()) {
            final Census cs = e.getValue();
            final JsonObject f = new JsonObject();
            f.addProperty("asks", cs.asks);
            f.addProperty("trivial", cs.trivial);
            f.addProperty("sent", cs.sent);
            final JsonObject h = new JsonObject();
            for (Map.Entry<String, Integer> b : cs.menuHist.entrySet()) {
                h.addProperty(b.getKey(), b.getValue());
            }
            f.add("menu_hist", h);
            if (!cs.slotHist.isEmpty()) {
                final JsonObject sh = new JsonObject();
                for (Map.Entry<String, Integer> b : cs.slotHist.entrySet()) {
                    sh.addProperty(b.getKey(), b.getValue());
                }
                f.add("slot_hist", sh);
            }
            if (cs.refused > 0) {
                f.addProperty("refused", cs.refused);
            }
            if (cs.autoRefused > 0) {
                f.addProperty("auto_refused", cs.autoRefused);
            }
            o.add(RlSchema.familyName(e.getKey()), f);
        }
        o.addProperty("unk_names", unkNames);
        o.addProperty("trunc_tokens", truncTokens);
        if (!mapFailed.isEmpty()) {
            final JsonObject mf = new JsonObject();
            for (Map.Entry<Integer, Integer> e : mapFailed.entrySet()) {
                mf.addProperty(RlSchema.familyName(e.getKey()), e.getValue());
            }
            o.add("map_failed", mf);
        }
        if (synthTargets > 0) {
            o.addProperty("synth_targets", synthTargets);
        }
        o.addProperty("src_missing", srcMissing);
        if (!notes.isEmpty()) {
            final JsonObject n = new JsonObject();
            for (Map.Entry<String, Integer> e : notes.entrySet()) {
                n.addProperty(e.getKey(), e.getValue());
            }
            o.add("notes", n);
        }
        return o;
    }

    public int mapFailedTotal() {
        int n = 0;
        for (int v : mapFailed.values()) {
            n += v;
        }
        return n;
    }
}
