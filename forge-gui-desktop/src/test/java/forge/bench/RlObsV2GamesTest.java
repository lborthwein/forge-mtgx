package forge.bench;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import forge.bench.rl.RlKnowledgeOracle;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSchemaV2;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Observation v2 gate G1 (lane rl-obs-v2-1006): random TRAIN games with obs-v2 on, every sent frame checked against
 * the live game and the independent oracle (with the F1 hand rule). Hidden cards appear only where observed; o_seen
 * holds only cards the seat saw face up; known-hidden and o_seen tokens carry identity only; an opponent's face-down
 * card is {@code <unk>}. Mutants that must be caught: (a) v1's peek (zone 17 from the true library order) and (b) o_seen
 * filled from the opponent's true hand. Then the v2 tapes replay digest-identically. Plays real Forge games: run inside
 * a broker test lease; cwd = forge-gui with res/.
 */
public class RlObsV2GamesTest {

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBenchTest.setUp();
    }

    /** Checks every sent v2 frame. */
    static final class Witness implements RlSeat.FrameListener {
        final FakeRlServer checker;
        int frames, knownTokens, oSeenTokens, rels, facts;
        int peekFrames, peekCaught, handMutantFrames, handMutantCaught;
        final List<String> violations = new ArrayList<>();
        final Map<Game, RlKnowledgeOracle> oracles = new java.util.concurrent.ConcurrentHashMap<>();
        /** Per game and seat: card ids the seat has seen face up at a frame (a sweep of what it sees). */
        final Map<Game, List<Set<Integer>>> witnessed = new java.util.concurrent.ConcurrentHashMap<>();

        Witness(final FakeRlServer checker) {
            this.checker = checker;
        }

        void violation(final String v) {
            if (violations.size() < 50) {
                violations.add(v);
            }
        }

        @Override
        public void onFrame(final Game g, final Player seat, final RlWire.Decide f, final RlCandidates.Menu m,
                final RlFeaturizer.Obs o, final short[] steps, final JsonObject answer) {
            frames++;
            if (f.version != 2 || o.version != 2) {
                violation("not a v2 frame");
                return;
            }
            checker.check(f, false);
            final RlKnowledgeOracle oracle = oracles.get(g);
            final int s = g.getRegisteredPlayers().indexOf(seat);
            final Set<Integer> seen = witnessed.computeIfAbsent(g, k -> List.of(new HashSet<>(), new HashSet<>()))
                    .get(s);
            for (Player p : g.getPlayers()) {
                for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
                        ZoneType.Hand, ZoneType.Command, ZoneType.Stack}) {
                    for (Card c : p.getCardsIn(z)) {
                        if (!c.isFaceDown() && c.getView().canBeShownTo(seat.getView())) {
                            seen.add(c.getId());
                        }
                    }
                }
            }
            final boolean[] identityOnly = new boolean[f.L];
            for (Map.Entry<Integer, Integer> e : o.posByCardId.entrySet()) {
                final int pos = e.getValue();
                final Card c = g.findById(e.getKey());
                if (c == null) {
                    continue;
                }
                final int z = f.tokZone[pos];
                if (!c.getView().canBeShownTo(seat.getView())) {
                    knownTokens++;
                    identityOnly[pos] = true;
                    if ((z == RlSchema.Z_U_EXILE || z == RlSchema.Z_O_EXILE) && c.isFaceDown() && c.isInZone(ZoneType.Exile)) {
                        // note N2: a face-down exiled card the seat may not look at: presence only
                        if (f.tokCard[pos] != CardIndex.UNK) {
                            violation("a face-down exiled card resolved: " + c);
                        }
                        continue;
                    }
                    if (z != RlSchema.Z_O_HAND_KNOWN && z != RlSchema.Z_U_LIB_KNOWN && z != RlSchema.Z_O_LIB_KNOWN) {
                        violation("hidden card in zone " + z + ": " + c);
                    } else if (oracle == null || !oracle.justified(s, c)) {
                        violation("hidden card the seat never observed: " + c + " in " + c.getZone() + " (zone " + z
                                + ")");
                    }
                }
                final int want;
                if (!c.isFaceDown()) {
                    want = RlFeaturizer.resolveCard(RlActorBenchTest.index, c);
                } else if (c.getView().canFaceDownBeShownTo(seat.getView())) {
                    want = RlFeaturizer.resolveCard(RlActorBenchTest.index, c);
                } else {
                    want = CardIndex.UNK;
                }
                if (f.tokCard[pos] != want) {
                    violation("tok_card " + f.tokCard[pos] + " is not " + want + " for " + c);
                }
                if (c.isFaceDown() && !c.getView().canFaceDownBeShownTo(seat.getView())
                        && f.tokCard[pos] != CardIndex.UNK) {
                    violation("an opponent's face-down card resolved: " + c);
                }
            }
            // o_seen: cards the seat saw face up, identity only
            int j = 0;
            for (int i = 0; i < f.L; i++) {
                if (f.tokZone[i] != RlSchemaV2.Z_O_SEEN) {
                    continue;
                }
                oSeenTokens++;
                identityOnly[i] = true;
                final int id = o.oSeenIds.get(j++);
                if (!seen.contains(id) && (oracle == null || !oracle.everObserved(s, id))) {
                    final Card gone = g.findById(id);
                    violation("o_seen card never seen face up: id " + id + " tok_card " + f.tokCard[i] + " "
                            + (gone == null ? "(no longer in the game)" : gone + " in " + gone.getZone()));
                }
                final Card c = g.findById(id);
                if (c != null && c.getOwner() == seat) {
                    violation("o_seen holds an own card: " + c);
                }
            }
            for (int i = 0; i < f.L; i++) {
                if (identityOnly[i] && f.tokBits[i] != 0) {
                    violation("bits on an identity-only token " + i + " zone " + f.tokZone[i]);
                }
            }
            for (int i = 0; i < f.F; i++) {
                facts++;
                if (identityOnly[f.factTok[i]]) {
                    violation("fact " + RlSchemaV2.FACTS.get(f.factId[i] & 0xffff) + " on an identity-only token");
                }
            }
            for (int i = 0; i < f.R; i++) {
                rels++;
                if (identityOnly[f.relSrc[i]] || (f.relDst[i] >= 0 && identityOnly[f.relDst[i]])) {
                    violation("relation " + RlSchemaV2.REL_TYPES.get(f.relType[i]) + " touches an identity-only token");
                }
            }
            // mutant (a): zone 17 from the true library order
            if (oracle != null && !seat.getCardsIn(ZoneType.Library).isEmpty()) {
                peekFrames++;
                for (Card c : seat.getCardsIn(ZoneType.Library)) {
                    if (!c.getView().canBeShownTo(seat.getView()) && !oracle.justified(s, c)) {
                        peekCaught++;
                        break;
                    }
                }
            }
            // mutant (b): o_seen filled from the opponent's true hidden hand. Counted only in frames where that hand
            // holds a hidden card the seat does not know now: when the seat knows the whole hand (a reveal it saw, e.g.
            // Valki, God of Lies), the mutant leaks nothing and there is nothing to catch.
            final Player opp = RlFeaturizer.opponentOf(g, seat);
            if (oracle != null && opp != null && !opp.getCardsIn(ZoneType.Hand).isEmpty()) {
                boolean any = false, caught = false;
                for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                    if (c.getView().canBeShownTo(seat.getView()) || oracle.justified(s, c)) {
                        continue;
                    }
                    any = true;
                    if (!seen.contains(c.getId()) && !oracle.everObserved(s, c.getId())) {
                        caught = true;
                    }
                }
                if (any) {
                    handMutantFrames++;
                    handMutantCaught += caught ? 1 : 0;
                }
            }
        }
    }

    @Test
    public void visibilityOracleAndReplay() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, java.util.Collections.emptyList());
        final Witness w = new Witness(checker);
        final List<JsonObject> tapes = new ArrayList<>();
        final int games = Integer.getInteger("rl.v2Games", 16);
        RlActorBench.KNOWLEDGE_TAP = game -> {
            final RlKnowledgeOracle or = new RlKnowledgeOracle(game);
            w.oracles.put(game, or);
            return or;
        };
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("train");
        cfg.obsSchema = 2;
        try {
            for (int i = 0; i < games; i++) {
                final RlActorBenchTest.LocalEndpoint ep = new RlActorBenchTest.LocalEndpoint(checker);
                ep.version = 2;
                final RlActorBench.Played p = RlActorBench.play(cfg, "train",
                        RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true),
                        new RlFeaturizer(RlActorBenchTest.index), ep, w, "test");
                Assert.assertNull(p.guardError);
                Assert.assertEquals(p.tape.get("schema_sha").getAsString(), RlSchemaV2.schemaSha());
                tapes.add(p.tape);
            }
        } finally {
            RlActorBench.KNOWLEDGE_TAP = null;
        }
        System.err.println("[v2-visibility] frames " + w.frames + ", known hidden tokens " + w.knownTokens
                + ", o_seen tokens " + w.oSeenTokens + ", relations " + w.rels + ", facts " + w.facts
                + ", peek mutant caught " + w.peekCaught + "/" + w.peekFrames + ", hand mutant caught "
                + w.handMutantCaught + "/" + w.handMutantFrames + ", violations " + w.violations);
        Assert.assertTrue(w.frames >= Integer.getInteger("rl.visMinFrames", 1000), "only " + w.frames + " frames");
        Assert.assertTrue(w.violations.isEmpty(), String.valueOf(w.violations));
        Assert.assertEquals(checker.badFrames.get(), 0L, String.valueOf(checker.problems));
        Assert.assertTrue(w.peekFrames > 0 && w.peekCaught >= 0.9 * w.peekFrames,
                "peek mutant: " + w.peekCaught + "/" + w.peekFrames);
        Assert.assertTrue(w.handMutantFrames > 0 && w.handMutantCaught >= 0.9 * w.handMutantFrames,
                "hand mutant: " + w.handMutantCaught + "/" + w.handMutantFrames);
        Assert.assertTrue(w.rels > 0 && w.facts > 0, "relations and facts are sent");
        // replay: v2 tapes, the same seeds and decks, RL seats from the tape: equal digests
        int replayed = 0;
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
            g.addProperty("obs_schema", 2);
            final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("train"), "train", g,
                    new RlFeaturizer(RlActorBenchTest.index), RlActorBench.tapeEndpoint(t,
                            RlWire.parseUid(t.get("game_uid").getAsString())), null, "test");
            Assert.assertEquals(r.end.get("digest").getAsString(), t.get("digest").getAsString(),
                    "replay of game " + t.get("game_uid"));
            replayed++;
        }
        Assert.assertTrue(replayed > 0);
    }
}
