package forge.ai.simulation;

import java.util.List;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** A stack entry is an activation, not the reusable ability template attached to its card. */
public class StackAbilityIsolationTest extends SimulationTest {
    private Game source(boolean twice) {
        Game g=initAndCreateGame(); Player owner=g.getPlayers().get(0), other=g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,owner);
        forge.item.PaperCard paper=new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of(
                "Name:Generated source", "ManaCost:1", "Types:Artifact",
                "A:AB$ DealDamage | Cost$ 0 | ValidTgts$ Player | NumDmg$ 1 | SpellDescription$ Deal 1 damage to target player.",
                "Oracle:")),"",forge.card.CardRarity.Common);
        Card c=Card.fromPaperCard(paper,owner);owner.getZone(ZoneType.Battlefield).add(c);
        c.setGameTimestamp(g.getNextTimestamp());
        SpellAbility template=c.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        SpellAbility first=template.copy(owner); first.getTargets().add(other);g.getStack().add(first);
        if(twice) { SpellAbility second=template.copy(owner);second.getTargets().add(owner);g.getStack().add(second); }
        assertNull(GameCopier.stackUnsupported(g)); return g;
    }
    private Game copy(Game g, boolean enabled) { GameCopier c=new GameCopier(g,true);c.setCopyStack(true);c.setCopyFidelity(1);c.setStackAbilityCopies(enabled);return c.makeCopy(); }
    @Test public void offAliasesTemplateWhileOnRetainsActivatorDuringMenuEnumeration() {
        Game live=source(false),old=copy(live,false);SpellAbility activation=old.getStack().peekAbility();
        assertSame(activation.getActivatingPlayer(),old.getPlayers().get(0));
        activation.getHostCard().getAllPossibleAbilities(old.getPlayers().get(1),false);
        assertSame(activation.getActivatingPlayer(),old.getPlayers().get(1));
        Game fixed=copy(live,true); SpellAbility on=fixed.getStack().peekAbility();
        on.getHostCard().getAllPossibleAbilities(fixed.getPlayers().get(1),false);
        assertSame(on.getActivatingPlayer(),fixed.getPlayers().get(0));
        assertSame(on.getTargets().getFirstTargetedPlayer(),fixed.getPlayers().get(1));
        assertFalse(on.getHostCard().getSpellAbilities().stream().anyMatch(sa -> sa==on));
        assertSame(on.getHostCard().getGame(),fixed); assertFalse(live.stackAbilityCopies);
        live.getStack().peekAbility().getHostCard().getAllPossibleAbilities(live.getPlayers().get(1),false);
        assertSame(live.getStack().peekAbility().getActivatingPlayer(),live.getPlayers().get(0));
    }
    @Test public void offCollapsesStackTargetsWhileOnKeepsIndependentActivations() {
        Game live=source(true),old=copy(live,false);
        var it=old.getStack().iterator();SpellAbility top=it.next().getSpellAbility(),bottom=it.next().getSpellAbility();
        assertSame(top,bottom); assertSame(top.getTargets().getFirstTargetedPlayer(),old.getPlayers().get(0));
        var original=live.getStack().iterator();assertNotSame(original.next().getSpellAbility(),original.next().getSpellAbility());
        Game fixed=copy(live,true); var entries=fixed.getStack().iterator();
        SpellAbility upper=entries.next().getSpellAbility(),lower=entries.next().getSpellAbility();
        assertNotSame(upper,lower);
        assertSame(upper.getHostCard(),lower.getHostCard());
        assertSame(upper.getTargets().getFirstTargetedPlayer(),fixed.getPlayers().get(0));
        assertSame(lower.getTargets().getFirstTargetedPlayer(),fixed.getPlayers().get(1));
        Game nested=copy(fixed,false); assertTrue(nested.stackAbilityCopies);
        var nestedEntries=nested.getStack().iterator();
        SpellAbility nestedTop=nestedEntries.next().getSpellAbility(),nestedBottom=nestedEntries.next().getSpellAbility();
        assertNotSame(nestedTop,nestedBottom);
        assertSame(nestedTop.getTargets().getFirstTargetedPlayer(),nested.getPlayers().get(0));
        assertSame(nestedBottom.getTargets().getFirstTargetedPlayer(),nested.getPlayers().get(1));
        int life0=fixed.getPlayers().get(0).getLife(),life1=fixed.getPlayers().get(1).getLife();
        fixed.getStack().resolveStack(); fixed.getStack().resolveStack();
        assertEquals(fixed.getPlayers().get(0).getLife(),life0-1);
        assertEquals(fixed.getPlayers().get(1).getLife(),life1-1);
        assertEquals(live.getPlayers().get(0).getLife(),life0);
        assertEquals(live.getPlayers().get(1).getLife(),life1);
    }
    @Test public void targetCollectionsAreIndependentOfTheTemplate() {
        Game fixed=copy(source(false),true); SpellAbility on=fixed.getStack().peekAbility();
        SpellAbility template=on.getHostCard().getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        template.resetTargets(); template.getTargets().add(fixed.getPlayers().get(0));
        assertSame(on.getTargets().getFirstTargetedPlayer(),fixed.getPlayers().get(1));
        on.resetTargets(); assertSame(template.getTargets().getFirstTargetedPlayer(),fixed.getPlayers().get(0));
    }
    @Test public void defaultOffSearchAndSolverConfigurationRoundTrip() {
        org.testng.Assert.assertEquals(new forge.bench.rl.RlSearch.Config().stackAbilityCopies, LookaheadSearch.LIVE_STACK_ABILITY_COPIES); // live-fixes-1107
        org.testng.Assert.assertEquals(new LookaheadSearch.Config().stackAbilityCopies, LookaheadSearch.LIVE_STACK_ABILITY_COPIES); // live-fixes-1107
        assertFalse(new ForcedWinSolver.Config().stackAbilityCopies);
        com.google.gson.JsonObject j=new com.google.gson.JsonObject();
        j.addProperty("server","127.0.0.1:1"); j.addProperty("stackAbilityCopies",true);
        forge.bench.rl.RlSearch.Config c=forge.bench.rl.RlSearch.Config.parse(j);
        assertTrue(c.stackAbilityCopies); assertTrue(c.toJson().get("stackAbilityCopies").getAsBoolean());
        LookaheadSearch.Config l=new LookaheadSearch.Config(); l.stackAbilityCopies=true;
        assertTrue(l.toJson().get("stackAbilityCopies").getAsBoolean());
        assertTrue(ForcedWinSolver.Config.fromProps(k -> "stackAbilityCopies".equals(k)?"true":null).stackAbilityCopies);
    }
}
