package forge.bench.rl;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.ChoiceApply;
import forge.ai.simulation.LookaheadSearch;
import forge.ai.simulation.WhatIf;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-whatif-1111 ({@code choiceScorer.whatIf}; absent = off, and then nothing here runs): the scored options of a
 * card ask get a what-if value ({@link WhatIf}: how soon the engine search finds a kill from the position each option
 * leads to, as a goldfish over sampled orders of the seat's unknown library), and the ask is answered by
 * {@code P = a*W + (1 - a*W) * v}, v being the option's value from today's scorer. When no option has a kill, or all
 * have the same W, today's answer stands. {@code shadow}: computed and logged, today's answer kept (the game is
 * unchanged: the what-if runs on its own work clock, random stream and id scope).
 *
 * <p>Each option is applied again on its own copy, whose unknown own library is put in a seeded order before anything
 * resolves, so a card the resolution draws is not the real next card.
 */
public final class WhatIfChoice {
    private WhatIfChoice() {
    }

    public static final class Config {
        public int worlds = 3, horizon = 4, nodes = 60, maxSteps = 600, second = 0, secondNodes = 120, secondMax = 300, all = 0, allNodes = 400, allRounds = 2;
        public long probeMs = 1500, askMs = 60_000;
        public double gamma = 0.8, alpha = 1.0, secondDiscount = 0.5, allDiscount = 0.5;
        public boolean pass = false, shadow = false, pool = true;
        public String dev = "ai";
        /** Real-time guard of one ask, ms (0 = none): checked between options' applications only. */
        public long realMs = 0;

        static final Set<String> KEYS = new java.util.TreeSet<>(Arrays.asList("worlds", "horizon", "nodes", "probeMs",
                "askMs", "gamma", "alpha", "pass", "shadow", "dev", "maxSteps", "realMs", "pool", "second", "secondNodes", "secondDiscount", "secondMax", "all", "allNodes", "allRounds", "allDiscount"));

        public static Config parse(final JsonObject o) {
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("choiceScorer.whatIf: unknown key " + k);
                }
            }
            final Config c = new Config();
            if (o.has("worlds")) c.worlds = o.get("worlds").getAsInt();
            if (o.has("horizon")) c.horizon = o.get("horizon").getAsInt();
            if (o.has("nodes")) c.nodes = o.get("nodes").getAsInt();
            if (o.has("probeMs")) c.probeMs = o.get("probeMs").getAsLong();
            if (o.has("askMs")) c.askMs = o.get("askMs").getAsLong();
            if (o.has("gamma")) c.gamma = o.get("gamma").getAsDouble();
            if (o.has("alpha")) c.alpha = o.get("alpha").getAsDouble();
            if (o.has("pass")) c.pass = o.get("pass").getAsBoolean();
            if (o.has("shadow")) c.shadow = o.get("shadow").getAsBoolean();
            if (o.has("pool")) c.pool = o.get("pool").getAsBoolean();
            if (o.has("second")) c.second = o.get("second").getAsInt();
            if (o.has("secondNodes")) c.secondNodes = o.get("secondNodes").getAsInt();
            if (o.has("secondDiscount")) c.secondDiscount = o.get("secondDiscount").getAsDouble();
            if (o.has("secondMax")) c.secondMax = o.get("secondMax").getAsInt();
            if (o.has("all")) c.all = o.get("all").getAsInt();
            if (o.has("allNodes")) c.allNodes = o.get("allNodes").getAsInt();
            if (o.has("allRounds")) c.allRounds = o.get("allRounds").getAsInt();
            if (o.has("allDiscount")) c.allDiscount = o.get("allDiscount").getAsDouble();
            if (o.has("dev")) c.dev = o.get("dev").getAsString();
            if (o.has("maxSteps")) c.maxSteps = o.get("maxSteps").getAsInt();
            if (o.has("realMs")) c.realMs = o.get("realMs").getAsLong();
            if (!"ai".equals(c.dev) && !"land".equals(c.dev)) {
                throw new IllegalArgumentException("choiceScorer.whatIf.dev: ai or land, not " + c.dev);
            }
            return c;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("worlds", worlds);
            o.addProperty("horizon", horizon);
            o.addProperty("nodes", nodes);
            o.addProperty("probeMs", probeMs);
            o.addProperty("askMs", askMs);
            o.addProperty("gamma", gamma);
            o.addProperty("alpha", alpha);
            o.addProperty("pass", pass);
            o.addProperty("shadow", shadow);
            o.addProperty("pool", pool);
            if (second > 0) {
                o.addProperty("second", second);
                o.addProperty("secondNodes", secondNodes);
                o.addProperty("secondDiscount", secondDiscount);
                o.addProperty("secondMax", secondMax);
            }
            o.addProperty("dev", dev);
            o.addProperty("maxSteps", maxSteps);
            if (all > 0) {
                o.addProperty("all", all);
                o.addProperty("allNodes", allNodes);
                o.addProperty("allRounds", allRounds);
                o.addProperty("allDiscount", allDiscount);
            }
            if (realMs > 0) {
                o.addProperty("realMs", realMs);
            }
            return o;
        }

        public WhatIf.Opts opts(final long seed) {
            final WhatIf.Opts w = new WhatIf.Opts();
            w.worlds = worlds;
            w.horizon = horizon;
            w.nodes = nodes;
            w.probeMs = probeMs;
            w.askMs = askMs;
            w.gamma = gamma;
            w.pass = pass;
            w.pool = pool;
            w.second = second;
            w.secondNodes = secondNodes;
            w.secondDiscount = secondDiscount;
            w.secondMax = secondMax;
            w.all = all;
            w.allNodes = allNodes;
            w.allRounds = allRounds;
            w.allDiscount = allDiscount;
            w.dev = dev;
            w.maxSteps = maxSteps;
            w.seed = seed;
            return w;
        }
    }

    /** Stored-position dump (measuring tools only): {@code -Dforge.ai.whatIf.dump=DIR}; one JSON line per ask. */
    private static final String DUMP = System.getProperty("forge.ai.whatIf.dump");

    private static ChoiceApply.Frames frames(final RlKnowledge live, final int seat, final boolean searchedTop,
            final boolean pregame, final boolean search, final long seed, final WhatIf.Snapshot[] out) {
        return new ChoiceApply.Frames() {
            @Override
            public Object onCopy(final Game copy, final Player me) {
                final RlKnowledge k = live.forkFor(copy);
                if (searchedTop) {
                    k.setSearchedTopSeats(1 << seat);
                }
                k.attach();
                // before anything resolves on the copy: the part of our library the seat does not know, in a seeded
                // order (the real order is not read by what the resolution draws or looks at)
                // (a seat searching its library, the menu's cards being there, knows every card in it but not their order:
                // nothing stays in place then)
                final int kt = knownTop(k, me);
                WhatIf.reorder(me, search && kt >= me.getCardsIn(ZoneType.Library).size() ? 0 : kt, LookaheadSearch.mix(seed, 0x0DDE7));
                return k;
            }

            @Override
            public byte[] capture(final Object ctx, final Game g, final Player me) {
                out[0] = WhatIf.snapshot(g, me, knownTop((RlKnowledge) ctx, me), pregame);
                return new byte[0];
            }

            @Override
            public void onOrder(final Object ctx, final Game g, final Player me, final List<Card> cards) {
                ((RlKnowledge) ctx).onLook(g, me, cards, ZoneType.Library);
            }
        };
    }

    private static int knownTop(final RlKnowledge k, final Player me) {
        final int st = k.seatOf(me);
        int n = 0;
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            if (!k.knows(st, c)) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * The ask's answer with the what-if: {@code alt} (today's answer: the steps to play, or null = the policy's own)
     * unless the what-if departs from it. {@code s} is the scored ask after {@link ChoiceScorer#choose}; its
     * {@code whatIf} row object is filled.
     */
    public static short[] answer(final ChoiceScorer.Config sc, final Config wc, final Game g, final Player me,
            final RlCandidates.Menu m, final ChoiceScorer.Scored s, final short[] greedy, final short[] alt,
            final RlKnowledge know, final long seed, final String tag) {
        if (s.unsupported != null || s.options.isEmpty() || sc.solverCfg == null
                || m.family != RlSchema.F_CARDS && m.family != RlSchema.F_MULLIGAN_BOTTOM) {
            return alt;
        }
        final forge.game.WorkClock.Clock outer = forge.game.WorkClock.current();
        if (outer != null) {
            forge.game.WorkClock.install(forge.game.WorkClock.newClock(outer.scale)); // its own clock: the decision's is not charged
        }
        try {
            final long real0 = System.nanoTime();
            final int seat = g.getRegisteredPlayers().indexOf(me);
            final boolean pregame = m.family == RlSchema.F_MULLIGAN_BOTTOM;
            boolean search = false;
            for (int i = 0; i < m.C() && !search; i++) {
                final Card h = m.cands.get(i).host;
                search = h != null && h.isInZone(ZoneType.Library) && h.getOwner() == me;
            }
            final List<WhatIf.Snapshot> snaps = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            for (ChoiceScorer.Option o : s.options) {
                labels.add(o.label);
                WhatIf.Snapshot snap = null;
                // an option the scorer's own ask budget dropped is still asked here (it then ranks with the lowest value)
                if ((o.fail == null && !Double.isNaN(o.value) || "budget".equals(o.fail))
                        && (wc.realMs <= 0 || System.nanoTime() - real0 < wc.realMs * 1_000_000L)) {
                    final WhatIf.Snapshot[] out = new WhatIf.Snapshot[1];
                    final ChoiceApply.Frames fr = frames(know, seat, sc.searchedTop, pregame, search, seed, out);
                    final ChoiceApply.Result r;
                    if (pregame) {
                        final List<Integer> idx = new ArrayList<>();
                        for (short st : o.steps) {
                            idx.add((int) st);
                        }
                        r = ChoiceApply.applyHand(g, me, false, idx, seed, sc.fidelity, fr);
                    } else if (o.pileIds != null) {
                        r = ChoiceApply.applyPicks(g, me, o.pileIds, o.pileIds, seed, sc.settleSteps, sc.fidelity, fr);
                    } else if (o.steps.length == 0) {
                        r = ChoiceApply.applyPicks(g, me, Collections.singletonList(-1), seed, sc.settleSteps, sc.fidelity, fr);
                    } else {
                        r = ChoiceApply.applyPicks(g, me,
                                Collections.singletonList(m.cands.get(o.steps[0]).host.getId()), seed, sc.settleSteps,
                                sc.fidelity, fr);
                    }
                    if (r.fail == null && out[0] != null) {
                        snap = out[0];
                        // how the option relates to its card, for the pooling of kill lines
                        if (pregame && o.steps.length == 1) {
                            snap.mode = WhatIf.Snapshot.REMOVES;
                            snap.card = m.cands.get(o.steps[0]).host.getName();
                        } else if (!pregame && o.pileIds == null && o.steps.length == 0 && search) {
                            snap.mode = WhatIf.Snapshot.NOTHING;
                        } else if (!pregame && o.pileIds == null && o.steps.length == 1) {
                            final Card h = m.cands.get(o.steps[0]).host;
                            if (search) {
                                snap.mode = WhatIf.Snapshot.ADDS;
                                snap.card = h.getName();
                            } else if (h.isInZone(ZoneType.Hand) && h.getOwner() == me) {
                                snap.mode = WhatIf.Snapshot.REMOVES;
                                snap.card = h.getName();
                            }
                        }
                    }
                }
                snaps.add(snap);
            }
            final WhatIf.Opts wo = wc.opts(seed);
            final WhatIf.Ask a = WhatIf.evaluate(snaps, sc.solverCfg, wo);
            WhatIf.receipt(tag, labels, a, wo);
            // P = a*W + (1 - a*W) * v
            double wMin = Double.POSITIVE_INFINITY, wMax = 0;
            ChoiceScorer.Option best = null, own = null;
            double bestP = Double.NaN, ownP = Double.NaN;
            final double[] ps = new double[s.options.size()];
            double vMin = Double.NaN;
            for (ChoiceScorer.Option o : s.options) {
                if (!Double.isNaN(o.value) && !(o.value >= vMin)) {
                    vMin = o.value;
                }
            }
            for (int i = 0; i < s.options.size(); i++) {
                final ChoiceScorer.Option o = s.options.get(i);
                final WhatIf.Result r = a.results.get(i);
                final double w = Double.isNaN(r.value) ? 0.0 : r.value;
                ps[i] = Double.NaN;
                final double v = Double.isNaN(o.value) && "budget".equals(o.fail) && !Double.isNaN(r.value) ? vMin : o.value;
                if (Double.isNaN(v)) {
                    continue;
                }
                wMin = Math.min(wMin, w);
                wMax = Math.max(wMax, w);
                final double x = wc.alpha * w;
                ps[i] = x + (1 - x) * v;
                if (o.policy) {
                    own = o;
                    ownP = ps[i];
                }
                if (best == null || ps[i] > bestP) {
                    best = o;
                    bestP = ps[i];
                }
            }
            final boolean differs = wMax > 0 && wMax - wMin > 1e-9;
            // today's answer, by label: the scorer's departure, else the policy's own option
            String today = alt != null && s.chosen != null ? s.chosen.label : null;
            if (today == null) {
                for (ChoiceScorer.Option o : s.options) {
                    if (o.policy) {
                        today = o.label;
                    }
                }
            }
            short[] out = alt;
            String pick = today;
            ChoiceScorer.Option take = null;
            boolean keepPolicy = false;
            if (differs && best != null) {
                if (own == null) {
                    take = best;
                } else if (best == own || !(bestP > ownP + sc.margin)) {
                    keepPolicy = true;
                } else {
                    take = best;
                }
                pick = take != null ? take.label : own.label;
                if (!wc.shadow) {
                    if (keepPolicy) {
                        out = null;
                    } else {
                        out = take.steps;
                        s.chosen = take;
                    }
                }
            }
            final JsonObject row = new JsonObject();
            row.addProperty("stages", a.stages);
            row.addProperty("cut", a.cut);
            row.addProperty("probes", a.probes);
            row.addProperty("nodes", a.nodes);
            row.addProperty("ms", Math.round(a.nanos / 1e4) / 100.0);
            row.addProperty("work_ms", Math.round(a.workNanos / 1e4) / 100.0);
            row.addProperty("total_ms", Math.round((System.nanoTime() - real0) / 1e4) / 100.0);
            row.addProperty("differs", differs);
            row.addProperty("shadow", wc.shadow);
            if (pick != null) {
                row.addProperty("pick", pick);
            }
            if (today != null) {
                row.addProperty("today", today);
            }
            if (best != null) {
                row.addProperty("best", best.label);
            }
            final JsonArray arr = new JsonArray();
            for (int i = 0; i < s.options.size(); i++) {
                final WhatIf.Result r = a.results.get(i);
                final JsonObject x = new JsonObject();
                x.addProperty("o", s.options.get(i).label);
                if (!Double.isNaN(r.value)) {
                    x.addProperty("w", Math.round(r.value * 1e4) / 1e4);
                    if (r.raw != r.value) {
                        x.addProperty("w_own", Math.round(r.raw * 1e4) / 1e4);
                    }
                    x.addProperty("kills", r.kills);
                    x.addProperty("worlds", r.worlds);
                }
                if (!Double.isNaN(ps[i])) {
                    x.addProperty("p", Math.round(ps[i] * 1e4) / 1e4);
                }
                if (r.all > 0) {
                    x.addProperty("all", Math.round(r.all * 1e4) / 1e4);
                    x.addProperty("all_needs", r.allNeeds);
                }
                if (r.second > 0) {
                    x.addProperty("second", Math.round(r.second * 1e4) / 1e4);
                    x.addProperty("with", s.options.get(r.partner).label);
                }
                if (r.bestTurn >= 0) {
                    x.addProperty("turn", r.bestTurn);
                    x.addProperty("line", r.line == null ? "" : r.line.length() > 400 ? r.line.substring(0, 400) : r.line);
                }
                if (r.fail != null) {
                    x.addProperty("fail", r.fail);
                }
                x.addProperty("probes", r.probes);
                x.addProperty("n", r.nodes);
                if (r.capped > 0) {
                    x.addProperty("capped", r.capped);
                }
                arr.add(x);
            }
            row.add("options", arr);
            s.whatIf = row;
            if (DUMP != null && !DUMP.isEmpty()) {
                dump(tag, g, me, m, s, snaps, row);
            }
            return out;
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("[whatif] " + tag + " " + e);
            return alt;
        } finally {
            if (outer != null) {
                forge.game.WorkClock.install(outer);
            }
        }
    }

    private static synchronized void dump(final String tag, final Game g, final Player me, final RlCandidates.Menu m,
            final ChoiceScorer.Scored s, final List<WhatIf.Snapshot> snaps, final JsonObject row) {
        final JsonObject d = new JsonObject();
        d.addProperty("tag", tag);
        d.addProperty("family", RlSchema.familyName(m.family));
        d.addProperty("turn", g.getPhaseHandler().getTurn());
        d.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        d.addProperty("seat", g.getRegisteredPlayers().indexOf(me));
        d.addProperty("src", ChoiceScorer.srcOf(g));
        d.add("whatif", row);
        final JsonArray arr = new JsonArray();
        for (int i = 0; i < s.options.size(); i++) {
            final ChoiceScorer.Option o = s.options.get(i);
            final JsonObject x = new JsonObject();
            x.addProperty("o", o.label);
            x.addProperty("policy", o.policy);
            x.addProperty("chosen", o == s.chosen);
            if (!Double.isNaN(o.value)) {
                x.addProperty("v", o.value);
            }
            if (o.screen != null && !Double.isNaN(o.screen.reach)) {
                x.addProperty("reach", o.screen.reach);
            }
            final WhatIf.Snapshot sn = snaps.get(i);
            if (sn != null) {
                x.addProperty("state", sn.dump());
                x.addProperty("knownTop", sn.knownTop());
                x.addProperty("pregame", sn.pregame());
                x.addProperty("mode", sn.mode);
                if (sn.card != null) {
                    x.addProperty("card", sn.card);
                }
                x.addProperty("me", sn.seat());
            }
            arr.add(x);
        }
        d.add("options", arr);
        try (FileWriter w = new FileWriter(DUMP + "/whatif-" + ProcessHandle.current().pid() + ".jsonl", true)) {
            w.write(d.toString());
            w.write("\n");
        } catch (IOException e) {
            System.err.println("[whatif] dump " + e);
        }
    }
}
