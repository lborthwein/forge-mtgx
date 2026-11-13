package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * untested-keys-1113 (lookahead holdDeploy): which "hold, then deploy" candidates a decision gets. The card qualifies by
 * Forge's own timing check only (a permanent spell {@link SpellAbility#withFlash} lets us cast outside our main phase);
 * the windows are the opponent's beginning of combat and end step not yet reached. Card facts are test data only (the
 * owner's G15 frames of {@link UntestedKeysG15Test}). Play-outs are not run here (they need the policy service).
 */
public class HoldDeployTest extends forge.ai.AITest {

    /** The given list as the policy seat would send it: pass first, then every castable spell in hand. */
    static List<SpellAbility> given(final Game g, final Player me) {
        final List<SpellAbility> out = new ArrayList<>();
        out.add(null);
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getSpellAbilities()) {
                sa.setActivatingPlayer(me);
                if (sa.isSpell() && sa.canPlay()) {
                    out.add(sa);
                }
            }
        }
        return out;
    }

    /** The hold candidates' labels at the frame's decision (holdDeploy on). */
    static List<String> holds(final Game g) {
        final Player me = g.getPlayers().get(0);
        final LookaheadSearch.Config cfg = new LookaheadSearch.Config();
        cfg.holdDeploy = true;
        final LookaheadSearch ls = new LookaheadSearch(cfg);
        try {
            final List<SpellAbility> given = given(g, me);
            final List<LookaheadSearch.Cand> all = new ArrayList<>(), cands = new ArrayList<>();
            final List<Integer> allAt = new ArrayList<>(), at = new ArrayList<>();
            for (int i = 0; i < given.size(); i++) {
                all.add(new LookaheadSearch.Cand(given.get(i), i == 0));
                allAt.add(i);
            }
            ls.addHoldDeploy(g, me, given, all, allAt, cands, at, g.getPhaseHandler(), false);
            final List<String> out = new ArrayList<>();
            for (int i = 0; i < cands.size(); i++) {
                final LookaheadSearch.Cand c = cands.get(i);
                Assert.assertTrue(c.pass && c.deploy != null && at.get(i) == 0, "a hold is played live as the pass");
                out.add(c.label + " turn " + c.deploy.turn);
            }
            System.out.println("HOLDDEPLOY turn=" + g.getPhaseHandler().getTurn() + " " + g.getPhaseHandler().getPhase()
                    + " given=" + given.size() + " holds=" + out + " stats=" + ls.getStats().holdDeployCands);
            return out;
        } finally {
            ls.shutdown();
        }
    }

    /** G15 step 0 (our main phase, land played): the flash creature holds for their combat and their end step; nothing else. */
    @Test
    public void step0TheFlashCreatureGetsBothWindows() {
        final List<String> h = holds(UntestedKeysG15Test.game(UntestedKeysG15Test.step0()));
        Assert.assertEquals(h.size(), 2, h.toString());
        Assert.assertTrue(h.get(0).contains("Pestermite") && h.get(0).contains("COMBAT_BEGIN") && h.get(0).endsWith("turn 10"), h.toString());
        Assert.assertTrue(h.get(1).contains("Pestermite") && h.get(1).contains("END_OF_TURN") && h.get(1).endsWith("turn 10"), h.toString());
    }

    /** Their turn: only the windows not yet reached (at their beginning of combat: their end step; at their end step: none). */
    @Test
    public void theirTurnOnlyTheWindowsAhead() {
        final List<String> c = holds(UntestedKeysG15Test.game(UntestedKeysG15Test.theirTurn("COMBAT_BEGIN", 16)));
        Assert.assertEquals(c.size(), 1, c.toString());
        Assert.assertTrue(c.get(0).contains("END_OF_TURN") && c.get(0).endsWith("turn 10"), c.toString());
        Assert.assertTrue(holds(UntestedKeysG15Test.game(UntestedKeysG15Test.theirTurn("END_OF_TURN", 16))).isEmpty());
        final List<String> u = holds(UntestedKeysG15Test.game(UntestedKeysG15Test.theirTurn("UPKEEP", 16)));
        Assert.assertEquals(u.size(), 2, u.toString());
    }

    /** Our main phase with the land drop unused and a land in hand: no hold (the hold passes the rest of the turn). */
    @Test
    public void noHoldBeforeTheLandDrop() {
        final String f = UntestedKeysG15Test.step0().replace("humanlandsplayed=1", "humanlandsplayed=0")
                .replace("humanhand=Pestermite;", "humanhand=Island;Pestermite;");
        Assert.assertTrue(holds(UntestedKeysG15Test.game(f)).isEmpty());
    }

    /** Keys off: the config's JSON is unchanged by the key's presence at false (the search reads nothing new). */
    @Test
    public void keysOffConfigIsUnchanged() {
        final LookaheadSearch.Config a = new LookaheadSearch.Config(), b = new LookaheadSearch.Config();
        b.holdDeploy = false;
        Assert.assertEquals(b.toJson().toString(), a.toJson().toString());
        Assert.assertFalse(a.toJson().has("holdDeploy"));
        b.holdDeploy = true;
        Assert.assertTrue(b.toJson().get("holdDeploy").getAsBoolean());
    }
}
