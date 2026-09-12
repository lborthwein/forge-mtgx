package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.ApiType;
import forge.game.card.*;
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
import java.util.*;

/** Actual priority enumeration and stack legality, not policy/card-data equivalence or a game. */
public final class PriorityStackTargetDomainSmoke {
    private static int checks;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static Card card(String name,Player owner,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);var card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());owner.getZone(zone).add(card);card.setSickness(false);return card;
    }
    private static SpellAbility spell(Card card){return card.getSpellAbilities().stream().filter(SpellAbility::isSpell).findFirst().orElseThrow();}
    private static SpellAbilityStackInstance push(Game game,SpellAbility ability,Player actor){
        ability.setActivatingPlayer(actor);if(ability.isSpell())game.getAction().moveToStack(ability.getHostCard(),ability);
        game.getStack().add(ability);return game.getStack().peek();
    }
    private static int globalSequence(Class<?> type,String field) throws Exception {
        var value=type.getDeclaredField(field);value.setAccessible(true);return value.getInt(null);
    }
    private static void run(int seat,String name,boolean empty) throws Exception {
        var session=new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()));
        var lobby=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var enemy=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,enemy):List.of(enemy,own),"Priority stack domain fixture").createGame();
        game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);var other=game.getPlayers().get(1-seat);session.setLiveGame(game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        for(int n=0;n<4;n++)card("Island",actor,ZoneType.Battlefield);card("Mountain",actor,ZoneType.Battlefield);
        var source=card(name,actor,name.equals("Glen Elendra Archmage")?ZoneType.Battlefield:ZoneType.Hand);
        var option=name.equals("Glen Elendra Archmage")?source.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.Counter).findFirst().orElseThrow():spell(source);
        option.setActivatingPlayer(actor);
        var expected=new LinkedHashSet<Integer>();SpellAbilityStackInstance decay=null;
        if(!empty){
            var ballista=card("Walking Ballista",other,ZoneType.Battlefield);ballista.setCounters(CounterEnumType.P1P1,4);
            var lion=push(game,spell(card("Savannah Lions",actor,ZoneType.Hand)),actor);
            var bolt=spell(card("Lightning Bolt",other,ZoneType.Hand));bolt.getTargets().add(actor);var boltEntry=push(game,bolt,other);
            var abrupt=spell(card("Abrupt Decay",actor,ZoneType.Hand));abrupt.getTargets().add(ballista);decay=push(game,abrupt,actor);
            var ping=ballista.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.DealDamage).findFirst().orElseThrow();ping.getTargets().add(actor);
            var activation=push(game,ping,other);
            switch(name){
                case "Counterspell" -> expected.addAll(List.of(lion.getId(),boltEntry.getId(),decay.getId()));
                case "Negate","Spell Pierce","Glen Elendra Archmage" -> expected.addAll(List.of(boltEntry.getId(),decay.getId()));
                case "Essence Scatter","Stern Scolding" -> expected.add(lion.getId());
                case "Stifle" -> expected.add(activation.getId());
            }
        }
        game.getPhaseHandler().setPriority(actor);game.getAction().checkStaticAbilities();BenchRandomAudit.install(9761);
        // Initialize the normal production menu first; domain observation itself
        // must neither change actor/targets nor allocate any global identity.
        var build=PlayerControllerBridge.class.getDeclaredMethod("buildPriorityDecision");build.setAccessible(true);
        List<SpellAbility> menu;String menuUnsupported=null;
        try {
            var decision=build.invoke(actor.getController());var getter=decision.getClass().getDeclaredMethod("menu");getter.setAccessible(true);
            @SuppressWarnings("unchecked") List<SpellAbility> offered=(List<SpellAbility>)getter.invoke(decision);menu=offered;
        } catch(java.lang.reflect.InvocationTargetException failure) {
            if(!(failure.getCause() instanceof RulesCostFeasibility.Unsupported))throw failure;
            menu=List.of();menuUnsupported=failure.getCause().getMessage();
            if(name.equals("Glen Elendra Archmage"))
                check(menuUnsupported.contains("CostSacrifice"),"preserve existing unsupported Archmage payment domain, do not bypass");
            else if(name.equals("Cryptic Command")||name.equals("Mystic Confluence"))
                check(menuUnsupported.contains("unsupported modal choice domain"),"composed controlled-casting refuses entire unsupported modal decision before encoding");
            else throw failure;
        }
        var expectedApi=option.getApi();
        var actual=menu.stream().filter(sa->sa.getHostCard().getId()==source.getId()&&sa.getApi()==expectedApi).findFirst().orElse(null);
        if(!empty&&menuUnsupported==null)check(actual!=null,"actual production priority menu includes "+name+" seat="+seat);
        else if(empty)check(actual==null,"empty-stack Counterspell is not offered by production menu");
        if(actual!=null)option=actual;
        var before=BenchMenuStateAudit.capture(game);var rng=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().deepCopy();
        var actorBefore=option.getActivatingPlayer();var targetBefore=new ArrayList<>(option.getTargets());
        int abilities=globalSequence(SpellAbility.class,"maxId"),instances=globalSequence(SpellAbilityStackInstance.class,"maxId");
        var domain=PriorityStackTargetDomain.encode(option);
        if(name.equals("Counterspell")&&!empty) {
            option.setTargetingPlayer(other);
            var routed=PriorityStackTargetDomain.encode(option);
            check(routed.get("kind").getAsString().equals("unsupported")&&routed.get("reason").getAsString().equals("alternate-targeting-player"),"runtime alternate stack targeting player refused");
            option.setTargetingPlayer(null);
        }
        check(domain.equals(PriorityStackTargetDomain.encode(option)),"per-action domain deterministic");
        BenchMenuStateAudit.assertUnchanged(before,game);checks++;
        check(rng.equals(((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot()),"domain consumes no RNG");
        check(actorBefore==option.getActivatingPlayer()&&targetBefore.equals(option.getTargets()),"domain preserves actor and target selection");
        check(abilities==globalSequence(SpellAbility.class,"maxId")&&instances==globalSequence(SpellAbilityStackInstance.class,"maxId"),"domain allocates no global ability/stack IDs");
        String kind=domain.get("kind").getAsString();
        if(name.equals("Cryptic Command")||name.equals("Mystic Confluence"))check(kind.equals("unsupported"),"compound modal domain is explicitly unsupported, not approximate");
        else if(name.equals("Lightning Bolt"))check(kind.equals("none"),"ordinary targeted burn has no stack-target spec");
        else {
            check(kind.equals("exact"),"simple counter gets exact domain "+name);
            var found=new LinkedHashSet<Integer>();var rows=domain.getAsJsonArray("rows");
            check(rows.size()==game.getStack().size(),"eligibility mask covers every live entry, including disallowed");
            for(var raw:rows){var row=raw.getAsJsonObject();if(row.get("allowed").getAsBoolean())found.add(row.get("stackId").getAsInt());}
            check(found.equals(expected),"literal mixed-stack legal set equals expected for "+name);
            if(name.equals("Counterspell")&&!empty){
                check(!decay.getSpellAbility().isCounterableBy(option)&&found.contains(decay.getId()),"uncounterable spell is nevertheless a legal target");
                check(found.stream().anyMatch(id->{for(var entry:game.getStack())if(entry.getId()==id)return entry.getActivatingPlayer()==actor;return false;}),"own spell remains in legal domain; no AI ownership filter");
            }
        }
        var ask=new JsonObject();ask.addProperty("type","ask");ask.addProperty("kind","priority");ask.addProperty("id",checks);ask.addProperty("game","domain/"+seat+"/"+name+(empty?"/empty":""));ask.addProperty("seat",seat);
        ask.addProperty("rulesCostVersion",RulesCostFeasibility.VERSION);ask.addProperty("paymentVersion",RulesCostFeasibility.PAYMENT_VERSION);ask.addProperty("paymentControl","host-complete-witness");
        ask.addProperty("priorityStackTargetsVersion",PriorityStackTargetDomain.VERSION);ask.add("state",StateEncoder.encodeWithStackInstances(game,actor));
        var encodedMenu=new JsonArray();encodedMenu.add(StateEncoder.encodePriorityAbilityWithStackTargets(null,actor.getView()));encodedMenu.add(StateEncoder.encodePriorityAbilityWithStackTargets(option,actor.getView()));ask.add("menu",encodedMenu);
        var result=new JsonObject();result.addProperty("name",name);result.addProperty("seat",seat);result.addProperty("empty",empty);result.addProperty("actualPriorityOffered",actual!=null);if(menuUnsupported!=null)result.addProperty("menuUnsupported",menuUnsupported);result.add("ask",ask);
        var ids=new JsonArray();for(int id:expected)ids.add(id);result.add("expectedStackIds",ids);System.out.println("DOMAIN_CASE "+result);
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
            (proxy,method,values)->switch(method.getName()){
                case "getAssetsDir"->args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                case "getCurrentVersion"->"priority-stack-domain-fixture";
                default->throw new AssertionError("Unexpected GUI call "+method.getName());
            }));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){
            for(String name:List.of("Lightning Bolt","Counterspell","Spell Pierce","Stern Scolding","Glen Elendra Archmage","Stifle","Negate","Essence Scatter","Cryptic Command","Mystic Confluence"))run(seat,name,false);
            run(seat,"Counterspell",true);
        }
        System.out.println("PASS "+checks+" priority stack-domain checks");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
