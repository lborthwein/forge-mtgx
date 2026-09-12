package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deliberate ledger-boundary fault injection; NOT a legal cast or played game. */
public final class SpellFaceReceiptMutationSmoke {
    private static int checks;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static void run(int seat, String fault, boolean legacy) {
        var players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var game = new Match(new GameRules(GameType.Constructed),players,"Face receipt fault injection").createGame();
        game.setAge(GameStage.Play); var actor = game.getPlayers().get(seat);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        StaticData.instance().attemptToLoadCard("Birgi, God of Storytelling");
        var source = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard("Birgi, God of Storytelling")),actor);
        source.setGameTimestamp(game.getNextTimestamp()); game.getStackZone().add(source);
        var sa = source.getFirstSpellAbility(); sa.setActivatingPlayer(actor);
        check(sa.getCardStateName()==CardStateName.Original,"initial ability is front face");
        var wire = new ByteArrayOutputStream();
        var session = new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]),wire));
        session.setLiveGame(game); BenchActionAudit.beginGame(game,"face-mutation");
        game.subscribeToEvents(new BenchMain.EventEmitter(session.getChannel(),"face-mutation",game));
        var answer = new JsonObject(); answer.addProperty("choice",1);
        var capture = new ByteArrayOutputStream(); var previous = System.err;
        RuntimeException rejected = null;
        try {
            System.setErr(new PrintStream(capture,true,StandardCharsets.UTF_8));
            BenchActionAudit.chosen(game,seat,sa,answer);
            if(fault.equals("before-announcement")) sa.setCardState(source.getState(CardStateName.Backside));
            try {
                BenchActionAudit.selected(game,seat,sa,answer);
                if(fault.equals("before-event")) sa.setCardState(source.getState(CardStateName.Backside));
                // Real MagicStack event is the observation boundary; direct insertion
                // deliberately bypasses payment here to isolate the receipt validator.
                game.getStack().add(sa);
            } catch(RuntimeException failure) { rejected=failure; }
            BenchActionAudit.finishGame(game,session);
        } finally { System.setErr(previous); }
        var rows = capture.toString(StandardCharsets.UTF_8).lines().filter(l->l.startsWith("[bench-action] "))
                .map(l->JsonParser.parseString(l.substring(15)).getAsJsonObject()).toList();
        var summary=rows.stream().filter(r->r.get("kind").getAsString().equals("summary")).findFirst().orElseThrow();
        var outcome=new JsonObject(); outcome.addProperty("winner",0); outcome.addProperty("crashed",false);
        BenchMain.guardIntegrityOutcome(session,game,outcome);
        boolean invalid = !legacy && !fault.equals("healthy");
        check(outcome.get("crashed").getAsBoolean()==invalid,"outcome admission matches explicit fault mode");
        check(summary.get("chosen").getAsInt()==1,"one exact priority choice recorded");
        if(invalid && fault.equals("before-announcement")) {
            check(rejected!=null && rejected.getMessage().contains("announcement does not match"),"chosen-to-announced face drift rejected");
            check(summary.get("selected").getAsInt()==0 && summary.get("observed").getAsInt()==0,"drift receives no announcement/execution receipt");
        } else {
            check(rejected==null,"insertion itself returned normally");
            check(summary.get("selected").getAsInt()==1 && summary.get("observed").getAsInt()==1,"actual stack event joined");
            check(summary.get("mismatches").getAsInt()==(invalid?1:0),"selected-to-event semantic comparison");
        }
        if(!legacy) {
            var choice=rows.stream().filter(r->r.get("kind").getAsString().equals("priority-choice-not-announced")).findFirst().orElseThrow();
            check(choice.getAsJsonObject("basis").getAsJsonObject("spellFace").get("state").getAsString().equals("Original"),"chosen face snapshotted before mutation");
        }
        check(!wire.toString(StandardCharsets.UTF_8).contains("forge-bench-private-action"),"private receipts never enter policy stream");
        var result=new JsonObject();result.addProperty("seat",seat);result.addProperty("fault",fault);result.addProperty("legacyGap",legacy);
        result.add("summary",summary);result.add("outcome",outcome);result.add("receipts",new Gson().toJsonTree(rows));
        System.out.println("FACE_RECEIPT_CASE "+result);
    }
    public static void main(String[] args) { try {
        GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,a)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"face-receipt-injection";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        boolean legacy=args.length>1 && args[1].equals("--legacy-gap");
        for(int seat=0;seat<2;seat++)for(String fault:List.of("healthy","before-announcement","before-event"))run(seat,fault,legacy);
        System.out.println("PASS "+checks+" face receipt fault-injection checks; legacyGap="+legacy);System.exit(0);
    } catch(Throwable failure){failure.printStackTrace();System.exit(1);} }
}
