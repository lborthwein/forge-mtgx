package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Labeller v2 items (a), (b), (c), (e) (PREDECLARATION-labeller-v2.md §1, §3 tests 1-4, 6, 7). Exact mode (one world,
 * the position as it stands), seat "ai" is ours, the opponent (seat "human") has no cards: every case is about our own
 * choices inside a line, not about the opponent. Each case runs v1 (all v2 keys off) and v2 (the item's keys on).
 */
public class LabellerV2ChoicesTest extends AITest {

    static String frame(String aiHand, String aiBf, String aiLib, int humanLife, int aiLife, String humanBf) {
        return "[state]\nturn=7\nactiveplayer=ai\nactivephase=MAIN1\nhumanlife=" + humanLife + "\nailife=" + aiLife + "\n"
                + "humanlandsplayed=0\nailandsplayed=0\naihand=" + aiHand + "\nailibrary=" + aiLib
                + "\naigraveyard=\naiexile=\naibattlefield=" + aiBf + "\nhumanhand=\nhumanbattlefield=" + humanBf
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + ForcedWinSolverTest.LIB20 + "\n";
    }

    static Game game(String aiHand, String aiBf, String aiLib, int humanLife) {
        return ForcedWinSolverTest.game("Default", "Default", frame(aiHand, aiBf, aiLib, humanLife, 20, ""));
    }

    /** As {@link #game}, at 3 life against their Hill Giant: a line that waits for our next turn loses. */
    static Game thisTurn(String aiHand, String aiBf, String aiLib, int humanLife) {
        return ForcedWinSolverTest.game("Default", "Default", frame(aiHand, aiBf, aiLib, humanLife, 3, "Hill Giant"));
    }

    /**
     * A counts-only exact config, plus the given v2 keys. frameMemory: the seat knows its library order (the planner's
     * known top). planner=false starves the width planner (1 node; the engine only runs after it, with planning on);
     * the engine runs only when a card the planner cannot model is in play (Lightning Bolt can target a creature,
     * Exhume returns one), so the boards for the engine carry one.
     */
    static ForcedWinSolver.Config cfg(boolean planner, boolean engine, String... v2) {
        final Map<String, String> m = new HashMap<>();
        m.put("budget", "counter");
        m.put("defense", "false");
        m.put("exact", "true");
        m.put("act", "false");
        m.put("horizon", "1");
        m.put("nodes", planner ? "3000" : "1");
        m.put("frameMemory", "true");
        m.put("engine", engine ? null : "false");
        m.put("engineNodes", "200");
        m.put("engineSteps", "6000");
        for (String k : v2) {
            m.put(k, "true");
        }
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(m::get);
        return c;
    }

    static ForcedWinSolver.Proposal best(Game g, ForcedWinSolver.Config c) {
        final Player ai = g.getPlayers().get(1);
        g.AI_TIMEOUT = 600;
        final ForcedWinSolver s = new ForcedWinSolver(ai, c);
        final List<ForcedWinSolver.Proposal> ps = s.propose(g);
        for (ForcedWinSolver.Proposal p : ps) {
            if (p.proven(1)) {
                return p;
            }
        }
        return null;
    }

    static boolean proven(java.util.function.Supplier<Game> g, ForcedWinSolver.Config c, String tag) {
        final ForcedWinSolver.Proposal p = best(g.get(), c);
        System.err.println("[labeller-v2] " + tag + " proven=" + (p != null) + (p != null ? " line=" + p.text : ""));
        return p != null;
    }

    // ------------------------------------------------------------------ test 1: off identity

    @Test
    public void offIsTheV1ConfigAndTheRecordedConfigIsUnchanged() {
        final ForcedWinSolver.Config off = cfg(true, true);
        Assert.assertFalse(off.v2());
        final String s = off.toString();
        for (String k : new String[] {"cacPicks", "cacLine", "planScry", "planDiscard", "payRecord", "oppMin"}) {
            Assert.assertFalse(s.contains(k), k + " in " + s);
        }
        // the recorded generator config of an off config carries no v2 key (frozen v1 spec bytes)
        final JsonObject j = HonestGenerator.solverCfgJson(off);
        for (String k : ForcedWinSolver.Config.V2_DEFAULTS.keySet()) {
            Assert.assertFalse(j.has(k), "v2 key " + k + " recorded at its default");
        }
        final ForcedWinSolver.Config bare = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(bare.v2());
        // labellerV2=true turns all five items on; every v2 key refuses a ms budget (counts only)
        final ForcedWinSolver.Config all = ForcedWinSolver.Config.fromProps(k -> "budget".equals(k) ? "counter"
                : "labellerV2".equals(k) ? "true" : "defense".equals(k) ? "false" : null);
        Assert.assertTrue(all.oppMin && all.cacPicks && all.cacLine && all.planScry && all.planDiscard && all.payRecord);
        for (String k : new String[] {"cacPicks", "cacLine", "planScry", "planDiscard", "payRecord", "labellerV2"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> ForcedWinSolver.Config.fromProps(x ->
                    k.equals(x) ? "true" : null));
        }
    }

    // ------------------------------------------------------------------ token layer

    @Test
    public void tokensApplyByNameAndMissWhenAbsent() {
        final Game g = game("Lightning Bolt;Opt;Opt;Mountain", "Island", "Griselbrand;Lightning Bolt;Forest", 20);
        final Player ai = g.getPlayers().get(1);
        final forge.game.card.CardCollection hand = new forge.game.card.CardCollection(ai.getCardsIn(forge.game.zone.ZoneType.Hand));
        final forge.game.card.CardCollection top = new forge.game.card.CardCollection(
                ai.getCardsIn(forge.game.zone.ZoneType.Library).subList(0, 2));
        // a scry token: names kept top first, the rest away; "?" takes what is left
        final var arr = ForcedWinChoices.applyArr("scry:Lightning Bolt|?", top);
        Assert.assertEquals(arr.getLeft().get(0).getName(), "Lightning Bolt");
        Assert.assertEquals(arr.getRight().get(0).getName(), "Griselbrand");
        Assert.assertNull(ForcedWinChoices.applyArr("scry:Lightning Bolt|", top), "sizes must match");
        // a discard token: exactly its names, else no answer (a miss, never a guess)
        Assert.assertEquals(ForcedWinChoices.applyDisc("disc:Mountain;Opt", hand, 2, 2).size(), 2);
        Assert.assertNull(ForcedWinChoices.applyDisc("disc:Griselbrand;Opt", hand, 2, 2));
        Assert.assertNull(ForcedWinChoices.applyPick("pick:Griselbrand", hand));
        Assert.assertNull(ForcedWinChoices.applyPick("pick:-", hand)[0]);
        Assert.assertTrue(ForcedWinChoices.applyShuf("shuf:y"));
        Assert.assertFalse(ForcedWinChoices.applyShuf("shuf:n"));
        // enumerators: the default answer first, distinct by names, capped
        final List<String> d = ForcedWinChoices.discards(hand, 2, new forge.game.card.CardCollection(List.of(hand.get(0), hand.get(1))), 48);
        Assert.assertEquals(d.size(), 4, "Bolt+Opt (the default), Bolt+Mountain, Mountain+Opt, Opt+Opt: " + d);
        Assert.assertEquals(new java.util.HashSet<>(d).size(), d.size());
        final List<String> sc = ForcedWinChoices.arrangements("scry", top, org.apache.commons.lang3.tuple.ImmutablePair.of(
                top, new forge.game.card.CardCollection()), 48);
        Assert.assertEquals(sc.size(), 5, "2 cards: 2 orders on top, 2 one-away, 1 both away: " + sc);
        Assert.assertEquals(ForcedWinChoices.picks(hand, true, hand.get(0), 3).size(), 3, "capped");
        // the cursor: tokens of a kind in order, each used once
        final ForcedWinChoices.Cursor c = new ForcedWinChoices.Cursor(List.of("pick:A", "scry:B|", "pick:C"));
        Assert.assertEquals(c.next("pick"), "pick:A");
        Assert.assertEquals(c.next("pick"), "pick:C");
        Assert.assertNull(c.next("pick"));
        Assert.assertEquals(c.next("scry"), "scry:B|");
    }

    // ------------------------------------------------------------------ test 2: (a) a low-mana-value tutor target

    /**
     * Demonic Tutor with both Swamps; the kill is the Mountain (mana value 0) then Lightning Bolt. v1's engine tries
     * Forge AI's pick and the 3 highest mana values (Griselbrand, Sphinx, Tarmogoyf); v2 enumerates every name. The
     * planner is starved (the engine's recall is the point; a Swamp in hand keeps Forge AI from fetching a land itself),
     * and their Hill Giant kills us next turn (with no land in hand next turn Forge AI fetches the Mountain itself).
     */
    @Test
    public void engineTriesTheLowManaValueTutorTarget() {
        final java.util.function.Supplier<Game> g = () -> thisTurn("Demonic Tutor;Lightning Bolt;Swamp;Exhume", "Swamp;Swamp",
                "Griselbrand;Sphinx of the Steel Wind;Tarmogoyf;Ancestral Recall;Mountain;Forest;Forest;Forest", 3);
        final boolean v1 = proven(g, cfg(false, true), "tutor v1");
        final boolean v2 = proven(g, cfg(false, true, "cacPicks", "cacLine"), "tutor v2");
        Assert.assertTrue(v2, "v2's engine finds Demonic Tutor -> Mountain -> Bolt");
        Assert.assertFalse(v1, "v1's engine never tries the mana-value-0 Mountain");
    }

    // ------------------------------------------------------------------ test 3: (a)+(b) scry, Ponder order

    /** Preordain: scry Griselbrand and the Forest to the bottom, draw the Bolt (third). */
    static Game preordain() {
        return game("Preordain;Exhume", "Island;Mountain", "Griselbrand;Forest;Lightning Bolt;Forest;Forest;Forest", 3);
    }

    /** The line's scry token sends cards away (review S2-3: a bottom, not a reorder). */
    static void assertScryAway(ForcedWinSolver.Proposal p) {
        Assert.assertNotNull(p);
        Assert.assertTrue(java.util.regex.Pattern.compile("scry:[^|\\]]*\\|[^\\],\\]]+").matcher(p.text).find(),
                "a scry with cards sent away: " + p.text);
    }

    @Test
    public void aScryToTheBottomIsVerifiedAsRecorded() {
        final boolean v1 = proven(LabellerV2ChoicesTest::preordain, cfg(true, true), "preordain v1");
        final boolean v2 = proven(LabellerV2ChoicesTest::preordain, cfg(true, true, "cacPicks", "cacLine"), "preordain v2 engine");
        Assert.assertFalse(v1, "v1 verifies every scry as all-on-top: the line draws Griselbrand");
        Assert.assertTrue(v2, "v2's engine records its scry and the play-out answers it as recorded");
        assertScryAway(best(preordain(), cfg(true, true, "cacPicks", "cacLine")));
    }

    /** Ponder: the Bolt is second of the three looked at; the line puts it on top (v1's play-out keeps the order). */
    static Game ponder() {
        return game("Ponder;Exhume", "Island;Mountain", "Forest;Lightning Bolt;Forest;Forest;Forest;Forest", 3);
    }

    @Test
    public void aPonderOrderIsVerifiedAsRecorded() {
        final boolean v1 = proven(LabellerV2ChoicesTest::ponder, cfg(true, true), "ponder v1");
        final boolean v2 = proven(LabellerV2ChoicesTest::ponder, cfg(true, true, "cacPicks", "cacLine"), "ponder v2");
        Assert.assertFalse(v1, "v1's play-out keeps a Ponder's order: it draws the Forest");
        Assert.assertTrue(v2, "v2's line puts the Bolt on top and the play-out follows it");
    }

    // ------------------------------------------------------------------ test 4: (c) planner scry and discard

    @Test
    public void thePlannerFindsAScryBottomWin() {
        final boolean v1 = proven(LabellerV2ChoicesTest::preordain, cfg(true, false), "planner scry v1");
        final boolean v2 = proven(LabellerV2ChoicesTest::preordain, cfg(true, false, "planScry", "cacLine"), "planner scry v2");
        Assert.assertFalse(v1, "v1's planner reads Preordain as draw 1 (scry not modelled)");
        Assert.assertTrue(v2, "v2's planner bottoms Griselbrand, draws the Bolt; the play-out scries as recorded");
        assertScryAway(best(preordain(), cfg(true, false, "planScry", "cacLine")));
    }

    /**
     * Careful Study draws the Bolt and the Mountain, then discards two: leastUseful throws away Griselbrand and then the
     * Mountain (a land ranks below a draw spell; Concentrate is uncastable here), so the Bolt has no red mana. Keeping
     * the Mountain (discard Griselbrand and a Concentrate) wins. The engine is off (the planner's recall is the point).
     */
    static Game study() {
        return game("Careful Study;Concentrate;Concentrate;Griselbrand", "Island",
                "Lightning Bolt;Mountain;Forest;Forest;Forest;Forest", 3);
    }

    @Test
    public void thePlannerBranchesADiscard() {
        final boolean v1 = proven(LabellerV2ChoicesTest::study, cfg(true, false), "planner discard v1");
        final boolean v2 = proven(LabellerV2ChoicesTest::study, cfg(true, false, "planDiscard", "cacLine"), "planner discard v2");
        Assert.assertFalse(v1, "v1's planner discards the Mountain (leastUseful)");
        Assert.assertTrue(v2, "v2's planner keeps the Mountain");
    }

    // ------------------------------------------------------------------ test 6: (e) payment

    /**
     * Impulse ({1}{U}) finds the Bolt (R). Lands in order Island, Mountain, Island: the greedy payment pays Impulse's U
     * with the first Island and its generic with the Mountain (least flexible first, battlefield order on ties), so the
     * Bolt has no red mana. A remainder that keeps the Mountain untapped wins; the line floats exactly those sources.
     * The engine is off (the planner's payment is the point).
     */
    static Game impulse() {
        return game("Impulse;Exhume", "Island;Mountain;Island", "Lightning Bolt;Forest;Forest;Forest;Forest;Forest", 3);
    }

    @Test
    public void thePlannerKeepsTheRightSourceUntapped() {
        final boolean v1 = proven(LabellerV2ChoicesTest::impulse, cfg(true, false), "pay planner v1");
        final boolean v2 = proven(LabellerV2ChoicesTest::impulse, cfg(true, false, "payRecord"), "pay planner v2");
        Assert.assertFalse(v1, "v1's greedy payment taps the Mountain for Impulse's generic");
        Assert.assertTrue(v2, "v2 branches the remainder and keeps the Mountain for the Bolt");
    }

    // ------------------------------------------------------------------ review S2-3: hidden library, belief worlds

    /**
     * Not exact: 4 belief worlds, hideOwnLibrary, our library shuffled in each world except the top cards the seat
     * placed (notePlaced, as OwnLibraryMemory hands the label solver).
     */
    static ForcedWinSolver.Proposal bestHidden(Game g, int knownTop, String... v2) {
        final Map<String, String> m = new HashMap<>();
        m.put("budget", "counter");
        m.put("defense", "false");
        m.put("act", "false");
        m.put("horizon", "1");
        m.put("nodes", "3000");
        m.put("worlds", "4");
        m.put("hideOwnLibrary", "true");
        m.put("engineNodes", "200");
        m.put("engineSteps", "6000");
        for (String k : v2) {
            m.put(k, "true");
        }
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(m::get);
        final Player ai = g.getPlayers().get(1);
        g.AI_TIMEOUT = 600;
        final ForcedWinSolver s = new ForcedWinSolver(ai, c);
        s.notePlaced(ai.getCardsIn(forge.game.zone.ZoneType.Library).subList(0, knownTop));
        for (ForcedWinSolver.Proposal p : s.propose(g)) {
            if (p.proven(4)) {
                return p;
            }
        }
        return null;
    }

    @Test
    public void hiddenLibraryScryLinesInBeliefWorlds() {
        final ForcedWinSolver.Proposal v1 = bestHidden(preordain(), 3);
        final ForcedWinSolver.Proposal v2 = bestHidden(preordain(), 3, "labellerV2");
        System.err.println("[labeller-v2] hidden preordain v1=" + (v1 != null) + " v2=" + (v2 == null ? null : v2.text));
        Assert.assertNull(v1, "v1 verifies the scry as all-on-top");
        assertScryAway(v2);
        Assert.assertNotNull(bestHidden(preordain(), 3, "planScry", "cacLine"), "(c)+(b) alone, in belief worlds");
    }

    @Test
    public void hiddenLibraryDiscardLineInBeliefWorlds() {
        Assert.assertNull(bestHidden(study(), 2), "v1's planner discards the Mountain");
        Assert.assertNotNull(bestHidden(study(), 2, "planDiscard", "cacLine"), "v2 keeps the Mountain in every world");
    }

    // ------------------------------------------------------------------ review S2-3: Ponder's shuffle bit

    @Test
    public void aRecordedShuffleIsAnsweredAsRecorded() {
        final Game g = ponder();
        final Player ai = g.getPlayers().get(1);
        final ForcedWinPlanner.Step st = ForcedWinPlanner.Step.engine(ForcedWinModel.AbilKind.ENGINE, 1, "Ponder", "", "", true);
        st.cacPlayed = new java.util.ArrayList<>(List.of("shuf:y"));
        final ForcedWinPlanner.Line line = new ForcedWinPlanner.Line(new java.util.ArrayList<>(List.of(st)), List.of(), 0, 0,
                0, 0, List.of(), "test");
        for (boolean on : new boolean[] {true, false}) {
            final ForcedWinSolver.Follower f = new ForcedWinSolver.Follower(line);
            f.cacLine = on;
            f.choices = new ForcedWinChoices.Cursor(st.cacPlayed);
            final ForcedWinSolver.LineAi ai2 = new ForcedWinSolver.LineAi(g, ai, ai.getController().getLobbyPlayer(), f);
            Assert.assertEquals(ai2.confirmAction(null, null, "Shuffle your library?", null, null, null), on,
                    "cacLine " + on + ": the recorded shuffle bit, else v1's never-shuffle");
        }
    }

    // ------------------------------------------------------------------ review S2-3: payment variants in the engine

    /**
     * Impulse ({1}{U}) finds Psionic Blast ({2}{U}, 4 damage): Forge AI pays Impulse with both Islands (it pays generic
     * with a same-colour source), leaving no blue for the Blast; the engine's payment variant pays with an Island and a
     * Mountain.
     */
    static Game blast() {
        return thisTurn("Impulse;Exhume", "Island;Island;Mountain;Mountain;Mountain", "Psionic Blast;Forest;Forest;Forest;Forest",
                4);
    }

    @Test
    public void engineFindsThePaymentVariant() {
        final boolean v1 = proven(LabellerV2ChoicesTest::blast, cfg(false, true), "blast engine v1");
        final ForcedWinSolver.Proposal v2 = best(blast(), cfg(false, true, "payRecord"));
        System.err.println("[labeller-v2] blast engine v2=" + (v2 == null ? null : v2.text));
        Assert.assertFalse(v1, "Forge AI's payment taps both Islands");
        Assert.assertNotNull(v2, "a payment variant keeps an Island");
        Assert.assertTrue(v2.text.contains(" pay["), "the line floats its recorded sources: " + v2.text);
    }

    /**
     * Faithless Looting must discard Highway Robber (Exhume returns it; its drain is the last 2). v2's line records the
     * discard and the play-out replays it. v1 is reported: with Exhume in hand Forge AI's own discard already throws
     * the creature away (Gray Merchant and Inferno Titan behaved the same), so the planner case (Careful Study) is the
     * discard-recall test.
     */
    @Test
    public void engineDiscardsTheReanimationTarget() {
        final java.util.function.Supplier<Game> g = () -> game("Faithless Looting;Exhume;Highway Robber;Island",
                "Mountain;Swamp;Swamp", "Forest;Forest;Forest;Forest;Forest;Forest", 2);
        final boolean v1 = proven(g, cfg(false, true), "merchant v1");
        final boolean v2 = proven(g, cfg(false, true, "cacPicks", "cacLine"), "merchant v2");
        Assert.assertTrue(v2, "v2 loots the Robber away and Exhumes it");
        final ForcedWinSolver.Proposal p = best(g.get(), cfg(false, true, "cacPicks", "cacLine"));
        Assert.assertTrue(p.text.contains("disc:Forest;Highway Robber"), "the discard is recorded: " + p.text);
    }

    // ------------------------------------------------------------------ test 7: determinism

    @Test
    public void sameSeedSameLabel() {
        final ForcedWinSolver.Proposal a = best(preordain(), cfg(true, true, "labellerV2"));
        final ForcedWinSolver.Proposal b = best(preordain(), cfg(true, true, "labellerV2"));
        Assert.assertNotNull(a);
        Assert.assertEquals(a.text, b.text);
        // across JVMs: this line is compared between two separate runs of the class (review S2-3)
        System.err.println("[determinism] " + Integer.toHexString(a.text.hashCode()) + " " + a.text.length());
    }
}
