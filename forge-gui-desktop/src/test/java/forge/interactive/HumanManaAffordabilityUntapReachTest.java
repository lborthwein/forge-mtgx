package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;

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
 * refuses the untap bound ({@link HumanManaAffordability#untapWidening}). Only the colour screen was left, and it counted
 * the colours of every permanent the player controls, tapped or not, on the ground that "an untap can reach it". These
 * untaps reach only their own Monoliths: the tapped Scrubland and Plains can be untapped by nothing, so they make no mana.</p>
 *
 * <p>Now the payable proof works out which permanents an untap usable now can reach. Each untapped source still counts
 * once, each reachable permanent's colours count without limit, and a tapped source nothing can untap counts not at all.
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

    private void noGlow(final Card card) {
        assertNotEquals(flag(card), Boolean.TRUE, card.getName() + " must not glow");
        for (SpellAbility sa : plays(card)) {
            assertEquals(HumanManaAffordability.assessPayable(me, sa), Assessment.PROVEN_UNAFFORDABLE,
                    card.getName() + ": the payable proof of " + sa);
            assertEquals(HumanManaAffordability.proof(me, sa), Proof.UNAFFORDABLE, card.getName() + ": the wire's proof");
            assertFalse(forgePays(sa), card.getName() + ": Forge's payer must not pay " + sa + " either");
        }
    }

    private void glows(final Card card) {
        assertEquals(flag(card), Boolean.TRUE, card.getName() + " is payable and offered");
        assertTrue(plays(card).stream().anyMatch(this::forgePays), card.getName() + ": Forge's payer pays it");
    }

    /** Not proved unpayable: offered with payable true, whatever Forge's payer (which activates no untap) says. */
    private void notUnaffordable(final Card card) {
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
     * The cause, on either jar. Zirda's cost static refuses the untap bound, so only the colour screen was left; and the same
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
     * One untapped Plains makes one white mana and nothing can untap it: the unbounded mana is colourless, so the second
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

    /** A colour-changing hook: Mycosynth Lattice lets the colourless mana pay {W/B}. The proof stays unknown; Forge pays. */
    @Test
    public void colourChangingHookKeepsTheProofUnknown() {
        reportedBoard();
        mine("Mycosynth Lattice", false);
        final Card lurrus = hand("Lurrus of the Dream-Den");
        settle();

        notUnaffordable(lurrus);
        assertEquals(HumanManaAffordability.proof(me, spell(lurrus)), Proof.UNKNOWN);
        assertTrue(forgePays(spell(lurrus)), "Forge's payer spends the colourless mana as white or black");
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

    /** A reachable permanent's colours count without limit: Basalt Monolith given "{T}: Add {W}" keeps Lurrus lit. */
    @Test
    public void aReachablePermanentsColoursCountWithoutLimit() {
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
}
