package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.InfoStates;
import forge.ai.simulation.SimulationTest;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

/**
 * attack-holdback-1118: the look-ahead over the policy seat's declare-attackers asks (spec key {@code attacks}, off by
 * default), with the worlds keeping a card the seat saw return to the opponent's hand ({@code knownHand}). Report
 * 2026-11-18T14-37-11 (evidence/state.json, turn 22, cursor 5893): AI Player at 3 life attacked with Bloodgift Demon
 * (5/4 flying), its only flier, while the human held Kolaghan, the Storm's Fury (dash {3}{B}{R}, 4/5 flying), publicly
 * returned to hand at the end of the turn before; the human dashed it back and hit for lethal.
 *
 * <p>The tests drive the live seat ({@link RlLiveSeat}) on that board against {@link FakeSearchService}. Its scripted
 * policy: the live seat's attack declaration is the test's ({@code liveAttackers}); in the play-outs our seat attacks
 * with nothing, blocks each attacker with the blocker that can (each blocker on the highest-power attacker it may
 * block) and passes; the human is Forge AI. The leaf is {@link RlBlockSearchTest#leaf} (0.3 per creature of ours plus
 * half our life fraction, minus 0.5; a lost play-out is worst). No rule about attacking or holding back is anywhere in
 * the search: the candidates (the policy's declaration, it less one attacker, none) are played out and valued.
 *
 * <p>Plays real Forge copies on the search's pool: run inside a broker test lease, cwd = forge-gui with res/.
 */
public class RlAttackSearchTest extends SimulationTest {
    static final String SHA = RlBlockSearchTest.SHA;
    static final long SEED = 741461525L;
    static final String DRAGON = "Kolaghan, the Storm's Fury";
    static final String DEMON = "Bloodgift Demon";
    static final String PRIEST = "Skirsdag High Priest";

    /** Power of the attackers the play-out seat may block (its blocks: the highest first). */
    static final Map<String, Integer> POWER = Map.of(DRAGON, 4, "Burning-Tree Emissary", 2, "Master of Cruelties", 1,
            DEMON, 5, PRIEST, 1, "Runeclaw Bear", 2);

    private static Path indexFile;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    private static synchronized Path index() throws Exception {
        if (indexFile == null) {
            final TreeSet<String> names = new TreeSet<>(Arrays.asList("Forest", "Plains", "Mountain", "Island", "Swamp",
                    "Stomping Ground", "Cinder Glade", DEMON, PRIEST, "Burning-Tree Emissary", DRAGON, "Master of Cruelties",
                    "Abrupt Decay", "Runeclaw Bear", "Serra Angel"));
            final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
            int i = 2;
            for (String n : names) {
                NAMES.put(i, n);
                sb.append(n).append('\t').append(i++).append('\n');
            }
            indexFile = Files.createTempFile("attack-search-index-", ".tsv");
            Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return indexFile;
    }

    static String spec(final String server, final String extra) throws Exception {
        return "server=" + server + ",policySha=" + SHA + ",cardIndex=" + index() + ",threads=4"
                + (extra.isEmpty() ? "" : "," + extra);
    }

    static String tokName(final RlWire.Decide d, final int tok) {
        return tok < 0 || tok >= d.L ? null : NAMES.get(d.tokCard[tok]);
    }

    /** The scripted policy (see the class comment). */
    static final class Script implements java.util.function.Function<RlWire.Decide, short[]> {
        final long liveUid;
        final Set<String> liveAttackers;
        final java.util.concurrent.atomic.AtomicInteger liveAttackAsks = new java.util.concurrent.atomic.AtomicInteger();

        Script(final long liveUid, final Set<String> liveAttackers) {
            this.liveUid = liveUid;
            this.liveAttackers = liveAttackers;
        }

        static short[] none(final RlWire.Decide d) {
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

        @Override
        public short[] apply(final RlWire.Decide d) {
            if (d.family == RlSchema.F_ATTACK) {
                final short[] out = none(d);
                if (d.gameUid == liveUid) {
                    liveAttackAsks.incrementAndGet();
                    for (int s = 0; s < d.S; s++) {
                        if (!liveAttackers.contains(tokName(d, d.slotTok[s]))) {
                            continue;
                        }
                        for (int i = 0; i < d.C; i++) {
                            if (d.candSlot[i] == s && d.candKind[i] == RlSchema.K_DEFENDER && d.candTgt[2 * i] < 0) {
                                out[s] = (short) i; // the opposing player
                            }
                        }
                    }
                }
                return out;
            }
            if (d.family == RlSchema.F_BLOCK) {
                final short[] out = none(d);
                for (int s = 0; s < d.S; s++) {
                    int best = -1, bestPow = -1;
                    for (int i = 0; i < d.C; i++) {
                        if (d.candSlot[i] == s && d.candKind[i] == RlSchema.K_ATTACKER) {
                            final int pow = POWER.getOrDefault(tokName(d, d.candTgt[2 * i]), 0);
                            if (pow > bestPow) {
                                bestPow = pow;
                                best = i;
                            }
                        }
                    }
                    if (best >= 0) {
                        out[s] = (short) best;
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

    static final class Run {
        Combat combat;
        String err;
        long decides;
        int departures = -1;
        int liveAsks;
        final List<String> attackLines = new ArrayList<>();
        final List<String> explainLines = new ArrayList<>();
        String bootLine;
        final Set<String> attackers = new TreeSet<>();
        InfoStates is;
        boolean knowsDragon;
        Card demon, priest, dragon;

        JsonObject line() {
            return attackLines.isEmpty() ? null
                    : JsonParser.parseString(attackLines.get(0).substring("[lookahead-decision] ".length())).getAsJsonObject();
        }

        String summary() {
            final JsonObject l = line();
            if (l == null) {
                return "no attack line; attackers " + attackers;
            }
            return "labels " + l.get("labels") + " ev " + l.get("ev") + " outcome " + l.get("outcome") + " chosen "
                    + l.get("chosen") + " searchMs " + l.get("searchMs") + " attackers " + attackers;
        }
    }

    interface Board {
        /** Build p0 (the policy seat, attacking) and p1 (the human); return {demon, priest, dragon}. */
        Card[] build(Game g, Player p0, Player p1);
    }

    private Run run(final String extra, final Board board, final Set<String> liveAttackers, final boolean returnDragon)
            throws Exception {
        final Run r = new Run();
        final PrintStream was = System.err;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            svc.leafValue = RlBlockSearchTest::leaf;
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), extra), "attack-search-test");
            Assert.assertNotNull(live, "the seat binds the fake service");
            final LobbyPlayerAi ai = new LobbyPlayerAi("Seat1", null);
            ai.setAiProfile("Default");
            final List<RegisteredPlayer> players = Arrays.asList(
                    new RegisteredPlayer(new Deck()).setPlayer(live.lobby("Seat0", 0, "Default")),
                    new RegisteredPlayer(new Deck()).setPlayer(ai));
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(players, rules, new Match(rules, players, "attack-search"));
            final Player p0 = game.getRegisteredPlayers().get(0);
            final Player p1 = game.getRegisteredPlayers().get(1);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p0, 22);
            final Card[] cs = board.build(game, p0, p1);
            r.demon = cs[0];
            r.priest = cs[1];
            r.dragon = cs[2];
            game.getAction().checkStateEffects(true);
            final long liveUid = RlSearch.splitmix(SEED ^ 0x6c697665L);
            final Script script = new Script(liveUid, liveAttackers);
            svc.script = script;
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, p0, false, 22);
            final Combat combat = new Combat(p0);
            game.getPhaseHandler().setCombat(combat);
            combat.initConstraints();
            r.combat = combat;
            live.bind(game, 0, SEED, null, AiFixes.Mode.OFF);
            if (returnDragon) {
                // the end of the human's turn before: the dashed Kolaghan returns from the battlefield to its hand
                game.getAction().moveToHand(r.dragon, null);
                r.dragon = game.findById(r.dragon.getId());
                Assert.assertTrue(r.dragon.isInZone(ZoneType.Hand), "Kolaghan is in the human's hand");
            }
            r.is = InfoStates.find(game);
            r.knowsDragon = r.is != null && r.is.knowsInHand(p0, r.dragon);
            final long d0 = svc.decides.get();
            try {
                p0.getController().declareAttackers(p0, combat);
            } finally {
                live.finish();
            }
            r.decides = svc.decides.get() - d0;
            r.liveAsks = script.liveAttackAsks.get();
            for (Card c : combat.getAttackers()) {
                r.attackers.add(c.getName());
            }
            System.err.flush();
            r.err = buf.toString(StandardCharsets.UTF_8);
            for (String l : r.err.split("\n")) {
                if (l.startsWith("[lookahead-decision] ") && l.contains("\"kind\":\"attacks\"")) {
                    r.attackLines.add(l);
                }
                if (l.startsWith("[lookahead-explain] ") && l.contains("\"kind\":\"attacks\"")) {
                    r.explainLines.add(l);
                }
                if (l.startsWith("[policy-seat] ") && l.contains("\"bound\":true")) {
                    r.bootLine = l;
                }
                if (l.startsWith("[policy-seat] ") && l.contains("\"end\":true")) {
                    final JsonObject o = JsonParser.parseString(l.substring("[policy-seat] ".length())).getAsJsonObject();
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
     * The reported board at the AI's declare-attackers ask (turn 22; evidence/state.json cursor 5893). AI (p0) at 3 life:
     * Islands x4, Swamps x2 (tapped), Skirsdag High Priest 1/2, Bloodgift Demon 5/4 flying; 2 cards in hand. The human
     * (p1) at {@code humanLife} (13 in the report): Stomping Ground, Cinder Glade, Swamp, Mountain, Swamp, Swamp (all
     * tapped: they untap on its turn), Burning-Tree Emissary 2/2; Master of Cruelties and Abrupt Decay in hand (never
     * seen) and Kolaghan, on its battlefield here, returned to its hand once the seat is bound (as at the end of turn 20).
     */
    private Board reported(final int humanLife) {
        return (g, p0, p1) -> {
            p0.setLife(3, null);
            p1.setLife(humanLife, null);
            for (String n : new String[] {"Island", "Island", "Island", "Island"}) {
                addCard(n, p0);
            }
            for (String n : new String[] {"Swamp", "Swamp"}) {
                addCard(n, p0).setTapped(true);
            }
            final Card priest = addCard(PRIEST, p0);
            priest.setSickness(false);
            final Card demon = addCard(DEMON, p0);
            demon.setSickness(false);
            for (String n : new String[] {"Island", "Swamp"}) {
                addCardToZone(n, p0, ZoneType.Hand);
            }
            fillLibrary(p0, 22);
            for (String n : new String[] {"Stomping Ground", "Cinder Glade", "Swamp", "Mountain", "Swamp", "Swamp"}) {
                addCard(n, p1).setTapped(true);
            }
            addCard("Burning-Tree Emissary", p1).setSickness(false);
            for (String n : new String[] {"Master of Cruelties", "Abrupt Decay"}) {
                addCardToZone(n, p1, ZoneType.Hand);
            }
            final Card dragon = addCard(DRAGON, p1);
            fillLibrary(p1, 21);
            return new Card[] {demon, priest, dragon};
        };
    }

    static final String KEYS_ON = "infoState=on,knownHand=on,attacks=1";

    // ------------------------------------------------------------------------------------------------ tests

    /** Keys absent: the policy's declaration (the Demon attacks) stands, one DECIDE, no attack line, as today. */
    @Test
    public void keysAbsentKeepThePolicysDeclaration() throws Exception {
        final Run r = run("infoState=on", reported(13), Set.of(DEMON), true);
        Assert.assertEquals(r.attackers, Set.of(DEMON), "the Demon attacks, as the policy declared");
        Assert.assertEquals(r.decides, 1L, "one DECIDE: the seat's own; no play-outs");
        Assert.assertTrue(r.attackLines.isEmpty(), "no attack decision line");
        Assert.assertNotNull(r.bootLine);
        Assert.assertFalse(r.bootLine.contains("\"attacks\""), "the boot line is unchanged: " + r.bootLine);
        Assert.assertFalse(r.knowsDragon, "InfoState does not track hands without knownHand");
        Assert.assertFalse(r.is.tracksHands());
        Assert.assertEquals(r.departures, 0);
    }

    /**
     * The reported board, keys on: InfoState(AI) knows Kolaghan in the human's hand, the candidates are the policy's
     * declaration (the Demon attacks) and the Demon held back (= no attacks), and the play-outs decide. Every candidate's
     * value is printed ({@code [attack-search-report]}).
     *
     * <p>What the play-outs find (recorded 2026-11-18, 8 worlds): no world loses to Kolaghan. Forge AI, playing the human,
     * keeps Kolaghan in hand on turn 23 in every world: it casts Abrupt Decay on Skirsdag High Priest (leaving 4 lands,
     * one short of dash) and attacks with Burning-Tree Emissary. With the Demon tapped the Emissary connects (AI 3 -> 1);
     * with it home the human does not attack. The search holds the Demon back on that difference (the policy's attack
     * 0.4344, held back 0.5687; 7 of 8 worlds +0.15), not on the dash crack-back, which these play-outs never find.
     */
    @Test
    public void theReportedBoardHoldsTheFlierBack() throws Exception {
        final Run r = run(KEYS_ON, reported(13), Set.of(DEMON), true);
        System.out.println("[attack-search-report] keys on, human at 13: " + r.summary());
        for (String x : r.explainLines) {
            System.out.println("[attack-search-report] " + x);
        }
        Assert.assertTrue(r.knowsDragon, "InfoState(AI) saw Kolaghan return to the human's hand");
        Assert.assertNotNull(r.bootLine);
        Assert.assertTrue(r.bootLine.contains("\"attacks\":true"), r.bootLine);
        Assert.assertEquals(r.attackLines.size(), 1, "one attack decision line: " + r.err);
        final JsonObject l = r.line();
        final JsonArray labels = l.getAsJsonArray("labels");
        Assert.assertEquals(labels.size(), 2, "the policy's declaration and the Demon held back: " + labels);
        Assert.assertEquals(labels.get(0).getAsString(), "policy");
        Assert.assertEquals(labels.get(1).getAsString(), "hold" + r.demon.getId());
        Assert.assertEquals(l.get("phase").getAsString(), "COMBAT_DECLARE_ATTACKERS");
        Assert.assertEquals(l.get("capped").getAsBoolean(), false, r.summary());
        Assert.assertEquals(l.get("outcome").getAsString(), "departed", "the search holds the Demon back: " + r.summary());
        Assert.assertTrue(r.attackers.isEmpty(), "nothing attacks: " + r.summary());
        Assert.assertEquals(r.departures, 1);
    }

    /**
     * attacks on, knownHand off: the same candidates and play-outs; InfoState does not track the hand (the live seat's
     * tracker, RlKnowledge, still remembers Kolaghan for the uniform belief worlds, as the probe found). Printed for the
     * report: whether knownHand changes the values on this board.
     */
    @Test
    public void theReportedBoardWithoutKnownHand() throws Exception {
        final Run r = run("infoState=on,attacks=1", reported(13), Set.of(DEMON), true);
        System.out.println("[attack-search-report] attacks on, knownHand off, human at 13: " + r.summary());
        Assert.assertFalse(r.knowsDragon, "InfoState does not track hands without knownHand");
        Assert.assertEquals(r.attackLines.size(), 1, "one attack decision line: " + r.err);
        Assert.assertEquals(r.line().getAsJsonArray("labels").size(), 2, r.summary());
    }

    /** The same board with the human at 5: the Demon's attack wins on the spot, and it is not held back. */
    @Test
    public void aWinningAttackIsNotHeldBack() throws Exception {
        final Run r = run(KEYS_ON, reported(5), Set.of(DEMON), true);
        System.out.println("[attack-search-report] keys on, human at 5: " + r.summary());
        Assert.assertEquals(r.attackLines.size(), 1, "one attack decision line: " + r.err);
        Assert.assertNotEquals(r.line().get("outcome").getAsString(), "departed", r.summary());
        Assert.assertEquals(r.attackers, Set.of(DEMON), "the Demon attacks for the win: " + r.summary());
        Assert.assertEquals(r.departures, 0);
    }

    /**
     * The policy attacks with both creatures: the candidates are its declaration, each attacker held back (the Demon, the
     * higher power, first) and no attacks; whatever is played attacks with no creature the policy did not choose.
     */
    @Test
    public void theCandidatesOnlyEverRemoveAttackers() throws Exception {
        final Run r = run(KEYS_ON, reported(13), Set.of(DEMON, PRIEST), true);
        System.out.println("[attack-search-report] keys on, policy attacks with both: " + r.summary());
        Assert.assertEquals(r.attackLines.size(), 1, "one attack decision line: " + r.err);
        final JsonArray labels = r.line().getAsJsonArray("labels");
        Assert.assertEquals(labels.toString(), "[\"policy\",\"hold" + r.demon.getId() + "\",\"hold" + r.priest.getId()
                + "\",\"none\"]");
        Assert.assertTrue(Set.of(DEMON, PRIEST).containsAll(r.attackers), "only the policy's attackers: " + r.attackers);
    }

    /** The policy declares no attack: no candidate removes anything, nothing is searched, no attacker is added. */
    @Test
    public void noAttackDeclaredIsNotSearched() throws Exception {
        final Run r = run(KEYS_ON, reported(13), Set.of(), true);
        Assert.assertTrue(r.attackers.isEmpty());
        Assert.assertEquals(r.attackLines.size(), 1, r.err);
        Assert.assertEquals(r.line().get("outcome").getAsString(), "uncontested", r.summary());
        Assert.assertEquals(r.decides, 1L, "one DECIDE: the seat's own; no play-outs");
    }

    /** The live spec's keys: absent = off (the JSON unchanged), explicit off = absent, on/1 arms, others refused. */
    @Test
    public void specKeysAreOffUnlessSetAndChecked() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "infoState=on"));
        Assert.assertEquals(off.search.attacks, 0);
        Assert.assertFalse(off.search.knownHand);
        Assert.assertFalse(off.search.toJson().has("attacks"));
        Assert.assertFalse(off.search.toJson().has("knownHand"));
        final RlLiveSeat.Spec explicitOff = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "infoState=on,attacks=0,knownHand=off"));
        Assert.assertEquals(explicitOff.search.toJson().toString(), off.search.toJson().toString());
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", KEYS_ON));
        Assert.assertEquals(on.search.attacks, 1);
        Assert.assertTrue(on.search.knownHand);
        Assert.assertEquals(on.search.toJson().get("attacks").getAsInt(), 1);
        Assert.assertTrue(on.search.toJson().get("knownHand").getAsBoolean());
        for (String bad : new String[] {"attacks=2", "attacks=yes", "attacks=on", "knownHand=yes", "knownHand=1",
                "knownHand=On"}) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "infoState=on," + bad)));
        }
        Assert.expectThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "knownHand=on")));
        Assert.expectThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "attacks=1,playout=forge")));
    }
}
