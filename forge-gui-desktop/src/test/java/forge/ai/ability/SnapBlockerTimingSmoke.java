package forge.ai.ability;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;
import java.lang.reflect.Proxy;
import java.util.*;

/** Prepared native decision fixtures, not natural-game or strength evidence. */
public final class SnapBlockerTimingSmoke {
    private static final List<String> CASES = List.of("before", "after", "tapped", "flying", "save",
            "trample", "as-unblocked", "survivor", "lifelink", "death-trigger", "cast-trigger", "enhanced-mana", "cost-reducer", "free-cost-object");
    private static List<String> cards(boolean own, String key) {
        List<String> names = new ArrayList<>();
        if (own) {
            names.add(switch(key) {case "flying" -> "Serra Angel"; case "save" -> "Grizzly Bears";
                case "trample" -> "Colossal Dreadmaw"; case "as-unblocked" -> "Thorn Elemental";
                default -> "Sundering Titan";});
            names.add("Snap"); names.add("Island"); names.add(key.equals("enhanced-mana") ? "Ancient Tomb" : "Island");
            if (key.equals("cast-trigger")) names.add("Monastery Swiftspear");
            if (key.equals("cost-reducer")) names.add("Baral, Chief of Compliance");
        } else names.add(switch(key) {case "save", "survivor" -> "Colossal Dreadmaw";
            case "lifelink" -> "Sacred Cat"; case "death-trigger" -> "Doomed Traveler";
            default -> "Magda, Brazen Outlaw";});
        while(names.size()<40)names.add("Forest"); return names;
    }
    private static Deck deck(boolean own, String key) {
        Deck d=new Deck("Snap timing prepared "+key); for(String n:cards(own,key))d.getMain().add(n,1);return d;
    }
    private static Card populate(Player p, boolean own, String key) {
        Card first=null;int i=0;
        for(String name:cards(own,key)) {
            Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
            c.setGameTimestamp(p.getGame().getNextTimestamp());c.setSickness(false);
            ZoneType z=name.equals("Snap")?ZoneType.Hand:name.equals("Forest")?ZoneType.Library:ZoneType.Battlefield;
            p.getZone(z).add(c);if(i++==0)first=c;
        }
        if(i!=40)throw new AssertionError("deck conservation");return first;
    }
    private static void check(boolean b, String message) {if(!b)throw new AssertionError(message);}
    private static void run(int seat, String key, boolean baseline) throws Exception {
        List<RegisteredPlayer> players=new ArrayList<>();
        for(int s=0;s<2;s++){LobbyPlayerAi l=new LobbyPlayerAi("seat"+s,null);l.setAiProfile("Default");players.add(new RegisteredPlayer(deck(s==seat,key)).setPlayer(l));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        Game g=new Match(rules,players,"Snap timing").createGame();Player ai=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        Card attacker=populate(ai,true,key),blocker=populate(op,false,key);
        ai.setLife(15,null);op.setLife(13,null);g.setAge(GameStage.Play);
        boolean before=List.of("before","tapped","flying").contains(key);
        g.getPhaseHandler().devModeSet(before?PhaseType.COMBAT_DECLARE_ATTACKERS:PhaseType.COMBAT_DECLARE_BLOCKERS,ai);
        g.getAction().checkStaticAbilities(false);
        Combat combat=new Combat(ai);combat.addAttacker(attacker,op);g.getPhaseHandler().setCombat(combat);
        if(key.equals("tapped"))blocker.setTapped(true);
        if(!before){combat.addBlocker(attacker,blocker);combat.setBlocked(attacker,true);}
        var snap=ai.getCardsIn(ZoneType.Hand).get(0).getSpellAbilities().get(0);snap.setActivatingPlayer(ai);
        if(key.equals("free-cost-object")) snap.setPayCosts(new forge.game.cost.Cost("0",false));
        boolean eligible=baseline?!before:ChangeZoneAi.isCombatRemovalCandidate(ai,blocker);
        if(!baseline)check(eligible != List.of("tapped","flying").contains(key),"candidate "+key);
        boolean reject=!baseline&&ChangeZoneAi.isWastefulDoomedBlockerBounce(ai,snap,blocker);
        // The survivor control must survive the attack: lower the attacker's power
        // before asking the predictor, retaining its identity and blocked status.
        if(key.equals("survivor")) {
            attacker.setBasePower(1);reject=ChangeZoneAi.isWastefulDoomedBlockerBounce(ai,snap,blocker);
        }
        check(reject==(!baseline&&key.equals("after")),"doomed rejection "+key+" was "+reject);
        if(key.equals("before")||key.equals("after")) {
            var method=ChangeZoneAi.class.getDeclaredMethod("isPreferredTarget",Player.class,forge.game.spellability.SpellAbility.class,boolean.class,boolean.class);
            method.setAccessible(true);
            // Deliberately expose any admitted randomized tempo play; no sampled game claim.
            MyRandom.setRandom(new Random(914L){@Override public float nextFloat(){return 0f;}});
            boolean selected=(Boolean)method.invoke(null,ai,snap,false,false);
            check(selected==(baseline?!before:before),"actual selector "+key+" selected="+selected);
            if(selected)check(snap.getTargetCard()==blocker,"selected wrong card");
            if(!before){
                // Script the bad action only for a separate rules consequence check.
                // This is native resolution, not evidence of candidate willingness.
                snap.resetTargets();snap.getTargets().add(blocker);
                forge.game.ability.AbilityUtils.resolve(snap);
                check(blocker.isInZone(ZoneType.Hand),"native Snap return");
                check(combat.getBlockers(attacker).isEmpty(),"native bounce removed blocker");
                check(combat.isBlocked(attacker),"native bounce cleared blocked status");
                int life=op.getLife();combat.assignCombatDamage(false);combat.dealAssignedDamage();
                check(op.getLife()==life,"blocked Titan incorrectly dealt player damage");
                System.out.println("SNAP_RULES seat="+seat+" nativeResolved=true blocked=true playerDamage=0");
            }
        }
        System.out.println("SNAP_TIMING_CASE arm="+(baseline?"baseline":"candidate")+" seat="+seat+" case="+key+" candidate="+eligible+" reject="+reject+" registered=40 PASS");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){
                case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                case "getCurrentVersion"->"snap-timing-diagnostic-1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(String key:CASES)for(boolean own:List.of(true,false))for(String name:cards(own,key))StaticData.instance().attemptToLoadCard(name);
            boolean baseline=args.length>1&&args[1].equals("baseline");
            for(String key:baseline?List.of("before","after"):CASES)for(int seat=0;seat<2;seat++)run(seat,key,baseline);
            System.out.println("SNAP_TIMING_COMPLETE cases="+(baseline?4:28));
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
