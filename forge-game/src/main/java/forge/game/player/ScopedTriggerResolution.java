package forge.game.player;

import forge.game.trigger.WrappedAbility;

/** Optional controller integration boundary, not an AI decision. Implementations
 * must invoke the supplied native resolution exactly once within this call. */
public interface ScopedTriggerResolution {
    boolean requiresTriggerResolutionScope(WrappedAbility ability);
    void withTriggerResolutionScope(WrappedAbility ability, Runnable nativeResolution);
}
