package forge.bench;

import java.util.Collections;
import java.util.HashMap;
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
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlSeat;

/**
 * Lane sdcc-go-1111: the search spec's {@code lineAsk}. With it, a solver line held at a plan point starts only when
 * the policy's own answer at that priority is the line's first step (that decision stays the policy's: not overridden,
 * no label), or, with probability {@code lineAskEps}, regardless (overridden, labelled {@link RlSeat#IMIT_LINESTART});
 * otherwise the policy's answer is played and nothing else changes. Whole train-mode games of the stand-in policy
 * against itself with the real solver (the rung's plans block, work units), no search service.
 *
 * <ul>
 * <li>off: no key anywhere; the spec round-trips;</li>
 * <li>lineAskEps 1 = the same games as off (every held line starts where off takes it): same digests, rows and
 *     answers; the decisions the policy started itself leave {@code overridden} and carry no label, the other starts
 *     carry reason 9 where off carried reason 1;</li>
 * <li>lineAskEps 0: a declined line's decision is the policy's own answer, not overridden, not labelled;</li>
 * <li>a declined line changes nothing else: declining after the line's first step was looked at gives the same games
 *     as declining before looking ({@code -Drl.lineAskTest}).</li>
 * </ul>
 *
 * <p>Plays real Forge games: cwd = forge-gui with res/; fixtures as RlActorBenchTest. {@code -Dgo.games} (default 6).
 */
public class RlLineAskTest {
    static final String PLANS = "\"plans\":{\"lineChoices\":\"forge\",\"lineKeep\":true,\"planX\":true,\"replanOnEntry\":true,"
            + "\"samples\":0,\"unified\":true,\"solverCfg\":{\"budgetMs\":\"6000\",\"copyBackFace\":\"true\",\"cycles\":\"true\","
            + "\"engineAfterPlanFail\":\"true\",\"engineMoves\":\"true\",\"engineMs\":\"4000\",\"engineNodesPerSearch\":\"true\","
            + "\"engineOpen\":\"true\",\"engineRerun\":\"true\",\"engineTriggerTargets\":\"true\",\"engineVerify\":\"true\","
            + "\"evalReserveMs\":\"1500\",\"followPay\":\"true\",\"hideOwnLibrary\":\"true\",\"historyLki\":\"true\","
            + "\"landEntersProbe\":\"true\",\"lineUncapped\":\"true\",\"offBattlefieldLimits\":\"true\",\"paySearch\":\"true\","
            + "\"planOrder\":\"7\",\"probeSettle\":\"true\",\"readerPerGame\":\"true\",\"sourceColorsLive\":\"true\","
            + "\"sourceLeaves\":\"true\",\"sourceOutput\":\"true\",\"stackAbilityCopies\":\"true\",\"stackHistory\":\"true\","
            + "\"stalePlayerStatics\":\"true\",\"staleStatics\":\"true\",\"turnLimits\":\"true\",\"tutorCast\":\"true\","
            + "\"unknownWorlds\":\"true\",\"verifyReplan\":\"true\",\"breakOnCounter\":\"true\"}}";
    static final String OFF = "{\"server\":\"127.0.0.1:1\",\"connectTimeoutMs\":200,\"provenOnly\":true,\"imit\":true,\"playout\":\"policy\","
            + "\"anytime\":true,\"workUnits\":true,\"workScale\":0.25,\"workWallMs\":600000,\"budgetMs\":8000," + PLANS + "}";

    static String on(final double eps) {
        return OFF.substring(0, OFF.length() - 1) + ",\"lineAsk\":true,\"lineAskEps\":" + eps + "}";
    }

    static final int GAMES = Integer.getInteger("go.games", 6);

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static RlActorBench.Played play(final FakeRlServer checker, final JsonObject game, final String spec,
            final RlSearchOverriddenTest.Keeping ep) throws Exception {
        final long t0 = System.nanoTime();
        final RlActorBench.Played p = RlImitLabelsTest.play(checker, game, spec, ep);
        Assert.assertNull(p.guardError);
        System.err.println("[line-ask] game played in " + (System.nanoTime() - t0) / 1_000_000_000L + " s, turns "
                + p.end.get("turns") + ", lines taken " + taken(p.end) + ", line_ask "
                + (p.end.getAsJsonObject("search").has("line_ask") ? ask(p.end).toString().replaceAll(",\"rows\".*", "}") : "off"));
        return p;
    }

    static RlSearchOverriddenTest.Keeping ep(final FakeRlServer checker) {
        return new RlSearchOverriddenTest.Keeping(new RlActorBenchTest.LocalEndpoint(checker));
    }

    static Map<Integer, JsonArray> labels(final JsonObject end) {
        final Map<Integer, JsonArray> out = new HashMap<>();
        for (JsonElement e : end.getAsJsonArray("imit")) {
            out.put(e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray());
        }
        return out;
    }

    static Map<Integer, JsonArray> played(final JsonObject tape) {
        final Map<Integer, JsonArray> out = new HashMap<>();
        for (JsonElement e : tape.getAsJsonArray("dec")) {
            out.put(e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(4).getAsJsonArray());
        }
        return out;
    }

    static JsonObject ask(final JsonObject end) {
        return end.getAsJsonObject("search").getAsJsonObject("line_ask");
    }

    static int taken(final JsonObject end) {
        int n = 0;
        final JsonObject plans = end.getAsJsonObject("search").getAsJsonObject("plans");
        for (String k : plans.keySet()) {
            n += plans.getAsJsonObject(k).get("provenLinesTaken").getAsInt();
        }
        return n;
    }

    static JsonArray one(final int step) {
        final JsonArray a = new JsonArray();
        a.add(step);
        return a;
    }

    @Test
    public void specKeys() {
        final RlSearch.Config off = RlSearch.Config.parse(JsonParser.parseString(OFF).getAsJsonObject());
        Assert.assertFalse(off.lineAsk);
        Assert.assertFalse(off.toJson().has("lineAsk") || off.toJson().has("lineAskEps"), "off: absent from the spec's JSON");
        final RlSearch.Config on = RlSearch.Config.parse(JsonParser.parseString(on(0.25)).getAsJsonObject());
        Assert.assertTrue(on.lineAsk && on.toJson().get("lineAsk").getAsBoolean());
        Assert.assertEquals(on.toJson().get("lineAskEps").getAsDouble(), 0.25);
        final String dflt = OFF.substring(0, OFF.length() - 1) + ",\"lineAsk\":true}";
        Assert.assertEquals(RlSearch.Config.parse(JsonParser.parseString(dflt).getAsJsonObject()).lineAskEps, 0.5);
        for (String bad : new String[] {"{\"server\":\"127.0.0.1:1\",\"lineAsk\":true}",
                "{\"server\":\"127.0.0.1:1\",\"provenOnly\":true,\"lineAsk\":true}",
                OFF.substring(0, OFF.length() - 1) + ",\"lineAskEps\":0.5}",
                OFF.substring(0, OFF.length() - 1) + ",\"lineAsk\":true,\"lineAskEps\":1.5}"}) {
            try {
                RlSearch.Config.parse(JsonParser.parseString(bad).getAsJsonObject());
                Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException e) {
                // refused
            }
        }
    }

    @Test
    public void epsOneIsTheSameGameAndTheStartRowsAreAsSpecified() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int offered = 0, byPolicy = 0, byEps = 0, forced = 0, start9 = 0, lines = 0;
        for (int i = 0; i < GAMES; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", "rl:M", true);
            final RlSearchOverriddenTest.Keeping e0 = ep(checker), e1 = ep(checker);
            final RlActorBench.Played off = play(checker, game, OFF, e0);
            final RlActorBench.Played on = play(checker, game, on(1.0), e1);
            Assert.assertFalse(off.end.getAsJsonObject("search").has("line_ask"), "off: no line_ask");
            Assert.assertFalse(off.end.getAsJsonObject("search").getAsJsonObject("spec").has("lineAsk"));
            // ---- the same game
            for (String k : new String[] {"digest", "digest_lb", "result", "turns", "reason"}) {
                Assert.assertEquals(on.end.get(k), off.end.get(k), "game " + i + " " + k);
            }
            Assert.assertEquals(on.tape.get("dec"), off.tape.get("dec"), "game " + i + ": the same rows and answers");
            Assert.assertEquals(taken(on.end), taken(off.end), "the same lines taken");
            lines += taken(off.end);
            final JsonObject la = ask(on.end);
            Assert.assertEquals(la.get("declined").getAsInt(), 0, "eps 1 declines nothing");
            Assert.assertEquals(la.get("offered").getAsInt(),
                    la.get("policy").getAsInt() + la.get("eps").getAsInt() + la.get("forced").getAsInt());
            Assert.assertEquals(la.get("offered").getAsInt(), taken(on.end), "every held line started");
            Assert.assertEquals(la.getAsJsonArray("rows").size(), la.get("offered").getAsInt());
            offered += la.get("offered").getAsInt();
            // ---- rows
            final TreeSet<Integer> overOff = RlSearchOverriddenTest.ints(off.end.getAsJsonArray("overridden"));
            final TreeSet<Integer> overOn = RlSearchOverriddenTest.ints(on.end.getAsJsonArray("overridden"));
            final Map<Integer, JsonArray> lOff = labels(off.end), lOn = labels(on.end), rows = played(on.tape);
            final TreeSet<Integer> own = new TreeSet<>();
            for (JsonElement e : la.getAsJsonArray("rows")) {
                final JsonObject r = e.getAsJsonObject();
                final int d = r.get("d").getAsInt();
                final String o = r.get("o").getAsString();
                Assert.assertTrue(overOff.contains(d), "off: every take's decision is overridden");
                if ("policy".equals(o)) {
                    byPolicy++;
                    own.add(d);
                    Assert.assertEquals(r.get("own").getAsInt(), r.get("c").getAsInt());
                    Assert.assertFalse(overOn.contains(d), "dec " + d + ": the policy's own start is not overridden");
                    Assert.assertFalse(lOn.containsKey(d), "dec " + d + ": and carries no label");
                    Assert.assertEquals(rows.get(d), one(r.get("c").getAsInt()), "its row is the policy's answer = the first step");
                    Assert.assertEquals(e1.own.get(d)[0], (short) r.get("c").getAsInt());
                    if (lOff.containsKey(d)) {
                        Assert.assertEquals(lOff.get(d).get(2).getAsJsonArray(), one(r.get("c").getAsInt()),
                                "dec " + d + ": the step asked is the step the hook played");
                    }
                } else {
                    Assert.assertTrue(overOn.contains(d), "dec " + d + ": a start the policy did not choose is overridden");
                    if ("eps".equals(o)) {
                        byEps++;
                        Assert.assertNotEquals(r.get("own").getAsInt(), r.get("c").getAsInt());
                    } else {
                        forced++;
                        Assert.assertEquals(o, "forced");
                    }
                    Assert.assertEquals(lOn.containsKey(d), lOff.containsKey(d), "dec " + d + ": labelled as off");
                    if (lOn.containsKey(d)) {
                        start9++;
                        Assert.assertEquals(lOff.get(d).get(1).getAsInt(), RlSeat.IMIT_LINE);
                        Assert.assertEquals(lOn.get(d).get(1).getAsInt(), RlSeat.IMIT_LINESTART, "dec " + d + ": reason linestart");
                        Assert.assertEquals(lOn.get(d).get(2), lOff.get(d).get(2), "the same steps");
                        if ("eps".equals(o)) {
                            Assert.assertEquals(lOn.get(d).get(2).getAsJsonArray(), one(r.get("c").getAsInt()),
                                    "dec " + d + ": the label is the step that was asked");
                        }
                    }
                }
            }
            final TreeSet<Integer> expect = new TreeSet<>(overOff);
            expect.removeAll(own);
            Assert.assertEquals(overOn, expect, "game " + i + ": overridden = off's less the policy's own starts");
            for (Map.Entry<Integer, JsonArray> e : lOff.entrySet()) {
                if (!own.contains(e.getKey()) && lOn.get(e.getKey()).get(1).getAsInt() != RlSeat.IMIT_LINESTART) {
                    Assert.assertEquals(lOn.get(e.getKey()), e.getValue(), "every other label is unchanged");
                }
            }
            Assert.assertEquals(lOn.size(), lOff.size() - (int) own.stream().filter(lOff::containsKey).count());
        }
        checker.close();
        System.err.println("[line-ask] eps1: games " + GAMES + " lines " + lines + " offered " + offered + " policy " + byPolicy
                + " eps " + byEps + " forced " + forced + " linestart labels " + start9);
        Assert.assertTrue(offered > 0, "no line was held in " + GAMES + " games");
        Assert.assertTrue(byPolicy > 0 && byEps > 0, "policy " + byPolicy + ", eps " + byEps);
    }

    @Test
    public void epsZeroADeclinedDecisionIsThePolicysOwn() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int declined = 0, byPolicy = 0;
        for (int i = 0; i < GAMES; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", "rl:M", true);
            final RlSearchOverriddenTest.Keeping e1 = ep(checker);
            final RlActorBench.Played on = play(checker, game, on(0.0), e1);
            final JsonObject la = ask(on.end);
            Assert.assertEquals(la.get("eps").getAsInt(), 0);
            Assert.assertEquals(taken(on.end), la.get("policy").getAsInt() + la.get("forced").getAsInt());
            final TreeSet<Integer> over = RlSearchOverriddenTest.ints(on.end.getAsJsonArray("overridden"));
            final Map<Integer, JsonArray> lab = labels(on.end), rows = played(on.tape);
            for (JsonElement e : la.getAsJsonArray("rows")) {
                final JsonObject r = e.getAsJsonObject();
                final int d = r.get("d").getAsInt();
                final String o = r.get("o").getAsString();
                if ("declined".equals(o)) {
                    declined++;
                    Assert.assertNotEquals(r.get("own").getAsInt(), r.get("c").getAsInt());
                    Assert.assertFalse(over.contains(d) || lab.containsKey(d), "dec " + d + ": declined = the policy's own row");
                    Assert.assertEquals(rows.get(d), one(r.get("own").getAsInt()), "dec " + d + ": the policy's answer is played");
                } else if ("policy".equals(o)) {
                    byPolicy++;
                    Assert.assertFalse(over.contains(d) || lab.containsKey(d));
                    Assert.assertEquals(rows.get(d), one(r.get("c").getAsInt()));
                }
            }
            // every row the search did not override is the policy's own answer
            final TreeSet<Integer> changed = RlSearchOverriddenTest.changed(on.tape, e1);
            Assert.assertTrue(over.containsAll(changed), "changed " + changed + " overridden " + over);
        }
        checker.close();
        System.err.println("[line-ask] eps0: declined " + declined + " policy " + byPolicy);
        Assert.assertTrue(declined > 0, "nothing declined");
    }

    @Test
    public void aDeclinedLineChangesNothingElse() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int offered = 0;
        try {
            for (int i = 0; i < GAMES; i++) {
                final JsonObject game = RlActorBenchTest.game(i, "rl:M", "rl:M", true);
                System.setProperty("rl.lineAskTest", "noPeek");
                final RlSearchOverriddenTest.Keeping e0 = ep(checker), e1 = ep(checker);
                final RlActorBench.Played blind = play(checker, game, on(0.0), e0);
                // the control: the same arm twice is the same game (work units: no wall clock in a decision)
                final RlActorBench.Played again = play(checker, game, on(0.0), ep(checker));
                Assert.assertEquals(again.end.get("digest"), blind.end.get("digest"), "game " + i + ": the control repeats");
                Assert.assertEquals(again.tape.get("dec"), blind.tape.get("dec"), "game " + i + ": the control repeats");
                System.setProperty("rl.lineAskTest", "declineAll");
                final RlActorBench.Played looked = play(checker, game, on(0.0), e1);
                for (String k : new String[] {"digest", "digest_lb", "result", "turns", "reason", "overridden"}) {
                    Assert.assertEquals(looked.end.get(k), blind.end.get(k), "game " + i + " " + k);
                }
                Assert.assertEquals(looked.tape.get("dec"), blind.tape.get("dec"), "game " + i + ": the same rows and answers");
                Assert.assertEquals(taken(looked.end), 0);
                Assert.assertEquals(looked.seat.searchOverridden, 0, "nothing is overridden when every line is declined");
                Assert.assertTrue(RlSearchOverriddenTest.changed(looked.tape, e1).isEmpty(), "every answer is the policy's own");
                Assert.assertEquals(ask(looked.end).get("offered"), ask(blind.end).get("offered"));
                Assert.assertEquals(ask(looked.end).get("declined"), ask(looked.end).get("offered"));
                offered += ask(looked.end).get("offered").getAsInt();
            }
        } finally {
            System.clearProperty("rl.lineAskTest");
            checker.close();
        }
        System.err.println("[line-ask] declined-only games: offered " + offered);
        Assert.assertTrue(offered > 0);
    }
}
