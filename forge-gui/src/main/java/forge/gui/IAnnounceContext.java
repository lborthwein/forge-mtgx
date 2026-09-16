package forge.gui;

import forge.game.spellability.SpellAbility;

/**
 * Lets a GUI learn which ability an announced value belongs to.
 *
 * {@code IGuiGame.chooseManaX} carries only a message and bounds, which is all
 * a human needs and not nearly enough for a GUI that wants to ask Forge's own
 * AI what the value should be. A GUI that implements this is told the ability
 * immediately before the call and told again, with nulls, immediately after —
 * so the context can never outlive the question it belongs to.
 *
 * Implementing it is optional; Forge's own GUIs do not, and nothing changes for
 * them.
 */
public interface IAnnounceContext {
    void setPendingAnnounce(SpellAbility ability, String announce);
}
