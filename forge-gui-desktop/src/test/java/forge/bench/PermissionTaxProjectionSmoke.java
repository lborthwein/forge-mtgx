package forge.bench;

import forge.StaticData;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.*;
import forge.game.cost.*;
import forge.game.mana.ManaConversionMatrix;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Development reproduction: real Spellbinder permission effect and native
 * announced payment. Does not claim complete ETB choice/policy conformance. */
public final class PermissionTaxProjectionSmoke {
    private static int checks;
    private static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why);checks++; }
    private static Card card(String name,Player p,ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(c);c.setSickness(false);return c;
    }
    private static void run(int seat, boolean controlled, String variant, boolean baseline) {
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),players,"Permission tax projection").createGame();
        g.setAge(GameStage.Play);var actor=g.getPlayers().get(seat);var opponent=g.getPlayers().get(1-seat);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var binder=card("Elite Spellbinder",opponent,ZoneType.Battlefield);
        boolean x=variant.equals("x");
        boolean other=variant.equals("other-permission") || variant.equals("unremembered");
        boolean hand=variant.equals("hand");
        boolean fault=variant.equals("mutation") || variant.endsWith("-drift");
        int expected=other || hand ? 1 : x || variant.equals("stacked") ? 4 : 3;
        var spell=card(x?"Stonecoil Serpent":"Sol Ring",actor,hand?ZoneType.Hand:ZoneType.Exile);
        for(int i=0;i<expected;i++)card("Plains",actor,ZoneType.Battlefield);
        // Start at the real script's post-choice effect boundary, not a mock
        // static ability or a claim that the full ETB was driven by a policy.
        binder.addImprintedCard(spell);
        var effect=AbilityFactory.getAbility(binder.getSVar("DBEffect"),binder);
        effect.setActivatingPlayer(opponent);AbilityUtils.resolve(effect);
        g.getAction().checkStaticAbilities();
        var tax=g.getCardsIn(ZoneType.Command).stream().flatMap(c->c.getStaticAbilities().stream())
            .filter(s->s.checkMode(forge.game.staticability.StaticAbilityMode.RaiseCost)).findFirst().orElseThrow();
        if(variant.equals("two-binders")) {
            var second=card("Elite Spellbinder",opponent,ZoneType.Battlefield);second.addImprintedCard(spell);
            var extra=AbilityFactory.getAbility(second.getSVar("DBEffect"),second);
            extra.setActivatingPlayer(opponent);AbilityUtils.resolve(extra);
        }
        if(other) {
            var dragon=card("Decadent Dragon",actor,ZoneType.Battlefield);
            dragon.setState(forge.card.CardStateName.Secondary,false);dragon.addRemembered(spell);
            var extra=AbilityFactory.getAbility(dragon.getSVar("DBEffect"),dragon);
            extra.setActivatingPlayer(actor);AbilityUtils.resolve(extra);
            if(variant.equals("unremembered"))tax.getHostCard().removeRemembered(spell);
        }
        if(variant.equals("stale")) {
            spell.setCastSA(spell.getFirstSpellAbility());
            spell.setCastFrom(actor.getZone(ZoneType.Graveyard));
        }
        g.getAction().checkStaticAbilities();
        if(variant.equals("stacked")) {
            var secondTax=tax.getHostCard().addStaticAbility(
                "Mode$ RaiseCost | Type$ Spell | Amount$ 1 | AffectedZone$ Exile | ValidCard$ Card.IsRemembered+CastSa Spell.MayPlaySource");
            // EffectEffect installs this non-text active zone on generated
            // command effects; a bare static defaults to battlefield only.
            secondTax.setActiveZone(EnumSet.copyOf(tax.getActiveZone()));
            check(secondTax.checkConditions(),"second fixed tax is actually active");
        }
        BenchRandomAudit.install(71955);
        var candidates=spell.getAllPossibleAbilities(actor,true).stream()
            .filter(a->a.isSpell() && (hand ? a.getMayPlay()==null : a.getMayPlay()!=null
                && (other ? a.getMayPlay().getHostCard()!=tax.getHostCard() : a.getMayPlay().getHostCard()==tax.getHostCard()))).toList();
        check(candidates.size()==1,"one selected permission/ordinary cast");
        var sa=candidates.get(0);
        if(x)sa.setXManaCostPaid(2);
        check(sa.canPlay(),"owner can cast selected card");
        var oldCast=spell.getCastSA();var oldOrigin=spell.getCastFrom();
        var before=BenchMenuStateAudit.capture(g);var rng=BenchRandomAudit.begin();
        var forecast=CostAdjustment.benchmarkManaCost(sa);
        if(baseline) {
            check(forecast!=null,"permission tax must have a verified forecast");return;
        }
        check(forecast!=null,"permission tax has a verified forecast");
        check(CostAdjustment.benchmarkExpandedMana(sa,forecast).getConvertedManaCost()==expected,
            "exact expanded forecast "+variant+": "+forecast+" expected total "+expected);
        // Demonstrate why adding AffectedZone to a whitelist is insufficient:
        // native predicates inspect CastSA, installed only during announcement.
        var naive=CostAdjustment.adjust(sa.getPayCosts(),sa,false).getTotalMana();
        check(naive.getGenericCost()==(x?0:1),"unannounced direct adjustment misses permission-linked tax");
        var projected=sa.copyForEnumeration(actor);
        var host=CardCopyService.getLKICopy(spell);
        projected.setHostCard(host);host.setCastSA(projected);host.setCastFrom(spell.getLastKnownZone());
        var price=CostAdjustment.adjust(projected.getPayCosts(),projected,false).getTotalMana();
        check(CostAdjustment.benchmarkExpandedMana(projected,price).getConvertedManaCost()==expected,"detached projection matches forecast");
        check(spell.getCastSA()==oldCast && spell.getCastFrom()==oldOrigin,"live card cast metadata unchanged");
        var assessed=RulesCostFeasibility.assess(actor,sa);
        check(assessed.status()==RulesCostFeasibility.Status.PAYABLE,"exact tax is affordable");
        if(x)check(RulesCostFeasibility.announcementRange(actor,sa).max()==2,"X range includes fixed permission tax");
        BenchMenuStateAudit.assertUnchanged(before,g);BenchRandomAudit.assertUnchanged(rng,"permission-tax projection");checks+=2;
        // Unsupported shapes still refuse rather than masquerade as this schema.
        tax.getMapParams().put("ValidTarget","Player");
        check(CostAdjustment.benchmarkManaCost(sa)==null,"target-dependent tax remains unsupported");
        tax.getMapParams().remove("ValidTarget");
        var payment=controlled?new RulesPaymentExecutor(actor,sa,assessed.witness()):null;
        final int[] callbacks={0};
        actor.dangerouslySetController(new PlayerControllerAi(g,actor,actor.getLobbyPlayer()) {
            @Override public boolean playTrigger(Card source,forge.game.trigger.WrappedAbility wrapper,boolean mandatory) {
                return payment==null?super.playTrigger(source,wrapper,mandatory)
                    :payment.duringMandatoryTrigger(source,wrapper,mandatory,()->super.playTrigger(source,wrapper,mandatory));
            }
            @Override public boolean payManaCost(ManaCost cost,CostPartMana part,SpellAbility actual,String prompt,ManaConversionMatrix matrix,boolean isEffect) {
                if(actual.getHostCard().getId()==spell.getId()) {
                    check(CostAdjustment.benchmarkExpandedMana(actual,cost).getConvertedManaCost()==expected,"actual native announced cost agrees");callbacks[0]++;
                    if(variant.equals("mutation"))tax.getMapParams().put("Amount","3");
                    if(variant.equals("permission-drift"))actual.setMayPlay(null);
                    if(variant.equals("origin-drift"))actual.getHostCard().setCastFrom(actor.getZone(ZoneType.Hand));
                    if(variant.equals("remembered-drift"))tax.getHostCard().clearRemembered();
                    if(variant.equals("castsa-drift"))actual.getHostCard().setCastSA(null);
                }
                return payment==null?super.payManaCost(cost,part,actual,prompt,matrix,isEffect):payment.pay(cost,part,actual,isEffect);
            }
        });
        if(fault) {
            try {
                ComputerUtil.handlePlayingSpellAbility(actor,sa,null,payment::decisions);
                throw new AssertionError("changed tax accepted");
            }catch(RulesCostFeasibility.Unsupported failure) {
                check(failure.getMessage().contains("cost differs from witness"),"changed actual tax invalidates payment");
            }
            check(actor.getCardsIn(ZoneType.Battlefield).stream().noneMatch(Card::isTapped),"changed tax fails before spending sources");
            return;
        }
        check(payment==null?ComputerUtil.handlePlayingSpellAbility(actor,sa,null)
            :ComputerUtil.handlePlayingSpellAbility(actor,sa,null,payment::decisions),"actual cast succeeds");
        if(payment!=null)payment.assertPaid();
        check(callbacks[0]==1 && sa.getPayingMana().size()==expected,"actual mana receipt agrees with projection");
        check(sa.getHostCard().isInZone(ZoneType.Stack),"native cast reaches stack");
        System.out.println("PERMISSION_TAX seat="+seat+" controlled="+controlled+" variant="+variant+" paid="+sa.getPayingMana().size());
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (p,m,v)->switch(m.getName()) {
                    case "getAssetsDir"->args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"permission-tax-projection-fixture";
                    default->throw new AssertionError("unexpected GUI: "+m.getName());
                }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            if(args.length>1 && args[1].equals("baseline"))run(0,false,"taxed",true);
            else for(int seat=0;seat<2;seat++) {
                for(boolean controlled:List.of(false,true))for(String variant:List.of("taxed","other-permission","unremembered","hand","stale","x","stacked","two-binders"))
                    run(seat,controlled,variant,false);
                for(String fault:List.of("mutation","permission-drift","origin-drift","remembered-drift","castsa-drift"))
                    run(seat,true,fault,false);
            }
            System.out.println("PASS "+checks+" permission-tax projection and actual payment checks; NOT CERTIFIED");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
