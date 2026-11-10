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
        /**
         * choice-search-1109 (false = off, the default, and then nothing changes): the line-existence rule. Each applied
         * option's position is screened by the planner (this turn, then by our next turn); an option with a planner line
         * gets the honest win check ({@link #check}: belief worlds, hidden own library, the adversary). The option with
         * the highest verified p_win is played, ties broken by the leaf value; with no verified line anywhere the policy's
         * answer stands. The value alone never moves the answer.
         */
        public boolean lineOnly = false;
        /**
         * choice-search-1109 (null = no check): the honest win check's label spec ({@link
         * forge.ai.simulation.HonestLabel.Spec}; belief cube keys default to the search spec's).
         */
        public JsonObject check = null;
        /** choice-search-1109: honest checks per ask at most (the policy's answer is applied first). */
        public int checkTop = 3;
        /** choice-search-1109: options per multi-pick ask at most (the policy's set, then one-card swaps of it). */
        public int subsetCap = 24;
        /**
         * choice-search-1109 pass 3 (0 = off, the default, and then nothing changes): with lineOnly, when no option has a
         * verified line, the relaxed-planning distance of up to {@code relaxTop} options (the policy's first, then by value):
         * on each option's position, R = 1..relax generic relaxations (an extra untapped land of the seat's own, the one
         * making the most colors; a card drawn from the seat's unknown library, {@code relaxDraws} samples) until the
         * planner finds a line. The option with the fewest is played, ties to the value, then to the policy's answer; with
         * no relaxed line anywhere the policy's answer stands. {@code relaxMs}: the pass's budget per ask (work clock).
         */
        public int relax = 0;
        public int relaxTop = 6;
        public long relaxMs = 3000;
        public int relaxDraws = 2;

        static final Set<String> KEYS = new java.util.TreeSet<>(Arrays.asList("families", "cap", "explore", "mullWorlds",
                "settleSteps", "margin", "budgetMs", "searchedTop", "fidelity", "shadow", "screenNodes", "screenMs", "guard", "pile", "pileTop", "pileNodes", "pileMs",
                "lineOnly", "check", "checkTop", "subsetCap", "relax", "relaxTop", "relaxMs", "relaxDraws"));

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
                    if (i != RlSchema.F_CARDS && i != RlSchema.F_MULLIGAN && i != RlSchema.F_MULLIGAN_BOTTOM
                            && i != RlSchema.F_DISCARD_FROM && i != RlSchema.F_COST_CARDS) {
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
            if (o.has("budgetMs")) c.budgetMs = o.get("budgetMs").getAsLong();
            if (o.has("searchedTop")) c.searchedTop = o.get("searchedTop").getAsBoolean();
            if (o.has("fidelity")) c.fidelity = o.get("fidelity").getAsInt();
            if (o.has("shadow")) c.shadow = o.get("shadow").getAsBoolean();
            if (o.has("screenNodes")) c.screenNodes = o.get("screenNodes").getAsInt();
            if (o.has("screenMs")) c.screenMs = o.get("screenMs").getAsLong();
            if (o.has("guard")) c.guard = o.get("guard").getAsBoolean();
            if (o.has("pile")) c.pile = o.get("pile").getAsBoolean();
            if (o.has("pileTop")) c.pileTop = o.get("pileTop").getAsInt();
            if (o.has("pileNodes")) c.pileNodes = o.get("pileNodes").getAsInt();
            if (o.has("pileMs")) c.pileMs = o.get("pileMs").getAsLong();
            if (o.has("lineOnly")) c.lineOnly = o.get("lineOnly").getAsBoolean();
            if (o.has("check") && !o.get("check").isJsonNull()) c.check = o.getAsJsonObject("check");
            if (o.has("checkTop")) c.checkTop = o.get("checkTop").getAsInt();
            if (o.has("subsetCap")) c.subsetCap = o.get("subsetCap").getAsInt();
            if (o.has("relax")) c.relax = o.get("relax").getAsInt();
            if (o.has("relaxTop")) c.relaxTop = o.get("relaxTop").getAsInt();
            if (o.has("relaxMs")) c.relaxMs = o.get("relaxMs").getAsLong();
            if (o.has("relaxDraws")) c.relaxDraws = o.get("relaxDraws").getAsInt();
            if (c.relax < 0 || c.relax > 6 || c.relaxTop < 1 || c.relaxDraws < 1) {
                throw new IllegalArgumentException("choiceScorer: relax in [0, 6], relaxTop >= 1, relaxDraws >= 1");
            }
            if (c.relax > 0 && !c.lineOnly) {
                throw new IllegalArgumentException("choiceScorer: relax needs lineOnly");
            }
            if (c.checkTop < 0 || c.subsetCap < 1) {
                throw new IllegalArgumentException("choiceScorer: checkTop >= 0, subsetCap >= 1");
            }
            if (c.lineOnly && c.screenNodes <= 0) {
                throw new IllegalArgumentException("choiceScorer: lineOnly needs the planner screen (screenNodes > 0)");
            }
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
            o.addProperty("budgetMs", budgetMs);
            o.addProperty("searchedTop", searchedTop);
            o.addProperty("fidelity", fidelity);
            o.addProperty("shadow", shadow);
            o.addProperty("screenNodes", screenNodes);
            o.addProperty("screenMs", screenMs);
            o.addProperty("guard", guard);
            o.addProperty("pile", pile);
            o.addProperty("pileTop", pileTop);
            o.addProperty("pileNodes", pileNodes);
            o.addProperty("pileMs", pileMs);
            if (lineOnly || check != null) {
                // choice-search-1109: written only when on (an old config prints as before)
                o.addProperty("lineOnly", lineOnly);
                if (check != null) o.add("check", check);
                o.addProperty("checkTop", checkTop);
                o.addProperty("subsetCap", subsetCap);
            }
            if (relax > 0) {
                // choice-search-1109 pass 3: written only when on
                o.addProperty("relax", relax);
                o.addProperty("relaxTop", relaxTop);
                o.addProperty("relaxMs", relaxMs);
                o.addProperty("relaxDraws", relaxDraws);
            }
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
        /** choice-search-1109 pass 3: this option's first-pass apply time (copy, settle, screen), the relax order. */
        public long passNanos;
        /** A pile option: the cards top first. */
        public List<Integer> pileIds;
    }

    /** One scored ask. */
    public static final class Scored {
        public final List<Option> options = new ArrayList<>();
        public String unsupported;
        public int menuOptions, cut, failed, frames;
        public long copyNanos, runNanos, featNanos, totalNanos;
        public boolean overBudget;
        public Option chosen;
        /** choice-search-1109: honest checks run, whether one proved a win (no further check), and their time. */
        public int checks;
        public boolean proven;
        public long checkNanos;
        /** choice-search-1109 pass 3: options the relax pass screened, its time, and whether it moved the answer. */
        public int relaxed;
        public long relaxNanos;
        public boolean relaxDeparted;
    }

    /** Frames at most for one opening-hand ask. */
    static final int MULL_FRAMES = 120;

    private boolean guardSwap = false;
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
                k.attach();
                final RlFeaturizer f = new RlFeaturizer(index);
                f.setVersion(obsVersion);
                f.setKnowledge(k);
                return f;
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
                final forge.ai.simulation.ForcedWinSolver.Screen sc = forge.ai.simulation.ForcedWinSolver.withReader(me,
                        cfg.solverCfg, screenReader).screen(g, top, cfg.screenNodes, cfg.screenMs);
                if (relaxMode && sc != null && sc.lines == 0 && !sc.failed) {
                    // choice-search-1109 pass 3: the relaxed-planning distance of this (throwaway) leaf copy, up to the best
                    // distance found so far in this ask (a farther option cannot win), within the pass's budget
                    if (forge.game.WorkClock.nanoTime() <= relaxDeadline) {
                        relaxDistance(g, me, c -> k.knows(st, c), top, sc, Math.min(cfg.relax, relaxBest));
                        if (sc.relaxR >= 0 && sc.relaxR != Integer.MAX_VALUE) {
                            relaxBest = Math.min(relaxBest, sc.relaxR);
                        }
                    }
                    return sc;
                }
                if (sc != null && sc.lines > 0 && checker != null && !noCheck && s.checks < cfg.checkTop && !s.proven) {
                    // choice-search-1109: the planner saw a line here; the honest win check prices it over belief worlds
                    final java.util.Set<Integer> known = new java.util.HashSet<>();
                    for (Card c : me.getCardsIn(ZoneType.Library)) {
                        if (k.knows(st, c)) {
                            known.add(c.getId());
                        }
                    }
                    final long t = System.nanoTime();
                    final forge.ai.simulation.HonestLabel.Result r = checker.label(g, me, curKey, known);
                    s.checkNanos += System.nanoTime() - t;
                    s.checks++;
                    sc.pWin = r.pWin;
                    sc.check = r.label;
                    sc.checkGate = r.gate;
                    sc.checkWorlds = r.worldsWin;
                    sc.checkMs = r.ms;
                    if ("win".equals(r.label)) {
                        s.proven = true; // early exit: no later option is checked
                    }
                }
                return sc;
            }
        };
    }

    // ------------------------------------------------------------------ choice-search-1109 pass 3: relaxed-planning distance

    /** True while the relax pass re-applies options (their leaf screens compute the distance, no check). */
    private boolean relaxMode = false;
    private long relaxDeadline = Long.MAX_VALUE;
    /** The fewest relaxations an option of this ask needed so far (later options are searched no deeper). */
    private int relaxBest = Integer.MAX_VALUE;

    /** The trigger kinds silenced while a relaxation moves a card (as the near-miss edits do). */
    private static final forge.game.trigger.TriggerType[] QUIET = {forge.game.trigger.TriggerType.ChangesZone,
            forge.game.trigger.TriggerType.ChangesZoneAll, forge.game.trigger.TriggerType.Taps,
            forge.game.trigger.TriggerType.Untaps, forge.game.trigger.TriggerType.Drawn};

    /** Run {@code r} on {@code g} with QUIET triggers suppressed, then re-apply static abilities. */
    static void quietly(final Game g, final Runnable r) {
        final forge.game.trigger.TriggerHandler th = g.getTriggerHandler();
        final List<forge.game.trigger.TriggerType> added = new ArrayList<>();
        for (forge.game.trigger.TriggerType t : QUIET) {
            if (!th.isTriggerSuppressed(t)) {
                th.suppressMode(t);
                added.add(t);
            }
        }
        try {
            r.run();
        } finally {
            for (forge.game.trigger.TriggerType t : added) {
                th.clearSuppression(t);
            }
            g.getAction().checkStaticAbilities(false);
        }
    }

    /** The colors a land's mana abilities make (Any = 5); engine reads only. */
    static int colorsOf(final Card c) {
        final Set<Character> cs = new java.util.HashSet<>();
        for (SpellAbility sa : c.getManaAbilities()) {
            final String p = sa.getManaPart() == null ? "" : String.valueOf(sa.getManaPart().getOrigProduced());
            if (p.contains("Any")) {
                return 5;
            }
            for (char ch : p.toCharArray()) {
                if ("WUBRG".indexOf(ch) >= 0) {
                    cs.add(ch);
                }
            }
        }
        return cs.size();
    }

    /**
     * Pass 3 on a throwaway leaf copy: R = 1..relax; at each R first the draw relaxation on top of the R-1 lands added so
     * far (relaxDraws samples of a uniformly random card of the seat's unknown library, moved to hand and back), then one
     * more land (the seat's own off-battlefield land making the most colors: library first, then graveyard, then hand)
     * entering untapped. The first R whose screen finds a line is the distance; the share of draw samples with a line is
     * its p (1 for a land-only relaxation). No card names: zones, types and mana abilities only.
     */

    /**
     * Tests only: the relaxed-planning distance of {@code g}'s position for {@code me} under {@code cfg} (its solverCfg set),
     * {@code known} = the seat's known library cards. The position is edited (pass a throwaway game).
     */
    public static forge.ai.simulation.ForcedWinSolver.Screen relaxDistanceForTest(final Config cfg, final Game g, final Player me,
            final java.util.function.Predicate<Card> known, final long key) {
        final ChoiceScorer sc = new ChoiceScorer(cfg, null, 2, 1L);
        sc.curKey = key;
        sc.relaxDeadline = Long.MAX_VALUE;
        sc.screenReader = forge.ai.simulation.ForcedWinSolver.newSharedReader();
        final forge.ai.simulation.ForcedWinSolver.Screen out = new forge.ai.simulation.ForcedWinSolver.Screen();
        sc.relaxDistance(g, me, known, new ArrayList<>(), out, cfg.relax);
        return out;
    }

    private void relaxDistance(final Game g, final Player me, final java.util.function.Predicate<Card> known,
            final List<Integer> top, final forge.ai.simulation.ForcedWinSolver.Screen out, final int maxR) {
        out.relaxR = Integer.MAX_VALUE;
        out.relaxScreens = 0;
        final Random rng = new Random(LookaheadSearch.mix(curKey, 0x7E1AL));
        final List<Card> lands = new ArrayList<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Library, ZoneType.Graveyard, ZoneType.Hand}) {
            final List<Card> zl = new ArrayList<>();
            for (Card c : me.getCardsIn(z)) {
                if (c.isLand() && colorsOf(c) > 0) {
                    zl.add(c);
                }
            }
            zl.sort((a, b) -> a.getId() - b.getId());
            zl.sort((a, b) -> Integer.compare(colorsOf(b), colorsOf(a))); // stable: most colors first, then id
            lands.addAll(zl);
        }
        int added = 0;
        for (int r = 1; r <= maxR; r++) {
            if (forge.game.WorkClock.nanoTime() > relaxDeadline) {
                out.relaxHow = "budget";
                return;
            }
            // (a) L^(r-1) + one draw
            final List<Card> unknown = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                if (!known.test(c)) {
                    unknown.add(c);
                }
            }
            if (!unknown.isEmpty() && cfg.relaxDraws <= 0) {
                // relax-label-1110 (labeller only; relaxDraws <= 0): every distinct unknown card name, weighted by its
                // count, instead of sampled draws. p = the share of the unknown library whose draw gives a line.
                final java.util.Map<String, List<Card>> byName = new java.util.TreeMap<>();
                for (Card c : unknown) {
                    byName.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
                }
                double wHit = 0;
                for (List<Card> same : byName.values()) {
                    final Card c = same.get(0);
                    final int at = new ArrayList<>(me.getCardsIn(ZoneType.Library)).indexOf(c);
                    final Card[] inHand = new Card[1];
                    quietly(g, () -> inHand[0] = g.getAction().moveTo(ZoneType.Hand, c, null, forge.game.ability.AbilityKey.newMap()));
                    final forge.ai.simulation.ForcedWinSolver.Screen x = forge.ai.simulation.ForcedWinSolver.withReader(me,
                            cfg.solverCfg, screenReader).screen(g, top, cfg.screenNodes, cfg.screenMs);
                    out.relaxScreens++;
                    if (x.lines > 0) {
                        wHit += same.size();
                    }
                    if (inHand[0] != null) {
                        quietly(g, () -> g.getAction().moveToLibrary(inHand[0], Math.max(0, at), null,
                                forge.game.ability.AbilityKey.newMap()));
                    }
                }
                if (wHit > 0) {
                    out.relaxR = r;
                    out.relaxP = wHit / unknown.size();
                    out.relaxHow = "L" + (r - 1) + "D1x";
                    return;
                }
            } else if (!unknown.isEmpty()) {
                int hits = 0;
                for (int d = 0; d < cfg.relaxDraws; d++) {
                    final Card c = unknown.get(rng.nextInt(unknown.size()));
                    final int at = new ArrayList<>(me.getCardsIn(ZoneType.Library)).indexOf(c);
                    final Card[] inHand = new Card[1];
                    quietly(g, () -> inHand[0] = g.getAction().moveTo(ZoneType.Hand, c, null, forge.game.ability.AbilityKey.newMap()));
                    final forge.ai.simulation.ForcedWinSolver.Screen x = forge.ai.simulation.ForcedWinSolver.withReader(me,
                            cfg.solverCfg, screenReader).screen(g, top, cfg.screenNodes, cfg.screenMs);
                    out.relaxScreens++;
                    if (x.lines > 0) {
                        hits++;
                    }
                    if (inHand[0] != null) {
                        quietly(g, () -> g.getAction().moveToLibrary(inHand[0], Math.max(0, at), null,
                                forge.game.ability.AbilityKey.newMap()));
                    }
                }
                if (hits > 0) {
                    out.relaxR = r;
                    out.relaxP = hits / (double) cfg.relaxDraws;
                    out.relaxHow = "L" + (r - 1) + "D1";
                    return;
                }
            }
            // (b) L^r
            if (added < lands.size()) {
                final Card c = lands.get(added++);
                quietly(g, () -> {
                    final Card nc = g.getAction().moveToPlay(c, me, null, forge.game.ability.AbilityKey.newMap());
                    if (nc != null) {
                        nc.setTapped(false);
                    }
                });
                final forge.ai.simulation.ForcedWinSolver.Screen x = forge.ai.simulation.ForcedWinSolver.withReader(me,
                        cfg.solverCfg, screenReader).screen(g, top, cfg.screenNodes, cfg.screenMs);
                out.relaxScreens++;
                if (x.lines > 0) {
                    out.relaxR = r;
                    out.relaxP = 1.0;
                    out.relaxHow = "L" + r;
                    return;
                }
            }
        }
    }

    /** Pass 3: the share of draw samples with a line at the option's distance (1 for lands only; 0 when unknown). */
    private static double relaxP(final Option o) {
        return o.screen == null || Double.isNaN(o.screen.relaxP) ? 0.0 : o.screen.relaxP;
    }

    /** The card ids an option answers with (decline: -1). */
    private static List<Integer> picksOf(final RlCandidates.Menu m, final Option o) {
        if (o.steps == null || o.steps.length == 0) {
            return Collections.singletonList(-1);
        }
        final List<Integer> ids = ids(m, o.steps);
        return ids == null ? Collections.singletonList(-1) : ids;
    }

    /**
     * Pass 3 (relax > 0, lineOnly): when no option has a verified line, the relaxed-planning distance of the policy's option
     * and the best-valued others (relaxTop in all; options whose plain screen saw a line are left out: the check priced
     * them), within relaxMs. Returns the steps to play instead of {@code greedy}, or null (no relaxed line anywhere, or
     * the policy's answer is among the closest and valued no lower). Call after {@link #choose}.
     */
    public short[] relax(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy, final RlKnowledge know,
            final long seed, final Scored s) {
        if (cfg.relax <= 0 || !cfg.lineOnly || s.unsupported != null || s.options.isEmpty()) {
            return null;
        }
        for (Option o : s.options) {
            if (pLine(o) > 0) {
                return null; // a verified line: the line rule decided
            }
        }
        Option own = null;
        final List<Option> rest = new ArrayList<>();
        for (Option o : s.options) {
            if (o.policy) {
                own = o;
            } else if (o.fail == null && Double.isNaN(o.terminal) && (o.screen == null || o.screen.lines == 0)) {
                rest.add(o);
            }
        }
        // coordinator 11-09: relax in order of the cheapest first pass (the policy's option first: the default to beat)
        rest.sort((a, b) -> Long.compare(a.passNanos, b.passNanos));
        final List<Option> cands = new ArrayList<>();
        if (own != null && own.fail == null && Double.isNaN(own.terminal) && (own.screen == null || own.screen.lines == 0)) {
            cands.add(own);
        }
        for (Option o : rest) {
            if (cands.size() >= cfg.relaxTop) {
                break;
            }
            cands.add(o);
        }
        final long t0 = System.nanoTime();
        final int seat = g.getRegisteredPlayers().indexOf(me);
        final ChoiceApply.Frames fr = frames(know, cfg.searchedTop ? 1 << seat : 0, s);
        relaxMode = true;
        noCheck = true;
        relaxBest = Integer.MAX_VALUE;
        relaxDeadline = (forge.game.WorkClock.active() ? forge.game.WorkClock.nanoTime() : System.nanoTime()) + cfg.relaxMs * 1_000_000L;
        try {
            int at = 0;
            for (Option o : cands) {
                if (forge.game.WorkClock.nanoTime() > relaxDeadline) {
                    break;
                }
                curKey = LookaheadSearch.mix(seed, 0x7E1A0000L + at++);
                final ChoiceApply.Result r = apply(g, me, picksOf(m, o), seed, fr);
                if (r.screen != null && r.screen.relaxR >= 0) {
                    if (o.screen == null) {
                        o.screen = r.screen;
                    } else {
                        o.screen.relaxR = r.screen.relaxR;
                        o.screen.relaxP = r.screen.relaxP;
                        o.screen.relaxHow = r.screen.relaxHow;
                        o.screen.relaxScreens = r.screen.relaxScreens;
                    }
                }
                s.relaxed++;
            }
        } finally {
            relaxMode = false;
            noCheck = false;
            relaxDeadline = Long.MAX_VALUE;
            s.relaxNanos = System.nanoTime() - t0;
        }
        Option best = null;
        for (Option o : cands) {
            final int r = o.screen == null ? -1 : o.screen.relaxR;
            if (r < 0 || r == Integer.MAX_VALUE) {
                continue;
            }
            if (best == null || r < best.screen.relaxR || r == best.screen.relaxR && (relaxP(o) > relaxP(best)
                    || relaxP(o) == relaxP(best) && tieValue(o) > tieValue(best))) {
                best = o;
            }
        }
        if (best == null || best == own) {
            s.chosen = own;
            return null;
        }
        if (own != null && own.screen != null && own.screen.relaxR == best.screen.relaxR && relaxP(own) == relaxP(best)
                && tieValue(own) >= tieValue(best)) {
            s.chosen = own;
            return null;
        }
        s.chosen = best;
        s.relaxDeparted = true;
        return best.steps;
    }

    /** choice-search-1109: the honest win check of an option's position (null = none). */
    private forge.ai.simulation.HonestLabel.Seat checker;
    /** choice-search-1109: the check's world key for the option being applied. */
    private long curKey;
    /** choice-search-1109: true while the guard's probe copies run (they never spend a check). */
    private boolean noCheck = false;
    /** choice-search-1109: the asking sub-ability's index in the resolving entry's chain (0 = root). */
    private int askChain = 0;
    /** choice-search-1109: this ask is the cleanup's discard to hand size (a step, not a resolution). */
    private boolean cleanupMode = false;
    /** choice-search-1109: this ask is a card cost of a spell being cast (COST_CARDS), and that spell's ability. */
    private boolean costMode = false;
    private SpellAbility castSa;
    /** choice-search-1109 (COST_CARDS): the asked cost part's class (CostDiscard, CostExile, ...), set by the caller. */
    private String costPart;

    public void setCostPart(final String part) {
        this.costPart = part;
    }

    /** choice-search-1109: one answer applied on a copy: the resolution from the asking sub-ability, or the cleanup step. */
    private ChoiceApply.Result apply(final Game g, final Player me, final List<Integer> picks, final long seed,
            final ChoiceApply.Frames fr) {
        return costMode ? ChoiceApply.applyCost(g, me, castSa, costPart, picks, seed, cfg.settleSteps, cfg.fidelity, fr)
                : cleanupMode ? ChoiceApply.applyCleanup(g, me, picks, seed, cfg.settleSteps, cfg.fidelity, fr)
                : ChoiceApply.applyPicks(g, me, picks, null, seed, cfg.settleSteps, cfg.fidelity, fr, askChain);
    }

    /** choice-search-1109: the card ids of a menu answer's steps (null when a step has no card). */
    static List<Integer> ids(final RlCandidates.Menu m, final short[] steps) {
        if (steps == null) {
            return null;
        }
        final List<Integer> out = new ArrayList<>();
        for (short x : steps) {
            if (x < 0 || x >= m.C() || m.cands.get(x).host == null) {
                return null;
            }
            out.add(m.cands.get(x).host.getId());
        }
        return out;
    }

    /**
     * choice-search-1109: a multi-pick ask (a discard of several cards, a put-back in one answer). The options are the
     * policy's set, then every set one card away from it (one of its cards swapped for a card it left out; cards that
     * are the same answer, by name, zone and controller, count once), then, when fewer are allowed, the set without
     * one of its cards; at most {@code subsetCap}. Every "keep this card" answer is one swap away from the policy's.
     */
    private void subsets(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy, final long seed,
            final ChoiceApply.Frames fr, final Scored s, final long deadline) {
        if (greedy == null || greedy.length == 0 || m.validate(greedy) != null) {
            s.unsupported = "noPolicySet";
            return;
        }
        final List<short[]> sets = new ArrayList<>();
        final Set<String> seen = new java.util.HashSet<>();
        final java.util.function.Function<short[], String> keyOf = st -> {
            final List<String> ks = new ArrayList<>();
            for (short x : st) {
                ks.add(ChoiceWindow.looseKey(m, x, me));
            }
            Collections.sort(ks);
            return String.join("&", ks);
        };
        sets.add(greedy.clone());
        seen.add(keyOf.apply(greedy));
        final Set<Integer> in = new java.util.HashSet<>();
        for (short x : greedy) {
            in.add((int) x);
        }
        for (int a = 0; a < greedy.length && sets.size() < cfg.subsetCap; a++) {
            for (int q = 0; q < m.C() && sets.size() < cfg.subsetCap; q++) {
                if (in.contains(q) || m.cands.get(q).kind <= 0 || m.cands.get(q).host == null) {
                    continue;
                }
                final short[] st = greedy.clone();
                st[a] = (short) q;
                if (m.validate(st) == null && seen.add(keyOf.apply(st))) {
                    sets.add(st);
                }
            }
        }
        if (m.minPick < greedy.length) {
            for (int a = 0; a < greedy.length && sets.size() < cfg.subsetCap; a++) {
                final short[] st = new short[greedy.length - 1];
                for (int i = 0, j = 0; i < greedy.length; i++) {
                    if (i != a) {
                        st[j++] = greedy[i];
                    }
                }
                if (m.validate(st) == null && seen.add(keyOf.apply(st))) {
                    sets.add(st);
                }
            }
        }
        s.menuOptions = sets.size();
        int at = 0;
        for (short[] st : sets) {
            final Option o = new Option();
            o.steps = st;
            final StringBuilder lb = new StringBuilder();
            for (short x : st) {
                lb.append(lb.length() == 0 ? "" : "+").append(m.cands.get(x).host.getName());
            }
            o.label = lb.length() == 0 ? "none" : lb.toString();
            o.policy = at == 0;
            if (forge.game.WorkClock.nanoTime() >= deadline && !o.policy) {
                o.fail = "budget";
                s.overBudget = true;
            } else {
                curKey = LookaheadSearch.mix(seed, 0x50170000L + at);
                final List<Integer> picks = ids(m, st);
                if (picks.isEmpty()) {
                    picks.add(-1);
                }
                run(g, me, picks, seed, fr, o, s);
            }
            at++;
            s.options.add(o);
        }
    }

    public void setChecker(final forge.ai.simulation.HonestLabel.Seat c) {
        this.checker = c;
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

    /** The ask's options with their frames; values are the caller's batched pass ({@link #choose}). */
    public Scored enumerate(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy,
            final RlKnowledge know, final long seed, final SpellAbility askSa) {
        final Scored s = new Scored();
        final long t0 = System.nanoTime();
        if (cfg.relax <= 0) {
            screenReader = null; // one reader per ask: its probes are of this position's copies
        } // choice-search-1109 pass 3: with relax, one reader per game (the solver's readerPerGame; affordances by card)
        relaxBest = Integer.MAX_VALUE;
        // choice-search-1109: an ask from a sub-ability is replayed from that sub-ability (0 = the root, as before)
        cleanupMode = false;
        costMode = cfg.lineOnly && m.family == RlSchema.F_COST_CARDS;
        castSa = costMode ? askSa : null;
        askChain = askSa == null || costMode ? 0 : ChoiceApply.chainOf(g, askSa);
        if (askChain < 0) {
            s.unsupported = "chain";
            s.totalNanos = System.nanoTime() - t0;
            return s;
        }
        final int seat = g.getRegisteredPlayers().indexOf(me);
        final ChoiceApply.Frames fr = frames(know, cfg.searchedTop ? 1 << seat : 0, s);
        final long deadline = cfg.budgetMs > 0 ? (forge.game.WorkClock.active() ? forge.game.WorkClock.nanoTime() : t0) + cfg.budgetMs * 1_000_000L : Long.MAX_VALUE;
        if (m.family == RlSchema.F_CARDS || (m.family == RlSchema.F_DISCARD_FROM || m.family == RlSchema.F_COST_CARDS) && cfg.lineOnly) {
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
        }
        s.totalNanos = System.nanoTime() - t0;
        return s;
    }

    private void cards(final Game g, final Player me, final RlCandidates.Menu m, final short[] greedy, final long seed,
            final ChoiceApply.Frames fr, final Scored s, final long deadline) {
        final boolean subset = cfg.lineOnly && !ChoiceWindow.singlePick(m) && m.mode == RlSchema.M_SUBSET;
        if (!ChoiceWindow.singlePick(m) && !subset) {
            s.unsupported = "multiPick";
            return;
        }
        // choice-search-1109: the cleanup's discard to hand size is replayed on a copy of the step, not of a resolution
        cleanupMode = cfg.lineOnly && "chooseCardsToDiscardToMaximumHandSize".equals(m.method) && ChoiceApply.cleanupAsk(g, me);
        s.unsupported = costMode ? ChoiceApply.castUnsupported(g, me, castSa) : cleanupMode ? null : ChoiceApply.unsupported(g, null);
        if (s.unsupported != null) {
            return;
        }
        if (cfg.guard) {
            noCheck = true; // choice-search-1109: the guard's probe copies never spend a check
            try {
                s.unsupported = hiddenGuard(g, me, m, seed, fr, deadline, subset ? ids(m, greedy) : null);
            } finally {
                noCheck = false;
            }
            if ("multiAnswer".equals(s.unsupported) && cfg.pile && cfg.solverCfg != null) {
                piles(g, me, m, seed, fr, s);
                return;
            }
            if (s.unsupported != null) {
                return;
            }
        }
        if (subset) {
            subsets(g, me, m, greedy, seed, fr, s, deadline);
            return;
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
        if (cfg.lineOnly) {
            // choice-search-1109: the policy's answer first (it gets the first check), then the menu's order; a decline
            // (an optional search's "find nothing") is an option like the others
            final List<Option> opts = new ArrayList<>();
            if (m.minPick == 0) {
                final Option no = new Option();
                no.steps = new short[0];
                no.label = "decline";
                no.policy = greedy != null && greedy.length == 0;
                opts.add(no);
            }
            for (int i : keep) {
                final Option o = new Option();
                o.steps = new short[] {(short) i};
                o.label = m.cands.get(i).host.getName();
                o.policy = i == own;
                opts.add(o);
            }
            opts.sort((a, b) -> Boolean.compare(b.policy, a.policy)); // stable: the policy's answer first
            int at = 0;
            for (Option o : opts) {
                if (forge.game.WorkClock.nanoTime() >= deadline && !o.policy) {
                    o.fail = "budget";
                    s.overBudget = true;
                } else {
                    curKey = LookaheadSearch.mix(seed, 0x50170000L + at);
                    run(g, me, o.steps.length == 0 ? Collections.singletonList(-1)
                            : Collections.singletonList(m.cands.get(o.steps[0]).host.getId()), seed, fr, o, s);
                }
                at++;
                s.options.add(o);
            }
            return;
        }
        if (m.minPick == 0) {
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
            final ChoiceApply.Frames fr, final long deadline, final List<Integer> probe) {
        int first = -1;
        for (int i = 0; i < m.C() && first < 0; i++) {
            if (m.cands.get(i).kind > 0 && m.cands.get(i).host != null) {
                first = i;
            }
        }
        if (first < 0 && probe == null) {
            return null;
        }
        // choice-search-1109: a multi-pick ask is probed with the policy's whole answer
        final List<Integer> pick = probe != null && !probe.isEmpty() ? probe
                : Collections.singletonList(m.cands.get(first).host.getId());
        final ChoiceApply.Result a = apply(g, me, pick, seed, fr);
        if (a.fail != null) {
            return null; // the options report their own failures
        }
        if (a.tailAsks > 0) {
            return "multiAnswer";
        }
        final boolean was = guardSwap;
        guardSwap = true;
        try {
            final ChoiceApply.Result b = apply(g, me, pick, seed, fr);
            if (b.fail == null && !Arrays.equals(a.payload, b.payload)) {
                return "oppHidden";
            }
        } finally {
            guardSwap = was;
        }
        return null;
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
        final long t = System.nanoTime();
        final ChoiceApply.Result r = apply(g, me, picks, seed, fr);
        o.passNanos = System.nanoTime() - t;
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
            if (o.policy) {
                own = o;
            }
            if (!Double.isNaN(o.value) && (best == null || better(o, best))) {
                best = o;
            }
        }
        s.chosen = best;
        if (cfg.lineOnly) {
            return chooseLine(s, own);
        }
        if (best != null && best.pileIds != null) {
            return best.screen != null && best.screen.lines > 0 ? best.steps : null;
        }
        if (best == null || own == null || Double.isNaN(own.value) || best == own
                || !(own.screen != null && best.screen != null && best.screen.lines > 0 && own.screen.lines == 0)
                && best.value <= own.value + cfg.margin) {
            return null;
        }
        return best.steps;
    }

    /**
     * choice-search-1109: an option's verified chance of a line: 1 for a copy that ended in our win, the honest check's
     * p_win where it ran and found a line, else 0 (no line seen, or not verified).
     */
    static double pLine(final Option o) {
        if (o.fail != null) {
            return 0.0;
        }
        if (!Double.isNaN(o.terminal)) {
            return o.terminal >= 1.0 ? 1.0 : 0.0;
        }
        if (o.screen == null || Double.isNaN(o.screen.pWin)) {
            return 0.0;
        }
        return Math.max(0.0, o.screen.pWin);
    }

    /**
     * choice-search-1109 (lineOnly): the option with the highest verified p_win, ties broken by the leaf value (an option
     * whose value failed ranks below); with no verified line anywhere, or the policy's answer among the best, the policy's
     * answer stands (null). {@link Scored#chosen} is the option played.
     */
    short[] chooseLine(final Scored s, final Option own) {
        Option best = null;
        double bp = 0.0;
        for (Option o : s.options) {
            final double p = pLine(o);
            if (p <= 0.0) {
                continue;
            }
            if (best == null || p > bp || p == bp && tieValue(o) > tieValue(best)) {
                best = o;
                bp = p;
            }
        }
        if (best == null) {
            s.chosen = own;
            return null;
        }
        if (own != null && (best == own || pLine(own) == bp && tieValue(own) >= tieValue(best))) {
            s.chosen = own;
            return null;
        }
        s.chosen = best;
        return best.steps;
    }

    private static double tieValue(final Option o) {
        return Double.isNaN(o.value) ? Double.NEGATIVE_INFINITY : o.value;
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
                x.addProperty("plan_nodes", o.screen.nodes);
                x.addProperty("plan_ms", Math.round(o.screen.nanos / 1e4) / 100.0);
                if (o.screen.exhausted) x.addProperty("plan_exh", true);
                if (o.screen.failed) x.addProperty("plan_fail", String.valueOf(o.screen.best));
                if (o.screen.bestTurn >= 0) {
                    x.addProperty("win_turn", o.screen.bestTurn);
                    x.addProperty("win_steps", o.screen.bestSteps);
                }
                if (o.screen.relaxR >= 0) {
                    // choice-search-1109 pass 3
                    x.addProperty("relax_r", o.screen.relaxR == Integer.MAX_VALUE ? -1 : o.screen.relaxR);
                    if (!Double.isNaN(o.screen.relaxP)) x.addProperty("relax_p", o.screen.relaxP);
                    if (o.screen.relaxHow != null) x.addProperty("relax_how", o.screen.relaxHow);
                    x.addProperty("relax_screens", o.screen.relaxScreens);
                }
                if (o.screen.check != null) {
                    // choice-search-1109: the honest check of this option's position
                    x.addProperty("check", o.screen.check);
                    x.addProperty("check_gate", o.screen.checkGate);
                    if (!Double.isNaN(o.screen.pWin)) x.addProperty("p_win", Math.round(o.screen.pWin * 1e4) / 1e4);
                    x.addProperty("check_worlds", o.screen.checkWorlds);
                    x.addProperty("check_ms", Math.round(o.screen.checkMs * 100) / 100.0);
                }
            }
            if (cfg.lineOnly && o == s.chosen) x.addProperty("chosen", true);
            a.add(x);
        }
        r.add("options", a);
        if (cfg.lineOnly) {
            r.addProperty("line_only", true);
            r.addProperty("checks", s.checks);
            r.addProperty("proven", s.proven);
            r.addProperty("check_ms", Math.round(s.checkNanos / 1e4) / 100.0);
        }
        if (cfg.relax > 0) {
            r.addProperty("relaxed", s.relaxed);
            r.addProperty("relax_ms", Math.round(s.relaxNanos / 1e4) / 100.0);
            r.addProperty("relax_departed", s.relaxDeparted);
        }
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
