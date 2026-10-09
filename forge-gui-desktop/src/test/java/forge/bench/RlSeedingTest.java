package forge.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlTape;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.MyRandom;

/**
 * G3 opening-hand seeding (lane cm-curriculum-1009, {@link RlSeeding}): off is the plain path, p = 0 and a missing piece
 * play the off game, an applied spec places k pieces in the opening hand and the rest in the window with the library's
 * multiset unchanged, the same seed gives the same game, the seat's stream leaves the game's random stream alone, a
 * restart does not re-apply, a replayed seeded tape re-applies its seeding, and bad specs are refused. Plays real
 * Forge games (Forge seats only): run inside a broker test lease, cwd = forge-gui with res/.
 *
 * <p>Fixtures: the 24 TRAIN decks of {@code rl/decks} in a temporary {@code $RL_ROOT}; seeds {@code -Drl.seedingSeed}
 * (default 722191000 + i, lane cm-curriculum-1009's block).
 */
public class RlSeedingTest {

    static Path root;
    static Path bank;
    static Path evalDeck;
    static CardIndex index;
    static List<String[]> pairs;
    static long seed0;

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBench.ensureBooted();
        final Path decks = forge.bench.rl.RlWireSchemaTest.res().resolve("decks");
        root = Files.createTempDirectory("rl-seeding-");
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
        // an eval bank of one deck (a renamed copy): nothing is played on it, the GAME is refused first
        final Path evalBank = root.resolve("data/eval/e1");
        Files.createDirectories(evalBank.resolve("decks"));
        evalDeck = evalBank.resolve("decks/ev0.dck");
        Files.write(evalDeck, (new String(Files.readAllBytes(files.get(0)), StandardCharsets.UTF_8) + "\n// eval copy\n")
                .getBytes(StandardCharsets.UTF_8));
        final JsonArray ed = new JsonArray();
        ed.add(RlActorBenchTest.entry("ev0.dck", evalDeck));
        RlActorBenchTest.manifest(evalBank, "e1", "eval", ed);
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : names) sb.append(n).append('\t').append(i++).append('\n');
        index = CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
        pairs = RlFakeRun.pairs(bank);
        seed0 = Long.getLong("rl.seedingSeed", 722191000L);
    }

    static RlActorBench.Cfg cfg(final String mode) {
        final RlActorBench.Cfg c = new RlActorBench.Cfg();
        c.mode = mode;
        c.rlRoot = root.toString();
        c.aiTimeoutSec = 60; // Forge's wall-clock AI timeout must not decide a move on a loaded host
        return c;
    }

    /** Game i: pair (i mod pairs), seed seed0 + i, Forge on both seats; the seeding object when non-null. */
    static JsonObject game(final int i, final JsonElement seeding) throws IOException {
        final JsonObject g = RlFakeRun.schedule(bank, i, 1, seed0, new String[] {"forge", "forge"}, false, false, 5000L)
                .get(0);
        if (seeding != null) {
            g.add("seeding", seeding);
        }
        return g;
    }

    static String deckOf(final int i, final int seat) {
        return pairs.get(i % pairs.size())[seat];
    }

    /** n distinct nonland names of the seat's deck that no pregame action moves, from a fixed offset. */
    static List<String> pieces(final int i, final int seat, final int n) {
        final TreeSet<String> all = new TreeSet<>();
        for (Map.Entry<PaperCard, Integer> e : RlActorBench.deck(deckOf(i, seat)).getMain()) {
            final String nm = e.getKey().getName();
            if (!e.getKey().getRules().getType().isLand() && !nm.startsWith("Leyline") && !nm.contains("Chancellor")
                    && !nm.equals("Gemstone Caverns") && !nm.equals("Serum Powder")) {
                all.add(nm);
            }
        }
        final List<String> l = new ArrayList<>(all);
        final List<String> out = new ArrayList<>();
        for (int j = 0; j < n; j++) out.add(l.get((3 * i + 5 * j + seat) % l.size()));
        return new ArrayList<>(new java.util.LinkedHashSet<>(out));
    }

    static JsonObject spec(final double p, final int[] k, final int window, final JsonElement s0, final JsonElement s1) {
        final JsonObject o = new JsonObject();
        o.addProperty("schema", RlSeeding.SCHEMA);
        o.addProperty("p", p);
        final JsonArray ka = new JsonArray();
        for (int x : k) ka.add(x);
        o.add("k", ka);
        o.addProperty("window", window);
        final JsonArray seats = new JsonArray();
        seats.add(s0 == null ? JsonNull.INSTANCE : s0);
        seats.add(s1 == null ? JsonNull.INSTANCE : s1);
        o.add("seats", seats);
        return o;
    }

    static JsonObject seat(final String route, final List<String> pieces) {
        final JsonObject o = new JsonObject();
        o.addProperty("route", route);
        final JsonArray a = new JsonArray();
        for (String x : pieces) a.add(x);
        o.add("pieces", a);
        return o;
    }

    // ------------------------------------------------------------------------------------------------ the probe

    /** Registered after the seeding (KNOWLEDGE_TAP runs after RlActorBench attaches it): what the game started with. */
    static final class Probe {
        final Game g;
        final List<List<Integer>> libIds = new ArrayList<>();
        final List<List<String>> libNames = new ArrayList<>();
        final List<List<String>> handT1 = new ArrayList<>();
        final int[] mulligans = new int[2];
        long nextOfGameRandom;
        int started;

        Probe(final Game g) {
            this.g = g;
        }

        @Subscribe
        public void started(final GameEventGameStarted ev) {
            if (started++ > 0) {
                return;
            }
            for (Player p : g.getRegisteredPlayers()) {
                final List<Integer> ids = new ArrayList<>();
                final List<String> nm = new ArrayList<>();
                for (Card c : p.getZone(ZoneType.Library).getCards(false)) {
                    ids.add(c.getId());
                    nm.add(c.getName());
                }
                libIds.add(ids);
                libNames.add(nm);
            }
            nextOfGameRandom = cloneOf(MyRandom.getThreadRandom()).nextLong(); // the stream's state, not consumed
        }

        @Subscribe
        public void turn(final GameEventTurnBegan ev) {
            if (!handT1.isEmpty()) {
                return;
            }
            int s = 0;
            for (Player p : g.getRegisteredPlayers()) {
                final List<String> h = new ArrayList<>();
                for (Card c : p.getCardsIn(ZoneType.Hand)) h.add(c.getName());
                handT1.add(h);
                mulligans[s++] = p.getStats().getMulliganCount();
            }
        }
    }

    static Random cloneOf(final Random r) {
        try {
            final ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (ObjectOutputStream o = new ObjectOutputStream(b)) {
                o.writeObject(r);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(b.toByteArray()))) {
                return (Random) in.readObject();
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class Run {
        RlActorBench.Played p;
        Probe probe;
    }

    static final RlSeat.Endpoint NO_SERVER = new RlSeat.Endpoint() {
        @Override
        public RlWire.Decision decide(final byte[] b) {
            throw new IllegalStateException("Forge seats ask no server");
        }

        @Override
        public void record(final byte[] b) {
            throw new IllegalStateException("Forge seats record nothing");
        }
    };

    static Run play(final String mode, final JsonObject g) {
        final Run r = new Run();
        RlActorBench.KNOWLEDGE_TAP = game -> {
            r.probe = new Probe(game);
            game.subscribeToEvents(r.probe);
            return new BenchSession.KnowledgeObserver() {
                @Override
                public void onReveal(final Game gm, final Player v, final List<Card> cards, final ZoneType z,
                        final Player owner) {
                }

                @Override
                public void onLook(final Game gm, final Player v, final List<Card> cards, final ZoneType dest) {
                }
            };
        };
        try {
            r.p = RlActorBench.play(cfg(mode), mode, g, new RlFeaturizer(index), NO_SERVER, null, "test");
        } finally {
            RlActorBench.KNOWLEDGE_TAP = null;
        }
        return r;
    }

    static final Map<Integer, Run> OFF = new HashMap<>();

    static Run off(final int i) throws IOException {
        Run r = OFF.get(i);
        if (r == null) {
            r = play("train", game(i, null));
            Assert.assertNull(r.p.guardError);
            Assert.assertTrue(r.p.end.get("void").isJsonNull(), "off game " + i + " void " + r.p.end.get("void"));
            OFF.put(i, r);
        }
        return r;
    }

    static String digest(final Run r) {
        return r.p.end.get("digest").getAsString();
    }

    // ------------------------------------------------------------------------------------------------ off

    @Test
    public void offIsThePlainPath() throws Exception {
        for (int i = 0; i < 2; i++) {
            final Run a = off(i);
            Assert.assertFalse(a.p.tape.has("seeding"), "no tape key when off");
            Assert.assertFalse(a.p.tape.has("seeded"), "no tape key when off");
            Assert.assertFalse(a.p.end.has("seeded"), "no GAME_END key when off");
            // JSON null is off as well
            final Run n = play("train", game(i, JsonNull.INSTANCE));
            Assert.assertEquals(digest(n), digest(a));
            Assert.assertEquals(n.probe.libIds, a.probe.libIds);
            Assert.assertEquals(n.probe.handT1, a.probe.handT1);
            Assert.assertFalse(n.p.tape.has("seeding"));
            Assert.assertEquals(RlWire.canonicalString(strip(n.p.tape)), RlWire.canonicalString(strip(a.p.tape)),
                    "the null-spec tape is the off tape");
            // a spec without seats changes no library either (only its tape keys)
            final Run e = play("train", game(i, spec(1.0, new int[] {1, 2}, 8, null, null)));
            Assert.assertEquals(digest(e), digest(a));
            Assert.assertEquals(e.probe.libIds, a.probe.libIds);
            Assert.assertEquals(e.p.tape.get("seeded").toString(), "[null,null]");
        }
    }

    /** A tape line without its timing fields (the rest is a function of the game). */
    static JsonObject strip(final JsonObject t) {
        final JsonObject c = t.deepCopy();
        for (String k : new String[] {"cpu_ms", "wall_ms", "cpu_other_ms"}) c.remove(k);
        return c;
    }

    @Test
    public void pZeroIsOff() throws Exception {
        final int i = 2;
        final Run a = off(i);
        final JsonObject sp = spec(0.0, new int[] {1, 2}, 8, seat("r0", pieces(i, 0, 2)), seat("r1", pieces(i, 1, 2)));
        final Run z = play("train", game(i, sp));
        Assert.assertNull(z.p.guardError);
        Assert.assertEquals(digest(z), digest(a), "p = 0 plays the off game");
        Assert.assertEquals(z.probe.libIds, a.probe.libIds);
        Assert.assertEquals(z.probe.handT1, a.probe.handT1);
        Assert.assertEquals(z.probe.nextOfGameRandom, a.probe.nextOfGameRandom);
        for (int s = 0; s < 2; s++) {
            final JsonObject r = z.p.tape.getAsJsonArray("seeded").get(s).getAsJsonObject();
            Assert.assertFalse(r.get("applied").getAsBoolean());
            Assert.assertEquals(r.get("reason").getAsString(), "p");
        }
        Assert.assertEquals(z.p.tape.get("seeding"), sp, "the tape records the spec as played");
        Assert.assertEquals(z.p.end.get("seeded"), z.p.tape.get("seeded"));
    }

    // ------------------------------------------------------------------------------------------------ applied

    @Test
    public void appliedPlacesKInTheHandAndTheRestInTheWindow() throws Exception {
        final int games = Integer.getInteger("rl.seedingGames", 8);
        final int window = 8;
        int[] kSeen = new int[3];
        int handChecked = 0;
        for (int i = 3; i < 3 + games; i++) {
            final Run a = off(i);
            final List<List<String>> want = new ArrayList<>();
            want.add(pieces(i, 0, 3));
            want.add(pieces(i, 1, 2));
            final JsonObject sp = spec(1.0, new int[] {1, 2}, window, seat("A", want.get(0)), seat("B", want.get(1)));
            final Run r = play("train", game(i, sp));
            Assert.assertNull(r.p.guardError);
            Assert.assertTrue(r.p.end.get("void").isJsonNull(), "seeded game " + i + " void " + r.p.end.get("void"));
            Assert.assertEquals(r.probe.nextOfGameRandom, a.probe.nextOfGameRandom,
                    "the seat streams must not draw from the game's random stream");
            final JsonArray seeded = r.p.tape.getAsJsonArray("seeded");
            for (int s = 0; s < 2; s++) {
                final JsonObject o = seeded.get(s).getAsJsonObject();
                Assert.assertTrue(o.get("applied").getAsBoolean(), "game " + i + " seat " + s + ": " + o);
                final int k = o.get("k").getAsInt();
                final int hand = o.get("hand_size").getAsInt();
                Assert.assertEquals(hand, 7);
                kSeen[k]++;
                final List<String> top = names(o.getAsJsonArray("top")), win = names(o.getAsJsonArray("window"));
                Assert.assertEquals(top.size(), k);
                Assert.assertEquals(top.size() + win.size(), want.get(s).size());
                final List<String> both = new ArrayList<>(top);
                both.addAll(win);
                Collections.sort(both);
                final List<String> ws = new ArrayList<>(want.get(s));
                Collections.sort(ws);
                Assert.assertEquals(both, ws, "every piece placed once");
                // the live library at the start: the pieces where the tape says, k of them in the hand
                final List<String> lib = r.probe.libNames.get(s);
                final JsonArray pos = o.getAsJsonArray("pos");
                int inHand = 0, inWin = 0;
                for (int j = 0; j < pos.size(); j++) {
                    final int at = pos.get(j).getAsInt();
                    Assert.assertEquals(lib.get(at), j < k ? top.get(j) : win.get(j - k));
                    if (at < hand) inHand++;
                    else if (at < hand + window) inWin++;
                }
                Assert.assertEquals(inHand, k);
                Assert.assertEquals(inWin, want.get(s).size() - k);
                int piecesInHand = 0;
                for (String nm : lib.subList(0, hand)) if (want.get(s).contains(nm)) piecesInHand++;
                Assert.assertEquals(piecesInHand, k, "exactly k pieces in the opening seven");
                // reorder only: the same cards, and the non-pieces keep their relative order
                final List<Integer> ids = new ArrayList<>(r.probe.libIds.get(s)), offIds = new ArrayList<>(a.probe.libIds.get(s));
                final List<Integer> sortedIds = new ArrayList<>(ids), sortedOff = new ArrayList<>(offIds);
                Collections.sort(sortedIds);
                Collections.sort(sortedOff);
                Assert.assertEquals(sortedIds, sortedOff, "library multiset unchanged");
                final java.util.Set<Integer> pieceIds = new java.util.HashSet<>();
                for (int j = 0; j < pos.size(); j++) pieceIds.add(ids.get(pos.get(j).getAsInt()));
                ids.removeAll(pieceIds);
                offIds.removeAll(pieceIds);
                Assert.assertEquals(ids, offIds, "non-pieces keep their order");
                // the opening hand itself (a kept seven: no mulligan)
                if (r.probe.mulligans[s] == 0) {
                    final List<String> h = new ArrayList<>(r.probe.handT1.get(s));
                    for (String t : top) Assert.assertTrue(h.remove(t), "opening hand lacks " + t + ": " + r.probe.handT1.get(s));
                    handChecked++;
                }
            }
            Assert.assertEquals(r.p.end.get("seeded"), seeded);
            System.err.println("[seeding] game " + i + " " + seeded + " digest " + digest(r) + " (off " + digest(a) + ")");
        }
        System.err.println("[seeding] k seen " + java.util.Arrays.toString(kSeen) + ", kept-seven hands checked "
                + handChecked);
        Assert.assertTrue(kSeen[1] > 0 && kSeen[2] > 0, "both k values drawn: " + java.util.Arrays.toString(kSeen));
        Assert.assertTrue(handChecked > 0, "no kept seven to check");
    }

    static List<String> names(final JsonArray a) {
        final List<String> l = new ArrayList<>();
        for (JsonElement e : a) l.add(e.getAsString());
        return l;
    }

    @Test
    public void sameSeedSameGame() throws Exception {
        final int i = 3;
        final JsonObject sp = spec(1.0, new int[] {1, 2}, 8, seat("A", pieces(i, 0, 3)), null);
        final Run a = play("train", game(i, sp)), b = play("train", game(i, sp));
        Assert.assertEquals(a.probe.libIds, b.probe.libIds);
        Assert.assertEquals(digest(a), digest(b));
        Assert.assertEquals(a.p.tape.get("seeded"), b.p.tape.get("seeded"));
        Assert.assertTrue(a.p.tape.get("seeded").getAsJsonArray().get(1).isJsonNull());
        // another game seed draws its own u, k and positions
        Assert.assertEquals(RlSeeding.seatSeed(1L, 0), RlSeeding.seatSeed(1L, 0));
        Assert.assertNotEquals(RlSeeding.seatSeed(1L, 0), RlSeeding.seatSeed(1L, 1));
        Assert.assertNotEquals(RlSeeding.seatSeed(1L, 0), RlSeeding.seatSeed(2L, 0));
    }

    @Test
    public void missingPieceIsNotApplied() throws Exception {
        final int i = 4;
        final Run a = off(i);
        final List<String> pcs = new ArrayList<>(pieces(i, 0, 2));
        pcs.add("No Such Card Anywhere");
        final Run m = play("train", game(i, spec(1.0, new int[] {2}, 8, seat("A", pcs), null)));
        final JsonObject o = m.p.tape.getAsJsonArray("seeded").get(0).getAsJsonObject();
        Assert.assertFalse(o.get("applied").getAsBoolean());
        Assert.assertEquals(o.get("reason").getAsString(), "missing");
        Assert.assertEquals(o.getAsJsonArray("missing").toString(), "[\"No Such Card Anywhere\"]");
        Assert.assertEquals(m.probe.libIds, a.probe.libIds);
        Assert.assertEquals(digest(m), digest(a));
    }

    /** A face name (adventure, back face, modal back) locates its card; the full name does too. */
    @Test
    public void faceNamesLocateTheirCard() throws Exception {
        final String[][] faces = {{"Fable of the Mirror-Breaker", "Reflection of Kiki-Jiki"},
                {"Brazen Borrower", "Petty Theft"}, {"Valki, God of Lies", "Tibalt, Cosmic Impostor"}};
        int checked = 0;
        for (String[] f : faces) {
            int at = -1, seatOf = -1;
            for (int i = 0; i < pairs.size() && at < 0; i++) {
                for (int s = 0; s < 2; s++) {
                    for (Map.Entry<PaperCard, Integer> e : RlActorBench.deck(deckOf(i, s)).getMain()) {
                        if (e.getKey().getName().equals(f[0])) {
                            at = i;
                            seatOf = s;
                        }
                    }
                }
            }
            if (at < 0) {
                continue;
            }
            final List<String> one = Collections.singletonList(f[1]);
            final JsonObject sp = spec(1.0, new int[] {1}, 8, seatOf == 0 ? seat("F", one) : null,
                    seatOf == 1 ? seat("F", one) : null);
            final Run r = play("train", game(at, sp));
            final JsonObject o = r.p.tape.getAsJsonArray("seeded").get(seatOf).getAsJsonObject();
            Assert.assertTrue(o.get("applied").getAsBoolean(), f[1] + ": " + o);
            final int pos = o.getAsJsonArray("pos").get(0).getAsInt();
            Assert.assertTrue(pos < 7);
            Assert.assertEquals(r.probe.libNames.get(seatOf).get(pos), f[0], "the face name located " + f[0]);
            checked++;
        }
        Assert.assertTrue(checked > 0, "no multi-face card in the fixture decks");
    }

    // ------------------------------------------------------------------------------------------------ restart

    @Test
    public void aRestartDoesNotReapply() throws Exception {
        // a finished game's library, seeded by hand: the first start applies, a second (Karn restart) does not
        final int i = 5;
        final Run r = play("train", game(i, null));
        final Game g = r.probe.g;
        final Player p0 = g.getRegisteredPlayers().get(0);
        final List<Card> before = new ArrayList<>(p0.getZone(ZoneType.Library).getCards(false));
        if (before.size() < 16) {
            throw new org.testng.SkipException("library too small after the game: " + before.size());
        }
        final List<String> pcs = new ArrayList<>();
        for (Card c : before.subList(8, before.size())) {
            if (!pcs.contains(c.getName()) && pcs.size() < 2) {
                pcs.add(c.getName());
            }
        }
        final JsonObject gm = game(i, spec(1.0, new int[] {2}, 8, seat("A", pcs), null));
        final RlSeeding sd = RlSeeding.of(gm, gm.get("seed").getAsLong());
        sd.attach(g);
        g.fireEvent(new GameEventGameStarted(GameType.Constructed, p0, g.getPlayers()));
        final List<Card> first = new ArrayList<>(p0.getZone(ZoneType.Library).getCards(false));
        final JsonArray s1 = sd.seeded();
        Assert.assertTrue(s1.get(0).getAsJsonObject().get("applied").getAsBoolean(), String.valueOf(s1));
        int inHand = 0;
        for (Card c : first.subList(0, 7)) if (pcs.contains(c.getName())) inHand++;
        Assert.assertEquals(inHand, 2, "k = 2 pieces on top: " + s1);
        g.fireEvent(new GameEventGameStarted(GameType.Constructed, p0, g.getPlayers()));
        final List<Card> second = new ArrayList<>(p0.getZone(ZoneType.Library).getCards(false));
        Assert.assertEquals(second, first, "a second start (Karn restart) re-applied the seeding");
        Assert.assertEquals(sd.seeded(), s1);
    }

    // ------------------------------------------------------------------------------------------------ replay

    @Test
    public void aSeededTapeReplaysWithItsSeeding() throws Exception {
        final int i = 6;
        final JsonObject sp = spec(1.0, new int[] {1, 2}, 8, seat("A", pieces(i, 0, 2)), seat("B", pieces(i, 1, 3)));
        final Run r = play("train", game(i, sp));
        Assert.assertTrue(r.p.end.get("void").isJsonNull());
        Assert.assertNotEquals(digest(r), digest(off(i)), "the seeding changed nothing in this game; pick another");
        final Path dir = Files.createTempDirectory(root, "tapes-");
        try (RlTape t = new RlTape(dir, 1000)) {
            t.write(r.p.tape);
            final JsonObject lost = r.p.tape.deepCopy();
            lost.remove("seeding"); // a replay that drops the seeding must not pass
            t.write(lost);
        }
        final Path out = dir.resolve("replay.jsonl");
        final RlActorBench.Cfg c = cfg("replay");
        c.replayTapes.add(dir.resolve("tapes-00000.jsonl.gz").toString());
        c.replayLines.add(new int[] {0, 0});
        c.replayLines.add(new int[] {0, 1});
        c.replayOut = out.toString();
        final int rc = RlActorBench.replay(c, index, "test");
        final List<String> rows = Files.readAllLines(out, StandardCharsets.UTF_8);
        System.err.println("[seeding] replay rows " + rows);
        final JsonObject ok = JsonParser.parseString(rows.get(0)).getAsJsonObject();
        final JsonObject bad = JsonParser.parseString(rows.get(1)).getAsJsonObject();
        Assert.assertTrue(ok.get("equal").getAsBoolean(), rows.get(0));
        Assert.assertTrue(ok.get("seeded_equal").getAsBoolean(), rows.get(0));
        Assert.assertFalse(bad.get("equal").getAsBoolean(), rows.get(1));
        Assert.assertFalse(bad.get("seeded_equal").getAsBoolean(), rows.get(1));
        Assert.assertEquals(rc, 4, "one unequal line");
    }

    // ------------------------------------------------------------------------------------------------ refusals

    @Test
    public void badSpecsAreRefused() throws Exception {
        final JsonObject good = spec(0.5, new int[] {1, 2}, 8, seat("A", pieces(0, 0, 2)), null);
        final List<JsonObject> bad = new ArrayList<>();
        JsonObject b = good.deepCopy();
        b.addProperty("extra", 1);
        bad.add(b);
        b = good.deepCopy();
        b.addProperty("p", 1.5);
        bad.add(b);
        b = good.deepCopy();
        b.add("k", new JsonArray());
        bad.add(b);
        b = good.deepCopy();
        b.addProperty("schema", "mtgx-rl-seeding/0");
        bad.add(b);
        b = good.deepCopy();
        b.getAsJsonArray("seats").remove(1);
        bad.add(b);
        b = good.deepCopy();
        b.remove("window");
        bad.add(b);
        b = good.deepCopy();
        b.getAsJsonArray("seats").get(0).getAsJsonObject().add("pieces", new JsonArray());
        bad.add(b);
        b = good.deepCopy();
        b.getAsJsonArray("seats").get(0).getAsJsonObject().addProperty("family", "twin");
        bad.add(b);
        for (JsonObject x : bad) {
            final Run r = play("train", game(0, x));
            Assert.assertNotNull(r.p.guardError, "accepted " + x);
            Assert.assertTrue(r.p.guardError.startsWith("seeding: "), r.p.guardError);
            Assert.assertNull(r.p.end, "nothing played");
        }
        // eval mode: the same GAME on an eval deck passes the deck guard and is refused for its seeding alone
        final JsonObject ev = game(0, good);
        final String evSha = CardIndex.sha256(Files.readAllBytes(evalDeck));
        for (int s = 0; s < 2; s++) {
            ev.getAsJsonArray("decks").set(s, new com.google.gson.JsonPrimitive(evalDeck.toString()));
            ev.getAsJsonArray("deck_sha").set(s, new com.google.gson.JsonPrimitive(evSha));
        }
        Assert.assertNull(RlActorBench.deckGuard("eval", root.toString(), evalDeck.toString(), evSha));
        final RlActorBench.Played e = RlActorBench.play(cfg("eval"), "eval", ev, new RlFeaturizer(index), NO_SERVER,
                null, "test");
        Assert.assertNotNull(e.guardError);
        Assert.assertTrue(e.guardError.contains("TRAIN curriculum"), e.guardError);
        Assert.assertNull(e.end, "nothing played");
        Assert.assertNull(RlSeeding.of(new JsonObject(), 1L));
    }
}
