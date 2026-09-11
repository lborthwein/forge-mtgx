package forge.bench;

import forge.StaticData;
import forge.ai.AiCardMemory;
import forge.ai.CubeDoomsdayPlan;
import forge.ai.CubeComboAi;
import forge.ai.LobbyPlayerCubeComboAi;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Direct non-executing planner calls, not opening-game outcomes or sampled seeds. */
public final class CubeComboProbePuritySmoke {
    private static Card card(String name, Player p, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        Card c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(zone).add(c); c.setSickness(false); return c;
    }
    private static Map<String,Object> state(Player p) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (var set : AiCardMemory.MemorySet.values()) result.put(set.name(), AiCardMemory.getMemorySet(p,set).stream().map(Card::getId).sorted().toList());
        result.put("mana",p.getManaPool().totalMana());
        result.put("snowForColor",p.getManaPool().isSnowForColor());
        result.put("conversion",Arrays.asList(p.getManaPool().getPossibleColorUses(MagicColor.WHITE),p.getManaPool().getPossibleColorUses(MagicColor.BLUE),p.getManaPool().getPossibleColorUses(MagicColor.BLACK),p.getManaPool().getPossibleColorUses(MagicColor.RED),p.getManaPool().getPossibleColorUses(MagicColor.GREEN)));
        result.put("board",p.getCardsIn(ZoneType.Battlefield).stream().map(c->c.getId()+":"+c.isTapped()).toList());
        result.put("express",forge.game.card.CardCollection.combine(p.getCardsIn(ZoneType.Battlefield),p.getCardsIn(ZoneType.Hand)).stream().flatMap(c->c.getManaAbilities().stream()).map(a->a.getHostCard().getId()+":"+a.getManaPart().getExpressChoice()).toList());
        result.put("actors",forge.game.card.CardCollection.combine(p.getCardsIn(ZoneType.Battlefield),p.getCardsIn(ZoneType.Hand)).stream()
                .flatMap(c->c.getSpellAbilities().stream()).map(a->a.getHostCard().getId()+":"+(a.getActivatingPlayer()==null?"null":a.getActivatingPlayer().getId())).toList());
        result.put("life",p.getLife()); return result;
    }
    private static boolean run(int seat, PhaseType phase, boolean probeDoom) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for(int i=0;i<2;i++) {
            Deck deck = new Deck(); StaticData.instance().attemptToLoadCard("Thassa's Oracle");
            deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard("Thassa's Oracle"),1);
            players.add(new RegisteredPlayer(deck).setPlayer(i==seat?new LobbyPlayerCubeComboAi("Combo"+i):GamePlayerUtil.createAiPlayer("Default"+i,i,0,null,"Default")));
        }
        GameRules rules = new GameRules(GameType.Constructed); rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game=new Match(rules,players,"probe purity").createGame();game.setAge(GameStage.Play);Player p=game.getPlayers().get(seat);
        game.getPhaseHandler().setupFirstTurn(p,()->game.getPhaseHandler().devModeSet(phase,p));
        for(int i=0;i<3;i++)card("Swamp",p,ZoneType.Battlefield);
        Card sentinel=card("Island",p,ZoneType.Battlefield);
        Card guide=card("Simian Spirit Guide",p,ZoneType.Hand);
        card("Thassa's Oracle",p,ZoneType.Library);for(int i=0;i<9;i++)card("Forest",p,ZoneType.Library);
        if(probeDoom)card("Doomsday",p,ZoneType.Hand);
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();
        AiCardMemory.rememberCard(p,sentinel,AiCardMemory.MemorySet.PAYS_SAC_COST);
        AiCardMemory.rememberCard(p,sentinel,AiCardMemory.MemorySet.PAYS_TAP_COST);
        sentinel.getManaAbilities().get(0).getManaPart().setExpressChoice("U");
        guide.getManaAbilities().get(0).getManaPart().setExpressChoice("R");
        p.getManaPool().adjustColorReplacement(MagicColor.BLUE,MagicColor.RED,true);p.getManaPool().setSnowForColor(true);
        Map<String,Object> before=state(p);
        var action=new CubeDoomsdayPlan(p).nextAction();
        if(action!=null)throw new AssertionError("No draw route exists; planner must decline");
        Map<String,Object> after=state(p);List<String> changed=new ArrayList<>();
        for(String k:before.keySet())if(!before.get(k).equals(after.get(k)))changed.add(k+":"+before.get(k)+"->"+after.get(k));
        System.out.println("PURITY seat="+seat+" phase="+phase+" probeDoom="+probeDoom+" changed="+changed);
        if(changed.isEmpty()) {
            // Nested and exceptional speculation must restore the same live objects.
            var liveMemory=AiCardMemory.getMemorySet(p,AiCardMemory.MemorySet.PAYS_TAP_COST);
            var livePool=p.getManaPool();
            try {
                CubeComboAi.probePayment(p,()->{
                    liveMemory.clear();livePool.restoreColorReplacements();
                    CubeComboAi.probePayment(p,()->{liveMemory.add(sentinel);livePool.setSnowForColor(true);return null;});
                    if(!liveMemory.isEmpty()||livePool.isSnowForColor())throw new AssertionError("Nested probe did not restore outer state");
                    throw new IllegalArgumentException("intentional probe exception");
                });
                throw new AssertionError("Expected probe exception");
            } catch(IllegalArgumentException expected) {
                if(!expected.getMessage().equals("intentional probe exception"))throw expected;
            }
            if(livePool!=p.getManaPool()||liveMemory!=AiCardMemory.getMemorySet(p,AiCardMemory.MemorySet.PAYS_TAP_COST)||!before.equals(state(p)))throw new AssertionError("Exceptional probe changed state/identity");
            if(probeDoom) {
                var doom=p.getCardsIn(ZoneType.Hand).stream().filter(c->c.getName().equals("Doomsday")).findFirst().orElseThrow().getSpellAbilities().get(0).copy(p);
                if(!CubeComboAi.canPayCost(doom,p,false))throw new AssertionError("BBB is genuinely payable despite no combo draw route");
                if(!before.equals(state(p)))throw new AssertionError("Successful payment probe changed live state");
                // Detached queried abilities are not necessarily in a card's
                // live list. A parent's setter propagates through the tree.
                doom.setActivatingPlayer(null);
                var child=doom.getSubAbility();
                Player other=p.getOpponents().get(0);
                child.setActivatingPlayer(other);
                var extra=child.copy(other);doom.setAdditionalAbility("purity",extra);
                var listChild=(forge.game.spellability.AbilitySub)child.copy(null);
                // Native copy(null) preserves the original actor; explicitly
                // construct the null-actor test state instead of assuming it.
                listChild.setActivatingPlayer(null);
                doom.setAdditionalAbilityList("purity",List.of(listChild));
                if(doom.getActivatingPlayer()!=null||child.getActivatingPlayer()!=other||extra.getActivatingPlayer()!=other||listChild.getActivatingPlayer()!=null)
                    throw new AssertionError("Actor fixture precondition");
                if(!CubeComboAi.canPayCost(doom,p,false))throw new AssertionError("Detached null-actor cost is payable");
                if(doom.getActivatingPlayer()!=null||child.getActivatingPlayer()!=other||extra.getActivatingPlayer()!=other||listChild.getActivatingPlayer()!=null)
                    throw new AssertionError("Query actor/child identity not restored");
                var handMana=guide.getManaAbilities().get(0);
                try {
                    CubeComboAi.probePayment(p,()->{
                        doom.setActivatingPlayer(p);handMana.getManaPart().setExpressChoice("G");handMana.setActivatingPlayer(other);
                        CubeComboAi.probePayment(p,()->{doom.setActivatingPlayer(other);handMana.getManaPart().setExpressChoice("B");return null;},doom);
                        if(doom.getActivatingPlayer()!=p||!handMana.getManaPart().getExpressChoice().equals("G"))throw new AssertionError("Nested actor/hand choice restoration");
                        throw new IllegalArgumentException("intentional actor exception");
                    },doom);
                    throw new AssertionError("Expected actor exception");
                } catch(IllegalArgumentException expected) {
                    if(!expected.getMessage().equals("intentional actor exception"))throw expected;
                }
                if(doom.getActivatingPlayer()!=null||child.getActivatingPlayer()!=other||extra.getActivatingPlayer()!=other||listChild.getActivatingPlayer()!=null||!before.equals(state(p)))
                    throw new AssertionError("Exceptional actor/hand choice restoration");
            }
        }
        return changed.isEmpty();
    }
    private static final class InspectMatrix extends forge.game.mana.ManaConversionMatrix {
        String value(){return Arrays.toString(colorConversionMatrix)+Arrays.toString(colorRestrictionMatrix)+snowForColor;}
    }
    private static void matrixCopy() {
        InspectMatrix a=new InspectMatrix();a.restoreColorReplacements();
        a.adjustColorReplacement(MagicColor.BLUE,MagicColor.RED,true);
        a.adjustColorReplacement(MagicColor.BLUE,MagicColor.BLUE,false);a.setSnowForColor(true);
        String before=a.value();var snapshot=a.copyConversionState();
        a.restoreColorReplacements();a.restoreConversionState(snapshot);
        if(!before.equals(a.value()))throw new AssertionError("Matrix copy loses masked conversion/restriction bits");
        a.adjustColorReplacement(MagicColor.BLUE,MagicColor.GREEN,true);a.restoreConversionState(snapshot);
        if(!before.equals(a.value()))throw new AssertionError("Matrix snapshot aliases live arrays");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){
                case "getAssetsDir" -> args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "combo-probe-purity-v2";
                default -> throw new AssertionError(method.getName());
            }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            int failures=0;
            matrixCopy();
            for(int seat=0;seat<2;seat++)for(PhaseType phase:List.of(PhaseType.MAIN1,PhaseType.MAIN2))for(boolean probe:new boolean[]{false,true})if(!run(seat,phase,probe))failures++;
            if(failures!=0)throw new AssertionError("Null plan leaked live state in "+failures+" of8 cases");
            System.out.println("PASS null-plan purity8/8");
        } catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
