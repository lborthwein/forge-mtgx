package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.gamemodes.puzzle.Puzzle;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;
import forge.player.HumanManaAffordability.Proof;

/**
 * afford-colour-unbounded-1119: the castable glow on the owner's host game of 2026-11-19 (report 2026-11-19T01-31-38,
 * jar 196b9b84, session c69c9b13, request r122). Turn 7, main 1, the owner's priority, a puzzle position: Grim Monolith,
 * Basalt Monolith and Island untapped; Scrubland, Plains and Library of Alexandria tapped; Zirda, the Dawnwaker (summoning
 * sick); {C}{C}{C} floating. Lurrus of the Dream-Den ({1}{W/B}{W/B}) glowed with no white or black mana to be had.
 *
 * <p>Why: Zirda makes Basalt Monolith's untap cost {1}, so the amount of mana is unbounded, and a cost static in play
 * refuses the untap bound ({@link HumanManaAffordability#untapWidening}). Only the color screen was left, and it counted
 * the colors of every permanent the player controls, tapped or not, on the ground that "an untap can reach it". These
 * untaps reach only their own Monoliths: the tapped Scrubland and Plains can be untapped by nothing, so they make no mana.</p>
 *
 * <p>Now the payable proof works out which permanents an untap usable now can reach. Each untapped source still counts
 * once, each reachable permanent's colors count without limit, and a tapped source nothing can untap counts not at all.
 * When some untap (or a trigger, a replacement, a copy, a spell) could reach more, the old reading stands.</p>
 *
 * <p>As in {@link HumanManaAffordabilityReflectedUntapTest}, each card that must not glow also asserts that Forge's own
 * payer ({@link ComputerUtilMana#canPayManaCost}) cannot pay, and each that glows that it can.</p>
 */
public class HumanManaAffordabilityUntapReachTest extends AITest {

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
        card.setSickness(false);
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

    /**
     * Review S1 (Astra, #250): the offer ({@link HumanManaAffordability#assess}, so {@code mayAfford}) is the live jar's,
     * exactly. With {@code -Dforge.afford.baselineJar=<a jar of the base tree>} every ability of the card is assessed by
     * that jar's HumanManaAffordability too, and the two must agree. Without the property this checks nothing; the
     * pinned offers below (and a run of this class on the live jar) still hold the line.
     */
    private void sameOffer(final Card card) {
        for (SpellAbility sa : card.getAllPossibleAbilities(me, true)) sameOffer(sa);
    }

    private void sameOffer(final SpellAbility sa) {
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

    private void noGlow(final Card card) {
        sameOffer(card);
        assertNotEquals(flag(card), Boolean.TRUE, card.getName() + " must not glow");
        for (SpellAbility sa : plays(card)) {
            assertEquals(HumanManaAffordability.assessPayable(me, sa), Assessment.PROVEN_UNAFFORDABLE,
                    card.getName() + ": the payable proof of " + sa);
            assertEquals(HumanManaAffordability.proof(me, sa), Proof.UNAFFORDABLE, card.getName() + ": the wire's proof");
            assertFalse(forgePays(sa), card.getName() + ": Forge's payer must not pay " + sa + " either");
        }
    }

    private void glows(final Card card) {
        sameOffer(card);
        assertEquals(flag(card), Boolean.TRUE, card.getName() + " is payable and offered");
        assertTrue(plays(card).stream().anyMatch(this::forgePays), card.getName() + ": Forge's payer pays it");
    }

    /** Not proved unpayable: offered with payable true, whatever Forge's payer (which activates no untap) says. */
    private void notUnaffordable(final Card card) {
        sameOffer(card);
        assertEquals(flag(card), Boolean.TRUE, card.getName() + " must stay lit");
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(card)), Assessment.PROVEN_UNAFFORDABLE,
                card.getName() + ": the payable proof");
        assertNotEquals(HumanManaAffordability.proof(me, spell(card)), Proof.UNAFFORDABLE, card.getName() + ": the wire's proof");
    }

    /** The report's battlefields, graveyard and pool (state.json, gameTape.events[168]); the hand is added per test. */
    private void reportedBoard(final boolean whiteSourcesTapped) {
        board();
        mine("Library of Alexandria", true);
        mine("Scrubland", whiteSourcesTapped);
        mine("Plains", whiteSourcesTapped);
        mine("Grim Monolith", false);
        final Card zirda = mine("Zirda, the Dawnwaker", false);
        zirda.setSickness(true);
        final Card basalt = mine("Basalt Monolith", false);
        mine("Island", false);
        theirs("Raffine's Tower", true);
        theirs("Island", true);
        theirs("Swamp", true);
        theirs("Mind Stone", true);
        addCardToZone("Flooded Strand", me, ZoneType.Graveyard);
        addCardToZone("Preordain", me, ZoneType.Graveyard);
        me.setLandsPlayedThisTurn(1);
        // The goal effect of the puzzle position (ask-km1827p-mull: win within 21 turns), as the host installs it.
        new Puzzle(Map.of("metadata", List.of("Name:ask-km1827p-mull", "Goal:Win", "Turns:21"), "state", List.of()))
                .addGoalEnforcement(game, me);
        final SpellAbility tap = basalt.getManaAbilities().iterator().next();
        for (int i = 0; i < 3; i++) me.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, tap.getManaPart(), me));
    }

    private void reportedBoard() {
        reportedBoard(true);
    }

    // ------------------------------------------------------------------ the owner's board

    /** The report's hand: Lurrus, Kiki-Jiki and Imperial Recruiter do not glow; Mana Vault does. */
    @Test
    public void owner1119_lurrusKikiRecruiterNoGlow_vaultGlows() {
        reportedBoard();
        final Card kiki = hand("Kiki-Jiki, Mirror Breaker");
        final Card recruiter = hand("Imperial Recruiter");
        final Card vault = hand("Mana Vault");
        final Card lurrus = hand("Lurrus of the Dream-Den");
        hand("Bazaar of Baghdad");
        settle();

        noGlow(lurrus);
        noGlow(kiki);
        noGlow(recruiter);
        glows(vault);
        assertNotEquals(HumanManaAffordability.proof(me, spell(vault)), Proof.UNAFFORDABLE);
        // Lurrus is still offered (assess, which decides the offer, is unchanged): a wrong proof costs a glow, never a play.
        assertFalse(offered(lurrus).isEmpty(), "Lurrus stays clickable");
    }

    /**
     * The cause, on either jar. Zirda's cost static refuses the untap bound, so only the color screen was left; and the same
     * board without the tapped Scrubland and Plains was already proved unpayable. Those two tapped lands, which no untap on
     * the board can reach, were all that kept Lurrus lit.
     */
    @Test
    public void owner1119_cause_zirdaRefusesTheBoundAndOnlyTheTappedLandsKeptLurrusLit() {
        board();
        mine("Library of Alexandria", true);
        mine("Grim Monolith", false);
        mine("Zirda, the Dawnwaker", false).setSickness(true);
        final Card basalt = mine("Basalt Monolith", false);
        mine("Island", false);
        final SpellAbility tap = basalt.getManaAbilities().iterator().next();
        for (int i = 0; i < 3; i++) me.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, tap.getManaPart(), me));
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        assertNull(HumanManaAffordability.untapWidening(me), "Zirda (a cost static in play) refuses the untap bound");
        noGlow(lurrus);
    }

    // ------------------------------------------------------------------ the rule's edges

    /** One hybrid half makeable: two untapped Plains pay {W/B}{W/B} with white. Lurrus glows, and Forge's payer pays it. */
    @Test
    public void hybridWithOneHalfMakeableStaysLit() {
        reportedBoard();
        mine("Plains", false);
        mine("Plains", false);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        glows(lurrus);
        notUnaffordable(lurrus);
    }

    /** With the reported Scrubland and Plains untapped, Lurrus is payable: it glows, and Forge's payer pays it. */
    @Test
    public void untappedWhiteSourcesMakeLurrusPayable() {
        reportedBoard(false);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        glows(lurrus);
        notUnaffordable(lurrus);
    }

    /**
     * One untapped Plains makes one white mana and nothing can untap it: the unbounded mana is colorless, so the second
     * {W/B} has nothing to pay it. Forge's payer agrees.
     */
    @Test
    public void oneWhiteSourcePaysOneHybridPipOnly() {
        reportedBoard();
        mine("Plains", false);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        noGlow(lurrus);
    }

    /** A color-changing hook: Mycosynth Lattice lets the colorless mana pay {W/B}. The proof stays unknown; Forge pays. */
    @Test
    public void colorChangingHookKeepsTheProofUnknown() {
        reportedBoard();
        mine("Mycosynth Lattice", false);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        notUnaffordable(lurrus);
        assertEquals(HumanManaAffordability.proof(me, spell(lurrus)), Proof.UNKNOWN);
        assertTrue(forgePays(spell(lurrus)), "Forge's payer spends the colorless mana as white or black");
    }

    /** A tapped source that a self-untap CAN reach still counts: Scrubland given "{1}: Untap this land" keeps Lurrus lit. */
    @Test
    public void aReachableTappedSourceStillCounts() {
        reportedBoard();
        Card scrubland = null;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Scrubland")) scrubland = c;
        }
        assertTrue(scrubland != null);
        scrubland.addSpellAbility(AbilityFactory.getAbility(
                "AB$ Untap | Cost$ 1 | Defined$ Self | SpellDescription$ Untap this land.", scrubland));
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        notUnaffordable(lurrus);
    }

    /** A reachable permanent's colors count without limit: Basalt Monolith given "{T}: Add {W}" keeps Lurrus lit. */
    @Test
    public void aReachablePermanentsColorsCountWithoutLimit() {
        reportedBoard();
        Card basalt = null;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Basalt Monolith")) basalt = c;
        }
        assertTrue(basalt != null);
        basalt.addSpellAbility(AbilityFactory.getAbility(
                "AB$ Mana | Cost$ T | Produced$ W | SpellDescription$ Add {W}.", basalt));
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        notUnaffordable(lurrus);
    }

    // ------------------------------------------------------------------ untaps that may reach more: the old reading

    /** Ley Druid's "{T}: Untap target land" can untap the tapped Scrubland: the old reading stands, and Lurrus stays lit. */
    @Test
    public void aTargetedUntapKeepsTheOldReading() {
        reportedBoard();
        mine("Ley Druid", false);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        notUnaffordable(lurrus);
    }

    /** A trigger that untaps (Mana Vault's upkeep trigger, on the battlefield) keeps the old reading. */
    @Test
    public void anUntapTriggerInPlayKeepsTheOldReading() {
        reportedBoard();
        mine("Mana Vault", true);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        notUnaffordable(lurrus);
    }

    /** Turnabout in hand untaps every land: a spell that untaps keeps the old reading. */
    @Test
    public void anUntapSpellKeepsTheOldReading() {
        reportedBoard();
        hand("Turnabout");
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        notUnaffordable(lurrus);
    }

    // ------------------------------------------------------------------ review S1 (Astra, #250): hidden cards

    /** What the player sees of the glow of each card in its hand: offered, the payable flag and the wire's proof. */
    private List<String> glowOfHand() {
        final List<String> out = new ArrayList<>();
        for (Card card : me.getCardsIn(ZoneType.Hand)) {
            out.add(card.getName() + " flag=" + flag(card));
            for (SpellAbility sa : card.getAllPossibleAbilities(me, true)) {
                if (sa.isManaAbility()) continue;
                out.add(card.getName() + " " + sa.getDescription() + " payable=" + HumanManaAffordability.payable(me, sa)
                        + " proof=" + HumanManaAffordability.proof(me, sa) + " offer=" + HumanManaAffordability.assess(me, sa));
            }
        }
        return out;
    }

    /** A card that untaps or reacts to an untap cycle from every zone: a trigger with no TriggerZones is live anywhere. */
    private static void reactsFromEveryZone(final Card card) {
        card.setSVar("ReviewUntap", "DB$ UntapAll | ValidCards$ Land");
        card.addTrigger(TriggerHandler.parseTrigger("Mode$ Drawn | ValidCard$ Card | Execute$ ReviewUntap"
                + " | TriggerDescription$ Whenever a player draws a card, untap all lands.", card, true));
        card.setSVar("ReviewDraw", "DB$ Draw | Defined$ You");
        card.addTrigger(TriggerHandler.parseTrigger("Mode$ Untaps | ValidCard$ Permanent | Execute$ ReviewDraw"
                + " | TriggerDescription$ Whenever a permanent becomes untapped, draw a card.", card, true));
    }

    @DataProvider(name = "hiddenPlaces")
    public Object[][] hiddenPlaces() {
        final List<Object[]> rows = new ArrayList<>();
        for (String place : new String[] {"their hand", "their library", "my library", "their exile face down",
                "their battlefield face down", "my exile face down"}) {
            for (String name : new String[] {"Vizier of Tumbling Sands", "Copperhorn Scout", "Peregrine Drake",
                    "Mana Vault", "reacts from every zone"}) {
                rows.add(new Object[] {place, name});
            }
        }
        return rows.toArray(new Object[0][]);
    }

    private Card hide(final String place, final String name) {
        final boolean mine = place.startsWith("my");
        final Player owner = mine ? me : foe;
        final String cardName = name.equals("reacts from every zone") ? "Grizzly Bears" : name;
        final ZoneType zone = place.contains("hand") ? ZoneType.Hand : place.contains("library") ? ZoneType.Library
                : place.contains("exile") ? ZoneType.Exile : ZoneType.Battlefield;
        final Card card = zone == ZoneType.Battlefield ? addCard(cardName, owner) : addCardToZone(cardName, owner, zone);
        if (name.equals("reacts from every zone")) reactsFromEveryZone(card);
        if (place.contains("face down")) card.turnFaceDown(true);
        return card;
    }

    /**
     * Review S1 (Astra, #250), hidden-card substitution invariance: on the owner's board, a card the player may not
     * identify (in the opponent's hand or library, its own library, or face down) never changes the glow, the payable
     * proof or the offer, whatever the card is. A Grizzly Bears there is the control; Vizier of Tumbling Sands ("when you
     * cycle, untap target permanent") and Copperhorn Scout ("whenever it attacks, untap each other creature") have
     * triggers with no zone restriction, and one card is given untap and untap-cycle triggers live in every zone.
     */
    @Test(dataProvider = "hiddenPlaces")
    public void review250_aHiddenCardNeverChangesTheGlow(final String place, final String name) {
        reportedBoard();
        hand("Kiki-Jiki, Mirror Breaker");
        hand("Imperial Recruiter");
        hand("Mana Vault");
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();
        final List<String> before = glowOfHand();

        final Card control = hide(place, "Grizzly Bears");
        settle();
        assertFalse(HumanManaAffordability.mayIdentify(control, me), place + ": the player may not identify the card");
        assertEquals(glowOfHand(), before, place + ": a Grizzly Bears");

        final Card hidden = hide(place, name);
        settle();
        assertFalse(HumanManaAffordability.mayIdentify(hidden, me), place + ": the player may not identify " + name);
        assertEquals(glowOfHand(), before, place + ": " + name + " must not change the glow");
        noGlow(lurrus);
    }

    /** The control: the same untap trigger on a card in view (the opponent's battlefield, face up) keeps the old reading. */
    @Test
    public void review250_theSameTriggerInViewKeepsTheOldReading() {
        reportedBoard();
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();
        noGlow(lurrus);
        reactsFromEveryZone(theirs("Grizzly Bears", false));
        settle();
        notUnaffordable(lurrus);
    }

    /** The proof's visibility rule is the interactive layer's ({@link InteractiveState#mayReceiveIdentity}), card for card. */
    @Test
    public void review250_mayIdentifyIsTheInteractiveLayersRule() {
        board();
        final List<Card> cards = new ArrayList<>();
        for (Player owner : List.of(me, foe)) {
            cards.add(addCardToZone("Grizzly Bears", owner, ZoneType.Hand));
            cards.add(addCardToZone("Grizzly Bears", owner, ZoneType.Library));
            cards.add(addCardToZone("Grizzly Bears", owner, ZoneType.Graveyard));
            cards.add(addCardToZone("Grizzly Bears", owner, ZoneType.Exile));
            final Card exiledDown = addCardToZone("Grizzly Bears", owner, ZoneType.Exile);
            exiledDown.turnFaceDown(true);
            cards.add(exiledDown);
            cards.add(addCard("Grizzly Bears", owner));
            final Card down = addCard("Grizzly Bears", owner);
            down.turnFaceDown(true);
            cards.add(down);
        }
        settle();
        for (Player viewer : List.of(me, foe)) {
            for (Card card : cards) {
                assertEquals(HumanManaAffordability.mayIdentify(card, viewer),
                        InteractiveState.mayReceiveIdentity(card.getView(), viewer.getView()),
                        card + " in " + card.getZone() + (card.isFaceDown() ? " face down" : "") + " for " + viewer);
            }
        }
    }

    // ------------------------------------------------------------------ review S1 (Astra, #250): the offer

    /**
     * Review S1 (Astra, #250): untapped Basalt Monolith, tapped River of Tears, Lightning Bolt in hand. The live jar
     * offers nothing ({@code assess} PROVEN_UNAFFORDABLE: River of Tears makes {U}, or {B} after a land drop, never {R}).
     * The payable proof's reading of a mana ability's sub-abilities must not reach the offer.
     */
    @Test
    public void review250_theOfferIsTheLiveJars_riverOfTears() {
        board();
        mine("Basalt Monolith", false);
        mine("River of Tears", true);
        final Card bolt = hand("Lightning Bolt");
        settle();
        final SpellAbility sa = spell(bolt);

        assertEquals(HumanManaAffordability.assess(me, sa), Assessment.PROVEN_UNAFFORDABLE, "the live jar's offer");
        assertFalse(HumanManaAffordability.mayAfford(me, sa));
        sameOffer(bolt);
    }

    @DataProvider(name = "payments")
    public Object[][] payments() {
        return new Object[][] {
            {"Lurrus of the Dream-Den", new String[] {"Plains", "Plains"}},
            {"Counterspell", new String[] {"Island", "Island"}},
            {"Counterspell", new String[] {"Island", "Mystic Gate"}},
            {"Counterspell", new String[] {"Chromatic Star", "Island"}},
            {"Counterspell", new String[] {"Skycloud Expanse", "Island"}},
            {"Counterspell", new String[] {"City of Brass", "Mana Confluence"}},
            {"Counterspell", new String[] {"Reflecting Pool", "Island"}},
            {"Lightning Bolt", new String[] {"Lotus Petal"}},
            {"Dark Ritual", new String[] {"Blood Pet"}},
            {"Gitaxian Probe", new String[] {}},
            {"Dismember", new String[] {}},
            {"Spectral Procession", new String[] {"Plains", "Plains", "Plains"}},
            {"Walking Ballista", new String[] {}},
            {"Banefire", new String[] {"Mountain"}},
            {"Arcum's Astrolabe", new String[] {"Snow-Covered Island"}},
            {"Counterspell", new String[] {"Mycosynth Lattice"}},
            {"Treasure Cruise", new String[] {"Island"}},
            {"Force of Will", new String[] {"Island", "Island", "Helm of Awakening"}},
            {"Counterspell", new String[] {"Izzet Signet", "Island"}},
            {"Lightning Bolt", new String[] {"River of Tears"}},
        };
    }

    /**
     * The reviewer's payments (Astra, #250), on Zirda + Basalt Monolith + tapped Scrubland and Plains: whatever Forge's
     * payer can pay is never proved unpayable, and every offer is the live jar's.
     */
    @Test(dataProvider = "payments")
    public void review250_whatForgePaysIsNeverDark(final String name, final String[] sources) {
        board();
        mine("Zirda, the Dawnwaker", false);
        mine("Basalt Monolith", false);
        mine("Scrubland", true);
        mine("Plains", true);
        for (String source : sources) mine(source, false);
        if (name.equals("Treasure Cruise")) for (int i = 0; i < 7; i++) addCardToZone("Forest", me, ZoneType.Graveyard);
        final Card card = hand(name);
        settle();
        final SpellAbility sa = spell(card);
        sameOffer(card);
        if (forgePays(sa)) assertNotEquals(HumanManaAffordability.assessPayable(me, sa), Assessment.PROVEN_UNAFFORDABLE,
                name + " " + java.util.Arrays.toString(sources) + ": Forge pays this cost");
    }

    /** Two floating {W} pay both {W/B} pips: Forge pays, so Lurrus is not proved unpayable (Astra, #250). */
    @Test
    public void review250_floatingWhitePaysBothPips() {
        board();
        final Card basalt = mine("Basalt Monolith", false);
        mine("Zirda, the Dawnwaker", false);
        mine("Plains", true);
        mine("Scrubland", true);
        final SpellAbility tap = basalt.getManaAbilities().iterator().next();
        for (int i = 0; i < 2; i++) me.getManaPool().addMana(new Mana((byte) ManaAtom.WHITE, basalt, tap.getManaPart(), me));
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();
        assertTrue(forgePays(spell(lurrus)));
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(lurrus)), Assessment.PROVEN_UNAFFORDABLE);
        sameOffer(lurrus);
    }

    /** Chrome Mox imprinting a white card plus a Treasure pay both hybrid pips (Astra, #250). */
    @Test
    public void review250_chromeMoxAndTreasurePayHybrid() {
        board();
        mine("Basalt Monolith", false);
        mine("Zirda, the Dawnwaker", false);
        final Card mox = mine("Chrome Mox", false);
        mox.addImprintedCard(addCardToZone("Swords to Plowshares", me, ZoneType.Exile));
        addToken("c_a_treasure_sac", me).setSickness(false);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();
        assertTrue(forgePays(spell(lurrus)));
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(lurrus)), Assessment.PROVEN_UNAFFORDABLE);
        sameOffer(lurrus);
    }
}
