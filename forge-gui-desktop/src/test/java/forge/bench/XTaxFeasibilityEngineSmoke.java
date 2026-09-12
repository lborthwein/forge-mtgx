package forge.bench;

import forge.StaticData;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.List;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import com.google.gson.*;

/** Real scripts, explicit fixture-chosen X, no matches or playing-strength claim. */
public final class XTaxFeasibilityEngineSmoke {
    private static int checks;
    private static final Map<String,String> nativeResults=new HashMap<>();
    private static final Map<String,String> nativePaidMetadata=new HashMap<>();
    private static final Map<String,Long> nativeDraws=new HashMap<>();
    private static final java.lang.reflect.Field ACTIVE_RULES_PAYMENT = activeRulesPaymentField();
    private static java.lang.reflect.Field activeRulesPaymentField() {
        try {
            var field=PlayerControllerBridge.class.getDeclaredField("activeRulesPayment");
            field.setAccessible(true);return field;
        } catch (ReflectiveOperationException failure) { throw new AssertionError("fixture requires active payment receipt",failure); }
    }
    private static String paidMetadata(forge.game.mana.ManaCostBeingPaid paid) {
        return paid.isPaid()+"/"+paid.getXcounter()+"/"+new TreeMap<>(paid.getXManaCostPaidByColor()==null?Map.of():paid.getXManaCostPaidByColor())+"/"+paid.getColorsPaid()+"/"+paid.getSunburst();
    }
    private static String resolvedState(Game game,int seat,SpellAbility sa) {
        var out=new StringBuilder().append(sa.getXManaCostPaid()).append('/').append(sa.getPayingMana().size()).append('/').append(game.getStack().size());
        for(var player:game.getPlayers()) {
            out.append('/').append(player.getLife()).append('/').append(player.getManaPool().totalMana());
            for(var zone:List.of(ZoneType.Hand,ZoneType.Graveyard)) out.append('/').append(player.getCardsIn(zone).stream().map(Card::getName).sorted().toList());
            out.append('/').append(player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).map(c->c.getName()+":"+c.getNetPower()+"/"+c.getNetToughness()).sorted().toList());
        }
        var rng=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot();rng.remove("purityChecks");
        return out.append('/').append(rng).toString();
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static Game game(int seat) {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0",0,0,null,"Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1",1,0,null,"Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Explicit X tax fixture").createGame();
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(seat));
        return game;
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing pinned script " + name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp()); player.getZone(zone).add(card); card.setSickness(false);
        return card;
    }
    private static SpellAbility setup(Game game, int seat, String name, int landCount) {
        var player = game.getPlayers().get(seat);
        for (int i=0;i<landCount;i++) card(name.equals("Mind Twist") ? "Swamp" : i==0 ? "Mountain" : "Plains", player, ZoneType.Battlefield);
        card("Thalia, Guardian of Thraben",game.getPlayers().get(1-seat),ZoneType.Battlefield);
        for (String hand : List.of("Forest","Island","Mountain")) card(hand,game.getPlayers().get(1-seat),ZoneType.Hand);
        var sa = card(name,player,ZoneType.Hand).getFirstSpellAbility(); sa.setActivatingPlayer(player);
        if (sa.usesTargeting()) sa.getTargets().add(game.getPlayers().get(1-seat));
        game.getAction().checkStateEffects(true); return sa;
    }
    private static void floatSetup(Game game,Player player) {
        var previous=player.getController();player.dangerouslySetController(new PlayerControllerAi(game,player,player.getLobbyPlayer()));
        try {for(var land:List.copyOf(player.getCardsIn(ZoneType.Battlefield))) {
            if(!land.isLand())continue;var mana=land.getManaAbilities().get(0);mana.setActivatingPlayer(player);
            check(ComputerUtil.handlePlayingSpellAbility(player,mana,null),"fixture setup actually floats land mana before measured boundary");
        }}finally{player.dangerouslySetController(previous);}
    }
    private static void nativeSequence(String name, int seat,boolean floating) {
        var game=game(seat); var player=game.getPlayers().get(seat); var sa=setup(game,seat,name,name.equals("Mind Twist")?4:5);
        if(floating)floatSetup(game,player);
        String key=name+seat+floating;
        sa.setXManaCostPaid(2);
        player.dangerouslySetController(new PlayerControllerAi(game,player,player.getLobbyPlayer()) {
            @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility actual, String prompt, ManaConversionMatrix matrix, boolean effect) {
                System.out.println("CALLBACK native " + name + " cost="+cost+" chosenX="+actual.getXManaCostPaid()+" zone="+actual.getHostCard().getZone());
                if (actual.getHostCard().getId()==sa.getHostCard().getId()) check(cost.countX()==1 && cost.getGenericCost()==1,
                        "native callback retains symbolic X plus exactly one Thalia tax");
                if(actual.getHostCard().getId()==sa.getHostCard().getId()) {
                    // Exact stock wrapper decomposition, retaining its local paid
                    // object solely so the fixture can inspect X-by-color metadata.
                    var expanded=forge.ai.ComputerUtilMana.calculateManaCost(new forge.game.cost.Cost(cost,effect),actual,player,false,0,effect);
                    boolean ok=forge.ai.ComputerUtilMana.payManaCost(expanded,actual,player,effect);
                    nativePaidMetadata.put(key,paidMetadata(expanded));
                    return ok;
                }
                return super.payManaCost(cost,part,actual,prompt,matrix,effect);
            }
        });
        BenchRandomAudit.install(91804);
        check(ComputerUtil.handlePlayingSpellAbility(player,sa,null),"native reference casts explicit X=2 " + name);
        check(sa.getXManaCostPaid()==2,"native reference does not choose max-X");
        check(sa.getPayingMana().size()==(name.equals("Mind Twist")?4:5),"native actual total includes X, colored pips, tax");
        game.getStack().resolveStack(); game.getAction().checkStateEffects(true);
        check(name.equals("Mind Twist") ? game.getPlayers().get(1-seat).getCardsIn(ZoneType.Hand).size()==1
                : player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count()==2,"native effect consumes semantic X=2");
        nativeResults.put(key,resolvedState(game,seat,sa));
        nativeDraws.put(key,((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().get("draws").getAsLong());
    }
    private static void reproduce(String name, int seat) {
        var game=game(seat); var player=game.getPlayers().get(seat); var sa=setup(game,seat,name,name.equals("Mind Twist")?4:5);
        sa.setXManaCostPaid(2);
        var before=BenchMenuStateAudit.capture(game); var rng=BenchRandomAudit.begin();
        var assessed=RulesCostFeasibility.assess(player,sa);
        System.out.println("REPRO "+name+" seat="+seat+" selectedX=2 status="+assessed.status()+" reason="+assessed.reason());
        BenchMenuStateAudit.assertUnchanged(before,game); BenchRandomAudit.assertUnchanged(rng,"explicit X tax feasibility");
        check(assessed.status()==RulesCostFeasibility.Status.PAYABLE,"genuine affordable X+tax must be represented");
        check(assessed.space().x()==2 && assessed.space().cost().countX()==1,"symbolic X and chosen value retained separately");
        sa.setXManaCostPaid(null);
        before=BenchMenuStateAudit.capture(game); rng=BenchRandomAudit.begin();
        check(RulesCostFeasibility.assess(player,sa).status()==RulesCostFeasibility.Status.UNSUPPORTED,"selected cost never defaults absent X to zero");
        for(int i=0;i<3;i++) {
            var range=RulesCostFeasibility.announcementRange(player,sa);
            check(range.min()==0 && range.max()==2,"exact payable range accounts for Thalia and colored pips");
            check(StateEncoder.encodePriorityAbility(sa,player.getView()).getAsJsonObject("x").get("maxAnnounce").getAsInt()==2,"strict encoded range matches exact feasibility");
        }
        BenchMenuStateAudit.assertUnchanged(before,game); BenchRandomAudit.assertUnchanged(rng,"X menu and encoding");
        sa.setXManaCostPaid(3);
        check(RulesCostFeasibility.assess(player,sa).status()==RulesCostFeasibility.Status.UNPAYABLE,"joint total rejects one-mana-short X plus tax");
        sa.setXManaCostPaid(2); sa.getMapParams().put("XColor","Red");
        check(RulesCostFeasibility.assess(player,sa).status()==RulesCostFeasibility.Status.UNSUPPORTED,"colored X remains unsupported");
        sa.getMapParams().remove("XColor"); sa.getMapParams().put("XAlternative","Y");
        check(RulesCostFeasibility.assess(player,sa).status()==RulesCostFeasibility.Status.UNSUPPORTED,"X alternative remains unsupported");
    }

    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire; Function<JsonObject,JsonObject> respond;
        byte[] pending=new byte[0]; int at=0,last=0;
        Host(ByteArrayOutputStream wire) { this.wire=wire; }
        void prepare() {
            if(at<pending.length) return;
            var asks=wire.toString(StandardCharsets.UTF_8).lines().filter(s->!s.isBlank()).map(s->JsonParser.parseString(s).getAsJsonObject())
                    .filter(o->"ask".equals(o.get("type").getAsString())).toList();
            var ask=asks.get(asks.size()-1); int id=ask.get("id").getAsInt();
            if(id<=last) throw new AssertionError("Repeated/unexpected read");
            var answer=respond.apply(ask.deepCopy()); answer.addProperty("type","answer"); answer.addProperty("id",id);
            pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);at=0;last=id;
        }
        @Override public int read() { prepare(); return pending[at++]&255; }
        @Override public int read(byte[] b,int offset,int length) { if(length==0)return 0;prepare();int n=Math.min(length,pending.length-at);System.arraycopy(pending,at,b,offset,n);at+=n;return n; }
    }
    private static JsonObject payment(JsonObject ask, String fault) {
        var cost=ask.getAsJsonObject("cost");
        int x=cost.get("mana").getAsString().contains("{X}")?2:0;
        check(cost.get("x").getAsInt()==x,"production request retains chosen X and symbolic cost");
        var expanded=new forge.game.mana.ManaCostBeingPaid(new ManaCost(cost.get("mana").getAsString().replace("}{"," ").replace("{","").replace("}","")));
        expanded.setXManaCostPaid(x,"1");
        check(expanded.toManaCost().toString().equals(cost.get("expandedMana").getAsString()),"request expanded cost equals literal symbolic-X expansion");
        var answer=new JsonObject();answer.addProperty("paymentVersion",RulesCostFeasibility.PAYMENT_VERSION);answer.addProperty("x",x);answer.addProperty("lifePaid",0);
        var order=new JsonArray(); var options=new ArrayList<JsonObject>();
        for(var raw:ask.getAsJsonArray("sourceOptions")) options.add(raw.getAsJsonObject());
        Collections.reverse(options); // Fixture host's explicit source order, not an AI policy.
        for(var option:options) order.add(option.get("id"));
        answer.add("sourceOrder",order); var spend=new JsonArray();var used=new HashSet<String>();
        var tokens=new ArrayList<JsonObject>();
        for(var option:options){var token=new JsonObject();token.addProperty("id",option.get("id").getAsString()+":0");token.add("color",option.getAsJsonArray("output").get(0));tokens.add(token);}
        for(var raw:ask.getAsJsonArray("pool"))tokens.add(raw.getAsJsonObject());
        for(int i=0;i<cost.getAsJsonArray("shards").size();i++) {
            var shard=forge.card.mana.ManaCostShard.valueOf(cost.getAsJsonArray("shards").get(i).getAsString());
            JsonObject found=null;
            for(var token:tokens) if(!used.contains(token.get("id").getAsString())
                    && shard.canBePaidWithManaOfColor(forge.card.mana.ManaAtom.fromName(token.get("color").getAsString()))) {found=token;break;}
            if(found==null)throw new AssertionError("No exact fixture source");
            String id=found.get("id").getAsString();used.add(id);
            var allocation=new JsonObject();allocation.addProperty("token",id);allocation.addProperty("shardIndex",i);spend.add(allocation);
        }
        answer.add("spend",spend);
        if("payment-x-missing".equals(fault))answer.remove("x");
        if("payment-x-mismatch".equals(fault))answer.addProperty("x",1);
        if("payment-partial".equals(fault))spend.remove(spend.size()-1);
        return answer;
    }
    private static void production(String name,int seat,String fault) throws Exception {
        production(name,seat,fault,false);
    }
    private static void production(String name,int seat,String fault,boolean extraMana) throws Exception {
        production(name,seat,fault,extraMana,false);
    }
    private static void production(String name,int seat,String fault,boolean extraMana,boolean floating) throws Exception {
        boolean hasX=!name.equals("Lightning Bolt");
        var wire=new ByteArrayOutputStream();var host=new Host(wire);var channel=new JsonRpcChannel(host,wire);var session=new BenchSession(channel);
        var lobby=new LobbyPlayerBridge("Host",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var controlled=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var stock=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(controlled,stock):List.of(stock,controlled),"X tax production").createGame();
        game.setAge(GameStage.Play);var player=game.getPlayers().get(seat);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,player);session.setLiveGame(game);
        // The production bridge intentionally clears this executor after the cast.
        // Observe it only inside its real payManaCost callback, never at selection.
        final RulesPaymentExecutor[] executedPayment={null};
        player.dangerouslySetController(new PlayerControllerBridge(game,player,lobby,session,BenchSession.Mode.BRIDGE,seat,lobby.getCounters()) {
            @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility actual, String prompt, ManaConversionMatrix matrix, boolean effect) {
                try {
                    Object active=ACTIVE_RULES_PAYMENT.get(this);
                    if(active instanceof RulesPaymentExecutor executor) executedPayment[0]=executor;
                } catch (IllegalAccessException failure) { throw new AssertionError("fixture cannot observe active payment receipt",failure); }
                return super.payManaCost(cost,part,actual,prompt,matrix,effect);
            }
        });
        var printed=setup(game,seat,name,(!hasX?2:name.equals("Mind Twist")?4:5)+(extraMana?1:0));int fid=printed.getHostCard().getId();
        if(floating)floatSetup(game,player);
        var originalPool=new ArrayList<forge.game.mana.Mana>();for(var mana:player.getManaPool())originalPool.add(mana);
        final JsonObject[] paymentRequest={null},paymentAnswer={null};
        host.respond=ask->{
            String kind=ask.get("kind").getAsString();var answer=new JsonObject();
            if(kind.equals("priority")) {
                int chosen=-1;var menu=ask.getAsJsonArray("menu");
                for(int i=1;i<menu.size();i++)if(menu.get(i).getAsJsonObject().get("fid").getAsInt()==fid)chosen=i;
                check(chosen>0,"real priority menu offers affordable X plus tax");
                if(hasX)check(menu.get(chosen).getAsJsonObject().getAsJsonObject("x").get("maxAnnounce").getAsInt()==(extraMana?3:2),"production menu publishes exact taxed maximum");
                var cost=menu.get(chosen).getAsJsonObject().getAsJsonObject("cost");
                check(cost.get("mana").getAsString().contains("{1}") && !cost.get("printedMana").getAsString().contains("{1}"),"priority cost includes exactly one literal tax and preserves printed cost");
                answer.addProperty("choice",chosen);if(hasX)answer.addProperty("x",2);
                if("extraneous".equals(fault))answer.addProperty("x",0);
                if("missing".equals(fault))answer.remove("x");
                if("fraction".equals(fault))answer.addProperty("x",1.5);
                if("string".equals(fault))answer.addProperty("x","2");
                if("negative".equals(fault))answer.addProperty("x",-1);
                if("too-high".equals(fault))answer.addProperty("x",3);
                return answer;
            }
            if(kind.equals("targets")) {
                if("target-missing".equals(fault))return answer;
                var choices=new JsonArray();var target=new JsonObject();target.addProperty("kind","player");target.addProperty("id",game.getPlayers().get(1-seat).getId());choices.add(target);answer.add("choices",choices);return answer;
            }
            if(kind.equals("payment")) {paymentRequest[0]=ask;paymentAnswer[0]=payment(ask,fault);return paymentAnswer[0].deepCopy();}
            throw new AssertionError("Unexpected controller ask "+kind);
        };
        BenchRandomAudit.install(91804);BenchActionAudit.beginGame(game,"x-tax");game.subscribeToEvents(new BenchMain.EventEmitter(channel,"x-tax",game));
        var before=BenchMenuStateAudit.capture(game);var rng=BenchRandomAudit.begin();
        var audit=new ByteArrayOutputStream();var stderr=System.err;SpellAbility selected=null;boolean rejected=false;
        boolean selectionFault=fault!=null&&Set.of("extraneous","missing","fraction","string","negative","too-high").contains(fault);
        try {
            System.setErr(new PrintStream(audit,true,StandardCharsets.UTF_8));
            try {
                var choices=player.getController().chooseSpellAbilityToPlay();selected=choices.get(0);
                if(selectionFault)throw new AssertionError("Accepted invalid action/X answer "+fault);
                check(!hasX || selected.getXManaCostPaid()==2,"host choice X=2 survives selection");
                check(player.getController().playChosenSpellAbility(selected),"actual production exact payment and stack insertion");
                if(hasX) {
                    check(executedPayment[0]!=null,"actual payment callback captured its active executor");
                    check(nativePaidMetadata.get(name+seat+floating).equals(paidMetadata(executedPayment[0].paidCostReceipt())),"native and exact payment X-by-color/colors/sunburst/unpaid metadata agree");
                }
            } catch(RulesCostFeasibility.Unsupported expected) {if(fault==null)throw expected;rejected=true;}
            BenchActionAudit.finishGame(game);
        } finally {System.setErr(stderr);}
        BenchRandomAudit.assertUnchanged(rng,"production X select/payment before spell resolution");
        if(fault!=null) {
            check(rejected && game.getStack().isEmpty() && printed.getHostCard().isInZone(ZoneType.Hand),"bad X/target/payment fails closed before cast: "+fault);
            // Selection is pure; failed native announcement rollback may change
            // internal IDs/permissions, so do not claim full reversibility.
            if(selectionFault)BenchMenuStateAudit.assertUnchanged(before,game);
            check(!audit.toString(StandardCharsets.UTF_8).contains("engine-stack-add"),"rejected action has no execution receipt");
        } else {
            String log=audit.toString(StandardCharsets.UTF_8);
            check(log.contains("engine-stack-add") && log.contains("\"semanticsMatch\":true") && !log.contains("BENCH_INTEGRITY_"),"private actual receipt agrees with announced X and targets");
            check(selected.getPayingMana().size()==paymentAnswer[0].getAsJsonArray("spend").size(),"actual paid count matches full cost");
            var order=paymentAnswer[0].getAsJsonArray("sourceOrder");var options=paymentRequest[0].getAsJsonArray("sourceOptions");
            for(int i=0;i<order.size();i++) {
                String id=order.get(i).getAsString();var option=options.asList().stream().map(JsonElement::getAsJsonObject).filter(o->o.get("id").getAsString().equals(id)).findFirst().orElseThrow();
                check(selected.getPayingManaAbilities().get(i).getHostCard().getId()==option.get("fid").getAsInt(),"actual source order equals host choice");
            }
            for(int i=0;i<selected.getPayingMana().size();i++) {
                String token=paymentAnswer[0].getAsJsonArray("spend").get(i).getAsJsonObject().get("token").getAsString();
                if(token.startsWith("pool")){check(selected.getPayingMana().get(i)==originalPool.get(Integer.parseInt(token.substring(4))),"exact chosen floating object consumed in host order");continue;}
                String id=token.substring(0,token.lastIndexOf(':'));
                var option=options.asList().stream().map(JsonElement::getAsJsonObject).filter(o->o.get("id").getAsString().equals(id)).findFirst().orElseThrow();
                var source=player.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.getId()==option.get("fid").getAsInt()).findFirst().orElseThrow();
                check(selected.getPayingMana().get(i)==source.getManaAbilities().get(0).getManaPart().getLastManaProduced().iterator().next(),"exact host token identity and consumption order preserved");
            }
            check(player.getManaPool().totalMana()==(extraMana?1:0),"complete payment has correct remaining pool");
            game.getStack().resolveStack();game.getAction().checkStateEffects(true);
            check(!hasX?game.getPlayers().get(1-seat).getLife()==17:name.equals("Mind Twist")?game.getPlayers().get(1-seat).getCardsIn(ZoneType.Hand).size()==1:player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count()==2,"production resolved genuine semantic X=2 or ordinary Bolt effect");
            if(hasX && !extraMana) {
                String key=name+seat+floating;
                System.out.println("NATIVE_RESULT floating="+floating+" "+nativeResults.get(key));System.out.println("BRIDGE_RESULT floating="+floating+" "+resolvedState(game,seat,selected));
                if(floating)check(nativeResults.get(key).equals(resolvedState(game,seat,selected)),"matched floating native and exact bridge agree on resolved zones/life/pool/X and RNG transcript");
                else {
                    long draws=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().get("draws").getAsLong();
                    check(nativeDraws.get(key)-draws==(name.equals("Mind Twist")?4:5),"EXPECTED SOURCE-POLICY DIVERGENCE: native percentTrue(100) reservation probes consume one RNG draw per land; same seed is not same chance tape");
                }
            }
            if(extraMana)check(selected.getXManaCostPaid()==2,"host X=2 not replaced by affordable maximum 3");
            var exported=new JsonObject();exported.addProperty("card",name);exported.addProperty("seat",seat);exported.add("request",paymentRequest[0]);exported.add("answer",paymentAnswer[0]);System.out.println("WIRE_X_FIXTURE "+exported);
        }
        System.out.println("PASS production "+name+" seat="+seat+" fault="+fault+" extraMana="+extraMana+" floating="+floating);
    }
    private static void boundaries() {
        check(RulesPaymentExecutor.sameManaCost(new ManaCost("1 B"),new ManaCost("1 B")),"fresh equal adjusted costs compare structurally");
        for(var pair:List.of(new String[]{"X B","1 B"},new String[]{"X X","X"},new String[]{"1 B","1 R"},new String[]{"1 W/U","W U"}))
            check(!RulesPaymentExecutor.sameManaCost(new ManaCost(pair[0]),new ManaCost(pair[1])),"different symbolic cost remains distinguishable "+Arrays.toString(pair));
        check(!RulesPaymentExecutor.sameManaCost(ManaCost.ZERO,ManaCost.NO_COST),"no cost is not zero mana");
        var game=game(0);var player=game.getPlayers().get(0);
        for(int i=0;i<5;i++)card("Plains",player,ZoneType.Battlefield);
        card("Thalia, Guardian of Thraben",game.getPlayers().get(1),ZoneType.Battlefield);
        var sa=card("Walking Ballista",player,ZoneType.Hand).getFirstSpellAbility();sa.setActivatingPlayer(player);game.getAction().checkStateEffects(true);
        var before=BenchMenuStateAudit.capture(game);var rng=BenchRandomAudit.begin();
        check(RulesCostFeasibility.announcementRange(player,sa).max()==2,"repeated-X Ballista divides five available mana by two; creature not taxed");
        BenchMenuStateAudit.assertUnchanged(before,game);BenchRandomAudit.assertUnchanged(rng,"repeated-X range");
        sa.setXManaCostPaid(2);var result=RulesCostFeasibility.assess(player,sa);
        check(result.status()==RulesCostFeasibility.Status.PAYABLE && result.space().shards().size()==4 && result.space().cost().countX()==2,"two printed X symbols expand to four while semantic X stays two");
        var thalia=game.getPlayers().get(1).getCardsIn(ZoneType.Battlefield).get(0);
        thalia.getStaticAbilities().get(0).getMapParams().put("ValidCard","Card.cmcLE3");
        check(RulesCostFeasibility.assess(player,sa).status()==RulesCostFeasibility.Status.UNSUPPORTED,"X-dependent literal-tax predicate cannot enter monotonic range proof");
        var wrongGame=game(0);var wrongPlayer=wrongGame.getPlayers().get(0);var spell=setup(wrongGame,0,"Mind Twist",4);spell.setXManaCostPaid(2);
        var domain=new RulesPaymentDomain(wrongPlayer,spell);var executor=new RulesPaymentExecutor(wrongPlayer,spell,domain.select(payment(domain.request(),null)));
        before=BenchMenuStateAudit.capture(wrongGame);rng=BenchRandomAudit.begin();boolean rejected=false;
        try{executor.pay(new ManaCost("X 2 B"),spell.getPayCosts().getCostMana(),spell,false);}catch(RulesCostFeasibility.Unsupported expected){rejected=true;}
        check(rejected,"changed actual symbolic tax fails before any source activation");
        BenchMenuStateAudit.assertUnchanged(before,wrongGame);BenchRandomAudit.assertUnchanged(rng,"wrong actual tax rejection");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                    (proxy,method,values)->switch(method.getName()) {
                        case "getAssetsDir" -> args[0]+"/forge-gui/";
                        case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                        case "getCurrentVersion" -> "x-tax-feasibility";
                        default -> throw new AssertionError("Unexpected GUI call "+method.getName());
                    }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            BenchRandomAudit.install(91804);
            for(boolean floating:List.of(false,true))for (int seat=0;seat<2;seat++) for(String name:List.of("Mind Twist","Forth Eorlingas!")) nativeSequence(name,seat,floating);
            for (int seat=0;seat<2;seat++) for(String name:List.of("Mind Twist","Forth Eorlingas!")) reproduce(name,seat);
            for (int seat=0;seat<2;seat++) for(String name:List.of("Mind Twist","Forth Eorlingas!")) production(name,seat,null);
            production("Mind Twist",0,null,true);
            for (int seat=0;seat<2;seat++)for(String name:List.of("Mind Twist","Forth Eorlingas!"))production(name,seat,null,false,true);
            for (int seat=0;seat<2;seat++)production("Lightning Bolt",seat,null);
            production("Lightning Bolt",0,"extraneous");
            for(String fault:List.of("missing","fraction","string","negative","too-high","target-missing","payment-x-missing","payment-x-mismatch","payment-partial"))production("Mind Twist",0,fault);
            boundaries();
            System.out.println("PASS "+checks+" explicit X/tax checks; no games or strength claims"); System.exit(0);
        } catch(Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
