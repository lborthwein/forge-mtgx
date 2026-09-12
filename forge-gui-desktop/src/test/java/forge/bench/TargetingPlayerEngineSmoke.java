package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Real priority/announcement/payment/resolution, not a match or policy benchmark. */
public final class TargetingPlayerEngineSmoke {
    private static int checks;
    private static String hiddenName="Emrakul, the Aeons Torn";
    private static final Map<Integer,JsonObject> hostResults=new HashMap<>(),hostRandom=new HashMap<>();
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();final List<JsonObject> asks=new ArrayList<>();
        Function<JsonObject,JsonObject> respond;byte[] pending=new byte[0];int at;
        private void prepare(){if(at<pending.length)return;var ask=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).reduce((a,b)->b).orElseThrow();
            asks.add(ask.deepCopy());var answer=respond.apply(ask);answer.addProperty("type","answer");answer.addProperty("id",ask.get("id").getAsInt());
            System.out.println("RAW_ASK "+ask);System.out.println("RAW_ANSWER "+answer);pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);at=0;}
        public int read(){prepare();return pending[at++]&255;}
        public int read(byte[] data,int offset,int length){if(length==0)return 0;prepare();int n=Math.min(length,pending.length-at);System.arraycopy(pending,at,data,offset,n);at+=n;return n;}
    }
    private record Context(Game game,Player actor,Player chooser,PlayerControllerBridge controller,PlayerControllerBridge choosingController,Host host,Card source,Card weak,Card strong,Card secret,boolean hostCasts,String bad){}
    private static Card card(String name,Player owner,ZoneType zone){StaticData.instance().attemptToLoadCard(name);var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),owner);
        c.setGameTimestamp(owner.getGame().getNextTimestamp());owner.getZone(zone).add(c);c.setSickness(false);return c;}
    private static Context context(int seat,boolean hostCasts,String bad){return context(seat,hostCasts,bad,false);}
    private static Context context(int seat,boolean hostCasts,String bad,boolean nativeOnly){
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));var players=new ArrayList<RegisteredPlayer>();
        for(int i=0;i<2;i++){boolean bridge=!nativeOnly&&(i==seat)==hostCasts;var lobby=new LobbyPlayerBridge(bridge?"Host":"Default",null,session,bridge?BenchSession.Mode.BRIDGE:BenchSession.Mode.NULL,i);lobby.setAiProfile("Default");players.add(new RegisteredPlayer(new Deck()).setPlayer(lobby));}
        var game=new Match(new GameRules(GameType.Constructed),players,"TargetingPlayer fixture").createGame();var actor=game.getPlayers().get(seat);var chooser=game.getPlayers().get(1-seat);
        game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);session.setLiveGame(game);
        var source=card("Preacher",actor,ZoneType.Battlefield);var weak=card("Savannah Lions",chooser,ZoneType.Battlefield);var strong=card("Serra Angel",chooser,ZoneType.Battlefield);
        var secret=card(hiddenName,actor,ZoneType.Hand);game.getAction().checkStateEffects(true);
        var c=new Context(game,actor,chooser,(PlayerControllerBridge)actor.getController(),(PlayerControllerBridge)chooser.getController(),host,source,weak,strong,secret,hostCasts,bad);
        host.respond=ask->answer(c,ask);c.controller.getCounters().reset();c.choosingController.getCounters().reset();BenchRandomAudit.install(195901);return c;
    }
    private static JsonObject answer(Context c,JsonObject ask){var answer=new JsonObject();switch(ask.get("kind").getAsString()){
        case "priority"->{check(c.hostCasts,"only host actor receives priority");int index=-1;var menu=ask.getAsJsonArray("menu");for(int i=1;i<menu.size();i++)if(menu.get(i).getAsJsonObject().get("fid").getAsInt()==c.source.getId())index=i;
            check(index>0,"actual Preacher activation offered");answer.addProperty("choice",index);}
        case "payment"->{check(c.hostCasts,"actual actor alone pays");answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);answer.addProperty("x",0);answer.addProperty("lifePaid",0);answer.add("sourceOrder",new JsonArray());answer.add("spend",new JsonArray());}
        case "targets"->{check(!c.hostCasts,"Default chooser never sends host target RPC");check(ask.get("seat").getAsInt()==c.chooser.getId(),"target envelope belongs to actual chooser seat");
            var menu=ask.getAsJsonArray("menu");check(menu.size()==2,"chooser sees exactly its two legal creatures");
            check(menu.asList().stream().map(JsonElement::getAsJsonObject).allMatch(o->o.get("id").getAsInt()==c.weak.getId()||o.get("id").getAsInt()==c.strong.getId()),"opponent Preacher is not offered as illegal target");
            check(!ask.getAsJsonObject("state").toString().contains("Emrakul, the Aeons Torn"),"chooser observation does not reveal caster hand");
            if("delegate".equals(c.bad)){answer.addProperty("delegate",true);break;}
            var ref=new JsonObject();ref.addProperty("kind","card");ref.addProperty("id","illegal".equals(c.bad)?c.source.getId():c.strong.getId());var choices=new JsonArray();choices.add(ref);answer.add("choices",choices);}
        default->throw new AssertionError("unexpected host request "+ask);
    }return answer;}
    private static SpellAbility ability(Context c){var sa=c.source.getSpellAbilities().stream().filter(a->a.hasParam("TargetingPlayer")).findFirst().orElseThrow();sa.setActivatingPlayer(c.actor);return sa;}
    private static JsonObject rng(){var out=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot();out.remove("purityChecks");return out;}
    private static JsonObject ledger(PlayerControllerBridge c,String method){return c.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods").getAsJsonObject(method);}
    private static JsonObject visibleResult(Context c){var state=StateEncoder.encode(c.game,c.actor);for(var player:state.getAsJsonArray("players"))player.getAsJsonObject().remove("name");return state;}
    private static void nativeFullComparison(int seat){
        var c=context(seat,true,null,true);var selected=c.controller.chooseSpellAbilityToPlay().get(0);
        check(selected.getHostCard()==c.source,"native reference actually selects Preacher");check(c.controller.playChosenSpellAbility(selected),"native reference pays and stacks Preacher");
        check(c.game.getStack().peekAbility().getTargetCard().getId()==c.weak.getId(),"native reference assigns same target identity");
        c.game.getStack().resolveStack();c.game.getAction().checkStateEffects(true);
        check(c.source.isTapped()&&c.game.getCardState(c.weak).getController()==c.actor,"native reference pays actual tap and gains same creature");
        check(hostResults.get(seat).equals(visibleResult(c)),"full native-versus-host Preacher resulting actor-visible state matches after display-name normalization");
        check(hostRandom.get(seat).equals(rng()),"full native-versus-host Preacher final RNG matches");
        check(c.host.asks.isEmpty(),"entire native reference makes no host requests");
    }
    private static void refusal(Runnable action,String why){try{action.run();throw new AssertionError("Accepted "+why);}catch(RulesCostFeasibility.Unsupported expected){check(true,why);}}
    private static void nativeComparison(int seat,String name)throws Exception{
        int target=-1;JsonObject finalRandom=null;
        for(boolean scoped:List.of(false,true)){
            var c=context(seat,true,null,!scoped);SpellAbility sa;
            if(name.equals("Preacher"))sa=ability(c);
            else {var source=card(name,c.actor,ZoneType.Hand);sa=source.getFirstSpellAbility();sa.setActivatingPlayer(c.actor);}
            final var before=rng();
            if(scoped){try(var scope=TargetingPlayerRouting.open(sa,c.actor)){check(sa.setupTargets(),"controlled native setupTargets accepts "+name);}}
            else check(sa.setupTargets(),"unmodified native setupTargets accepts "+name);
            check(sa.getTargetingPlayer()==c.chooser&&sa.getTargetCard().getId()==c.weak.getId(),"same actual chooser and Default target in "+name);
            check(before.equals(rng()),"target rules/Default choice consume no RNG in "+name);
            check(c.host.asks.isEmpty(),"native-owned targeting has no host asks in "+name);
            if(!scoped){target=sa.getTargetCard().getId();finalRandom=rng();}
            else check(target==sa.getTargetCard().getId()&&finalRandom.equals(rng()),"native-versus-controlled exact target and RNG parity "+name);
        }
    }
    private static void scopeControls(int seat)throws Exception{
        var c=context(seat,true,null);var sa=ability(c);sa.setTargetingPlayer(c.chooser);var before=BenchMenuStateAudit.capture(c.game);var random=rng();
        var options=new forge.util.collect.FCollection<Player>();options.add(c.chooser);
        try(var scope=TargetingPlayerRouting.open(sa,c.actor)){
            check(TargetingPlayerRouting.forcedChooser(c.actor,sa,options,"Choose the targeting player",false,null,null,null)==c.chooser,"exact mandatory native singleton accepted");
            refusal(()->TargetingPlayerRouting.forcedChooser(c.actor,sa,options,"Other selection",false,null,null,null),"unrelated singleton callback not mislabeled forced");
            refusal(()->TargetingPlayerRouting.forcedChooser(c.actor,sa,options,"Choose the targeting player",true,null,null,null),"optional singleton not mislabeled forced");
            refusal(()->TargetingPlayerRouting.requireController(sa,c.actor,true),"wrong chooser rejected inside exact scope");
            var copy=sa.copyForEnumeration(c.actor);copy.setTargetingPlayer(c.chooser);
            refusal(()->TargetingPlayerRouting.requireController(copy,c.chooser,false),"same-looking copied ability rejected by exact scope");
            try(var inner=TargetingPlayerRouting.open(copy,c.actor)){TargetingPlayerRouting.requireController(copy,c.chooser,false);check(true,"nested routing scope accepts only its own exact chain");}
            TargetingPlayerRouting.requireController(sa,c.chooser,false);check(true,"nested scope restores exact parent");
        }
        check(TargetingPlayerRouting.forcedChooser(c.actor,sa,options,"Choose the targeting player",false,null,null,null)==null,"scope finally removes singleton shortcut");
        var copy=sa.copyForEnumeration(c.actor);copy.setTargetingPlayer(c.chooser);TargetingPlayerRouting.requireController(copy,c.chooser,true);check(true,"native deferred host targeting works outside controlled scope");
        copy.getMapParams().put("TargetingPlayer","TriggeredPlayer");refusal(()->TargetingPlayerRouting.chooser(copy),"unrepresented dependent chooser definition is explicit unsupported");
        BenchMenuStateAudit.assertUnchanged(before,c.game);check(random.equals(rng()),"scope controls and copied definitions preserve live state and RNG");
    }
    private static void mixedChain(int seat){
        var c=context(seat,true,null);var arena=card("Arena",c.actor,ZoneType.Battlefield);var sa=arena.getSpellAbilities().stream().filter(a->a.getApi()==forge.game.ability.ApiType.Tap).findFirst().orElseThrow();sa.setActivatingPlayer(c.actor);
        c.host.respond=ask->{check(ask.get("kind").getAsString().equals("targets"),"mixed Arena root alone asks host for targets");
            check(ask.get("seat").getAsInt()==c.actor.getId(),"mixed root target ask belongs to caster");
            check(ask.getAsJsonArray("menu").size()==1&&ask.getAsJsonArray("menu").get(0).getAsJsonObject().get("id").getAsInt()==c.source.getId(),"Arena root domain is actor creature only");
            var answer=new JsonObject();var choices=new JsonArray();var ref=new JsonObject();ref.addProperty("kind","card");ref.addProperty("id",c.source.getId());choices.add(ref);answer.add("choices",choices);return answer;};
        try(var scope=TargetingPlayerRouting.open(sa,c.actor)){check(sa.setupTargets(),"native mixed-owner Arena chain succeeds");}
        check(sa.getTargetingPlayer()==c.actor&&sa.getTargetCard().getId()==c.source.getId(),"host root Arena target preserved");
        var sub=sa.getSubAbility();check(sub.getTargetingPlayer()==c.chooser&&sub.getTargetCard().getController()==c.chooser,"Default opponent owns Arena subability target");
        check(sub.getParent()==sa&&sub.getSubAbility().getParent()==sub,"native setup preserves full parent chain including untargeted child");
        check(ledger(c.controller,"chooseTargetsFor").get("host").getAsInt()==1&&ledger(c.choosingController,"chooseTargetsFor").get("stock").getAsInt()==1,"mixed-chain ownership counted independently");
    }
    private static void hiddenSubstitution(int seat){
        JsonObject first=null;
        for(String hidden:List.of("Emrakul, the Aeons Torn","Ulamog, the Infinite Gyre")){
            hiddenName=hidden;var c=context(seat,false,null);var sa=ability(c);sa.setTargetingPlayer(c.chooser);sa.clearTargets();var before=rng();
            check(c.choosingController.chooseTargetsFor(sa),"actual host target callback succeeds with substituted private card");
            check(before.equals(rng()),"host target encoding and selection consume no engine RNG");
            var ask=c.host.asks.get(0);check(!ask.toString().contains(hidden),"actual chooser request omits substituted private identity");
            if(first==null)first=ask;else check(first.equals(ask),"entire actual target request invariant to opponent hidden-card substitution");
        }
        hiddenName="Emrakul, the Aeons Torn";
    }
    private static void run(int seat,boolean hostCasts,String bad,boolean baseline)throws Exception{
        var c=context(seat,hostCasts,bad);ability(c);var before=BenchMenuStateAudit.capture(c.game);var random=rng();
        if(hostCasts){var enough=PlayerControllerBridge.class.getDeclaredMethod("hasEnoughTargets",SpellAbility.class);enough.setAccessible(true);
            try{check((Boolean)enough.invoke(null,ability(c)),"pure exact opponent target domain has legal creatures");if(baseline)throw new AssertionError("Expected old containment");}
            catch(java.lang.reflect.InvocationTargetException failure){if(!baseline)throw failure;check(failure.getCause() instanceof RulesCostFeasibility.Unsupported,"baseline actual Preacher observer refuses TargetingPlayer");System.out.println("BASELINE_CONTAINMENT "+failure.getCause());return;}
            BenchMenuStateAudit.assertUnchanged(before,c.game);check(random.equals(rng()),"prospective chooser observation does not mutate state or RNG");}
        SpellAbility selected;
        if(hostCasts)selected=c.controller.chooseSpellAbilityToPlay().get(0);
        else {var choices=c.controller.chooseSpellAbilityToPlay();check(choices!=null&&!choices.isEmpty(),"actual Default selects an action");selected=choices.get(0);check(selected.getHostCard()==c.source,"actual Default selects Preacher");}
        try{check(c.controller.playChosenSpellAbility(selected),"actual chosen Preacher activation executes");if(bad!=null)throw new AssertionError("malformed target accepted");}
        catch(RulesCostFeasibility.Unsupported failure){if(bad==null)throw failure;check(c.game.getStack().isEmpty(),"malformed host targets do not put ability on stack");var counts=ledger(c.choosingController,"chooseTargetsFor");check(counts.get("host").getAsInt()==0&&counts.get("stock").getAsInt()==0&&counts.get("unclassified").getAsInt()==1,"failed host choice never acquires AI or host ownership");return;}
        selected=c.game.getStack().peekAbility();
        check(selected.getActivatingPlayer()==c.actor&&selected.getTargetingPlayer()==c.chooser,"payer/actor unchanged; targeting player is actual opponent");
        check(selected.getTargetCard().getId()==(hostCasts?c.weak:c.strong).getId(),"actual Default chooses weak creature; host choice preserved in reverse direction");
        check(c.source.isTapped(),"Preacher actual tap cost paid by actor");c.game.getStack().resolveStack();c.game.getAction().checkStateEffects(true);
        check(c.game.getCardState(hostCasts?c.weak:c.strong).getController()==c.actor,"actual Preacher resolution gives chosen creature to caster");
        var targetLedger=ledger(c.choosingController,"chooseTargetsFor");check(targetLedger.get(hostCasts?"stock":"host").getAsInt()==1&&targetLedger.get("unclassified").getAsInt()==0,"actual target chooser has exact ownership ledger");
        if(hostCasts){check(c.host.asks.stream().noneMatch(a->a.get("kind").getAsString().equals("targets")||a.get("kind").getAsString().equals("entityChoice")),"opponent targeting is not delegated to host");check(ledger(c.controller,"chooseSingleEntityForEffect").get("forced").getAsInt()==1,"mandatory singleton targeting-player routing is forced exactly once");}
        if(hostCasts){hostResults.put(seat,visibleResult(c));hostRandom.put(seat,rng());}
        System.out.println("ACTUAL_PREACHER seat="+seat+" hostCasts="+hostCasts+" target="+selected.getTargetCard()+" rng="+rng());
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"targeting-player-fixture";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        boolean baseline=args.length>1&&args[1].equals("--baseline");for(int seat=0;seat<2;seat++){run(seat,true,null,baseline);if(!baseline){nativeFullComparison(seat);run(seat,false,null,false);run(seat,false,"illegal",false);run(seat,false,"delegate",false);nativeComparison(seat,"Preacher");nativeComparison(seat,"Evangelize");scopeControls(seat);mixedChain(seat);hiddenSubstitution(seat);}}
        System.out.println("PASS "+checks+" targeting-player checks; no matches or policy-parity claim");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
