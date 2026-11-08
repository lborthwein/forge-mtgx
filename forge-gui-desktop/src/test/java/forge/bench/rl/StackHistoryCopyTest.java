package forge.bench.rl;

import java.nio.charset.StandardCharsets;
import java.util.List;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** A public cast-history target refers to a pending stack item by its instance id. */
public class StackHistoryCopyTest extends SimulationTest {
    private Card generated(Player p,String name,String ability) {
        forge.item.PaperCard paper=new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of(
                "Name:"+name,"ManaCost:1","Types:Instant","A:"+ability,"Oracle:")),"",forge.card.CardRarity.Common);
        return Card.fromPaperCard(paper,p);
    }
    private SpellAbility push(Game g,Card c,Player p,GameObject target) {
        SpellAbility sa=c.getFirstSpellAbility();sa.setActivatingPlayer(p);sa.getTargets().add(target);
        g.getStackZone().add(c);g.getStack().add(sa);return g.getStack().peekAbility();
    }
    private RlFeaturizer.Obs observe(Game g,RlKnowledge k) {
        CardIndex ix=CardIndex.of("<pad>\t0\n<unk>\t1\nGenerated spell\t2\nGenerated answer\t3\n".getBytes(StandardCharsets.UTF_8));
        RlFeaturizer f=new RlFeaturizer(ix);f.setVersion(2);f.setKnowledge(k);
        return f.observe(g,g.getPlayers().get(0),0,false);
    }
    private boolean historyTargetExists(RlFeaturizer.Obs o) {
        for(int i=0;i<o.R;i++) {
            int src=o.relSrc[i],dst=o.relDst[i];
            if(src>=0&&dst>=0&&o.relType[i]==RlSchemaV2.R_TARGET&&o.tokZone[src]==21&&o.tokZone[dst]==14)return true;
        }
        return false;
    }
    private static final class Fixture {
        final Game game; final RlKnowledge knowledge;
        Fixture(Game g,RlKnowledge k) { game=g;knowledge=k; }
    }
    private Fixture source() {
        Game live=initAndCreateGame();Player a=live.getPlayers().get(0),b=live.getPlayers().get(1);
        live.getPhaseHandler().devModeSet(PhaseType.MAIN1,a);
        RlKnowledge knowledge=new RlKnowledge(live);knowledge.v2=true;knowledge.attach();
        SpellAbility first=push(live,generated(a,"Generated spell","SP$ DealDamage | Cost$ 1 | ValidTgts$ Player | NumDmg$ 1 | SpellDescription$ Deal 1 damage."),a,b);
        push(live,generated(b,"Generated answer","SP$ Counter | Cost$ 1 | ValidTgts$ Spell | SpellDescription$ Counter target spell."),b,first);
        assertEquals(knowledge.tail().size(),2);assertTrue(historyTargetExists(observe(live,knowledge)));
        return new Fixture(live,knowledge);
    }
    private Game copy(Game source,boolean repair) {
        GameCopier copier=new GameCopier(source,true);copier.setCopyStack(true);copier.setCopyFidelity(1);
        copier.setStackHistory(repair);return copier.makeCopy();
    }
    @Test public void offLosesThePublicHistoryTargetWhileOnRemapsItThroughCopies() {
        Fixture f=source();Game live=f.game;RlKnowledge knowledge=f.knowledge;
        Game old=copy(live,false);RlKnowledge off=knowledge.forkFor(old);
        assertFalse(historyTargetExists(observe(old,off)));
        assertEquals(off.tail().get(0).targets.get(0)[0],RlStackFacts.T_STACK);
        assertNotEquals(off.tail().get(0).targets.get(0)[1],RlStackFacts.targetRefs(old,old.getStack().peekAbility()).get(0)[1]);
        int originalTarget=knowledge.tail().get(0).targets.get(0)[1];
        Game fixed=copy(live,true);RlKnowledge on=knowledge.forkFor(fixed);
        assertTrue(historyTargetExists(observe(fixed,on)));assertTrue(fixed.stackHistory);assertFalse(live.stackHistory);
        assertEquals(on.tail().get(0).targets.get(0)[1],RlStackFacts.targetRefs(fixed,fixed.getStack().peekAbility()).get(0)[1]);
        assertNotEquals(on.tail().get(0).targets.get(0)[1],originalTarget,"engine IDs still change normally");
        assertNotSame(on.tail().get(0),knowledge.tail().get(0));
        assertNotSame(on.tail().get(0).targets.get(0),knowledge.tail().get(0).targets.get(0));
        assertEquals(knowledge.tail().get(0).targets.get(0)[1],originalTarget);assertTrue(historyTargetExists(observe(live,knowledge)));
        Game nested=copy(fixed,false);RlKnowledge nestedKnowledge=on.forkFor(nested);
        assertTrue(nested.stackHistory);assertTrue(historyTargetExists(observe(nested,nestedKnowledge)));
        assertEquals(nestedKnowledge.tail().get(0).targets.get(0)[1],RlStackFacts.targetRefs(nested,nested.getStack().peekAbility()).get(0)[1]);
    }
    @Test public void aPastTargetCannotBindToAnUnrelatedEntryWithARecycledScopedId() {
        Fixture f=forge.util.IdScope.detached(() -> {
            Fixture fresh=source();Game live=fresh.game;
            live.getStack().resolveStack();assertTrue(live.getStack().isEmpty());
            Player a=live.getPlayers().get(0),b=live.getPlayers().get(1);
            push(live,generated(a,"Generated spell","SP$ DealDamage | Cost$ 1 | ValidTgts$ Player | NumDmg$ 1 | SpellDescription$ Deal 1 damage."),a,b);
            return fresh;
        });
        assertFalse(historyTargetExists(observe(f.game,f.knowledge)),"the original targeted spell is gone");
        Game old=forge.util.IdScope.detached(() -> copy(f.game,false));
        assertTrue(historyTargetExists(observe(old,f.knowledge.forkFor(old))),"fresh scope reuses the old target's numeric ID");
        Game fixed=forge.util.IdScope.detached(() -> copy(f.game,true));
        RlKnowledge on=f.knowledge.forkFor(fixed);
        assertFalse(historyTargetExists(observe(fixed,on)));
        assertTrue(on.tail().stream().flatMap(e -> e.targets.stream()).anyMatch(t -> t[0]==RlStackFacts.T_STACK&&t[1]==-1));
        assertFalse(f.knowledge.tail().stream().flatMap(e -> e.targets.stream()).anyMatch(t -> t[0]==RlStackFacts.T_STACK&&t[1]==-1));
    }
    @Test public void emptyCopiedStackLeavesNoHistoryTargetBound() {
        Fixture f=source();GameCopier copier=new GameCopier(f.game,true);copier.setStackHistory(true);
        Game copy=copier.makeCopy();assertTrue(copy.getStack().isEmpty());
        assertFalse(historyTargetExists(observe(copy,f.knowledge.forkFor(copy))));
        assertTrue(historyTargetExists(observe(f.game,f.knowledge)));
    }
    @Test public void defaultOffSearchAndSolverConfigurationRoundTrip() {
        assertFalse(new RlSearch.Config().stackHistory);
        assertFalse(new forge.ai.simulation.LookaheadSearch.Config().stackHistory);
        assertFalse(new forge.ai.simulation.ForcedWinSolver.Config().stackHistory);
        com.google.gson.JsonObject j=new com.google.gson.JsonObject();j.addProperty("server","127.0.0.1:1");j.addProperty("stackHistory",true);
        RlSearch.Config c=RlSearch.Config.parse(j);assertTrue(c.stackHistory);assertTrue(c.toJson().get("stackHistory").getAsBoolean());
        assertTrue(forge.ai.simulation.ForcedWinSolver.Config.fromProps(k -> "stackHistory".equals(k)?"true":null).stackHistory);
    }
}
