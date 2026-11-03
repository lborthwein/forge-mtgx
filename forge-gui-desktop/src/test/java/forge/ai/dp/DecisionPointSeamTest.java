package forge.ai.dp;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;

/** decisionpoint-1102 PR-C: seam decision points (mana source per symbol, targets at the cast checkpoint). */
public class DecisionPointSeamTest extends AITest {

    /** Casts a {1} artifact with two untapped lands; returns which land got tapped. */
    private String castSolRing(final DecisionAnswerer a, final List<String> seen) {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card island = addCard("Island", p);
        final Card swamp = addCard("Swamp", p);
        final Card ring = addCardToZone("Sol Ring", p, ZoneType.Hand);
        final PlayerControllerAi pc = (PlayerControllerAi) p.getController();
        pc.setDecisionAnswerer((dp, cur) -> {
            if (seen != null) seen.add(dp.ctx() + ":" + dp.optionCount());
            return a == null ? cur : a.answer(dp, cur);
        });
        final SpellAbility sa = ring.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(p, sa, null));
        return island.isTapped() && !swamp.isTapped() ? "Island" : swamp.isTapped() && !island.isTapped() ? "Swamp" : "both/none";
    }

    @Test
    public void manaSourceIsAPointAndAnAnswererCanTapTheOtherLand() {
        final List<String> seen = new ArrayList<>();
        final String forge = castSolRing(null, seen);
        assertTrue(seen.contains("seam:mana:2"), seen.toString());
        assertTrue(forge.equals("Island") || forge.equals("Swamp"), forge);
        final String other = castSolRing((dp, cur) -> {
            if (!"seam:mana".equals(dp.ctx())) return cur;
            for (Object o : dp.options()) if (o != cur) return o;
            return cur;
        }, null);
        assertEquals(other, forge.equals("Island") ? "Swamp" : "Island");
    }

    @Test
    public void anIllegalManaAnswerIsRefused() {
        final String forge = castSolRing(null, null);
        final String refused = castSolRing((dp, cur) -> "seam:mana".equals(dp.ctx()) ? "not a source" : cur, null);
        assertEquals(refused, forge);
    }

    @Test
    public void castTargetsAreAPoint() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        addCard("Mountain", p);
        final Card bear = addCard("Grizzly Bears", opp);
        final Card elf = addCard("Llanowar Elves", opp);
        final Card shock = addCardToZone("Shock", p, ZoneType.Hand);
        final SpellAbility sa = shock.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        sa.resetTargets();
        sa.getTargets().add(bear);
        final PlayerControllerAi pc = (PlayerControllerAi) p.getController();
        final List<String> seen = new ArrayList<>();
        pc.setDecisionAnswerer((dp, cur) -> {
            seen.add(dp.ctx());
            if (!"seam:targets:h".equals(dp.ctx())) return cur;
            final TargetChoices tc = new TargetChoices();
            tc.add(elf);
            return List.of(tc);
        });
        assertTrue(ComputerUtil.handlePlayingSpellAbility(p, sa, null));
        assertTrue(seen.contains("seam:targets:h"), seen.toString());
        assertNotNull(game.getStack().peekAbility());
        final SpellAbility onStack = game.getStack().peekAbility();
        assertTrue(onStack.getTargets().contains(elf), String.valueOf(onStack.getTargets()));
        assertFalse(onStack.getTargets().contains(bear));
    }

    // ------------------------------------------------------------------ review #175: refusals

    @Test
    public void aManaPickThatIsNotAnOfferedSourceIsRefused() {
        final String forge = castSolRing(null, null);
        final String[] opp = new String[1];
        final String refused = castSolRing((dp, cur) -> {
            if (!"seam:mana".equals(dp.ctx())) return cur;
            final Player o = dp.seat().getGame().getPlayers().get(0);
            final Card land = addCard("Forest", o); // the opponent's land: never offered, never legal
            opp[0] = land.getName();
            return land.getManaAbilities().get(0);
        }, null);
        assertNotNull(opp[0]);
        assertEquals(refused, forge);
    }

    @Test
    public void aWrongTypeManaAnswerIsRefused() {
        final String forge = castSolRing(null, null);
        final String refused = castSolRing((dp, cur) -> {
            if (!"seam:mana".equals(dp.ctx())) return cur;
            for (Object o : dp.options()) if (o != cur) return List.of(o); // a list, not a SpellAbility
            return cur;
        }, null);
        assertEquals(refused, forge);
    }

    @Test
    public void aDividedSpellsTargetsWithoutADivisionAreRefused() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        addCard("Mountain", p);
        final Card bear = addCard("Grizzly Bears", opp);
        final Card elf = addCard("Llanowar Elves", opp);
        final Card bolt = addCardToZone("Forked Bolt", p, ZoneType.Hand);
        final SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        assertTrue(sa.isDividedAsYouChoose());
        sa.resetTargets();
        sa.getTargets().add(bear);
        sa.getTargets().add(elf);
        sa.addDividedAllocation(bear, 1);
        sa.addDividedAllocation(elf, 1);
        final List<String> seen = new ArrayList<>();
        ((PlayerControllerAi) p.getController()).setDecisionAnswerer((dp, cur) -> {
            if (!"seam:targets:h".equals(dp.ctx())) return cur;
            seen.add(dp.ctx());
            final TargetChoices tc = new TargetChoices();
            tc.add(elf); // no division map: refused
            return List.of(tc);
        });
        assertTrue(ComputerUtil.handlePlayingSpellAbility(p, sa, null));
        assertEquals(seen.size(), 1);
        final SpellAbility onStack = game.getStack().peekAbility();
        assertTrue(onStack.getTargets().contains(bear) && onStack.getTargets().contains(elf),
                String.valueOf(onStack.getTargets()));
    }

    @Test
    public void xOfZeroIsRefusedWhenXCantBe0() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        addCard("Mountain", p);
        addCard("Mountain", p);
        final Card blaze = addCardToZone("Blaze", p, ZoneType.Hand);
        final SpellAbility sa = blaze.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        sa.putParam("XCantBe0", "True");
        sa.resetTargets();
        sa.getTargets().add(opp);
        sa.setXManaCostPaid(1);
        final List<String> seen = new ArrayList<>();
        ((PlayerControllerAi) p.getController()).setDecisionAnswerer((dp, cur) -> {
            if (!"seam:x:h".equals(dp.ctx())) return cur;
            seen.add(dp.ctx());
            return 0;
        });
        assertTrue(ComputerUtil.handlePlayingSpellAbility(p, sa, null));
        assertEquals(seen, List.of("seam:x:h"));
        assertEquals(game.getStack().peekAbility().getXManaCostPaid(), Integer.valueOf(1));
    }
}
