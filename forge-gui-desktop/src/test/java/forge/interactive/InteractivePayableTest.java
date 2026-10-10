package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gui.control.FControlGameEventHandler;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;
import forge.player.PlayerControllerHuman;
import forge.util.IdScope;
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
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * castable-1010: the human seat's priority controls carry {@code value.payable}, and the client
 * glows only payable cards (live reports 2026-10-10T06-33-30 and 06-36-16: "why is Kuzunoha listed
 * as castable right now when I have no mana up?").
 *
 * <p>The bridge already hid a card whose every way to play it was PROVEN_UNAFFORDABLE
 * ({@link HumanManaAffordability#assess}). But {@code CostAdjustment.presentationManaCost} gave up
 * (UNKNOWN) whenever any ReduceCost static with MinMana was in play or on the card, whether or not
 * it applied. Zirda, the Dawnwaker ("abilities you activate cost {2} less; not below one mana") did
 * that from the hand for Zirda itself (report 1), and from the battlefield for every spell and
 * ability (report 2: Portal to Phyrexia, Oust, Snap, Figure of Destiny).</p>
 *
 * <p>{@code flag} below is what the bridge publishes for a card: null when the card is not offered
 * at all (today's filter), else {@link InteractiveGuiGame#controlPayable} over the same abilities
 * the bridge offers.</p>
 */
public class InteractivePayableTest extends AITest {

    private Game game;
    private Player me;
    private Player foe;

    private void board() {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        foe = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private Card land(final String name, final boolean tapped) {
        final Card card = addCard(name, me);
        card.setTapped(tapped);
        return card;
    }

    private void lands(final boolean tapped, final String... names) {
        for (String name : names) {
            land(name, tapped);
        }
    }

    private Card hand(final String name) {
        return addCardToZone(name, me, ZoneType.Hand);
    }

    private Card permanent(final String name) {
        final Card card = addCard(name, me);
        card.setSickness(false);
        if (card.isPlaneswalker()) {
            card.setCounters(CounterEnumType.LOYALTY, Integer.parseInt(card.getCurrentState().getBaseLoyalty()));
        }
        return card;
    }

    private void floating(final byte color) {
        me.getManaPool().addMana(new Mana(color, land("Island", true), null, me));
    }

    /** The abilities the bridge offers on this card at priority (buildStatefulControls). */
    private List<SpellAbility> offered(final Card card) {
        return card.getAllPossibleAbilities(me, true).stream()
                .filter(a -> InteractiveGuiGame.passesCastRestrictions(me, a))
                .filter(a -> HumanManaAffordability.mayAfford(me, a)).toList();
    }

    /** Null: not offered (today's filter). Else the published payable flag. */
    private Boolean flag(final Card card) {
        final List<SpellAbility> offered = offered(card);
        return offered.isEmpty() ? null : InteractiveGuiGame.controlPayable(me, offered);
    }

    private SpellAbility spell(final Card card) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    // ------------------------------------------------------------------ the two reported boards

    /** Report 1 (turn 17): every land tapped, Zirda, Portal to Phyrexia and Oust in hand. */
    @Test
    public void zirdaInHandWithEveryLandTappedIsNotPayable() {
        board();
        lands(true, "Island", "Island", "Plains", "Plains", "Plains");
        permanent("Venser, Shaper Savant");
        final Card teferi = permanent("Teferi, Hero of Dominaria");
        permanent("Timeless Dragon");
        final Card zirda = hand("Zirda, the Dawnwaker");
        final Card portal = hand("Portal to Phyrexia");
        final Card oust = hand("Oust");
        settle();
        // The cause: Zirda's own MinMana reduction made its price unknown, so the old filter kept it.
        assertEquals(HumanManaAffordability.assess(me, spell(zirda)), Assessment.UNKNOWN);
        assertEquals(flag(zirda), Boolean.FALSE, "Zirda {1}{R/W}{R/W} with no mana is offered but not payable");
        assertNull(flag(portal), "Portal stays hidden, as today");
        assertNull(flag(oust), "Oust stays hidden, as today");
        assertEquals(flag(teferi), Boolean.TRUE, "a loyalty ability costs no mana");
    }

    /** Report 1's guard: three untapped Plains pay for Zirda. */
    @Test
    public void zirdaInHandIsPayableWithThreeOpenPlains() {
        board();
        lands(false, "Plains", "Plains", "Plains");
        final Card zirda = hand("Zirda, the Dawnwaker");
        settle();
        assertEquals(flag(zirda), Boolean.TRUE);
        board();
        lands(false, "Plains", "Plains");
        lands(true, "Plains");
        final Card short1 = hand("Zirda, the Dawnwaker");
        settle();
        assertEquals(flag(short1), Boolean.FALSE, "two open lands cannot pay three mana");
    }

    /** Report 2 (turn 20): Zirda on the battlefield, every land tapped, {U} floating. */
    @Test
    public void zirdaOnTheBattlefieldNoLongerHidesEveryPrice() {
        board();
        lands(true, "Island", "Island", "Plains", "Plains", "Plains");
        permanent("Zirda, the Dawnwaker");
        final Card figure = permanent("Figure of Destiny");
        final Card portal = hand("Portal to Phyrexia");
        final Card oust = hand("Oust");
        final Card snap = hand("Snap");
        floating(MagicColor.BLUE);
        settle();
        for (Card card : List.of(portal, oust, snap)) {
            assertEquals(HumanManaAffordability.assess(me, spell(card)), Assessment.UNKNOWN,
                    "the cause: Zirda's MinMana reduction made " + card.getName() + "'s price unknown");
            assertEquals(flag(card), Boolean.FALSE, card.getName() + " cannot be paid with one {U}");
        }
        assertEquals(flag(figure), Boolean.FALSE, "{R/W} cannot be paid with {U}; Zirda keeps one mana");
    }

    /** Report 2's guard: one more untapped Plains pays Oust, Snap (with the {U}) and Figure. */
    @Test
    public void zirdaOnTheBattlefieldWithAnOpenPlains() {
        board();
        lands(true, "Island", "Island", "Plains", "Plains");
        land("Plains", false);
        permanent("Zirda, the Dawnwaker");
        final Card figure = permanent("Figure of Destiny");
        final Card portal = hand("Portal to Phyrexia");
        final Card oust = hand("Oust");
        final Card snap = hand("Snap");
        floating(MagicColor.BLUE);
        settle();
        assertEquals(flag(oust), Boolean.TRUE);
        assertEquals(flag(snap), Boolean.TRUE);
        assertEquals(flag(figure), Boolean.TRUE);
        assertEquals(flag(portal), Boolean.FALSE);
    }

    /** Zirda's own {1},{T} ability: its reduction cannot take the cost below one mana. */
    @Test
    public void zirdaKeepsItsOwnAbilityAtOneMana() {
        board();
        lands(true, "Plains", "Plains");
        final Card zirda = permanent("Zirda, the Dawnwaker");
        settle();
        final SpellAbility ability = zirda.getSpellAbilities().stream()
                .filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        ability.setActivatingPlayer(me);
        assertEquals(HumanManaAffordability.assessPayable(me, ability), Assessment.PROVEN_UNAFFORDABLE);
    }

    /** Cost statics that do apply still count while Zirda's makes the old price unknown. */
    @Test
    public void raisesAndReductionsThatApplyStillCount() {
        board();
        land("Mountain", false);
        permanent("Zirda, the Dawnwaker");
        permanent("Thalia, Guardian of Thraben");
        final Card bolt = hand("Lightning Bolt");
        settle();
        assertEquals(HumanManaAffordability.assess(me, spell(bolt)), Assessment.UNKNOWN);
        assertEquals(flag(bolt), Boolean.FALSE, "Thalia makes Bolt cost {1}{R}: one Mountain is short");

        board();
        land("Mountain", false);
        permanent("Zirda, the Dawnwaker");
        permanent("Goblin Electromancer");
        final Card strike = hand("Lightning Strike");
        settle();
        assertEquals(flag(strike), Boolean.TRUE, "Electromancer makes Lightning Strike cost {R}");
    }

    // ------------------------------------------------------------------ alternative costs

    /** Force of Will's pitch: 1 life and another blue card, no mana. */
    @Test
    public void forceOfWillPitchIsPayableWithEveryLandTapped() {
        board();
        lands(true, "Island", "Island", "Island", "Island", "Island");
        final Card fow = hand("Force of Will");
        hand("Brainstorm");
        settle();
        assertEquals(flag(fow), Boolean.TRUE, "the pitch needs no mana: " + offered(fow));
    }

    /** Without another blue card the pitch is not offered and {3}{U}{U} is proven unaffordable. */
    @Test
    public void forceOfWillWithoutABlueCardIsNotOffered() {
        board();
        lands(true, "Island", "Island", "Island", "Island", "Island");
        final Card fow = hand("Force of Will");
        hand("Lightning Bolt");
        settle();
        assertNull(flag(fow));
    }

    /** Daze: return an Island you control, tapped or not. */
    @Test
    public void dazeReturnsATappedIsland() {
        board();
        lands(true, "Island");
        final Card daze = hand("Daze");
        settle();
        assertEquals(flag(daze), Boolean.TRUE);
        board();
        lands(true, "Plains");
        final Card noIsland = hand("Daze");
        settle();
        assertNull(flag(noIsland), "no Island to return and no {1}{U}");
    }

    /** Evoke: Mulldrifter's {2}{U} and Solitude's pitch. */
    @Test
    public void evokeCosts() {
        board();
        lands(false, "Island", "Plains", "Plains");
        final Card mulldrifter = hand("Mulldrifter");
        settle();
        assertEquals(flag(mulldrifter), Boolean.TRUE, "evoke {2}{U} with three open lands");
        final SpellAbility normal = spell(mulldrifter);
        assertEquals(HumanManaAffordability.assessPayable(me, normal), Assessment.PROVEN_UNAFFORDABLE,
                "{4}{U} with three lands");

        board();
        lands(true, "Plains", "Plains", "Plains", "Plains", "Plains");
        final Card solitude = hand("Solitude");
        hand("Savannah Lions");
        settle();
        assertEquals(flag(solitude), Boolean.TRUE, "evoke by exiling a white card needs no mana");
    }

    /** Flashback from the graveyard is priced at its flashback cost. */
    @Test
    public void flashback() {
        board();
        lands(false, "Plains", "Swamp");
        final Card souls = addCardToZone("Lingering Souls", me, ZoneType.Graveyard);
        settle();
        assertEquals(flag(souls), Boolean.TRUE, "{1}{B} with Plains and Swamp");
        board();
        lands(false, "Plains", "Plains");
        final Card noBlack = addCardToZone("Lingering Souls", me, ZoneType.Graveyard);
        settle();
        final SpellAbility flashback = noBlack.getAllPossibleAbilities(me, false).stream()
                .filter(SpellAbility::isFlashback).findFirst().orElseThrow();
        assertEquals(HumanManaAffordability.assessPayable(me, flashback), Assessment.PROVEN_UNAFFORDABLE,
                "{1}{B} with two Plains");
    }

    /** Phyrexian mana: 2 life per pip. */
    @Test
    public void phyrexianMana() {
        board();
        lands(true, "Island");
        final Card probe = hand("Gitaxian Probe");
        settle();
        assertEquals(flag(probe), Boolean.TRUE, "{U/P} with 20 life");
        me.setLife(1, null);
        assertEquals(HumanManaAffordability.assessPayable(me, spell(probe)), Assessment.PROVEN_UNAFFORDABLE,
                "{U/P} with 1 life and no blue");

        board();
        lands(true, "Plains");
        final Card dismember = hand("Dismember");
        settle();
        assertEquals(HumanManaAffordability.assessPayable(me, spell(dismember)), Assessment.PROVEN_UNAFFORDABLE,
                "the {1} needs mana");
        land("Plains", false);
        assertEquals(flag(dismember), Boolean.TRUE, "{1} from a Plains, both pips with life");
    }

    /** Convoke: each untapped creature pays {1} or one mana of its colour. */
    @Test
    public void convoke() {
        board();
        lands(true, "Mountain", "Mountain");
        final Card stoke = hand("Stoke the Flames");
        settle();
        assertEquals(HumanManaAffordability.assess(me, spell(stoke)), Assessment.UNKNOWN, "assess gives up on convoke");
        assertEquals(flag(stoke), Boolean.FALSE, "no creature and no mana");

        board();
        final Card stoke2 = hand("Stoke the Flames");
        for (int i = 0; i < 4; i++) addCard("Goblin Guide", me);   // summoning sick: convoke does not care
        settle();
        assertEquals(flag(stoke2), Boolean.TRUE, "four red creatures pay {2}{R}{R}");

        board();
        final Card stoke3 = hand("Stoke the Flames");
        for (int i = 0; i < 4; i++) addCard("Savannah Lions", me);
        settle();
        assertEquals(flag(stoke3), Boolean.FALSE, "white creatures cannot pay {R}{R}");
    }

    /** Delve: each other card in the graveyard pays {1}. */
    @Test
    public void delve() {
        board();
        land("Island", false);
        final Card cruise = hand("Treasure Cruise");
        for (int i = 0; i < 7; i++) addCardToZone("Grizzly Bears", me, ZoneType.Graveyard);
        settle();
        assertEquals(flag(cruise), Boolean.TRUE, "seven cards delved and an Island");

        board();
        land("Island", true);
        final Card tapped = hand("Treasure Cruise");
        for (int i = 0; i < 7; i++) addCardToZone("Grizzly Bears", me, ZoneType.Graveyard);
        settle();
        assertEquals(flag(tapped), Boolean.FALSE, "the {U} needs blue mana");

        board();
        land("Island", false);
        final Card six = hand("Treasure Cruise");
        for (int i = 0; i < 6; i++) addCardToZone("Grizzly Bears", me, ZoneType.Graveyard);
        settle();
        assertEquals(flag(six), Boolean.FALSE, "six cards and an Island are one short");
    }

    /** Improvise: each untapped artifact pays {1}. */
    @Test
    public void improvise() {
        board();
        lands(false, "Island", "Island");
        final Card engineer = hand("Reverse Engineer");
        for (int i = 0; i < 3; i++) permanent("Ornithopter");
        settle();
        assertEquals(flag(engineer), Boolean.TRUE, "{3} from three artifacts, {U}{U} from Islands");

        board();
        lands(false, "Island");
        final Card short1 = hand("Reverse Engineer");
        for (int i = 0; i < 3; i++) permanent("Ornithopter");
        settle();
        assertEquals(flag(short1), Boolean.FALSE, "artifacts cannot pay {U}");
    }

    /** X costs are priced at X = 0. */
    @Test
    public void xIsZero() {
        board();
        final Card ballista = hand("Walking Ballista");
        settle();
        assertEquals(flag(ballista), Boolean.TRUE, "{X}{X} at X = 0 costs nothing");
        board();
        land("Mountain", false);
        final Card banefire = hand("Banefire");
        settle();
        assertEquals(flag(banefire), Boolean.TRUE, "{X}{R} at X = 0 with a Mountain");
        board();
        permanent("Zirda, the Dawnwaker");
        land("Plains", false);
        final Card noRed = hand("Banefire");
        settle();
        assertEquals(flag(noRed), Boolean.FALSE, "{R} cannot come from a Plains");
    }

    /** A land play costs nothing; a summoning-sick mana creature pays nothing. */
    @Test
    public void landsAndSickSources() {
        board();
        lands(true, "Forest");
        final Card forest = hand("Forest");
        addCard("Llanowar Elves", me);          // entered this turn: its {T} cannot be used
        permanent("Zirda, the Dawnwaker");      // makes assess give up, so the new check decides
        final Card bears = hand("Grizzly Bears");
        settle();
        assertEquals(flag(forest), Boolean.TRUE);
        assertEquals(flag(bears), Boolean.FALSE);
    }

    // ------------------------------------------------------------------ the price is a lower bound

    /**
     * Soundness of the price: on every board, presentationManaCostLowerBound never asks for more than
     * Forge's own payment pricing (CostAdjustment.adjust on a disposable copy, as
     * HumanManaAffordabilityEngineSmoke does): no higher mana value, and no coloured shard Forge would
     * not ask for. The reference may choose (both seats here are AI); the query under test never does.
     */
    @Test
    public void thePriceNeverExceedsForgesOwn() {
        final List<List<String>> modifiers = List.of(List.of(), List.of("Zirda, the Dawnwaker"),
                List.of("Thalia, Guardian of Thraben"), List.of("Goblin Electromancer"), List.of("Trinisphere"),
                List.of("Helm of Awakening"), List.of("Sphere of Resistance"),
                List.of("Zirda, the Dawnwaker", "Thalia, Guardian of Thraben"),
                List.of("Zirda, the Dawnwaker", "Goblin Electromancer", "Trinisphere"),
                List.of("Goblin Electromancer", "Helm of Awakening", "Thalia, Guardian of Thraben"));
        final List<String> spells = List.of("Lightning Bolt", "Lightning Strike", "Counterspell", "Grizzly Bears",
                "Portal to Phyrexia", "Oust", "Snap", "Mulldrifter", "Force of Will", "Dismember", "Zirda, the Dawnwaker");
        int checked = 0;
        for (List<String> modifier : modifiers) {
            for (String name : spells) {
                board();
                for (String m : modifier) permanent(m);
                final Card card = hand(name);
                settle();
                for (SpellAbility sa : card.getAllPossibleAbilities(me, false)) {
                    if (!sa.isSpell()) continue;
                    final forge.card.mana.ManaCost lower = forge.game.cost.CostAdjustment.presentationManaCostLowerBound(sa);
                    if (lower == null) continue;
                    final SpellAbility copy = sa.copy(card, me, true);
                    final forge.game.cost.Cost raised = forge.game.cost.CostAdjustment.adjust(copy.getPayCosts(), copy, false);
                    final forge.game.mana.ManaCostBeingPaid paid = new forge.game.mana.ManaCostBeingPaid(
                            raised.getCostMana() == null ? forge.card.mana.ManaCost.ZERO : raised.getCostMana().getMana());
                    forge.game.cost.CostAdjustment.adjust(paid, copy, me, null, true, false);
                    final forge.card.mana.ManaCost actual = paid.toManaCost();
                    final String where = name + " (" + sa + ") with " + modifier + ": lower " + lower + " vs Forge " + actual;
                    assertTrue(lower.getCMC() <= actual.getCMC(), where);
                    final List<forge.card.mana.ManaCostShard> asked = new ArrayList<>();
                    actual.forEach(asked::add);
                    for (forge.card.mana.ManaCostShard shard : lower) {
                        assertTrue(asked.remove(shard), "a shard Forge does not ask for: " + where);
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked >= 100, "priced " + checked);
    }

    // ------------------------------------------------------------------ no side effects

    /**
     * The flag is a read. Computing it for every card leaves the game, the random stream, the id
     * counters and both AI seats' card memory as they were.
     */
    @Test
    public void computingTheFlagChangesNothing() {
        board();
        lands(true, "Island", "Island", "Plains");
        lands(false, "Mountain", "Swamp");
        permanent("Zirda, the Dawnwaker");
        permanent("Figure of Destiny");
        permanent("Thalia, Guardian of Thraben");
        permanent("Teferi, Hero of Dominaria");
        for (String name : List.of("Force of Will", "Brainstorm", "Daze", "Mulldrifter", "Stoke the Flames",
                "Treasure Cruise", "Walking Ballista", "Banefire", "Gitaxian Probe", "Dismember", "Forest")) {
            hand(name);
        }
        addCardToZone("Lingering Souls", me, ZoneType.Graveyard);
        addCard("Goblin Guide", foe);
        floating(MagicColor.BLUE);
        settle();
        // Warm every lazily built list the bridge already builds today (getAllPossibleAbilities).
        final List<List<SpellAbility>> lists = new ArrayList<>();
        for (Card card : game.getCardsInGame()) {
            if (card.getController() == me) lists.add(offered(card));
        }
        final String before = state(game);
        final List<String> memoryBefore = memory(game);
        final Random seeded = new Random(0x0B5E47EL);
        final Random was = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(0x0B5E47EL));
        IdScope.open();
        final Object counters = IdScope.capture();
        final String countersBefore = Arrays.toString((Object[]) counters);
        int published = 0;
        try {
            for (List<SpellAbility> offered : lists) {
                if (!offered.isEmpty()) {
                    InteractiveGuiGame.controlPayable(me, offered);
                    published++;
                }
            }
            assertEquals(MyRandom.getThreadRandom().nextLong(), seeded.nextLong(), "no random draw");
            assertEquals(Arrays.toString((Object[]) IdScope.capture()), countersBefore, "no id taken");
        } finally {
            IdScope.close();
            MyRandom.setThreadRandom(was);
        }
        assertTrue(published >= 10, "the board offers many controls: " + published);
        assertEquals(state(game), before, "no game state changed");
        assertEquals(memory(game), memoryBefore, "no AI card memory changed");
    }

    private static List<String> memory(final Game game) {
        final List<String> out = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            if (!p.getController().isAI()) continue;
            for (AiCardMemory.MemoryType<?> type : allMemoryTypes()) {
                final Set<?> set = AiCardMemory.getMemorySet(p, type);
                out.add(p.getId() + ":" + type + "=" + (set == null ? "null" : set.toString()));
            }
        }
        return out;
    }

    private static List<AiCardMemory.MemoryType<?>> allMemoryTypes() {
        final List<AiCardMemory.MemoryType<?>> types = new ArrayList<>();
        types.addAll(Arrays.asList(AiCardMemory.MemorySet.values()));
        types.addAll(Arrays.asList(AiCardMemory.MemorySetMana.values()));
        return types;
    }

    /** As HumanManaAffordabilityEngineSmoke.state: life, pool, zones, taps, every ability's paying state. */
    private static String state(final Game game) {
        final StringBuilder state = new StringBuilder();
        for (Player player : game.getPlayers()) {
            state.append(player.getLife()).append('/').append(player.getManaPool().totalMana());
            for (var mana : player.getManaPool()) state.append(':').append(System.identityHashCode(mana));
        }
        for (Card card : game.getCardsInGame()) {
            state.append('|').append(card.getId()).append(':').append(card.getZone()).append(':').append(card.isTapped())
                    .append(':').append(card.getCounters());
            for (SpellAbility ability : card.getAllSpellAbilities()) {
                state.append(';').append(ability.getId()).append(':').append(ability.getActivatingPlayer())
                        .append(':').append(ability.getPayCosts()).append(':').append(ability.getXManaCostPaid())
                        .append(':').append(ability.getPayingMana().size());
                if (ability.getManaPart() != null) state.append(':').append(ability.getManaPart().getExpressChoice());
            }
        }
        return state.toString();
    }

    // ------------------------------------------------------------------ on the wire

    private static final String SESSION = "payable-test";

    /** A seat with a human controller behind the bridge, publishing its priority request. */
    private final class Harness implements AutoCloseable {
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();

        Harness(final Consumer<Player> seatBoard) throws IOException {
            board();
            seatBoard.accept(me);
            settle();
            controller = new PlayerControllerHuman(game, me, me.getLobbyPlayer());
            final PipedReader fromClient = new PipedReader(client, 1 << 16);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(new BufferedReader(fromClient),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, me, controller);
            controller.setGui(gui);
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui,
                    new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            gui.startReader();
            final Thread forgeThread = new Thread(() -> {
                while (!closed.get() && !gui.hasFailed()) {
                    new InputPassPriority(controller).showAndWait();
                }
            }, "test Forge game thread");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject priorityRequest() throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final long left = deadline - System.currentTimeMillis();
                final JsonObject message = wire.poll(Math.max(0, left), TimeUnit.MILLISECONDS);
                assertNotNull(message, "the priority input was published");
                if ("error".equals(message.get("type").getAsString())) {
                    fail("the bridge failed: " + message);
                }
                if ("request".equals(message.get("type").getAsString())) {
                    return message;
                }
            }
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

    /** The control's value.payable, or null when the card has no control. */
    private static Boolean wirePayable(final JsonObject request, final Card card) {
        for (JsonElement element : request.getAsJsonArray("controls")) {
            final JsonObject control = element.getAsJsonObject();
            if (("card:" + card.getId()).equals(control.get("controlId").getAsString())) {
                final JsonObject value = control.getAsJsonObject("value");
                assertTrue(value.has("payable"), "every priority card control says payable: " + control);
                return value.get("payable").getAsBoolean();
            }
        }
        return null;
    }

    /** Report 1 on the wire: Zirda offered and not payable; Teferi payable; a pitch spell payable. */
    @Test(timeOut = 900000)
    public void theReportedBoardOnTheWire() throws Exception {
        final Card[] cards = new Card[5];
        try (Harness harness = new Harness(seat -> {
            lands(true, "Island", "Island", "Plains", "Plains", "Plains");
            cards[0] = permanent("Teferi, Hero of Dominaria");
            cards[1] = hand("Zirda, the Dawnwaker");
            cards[2] = hand("Oust");
            cards[3] = hand("Force of Will");
            cards[4] = hand("Brainstorm");
        })) {
            final JsonObject request = harness.priorityRequest();
            assertEquals(request.get("kind").getAsString(), "priority", request.toString());
            assertEquals(wirePayable(request, cards[0]), Boolean.TRUE, "Teferi: " + request);
            assertEquals(wirePayable(request, cards[1]), Boolean.FALSE, "Zirda: " + request);
            assertNull(wirePayable(request, cards[2]), "Oust stays hidden: " + request);
            assertEquals(wirePayable(request, cards[3]), Boolean.TRUE, "Force of Will's pitch: " + request);
            assertNull(wirePayable(request, cards[4]), "Brainstorm stays hidden: " + request);
        }
    }

    /** A spell castable only with an untapped land: payable with it, hidden without it. */
    @Test(timeOut = 900000)
    public void anOpenLandOnTheWire() throws Exception {
        final Card[] cards = new Card[2];
        try (Harness harness = new Harness(seat -> {
            land("Mountain", false);
            cards[0] = hand("Lightning Bolt");
            cards[1] = hand("Mountain");
        })) {
            final JsonObject request = harness.priorityRequest();
            assertEquals(wirePayable(request, cards[0]), Boolean.TRUE, "Bolt with an open Mountain: " + request);
            assertEquals(wirePayable(request, cards[1]), Boolean.TRUE, "a land play: " + request);
        }
    }
}
