package forge.ai.simulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * sdcc-multitarget-loop-1119 (lane solver-distill-cc): solver key engineLoopLethalFirst. The position is a recorded
 * pilot game's frame (KIKI-DECK game 8576882226995152249, decision 137, the pilot's first main phase of global turn 6),
 * as a TEST of the engine search. Card facts are test data only; nothing here reaches a pilot or a label.
 *
 * <p>The pilot has a copy loop in play (a legendary creature that copies a nonlegendary creature, the copy untaps the
 * source, the copies are hasty) and a second creature the copier could target. The engine's root lists both targets and
 * its loop detector finds the right body; the chunked fast-forward stops at the count whose attackers are lethal past
 * blockers. With engineLoopHoist on, the search hoisted the loop from the root BEFORE searching that node, and the hoist's
 * doubling outlet searches spent the budget first: 0 lines. With the key, the lethal node is searched first (the hoist
 * runs after if that finds nothing), and a hoisted fast-forward stops at the lethal count too.</p>
 *
 * <p>Frames: the recorded one (key on: proven; off: no line), the second target removed (proven either way), the
 * opponent at 4 life (proven either way), at 20 life (proven with the key: the count is sized to the kill, not capped),
 * and with one untapped opposing creature (proven with the key, the count covering the block). Two more variants (the
 * copied creature not summoning sick; the other untapper as the copied creature) only check that the key loses no
 * proven line.</p>
 */
public class LoopLethalFirstTest extends forge.ai.AITest {

    /** The recorded frame (the pilot is "human", seat 0). */
    static final String FRAME = String.join("\n",
            "[state]",
            "turn=6",
            "activeplayer=human",
            "activephase=MAIN1",
            "humanlife=20",
            "ailife=18",
            "humanlandsplayed=0",
            "ailandsplayed=0",
            "aihand=Misty Rainforest;Mishra's Workshop;Thragtusk;White Plume Adventurer",
            "ailibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Bomat Courier;Titan of Industry;"
                    + "Elvish Reclaimer;Tireless Tracker;Triplicate Titan;Old One Eye;Finale of Devastation;Knight of Autumn;"
                    + "Plains;Torsten, Founder of Benalia;Utopia Sprawl;Sol Ring;Tear Asunder;Esika's Chariot;Vorinclex;"
                    + "Ramunap Excavator;Karn, Scion of Urza;Natural Order;Swords to Plowshares;Oracle of Mul Daya",
            "aigraveyard=Marsh Flats;Plains;Crop Rotation",
            "aiexile=",
            "aibattlefield=Indatha Triome|Tapped;Paradise Druid|Tapped;Boseiju, Who Endures|Tapped;Oath of Druids",
            "humanhand=Ancient Tomb;Pestermite;Mana Leak;Faithless Looting;Recruiter of the Guard;Mountain",
            "humanbattlefield=Tundra;Mox Ruby;Mana Crypt|Tapped;Karakas;Imperial Recruiter;Kiki-Jiki, Mirror Breaker;"
                    + "Deceiver Exarch|SummonSick",
            "humangraveyard=Ancestral Recall;Black Lotus",
            "humanexile=",
            "humanlibrary=Splinter Twin;Frantic Search;Mountain;Mountain;Mox Diamond;Enlightened Tutor;Goblin Engineer;"
                    + "Island;Island;Mox Sapphire;Restoration Angel;Miscalculation;Memory Lapse;Plateau;Mox Pearl;The One Ring;"
                    + "Arid Mesa;Steam Vents;Scalding Tarn;Seething Song;Volcanic Island;Sacred Foundry;Zealous Conscripts;"
                    + "Lotus Petal;Raugrin Triome",
            "");

    private static String with(final String from, final String to) {
        Assert.assertTrue(FRAME.contains(from), from);
        return FRAME.replace(from, to);
    }

    /** The live FB solver config (FB.json 86b175322b5d), searching only. */
    static JsonObject fb(final boolean lethalFirst) {
        final JsonObject o = new JsonObject();
        o.addProperty("budgetMs", "6000");
        o.addProperty("engineMs", "4000");
        o.addProperty("evalReserveMs", "1500");
        o.addProperty("planOrder", "7");
        for (String k : new String[] {"breakOnCounter", "copyBackFace", "costTax", "cycles", "drawGrowth", "engineAfterPlanFail",
                "engineLongLoops", "engineLoopBisect", "engineLoopHoist", "engineLoopReach", "engineModeChoice", "engineMoves",
                "engineNodesPerSearch", "engineOpen", "engineRerun", "engineTriggerTargets", "engineVerify", "fetchThin",
                "followPay", "grantSacMana", "hideOwnLibrary", "historyLki", "landEntersProbe", "lineUncapped", "loopMarginal",
                "offBattlefieldLimits", "passBlockers", "payKeepUse", "paySearch", "pileTutorCast", "planDiscardWall",
                "planExpandCap", "planLoops", "probeSettle", "protectUntilNext", "readerPerGame", "sourceColorsLive",
                "sourceLeaves", "sourceOutput", "stackAbilityCopies", "stackHistory", "stalePlayerStatics", "staleStatics",
                "turnLimits", "tutorCast", "unknownWorlds", "verifyReplan"}) {
            o.addProperty(k, "true");
        }
        if (lethalFirst) {
            o.addProperty("engineLoopLethalFirst", "true");
        }
        o.addProperty("act", "false");
        o.addProperty("defense", "false");
        return o;
    }

    private static String cut(final Object o, final int n) {
        final String s = String.valueOf(o).replaceAll("\\s+", " ");
        return s.substring(0, Math.min(n, s.length()));
    }

    /** -1: no line; 0: lines, none proven; 1: a proven line. */
    private static int propose(final String name, final String frame, final boolean on) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(fb(on)));
        final long t0 = System.nanoTime();
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        final boolean proven = best != null && best.proven(1);
        System.out.println("LOOPLETHAL " + name + " key=" + (on ? "on" : "off") + " ms=" + (System.nanoTime() - t0) / 1_000_000
                + " lines=" + props.size() + (best == null ? " none" : " proven=" + proven + " | " + cut(best.text, 200))
                + " || " + s.summary());
        return best == null ? -1 : proven ? 1 : 0;
    }

    @Test
    public void lethalForwardSearchedBeforeHoist() {
        final Map<String, String> frames = new LinkedHashMap<>();
        frames.put("recorded", FRAME);
        frames.put("norecruiter", with("Karakas;Imperial Recruiter;", "Karakas;"));
        frames.put("life4", with("ailife=18", "ailife=4"));
        frames.put("life20", with("ailife=18", "ailife=20"));
        frames.put("blocker", with("Paradise Druid|Tapped", "Paradise Druid"));
        frames.put("nosick", with("Deceiver Exarch|SummonSick", "Deceiver Exarch"));
        frames.put("pestermite", with("Ancient Tomb;Pestermite;", "Ancient Tomb;Deceiver Exarch;")
                .replace("Deceiver Exarch|SummonSick", "Pestermite|SummonSick"));
        final Map<String, int[]> got = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : frames.entrySet()) {
            got.put(e.getKey(), new int[] {propose(e.getKey(), e.getValue(), false), propose(e.getKey(), e.getValue(), true)});
        }
        final StringBuilder bad = new StringBuilder();
        check(bad, "recorded: key on proves", got.get("recorded")[1] == 1);
        check(bad, "recorded: key off has no line", got.get("recorded")[0] == -1);
        check(bad, "norecruiter: proven, key off", got.get("norecruiter")[0] == 1);
        check(bad, "norecruiter: proven, key on", got.get("norecruiter")[1] == 1);
        check(bad, "life4: proven, key off", got.get("life4")[0] == 1);
        check(bad, "life4: proven, key on", got.get("life4")[1] == 1);
        check(bad, "life20: key on proves", got.get("life20")[1] == 1);
        check(bad, "blocker: key on proves", got.get("blocker")[1] == 1);
        for (Map.Entry<String, int[]> e : got.entrySet()) {
            check(bad, e.getKey() + ": no proven line lost", e.getValue()[0] != 1 || e.getValue()[1] == 1);
        }
        Assert.assertEquals(bad.toString(), "", "engineLoopLethalFirst");
    }

    private static void check(final StringBuilder bad, final String what, final boolean ok) {
        System.out.println("LOOPLETHAL check " + (ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            bad.append(what).append("; ");
        }
    }
}
