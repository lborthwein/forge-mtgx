package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane dd-expert-1110 / planner-mana-1110 (solverCfg planLands; off by default). Two defects of the width model's land
 * mana, found by hand review of the owner's Doomsday games (runs/2026-10-23-backward-value-walk/lanes/dd-expert-1110):
 * <ol>
 * <li>A land dropped during the plan was read with the mana of its template (its printed abilities, as learned at the
 * card's first sight). Under a continuous effect that changes what lands tap for, the dropped land, and the lands the
 * drop itself changes, tap for other colours in the state reached. sourceColorsLive re-reads only what is already on the
 * battlefield.</li>
 * <li>An ability that puts a land from our library onto the battlefield had no effect in the model: no mana source, no
 * library change. Such a land was a land drop that made nothing.</li>
 * </ol>
 * Cards appear only here, as fixtures. The three long frames are real positions of r4-cand-1108 set F (own first
 * main-phase priority, before the land drop).
 */
public class PlanLandsTest extends forge.ai.AITest {

    private static Map<String, String> cfg(final boolean on) {
        final Map<String, String> m = new HashMap<>();
        m.put("sourceColorsLive", "true");
        m.put("sourceOutput", "true");
        if (on) {
            m.put("planLands", "true");
        }
        return m;
    }

    /** The planner as the solver calls it: this turn, then with one pass; greedy payment, then the payment search. */
    private static ForcedWinPlanner.Result plan(final ForcedWinModel.Position pos, final int horizon, final boolean on) {
        ForcedWinPlanner.Result res = null;
        for (int pay : new int[] {1, ForcedWinPlanner.PAY_POLICIES}) {
            for (int h = 1; h <= horizon; h++) {
                final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
                lim.horizon = h;
                lim.payPolicies = pay;
                lim.planLands = on; // as ForcedWinSolver#limits sets it from the key
                res = ForcedWinPlanner.plan(pos, lim);
                if (!res.lines.isEmpty()) {
                    return res;
                }
            }
        }
        return res;
    }

    private static ForcedWinModel.Position read(final String frame, final int seat, final boolean on) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(seat);
        final ForcedWinSolver s = new ForcedWinSolver(me, ForcedWinSolver.Config.fromProps(cfg(on)::get));
        return s.readPosition(g, s.knownTop(g));
    }

    private static ForcedWinPlanner.Result plan(final String tag, final String frame, final int seat, final boolean on,
            final int horizon) {
        final long t0 = System.nanoTime();
        final ForcedWinModel.Position pos = read(frame, seat, on);
        final long t1 = System.nanoTime();
        final ForcedWinPlanner.Result res = plan(pos, horizon, on);
        System.err.println("[plan-lands-test] " + tag + " planLands=" + on + " readMs=" + (t1 - t0) / 1_000_000 + " planMs="
                + (System.nanoTime() - t1) / 1_000_000 + " " + res.summary()
                + (res.lines.isEmpty() ? "" : " best=" + res.lines.get(0)));
        return res;
    }

    private static String frame(final String hand, final String battlefield, final String library, final int theirLife) {
        return ForcedWinSolverTest.frame(hand, battlefield, 20, "", "Forest", theirLife, "human").replace(
                "humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
    }

    // ------------------------------------------------------------------ the key

    @Test
    public void theKeyIsOffByDefault() {
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).planLands);
        Assert.assertTrue(ForcedWinSolver.Config.fromProps(Map.of("planLands", "true")::get).planLands);
        Assert.assertFalse(HonestGenerator.solverCfgJson(ForcedWinSolver.Config.fromProps(k -> null)).has("planLands"));
        Assert.assertTrue(HonestGenerator.solverCfgJson(ForcedWinSolver.Config.fromProps(Map.of("planLands", "true")::get))
                .has("planLands"));
        // the screens' key is a second one, off by default too, and a recorded config names it only when it is on
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(Map.of("planLands", "true")::get).planLandsScreens);
        Assert.assertFalse(HonestGenerator.solverCfgJson(ForcedWinSolver.Config.fromProps(Map.of("planLands", "true")::get))
                .has("planLandsScreens"));
        Assert.assertTrue(HonestGenerator.solverCfgJson(ForcedWinSolver.Config.fromProps(
                Map.of("planLands", "true", "planLandsScreens", "true")::get)).has("planLandsScreens"));
    }

    // ------------------------------------------------------------------ defect 1: a land drop under a continuous effect

    /** The only untapped source after the drop is the dropped land; its B exists only through the effect in play. */
    @Test
    public void aDroppedLandTapsForWhatItMakesInTheStateReached() {
        final String f = frame("Island;Bump in the Night", "Urborg, Tomb of Yawgmoth|Tapped", ForcedWinSolverTest.OURLIB, 3);
        Assert.assertTrue(plan("drop-under-effect", f, 0, false, 1).lines.isEmpty(), "off: the dropped land is U only");
        final ForcedWinPlanner.Result on = plan("drop-under-effect", f, 0, true, 1);
        Assert.assertFalse(on.lines.isEmpty(), "on: land, B from it, the drain: " + on.summary());
        Assert.assertTrue(on.sameTurn);
    }

    /** The dropped land carries the effect: it taps for B itself, and so do the lands that were already there. */
    @Test
    public void droppingTheEffectChangesTheOtherSources() {
        final String f = frame("Urborg, Tomb of Yawgmoth;Tendrils of Agony", "Island;Island;Island", ForcedWinSolverTest.OURLIB, 2);
        Assert.assertTrue(plan("drop-the-effect", f, 0, false, 1).lines.isEmpty(), "off: no B at all");
        final ForcedWinPlanner.Result on = plan("drop-the-effect", f, 0, true, 1);
        Assert.assertFalse(on.lines.isEmpty(), "on: BB from the dropped land and one it changed: " + on.summary());
    }

    /** Without the effect nothing changes: an Island is an Island. */
    @Test
    public void aDropWithNoEffectInPlayIsWhatItWas() {
        final String f = frame("Island;Bump in the Night", "Island|Tapped", ForcedWinSolverTest.OURLIB, 3);
        Assert.assertTrue(plan("drop-plain", f, 0, false, 1).lines.isEmpty());
        Assert.assertTrue(plan("drop-plain", f, 0, true, 1).lines.isEmpty(), "on: still no B");
    }

    // ------------------------------------------------------------------ defect 2: a land that finds a land

    @Test
    public void aFetchedLandIsASourceAndItsCostIsPaid() {
        final String f = frame("Marsh Flats;Bump in the Night", "", "Island;Swamp;Island;Island", 3);
        Assert.assertTrue(plan("fetch", f, 0, false, 2).lines.isEmpty(), "off: the fetch is a land that makes nothing");
        final ForcedWinPlanner.Result on = plan("fetch", f, 0, true, 1);
        Assert.assertFalse(on.lines.isEmpty(), "on: land, fetch a Swamp, B, the drain: " + on.summary());
        Assert.assertTrue(on.sameTurn);
        Assert.assertEquals(on.lines.get(0).lifeLeft, 19, "the fetch's life is paid: " + on.lines.get(0));
        final List<Integer> picks = new ArrayList<>();
        for (ForcedWinPlanner.Step st : on.lines.get(0).steps) {
            picks.addAll(st.picks);
        }
        Assert.assertEquals(picks.size(), 1, "the line names the land it fetches: " + on.lines.get(0));
    }

    @Test
    public void aFetchWithNothingToFindMakesNothing() {
        final String f = frame("Marsh Flats;Bump in the Night", "", "Island;Island;Island;Island", 3);
        Assert.assertTrue(plan("fetch-none", f, 0, true, 2).lines.isEmpty(), "no Plains or Swamp card in the library");
    }

    /** The fetched land's entry is the engine's: one that enters tapped gives its mana on our next turn. */
    @Test
    public void aFetchedLandThatEntersTappedWaitsATurn() {
        final String f = frame("Marsh Flats;Bump in the Night", "", "Island;Raffine's Tower;Island;Island", 3);
        Assert.assertTrue(plan("fetch-tapped", f, 0, true, 1).lines.isEmpty(), "this turn: it enters tapped");
        final ForcedWinPlanner.Result next = plan("fetch-tapped", f, 0, true, 2);
        Assert.assertFalse(next.lines.isEmpty(), "next turn: " + next.summary());
        Assert.assertTrue(next.nextTurn);
    }

    /**
     * A land that may enter untapped for a price has two entries, whatever Forge AI would choose in its own sandbox: the
     * plan takes the paid one when it needs the mana now (and the follower pays it), the unpaid one when it cannot pay.
     */
    @Test
    public void aFetchedLandEntersPaidOrUnpaid() {
        final String lib = "Island;Overgrown Tomb;Island;Island";
        final String rich = ForcedWinSolverTest.frame("Misty Rainforest;Bump in the Night", "", 20, "", "Forest", 3, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib);
        final ForcedWinPlanner.Result now = plan("fetch-paid", rich, 0, true, 1);
        Assert.assertFalse(now.lines.isEmpty(), now.summary());
        Assert.assertEquals(now.lines.get(0).lifeLeft, 17, "1 for the fetch, 2 for the entry: " + now.lines.get(0));
        Assert.assertTrue(now.lines.get(0).toString().contains("paid["), now.lines.get(0).toString());
        // at 3 life the paid entry would kill us: the land enters tapped and the drain waits a turn
        final String poor = ForcedWinSolverTest.frame("Misty Rainforest;Bump in the Night", "", 3, "", "Forest", 3, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib);
        Assert.assertTrue(plan("fetch-unpaid", poor, 0, true, 1).lines.isEmpty(), "no paid entry at 3 life");
        final ForcedWinPlanner.Result next = plan("fetch-unpaid", poor, 0, true, 2);
        Assert.assertFalse(next.lines.isEmpty(), next.summary());
        Assert.assertEquals(next.lines.get(0).lifeLeft, 2, next.lines.get(0).toString());
        Assert.assertTrue(next.lines.get(0).toString().contains("unpaid["), next.lines.get(0).toString());
        // and the solver's own game copies agree with the paid entry (the follower pays; Forge AI alone need not)
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", rich);
        final Map<String, String> m = cfg(true);
        m.put("hideOwnLibrary", "true");
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(m::get));
        Assert.assertTrue(plannedWinThisTurn(s.propose(g)), "the paid entry is played in a game copy");
    }

    @Test
    public void theFetchAbilityIsLearnedOnlyWithTheKey() {
        for (boolean on : new boolean[] {false, true}) {
            final ForcedWinModel.Position pos = read(frame("Marsh Flats", "", "Island;Swamp;Island;Island", 20), 0, on);
            boolean fetch = false;
            for (ACard a : pos.hand) {
                for (Abil ab : a.onBattlefield) {
                    fetch |= ab.has(ForcedWinModel.EffKind.LAND_FETCH);
                }
            }
            Assert.assertEquals(fetch, on, pos.describe());
        }
    }

    // ------------------------------------------------------------------ the real positions

    /** r4-cand-1108 set F pair-021 L2 and L3, seat 0, turn 9: Doomsday needs B from the Island in hand (Urborg in play). */
    static final String PAIR_021_T9 = """
            [state]
            turn=9
            activeplayer=p0
            activephase=MAIN1
            p0life=20
            p0landsplayed=0
            p0hand=Jace, the Mind Sculptor;Memory Jar;Doomsday;Echo of Eons;Timetwister;Waterlogged Grove;Island
            p0library=Beseech the Mirror;Misty Rainforest;Swamp;Darkslick Shores;Bolas's Citadel;Gush;Thassa's Oracle;Library of Alexandria;Swamp;Jace, Wielder of Mysteries;Island;Scalding Tarn;Xander's Lounge;Collective Brutality;Baleful Strix;Wishclaw Talisman;Lotus Petal;Verdant Catacombs;Chart a Course;Swamp;Troll of Khazad-dûm;Island;Cabal Ritual;Mind Stone;Stern Scolding;Overgrown Tomb;Chromatic Star;Counterspell
            p0graveyard=Daze;Mystical Tutor;Remand
            p0battlefield=Raffine's Tower;Urborg, Tomb of Yawgmoth
            p0exile=
            p1life=19
            p1landsplayed=0
            p1hand=Gitaxian Probe;Consider;Daze;Kaito Shizuki
            p1library=Gush;Emry, Lurker of the Loch;Chart a Course;Island;Lotus Petal;Island;Jace, Vryn's Prodigy;Island;Brain Freeze;Swamp;Raffine's Tower;Spellseeker;Botanical Sanctum;Swamp;Vampiric Tutor;Bazaar of Baghdad;Remand;Doomsday;Mox Emerald;Brainstorm;Swamp;Library of Alexandria;Urborg, Tomb of Yawgmoth;Chromatic Star;Jace, the Mind Sculptor;Cabal Ritual;Mishra's Bauble;Echo of Eons;Thassa's Oracle
            p1graveyard=Marsh Flats;Dark Ritual;Liliana of the Veil;Troll of Khazad-dûm
            p1battlefield=Bayou|Tapped;Tolarian Academy|Tapped;Underground Sea|Tapped
            p1exile=
            """;

    /** pair-021 L1, seat 1, turn 16: Urborg in hand; Doomsday and the 2-drop need four B from lands that make two. */
    static final String PAIR_021_L1_T16 = """
            [state]
            turn=16
            activeplayer=p1
            activephase=MAIN1
            p0life=16
            p0landsplayed=0
            p0hand=Spellseeker;Echo of Eons;Emry, Lurker of the Loch;Brainstorm
            p0library=Island;Swamp;Remand;Bayou;Brain Freeze;Jace, Vryn's Prodigy;Botanical Sanctum;Raffine's Tower;Kaito Shizuki;Cabal Ritual;Doomsday;Gush;Bazaar of Baghdad;Liliana of the Veil;Consider;Marsh Flats;Swamp;Island
            p0graveyard=Vampiric Tutor;Mishra's Bauble;Swamp;Chart a Course;Dark Ritual;Gitaxian Probe;Daze;Lotus Petal
            p0battlefield=Underground Sea|Tapped;Mox Emerald|Tapped;Chromatic Star;Thassa's Oracle|Tapped;Library of Alexandria|Tapped;Jace, the Mind Sculptor|Counters:LOYALTY=2;Tolarian Academy|Tapped;Wishclaw Talisman|Owner:P1;Urborg, Tomb of Yawgmoth|Tapped;Island|Tapped;Troll of Khazad-dûm|SummonSick
            p0exile=
            p1life=17
            p1landsplayed=0
            p1hand=Troll of Khazad-dûm;Urborg, Tomb of Yawgmoth;Baleful Strix;Doomsday;Remand
            p1library=Cabal Ritual;Waterlogged Grove;Swamp;Stern Scolding;Gush;Bolas's Citadel;Swamp;Beseech the Mirror;Verdant Catacombs;Thassa's Oracle;Chart a Course;Memory Jar;Darkslick Shores;Counterspell;Echo of Eons;Jace, Wielder of Mysteries;Misty Rainforest;Chromatic Star;Overgrown Tomb;Island
            p1graveyard=Lotus Petal;Mystical Tutor;Timetwister;Daze;Collective Brutality;Scalding Tarn;Jace, the Mind Sculptor;Mind Stone
            p1battlefield=Xander's Lounge;Swamp|Tapped;Raffine's Tower;Island;Island;Library of Alexandria
            p1exile=
            """;

    /** pair-035 L1 and L3, seat 1, turn 4: the third B is a land a fetch land finds; next turn another fetch gives the second U. */
    static final String PAIR_035_T4 = """
            [state]
            turn=4
            activeplayer=p1
            activephase=MAIN1
            p0life=20
            p0landsplayed=0
            p0hand=Torsten, Founder of Benalia;Tireless Tracker;Thragtusk;Forest;Plains
            p0library=Vorinclex;Ramunap Excavator;Tear Asunder;Marsh Flats;Forest;Mishra's Workshop;Boseiju, Who Endures;Forest;Bomat Courier;Misty Rainforest;Swords to Plowshares;Utopia Sprawl;Finale of Devastation;Esika's Chariot;Plains;Elvish Reclaimer;Titan of Industry;Oath of Druids;Knight of Autumn;Karn, Scion of Urza;Oracle of Mul Daya;White Plume Adventurer;Crop Rotation;Triplicate Titan;Old One Eye;Forest;Sol Ring;Forest;Indatha Triome;Natural Order;Forest;Forest
            p0graveyard=Paradise Druid
            p0battlefield=Forest|Tapped;Forest|Tapped
            p0exile=
            p1life=20
            p1landsplayed=0
            p1hand=Doomsday;Timetwister;Scalding Tarn;Jace, Wielder of Mysteries;Misty Rainforest;Thassa's Oracle
            p1library=Counterspell;Beseech the Mirror;Jace, the Mind Sculptor;Cabal Ritual;Baleful Strix;Chromatic Star;Raffine's Tower;Chart a Course;Bolas's Citadel;Daze;Library of Alexandria;Mystical Tutor;Remand;Swamp;Wishclaw Talisman;Collective Brutality;Urborg, Tomb of Yawgmoth;Waterlogged Grove;Island;Xander's Lounge;Memory Jar;Mind Stone;Swamp;Echo of Eons;Gush;Troll of Khazad-dûm;Island;Island;Verdant Catacombs;Swamp;Overgrown Tomb
            p1graveyard=Stern Scolding
            p1battlefield=Darkslick Shores;Lotus Petal
            p1exile=
            """;

    @Test
    public void pair021Turn9HasANextTurnLine() {
        Assert.assertTrue(plan("pair-021-T9", PAIR_021_T9, 0, false, 2).lines.isEmpty(), "off: BB and U, no Doomsday");
        final ForcedWinPlanner.Result on = plan("pair-021-T9", PAIR_021_T9, 0, true, 2);
        Assert.assertFalse(on.lines.isEmpty(), "on: " + on.summary());
    }

    @Test
    public void pair021L1Turn16HasASameTurnLine() {
        Assert.assertTrue(plan("pair-021-L1-T16", PAIR_021_L1_T16, 1, false, 1).lines.isEmpty(), "off: two B sources");
        final ForcedWinPlanner.Result on = plan("pair-021-L1-T16", PAIR_021_L1_T16, 1, true, 1);
        Assert.assertFalse(on.lines.isEmpty(), "on: " + on.summary());
        Assert.assertTrue(on.sameTurn);
    }

    @Test
    public void pair035Turn4HasALine() {
        Assert.assertTrue(plan("pair-035-T4", PAIR_035_T4, 1, false, 2).lines.isEmpty(), "off: two mana");
        final ForcedWinPlanner.Result on = plan("pair-035-T4", PAIR_035_T4, 1, true, 2);
        Assert.assertFalse(on.lines.isEmpty(), "on: " + on.summary());
    }

    // ------------------------------------------------------------------ the other reviewed positions (no assertion on the key)

    /** pair-025 L3, seat 1, turn 16: a same-turn line by hand; not a land-mana position. */
    static final String PAIR_025_T16 = """
            [state]
            turn=16
            activeplayer=p1
            activephase=MAIN1
            monarch=p0
            p0life=20
            p0landsplayed=0
            p0hand=Oust;Plains
            p0library=Serra Paragon;Prismatic Ending;Intrepid Adversary;Adeline, Resplendent Cathar;Seasoned Dungeoneer;Plains;Flickerwisp;Arid Mesa;Plains;Winds of Abandon;Plains;Wall of Omens;Plains;Eagles of the North;Spell Queller;Tundra;Lingering Souls;Sol Ring;Night Scythe;Samwise the Stouthearted;Daze;Plains;Timeless Dragon
            p0graveyard=Reprieve;Gideon, Ally of Zendikar;Hero of Bladehold;The Wandering Emperor
            p0battlefield=Island|Tapped;Plains|Tapped;Plains|Tapped;Plains|Tapped;Plains|Tapped;T:w_2_2_samurai_vigilance;Plains|Tapped;T:w_2_2_samurai_vigilance;Plains|Tapped;Golos, Tireless Pilgrim|Tapped;Karakas|Tapped;T:w_2_2_samurai_vigilance|SummonSick;Ranger-Captain of Eos|SummonSick;Palace Jailer|SummonSick
            p0exile=
            p1life=17
            p1landsplayed=0
            p1hand=Island;Doomsday;Emry, Lurker of the Loch;Consider
            p1library=Urborg, Tomb of Yawgmoth;Cabal Ritual;Swamp;Swamp;Dark Ritual;Mox Emerald;Gush;Thassa's Oracle;Bazaar of Baghdad;Library of Alexandria;Gitaxian Probe;Jace, Vryn's Prodigy;Remand;Brain Freeze;Chart a Course;Echo of Eons;Swamp
            p1graveyard=Marsh Flats;Mishra's Bauble;Brainstorm;Troll of Khazad-dûm;Daze;Vampiric Tutor;Liliana of the Veil;Botanical Sanctum;Lotus Petal;Spellseeker;Jace, the Mind Sculptor;Raffine's Tower;Kaito Shizuki
            p1battlefield=Bayou;Chromatic Star;Underground Sea;Island;Tolarian Academy;Island
            p1exile=
            """;

    /** pair-026 L3, seat 1, turn 13: a next-turn line by hand; not a land-mana position. */
    static final String PAIR_026_T13 = """
            [state]
            turn=13
            activeplayer=p1
            activephase=MAIN1
            monarch=p0
            p0life=20
            p0landsplayed=0
            p0hand=Boon-Bringer Valkyrie;Tough Cookie;Parallax Wave;Titania, Protector of Argoth
            p0library=Cathar Commando;Plains;Winds of Abandon;Forest;Skyclave Apparition;Oath of Druids;Jetmir's Garden;Eagles of the North;Umezawa's Jitte;Forest;Thragtusk;Forest;Undermountain Adventurer;Solitude;Elite Spellbinder;Forest;Flickerwisp;Plains;Forest;Woodfall Primus;Plains;Savannah;Path to Exile;Plains;Indatha Triome;Torsten, Founder of Benalia;Old One Eye
            p0graveyard=Plains;Seasoned Dungeoneer;Endurance;Palace Jailer|Id:38
            p0battlefield=Temple Garden;Forest|Tapped;Plains|Tapped;Plains|Tapped;Plains|Tapped
            p0exile=
            p1life=18
            p1landsplayed=0
            p1hand=Swamp;Underground Sea;Doomsday
            p1library=Island;Swamp;Gitaxian Probe;Jace, Vryn's Prodigy;Spellseeker;Cabal Ritual;Dark Ritual;Consider;Mishra's Bauble;Lotus Petal;Kaito Shizuki;Marsh Flats;Thassa's Oracle;Tolarian Academy;Island;Jace, the Mind Sculptor;Library of Alexandria;Island;Urborg, Tomb of Yawgmoth;Gush
            p1graveyard=Vampiric Tutor;Echo of Eons;Daze;Brain Freeze;Chromatic Star;Chart a Course;Bazaar of Baghdad;Mox Emerald;Brainstorm;Remand;Troll of Khazad-dûm
            p1battlefield=Raffine's Tower;Bayou;Botanical Sanctum;Liliana of the Veil|Counters:LOYALTY=3;Swamp
            p1exile=Emry, Lurker of the Loch|Id:51|ExiledWith:38
            """;

    /** pair-037 L0, seat 0, turn 7: a next-turn line by hand. */
    static final String PAIR_037_T7 = """
            [state]
            turn=7
            activeplayer=p0
            activephase=MAIN1
            p0life=17
            p0landsplayed=0
            p0hand=Verdant Catacombs;Swamp;Doomsday;Island;Scalding Tarn;Jace, the Mind Sculptor
            p0library=Swamp;Collective Brutality;Mind Stone;Cabal Ritual;Gush;Beseech the Mirror;Echo of Eons;Remand;Counterspell;Island;Baleful Strix;Mystical Tutor;Wishclaw Talisman;Lotus Petal;Misty Rainforest;Memory Jar;Urborg, Tomb of Yawgmoth;Darkslick Shores;Thassa's Oracle;Daze;Jace, Wielder of Mysteries;Raffine's Tower;Swamp;Bolas's Citadel;Stern Scolding;Library of Alexandria;Chart a Course
            p0graveyard=Timetwister;Xander's Lounge;Troll of Khazad-dûm
            p0battlefield=Waterlogged Grove;Chromatic Star;Island;Overgrown Tomb
            p0exile=
            p1life=19
            p1landsplayed=0
            p1hand=Saheeli, Sublime Artificer;Duress;Reanimate;Phyrexian Metamorph;Crabomination;Snuff Out
            p1library=Murderous Cut;Swamp;Swamp;Inquisition of Kozilek;Island;Island;Island;Tamiyo, Inquisitive Student;Thieving Skydiver;Fallen Shinobi;Watery Grave;Enduring Curiosity;Island;Swamp;Ancient Tomb;Island;Spell Pierce;Brazen Borrower;Stock Up;Liliana of the Veil;Karn, Scion of Urza;Ancestral Recall;Faerie Mastermind;Demonic Tutor;Swamp;Baleful Strix;Raucous Theater;Underground Sea;Valki, God of Lies
            p1graveyard=Hymn to Tourach;Prismatic Vista
            p1battlefield=Swamp|Tapped;Swamp|Tapped;Island
            p1exile=
            """;

    /** pair-037 L0, seat 0, turn 9: a same-turn line by hand; the game's solver found lines here at 83 % and kept the default. */
    static final String PAIR_037_T9 = """
            [state]
            turn=9
            activeplayer=p0
            activephase=MAIN1
            p0life=16
            p0landsplayed=0
            p0hand=Verdant Catacombs;Doomsday;Scalding Tarn;Mind Stone;Island
            p0library=Swamp;Cabal Ritual;Gush;Beseech the Mirror;Echo of Eons;Remand;Counterspell;Island;Baleful Strix;Mystical Tutor;Wishclaw Talisman;Lotus Petal;Misty Rainforest;Memory Jar;Urborg, Tomb of Yawgmoth;Darkslick Shores;Thassa's Oracle;Daze;Jace, Wielder of Mysteries;Raffine's Tower;Swamp;Bolas's Citadel;Stern Scolding;Library of Alexandria;Chart a Course
            p0graveyard=Timetwister;Xander's Lounge;Collective Brutality
            p0battlefield=Waterlogged Grove;Chromatic Star;Island;Overgrown Tomb;Swamp;Jace, the Mind Sculptor|Counters:LOYALTY=3
            p0exile=
            p1life=13
            p1landsplayed=0
            p1hand=Saheeli, Sublime Artificer;Phyrexian Metamorph;Crabomination;Snuff Out;Murderous Cut
            p1library=Swamp;Swamp;Inquisition of Kozilek;Island;Island;Island;Tamiyo, Inquisitive Student;Thieving Skydiver;Fallen Shinobi;Watery Grave;Enduring Curiosity;Island;Swamp;Ancient Tomb;Island;Spell Pierce;Brazen Borrower;Stock Up;Liliana of the Veil;Karn, Scion of Urza;Ancestral Recall;Faerie Mastermind;Demonic Tutor;Swamp;Baleful Strix;Raucous Theater;Underground Sea;Valki, God of Lies
            p1graveyard=Hymn to Tourach;Prismatic Vista;Reanimate;Duress
            p1battlefield=Swamp|Tapped;Swamp|Tapped;Island;Troll of Khazad-dûm|Owner:P0|SummonSick
            p1exile=
            """;

    // the two other legs of the same pairs that differ from the fixtures above: pair-021 L2 (the same own position, another
    // opponent hand) and pair-037 L2 (another land in hand). pair-035 L1, turn 4, is the pair-035 fixture card for card.

    static final String PAIR_021_L2_T9 = """
            [state]
            turn=9
            activeplayer=p0
            activephase=MAIN1
            p0life=20
            p0landsplayed=0
            p0hand=Jace, the Mind Sculptor;Memory Jar;Doomsday;Echo of Eons;Timetwister;Waterlogged Grove;Island
            p0library=Beseech the Mirror;Misty Rainforest;Swamp;Darkslick Shores;Bolas's Citadel;Gush;Thassa's Oracle;Library of Alexandria;Swamp;Jace, Wielder of Mysteries;Island;Scalding Tarn;Xander's Lounge;Collective Brutality;Baleful Strix;Wishclaw Talisman;Lotus Petal;Verdant Catacombs;Chart a Course;Swamp;Troll of Khazad-dûm;Island;Cabal Ritual;Mind Stone;Stern Scolding;Overgrown Tomb;Chromatic Star;Counterspell
            p0graveyard=Daze;Mystical Tutor;Remand
            p0battlefield=Raffine's Tower;Urborg, Tomb of Yawgmoth
            p0exile=
            p1life=19
            p1landsplayed=0
            p1hand=Gitaxian Probe;Consider;Swamp;Kaito Shizuki
            p1library=Urborg, Tomb of Yawgmoth;Swamp;Gush;Vampiric Tutor;Spellseeker;Doomsday;Echo of Eons;Jace, the Mind Sculptor;Mox Emerald;Mishra's Bauble;Brainstorm;Cabal Ritual;Bazaar of Baghdad;Botanical Sanctum;Daze;Island;Jace, Vryn's Prodigy;Swamp;Chromatic Star;Remand;Island;Brain Freeze;Library of Alexandria;Island;Chart a Course;Emry, Lurker of the Loch;Lotus Petal;Thassa's Oracle;Raffine's Tower
            p1graveyard=Marsh Flats;Dark Ritual;Liliana of the Veil;Troll of Khazad-dûm
            p1battlefield=Bayou|Tapped;Tolarian Academy|Tapped;Underground Sea|Tapped
            p1exile=
            """;

    static final String PAIR_037_L2_T7 = """
            [state]
            turn=7
            activeplayer=p0
            activephase=MAIN1
            p0life=17
            p0landsplayed=0
            p0hand=Verdant Catacombs;Xander's Lounge;Swamp;Doomsday;Island;Jace, the Mind Sculptor
            p0library=Swamp;Collective Brutality;Mind Stone;Cabal Ritual;Gush;Beseech the Mirror;Echo of Eons;Remand;Counterspell;Island;Baleful Strix;Mystical Tutor;Wishclaw Talisman;Lotus Petal;Misty Rainforest;Memory Jar;Urborg, Tomb of Yawgmoth;Darkslick Shores;Thassa's Oracle;Daze;Jace, Wielder of Mysteries;Raffine's Tower;Swamp;Bolas's Citadel;Stern Scolding;Library of Alexandria;Chart a Course
            p0graveyard=Timetwister;Scalding Tarn;Troll of Khazad-dûm
            p0battlefield=Waterlogged Grove;Chromatic Star;Island;Overgrown Tomb
            p0exile=
            p1life=19
            p1landsplayed=0
            p1hand=Saheeli, Sublime Artificer;Duress;Reanimate;Phyrexian Metamorph;Crabomination;Snuff Out
            p1library=Inquisition of Kozilek;Fallen Shinobi;Murderous Cut;Karn, Scion of Urza;Baleful Strix;Liliana of the Veil;Valki, God of Lies;Ancestral Recall;Swamp;Faerie Mastermind;Swamp;Brazen Borrower;Tamiyo, Inquisitive Student;Spell Pierce;Island;Island;Stock Up;Raucous Theater;Underground Sea;Swamp;Swamp;Watery Grave;Demonic Tutor;Island;Island;Island;Thieving Skydiver;Enduring Curiosity;Ancient Tomb
            p1graveyard=Prismatic Vista;Hymn to Tourach
            p1battlefield=Swamp|Tapped;Swamp|Tapped;Island
            p1exile=
            """;

    /** The key never takes a line away: where the planner has one with it off, it has one with it on. */
    @Test
    public void theOtherReviewedPositionsKeepTheirLines() {
        final Object[][] cases = {{"pair-025-T16", PAIR_025_T16, 1}, {"pair-026-T13", PAIR_026_T13, 1},
                {"pair-037-T7", PAIR_037_T7, 0}, {"pair-037-T9", PAIR_037_T9, 0}};
        for (Object[] c : cases) {
            final ForcedWinPlanner.Result off = plan((String) c[0], (String) c[1], (Integer) c[2], false, 2);
            final ForcedWinPlanner.Result on = plan((String) c[0], (String) c[1], (Integer) c[2], true, 2);
            Assert.assertTrue(off.lines.isEmpty() || !on.lines.isEmpty(), c[0] + " off=" + off.summary() + " on=" + on.summary());
        }
    }

    // ------------------------------------------------------------------ the solver: the lines are verified in game copies

    /** The ugold4 spec's plans.solverCfg (integrate-1110 kit), with planLands on or off. */
    private static Map<String, String> ugold4(final boolean on) {
        final Map<String, String> m = new HashMap<>();
        for (String kv : ("budgetMs=6000,copyBackFace=true,cycles=true,engineAfterPlanFail=true,engineLongLoops=true,"
                + "engineLoopBisect=true,engineLoopHoist=true,engineLoopReach=true,engineMoves=true,engineMs=4000,"
                + "engineNodesPerSearch=true,engineOpen=true,engineRerun=true,engineTriggerTargets=true,engineVerify=true,"
                + "evalReserveMs=1500,followPay=true,hideOwnLibrary=true,historyLki=true,lineUncapped=true,loopMarginal=true,"
                + "offBattlefieldLimits=true,paySearch=true,planDiscardWall=true,planExpandCap=true,readerPerGame=true,"
                + "sourceColorsLive=true,sourceOutput=true,stackAbilityCopies=true,stackHistory=true,stalePlayerStatics=true,"
                + "staleStatics=true,turnLimits=true,unknownWorlds=true,verifyReplan=true").split(",")) {
            m.put(kv.split("=")[0], kv.split("=")[1]);
        }
        if (on) {
            m.put("planLands", "true");
        }
        return m;
    }

    /** The solver of the last {@link #propose} call (its report counters). */
    private static ForcedWinSolver last;

    private static List<ForcedWinSolver.Proposal> propose(final String tag, final String frame, final int seat, final boolean on) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(seat), ForcedWinSolver.Config.fromProps(ugold4(on)::get));
        last = s;
        final long t0 = System.nanoTime();
        final List<ForcedWinSolver.Proposal> p = s.propose(g);
        System.err.println("[plan-lands-test] propose " + tag + " planLands=" + on + " ms=" + (System.nanoTime() - t0) / 1_000_000
                + " proposals=" + p.size() + " probes=" + s.reader.probeStats.summary() + " plannerNodes=" + s.meter.plannerNodes
                + " landsRetries=" + s.landsRetries + "/" + s.landsRetryFound + "/" + s.landsRetryWins + " retryNodes="
                + s.landsRetryNodes + " copies=" + s.copies);
        for (ForcedWinSolver.Proposal x : p) {
            System.err.println("[plan-lands-test]   " + tag + " unopposed=" + x.pUnopposed + " belief=" + x.pBelief + " worlds="
                    + x.worldsRun + " " + x.text);
        }
        return p;
    }

    /** A width-search line (not the engine search's) that won unopposed in the solver's own game copies, this turn. */
    private static boolean plannedWinThisTurn(final List<ForcedWinSolver.Proposal> ps) {
        for (ForcedWinSolver.Proposal x : ps) {
            if (x.pUnopposed >= 1.0 && x.winTurn == 0 && !x.engine) {
                return true;
            }
        }
        return false;
    }

    /** The follower plays the dropped land for the colour the plan read, and the engine agrees: the line wins in a game copy. */
    @Test
    public void theSolverPlaysTheDropLineInAGameCopy() {
        final String f = frame("Island;Bump in the Night", "Urborg, Tomb of Yawgmoth|Tapped", ForcedWinSolverTest.OURLIB, 3);
        Assert.assertFalse(plannedWinThisTurn(propose("drop-under-effect", f, 0, false)), "off: next turn at best");
        Assert.assertTrue(plannedWinThisTurn(propose("drop-under-effect", f, 0, true)));
    }

    /**
     * The follower activates the fetch, the pick is the line's land, and the engine agrees. (Off, the engine search finds
     * this small position by itself; the width search does not.) The engine search is turned off here to see the
     * planner's own line played.
     */
    @Test
    public void theSolverPlaysTheFetchLineInAGameCopy() {
        final String f = frame("Marsh Flats;Bump in the Night", "", "Island;Swamp;Island;Island", 3);
        for (boolean on : new boolean[] {false, true}) {
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", f);
            final Map<String, String> m = cfg(on);
            m.put("hideOwnLibrary", "true");
            final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(m::get));
            final List<ForcedWinSolver.Proposal> ps = s.propose(g);
            for (ForcedWinSolver.Proposal x : ps) {
                System.err.println("[plan-lands-test]   fetch-follow planLands=" + on + " unopposed=" + x.pUnopposed + " belief="
                        + x.pBelief + " engine=" + x.engine + " " + x.text);
            }
            Assert.assertEquals(plannedWinThisTurn(ps), on, "planLands=" + on);
        }
    }

    /**
     * pair-037 L0, turn 7: the plain search has lines by our next turn and every one of them fails in its game copy, so
     * the second search (the land plans) never ran and the solver proposed nothing. With the key the solver reads the
     * land plans once after that failure: the line through the fetched land wins its play-out.
     */
    @Test
    public void whenNoPlainLineWinsItsPlayOutTheLandPlansAreReadOnce() {
        final List<ForcedWinSolver.Proposal> off = propose("pair-037-T7 retry", PAIR_037_T7, 0, false);
        Assert.assertEquals(last.landsRetries, 0, "off: no second search");
        for (ForcedWinSolver.Proposal x : off) {
            Assert.assertTrue(x.pUnopposed < 1.0 || x.engine, "off: no planner line wins: " + x.text);
        }
        final List<ForcedWinSolver.Proposal> on = propose("pair-037-T7 retry", PAIR_037_T7, 0, true);
        Assert.assertEquals(last.landsRetries, 1, "on: one second search");
        Assert.assertEquals(last.landsRetryWins, 1);
        boolean won = false;
        for (ForcedWinSolver.Proposal x : on) {
            won |= x.pUnopposed >= 1.0 && !x.engine && x.winTurn == 1 && x.text.contains("ACTIVATE:Verdant Catacombs pick[");
        }
        Assert.assertTrue(won, "on: the line through the fetched land wins unopposed");
    }

    /** The real positions through the whole solver (planner, then its own play-outs): report only. */
    @Test
    public void theRealPositionsThroughTheSolver() {
        final Object[][] cases = {{"pair-021-T9", PAIR_021_T9, 0}, {"pair-021-L1-T16", PAIR_021_L1_T16, 1},
                {"pair-035-T4", PAIR_035_T4, 1}, {"pair-025-T16", PAIR_025_T16, 1}, {"pair-026-T13", PAIR_026_T13, 1},
                {"pair-037-T7", PAIR_037_T7, 0}, {"pair-037-T9", PAIR_037_T9, 0}, {"pair-021-L2-T9", PAIR_021_L2_T9, 0},
                {"pair-037-L2-T7", PAIR_037_L2_T7, 0}};
        for (Object[] c : cases) {
            for (boolean on : new boolean[] {false, true}) {
                propose((String) c[0], (String) c[1], (Integer) c[2], on);
            }
        }
    }

    // ------------------------------------------------------------------ the planner-only screens (planLandsScreens)

    private static final Object[][] REAL = {{"pair-021-T9", PAIR_021_T9, 0}, {"pair-021-L1-T16", PAIR_021_L1_T16, 1},
            {"pair-035-T4", PAIR_035_T4, 1}, {"pair-025-T16", PAIR_025_T16, 1}, {"pair-026-T13", PAIR_026_T13, 1},
            {"pair-037-T7", PAIR_037_T7, 0}, {"pair-037-T9", PAIR_037_T9, 0}};

    /**
     * The three screens a search calls (the plain screen, the reach over the next draw, the goldfish with the ugold4
     * spec's sizes; their time caps opened so the node caps decide), as text without the times. mode 0 = no key,
     * 1 = planLands, 2 = planLands and planLandsScreens.
     */
    private static String[] screens(final String tag, final String frame, final int seat, final int mode) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Map<String, String> m = ugold4(mode >= 1);
        if (mode >= 2) {
            m.put("planLandsScreens", "true");
        }
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(seat), ForcedWinSolver.Config.fromProps(m::get));
        final List<Integer> top = s.knownTop(g);
        s.readPosition(g, top); // the templates are learned before the clock starts
        final String[] out = new String[3];
        final long t0 = System.nanoTime();
        final ForcedWinSolver.Screen sc = s.screen(g, top, 2000, 5000);
        out[0] = "screen lines=" + sc.lines + " nodes=" + sc.nodes + " turn=" + sc.bestTurn + " best=" + sc.best;
        final long t1 = System.nanoTime();
        final ForcedWinSolver.ReachOpts ro = new ForcedWinSolver.ReachOpts();
        ro.nodes = 2000;
        ro.ms = 5000;
        ro.maxNames = 40;
        ro.nameNodes = 1000;
        ro.nameOnePass = true;
        ro.skipCapped = true;
        ro.sig = true;
        ro.gamma = 0.9;
        final ForcedWinSolver.Reach r = s.reach(g, top, ro);
        out[1] = "reach p=" + r.p + " pd=" + r.pd + " direct=" + r.direct + " screens=" + r.screens + " nodes=" + r.nodes
                + " capped=" + r.s0Capped + "/" + r.nameCapped + " skipped=" + r.skipped;
        final long t2 = System.nanoTime();
        final ForcedWinSolver.GoldfishOpts go = new ForcedWinSolver.GoldfishOpts();
        go.ms = 5000;
        final ForcedWinSolver.Goldfish gf = s.goldfish(g, top, go);
        out[2] = "goldfish g=" + gf.g + " direct=" + gf.direct + " turn=" + gf.bestTurn + " screens=" + gf.screens + " nodes="
                + gf.nodes + " best=" + gf.best;
        final long t3 = System.nanoTime();
        System.err.println("[plan-lands-test] screens " + tag + " mode=" + mode + " screenMs=" + (t1 - t0) / 1_000_000.0 + " reachMs="
                + (t2 - t1) / 1_000_000.0 + " goldfishMs=" + (t3 - t2) / 1_000_000.0);
        for (String o : out) {
            System.err.println("[plan-lands-test]   " + tag + " mode=" + mode + " " + (o.length() > 420 ? o.substring(0, 420) : o));
        }
        return out;
    }

    private static int turnOf(final String screen) {
        final int i = screen.indexOf(" turn=") + 6;
        return Integer.parseInt(screen.substring(i, screen.indexOf(' ', i)));
    }

    /** planLands alone does not reach a screen: on every real position the three screens are what they are without it. */
    @Test
    public void theScreensAreUnchangedWithoutTheirOwnKey() {
        for (Object[] c : REAL) {
            final String[] off = screens((String) c[0], (String) c[1], (Integer) c[2], 0);
            final String[] on = screens((String) c[0], (String) c[1], (Integer) c[2], 1);
            Assert.assertEquals(on, off, (String) c[0]);
        }
    }

    /**
     * With planLandsScreens the screens read the land plans: where a screen had a line it keeps it (the first search is
     * the old one), and the position whose only line needs the fetched land's mana now has one in the plain screen.
     */
    @Test
    public void theScreensWithTheirKeyReadTheLandPlans() {
        for (Object[] c : REAL) {
            final String[] off = screens((String) c[0], (String) c[1], (Integer) c[2], 0);
            final String[] on = screens((String) c[0], (String) c[1], (Integer) c[2], 2);
            if (!off[0].contains("lines=0")) {
                // a screen with a line keeps one, never a later one (a same-turn line through a land plan may come first)
                Assert.assertFalse(on[0].contains("lines=0"), c[0] + " " + on[0]);
                Assert.assertTrue(turnOf(on[0]) <= turnOf(off[0]), c[0] + " " + on[0] + " / " + off[0]);
            }
            if ("pair-035-T4".equals(c[0])) {
                Assert.assertTrue(off[0].contains("lines=0"), off[0]);
                Assert.assertFalse(on[0].contains("lines=0"), on[0]);
            }
        }
    }

    // ------------------------------------------------------------------ the second search is inside the solver's budget

    /** A second search starts only while time is left for it: past its deadline the plain search's answer stands. */
    @Test
    public void theSecondSearchDoesNotStartPastItsDeadline() {
        final ForcedWinModel.Position pos = read(PAIR_035_T4, 1, true);
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 2;
        lim.planLands = true;
        final ForcedWinPlanner.Result open = ForcedWinPlanner.plan(pos, lim);
        Assert.assertTrue(open.landsPass && !open.landsSkipped && !open.lines.isEmpty(), open.summary());
        lim.landsDeadline = forge.game.WorkClock.nanoTime() - 1; // nothing left
        final ForcedWinPlanner.Result shut = ForcedWinPlanner.plan(pos, lim);
        Assert.assertTrue(shut.landsSkipped && !shut.landsPass && shut.lines.isEmpty(), shut.summary());
        lim.planLands = false;
        Assert.assertEquals(shut.nodes, ForcedWinPlanner.plan(pos, lim).nodes, "the plain search alone");
    }

    /**
     * The solver gives a second search what is left of its own budget before the evaluation's reserve: budgetMs less
     * evalReserveMs from the offense's start, and no later than the decision's hard deadline less that reserve. With
     * nothing left it does not run and the solver proposes what it proposes without the key.
     */
    @Test
    public void theSolverBoundsTheSecondSearchByItsBudget() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", PAIR_035_T4);
        final ForcedWinSolver open = new ForcedWinSolver(g0.getPlayers().get(1), ForcedWinSolver.Config.fromProps(ugold4(true)::get));
        final long t0 = 1_000_000_000L;
        Assert.assertEquals(open.landsDeadline(t0), t0 + (forge.ai.WallClock.budgetMs(6000) - 1500) * 1_000_000L,
                "budgetMs 6000 less evalReserveMs 1500 from the offense's start");
        open.setHardDeadline(t0 + 2_000L * 1_000_000L);
        Assert.assertEquals(open.landsDeadline(t0), t0 + 500L * 1_000_000L, "the decision's hard deadline less the reserve");
        open.setHardDeadline(0L);
        Assert.assertTrue(plannedWinNextTurn(open.propose(g0)), "budget open: the line through the fetched land");
        Assert.assertEquals(open.landsSkips, 0);

        // the solver's budget equals the reserve: nothing is left for a second search
        final Map<String, String> m = ugold4(true);
        m.put("budgetMs", "1500");
        final Game g1 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", PAIR_035_T4);
        final ForcedWinSolver shut = new ForcedWinSolver(g1.getPlayers().get(1), ForcedWinSolver.Config.fromProps(m::get));
        Assert.assertFalse(plannedWinNextTurn(shut.propose(g1)), "no budget: as without the key");
        Assert.assertTrue(shut.landsSkips > 0, "the second searches were skipped: " + shut.summary());

        // the decision's hard deadline counts too (PlanSearch sets it with plans.unified): less than the reserve is left
        final Game g2 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", PAIR_037_T7);
        final ForcedWinSolver hard = new ForcedWinSolver(g2.getPlayers().get(0), ForcedWinSolver.Config.fromProps(ugold4(true)::get));
        hard.setHardDeadline(forge.game.WorkClock.nanoTime() + 1_000L * 1_000_000L);
        hard.propose(g2);
        hard.setHardDeadline(0L);
        Assert.assertEquals(hard.landsRetries, 0, "no second search after the failed lines: " + hard.summary());
        Assert.assertTrue(hard.landsSkips > 0, hard.summary());
    }

    private static boolean plannedWinNextTurn(final List<ForcedWinSolver.Proposal> ps) {
        for (ForcedWinSolver.Proposal x : ps) {
            if (x.pUnopposed >= 1.0 && x.winTurn == 1 && !x.engine) {
                return true;
            }
        }
        return false;
    }

    /** The read's work-clock weights (reference nanoseconds per unit of each counted site), for the test below. */
    private static final String READ_WEIGHTS = "mainLoopStep:5000,checkStateEffects:1589000,makeCopy:1,copyCard:184996,"
            + "copyLeakWalk:200,canPlayAndPayFor:762901,evaluateBoardPosition:894742,getPossibleBlockers:346318,"
            + "serviceRequest:67779073,serviceItem:17775284,plannerNode:34963,engineNode:50000,oppMinNode:100000";

    /**
     * Inside a decision. A search with plans.unified sets the solver's hard deadline to the decision's start plus the
     * decision's budget (PlanSearch#propose), on the work clock when the decision is charged in work units. Here that
     * clock is installed with a read's weights and the deadline is 8 s from the call, as a read's is. With the key on or
     * off the solver's call ends at that deadline (the tail is the copy or search step in progress), on a position where
     * the key finds unproven lines, on one where it finds none at the planner's node cap, and on the retry.
     */
    @Test
    public void insideADecisionTheSolverEndsAtTheDecisionsDeadline() {
        forge.game.WorkClock.setWeights(forge.game.WorkClock.parseWeights(READ_WEIGHTS));
        try {
            final Object[][] cases = {{"pair-021-T9", PAIR_021_T9, 0}, {"pair-026-T13", PAIR_026_T13, 1}, {"pair-037-T7", PAIR_037_T7, 0}};
            for (Object[] c : cases) {
                for (boolean on : new boolean[] {false, true}) {
                    final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", (String) c[1]);
                    final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get((Integer) c[2]), ForcedWinSolver.Config.fromProps(ugold4(on)::get));
                    final forge.game.WorkClock.Clock clock = forge.game.WorkClock.newClock(1.0);
                    final forge.game.WorkClock.Clock prev = forge.game.WorkClock.install(clock);
                    try {
                        s.readPosition(g, s.knownTop(g)); // a game's first read learns the templates; not this decision's
                        final long t0 = forge.game.WorkClock.nanoTime();
                        s.setHardDeadline(t0 + 8_000L * 1_000_000L);
                        final List<ForcedWinSolver.Proposal> ps = s.propose(g);
                        s.setHardDeadline(0L);
                        final double ms = (forge.game.WorkClock.nanoTime() - t0) / 1e6;
                        System.err.println("[plan-lands-test] in-decision " + c[0] + " planLands=" + on + " workMs=" + Math.round(ms)
                                + " proposals=" + ps.size() + " plannerNodes=" + s.meter.plannerNodes + " " + s.summary());
                        Assert.assertTrue(ms <= 8_000 + 250, c[0] + " planLands=" + on + ": " + ms + " ms of work clock");
                    } finally {
                        forge.game.WorkClock.install(prev);
                    }
                }
            }
        } finally {
            forge.game.WorkClock.setWeights(null);
        }
    }

    /** Cost of the key at a read with every template already learned: one sandbox drop per distinct land name in hand. */
    @Test
    public void theCostOfARead() {
        final Object[][] cases = {{"pair-021-T9", PAIR_021_T9, 0}, {"pair-021-L1-T16", PAIR_021_L1_T16, 1}, {"pair-035-T4", PAIR_035_T4, 1}};
        for (Object[] c : cases) {
            for (boolean on : new boolean[] {false, true}) {
                final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", (String) c[1]);
                final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get((Integer) c[2]), ForcedWinSolver.Config.fromProps(cfg(on)::get));
                s.readPosition(g, s.knownTop(g)); // cold: learns the templates
                final int probes0 = s.reader.probeStats.probes;
                final long t0 = System.nanoTime();
                final int n = 20;
                for (int i = 0; i < n; i++) {
                    s.readPosition(g, s.knownTop(g));
                }
                System.err.println("[plan-lands-test] read-cost " + c[0] + " planLands=" + on + " warmReadMs="
                        + (System.nanoTime() - t0) / 1_000_000.0 / n + " probesPerRead=" + (s.reader.probeStats.probes - probes0) / (double) n);
            }
        }
    }
}
