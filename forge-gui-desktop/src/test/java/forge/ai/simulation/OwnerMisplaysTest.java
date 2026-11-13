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
}
