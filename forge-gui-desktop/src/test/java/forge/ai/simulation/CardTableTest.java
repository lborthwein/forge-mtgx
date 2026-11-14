package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane searchspeed-1113 (solver key cardTable; card names are test data only). A saved card-model table is read only by
 * the build that wrote it; what it holds is what the canonical sandbox learns; a model does not depend on the position
 * the card is first seen in; a card the table lacks is learned canonically, saved once and loaded by the next start.
 */
public class CardTableTest extends AITest {
    static final List<String> POOL = Arrays.asList("Chromatic Star", "Sensei's Divining Top", "Dark Ritual", "Brainstorm",
            "Lightning Bolt", "Llanowar Elves", "Sol Ring", "Counterspell", "Grizzly Bears", "Demonic Tutor", "Swords to Plowshares",
            "Mox Opal", "Oblivion Ring", "Jace Beleren", "Wasteland", "Splinter Twin", "Deceiver Exarch", "Kiki-Jiki, Mirror Breaker");
    static final String[] VARIANTS = {"", "sl|tc|settle|"};

    private static String shape(final ACard a) {
        return new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(a.instance(0));
    }

    private static ForcedWinCanon.Table generated(final List<String> names) {
        final ForcedWinCanon.Table tb = ForcedWinCanon.newTable(POOL);
        for (String v : VARIANTS) {
            for (String n : names) {
                Assert.assertTrue(ForcedWinCanon.generate(tb, v, n), v + n);
            }
        }
        return tb;
    }

    private static Path saved(final ForcedWinCanon.Table tb) throws Exception {
        final Path p = Files.createTempFile("card-table", ".json");
        Files.write(p, tb.toJson("test").getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(Path.of(p + ".add.jsonl"));
        return p;
    }

    private static Game game(final String hand, final String field, final String oppField) {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame(hand, field, 20, "", oppField, 20, "human"));
    }

    @Test
    public void theCanonicalSandboxGivesATargetedSpellSomethingToActOn() {
        final ForcedWinCanon.Table tb = generated(POOL);
        final ForcedWinCanon.Base b = tb.base();
        System.err.println("CANON FRAME\n" + b.frame);
        for (Player p : b.g.getPlayers()) {
            Assert.assertTrue(p.getCreaturesInPlay().size() >= 1, "a creature in play for " + p);
            Assert.assertTrue(p.getCardsIn(ZoneType.Graveyard).size() >= 3, "graveyard of " + p);
            Assert.assertTrue(p.getCardsIn(ZoneType.Hand).size() >= 3, "hand of " + p);
            Assert.assertTrue(p.getLandsInPlay().size() >= 5, "lands of " + p);
        }
        Assert.assertEquals(b.me.getCardsIn(ZoneType.Library).size(), b.pool.size());
        for (String name : new String[] {"Swords to Plowshares", "Lightning Bolt", "Demonic Tutor", "Oblivion Ring"}) {
            final ACard a = tb.entries.get(name).card;
            System.err.println("CANON " + name + " " + shape(a));
            Assert.assertFalse(a.fromHand.isEmpty(), name);
            Assert.assertFalse(a.fromHand.get(0).unplayable, name + ": played in the canonical sandbox");
        }
    }

    @Test
    public void theKeyIsOffByDefault() {
        Assert.assertNull(ForcedWinSolver.Config.fromProps(k -> null).cardTable);
        Assert.assertEquals(ForcedWinSolver.Config.fromProps(k -> "cardTable".equals(k) ? "/x/t.json" : null).cardTable, "/x/t.json");
        Assert.assertNull(new ForcedWinModel.Reader().table);
        final Game g = game("Lightning Bolt", "Mountain", "Plains");
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(k -> null));
        Assert.assertNull(s.reader.table);
        Assert.assertNull(s.oppReader.table);
    }

    @Test
    public void aTableOfAnotherBuildIsRefused() throws Exception {
        final Path p = saved(generated(POOL.subList(0, 6)));
        try {
            ForcedWinCanon.forgetForTest();
            Assert.assertNotNull(ForcedWinCanon.open(p.toString()), "this build's own table");
            // the same file read by another build (a different jar): refused, and a solver with the key is as without it
            ForcedWinCanon.forgetForTest();
            ForcedWinCanon.setStampForTest("0000another-build:0000");
            Assert.assertNull(ForcedWinCanon.open(p.toString()));
            final Game g = game("Lightning Bolt", "Mountain", "Plains");
            final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0),
                    ForcedWinSolver.Config.fromProps(k -> "cardTable".equals(k) ? p.toString() : null));
            Assert.assertNull(s.reader.table);
            Assert.assertNull(s.oppReader.table);
            // a missing file is refused too
            Assert.assertNull(ForcedWinCanon.open(p + ".nope"));
        } finally {
            ForcedWinCanon.setStampForTest(null);
            ForcedWinCanon.forgetForTest();
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void aLoadedModelIsTheCanonicalLearn() throws Exception {
        final ForcedWinCanon.Table first = generated(POOL);
        final Path p = saved(first);
        try {
            ForcedWinCanon.forgetForTest();
            final ForcedWinCanon.Table loaded = ForcedWinCanon.open(p.toString());
            Assert.assertNotNull(loaded);
            Assert.assertEquals(loaded.entries.size(), POOL.size() * VARIANTS.length);
            final ForcedWinCanon.Table again = generated(POOL); // a second canonical learn of every key, from scratch
            for (String k : again.entries.keySet()) {
                Assert.assertEquals(JsonParser.parseString(shape(loaded.entries.get(k).card)),
                        JsonParser.parseString(shape(again.entries.get(k).card)), k);
                Assert.assertEquals(loaded.entries.get(k).hash, again.entries.get(k).hash, k);
            }
            // a solver with the key reads the card from the table: no sandbox, and the same model
            final Game g = game("Lightning Bolt;Demonic Tutor", "Mountain;Swamp", "Plains");
            final Player me = g.getPlayers().get(0);
            final ForcedWinSolver s = new ForcedWinSolver(me, ForcedWinSolver.Config.fromProps(k -> "cardTable".equals(k) ? p.toString() : null));
            Assert.assertSame(s.reader.table, loaded);
            final long learnedBefore = loaded.learned.get();
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                final ACard a = s.reader.card(g, me, c);
                Assert.assertEquals(a.id, c.getId());
                Assert.assertEquals(JsonParser.parseString(shape(a)), JsonParser.parseString(shape(loaded.entries.get(c.getName()).card)));
            }
            Assert.assertEquals(loaded.learned.get(), learnedBefore, "no card was learned again");
            Assert.assertEquals(s.reader.probeStats.probes, 0);
        } finally {
            ForcedWinCanon.forgetForTest();
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void aModelDoesNotDependOnThePositionAndAMissingCardIsSavedOnce() throws Exception {
        final Path p = saved(generated(POOL.subList(0, 4)));
        final Path add = Path.of(p + ".add.jsonl");
        try {
            ForcedWinCanon.forgetForTest();
            final ForcedWinCanon.Table tb = ForcedWinCanon.open(p.toString());
            final int n0 = tb.entries.size();
            // the same spells first seen in two positions: one with nothing to target or find, one with both
            final Game bare = game("Swords to Plowshares;Demonic Tutor;Lightning Bolt", "Plains;Swamp;Mountain", "Plains");
            final Game rich = game("Swords to Plowshares;Demonic Tutor;Lightning Bolt", "Plains;Swamp;Mountain;Grizzly Bears",
                    "Grizzly Bears;Llanowar Elves");
            final List<String> shapes = new ArrayList<>();
            for (Game g : new Game[] {bare, rich}) {
                final Player me = g.getPlayers().get(0);
                final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
                r.table = tb;
                final StringBuilder sb = new StringBuilder();
                for (Card c : me.getCardsIn(ZoneType.Hand)) {
                    sb.append(shape(r.card(g, me, c))).append('\n');
                }
                shapes.add(sb.toString());
            }
            Assert.assertEquals(shapes.get(0), shapes.get(1));
            Assert.assertEquals(tb.entries.size(), n0 + 3, "three cards learned at run time, once each");
            Assert.assertEquals(tb.learned.get(), 3);
            final List<String> lines = Files.readAllLines(add);
            Assert.assertEquals(lines.size(), 3);
            // a line of another build is not loaded; the next start loads this build's three
            Files.write(add, (lines.get(0).replace(ForcedWinCanon.stamp(), "0000another-build:0000") + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            ForcedWinCanon.forgetForTest();
            final ForcedWinCanon.Table next = ForcedWinCanon.open(p.toString());
            Assert.assertEquals(next.entries.size(), n0 + 3);
            final Game g = bare;
            final Player me = g.getPlayers().get(0);
            final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
            r.table = next;
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                r.card(g, me, c);
            }
            Assert.assertEquals(next.learned.get(), 0, "read from the additions, not learned again");
            Assert.assertEquals(Files.readAllLines(add).size(), 4, "nothing appended twice");
        } finally {
            ForcedWinCanon.forgetForTest();
            Files.deleteIfExists(p);
            Files.deleteIfExists(add);
        }
    }

    /**
     * ct2-live-1114: cardTable=auto loads the file named for the jar's sha and the flags from the jar's directory, learns
     * a miss in memory only (nothing written, no additions read), and prints a summary; no such file is off with a reason.
     */
    @Test
    public void autoLoadsTheTableNamedForThisBuildAndNeverWrites() throws Exception {
        final Path dir = Files.createTempDirectory("card-table-auto");
        ForcedWinCanon.forgetForTest();
        ForcedWinCanon.setStampForTest("0123456789abcdef0123456789abcdef:fedcba9876543210");
        ForcedWinCanon.setAutoDirForTest(dir);
        try {
            Assert.assertEquals(ForcedWinCanon.autoName(), "card-table-0123456789abcdef-fedcba9876543210.json");
            Assert.assertEquals(ForcedWinSolver.Config.fromProps(k -> "cardTable".equals(k) ? "auto" : null).cardTable, "auto");
            // no file: off, with the reason in the summary line
            Assert.assertNull(ForcedWinCanon.open("auto"));
            final String offLine = ForcedWinCanon.summaryLine("auto");
            Assert.assertTrue(offLine.startsWith("[card-table] summary key=auto off reason=cardTable=auto: no card-table-0123456789abcdef-"), offLine);
            Assert.assertNull(ForcedWinCanon.summaryLine(null));
            // the file next to the jar: loaded
            final Path p = dir.resolve(ForcedWinCanon.autoName());
            Files.write(p, generated(POOL.subList(0, 4)).toJson("test").getBytes(StandardCharsets.UTF_8));
            final Path add = Path.of(p + ".add.jsonl");
            ForcedWinCanon.forgetForTest();
            final ForcedWinCanon.Table tb = ForcedWinCanon.open("auto");
            Assert.assertNotNull(tb);
            Assert.assertNull(tb.additions, "auto never writes");
            final int n0 = tb.entries.size();
            final Game g = game("Swords to Plowshares;Demonic Tutor;Lightning Bolt", "Plains;Swamp;Mountain", "Plains");
            final Player me = g.getPlayers().get(0);
            final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
            r.table = tb;
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                r.card(g, me, c);
            }
            Assert.assertEquals(tb.learned.get(), 3, "three pool cards the file lacks, learned canonically");
            Assert.assertEquals(tb.entries.size(), n0 + 3, "kept in memory for the JVM's readers");
            Assert.assertFalse(Files.exists(add), "no additions file written");
            try (java.util.stream.Stream<Path> ls = Files.list(dir)) {
                Assert.assertEquals(ls.count(), 1L, "the directory holds the table only");
            }
            final String line = ForcedWinCanon.summaryLine("auto");
            Assert.assertTrue(line.startsWith("[card-table] summary key=auto table=" + ForcedWinCanon.autoName() + " loaded=" + n0), line);
            Assert.assertTrue(line.contains(" misses=3 ") && line.contains(" additions=off") && line.endsWith(" refusal=none"), line);
            // classes in a directory (no jar hash): auto has no name and is off
            ForcedWinCanon.forgetForTest();
            ForcedWinCanon.setStampForTest("dir:fedcba9876543210");
            Assert.assertNull(ForcedWinCanon.autoName());
            Assert.assertNull(ForcedWinCanon.open("auto"));
        } finally {
            ForcedWinCanon.setStampForTest(null);
            ForcedWinCanon.setAutoDirForTest(null);
            ForcedWinCanon.forgetForTest();
            try (java.util.stream.Stream<Path> ls = Files.list(dir)) {
                for (Path x : (Iterable<Path>) ls::iterator) {
                    Files.deleteIfExists(x);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    /**
     * cardtable2-1114: a card that is not in the table's pool (a card a booster-opening effect made, in read R3) is
     * learned exactly as without the key, kept by the reader only, and never appended.
     */
    @Test
    public void aCardFromOutsideThePoolIsLearnedAsWithoutTheKeyAndNotAppended() throws Exception {
        final Path p = saved(generated(POOL));
        final Path add = Path.of(p + ".add.jsonl");
        try {
            ForcedWinCanon.forgetForTest();
            final ForcedWinCanon.Table tb = ForcedWinCanon.open(p.toString());
            final int n0 = tb.entries.size();
            final String hand = "Giant Growth;Shock;Lightning Bolt";
            final Game g = game(hand, "Forest;Mountain;Grizzly Bears", "Grizzly Bears");
            final Game same = game(hand, "Forest;Mountain;Grizzly Bears", "Grizzly Bears");
            final Player me = g.getPlayers().get(0), meSame = same.getPlayers().get(0);
            final ForcedWinModel.Reader with = new ForcedWinModel.Reader(), without = new ForcedWinModel.Reader();
            with.table = tb;
            final List<Card> a = new ArrayList<>(me.getCardsIn(ZoneType.Hand)), b = new ArrayList<>(meSame.getCardsIn(ZoneType.Hand));
            for (int i = 0; i < a.size(); i++) {
                final String name = a.get(i).getName();
                if (POOL.contains(name)) {
                    with.card(g, me, a.get(i));
                    continue;
                }
                Assert.assertFalse(tb.inPool(name), name);
                Assert.assertEquals(shape(with.card(g, me, a.get(i))), shape(without.card(same, meSame, b.get(i))), name);
            }
            Assert.assertEquals(tb.outside.get(), 2, "two cards from outside the pool");
            Assert.assertEquals(tb.learned.get(), 0, "nothing learned canonically");
            Assert.assertEquals(tb.entries.size(), n0, "nothing added to the table");
            Assert.assertFalse(Files.exists(add) && !Files.readAllLines(add).isEmpty(), "nothing appended");
        } finally {
            ForcedWinCanon.forgetForTest();
            Files.deleteIfExists(p);
            Files.deleteIfExists(add);
        }
    }

    /**
     * cardtable2-1114: a card's canonical model does not depend on what the JVM learned before it. The base position's
     * continuous effects are applied when it is built, so a card with a characteristic that holds in every zone (one
     * that is also a creature while it is not on the battlefield) reads the same whether it is learned first or last.
     */
    @Test
    public void aModelDoesNotDependOnTheOrderOfLearning() {
        final List<String> pool = new ArrayList<>(POOL);
        pool.add("Grist, the Hunger Tide");
        final List<String> back = new ArrayList<>(pool);
        java.util.Collections.reverse(back);
        final List<ForcedWinCanon.Table> tables = new ArrayList<>();
        for (List<String> order : Arrays.asList(pool, back)) {
            final ForcedWinCanon.Table tb = ForcedWinCanon.newTable(pool);
            for (String v : VARIANTS) {
                for (String n : order) {
                    Assert.assertTrue(ForcedWinCanon.generate(tb, v, n), v + n);
                }
            }
            tables.add(tb);
        }
        for (String v : VARIANTS) {
            for (String n : pool) {
                Assert.assertEquals(shape(tables.get(0).entries.get(v + n).card), shape(tables.get(1).entries.get(v + n).card), v + n);
            }
            Assert.assertTrue(tables.get(0).entries.get(v + "Grist, the Hunger Tide").card.creature,
                    "the all-zone characteristic is in force from the first learn");
        }
    }

    @Test
    public void loopProbesAreSavedPerPair() throws Exception {
        final ForcedWinCanon.Table tb = generated(POOL);
        final ForcedWinSolver.Config cfg = ForcedWinSolver.Config.fromProps(k -> "planLoops".equals(k) ? "true" : null);
        int n = 0;
        for (String name : POOL) {
            n += ForcedWinCanon.generateLoops(tb, cfg, "", name);
        }
        Assert.assertTrue(n > 0, "the pool holds cards that copy a permanent: " + tb.loops.keySet());
        // the pairs that go infinite are found in the canonical sandbox, and the ones that do not are saved as such
        int wins = 0;
        final StringBuilder all = new StringBuilder();
        for (java.util.Map.Entry<String, String[]> e : tb.loops.entrySet()) {
            all.append(e.getKey()).append('=').append(e.getValue()[1]).append(' ');
            wins += Boolean.parseBoolean(e.getValue()[1]) ? 1 : 0;
            if (e.getKey().endsWith("Kiki-Jiki, Mirror Breaker|false|Deceiver Exarch") || e.getKey().endsWith("Splinter Twin|true|Deceiver Exarch")) {
                Assert.assertEquals(e.getValue()[1], "true", e.getKey());
            }
            if (e.getKey().endsWith("|Grizzly Bears")) {
                Assert.assertEquals(e.getValue()[1], "false", e.getKey());
            }
        }
        System.err.println("LOOPS " + all);
        Assert.assertTrue(wins >= 2, "loop pairs found: " + all);
        final Path p = saved(tb);
        try {
            ForcedWinCanon.forgetForTest();
            final ForcedWinCanon.Table loaded = ForcedWinCanon.open(p.toString());
            Assert.assertEquals(loaded.loops.size(), tb.loops.size());
            for (String k : tb.loops.keySet()) {
                Assert.assertEquals(loaded.loops.get(k), tb.loops.get(k), k);
            }
        } finally {
            ForcedWinCanon.forgetForTest();
            Files.deleteIfExists(p);
        }
    }
}
