package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlSearch;
import forge.game.Game;

/**
 * knowledge-same-name-1118: the knowledgeSameName key in the policy seat's search spec ({@link RlLiveSeat.Spec#parse}).
 * Today's live string (the deadPump fixture d0cd5c83 plus lethalBlock, deadFaceUp, knownHand and attacks: cb96edb2;
 * its card index a temp file) with the key absent parses to the same search JSON, byte for byte (its sha256 pinned from
 * the live jar forge-guards-e3de95d2ab8), and so with the key explicitly off; on adds only the key and reaches the seat's
 * tracker ({@link RlLiveSeat#newKnowledge}). The K8 look-ahead seat has no RlKnowledge, so its spec has no such key.
 */
public class KnowledgeSameNameLiveKeyTest extends forge.ai.AITest {

    /** The live policy-search string of 2026-11-18 (cb96edb2) after server, policySha and cardIndex. */
    static final String LIVE_1118_REST = AttackHoldbackLiveKeyTest.LIVE_1117_REST
            + ",lethalBlock=on,deadFaceUp=on,knownHand=on,attacks=1";

    /** sha256 of the search JSON of {@link #live} with no extra key, on the live jar forge-guards-e3de95d2ab8 (ae01ac2e). */
    static final String BASE_JSON_SHA256 = "710e3db8b651456ecd4d638c2c41b0bbee62e9b554e5df21ba423b943855c61c";

    static String live(final String extra) throws Exception {
        final java.nio.file.Path index = java.nio.file.Files.createTempFile("ksn-index-", ".tsv");
        return "server=127.0.0.1:5420,policySha=3b6747940fe2d6d4e0921fd30d90c1bfa7bb27434ebd83a08ff9d3bb51b39071,cardIndex="
                + index + "," + LIVE_1118_REST + (extra.isEmpty() ? "" : "," + extra);
    }

    /** Key absent: today's live string's search JSON is the live jar's, byte for byte. */
    @Test
    public void theLiveStringParsesAsOnTheLiveJar() throws Exception {
        final String json = RlLiveSeat.Spec.parse(live("")).search.toJson().toString();
        final String sha = AttackHoldbackLiveKeyTest.sha256(json);
        System.out.println("[knowledge-same-name-identity] live 1118 search JSON sha256 " + sha + " (" + json.length()
                + " chars)");
        Assert.assertEquals(sha, BASE_JSON_SHA256, "the key absent leaves the live search JSON unchanged");
    }

    @Test
    public void theKeyIsOffUnlessSet() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(live(""));
        Assert.assertFalse(off.search.knowledgeSameName);
        Assert.assertFalse(off.search.toJson().has("knowledgeSameName"));
        final RlLiveSeat.Spec explicitOff = RlLiveSeat.Spec.parse(live("knowledgeSameName=off"));
        Assert.assertEquals(explicitOff.search.toJson().toString(), off.search.toJson().toString());
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(live("knowledgeSameName=on"));
        Assert.assertTrue(on.search.knowledgeSameName);
        final JsonObject onJson = on.search.toJson();
        Assert.assertTrue(onJson.get("knowledgeSameName").getAsBoolean());
        onJson.remove("knowledgeSameName");
        Assert.assertEquals(onJson.toString(), off.search.toJson().toString(), "on adds the key and nothing else");
        for (String bad : new String[] {"true", "false", "1", "0", "On", "shadow", ""}) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> RlLiveSeat.Spec.parse(live("knowledgeSameName=" + bad)));
        }
    }

    /** The seat's tracker, as bind makes it: the key on arms it, absent or off leaves it off (obs-v2 either way). */
    @Test
    public void theKeyReachesTheSeatTracker() throws Exception {
        final Game g = initAndCreateGame();
        final RlKnowledge off = RlLiveSeat.newKnowledge(g, RlLiveSeat.Spec.parse(live("")));
        final RlKnowledge explicitOff = RlLiveSeat.newKnowledge(g, RlLiveSeat.Spec.parse(live("knowledgeSameName=off")));
        final RlKnowledge on = RlLiveSeat.newKnowledge(g, RlLiveSeat.Spec.parse(live("knowledgeSameName=on")));
        Assert.assertFalse(off.sameName);
        Assert.assertFalse(explicitOff.sameName);
        Assert.assertTrue(on.sameName);
        Assert.assertTrue(off.v2 && on.v2, "obs=2: observation v2 as before");
        Assert.assertSame(on.game(), g);
        // the forked tracker (a look-ahead copy's) carries it
        Assert.assertTrue(on.forkFor(g).sameName);
        Assert.assertFalse(off.forkFor(g).sameName);
    }

    /** The bench JSON spec: the key round-trips, absent when off. */
    @Test
    public void theBenchSpecRoundTrips() {
        final JsonObject o = new JsonObject();
        o.addProperty("server", "127.0.0.1:1");
        Assert.assertFalse(RlSearch.Config.parse(o).knowledgeSameName);
        Assert.assertFalse(RlSearch.Config.parse(o).toJson().has("knowledgeSameName"));
        o.addProperty("knowledgeSameName", true);
        final RlSearch.Config c = RlSearch.Config.parse(o);
        Assert.assertTrue(c.knowledgeSameName);
        Assert.assertTrue(c.toJson().get("knowledgeSameName").getAsBoolean());
        final JsonObject back = c.toJson();
        back.addProperty("server", "127.0.0.1:1"); // the JSON carries no server
        Assert.assertTrue(RlSearch.Config.parse(back).knowledgeSameName);
    }

    /** The K8 parser ignores keys it does not know: the key arms nothing there and leaves its JSON unchanged. */
    @Test
    public void theK8SpecHasNoSuchKey() {
        final InteractiveMain.ParsedLookahead k8 = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        final InteractiveMain.ParsedLookahead with = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8
                + ",knowledgeSameName=on");
        Assert.assertEquals(with.config.toJson().toString(), k8.config.toJson().toString());
    }
}
