package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
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

/** Catalogue K diagnostic. Entirely native decisions on registered boards;
 * no policy override or claim of infinity/strength from a prepared fixture. */
public final class CubeTopKittenExecutionSmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final Set<String> loaded = new HashSet<>();
    private static forge.item.PaperCard paper(String name) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name);
    }
    private static Deck deck(List<Entry> entries) {
        Deck deck = new Deck();
        for (Entry entry : entries) deck.getMain().add(paper(entry.name()), 1);
        return deck;
    }
    private static void populate(Player player, List<Entry> entries) {
        for (Entry entry : entries) {
            Card card = Card.fromPaperCard(paper(entry.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(entry.zone()).add(card); card.setSickness(false);
            if (card.getName().equals("Narset, Parter of Veils")) card.setCounters(CounterEnumType.LOYALTY, 5);
        }
    }
    private static void piece(List<Entry> list, String name, boolean removed) {
        list.add(new Entry(removed ? "Forest" : name, removed ? ZoneType.Exile : ZoneType.Battlefield));
    }
    private static void run(int seat, String engine, String control, boolean candidate) {
        List<Entry> own = new ArrayList<>(), other = new ArrayList<>();
        piece(own, "Displacer Kitten", control.equals("no-kitten"));
        piece(own, "Aetherflux Reservoir", control.equals("no-outlet"));
        String restorer = engine.equals("mystic") ? "Mystic Forge" : engine.startsWith("ring") ? "The One Ring" : "Narset, Parter of Veils";
        piece(own, restorer, control.equals("no-restorer"));
        piece(own, engine.equals("mystic") ? "Sol Ring" : engine.endsWith("birgi") ? "Birgi, God of Storytelling" : "Helm of Awakening", false);
        own.add(new Entry("Sensei's Divining Top", engine.startsWith("ring") ? ZoneType.Hand : ZoneType.Battlefield));
        for (int i = 0; i < 2; i++) own.add(new Entry("Island", ZoneType.Battlefield));
        for (int i = 0; i < 20; i++) own.add(new Entry("Forest", ZoneType.Library));
        while (own.size() < 40) own.add(new Entry("Forest", ZoneType.Exile));
        for (int i = 0; i < 30; i++) other.add(new Entry("Forest", ZoneType.Library));
        while (other.size() < 40) other.add(new Entry("Forest", ZoneType.Exile));
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(candidate && s == seat
                ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Top Kitten native diagnostic").createGame(); game.setAge(GameStage.Play);
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player));
        populate(player, own); populate(opponent, other); opponent.setLife(40, null);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(850913L + seat);
        String key = "seat=" + seat + " engine=" + engine + " control=" + control
                + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION;
        System.out.println("TOP_KITTEN_FIXTURE " + key);
        Set<Integer> seen = new HashSet<>();
        int steps = 0, casts = 0, draws = 0, kittenTriggers = 0, restores = 0, lastTurn = -1, entries = 0;
        Map<Integer, Long> timestamps = new HashMap<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (card.getName().equals(restorer) || card.getName().equals("Sol Ring")) timestamps.put(card.getId(), card.getGameTimestamp());
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps < 4000) {
            int turn = game.getPhaseHandler().getTurn();
            if (turn != lastTurn) {
                System.out.println("TOP_KITTEN_TURN turn=" + turn + " ours=" + (game.getPhaseHandler().getPlayerTurn() == player)
                        + " hand=" + player.getCardsIn(ZoneType.Hand) + " librarySize=" + player.getCardsIn(ZoneType.Library).size());
                lastTurn = turn;
            }
            steps++; game.getPhaseHandler().mainLoopStep();
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) if (timestamps.containsKey(card.getId())
                    && timestamps.get(card.getId()) != card.getGameTimestamp()) {
                entries++; timestamps.put(card.getId(), card.getGameTimestamp());
                System.out.println("TOP_KITTEN_RESTORE turn=" + game.getPhaseHandler().getTurn()
                        + " card=" + card.getName().replace(' ', '_') + " tapped=" + card.isTapped()
                        + " loyalty=" + card.getCounters(CounterEnumType.LOYALTY)
                        + " burden=" + card.getCounters(CounterEnumType.BURDEN));
            }
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var ability = item.getSpellAbility(); if (ability.getActivatingPlayer() != player) continue;
                String name = ability.getHostCard().getName();
                if (name.equals("Sensei's Divining Top")) {
                    if (ability.isSpell() && !ability.isCopied()) casts++;
                    if (ability.getApi() == forge.game.ability.ApiType.Draw) draws++;
                }
                if (name.equals("Displacer Kitten") && ability.getApi() == forge.game.ability.ApiType.ChangeZone) kittenTriggers++;
                if (name.equals(restorer) && !ability.isSpell()) restores++;
                System.out.println("TOP_KITTEN_STACK turn=" + game.getPhaseHandler().getTurn() + " card=" + name
                        + " api=" + ability.getApi() + " targets=" + ability.getTargets());
            }
        }
        System.out.println("TOP_KITTEN_RESULT " + key + " steps=" + steps + " topCasts=" + casts + " topDraws=" + draws
                + " kittenTriggers=" + kittenTriggers + " restores=" + restores + " entries=" + entries + " librarySize=" + player.getCardsIn(ZoneType.Library).size()
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver() + " life=" + player.getLife()
                + " opponentLife=" + opponent.getLife() + " budgetExhausted=" + (steps >= 4000));
        if (steps >= 4000) throw new AssertionError("Diagnostic budget exhausted: " + key);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p,m,v) -> switch(m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "top-kitten-diagnostic-v1";
                default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> {p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);p.setPref(FPref.UI_LANGUAGE, "en-US");return null;});
            boolean candidate = args.length < 2 || !args[1].equals("baseline"); int cases = 0;
            for (int seat = 0; seat < 2; seat++) for (String engine : List.of("mystic", "ring-birgi", "ring-helm", "narset-birgi", "narset-helm"))
                for (String control : List.of("none", "no-kitten", "no-restorer", "no-outlet")) {
                    run(seat, engine, control, candidate); cases++;
                }
            System.out.println("TOP_KITTEN_COMPLETE cases=" + cases + " candidate=" + candidate);
        } catch (Throwable failure) {failure.printStackTrace();System.exit(1);}
    }
}
