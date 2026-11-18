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

    // ------------------------------------------------------------------------------------------------ the modes

    private static double[][] values() {
        return new double[][] {{0.10, 0.12}, {0.09, 0.08}, {0.32, 0.33}};
    }

    private static LookaheadSearch.LethalCheck flagged(boolean... lethal) {
        final LookaheadSearch.LethalCheck lc = new LookaheadSearch.LethalCheck();
        lc.lethal = lethal;
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

    /** The reported board through the live seat: blocks=1, both play-out seats the scripted policy, plus {@code extra}. */
    private Run reportedLive(final String extra) throws Exception {
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
            final Card[] b = reported(game, p0, p1, 7);
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
     * nothing re-scored. ON: no blocks scores as a loss and the search keeps a block.
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
        }
        AssertJUnit.assertTrue(shadow.bootLine, shadow.bootLine.contains("\"lethalBlock\":\"shadow\""));

        final Run on = reportedLive("lethalBlock=on");
        final JsonObject ln = on.line();
        AssertJUnit.assertTrue(ln.toString(), ln.getAsJsonObject("lethalBlock").get("rescored").getAsBoolean());
        AssertJUnit.assertFalse(ln.toString(), ln.get("chosen").getAsString().equals("none"));
        AssertJUnit.assertFalse("a block is kept: " + ln, on.combat.getAllBlockers().isEmpty());
    }
}
