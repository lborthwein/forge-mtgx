package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;
import forge.player.HumanManaAffordability.Proof;

/**
 * afford-amount-1122: the castable glow on the owner's host game of 2026-11-22 (report 2026-11-22T05-54-09, trunk-fd4432298,
 * request r36): "When stacked coil is in play with zero counters, the UI is suggesting I can cast arbitrarily expensive
 * cards from my hand". Turn 3, the owner's main 1: Swamp untapped, Island tapped, Everflowing Chalice untapped with no
 * charge counter. Glen Elendra Archmage ({3}{U}) and The Scarab God ({3}{U}{B}) came with {@code payable: true,
 * payableProof: "unknown"}: lit, with {B} the only mana there was.
 *
 * <p>Why: the Chalice's ability is {@code Amount$ X} with {@code X = Count$CardCounters.CHARGE}. The payable proof gave up
 * ({@code unknown("source-amount")}) on any amount that is not a number, before its color screen.</p>
 *
 * <p>Now a count of the source's own counters is read exactly (none: the source adds no mana), and any other amount counts
 * as the source's colors without limit, so it can never hide a missing color. The offer ({@code assess}, so
 * {@code mayAfford}) is the live jar's: a spell the proof darkens stays clickable, and tapping a 0-counter source for
 * nothing stays offered. As in {@link HumanManaAffordabilityUntapReachTest}, every card that must not glow also asserts
 * that Forge's own payer ({@link ComputerUtilMana#canPayManaCost}) cannot pay it, and every card that glows that it can.
 * With {@code -Dforge.afford.baselineJar=<live jar>} every offer is checked against that jar's {@code assess}.</p>
 *
 * <p>The human view: the encoder lists {@code producedMana: ['C']} for the 0-counter Chalice ({@code canProduceColorMana}
 * reads colors, not amounts). The browser's view leaves it out and keeps {@code producedManaKnown: true}; the encoder's
 * own output, the pilot's observation, is unchanged.</p>
 */
public class HumanManaAffordabilityAmountTest extends AITest {

    private Game game;
    private Player me;
    private Player foe;

    private void board() {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        foe = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private Card mine(final String name, final boolean tapped) {
        final Card card = addCard(name, me);
        card.setTapped(tapped);
        card.setSickness(false);
        return card;
    }

    private Card theirs(final String name, final boolean tapped) {
        final Card card = addCard(name, foe);
        card.setTapped(tapped);
        return card;
    }

    private Card hand(final String name) {
        return addCardToZone(name, me, ZoneType.Hand);
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    private List<SpellAbility> offered(final Card card) {
        return card.getAllPossibleAbilities(me, true).stream()
                .filter(a -> InteractiveGuiGame.passesCastRestrictions(me, a))
                .filter(a -> HumanManaAffordability.mayAfford(me, a)).toList();
    }

    private Boolean flag(final Card card) {
        final List<SpellAbility> offered = offered(card);
        return offered.isEmpty() ? null : InteractiveGuiGame.controlPayable(me, offered);
    }

    private List<SpellAbility> plays(final Card card) {
        return offered(card).stream().filter(a -> !a.isManaAbility()).toList();
    }

    private SpellAbility spell(final Card card) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    private boolean forgePays(final SpellAbility sa) {
        return ComputerUtilMana.canPayManaCost(sa, me, 0, false);
    }

    // ------------------------------------------------------------------ the offer is the base jar's

    private void sameOffer(final Card card) {
        for (SpellAbility sa : card.getAllPossibleAbilities(me, true)) {
            final Method baseline = baselineAssess();
            if (baseline == null) return;
            final String live;
            try {
                live = String.valueOf(baseline.invoke(null, me, sa));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("baseline assess failed", e);
            }
            assertEquals(HumanManaAffordability.assess(me, sa).toString(), live, "the offer of " + sa + " is the base jar's");
        }
    }

    private static Method baselineMethod;

    /** The base jar's {@code HumanManaAffordability.assess}, loaded child-first; every other class is this tree's. */
    private static synchronized Method baselineAssess() {
        final String jar = System.getProperty("forge.afford.baselineJar");
        if (jar == null || jar.isBlank()) return null;
        if (baselineMethod == null) {
            try {
                final ClassLoader parent = HumanManaAffordability.class.getClassLoader();
                final URLClassLoader loader = new URLClassLoader(new URL[] {new File(jar).toURI().toURL()}, parent) {
                    @Override
                    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                        if (!name.startsWith("forge.player.HumanManaAffordability")) return super.loadClass(name, resolve);
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> c = findLoadedClass(name);
                            if (c == null) c = findClass(name);
                            if (resolve) resolveClass(c);
                            return c;
                        }
                    }
                };
                baselineMethod = loader.loadClass("forge.player.HumanManaAffordability")
                        .getMethod("assess", Player.class, SpellAbility.class);
            } catch (ReflectiveOperationException | java.io.IOException e) {
                throw new AssertionError("cannot load the baseline jar " + jar, e);
            }
        }
        return baselineMethod;
    }

    // ------------------------------------------------------------------ verdicts

    /** Dark: offered (or not) exactly as before, payable false, the proof UNAFFORDABLE, and Forge's payer cannot pay. */
    private void noGlow(final Card card) {
        sameOffer(card);
        assertNotEquals(flag(card), Boolean.TRUE, card.getName() + " must not glow");
        assertFalse(plays(card).isEmpty(), card.getName() + " stays offered (the offer is unchanged): " + offered(card));
        for (SpellAbility sa : plays(card)) {
            assertEquals(HumanManaAffordability.assessPayable(me, sa), Assessment.PROVEN_UNAFFORDABLE,
                    card.getName() + ": the payable proof of " + sa);
            assertEquals(HumanManaAffordability.proof(me, sa), Proof.UNAFFORDABLE, card.getName() + ": the wire's proof");
            assertFalse(forgePays(sa), card.getName() + ": Forge's payer must not pay " + sa + " either");
        }
    }

    /** Lit: offered with payable true, the proof not UNAFFORDABLE, and Forge's payer pays it. */
    private void glows(final Card card) {
        sameOffer(card);
        assertEquals(flag(card), Boolean.TRUE, card.getName() + " is payable and offered");
        assertNotEquals(HumanManaAffordability.proof(me, spell(card)), Proof.UNAFFORDABLE, card.getName() + ": the wire's proof");
        assertTrue(plays(card).stream().anyMatch(this::forgePays), card.getName() + ": Forge's payer pays it");
    }

    /** The report's board (state.json, gameTape.events[49]); the Chalice with {@code charge} counters. */
    private Card reportedBoard(final int charge) {
        board();
        mine("Swamp", false);
        mine("Island", true);
        final Card chalice = mine("Everflowing Chalice", false);
        chalice.setCounters(CounterEnumType.CHARGE, charge);
        addCardToZone("Atraxa, Grand Unifier", me, ZoneType.Graveyard);
        addCardToZone("Entomb", me, ZoneType.Graveyard);
        addCardToZone("Mystical Tutor", me, ZoneType.Graveyard);
        hand("Swamp");
        theirs("Forest", true);
        theirs("Elvish Mystic", false);
        me.setLandsPlayedThisTurn(1);
        return chalice;
    }

    // ------------------------------------------------------------------ the owner's board

    /**
     * The report's hand: neither the Archmage nor The Scarab God glows; both stay offered. The Chalice's own mana ability is
     * still offered (tapping it for nothing is legal).
     */
    @Test
    public void owner1122_zeroCounterChalice_archmageAndScarabGodDark() {
        final Card chalice = reportedBoard(0);
        final Card archmage = hand("Glen Elendra Archmage");
        final Card god = hand("The Scarab God");
        settle();
        assertEquals(chalice.getCounters(CounterEnumType.CHARGE), 0, "no charge counter, as reported");

        noGlow(archmage);
        noGlow(god);
        sameOffer(chalice);
        assertTrue(offered(chalice).stream().anyMatch(SpellAbility::isManaAbility),
                "the Chalice's mana ability stays offered: " + offered(chalice));
    }

    /** The cause: the counter count is read exactly. With none the Chalice adds nothing, so {4} is unpayable. */
    @Test
    public void zeroCounters_colorlessFourDark_blackOneLit() {
        reportedBoard(0);
        final Card solemn = hand("Solemn Simulacrum");
        final Card ritual = hand("Dark Ritual");
        settle();

        noGlow(solemn);
        glows(ritual);
        assertEquals(HumanManaAffordability.proof(me, spell(ritual)), Proof.AFFORDABLE, "the Swamp surely pays {B}");
    }

    /** Two charge counters make {C}{C}: {2}{B} is payable (and provably so), {4} is not (2 + 1 < 4). */
    @Test
    public void twoCounters_twoBlackLit_colorlessFourDark() {
        reportedBoard(2);
        final Card rager = hand("Phyrexian Rager");
        final Card solemn = hand("Solemn Simulacrum");
        settle();

        glows(rager);
        assertEquals(HumanManaAffordability.proof(me, spell(rager)), Proof.AFFORDABLE,
                "the Swamp's {B} and the Chalice's {C}{C} surely pay {2}{B}");
        noGlow(solemn);
    }

    // ------------------------------------------------------------------ an amount the proof cannot bound (rule 2)

    /**
     * Gaea's Cradle ({@code Count$Valid Creature.YouCtrl}) is not read exactly: it makes green without limit. So a blue
     * spell is still unpayable, and a green one is not proved unpayable.
     */
    @Test
    public void variableCount_missingColorDark_coveredColorLit() {
        board();
        mine("Gaea's Cradle", false);
        mine("Grizzly Bears", false);
        mine("Grizzly Bears", false);
        final Card brainstorm = hand("Brainstorm");
        final Card library = hand("Sylvan Library");
        settle();

        noGlow(brainstorm);
        glows(library);
    }

    /** An unbounded amount never hides a missing color: with the Cradle and a Swamp, {U}{B} has no blue. */
    @Test
    public void variableCount_besideAnotherSource_missingColorDark() {
        board();
        mine("Gaea's Cradle", false);
        mine("Grizzly Bears", false);
        mine("Swamp", false);
        final Card god = hand("The Scarab God");
        final Card whisper = hand("Night's Whisper");
        settle();

        noGlow(god);
        glows(whisper);
    }

    // ------------------------------------------------------------------ a count that may grow first

    /**
     * Coretapper's "{T}: Put a charge counter on target artifact" and "Sacrifice: Put two charge counters on target
     * artifact", usable now, can grow the Chalice's count before the payment: the count is then not exact, and {1}{B} is
     * not proved unpayable. Without Coretapper it is. (Forge's payer activates no such ability, as it activates no untap.)
     */
    @Test
    public void counterGrowthUsableNow_countIsNotExact() {
        reportedBoard(0);
        final Card whisper = hand("Night's Whisper");
        settle();
        noGlow(whisper);

        final Card coretapper = mine("Coretapper", false);
        settle();
        sameOffer(whisper);
        assertEquals(flag(whisper), Boolean.TRUE, "Night's Whisper is lit: Coretapper may put a counter first");
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(whisper)), Assessment.PROVEN_UNAFFORDABLE);

        // Tapped, it can still be sacrificed for two counters: still not exact.
        coretapper.setTapped(true);
        settle();
        assertEquals(flag(whisper), Boolean.TRUE, "the sacrifice ability needs no tap");
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(whisper)), Assessment.PROVEN_UNAFFORDABLE);
    }

    // ------------------------------------------------------------------ the human view

    private static JsonObject card(final JsonObject view, final int fid) {
        for (JsonElement p : view.getAsJsonArray("players")) {
            for (JsonElement c : p.getAsJsonObject().getAsJsonArray("battlefield")) {
                if (c.getAsJsonObject().get("fid").getAsInt() == fid) return c.getAsJsonObject();
            }
        }
        throw new AssertionError("card " + fid + " is not on the battlefield of " + view);
    }

    /**
     * The 0-counter Chalice: the browser's view (both seats', and an AI act view built as InteractiveGuiGame builds it)
     * publishes no {@code producedMana} and keeps {@code producedManaKnown: true}; StateEncoder's own encode still lists
     * {@code ['C']}. The Swamp is unchanged.
     */
    @Test
    public void view_zeroCounterSourcePublishesNoProducedMana() {
        final Card chalice = reportedBoard(0);
        final Card swamp = me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Swamp")).findFirst().orElseThrow();
        settle();

        final JsonObject encoded = card(StateEncoder.encode(game, me), chalice.getId());
        assertEquals(encoded.get("producedMana").toString(), "[\"C\"]", "the encoder (the pilot's observation) is unchanged");
        assertTrue(encoded.get("producedManaKnown").getAsBoolean());

        for (Player viewer : new Player[] {me, foe}) {
            final JsonObject view = InteractiveState.seatView(game, viewer, StateEncoder.playerIndex(game, viewer));
            final JsonObject shown = card(view, chalice.getId());
            assertFalse(shown.has("producedMana"), "a source that makes nothing now lists no mana: " + shown);
            assertTrue(shown.get("producedManaKnown").getAsBoolean(), "\"produces nothing\" is a statement: " + shown);
            assertEquals(card(view, swamp.getId()).get("producedMana").toString(), "[\"B\"]");
        }
        final JsonObject aiAct = StateEncoder.withoutManaWrites(new HashSet<>(),
                () -> InteractiveState.seatView(game, me, StateEncoder.playerIndex(game, me)));
        assertFalse(card(aiAct, chalice.getId()).has("producedMana"), "an AI act view is the same view");

        // And the encoder after the view: the view's copy never wrote back.
        assertEquals(card(StateEncoder.encode(game, me), chalice.getId()).get("producedMana").toString(), "[\"C\"]");
    }

    /** Two charge counters: the view lists {@code ['C']}, as the encoder does. */
    @Test
    public void view_twoCounterSourcePublishesItsColor() {
        final Card chalice = reportedBoard(2);
        settle();

        final JsonObject shown = card(InteractiveState.seatView(game, me, StateEncoder.playerIndex(game, me)), chalice.getId());
        assertEquals(shown.get("producedMana").toString(), "[\"C\"]", "a source that makes mana lists it: " + shown);
        assertTrue(shown.get("producedManaKnown").getAsBoolean());
        assertEquals(card(StateEncoder.encode(game, me), chalice.getId()).get("producedMana").toString(), "[\"C\"]");
    }

    /** A source whose amount is not read exactly (the Cradle) keeps its colors in the view, whatever it would make. */
    @Test
    public void view_unboundedSourceKeepsItsColors() {
        board();
        final Card cradle = mine("Gaea's Cradle", false);
        settle();

        final JsonObject shown = card(InteractiveState.seatView(game, me, StateEncoder.playerIndex(game, me)), cradle.getId());
        assertEquals(shown.get("producedMana").toString(), "[\"G\"]", "an amount the view cannot read keeps the colors: " + shown);
    }
}
