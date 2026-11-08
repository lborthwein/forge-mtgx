package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;




/**
 * solver-cap12-1030 round 2 (PREDECLARATION-solver-cap12-APPENDIX-round2 R2-5): the win-condition coverage suite. One
 * puzzle per way to win (the queued lane's list, every combo of COMBO-EDGE-CASES-1030 and the cube's "win the game" /
 * "loses the game" cards), each labelled by the honest label solver (HonestLabel.Seat on the step-1 counter kill spec,
 * K = 8 belief worlds, hideOwnLibrary) at the combo seat's main phase 1 priority, with an expected verdict:
 * <ul>
 * <li>PROVEN: the label is "win" (a forced line this turn or next in every world);</li>
 * <li>NOT_PROVEN: an advantage, lock or engine with no same-turn or next-turn kill, or a negative control: the label is
 * not "win" (and the label finishes: a loop with no gain ceiling must stop).</li>
 * </ul>
 * The puzzle text names cards (it is a position); nothing in the solver does. Rows print as "COV name expected got ..."
 * for the pass/fail table; -Dsc12.cov=name1,name2 runs a subset.
 */
/** live-fixes-1107: the puzzle-frame helpers only (P, V, n, j); the coverage suite needs the research HonestLabel. */
public class WinConditionCoverageTest extends forge.ai.AITest {

    /** The step-1 counter kill spec (CP-1029 kill check, repro/kill-spec.json). */
    static final String KILL_SPEC = "{\"lossCfg\":{\"budget\":\"counter\",\"budgetMs\":\"2000\",\"cycles\":\"true\","
            + "\"engineCards\":\"12500\",\"engineMs\":\"750\",\"enginePathCards\":\"20000\",\"engineSteps\":\"500\","
            + "\"evalReserveMs\":\"500\",\"evalSteps\":\"500\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"500\"},"
            + "\"lossWallMs\":0,\"redraw\":\"belief\",\"worlds\":8,\"sequentialAi\":true,\"aiTimeoutSec\":600,"
            + "\"solverCfg\":{\"budget\":\"counter\",\"budgetMs\":\"4000\",\"cycles\":\"true\",\"engineCards\":\"25000\","
            + "\"engineMs\":\"2000\",\"enginePathCards\":\"20000\",\"engineSteps\":\"1000\",\"evalReserveMs\":\"1000\","
            + "\"evalSteps\":\"1250\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"1000\"}}";

    enum V { PROVEN, NOT_PROVEN }

    /** A puzzle: the combo seat is "human" (seat 0), active in its main phase 1; the opponent "ai" has no hand. */
    static final class P {
        final String name;
        final V expect;
        final String why;
        final Map<String, String> kv = new LinkedHashMap<>();

        P(String name, V expect, String why) {
            this.name = name;
            this.expect = expect;
            this.why = why;
            kv.put("turn", "7");
            kv.put("activeplayer", "human");
            kv.put("activephase", "MAIN1");
            kv.put("humanlife", "20");
            kv.put("ailife", "20");
            kv.put("humanlandsplayed", "0");
            kv.put("ailandsplayed", "0");
            kv.put("humanhand", "");
            kv.put("humanbattlefield", "");
            kv.put("humangraveyard", "");
            kv.put("humanexile", "");
            kv.put("humanlibrary", n("Island", 10));
            kv.put("aihand", "");
            kv.put("aibattlefield", "");
            kv.put("aigraveyard", "");
            kv.put("aiexile", "");
            kv.put("ailibrary", n("Forest", 20));
        }

        P k(String key, String value) {
            kv.put(key, value);
            return this;
        }

        String frame() {
            final StringBuilder sb = new StringBuilder("[state]\n");
            for (Map.Entry<String, String> e : kv.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static String n(String card, int count) {
        return String.join(";", Collections.nCopies(count, card));
    }

    static String j(String... parts) {
        final List<String> xs = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                xs.add(p);
            }
        }
        return String.join(";", xs);
    }
}
