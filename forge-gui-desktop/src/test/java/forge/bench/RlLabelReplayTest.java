package forge.bench;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.HonestLabel;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlReplayLabeller;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlTape;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * Lane bvw-plr-1023 (BVW P-LR, label during replay): the honest-mode refusal, replay identity under labelling (and a
 * mutant labeller that moves the game, which the digest check must catch and whose rows must be void), and the label
 * row schema (MXL1 value records + index rows). The identity and schema tests play real Forge games (record mode with
 * fair naming, Default and CubeCombo recorders): run inside a broker test lease, cwd = forge-gui with res/.
 *
 * <p>Fixtures: the 24 TRAIN decks of {@code rl/decks} in a temporary {@code $RL_ROOT} (as RlActorBenchTest); seeds
 * {@code -Drl.seed} (default 719820000 + i); {@code -Drl.labelGames} games (default 4).
 */
public class RlLabelReplayTest {

    static Path root, bank, out;
    static CardIndex index;
    static List<JsonObject> tapes = new ArrayList<>();
    static Path tapeFile;

    static JsonObject honest() {
        final JsonObject o = new JsonObject();
        o.addProperty("redraw", "belief");
        o.addProperty("worlds", 2);
        final JsonObject sc = new JsonObject();
        sc.addProperty("hideOwnLibrary", "true");
        sc.addProperty("budgetMs", "1500");
        sc.addProperty("engineMs", "400");
        sc.addProperty("evalReserveMs", "500");
        sc.addProperty("wallGraceMs", "500");
        o.add("solverCfg", sc);
        o.add("lossCfg", sc.deepCopy());
        return o;
    }

    // ------------------------------------------------------------------------------------------------ 1. refusal

    @Test
    public void refusesUnlessBeliefRedrawAndHiddenOwnLibrary() {
        HonestLabel.Spec.parse(honest()); // the honest spec is accepted
        final String[][] bad = {{"redraw", null}, {"redraw", "off"}, {"redraw", "real"}, {"redraw", "remainder"}};
        for (String[] b : bad) {
            final JsonObject o = honest();
            if (b[1] == null) {
                o.remove(b[0]);
            } else {
                o.addProperty(b[0], b[1]);
            }
            final IllegalArgumentException e = Assert.expectThrows(IllegalArgumentException.class,
                    () -> HonestLabel.Spec.parse(o));
            Assert.assertTrue(e.getMessage().startsWith("refused:"), e.getMessage());
        }
        for (String cfg : new String[] {"solverCfg", "lossCfg"}) {
            for (String v : new String[] {null, "false", ""}) {
                final JsonObject o = honest();
                if (v == null) {
                    o.getAsJsonObject(cfg).remove("hideOwnLibrary");
                } else {
                    o.getAsJsonObject(cfg).addProperty("hideOwnLibrary", v);
                }
                final IllegalArgumentException e = Assert.expectThrows(IllegalArgumentException.class,
                        () -> HonestLabel.Spec.parse(o));
                Assert.assertTrue(e.getMessage().startsWith("refused:") && e.getMessage().contains(cfg), e.getMessage());
            }
        }
        // the runner's config: a dishonest label block is a bad config (exit 2), before anything is played
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("mode", "replay");
        final JsonObject l = honest();
        l.addProperty("redraw", "off");
        l.addProperty("out", "/tmp/never");
        cfg.add("label", l);
        Assert.expectThrows(IllegalArgumentException.class, () -> RlActorBench.parse(cfg));
        // a spec turned dishonest after parsing is refused again where solvers are built
        final HonestLabel.Spec s = HonestLabel.Spec.parse(honest());
        s.redraw = "off";
        Assert.expectThrows(IllegalArgumentException.class, () -> new HonestLabel.Seat(s));
        final JsonObject t = new JsonObject();
        t.addProperty("game_uid", "1");
        t.addProperty("turns", 9);
        Assert.expectThrows(IllegalArgumentException.class, () -> new RlReplayLabeller(s, 3, t, "test", null, null, null, 2));
        final HonestLabel.Spec s2 = HonestLabel.Spec.parse(honest());
        s2.solverCfg.addProperty("hideOwnLibrary", "false");
        Assert.expectThrows(IllegalArgumentException.class, () -> new RlReplayLabeller(s2, 3, t, "test", null, null, null, 2));
        // the spec's identity: its sha moves with any key
        Assert.assertEquals(HonestLabel.Spec.parse(honest()).sha(), HonestLabel.Spec.parse(honest()).sha());
        final JsonObject k3 = honest();
        k3.addProperty("worlds", 3);
        Assert.assertNotEquals(HonestLabel.Spec.parse(k3).sha(), HonestLabel.Spec.parse(honest()).sha());
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBench.ensureBooted();
        final Path decks = forge.bench.rl.RlWireSchemaTest.res().resolve("decks");
        root = Files.createTempDirectory("rl-label-root-");
        bank = root.resolve("data/train/t24");
        Files.createDirectories(bank.resolve("decks"));
        final JsonArray td = new JsonArray();
        final TreeSet<String> names = new TreeSet<>();
        final List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(decks)) {
            s.filter(p -> p.toString().endsWith(".dck")).sorted().forEach(files::add);
        }
        for (Path f : files) {
            final String n = f.getFileName().toString();
            Files.copy(f, bank.resolve("decks").resolve(n));
            td.add(RlActorBenchTest.entry(n, f));
            final Deck d = DeckSerializer.fromFile(f.toFile());
            for (Map.Entry<PaperCard, Integer> e : d.getMain()) names.add(e.getKey().getName());
        }
        RlActorBenchTest.manifest(bank, "t24", "train", td);
        final String ci = System.getProperty("rl.cardIndex");
        if (ci != null) {
            index = CardIndex.load(Paths.get(ci));
        } else {
            final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
            int i = 2;
            for (String n : names) sb.append(n).append('\t').append(i++).append('\n');
            index = CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        out = Files.createTempDirectory("rl-label-out-");
        // fresh games with fair naming: Default recorders (record mode) and CubeCombo Forge seats (train mode, no RL seat;
        // recorders refuse the combo profile) alternate
        final int n = Integer.getInteger("rl.labelGames", 4);
        final long seed0 = Long.getLong("rl.seed", 719820000L);
        for (int i = 0; i < n; i++) {
            final String mode = i % 2 == 0 ? "record" : "train";
            final String c = i % 2 == 0 ? "record" : "forge:CubeCombo";
            final RlActorBench.Cfg rc = cfg(mode);
            rc.obsSchema = 2;
            final JsonObject g = RlFakeRun.schedule(bank, i, 1, seed0, new String[] {c, c}, false, false, 5000L).get(0);
            g.addProperty("fair_naming", true);
            g.addProperty("obs_schema", 2);
            final RlActorBench.Played p = RlActorBench.play(rc, mode, g, new RlFeaturizer(index),
                    RlActorBench.nullRecorder(), null, "test");
            Assert.assertNull(p.guardError, String.valueOf(p.guardError));
            Assert.assertEquals(p.tape.get("fair_naming").getAsBoolean(), true);
            tapes.add(p.tape);
        }
        tapeFile = out.resolve("tapes/tapes-00000.jsonl.gz");
        try (RlTape t = new RlTape(out.resolve("tapes"), 1000)) {
            for (JsonObject j : tapes) {
                t.write(j);
            }
        }
    }

    static RlActorBench.Cfg cfg(final String mode) {
        final RlActorBench.Cfg c = new RlActorBench.Cfg();
        c.mode = mode;
        c.rlRoot = root.toString();
        c.aiTimeoutSec = 600;
        return c;
    }

    static RlActorBench.Cfg replayCfg(final Path labels, final int turns) {
        final RlActorBench.Cfg c = cfg("replay");
        c.replayDir = out.resolve("tapes").toString();
        c.replayOut = labels.resolve("replay.jsonl").toString();
        if (labels != null) {
            final JsonObject l = honest();
            l.addProperty("turns", turns);
            l.addProperty("out", labels.toString());
            c.labelSpec = HonestLabel.Spec.parse(l);
            c.labelTurns = turns;
            c.labelOut = labels.toString();
        }
        return c;
    }

    static List<JsonObject> jsonl(final Path dir, final String prefix) throws Exception {
        final List<JsonObject> rows = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().startsWith(prefix)).sorted()::iterator) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        rows.add(JsonParser.parseString(line).getAsJsonObject());
                    }
                }
            }
        }
        return rows;
    }

    /** Every MXL1 record of every label file in {@code dir}: {header, payload}. */
    static List<Object[]> records(final Path dir) throws Exception {
        final List<Object[]> recs = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".mxl.gz")).sorted()::iterator) {
                final ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) { // concatenated members
                    in.transferTo(bo);
                }
                final ByteBuffer b = ByteBuffer.wrap(bo.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                while (b.hasRemaining()) {
                    final byte[] magic = new byte[4];
                    b.get(magic);
                    Assert.assertEquals(new String(magic, StandardCharsets.US_ASCII), "MXL1");
                    final byte[] h = new byte[b.getInt()];
                    b.get(h);
                    final byte[] pl = new byte[b.getInt()];
                    b.get(pl);
                    recs.add(new Object[] {JsonParser.parseString(new String(h, StandardCharsets.UTF_8)).getAsJsonObject(),
                            pl});
                }
            }
        }
        return recs;
    }

    // ------------------------------------------------------------------------------------------------ 2. identity

    @Test
    public void labelledReplayKeepsEveryDigestAndAMovingLabellerIsCaught() throws Exception {
        // plain replay: every tape replays to its digest
        final Path plain = Files.createTempDirectory(out, "plain-");
        final RlActorBench.Cfg pc = replayCfg(plain, 3);
        pc.labelSpec = null;
        Assert.assertEquals(RlActorBench.replay(pc, index, "test"), 0, "plain replay");
        // labelled replay (T = 3): every digest equal, every game's rows written, some positions solved
        final Path lab = Files.createTempDirectory(out, "labels-");
        Assert.assertEquals(RlActorBench.replay(replayCfg(lab, 3), index, "test"), 0, "labelled replay");
        final List<JsonObject> games = jsonl(lab, "games-");
        Assert.assertEquals(games.size(), tapes.size());
        int rows = 0, solved = 0;
        for (JsonObject g : games) {
            Assert.assertTrue(g.get("equal").getAsBoolean(), "digest of " + g.get("game_uid"));
            Assert.assertEquals(g.get("digest_replay"), g.get("digest_tape"));
            Assert.assertEquals(g.get("rows_void").getAsInt(), 0);
            rows += g.get("rows").getAsInt();
            solved += g.get("solved").getAsInt();
        }
        System.err.println("[bvw-label test] games " + games.size() + " rows " + rows + " solved " + solved);
        Assert.assertTrue(rows > 0, "no label rows");
        Assert.assertTrue(solved > 0, "no position reached a solver check");
        Assert.assertEquals(records(lab).size(), rows);
        Assert.assertEquals(jsonl(lab, "index-").size(), rows);
        // the mutant: a labeller that moves the live game (rotates each library) must void the game's rows
        final Path mut = Files.createTempDirectory(out, "mutant-");
        RlReplayLabeller.TEST_PROBE = g -> {
            for (Player p : g.getPlayers()) {
                final List<forge.game.card.Card> lib = new ArrayList<>(p.getCardsIn(ZoneType.Library));
                if (lib.size() > 1) {
                    Collections.rotate(lib, 1); // the bottom card to the top (not an involution: repeats keep moving it)
                    p.getZone(ZoneType.Library).setCards(lib);
                }
            }
        };
        final int rc;
        try {
            rc = RlActorBench.replay(replayCfg(mut, 3), index, "test");
        } finally {
            RlReplayLabeller.TEST_PROBE = null;
        }
        Assert.assertEquals(rc, 4, "a labeller that moves the game must fail the replay (exit 4)");
        int unequal = 0, written = 0, voided = 0;
        for (JsonObject g : jsonl(mut, "games-")) {
            if (!g.get("equal").getAsBoolean()) {
                unequal++;
                Assert.assertEquals(g.get("rows").getAsInt(), 0, "a moved game's rows must not be written");
                voided += g.get("rows_void").getAsInt();
            }
            written += g.get("rows").getAsInt();
        }
        Assert.assertTrue(unequal > 0, "the digest check missed the moving labeller");
        Assert.assertTrue(voided > 0);
        Assert.assertEquals(records(mut).size(), written);
    }

    // ------------------------------------------------------------------------------------------------ 3. row schema

    @Test
    public void labelRowsAreValueRecordsWithHonestFrames() throws Exception {
        final Path lab = Files.createTempDirectory(out, "schema-");
        Assert.assertEquals(RlActorBench.replay(replayCfg(lab, 3), index, "test"), 0);
        final List<Object[]> recs = records(lab);
        final List<JsonObject> idx = jsonl(lab, "index-");
        Assert.assertTrue(recs.size() > 0);
        Assert.assertEquals(idx.size(), recs.size());
        final Map<String, Integer> labels = new TreeMap<>();
        for (int i = 0; i < recs.size(); i++) {
            final JsonObject h = (JsonObject) recs.get(i)[0];
            final byte[] pl = (byte[]) recs.get(i)[1];
            for (String k : new String[] {"kind", "schema", "split", "game_uid", "seed", "seat", "dec_idx", "turn",
                    "phase", "stack", "family", "C", "obs", "prior", "turns_before_end", "tape_turns", "tape_reason",
                    "tape_result", "label", "check", "gate", "p_win", "p_loss", "how", "turns_to_end", "spec_sha",
                    "jar_sha", "decks", "deck_sha", "controllers"}) {
                Assert.assertTrue(h.has(k), "header key " + k + " in " + h);
            }
            Assert.assertEquals(h.get("kind").getAsString(), "value");
            Assert.assertEquals(h.get("schema").getAsString(), RlReplayLabeller.SCHEMA);
            Assert.assertEquals(h.get("split").getAsString(), "TRAIN");
            final int tbe = h.get("turns_before_end").getAsInt();
            Assert.assertTrue(tbe >= 0 && tbe < 3, "turns_before_end " + tbe);
            Assert.assertEquals(h.get("tape_turns").getAsInt() - h.get("turn").getAsInt(), tbe);
            final String label = h.get("label").getAsString();
            Assert.assertTrue(label.equals("win") || label.equals("loss") || label.equals("unresolved"), label);
            labels.merge(label, 1, Integer::sum);
            for (String p : new String[] {"p_win", "p_loss"}) {
                if (!h.get(p).isJsonNull()) {
                    final double v = h.get(p).getAsDouble();
                    Assert.assertTrue(v >= 0 && v <= 1, p + " " + v);
                }
            }
            if (!label.equals("unresolved")) {
                Assert.assertFalse(h.get("turns_to_end").isJsonNull(), "a proven label has turns_to_end");
                Assert.assertFalse(h.get("how").getAsString().equals("unresolved"), "a proven label has a how: " + h);
            }
            Assert.assertEquals(h.get("family").getAsInt(), RlSchema.F_PRIORITY);
            if (h.get("stack").getAsInt() > 0) {
                Assert.assertEquals(h.get("check").getAsString(), "none", "a solver check with a non-empty stack");
            }
            // the frame: the seat's own observation (a v(o) leaf frame: PRIORITY, PASS only), never the privileged block
            final RlWire.Decide d = RlWire.decodeDecide(pl, h.get("obs").getAsInt());
            Assert.assertEquals(d.mode, RlSchema.M_SINGLE);
            Assert.assertEquals(d.family, RlSchema.F_PRIORITY);
            Assert.assertEquals(d.C, 1);
            Assert.assertEquals(d.turn, h.get("turn").getAsInt());
            Assert.assertEquals(d.teacher.length, 0);
            Assert.assertFalse(d.hasPriv(), "privileged block in a label frame");
            Assert.assertEquals(Long.toUnsignedString(d.gameUid), h.get("game_uid").getAsString());
            Assert.assertEquals(d.decIdx, h.get("dec_idx").getAsInt());
            Assert.assertEquals(d.seat, h.get("seat").getAsInt());
            Assert.assertEquals(d.C, h.get("C").getAsInt());
            Assert.assertEquals(h.getAsJsonArray("prior").size(), d.C);
            double sum = 0;
            for (JsonElement e : h.getAsJsonArray("prior")) sum += e.getAsDouble();
            Assert.assertEquals(sum, 1.0, 1e-9);
            // the index row is the same position
            Assert.assertEquals(idx.get(i).get("game_uid"), h.get("game_uid"));
            Assert.assertEquals(idx.get(i).get("dec_idx"), h.get("dec_idx"));
            Assert.assertEquals(idx.get(i).get("label"), h.get("label"));
        }
        System.err.println("[bvw-label test] rows " + recs.size() + " by label " + labels);
    }
}
