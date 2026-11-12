package forge.game;

import forge.game.combat.CombatView;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;

/**
 * Lane replay-record-1021: Forge's process-wide object-id counters (the ones {@link forge.util.IdScope} scopes), read
 * and restored as one state. Those ids feed {@code hashCode()} and with it hash-collection order; a forced replay of the
 * AI tape skips the AI's deliberation (which creates abilities) and restores the counters a decision left behind, so
 * every object the game creates afterwards gets the id it got live.
 */
public final class GlobalIds {
    private GlobalIds() {
    }

    /** {Game, SpellAbility, stack instance, Trigger, ReplacementEffect, StaticAbility, CombatView (counts down)}. */
    public static int[] capture() {
        return new int[] {Game.peekMaxId(), SpellAbility.peekMaxId(), SpellAbilityStackInstance.peekMaxId(),
                Trigger.peekMaxId(), ReplacementEffect.peekMaxId(), StaticAbility.peekMaxId(), CombatView.peekNextId()};
    }

    /**
     * Lane bvw-decision-log-1027: the counters a game's ids come from. {@code scope} is the game thread's own id scope
     * ({@link forge.util.IdScope#capture}; a bench game runs inside one, from 1e9): then its counters, in this class's
     * form; null (a live server game, no scope): the process-wide counters, {@link #capture()}.
     */
    public static int[] capture(final Object scope) {
        return scope == null ? capture() : forge.util.IdScope.peek(scope);
    }

    /** Lane bvw-decision-log-1027: {@link #restoreForward(int[])} on the counters {@link #capture(Object)} reads. */
    public static int restoreForward(final Object scope, final int[] to) {
        return scope == null ? restoreForward(to) : forge.util.IdScope.restoreForward(scope, to);
    }

    /**
     * Move every counter forward to {@code to} (from {@link #capture}); a counter already past it is left alone (ids
     * already handed out are never handed out again). Returns how many counters were already past.
     */
    public static int restoreForward(final int[] to) {
        final int[] now = capture();
        int ahead = 0;
        if (now[0] < to[0]) Game.dangerouslySetMaxId(to[0]); else if (now[0] > to[0]) ahead++;
        if (now[1] < to[1]) SpellAbility.dangerouslySetMaxId(to[1]); else if (now[1] > to[1]) ahead++;
        if (now[2] < to[2]) SpellAbilityStackInstance.dangerouslySetMaxId(to[2]); else if (now[2] > to[2]) ahead++;
        if (now[3] < to[3]) Trigger.dangerouslySetMaxId(to[3]); else if (now[3] > to[3]) ahead++;
        if (now[4] < to[4]) ReplacementEffect.dangerouslySetMaxId(to[4]); else if (now[4] > to[4]) ahead++;
        if (now[5] < to[5]) StaticAbility.dangerouslySetMaxId(to[5]); else if (now[5] > to[5]) ahead++;
        // CombatView counts down
        if (now[6] > to[6]) CombatView.dangerouslySetNextId(to[6]); else if (now[6] < to[6]) ahead++;
        return ahead;
    }
}
