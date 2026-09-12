package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** DEVELOPMENT X-invariance witness; it proves target-domain stability, not policy parity. */
public final class XBoardTargetDomainSmoke {
    private static int checks;
    private static void check(boolean v,String s){if(!v)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    private static Card card(String n,Player p,ZoneType z){StaticData.instance().attemptToLoadCard(n);var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(n)),p);c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(z).add(c);c.setSickness(false);return c;}
    private static int seq(Class<?> c,String n)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);return f.getInt(null);}
    @SuppressWarnings("unchecked") private static Object decision(Player p)throws Exception{var m=PlayerControllerBridge.class.getDeclaredMethod("buildPriorityDecision");m.setAccessible(true);return m.invoke(p.getController());}
    @SuppressWarnings("unchecked") private static List<SpellAbility> menu(Object d)throws Exception{var m=d.getClass().getDeclaredMethod("menu");m.setAccessible(true);return(List<SpellAbility>)m.invoke(d);}
    private static JsonObject body(Object d)throws Exception{var m=d.getClass().getDeclaredMethod("body");m.setAccessible(true);return((JsonObject)m.invoke(d)).deepCopy();}
    private static void run(int seat,int swamps)throws Exception{
        var session=new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()));var lobby=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);var foe=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,foe):List.of(foe,own),"X board target fixture").createGame();game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);var other=game.getPlayers().get(1-seat);session.setLiveGame(game);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        for(int i=0;i<swamps;i++)card("Swamp",actor,ZoneType.Battlefield);card("Thalia, Guardian of Thraben",other,ZoneType.Battlefield);for(String n:List.of("Forest","Island","Mountain"))card(n,other,ZoneType.Hand);
        var twist=card("Mind Twist",actor,ZoneType.Hand).getFirstSpellAbility();twist.setActivatingPlayer(actor);game.getPhaseHandler().setPriority(actor);game.getAction().checkStaticAbilities();BenchRandomAudit.install(77100+seat);
        var before=BenchMenuStateAudit.capture(game);var rng=BenchRandomAudit.begin();var actorBefore=twist.getActivatingPlayer();var xBefore=twist.getXManaCostPaid();var targets=new ArrayList<>(twist.getTargets());int abilities=seq(SpellAbility.class,"maxId"),instances=seq(SpellAbilityStackInstance.class,"maxId");
        Object d=decision(actor);var offered=menu(d).stream().filter(sa->sa.getHostCard().getId()==twist.getHostCard().getId()).findFirst().orElseThrow();var domain=PriorityBoardTargetDomain.encode(offered);
        check("exact".equals(domain.get("kind").getAsString()),"Mind Twist X target domain exact seat="+seat);var proof=domain.getAsJsonObject("xInvariant");check(proof!=null&&proof.get("min").getAsInt()==0&&proof.get("max").getAsInt()==swamps-2&&proof.get("checkedFrom").getAsInt()==0&&proof.get("checkedThrough").getAsInt()==swamps-2,"X0..payable ceiling proof seat="+seat);
        var rows=domain.getAsJsonArray("rows");boolean opponentAllowed=false;for(var raw:rows){var row=raw.getAsJsonObject();if("player".equals(row.get("kind").getAsString())&&row.get("id").getAsInt()==1-seat)opponentAllowed=row.get("allowed").getAsBoolean();}check(opponentAllowed,"Mind Twist opponent player row allowed seat="+seat);
        var ask=body(d);ask.addProperty("type","ask");ask.addProperty("kind","priority");ask.addProperty("id",920000000+seat+10*swamps);ask.addProperty("fixtureSynthetic",true);ask.addProperty("fixtureProvenance","DEVELOPMENT XBoardTargetDomainSmoke synthetic observation; not a live RPC ask");var out=new JsonObject();out.addProperty("seat",seat);out.addProperty("swamps",swamps);out.addProperty("name","Mind Twist");out.add("ask",ask);out.addProperty("sourceFid",offered.getHostCard().getId());out.addProperty("expectedPlayerSeats",1-seat);out.addProperty("xMin",proof.get("min").getAsInt());out.addProperty("xMax",proof.get("max").getAsInt());System.out.println("X_BOARD_CASE "+out);
        BenchMenuStateAudit.assertUnchanged(before,game);BenchRandomAudit.assertUnchanged(rng,"X board target enumeration");check(twist.getActivatingPlayer()==actorBefore&&Objects.equals(twist.getXManaCostPaid(),xBefore)&&targets.equals(twist.getTargets()),"original Mind Twist actor X and targets unchanged seat="+seat);check(abilities==seq(SpellAbility.class,"maxId")&&instances==seq(SpellAbilityStackInstance.class,"maxId"),"X enumeration allocates no global IDs seat="+seat);
        // A copied native option exercises the alternate-targeting-player refusal
        // without changing the offered Mind Twist or allocating IDs inside its audit.
        var routed=twist.copyForEnumeration(actor);routed.setTargetingPlayer(other);
        before=BenchMenuStateAudit.capture(game);rng=BenchRandomAudit.begin();abilities=seq(SpellAbility.class,"maxId");instances=seq(SpellAbilityStackInstance.class,"maxId");
        var routedDomain=PriorityBoardTargetDomain.encode(routed);check("unsupported".equals(routedDomain.get("kind").getAsString())&&"alternate-targeting-player".equals(routedDomain.get("reason").getAsString()),"alternate targeting player is explicitly unsupported seat="+seat);
        BenchMenuStateAudit.assertUnchanged(before,game);BenchRandomAudit.assertUnchanged(rng,"alternate targeting player refusal");check(abilities==seq(SpellAbility.class,"maxId")&&instances==seq(SpellAbilityStackInstance.class,"maxId"),"alternate targeting refusal allocates no global IDs seat="+seat);
        if(swamps==4)varyingX(seat);
    }
    private static void varyingX(int seat)throws Exception{
        var a=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default"));var b=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default"));var game=new Match(new GameRules(GameType.Constructed),List.of(a,b),"Plaguebearer X varying fixture").createGame();game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);var other=game.getPlayers().get(1-seat);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        for(int i=0;i<5;i++)card("Swamp",actor,ZoneType.Battlefield);var plague=card("Plaguebearer",actor,ZoneType.Battlefield);var zero=card("Ornithopter",other,ZoneType.Battlefield);var one=card("Savannah Lions",other,ZoneType.Battlefield);var two=card("Grizzly Bears",other,ZoneType.Battlefield);var ability=plague.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();ability.setActivatingPlayer(actor);game.getAction().checkStaticAbilities();BenchRandomAudit.install(88100+seat);
        var x0=ability.copyForEnumeration(actor);x0.setXManaCostPaid(0);var x1=ability.copyForEnumeration(actor);x1.setXManaCostPaid(1);check(x0.canTarget(zero)&&!x0.canTarget(one)&&!x0.canTarget(two)&&!x1.canTarget(zero)&&x1.canTarget(one)&&!x1.canTarget(two),"native Plaguebearer rows genuinely vary with announced X seat="+seat);
        var before=BenchMenuStateAudit.capture(game);var rng=BenchRandomAudit.begin();var xBefore=ability.getXManaCostPaid();var targets=new ArrayList<>(ability.getTargets());int ids=seq(SpellAbility.class,"maxId"),stack=seq(SpellAbilityStackInstance.class,"maxId");var domain=PriorityBoardTargetDomain.encode(ability);check("unsupported".equals(domain.get("kind").getAsString())&&"x-dependent-target-domain".equals(domain.get("reason").getAsString()),"Plaguebearer varying-X domain is explicitly unsupported seat="+seat);BenchMenuStateAudit.assertUnchanged(before,game);BenchRandomAudit.assertUnchanged(rng,"Plaguebearer X-dependent refusal");check(Objects.equals(xBefore,ability.getXManaCostPaid())&&targets.equals(ability.getTargets())&&ids==seq(SpellAbility.class,"maxId")&&stack==seq(SpellAbilityStackInstance.class,"maxId"),"Plaguebearer refusal preserves original X targets and IDs seat="+seat);
    }
    public static void main(String[]a){try{GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){case "getAssetsDir"->a[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"x-board-domain";default->throw new AssertionError(m.getName());}));FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});run(0,4);run(1,4);run(0,6);run(1,6);System.out.println("PASS "+checks+" X board-target checks");System.exit(0);}catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
