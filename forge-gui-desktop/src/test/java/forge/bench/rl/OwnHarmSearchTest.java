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
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
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
 * whose harm can land only on our own permanents, or whose destroy can only hit indestructible ones, is refused. Review
 * 3: a refusal demotes. The refused candidate is played out as usual and is played only if, against the best candidate
 * not refused, it passes the departZ test and is better by at least {@link LookaheadSearch.Config#OWN_HARM_FLOOR}.
 *
 * <p>The tests drive the live seat ({@link RlLiveSeat}) at its first main phase against {@link FakeSearchService}: the
 * scripted policy answers the live priority ask with the named card (pass when none) and passes in every play-out; the
 * service's priors are uniform and every leaf is worth 0, so the search keeps its default unless values differ. The
 * decision-level tests stub the play-outs' values ({@link LookaheadSearch#testValues}, {@link #gains}). Plays real Forge
 * copies on the search's pool: run inside a broker test lease, cwd = forge-gui with res/.
 */
public class OwnHarmSearchTest extends SimulationTest {
    static final String SHA = RlBlockSearchTest.SHA;
    static final long SEED = 1119L;
    static final String SNUFF = "Snuff Out";
    static final String ULAMOG = "Ulamog, the Ceaseless Hunger";
    static final String SWORDS = "Swords to Plowshares";
    static final String GUTTERSNIPE = "Guttersnipe";
    static final String PIA = "Pia Nalaar, Consul of Revival";
    static final String GARRUK = "Garruk Relentless";

    private static Path indexFile;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    private static synchronized Path index() throws Exception {
        if (indexFile == null) {
            final TreeSet<String> names = new TreeSet<>(Arrays.asList("Swamp", "Mountain", "Plains", "Island", SNUFF, ULAMOG,
                    "Abrade", "Sol Ring", "Grizzly Bears", "Ephemerate", "Runeclaw Bear", "Disfigure", "Blood Artist", SWORDS,
                    GUTTERSNIPE, "Forest", "Flame Slash", "Light Up the Stage", PIA, "Shirei, Shizo's Caretaker",
                    "Out of Time", "Tear Asunder", "Soulfire Grand Master", "Darksteel Myr", "Adarkar Valkyrie", "Doom Blade",
                    "Banishing Light", GARRUK, "Wandertale Mentor"));
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
     * Key on: the policy's own Snuff Out (nothing it can destroy: Ulamog is indestructible, and ours) is refused and
     * played out with the rest. Every leaf is worth 0, so it ties with the others and is demoted: the best entry not
     * refused (pass: equal values, the earliest entry) is the decision, and the decision line carries the demotion.
     */
    @Test
    public void keyOnThePolicysOwnSnuffOutIsDemotedInATie() throws Exception {
        final Run r = run("ownHarm=on", snuff(), SNUFF);
        Assert.assertNotEquals(r.answer, SNUFF, r.summary());
        Assert.assertEquals(r.answer, "pass", r.summary());
        final JsonObject oh = r.ownHarm();
        Assert.assertNotNull(oh, "the decision line names the demotion: " + r.summary());
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
        final JsonObject dm = oh.getAsJsonObject("demotion");
        Assert.assertEquals(dm.get("natural").getAsInt(), 0, "the search kept the policy's own Snuff Out: " + dm);
        Assert.assertTrue(dm.get("changed").getAsBoolean() && dm.get("applied").getAsBoolean(), dm.toString());
        Assert.assertEquals(dm.get("decisionLabel").getAsString(), "pass", dm.toString());
        final JsonObject t = dm.getAsJsonObject("test");
        Assert.assertTrue(t.get("refused").getAsString().startsWith(SNUFF), t.toString());
        Assert.assertEquals(t.get("against").getAsString(), "pass", t.toString());
        Assert.assertFalse(t.get("confident").getAsBoolean() || t.get("better").getAsBoolean(), "a tie: " + t);
        Assert.assertEquals(t.get("refusedEv").getAsDouble(), t.get("againstEv").getAsDouble(), 1e-12, t.toString());
        Assert.assertFalse(oh.has("fallback"), "a verdict, no stand-in: " + oh);
        Assert.assertEquals(r.ownHarmLines.size(), 1, "logged once: " + r.summary());
    }

    /**
     * SHADOW: the refusal and the demotion it would make are logged ([own-harm], applied false); the seat decides as with
     * the key off, so the decision line has no ownHarm field (it fires only when the demotion changed the decision).
     */
    @Test
    public void keyShadowLogsAndDecidesAsOff() throws Exception {
        final Run r = run("ownHarm=shadow", snuff(), SNUFF);
        Assert.assertEquals(r.answer, SNUFF, r.summary());
        Assert.assertNull(r.ownHarm(), r.summary());
        Assert.assertEquals(r.ownHarmLines.size(), 1, r.summary());
        final JsonObject oh = note(r.ownHarmLines.get(0));
        Assert.assertEquals(oh.get("mode").getAsString(), "shadow");
        Assert.assertFalse(oh.has("fallback"), oh.toString());
        final JsonObject dm = oh.getAsJsonObject("demotion");
        Assert.assertTrue(dm.get("changed").getAsBoolean(), "it would have: " + dm);
        Assert.assertFalse(dm.get("applied").getAsBoolean(), dm.toString());
    }

    // ------------------------------------------------------------------ departures

    /**
     * The policy passes: key on, the refused Snuff Out is still among the searched candidates (as off), and in a tie the
     * search keeps pass, so the demotion has nothing to change and the decision line no ownHarm field.
     */
    @Test
    public void keyOnASnuffOutDepartureIsPlayedOut() throws Exception {
        final Run off = run("", snuff(), null);
        final Run on = run("ownHarm=on", snuff(), null);
        Assert.assertEquals(off.answer, "pass", off.summary());
        Assert.assertEquals(on.answer, "pass", on.summary());
        Assert.assertNotNull(off.line(), off.summary());
        Assert.assertNotNull(on.line(), on.summary());
        Assert.assertNull(on.ownHarm(), on.summary());
        Assert.assertEquals(on.ownHarmLines.size(), 1, on.summary());
        final JsonObject oh = note(on.ownHarmLines.get(0));
        final JsonArray refused = oh.getAsJsonArray("refused");
        Assert.assertTrue(refused.size() >= 1, oh.toString());
        for (int i = 0; i < refused.size(); i++) {
            final JsonObject x = refused.get(i).getAsJsonObject();
            Assert.assertEquals(x.get("card").getAsString(), SNUFF, oh.toString());
            Assert.assertFalse(x.has("default"), "the policy passed: " + oh);
        }
        Assert.assertFalse(oh.has("fallback") || oh.has("demotion"), "pass, kept, is no refused pick: " + oh);
        Assert.assertEquals(on.line().get("candidates").getAsInt(), off.line().get("candidates").getAsInt(),
                "nothing leaves: off " + off.line() + " on " + on.line());
    }

    /**
     * Decision-line numbering: replay marks and "Continue from here" rebuilds key on the policy seat's decision-line index.
     * The policy passes with only Snuff Out besides: nothing leaves under the demotion, so on writes decision line 0 as off
     * does (searched, the same candidates and outcome), and every later line keeps its key-off index.
     */
    @Test
    public void keyOnTheDecisionLineIsTheKeyOffOne() throws Exception {
        final Run off = run("", snuff(false), null);
        final Run on = run("ownHarm=on", snuff(false), null);
        Assert.assertEquals(off.answer, "pass", off.summary());
        Assert.assertEquals(on.answer, "pass", on.summary());
        Assert.assertNotNull(off.line(), off.summary());
        Assert.assertNotNull(on.line(), on.summary());
        Assert.assertEquals(off.line().get("decision").getAsInt(), 0);
        Assert.assertEquals(on.line().get("decision").getAsInt(), 0);
        Assert.assertNotEquals(on.line().get("outcome").getAsString(), "uncontested", on.summary());
        Assert.assertEquals(on.line().get("outcome").getAsString(), off.line().get("outcome").getAsString(), on.summary());
        Assert.assertEquals(on.line().get("candidates").getAsInt(), off.line().get("candidates").getAsInt(), on.summary());
        Assert.assertNull(on.ownHarm(), on.summary());
    }

    // ------------------------------------------------------------------ what stays

    /**
     * Abrade: its damage mode has only our own Grizzly Bears to hit, but its destroy-artifact mode has the opponent's Sol
     * Ring: a mode is live, so the policy's Abrade stays; with no artifact on the battlefield every mode it could take is
     * refused (by controller: our own Bears), and pass, the only entry left, wins the tie.
     */
    @Test
    public void keyOnAModalSpellWithALiveModeStays() throws Exception {
        final Run live = run("ownHarm=on", abrade(true), "Abrade");
        Assert.assertEquals(live.answer, "Abrade", live.summary());
        Assert.assertNull(live.ownHarm(), live.summary());
        Assert.assertTrue(live.ownHarmLines.isEmpty(), live.summary());
        final Run dead = run("ownHarm=on", abrade(false), "Abrade");
        Assert.assertNotEquals(dead.answer, "Abrade", dead.summary());
        Assert.assertEquals(dead.answer, "pass", dead.summary());
        Assert.assertNotNull(dead.line(), dead.summary());
        Assert.assertNotEquals(dead.line().get("outcome").getAsString(), "uncontested", "Abrade is played out: " + dead.summary());
        Assert.assertEquals(dead.ownHarmLines.size(), 1, dead.summary());
        final JsonObject oh = dead.ownHarm();
        Assert.assertNotNull(oh, dead.summary());
        Assert.assertEquals(oh.getAsJsonObject("demotion").get("decisionLabel").getAsString(), "pass", oh.toString());
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

    // ------------------------------------------------------------------ review 1b: a proven line through such a step

    /**
     * Our summoning-sick Guttersnipe (2 damage to each opponent whenever we cast an instant or sorcery), the opponent at 2
     * life with no creature and no card in hand, and Swords to Plowshares in hand: its only legal target is our own
     * Guttersnipe, and casting it wins (the trigger resolves first). Every winning line casts Swords at our Guttersnipe.
     */
    private Board guttersnipe() {
        return (g, p0, p1) -> {
            addCard("Plains", p0);
            addCard(GUTTERSNIPE, p0).setSickness(true);
            addCardToZone(SWORDS, p0, ZoneType.Hand);
            addCard("Island", p1);
            p1.setLife(2, null);
        };
    }

    /**
     * Review 2 of #251: our summoning-sick Bears and Sanguine Bond (whenever we gain life, target opponent loses that
     * much), the opponent at 2 life with no creature and no card in hand, and Swords to Plowshares in hand: its only legal
     * target is our own Bears, and casting it wins (we gain 2, the opponent loses 2). No trigger of ours could fire from
     * the exile itself (Sanguine Bond's is a life-gain trigger, Swords' own follow-up), so a line through it breaks.
     */
    private Board sanguineBond() {
        return (g, p0, p1) -> {
            addCard("Plains", p0);
            addCard("Grizzly Bears", p0).setSickness(true);
            addCard("Sanguine Bond", p0);
            addCardToZone(SWORDS, p0, ZoneType.Hand);
            addCard("Island", p1);
            p1.setLife(2, null);
        };
    }

    /** The plan search with its solver on (plans present; live requires hideOwnLibrary), lineOwnTarget as given. */
    private static String plans(final boolean lineOwnTarget, final String extra) {
        return "plans.solverCfg.hideOwnLibrary=true,plans.solverCfg.lineOwnTarget=" + lineOwnTarget
                + (extra.isEmpty() ? "" : "," + extra);
    }

    /**
     * Key off: the solver proves the Swords line at the plan point and the seat takes it at once (decision outcome
     * "proven"); its hook casts Swords at our own creature.
     */
    @Test
    public void keyOffAProvenLineThroughSwordsAtOurOwnCreatureIsTaken() throws Exception {
        for (Board b : new Board[] {guttersnipe(), sanguineBond()}) {
            final Run r = run(plans(false, ""), b, null);
            Assert.assertNotNull(r.line(), r.summary());
            Assert.assertEquals(r.line().get("outcome").getAsString(), "proven", r.summary());
            Assert.assertEquals(r.answer, SWORDS, r.summary());
        }
    }

    /**
     * Review 2 of #251 (the reviewer's S1 3 in a line): Guttersnipe's cast trigger could fire from casting Swords, so with
     * lineOwnTarget on the proven line through Swords at our own Guttersnipe is proposed and taken, as with the key off.
     */
    @Test
    public void keyOnAProvenLineThroughACastTriggerOfOursIsTaken() throws Exception {
        final Run r = run(plans(true, "ownHarm=on"), guttersnipe(), null);
        Assert.assertNotNull(r.line(), r.summary());
        Assert.assertEquals(r.line().get("outcome").getAsString(), "proven", r.summary());
        Assert.assertEquals(r.answer, SWORDS, r.summary());
        Assert.assertTrue(r.ownHarmLines.isEmpty(), r.summary());
    }

    /**
     * Review 1b of #251: with lineOwnTarget on, a proven line the live follower would break at its first step (Swords at
     * our own Bears, paid for only by its own life gain; Sanguine Bond's life-gain trigger is not one the exile sets off)
     * is not proposed, so it is never adopted, broken and proposed again at this priority. The seat ends on a normal
     * search decision in the same call, where ownHarm keeps Swords (its life gain) and the search casts it: as the
     * policy's own choice, and as a departure when the policy passes. Before this fix the line was adopted and broke at
     * once, and Forge AI played the priority.
     */
    @Test
    public void keyOnTheLineIsNotAdoptedAndTheSearchStillCastsSwords() throws Exception {
        for (String pick : new String[] {SWORDS, null}) {
            final Run r = run(plans(true, "ownHarm=on"), sanguineBond(), pick);
            Assert.assertEquals(r.decisions.size(), 1, "one decision: " + r.summary());
            Assert.assertNotEquals(r.line().get("outcome").getAsString(), "proven", "no line taken: " + r.summary());
            Assert.assertTrue(r.line().has("candidates") && r.line().get("candidates").getAsInt() >= 2,
                    "a searched decision: " + r.summary());
            Assert.assertNull(r.ownHarm(), "ownHarm refuses nothing here (Swords' life gain): " + r.summary());
            Assert.assertTrue(r.ownHarmLines.isEmpty(), r.summary());
            Assert.assertEquals(r.answer, SWORDS, "pick " + pick + ": " + r.summary());
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

    // ------------------------------------------------------------------ review 3: the demotion, decision level

    /** The [own-harm] line's note. */
    private static JsonObject note(final String line) {
        return JsonParser.parseString(line.substring("[own-harm] ".length())).getAsJsonObject();
    }

    /**
     * Stubbed play-out values ({@link LookaheadSearch#testValues}): every searched candidate is worth 0.5 plus the gain of
     * the first key it matches (a key "Name|text" matches a label starting with Name and containing text; "pass" only
     * pass), plus a jitter of 1e-4 alternating by world, doubled for a candidate with a gain. So, over the two worlds, a
     * positive gain over an ungained candidate passes the departZ test (t = gain / 1e-4), and equal gains tie exactly.
     */
    static java.util.function.ToDoubleBiFunction<String, Integer> gains(final Object... keyGain) {
        return (label, w) -> {
            final double j = (w % 2 == 0 ? 1 : -1) * 1e-4;
            for (int i = 0; i < keyGain.length; i += 2) {
                final String key = (String) keyGain[i];
                final int bar = key.indexOf('|');
                final String head = bar < 0 ? key : key.substring(0, bar);
                final boolean hit = "pass".equals(head) ? "pass".equals(label)
                        : label.startsWith(head) && (bar < 0 || label.contains(key.substring(bar + 1)));
                if (hit) {
                    final double gn = ((Number) keyGain[i + 1]).doubleValue();
                    return 0.5 + gn + (gn == 0 ? j : 2 * j);
                }
            }
            return 0.5 + j;
        };
    }

    private Run runStub(final String extra, final Board board, final String pick,
            final java.util.function.ToDoubleBiFunction<String, Integer> values) throws Exception {
        LookaheadSearch.testValues = values;
        try {
            return run(extra, board, pick);
        } finally {
            LookaheadSearch.testValues = null;
        }
    }

    private static SpellAbility ability(final Card c, final ApiType api, final Player me) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.getApi() == api) {
                sa.setActivatingPlayer(me);
                return sa;
            }
        }
        throw new AssertionError(c + " has no " + api);
    }

    private static SpellAbility script(final Card c, final String svar, final Player me) {
        final SpellAbility sa = AbilityFactory.getAbility(c.getSVar(svar), c);
        sa.setActivatingPlayer(me);
        return sa;
    }

    /** One of the reviewer's round-3 boards (review/251/r3): the refused card and the stub key of its refused ability. */
    static final class Repro {
        final String tag, card, key;
        final Board board;

        Repro(final String tag, final String card, final String key, final Board board) {
            this.tag = tag;
            this.card = card;
            this.key = key;
            this.board = board;
        }
    }

    /**
     * The eight round-3 repros (review/251/r3, findings S1 1 to 6), each with a payoff the guard cannot model, so the
     * guard refuses the card; the opponent has no creature, artifact or enchantment, and the policy passes.
     */
    private Repro[] r3() {
        return new Repro[] {
            // S1 1a: Pia Nalaar's cast-from-exile trigger (Flame Slash exiled by Light Up the Stage), at our only creature
            new Repro("cast-origin", "Flame Slash", "Flame Slash", (g, p0, p1) -> {
                addCard("Mountain", p0);
                addCard(PIA, p0).setSickness(false);
                addCardToZone("Mountain", p0, ZoneType.Library);
                final Card slash = addCardToZone("Flame Slash", p0, ZoneType.Library);
                AbilityUtils.resolve(ability(addCardToZone("Light Up the Stage", p0, ZoneType.Hand), ApiType.Dig, p0));
                Assert.assertTrue(slash.isInZone(ZoneType.Exile), "Flame Slash exiled, castable");
                addCard("Island", p1);
            }),
            // S1 1b: Shirei returns the shrunk 0/0 Bears
            new Repro("shrink-filter", "Disfigure", "Disfigure", (g, p0, p1) -> {
                addCard("Swamp", p0);
                addCard("Shirei, Shizo's Caretaker", p0);
                addCard("Grizzly Bears", p0);
                addCardToZone("Disfigure", p0, ZoneType.Hand);
                addCard("Island", p1);
            }),
            // S1 3: exiling our Out of Time phases our creature back in (a static command-zone return)
            new Repro("static-return", "Tear Asunder", "Tear Asunder", (g, p0, p1) -> {
                addCard("Forest", p0);
                addCard("Forest", p0);
                final Card bears = addCard("Grizzly Bears", p0);
                final Card out = addCard("Out of Time", p0);
                AbilityUtils.resolve(script(out, "TrigUntap", p0));
                Assert.assertTrue(bears.isPhasedOut());
                addCardToZone("Tear Asunder", p0, ZoneType.Hand);
                addCard("Island", p1);
            }),
            // S1 5: Soulfire Grand Master's lifelink on Flame Slash at our indestructible Myr, at 2 life
            new Repro("lifelink", "Flame Slash", "Flame Slash", (g, p0, p1) -> {
                addCard("Mountain", p0);
                addCard("Soulfire Grand Master", p0);
                addCard("Darksteel Myr", p0);
                addCardToZone("Flame Slash", p0, ZoneType.Hand);
                p0.setLife(2, null);
                addCard("Island", p1);
            }),
            // S1 4: Adarkar Valkyrie's delayed trigger returns the destroyed Bears
            new Repro("delayed-death", "Doom Blade", "Doom Blade", (g, p0, p1) -> {
                addCard("Swamp", p0);
                addCard("Swamp", p0);
                final Card valk = addCard("Adarkar Valkyrie", p0);
                final Card bears = addCard("Grizzly Bears", p0);
                final SpellAbility d = ability(valk, ApiType.DelayedTrigger, p0);
                d.getTargets().add(bears);
                AbilityUtils.resolve(d);
                addCardToZone("Doom Blade", p0, ZoneType.Hand);
                addCard("Island", p1);
            }),
            // S1 6: exiling our Banishing Light returns our stolen Bears
            new Repro("duration-return", "Tear Asunder", "Tear Asunder", (g, p0, p1) -> {
                addCard("Forest", p0);
                addCard("Forest", p0);
                final Card bears = addCard("Grizzly Bears", p0);
                bears.setController(p1, g.getNextTimestamp());
                final Card light = addCard("Banishing Light", p0);
                final SpellAbility ex = script(light, "TrigExile", p0);
                ex.getTargets().add(bears);
                AbilityUtils.resolve(ex);
                addCardToZone("Tear Asunder", p0, ZoneType.Hand);
                addCard("Island", p1);
            }),
            // S1 2a: Garruk Relentless fights our Bears and transforms
            new Repro("garruk-state", GARRUK, GARRUK + "|damage", (g, p0, p1) -> {
                final Card garruk = addCard(GARRUK, p0);
                garruk.setCounters(CounterEnumType.LOYALTY, 3);
                addCard("Grizzly Bears", p0);
                addCard("Island", p1);
            }),
            // S1 2b: Wandertale Mentor's expend 4 grows it before Disfigure resolves
            new Repro("expend", "Disfigure", "Disfigure", (g, p0, p1) -> {
                addCard("Swamp", p0);
                addCard("Wandertale Mentor", p0);
                p0.setExpentThisTurn(3);
                addCardToZone("Disfigure", p0, ZoneType.Hand);
                addCard("Island", p1);
            }),
        };
    }

    /** The decision's demotion record (from its [own-harm] line), asserting the guard refused {@code card}. */
    private static JsonObject refusedAndDemotion(final Run r, final String card) {
        Assert.assertEquals(r.ownHarmLines.size(), 1, r.summary());
        final JsonObject oh = note(r.ownHarmLines.get(0));
        boolean refused = false;
        for (com.google.gson.JsonElement e : oh.getAsJsonArray("refused")) {
            refused |= card.equals(e.getAsJsonObject().get("card").getAsString());
        }
        Assert.assertTrue(refused, "the guard refuses " + card + ": " + oh);
        return oh.has("demotion") ? oh.getAsJsonObject("demotion") : null;
    }

    /**
     * Review 3: each round-3 repro (refused by the guard) is CHOSEN at the decision level when its play-outs are better by
     * 0.05 and pass the departZ test: the demotion lets a real payoff through. Pia Nalaar's (cast-origin) Flame Slash from
     * exile is the exception the copier makes: its play-outs fail in every copy (candidate_not_in_copy: the may-play
     * effect's text differs in the copy), so it is never chosen with the key off either; the demotion changes nothing.
     */
    @Test
    public void r3ReprosAreChosenWhenBetterByTheFloor() throws Exception {
        final List<String> bad = new ArrayList<>();
        for (Repro x : r3()) {
            final Run r = runStub("ownHarm=on", x.board, null, gains(x.key, 0.05));
            try {
                final JsonObject dm = refusedAndDemotion(r, x.card);
                if ("cast-origin".equals(x.tag)) {
                    Assert.assertEquals(r.answer, "pass", r.summary());
                    Assert.assertNull(dm, "its play-outs failed: no pick to demote: " + r.summary());
                    Assert.assertTrue(r.err.contains("candidate_not_in_copy cast Flame Slash"), "the copier: " + r.summary());
                    final Run off = runStub("", x.board, null, gains(x.key, 0.05));
                    Assert.assertEquals(off.answer, "pass", "key off too: " + off.summary());
                    continue;
                }
                Assert.assertEquals(r.answer, x.card, r.summary());
                Assert.assertNotNull(dm, "the refused departure was tested: " + r.summary());
                final JsonObject t = dm.getAsJsonObject("test");
                Assert.assertTrue(t.get("better").getAsBoolean(), t.toString());
                Assert.assertEquals(t.get("against").getAsString(), "pass", t.toString());
                Assert.assertEquals(t.get("gain").getAsDouble(), 0.05, 1e-9, t.toString());
                Assert.assertFalse(dm.get("changed").getAsBoolean(), dm.toString());
                Assert.assertNull(r.ownHarm(), "unchanged, so no decision-line field: " + r.summary());
            } catch (AssertionError e) {
                bad.add(x.tag + ": " + e.getMessage());
            }
        }
        Assert.assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /**
     * Review 3: better by only 0.005 (confident, but under the floor) or tied, a round-3 repro is NOT chosen, and with the
     * key off the confident 0.005 departure is taken: the floor is what stops it (Pia Nalaar's: see above).
     */
    @Test
    public void r3ReprosAreNotChosenUnderTheFloorOrInATie() throws Exception {
        final List<String> bad = new ArrayList<>();
        for (Repro x : r3()) {
            if ("cast-origin".equals(x.tag)) {
                continue;
            }
            try {
                final Run small = runStub("ownHarm=on", x.board, null, gains(x.key, 0.005));
                final JsonObject dm = refusedAndDemotion(small, x.card);
                Assert.assertEquals(small.answer, "pass", small.summary());
                Assert.assertNotNull(dm, "the refused departure was tested: " + small.summary());
                final JsonObject t = dm.getAsJsonObject("test");
                Assert.assertTrue(t.get("confident").getAsBoolean(), t.toString());
                Assert.assertFalse(t.get("aboveFloor").getAsBoolean() || t.get("better").getAsBoolean(), t.toString());
                Assert.assertTrue(dm.get("changed").getAsBoolean(), dm.toString());
                Assert.assertNotNull(small.ownHarm(), "the demotion changed the decision: " + small.summary());
                final Run off = runStub("", x.board, null, gains(x.key, 0.005));
                Assert.assertEquals(off.answer, x.card, "key off: " + off.summary());
                final Run tie = runStub("ownHarm=on", x.board, null, gains(x.key, 0.0));
                Assert.assertEquals(tie.answer, "pass", "tie: " + tie.summary());
                Assert.assertNull(refusedAndDemotion(tie, x.card), "tie: the search keeps pass, nothing to demote");
            } catch (AssertionError e) {
                bad.add(x.tag + ": " + e.getMessage());
            }
        }
        Assert.assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /**
     * Review 3: the policy's own choice refused (Snuff Out). Tied with the Island play (both +0.02 over pass), the best
     * entry not refused, the Island, is the decision; better than it by 0.05 Snuff Out stays; by 0.005 it does not.
     */
    @Test
    public void thePolicysOwnRefusedChoiceGivesWayToTheBestEntryNotRefused() throws Exception {
        final Run tie = runStub("ownHarm=on", snuff(), SNUFF, gains(SNUFF, 0.02, "Island", 0.02));
        Assert.assertEquals(tie.answer, "Island", tie.summary());
        final JsonObject dm = refusedAndDemotion(tie, SNUFF);
        Assert.assertEquals(dm.get("natural").getAsInt(), 0, dm.toString());
        Assert.assertTrue(dm.getAsJsonObject("test").get("against").getAsString().startsWith("Island"), dm.toString());
        Assert.assertFalse(dm.getAsJsonObject("test").get("confident").getAsBoolean(), dm.toString());
        Assert.assertNotNull(tie.ownHarm(), tie.summary());
        final Run better = runStub("ownHarm=on", snuff(), SNUFF, gains(SNUFF, 0.07, "Island", 0.02));
        Assert.assertEquals(better.answer, SNUFF, better.summary());
        Assert.assertTrue(refusedAndDemotion(better, SNUFF).getAsJsonObject("test").get("better").getAsBoolean(),
                better.summary());
        Assert.assertNull(better.ownHarm(), better.summary());
        final Run small = runStub("ownHarm=on", snuff(), SNUFF, gains(SNUFF, 0.025, "Island", 0.02));
        Assert.assertEquals(small.answer, "Island", small.summary());
    }

    /**
     * Review 3: every entry but pass refused (Abrade, both modes aimed only at our own Bears). Tied, pass; better by 0.05,
     * Abrade, as the policy's own choice and as a departure.
     */
    @Test
    public void allButPassRefusedPassesInATie() throws Exception {
        for (String pick : new String[] {"Abrade", null}) {
            final Run tie = runStub("ownHarm=on", abrade(false), pick, gains("Abrade", 0.0));
            Assert.assertEquals(tie.answer, "pass", pick + ": " + tie.summary());
            final Run better = runStub("ownHarm=on", abrade(false), pick, gains("Abrade", 0.05));
            Assert.assertEquals(better.answer, "Abrade", pick + ": " + better.summary());
            Assert.assertTrue(refusedAndDemotion(better, "Abrade").getAsJsonObject("test").get("better").getAsBoolean(),
                    better.summary());
        }
    }
}
