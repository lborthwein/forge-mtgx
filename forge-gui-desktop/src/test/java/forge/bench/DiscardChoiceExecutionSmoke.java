package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.*;
import forge.game.card.*;
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

/** Real DiscardEffect continuation, exact instance selection and visibility. */
public final class DiscardChoiceExecutionSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        String fault="none";int seen,at,asks;byte[] reply=new byte[0];JsonObject request;
        Runnable beforeAnswer=()->{};
        private void prepare(){if(at<reply.length)return;
            String fresh=wire.toString(StandardCharsets.UTF_8).substring(seen);seen=wire.size();
            for(String line:fresh.split("\n")){if(line.isBlank())continue;var o=JsonParser.parseString(line).getAsJsonObject();
                if("ask".equals(o.get("type").getAsString()))request=o;}
            check(request!=null,"host received discard ask");asks++;
            check("native-discard-v1".equals(request.get("discardVersion").getAsString()),"strict discard version");
            var out=new JsonObject();out.addProperty("type","answer");out.add("id",request.get("id"));
            var ids=new JsonArray();var menu=request.getAsJsonArray("menu");
            if(!fault.equals("empty"))ids.add(menu.get(menu.size()-1).getAsJsonObject().get("fid"));
            switch(fault){
                case "duplicate"->ids.add(ids.get(0));
                case "unknown"->ids.set(0,new JsonPrimitive(99999999));
                case "string"->ids.set(0,new JsonPrimitive(ids.get(0).getAsString()));
                case "fraction"->ids.set(0,new JsonPrimitive(1.5));
                case "overflow"->ids.set(0,new JsonPrimitive(4294967296L));
                default->{}
            }
            out.add("choices",ids);
            if(fault.equals("missing"))out.remove("choices");
            if(fault.equals("delegate"))out.addProperty("delegate",true);
            beforeAnswer.run();reply=(out+"\n").getBytes(StandardCharsets.UTF_8);at=0;
        }
        @Override public int read(){if(fault.equals("eof"))return -1;prepare();return reply[at++]&255;}
        @Override public int read(byte[] b,int off,int n){if(n==0)return 0;if(fault.equals("eof"))return -1;prepare();int k=Math.min(n,reply.length-at);System.arraycopy(reply,at,b,off,k);at+=k;return k;}
    }
    private static Card card(Game g,Player p,String name,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(g.getNextTimestamp());if(zone==ZoneType.Stack)g.getStackZone().add(c);else p.getZone(zone).add(c);return c;
    }
    private static void run(int seat,String fault,boolean foreign,boolean forced){
        var host=new Host();host.fault=fault;var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        var lobby=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var g=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Discard execution").createGame();
        g.setAge(GameStage.Play);session.setLiveGame(g);var actor=g.getPlayers().get(seat);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var discarded=g.getPlayers().get(foreign?1-seat:seat);var controller=(PlayerControllerBridge)actor.getController();
        var source=card(g,actor,"Kolaghan's Command",ZoneType.Stack);
        var first=card(g,discarded,"Plains",ZoneType.Hand);
        var second=forced?first:card(g,discarded,"Sol Ring",ZoneType.Hand);
        var visible=new CardCollection(discarded.getCardsIn(ZoneType.Hand));
        var valid=new CardCollection(visible);
        if(foreign)visible.add(card(g,discarded,"Mountain",ZoneType.Hand));
        var ability=AbilityFactory.getAbility("DB$ Discard | Defined$ You | Mode$ "+(foreign?"LookYouChoose":"TgtChoose")+" | NumCards$ 1",source);
        ability.setActivatingPlayer(actor);
        if(fault.equals("stale"))host.beforeAnswer=()->discarded.getZone(ZoneType.Hand).remove(second);
        Runnable action=foreign?()->{
            var chosen=controller.chooseCardsToDiscardFrom(discarded,ability,valid,1,1,visible);
            check(chosen.size()==1 && chosen.get(0)==second,"foreign revealed hand selection retains exact instance");
        }:()->AbilityUtils.resolve(ability);
        boolean failure=!fault.equals("none");
        try{action.run();check(!failure,"invalid discard response accepted");}
        catch(RuntimeException e){if(!failure)throw e;check(session.integrityFailure(g)!=null,"failed discard latches game invalidity");}
        var bucket=controller.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods")
            .getAsJsonObject("chooseCardsToDiscardFrom");
        check(bucket.get("stock").getAsInt()==0,"no stock discard authority");
        if(failure){
            check(bucket.get("unclassified").getAsInt()==1 && bucket.get("host").getAsInt()==0,"failed answer never earns host ownership");
            check(discarded.getCardsIn(ZoneType.Graveyard).isEmpty(),"invalid answer never discards");
            int asked=host.asks;host.fault="none";
            try{controller.chooseCardsToDiscardFrom(discarded,ability,valid,1,1,visible);throw new AssertionError("latched failure resumed");}
            catch(RuntimeException e){check(host.asks==asked,"latched failure does not reask");}
        }else{
            check(bucket.get(forced?"forced":"host").getAsInt()==1,"successful branch has exact ownership");
            if(!foreign)check(discarded.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->c.getId()==second.getId()),"actual native discard moved selected card");
            if(forced)check(host.asks==0,"forced discard never queries a policy");
            if(foreign)check(host.request.getAsJsonArray("visibleCards").size()==3 && host.request.getAsJsonArray("menu").size()==2,"revealed ineligible card visible but not selectable");
        }
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"discard-execution-fixture";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){
            for(String fault:List.of("none","missing","duplicate","unknown","string","fraction","overflow","empty","delegate","eof","stale"))run(seat,fault,false,false);
            run(seat,"none",false,true);run(seat,"none",true,false);
        }
        System.out.println("PASS "+checks+" discard execution checks; NOT CERTIFIED");System.exit(0);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
