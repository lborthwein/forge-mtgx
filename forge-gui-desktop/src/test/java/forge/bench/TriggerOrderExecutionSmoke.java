package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual pending instances, native insertion/resolution and fail-closed RPC. */
public final class TriggerOrderExecutionSmoke {
    private static int checks;
    private static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why);checks++; }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        final List<JsonObject> orders=new ArrayList<>();
        int pick,answered;String fault="none";byte[] pending=new byte[0];int offset;
        private void prepare() {
            if(offset<pending.length)return;
            var ask=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).reduce((a,b)->b).orElseThrow();
            int id=ask.get("id").getAsInt();check(id>answered,"fresh host read");
            var answer=new JsonObject();answer.addProperty("type","answer");answer.addProperty("id",id);
            switch(ask.get("kind").getAsString()) {
                case "triggerOrder" -> {
                    orders.add(ask.deepCopy());check(ask.get("order").getAsString().equals("deepest-first"),"native insertion direction");
                    var menu=ask.getAsJsonArray("menu");check(menu.size()>1,"singleton never asks");
                    if(fault.equals("delegate"))answer.addProperty("delegate",true);
                    else {
                        if(!fault.equals("missing-version"))answer.addProperty("triggerOrderVersion",TriggerOrderChoices.VERSION);
                        var selected=menu.get(pick==0?0:menu.size()-1).getAsJsonObject().get("instance");
                        if(fault.equals("unknown"))answer.addProperty("choice",99999);
                        else if(fault.equals("fraction"))answer.addProperty("choice",0.5);
                        else if(fault.equals("string"))answer.addProperty("choice",selected.getAsString());
                        else answer.add("choice",selected);
                    }
                }
                case "confirm" -> answer.addProperty("yes",true);
                case "targets" -> {
                    var choices=new JsonArray();var target=new JsonObject();target.addProperty("kind","player");
                    target.addProperty("id",1-ask.get("seat").getAsInt());choices.add(target);answer.add("choices",choices);
                }
                default -> throw new AssertionError("unexpected decision "+ask);
            }
            pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);offset=0;answered=id;
        }
        @Override public int read(){if(fault.equals("eof"))return -1;prepare();return pending[offset++]&255;}
        @Override public int read(byte[] bytes,int at,int n){if(n==0)return 0;if(fault.equals("eof"))return -1;prepare();int count=Math.min(n,pending.length-offset);System.arraycopy(pending,offset,bytes,at,count);offset+=count;return count;}
    }
    private record Context(Game game,Player actor,Host host,BenchSession session) {}
    private static Context context(int seat,String mode) {
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        forge.LobbyPlayer lobby;
        if(mode.equals("native"))lobby=GamePlayerUtil.createAiPlayer("Actor",seat,0,null,"Default");
        else {var bridge=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.valueOf(mode),seat);bridge.setAiProfile("Default");lobby=bridge;}
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Trigger order").createGame();
        game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);session.setLiveGame(game);
        return new Context(game,actor,host,session);
    }
    private static Card card(String name,Player p,ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(c);c.setSickness(false);return c;
    }
    private static void ready(Context c) {
        c.game.getAction().checkStateEffects(true);c.game.getTriggerHandler().resetActiveTriggers();
        if(c.actor.getController() instanceof PlayerControllerBridge bridge)bridge.getCounters().reset();
        BenchRandomAudit.install(71941);
    }
    private static String vats(int seat,int pick,int count,String mode,String fault) {
        var c=context(seat,mode);c.host.pick=pick;c.host.fault=fault;
        var vat=card("Mimic Vat",c.actor,ZoneType.Battlefield);
        var dead=new ArrayList<Card>();
        for(String name:List.of("Llanowar Elves","Grim Lavamancer","Flickerwisp").subList(0,count))dead.add(card(name,c.game.getPlayers().get(1-seat),ZoneType.Battlefield));
        ready(c);
        for(var creature:dead)c.game.getAction().moveToGraveyard(creature,null);
        c.game.getTriggerHandler().runWaitingTriggers();
        check(c.game.getStack().hasSimultaneousStackEntries(),"real death triggers queued");
        try { c.game.getStack().addAllTriggeredAbilitiesToStack(); }
        catch(RulesCostFeasibility.Unsupported failure) {
            if(fault.equals("none"))throw failure;
            check(c.game.getStack().isEmpty(),"invalid order inserts no partial batch");
            check(c.session.integrityFailure(c.game)!=null,"invalid host order permanently invalidates game");
            return "invalid";
        }
        check(fault.equals("none"),"malformed order was accepted");
        check(c.game.getStack().size()==count,"all distinct trigger instances inserted");
        var top=c.game.getStack().peekAbility();var next=(Card)top.getTriggeringObject(AbilityKey.Card);
        check(dead.contains(next),"top retains real triggering object");
        while(!c.game.getStack().isEmpty())c.game.getStack().resolveStack();
        var exiled=dead.stream().filter(d->c.game.getCardState(d,null).isInZone(ZoneType.Exile)).map(Card::getName).toList();
        check(exiled.size()==1,"native imprint ends with exactly one card");
        check(vat.isInZone(ZoneType.Battlefield),"source remains on battlefield");
        if(mode.equals("BRIDGE")) {
            check(c.host.orders.size()==count-1,"serial next-choice then forced final instance");
            for(int i=0;i<c.host.orders.size();i++)check(c.host.orders.get(i).getAsJsonArray("menu").size()==count-i,"remaining menu shrinks once");
            var row=new JsonObject();row.addProperty("seat",seat);row.addProperty("pick",pick);row.addProperty("count",count);row.addProperty("imprinted",exiled.get(0));
            var asks=new JsonArray();c.host.orders.forEach(asks::add);row.add("asks",asks);
            System.out.println("TRIGGER_ORDER_CASE "+row);
        } else check(c.host.orders.isEmpty(),"stock/null has no host traffic");
        return exiled.get(0);
    }
    private static void palantir(int seat) {
        var c=context(seat,"BRIDGE");card("Palantír of Orthanc",c.actor,ZoneType.Battlefield);
        c.game.setMonarch(c.actor);c.actor.createMonarchEffect(null);
        c.game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN,c.actor);ready(c);
        c.game.getTriggerHandler().runTrigger(TriggerType.Phase,AbilityKey.mapFromPlayer(c.actor),false);
        c.game.getStack().addAllTriggeredAbilitiesToStack();
        check(c.host.orders.size()==1&&c.game.getStack().size()==2,"actual Palantir and monarch ordered and targeted");
        System.out.println("TRIGGER_ORDER_PALANTIR "+c.host.orders.get(0));
    }
    private static void apnap(int seat) {
        var c=context(seat,"BRIDGE");var active=c.game.getPlayers().get(1-seat);
        c.game.getPhaseHandler().devModeSet(PhaseType.MAIN1,active);
        card("Mimic Vat",c.actor,ZoneType.Battlefield);card("Mimic Vat",active,ZoneType.Battlefield);
        var a=card("Llanowar Elves",active,ZoneType.Battlefield);var b=card("Grim Lavamancer",active,ZoneType.Battlefield);
        ready(c);c.game.getAction().moveToGraveyard(a,null);c.game.getAction().moveToGraveyard(b,null);
        c.game.getTriggerHandler().runWaitingTriggers();c.game.getStack().addAllTriggeredAbilitiesToStack();
        var controllers=new ArrayList<Player>();for(var instance:c.game.getStack())controllers.add(instance.getSpellAbility().getActivatingPlayer());
        check(controllers.equals(List.of(c.actor,c.actor,active,active)),"NAP batch is above AP batch, internal host order does not cross ownership");
        check(c.host.orders.size()==1&&c.host.orders.get(0).get("seat").getAsInt()==seat,"only actual NAP chooses its batch");
    }
    private static void unsupportedBatch(int seat,boolean first) {
        var c=context(seat,"BRIDGE");var abilities=new ArrayList<forge.game.spellability.SpellAbility>();
        for(int i=0;i<2;i++) {
            var source=card("Luminarch Aspirant",c.actor,ZoneType.Battlefield);var trigger=source.getTriggers().get(0);
            var body=trigger.ensureAbility().copyForEnumeration(c.actor);body.setTrigger(trigger);
            if((i==0)==first)body.setPayCosts(new forge.game.cost.Cost("1",false));
            abilities.add(new forge.game.trigger.WrappedAbility(trigger,body,null));
        }
        ready(c);
        try { c.actor.getController().orderAndPlaySimultaneousSa(abilities);throw new AssertionError("unsupported batch accepted"); }
        catch(RulesCostFeasibility.Unsupported failure) {check(failure.getMessage().contains("not literal zero"),"actual unsupported cost rejected");}
        check(c.host.orders.isEmpty()&&c.host.answered==0&&c.game.getStack().isEmpty(),"known unsupported member rejects before order/target prompts or partial insertion");
        check(c.session.integrityFailure(c.game)!=null,"unsupported batch invalidates result");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir"->args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                case "getCurrentVersion"->"trigger-order-fixture";
                default->throw new AssertionError("unexpected GUI "+m.getName());
            }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                check(!vats(seat,0,2,"BRIDGE","none").equals(vats(seat,1,2,"BRIDGE","none")),"opposite order changes actual imprint");
                vats(seat,1,3,"BRIDGE","none");
                var nativeResult=vats(seat,0,2,"native","none");
                check(nativeResult.equals(vats(seat,0,2,"NULL","none")),"Default/null order outcome unchanged");
                for(String fault:List.of("delegate","missing-version","unknown","fraction","string","eof"))vats(seat,0,2,"BRIDGE",fault);
                palantir(seat);
                apnap(seat);
                unsupportedBatch(seat,false);unsupportedBatch(seat,true);
            }
            System.out.println("PASS "+checks+" actual trigger ordering checks; NOT CERTIFIED");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
