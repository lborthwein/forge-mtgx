package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane bvw-step1-build-1024: the generator's adopted line whose first step is our attack (lane bvw-label-audit-1024,
 * DIAGNOSIS cases B and O1). Off (the default and the step-0 generator): {@code decide} drops the line at adoption and
 * Forge AI plays the turn. On ({@code adoptAttack=true}): the line is kept, the seat passes to combat, and
 * {@code answerAttack} declares the alpha strike. Fixture position only.
 */
public class BvwAdoptAttackTest extends SimulationTest {

    /** Our MAIN1, a lethal attacker against an empty board (their life 6, no cards in hand, no mana). */
    static Game lethalBoard() {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("", "Craw Wurm;Forest", 20, "", "",
                6, "human"));
    }

    static ForcedWinSolver solver(final Player me, final boolean adoptAttack) {
        final ForcedWinSolver.Config c = HonestGeneratorTest.config();
        c.adoptAttack = adoptAttack;
        return HonestGenerator.solver(me, c);
    }

    @Test(timeOut = 300000)
    public void anAttackFirstLineIsDroppedWithoutTheFlag() {
        final Game g = lethalBoard();
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, false);
        final ForcedWinSolver.Decision d = s.decide(g);
        Assert.assertEquals(s.taken, 1, "the solver must take the attack line: " + s.summary());
        Assert.assertFalse(d.owned, "the step-0 generator drops the attack-first line at adoption");
        Assert.assertFalse(s.lineActive());
        final Combat combat = new Combat(me);
        Assert.assertFalse(s.answerAttack(me, combat), "no line, so the alpha strike is not declared");
        Assert.assertTrue(combat.getAttackers().isEmpty());
    }

    @Test(timeOut = 300000)
    public void anAttackFirstLineIsDeclaredWithTheFlag() {
        final Game g = lethalBoard();
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, true);
        final ForcedWinSolver.Decision d = s.decide(g);
        Assert.assertEquals(s.taken, 1, s.summary());
        Assert.assertTrue(d.owned && d.action == null, "the seat passes priority to combat, keeping the line");
        Assert.assertTrue(s.lineActive());
        // later priorities before combat keep passing (the follower waits for the attack)
        final ForcedWinSolver.Decision again = s.decide(g);
        Assert.assertTrue(again.owned && again.action == null);
        final Combat combat = new Combat(me);
        Assert.assertTrue(s.answerAttack(me, combat), "the adopted line declares its alpha strike");
        boolean wurm = false;
        for (Card c : combat.getAttackers()) {
            wurm |= c.getName().equals("Craw Wurm");
        }
        Assert.assertTrue(wurm, "Craw Wurm attacks");
    }

    /** Our MAIN1: Craw Wurm (6/4) and Grizzly Bears against their 6 life, an untapped Mountain, no cards in hand. */
    static Game twoAttackerBoard() {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("",
                "Craw Wurm;Grizzly Bears;Forest", 20, "", "Mountain", 6, "human"));
    }

    /** Their response in our MAIN1: Lightning Bolt (a card given to them after the line was adopted) at the Bears. */
    SpellAbility boltTheBears(final Game g, final Player opp, final Card bears) {
        final Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        final SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        sa.getTargets().add(bears);
        return sa;
    }

    static List<String> names(final Iterable<Card> cs) {
        final List<String> out = new ArrayList<>();
        for (Card c : cs) {
            out.add(c.getName());
        }
        return out;
    }

    @Test(timeOut = 300000)
    public void theLineSurvivesTheirMain1ResponseAndAttacksWithWhatIsLeft() {
        // review #16f: the adopted attack-first line across an opponent's response in our MAIN1. Their Bolt on the
        // stack: no counter to protect with, the line is kept and the seat passes (the bolt resolves). After it the
        // line still waits for combat, and the alpha strike declares what is left (Craw Wurm, still lethal), no replan
        final Game g = twoAttackerBoard();
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        final ForcedWinSolver s = solver(me, true);
        final ForcedWinSolver.Decision d = s.decide(g);
        Assert.assertEquals(s.taken, 1, s.summary());
        Assert.assertTrue(d.owned && d.action == null && s.lineActive(), "the attack-first line is adopted: " + s.summary());
        final Card bears = ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Grizzly Bears");
        final SpellAbility bolt = boltTheBears(g, opp, bears);
        g.getStackZone().add(bolt.getHostCard());
        g.getStack().add(bolt);
        final ForcedWinSolver.Decision onTop = s.decide(g);
        Assert.assertTrue(onTop.owned && onTop.action == null, "their spell on top: the seat passes, keeping the line");
        Assert.assertTrue(s.lineActive());
        g.getStack().resolveStack();
        g.getAction().checkStateEffects(true); // lethal damage: the Bears die
        Assert.assertTrue(g.getStack().isEmpty());
        Assert.assertNotNull(ForcedWinSolverTest.inZone(me, ZoneType.Graveyard, "Grizzly Bears"), "the bolt killed the Bears");
        final ForcedWinSolver.Decision after = s.decide(g);
        Assert.assertTrue(after.owned && after.action == null && s.lineActive(), "after it the line still waits for combat");
        Assert.assertEquals(s.replans, 0, "the line is kept, not replanned: " + s.summary());
        Assert.assertEquals(s.taken, 1, s.summary());
        final Combat combat = new Combat(me);
        Assert.assertTrue(s.answerAttack(me, combat), "the adopted line declares its alpha strike");
        Assert.assertEquals(names(combat.getAttackers()), List.of("Craw Wurm"), "the alpha strike uses what is left");
    }

    @Test(timeOut = 600000)
    public void endToEndTheirMain1BoltDoesNotStopTheLethalAttack() {
        // review #16f end to end: the flagged seat adopts the attack line in its MAIN1; their seat answers our first pass
        // in MAIN1 with Lightning Bolt at the Bears; the line survives, Craw Wurm alone attacks and kills on turn 7
        final Game g = twoAttackerBoard();
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        final ForcedWinSolver s = solver(me, true);
        final List<List<String>> declared = new ArrayList<>();
        me.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public List<SpellAbility> chooseSpellAbilityToPlay() {
                final ForcedWinSolver.Decision dd = s.decide(getGame());
                if (dd.owned) {
                    return dd.action == null ? null : List.of(dd.action);
                }
                return super.chooseSpellAbilityToPlay();
            }

            @Override
            void declareFirst(final Player attacker, final Combat combat) {
                if (!s.answerAttack(attacker, combat)) {
                    super.declareFirst(attacker, combat);
                }
                declared.add(names(combat.getAttackers()));
            }
        });
        final boolean[] cast = {false};
        opp.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, opp, opp.getController().getLobbyPlayer()) {
            @Override
            public List<SpellAbility> chooseSpellAbilityToPlay() {
                final PhaseHandler ph = getGame().getPhaseHandler();
                final Card bears = ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, "Grizzly Bears");
                if (!cast[0] && ph.isPlayerTurn(me) && ph.is(PhaseType.MAIN1) && getGame().getStack().isEmpty()
                        && bears != null && s.lineActive()) {
                    cast[0] = true;
                    return List.of(boltTheBears(getGame(), opp, bears));
                }
                return super.chooseSpellAbilityToPlay();
            }
        });
        ForcedWinSolverTest.play(g, 7);
        Assert.assertTrue(cast[0], "their MAIN1 response was cast");
        Assert.assertNotNull(ForcedWinSolverTest.inZone(me, ZoneType.Graveyard, "Grizzly Bears"), "the bolt killed the Bears");
        Assert.assertTrue(g.isGameOver() && opp.hasLost(), "Craw Wurm's attack kills on turn 7: " + s.summary());
        Assert.assertEquals(g.getPhaseHandler().getTurn(), 7);
        Assert.assertEquals(s.taken, 1, s.summary());
        Assert.assertEquals(s.replans, 0, "the line was kept across the response: " + s.summary());
        Assert.assertEquals(declared, List.of(List.of("Craw Wurm")), "one declaration, what was left");
    }

    @Test(timeOut = 300000)
    public void theFlaggedLinePlaysTheLethalAttack() {
        // end to end: the honest generator seat with the flag kills on this turn through its own declaration
        final Game g = lethalBoard();
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, true);
        final Player opp = g.getPlayers().get(1);
        me.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, me, me.getController().getLobbyPlayer()) {
            @Override
            public java.util.List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
                final ForcedWinSolver.Decision dd = s.decide(getGame());
                if (dd.owned) {
                    return dd.action == null ? null : java.util.List.of(dd.action);
                }
                return super.chooseSpellAbilityToPlay();
            }

            @Override
            void declareFirst(final Player attacker, final Combat combat) {
                if (!s.answerAttack(attacker, combat)) {
                    super.declareFirst(attacker, combat);
                }
            }
        });
        ForcedWinSolverTest.play(g, 7);
        Assert.assertTrue(g.isGameOver() && opp.hasLost(), "the alpha strike kills on turn 7: " + s.summary());
        Assert.assertEquals(g.getPhaseHandler().getTurn(), 7);
    }
}
