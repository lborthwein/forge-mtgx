package forge.bench;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.ability.AbilityKey;
import forge.game.card.CardZoneTable;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.util.List;
import static forge.bench.OptionalTriggerExecutionSmoke.*;

/** Actual land entry, native stack resolution, and host-selected mana output.
 * Scripted native choices establish execution correspondence, not AI strength. */
public final class TriggeredManaChoiceEngineSmoke {
    private record Outcome(int pool, int life, List<Integer> colors) {}
    private static Outcome run(int seat, String mode, String color, String fault) {
        var c=context(seat,mode,null);
        c.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,c.actor());
        var cobra=card("Lotus Cobra",c);
        if(mode.equals("native")) c.actor().dangerouslySetController(new forge.ai.PlayerControllerAi(c.game(),c.actor(),c.actor().getLobbyPlayer()) {
            @Override public byte chooseColor(String message,SpellAbility ability,ColorSet options) {
                check(options.equals(ColorSet.WUBRG),"native offers five colors");return MagicColor.fromName(color);
            }
        });
        ready(c);c.host().color=color;c.host().fault=fault;
        var land=card("Forest",c);c.actor().getZone(ZoneType.Battlefield).remove(land);c.actor().getZone(ZoneType.Hand).add(land);
        var table=new CardZoneTable();var params=AbilityKey.newMap();params.put(AbilityKey.InternalTriggerTable,table);
        c.game().getAction().moveToPlay(land,null,params);
        table.triggerChangesZoneAll(c.game(),null);
        c.game().getTriggerHandler().runWaitingTriggers();
        check(c.game().getStack().hasSimultaneousStackEntries(),"actual land entry queues Lotus Cobra");
        c.game().getStack().addAllTriggeredAbilitiesToStack();
        var wrapper=(WrappedAbility)c.game().getStack().peekAbility();
        check(wrapper.getHostCard()==cobra && c.host().kinds.isEmpty(),"no color chosen before resolution");
        var effect=wrapper.getWrappedAbility();
        if(fault.startsWith("mutate-")) c.host().beforeAnswer=()->{
            switch(fault) {
                case "mutate-source" -> cobra.setGameTimestamp(c.game().getNextTimestamp());
                case "mutate-amount" -> effect.getMapParams().put("Amount","2");
                case "mutate-copy" -> effect.setCopied(true);
                default -> throw new AssertionError(fault);
            }
        };
        if(!fault.isEmpty()) {
            reject(()->drain(c),"malformed or changed color decision rejected: "+fault);
            check(c.session().integrityFailure(c.game())!=null,"color failure invalidates game");
            check(c.actor().getManaPool().totalMana()==0,"invalid color produces no mana");return null;
        }
        drain(c);
        var output=effect.getManaPart().getLastManaProduced();
        var result=new Outcome(c.actor().getManaPool().totalMana(),c.actor().getLife(),output.stream().map(m->(int)m.getColor()).sorted().toList());
        check(result.equals(new Outcome(1,20,List.of((int)MagicColor.fromName(color)))),"native effect produces exactly selected color");
        check(output.stream().allMatch(m->m.getPlayer()==c.actor() && m.getSourceCard().getId()==cobra.getId()
            && m.getSourceCard().getGameTimestamp()==cobra.getGameTimestamp() && m.getManaAbility()==effect.getManaPart()),"exact output provenance (native LKI card snapshot)");
        if(mode.equals("BRIDGE")) {
            check(c.host().kinds.equals(List.of("manaColor")),"host owns color decision exactly once");
            var methods=((PlayerControllerBridge)c.actor().getController()).getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
            for(var entry:methods.entrySet())check(entry.getValue().getAsJsonObject().get("stock").getAsInt()==0
                && entry.getValue().getAsJsonObject().get("unclassified").getAsInt()==0,"mana trigger owns callback "+entry.getKey());
            check(c.session().integrityFailure(c.game())==null,"healthy color resolution has no integrity failure");
            reject(()->c.actor().getController().chooseColor("stale",effect,ColorSet.WUBRG),"color authority cannot escape resolution");
        }
        return result;
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir"->args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                case "getCurrentVersion"->"triggered-mana-choice-fixture";
                default->throw new AssertionError(m.getName());
            }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                for(String color:List.of("W","U","B","R","G"))check(run(seat,"native",color,"").equals(run(seat,"BRIDGE",color,"")),"native/bridge color receipts equal seat="+seat);
                for(String fault:List.of("delegate","missing","number","illegal","mutate-source","mutate-amount","mutate-copy"))run(seat,"BRIDGE",fault.equals("illegal")?"C":"G",fault);
            }
            System.out.println("PASS "+checks+" triggered mana color execution checks");System.exit(0);
        } catch(Throwable failure) {failure.printStackTrace();System.exit(1);}
    }
}
