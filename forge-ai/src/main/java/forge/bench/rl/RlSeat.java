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
 * The RL seat (lane rl-r0-b1-1005): a {@link BenchSession.LocalAnswerer} for the bridged seats of ONE game, which
 * turns every Phase A ask into a DECIDE frame (or, in record mode, a RECORD frame carrying Forge's own answer).
 *
 * <ul>
 *   <li><b>Trivial asks</b> (a pass-only priority menu, or exactly one legal answer) are answered here, counted in
 *       the census, and never sent (§2.3).</li>
 *   <li><b>Phase B families</b> and asks over the caps ({@code C > C_MAX}; {@code S > S_MAX}) are delegated to Forge
 *       and counted ({@code forge_decided["capC:<family>"]}).</li>
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
    /** method → dec_idx of its latest unsettled answer (−1 = trivial / forced). */
    private final Map<String, Integer> lastAnswered = new HashMap<>();
    private final Map<String, Integer> lastFamily = new HashMap<>();
    /** Record mode: frames waiting for Forge's echo. */
    private final Deque<Pending> pending = new ArrayDeque<>();
    /** Optional observer of sent frames (goldens, visibility tests). */
    public FrameListener listener;

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

    /** Record-only games (every bridged seat a recorder) are pure observers: isolate ids (see the bridge). */
    @Override
    public boolean isolateIds() {
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

    /** Diagnosis switch (-Drlseat.noIsolate=true): record mode without id isolation. Off by default. */
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
        } else if (method != null) {
            lastAnswered.remove(method);
        }
        if (family > 0) {
            c(family).asks++;
        }
        if (!RlSchema.isPhaseA(family)) {
            return null; // Phase B and the mechanical kinds: Forge decides (forge_decided by method)
        }
        if (RECORD_BARE && roles[seat] == Role.RECORD) {
            return null; // diagnosis only: the bridge's own BRIDGE-mode work, none of the seat's
        }
        final RlCandidates.Menu m = RlCandidates.build(g, player, method, kind, body, objs);
        if (m == null || m.unposable != null) {
            forgeDecidedExtra.merge("unposed:" + RlSchema.familyName(family), 1, Integer::sum);
            return null;
        }
        if (m.trivial) {
            c(family).trivial++;
            if (roles[seat] == Role.RECORD) {
                return null;
            }
            lastAnswered.put(method, -1);
            lastFamily.put(method, family);
            ok(method);
            return m.answer(m.trivialSteps);
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
        final RlFeaturizer.Obs obs = feat.observe(g, player, m.mullK, priv);
        unkNames += obs.unknownNames;
        m.bind(obs, feat, player);
        if (roles[seat] == Role.RECORD) {
            pending.push(new Pending(method, seat, player, m, obs));
            while (pending.size() > 32) {
                pending.removeLast();
            }
            return null;
        }
        // ---- RL seat: DECIDE → DECISION
        final RlWire.Decide frame = frame(seat, g, m, obs);
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
        final String bad = d.status == RlWire.ST_OK ? m.validate(d.steps) : null;
        if (bad != null) {
            System.err.println("[rlseat] invalid DECISION steps for dec " + frame.decIdx + ": " + bad);
            endGame("server_error");
            return null;
        }
        noteSent(seat, family, m, frame, d.steps);
        noteVersion(seat, controllers[seat].substring(3), d.policyVersion);
        if (d.status == RlWire.ST_DELEGATE) {
            overridden.add(frame.decIdx);
            forgeDecidedExtra.merge("serverDelegate:" + RlSchema.familyName(family), 1, Integer::sum);
            return null;
        }
        final JsonObject ans = m.answer(d.steps);
        if (listener != null) {
            listener.onFrame(g, player, frame, m, obs, d.steps, ans);
        }
        lastAnswered.put(method, frame.decIdx);
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

    private RlWire.Decide frame(final int seat, final Game g, final RlCandidates.Menu m, final RlFeaturizer.Obs o) {
        final RlWire.Decide f = new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = decIdx;
        f.seat = seat;
        f.family = m.family;
        f.mode = m.mode;
        f.flags = (o.hasPriv ? RlWire.F_HAS_PRIV : 0) | (o.truncated ? RlWire.F_TRUNC_TOKENS : 0);
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
        System.arraycopy(o.ctx, 0, f.ctx, 0, RlSchema.N_CTX);
        f.candKind = m.kindA;
        f.candTok = m.tok;
        f.candCard = m.card;
        f.candTgt = m.tgt;
        f.candSlot = m.slot;
        f.candNum = m.num;
        f.candAbility = m.ability;
        f.candFlags = m.flagsA;
        f.slotTok = m.slotTok;
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
        if (d == null) {
            return;
        }
        final Integer fam = lastFamily.get(method);
        okAnswers.merge(method, -1, Integer::sum);
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
        final int[] snap = isolateIds() ? idValues() : null;
        try {
            echoed(g, p, forgeAnswer, forgeDecision);
        } finally {
            if (snap != null) {
                restoreIds(snap);
            }
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
        final short[] steps = m.fromEcho(forgeAnswer);
        if (steps == null) {
            mapFailed.merge(m.family, 1, Integer::sum);
        } else if (!sendRecord(p.seat, g, m, p.obs, steps)) {
            return;
        }
        if (m.family == RlSchema.F_PRIORITY && forgeDecision instanceof List && !((List<?>) forgeDecision).isEmpty()
                && ((List<?>) forgeDecision).get(0) instanceof SpellAbility) {
            final SpellAbility chosen = (SpellAbility) ((List<?>) forgeDecision).get(0);
            for (Object[] t : RlCandidates.targetsFromChosen(g, chosen)) {
                final RlCandidates.Menu tm = (RlCandidates.Menu) t[0];
                final short[] ts = (short[]) t[1];
                synthTargets++;
                c(RlSchema.F_TARGETS).asks++;
                if (tm.unposable != null || ts == null) {
                    mapFailed.merge(RlSchema.F_TARGETS, 1, Integer::sum);
                    continue;
                }
                if (tm.trivial) {
                    c(RlSchema.F_TARGETS).trivial++;
                    continue;
                }
                if (tm.C() > RlSchema.C_MAX) {
                    forgeDecidedExtra.merge("capC:TARGETS", 1, Integer::sum);
                    continue;
                }
                tm.bind(p.obs, feat, p.player);
                if (!sendRecord(p.seat, g, tm, p.obs, ts)) {
                    return;
                }
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
