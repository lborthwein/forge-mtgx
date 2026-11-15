package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;
import forge.player.HumanManaAffordability.Proof;

/**
 * afford-reflected-1114: the castable glow on the owner's host game of 2026-11-15 (report 2026-11-15T01-17-17, jar
 * cd7295b7). Turn 11, main 1, the owner's priority: Chrome Mox untapped with nothing imprinted, Celestial Colonnade,
 * Library of Alexandria, two Mountains, Grim Monolith tapped. Kiki-Jiki, Mirror Breaker ({2}{R}{R}{R}) and Ulamog, the
 * Infinite Gyre ({11}) glowed with two red sources and four mana in all.
 *
 * <ul>
 *   <li>Chrome Mox's reflected ability produces "1" by its script, which the proof could not read: every proof gave up
 *       while it was untapped. Now its colours are the imprinted card's, and with nothing imprinted it makes no mana.</li>
 *   <li>Grim Monolith's "{4}: Untap" made the amount of mana unknown. It costs more than it gives back, so the mana counted
 *       now bounds the payment; each may stand in for the {C} it brings back.</li>
 * </ul>
 *
 * <p>As in {@link HumanManaAffordabilityGlowAuditTest}, each board that must not glow also asserts that Forge's own
 * payer ({@link ComputerUtilMana#canPayManaCost}) cannot pay, and each that glows that it can.</p>
 */
public class HumanManaAffordabilityReflectedUntapTest extends AITest {

    private Game game;
    private Player me;

    private void board() {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private Card mine(final String name, final boolean tapped) {
        final Card card = addCard(name, me);
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

    /** The abilities the bridge offers on this card at priority (buildStatefulControls). */
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

    private Card ownerBoard(final boolean monolithTapped) {
        board();
        final Card mox = mine("Chrome Mox", false);
        mine("Celestial Colonnade", false);
        mine("Library of Alexandria", false);
        mine("Mountain", false);
        mine("Grim Monolith", monolithTapped);
        mine("Mountain", false);
        return mox;
    }

    // ------------------------------------------------------------------ the owner's board

    /** The report's board: Kiki-Jiki and Ulamog do not glow; Mystical Tutor and Pestermite still do. */
    @Test
    public void owner1115_kikiAndUlamogNoGlow_tutorAndPestermiteGlow() {
        ownerBoard(true);
        final Card tutor = hand("Mystical Tutor");
        final Card ulamog = hand("Ulamog, the Infinite Gyre");
        final Card pestermite = hand("Pestermite");
        final Card kiki = hand("Kiki-Jiki, Mirror Breaker");
        settle();

        noGlow(kiki);
        noGlow(ulamog);
        glows(tutor);
        glows(pestermite);
        assertEquals(HumanManaAffordability.proof(me, spell(tutor)), Proof.AFFORDABLE);
        assertEquals(HumanManaAffordability.proof(me, spell(pestermite)), Proof.AFFORDABLE);
    }

    /** Chrome Mox with nothing imprinted reflects no colour: it makes no mana (ManaReflectedEffect adds "0"). */
    @Test
    public void chromeMoxWithNothingImprintedMakesNoMana() {
        final Card mox = ownerBoard(true);
        settle();
        final SpellAbility tap = mox.getManaAbilities().iterator().next();
        tap.setActivatingPlayer(me);
        assertEquals(HumanManaAffordability.reflectedColours(me, tap), Integer.valueOf(0));
    }

    /** Imprinted with a red card, Chrome Mox is a third red source: Kiki-Jiki glows, and Forge's payer pays it. */
    @Test
    public void chromeMoxImprintedRedMakesKikiPayable() {
        final Card mox = ownerBoard(true);
        final Card bolt = addCardToZone("Lightning Bolt", me, ZoneType.Exile);
        mox.addImprintedCard(bolt);
        final Card kiki = hand("Kiki-Jiki, Mirror Breaker");
        final Card ulamog = hand("Ulamog, the Infinite Gyre");
        settle();

        final SpellAbility tap = mox.getManaAbilities().iterator().next();
        tap.setActivatingPlayer(me);
        assertEquals(HumanManaAffordability.reflectedColours(me, tap) & ManaAtom.ALL_MANA_TYPES, ManaAtom.RED);
        glows(kiki);
        noGlow(ulamog);
    }

    /** With Grim Monolith untapped its {C}{C}{C} counts: seven mana, still two red. Ulamog and Kiki-Jiki stay dark. */
    @Test
    public void untappedMonolithCountsItsManaOnce() {
        ownerBoard(false);
        final Card kiki = hand("Kiki-Jiki, Mirror Breaker");
        final Card ulamog = hand("Ulamog, the Infinite Gyre");
        final Card pestermite = hand("Pestermite");
        settle();

        noGlow(kiki);
        noGlow(ulamog);
        glows(pestermite);
    }

    // ------------------------------------------------------------------ untaps the bound refuses

    /**
     * Voltaic Key ("{1}, {T}: Untap target artifact") can untap Grim Monolith for one mana, which gives back three: an
     * untap that may add mana. The bound is refused and the old answer stands: Ulamog is not proved unpayable.
     */
    @Test
    public void targetedUntapThatAddsManaKeepsTheOldAnswer() {
        ownerBoard(true);
        mine("Voltaic Key", false);
        final Card ulamog = hand("Ulamog, the Infinite Gyre");
        settle();

        assertNull(HumanManaAffordability.untapWidening(me), "Voltaic Key's targeted untap is not admitted");
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(ulamog)), Assessment.PROVEN_UNAFFORDABLE);
    }

    /** Grim Monolith's own untap is admitted: it widens by {C} alone. */
    @Test
    public void monolithSelfUntapWidensByColourless() {
        ownerBoard(true);
        settle();
        final Integer widen = HumanManaAffordability.untapWidening(me);
        assertEquals(widen == null ? null : widen & ManaAtom.ALL_MANA_TYPES, Integer.valueOf(ManaAtom.COLORLESS));
    }
}
