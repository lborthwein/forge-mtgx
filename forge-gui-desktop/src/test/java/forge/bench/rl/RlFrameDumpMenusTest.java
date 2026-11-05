package forge.bench.rl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Lane dump-menus-1105: the menu a forced ask poses ({@link RlFrameDump#ask}, {@link RlFrameDump#optionJson}): a tutor's
 * whole library pick, a fetch's optional land pick, and a trigger's target. Every option is listed with its tape
 * reference and card-index id, the record's answer is the chosen option, and each option's answer {@code a} is the one
 * the tape would hold had it been chosen (the chosen option's equals the record's).
 */
public class RlFrameDumpMenusTest extends AITest {

    private static CardIndex index(final String... names) {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : names) {
            sb.append(n).append('\t').append(i++).append('\n');
        }
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<JsonObject> options(final Game g, final Player p, final RlFrameDump.MenuAsk k,
            final CardIndex ix, final JsonElement recorded) {
        final List<JsonObject> out = new ArrayList<>();
        for (int i = 0; i < k.opts.size(); i++) {
            out.add(RlFrameDump.optionJson(g, p, k, i, null, ix, recorded));
        }
        return out;
    }

    @Test
    public void aTutorsMenuIsTheWholeLibraryWithTheChosenCardMarked() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final String[] lib = {"Island", "Swamp", "Mountain", "Forest", "Plains", "Lightning Bolt", "Counterspell",
            "Dark Ritual", "Giant Growth", "Llanowar Elves", "Grizzly Bears", "Shock"};
        final CardIndex ix = index(Arrays.copyOf(lib, lib.length));
        final Card tutor = addCardToZone("Demonic Tutor", p, ZoneType.Hand);
        final CardCollection fetch = new CardCollection();
        for (String n : lib) {
            fetch.add(addCardToZone(n, p, ZoneType.Library));
        }
        final SpellAbility sa = tutor.getFirstSpellAbility();
        final Card pick = fetch.get(6);
        final RlFrameDump.MenuAsk k = RlFrameDump.ask(game, p, "chooseSingleCardForZoneChange",
                new Object[] {ZoneType.Hand, List.of(ZoneType.Library), sa, fetch, false, p}, pick);
        Assert.assertNotNull(k);
        Assert.assertEquals(k.family, "ZONE_CHANGE");
        Assert.assertEquals(k.options(), lib.length, "every library card is an option");
        Assert.assertTrue(k.options() >= 10);
        Assert.assertEquals(k.chosen, List.of(6));
        final JsonElement recorded = new JsonPrimitive("c" + pick.getId()); // AiTapeController.one(card)
        final List<JsonObject> os = options(game, p, k, ix, recorded);
        for (int i = 0; i < os.size(); i++) {
            final JsonObject o = os.get(i);
            Assert.assertEquals(o.get("kind").getAsString(), "card");
            Assert.assertEquals(o.get("ref").getAsString(), "c" + fetch.get(i).getId());
            Assert.assertEquals(o.get("id").getAsInt(), fetch.get(i).getId());
            Assert.assertEquals(o.get("card").getAsInt(), ix.lookup(lib[i]), "the card-index id, not a name");
            Assert.assertEquals(o.get("zone").getAsString(), "Library");
            Assert.assertEquals(o.get("owner").getAsInt(), 0);
            Assert.assertEquals(o.get("a"), new JsonPrimitive("c" + fetch.get(i).getId()));
        }
        Assert.assertEquals(os.get(6).get("a"), recorded, "the chosen option's answer is the record's");
    }

    @Test
    public void aFetchsOptionalMenuHasANoneOption() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final CardIndex ix = index("Island", "Swamp", "Polluted Delta");
        final Card fetchland = addCardToZone("Polluted Delta", p, ZoneType.Battlefield);
        final CardCollection lands = new CardCollection();
        lands.add(addCardToZone("Island", p, ZoneType.Library));
        lands.add(addCardToZone("Swamp", p, ZoneType.Library));
        lands.add(addCardToZone("Island", p, ZoneType.Library));
        addCardToZone("Lightning Bolt", p, ZoneType.Library); // not in the fetch list: not an option
        final SpellAbility sa = fetchland.getSpellAbilities().iterator().next();
        final Object[] args = {ZoneType.Battlefield, List.of(ZoneType.Library), sa, lands, true, p};
        final RlFrameDump.MenuAsk k = RlFrameDump.ask(game, p, "chooseSingleCardForZoneChange", args, lands.get(1));
        Assert.assertEquals(k.options(), 3);
        Assert.assertEquals(k.opts.size(), 4, "the three lands and none");
        Assert.assertEquals(k.min, 0);
        Assert.assertEquals(k.chosen, List.of(1));
        final List<JsonObject> os = options(game, p, k, ix, new JsonPrimitive("c" + lands.get(1).getId()));
        Assert.assertEquals(os.get(3).get("kind").getAsString(), "none");
        Assert.assertTrue(os.get(3).get("a").isJsonNull(), "fail to find: the tape's null answer");
        Assert.assertEquals(os.get(0).get("card").getAsInt(), os.get(2).get("card").getAsInt(), "same card, same id");
        Assert.assertNotEquals(os.get(0).get("ref"), os.get(2).get("ref"), "but two options");
        // the record declined to find: the none option is chosen
        final RlFrameDump.MenuAsk none = RlFrameDump.ask(game, p, "chooseSingleCardForZoneChange", args, null);
        Assert.assertEquals(none.chosen, List.of(3));
    }

    @Test
    public void aTriggersTargetMenuIsEveryLegalTargetOfItsLink() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Player opp = game.getPlayers().get(1);
        final CardIndex ix = index("Flametongue Kavu", "Grizzly Bears", "Llanowar Elves", "Island");
        final Card kavu = addCardToZone("Flametongue Kavu", p, ZoneType.Battlefield);
        final Card own = addCardToZone("Llanowar Elves", p, ZoneType.Battlefield);
        final Card b1 = addCardToZone("Grizzly Bears", opp, ZoneType.Battlefield);
        final Card b2 = addCardToZone("Grizzly Bears", opp, ZoneType.Battlefield);
        addCardToZone("Island", opp, ZoneType.Battlefield); // not a creature: not a target
        Trigger t = null;
        for (Trigger x : kavu.getTriggers()) {
            t = x;
        }
        final SpellAbility sa = t.ensureAbility();
        sa.setActivatingPlayer(p);
        Assert.assertTrue(sa.usesTargeting());
        sa.getTargets().add(b2);
        final RlFrameDump.MenuAsk k = RlFrameDump.ask(game, p, "cfg:s", new Object[] {sa}, null);
        Assert.assertNotNull(k);
        Assert.assertEquals(k.family, "TARGETS");
        Assert.assertEquals(k.options(), 4, "every creature on the battlefield");
        Assert.assertEquals(k.slots.size(), 1);
        final List<Card> opts = new ArrayList<>();
        for (RlFrameDump.Opt x : k.opts) {
            opts.add((Card) x.o);
            Assert.assertEquals(x.slot, 0);
        }
        Assert.assertTrue(opts.containsAll(List.of(kavu, own, b1, b2)));
        Assert.assertEquals(k.chosen, List.of(opts.indexOf(b2)));
        // the cfg the tape holds (AiTapeCodec.config): one link, targets b2
        final JsonObject recorded = JsonParser.parseString("{\"h\":" + kavu.getId() + ",\"n\":1,\"t\":[[\"c" + b2.getId()
                + "\"]]}").getAsJsonObject();
        final List<JsonObject> os = options(game, p, k, ix, recorded);
        for (int i = 0; i < os.size(); i++) {
            final JsonArray ti = os.get(i).getAsJsonObject("a").getAsJsonArray("t").get(0).getAsJsonArray();
            Assert.assertEquals(ti.size(), 1);
            Assert.assertEquals(ti.get(0).getAsString(), os.get(i).get("ref").getAsString(), "option " + i);
            Assert.assertEquals(os.get(i).get("ctrl").getAsInt(), opts.get(i).getController() == p ? 0 : 1);
        }
        Assert.assertEquals(os.get(opts.indexOf(b2)).get("a"), recorded, "the chosen option's answer is the record's");
        // an SA configured without targets poses no menu
        final SpellAbility untargeted = addCardToZone("Island", p, ZoneType.Battlefield).getManaAbilities().iterator().next();
        Assert.assertNull(RlFrameDump.ask(game, p, "cfg:n", new Object[] {untargeted}, null));
    }

    @Test
    public void anOptionalTriggerIsAYesNoMenu() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Card kavu = addCardToZone("Flametongue Kavu", p, ZoneType.Battlefield);
        final SpellAbility sa = kavu.getTriggers().iterator().next().ensureAbility();
        final RlFrameDump.MenuAsk k = RlFrameDump.ask(game, p, "confirmTrigger", new Object[] {sa}, Boolean.FALSE);
        Assert.assertEquals(k.family, "OPTIONAL_TRIGGER");
        Assert.assertEquals(k.options(), 2);
        Assert.assertEquals(k.chosen, List.of(1));
        final List<JsonObject> os = options(game, p, k, null, new JsonPrimitive(false));
        Assert.assertEquals(os.get(0).get("a"), new JsonPrimitive(true));
        Assert.assertEquals(os.get(1).get("a"), new JsonPrimitive(false));
        Assert.assertNull(RlFrameDump.ask(game, p, "chooseColor", new Object[] {}, null), "not a recorded menu");
    }
}
