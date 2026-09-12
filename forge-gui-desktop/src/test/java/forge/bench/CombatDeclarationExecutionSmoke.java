package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.*;
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

/** Real bridge RPC and native Combat declarations. No playing-strength claim. */
public final class CombatDeclarationExecutionSmoke {
    static int checks;
    static void check(boolean ok, String why) { if(!ok)throw new AssertionError(why);checks++; }
    static final class Host extends InputStream {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        JsonObject payload = new JsonObject(); JsonObject ask;
        byte[] pending = new byte[0]; int at, asks; boolean eof; Runnable before = () -> {};
        void prepare() {
            if(at < pending.length)return;
            ask = wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString)
                .map(JsonElement::getAsJsonObject).filter(o -> o.has("type") && o.get("type").getAsString().equals("ask"))
                .reduce((a,b) -> b).orElseThrow();
            asks++; check(asks == 1, "one explicit declaration request"); before.run();
            JsonObject answer=payload.deepCopy();answer.addProperty("type","answer");answer.add("id",ask.get("id"));
            pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);at=0;
        }
        @Override public int read(){if(eof)return -1;prepare();return pending[at++]&255;}
        @Override public int read(byte[] b,int off,int len){if(len==0)return 0;if(eof)return -1;prepare();int n=Math.min(len,pending.length-at);System.arraycopy(pending,at,b,off,n);at+=n;return n;}
    }
    record C(Game game, Player actor, Player other, BenchSession session, Host host, PlayerControllerBridge controller) {}
    static C context(int seat) { return context(seat,"BRIDGE"); }
    static C context(int seat,String mode) {
        Host host=new Host();BenchSession session=new BenchSession(new JsonRpcChannel(host,host.wire));
        forge.LobbyPlayer lobby;
        if(mode.equals("native"))lobby=GamePlayerUtil.createAiPlayer("Actor",seat,0,null,"Default");
        else {var bridge=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.valueOf(mode),seat);bridge.setAiProfile("Default");lobby=bridge;}
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        Game g=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"combat declaration").createGame();
        g.setAge(GameStage.Play);Player actor=g.getPlayers().get(seat);session.setLiveGame(g);
        return new C(g,actor,g.getPlayers().get(1-seat),session,host,actor.getController() instanceof PlayerControllerBridge b?b:null);
    }
    static Card card(C c,Player p,String name) {
        StaticData.instance().attemptToLoadCard(name);
        Card card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        card.setGameTimestamp(c.game.getNextTimestamp());p.getZone(ZoneType.Battlefield).add(card);card.setSickness(false);
        if(card.isPlaneswalker())card.setCounters(CounterEnumType.LOYALTY,5);
        return card;
    }
    static Combat ready(C c,boolean attack) {
        Player attacking=attack?c.actor:c.other;
        c.game.getPhaseHandler().devModeSet(attack?PhaseType.COMBAT_DECLARE_ATTACKERS:PhaseType.COMBAT_DECLARE_BLOCKERS,attacking);
        c.game.getAction().checkStateEffects(true);c.game.getTriggerHandler().resetActiveTriggers();
        Combat combat=new Combat(attacking);c.game.getPhaseHandler().setCombat(combat);if(c.controller!=null)c.controller.getCounters().reset();
        BenchRandomAudit.install(91612);return combat;
    }
    static JsonArray pair(Card first,JsonElement second) {JsonArray p=new JsonArray();p.add(first.getId());p.add(second);return p;}
    static void run(C c,Combat combat,boolean attack,boolean fails,boolean noMutation) {
        var originalAttackers=new LinkedHashMap<>(combat.getAttackersAndDefenders());int originalBlocks=combat.getAllBlockers().size();
        try { if(attack)c.controller.declareAttackers(c.actor,combat);else c.controller.declareBlockers(c.actor,combat);check(!fails,"bad answer accepted"); }
        catch(RuntimeException failure){if(!fails)throw failure;check(c.session.integrityFailure(c.game)!=null,"failure is permanent");}
        var methods=c.controller.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
        var bucket=methods.getAsJsonObject(attack?"declareAttackers":"declareBlockers");
        check(bucket.get("stock").getAsInt()==0,"no stock policy");
        check(bucket.get(fails?"unclassified":"host").getAsInt()==1,"ownership only after accepted declaration");
        if(fails && noMutation){check(originalAttackers.equals(combat.getAttackersAndDefenders()),"malformed answer preserves attackers");check(originalBlocks==combat.getAllBlockers().size(),"malformed answer preserves blocks");}
        if(fails){int asks=c.host.asks;c.host.eof=false;
            try{if(attack)c.controller.declareAttackers(c.actor,combat);else c.controller.declareBlockers(c.actor,combat);throw new AssertionError("invalid game resumed");}
            catch(RuntimeException expected){check(c.host.asks==asks,"latched game never asks again");}}
    }
    static void attack(int seat,String fault) {
        C c=context(seat);Card a=card(c,c.actor,fault.equals("mandatory")?"Juggernaut":"Grizzly Bears");
        Card b=card(c,c.actor,"Llanowar Elves");Card walker=card(c,c.other,"Jace, the Mind Sculptor");
        Combat combat=ready(c,true);JsonArray pairs=new JsonArray();
        if(!fault.equals("empty") && !fault.equals("mandatory"))pairs.add(pair(a,StateEncoder.entityRef(fault.equals("walker")?walker:c.other)));
        if(fault.equals("two"))pairs.add(pair(b,StateEncoder.entityRef(c.other)));
        if(fault.equals("duplicate"))pairs.add(pair(a,StateEncoder.entityRef(c.other)));
        if(fault.equals("fraction"))pairs.get(0).getAsJsonArray().set(0,new JsonPrimitive(a.getId()+0.5));
        if(fault.equals("string"))pairs.get(0).getAsJsonArray().set(0,new JsonPrimitive(String.valueOf(a.getId())));
        if(fault.equals("overflow"))pairs.get(0).getAsJsonArray().set(0,new JsonPrimitive(4294967296L+a.getId()));
        if(fault.equals("bare-defender"))pairs.get(0).getAsJsonArray().set(1,new JsonPrimitive(c.other.getId()));
        if(fault.equals("bad-kind"))pairs.get(0).getAsJsonArray().get(1).getAsJsonObject().addProperty("kind","card");
        if(fault.equals("stale"))c.host.before=()->c.actor.getZone(ZoneType.Battlefield).remove(a);
        if(fault.equals("reentered"))c.host.before=()->a.setGameTimestamp(c.game.getNextTimestamp());
        if(fault.equals("redeclaration"))combat.addAttacker(b,c.other);
        c.host.payload.add("pairs",pairs);
        if(fault.equals("missing"))c.host.payload.remove("pairs");
        if(fault.equals("delegate"))c.host.payload.addProperty("delegate",true);
        if(fault.equals("eof"))c.host.eof=true;
        boolean fails=!Set.of("none","two","empty","walker","redeclaration").contains(fault);
        run(c,combat,true,fails,true);
        if(!fails){check(combat.getAttackers().size()==pairs.size(),"exact native attacker count");
            if(!pairs.isEmpty())check(combat.getDefenderByAttacker(a)==(fault.equals("walker")?walker:c.other),"typed native defender");}
    }
    static void block(int seat,String fault) {
        C c=context(seat);Card a=card(c,c.other,fault.startsWith("menace")?"Boggart Brute":"Grizzly Bears");
        Card a2=card(c,c.other,"Grizzly Bears");Card b=card(c,c.actor,fault.equals("multi")?"Palace Guard":"Grizzly Bears");
        Card b2=card(c,c.actor,"Grizzly Bears");Card walker=card(c,c.actor,"Jace, the Mind Sculptor");
        Combat combat=ready(c,false);combat.addAttacker(a,c.actor);combat.addAttacker(a2,c.actor);
        JsonArray pairs=new JsonArray();if(!fault.equals("empty"))pairs.add(pair(b,new JsonPrimitive(a.getId())));
        if(fault.equals("menace-good"))pairs.add(pair(b2,new JsonPrimitive(a.getId())));
        if(fault.equals("multi")||fault.equals("overcapacity"))pairs.add(pair(b,new JsonPrimitive(a2.getId())));
        if(fault.equals("duplicate"))pairs.add(pair(b,new JsonPrimitive(a.getId())));
        if(fault.equals("fraction"))pairs.get(0).getAsJsonArray().set(1,new JsonPrimitive(a.getId()+0.5));
        if(fault.equals("string"))pairs.get(0).getAsJsonArray().set(0,new JsonPrimitive(String.valueOf(b.getId())));
        if(fault.equals("stale"))c.host.before=()->c.actor.getZone(ZoneType.Battlefield).remove(b);
        if(fault.equals("reentered"))c.host.before=()->b.setGameTimestamp(c.game.getNextTimestamp());
        if(fault.equals("retargeted"))c.host.before=()->{combat.clearAttackers();combat.addAttacker(a,walker);combat.addAttacker(a2,c.actor);};
        c.host.payload.add("pairs",pairs);if(fault.equals("delegate"))c.host.payload.addProperty("delegate",true);
        if(fault.equals("eof"))c.host.eof=true;
        boolean fails=!Set.of("none","empty","menace-good","multi").contains(fault);
        run(c,combat,false,fails,!Set.of("menace-bad","overcapacity","retargeted").contains(fault));
        if(!fails)check(combat.getAttackersBlockedBy(b).size()==(fault.equals("multi")?2:fault.equals("empty")?0:1),"exact block assignments");
    }
    static void wrongContext(int seat,boolean detached) {
        C c=context(seat);card(c,c.actor,"Grizzly Bears");card(c,c.other,"Grizzly Bears");Combat live=ready(c,true);
        Combat passed=new Combat(detached?c.actor:c.other);
        if(!detached)c.game.getPhaseHandler().setCombat(passed);
        try{c.controller.declareAttackers(detached?c.actor:c.other,passed);throw new AssertionError("wrong context accepted");}
        catch(RuntimeException expected){check(c.session.integrityFailure(c.game)!=null,"wrong context invalidates game");}
        check(c.host.asks==0 && passed.getAttackers().isEmpty() && live.getAttackers().isEmpty(),"wrong context rejected before ask/mutation");
    }
    static void routedDeclaration(int seat,boolean attack) {
        C c=context(seat);Card ours=card(c,c.actor,"Grizzly Bears");Card theirs=card(c,c.other,"Llanowar Elves");
        Combat combat=ready(c,!attack);
        c.game.getPhaseHandler().devModeSet(attack?PhaseType.COMBAT_DECLARE_ATTACKERS:PhaseType.COMBAT_DECLARE_BLOCKERS,attack?c.other:c.actor,false);
        combat.initConstraints();
        JsonArray pairs=new JsonArray();
        if(attack){c.other.addDeclaresAttackers(c.game.getNextTimestamp(),c.actor);pairs.add(pair(theirs,StateEncoder.entityRef(c.actor)));}
        else{c.other.addDeclaresBlockers(c.game.getNextTimestamp(),c.actor);combat.addAttacker(ours,c.other);pairs.add(pair(theirs,new JsonPrimitive(ours.getId())));}
        c.host.payload.add("pairs",pairs);
        if(attack)c.controller.declareAttackers(c.other,combat);else c.controller.declareBlockers(c.other,combat);
        check(c.session.integrityFailure(c.game)==null && c.host.asks==1,"engine-routed other-player declaration accepted");
        check(attack?combat.getDefenderByAttacker(theirs)==c.actor:combat.getBlockers(ours).contains(theirs),"routed declaration actually assigned");
    }
    static String stockControl(int seat,boolean attack,String mode) {
        C c=context(seat,mode);card(c,c.actor,"Grizzly Bears");card(c,c.actor,"Llanowar Elves");
        Card enemy=card(c,c.other,"Grizzly Bears");Combat combat=ready(c,attack);
        if(attack)c.actor.getController().declareAttackers(c.actor,combat);
        else {combat.addAttacker(enemy,c.actor);c.actor.getController().declareBlockers(c.actor,combat);}
        check(c.host.asks==0,"stock/null declaration never asks host");
        if(c.controller!=null){var bucket=c.controller.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods").getAsJsonObject(attack?"declareAttackers":"declareBlockers");
            check(bucket.get("stock").getAsInt()==1 && bucket.get("host").getAsInt()==0,"null remains explicitly stock owned");}
        List<String> declarations=new ArrayList<>();
        if(attack)for(var e:combat.getAttackersAndDefenders().entrySet())declarations.add(e.getKey().getName()+":"+e.getValue().getName());
        else for(Card b:combat.getAllBlockers())for(Card a:combat.getAttackersBlockedBy(b))declarations.add(b.getName()+":"+a.getName());
        Collections.sort(declarations);return declarations.toString();
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir"->args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                case "getCurrentVersion"->"combat-declaration-fixture";
                default->throw new AssertionError("unexpected GUI "+m.getName());
            }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                for(String fault:List.of("none","two","empty","walker","redeclaration","mandatory","duplicate","fraction","string","overflow","bare-defender","bad-kind","stale","reentered","missing","delegate","eof"))attack(seat,fault);
                for(String fault:List.of("none","empty","menace-good","multi","menace-bad","overcapacity","duplicate","fraction","string","stale","reentered","retargeted","delegate","eof"))block(seat,fault);
                wrongContext(seat,true);wrongContext(seat,false);
                for(boolean attack:new boolean[]{true,false}){routedDeclaration(seat,attack);check(stockControl(seat,attack,"native").equals(stockControl(seat,attack,"NULL")),"Default/null declarations identical");}
            }
            System.out.println("PASS "+checks+" native combat declaration checks; damage and policy equivalence NOT CERTIFIED");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
