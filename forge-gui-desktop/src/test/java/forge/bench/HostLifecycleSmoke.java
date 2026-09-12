package forge.bench;

import com.google.gson.JsonObject;
import forge.ai.PlayerControllerAi;
import forge.ai.AiCardMemory.MemorySet;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.util.*;
import static forge.bench.CombatDeclarationExecutionSmoke.*;

/** Lifecycle behavior/side effects, not a playing-strength experiment. */
public final class HostLifecycleSmoke {
    private static int checks;
    private static void verify(boolean ok,String why) { if(!ok)throw new AssertionError(why);checks++; }
    private static JsonObject rng() { return ((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot(); }
    private static void live(int seat,boolean initialProfile) throws Exception {
        var c=context(seat);var card=card(c,c.actor(),"Grizzly Bears");
        var profile=PlayerControllerAi.class.getDeclaredField("pilotsNonAggroDeck");profile.setAccessible(true);
        profile.setBoolean(c.controller(),initialProfile);
        BenchRandomAudit.install(91614);
        var before=BenchMenuStateAudit.capture(c.game());var chance=rng();
        c.controller().setupAutoProfile(new Deck(initialProfile?"Aggro fixture":"Control fixture"));
        verify(c.controller().pilotsNonAggroDeck()==initialProfile,"profile hook preserves existing flag, not fixed replacement");
        verify(c.controller().complainCardsCantPlayWell(new Deck("Control fixture")).isEmpty(),"no Default diagnostics for host pilot");
        verify(c.controller().pilotsNonAggroDeck()==initialProfile,"diagnostic cannot configure nested combat profile");
        for(var set:MemorySet.values())c.controller().getAi().getCardMemory().rememberCard(card,set);
        c.controller().resetAtEndOfTurn();
        for(var set:MemorySet.values())verify(forge.ai.AiCardMemory.getMemorySet(c.actor(),set).isEmpty(),"all transient sets cleared: "+set);
        BenchMenuStateAudit.assertUnchanged(before,c.game());verify(chance.equals(rng()),"lifecycle preserves exact RNG transcript");
        verify(c.host().wire.size()==0,"lifecycle sends neither decisions nor private deck data");
        verify(c.session().integrityFailure(c.game())==null,"healthy lifecycle not failed");
        var methods=c.controller().getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
        for(String method:List.of("setupAutoProfile","complainCardsCantPlayWell","resetAtEndOfTurn")) {
            var b=methods.getAsJsonObject(method);verify(b.get("rules").getAsInt()==1,"rules receipt for "+method);
            for(String owner:List.of("host","forced","stock","unclassified"))verify(b.get(owner).getAsInt()==0,"no other authority for "+method);
        }
    }
    private static void reference(int seat,String mode,boolean copy) {
        var c=context(seat,mode);var controller=(PlayerControllerAi)c.actor().getController();
        var card=card(c,c.actor(),"Grizzly Bears");
        if(copy)c.session().setLiveGame(context(seat,"native").game());
        BenchRandomAudit.install(91614);var chance=rng();
        controller.setupAutoProfile(new Deck("Control fixture"));verify(controller.pilotsNonAggroDeck(),"reference profile unchanged");
        controller.complainCardsCantPlayWell(new Deck("Aggro fixture"));verify(!controller.pilotsNonAggroDeck(),"reference diagnostic still configures profile");
        for(var set:MemorySet.values())controller.getAi().getCardMemory().rememberCard(card,set);
        controller.resetAtEndOfTurn();
        for(var set:MemorySet.values())verify(forge.ai.AiCardMemory.getMemorySet(c.actor(),set).isEmpty(),"reference clears "+set);
        verify(chance.equals(rng())&&c.host().wire.size()==0,"reference lifecycle preserves chance/no host traffic");
        if(copy)verify(c.controller().getCounters().toJson().get("totalCalls").getAsInt()==0,"search copy does not contaminate live accounting");
    }
    private static void closed(int seat,String operation) {
        var c=context(seat);c.host().eof=true;
        c.session().getChannel().ask("fixture-close",new JsonObject());
        verify(c.session().getChannel().isClosed(),"actual channel closed");
        try {
            switch(operation) {
                case "setupAutoProfile" -> c.controller().setupAutoProfile(new Deck("Control"));
                case "complainCardsCantPlayWell" -> c.controller().complainCardsCantPlayWell(new Deck("Control"));
                default -> c.controller().resetAtEndOfTurn();
            }
            throw new AssertionError("closed lifecycle accepted");
        } catch(RulesCostFeasibility.Unsupported expected) { verify(c.session().integrityFailure(c.game())!=null,"failure latches"); }
        var b=c.controller().getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods").getAsJsonObject(operation);
        verify(b.get("rules").getAsInt()==0&&b.get("unclassified").getAsInt()==1,"failed lifecycle never certified");
    }
    public static void main(String[] args) {try {
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"host-lifecycle";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++) {
            live(seat,false);live(seat,true);
            for(String mode:List.of("native","NULL","NULL_PROBE"))reference(seat,mode,false);
            reference(seat,"BRIDGE",true);
            for(String operation:List.of("setupAutoProfile","complainCardsCantPlayWell","resetAtEndOfTurn"))closed(seat,operation);
        }
        System.out.println("PASS "+checks+" lifecycle checks; NOT CERTIFIED for strength");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
