package forge.bench.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.bench.CallCounter;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Lane r4-payment-1015: the mana source of one shard. {@link RlManaChoice} groups Forge AI's acceptable sources into
 * materially different options without moving the game, and {@link ComputerUtilMana.ShardSourceChooser} lets a
 * controller pay with another one. Positions are built directly (no game loop); the payment is Forge AI's own
 * {@code payManaCost} of a card in hand.
 */
public class RlManaChoiceTest extends AITest {

    /** A Forge AI controller that enumerates every shard it pays and answers with {@link #pick}. */
    static final class Chooser extends PlayerControllerAi implements ComputerUtilMana.ShardSourceChooser {
        final List<RlManaChoice.Ask> asks = new ArrayList<>();
        final List<ManaCostShard> shards = new ArrayList<>();
        Function<RlManaChoice.Ask, SpellAbility> pick = a -> null;
        boolean enumerate = true;
        boolean restCheck = true;
        int refused;

        Chooser(final Game g, final Player p) {
            super(g, p, p.getController().getLobbyPlayer());
        }

        @Override
        public SpellAbility chooseShardSource(final ManaCostBeingPaid cost, final SpellAbility sa,
                final ManaCostShard toPay, final List<SpellAbility> candidates, final boolean checkCosts,
                final boolean effect) {
            if (!enumerate) {
                return null;
            }
            final RlManaChoice.Ask a = RlManaChoice.enumerate(getGame(), getPlayer(), cost, sa, toPay, candidates,
                    checkCosts, effect, restCheck);
            asks.add(a);
            shards.add(toPay);
            return pick.apply(a);
        }

        @Override
        public void shardSourceRefused(final SpellAbility pick) {
            refused++;
        }
    }

    static final class Pos {
        Game game;
        Player ai;
        Chooser chooser;
        Card spell;
        List<Card> islands = new ArrayList<>();
        Card volcanic, petal;
    }

    /** Island x2, Volcanic Island, Lotus Petal; Brainstorm ({U}) in hand. {@code chooser}: install the chooser. */
    private Pos position(final boolean chooser, final boolean volcanic, final boolean petal) {
        final Pos p = new Pos();
        p.game = initAndCreateGame();
        p.ai = p.game.getPlayers().get(1);
        fillLibrary(p.ai, 10);
        fillLibrary(p.game.getPlayers().get(0), 10);
        p.islands = addCards("Island", 2, p.ai);
        if (volcanic) {
            p.volcanic = addCard("Volcanic Island", p.ai);
        }
        if (petal) {
            p.petal = addCard("Lotus Petal", p.ai);
        }
        p.spell = addCardToZone("Brainstorm", p.ai, ZoneType.Hand);
        p.game.getAction().checkStateEffects(true);
        if (chooser) {
            p.chooser = new Chooser(p.game, p.ai);
            p.ai.dangerouslySetController(p.chooser);
        }
        return p;
    }

    private static boolean pay(final Pos p) {
        final SpellAbility sa = p.spell.getFirstSpellAbility();
        sa.setActivatingPlayer(p.ai);
        return ComputerUtilMana.payManaCost(sa.getPayCosts(), p.ai, sa, false);
    }

    private static String tapped(final Pos p) {
        final StringBuilder sb = new StringBuilder();
        for (Card c : p.ai.getCardsIn(ZoneType.Battlefield)) {
            sb.append(c.getName()).append(c.isTapped() ? ":T " : ":U ");
        }
        sb.append("| gy ").append(p.ai.getCardsIn(ZoneType.Graveyard).size());
        return sb.toString();
    }

    private static int optionOf(final RlManaChoice.Ask a, final Card host) {
        for (int i = 0; i < a.options.size(); i++) {
            if (a.options.get(i).cand.getHostCard() == host) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void optionsAreMateriallyDifferentSources() {
        final Pos p = position(true, true, true);
        Assert.assertTrue(pay(p));
        Assert.assertEquals(p.chooser.asks.size(), 1, "one shard (U) paid from a source");
        final RlManaChoice.Ask a = p.chooser.asks.get(0);
        System.err.println("[mana-choice] candidates " + a.candidates + " acceptable " + a.acceptable + " forge "
                + a.forge + " options " + keys(a));
        Assert.assertEquals(a.acceptable, 4, "both Islands, Volcanic Island and Lotus Petal can pay U");
        Assert.assertEquals(a.options.size(), 3, "the two Islands are one option");
        Assert.assertTrue(a.contested());
        final int isl = optionOf(a, p.islands.get(0)) >= 0 ? optionOf(a, p.islands.get(0)) : optionOf(a, p.islands.get(1));
        Assert.assertTrue(isl >= 0);
        Assert.assertEquals(a.options.get(isl).members, 2);
        Assert.assertTrue(a.options.get(isl).plainLand);
        Assert.assertEquals(a.options.get(isl).colours, "U");
        final RlManaChoice.Option vol = a.options.get(optionOf(a, p.volcanic));
        Assert.assertEquals(vol.colours, "UR");
        Assert.assertTrue(vol.plainLand);
        final RlManaChoice.Option pet = a.options.get(optionOf(a, p.petal));
        Assert.assertTrue(pet.sacrifice, "Lotus Petal is sacrificed");
        Assert.assertFalse(pet.plainLand);
        Assert.assertTrue(a.forge >= 0 && a.forgePick != null);
        Assert.assertNotEquals(a.forge, optionOf(a, p.petal), "Forge AI does not sacrifice the Petal for one U");
        // returned null: Forge AI paid as usual, with its own pick
        Assert.assertTrue(a.forgePick.getHostCard().isTapped(), tapped(p));
        // the census of this shard
        final CallCounter cc = new CallCounter();
        RlManaChoice.census(cc, a, p.spell.getFirstSpellAbility(), true, true, 0);
        final JsonObject ins = cc.toJson().getAsJsonObject("instruments");
        Assert.assertEquals(ins.get("pay.contested").getAsInt(), 1);
        Assert.assertEquals(ins.get("pay.options").getAsInt(), 3);
        Assert.assertEquals(ins.get("pay.differs.sac").getAsInt(), 1);
        Assert.assertEquals(ins.get("pay.differs.colour").getAsInt(), 1);
    }

    @Test
    public void twoIslandsAreNotAChoice() {
        final Pos p = position(true, false, false);
        Assert.assertTrue(pay(p));
        final RlManaChoice.Ask a = p.chooser.asks.get(0);
        Assert.assertEquals(a.acceptable, 2);
        Assert.assertEquals(a.options.size(), 1);
        Assert.assertFalse(a.contested());
        Assert.assertEquals(a.names, 1);
    }

    @Test
    public void aChosenSourcePays() {
        final Pos p = position(true, true, true);
        p.chooser.pick = a -> a.options.get(optionOf(a, p.volcanic)).cand;
        Assert.assertTrue(pay(p));
        Assert.assertTrue(p.volcanic.isTapped(), tapped(p));
        Assert.assertFalse(p.islands.get(0).isTapped() || p.islands.get(1).isTapped(), tapped(p));
        Assert.assertTrue(p.ai.getCardsIn(ZoneType.Battlefield).contains(p.petal));
        Assert.assertEquals(p.chooser.refused, 0);
        final Pos q = position(true, true, true);
        q.chooser.pick = a -> a.options.get(optionOf(a, q.petal)).cand;
        Assert.assertTrue(pay(q));
        Assert.assertFalse(q.ai.getCardsIn(ZoneType.Battlefield).contains(q.petal), "the Petal was sacrificed");
        Assert.assertFalse(q.volcanic.isTapped() || q.islands.get(0).isTapped() || q.islands.get(1).isTapped(),
                tapped(q));
    }

    /** Enumerating (and returning null) leaves the payment and the random stream exactly as Forge AI alone. */
    @Test
    public void enumerationDoesNotMoveTheGame() {
        final String[] states = new String[2];
        final long[] next = new long[2];
        for (int k = 0; k < 2; k++) {
            final Pos p = position(true, true, true);
            p.chooser.enumerate = k == 1;
            MyRandom.setThreadRandom(new Random(77));
            try {
                Assert.assertTrue(pay(p));
                states[k] = tapped(p);
                next[k] = MyRandom.getRandom().nextLong();
            } finally {
                MyRandom.setThreadRandom(null);
            }
            Assert.assertEquals(p.chooser.asks.size(), k == 1 ? 1 : 0);
        }
        Assert.assertEquals(states[1], states[0]);
        Assert.assertEquals(next[1], next[0], "the enumeration drew from the live random stream");
    }

    /** A Triome's three abilities pay a generic shard alike: one option (it is a plain land; cycling is from hand). */
    @Test
    public void oneLandsAbilitiesAreOneOption() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 5);
        final Card tri = addCard("Ziatora's Proving Ground", ai);
        final Card ring = addCardToZone("Sol Ring", ai, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final Chooser ch = new Chooser(game, ai);
        ai.dangerouslySetController(ch);
        final SpellAbility sa = ring.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        Assert.assertTrue(ComputerUtilMana.payManaCost(sa.getPayCosts(), ai, sa, false));
        Assert.assertEquals(ch.asks.size(), 1);
        final RlManaChoice.Ask a = ch.asks.get(0);
        Assert.assertTrue(a.acceptable >= 3, "the Triome's three abilities can pay {1}: " + a.acceptable);
        Assert.assertEquals(a.options.size(), 1, keys(a));
        Assert.assertTrue(RlManaChoice.plainLand(tri));
        Assert.assertFalse(a.contested());
    }

    /** The rest-of-the-cost test: a source is a legal payment only if what is left can still be paid without it. */
    @Test
    public void restPayableSeesAStrandedCost() {
        for (int islands = 1; islands <= 2; islands++) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            fillLibrary(ai, 5);
            final List<Card> isl = addCards("Island", islands, ai);
            addCard("Mountain", ai);
            final Card cs = addCardToZone("Counterspell", ai, ZoneType.Hand);
            game.getAction().checkStateEffects(true);
            final SpellAbility sa = cs.getFirstSpellAbility();
            sa.setActivatingPlayer(ai);
            final ManaCostBeingPaid cost = new ManaCostBeingPaid(sa.getPayCosts().getTotalMana());
            final SpellAbility ma = isl.get(0).getManaAbilities().get(0);
            MyRandom.setThreadRandom(new Random(5));
            try {
                final boolean ok = RlManaChoice.restPayable(game, ai, cost, sa, ManaCostShard.BLUE, ma, false);
                Assert.assertEquals(ok, islands == 2, islands + " Islands: the second U needs another Island");
                Assert.assertEquals(MyRandom.getRandom().nextLong(), new Random(5).nextLong(), "isolated");
            } finally {
                MyRandom.setThreadRandom(null);
            }
            Assert.assertFalse(isl.get(0).isTapped(), "the source's tapped state is put back");
        }
    }

    /** The MANA_SOURCE menu: AUTO first, then one candidate per option; AUTO and Forge AI's own source flagged. */
    @Test
    public void manaSourceMenu() {
        final Pos p = position(true, true, true);
        Assert.assertTrue(pay(p));
        final RlManaChoice.Ask a = p.chooser.asks.get(0);
        final SpellAbility sa = p.spell.getFirstSpellAbility();
        final RlCandidates.Menu m = RlCandidates.build(p.game, p.ai, "chooseShardSource", "manaSource",
                new JsonObject(), new Object[] {a, sa, p.chooser.shards.get(0), 1});
        Assert.assertNotNull(m);
        Assert.assertNull(m.unposable);
        Assert.assertEquals(m.family, RlSchemaV3.F_MANA_SOURCE);
        Assert.assertEquals(m.mode, RlSchema.M_SINGLE);
        Assert.assertFalse(m.trivial);
        Assert.assertEquals(m.C(), 1 + a.options.size());
        Assert.assertEquals(m.cands.get(0).kind, RlSchema.K_NONE);
        Assert.assertEquals(m.cands.get(0).flags, RlSchemaV3.FLAG_FORGE_DEFAULT);
        Assert.assertEquals(m.cands.get(0).num, 1, "AUTO's cand_num: the mana still unpaid");
        for (int i = 0; i < a.options.size(); i++) {
            final RlCandidates.Cand c = m.cands.get(i + 1);
            Assert.assertEquals(c.kind, RlSchema.K_ACTIVATE);
            Assert.assertEquals(c.host, a.options.get(i).cand.getHostCard());
            Assert.assertEquals(c.num, RlSchemaV3.SH_U);
            Assert.assertEquals(c.tgt1, p.spell);
            Assert.assertEquals(c.flags, i == a.forge ? RlSchemaV3.FLAG_FORGE_DEFAULT : 0);
            Assert.assertEquals(m.fragment(i + 1).getAsJsonObject().get("choice").getAsInt(), i + 1);
        }
        Assert.assertEquals(m.fragment(0).getAsJsonObject().get("choice").getAsInt(), 0);
        final JsonObject echo = new JsonObject();
        echo.addProperty("choice", a.forge + 1);
        Assert.assertEquals(m.fromEcho(echo), new short[] {(short) (a.forge + 1)});
        Assert.assertEquals(RlSchema.familyOf("manaSource", "chooseShardSource"), RlSchemaV3.F_MANA_SOURCE);
        Assert.assertTrue(RlSchema.isLearned(RlSchemaV3.F_MANA_SOURCE));
        Assert.assertEquals(RlSchema.familyName(RlSchemaV3.F_MANA_SOURCE), "MANA_SOURCE");
    }

    /** obs-v3.json is vendored byte for byte: its sha, family 25, the forge_default flag bit and the shard codes. */
    @Test
    public void schemaV3IsTheVendoredJson() throws Exception {
        final byte[] b = java.nio.file.Files.readAllBytes(RlWireSchemaTest.res().resolve("obs-v3.json"));
        Assert.assertEquals(CardIndex.sha256(b), RlSchemaV3.SCHEMA_SHA);
        final JsonObject s = com.google.gson.JsonParser.parseString(new String(b,
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        Assert.assertEquals(s.get("schema").getAsString(), RlSchemaV3.SCHEMA);
        Assert.assertEquals(s.get("wire").getAsString(), RlSchemaV3.PROTO);
        final com.google.gson.JsonArray fam = s.getAsJsonArray("families");
        Assert.assertEquals(fam.size(), RlSchema.N_FAMILIES + 1);
        final JsonObject last = fam.get(fam.size() - 1).getAsJsonObject();
        Assert.assertEquals(last.get("id").getAsInt(), RlSchemaV3.F_MANA_SOURCE);
        Assert.assertEquals(last.get("name").getAsString(), RlSchemaV3.MANA_SOURCE);
        Assert.assertEquals(last.get("mode").getAsString(), "SINGLE");
        Assert.assertEquals(1 << indexOf(s.getAsJsonArray("cand_flags"), "forge_default"),
                RlSchemaV3.FLAG_FORGE_DEFAULT);
        final com.google.gson.JsonArray sh = s.getAsJsonArray("mana_shards");
        Assert.assertEquals(indexOf(sh, "U"), RlSchemaV3.SH_U);
        Assert.assertEquals(indexOf(sh, "generic"), RlSchemaV3.SH_GENERIC);
        Assert.assertEquals(indexOf(sh, "other"), RlSchemaV3.SH_OTHER);
        Assert.assertEquals(sh.size(), RlSchemaV3.SH_OTHER + 1);
        // v3 keeps every v2 list: only the schema name, the families and the two new lists differ
        final JsonObject v2 = com.google.gson.JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(
                RlWireSchemaTest.res().resolve("obs-v2.json")), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (String k : v2.keySet()) {
            if (!k.equals("schema") && !k.equals("families")) {
                Assert.assertEquals(s.get(k), v2.get(k), k);
            }
        }
        Assert.assertEquals(RlSchemaV3.versionOfSha(RlSchemaV3.SCHEMA_SHA), 3);
        Assert.assertEquals(RlSchemaV3.versionOfSha(RlSchemaV2.schemaSha()), 2);
        Assert.assertEquals(RlSchemaV3.versionOfSha(RlSchema.schemaSha()), 1);
        Assert.assertEquals(RlSchemaV3.layoutOf(3), 2);
    }

    private static int indexOf(final com.google.gson.JsonArray a, final String v) {
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getAsString().equals(v)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void shardCodes() {
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.BLUE), RlSchemaV3.SH_U);
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.GENERIC), RlSchemaV3.SH_GENERIC);
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.COLORLESS), RlSchemaV3.SH_C);
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.UR), RlSchemaV3.SH_HYBRID);
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.UP), RlSchemaV3.SH_PHYREXIAN);
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.U2), RlSchemaV3.SH_TWO_GENERIC);
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.S), RlSchemaV3.SH_SNOW);
        Assert.assertEquals(RlSchemaV3.shardCode(ManaCostShard.X), RlSchemaV3.SH_X);
    }

    private static String keys(final RlManaChoice.Ask a) {
        final StringBuilder sb = new StringBuilder();
        for (RlManaChoice.Option o : a.options) {
            sb.append(o.key).append(" x").append(o.members).append(o.sacrifice ? " sac" : "").append("; ");
        }
        return sb.toString();
    }
}
