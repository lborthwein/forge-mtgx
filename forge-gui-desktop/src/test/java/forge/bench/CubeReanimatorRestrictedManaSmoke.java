package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
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
import java.lang.reflect.Proxy;
import java.util.*;

/** Prepared native-cost query fixture, not gameplay or a strength result.
 * No sampled games, forced payoff, or opponent hidden information. */
public final class CubeReanimatorRestrictedManaSmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final String TROLL = "Troll of Khazad-dûm";
    private static forge.item.PaperCard paper(String name) {
        StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name);
    }
    private static Deck deck(List<Entry> entries) {
        Deck d = new Deck();
        for (Entry e : entries) d.getMain().add(paper(e.name()), 1);
        if (entries.size() != 40) throw new AssertionError("registered deck size");
        return d;
    }
    private static void populate(Player p, List<Entry> entries) {
        for (Entry e : entries) {
            Card c = Card.fromPaperCard(paper(e.name()), p);
            c.setGameTimestamp(p.getGame().getNextTimestamp());
            p.getZone(e.zone()).add(c); c.setSickness(false);
        }
    }
    private static Map<String, Object> visibleSnapshot(Player player) {
        Map<String, Object> state = new LinkedHashMap<>();
        Game game = player.getGame();
        state.put("timestamp", game.getTimestamp());
        state.put("rng", ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot().toString());
        state.put("mana", player.getManaPool().totalMana());
        state.put("phase", game.getPhaseHandler().getPhase());
        state.put("active", game.getPhaseHandler().getPlayerTurn().getId());
        state.put("conversion", java.util.stream.IntStream.range(0, 6)
                .map(i -> player.getManaPool().getPossibleColorUses((byte)(1 << i))).boxed().toList());
        state.put("snowConversion", player.getManaPool().isSnowForColor());
        state.put("manaObjects", java.util.stream.StreamSupport.stream(player.getManaPool().spliterator(), false).toList());
        for (var memory : forge.ai.AiCardMemory.MemorySet.values())
            state.put(memory.name(), forge.ai.AiCardMemory.getMemorySet(player, memory).stream().map(Card::getId).sorted().toList());
        for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Graveyard, ZoneType.Battlefield, ZoneType.Exile}) {
            state.put(zone.name(), player.getCardsIn(zone).stream()
                    .map(c -> c.getId() + ":" + c.getGameTimestamp() + ":" + c.isTapped() + ":" + c.getCastFrom()
                            + ":" + c.getCastSA()).toList());
            state.put(zone.name() + "Abilities", player.getCardsIn(zone).stream().flatMap(c -> c.getSpellAbilities().stream())
                    .map(a -> a.getHostCard().getId() + ":" + a.getActivatingPlayer() + ":" + a.getTargets()
                            + ":" + (a.getManaPart() == null ? "null" : a.getManaPart().getExpressChoice())).toList());
        }
        state.put("history", game.getStack().getSpellCardsCastThisTurn().stream()
                .map(c -> c.getId() + ":" + c.getCastFrom() + ":" + c.getController()).toList());
        state.put("stack", java.util.stream.StreamSupport.stream(game.getStack().spliterator(), false)
                .map(a -> a.getId() + ":" + a.getSpellAbility().getTargets()).toList());
        return state;
    }
    private static void run(int seat, int sharedCount, int index) throws Exception {
        BenchRandomAudit.install(999400 + index);
        List<Entry> own = new ArrayList<>(), other = new ArrayList<>();
        own.add(new Entry("Entomb", ZoneType.Hand)); own.add(new Entry("Karmic Guide", ZoneType.Hand));
        for (int i=0;i<10+sharedCount;i++) own.add(new Entry("Memnite", ZoneType.Battlefield));
        while (own.size()<40) own.add(new Entry("Swamp",ZoneType.Library));
        while (other.size()<40) other.add(new Entry("Forest",ZoneType.Library));
        List<RegisteredPlayer> players=new ArrayList<>();
        for(int s=0;s<2;s++)players.add(new RegisteredPlayer(deck(s==seat?own:other)).setPlayer(s==seat
                ?new forge.ai.LobbyPlayerCubeComboAi("Combo-"+s):GamePlayerUtil.createAiPlayer("Default-"+s,s,0,null,"Default")));
        GameRules rules=new GameRules(GameType.Constructed); rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game game=new Match(rules,players,"Restricted-mana forecast diagnostic").createGame();game.setAge(GameStage.Play);
        Player p=game.getPlayers().get(seat),opp=game.getPlayers().get(1-seat);
        game.getPhaseHandler().setupFirstTurn(p,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p));
        populate(p,own);populate(opp,other);
        var bodies=new ArrayList<Card>();p.getCardsIn(ZoneType.Battlefield).forEach(bodies::add);
        for(int i=0;i<bodies.size();i++) {
            String produced=i<sharedCount?"Any":(i<sharedCount+5?new String[]{"W","W","C","C","C"}[i-sharedCount]:new String[]{"B","W","C","C","C"}[i-sharedCount-5]);
            String restrict=i<sharedCount?"":i<sharedCount+5?" | RestrictValid$ Spell.Instant":" | RestrictValid$ Spell.Creature";
            Card card=bodies.get(i);
            var mana=forge.game.ability.AbilityFactory.getAbility("AB$ Mana | Cost$ T | Produced$ "+produced+restrict+" | SpellDescription$ Diagnostic restricted producer",card);
            card.getCurrentState().addSpellAbility(mana);
        }
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();
        var search=p.getCardsIn(ZoneType.Hand).stream().filter(c->c.getName().equals("Entomb")).flatMap(c->c.getSpellAbilities().stream()).filter(forge.game.spellability.SpellAbility::isSpell).findFirst().orElseThrow();
        var guide=p.getCardsIn(ZoneType.Hand).stream().filter(c->c.getName().equals("Karmic Guide")).flatMap(c->c.getSpellAbilities().stream()).filter(forge.game.spellability.SpellAbility::isSpell).findFirst().orElseThrow();
        search.setActivatingPlayer(p);guide.setActivatingPlayer(p);
        // A black Entomb payment must consume one of the shared producers.
        long blackForSearch=bodies.stream().filter(c->c.getManaAbilities().stream().anyMatch(m->m.canProduce("B")&&m.getManaPart().meetsManaRestrictions(search))).count();
        if(blackForSearch!=sharedCount)throw new AssertionError("native restriction setup "+blackForSearch);
        var method=forge.ai.CubeReanimatorPlan.class.getDeclaredMethod("bothPayable",forge.game.spellability.SpellAbility.class);method.setAccessible(true);
        var plan=new forge.ai.CubeReanimatorPlan(p);var before=visibleSnapshot(p);
        boolean forecast=(boolean)method.invoke(plan,search);
        if(!before.equals(visibleSnapshot(p)))throw new AssertionError("forecast mutated state");
        boolean searchPayable=forge.ai.CubeComboAi.canPayCost(search,p,false);
        // Explicit diagnostic intervention only: reserve the source every Entomb
        // payment needs, then ask native payment about Guide. No spell is cast.
        boolean returnAfterSearch;
        bodies.get(0).setTapped(true);
        try {returnAfterSearch=forge.ai.CubeComboAi.canPayCost(guide,p,false);}
        finally {bodies.get(0).setTapped(false);}
        if(!searchPayable||returnAfterSearch!=(sharedCount==2))throw new AssertionError("native resource witness mismatch");
        System.out.println("RESTRICTED_REANIMATOR_CASE seat="+seat+" shared="+sharedCount+" forecast="+forecast
                +" searchPayable="+searchPayable+" returnAfterRequiredSource="+returnAfterSearch+" policy="+forge.ai.CubeComboAi.VERSION);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "reanimator-cost-forecast";
                default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> { p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            int cases=0;
            for(int seat=0;seat<2;seat++)for(int shared:new int[]{1,2})run(seat,shared,cases++);
            System.out.println("RESTRICTED_REANIMATOR_COMPLETE cases="+cases+" policy="+forge.ai.CubeComboAi.VERSION);
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
}
