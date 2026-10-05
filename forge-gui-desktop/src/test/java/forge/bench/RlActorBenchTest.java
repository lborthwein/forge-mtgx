package forge.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.bench.rl.CardIndex;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlCandidates;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.MyRandom;

/**
 * Brief B1 tests 4-8 (lane rl-r0-b1-1005): visibility, the R-11 witness on the privileged block (with a peeking
 * mutant that must fail), the deck guard, recorder and forge-seat do-no-harm against RlSimBench policy=forge, trivial
 * asks never sent, and tape replay. Plays real Forge games: run inside a broker test lease. cwd = forge-gui with res/.
 *
 * <p>Fixtures: the 24 TRAIN decks of {@code rl/decks} (ei-1004 tiny-24) in a temporary {@code $RL_ROOT}; a card index
 * built from their names unless {@code -Drl.cardIndex=<tsv>}; seeds {@code -Drl.seed} (default 719780000 + i).
 */
public class RlActorBenchTest {

    static Path root;
    static Path bank;
    static Path evalBank;
    static CardIndex index;
    static List<String[]> pairs;
    static long seed0;

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBench.ensureBooted();
        final Path decks = forge.bench.rl.RlWireSchemaTest.res().resolve("decks");
        root = Files.createTempDirectory("rl-root-");
        bank = root.resolve("data/train/t24");
        evalBank = root.resolve("data/eval/e2");
        Files.createDirectories(bank.resolve("decks"));
        Files.createDirectories(evalBank.resolve("decks"));
        final JsonArray td = new JsonArray(), ed = new JsonArray();
        final TreeSet<String> names = new TreeSet<>();
        final List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(decks)) {
            s.filter(p -> p.toString().endsWith(".dck")).sorted().forEach(files::add);
        }
        for (Path f : files) {
            final String n = f.getFileName().toString();
            Files.copy(f, bank.resolve("decks").resolve(n));
            td.add(entry(n, f));
            final Deck d = DeckSerializer.fromFile(f.toFile());
            for (Map.Entry<PaperCard, Integer> e : d.getMain()) names.add(e.getKey().getName());
        }
        // the eval bank: two of the same decks under other names (a TRAIN-path copy of an eval deck must be refused)
        for (int i = 0; i < 2; i++) {
            final Path f = files.get(i);
            final String n = "ev" + i + ".dck";
            Files.write(evalBank.resolve("decks").resolve(n), (new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
                    + "\n// eval copy " + i + "\n").getBytes(StandardCharsets.UTF_8));
            ed.add(entry(n, evalBank.resolve("decks").resolve(n)));
        }
        manifest(bank, "t24", "train", td);
        manifest(evalBank, "e2", "eval", ed);
        final String ci = System.getProperty("rl.cardIndex");
        if (ci != null) {
            index = CardIndex.load(Paths.get(ci));
        } else {
            final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
            int i = 2;
            for (String n : names) sb.append(n).append('\t').append(i++).append('\n');
            index = CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        pairs = RlFakeRun.pairs(bank);
        seed0 = Long.getLong("rl.seed", 719780000L);
    }

    static JsonObject entry(final String file, final Path p) throws IOException {
        final JsonObject o = new JsonObject();
        o.addProperty("id", file.replace(".dck", ""));
        o.addProperty("file", "decks/" + file);
        o.addProperty("sha256", CardIndex.sha256(Files.readAllBytes(p)));
        final int k = file.lastIndexOf("-p");
        o.addProperty("pod", k > 0 ? file.substring(0, k) : file);
        return o;
    }

    static void manifest(final Path dir, final String id, final String use, final JsonArray decks) throws IOException {
        final JsonObject m = new JsonObject();
        m.addProperty("schema", "mtgx-rl-bank/1");
        m.addProperty("bank_id", id);
        m.addProperty("use", use);
        m.add("decks", decks);
        Files.write(dir.resolve("MANIFEST.json"), RlWire.canonical(m));
    }

    static RlActorBench.Cfg cfg(final String mode) {
        final RlActorBench.Cfg c = new RlActorBench.Cfg();
        c.mode = mode;
        c.rlRoot = root.toString();
        return c;
    }

    static JsonObject game(final int i, final String c0, final String c1, final boolean priv) throws IOException {
        final String[] ctl = {c0, c1};
        return RlFakeRun.schedule(bank, i + 1, seed0, ctl, false, priv, 1000L).get(i);
    }

    /** In-process server: uniform legal steps (FakeRlServer's rule) and RECORD frames checked and kept. */
    static final class LocalEndpoint implements RlSeat.Endpoint {
        final FakeRlServer checker;
        final Map<Integer, Integer> recordsByFamily = new HashMap<>();
        int decides;

        LocalEndpoint(final FakeRlServer checker) {
            this.checker = checker;
        }

        @Override
        public RlWire.Decision decide(final byte[] p) {
            final RlWire.Decide d = RlWire.decodeDecide(p);
            checker.check(d, false);
            decides++;
            final RlWire.Decision x = new RlWire.Decision();
            x.gameUid = d.gameUid;
            x.decIdx = d.decIdx;
            x.status = RlWire.ST_OK;
            x.policyVersion = 1;
            x.steps = FakeRlServer.randomSteps(d, new Random(FakeRlServer.mix(7, d.gameUid, d.decIdx)));
            return x;
        }

        @Override
        public void record(final byte[] p) {
            final RlWire.Decide d = RlWire.decodeDecide(p);
            checker.check(d, true);
            recordsByFamily.merge(d.family, 1, Integer::sum);
        }
    }

    // ------------------------------------------------------------------------------------------------ 6. deck guard

    @Test
    public void deckGuardRefusesEvalPathsSymlinksAndShaMismatch() throws Exception {
        final Path ok = bank.resolve("decks").resolve(pairs.get(0)[0].substring(pairs.get(0)[0].lastIndexOf('/') + 1));
        final String okSha = CardIndex.sha256(Files.readAllBytes(ok));
        final Path ev = evalBank.resolve("decks/ev0.dck");
        final String evSha = CardIndex.sha256(Files.readAllBytes(ev));
        final Path link = bank.resolve("decks/link-to-eval.dck");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, ev);
        // a TRAIN-path file whose sha is an eval deck's (and not on the train allowlist)
        final Path planted = bank.resolve("decks/planted.dck");
        Files.copy(ev, planted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // a train dir with an 'eval' component
        final Path evalComponent = root.resolve("data/train/t24/eval/x.dck");
        Files.createDirectories(evalComponent.getParent());
        Files.copy(ok, evalComponent, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for (String mode : new String[] {"train", "record"}) {
            Assert.assertNull(RlActorBench.deckGuard(mode, root.toString(), ok.toString(), okSha), mode);
            Assert.assertNotNull(RlActorBench.deckGuard(mode, root.toString(), ev.toString(), evSha), mode + " eval path");
            Assert.assertNotNull(RlActorBench.deckGuard(mode, root.toString(), link.toString(), evSha), mode + " symlink");
            Assert.assertNotNull(RlActorBench.deckGuard(mode, root.toString(), ok.toString(), evSha), mode + " sha");
            Assert.assertNotNull(RlActorBench.deckGuard(mode, root.toString(), planted.toString(), evSha),
                    mode + " planted");
            Assert.assertNotNull(RlActorBench.deckGuard(mode, root.toString(), evalComponent.toString(), okSha),
                    mode + " eval component");
            Assert.assertNotNull(RlActorBench.deckGuard(mode, root.toString(), "decks/x.dck", okSha), mode + " relative");
        }
        Assert.assertNull(RlActorBench.deckGuard("eval", root.toString(), ev.toString(), evSha));
        Assert.assertNotNull(RlActorBench.deckGuard("eval", root.toString(), ok.toString(), okSha), "eval mode, train deck");
        // a GAME carrying an eval deck is refused before anything is played
        final JsonObject g = game(0, "rl:M", "forge", true);
        g.getAsJsonArray("decks").set(1, new com.google.gson.JsonPrimitive(ev.toString()));
        g.getAsJsonArray("deck_sha").set(1, new com.google.gson.JsonPrimitive(evSha));
        final RlActorBench.Played p = RlActorBench.play(cfg("train"), "train", g, new RlFeaturizer(index),
                new LocalEndpoint(new FakeRlServer(0, "train", 7, null, Collections.emptyList())), null, "test");
        Assert.assertNotNull(p.guardError);
        Assert.assertNull(p.end);
        Files.delete(link);
        Files.delete(planted);
        Files.delete(evalComponent);
    }

    // ------------------------------------------------------------------------------------------------ 7. do-no-harm

    @Test
    public void recorderAndForgeSeatsReproduceRlSimBenchForge() throws Exception {
        final int n = Integer.getInteger("rl.doNoHarmGames", 24);
        final RlSimBench.Cfg rs = new RlSimBench.Cfg();
        rs.pairs = pairs;
        rs.seed = seed0;
        rs.policy = "forge";
        rs.games = n;
        final java.lang.management.MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        final FakeRlServer checker = new FakeRlServer(0, "record", 7, null, Collections.emptyList());
        final LocalEndpoint rec = new LocalEndpoint(checker);
        final List<String> bad = new ArrayList<>();
        int recorded = 0, mapFailed = 0;
        for (int i = 0; i < n; i++) {
            final JsonObject sim = RlSimBench.playOne(rs, i, mem);
            final String want = sim.get("digest").getAsString();
            final RlActorBench.Played r = RlActorBench.play(cfg("record"), "record", game(i, "record", "record", true),
                    new RlFeaturizer(index), rec, null, "test");
            final RlActorBench.Played f = RlActorBench.play(cfg("train"), "train", game(i, "forge", "forge", true),
                    new RlFeaturizer(index), new LocalEndpoint(checker), null, "test");
            recorded += r.seat.totalSent();
            mapFailed += r.seat.mapFailedTotal();
            final String line = "game " + i + " seed " + (seed0 + i) + ": rlsim " + want + " " + sim.get("outcome")
                    + " | record " + r.end.get("digest") + " void " + r.end.get("void") + " | forge-seats "
                    + f.end.get("digest");
            System.err.println("[do-no-harm] " + line);
            if (!want.equals(r.end.get("digest").getAsString()) || !want.equals(f.end.get("digest").getAsString())) {
                bad.add(line);
            }
        }
        System.err.println("[do-no-harm] recorded " + recorded + " frames, map failures " + mapFailed + ", by family "
                + rec.recordsByFamily + ", server problems " + checker.problems);
        Assert.assertTrue(bad.isEmpty(), "digest mismatches: " + bad);
        Assert.assertEquals(checker.badFrames.get(), 0L, String.valueOf(checker.problems));
        Assert.assertEquals(checker.badTeachers.get(), 0L, String.valueOf(checker.problems));
        Assert.assertEquals(checker.trivialFrames.get(), 0L, String.valueOf(checker.problems));
        for (int fam : new int[] {1, 2, 3, 4, 5}) {
            Assert.assertTrue(rec.recordsByFamily.getOrDefault(fam, 0) > 0, "no RECORD rows for family " + fam);
        }
        Assert.assertTrue(mapFailed <= 0.02 * (recorded + mapFailed), "map failures " + mapFailed + " of " + recorded);
    }

    // ------------------------------------------------------------------------------------------------ 4, 5, 8: visibility, R-11, trivial

    /** Checks every sent frame of a train game against the live game. */
    static final class Witness implements RlSeat.FrameListener {
        final FakeRlServer checker;
        int frames;
        int r11Done;
        final List<String> violations = new ArrayList<>();

        Witness(final FakeRlServer checker) {
            this.checker = checker;
        }

        @Override
        public void onFrame(final Game g, final Player seat, final RlWire.Decide f, final RlCandidates.Menu m,
                final RlFeaturizer.Obs o, final short[] steps, final JsonObject answer) {
            frames++;
            checker.check(f, false);
            if (FakeRlServer.trivial(f)) {
                violations.add("trivial frame sent");
            }
            final boolean[] covered = new boolean[f.L];
            for (Map.Entry<Integer, Integer> e : o.posByCardId.entrySet()) {
                final int pos = e.getValue();
                covered[pos] = true;
                final Card c = g.findById(e.getKey());
                if (c == null) {
                    continue; // a spell on the stack, checked through its stack instance
                }
                if (!c.getView().canBeShownTo(seat.getView())) {
                    violations.add("hidden card tokenised: " + c + " in " + c.getZone());
                }
                if (c.isInZone(ZoneType.Hand) && c.getOwner() != seat && !c.getView().canBeShownTo(seat.getView())) {
                    violations.add("opponent hidden hand card: " + c);
                }
                if (c.isInZone(ZoneType.Library) && !c.getView().canBeShownTo(seat.getView())) {
                    violations.add("library card without a known position: " + c);
                }
                final int want = c.isFaceDown() ? CardIndex.UNK : index.resolve(c.getName());
                if (f.tokCard[pos] != want) {
                    violations.add("tok_card " + f.tokCard[pos] + " is not the name index " + want + " of " + c);
                }
            }
            for (int pos : o.posByStackId.values()) {
                covered[pos] = true;
            }
            for (int i = 0; i < f.L; i++) {
                if (!covered[i]) {
                    violations.add("token " + i + " has no visible source");
                }
            }
            if (r11Done == 0) {
                r11(g, seat, o);
            }
        }

        /** R-11: permutations and a new seed leave the block identical; one hand change and a peeking mutant do not. */
        void r11(final Game g, final Player seat, final RlFeaturizer.Obs o) {
            final Player opp = RlFeaturizer.opponentOf(g, seat);
            final List<Card> own = new ArrayList<>(seat.getCardsIn(ZoneType.Library));
            final List<Card> oth = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
            final List<Card> hidden = new ArrayList<>();
            for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                if (!c.getView().canBeShownTo(seat.getView())) hidden.add(c);
            }
            if (own.size() < 10 || oth.size() < 10 || hidden.size() < 2) {
                return;
            }
            r11Done++;
            final int[][] b0 = RlFeaturizer.privBlock(index, hidden, own, oth);
            final String m0 = peek(own);
            // permute both libraries in the live game, recompute, restore
            final List<Card> ownP = new ArrayList<>(own), othP = new ArrayList<>(oth);
            Collections.shuffle(ownP, new Random(11));
            Collections.shuffle(othP, new Random(12));
            seat.getZone(ZoneType.Library).setCards(ownP);
            opp.getZone(ZoneType.Library).setCards(othP);
            final Random saved = MyRandom.getThreadRandom();
            MyRandom.setThreadRandom(new Random(424242)); // a different seed, zone contents fixed
            final RlFeaturizer.Obs o2 = new RlFeaturizer(index).observe(g, seat, 0, true);
            MyRandom.setThreadRandom(saved);
            final int[][] b1 = RlFeaturizer.privBlock(index, hidden, seat.getCardsIn(ZoneType.Library),
                    opp.getCardsIn(ZoneType.Library));
            final String m1 = peek(seat.getCardsIn(ZoneType.Library));
            seat.getZone(ZoneType.Library).setCards(own);
            opp.getZone(ZoneType.Library).setCards(oth);
            if (!Arrays.deepEquals(b0, b1)) {
                violations.add("R-11: permuting the libraries changed the privileged block");
            }
            if (!Arrays.equals(o.privCard, o2.privCard) || !Arrays.equals(o.privZone, o2.privZone)
                    || !Arrays.equals(o.privCnt, o2.privCnt)) {
                violations.add("R-11: a new seed/permutation changed the observed privileged block");
            }
            if (m0.equals(m1)) {
                violations.add("R-11 witness is blind: the peeking mutant did not change under permutation");
            }
            // one opponent hand card changed (to a card with another name) changes the block
            Card other = null;
            for (Card c : oth) {
                if (index.resolve(c.getName()) != index.resolve(hidden.get(0).getName())) {
                    other = c;
                    break;
                }
            }
            if (other != null) {
                final List<Card> h2 = new ArrayList<>(hidden);
                h2.set(0, other);
                final int[][] b2 = RlFeaturizer.privBlock(index, h2, own, oth);
                if (Arrays.deepEquals(b0, b2)) {
                    violations.add("R-11: changing an opponent hand card did not change the block");
                }
            }
        }

        /** The deliberately unsorted (peeking) mutant: library cards in library order. */
        static String peek(final Iterable<Card> lib) {
            final StringBuilder sb = new StringBuilder();
            for (Card c : lib) sb.append(index.resolve(c.getName())).append(',');
            return sb.toString();
        }
    }

    @Test
    public void visibilityR11TrivialAndReplay() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        final Witness w = new Witness(checker);
        final List<JsonObject> tapes = new ArrayList<>();
        int trivial = 0;
        final int games = Integer.getInteger("rl.visGames", 4);
        for (int i = 0; i < games; i++) {
            final RlActorBench.Played p = RlActorBench.play(cfg("train"), "train",
                    game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true), new RlFeaturizer(index),
                    new LocalEndpoint(checker), w, "test");
            Assert.assertNull(p.guardError);
            tapes.add(p.tape);
            final JsonObject census = p.tape.getAsJsonObject("census");
            for (Map.Entry<String, JsonElement> e : census.entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("trivial")) {
                    trivial += e.getValue().getAsJsonObject().get("trivial").getAsInt();
                }
            }
        }
        System.err.println("[visibility] frames " + w.frames + ", R-11 witnesses " + w.r11Done + ", trivial auto "
                + trivial + ", violations " + w.violations.subList(0, Math.min(10, w.violations.size())));
        Assert.assertTrue(w.frames >= 100, "only " + w.frames + " frames");
        Assert.assertTrue(w.r11Done >= 1, "no frame qualified for the R-11 witness");
        Assert.assertTrue(w.violations.isEmpty(), String.valueOf(w.violations));
        Assert.assertEquals(checker.badFrames.get(), 0L, String.valueOf(checker.problems));
        Assert.assertEquals(checker.trivialFrames.get(), 0L);
        Assert.assertTrue(trivial > 0, "trivial asks must be counted in the census");
        // replay: the same seeds and decks, steps from the tape, Forge on the forge seats: equal digests
        for (JsonObject t : tapes) {
            if (!t.get("void").isJsonNull()) {
                continue;
            }
            final JsonObject g = new JsonObject();
            g.add("game_uid", t.get("game_uid"));
            g.add("seed", t.get("seed"));
            final JsonArray decks = new JsonArray(), shas = new JsonArray();
            for (JsonElement d : t.getAsJsonArray("decks")) {
                decks.add(d.getAsJsonObject().get("path"));
                shas.add(d.getAsJsonObject().get("sha"));
            }
            g.add("decks", decks);
            g.add("deck_sha", shas);
            g.add("controllers", t.get("controllers"));
            g.addProperty("priv", false);
            final RlActorBench.Played r = RlActorBench.play(cfg("train"), "train", g, new RlFeaturizer(index),
                    RlActorBench.tapeEndpoint(t, RlWire.parseUid(t.get("game_uid").getAsString())), null, "test");
            Assert.assertNull(r.seat.fatal, "replay error");
            Assert.assertEquals(r.end.get("digest"), t.get("digest"), "replay digest of " + t.get("game_uid"));
        }
    }
}
