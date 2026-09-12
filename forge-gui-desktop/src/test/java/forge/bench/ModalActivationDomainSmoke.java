package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
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
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Production priority masks followed by real mode/target/payment/resolve, both seats. */
public final class ModalActivationDomainSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static Card card(String name,Player p){
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(ZoneType.Battlefield).add(c);c.setSickness(false);return c;
    }
    private static final class Host extends InputStream {
        final ByteArrayOutputStream wire=new ByteArrayOutputStream();final List<JsonObject> asks=new ArrayList<>();
        int source,target,land,mode,answered,offset;byte[] pending=new byte[0];
        private void prepare(){if(offset<pending.length)return;
            var ask=wire.toString(StandardCharsets.UTF_8).lines().map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                    .filter(o->o.has("type")&&o.get("type").getAsString().equals("ask")).reduce((a,b)->b).orElseThrow();
            int id=ask.get("id").getAsInt();check(id>answered,"new request");asks.add(ask.deepCopy());
            var answer=new JsonObject();answer.addProperty("type","answer");answer.addProperty("id",id);
            switch(ask.get("kind").getAsString()) {
                case "priority" -> {
                    int choice=-1;var menu=ask.getAsJsonArray("menu");
                    for(int i=1;i<menu.size();i++)if(menu.get(i).getAsJsonObject().get("fid").getAsInt()==source)choice=i;
                    check(choice>0,"Cratermaker actually offered");answer.addProperty("choice",choice);
                }
                case "mode" -> {
                    String api=mode==0?"DealDamage":"Destroy";int choice=-1;var menu=ask.getAsJsonArray("menu");
                    for(int i=0;i<menu.size();i++)if(menu.get(i).getAsJsonObject().get("api").getAsString().equals(api))choice=i;
                    check(choice>=0,"selected mode legal");var ids=new JsonArray();ids.add(choice);answer.add("choices",ids);
                }
                case "targets" -> {var ids=new JsonArray();var row=new JsonObject();row.addProperty("kind","card");row.addProperty("id",target);ids.add(row);answer.add("choices",ids);}
                case "payment" -> {
                    answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);answer.addProperty("x",0);answer.addProperty("lifePaid",0);
                    JsonObject sourceOption=null;for(var raw:ask.getAsJsonArray("sourceOptions")) {
                        var option=raw.getAsJsonObject();if(option.get("fid").getAsInt()==land){sourceOption=option;break;}
                    }
                    check(sourceOption!=null,"Mountain payment offered");String sourceId=sourceOption.get("id").getAsString();
                    var order=new JsonArray();order.add(sourceId);answer.add("sourceOrder",order);
                    var spend=new JsonArray();var token=new JsonObject();token.addProperty("token",sourceId+":0");token.addProperty("shardIndex",0);spend.add(token);answer.add("spend",spend);
                }
                default -> throw new AssertionError("unexpected modal activation callback "+ask);
            }
            answered=id;pending=(answer+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
        }
        @Override public int read(){prepare();return pending[offset++]&255;}
        @Override public int read(byte[] b,int off,int len){if(len==0)return 0;prepare();int n=Math.min(len,pending.length-offset);System.arraycopy(pending,offset,b,off,n);offset+=n;return n;}
    }
    private static int sequence() throws Exception {var f=SpellAbility.class.getDeclaredField("maxId");f.setAccessible(true);return f.getInt(null);}
    private static void run(int seat,int mode,boolean colorless) throws Exception {
        var host=new Host();var session=new BenchSession(new JsonRpcChannel(host,host.wire));
        var lobby=new LobbyPlayerBridge("Actor",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var enemy=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,enemy):List.of(enemy,own),"Modal activation domain").createGame();
        var actor=game.getPlayers().get(seat);var other=game.getPlayers().get(1-seat);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);session.setLiveGame(game);
        var source=card("Goblin Cratermaker",actor);var land=card("Mountain",actor);var colored=card("Flickerwisp",other);
        Card ballista=null,vault=null;if(colorless){ballista=card("Walking Ballista",other);ballista.setCounters(CounterEnumType.P1P1,2);vault=card("Mana Vault",other);}
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(91710+seat);
        host.source=source.getId();host.land=land.getId();host.mode=mode;host.target=mode==1?vault.getId():colorless?ballista.getId():colored.getId();
        var ability=source.getSpellAbilities().stream().filter(a->a.getApi()==forge.game.ability.ApiType.Charm).findFirst().orElseThrow();ability.setActivatingPlayer(actor);
        var before=BenchMenuStateAudit.capture(game);var rng=BenchRandomAudit.begin();int sequence=sequence();
        var board=PriorityBoardTargetDomain.encode(ability);var stack=PriorityStackTargetDomain.encode(ability);
        check(board.equals(PriorityBoardTargetDomain.encode(ability)),"repeated modal domain identical");
        BenchMenuStateAudit.assertUnchanged(before,game);BenchRandomAudit.assertUnchanged(rng,"modal domain");check(sequence==sequence(),"no global ability allocations");
        check(board.get("kind").getAsString().equals("modes")&&board.getAsJsonArray("branches").size()==2,"both printed modes represented");
        for(int m=0;m<2;m++) {
            var domain=board.getAsJsonArray("branches").get(m).getAsJsonObject().getAsJsonObject("domain");
            check(domain.get("kind").getAsString().equals("exact"),"exact per-mode board mask");
            for(var raw:domain.getAsJsonArray("rows")) {var row=raw.getAsJsonObject();int id=row.get("id").getAsInt();
                boolean expected=row.get("kind").getAsString().equals("card") && (m==0?(id==source.getId()||id==colored.getId()||(colorless&&id==ballista.getId())):(colorless&&(id==ballista.getId()||id==vault.getId())));
                check(row.get("allowed").getAsBoolean()==expected,"literal per-mode membership including forbidden players");
            }
            check(stack.getAsJsonArray("branches").get(m).getAsJsonObject().getAsJsonObject("domain").get("kind").getAsString().equals("none"),"noncounter mode no stack domain");
        }
        BenchActionAudit.beginGame(game,"modal-activation");game.subscribeToEvents(new BenchMain.EventEmitter(session.getChannel(),"modal-activation",game));
        var selected=actor.getController().chooseSpellAbilityToPlay();check(selected!=null&&selected.size()==1,"host selected one activation");
        check(actor.getController().playChosenSpellAbility(selected.get(0)),"mode target and sacrifice payment executed");
        check(game.getStack().size()==1&&land.isTapped()&&actor.getManaPool().totalMana()==0,"exact payment with ability on stack");
        check(actor.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->c.getId()==source.getId()),"source sacrificed before resolution");
        check(other.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getId()==host.target),"target not moved before resolution");
        game.getStack().resolveStack();game.getAction().checkStateEffects(true);
        check(other.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->c.getId()==host.target),"selected mode actually removes its exact target");
        check(host.asks.stream().map(a->a.get("kind").getAsString()).toList().equals(List.of("priority","mode","targets","payment")),"native announcement sequence");
        check(session.integrityFailure(game)==null,"no swallowed failure");BenchActionAudit.finishGame(game,session);
        var observation=new JsonObject();observation.addProperty("seat",seat);observation.addProperty("mode",mode);observation.addProperty("colorless",colorless);observation.addProperty("sourceFid",source.getId());
        observation.add("ask",host.asks.get(0));observation.addProperty("executionPassed",true);
        System.out.println("MODAL_ACTIVATION_CASE "+observation);
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"modal-activation";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){run(seat,0,true);run(seat,1,true);run(seat,0,false);}
        System.out.println("PASS "+checks+" modal activation checks; NOT CERTIFIED");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
