package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane forge-live-fixes-1012: the R3 training crash {@code NullPointerException: Cannot invoke
 * "forge.game.cost.CostPartMana.getMana()" because the return value of "forge.game.cost.Cost.getCostMana()" is null}
 * (7 games; jar JAR-1, fork 7af54b89bfd; the same code at f25c9278782). Every record's stack:
 * {@code SpecialCardAi$YawgmothsWill.consider(SpecialCardAi.java:2126)} &lt;- EffectAi.checkApiLogic &lt;- doTrigger &lt;-
 * {@code PlayerControllerAi.chooseNewTargetsForCopy} &lt;- {@code orderAndPlaySimultaneousSa} &lt;- CopySpellAbilityEffect,
 * and each game's Yawgmoth's Will deck also held Krark, the Thumbless ("copy that spell, and you may choose new
 * targets for the copy"). A copy is not cast, so {@link CardFactory#copySpellAbilityAndPossiblyHost} gives it a cost
 * with no mana part, and the AI's Yawgmoth's Will logic read the copy's mana cost without a null check. A missing
 * mana part now counts as zero mana.
 */
public class YawgmothsWillCopyTest extends AITest {

    /** Yawgmoth's Will in the AI's hand (its turn, main 1) and a Krark-style copy of it (new targets allowed). */
    private SpellAbility[] willAndCopy(final Game game, final Player ai) {
        final Card will = addCardToZone("Yawgmoth's Will", ai, ZoneType.Hand);
        final SpellAbility sa = will.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        final SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(sa, sa, ai);
        copy.setMayChooseNewTargets(true); // Krark's DBCopy: MayChooseTarget$ True
        return new SpellAbility[] {sa, copy};
    }

    @Test
    public void aSpellCopyHasNoManaPart() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility[] s = willAndCopy(game, ai);
        Assert.assertNotNull(s[0].getPayCosts().getCostMana(), "the cast spell has its {2}{B}");
        Assert.assertEquals(s[0].getPayCosts().getCostMana().getMana().getCMC(), 3);
        Assert.assertTrue(s[1].isCopied());
        Assert.assertNull(s[1].getPayCosts().getCostMana(), "a copy is not cast: no mana part (the crash's shape)");
    }

    /** The crash's own path: the AI plays a copy of Yawgmoth's Will it may retarget (the bridge calls the same method). */
    @Test
    public void theAiPlaysACopyOfYawgmothsWillWithoutCrashing() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Memnite", ai, ZoneType.Graveyard); // the logic returns early on an empty graveyard
        }
        final SpellAbility[] s = willAndCopy(game, ai);
        ai.getController().orderAndPlaySimultaneousSa(Lists.newArrayList(s[1]));
        Assert.assertEquals(game.getStack().size(), 1, "the copy is on the stack");
        Assert.assertSame(game.getStack().peekAbility(), s[1]);
    }

    /**
     * The logic's answer for the copy is its answer for the cast spell: here the graveyard's cards cannot be cast from
     * there yet (no Will effect is active while the copy is being put on the stack, as in the crash games: Krark's trigger
     * resolves above the original spell), so both are no, and the copy no longer throws.
     */
    @Test
    public void aCopyGetsTheCastSpellsAnswer() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Swamp", 5, ai);
        for (String n : new String[] {"Memnite", "Grizzly Bears", "Dark Ritual"}) {
            addCardToZone(n, ai, ZoneType.Graveyard);
        }
        final SpellAbility[] s = willAndCopy(game, ai);
        final boolean cast = SpecialCardAi.YawgmothsWill.consider(ai, s[0]);
        Assert.assertEquals(SpecialCardAi.YawgmothsWill.consider(ai, s[1]), cast);
        Assert.assertFalse(cast, "nothing in the graveyard is castable from there yet");
    }
}
