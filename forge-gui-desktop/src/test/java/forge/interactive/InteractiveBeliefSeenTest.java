package forge.interactive;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * deck-guess-1021: the config line's optional {@code beliefSeen} (the opponent's cards from earlier games of the
 * match): absent = null, names trimmed, a bad entry refuses the config, and the config itself still parses.
 */
public class InteractiveBeliefSeenTest {

    private static final String BASE = "{\"protocol\":\"" + InteractiveProtocol.VERSION + "\",\"type\":\"config\",\"session\":\"s\","
            + "\"humanSeat\":0,\"seed\":1,\"aiProfile\":\"Default\",\"decks\":[\"/a.dck\",\"/b.dck\"]";

    @Test
    public void theSeenListIsOptionalAndChecked() throws Exception {
        Assert.assertNull(InteractiveProtocol.readBeliefSeen(BASE + "}"));
        Assert.assertNull(InteractiveProtocol.readBeliefSeen(BASE + ",\"beliefSeen\":null}"));
        Assert.assertEquals(InteractiveProtocol.readBeliefSeen(BASE + ",\"beliefSeen\":[\" Doomsday \",\"Thassa's Oracle\"]}"),
                List.of("Doomsday", "Thassa's Oracle"));
        Assert.assertEquals(InteractiveProtocol.readBeliefSeen(BASE + ",\"beliefSeen\":[]}"), List.of());
        for (String bad : new String[] {"[1]", "[\"\"]", "\"Doomsday\"", "[{\"name\":\"Doomsday\"}]"}) {
            Assert.assertThrows(InteractiveProtocol.ProtocolException.class,
                    () -> InteractiveProtocol.readBeliefSeen(BASE + ",\"beliefSeen\":" + bad + "}"));
        }
        Assert.assertEquals(InteractiveProtocol.readConfig(BASE + ",\"beliefSeen\":[\"Doomsday\"]}").seed(), 1L);
    }
}
