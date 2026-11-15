package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.CardIndex;
import forge.bench.rl.ChoiceScorer;
import forge.bench.rl.RlCandidates;
import forge.bench.rl.RlKnowledge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * sdcc-scorerleak-1115 (choiceScorer {@code redealOpp}): the card scorer replays an answer on a copy of the live game.
 * Without the key that copy holds the opponent's REAL hand and library order, and the opponent's Forge AI answers inside
 * the replayed resolution from it. With the key every scorer copy re-deals the opposing cards the seat does not know
 * before the replay, so the scorer's output (refusal, options, failures, frames, screens, choice) is the same for every
 * split of those cards between the opponent's hand and library: a hidden-hand swap never changes it.
 *
 * <p>Two hidden worlds of one game: A = the opponent holds a known Forest plus Lightning Bolt and Counterspell; B = the
 * same game with Bolt and Counterspell traded for two Mountains from their library (card ids kept, no events). The seat
 * knows the Forest (revealed) and nothing else of the opponent's. Card names appear here only (tests).
 */
public class CardScorerOppHiddenTest extends AITest {

    private static final String OPP_LIB = "Mountain;Mountain;Mountain;Forest;Forest;Forest;Forest;Forest;Forest;Forest;"
            + "Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest";

    private static String frame(final String hand) {
        return "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\n"
                + "humanlandsplayed=0\nailandsplayed=0\naihand=Forest;Lightning Bolt;Counterspell\nailibrary=" + OPP_LIB
                + "\naigraveyard=\naiexile=\naibattlefield=Island;Mountain;Grizzly Bears\nhumanhand=" + hand
                + "\nhumanbattlefield=Swamp;Swamp;Island;Balduvian Bears\nhumangraveyard=\nhumanexile=\nhumanlibrary="
                + "Counterspell;Dark Ritual;Ponder;Swamp;Forest;Lightning Bolt;Island;Island;Island;Island\n";
    }

    private static CardIndex index() {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : new String[] {"Demonic Tutor", "Smallpox", "Mountain", "Plains", "Island", "Swamp", "Forest",
            "Lightning Bolt", "Counterspell", "Dark Ritual", "Ponder", "Grizzly Bears", "Balduvian Bears", "Giant Growth"}) {
            sb.append(n).append('\t').append(i++).append('\n');
        }
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The live pilot's solver keys that matter to the scorer's guards (hideOwnLibrary on). */
    private static final Map<String, String> SOLVER = new HashMap<>();
    static {
        for (String k : new String[] {"cycles", "hideOwnLibrary", "historyLki", "readerPerGame", "stackHistory",
            "unknownWorlds", "verifyReplan"}) {
            SOLVER.put(k, "true");
        }
        SOLVER.put("budgetMs", "6000");
    }

    /**
     * The live string's choiceScorer block (shipA ... e2-copyside), clock caps opened (a unit test has no work clock);
     * {@code redealOpp} = the key.
     */
    private static ChoiceScorer scorer(final boolean redealOpp) {
        final JsonObject o = JsonParser.parseString("{\"budgetMs\":60000,\"families\":\"CARDS\",\"gfActive\":3,"
                + "\"gfAskNodes\":60000,\"gfExtra\":2,\"gfGamma\":0.8,\"gfHorizon\":5,\"gfLambda\":0.6,\"gfLands\":true,"
                + "\"gfMs\":60000,\"gfNodes\":1500,\"gfSamples\":0,\"margin\":0.02,\"reachAlpha\":0.85,\"reachNameNodes\":1000,"
                + "\"reachOnePass\":true,\"reachSig\":true,\"reachSkipCapped\":true,\"screenMs\":60000,\"screenNodes\":2000,"
                + "\"seqs\":2,\"reachDiscards\":true,\"redealOwn\":4,\"departScored\":1,\"departFloor\":0.1}").getAsJsonObject();
        if (redealOpp) {
            o.addProperty("redealOpp", true);
        }
        final ChoiceScorer.Config cfg = ChoiceScorer.Config.parse(o);
        Assert.assertEquals(cfg.redealOpp, redealOpp);
        Assert.assertEquals(cfg.toJson().has("redealOpp"), redealOpp, "the key prints only when on");
        cfg.solverCfg = ForcedWinSolver.Config.fromProps(SOLVER::get);
        return new ChoiceScorer(cfg, index(), 2, 77L);
    }

    /** World B: the opponent's unknown Bolt and Counterspell trade places with the top two Mountains of their library. */
    private static void swapHidden(final Game g) {
        final Player opp = g.getPlayers().get(1);
        final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
        final List<Card> lib = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
        int at = 0;
        for (int i = 0; i < hand.size(); i++) {
            if (!"Forest".equals(hand.get(i).getName())) {
                while (!"Mountain".equals(lib.get(at).getName())) {
                    at++;
                }
                final Card h = hand.get(i);
                hand.set(i, lib.get(at));
                lib.set(at, h);
                at++;
            }
        }
        opp.getZone(ZoneType.Hand).setCards(hand);
        opp.getZone(ZoneType.Library).setCards(lib);
    }

    private static String names(final Player p, final ZoneType z) {
        final List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(z)) {
            out.add(c.getName());
        }
        Collections.sort(out);
        return String.join(";", out);
    }

    /** The seat's tracker: it saw the opponent's Forest (revealed), nothing else of theirs. */
    private static RlKnowledge know(final Game g) {
        final RlKnowledge k = new RlKnowledge(g);
        k.v2 = true;
        k.attach();
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card forest = ForcedWinSolverTest.inZone(opp, ZoneType.Hand, "Forest");
        k.onReveal(g, me, Collections.singletonList(forest), ZoneType.Hand, opp);
        return k;
    }

    /** Everything the scorer produced for one ask, as text (frames as hashes). */
    private static String dump(final ChoiceScorer.Scored s, final short[] alt) {
        final StringBuilder b = new StringBuilder("unsupported=" + s.unsupported + " held=" + s.held + " alt="
                + Arrays.toString(alt));
        for (ChoiceScorer.Option o : s.options) {
            b.append("\n  ").append(o.label).append(" fail=").append(o.fail).append(" terminal=").append(o.terminal)
                    .append(" reach=").append(o.screen == null ? "-" : String.valueOf(o.screen.reach)).append(" gf=")
                    .append(o.screen == null ? "-" : String.valueOf(o.screen.gf)).append(" v=").append(o.value)
                    .append(" frames=");
            for (byte[] f : o.frames) {
                b.append(Integer.toHexString(Arrays.hashCode(f))).append(',');
            }
        }
        return b.toString();
    }

    /** enumerate + choose as RlSearch.decideChoice0 runs them; leaf value = a fixed function of the frame bytes. */
    private static String score(final ChoiceScorer sc, final Game g, final Player me, final RlCandidates.Menu m,
            final short[] greedy, final RlKnowledge know, final SpellAbility askSa) {
        final ChoiceScorer.Scored s = sc.enumerate(g, me, m, greedy, know, 4242L, sc.mechAsk(m) ? askSa : null);
        short[] alt = null;
        if (s.unsupported == null) {
            final List<byte[]> ps = sc.payloads(s);
            final double[] v = new double[ps.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (Arrays.hashCode(ps.get(i)) & 0xffff) / 65536.0; // any frame difference moves the value
            }
            alt = sc.choose(s, v, greedy);
        }
        return dump(s, alt);
    }

    private static void addMana(final Player me, final Card host) {
        for (int c = 0; c < 6; c++) {
            for (int k = 0; k < 3; k++) {
                me.getManaPool().addMana(new Mana(ForcedWinModel.FORGE_COLOR[c], host, null, me));
            }
        }
    }

    // ------------------------------------------------------------------ 1. a tutor's pick (CARDS, scored)

    private static String tutor(final boolean redealOpp, final boolean worldB) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame("Demonic Tutor;Giant Growth"));
        if (worldB) {
            swapHidden(g);
        }
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final RlKnowledge know = know(g);
        final Card host = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Demonic Tutor");
        addMana(me, host);
        SpellAbility sa = null;
        for (SpellAbility x : host.getAllPossibleAbilities(me, true)) {
            sa = x;
        }
        Assert.assertTrue(ForcedWinProbe.play(g, me, sa), "Demonic Tutor is cast");
        g.getStack().setResolving(true);
        final RlCandidates.Menu m = forge.bench.rl.TestMenus.singleCards(me.getCardsIn(ZoneType.Library));
        final String hand0 = names(opp, ZoneType.Hand), lib0 = names(opp, ZoneType.Library);
        final String out = score(scorer(redealOpp), g, me, m, new short[] {0}, know, null);
        Assert.assertEquals(names(opp, ZoneType.Hand), hand0, "the live game is untouched");
        Assert.assertEquals(names(opp, ZoneType.Library), lib0);
        System.out.println("OPPHIDDEN tutor redealOpp=" + redealOpp + " world=" + (worldB ? "B" : "A") + " " + out);
        return out;
    }

    @Test
    public void aTutorPickIsTheSameInEveryHiddenWorldWithTheKey() {
        final String a = tutor(true, false), b = tutor(true, true);
        Assert.assertTrue(a.startsWith("unsupported=null"), "the tutor's menu is scored:\n" + a);
        Assert.assertEquals(b, a, "a hidden-hand swap never changes the scorer's output");
    }

    // ------------------------------------------------------------------ 2. Smallpox: our discard, then the opponent's

    private static String smallpox(final boolean redealOpp, final boolean worldB) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame("Smallpox;Giant Growth;Dark Ritual;Swamp"));
        if (worldB) {
            swapHidden(g);
        }
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final RlKnowledge know = know(g);
        final ChoiceScorer sc = scorer(redealOpp);
        final String[] out = new String[1];
        me.dangerouslySetController(new PlayerControllerAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa,
                    final CardCollection validCards, final int min, final int max, final CardCollectionView visible) {
                final JsonObject body = new JsonObject();
                body.addProperty("min", min);
                body.addProperty("max", max);
                final RlCandidates.Menu m = RlCandidates.build(g, me, "chooseCardsToDiscardFrom", "discardFrom", body,
                        new Object[] {validCards, sa, p});
                sc.setAskPlayer(p);
                out[0] = score(sc, g, me, m, new short[] {0}, know, sa);
                final CardCollection live = new CardCollection();
                live.add(validCards.get(0));
                return live;
            }
        });
        final Card host = ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Smallpox");
        addMana(me, host);
        SpellAbility sa = null;
        for (SpellAbility x : host.getAllPossibleAbilities(me, true)) {
            sa = x;
        }
        Assert.assertTrue(ForcedWinProbe.play(g, me, sa), "Smallpox is cast");
        MyRandom.setRandom(new Random(42));
        g.getStack().resolveStack();
        Assert.assertNotNull(out[0], "our discard was asked");
        System.out.println("OPPHIDDEN smallpox redealOpp=" + redealOpp + " world=" + (worldB ? "B" : "A") + " opp graveyard="
                + names(opp, ZoneType.Graveyard) + " " + out[0]);
        return out[0];
    }

    @Test
    public void aDiscardFollowedByTheOpponentsIsTheSameInEveryHiddenWorldWithTheKey() {
        final String a = smallpox(true, false), b = smallpox(true, true);
        Assert.assertEquals(b, a, "a hidden-hand swap never changes the scorer's output");
    }

    @Test
    public void withoutTheKeyTheTwoWorldsAreReported() {
        // the old behaviour (diagnostic print, no assertion on the outputs): the copies hold the real hand
        tutor(false, false);
        tutor(false, true);
        smallpox(false, false);
        smallpox(false, true);
    }

    // ------------------------------------------------------------------ 3. the deal itself

    @Test
    public void theDealDependsOnlyOnWhatTheSeatCannotSee() {
        final List<String> hands = new ArrayList<>();
        for (boolean worldB : new boolean[] {false, true}) {
            final Game g = ForcedWinSolverTest.game("Default", "Default", frame("Giant Growth"));
            if (worldB) {
                swapHidden(g);
            }
            final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
            final RlKnowledge know = know(g);
            final int handSize = opp.getCardsIn(ZoneType.Hand).size(), libSize = opp.getCardsIn(ZoneType.Library).size();
            ChoiceScorer.redealOppHidden(g, me, know, 99L);
            Assert.assertEquals(opp.getCardsIn(ZoneType.Hand).size(), handSize);
            Assert.assertEquals(opp.getCardsIn(ZoneType.Library).size(), libSize);
            Assert.assertNotNull(ForcedWinSolverTest.inZone(opp, ZoneType.Hand, "Forest"), "the known card stays in hand");
            final StringBuilder ids = new StringBuilder();
            for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                ids.append(c.getId()).append(':').append(c.getName()).append(' ');
                Assert.assertEquals(c.getZone().getZoneType(), ZoneType.Hand);
            }
            ids.append('|');
            for (Card c : opp.getCardsIn(ZoneType.Library)) {
                ids.append(c.getId()).append(' ');
            }
            hands.add(ids.toString());
            Assert.assertTrue(names(me, ZoneType.Hand).equals("Giant Growth"), "our own cards are not dealt");
        }
        Assert.assertEquals(hands.get(1), hands.get(0), "the same deal from both hidden worlds");
    }
}
