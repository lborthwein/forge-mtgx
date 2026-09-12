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

/** Real controlled activation -> native cost -> stack -> library choice/resolution. */
public final class SelfSacrificeExecutionSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static Card card(String name,Player p,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(c);c.setSickness(false);return c;
    }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();final List<String> kinds=new ArrayList<>();
        int source,target,answered,offset;boolean failToFind;byte[] pending=new byte[0];
        private void prepare(){if(offset<pending.length)return;
            var ask=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).reduce((a,b)->b).orElseThrow();
            int id=ask.get("id").getAsInt();check(id>answered,"new host request");
            String kind=ask.get("kind").getAsString();kinds.add(kind);var answer=new JsonObject();
            answer.addProperty("type","answer");answer.addProperty("id",id);
            if(kind.equals("priority")||kind.equals("entityChoice")) {
                int wanted=kind.equals("priority")?source:target,chosen=-1;
                var menu=ask.getAsJsonArray("menu");
                for(int i=0;i<menu.size();i++){var row=menu.get(i).getAsJsonObject();if(row.has("fid")&&row.get("fid").getAsInt()==wanted)chosen=i;}
                check(chosen>=0,"exact desired source/basic offered: "+ask);answer.addProperty("choice",chosen);
            } else if(kind.equals("zoneChange")) {
                check(ask.get("min").getAsInt()==0&&ask.get("max").getAsInt()==1,"search permits failure to find");
                check(ask.getAsJsonArray("menu").asList().stream().anyMatch(r->r.getAsJsonObject().get("fid").getAsInt()==target),"desired basic in actual native search menu");
                var ids=new JsonArray();if(!failToFind)ids.add(target);answer.add("choices",ids);
            } else if(kind.equals("confirm")) {
                check(failToFind&&ask.get("mode").getAsString().equals("ChangeZoneGeneral"),"native confirmation only for intentional failure to find");
                answer.addProperty("yes",true);
            } else if(kind.equals("payment")) {
                check(ask.getAsJsonObject("cost").getAsJsonArray("shards").isEmpty(),"fetch requires no mana tokens");
                answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);answer.addProperty("x",0);
                answer.addProperty("lifePaid",ask.getAsJsonObject("cost").get("life").getAsInt());
                answer.add("sourceOrder",new JsonArray());answer.add("spend",new JsonArray());
            } else throw new AssertionError("unexpected fetch decision "+ask);
            answered=id;pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
        }
        @Override public int read(){prepare();return pending[offset++]&255;}
        @Override public int read(byte[] b,int off,int len){if(len==0)return 0;prepare();int n=Math.min(len,pending.length-offset);System.arraycopy(pending,offset,b,off,n);offset+=n;return n;}
    }
    private static void run(int seat,String name,int otherLands,boolean entersTapped,int life) {
        run(seat,name,otherLands,entersTapped,life,false);
    }
    private static void run(int seat,String name,int otherLands,boolean entersTapped,int life,boolean failToFind) {
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        var lobby=new LobbyPlayerBridge("Fetcher",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Self sacrifice fixture").createGame();
        var p=game.getPlayers().get(seat);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p);session.setLiveGame(game);
        var fetch=card(name,p,ZoneType.Battlefield);for(int i=0;i<otherLands;i++)card("Mountain",p,ZoneType.Battlefield);
        var basic=card("Plains",p,ZoneType.Library);card("Island",p,ZoneType.Library);card("Flickerwisp",p,ZoneType.Library);
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();
        host.source=fetch.getId();host.target=basic.getId();host.failToFind=failToFind;BenchRandomAudit.install(91680+seat);BenchActionAudit.beginGame(game,"fetch-"+seat+"-"+name);
        game.subscribeToEvents(new BenchMain.EventEmitter(new JsonRpcChannel(InputStream.nullInputStream(),new ByteArrayOutputStream()),"fetch",game));
        var ability=fetch.getSpellAbilities().stream().filter(a->a.getApi()==forge.game.ability.ApiType.ChangeZone).findFirst().orElseThrow();
        ability.setActivatingPlayer(p);
        var before=RulesCostFeasibilityEngineSmoke.state(game);var rng=BenchRandomAudit.begin();
        check(RulesCostFeasibility.assess(p,ability).status()==RulesCostFeasibility.Status.PAYABLE,"actual fetch cost payable");
        check(before.equals(RulesCostFeasibilityEngineSmoke.state(game)),"feasibility leaves state unchanged");BenchRandomAudit.assertUnchanged(rng,"fetch feasibility");
        fetch.setTapped(true);check(RulesCostFeasibility.assess(p,ability).status()==RulesCostFeasibility.Status.UNPAYABLE,"tapped fetch cannot repay tap cost");fetch.setTapped(false);
        if(life>0){p.setLife(0,null);check(RulesCostFeasibility.assess(p,ability).status()==RulesCostFeasibility.Status.UNPAYABLE,"life payment cannot exceed total");p.setLife(20,null);}
        var selected=p.getController().chooseSpellAbilityToPlay();check(selected!=null&&selected.size()==1&&selected.get(0).getHostCard().getId()==fetch.getId(),"actual host-selected fetch activation");
        check(p.getController().playChosenSpellAbility(selected.get(0)),"actual controlled fetch cost execution");
        check(game.getStack().size()==1,"fetch ability uses stack");
        check(p.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->c.getId()==fetch.getId()),"exact fetch sacrificed before resolution");
        check(p.getCardsIn(ZoneType.Library).stream().anyMatch(c->c.getId()==basic.getId()),"basic still in library before resolution");
        check(p.getLife()==20-life&&p.getManaPool().totalMana()==0,"exact life and no invented mana");
        check(host.kinds.equals(List.of("priority","payment")),"search choice does not precede cost payment");
        game.getStack().resolveStack();game.getAction().checkStateEffects(true);
        var moved=p.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.getId()==basic.getId()).findFirst().orElse(null);
        check(failToFind?moved==null:moved!=null&&moved.isTapped()==entersTapped,"fetched basic final tapped state or lawful failure to find");
        check(p.getCardsIn(ZoneType.Library).size()==(failToFind?3:2)&&game.getStack().isEmpty(),"only selected basic removed, ability resolved");
        check(host.kinds.equals(failToFind?List.of("priority","payment","zoneChange","confirm"):List.of("priority","payment","zoneChange")),"one actual host library choice with native cancel confirmation if needed");
        check(session.integrityFailure(game)==null,"no swallowed bridge failure");BenchActionAudit.finishGame(game,session);
        System.out.println("FETCH "+name+" seat="+seat+" otherLands="+otherLands+" tapped="+(moved==null?"none":moved.isTapped())+" lifePaid="+life);
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"self-sacrifice";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){run(seat,"Fabled Passage",2,true,0);run(seat,"Fabled Passage",3,false,0);run(seat,"Evolving Wilds",3,true,0);run(seat,"Arid Mesa",0,false,1);run(seat,"Fabled Passage",3,false,0,true);}
        System.out.println("PASS "+checks+" actual fetch execution checks; NOT CERTIFIED");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
