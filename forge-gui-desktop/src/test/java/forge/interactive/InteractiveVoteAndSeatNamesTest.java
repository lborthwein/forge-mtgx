package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.event.GameEventVote;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.HumanManaX;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Owner reports of the dev Forge game 6cc1b95d (2026-10-29):
 * <ul>
 * <li>18-03-47, Council's Judgment cast by the AI: "I don't see what the AI voted for!" The AI's vote came as
 * "Default Forge vote Craterhoof Behemoth (32)\r\nCurrent Votes:{...}", and the human's vote request said nothing of
 * it. Now every request while the vote is open carries {@code context.votes} / {@code voteOrder}, the announcement is
 * in words with seat names, and each vote is a {@code vote_cast} event (the game-log line).</li>
 * <li>Seat names: prompt and modal text never says Forge's engine names, nor the "Forge" placeholder title.</li>
 * <li>17-50-18, Reclamation Sage: the target request of an optional trigger says the "use it?" question comes on
 * resolution ({@code context.optionalOnResolve}).</li>
 * <li>17-59-25, Fiend Artisan: the X request with an unknown affordability gives {@code value.manaMax}.</li>
 * </ul>
 * None of it changes play: these are context fields and text. Seats are registered order: the foe (AI) is 0, the
 * browser seat is 1.
 */
public class InteractiveVoteAndSeatNamesTest extends AITest {

    private static final String SESSION = "vote-seat-names-test";

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final List<JsonObject> seen = new ArrayList<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();
        final Thread forgeThread;

        /** {@code play}: what the game thread does (null: the seat's priority loop). */
        Harness(final BiConsumer<Player, Player> board, final List<String> seatNames,
                 final Consumer<Harness> play) throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            board.accept(seat, foe);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
            game.getAction().checkStateEffects(true);
            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            controller.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
            seat.dangerouslySetController(controller);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(
                    new BufferedReader(new PipedReader(client, 1 << 16)),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, seat, controller);
            gui.setSeatNames(seatNames);
            controller.setGui(gui);
            gui.setGameView(game.getView());
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui, new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            gui.startReader();
            forgeThread = new Thread(() -> {
                if (play != null) {
                    play.accept(this);
                    return;
                }
                while (!closed.get() && !gui.hasFailed() && !game.isGameOver()) {
                    final List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
                    if (chosen != null) {
                        for (SpellAbility ability : chosen) {
                            controller.playChosenSpellAbility(ability);
                        }
                    }
                }
            }, "Game thread (vote and seat names test)");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject request() throws InterruptedException {
            return next("request");
        }

        JsonObject next(final String type) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final JsonObject m = wire.poll(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                assertNotNull(m, "a " + type + " (seen: " + seen + ")");
                seen.add(m);
                if ("error".equals(m.get("type").getAsString())) {
                    fail("the bridge failed: " + m);
                }
                if (type.equals(m.get("type").getAsString())) {
                    return m;
                }
            }
        }

        void send(final JsonObject request, final JsonObject action) throws IOException {
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

        void answer(final JsonObject request, final String type, final String controlId) throws IOException {
            final JsonObject action = new JsonObject();
            action.addProperty("type", type);
            action.addProperty("controlId", controlId);
            send(request, action);
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

    private static void assertNoEngineNames(final JsonObject request, final Player... players) {
        final JsonObject shown = new JsonObject();
        shown.add("prompt", request.get("prompt"));
        shown.add("controls", request.get("controls"));
        if (request.has("context")) {
            shown.add("context", request.get("context"));
        }
        final String text = shown.toString();
        assertFalse(text.contains("Forge"), "player-facing text never says Forge: " + text);
        for (Player p : players) {
            assertFalse(text.matches("(?s).*\\b" + java.util.regex.Pattern.quote(p.getName()) + "\\b.*"),
                    "engine name " + p.getName() + " in " + text);
        }
    }

    /**
     * The host's boundary (mtgx tools/forge/interactive-protocol.ts parseContext): a context value is a JSON primitive,
     * or a record per advertised option (a controlId or an item id) of primitives. Anything else ends the session.
     */
    private static void assertHostAdmits(final JsonObject request) {
        if (!request.has("context")) {
            return;
        }
        final java.util.Set<String> advertised = new java.util.HashSet<>();
        for (JsonElement c : request.getAsJsonArray("controls")) {
            final JsonObject control = c.getAsJsonObject();
            advertised.add(control.get("controlId").getAsString());
            if (control.has("items")) {
                for (JsonElement i : control.getAsJsonArray("items")) {
                    advertised.add(i.getAsJsonObject().get("id").getAsString());
                }
            }
        }
        for (var entry : request.getAsJsonObject("context").entrySet()) {
            final JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                for (var option : value.getAsJsonObject().entrySet()) {
                    assertTrue(advertised.contains(option.getKey()), entry.getKey() + " names " + option.getKey());
                    for (var fact : option.getValue().getAsJsonObject().entrySet()) {
                        assertTrue(fact.getValue().isJsonPrimitive() || fact.getValue().isJsonNull(),
                                entry.getKey() + "." + option.getKey() + "." + fact.getKey());
                    }
                }
            } else {
                assertTrue(value.isJsonPrimitive() || value.isJsonNull(), "context." + entry.getKey() + " = " + value);
            }
        }
    }

    /** The foe (AI) casts Council's Judgment; the seat votes second. */
    @Test(timeOut = 900000)
    public void councilsJudgmentShowsTheAisVoteWhileTheSeatDecides() throws Exception {
        final Card[] hoof = new Card[1];
        final Card[] ring = new Card[1];
        final Card[] judgment = new Card[1];
        final List<GameEventVote> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (Harness h = new Harness((seat, foe) -> {
            hoof[0] = addCard("Craterhoof Behemoth", seat);
            ring[0] = addCard("Sol Ring", seat);
            judgment[0] = addCardToZone("Council's Judgment", foe, ZoneType.Hand);
            // a resolving spell is on the stack, where everyone sees it
            seat.getGame().getStackZone().add(judgment[0]);
        }, null, self -> {
            final SpellAbility sa = judgment[0].getFirstSpellAbility();
            sa.setActivatingPlayer(self.foe);
            AbilityUtils.resolve(sa);
        })) {
            h.game.subscribeToEvents(new Object() {
                @com.google.common.eventbus.Subscribe
                public void on(final forge.game.event.GameEvent e) {
                    if (e instanceof GameEventVote v) {
                        events.add(v);
                    }
                }
            });
            // 1. the AI's vote, announced in words, with the votes so far in context
            final JsonObject announce = h.request();
            assertEquals(announce.get("inputClass").getAsString(), "modal:message", announce.toString());
            final String message = announce.getAsJsonObject("prompt").get("message").getAsString();
            assertTrue(message.startsWith("AI Player votes for "), message);
            assertTrue(message.contains("Votes so far:"), message);
            assertTrue(message.contains("Still to vote: You"), message);
            assertFalse(message.contains("Current Votes"), message);
            assertNoEngineNames(announce, h.seat, h.foe);
            final JsonObject announced = announce.getAsJsonObject("context");
            assertTrue(announced.get("voteOpen").getAsBoolean(), announced.toString());
            assertEquals(announced.get("voteOrder").getAsString(), "0,1");
            assertEquals(announced.get("voteOrder0").getAsInt(), 0);
            assertEquals(announced.get("voteOrder1").getAsInt(), 1);
            assertEquals(announced.get("voteNextSeat").getAsInt(), 1);
            assertEquals(announced.get("voteSource").getAsInt(), judgment[0].getId());
            assertEquals(announced.get("voteSourceName").getAsString(), "Council's Judgment");
            assertEquals(announced.get("voteCount").getAsInt(), 1, announced.toString());
            assertEquals(announced.get("vote0Seat").getAsInt(), 0);
            assertEquals(announced.get("vote0ChoiceKind").getAsString(), "card");
            final int aiChoice = announced.get("vote0Choice").getAsInt();
            assertTrue(aiChoice == hoof[0].getId() || aiChoice == ring[0].getId(), announced.toString());
            assertTrue(message.contains(announced.get("vote0Label").getAsString()), message);
            assertHostAdmits(announce);
            h.answer(announce, "ok", "acknowledge");

            // 2. the seat's own vote request carries the AI's vote too
            final JsonObject vote = h.request();
            assertFalse(vote.getAsJsonObject("prompt").has("title")
                    && "Forge".equals(vote.getAsJsonObject("prompt").get("title").getAsString()), vote.toString());
            assertNoEngineNames(vote, h.seat, h.foe);
            final JsonObject context = vote.getAsJsonObject("context");
            assertNotNull(context, vote.toString());
            assertEquals(context.get("voteCount").getAsInt(), 1, context.toString());
            assertEquals(context.get("vote0Choice").getAsInt(), aiChoice);
            assertEquals(context.get("voteNextSeat").getAsInt(), 1);
            assertHostAdmits(vote);
            // the AI's choice, per option of this request
            final JsonObject tally = context.getAsJsonObject("voteTally");
            assertNotNull(tally, context.toString());
            assertEquals(tally.size(), 1, tally.toString());
            final JsonObject counted = tally.entrySet().iterator().next().getValue().getAsJsonObject();
            assertEquals(counted.get("votes").getAsInt(), 1);
            assertEquals(counted.get("seats").getAsString(), "0");
            String controlId = null;
            for (JsonElement c : vote.getAsJsonArray("controls")) {
                final JsonObject control = c.getAsJsonObject();
                if (control.has("cardId") && control.get("cardId").getAsInt() == hoof[0].getId()) {
                    controlId = control.get("controlId").getAsString();
                }
            }
            if (controlId != null) {
                h.answer(vote, "selectCard", controlId);
            } else {
                // a list choice: the item whose label names Craterhoof Behemoth
                String item = null;
                for (JsonElement c : vote.getAsJsonArray("controls")) {
                    final JsonObject control = c.getAsJsonObject();
                    if (control.has("items")) {
                        for (JsonElement i : control.getAsJsonArray("items")) {
                            if (i.getAsJsonObject().get("label").getAsString().contains("Craterhoof")) {
                                item = i.getAsJsonObject().get("id").getAsString();
                            }
                        }
                    }
                }
                assertNotNull(item, vote.toString());
                final JsonObject action = new JsonObject();
                action.addProperty("type", "choice");
                action.addProperty("controlId", "choices");
                final JsonArray choices = new JsonArray();
                choices.add(item);
                action.add("choices", choices);
                h.send(vote, action);
            }
            h.forgeThread.join(30000);
            assertFalse(h.forgeThread.isAlive(), "the vote resolved: " + h.seen);

            // 3. the engine events and the browser's log lines (vote_cast), in order
            assertEquals(events.get(0).stage(), GameEventVote.Stage.STARTED);
            assertEquals(events.get(events.size() - 1).stage(), GameEventVote.Stage.ENDED);
            final List<JsonObject> cast = new ArrayList<>();
            JsonObject m;
            while ((m = h.wire.poll(2, TimeUnit.SECONDS)) != null) {
                h.seen.add(m);
            }
            JsonObject started = null;
            for (JsonObject s : h.seen) {
                if ("event".equals(s.get("type").getAsString()) && s.getAsJsonObject("event").has("kind")) {
                    final JsonObject e = s.getAsJsonObject("event");
                    if ("vote_cast".equals(e.get("kind").getAsString())) {
                        cast.add(e);
                    } else if ("vote_started".equals(e.get("kind").getAsString())) {
                        started = e;
                    }
                }
            }
            assertNotNull(started, h.seen.toString());
            assertEquals(started.getAsJsonArray("order").toString(), "[0,1]");
            assertEquals(started.get("source").getAsString(), "Council's Judgment");
            assertEquals(cast.size(), 2, cast.toString());
            assertEquals(cast.get(0).get("playerSeat").getAsInt(), 0);
            assertEquals(cast.get(0).get("choiceFid").getAsInt(), aiChoice);
            assertEquals(cast.get(1).get("playerSeat").getAsInt(), 1);
            assertEquals(cast.get(1).get("choice").getAsString(), "Craterhoof Behemoth");
            assertEquals(cast.get(1).get("choiceFid").getAsInt(), hoof[0].getId());
        }
    }

    /** The host's seatNames replace the defaults ("You" / "AI Player"). */
    @Test
    public void seatNamesRenderEngineNamesAsDisplayNames() {
        final Game game = initAndCreateGame();
        final Player foe = game.getPlayers().get(0);   // "p2", an AI
        final Player seat = game.getPlayers().get(1);  // "p1"
        final InteractiveSeatNames defaults = new InteractiveSeatNames(null);
        // AITest's seats are both AI: the foe is "AI Player"; the viewer's own seat is AI too here
        assertEquals(defaults.render("Select creatures to attack " + foe.getName() + ".", game, 1),
                "Select creatures to attack AI Player.");
        final InteractiveSeatNames named = new InteractiveSeatNames(List.of("KETTLEsquad", "lukens"));
        assertEquals(named.render(foe.getName() + " is going first.\n" + seat.getName() + ", you are going 2nd.",
                game, 1), "KETTLEsquad is going first.\nlukens, you are going 2nd.");
        assertEquals(named.render("Looking at cards in " + foe.getName() + "'s library", game, 1),
                "Looking at cards in KETTLEsquad's library");
        // a name inside a longer word is not a name
        assertEquals(named.render("xp2y", game, 1), "xp2y");
    }

    /** "You" for the viewer reads as a reader expects (a one-seat human with no seatNames). */
    @Test
    public void theViewersOwnNameReadsAsYou() throws Exception {
        try (Harness h = new Harness((seat, foe) -> addCard("Plains", seat), null, null)) {
            final InteractiveSeatNames names = new InteractiveSeatNames(null);
            final String self = h.seat.getName();
            final String foe = h.foe.getName();
            assertEquals(names.render(foe + " is going first.\n" + self + ", you are going 2nd.", h.game, 1),
                    "AI Player is going first.\nYou are going 2nd.");
            assertEquals(names.render(self + " gains 3 life.", h.game, 1), "You gain 3 life.");
            assertEquals(names.render("Noise Marine deals 2 damage to " + self + ".", h.game, 1),
                    "Noise Marine deals 2 damage to you.");
            assertEquals(names.render(self + "'s library", h.game, 1), "Your library");
            assertEquals(names.render("Turn: 1 (" + foe + ")", h.game, 1), "Turn: 1 (AI Player)");
            // the priority request on the wire: no engine name, no "Forge" title
            final JsonObject priority = h.request();
            assertNoEngineNames(priority, h.seat, h.foe);
        }
    }

    @Test
    public void seatNamesConfigIsReadAndValidated() throws Exception {
        assertNull(InteractiveProtocol.readSeatNames("{\"protocol\":\"x\"}"));
        assertEquals(InteractiveProtocol.readSeatNames("{\"seatNames\":[\"lukens\",\"KETTLEsquad\"]}"),
                List.of("lukens", "KETTLEsquad"));
        for (String bad : List.of("{\"seatNames\":[\"a\"]}", "{\"seatNames\":[\"a\",\"<b>\"]}",
                "{\"seatNames\":\"a\"}")) {
            try {
                InteractiveProtocol.readSeatNames(bad);
                fail("refused: " + bad);
            } catch (InteractiveProtocol.ProtocolException expected) {
                assertEquals(expected.code(), "invalid_config");
            }
        }
    }

    /** Reclamation Sage's "you may destroy": its target request says the decline comes on resolution. */
    @Test(timeOut = 900000)
    public void anOptionalTriggersTargetRequestSaysTheChoiceComesOnResolution() throws Exception {
        final Card[] sage = new Card[1];
        final Card[] ring = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            sage[0] = addCardToZone("Reclamation Sage", seat, ZoneType.Hand);
            ring[0] = addCard("Sol Ring", foe);
            addCard("Mox Sapphire", foe);
            addCard("Mox Pearl", seat); // the owner's case: an own artifact among the legal targets
        }, null, self -> {
            self.game.getAction().moveToPlay(sage[0], null, null);
            self.game.getAction().checkStateEffects(true);
            self.game.getStack().addAllTriggeredAbilitiesToStack();
        })) {
            final JsonObject target = h.request();
            assertEquals(target.get("kind").getAsString(), "target", target.toString());
            final JsonObject context = target.getAsJsonObject("context");
            assertTrue(context.get("targetRequired").getAsBoolean(), context.toString());
            assertTrue(context.get("optionalOnResolve").getAsBoolean(), context.toString());
            assertEquals(context.get("optionalDeciderSeat").getAsInt(), 1, context.toString());
            assertHostAdmits(target);
        }
    }

    /** A mandatory trigger's target request has no optionalOnResolve (Swords to Plowshares: a spell, none either). */
    @Test(timeOut = 900000)
    public void aMandatoryTargetHasNoOptionalOnResolve() throws Exception {
        final Card[] swords = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Plains", seat);
            swords[0] = addCardToZone("Swords to Plowshares", seat, ZoneType.Hand);
            addCard("Grizzly Bears", foe);
        }, null, null)) {
            final JsonObject priority = h.request();
            h.answer(priority, "selectCard", "card:" + swords[0].getId());
            final JsonObject target = h.request();
            assertEquals(target.get("kind").getAsString(), "target", target.toString());
            assertFalse(target.getAsJsonObject("context").has("optionalOnResolve"), target.toString());
        }
    }

    /** Fiend Artisan, {X}{G}, {T}, sacrifice another creature: three Forests pay X up to 2. */
    @Test(timeOut = 900000)
    public void fiendArtisansXRequestGivesTheManaMax() throws Exception {
        final Card[] artisan = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            for (int i = 0; i < 3; i++) {
                addCard("Forest", seat);
            }
            addCard("Grizzly Bears", seat).setSickness(false);
            artisan[0] = addCard("Fiend Artisan", seat);
            artisan[0].setSickness(false);
        }, null, null)) {
            JsonObject request = h.request();
            assertEquals(request.get("kind").getAsString(), "priority", request.toString());
            h.answer(request, "selectCard", "card:" + artisan[0].getId());
            request = h.request();
            assertEquals(request.get("inputClass").getAsString(), "modal:announceManaX", request.toString());
            JsonObject x = null;
            for (JsonElement c : request.getAsJsonArray("controls")) {
                if ("mana-x".equals(c.getAsJsonObject().get("controlId").getAsString())) {
                    x = c.getAsJsonObject();
                }
            }
            assertNotNull(x, request.toString());
            final JsonObject value = x.getAsJsonObject("value");
            assertEquals(value.get("affordability").getAsString(), "unknown", "the sacrifice is a non-mana part");
            assertEquals(x.get("max").getAsInt(), Integer.MAX_VALUE, "max is left as it is");
            assertEquals(value.get("manaMax").getAsInt(), 2, value.toString());
            for (Card c : h.seat.getCardsIn(ZoneType.Battlefield)) {
                assertFalse(c.isTapped(), "nothing tapped by the hint: " + c);
            }
        }
    }

    /** The hint is read-only: no randomness drawn, nothing tapped, no floating mana, the ability's X untouched. */
    @Test
    public void manaMaxDrawsNoRandomnessAndChangesNothing() throws Exception {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        for (int i = 0; i < 4; i++) {
            addCard("Forest", p);
        }
        addCard("Sol Ring", p);
        addCard("Grizzly Bears", p).setSickness(false);
        final Card artisan = addCard("Fiend Artisan", p);
        artisan.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        SpellAbility ability = null;
        for (SpellAbility sa : artisan.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.costHasManaX()) {
                ability = sa;
            }
        }
        assertNotNull(ability);
        ability.setActivatingPlayer(p);
        final java.lang.reflect.Field seed = java.util.Random.class.getDeclaredField("seed");
        seed.setAccessible(true);
        final long before = ((java.util.concurrent.atomic.AtomicLong) seed.get(MyRandom.getRandom())).get();
        final Integer xBefore = ability.getXManaCostPaid();
        final Integer max = HumanManaX.manaMax(p, ability, Integer.MAX_VALUE);
        assertEquals(max, Integer.valueOf(5), "4 Forests and Sol Ring: {G} and X = 5");
        assertEquals(((java.util.concurrent.atomic.AtomicLong) seed.get(MyRandom.getRandom())).get(), before,
                "no randomness drawn");
        assertEquals(ability.getXManaCostPaid(), xBefore);
        assertTrue(p.getManaPool().isEmpty());
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            assertFalse(c.isTapped(), c.toString());
        }
        // the cap: never past the offered max
        assertEquals(HumanManaX.manaMax(p, ability, 3), Integer.valueOf(3));
    }

    /** Mana Leak at the seat's spell: the "unless" payment's cancel declines (context.optionalPayment). */
    @Test(timeOut = 900000)
    public void anUnlessPaymentSaysCancelDeclines() throws Exception {
        final Card[] leak = new Card[1];
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            for (int i = 0; i < 3; i++) {
                addCard("Island", seat);
            }
            spell[0] = addCardToZone("Divination", seat, ZoneType.Hand);
            final SpellAbility own = spell[0].getFirstSpellAbility();
            own.setActivatingPlayer(seat);
            seat.getGame().getStackZone().add(spell[0]);
            seat.getGame().getStack().add(own);
            leak[0] = addCardToZone("Mana Leak", foe, ZoneType.Hand);
            seat.getGame().getStackZone().add(leak[0]);
        }, null, self -> {
            final SpellAbility sa = leak[0].getFirstSpellAbility();
            sa.setActivatingPlayer(self.foe);
            sa.getTargets().add(self.game.getStack().peekAbility());
            AbilityUtils.resolve(sa);
        })) {
            JsonObject request = h.request();
            for (int guard = 0; guard < 3 && !"mana".equals(request.get("kind").getAsString()); guard++) {
                // "Do you want to pay {3}?": yes
                String yes = null;
                for (JsonElement c : request.getAsJsonArray("controls")) {
                    final JsonObject control = c.getAsJsonObject();
                    if ("confirm".equals(control.get("type").getAsString()) && control.has("value")
                            && control.get("value").getAsBoolean()
                            || "ok".equals(control.get("type").getAsString())) {
                        yes = control.get("controlId").getAsString();
                        if ("confirm".equals(control.get("type").getAsString())) {
                            final JsonObject action = new JsonObject();
                            action.addProperty("type", "confirm");
                            action.addProperty("controlId", yes);
                            action.addProperty("confirm", true);
                            h.send(request, action);
                        } else {
                            h.answer(request, "ok", yes);
                        }
                        break;
                    }
                }
                assertNotNull(yes, request.toString());
                request = h.request();
            }
            assertEquals(request.get("kind").getAsString(), "mana", request.toString());
            assertTrue(request.getAsJsonObject("context").get("optionalPayment").getAsBoolean(), request.toString());
            assertHostAdmits(request);
            assertNoEngineNames(request, h.seat, h.foe);
        }
    }

    /** A cast's own payment: cancel backs out of the cast, so no optionalPayment. */
    @Test(timeOut = 900000)
    public void aCastsPaymentIsNotOptional() throws Exception {
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            for (int i = 0; i < 3; i++) {
                addCard("Island", seat);
            }
            spell[0] = addCardToZone("Divination", seat, ZoneType.Hand);
        }, null, null)) {
            final JsonObject priority = h.request();
            h.answer(priority, "selectCard", "card:" + spell[0].getId());
            final JsonObject mana = h.request();
            assertEquals(mana.get("kind").getAsString(), "mana", mana.toString());
            assertFalse(mana.has("context") && mana.getAsJsonObject("context").has("optionalPayment"), mana.toString());
        }
    }
}
