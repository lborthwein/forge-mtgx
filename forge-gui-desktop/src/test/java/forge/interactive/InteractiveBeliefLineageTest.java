package forge.interactive;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * belief-jar-1027 (review S1-1, owner ruling: refuse by LINEAGE): the config line's optional {@code beliefLineage}, the
 * human seat's deck lineage tokens (draft, pod, saved deck, match) that belief=posterior's honesty check reads.
 */
public class InteractiveBeliefLineageTest {
    private static final String BASE = "{\"protocol\":\"" + InteractiveProtocol.VERSION + "\",\"type\":\"config\",\"session\":\"s\","
            + "\"humanSeat\":0,\"seed\":1,\"aiProfile\":\"Default\",\"decks\":[\"/a.dck\",\"/b.dck\"]";

    @Test
    public void lineageTokensParseOrRefuse() throws Exception {
        Assert.assertNull(InteractiveProtocol.readBeliefLineage(BASE + "}"));
        Assert.assertNull(InteractiveProtocol.readBeliefLineage(BASE + ",\"beliefLineage\":null}"));
        Assert.assertEquals(InteractiveProtocol.readBeliefLineage(BASE
                + ",\"beliefLineage\":[\"draft:draft-mvr4zf4t-86ju\",\"deck:deck-mvr4zf1k-dp60\",\"pod:pod-1\",\"match:m7\"]}"),
                List.of("draft:draft-mvr4zf4t-86ju", "deck:deck-mvr4zf1k-dp60", "pod:pod-1", "match:m7"));
        final StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 65; i++) {
            many.append(i == 0 ? "" : ",").append("\"match:").append(i).append('"');
        }
        for (String bad : new String[] {"\"draft:x\"", "[\"saved:x\"]", "[\"draft:\"]", "[\"draft:a b\"]", "[1]", many + "]"}) {
            Assert.assertThrows(InteractiveProtocol.ProtocolException.class,
                    () -> InteractiveProtocol.readBeliefLineage(BASE + ",\"beliefLineage\":" + bad + "}"));
        }
    }
}
