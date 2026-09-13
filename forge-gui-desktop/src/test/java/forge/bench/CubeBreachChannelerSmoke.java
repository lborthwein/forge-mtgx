package forge.bench;

import forge.StaticData;
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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Native observation only: no forced choices or strategy changes. */
public final class CubeBreachChannelerSmoke {
    private static final String BREACH = "Underworld Breach", FRANTIC = "Frantic Search";
    private static final String DRC = "Dragon's Rage Channeler", OUTLET = "Aetherflux Reservoir";
    private static final List<String> CASES = List.of("complete", "exact-library", "short-library",
            "no-breach", "no-channeler", "no-outlet", "short-fuel", "no-blue", "two-lands", "low-library");
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone, boolean tapped) { }
    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone, false));
    }
    private static List<Placement> own(String name) {
        List<Placement> result = new ArrayList<>();
        if (!name.equals("no-breach")) add(result, 1, BREACH, ZoneType.Battlefield);
        if (!name.equals("no-channeler")) add(result, 1, DRC, ZoneType.Battlefield);
        if (!name.equals("no-outlet")) add(result, 1, OUTLET, ZoneType.Battlefield);
        add(result, name.equals("two-lands") ? 2 : 3,
                name.equals("no-blue") ? "Plains" : "Island", ZoneType.Battlefield);
        add(result, 1, FRANTIC, ZoneType.Graveyard);
        add(result, name.equals("short-fuel") ? 2 : 3, "Forest", ZoneType.Graveyard);
        add(result, 2, "Forest", ZoneType.Hand);
        int library = switch (name) {
            case "exact-library" -> 15;
            case "short-library" -> 14;
            case "low-library" -> 3;
            default -> 18;
        };
        add(result, library, "Forest", ZoneType.Library);
        add(result, 40 - result.size(), "Forest", ZoneType.Exile);
        if (result.size() != 40) throw new AssertionError("own deck size");
        return result;
    }
    private static List<Placement> other(String name) {
        List<Placement> result = new ArrayList<>();
        add(result, 40, "Forest", ZoneType.Library);
        return result;
    }
    private static List<Placement> placements(boolean owner, String name) {
        return owner ? own(name) : other(name);
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("breach-channeler fixture");
        for (Placement p : placements(owner, name)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String name) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(owner, name)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(
                    FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(false);
            if (p.tapped()) card.setTapped(true);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch " + name + " actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }


    /** Observe the actual partition after native surveil, without reading library identities. */
    public static final class SurveilObserver {
        private final Player owner;
        private final String key;
        int step;
        SurveilObserver(Player owner, String key) { this.owner = owner; this.key = key; }
        @com.google.common.eventbus.Subscribe
        public void surveil(forge.game.event.GameEventSurveil event) {
            if (!owner.getView().equals(event.player())) return;
            var resolving = owner.getGame().getStack().peekAbility();
            Object triggering = resolving == null ? null
                    : resolving.getTriggeringObject(forge.game.ability.AbilityKey.SpellAbility);
            var cast = triggering instanceof forge.game.spellability.SpellAbility value ? value : null;
            boolean castOnStack = false;
            if (cast != null) for (var item : owner.getGame().getStack())
                if (item.getSpellAbility() == cast) castOnStack = true;
            System.out.println("CHANNELER_SURVEIL " + key + " step=" + step
                    + " kept=" + event.toLibrary() + " milled=" + event.toGraveyard()
                    + " api=" + (resolving == null ? "none" : resolving.getApi())
                    + " source=" + (resolving == null ? "none" : resolving.getHostCard().getName().replace(' ', '_'))
                    + " actor=" + (resolving != null && resolving.getActivatingPlayer() == owner)
                    + " sourceId=" + (resolving == null ? -1 : resolving.getHostCard().getId())
                    + " sourceTimestamp=" + (resolving == null ? -1 : resolving.getHostCard().getGameTimestamp())
                    + " cast=" + (cast == null ? "none" : cast.getHostCard().getName().replace(' ', '_'))
                    + " castActor=" + (cast != null && cast.getActivatingPlayer() == owner)
                    + " castId=" + (cast == null ? -1 : cast.getHostCard().getId())
                    + " castTimestamp=" + (cast == null ? -1 : cast.getHostCard().getGameTimestamp())
                    + " castOnStack=" + castOnStack);
        }
    }
    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }
    private static void run(boolean improved, int seat, String name) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, name)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : defaultAi(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native breach-channeler diagnosis").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, name); populate(opponent, false, name);
        player.setLife(40, null);
        game.setAge(GameStage.Play);
        int startTurn = seat == 0 ? 1 : 2;
        game.getPhaseHandler().setupFirstTurn(seat == 0 ? player : opponent,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(987100L + 100L * seat + CASES.indexOf(name));
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        System.out.println("CHANNELER_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " ownLife=40 registered=40 initialMana=0 startTurn=" + startTurn
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size());
        SurveilObserver observer = new SurveilObserver(player, key);
        game.subscribeToEvents(observer);
        Set<Integer> ids = new HashSet<>();
        int steps = 0, frantic = 0, escapes = 0, surveilTriggers = 0;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= startTurn + 1 && steps < 900) {
            observer.step = steps + 1;
            game.getPhaseHandler().mainLoopStep(); steps++;
            for (var item : game.getStack()) if (ids.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() != player) continue;
                String host = sa.getHostCard().getName();
                if (sa.isSpell() && host.equals(FRANTIC)) {
                    frantic++; if (sa.isEscape()) escapes++;
                }
                if (!sa.isSpell() && host.equals(DRC)) surveilTriggers++;
                System.out.println("CHANNELER_STACK " + key + " step=" + steps + " source="
                        + host.replace(' ', '_') + " api=" + sa.getApi() + " spell=" + sa.isSpell()
                        + " escape=" + sa.isEscape() + " sourceId=" + sa.getHostCard().getId()
                        + " timestamp=" + sa.getHostCard().getGameTimestamp());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " phase=" + game.getPhaseHandler().getPhase()
                    + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                    + " ownHand=" + player.getCardsIn(ZoneType.Hand).size()
                    + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size()
                    + " exile=" + player.getCardsIn(ZoneType.Exile).size()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("CHANNELER_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("CHANNELER_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                + " franticCasts=" + frantic + " escapes=" + escapes + " observedDrcTriggers=" + surveilTriggers
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " ownLife=" + player.getLife() + " opponentLife=" + opponent.getLife()
                + " outcome=" + game.getOutcome());
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "breach-channeler-diagnosis-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US"); return null;
            });
            for (String card : List.of(BREACH, FRANTIC, DRC, OUTLET, "Forest", "Plains", "Island"))
                StaticData.instance().attemptToLoadCard(card);
            for (String name : CASES) for (int seat = 0; seat < 2; seat++) run(args[1].equals("improved"), seat, name);
            System.out.println("CHANNELER_SUITE_COMPLETE cases=" + (CASES.size() * 2));
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
