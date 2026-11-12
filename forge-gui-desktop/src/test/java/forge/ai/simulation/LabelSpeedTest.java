package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * label-speed-1104: the R3 label-speed keys. Off = absent from the identity (every frozen spec's sha unchanged); each key on
 * changes the sha; the proof cache key (b) separates every state difference the proof reads and joins equal states; the
 * spread order (a, d) is a permutation whose prefixes cover the strata.
 */
public class LabelSpeedTest extends SimulationTest {

    static final String[] KEYS = {"proofCache", "probeWorlds", "selPrune", "tgtStop", "proofPrePct"};

    @Test
    public void offKeysLeaveTheIdentity() {
        final String base = R3LabelSearch.Spec.parse(new JsonObject()).sha();
        final JsonObject off = new JsonObject();
        off.addProperty("proofCache", false);
        off.addProperty("probeWorlds", 0);
        off.addProperty("selPrune", 0);
        off.addProperty("tgtStop", 0);
        off.addProperty("proofPrePct", 0);
        final R3LabelSearch.Spec s = R3LabelSearch.Spec.parse(off);
        AssertJUnit.assertEquals("keys at their off values: the same sha", base, s.sha());
        for (String k : KEYS) {
            AssertJUnit.assertFalse(k + " is not in an off identity", s.identity().has(k));
        }
    }

    @Test
    public void eachKeyOnChangesTheIdentity() {
        final String base = R3LabelSearch.Spec.parse(new JsonObject()).sha();
        final Set<String> shas = new HashSet<>();
        for (String k : KEYS) {
            final JsonObject o = new JsonObject();
            if ("proofCache".equals(k)) o.addProperty(k, true);
            else o.addProperty(k, 2);
            final String sha = R3LabelSearch.Spec.parse(o).sha();
            AssertJUnit.assertFalse(k + " on changes the sha", base.equals(sha));
            AssertJUnit.assertTrue(k + ": a sha of its own", shas.add(sha));
        }
    }

    @Test
    public void keysOutOfRangeAreRefused() {
        for (String[] kv : new String[][] {{"proofPrePct", "100"}, {"selPrune", "-1"}, {"probeWorlds", "9"}}) {
            final JsonObject o = new JsonObject();
            o.addProperty(kv[0], Integer.parseInt(kv[1]));
            try {
                R3LabelSearch.Spec.parse(o);
                AssertJUnit.fail(kv[0] + "=" + kv[1] + " accepted");
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void spreadIsAPermutationWithEvenPrefixes() {
        for (int W = 1; W <= 16; W++) {
            final int[] o = R3LabelSearch.spread(W);
            final Set<Integer> seen = new HashSet<>();
            for (int w : o) AssertJUnit.assertTrue(seen.add(w) && w >= 0 && w < W);
            AssertJUnit.assertEquals(W, seen.size());
            if (W == 16) {
                // the first 4 hit every quarter of the sorted strata
                final Set<Integer> q = new HashSet<>();
                for (int k = 0; k < 4; k++) q.add(o[k] / 4);
                AssertJUnit.assertEquals(4, q.size());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ (b) the cache key

    private Game board() {
        final Game g = initAndCreateGame();
        final Player a = g.getPlayers().get(0), b = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (int i = 0; i < 6; i++) addCardToZone(i % 2 == 0 ? "Forest" : "Island", a, ZoneType.Library);
        for (int i = 0; i < 6; i++) addCardToZone("Plains", b, ZoneType.Library);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        addCardToZone("Counterspell", b, ZoneType.Hand);
        addCard("Mountain", a);
        addCard("Island", b);
        addCard("Grizzly Bears", b).setSickness(false);
        addCard("Llanowar Elves", a).setSickness(false);
        g.getAction().checkStateEffects(true);
        return g;
    }

    private static Card find(final Game g, final String name) {
        for (Card c : g.getCardsInGame()) {
            if (c.getName().equals(name)) return c;
        }
        throw new IllegalStateException(name);
    }

    @Test
    public void proofKeyJoinsEqualStatesAndSeparatesEveryDifference() {
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(7L));
        final Game g0 = board();
        final String k0 = R3LabelSearch.proofKey(g0, 11L, 3);
        AssertJUnit.assertEquals("the same state, seed and turn: one key", k0, R3LabelSearch.proofKey(g0, 11L, 3));
        MyRandom.getRandom().nextInt(); // the line's own stream moved: the proof never reads it
        AssertJUnit.assertEquals("the line's random stream is not in the key", k0, R3LabelSearch.proofKey(g0, 11L, 3));
        AssertJUnit.assertFalse("another seed", k0.equals(R3LabelSearch.proofKey(g0, 12L, 3)));
        AssertJUnit.assertFalse("another turn", k0.equals(R3LabelSearch.proofKey(g0, 11L, 4)));

        final List<String> names = new ArrayList<>();
        final List<Consumer<Game>> edits = new ArrayList<>();
        names.add("mana pool");
        edits.add(g -> g.getPlayers().get(0).getManaPool().addMana(new Mana((byte) ManaAtom.GREEN, find(g, "Llanowar Elves"), null, g.getPlayers().get(0))));
        names.add("damage");
        edits.add(g -> find(g, "Grizzly Bears").setDamage(1));
        names.add("summoning sickness");
        edits.add(g -> find(g, "Llanowar Elves").setSickness(true));
        names.add("storm count");
        edits.add(g -> {
            final SpellAbility sa = find(g, "Lightning Bolt").getFirstSpellAbility();
            sa.setActivatingPlayer(g.getPlayers().get(0));
            g.getStack().getSpellsCastThisTurn().add(sa);
        });
        names.add("spells cast this game");
        edits.add(g -> g.getPlayers().get(0).addSpellCastThisTurn());
        names.add("counters");
        edits.add(g -> find(g, "Grizzly Bears").addCounterInternal(CounterEnumType.P1P1, 1, g.getPlayers().get(1), false, null, null));
        names.add("tapped");
        edits.add(g -> find(g, "Mountain").setTapped(true));
        names.add("life");
        edits.add(g -> g.getPlayers().get(1).setLife(19, null));
        names.add("library order");
        edits.add(g -> {
            final forge.game.card.CardCollection lib = new forge.game.card.CardCollection(g.getPlayers().get(0).getCardsIn(ZoneType.Library));
            final Card top = lib.remove(0);
            lib.add(top);
            g.getPlayers().get(0).getZone(ZoneType.Library).setCards(lib);
        });
        names.add("hand");
        edits.add(g -> g.getAction().moveTo(ZoneType.Graveyard, find(g, "Counterspell"), null, null));
        names.add("stack");
        edits.add(g -> {
            final Card bolt = find(g, "Lightning Bolt");
            final SpellAbility sa = bolt.getFirstSpellAbility();
            sa.setActivatingPlayer(g.getPlayers().get(0));
            sa.getTargets().add(g.getPlayers().get(1));
            g.getStack().add(sa);
        });
        names.add("phase");
        edits.add(g -> g.getPhaseHandler().devModeSet(PhaseType.MAIN2, g.getPlayers().get(0)));
        // the lines' games are copies of one live game: equal copies share the key, each edit separates it
        final String kc = R3LabelSearch.proofKey(copy(g0), 11L, 3);
        AssertJUnit.assertEquals("two copies of one state: one key", kc, R3LabelSearch.proofKey(copy(g0), 11L, 3));
        final Set<String> keys = new HashSet<>();
        keys.add(kc);
        for (int i = 0; i < edits.size(); i++) {
            final Game g = copy(g0);
            AssertJUnit.assertEquals("a fresh copy: the base key", kc, R3LabelSearch.proofKey(g, 11L, 3));
            edits.get(i).accept(g);
            final String k = R3LabelSearch.proofKey(g, 11L, 3);
            AssertJUnit.assertTrue(names.get(i) + " separates the key", keys.add(k));
        }
    }

    private static Game copy(final Game g) {
        final GameCopier c = new GameCopier(g, true);
        c.setCopyFidelity(1);
        return c.makeCopy();
    }
}
