package forge.ai.simulation;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-leak-1111: does the solver's answer depend on hidden information? Each position ({@code -Dleak.positions=FILE},
 * the format of {@link OwnerLinesTest}) is solved with the seat's own library in the frame's order (order 0) and in
 * {@code leak.orders - 1} random reorderings of the part the seat does not know, each with {@code leak.seeds} solver
 * seeds, plus {@code leak.repeat} repeats of (order 0, seed 0) for the clock's noise. The cards keep their ids (the
 * library is reordered after the game is built), so nothing but the order differs between arms. {@code leak.dim=opp}
 * re-deals the opponent's hand and library instead (their hidden cards). A seat that cannot see the order must answer
 * alike for one order under two seeds and for two orders under two seeds; the Python reader (planner-fixes/leak/) counts
 * that. {@code leak.pile=known}: a position whose library top is its {@code pile} is told so (notePlaced), and only
 * the cards below it are reordered. {@code leak.cfg=k=v,...} overrides solver keys (to switch components off).
 * With {@code -Dforge.ai.leakTrace=true} each run also prints which component read a real-order copy
 * ({@link LeakTrace}). Without the positions property the test does nothing.
 */
public class LibraryLeakTest extends forge.ai.AITest {

    private static String cut(final Object o, final int n) {
        final String s = String.valueOf(o).replaceAll("\\s+", " ");
        return s.substring(0, Math.min(n, s.length()));
    }

    @Test
    public void orderArms() throws Exception {
        final String path = System.getProperty("leak.positions");
        if (path == null || path.isEmpty()) {
            System.out.println("LEAK skipped: no -Dleak.positions");
            return;
        }
        final JsonObject root;
        try (FileReader r = new FileReader(path)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        final JsonObject solverCfg = root.getAsJsonObject("solverCfg").deepCopy();
        solverCfg.addProperty("act", "false");
        solverCfg.addProperty("defense", "false");
        final String over = System.getProperty("leak.cfg", "");
        for (String kv : over.split(",")) {
            final int eq = kv.indexOf('=');
            if (eq > 0) {
                solverCfg.addProperty(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        final int orders = Integer.getInteger("leak.orders", 5), seeds = Integer.getInteger("leak.seeds", 3);
        final int repeat = Integer.getInteger("leak.repeat", 1);
        final boolean opp = "opp".equals(System.getProperty("leak.dim", "own"));
        final boolean pileKnown = "known".equals(System.getProperty("leak.pile", "unknown"));
        final boolean setup = Boolean.getBoolean("leak.setup");
        final JsonArray ps = root.getAsJsonArray("positions");
        for (JsonElement e : ps) {
            final JsonObject o = e.getAsJsonObject();
            final String id = o.get("game").getAsString();
            final String frame = o.get("state").getAsString();
            final List<String> pile = new ArrayList<>();
            if (pileKnown && o.has("pile") && o.get("pile").isJsonArray()) {
                for (JsonElement x : o.getAsJsonArray("pile")) {
                    pile.add(x.getAsString());
                }
            }
            for (int ord = 0; ord < orders; ord++) {
                for (int sd = 0; sd < seeds; sd++) {
                    final int reps = ord == 0 && sd == 0 ? 1 + repeat : 1;
                    for (int rep = 0; rep < reps; rep++) {
                        run(id, frame, solverCfg, pile, ord, sd, rep, opp, setup);
                    }
                }
            }
        }
    }

    private static void run(final String id, final String frame, final JsonObject solverCfg, final List<String> pile,
            final int ord, final int sd, final int rep, final boolean opp, final boolean setup) {
        final String head = "LEAK " + id + " dim=" + (opp ? "opp" : "own") + " order=" + ord + " seed=" + sd + " rep=" + rep;
        try {
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
            final Player me = g.getPlayers().get(0);
            final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
            // the known top: the frame's pile, when the library begins with exactly those cards
            int known = 0;
            if (!pile.isEmpty() && pile.size() <= lib.size()) {
                final List<String> top = new ArrayList<>(), want = new ArrayList<>(pile);
                for (int i = 0; i < pile.size(); i++) {
                    top.add(lib.get(i).getName());
                }
                Collections.sort(top);
                Collections.sort(want);
                known = top.equals(want) ? pile.size() : 0;
            }
            final Random perm = new Random(31L * id.hashCode() + 7919L * ord + 13);
            if (!opp && ord > 0) {
                final List<Card> rest = new ArrayList<>(lib.subList(known, lib.size()));
                Collections.shuffle(rest, perm);
                final List<Card> out = new ArrayList<>(lib.subList(0, known));
                out.addAll(rest);
                me.getZone(ZoneType.Library).setCards(out);
            }
            int oppHidden = 0;
            if (opp && ord > 0) {
                for (Player q : g.getPlayers()) {
                    if (q == me) {
                        continue;
                    }
                    final List<Card> hand = new ArrayList<>(q.getCardsIn(ZoneType.Hand));
                    final List<Card> pool = new ArrayList<>(q.getCardsIn(ZoneType.Library));
                    pool.addAll(hand);
                    Collections.shuffle(pool, perm);
                    oppHidden += hand.size();
                    q.getZone(ZoneType.Hand).setCards(new ArrayList<>(pool.subList(0, hand.size())));
                    q.getZone(ZoneType.Library).setCards(new ArrayList<>(pool.subList(hand.size(), pool.size())));
                }
            }
            final ForcedWinSolver.Config cfg = SolverDriver.config(solverCfg);
            cfg.seed = cfg.seed + 1_000_003L * sd;
            final ForcedWinSolver s = new ForcedWinSolver(me, cfg);
            final Set<Integer> knownIds = new HashSet<>();
            if (known > 0) {
                final List<Card> top = new ArrayList<>(me.getCardsIn(ZoneType.Library)).subList(0, known);
                s.notePlaced(top);
                for (Card c : top) {
                    knownIds.add(c.getId());
                }
            }
            LeakTrace.arm(g, me, knownIds);
            LeakTrace.report("", true);
            final long t0 = System.nanoTime();
            final List<ForcedWinSolver.Proposal> props = s.propose(g);
            ForcedWinSolver.Proposal best = null;
            for (ForcedWinSolver.Proposal p : props) {
                if (best == null || p.pBelief > best.pBelief) {
                    best = p;
                }
            }
            System.out.println(head + " unknown=" + (opp ? oppHidden : lib.size() - known) + " PROPOSE ms="
                    + (System.nanoTime() - t0) / 1_000_000 + " lines=" + props.size()
                    + (best == null ? " none" : " proven=" + best.proven(1) + " pUnopposed=" + best.pUnopposed + " pBelief="
                            + best.pBelief + " worlds=" + best.worldsRun + " steps=" + best.steps + " winTurn=" + best.winTurn
                            + " how=" + best.how + " | " + cut(best.text, 400))
                    + " cap=" + s.lastCap());
            for (String l : LeakTrace.report(head + " TRACE", true)) {
                System.out.println(l);
            }
            if (setup) {
                final ForcedWinSolver.GoldfishOpts go = new ForcedWinSolver.GoldfishOpts();
                go.horizon = 3;
                go.active = 3;
                go.nodes = 12000;
                go.maxNodes = 48000;
                go.ms = -1L;
                final ForcedWinSolver.Proposal p = s.setup(g, s.knownTop(g), go);
                System.out.println(head + " SETUP" + (p == null ? " none" : " gf=" + p.gf + " turn+" + p.winTurn + " steps=" + p.steps
                        + " | " + cut(p.text != null ? p.text : p.line, 400)));
                for (String l : LeakTrace.report(head + " TRACE-SETUP", true)) {
                    System.out.println(l);
                }
            }
        } catch (RuntimeException | Error ex) {
            System.out.println(head + " failed: " + cut(ex, 200));
        }
    }
}
