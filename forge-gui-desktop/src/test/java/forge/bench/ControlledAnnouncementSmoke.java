package forge.bench;

import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.util.List;

/** Composition checkpoint; original production fixture assertion bodies retained. */
public final class ControlledAnnouncementSmoke {
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"controlled-announcement";default->throw new AssertionError(m.getName());
        }));FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        var fixture=ProductionPaymentDomainEngineSmoke.class;
        var scenario=fixture.getDeclaredMethod("scenario",String.class);scenario.setAccessible(true);
        var run=fixture.getDeclaredMethod("run",scenario.getReturnType(),String.class,boolean.class);run.setAccessible(true);
        for(String name:List.of("twenty-plains","twenty-surplus","two-shards","mixed-rw","mixed-hybrid","hybrid-generic","source-life","life-only","face-down-exile","adventure-exile","x-tax-mind-twist","x-tax-forth","top-tax")){
            System.out.println("CAST_CASE "+name);run.invoke(null,scenario.invoke(null,name),null,false);
        }
        System.out.println("PASS 13 existing production cast scenarios composed with controlled announcement");
        // Preserve the old strict assertion, and name its detected limitation.
        // Native announcement rollback is not a fully reversible transaction;
        // rejected games must be invalidated, never resumed or scored.
        try {run.invoke(null,scenario.invoke(null,"two-shards"),"delegate",false);throw new AssertionError("Expected native rollback sequence allocation");}
        catch(java.lang.reflect.InvocationTargetException expected){
            if(!(expected.getCause() instanceof IllegalStateException)
                    ||!expected.getCause().getMessage().equals("BENCH_INTEGRITY_FAILURE: priority enumeration mutated abilitySequence"))throw expected;
            System.out.println("REFERENCE_LIMITATION native rollback changes abilitySequence; failed games are invalid, not reversible");
        }
        ControlledModalEngineSmoke.runAll();CastingAuthorizationEngineSmoke.runAll();System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
