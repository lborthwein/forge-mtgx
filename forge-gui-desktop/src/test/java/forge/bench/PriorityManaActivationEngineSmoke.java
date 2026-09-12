package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real host RPC -> priority selection -> payment -> native immediate mana.
 * Scripted legal decisions are capability evidence, never playing strength. */
public final class PriorityManaActivationEngineSmoke {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static Card card(String name,Player p,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);
        Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name),name),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(c);c.setSickness(false);return c;
    }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        String source,color,fault;int answers;byte[] pending={};int at;
        private void prepare(){
            if(at<pending.length)return;
            var asks=wire.toString(StandardCharsets.UTF_8).lines().map(s->JsonParser.parseString(s).getAsJsonObject())
                .filter(o->o.has("kind")&&"ask".equals(o.get("type").getAsString())).toList();
            var ask=asks.get(asks.size()-1);var answer=new JsonObject();
            if("priority".equals(ask.get("kind").getAsString())) {
                check(ask.has("priorityManaVersion")&&PriorityManaActivation.VERSION.equals(ask.get("priorityManaVersion").getAsString()),"explicit executable mana capability");
                if(fault==null)System.out.println("PRIORITY_MANA_CASE "+ask);
                var menu=ask.getAsJsonArray("menu");int index=-1,output=-1;
                for(int i=1;i<menu.size();i++) {
                    var row=menu.get(i).getAsJsonObject();
                    if(!row.get("isManaAbility").getAsBoolean() || !source.equals(row.get("source").getAsString()))continue;
                    check(row.has("manaActivation"),"executable mana has typed output domain");
                    var domain=row.getAsJsonObject("manaActivation");check(PriorityManaActivation.VERSION.equals(domain.get("version").getAsString()),"output version");
                    var outputs=domain.getAsJsonArray("outputs");
                    for(int j=0;j<outputs.size();j++)if(color.equals(outputs.get(j).getAsJsonObject().get("choice").getAsString())) {
                        check(index<0,"unambiguous fixture ability/output");index=i;output=j;
                    }
                }
                check(index>0,"standalone mana action offered for "+source);
                answer.addProperty("choice",index);
                if(!"missing".equals(fault))answer.addProperty("manaOutput","bad-index".equals(fault)?999:output);
                if("fraction".equals(fault))answer.addProperty("manaOutput",0.5);
            } else if("payment".equals(ask.get("kind").getAsString())) {
                var cost=ask.getAsJsonObject("cost");
                answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);answer.add("x",cost.get("x"));
                answer.add("lifePaid",cost.get("life"));answer.add("sourceOrder",new JsonArray());var spend=new JsonArray();
                var shards=cost.getAsJsonArray("shards");var pool=ask.getAsJsonArray("pool");
                for(int i=0;i<shards.size();i++) {
                    check("GENERIC".equals(shards.get(i).getAsString()),"fixture payment is generic");
                    var allocation=new JsonObject();allocation.add("token",pool.get(i).getAsJsonObject().get("id"));
                    allocation.addProperty("shardIndex",i);spend.add(allocation);
                }
                answer.add("spend",spend);
            } else throw new AssertionError("unexpected host choice: "+ask);
            answer.addProperty("type","answer");answer.add("id",ask.get("id"));answers++;
            pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);at=0;
        }
        @Override public int read(){prepare();return pending[at++]&255;}
        @Override public int read(byte[] b,int off,int len){if(len==0)return 0;prepare();int n=Math.min(len,pending.length-at);System.arraycopy(pending,at,b,off,n);at+=n;return n;}
    }
    private static void run(int seat,String name,String color,int count,String fault){
        Host host=new Host();var channel=new JsonRpcChannel(host,host.wire);var session=new BenchSession(channel);
        var lobby=new LobbyPlayerBridge("Owner",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var players=new ArrayList<RegisteredPlayer>();
        for(int s=0;s<2;s++)players.add(new RegisteredPlayer(new Deck()).setPlayer(s==seat?lobby:GamePlayerUtil.createAiPlayer("Other",s,0,null,"Default")));
        var game=new Match(new GameRules(GameType.Constructed),players,"Priority mana fixture").createGame();
        var p=game.getPlayers().get(seat);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p);session.setLiveGame(game);
        boolean star=name.equals("Chromatic Star"),kinnan=name.equals("Basalt Monolith");
        Card source=card(name,p,ZoneType.Battlefield);if(kinnan)card("Kinnan, Bonder Prodigy",p,ZoneType.Battlefield);
        if(star){card("Island",p,ZoneType.Battlefield);card("Gush",p,ZoneType.Library);card("Thassa's Oracle",p,ZoneType.Library);}
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(948156+seat);
        BenchActionAudit.beginGame(game,"priority-mana-"+seat+"-"+name);
        game.subscribeToEvents(new BenchMain.EventEmitter(channel,"priority-mana",game));
        if(star){host.source="Island";host.color="";play(p);check(p.getManaPool().totalMana()==1,"float before Star");}
        host.source=name;host.color=color;host.fault=fault;
        var before=BenchMenuStateAudit.capture(game);var rng=BenchRandomAudit.begin();
        ((PlayerControllerBridge)p.getController()).probePriorityMenuPurity();
        BenchMenuStateAudit.assertUnchanged(before,game);BenchRandomAudit.assertUnchanged(rng,"priority mana menu");
        if(fault!=null) {
            try {play(p);throw new AssertionError("invalid mana output accepted");}
            catch(RulesCostFeasibility.Unsupported expected){check(expected.getMessage().contains("priority mana"),"specific output refusal");}
            check(!source.isTapped()&&p.getManaPool().totalMana()==0,"rejected output pays nothing");
            BenchActionAudit.finishGame(game);return;
        }
        play(p);check(p.getManaPool().totalMana()==count,"exact total production for "+name);
        check(game.getStack().isEmpty(),"mana action has no stack entry");
        if(name.equals("Lotus Petal")||star)check(p.getCardsIn(ZoneType.Battlefield).stream().noneMatch(c->c.getId()==source.getId()),"exact sacrificed source");
        else check(source.isTapped(),"native tap cost");
        if(name.equals("Mana Confluence"))check(p.getLife()==19,"native life cost");
        if(star){
            check(p.getCardsIn(ZoneType.Hand).isEmpty(),"Star draw not resolved during mana production");
            check(game.getStack().hasSimultaneousStackEntries(),"Star trigger retained");
            check(game.getStack().addAllTriggeredAbilitiesToStack(),"Star trigger enters real stack");
            game.getStack().resolveStack();check(p.getCardsIn(ZoneType.Hand).size()==1&&p.getCardsIn(ZoneType.Hand).get(0).getName().equals("Gush"),"Star draws exact next card");
        }
        BenchActionAudit.finishGame(game,session);check(session.integrityFailure(game)==null,"complete action receipts");
        var methods=((PlayerControllerBridge)p.getController()).getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
        for(var row:methods.entrySet()) {
            var buckets=row.getValue().getAsJsonObject();check(buckets.get("stock").getAsInt()==0&&buckets.get("unclassified").getAsInt()==0,"no delegated decision: "+row.getKey());
        }
        System.out.println("PASS seat="+seat+" source="+name+" pool="+count);
    }
    private static void play(Player p){
        var selected=p.getController().chooseSpellAbilityToPlay();check(selected!=null&&selected.size()==1,"one host action");
        var actual=selected.get(0);check(p.getController().playChosenSpellAbility(actual),"native action executed");
        check(actual.getManaPart().getExpressChoice().isEmpty(),"native effect consumes express output choice");
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"priority-mana";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++) {
            run(seat,"Island","",1,null);run(seat,"Lotus Petal","U",1,null);run(seat,"Chromatic Star","U",1,null);
            run(seat,"Mana Confluence","W",1,null);run(seat,"Basalt Monolith","",4,null);run(seat,"Boros Garrison","",2,null);
            for(String fault:List.of("missing","bad-index","fraction"))run(seat,"Lotus Petal","U",1,fault);
        }
        System.out.println("PASS "+checks+" priority mana capability checks; not playing strength");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
