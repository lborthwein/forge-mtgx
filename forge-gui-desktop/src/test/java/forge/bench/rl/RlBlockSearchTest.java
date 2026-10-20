package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.SimulationTest;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane block-search-1019: the look-ahead over the policy seat's declare-blockers asks (spec key {@code blocks}, off by
 * default). The owner's live game of 2026-10-19 (session d5b5c797): AI Player (Hard) at 20 life and 0 poison did not
 * block Blightsteel Colossus (11/11 trample, infect) with its untapped 3/3 Nissa-animated Forest, and lost to poison. On
 * live S-b2h a declare-blockers ask was the policy's greedy pick: neither the look-ahead nor the solver ever saw it.
 *
 * <p>The tests drive the live seat ({@link RlLiveSeat}) on a constructed position against {@link FakeSearchService}. Its
 * scripted policy declares no blocks wherever it is asked (the blind spot), except where a test makes the live seat's own
 * declaration something else; every play-out seat passes and declares nothing. The leaf v(o) is
 * {@link #leaf}: 0.3 per creature of ours outside the lands plus half our life fraction, minus 0.5, so a chump that costs
 * a valuable creature to save a little life is worse, and a loss (a terminal play-out) is worst. No rule about chump blocks
 * is anywhere in the search: the candidates (the policy's, none, Forge AI's) are played out and valued.
 *
 * <p>Plays real Forge copies on the search's pool: run inside a broker test lease, cwd = forge-gui with res/.
 */
public class RlBlockSearchTest extends SimulationTest {
    static final String SHA = "7f7d160785e58bd1da37435f8ed71e145d761fb34f9d45b40809880982388465";
    static final long SEED = 741461525L;

    private static Path indexFile;

    private static synchronized Path index() throws Exception {
        if (indexFile == null) {
            final TreeSet<String> names = new TreeSet<>(Arrays.asList("Forest", "Plains", "Mountain", "Island", "Swamp",
                    "Titan of Industry", "Black Lotus", "Decadent Dragon", "Nissa, Who Shakes the World",
                    "Shelldock Isle", "Blightsteel Colossus", "Chaos Defiler", "The One Ring", "Colossal Dreadmaw",
                    "Serra Angel", "Runeclaw Bear"));
            final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
            int i = 2;
            for (String n : names) {
                sb.append(n).append('\t').append(i++).append('\n');
            }
            indexFile = Files.createTempFile("block-search-index-", ".tsv");
            Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return indexFile;
    }

    static String spec(final String server, final String extra) throws Exception {
        return "server=" + server + ",policySha=" + SHA + ",cardIndex=" + index() + ",threads=4"
                + (extra.isEmpty() ? "" : "," + extra);
    }

    /** v(o) of a leaf (obs-v1): 0.3 per creature of ours (lands excluded) + half our life / 20 - 0.5. */
    static double leaf(final byte[] payload) {
        final RlWire.Decide d = RlWire.decodeDecide(payload, 1);
        int creatures = 0;
        for (int i = 0; i < d.L; i++) {
            if (d.tokZone[i] == RlSchema.Z_U_CRE) {
                creatures++;
            }
        }
        return Math.max(-1, Math.min(1, 0.3 * creatures + 0.5 * d.scal[3] - 0.5));
    }

    /** The scripted policy: the live seat's own block declaration is {@code liveBlocks}; every other ask passes. */
    static final class Script implements java.util.function.Function<RlWire.Decide, short[]> {
        final long liveUid;
        /** The live seat's declaration: blocker card id -> attacker card id (empty = no blocks). */
        final java.util.Map<Integer, Integer> liveBlocks;
        final java.util.concurrent.atomic.AtomicInteger liveBlockAsks = new java.util.concurrent.atomic.AtomicInteger();

        Script(final long liveUid, final java.util.Map<Integer, Integer> liveBlocks) {
            this.liveUid = liveUid;
            this.liveBlocks = liveBlocks;
        }

        @Override
        public short[] apply(final RlWire.Decide d) {
            if (d.family == RlSchema.F_BLOCK || d.family == RlSchema.F_ATTACK) {
                final boolean live = d.gameUid == liveUid && d.family == RlSchema.F_BLOCK;
                if (live) {
                    liveBlockAsks.incrementAndGet();
                }
                final short[] out = new short[d.S];
                for (int s = 0; s < d.S; s++) {
                    out[s] = -1;
                    for (int i = 0; i < d.C; i++) {
                        if (d.candSlot[i] == s && d.candKind[i] == RlSchema.K_NONE) {
                            out[s] = (short) i;
                        }
                    }
                }
                return out;
            }
            if (d.family == RlSchema.F_PRIORITY) {
                for (int i = 0; i < d.C; i++) {
                    if (d.candKind[i] == RlSchema.K_PASS) {
                        return new short[] {(short) i};
                    }
                }
            }
            return null;
        }
    }

    /** One run: the position, the seat bound, its declare-blockers ask answered. */
    static final class Run {
        Combat combat;
        Card attacker, blocker;
        String err;
        long decides;
        int departures;
        List<String> blockLines = new ArrayList<>();
        String bootLine;
    }

    private interface Board {
        /** Build the board of p0 (the policy seat, defending) and p1 (attacking); return {attacker, blocker}. */
        Card[] build(Game g, Player p0, Player p1);
    }

    /** Review S2: the live seat's 8 block samples alternate "slot 0 blocks" / "slot 1 blocks" (null = off). */
    private volatile boolean splitSamples = false;

    private Run run(final String extra, final Board board, final boolean liveChumps) throws Exception {
        final Run r = new Run();
        final PrintStream was = System.err;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            svc.leafValue = RlBlockSearchTest::leaf;
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), extra), "block-search-test");
            Assert.assertNotNull(live, "the seat binds the fake service");
            final LobbyPlayerAi ai = new LobbyPlayerAi("Seat1", null);
            ai.setAiProfile("Default");
            final List<RegisteredPlayer> players = Arrays.asList(
                    new RegisteredPlayer(new Deck()).setPlayer(live.lobby("Seat0", 0, "Default")),
                    new RegisteredPlayer(new Deck()).setPlayer(ai));
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(players, rules, new Match(rules, players, "block-search"));
            final Player p0 = game.getRegisteredPlayers().get(0);
            final Player p1 = game.getRegisteredPlayers().get(1);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1, 8);
            final Card[] ab = board.build(game, p0, p1);
            r.attacker = ab[0];
            r.blocker = ab[1];
            game.getAction().checkStateEffects(true);
            final long liveUid = RlSearch.splitmix(SEED ^ 0x6c697665L);
            final java.util.Map<Integer, Integer> lb = new java.util.TreeMap<>();
            if (liveChumps) {
                lb.put(r.blocker.getId(), r.attacker.getId());
            }
            final Script script = new Script(liveUid, lb);
            final java.util.Set<Long> even = new java.util.HashSet<>(), odd = new java.util.HashSet<>();
            for (int j = 0; j < 64; j++) {
                (j % 2 == 0 ? even : odd).add(RlSearch.splitmix(liveUid ^ (0xb10c0000L + j)));
            }
            final java.util.function.Function<RlWire.Decide, short[]> base = liveChumps ? d -> {
                if (d.gameUid == liveUid && d.family == RlSchema.F_BLOCK) {
                    // the live seat's own (greedy) declaration: the blocker on the attacker
                    final short[] out = script.apply(d);
                    for (int i = 0; i < d.C; i++) {
                        if (d.candSlot[i] == 0 && d.candKind[i] == RlSchema.K_ATTACKER) {
                            out[0] = (short) i;
                        }
                    }
                    return out;
                }
                return script.apply(d);
            } : script;
            svc.script = !splitSamples ? base : d -> {
                if (d.family == RlSchema.F_BLOCK && (even.contains(d.gameUid) || odd.contains(d.gameUid))) {
                    // a sampled DECIDE of the live block frame: alternately the slot-0 and the slot-1 blocker blocks
                    final short[] out = script.apply(d);
                    final int slot = even.contains(d.gameUid) ? 0 : 1;
                    for (int i = 0; i < d.C; i++) {
                        if (d.candSlot[i] == slot && d.candKind[i] == RlSchema.K_ATTACKER) {
                            out[slot] = (short) i;
                        }
                    }
                    return out;
                }
                return base.apply(d);
            };
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, p1, false, 8);
            final Combat combat = new Combat(p1);
            combat.addAttacker(r.attacker, p0);
            r.attacker.setTapped(!r.attacker.hasKeyword(forge.game.keyword.Keyword.VIGILANCE));
            game.getPhaseHandler().setCombat(combat);
            r.combat = combat;
            live.bind(game, 0, SEED, null, AiFixes.Mode.OFF);
            final long d0 = svc.decides.get();
            try {
                p0.getController().declareBlockers(p0, combat);
            } finally {
                live.finish();
            }
            r.decides = svc.decides.get() - d0;
            System.err.flush();
            r.err = buf.toString(StandardCharsets.UTF_8);
            for (String l : r.err.split("\n")) {
                if (l.startsWith("[lookahead-decision] ") && l.contains("\"kind\":\"blocks\"")) {
                    r.blockLines.add(l);
                }
                if (l.startsWith("[policy-seat] ") && l.contains("\"bound\":true")) {
                    r.bootLine = l;
                }
                if (l.startsWith("[policy-seat] ") && l.contains("\"end\":true")) {
                    final com.google.gson.JsonObject o = com.google.gson.JsonParser
                            .parseString(l.substring("[policy-seat] ".length())).getAsJsonObject();
                    r.departures = o.has("searchDepartures") ? o.get("searchDepartures").getAsInt() : -1;
                }
            }
        } finally {
            System.setErr(was);
            was.print(buf.toString(StandardCharsets.UTF_8));
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------------ positions

    /**
     * The owner's game at the AI's declare-blockers ask (turn 8; state.json gameTape events 147-148): AI (p0) at 20 life,
     * 0 poison, 3 cards in hand; Forest animated by Nissa (+1: three +1/+1 counters, a 0/0 Elemental creature land with
     * vigilance and haste) untapped, Plains, Forest and Mountain tapped; Black Lotus, Decadent Dragon and Nissa in its
     * graveyard. The owner (p1) at 16 life attacks with Blightsteel Colossus; Chaos Defiler (summoning sick) stays home;
     * Shelldock Isle and a Swamp untapped, an Island and a Swamp tapped; The One Ring in hand.
     */
    private Card[] blightsteel(final Game g, final Player p0, final Player p1) {
        p0.setLife(20, null);
        p1.setLife(16, null);
        final Card forest = addCard("Forest", p0);
        for (String n : new String[] {"Plains", "Forest", "Mountain"}) {
            addCard(n, p0).setTapped(true);
        }
        final Card nissa = addCard("Nissa, Who Shakes the World", p0);
        nissa.setCounters(CounterEnumType.LOYALTY, 5);
        SpellAbility plus = null;
        for (SpellAbility sa : nissa.getSpellAbilities()) {
            if (sa.getApi() == ApiType.PutCounter) {
                plus = sa;
            }
        }
        Assert.assertNotNull(plus, "Nissa's +1");
        plus.setActivatingPlayer(p0);
        plus.getTargets().add(forest);
        AbilityUtils.resolve(plus);
        g.getAction().moveToGraveyard(nissa, null);
        for (String n : new String[] {"Black Lotus", "Decadent Dragon"}) {
            addCardToZone(n, p0, ZoneType.Graveyard);
        }
        for (String n : new String[] {"Titan of Industry", "Plains", "Forest"}) {
            addCardToZone(n, p0, ZoneType.Hand);
        }
        fillLibrary(p0, 30);
        addCard("Shelldock Isle", p1);
        addCard("Island", p1).setTapped(true);
        addCard("Swamp", p1).setTapped(true);
        addCard("Swamp", p1);
        final Card bsc = addCard("Blightsteel Colossus", p1);
        bsc.setSickness(false);
        addCard("Chaos Defiler", p1);
        addCardToZone("The One Ring", p1, ZoneType.Hand);
        fillLibrary(p1, 26);
        g.getAction().checkStateEffects(true);
        Assert.assertTrue(forest.isCreature() && forest.isLand(), "the Forest is a creature land");
        Assert.assertEquals(forest.getNetPower(), 3);
        Assert.assertEquals(forest.getNetToughness(), 3);
        Assert.assertFalse(forest.isTapped(), "untapped");
        return new Card[] {bsc, forest};
    }

    /**
     * Not lethal, a valuable blocker: AI (p0) at 20 life with an untapped Serra Angel (4/4 flying vigilance); the opponent
     * attacks with Colossal Dreadmaw (6/6 trample). Chumping loses the Angel to save 4 life.
     */
    private Card[] dreadmaw(final Game g, final Player p0, final Player p1) {
        p0.setLife(20, null);
        p1.setLife(20, null);
        for (String n : new String[] {"Plains", "Plains", "Plains", "Plains"}) {
            addCard(n, p0).setTapped(true);
        }
        final Card angel = addCard("Serra Angel", p0);
        angel.setSickness(false);
        fillLibrary(p0, 30);
        for (String n : new String[] {"Forest", "Forest", "Forest", "Forest", "Forest", "Forest"}) {
            addCard(n, p1).setTapped(true);
        }
        final Card maw = addCard("Colossal Dreadmaw", p1);
        maw.setSickness(false);
        fillLibrary(p1, 30);
        return new Card[] {maw, angel};
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Test
    public void specWithoutBlocksIsUnchangedAndKeysAreChecked() throws Exception {
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", ""));
        Assert.assertEquals(s.search.blocks, 0, "absent = off");
        Assert.assertFalse(s.search.toJson().has("blocks"), "the boot line's search JSON is unchanged");
        Assert.assertFalse(s.search.toJson().has("blockSamples"));
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "blocks=1,blocks.samples=6,blocks.top=2"));
        Assert.assertEquals(on.search.blocks, 1);
        Assert.assertEquals(on.search.blockSamples, 6);
        Assert.assertEquals(on.search.blockTop, 2);
        Assert.assertEquals(on.search.toJson().get("blocks").getAsInt(), 1);
        Assert.assertEquals(RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "blocks=0")).search.toJson().has("blocks"), false);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "blocks=2")));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "blocks=yes")));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "blocks=1,blocks.top=0")));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "blocks=1,playout=forge")));
        // the bench spec (JSON) takes the same keys and refuses forge play-outs with blocks
        final com.google.gson.JsonObject j = com.google.gson.JsonParser.parseString(
                "{\"server\":\"127.0.0.1:1\",\"playout\":\"policy\",\"blocks\":1}").getAsJsonObject();
        Assert.assertEquals(RlSearch.Config.parse(j).blocks, 1);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(com.google.gson.JsonParser
                .parseString("{\"server\":\"127.0.0.1:1\",\"blocks\":1}").getAsJsonObject()));
    }

    /** Absent key: the policy's own declaration (no blocks) stands, with one DECIDE and no block line, as today. */
    @Test
    public void absentKeyKeepsThePolicysDeclaration() throws Exception {
        final Run r = run("", this::blightsteel, false);
        Assert.assertTrue(r.combat.getAllBlockers().isEmpty(), "no blocks, as the policy declared");
        Assert.assertEquals(r.decides, 1L, "one DECIDE: the seat's own; no samples, no play-outs");
        Assert.assertTrue(r.blockLines.isEmpty(), "no block decision line");
        Assert.assertNotNull(r.bootLine);
        Assert.assertFalse(r.bootLine.contains("\"blocks\""), "the boot line is unchanged: " + r.bootLine);
        Assert.assertEquals(r.departures, 0);
    }

    /** The owner's position: the search chump-blocks Blightsteel Colossus with the animated Forest. */
    @Test
    public void blightsteelAtZeroPoisonIsChumpBlocked() throws Exception {
        final Run r = run("blocks=1", this::blightsteel, false);
        Assert.assertEquals(r.blockLines.size(), 1, "one block decision line: " + r.err);
        final String line = r.blockLines.get(0);
        Assert.assertTrue(line.contains("\"outcome\":\"departed\""), line);
        Assert.assertTrue(line.contains("\"capped\":false"), line);
        Assert.assertEquals(r.combat.getAttackersBlockedBy(r.blocker).size(), 1, "the Forest blocks: " + line);
        Assert.assertSame(r.combat.getAttackersBlockedBy(r.blocker).get(0), r.attacker);
        Assert.assertEquals(r.departures, 1);
        Assert.assertNotNull(r.bootLine);
        Assert.assertTrue(r.bootLine.contains("\"blocks\":true"), r.bootLine);
    }

    /** Not lethal, a valuable blocker: the policy's own chump is searched against no blocks, and the Angel is kept. */
    @Test
    public void aNonLethalChumpWithAValuableBlockerIsNotMade() throws Exception {
        final Run r = run("blocks=1", this::dreadmaw, true);
        Assert.assertEquals(r.blockLines.size(), 1, "one block decision line: " + r.err);
        final String line = r.blockLines.get(0);
        Assert.assertTrue(line.contains("\"outcome\":\"departed\""), "departs from the policy's chump: " + line);
        Assert.assertTrue(r.combat.getAllBlockers().isEmpty(), "the Angel does not block: " + line);
        // and without the search the policy's chump stands (so the departure is the search's)
        final Run off = run("", this::dreadmaw, true);
        Assert.assertEquals(off.combat.getAttackersBlockedBy(off.blocker).size(), 1, "the policy chumps without blocks=1");
    }

    // ------------------------------------------------------------------------------------------------ review S2

    /** Two untapped Grizzly Bears-sized blockers (distinct cards) and an attacking Craw Wurm, AI at 20 life. */
    private Card[] twoBlockers(final Game g, final Player p0, final Player p1) {
        p0.setLife(20, null);
        final Card a = addCard("Grizzly Bears", p0);
        a.setSickness(false);
        final Card b = addCard("Runeclaw Bear", p0);
        b.setSickness(false);
        fillLibrary(p0, 30);
        final Card wurm = addCard("Craw Wurm", p1);
        wurm.setSickness(false);
        fillLibrary(p1, 30);
        return new Card[] {wurm, a};
    }

    private static Card blockerOf(final Combat c) {
        return c.getAllBlockers().isEmpty() ? null : c.getAllBlockers().get(0);
    }

    /**
     * Review S2: two distinct sampled declarations, equally frequent (both "sample4"), the natural choice altered to the
     * first (a test hook): the replay mark names the other by its canonical declaration and the replay plays exactly it;
     * a mark naming no candidate is a forcedMiss and the natural choice stands.
     */
    @Test
    public void equallyFrequentDistinctSampledDeclarationsReplayByTheirCanonicalIdentity() throws Exception {
        splitSamples = true;
        try {
            forge.ai.simulation.LookaheadTestHooks.setOverride(line -> 1); // the first distinct sampled declaration
            final Run first = run("blocks=1", this::twoBlockers, false);
            final Card natural = blockerOf(first.combat);
            Assert.assertNotNull(natural, "the altered natural choice blocks: " + first.err);
            Assert.assertEquals(first.blockLines.size(), 1, first.err);
            Assert.assertTrue(first.blockLines.get(0).contains("\"sample4\""), "both samples count 4: " + first.blockLines);
            // the other declaration: the other bear on the wurm, by its canonical identity in the replayed game itself
            final String otherName = natural.getName().equals("Grizzly Bears") ? "Runeclaw Bear" : "Grizzly Bears";
            final Path marks = Files.createTempFile("block-marks-", ".jsonl");
            final int[] otherId = {-1};
            System.setProperty("forge.interactive.aiReplay", marks.toString());
            final Run replay = run("blocks=1", (g, p0, p1) -> {
                final Card[] ab = twoBlockers(g, p0, p1);
                for (Card c : p0.getCardsIn(ZoneType.Battlefield)) {
                    if (c.getName().equals(otherName)) {
                        otherId[0] = c.getId();
                    }
                }
                try {
                    // written before the seat binds (and reads the marks)
                    Files.write(marks, ("{\"t\":\"ai\",\"decision\":0,\"outcome\":\"departed\",\"played\":\"blocks a"
                            + ab[0].getId() + ">b" + otherId[0] + "\"}\n").getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                return ab;
            }, false);
            Assert.assertEquals(blockerOf(replay.combat).getName(), otherName, "the replay plays the named declaration: "
                    + replay.blockLines);
            Assert.assertEquals(blockerOf(replay.combat).getId(), otherId[0]);
            Assert.assertTrue(replay.blockLines.get(0).contains("\"forced\":true"), replay.blockLines.toString());

            final Path miss = Files.createTempFile("block-marks-", ".jsonl");
            Files.write(miss, "{\"t\":\"ai\",\"decision\":0,\"played\":\"blocks a1>b2\"}\n".getBytes(StandardCharsets.UTF_8));
            System.setProperty("forge.interactive.aiReplay", miss.toString());
            final Run missed = run("blocks=1", this::twoBlockers, false);
            Assert.assertEquals(blockerOf(missed.combat).getName(), natural.getName(), "no such candidate: the natural choice");
            Assert.assertTrue(missed.blockLines.get(0).contains("\"forcedMiss\":true"), missed.blockLines.toString());
        } finally {
            splitSamples = false;
            System.clearProperty("forge.interactive.aiReplay");
            forge.ai.simulation.LookaheadTestHooks.setOverride(null);
        }
    }
}
