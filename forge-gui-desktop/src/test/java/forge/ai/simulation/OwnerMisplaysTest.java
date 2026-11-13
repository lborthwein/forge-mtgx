package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * owner-misplays-1113 (lane solver-distill-cc): the owner's three positions from his review of recorded games of the
 * live pilot (review set G15, G16, G17 = games D048, D066, D119), as TESTS of the planner screens and the solver. Card
 * facts are test data only; nothing here reaches a pilot, a search or a label.
 *
 * <ul>
 * <li>G15: a creature with flash and the Aura that makes it copy itself, both in hand with the mana. His line: the
 * creature on the opponent's turn, the Aura on the next own turn. The look-ahead's leaves can credit "the creature in
 * play, the Aura next" only through the planner screens' loop probe (solver key planLoops).</li>
 * <li>G16: the creature in play, the Aura in hand, one source of the Aura's colour in play, a looting discard between the
 * Aura and a land that is the second source. Keeping the Aura has a line over the next draw, keeping the land has none.</li>
 * <li>G17: two rituals, a spell that lets the graveyard be cast again, a storm finisher, a modal spell with an
 * additional-cost escalation in the graveyard, the opponent at 16. The kill: ritual, ritual, the recast spell, both
 * rituals again, the modal spell PLAIN with its drain mode, the finisher at storm 6.</li>
 * </ul>
 */
public class OwnerMisplaysTest extends forge.ai.AITest {

    /** The ship string's solver keys (plans.solverCfg.*), as the live parser hands them to the solver. */
    static JsonObject shipSolverCfg(final String extra) {
        final JsonObject o = new JsonObject();
        o.addProperty("budgetMs", "6000");
        o.addProperty("engineMs", "4000");
        o.addProperty("evalReserveMs", "1500");
        o.addProperty("planOrder", "7");
        for (String k : new String[] {"cycles", "hideOwnLibrary", "breakOnCounter", "engineAfterPlanFail", "engineLongLoops",
                "engineLoopBisect", "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen",
                "engineRerun", "engineTriggerTargets", "engineVerify", "followPay", "historyLki", "landEntersProbe",
                "loopMarginal", "paySearch", "planDiscardWall", "probeSettle", "readerPerGame", "sourceColorsLive",
                "sourceLeaves", "sourceOutput", "stackHistory", "tutorCast", "unknownWorlds", "verifyReplan", "passBlockers",
                // the live fixes' solver keys (the jar's default under forge.liveFixes)
                "staleStatics", "turnLimits", "lineUncapped", "stalePlayerStatics", "offBattlefieldLimits", "copyBackFace",
                "stackAbilityCopies", "planExpandCap"}) {
            o.addProperty(k, "true");
        }
        final String more = (extra == null ? "" : extra) + "," + System.getProperty("owner.misplays.solver", "");
        for (String kv : more.split(",")) {
            final int i = kv.indexOf('=');
            if (i > 0) {
                o.addProperty(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
        o.addProperty("act", "false");
        o.addProperty("defense", "false");
        return o;
    }

    private static String cut(final Object o, final int n) {
        final String s = String.valueOf(o).replaceAll("\\s+", " ");
        return s.substring(0, Math.min(n, s.length()));
    }

    // ------------------------------------------------------------------ G15 / G16: the planner screens

    private static ForcedWinSolver screenSolver(final Player me, final boolean loops) {
        final Map<String, String> p = new java.util.HashMap<>();
        for (String k : new String[] {"cycles", "engineLoopReach", "engineLongLoops", "engineLoopBisect", "engineLoopHoist",
                "engineMoves", "engineTriggerTargets", "turnLimits", "stackAbilityCopies", "stackHistory", "historyLki",
                "copyBackFace", "offBattlefieldLimits", "staleStatics", "stalePlayerStatics", "planExpandCap"}) {
            p.put(k, "true");
        }
        p.put("planLoops", String.valueOf(loops));
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(p::get);
        c.sharedReader = false;
        c.honestProbes = false;
        return ForcedWinSolver.withReader(me, c, ForcedWinSolver.newSharedReader());
    }

    private static double reach(final String what, final String battlefield, final String hand, final String library,
            final String oppBattlefield, final boolean loops) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 16, "", oppBattlefield, 10, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = screenSolver(me, loops);
        final ForcedWinSolver.ReachOpts o = new ForcedWinSolver.ReachOpts();
        o.nodes = 4000;
        o.ms = -1L;
        o.nameOnePass = true;
        final ForcedWinSolver.Reach r = s.reach(g, s.knownTop(g), o);
        System.out.println("OWNERMISPLAYS " + what + " loops=" + loops + " -> reach p=" + r.p + " direct=" + r.direct + " failed=" + r.failed);
        Assert.assertFalse(r.failed);
        return r.p;
    }

    static final String G15_LANDS = "Island;Badlands;Ancient Tomb;Mountain;Island";
    static final String G15_OPP = "Torsten, Founder of Benalia;Titan of Industry;Plains;Forest;Forest;Forest;Forest";
    static final String G15_LIB = "Island;Island;Mountain;Remand;Preordain;Ponder;Island;Baleful Strix";

    /** G15, the leaf his line reaches: the creature in play since the opponent's turn, the Aura in hand, the mana. */
    @Test
    public void g15TheCreatureInPlayAndTheAuraIsALineOnlyWithTheLoopScreens() {
        Assert.assertEquals(reach("G15 creature in play + aura in hand", G15_LANDS + ";Pestermite", "Splinter Twin;Time Warp", G15_LIB, G15_OPP, true),
                1.0, 1e-9);
        Assert.assertEquals(reach("G15 creature in play + aura in hand", G15_LANDS + ";Pestermite", "Splinter Twin;Time Warp", G15_LIB, G15_OPP, false),
                0.0, 1e-9, "the ship string (planLoops off): the leaf has no credit for the combo one step from the kill");
        // both pieces still in hand at our own turn, with the mana: the screens count the line by our NEXT turn as well
        // (creature now, Aura next turn), so with the loop screens on a "hold" leaf and a "deployed" leaf differ only by
        // the reach's per-turn discount (reach.gamma), not by whether the creature survived the opponent's turn
        Assert.assertEquals(reach("G15 both in hand", G15_LANDS, "Pestermite;Splinter Twin;Time Warp", G15_LIB, G15_OPP, true), 1.0, 1e-9);
        Assert.assertEquals(reach("G15 both in hand", G15_LANDS, "Pestermite;Splinter Twin;Time Warp", G15_LIB, G15_OPP, false), 0.0, 1e-9);
    }

    static final String G16_BOARD = "Mountain;Island;Island;Island;Island;Swamp;Pestermite;Baleful Strix";
    /** Ten unknown library cards, four of them a second source of the Aura's colour. */
    static final String G16_LIB = "Mountain;Mountain;Blood Crypt;Steam Vents;Island;Island;Island;Remand;Ponder;Preordain";

    /** G16, the two positions after the loot's discard: the Aura kept (a line over the draw) against the land kept (none). */
    @Test
    public void g16KeepingTheAuraHasALineOverTheDrawKeepingTheLandHasNone() {
        final double aura = reach("G16 kept the aura", G16_BOARD, "Splinter Twin", G16_LIB, "Old One Eye;Forest;Forest;Forest", true);
        final double land = reach("G16 kept the land", G16_BOARD, "Badlands", G16_LIB, "Old One Eye;Forest;Forest;Forest", true);
        Assert.assertTrue(aura >= 0.2 - 1e-9 && aura <= 0.4 + 1e-9, "the draws that are a second source (two that enter untapped, two more that can): " + aura);
        Assert.assertEquals(land, 0.0, 1e-9);
        Assert.assertEquals(reach("G16 kept the aura", G16_BOARD, "Splinter Twin", G16_LIB, "Old One Eye;Forest;Forest;Forest", false), 0.0, 1e-9,
                "the ship string: no screen separates the two discards");
    }

    /**
     * G16 one turn earlier (his "the setup totally ready to go"): the creature just cast, the Aura in hand, a land and a
     * one-shot artifact as the two sources of the Aura's colour. Mana sources are any permanent with a mana ability.
     */
    @Test
    public void g16TheOneShotArtifactIsASourceOfTheAurasColour() {
        final String board = "Mountain;Island;Island;Island;Island;Swamp;Lotus Petal;Pestermite;Baleful Strix";
        Assert.assertEquals(reach("G16 turn 9, artifact in play", board, "Splinter Twin;Smuggler's Copter", G16_LIB, "Old One Eye;Forest;Forest;Forest", true),
                1.0, 1e-9, "two sources of the colour in play: the line needs no draw");
        Assert.assertTrue(reach("G16 turn 9, artifact spent", G16_BOARD, "Splinter Twin", G16_LIB, "Old One Eye;Forest;Forest;Forest", true) < 1.0);
    }

    /**
     * G16 root (owner, step 22, turn 9 second main phase): the creature in play, the Aura and an off-route artifact in hand,
     * the land of the Aura's colour untapped and a one-shot artifact as the second source. Casting the off-route artifact
     * spends the one-shot source. The look-ahead's reach part (spec reach, gate on) is read only when the root shows
     * potential ({@link ForcedWinSolver#potential}): it needs a card that ends games in the planner's model, and a copy
     * loop is one only with the loop screens (planLoops). Report: the gate with and without, and the two leaves' reach.
     */
    @Test
    public void g16AtTheOffRouteCastTheGateAndTheTwoLeaves() {
        final String board = "Mountain;Island|Tapped;Island|Tapped;Island|Tapped;Island|Tapped;Swamp|Tapped;Lotus Petal;Pestermite;Baleful Strix";
        final String opp = "Old One Eye;Ketria Triome;Forest;Forest;Forest";
        final String lib = "Badlands;" + G16_LIB;
        for (boolean loops : new boolean[] {false, true}) {
            final String frame = ForcedWinSolverTest.frame("Splinter Twin;Smuggler's Copter", board, 9, "", opp, 18, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib);
            final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
            final Player me = g.getPlayers().get(0);
            final ForcedWinSolver s = screenSolver(me, loops);
            final boolean ender = s.hasEnder(g);
            final boolean pot = s.potential(g, s.knownTop(g), new ForcedWinSolver.GoldfishOpts(), 40, true, 12_000L);
            System.out.println("OWNERMISPLAYS G16 step 22 loops=" + loops + " hasEnder=" + ender + " potential=" + pot);
            if (loops) {
                Assert.assertTrue(pot, "with the loop screens the root shows potential: the gate opens");
            }
        }
        // the two leaves at our next turn (lands untapped): the one-shot source kept, or spent on the off-route cast
        final double hold = reach("G16 next turn, source kept", "Mountain;Island;Island;Island;Island;Swamp;Lotus Petal;Pestermite;Baleful Strix",
                "Splinter Twin;Smuggler's Copter", lib, opp, true);
        final double spent = reach("G16 next turn, source spent", "Mountain;Island;Island;Island;Island;Swamp;Smuggler's Copter;Pestermite;Baleful Strix",
                "Splinter Twin", lib, opp, true);
        Assert.assertEquals(hold, 1.0, 1e-9);
        Assert.assertTrue(spent < hold, "spending the source costs the line: " + spent);
    }

    /** G08: the loot's discard between a creature that copies its partner in play and a tutor creature; three sources in play. */
    @Test
    public void g08KeepingTheCopyingCreatureHasALineKeepingTheTutorHasNone() {
        final String board = "Plains;Plains;Plains;Mountain;Plateau;Mox Ruby;Mana Vault;Skullclamp;Restoration Angel";
        final String lib = "Plains;Plains;Island;Swords to Plowshares;Flickerwisp;Stoneforge Mystic;Plains;Mountain";
        final String opp = "Elite Spellbinder;Mountain;Mountain;Mountain";
        final double piece = reach("G08 kept the copying creature", board, "Kiki-Jiki, Mirror Breaker", lib, opp, true);
        final double tutor = reach("G08 kept the tutor", board, "Recruiter of the Guard", lib, opp, true);
        Assert.assertEquals(piece, 1.0, 1e-9, "its partner is in play and the mana is there");
        Assert.assertTrue(tutor < piece, "the tutor has no piece left to find: " + tutor);
        Assert.assertEquals(reach("G08 kept the copying creature", board, "Kiki-Jiki, Mirror Breaker", lib, opp, false), 0.0, 1e-9);
    }

    /** loop-probe-1113: a copier in play with its untapper, and more creature names in the library than one call probes. */
    private static Game loopGame(final String copierPartner, final String library) {
        final String frame = ForcedWinSolverTest.frame("", "Mountain;Mountain;Mountain;Mountain;Mountain;Kiki-Jiki, Mirror Breaker;" + copierPartner,
                9, "", "Forest;Forest", 18, "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    static final String MANY = "Grizzly Bears;Balduvian Bears;Runeclaw Bear;Elvish Mystic;Llanowar Elves;Savannah Lions;Goblin Piker;"
            + "Hill Giant;Craw Wurm;Gray Ogre;Scathe Zombies;Wall of Wood;Pearled Unicorn;Mons's Goblin Raiders;Merfolk of the Pearl Trident";

    /**
     * loop-probe-1113 (G09's cost: the probe ran again inside a card-scorer ask, 12 partners, and took the ask's budget):
     * the answers are kept per partner, so a second reader on the same game with the library in another order (a re-dealt
     * copy; the old key was the first 12 names in zone order) probes nothing again.
     */
    @Test
    public void theLoopProbeIsNotRunAgainWhenTheLibraryOrderChanges() {
        final Game g = loopGame("Pestermite", MANY);
        final Player me = g.getPlayers().get(0);
        final long p0 = ForcedWinSolver.LOOP_PROBES.get();
        Assert.assertTrue(screenSolver(me, true).hasEnder(g), "the copier and its untapper are a loop");
        final long first = ForcedWinSolver.LOOP_PROBES.get() - p0;
        Assert.assertTrue(first > 0, "the first reader probes");
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        java.util.Collections.reverse(lib);
        me.getZone(ZoneType.Library).setCards(lib);
        Assert.assertTrue(screenSolver(me, true).hasEnder(g), "the same answer from the kept probes");
        final long again = ForcedWinSolver.LOOP_PROBES.get() - p0 - first;
        System.out.println("OWNERMISPLAYS loop probe: first reader " + first + " probes, second reader (library reversed) " + again);
        // 16 creature names, 12 partners a call: the reversed order brings up to 4 names not probed before, none twice
        Assert.assertTrue(again <= 4, "only partners not probed before: " + again);
        final long p1 = ForcedWinSolver.LOOP_PROBES.get();
        Assert.assertTrue(screenSolver(me, true).hasEnder(g));
        java.util.Collections.reverse(lib);
        me.getZone(ZoneType.Library).setCards(lib);
        Assert.assertTrue(screenSolver(me, true).hasEnder(g));
        Assert.assertEquals(ForcedWinSolver.LOOP_PROBES.get() - p1, 0L, "every partner is probed once a game, in either order");
        // a copy of the game (the card scorer's, the look-ahead's) has its own Match: it still reuses the live game's probes
        final Game copy = new GameCopier(g, true).makeCopy();
        Assert.assertTrue(screenSolver(copy.getPlayers().get(0), true).hasEnder(copy));
        Assert.assertEquals(ForcedWinSolver.LOOP_PROBES.get() - p1, 0L, "a copy probes nothing the live game probed");
    }

    /**
     * loop-probe-1113: the probes take their time from the caller's budget. With the caller's deadline passed and no
     * minimum, nothing is probed and nothing is recorded as "no loop": the same reader asks again once there is time.
     */
    @Test
    public void theLoopProbeWaitsWhenTheCallerHasNoTimeAndAsksAgainLater() {
        final Game g = loopGame("Deceiver Exarch", MANY.replace("Grizzly Bears", "Zodiac Monkey"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = screenSolver(me, true);
        final long min = ForcedWinSolver.LOOP_MIN_NANOS;
        try {
            ForcedWinSolver.LOOP_MIN_NANOS = 0L;
            ForcedWinSolver.loopDeadline(g, System.nanoTime() - 1L);
            final long p0 = ForcedWinSolver.LOOP_PROBES.get();
            Assert.assertFalse(s.hasEnder(g), "no time: no probe, so no loop known yet");
            Assert.assertEquals(ForcedWinSolver.LOOP_PROBES.get() - p0, 0L);
            ForcedWinSolver.loopDeadline(g, 0L);
            Assert.assertTrue(s.hasEnder(g), "asked again with time: the loop is found");
        } finally {
            ForcedWinSolver.LOOP_MIN_NANOS = min;
            ForcedWinSolver.loopDeadline(g, 0L);
        }
    }

    // ------------------------------------------------------------------ G17: the solver

    static final String G17_FRAME = "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN2\nhumanlife=18\nailife=16\n"
            + "humanlandsplayed=1\nailandsplayed=0\naihand=Old One Eye;Triplicate Titan\nailibrary=" + ForcedWinSolverTest.LIB20
            + "\naigraveyard=Vorinclex;Swords to Plowshares\naiexile=\naibattlefield=Forest;Forest;Indatha Triome;Oracle of Mul Daya\n"
            + "humanhand=Tendrils of Agony;Dark Ritual;Cabal Ritual;Yawgmoth's Will\n"
            + "humanbattlefield=Swamp;Swamp;Swamp;Island\nhumangraveyard=Archon of Cruelty;Collective Brutality\nhumanexile=\n"
            + "humanlibrary=Swamp;Swamp;Swamp;Duress;Night's Whisper;Grave Titan;Swamp;Swamp\n";

    /** Casts the named cards from its hand in order (one at a time, each resolved before the next), then rests. */
    static final class Script extends PlayerControllerAi {
        final List<String> names;
        int next;
        boolean rest;

        Script(final Game g, final Player p, final forge.LobbyPlayer lp, final List<String> names) {
            super(g, p, lp);
            this.names = names;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            if (!g.getStack().isEmpty()) {
                return null;
            }
            if (next >= names.size()) {
                rest = true;
                return null;
            }
            for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) {
                if (c.getName().equals(names.get(next))) {
                    final SpellAbility sa = c.getFirstSpellAbility();
                    sa.setActivatingPlayer(getPlayer());
                    if (sa.canPlay()) {
                        next++;
                        final List<SpellAbility> out = new ArrayList<>();
                        out.add(sa);
                        return out;
                    }
                }
            }
            rest = true;
            return null;
        }
    }

    /** The G17 game at our second main phase; with {@code played}, after those casts from hand resolved (mana floating). */
    static Game g17(final List<String> played) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", G17_FRAME);
        if (played == null || played.isEmpty()) {
            return g;
        }
        final Player me = g.getPlayers().get(0);
        final forge.game.player.PlayerController before = me.getController();
        final Script s = new Script(g, me, before.getLobbyPlayer(), played);
        me.dangerouslySetController(s);
        final PhaseHandler ph = g.getPhaseHandler();
        LookaheadSearch.givePriority(ph, me);
        int steps = 0;
        while (!s.rest && !g.isGameOver() && steps++ < 400) {
            ph.mainLoopStep();
        }
        me.dangerouslySetController(before);
        LookaheadSearch.givePriority(ph, me);
        Assert.assertEquals(s.next, played.size(), "the script's casts");
        Assert.assertTrue(g.getStack().isEmpty());
        return g;
    }

    private static ForcedWinSolver.Proposal propose(final String what, final Game g, final String extra) {
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(shipSolverCfg(extra)));
        final long t0 = System.nanoTime();
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        System.out.println("OWNERMISPLAYS " + what + " [" + (extra == null ? "ship" : extra) + "] turn=" + g.getPhaseHandler().getTurn() + " "
                + g.getPhaseHandler().getPhase() + " hand=" + me.getCardsIn(ZoneType.Hand) + " gy=" + me.getCardsIn(ZoneType.Graveyard)
                + " pool=" + me.getManaPool().totalMana() + " opp life=" + opp.getLife() + " PROPOSE ms=" + (System.nanoTime() - t0) / 1_000_000
                + " lines=" + props.size() + (best == null ? " none" : " proven=" + best.proven(1) + " pUnopposed=" + best.pUnopposed
                        + " pBelief=" + best.pBelief + " steps=" + best.steps + " winTurn=" + best.winTurn + " how=" + best.how + " | "
                        + cut(best.text, 400))
                + " cap=" + s.lastCap() + " || " + s.summary());
        return best;
    }

    /**
     * G17 at the two plan points the live pilot had (start of the second main phase; and, had it been asked again, after
     * the recast spell resolved). Report only unless {@code -Downer.misplays.expect=true}: then the kill must be proven.
     * {@code -Downer.misplays.solver=key=value,...} adds solver keys (a fix under test); {@code -Downer.misplays.budgets=1,5}
     * multiplies the solver's budget.
     */
    @Test
    public void g17TheStormKill() {
        final boolean expect = Boolean.getBoolean("owner.misplays.expect");
        for (String mult : System.getProperty("owner.misplays.budgets", "1").split(",")) {
            final int m = Integer.parseInt(mult.trim());
            final String extra = m == 1 ? null : "budgetMs=" + 6000 * m + ",engineMs=" + 4000 * m;
            final ForcedWinSolver.Proposal a = propose("G17 main phase x" + m, g17(null), extra);
            final ForcedWinSolver.Proposal b = propose("G17 after the recast spell x" + m,
                    g17(java.util.Arrays.asList("Dark Ritual", "Cabal Ritual", "Yawgmoth's Will")), extra);
            if (expect) {
                Assert.assertTrue(b != null && b.proven(1), "after the recast spell the kill is four casts");
                Assert.assertTrue(a != null && a.proven(1), "from the main phase the kill is seven casts");
            }
        }
    }

    /**
     * G17 with the modes of a modal spell as a choice point of the engine search (solver key engineModeChoice): after the
     * recast spell resolved the kill is ritual, ritual, the modal spell with its drain mode ALONE (a second mode would
     * cost the finisher as its additional discard), the finisher. A wide budget: the question is whether the line is in
     * the search space, not how fast this host is. Without the key the same search finds nothing.
     */
    @Test
    public void g17WithModeChoicePointsTheKillAfterTheRecastSpellIsProven() {
        final java.util.List<String> played = java.util.Arrays.asList("Dark Ritual", "Cabal Ritual", "Yawgmoth's Will");
        final String wide = "budgetMs=90000,engineMs=60000,evalReserveMs=20000";
        final ForcedWinSolver.Proposal off = propose("G17 after the recast spell, wide", g17(played), wide);
        final ForcedWinSolver.Proposal on = propose("G17 after the recast spell, wide", g17(played), wide + ",engineModeChoice=true");
        Assert.assertTrue(on != null && on.pUnopposed >= 1.0, "with mode choice points the engine finds the kill");
        Assert.assertTrue(String.valueOf(on.text).contains("Collective Brutality") && String.valueOf(on.text).contains("Tendrils of Agony"),
                "the line casts the modal spell and the finisher: " + on.text);
        Assert.assertEquals(on.winTurn, 0, "this turn");
        Assert.assertTrue(off == null || off.pBelief < 1.0 || off.winTurn > 0, "without the key no proven kill this turn (the ship string)");
    }

    /**
     * The live build's deadAltCost guard (8b4838874b9) at G17's cast: the modal spell's extra card is chosen with its modes,
     * after the cast is a candidate, so the candidate's pay cost spends no hand card and the guard does not drop it (it can
     * neither cause nor prevent the G17 loss). Every option the live engine offers for it is checked.
     */
    @Test
    public void g17DeadAltCostDoesNotTouchTheModalCast() {
        final Game g = g17(java.util.Arrays.asList("Dark Ritual", "Cabal Ritual", "Yawgmoth's Will"));
        final Player me = g.getPlayers().get(0);
        int seen = 0;
        for (Card c : me.getCardsIn(ZoneType.Graveyard)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) { // the graveyard permission's alternatives included
                if (!sa.isSpell()) {
                    continue;
                }
                seen++;
                System.out.println("OWNERMISPLAYS deadAltCost " + c.getName() + " spendsHandCard=" + LookaheadSearch.spendsHandCard(sa)
                        + " dead=" + LookaheadSearch.deadAltCost(sa, me));
                if (c.getName().equals("Collective Brutality")) {
                    Assert.assertFalse(LookaheadSearch.deadAltCost(sa, me), "the modal cast is not a dead alternative cost");
                }
            }
        }
        Assert.assertTrue(seen > 0, "a cast from the graveyard is offered after the recast spell");
    }
}
