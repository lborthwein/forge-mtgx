package forge.ai.dp;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.GameRules;
import forge.game.GameType;

/** decisionpoint-1102 PR-A: the interface's pure parts (Forge booted by AITest: GameRules needs the static data). */
public class DecisionPointsTest extends AITest {
    @AfterMethod
    public void reset() {
        DecisionPoints.setObserver(null);
    }

    private static DecisionPoint p(final Kind k, final List<?> opts, final int min, final int max, final Object def) {
        return DecisionPoints.point(k, "test", null, null, opts, min, max, def);
    }

    @Test
    public void nothingInstalledReturnsForgesAnswerItself() {
        final Object def = new Object();
        assertSame(DecisionPoints.resolve(p(Kind.CHOICE, null, -1, -1, def), null, null), def);
    }

    @Test
    public void legalChecksOptionsTheEngineGave() {
        final List<String> o = List.of("a", "b", "c");
        assertTrue(DecisionPoints.legal(p(Kind.SELECT, o, 1, 1, "a"), "b"));
        assertFalse(DecisionPoints.legal(p(Kind.SELECT, o, 1, 1, "a"), "z"));
        assertTrue(DecisionPoints.legal(p(Kind.SELECT, o, 1, 2, List.of("a")), List.of("b", "c")));
        assertFalse(DecisionPoints.legal(p(Kind.SELECT, o, 1, 1, List.of("a")), List.of("b", "c")));
        assertTrue(DecisionPoints.legal(p(Kind.ARRANGE, o, -1, -1, o), List.of("c", "a", "b")));
        assertFalse(DecisionPoints.legal(p(Kind.ARRANGE, o, -1, -1, o), List.of("c", "a")));
        assertTrue(DecisionPoints.legal(p(Kind.ARRANGE, o, -1, -1, null),
                ImmutablePair.of(List.of("a"), List.of("b", "c"))));
        assertTrue(DecisionPoints.legal(p(Kind.NUMBER, List.of(), 0, 3, 1), 3));
        assertFalse(DecisionPoints.legal(p(Kind.NUMBER, List.of(), 0, 3, 1), 4));
        assertTrue(DecisionPoints.legal(p(Kind.NUMBER, null, 0, 3, 1), 2));
        assertFalse(DecisionPoints.legal(p(Kind.NUMBER, null, 0, 3, 1), -1));
        // no option list: only Forge's own answer
        assertTrue(DecisionPoints.legal(p(Kind.PRIORITY, null, -1, -1, "x"), "x"));
        assertFalse(DecisionPoints.legal(p(Kind.PRIORITY, null, -1, -1, "x"), "y"));
    }

    @Test
    public void chainAndObserver() {
        final List<String> seen = new ArrayList<>();
        DecisionPoints.setObserver((dp, a, by) -> seen.add(dp.ctx() + "=" + a + "/" + by));
        final List<String> o = List.of("a", "b");
        assertEquals(DecisionPoints.resolve(p(Kind.SELECT, o, 1, 1, "a"), null, null), "a");
        assertEquals(DecisionPoints.resolve(p(Kind.SELECT, o, 1, 1, "a"), (dp, cur) -> "b", null), "b");
        assertEquals(DecisionPoints.resolve(p(Kind.SELECT, o, 1, 1, "a"), null, (dp, cur) -> "b"), "b");
        assertEquals(DecisionPoints.resolve(p(Kind.SELECT, o, 1, 1, "a"), null, (dp, cur) -> "zz"), "a");
        assertEquals(seen, List.of("test=a/forge", "test=b/cac", "test=b/answerer", "test=a/refused"));
        DecisionPoints.note(null, Kind.CHOICE, "solverAsk", 2, "solver");
        assertEquals(seen.get(4), "solverAsk=null/solver");
    }

    @Test
    public void cheatShuffleIsRefused() {
        final GameRules r = new GameRules(GameType.Constructed);
        DecisionPoints.assertNoCheatShuffle(r, "t");
        r.setAllowCheatShuffle(true);
        expectThrows(IllegalStateException.class, () -> DecisionPoints.assertNoCheatShuffle(r, "t"));
    }

    @Test
    public void censusWritesCounts() throws Exception {
        final Path out = Files.createTempDirectory("dp").resolve("census.json");
        final CensusObserver c = new CensusObserver(out);
        DecisionPoints.setObserver(c);
        DecisionPoints.resolve(p(Kind.SELECT, List.of("a", "b"), 1, 1, "a"), null, null);
        DecisionPoints.note(null, Kind.CHOICE, "x", -1, "solver");
        c.write();
        final String s = Files.readString(out);
        assertTrue(s.contains("\"live|norow|-|SELECT|test|forge|n2+\": 1"), s);
        assertTrue(s.contains("\"live|norow|-|CHOICE|x|solver|n?\": 1"), s);
    }

    @Test
    public void codecIsCanonical() {
        assertEquals(DpCodec.encode(null, List.of(1, true, "s", Kind.ARRANGE)).toString(), "[1,true,\"s\",\"ARRANGE\"]");
        assertEquals(DpCodec.encode(null, ImmutablePair.of(List.of(1), List.of())).toString(), "[[1],[]]");
    }
}
