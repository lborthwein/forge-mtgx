package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Real keyword identities and paid/resolved level-up actions, both seats. */
public final class LevelUpIdentityEngineSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static Card card(String name,Player p){
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(ZoneType.Battlefield).add(c);c.setSickness(false);return c;
    }
    private static void run(int seat,String name){
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),players,"Level-up identity").createGame();
        var p=g.getPlayers().get(seat);g.setAge(GameStage.Play);g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p);
        var source=card(name,p);card("Forest",p);card("Plains",p);
        g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        var sa=source.getSpellAbilities().stream().filter(a->a.isKeyword(forge.game.keyword.Keyword.LEVEL_UP)).findFirst().orElseThrow();
        sa.setActivatingPlayer(p);
        var before=BenchMenuStateAudit.capture(g);var rng=BenchRandomAudit.begin();
        var option=StateEncoder.encodePriorityAbilityWithTargetDomains(sa,p.getView());
        var identity=option.getAsJsonObject("activationIdentity");
        check(identity.get("version").getAsString().equals("priority-activation-identity-v4"),"expected semantic level-up identity");
        check(identity.get("kind").getAsString().equals("intrinsic-level-up"),"actual keyword identity");
        check(identity.get("counter").getAsString().equals("level")&&identity.get("amount").getAsInt()==1&&identity.get("sorceryOnly").getAsBoolean(),"exact level-up semantics");
        BenchMenuStateAudit.assertUnchanged(before,g);BenchRandomAudit.assertUnchanged(rng,"level-up encoding");
        var row=new JsonObject();row.addProperty("name",name);row.addProperty("seat",seat);row.add("state",StateEncoder.encodeWithStackInstances(g,p));row.add("option",option);
        var assessed=RulesCostFeasibility.assess(p,sa);check(assessed.status()==RulesCostFeasibility.Status.PAYABLE,"actual level-up payable");
        var paymentSources=new JsonArray();
        for(var choice:assessed.witness().sources())paymentSources.add(choice.ability().getHostCard().getId());
        row.add("paymentSources",paymentSources);System.out.println("LEVEL_UP_CASE "+row);
        var payment=new RulesPaymentExecutor(p,sa,assessed.witness());
        p.dangerouslySetController(new forge.ai.PlayerControllerAi(g,p,p.getLobbyPlayer()){
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost,forge.game.cost.CostPartMana part,SpellAbility actual,String prompt,forge.game.mana.ManaConversionMatrix matrix,boolean effect){
                check(matrix==null,"no conversion");return payment.pay(cost,part,actual,effect);
            }
        });
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(p,sa,null,payment::decisions),"real level-up activation paid");payment.assertPaid();
        check(g.getStack().size()==1&&source.getCounters(CounterEnumType.LEVEL)==0,"counter not granted before resolution");
        g.getStack().resolveStack();g.getAction().checkStateEffects(true);
        check(source.getCounters(CounterEnumType.LEVEL)==1&&g.getStack().isEmpty(),"native resolution adds exactly one level");
        for(var entry:Map.of("CounterType","P1P1","CounterNum","2","Defined","Opponent","SorcerySpeed","False","Optional","True").entrySet()){
            String old=sa.getParam(entry.getKey());sa.putParam(entry.getKey(),entry.getValue());
            check(PriorityActivationIdentity.encode(sa).get("kind").getAsString().equals("unsupported"),"mutated keyword refuses: "+entry.getKey());
            if(old==null)sa.removeParam(entry.getKey());else sa.putParam(entry.getKey(),old);
        }
        check(PriorityActivationIdentity.encode(sa).get("kind").getAsString().equals("intrinsic-level-up"),"restore exact script");
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"level-up-identity";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});BenchRandomAudit.install(94101);
        for(int seat=0;seat<2;seat++)for(String name:List.of("Joraga Treespeaker","Hexdrinker","Student of Warfare"))run(seat,name);
        System.out.println("PASS "+checks+" level-up identity/execution checks; not a strength result");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
