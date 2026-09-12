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
import java.util.*;
import java.io.*;

/** Real engine stack insertion/targeting/resolution; no payment or policy equivalence claim. */
public final class StackIdentityEngineSmoke {
    private static int checks;
    private static boolean external;
    private static final BufferedReader replies=new BufferedReader(new InputStreamReader(System.in));
    private static void check(boolean condition,String label) { if(!condition)throw new AssertionError(label); checks++; System.out.println("PASS "+label); }
    private static Card card(String name, Player owner, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        Card card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());owner.getZone(zone).add(card);card.setSickness(false);return card;
    }
    private static void run(int seat) {
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var game=new Match(new GameRules(GameType.Constructed),players,"Stack identity fixture").createGame();
        game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);var other=game.getPlayers().get(1-seat);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var ballista=card("Walking Ballista",actor,ZoneType.Battlefield);
        ballista.setCounters(CounterEnumType.P1P1,4);
        var ping=ballista.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.DealDamage).findFirst().orElseThrow();
        ping.setActivatingPlayer(actor);ping.getTargets().add(actor);game.getStack().add(ping);
        var first=game.getStack().peek();
        ping.setActivatingPlayer(actor);ping.getTargets().add(other);game.getStack().add(ping);
        var second=game.getStack().peek();
        check(game.getStack().size()==2&&first.getId()!=second.getId(),"actual distinct stack instances");
        check(first.getSourceCard()==ballista&&second.getSourceCard()==ballista,"both entries use same actual source card");
        if(seat==1) {
            game.getAction().moveToGraveyard(ballista,null);
            check(!ballista.isInZone(ZoneType.Battlefield),"source leaves battlefield while both abilities remain live");
        }
        var stifle=card("Stifle",other,ZoneType.Hand);
        var counter=stifle.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.Counter).findFirst().orElseThrow();
        SpellAbilityStackInstance chosen=first;
        if(external) {
            var ask=new JsonObject();ask.addProperty("type","stack-fixture-ask");ask.addProperty("seat",seat);
            ask.add("state",StateEncoder.encodeWithStackInstances(game,actor));
            System.out.println("STACK_ASK "+ask);System.out.flush();
            try {
                var reply=JsonParser.parseString(replies.readLine()).getAsJsonObject();
                int targetId=reply.get("stackId").getAsInt();chosen=null;
                for(var candidate:game.getStack())if(candidate.getId()==targetId)chosen=candidate;
                check(chosen!=null&&chosen==first,"real TS reply selected first instance, not shared source ID");
            }catch(IOException failure){throw new RuntimeException(failure);}
        }
        counter.setActivatingPlayer(other);counter.getTargets().add(chosen.getSpellAbility());
        check(counter.canTarget(first.getSpellAbility()),"real Stifle can target first ability");
        game.getAction().moveToStack(stifle,counter);game.getStack().add(counter);
        check(game.getStack().size()==3,"counter is actual third entry");
        JsonObject encoded=StateEncoder.encode(game,actor);
        System.out.println("STATE "+encoded);
        var top=encoded.getAsJsonArray("stack").get(0).getAsJsonObject();
        int encodedTarget=top.getAsJsonArray("targetSpells").get(0).getAsJsonObject().get("stackId").getAsInt();
        check(encodedTarget==first.getSpellAbility().getId(),"legacy targetSpells stackId is SpellAbility id");
        check(encodedTarget!=first.getId(),"actual witness distinguishes SpellAbility id from stackinstance id");
        var explicit=StateEncoder.encodeWithStackInstances(game,actor);
        var explicitTarget=explicit.getAsJsonArray("stack").get(0).getAsJsonObject().getAsJsonArray("targetSpells").get(0).getAsJsonObject();
        check(explicitTarget.get("stackId").getAsInt()==first.getId(),"new wire references exact live instance");
        check(encoded.equals(StateEncoder.encode(game,actor)),"opt-in encode does not mutate legacy state");
        System.out.println("VERSIONED_STATE "+explicit);
        counter.getTargets().clear();counter.getTargets().add(ping);
        var nonlive=StateEncoder.encodeWithStackInstances(game,actor).getAsJsonArray("stack").get(0).getAsJsonObject().getAsJsonArray("targetSpells").get(0).getAsJsonObject();
        check(nonlive.get("targetState").getAsString().equals("not-on-stack")&&!nonlive.has("stackId")
            &&nonlive.get("spellAbilityId").getAsInt()==ping.getId(),"unresolved reference keeps typed nonlive SA identity, never invented stackId");
        counter.getTargets().clear();counter.getTargets().add(first.getSpellAbility());
        game.getStack().resolveStack();
        check(game.getStack().size()==1&&game.getStack().peek().getId()==second.getId(),"Stifle counters first of duplicate-source entries only");
        game.getStack().resolveStack();
        check(actor.getLife()==20&&other.getLife()==19,"remaining second ability resolves for its own target");
        var result=new JsonObject();result.addProperty("type","stack-fixture-result");result.addProperty("seat",seat);
        result.addProperty("firstId",first.getId());result.addProperty("secondId",second.getId());result.addProperty("sourceId",ballista.getId());
        result.addProperty("passed",true);result.addProperty("scope","ACTUAL_STACK_IDENTITY_ROUNDTRIP_NOT_PAYMENT_POLICY_OR_STRENGTH");
        System.out.println("STACK_RESULT "+result);
    }
    private static void expiredTargets(int seat) {
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var game=new Match(new GameRules(GameType.Constructed),players,"Expired stack target fixture").createGame();
        game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);var other=game.getPlayers().get(1-seat);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var ballista=card("Walking Ballista",actor,ZoneType.Battlefield);ballista.setCounters(CounterEnumType.P1P1,4);
        var ping=ballista.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.DealDamage).findFirst().orElseThrow();
        ping.setActivatingPlayer(actor);ping.getTargets().add(other);game.getStack().add(ping);
        var target=game.getStack().peek();SpellAbility older=null;
        for(int n=0;n<2;n++) {
            var source=card("Stifle",other,ZoneType.Hand);
            var counter=source.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.Counter).findFirst().orElseThrow();
            counter.setActivatingPlayer(other);counter.getTargets().add(target.getSpellAbility());
            check(counter.canTarget(target.getSpellAbility()),"both real Stifles initially target same live activation");
            game.getAction().moveToStack(source,counter);game.getStack().add(counter);if(n==0)older=counter;
        }
        check(game.getStack().size()==3,"two real counters plus one activation");
        game.getStack().resolveStack();
        check(game.getStack().size()==1&&game.getStack().peekAbility()==older,"top Stifle resolved and removed activation, older Stifle remains");
        check(older.getTargets().getFirstTargetedSpell()==target.getSpellAbility(),"older counter retains exact engine target SpellAbility object");
        check(game.getStack().getInstanceMatchingSpellAbilityID(target.getSpellAbility())==null,"retained target no longer has a live stack instance");
        var before=StateEncoder.encode(game,actor);BenchRandomAudit.install(9741);
        var rngBefore=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().deepCopy();
        var encoded=StateEncoder.encodeWithStackInstances(game,actor);
        check(encoded.equals(StateEncoder.encodeWithStackInstances(game,actor)),"expired-target observation is deterministic without cache history");
        check(before.equals(StateEncoder.encode(game,actor)),"expired-target observation does not mutate engine state");
        check(rngBefore.equals(((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot()),"expired-target observation consumes no RNG");
        System.out.println("EXPIRED_STATE "+encoded);
        game.getStack().resolveStack();
        check(game.getStack().size()==0,"older Stifle completes its rules fizzle");
        check(actor.getLife()==20&&other.getLife()==20,"countered Ballista damage never happens");
        check(other.getZone(ZoneType.Graveyard).size()==2,"both Stifles reach graveyard after resolution/fizzle");
        var result=new JsonObject();result.addProperty("seat",seat);result.addProperty("passed",true);
        result.addProperty("formerStackId",target.getId());result.addProperty("retainedSpellAbilityId",target.getSpellAbility().getId());
        System.out.println("EXPIRED_RESULT "+result);
    }
    public static void main(String[] args) {
        try {
            external=args.length>1&&args[1].equals("--stdio");
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (proxy,method,values)->switch(method.getName()) {
                    case "getAssetsDir"->args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"stack-identity-fixture";
                    default->throw new AssertionError("Unexpected GUI call "+method.getName());
                }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            if(args.length>1&&args[1].equals("--expired-only")){expiredTargets(0);expiredTargets(1);}
            else {run(0);run(1);expiredTargets(0);expiredTargets(1);}
            System.out.println("PASS "+checks+" stack identity checks");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
