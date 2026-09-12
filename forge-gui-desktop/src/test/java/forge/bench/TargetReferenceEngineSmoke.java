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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Actual target callbacks on real card abilities; no match or policy substitute. */
public final class TargetReferenceEngineSmoke {
    private static int checks, seat;
    private static void check(boolean ok,String label) { if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label); }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();
        Function<JsonObject,JsonObject> responder; byte[] pending=new byte[0];int at,answered;
        private void prepare() {
            if(at<pending.length)return;
            var asks=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).toList();
            var ask=asks.get(asks.size()-1);int id=ask.get("id").getAsInt();check(id>answered,"monotonic actual request");
            var answer=responder.apply(ask.deepCopy());answer.addProperty("type","answer");answer.addProperty("id",id);
            System.out.println("RAW_ASK "+ask);System.out.println("RAW_ANSWER "+answer);
            pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);at=0;answered=id;
        }
        public int read(){prepare();return pending[at++]&255;}
        public int read(byte[] data,int offset,int length){if(length==0)return 0;prepare();int n=Math.min(length,pending.length-at);System.arraycopy(pending,at,data,offset,n);at+=n;return n;}
    }
    private record Context(Game game,Player actor,PlayerControllerBridge controller,Host host,SpellAbility sa,Card bear,Card other,SpellAbility onStack) {}
    private static Card card(String name,Player p,ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());(zone==ZoneType.Stack?p.getGame().getStackZone():p.getZone(zone)).add(c);c.setSickness(false);return c;
    }
    private static Context context(String spellName,boolean stack) {
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        var lobby=new LobbyPlayerBridge("Host",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var otherPlayer=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Default",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,otherPlayer):List.of(otherPlayer,own),"Target reference fixture").createGame();
        var actor=game.getPlayers().get(seat);var opponent=game.getPlayers().get(1-seat);
        game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);session.setLiveGame(game);
        var bear=card("Grizzly Bears",opponent,ZoneType.Battlefield);var other=card("Grizzly Bears",opponent,ZoneType.Battlefield);
        var printed=card(spellName,actor,ZoneType.Hand);for(int i=0;i<3;i++){card("Island",actor,ZoneType.Battlefield);card("Mountain",actor,ZoneType.Battlefield);}
        SpellAbility onStack=null;
        if(stack){var creature=card("Savannah Lions",opponent,ZoneType.Stack);onStack=creature.getFirstSpellAbility();onStack.setActivatingPlayer(opponent);game.getStack().add(onStack);}
        game.getAction().checkStateEffects(true);
        var sa=printed.getFirstSpellAbility().copyForEnumeration(actor);sa.setActivatingPlayer(actor);sa.clearTargets();sa.setTargetingPlayer(actor);
        var controller=(PlayerControllerBridge)actor.getController();controller.getCounters().reset();BenchRandomAudit.install(81317);
        return new Context(game,actor,controller,host,sa,bear,other,onStack);
    }
    private static JsonObject ref(String kind,int id) {var out=new JsonObject();out.addProperty("kind",kind);out.addProperty("id",id);return out;}
    private static JsonObject rng(){var out=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot();out.remove("purityChecks");return out;}
    private static int stackWire(Context c){return StateEncoder.SPELL_TARGET_ID_BASE+c.game.getStack().peek().getId();}
    private static JsonElement malformed(String mode,String kind,int id) {
        JsonElement value=switch(mode){
            case "string","bare-string" -> new JsonPrimitive(String.valueOf(id));
            case "fraction","bare-fraction" -> JsonParser.parseString(id+".75");
            case "overflow","bare-overflow" -> new JsonPrimitive(4294967296L+id);
            case "boolean","bare-boolean" -> new JsonPrimitive(true);
            case "array","bare-array" -> JsonParser.parseString("["+id+"]");
            default -> new JsonPrimitive(id);
        };
        if(mode.startsWith("bare-"))return value;
        var out=new JsonObject();out.add("id",value);out.addProperty("kind",kind);
        if(mode.equals("kind-array"))out.add("kind",JsonParser.parseString("[\""+kind+"\"]"));
        if(mode.equals("kind-object"))out.add("kind",new JsonObject());
        if(mode.equals("unknown-kind"))out.addProperty("kind","creature");
        if(mode.equals("negative"))out.addProperty("id",-1);
        return out;
    }
    private static void call(Context c,List<JsonElement> choices,List<GameObject> expected,String label,boolean characterize) throws Exception {
        // This is the real callback in external-target mode, as reached by
        // priority/trigger prep. The flag only keeps existing strict refusal
        // semantics; no target parser/helper is called reflectively.
        var strict=PlayerControllerBridge.class.getDeclaredField("selectingExternalTargets");strict.setAccessible(true);strict.set(c.controller,true);
        c.sa.getTargets().add(c.onStack==null?c.other:c.onStack);
        if(c.sa.isDividedAsYouChoose())c.sa.addDividedAllocation(c.other,3);
        var previous=c.sa.getTargets();var previousList=List.copyOf(previous);var previousDivide=List.copyOf(previous.getDividedValues());
        var state=BenchMenuStateAudit.capture(c.game);var before=rng();
        c.host.responder=ask->{
            check(ask.get("kind").getAsString().equals("targets"),"actual targets callback only");
            var answer=new JsonObject();var array=new JsonArray();for(var choice:choices)array.add(choice);answer.add("choices",array);
            if(c.sa.isDividedAsYouChoose()){
                answer.addProperty("targetAllocationVersion","host-explicit-divide-v1");var divide=new JsonObject();
                if(expected!=null)for(int i=0;i<expected.size();i++){
                    var target=expected.get(i);String key=target instanceof Player?"player:"+((Player)target).getId():target instanceof Card?"card:"+((Card)target).getId():"spell:"+stackWire(c);
                    divide.addProperty(key,expected.size()==1?3:i==0?1:2);
                }
                answer.add("divide",divide);
            }
            return answer;
        };
        Throwable rejected=null;boolean success=false;
        try{success=c.controller.chooseTargetsFor(c.sa);}catch(Throwable failure){rejected=failure;}finally{strict.set(c.controller,false);}
        System.out.println("TARGET_REFERENCE_CASE seat="+seat+" label="+label+" success="+success+" error="+(rejected==null?"none":rejected)+" selected="+c.sa.getTargets());
        if(!characterize){
            if(expected==null){
                check(rejected instanceof RulesCostFeasibility.Unsupported,"malformed/illegal ref explicitly refused: "+label);
                check(!success&&c.sa.getTargets()==previous&&previousList.equals(List.copyOf(c.sa.getTargets()))&&previousDivide.equals(List.copyOf(c.sa.getTargets().getDividedValues())),"rejected selection restores entire previous target object and allocations");
                check(c.controller.getCounters().toJson().getAsJsonObject("delegatedRefused").get("chooseTargetsFor").getAsInt()==1,"refusal counted without Default substitution");
            }else{
                check(rejected==null&&success,"valid representation accepted: "+label);
                check(expected.equals(List.copyOf(c.sa.getTargets()))&&c.sa.isTargetNumberValid(),"exact typed identities and target cardinality retained");
            }
        }
        check(before.equals(rng()),"target decoder does not consume RNG");BenchMenuStateAudit.assertUnchanged(state,c.game);checks++;
    }
    private static void malformedCases(boolean characterize) throws Exception {
        for(String kind:List.of("card","player","spell"))for(String mode:List.of("string","fraction","overflow","boolean","array","bare-string","bare-fraction","bare-overflow","bare-boolean","bare-array","kind-array","kind-object","unknown-kind","negative")){
            var c=context(kind.equals("spell")?"Counterspell":"Lightning Bolt",kind.equals("spell"));
            int id=kind.equals("card")?c.bear.getId():kind.equals("player")?c.game.getPlayers().get(1).getId():stackWire(c);
            call(c,List.of(malformed(mode,kind,id)),null,kind+":"+mode,characterize);
        }
    }
    private static void validAndIllegal() throws Exception {
        for(String kind:List.of("card","player","spell"))for(String format:List.of("typed","legacy","id-object","null-kind-object","kind-normalized","exact-decimal")){
            var c=context(kind.equals("spell")?"Counterspell":"Lightning Bolt",kind.equals("spell"));
            int id=kind.equals("card")?c.other.getId():kind.equals("player")?0:stackWire(c);
            GameObject expected=kind.equals("card")?c.other:kind.equals("player")?c.game.getPlayers().get(0):c.onStack;
            JsonElement encoded=ref(kind,id);
            if(format.equals("legacy"))encoded=new JsonPrimitive(id);
            else if(format.equals("id-object"))encoded.getAsJsonObject().remove("kind");
            else if(format.equals("null-kind-object"))encoded.getAsJsonObject().add("kind",JsonNull.INSTANCE);
            else if(format.equals("kind-normalized"))encoded.getAsJsonObject().addProperty("kind"," "+kind.toUpperCase(Locale.ROOT)+" ");
            else if(format.equals("exact-decimal"))encoded.getAsJsonObject().add("id",JsonParser.parseString(id+".0"));
            call(c,List.of(encoded),List.of(expected),kind+":"+format,false);
            var instruments=c.controller.getCounters().toJson().getAsJsonObject("instruments");
            int legacy=instruments.has("legacy.untypedRef")?instruments.get("legacy.untypedRef").getAsInt():0;
            check(legacy==(List.of("legacy","id-object","null-kind-object").contains(format)?1:0),"all and only null-kind target refs count as legacy ambiguity");
        }
        var rawStack=context("Counterspell",true);call(rawStack,List.of(ref("spell",rawStack.game.getStack().peek().getId())),List.of(rawStack.onStack),"typed raw stack ID alias",false);
        for(String kind:List.of("card","player","spell")){
            var c=context(kind.equals("spell")?"Double Negative":kind.equals("card")?"Frost Breath":"Arc Lightning",kind.equals("spell"));
            int id=kind.equals("card")?c.bear.getId():kind.equals("player")?0:stackWire(c);
            var first=ref(kind,id);var second=kind.equals("spell")?ref(kind,c.game.getStack().peek().getId()):ref(kind,id);
            call(c,List.of(first,second),null,"duplicate "+kind+" including stack aliases",false);
        }
        var illegal=context("Frost Breath",false);call(illegal,List.of(ref("player",0)),null,"real creature-only ability refuses player",false);
        var wrongKind=context("Counterspell",true);call(wrongKind,List.of(ref("card",stackWire(wrongKind))),null,"stack wire ID with card kind not reinterpreted",false);
        var collision=context("Arc Lightning",false);check(collision.bear.getId()==1,"real card/player ID collision installed");
        call(collision,List.of(ref("card",1),ref("player",1)),List.of(collision.bear,collision.game.getPlayers().get(1)),"typed card1/player1 remain distinct targets with explicit division",false);
        var legacyCollision=context("Lightning Bolt",false);call(legacyCollision,List.of(new JsonPrimitive(1)),List.of(legacyCollision.game.getPlayers().get(1)),"legacy collision retains documented first-menu-match behavior",false);
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";
            case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"target-reference-fixture";
            default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        boolean characterize=args.length>1&&args[1].equals("--characterize");
        for(seat=0;seat<2;seat++){BenchRandomAudit.install(81317);malformedCases(characterize);if(!characterize)validAndIllegal();}
        System.out.println("PASS "+checks+" bounded target-reference checks; actual callbacks, not games or complete-control proof");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
