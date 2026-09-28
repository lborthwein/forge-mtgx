package forge.bench;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.GuiDesktop;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.ai.ability.ChangeZoneAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * "Forge AI's pick" baseline for the tutor ranker (lane tutor-ranking-0928): for each 17Lands search event (the
 * extractor's JSONL: source, deck, library estimate, the previous end-of-turn board and hand), build the position in a
 * bare Forge game, find the source card's library-search ability and ask Forge AI's own choice
 * ({@link ChangeZoneAi#chooseCardToHiddenOriginChangeZone}) over the library cards the ability can find. Writes one JSON
 * line per event: the Forge pick, the distinct names Forge offered, and why an event was skipped.
 *
 * <p>{@code java -cp <jar> forge.bench.TutorPickProbe <events.jsonl> <out.jsonl>}, cwd = {@code <forge>/forge-gui}.
 */
public final class TutorPickProbe {
    private TutorPickProbe() {
    }

    /** The 17Lands ability classes have no card name in the event: a card with that ability. */
    static final Map<String, String> CLASS_CARD = new TreeMap<>();
    static {
        CLASS_CARD.put("wishclaw", "Wishclaw Talisman");
        CLASS_CARD.put("stoneforge", "Stoneforge Mystic");
        CLASS_CARD.put("trinket", "Trinket Mage");
        CLASS_CARD.put("survival", "Survival of the Fittest");
        CLASS_CARD.put("magda", "Magda, Brazen Outlaw");
        CLASS_CARD.put("map", "Expedition Map");
        CLASS_CARD.put("saga", "Urza's Saga");
        CLASS_CARD.put("golos", "Golos, Tireless Pilgrim");
    }

    static String fetchland(String filter) {
        // filter "land:A,B" from the fetchland's ability text; "basic" = Prismatic Vista
        if ("basic".equals(filter)) {
            return "Prismatic Vista";
        }
        final String t = filter.startsWith("land:") ? filter.substring(5) : filter;
        final String[] ab = t.split(",");
        final java.util.Set<String> s = new java.util.TreeSet<>(java.util.Arrays.asList(ab));
        final String k = String.join("+", s);
        switch (k) {
            case "Island+Swamp": return "Polluted Delta";
            case "Forest+Mountain": return "Wooded Foothills";
            case "Island+Plains": return "Flooded Strand";
            case "Mountain+Swamp": return "Bloodstained Mire";
            case "Forest+Plains": return "Windswept Heath";
            case "Plains+Swamp": return "Marsh Flats";
            case "Island+Mountain": return "Scalding Tarn";
            case "Forest+Swamp": return "Verdant Catacombs";
            case "Mountain+Plains": return "Arid Mesa";
            case "Forest+Island": return "Misty Rainforest";
            default: return null;
        }
    }

    public static void main(String[] args) throws Exception {
        final PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.setOut(err);
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        final List<String> lines = Files.readAllLines(Path.of(args[0]));
        final StringBuilder out = new StringBuilder();
        int n = 0, ok = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            final JsonObject ev = JsonParser.parseString(line).getAsJsonObject();
            final JsonObject row = new JsonObject();
            row.addProperty("n", n);
            if (ev.has("eid")) {
                row.add("eid", ev.get("eid"));
            }
            try {
                probe(ev, row, n);
                ok += row.has("forge") ? 1 : 0;
            } catch (RuntimeException e) {
                row.addProperty("skip", "error: " + e);
            }
            out.append(row).append('\n');
            n++;
        }
        Files.writeString(Path.of(args[1]), out.toString(), StandardCharsets.UTF_8);
        err.println("[tutor-pick-probe] " + n + " events, " + ok + " with a Forge pick");
        System.exit(0);
    }

    private static IPaperCard paper(String name) {
        IPaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        if (pc == null) {
            StaticData.instance().attemptToLoadCard(name);
            pc = FModel.getMagicDb().getCommonCards().getCard(name);
        }
        return pc;
    }

    private static Card add(Game g, Player p, String name, ZoneType z) {
        final IPaperCard pc = paper(name);
        if (pc == null) {
            return null;
        }
        final Card c = Card.fromPaperCard(pc, p);
        c.setGameTimestamp(g.getNextTimestamp());
        p.getZone(z).add(c);
        return c;
    }

    static void probe(JsonObject ev, JsonObject row, int seed) {
        final String cls = ev.get("cls").getAsString();
        final String src = ev.get("src").getAsString();
        String source;
        if ("fetch".equals(cls)) {
            final JsonElement f = ev.get("filter");
            source = fetchland(f.isJsonArray() ? f.getAsJsonArray().get(0).getAsString() : f.getAsString());
        } else if (src.startsWith("ab")) {
            source = CLASS_CARD.get(cls);
        } else {
            source = src;
        }
        row.addProperty("source", source);
        if (source == null || paper(source) == null) {
            row.addProperty("skip", "no source card");
            return;
        }
        // The deck (for Forge's key cards) and a bare two-player game at the seat's main phase.
        final Deck d = new Deck();
        for (Map.Entry<String, JsonElement> e : ev.getAsJsonObject("deck").entrySet()) {
            final IPaperCard pc = paper(e.getKey());
            if (pc instanceof PaperCard p) {
                d.getOrCreate(DeckSection.Main).add(p, e.getValue().getAsInt());
            }
        }
        final List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p0", null)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p1", null)));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Match match = new Match(rules, players, "tutor-pick-probe");
        final Game g = new Game(players, rules, match);
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        g.setAge(GameStage.Play);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        g.getPhaseHandler().onStackResolved();
        final JsonObject before = ev.has("before") && ev.get("before").isJsonObject() ? ev.getAsJsonObject("before") : new JsonObject();
        int missing = 0;
        for (String z : new String[] {"lands", "creatures", "noncreatures"}) {
            if (before.has(z)) {
                for (JsonElement c : before.getAsJsonArray(z)) {
                    missing += add(g, me, c.getAsString(), ZoneType.Battlefield) == null ? 1 : 0;
                }
            }
        }
        if (before.has("hand")) {
            for (JsonElement c : before.getAsJsonArray("hand")) {
                missing += add(g, me, c.getAsString(), ZoneType.Hand) == null ? 1 : 0;
            }
        }
        for (String z : new String[] {"opp_creatures", "opp_noncreatures"}) {
            if (before.has(z)) {
                for (JsonElement c : before.getAsJsonArray(z)) {
                    add(g, opp, c.getAsString(), ZoneType.Battlefield);
                }
            }
        }
        final int oppLands = before.has("opp_lands") ? before.get("opp_lands").getAsInt() : 0;
        for (int i = 0; i < oppLands; i++) {
            add(g, opp, "Island", ZoneType.Battlefield);
        }
        for (int i = 0; i < 20; i++) {
            add(g, opp, "Island", ZoneType.Library);
        }
        try {
            me.setLife(Integer.parseInt(before.get("life").getAsString().replace(".0", "")), null);
            opp.setLife(Integer.parseInt(before.get("opp_life").getAsString().replace(".0", "")), null);
        } catch (RuntimeException ignored) {
            // keep 20
        }
        for (Map.Entry<String, JsonElement> e : ev.getAsJsonObject("library_est").entrySet()) {
            for (int i = 0; i < e.getValue().getAsInt(); i++) {
                missing += add(g, me, e.getKey(), ZoneType.Library) == null ? 1 : 0;
            }
        }
        for (JsonElement t : ev.getAsJsonArray("target")) {
            // The found card must be in the library (the estimate can miss it).
            boolean in = false;
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                in |= c.getName().equals(t.getAsString());
            }
            if (!in) {
                add(g, me, t.getAsString(), ZoneType.Library);
                row.addProperty("targetAdded", true);
            }
        }
        row.addProperty("unknownCards", missing);
        // The source card: on the battlefield for abilities / triggers, in hand for spells.
        final boolean ability = src.startsWith("ab");
        final Card host = add(g, me, source, ability ? ZoneType.Battlefield : ZoneType.Hand);
        g.getAction().checkStateEffects(true);
        SpellAbility search = null;
        for (SpellAbility sa : host.getAllSpellAbilities()) {
            if (sa.getApi() == ApiType.ChangeZone && "Library".equals(sa.getParam("Origin"))) {
                search = sa;
                break;
            }
        }
        if (search == null) {
            for (Trigger t : host.getTriggers()) {
                SpellAbility sa = t.getOverridingAbility();
                if (sa == null && t.hasParam("Execute")) {
                    sa = AbilityFactory.getAbility(host, t.getParam("Execute"));
                }
                if (sa != null && sa.getApi() == ApiType.ChangeZone && "Library".equals(sa.getParam("Origin"))) {
                    search = sa;
                    break;
                }
            }
        }
        if (search == null) {
            row.addProperty("skip", "no library search on " + source);
            return;
        }
        search.setActivatingPlayer(me);
        if (search.hasParam("ChangeType") && search.getParam("ChangeType").contains("X")) {
            // Green Sun's Zenith: X = the seat's lands minus the G.
            int lands = 0;
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                lands += c.isLand() ? 1 : 0;
            }
            search.setXManaCostPaid(Math.max(0, lands - 1));
        }
        CardCollection fetch = new CardCollection(me.getCardsIn(ZoneType.Library));
        if (search.hasParam("ChangeType")) {
            fetch = (CardCollection) AbilityUtils.filterListByType(fetch, search.getParam("ChangeType"), search);
        }
        final java.util.Set<String> names = new java.util.TreeSet<>();
        for (Card c : fetch) {
            names.add(c.getName());
        }
        final JsonArray nj = new JsonArray();
        names.forEach(nj::add);
        row.add("offered", nj);
        if (fetch.isEmpty()) {
            row.addProperty("skip", "nothing to find");
            return;
        }
        final ZoneType dest = ZoneType.smartValueOf(search.getParam("Destination"));
        MyRandom.setRandom(new Random(719_250_000L + seed));
        final Card pick = ChangeZoneAi.chooseCardToHiddenOriginChangeZone(dest, Lists.newArrayList(ZoneType.Library), search,
                fetch, me, me);
        row.addProperty("forge", pick == null ? null : pick.getName());
        row.addProperty("dest", String.valueOf(dest));
    }
}
