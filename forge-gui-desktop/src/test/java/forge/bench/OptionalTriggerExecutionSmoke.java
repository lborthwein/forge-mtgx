package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual trigger lifecycle, no games or policy-strength claim. */
public final class OptionalTriggerExecutionSmoke {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static void reject(Runnable action,String label){try{action.run();throw new AssertionError("accepted "+label);}catch(RulesCostFeasibility.Unsupported expected){check(true,label);}}
    static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        final List<String> kinds=new ArrayList<>();
        int target,answered; boolean yes, echo; int repeatedLimit = -1; String fault="", color="G"; byte[] pending=new byte[0];int offset;
        Runnable beforeAnswer;
        void prepare(){if(offset<pending.length)return;
            var ask=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).reduce((a,b)->b).orElseThrow();
            int id=ask.get("id").getAsInt();if(id<=answered)throw new AssertionError("repeated host read");
            String kind=ask.get("kind").getAsString();kinds.add(kind);
            var answer=new JsonObject();answer.addProperty("type","answer");answer.addProperty("id",id);
            if(kind.equals("targets")){
                var choices=new JsonArray();if(target!=0){var ref=new JsonObject();ref.addProperty("kind","card");ref.addProperty("id",target);choices.add(ref);}answer.add("choices",choices);
            }else if(kind.equals("confirm")){
                check(echo ? ask.get("resolutionPaymentVersion").getAsString().equals("native-echo-mana-v1")
                    : ask.get("mode").getAsString().equals("Trigger"),"actual native trigger confirmation request");
                if(fault.equals("delegate"))answer.addProperty("delegate",true);
                else if(fault.equals("string"))answer.addProperty("yes","true");
                else if(!fault.equals("missing"))answer.addProperty("yes",yes && (repeatedLimit < 0
                    || ask.get("completedPayments").getAsInt() < repeatedLimit));
            }else if(kind.equals("manaColor")){
                check(ask.get("manaChoiceVersion").getAsString().equals("native-trigger-mana-color-v1"),"versioned native trigger color request");
                check(ask.get("amount").getAsInt()==1 && ask.getAsJsonArray("colors").toString().equals("[\"W\",\"U\",\"B\",\"R\",\"G\"]"),"all five colors offered without ranking");
                if(fault.equals("delegate"))answer.addProperty("delegate",true);
                else if(fault.equals("number"))answer.addProperty("color",1);
                else if(!fault.equals("missing"))answer.addProperty("color",color);
            }else if(kind.equals("payment")){
                check(ask.get("paymentContext").getAsString().equals(echo ? "echo-resolution-v1" : repeatedLimit < 0 ? "optional-trigger-resolution-v1"
                    : "repeated-trigger-resolution-v1"),"payment belongs to accepted trigger resolution");
                if(fault.equals("payment-delegate"))answer.addProperty("delegate",true);
                else if(!fault.equals("payment-missing")) {
                    answer.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION);
                    answer.addProperty("x", 0); answer.addProperty("lifePaid", 0);
                    var order = new JsonArray(); var spend = new JsonArray();
                    int needed = ask.getAsJsonObject("cost").getAsJsonArray("shards").size();
                    check(needed == (echo ? 5 : repeatedLimit < 0 ? 4 : 2),"actual full unit payment cost");
                    for (int i=0;i<needed;i++) {
                        var option=ask.getAsJsonArray("sourceOptions").get(i).getAsJsonObject();
                        check(option.getAsJsonArray("output").size()==1,"fixed single-output basic source");
                        String source=option.get("id").getAsString();order.add(source);
                        var token=new JsonObject();token.addProperty("token",source+":0");token.addProperty("shardIndex",i);spend.add(token);
                    }
                    if(fault.equals("payment-overspend"))spend.add(spend.get(0).deepCopy());
                    answer.add("sourceOrder",order);answer.add("spend",spend);
                }
            }else throw new AssertionError("Unexpected ask "+ask);
            if (beforeAnswer != null) beforeAnswer.run();
            answered=id;pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
        }
        @Override public int read(){prepare();return pending[offset++]&255;}
        @Override public int read(byte[] b,int start,int length){if(length==0)return 0;prepare();int n=Math.min(length,pending.length-offset);System.arraycopy(pending,offset,b,start,n);offset+=n;return n;}
    }
    record Context(Game game,Player actor,Host host,BenchSession session){}
    static final class NoopScopedController extends forge.ai.PlayerControllerAi implements ScopedTriggerResolution {
        final boolean answer;int scopes;
        NoopScopedController(Game game,Player actor,forge.LobbyPlayer lobby,boolean answer){super(game,actor,lobby);this.answer=answer;}
        @Override public boolean confirmTrigger(WrappedAbility wrapper){return answer;}
        @Override public boolean requiresTriggerResolutionScope(WrappedAbility wrapper){return wrapper.isOptionalTrigger();}
        @Override public void withTriggerResolutionScope(WrappedAbility wrapper,Runnable nativeResolution){scopes++;nativeResolution.run();}
    }
    static Context context(int seat,String mode,Boolean scripted){
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        forge.LobbyPlayer lobby;
        if(scripted!=null){var scriptedLobby=new forge.ai.LobbyPlayerAi("Observed",null){
            @Override public Player createIngamePlayer(Game game,int id){var actor=new Player(getName(),game,id);actor.setFirstController(mode.equals("noop")?new NoopScopedController(game,actor,this,scripted):new forge.ai.PlayerControllerAi(game,actor,this){
                @Override public boolean confirmTrigger(WrappedAbility wrapper){return scripted;}
            });return actor;}
        };scriptedLobby.setAiProfile("Default");lobby=scriptedLobby;}
        else if(mode.equals("native"))lobby=GamePlayerUtil.createAiPlayer("Observed",seat,0,null,"Default");
        else {var bridge=new LobbyPlayerBridge("Observed",null,session,BenchSession.Mode.valueOf(mode),seat);bridge.setAiProfile("Default");lobby=bridge;}
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Optional trigger fixture").createGame();
        var actor=game.getPlayers().get(seat);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN,actor);session.setLiveGame(game);
        return new Context(game,actor,host,session);
    }
    static Card card(String name,Context c){StaticData.instance().attemptToLoadCard(name);var card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),c.actor);card.setGameTimestamp(c.game.getNextTimestamp());c.actor.getZone(ZoneType.Battlefield).add(card);card.setSickness(false);return card;}
    static void ready(Context c){c.game.getAction().checkStateEffects(true);c.game.getTriggerHandler().resetActiveTriggers();if(c.actor.getController() instanceof PlayerControllerBridge b)b.getCounters().reset();BenchRandomAudit.install(71941);}
    static WrappedAbility queue(Context c){c.game.getTriggerHandler().runTrigger(TriggerType.Phase,AbilityKey.mapFromPlayer(c.actor),false);check(c.game.getStack().hasSimultaneousStackEntries(),"real phase event queued optional trigger");c.game.getStack().addAllTriggeredAbilitiesToStack();return c.game.getStack().isEmpty()?null:(WrappedAbility)c.game.getStack().peekAbility();}
    static void drain(Context c){for(int i=0;i<8;i++){if(c.game.getStack().isEmpty()){if(!c.game.getStack().hasSimultaneousStackEntries())return;c.game.getStack().addAllTriggeredAbilitiesToStack();}if(!c.game.getStack().isEmpty())c.game.getStack().resolveStack();}throw new AssertionError("trigger drain exceeded bound");}
    static int bucket(Context c,String method,String bucket){var methods=((PlayerControllerBridge)c.actor.getController()).getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");return methods.has(method)?methods.getAsJsonObject(method).get(bucket).getAsInt():0;}
    record Outcome(boolean blinked,int counters,int life,int mana,Object rng){}
    static Outcome soul(int seat,String mode,boolean yes,boolean noTarget,boolean upToOne,String fault,Boolean scripted){
        var c=context(seat,mode,scripted);var soul=card("Soulherder",c);var bear=noTarget?null:card("Grizzly Bears",c);long before=bear==null?0:bear.getGameTimestamp();
        if(upToOne){soul.setSVar("ConjurerExile",soul.getSVar("ConjurerExile")+" | TargetMin$ 0 | TargetMax$ 1");
            soul.getTriggers().stream().filter(t->t.hasParam("OptionalDecider")).forEach(t->t.setOverridingAbility(forge.game.ability.AbilityFactory.getAbility(soul,"ConjurerExile")));
        }
        ready(c);c.host.target=bear==null||upToOne?0:bear.getId();c.host.yes=yes;c.host.fault=fault;
        var wrapper=queue(c);
        if(mode.equals("BRIDGE")){
            check(c.host.kinds.equals(noTarget&&!upToOne?List.of():List.of("targets")),"targets precede confirmation; no impossible-target prompt");
        }
        if(!fault.isEmpty()){
            reject(()->drain(c),"bad host confirmation "+fault);
            var b=(PlayerControllerBridge)c.actor.getController();reject(()->b.playSpellAbilityNoStack(wrapper.getWrappedAbility(),false),"failed resolution leaves no receipt");
            reject(b::chooseSpellAbilityToPlay,"failed resolution invalidates game");
            check(c.game.getCardState(bear).getGameTimestamp()==before,"bad confirm does not blink");return null;
        }
        drain(c);var current=bear==null?null:c.game.getCardState(bear);boolean blinked=current!=null&&current.getGameTimestamp()!=before;
        if(c.actor.getController() instanceof NoopScopedController noop)check(noop.scopes==1,"native no-op resolution hook executes exactly once");
        if(mode.equals("BRIDGE")){
            boolean shouldBlink=yes&&!noTarget&&!upToOne;check(blinked==shouldBlink,"actual Soulherder blink matches host decision seat="+seat+" yes="+yes+" noTarget="+noTarget+" upToOne="+upToOne);
            check(soul.getCounters(CounterEnumType.P1P1)==(shouldBlink?1:0),"actual Soulherder exile trigger counter correct");
            check(bucket(c,"confirmTrigger","host")==((noTarget&&!upToOne)?0:1),"confirmation host ownership only if native reaches it");
            if(wrapper!=null){
                int rules=bucket(c,"playSpellAbilityNoStack","rules"),unknown=bucket(c,"playSpellAbilityNoStack","unclassified");
                reject(()->c.actor.getController().playSpellAbilityNoStack(wrapper.getWrappedAbility(),false),"receipt cannot be reused after resolution");
                check(bucket(c,"playSpellAbilityNoStack","rules")==rules&&bucket(c,"playSpellAbilityNoStack","unclassified")==unknown+1,"failed reuse adds unknown ownership, never an extra rules receipt");
            }
        }
        var rng=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().deepCopy();rng.remove("purityChecks");
        return new Outcome(blinked,soul.getCounters(CounterEnumType.P1P1),c.actor.getLife(),c.actor.getManaPool().totalMana(),rng);
    }
    static void unscopedExecution(int seat){
        var c=context(seat,"BRIDGE",null);card("Soulherder",c);var bear=card("Grizzly Bears",c);ready(c);c.host.target=bear.getId();
        var wrapper=queue(c);var b=(PlayerControllerBridge)c.actor.getController();long before=bear.getGameTimestamp();
        reject(()->b.confirmTrigger(wrapper),"manual confirmation outside resolution rejected");
        reject(()->b.playSpellAbilityNoStack(wrapper.getWrappedAbility(),false),"manual noStack without receipt rejected");
        check(c.session.integrityFailure(c.game)!=null,"unscoped effect permanently invalidates this game");
        check(bear.getGameTimestamp()==before&&c.host.kinds.equals(List.of("targets")),"unscoped failure neither blinks nor asks confirmation");
        check(bucket(c,"playSpellAbilityNoStack","rules")==0&&bucket(c,"playSpellAbilityNoStack","unclassified")==1,"unscoped failure gets no rules receipt");
    }
    static void earlyReturn(int seat,String gate){var c=context(seat,"BRIDGE",null);var soul=card("Soulherder",c);var bear=card("Grizzly Bears",c);ready(c);c.host.target=bear.getId();var wrapper=queue(c);
        if(gate.equals("limit"))wrapper.getTrigger().getMapParams().put("ResolvedLimit","0");else {wrapper.getTrigger().getMapParams().put("IsPresent","Creature.YouCtrl");wrapper.getTrigger().getMapParams().put("PresentCompare","GE99");}
        long before=bear.getGameTimestamp();drain(c);check(c.host.kinds.equals(List.of("targets")),"native "+gate+" returns before confirmation");check(c.game.getCardState(bear).getGameTimestamp()==before,"early return no effect");reject(()->c.actor.getController().playSpellAbilityNoStack(wrapper.getWrappedAbility(),false),"early return leaves no authority");}
    static void scopedMisuse(int seat){var c=context(seat,"BRIDGE",null);var soul=card("Soulherder",c);var bear=card("Grizzly Bears",c);ready(c);c.host.target=bear.getId();c.host.yes=true;var wrapper=queue(c);var b=(PlayerControllerBridge)c.actor.getController();
        reject(()->b.withTriggerResolutionScope(wrapper,()->{b.confirmTrigger(wrapper);b.confirmTrigger(wrapper);}),"double confirmation rejected");
        reject(()->b.playSpellAbilityNoStack(wrapper.getWrappedAbility(),false),"exception closes authority");
        reject(()->b.withTriggerResolutionScope(wrapper,()->b.confirmTrigger(wrapper)),"accepted but omitted native effect rejected");
        try{b.withTriggerResolutionScope(wrapper,()->{throw new IllegalArgumentException("fixture");});throw new AssertionError("exception lost");}catch(IllegalArgumentException expected){check(true,"native exception preserved");}
        reject(()->b.confirmTrigger(wrapper),"scope cleared after native exception");
        var scope=new OptionalZeroTriggerExecution.Resolution(c.actor,wrapper);scope.answer(false);reject(()->scope.consume(c.actor,wrapper.getWrappedAbility()),"decline does not authorize effect");scope.close();reject(()->scope.requireConfirmation(c.actor,wrapper),"closed scope rejected");
        var accepted=new OptionalZeroTriggerExecution.Resolution(c.actor,wrapper);accepted.answer(true);accepted.consume(c.actor,wrapper.getWrappedAbility());reject(()->accepted.consume(c.actor,wrapper.getWrappedAbility()),"exact underlying consumption single-use");accepted.close();
        var other=new WrappedAbility(wrapper.getTrigger(),wrapper.getWrappedAbility(),c.game.getPlayers().get(1-seat));
        reject(()->OptionalZeroTriggerExecution.require(c.actor,other),"different optional decider unsupported");
        var empty=new SpellAbility.EmptySa(soul);empty.setActivatingPlayer(c.actor);empty.setTrigger(wrapper.getTrigger());empty.setOptionalTrigger(true);
        reject(()->OptionalZeroTriggerExecution.require(c.actor,new WrappedAbility(wrapper.getTrigger(),empty,c.actor)),"unhooked null-API wrapper unsupported");
        var cost=wrapper.getWrappedAbility().getPayCosts();wrapper.getWrappedAbility().setPayCosts(new forge.game.cost.Cost("1",true));
        reject(()->OptionalZeroTriggerExecution.require(c.actor,wrapper),"paid optional trigger unsupported");wrapper.getWrappedAbility().setPayCosts(cost);
    }
    static void mandatoryControls(int seat)throws Exception{
        var run=MandatoryTriggerExecutionSmoke.class.getDeclaredMethod("run",int.class,String.class,int.class);run.setAccessible(true);
        var expected=run.invoke(null,seat,"native",0);
        check(expected.equals(run.invoke(null,seat,"NULL",0)),"Luminarch native/null preserved");
        check(expected.equals(run.invoke(null,seat,"NULL_PROBE",0)),"Luminarch native/probe preserved");
        check(expected.equals(run.invoke(null,seat,"BRIDGE",1)),"Luminarch actual mandatory host execution preserved");
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"optional-trigger";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){
            var nativeResult=soul(seat,"native",true,false,false,"",null);
            check(nativeResult.equals(soul(seat,"NULL",true,false,false,"",null)),"Default/null exact state+RNG");
            check(nativeResult.equals(soul(seat,"NULL_PROBE",true,false,false,"",null)),"Default/probe exact state+RNG");
            for(boolean yes:List.of(false,true)){var actual=soul(seat,"BRIDGE",yes,false,false,"",null);var scripted=soul(seat,"native",yes,false,false,"",yes);check(actual.equals(scripted),"host vs scripted native resolution choice state+RNG yes="+yes);check(scripted.equals(soul(seat,"noop",yes,false,false,"",yes)),"no-op scoped hook preserves native state+RNG yes="+yes);}
            check(soul(seat,"BRIDGE",true,true,false,"",null).equals(soul(seat,"native",true,true,false,"",null)),"no legal target same native state and RNG");
            soul(seat,"BRIDGE",false,true,true,"",null);soul(seat,"BRIDGE",true,true,true,"",null);
            for(String fault:List.of("missing","delegate","string"))soul(seat,"BRIDGE",true,false,false,fault,null);
            unscopedExecution(seat);earlyReturn(seat,"limit");earlyReturn(seat,"intervening");scopedMisuse(seat);mandatoryControls(seat);
        }
        System.out.println("PASS "+checks+" optional trigger actual execution checks");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
