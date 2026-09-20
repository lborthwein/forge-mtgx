package forge.interactive;

import com.google.gson.JsonObject;
import forge.bench.StateEncoder;
import forge.game.card.Card;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class InteractiveStateWireTest {
    private static final String BLOOD_RULES =
            "{1}, {T}, Discard a card, Sacrifice this token: Draw a card.";

    private static final class WireCard extends Card {
        private WireCard(final int id) {
            super(id, null);
        }

        @Override
        public int getCMC() {
            return 0;
        }
    }

    @Test
    public void serializesVisibleBloodTokenRulesText() {
        final Card blood = new WireCard(82);
        blood.setName("Blood Token");
        blood.setTokenCard(true);
        blood.setOracleText(BLOOD_RULES);

        final JsonObject encoded = StateEncoder.encodeCardUnchecked(blood);

        assertEquals(encoded.get("oracleText").getAsString(), BLOOD_RULES);
    }

    @Test
    public void redactionRemovesOracleTextFromFaceDownPublicShell() {
        final JsonObject encoded = new JsonObject();
        encoded.addProperty("fid", 82);
        encoded.addProperty("name", "Blood Token");
        encoded.addProperty("faceDown", true);
        encoded.addProperty("oracleText", BLOOD_RULES);

        InteractiveState.redactPrivateCard(encoded);

        assertEquals(encoded.get("name").getAsString(), "Face-down card");
        assertFalse(encoded.has("oracleText"));
    }
}
