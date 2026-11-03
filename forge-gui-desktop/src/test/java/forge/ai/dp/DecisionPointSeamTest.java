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
}
