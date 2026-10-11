package forge.game;

import java.util.List;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * One priority decision of the live game (mtgx lane forge-bot-tables-1011; nothing calls it unless a listener was added
 * with {@link Game#addPriorityDecisionListener}). {@link forge.game.phase.PhaseHandler}'s priority loop calls
 * {@link #beforeDecision} immediately before the priority player's controller chooses what to play
 * ({@code chooseSpellAbilityToPlay}) and {@link #afterDecision} immediately after it returns, before anything is played.
 * Each iteration of that loop is one decision: a player who casts a spell decides again in the same priority window
 * without a new {@code GameEventPlayerPriority}.
 *
 * <p>Listeners run on the game thread, between actions. They must not change the game. A game copy
 * ({@code GameCopier}) never has listeners.</p>
 */
public interface PriorityDecisionListener {
    /** {@code p} is about to choose what to play (or to pass). */
    void beforeDecision(Player p);

    /** {@code p} chose {@code chosen} (null: pass); nothing of it has been played yet. */
    void afterDecision(Player p, List<SpellAbility> chosen);
}
