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
    public enum Kind { SEARCH, MULTI, PILE, DIG, DIGPICK, DISCARD, SCRY, SURVEIL, ORDER, MULLIGAN, LAND, RETURN_COST }

    // ------------------------------------------------------------------------------------------------ config

    public static final class Config {
        public enum Mode { OFF, SHADOW, ON }

        public Mode mode = Mode.OFF;
        public Set<Kind> kinds = new java.util.TreeSet<>(Arrays.asList(Kind.values()));
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
        public int beamBranch = 10;
        /** A departure must beat Forge's answer's score by more than this. */
        public double margin = 0.0;
        public long seed = 1029L;

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
            if (c.cap < 2 || c.shuffleWorlds < 1 || c.settleSteps < 1 || c.beamWidth < 1 || c.beamBranch < 1 || c.topM < 0) {
                throw new IllegalArgumentException("chooseACard: cap >= 2, topM >= 0, shuffleWorlds/settleSteps/beam >= 1");
            }
            return c;
        }

        static final Set<String> KEYS = new java.util.TreeSet<>(Arrays.asList("mode", "kinds", "cap", "topM",
                "shuffleWorlds", "settleSteps", "beamWidth", "beamBranch", "margin", "seed"));

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
    }

    // ------------------------------------------------------------------------------------------------ state

    private final Config cfg;
    private Valuer valuer;
    private Checker checker;
    private Game live;
    private Player me;
    private OwnLibraryMemory memory;
    private int decisions = 0;
    /** The plan the live seat follows for the rest of the resolution it departed in. */
    private Answer plan = null;
    private SpellAbility planTop = null;
    private int planPick = 0;
    /** One entry per hooked decision (DESIGN-r2 §8). */
    public final JsonArray log = new JsonArray();
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
        this.live = ctrl.getGame();
        this.me = ctrl.getPlayer();
        this.memory = new OwnLibraryMemory(me);
        memory.watch(live);
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

    /** The plan is still live: the same stack entry is resolving. */
    private boolean planLive() {
        if (plan == null) {
            return false;
        }
        if (planTop == null || live.getStack().isEmpty() || !live.getStack().isResolving()
                || live.getStack().peekAbility() != planTop) {
            plan = null;
            planTop = null;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ hooks

    @Override
    public Card pick(final PlayerControllerAi c, final ZoneType destination, final List<ZoneType> origin,
            final SpellAbility sa, final CardCollection fetchList, final boolean isOptional, final Player decider,
            final Card def) {
        if (!mine(c) || decider != me || fetchList == null || fetchList.isEmpty()) {
            return def;
        }
        if (planLive() && plan.picks != null) {
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
        if (kind == Kind.PILE && countSequences(pool, counts0, n, cfg.cap + 1) > cfg.cap - 1) {
            d.beam = new PileBeam(this, d, fetchList, n);
        } else {
            enumerate(fetchList, n, exactOrdered, answers, total);
        }
        d.nEnum = total[0];
        return decidePicks(d, answers, def, fetchList);
    }

    @Override
    public CardCollectionView order(final PlayerControllerAi c, final CardCollectionView cards, final ZoneType destination,
            final SpellAbility sa, final CardCollectionView def) {
        if (!mine(c) || cards == null) {
            return def;
        }
        if (destination == ZoneType.Library && memory != null) {
            memory.noteLooked(cards);
        }
        if (destination == ZoneType.Library && planLive() && plan.order != null) {
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
            final boolean s = plan.shuffle;
            plan = null;
            return s;
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
        if (!cfg.kinds.contains(kind) || live.getStack().isEmpty() || !live.getStack().isResolving()) {
            return def;
        }
        final SpellAbility sa = live.getStack().peekAbility();
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
        return ImmutablePair.of(t, w);
    }

    @Override
    public CardCollection discard(final PlayerControllerAi c, final Player discarding, final SpellAbility sa,
            final CardCollection valid, final int min, final int max, final CardCollection def) {
        if (!mine(c) || discarding != me || valid == null || valid.isEmpty() || !cfg.kinds.contains(Kind.DISCARD)
                || def == null || min != max || min < 1 || live.getStack().isEmpty() || !live.getStack().isResolving()) {
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
        final Answer best = decide(d, answers);
        if (best == null || best.forge) {
            return def;
        }
        final CardCollection out = byIds(best.cards, valid);
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
        final Answer best = decide(d, answers);
        if (best == null || best.forge) {
            return def;
        }
        final CardCollection out = byIds(best.cards, hand);
        return out == null ? def : out;
    }

    @Override
    public List<SpellAbility> priority(final PlayerControllerAi c, final List<SpellAbility> def) {
        if (mine(c) && def != null && def.size() == 1 && !def.get(0).isLandAbility() && returnPart(def.get(0)) != null) {
            return returnCostDecision(def);
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
        if (!mine(c) || !cfg.kinds.contains(Kind.DIGPICK) || options.isEmpty() || max < 1 || def == null
                || live.getStack().isEmpty() || !live.getStack().isResolving()) {
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
        final Answer best = decide(d, answers);
        if (best == null || best.forge) return def;
        final CardCollection out = byIds(best.cards, options);
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

        Decision(final Ask ask, final List<Integer> forgeFirst) {
            this.ask = ask;
            this.forgeFirst = forgeFirst;
        }
    }

    private Card decidePicks(final Decision d, final List<Answer> answers, final Card def, final CardCollection fetchList) {
        final Answer best = d.beam != null ? d.beam.run(answers) : decide(d, answers);
        if (best == null || best.forge || best.picks == null || best.picks.isEmpty()) {
            return def;
        }
        startPlan(best, d.ask);
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
        plan = a;
        planTop = ask.top;
        planPick = 0;
    }

    /** Apply every answer, one batched value pass, solver on the top-m; the answer to play (null = Forge's). */
    Answer decide(final Decision d, final List<Answer> answers) {
        final long t0 = System.nanoTime();
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
            if (cfg.mode == Config.Mode.SHADOW && out != null && !out.forge) {
                // shadow: logged, never played (the live answer stays Forge AI's)
                e.addProperty("outcome", "shadow-would-depart");
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
            log.add(e);
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
                final Prep p = apply(d, a, LookaheadSearch.mix(LookaheadSearch.mix(cfg.seed, 0xCAC0000L + d.index),
                        (long) i * 64 + j + (d.beam == null ? 0 : 1_000_000L * d.beam.depth)));
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
            this.record = a.forge;
            this.leaf = leaf;
            this.firstPick = firstPick;
        }

        @Override
        public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
                final SpellAbility sa, final CardCollection fetchList, final forge.game.player.DelayedReveal delayedReveal,
                final String selectPrompt, final boolean isOptional, final Player decider) {
            if (!leaf.armed && decider == getPlayer()) {
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
            if (!leaf.armed && a.order != null && !orderUsed && !record && destinationZone == ZoneType.Library) {
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

        /** Consumed every forced response it was given. */
        boolean complete() {
            if (miss) return false;
            if (a.picks != null && pickIdx < a.picks.size()) {
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
                if (ask.top != null) {
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
                if (memory != null) {
                    OwnLibraryMemory.shuffleUnknown(cm, memory.known(cm),
                            new Random(LookaheadSearch.mix(cfg.seed, 0x5EED0000L + d.index)));
                }
                if (redraw != null) {
                    redraw.apply(live, me, g, cm, new Random(LookaheadSearch.mix(cfg.seed, 0x0DD0000L + d.index)));
                }
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 3)));
            final Leaf leaf = new Leaf();
            final Scripted sc = new Scripted(g, cm, cm.getController().getLobbyPlayer(), a, leaf, d.forgeFirst);
            cm.dangerouslySetController(sc);
            for (Player o : g.getPlayers()) {
                if (o != cm) o.dangerouslySetController(new Watch(g, o, o.getController().getLobbyPlayer(), leaf));
            }
            final Object ctx = valuer == null ? null : valuer.onCopy(g, cm);
            // resume the decision
            switch (ask.kind) {
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
            }
            p.ids = forge.util.IdScope.capture();
            p.cache = AiCache.captureScope();
            p.rnd = MyRandom.getThreadRandom();
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
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

    /** Pick answers of size n from the list: ordered sequences or sets, by (name, zone) multiset, ids lowest first. */
    void enumerate(final CardCollection fetchList, final int n, final boolean ordered, final List<Answer> out,
            final int[] total) {
        final List<Card> sorted = new ArrayList<>(fetchList);
        sorted.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
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
        cards.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
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
