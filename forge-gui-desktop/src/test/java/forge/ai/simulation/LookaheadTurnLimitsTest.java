package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

/** The published p9 solver repair does not arm the separate look-ahead copier. */
public class LookaheadTurnLimitsTest extends SimulationTest {
    private static StaticAbility mayPlay(Card c) {
        for (StaticAbility st : c.getStaticAbilities()) if (st.hasParam("MayPlayLimit")) return st;
        throw new AssertionError("No limited static");
    }
    private static Card named(Game g, String name) {
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals(name)) return c;
        throw new AssertionError(name);
    }
    private static int activations(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) if (sa.isActivatedAbility()) return sa.getActivationsThisTurn();
        throw new AssertionError("No activated ability");
    }
    private Game live() {
        Game game = initAndCreateGame();
        Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        Card lurrus = addCard("Lurrus of the Dream-Den", me);
        Card walla = addCard("Rootwalla", me);
        walla.setSickness(false);
        game.getAction().checkStateEffects(true);
        mayPlay(lurrus).incMayPlayTurn();
        for (SpellAbility sa : walla.getSpellAbilities()) if (sa.isActivatedAbility()) walla.addAbilityActivated(sa);
        return game;
    }
    private static void counts(Game g, int n) {
        Assert.assertEquals(mayPlay(named(g, "Lurrus of the Dream-Den")).getMayPlayTurn(), n);
        Assert.assertEquals(activations(named(g, "Rootwalla")), n);
    }
    private Game prepare(LookaheadSearch search, Game live) {
        LookaheadSearch.Prepared p = search.prepare(live, live.getPlayers().get(1),
                new LookaheadSearch.Cand((SpellAbility) null, true), null, 73, false, null);
        Assert.assertFalse(p.failed, String.valueOf(p.error));
        return p.g;
    }
    @Test public void lookaheadReproducesOffAndPreservesBothLimitsOn() {
        Game live = live();
        counts(live, 1);
        for (boolean enabled : new boolean[]{false, true}) {
            LookaheadSearch.Config cfg = new LookaheadSearch.Config();
            cfg.threads = 1;
            cfg.copyTurnLimits = enabled;
            LookaheadSearch search = new LookaheadSearch(cfg);
            try {
                Game copy = prepare(search, live);
                counts(copy, enabled ? 1 : 0);
                counts(prepare(search, copy), enabled ? 1 : 0);
                // Subsequent solver copies already use published p9. They cannot recover a count
                // that the initial look-ahead copy lost, and must not increment it twice.
                GameCopier solver = new GameCopier(copy, true);
                solver.setTurnLimits(true);
                counts(solver.makeCopy(), enabled ? 1 : 0);
            } finally { search.shutdown(); }
            counts(live, 1);
        }
    }
    @Test public void defaultOffAndRoundTripIndependentOfBelief() {
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        forge.bench.rl.RlSearch.Config off = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertEquals(off.copyTurnLimits, LookaheadSearch.LIVE_COPY_TURN_LIMITS); // live-fixes-1107: default follows forge.liveFixes
        Assert.assertEquals(off.toJson().has("copyTurnLimits"), LookaheadSearch.LIVE_COPY_TURN_LIMITS);
        spec.addProperty("copyTurnLimits", true);
        forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertTrue(on.copyTurnLimits);
        Assert.assertTrue(on.toJson().get("copyTurnLimits").getAsBoolean());
        LookaheadSearch.Config look = new LookaheadSearch.Config();
        Assert.assertEquals(look.toJson().has("copyTurnLimits"), LookaheadSearch.LIVE_COPY_TURN_LIMITS);
        look.copyTurnLimits = true;
        Assert.assertTrue(look.toJson().get("copyTurnLimits").getAsBoolean());
    }
}
