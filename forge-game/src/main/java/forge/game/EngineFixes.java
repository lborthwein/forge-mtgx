package forge.game;

/**
 * Default-off engine and copier fixes. Each one changes play only where the old code threw (lane chaos-bugs-1106).
 * {@code -Dforge.copyRobust=true}, read at class load. The flag is part of a pilot's identity when on.
 */
public final class EngineFixes {
    private static boolean copyRobust = Boolean.getBoolean("forge.copyRobust");

    /**
     * unified-jar-1106 ({@code -Dforge.researchFixes=true}, read at class load; off = the live line's copies and leaves,
     * byte for byte): the research line's unkeyed copier and client changes, keyed on this line. A research run (labels,
     * dump, generator) sets it to get what that line always had. Sites: GameCopier's copy-token prototype, effect source
     * ability, owned trait states and the "gone" stack refusal; LookaheadSearch's belief-world static refresh and the
     * shown-on-stack filter; RlSearchClient's split LEAVES batches.
     */
    private static boolean researchFixes = Boolean.getBoolean("forge.researchFixes");

    private EngineFixes() {
    }

    public static boolean copyRobust() {
        return copyRobust;
    }

    public static boolean researchFixes() {
        return researchFixes;
    }

    /** For tests. */
    public static void setResearchFixesForTest(final boolean on) {
        researchFixes = on;
    }

    /**
     * loop-exec-1107 ({@code -Dforge.copyAltCost=true}, off by default): a game copy rebuilds a spell cast for an alternative
     * cost that a keyword grants only in another zone (escape from the graveyard, granted by a static) from the card's own
     * spell, keeping the alternative cost. Without it the copy threw ("stack copy: no ability"), and every play-out of that
     * position failed.
     */
    private static boolean copyAltCost = Boolean.getBoolean("forge.copyAltCost");

    public static boolean copyAltCost() {
        return copyAltCost;
    }

    /** For tests. */
    public static void setCopyAltCostForTest(final boolean on) {
        copyAltCost = on;
    }

    /**
     * ledger-sweep-1107, ledger row 18 ({@code -Dforge.standInEffect=true}, off by default). A frame install with
     * {@code forge.gamestate.installAsIs} links an exiled card whose recorded source is in no zone to a stand-in object.
     * Off, the stand-in is a blank {@code new Card(id, game)}: its name is empty, so any last-known-information copy of a
     * card exiled with it (Forge AI asking whether it may play that card, Spell.canPlayFromHost) looks up a paper card for
     * the empty name and throws (CardDb$CardRequest.fromString). On, the stand-in is built as Forge builds any effect
     * object: a name that no card has and the effect game-piece type, so the copy finds no paper card and goes on.
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
}
