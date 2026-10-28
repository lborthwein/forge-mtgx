package forge.ai;

import java.util.Collection;
import java.util.function.Supplier;

import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.spellability.SpellAbility;

/**
 * mtgx lane lessons-forge-jar-1027: a seat whose real mana payments may name their sources (a lesson's scripted seat,
 * {@link forge.ai.lesson.LessonScriptController}: "cast it, paying with these lands"). {@link ComputerUtilMana}'s real
 * payment loop asks it for each source it taps; any other seat (every seat outside a lesson) is never asked, and its
 * payment is Forge AI's own, unchanged.
 */
public interface ScriptedManaSources {
    /**
     * The mana ability to tap for one shard of {@code paying}'s cost, from {@code options} (the ones Forge AI may use for
     * that shard); {@code ai} is Forge AI's own choice, for any shard the script does not name.
     */
    SpellAbility chooseManaSource(SpellAbility paying, Collection<SpellAbility> options, Supplier<SpellAbility> ai);

    /** The payer's scripted seat (also under the AI tape's controller), or null: then nothing changes. */
    static ScriptedManaSources of(final Player payer) {
        if (payer == null) {
            return null;
        }
        PlayerController pc = payer.getController();
        if (pc instanceof AiTapeController tape) {
            pc = tape.inner();
        }
        return pc instanceof ScriptedManaSources s ? s : null;
    }
}
