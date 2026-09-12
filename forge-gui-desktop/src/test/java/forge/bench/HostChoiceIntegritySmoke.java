package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual host callbacks and native scry movement, including transport failures.
 * No policy-strength or complete-controller-coverage claim. */
public final class HostChoiceIntegritySmoke {
    private static int checks;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        String fault = "none"; boolean keep; int asks; JsonObject observed;
        int declines, mulligans; final List<Integer> tuckCounts = new ArrayList<>();
        byte[] pending = new byte[0]; int offset;
        private void prepare() {
            if (offset < pending.length) return;
            var ask = wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString)
                .map(JsonElement::getAsJsonObject).filter(o -> o.has("type") && o.get("type").getAsString().equals("ask"))
                .reduce((a,b) -> b).orElseThrow();
            observed = ask; asks++;
            var answer = new JsonObject(); answer.addProperty("type", "answer"); answer.add("id", ask.get("id"));
            if (ask.get("kind").getAsString().equals("mulligan")) {
                tuckCounts.add(ask.get("cardsToReturn").getAsInt());
                answer.addProperty("keep", declines > 0 ? mulligans++ >= declines : keep);
                switch (fault) {
                    case "string" -> answer.addProperty("keep", "false");
                    case "number" -> answer.addProperty("keep", 1);
                    case "fraction" -> { answer.remove("keep"); answer.addProperty("choice", 0.5); }
                    case "choice-string" -> { answer.remove("keep"); answer.addProperty("choice", "1"); }
                    case "overflow" -> { answer.remove("keep"); answer.addProperty("choice", 4294967296L); }
                    case "contradiction" -> answer.addProperty("choice", keep ? 0 : 1);
                    case "integer" -> { answer.remove("keep"); answer.addProperty("choice", keep ? 1 : 0); }
                    case "missing" -> answer.remove("keep");
                    default -> { }
                }
            } else if (ask.get("kind").getAsString().equals("cardsChoice")) {
                check(declines > 0,"only the service fixture tucks cards");
                int min=ask.get("min").getAsInt();
                check(min==ask.get("max").getAsInt() && min>0 && min<=2,"native London bottom count");
                var selected=new JsonArray();
                for(int i=0;i<min;i++)selected.add(ask.getAsJsonArray("menu").get(i).getAsJsonObject().get("fid"));
                switch(fault) {
                    case "string" -> selected.set(0,new JsonPrimitive(selected.get(0).getAsString()));
                    case "fraction" -> selected.set(0,new JsonPrimitive(selected.get(0).getAsInt()+0.5));
                    case "duplicate" -> selected.set(1,selected.get(0));
                    case "unknown" -> selected.set(0,new JsonPrimitive(99999999));
                    case "short" -> selected.remove(0);
                    default -> { }
                }
                answer.add("choices",selected);
                if(fault.equals("missing"))answer.remove("choices");
            } else {
                check(ask.get("kind").getAsString().equals("scry"), "only intended host decision");
                var menu = ask.getAsJsonArray("menu");
                check(menu.size() == 3, "real scry reveals three objects");
                var top = new JsonArray(); var bottom = new JsonArray();
                top.add(menu.get(2).getAsJsonObject().get("fid"));
                top.add(menu.get(0).getAsJsonObject().get("fid"));
                bottom.add(menu.get(1).getAsJsonObject().get("fid"));
                switch (fault) {
                    case "string" -> top.set(0, new JsonPrimitive(top.get(0).getAsString()));
                    case "fraction" -> top.set(0, new JsonPrimitive(top.get(0).getAsInt() + 0.5));
                    case "overflow" -> top.set(0, new JsonPrimitive(top.get(0).getAsLong() + 4294967296L));
                    case "duplicate" -> bottom.set(0, top.get(0));
                    case "unknown" -> top.set(0, new JsonPrimitive(99999999));
                    case "short" -> top.remove(0);
                    default -> { }
                }
                answer.add("top", top); answer.add("bottom", bottom);
                if (fault.equals("missing")) answer.remove("bottom");
            }
            if (fault.equals("delegate")) answer.addProperty("delegate", true);
            pending = (answer + "\n").getBytes(StandardCharsets.UTF_8); offset = 0;
        }
        @Override public int read() { if (fault.equals("eof")) return -1; prepare(); return pending[offset++] & 255; }
        @Override public int read(byte[] b,int at,int n) {
            if (n == 0) return 0; if (fault.equals("eof")) return -1; prepare();
            int count = Math.min(n, pending.length - offset); System.arraycopy(pending,offset,b,at,count); offset += count; return count;
        }
    }
    private record Context(Game game, Player actor, PlayerControllerBridge controller, BenchSession session, Host host) {}
    private static Context context(int seat) {
        var host = new Host(); var session = new BenchSession(new JsonRpcChannel(host,host.wire));
        var lobby = new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat); lobby.setAiProfile("Default");
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game = new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Host choice integrity").createGame();
        game.setAge(GameStage.Play); session.setLiveGame(game); var actor = game.getPlayers().get(seat);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        return new Context(game,actor,(PlayerControllerBridge)actor.getController(),session,host);
    }
    private static Card card(Context c,String name,ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),c.actor);
        card.setGameTimestamp(c.game.getNextTimestamp()); c.actor.getZone(zone).add(card); return card;
    }
    private static int bucket(Context c,String method,String owner) {
        return c.controller.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods")
            .getAsJsonObject(method).get(owner).getAsInt();
    }
    private static void failure(Context c,String method,Runnable action) {
        try { action.run(); throw new AssertionError("accepted invalid " + method + ": " + c.host.fault); }
        catch (RulesCostFeasibility.Unsupported expected) {
            check(c.session.integrityFailure(c.game)!=null,"failure permanently invalidates session");
            check(bucket(c,method,"unclassified")==1 && bucket(c,method,"host")==0 && bucket(c,method,"stock")==0,
                "failed callback acquires neither host nor stock ownership");
        }
        int prior = c.host.asks;
        c.host.fault="none";
        try { action.run(); throw new AssertionError("failed session resumed"); }
        catch (RulesCostFeasibility.Unsupported expected) { check(c.host.asks==prior,"latched failure cannot issue another host ask"); }
    }
    private static void mulligan(int seat,String fault,boolean keep) {
        var c=context(seat); c.host.fault=fault; c.host.keep=keep;
        card(c,"Plains",ZoneType.Hand); card(c,"Savannah Lions",ZoneType.Hand);
        var before=List.copyOf(c.actor.getCardsIn(ZoneType.Hand));
        if (fault.equals("none") || fault.equals("integer")) {
            check(c.controller.mulliganKeepHand(c.actor,1)==keep,"exact host keep/mulligan decision");
            check(c.controller.mulliganKeepHand(c.actor,2)==keep,"subsequent mulligan gets a fresh decision");
            check(c.host.asks==2 && c.host.observed.get("cardsToReturn").getAsInt()==2,"repeat mulligan carries current count");
            check(bucket(c,"mulliganKeepHand","host")==2 && bucket(c,"mulliganKeepHand","unclassified")==0,"each validated mulligan is host-owned");
        } else failure(c,"mulliganKeepHand",()->c.controller.mulliganKeepHand(c.actor,1));
        check(before.equals(List.copyOf(c.actor.getCardsIn(ZoneType.Hand))),"mulligan callback itself never moves hand cards");
    }
    private static void scry(int seat,String fault) {
        var c=context(seat); c.host.fault=fault;
        var a=card(c,"Plains",ZoneType.Library); var b=card(c,"Plains",ZoneType.Library);
        var d=card(c,"Island",ZoneType.Library); var tail=card(c,"Mountain",ZoneType.Library);
        var source=card(c,"Preordain",ZoneType.Hand);
        var ability=AbilityFactory.getAbility("DB$ Scry | Defined$ You | ScryNum$ 3",source); ability.setActivatingPlayer(c.actor);
        var before=List.copyOf(c.actor.getCardsIn(ZoneType.Library));
        Runnable action=()->c.game.getAction().scry(List.of(c.actor),3,ability);
        if (fault.equals("none")) {
            action.run();
            check(c.actor.getCardsIn(ZoneType.Library).stream().map(Card::getId).toList().equals(List.of(d.getId(),a.getId(),tail.getId(),b.getId())),
                "native scry movement preserves exact host top order and bottom instance");
            check(bucket(c,"arrangeForScry","host")==1 && bucket(c,"arrangeForScry","unclassified")==0,"scry classified only after exact partition");
        } else {
            failure(c,"arrangeForScry",action);
            check(before.equals(List.copyOf(c.actor.getCardsIn(ZoneType.Library))),"failed scry leaves exact library order intact");
        }
    }
    private static void londonService(int seat,int firstSeat) {
        var c=context(seat); c.host.declines=2;
        StaticData.instance().setMulliganRule(forge.MulliganDefs.MulliganRule.London);
        var first=c.game.getPlayers().get(firstSeat);
        var other=c.game.getPlayers().get(1-seat);
        other.dangerouslySetController(new forge.ai.PlayerControllerAi(c.game,other,other.getLobbyPlayer()) {
            @Override public boolean mulliganKeepHand(Player starting,int count) {
                check(starting==first,"native service passes the starting player, not controller owner");return true;
            }
        });
        for(int i=0;i<7;i++)card(c,"Plains",ZoneType.Hand);
        for(int i=0;i<40;i++)card(c,"Island",ZoneType.Library);
        new forge.game.mulligan.MulliganService(first).perform();
        check(c.host.tuckCounts.equals(List.of(0,1,2)),"real London service repeats decision after both mulligans");
        check(c.actor.getCardsIn(ZoneType.Hand).size()==5 && c.actor.getCardsIn(ZoneType.Library).size()==42,
            "two real reshuffles/draws/bottom selections conserve cards and leave five in hand");
        check(bucket(c,"mulliganKeepHand","host")==3 && bucket(c,"mulliganKeepHand","unclassified")==0,
            "starting and non-starting controllers retain three host decisions");
        check(bucket(c,"tuckCardsViaMulligan","host")==2 && bucket(c,"tuckCardsViaMulligan","unclassified")==0,
            "each real London bottom selection is host-owned");
        check(c.session.integrityFailure(c.game)==null,"native starting-player argument does not invalidate mulligan");
    }
    private static void tuck(int seat,String fault) {
        var c=context(seat);c.host.declines=1;c.host.fault=fault;
        var a=card(c,"Plains",ZoneType.Hand);var b=card(c,"Plains",ZoneType.Hand);card(c,"Island",ZoneType.Hand);
        var hand=new CardCollection(c.actor.getCardsIn(ZoneType.Hand));
        if(fault.equals("none")) {
            var selected=c.controller.tuckCardsViaMulligan(hand,2);
            check(selected.size()==2 && selected.get(0)==a && selected.get(1)==b,"host chooses exact equal-name London instances in order");
            check(bucket(c,"tuckCardsViaMulligan","host")==1,"valid bottom selection host-owned");
            check(c.controller.tuckCardsViaMulligan(hand,0).isEmpty(),"zero bottom count is forced empty");
            check(bucket(c,"tuckCardsViaMulligan","forced")==1 && c.host.asks==1,"zero bottom count does not ask or delegate");
        } else failure(c,"tuckCardsViaMulligan",()->c.controller.tuckCardsViaMulligan(hand,2));
        check(hand.equals(c.actor.getCardsIn(ZoneType.Hand)),"bottom callback itself never mutates the hand");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (p,m,v)->switch(m.getName()) {
                    case "getAssetsDir"->args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"host-choice-integrity";
                    default->throw new AssertionError("unexpected GUI: "+m.getName());
                }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            if(args.length>1 && args[1].equals("probe")) { mulligan(0,"string",false); System.exit(0); }
            for(int seat=0;seat<2;seat++) {
                for(int firstSeat=0;firstSeat<2;firstSeat++)londonService(seat,firstSeat);
                for(boolean keep:List.of(false,true)) for(String shape:List.of("none","integer")) mulligan(seat,shape,keep);
                for(String fault:List.of("string","number","fraction","choice-string","overflow","contradiction","missing","delegate","eof")) mulligan(seat,fault,false);
                for(String fault:List.of("none","string","fraction","overflow","duplicate","unknown","short","missing","delegate","eof")) scry(seat,fault);
                for(String fault:List.of("none","string","fraction","duplicate","unknown","short","missing","delegate","eof")) tuck(seat,fault);
            }
            System.out.println("PASS "+checks+" host scry/mulligan integrity checks; NOT CERTIFIED"); System.exit(0);
        } catch(Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
