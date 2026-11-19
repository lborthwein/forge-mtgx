package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import forge.game.GameObject;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * target-picks-1119: the policy seat's target picks. Keys (all absent = off, the seat as on the live jar):
 * {@code ownHarmPicks=on} (the pick guard, and the ownHarm check reading enters triggers), {@code targetVariants=N} (the
 * pilot's search offers target variants), {@code targetLog=on} (per-pick records on the decision lines, totals on the end
 * line).
 *
 * <p>Find-first: in the live pilot configuration the bridge routes a trigger's targets (after Forge AI prepared them,
 * {@code PlayerControllerBridge.prepareForSeat} then {@code retargetForSeat}) and the "you may" answer
 * ({@code confirmTrigger} at resolution) to the seat, whose policy answers both whenever the menu has a real choice
 * ({@link #findFirstTheSeatAnswersTriggerTargetsAndYouMay}).
 *
 * <p>Drives the live seat ({@link RlLiveSeat}) against {@link FakeSearchService} (a scripted policy: {@link Script}),
 * as {@link OwnHarmSearchTest}. Plays real Forge copies on the search's pool: run inside a broker test lease, cwd =
 * forge-gui with res/.
 */
public class TargetPicksTest extends SimulationTest {
    static final String SHA = RlBlockSearchTest.SHA;
    static final long SEED = 1119L;
    static final long LIVE_UID = RlSearch.splitmix(SEED ^ 0x6c697665L);
    static final String SAGE = "Reclamation Sage";
    static final String PRIMUS = "Woodfall Primus";
    static final String SOL = "Sol Ring";
    static final String STONE = "Mind Stone";
    static final String SLASH = "Flame Slash";
    static final String BOLT = "Lightning Bolt";
    static final String OURS = "Runeclaw Bear";
    static final String THEIRS = "Grizzly Bears";

    private static Path indexFile;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    private static synchronized Path index() throws Exception {
        if (indexFile == null) {
            final TreeSet<String> names = new TreeSet<>(Arrays.asList("Forest", "Mountain", "Plains", "Island", "Swamp",
                    SAGE, PRIMUS, SOL, STONE, SLASH, BOLT, OURS, THEIRS));
            final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
            int i = 2;
            for (String n : names) {
                NAMES.put(i, n);
                sb.append(n).append('\t').append(i++).append('\n');
            }
            indexFile = Files.createTempFile("target-picks-index-", ".tsv");
            Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return indexFile;
    }

    static String spec(final String server, final String extra) throws Exception {
        return "server=" + server + ",policySha=" + SHA + ",cardIndex=" + index() + ",worlds=2,breadth=8,horizon=1,threads=2"
                + (extra.isEmpty() ? "" : "," + extra);
    }

    /**
     * The scripted policy, live game only: priority = the first entry of {@code pick} (null: pass); targets = the card
     * named {@code target} (null: the fake's random legal answer); "you may" = {@code yes} (null: random). Every play-out
     * passes. The live game's families asked are kept.
     */
    static final class Script implements java.util.function.Function<RlWire.Decide, short[]> {
        final String pick, target;
        final Boolean yes;
        final List<Integer> families = Collections.synchronizedList(new ArrayList<>());

        Script(final String pick, final String target, final Boolean yes) {
            this.pick = pick;
            this.target = target;
            this.yes = yes;
        }

        @Override
        public short[] apply(final RlWire.Decide d) {
            final boolean live = d.gameUid == LIVE_UID;
            if (live) {
                families.add(d.family);
            }
            if (d.family == RlSchema.F_PRIORITY) {
                int pass = -1, want = -1;
                for (int i = 0; i < d.C; i++) {
                    if (d.candKind[i] == RlSchema.K_PASS && pass < 0) {
                        pass = i;
                    }
                    if (want < 0 && pick != null && live && (d.candKind[i] == RlSchema.K_CAST
                            || d.candKind[i] == RlSchema.K_ACTIVATE) && pick.equals(NAMES.get(d.candCard[i]))) {
                        want = i;
                    }
                }
                return new short[] {(short) (want >= 0 ? want : pass)};
            }
            if (!live) {
                return null;
            }
            if (d.family == RlSchema.F_TARGETS && target != null) {
                for (int i = 0; i < d.C; i++) {
                    if (d.candKind[i] == RlSchema.K_CARD && target.equals(NAMES.get(d.candCard[i]))) {
                        return new short[] {(short) i};
                    }
                }
            }
            if (d.family == RlSchema.F_OPTIONAL_TRIGGER && yes != null) {
                for (int i = 0; i < d.C; i++) {
                    if (d.candKind[i] == (yes ? RlSchema.K_YES : RlSchema.K_NO)) {
                        return new short[] {(short) i};
                    }
                }
            }
            return null;
        }
    }

    static final class Run {
        String answer;
        /** The answer's first targeting part's targets (names; a player by name). */
        final List<String> targets = new ArrayList<>();
        final List<JsonObject> decisions = new ArrayList<>();
        final List<String> pickLines = new ArrayList<>();
        JsonObject end;
        String err;
        Game game;

        JsonObject line() {
            return decisions.isEmpty() ? null : decisions.get(0);
        }

        boolean onField(final String name) {
            for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
                if (c.getName().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        String summary() {
            return "answer " + answer + " -> " + targets + "; decision " + line() + "; picks " + pickLines + "; end " + end;
        }
    }

    interface Board {
        void build(Game g, Player p0, Player p1);
    }

    interface Act {
        void act(Game g, Player p0, Run r);
    }

    /** The live seat's priority ask; the answer and its first targeting part's targets. */
    static final Act PRIORITY = (g, p0, r) -> {
        final List<SpellAbility> a = p0.getController().chooseSpellAbilityToPlay();
        final SpellAbility sa = a == null || a.isEmpty() ? null : a.get(0);
        r.answer = sa == null ? "pass" : sa.getHostCard() == null ? "?" : sa.getHostCard().getName();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                for (GameObject o : s.getTargets()) {
                    r.targets.add(o instanceof Card ? ((Card) o).getName() : o instanceof Player ? ((Player) o).getName() : "?");
                }
                break;
            }
        }
    };

    /** Reclamation Sage (in our hand) enters: its trigger goes on the stack (the seat's targets) and resolves ("you may"). */
    static final Act SAGE_ENTERS = (g, p0, r) -> {
        Card sage = null;
        for (Card c : p0.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals(SAGE)) {
                sage = c;
            }
        }
        Assert.assertNotNull(sage, "Reclamation Sage in hand");
        g.getAction().moveToPlay(sage, null, AbilityKey.newMap());
        g.getAction().checkStateEffects(true);
        g.getTriggerHandler().runWaitingTriggers();
        g.getStack().addAllTriggeredAbilitiesToStack();
        Assert.assertFalse(g.getStack().isEmpty(), "the trigger is on the stack");
        g.getStack().resolveStack();
        g.getAction().checkStateEffects(true);
        r.answer = "resolved";
    };

    private Run run(final String extra, final Board board, final Script script, final Act act) throws Exception {
        final Run r = new Run();
        final PrintStream was = System.err;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), extra), "target-picks-test");
            Assert.assertNotNull(live, "the seat binds the fake service");
            final LobbyPlayerAi ai = new LobbyPlayerAi("Seat1", null);
            ai.setAiProfile("Default");
            final List<RegisteredPlayer> players = Arrays.asList(
                    new RegisteredPlayer(new Deck()).setPlayer(live.lobby("Seat0", 0, "Default")),
                    new RegisteredPlayer(new Deck()).setPlayer(ai));
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(players, rules, new Match(rules, players, "target-picks"));
            r.game = game;
            final Player p0 = game.getRegisteredPlayers().get(0);
            final Player p1 = game.getRegisteredPlayers().get(1);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p0, 10);
            board.build(game, p0, p1);
            fillLibrary(p0, 20);
            fillLibrary(p1, 20);
            game.getAction().checkStateEffects(true);
            svc.script = script;
            live.bind(game, 0, SEED, null, AiFixes.Mode.OFF);
            try {
                act.act(game, p0, r);
            } finally {
                live.finish();
            }
            System.err.flush();
            r.err = buf.toString(StandardCharsets.UTF_8);
            for (String l : r.err.split("\n")) {
                if (l.startsWith("[lookahead-decision] ") && l.contains("\"seat\":\"policy\"") && !l.contains("\"kind\"")) {
                    r.decisions.add(JsonParser.parseString(l.substring("[lookahead-decision] ".length())).getAsJsonObject());
                }
                if (l.startsWith("[own-harm-pick] ")) {
                    r.pickLines.add(l);
                }
                if (l.startsWith("[policy-seat] ") && l.contains("\"end\":true")) {
                    r.end = JsonParser.parseString(l.substring("[policy-seat] ".length())).getAsJsonObject();
                }
            }
        } finally {
            System.setErr(was);
            was.print(buf.toString(StandardCharsets.UTF_8));
        }
        System.out.println("[target-picks-test] " + extra + ": " + r.summary());
        return r;
    }

    // ------------------------------------------------------------------ boards

    /** Reclamation Sage in our hand; our Sol Ring; the opponent's Mind Stone when {@code theirs}. */
    private Board sage(final boolean theirs) {
        return (g, p0, p1) -> {
            addCard("Forest", p0);
            addCard(SOL, p0);
            addCardToZone(SAGE, p0, ZoneType.Hand);
            if (theirs) {
                addCard(STONE, p1);
            }
            addCard("Plains", p1);
        };
    }

    /**
     * Woodfall Primus (5GGG: destroy target noncreature permanent as it enters) with eight Forests; the opponent has only
     * a creature, so every legal target of its enters trigger is a land of ours.
     */
    private Board primus() {
        return (g, p0, p1) -> {
            for (int i = 0; i < 8; i++) {
                addCard("Forest", p0);
            }
            addCardToZone(PRIMUS, p0, ZoneType.Hand);
            addCard(THEIRS, p1);
        };
    }

    /** Flame Slash (4 damage to target creature) with our Runeclaw Bear and the opponent's Grizzly Bears. */
    private Board slash() {
        return (g, p0, p1) -> {
            addCard("Mountain", p0);
            addCard(OURS, p0).setSickness(false);
            addCardToZone(SLASH, p0, ZoneType.Hand);
            addCard(THEIRS, p1);
            addCard("Plains", p1);
        };
    }

    /** Lightning Bolt (3 damage to any target) with the opponent's Grizzly Bears; nothing of ours to hit. */
    private Board bolt() {
        return (g, p0, p1) -> {
            addCard("Mountain", p0);
            addCardToZone(BOLT, p0, ZoneType.Hand);
            addCard(THEIRS, p1);
            addCard("Plains", p1);
        };
    }

    // ------------------------------------------------------------------ find first

    /**
     * Key absent (the live configuration's routing): Reclamation Sage's trigger, with our Sol Ring and their Mind Stone
     * both legal, sends the seat a TARGETS ask (the trigger's targets) when it goes on the stack and an OPTIONAL_TRIGGER
     * ask ("you may") when it resolves; the policy answers both (here: the scripted policy picks their Mind Stone, yes).
     */
    @Test
    public void findFirstTheSeatAnswersTriggerTargetsAndYouMay() throws Exception {
        final Script s = new Script(null, STONE, true);
        final Run r = run("", sage(true), s, SAGE_ENTERS);
        Assert.assertTrue(s.families.contains(RlSchema.F_TARGETS), "a TARGETS ask reached the policy: " + s.families);
        Assert.assertTrue(s.families.contains(RlSchema.F_OPTIONAL_TRIGGER), "a 'you may' ask reached the policy: " + s.families);
        Assert.assertFalse(r.onField(STONE), "the policy's target, destroyed: " + r.summary());
        Assert.assertTrue(r.onField(SOL), r.summary());
    }

    // ------------------------------------------------------------------ A1: triggers of ours

    /** Key off: only our Sol Ring to target; the seat answers the one-candidate menu itself and the policy's "yes" destroys it. */
    @Test
    public void sageOnlyOurArtifactKeyOffThePolicysYesDestroysIt() throws Exception {
        final Run r = run("", sage(false), new Script(null, null, true), SAGE_ENTERS);
        Assert.assertFalse(r.onField(SOL), "today: our own Sol Ring destroyed: " + r.summary());
        Assert.assertTrue(r.pickLines.isEmpty(), r.summary());
    }

    /** Key on: only our Sol Ring to target, an optional trigger: declined when it resolves, whatever the policy says. */
    @Test
    public void sageOnlyOurArtifactKeyOnIsDeclined() throws Exception {
        final Run r = run("ownHarmPicks=on", sage(false), new Script(null, null, true), SAGE_ENTERS);
        Assert.assertTrue(r.onField(SOL), "declined: our Sol Ring stays: " + r.summary());
        Assert.assertTrue(r.pickLines.stream().anyMatch(l -> l.contains(SAGE) && l.contains("-> decline")), r.summary());
        Assert.assertTrue(r.pickLines.stream().anyMatch(l -> l.contains("-> declined")), r.summary());
    }

    /** Key off: their Mind Stone legal, but the policy picks our Sol Ring and says yes: ours is destroyed. */
    @Test
    public void sageMixedKeyOffThePolicysOwnPickStands() throws Exception {
        final Run r = run("", sage(true), new Script(null, SOL, true), SAGE_ENTERS);
        Assert.assertFalse(r.onField(SOL), "today: the policy's pick, our Sol Ring, destroyed: " + r.summary());
        Assert.assertTrue(r.onField(STONE), r.summary());
    }

    /** Key on: their Mind Stone legal and the policy picks our Sol Ring: retargeted to theirs. */
    @Test
    public void sageMixedKeyOnTargetsTheirs() throws Exception {
        final Run r = run("ownHarmPicks=on", sage(true), new Script(null, SOL, true), SAGE_ENTERS);
        Assert.assertTrue(r.onField(SOL), "ours stays: " + r.summary());
        Assert.assertFalse(r.onField(STONE), "theirs destroyed: " + r.summary());
        Assert.assertTrue(r.pickLines.stream().anyMatch(l -> l.contains("-> retarget") && l.contains(STONE)), r.summary());
    }

    // ------------------------------------------------------------------ A2: Woodfall Primus

    /** ownHarm on, ownHarmPicks absent: the enters trigger is not read; the policy's Woodfall Primus stands. */
    @Test
    public void primusKeyOffStands() throws Exception {
        final Run r = run("ownHarm=on", primus(), new Script(PRIMUS, null, null), PRIORITY);
        Assert.assertEquals(r.answer, PRIMUS, r.summary());
        Assert.assertTrue(r.line() == null || !r.line().has("ownHarm"), r.summary());
    }

    /**
     * ownHarm on and ownHarmPicks on: Woodfall Primus (its enters trigger can destroy only our own Forests) is refused and
     * demoted: in a tie (every value equal) the best entry not refused is played.
     */
    @Test
    public void primusKeyOnIsDemotedInATie() throws Exception {
        final Run r = run("ownHarm=on,ownHarmPicks=on", primus(), new Script(PRIMUS, null, null), PRIORITY);
        Assert.assertNotEquals(r.answer, PRIMUS, r.summary());
        final JsonObject oh = r.line() == null ? null : r.line().getAsJsonObject("ownHarm");
        Assert.assertNotNull(oh, r.summary());
        final JsonObject first = oh.getAsJsonArray("refused").get(0).getAsJsonObject();
        Assert.assertEquals(first.get("card").getAsString(), PRIMUS, oh.toString());
        Assert.assertTrue(first.get("why").getAsString().startsWith("enters: destroy with only our own to target"), oh.toString());
    }

    /** As above, with Woodfall Primus better by more than the floor: it clears the demotion and is played. */
    @Test
    public void primusKeyOnClearsTheFloorWhenBetter() throws Exception {
        LookaheadSearch.testValues = OwnHarmSearchTest.gains(PRIMUS, 0.05);
        final Run r;
        try {
            r = run("ownHarm=on,ownHarmPicks=on", primus(), new Script(PRIMUS, null, null), PRIORITY);
        } finally {
            LookaheadSearch.testValues = null;
        }
        Assert.assertEquals(r.answer, PRIMUS, r.summary());
    }

    // ------------------------------------------------------------------ A3: the cast-time pick

    /**
     * Forge AI's own pick for Flame Slash on this board (a plain Forge AI seat, no policy) is their Grizzly Bears, never
     * our Runeclaw Bear: the harmful mixed-target pick below is constructed (the scripted policy picks ours), as the brief
     * asks when Forge does not pick ours.
     */
    @Test
    public void forgeAiOwnPickForFlameSlashIsTheirs() {
        final Game game = initAndCreateGame();
        final Player p0 = game.getPlayers().get(0);
        final Player p1 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p0);
        slash().build(game, p0, p1);
        game.getAction().checkStateEffects(true);
        Card slash = null;
        for (Card c : p0.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals(SLASH)) {
                slash = c;
            }
        }
        Assert.assertNotNull(slash);
        final SpellAbility sa = slash.getFirstSpellAbility();
        sa.setActivatingPlayer(p0);
        Assert.assertTrue(p0.getController().chooseTargetsFor(sa), "Forge AI targets Flame Slash");
        final List<String> names = new ArrayList<>();
        for (GameObject o : sa.getTargets()) {
            names.add(o instanceof Card ? ((Card) o).getName() : String.valueOf(o));
        }
        System.out.println("[target-picks-test] Forge AI's Flame Slash: " + names);
        Assert.assertEquals(names, List.of(THEIRS));
    }

    /**
     * Key off: the policy's Flame Slash, its target asked of the policy (two creatures: a real choice), picks our own
     * Runeclaw Bear; the pick stands (constructed: see {@link #forgeAiOwnPickForFlameSlashIsTheirs}).
     */
    @Test
    public void slashKeyOffThePolicysOwnPickStands() throws Exception {
        final Run r = run("", slash(), new Script(SLASH, OURS, null), PRIORITY);
        Assert.assertEquals(r.answer, SLASH, r.summary());
        Assert.assertEquals(r.targets, List.of(OURS), r.summary());
    }

    /** Key on: the same pick is retargeted to their Grizzly Bears. */
    @Test
    public void slashKeyOnRetargetsToTheirs() throws Exception {
        final Run r = run("ownHarmPicks=on", slash(), new Script(SLASH, OURS, null), PRIORITY);
        Assert.assertEquals(r.answer, SLASH, r.summary());
        Assert.assertEquals(r.targets, List.of(THEIRS), r.summary());
        Assert.assertTrue(r.pickLines.stream().anyMatch(l -> l.startsWith("[own-harm-pick] cast " + SLASH)
                && l.contains("-> retarget")), r.summary());
    }

    // ------------------------------------------------------------------ B: target variants

    /**
     * targetVariants=4: Lightning Bolt's variants are offered (the policy's Bolt is the default; its play-out targets as the
     * policy's play-out seat picks); with the face variant better (stubbed values), the search departs to it and the live
     * cast's target is the opponent, forced on the seat's targets ask.
     */
    @Test
    public void boltVariantToTheFaceWins() throws Exception {
        LookaheadSearch.testValues = OwnHarmSearchTest.gains(BOLT + "|-> Seat1", 0.05);
        final Run r;
        try {
            r = run("targetVariants=4,targetLog=on", bolt(), new Script(BOLT, THEIRS, null), PRIORITY);
        } finally {
            LookaheadSearch.testValues = null;
        }
        Assert.assertEquals(r.answer, BOLT, r.summary());
        final JsonObject d = r.line();
        Assert.assertNotNull(d, r.summary());
        Assert.assertTrue(d.has("variants") && d.get("variants").getAsInt() >= 1, "variants offered: " + d);
        Assert.assertTrue(d.has("variant"), "a variant chosen: " + d);
        Assert.assertEquals(d.getAsJsonObject("variant").get("ref").getAsString(), "P1", d.toString());
        Assert.assertTrue(d.get("departed").getAsBoolean(), d.toString());
        Assert.assertEquals(r.targets, List.of("Seat1"), "the forced target, not the policy's Grizzly Bears: " + r.summary());
        final JsonArray picks = d.getAsJsonArray("targets");
        Assert.assertNotNull(picks, d.toString());
        Assert.assertEquals(picks.get(0).getAsJsonObject().get("by").getAsString(), "variant", picks.toString());
        Assert.assertEquals(picks.get(0).getAsJsonObject().get("side").getAsString(), "face", picks.toString());
    }

    /** Key absent: no variant offered; the policy's own Bolt at their creature, as on the live jar. */
    @Test
    public void boltKeyOffOffersNoVariant() throws Exception {
        LookaheadSearch.testValues = OwnHarmSearchTest.gains(BOLT + "|-> Seat1", 0.05);
        final Run r;
        try {
            r = run("", bolt(), new Script(BOLT, THEIRS, null), PRIORITY);
        } finally {
            LookaheadSearch.testValues = null;
        }
        Assert.assertEquals(r.answer, BOLT, r.summary());
        Assert.assertEquals(r.targets, List.of(THEIRS), r.summary());
        Assert.assertTrue(r.line() == null || !r.line().has("variants"), r.summary());
    }

    // ------------------------------------------------------------------ C: the pick log

    /**
     * targetLog=on: the policy's Flame Slash at their Grizzly Bears is one record on its own decision line (cast, policy,
     * legal [1 own, 1 opposing, 0 players, 0 other], side opp, harm damage), and the end line carries the totals.
     */
    @Test
    public void targetLogRecordsTheCastPick() throws Exception {
        final Run r = run("targetLog=on", slash(), new Script(SLASH, THEIRS, null), PRIORITY);
        Assert.assertEquals(r.targets, List.of(THEIRS), r.summary());
        final JsonObject d = r.line();
        Assert.assertNotNull(d, r.summary());
        final JsonArray picks = d.getAsJsonArray("targets");
        Assert.assertNotNull(picks, "the decision line's targets: " + d);
        Assert.assertEquals(picks.size(), 1, picks.toString());
        final JsonObject p = picks.get(0).getAsJsonObject();
        Assert.assertEquals(p.get("o").getAsString(), "cast", p.toString());
        Assert.assertEquals(p.get("card").getAsString(), SLASH, p.toString());
        Assert.assertEquals(p.get("by").getAsString(), "policy", p.toString());
        Assert.assertEquals(p.getAsJsonArray("legal").toString(), "[1,1,0,0]", p.toString());
        Assert.assertEquals(p.get("side").getAsString(), "opp", p.toString());
        Assert.assertEquals(p.get("harm").getAsString(), "damage", p.toString());
        final JsonObject t = r.end.getAsJsonObject("targets");
        Assert.assertNotNull(t, "the end line's totals: " + r.end);
        Assert.assertEquals(t.get("picks").getAsInt(), 1, t.toString());
        Assert.assertEquals(t.get("mixedLegal").getAsInt(), 1, t.toString());
        Assert.assertEquals(t.get("harmfulOwn").getAsInt(), 0, t.toString());
    }

    /**
     * targetLog=on with the guard: Reclamation Sage with only our Sol Ring (no decision line follows: the records are the
     * end line's tail): the trigger's pick (the seat's own, one candidate: single; the guard's decline) and the decline at
     * resolution.
     */
    @Test
    public void targetLogRecordsTheTriggerPickAndTheGuard() throws Exception {
        final Run r = run("ownHarmPicks=on,targetLog=on", sage(false), new Script(null, null, true), SAGE_ENTERS);
        final JsonObject t = r.end.getAsJsonObject("targets");
        Assert.assertNotNull(t, r.summary());
        final JsonArray tail = t.getAsJsonArray("tail");
        Assert.assertNotNull(tail, t.toString());
        Assert.assertEquals(tail.size(), 2, tail.toString());
        final JsonObject pick = tail.get(0).getAsJsonObject();
        Assert.assertEquals(pick.get("o").getAsString(), "trigger", pick.toString());
        Assert.assertEquals(pick.get("by").getAsString(), "single", pick.toString());
        Assert.assertEquals(pick.get("side").getAsString(), "own", pick.toString());
        Assert.assertEquals(pick.get("g").getAsString(), "decline", pick.toString());
        Assert.assertEquals(pick.get("harm").getAsString(), "destroy", pick.toString());
        final JsonObject dec = tail.get(1).getAsJsonObject();
        Assert.assertEquals(dec.get("by").getAsString(), "guard", dec.toString());
        Assert.assertEquals(dec.get("g").getAsString(), "declined", dec.toString());
        Assert.assertEquals(t.get("harmfulOwn").getAsInt(), 2, t.toString());
        Assert.assertTrue(r.onField(SOL), r.summary());
    }

    // ------------------------------------------------------------------ keys

    /** The keys parse exactly (no aliases) and write the search spec's JSON only when set. */
    @Test
    public void keysParseStrictlyAndAreAbsentFromTheSpecWhenOff() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", ""));
        Assert.assertFalse(off.search.toJson().has("ownHarmPicks"));
        Assert.assertFalse(off.search.toJson().has("targetVariants"));
        Assert.assertFalse(off.targetLog);
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "ownHarmPicks=on,targetVariants=3,targetLog=on"));
        Assert.assertEquals(on.search.toJson().get("ownHarmPicks").getAsString(), "on");
        Assert.assertEquals(on.search.toJson().get("targetVariants").getAsInt(), 3);
        Assert.assertTrue(on.targetLog);
        for (String bad : new String[] {"ownHarmPicks=true", "ownHarmPicks=shadow", "targetLog=1", "targetVariants=17"}) {
            try {
                RlLiveSeat.Spec.parse(spec("127.0.0.1:1", bad));
                Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }
}
