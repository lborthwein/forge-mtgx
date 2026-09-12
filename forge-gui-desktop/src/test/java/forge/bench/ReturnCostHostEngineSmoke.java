package forge.bench;

import com.google.gson.*;
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

/** Actual RPC return-cost choice: no Default selection or synthetic visitor. */
public final class ReturnCostHostEngineSmoke {
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        String fault;int choices;byte[] pending={};int at;
        void prepare(){
            if(at<pending.length)return;
            var ask=wire.toString(StandardCharsets.UTF_8).lines().map(s->JsonParser.parseString(s).getAsJsonObject())
                .filter(o->o.has("kind")&&"ask".equals(o.get("type").getAsString())).reduce((a,b)->b).orElseThrow();
            var ans=new JsonObject();ans.addProperty("type","answer");ans.add("id",ask.get("id"));
            switch(ask.get("kind").getAsString()) {
                case "priority" -> {
                    int pick=-1;var menu=ask.getAsJsonArray("menu");
                    for(int i=1;i<menu.size();i++) {
                        var row=menu.get(i).getAsJsonObject();
                        if(!"Gush".equals(row.get("source").getAsString()))continue;
                        for(var part:row.getAsJsonObject("cost").getAsJsonArray("parts"))
                            if("CostReturn".equals(part.getAsJsonObject().get("kind").getAsString())){check(pick<0,"unique alternative");pick=i;}
                    }
                    check(pick>0,"actual alternate cast offered");ans.addProperty("choice",pick);
                }
                case "payment" -> {
                    check(ask.getAsJsonObject("cost").getAsJsonArray("shards").isEmpty(),"zero ordinary mana");
                    ans.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);ans.addProperty("x",0);ans.addProperty("lifePaid",0);
                    ans.add("sourceOrder",new JsonArray());ans.add("spend",new JsonArray());
                }
                case "cardsChoice" -> {
                    check("returnCost".equals(ask.get("reason").getAsString()),"typed return choice");
                    check(RulesReturnCostDomain.VERSION.equals(ask.get("returnCostVersion").getAsString()),"version");
                    check(ask.get("afterManaPayment").getAsBoolean(),"payment stage");
                    check(ask.get("min").getAsInt()==2&&ask.get("max").getAsInt()==2,"exact cost count");
                    var menu=ask.getAsJsonArray("menu");check(menu.size()==3,"all three eligible lands exposed");
                    var ids=new JsonArray();ids.add(menu.get(2).getAsJsonObject().get("fid"));ids.add(menu.get(0).getAsJsonObject().get("fid"));
                    if("duplicate".equals(fault))ids.set(1,ids.get(0));
                    if("fraction".equals(fault))ids.set(0,new JsonPrimitive(1.5));
                    if("outside".equals(fault))ids.set(0,new JsonPrimitive(999999));
                    if("short".equals(fault))ids.remove(1);
                    ans.add("choices",ids);if("delegate".equals(fault))ans.addProperty("delegate",true);
                    choices++;
                }
                default -> throw new AssertionError("unexpected RPC: "+ask);
            }
            pending=(ans+"\n").getBytes(StandardCharsets.UTF_8);at=0;
        }
        @Override public int read(){prepare();return pending[at++]&255;}
        @Override public int read(byte[] b,int off,int n){if(n==0)return 0;prepare();int k=Math.min(n,pending.length-at);System.arraycopy(pending,at,b,off,k);at+=k;return k;}
    }
    static void run(int seat,String fault) {
        var host=new Host();host.fault=fault;var channel=new JsonRpcChannel(host,host.wire);var session=new BenchSession(channel);
        var lobby=new LobbyPlayerBridge("Owner",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var players=new ArrayList<RegisteredPlayer>();for(int s=0;s<2;s++)players.add(new RegisteredPlayer(new Deck())
                .setPlayer(s==seat?lobby:GamePlayerUtil.createAiPlayer("Other",s,0,null,"Default")));
        var game=new Match(new GameRules(GameType.Constructed),players,"Host return").createGame();
        var p=game.getPlayers().get(seat);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p);session.setLiveGame(game);
        var lands=new ArrayList<Card>();for(int i=0;i<3;i++)lands.add(SourceDiscardCostEngineSmoke.card("Island",p,ZoneType.Battlefield));
        SourceDiscardCostEngineSmoke.card("Gush",p,ZoneType.Hand);game.getAction().checkStateEffects(true);
        BenchRandomAudit.install(949100+seat);
        BenchActionAudit.beginGame(game,"host-return");game.subscribeToEvents(new BenchMain.EventEmitter(channel,"host-return",game));
        var c=(PlayerControllerBridge)p.getController();var chosen=c.chooseSpellAbilityToPlay();check(chosen.size()==1,"one chosen spell");
        if(fault!=null) {
            try{c.playChosenSpellAbility(chosen.get(0));throw new AssertionError("invalid answer accepted "+fault);}
            catch(RulesCostFeasibility.Unsupported expected){checks++;}
            check(session.integrityFailure(game)!=null,"invalid answer taints session");
            check(p.getCardsIn(ZoneType.Battlefield).size()==3,"invalid answer returned no lands");
            return;
        }
        check(c.playChosenSpellAbility(chosen.get(0)),"real Gush cast");
        check(host.choices==1,"one actual host cost selection");
        check(p.getCardsIn(ZoneType.Battlefield).size()==1&&p.getCardsIn(ZoneType.Battlefield).contains(lands.get(1)),"exact unselected land remains");
        check(p.getCardsIn(ZoneType.Hand).size()==2,"exact two selected lands returned");
        BenchActionAudit.finishGame(game,session);check(session.integrityFailure(game)==null,"exact native action receipt");
        var methods=c.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
        check(methods.getAsJsonObject("chooseReturnForCost").get("host").getAsInt()==1,"return selection HOST owned");
        for(var row:methods.entrySet()){var b=row.getValue().getAsJsonObject();check(b.get("stock").getAsInt()==0&&b.get("unclassified").getAsInt()==0,"no missing/delegated callback "+row.getKey());}
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (p,m,v)->switch(m.getName()){
                    case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"host-return";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){run(seat,null);for(String fault:List.of("duplicate","fraction","outside","short","delegate"))run(seat,fault);}
        System.out.println("PASS "+checks+" host return cost checks; not playing strength");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
