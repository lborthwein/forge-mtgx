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
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
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

    // ------------------------------------------------------------------ review S1 (Astra): a count that may grow first

    /** An effect that puts a charge counter on each artifact its controller controls. */
    private static final String CHARGE_MY_ARTIFACTS =
            "DB$ PutCounter | Defined$ Valid Artifact.YouCtrl | CounterType$ CHARGE | CounterNum$ 1";

    /**
     * Lit only because a counter may be put first: offered as before, payable true, the proof UNKNOWN (neither proved
     * unpayable nor provably payable), and Forge's payer, which puts no counter first, cannot pay it yet.
     */
    private void litBeforeGrowth(final Card card) {
        sameOffer(card);
        assertEquals(flag(card), Boolean.TRUE, card.getName() + " is lit: a counter may be put on a source first");
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(card)), Assessment.PROVEN_UNAFFORDABLE,
                card.getName() + ": the payable proof");
        assertEquals(HumanManaAffordability.proof(me, spell(card)), Proof.UNKNOWN, card.getName() + ": the wire's proof");
        assertFalse(forgePays(spell(card)), card.getName() + ": Forge's payer puts no counter first");
    }

    /** Taps {@code source} for mana with Forge's own activation (no stack: a mana ability). */
    private void tapForMana(final Card source) {
        final SpellAbility mana = source.getManaAbilities().get(0);
        mana.setActivatingPlayer(me);
        assertTrue(ComputerUtil.playNoStack(me, mana, game, false), "Forge activates " + mana);
    }

    /** Opponent's turn; Rotating Fireplace with one time counter ({C}); Spatial Contortion ({1}{C}, an instant) in hand. */
    private Card fireplaceBoard() {
        board();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, foe);
        final Card fireplace = mine("Rotating Fireplace", false);
        fireplace.setCounters(CounterEnumType.TIME, 1);
        return fireplace;
    }

    /**
     * Review S1-1 (Astra's repro): Jhoira's Timebug's "{T}: ... put another time counter on it" is an AddOrRemoveCounter
     * ability, usable now, that grows the Fireplace before the payment. The count is then not exact: Spatial Contortion
     * is lit. Forge is the judge: the Timebug's ability resolves and Forge's payer then pays {1}{C}. Without the Timebug
     * it is dark (the Fireplace's own Time Travel is sorcery speed).
     */
    @Test
    public void review_timebugAddOrRemoveCounter_countIsNotExact() {
        final Card fireplace = fireplaceBoard();
        final Card contortion = hand("Spatial Contortion");
        settle();
        noGlow(contortion);

        final Card bug = mine("Jhoira's Timebug", false);
        settle();
        litBeforeGrowth(contortion);

        final SpellAbility grow = bug.getSpellAbilities().stream()
                .filter(a -> a.getApi() == ApiType.AddOrRemoveCounter).findFirst().orElseThrow();
        grow.setActivatingPlayer(me);
        grow.getTargets().add(fireplace);
        assertTrue(ComputerUtil.playNoStack(me, grow, game, false), "Forge resolves the Timebug's ability");
        assertEquals(fireplace.getCounters(CounterEnumType.TIME), 2, "the Timebug added a second time counter");
        assertTrue(forgePays(spell(contortion)), "Forge's payer pays {1}{C} after the Timebug's ability");
    }

    /** The control: a tapped Timebug cannot act, so the count stays exact and Spatial Contortion stays dark. */
    @Test
    public void review_tappedTimebug_countStaysExact() {
        fireplaceBoard();
        mine("Jhoira's Timebug", true);
        final Card contortion = hand("Spatial Contortion");
        settle();
        noGlow(contortion);
    }

    /**
     * The control for triggers: Badgermole Cub's own "When this creature enters, earthbend 1" (a +1/+1 counter) acts
     * only when the Cub enters again, as a new object, so beside the 0-counter Chalice it leaves the count exact: {4}
     * stays dark. (The Cub's mana trigger is for creatures tapped for mana; neither the Swamp nor the Chalice is one.)
     */
    @Test
    public void review_ownEntryTrigger_countStaysExact() {
        reportedBoard(0);
        mine("Badgermole Cub", false);
        final Card solemn = hand("Solemn Simulacrum");
        settle();
        noGlow(solemn);
    }

    /** The Chalice with no counter and an untapped Island; Spatial Contortion ({1}{C}) in hand: {C} is missing. */
    private Card chaliceIslandBoard() {
        board();
        final Card chalice = mine("Everflowing Chalice", false);
        mine("Island", false);
        return chalice;
    }

    private Card myIsland() {
        return me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Island")).findFirst().orElseThrow();
    }

    /**
     * Review S1-1, a trigger: "Whenever this Island becomes tapped, put a charge counter on each artifact you control"
     * (a Taps trigger, not a mana trigger, so the proof's mana-hook screen does not see it). Tapping the Island for mana
     * during the payment sets it off. The count is not exact: lit. Forge is the judge: the tap adds {U} and a charge
     * counter, and its payer then pays {1}{C}.
     */
    @Test
    public void review_triggerPutsCounter_countIsNotExact() {
        final Card chalice = chaliceIslandBoard();
        final Card island = myIsland();
        final Card contortion = hand("Spatial Contortion");
        settle();
        noGlow(contortion);

        island.setSVar("TrigCharge", CHARGE_MY_ARTIFACTS);
        island.addTrigger(TriggerHandler.parseTrigger("Mode$ Taps | ValidCard$ Card.Self | Static$ True"
                + " | TriggerZones$ Battlefield | Execute$ TrigCharge | TriggerDescription$ Whenever CARDNAME becomes"
                + " tapped, put a charge counter on each artifact you control.", island, true));
        settle();
        game.getTriggerHandler().resetActiveTriggers();
        litBeforeGrowth(contortion);

        tapForMana(island);
        assertEquals(chalice.getCounters(CounterEnumType.CHARGE), 1, "the trigger put a charge counter on the Chalice");
        assertTrue(forgePays(spell(contortion)), "Forge's payer pays {1}{C} with the {U} and the Chalice's {C}");
    }

    /**
     * Review S1-1, a replacement: "If you would draw a card, put a charge counter on each artifact you control instead",
     * beside a free draw ability. The count is not exact: lit. Forge is the judge: the draw resolves as a charge counter
     * and its payer then pays {1}{C}. Without the replacement the draw ability puts no counter: dark.
     */
    @Test
    public void review_replacementPutsCounter_countIsNotExact() {
        final Card chalice = chaliceIslandBoard();
        addCardToZone("Island", me, ZoneType.Library);
        final Card bears = mine("Grizzly Bears", false);
        final SpellAbility draw = AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | NumCards$ 1 | SpellDescription$ Draw a card.", bears);
        bears.addSpellAbility(draw);
        final Card contortion = hand("Spatial Contortion");
        settle();
        noGlow(contortion);

        bears.setSVar("RepCharge", CHARGE_MY_ARTIFACTS);
        bears.addReplacementEffect(ReplacementHandler.parseReplacement("Event$ Draw | ActiveZones$ Battlefield"
                + " | ValidPlayer$ You | ReplaceWith$ RepCharge | Description$ If you would draw a card, put a charge"
                + " counter on each artifact you control instead.", bears, true));
        settle();
        litBeforeGrowth(contortion);

        draw.setActivatingPlayer(me);
        assertTrue(ComputerUtil.playNoStack(me, draw, game, false), "Forge resolves the draw");
        assertEquals(chalice.getCounters(CounterEnumType.CHARGE), 1, "the draw became a charge counter on the Chalice");
        assertTrue(forgePays(spell(contortion)), "Forge's payer pays {1}{C} with the Island and the Chalice");
    }

    /**
     * Review S1-1, a static: "Lands you control have 'Whenever this land becomes tapped, put a charge counter on each
     * artifact you control'". The count is not exact: lit. Forge is the judge: the Island has the granted trigger,
     * tapping it adds {U} and a charge counter, and its payer then pays {1}{C}.
     */
    @Test
    public void review_staticGrantsCounterTrigger_countIsNotExact() {
        final Card chalice = chaliceIslandBoard();
        final Card island = myIsland();
        final Card bears = mine("Grizzly Bears", false);
        final Card contortion = hand("Spatial Contortion");
        settle();
        noGlow(contortion);

        bears.setSVar("TapTrig", "Mode$ Taps | ValidCard$ Card.Self | Static$ True | Execute$ TrigCharge"
                + " | TriggerDescription$ Whenever CARDNAME becomes tapped, put a charge counter on each artifact you control.");
        bears.setSVar("TrigCharge", CHARGE_MY_ARTIFACTS);
        bears.addStaticAbility("Mode$ Continuous | Affected$ Land.YouCtrl | AddTrigger$ TapTrig | Description$ Lands"
                + " you control have \"Whenever this land becomes tapped, put a charge counter on each artifact you control.\"");
        settle();
        game.getTriggerHandler().resetActiveTriggers();
        assertTrue(island.getTriggers().stream().anyMatch(t -> t.getMode() == TriggerType.Taps), "the Island has the granted trigger");
        litBeforeGrowth(contortion);

        tapForMana(island);
        assertEquals(chalice.getCounters(CounterEnumType.CHARGE), 1, "the granted trigger put a charge counter on the Chalice");
        assertTrue(forgePays(spell(contortion)), "Forge's payer pays {1}{C} with the {U} and the Chalice's {C}");
    }

    // ------------------------------------------------------------------ review S1 (Astra): zero printed, not zero made

    /** The card's {@code producedMana} in each seat's view and in an AI act view (null when it lists none). */
    private String[] shownProduced(final Card card) {
        final String[] shown = new String[3];
        int i = 0;
        for (Player viewer : new Player[] {me, foe}) {
            final JsonObject c = card(InteractiveState.seatView(game, viewer, StateEncoder.playerIndex(game, viewer)), card.getId());
            assertTrue(c.get("producedManaKnown").getAsBoolean(), "producedManaKnown stays: " + c);
            shown[i++] = c.has("producedMana") ? c.get("producedMana").toString() : null;
        }
        final JsonObject aiAct = StateEncoder.withoutManaWrites(new HashSet<>(),
                () -> InteractiveState.seatView(game, me, StateEncoder.playerIndex(game, me)));
        final JsonObject c = card(aiAct, card.getId());
        shown[i] = c.has("producedMana") ? c.get("producedMana").toString() : null;
        return shown;
    }

    private String encoded(final Card card) {
        return card(StateEncoder.encode(game, me), card.getId()).get("producedMana").toString();
    }

    /**
     * Review S1-2 (Astra's repro): Gyre Sage with no +1/+1 counter beside Badgermole Cub ("Whenever you tap a creature
     * for mana, add an additional {G}"). The Sage's activation makes {G}, so every view keeps the encoder's
     * {@code producedMana}. Forge is the judge: the activation adds {G} and its payer spends it on Llanowar Elves. The
     * Chalice beside them (not a creature: the Cub's trigger does not apply) still lists nothing.
     */
    @Test
    public void review_view_manaTriggerOnZeroSource_keepsProducedMana() {
        board();
        mine("Badgermole Cub", false);
        final Card sage = mine("Gyre Sage", false);
        final Card chalice = mine("Everflowing Chalice", false);
        final Card elves = hand("Llanowar Elves");
        settle();
        assertEquals(sage.getCounters(CounterEnumType.P1P1), 0);

        assertEquals(encoded(sage), "[\"G\"]", "the encoder (the pilot's observation) is unchanged");
        for (String shown : shownProduced(sage)) assertEquals(shown, "[\"G\"]", "the Sage makes {G} with the Cub");
        for (String shown : shownProduced(chalice)) assertEquals(shown, null, "the Chalice still makes nothing");
        assertEquals(encoded(chalice), "[\"C\"]");

        tapForMana(sage);
        assertEquals(me.getManaPool().totalMana(), 1, "Forge: the Cub's trigger adds {G} to the empty activation");
        assertTrue(forgePays(spell(elves)), "Forge's payer pays Llanowar Elves with that {G}");
    }

    /**
     * Review S1-2 (Astra's repro): City of Shadows with no storage counter under Contamination ("If a land is tapped for
     * mana, it produces {B} instead of any other type and amount"). The activation makes {B}, so every view keeps the
     * encoder's {@code producedMana}. Forge is the judge: it adds one mana and its payer pays Dark Ritual. The Chalice
     * (not a land) still lists nothing.
     */
    @Test
    public void review_view_manaReplacementOnZeroSource_keepsProducedMana() {
        board();
        final Card city = mine("City of Shadows", false);
        mine("Contamination", false);
        final Card chalice = mine("Everflowing Chalice", false);
        final Card ritual = hand("Dark Ritual");
        settle();
        assertEquals(city.getCounters(CounterEnumType.STORAGE), 0);

        final String encodedCity = encoded(city);
        for (String shown : shownProduced(city)) assertEquals(shown, encodedCity, "City makes {B} under Contamination");
        for (String shown : shownProduced(chalice)) assertEquals(shown, null, "the Chalice still makes nothing");

        tapForMana(city);
        assertEquals(me.getManaPool().totalMana(), 1, "Forge: Contamination replaces the empty output with {B}");
        assertTrue(forgePays(spell(ritual)), "Forge's payer pays Dark Ritual with that {B}");
    }
}
