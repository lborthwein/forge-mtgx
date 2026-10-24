package forge.interactive;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Lane gamestate-setup-1024: the owner's two puzzles that asked "Do you want to pay 2 life?" before the game began
 * (reports 2026-10-23T23-41-50 for forge-51da49e6e728-t8; 2026-10-24T00-16-53 and 00-21-25 for
 * forge-269441dd653b-t6, where the answer took the saved 9 life to 7). Each is installed as the play host installs
 * it, through {@link InteractivePuzzle#hook}, with the browser seat a real {@link PlayerControllerHuman} on an
 * {@link InteractiveGuiGame} wire. A question asked during the install is a {@code request} on that wire.
 *
 * <p>Acceptance (mtgx-52's item 1): no request while the position is installed, and each seat's life, battlefield
 * and tapped state equal the .pzl. No zone_changed event the seat is sent says a card came from a hand.</p>
 */
public class InteractivePuzzleSetupTest extends AITest {

    /** tools/forge/puzzles/forge-51da49e6e728-t8.pzl, its [state] block verbatim. */
    static final String T8 = String.join("\n",
            "turn=8",
            "activeplayer=human",
            "activephase=MAIN1",
            "ailife=20",
            "ailandsplayed=0",
            "ailandsplayedlastturn=0",
            "ainumringtemptedyou=0",
            "aispeed=0",
            "aihand=Trumpeting Carnosaur|Set:LCI|Art:1;Restoration Angel|Set:INR|Art:1;Zealous Conscripts|Set:INR"
                    + "|Art:1;Gideon, Ally of Zendikar|Set:PIO|Art:1;Pia and Kiran Nalaar|Set:DRC|Art:1;Giver of Runes|Set:"
                    + "MB2|Art:1",
            "ailibrary=Figure of Destiny|Set:2X2|Art:1;Cathar Commando|Set:INR|Art:1;Mountain|Set:TRK|Art:1;Wrath"
                    + " of God|Set:CMM|Art:1;Winds of Abandon|Set:SOA|Art:1;Portable Hole|Set:HBG|Art:1;Mountain|Set:TRK|Ar"
                    + "t:1;Mountain|Set:TRK|Art:1;Oliphaunt|Set:HOC|Art:1;Fable of the Mirror-Breaker|Set:SCH|Art:1;Plains|"
                    + "Set:TRK|Art:1;Hero of Bladehold|Set:TDC|Art:1;The Wandering Emperor|Set:NEO|Art:1;Thalia, Guardian o"
                    + "f Thraben|Set:MUL|Art:1;Plains|Set:TRK|Art:1;Plains|Set:TRK|Art:1;Plains|Set:TRK|Art:1;Mountain|Set:"
                    + "TRK|Art:1;Krark, the Thumbless|Set:CMR|Art:1;Mountain|Set:TRK|Art:1;Comet, Stellar Pup|Set:MB2|Art:1"
                    + ";Plains|Set:TRK|Art:1;Mountain|Set:TRK|Art:1;Containment Priest|Set:MB2|Art:1;Plains|Set:TRK|Art:1;P"
                    + "lains|Set:TRK|Art:1;Embereth Shieldbreaker|Set:CLB|Art:1;Koth of the Hammer|Set:SLD|Art:1",
            "aigraveyard=Timeless Dragon|Set:DRC|Art:1",
            "aibattlefield=Mountain|Set:TRK|Art:1|Tapped;Mountain|Set:TRK|Art:1|Tapped;Plains|Set:TRK|Art:1|Tappe"
                    + "d;Imperial Recruiter|Set:SLZ|Art:1|Tapped;Blade Splicer|Set:BLC|Art:1|SummonSick;T:c_3_3_a_phyrexian"
                    + "_golem|Set:BLC|SummonSick",
            "aiexile=",
            "aicommand=",
            "aisideboard=",
            "humanlife=18",
            "humanlandsplayed=0",
            "humanlandsplayedlastturn=0",
            "humannumringtemptedyou=0",
            "humanspeed=0",
            "humanhand=Stern Scolding|Set:HOC|Art:1;Doomsday|Set:SLZ|Art:1;Counterspell|Set:PF26|Art:1;Baleful St"
                    + "rix|Set:SLZ|Art:1;Urborg, Tomb of Yawgmoth|Set:SLZ|Art:1;Gush|Set:MB2|Art:1",
            "humanlibrary=Library of Alexandria|Set:SPG|Art:1;Scalding Tarn|Set:SPG|Art:1;Timetwister|Set:30A|Art"
                    + ":1;Remand|Set:J25|Art:1;Island|Set:TRK|Art:1;Daze|Set:SOA|Art:1;Collective Brutality|Set:INR|Art:1;S"
                    + "wamp|Set:TRK|Art:1;Cabal Ritual|Set:MB2|Art:1;Mind Stone|Set:MBC|Art:1;Memory Jar|Set:AA3|Art:1;Mist"
                    + "y Rainforest|Set:SPG|Art:1;Bolas's Citadel|Set:MB2|Art:1;Darkslick Shores|Set:OTC|Art:1;Island|Set:T"
                    + "RK|Art:1;Lotus Petal|Set:MB2|Art:1;Waterlogged Grove|Set:ACR|Art:1;Chart a Course|Set:FDN|Art:1;Isla"
                    + "nd|Set:TRK|Art:1;Echo of Eons|Set:MB2|Art:1;Thassa's Oracle|Set:MB2|Art:1;Swamp|Set:TRK|Art:1;Jace, "
                    + "Wielder of Mysteries|Set:PLST|Art:1;Swamp|Set:TRK|Art:1;Wishclaw Talisman|Set:FDN|Art:1;Beseech the "
                    + "Mirror|Set:WOE|Art:1;Troll of Khazad-d\u00fbm|Set:HOC|Art:1;Jace, the Mind Sculptor|Set:BLC|Art:1",
            "humangraveyard=Verdant Catacombs|Set:SPG|Art:1;Mystical Tutor|Set:TLE|Art:1",
            "humanbattlefield=Raffine's Tower|Set:SNC|Art:1;Xander's Lounge|Set:SNC|Art:1;Chromatic Star|Set:BRR|"
                    + "Art:1;Overgrown Tomb|Set:TRK|Art:1",
            "humanexile=",
            "humancommand=",
            "humansideboard=");

    /** tools/forge/puzzles/forge-269441dd653b-t6.pzl, its [state] block verbatim. */
    static final String T6 = String.join("\n",
            "turn=6",
            "activeplayer=human",
            "activephase=MAIN1",
            "ailife=20",
            "ailandsplayed=0",
            "ailandsplayedlastturn=1",
            "ainumringtemptedyou=0",
            "aispeed=0",
            "aihand=Wrath of God|Set:CMM|Art:1;Hero of Bladehold|Set:TDC|Art:1;Pia and Kiran Nalaar|Set:DRC|Art:1"
                    + ";Portable Hole|Set:HBG|Art:1;Restoration Angel|Set:INR|Art:1;Winds of Abandon|Set:SOA|Art:1",
            "ailibrary=Plains|Set:TRK|Art:1;Containment Priest|Set:MB2|Art:1;Plains|Set:TRK|Art:1;Oliphaunt|Set:H"
                    + "OC|Art:1;Thalia, Guardian of Thraben|Set:MUL|Art:1;Underworld Breach|Set:PZA|Art:1;Mountain|Set:TRK|"
                    + "Art:1;Lightning Greaves|Set:MSC|Art:1;Sundering Titan|Set:BRR|Art:1;Mountain|Set:TRK|Art:1;Giver of "
                    + "Runes|Set:MB2|Art:1;Timeless Dragon|Set:DRC|Art:1;Blade Splicer|Set:BLC|Art:1;Cathar Commando|Set:IN"
                    + "R|Art:1;Mountain|Set:TRK|Art:1;Comet, Stellar Pup|Set:MB2|Art:1;Plains|Set:TRK|Art:1;Gideon, Ally of"
                    + " Zendikar|Set:PIO|Art:1;Mountain|Set:TRK|Art:1;Plains|Set:TRK|Art:1;Fable of the Mirror-Breaker|Set:"
                    + "SCH|Art:1;Figure of Destiny|Set:2X2|Art:1;Trumpeting Carnosaur|Set:LCI|Art:1;Plains|Set:TRK|Art:1;Th"
                    + "e Wandering Emperor|Set:NEO|Art:1;Imperial Recruiter|Set:SLZ|Art:1;Mountain|Set:TRK|Art:1;Plains|Set"
                    + ":TRK|Art:1;Mountain|Set:TRK|Art:1;Plains|Set:TRK|Art:1;Zealous Conscripts|Set:INR|Art:1",
            "aigraveyard=",
            "aibattlefield=Plains|Set:TRK|Art:1;Mountain|Set:TRK|Art:1;Mountain|Set:TRK|Art:1",
            "aiexile=",
            "aicommand=",
            "aisideboard=",
            "humanlife=9",
            "humanlandsplayed=1",
            "humanlandsplayedlastturn=0",
            "humannumringtemptedyou=0",
            "humanspeed=0",
            "humanhand=Island|Set:TRK|Art:1;Swamp|Set:TRK|Art:1;Swamp|Set:TRK|Art:1;Echo of Eons|Set:MB2|Art:1",
            "humanlibrary=Gush|Set:MB2|Art:1;Daze|Set:SOA|Art:1;Thassa's Oracle|Set:MB2|Art:1;Jace, Wielder of My"
                    + "steries|Set:PLST|Art:1;Counterspell|Set:PF26|Art:1",
            "humangraveyard=Doomsday|Set:SLZ|Art:1|Id:43",
            "humanbattlefield=Xander's Lounge|Set:SNC|Art:1|Tapped;Overgrown Tomb|Set:TRK|Art:1|Tapped;Misty Rain"
                    + "forest|Set:SPG|Art:1",
            "humanexile=Verdant Catacombs|Set:SPG|Art:1|ExiledWith:43;Mystical Tutor|Set:TLE|Art:1|ExiledWith:43;"
                    + "Cabal Ritual|Set:MB2|Art:1|ExiledWith:43;Stern Scolding|Set:HOC|Art:1|ExiledWith:43;Baleful Strix|Se"
                    + "t:SLZ|Art:1|ExiledWith:43;Darkslick Shores|Set:OTC|Art:1|ExiledWith:43;Mind Stone|Set:MBC|Art:1|Exil"
                    + "edWith:43;Urborg, Tomb of Yawgmoth|Set:SLZ|Art:1|ExiledWith:43;Beseech the Mirror|Set:WOE|Art:1|Exil"
                    + "edWith:43;Wishclaw Talisman|Set:FDN|Art:1|ExiledWith:43;Memory Jar|Set:AA3|Art:1|ExiledWith:43;Troll"
                    + " of Khazad-d\u00fbm|Set:HOC|Art:1|ExiledWith:43;Swamp|Set:TRK|Art:1|ExiledWith:43;Raffine's Tower|Se"
                    + "t:SNC|Art:1|ExiledWith:43;Scalding Tarn|Set:SPG|Art:1|ExiledWith:43;Remand|Set:J25|Art:1|ExiledWith:"
                    + "43;Collective Brutality|Set:INR|Art:1|ExiledWith:43;Chromatic Star|Set:BRR|Art:1|ExiledWith:43;Jace,"
                    + " the Mind Sculptor|Set:BLC|Art:1|ExiledWith:43;Island|Set:TRK|Art:1|ExiledWith:43;Library of Alexand"
                    + "ria|Set:SPG|Art:1|ExiledWith:43;Island|Set:TRK|Art:1|ExiledWith:43;Bolas's Citadel|Set:MB2|Art:1|Exi"
                    + "ledWith:43;Waterlogged Grove|Set:ACR|Art:1|ExiledWith:43;Timetwister|Set:30A|Art:1|ExiledWith:43;Lot"
                    + "us Petal|Set:MB2|Art:1|ExiledWith:43;Chart a Course|Set:FDN|Art:1|ExiledWith:43",
            "humancommand=",
            "humansideboard=");

    private static final String SESSION = "puzzle-setup-test";

    /** One puzzle installed on a game whose seat 0 is the browser seat; what the wire heard while it ran. */
    static final class Install implements AutoCloseable {
        final Game game;
        final Player seat;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final List<JsonObject> requests = new ArrayList<>();
        final List<GameEventCardChangeZone> moves = new ArrayList<>();
        Throwable failure;

        Install(final Game game, final String state, final String goal, final int turns) throws Exception {
            this.game = game;
            seat = game.getPlayers().get(0);
            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            seat.dangerouslySetController(controller);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(
                    new BufferedReader(new PipedReader(client, 1 << 16)),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 0);
            gui.bind(game, seat, controller);
            controller.setGui(gui);
            gui.setGameView(game.getView());
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui, new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            game.subscribeToEvents(new Object() {
                @Subscribe
                public void moved(final GameEventCardChangeZone e) {
                    moves.add(e);
                }
            });
            gui.startReader();

            final JsonObject puzzle = new JsonObject();
            puzzle.addProperty("state", state);
            puzzle.addProperty("goal", goal);
            puzzle.addProperty("turns", turns);
            final JsonObject config = JsonParser.parseString("{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":"
                    + "\"config\",\"session\":\"" + SESSION + "\",\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\"}")
                    .getAsJsonObject();
            config.add("puzzle", puzzle);
            final InteractivePuzzle.Spec spec = InteractivePuzzle.read(config);
            final List<String> lines = InteractivePuzzle.lines(spec, 0);
            InteractivePuzzle.checkCards(lines);
            final Runnable hook = InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game);

            // The install on its own thread: a question to the browser seat parks it on the seat's input queue.
            final Thread install = new Thread(() -> {
                try {
                    hook.run();
                } catch (Throwable t) {
                    failure = t;
                }
            }, "puzzle install (setup test)");
            install.setDaemon(true);
            install.start();
            final long deadline = System.currentTimeMillis() + 120000;
            while (install.isAlive() && System.currentTimeMillis() < deadline) {
                final JsonObject m = wire.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && "request".equals(m.get("type").getAsString())) {
                    requests.add(m);
                    break; // the install now waits for an answer it will never get
                }
            }
            install.join(requests.isEmpty() ? Math.max(1, deadline - System.currentTimeMillis()) : 1);
            for (JsonObject m; (m = wire.poll()) != null; ) {
                if ("request".equals(m.get("type").getAsString())) {
                    requests.add(m);
                }
            }
            if (requests.isEmpty() && install.isAlive()) {
                throw new AssertionError("the install did not finish in 120 s and asked nothing");
            }
        }

        /** Each request, whole, for the failure message. */
        List<String> asked() {
            final List<String> out = new ArrayList<>();
            for (JsonObject r : requests) {
                out.add(r.toString());
            }
            return out;
        }

        /** Each move the browser seat is told about that names a hand as its origin. */
        List<String> fromAHand() {
            final List<String> out = new ArrayList<>();
            for (GameEventCardChangeZone e : moves) {
                final JsonObject encoded = InteractiveGameEvents.encode(e, game, seat.getView());
                if (encoded.has("fromZone") && "Hand".equals(encoded.get("fromZone").getAsString())) {
                    out.add(encoded.toString());
                }
            }
            return out;
        }

        @Override
        public void close() {
            controller.getInputQueue().onGameOver(true);
            gui.close();
        }
    }

    private static final class LineSink extends OutputStream {
        private final BlockingQueue<JsonObject> wire;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineSink(final BlockingQueue<JsonObject> wire) {
            this.wire = wire;
        }

        @Override
        public synchronized void write(final int b) {
            if (b == '\n') {
                wire.add(JsonParser.parseString(line.toString(StandardCharsets.UTF_8)).getAsJsonObject());
                line.reset();
            } else {
                line.write(b);
            }
        }
    }

    /** One seat of a [state] block: its life and its battlefield as written (name, tapped), tokens counted apart. */
    record Side(Integer life, List<Map.Entry<String, Boolean>> permanents, int tokens, int hand) {
        static Side of(final String state, final String prefix) {
            final Map<String, String> kv = new LinkedHashMap<>();
            for (String line : state.split("\\R")) {
                final int eq = line.indexOf('=');
                if (eq > 0) {
                    kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            final String life = kv.get(prefix + "life");
            final List<Map.Entry<String, Boolean>> permanents = new ArrayList<>();
            int tokens = 0;
            for (String entry : cards(kv.get(prefix + "battlefield"))) {
                final String[] fields = entry.split("\\|");
                if (fields[0].startsWith("t:") || fields[0].startsWith("T:")) {
                    tokens++;
                    continue;
                }
                boolean tapped = false;
                for (String f : fields) {
                    tapped |= f.startsWith("Tapped");
                }
                permanents.add(Map.entry(fields[0], tapped));
            }
            return new Side(life == null ? null : Integer.valueOf(life), permanents, tokens,
                    cards(kv.get(prefix + "hand")).size());
        }

        private static List<String> cards(final String value) {
            final List<String> out = new ArrayList<>();
            if (value != null) {
                for (String s : value.split(";")) {
                    if (!s.isBlank()) {
                        out.add(s.trim());
                    }
                }
            }
            return out;
        }

        /** How the installed seat differs from this record; empty when it is as written. */
        List<String> differences(final Player p) {
            final List<String> out = new ArrayList<>();
            if (life != null && p.getLife() != life) {
                out.add(p.getName() + " life " + p.getLife() + ", the record " + life);
            }
            final List<Map.Entry<String, Boolean>> installed = new ArrayList<>();
            int installedTokens = 0;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (c.isToken()) {
                    installedTokens++;
                } else {
                    installed.add(Map.entry(c.getPaperCard().getName(), c.isTapped()));
                }
            }
            if (!installed.equals(permanents)) {
                out.add(p.getName() + " battlefield (name=tapped) " + installed + ", the record " + permanents);
            }
            if (installedTokens != tokens) {
                out.add(p.getName() + " tokens " + installedTokens + ", the record " + tokens);
            }
            if (p.getCardsIn(ZoneType.Hand).size() != hand) {
                out.add(p.getName() + " hand " + p.getCardsIn(ZoneType.Hand).size() + ", the record " + hand);
            }
            return out;
        }
    }

    /** What an installed position got wrong: a question asked, a seat unlike its record, a move out of a hand. */
    static List<String> check(final Install in, final String state) {
        final List<String> out = new ArrayList<>();
        if (in.failure != null) {
            out.add("the install failed: " + in.failure);
        }
        for (String asked : in.asked()) {
            out.add("asked during the install: " + asked);
        }
        out.addAll(Side.of(state, "human").differences(in.game.getPlayers().get(0)));
        out.addAll(Side.of(state, "ai").differences(in.game.getPlayers().get(1)));
        for (String moved : in.fromAHand()) {
            out.add("the seat is told a card came from a hand: " + moved);
        }
        return out;
    }

    private static Card onBattlefield(final Player p, final String name) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getPaperCard().getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Test(timeOut = 600000)
    public void puzzleT8InstallsAt18LifeWithoutAsking() throws Exception {
        try (Install in = new Install(initAndCreateGame(), T8, "win", 8)) {
            final List<String> wrong = check(in, T8);
            assertTrue(wrong.isEmpty(), String.join("\n", wrong));
            final Player human = in.game.getPlayers().get(0);
            assertEquals(human.getLife(), 18);
            final Card tomb = onBattlefield(human, "Overgrown Tomb");
            assertTrue(tomb != null && !tomb.isTapped(), "Overgrown Tomb is on the battlefield, untapped as written");
        }
    }

    @Test(timeOut = 600000)
    public void puzzleT6InstallsAt9LifeWithoutAsking() throws Exception {
        try (Install in = new Install(initAndCreateGame(), T6, "win", 8)) {
            final List<String> wrong = check(in, T6);
            assertTrue(wrong.isEmpty(), String.join("\n", wrong));
            final Player human = in.game.getPlayers().get(0);
            assertEquals(human.getLife(), 9, "the saved 9 life, not 7");
            final Card tomb = onBattlefield(human, "Overgrown Tomb");
            assertTrue(tomb != null && tomb.isTapped(), "Overgrown Tomb is on the battlefield, |Tapped as written");
            assertEquals(human.getCardsIn(ZoneType.Hand).size(), 4, "the Tomb is not left in or taken from the hand");
            assertEquals(human.getCardsIn(ZoneType.Exile).size(), 27);
            assertEquals(human.getCardsIn(ZoneType.Graveyard).size(), 1);
        }
    }
}
