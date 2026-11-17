package forge.bench;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.ai.simulation.ExactStartKnown;
import forge.ai.simulation.ForcedWinSolver;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-exact-known-1117: an exact start's {@code known_top} (and {@code known_bottom}) teach the named seat, and only it,
 * the order of those own-library cards: its seat knowledge (what frames and the review sidecar's knownLibrary read) and
 * the live solver's pile memory (its known top). Absent keys teach nothing. Run with -Dforge.bench.exactStart=true.
 */
public class RlExactKnownTest extends AITest {
    static final String STATE = String.join("\n", "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20",
            "ailife=20", "humanhand=Counterspell", "humanlibrary=Island;Swamp;Forest;Mountain;Lightning Bolt;Ponder",
            "ailibrary=Island;Swamp;Forest");

    private static JsonObject game(final JsonObject exact) {
        final JsonObject g = new JsonObject();
        final JsonArray decks = new JsonArray();
        decks.add("/x/a.dck");
        decks.add("/x/b.dck");
        g.add("decks", decks);
        g.add("exact_start", exact);
        return g;
    }

    private static JsonObject exact(final Integer top, final Integer bottom) {
        final JsonObject e = new JsonObject();
        e.addProperty("schema", RlStartState.EXACT_SCHEMA);
        e.addProperty("seat", 0);
        e.addProperty("state", STATE);
        e.addProperty("stop", 50);
        if (top != null) {
            e.addProperty("known_top", top);
        }
        if (bottom != null) {
            e.addProperty("known_bottom", bottom);
        }
        return e;
    }

    private static int solverTop(final Player p) {
        final ForcedWinSolver s = ForcedWinSolver.withReader(p, new ForcedWinSolver.Config(), ForcedWinSolver.newSharedReader());
        return s.knownTopCount(p.getGame());
    }

    private RlKnowledgeTest.Fixture install(final JsonObject g) {
        Assert.assertNotNull(System.getProperty("forge.bench.exactStart"), "run with -Dforge.bench.exactStart=true");
        final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
        final RlStartState st = RlStartState.of(g, 1L);
        st.setKnowledge(f.know);
        st.hook(f.game).run();
        Assert.assertTrue(st.started().get("applied").getAsBoolean(), st.started().toString());
        return f;
    }

    @Test
    public void knownTopThreeTeachesTheSeatOnly() {
        final RlKnowledgeTest.Fixture f = install(game(exact(3, null)));
        final List<Card> lib = new ArrayList<>(f.p0.getCardsIn(ZoneType.Library));
        Assert.assertEquals(lib.size(), 6);
        Assert.assertEquals(lib.get(0).getName(), "Island"); // the state's order, top first
        final List<Integer> knownIds = new ArrayList<>();
        for (int i = 0; i < lib.size(); i++) {
            Assert.assertEquals(f.know.knows(0, lib.get(i)), i < 3, "seat 0, card " + i);
            Assert.assertFalse(f.know.knows(1, lib.get(i)), "the opponent learns nothing, card " + i);
            if (i < 3) {
                knownIds.add(lib.get(i).getId());
            }
        }
        // the live solver's pile memory: those three, in order, are its known top; the opponent has none
        final ExactStartKnown.Entry e = ExactStartKnown.get(f.game, f.p0);
        Assert.assertEquals(e.top, knownIds);
        Assert.assertTrue(e.bottom.isEmpty());
        Assert.assertNull(ExactStartKnown.get(f.game, f.p1));
        Assert.assertEquals(solverTop(f.p0), 3);
        Assert.assertEquals(solverTop(f.p1), 0);
        // the review sidecar's knownLibrary count is the seat knowledge's count over its library
        int known = 0;
        for (Card c : f.p0.getCardsIn(ZoneType.Library)) {
            known += f.know.knows(0, c) ? 1 : 0;
        }
        Assert.assertEquals(known, 3);
    }

    @Test
    public void knownBottom() {
        final RlKnowledgeTest.Fixture f = install(game(exact(1, 2)));
        final List<Card> lib = new ArrayList<>(f.p0.getCardsIn(ZoneType.Library));
        for (int i = 0; i < lib.size(); i++) {
            Assert.assertEquals(f.know.knows(0, lib.get(i)), i == 0 || i >= 4, "card " + i);
        }
        Assert.assertEquals(ExactStartKnown.get(f.game, f.p0).bottom.size(), 2);
        Assert.assertEquals(solverTop(f.p0), 1);
    }

    @Test
    public void absentKeysTeachNothing() {
        final RlKnowledgeTest.Fixture f = install(game(exact(null, null)));
        for (Card c : f.p0.getCardsIn(ZoneType.Library)) {
            Assert.assertFalse(f.know.knows(0, c));
        }
        Assert.assertNull(ExactStartKnown.get(f.game, f.p0));
        Assert.assertEquals(solverTop(f.p0), 0);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void negativeIsRefused() {
        RlStartState.of(game(exact(-1, null)), 1L);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unknownKeyIsRefused() {
        final JsonObject e = exact(3, null);
        e.addProperty("known_middle", 1);
        RlStartState.of(game(e), 1L);
    }

    @Test
    public void tooManyIsAnInstallError() {
        final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture();
        final RlStartState st = RlStartState.of(game(exact(5, 2)), 1L);
        st.setKnowledge(f.know);
        st.hook(f.game).run();
        Assert.assertFalse(st.started().get("applied").getAsBoolean());
    }
}
