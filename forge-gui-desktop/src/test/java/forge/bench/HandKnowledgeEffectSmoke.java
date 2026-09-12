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

/** Actual reveal/choose/exile effect and knowledge-boundary negative controls. */
public final class HandKnowledgeEffectSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        byte[] reply=new byte[0];int at,asks;JsonObject observed;
        String fault="none";Runnable beforeAnswer=()->{};
        void prepare(){if(at<reply.length)return;
            observed=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                .filter(o->"ask".equals(o.get("type").getAsString())).reduce((a,b)->b).orElseThrow();asks++;
            check("cardsChoice".equals(observed.get("kind").getAsString()),"only effect card ask");
            var answer=new JsonObject();answer.addProperty("type","answer");answer.add("id",observed.get("id"));
            var ids=new JsonArray();var menu=observed.getAsJsonArray("menu");
            if(!fault.equals("decline"))ids.add(menu.get(menu.size()-1).getAsJsonObject().get("fid"));
            switch(fault){
                case "duplicate"->ids.add(ids.get(0));
                case "unknown"->ids.set(0,new JsonPrimitive(999999));
                case "fraction"->ids.set(0,new JsonPrimitive(1.5));
                case "string"->ids.set(0,new JsonPrimitive(ids.get(0).getAsString()));
                case "overflow"->ids.set(0,new JsonPrimitive(4294967296L));
                default->{}
            }
            answer.add("choices",ids);if(fault.equals("missing"))answer.remove("choices");
            if(fault.equals("delegate"))answer.addProperty("delegate",true);
            beforeAnswer.run();reply=(answer+"\n").getBytes(StandardCharsets.UTF_8);at=0;
        }
        @Override public int read(){if(fault.equals("eof"))return -1;prepare();return reply[at++]&255;}
        @Override public int read(byte[] b,int off,int n){if(n==0)return 0;if(fault.equals("eof"))return -1;prepare();int k=Math.min(n,reply.length-at);System.arraycopy(reply,at,b,off,k);at+=k;return k;}
    }
    private record Context(Game game,Player actor,Player foe,PlayerControllerBridge controller,BenchSession session,Host host){}
    private static Context context(int seat){
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        var lobby=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Hand knowledge").createGame();
        game.setAge(GameStage.Play);session.setLiveGame(game);var actor=game.getPlayers().get(seat);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        return new Context(game,actor,game.getPlayers().get(1-seat),(PlayerControllerBridge)actor.getController(),session,host);
    }
    private static Card card(Context c,Player owner,String name,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);var card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),owner);
        card.setGameTimestamp(c.game.getNextTimestamp());owner.getZone(zone).add(card);return card;
    }
    private static JsonObject state(Context c){try{
        var method=PlayerControllerBridge.class.getDeclaredMethod("envelope",boolean.class);method.setAccessible(true);
        return ((JsonObject)method.invoke(c.controller,true)).getAsJsonObject("state");
    }catch(Exception e){throw new RuntimeException(e);}}
    private static JsonArray hand(JsonObject state,Player owner){return state.getAsJsonArray("players").get(StateEncoder.playerIndex(owner.getGame(),owner)).getAsJsonObject().getAsJsonArray("hand");}
    private static Set<String> names(JsonArray cards){var names=new HashSet<String>();for(var card:cards)names.add(card.getAsJsonObject().get("name").getAsString());return names;}
    private static int bucket(Context c,String method,String owner){return c.controller.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods").getAsJsonObject(method).get(owner).getAsInt();}
    private static void hiddenWorlds(int seat){
        var a=context(seat);var b=context(seat);
        var sourceA=card(a,a.actor,"Elite Spellbinder",ZoneType.Battlefield);
        var sourceB=card(b,b.actor,"Elite Spellbinder",ZoneType.Battlefield);
        card(a,a.foe,"Lightning Bolt",ZoneType.Hand);card(b,b.foe,"Sol Ring",ZoneType.Hand);
        card(a,a.foe,"Plains",ZoneType.Library);card(b,b.foe,"Mountain",ZoneType.Library);
        check(state(a).equals(state(b)),"different unrevealed hand/library identities give byte-equivalent policy state");
        for(var c:List.of(a,b)){
            var source=c==a?sourceA:sourceB;
            var reveal=AbilityFactory.getAbility("DB$ RevealHand | Defined$ Opponent | Look$ True",source);
            reveal.setActivatingPlayer(c.actor);AbilityUtils.resolve(reveal);
        }
        check(!state(a).equals(state(b)),"legitimate reveal makes different hands distinguishable");
        check(names(hand(state(a),a.foe)).equals(Set.of("Lightning Bolt")),"first world reveals only its real hand");
        check(names(hand(state(b),b.foe)).equals(Set.of("Sol Ring")),"second world reveals only its real hand");
        check(!state(a).toString().contains("Plains") && !state(b).toString().contains("Mountain"),"reveal does not disclose library identity");
    }
    private static void knowledge(int seat,boolean views){
        var c=context(seat);var plains=card(c,c.foe,"Plains",ZoneType.Hand);var bolt=card(c,c.foe,"Lightning Bolt",ZoneType.Hand);
        var secret=card(c,c.foe,"Sol Ring",ZoneType.Library);
        check(hand(state(c),c.foe).isEmpty(),"unrevealed hand hidden");
        if(views)c.controller.reveal(List.of(plains.getView(),bolt.getView()),ZoneType.Hand,c.foe.getView(),"look",false);
        else c.controller.reveal(new CardCollection(List.of(plains,bolt)),ZoneType.Hand,c.foe,"look",false);
        check(names(hand(state(c),c.foe)).equals(Set.of("Plains","Lightning Bolt")),"entire revealed hand retained including lands");
        check(hand(StateEncoder.encodeWithStackInstances(c.game,c.actor),c.foe).isEmpty(),"knowledge does not mutate Forge visibility");
        var outsider=new KnownHandObservation(c.actor);var isolated=StateEncoder.encodeWithStackInstances(c.game,c.actor);outsider.augment(isolated);
        check(hand(isolated,c.foe).isEmpty(),"independent observation ledger has no borrowed memory");
        c.controller.resetAtEndOfTurn();
        check(names(hand(state(c),c.foe)).contains("Lightning Bolt"),"legitimate hand knowledge survives AI end-turn memory reset");
        c.game.getAction().moveToHand(secret,null);
        check(hand(state(c),c.foe).size()==2,"new draw not automatically revealed");
        var moved=c.game.getAction().moveToGraveyard(bolt,null);
        check(names(hand(state(c),c.foe)).equals(Set.of("Plains")),"card leaving hand forgotten");
        c.game.getAction().moveToHand(moved,null);
        check(names(hand(state(c),c.foe)).equals(Set.of("Plains")),"returning same physical card is a new hidden hand visit");
        check(bucket(c,"reveal","rules")==1,"hand reveal is observation delivery, not AI choice");
    }
    private static void effect(int seat,String fault){
        var c=context(seat);c.host.fault=fault;
        var source=card(c,c.actor,"Elite Spellbinder",ZoneType.Battlefield);
        var plains=card(c,c.foe,"Plains",ZoneType.Hand);var bolt=card(c,c.foe,"Lightning Bolt",ZoneType.Hand);
        var ring=card(c,c.foe,"Sol Ring",ZoneType.Hand);
        var ability=AbilityFactory.getAbility("DB$ RevealHand | Defined$ Opponent | Look$ True | RememberRevealed$ True | SubAbility$ DBChooseCard",source);
        ability.setActivatingPlayer(c.actor);
        if(fault.equals("stale"))c.host.beforeAnswer=()->c.game.getAction().moveToGraveyard(ring,null);
        boolean invalid=!Set.of("none","decline").contains(fault);
        try{AbilityUtils.resolve(ability);check(!invalid,"invalid effect answer accepted");}
        catch(RuntimeException failure){if(!invalid)throw failure;check(c.session.integrityFailure(c.game)!=null,"effect failure invalidates whole game");}
        if(invalid){
            check(bucket(c,"chooseCardsForEffect","host")==0 && bucket(c,"chooseCardsForEffect","unclassified")==1,"failure never earns host ownership");
            check(c.foe.getCardsIn(ZoneType.Exile).isEmpty(),"invalid host answer never exiles a card");
        }else{
            check(names(hand(c.host.observed.getAsJsonObject("state"),c.foe)).equals(Set.of("Plains","Lightning Bolt","Sol Ring")),"actual reveal effect exports full legal knowledge");
            check(names(c.host.observed.getAsJsonArray("menu")).equals(Set.of("Lightning Bolt","Sol Ring")),"only nonlands selectable");
            check(bucket(c,"chooseCardsForEffect","host")==1 && bucket(c,"chooseCardsForEffect","stock")==0,"actual host owns selection");
            check(fault.equals("decline")?c.foe.getCardsIn(ZoneType.Exile).isEmpty():c.foe.getCardsIn(ZoneType.Exile).stream().anyMatch(x->x.getName().equals("Sol Ring")),"native continuation executes exact selected card or decline");
            check(!plains.isInZone(ZoneType.Exile) && !bolt.isInZone(ZoneType.Exile),"unselected cards remain");
        }
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"hand-knowledge-fixture";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){
            hiddenWorlds(seat);
            knowledge(seat,false);knowledge(seat,true);
            for(String fault:List.of("none","decline","duplicate","unknown","fraction","string","overflow","missing","delegate","eof","stale"))effect(seat,fault);
        }
        System.out.println("PASS "+checks+" hand-knowledge/effect checks; NOT CERTIFIED");System.exit(0);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
