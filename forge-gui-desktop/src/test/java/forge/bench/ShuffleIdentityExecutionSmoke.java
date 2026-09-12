package forge.bench;

import forge.game.card.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;
import java.util.*;

/** One native shuffle, no AI smoothing and no host exposure of library order. */
public final class ShuffleIdentityExecutionSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static List<Integer> ids(Iterable<Card> cards){List<Integer> ids=new ArrayList<>();for(Card c:cards)ids.add(c.getId());return ids;}
    private static void identity(int seat,String mode,int size) {
        var c=CombatDeclarationExecutionSmoke.context(seat,mode);
        var cards=new CardCollection();
        for(int i=0;i<size;i++){
            Card card=CombatDeclarationExecutionSmoke.card(c,c.actor(),i%2==0?"Plains":"Grizzly Bears");
            c.actor().getZone(ZoneType.Battlefield).remove(card);c.actor().getZone(ZoneType.Library).add(card);cards.add(card);
        }
        c.game().getRules().setAllowCheatShuffle(false);
        if(c.controller()!=null)c.controller().getCounters().reset();
        BenchRandomAudit.install(91613);var before=BenchRandomAudit.begin();
        var direct=c.actor().getController().cheatShuffle(cards);
        check(direct==cards,"disabled smoothing returns exact collection");
        check(ids(direct).equals(ids(cards)),"order preserved");
        BenchRandomAudit.assertUnchanged(before,"shuffle callback identity");checks++;
        check(c.host().wire.size()==0,"library never sent to host");
        if(c.controller()!=null)c.controller().getCounters().reset();
        var expected=new ArrayList<Card>();for(Card card:cards)expected.add(card);
        BenchRandomAudit.install(91613);Collections.shuffle(expected,MyRandom.getRandom());
        var expectedRng=((BenchRandomAudit.AuditedRandom)MyRandom.getRandom()).snapshot();
        BenchRandomAudit.install(91613);c.actor().shuffle(null);
        check(ids(c.actor().getCardsIn(ZoneType.Library)).equals(ids(expected)),"actual Player.shuffle equals one native permutation");
        var actualRng=((BenchRandomAudit.AuditedRandom)MyRandom.getRandom()).snapshot();
        for(String key:List.of("digest","draws","stateTouches"))check(actualRng.get(key).equals(expectedRng.get(key)),"exact native shuffle RNG "+key);
        check(c.host().wire.size()==0,"actual shuffle does not reveal cards");
        if(c.controller()!=null){var bucket=c.controller().getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods").getAsJsonObject("cheatShuffle");
            check(bucket.get(mode.equals("BRIDGE")?"rules":"stock").getAsInt()==1,"exact shuffle ownership");}
    }
    private static void refused(int seat,boolean nullCards) {
        var c=CombatDeclarationExecutionSmoke.context(seat);
        c.controller().getCounters().reset();c.game().getRules().setAllowCheatShuffle(!nullCards);
        BenchRandomAudit.install(91613);var before=BenchRandomAudit.begin();
        try{c.controller().cheatShuffle(nullCards?null:new CardCollection());throw new AssertionError("bad shuffle accepted");}
        catch(RuntimeException expected){check(c.session().integrityFailure(c.game())!=null,"bad shuffle invalidates game");}
        BenchRandomAudit.assertUnchanged(before,"rejected shuffle");checks++;
        check(c.host().wire.size()==0,"rejected shuffle never asks policy");
        c.game().getRules().setAllowCheatShuffle(false);
        try{c.controller().cheatShuffle(new CardCollection());throw new AssertionError("invalid game resumed");}
        catch(RuntimeException expected){check(true,"failure persists");}
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir"->args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                case "getCurrentVersion"->"shuffle-identity-fixture";
                default->throw new AssertionError("unexpected GUI "+m.getName());
            }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++){
                for(String mode:List.of("BRIDGE","NULL","native"))for(int size:List.of(0,7,40))identity(seat,mode,size);
                refused(seat,false);refused(seat,true);
            }
            System.out.println("PASS "+checks+" shuffle identity checks; NOT CERTIFIED for strength");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
