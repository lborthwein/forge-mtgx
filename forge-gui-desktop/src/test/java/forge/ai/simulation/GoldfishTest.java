package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * solver-distill-cc-1109 (GOLDFISH): the planner over several of our turns. The later turns' cards come from the
 * land-drop relaxation (one extra land a turn: the union of the library's lands) or from sampled orders of our unknown library. The
 * frames are card facts used as tests only (Thassa's Oracle wins when our library is at most our devotion to blue;
 * Doomsday piles five cards; Gush returns two Islands and draws two).
 */
public class GoldfishTest extends forge.ai.AITest {

    private static ForcedWinSolver.GoldfishOpts opts(final int horizon, final int active, final boolean lands,
            final int samples, final int nodes) {
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.horizon = horizon;
        o.active = active;
        o.lands = lands;
        o.samples = samples;
        o.nodes = nodes;
        o.ms = -1L;
        o.seed = 7L;
        return o;
    }

    private static ForcedWinSolver.Goldfish fish(final String battlefield, final String hand, final String library,
            final boolean topKnown, final ForcedWinSolver.GoldfishOpts o) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final List<Integer> top = topKnown ? List.of(me.getCardsIn(ZoneType.Library).get(0).getId()) : s.knownTop(g);
        final ForcedWinSolver.Goldfish r = s.goldfish(g, top, o);
        Assert.assertFalse(r.failed, library);
        System.out.println("GOLDFISH h=" + o.horizon + " a=" + o.active + " lands=" + o.lands + " samples=" + r.samples
                + " screens=" + r.screens + " nodes=" + r.nodes + " capped=" + r.capped + " granted=" + r.granted + " g=" + r.g
                + " p=" + r.p + " turn=" + r.bestTurn + " direct=" + r.direct + " | "
                + library.substring(0, Math.min(40, library.length())) + " | " + r.best);
        return r;
    }

    /** Two Islands, an empty hand: only a drawn Thassa's Oracle wins, on the turn our library is at most two cards. */
    @Test
    public void sampledDrawsOverSeveralTurns() {
        final ForcedWinSolver.Goldfish none = fish("Island;Island", "", "Island;Island;Island;Island", false,
                opts(3, 3, false, 4, 3000));
        Assert.assertEquals(none.g, 0.0, 1e-9);
        Assert.assertEquals(none.p, 0.0, 1e-9);
        // two cards: the Oracle is drawn next turn (one card left) or the turn after (none left): every order wins
        final ForcedWinSolver.Goldfish two = fish("Island;Island", "", "Thassa's Oracle;Island", false,
                opts(3, 3, false, 4, 3000));
        Assert.assertEquals(two.p, 1.0, 1e-9);
        Assert.assertTrue(two.g >= 0.64 - 1e-9 && two.g <= 0.8 + 1e-9, "between gamma^2 and gamma: " + two.g);
        Assert.assertFalse(two.direct);
        // the same seed plans the same draws
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, opts(3, 3, false, 4, 3000)).g, two.g,
                1e-12);
        // one turn of horizon: this turn only, nothing to cast
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, opts(1, 1, false, 4, 3000)).g, 0.0,
                1e-9);
        // no sample and no relaxation: the unknown draws are blanks
        Assert.assertEquals(fish("Island;Island", "", "Thassa's Oracle;Island", false, opts(3, 3, false, 0, 3000)).g, 0.0,
                1e-9);
    }

    /**
     * A tutored Doomsday on top with the mana in play: drawn next turn, cast, and the pile wins the turn after (Gush for
     * its two Islands with their mana floating, the Oracle on a two-card library). Two turns out: nothing by our next
     * turn; three turns find it, and the line needs no granted land (full value).
     */
    @Test
    public void doomsdayFromTheTop() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Island;Island;Island;Island;Swamp;Swamp;Swamp;Swamp;Swamp";
        final String bf = "Underground Sea;Watery Grave;Swamp";
        Assert.assertEquals(fish(bf, "", lib, true, opts(2, 3, true, 0, 20000)).g, 0.0, 1e-9, "not by our next turn");
        final ForcedWinSolver.Goldfish three = fish(bf, "", lib, true, opts(3, 3, true, 0, 20000));
        Assert.assertEquals(three.bestTurn, 2);
        Assert.assertTrue(three.direct, "a line that plays no granted land");
        Assert.assertEquals(three.g, 0.64, 1e-9);
        final ForcedWinSolver.Goldfish menu = fish(bf, "", lib, true, opts(5, 3, true, 0, 1500)); // the menu's knobs
        Assert.assertEquals(menu.g, 0.64, 1e-9);
        Assert.assertEquals(menu.screens, 1, "the first window finds it");
    }

    /** One land short. The relaxation plays a library Swamp (lambda); without it only a sampled Swamp casts Doomsday. */
    @Test
    public void doomsdayNeedsALand() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp";
        final String bf = "Underground Sea;Watery Grave";
        final ForcedWinSolver.Goldfish relaxed = fish(bf, "", lib, true, opts(5, 3, true, 0, 1500));
        Assert.assertEquals(relaxed.bestTurn, 2);
        Assert.assertFalse(relaxed.direct);
        Assert.assertEquals(relaxed.g, 0.6 * 0.64, 1e-9);
        Assert.assertEquals(fish(bf, "", lib, true, opts(5, 3, false, 0, 1500)).g, 0.0, 1e-9, "no land, no line");
        final ForcedWinSolver.Goldfish sampled = fish(bf, "", lib, true, opts(4, 4, false, 6, 6000));
        Assert.assertTrue(sampled.g > 0, "a sampled Swamp casts it");
        Assert.assertEquals(sampled.bestTurn, 3);
    }

    /** The rest of a 40-card Doomsday list below a tutored top card (a turn-one tutor: one land in play, five cards in hand). */
    static final String DECK = "Bayou;Bazaar of Baghdad;Botanical Sanctum;Brain Freeze;Brainstorm;Cabal Ritual;Chromatic Star;"
            + "Consider;Dark Ritual;Echo of Eons;Gush;Island;Island;Island;Jace, the Mind Sculptor;Jace, Vryn's Prodigy;"
            + "Library of Alexandria;Liliana of the Veil;Lotus Petal;Marsh Flats;Mishra's Bauble;Mox Emerald;Raffine's Tower;"
            + "Remand;Spellseeker;Swamp;Swamp;Thassa's Oracle;Tolarian Academy;Troll of Khazad-dûm;Underground Sea;"
            + "Urborg, Tomb of Yawgmoth";

    /**
     * The tutor menu's question on a real list: with Doomsday on top the relaxed goldfish finds a kill inside the menu's
     * node allowance; with a land or a creature on top it finds none.
     */
    @Test
    public void tutorTargetOnARealList() {
        final String hand = "Emry, Lurker of the Loch;Daze;Gitaxian Probe;Kaito Shizuki;Chart a Course";
        final ForcedWinSolver.GoldfishOpts o = opts(5, 3, true, 0, 1500);
        o.maxNodes = 3000;
        final ForcedWinSolver.Goldfish dd = fish("Swamp", hand, "Doomsday;" + DECK, true, o);
        Assert.assertTrue(dd.g > 0, "Doomsday on top: a relaxed line");
        Assert.assertFalse(dd.direct);
        Assert.assertEquals(fish("Swamp", hand, "Tolarian Academy;Doomsday;" + DECK.replace("Tolarian Academy;", ""), true, o).g,
                0.0, 1e-9, "a land on top: Doomsday is an unknown draw");
        Assert.assertEquals(fish("Swamp", hand, "Troll of Khazad-dûm;Doomsday;" + DECK.replace("Troll of Khazad-dûm;", ""), true, o).g,
                0.0, 1e-9);
    }

    private static boolean potential(final String battlefield, final String hand, final String library) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.ms = -1L;
        return s.potential(g, s.knownTop(g), o, 40, true, 40_000L);
    }

    /** The reach gate: a seat has potential when some single library card (with its land drops) makes a line. */
    @Test
    public void potentialIsOneCardFromALine() {
        Assert.assertFalse(potential("Island;Island", "", "Island;Island;Island"), "lands only");
        Assert.assertFalse(potential("Island;Island", "Grizzly Bears", "Island;Balduvian Bears;Island;Forest"), "no line with any card");
        Assert.assertTrue(potential("Island;Island", "", "Island;Thassa's Oracle;Island"), "the Oracle as the next draw");
        Assert.assertTrue(potential("Swamp", "Emry, Lurker of the Loch;Daze;Kaito Shizuki", "Bayou;Doomsday;" + DECK.replace("Bayou;", "")),
                "a Doomsday list with Doomsday somewhere in the library");
    }

    static final String DD1010 = "Baleful Strix;Beseech the Mirror;Bolas's Citadel;Chart a Course;Chromatic Star;Collective Brutality;"
            + "Counterspell;Darkslick Shores;Daze;Doomsday;Echo of Eons;Gush;Island;Island;Island;Jace, Wielder of Mysteries;"
            + "Jace, the Mind Sculptor;Library of Alexandria;Lotus Petal;Memory Jar;Misty Rainforest;Mystical Tutor;Overgrown Tomb;"
            + "Remand;Scalding Tarn;Stern Scolding;Swamp;Swamp;Timetwister;Troll of Khazad-dûm;Waterlogged Grove;Wishclaw Talisman;"
            + "Xander's Lounge";

    /**
     * An opening hand the policy mulliganed in a read (four lands, a ritual, Mind Stone, the Oracle; nothing in play): is a
     * kill within reach, and what does the check cost? (keepGate asks this before the first turn.)
     */
    @Test
    public void openingHandPotential() {
        final String hand = "Verdant Catacombs;Cabal Ritual;Urborg, Tomb of Yawgmoth;Raffine's Tower;Mind Stone;Thassa's Oracle;Swamp";
        final String frame = ForcedWinSolverTest.frame(hand, "", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + DD1010);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        for (long cap : new long[] {12_000L, 40_000L, 120_000L}) {
            final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
            o.ms = -1L;
            o.window = false;
            final long t0 = System.nanoTime();
            final boolean pot = s.potential(g, new java.util.ArrayList<>(), o, 40, true, cap);
            System.out.println("GOLDFISH openingHand potential cap=" + cap + " -> " + pot + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        // Doomsday as the next draw, by hand: where is the line and what does it cost?
        Card dd = null;
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            if (c.getName().equals("Doomsday")) {
                dd = c;
            }
        }
        me.getZone(ZoneType.Library).remove(dd);
        me.getZone(ZoneType.Library).add(dd, 0);
        for (int nodes : new int[] {1500, 6000, 20000}) {
            final ForcedWinSolver.GoldfishOpts o = opts(5, 3, true, 0, nodes);
            o.window = false;
            final ForcedWinSolver.Goldfish r = s.goldfish(g, List.of(dd.getId()), o);
            System.out.println("GOLDFISH openingHand DD-next nodes/screen=" + nodes + " g=" + r.g + " turn=" + r.bestTurn + " screens=" + r.screens
                    + " nodes=" + r.nodes + " capped=" + r.capped + " | " + r.best);
        }
    }

    /**
     * The same hand before the game starts (the mulligan decision): the game's stage is Mulligan, and the planner must
     * still see the kill within reach (its sandboxes play from our first main phase).
     */
    @Test
    public void openingHandPotentialBeforeTheGame() {
        final String hand = "Verdant Catacombs;Cabal Ritual;Urborg, Tomb of Yawgmoth;Raffine's Tower;Mind Stone;Thassa's Oracle;Swamp";
        final String frame = ForcedWinSolverTest.frame(hand, "", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + DD1010);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        g.setAge(forge.game.GameStage.Mulligan);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.ms = -1L;
        o.window = false;
        final boolean pot = s.potential(g, new java.util.ArrayList<>(), o, 40, true, 12_000L);
        System.out.println("GOLDFISH openingHand before the game: potential=" + pot + " age=" + g.getAge());
        Assert.assertTrue(pot, "a kill within reach from the opening hand, asked at the mulligan");
        Assert.assertEquals(g.getAge(), forge.game.GameStage.Mulligan, "the live game is untouched");
        // keepGate's own question: no land relaxation (only the lands in the hand): this hand's four lands and ritual do it
        final ForcedWinSolver.GoldfishOpts strict = new ForcedWinSolver.GoldfishOpts();
        strict.ms = -1L;
        strict.window = false;
        strict.lands = false;
        Assert.assertTrue(s.potential(g, new java.util.ArrayList<>(), strict, 40, true, 12_000L), "four lands in hand reach it");
        // one land, no fast mana: with no land assumed the planner has nothing, and the policy's mulligan stands
        final String thin = "Swamp;Doomsday;Thassa's Oracle;Gush;Counterspell;Remand;Baleful Strix";
        final String rest = DD1010.replace("Doomsday;", "").replace("Gush;", "").replace("Counterspell;", "").replace("Remand;", "")
                .replace("Baleful Strix;", "") + ";Verdant Catacombs;Cabal Ritual;Urborg, Tomb of Yawgmoth;Raffine's Tower;Mind Stone";
        final Game g3 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame(thin, "", 20, "", "", 20, "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + rest));
        g3.setAge(forge.game.GameStage.Mulligan);
        final ForcedWinSolver s3 = ((CubeComboPlayerController) g3.getPlayers().get(0).getController()).cubeCombo().forcedWin();
        final boolean thinStrict = s3.potential(g3, new java.util.ArrayList<>(), strict, 40, true, 12_000L);
        final boolean thinRelaxed = s3.potential(g3, new java.util.ArrayList<>(), o, 40, true, 12_000L);
        System.out.println("GOLDFISH openingHand before the game: one-lander strict=" + thinStrict + " relaxed=" + thinRelaxed);
        Assert.assertFalse(thinStrict, "one land and no fast mana: not kept by the gate");
        // a hand of lands and creatures of a deck with no kill: nothing
        final String fair = ForcedWinSolverTest.frame("Forest;Forest;Grizzly Bears;Balduvian Bears;Forest;Forest;Grizzly Bears", "", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Forest;Forest;Grizzly Bears;Balduvian Bears;Forest;Forest;Forest;Grizzly Bears");
        final Game g2 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", fair);
        g2.setAge(forge.game.GameStage.Mulligan);
        final ForcedWinSolver s2 = ((CubeComboPlayerController) g2.getPlayers().get(0).getController()).cubeCombo().forcedWin();
        Assert.assertFalse(s2.potential(g2, new java.util.ArrayList<>(), o, 40, true, 12_000L), "no kill in the deck: the policy's mulligan stands");
    }

    /**
     * No land in play: with a land drop a turn Doomsday is cast on the third turn from now and the kill is on the fourth.
     * The first window (turns 0-2) has no line; the second (a quiet turn, then turns 1-3) finds it.
     */
    @Test
    public void quietTurnsThenTheWindow() {
        final String lib = "Doomsday;Gush;Thassa's Oracle;Island;Island;Swamp;Swamp;Swamp;Swamp";
        final ForcedWinSolver.Goldfish r = fish("", "", lib, true, opts(5, 3, true, 0, 1500));
        Assert.assertEquals(r.bestTurn, 3);
        Assert.assertEquals(r.screens, 2);
        Assert.assertEquals(r.g, 0.6 * 0.512, 1e-9);
        // a call cap below one screen's nodes stops after the first screen
        final ForcedWinSolver.GoldfishOpts o = opts(5, 3, true, 0, 1500);
        o.maxNodes = 1;
        final ForcedWinSolver.Goldfish cut = fish("", "", lib, true, o);
        Assert.assertTrue(cut.cut);
        Assert.assertEquals(cut.screens, 1);
    }

    /**
     * mullValue's sandbox: before the game starts, another hand of one card fewer from our own shuffled library, standing
     * at our first main phase; the hand is a function of the seed and the live game is untouched.
     */
    @Test
    public void handSandboxRedraws() {
        final String hand = "Verdant Catacombs;Cabal Ritual;Urborg, Tomb of Yawgmoth;Raffine's Tower;Mind Stone;Thassa's Oracle;Swamp";
        final String frame = ForcedWinSolverTest.frame(hand, "", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + DD1010);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        g.setAge(forge.game.GameStage.Mulligan);
        final Player me = g.getPlayers().get(0);
        final int lib = me.getCardsIn(forge.game.zone.ZoneType.Library).size();
        final ForcedWinProbe.SandboxUse<String> read = (c, cm) -> {
            ForcedWinProbe.redraw(c, cm, 6);
            ForcedWinProbe.toFirstMain(c, cm);
            final java.util.List<String> names = new java.util.ArrayList<>();
            for (forge.game.card.Card x : cm.getCardsIn(forge.game.zone.ZoneType.Hand)) {
                names.add(x.getName());
            }
            java.util.Collections.sort(names);
            return names.size() + "|" + cm.getCardsIn(forge.game.zone.ZoneType.Library).size() + "|" + c.getAge() + "|"
                    + c.getPhaseHandler().getPhase() + "|" + (c.getPhaseHandler().getPlayerTurn() == cm) + "|" + names;
        };
        final String a = ForcedWinProbe.handSandbox(g, me, 11L, read);
        final String a2 = ForcedWinProbe.handSandbox(g, me, 11L, read);
        final String b = ForcedWinProbe.handSandbox(g, me, 12L, read);
        final String kept = ForcedWinProbe.handSandbox(g, me, 13L, (c, cm) -> {
            ForcedWinProbe.toFirstMain(c, cm);
            return cm.getCardsIn(forge.game.zone.ZoneType.Hand).size() + "|" + cm.getCardsIn(forge.game.zone.ZoneType.Library).size();
        });
        System.out.println("MULLVALUE sandbox a=" + a + " b=" + b + " kept=" + kept);
        Assert.assertNotNull(a);
        Assert.assertTrue(a.startsWith("6|" + (lib + 1) + "|Play|MAIN1|true|"), a);
        Assert.assertEquals(a2, a, "the redrawn hand is a function of the seed");
        Assert.assertNotEquals(b, a, "another seed, another hand");
        Assert.assertEquals(kept, "7|" + lib, "the hand as dealt");
        Assert.assertEquals(me.getCardsIn(forge.game.zone.ZoneType.Hand).size(), 7, "the live hand is untouched");
        Assert.assertEquals(me.getCardsIn(forge.game.zone.ZoneType.Library).size(), lib);
        Assert.assertEquals(g.getAge(), forge.game.GameStage.Mulligan, "the live game is untouched");
    }
}
