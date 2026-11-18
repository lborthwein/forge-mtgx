package forge.ai.simulation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

/**
 * The lethalBlock guard of the block search (Config.lethalBlock, run block-vs-open-pump-1118). The report of
 * 2026-11-18T05:34Z (session 3142b1dd): on turn 22, at its declare-blockers ask, the policy seat at 8 life with Mikaeus,
 * the Unhallowed, Tasigur, the Golden Fang and Reclamation Sage untapped faced an attacking Polukranos, World Eater (5/5)
 * whose controller had seven untapped lands. The policy declared a double block; the block search departed to no blocks
 * (its play-outs never paid Monstrosity), and the opponent made Polukranos monstrous for X = 3 (8 damage) and won. The
 * class: a declaration whose worst-case combat damage (what the attacking player can add with the mana it has, through the
 * spells and abilities the seat knows) is lethal, while another candidate is not, is a lost play-out.
 *
 * <p>The bound's tests drive {@link LookaheadSearch#lethalBlocksOf} on constructed boards (it copies the game, as the live
 * guard does); the mode tests drive {@link LookaheadSearch#lethalBlockApply} on given values; the reported board also runs
 * end to end through the live seat ({@link RlLiveSeat}) against {@link FakeSearchService}, as {@code RlBlockSearchTest}
 * does. Plays real Forge copies: run inside a broker test lease, cwd = forge-gui with res/.
 *
 * <p>Round 6 (brief-r6): the bound is a prefilter; a candidate counts as lethal only when the engine witnesses it in a
 * sandboxed copy ({@link LookaheadSearch#lethalWitness}). The sweep tests replay Astra's re-review 4 boards
 * (review/245/recheck4): none is re-scored where its real maximum is not lethal, and the witness alone, given every ability,
 * does not witness it either. The positive, hidden-information and live-state tests follow the brief.
 */
public class LookaheadLethalBlockTest extends SimulationTest {

    // ------------------------------------------------------------------------------------------------ boards

    /**
     * The reported board at the declare-blockers ask: ai (the policy seat) at 8 life, six tapped Swamps, Mikaeus (5/5),
     * Tasigur (4/5) and Reclamation Sage (3/2 with Mikaeus), all untapped; opp at 15 with Polukranos (not attacking yet) and
     * the first {@code lands} of Stomping Ground, Swamp, Forest, Cinder Glade, Mountain, Forest, Swamp untapped, and two
     * hand cards ai has not seen. Returns {Polukranos, Mikaeus, Tasigur, Sage}.
     */
    private Card[] reported(Game g, Player ai, Player opp, int lands) {
        ai.setLife(8, null);
        opp.setLife(15, null);
        for (int i = 0; i < 6; i++) {
            addCard("Swamp", ai).setTapped(true);
        }
        final Card mik = addCard("Mikaeus, the Unhallowed", ai);
        final Card tas = addCard("Tasigur, the Golden Fang", ai);
        final Card sage = addCard("Reclamation Sage", ai);
        for (Card c : new Card[] {mik, tas, sage}) {
            c.setSickness(false);
        }
        final String[] l = {"Stomping Ground", "Swamp", "Forest", "Cinder Glade", "Mountain", "Forest", "Swamp"};
        for (int i = 0; i < lands; i++) {
            addCard(l[i], opp);
        }
        final Card pol = addCard("Polukranos, World Eater", opp);
        pol.setSickness(false);
        addCardToZone("Forest", opp, ZoneType.Hand);
        addCardToZone("Mountain", opp, ZoneType.Hand);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        return new Card[] {pol, mik, tas, sage};
    }

    /** A fresh game at opp's declare-blockers step (ai = player 0, defending; opp = player 1). */
    private Game game() {
        final Game g = initAndCreateGame();
        g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, g.getPlayers().get(1));
        return g;
    }

    /** opp attacks ai with {@code attackers} (tapped, no vigilance here). */
    private static Combat attack(Game g, Player opp, Player ai, Card... attackers) {
        final Combat c = new Combat(opp);
        for (Card a : attackers) {
            c.addAttacker(a, ai);
            a.setTapped(true);
        }
        g.getPhaseHandler().setCombat(c);
        g.getAction().checkStateEffects(true);
        return c;
    }

    /** A declaration: blocker, attacker, blocker, attacker... (none = no blocks). */
    private static Map<Integer, List<Integer>> plan(Card... pairs) {
        final Map<Integer, List<Integer>> m = new TreeMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.computeIfAbsent(pairs[i].getId(), x -> new ArrayList<>()).add(pairs[i + 1].getId());
        }
        return m;
    }

    @SafeVarargs
    private static LookaheadSearch.LethalCheck check(Game g, Map<Integer, List<Integer>>... plans) {
        final LookaheadSearch s = new LookaheadSearch(config(AiFixes.Mode.ON));
        try {
            return s.lethalBlocksOf(g, g.getPlayers().get(0), Arrays.asList(plans));
        } finally {
            s.shutdown();
        }
    }

    private static LookaheadSearch.Config config(AiFixes.Mode mode) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 4;
        c.threads = 1;
        c.seed = 1_118_000_005L;
        c.lethalBlock = mode;
        return c;
    }

    private static Card bf(Player p, String name) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(name + " is not on " + p + "'s battlefield");
    }

    private static String flags(LookaheadSearch.LethalCheck lc) {
        return "aside=" + lc.aside + " lethal=" + Arrays.toString(lc.lethal) + " life=" + Arrays.toString(lc.life) + " poison="
                + Arrays.toString(lc.poison) + " mana=" + lc.mana + " abilities=" + lc.abilities;
    }

    // ------------------------------------------------------------------------------------------------ the bound

    /** The reported board: no blocks takes 5 + Monstrosity 3 (7 mana: X X G) = 8 at 8 life; each block declaration 0. */
    @Test
    public void theReportedBoardIsLethalOnlyWithoutBlocks() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = reported(g, ai, opp, 7);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0], b[2], b[0], b[3], b[0]), plan(b[1], b[0], b[2], b[0]),
                plan(b[2], b[0]), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 7, lc.mana);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, false, false, true}, lc.lethal));
        AssertJUnit.assertEquals(flags(lc), 8, lc.life[3]);
        AssertJUnit.assertEquals(flags(lc), 0, lc.life[0]);
        // the copy only: the live game's hand and X are untouched
        AssertJUnit.assertEquals(2, opp.getCardsIn(ZoneType.Hand).size());
    }

    /** mana: six lands make X = 2 at most, 5 + 2 = 7 < 8. */
    @Test
    public void sixLandsAreNotLethal() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = reported(g, ai, opp, 6);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[2], b[0]), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 6, lc.mana);
        AssertJUnit.assertEquals(flags(lc), 7, lc.life[1]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[1]);
    }

    /** limits: an attacker already monstrous adds nothing with Monstrosity. */
    @Test
    public void anAlreadyMonstrousAttackerAddsNothing() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = reported(g, ai, opp, 7);
        b[0].setMonstrous(true);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 5, lc.life[0]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
    }

    /** can't lose: ai cannot lose for 0 or less life (Platinum Angel): nothing is lethal, so nothing is re-scored. */
    @Test
    public void aPlayerWhoCannotLoseFromLifeIsNeverLethal() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = reported(g, ai, opp, 7);
        addCard("Platinum Angel", ai).setSickness(false);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[2], b[0]), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 8, lc.life[1]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0] || lc.lethal[1]);
    }

    /** firebreathing-style: a repeatable +1/+0 ({R}) counts once per mana: 5 + 3 Mountains = 8. */
    @Test
    public void aRepeatablePumpCountsOncePerMana() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(8, null);
        for (int i = 0; i < 3; i++) {
            addCard("Mountain", opp);
        }
        final Card dragon = addCard("Shivan Dragon", opp);
        dragon.setSickness(false);
        attack(g, opp, ai, dragon);
        LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertEquals(flags(lc), 8, lc.life[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
        ai.setLife(9, null);
        lc = check(g, plan());
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
    }

    /** colors: the same {R} pump with only Forests cannot be paid: 5 < 6. */
    @Test
    public void aPumpItCannotPayForByColorDoesNotCount() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(6, null);
        for (int i = 0; i < 3; i++) {
            addCard("Forest", opp);
        }
        final Card dragon = addCard("Shivan Dragon", opp);
        dragon.setSickness(false);
        attack(g, opp, ai, dragon);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 3, lc.mana);
        AssertJUnit.assertEquals(flags(lc), 5, lc.life[0]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
    }

    /** another permanent's pump: an enchantment's "{R}: target creature gets +1/+0" counts, per mana: 2 + 2 = 4. */
    @Test
    public void anotherPermanentsTargetedPumpCounts() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        addCard("Captive Flame", opp);
        addCard("Mountain", opp);
        addCard("Mountain", opp);
        final Card bear = addCard("Grizzly Bears", opp);
        bear.setSickness(false);
        attack(g, opp, ai, bear);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 4, lc.life[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
    }

    /** PumpAll: "{2}{W}: creatures you control get +1/+1" includes the attacker; six Plains pay it twice: 2 + 2 = 4. */
    @Test
    public void aPumpAllThatIncludesTheAttackerCounts() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        addCard("Gerrard's Battle Cry", opp);
        for (int i = 0; i < 6; i++) {
            addCard("Plains", opp);
        }
        final Card bear = addCard("Grizzly Bears", opp);
        bear.setSickness(false);
        attack(g, opp, ai, bear);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 4, lc.life[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
    }

    /**
     * trample: Colossal Dreadmaw (6/6 trample) over a Grizzly Bears chump deals 6 - 2 = 4, plus two {R} pumps = 6: lethal at
     * 6 through the block; a block without trample would deal none.
     */
    @Test
    public void aBoostedTramplerIsLethalThroughAChump() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(6, null);
        final Card chump = addCard("Grizzly Bears", ai);
        chump.setSickness(false);
        addCard("Captive Flame", opp);
        addCard("Mountain", opp);
        addCard("Mountain", opp);
        final Card maw = addCard("Colossal Dreadmaw", opp);
        maw.setSickness(false);
        attack(g, opp, ai, maw);
        LookaheadSearch.LethalCheck lc = check(g, plan(chump, maw), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 6, lc.life[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
        AssertJUnit.assertEquals(flags(lc), 8, lc.life[1]);
        ai.setLife(7, null);
        lc = check(g, plan(chump, maw), plan());
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[1]);
    }

    /** hosts: a "{T}: target creature gets +1/+1" counts while its host is untapped, not once it is tapped. */
    @Test
    public void aTapPumpOnATappedHostDoesNotCount() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(3, null);
        final Card warden = addCard("Ghost Warden", opp);
        warden.setSickness(false);
        final Card bear = addCard("Grizzly Bears", opp);
        bear.setSickness(false);
        attack(g, opp, ai, bear);
        LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertEquals(flags(lc), 3, lc.life[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
        warden.setTapped(true);
        lc = check(g, plan());
        AssertJUnit.assertEquals(flags(lc), 2, lc.life[0]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
    }

    /** timing: a sorcery-speed "{T}: target creature gets +1/+0" does not count at declare blockers. */
    @Test
    public void aSorcerySpeedPumpDoesNotCount() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(3, null);
        addCard("Traveling Minister", opp).setSickness(false);
        final Card bear = addCard("Grizzly Bears", opp);
        bear.setSickness(false);
        attack(g, opp, ai, bear);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 2, lc.life[0]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
    }

    /** hand: a Giant Growth in opp's hand that ai has not seen does not count; the same card revealed to ai does. */
    @Test
    public void aHiddenPumpInstantDoesNotCountARevealedOneDoes() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(5, null);
        addCard("Forest", opp);
        final Card growth = addCardToZone("Giant Growth", opp, ZoneType.Hand);
        final Card bear = addCard("Grizzly Bears", opp);
        bear.setSickness(false);
        attack(g, opp, ai, bear);
        LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 2, lc.life[0]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
        growth.addMayLookAt(g.getNextTimestamp(), Arrays.asList(ai));
        lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 5, lc.life[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
    }

    /** several attackers sum: two Grizzly Bears are 4 unblocked (lethal at 4), 2 with one blocked. */
    @Test
    public void severalAttackersSum() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card wall = addCard("Grizzly Bears", ai);
        wall.setSickness(false);
        final Card a1 = addCard("Grizzly Bears", opp);
        final Card a2 = addCard("Grizzly Bears", opp);
        a1.setSickness(false);
        a2.setSickness(false);
        attack(g, opp, ai, a1, a2);
        final LookaheadSearch.LethalCheck lc = check(g, plan(wall, a1), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 2, lc.life[0]);
        AssertJUnit.assertEquals(flags(lc), 4, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, true}, lc.lethal));
    }

    /** stand aside: a face-down attacker (its card unseen by ai) makes the bound untrusted. */
    @Test
    public void aFaceDownAttackerStandsAside() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(2, null);
        final Card bear = addCard("Grizzly Bears", opp);
        bear.setSickness(false);
        bear.turnFaceDown(true);
        attack(g, opp, ai, bear);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNotNull(flags(lc), lc.aside);
    }

    // ---------------------------------------------------------------------------- review round 2 (Astra HOLD @ c60bd487c13)

    /**
     * S1, knowledge: Stormchaser Chimera's "+X/+0, where X is the revealed top card's mana value" reads opp's library top,
     * which ai has not seen. The guard decides from the ability's text: X is not a number or X paid, so it stands aside the
     * same way whether the top is a Forest or Emrakul, the Aeons Torn (c60 read the top: bound 2 against 17, and re-scored
     * only with Emrakul on top).
     */
    @Test
    public void aHiddenLibraryTopNeverChangesTheDecision() {
        final String[] tops = {"Forest", "Emrakul, the Aeons Torn"};
        final String[] asides = new String[2];
        for (int j = 0; j < 2; j++) {
            final Game g = game();
            final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
            ai.setLife(8, null);
            final Card chimera = addCard("Stormchaser Chimera", opp);
            chimera.setSickness(false);
            final Card drake = addCard("Wind Drake", ai);
            drake.setSickness(false);
            for (String land : new String[] {"Island", "Mountain", "Forest", "Forest"}) {
                addCard(land, opp);
            }
            final Card top = addCardToZone(tops[j], opp, ZoneType.Library);
            AssertJUnit.assertFalse(LookaheadSearch.pumpKnows(ai, top));
            fillLibrary(ai, 20);
            attack(g, opp, ai, chimera);
            final LookaheadSearch.LethalCheck lc = check(g, plan(drake, chimera), plan());
            AssertJUnit.assertNotNull(tops[j] + ": " + flags(lc), lc.aside);
            AssertJUnit.assertTrue(flags(lc), lc.aside.contains("amount"));
            asides[j] = lc.aside;
            final double[][] v = {{0.25, 0.25}, {0.40, 0.40}};
            final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true});
            AssertJUnit.assertFalse(tops[j] + ": " + flags(lc), r.lethalRescored);
            AssertJUnit.assertTrue(tops[j], Arrays.deepEquals(new double[][] {{0.25, 0.25}, {0.40, 0.40}}, v));
        }
        AssertJUnit.assertEquals("the same decision whatever the hidden top", asides[0], asides[1]);
    }

    /** The Kessig Wolf Run board: ai at {@code life} with a Grizzly Bears; opp's attacking Grizzly Bears, Wolf Run, a Mountain, {@code forests} Forests. */
    private Card[] wolfRun(Game g, Player ai, Player opp, int life, int forests) {
        ai.setLife(life, null);
        final Card blocker = addCard("Grizzly Bears", ai);
        blocker.setSickness(false);
        final Card attacker = addCard("Grizzly Bears", opp);
        attacker.setSickness(false);
        addCard("Kessig Wolf Run", opp);
        addCard("Mountain", opp);
        for (int i = 0; i < forests; i++) {
            addCard("Forest", opp);
        }
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        return new Card[] {attacker, blocker};
    }

    /**
     * S1, joint mana: Kessig Wolf Run ({X}{R}{G}, {T}: +X/+0 and trample) cannot also tap for its own {C}. With Wolf Run,
     * a Mountain and a Forest the most is X = 0: no blocks takes 2 at 3 life, not lethal, and nothing is re-scored (c60
     * counted Wolf Run's mana for X: 3, lethal, and re-scored no blocks to a loss).
     */
    @Test
    public void wolfRunsTapPaysOnlyOnce() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = wolfRun(g, ai, opp, 3, 1);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0]), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 3, lc.mana);
        AssertJUnit.assertEquals(flags(lc), 0, lc.life[0]);
        AssertJUnit.assertEquals("X = 0 at most: " + flags(lc), 2, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, false}, lc.lethal));
        final double[][] v = {{0.25, 0.25}, {0.40, 0.40}};
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(new double[][] {{0.25, 0.25}, {0.40, 0.40}}, v));
    }

    /**
     * Joint mana, positive: with a second Forest the most is X = 1 (Wolf Run's tap still pays nothing): no blocks takes 3,
     * lethal at 3 while the block (trample: 2 - 2 + 1) is not; at 4 life nothing is lethal (c60: X = 2, 4).
     */
    @Test
    public void wolfRunStillFindsLethalWithASpareSource() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = wolfRun(g, ai, opp, 3, 2);
        attack(g, opp, ai, b[0]);
        LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0]), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 4, lc.mana);
        AssertJUnit.assertEquals(flags(lc), 1, lc.life[0]);
        AssertJUnit.assertEquals(flags(lc), 3, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, true}, lc.lethal));
        ai.setLife(4, null);
        lc = check(g, plan(b[1], b[0]), plan());
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, false}, lc.lethal));
    }

    /**
     * Joint mana, other permanents: Root-Kin Ally's "tap two untapped creatures you control: +2/+2" taps mana creatures.
     * With two Llanowar Elves and a revealed Giant Growth, either the Ally's +2 (both Elves) or Giant Growth's +3 (one Elf):
     * 3 + 3 = 6, not lethal at 8 (c60 counted both: 8). A Grizzly Bears to tap instead of one Elf allows both: 8, lethal.
     */
    @Test
    public void tappingManaCreaturesForACostSpendsTheirMana() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(8, null);
        final Card ally = addCard("Root-Kin Ally", opp);
        ally.setSickness(false);
        for (int i = 0; i < 2; i++) {
            addCard("Llanowar Elves", opp).setSickness(false);
        }
        final Card growth = addCardToZone("Giant Growth", opp, ZoneType.Hand);
        growth.addMayLookAt(g.getNextTimestamp(), Arrays.asList(ai));
        fillLibrary(ai, 20);
        attack(g, opp, ai, ally);
        LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 2, lc.mana);
        AssertJUnit.assertEquals(flags(lc), 6, lc.life[0]);
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
        addCard("Grizzly Bears", opp).setSickness(false);
        g.getAction().checkStateEffects(true);
        lc = check(g, plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 8, lc.life[0]);
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
    }

    /**
     * S2, compounding: Chameleon Colossus's "+X/+X, where X is its power" doubles on each activation (4 -> 8 -> 16 with
     * eight Forests: lethal at 16). An amount that reads power is not a number or X: the guard stands aside (c60 counted
     * 4 + 2 x 4 = 12, not lethal).
     */
    @Test
    public void aPowerReadingPumpStandsAside() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(16, null);
        final Card colossus = addCard("Chameleon Colossus", opp);
        colossus.setSickness(false);
        for (int i = 0; i < 8; i++) {
            addCard("Forest", opp);
        }
        final Card bear = addCard("Grizzly Bears", ai);
        bear.setSickness(false);
        attack(g, opp, ai, colossus);
        final LookaheadSearch.LethalCheck lc = check(g, plan(bear, colossus), plan());
        AssertJUnit.assertNotNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), lc.aside.contains("amount"));
        final double[][] v = {{0.25, 0.25}, {0.40, 0.40}};
        AssertJUnit.assertFalse(apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true}).lethalRescored);
    }

    /** Composition: a prowess attacker gets +1/+1 from each spell its controller casts, which the bound does not count: aside. */
    @Test
    public void aProwessAttackerStandsAside() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(3, null);
        final Card spear = addCard("Monastery Swiftspear", opp);
        attack(g, opp, ai, spear);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        AssertJUnit.assertNotNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), lc.aside.contains("trigger"));
    }

    /** S3, double strike unblocked: Fencing Ace (1/1) deals 2, lethal at 2 and not at 3; blocked (no trample) 0. */
    @Test
    public void anUnblockedDoubleStrikerDealsTwice() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(2, null);
        final Card ace = addCard("Fencing Ace", opp);
        ace.setSickness(false);
        final Card bear = addCard("Grizzly Bears", ai);
        bear.setSickness(false);
        attack(g, opp, ai, ace);
        LookaheadSearch.LethalCheck lc = check(g, plan(bear, ace), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new int[] {0, 2}, lc.life));
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, true}, lc.lethal));
        ai.setLife(3, null);
        lc = check(g, plan(bear, ace), plan());
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, false}, lc.lethal));
    }

    /**
     * S3, double strike with trample, blocked: Bolrac-Clan Basher (3/2) over a Grizzly Bears deals 2 x 3 - 2 = 4 (lethal
     * at 4, not 5), 6 unblocked. Dragon Tyrant (6/6) with two Mountains for its "{R}: +1/+0": 12 - 2 + 2 x 2 = 14 blocked,
     * 16 unblocked (the boost lands twice).
     */
    @Test
    public void aBlockedDoubleStrikingTramplerDealsTwicePowerLessLethal() {
        Game g = game();
        Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card basher = addCard("Bolrac-Clan Basher", opp);
        basher.setSickness(false);
        Card bear = addCard("Grizzly Bears", ai);
        bear.setSickness(false);
        attack(g, opp, ai, basher);
        LookaheadSearch.LethalCheck lc = check(g, plan(bear, basher), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new int[] {4, 6}, lc.life));
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {true, true}, lc.lethal));
        ai.setLife(5, null);
        lc = check(g, plan(bear, basher), plan());
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, true}, lc.lethal));

        g = game();
        ai = g.getPlayers().get(0);
        opp = g.getPlayers().get(1);
        ai.setLife(14, null);
        final Card tyrant = addCard("Dragon Tyrant", opp);
        tyrant.setSickness(false);
        addCard("Mountain", opp);
        addCard("Mountain", opp);
        bear = addCard("Grizzly Bears", ai);
        bear.setSickness(false);
        attack(g, opp, ai, tyrant);
        lc = check(g, plan(bear, tyrant), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new int[] {14, 16}, lc.life));
        AssertJUnit.assertTrue(flags(lc), lc.lethal[0]);
        ai.setLife(15, null);
        lc = check(g, plan(bear, tyrant), plan());
        AssertJUnit.assertFalse(flags(lc), lc.lethal[0]);
    }

    // ------------------------------------------------------------------------------------------------ the modes

    private static double[][] values() {
        return new double[][] {{0.10, 0.12}, {0.09, 0.08}, {0.32, 0.33}};
    }

    /** A check whose bound flags {@code lethal} and whose witness confirms each of them (round 6). */
    private static LookaheadSearch.LethalCheck flagged(boolean... lethal) {
        final LookaheadSearch.LethalCheck lc = new LookaheadSearch.LethalCheck();
        lc.lethal = lethal;
        lc.witnessed = lethal.clone();
        return lc;
    }

    private static LookaheadSearch.GivenResult apply(AiFixes.Mode mode, LookaheadSearch.LethalCheck lc, double[][] v,
            boolean[] ok) {
        final LookaheadSearch s = new LookaheadSearch(config(mode));
        try {
            final LookaheadSearch.GivenResult r = new LookaheadSearch.GivenResult();
            s.lethalBlockApply(lc, v, ok, 2, 0.0, r);
            final JsonObject st = s.getStats().toJson().getAsJsonObject("lethalBlock");
            AssertJUnit.assertNotNull("guard stats in the stats JSON while " + mode, st);
            AssertJUnit.assertEquals(mode.key(), st.get("mode").getAsString());
            return r;
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void offByDefaultAndSilent() {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.lethalBlock);
        AssertJUnit.assertFalse(c.toJson().has("lethalBlock"));
        final LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("lethalBlock"));
        } finally {
            s.shutdown();
        }
        c.lethalBlock = AiFixes.Mode.SHADOW;
        AssertJUnit.assertEquals("shadow", c.toJson().get("lethalBlock").getAsString());
        final RlSearch.Config r = new RlSearch.Config();
        AssertJUnit.assertEquals("off", r.lethalBlock);
        AssertJUnit.assertFalse("policy-seat spec JSON unchanged while off", r.toJson().has("lethalBlock"));
    }

    /** ON: the lethal candidate scores as a lost play-out in every world; the others are unchanged. */
    @Test
    public void onRescoresTheLethalCandidates() {
        final double[][] v = values();
        final boolean[] ok = {true, true, true};
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, flagged(false, false, true), v, ok);
        AssertJUnit.assertTrue(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.equals(new double[] {0.0, 0.0}, v[2]));
        AssertJUnit.assertTrue(Arrays.deepEquals(Arrays.copyOf(values(), 2), Arrays.copyOf(v, 2)));
        AssertJUnit.assertTrue(Arrays.equals(new boolean[] {false, false, true}, r.lethal));
    }

    /** The default may be re-scored but is never removed: it keeps its place and its ok. */
    @Test
    public void aLethalDefaultIsRescoredNotRemoved() {
        final double[][] v = values();
        final boolean[] ok = {true, true, true};
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, flagged(true, false, false), v, ok);
        AssertJUnit.assertTrue(r.lethalRescored);
        AssertJUnit.assertEquals(3, v.length);
        AssertJUnit.assertTrue(ok[0]);
        AssertJUnit.assertTrue(Arrays.equals(new double[] {0.0, 0.0}, v[0]));
    }

    /** SHADOW: flags, values unchanged. */
    @Test
    public void shadowFlagsWithoutChangingValues() {
        final double[][] v = values();
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.SHADOW, flagged(false, false, true), v, new boolean[] {true, true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.equals(new boolean[] {false, false, true}, r.lethal));
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
    }

    /** all lethal (or none): values unchanged, nothing re-scored. */
    @Test
    public void allLethalOrNoneLeavesTheValues() {
        double[][] v = values();
        LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, flagged(true, true, true), v, new boolean[] {true, true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
        v = values();
        r = apply(AiFixes.Mode.ON, flagged(false, false, false), v, new boolean[] {true, true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
        // the only candidate not lethal failed its play-outs: nothing is re-scored
        v = values();
        r = apply(AiFixes.Mode.ON, flagged(true, false, true), v, new boolean[] {true, false, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
    }

    /** Stood aside: nothing changes and the result says why. */
    @Test
    public void standingAsideChangesNothing() {
        final double[][] v = values();
        final LookaheadSearch.LethalCheck lc = LookaheadSearch.LethalCheck.aside("an attacker the seat cannot see");
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertEquals(0, r.lethal.length);
        AssertJUnit.assertEquals("an attacker the seat cannot see", r.lethalAside);
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
    }

    // ------------------------------------------------------------------------------------------------ end to end

    static final String SHA = "7f7d160785e58bd1da37435f8ed71e145d761fb34f9d45b40809880982388465";
    static final long SEED = 1_118_053_438L;

    private static Path indexFile;

    private static synchronized Path index() throws Exception {
        if (indexFile == null) {
            final TreeSet<String> names = new TreeSet<>(Arrays.asList("Forest", "Plains", "Mountain", "Island", "Swamp",
                    "Stomping Ground", "Cinder Glade", "Polukranos, World Eater", "Mikaeus, the Unhallowed",
                    "Tasigur, the Golden Fang", "Reclamation Sage", "Grizzly Bears"));
            final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
            int i = 2;
            for (String n : names) {
                sb.append(n).append('\t').append(i++).append('\n');
            }
            indexFile = Files.createTempFile("lethal-block-index-", ".tsv");
            Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return indexFile;
    }

    /**
     * The leaf v(o) stands for the value net that preferred no blocks on the live board (0.322 against 0.09-0.12): 0.3 per
     * creature of the opponent's (it keeps Polukranos when nothing blocks) minus 0.5. The play-outs never pay Monstrosity
     * (both seats are the scripted policy: they pass), so no blocks looks best to the search.
     */
    static double leaf(final byte[] payload) {
        final RlWire.Decide d = RlWire.decodeDecide(payload, 1);
        int theirs = 0;
        for (int i = 0; i < d.L; i++) {
            if (d.tokZone[i] == RlSchema.Z_O_CRE) {
                theirs++;
            }
        }
        return Math.max(-1, Math.min(1, 0.3 * theirs - 0.5));
    }

    /** The scripted policy: every block ask declares each blocker on an attacker (the policy's own block); else pass. */
    static short[] script(final RlWire.Decide d) {
        if (d.family == RlSchema.F_BLOCK || d.family == RlSchema.F_ATTACK) {
            final short[] out = new short[d.S];
            for (int s = 0; s < d.S; s++) {
                out[s] = -1;
                for (int i = 0; i < d.C; i++) {
                    if (d.candSlot[i] == s && d.candKind[i] == RlSchema.K_NONE) {
                        out[s] = (short) i;
                    }
                }
                if (d.family == RlSchema.F_BLOCK) {
                    for (int i = 0; i < d.C; i++) {
                        if (d.candSlot[i] == s && d.candKind[i] == RlSchema.K_ATTACKER) {
                            out[s] = (short) i;
                        }
                    }
                }
            }
            return out;
        }
        if (d.family == RlSchema.F_PRIORITY) {
            for (int i = 0; i < d.C; i++) {
                if (d.candKind[i] == RlSchema.K_PASS) {
                    return new short[] {(short) i};
                }
            }
        }
        return null;
    }

    static final class Run {
        Combat combat;
        String err;
        final List<String> blockLines = new ArrayList<>();
        String bootLine;
        JsonObject line() {
            AssertJUnit.assertEquals("one block decision line: " + err, 1, blockLines.size());
            return JsonParser.parseString(blockLines.get(0).substring("[lookahead-decision] ".length())).getAsJsonObject();
        }
    }

    /** A board for the live seat: builds it (ai = seat 0, opp = seat 1) and returns {attacker, ...}. */
    interface Board {
        Card[] build(Game g, Player ai, Player opp);
    }

    /** The reported board through the live seat: blocks=1, both play-out seats the scripted policy, plus {@code extra}. */
    private Run reportedLive(final String extra) throws Exception {
        return live((g, ai, opp) -> reported(g, ai, opp, 7), extra);
    }

    /** {@code board} through the live seat, as {@link #reportedLive}: its first card attacks seat 0. */
    private Run live(final Board board, final String extra) throws Exception {
        final Run r = new Run();
        final PrintStream was = System.err;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            svc.leafValue = LookaheadLethalBlockTest::leaf;
            svc.script = LookaheadLethalBlockTest::script;
            final String spec = "server=" + svc.address() + ",policySha=" + SHA + ",cardIndex=" + index()
                    + ",threads=4,blocks=1,opponent=policy" + (extra.isEmpty() ? "" : "," + extra);
            final RlLiveSeat live = RlLiveSeat.connect(spec, "lethal-block-test");
            AssertJUnit.assertNotNull("the seat binds the fake service", live);
            final LobbyPlayerAi ai = new LobbyPlayerAi("Seat1", null);
            ai.setAiProfile("Default");
            final List<RegisteredPlayer> players = Arrays.asList(
                    new RegisteredPlayer(new Deck()).setPlayer(live.lobby("Seat0", 0, "Default")),
                    new RegisteredPlayer(new Deck()).setPlayer(ai));
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(players, rules, new Match(rules, players, "lethal-block"));
            final Player p0 = game.getRegisteredPlayers().get(0);
            final Player p1 = game.getRegisteredPlayers().get(1);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1, 22);
            final Card[] b = board.build(game, p0, p1);
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, p1, false, 22);
            final Combat combat = new Combat(p1);
            combat.addAttacker(b[0], p0);
            b[0].setTapped(true);
            game.getPhaseHandler().setCombat(combat);
            r.combat = combat;
            live.bind(game, 0, SEED, null, AiFixes.Mode.OFF);
            try {
                p0.getController().declareBlockers(p0, combat);
            } finally {
                live.finish();
            }
            System.err.flush();
            r.err = buf.toString(StandardCharsets.UTF_8);
            for (String l : r.err.split("\n")) {
                if (l.startsWith("[lookahead-decision] ") && l.contains("\"kind\":\"blocks\"")) {
                    r.blockLines.add(l);
                }
                if (l.startsWith("[policy-seat] ") && l.contains("\"bound\":true")) {
                    r.bootLine = l;
                }
            }
        } finally {
            System.setErr(was);
            was.print(buf.toString(StandardCharsets.UTF_8));
        }
        return r;
    }

    private static int noneIndex(JsonObject line) {
        final JsonArray labels = line.getAsJsonArray("labels");
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).getAsString().equals("none")) {
                return i;
            }
        }
        throw new AssertionError("no 'none' candidate: " + line);
    }

    /**
     * The reported board end to end. OFF (key absent): the search departs from the policy's block to no blocks, as live,
     * and the decision line has no lethalBlock field. SHADOW: the same declaration, with "none" flagged lethal and
     * witnessed (round 6: by Monstrosity X = 3), nothing re-scored. ON: no blocks, witnessed, scores as a loss and the
     * search keeps the policy's block; the witness takes under 300 ms.
     */
    @Test
    public void theReportedBoardKeepsABlockWhenOn() throws Exception {
        final Run off = reportedLive("");
        final JsonObject lo = off.line();
        AssertJUnit.assertEquals(lo.toString(), "departed", lo.get("outcome").getAsString());
        AssertJUnit.assertEquals(lo.toString(), "none", lo.get("chosen").getAsString());
        AssertJUnit.assertTrue("no blocks, as live: " + lo, off.combat.getAllBlockers().isEmpty());
        AssertJUnit.assertFalse(lo.toString(), lo.has("lethalBlock"));
        AssertJUnit.assertFalse(off.bootLine, off.bootLine.contains("lethalBlock"));

        final Run shadow = reportedLive("lethalBlock=shadow");
        final JsonObject ls = shadow.line();
        AssertJUnit.assertEquals(ls.toString(), "none", ls.get("chosen").getAsString());
        AssertJUnit.assertTrue("shadow decides as off: " + ls, shadow.combat.getAllBlockers().isEmpty());
        final JsonObject gs = ls.getAsJsonObject("lethalBlock");
        AssertJUnit.assertNotNull(ls.toString(), gs);
        AssertJUnit.assertFalse(ls.toString(), gs.get("rescored").getAsBoolean());
        final JsonArray fs = gs.getAsJsonArray("lethal");
        AssertJUnit.assertEquals(ls.toString(), ls.getAsJsonArray("labels").size(), fs.size());
        final int none = noneIndex(ls);
        for (int i = 0; i < fs.size(); i++) {
            AssertJUnit.assertEquals(ls.toString(), i == none, fs.get(i).getAsBoolean());
            AssertJUnit.assertEquals(ls.toString(), i == none, gs.getAsJsonArray("witnessed").get(i).getAsBoolean());
        }
        AssertJUnit.assertTrue(ls.toString(), gs.getAsJsonArray("line").get(none).getAsString().contains("Monstrosity"));
        AssertJUnit.assertTrue(shadow.bootLine, shadow.bootLine.contains("\"lethalBlock\":\"shadow\""));

        final Run on = reportedLive("lethalBlock=on");
        final JsonObject ln = on.line();
        final JsonObject gn = ln.getAsJsonObject("lethalBlock");
        AssertJUnit.assertTrue(ln.toString(), gn.get("rescored").getAsBoolean());
        AssertJUnit.assertFalse(ln.toString(), ln.get("chosen").getAsString().equals("none"));
        AssertJUnit.assertEquals(ln.toString(), "policy", ln.get("chosen").getAsString());
        AssertJUnit.assertFalse("a block is kept: " + ln, on.combat.getAllBlockers().isEmpty());
        final int noneOn = noneIndex(ln);
        AssertJUnit.assertTrue(ln.toString(), gn.getAsJsonArray("witnessed").get(noneOn).getAsBoolean());
        AssertJUnit.assertTrue(ln.toString(), gn.getAsJsonArray("line").get(noneOn).getAsString().contains("[X=3]"));
        final double ms = gn.get("witnessMs").getAsDouble();
        System.err.println("[lethal-witness] reported board, live seat ON: witnessMs=" + ms);
        AssertJUnit.assertTrue(ln.toString(), ms < 300);
    }

    /**
     * S1 on the played path: the Kessig Wolf Run board through the live seat. The policy blocks; the search departs to no
     * blocks (0.40 against 0.25) and ON keeps that declaration: X = 0 at most, so nothing is lethal and nothing is
     * re-scored (c60 re-scored no blocks to a loss and kept the policy's block).
     */
    @Test
    public void theWolfRunBoardIsNotRescoredWhenOn() throws Exception {
        final Board board = (g, ai, opp) -> wolfRun(g, ai, opp, 3, 1);
        final JsonObject lo = live(board, "").line();
        AssertJUnit.assertEquals(lo.toString(), "none", lo.get("chosen").getAsString());
        final Run on = live(board, "lethalBlock=on");
        final JsonObject ln = on.line();
        final JsonObject gn = ln.getAsJsonObject("lethalBlock");
        AssertJUnit.assertNotNull(ln.toString(), gn);
        AssertJUnit.assertFalse(ln.toString(), gn.has("aside"));
        AssertJUnit.assertFalse(ln.toString(), gn.get("rescored").getAsBoolean());
        for (com.google.gson.JsonElement e : gn.getAsJsonArray("lethal")) {
            AssertJUnit.assertFalse(ln.toString(), e.getAsBoolean());
        }
        AssertJUnit.assertEquals(ln.toString(), "none", ln.get("chosen").getAsString());
        AssertJUnit.assertTrue("no blocks, as off: " + ln, on.combat.getAllBlockers().isEmpty());
    }

    /** The Springleaf Drum board: ai at 6 with a Centaur Courser; opp's attacking Root-Kin Ally, Llanowar Elves, Grizzly Bears, Springleaf Drum, Captive Flame. */
    private Card[] drumBoard(Game g, Player ai, Player opp) {
        ai.setLife(6, null);
        final Card attacker = addCard("Root-Kin Ally", opp);
        attacker.setSickness(false);
        addCard("Llanowar Elves", opp).setSickness(false);
        addCard("Grizzly Bears", opp).setSickness(false);
        addCard("Springleaf Drum", opp);
        addCard("Captive Flame", opp);
        final Card blocker = addCard("Centaur Courser", ai);
        blocker.setSickness(false);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        return new Card[] {attacker, blocker};
    }

    /**
     * Re-review S1: Springleaf Drum's mana ability taps another creature, which the Ally's "tap two untapped creatures"
     * may also tap. The real most is 5 at 6 life; 6c45 credited the Drum's mana on its own (6, lethal) and ON re-scored
     * no blocks to a loss. A mana ability whose cost takes another permanent now stands aside: nothing re-scored, and the
     * live seat keeps off's no blocks.
     */
    @Test
    public void aManaAbilityThatTapsAnotherCreatureStandsAside() throws Exception {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = drumBoard(g, ai, opp);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0]), plan());
        AssertJUnit.assertNotNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), lc.aside.contains("takes another permanent"));
        final double[][] v = {{0.55, 0.55}, {0.70, 0.70}};
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(new double[][] {{0.55, 0.55}, {0.70, 0.70}}, v));
        final JsonObject lo = live(this::drumBoard, "").line();
        AssertJUnit.assertEquals(lo.toString(), "none", lo.get("chosen").getAsString());
        final JsonObject ln = live(this::drumBoard, "lethalBlock=on").line();
        final JsonObject gn = ln.getAsJsonObject("lethalBlock");
        AssertJUnit.assertNotNull(ln.toString(), gn);
        AssertJUnit.assertFalse(ln.toString(), gn.has("rescored") && gn.get("rescored").getAsBoolean());
        AssertJUnit.assertEquals(ln.toString(), "none", ln.get("chosen").getAsString());
    }

    /**
     * The stand-aside is narrow: a source whose cost is only its own tap and its own sacrifice (Lotus Petal) still counts.
     * The Wolf Run board with a Petal for the second Forest: 4 mana, X = 1, no blocks takes 3, lethal at 3.
     */
    @Test
    public void aSelfSacrificeManaSourceStillCounts() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = wolfRun(g, ai, opp, 3, 1);
        addCard("Lotus Petal", opp);
        g.getAction().checkStateEffects(true);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0]), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 4, lc.mana);
        AssertJUnit.assertEquals(flags(lc), 3, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, true}, lc.lethal));
    }

    /** The Workhorse board: ai at 2 with a Grizzly Bears; opp's attacking Carrion Ants, a Workhorse with one +1/+1 counter, Power Conduit. */
    private Card[] counterBoard(Game g, Player ai, Player opp) {
        ai.setLife(2, null);
        final Card attacker = addCard("Carrion Ants", opp);
        attacker.setSickness(false);
        final Card horse = addCard("Workhorse", opp);
        horse.setSickness(false);
        horse.setCounters(forge.game.card.CounterEnumType.P1P1, 1);
        addCard("Power Conduit", opp);
        final Card blocker = addCard("Grizzly Bears", ai);
        blocker.setSickness(false);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        return new Card[] {attacker, blocker};
    }

    /**
     * Re-review 2 S1: Workhorse's one counter buys either its {C} (the Ants' +1) or Power Conduit's +1/+1 counter, not
     * both: the real most is 1 at 2 life. fa29 let the source pay from itself and the Conduit remove the same counter
     * (2, lethal) and ON re-scored no blocks. A mana ability paid with anything but mana, its own tap or its own sacrifice
     * now stands aside (and so does a pump cost that removes counters): nothing re-scored, the live seat keeps none.
     */
    @Test
    public void aCounterPaidManaAbilityStandsAside() throws Exception {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = counterBoard(g, ai, opp);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0]), plan());
        AssertJUnit.assertNotNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), lc.aside.contains("shared pool"));
        final double[][] v = {{0.40, 0.40}, {0.55, 0.55}};
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(new double[][] {{0.40, 0.40}, {0.55, 0.55}}, v));
        final JsonObject lo = live(this::counterBoard, "").line();
        AssertJUnit.assertEquals(lo.toString(), "none", lo.get("chosen").getAsString());
        final JsonObject ln = live(this::counterBoard, "lethalBlock=on").line();
        final JsonObject gn = ln.getAsJsonObject("lethalBlock");
        AssertJUnit.assertNotNull(ln.toString(), gn);
        AssertJUnit.assertFalse(ln.toString(), gn.has("rescored") && gn.get("rescored").getAsBoolean());
        AssertJUnit.assertEquals(ln.toString(), "none", ln.get("chosen").getAsString());
    }

    /**
     * Two pumps whose costs take creatures from one pool: Bloodthrone Vampire (sacrifice a creature: +2/+2) attacks; Mogg
     * Raider (sacrifice a Goblin: target creature +1/+1) and Goblin Piker stay home. Each alone could use both Goblins,
     * but there are only two: the guard stands aside rather than count four sacrifices.
     */
    @Test
    public void twoPumpsSharingSacrificeFodderStandAside() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(7, null);
        final Card attacker = addCard("Bloodthrone Vampire", opp);
        attacker.setSickness(false);
        addCard("Mogg Raider", opp).setSickness(false);
        addCard("Goblin Piker", opp).setSickness(false);
        final Card blocker = addCard("Grizzly Bears", ai);
        blocker.setSickness(false);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        attack(g, opp, ai, attacker);
        final LookaheadSearch.LethalCheck lc = check(g, plan(blocker, attacker), plan());
        AssertJUnit.assertNotNull(flags(lc), lc.aside);
        AssertJUnit.assertTrue(flags(lc), lc.aside.contains("same pool"));
    }

    /**
     * An attacker is never fodder for a sacrifice cost (it would leave combat): a lone attacking Bloodthrone Vampire
     * adds nothing (1 at 3 life, not lethal; fa29 counted sacrificing itself: 3). With two Grizzly Bears at home it may
     * sacrifice both: 1 + 4 = 5, lethal at 5.
     */
    @Test
    public void anAttackerIsNeverItsOwnFodder() {
        Game g = game();
        Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(3, null);
        Card attacker = addCard("Bloodthrone Vampire", opp);
        attacker.setSickness(false);
        Card blocker = addCard("Grizzly Bears", ai);
        blocker.setSickness(false);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        attack(g, opp, ai, attacker);
        LookaheadSearch.LethalCheck lc = check(g, plan(blocker, attacker), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 1, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, false}, lc.lethal));

        g = game();
        ai = g.getPlayers().get(0);
        opp = g.getPlayers().get(1);
        ai.setLife(5, null);
        attacker = addCard("Bloodthrone Vampire", opp);
        attacker.setSickness(false);
        addCard("Grizzly Bears", opp).setSickness(false);
        addCard("Grizzly Bears", opp).setSickness(false);
        blocker = addCard("Grizzly Bears", ai);
        blocker.setSickness(false);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        attack(g, opp, ai, attacker);
        lc = check(g, plan(blocker, attacker), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 5, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, true}, lc.lethal));
    }

    /** The Wild Cantor board: ai at 2 with a Grizzly Bears; opp's attacking Wild Cantor and Captive Flame, no other mana. */
    private Card[] cantorBoard(Game g, Player ai, Player opp) {
        ai.setLife(2, null);
        final Card attacker = addCard("Wild Cantor", opp);
        attacker.setSickness(false);
        addCard("Captive Flame", opp);
        final Card blocker = addCard("Grizzly Bears", ai);
        blocker.setSickness(false);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        return new Card[] {attacker, blocker};
    }

    /**
     * Re-review 3 S1: Wild Cantor's mana costs its own sacrifice; spent, the Cantor leaves combat. Kept, it deals 1 at 2
     * life. 333d counted its mana for the Flame and its pumped damage too (2, lethal) and ON re-scored no blocks. An
     * attacker's self-sacrifice mana is no longer counted: 1, nothing lethal, the live seat keeps none.
     */
    @Test
    public void anAttackerSacrificedForManaIsNotAlsoDamage() throws Exception {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = cantorBoard(g, ai, opp);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0]), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 0, lc.mana);
        AssertJUnit.assertEquals(flags(lc), 1, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, false}, lc.lethal));
        final JsonObject lo = live(this::cantorBoard, "").line();
        AssertJUnit.assertEquals(lo.toString(), "none", lo.get("chosen").getAsString());
        final JsonObject ln = live(this::cantorBoard, "lethalBlock=on").line();
        final JsonObject gn = ln.getAsJsonObject("lethalBlock");
        AssertJUnit.assertNotNull(ln.toString(), gn);
        AssertJUnit.assertFalse(ln.toString(), gn.has("rescored") && gn.get("rescored").getAsBoolean());
        AssertJUnit.assertEquals(ln.toString(), "none", ln.get("chosen").getAsString());
    }

    /**
     * The pump side of the same rule: an attacking Child of Thorns (sacrifice it: target creature +1/+1) and an attacking
     * Grizzly Bears at 4 life. Kept or sacrificed for the Bears, 3 either way; 333d counted the Child's 1 and its +1 on
     * the Bears (4, lethal). An attacker that leaves to pay its own cost is not counted as a pump: 3, not lethal.
     */
    @Test
    public void anAttackerSacrificedForAPumpIsNotAlsoDamage() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card child = addCard("Child of Thorns", opp);
        child.setSickness(false);
        final Card bears = addCard("Grizzly Bears", opp);
        bears.setSickness(false);
        final Card blocker = addCard("Centaur Courser", ai);
        blocker.setSickness(false);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        g.getAction().checkStateEffects(true);
        attack(g, opp, ai, child, bears);
        final LookaheadSearch.LethalCheck lc = check(g, plan(blocker, bears), plan());
        AssertJUnit.assertNull(flags(lc), lc.aside);
        AssertJUnit.assertEquals(flags(lc), 3, lc.life[1]);
        AssertJUnit.assertTrue(flags(lc), Arrays.equals(new boolean[] {false, false}, lc.lethal));
    }

    // ------------------------------------------------------------------------ round 6: the engine witnesses the bound

    /** A creature of {@code p}'s that may act now (no summoning sickness). */
    private Card creature(String name, Player p) {
        final Card c = addCard(name, p);
        c.setSickness(false);
        return c;
    }

    private void lands(Player p, String name, int n) {
        for (int i = 0; i < n; i++) {
            addCard(name, p);
        }
    }

    /** Both libraries filled and state-based actions checked: the board is ready for {@link #attack}. */
    private void ready(Game g) {
        fillLibrary(g.getPlayers().get(0), 20);
        fillLibrary(g.getPlayers().get(1), 20);
        g.getAction().checkStateEffects(true);
    }

    private static String witnessOf(LookaheadSearch.LethalCheck lc) {
        return flags(lc) + " witnessed=" + Arrays.toString(lc.witnessed) + " line=" + Arrays.toString(lc.witnessLine)
                + " runs=" + lc.witnessRuns + " capped=" + lc.witnessCapped + " ms=" + lc.witnessNanos / 1e6;
    }

    private static void logWitness(String label, LookaheadSearch.LethalCheck lc) {
        System.err.println("[lethal-witness] " + label + ": " + witnessOf(lc));
    }

    /**
     * A sweep board's guard decision. {@code real}: per candidate, whether its real maximum is lethal. The bound's check,
     * then ON with every candidate searched: a candidate whose real maximum is not lethal keeps its values (262814126dc
     * re-scored the ones the bound flagged); one whose real maximum is lethal is re-scored, as there is a candidate that
     * is not. Then the round-6 fields: nothing not really lethal is witnessed; and the witness alone, given every ability
     * of the attacking player's ({@link #witnessAll}), does not witness it either, whatever the bound did.
     */
    @SafeVarargs
    private final void sweep(String label, Game g, boolean[] real, Map<Integer, List<Integer>>... plans) {
        final int n = plans.length;
        final LookaheadSearch.LethalCheck lc = check(g, plans);
        final double[][] v = new double[n][2];
        for (int c = 0; c < n; c++) {
            Arrays.fill(v[c], 0.30 + 0.10 * c);
        }
        final boolean[] ok = new boolean[n];
        Arrays.fill(ok, true);
        final LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, lc, v, ok);
        boolean anySafe = false;
        for (boolean x : real) {
            anySafe |= !x;
        }
        for (int c = 0; c < n; c++) {
            final boolean rescore = lc.aside == null && real[c] && anySafe;
            AssertJUnit.assertEquals(label + " candidate " + c + " values: " + flags(lc) + " " + Arrays.deepToString(v),
                    rescore ? 0.0 : 0.30 + 0.10 * c, v[c][0], 1e-12);
        }
        AssertJUnit.assertEquals(label + ": " + flags(lc), lc.aside == null && anySafe && contains(real, true), r.lethalRescored);
        logWitness(label, lc);
        if (lc.aside == null) {
            for (int c = 0; c < n; c++) {
                AssertJUnit.assertEquals(label + " candidate " + c + ": " + witnessOf(lc), lc.lethal[c] && real[c], lc.witnessed[c]);
            }
        }
        for (int c = 0; c < n; c++) {
            if (!real[c]) {
                final LookaheadSearch.LethalCheck all = witnessAll(g, plans[c]);
                logWitness(label + " candidate " + c + ", every ability", all);
                AssertJUnit.assertFalse(label + " candidate " + c + ": " + witnessOf(all), all.witnessed[0]);
            }
        }
    }

    private static boolean contains(boolean[] a, boolean x) {
        for (boolean b : a) {
            if (b == x) {
                return true;
            }
        }
        return false;
    }

    /**
     * The witness alone on {@code plan}, given every non-mana ability of the attacking player's battlefield and of the hand
     * cards the seat knows, each once per attacker it may target, repeated while payable (up to 30 times; X at the most);
     * with a budget large enough that it never runs out (asserted).
     */
    private static LookaheadSearch.LethalCheck witnessAll(Game g, Map<Integer, List<Integer>> plan) {
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final List<Card> atk = g.getPhaseHandler().getCombat().getAttackers();
        final List<LookaheadSearch.LbLine> lines = new ArrayList<>();
        final List<Card> cards = new ArrayList<>(opp.getCardsIn(ZoneType.Battlefield));
        for (Card c : opp.getCardsIn(ZoneType.Hand)) {
            if (LookaheadSearch.pumpKnows(ai, c)) {
                cards.add(c);
            }
        }
        for (Card c : cards) {
            lines.addAll(LookaheadSearch.lbLinesFor(c, opp, atk, LookaheadSearch.WITNESS_REPEATS));
        }
        final LookaheadSearch s = new LookaheadSearch(config(AiFixes.Mode.ON));
        s.witnessLineCap = 1000;
        s.witnessWallMs = 120_000;
        try {
            final LookaheadSearch.LethalCheck lc = s.lethalWitnessOf(g, ai, Arrays.asList(plan), lines);
            AssertJUnit.assertFalse("the exhaustive witness ran out of budget: " + witnessOf(lc), lc.witnessCapped);
            return lc;
        } finally {
            s.shutdown();
        }
    }

    /** The witness alone on {@code plans}, given {@code lines}, with the guard's own budget. */
    @SafeVarargs
    private static LookaheadSearch.LethalCheck witness(Game g, List<LookaheadSearch.LbLine> lines,
            Map<Integer, List<Integer>>... plans) {
        final LookaheadSearch s = new LookaheadSearch(config(AiFixes.Mode.ON));
        try {
            return s.lethalWitnessOf(g, g.getPlayers().get(0), Arrays.asList(plans), lines);
        } finally {
            s.shutdown();
        }
    }

    // ------------------------------------------- Astra re-review 4 (recheck4/findings.md): the 14 findings' 21 boards

    /**
     * Finding 1, one tapped host pays two untap costs: Grizzly Bears wearing two Umbral Mantles, six Forests, ai at 6. The
     * bound counts both {3},{Q} pumps (6); the second untap cannot be paid on an untapped Bears: 4. A Bears blocks or not.
     */
    @Test
    public void sweep01DuplicateUntapIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(6, null);
        final Card a = creature("Grizzly Bears", opp);
        addCard("Umbral Mantle", opp).attachToEntity(a, null);
        addCard("Umbral Mantle", opp).attachToEntity(a, null);
        lands(opp, "Forest", 6);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("duplicateUntap", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 2a, payments keep the bonus they remove: Metallic Sliver; Basal Sliver, Muscle Sliver, Dragon Blood; ai at 3 (real 2). */
    @Test
    public void sweep02aSacrificedManaLordIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(3, null);
        final Card a = creature("Metallic Sliver", opp);
        creature("Basal Sliver", opp);
        creature("Muscle Sliver", opp);
        addCard("Dragon Blood", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("sacrificedManaLord", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 2b: Bloodthrone Vampire with Captivating Vampire as fodder; ai at 4 (real 3). */
    @Test
    public void sweep02bSacrificedPoolLordIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card a = creature("Bloodthrone Vampire", opp);
        creature("Captivating Vampire", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("sacrificedPoolLord", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 2c: Grizzly Bears; Juniper Order Advocate wearing Paradise Mantle; Captive Flame; ai at 4 (real 3). */
    @Test
    public void sweep02cTappedManaLordIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card a = creature("Grizzly Bears", opp);
        final Card lord = creature("Juniper Order Advocate", opp);
        addCard("Paradise Mantle", opp).attachToEntity(lord, null);
        addCard("Captive Flame", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("tappedManaLord", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 2d: Duergar Mine-Captain wearing Sword of the Paruns, two Mountains; ai at 5 (real 4). */
    @Test
    public void sweep02dUntappedLordIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(5, null);
        final Card a = creature("Duergar Mine-Captain", opp);
        addCard("Sword of the Paruns", opp).attachToEntity(a, null);
        lands(opp, "Mountain", 2);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("untappedLord", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 3a, keyword grants reuse payment: Carrion Ants, Sunhome, three Mountains, a Plains; ai at 10 (real 5). */
    @Test
    public void sweep03aFreeKeywordPaymentIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(10, null);
        final Card a = creature("Carrion Ants", opp);
        addCard("Sunhome, Fortress of the Legion", opp);
        lands(opp, "Mountain", 3);
        lands(opp, "Plains", 1);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("freeKeywordPayment", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 3b, one activation's double strike on two targets: two Grizzly Bears, Sunhome, three Mountains, a Plains; ai at 8 (real 6). */
    @Test
    public void sweep03bFreeKeywordTargetsIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(8, null);
        final Card a1 = creature("Grizzly Bears", opp);
        final Card a2 = creature("Grizzly Bears", opp);
        addCard("Sunhome, Fortress of the Legion", opp);
        lands(opp, "Mountain", 3);
        lands(opp, "Plains", 1);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a1, a2);
        sweep("freeKeywordTargets", g, new boolean[] {false, false}, plan(b, a1), plan());
    }

    /** Finding 4, X spends mana that cannot be started: Grizzly Bears, two Boros Signets, Puffer Extract; ai at 4 (real 2). */
    @Test
    public void sweep04ManaWithoutSeedIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card a = creature("Grizzly Bears", opp);
        addCard("Boros Signet", opp);
        addCard("Boros Signet", opp);
        addCard("Puffer Extract", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("manaWithoutSeed", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 5, a Forest is not another red: Shivan Dragon, a Mountain, a Forest; ai at 7 (real 6); a Wind Drake may block. */
    @Test
    public void sweep05ColorsSpentTwiceIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(7, null);
        final Card a = creature("Shivan Dragon", opp);
        lands(opp, "Mountain", 1);
        lands(opp, "Forest", 1);
        final Card b = creature("Wind Drake", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("colorsSpentTwice", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 6, restricted mana: Carrion Ants, Mishra's Workshop, a Swamp; ai at 4 (real 1). */
    @Test
    public void sweep06RestrictedManaIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card a = creature("Carrion Ants", opp);
        addCard("Mishra's Workshop", opp);
        addCard("Swamp", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("restrictedMana", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 7, mana replacement: Carrion Ants, Ancient Tomb, Damping Sphere; ai at 2 (real 1). */
    @Test
    public void sweep07ManaReplacementIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(2, null);
        final Card a = creature("Carrion Ants", opp);
        addCard("Ancient Tomb", opp);
        addCard("Damping Sphere", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("manaReplacement", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 8a, the last life buys no damage: opp at 3 with Wall of Blood and Rolling Stones; ai at 3 (real 2). */
    @Test
    public void sweep08aLifePaidToZeroIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(3, null);
        opp.setLife(3, null);
        final Card a = creature("Wall of Blood", opp);
        addCard("Rolling Stones", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("lifePaidToZero", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 8b, a mana source's damage kills its controller: opp at 2, Grizzly Bears, Ancient Tomb, Puffer Extract; ai at 4 (real 2). */
    @Test
    public void sweep08bLethalManaEffectIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        opp.setLife(2, null);
        final Card a = creature("Grizzly Bears", opp);
        addCard("Ancient Tomb", opp);
        addCard("Puffer Extract", opp);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("lethalManaEffect", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 9a, a pump kills its own attacker: Grizzly Bears enchanted with Flowstone Embrace; ai at 4 (real 2). */
    @Test
    public void sweep09aOwnTapKillsAttackerIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card a = creature("Grizzly Bears", opp);
        addCard("Flowstone Embrace", opp).attachToEntity(a, null);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("ownTapKillsAttacker", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 9b: Flowstone Shambler ({R}: +1/-1), three Mountains; ai at 5 (real 3). */
    @Test
    public void sweep09bToughnessPaidToZeroIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(5, null);
        final Card a = creature("Flowstone Shambler", opp);
        lands(opp, "Mountain", 3);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("toughnessPaidToZero", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 10, a pump changes who the next one may hit: Suntail Hawk, two Pendelhaven Elders; ai at 3 (real 2); a Wind Drake may block. */
    @Test
    public void sweep10ChangingEligibilityIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(3, null);
        final Card a = creature("Suntail Hawk", opp);
        creature("Pendelhaven Elder", opp);
        creature("Pendelhaven Elder", opp);
        final Card b = creature("Wind Drake", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("changingEligibility", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /** Finding 11, negative power: Char-Rumbler (-1/3, double strike, {R}: +1/+0), two Mountains; ai at 4 (real 2). */
    @Test
    public void sweep11NegativeBasePowerIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card a = creature("Char-Rumbler", opp);
        lands(opp, "Mountain", 2);
        final Card b = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("negativeBasePower", g, new boolean[] {false, false}, plan(b, a), plan());
    }

    /**
     * Finding 12, trample through unpaid toughness: Defiant Elf (1/1 trample), Captive Flame, a Mountain; ai at 1. Blocked
     * by Centaur Courser the real most is 0 (the bound: 1); with a Grizzly Bears too, 0 (not flagged); unblocked, 1 (lethal,
     * witnessed with no line). ON re-scores only no blocks.
     */
    @Test
    public void sweep12TrampleDeficitIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(1, null);
        final Card a = creature("Defiant Elf", opp);
        addCard("Captive Flame", opp);
        lands(opp, "Mountain", 1);
        final Card courser = creature("Centaur Courser", ai);
        final Card bears = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("trampleDeficit", g, new boolean[] {false, false, true}, plan(courser, a), plan(courser, a, bears, a), plan());
    }

    /**
     * Finding 13a, double strike against protection: Bolrac-Clan Basher (3/2 double strike, trample) blocked by Auriok
     * Champion (protection from red); ai at 5. The bound: 5; the real most 4. A Grizzly Bears also blocking: not flagged;
     * unblocked: 6, lethal.
     */
    @Test
    public void sweep13aProtectionDoubleStrikeIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(5, null);
        final Card a = creature("Bolrac-Clan Basher", opp);
        final Card champ = creature("Auriok Champion", ai);
        final Card bears = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("protectionDoubleStrike", g, new boolean[] {false, false, true}, plan(champ, a), plan(champ, a, bears, a), plan());
    }

    /** Finding 13b, the first-strike exclusion: Bolrac-Clan Basher blocked by Porcelain Legionnaire (it dies first); ai at 5 (real 2). */
    @Test
    public void sweep13bFirstStrikeSurvivalIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(5, null);
        final Card a = creature("Bolrac-Clan Basher", opp);
        final Card leg = creature("Porcelain Legionnaire", ai);
        final Card bears = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("firstStrikeSurvival", g, new boolean[] {false, false, true}, plan(leg, a), plan(leg, a, bears, a), plan());
    }

    /**
     * Finding 14, toxic without a hit: Bloated Contaminator (4/4 trample, toxic 1) blocked by Colossal Dreadmaw, ai at 9
     * poison. The bound gives the toxic counter without player damage (10, lethal); the real block deals none. Unblocked it
     * hits: lethal. Every declaration was flagged at 262814126dc, so nothing was re-scored there; now only no blocks is
     * witnessed and re-scored.
     */
    @Test
    public void sweep14ToxicWithoutHitIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setPoisonCounters(9, opp);
        final Card a = creature("Bloated Contaminator", opp);
        final Card maw = creature("Colossal Dreadmaw", ai);
        ready(g);
        attack(g, opp, ai, a);
        sweep("toxicWithoutHit", g, new boolean[] {false, true}, plan(maw, a), plan());
    }

    /**
     * The earlier boards (Kessig Wolf Run with one Forest, Springleaf Drum, Workhorse, Wild Cantor, Child of Thorns): their
     * tests above keep the bound's stand-aside or not-lethal; here the witness alone, given every ability, does not witness
     * their non-lethal declaration either.
     */
    @Test
    public void theEarlierBoardsAreNotWitnessed() {
        final List<Board> boards = Arrays.asList((g, ai, opp) -> wolfRun(g, ai, opp, 3, 1), this::drumBoard, this::counterBoard,
                this::cantorBoard);
        final String[] names = {"wolfRun", "drum", "workhorse", "cantor"};
        for (int i = 0; i < boards.size(); i++) {
            final Game g = game();
            final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
            final Card[] b = boards.get(i).build(g, ai, opp);
            attack(g, opp, ai, b[0]);
            final LookaheadSearch.LethalCheck lc = witnessAll(g, plan());
            logWitness(names[i] + " (no blocks), every ability", lc);
            AssertJUnit.assertFalse(names[i] + ": " + witnessOf(lc), lc.witnessed[0]);
            final LookaheadSearch.LethalCheck guard = check(g, plan(b[1], b[0]), plan());
            logWitness(names[i], guard);
            AssertJUnit.assertTrue(names[i] + ": " + witnessOf(guard), guard.aside != null || !guard.lethal[1] && !guard.witnessed[1]);
        }
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        final Card child = creature("Child of Thorns", opp);
        final Card bears = creature("Grizzly Bears", opp);
        final Card blocker = creature("Centaur Courser", ai);
        ready(g);
        attack(g, opp, ai, child, bears);
        final LookaheadSearch.LethalCheck lc = witnessAll(g, plan(blocker, bears));
        logWitness("childOfThorns (Courser blocks the Bears), every ability", lc);
        AssertJUnit.assertFalse(witnessOf(lc), lc.witnessed[0]);
    }

    // ---------------------------------------------------------------------------------- positives the engine witnesses

    /** The reported board: only no blocks is flagged, and the engine witnesses it by Monstrosity X = 3, within 300 ms. */
    @Test
    public void theReportedBoardIsWitnessedByMonstrosityX3() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = reported(g, ai, opp, 7);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0], b[2], b[0], b[3], b[0]), plan(b[1], b[0], b[2], b[0]),
                plan(b[2], b[0]), plan());
        logWitness("reported board", lc);
        AssertJUnit.assertNull(witnessOf(lc), lc.aside);
        AssertJUnit.assertTrue(witnessOf(lc), Arrays.equals(new boolean[] {false, false, false, true}, lc.witnessed));
        AssertJUnit.assertTrue(witnessOf(lc), lc.witnessLine[3].contains("Monstrosity") && lc.witnessLine[3].contains("[X=3]"));
        AssertJUnit.assertTrue("under 300 ms: " + witnessOf(lc), lc.witnessNanos < 300_000_000L);
        AssertJUnit.assertFalse(witnessOf(lc), lc.witnessCapped);
        // six lands: the bound does not flag it, so nothing is witnessed or played
        final Game g6 = game();
        final Card[] b6 = reported(g6, g6.getPlayers().get(0), g6.getPlayers().get(1), 6);
        attack(g6, g6.getPlayers().get(1), g6.getPlayers().get(0), b6[0]);
        final LookaheadSearch.LethalCheck lc6 = check(g6, plan(b6[2], b6[0]), plan());
        AssertJUnit.assertEquals(witnessOf(lc6), 0, lc6.witnessRuns);
        // and the witness alone with every ability: X = 2 at most, 7 < 8
        final LookaheadSearch.LethalCheck all6 = witnessAll(g6, plan());
        logWitness("reported board, six lands, every ability", all6);
        AssertJUnit.assertFalse(witnessOf(all6), all6.witnessed[0]);
    }

    /** Firebreathing repeated: Shivan Dragon with three Mountains at 8: {R} three times, 8 (blocked by a Wind Drake: none). */
    @Test
    public void firebreathingRepeatedIsWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(8, null);
        lands(opp, "Mountain", 3);
        final Card dragon = creature("Shivan Dragon", opp);
        final Card drake = creature("Wind Drake", ai);
        ready(g);
        attack(g, opp, ai, dragon);
        final LookaheadSearch.LethalCheck lc = check(g, plan(drake, dragon), plan());
        logWitness("firebreathing", lc);
        AssertJUnit.assertTrue(witnessOf(lc), Arrays.equals(new boolean[] {false, true}, lc.lethal));
        AssertJUnit.assertTrue(witnessOf(lc), Arrays.equals(new boolean[] {false, true}, lc.witnessed));
        AssertJUnit.assertTrue(witnessOf(lc), lc.witnessLine[1].startsWith("Shivan Dragon") && lc.witnessLine[1].contains(" x3"));
        final double[][] v = {{0.25, 0.25}, {0.40, 0.40}};
        AssertJUnit.assertTrue(apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true}).lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(new double[][] {{0.25, 0.25}, {0.0, 0.0}}, v));
    }

    /** Another permanent's targeted pump: Captive Flame twice on the attacking Grizzly Bears, 4 at 4. */
    @Test
    public void anotherPermanentsTargetedPumpIsWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(4, null);
        addCard("Captive Flame", opp);
        lands(opp, "Mountain", 2);
        final Card bear = creature("Grizzly Bears", opp);
        ready(g);
        attack(g, opp, ai, bear);
        final LookaheadSearch.LethalCheck lc = check(g, plan());
        logWitness("targeted pump", lc);
        AssertJUnit.assertTrue(witnessOf(lc), lc.witnessed[0]);
        AssertJUnit.assertTrue(witnessOf(lc), lc.witnessLine[0].startsWith("Captive Flame")
                && lc.witnessLine[0].contains(" x2") && lc.witnessLine[0].contains("-> Grizzly Bears (" + bear.getId() + ")"));
    }

    /** A boosted trampler over a chump: Colossal Dreadmaw plus Captive Flame twice over a Grizzly Bears, 6 at 6. */
    @Test
    public void aBoostedTramplerOverAChumpIsWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(6, null);
        final Card chump = creature("Grizzly Bears", ai);
        addCard("Captive Flame", opp);
        lands(opp, "Mountain", 2);
        final Card maw = creature("Colossal Dreadmaw", opp);
        ready(g);
        attack(g, opp, ai, maw);
        final LookaheadSearch.LethalCheck lc = check(g, plan(chump, maw), plan());
        logWitness("trampler over a chump", lc);
        AssertJUnit.assertTrue(witnessOf(lc), Arrays.equals(new boolean[] {true, true}, lc.witnessed));
        AssertJUnit.assertTrue(witnessOf(lc), lc.witnessLine[0].startsWith("Captive Flame") && lc.witnessLine[0].contains(" x2"));
        AssertJUnit.assertEquals(witnessOf(lc), "nothing", lc.witnessLine[1]);
        ai.setLife(7, null);
        final LookaheadSearch.LethalCheck lc7 = check(g, plan(chump, maw), plan());
        logWitness("trampler over a chump at 7", lc7);
        AssertJUnit.assertTrue(witnessOf(lc7), Arrays.equals(new boolean[] {false, true}, lc7.witnessed));
    }

    /** An unblocked double striker: Fencing Ace deals 2 at 2 with no line; blocked, nothing (not flagged). */
    @Test
    public void anUnblockedDoubleStrikerIsWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(2, null);
        final Card ace = creature("Fencing Ace", opp);
        final Card bear = creature("Grizzly Bears", ai);
        ready(g);
        attack(g, opp, ai, ace);
        final LookaheadSearch.LethalCheck lc = check(g, plan(bear, ace), plan());
        logWitness("double striker", lc);
        AssertJUnit.assertTrue(witnessOf(lc), Arrays.equals(new boolean[] {false, true}, lc.witnessed));
        AssertJUnit.assertEquals(witnessOf(lc), "nothing", lc.witnessLine[1]);
    }

    /** Kessig Wolf Run with a spare source: a Mountain and two Forests make X = 1 at most: 3 at 3, witnessed at X = 1. */
    @Test
    public void wolfRunWithASpareSourceIsWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = wolfRun(g, ai, opp, 3, 2);
        attack(g, opp, ai, b[0]);
        final LookaheadSearch.LethalCheck lc = check(g, plan(b[1], b[0]), plan());
        logWitness("wolf run, spare source", lc);
        AssertJUnit.assertTrue(witnessOf(lc), Arrays.equals(new boolean[] {false, true}, lc.witnessed));
        AssertJUnit.assertTrue(witnessOf(lc), lc.witnessLine[1].startsWith("Kessig Wolf Run") && lc.witnessLine[1].contains("[X=1]"));
    }

    // ------------------------------------------------------------------------------------------- hidden information

    /**
     * Stormchaser Chimera ("+X/+0, X = the revealed top card's mana value") at 8 life: the bound stands aside, and the
     * witness alone, given the Chimera's ability, decides the same with a Forest or Emrakul, the Aeons Torn on top: the copy's
     * library is placeholders, so the top it reveals is never the real one (with Emrakul, 2 + 15 would be lethal).
     */
    @Test
    public void theWitnessNeverReadsAHiddenLibraryTop() {
        final String[] tops = {"Forest", "Emrakul, the Aeons Torn"};
        final String[] results = new String[2];
        for (int j = 0; j < 2; j++) {
            final Game g = game();
            final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
            ai.setLife(8, null);
            final Card chimera = creature("Stormchaser Chimera", opp);
            final Card drake = creature("Wind Drake", ai);
            for (String land : new String[] {"Island", "Mountain", "Forest", "Forest"}) {
                addCard(land, opp);
            }
            fillLibrary(ai, 20);
            final Card top = addCardToZone(tops[j], opp, ZoneType.Library);
            g.getAction().checkStateEffects(true);
            attack(g, opp, ai, chimera);
            final LookaheadSearch.LethalCheck lc = witness(g,
                    Arrays.asList(LookaheadSearch.lbLineFor(chimera, opp, new ArrayList<>())), plan(drake, chimera), plan());
            logWitness("chimera, " + tops[j] + " on top", lc);
            AssertJUnit.assertTrue(tops[j] + ": " + witnessOf(lc), Arrays.equals(new boolean[] {false, false}, lc.witnessed));
            AssertJUnit.assertEquals(top, opp.getCardsIn(ZoneType.Library).get(0));
            results[j] = Arrays.toString(lc.witnessed) + " " + Arrays.toString(lc.witnessLine);
        }
        AssertJUnit.assertEquals("the same result whatever the hidden top", results[0], results[1]);
    }

    /**
     * A Giant Growth in the attacking player's hand that ai has not seen is not in the witness's copy: given its line (as
     * if the bound had counted it), the witness does not witness 2 + 3 at 5. Revealed to ai, it does (and the guard's own
     * check flags and witnesses it).
     */
    @Test
    public void aHiddenHandPumpIsNotWitnessed() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        ai.setLife(5, null);
        addCard("Forest", opp);
        final Card growth = addCardToZone("Giant Growth", opp, ZoneType.Hand);
        final Card bear = creature("Grizzly Bears", opp);
        ready(g);
        attack(g, opp, ai, bear);
        final List<LookaheadSearch.LbLine> line = Arrays.asList(LookaheadSearch.lbLineFor(growth, opp, Arrays.asList(bear)));
        LookaheadSearch.LethalCheck lc = witness(g, line, plan());
        logWitness("hidden Giant Growth", lc);
        AssertJUnit.assertFalse(witnessOf(lc), lc.witnessed[0]);
        AssertJUnit.assertEquals(1, opp.getCardsIn(ZoneType.Hand).size());
        growth.addMayLookAt(g.getNextTimestamp(), Arrays.asList(ai));
        lc = witness(g, line, plan());
        logWitness("revealed Giant Growth", lc);
        AssertJUnit.assertTrue(witnessOf(lc), lc.witnessed[0]);
        lc = check(g, plan());
        AssertJUnit.assertTrue(witnessOf(lc), lc.lethal[0] && lc.witnessed[0] && lc.witnessLine[0].startsWith("Giant Growth"));
    }

    // ------------------------------------------------------------------------------------------------- live state

    /**
     * The guard leaves the live game as it is with the guard off (where nothing runs): after the bound and the witness's
     * lines on the reported board, the live game's card-id counters, the global id counters, this thread's id scope, its
     * random stream (the same object, no draw taken) and its AI-cache scope (the same map, nothing added) are unchanged,
     * and so are the hands, libraries, life totals and blocks.
     */
    @Test
    public void theWitnessLeavesTheLiveStateAsOff() {
        final Game g = game();
        final Player ai = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Card[] b = reported(g, ai, opp, 7);
        attack(g, opp, ai, b[0]);
        final List<Map<Integer, List<Integer>>> plans = Arrays.asList(plan(b[2], b[0]), plan());
        final LookaheadSearch s = new LookaheadSearch(config(AiFixes.Mode.ON));
        final java.util.Random wasRandom = forge.util.MyRandom.getThreadRandom();
        final Object wasIds = forge.util.IdScope.capture();
        final Object wasCache = forge.ai.AiCache.captureScope();
        try {
            final java.util.Random rnd = new java.util.Random(77);
            forge.util.MyRandom.setThreadRandom(rnd);
            forge.util.IdScope.open();
            final Object ids = forge.util.IdScope.capture();
            forge.ai.AiCache.openScope();
            final Object cache = forge.ai.AiCache.captureScope();
            final String before = liveState(g, ids, cache);
            final LookaheadSearch.LethalCheck lc = s.lethalBlocksOf(g, ai, plans);
            logWitness("live state", lc);
            AssertJUnit.assertTrue(witnessOf(lc), lc.witnessed[1] && lc.witnessRuns > 0);
            AssertJUnit.assertSame(rnd, forge.util.MyRandom.getThreadRandom());
            AssertJUnit.assertEquals("no draw from the live stream", new java.util.Random(77).nextLong(), rnd.nextLong());
            AssertJUnit.assertSame(ids, forge.util.IdScope.capture());
            AssertJUnit.assertSame(cache, forge.ai.AiCache.captureScope());
            AssertJUnit.assertEquals(before, liveState(g, ids, cache));
        } finally {
            s.shutdown();
            forge.ai.AiCache.installScope(wasCache);
            forge.util.IdScope.install(wasIds);
            forge.util.MyRandom.setThreadRandom(wasRandom);
        }
    }

    private static String liveState(Game g, Object ids, Object cache) {
        final StringBuilder sb = new StringBuilder();
        sb.append("cards=").append(Arrays.toString(g.peekCardIdCounters()));
        sb.append(" scope=").append(Arrays.toString(forge.util.IdScope.peek(ids)));
        sb.append(" global=").append(forge.game.spellability.SpellAbility.peekMaxId()).append('/')
                .append(forge.game.trigger.Trigger.peekMaxId()).append('/').append(forge.game.replacement.ReplacementEffect.peekMaxId())
                .append('/').append(forge.game.staticability.StaticAbility.peekMaxId()).append('/')
                .append(forge.game.spellability.SpellAbilityStackInstance.peekMaxId()).append('/').append(Game.peekMaxId());
        sb.append(" cache=").append(((com.google.common.collect.Multimap<?, ?>) cache).size());
        for (Player p : g.getPlayers()) {
            sb.append(" | ").append(p.getName()).append(" life=").append(p.getLife()).append(" poison=").append(p.getPoisonCounters());
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Battlefield, ZoneType.Graveyard}) {
                sb.append(' ').append(z).append('=');
                for (Card c : p.getCardsIn(z)) {
                    sb.append(c.getId()).append(c.isTapped() ? "t" : "").append(c.getCounters().isEmpty() ? "" : "c").append(',');
                }
            }
        }
        sb.append(" blockers=").append(g.getPhaseHandler().getCombat().getAllBlockers().size());
        sb.append(" phase=").append(g.getPhaseHandler().getPhase()).append(" stack=").append(g.getStack().size());
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------- the modes, round 6

    /**
     * Only a witnessed candidate is re-scored: one the bound flags but the engine does not witness keeps its values (and
     * counts as a candidate that is not lethal); a check without witnesses re-scores nothing.
     */
    @Test
    public void onlyAWitnessedCandidateIsRescored() {
        double[][] v = values();
        LookaheadSearch.LethalCheck lc = flagged(false, false, true);
        lc.witnessed = new boolean[3];
        LookaheadSearch.GivenResult r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
        AssertJUnit.assertTrue(Arrays.equals(new boolean[] {false, false, true}, r.lethal));
        AssertJUnit.assertTrue(Arrays.equals(new boolean[3], r.witnessed));
        v = values();
        lc = flagged(true, true, true);
        lc.witnessed = null;
        r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
        // all three flagged, two witnessed: the unwitnessed one is the candidate that is not lethal
        v = values();
        lc = flagged(true, true, true);
        lc.witnessed = new boolean[] {true, false, true};
        lc.witnessLine = new String[] {"a", null, "c"};
        r = apply(AiFixes.Mode.ON, lc, v, new boolean[] {true, true, true});
        AssertJUnit.assertTrue(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(new double[][] {{0.0, 0.0}, values()[1], {0.0, 0.0}}, v));
        AssertJUnit.assertEquals("c", r.witnessLine[2]);
        // SHADOW: the same check flags without changing values
        v = values();
        r = apply(AiFixes.Mode.SHADOW, lc, v, new boolean[] {true, true, true});
        AssertJUnit.assertFalse(r.lethalRescored);
        AssertJUnit.assertTrue(Arrays.deepEquals(values(), v));
        AssertJUnit.assertTrue(Arrays.equals(new boolean[] {true, false, true}, r.witnessed));
    }
}
