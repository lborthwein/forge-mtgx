package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
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
 * glow-valki-1027: the owner's ranked game forge-mvr55yi8, report 2026-10-27T20-34-32 ("Game is showing me I can cast
 * creatures when I only have 1 possible mana available due to the once per turn nature of Hundred-Year Hedge"). Turn 4,
 * main 1: Library of Alexandria and Forest tapped, the only untapped source Wall of Roots ("Put a -0/-1 counter on this
 * creature: Add {G}. Activate only once each turn.", just cast). Questing Beast {2}{G}{G} and Nissa, Ascended Animist
 * glowed: the payable proof did not model a cost that puts a counter on the source, so it gave up (unknown = glow).
 *
 * <p>Now the cost is admitted and the activation limit bounds it: the Wall makes exactly one {G} a turn, none once used
 * this turn. Each no-glow board also asserts that Forge's own payer cannot pay (the ground truth); each glow board that
 * it can.</p>
 */
public class HumanManaAffordabilityActivationLimitTest extends AITest {

    private Game game;
    private Player me;
    private Player foe;

    /** The report's board: my turn 4, main 1; Library of Alexandria and Forest tapped; Wall of Roots just cast. */
    private Card reportBoard() {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        foe = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (String n : new String[] {"Library of Alexandria", "Forest"}) {
            final Card c = addCard(n, me);
            c.setTapped(true);
            c.setSickness(false);
        }
        final Card wall = addCard("Wall of Roots", me); // summoning sick: its ability has no {T}
        for (String n : new String[] {"Raging Ravine", "Mountain", "Wooded Foothills", "Mountain", "Forest"}) {
            addCardToZone(n, me, ZoneType.Hand);
        }
        me.setLandsPlayedThisTurn(1);
        for (String n : new String[] {"Swamp", "Plains"}) {
            addCard(n, foe).setTapped(true);
        }
        addCard("Valki, God of Lies", foe);
        game.getAction().checkStateEffects(true);
        return wall;
    }

    private Card hand(final String name) {
        return addCardToZone(name, me, ZoneType.Hand);
    }

    private SpellAbility spell(final Card card) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    private boolean forgePays(final SpellAbility sa) {
        return ComputerUtilMana.canPayManaCost(sa, me, 0, false);
    }

    private List<SpellAbility> offered(final Card card) {
        return card.getAllPossibleAbilities(me, true).stream()
                .filter(a -> InteractiveGuiGame.passesCastRestrictions(me, a))
                .filter(a -> HumanManaAffordability.mayAfford(me, a)).toList();
    }

    /** No glow: the flag is false (or the card is not offered), proved, the wire says unaffordable, Forge cannot pay. */
    private void noGlow(final Card card) {
        final List<SpellAbility> offered = offered(card);
        if (!offered.isEmpty()) {
            assertNotEquals(InteractiveGuiGame.controlPayable(me, offered), Boolean.TRUE, card.getName() + " must not glow");
        }
        final SpellAbility sa = spell(card);
        assertEquals(HumanManaAffordability.assessPayable(me, sa), Assessment.PROVEN_UNAFFORDABLE, card.getName());
        assertEquals(HumanManaAffordability.proof(me, sa), Proof.UNAFFORDABLE, card.getName());
        assertFalse(forgePays(sa), card.getName() + ": Forge's payer must not pay it either");
    }

    private void glows(final Card card) {
        final SpellAbility sa = spell(card);
        assertTrue(HumanManaAffordability.payable(me, sa), card.getName() + " is payable");
        assertTrue(forgePays(sa), card.getName() + ": Forge's payer pays it");
    }

    private SpellAbility wallMana(final Card wall) {
        final SpellAbility m = wall.getManaAbilities().get(0);
        m.setActivatingPlayer(me);
        return m;
    }

    /**
     * Use the Wall's mana ability once this turn, as paying for something with it does: its cost (the -0/-1 counter),
     * then the activation itself (MagicStack.add records it and adds the {G}), and the {G} is spent at once.
     */
    private void useWall(final Card wall) {
        final SpellAbility m = wallMana(wall);
        assertTrue(m.canPlay(), "the Wall's ability is usable before");
        wall.addCounterInternal(forge.game.card.CounterEnumType.M0M1, 1, me, false, null, null);
        game.getStack().add(m);
        me.getManaPool().clearPool(false);
        game.getAction().checkStateEffects(true);
        assertEquals(m.getActivationsThisTurn(), 1);
        assertFalse(m.canPlay(), "Activate only once each turn");
    }

    @Test
    public void owner1027_reportBoard_questingBeastAndNissaDoNotGlow() {
        reportBoard();
        final Card beast = hand("Questing Beast");
        final Card nissa = hand("Nissa, Ascended Animist");
        noGlow(beast);
        noGlow(nissa);
    }

    @Test
    public void theUnusedWallStillCountsOne() {
        reportBoard();
        glows(hand("Llanowar Elves"));      // {G}: the Wall's one {G}
        noGlow(hand("Grizzly Bears"));      // {1}{G}: one activation, not two
    }

    @Test
    public void theUsedWallCountsNothing() {
        final Card wall = reportBoard();
        useWall(wall);
        assertEquals(wall.getCounters(forge.game.card.CounterEnumType.M0M1), 1);
        noGlow(hand("Llanowar Elves"));
    }

    @Test
    public void theUsedWallBesideAnUntappedForest() {
        final Card wall = reportBoard();
        useWall(wall);
        final Card forest = addCard("Forest", me);
        forest.setSickness(false);
        glows(hand("Llanowar Elves"));
        noGlow(hand("Grizzly Bears"));
    }

    @Test
    public void nextTurnTheWallCountsAgain() {
        final Card wall = reportBoard();
        useWall(wall);
        noGlow(hand("Llanowar Elves"));
        // the per-turn record resets with the turn, as Forge's own restriction reads it
        wall.resetActivationsPerTurn();
        assertTrue(wallMana(wall).canPlay());
        glows(hand("Llanowar Elves"));
    }
}
