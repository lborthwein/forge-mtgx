package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.card.*;
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

/** Real phase trigger transport; unbridged ordering/payment remain uncertified. */
public final class HostTriggerPreparationSmoke {
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; System.out.println("PASS " + label); }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int target, asks, answered; String response = "target"; byte[] pending = new byte[0]; int offset;
        private void prepare() {
            if (offset < pending.length) return;
            var ask = wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o -> o.has("type") && o.get("type").getAsString().equals("ask")).reduce((a,b) -> b).orElseThrow();
            int id = ask.get("id").getAsInt(); if (id <= answered) throw new AssertionError("Repeated host read");
            check(ask.get("kind").getAsString().equals("targets"), "real trigger asks existing targets transport");
            asks++; var answer = new JsonObject(); answer.addProperty("type", "answer"); answer.addProperty("id", id);
            if (response.equals("delegate")) answer.addProperty("delegate", true);
            else if (!response.equals("missing")) {
                var choices = new JsonArray(); var ref = new JsonObject(); ref.addProperty("kind", "card"); ref.addProperty("id", target); choices.add(ref); answer.add("choices", choices);
            }
            pending = (answer + "\n").getBytes(StandardCharsets.UTF_8); offset = 0; answered = id;
        }
        @Override public int read() { if(response.equals("eof"))return -1; prepare(); return pending[offset++] & 255; }
        @Override public int read(byte[] data, int start, int length) { if (length==0) return 0; if(response.equals("eof"))return -1; prepare(); int n=Math.min(length,pending.length-offset); System.arraycopy(pending,offset,data,start,n); offset+=n; return n; }
    }
    private record Context(Game game, Player actor, PlayerController controller, Host host, BenchSession session) {}
    private static Context context(int seat, String mode) {
        return context(seat,mode,null,null);
    }
    private static Context context(int seat, String mode, InputStream input, OutputStream output) {
        var host = new Host(); var channel = new JsonRpcChannel(input==null?host:input,output==null?host.wire:output); var session = new BenchSession(channel);
        forge.LobbyPlayer lobby;
        if (mode.equals("native")) lobby = GamePlayerUtil.createAiPlayer("Observed", seat, 0, null, "Default");
        else { var bridge = new LobbyPlayerBridge("Observed", null, session, BenchSession.Mode.valueOf(mode), seat); bridge.setAiProfile("Default"); lobby=bridge; }
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent",1-seat,0,null,"Default"));
        var game = new Match(new GameRules(GameType.Constructed), seat==0 ? List.of(own,other) : List.of(other,own), "Host trigger fixture").createGame();
        game.setAge(GameStage.Play); var actor=game.getPlayers().get(seat); game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN,actor); session.setLiveGame(game);
        return new Context(game,actor,actor.getController(),host,session);
    }
    private static Card card(String name, Context c) {
        StaticData.instance().attemptToLoadCard(name);
        var card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),c.actor);
        card.setGameTimestamp(c.game.getNextTimestamp()); c.actor.getZone(ZoneType.Battlefield).add(card); card.setSickness(false); return card;
    }
    private static void ready(Context c) {
        c.game.getAction().checkStateEffects(true); c.game.getTriggerHandler().resetActiveTriggers();
        if(c.controller instanceof PlayerControllerBridge bridge) bridge.getCounters().reset();
        BenchRandomAudit.install(71941);
    }
    private static void fire(Context c) {
        c.game.getTriggerHandler().runTrigger(TriggerType.Phase,AbilityKey.mapFromPlayer(c.actor),false);
        check(c.game.getStack().hasSimultaneousStackEntries(),"actual Luminarch phase event queued");
        check(c.game.getStack().addAllTriggeredAbilitiesToStack(),"actual trigger inserted on stack");
    }
    private static int bucket(Context c,String method,String key) {
        var methods=((PlayerControllerBridge)c.controller).getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
        return methods.has(method)?methods.getAsJsonObject(method).get(key).getAsInt():0;
    }
    private record Outcome(int aspirantCounters,int bearsCounters,Object rng) {}
    private static void checkTargetExecutionOwnership(Context c) {
        for(String method:List.of("orderAndPlaySimultaneousSa","playSpellAbilityNoStack")) {
            check(bucket(c,method,"rules")==1,"actual native execution accounted: "+method);
            for(String key:List.of("host","forced","stock","unclassified"))
                check(bucket(c,method,key)==0,"no substituted or unknown execution: "+method+"/"+key);
        }
        check(bucket(c,"chooseTargetsFor","host")==1,"exact target selection is host-owned");
        for(String key:List.of("forced","rules","stock","unclassified"))
            check(bucket(c,"chooseTargetsFor",key)==0,"target selection not substituted: "+key);
        check(c.session.integrityFailure(c.game)==null,"target resolution did not latch a swallowed failure");
    }
    private static Outcome run(int seat,String mode,int choose) {
        var c=context(seat,mode); var aspirant=card("Luminarch Aspirant",c); var bears=card("Grizzly Bears",c); ready(c);
        if(mode.equals("NULL_PROBE")) ((PlayerControllerBridge)c.controller).probePriorityMenuPurity();
        c.host.target=choose==0?aspirant.getId():bears.getId(); fire(c);
        c.game.getStack().resolveStack();
        int a=aspirant.getCounters(CounterEnumType.P1P1), b=bears.getCounters(CounterEnumType.P1P1);
        if(mode.equals("BRIDGE")) {
            check(a==(choose==0?1:0)&&b==(choose==1?1:0),"host-selected creature alone receives actual counter seat="+seat+" choice="+choose);
            check(c.host.asks==1,"one actual host target choice");
            checkTargetExecutionOwnership(c);
        } else check(c.host.asks==0,"native/null/probe never request a host answer");
        var rng=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().deepCopy();
        check(rng.get("purityChecks").getAsInt()==(mode.equals("NULL_PROBE")?1:0),"expected diagnostic-only purity probe count");
        rng.remove("purityChecks"); // diagnostic count differs; provider/state/draws/digest must remain identical
        var outcome=new Outcome(a,b,rng);
        System.out.println("OUTCOME seat="+seat+" mode="+mode+" "+outcome);
        if(c.controller instanceof PlayerControllerBridge bridge)System.out.println("LEDGER "+bridge.getCounters().toJson());
        return outcome;
    }
    private static void invalid(int seat,String response) {
        var c=context(seat,"BRIDGE"); var a=card("Luminarch Aspirant",c); var b=card("Grizzly Bears",c); ready(c);
        c.host.target=999999; c.host.response=response;
        try { fire(c); throw new AssertionError("Invalid trigger answer accepted"); }
        catch(RulesCostFeasibility.Unsupported expected) { checks++; System.out.println("PASS failclosed trigger "+response+" "+expected.getMessage()); }
        check(a.getCounters(CounterEnumType.P1P1)+b.getCounters(CounterEnumType.P1P1)==0,"failed target never resolves a substituted AI counter");
        check(bucket(c,"orderAndPlaySimultaneousSa","rules")==0,"failed target never certifies enclosing execution");
    }
    private static void unauthorizedOptional(int seat) {
        var c=context(seat,"BRIDGE"); var soul=card("Soulherder",c); var bears=card("Grizzly Bears",c);
        c.game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN,c.actor); ready(c);
        c.game.getTriggerHandler().runTrigger(TriggerType.Phase,AbilityKey.mapFromPlayer(c.actor),false);
        check(c.game.getStack().hasSimultaneousStackEntries(),"actual optional Soulherder trigger queued");
        c.host.target=bears.getId();
        c.game.getStack().addAllTriggeredAbilitiesToStack();
        var wrapper=(forge.game.trigger.WrappedAbility)c.game.getStack().peekAbility();
        try { c.controller.confirmTrigger(wrapper); throw new AssertionError("Out-of-scope optional confirmation silently accepted"); }
        catch(RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("outside actual resolution"),"optional confirmation outside native resolution remains rejected, not auto-yes"); }
        check(c.host.asks==1&&soul.isInZone(ZoneType.Battlefield)&&bears.isInZone(ZoneType.Battlefield),"only real target preparation occurred; no fabricated confirmation or blink");
    }
    private static void hypotheticalGame(int seat) {
        var c=context(seat,"BRIDGE"); var a=card("Luminarch Aspirant",c); var b=card("Grizzly Bears",c); ready(c);
        c.session.setLiveGame(context(seat,"native").game);
        fire(c);c.game.getStack().resolveStack();
        check(c.host.asks==0&&a.getCounters(CounterEnumType.P1P1)==0&&b.getCounters(CounterEnumType.P1P1)==1,"non-live game retains Default targeting without host traffic");
        check(((PlayerControllerBridge)c.controller).getCounters().toJson().get("totalCalls").getAsInt()==0,"non-live game does not pollute live ownership ledger");
    }
    /** Observe the real wire without answering or selecting a target in this fixture. */
    private static JsonObject externalTrigger(int seat, PrintStream protocol) {
        if(seat<0||seat>1)throw new IllegalArgumentException("Seat must be 0 or 1");
        var incoming=new ByteArrayOutputStream();var outgoing=new ByteArrayOutputStream();
        var input=new FilterInputStream(System.in) {
            @Override public int read() throws IOException {int value=in.read();if(value>=0)incoming.write(value);return value;}
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {int count=in.read(bytes,offset,length);if(count>0)incoming.write(bytes,offset,count);return count;}
        };
        var output=new OutputStream() {
            @Override public void write(int value){outgoing.write(value);protocol.write(value);}
            @Override public void write(byte[] bytes,int offset,int length){outgoing.write(bytes,offset,length);protocol.write(bytes,offset,length);}
            @Override public void flush(){protocol.flush();}
        };
        var c=context(seat,"BRIDGE",input,output);var aspirant=card("Luminarch Aspirant",c);var bears=card("Grizzly Bears",c);ready(c);fire(c);
        var asks=outgoing.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).toList();
        check(asks.size()==1&&asks.get(0).get("kind").getAsString().equals("targets"),"one actual external target ask");
        int askId=asks.get(0).get("id").getAsInt();
        var answers=incoming.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                .filter(o->o.has("type")&&o.get("type").getAsString().equals("answer")).toList();
        check(answers.size()==1&&answers.get(0).get("id").getAsInt()==askId,"one real host answer matches actual ask");
        var choices=answers.get(0).getAsJsonArray("choices");
        check(choices.size()==1&&choices.get(0).getAsJsonObject().get("kind").getAsString().equals("card"),"external host chose one card target");
        int targetId=choices.get(0).getAsJsonObject().get("id").getAsInt();
        check(targetId==aspirant.getId()||targetId==bears.getId(),"external target is one of two actual legal creatures");
        var stackTargets=c.game.getStack().peekAbility().getTargets().getTargetCards();
        check(stackTargets.size()==1&&stackTargets.get(0).getId()==targetId,"actual stack target equals external answer");
        c.game.getStack().resolveStack();
        for(var creature:List.of(aspirant,bears))check(creature.getCounters(CounterEnumType.P1P1)==(creature.getId()==targetId?1:0),"exact external target counter recipient fid="+creature.getId());
        checkTargetExecutionOwnership(c);
        var result=new JsonObject();result.addProperty("type","fixture-result");result.addProperty("passed",true);result.addProperty("id",askId);result.addProperty("seat",seat);
        result.addProperty("targetId",targetId);result.addProperty("counter",1);result.addProperty("aspirantId",aspirant.getId());result.addProperty("bearsId",bears.getId());
        result.addProperty("scope","ACTUAL_HOST_TRIGGER_TARGET_AND_COUNTER_NOT_NATIVE_POLICY_PARITY_OR_STRENGTH");return result;
    }
    public static void main(String[] args) {
        boolean stdio=args.length>1&&args[1].equals("--stdio");PrintStream protocol=System.out;
        try {
            if(stdio)System.setOut(System.err);
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                    (proxy,method,values)->switch(method.getName()) {
                        case "getAssetsDir"->args[0]+"/forge-gui/";
                        case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                        case "getCurrentVersion"->"host-trigger-preparation";
                        default->throw new AssertionError("Unexpected GUI call "+method.getName());
                    }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            if(stdio){protocol.println(externalTrigger(Integer.parseInt(args[2]),protocol));System.exit(0);}
            for(int seat=0;seat<2;seat++) {
                var nativeResult=run(seat,"native",0);
                check(nativeResult.equals(run(seat,"NULL",0)),"actual Default and null outcome/RNG identical seat="+seat);
                check(nativeResult.equals(run(seat,"NULL_PROBE",0)),"actual Default and null-probe outcome/RNG identical seat="+seat);
                run(seat,"BRIDGE",0);run(seat,"BRIDGE",1);
                for(String response:List.of("target","missing","delegate","eof"))invalid(seat,response);
                unauthorizedOptional(seat);hypotheticalGame(seat);
            }
            System.out.println("PASS "+checks+" host trigger preparation checks; incomplete execution ownership stays UNCLASSIFIED");System.exit(0);
        } catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
