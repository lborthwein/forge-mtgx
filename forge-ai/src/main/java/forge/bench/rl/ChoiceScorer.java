package forge.bench.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.ChoiceApply;
import forge.ai.simulation.LookaheadSearch;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * choice-scorer-1106 (GOAL "choose-a-card"; off unless the spec's {@code choiceScorer} is set): a choice the search does
 * not reach is answered by enumerating its options, applying each on a copy, featurizing the position it leads to for the
 * seat, and one batched value pass. No card, combo or answer knowledge: options come from the ask's menu.
 *
 * <p>Honest input: a frame reads the seat's knowledge only (the live tracker forked per copy; searched-to-top cards kept
 * with {@code searchedTop}). A menu above {@code cap} keeps the policy's answer, the menu's first cards and
 * {@code explore} seeded random others, so the cut can't hide an option for good (N12).
 */
public final class ChoiceScorer {

    public static final class Config {
        /** MULLIGAN, MULLIGAN_BOTTOM, CARDS. */
        public Set<Integer> families = new java.util.TreeSet<>(Arrays.asList(RlSchema.F_CARDS));
        public int cap = 40;
        public int explore = 4;
        /** New hands per scored mulligan. */
        public int mullWorlds = 8;
        /** Main-loop steps from the resolved answer to the next priority. */
        public int settleSteps = 60;
        /** An answer must beat the policy's by more than this. */
        public double margin = 0.0;
        /**
         * solver-distill-cc-1109 (false = off, the default, and then absent from the JSON): declining an optional pick is
         * an option only when it is the policy's own answer; the scorer never departs TO declining. The served values
         * (R3F and the outcome-trained tower alike) rate the position after a declined search above the one after the
         * search, and random-deviation games price a dropped pick at about -4 points. Generic: no card knowledge.
         */
        public boolean noDecline = false;
        /** Wall budget of one ask's option applications, ms; past it the unapplied options are dropped (counted). */
        public long budgetMs = 6000;
        /** Keep searched-to-top cards for this search's seats (the live tracker and its forks). */
        public boolean searchedTop = true;
        public int fidelity = 1;
        /** Score and log, keep the policy's answer. */
        public boolean shadow = false;
        /** Planner screen per applied option (nodes; 0 = off) and its wall cap per option, ms. */
        public int screenNodes = 0;
        public long screenMs = 200;
        /**
         * solver-distill-cc-1109 (REACH; 0 = off, the default): each applied option's value is lifted by its reach, the
         * planner screen over the seat's next unknown draw ({@link forge.ai.simulation.ForcedWinSolver#reach}, with
         * screenNodes / screenMs per screen): P = a*R + (1 - a*R) * v with a = reachAlpha; options rank by P alone.
         */
        public double reachAlpha = 0.0;
        public int reachMaxNames = 40;
        /** REACH cost knobs (ForcedWinSolver.ReachOpts: nameNodes, nameOnePass, skipCapped, sig; defaults = off). */
        public int reachNameNodes = -1;
        public boolean reachOnePass, reachSkipCapped, reachSig;
        /**
         * solver-distill-cc-1109 (GOLDFISH; gfHorizon 0 = off, the default; read with reachAlpha): an applied option
         * whose reach is below 1 is also planned over gfHorizon of our turns with gfSamples sampled orders of our unknown
         * library ({@link forge.ai.simulation.ForcedWinSolver#goldfish}); the option's reach is the larger of the two.
         */
        public int gfHorizon = 0, gfSamples = 0, gfNodes = 1500, gfExtra = 2, gfActive = 3;
        public long gfMs = 60;
        public double gfGamma = 0.8, gfLambda = 0.6;
        /** The land-drop relaxation, and one ask's node allowance for its options' goldfish (shared evenly; 0 = none). */
        public boolean gfLands = true;
        public long gfAskNodes = 120_000;
        /**
         * solver-distill-cc-1109 (0 = off, the default): a CARDS ask whose resolution asks again (put-backs: Brainstorm and
         * the like; the guard's "multiAnswer") is answered as a whole: every ordered sequence of {@code seqs} distinct
         * menu cards is applied on a copy (no Forge AI tail) and scored like a single pick; the chosen sequence answers the
         * resolution's later picks and its order ask. The first ask of a resolution only. No card knowledge.
         */
        public int seqs = 0;
        /** Ordered multi-pick choices (piles): the planner proposes, applied piles are screened and valued. Off by default. */
        public boolean pile = false;
        public int pileTop = 6;
        public int pileNodes = 30000;
        public long pileMs = 1500;
        /** Cards never answered by a copy whose frame depends on the opponent's hidden hand ({@link #hiddenGuard}). */
        public boolean guard = true;
        /** The solver's config for the screen (spec's plans.solverCfg; not a JSON key). */
        public forge.ai.simulation.ForcedWinSolver.Config solverCfg;
        /**
         * Leak test only (never a JSON key): before the frame, swap the opponent's unknown hand cards with unknown library
         * cards and shuffle its library when none of it is known. A frame that changes reads hidden cards.
         */
        public boolean probeOpp = false;

        static final Set<String> KEYS = new java.util.TreeSet<>(Arrays.asList("families", "cap", "explore", "mullWorlds",
                "settleSteps", "margin", "noDecline", "budgetMs", "searchedTop", "fidelity", "shadow", "screenNodes", "screenMs", "guard", "pile", "pileTop", "pileNodes", "pileMs", "reachAlpha", "reachMaxNames", "gfHorizon", "gfSamples", "gfNodes", "gfExtra", "gfMs", "gfGamma", "gfActive", "gfLambda", "gfLands", "gfAskNodes", "seqs", "reachNameNodes", "reachOnePass", "reachSkipCapped", "reachSig"));

        public static Config parse(final JsonObject o) {
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("choiceScorer: unknown key " + k);
                }
            }
            final Config c = new Config();
            if (o.has("families")) {
                c.families = new java.util.TreeSet<>();
                for (String f : o.get("families").getAsString().split(",")) {
                    final int i = RlSchema.FAMILIES.indexOf(f.trim());
                    if (i != RlSchema.F_CARDS && i != RlSchema.F_MULLIGAN && i != RlSchema.F_MULLIGAN_BOTTOM) {
                        throw new IllegalArgumentException("choiceScorer: family not supported: " + f);
                    }
                    c.families.add(i);
                }
            }
            if (o.has("cap")) c.cap = o.get("cap").getAsInt();
            if (o.has("explore")) c.explore = o.get("explore").getAsInt();
            if (o.has("mullWorlds")) c.mullWorlds = o.get("mullWorlds").getAsInt();
            if (o.has("settleSteps")) c.settleSteps = o.get("settleSteps").getAsInt();
            if (o.has("margin")) c.margin = o.get("margin").getAsDouble();
            if (o.has("noDecline")) c.noDecline = o.get("noDecline").getAsBoolean();
            if (o.has("budgetMs")) c.budgetMs = o.get("budgetMs").getAsLong();
            if (o.has("searchedTop")) c.searchedTop = o.get("searchedTop").getAsBoolean();
            if (o.has("fidelity")) c.fidelity = o.get("fidelity").getAsInt();
            if (o.has("shadow")) c.shadow = o.get("shadow").getAsBoolean();
            if (o.has("screenNodes")) c.screenNodes = o.get("screenNodes").getAsInt();
            if (o.has("screenMs")) c.screenMs = o.get("screenMs").getAsLong();
            if (o.has("reachAlpha")) c.reachAlpha = o.get("reachAlpha").getAsDouble();
            if (o.has("reachMaxNames")) c.reachMaxNames = o.get("reachMaxNames").getAsInt();
            if (o.has("reachNameNodes")) c.reachNameNodes = o.get("reachNameNodes").getAsInt();
            if (o.has("reachOnePass")) c.reachOnePass = o.get("reachOnePass").getAsBoolean();
            if (o.has("reachSkipCapped")) c.reachSkipCapped = o.get("reachSkipCapped").getAsBoolean();
            if (o.has("reachSig")) c.reachSig = o.get("reachSig").getAsBoolean();
            if (o.has("gfHorizon")) c.gfHorizon = o.get("gfHorizon").getAsInt();
            if (o.has("gfSamples")) c.gfSamples = o.get("gfSamples").getAsInt();
            if (o.has("gfNodes")) c.gfNodes = o.get("gfNodes").getAsInt();
            if (o.has("gfExtra")) c.gfExtra = o.get("gfExtra").getAsInt();
            if (o.has("gfMs")) c.gfMs = o.get("gfMs").getAsLong();
            if (o.has("gfGamma")) c.gfGamma = o.get("gfGamma").getAsDouble();
            if (o.has("gfActive")) c.gfActive = o.get("gfActive").getAsInt();
            if (o.has("gfLambda")) c.gfLambda = o.get("gfLambda").getAsDouble();
            if (o.has("gfLands")) c.gfLands = o.get("gfLands").getAsBoolean();
            if (o.has("gfAskNodes")) c.gfAskNodes = o.get("gfAskNodes").getAsLong();
            if (o.has("seqs")) c.seqs = o.get("seqs").getAsInt();
            if (o.has("guard")) c.guard = o.get("guard").getAsBoolean();
            if (o.has("pile")) c.pile = o.get("pile").getAsBoolean();
            if (o.has("pileTop")) c.pileTop = o.get("pileTop").getAsInt();
            if (o.has("pileNodes")) c.pileNodes = o.get("pileNodes").getAsInt();
            if (o.has("pileMs")) c.pileMs = o.get("pileMs").getAsLong();
            return c;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            final List<String> fs = new ArrayList<>();
            for (int f : families) {
                fs.add(RlSchema.familyName(f));
            }
            o.addProperty("families", String.join(",", fs));
            o.addProperty("cap", cap);
            o.addProperty("explore", explore);
            o.addProperty("mullWorlds", mullWorlds);
            o.addProperty("settleSteps", settleSteps);
            o.addProperty("margin", margin);
            if (noDecline) {
                o.addProperty("noDecline", true);
            }
            o.addProperty("budgetMs", budgetMs);
            o.addProperty("searchedTop", searchedTop);
            o.addProperty("fidelity", fidelity);
            o.addProperty("shadow", shadow);
            o.addProperty("screenNodes", screenNodes);
            o.addProperty("screenMs", screenMs);
            if (reachAlpha > 0) {
                o.addProperty("reachAlpha", reachAlpha);
                o.addProperty("reachMaxNames", reachMaxNames);
                if (reachNameNodes >= 0) {
                    o.addProperty("reachNameNodes", reachNameNodes);
                }
                if (reachOnePass) {
                    o.addProperty("reachOnePass", true);
                }
                if (reachSkipCapped) {
                    o.addProperty("reachSkipCapped", true);
                }
                if (reachSig) {
                    o.addProperty("reachSig", true);
                }
                if (gfHorizon > 0) {
                    o.addProperty("gfHorizon", gfHorizon);
                    o.addProperty("gfSamples", gfSamples);
                    o.addProperty("gfNodes", gfNodes);
                    o.addProperty("gfExtra", gfExtra);
                    o.addProperty("gfMs", gfMs);
                    o.addProperty("gfGamma", gfGamma);
                    o.addProperty("gfActive", gfActive);
                    o.addProperty("gfLambda", gfLambda);
                    o.addProperty("gfLands", gfLands);
                    o.addProperty("gfAskNodes", gfAskNodes);
                }
            }
            if (seqs > 0) {
                o.addProperty("seqs", seqs);
            }
            o.addProperty("guard", guard);
            o.addProperty("pile", pile);
            o.addProperty("pileTop", pileTop);
            o.addProperty("pileNodes", pileNodes);
            o.addProperty("pileMs", pileMs);
            return o;
        }
    }

    /** One answer: the seat's steps, its label, and its frames (several for an expectation over new hands). */
    public static final class Option {
        public short[] steps;
        public String label;
        public final List<byte[]> frames = new ArrayList<>();
        /** Per frame: the sub-answer group (a new hand), and terminal values. */
        public final List<Integer> groups = new ArrayList<>();
        public double terminal = Double.NaN;
        public String fail;
        public boolean policy;
        public double value = Double.NaN;
        /** Further picks of this resolution Forge AI answered. */
        public int tail;
        public forge.ai.simulation.ForcedWinSolver.Screen screen;
        /** A pile option: the cards top first. */
        public List<Integer> pileIds;
        /** solver-distill-cc-1109: a whole-resolution answer ({@link Config#seqs}); pileIds holds its picks. */
        public boolean seq;
    }

    /** One scored ask. */
    public static final class Scored {
        public final List<Option> options = new ArrayList<>();
        public String unsupported;
        public int menuOptions, cut, failed, frames;
        public long copyNanos, runNanos, featNanos, totalNanos;
        public boolean overBudget;
        public Option chosen;
    }

    /** Frames at most for one opening-hand ask. */
    static final int MULL_FRAMES = 120;

    private boolean guardSwap = false;
    /** sdcc-leak-1111: the guard's second probe, our own unknown library cards reversed ({@link #swapOwnHidden}). */
    private boolean guardSwapOwn = false;
    private Object screenReader;
    private int pileSeen = 0;
    /** The chosen pile of the resolution in progress (top first), and the picks already given; null when none. */
    private List<Integer> pilePlan;
    private int pileKey, pileNext;
    private final Config cfg;
    private final CardIndex index;
    private final int obsVersion;
    private final long uid;

    public ChoiceScorer(final Config cfg, final CardIndex index, final int obsVersion, final long uid) {
        this.cfg = cfg;
        this.index = index;
        this.obsVersion = obsVersion;
        this.uid = uid;
    }

    public Config config() {
        return cfg;
    }

    /** The frame side over a live tracker. */
    private ChoiceApply.Frames frames(final RlKnowledge live, final int seatMask, final Scored s) {
        return new ChoiceApply.Frames() {
            @Override
            public Object onCopy(final Game copy, final Player me) {
                final RlKnowledge k = live.forkFor(copy);
                if (seatMask != 0) {
                    k.setSearchedTopSeats(seatMask);
                }
                if (cfg.probeOpp || guardSwap) {
                    swapOpponentHidden(copy, me, k);
                }
                if (guardSwapOwn) {
                    swapOwnHidden(copy, me, k); // sdcc-leak-1111
                }
                k.attach();
                final RlFeaturizer f = new RlFeaturizer(index);
                f.setVersion(obsVersion);
                f.setKnowledge(k);
                return f;
            }

            @Override
            public java.util.Set<Integer> hidden(final Object ctx, final Game g, final Player me) {
                if (!ownGuard()) {
                    return null; // sdcc-leak-1111: off without the solver's hideOwnLibrary, or with hiddenOrderLegacy
                }
                final RlKnowledge k = ((RlFeaturizer) ctx).knowledge();
                final int seat = k.seatOf(me);
                final java.util.Set<Integer> out = new java.util.HashSet<>();
                for (Card c : me.getCardsIn(ZoneType.Library)) {
                    if (seat < 0 || !k.knows(seat, c)) {
                        out.add(c.getId());
                    }
                }
                for (Player o : g.getPlayers()) {
                    if (o != me) {
                        for (Card c : o.getCardsIn(ZoneType.Hand)) {
                            if (seat < 0 || !k.knows(seat, c)) {
                                out.add(c.getId());
                            }
                        }
                    }
                }
                return out;
            }

            @Override
            public byte[] capture(final Object ctx, final Game g, final Player me) {
                final long t = System.nanoTime();
                final RlFeaturizer.Obs o = ((RlFeaturizer) ctx).observe(g, me, 0, false, null);
                final byte[] p = RlWire.encodeDecide(RlSearch.leafFrame(o, uid, g.getRegisteredPlayers().indexOf(me),
                        g.getPhaseHandler().getTurn()));
                s.featNanos += System.nanoTime() - t;
                return p;
            }

            @Override
            public void onOrder(final Object ctx, final Game g, final Player me, final List<Card> cards) {
                ((RlFeaturizer) ctx).knowledge().onLook(g, me, cards, ZoneType.Library);
            }

            @Override
            public forge.ai.simulation.ForcedWinSolver.Screen screen(final Object ctx, final Game g, final Player me) {
                if (cfg.screenNodes <= 0 || cfg.solverCfg == null) {
                    return null;
                }
                final RlKnowledge k = ((RlFeaturizer) ctx).knowledge();
                final int st = k.seatOf(me);
                final List<Integer> top = new ArrayList<>();
                for (Card c : me.getCardsIn(ZoneType.Library)) {
                    if (!k.knows(st, c)) {
                        break;
                    }
                    top.add(c.getId());
                }
                if (screenReader == null) {
                    screenReader = forge.ai.simulation.ForcedWinSolver.newSharedReader();
                }
                final forge.ai.simulation.ForcedWinSolver sol = forge.ai.simulation.ForcedWinSolver.withReader(me,
                        cfg.solverCfg, screenReader);
                if (cfg.reachAlpha > 0) {
                    // solver-distill-cc-1109: the reach over our next unknown draw; lines = 1 when it is above 0
                    final forge.ai.simulation.ForcedWinSolver.ReachOpts ro = new forge.ai.simulation.ForcedWinSolver.ReachOpts();
                    ro.nodes = cfg.screenNodes;
                    ro.ms = cfg.screenMs;
                    ro.maxNames = cfg.reachMaxNames;
                    ro.window = true;
                    ro.nameNodes = cfg.reachNameNodes;
                    ro.nameOnePass = cfg.reachOnePass;
                    ro.skipCapped = cfg.reachSkipCapped;
                    ro.sig = cfg.reachSig;
                    final forge.ai.simulation.ForcedWinSolver.Reach r = sol.reach(g, top, ro);
                    final forge.ai.simulation.ForcedWinSolver.Screen sc = new forge.ai.simulation.ForcedWinSolver.Screen();
                    sc.failed = r.failed;
                    sc.reach = r.p;
                    sc.lines = r.direct ? 1 : 0;
                    sc.nodes = r.screens;
                    sc.nanos = r.nanos;
                    if (cfg.gfHorizon > 0 && !r.failed && !(r.p >= 1.0)) {
                        // GOLDFISH: several turns, sampled draws; one seed per ask (game, turn, phase), so every option
                        // of a menu is planned over the same draws
                        final forge.ai.simulation.ForcedWinSolver.GoldfishOpts go = new forge.ai.simulation.ForcedWinSolver.GoldfishOpts();
                        go.horizon = cfg.gfHorizon;
                        go.samples = cfg.gfSamples;
                        go.nodes = cfg.gfNodes;
                        go.extra = cfg.gfExtra;
                        go.ms = cfg.gfMs;
                        go.gamma = cfg.gfGamma;
                        go.active = cfg.gfActive;
                        go.lambda = cfg.gfLambda;
                        go.lands = cfg.gfLands;
                        go.maxNodes = cfg.gfAskNodes <= 0 ? 0 : Math.max(cfg.gfNodes, cfg.gfAskNodes / Math.max(1, s.menuOptions));
                        go.seed = uid * 0x9E3779B97F4A7C15L + g.getPhaseHandler().getTurn() * 64L
                                + g.getPhaseHandler().getPhase().ordinal();
                        final forge.ai.simulation.ForcedWinSolver.Goldfish gf = sol.goldfish(g, top, go);
                        sc.nodes += gf.screens;
                        sc.nanos += gf.nanos;
                        sc.gfNodes = gf.nodes;
                        sc.gfScreens = gf.screens;
                        sc.gfCut = gf.cut;
                        if (!gf.failed && !Double.isNaN(gf.g)) {
                            sc.gf = gf.g;
                            sc.gfTurn = gf.bestTurn;
                            if (Double.isNaN(sc.reach) || gf.g > sc.reach) {
                                sc.reach = gf.g;
                            }
                        }
                    }
                    return sc;
                }
                return sol.screen(g, top, cfg.screenNodes, cfg.screenMs);
            }
        };
    }

    /** The opponent's unknown hand cards trade places with unknown library cards below the top. Positions stay. */
    private static void swapOpponentHidden(final Game g, final Player me, final RlKnowledge k) {
        final int seat = k.seatOf(me);
        for (Player o : me.getOpponents()) {
            final List<Card> hand = new ArrayList<>();
            for (Card c : o.getCardsIn(ZoneType.Hand)) {
                if (!k.knows(seat, c)) {
                    hand.add(c);
                }
            }
            final List<Card> lib = new ArrayList<>(new forge.game.card.CardCollection(o.getCardsIn(ZoneType.Library)));
            int at = lib.size() - 1;
            for (Card h : hand) {
                while (at > 0 && k.knows(seat, lib.get(at))) {
                    at--;
                }
                if (at <= 0) {
                    break;
                }
                final Card l = lib.get(at);
                g.getAction().moveToLibrary(h, at, null);
                g.getAction().moveTo(ZoneType.Hand, l, null, null);
                at--;
            }
        }
    }

    /**
     * sdcc-leak-1111: our own library cards whose place the seat does not know, in reverse order among their own
     * positions (the cards it knows stay). A frame that changes read the real order of our library: an answer replayed
     * on a copy of the real game drew from it (a resolution that draws before it asks is replayed whole: Brainstorm's
     * put-backs were scored with three more real cards in hand).
     */
    private static void swapOwnHidden(final Game g, final Player me, final RlKnowledge k) {
        final int seat = k.seatOf(me);
        final List<Card> lib = new ArrayList<>(new forge.game.card.CardCollection(me.getCardsIn(ZoneType.Library)));
        final List<Integer> at = new ArrayList<>();
        for (int i = 0; i < lib.size(); i++) {
            if (seat < 0 || !k.knows(seat, lib.get(i))) {
                at.add(i);
            }
        }
        if (at.size() < 2) {
            return;
        }
        final List<Card> out = new ArrayList<>(lib);
        for (int j = 0; j < at.size(); j++) {
            out.set(at.get(j), lib.get(at.get(at.size() - 1 - j)));
        }
        me.getZone(ZoneType.Library).setCards(out);
    }

    /** sdcc-leak-1111: the own-library guard is on with the solver's hideOwnLibrary (and not hiddenOrderLegacy). */
    private boolean ownGuard() {
        return cfg.solverCfg != null && cfg.solverCfg.hideHidden();
    }

    /** The ask's options with their frames; values are the caller's batched pass ({@link #choose}). */
    public Scored enumerate(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy,
            final RlKnowledge know, final long seed, final SpellAbility askSa) {
        final Scored s = new Scored();
        final long t0 = System.nanoTime();
        screenReader = null; // one reader per ask: its probes are of this position's copies
        final int seat = g.getRegisteredPlayers().indexOf(me);
        final ChoiceApply.Frames fr = frames(know, cfg.searchedTop ? 1 << seat : 0, s);
        final long deadline = cfg.budgetMs > 0 ? (forge.game.WorkClock.active() ? forge.game.WorkClock.nanoTime() : t0) + cfg.budgetMs * 1_000_000L : Long.MAX_VALUE;
        if (m.family == RlSchema.F_CARDS) {
            cards(g, me, m, greedy, seed, fr, s, deadline);
        } else if (m.family == RlSchema.F_MULLIGAN) {
            mulligan(g, me, m, greedy, seed, fr, s, deadline);
        } else if (m.family == RlSchema.F_MULLIGAN_BOTTOM) {
            bottoms(g, me, m, greedy, seed, fr, s, deadline);
        } else {
            s.unsupported = "family";
        }
        for (Option o : s.options) {
            s.frames += o.frames.size();
            if (o.fail != null) {
                s.failed++;
            }
            if (s.unsupported == null && ("ownHidden".equals(o.fail) || "oppHidden".equals(o.fail))) {
                // sdcc-leak-1111: one option's replay read a card the seat did not know: the whole menu is the policy's
                s.unsupported = o.fail;
            }
        }
        s.totalNanos = System.nanoTime() - t0;
        return s;
    }

    private void cards(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy, final long seed,
            final ChoiceApply.Frames fr, final Scored s, final long deadline) {
        if (!ChoiceWindow.singlePick(m)) {
            s.unsupported = "multiPick";
            return;
        }
        s.unsupported = ChoiceApply.unsupported(g, null);
        if (s.unsupported != null) {
            return;
        }
        if (cfg.guard) {
            s.unsupported = hiddenGuard(g, me, m, seed, fr, deadline);
            if ("multiAnswer".equals(s.unsupported) && cfg.pile && cfg.solverCfg != null) {
                piles(g, me, m, seed, fr, s);
                return;
            }
            if ("multiAnswer".equals(s.unsupported) && cfg.seqs > 0) {
                seqs(g, me, m, greedy, seed, fr, s, deadline);
                return;
            }
            if (s.unsupported != null) {
                return;
            }
        }
        // one option per distinct card (name, zone, controller: two Islands are one answer)
        final Map<String, Integer> byKey = new LinkedHashMap<>();
        for (int i = 0; i < m.C(); i++) {
            if (m.cands.get(i).kind > 0 && m.cands.get(i).host != null) {
                byKey.putIfAbsent(ChoiceWindow.looseKey(m, i, me), i);
            }
        }
        final int own = greedy != null && greedy.length == 1 ? greedy[0] : -1;
        final String ownKey = own >= 0 && own < m.C() ? ChoiceWindow.looseKey(m, own, me) : null;
        if (ownKey != null) {
            byKey.put(ownKey, own); // the policy's own card stands for its name
        }
        s.menuOptions = byKey.size() + (m.minPick == 0 ? 1 : 0);
        final List<Integer> keep = cut(new ArrayList<>(byKey.values()), own, seed, s);
        if (m.minPick == 0 && (!cfg.noDecline || greedy != null && greedy.length == 0)) {
            final Option no = new Option();
            no.steps = new short[0];
            no.label = "decline";
            no.policy = greedy != null && greedy.length == 0;
            if (forge.game.WorkClock.nanoTime() < deadline) {
                run(g, me, Collections.singletonList(-1), seed, fr, no, s);
            } else {
                no.fail = "budget";
                s.overBudget = true;
            }
            s.options.add(no);
        }
        for (int i : keep) {
            final Option o = new Option();
            o.steps = new short[] {(short) i};
            o.label = m.cands.get(i).host.getName();
            o.policy = i == own;
            if (forge.game.WorkClock.nanoTime() >= deadline && !o.policy) {
                o.fail = "budget";
                s.overBudget = true;
            } else {
                run(g, me, Collections.singletonList(m.cands.get(i).host.getId()), seed, fr, o, s);
            }
            s.options.add(o);
        }
    }

    /**
     * Whether a copy can answer this ask honestly, from one option applied twice: "multiAnswer" if Forge AI would
     * answer further picks of the same resolution (an earlier pick is not replayed, and the tail is stream noise), or
     * "oppHidden" if the frame changes when the opponent's unknown hand cards are swapped with unknown library cards
     * (the opponent acts on hidden cards during the resolution). Null = fine. No card knowledge.
     */
    private String hiddenGuard(final Game g, final Player me, final RlCandidates.Menu m, final long seed,
            final ChoiceApply.Frames fr, final long deadline) {
        int first = -1;
        for (int i = 0; i < m.C() && first < 0; i++) {
            if (m.cands.get(i).kind > 0 && m.cands.get(i).host != null) {
                first = i;
            }
        }
        if (first < 0) {
            return null;
        }
        final List<Integer> pick = Collections.singletonList(m.cands.get(first).host.getId());
        final ChoiceApply.Result a = ChoiceApply.applyPicks(g, me, pick, seed, cfg.settleSteps, cfg.fidelity, fr);
        if (a.fail != null) {
            return null; // the options report their own failures
        }
        if (a.tailAsks > 0) {
            return "multiAnswer";
        }
        final boolean was = guardSwap;
        guardSwap = true;
        try {
            final ChoiceApply.Result b = ChoiceApply.applyPicks(g, me, pick, seed, cfg.settleSteps, cfg.fidelity, fr);
            if (b.fail == null && !Arrays.equals(a.payload, b.payload)) {
                return "oppHidden";
            }
        } finally {
            guardSwap = was;
        }
        if (ownGuard()) {
            // sdcc-leak-1111: the same check for our own library's unknown order
            guardSwapOwn = true;
            try {
                final ChoiceApply.Result c = ChoiceApply.applyPicks(g, me, pick, seed, cfg.settleSteps, cfg.fidelity, fr);
                if (c.fail == null && !Arrays.equals(a.payload, c.payload)) {
                    return "ownHidden";
                }
            } finally {
                guardSwapOwn = false;
            }
        }
        return null;
    }

    private int seqSeen = -1;

    /** solver-distill-cc-1109 ({@link Config#seqs}): every ordered sequence of distinct menu cards, applied whole. */
    private void seqs(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy, final long seed,
            final ChoiceApply.Frames fr, final Scored s, final long deadline) {
        final SpellAbility top = g.getStack().peekAbility();
        final int key = top == null ? -1 : System.identityHashCode(top);
        if (key == seqSeen) {
            s.unsupported = "seqLaterPick";
            return;
        }
        seqSeen = key;
        // distinct cards by name (two Islands are one answer; a name twice only when two copies are on the menu)
        final Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < m.C(); i++) {
            if (m.cands.get(i).kind > 0 && m.cands.get(i).host != null) {
                byKey.computeIfAbsent(ChoiceWindow.looseKey(m, i, me), x -> new ArrayList<>()).add(i);
            }
        }
        final List<List<Integer>> seqs = new ArrayList<>();
        final List<String> keys = new ArrayList<>(byKey.keySet());
        final int k = cfg.seqs;
        seqBuild(keys, byKey, k, new ArrayList<>(), seqs, Math.max(1, cfg.cap));
        if (seqs.isEmpty()) {
            s.unsupported = "seqNone";
            return;
        }
        // the honest-copy checks on the first full sequence: no Forge AI tail, no read of the opponent's hidden cards
        final List<Integer> probe = ids(m, seqs.get(0));
        final ChoiceApply.Result a = ChoiceApply.applyPicks(g, me, probe, probe, seed, cfg.settleSteps, cfg.fidelity, fr);
        if (a.fail == null && a.tailAsks > 0) {
            s.unsupported = "seqTail";
            return;
        }
        final boolean was = guardSwap;
        guardSwap = true;
        try {
            final ChoiceApply.Result b = ChoiceApply.applyPicks(g, me, probe, probe, seed, cfg.settleSteps, cfg.fidelity, fr);
            if (a.fail == null && b.fail == null && !Arrays.equals(a.payload, b.payload)) {
                s.unsupported = "oppHidden";
                return;
            }
        } finally {
            guardSwap = was;
        }
        if (ownGuard()) {
            // sdcc-leak-1111: a sequence replayed on a copy that drew from our real library is not scored
            guardSwapOwn = true;
            try {
                final ChoiceApply.Result c = ChoiceApply.applyPicks(g, me, probe, probe, seed, cfg.settleSteps, cfg.fidelity, fr);
                if (a.fail == null && c.fail == null && !Arrays.equals(a.payload, c.payload)) {
                    s.unsupported = "ownHidden";
                    return;
                }
            } finally {
                guardSwapOwn = false;
            }
        }
        s.unsupported = null;
        s.menuOptions = seqs.size();
        final int own = greedy != null && greedy.length == 1 ? greedy[0] : -1;
        // the policy's own answer: its first pick, Forge AI on the rest (the seat's own later picks are not known here)
        if (own >= 0 && own < m.C() && m.cands.get(own).host != null) {
            final Option o = new Option();
            o.steps = new short[] {(short) own};
            o.label = "policy:" + m.cands.get(own).host.getName();
            o.policy = true;
            run(g, me, Collections.singletonList(m.cands.get(own).host.getId()), seed, fr, o, s);
            s.options.add(o);
        }
        for (List<Integer> sq : seqs) {
            final Option o = new Option();
            o.seq = true;
            o.steps = new short[] {sq.get(0).shortValue()};
            o.pileIds = ids(m, sq);
            final StringBuilder lb = new StringBuilder("seq:");
            for (int i : sq) {
                lb.append(lb.length() == 4 ? "" : ">").append(m.cands.get(i).host.getName());
            }
            o.label = lb.toString();
            if (forge.game.WorkClock.nanoTime() >= deadline) {
                o.fail = "budget";
                s.overBudget = true;
            } else {
                note(ChoiceApply.applyPicks(g, me, o.pileIds, o.pileIds, seed, cfg.settleSteps, cfg.fidelity, fr), o, s, 0);
            }
            s.options.add(o);
        }
    }

    private static List<Integer> ids(final RlCandidates.Menu m, final List<Integer> idx) {
        final List<Integer> out = new ArrayList<>();
        for (int i : idx) {
            out.add(m.cands.get(i).host.getId());
        }
        return out;
    }

    private static void seqBuild(final List<String> keys, final Map<String, List<Integer>> byKey, final int k,
            final List<Integer> cur, final List<List<Integer>> out, final int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (cur.size() == k) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (String key : keys) {
            for (int i : byKey.get(key)) {
                if (!cur.contains(i)) {
                    cur.add(i);
                    seqBuild(keys, byKey, k, cur, out, cap);
                    cur.remove(cur.size() - 1);
                    break; // one card per name at each place
                }
            }
        }
    }

    /** The planner's piles for the resolving spell, each applied and screened; none: stays refused, the policy picks. */
    private void piles(final Game g, final Player me, final RlCandidates.Menu m, final long seed,
            final ChoiceApply.Frames fr, final Scored s) {
        final SpellAbility top = g.getStack().peekAbility();
        final int key = top == null ? -1 : System.identityHashCode(top);
        if (key == pileSeen) {
            s.unsupported = "pileLaterPick";
            return;
        }
        pileSeen = key;
        final List<List<Integer>> ps = ChoiceApply.pileChoices(g, me, cfg.solverCfg, cfg.pileNodes, cfg.pileMs, cfg.pileTop, seed);
        s.menuOptions = ps.size();
        if (ps.isEmpty()) {
            s.unsupported = "noPile";
            return;
        }
        s.unsupported = null;
        final Map<Integer, Integer> menuOf = new java.util.HashMap<>();
        for (int i = 0; i < m.C(); i++) {
            if (m.cands.get(i).kind > 0 && m.cands.get(i).host != null) {
                menuOf.put(m.cands.get(i).host.getId(), i);
            }
        }
        for (List<Integer> p : ps) {
            final Option o = new Option();
            o.pileIds = p;
            final StringBuilder lb = new StringBuilder("pile:");
            for (int id : p) {
                final Card c = g.findById(id);
                lb.append(lb.length() == 5 ? "" : ">").append(c == null ? "?" + id : c.getName());
            }
            o.label = lb.toString();
            final Integer first = p.isEmpty() ? null : menuOf.get(p.get(0));
            if (first == null) {
                o.fail = "notOnMenu";
                o.steps = new short[0];
            } else {
                o.steps = new short[] {first.shortValue()};
                note(ChoiceApply.applyPicks(g, me, p, p, seed, cfg.settleSteps, cfg.fidelity, fr), o, s, 0);
            }
            s.options.add(o);
        }
    }

    private void run(final Game g, final Player me, final List<Integer> picks, final long seed,
            final ChoiceApply.Frames fr, final Option o, final Scored s) {
        final ChoiceApply.Result r = ChoiceApply.applyPicks(g, me, picks, seed, cfg.settleSteps, cfg.fidelity, fr);
        note(r, o, s, 0);
    }

    private static void note(final ChoiceApply.Result r, final Option o, final Scored s, final int group) {
        s.copyNanos += r.copyNanos;
        s.runNanos += r.runNanos;
        o.tail = Math.max(o.tail, r.tailAsks);
        if (r.screen != null) {
            o.screen = r.screen;
        }
        if (r.fail != null) {
            o.fail = r.fail;
        } else if (r.hiddenOwn > 0 || r.hiddenOpp > 0) {
            o.fail = r.hiddenOwn > 0 ? "ownHidden" : "oppHidden"; // sdcc-leak-1111 (Frames#hidden only)
        } else if (!Double.isNaN(r.terminal)) {
            o.terminal = r.terminal;
        } else if (r.payload != null) {
            o.frames.add(r.payload);
            o.groups.add(group);
        }
    }

    /** Menus above the cap: the policy's answer, the menu's first, then seeded random others. */
    private List<Integer> cut(final List<Integer> all, final int own, final long seed, final Scored s) {
        final int room = cfg.cap + cfg.explore;
        if (all.size() <= room) {
            return all;
        }
        final List<Integer> out = new ArrayList<>();
        if (own >= 0 && all.contains(own)) {
            out.add(own);
        }
        for (int i : all) {
            if (out.size() >= cfg.cap) {
                break;
            }
            if (!out.contains(i)) {
                out.add(i);
            }
        }
        final List<Integer> rest = new ArrayList<>(all);
        rest.removeAll(out);
        Collections.shuffle(rest, new Random(LookaheadSearch.mix(seed, 0xE9)));
        out.addAll(rest.subList(0, Math.min(cfg.explore, rest.size())));
        s.cut = all.size() - out.size();
        return out;
    }

    /**
     * MULLIGAN: keep is this hand; mulligan is the mean over new hands (groups) of the best bottoms of that hand. The
     * bottoms taken are every set of k from the new seven, with worlds cut so the frames stay at {@code MULL_FRAMES}.
     */
    private void mulligan(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy, final long seed,
            final ChoiceApply.Frames fr, final Scored s, final long deadline) {
        s.menuOptions = m.C();
        final int handNow = me.getCardsIn(ZoneType.Hand).size();
        final int k = Math.max(0, 7 - handNow) + 1;
        final List<int[]> sets = new ArrayList<>();
        if (k < 7) {
            combos(7, k, 0, new int[k], 0, sets, MULL_FRAMES);
        }
        final int worlds = sets.isEmpty() ? 0 : Math.max(1, Math.min(cfg.mullWorlds, MULL_FRAMES / sets.size()));
        for (int i = 0; i < m.C(); i++) {
            final boolean keep = m.cands.get(i).kind == RlSchema.K_KEEP;
            final Option o = new Option();
            o.steps = new short[] {(short) i};
            o.label = keep ? "keep" : "mulligan";
            o.policy = greedy != null && greedy.length == 1 && greedy[0] == i;
            if (keep) {
                note(ChoiceApply.applyHand(g, me, false, null, seed, cfg.fidelity, fr), o, s, 0);
            } else {
                for (int w = 0; w < worlds; w++) {
                    if (forge.game.WorkClock.nanoTime() >= deadline) {
                        s.overBudget = true;
                        break;
                    }
                    final long ws = LookaheadSearch.mix(seed, 0x4D00 + w);
                    for (int[] x : sets) {
                        final List<Integer> idx = new ArrayList<>();
                        for (int j : x) {
                            idx.add(j);
                        }
                        note(ChoiceApply.applyHand(g, me, true, idx, ws, cfg.fidelity, fr), o, s, w);
                    }
                }
            }
            s.options.add(o);
        }
    }

    /** MULLIGAN_BOTTOM: every set of k cards (at most {@code MULL_FRAMES} sets). */
    private void bottoms(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy, final long seed,
            final ChoiceApply.Frames fr, final Scored s, final long deadline) {
        final int k = m.minPick;
        final int n = m.C();
        final List<int[]> sets = new ArrayList<>();
        combos(n, k, 0, new int[k], 0, sets, MULL_FRAMES);
        s.menuOptions = sets.size();
        for (int[] x : sets) {
            final Option o = new Option();
            final short[] st = new short[k];
            final List<Integer> idx = new ArrayList<>();
            final StringBuilder lb = new StringBuilder();
            for (int j = 0; j < k; j++) {
                st[j] = (short) x[j];
                idx.add(x[j]);
                lb.append(j == 0 ? "" : "+").append(m.cands.get(x[j]).host.getName());
            }
            o.steps = st;
            o.label = lb.toString();
            o.policy = greedy != null && sameSet(greedy, st);
            if (forge.game.WorkClock.nanoTime() >= deadline && !o.policy) {
                o.fail = "budget";
                s.overBudget = true;
            } else {
                note(ChoiceApply.applyHand(g, me, false, idx, seed, cfg.fidelity, fr), o, s, 0);
            }
            s.options.add(o);
        }
    }

    private static boolean sameSet(final short[] a, final short[] b) {
        final short[] x = a.clone(), y = b.clone();
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    private static void combos(final int n, final int k, final int from, final int[] cur, final int at,
            final List<int[]> out, final int stop) {
        if (out.size() >= stop) {
            return;
        }
        if (at == k) {
            out.add(cur.clone());
            return;
        }
        for (int i = from; i < n; i++) {
            cur[at] = i;
            combos(n, k, i + 1, cur, at + 1, out, stop);
        }
    }

    /** A chosen pile takes over this resolution's further picks and its order ask. */
    public void adoptPile(final Game g, final Option o) {
        pilePlan = o.pileIds;
        pileKey = System.identityHashCode(g.getStack().peekAbility());
        pileNext = 1;
    }

    public boolean pileActive(final Game g) {
        return pilePlan != null && !g.getStack().isEmpty() && g.getStack().isResolving()
                && System.identityHashCode(g.getStack().peekAbility()) == pileKey;
    }

    /** The plan's answer to this ask of the resolution in progress (CARDS: next pick; ORDER: top first), or null. */
    public short[] pileAnswer(final Game g, final RlCandidates.Menu m) {
        if (!pileActive(g)) {
            pilePlan = null;
            return null;
        }
        if (m.family == RlSchema.F_CARDS && pileNext < pilePlan.size()) {
            final int id = pilePlan.get(pileNext++);
            for (int i = 0; i < m.C(); i++) {
                if (m.cands.get(i).kind > 0 && m.cands.get(i).host != null && m.cands.get(i).host.getId() == id) {
                    return new short[] {(short) i};
                }
            }
            pilePlan = null;
            return null;
        }
        if (m.family == RlSchema.F_ORDER) {
            final short[] out = new short[m.C()];
            int at = 0;
            final boolean[] used = new boolean[m.C()];
            for (int id : pilePlan) {
                for (int i = 0; i < m.C(); i++) {
                    if (!used[i] && m.cands.get(i).host != null && m.cands.get(i).host.getId() == id) {
                        out[at++] = (short) i;
                        used[i] = true;
                    }
                }
            }
            for (int i = 0; i < m.C(); i++) {
                if (!used[i]) {
                    out[at++] = (short) i;
                }
            }
            pilePlan = null;
            return out;
        }
        return null;
    }

    /**
     * Set each option's value from the batch's values (P(win) in [0, 1], in the options' frame order): the mean over
     * groups of each group's best. Returns the steps to play instead of {@code greedy}, or null to keep it.
     */
    public short[] choose(final Scored s, final double[] v, final short[] greedy) {
        int at = 0;
        Option best = null;
        Option own = null;
        for (Option o : s.options) {
            final Map<Integer, Double> grp = new LinkedHashMap<>();
            for (int j = 0; j < o.frames.size(); j++) {
                grp.merge(o.groups.get(j), v[at++], Math::max);
            }
            if (!Double.isNaN(o.terminal)) {
                o.value = o.terminal;
            } else if (!grp.isEmpty()) {
                double sum = 0;
                for (double x : grp.values()) {
                    sum += x;
                }
                o.value = sum / grp.size();
            }
            if (cfg.reachAlpha > 0 && !Double.isNaN(o.value) && o.screen != null && !Double.isNaN(o.screen.reach)) {
                final double a = cfg.reachAlpha * o.screen.reach;
                o.value = a + (1 - a) * o.value; // solver-distill-cc-1109
            }
            if (o.policy) {
                own = o;
            }
            if (!Double.isNaN(o.value) && (best == null || (cfg.reachAlpha > 0 ? o.value > best.value : better(o, best)))) {
                best = o;
            }
        }
        s.chosen = best;
        if (best != null && best.pileIds != null && !best.seq) {
            return best.screen != null && best.screen.lines > 0 ? best.steps : null;
        }
        if (cfg.reachAlpha > 0) {
            return best == null || own == null || Double.isNaN(own.value) || best == own
                    || best.value <= own.value + cfg.margin ? null : best.steps;
        }
        if (best == null || own == null || Double.isNaN(own.value) || best == own
                || !(own.screen != null && best.screen != null && best.screen.lines > 0 && own.screen.lines == 0)
                && best.value <= own.value + cfg.margin) {
            return null;
        }
        return best.steps;
    }

    /** A planner line outranks no line (when the screen ran); then the value. */
    private static boolean better(final Option a, final Option b) {
        final boolean la = a.screen != null && a.screen.lines > 0, lb = b.screen != null && b.screen.lines > 0;
        return la != lb ? la : a.value > b.value;
    }

    public List<byte[]> payloads(final Scored s) {
        final List<byte[]> out = new ArrayList<>();
        for (Option o : s.options) {
            out.addAll(o.frames);
        }
        return out;
    }

    public JsonObject row(final Scored s, final String src) {
        final JsonObject r = new JsonObject();
        r.addProperty("src", src);
        r.addProperty("menu", s.menuOptions);
        r.addProperty("cut", s.cut);
        r.addProperty("failed", s.failed);
        r.addProperty("frames", s.frames);
        r.addProperty("ms", Math.round(s.totalNanos / 1e4) / 100.0);
        r.addProperty("copy_ms", Math.round(s.copyNanos / 1e4) / 100.0);
        r.addProperty("run_ms", Math.round(s.runNanos / 1e4) / 100.0);
        r.addProperty("feat_ms", Math.round(s.featNanos / 1e4) / 100.0);
        r.addProperty("over_budget", s.overBudget);
        if (s.unsupported != null) {
            r.addProperty("unsupported", s.unsupported);
        }
        final JsonArray a = new JsonArray();
        for (Option o : s.options) {
            final JsonObject x = new JsonObject();
            x.addProperty("o", o.label);
            if (o.policy) x.addProperty("policy", true);
            if (!Double.isNaN(o.value)) x.addProperty("v", Math.round(o.value * 1e4) / 1e4);
            if (!Double.isNaN(o.terminal)) x.addProperty("term", o.terminal);
            if (o.fail != null) x.addProperty("fail", o.fail);
            x.addProperty("n", o.frames.size());
            if (o.tail > 0) x.addProperty("tail", o.tail);
            if (o.screen != null) {
                x.addProperty("lines", o.screen.lines);
                if (!Double.isNaN(o.screen.gf)) {
                    x.addProperty("gf", Math.round(o.screen.gf * 1000) / 1000.0);
                    x.addProperty("gf_turn", o.screen.gfTurn);
                }
                if (o.screen.gfScreens > 0) {
                    x.addProperty("gf_nodes", o.screen.gfNodes);
                    x.addProperty("gf_screens", o.screen.gfScreens);
                    if (o.screen.gfCut) {
                        x.addProperty("gf_cut", true);
                    }
                }
                if (!Double.isNaN(o.screen.reach)) {
                    x.addProperty("reach", Math.round(o.screen.reach * 1000) / 1000.0);
                }
                x.addProperty("plan_nodes", o.screen.nodes);
                x.addProperty("plan_ms", Math.round(o.screen.nanos / 1e4) / 100.0);
                if (o.screen.exhausted) x.addProperty("plan_exh", true);
                if (o.screen.failed) x.addProperty("plan_fail", String.valueOf(o.screen.best));
                if (o.screen.bestTurn >= 0) {
                    x.addProperty("win_turn", o.screen.bestTurn);
                    x.addProperty("win_steps", o.screen.bestSteps);
                }
            }
            a.add(x);
        }
        r.add("options", a);
        return r;
    }

    /** The asking ability's source name, for rows (null when the stack has none). */
    public static String srcOf(final Game g) {
        try {
            if (!g.getStack().isEmpty()) {
                final Card c = g.getStack().peekAbility().getHostCard();
                return c == null ? null : c.getName();
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }
}
