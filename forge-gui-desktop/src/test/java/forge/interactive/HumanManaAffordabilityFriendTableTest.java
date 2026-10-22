package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;

/**
 * table-live-view-1022: the castable proof on the owner's friend-table boards of 2026-10-22.
 *
 * <ul>
 *   <li>(a) 20-07-11: "I didn't have the mana to cast my counter but it still prompted". Every land and Mana Vault
 *   tapped, Pentad Prism untapped with ONE charge counter: Miscalculation ({1}{U}) was offered and stopped on every
 *   priority. The proof counted "Remove a charge counter: Add one mana" as unbounded (UNKNOWN = payable). A source whose
 *   cost removes counters from itself is now capped by the counters it has.</li>
 *   <li>(b) 19-55-39: "No valid target for Sapper's Tunnel and my friend sac'ed it without noticing". Wasteland (the
 *   pantheon skin's Sapper's Tunnel) with no other nonbasic land in play glowed, and was activated targeting itself:
 *   its cost sacrifices the only legal target, so the ability cannot resolve. Its {@code payable} is now false.</li>
 * </ul>
 * Both are proofs: anything unsure still says payable.
 */
public class HumanManaAffordabilityFriendTableTest extends AITest {

    private Game game;
    private Player me;
    private Player foe;

    private void board(final PhaseType phase, final boolean mine) {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        foe = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(phase, mine ? me : foe);
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

    private SpellAbility spell(final Card card) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    /** The card's first non-mana activated ability, activated by me. */
    private SpellAbility activated(final Card card) {
        for (SpellAbility sa : card.getSpellAbilities()) {
            if (!sa.isSpell() && !sa.isManaAbility() && !sa.isLandAbility()) {
                sa.setActivatingPlayer(me);
                return sa;
            }
        }
        throw new AssertionError("no activated ability on " + card);
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

    // ------------------------------------------------------------------ (a) counter-limited sources

    /** The owner's board, 20-07-11 (turn 13, the friend's main 1; me holding Miscalculation). */
    private Card owner2007(final int charge) {
        board(PhaseType.MAIN1, false);
        for (String land : List.of("Island", "Swamp", "Island", "Island", "Island", "Swamp")) mine(land, true);
        mine("Narset, Parter of Veils", false).setCounters(CounterEnumType.LOYALTY, 1);
        mine("Mana Vault", true);
        mine("Pentad Prism", false).setCounters(CounterEnumType.CHARGE, charge);
        hand("Island");
        hand("Otawara, Soaring City");
        hand("Gitaxian Probe");
        theirs("Swamp", true);
        theirs("Swamp", true);
        theirs("Swamp", false);
        theirs("Swamp", false);
        theirs("Talisman of Hierarchy", false);
        final Card miscalculation = hand("Miscalculation");
        game.getAction().checkStateEffects(true);
        return miscalculation;
    }

    @Test
    public void owner1022_200711_onePrismChargeDoesNotPayMiscalculation() {
        final Card miscalculation = owner2007(1);
        assertEquals(HumanManaAffordability.assessPayable(me, spell(miscalculation)), Assessment.PROVEN_UNAFFORDABLE,
                "one charge counter is one mana; {1}{U} needs two");
        assertFalse(HumanManaAffordability.payable(me, spell(miscalculation)));
        assertEquals(HumanManaAffordability.proof(me, spell(miscalculation)), HumanManaAffordability.Proof.UNAFFORDABLE);
        assertNotEquals(flag(miscalculation), Boolean.TRUE, "no glow: neither the counter nor cycling {2} is payable");
    }

    @Test
    public void twoPrismChargesPayMiscalculation() {
        final Card miscalculation = owner2007(2);
        assertTrue(HumanManaAffordability.payable(me, spell(miscalculation)), "two charge counters, two mana of any colour");
        assertEquals(flag(miscalculation), Boolean.TRUE);
    }

    /** A repeatable counter source makes as many mana as its counters pay for, no more. */
    @Test
    public void aPrismMakesAsManyManaAsItsCounters() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        final Card prism = mine("Pentad Prism", false);
        final Card counterspell = hand("Counterspell");
        prism.setCounters(CounterEnumType.CHARGE, 1);
        assertFalse(HumanManaAffordability.payable(me, spell(counterspell)), "{U}{U} from one charge");
        prism.setCounters(CounterEnumType.CHARGE, 2);
        assertTrue(HumanManaAffordability.payable(me, spell(counterspell)), "{U}{U} from two charges");
        prism.setCounters(CounterEnumType.CHARGE, 0);
        assertFalse(HumanManaAffordability.payable(me, spell(counterspell)), "an empty prism makes nothing");
    }

    /** Gemstone Mine-style depletion: a {T} source that also removes a counter makes nothing once it has none. */
    @Test
    public void anEmptyGemstoneMineMakesNothing() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        final Card mine = mine("Gemstone Mine", false);
        final Card brainstorm = hand("Brainstorm");
        mine.setCounters(CounterEnumType.MINING, 0);
        assertFalse(HumanManaAffordability.payable(me, spell(brainstorm)), "no mining counter: no mana");
        mine.setCounters(CounterEnumType.MINING, 1);
        assertTrue(HumanManaAffordability.payable(me, spell(brainstorm)), "one mining counter: one mana");
        mine.setCounters(CounterEnumType.MINING, 3);
        final Card counterspell = hand("Counterspell");
        assertFalse(HumanManaAffordability.payable(me, spell(counterspell)), "still one {T}: never two mana");
    }

    // ------------------------------------------------------------------ (b) the cost sacrifices the only target

    @Test
    public void owner1022_195539_wastelandWithOnlyItselfToTargetIsNotPlayable() {
        board(PhaseType.MAIN1, true);
        mine("Swamp", false);
        final Card wasteland = mine("Wasteland", false);
        theirs("Swamp", false);
        theirs("Swamp", false);
        game.getAction().checkStateEffects(true);
        final SpellAbility destroy = activated(wasteland);
        assertTrue(HumanManaAffordability.onlyTargetIsItsSacrificedSource(me, destroy));
        assertFalse(HumanManaAffordability.payable(me, destroy), "its cost sacrifices its only target");
        assertEquals(HumanManaAffordability.proof(me, destroy), HumanManaAffordability.Proof.UNAFFORDABLE);
        // The land still taps for mana: the card's control stays payable, and the destroy ability is still offered.
        assertTrue(offered(wasteland).contains(destroy), "Forge's legality is unchanged: the click is Forge's");
        assertEquals(flag(wasteland), Boolean.TRUE, "its mana ability is payable");
        for (SpellAbility mana : wasteland.getManaAbilities()) {
            mana.setActivatingPlayer(me);
            assertTrue(HumanManaAffordability.payable(me, mana));
        }
    }

    @Test
    public void wastelandWithAnotherNonbasicToTargetIsPlayable() {
        board(PhaseType.MAIN1, true);
        final Card wasteland = mine("Wasteland", false);
        theirs("Tropical Island", false);
        game.getAction().checkStateEffects(true);
        final SpellAbility destroy = activated(wasteland);
        assertFalse(HumanManaAffordability.onlyTargetIsItsSacrificedSource(me, destroy));
        assertTrue(HumanManaAffordability.payable(me, destroy));
    }

    @Test
    public void myOwnOtherNonbasicIsATargetToo() {
        board(PhaseType.MAIN1, true);
        final Card wasteland = mine("Wasteland", false);
        mine("Ancient Tomb", false);
        theirs("Swamp", false);
        game.getAction().checkStateEffects(true);
        assertTrue(HumanManaAffordability.payable(me, activated(wasteland)), "Ancient Tomb is a legal target");
    }

    /** A player is a legal target of "any target": never proven, whatever the board. */
    @Test
    public void anyTargetIsNeverOnlyTheSource() {
        board(PhaseType.MAIN1, true);
        final Card fanatic = mine("Mogg Fanatic", false);
        game.getAction().checkStateEffects(true);
        final SpellAbility ping = activated(fanatic);
        assertFalse(HumanManaAffordability.onlyTargetIsItsSacrificedSource(me, ping));
        assertTrue(HumanManaAffordability.payable(me, ping));
    }

    /** No sacrifice of the source in the cost: no proof (the source stays a legal target). */
    @Test
    public void noSelfSacrificeNoProof() {
        board(PhaseType.MAIN1, true);
        final Card port = mine("Rishadan Port", false);
        mine("Island", false);
        game.getAction().checkStateEffects(true);
        assertFalse(HumanManaAffordability.onlyTargetIsItsSacrificedSource(me, activated(port)));
    }
}
