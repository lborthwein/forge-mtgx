package forge.game;

/**
 * Engine and copier fixes. Each one changes play only where the old code threw or copied the game wrongly (lane
 * chaos-bugs-1106 and later). Every flag is read at class load and is part of a pilot's identity when on.
 *
 * <p><b>Live line (live-fixes-1107):</b> {@code -Dforge.liveFixes} (default {@code true} on this line only) is the one
 * switch that sets the DEFAULT of every selected bug fix for every seat: the JVM flags {@code forge.copyRobust},
 * {@code forge.copyAltCost}, {@code forge.researchFixes}, {@code forge.copyPoolMana}, {@code forge.probeEmptyDraw} (ForcedWinProbe), {@code forge.ai.belief.basicsByType}, the house look-ahead keys
 * ({@code forge.house.<key>}, read in LookaheadSearch), and the solver keys {@code staleStatics}, {@code turnLimits},
 * {@code lineUncapped} ({@code ForcedWinSolver.Config.fromProps}). An explicit flag or key still wins.
 * {@code -Dforge.liveFixes=false} is the live jar before these fixes, byte for byte.
 */
public final class EngineFixes {
    private static boolean liveFixes = Boolean.parseBoolean(System.getProperty("forge.liveFixes", "true"));

    /** A fix flag: its own property when set, else {@link #liveFixes()}. */
    private static boolean flag(final String property) {
        final String v = System.getProperty(property);
        return v == null ? liveFixes : Boolean.parseBoolean(v);
    }

    /** A fix flag of another module: its own property when set, else {@link #liveFixes()} (read once, at the caller's class load). */
    public static boolean liveFlag(final String property) {
        return flag(property);
    }

    private static boolean copyRobust = flag("forge.copyRobust");

    private EngineFixes() {
    }

    /** live-fixes-1107: the default of every selected fix (see the class comment). */
    public static boolean liveFixes() {
        return liveFixes;
    }

    /** The startup line: the switch and what each fix resolved to. */
    public static String describe() {
        return "[live-fixes] forge.liveFixes=" + liveFixes + " copyRobust=" + copyRobust + " copyAltCost=" + copyAltCost
                + " researchFixes=" + researchFixes + " copyPoolMana=" + copyPoolMana + " probeEmptyDraw=" + flag("forge.probeEmptyDraw") + " basicsByType=" + flag("forge.ai.belief.basicsByType")
                + " house(beliefStatics,beliefPlayerStatics,copyTurnLimits,offBattlefieldLimits,beliefRememberedHand,copyBackFace,stackAbilityCopies)="
                + flag("forge.house.beliefStatics") + "," + flag("forge.house.beliefPlayerStatics") + "," + flag("forge.house.copyTurnLimits")
                + "," + flag("forge.house.offBattlefieldLimits") + "," + flag("forge.house.beliefRememberedHand") + ","
                + flag("forge.house.copyBackFace") + "," + flag("forge.house.stackAbilityCopies")
                + " solver(staleStatics,turnLimits,lineUncapped,stalePlayerStatics,offBattlefieldLimits,copyBackFace,stackAbilityCopies,planExpandCap)=" + liveFixes
                + " (solver keys: default; an explicit key wins)";
    }

    /** For tests. */
    public static void setLiveFixesForTest(final boolean on) {
        liveFixes = on;
    }

    public static boolean copyRobust() {
        return copyRobust;
    }

    /**
     * loop-exec-1107 ({@code -Dforge.copyAltCost=true}): a game copy rebuilds a spell cast for an alternative cost that a
     * keyword grants only in another zone (escape from the graveyard, granted by a static) from the card's own spell,
     * keeping the alternative cost. Without it the copy threw ("stack copy: no ability"), and every play-out of that
     * position failed.
     */
    private static boolean copyAltCost = flag("forge.copyAltCost");

    public static boolean copyAltCost() {
        return copyAltCost;
    }

    /** For tests. */
    public static void setCopyAltCostForTest(final boolean on) {
        copyAltCost = on;
    }

    /**
     * unified-jar-1106 ({@code -Dforge.researchFixes=true}): the research line's copier fixes, keyed. Live port
     * (live-fixes-1107) sites: GameCopier's copy-token prototype and owned trait states, effect source ability and the
     * "gone" stack refusal; LookaheadSearch's belief-world static refresh and the shown-on-stack filter. The split-LEAVES
     * batches of the research line are not on the live line.
     */
    private static boolean researchFixes = flag("forge.researchFixes");

    public static boolean researchFixes() {
        return researchFixes;
    }

    /**
     * shipA-on-leakfix-1112: the research line's client-side sites that the live port (live-fixes-1107) did not take:
     * RlSearchClient's split LEAVES batches and the bench seat's InfoStates attach (RlSearch.attachInfoStates). On the
     * research line they follow {@code -Dforge.researchFixes=true}; here they follow that property only when it is SET
     * (as on the research line), never the {@code forge.liveFixes} default, so the live line's play is unchanged.
     */
    private static boolean researchClient = Boolean.getBoolean("forge.researchFixes");

    public static boolean researchClient() {
        return researchClient;
    }

    /** For tests. */
    public static void setResearchFixesForTest(final boolean on) {
        researchFixes = on;
        researchClient = on;
    }

    /**
     * ledger-sweep-1107, ledger row 18 ({@code -Dforge.standInEffect=true}, off by default; not a live fix: liveFixes does
     * not turn it on). A frame install with {@code forge.gamestate.installAsIs} links an exiled card whose recorded source
     * is in no zone to a stand-in object. Off, the stand-in is a blank {@code new Card(id, game)}; on, it is built as Forge
     * builds any effect object.
     */
    private static boolean standInEffect = Boolean.getBoolean("forge.standInEffect");

    public static boolean standInEffect() {
        return standInEffect;
    }

    /** For tests. */
    public static void setStandInEffectForTest(final boolean on) {
        standInEffect = on;
    }

    /** For tests. */
    public static void setCopyRobustForTest(final boolean on) {
        copyRobust = on;
    }

    /**
     * copy-pool-mana-1113 ({@code -Dforge.copyPoolMana}, a live fix: default {@link #liveFixes()}): a game copy rebuilds
     * each floating mana as the copy's own, its player the copy's player and its source card and mana ability the
     * copy's. Off, a copy shares the original's {@code Mana} objects, so spending floating mana in a play-out raises the
     * ORIGINAL player's mana-spent count and holds a ManaExpend trigger on the ORIGINAL game's waiting list, from the
     * play-out's thread (the "wt is null" crash in TriggerHandler.collectTriggerForWaiting), and mana whose ability adds
     * an effect when spent (can't be countered, counters) adds it to the original game.
     */
    private static boolean copyPoolMana = flag("forge.copyPoolMana");

    public static boolean copyPoolMana() {
        return copyPoolMana;
    }

    /** For tests. */
    public static void setCopyPoolManaForTest(final boolean on) {
        copyPoolMana = on;
    }

    // ---- state-machines-1113: copy-fidelity fixes, OFF by default (not live fixes: forge.liveFixes does not turn them on).
    // Each makes a game copy carry state its original has; on, play-outs change, so each is a strategic change under the
    // heuristic contract and part of a pilot's identity when on.

    /**
     * {@code -Dforge.copyCombatStep=true}: a combat copy keeps which combatants already dealt first-strike damage (and
     * the rules' order-combatants setting). Off, a copy made after first-strike damage lets first strikers deal damage
     * again in the regular damage step.
     */
    private static boolean copyCombatStep = Boolean.getBoolean("forge.copyCombatStep");

    public static boolean copyCombatStep() {
        return copyCombatStep;
    }

    /**
     * {@code -Dforge.copyTurnCounters=true}: a game copy carries the turn's step counters (mains, combats, upkeeps, end
     * steps, planar dice, the skip-damage and repeat-cleanup latches). Off, they start at zero, so "at the beginning of
     * your second main phase" never triggers in a copy made in the first main phase, and "first combat" triggers misfire.
     */
    private static boolean copyTurnCounters = Boolean.getBoolean("forge.copyTurnCounters");

    public static boolean copyTurnCounters() {
        return copyTurnCounters;
    }

    /**
     * {@code -Dforge.copyPhased=true}: a game copy carries phased-out permanents (and their phasing state). Off, every
     * phased-out permanent is missing from every copy and never phases back in during a play-out.
     */
    private static boolean copyPhased = Boolean.getBoolean("forge.copyPhased");

    public static boolean copyPhased() {
        return copyPhased;
    }

    /** The copy-fidelity flags as one line (startup logs, manifests). */
    public static String describeCopyFidelity() {
        return "[copy-fidelity] copyCombatStep=" + copyCombatStep + " copyTurnCounters=" + copyTurnCounters + " copyPhased=" + copyPhased;
    }

    /** For tests. */
    public static void setCopyFidelityForTest(final boolean combatStep, final boolean turnCounters, final boolean phased) {
        copyCombatStep = combatStep;
        copyTurnCounters = turnCounters;
        copyPhased = phased;
    }
}
