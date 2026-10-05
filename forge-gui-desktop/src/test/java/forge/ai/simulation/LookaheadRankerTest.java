package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The EI ranker seat (lane ei-1004): the ranker's pick is played (departure bookkeeping as the search's); shadow calls
 * and counts but plays Forge's answer; a failed call plays Forge's answer; the placebo needs no service; the request
 * carries the seat's root, the decision context and every candidate with its targets; the live game is untouched.
 */
public class LookaheadRankerTest extends SimulationTest {

    /** Main phase 1 with a Forest untapped: Grizzly Bears castable, Llanowar Elves castable, a land drop available. */
    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Forest" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        addCard("Forest", a);
        addCard("Forest", a);
        addCardToZone("Llanowar Elves", a, ZoneType.Hand);
        addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        addCardToZone("Forest", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** A mock service: answers with p = 1 on candidate {@code want} (clamped to the set), records the requests. */
    static final class Mock extends RankerClient {
        final List<JsonObject> requests = new ArrayList<>();
        final int want;
        final boolean fail;

        Mock(int want, boolean fail) {
            super("http://127.0.0.1:1", 1000);
            this.want = want;
            this.fail = fail;
        }

        @Override
        protected String post(String path, String body) throws Exception {
            final JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            requests.add(req);
            if (fail) {
                throw new java.io.IOException("down");
            }
            final int n = req.getAsJsonArray("cands").size();
            final JsonArray ch = new JsonArray();
            for (int i = 0; i < n; i++) {
                ch.add(i == Math.min(want, n - 1) ? 1.0 : 0.0);
            }
            final JsonObject o = new JsonObject();
            o.addProperty("schema", RESPONSE_SCHEMA);
            o.add("choice", ch);
            return o.toString();
        }
    }

    private static LookaheadSearch.Config cfg(boolean shadow, double placebo) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 1;
        c.breadth = 4;
        c.threads = 1;
        c.seed = 719_532_000L;
        if (placebo > 0) {
            c.placeboRate = placebo;
        } else {
            c.rankerUrl = "http://127.0.0.1:1";
            c.rankerCheckpointSha256 = "test";
        }
        c.rankerShadow = shadow;
        return c;
    }

    private static String desc(List<SpellAbility> d) {
        return d == null || d.isEmpty() ? "pass" : d.get(0).getHostCard().getName() + ":" + d.get(0);
    }

    @Test
    public void offIsSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertFalse(c.rankerOn());
        AssertJUnit.assertFalse(c.toJson().has("ranker"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("ranker"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void picksArePlayedShadowAndFailureKeepForge() {
        // the ranker that always picks candidate 1 (pass when Forge acts, else the next candidate)
        Game g1 = board();
        Player a1 = g1.getPlayers().get(0);
        PlayerControllerAi c1 = (PlayerControllerAi) a1.getController();
        List<SpellAbility> forge1 = c1.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(g1);
        Mock m1 = new Mock(1, false);
        LookaheadSearch s1 = new LookaheadSearch(cfg(false, 0), m1);
        Game g2 = board();
        Player a2 = g2.getPlayers().get(0);
        PlayerControllerAi c2 = (PlayerControllerAi) a2.getController();
        Mock m2 = new Mock(1, false);
        LookaheadSearch s2 = new LookaheadSearch(cfg(true, 0), m2);
        Game g3 = board();
        Player a3 = g3.getPlayers().get(0);
        PlayerControllerAi c3 = (PlayerControllerAi) a3.getController();
        Mock m3 = new Mock(1, true);
        LookaheadSearch s3 = new LookaheadSearch(cfg(false, 0), m3);
        try {
            List<SpellAbility> got = s1.decide(c1, forge1);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g1));
            AssertJUnit.assertEquals(1, m1.requests.size());
            JsonObject req = m1.requests.get(0);
            AssertJUnit.assertEquals(RankerClient.REQUEST_SCHEMA, req.get("schema").getAsString());
            AssertJUnit.assertEquals(0, req.get("seat").getAsInt());
            AssertJUnit.assertEquals("MAIN1", req.getAsJsonObject("decision").get("phase").getAsString());
            AssertJUnit.assertTrue(req.getAsJsonObject("root").has("players"));
            JsonArray ca = req.getAsJsonArray("cands");
            AssertJUnit.assertTrue(ca.size() >= 2);
            AssertJUnit.assertTrue(ca.get(0).getAsJsonObject().get("isDefault").getAsBoolean());
            AssertJUnit.assertEquals(1, s1.getStats().rankerNonDefault);
            AssertJUnit.assertEquals(1, s1.getStats().departed);
            AssertJUnit.assertFalse(desc(forge1).equals(desc(got)));

            List<SpellAbility> sh = s2.decide(c2, c2.chooseSpellAbilityToPlay());
            AssertJUnit.assertEquals(desc(forge1), desc(sh));
            AssertJUnit.assertEquals(1, s2.getStats().rankerNonDefault);
            AssertJUnit.assertEquals(0, s2.getStats().departed);

            List<SpellAbility> fl = s3.decide(c3, c3.chooseSpellAbilityToPlay());
            AssertJUnit.assertEquals(desc(forge1), desc(fl));
            AssertJUnit.assertEquals(1, s3.getStats().rankerFailures);
            AssertJUnit.assertEquals(0, s3.getStats().departed);
            AssertJUnit.assertTrue(s1.getStats().toJson().getAsJsonObject("ranker").get("calls").getAsLong() == 1);
        } finally {
            s1.shutdown();
            s2.shutdown();
            s3.shutdown();
        }
    }

    @Test
    public void placeboNeedsNoServiceAndReplays() {
        Game g1 = board();
        Player a1 = g1.getPlayers().get(0);
        PlayerControllerAi c1 = (PlayerControllerAi) a1.getController();
        Game g2 = board();
        Player a2 = g2.getPlayers().get(0);
        PlayerControllerAi c2 = (PlayerControllerAi) a2.getController();
        LookaheadSearch p1 = new LookaheadSearch(cfg(false, 1.0)), p2 = new LookaheadSearch(cfg(false, 1.0));
        try {
            List<SpellAbility> d1 = p1.decide(c1, c1.chooseSpellAbilityToPlay());
            List<SpellAbility> d2 = p2.decide(c2, c2.chooseSpellAbilityToPlay());
            AssertJUnit.assertEquals(desc(d1), desc(d2));
            AssertJUnit.assertEquals(1, p1.getStats().placeboDraws);
            AssertJUnit.assertEquals(1, p1.getStats().departed);
            AssertJUnit.assertEquals(0, p1.getStats().rankerCalls);
        } finally {
            p1.shutdown();
            p2.shutdown();
        }
    }
}
