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
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.LookaheadSearch;
import forge.ai.simulation.SimulationTest;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * line-own-target-1119: the policy seat's {@code ownHarm} key (off by default). Owner report 2026-11-19T01-14-57 (game
 * 38a8a0e4, turn 10): with the proven line broken before its Snuff Out (lineOwnTarget), the policy search departed to
 * Snuff Out anyway (decision line 55, 0.99955 against 0.99952) and Forge AI aimed it at the only nonblack creature, our
 * own indestructible Ulamog (the opponent's card, under our control): 4 life for nothing. With the key on, a candidate
 * whose harm can land only on our own permanents, or whose destroy can only hit indestructible ones, is dropped before
 * the play-outs, the policy's own choice too.
 *
 * <p>The tests drive the live seat ({@link RlLiveSeat}) at its first main phase against {@link FakeSearchService}: the
 * scripted policy answers the live priority ask with the named card (pass when none) and passes in every play-out; the
 * service's priors are uniform and every leaf is worth 0, so the search keeps its default unless the default is gone.
 * Plays real Forge copies on the search's pool: run inside a broker test lease, cwd = forge-gui with res/.
 */
public class OwnHarmSearchTest extends SimulationTest {
    static final String SHA = RlBlockSearchTest.SHA;
    static final long SEED = 1119L;
    static final String SNUFF = "Snuff Out";
    static final String ULAMOG = "Ulamog, the Ceaseless Hunger";

    private static Path indexFile;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    private static synchronized Path index() throws Exception {
        if (indexFile == null) {
            final TreeSet<String> names = new TreeSet<>(Arrays.asList("Swamp", "Mountain", "Plains", "Island", SNUFF, ULAMOG,
                    "Abrade", "Sol Ring", "Grizzly Bears", "Ephemerate", "Runeclaw Bear", "Disfigure", "Blood Artist"));
            final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
            int i = 2;
            for (String n : names) {
                NAMES.put(i, n);
                sb.append(n).append('\t').append(i++).append('\n');
            }
            indexFile = Files.createTempFile("own-harm-index-", ".tsv");
            Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return indexFile;
    }

    static String spec(final String server, final String extra) throws Exception {
        return "server=" + server + ",policySha=" + SHA + ",cardIndex=" + index() + ",worlds=2,breadth=8,horizon=1,threads=2"
                + (extra.isEmpty() ? "" : "," + extra);
    }

    /** The scripted policy: the live priority ask answers the first menu entry of {@code pick} (null: pass). */
    static final class Script implements java.util.function.Function<RlWire.Decide, short[]> {
        final long liveUid;
        final String pick;

        Script(final long liveUid, final String pick) {
            this.liveUid = liveUid;
            this.pick = pick;
        }

        @Override
        public short[] apply(final RlWire.Decide d) {
            if (d.family != RlSchema.F_PRIORITY) {
                return null;
            }
            int pass = -1, want = -1;
            for (int i = 0; i < d.C; i++) {
                if (d.candKind[i] == RlSchema.K_PASS && pass < 0) {
                    pass = i;
                }
                if (want < 0 && pick != null && d.gameUid == liveUid
                        && (d.candKind[i] == RlSchema.K_CAST || d.candKind[i] == RlSchema.K_ACTIVATE)
                        && pick.equals(NAMES.get(d.candCard[i]))) {
                    want = i;
                }
            }
            return new short[] {(short) (want >= 0 ? want : pass)};
        }
    }

    static final class Run {
        String answer;
        final List<JsonObject> decisions = new ArrayList<>();
        final List<String> ownHarmLines = new ArrayList<>();
        String err;
        /** The probe's reading of the live game before and after the decision (null: no probe). */
        String before, after;

        JsonObject line() {
            return decisions.isEmpty() ? null : decisions.get(0);
        }

        JsonObject ownHarm() {
            final JsonObject l = line();
            return l == null || !l.has("ownHarm") ? null : l.getAsJsonObject("ownHarm");
        }

        String summary() {
            return "answer " + answer + "; decision " + line() + "; own-harm lines " + ownHarmLines;
        }
    }

    interface Board {
        /** Build p0 (the policy seat, in its first main phase) and p1 (Forge AI). */
        void build(Game g, Player p0, Player p1);
    }

    private Run run(final String extra, final Board board, final String pick) throws Exception {
        return run(extra, board, pick, null);
    }

    private Run run(final String extra, final Board board, final String pick,
            final java.util.function.Function<Game, String> probe) throws Exception {
        final Run r = new Run();
        final PrintStream was = System.err;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), extra), "own-harm-test");
            Assert.assertNotNull(live, "the seat binds the fake service");
            final LobbyPlayerAi ai = new LobbyPlayerAi("Seat1", null);
            ai.setAiProfile("Default");
            final List<RegisteredPlayer> players = Arrays.asList(
                    new RegisteredPlayer(new Deck()).setPlayer(live.lobby("Seat0", 0, "Default")),
                    new RegisteredPlayer(new Deck()).setPlayer(ai));
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(players, rules, new Match(rules, players, "own-harm"));
            final Player p0 = game.getRegisteredPlayers().get(0);
            final Player p1 = game.getRegisteredPlayers().get(1);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p0, 10);
            board.build(game, p0, p1);
            fillLibrary(p0, 20);
            fillLibrary(p1, 20);
            game.getAction().checkStateEffects(true);
            svc.script = new Script(RlSearch.splitmix(SEED ^ 0x6c697665L), pick);
            live.bind(game, 0, SEED, null, AiFixes.Mode.OFF);
            r.before = probe == null ? null : probe.apply(game);
            final List<SpellAbility> a;
            try {
                a = p0.getController().chooseSpellAbilityToPlay();
            } finally {
                live.finish();
            }
            r.after = probe == null ? null : probe.apply(game);
            r.answer = a == null || a.isEmpty() || a.get(0) == null ? "pass"
                    : a.get(0).getHostCard() == null ? "?" : a.get(0).getHostCard().getName();
            System.err.flush();
            r.err = buf.toString(StandardCharsets.UTF_8);
            for (String l : r.err.split("\n")) {
                if (l.startsWith("[lookahead-decision] ") && l.contains("\"seat\":\"policy\"") && !l.contains("\"kind\"")) {
                    r.decisions.add(JsonParser.parseString(l.substring("[lookahead-decision] ".length())).getAsJsonObject());
                }
                if (l.startsWith("[own-harm] ")) {
                    r.ownHarmLines.add(l);
                }
            }
        } finally {
            System.setErr(was);
            was.print(buf.toString(StandardCharsets.UTF_8));
        }
        System.out.println("[own-harm-test] " + extra + " pick " + pick + ": " + r.summary());
        return r;
    }

    private static void control(final Card c, final Player to) {
        final Game g = to.getGame();
        c.addTempController(to, g.getNextTimestamp());
        g.getAction().controllerChangeZoneCorrection(c);
        g.getAction().checkStateEffects(true);
        Assert.assertEquals(c.getController(), to);
        Assert.assertNotEquals(c.getOwner(), to);
    }

    // ------------------------------------------------------------------ boards

    /**
     * Game 38a8a0e4 at turn 10, cut down: the opponent's Ulamog, the Ceaseless Hunger (indestructible) under our control,
     * the opponent with no creature; Snuff Out in our hand, castable both ways (four lands, two Swamps, 20 life).
     */
    private Board snuff() {
        return snuff(true);
    }

    /** As {@link #snuff()}; {@code island}: an Island in hand too (a land play among the candidates). */
    private Board snuff(final boolean island) {
        return (g, p0, p1) -> {
            for (String n : new String[] {"Swamp", "Swamp", "Mountain", "Mountain"}) {
                addCard(n, p0);
            }
            addCardToZone(SNUFF, p0, ZoneType.Hand);
            if (island) {
                addCardToZone("Island", p0, ZoneType.Hand);
            }
            final Card ulamog = addCard(ULAMOG, p1);
            control(ulamog, p0);
            ulamog.setSickness(false);
            for (String n : new String[] {"Plains", "Plains", "Plains"}) {
                addCard(n, p1);
            }
        };
    }

    /** Abrade (3 damage to target creature, or destroy target artifact) with our Grizzly Bears the only creature. */
    private Board abrade(final boolean theirArtifact) {
        return (g, p0, p1) -> {
            for (String n : new String[] {"Mountain", "Mountain"}) {
                addCard(n, p0);
            }
            addCard("Grizzly Bears", p0).setSickness(false);
            addCardToZone("Abrade", p0, ZoneType.Hand);
            if (theirArtifact) {
                addCard("Sol Ring", p1);
            }
            addCard("Plains", p1);
        };
    }

    /** Ephemerate (exile target creature you control, return it): our Grizzly Bears, by design. */
    private Board ephemerate() {
        return (g, p0, p1) -> {
            addCard("Plains", p0);
            addCard("Grizzly Bears", p0).setSickness(false);
            addCardToZone("Ephemerate", p0, ZoneType.Hand);
            addCard("Plains", p1);
        };
    }

    // ------------------------------------------------------------------ the policy's own choice

    /** Key absent: the policy's Snuff Out stands, as on the live jar; the rule never runs. */
    @Test
    public void keyOffThePolicysSnuffOutStands() throws Exception {
        final Run r = run("", snuff(), SNUFF);
        Assert.assertEquals(r.answer, SNUFF, r.summary());
        Assert.assertTrue(r.ownHarmLines.isEmpty(), r.summary());
        Assert.assertTrue(r.line() == null || !r.line().has("ownHarm"), r.summary());
    }

    /**
     * Key on: the policy's own Snuff Out (nothing it can destroy: Ulamog is indestructible, and ours) is dropped before
     * the play-outs; its best remaining entry (pass: uniform priors, the earliest entry) is the default the search keeps.
     */
    @Test
    public void keyOnThePolicysOwnSnuffOutIsRefused() throws Exception {
        final Run r = run("ownHarm=on", snuff(), SNUFF);
        Assert.assertNotEquals(r.answer, SNUFF, r.summary());
        Assert.assertEquals(r.answer, "pass", r.summary());
        final JsonObject oh = r.ownHarm();
        Assert.assertNotNull(oh, "the decision line names the refusal: " + r.summary());
        Assert.assertEquals(oh.get("mode").getAsString(), "on");
        final JsonArray refused = oh.getAsJsonArray("refused");
        Assert.assertTrue(refused.size() >= 1, r.summary());
        final JsonObject first = refused.get(0).getAsJsonObject();
        Assert.assertEquals(first.get("card").getAsString(), SNUFF);
        Assert.assertTrue(first.has("default") && first.get("default").getAsBoolean(), "the policy's own choice: " + oh);
        Assert.assertTrue(first.get("why").getAsString().startsWith("destroy has no effect"), oh.toString());
        for (int i = 0; i < refused.size(); i++) {
            Assert.assertEquals(refused.get(i).getAsJsonObject().get("card").getAsString(), SNUFF, oh.toString());
        }
        Assert.assertEquals(oh.get("fallback").getAsInt(), 0, "pass stands in: " + oh);
        Assert.assertFalse(r.ownHarmLines.isEmpty(), "logged");
    }

    /** SHADOW: counted and logged on the decision line; the seat decides as with the key off. */
    @Test
    public void keyShadowLogsAndDecidesAsOff() throws Exception {
        final Run r = run("ownHarm=shadow", snuff(), SNUFF);
        Assert.assertEquals(r.answer, SNUFF, r.summary());
        final JsonObject oh = r.ownHarm();
        Assert.assertNotNull(oh, r.summary());
        Assert.assertEquals(oh.get("mode").getAsString(), "shadow");
        Assert.assertFalse(oh.has("fallback"), oh.toString());
    }

    // ------------------------------------------------------------------ departures

    /** The policy passes: key on, Snuff Out is not among the searched candidates (key off, it is). */
    @Test
    public void keyOnASnuffOutDepartureIsNeverPlayedOut() throws Exception {
        final Run off = run("", snuff(), null);
        final Run on = run("ownHarm=on", snuff(), null);
        Assert.assertEquals(off.answer, "pass", off.summary());
        Assert.assertEquals(on.answer, "pass", on.summary());
        Assert.assertNotNull(off.line(), off.summary());
        Assert.assertNotNull(on.line(), on.summary());
        final JsonObject oh = on.ownHarm();
        Assert.assertNotNull(oh, on.summary());
        final JsonArray refused = oh.getAsJsonArray("refused");
        for (int i = 0; i < refused.size(); i++) {
            final JsonObject x = refused.get(i).getAsJsonObject();
            Assert.assertEquals(x.get("card").getAsString(), SNUFF, oh.toString());
            Assert.assertFalse(x.has("default"), "the policy passed: " + oh);
        }
        Assert.assertFalse(oh.has("fallback"), "the policy's own choice stands: " + oh);
        Assert.assertEquals(on.line().get("candidates").getAsInt(), off.line().get("candidates").getAsInt() - refused.size(),
                "only the refused entries leave: off " + off.line() + " on " + on.line());
    }

    /**
     * Decision-line numbering: replay marks and "Continue from here" rebuilds key on the policy seat's decision-line index.
     * The policy passes with only Snuff Out besides: off, the decision is searched (decision line 0); on, Snuff Out leaves
     * and pass is alone, yet decision line 0 is still written (outcome uncontested, with its ownHarm field), so every later
     * line keeps its key-off index.
     */
    @Test
    public void keyOnADecisionLeftUncontestedKeepsItsDecisionLine() throws Exception {
        final Run off = run("", snuff(false), null);
        final Run on = run("ownHarm=on", snuff(false), null);
        Assert.assertEquals(off.answer, "pass", off.summary());
        Assert.assertEquals(on.answer, "pass", on.summary());
        Assert.assertNotNull(off.line(), off.summary());
        Assert.assertNotNull(on.line(), on.summary());
        Assert.assertEquals(off.line().get("decision").getAsInt(), 0);
        Assert.assertEquals(on.line().get("decision").getAsInt(), 0);
        Assert.assertNotEquals(off.line().get("outcome").getAsString(), "uncontested", off.summary());
        Assert.assertEquals(on.line().get("outcome").getAsString(), "uncontested", on.summary());
        Assert.assertNotNull(on.ownHarm(), on.summary());
    }

    // ------------------------------------------------------------------ what stays

    /**
     * Abrade: its damage mode has only our own Grizzly Bears to hit, but its destroy-artifact mode has the opponent's Sol
     * Ring: a mode is live, so the policy's Abrade stays; with no artifact on the battlefield every mode it could take is
     * refused and it goes (by controller: our own Bears).
     */
    @Test
    public void keyOnAModalSpellWithALiveModeStays() throws Exception {
        final Run live = run("ownHarm=on", abrade(true), "Abrade");
        Assert.assertEquals(live.answer, "Abrade", live.summary());
        Assert.assertNull(live.ownHarm(), live.summary());
        Assert.assertTrue(live.ownHarmLines.isEmpty(), live.summary());
        final Run dead = run("ownHarm=on", abrade(false), "Abrade");
        Assert.assertNotEquals(dead.answer, "Abrade", dead.summary());
        // pass alone is left: nothing to search, but the decision line is still written (see the numbering test)
        Assert.assertNotNull(dead.line(), dead.summary());
        Assert.assertEquals(dead.line().get("outcome").getAsString(), "uncontested", dead.summary());
        Assert.assertEquals(dead.ownHarmLines.size(), 1, dead.summary());
        final JsonObject oh = dead.ownHarm();
        Assert.assertNotNull(oh, dead.summary());
        Assert.assertEquals(oh.get("fallback").getAsInt(), 0, oh.toString());
        Assert.assertTrue(oh.getAsJsonArray("refused").get(0).getAsJsonObject().get("why").getAsString()
                .contains("only our own to target: [Grizzly Bears"), oh.toString());
    }

    /** An ability that can only target our own permanents (a blink) is never refused. */
    @Test
    public void keyOnABlinkOfOurOwnCreatureStays() throws Exception {
        final Run r = run("ownHarm=on", ephemerate(), "Ephemerate");
        Assert.assertEquals(r.answer, "Ephemerate", r.summary());
        Assert.assertNull(r.ownHarm(), r.summary());
    }

    /**
     * Review 1 of #251, S1 7: the guard reads the live ability without changing it. The reviewer's board (Abrade in hand,
     * the opponent's Sol Ring, no creature: one mode offered, so a CharmOrder renumbering would show) with the policy
     * passing, so Abrade is a departure the guard inspects (shadow, on) and never the answer. The key-off seat itself
     * changes the live Abrade as it builds its menu (it sets the activator, and Forge's makePossibleOptions renumbers the
     * offered mode's CharmOrder): shadow and on leave the live Abrade (its activator, targets and SVars, its modes'
     * CharmOrder among them) exactly as off does. The guard's own calls, with nothing else between, are checked
     * before/after in LineOwnTargetReviewTest and OwnHarmK8Test.
     */
    @Test
    public void theGuardLeavesTheLiveAbilityUnchangedOffShadowAndOn() throws Exception {
        final Board b = (g, p0, p1) -> {
            for (String n : new String[] {"Mountain", "Mountain"}) {
                addCard(n, p0);
            }
            addCardToZone("Abrade", p0, ZoneType.Hand);
            addCard("Sol Ring", p1);
            addCard("Plains", p1);
        };
        final java.util.function.Function<Game, String> probe = g -> {
            final StringBuilder sb = new StringBuilder();
            for (Card c : g.getPlayers().get(0).getCardsIn(ZoneType.Hand)) {
                if (c.getName().equals("Abrade")) {
                    for (SpellAbility sa : c.getSpellAbilities()) {
                        sb.append(forge.ai.simulation.LineOwnTargetReviewTest.state(sa));
                    }
                }
            }
            return sb.toString();
        };
        String offAfter = null;
        for (String mode : new String[] {"", "ownHarm=shadow", "ownHarm=on"}) {
            final Run r = run(mode, b, null, probe);
            Assert.assertEquals(r.answer, "pass", r.summary());
            Assert.assertFalse(r.before.isEmpty(), "Abrade in hand");
            Assert.assertTrue(r.before.contains("CharmOrder=1") && r.before.contains("CharmOrder=2"), r.before);
            Assert.assertTrue(r.ownHarmLines.isEmpty(), "destroy Sol Ring is live: nothing refused: " + r.summary());
            System.out.println("[own-harm-test] '" + mode + "' live Abrade before:\n" + r.before + "after:\n" + r.after);
            offAfter = offAfter == null ? r.after : offAfter;
            Assert.assertEquals(r.after, offAfter, "'" + mode + "': the live Abrade after the decision, as off");
        }
    }

    // ------------------------------------------------------------------ the rule itself (no seat)

    private static SpellAbility spell(final Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                sa.setActivatingPlayer(c.getController());
                return sa;
            }
        }
        throw new AssertionError("no spell on " + c);
    }

    /**
     * A shrink with only our own creatures to hit is refused, unless one of ours pays off its death (Blood Artist); the
     * opponent's creature among the legal targets makes it a choice worth having.
     */
    @Test
    public void theRuleKeepsADeathPayoffAndAnOpposingTarget() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(1);
        final Player opp = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        addCard("Swamp", me);
        addCard("Grizzly Bears", me);
        final Card disfigure = addCardToZone("Disfigure", me, ZoneType.Hand);
        g.getAction().checkStateEffects(true);
        final String alone = LookaheadSearch.ownHarmWhy(me, spell(disfigure));
        Assert.assertNotNull(alone, "only our own Bears to shrink");
        Assert.assertTrue(alone.startsWith("shrink with only our own to target"), alone);
        addCard("Blood Artist", me);
        g.getAction().checkStateEffects(true);
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me, spell(disfigure)), "Blood Artist pays off a death of ours");
        final Game g2 = initAndCreateGame();
        final Player me2 = g2.getPlayers().get(1);
        g2.getPhaseHandler().devModeSet(PhaseType.MAIN1, me2);
        addCard("Swamp", me2);
        addCard("Grizzly Bears", me2);
        addCard("Runeclaw Bear", g2.getPlayers().get(0));
        final Card d2 = addCardToZone("Disfigure", me2, ZoneType.Hand);
        g2.getAction().checkStateEffects(true);
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me2, spell(d2)), "the opponent's Runeclaw Bear is a legal target");
        Assert.assertNull(LookaheadSearch.ownHarmWhy(me2, null), "pass is never refused");
        Assert.assertNotNull(opp);
    }
}
