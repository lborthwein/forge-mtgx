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

/** Diagnostic of native decisions from assembled positions, not whole-game strength.
 * No action is supplied by the host. Setup is synthetic and exactly registered.
 */
public final class CubeThopterExecutionSmoke {
    private static final String URZA = "Urza, Lord High Artificer";
    private static final String FOUNDRY = "Thopter Foundry";
    private static final String SWORD = "Sword of the Meek";
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone) {}

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        if (owner) {
            result.add(new Placement(URZA, ZoneType.Battlefield));
            result.add(new Placement(FOUNDRY, ZoneType.Battlefield));
            result.add(new Placement(SWORD, control.equals("sword-exile") ? ZoneType.Exile : ZoneType.Battlefield));
            if (control.equals("one-island")) result.add(new Placement("Island", ZoneType.Battlefield));
            if (control.equals("token-anthem")) result.add(new Placement("Intangible Virtue", ZoneType.Battlefield));
        } else if (control.equals("cursed-totem")) {
            result.add(new Placement("Cursed Totem", ZoneType.Battlefield));
        }
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        return result;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("Thopter diagnostic");
        for (Placement p : placements(owner, control)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String control) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(owner, control)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name())), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(false);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch");
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }

    private static int tokens(Player player) {
        return (int) player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isToken() && c.getType().hasSubtype("Thopter")).count();
    }

    private static String swordZone(Player player) {
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) if (c.getName().equals(SWORD)) return z.name();
        return "missing";
    }

    private static void run(boolean improved, int seat, PhaseType phase, String control) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                        : recordingDefault(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native Thopter diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(10811 + seat * 100 + control.length());
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("THOPTER_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0");
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, activations = 0, returns = 0, resolvedReturns = 0, created = 0, maxMana = 0, maxTokens = 0;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps < 600) {
            int before = tokens(player);
            String beforeSword = swordZone(player);
            game.getPhaseHandler().mainLoopStep();
            steps++;
            if (beforeSword.equals("Graveyard") && swordZone(player).equals("Battlefield")) {
                resolvedReturns++;
                Card sword = player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(SWORD)).findFirst().orElseThrow();
                if (!sword.isEquipping() || sword.getEquipping().getController() != player
                        || !sword.getEquipping().getType().hasSubtype("Thopter"))
                    throw new AssertionError("Sword must return and attach through native resolution");
            }
            created += Math.max(0, tokens(player) - before);
            maxTokens = Math.max(maxTokens, tokens(player));
            maxMana = Math.max(maxMana, player.getManaPool().totalMana());
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() == player && sa.getHostCard().getName().equals(FOUNDRY) && sa.isActivatedAbility()) activations++;
                if (sa.getHostCard().getName().equals(SWORD) && sa.isTrigger()) returns++;
                System.out.println("THOPTER_STACK " + key + " step=" + steps + " source=" + sa.getHostCard().getName()
                        + " api=" + sa.getApi() + " trigger=" + sa.isTrigger() + " costs=" + sa.getPayCosts());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife() + " tokens=" + tokens(player)
                    + " mana=" + player.getManaPool().totalMana() + " sword=" + swordZone(player);
            if (!state.equals(previous)) System.out.println("THOPTER_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 600) throw new AssertionError("native step budget exhausted " + key);
        if (control.equals("sword-exile") && returns != 0) throw new AssertionError("exiled Sword returned");
        if (control.equals("token-anthem") && returns != 0) throw new AssertionError("2/2 Thopter triggered Sword");
        if (improved && Boolean.getBoolean("forge.test.requireSwordReturn")
                && List.of("none", "one-island").contains(control) && resolvedReturns < 1)
            throw new AssertionError("Candidate must accept and resolve the free Sword return");
        System.out.println("THOPTER_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " foundryActivations=" + activations + " swordTriggers=" + returns
                + " resolvedReturns=" + resolvedReturns
                + " created=" + created + " maxTokens=" + maxTokens + " maxMana=" + maxMana
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
    }

    private static forge.ai.LobbyPlayerAi recordingDefault(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null) {
            @Override
            public Player createIngamePlayer(Game game, int id) {
                Player result = new Player(getName(), game, id);
                result.setFirstController(new forge.ai.PlayerControllerAi(game, result, this) {
                    @Override
                    public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
                        boolean answer = super.confirmTrigger(wrapper);
                        if (wrapper.getHostCard().getName().equals(SWORD))
                            System.out.println("THOPTER_DEFAULT_CONFIRM answer=" + answer + " source=" + SWORD);
                        return answer;
                    }
                });
                return result;
            }
        };
        lobby.setAiProfile("Default");
        return lobby;
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "thopter-native-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(URZA, FOUNDRY, SWORD, "Forest", "Island", "Cursed Totem", "Intangible Virtue"))
                StaticData.instance().attemptToLoadCard(name);
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : List.of("none", "one-island", "sword-exile", "cursed-totem", "token-anthem"))
                    run(args[1].equals("improved"), seat, phase, control);
            System.out.println("THOPTER_SUITE_COMPLETE cases=20");
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
