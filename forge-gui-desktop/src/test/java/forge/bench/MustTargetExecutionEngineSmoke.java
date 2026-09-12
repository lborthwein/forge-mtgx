package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
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

/** Real priority selection, exact payment, cast and resolution; no match. */
public final class MustTargetExecutionEngineSmoke {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream(); final List<JsonObject> asks=new ArrayList<>();
        Function<JsonObject,JsonObject> responder;byte[] pending=new byte[0];int at,answered,targetIndex;
        private void prepare(){if(at<pending.length)return;var all=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).toList();var ask=all.get(all.size()-1);int id=ask.get("id").getAsInt();
            check(id>answered,"monotonic actual host ask");var answer=responder.apply(ask);answer.addProperty("type","answer");answer.addProperty("id",id);asks.add(ask.deepCopy());
            System.out.println("RAW_ASK "+ask);System.out.println("RAW_ANSWER "+answer);pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);at=0;answered=id;}
        public int read(){prepare();return pending[at++]&255;}
        public int read(byte[] data,int offset,int length){if(length==0)return 0;prepare();int n=Math.min(length,pending.length-at);System.arraycopy(pending,at,data,offset,n);at+=n;return n;}
    }
    private record Context(Game game,Player actor,Player opponent,PlayerControllerBridge controller,Host host,Card spell,Card bearer,Card bear,List<Card> sources,String scenario) {}
    private static Card card(String name,Player player,ZoneType zone){StaticData.instance().attemptToLoadCard(name);var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),player);
        c.setGameTimestamp(player.getGame().getNextTimestamp());player.getZone(zone).add(c);c.setSickness(false);return c;}
    private static Context context(int seat,String scenario){
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));var lobby=new LobbyPlayerBridge("Host",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Default",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"MustTarget fixture").createGame();var actor=game.getPlayers().get(seat);var opponent=game.getPlayers().get(1-seat);
        game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);session.setLiveGame(game);
        var bearer=card("Standard Bearer",scenario.equals("bolt-own")?actor:opponent,scenario.equals("bolt-inactive")?ZoneType.Graveyard:ZoneType.Battlefield);
        var bear=scenario.equals("seeds-sole")?null:card("Grizzly Bears",actor,ZoneType.Battlefield);
        var spell=card(scenario.startsWith("seeds")?"Seeds of Strength":"Lightning Bolt",actor,ZoneType.Hand);
        var sources=new ArrayList<Card>();for(String name:scenario.startsWith("seeds")?List.of("Forest","Plains"):List.of("Mountain"))sources.add(card(name,actor,ZoneType.Battlefield));
        game.getAction().checkStateEffects(true);var c=new Context(game,actor,opponent,(PlayerControllerBridge)actor.getController(),host,spell,bearer,bear,sources,scenario);
        host.responder=ask->answer(c,ask);c.controller.getCounters().reset();BenchRandomAudit.install(82301);return c;
    }
    private static JsonObject ref(GameObject target){var r=new JsonObject();r.addProperty("kind",target instanceof Player?"player":"card");r.addProperty("id",((GameEntity)target).getId());return r;}
    private static JsonObject answer(Context c,JsonObject ask){var answer=new JsonObject();switch(ask.get("kind").getAsString()){
        case "priority"->{int choice=-1;var menu=ask.getAsJsonArray("menu");for(int i=1;i<menu.size();i++)if(menu.get(i).getAsJsonObject().get("fid").getAsInt()==c.spell.getId())choice=i;
            check(choice>0,"actual real spell offered despite mandatory target constraint");answer.addProperty("choice",choice);}
        case "targets"->{int index=c.host.targetIndex++;GameObject target;
            if(c.scenario.startsWith("seeds"))target=c.scenario.equals("seeds-sole")||c.scenario.equals("seeds-last")&&index==2?c.bearer:c.bear;
            else target=c.scenario.equals("bolt-bearer")?c.bearer:c.opponent;
            check(ask.getAsJsonArray("menu").asList().stream().map(JsonElement::getAsJsonObject).anyMatch(o->o.get("id").getAsInt()==((GameEntity)target).getId()&&o.get("kind").getAsString().equals(target instanceof Player?"player":"card")),"chosen target individually legal and explicitly offered");
            if(c.scenario.equals("seeds-sole"))check(ask.getAsJsonArray("menu").size()==1,"sole legal creature target offered in each group");
            var choices=new JsonArray();choices.add(ref(target));answer.add("choices",choices);}
        case "payment"->{answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);answer.addProperty("x",0);answer.addProperty("lifePaid",0);var order=new JsonArray();var spend=new JsonArray();
            int shardIndex=0;for(var shard:ask.getAsJsonObject("cost").getAsJsonArray("shards")){
                String color=switch(shard.getAsString()){case "RED"->"R";case "GREEN"->"G";case "WHITE"->"W";default->throw new AssertionError(shard);};
                var source=ask.getAsJsonArray("sourceOptions").asList().stream().map(JsonElement::getAsJsonObject).filter(o->o.getAsJsonArray("output").get(0).getAsString().equals(color)).findFirst().orElseThrow();
                String id=source.get("id").getAsString();order.add(id);var token=new JsonObject();token.addProperty("token",id+":0");token.addProperty("shardIndex",shardIndex++);spend.add(token);
            }answer.add("sourceOrder",order);answer.add("spend",spend);}
        default->throw new AssertionError("unexpected host ask "+ask);
    }return answer;}
    private static boolean valid(String scenario){return !scenario.equals("bolt-illegal")&&!scenario.equals("seeds-illegal");}
    private static JsonObject rng(){var result=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot();result.remove("purityChecks");return result;}
    private static void nativeSetup(int seat,String scenario){var c=context(seat,scenario);var sa=c.spell.getFirstSpellAbility().copyForEnumeration(c.actor);sa.setActivatingPlayer(c.actor);
        var before=rng();boolean accepted=sa.setupTargets();check(accepted==valid(scenario),"native setupTargets whole-chain verdict "+scenario);
        check(StaticAbilityMustTarget.meetsMustTargetRestriction(sa)==valid(scenario),"independent native restriction verdict "+scenario);check(before.equals(rng()),"native rules validation no RNG draw");
        System.out.println("NATIVE_MUST_TARGET seat="+seat+" scenario="+scenario+" accepted="+accepted+" groups="+c.host.targetIndex);
    }
    private static void run(int seat,String scenario,boolean characterize){nativeSetup(seat,scenario);var c=context(seat,scenario);var before=rng();var state=BenchMenuStateAudit.capture(c.game);
        SpellAbility chosen=c.controller.chooseSpellAbilityToPlay().get(0);
        check(chosen!=null,"priority selection returns the offered action before controlled announcement");
        check(c.host.asks.stream().map(a->a.get("kind").getAsString()).toList().equals(List.of("priority")),"selection performs action/X choice only; no target or payment ask");
        check(before.equals(rng()),"action/X selection consumes no RNG");BenchMenuStateAudit.assertUnchanged(state,c.game);checks++;
        RulesCostFeasibility.Unsupported failure=null;boolean accepted=false;
        try{accepted=c.controller.playChosenSpellAbility(chosen);}catch(RulesCostFeasibility.Unsupported expected){failure=expected;}
        boolean rule=StaticAbilityMustTarget.meetsMustTargetRestriction(chosen);
        System.out.println("BRIDGE_MUST_TARGET seat="+seat+" scenario="+scenario+" selected=true accepted="+accepted+" rulesAccept="+rule+" error="+failure);
        if(!valid(scenario)){
            check(failure!=null&&!accepted&&failure.getMessage().contains("target"),"whole-chain forbidden targets explicitly fail during execution");
            check(c.host.targetIndex==(scenario.startsWith("seeds")?3:1),"execution requests all target groups before whole-chain validation");
            check(c.host.asks.stream().noneMatch(a->a.get("kind").getAsString().equals("payment")),"invalid whole-chain target selection rejects before payment RPC");
            check(c.sources.stream().noneMatch(Card::isTapped)&&c.actor.getLife()==20&&c.opponent.getLife()==20&&c.game.getStack().isEmpty(),"forbidden spell neither spends, changes life, nor casts");
            try{c.controller.chooseSpellAbilityToPlay();throw new AssertionError("failed game resumed");}catch(RulesCostFeasibility.Unsupported expected){check(expected.getMessage().contains("cannot continue"),"failed execution invalidates the game permanently");}
            // Native rollback changes internal IDs/permissions; only the external
            // safety receipts above are reversible invariants.
            if(characterize)System.out.println("CHARACTERIZATION selection previously performed target validation; integrated lifecycle validates only during execution");
            checks++;return;
        }
        check(failure==null&&accepted,"actual selected spell accepted for execution");
        check(rule==valid(scenario),"executed target chain agrees with independent rules verdict");
        if(scenario.equals("seeds-last")){check(chosen.getTargetCard()==c.bear&&chosen.getSubAbility().getTargetCard()==c.bear&&chosen.getSubAbility().getSubAbility().getTargetCard()==c.bearer,"host first two ordinary targets preserved; requirement fulfilled only in final group");}
        c.game.getStack().resolveStack();c.game.getAction().checkStateEffects(true);
        check(c.sources.stream().allMatch(Card::isTapped),"full printed cost actually spent");
        if(scenario.startsWith("seeds")){
            check(c.bearer.getNetPower()==(scenario.equals("seeds-sole")?4:scenario.equals("seeds-last")?2:1),"actual Flagbearer pump outcome");
            if(c.bear!=null)check(c.bear.getNetPower()==(scenario.equals("seeds-last")?4:5),"actual ordinary creature pump outcome");
        }else if(scenario.equals("bolt-bearer"))check(c.opponent.getCardsIn(ZoneType.Graveyard).stream().anyMatch(card->card.getId()==c.bearer.getId())&&c.opponent.getLife()==20,"required Flagbearer dies and player unhurt");
        else check(c.opponent.getLife()==17,"actual player damage outcome, including preserved baseline illegal cast");
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"must-target-fixture";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        boolean characterize=args.length>1&&args[1].equals("--characterize");
        for(int seat=0;seat<2;seat++)for(String scenario:characterize?List.of("bolt-illegal","seeds-illegal"):List.of("bolt-illegal","seeds-illegal","bolt-bearer","seeds-last","seeds-sole","bolt-own","bolt-inactive"))run(seat,scenario,characterize);
        System.out.println("PASS "+checks+" actual MustTarget cast checks; no matches or playing-strength claim");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
