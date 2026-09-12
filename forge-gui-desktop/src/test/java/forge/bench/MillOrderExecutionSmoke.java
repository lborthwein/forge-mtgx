package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.*;
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

/** Native mill movement and top-graveyard consumer; no fixture-side sorting. */
public final class MillOrderExecutionSmoke {
    private static int checks;
    private static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why);checks++; }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        JsonObject observed; String fault="none"; boolean reverse; byte[] pending=new byte[0];int offset;
        private void prepare() {
            if(offset<pending.length)return;
            var ask=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).reduce((a,b)->b).orElseThrow();
            var answer=new JsonObject();answer.addProperty("type","answer");answer.add("id",ask.get("id"));
            var menu=ask.getAsJsonArray("menu");var choices=new JsonArray();
            if(ask.get("kind").getAsString().equals("zoneChange")) {
                check(observed!=null && menu.size()==1,"Shallow Grave has exactly one eligible top creature");
                choices.add(menu.get(0).getAsJsonObject().get("fid"));answer.add("choices",choices);
                pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);offset=0;return;
            }
            check(observed==null,"only one whole-permutation ask");observed=ask.deepCopy();
            check(ask.get("kind").getAsString().equals("orderZone"),"actual mill asks orderZone");
            for(int i=0;i<menu.size();i++) {
                int fid=menu.get(reverse?menu.size()-1-i:i).getAsJsonObject().get("fid").getAsInt();
                if(fault.equals("string"))choices.add(String.valueOf(fid));
                else if(fault.equals("fraction"))choices.add(fid+0.5);
                else choices.add(fault.equals("duplicate")?menu.get(0).getAsJsonObject().get("fid").getAsInt():fid);
            }
            if(fault.equals("unknown"))choices.set(0,new JsonPrimitive(999999));
            if(fault.equals("short"))choices.remove(0);
            if(fault.equals("delegate"))answer.addProperty("delegate",true);
            else if(!fault.equals("missing"))answer.add("choices",choices);
            pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
        }
        @Override public int read(){if(fault.equals("eof"))return -1;prepare();return pending[offset++]&255;}
        @Override public int read(byte[] b,int at,int n){if(n==0)return 0;if(fault.equals("eof"))return -1;prepare();int count=Math.min(n,pending.length-offset);System.arraycopy(pending,offset,b,at,count);offset+=count;return count;}
    }
    private static Card card(String name,Player p,ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(c);return c;
    }
    private static void run(int seat,boolean reverse,String fault) {
        run(seat,reverse,fault,false);
    }
    private static void run(int seat,boolean reverse,String fault,boolean replacement) {
        var host=new Host();host.reverse=reverse;host.fault=fault;
        var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        var lobby=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Mill order").createGame();
        game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);session.setLiveGame(game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var source=card("Palantír of Orthanc",actor,ZoneType.Battlefield);
        if(replacement)card("Dauthi Voidwalker",game.getPlayers().get(1-seat),ZoneType.Battlefield);
        var first=card("Llanowar Elves",actor,ZoneType.Library);var second=card("Grim Lavamancer",actor,ZoneType.Library);
        var mill=AbilityFactory.getAbility("DB$ Mill | Defined$ You | NumCards$ 2",source);mill.setActivatingPlayer(actor);
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(71942);
        try { AbilityUtils.resolve(mill); }
        catch(RuntimeException failure) {
            if(fault.equals("none"))throw failure;
            check(actor.getCardsIn(ZoneType.Library).size()==2,"refusal moves no cards");
            check(actor.getCardsIn(ZoneType.Graveyard).isEmpty(),"refusal does not guess graveyard order");
            check(session.integrityFailure(game)!=null,"failure permanently invalidates run");return;
        }
        check(fault.equals("none"),"malformed permutation accepted");
        check(actor.getCardsIn(ZoneType.Library).isEmpty(),"real mill consumed library");
        if(replacement) {
            check(host.observed!=null,"Forge asks before Dauthi replaces movement");
            check(actor.getCardsIn(ZoneType.Graveyard).isEmpty() && actor.getCardsIn(ZoneType.Exile).size()==2,"native replacement exiles both milled cards");
            System.out.println("MILL_ORDER_REPLACEMENT "+host.observed);return;
        }
        check(actor.getCardsIn(ZoneType.Graveyard).size()==2,"real mill moved both cards");
        String expected=reverse?first.getName():second.getName();
        var shallow=card("Shallow Grave",actor,ZoneType.Hand);var spell=shallow.getFirstSpellAbility();spell.setActivatingPlayer(actor);
        AbilityUtils.resolve(spell);
        check(actor.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals(expected)),"native Shallow Grave consumes chosen top creature");
        var row=new JsonObject();row.addProperty("seat",seat);row.addProperty("reverse",reverse);row.addProperty("returned",expected);row.add("ask",host.observed);
        System.out.println("MILL_ORDER_CASE "+row);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                    (p,m,v)->switch(m.getName()) {
                        case "getAssetsDir"->args[0]+"/forge-gui/";
                        case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                        case "getCurrentVersion"->"mill-order-fixture";
                        default->throw new AssertionError("unexpected GUI: "+m.getName());
                    }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                run(seat,false,"none");run(seat,true,"none");
                run(seat,false,"none",true);
                for(String fault:List.of("delegate","missing","duplicate","unknown","short","string","fraction","eof"))run(seat,false,fault);
            }
            System.out.println("PASS "+checks+" native mill ordering checks; NOT CERTIFIED");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
