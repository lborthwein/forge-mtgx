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

/** Actual cost-free trigger rules execution, bounded evidence; outer ownership remains incomplete. */
public final class MandatoryTriggerExecutionSmoke {
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
            check(ask.get("kind").getAsString().equals(response.equals("delegate-order") ? "triggerOrder" : "targets"), "real trigger asks expected transport");
            asks++; var answer = new JsonObject(); answer.addProperty("type", "answer"); answer.addProperty("id", id);
            if (response.equals("delegate") || response.equals("delegate-order")) answer.addProperty("delegate", true);
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
    private static String paymentMetadata(forge.game.spellability.SpellAbility ability,Player actor) {
        var result=new StringBuilder();
        for(var sa:ability instanceof forge.game.trigger.WrappedAbility wrapper
                ?List.of(ability,wrapper.getWrappedAbility()):List.of(ability)) {
            result.append(sa.getActivatingPlayer()==actor).append('|').append(sa.getPayCosts().toSimpleString())
                .append('|').append(sa.getXManaCostPaid()).append('|').append(sa.getMaxWaterbend())
                .append('|').append(sa.getPayingMana().size()).append('|').append(sa.getAmountLifePaid())
                .append('|').append(sa.getSpendPhyrexianMana()).append('|').append(sa.getPaidHash())
                .append('|').append(sa.getTargets().getTargetCards().stream().map(Card::getName).toList()).append(';');
        }
        return result.toString();
    }
    private record Outcome(int aspirantCounters,int bearsCounters,int life,int mana,boolean stackEmpty,String paymentMetadata,Object rng) {}
    private static Outcome run(int seat,String mode,int choose) {
        var c=context(seat,mode); var aspirant=card("Luminarch Aspirant",c); var bears=card("Grizzly Bears",c); ready(c);
        if(mode.equals("NULL_PROBE")) ((PlayerControllerBridge)c.controller).probePriorityMenuPurity();
        c.host.target=choose==0?aspirant.getId():bears.getId(); fire(c);
        var stacked=c.game.getStack().peekAbility();
        c.game.getStack().resolveStack();
        int a=aspirant.getCounters(CounterEnumType.P1P1), b=bears.getCounters(CounterEnumType.P1P1);
        if(mode.equals("BRIDGE")) {
            check(a==(choose==0?1:0)&&b==(choose==1?1:0),"host-selected creature alone receives actual counter seat="+seat+" choice="+choose);
            check(c.host.asks==1,"one actual host target choice");
            check(bucket(c,"orderAndPlaySimultaneousSa","rules")==1 && bucket(c,"orderAndPlaySimultaneousSa","unclassified")==0,"rules insertion has independently accounted target/order children");
            check(bucket(c,"playSpellAbilityNoStack","rules")==1,"native counter resolution has independently accounted children");
            check(bucket(c,"orderSimultaneousSa","forced")==1,"singleton ordering is identity, not AI policy");
            check(bucket(c,"payManaCost","rules")==2&&bucket(c,"payManaCost","unclassified")==0,"both actual zero-payment callbacks are exact rules execution");
            var instruments=((PlayerControllerBridge)c.controller).getCounters().toJson().getAsJsonObject("instruments");
            check(instruments.get("hostTrigger.rulesStackInsertion").getAsInt()==1&&instruments.get("hostTrigger.rulesNoStackExecution").getAsInt()==1,"actual stack and no-stack rules paths both reached");
        } else check(c.host.asks==0,"native/null/probe never request a host answer");
        var rng=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().deepCopy();
        check(rng.get("purityChecks").getAsInt()==(mode.equals("NULL_PROBE")?1:0),"expected diagnostic-only purity probe count");
        rng.remove("purityChecks"); // diagnostic count differs; provider/state/draws/digest must remain identical
        var outcome=new Outcome(a,b,c.actor.getLife(),c.actor.getManaPool().totalMana(),c.game.getStack().isEmpty(),paymentMetadata(stacked,c.actor),rng);
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
    private static void unsupportedOtherOptionalDecider(int seat) {
        var c=context(seat,"BRIDGE"); var soul=card("Soulherder",c); var bears=card("Grizzly Bears",c);
        // Ordinary actor-owned Soulherder is now covered positively by
        // OptionalTriggerExecutionSmoke; another decider remains unsupported.
        soul.getTriggers().stream().filter(t->t.hasParam("OptionalDecider"))
                .forEach(t->t.getMapParams().put("OptionalDecider","Opponent"));
        c.game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN,c.actor); ready(c);
        c.game.getTriggerHandler().runTrigger(TriggerType.Phase,AbilityKey.mapFromPlayer(c.actor),false);
        check(c.game.getStack().hasSimultaneousStackEntries(),"actual optional Soulherder trigger queued");
        try { c.game.getStack().addAllTriggeredAbilitiesToStack(); throw new AssertionError("Unbridged optional confirmation silently accepted"); }
        catch(RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("decider"),"other-decider optional confirmation remains unsupported, not auto-yes"); }
        check(c.host.asks==0&&soul.isInZone(ZoneType.Battlefield)&&bears.isInZone(ZoneType.Battlefield),"unsupported optional trigger neither fabricates a host choice nor blinks a card");
    }
    private static void hypotheticalGame(int seat) {
        var c=context(seat,"BRIDGE"); var a=card("Luminarch Aspirant",c); var b=card("Grizzly Bears",c); ready(c);
        c.session.setLiveGame(context(seat,"native").game);
        fire(c);c.game.getStack().resolveStack();
        check(c.host.asks==0&&a.getCounters(CounterEnumType.P1P1)==0&&b.getCounters(CounterEnumType.P1P1)==1,"non-live game retains Default targeting without host traffic");
        check(((PlayerControllerBridge)c.controller).getCounters().toJson().get("totalCalls").getAsInt()==0,"non-live game does not pollute live ownership ledger");
    }
    private static void paidTrigger(int seat, String cost) {
        var c=context(seat,"BRIDGE"); var a=card("Luminarch Aspirant",c); var b=card("Grizzly Bears",c);
        var paid=forge.game.ability.AbilityFactory.getAbility("AB$ PutCounter | Cost$ "+cost+" | ValidTgts$ Creature.YouCtrl | CounterType$ P1P1 | CounterNum$ 1",a);
        paid.getPayCosts().setMandatory(true); a.getTriggers().get(0).setOverridingAbility(paid); ready(c);
        c.game.getTriggerHandler().runTrigger(TriggerType.Phase,AbilityKey.mapFromPlayer(c.actor),false);
        check(c.game.getStack().hasSimultaneousStackEntries(),"actual modified paid trigger queued cost="+cost);
        try { c.game.getStack().addAllTriggeredAbilitiesToStack(); throw new AssertionError("Paid trigger accepted as zero"); }
        catch(RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("not literal zero"),"paid trigger explicitly rejected cost="+cost); }
        check(c.host.asks==0&&c.actor.getLife()==20&&a.getCounters(CounterEnumType.P1P1)+b.getCounters(CounterEnumType.P1P1)==0,"paid rejection precedes host choice/payment/effect");
        check(bucket(c,"payManaCost","rules")==0&&bucket(c,"orderAndPlaySimultaneousSa","rules")==0,"paid rejection does not certify execution");
    }
    private static void multiple(int seat) {
        var c=context(seat,"BRIDGE"); var a=card("Luminarch Aspirant",c); var b=card("Luminarch Aspirant",c); ready(c);
        c.host.response="delegate-order";
        c.game.getTriggerHandler().runTrigger(TriggerType.Phase,AbilityKey.mapFromPlayer(c.actor),false);
        try { c.game.getStack().addAllTriggeredAbilitiesToStack(); throw new AssertionError("Multiple trigger ordering delegated"); }
        catch(RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("trigger ordering"),"declined host ordering explicitly rejected seat="+seat); }
        check(c.host.asks==1&&a.getCounters(CounterEnumType.P1P1)+b.getCounters(CounterEnumType.P1P1)==0,"ordering failure executes no guessed trigger order");
        check(bucket(c,"orderSimultaneousSa","forced")==0&&bucket(c,"orderSimultaneousSa","unclassified")==1,"ordering failure is not labelled forced");
    }
    private record StaticOutcome(int mana,int life,boolean tapped,boolean attached,boolean costStackEmpty,Object rng) {}
    private static StaticOutcome staticMana(int seat,String mode) {
        var c=context(seat,mode);var land=card("Forest",c);var aura=card("Wild Growth",c);
        aura.attachToEntity(land,null);ready(c);
        var mana=land.getManaAbilities().get(0);mana.setActivatingPlayer(c.actor);
        // Scripted fixture input: execute the Forest tap through the native engine.
        // This assertion covers the resulting actual Wild Growth trigger, NOT
        // ownership of the fixture's preselected land activation.
        check(forge.ai.ComputerUtil.playNoStack(c.actor,mana,c.game,false),"actual Forest activation succeeds");
        check(c.actor.getManaPool().totalMana()==2&&land.isTapped(),"real static Wild Growth produces its additional green mana");
        check(c.host.asks==0,"fixed-color mandatory mana trigger invents no host choice");
        if(mode.equals("BRIDGE")) {
            var instruments=((PlayerControllerBridge)c.controller).getCounters().toJson().getAsJsonObject("instruments");
            check(instruments.get("hostTrigger.rulesStaticExecution").getAsInt()==1&&instruments.get("hostTrigger.rulesNoStackExecution").getAsInt()==1,"static wrapper and underlying ability use rules execution");
            check(bucket(c,"payManaCost","rules")==2,"static trigger exact zero callbacks both classified rules");
            check(bucket(c,"playTrigger","rules")==1&&bucket(c,"playSpellAbilityNoStack","rules")==1,"static mana execution and every nested callback have ownership receipts");
        }
        var rng=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().deepCopy();rng.remove("purityChecks");
        return new StaticOutcome(c.actor.getManaPool().totalMana(),c.actor.getLife(),land.isTapped(),aura.getEnchantingCard()==land,c.game.costPaymentStack.peek()==null,rng);
    }
    private static void callbackFailure(int seat) throws Exception {
        var c=context(seat,"BRIDGE");var a=card("Luminarch Aspirant",c);var b=card("Grizzly Bears",c);ready(c);
        var ability=forge.game.ability.AbilityFactory.getAbility("AB$ PutCounter | Cost$ 0 | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1",a);
        ability.setActivatingPlayer(c.actor);ability.setTrigger(a.getTriggers().get(0));
        ability.getPayCosts().getCostParts().clear();
        ability.getPayCosts().getCostParts().add(new forge.game.cost.CostPartMana(forge.card.mana.ManaCost.ZERO,null) {
            @Override public forge.card.mana.ManaCost getManaCostFor(forge.game.spellability.SpellAbility ignored) {
                return forge.card.mana.ManaCost.get(1); // adversarial repricing at actual callback
            }
        });
        var rng=BenchRandomAudit.begin();
        try { c.controller.playSpellAbilityNoStack(ability,false);throw new AssertionError("Repriced zero callback accepted"); }
        catch(RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("changed, unrelated or repeated"),"actual callback repricing fails closed seat="+seat); }
        check(c.game.costPaymentStack.peek()==null,"Forge cost stack unwinds after rejected callback");
        var field=PlayerControllerBridge.class.getDeclaredField("activeZeroTriggerPayment");field.setAccessible(true);
        check(field.get(c.controller)==null,"zero-payment scope is cleared after exception");
        check(bucket(c,"payManaCost","rules")==0&&bucket(c,"payManaCost","unclassified")==1,"failed callback retains unknown ownership");
        check(a.getCounters(CounterEnumType.P1P1)+b.getCounters(CounterEnumType.P1P1)==0&&c.actor.getLife()==20,"rejected callback never resolves the effect");
        BenchRandomAudit.assertUnchanged(rng,"rejected zero callback");
        var replacement=forge.game.ability.AbilityFactory.getAbility("DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1",a);
        replacement.setActivatingPlayer(c.actor);
        try { c.controller.playSpellAbilityNoStack(replacement,true);throw new AssertionError("Latched failure permitted later execution"); }
        catch(RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("healthy open host channel"),"latched callback failure blocks later execution"); }
        check(a.getCounters(CounterEnumType.P1P1)==0,"latched failure never resolves subsequent effect");
        // Check invalid permission independently, before a prior failure closes
        // the host channel. Otherwise the liveness guard correctly fires first.
        c=context(seat,"BRIDGE"); a=card("Luminarch Aspirant",c); ready(c);
        replacement=forge.game.ability.AbilityFactory.getAbility("DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1",a);
        replacement.setActivatingPlayer(c.actor);
        try { c.controller.playSpellAbilityNoStack(replacement,true);throw new AssertionError("Non-trigger effect borrowed mandatory permission"); }
        catch(RulesCostFeasibility.Unsupported expected) { check(expected.getMessage().contains("not an ordinary owned trigger"),"non-trigger/replacement path explicitly unsupported"); }
    }
    /** A real engine effect reaches a still-legacy controller callback. Successful
     * resolution must not conceal that missing policy correspondence. */
    private static void unknownNestedEffect(int seat) {
        var c=context(seat,"BRIDGE"); var source=card("Luminarch Aspirant",c);
        StaticData.instance().attemptToLoadCard("Forest");
        var hidden=Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Forest"),c.actor);
        hidden.setGameTimestamp(c.game.getNextTimestamp()); c.actor.getZone(ZoneType.Hand).add(hidden); ready(c);
        var ability=forge.game.ability.AbilityFactory.getAbility("AB$ Reveal | Cost$ 0 | Defined$ You | NumCards$ 1 | RememberRevealed$ True",source);
        ability.setActivatingPlayer(c.actor); ability.setTrigger(source.getTriggers().get(0));
        c.controller.playSpellAbilityNoStack(ability,false);
        check(source.isRemembered(hidden),"actual native reveal effect completes with selected hand card");
        check(bucket(c,"chooseCardsToRevealFromHand","unclassified")==1,"legacy reveal selection retains its missing ownership receipt");
        check(bucket(c,"playSpellAbilityNoStack","unclassified")==1&&bucket(c,"playSpellAbilityNoStack","rules")==0,"successful native effect cannot certify parent over unknown child");
        check(bucket(c,"payManaCost","rules")==1,"known zero-payment child retains independent rules ownership");
        check(c.host.asks==0&&c.game.costPaymentStack.peek()==null,"unknown-child fixture invents no host answer and balances payment stack");
    }
    private static String emptyCost(int seat,String mode) {
        var c=context(seat,mode);var a=card("Luminarch Aspirant",c);ready(c);
        var ability=forge.game.ability.AbilityFactory.getAbility("AB$ PutCounter | Cost$ 0 | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1",a);
        ability.setActivatingPlayer(c.actor);ability.setTrigger(a.getTriggers().get(0));
        ability.getPayCosts().getCostParts().clear();
        check(ability.getPayCosts().getCostParts().isEmpty(),"fixture actual original cost list empty");
        var original=ability.getPayCosts();var rng=BenchRandomAudit.begin();
        c.controller.playSpellAbilityNoStack(ability,false);
        check(a.getCounters(CounterEnumType.P1P1)==1,"empty-cost trigger actually resolves");
        check(ability.getPayCosts()==original&&original.getCostParts().isEmpty(),"engine zero normalization never replaces/modifies original empty cost");
        check(c.game.costPaymentStack.peek()==null,"empty-cost lifecycle leaves payment stack balanced");
        if(mode.equals("BRIDGE"))check(bucket(c,"payManaCost","rules")==1,"empty list normalized by Forge to one real zero callback");
        BenchRandomAudit.assertUnchanged(rng,"empty-cost trigger");
        return paymentMetadata(ability,c.actor)+"/"+c.actor.getLife()+"/"+c.actor.getManaPool().totalMana();
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
        check(bucket(c,"orderAndPlaySimultaneousSa","rules")==1&&bucket(c,"playSpellAbilityNoStack","rules")==1,"actual external target execution has accounted nested controller calls");
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
                run(seat,"BRIDGE",0);
                check(nativeResult.equals(run(seat,"BRIDGE",1)),"same chosen target matches Default outcome and RNG seat="+seat);
                for(String response:List.of("target","missing","delegate","eof"))invalid(seat,response);
                unsupportedOtherOptionalDecider(seat);hypotheticalGame(seat);
                paidTrigger(seat,"1");paidTrigger(seat,"0 PayLife<1>");multiple(seat);
                var nativeStatic=staticMana(seat,"native");
                check(nativeStatic.equals(staticMana(seat,"NULL")),"static native and null state/RNG equal");
                check(nativeStatic.equals(staticMana(seat,"NULL_PROBE")),"static native and null-probe state/RNG equal");
                check(nativeStatic.equals(staticMana(seat,"BRIDGE")),"static native and bridge state/RNG equal");
                callbackFailure(seat);
                unknownNestedEffect(seat);
                var nativeEmpty=emptyCost(seat,"native");
                check(nativeEmpty.equals(emptyCost(seat,"BRIDGE")),"empty-cost native/bridge metadata and state match");
            }
            System.out.println("PASS "+checks+" host trigger preparation checks; incomplete execution ownership stays UNCLASSIFIED");System.exit(0);
        } catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
