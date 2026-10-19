package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;

/**
 * castable-glow-1019 (fork part, lane block-search-1019): The Rhinegold's "protection from everything until your next
 * turn" puts {@code Event$ DamageDone | Prevent$ True} on the player's "Keyword Effects" card in the command zone. Both
 * proof loops of {@link HumanManaAffordability} returned UNKNOWN (= payable) for ANY active DamageDone replacement, so
 * every card glowed for a turn cycle. A replacement that only prevents damage never changes the mana supply; one that
 * puts something in its place (Furnace of Rath's double damage) still makes the proof UNKNOWN.
 */
public class HumanManaAffordabilityPreventionTest extends AITest {

    private Game game;
    private Player me;

    private Card board() {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (String n : new String[] {"Swamp", "Swamp", "Island"}) {
            addCard(n, me).setTapped(true);
        }
        final Card arena = addCardToZone("Phyrexian Arena", me, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return arena;
    }

    private SpellAbility spell(final Card c) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    private void protectionFromEverything() {
        me.addChangedKeywords(List.of("Protection from everything"), List.of(), game.getNextTimestamp(), 0);
        game.getAction().checkStateEffects(true);
        final Card kw = me.getKeywordCard();
        assertTrue(kw.isInZone(ZoneType.Command), "the keyword card is in the command zone");
        assertTrue(kw.getReplacementEffects().stream().anyMatch(r -> r.getMode() == ReplacementType.DamageDone
                && "True".equals(r.getParam("Prevent"))), "the protection's prevention replacement is on it");
    }

    @Test
    public void everyLandTappedIsProvenUnaffordable() {
        final Card arena = board();
        assertEquals(HumanManaAffordability.assess(me, spell(arena)), Assessment.PROVEN_UNAFFORDABLE);
        assertEquals(HumanManaAffordability.assessPayable(me, spell(arena)), Assessment.PROVEN_UNAFFORDABLE);
    }

    @Test
    public void protectionFromEverythingNoLongerMakesTheProofUnknown() {
        final Card arena = board();
        protectionFromEverything();
        assertEquals(HumanManaAffordability.assess(me, spell(arena)), Assessment.PROVEN_UNAFFORDABLE,
                "a prevention-only replacement does not change the mana supply");
        assertEquals(HumanManaAffordability.assessPayable(me, spell(arena)), Assessment.PROVEN_UNAFFORDABLE);
    }

    @Test
    public void aDamageReplacementWithAnEffectStillMakesItUnknown() {
        final Card arena = board();
        addCard("Furnace of Rath", me);
        game.getAction().checkStateEffects(true);
        assertEquals(HumanManaAffordability.assess(me, spell(arena)), Assessment.UNKNOWN,
                "Furnace of Rath replaces damage with more damage: unchanged");
        assertEquals(HumanManaAffordability.assessPayable(me, spell(arena)), Assessment.UNKNOWN);
    }
}
