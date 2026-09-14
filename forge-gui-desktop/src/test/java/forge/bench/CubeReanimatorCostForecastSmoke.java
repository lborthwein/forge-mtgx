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
public final class CubeReanimatorCostForecastSmoke {
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
    private static void run(int seat, int lands, String modifier, int index) throws Exception {
        BenchRandomAudit.install(999200 + index);
        List<Entry> own = new ArrayList<>(), other = new ArrayList<>();
        own.add(new Entry("Entomb", ZoneType.Hand)); own.add(new Entry("Corpse Dance", ZoneType.Hand));
        for (int i = 0; i < lands; i++) own.add(new Entry("Swamp", ZoneType.Battlefield));
        if (modifier.equals("reduce")) own.add(new Entry("Goblin Electromancer", ZoneType.Battlefield));
        if (modifier.equals("tax")) other.add(new Entry("Thalia, Guardian of Thraben", ZoneType.Battlefield));
        if (modifier.equals("minimum")) other.add(new Entry("Trinisphere", ZoneType.Battlefield));
        if (modifier.equals("limit")) other.add(new Entry("Rule of Law", ZoneType.Battlefield));
        while (own.size() < 40) own.add(new Entry("Swamp", ZoneType.Library));
        while (other.size() < 40) other.add(new Entry("Forest", ZoneType.Library));
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(s == seat
                ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Reanimator adjusted-cost query").createGame(); game.setAge(GameStage.Play);
        Player p = game.getPlayers().get(seat), opp = game.getPlayers().get(1-seat);
        game.getPhaseHandler().setupFirstTurn(p, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p));
        populate(p, own); populate(opp, other); game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        var search = p.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Entomb"))
                .flatMap(c -> c.getSpellAbilities().stream()).filter(forge.game.spellability.SpellAbility::isSpell).findFirst().orElseThrow();
        search.setActivatingPlayer(p);
        if (modifier.equals("extra-cost")) search.setPayCosts(new forge.game.cost.Cost("B Discard<1/Card>", false));
        var method = forge.ai.CubeReanimatorPlan.class.getDeclaredMethod("bothPayable", forge.game.spellability.SpellAbility.class);
        method.setAccessible(true);
        var plan = new forge.ai.CubeReanimatorPlan(p);
        boolean expected = !modifier.equals("limit") && !modifier.equals("extra-cost")
                && lands >= (modifier.equals("tax") || modifier.equals("minimum") ? 6 : modifier.equals("reduce") ? 3 : 4);
        var before = visibleSnapshot(p);
        for (int repeat = 0; repeat < 3; repeat++) {
            boolean actual = (boolean) method.invoke(plan, search);
            if (actual != expected) throw new AssertionError("forecast seat="+seat+" lands="+lands+" modifier="+modifier+" expected="+expected+" actual="+actual);
            if (!before.equals(visibleSnapshot(p))) throw new AssertionError("query changed own/public state "+modifier);
        }
        System.out.println("REANIMATOR_COST_CASE seat="+seat+" lands="+lands+" modifier="+modifier+" payable="+expected+" purityRepeats=3");
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
            for (int seat=0;seat<2;seat++) for (int lands : new int[]{3,4,5,6})
                for (String modifier : List.of("plain","tax","reduce","minimum","limit","extra-cost")) run(seat,lands,modifier,cases++);
            System.out.println("REANIMATOR_COST_COMPLETE cases="+cases+" policy="+forge.ai.CubeComboAi.VERSION);
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
}
