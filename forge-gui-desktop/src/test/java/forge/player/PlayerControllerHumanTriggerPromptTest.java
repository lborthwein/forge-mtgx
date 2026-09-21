package forge.player;

import forge.game.card.Card;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/** Viewer-safe text for an already-triggered event, never a current hidden-zone lookup. */
public class PlayerControllerHumanTriggerPromptTest {
    @Test
    public void publicLkiUsesItsPublicEventIdentity() {
        final Card card = new Card(35, null);
        card.setName("Emrakul, the Aeons Torn");
        card.setLKICMC(15);
        card.setLastKnownZone(new Zone(ZoneType.Battlefield, null));

        assertEquals(PlayerControllerHuman.triggerSourceLabel(card), "Emrakul, the Aeons Torn");
    }

    @Test
    public void hiddenCurrentObjectIsNotRenderedAsTheTriggerSource() {
        final Card card = new Card(35, null);
        card.setName("Emrakul, the Aeons Torn");
        card.setZone(new Zone(ZoneType.Library, null));

        assertNull(PlayerControllerHuman.triggerSourceLabel(card));
    }
}
