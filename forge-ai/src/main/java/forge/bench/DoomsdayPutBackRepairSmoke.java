package forge.bench;

import forge.StaticData;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.ability.ApiType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.lang.reflect.Method;

/** Scripted engine legality only. Does not evaluate autonomous pilot strength. */
public final class DoomsdayPutBackRepairSmoke {
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = CubeDoomsdayExecutionSmoke.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
    private static Card add(String name, Player p, ZoneType zone) throws Exception {
        return (Card) call("card", new Class<?>[]{String.class, Player.class, ZoneType.class}, name,p,zone);
    }
    private static void cast(Game g, Player p, String name) throws Exception {
        call("cast", new Class<?>[]{Game.class,Player.class,String.class},g,p,name);
    }
    private static void check(boolean b, String why) { if(!b) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        check(args.length==7,"assets + six fresh seeds required");
        GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
            new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()) {
                case "getAssetsDir" -> args[0]+"/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "doomsday-jace-scripted-legality-v1";
                default -> throw new AssertionError(method.getName());
            }));
        FModel.initialize(null, preferences->{preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);preferences.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        var field=CubeDoomsdayExecutionSmoke.class.getDeclaredField("improved");field.setAccessible(true);field.setBoolean(null,true);
        int index=0;
        for(int seat=0;seat<2;seat++) for(String arm:new String[]{"native-jace","scripted-jace","no-jace"}) {
            boolean withJace=!arm.equals("no-jace"), force=arm.equals("scripted-jace");
            long seed=Long.parseLong(args[++index]);BenchRandomAudit.install(seed);
            Game g=(Game)call("game",new Class<?>[]{int.class},seat);Player p=g.getPlayers().get(seat);
            final int[] puts={0};
            if(force) p.dangerouslySetController(new forge.ai.CubeComboPlayerController(g,p,p.getLobbyPlayer()) {
                @Override public Card chooseSingleCardForZoneChange(ZoneType destination, java.util.List<ZoneType> origin,
                        forge.game.spellability.SpellAbility ability, forge.game.card.CardCollection choices,
                        forge.game.player.DelayedReveal reveal,String prompt,boolean optional,Player decider) {
                    if(destination==ZoneType.Library && origin.contains(ZoneType.Hand)
                            && ability.getHostCard().getName().equals("Jace, the Mind Sculptor")) {
                        check(decider==p,"own hand only");
                        Card forest=choices.stream().filter(c->c.getName().equals("Forest")).findFirst().orElseThrow();
                        puts[0]++;return forest;
                    }
                    return super.chooseSingleCardForZoneChange(destination,origin,ability,choices,reveal,prompt,optional,decider);
                }
            });
            for(int i=0;i<3;i++)add("Swamp",p,ZoneType.Battlefield);
            for(int i=0;i<2;i++)add("Island",p,ZoneType.Battlefield);
            Card jace=withJace?add("Jace, the Mind Sculptor",p,ZoneType.Battlefield):null;
            if(jace!=null)jace.setCounters(CounterEnumType.LOYALTY,3);
            add("Doomsday",p,ZoneType.Hand);add("Thassa's Oracle",p,ZoneType.Hand);
            for(int i=0;i<25;i++)add("Forest",p,ZoneType.Library);
            g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
            check(p.getLife()==20,"starting life");
            cast(g,p,"Doomsday");check(p.getCardsIn(ZoneType.Library).size()==5,"Doomsday five");check(p.getLife()==10,"Doomsday life");
            if(withJace){
                var draw=jace.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.Draw).findFirst().orElseThrow();
                draw.setActivatingPlayer(p);check(p.getController().playChosenSpellAbility(draw),"Jace activation paid");
                call("settle",new Class<?>[]{Game.class},g);
                check(p.getCardsIn(ZoneType.Library).size()==4,"Jace net draw one");
                
            }
            boolean retained=p.getCardsIn(ZoneType.Hand).stream().anyMatch(c->c.getName().equals("Thassa's Oracle"));
            boolean oracleInLibrary=p.getCardsIn(ZoneType.Library).stream().anyMatch(c->c.getName().equals("Thassa's Oracle"));
            check(retained,"registered retention pattern");
            check(!oracleInLibrary,"registered Oracle library location");
            check(puts[0]==(force?2:0),"exact scripted choices");
            if(retained)cast(g,p,"Thassa's Oracle");
            check(p.hasWon()==withJace,"native and scripted Jace lines win");
            System.out.println("PUTBACK_REPAIR_CASE seat="+seat+" arm="+arm+" seed="+seed+" library="+p.getCardsIn(ZoneType.Library).size()+" retained="+retained+" oracleInLibraryBeforeCast="+oracleInLibrary+" putbacks="+puts[0]+" won="+p.hasWon()+" life="+p.getLife()+" lands=5 fastMana=0");
        }
        System.out.println("PUTBACK_REPAIR_COMPLETE cases=6 scripted=true strengthClaim=false");
        System.exit(0);
    }
}
