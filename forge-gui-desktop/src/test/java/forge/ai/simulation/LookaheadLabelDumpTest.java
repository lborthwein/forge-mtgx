package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AiFixes;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The label dump (lane ei-1004, expert iteration): off by default and silent; with a sink, one line per searched
 * decision carrying the candidate set, every candidate's per-world values, Forge's answer as candidate 0, the shadow
 * guard flags and the seat's ForgeState; and the decision, the live game and the counters are exactly those of the
 * same search without the sink.
 */
public class LookaheadLabelDumpTest extends SimulationTest {

    /** The zeroX board: one Forest, Pest Infestation (X = 0) and Grizzly Bears in hand, main phase 1. */
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
        addCard("Grizzly Bears", b);
        addCardToZone("Grizzly Bears", b, ZoneType.Hand);
        addCardToZone("Pest Infestation", a, ZoneType.Hand);
        addCardToZone("Llanowar Elves", a, ZoneType.Hand);
        addCardToZone("Forest", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch search() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_510_000L;
        c.zeroX = AiFixes.Mode.SHADOW;
        c.deadEtb = AiFixes.Mode.SHADOW;
        return new LookaheadSearch(c);
    }

    private static String names(List<SpellAbility> d) {
        if (d == null || d.isEmpty()) {
            return "pass";
        }
        final Card h = d.get(0).getHostCard();
        return h.getName() + ":" + d.get(0);
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch s = search();
        try {
            AssertJUnit.assertFalse(s.getStats().labelsOn);
            AssertJUnit.assertFalse(s.getStats().toJson().has("labels"));
            AssertJUnit.assertFalse(s.getConfig().toJson().has("labels"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void sinkChangesNothingAndRecordsTheDecision() {
        Game g1 = board();
        Player a1 = g1.getPlayers().get(0);
        PlayerControllerAi c1 = (PlayerControllerAi) a1.getController();
        Game g2 = board();
        Player a2 = g2.getPlayers().get(0);
        PlayerControllerAi c2 = (PlayerControllerAi) a2.getController();
        LookaheadSearch off = search(), on = search();
        final List<JsonObject> lines = new ArrayList<>();
        on.setLabelSink(lines::add);
        try {
            List<SpellAbility> d1 = off.decide(c1, c1.chooseSpellAbilityToPlay());
            String fp1 = LookaheadSearch.fingerprint(g1);
            List<SpellAbility> d2 = on.decide(c2, c2.chooseSpellAbilityToPlay());
            String fp2 = LookaheadSearch.fingerprint(g2);
            AssertJUnit.assertEquals(names(d1), names(d2));
            AssertJUnit.assertEquals(fp1, fp2);
            AssertJUnit.assertEquals(off.getStats().searched, on.getStats().searched);
            AssertJUnit.assertEquals(off.getStats().departed, on.getStats().departed);
            AssertJUnit.assertEquals(off.getStats().rollouts, on.getStats().rollouts);
            AssertJUnit.assertEquals(off.getStats().steps, on.getStats().steps);
            AssertJUnit.assertEquals(off.getStats().zeroXCands, on.getStats().zeroXCands);

            AssertJUnit.assertEquals(1, on.getStats().searched);
            AssertJUnit.assertEquals(1, lines.size());
            AssertJUnit.assertEquals(1, on.getStats().labels);
            AssertJUnit.assertEquals(0, on.getStats().labelFailures);
            JsonObject l = lines.get(0);
            AssertJUnit.assertEquals(LookaheadSearch.LABEL_SCHEMA, l.get("schema").getAsString());
            AssertJUnit.assertEquals(2, l.get("worlds").getAsInt());
            AssertJUnit.assertTrue(l.get("active").getAsBoolean());
            AssertJUnit.assertEquals(0, l.get("seat").getAsInt());
            JsonArray cands = l.getAsJsonArray("cands");
            AssertJUnit.assertEquals(on.lastCandidates.size(), cands.size());
            AssertJUnit.assertTrue(cands.size() >= 2);
            AssertJUnit.assertTrue(cands.get(0).getAsJsonObject().get("isDefault").getAsBoolean());
            boolean zx = false;
            for (int c = 0; c < cands.size(); c++) {
                JsonObject co = cands.get(c).getAsJsonObject();
                AssertJUnit.assertEquals(on.lastCandidates.get(c).key(), co.get("key").getAsString());
                AssertJUnit.assertEquals(2, co.getAsJsonArray("v").size());
                zx |= co.has("zeroX") && co.get("label").getAsString().startsWith("Pest Infestation");
            }
            AssertJUnit.assertTrue("the X = 0 Pest Infestation candidate is flagged (shadow)", zx);
            int best = l.get("best").getAsInt();
            AssertJUnit.assertTrue(best >= 0 && best < cands.size());
            // The root is the seat's own view: its hand in full, the opponent's hand as a count.
            JsonObject root = l.getAsJsonObject("root");
            AssertJUnit.assertEquals(0, root.get("seat").getAsInt());
            AssertJUnit.assertEquals(3, root.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("hand").size());
            AssertJUnit.assertEquals(0, root.getAsJsonArray("players").get(1).getAsJsonObject().getAsJsonArray("hand").size());
            AssertJUnit.assertEquals(1, root.getAsJsonArray("players").get(1).getAsJsonObject().get("handSize").getAsInt());
            AssertJUnit.assertTrue(on.getStats().toJson().getAsJsonObject("labels").get("lines").getAsLong() == 1);
        } finally {
            off.shutdown();
            on.shutdown();
        }
    }

    @Test
    public void encodeInCopyLeavesTheGameUntouched() {
        Game g = board();
        Player a = g.getPlayers().get(0);
        String before = LookaheadSearch.fingerprint(g);
        JsonObject st = LookaheadSearch.encodeInCopy(g, a, 42L);
        AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(g));
        AssertJUnit.assertNotNull(st);
        AssertJUnit.assertEquals(0, st.get("seat").getAsInt());
        AssertJUnit.assertEquals(3, st.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("hand").size());
        AssertJUnit.assertEquals(0, st.getAsJsonArray("players").get(1).getAsJsonObject().getAsJsonArray("hand").size());
        AssertJUnit.assertEquals(st.toString(), LookaheadSearch.encodeInCopy(g, a, 42L).toString());
    }

    @Test
    public void targetRefsOfAChain() {
        Game g = board();
        Player b = g.getPlayers().get(1);
        AssertJUnit.assertEquals(0, LookaheadSearch.targetRefs(g, null).size());
        AssertJUnit.assertEquals(0, LookaheadSearch.targetRefs(null, new ArrayList<>()).size());
        AssertJUnit.assertEquals("P1", LookaheadSearch.targetRef(g, b));
    }
}
