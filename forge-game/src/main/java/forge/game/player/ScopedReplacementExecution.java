package forge.game.player;

import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;

/** Optional controller boundary around the exact replacement selected by the
 * engine, after any optional confirmation. Does not select a replacement. */
public interface ScopedReplacementExecution {
    void withReplacementExecutionScope(ReplacementEffect replacement, SpellAbility ability, Runnable nativeExecution);
}
