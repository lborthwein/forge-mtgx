package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlRouteLabels;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;

/**
 * Lane sdcc-route-1111: route labels are training data and nothing else. With the search spec's {@code routeLabels}
 * (and {@code imit}) GAME_END and the tape carry, per card-choice ask the seat's deck's routes have an opinion on, an
 * {@code imit} entry of reason {@link RlSeat#IMIT_ROUTE} and an audit row in {@code route}. The game is the same game
 * (digest, every played answer, the overridden list, every other label) as without the key; without the key the route
 * file is not read; a file that holds a route of a family on its own held-out list is refused.
 *
 * <p>The route file is test data built from the fixture decks themselves (a "route" = two cards of a deck: a land
 * a fetch land can find beside a spell, and two spells), so the labels fire in ordinary games against the stand-in
 * policy. Plays real Forge games: cwd = forge-gui with res/; fixtures as RlActorBenchTest.
 */
public class RlRouteLabelsTest {
    static final String BASE = RlSearchOverriddenTest.SPEC;
    static final String IMIT = BASE.substring(0, BASE.length() - 1) + ",\"imit\":true}";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static String withRoute(final String spec, final Path file) {
        return spec.substring(0, spec.length() - 1) + ",\"routeLabels\":" + new com.google.gson.JsonPrimitive(file.toString()) + "}";
    }

    /** name → count of a .dck file's [Main] section, in file order. */
    static Map<String, Integer> main(final Path dck) throws Exception {
        final Map<String, Integer> out = new LinkedHashMap<>();
        boolean in = false;
        for (String l : Files.readAllLines(dck, StandardCharsets.UTF_8)) {
            final String t = l.trim();
            if (t.startsWith("[")) {
                in = t.equalsIgnoreCase("[main]");
                continue;
            }
            if (!in || t.isEmpty() || t.startsWith("//")) {
                continue;
            }
            final int sp = t.indexOf(' ');
            String name = t.substring(sp + 1);
            if (name.indexOf('|') >= 0) {
                name = name.substring(0, name.indexOf('|'));
            }
            out.merge(name.trim(), Integer.parseInt(t.substring(0, sp)), Integer::sum);
        }
        return out;
    }

    static boolean isLand(final String name) {
        final forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getCard(name);
        return pc != null && pc.getRules().getType().isLand();
    }

    /** Test data: per deck one route of a land and a spell and one of two spells (family = the deck's file name). */
    static Path routeFile(final List<Path> decks, final String heldOut) throws Exception {
        final JsonArray routes = new JsonArray();
        for (Path d : decks) {
            final List<String> lands = new ArrayList<>(), spells = new ArrayList<>();
            for (Map.Entry<String, Integer> e : main(d).entrySet()) {
                if (isLand(e.getKey())) {
                    if (e.getValue() <= 3) {
                        lands.add(e.getKey());
                    }
                } else {
                    spells.add(e.getKey());
                }
            }
            final String fam = d.getFileName().toString().replace(".dck", "");
            for (int k = 0; k < Math.min(lands.size(), 4); k++) {
                routes.add(route(fam + ":land" + k, fam, lands.get(k), spells.get(k)));
            }
            for (int k = 4; k + 1 < Math.min(spells.size(), 12); k += 2) {
                routes.add(route(fam + ":spells" + k, fam, spells.get(k), spells.get(k + 1)));
            }
        }
        final JsonObject o = new JsonObject();
        o.addProperty("schema", RlRouteLabels.SCHEMA);
        final JsonArray held = new JsonArray();
        if (heldOut != null) {
            held.add(heldOut);
        }
        o.add("held_out", held);
        o.add("routes", routes);
        final Path f = Files.createTempFile("routes-", ".json");
        Files.write(f, o.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    static JsonObject route(final String id, final String fam, final String a, final String b) {
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("family", fam);
        final JsonArray p = new JsonArray();
        p.add(a);
        p.add(b);
        r.add("pieces", p);
        return r;
    }

    static List<Path> decksOf(final JsonObject game) {
        final List<Path> out = new ArrayList<>();
        for (JsonElement e : game.getAsJsonArray("decks")) {
            final Path p = Paths.get(e.getAsString());
            out.add(p.isAbsolute() ? p : RlActorBenchTest.root.resolve(p));
        }
        return out;
    }

    static RlActorBench.Played play(final JsonObject game, final String spec, final RlSeat.Endpoint ep) throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("train");
        cfg.search = RlSearch.Config.parse(JsonParser.parseString(spec).getAsJsonObject());
        return RlActorBench.play(cfg, "train", game.deepCopy(), new RlFeaturizer(RlActorBenchTest.index), ep, null, "test");
    }

    @Test
    public void specKeyRoundTrips() {
        Assert.assertNull(RlSearch.Config.parse(JsonParser.parseString(IMIT).getAsJsonObject()).routeLabels);
        Assert.assertFalse(RlSearch.Config.parse(JsonParser.parseString(IMIT).getAsJsonObject()).toJson().has("routeLabels"),
                "off: absent from the spec's JSON");
        final RlSearch.Config on = RlSearch.Config.parse(
                JsonParser.parseString(withRoute(IMIT, Paths.get("/nowhere/routes.json"))).getAsJsonObject());
        Assert.assertEquals(on.routeLabels, "/nowhere/routes.json");
        Assert.assertEquals(on.toJson().get("routeLabels").getAsString(), "/nowhere/routes.json");
    }

    @Test
    public void heldOutFamilyInTheFileIsRefused() throws Exception {
        final JsonObject game = RlActorBenchTest.game(0, "rl:M", "rl:M", true);
        final List<Path> decks = decksOf(game);
        final String fam = decks.get(0).getFileName().toString().replace(".dck", "");
        final Path bad = routeFile(decks, fam);
        try {
            RlRouteLabels.load(bad.toString());
            Assert.fail("a route of a held-out family was accepted");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("held-out"), e.getMessage());
        }
    }

    @Test
    public void routeLabelsAreDataAndChangeNothing() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        final int[] kinds = new int[4];
        int labels = 0, same = 0, onPolicyRows = 0, onOverridden = 0;
        for (int i = 0; i < 6; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true);
            final Path file = routeFile(decksOf(game), null);
            // ---- off: imit without the key; and the key without imit (no labels are written, so no file is read)
            final int loads0 = RlRouteLabels.LOADS.get();
            final RlActorBench.Played off = play(game, IMIT, new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played noImit = play(game, withRoute(BASE, file), new RlActorBenchTest.LocalEndpoint(checker));
            Assert.assertEquals(RlRouteLabels.LOADS.get(), loads0, "game " + i + ": no route file is read without the keys");
            for (RlActorBench.Played x : new RlActorBench.Played[] {off, noImit}) {
                Assert.assertNull(x.guardError);
                Assert.assertFalse(x.end.has("route") || x.tape.has("route"), "no route list without the keys");
            }
            // ---- on: the same game
            final RlImitLabelsTest.ByContent ep = new RlImitLabelsTest.ByContent(checker);
            final RlActorBench.Played on0 = play(game, withRoute(IMIT, file), new RlActorBenchTest.LocalEndpoint(checker));
            Assert.assertEquals(RlRouteLabels.LOADS.get(), loads0 + 1, "the file is read once");
            Assert.assertNull(on0.guardError);
            for (RlActorBench.Played x : new RlActorBench.Played[] {off, noImit}) {
                Assert.assertEquals(on0.end.get("digest"), x.end.get("digest"), "game " + i + ": same game digest");
                Assert.assertEquals(on0.tape.get("dec"), x.tape.get("dec"), "game " + i + ": every answer played is the same");
                Assert.assertEquals(on0.end.get("overridden"), x.end.get("overridden"));
                Assert.assertEquals(on0.end.get("result"), x.end.get("result"));
                Assert.assertEquals(on0.end.get("turns"), x.end.get("turns"));
            }
            Assert.assertEquals(on0.end.get("route_errors").getAsInt(), 0);
            Assert.assertEquals(on0.tape.get("route"), on0.end.get("route"));
            Assert.assertEquals(on0.tape.get("imit"), on0.end.get("imit"));
            // every other label is the off arm's, in order; the route labels follow
            final JsonArray other = new JsonArray(), routeImit = new JsonArray();
            for (JsonElement e : on0.end.getAsJsonArray("imit")) {
                (e.getAsJsonArray().get(1).getAsInt() == RlSeat.IMIT_ROUTE ? routeImit : other).add(e);
            }
            Assert.assertEquals(other, off.end.getAsJsonArray("imit"), "game " + i + ": the search's labels are unchanged");
            final JsonArray audit = on0.end.getAsJsonArray("route");
            Assert.assertEquals(routeImit.size(), audit.size());
            // the frames of this game, by dec_idx (a replay with the content-keyed endpoint would differ: the stand-in
            // policy here is keyed on dec_idx, so the frames are read from a third identical game)
            final Map<Integer, RlWire.Decide> frames = new HashMap<>();
            final RlActorBenchTest.LocalEndpoint in = new RlActorBenchTest.LocalEndpoint(checker);
            final RlActorBench.Played on = play(game, withRoute(IMIT, file), new RlSeat.Endpoint() {
                @Override
                public RlWire.Decision decide(final byte[] p) {
                    final RlWire.Decide d = RlWire.decodeDecide(p, 1);
                    frames.put(d.decIdx, d);
                    return in.decide(p);
                }

                @Override
                public void record(final byte[] p) {
                    in.record(p);
                }
            });
            Assert.assertEquals(on.end.get("digest"), on0.end.get("digest"));
            Assert.assertEquals(on.end.get("imit"), on0.end.get("imit"), "the labels are a function of the game");
            final Map<Integer, JsonArray> played = new HashMap<>();
            for (JsonElement e : on.tape.getAsJsonArray("dec")) {
                played.put(e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(4).getAsJsonArray());
            }
            final TreeSet<Integer> over = RlSearchOverriddenTest.ints(on.end.getAsJsonArray("overridden"));
            final TreeSet<Integer> seen = new TreeSet<>();
            for (int k = 0; k < audit.size(); k++) {
                final JsonArray lab = routeImit.get(k).getAsJsonArray(), au = audit.get(k).getAsJsonArray();
                final int d = lab.get(0).getAsInt();
                Assert.assertEquals(au.get(0).getAsInt(), d, "audit and label in the same order");
                Assert.assertTrue(seen.add(d), "one route label per decision");
                final RlWire.Decide f = frames.get(d);
                Assert.assertNotNull(f, "dec " + d + ": a route label sits on a decision that was sent");
                final JsonArray st = lab.get(2).getAsJsonArray();
                final boolean[] used = new boolean[f.C];
                for (JsonElement s : st) {
                    final int c = s.getAsInt();
                    Assert.assertTrue(c >= 0 && c < f.C && f.candKind[c] > 0, "dec " + d + ": a legal candidate");
                    if (f.mode != RlSchema.M_ASSIGN) {
                        Assert.assertFalse(used[c], "dec " + d + ": no candidate twice");
                    }
                    used[c] = true;
                }
                if (f.mode == RlSchema.M_SUBSET) {
                    Assert.assertTrue(st.size() >= f.minPick && st.size() <= f.maxPick, "dec " + d + ": pick count");
                }
                final int kind = au.get(1).getAsInt();
                kinds[kind]++;
                Assert.assertEquals(au.get(4).getAsInt() == 1, st.equals(played.get(d)), "dec " + d + ": the same-as-played flag");
                switch (kind) {
                    case RlRouteLabels.KEEP:
                        Assert.assertEquals(f.family, RlSchema.F_MULLIGAN);
                        Assert.assertEquals(f.candKind[st.get(0).getAsInt()], RlSchema.K_KEEP);
                        break;
                    case RlRouteLabels.TAKE:
                        Assert.assertEquals(f.family, RlSchema.F_CARDS);
                        break;
                    case RlRouteLabels.PROTECT:
                        Assert.assertNotEquals(st, played.get(d), "dec " + d + ": a protect label is a correction");
                        Assert.assertEquals(st.size(), played.get(d).size(), "dec " + d + ": as many cards as played");
                        break;
                    default:
                        Assert.fail("kind " + kind);
                }
                labels++;
                same += au.get(4).getAsInt();
                if (over.contains(d)) {
                    onOverridden++;
                } else {
                    onPolicyRows++;
                }
            }
            Files.deleteIfExists(file);
        }
        checker.close();
        System.err.println("[route-labels] labels " + labels + " take " + kinds[RlRouteLabels.TAKE] + " keep "
                + kinds[RlRouteLabels.KEEP] + " protect " + kinds[RlRouteLabels.PROTECT] + " same-as-played " + same
                + " on policy rows " + onPolicyRows + " on overridden rows " + onOverridden);
        Assert.assertTrue(kinds[RlRouteLabels.KEEP] > 0, "keep labels");
        Assert.assertTrue(kinds[RlRouteLabels.TAKE] > 0, "take labels");
        Assert.assertTrue(kinds[RlRouteLabels.PROTECT] > 0, "protect labels");
        Assert.assertTrue(onPolicyRows > 0, "labels on decisions the policy answered itself");
    }
}
