package forge.interactive;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * ACTION PREVIEW (lane action-preview-1006): each priority {@code card:<fid>} control carries
 * {@code value.preview}, what a click will ask. One test per preview shape; where Forge then asks
 * a question, the test clicks and checks the live prompt against the preview (targets, X, the
 * ability chooser, a land's "pay 2 life?"). The last tests pin the read-only contract: no event,
 * no timestamp and no id moves, and the property turns it off.
 *
 * <p>The harness is {@link InteractivePaymentColourTest}'s: Forge's real cast path for the seat.</p>
 */
public class InteractivePreviewTest extends AITest {

    private static final String SESSION = "preview-test";

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();

        Harness(final BiConsumer<Player, Player> board) throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            board.accept(seat, foe);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
            game.getAction().checkStateEffects(true);

            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            controller.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
            seat.dangerouslySetController(controller);
            final PipedReader fromClient = new PipedReader(client, 1 << 16);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(new BufferedReader(fromClient),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, seat, controller);
            controller.setGui(gui);
            gui.setGameView(game.getView());
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui,
                    new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            gui.startReader();

            final Thread forgeThread = new Thread(() -> {
                while (!closed.get() && !gui.hasFailed() && !game.isGameOver()) {
                    final List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
                    if (chosen == null) {
                        continue;
                    }
                    for (SpellAbility ability : chosen) {
                        controller.playChosenSpellAbility(ability);
                    }
                }
            }, "Game thread (preview test)");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject request() throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                assertNotNull(message, "a request");
                final String got = message.get("type").getAsString();
                if ("error".equals(got)) {
                    fail("the bridge failed: " + message);
                }
                if ("request".equals(got)) {
                    return message;
                }
            }
        }

        void click(final JsonObject request, final Card card) throws IOException {
            final JsonObject action = new JsonObject();
            action.addProperty("type", "selectCard");
            action.addProperty("controlId", "card:" + card.getId());
            final JsonObject input = new JsonObject();
            input.addProperty("protocol", "mtgx-forge-interactive/1");
            input.addProperty("session", SESSION);
            input.addProperty("type", "input");
            input.addProperty("requestId", request.get("requestId").getAsString());
            input.addProperty("inputId", UUID.randomUUID().toString());
            input.addProperty("kind", request.get("kind").getAsString());
            input.add("action", action);
            client.write(input + "\n");
            client.flush();
        }

        @Override
        public void close() {
            closed.set(true);
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

    /** The control for {@code card} in a request, or null. */
    private static JsonObject control(final JsonObject request, final Card card) {
        for (JsonElement c : request.getAsJsonArray("controls")) {
            if (("card:" + card.getId()).equals(c.getAsJsonObject().get("controlId").getAsString())) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonObject preview(final JsonObject request, final Card card) {
        final JsonObject control = control(request, card);
        assertNotNull(control, card + " is offered: " + request);
        final JsonObject value = control.getAsJsonObject("value");
        assertTrue(value.has("preview"), "the control carries a preview: " + control);
        final JsonObject preview = value.getAsJsonObject("preview");
        assertEquals(preview.get("v").getAsInt(), InteractivePreview.VERSION);
        assertFalse(preview.has("partial"), "complete within the default budget: " + preview);
        return preview;
    }

    private static JsonObject ability(final JsonObject preview, final int i) {
        return preview.getAsJsonArray("abilities").get(i).getAsJsonObject();
    }

    private static Set<String> strings(final JsonArray array) {
        final Set<String> out = new TreeSet<>();
        if (array != null) {
            array.forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    /** The ids of the request's controls of one type, minus their prefix. */
    private static Set<String> controlIds(final JsonObject request, final String prefix) {
        final Set<String> out = new TreeSet<>();
        for (JsonElement c : request.getAsJsonArray("controls")) {
            final String id = c.getAsJsonObject().get("controlId").getAsString();
            if (id.startsWith(prefix)) {
                out.add(id.substring(prefix.length()));
            }
        }
        return out;
    }

    private static boolean inHand(final JsonObject request, final Card card) {
        for (JsonElement p : request.getAsJsonObject("view").getAsJsonArray("players")) {
            for (JsonElement c : p.getAsJsonObject().getAsJsonArray("hand")) {
                if (c.getAsJsonObject().get("fid").getAsInt() == card.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- shapes ----------------------------------------------------------------------------

    /** A plain sorcery: one ability, nothing asked but the mana. */
    @Test(timeOut = 600000)
    public void aPlainSpellAsksOnlyForMana() throws Exception {
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            for (int i = 0; i < 3; i++) addCard("Island", seat);
            spell[0] = addCardToZone("Divination", seat, ZoneType.Hand);
        })) {
            final JsonObject p = preview(h.request(), spell[0]);
            assertFalse(p.has("asks"), "one ability, no chooser: " + p);
            final JsonObject a = ability(p, 0);
            assertEquals(a.get("kind").getAsString(), "spell");
            assertFalse(a.has("targets") || a.has("x") || a.has("inHand") || a.has("refusal"), a.toString());
            assertTrue(a.getAsJsonObject("mana").get("asks").getAsBoolean(), a.toString());
        }
    }

    /** Lightning Bolt: the target slot's candidates are exactly what Forge's target prompt offers. */
    @Test(timeOut = 600000)
    public void targetCandidatesMatchTheLivePrompt() throws Exception {
        final Card[] cards = new Card[3];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Mountain", seat);
            cards[0] = addCardToZone("Lightning Bolt", seat, ZoneType.Hand);
            cards[1] = addCard("Grizzly Bears", foe);
            cards[2] = addCard("Grizzly Bears", seat);
        })) {
            final JsonObject priority = h.request();
            final JsonObject a = ability(preview(priority, cards[0]), 0);
            final JsonObject slot = a.getAsJsonArray("targets").get(0).getAsJsonObject();
            assertEquals(slot.get("min").getAsInt(), 1);
            assertEquals(slot.get("max").getAsInt(), 1);
            assertEquals(slot.get("ask").getAsString(), "target");
            assertTrue(strings(slot.getAsJsonArray("cards")).containsAll(
                    Set.of(String.valueOf(cards[1].getId()), String.valueOf(cards[2].getId()))), slot.toString());
            assertEquals(strings(slot.getAsJsonArray("players")), Set.of("0", "1"), slot.toString());

            h.click(priority, cards[0]);
            final JsonObject target = h.request();
            assertEquals(target.get("kind").getAsString(), "target", target.toString());
            assertEquals(controlIds(target, "card:"), strings(slot.getAsJsonArray("cards")), "same cards as the live prompt");
            assertEquals(controlIds(target, "player:"), strings(slot.getAsJsonArray("players")), "same players");
        }
    }

    /** Doom Blade with only a black creature to aim at: Forge would roll the cast back. */
    @Test(timeOut = 600000)
    public void tooFewTargetsIsARefusal() throws Exception {
        final Card[] spell = new Card[2];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            spell[0] = addCardToZone("Doom Blade", seat, ZoneType.Hand);
            spell[1] = addCardToZone("Counterspell", seat, ZoneType.Hand);
            addCard("Vampire Nighthawk", foe);
            addCard("Island", seat);
            addCard("Island", seat);
        })) {
            final JsonObject priority = h.request();
            final JsonObject blade = ability(preview(priority, spell[0]), 0);
            assertEquals(blade.get("refusal").getAsString(), "no-targets", blade.toString());
            final JsonObject counter = ability(preview(priority, spell[1]), 0);
            assertEquals(counter.get("refusal").getAsString(), "no-targets", "an empty stack: " + counter);
            assertEquals(counter.getAsJsonArray("targets").get(0).getAsJsonObject().get("ask").getAsString(), "choice");
        }
    }

    /** A charm: its modes, which are possible, and whether Forge asks (it does not when the choice is forced). */
    @Test(timeOut = 600000)
    public void charmModes() throws Exception {
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Swamp", seat);
            addCard("Mountain", seat);
            addCard("Mountain", seat);
            spell[0] = addCardToZone("Kolaghan's Command", seat, ZoneType.Hand);
        })) {
            final JsonObject a = ability(preview(h.request(), spell[0]), 0);
            final JsonObject modes = a.getAsJsonObject("modes");
            assertEquals(modes.getAsJsonArray("options").size(), 4, modes.toString());
            assertEquals(modes.get("min").getAsInt(), 2);
            assertEquals(modes.get("max").getAsInt(), 2);
            // No creature card in the graveyard and no artifact: two modes left, both forced.
            assertFalse(modes.get("asks").getAsBoolean(), modes.toString());
            assertFalse(a.has("inHand"), a.toString());
        }
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Swamp", seat);
            addCard("Mountain", seat);
            addCard("Mountain", seat);
            addCard("Sol Ring", foe);
            spell[0] = addCardToZone("Kolaghan's Command", seat, ZoneType.Hand);
        })) {
            final JsonObject a = ability(preview(h.request(), spell[0]), 0);
            assertTrue(a.getAsJsonObject("modes").get("asks").getAsBoolean(), a.toString());
            assertEquals(strings(a.getAsJsonArray("inHand")), Set.of("modes"), a.toString());
        }
    }

    /** Blaze: the X range is the live prompt's (`mana-x`, the same HumanManaX call). */
    @Test(timeOut = 600000)
    public void xRangeMatchesTheLivePrompt() throws Exception {
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            for (int i = 0; i < 4; i++) addCard("Mountain", seat);
            spell[0] = addCardToZone("Blaze", seat, ZoneType.Hand);
        })) {
            final JsonObject priority = h.request();
            final JsonObject x = ability(preview(priority, spell[0]), 0).getAsJsonObject("x");
            assertEquals(x.get("control").getAsString(), "mana-x", x.toString());
            assertEquals(x.get("min").getAsInt(), 0);
            assertEquals(x.get("max").getAsInt(), 3);
            assertTrue(x.get("exact").getAsBoolean(), x.toString());

            h.click(priority, spell[0]);
            final JsonObject number = h.request();
            assertEquals(number.get("kind").getAsString(), "number", number.toString());
            final JsonObject live = number.getAsJsonArray("controls").get(0).getAsJsonObject();
            assertEquals(live.get("controlId").getAsString(), "mana-x");
            assertEquals(live.get("min").getAsInt(), x.get("min").getAsInt());
            assertEquals(live.get("max").getAsInt(), x.get("max").getAsInt());
        }
    }

    /** Burst Lightning: kicker is asked while the card is still in the hand. */
    @Test(timeOut = 600000)
    public void optionalCostsAreAskedInHand() throws Exception {
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Mountain", seat);
            spell[0] = addCardToZone("Burst Lightning", seat, ZoneType.Hand);
        })) {
            final JsonObject priority = h.request();
            final JsonObject a = ability(preview(priority, spell[0]), 0);
            assertEquals(strings(a.getAsJsonArray("inHand")), Set.of("optionalCosts"), a.toString());
            assertEquals(a.getAsJsonArray("optionalCosts").get(0).getAsJsonObject().get("type").getAsString(), "Kicker1");

            h.click(priority, spell[0]);
            final JsonObject asked = h.request();
            assertTrue(inHand(asked, spell[0]), "Forge asks while the card is in the hand: " + asked);
        }
    }

    /** Censor: cast it or cycle it. Two abilities open Forge's chooser with ability:<i> per preview index. */
    @Test(timeOut = 600000)
    public void twoAbilitiesOpenTheChooser() throws Exception {
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Island", seat);
            addCard("Island", seat);
            spell[0] = addCardToZone("Censor", seat, ZoneType.Hand);
        })) {
            final JsonObject priority = h.request();
            final JsonObject p = preview(priority, spell[0]);
            assertEquals(p.get("asks").getAsString(), "ability", p.toString());
            final JsonArray abilities = p.getAsJsonArray("abilities");
            assertEquals(abilities.size(), 2, p.toString());
            final Set<String> offered = new TreeSet<>();
            for (int i = 0; i < abilities.size(); i++) {
                final JsonObject a = abilities.get(i).getAsJsonObject();
                assertEquals(a.get("i").getAsInt(), i);
                if (!a.has("offered")) offered.add(String.valueOf(i));
            }
            final JsonObject spellAbility = ability(p, 0).get("kind").getAsString().equals("spell") ? ability(p, 0) : ability(p, 1);
            assertEquals(spellAbility.get("refusal").getAsString(), "no-targets", "nothing on the stack: " + spellAbility);

            h.click(priority, spell[0]);
            final JsonObject chooser = h.request();
            assertEquals(chooser.get("inputClass").getAsString(), "modal:getAbilityToPlay", chooser.toString());
            assertEquals(controlIds(chooser, "ability:").stream().filter(id -> !id.equals("cancel"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new)), offered, chooser.toString());
            assertTrue(inHand(chooser, spell[0]), "asked in the hand");
        }
    }

    /** Force of Will with no lands: only the alternative cost is offered; its exile candidates are listed. */
    @Test(timeOut = 600000)
    public void alternativeCostAndItsCandidates() throws Exception {
        final Card[] cards = new Card[2];
        try (Harness h = new Harness((seat, foe) -> {
            cards[0] = addCardToZone("Force of Will", seat, ZoneType.Hand);
            cards[1] = addCardToZone("Counterspell", seat, ZoneType.Hand);
            addCardToZone("Lightning Bolt", seat, ZoneType.Hand);
        })) {
            final JsonObject p = preview(h.request(), cards[0]);
            assertEquals(p.get("asks").getAsString(), "ability", p.toString());
            JsonObject alt = null;
            for (JsonElement e : p.getAsJsonArray("abilities")) {
                final JsonObject a = e.getAsJsonObject();
                if (a.has("costs")) alt = a;
                else assertFalse(a.has("offered") && a.get("offered").getAsBoolean(), "the mana cost is unaffordable: " + a);
            }
            assertNotNull(alt, "the alternative cost: " + p);
            assertFalse(alt.has("offered"), alt.toString());
            final List<String> types = new ArrayList<>();
            JsonObject exile = null;
            for (JsonElement e : alt.getAsJsonArray("costs")) {
                types.add(e.getAsJsonObject().get("type").getAsString());
                if ("exile".equals(e.getAsJsonObject().get("type").getAsString())) exile = e.getAsJsonObject();
            }
            assertTrue(types.containsAll(List.of("life", "exile")), types.toString());
            assertNotNull(exile);
            assertEquals(strings(exile.getAsJsonArray("cards")), Set.of(String.valueOf(cards[1].getId())),
                    "the blue card, not Force itself and not the red one: " + exile);
            assertEquals(exile.get("from").getAsString(), "Hand");
        }
    }

    /** Watery Grave asks "pay 2 life?" in the hand; at 1 life it cannot pay, so it does not ask. */
    @Test(timeOut = 600000)
    public void shockLandAsksToPayLife() throws Exception {
        final Card[] land = new Card[1];
        try (Harness h = new Harness((seat, foe) -> land[0] = addCardToZone("Watery Grave", seat, ZoneType.Hand))) {
            final JsonObject priority = h.request();
            final JsonObject a = ability(preview(priority, land[0]), 0);
            assertEquals(a.get("kind").getAsString(), "land");
            assertEquals(strings(a.getAsJsonObject("etb").getAsJsonArray("asks")), Set.of("pay-life"), a.toString());

            h.click(priority, land[0]);
            final JsonObject asked = h.request();
            assertEquals(asked.get("kind").getAsString(), "confirm", asked.toString());
            assertTrue(inHand(asked, land[0]), "asked while the land is in the hand: " + asked);
        }
        try (Harness h = new Harness((seat, foe) -> {
            seat.setLife(1, null);
            land[0] = addCardToZone("Watery Grave", seat, ZoneType.Hand);
        })) {
            final JsonObject a = ability(preview(h.request(), land[0]), 0);
            assertFalse(a.has("etb"), "cannot pay 2 life: no prompt, it enters tapped: " + a);
        }
    }

    /** A snarl asks only when the hand holds a card it may reveal. */
    @Test(timeOut = 600000)
    public void snarlAsksOnlyWithACardToReveal() throws Exception {
        final Card[] land = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            land[0] = addCardToZone("Shineshadow Snarl", seat, ZoneType.Hand);
            addCardToZone("Plains", seat, ZoneType.Hand);
        })) {
            final JsonObject a = ability(preview(h.request(), land[0]), 0);
            assertEquals(strings(a.getAsJsonObject("etb").getAsJsonArray("asks")), Set.of("reveal"), a.toString());
        }
        try (Harness h = new Harness((seat, foe) -> {
            land[0] = addCardToZone("Shineshadow Snarl", seat, ZoneType.Hand);
            addCardToZone("Island", seat, ZoneType.Hand);
        })) {
            final JsonObject a = ability(preview(h.request(), land[0]), 0);
            assertFalse(a.has("etb"), a.toString());
        }
    }

    /** A thriving land chooses a colour as it enters. */
    @Test(timeOut = 600000)
    public void thrivingLandChoosesAColour() throws Exception {
        final Card[] land = new Card[1];
        try (Harness h = new Harness((seat, foe) -> land[0] = addCardToZone("Thriving Isle", seat, ZoneType.Hand))) {
            final JsonObject a = ability(preview(h.request(), land[0]), 0);
            assertTrue(strings(a.getAsJsonObject("etb").getAsJsonArray("asks")).contains("choose-color"), a.toString());
        }
    }

    // ---- contract ---------------------------------------------------------------------------

    /** {@code -Dforge.interactive.preview=off}: no preview, the control is exactly as before. */
    @Test(timeOut = 600000)
    public void offWhenThePropertySaysSo() throws Exception {
        final String before = System.getProperty(InteractivePreview.PROPERTY);
        System.setProperty(InteractivePreview.PROPERTY, "off");
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Mountain", seat);
            spell[0] = addCardToZone("Lightning Bolt", seat, ZoneType.Hand);
        })) {
            final JsonObject control = control(h.request(), spell[0]);
            assertNotNull(control);
            assertFalse(control.getAsJsonObject("value").has("preview"), control.toString());
        } finally {
            if (before == null) System.clearProperty(InteractivePreview.PROPERTY);
            else System.setProperty(InteractivePreview.PROPERTY, before);
        }
    }

    /** A spent budget leaves every card {@code partial}: the browser then does what it does today. */
    @Test(timeOut = 600000)
    public void aSpentBudgetMarksThePreviewPartial() throws Exception {
        final String before = System.getProperty(InteractivePreview.BUDGET_PROPERTY);
        System.setProperty(InteractivePreview.BUDGET_PROPERTY, "0");
        final Card[] spell = new Card[2];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Mountain", seat);
            spell[0] = addCardToZone("Lightning Bolt", seat, ZoneType.Hand);
            spell[1] = addCardToZone("Burst Lightning", seat, ZoneType.Hand);
        })) {
            final JsonObject request = h.request();
            for (Card card : spell) {
                final JsonObject p = control(request, card).getAsJsonObject("value").getAsJsonObject("preview");
                assertTrue(p.get("partial").getAsBoolean(), p.toString());
                assertFalse(p.has("abilities") && p.getAsJsonArray("abilities").size() > 0, p.toString());
            }
        } finally {
            if (before == null) System.clearProperty(InteractivePreview.BUDGET_PROPERTY);
            else System.setProperty(InteractivePreview.BUDGET_PROPERTY, before);
        }
    }

    private static final class EventCounter {
        int count;

        @Subscribe
        public void receive(final GameEvent event) {
            count++;
        }
    }

    /**
     * The read-only contract on a board with every shape at once: computing the previews fires no
     * game event, takes no timestamp, moves no spell-ability id and moves no card.
     */
    @Test(timeOut = 600000)
    public void thePreviewIsSilent() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        final Player foe = game.getPlayers().get(0);
        for (int i = 0; i < 4; i++) addCard("Mountain", seat);
        addCard("Island", seat);
        addCard("Swamp", seat);
        addCard("Grizzly Bears", foe);
        addCard("Sol Ring", foe);
        final List<Card> hand = new ArrayList<>();
        for (String name : List.of("Lightning Bolt", "Blaze", "Burst Lightning", "Kolaghan's Command", "Censor",
                "Force of Will", "Counterspell", "Watery Grave", "Shineshadow Snarl", "Thriving Isle", "Doom Blade")) {
            hand.add(addCardToZone(name, seat, ZoneType.Hand));
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
        game.getAction().checkStateEffects(true);
        final List<List<SpellAbility>> possible = new ArrayList<>();
        for (Card card : hand) {
            possible.add(card.getAllPossibleAbilities(seat, true));
        }
        final EventCounter counter = new EventCounter();
        game.subscribeToEvents(counter);
        final long timestamp = game.getNextTimestamp();
        final int idBefore = new SpellAbility.EmptySa(hand.get(0)).getId();
        final List<String> zones = new ArrayList<>();
        hand.forEach(c -> zones.add(String.valueOf(c.getZone().getZoneType())));

        final InteractivePreview.Seat view = new InteractivePreview.Seat() {
            @Override public String abilityLabel(final SpellAbility ability) { return String.valueOf(ability.getDescription()); }
            @Override public String text(final String text) { return String.valueOf(text); }
            @Override public boolean mayList(final Card card) { return true; }
            @Override public int seatOf(final Player player) { return game.getRegisteredPlayers().indexOf(player); }
        };
        final InteractivePreview.Pass pass = new InteractivePreview.Pass(Long.MAX_VALUE / 4);
        final List<JsonObject> previews = new ArrayList<>();
        // Deliberately OUTSIDE a detached id scope (the bridge adds one as a second guard).
        for (int i = 0; i < hand.size(); i++) {
            previews.add(InteractivePreview.forCard(pass, seat, hand.get(i), possible.get(i), a -> true, view));
        }

        assertEquals(new SpellAbility.EmptySa(hand.get(0)).getId(), idBefore + 1, "no spell-ability id was taken");
        assertEquals(counter.count, 0, "no game event");
        assertEquals(game.getNextTimestamp(), timestamp + 1, "no timestamp");
        final List<String> after = new ArrayList<>();
        hand.forEach(c -> after.add(String.valueOf(c.getZone().getZoneType())));
        assertEquals(after, zones, "no card moved");
        for (JsonObject p : previews) {
            assertFalse(p.has("partial") || p.has("error"), p.toString());
            assertTrue(p.getAsJsonArray("abilities").size() > 0, p.toString());
        }
        assertNull(InteractivePreview.scriptParam("DB$ Tap | UnlessCost$ PayLife<2>", "Missing"));
        assertEquals(InteractivePreview.scriptParam("DB$ Tap | UnlessCost$ PayLife<2>", "UnlessCost"), "PayLife<2>");
    }
}
