package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.ai.ComputerUtil;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
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
import java.util.function.Function;

/** Actual controlled modal casting, with unchanged Default's orphan as a named reference defect. */
public final class ControlledModalEngineSmoke {
    private static boolean external;
    private static PrintStream protocolOutput;
    private static int checks;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream(){
            @Override public synchronized void write(int value){super.write(value);if(external)protocolOutput.write(value);}
            @Override public synchronized void write(byte[] value,int at,int length){super.write(value,at,length);if(external)protocolOutput.write(value,at,length);}
            @Override public void flush(){if(external)protocolOutput.flush();}
        };final List<JsonObject> asks=new ArrayList<>();
        Function<JsonObject,JsonObject> respond;byte[] pending=new byte[0];int offset,answered;
        private void prepare(){
            if(offset<pending.length)return;
            var ask=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).reduce((a,b)->b).orElseThrow();
            int id=ask.get("id").getAsInt();if(external){if(id>answered){asks.add(ask.deepCopy());answered=id;}return;}
            if(id<=answered)throw new AssertionError("repeat read");
            asks.add(ask.deepCopy());var response=respond.apply(ask);response.addProperty("type","answer");response.addProperty("id",id);
            pending=(response+"\n").getBytes(StandardCharsets.UTF_8);offset=0;answered=id;
        }
        @Override public int read() throws IOException {prepare();return external?System.in.read():pending[offset++]&255;}
        @Override public int read(byte[] bytes,int at,int len) throws IOException {if(len==0)return 0;prepare();if(external)return System.in.read(bytes,at,len);int n=Math.min(len,pending.length-offset);System.arraycopy(pending,offset,bytes,at,n);offset+=n;return n;}
    }
    private record Context(Game game,Player actor,Card reb,Card tithe,Card land,Card blue,Host host,BenchSession session){}
    private static Card card(String name,Player p,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);var card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        card.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(card);card.setSickness(false);return card;
    }
    private static Context context(int seat,boolean controlled,boolean blue){
        return context(seat,controlled,blue,BenchSession.Mode.BRIDGE);
    }
    private static Context context(int seat,boolean controlled,boolean blue,BenchSession.Mode controllerMode){
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));forge.LobbyPlayer lobby;
        if(controlled){var bridge=new LobbyPlayerBridge("Host",null,session,controllerMode,seat);bridge.setAiProfile("Default");lobby=bridge;}
        else lobby=GamePlayerUtil.createAiPlayer("Native",seat,0,null,"Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Modal orphan fixture").createGame();
        var actor=game.getPlayers().get(seat);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN,actor);session.setLiveGame(game);
        var reb=card("Red Elemental Blast",actor,ZoneType.Hand);var tithe=card("Mana Tithe",actor,ZoneType.Hand);var land=card("Plateau",actor,ZoneType.Battlefield);
        var target=blue?card("Serendib Efreet",game.getPlayers().get(1-seat),ZoneType.Battlefield):null;
        game.getAction().checkStateEffects(true);if(controlled)BenchRandomAudit.install(841991);
        return new Context(game,actor,reb,tithe,land,target,host,session);
    }
    private static List<Integer> ids(Game game,ZoneType zone){return game.getCardsIn(zone).stream().map(Card::getId).toList();}
    private static List<Integer> stackIds(Game game){var out=new ArrayList<Integer>();for(var entry:game.getStack())out.add(entry.getSpellAbility().getHostCard().getId());return out;}
    private static JsonObject payment(JsonObject ask){
        var out=new JsonObject();out.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);out.addProperty("x",0);out.addProperty("lifePaid",0);
        JsonObject selected=null;for(var raw:ask.getAsJsonArray("sourceOptions")){
            var source=raw.getAsJsonObject();System.out.println("PAYMENT_SOURCE "+source);
            if(source.getAsJsonArray("output").get(0).getAsString().equals("R")){selected=source;break;}
        }
        if(selected==null)throw new AssertionError("No real red source");
        String id=selected.get("id").getAsString();var order=new JsonArray();order.add(id);out.add("sourceOrder",order);
        var spend=new JsonArray();var token=new JsonObject();token.addProperty("token",id+":0");token.addProperty("shardIndex",0);spend.add(token);out.add("spend",spend);return out;
    }
    private static void run(int seat,boolean controlled,boolean blue){ run(seat,controlled,blue,null); }
    private static void run(int seat,boolean controlled,boolean blue,String fault){
        var c=context(seat,controlled,blue);var sa=c.reb.getFirstSpellAbility();sa.setActivatingPlayer(c.actor);
        check(sa.getApi()==ApiType.Charm,"real REB modal ability");
        // makePossibleOptions writes CharmOrder metadata: probe a separate game,
        // never precondition the real production menu/announcement under test.
        var probe=context(seat,false,blue);var probeSa=probe.reb.getFirstSpellAbility();probeSa.setActivatingPlayer(probe.actor);
        var legalModes=CharmEffect.makePossibleOptions(probeSa);
        check(legalModes.size()==(blue?1:0),"actual possible modal count before announcement");
        check(ids(c.game,ZoneType.Hand).contains(c.reb.getId())&&ids(c.game,ZoneType.Stack).isEmpty()&&stackIds(c.game).isEmpty(),"initial hand and both stacks consistent");
        if(controlled){
            BenchActionAudit.beginGame(c.game,"modal-receipt");
            c.game.subscribeToEvents(new BenchMain.EventEmitter(c.session.getChannel(),"modal-receipt",c.game));
        }
        c.host.respond=ask->{
            var answer=new JsonObject();switch(ask.get("kind").getAsString()){
                case "priority"->{int choice=-1;var menu=ask.getAsJsonArray("menu");for(int i=1;i<menu.size();i++)if(menu.get(i).getAsJsonObject().get("fid").getAsInt()==c.reb.getId())choice=i;check(blue?choice>0:choice==-1,"controlled menu has REB iff a legal mode exists");answer.addProperty("choice",blue?choice:0);}
                case "payment"->{if("payment-delegate".equals(fault)){answer.addProperty("delegate",true);return answer;}return payment(ask);}
                case "mode"->{var choices=new JsonArray();if("mode-fraction".equals(fault))choices.add(0.5);else choices.add(0);answer.add("choices",choices);if("mode-delegate".equals(fault))answer.addProperty("delegate",true);check(blue&&ask.getAsJsonArray("menu").size()==1,"sole actual legal blue permanent mode");}
                case "targets"->{var choices=new JsonArray();var target=new JsonObject();target.addProperty("kind","card");target.addProperty("id","target-illegal".equals(fault)?c.land.getId():c.blue.getId());choices.add(target);answer.add("choices",choices);}
                default->throw new AssertionError("Unexpected ask "+ask);
            }return answer;
        };
        boolean accepted=false;RuntimeException failure=null;
        try{
            if(controlled){var controller=(PlayerControllerBridge)c.actor.getController();var selected=controller.chooseSpellAbilityToPlay();check(blue?selected!=null&&selected.size()==1:selected==null,"actual host selected only a legal offered action");if(selected!=null)accepted=controller.playChosenSpellAbility(selected.get(0));}
            else accepted=ComputerUtil.handlePlayingSpellAbility(c.actor,sa,null);
        }catch(RuntimeException ex){failure=ex;}
        if(controlled){
            var ledger=((PlayerControllerBridge)c.actor.getController()).getCounters().toJson();
            var methods=ledger.getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
            var modeCall=methods.getAsJsonObject("chooseModeForAbility");
            if(!blue)check(modeCall==null,"no mode callback means no invented ownership receipt");
            else{
                String owner=fault!=null&&fault.startsWith("mode-")?"unclassified":"host";
                for(String bucket:List.of("host","stock","forced","rules","unclassified"))
                    check(modeCall.get(bucket).getAsInt()==(bucket.equals(owner)?1:0),"actual mode callback ownership "+bucket+" fault="+fault+" seat="+seat);
                check(ledger.getAsJsonObject("calls").get("chooseModeForAbility").getAsInt()==1,"mode callback counted exactly once");
            }
        }
        var result=new JsonObject();result.addProperty("seat",seat);result.addProperty("controlled",controlled);result.addProperty("blue",blue);result.addProperty("accepted",accepted);result.addProperty("failure",failure==null?null:failure.toString());
        result.add("hand",new Gson().toJsonTree(ids(c.game,ZoneType.Hand)));result.add("stackZone",new Gson().toJsonTree(ids(c.game,ZoneType.Stack)));result.add("magicStack",new Gson().toJsonTree(stackIds(c.game)));result.addProperty("landTapped",c.land.isTapped());result.addProperty("stackFrozen",c.game.getStack().isFrozen());
        var tithe=c.tithe.getFirstSpellAbility();tithe.setActivatingPlayer(c.actor);result.add("titheCardCandidates",new Gson().toJsonTree(tithe.getTargetRestrictions().getAllCandidates(tithe).stream().map(GameEntity::getId).toList()));
        result.add("asks",new Gson().toJsonTree(c.host.asks.stream().map(o->o.get("kind").getAsString()).toList()));
        System.out.println("MODAL_ORPHAN_OBS "+result);
        if(controlled)System.out.println("MODAL_ORPHAN_WIRE "+new Gson().toJson(c.host.asks));
        if(controlled&&fault!=null){
            check(failure!=null&&!accepted,"malformed host announcement fails, not Default fallback "+fault);
            check(ids(c.game,ZoneType.Hand).contains(c.reb.getId())&&ids(c.game,ZoneType.Stack).isEmpty()&&stackIds(c.game).isEmpty(),"native rollback restores source and clears orphan");
            check(!c.land.isTapped()&&!c.game.getStack().isFrozen(),"failed announcement leaves no mana tap or frozen stack");
            check(c.blue.isInZone(ZoneType.Battlefield),"invalid action never destroys a substituted target");
            try{c.actor.getController().chooseSpellAbilityToPlay();throw new AssertionError("failed game resumed");}catch(RulesCostFeasibility.Unsupported expected){check(expected.getMessage().contains("cannot continue"),"failed game cannot silently resume");}
        }else if(!blue&&!controlled){
            check(!accepted,"no-legal-mode execution does not succeed");
            check(!ids(c.game,ZoneType.Hand).contains(c.reb.getId())&&ids(c.game,ZoneType.Stack).contains(c.reb.getId())&&!stackIds(c.game).contains(c.reb.getId()),"BUG reproduced: hand card orphaned in Stack zone without MagicStack instance");
            check(!c.land.isTapped(),"orphan created before mana payment");
            check(tithe.getTargetRestrictions().getAllCandidates(tithe).stream().anyMatch(entity->entity.getId()==c.reb.getId()),"BUG reproduced: orphan published as Mana Tithe card target candidate");
            check(controlled==(failure!=null),"controlled path explicitly fails whereas native returns false");
        }else if(blue){
            check(accepted&&failure==null,"valid blue-target control actually casts");
            check(ids(c.game,ZoneType.Stack).contains(c.reb.getId())&&stackIds(c.game).contains(c.reb.getId()),"valid spell has both stack representations");
            if(controlled)check(c.host.asks.stream().map(o->o.get("kind").getAsString()).toList().equals(List.of("priority","mode","targets","payment")),"real modes then targets then payment sequence, no duplicate modes");
            c.game.getStack().resolveStack();check(ids(c.game,ZoneType.Graveyard).contains(c.blue.getId()),"valid REB actually destroys blue target");
        }else{
            check(failure==null&&!accepted,"no-mode menu pass does not attempt a cast");
            check(ids(c.game,ZoneType.Hand).contains(c.reb.getId())&&ids(c.game,ZoneType.Stack).isEmpty()&&stackIds(c.game).isEmpty(),"impossible REB stays in hand without orphan");
            check(c.host.asks.size()==1&&!c.land.isTapped(),"impossible REB asks priority only and spends nothing");
        }
        if(controlled){
            BenchActionAudit.finishGame(c.game,c.session);
            var outcome=new JsonObject();outcome.addProperty("crashed",false);
            BenchMain.guardIntegrityOutcome(c.session,c.game,outcome);
            check(outcome.get("crashed").getAsBoolean()==(fault!=null),"modal choice-to-announcement-to-event receipts admit valid casts/passes and reject every failed announcement");
        }
        if(external){var completed=new JsonObject();completed.addProperty("type","fixture-result");completed.addProperty("case","red-elemental-blast");completed.addProperty("seat",seat);completed.addProperty("passed",accepted&&failure==null&&c.blue.isInZone(ZoneType.Graveyard));completed.addProperty("actualBridgeRoundtrip",true);completed.addProperty("sourceFid",c.reb.getId());completed.addProperty("targetFid",c.blue.getId());protocolOutput.println(completed);}
    }
    private static void stockMode(int seat,BenchSession.Mode mode){
        var c=context(seat,true,true,mode);
        c.host.respond=ask->{var answer=new JsonObject();answer.addProperty("delegate",true);return answer;};
        var sa=c.reb.getFirstSpellAbility();sa.setActivatingPlayer(c.actor);
        check(ComputerUtil.handlePlayingSpellAbility(c.actor,sa,null),"actual stock modal cast succeeds "+mode+" seat="+seat);
        var ledger=((PlayerControllerBridge)c.actor.getController()).getCounters().toJson();
        var buckets=ledger.getAsJsonObject("controllerCoverage").getAsJsonObject("methods").getAsJsonObject("chooseModeForAbility");
        for(String bucket:List.of("host","stock","forced","rules","unclassified"))
            check(buckets.get(bucket).getAsInt()==(bucket.equals("stock")?1:0),"actual stock modal callback ownership "+mode+" "+bucket);
        check(ledger.getAsJsonObject("calls").get("chooseModeForAbility").getAsInt()==1,"stock modal callback counted exactly once");
        c.game.getStack().resolveStack();check(ids(c.game,ZoneType.Graveyard).contains(c.blue.getId()),"stock modal result actually destroys target");
    }
    static void runAll(){for(int seat=0;seat<2;seat++){run(seat,false,false);run(seat,false,true);run(seat,true,false);run(seat,true,true);for(String fault:List.of("mode-delegate","mode-fraction","target-illegal","payment-delegate"))run(seat,true,true,fault);stockMode(seat,BenchSession.Mode.NULL);stockMode(seat,BenchSession.Mode.NULL_PROBE);}System.out.println("PASS "+checks+" controlled modal/rollback and unchanged Default checks");}
    public static void main(String[] args){try{
        external=args.length>1&&args[1].equals("--stdio");protocolOutput=System.out;if(external)System.setOut(System.err);
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"modal-orphan-fixture";default->throw new AssertionError(m.getName());
        }));FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        if(external){run(Integer.parseInt(args[2]),true,true);System.exit(0);}
        runAll();System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
