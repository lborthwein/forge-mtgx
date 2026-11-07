package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.bench.PlayerControllerBridge;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.HiddenExile;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.StepSearch;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * Lane bvw-step1-build-1024, PREDECLARATION-step1 OPEN-6 (ruled 2026-10-24): a face-down exiled card the labelling seat
 * may not look at, read on a COPY from that seat's view ({@link HiddenExile#scan}), makes the band row unsupported (no
 * search, no target, the proof withheld). Shelldock Isle's hideaway and Bomat Courier's attack exile are played by the
 * rules here, not set up: the hideaway card is hidden from the opponent only (the copy keeps the owner's look), Bomat
 * Courier's from both seats. Fixture positions only.
 */
public class BvwHiddenExileTest extends SimulationTest {

    static final String LIB = "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;Island";

    static String frame(final String humanHand, final String humanBattlefield, final String humanExile,
            final String aiBattlefield, final String aiExile) {
        return "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\nhumanlandsplayed=0\n"
                + "ailandsplayed=0\naihand=\nailibrary=" + LIB + "\naigraveyard=\naiexile=" + aiExile + "\naibattlefield="
                + aiBattlefield + "\nhumanhand=" + humanHand + "\nhumanbattlefield=" + humanBattlefield
                + "\nhumangraveyard=\nhumanexile=" + humanExile + "\nhumanlibrary=" + LIB + "\n";
    }

    static Card faceDownExile(final Player p) {
        for (Card c : p.getCardsIn(ZoneType.Exile)) {
            if (c.isFaceDown()) {
                return c;
            }
        }
        return null;
    }

    /** The scan of a copy of {@code g} from seat {@code s}'s view. */
    static JsonArray scanCopy(final Game g, final int s) {
        final GameCopier copier = new GameCopier(g, true);
        copier.setCopyFidelity(1);
        final Game c = copier.makeCopy();
        return HiddenExile.scan(c, (Player) copier.find(g.getPlayers().get(s)));
    }

    @Test(timeOut = 600000)
    public void shelldockIsleHideawayIsHiddenFromTheOpponentAndOnTheCopy() {
        // our MAIN1 with Shelldock Isle in hand: Forge AI plays it, its hideaway trigger exiles one of the top four
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame("Shelldock Isle", "", "", "", ""));
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        Assert.assertFalse(HiddenExile.anyFaceDownExile(g));
        Assert.assertEquals(scanCopy(g, 1).size(), 0, "nothing exiled yet");
        ForcedWinSolverTest.play(g, 7);
        final Card hidden = faceDownExile(me);
        Assert.assertNotNull(hidden, "the hideaway card is exiled face down");
        Assert.assertEquals(hidden.getExiledWith().getName(), "Shelldock Isle");
        Assert.assertTrue(hidden.mayPlayerLook(me) && !hidden.mayPlayerLook(opp), "live: the owner may look, they may not");
        Assert.assertTrue(HiddenExile.anyFaceDownExile(g));
        final JsonArray theirs = scanCopy(g, 1);
        Assert.assertEquals(theirs.size(), 1, theirs.toString());
        final JsonObject e = theirs.get(0).getAsJsonObject();
        Assert.assertEquals(e.get("owner").getAsString(), "opponent");
        Assert.assertEquals(e.get("exiled_with").getAsString(), "Shelldock Isle");
        final String real = hidden.getPaperCard().getName(); // a face-down card's own name is empty
        Assert.assertEquals(real, "Island");
        Assert.assertFalse(e.toString().contains(real), "the scan never names the hidden card");
        // the copy keeps the owner's look at its hideaway card: from the owner's view nothing is hidden
        final JsonArray ours = scanCopy(g, 0);
        Assert.assertEquals(ours.size(), 0, ours.toString());
    }

    @Test(timeOut = 600000)
    public void bomatCourierAttackExileIsHiddenFromBothSeats() {
        // our MAIN1, a hasty Bomat Courier against an empty board: it attacks, its trigger exiles our top card face down
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame("", "Bomat Courier;Mountain", "", "", ""));
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        ForcedWinSolverTest.play(g, 7);
        Assert.assertEquals(opp.getLife(), 19, "Bomat Courier attacked");
        final Card hidden = faceDownExile(me);
        Assert.assertNotNull(hidden, "the attack trigger exiled our top card face down");
        Assert.assertFalse(hidden.mayPlayerLook(opp));
        for (int s = 0; s < 2; s++) {
            final JsonArray x = scanCopy(g, s);
            Assert.assertEquals(x.size(), 1, "seat " + s + ": " + x);
            final JsonObject e = x.get(0).getAsJsonObject();
            Assert.assertEquals(e.get("owner").getAsString(), s == 0 ? "self" : "opponent");
            Assert.assertTrue(e.get("exiled_with").isJsonNull() || "Bomat Courier".equals(e.get("exiled_with").getAsString()),
                    e.toString());
        }
    }

    @Test(timeOut = 300000)
    public void onlyFaceDownExilesOfTheSixOrOfUnknownExilersCount() {
        // a face-up exile: nothing; a face-down exile of a card outside the six: nothing; of an unknown exiler: counted
        final Game up = ForcedWinSolverTest.game("Default", "Default", frame("", "", "Lightning Bolt", "", ""));
        Assert.assertFalse(HiddenExile.anyFaceDownExile(up));
        Assert.assertEquals(scanCopy(up, 1).size(), 0);
        final Game other = ForcedWinSolverTest.game("Default", "Default", frame("", "Grizzly Bears|Id:901",
                "Lightning Bolt|FaceDown|ExiledWith:901", "", ""));
        Assert.assertTrue(HiddenExile.anyFaceDownExile(other));
        Assert.assertEquals(scanCopy(other, 1).size(), 0, "a face-down exile of another card is not the rule's");
        final Game shell = ForcedWinSolverTest.game("Default", "Default", frame("", "", "", "Shelldock Isle|Id:902",
                "Lightning Bolt|FaceDown|ExiledWith:902"));
        final JsonArray x = scanCopy(shell, 0);
        Assert.assertEquals(x.size(), 1, x.toString());
        Assert.assertEquals(x.get(0).getAsJsonObject().get("exiled_with").getAsString(), "Shelldock Isle");
        final Game unknown = ForcedWinSolverTest.game("Default", "Default", frame("", "", "", "", "Lightning Bolt|FaceDown"));
        final JsonArray u = scanCopy(unknown, 0);
        Assert.assertEquals(u.size(), 1, u.toString());
        Assert.assertTrue(u.get(0).getAsJsonObject().get("exiled_with").isJsonNull());
    }

    @Test(timeOut = 600000)
    public void theStepSearchGivesNoTargetAtAHiddenExile() throws Exception {
        // the step-1 search reads the hidden exile on its own copy and stops before any search or service call
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame("Grizzly Bears", "Forest;Forest", "",
                "Shelldock Isle|Id:903", "Lightning Bolt|FaceDown|ExiledWith:903"));
        final Player me = g.getPlayers().get(0);
        try (FakeSearchService svc = new FakeSearchService(BvwStepSearchHonestyTest.SHA, 2)) {
            final StepSearch ss = new StepSearch(BvwStepSearchHonestyTest.spec(svc.port()), BvwStepSearchHonestyTest.index(),
                    2, "test", "hidden-exile");
            final RlKnowledge know = new RlKnowledge(g);
            know.v2 = true;
            know.attach();
            final String before = LookaheadSearch.fingerprint(g);
            final StepSearch.Result r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(
                    () -> ss.value(g, me, know, 2988000002L, 4243L)));
            Assert.assertEquals(LookaheadSearch.fingerprint(g), before, "the search moved the game");
            Assert.assertEquals(r.why, HiddenExile.TARGET_SOURCE);
            Assert.assertFalse(r.hasTarget());
            Assert.assertEquals(r.hiddenExile.size(), 1);
            Assert.assertEquals(svc.leaves.get(), 0, "nothing was searched");
            Assert.assertTrue(r.toJson().has("hidden_exile"));
        }
    }
}
