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
import forge.player.GamePlayerUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Actual Arc Lightning announcement/payment/resolution; development, not matches. */
public final class DividedTargetExecutionEngineSmoke {
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final List<JsonObject> asks = new ArrayList<>(), answers = new ArrayList<>();
        Function<JsonObject, JsonObject> respond;
        byte[] pending = new byte[0]; int offset, answered;
        private void prepare() {
            if (offset < pending.length) return;
            var sent = wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o -> o.has("type") && "ask".equals(o.get("type").getAsString())).toList();
            var ask = sent.get(sent.size()-1); int id = ask.get("id").getAsInt();
            if (id <= answered) throw new AssertionError("Repeated host read");
            var answer = respond.apply(ask.deepCopy()); answer.addProperty("type", "answer"); answer.addProperty("id", id);
            asks.add(ask.deepCopy()); answers.add(answer.deepCopy());
            pending = (answer + "\n").getBytes(StandardCharsets.UTF_8); offset = 0; answered = id;
        }
        @Override public int read() { prepare(); return pending[offset++] & 255; }
        @Override public int read(byte[] out, int at, int length) { if (length == 0) return 0; prepare(); int n = Math.min(length,pending.length-offset); System.arraycopy(pending,offset,out,at,n); offset+=n; return n; }
    }
    private static Card card(String name, Player p, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(zone).add(c); c.setSickness(false); return c;
    }
    private record Context(Game game, Player actor, PlayerControllerBridge controller, Host host, Card spell, List<Card> lands, Card first, Card second) {}
    private static Context context(int seat) {
        return context(seat,null,null);
    }
    private static Context context(int seat, InputStream externalInput, OutputStream externalOutput) {
        var host = new Host();
        OutputStream wire = externalOutput == null ? host.wire : new OutputStream() {
            @Override public void write(int value) throws IOException { host.wire.write(value); externalOutput.write(value); }
            @Override public void flush() throws IOException { externalOutput.flush(); }
        };
        var rpc = new JsonRpcChannel(externalInput == null ? host : externalInput,wire); var session = new BenchSession(rpc);
        var lobby = new LobbyPlayerBridge("Host",null,session,BenchSession.Mode.BRIDGE,seat); lobby.setAiProfile("Default");
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var opp = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Default",1-seat,0,null,"Default"));
        var game = new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,opp):List.of(opp,own),"Divided fixture").createGame();
        var actor=game.getPlayers().get(seat); var opponent=game.getPlayers().get(1-seat);
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor); session.setLiveGame(game);
        var spell=card("Arc Lightning",actor,ZoneType.Hand); var lands=new ArrayList<Card>();
        for(int i=0;i<3;i++) lands.add(card("Mountain",actor,ZoneType.Battlefield));
        var first=card("Grizzly Bears",opponent,ZoneType.Battlefield); var second=card("Grizzly Bears",opponent,ZoneType.Battlefield);
        game.getAction().checkStateEffects(true); BenchRandomAudit.install(78110);
        return new Context(game,actor,(PlayerControllerBridge)actor.getController(),host,spell,lands,first,second);
    }
    private static JsonObject ref(Card c) { var r=new JsonObject();r.addProperty("kind","card");r.addProperty("id",c.getId());return r; }
    private static JsonObject allocation(Context c, boolean single, String fault) {
        var answer=new JsonObject(); var choices=new JsonArray(); if(!single)choices.add(ref(c.first));choices.add(ref(c.second));answer.add("choices",choices);
        answer.addProperty("targetAllocationVersion","host-explicit-divide-v1"); var divide=new JsonObject();
        if(!single) divide.addProperty("card:"+c.first.getId(),1); divide.addProperty("card:"+c.second.getId(),single?3:2); answer.add("divide",divide);
        String first="card:"+c.first.getId(), second="card:"+c.second.getId();
        if(fault!=null) switch(fault) {
            case "missing" -> answer.remove("divide");
            case "version" -> answer.remove("targetAllocationVersion");
            case "wrong-type" -> answer.addProperty("divide",true);
            case "string" -> divide.addProperty(first,"1");
            case "fraction" -> divide.addProperty(first,1.5);
            case "zero" -> divide.addProperty(first,0);
            case "negative" -> divide.addProperty(first,-1);
            case "overflow" -> divide.addProperty(first,4294967297L);
            case "sum" -> divide.addProperty(second,1);
            case "omit" -> divide.remove(second);
            case "extra" -> divide.addProperty("card:999999",1);
            case "bare" -> { divide.remove(first); divide.addProperty(String.valueOf(c.first.getId()),1); }
            default -> throw new AssertionError(fault);
        }
        return answer;
    }
    private static JsonObject payment(JsonObject ask) {
        var answer=new JsonObject(); answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);answer.addProperty("x",0);answer.addProperty("lifePaid",0);
        var order=new JsonArray();var spend=new JsonArray();int index=0;
        for(var raw:ask.getAsJsonArray("sourceOptions")) {
            var source=raw.getAsJsonObject();String id=source.get("id").getAsString();order.add(id);
            var token=new JsonObject();token.addProperty("token",id+":0");token.addProperty("shardIndex",index++);spend.add(token);
        }
        check(index==3,"three real red sources");answer.add("sourceOrder",order);answer.add("spend",spend);return answer;
    }
    private static void run(int seat, boolean single, String fault) {
        var c=context(seat);
        c.host.respond=ask->{
            switch(ask.get("kind").getAsString()) {
                case "priority": {
                    int selected=-1;var menu=ask.getAsJsonArray("menu");
                    for(int i=1;i<menu.size();i++) if(menu.get(i).getAsJsonObject().get("fid").getAsInt()==c.spell.getId()) { check(selected==-1,"unique spell choice");selected=i; }
                    check(selected>0,"actual Arc Lightning offered");var answer=new JsonObject();answer.addProperty("choice",selected);return answer;
                }
                case "targets": {
                    check(ask.get("targetAllocationVersion").getAsString().equals("host-explicit-divide-v1"),"allocation authority explicit");
                    check(ask.get("targetSelectionMode").getAsString().equals("replace-all")&&ask.get("allocationTotal").getAsInt()==3,"whole-target replacement total");
                    return allocation(c,single,fault);
                }
                case "payment": return payment(ask);
                default: throw new AssertionError("unexpected ask "+ask);
            }
        };
        RulesCostFeasibility.Unsupported refusal=null;SpellAbility selected=c.controller.chooseSpellAbilityToPlay().get(0); boolean accepted=false;
        check(c.host.asks.size()==1,"selection only asks priority before target allocation");
        try { accepted=c.controller.playChosenSpellAbility(selected); }
        catch(RulesCostFeasibility.Unsupported expected) { refusal=expected; }
        if(fault!=null) {
            check(refusal!=null&&!accepted,"invalid allocation explicitly rejected during execution "+fault);
            check(c.game.getStack().isEmpty()&&c.lands.stream().noneMatch(Card::isTapped),"invalid allocation neither casts nor spends");
            check(c.first.getDamage()==0&&c.second.getDamage()==0,"invalid allocation does not resolve damage");
            check(c.host.asks.size()==2,"no payment request after invalid targeting");
        } else {
            check(refusal==null&&accepted,"host target selection succeeds during execution");
            check(selected.getDividedValue(c.second)==(single?3:2),"exact host second-target amount retained");
            if(!single) check(selected.getDividedValue(c.first)==1,"host1:2 not replaced by old2:1 even split");
            c.game.getStack().resolveStack();c.game.getAction().checkStateEffects(true);
            check(c.first.isInZone(ZoneType.Battlefield)&&c.first.getDamage()==(single?0:1),"first Bears survives exact requested damage");
            check(c.actor.getOpponents().get(0).getCardsIn(ZoneType.Graveyard).stream().anyMatch(card->card.getId()==c.second.getId()),"second Bears dies from actual allocated damage");
            check(c.lands.stream().allMatch(Card::isTapped),"actual complete casting cost paid");
        }
        System.out.println("DIVIDED_WIRE "+new com.google.gson.Gson().toJson(Map.of("seat",seat,"single",single,"fault",fault==null?"none":fault,"asks",c.host.asks,"answers",c.host.answers)));
    }
    private static void reentrantAndAtomic(int seat) throws Exception {
        var c=context(seat);var sa=c.spell.getFirstSpellAbility();sa.setActivatingPlayer(c.actor);sa.clearTargets();sa.getTargets().add(c.first);sa.addDividedAllocation(c.first,2);
        var method=PlayerControllerBridge.class.getDeclaredMethod("applyDividedAllocation",SpellAbility.class,JsonObject.class,List.class);method.setAccessible(true);
        sa.getTargets().add(c.second);
        var before=List.copyOf(sa.getTargets().getDividedValues());
        String error=(String)method.invoke(null,sa,allocation(c,false,"sum"),List.of());
        check(error!=null&&before.equals(List.copyOf(sa.getTargets().getDividedValues())),"invalid full allocation does not partially mutate metadata");
        sa.resetTargets();sa.getTargets().add(c.first);sa.addDividedAllocation(c.first,2);
        c.host.respond=ask->{check(ask.get("divideRemaining").getAsInt()==1&&ask.get("allocationTotal").getAsInt()==3,"reentry exposes old remainder but full replacement budget");return allocation(c,false,null);};
        var strict=PlayerControllerBridge.class.getDeclaredField("selectingExternalTargets");strict.setAccessible(true);strict.set(c.controller,true);
        try {check(c.controller.chooseTargetsFor(sa),"real reentrant target callback succeeds");} finally{strict.set(c.controller,false);}
        check(sa.getTotalDividedValue()==3&&sa.getDividedValue(c.first)==1&&sa.getDividedValue(c.second)==2,"reentry replaces rather than doubles old allocation");
    }
    private static JsonObject externalCast(int seat, PrintStream protocol) {
        var c=context(seat,System.in,protocol);
        int firstLife=c.game.getPlayers().get(0).getLife(),secondLife=c.game.getPlayers().get(1).getLife();
        var selected=c.controller.chooseSpellAbilityToPlay();
        check(selected!=null&&selected.size()==1&&selected.get(0).getHostCard().getId()==c.spell.getId(),"real external host selected Arc Lightning");
        var sa=selected.get(0);check(c.controller.playChosenSpellAbility(sa),"real external payment and cast execute");var targets=new ArrayList<GameObject>();for(var t:sa.getTargets())targets.add(t);
        check(targets.size()==1&&sa.getTotalDividedValue()==3&&sa.getDividedValue(targets.get(0))==3,"existing TS policy's single target has explicit full allocation");
        var target=targets.get(0);
        check(target instanceof Player||target==c.first||target==c.second,"actual target resolves to offered fixture entity");
        c.game.getStack().resolveStack();c.game.getAction().checkStateEffects(true);
        for(int i=0;i<2;i++)check(c.game.getPlayers().get(i).getLife()==(i==0?firstLife:secondLife)-(target==c.game.getPlayers().get(i)?3:0),"exact resolved player damage seat="+i);
        for(var bear:List.of(c.first,c.second)) {
            boolean dead=c.actor.getOpponents().get(0).getCardsIn(ZoneType.Graveyard).stream().anyMatch(card->card.getId()==bear.getId());
            check(dead==(target==bear),"exact resolved creature damage fid="+bear.getId());
        }
        check(c.lands.stream().allMatch(Card::isTapped),"external full cost actually paid");
        var out=new JsonObject();out.addProperty("type","fixture-result");out.addProperty("passed",true);out.addProperty("seat",seat);
        out.addProperty("scope","ACTUAL_TS_TARGET_ALLOCATION_AND_PAYMENT_NOT_POLICY_PARITY_OR_STRENGTH");
        out.addProperty("targetKind",target instanceof Player?"player":"card");out.addProperty("targetId",((GameEntity)target).getId());out.addProperty("allocated",3);
        return out;
    }
    public static void main(String[] args) {
        boolean stdio=args.length>1&&args[1].equals("--stdio");PrintStream protocol=System.out;
        try {
            if(stdio)System.setOut(System.err);
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir" -> args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "divided-target-fixture";
                default -> throw new AssertionError(m.getName());
            }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            if(stdio) {protocol.println(externalCast(Integer.parseInt(args[2]),protocol));System.exit(0);}
            for(int seat=0;seat<2;seat++) {
                run(seat,true,null);run(seat,false,null);
                for(String fault:List.of("missing","version","wrong-type","string","fraction","zero","negative","overflow","sum","omit","extra","bare"))run(seat,false,fault);
                reentrantAndAtomic(seat);
            }
            System.out.println("PASS "+checks+" actual divided-target checks; scripted host allocations, no strength claim");System.exit(0);
        } catch(Throwable failure) { failure.printStackTrace();System.exit(1); }
    }
}
