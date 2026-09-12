package forge.bench;

import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
import forge.game.player.*;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.collect.FCollection;
import java.io.*;
import java.util.*;

/** Forced branches perform no strategic selection and never call the host/AI. */
public final class ForcedControllerChoiceSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static int bucket(PlayerControllerBridge c,String method,String owner){return c.getCounters().toJson()
        .getAsJsonObject("controllerCoverage").getAsJsonObject("methods").getAsJsonObject(method).get(owner).getAsInt();}
    private static void run(int seat){
        var wire=new ByteArrayOutputStream();var session=new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),wire));
        var lobby=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Forced callbacks").createGame();
        game.setAge(GameStage.Play);session.setLiveGame(game);
        var actor=game.getPlayers().get(seat);var foe=game.getPlayers().get(1-seat);var c=(PlayerControllerBridge)actor.getController();
        CounterType plus=CounterEnumType.P1P1;
        check(c.chooseCounterType(List.of(),null,"counter",null)==null,"empty counter domain matches native null");
        check(c.chooseCounterType(List.of(plus),null,"counter",null)==plus,"singleton returns exact counter type");
        check(foe.getController().chooseCounterType(List.of(),null,"counter",null)==null,"Default empty control");
        check(foe.getController().chooseCounterType(List.of(plus),null,"counter",null)==plus,"Default singleton control");
        var options=new FCollection<Player>();options.add(foe);
        check(c.chooseSingleEntityForEffect(options,null,null,"only defender",false,null,null)==foe,"mandatory singleton returns exact defender");
        check(bucket(c,"chooseCounterType","forced")==2 && bucket(c,"chooseCounterType","stock")==0,"forced counter receipts, no stock authority");
        check(bucket(c,"chooseSingleEntityForEffect","forced")==1,"forced entity receipt");
        check(!wire.toString().contains("\"type\":\"ask\""),"forced branches do not request a policy choice");
        try{c.chooseCounterType(List.of(plus,CounterEnumType.LOYALTY),null,"counter",null);throw new AssertionError("multiple counter types silently chosen");}
        catch(RulesCostFeasibility.Unsupported expected){check(session.integrityFailure(game)!=null,"unimplemented genuine choice invalidates game");}
        check(bucket(c,"chooseCounterType","unclassified")==1 && bucket(c,"chooseCounterType","stock")==0,"unsupported multiple types do not borrow Default");
        try{c.chooseCounterType(List.of(plus),null,"counter",null);throw new AssertionError("failed game resumed");}
        catch(RulesCostFeasibility.Unsupported expected){check(true,"failure latch enforced even for later forced choice");}
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"forced-choice-fixture";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        run(0);run(1);System.out.println("PASS "+checks+" forced controller branch checks; NOT CERTIFIED");System.exit(0);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
