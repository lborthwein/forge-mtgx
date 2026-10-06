package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * mtgx fork (lane forge-spellhand-loop-1006): Forge AI's runaway guard for spells, the counterpart of
 * {@link ComputerUtil#preventRunAwayActivations(SpellAbility)}, which covers activated abilities only.
 *
 * <p>The loop it stops: with Displacer Kitten and Venser, Shaper Savant on its battlefield, the AI casts a free
 * noncreature spell (Mana Crypt, a Mox, Black Lotus). Kitten's cast trigger blinks Venser; Venser's mandatory enter
 * trigger targets the top spell of the stack ({@code ChangeZoneAi.doExileSpellLogic} with {@code mandatory}), which is
 * the AI's own spell, and returns it to its hand ("Moving spell to Hand"). The AI then casts it again, forever: nothing
 * in the AI bounds how often it recasts the same card, so the game never leaves the step and the JVM runs out of heap.
 *
 * <p>The rule: once the AI has cast the same card {@link #limit()} times this turn, it does not choose to cast that card
 * again this turn (the card stays where it is; the AI passes or plays something else). Not casting a spell is always
 * legal. A game where no card is cast that often by one player in one turn decides exactly as without the guard; a
 * mana-paid cycle cannot reach the limit without that much mana, so in practice only a free (no-progress) loop does.
 * The guard counts from the game's own record of spells cast this turn, so it holds no state of its own and draws no
 * random numbers.
 *
 * <p>Limit: system property {@code forge.ai.recastLimit} (default {@value #DEFAULT_LIMIT}; 0 or less turns the guard
 * off, which reproduces the loop). Every time the guard first stops a card in a turn, one line goes to stdout.
 */
public final class AiRecastGuard {
    public static final int DEFAULT_LIMIT = 20;

    private static volatile int limit = Integer.getInteger("forge.ai.recastLimit", DEFAULT_LIMIT);

    private AiRecastGuard() {
    }

    public static int limit() {
        return limit;
    }

    /** Tests and benches only: set the limit (0 or less turns the guard off). */
    public static void setLimit(final int n) {
        limit = n;
    }

    /** How many times {@code caster} has cast the card with this id this turn (the game's record of spells cast). */
    public static int castsThisTurn(final Player caster, final int cardId) {
        final Game game = caster.getGame();
        int n = 0;
        for (final SpellAbility cast : game.getStack().getSpellsCastThisTurn()) {
            final Card host = cast.getHostCard();
            if (host != null && host.getId() == cardId && caster.equals(cast.getActivatingPlayer())) {
                n++;
            }
        }
        return n;
    }

    /** True if {@code ai} must not choose to cast {@code sa} again this turn. Has no side effects. */
    public static boolean blocks(final Player ai, final SpellAbility sa) {
        final int lim = limit;
        if (lim <= 0 || ai == null || sa == null || !sa.isSpell() || sa.getHostCard() == null) {
            return false;
        }
        return castsThisTurn(ai, sa.getHostCard().getId()) >= lim;
    }

    /** The stdout line written when the guard first stops a card in a turn. */
    public static String logLine(final Player ai, final SpellAbility sa) {
        final Game game = ai.getGame();
        return "[ai-recast-guard] " + ai.getName() + " stops casting " + sa.getHostCard() + " for the rest of turn "
                + game.getPhaseHandler().getTurn() + " (" + game.getPhaseHandler().getPhase() + "): cast "
                + castsThisTurn(ai, sa.getHostCard().getId()) + " times this turn (limit " + limit
                + "), back in a castable zone each time; it passes or plays something else";
    }
}
