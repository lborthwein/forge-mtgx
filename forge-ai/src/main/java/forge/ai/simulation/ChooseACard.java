package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AiCache;
import forge.ai.CacHook;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * r2-choose-a-card-1029 (research; GOAL-PROMPT N1; DESIGN-r2.md): at a choice that assembles a combo, every legal answer
 * (capped, deduped by its result) is applied on a COPY of the live game, all resulting positions are valued in ONE
 * batched {@link Valuer} pass (the search service's value tower via {@link LookaheadSearch.SearchHooks}), the honest
 * solver ({@link Checker}) checks the top-m, and the best answer is played. Doomsday-size piles use a route-guided beam
 * ({@link PileBeam}).
 *
 * <p>Off unless a host installs it ({@link #install}): {@link PlayerControllerAi}'s hook field is null and nothing here is
 * constructed. Installed in {@link Config.Mode#SHADOW}, everything runs inside the copies' own scopes (random stream, id
 * scope, AI caches) and the live answer is always Forge AI's, so the live game is byte for byte the uninstalled one.
 * Every budget is a count (answers, steps, worlds, solver nodes): no decision reads a clock.
 */
public final class ChooseACard implements CacHook {

    /** The choice kinds (DESIGN-r2 §1, §12.1). */
    public enum Kind {
        SEARCH, MULTI, PILE, DIG, DIGPICK, DISCARD, SCRY, SURVEIL, ORDER, MULLIGAN, LAND, RETURN_COST,
        // r3-inline-choices-1030 (FLAG-R3-5): opt-in by name in "kinds"; never on by default (LEGACY below)
        PAYMENT, COLOR, COST_CARDS, LIFE, SACRIFICE, ORDER_TRIGGERS, PAY_TO_PREVENT,
        // r3-target-choices-1030
        TARGET
    }

    /** The kinds a config without "kinds" turns on: #143's, so an old config means what it meant. */
    public static final Set<Kind> LEGACY = java.util.Collections.unmodifiableSet(java.util.EnumSet.range(Kind.SEARCH,
            Kind.RETURN_COST));
    /** r3-inline-choices-1030: the kinds decided at the priority of a cast (on a copy of the position before it). */
    static final Set<Kind> CAST_KINDS = java.util.EnumSet.of(Kind.PAYMENT, Kind.COLOR, Kind.COST_CARDS, Kind.LIFE,
            Kind.SACRIFICE, Kind.TARGET);

    // ------------------------------------------------------------------------------------------------ config

    public static final class Config {
        public enum Mode { OFF, SHADOW, ON }

        public Mode mode = Mode.OFF;
        public Set<Kind> kinds = new java.util.TreeSet<>(LEGACY);
        /** Answers applied per decision (Forge's answer included). */
        public int cap = 48;
        /** Answers the solver checks (by v, best first); 0 = no solver. */
        public int topM = 3;
        /** Copies for an answer that shuffles our library (each its own stream), averaged. */
        public int shuffleWorlds = 4;
        /** Main-loop steps a copy may take, after the answer, to the next priority (the leaf). */
        public int settleSteps = 200;
        /** Pile beam: prefixes kept per depth, pool names tried per prefix. */
        public int beamWidth = 6;
        /** Pool names tried per prefix (the pool is ordered by route role, then mana value): names past it are logged. */
        public int beamBranch = 40;
        /** A departure must beat Forge's answer's score by more than this. */
        public double margin = 0.0;
        public long seed = 1029L;
        /** r3-inline-choices-1030: payment-remainder variants enumerated per cast (Forge's payment not counted). */
        public int payCap = 6;
        /** r3-inline-choices-1030: trigger orders enumerated per ask (Forge's order included; permutations capped). */
        public int orderCap = 24;
        /**
         * The honest win check's label spec ({@link HonestLabel.Spec#parse}: belief re-draw, hideOwnLibrary, counter
         * budgets); built into the solver check at install. ON refuses to install without a solver check.
         */
        public JsonObject label = null;
        /**
         * Tests only (never a JSON key): allow a stub valuer, no solver check and no belief re-draw. Every real install
         * refuses ON without the value tower and the solver check, and ON or SHADOW without the belief re-draw.
         */
        public boolean testUnsafe = false;

        public static Config fromJson(final JsonObject o) {
            final Config c = new Config();
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("chooseACard: unknown key " + k);
                }
            }
            if (o.has("mode")) c.mode = Mode.valueOf(o.get("mode").getAsString().toUpperCase(java.util.Locale.ROOT));
            if (o.has("kinds")) {
                c.kinds = new java.util.TreeSet<>();
                for (String s : o.get("kinds").getAsString().split(",")) {
                    if (!s.isBlank()) c.kinds.add(Kind.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)));
                }
            }
            if (o.has("cap")) c.cap = o.get("cap").getAsInt();
            if (o.has("topM")) c.topM = o.get("topM").getAsInt();
            if (o.has("shuffleWorlds")) c.shuffleWorlds = o.get("shuffleWorlds").getAsInt();
            if (o.has("settleSteps")) c.settleSteps = o.get("settleSteps").getAsInt();
            if (o.has("beamWidth")) c.beamWidth = o.get("beamWidth").getAsInt();
            if (o.has("beamBranch")) c.beamBranch = o.get("beamBranch").getAsInt();
            if (o.has("margin")) c.margin = o.get("margin").getAsDouble();
            if (o.has("seed")) c.seed = o.get("seed").getAsLong();
            if (o.has("payCap")) c.payCap = o.get("payCap").getAsInt();
            if (o.has("orderCap")) c.orderCap = o.get("orderCap").getAsInt();
            if (c.payCap < 0 || c.orderCap < 2) {
                throw new IllegalArgumentException("chooseACard: payCap >= 0, orderCap >= 2");
            }
            if (o.has("label") && !o.get("label").isJsonNull()) {
                c.label = o.getAsJsonObject("label");
                HonestLabel.Spec.parse(c.label); // refuse a dishonest or malformed spec at parse time
            }
            if (c.cap < 2 || c.shuffleWorlds < 1 || c.settleSteps < 1 || c.beamWidth < 1 || c.beamBranch < 1 || c.topM < 0) {
                throw new IllegalArgumentException("chooseACard: cap >= 2, topM >= 0, shuffleWorlds/settleSteps/beam >= 1");
            }
            return c;
        }

        static final Set<String> KEYS = new java.util.TreeSet<>(Arrays.asList("mode", "kinds", "cap", "topM",
                "shuffleWorlds", "settleSteps", "beamWidth", "beamBranch", "margin", "seed", "label", "payCap", "orderCap"));

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
            final StringBuilder k = new StringBuilder();
            for (Kind x : kinds) {
                k.append(k.length() == 0 ? "" : ",").append(x.name());
            }
            o.addProperty("kinds", k.toString());
            o.addProperty("cap", cap);
            o.addProperty("topM", topM);
            o.addProperty("shuffleWorlds", shuffleWorlds);
            o.addProperty("settleSteps", settleSteps);
            o.addProperty("beamWidth", beamWidth);
            o.addProperty("beamBranch", beamBranch);
            o.addProperty("margin", margin);
            o.addProperty("seed", seed);
            // r3-inline-choices-1030: the new keys are written only when a new kind is on (old configs print as before)
            if (!java.util.Collections.disjoint(kinds, java.util.EnumSet.complementOf(java.util.EnumSet.copyOf(LEGACY)))) {
                o.addProperty("payCap", payCap);
                o.addProperty("orderCap", orderCap);
            }
            if (label != null) o.add("label", label);
            return o;
        }
    }

    // ------------------------------------------------------------------------------------------------ value, solver

    /** The value of positions, P(this seat wins) in [0, 1]: one {@link #values} call per decision (one batch). */
    public interface Valuer {
        /** Inside the copy's scope, BEFORE the answer is applied (a knowledge tracker attaches here); may be null. */
        Object onCopy(Game copy, Player me);

        /** Inside the copy's scope, at the leaf: the leaf payload. */
        Object capture(Object ctx, Game g, Player me);

        /** One value per payload in [0, 1], in order; null = failed. */
        double[] values(List<Object> payloads);

        default String error() {
            return null;
        }
    }

    /** The search service's value tower through the look-ahead's leaf protocol (LEAVES, one message per batch). */
    public static Valuer of(final LookaheadSearch.SearchHooks h) {
        return new Valuer() {
            @Override
            public Object onCopy(final Game copy, final Player me) {
                return h.onCopy(copy, me);
            }

            @Override
            public Object capture(final Object ctx, final Game g, final Player me) {
                return h.captureLeaf(ctx, g, me);
            }

            @Override
            public double[] values(final List<Object> payloads) {
                return h.leafValues(payloads);
            }

            @Override
            public String error() {
                return h.leafError();
            }
        };
    }

    /** The honest solver's check of one position (a copy at the leaf). */
    public interface Checker {
        /**
         * {@code knownOwn}: our library card ids whose positions the seat knows (they must stay in place in every world).
         * Never throws for an unsupported position: returns {@link Verdict#none}.
         */
        Verdict check(Game copy, Player me, Set<Integer> knownOwn, long key);
    }

    public static final class Verdict {
        public boolean win;
        public String label = "unresolved";
        public String gate = "ok";
        public double pWin = Double.NaN;
        public int turnsToEnd = -1;

        public static Verdict none(final String gate) {
            final Verdict v = new Verdict();
            v.gate = gate;
            return v;
        }

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("label", label);
            o.addProperty("gate", gate);
            if (!Double.isNaN(pWin)) o.addProperty("p_win", Math.round(pWin * 1e4) / 1e4);
            if (turnsToEnd >= 0) o.addProperty("turns_to_end", turnsToEnd);
            return o;
        }
    }

    /** The honest win check of {@link HonestLabel} (belief worlds, hideOwnLibrary), with our known library kept. */
    public static Checker honest(final HonestLabel.Seat seat) {
        return (g, me, known, key) -> {
            final HonestLabel.Result r = seat.label(g, me, key, known);
            final Verdict v = new Verdict();
            v.label = r.label;
            v.gate = r.gate;
            v.pWin = r.pWin;
            v.turnsToEnd = r.turnsToEnd;
            v.win = "win".equals(r.label);
            return v;
        };
    }

    // ------------------------------------------------------------------------------------------------ answers

    /** One answer: the forced responses to the asks of one resolution (null fields = Forge AI answers that ask). */
    static final class Answer {
        /** chooseSingleCardForZoneChange answers, in ask order. */
        List<Integer> picks;
        /** orderMoveToZoneList to our library: top first. */
        List<Integer> order;
        /** MayShuffle confirm. */
        Boolean shuffle;
        /** scry / surveil: top (top first) and bottom / graveyard. */
        List<Integer> top, away;
        /** discard / mulligan bottom. */
        List<Integer> cards;
        /** land play: the card id. */
        int land = -1;
        /** a return-to-hand cost (Gush): the spell's card id, its ability index among the card's possible abilities, the cards. */
        int castCard = -1, castIndex = -1;
        List<Integer> returned;
        /** Forge AI's own answer (candidate 0). */
        boolean forge;
        /** An optional search's "find nothing". */
        boolean none;
        /**
         * Review S2 (baseline): Forge's full answer was recorded on an EXACT copy with the live random stream (what live
         * Forge itself plays), and is now a fixed answer like the others.
         */
        boolean probed;
        /** The probe copy's controller (its recorded answers), set by a probe apply. */
        Scripted probeSc;
        String key;
        List<String> desc = new ArrayList<>();

        int worlds() {
            return Boolean.TRUE.equals(shuffle) ? -1 : 1;
        }

        /** The ids this answer puts into known library positions. */
        Set<Integer> placed() {
            final Set<Integer> s = new HashSet<>();
            if (order != null) s.addAll(order);
            if (top != null) s.addAll(top);
            if (away != null && kindAwayIsLibrary) s.addAll(away);
            return s;
        }

        boolean kindAwayIsLibrary = false;

        // r3-inline-choices-1030 (FLAG-R3-5)
        /** PAYMENT / COLOR: mana floated before the cast, {source id, colour index (0-4 WUBRG, 5 C)}, in order. */
        List<int[]> floats;
        /** COST_CARDS / SACRIFICE (cost): the cards of each cost ask of the cast, in ask order (null = Forge's). */
        List<List<Integer>> costPicks;
        /** LIFE: Phyrexian pips paid with 2 life, the first n of the cast's (-1 = Forge's). */
        int lifePips = -1;
        /** ORDER_TRIGGERS: indexes into the asked list, first = bottom of the stack (Forge's convention). */
        List<Integer> trigOrder;
        /** PAY_TO_PREVENT: pay or not. */
        Boolean pay;
        /** COLOR (resolution): the colour mask. */
        byte colour = 0;
        /** COLOR at priority: the mana ability is played alone (castIndex is its index among the host's mana abilities). */
        boolean manaAlone;
        /** The kind this answer varies (the log's "sub"). */
        Kind sub;
        /** TARGET: the chain index of the targeting ability and its one target (card id, or -(player index + 1)). */
        int tgtChain = -1, tgt = Integer.MIN_VALUE;
        /** A cast probe's recorded asks (record mode only). */
        CastRecord rec;
    }

    /** r3-inline-choices-1030: what a cast asked on its copy (Forge's own answers). */
    static final class CastRecord {
        final List<String> parts = new ArrayList<>();
        final List<List<Integer>> valid = new ArrayList<>();
        final List<Integer> amounts = new ArrayList<>();
        final List<List<Integer>> defs = new ArrayList<>();
        int phyrexian = 0;
    }

    /** The ask that opened a decision. */
    static final class Ask {
        Kind kind;
        SpellAbility sa;
        /** The stack entry resolving (null: no stack, e.g. a land or a mulligan). */
        SpellAbility top;
        /** Sub-ability index of sa in top's chain (0 = root); -1 = not on the stack. */
        int chain = -1;
        boolean trigger;
        String src = "-";
        String method;
        /** r3-inline-choices-1030: copy the (copyable) stack with no resolving entry (a cast in response, a legend). */
        boolean copyStack;
        /** r3-inline-choices-1030: the live abilities a trigger order is asked for. */
        List<SpellAbility> trigs;
        /** r3-inline-choices-1030: the decision is a cast at priority (the CAST_KINDS). */
        boolean cast;
    }

    // ------------------------------------------------------------------------------------------------ state

    private final Config cfg;
    private Valuer valuer;
    private Checker checker;
    private Game live;
    private Player me;
    private OwnLibraryMemory memory;
    private int decisions = 0;
    /**
     * The resolving stack entry a decision was made in (review S1-1): every later ask of that same resolution follows
     * {@link #plan} (the departed answer) or, with no plan (kept, shadow, single, error), is Forge AI's own; it never
     * opens a second decision.
     */
    private SpellAbility pinTop = null;
    private Answer plan = null;
    private int planPick = 0;
    /** The last decisions (bounded: at most {@link #LOG_KEEP}); every entry also goes to the sink (the decision log). */
    public final JsonArray log = new JsonArray();
    static final int LOG_KEEP = 256;
    private java.util.function.Consumer<JsonObject> sink = null;

    /** Where every decision entry goes as it is made (the game's decision log); null = memory only (bounded). */
    public void setSink(final java.util.function.Consumer<JsonObject> s) {
        this.sink = s;
    }
    public final Map<String, Long> counts = new TreeMap<>();
    /** Tests: the last decision's candidate descriptions and scores, and its pick. */
    List<String> lastCands = null;
    double[] lastScores = null;
    int lastPick = -1;
    int lastValueCalls = 0;
    int valueCalls = 0;

    public ChooseACard(final Config cfg, final Valuer valuer, final Checker checker) {
        this.cfg = cfg;
        this.valuer = valuer;
        this.checker = checker;
    }

    public Config config() {
        return cfg;
    }

    public void setValuer(final Valuer v) {
        this.valuer = v;
    }

    /** The belief re-draw of the opponents' hidden cards in every apply copy (null = none: the leaf reads only our view). */
    private ForcedWinSolver.Redraw redraw = null;

    public void setRedraw(final ForcedWinSolver.Redraw r) {
        this.redraw = r;
    }

    public void setChecker(final Checker c) {
        this.checker = c;
    }

    /** Install on the live seat's controller (OFF installs nothing). */
    public void install(final PlayerControllerAi ctrl) {
        if (cfg.mode == Config.Mode.OFF) {
            return;
        }
        if (checker == null && cfg.label != null) {
            checker = honest(new HonestLabel.Seat(HonestLabel.Spec.parse(cfg.label)));
        }
        if (!cfg.testUnsafe) {
            // review S1-2 / S2-1: never on the opponent's real hidden cards; ON only with the tower and the solver
            if (redraw == null) {
                throw new IllegalStateException("chooseACard refused: no belief re-draw (the copies would hold the "
                        + "opponent's real hidden cards)");
            }
            if (cfg.mode == Config.Mode.ON && (valuer == null || valuer instanceof StaticValuer || checker == null
                    || cfg.topM < 1)) {
                throw new IllegalStateException("chooseACard refused: mode on needs the value tower (search hooks) and the "
                        + "solver check (a label spec, topM >= 1); shadow reports without them");
            }
        }
        if (ctrl.getCacHook() == this) {
            return; // already installed (a re-install after a controller swap is a no-op on the same controller)
        }
        if (memory == null || live != ctrl.getGame() || me != ctrl.getPlayer()) {
            this.memory = new OwnLibraryMemory(ctrl.getPlayer());
            memory.watch(ctrl.getGame());
        }
        this.live = ctrl.getGame();
        this.me = ctrl.getPlayer();
        ctrl.setCacHook(this);
    }

    public OwnLibraryMemory memory() {
        return memory;
    }

    private boolean mine(final PlayerControllerAi c) {
        return c.getGame() == live && c.getPlayer() == me;
    }

    private void count(final String k) {
        counts.merge(k, 1L, Long::sum);
    }

    /** A decision was made in the stack entry resolving now (its later asks follow the pin: the plan, or Forge AI). */
    private boolean pinned() {
        if (pinTop == null) {
            return false;
        }
        if (live.getStack().isEmpty() || !live.getStack().isResolving() || live.getStack().peekAbility() != pinTop) {
            pinTop = null;
            plan = null;
            return false;
        }
        return true;
    }

    private boolean planLive() {
        return pinned() && plan != null;
    }

    private void pin(final Ask ask) {
        if (ask != null && ask.top != null) {
            pinTop = ask.top;
            plan = null;
            planPick = 0;
            shadowBase = null;
            shadowPick = 1;
        }
    }

    /** Shadow: the decision's recorded Forge baseline, to count live answers that differ from it (review S2). */
    private Answer shadowBase = null;
    private int shadowPick = 1;

    // ------------------------------------------------------------------------------------------------ hooks

    @Override
    public Card pick(final PlayerControllerAi c, final ZoneType destination, final List<ZoneType> origin,
            final SpellAbility sa, final CardCollection fetchList, final boolean isOptional, final Player decider,
            final Card def) {
        curPre = preAskRng;
        preAskRng = null;
        if (!mine(c) || decider != me || fetchList == null || fetchList.isEmpty()) {
            return def;
        }
        if (pinned()) {
            if (plan == null || plan.picks == null) {
                if (shadowBase != null && shadowBase.picks != null && def != null) {
                    if (shadowPick >= shadowBase.picks.size() || shadowBase.picks.get(shadowPick) != def.getId()) {
                        count("baselineMismatch");
                    }
                    shadowPick++;
                }
                return def; // kept / shadow / single / error: the rest of this resolution is Forge AI's
            }
            if (plan.none) {
                return null; // the departed answer finds nothing
            }
            if (planPick < plan.picks.size()) {
                final int id = plan.picks.get(planPick++);
                for (Card x : fetchList) {
                    if (x.getId() == id) {
                        return x;
                    }
                }
            }
            count("liveMiss");
            plan = null;
            return def;
        }
        if (origin == null || sa == null) {
            return def;
        }
        final boolean fromHand = origin.size() == 1 && origin.get(0) == ZoneType.Hand && destination == ZoneType.Library;
        final boolean fromLib = origin.contains(ZoneType.Library);
        if (!fromHand && !fromLib) {
            return def;
        }
        for (Card x : fetchList) {
            if (x.getOwner() != me) {
                return def; // another player's library or hand: not an assembly choice of ours
            }
        }
        int n = 1;
        if (sa.hasParam("ChangeNum")) {
            try {
                n = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("ChangeNum"), sa);
            } catch (RuntimeException e) {
                n = 1;
            }
        }
        n = Math.max(1, Math.min(n, fetchList.size()));
        final boolean ordered = orderFollows(sa);
        final Kind kind = fromHand ? Kind.DIG : n == 1 ? Kind.SEARCH : ordered && destination == ZoneType.Library ? Kind.PILE : Kind.MULTI;
        if (!cfg.kinds.contains(kind)) {
            return def;
        }
        final Ask ask = ask(kind, sa, "chooseSingleCardForZoneChange");
        if (ask == null) {
            return def;
        }
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.desc.add("forge:" + (def == null ? "none" : def.getName()));
        answers.add(f); // its full answer is recorded on its copy
        final Map<String, Card> reps = reps(fetchList);
        final List<Card> pool = new ArrayList<>(reps.values());
        final Map<String, Integer> counts0 = new HashMap<>();
        for (Card x : fetchList) {
            counts0.merge(rkey(x), 1, Integer::sum);
        }
        final boolean exactOrdered = ordered && (kind == Kind.PILE || kind == Kind.DIG);
        final int[] total = {0};
        final Decision d = new Decision(ask, def == null ? null : Collections.singletonList(def.getId()));
        if (isOptional && def != null && kind == Kind.SEARCH) {
            // "up to one" / a search that may fail to find: finding nothing is an answer too
            final Answer none = new Answer();
            none.picks = new ArrayList<>();
            none.none = true;
            none.key = "none";
            none.desc.add("find nothing");
            answers.add(none);
        }
        if (kind == Kind.PILE) {
            final long seqs = countSequences(pool, counts0, n, 10_000_000L);
            d.entry.addProperty("n_sequences", seqs);
            if (seqs > cfg.cap - 1) {
                d.beam = new PileBeam(this, d, fetchList, n);
                d.nEnum = (int) Math.min(Integer.MAX_VALUE, seqs);
            }
        }
        if (d.beam == null) {
            enumerate(fetchList, n, exactOrdered, answers, total);
            d.nEnum = total[0];
            logDropped(d, fetchList, answers);
        }
        return decidePicks(d, answers, def, fetchList);
    }

    @Override
    public CardCollectionView order(final PlayerControllerAi c, final CardCollectionView cards, final ZoneType destination,
            final SpellAbility sa, final CardCollectionView def) {
        curPre = preAskRng;
        preAskRng = null;
        if (!mine(c) || cards == null) {
            return def;
        }
        if (destination == ZoneType.Library && memory != null) {
            memory.noteLooked(cards);
        }
        if (pinned()) {
            if (destination != ZoneType.Library || plan == null || plan.order == null) {
                return def;
            }
            final CardCollectionView o = toMoveOrder(plan.order, cards, destination);
            if (o != null) {
                return o;
            }
            count("liveMiss");
            plan = null;
            return def;
        }
        if (destination != ZoneType.Library || sa == null || sa.getApi() != ApiType.RearrangeTopOfLibrary
                || !cfg.kinds.contains(Kind.ORDER) || cards.size() < 2) {
            return def;
        }
        for (Card x : cards) {
            if (x.getOwner() != me) {
                return def;
            }
        }
        final Ask ask = ask(Kind.ORDER, sa, "orderMoveToZoneList");
        if (ask == null) {
            return def;
        }
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.order = topFirst(def, destination);
        if (sa.hasParam("MayShuffle")) {
            f.shuffle = null; // recorded on its copy
        }
        f.desc.add("forge");
        answers.add(f);
        final List<List<Card>> perms = new ArrayList<>();
        permutations(new ArrayList<>(cards), perms, cfg.cap);
        final Set<String> seen = new HashSet<>();
        for (List<Card> p : perms) {
            final StringBuilder k = new StringBuilder();
            for (Card x : p) k.append(x.getName()).append('>');
            if (!seen.add(k.toString())) continue;
            final Answer a = new Answer();
            a.order = ids(p);
            a.shuffle = sa.hasParam("MayShuffle") ? Boolean.FALSE : null;
            a.desc.add("top:" + names(p));
            answers.add(a);
        }
        if (sa.hasParam("MayShuffle")) {
            final Answer s = new Answer();
            s.order = topFirst(def, destination);
            s.shuffle = Boolean.TRUE;
            s.desc.add("shuffle");
            answers.add(s);
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = answers.size() - 1;
        final Answer best = decide(d, answers);
        if (best != null && best.forge && best.probed && cfg.mode == Config.Mode.ON) {
            startPlan(best, ask); // review S2: kept = the recorded baseline (its shuffle bit too) is played
            return def;
        }
        if (best == null || best.forge) {
            return def;
        }
        startPlan(best, ask);
        final CardCollectionView o = toMoveOrder(best.order, cards, destination);
        return o == null ? def : o;
    }

    @Override
    public boolean confirm(final PlayerControllerAi c, final SpellAbility sa, final PlayerActionConfirmMode mode,
            final String message, final boolean def) {
        if (mine(c) && planLive() && plan.shuffle != null && sa != null && sa.getApi() == ApiType.RearrangeTopOfLibrary) {
            return plan.shuffle;
        }
        return def;
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> scry(final PlayerControllerAi c, final CardCollection topN,
            final ImmutablePair<CardCollection, CardCollection> def) {
        return arrange(c, topN, def, Kind.SCRY);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> surveil(final PlayerControllerAi c, final CardCollection topN,
            final ImmutablePair<CardCollection, CardCollection> def) {
        return arrange(c, topN, def, Kind.SURVEIL);
    }

    private ImmutablePair<CardCollection, CardCollection> arrange(final PlayerControllerAi c, final CardCollection topN,
            final ImmutablePair<CardCollection, CardCollection> def, final Kind kind) {
        if (!mine(c) || topN == null || topN.isEmpty()) {
            return def;
        }
        if (memory != null) {
            memory.noteLooked(topN); // kept positions (top or bottom); a surveilled card leaves the library
        }
        if (pinned()) {
            if (plan != null && plan.top != null) {
                final CardCollection t = byIds(plan.top, topN), w = byIds(plan.away, topN);
                if (t != null && w != null && t.size() + w.size() == topN.size()) {
                    return ImmutablePair.of(t, w);
                }
                count("liveMiss");
            }
            return def;
        }
        if (!cfg.kinds.contains(kind) || live.getStack().isEmpty() || !live.getStack().isResolving()) {
            return def;
        }
        final Ask ask = askFromTop(kind, kind == Kind.SCRY ? "arrangeForScry" : "arrangeForSurveil");
        if (ask == null) {
            return def;
        }
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.top = ids(def.getLeft());
        f.away = ids(def.getRight());
        f.kindAwayIsLibrary = kind == Kind.SCRY;
        f.desc.add("forge top:" + names(def.getLeft()) + " away:" + names(def.getRight()));
        answers.add(f);
        final List<Card> cards = new ArrayList<>(topN);
        final int m = cards.size();
        final Set<String> seen = new HashSet<>();
        seen.add(arrKey(def.getLeft(), def.getRight()));
        outer:
        for (int mask = 0; mask < (1 << m); mask++) {
            final List<Card> away = new ArrayList<>(), keep = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                ((mask & (1 << i)) != 0 ? away : keep).add(cards.get(i));
            }
            final List<List<Card>> perms = new ArrayList<>();
            permutations(keep, perms, cfg.cap);
            for (List<Card> p : perms) {
                if (!seen.add(arrKey(p, away))) continue;
                if (answers.size() >= cfg.cap) {
                    count("capped");
                    break outer;
                }
                final Answer a = new Answer();
                a.top = ids(p);
                a.away = ids(away);
                a.kindAwayIsLibrary = kind == Kind.SCRY;
                a.desc.add("top:" + names(p) + " away:" + names(away));
                answers.add(a);
            }
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = answers.size() - 1;
        final Answer best = decide(d, answers);
        if (best == null || best.forge) {
            return def;
        }
        final CardCollection t = byIds(best.top, topN), w = byIds(best.away, topN);
        if (t == null || w == null) {
            count("liveMiss");
            return def;
        }
        startPlan(best, ask);
        return ImmutablePair.of(t, w);
    }

    @Override
    public CardCollection discard(final PlayerControllerAi c, final Player discarding, final SpellAbility sa,
            final CardCollection valid, final int min, final int max, final CardCollection def) {
        if (!mine(c) || discarding != me || valid == null || valid.isEmpty() || def == null) {
            return def;
        }
        if (pinned()) {
            if (plan != null && plan.cards != null) {
                final CardCollection out = byIds(plan.cards, valid);
                if (out != null) return out;
                count("liveMiss");
            }
            return def;
        }
        if (!cfg.kinds.contains(Kind.DISCARD) || min != max || min < 1 || live.getStack().isEmpty()
                || !live.getStack().isResolving()) {
            return def;
        }
        final Ask ask = ask(Kind.DISCARD, sa, "chooseCardsToDiscardFrom");
        if (ask == null) {
            return def;
        }
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.cards = ids(def);
        f.desc.add("forge:" + names(def));
        answers.add(f);
        final Set<String> seen = new HashSet<>();
        seen.add(multisetKey(def));
        final int[] total = {0};
        combos(new ArrayList<>(valid), min, cfg.cap, list -> {
            total[0]++;
            if (seen.add(multisetKey(list)) && answers.size() < cfg.cap) {
                final Answer a = new Answer();
                a.cards = ids(list);
                a.desc.add("discard:" + names(list));
                answers.add(a);
            }
        });
        final Decision d = new Decision(ask, null);
        d.nEnum = total[0];
        logDropped(d, valid, answers);
        final Answer best = decide(d, answers);
        if (best == null || best.forge) {
            return def;
        }
        final CardCollection out = byIds(best.cards, valid);
        if (out != null) startPlan(best, ask);
        return out == null ? def : out;
    }

    @Override
    public CardCollectionView tuck(final PlayerControllerAi c, final CardCollectionView hand, final int k,
            final CardCollectionView def) {
        if (!mine(c) || hand == null || k < 1 || def == null || !cfg.kinds.contains(Kind.MULLIGAN)) {
            return def;
        }
        final Ask ask = new Ask();
        ask.kind = Kind.MULLIGAN;
        ask.method = "tuckCardsViaMulligan";
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.cards = ids(def);
        f.desc.add("forge:" + names(def));
        answers.add(f);
        final Set<String> seen = new HashSet<>();
        seen.add(multisetKey(def));
        final int[] total = {0};
        combos(new ArrayList<>(hand), k, cfg.cap, list -> {
            total[0]++;
            if (seen.add(multisetKey(list)) && answers.size() < cfg.cap) {
                final Answer a = new Answer();
                a.cards = ids(list);
                a.desc.add("bottom:" + names(list));
                answers.add(a);
            }
        });
        final Decision d = new Decision(ask, null);
        d.nEnum = total[0];
        logDropped(d, hand, answers);
        final Answer best = decide(d, answers);
        if (best == null || best.forge) {
            return def;
        }
        final CardCollection out = byIds(best.cards, hand);
        return out == null ? def : out;
    }

    @Override
    public List<SpellAbility> priority(final PlayerControllerAi c, final List<SpellAbility> def) {
        if (mine(c)) {
            castPlan = null; // r3-inline-choices-1030: a cast plan lives from its priority to the next one
            castCostIdx = 0;
            castPhyIdx = 0;
        }
        if (mine(c) && def != null && def.size() == 1 && !def.get(0).isLandAbility() && returnPart(def.get(0)) != null) {
            return returnCostDecision(def);
        }
        if (mine(c) && def != null && def.size() == 1 && !def.get(0).isLandAbility()
                && !java.util.Collections.disjoint(cfg.kinds, CAST_KINDS)) {
            return castDecision(def);
        }
        if (!mine(c) || def == null || def.size() != 1 || !def.get(0).isLandAbility() || !cfg.kinds.contains(Kind.LAND)
                || !live.getStack().isEmpty()) {
            return def;
        }
        final SpellAbility chosen = def.get(0);
        final Map<String, SpellAbility> lands = new LinkedHashMap<>();
        lands.put(chosen.getHostCard().getName(), chosen);
        final List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        hand.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        for (Card x : hand) {
            if (!x.isLand() || lands.containsKey(x.getName())) continue;
            for (SpellAbility s : x.getAllPossibleAbilities(me, true)) {
                if (s.isLandAbility()) {
                    lands.put(x.getName(), s);
                    break;
                }
            }
        }
        if (lands.size() < 2) {
            return def;
        }
        final Ask ask = new Ask();
        ask.kind = Kind.LAND;
        ask.method = "chooseSpellAbilityToPlay";
        final List<Answer> answers = new ArrayList<>();
        final List<SpellAbility> sas = new ArrayList<>();
        for (Map.Entry<String, SpellAbility> e : lands.entrySet()) {
            final Answer a = new Answer();
            a.forge = answers.isEmpty();
            a.land = e.getValue().getHostCard().getId();
            a.desc.add((a.forge ? "forge:" : "land:") + e.getKey());
            answers.add(a);
            sas.add(e.getValue());
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = answers.size() - 1;
        final Answer best = decide(d, answers);
        if (best == null || best.forge) {
            return def;
        }
        return Collections.singletonList(sas.get(answers.indexOf(best)));
    }

    @Override
    public List<Card> dig(final PlayerControllerAi c, final List<Card> options, final int min, final int max,
            final SpellAbility sa, final List<Card> def) {
        if (!mine(c) || options.isEmpty() || def == null) {
            return def;
        }
        if (pinned()) {
            if (plan != null && plan.cards != null) {
                final CardCollection out = byIds(plan.cards, options);
                if (out != null) return new ArrayList<>(out);
                count("liveMiss");
            }
            return def;
        }
        if (!cfg.kinds.contains(Kind.DIGPICK) || max < 1 || live.getStack().isEmpty() || !live.getStack().isResolving()) {
            return def;
        }
        for (Card x : options) {
            if (x.getOwner() != me) return def;
        }
        final Ask ask = ask(Kind.DIGPICK, sa, "chooseEntitiesForEffect");
        if (ask == null) return def;
        final int k = Math.min(max, options.size());
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.cards = ids(def);
        f.desc.add("forge:" + names(def));
        answers.add(f);
        final Set<String> seen = new HashSet<>();
        seen.add(multisetKey(def));
        final int[] total = {0};
        combos(new ArrayList<>(options), k, cfg.cap, list -> {
            total[0]++;
            if (seen.add(multisetKey(list)) && answers.size() < cfg.cap) {
                final Answer a = new Answer();
                a.cards = ids(list);
                a.desc.add("pick:" + names(list));
                answers.add(a);
            }
        });
        final Decision d = new Decision(ask, null);
        d.nEnum = total[0];
        logDropped(d, options, answers);
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        final CardCollection out = byIds(best.cards, options);
        if (out != null) startPlan(best, ask);
        return out == null ? def : new ArrayList<>(out);
    }

    /** The return-cost plan of the cast the seat is about to make (Gush), consumed by its cost decision. */
    private Answer returnPlan = null;

    @Override
    public CardCollectionView returnCost(final PlayerControllerAi c, final SpellAbility sa, final String type,
            final int amount, final CardCollectionView def) {
        if (!mine(c) || returnPlan == null || sa == null || sa.getHostCard() == null
                || sa.getHostCard().getId() != returnPlan.castCard) {
            return def;
        }
        final Answer a = returnPlan;
        returnPlan = null;
        final CardCollection out = byIds(a.returned, me.getCardsIn(ZoneType.Battlefield));
        if (out == null || out.size() != amount) {
            count("liveMiss");
            return def;
        }
        return out;
    }

    /** The cast's return-to-hand cost part (not "return this card"), or null. */
    static forge.game.cost.CostReturn returnPart(final SpellAbility sa) {
        if (sa == null || sa.getPayCosts() == null) return null;
        for (forge.game.cost.CostPart p : sa.getPayCosts().getCostParts()) {
            if (p instanceof forge.game.cost.CostReturn r && !r.payCostFromSource()) return r;
        }
        return null;
    }

    /** At priority: a cast whose cost returns permanents (Gush's Islands) - choose which, then cast with that plan. */
    private List<SpellAbility> returnCostDecision(final List<SpellAbility> def) {
        final SpellAbility sa = def.get(0);
        final forge.game.cost.CostReturn part = returnPart(sa);
        if (part == null || !cfg.kinds.contains(Kind.RETURN_COST) || !live.getStack().isEmpty()) return def;
        final int amount = part.getAbilityAmount(sa);
        final CardCollection valid = forge.game.card.CardLists.getValidCards(me.getCardsIn(ZoneType.Battlefield),
                part.getType().split(";"), me, sa.getHostCard(), sa);
        if (amount < 1 || valid.size() <= amount) return def;
        final List<SpellAbility> all = new ArrayList<>(sa.getHostCard().getAllPossibleAbilities(me, true));
        final int idx = indexOfSa(all, sa);
        if (idx < 0) return def;
        final Ask ask = new Ask();
        ask.kind = Kind.RETURN_COST;
        ask.method = "AiCostDecision.visit(CostReturn)";
        ask.src = sa.getHostCard().getName();
        final List<Answer> answers = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final int[] total = {0};
        combos(new ArrayList<>(valid), amount, cfg.cap, list -> {
            total[0]++;
            final List<String> k = new ArrayList<>();
            for (Card x : list) k.add(x.getName() + (x.isTapped() ? "/T" : "/U"));
            Collections.sort(k);
            if (seen.add(k.toString()) && answers.size() < cfg.cap) {
                final Answer a = new Answer();
                a.castCard = sa.getHostCard().getId();
                a.castIndex = idx;
                a.returned = ids(list);
                a.forge = answers.isEmpty(); // the first (lowest ids) stands for Forge's: its own choice is made at payment
                a.key = "return:" + k;
                a.desc.add((a.forge ? "first:" : "return:") + k);
                answers.add(a);
            }
        });
        final Decision d = new Decision(ask, null);
        d.nEnum = total[0];
        logDropped(d, valid, answers);
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        returnPlan = best;
        return def;
    }

    static int indexOfSa(final List<SpellAbility> all, final SpellAbility sa) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == sa) return i;
        }
        final String want = String.valueOf(sa.getPayCosts()) + "|" + sa.getDescription();
        for (int i = 0; i < all.size(); i++) {
            if ((String.valueOf(all.get(i).getPayCosts()) + "|" + all.get(i).getDescription()).equals(want)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------ r3 in-line kinds

    /** r3-inline-choices-1030: the cast plan of the seat's next cast (cost cards, life), consumed by its payment. */
    private Answer castPlan = null;
    private int castCostIdx = 0, castPhyIdx = 0;

    /** A ask's result key part: name, zone, tapped (two untapped Islands are one answer). */
    static String stateKey(final Card c) {
        return c.getName() + "@" + (c.getZone() == null ? "?" : c.getZone().getZoneType()) + (c.isTapped() ? "/T" : "");
    }

    static String stateKeys(final Iterable<Card> cs) {
        final List<String> k = new ArrayList<>();
        for (Card c : cs) k.add(stateKey(c));
        Collections.sort(k);
        return k.toString();
    }

    /**
     * At priority, a cast or activation Forge AI chose (PAYMENT, COLOR, COST_CARDS, SACRIFICE, LIFE): Forge's cast is
     * candidate 0; the others vary ONE thing each: which sources pay (distinct untapped remainders, labeller v2 (e)'s
     * enumeration widened to every one-tap source), the colour of an any-colour source's mana, the cards of one cost
     * part (pitch, discard, sacrifice, tap, delve, convoke), or how many Phyrexian pips are paid with life. Each is
     * cast on a copy and settled to the first empty-stack priority; answers whose resulting state repeats are dropped.
     */
    /**
     * r3-inline-choices-1030: a live seat recorded by an AI tape plays the new kinds in shadow only. The tape records
     * the priority list, trigger orders, cost cards and pay-to-prevent as played, but not a Phyrexian pip paid with
     * life over an available source, and its encoding of a floated mana ability is not tested: so ON does not depart
     * there (counted). Label-search lines and copies are never taped.
     */
    private boolean tapedOn() {
        if (cfg.mode != Config.Mode.ON || forge.ai.AiTape.covering(me) == null) return false;
        count("unsupported:tapedSeat");
        return true;
    }

    private List<SpellAbility> castDecision(final List<SpellAbility> def) {
        if (tapedOn()) return def;
        final SpellAbility sa = def.get(0);
        final Card host = sa.getHostCard();
        if (host == null || sa.isCopied()) return def;
        if (sa.isManaAbility()) {
            return cfg.kinds.contains(Kind.COLOR) ? colourAtPriority(def) : def;
        }
        if (sa.getPayCosts() == null) return def;
        if (sa.getApi() == ApiType.Charm || sa.getPayCosts().hasXInAnyCostPart() || sa.getSplicedCards() != null && !sa.getSplicedCards().isEmpty()) {
            count("unsupported:castModesX");
            return def;
        }
        final List<SpellAbility> all = new ArrayList<>(host.getAllPossibleAbilities(me, true));
        final int idx = indexOfSa(all, sa);
        if (idx < 0) return def;
        final Ask ask = new Ask();
        ask.kind = Kind.PAYMENT;
        ask.cast = true;
        ask.sa = sa; // its targets are copied onto each copy's ability
        ask.method = "cast";
        ask.src = host.getName();
        if (!live.getStack().isEmpty()) {
            if (live.getStack().isResolving()) return def;
            final String why = GameCopier.stackUnsupported(live, false);
            if (why != null) {
                count("unsupported:" + why);
                return def;
            }
            ask.copyStack = true;
        }
        final Decision d = new Decision(ask, null);
        castAsk = sa;
        try {
            return castDecision(def, sa, host, idx, ask, d);
        } finally {
            castAsk = null;
        }
    }

    private List<SpellAbility> castDecision(final List<SpellAbility> def, final SpellAbility sa, final Card host,
            final int idx, final Ask ask, final Decision d) {
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.castCard = host.getId();
        f.castIndex = idx;
        f.desc.add("forge:" + host.getName());
        answers.add(f);
        int n = 0;
        // cost cards and Phyrexian life: Forge's cast recorded on a copy first (the asks and their valid cards)
        if (needsRecord(sa)) {
            final Answer r = new Answer();
            r.castCard = host.getId();
            r.castIndex = idx;
            r.rec = new CastRecord();
            r.forge = true;
            if (apply(d, r, LookaheadSearch.mix(cfg.seed, 0xCA570000L + d.index)) != null || !r.rec.parts.isEmpty()
                    || r.rec.phyrexian > 0) {
                n += costAnswers(r.rec, f, answers);
            }
            d.applied--; // the record copy is not a candidate
        }
        if (cfg.kinds.contains(Kind.TARGET)) {
            n += targetAnswers(sa, f, answers);
        }
        if (cfg.kinds.contains(Kind.PAYMENT) || cfg.kinds.contains(Kind.COLOR)) {
            n += payAnswers(sa, f, answers);
        }
        d.nEnum = n;
        if (answers.size() < 2) {
            return def;
        }
        ask.kind = answers.get(1).sub;
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        castPlan = best;
        castCostIdx = 0;
        castPhyIdx = 0;
        if (best.tgtChain >= 0 && !retarget(sa, best.tgtChain, best.tgt, live)) {
            count("liveMiss");
            castPlan = null;
            return def;
        }
        if (best.floats == null) return def;
        final List<SpellAbility> out = new ArrayList<>();
        for (int[] fl : best.floats) {
            final SpellAbility m = floatSa(live, me, fl[0], fl[1]);
            if (m == null) {
                count("liveMiss");
                castPlan = null;
                return def;
            }
            out.add(m);
        }
        out.add(sa);
        return out;
    }

    /** A cast whose payment asks something to record: a card-choosing cost part, delve, convoke, a Phyrexian pip. */
    static boolean needsRecord(final SpellAbility sa) {
        for (forge.game.cost.CostPart p : sa.getPayCosts().getCostParts()) {
            if ((p instanceof forge.game.cost.CostExile || p instanceof forge.game.cost.CostDiscard
                    || p instanceof forge.game.cost.CostSacrifice || p instanceof forge.game.cost.CostTapType)
                    && !p.payCostFromSource()) return true;
        }
        final Card h = sa.getHostCard();
        if (h.hasKeyword(forge.game.keyword.Keyword.DELVE) || h.hasKeyword(forge.game.keyword.Keyword.CONVOKE)
                || h.hasKeyword(forge.game.keyword.Keyword.IMPROVISE)) return true;
        final forge.card.mana.ManaCost mc = sa.getPayCosts().getTotalMana();
        if (mc != null) {
            for (forge.card.mana.ManaCostShard sh : mc) {
                if (sh.isPhyrexian()) return true;
            }
        }
        return false;
    }

    /** The cost-card and life answers from a recorded cast (one ask varied per answer). */
    private int costAnswers(final CastRecord rec, final Answer f, final List<Answer> answers) {
        int total = 0;
        for (int k = 0; k < rec.parts.size() && k < 4; k++) {
            final String part = rec.parts.get(k);
            final Kind sub = part.equals("CostSacrifice") ? Kind.SACRIFICE : Kind.COST_CARDS;
            if (!cfg.kinds.contains(sub)) continue;
            final List<Card> valid = new ArrayList<>();
            for (int id : rec.valid.get(k)) {
                final Card c = live.findById(id);
                if (c != null) valid.add(c);
            }
            valid.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
            final Set<String> seen = new HashSet<>();
            seen.add(stateKeys(byIdsLoose(rec.defs.get(k))));
            final int amount = rec.amounts.get(k);
            final boolean anySize = part.equals("Delve");
            final int lo = anySize ? 0 : amount, hi = Math.min(amount, valid.size());
            for (int size = hi; size >= lo; size--) {
                final int kk = k, sz = size;
                final int[] t = {0};
                combos(valid, size, cfg.cap, list -> {
                    t[0]++;
                    if (!seen.add(stateKeys(list)) || answers.size() >= cfg.cap) return;
                    final Answer a = new Answer();
                    a.castCard = f.castCard;
                    a.castIndex = f.castIndex;
                    a.costPicks = new ArrayList<>();
                    for (int j = 0; j < rec.parts.size(); j++) a.costPicks.add(j == kk ? ids(list) : null);
                    a.sub = sub;
                    a.desc.add((sub == Kind.SACRIFICE ? "sac:" : "cost:") + part + (anySize ? "#" + sz : "") + ":"
                            + names(list));
                    answers.add(a);
                });
                total += t[0];
            }
        }
        if (rec.phyrexian > 0 && cfg.kinds.contains(Kind.LIFE) && me.canPayLife(2, false, null)) {
            for (int k = 1; k <= rec.phyrexian && answers.size() < cfg.cap; k++) {
                if (me.getLife() <= 2 * k) break;
                final Answer a = new Answer();
                a.castCard = f.castCard;
                a.castIndex = f.castIndex;
                a.lifePips = k;
                a.sub = Kind.LIFE;
                a.desc.add("life:" + (2 * k));
                answers.add(a);
                total++;
            }
        }
        return total;
    }

    private List<Card> byIdsLoose(final List<Integer> ids) {
        final List<Card> out = new ArrayList<>();
        for (int id : ids) {
            final Card c = live.findById(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** One untapped mana source of ours: one-tap (or tap and sacrifice itself) abilities, its colours and amount. */
    static final class Src {
        Card c;
        int mask, amount;
        boolean any;
    }

    static List<Src> sources(final Player me, final Card except) {
        final List<Src> out = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c == except || c.isTapped()) continue;
            final Src s = new Src();
            s.c = c;
            for (SpellAbility ma : c.getManaAbilities()) {
                if (ma.getPayCosts() == null || !ma.getPayCosts().hasTapCost()) continue;
                boolean simple = true;
                for (forge.game.cost.CostPart p : ma.getPayCosts().getCostParts()) {
                    if (!(p instanceof forge.game.cost.CostTap)
                            && !(p instanceof forge.game.cost.CostSacrifice && p.payCostFromSource())) simple = false;
                }
                if (!simple || ma.getRestrictions() != null && !ma.getRestrictions().canPlay(c, ma)) continue;
                final SpellAbility m = ma.copy(me);
                m.setActivatingPlayer(me);
                if (!m.canPlay()) continue;
                for (int col = 0; col < 6; col++) {
                    if (m.canProduce(ForcedWinModel.SHORT[col])) s.mask |= 1 << col;
                }
                s.amount = Math.max(s.amount, Math.max(1, m.amountOfManaGenerated(false)));
                s.any |= m.getManaPart() != null && (m.getManaPart().isAnyMana() || m.getManaPart().isComboMana());
            }
            if (s.mask != 0) out.add(s);
        }
        out.sort((x, y) -> Integer.compare(x.c.getId(), y.c.getId()));
        return out;
    }

    /**
     * PAYMENT / COLOR answers: minimal source sets that pay the cost (coloured pips first), each with a colour per
     * source, deduped by the untapped remainder plus the colours left floating; X, hybrid, Phyrexian and snow costs
     * stay Forge's (stated gap).
     */
    private int payAnswers(final SpellAbility sa, final Answer f, final List<Answer> answers) {
        final forge.card.mana.ManaCost mc = sa.getPayCosts().getTotalMana();
        if (mc == null || mc.countX() > 0 || mc.isNoCost() || cfg.payCap == 0) return 0;
        final Card host = sa.getHostCard();
        if (host.hasKeyword(forge.game.keyword.Keyword.DELVE) || host.hasKeyword(forge.game.keyword.Keyword.CONVOKE)
                || host.hasKeyword(forge.game.keyword.Keyword.IMPROVISE)) return 0;
        final List<Integer> need = new ArrayList<>();
        for (forge.card.mana.ManaCostShard sh : mc) {
            if (sh == forge.card.mana.ManaCostShard.COLORLESS) {
                need.add(ForcedWinModel.C);
            } else if (sh.isMonoColor() && !sh.isOr2Generic() && !sh.isPhyrexian() && !sh.isSnow()
                    && Integer.bitCount(sh.getColorMask() & 0x1f) == 1) {
                need.add(Integer.numberOfTrailingZeros(sh.getColorMask()));
            } else if (!sh.isGeneric() || sh.isSnow() || sh.isOr2Generic()) {
                return 0;
            }
        }
        final int total = need.size() + mc.getGenericCost();
        if (total == 0) return 0;
        final List<Src> src = sources(me, host);
        final Set<String> seen = new HashSet<>();
        final int[] n = {0};
        final int before = answers.size();
        for (int size = 1; size <= Math.min(total, src.size()); size++) {
            final List<Card> cards = new ArrayList<>();
            for (Src s : src) cards.add(s.c);
            combos(cards, size, 4096, set -> {
                if (answers.size() - before >= cfg.payCap || answers.size() >= cfg.cap) return;
                final List<Src> ss = new ArrayList<>();
                int amt = 0, minAmt = Integer.MAX_VALUE;
                for (Card c : set) {
                    for (Src s : src) if (s.c == c) ss.add(s);
                }
                for (Src s : ss) {
                    amt += s.amount;
                    minAmt = Math.min(minAmt, s.amount);
                }
                if (amt < total || amt - minAmt >= total) return; // pays, and no source is spare
                for (int[] colour : colourings(ss, need, cfg.kinds.contains(Kind.COLOR))) {
                    if (answers.size() - before >= cfg.payCap || answers.size() >= cfg.cap) return;
                    n[0]++;
                    final List<String> left = new ArrayList<>();
                    for (Src s : src) if (!set.contains(s.c)) left.add(s.c.getName());
                    Collections.sort(left);
                    final List<String> excess = excessColours(ss, colour, need, total);
                    final String key = left + "|" + excess;
                    if (!seen.add(key)) continue;
                    final Answer a = new Answer();
                    a.castCard = f.castCard;
                    a.castIndex = f.castIndex;
                    a.floats = new ArrayList<>();
                    final List<String> dsc = new ArrayList<>();
                    for (int i = 0; i < ss.size(); i++) {
                        a.floats.add(new int[] {ss.get(i).c.getId(), colour[i]});
                        dsc.add(ss.get(i).c.getName() + "=" + ForcedWinModel.SHORT[colour[i]]);
                    }
                    a.sub = excess.isEmpty() || !cfg.kinds.contains(Kind.COLOR) ? Kind.PAYMENT : Kind.COLOR;
                    if (a.sub == Kind.PAYMENT && !cfg.kinds.contains(Kind.PAYMENT)) continue;
                    a.desc.add("pay:" + dsc + " keep:" + left + (excess.isEmpty() ? "" : " float:" + excess));
                    answers.add(a);
                }
            });
        }
        return n[0];
    }

    /** Colour per source covering every coloured pip (each source makes one colour); all distinct when {@code all}. */
    static List<int[]> colourings(final List<Src> ss, final List<Integer> need, final boolean all) {
        final List<int[]> out = new ArrayList<>();
        colourRec(ss, need, 0, new int[ss.size()], out, all ? 16 : 1);
        return out;
    }

    private static void colourRec(final List<Src> ss, final List<Integer> need, final int i, final int[] cur,
            final List<int[]> out, final int cap) {
        if (out.size() >= cap) return;
        if (i == ss.size()) {
            // coverage: each pip by a source of its colour, a source covers at most its amount
            final int[] cnt = new int[6];
            for (int j = 0; j < ss.size(); j++) cnt[cur[j]] += ss.get(j).amount;
            final int[] want = new int[6];
            for (int col : need) want[col]++;
            for (int col = 0; col < 6; col++) if (want[col] > cnt[col]) return;
            out.add(cur.clone());
            return;
        }
        final Src s = ss.get(i);
        // a one-colour or single-mana non-any source: its first useful colour only (no colour choice to make)
        final boolean choice = s.any && s.amount > 1 || Integer.bitCount(s.mask) > 1;
        boolean any = false;
        for (int col = 0; col < 6; col++) {
            if ((s.mask & (1 << col)) == 0) continue;
            if (!choice && any) break;
            cur[i] = col;
            any = true;
            colourRec(ss, need, i + 1, cur, out, cap);
            if (out.size() >= cap) return;
        }
    }

    /** The colours left floating after the pips are paid (multi-mana sources' excess). */
    static List<String> excessColours(final List<Src> ss, final int[] colour, final List<Integer> need, final int total) {
        final int[] cnt = new int[6];
        int amt = 0;
        for (int j = 0; j < ss.size(); j++) {
            cnt[colour[j]] += ss.get(j).amount;
            amt += ss.get(j).amount;
        }
        for (int col : need) cnt[col]--;
        int generic = total - need.size();
        final List<String> out = new ArrayList<>();
        for (int col = 5; col >= 0; col--) {
            while (cnt[col] > 0 && generic > 0) {
                cnt[col]--;
                generic--;
            }
        }
        for (int col = 0; col < 6; col++) for (int k = 0; k < cnt[col]; k++) out.add(ForcedWinModel.SHORT[col]);
        return amt > total ? out : new ArrayList<>();
    }

    /** A mana ability of {@code id} producing {@code col}, prepared to float it (labeller v2 / solver FLOAT steps). */
    static SpellAbility floatSa(final Game g, final Player me, final int id, final int col) {
        final Card host = g.findById(id);
        if (host == null) return null;
        for (SpellAbility m : host.getManaAbilities()) {
            final SpellAbility a = m.copy(me);
            a.setActivatingPlayer(me);
            if (a.getManaPart() == null || !a.canProduce(ForcedWinModel.SHORT[col])) continue;
            if (col < 5 && (a.getManaPart().isAnyMana() || a.getManaPart().isComboMana())) {
                a.setManaExpressChoice(forge.card.ColorSet.fromMask(ForcedWinModel.FORGE_COLOR[col]));
            }
            if (!a.canPlay() || !forge.ai.ComputerUtilCost.canPayCost(a, me, false)) continue;
            return a;
        }
        return null;
    }

    /** COLOR at priority: an any-colour mana ability played alone (Lion's Eye Diamond, Lotus): one answer per colour. */
    private List<SpellAbility> colourAtPriority(final List<SpellAbility> def) {
        final SpellAbility ma = def.get(0);
        if (ma.getManaPart() == null || !(ma.getManaPart().isAnyMana() || ma.getManaPart().isComboMana())
                || !live.getStack().isEmpty() && GameCopier.stackUnsupported(live, false) != null
                || live.getStack().isResolving()) return def;
        final Ask ask = new Ask();
        ask.kind = Kind.COLOR;
        ask.cast = true;
        ask.method = "manaAbility";
        ask.src = ma.getHostCard().getName();
        ask.copyStack = !live.getStack().isEmpty();
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.castCard = ma.getHostCard().getId();
        f.manaAlone = true;
        f.castIndex = ma.getHostCard().getManaAbilities().indexOf(ma.getRootAbility());
        f.desc.add("forge:" + ask.src);
        answers.add(f);
        for (int col = 0; col < 5; col++) {
            if (!ma.canProduce(ForcedWinModel.SHORT[col])) continue;
            final Answer a = new Answer();
            a.castCard = f.castCard;
            a.manaAlone = true;
            a.floats = List.<int[]>of(new int[] {f.castCard, col});
            a.sub = Kind.COLOR;
            a.desc.add("colour:" + ForcedWinModel.SHORT[col]);
            answers.add(a);
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = answers.size() - 1;
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        final SpellAbility m = floatSa(live, me, best.floats.get(0)[0], best.floats.get(0)[1]);
        return m == null ? def : Collections.singletonList(m);
    }

    @Override
    public CardCollectionView costCards(final PlayerControllerAi c, final SpellAbility sa, final String part,
            final CardCollectionView valid, final int amount, final CardCollectionView def) {
        if (!mine(c) || castPlan == null || castPlan.costPicks == null
                || sa != null && (sa.getHostCard() == null || sa.getHostCard().getId() != castPlan.castCard)) {
            return def;
        }
        final int k = castCostIdx++;
        if (k >= castPlan.costPicks.size() || castPlan.costPicks.get(k) == null) return def;
        final CardCollection out = byIds(castPlan.costPicks.get(k), valid);
        if (out == null) {
            count("liveMiss");
            return def;
        }
        return out;
    }

    @Override
    public boolean phyrexianLife(final PlayerControllerAi c, final SpellAbility sa, final boolean def) {
        if (!mine(c) || castPlan == null || castPlan.lifePips < 0 || sa == null || sa.getHostCard() == null
                || sa.getHostCard().getId() != castPlan.castCard) {
            return def;
        }
        return castPhyIdx++ < castPlan.lifePips;
    }

    /** SACRIFICE (resolution): an edict's or a "sacrifice a permanent" effect's choice. */
    @Override
    public CardCollectionView sacrifice(final PlayerControllerAi c, final SpellAbility sa, final int min, final int max,
            final CardCollectionView valid, final CardCollectionView def) {
        if (!mine(c) || def == null || valid == null) return def;
        if (pinned()) {
            if (plan != null && plan.cards != null && plan.sub == Kind.SACRIFICE) {
                final CardCollection out = byIds(plan.cards, valid);
                if (out != null) return out;
                count("liveMiss");
            }
            return def;
        }
        if (!cfg.kinds.contains(Kind.SACRIFICE) || def.isEmpty() || valid.size() <= def.size() || tapedOn()) return def;
        final Ask ask = ask(Kind.SACRIFICE, sa, "choosePermanentsToSacrifice");
        if (ask == null) return def;
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.cards = ids(def);
        f.sub = Kind.SACRIFICE;
        f.desc.add("forge:" + names(def));
        answers.add(f);
        final Set<String> seen = new HashSet<>();
        seen.add(stateKeys(def));
        final List<Card> opts = new ArrayList<>(valid);
        opts.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
        final int[] total = {0};
        combos(opts, def.size(), cfg.cap, list -> {
            total[0]++;
            if (seen.add(stateKeys(list)) && answers.size() < cfg.cap) {
                final Answer a = new Answer();
                a.cards = ids(list);
                a.sub = Kind.SACRIFICE;
                a.key = "sac:" + stateKeys(list);
                a.desc.add("sac:" + names(list));
                answers.add(a);
            }
        });
        f.key = "sac:" + stateKeys(def);
        final Decision d = new Decision(ask, null);
        d.nEnum = total[0];
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        final CardCollection out = byIds(best.cards, valid);
        if (out != null) startPlan(best, ask);
        return out == null ? def : out;
    }

    /** SACRIFICE (the legend rule, in state-based actions): which copy to keep. */
    @Override
    public Card legendKeep(final PlayerControllerAi c, final List<Card> options, final Card def) {
        if (!mine(c) || !cfg.kinds.contains(Kind.SACRIFICE) || options.size() < 2 || live.getStack().isResolving() || tapedOn()) {
            return def;
        }
        if (!live.getStack().isEmpty() && GameCopier.stackUnsupported(live, false) != null) {
            count("unsupported:legendStack");
            return def;
        }
        final Ask ask = new Ask();
        ask.kind = Kind.SACRIFICE;
        ask.method = "legendRule";
        ask.src = def.getName();
        ask.copyStack = !live.getStack().isEmpty();
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.cards = List.of(def.getId());
        f.sub = Kind.SACRIFICE;
        f.key = "keep:" + legendKey(def);
        f.desc.add("forge:keep " + legendKey(def));
        answers.add(f);
        final Set<String> seen = new HashSet<>();
        seen.add(legendKey(def));
        final List<Card> opts = new ArrayList<>(options);
        opts.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
        for (Card x : opts) {
            if (!seen.add(legendKey(x))) continue;
            final Answer a = new Answer();
            a.cards = List.of(x.getId());
            a.sub = Kind.SACRIFICE;
            a.key = "keep:" + legendKey(x);
            a.desc.add("keep:" + legendKey(x));
            answers.add(a);
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = opts.size();
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        final Card k = live.findById(best.cards.get(0));
        return k == null || !options.contains(k) ? def : k;
    }

    static String legendKey(final Card c) {
        return c.getName() + (c.isTapped() ? "/T" : "") + (c.isSick() ? "/sick" : "") + (c.hasCounters() ? "/" + c.getCounters() : "")
                + (c.isEnchanted() || c.isEquipped() ? "/attached" : "");
    }

    /** ORDER_TRIGGERS: our 2+ simultaneous triggers' order, permutations deduped by the host/trigger sequence. */
    @Override
    public List<SpellAbility> orderTriggers(final PlayerControllerAi c, final List<SpellAbility> sas,
            final List<SpellAbility> def) {
        if (DEBUG) System.err.println("[cac-dbg] orderTriggers mine=" + mine(c) + " n=" + sas.size());
        if (!mine(c) || !cfg.kinds.contains(Kind.ORDER_TRIGGERS) || sas.size() < 2 || tapedOn()) return def;
        for (SpellAbility s : sas) {
            if (!(s instanceof forge.game.trigger.WrappedAbility) || s.isCopied()
                    || s.getTrigger() != null && s.getTrigger().getMode() == forge.game.trigger.TriggerType.AbilityTriggered) {
                count("unsupported:orderNonTrigger");
                return def;
            }
        }
        if (live.getStack().hasSimultaneousStackEntries() || live.getStack().isResolving()) {
            count("unsupported:orderPending");
            return def;
        }
        final String why = live.getStack().isEmpty() ? null : GameCopier.stackUnsupported(live, false);
        if (why != null) {
            count("unsupported:" + why);
            return def;
        }
        final Ask ask = new Ask();
        ask.kind = Kind.ORDER_TRIGGERS;
        ask.method = "orderSimultaneousSa";
        ask.copyStack = !live.getStack().isEmpty();
        ask.trigs = new ArrayList<>(sas);
        final List<String> tk = new ArrayList<>();
        for (SpellAbility s : sas) tk.add(trigKey(s));
        ask.src = String.join("+", tk);
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.trigOrder = new ArrayList<>();
        for (SpellAbility s : def) f.trigOrder.add(sas.indexOf(s));
        f.sub = Kind.ORDER_TRIGGERS;
        f.key = "trig:" + seqKey(tk, f.trigOrder);
        f.desc.add("forge:" + seqKey(tk, f.trigOrder));
        answers.add(f);
        final Set<String> seen = new HashSet<>();
        seen.add(seqKey(tk, f.trigOrder));
        final List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < sas.size(); i++) idx.add(i);
        final List<List<Integer>> perms = new ArrayList<>();
        permIdx(new ArrayList<>(), idx, perms, 5040);
        int total = 0;
        for (List<Integer> pm : perms) {
            total++;
            if (answers.size() >= Math.min(cfg.cap, cfg.orderCap)) break;
            if (!seen.add(seqKey(tk, pm))) continue;
            final Answer a = new Answer();
            a.trigOrder = pm;
            a.sub = Kind.ORDER_TRIGGERS;
            a.key = "trig:" + seqKey(tk, pm);
            a.desc.add("order:" + seqKey(tk, pm));
            answers.add(a);
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = total;
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        final List<SpellAbility> out = new ArrayList<>();
        for (int i : best.trigOrder) out.add(sas.get(i));
        return out;
    }

    static String trigKey(final SpellAbility s) {
        final forge.game.trigger.Trigger t = s.getTrigger();
        return (s.getHostCard() == null ? "?" : s.getHostCard().getName()) + "#"
                + (t == null ? "" : t.getMode() + ":" + t.getParam("Execute"));
    }

    static String seqKey(final List<String> tk, final List<Integer> order) {
        final List<String> o = new ArrayList<>();
        for (int i : order) o.add(tk.get(i));
        return o.toString();
    }

    private static void permIdx(final List<Integer> cur, final List<Integer> rest, final List<List<Integer>> out,
            final int cap) {
        if (out.size() >= cap) return;
        if (rest.isEmpty()) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (int i = 0; i < rest.size(); i++) {
            final List<Integer> r = new ArrayList<>(rest);
            cur.add(r.remove(i));
            permIdx(cur, r, out, cap);
            cur.remove(cur.size() - 1);
        }
    }

    /** PAY_TO_PREVENT: pay a Daze / Mana Leak / tax, or not. */
    @Override
    public boolean payToPrevent(final PlayerControllerAi c, final forge.game.cost.Cost cost, final SpellAbility sa,
            final boolean canPay, final boolean def) {
        if (!mine(c) || !cfg.kinds.contains(Kind.PAY_TO_PREVENT) || !canPay || pinned() || tapedOn()) return def;
        final Ask ask = ask(Kind.PAY_TO_PREVENT, sa, "payCostToPreventEffect");
        if (ask == null) return def;
        final List<Answer> answers = new ArrayList<>();
        for (boolean b : new boolean[] {def, !def}) {
            final Answer a = new Answer();
            a.forge = answers.isEmpty();
            a.pay = b;
            a.sub = Kind.PAY_TO_PREVENT;
            a.desc.add((a.forge ? "forge:" : "") + (b ? "pay:" + cost.toSimpleString() : "decline"));
            answers.add(a);
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = 1;
        final Answer best = decide(d, answers);
        return best == null ? def : best.pay;
    }

    /** COLOR (resolution): a resolving ritual's or Manamorphose's any-colour mana. */
    @Override
    public byte chooseColor(final PlayerControllerAi c, final SpellAbility sa, final forge.card.ColorSet colors,
            final byte def) {
        if (!mine(c) || sa == null || !cfg.kinds.contains(Kind.COLOR) || pinned() || tapedOn() || live.getStack().isEmpty()
                || !live.getStack().isResolving()) return def;
        final Ask ask = ask(Kind.COLOR, sa, "chooseColor");
        if (ask == null) return def;
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.colour = def;
        f.sub = Kind.COLOR;
        f.desc.add("forge:" + forge.card.MagicColor.toShortString(def));
        answers.add(f);
        for (byte b : forge.card.MagicColor.WUBRG) {
            if ((colors.getColor() & b) == 0 || b == def) continue;
            final Answer a = new Answer();
            a.colour = b;
            a.sub = Kind.COLOR;
            a.desc.add("colour:" + forge.card.MagicColor.toShortString(b));
            answers.add(a);
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = answers.size() - 1;
        final Answer best = decide(d, answers);
        return best == null ? def : best.colour;
    }

    /** The resulting-state key of a cast answer (dedupes payments, colours and cost cards that end the same). */
    static String castKey(final Game g, final Player me) {
        final StringBuilder b = new StringBuilder("cast:");
        final List<String> bf = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) bf.add(stateKey(c));
        Collections.sort(bf);
        b.append(bf).append("|hand:").append(sortedNamesOf(me.getCardsIn(ZoneType.Hand)))
                .append("|gy:").append(sortedNamesOf(me.getCardsIn(ZoneType.Graveyard)))
                .append("|exile:").append(sortedNamesOf(me.getCardsIn(ZoneType.Exile)))
                .append("|pool:").append(poolKey(me))
                .append("|life:").append(me.getLife());
        return b.toString();
    }

    static String poolKey(final Player me) {
        final StringBuilder b = new StringBuilder();
        for (byte c : forge.card.MagicColor.WUBRGC) b.append(me.getManaPool().getAmountOfColor(c)).append(',');
        return b.toString();
    }

    static String sortedNamesOf(final Iterable<Card> cs) {
        final List<String> n = new ArrayList<>();
        for (Card c : cs) n.add(c.getName());
        Collections.sort(n);
        return n.toString();
    }

    // ------------------------------------------------------------------------------------------------ deciding

    /** One decision in progress. */
    final class Decision {
        final Ask ask;
        final List<Integer> forgeFirst;
        final int index = decisions++;
        int nEnum;
        PileBeam beam;
        final JsonObject entry = new JsonObject();
        final JsonArray cands = new JsonArray();
        int applied, leaves, terminals, misses, failures, solverChecks;
        long steps;

        /**
         * The live random stream as it stood just BEFORE Forge computed its own answer to this ask (a copy, from
         * {@link #beforeAsk}; else as it stands now): the baseline probe replays Forge from it, its first pick included
         * (Forge's pick shuffles the effect's own list in place, so forcing that pick would change the later ones).
         */
        final Random liveRng = takePreRng();
        final boolean preRng = lastPreRngTaken;

        Decision(final Ask ask, final List<Integer> forgeFirst) {
            this.ask = ask;
            this.forgeFirst = forgeFirst;
        }
    }

    private Card decidePicks(final Decision d, final List<Answer> answers, final Card def, final CardCollection fetchList) {
        final Answer best = d.beam != null ? d.beam.run(answers) : decide(d, answers);
        if (best != null && best.forge && best.probed && best.picks != null && !best.picks.isEmpty()
                && cfg.mode == Config.Mode.ON) {
            // review S2 (baseline): kept = the recorded Forge answer is played, so the valued state is the played one
            startPlan(best, d.ask);
            planPick = 1;
            return def;
        }
        if (best == null || best.forge || best.picks == null) {
            return def;
        }
        startPlan(best, d.ask);
        if (best.none) {
            return null;
        }
        planPick = 1;
        final int id = best.picks.get(0);
        for (Card x : fetchList) {
            if (x.getId() == id) {
                return x;
            }
        }
        plan = null;
        count("liveMiss");
        return def;
    }

    private void startPlan(final Answer a, final Ask ask) {
        pinTop = ask.top;
        plan = a;
        planPick = 0;
    }

    /** Apply every answer, one batched value pass, solver on the top-m; the answer to play (null = Forge's). */
    Answer decide(final Decision d, final List<Answer> answers) {
        final long t0 = System.nanoTime();
        final Answer[] sb = {null};
        final JsonObject e = d.entry;
        final PhaseHandler ph = live.getPhaseHandler();
        e.addProperty("i", d.index);
        e.addProperty("turn", ph.getTurn());
        e.addProperty("phase", String.valueOf(ph.getPhase()));
        e.addProperty("seat", live.getRegisteredPlayers().indexOf(me));
        e.addProperty("kind", d.ask.kind.name());
        e.addProperty("ask", d.ask.method);
        e.addProperty("src", d.ask.src);
        e.addProperty("n_enum", d.nEnum);
        e.addProperty("capped", d.nEnum + 1 > cfg.cap);
        Answer out = null;
        try {
            if (answers.size() < 2) {
                e.addProperty("outcome", "single");
                count("single");
                return null;
            }
            final List<Answer> list = answers.size() > cfg.cap ? new ArrayList<>(answers.subList(0, cfg.cap)) : answers;
            final double[] score = evaluate(d, list, true);
            if (score == null) {
                return null;
            }
            out = choose(d, list, score);
            if (cfg.mode == Config.Mode.SHADOW && list.get(0).probed) {
                sb[0] = list.get(0);
            }
            if (cfg.mode == Config.Mode.SHADOW && out != null && !out.forge) {
                // shadow: logged, never played (the live answer stays Forge AI's)
                e.addProperty("outcome", "shadow-would-depart");
                counts.merge("departed", -1L, Long::sum);
                count("shadowWouldDepart");
                return null;
            }
            return out;
        } catch (RuntimeException | StackOverflowError ex) {
            count("error");
            e.addProperty("outcome", "error");
            e.addProperty("error", String.valueOf(ex));
            System.err.println("[cac] decision " + d.index + " failed, keeping Forge's answer: " + ex);
            return null;
        } finally {
            e.add("cands", d.cands);
            final JsonObject w = new JsonObject();
            w.addProperty("applied", d.applied);
            w.addProperty("leaves", d.leaves);
            w.addProperty("terminals", d.terminals);
            w.addProperty("misses", d.misses);
            w.addProperty("failures", d.failures);
            w.addProperty("steps", d.steps);
            w.addProperty("solver_checks", d.solverChecks);
            e.add("work", w);
            e.addProperty("ms", Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
            pin(d.ask); // review S1-1: every outcome pins this resolution (a departure then sets the plan)
            shadowBase = sb[0];
            log.add(e);
            while (log.size() > LOG_KEEP) {
                log.remove(0);
            }
            if (sink != null) {
                sink.accept(e);
            }
        }
    }

    /** Applied copies of one batch (kept for the solver). */
    static final class Applied {
        final Answer a;
        final List<Prep> worlds = new ArrayList<>();
        double v = Double.NaN;
        boolean ok = true;
        String why;

        Applied(final Answer a) {
            this.a = a;
        }
    }

    /** One copy with the answer applied: its game and scopes, its leaf or terminal value. */
    static final class Prep {
        Game g;
        Player me;
        Object leaf;
        double terminal = Double.NaN;
        Object ids, cache;
        Random rnd;
    }

    private final List<Applied> lastApplied = new ArrayList<>();

    /** Apply and value a batch (one values() call). {@code solver}: also run the top-m check and log. */
    double[] evaluate(final Decision d, final List<Answer> list, final boolean finalBatch) {
        final List<Applied> applied = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();
        final List<int[]> where = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            final Answer a = list.get(i);
            final Applied ap = new Applied(a);
            applied.add(ap);
            final int w = Boolean.TRUE.equals(a.shuffle) ? cfg.shuffleWorlds : 1;
            for (int j = 0; j < w; j++) {
                // review S2-3: keyed on the decision, the world j and the beam depth only, so every answer of a batch
                // is played on the same streams (paired)
                final Prep p = apply(d, a, LookaheadSearch.mix(LookaheadSearch.mix(cfg.seed, 0xCAC0000L + d.index),
                        j + (d.beam == null ? 0 : 1_000_000L * d.beam.depth)));
                if (p == null) {
                    ap.ok = false;
                    break;
                }
                ap.worlds.add(p);
                if (Double.isNaN(p.terminal)) {
                    payloads.add(p.leaf);
                    where.add(new int[] {i, j});
                }
            }
        }
        // dedupe answers whose result key repeats an earlier one (Forge's recorded answer vs an enumerated one)
        final Set<String> keys = new HashSet<>();
        for (Applied ap : applied) {
            if (ap.ok && ap.a.key != null && !keys.add(ap.a.key) && !ap.a.forge) {
                ap.ok = false;
                ap.why = "duplicate";
            }
        }
        double[] vals = new double[0];
        if (!payloads.isEmpty()) {
            valueCalls++;
            vals = valuer.values(payloads);
            d.leaves += payloads.size();
            if (vals == null || vals.length != payloads.size()) {
                count("valueFailed");
                d.entry.addProperty("outcome", "valueFailed");
                d.entry.addProperty("error", String.valueOf(valuer.error()));
                release(applied);
                return null;
            }
        }
        final double[][] wv = new double[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            wv[i] = new double[applied.get(i).worlds.size()];
            for (int j = 0; j < wv[i].length; j++) {
                wv[i][j] = applied.get(i).worlds.get(j).terminal;
            }
        }
        for (int k = 0; k < where.size(); k++) {
            wv[where.get(k)[0]][where.get(k)[1]] = vals[k];
        }
        final double[] score = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final Applied ap = applied.get(i);
            if (!ap.ok || wv[i].length == 0) {
                score[i] = Double.NEGATIVE_INFINITY;
                continue;
            }
            double s = 0;
            for (double x : wv[i]) s += x;
            ap.v = s / wv[i].length;
            score[i] = ap.v;
        }
        lastApplied.clear();
        lastApplied.addAll(applied);
        if (!finalBatch) {
            release(applied);
        }
        return score;
    }

    private Answer choose(final Decision d, final List<Answer> list, final double[] v) {
        final double[] score = v.clone();
        final List<Applied> applied = new ArrayList<>(lastApplied);
        final JsonObject[] solverOut = new JsonObject[list.size()];
        if (checker != null && cfg.topM > 0) {
            final Integer[] ord = new Integer[list.size()];
            for (int i = 0; i < ord.length; i++) ord[i] = i;
            Arrays.sort(ord, (x, y) -> {
                final int c = Double.compare(v[y], v[x]);
                return c != 0 ? c : Integer.compare(x, y);
            });
            int checked = 0;
            for (Integer i : ord) {
                if (checked >= cfg.topM || v[i] == Double.NEGATIVE_INFINITY) break;
                final Applied ap = applied.get(i);
                if (ap.worlds.isEmpty() || !Double.isNaN(ap.worlds.get(0).terminal)) continue;
                checked++;
                d.solverChecks++;
                final Prep p = ap.worlds.get(0);
                final Verdict vd = inScope(p, () -> {
                    final Set<Integer> known = new HashSet<>(memory == null ? Collections.emptySet() : memory.known(p.me));
                    known.addAll(ap.a.placed());
                    return checker.check(p.g, p.me, known, LookaheadSearch.mix(cfg.seed, 0x50170000L + d.index * 64L + i));
                });
                solverOut[i] = vd.toJson();
                if (vd.win) {
                    score[i] = 1.0 + (vd.turnsToEnd >= 0 ? 1e-3 / (1 + vd.turnsToEnd) : 0);
                }
            }
        }
        release(applied);
        int best = 0;
        for (int i = 1; i < list.size(); i++) {
            if (score[i] > score[best]) best = i;
        }
        if (best != 0 && !(score[best] > score[0] + cfg.margin)) {
            best = 0;
        }
        if (score[0] == Double.NEGATIVE_INFINITY && best == 0) {
            d.entry.addProperty("outcome", "forgeFailed");
            count("forgeFailed");
            best = 0;
        }
        for (int i = 0; i < list.size(); i++) {
            final JsonObject c = new JsonObject();
            final Answer a = list.get(i);
            c.addProperty("i", i);
            final JsonArray desc = new JsonArray();
            a.desc.forEach(desc::add);
            c.add("answer", desc);
            if (a.key != null) c.addProperty("key", a.key);
            final Applied ap = applied.get(i);
            if (!ap.ok) c.addProperty("dropped", ap.why == null ? "apply" : ap.why);
            if (!Double.isNaN(ap.v)) c.addProperty("v", Math.round(ap.v * 1e6) / 1e6);
            if (!ap.worlds.isEmpty() && !Double.isNaN(ap.worlds.get(0).terminal)) c.addProperty("terminal", true);
            if (solverOut[i] != null) c.add("solver", solverOut[i]);
            if (score[i] != Double.NEGATIVE_INFINITY) c.addProperty("score", Math.round(score[i] * 1e6) / 1e6);
            d.cands.add(c);
        }
        lastCands = new ArrayList<>();
        for (Answer a : list) lastCands.add(String.join(" ", a.desc));
        lastScores = score;
        lastPick = best;
        d.entry.addProperty("forge", 0);
        d.entry.addProperty("pick", best);
        if (!d.entry.has("outcome")) {
            d.entry.addProperty("outcome", best == 0 ? "kept" : "departed");
        }
        count(best == 0 ? "kept" : "departed");
        return list.get(best);
    }

    private void release(final List<Applied> applied) {
        for (Applied ap : applied) {
            for (Prep p : ap.worlds) {
                p.g = null;
            }
        }
    }

    private <T> T inScope(final Prep p, final java.util.function.Supplier<T> f) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(p.rnd);
        AiCache.installScope(p.cache);
        forge.util.IdScope.install(p.ids);
        try {
            return f.get();
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    // ------------------------------------------------------------------------------------------------ applying

    /** The seat's controller in a copy: answers this resolution's asks from the answer, records Forge's otherwise. */
    static final class Scripted extends LookaheadSearch.RolloutAi {
        final Answer a;
        final boolean record;
        int pickIdx = 0;
        boolean orderUsed, shuffleUsed, arrangeUsed, cardsUsed;
        boolean miss = false;
        String missWhy = "";
        final List<Integer> recPicks = new ArrayList<>();
        List<Integer> recOrder;
        Boolean recShuffle;
        final Leaf leaf;
        final List<Integer> firstPick;

        Scripted(final Game g, final Player p, final forge.LobbyPlayer lp, final Answer a, final Leaf leaf,
                final List<Integer> firstPick) {
            super(g, p, lp);
            this.a = a;
            this.record = a.forge && !a.probed;
            this.leaf = leaf;
            this.firstPick = firstPick;
        }

        @Override
        public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
                final SpellAbility sa, final CardCollection fetchList, final forge.game.player.DelayedReveal delayedReveal,
                final String selectPrompt, final boolean isOptional, final Player decider) {
            if (!leaf.armed && decider == getPlayer()) {
                if (a.none && pickIdx == 0) {
                    pickIdx++;
                    return null;
                }
                if (a.picks != null && pickIdx < a.picks.size()) {
                    final int id = a.picks.get(pickIdx++);
                    for (Card x : fetchList) {
                        if (x.getId() == id) return x;
                    }
                    miss = true;
                    missWhy = "pick" + pickIdx;
                } else if (record) {
                    if (pickIdx == 0 && firstPick != null) {
                        pickIdx++;
                        for (Card x : fetchList) {
                            if (x.getId() == firstPick.get(0)) {
                                recPicks.add(x.getId());
                                return x;
                            }
                        }
                        miss = true;
                    }
                    final Card c = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                            selectPrompt, isOptional, decider);
                    if (c != null) recPicks.add(c.getId());
                    return c;
                }
            }
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                    isOptional, decider);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone,
                final SpellAbility source) {
            if (!leaf.armed && a.order != null && !orderUsed && destinationZone == ZoneType.Library) {
                orderUsed = true;
                final CardCollectionView o = toMoveOrder(a.order, cards, destinationZone);
                if (o != null) return o;
                miss = true;
                missWhy = "order" + cards.size() + "/" + a.order.size();
            }
            final CardCollectionView o = super.orderMoveToZoneList(cards, destinationZone, source);
            if (!leaf.armed && record && recOrder == null && destinationZone == ZoneType.Library) {
                recOrder = topFirst(o, destinationZone);
            }
            return o;
        }

        @Override
        public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message,
                final List<String> options, final Card cardToShow, final Map<String, Object> params) {
            if (!leaf.armed && a.shuffle != null && !shuffleUsed && sa != null && sa.getApi() == ApiType.RearrangeTopOfLibrary) {
                shuffleUsed = true;
                return a.shuffle;
            }
            final boolean b = super.confirmAction(sa, mode, message, options, cardToShow, params);
            if (!leaf.armed && record && recShuffle == null && sa != null && sa.getApi() == ApiType.RearrangeTopOfLibrary) {
                recShuffle = b;
            }
            return b;
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
            return arr(topN, true);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(final CardCollection topN) {
            return arr(topN, false);
        }

        private ImmutablePair<CardCollection, CardCollection> arr(final CardCollection topN, final boolean scry) {
            if (!leaf.armed && a.top != null && !arrangeUsed) {
                arrangeUsed = true;
                final CardCollection t = byIds(a.top, topN), w = byIds(a.away, topN);
                if (t != null && w != null && t.size() + w.size() == topN.size()) return ImmutablePair.of(t, w);
                miss = true;
            }
            return scry ? super.arrangeForScry(topN) : super.arrangeForSurveil(topN);
        }

        @Override
        public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa, final CardCollection validCards,
                final int min, final int max, final CardCollectionView visibleToChooser) {
            if (!leaf.armed && a.cards != null && !cardsUsed && p == getPlayer()) {
                cardsUsed = true;
                final CardCollection c = byIds(a.cards, validCards);
                if (c != null) return c;
                miss = true;
            }
            return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
        }

        @Override
        public <T extends forge.game.GameEntity> List<T> chooseEntitiesForEffect(
                final forge.util.collect.FCollectionView<T> optionList, final int min, final int max,
                final forge.game.player.DelayedReveal delayedReveal, final SpellAbility sa, final String title,
                final Player targetedPlayer, final Map<String, Object> params) {
            if (!leaf.armed && a.cards != null && !cardsUsed && sa != null && sa.getApi() == ApiType.Dig) {
                cardsUsed = true;
                final List<Card> opts = new ArrayList<>();
                for (T t : optionList) {
                    if (t instanceof Card) opts.add((Card) t);
                }
                final CardCollection c = byIds(a.cards, opts);
                if (c != null) {
                    @SuppressWarnings("unchecked")
                    final List<T> out = (List<T>) new ArrayList<Card>(c);
                    return out;
                }
                miss = true;
                missWhy = "dig";
            }
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (leaf.armed && leaf.reached(getGame())) {
                return null;
            }
            return super.chooseSpellAbilityToPlay();
        }

        // r3-inline-choices-1030
        List<Integer> trigPlan;
        boolean trigUsed, payUsed, colourUsed;
        int legendKeepId = -1;

        @Override
        public List<SpellAbility> orderSimultaneousSa(final List<SpellAbility> sas) {
            if (!leaf.armed && trigPlan != null && !trigUsed && sas != null && sas.size() == trigPlan.size()) {
                trigUsed = true;
                final List<SpellAbility> out = new ArrayList<>();
                for (int i : trigPlan) out.add(sas.get(i));
                return out;
            }
            return super.orderSimultaneousSa(sas);
        }

        @Override
        public CardCollectionView choosePermanentsToSacrifice(final SpellAbility sa, final int min, final int max,
                final CardCollectionView validTargets, final String message) {
            if (!leaf.armed && a.cards != null && a.sub == Kind.SACRIFICE && !cardsUsed) {
                cardsUsed = true;
                final CardCollection c = byIds(a.cards, validTargets);
                if (c != null) return c;
                miss = true;
                missWhy = "sac";
            }
            return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        }

        @Override
        public <T extends forge.game.GameEntity> T chooseSingleEntityForEffect(
                final forge.util.collect.FCollectionView<T> optionList, final forge.game.player.DelayedReveal delayedReveal,
                final SpellAbility sa, final String title, final boolean isOptional, final Player targetedPlayer,
                final Map<String, Object> params) {
            if (!leaf.armed && legendKeepId >= 0 && !cardsUsed && sa != null && sa.getApi() == ApiType.InternalLegendaryRule) {
                for (T t : optionList) {
                    if (t instanceof Card && ((Card) t).getId() == legendKeepId) {
                        cardsUsed = true;
                        return t;
                    }
                }
                miss = true;
                missWhy = "legend";
            }
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }

        @Override
        public boolean payCostToPreventEffect(final forge.game.cost.Cost cost, final SpellAbility sa,
                final boolean alreadyPaid, final forge.util.collect.FCollectionView<Player> allPayers) {
            if (!leaf.armed && a.pay != null && !payUsed) {
                payUsed = true;
                if (!a.pay) return false;
                if (!forge.ai.ComputerUtilCost.canPayCost(cost, sa, getPlayer(), true)) {
                    miss = true;
                    missWhy = "pay";
                    return false;
                }
                return new forge.game.cost.CostPayment(cost, sa).payComputerCosts(
                        forge.ai.AiCostDecision.forPayment(getPlayer(), sa, true));
            }
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }

        @Override
        public byte chooseColor(final String message, final SpellAbility sa, final forge.card.ColorSet colors) {
            if (!leaf.armed && a.colour != 0 && !colourUsed && (colors.getColor() & a.colour) != 0) {
                colourUsed = true;
                return a.colour;
            }
            return super.chooseColor(message, sa, colors);
        }

        /** Consumed every forced response it was given. */
        boolean complete() {
            if (miss) return false;
            if (a.pay != null && !payUsed) {
                missWhy = "noPay";
                return false;
            }
            if (a.colour != 0 && !colourUsed) {
                missWhy = "noColour";
                return false;
            }
            if (a.picks != null && !a.none && pickIdx < a.picks.size()) {
                missWhy = "picks" + pickIdx + "/" + a.picks.size();
                return false;
            }
            if (a.order != null && !record && !orderUsed) {
                missWhy = "noOrder";
                return false;
            }
            if (a.shuffle != null && !shuffleUsed && !record) return false;
            if (a.top != null && !arrangeUsed && !record) return false;
            return a.cards == null || cardsUsed || record;
        }
    }

    /** The other seats in a copy: Forge AI, stopping at the leaf. */
    static final class Watch extends LookaheadSearch.RolloutAi {
        final Leaf leaf;

        Watch(final Game g, final Player p, final forge.LobbyPlayer lp, final Leaf leaf) {
            super(g, p, lp);
            this.leaf = leaf;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (leaf.armed && leaf.reached(getGame())) {
                return null;
            }
            return super.chooseSpellAbilityToPlay();
        }
    }

    /**
     * The leaf point: once armed, the first priority of any seat with an EMPTY stack (R1's bank frames are taken at the
     * same point: the first empty-stack priority after the option). A priority with something on the stack is answered
     * by Forge AI (the triggers the answer caused resolve first).
     */
    static final class Leaf {
        volatile boolean armed = false, reached = false;

        /** True when this priority is the leaf (the seat then passes and the settle loop stops). */
        boolean reached(final Game g) {
            if (g.getStack().isEmpty() && !g.getStack().hasSimultaneousStackEntries()) {
                reached = true;
            }
            return reached;
        }
    }

    /** Copy the live game, apply {@code a}, settle to the leaf; null = the answer could not be applied. */
    private Prep apply(final Decision d, final Answer a, final long seed) {
        if (a.forge && !a.probed && probeKind(d.ask.kind) && d.liveRng != null) {
            probeForge(d, a, seed);
        }
        return apply(d, a, seed, false);
    }

    /**
     * Review S2 (baseline): record live Forge's own full answer to this resolution on an EXACT copy (our library
     * unshuffled, no re-draw: the position live Forge decides on) with a copy of the live random stream, then fix it
     * as candidate 0's answer. Only answers are read from this copy; it is never valued.
     */
    private void probeForge(final Decision d, final Answer a, final long seed) {
        final Answer rec = new Answer();
        rec.forge = true;
        final Prep pr = apply(d, rec, seed, true);
        if (pr == null || rec.probeSc == null) {
            count("probeFailed");
            return;
        }
        final Scripted sc = rec.probeSc;
        if (d.forgeFirst != null && !d.forgeFirst.isEmpty()
                && (sc.recPicks.isEmpty() || !sc.recPicks.get(0).equals(d.forgeFirst.get(0)))) {
            count("probeMismatch"); // the replay did not reproduce live Forge's first pick: keep the old record path
            return;
        }
        if (!sc.recPicks.isEmpty()) a.picks = new ArrayList<>(sc.recPicks);
        if (sc.recOrder != null) a.order = new ArrayList<>(sc.recOrder);
        else if (a.picks != null && (d.ask.kind == Kind.PILE || d.ask.kind == Kind.DIG)) a.order = new ArrayList<>(a.picks);
        if (sc.recShuffle != null) a.shuffle = sc.recShuffle;
        a.probed = true;
        a.key = null;
        a.desc.add("baseline:" + (a.picks != null ? namesById(live, a.order != null ? a.order : a.picks)
                : namesById(live, a.order)) + (Boolean.TRUE.equals(a.shuffle) ? "+shuffle" : ""));
    }

    private Prep apply(final Decision d, final Answer a, final long seed, final boolean probe) {
        final Ask ask = d.ask;
        final Prep p = new Prep();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        String why = null;
        try {
            d.applied++;
            final Game g;
            final Player cm;
            SpellAbility trig = null;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1);
                if ("legendRule".equals(ask.method)) {
                    copier.setDeferStateEffects(true); // the legend rule is resumed on the copy with the answer
                }
                if (ask.top != null || ask.copyStack) {
                    copier.setCopyStack(true);
                    copier.setSkipResolvingTrigger(true);
                }
                g = copier.makeCopy();
                cm = (Player) copier.find(me);
                if (ask.trigger) {
                    trig = LookaheadSearch.rebuildTrigger(g, cm, (forge.game.trigger.WrappedAbility) ask.top);
                }
                // honesty (DESIGN-r2 §14 (2)): the copy's unknown library slots are shuffled (known positions and the
                // cards this ask shows stay), one seed per decision so every answer sees the same arrangement
                if (memory != null && !probe) {
                    OwnLibraryMemory.shuffleUnknown(cm, memory.known(cm),
                            new Random(LookaheadSearch.mix(cfg.seed, 0x5EED0000L + d.index)));
                }
                if (redraw != null && !probe) {
                    redraw.apply(live, me, g, cm, new Random(LookaheadSearch.mix(cfg.seed, 0x0DD0000L + d.index)));
                }
            }
            MyRandom.setThreadRandom(probe ? cloneRandom(d.liveRng) : new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 3)));
            final Leaf leaf = new Leaf();
            final Scripted sc = new Scripted(g, cm, cm.getController().getLobbyPlayer(), a, leaf,
                    probe && d.preRng ? null : d.forgeFirst); // a pre-ask stream replays Forge's first pick too
            if (probe) {
                a.probeSc = sc;
            }
            cm.dangerouslySetController(sc);
            for (Player o : g.getPlayers()) {
                if (o != cm) o.dangerouslySetController(new Watch(g, o, o.getController().getLobbyPlayer(), leaf));
            }
            final Object ctx = valuer == null ? null : valuer.onCopy(g, cm);
            // resume the decision
            if (ask.cast) {
                why = applyCast(g, cm, sc, a); // r3-inline-choices-1030
                if (why != null) return null;
                if (a.rec != null) return p; // a record of the asks only: never valued
            } else if (ask.kind == Kind.TARGET) {
                why = applyTriggerTarget(g, cm, a, ask);
                if (why != null) return null;
            } else if (ask.kind == Kind.ORDER_TRIGGERS) {
                sc.trigPlan = a.trigOrder;
                why = applyOrder(g, cm, sc, a, ask);
                if (why != null) return null;
            } else if ("legendRule".equals(ask.method)) {
                sc.legendKeepId = a.cards.get(0);
                g.getAction().checkStateEffects(true);
                if (!sc.cardsUsed) {
                    if (DEBUG) System.err.println("[cac-dbg] legendNotAsked bf=" + castKey(g, cm) + " ctrl="
                            + cm.getController().getClass().getSimpleName() + " over=" + g.isGameOver());
                    why = "legendNotAsked";
                    return null;
                }
            } else switch (ask.kind) {
                case LAND: {
                    final Card land = g.findById(a.land);
                    SpellAbility ls = null;
                    if (land != null) {
                        for (SpellAbility s : land.getAllPossibleAbilities(cm, true)) {
                            if (s.isLandAbility()) {
                                ls = s;
                                break;
                            }
                        }
                    }
                    if (ls == null) {
                        why = "land";
                        return null;
                    }
                    ls.setActivatingPlayer(cm);
                    sc.playChosenSpellAbility(ls);
                    break;
                }
                case RETURN_COST: {
                    final Card host = g.findById(a.castCard);
                    final List<SpellAbility> all = host == null ? List.of()
                            : new ArrayList<>(host.getAllPossibleAbilities(cm, true));
                    if (a.castIndex < 0 || a.castIndex >= all.size()) {
                        why = "cast";
                        return null;
                    }
                    final SpellAbility cs = all.get(a.castIndex);
                    cs.setActivatingPlayer(cm);
                    final List<Integer> ret = a.returned;
                    sc.setCacHook(new forge.ai.CacHook() {
                        @Override
                        public CardCollectionView returnCost(final PlayerControllerAi pc, final SpellAbility s2,
                                final String type, final int amount, final CardCollectionView dd) {
                            final CardCollection o = byIds(ret, cm.getCardsIn(ZoneType.Battlefield));
                            if (o == null) {
                                sc.miss = true;
                                sc.missWhy = "return";
                                return dd;
                            }
                            sc.cardsUsed = true;
                            return o;
                        }
                    });
                    if (!sc.playChosenSpellAbility(cs)) {
                        why = "cast";
                        return null;
                    }
                    sc.setCacHook(null);
                    if (!sc.cardsUsed) {
                        sc.miss = true;
                        sc.missWhy = "returnNotAsked";
                    }
                    break;
                }
                case MULLIGAN: {
                    for (int id : a.cards) {
                        final Card c = g.findById(id);
                        if (c == null) {
                            why = "mulligan";
                            return null;
                        }
                        g.getAction().moveToBottomOfLibrary(c, null);
                    }
                    sc.cardsUsed = true;
                    break;
                }
                default: {
                    if (ask.trigger) {
                        cm.getController().playSpellAbilityNoStack(trig, false);
                        g.getAction().checkStaticAbilities();
                        g.getPhaseHandler().resetPriority();
                        g.getPhaseHandler().onStackResolved();
                    } else if (ask.chain == 0) {
                        g.getStack().resolveStack();
                    } else {
                        SpellAbility sub = g.getStack().peekAbility();
                        for (int i = 0; i < ask.chain && sub != null; i++) {
                            sub = sub.getSubAbility();
                        }
                        if (sub == null) {
                            why = "chain";
                            return null;
                        }
                        g.getStack().resolveStackFrom(sub);
                    }
                }
            }
            if (probe) {
                return sc.miss ? null : p; // the probe reads only the recorded answers
            }
            if (!sc.complete()) {
                d.misses++;
                why = "planMiss:" + sc.missWhy;
                return null;
            }
            if (a.forge && ask.kind != Kind.LAND && ask.kind != Kind.MULLIGAN) {
                // Forge's full answer, as recorded on its copy: its result key, to dedupe the enumerated answers
                if (a.picks == null && !sc.recPicks.isEmpty()) {
                    a.key = pickKey(g, sc.recPicks, sc.recOrder, ask.kind);
                    a.desc.add("rec:" + namesById(g, sc.recPicks));
                } else if (a.order != null || sc.recOrder != null) {
                    final List<Integer> o = sc.recOrder != null ? sc.recOrder : a.order;
                    a.key = "order:" + namesById(g, o) + (sc.recShuffle != null && sc.recShuffle ? "|shuffle" : "");
                    if (sc.recShuffle != null && sc.recShuffle) a.desc.add("rec:shuffle");
                }
            }
            if (a.key == null) {
                a.key = keyOf(g, a, ask.kind);
            }
            leaf.armed = true;
            int steps = 0;
            final PhaseHandler ph = g.getPhaseHandler();
            if (ask.kind == Kind.MULLIGAN) {
                leaf.reached = true; // no game loop yet: the leaf is the kept hand
            }
            if (a.manaAlone) {
                leaf.reached = true; // r3: mana floated alone is the leaf as it stands (the pool empties at the step's end)
            }
            while (!g.isGameOver() && !leaf.reached && steps < cfg.settleSteps) {
                ph.mainLoopStep();
                steps++;
            }
            d.steps += steps;
            p.g = g;
            p.me = cm;
            if (g.isGameOver() || !cm.isInGame()) {
                d.terminals++;
                p.terminal = cm.hasWon() ? 1.0 : cm.hasLost() || !cm.isInGame() ? 0.0 : 0.5;
                for (Player o : cm.getOpponents()) {
                    if (o.hasLost() && !cm.hasLost()) p.terminal = 1.0;
                }
            } else if (!leaf.reached) {
                if (DEBUG) System.err.println("[cac-dbg] settle " + ask.src + " phase=" + g.getPhaseHandler().getPhase()
                        + " turn=" + g.getPhaseHandler().getTurn() + " stack=" + g.getStack().size() + " simul="
                        + g.getStack().hasSimultaneousStackEntries() + " top=" + (g.getStack().isEmpty() ? "-"
                        : g.getStack().peekAbility()) + " prio=" + g.getPhaseHandler().getPriorityPlayer());
                why = "settle";
                return null;
            } else {
                p.leaf = valuer.capture(ctx, g, cm);
            }
            return p;
        } catch (RuntimeException | StackOverflowError ex) {
            d.failures++;
            why = "error:" + ex;
            if (d.failures <= 3) {
                System.err.println("[cac] apply failed: " + ex);
                if (d.failures == 1) ex.printStackTrace();
            }
            return null;
        } finally {
            if (why != null) {
                count("drop:" + (why.startsWith("error") ? "error" : why.replaceAll("[0-9]+", "#")));
                if (DEBUG) System.err.println("[cac-dbg] drop " + ask.kind + " " + ask.src + " " + why + (a.forge ? " (forge)" : ""));
            }
            p.ids = forge.util.IdScope.capture();
            p.cache = AiCache.captureScope();
            p.rnd = MyRandom.getThreadRandom();
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** r3-inline-choices-1030: cast (or activate) the answer's ability on the copy with its payment; null = applied. */
    private String applyCast(final Game g, final Player cm, final Scripted sc, final Answer a) {
        final Card host = g.findById(a.castCard);
        if (host == null) return "cast";
        if (a.manaAlone) {
            final SpellAbility m;
            if (a.floats != null) {
                m = floatSa(g, cm, a.floats.get(0)[0], a.floats.get(0)[1]);
            } else {
                final List<SpellAbility> mas = new ArrayList<>(host.getManaAbilities());
                if (a.castIndex < 0 || a.castIndex >= mas.size()) return "mana";
                m = mas.get(a.castIndex).copy(cm);
                m.setActivatingPlayer(cm);
            }
            if (m == null) return "float";
            sc.playChosenSpellAbility(m);
            a.key = castKey(g, cm);
            return null;
        }
        final List<SpellAbility> all = new ArrayList<>(host.getAllPossibleAbilities(cm, true));
        if (a.castIndex < 0 || a.castIndex >= all.size()) return "cast";
        final SpellAbility cs = all.get(a.castIndex);
        cs.setActivatingPlayer(cm);
        if (castAsk != null && !copyTargets(castAsk, cs, g)) return "targets";
        if (a.tgtChain >= 0 && !retarget(cs, a.tgtChain, a.tgt, g)) return "retarget";
        final int before = g.getStack().size();
        if (a.floats != null) {
            for (int[] fl : a.floats) {
                final SpellAbility m = floatSa(g, cm, fl[0], fl[1]);
                if (m == null) return "float";
                sc.playChosenSpellAbility(m);
            }
        }
        final int[] ci = {0}, pi = {0}, used = {0};
        sc.setCacHook(new forge.ai.CacHook() {
            @Override
            public CardCollectionView costCards(final PlayerControllerAi pc, final SpellAbility s2, final String part,
                    final CardCollectionView valid, final int amount, final CardCollectionView dd) {
                if (pc != sc) return dd;
                final int k = ci[0]++;
                if (a.rec != null) {
                    a.rec.parts.add(part);
                    a.rec.valid.add(ids(valid));
                    a.rec.amounts.add(amount);
                    a.rec.defs.add(ids(dd));
                    return dd;
                }
                if (a.costPicks == null || k >= a.costPicks.size() || a.costPicks.get(k) == null) return dd;
                final CardCollection o = byIds(a.costPicks.get(k), valid);
                if (o == null) {
                    sc.miss = true;
                    sc.missWhy = "cost";
                    return dd;
                }
                used[0]++;
                return o;
            }

            @Override
            public boolean phyrexianLife(final PlayerControllerAi pc, final SpellAbility s2, final boolean dd) {
                if (pc != sc) return dd;
                final int k = pi[0]++;
                if (a.rec != null) {
                    a.rec.phyrexian++;
                    return dd;
                }
                if (a.lifePips < 0) return dd;
                if (k < a.lifePips) used[0]++;
                return k < a.lifePips;
            }
        });
        try {
            sc.playChosenSpellAbility(cs);
        } finally {
            sc.setCacHook(null);
        }
        // the cast's own triggers (storm, cast triggers) go on the stack now, as the game loop puts them there before
        // the next priority; a cast made outside the loop otherwise leaves them pending and the copy never settles
        if (g.getStack().hasSimultaneousStackEntries()) {
            g.getStack().addAllTriggeredAbilitiesToStack();
        }
        if (g.getStack().size() <= before) {
            if (DEBUG) System.err.println("[cac-dbg] notCast " + host + " zone=" + host.getZone() + " life=" + cm.getLife()
                    + " bf=" + castKey(g, cm) + " phy=" + pi[0] + " used=" + used[0]);
            return "notCast";
        }
        int need = 0;
        if (a.costPicks != null) for (List<Integer> x : a.costPicks) if (x != null) need++;
        if (a.lifePips > 0) need += a.lifePips;
        if (a.rec == null && used[0] < need) return "costNotAsked";
        if (sc.miss) return "planMiss:" + sc.missWhy;
        // the cast's targets are part of its result (they are not on the board yet): Brain Freeze on us vs on them
        final List<String> tk = new ArrayList<>();
        for (SpellAbility x = cs; x != null; x = x.getSubAbility()) {
            if (x.usesTargeting()) for (forge.game.GameObject o : x.getTargets()) tk.add(targetKey(o));
        }
        a.key = castKey(g, cm) + (tk.isEmpty() ? "" : "|tgt:" + tk);
        return null;
    }

    /** The live cast whose copies are being applied (its targets), set around a cast decision's applies. */
    private SpellAbility castAsk = null;
    static final boolean DEBUG = Boolean.getBoolean("forge.cac.debug");

    /** The entity a TARGET answer encodes, in {@code g}. */
    private forge.game.GameObject entity(final int code, final Game g) {
        if (code < 0) {
            final int i = -code - 1;
            return i < g.getRegisteredPlayers().size() ? g.getRegisteredPlayers().get(i) : null;
        }
        return g.findById(code);
    }

    private int code(final forge.game.GameObject o) {
        if (o instanceof Player p) return -(live.getRegisteredPlayers().indexOf(p) + 1);
        return o instanceof Card c ? c.getId() : Integer.MIN_VALUE;
    }

    /** Set the chain link's one target to the encoded entity (legal on {@code g}). */
    private boolean retarget(final SpellAbility root, final int chain, final int code, final Game g) {
        SpellAbility x = root;
        for (int i = 0; i < chain && x != null; i++) x = x.getSubAbility();
        final forge.game.GameObject o = entity(code, g);
        if (x == null || o == null || !x.canTarget(o)) return false;
        x.resetTargets();
        x.getTargets().add(o);
        return true;
    }

    /**
     * TARGET answers: the first chain link that targets exactly one entity (as Forge AI chose it), every other legal
     * candidate, deduped by the target's state (two untapped Islands are one answer; players by seat), capped.
     */
    private int targetAnswers(final SpellAbility sa, final Answer f, final List<Answer> answers) {
        int chain = 0;
        for (SpellAbility x = sa; x != null; x = x.getSubAbility(), chain++) {
            if (!x.usesTargeting()) continue;
            if (x.getTargets().size() != 1 || x.getTargetRestrictions() == null) return 0;
            final forge.game.GameObject cur = x.getTargets().getFirstTargetedCard() != null ? x.getTargets().getFirstTargetedCard()
                    : x.getTargets().getFirstTargetedPlayer();
            if (cur == null) return 0;
            final Set<String> seen = new HashSet<>();
            seen.add(targetKey(cur));
            int total = 0;
            final List<forge.game.GameEntity> cands = new ArrayList<>(x.getTargetRestrictions().getAllCandidates(x, false));
            cands.sort((p, q) -> Integer.compare(code(p), code(q)));
            for (forge.game.GameEntity e : cands) {
                if (!(e instanceof Card) && !(e instanceof Player) || !x.canTarget(e)) continue;
                total++;
                if (!seen.add(targetKey(e)) || answers.size() >= cfg.cap) continue;
                final Answer a = new Answer();
                a.castCard = f.castCard;
                a.castIndex = f.castIndex;
                a.tgtChain = chain;
                a.tgt = code(e);
                a.sub = Kind.TARGET;
                a.desc.add("target:" + targetKey(e));
                answers.add(a);
            }
            return total;
        }
        return 0;
    }

    static String targetKey(final Object o) {
        if (o instanceof Player p) return "player:" + p.getName();
        if (o instanceof Card c) return stateKey(c) + (c.getController() == null ? "" : "/" + c.getController().getName());
        return String.valueOf(o);
    }

    /** TARGET (a lone trigger): Forge AI's targets are candidate 0; each other legal target is applied on a copy. */
    @Override
    public void triggerTargets(final PlayerControllerAi c, final SpellAbility sa) {
        if (!mine(c) || !cfg.kinds.contains(Kind.TARGET) || !(sa instanceof forge.game.trigger.WrappedAbility w)
                || tapedOn()) return;
        if (live.getStack().hasSimultaneousStackEntries() || live.getStack().isResolving()) {
            count("unsupported:targetPending");
            return;
        }
        if (!live.getStack().isEmpty() && GameCopier.stackUnsupported(live, false) != null) {
            count("unsupported:targetStack");
            return;
        }
        final Ask ask = new Ask();
        ask.kind = Kind.TARGET;
        ask.method = "triggerTargets";
        ask.copyStack = !live.getStack().isEmpty();
        ask.trigs = List.of(sa);
        ask.src = trigKey(sa);
        final List<Answer> answers = new ArrayList<>();
        final Answer f = new Answer();
        f.forge = true;
        f.sub = Kind.TARGET;
        int chain = 0;
        SpellAbility x = sa;
        for (; x != null && !x.usesTargeting(); x = x.getSubAbility()) chain++;
        if (x == null || x.getTargets().size() != 1) return;
        final Object cur = x.getTargets().getFirstTargetedCard() != null ? x.getTargets().getFirstTargetedCard()
                : x.getTargets().getFirstTargetedPlayer();
        if (!(cur instanceof Card) && !(cur instanceof Player)) return;
        f.tgtChain = chain;
        f.tgt = code((forge.game.GameObject) cur);
        f.key = "target:" + targetKey(cur);
        f.desc.add("forge:" + targetKey(cur));
        answers.add(f);
        final Set<String> seen = new HashSet<>();
        seen.add(targetKey(cur));
        int total = 0;
        final List<forge.game.GameEntity> cands = new ArrayList<>(x.getTargetRestrictions().getAllCandidates(x, false));
        cands.sort((p, q) -> Integer.compare(code(p), code(q)));
        for (forge.game.GameEntity e : cands) {
            if (!(e instanceof Card) && !(e instanceof Player) || !x.canTarget(e)) continue;
            total++;
            if (!seen.add(targetKey(e)) || answers.size() >= cfg.cap) continue;
            final Answer a = new Answer();
            a.tgtChain = chain;
            a.tgt = code(e);
            a.sub = Kind.TARGET;
            a.key = "target:" + targetKey(e);
            a.desc.add("target:" + targetKey(e));
            answers.add(a);
        }
        final Decision d = new Decision(ask, null);
        d.nEnum = total;
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return;
        if (!retarget(sa, best.tgtChain, best.tgt, live)) count("liveMiss");
    }

    /** Copy the live ability's targets (cards, players, stack entries; its sub-abilities too) onto {@code cs}. */
    private boolean copyTargets(final SpellAbility from, final SpellAbility cs, final Game g) {
        SpellAbility x = from, y = cs;
        while (x != null) {
            if (y == null) return false;
            if (x.usesTargeting()) {
                y.resetTargets();
                for (forge.game.GameObject o : x.getTargets()) {
                    final Object m;
                    if (o instanceof SpellAbility ts) {
                        m = mapStack(ts, g);
                    } else {
                        m = mapObj(o, g);
                    }
                    if (m == null || m == NO_MAP || !(m instanceof forge.game.GameObject)) return false;
                    y.getTargets().add((forge.game.GameObject) m);
                }
            }
            x = x.getSubAbility();
            y = y.getSubAbility();
        }
        return true;
    }

    /** A live stack entry's ability in the copy (same depth from the top), or null. */
    private SpellAbility mapStack(final SpellAbility ts, final Game g) {
        final List<SpellAbility> l = new ArrayList<>(), c = new ArrayList<>();
        for (forge.game.spellability.SpellAbilityStackInstance si : live.getStack()) l.add(si.getSpellAbility());
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) c.add(si.getSpellAbility());
        final int i = l.indexOf(ts);
        return i < 0 || l.size() != c.size() ? null : c.get(i);
    }

    /** r3-inline-choices-1030: rebuild our pending triggers on the copy and put them on its stack in the answer's order. */
    private String applyOrder(final Game g, final Player cm, final Scripted sc, final Answer a, final Ask ask) {
        for (SpellAbility ls : ask.trigs) {
            final forge.game.trigger.WrappedAbility w = (forge.game.trigger.WrappedAbility) ls;
            final SpellAbility inner = LookaheadSearch.rebuildTrigger(g, cm, w);
            final Map<forge.game.ability.AbilityKey, Object> m = mapTrig(w.getTriggeringObjects(), g);
            if (m == null) return "trigObj";
            inner.setTriggeringObjects(m);
            final forge.game.trigger.WrappedAbility cw = new forge.game.trigger.WrappedAbility(inner.getTrigger(), inner, cm);
            cw.setOptionalTrigger(w.isOptionalTrigger());
            g.getStack().addSimultaneousStackEntry(cw);
        }
        g.getStack().addAllTriggeredAbilitiesToStack();
        return sc.trigUsed ? null : "orderNotAsked";
    }

    /** r3-target-choices-1030: the lone trigger rebuilt on the copy with the answer's target, put on the stack. */
    private String applyTriggerTarget(final Game g, final Player cm, final Answer a, final Ask ask) {
        final forge.game.trigger.WrappedAbility w = (forge.game.trigger.WrappedAbility) ask.trigs.get(0);
        final SpellAbility inner = LookaheadSearch.rebuildTrigger(g, cm, w);
        final Map<forge.game.ability.AbilityKey, Object> m = mapTrig(w.getTriggeringObjects(), g);
        if (m == null) return "trigObj";
        inner.setTriggeringObjects(m);
        final forge.game.trigger.WrappedAbility cw = new forge.game.trigger.WrappedAbility(inner.getTrigger(), inner, cm);
        cw.setOptionalTrigger(w.isOptionalTrigger());
        if (!retarget(cw, a.tgtChain, a.tgt, g)) return "retarget";
        g.getStack().add(cw);
        return g.getStack().isEmpty() ? "notOnStack" : null;
    }

    private static final Object NO_MAP = new Object();

    /** A trigger's objects mapped into the copy by id (null = an object that cannot be mapped). */
    private Map<forge.game.ability.AbilityKey, Object> mapTrig(final Map<forge.game.ability.AbilityKey, Object> m,
            final Game g) {
        final Map<forge.game.ability.AbilityKey, Object> out = new java.util.EnumMap<>(forge.game.ability.AbilityKey.class);
        if (m == null) return out;
        for (Map.Entry<forge.game.ability.AbilityKey, Object> e : m.entrySet()) {
            final Object v = mapObj(e.getValue(), g);
            if (v == NO_MAP) return null;
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    private Object mapObj(final Object v, final Game g) {
        if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean || v instanceof Enum) return v;
        if (v instanceof Card c) {
            final Card x = g.findById(c.getId());
            return x == null ? NO_MAP : x;
        }
        if (v instanceof Player p) {
            final int i = live.getRegisteredPlayers().indexOf(p);
            return i < 0 ? NO_MAP : g.getRegisteredPlayers().get(i);
        }
        if (v instanceof CardCollectionView cc) {
            final CardCollection out = new CardCollection();
            for (Card c : cc) {
                final Card x = g.findById(c.getId());
                if (x == null) return NO_MAP;
                out.add(x);
            }
            return out;
        }
        return NO_MAP;
    }

    // ------------------------------------------------------------------------------------------------ asks

    /** Where the ask comes from on the live stack (null = unsupported, counted). */
    private Ask ask(final Kind kind, final SpellAbility sa, final String method) {
        final Ask a = new Ask();
        a.kind = kind;
        a.sa = sa;
        a.method = method;
        if (live.getStack().isEmpty() || !live.getStack().isResolving()) {
            count("unsupported:notResolving");
            return null;
        }
        final String why = GameCopier.stackUnsupported(live, true);
        if (why != null) {
            count("unsupported:" + why);
            return null;
        }
        final SpellAbility top = live.getStack().peekAbility();
        a.top = top;
        a.src = top.getHostCard() == null ? "-" : top.getHostCard().getName();
        SpellAbility root = top;
        if (top instanceof forge.game.trigger.WrappedAbility wa) {
            root = wa.getWrappedAbility();
            a.trigger = true;
        }
        int i = 0;
        for (SpellAbility s = root; s != null; s = s.getSubAbility(), i++) {
            if (s == sa) {
                a.chain = i;
                break;
            }
        }
        if (a.chain < 0) {
            count("unsupported:chain");
            return null;
        }
        if (a.trigger && a.chain != 0) {
            count("unsupported:triggerSub");
            return null;
        }
        return a;
    }

    /** An ask raised inside the resolving top's effect (scry, surveil) without its own sa: from the top's root. */
    private Ask askFromTop(final Kind kind, final String method) {
        final SpellAbility top = live.getStack().peekAbility();
        SpellAbility root = top instanceof forge.game.trigger.WrappedAbility wa ? wa.getWrappedAbility() : top;
        // the scry / surveil sub in the chain: the first one with that API
        final ApiType want = kind == Kind.SCRY ? ApiType.Scry : ApiType.Surveil;
        for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
            if (s.getApi() == want) {
                return ask(kind, s, method);
            }
        }
        count("unsupported:arrangeSource");
        return null;
    }

    /** A later sub-ability of sa's chain orders the cards (Reorder, RearrangeTopOfLibrary). */
    static boolean orderFollows(final SpellAbility sa) {
        if (sa.hasParam("Reorder")) return true;
        for (SpellAbility s = sa.getSubAbility(); s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.RearrangeTopOfLibrary) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ enumeration

    static String rkey(final Card c) {
        return c.getName() + "@" + (c.getZone() == null ? "?" : c.getZone().getZoneType());
    }

    /** Distinct (name, zone) representatives, lowest id first. */
    static Map<String, Card> reps(final Iterable<Card> cards) {
        final List<Card> sorted = new ArrayList<>();
        cards.forEach(sorted::add);
        sorted.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        final Map<String, Card> m = new LinkedHashMap<>();
        for (Card c : sorted) m.putIfAbsent(rkey(c), c);
        return m;
    }

    /**
     * Review S2-4: the order answers are enumerated in, so a cap drops the least likely ones: route role first
     * (finisher, draw, protection, mana, land, other: the route library's rank), then mana value (higher first), then id.
     * A heuristic for WHICH answers are applied when over the cap; the value tower still decides among them.
     */
    static final java.util.Comparator<Card> PRIOR = (a, b) -> {
        final int r = Integer.compare(PileBeam.ROLES.rankOf(a.getName()), PileBeam.ROLES.rankOf(b.getName()));
        if (r != 0) return r;
        final int m = Integer.compare(b.getCMC(), a.getCMC());
        return m != 0 ? m : Integer.compare(a.getId(), b.getId());
    };

    /** Over the cap: the names no applied answer uses are logged (review S2-4). */
    void logDropped(final Decision d, final Iterable<Card> all, final List<Answer> answers) {
        if (d.nEnum + 1 <= cfg.cap) return;
        final Set<Integer> used = new HashSet<>();
        for (Answer a : answers) {
            if (a.picks != null) used.addAll(a.picks);
            if (a.cards != null) used.addAll(a.cards);
            if (a.returned != null) used.addAll(a.returned);
        }
        final java.util.TreeSet<String> dropped = new java.util.TreeSet<>();
        final Set<String> usedNames = new HashSet<>();
        for (Card c : all) if (used.contains(c.getId())) usedNames.add(c.getName());
        for (Card c : all) if (!usedNames.contains(c.getName())) dropped.add(c.getName());
        final JsonArray j = new JsonArray();
        dropped.forEach(j::add);
        d.entry.add("dropped_names", j);
    }

    /** Pick answers of size n from the list: ordered sequences or sets, by (name, zone) multiset, in PRIOR order. */
    void enumerate(final CardCollection fetchList, final int n, final boolean ordered, final List<Answer> out,
            final int[] total) {
        final List<Card> sorted = new ArrayList<>(fetchList);
        sorted.sort(PRIOR);
        final Map<String, List<Card>> byKey = new LinkedHashMap<>();
        for (Card c : sorted) byKey.computeIfAbsent(rkey(c), k -> new ArrayList<>()).add(c);
        final List<String> keys = new ArrayList<>(byKey.keySet());
        final int[] used = new int[keys.size()];
        final List<Integer> seq = new ArrayList<>();
        rec(keys, byKey, used, seq, n, ordered, 0, out, total);
    }

    private void rec(final List<String> keys, final Map<String, List<Card>> byKey, final int[] used,
            final List<Integer> seq, final int n, final boolean ordered, final int from, final List<Answer> out,
            final int[] total) {
        if (seq.size() == n) {
            total[0]++;
            if (out.size() >= cfg.cap) return;
            final Answer a = new Answer();
            a.picks = new ArrayList<>();
            final List<String> nm = new ArrayList<>();
            final int[] u = new int[keys.size()];
            for (int k : seq) {
                a.picks.add(byKey.get(keys.get(k)).get(u[k]++).getId());
                nm.add(keys.get(k));
            }
            if (ordered) a.order = new ArrayList<>(a.picks);
            a.desc.add((ordered ? "seq:" : "set:") + nm);
            out.add(a);
            return;
        }
        for (int k = ordered ? 0 : from; k < keys.size(); k++) {
            if (used[k] >= byKey.get(keys.get(k)).size()) continue;
            used[k]++;
            seq.add(k);
            rec(keys, byKey, used, seq, n, ordered, k, out, total);
            seq.remove(seq.size() - 1);
            used[k]--;
            if (out.size() >= cfg.cap && total[0] > cfg.cap * 4L) return;
        }
    }

    /** Distinct ordered sequences of n from the pool's multiset, counted up to {@code stop}. */
    static long countSequences(final List<Card> pool, final Map<String, Integer> counts, final int n, final long stop) {
        final List<Integer> c = new ArrayList<>();
        for (Card x : pool) c.add(counts.getOrDefault(rkey(x), 1));
        return countRec(c, n, stop);
    }

    private static long countRec(final List<Integer> c, final int n, final long stop) {
        if (n == 0) return 1;
        long t = 0;
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i) == 0) continue;
            c.set(i, c.get(i) - 1);
            t += countRec(c, n - 1, stop - t);
            c.set(i, c.get(i) + 1);
            if (t >= stop) return t;
        }
        return t;
    }

    static void permutations(final List<Card> cards, final List<List<Card>> out, final int cap) {
        permRec(new ArrayList<>(), new ArrayList<>(cards), out, cap);
    }

    private static void permRec(final List<Card> cur, final List<Card> rest, final List<List<Card>> out, final int cap) {
        if (out.size() >= cap * 4) return;
        if (rest.isEmpty()) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (int i = 0; i < rest.size(); i++) {
            final Card c = rest.remove(i);
            cur.add(c);
            permRec(cur, rest, out, cap);
            cur.remove(cur.size() - 1);
            rest.add(i, c);
        }
    }

    /** Every k-subset of the cards (in id order), until the consumer has seen cap*4 of them. */
    static void combos(final List<Card> cards, final int k, final int cap, final java.util.function.Consumer<List<Card>> f) {
        cards.sort(PRIOR); // review S2-4: a cap drops the least likely subsets
        comboRec(cards, k, 0, new ArrayList<>(), f, new int[] {cap * 4});
    }

    private static void comboRec(final List<Card> cards, final int k, final int from, final List<Card> cur,
            final java.util.function.Consumer<List<Card>> f, final int[] left) {
        if (left[0] <= 0) return;
        if (cur.size() == k) {
            left[0]--;
            f.accept(new ArrayList<>(cur));
            return;
        }
        for (int i = from; i < cards.size(); i++) {
            cur.add(cards.get(i));
            comboRec(cards, k, i + 1, cur, f, left);
            cur.remove(cur.size() - 1);
        }
    }

    // ------------------------------------------------------------------------------------------------ keys, conversions

    static String multisetKey(final Iterable<Card> cs) {
        final List<String> n = new ArrayList<>();
        for (Card c : cs) n.add(c.getName());
        Collections.sort(n);
        return String.join("+", n);
    }

    static String arrKey(final Iterable<Card> top, final Iterable<Card> away) {
        final StringBuilder b = new StringBuilder("t:");
        for (Card c : top) b.append(c.getName()).append('>');
        return b.append("|a:").append(multisetKey(away)).toString();
    }

    static String pickKey(final Game g, final List<Integer> picks, final List<Integer> order, final Kind kind) {
        final List<String> n = new ArrayList<>();
        for (int id : picks) {
            final Card c = g.findById(id);
            n.add(c == null ? "#" + id : c.getName());
        }
        final boolean ordered = order != null && (kind == Kind.PILE || kind == Kind.DIG);
        if (ordered) {
            return "seq:" + namesById(g, order);
        }
        Collections.sort(n);
        return "set:" + n;
    }

    /** The result key of an enumerated answer (names; zones for picks). */
    String keyOf(final Game g, final Answer a, final Kind kind) {
        if (a.picks != null) {
            final List<String> n = new ArrayList<>();
            for (int id : a.picks) {
                final Card c = live.findById(id);
                n.add(c == null ? "#" + id : c.getName());
            }
            if (a.order != null && (kind == Kind.PILE || kind == Kind.DIG)) {
                return "seq:" + n;
            }
            Collections.sort(n);
            return "set:" + n;
        }
        if (a.order != null) {
            return "order:" + namesById(live, a.order) + (Boolean.TRUE.equals(a.shuffle) ? "|shuffle" : "");
        }
        if (a.top != null) {
            return "arr:" + namesById(live, a.top) + "|" + sortedNames(live, a.away);
        }
        if (a.cards != null) {
            return "cards:" + sortedNames(live, a.cards);
        }
        if (a.land >= 0) {
            final Card c = live.findById(a.land);
            return "land:" + (c == null ? a.land : c.getName());
        }
        if (a.trigOrder != null) {
            return "trig:" + a.trigOrder;
        }
        if (a.pay != null) {
            return "pay:" + a.pay;
        }
        if (a.colour != 0) {
            return "colour:" + a.colour;
        }
        return "?";
    }

    static String namesById(final Game g, final List<Integer> ids) {
        final List<String> n = new ArrayList<>();
        if (ids != null) {
            for (int id : ids) {
                final Card c = g.findById(id);
                n.add(c == null ? "#" + id : c.getName());
            }
        }
        return n.toString();
    }

    static String sortedNames(final Game g, final List<Integer> ids) {
        final List<String> n = new ArrayList<>();
        for (int id : ids) {
            final Card c = g.findById(id);
            n.add(c == null ? "#" + id : c.getName());
        }
        Collections.sort(n);
        return n.toString();
    }

    static List<Integer> ids(final Iterable<Card> cs) {
        final List<Integer> out = new ArrayList<>();
        if (cs != null) for (Card c : cs) out.add(c.getId());
        return out;
    }

    static String names(final Iterable<Card> cs) {
        final List<String> out = new ArrayList<>();
        if (cs != null) for (Card c : cs) out.add(c.getName());
        return out.toString();
    }

    static CardCollection byIds(final List<Integer> ids, final Iterable<Card> from) {
        final CardCollection out = new CardCollection();
        if (ids == null) return out;
        for (int id : ids) {
            Card hit = null;
            for (Card c : from) {
                if (c.getId() == id) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) return null;
            out.add(hit);
        }
        return out;
    }

    /**
     * orderMoveToZoneList's answer is the order the cards are MOVED in, one at a time, each to the top of the library:
     * the last one ends on top. Top-first → move order is the reverse (other destinations: as given).
     */
    static CardCollectionView toMoveOrder(final List<Integer> topFirst, final CardCollectionView cards, final ZoneType dest) {
        final CardCollection o = byIds(topFirst, cards);
        if (o == null || o.size() != cards.size()) return null;
        if (dest == ZoneType.Library) {
            final List<Card> l = new ArrayList<>(o);
            Collections.reverse(l);
            return new CardCollection(l);
        }
        return o;
    }

    static List<Integer> topFirst(final CardCollectionView moveOrder, final ZoneType dest) {
        final List<Integer> l = ids(moveOrder);
        if (dest == ZoneType.Library) Collections.reverse(l);
        return l;
    }

    /** Pile beam support: a sequence answer over the pool. */
    static Answer pileAnswer(final List<Card> seq) {
        final Answer a = new Answer();
        a.picks = ids(seq);
        a.order = new ArrayList<>(a.picks);
        a.desc.add("seq:" + names(seq));
        return a;
    }

    private Random preAskRng = null;
    private boolean lastPreRngTaken = false;

    @Override
    public void beforeAsk(final PlayerControllerAi c) {
        preAskRng = mine(c) && !pinned() ? cloneRandom(MyRandom.getRandom()) : null;
    }

    /** The snapshot taken for THIS ask (moved out of preAskRng at the hook's entry, so a stale one is never used). */
    private Random curPre = null;

    private Random takePreRng() {
        final Random r = curPre;
        curPre = null;
        lastPreRngTaken = r != null;
        return r != null ? r : cloneRandom(MyRandom.getRandom());
    }

    /** A copy of a random stream's state (null when it cannot be copied). Never advances {@code r}. */
    static Random cloneRandom(final Random r) {
        if (r == null) return null;
        if (r instanceof PlayoutKeys.TrackedRandom t) return PlayoutKeys.TrackedRandom.ofSnapshot(t.snapshot());
        try {
            final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(bo)) {
                o.writeObject(r);
            }
            try (java.io.ObjectInputStream i = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bo.toByteArray()))) {
                return (Random) i.readObject();
            }
        } catch (java.io.IOException | ClassNotFoundException e) {
            return null;
        }
    }

    static boolean probeKind(final Kind k) {
        return k == Kind.SEARCH || k == Kind.MULTI || k == Kind.PILE || k == Kind.DIG || k == Kind.ORDER;
    }

    static Answer pileAnswerOf(final Answer a) {
        final Answer b = new Answer();
        b.picks = new ArrayList<>(a.picks);
        b.order = a.order == null ? null : new ArrayList<>(a.order);
        b.desc.addAll(a.desc);
        return b;
    }

    /** The live game (beam). */
    Game liveGame() {
        return live;
    }
}
