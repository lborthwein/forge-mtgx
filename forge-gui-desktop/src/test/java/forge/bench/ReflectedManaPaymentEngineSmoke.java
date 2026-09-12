package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.*;
import forge.game.mana.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.*;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Real engine trigger/payment execution with exact host-specified bonus-token
 * spending. This verifies the rules/executor, not TypeScript policy parity. */
public final class ReflectedManaPaymentEngineSmoke {
    private static int checks;
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),player);
        c.setGameTimestamp(player.getGame().getNextTimestamp()); player.getZone(zone).add(c); c.setSickness(false); return c;
    }
    private static void run(int seat, String sourceName, boolean spendBonus, int snow, String fault) {
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),players,"Reflected payment fixture").createGame();
        var p=g.getPlayers().get(seat); var opponent=g.getPlayers().get(1-seat);
        g.setAge(GameStage.Play);g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p);
        var mono=card(sourceName,p,ZoneType.Battlefield);
        var kinnan=card("Kinnan, Bonder Prodigy",fault.equals("opponent")?opponent:p,ZoneType.Battlefield);
        var bonusCards=new ArrayList<Card>();bonusCards.add(kinnan);
        if(fault.equals("copy")) {
            var duplication=card("Irenicus's Vile Duplication",p,ZoneType.Hand).getFirstSpellAbility();
            duplication.setActivatingPlayer(p);duplication.getTargets().add(kinnan);
            AbilityUtils.resolve(duplication);
            var copy=p.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.isToken()&&c.getName().equals(kinnan.getName())).findFirst().orElseThrow();
            check(!copy.getType().isLegendary()&&copy.getId()!=kinnan.getId(),"native Duplication produces legal nonlegendary Kinnan copy");
            bonusCards.add(copy);
        }
        if (snow!=0) {
            card("Rimefeather Owl",p,ZoneType.Battlefield);
            if ((snow&1)!=0) mono.setCounters(CounterEnumType.ICE,1);
            if ((snow&2)!=0) kinnan.setCounters(CounterEnumType.ICE,1);
        }
        boolean bonusExpected=!fault.equals("opponent")&&!mono.isLand();
        int primary=mono.isLand()||sourceName.equals("Llanowar Elves")?1:3;
        int bonusCount=bonusExpected?bonusCards.size():0;
        var spell=card(bonusExpected?(primary==1?"Llanowar Elves":"Chromatic Lantern"):"Sol Ring",p,ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(p);
        g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        var before=BenchMenuStateAudit.capture(g);var rng=BenchRandomAudit.begin();
        var domain=new RulesPaymentDomain(p,spell);var request=domain.request();
        var option=request.getAsJsonArray("sourceOptions").get(0).getAsJsonObject();
        check(option.get("fid").getAsInt()==mono.getId(),"exact tapped source");
        int output=option.getAsJsonArray("output").size();
        check(output==primary+bonusCount,"exact forecast including independently produced bonus");
        check(request.get("representation").getAsString().equals(bonusExpected?"token-shard-domain-v2-producers":"token-shard-domain-v1"),"version distinguishes composite producer output");
        if (bonusExpected) {
            var origins=option.getAsJsonArray("outputOrigins");
            check(origins.size()==output,"every unit has provenance");
            for(int i=0;i<output;i++) {
                var o=origins.get(i).getAsJsonObject();
                check(o.get("sourceFid").getAsInt()==(i<primary?mono.getId():bonusCards.get(i-primary).getId()),"own physical producer");
                check(o.get("snow").getAsBoolean()==(i<primary?(snow&1)!=0:(snow&2)!=0),"per-producer snow snapshot");
            }
        }
        var answer=new JsonObject();answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);
        answer.addProperty("x",0);answer.addProperty("lifePaid",0);
        var order=new JsonArray();order.add(option.get("id").getAsString());answer.add("sourceOrder",order);
        var spend=new JsonArray();int count=bonusExpected?primary:1;
        for(int i=0;i<count;i++) {
            int unit=bonusExpected&&spendBonus&&i==count-1?primary:i;
            var a=new JsonObject();a.addProperty("token",option.get("id").getAsString()+":"+unit);a.addProperty("shardIndex",i);spend.add(a);
        }
        answer.add("spend",spend);var witness=domain.select(answer);
        BenchMenuStateAudit.assertUnchanged(before,g);BenchRandomAudit.assertUnchanged(rng,"reflected quote");
        check(witness.sources().get(0).bonuses().size()==bonusCount,"no opponent/land bonus invented");
        final RulesPaymentExecutor[] executor={null};final int[] callbacks={0};
        p.dangerouslySetController(new PlayerControllerAi(g,p,p.getLobbyPlayer()) {
            @Override public boolean payManaCost(ManaCost cost,CostPartMana part,SpellAbility sa,String prompt,ManaConversionMatrix matrix,boolean effect) {
                check(matrix==null,"no conversion");return executor[0].pay(cost,part,sa,effect);
            }
            @Override public boolean playTrigger(Card host,WrappedAbility wrapper,boolean mandatory) {
                callbacks[0]++;
                return executor[0].duringMandatoryTrigger(host,wrapper,mandatory,()->{
                    var effect=wrapper.getWrappedAbility();
                    if(fault.equals("skip"))return false;
                    if(fault.equals("producer"))effect.setManaPart(new AbilityManaPart(mono,effect.getManaPart()));
                    if(fault.equals("traits")) {
                        var changed=new HashMap<>(effect.getMapParams());changed.put("PersistentMana","True");
                        effect.setManaPart(new AbilityManaPart(effect,changed));
                    }
                    wrapper.resolve();
                    if(fault.equals("duplicate"))wrapper.resolve();
                    if(fault.equals("extra"))p.getManaPool().addMana(new Mana((byte)forge.card.mana.ManaAtom.GREEN,kinnan,effect.getManaPart(),p));
                    if(fault.equals("opponent-extra"))opponent.getManaPool().addMana(new Mana((byte)forge.card.mana.ManaAtom.GREEN,kinnan,effect.getManaPart(),opponent));
                    return true;
                });
            }
            @Override public void playSpellAbilityNoStack(SpellAbility sa,boolean mayChoose) {
                check(!mayChoose && sa.getApi()==forge.game.ability.ApiType.ManaReflected,"native mandatory no-stack reflection");
                AbilityUtils.resolve(sa);
            }
        });
        executor[0]=new RulesPaymentExecutor(p,spell,witness);
        if(fault.equals("removed")) {
            g.getAction().moveToGraveyard(kinnan,null);g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        }
        boolean rejected=false;
        try {
            check(new CostPayment(spell.getPayCosts(),spell).payComputerCosts(executor[0].decisions(spell)),"native whole-cost execution");
            executor[0].assertPaid();
        } catch(RulesCostFeasibility.Unsupported unsupported) {rejected=true;System.out.println("EXPECTED_REJECTION "+fault+" "+unsupported.getMessage());}
        boolean invalid=!Set.of("none","opponent","copy").contains(fault);
        check(rejected==invalid,"fault invalidates, ordinary native production completes: "+fault);
        if(invalid)check(spell.getPayingMana().isEmpty(),"failure never acquires spend receipt");
        else {
            check(callbacks[0]==bonusCount,"exact native trigger count");
            check(spell.getPayingMana().size()==count,"full cost paid");
            check(p.getManaPool().totalMana()==output-count,"exact surplus");
            if(bonusExpected) {
                check(spell.getPayingMana().stream().filter(m->m.getSourceCard().getId()==kinnan.getId()).count()==(spendBonus?1:0),"exact bonus token spent or retained");
                var surplus=new HashMap<Integer,Integer>();
                for(var m:p.getManaPool())surplus.merge(m.getSourceCard().getId(),1,Integer::sum);
                check(surplus.getOrDefault(mono.getId(),0)==(spendBonus?1:0),"primary surplus retains real producer");
                for(int i=0;i<bonusCount;i++)check(surplus.getOrDefault(bonusCards.get(i).getId(),0)==(spendBonus&&i==0?0:1),"each reflected surplus retains real producer");
                check(!witness.sources().get(0).equivalentUnits(0,primary),"same color does not merge primary and bonus provenance");
            }
        }
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (p,m,v)->switch(m.getName()) {
                    case "getAssetsDir"->args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"reflected-payment-fixture";
                    default->throw new AssertionError("unexpected GUI "+m.getName());
                }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            BenchRandomAudit.install(91802);
            for(int seat=0;seat<2;seat++) {
                for(String source:List.of("Basalt Monolith","Grim Monolith"))for(boolean spend:List.of(false,true))for(int snow=0;snow<4;snow++)run(seat,source,spend,snow,"none");
                run(seat,"Island",false,0,"none");run(seat,"Basalt Monolith",false,0,"opponent");
                for(boolean spend:List.of(false,true)) {
                    run(seat,"Llanowar Elves",spend,0,"none");
                    run(seat,"Basalt Monolith",spend,0,"copy");
                }
                for(String fault:List.of("skip","producer","traits","duplicate","extra","opponent-extra","removed"))run(seat,"Basalt Monolith",true,0,fault);
            }
            System.out.println("PASS "+checks+" reflected production payment checks; TypeScript policy NOT CERTIFIED");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
