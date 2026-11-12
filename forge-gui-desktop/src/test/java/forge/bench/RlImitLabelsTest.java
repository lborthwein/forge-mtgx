package forge.bench;

import java.util.Collections;
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
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlSeat;

/**
 * Lane sdcc-imit-1111: with the search spec's {@code imit}, GAME_END and the tape carry {@code imit}, one
 * {@code [dec_idx, reason, [steps played]]} per decision whose answer a search component chose: every entry is an
 * overridden decision, its steps are the tape row's, and its reason is the component's kind (here exploration, the one
 * search component a game against the stand-in policy can run without a service: reason {@link RlSeat#IMIT_EXPLORE},
 * which a learner never imitates). The labels change nothing in the game (same digest, rows and overridden list as the
 * same spec without the key), and without the key no {@code imit} is written.
 *
 * <p>Plays real Forge games: cwd = forge-gui with res/; fixtures as RlActorBenchTest.
 */
public class RlImitLabelsTest {
    static final String OFF = RlSearchOverriddenTest.SPEC;
    static final String ON = OFF.substring(0, OFF.length() - 1) + ",\"imit\":true}";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static RlActorBench.Played play(final FakeRlServer checker, final JsonObject game, final String spec,
            final RlSearchOverriddenTest.Keeping ep) throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("train");
        cfg.search = RlSearch.Config.parse(JsonParser.parseString(spec).getAsJsonObject());
        return RlActorBench.play(cfg, "train", game.deepCopy(), new RlFeaturizer(RlActorBenchTest.index), ep, null, "test");
    }

    @Test
    public void specKeyRoundTrips() {
        Assert.assertFalse(RlSearch.Config.parse(JsonParser.parseString(OFF).getAsJsonObject()).imit);
        final RlSearch.Config on = RlSearch.Config.parse(JsonParser.parseString(ON).getAsJsonObject());
        Assert.assertTrue(on.imit);
        Assert.assertTrue(on.toJson().get("imit").getAsBoolean());
        Assert.assertFalse(RlSearch.Config.parse(JsonParser.parseString(OFF).getAsJsonObject()).toJson().has("imit"),
                "off: absent from the spec's JSON");
    }

    @Test
    public void labelsNameTheAnswersTheSearchPlayedAndChangeNothing() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int labels = 0;
        for (int i = 0; i < 3; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true);
            final RlSearchOverriddenTest.Keeping e0 = new RlSearchOverriddenTest.Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played off = play(checker, game, OFF, e0);
            final RlSearchOverriddenTest.Keeping e1 = new RlSearchOverriddenTest.Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played on = play(checker, game, ON, e1);
            Assert.assertNull(off.guardError);
            Assert.assertNull(on.guardError);
            // ---- off: no key anywhere
            for (String k : new String[] {"imit", "imit_hook_rows", "imit_map_failed"}) {
                Assert.assertFalse(off.end.has(k) || off.tape.has(k), "no " + k + " without the spec key");
            }
            // ---- on: the same game
            Assert.assertEquals(on.end.get("digest"), off.end.get("digest"), "game " + i + ": the labels change no answer");
            Assert.assertEquals(on.tape.get("dec"), off.tape.get("dec"));
            Assert.assertEquals(on.end.get("overridden"), off.end.get("overridden"));
            Assert.assertEquals(on.end.get("search_overridden"), off.end.get("search_overridden"));
            Assert.assertEquals(on.end.get("imit_hook_rows").getAsInt(), 0, "no line was followed");
            Assert.assertEquals(on.tape.get("imit"), on.end.get("imit"), "the tape's list is GAME_END's");
            // ---- the labels: exactly the answers the search changed, with the steps played
            final TreeSet<Integer> changed = RlSearchOverriddenTest.changed(on.tape, e1);
            final TreeSet<Integer> over = RlSearchOverriddenTest.ints(on.end.getAsJsonArray("overridden"));
            final java.util.Map<Integer, JsonArray> played = new java.util.HashMap<>();
            for (JsonElement e : on.tape.getAsJsonArray("dec")) {
                played.put(e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(4).getAsJsonArray());
            }
            final TreeSet<Integer> labelled = new TreeSet<>();
            for (JsonElement e : on.end.getAsJsonArray("imit")) {
                final JsonArray row = e.getAsJsonArray();
                final int d = row.get(0).getAsInt();
                Assert.assertTrue(labelled.add(d), "one label per decision");
                Assert.assertTrue(over.contains(d), "a labelled decision is overridden");
                Assert.assertEquals(row.get(1).getAsInt(), RlSeat.IMIT_EXPLORE, "dec " + d + ": an exploration deviation");
                Assert.assertEquals(row.get(2).getAsJsonArray(), played.get(d), "dec " + d + ": the steps played");
            }
            Assert.assertEquals(labelled, changed, "game " + i + ": the labels are the changed answers");
            labels += labelled.size();
        }
        checker.close();
        System.err.println("[imit-labels] labels " + labels);
        Assert.assertTrue(labels > 0);
    }

    // ---------------------------------------------------------------- sdcc-imit2-1111: a line's choice asks as rows

    static final String LINE_OFF = "{\"server\":\"127.0.0.1:1\",\"connectTimeoutMs\":200,\"provenOnly\":true}";
    static final String LINE_ON = "{\"server\":\"127.0.0.1:1\",\"connectTimeoutMs\":200,\"provenOnly\":true,\"imit\":true}";

    /**
     * The stand-in policy keyed on the frame's content, not its dec_idx: the rows a line adds shift every later
     * dec_idx, and the same ask must still get the same answer in both arms. Keeps every frame by dec_idx.
     */
    static final class ByContent implements RlSeat.Endpoint {
        final FakeRlServer checker;
        final java.util.Map<Integer, forge.bench.rl.RlWire.Decide> frames = new java.util.HashMap<>();

        ByContent(final FakeRlServer checker) {
            this.checker = checker;
        }

        @Override
        public forge.bench.rl.RlWire.Decision decide(final byte[] p) {
            final forge.bench.rl.RlWire.Decide d = forge.bench.rl.RlWire.decodeDecide(p, 1);
            checker.check(d, false);
            frames.put(d.decIdx, d);
            final forge.bench.rl.RlWire.Decide k = forge.bench.rl.RlWire.decodeDecide(p, 1);
            k.decIdx = 0;
            final forge.bench.rl.RlWire.Decision x = new forge.bench.rl.RlWire.Decision();
            x.gameUid = d.gameUid;
            x.decIdx = d.decIdx;
            x.status = forge.bench.rl.RlWire.ST_OK;
            x.policyVersion = 1;
            x.steps = FakeRlServer.randomSteps(d, new java.util.Random(FakeRlServer.mix(7, d.gameUid,
                    java.util.Arrays.hashCode(forge.bench.rl.RlWire.encodeDecide(k)))));
            return x;
        }

        @Override
        public void record(final byte[] p) {
            throw new IllegalStateException("no RECORD frame in a train game");
        }
    }

    /**
     * A stand-in for a followed line, with no solver: it is always "following", plays every other priority itself
     * (Forge AI's own action, prepared with its targets and costs as a line's step is), declares every other attack,
     * answers every other card pick, look and order, and leaves every other choice ask and the cost cards to Forge
     * AI (plans.lineChoices=forge). Deterministic in the game, so both arms meet the same hook.
     */
    static final class StandInLine implements PlayerControllerBridge.PlanHook {
        int n;
        final java.util.Map<String, Integer> did = new java.util.TreeMap<>();

        private boolean mine(final String what) {
            final boolean b = (n++ & 1) == 0;
            if (b) {
                did.merge(what, 1, Integer::sum);
            }
            return b;
        }

        /** The hook just floated mana: its next priority answer is its own (the action the mana is for). */
        boolean floated;
        int prio;

        @Override
        public java.util.List<forge.game.spellability.SpellAbility> priority(final PlayerControllerBridge c) {
            if (floated) {
                floated = false;
                did.merge("afterFloat", 1, Integer::sum);
                return c.getAi().chooseSpellAbilityToPlay();
            }
            if (!mine("priority")) {
                return PlayerControllerBridge.NO_OPINION;
            }
            if (prio++ % 3 == 1) {
                // as a line's pay-float step: a mana ability of an untapped land, before the action it pays for
                final forge.game.player.Player me = c.getPlayer();
                final forge.game.phase.PhaseHandler ph = me.getGame().getPhaseHandler();
                if (ph.isPlayerTurn(me) && ph.getPhase().isMain() && me.getGame().getStack().isEmpty()) {
                    for (forge.game.card.Card land : me.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                        if (!land.isLand() || land.isTapped()) {
                            continue;
                        }
                        for (forge.game.spellability.SpellAbility m : land.getManaAbilities()) {
                            final forge.game.spellability.SpellAbility a = m.copy(me);
                            a.setActivatingPlayer(me);
                            if (a.canPlay() && forge.ai.ComputerUtilCost.canPayCost(a, me, false)) {
                                floated = true;
                                did.merge("float", 1, Integer::sum);
                                final java.util.List<forge.game.spellability.SpellAbility> out = new java.util.ArrayList<>();
                                out.add(a);
                                return out;
                            }
                        }
                    }
                }
            }
            return c.getAi().chooseSpellAbilityToPlay();
        }

        @Override
        public boolean lineActive() {
            return true;
        }

        @Override
        public boolean forgeChoices() {
            return true;
        }

        @Override
        public boolean forgeCosts() {
            return true;
        }

        @Override
        public boolean attack(final forge.game.player.Player attacker, final forge.game.combat.Combat combat) {
            if (!mine("attack")) {
                return false;
            }
            ((PlayerControllerBridge) attacker.getController()).getAi().declareAttackers(attacker, combat);
            return true;
        }

        @Override
        public forge.game.card.Card pick(final forge.game.zone.ZoneType destination,
                final java.util.List<forge.game.zone.ZoneType> origin, final forge.game.spellability.SpellAbility sa,
                final forge.game.card.CardCollection choices, final forge.game.player.Player decider) {
            return mine("pick") ? choices.get(choices.size() - 1) : null;
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection> look(
                final forge.game.card.CardCollection topN) {
            if (!mine("look")) {
                return null;
            }
            final forge.game.card.CardCollection top = new forge.game.card.CardCollection(topN);
            java.util.Collections.reverse(top);
            return org.apache.commons.lang3.tuple.ImmutablePair.of(top, new forge.game.card.CardCollection());
        }

        @Override
        public forge.game.card.CardCollectionView order(final forge.game.card.CardCollectionView cards,
                final forge.game.zone.ZoneType destination, final forge.game.spellability.SpellAbility source) {
            if (!mine("order")) {
                return null;
            }
            final forge.game.card.CardCollection out = new forge.game.card.CardCollection(cards);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    static RlActorBench.Played playLine(final JsonObject game, final String spec, final RlSeat.Endpoint ep,
            final java.util.List<StandInLine> hooks) throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("train");
        cfg.search = RlSearch.Config.parse(JsonParser.parseString(spec).getAsJsonObject());
        return RlActorBench.play(cfg, "train", game.deepCopy(), new RlFeaturizer(RlActorBenchTest.index), ep, null, "test",
                (g, seat, know) -> {
                    for (forge.game.player.Player p : g.getRegisteredPlayers()) {
                        if (p.getController() instanceof PlayerControllerBridge
                                && ((PlayerControllerBridge) p.getController()).getMode() == BenchSession.Mode.BRIDGE) {
                            final StandInLine h = new StandInLine();
                            hooks.add(h);
                            ((PlayerControllerBridge) p.getController()).planHook = h;
                        }
                    }
                });
    }

    /** A tape row without its dec_idx: [seat, family, C, steps]. */
    static String rowKey(final JsonArray row) {
        return row.get(1) + "/" + row.get(2) + "/" + row.get(3) + "/" + row.get(4);
    }

    /**
     * With {@code imit}, every ask of a followed line that the seat is not asked is a row of its own: the priorities the
     * hook plays (reason line) and the choice asks the hook or Forge AI answers, the targets the step was prepared
     * with, the hook's attack and the cost cards (reason linechoice). The game is the same game (digest), the seat's
     * own rows are the same rows with the same answers, every added row is overridden, and every label's steps are
     * legal candidates of the frame that was sent. Without the key nothing is added.
     */
    @Test
    public void aLinesChoiceAsksAreRowsAndTheGameIsTheSame() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        final java.util.Map<String, Integer> byFamily = new java.util.TreeMap<>();
        final java.util.Map<String, Integer> notes = new java.util.TreeMap<>();
        final java.util.Map<String, Integer> did = new java.util.TreeMap<>();
        int line = 0, choice = 0, added = 0, unmatched = 0;
        final int games = Integer.getInteger("rl.imitLineGames", 4);
        for (int i = 0; i < games; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true);
            final java.util.List<StandInLine> h0 = new java.util.ArrayList<>(), h1 = new java.util.ArrayList<>();
            final RlActorBench.Played off = playLine(game, LINE_OFF, new ByContent(checker), h0);
            final ByContent ep = new ByContent(checker);
            final RlActorBench.Played on = playLine(game, LINE_ON, ep, h1);
            Assert.assertNull(off.guardError);
            Assert.assertNull(on.guardError);
            Assert.assertTrue(off.end.get("void").isJsonNull() && on.end.get("void").isJsonNull(), "game " + i + " void");
            for (StandInLine h : h1) {
                h.did.forEach((k, v) -> did.merge(k, v, Integer::sum));
            }
            // ---- off: nothing new
            for (String k : new String[] {"imit", "imit_hook_rows", "imit_choice_rows", "imit_map_failed"}) {
                Assert.assertFalse(off.end.has(k) || off.tape.has(k), "no " + k + " without the spec key");
            }
            for (String k : off.tape.getAsJsonObject("census").getAsJsonObject("notes").keySet()) {
                Assert.assertFalse(k.startsWith("imit."), "no census note " + k + " without the spec key");
            }
            Assert.assertEquals(off.seat.imitAdded.size(), 0);
            // ---- on: the same game
            Assert.assertEquals(on.end.get("digest"), off.end.get("digest"), "game " + i + ": the rows change no answer");
            Assert.assertEquals(on.end.get("digest_lb"), off.end.get("digest_lb"));
            Assert.assertEquals(on.end.get("result"), off.end.get("result"));
            Assert.assertEquals(on.end.get("turns"), off.end.get("turns"));
            // ---- the seat's own rows are the same rows, in order, with the same answers
            final TreeSet<Integer> extra = new TreeSet<>(on.seat.imitAdded);
            Assert.assertEquals(extra.size(), on.seat.imitAdded.size());
            Assert.assertEquals(on.end.get("imit_hook_rows").getAsInt() + on.end.get("imit_choice_rows").getAsInt(),
                    extra.size(), "the added rows are the hook rows and the choice rows");
            final java.util.List<String> own = new java.util.ArrayList<>(), base = new java.util.ArrayList<>();
            final java.util.Map<Integer, JsonArray> rows = new java.util.HashMap<>();
            for (JsonElement e : on.tape.getAsJsonArray("dec")) {
                final JsonArray row = e.getAsJsonArray();
                rows.put(row.get(0).getAsInt(), row);
                if (!extra.contains(row.get(0).getAsInt())) {
                    own.add(rowKey(row));
                }
            }
            for (JsonElement e : off.tape.getAsJsonArray("dec")) {
                base.add(rowKey(e.getAsJsonArray()));
            }
            Assert.assertEquals(own, base, "game " + i + ": the seat's own rows");
            final TreeSet<Integer> over = RlSearchOverriddenTest.ints(on.end.getAsJsonArray("overridden"));
            Assert.assertTrue(over.containsAll(extra), "every added row is overridden");
            Assert.assertEquals(over.size() - extra.size(), off.end.getAsJsonArray("overridden").size(),
                    "no other decision became overridden");
            // ---- the labels: on added rows only, the tape row's steps, legal candidates of the frame sent
            Assert.assertEquals(on.tape.get("imit"), on.end.get("imit"), "the tape's list is GAME_END's");
            final TreeSet<Integer> labelled = new TreeSet<>();
            for (JsonElement e : on.end.getAsJsonArray("imit")) {
                final JsonArray l = e.getAsJsonArray();
                final int d = l.get(0).getAsInt(), reason = l.get(1).getAsInt();
                Assert.assertTrue(labelled.add(d), "one label per decision");
                Assert.assertTrue(extra.contains(d), "dec " + d + ": a label on an added row");
                final JsonArray row = rows.get(d);
                Assert.assertEquals(l.get(2).getAsJsonArray(), row.get(4).getAsJsonArray(), "dec " + d + ": the steps played");
                final forge.bench.rl.RlWire.Decide f = ep.frames.get(d);
                Assert.assertNotNull(f, "dec " + d + " was sent");
                final int fam = row.get(2).getAsInt();
                Assert.assertEquals(f.family, fam);
                Assert.assertEquals(reason, fam == forge.bench.rl.RlSchema.F_PRIORITY ? RlSeat.IMIT_LINE : RlSeat.IMIT_LINECHOICE,
                        "dec " + d + ": a priority is a line step, any other ask a line choice");
                final boolean[] seen = new boolean[f.C];
                for (JsonElement st : l.get(2).getAsJsonArray()) {
                    final int k = st.getAsInt();
                    Assert.assertTrue(k >= 0 && k < f.C && f.candKind[k] > 0, "dec " + d + ": step " + k + " is a legal candidate");
                    if (f.mode != forge.bench.rl.RlSchema.M_ASSIGN) {
                        Assert.assertFalse(seen[k], "dec " + d + ": a candidate picked twice");
                    }
                    seen[k] = true;
                }
                if (f.mode == forge.bench.rl.RlSchema.M_SINGLE) {
                    Assert.assertEquals(l.get(2).getAsJsonArray().size(), 1);
                } else if (f.mode == forge.bench.rl.RlSchema.M_SUBSET) {
                    final int k = l.get(2).getAsJsonArray().size();
                    Assert.assertTrue(k >= f.minPick && k <= f.maxPick, "dec " + d + ": " + k + " picks");
                } else if (f.mode == forge.bench.rl.RlSchema.M_PERMUTE) {
                    Assert.assertEquals(l.get(2).getAsJsonArray().size(), f.C);
                } else {
                    Assert.assertEquals(l.get(2).getAsJsonArray().size(), f.S);
                }
                if (reason == RlSeat.IMIT_LINE) {
                    line++;
                } else {
                    choice++;
                    byFamily.merge(forge.bench.rl.RlSchema.familyName(fam), 1, Integer::sum);
                }
            }
            Assert.assertEquals(extra.size() - labelled.size(), on.end.get("imit_map_failed").getAsInt(),
                    "an added row without a label is an answer no candidate names");
            added += extra.size();
            unmatched += on.end.get("imit_map_failed").getAsInt();
            final JsonObject nt = on.tape.getAsJsonObject("census").getAsJsonObject("notes");
            for (String k : nt.keySet()) {
                if (k.startsWith("imit.")) {
                    notes.merge(k, nt.get(k).getAsInt(), Integer::sum);
                }
            }
        }
        checker.close();
        System.err.println("[imit-line] games " + games + " added rows " + added + " line " + line + " linechoice " + choice
                + " " + byFamily + " unmatched " + unmatched + " hook " + did + " notes " + notes);
        Assert.assertEquals(checker.badFrames.get(), 0L, "every added frame is a valid frame");
        Assert.assertTrue(line > 0, "line steps were labelled");
        Assert.assertTrue(choice > 0 && byFamily.size() >= 3, "choice asks of several families were labelled: " + byFamily);
        Assert.assertTrue(byFamily.containsKey("ATTACK"), "the line's attack is a row");
        Assert.assertTrue(byFamily.containsKey("TARGETS"), "a step's targets are rows");
        // a priority the hook answered with a mana ability: no candidate of its menu, so no label, unless the hook's
        // next action is on that menu (then the priority is labelled with the action the mana was floated for)
        Assert.assertTrue(notes.getOrDefault("imit.prio.unmatched.mana", 0) > 0, "the stand-in floated mana at a recorded priority");
        Assert.assertTrue(notes.getOrDefault("imit.prio.float.labelled", 0) > 0, "a float took its action as the label");
        int counted = -notes.getOrDefault("imit.prio.float.labelled", 0);
        for (java.util.Map.Entry<String, Integer> e : notes.entrySet()) {
            if (e.getKey().contains(".unmatched.")) {
                counted += e.getValue();
            }
        }
        Assert.assertEquals(unmatched, counted, "every unlabelled added row is counted with its cause");
        Assert.assertEquals(notes.getOrDefault("imit.choice.TARGETS.unmatched.map", 0).intValue(), 0,
                "a targeted spell on the stack is named by its object");
    }
}
