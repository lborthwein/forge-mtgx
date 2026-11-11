package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-etb-1110 (solverCfg landEntersProbe). The offense reader learns a card's template once, at its first sight
 * in the game, and with it whether a land enters tapped. Darkslick Shores first seen with two lands in play enters
 * untapped; the template keeps that, so with three or more other lands in play the planner still taps it for mana on
 * the turn it enters (tapped, in the engine): the line is illegal, and variants of it fill the planner's line cap.
 * With the key on a plan asks the position it starts at: the land is played in a sandbox copy and read back.
 */
public class LandEntersProbeTest extends forge.ai.AITest {
    private static final String SHORES = "Darkslick Shores";
    private static final String BUMP = "Bump in the Night";

    private static Map<String, String> cfg(final boolean on) {
        final Map<String, String> m = new HashMap<>();
        m.put("act", "false");
        m.put("defense", "false");
        if (on) {
            m.put("landEntersProbe", "true");
        }
        return m;
    }

    /** The templates of a game that first saw Darkslick Shores with two lands in play (it enters untapped there). */
    private static String learnedEarly() {
        final Game early = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                SHORES, "Island;Island", 20, "", "Island", 20, "human"));
        final Player e = early.getPlayers().get(0);
        final ForcedWinSolver se = new ForcedWinSolver(e, ForcedWinSolver.Config.fromProps(k -> null));
        final ForcedWinModel.Position p = se.readPosition(early, se.knownTop(early));
        Assert.assertFalse(p.hand.get(0).entersTapped, "two other lands: it enters untapped, " + p.describe());
        return se.reader.exportJson();
    }

    private static ForcedWinSolver fresh(final Game g, final boolean on) {
        return new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(cfg(on)::get));
    }

    /** A solver on {@code late} that carries the early game's templates, as one game's reader does. */
    private static ForcedWinSolver later(final Game late, final boolean on) {
        final ForcedWinSolver s = fresh(late, on);
        s.reader.preload(learnedEarly());
        return s;
    }

    private static Game late(final String hand, final String battlefield, final int oppLife) {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(hand,
                battlefield, 20, "", "Island", oppLife, "human"));
    }

    private static ForcedWinPlanner.Result plan(final ForcedWinSolver s, final Game g, final int horizon, final int maxLines) {
        final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = horizon;
        lim.maxLines = maxLines;
        return ForcedWinPlanner.plan(pos, lim);
    }

    /** The line taps {@code land} for mana on the turn the line itself plays it. */
    private static boolean tapsOnEntry(final ForcedWinPlanner.Line l, final String land) {
        boolean fresh = false;
        for (ForcedWinPlanner.Step st : l.steps) {
            if (st.pass) {
                fresh = false;
            } else if (st.kind == AbilKind.LAND && st.hostName.equals(land)) {
                fresh = true;
            } else if (st.kind == AbilKind.FLOAT && st.hostName.equals(land) && fresh) {
                return true;
            }
        }
        return false;
    }

    private static int landStep(final ForcedWinPlanner.Line l, final String land) {
        for (int i = 0; i < l.steps.size(); i++) {
            if (l.steps.get(i).kind == AbilKind.LAND && l.steps.get(i).hostName.equals(land)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean plays(final ForcedWinPlanner.Line l, final String land) {
        return landStep(l, land) >= 0;
    }

    private static ACard inHand(final ForcedWinModel.Position pos, final String name) {
        return pos.hand.stream().filter(a -> a.name.equals(name)).findFirst().get();
    }

    private static int[] ids(final List<ACard> cards, final int... more) {
        final int[] out = new int[cards.size() + more.length];
        for (int i = 0; i < cards.size(); i++) {
            out[i] = cards.get(i).id;
        }
        System.arraycopy(more, 0, out, cards.size(), more.length);
        return out;
    }

    /** The engine itself: {@code land} played from our hand as our land drop in {@code g}; is it tapped afterwards? */
    private static boolean engineEntersTapped(final Game g, final String land) {
        final Player me = g.getPlayers().get(0);
        final Card c = ForcedWinSolverTest.inZone(me, ZoneType.Hand, land);
        for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
            if (sa.isLandAbility()) {
                Assert.assertTrue(ForcedWinProbe.play(g, me, sa), land + " could not be played");
                final Card in = ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, land);
                Assert.assertNotNull(in, land + " did not enter");
                return in.isTapped();
            }
        }
        throw new AssertionError("no land ability: " + land);
    }

    // ------------------------------------------------------------------ the gap, and the key

    /**
     * Three Islands in play, Bump in the Night (B: the opponent, at 3, loses 3) and Darkslick Shores in hand: the only
     * black mana this turn would be the entering land's, and it enters tapped. Key off: the planner proposes the illegal
     * line and the solver has nothing that verifies. Key on: no line this turn; the land untaps for the kill next turn.
     */
    @Test
    public void aLandThatEntersTappedHereIsNotTappedForManaTheTurnItEnters() {
        final String hand = BUMP + ";" + SHORES, bf = "Island;Island;Island";
        // off: the template's first-sight answer (untapped) is planned on
        final Game g0 = late(hand, bf, 3);
        final ForcedWinSolver off = later(g0, false);
        final ForcedWinPlanner.Result r0 = plan(off, g0, 1, 120);
        Assert.assertFalse(r0.lines.isEmpty(), r0.summary());
        for (ForcedWinPlanner.Line l : r0.lines) {
            Assert.assertTrue(tapsOnEntry(l, SHORES), "every line this turn needs the entering land's mana: " + l);
        }
        final List<ForcedWinSolver.Proposal> p0 = off.propose(g0);
        Assert.assertTrue(p0.isEmpty(), "the illegal line cannot verify: " + p0.size() + " " + off.summary());
        Assert.assertEquals(off.reader.landProbes, 0, "off: no land-entry sandbox runs");
        // on: the position's own answer
        final Game g1 = late(hand, bf, 3);
        final ForcedWinSolver on = later(g1, true);
        final ForcedWinPlanner.Result now = plan(on, g1, 1, 120);
        Assert.assertTrue(now.lines.isEmpty(), "no legal line this turn: " + (now.lines.isEmpty() ? "" : now.lines.get(0)));
        final ForcedWinPlanner.Result next = plan(on, g1, 2, 120);
        Assert.assertFalse(next.lines.isEmpty(), next.summary());
        for (ForcedWinPlanner.Line l : next.lines) {
            Assert.assertFalse(tapsOnEntry(l, SHORES), "tapped on the turn it enters: " + l);
            Assert.assertEquals(l.winTurn, 1, l.toString());
        }
        Assert.assertEquals(on.reader.landProbes, 1, "one sandbox for the land, shared by both plans");
        Assert.assertEquals(on.reader.landDiffers, 1, "its answer is not the template's");
        Assert.assertEquals(on.reader.landContexts, 0, "no plan changed the battlefield before the land");
        final List<ForcedWinSolver.Proposal> p1 = on.propose(g1);
        Assert.assertFalse(p1.isEmpty(), "the next-turn kill verifies: " + on.summary());
        Assert.assertEquals(p1.get(0).winTurn, 1, p1.get(0).text);
        Assert.assertTrue(p1.get(0).pUnopposed >= 1.0, p1.get(0).text);
        // the engine agrees with the probe (and not with the template)
        Assert.assertTrue(engineEntersTapped(late(hand, bf, 3), SHORES));
    }

    /**
     * The same position with a Swamp in hand too: a legal kill this turn exists (play the Swamp). Key off, the line cap
     * can be filled by the illegal variant alone (cap 1 here, 120 variants in the real games); key on, only legal lines
     * are planned and the solver proves the kill.
     */
    @Test
    public void aLegalLineIsFoundWhenThePositionHasOne() {
        final String hand = BUMP + ";" + SHORES + ";Swamp", bf = "Island;Island;Island";
        final Game g0 = late(hand, bf, 3);
        final ForcedWinSolver off = later(g0, false);
        final ForcedWinPlanner.Result all0 = plan(off, g0, 1, 120);
        Assert.assertTrue(all0.lines.stream().anyMatch(l -> tapsOnEntry(l, SHORES)), "off: the illegal line is planned");
        Assert.assertTrue(all0.lines.stream().anyMatch(l -> plays(l, "Swamp")), "off: so is the legal one");
        final ForcedWinPlanner.Result cap0 = plan(off, g0, 1, 1);
        Assert.assertEquals(cap0.lines.size(), 1);
        Assert.assertTrue(tapsOnEntry(cap0.lines.get(0), SHORES), "off: the capped plan holds only the illegal line: "
                + cap0.lines.get(0));

        final Game g1 = late(hand, bf, 3);
        final ForcedWinSolver on = later(g1, true);
        final ForcedWinPlanner.Result all1 = plan(on, g1, 1, 120);
        Assert.assertFalse(all1.lines.isEmpty(), all1.summary());
        for (ForcedWinPlanner.Line l : all1.lines) {
            Assert.assertFalse(tapsOnEntry(l, SHORES), "on: " + l);
        }
        final ForcedWinPlanner.Result cap1 = plan(on, g1, 1, 1);
        Assert.assertEquals(cap1.lines.size(), 1);
        Assert.assertTrue(plays(cap1.lines.get(0), "Swamp"), "on: the capped plan holds the legal line: " + cap1.lines.get(0));
        final List<ForcedWinSolver.Proposal> p1 = on.propose(g1);
        Assert.assertFalse(p1.isEmpty(), on.summary());
        Assert.assertEquals(p1.get(0).winTurn, 0, p1.get(0).text);
        Assert.assertTrue(p1.get(0).proven(1), p1.get(0).text);
    }

    /** Where the land does enter untapped (two other lands), the key on plans its mana as before, and it verifies. */
    @Test
    public void aLandThatEntersUntappedHereKeepsItsMana() {
        final Game g = late(BUMP + ";" + SHORES, "Island;Island", 3);
        final ForcedWinSolver on = later(g, true);
        final ForcedWinPlanner.Result r = plan(on, g, 1, 120);
        Assert.assertFalse(r.lines.isEmpty(), r.summary());
        Assert.assertTrue(tapsOnEntry(r.lines.get(0), SHORES), r.lines.get(0).toString());
        Assert.assertEquals(on.reader.landProbes, 1);
        Assert.assertEquals(on.reader.landDiffers, 0, "the probe agrees with the template here");
        final List<ForcedWinSolver.Proposal> p = on.propose(g);
        Assert.assertFalse(p.isEmpty(), on.summary());
        Assert.assertTrue(p.get(0).proven(1), p.get(0).text);
        Assert.assertFalse(engineEntersTapped(late(BUMP + ";" + SHORES, "Island;Island", 3), SHORES));
    }

    /** One sandbox per land and position, however often the position is read; a changed board is asked again. */
    @Test
    public void theAnswerIsThePositionsAndIsAskedOncePerPosition() {
        final Game g = late(BUMP + ";" + SHORES, "Island;Island", 3);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver on = later(g, true);
        for (int i = 0; i < 3; i++) {
            Assert.assertFalse(plan(on, g, 1, 120).lines.isEmpty());
        }
        Assert.assertEquals(on.reader.landProbes, 1, "three reads of one position, one sandbox");
        // a third land arrives: the same card now enters tapped, and the plan from the new position knows
        addCard("Island", me);
        Assert.assertTrue(plan(on, g, 1, 120).lines.isEmpty(), "three other lands: no mana from it this turn");
        Assert.assertEquals(on.reader.landProbes, 2);
        // the position's own cards only: a card that is not in the game has no answer (its template's stands)
        final ForcedWinModel.Position pos = on.readPosition(g, on.knownTop(g));
        Assert.assertNull(pos.landEntry.enters(inHand(pos, SHORES).instance(1_000_000_007), null, null));
        Assert.assertEquals(pos.landEntry.enters(inHand(pos, SHORES), null, null)[0], 1);
        Assert.assertEquals(on.reader.landProbes, 2, "answered from the position's kept entries");
    }

    /**
     * The real game the gap was read from (deck cur-doomsday-0023, turn 13, the first of its three plan points), rebuilt
     * from its receipt: our hand, battlefield, life and library size as the solver saw them; the library's order, our
     * graveyard and the opponent's board are made up. The reader had learned Darkslick Shores on turn 1 (it enters
     * untapped on an empty board). Doomsday needs BBB: two Swamps and the Shores, which enters tapped behind four lands.
     * Key off: 120 candidate lines, each with the Shores' mana on the turn it enters, and nothing verifies. Key on: no
     * line this turn, and by next turn a legal one (the Shores played now, untapped then) that verifies.
     */
    @Test
    public void theRealGamesPlanPointGetsALegalLine() {
        final String frame = "[state]\nturn=13\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=8\nailife=20\n"
                + "humanlandsplayed=0\nailandsplayed=0\naihand=\nailibrary=" + ForcedWinSolverTest.LIB20 + ";Forest\n"
                + "aigraveyard=\naiexile=\naibattlefield=Forest;Forest;Forest;Forest\n"
                + "humanhand=Doomsday;Mountain;Exhume;Shallow Grave;Island;Phantasmal Image;" + SHORES + "\n"
                + "humanbattlefield=Swamp;Swamp;Urza, Lord High Artificer;Island;Island;"
                + "Jace, Wielder of Mysteries|Counters:LOYALTY=4\n"
                + "humangraveyard=Mesmeric Fiend;Animate Dead;Gitaxian Probe;Sol Ring\nhumanexile=\n"
                + "humanlibrary=Shelldock Isle;Creeping Tar Pit;Mox Jet;Time Spiral;Swamp;Swamp;Ashiok, Nightmare Weaver;"
                + "Island;Island;Island;Deceiver Exarch;Show and Tell;Timetwister;Wheel of Fortune;Talisman of Dominance;"
                + "Thassa's Oracle;Polluted Delta;Brain Freeze;Damnation;Gush;Island;Island;Swamp\n";
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final ForcedWinSolver off = later(g0, false);
        final ForcedWinPlanner.Result r0 = plan(off, g0, 1, 120);
        Assert.assertEquals(r0.lines.size(), 120, "off: the line cap is full, " + r0.summary());
        for (ForcedWinPlanner.Line l : r0.lines) {
            Assert.assertTrue(tapsOnEntry(l, SHORES), "off: every candidate taps the Shores as it enters: " + l);
        }
        Assert.assertTrue(off.propose(g0).isEmpty(), "off: nothing verifies, " + off.summary());

        final Game g1 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final ForcedWinSolver on = later(g1, true);
        final ForcedWinPlanner.Result now = plan(on, g1, 1, 120);
        Assert.assertTrue(now.lines.isEmpty(), "on: no BBB this turn, " + (now.lines.isEmpty() ? "" : now.lines.get(0)));
        final ForcedWinPlanner.Result next = plan(on, g1, 2, 120);
        Assert.assertFalse(next.lines.isEmpty(), next.summary());
        for (ForcedWinPlanner.Line l : next.lines) {
            Assert.assertFalse(tapsOnEntry(l, SHORES), "on: " + l);
        }
        final List<ForcedWinSolver.Proposal> p1 = on.propose(g1);
        Assert.assertFalse(p1.isEmpty(), "on: a line by next turn verifies, " + on.summary());
        Assert.assertEquals(p1.get(0).winTurn, 1, p1.get(0).text);
        Assert.assertTrue(p1.get(0).pUnopposed >= 1.0, p1.get(0).text);
    }

    // ------------------------------------------------------------------ the board the line has built

    /**
     * Two lands in play, an Island and Darkslick Shores in hand, three Bumps for an opponent at 9 (two this turn at
     * most, so the kill is next turn). Played now the Shores enters untapped; played next turn after the Island it
     * enters tapped (three other lands). The position's answer alone (untapped) would plan Island now, Shores next turn
     * and its mana at once: the same illegal line, a turn on. The key asks again on the battlefield the line has built.
     */
    @Test
    public void aLandPlayedAfterAnotherLandDropIsAskedOnThatBoard() {
        final String hand = "Island;" + SHORES + ";" + BUMP + ";" + BUMP + ";" + BUMP, bf = "Swamp;Island";
        // off (even with a template learned at this very position): the wrong order is planned
        final Game g0 = late(hand, bf, 9);
        final ForcedWinPlanner.Result r0 = plan(fresh(g0, false), g0, 2, 120);
        Assert.assertTrue(r0.lines.stream().anyMatch(l -> tapsOnEntry(l, SHORES) && plays(l, "Island")
                && landStep(l, "Island") < landStep(l, SHORES)), "off: Island, then the Shores and its mana at once");
        final Game g1 = late(hand, bf, 9);
        final ForcedWinSolver on = fresh(g1, true);
        final ForcedWinPlanner.Result r1 = plan(on, g1, 2, 120);
        Assert.assertFalse(r1.lines.isEmpty(), r1.summary());
        for (ForcedWinPlanner.Line l : r1.lines) {
            Assert.assertFalse(tapsOnEntry(l, SHORES) && plays(l, "Island") && landStep(l, "Island") < landStep(l, SHORES),
                    "on: " + l);
        }
        Assert.assertTrue(r1.lines.stream().anyMatch(l -> tapsOnEntry(l, SHORES)), "on: the Shores first keeps its mana");
        Assert.assertTrue(on.reader.landContexts >= 1, on.summary());
        Assert.assertTrue(on.reader.landContextDiffers >= 1, on.summary());
        // the two answers, asked directly, and the engine's
        final ForcedWinModel.Position pos = on.readPosition(g1, on.knownTop(g1));
        Assert.assertEquals(pos.landEntry.enters(inHand(pos, SHORES), ids(pos.battlefield), ids(pos.hand))[0], 0);
        Assert.assertEquals(pos.landEntry.enters(inHand(pos, SHORES), ids(pos.battlefield, inHand(pos, "Island").id),
                new int[0])[0], 1);
        final List<ForcedWinSolver.Proposal> p = on.propose(g1);
        Assert.assertFalse(p.isEmpty(), on.summary());
        Assert.assertEquals(p.get(0).winTurn, 1, p.get(0).text);
        Assert.assertTrue(p.get(0).pUnopposed >= 1.0, p.get(0).text);
        final Game real = late(hand, bf, 9);
        addCard("Island", real.getPlayers().get(0));
        Assert.assertTrue(engineEntersTapped(real, SHORES), "three other lands");
    }

    /**
     * Four lands in play, two of them Islands: the Shores enters tapped. Gush returns the two Islands to our hand as its
     * cost; the Shores played after it sees two other lands and enters untapped: its black mana makes the third Bump
     * this turn. The position's answer alone (tapped) has no kill this turn.
     */
    @Test
    public void landsACostReturnedAreOffTheBoardTheLandEnters() {
        final String hand = "Gush;" + SHORES + ";" + BUMP + ";" + BUMP + ";" + BUMP, bf = "Island;Island;Swamp;Swamp";
        final Game g0 = late(hand, bf, 9);
        Assert.assertTrue(plan(fresh(g0, false), g0, 1, 120).lines.isEmpty(), "off: the template (tapped here) has no line");
        final Game g1 = late(hand, bf, 9);
        final ForcedWinSolver on = fresh(g1, true);
        final ForcedWinPlanner.Result r1 = plan(on, g1, 1, 120);
        Assert.assertFalse(r1.lines.isEmpty(), r1.summary());
        for (ForcedWinPlanner.Line l : r1.lines) {
            Assert.assertTrue(tapsOnEntry(l, SHORES), l.toString());
            Assert.assertTrue(l.steps.stream().anyMatch(st -> st.kind == AbilKind.SPELL && st.hostName.equals("Gush")), l.toString());
        }
        Assert.assertTrue(on.reader.landContextDiffers >= 1, on.summary());
        final List<ForcedWinSolver.Proposal> p = on.propose(g1);
        Assert.assertFalse(p.isEmpty(), on.summary());
        Assert.assertEquals(p.get(0).winTurn, 0, p.get(0).text);
        Assert.assertTrue(p.get(0).proven(1), p.get(0).text);
    }

    /**
     * A land whose entering nothing of its own makes depend on the board (a basic land; "this land enters tapped") is
     * asked once per position; one with a condition or a cost to pay instead is asked on each battlefield a plan builds.
     */
    @Test
    public void onlyALandWithAConditionIsAskedAgainOnAChangedBoard() {
        final Game g = late("Island;Raffine's Tower;Overgrown Tomb;" + SHORES, "Swamp;Island", 20);
        final ForcedWinSolver on = fresh(g, true);
        final ForcedWinModel.Position pos = on.readPosition(g, on.knownTop(g));
        final int[] changed = ids(pos.battlefield, inHand(pos, "Island").id);
        // {tapped on the position's board, tapped after the Island, the same on every board}
        final Object[][] cases = {{"Island", 0, 0, 1}, {"Raffine's Tower", 1, 1, 1}, {SHORES, 0, 1, 0}, {"Overgrown Tomb", 1, 1, 0}};
        int contexts = 0;
        for (Object[] c : cases) {
            final ACard a = inHand(pos, (String) c[0]);
            final int[] here = pos.landEntry.enters(a, ids(pos.battlefield), ids(pos.hand));
            final int[] there = "Island".equals(c[0]) ? here : pos.landEntry.enters(a, changed, new int[0]);
            Assert.assertEquals(here[0], c[1], c[0] + " here");
            Assert.assertEquals(there[0], c[2], c[0] + " after the Island");
            Assert.assertEquals(here[2], c[3], c[0] + ": the same on every board?");
            contexts += (int) c[3] == 0 ? 1 : 0;
            Assert.assertEquals(on.reader.landContexts, contexts, c[0] + ": " + on.summary());
        }
        // the two plain lands keep their template's answer with no sandbox; the two others are played in one
        Assert.assertEquals(on.reader.landProbes, 2, on.summary());
        Assert.assertEquals(on.reader.landPlain, 2, on.summary());
    }

    /**
     * A plain land is answered from its template only while nothing in play replaces other cards' entering: with a
     * Kismet on the opponent's side (our lands enter tapped) a basic land is played in the sandbox, and enters tapped.
     */
    @Test
    public void aPlainLandIsProbedWhenSomethingInPlayReplacesEntries() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                BUMP + ";Swamp", "Island;Island", 20, "", "Kismet", 3, "human"));
        final ForcedWinSolver on = later(g, true);
        final ForcedWinModel.Position pos = on.readPosition(g, on.knownTop(g));
        final int[] entry = pos.landEntry.enters(inHand(pos, "Swamp"), null, null);
        Assert.assertNotNull(entry, pos.describe());
        Assert.assertEquals(entry[0], 1, "it enters tapped under the opponent's Kismet");
        Assert.assertEquals(on.reader.landProbes, 1, on.summary());
        Assert.assertEquals(on.reader.landPlain, 0, on.summary());
        Assert.assertTrue(plan(on, g, 1, 120).lines.isEmpty(), "no black mana this turn");
        Assert.assertTrue(engineEntersTapped(ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame(BUMP + ";Swamp", "Island;Island", 20, "", "Kismet", 3, "human")), "Swamp"));
    }

    // ------------------------------------------------------------------ a choice on entry

    /**
     * A choice on entry (pay 2 life, or it enters tapped): the probe takes what the sandbox's Forge AI does at this
     * position, in its phase and with the mana the seat really has, and that is what the engine's seat does when the
     * land is really played there. (Forge AI pays when it holds a card it would cast now whose mana value is the land
     * count after the drop.) The template's sandbox is a first main phase with twelve mana of each colour afloat: its
     * Forge AI pays for a creature the seat holds back until its second main phase.
     */
    @Test
    public void aChoiceOnEntryIsTheEnginesOwn() {
        final String[][] cases = {
            // {hand, battlefield, phase, the seat pays the life?, the template (learned here) has it enter tapped?}
            // nothing to cast with it: the seat keeps its life, the land enters tapped
            {"Overgrown Tomb", "Swamp;Swamp;Swamp", "MAIN1", "no", "yes"},
            // a two-drop sorcery it casts now (Forest and Mox): the seat pays the 2 life
            {"Overgrown Tomb;Rampant Growth", "Forest;Mox Emerald", "MAIN1", "yes", "no"},
            // a two-drop creature: Forge AI casts it in its second main phase, so in the first it does not pay ...
            {"Overgrown Tomb;Grizzly Bears", "Forest;Mox Emerald", "MAIN1", "no", "no"},
            // ... and in the second it does
            {"Overgrown Tomb;Grizzly Bears", "Forest;Mox Emerald", "MAIN2", "yes", "no"},
        };
        for (String[] c : cases) {
            final String what = c[0] + " | " + c[1] + " | " + c[2];
            final String frame = ForcedWinSolverTest.frame(c[0], c[1], 20, "", "Island", 20, "human").replace(
                    "activephase=MAIN1", "activephase=" + c[2]);
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
            final ForcedWinSolver on = fresh(g, true);
            final ForcedWinModel.Position pos = on.readPosition(g, on.knownTop(g));
            final ACard tomb = inHand(pos, "Overgrown Tomb");
            Assert.assertEquals(tomb.entersTapped, "yes".equals(c[4]), "the template: " + what);
            final int[] entry = pos.landEntry.enters(tomb, null, null);
            Assert.assertNotNull(entry, pos.describe());
            final Game real = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
            final Player rm = real.getPlayers().get(0);
            final boolean tapped = engineEntersTapped(real, "Overgrown Tomb");
            Assert.assertEquals(tapped, "no".equals(c[3]), "the engine's seat: " + what);
            Assert.assertEquals(entry[0] != 0, tapped, "the probe is the engine's: " + what);
            Assert.assertEquals(entry[1], rm.getLife() - 20, what);
            Assert.assertEquals(entry[1], tapped ? 0 : -2, "untapped costs the 2 life: " + what);
        }
    }

    @Test
    public void theKeyIsOffByDefault() {
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).landEntersProbe);
        Assert.assertTrue(ForcedWinSolver.Config.fromProps(Map.of("landEntersProbe", "true")::get).landEntersProbe);
        final Game g = late(BUMP + ";" + SHORES, "Island;Island;Island", 3);
        final ForcedWinSolver off = later(g, false);
        Assert.assertNull(off.readPosition(g, off.knownTop(g)).landEntry);
    }
}
