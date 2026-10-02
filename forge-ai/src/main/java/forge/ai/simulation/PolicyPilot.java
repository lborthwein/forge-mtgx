package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AiCache;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * L2 policy P1 for one seat of one live game (lane l2-fork-1001; design {@code l2-prep-1001/l2-design.md} §3, decode
 * D1). Forge AI proposes every priority action as usual ({@link PlayerControllerPolicy}); the pilot may replace that
 * answer, and only with a legal spell cast from our hand or a land play:
 * <ul>
 * <li><b>Own turn, empty stack</b> (class {@code cast}): at our first main-phase priority the service's {@code plan}
 * gives P(cast this turn) per hand card. Forge's proposal to cast a hand card with a known p below the threshold is
 * <i>vetoed</i>: Forge AI is asked again with every such card excluded. When Forge AI then passes in a main phase, the
 * castable sorcery-speed hand card with the highest p &ge; threshold is <i>forced</i> (each card at most once per
 * turn). Our own instants on our turn are vetoed, never forced. With a non-empty stack on our turn Forge AI decides.</li>
 * <li><b>Land</b> (class {@code land}): when Forge AI plays a land from hand, the hand land with the highest
 * P(land) is played instead (ties to Forge's card).</li>
 * <li><b>Opponent's turn</b> (class {@code react}): our view at the first priority of the turn is cached; when an
 * opponent object is on top of the stack, or at their end step with an empty stack, the service's {@code react} head
 * (context: the opponent's casts this turn so far) picks the castable hand card with the highest p &ge; threshold, which
 * is cast (a counter targets the top stack object when Forge AI declines to choose). Forge AI's own proposals to cast
 * a hand card on their turn are vetoed when its react p is known and below the threshold.</li>
 * </ul>
 * Forge AI keeps targets, modes, X, payments, activated abilities, combat, mulligans, tutors and every choice inside a
 * resolving effect. Every state the service sees, and every castability check, comes from a {@link GameCopier} copy
 * of the live game in its own id scope, AI cache scope and random stream, so a decision the pilot does not change
 * leaves the live game exactly as Forge AI alone would (the shadow do-no-harm gate rests on this). A forced action is
 * checked for legality on the live game (restrictions, timing, cost, targets) before it is returned; Forge AI's
 * refusal to target it, or any failed check, keeps Forge AI's answer and is counted ({@code refused}). A failed
 * service call keeps Forge AI's answer for that turn's plan / that react context and is counted.
 *
 * <p>Shadow: every call and check is made and counted as "would", Forge AI's answer is always played (a would-force
 * is only seen where Forge AI itself passes, since the veto's re-ask is not made).
 */
public final class PolicyPilot {

    public static final class Config {
        public String url = null;
        public String checkpointSha256 = null;
        public int timeoutMs = 2000;
        /** Classes (D1): own-turn veto/force, land swap, opponent-turn react cast/veto. */
        public boolean cast = true;
        public boolean land = true;
        public boolean react = true;
        /** Compute, call and count, but always play Forge AI's answer. */
        public boolean shadow = false;
        public double threshold = 0.5;
        public int maxForcesPerTurn = 8;
        /** One compact event string per consulted decision in the row (diagnostics). */
        public boolean log = false;
        /** Seeds the copies' private random streams (never the live game's). */
        public long seed = 0L;

        public static Config fromJson(JsonObject o) {
            final Config c = new Config();
            if (o == null) {
                return c;
            }
            c.url = o.has("url") ? o.get("url").getAsString() : null;
            c.checkpointSha256 = o.has("checkpointSha256") ? o.get("checkpointSha256").getAsString() : null;
            c.timeoutMs = o.has("timeoutMs") ? o.get("timeoutMs").getAsInt() : 2000;
            c.cast = !o.has("cast") || o.get("cast").getAsBoolean();
            c.land = !o.has("land") || o.get("land").getAsBoolean();
            c.react = !o.has("react") || o.get("react").getAsBoolean();
            c.shadow = o.has("shadow") && o.get("shadow").getAsBoolean();
            c.threshold = o.has("threshold") ? o.get("threshold").getAsDouble() : 0.5;
            c.maxForcesPerTurn = o.has("maxForcesPerTurn") ? o.get("maxForcesPerTurn").getAsInt() : 8;
            c.log = o.has("log") && o.get("log").getAsBoolean();
            return c;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("url", url);
            o.addProperty("checkpointSha256", checkpointSha256);
            o.addProperty("timeoutMs", timeoutMs);
            o.addProperty("cast", cast);
            o.addProperty("land", land);
            o.addProperty("react", react);
            o.addProperty("shadow", shadow);
            o.addProperty("threshold", threshold);
            o.addProperty("maxForcesPerTurn", maxForcesPerTurn);
            o.addProperty("decode", "D1");
            o.addProperty("seed", seed);
            return o;
        }
    }

    /** Per-game counters; "changed" = the played action differs from Forge AI's answer. */
    public static final class Stats {
        public long decisions, ownTurn, oppTurn;
        public long planCalls, reactCalls, failures, plans, reactRoots;
        public long callNanos, maxCallNanos;
        public String lastError;
        // consultations (the pilot had an opinion that could change the answer)
        public long vetoConsults, forceConsults, landConsults, reactConsults, reactVetoConsults;
        // changed decisions, by class
        public long veto, force, landSwap, reactCast, reactVeto;
        // shadow: would have changed
        public long wouldVeto, wouldForce, wouldLandSwap, wouldReactCast, wouldReactVeto;
        // agreements: the policy's pick was Forge AI's answer
        public long forceAgree, landAgree, reactAgree;
        // Forge AI refused to target (or a live check failed) a forced cast / land
        public long forceRefused, reactRefused, landRefused;
        // a policy candidate with p >= threshold was not castable at that priority (copy check)
        public long notCastable;
        public long copyFailures, stackUnsupported, encodeFailures;
        public long unknownCards, truncated;
        public String digest, checkpoint;
        public final List<String> events = new ArrayList<>();

        public long changed() {
            return veto + force + landSwap + reactCast + reactVeto;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("decisions", decisions);
            o.addProperty("ownTurn", ownTurn);
            o.addProperty("oppTurn", oppTurn);
            o.addProperty("planCalls", planCalls);
            o.addProperty("reactCalls", reactCalls);
            o.addProperty("failures", failures);
            if (lastError != null) {
                o.addProperty("lastError", lastError);
            }
            o.addProperty("plans", plans);
            o.addProperty("reactRoots", reactRoots);
            o.addProperty("callMs", Math.round(callNanos / 1e4) / 100.0);
            o.addProperty("maxCallMs", Math.round(maxCallNanos / 1e4) / 100.0);
            final JsonObject cons = new JsonObject();
            cons.addProperty("veto", vetoConsults);
            cons.addProperty("force", forceConsults);
            cons.addProperty("land", landConsults);
            cons.addProperty("react", reactConsults);
            cons.addProperty("reactVeto", reactVetoConsults);
            o.add("consults", cons);
            final JsonObject ch = new JsonObject();
            ch.addProperty("veto", veto);
            ch.addProperty("force", force);
            ch.addProperty("landSwap", landSwap);
            ch.addProperty("reactCast", reactCast);
            ch.addProperty("reactVeto", reactVeto);
            o.add("changed", ch);
            o.addProperty("changedTotal", changed());
            final JsonObject wo = new JsonObject();
            wo.addProperty("veto", wouldVeto);
            wo.addProperty("force", wouldForce);
            wo.addProperty("landSwap", wouldLandSwap);
            wo.addProperty("reactCast", wouldReactCast);
            wo.addProperty("reactVeto", wouldReactVeto);
            o.add("would", wo);
            final JsonObject ag = new JsonObject();
            ag.addProperty("force", forceAgree);
            ag.addProperty("land", landAgree);
            ag.addProperty("react", reactAgree);
            o.add("agree", ag);
            final JsonObject rf = new JsonObject();
            rf.addProperty("force", forceRefused);
            rf.addProperty("react", reactRefused);
            rf.addProperty("land", landRefused);
            o.add("refused", rf);
            o.addProperty("notCastable", notCastable);
            o.addProperty("copyFailures", copyFailures);
            o.addProperty("stackUnsupported", stackUnsupported);
            o.addProperty("encodeFailures", encodeFailures);
            o.addProperty("unknownCards", unknownCards);
            o.addProperty("truncated", truncated);
            o.addProperty("policyDigest", digest);
            o.addProperty("policyCheckpoint", checkpoint);
            if (!events.isEmpty()) {
                final JsonArray ev = new JsonArray();
                for (String e : events) {
                    ev.add(e);
                }
                o.add("events", ev);
            }
            return o;
        }
    }

    private static final int MAX_EVENTS = 600;

    private final Config cfg;
    private final PolicyClient client;
    private final Stats stats = new Stats();
    private Game live;
    private Player seat;
    private JsonObject deck;
    private long copies = 0;

    // per-turn state
    private int turn = Integer.MIN_VALUE;
    private boolean planTried, rootTried;
    /** fid -> {cast, land}; null = no plan this turn (not yet asked, or the call failed). */
    private Map<Integer, Double[]> plan;
    private JsonObject reactRoot;
    /** oppCast context (joined names) -> fid -> react p; a failed call is cached as null (no retry this turn). */
    private final Map<String, Map<Integer, Double>> reactCache = new HashMap<>();
    private final Set<Integer> forced = new HashSet<>();
    private int forcesThisTurn;

    public PolicyPilot(Config cfg, PolicyClient client) {
        this.cfg = cfg;
        this.client = client;
    }

    /** The bench / interactive constructor: checks the pin (once per JVM and URL) and builds the HTTP client. */
    public static PolicyPilot create(Config cfg) {
        PolicyClient.checkHealth(cfg.url, cfg.checkpointSha256, cfg.timeoutMs);
        return new PolicyPilot(cfg, new PolicyClient(cfg.url, cfg.timeoutMs));
    }

    /** Bind to the live game and our seat in it; the pilot acts nowhere else (copies, other players). */
    public void bind(Game game, Player me) {
        this.live = game;
        this.seat = me;
    }

    public Config getConfig() {
        return cfg;
    }

    public Stats getStats() {
        stats.digest = client.digestHex();
        stats.checkpoint = client.checkpointSha256;
        stats.unknownCards = client.unknownCards;
        stats.truncated = client.truncated;
        return stats;
    }

    private void event(String s) {
        if (cfg.log && stats.events.size() < MAX_EVENTS) {
            stats.events.add(s);
        }
    }

    // ------------------------------------------------------------------ the decision

    /**
     * @param def Forge AI's own answer at this priority (already computed by the caller, exactly as without the pilot)
     * @return the action to play: {@code def}, or a legal replacement
     */
    public List<SpellAbility> decide(PlayerControllerAi ctrl, List<SpellAbility> def) {
        final Game g = ctrl.getGame();
        final Player me = ctrl.getPlayer();
        if (g != live || me != seat || g.isGameOver()) {
            return def;
        }
        stats.decisions++;
        final PhaseHandler ph = g.getPhaseHandler();
        if (ph.getTurn() != turn) {
            turn = ph.getTurn();
            planTried = rootTried = false;
            plan = null;
            reactRoot = null;
            reactCache.clear();
            forced.clear();
            forcesThisTurn = 0;
        }
        if (ph.getPlayerTurn() == me) {
            stats.ownTurn++;
            return ownTurn(ctrl, g, me, ph, def);
        }
        stats.oppTurn++;
        return oppTurn(ctrl, g, me, ph, def);
    }

    private List<SpellAbility> ownTurn(PlayerControllerAi ctrl, Game g, Player me, PhaseHandler ph, List<SpellAbility> def) {
        if (!(cfg.cast || cfg.land) || !g.getStack().isEmpty()) {
            return def;
        }
        final boolean main = ph.getPhase() != null && ph.getPhase().isMain();
        if (plan == null && !planTried && main) {
            planTried = true;
            plan = callPlan(g, me);
        }
        if (plan == null) {
            return def;
        }
        final SpellAbility d = first(def);
        if (d != null && d.isLandAbility()) {
            return cfg.land ? landSwap(ctrl, g, me, def, d) : def;
        }
        List<SpellAbility> answer = def;
        if (cfg.cast && d != null && handSpell(d, me)) {
            final Double p = castP(d.getHostCard().getId());
            if (p != null) {
                stats.vetoConsults++;
                if (p < cfg.threshold) {
                    if (cfg.shadow) {
                        stats.wouldVeto++;
                        event("T" + turn + " wouldVeto " + d.getHostCard().getName());
                        return def;
                    }
                    answer = reask(ctrl, me, c -> {
                        final Double q = castP(c.getId());
                        return q != null && q < cfg.threshold;
                    });
                    if (!same(answer, def)) {
                        stats.veto++;
                        event("T" + turn + " veto " + d.getHostCard().getName() + " -> " + label(answer));
                    }
                }
            }
        }
        if (cfg.cast && first(answer) == null && main) {
            return force(ctrl, g, me, answer);
        }
        return answer;
    }

    private List<SpellAbility> oppTurn(PlayerControllerAi ctrl, Game g, Player me, PhaseHandler ph, List<SpellAbility> def) {
        if (!cfg.react) {
            return def;
        }
        if (!rootTried) {
            rootTried = true;
            reactRoot = encodeRoot(g, me);
            if (reactRoot != null) {
                stats.reactRoots++;
            }
        }
        if (reactRoot == null) {
            return def;
        }
        final boolean stackEmpty = g.getStack().isEmpty();
        final String trigger;
        if (!stackEmpty && g.getStack().peekAbility() != null && g.getStack().peekAbility().getActivatingPlayer() != me) {
            trigger = "stack";
        } else if (stackEmpty && ph.is(PhaseType.END_OF_TURN)) {
            trigger = "end";
        } else {
            trigger = null;
        }
        final SpellAbility d = first(def);
        final boolean dHand = d != null && handSpell(d, me);
        if (trigger == null && !dHand) {
            return def;
        }
        final List<String> ctx = oppCasts(g, me);
        final Map<Integer, Double> rp = react(g, me, ctx, trigger == null ? "veto" : trigger);
        if (rp == null) {
            return def;
        }
        if (trigger != null && forcesThisTurn < cfg.maxForcesPerTurn) {
            final List<Integer> want = ranked(me, rp, false);
            if (!want.isEmpty()) {
                stats.reactConsults++;
                final LookaheadSearch.Cand pick = firstCastable(g, me, want, false);
                if (pick != null) {
                    if (d != null && d.isSpell() && d.getHostCard().getId() == pick.hostId) {
                        stats.reactAgree++;
                        return def;
                    }
                    if (cfg.shadow) {
                        stats.wouldReactCast++;
                        event("T" + turn + " wouldReact " + pick.label);
                        return def;
                    }
                    forced.add(pick.hostId);
                    forcesThisTurn++;
                    final List<SpellAbility> m = mapForce(ctrl, g, me, pick, "stack".equals(trigger));
                    if (m == null) {
                        stats.reactRefused++;
                        event("T" + turn + " reactRefused " + pick.label);
                    } else {
                        stats.reactCast++;
                        event("T" + turn + " react(" + trigger + "," + ctx.size() + ") " + pick.label + " over " + label(def));
                        return m;
                    }
                }
            }
        }
        if (dHand) {
            final Double p = rp.get(d.getHostCard().getId());
            if (p != null) {
                stats.reactVetoConsults++;
                if (p < cfg.threshold) {
                    if (cfg.shadow) {
                        stats.wouldReactVeto++;
                        event("T" + turn + " wouldReactVeto " + d.getHostCard().getName());
                        return def;
                    }
                    final List<SpellAbility> answer = reask(ctrl, me, c -> {
                        final Double q = rp.get(c.getId());
                        return q != null && q < cfg.threshold;
                    });
                    if (!same(answer, def)) {
                        stats.reactVeto++;
                        event("T" + turn + " reactVeto " + d.getHostCard().getName() + " -> " + label(answer));
                    }
                    return answer;
                }
            }
        }
        return def;
    }

    /** Forge AI passed in our main phase: force the best castable sorcery-speed plan card, if any. */
    private List<SpellAbility> force(PlayerControllerAi ctrl, Game g, Player me, List<SpellAbility> answer) {
        if (forcesThisTurn >= cfg.maxForcesPerTurn) {
            return answer;
        }
        final Map<Integer, Double> cast = new HashMap<>();
        for (Map.Entry<Integer, Double[]> e : plan.entrySet()) {
            if (e.getValue()[0] != null) {
                cast.put(e.getKey(), e.getValue()[0]);
            }
        }
        final List<Integer> want = ranked(me, cast, true);
        if (want.isEmpty()) {
            return answer;
        }
        stats.forceConsults++;
        final LookaheadSearch.Cand pick = firstCastable(g, me, want, true);
        if (pick == null) {
            return answer;
        }
        if (cfg.shadow) {
            stats.wouldForce++;
            event("T" + turn + " wouldForce " + pick.label);
            return answer;
        }
        forced.add(pick.hostId);
        forcesThisTurn++;
        final List<SpellAbility> m = mapForce(ctrl, g, me, pick, false);
        if (m == null) {
            stats.forceRefused++;
            event("T" + turn + " forceRefused " + pick.label);
            return answer;
        }
        stats.force++;
        event("T" + turn + " force " + pick.label);
        return m;
    }

    /** Forge AI plays a land from hand: play the hand land with the highest P(land) instead (ties to Forge's card). */
    private List<SpellAbility> landSwap(PlayerControllerAi ctrl, Game g, Player me, List<SpellAbility> def, SpellAbility d) {
        final Card forgeLand = d.getHostCard();
        if (forgeLand.getZone() == null || !forgeLand.isInZone(ZoneType.Hand)) {
            return def;
        }
        Card best = null;
        double bestP = -1;
        final Double fp = landP(forgeLand.getId());
        if (fp != null) {
            best = forgeLand;
            bestP = fp;
        }
        boolean any = false;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            final Double p = landP(c.getId());
            if (p == null || c == forgeLand) {
                continue;
            }
            any = true;
            if (p > bestP) {
                best = c;
                bestP = p;
            }
        }
        if (!any) {
            return def;
        }
        stats.landConsults++;
        if (best == null || best == forgeLand) {
            stats.landAgree++;
            return def;
        }
        // Legality on the live game (read-only checks): a land ability of the chosen card that can be played now.
        SpellAbility play = null;
        for (SpellAbility sa : best.getAllPossibleAbilities(me, true)) {
            if (sa.isLandAbility()) {
                sa.setActivatingPlayer(me);
                if (sa.canPlay()) {
                    play = sa;
                    break;
                }
            }
        }
        if (play == null) {
            stats.landRefused++;
            return def;
        }
        if (cfg.shadow) {
            stats.wouldLandSwap++;
            event("T" + turn + " wouldLand " + best.getName() + " over " + forgeLand.getName());
            return def;
        }
        stats.landSwap++;
        event("T" + turn + " land " + best.getName() + " over " + forgeLand.getName());
        final List<SpellAbility> l = new ArrayList<>();
        l.add(play);
        return l;
    }

    // ------------------------------------------------------------------ service calls

    private Map<Integer, Double[]> callPlan(Game g, Player me) {
        final JsonObject root = encodeRoot(g, me);
        if (root == null) {
            return null;
        }
        final int[] fids = handFids(me);
        if (fids.length == 0) {
            return Collections.emptyMap();
        }
        final JsonObject req = request(g, me, root, fids);
        final long t = System.nanoTime();
        final Double[][] p = client.call(PolicyClient.PLAN, req, fids, "cast", "land");
        noteCall(System.nanoTime() - t, true);
        if (p == null) {
            fail("plan", client.lastError);
            return null;
        }
        stats.plans++;
        final Map<Integer, Double[]> m = new HashMap<>();
        for (int i = 0; i < fids.length; i++) {
            m.put(fids[i], p[i]);
        }
        return m;
    }

    private Map<Integer, Double> react(Game g, Player me, List<String> ctx, String trigger) {
        final String key = String.join("\u0001", ctx);
        if (reactCache.containsKey(key)) {
            return reactCache.get(key); // null = this context's call failed (no retry this turn)
        }
        final int[] fids = handFids(me);
        final Map<Integer, Double> m = new HashMap<>();
        if (fids.length == 0) {
            reactCache.put(key, m);
            return m;
        }
        final JsonObject req = request(g, me, reactRoot, fids);
        final JsonObject c = new JsonObject();
        final JsonArray oc = new JsonArray();
        for (String n : ctx) {
            oc.add(n);
        }
        c.add("oppCast", oc);
        c.addProperty("trigger", trigger);
        req.add("context", c);
        final long t = System.nanoTime();
        final Double[][] p = client.call(PolicyClient.REACT, req, fids, "react");
        noteCall(System.nanoTime() - t, false);
        if (p == null) {
            fail("react", client.lastError);
            reactCache.put(key, null);
            return null;
        }
        for (int i = 0; i < fids.length; i++) {
            if (p[i][0] != null) {
                m.put(fids[i], p[i][0]);
            }
        }
        reactCache.put(key, m);
        return m;
    }

    private void noteCall(long dt, boolean plan) {
        if (plan) {
            stats.planCalls++;
        } else {
            stats.reactCalls++;
        }
        stats.callNanos += dt;
        stats.maxCallNanos = Math.max(stats.maxCallNanos, dt);
    }

    private void fail(String what, String err) {
        stats.failures++;
        stats.lastError = what + ": " + err;
        event("T" + turn + " FAIL " + what + ": " + err);
        System.err.println("[policy] " + what + " call failed in turn " + turn + ", playing Forge AI: " + err);
    }

    private JsonObject request(Game g, Player me, JsonObject root, int[] fids) {
        final JsonObject req = new JsonObject();
        req.addProperty("schema", PolicyClient.REQUEST_SCHEMA);
        req.addProperty("seat", forge.bench.StateEncoder.playerIndex(g, me));
        req.addProperty("startingSeat", g.getStartingPlayer() == null ? -1
                : forge.bench.StateEncoder.playerIndex(g, g.getStartingPlayer()));
        final JsonArray mull = new JsonArray();
        for (Player pl : g.getPlayers()) {
            mull.add(pl.getStats().getMulliganCount());
        }
        req.add("mulligans", mull);
        req.add("deck", deckOf(me));
        req.add("root", root);
        final JsonArray cs = new JsonArray();
        for (int i = 0; i < fids.length; i++) {
            final JsonObject c = new JsonObject();
            c.addProperty("id", i);
            c.addProperty("fid", fids[i]);
            cs.add(c);
        }
        req.add("cards", cs);
        return req;
    }

    private JsonObject deckOf(Player me) {
        if (deck == null) {
            final JsonObject d = new JsonObject();
            final forge.deck.Deck dk = me.getRegisteredPlayer() == null ? null : me.getRegisteredPlayer().getDeck();
            if (dk != null && dk.has(forge.deck.DeckSection.Main)) {
                final java.util.TreeMap<String, Integer> byName = new java.util.TreeMap<>();
                for (Map.Entry<forge.item.PaperCard, Integer> e : dk.get(forge.deck.DeckSection.Main)) {
                    byName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
                }
                for (Map.Entry<String, Integer> e : byName.entrySet()) {
                    d.addProperty(e.getKey(), e.getValue());
                }
            }
            deck = d;
        }
        return deck;
    }

    // ------------------------------------------------------------------ copies (never the live game)

    private interface InCopy<T> {
        T apply(Game g, Player me);
    }

    /** Run {@code f} on a fresh copy of the live game, in its own id / AI-cache scope and random stream. */
    private <T> T inCopy(Game g, Player liveMe, boolean withStack, InCopy<T> f) {
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(LookaheadSearch.mix(cfg.seed, 0x9011c7L + copies++)));
        AiCache.openScope();
        final Object prevIds = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            final GameCopier copier = new GameCopier(g, true);
            copier.setCopyStack(withStack);
            final Game c = copier.makeCopy();
            return f.apply(c, (Player) copier.find(liveMe));
        } finally {
            AiCache.closeScope();
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    private JsonObject encodeRoot(Game g, Player me) {
        try {
            return inCopy(g, me, false, forge.bench.StateEncoder::encode);
        } catch (RuntimeException e) {
            stats.encodeFailures++;
            event("T" + turn + " encodeFail " + e);
            return null;
        }
    }

    /**
     * The first of {@code want} (hand card ids, best first) with a spell ability castable now, checked in a copy
     * (restrictions, timing, cost, full targetability); null if none. {@code sorcerySpeed}: instants and flash spells
     * are skipped (own turn: veto only for them).
     */
    private LookaheadSearch.Cand firstCastable(Game g, Player liveMe, List<Integer> want, boolean sorcerySpeed) {
        final boolean withStack = !g.getStack().isEmpty();
        if (withStack) {
            final String why = GameCopier.stackUnsupported(g);
            if (why != null) {
                stats.stackUnsupported++;
                return null;
            }
        }
        final LookaheadSearch.Cand pick;
        try {
            pick = inCopy(g, liveMe, withStack, (c, me) -> {
                for (int fid : want) {
                    final Card card = c.findById(fid);
                    if (card == null || !card.isInZone(ZoneType.Hand)) {
                        continue;
                    }
                    final SpellAbility sa = castableSpell(card, me, sorcerySpeed);
                    if (sa != null) {
                        return new LookaheadSearch.Cand(sa, false);
                    }
                    stats.notCastable++;
                }
                return null;
            });
        } catch (RuntimeException e) {
            stats.copyFailures++;
            event("T" + turn + " copyFail " + e);
            return null;
        }
        return pick;
    }

    static SpellAbility castableSpell(Card card, Player me, boolean sorcerySpeed) {
        final List<SpellAbility> all = ComputerUtilAbility.getOriginalAndAltCostAbilities(
                ComputerUtilAbility.getSpellAbilities(new CardCollection(card), me), me);
        for (SpellAbility sa : all) {
            if (!sa.isSpell() || sa.isLandAbility()) {
                continue;
            }
            sa.setActivatingPlayer(me);
            if (sorcerySpeed && (card.isInstant() || sa.withFlash(card, me))) {
                continue;
            }
            if (legal(sa, me, true)) {
                return sa;
            }
        }
        return null;
    }

    /** Rules legality of casting {@code sa} now (targets: fully targetable, or, when chosen, all chosen and valid). */
    static boolean legal(SpellAbility sa, Player me, boolean targetable) {
        final Card host = sa.getHostCard();
        if (!sa.checkRestrictions(host, me) || !sa.isLegalAfterStack() || !sa.canPlay() || !sa.canCastTiming(me)) {
            return false;
        }
        if (!ComputerUtilCost.canPayCost(sa, me, false)) {
            return false;
        }
        return targetable ? ComputerUtilAbility.isFullyTargetable(sa) : !needsTargets(sa);
    }

    static boolean needsTargets(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && !s.isTargetNumberValid()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ live actions

    /**
     * The chosen copy candidate on the live game: Forge AI fills targets/modes/X ({@code canPlaySa}); for a react cast
     * at an opponent's stack object that Forge AI declines to target, its single target slot takes the top stack
     * object when legal. Then the full legality check on the live game. Null = refused (nothing returned is illegal).
     */
    private List<SpellAbility> mapForce(PlayerControllerAi ctrl, Game g, Player me, LookaheadSearch.Cand c, boolean counterTop) {
        final SpellAbility sa = LookaheadSearch.locate(g, me, c);
        if (sa == null || !sa.isSpell()) {
            return null;
        }
        sa.setActivatingPlayer(me);
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                s.resetTargets();
            }
        }
        final AiPlayDecision dec = ctrl.getAi().canPlaySa(sa);
        if (dec != AiPlayDecision.WillPlay && needsTargets(sa) && counterTop && !g.getStack().isEmpty()) {
            final SpellAbility slot = LookaheadSearch.singleTargetSlot(sa);
            final SpellAbility top = g.getStack().peekAbility();
            if (slot != null && top != null && slot.canTarget(top)) {
                slot.resetTargets();
                slot.getTargets().add(top);
            }
        }
        if (needsTargets(sa) || !LookaheadSearch.targetsInGame(sa, g) || !legal(sa, me, false)) {
            return null;
        }
        final List<SpellAbility> l = new ArrayList<>();
        l.add(sa);
        return l;
    }

    /** Ask Forge AI again, never considering a hand spell whose host the predicate rejects. */
    private static List<SpellAbility> reask(PlayerControllerAi ctrl, Player me, Predicate<Card> vetoed) {
        ctrl.getAi().setPolicyVeto(sa -> handSpell(sa, me) && vetoed.test(sa.getHostCard()));
        try {
            return ctrl.getAi().chooseSpellAbilityToPlay();
        } finally {
            ctrl.getAi().setPolicyVeto(null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Double castP(int fid) {
        final Double[] p = plan == null ? null : plan.get(fid);
        return p == null ? null : p[0];
    }

    private Double landP(int fid) {
        final Double[] p = plan == null ? null : plan.get(fid);
        return p == null ? null : p[1];
    }

    /**
     * Our hand cards (live hand order) with p &ge; threshold, not forced this turn, best first (ties: hand order);
     * {@code nonLand}: lands excluded.
     */
    private List<Integer> ranked(Player me, Map<Integer, Double> p, boolean nonLand) {
        final List<Card> hand = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            final Double v = p.get(c.getId());
            if (v != null && v >= cfg.threshold && !forced.contains(c.getId()) && !(nonLand && c.isLand())) {
                hand.add(c);
            }
        }
        hand.sort((a, b) -> Double.compare(p.get(b.getId()), p.get(a.getId())));
        final List<Integer> out = new ArrayList<>();
        for (Card c : hand) {
            out.add(c.getId());
        }
        return out;
    }

    private static int[] handFids(Player me) {
        final List<Card> h = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        final int[] f = new int[h.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = h.get(i).getId();
        }
        return f;
    }

    /** The opponent's spells cast this turn so far (names, cast order; public information). */
    static List<String> oppCasts(Game g, Player me) {
        final List<String> out = new ArrayList<>();
        for (SpellAbility sp : g.getStack().getSpellsCastThisTurn()) {
            final Player who = sp.getActivatingPlayer();
            if (who != null && who != me && who.isOpponentOf(me) && sp.getHostCard() != null) {
                out.add(sp.getHostCard().getName());
            }
        }
        return out;
    }

    static boolean handSpell(SpellAbility sa, Player me) {
        if (sa == null || !sa.isSpell() || sa.isLandAbility()) {
            return false;
        }
        final Card h = sa.getHostCard();
        return h != null && h.getController() == me && h.isInZone(ZoneType.Hand);
    }

    private static SpellAbility first(List<SpellAbility> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private static boolean same(List<SpellAbility> a, List<SpellAbility> b) {
        return first(a) == first(b);
    }

    private static String label(List<SpellAbility> l) {
        final SpellAbility s = first(l);
        return s == null ? "pass" : s.getHostCard().getName();
    }
}
