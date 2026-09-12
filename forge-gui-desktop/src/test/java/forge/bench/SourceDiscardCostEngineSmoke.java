package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;
import java.util.List;
import java.util.Random;

/** Actual pinned Channel/cycling costs, explicit fixture choices, no AI payment. */
public final class SourceDiscardCostEngineSmoke {
    static int checks;
    static void check(boolean value, String text) { if(!value)throw new AssertionError(text); checks++; }
    static Game game(int seat) {
        var registered=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),registered,"Source discard").createGame();
        g.setAge(GameStage.Play);g.getPhaseHandler().devModeSet(PhaseType.MAIN1,g.getPlayers().get(seat));return g;
    }
    static Card card(String name,Player player,ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper=FModel.getMagicDb().getCommonCards().getCard(name);check(paper!=null,"Pinned card "+name);
        var c=Card.fromPaperCard(paper,player);c.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(c);c.setSickness(false);return c;
    }
    static SpellAbility ability(Card source,Player payer) {
        var a=source.getSpellAbilities().stream().filter(s->s.getPayCosts()!=null
                &&s.getPayCosts().getCostParts().stream().anyMatch(RulesCostFeasibility::isSingleSelfDiscard)).findFirst().orElseThrow();
        a.setActivatingPlayer(payer);return a;
    }
    static void run(String name,int seat) {
        var g=game(seat);var p=g.getPlayers().get(seat);
        var land=card("Plains",p,ZoneType.Battlefield);
        if(name.equals("Touch the Spirit Realm") || name.equals("Miscalculation"))card("Plains",p,ZoneType.Battlefield);
        var source=card(name,p,ZoneType.Hand);var untouched=card("Lightning Bolt",p,ZoneType.Hand);
        var a=ability(source,p);
        var target=a.usesTargeting()?card("Flickerwisp",p,ZoneType.Battlefield):null;
        g.getAction().checkStateEffects(true);
        var identity=PriorityActivationIdentity.encode(a);
        check("intrinsic-hand-discard".equals(identity.get("kind").getAsString()),"native hand identity "+name+" "+identity);
        var wire=new com.google.gson.JsonObject();wire.addProperty("name",name);wire.addProperty("seat",seat);
        wire.add("state",StateEncoder.encodeWithStackInstances(g,p));
        wire.add("option",StateEncoder.encodePriorityAbilityWithTargetDomains(a,p.getView()));
        System.out.println("HAND_ACTIVATION_CASE "+wire);
        if(target!=null)a.getTargets().add(target);
        var rng=MyRandom.getRandom();
        MyRandom.setRandom(new Random(1){@Override protected int next(int n){throw new AssertionError("Unexpected RNG during rules quote/payment");}});
        try {
            var before=RulesCostFeasibilityEngineSmoke.state(g);
            for(int i=0;i<3;i++)check(RulesCostFeasibility.assess(p,a).status()==RulesCostFeasibility.Status.PAYABLE,"source discard payable "+name);
            check(before.equals(RulesCostFeasibilityEngineSmoke.state(g)),"quote is pure");
            var quote=RulesCostFeasibility.assess(p,a);
            var executor=new RulesPaymentExecutor(p,a,quote.witness());
            p.dangerouslySetController(new forge.ai.PlayerControllerAi(g,p,p.getLobbyPlayer()) {
                @Override public boolean payManaCost(forge.card.mana.ManaCost cost,CostPartMana part,SpellAbility actual,
                        String prompt,forge.game.mana.ManaConversionMatrix matrix,boolean effect) {
                    if (!cost.isZero()) check(p.getCardsIn(ZoneType.Hand).contains(source),"real mana precedes source discard: "+name);
                    return executor.pay(cost,part,actual,effect);
                }
            });
            check(forge.ai.ComputerUtil.handlePlayingSpellAbility(p,a,null,executor::decisions),"ability enters stack");
            executor.assertPaid();checks++;
            check(!p.getCardsIn(ZoneType.Hand).contains(source),"source consumed");
            check(p.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->c.getId()==source.getId()),"native discard destination");
            check(p.getCardsIn(ZoneType.Hand).contains(untouched),"unselected hand card preserved");
            check(p.getLife()==(name.equals("Street Wraith")?18:20),"exact fixed life cost");
            check(p.getManaPool().totalMana()==0,"exact mana payment");
            check(name.equals("Street Wraith")?!land.isTapped():land.isTapped(),"correct mana source used");
            System.out.println("PASS executed source discard "+name+" seat="+seat);
        } finally { MyRandom.setRandom(rng); }
    }
    static void rejectionChecks(int seat) {
        var g=game(seat);var p=g.getPlayers().get(seat);var source=card("Generous Ent",p,ZoneType.Hand);var a=ability(source,p);
        check(RulesCostFeasibility.assess(p,a).status()==RulesCostFeasibility.Status.UNPAYABLE,"no mana");
        card("Plains",p,ZoneType.Battlefield);
        var executor=new RulesPaymentExecutor(p,a,RulesCostFeasibility.assess(p,a).witness());
        source.setGameTimestamp(g.getNextTimestamp());
        try{executor.decisions(a);throw new AssertionError("Stale hand visit accepted");}
        catch(RulesCostFeasibility.Unsupported expected){checks++;}
        a.setPayCosts(new Cost("1 Discard<1/Card>",true));
        check(RulesCostFeasibility.assess(p,a).status()==RulesCostFeasibility.Status.UNSUPPORTED,"generic discard from hand remains unsupported");
        a.setPayCosts(new Cost("1 Discard<2/CARDNAME>",true));
        check(RulesCostFeasibility.assess(p,a).status()==RulesCostFeasibility.Status.UNSUPPORTED,"two source discards refused");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (proxy,method,values)->switch(method.getName()) {
                    case "getAssetsDir" -> args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                    case "getCurrentVersion" -> "source-discard-fixture";
                    default -> throw new AssertionError("Unexpected GUI call "+method.getName());
                }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                for(String name:List.of("Generous Ent","Troll of Khazad-dûm","Touch the Spirit Realm","Street Wraith","Miscalculation"))run(name,seat);
                rejectionChecks(seat);
            }
            System.out.println("PASS source-discard "+checks+" checks");System.exit(0);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
}
