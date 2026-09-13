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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Prepared native top-tutor availability diagnosis. No policy changes or forced actions.
 * Queries use only our hand and public state. No hidden-library identities are logged. */
public final class CubeTopTutorAvailabilitySmoke {
    private static final String WILL = "Yawgmoth's Will", TENDRILS = "Tendrils of Agony";
    private static final List<String> TUTORS = List.of("Imperial Seal", "Vampiric Tutor", "Mystical Tutor");
    private static final List<String> CONTROLS = List.of("probe", "ponder", "no-draw", "mana-none", "life-two", "search-blocked");
    private static final List<String> CASES = cases();
    private static List<String> cases() {
        List<String> result = new ArrayList<>();
        for (int tutor = 0; tutor < 3; tutor++) for (String half : List.of("will", "tendrils"))
            for (String control : CONTROLS) result.add(tutor + ":" + half + ":" + control);
        return result;
    }
    private static String tutor(String name) { return TUTORS.get(Integer.parseInt(name.split(":")[0])); }
    private static String missing(String name) { return name.split(":")[1].equals("will") ? WILL : TENDRILS; }
    private static String control(String name) { return name.split(":")[2]; }
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone, boolean tapped) { }
    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone, false));
    }
    private static List<Placement> placements(boolean owner, String name) {
        List<Placement> result = new ArrayList<>();
        if (owner) {
            add(result, 1, tutor(name), ZoneType.Hand);
            add(result, 1, missing(name).equals(WILL) ? TENDRILS : WILL, ZoneType.Hand);
            add(result, 1, "Dark Ritual", ZoneType.Graveyard);
            add(result, 1, "Cabal Ritual", ZoneType.Graveyard);
            add(result, 1, "Black Lotus", ZoneType.Graveyard);
            add(result, 4, "Forest", ZoneType.Graveyard);
            if (!control(name).equals("mana-none")) add(result, 8, "Underground Sea", ZoneType.Battlefield);
            if (!control(name).equals("no-draw")) add(result, 1,
                    control(name).equals("ponder") ? "Ponder" : "Gitaxian Probe", ZoneType.Hand);
            add(result, 1, "Echo of Eons", ZoneType.Library);
            add(result, 4, "Forest", ZoneType.Library);
            add(result, 1, missing(name), ZoneType.Library);
        } else if (control(name).equals("search-blocked")) add(result, 1, "Ashiok, Dream Render", ZoneType.Battlefield);
        add(result, 40-result.size(), "Forest", ZoneType.Library);
        if (result.size()!=40) throw new AssertionError("deck size " + name);
        return result;
    }
    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("top-tutor-availability fixture");
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


    private static Map<String, Object> snapshot(Player player) {
        Map<String, Object> state = new LinkedHashMap<>(); Game game = player.getGame();
        state.put("timestamp", game.getTimestamp());
        state.put("rng", ((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().toString());
        state.put("mana", java.util.stream.StreamSupport.stream(player.getManaPool().spliterator(), false).toList());
        state.put("conversion", java.util.stream.IntStream.range(0, 6)
                .map(i -> player.getManaPool().getPossibleColorUses((byte)(1 << i))).boxed().toList());
        state.put("snow", player.getManaPool().isSnowForColor());
        for (var memory : forge.ai.AiCardMemory.MemorySet.values())
            state.put(memory.name(), forge.ai.AiCardMemory.getMemorySet(player, memory).stream().map(Card::getId).sorted().toList());
        for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile}) {
            state.put(zone.name(), player.getCardsIn(zone).stream().map(c -> c.getId() + ":" + c.getGameTimestamp()
                    + ":" + c.isTapped() + ":" + c.getView().isTapped() + ":" + c.getPlaneswalkerAbilityActivated() + ":" + c.getCounters() + ":" + c.getCastFrom() + ":" + c.getCastSA()).toList());
            state.put(zone.name() + "Abilities", player.getCardsIn(zone).stream().flatMap(c -> c.getSpellAbilities().stream())
                    .map(sa -> sa.getHostCard().getId() + ":" + sa.getActivatingPlayer() + ":" + sa.getTargets() + ":" + System.identityHashCode(sa.getTargets())
                            + ":" + (sa.getManaPart() == null ? "null" : sa.getManaPart().getExpressChoice())).toList());
        }
        state.put("librarySize", player.getCardsIn(ZoneType.Library).size());
        state.put("history", game.getStack().getSpellCardsCastThisTurn().stream().map(c -> c.getId() + ":" + c.getCastFrom()).toList());
        for (Player p : game.getPlayers()) {
            state.put("public-" + p.getId(), p.getLife() + ":" + p.getPreventNextDamageTotalShields());
            state.put("command-" + p.getId(), p.getCardsIn(ZoneType.Command).stream()
                    .map(c -> c.getId() + ":" + c.getGameTimestamp() + ":" + c.getSVars() + ":" + c.getReplacementEffects()).toList());
        }
        return state;
    }
    private static String availability(Player player) {
        List<String> result = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (card.isFaceDown() || !(TUTORS.contains(card.getName()) || List.of("Ponder", "Gitaxian Probe").contains(card.getName()))) continue;
            for (var original : card.getSpellAbilities()) {
                if (!original.isSpell()) continue;
                var sa = original.copy(player);
                boolean legal = forge.ai.CubeComboAi.canPlayNative(sa, player);
                boolean payable = forge.ai.CubeComboAi.canPayCost(sa, player, false);
                result.add(card.getName().replace(' ', '_') + ":legal=" + legal + ":payable=" + payable);
            }
        }
        return String.join(";", result);
    }
    private static String observe(Player player, String key, int step, String previous) {
        Game game = player.getGame();
        if (game.isGameOver() || !game.getStack().isEmpty()
                || !(game.getPhaseHandler().is(PhaseType.MAIN1, player) || game.getPhaseHandler().is(PhaseType.MAIN2, player))) return previous;
        String stamp = "turn=" + game.getPhaseHandler().getTurn() + " phase=" + game.getPhaseHandler().getPhase()
                + " life=" + player.getLife() + " mana=" + player.getManaPool().totalMana()
                + " lands=" + player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && c.isUntapped()).count()
                + " hand=" + String.join(";", player.getCardsIn(ZoneType.Hand).stream().filter(c -> !c.isFaceDown())
                        .map(c -> c.getName().replace(' ', '_')).sorted().toList());
        if (stamp.equals(previous)) return previous;
        var before = snapshot(player); String first = null;
        for (int i = 0; i < 3; i++) {
            String value = availability(player);
            if (first == null) first = value;
            else if (!first.equals(value)) throw new AssertionError("availability query drift " + key);
            if (!before.equals(snapshot(player))) throw new AssertionError("availability mutated state/RNG " + key);
        }
        System.out.println("TOP_TUTOR_AVAILABLE " + key + " step=" + step + " repeats=3 unchanged=true " + stamp + " spells=" + first);
        return stamp;
    }
    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }
    private static void run(boolean improved, boolean observed, int seat, String name) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, name)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : defaultAi(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native top-tutor availability diagnosis").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1-seat);
        player.setLife(control(name).equals("life-two") ? 2 : 20, null);
        opponent.setLife(10, null);
        populate(player, true, name); populate(opponent, false, name);
        for (Card c : opponent.getCardsIn(ZoneType.Battlefield))
            if (c.isPlaneswalker()) c.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
        game.setAge(GameStage.Play);
        int startTurn = seat == 0 ? 1 : 2;
        game.getPhaseHandler().setupFirstTurn(seat == 0 ? player : opponent,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(989800L + 100L * seat + CASES.indexOf(name));
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        System.out.println("TOP_TUTOR_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " observed=" + observed + " ownLife=" + player.getLife() + " registered=40 initialMana=0 startTurn=" + startTurn);
        Set<Integer> ids = new HashSet<>();
        int steps = 0;
        TreeMap<String, Integer> casts = new TreeMap<>();
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= startTurn + 2 && steps < 1000) {
            if (observed) previous = observe(player, key, steps, previous);
            game.getPhaseHandler().mainLoopStep(); steps++;
            for (var item : game.getStack()) if (ids.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() != player) continue;
                String host = sa.getHostCard().getName();
                if (sa.isSpell() && !sa.isCopied()) casts.merge(host, 1, Integer::sum);
                System.out.println("TOP_TUTOR_STACK " + key + " step=" + steps + " source=" + host.replace(' ', '_')
                        + " phase=" + game.getPhaseHandler().getPhase() + " turn=" + game.getPhaseHandler().getTurn()
                        + " spell=" + sa.isSpell() + " copied=" + sa.isCopied() + " castFrom=" + sa.getHostCard().getCastFrom());
            }
        }
        if (steps >= 1000) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("TOP_TUTOR_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                + " tutorCasts=" + casts.getOrDefault(tutor(name), 0)
                + " willCasts=" + casts.getOrDefault(WILL, 0) + " tendrilsCasts=" + casts.getOrDefault(TENDRILS, 0)
                + " ponderCasts=" + casts.getOrDefault("Ponder", 0) + " probeCasts=" + casts.getOrDefault("Gitaxian Probe", 0)
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
                        case "getCurrentVersion" -> "top-tutor-availability-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US"); return null;
            });
            Set<String> cardNames = new java.util.LinkedHashSet<>();
            for (String name : CASES) for (boolean owner : List.of(false, true))
                for (Placement p : placements(owner, name)) cardNames.add(p.name());
            for (String cardName : cardNames) StaticData.instance().attemptToLoadCard(cardName);
            for (String name : CASES) for (int seat = 0; seat < 2; seat++)
                run(args[1].equals("improved"), args[2].equals("observed"), seat, name);
            System.out.println("TOP_TUTOR_SUITE_COMPLETE cases=" + (CASES.size()*2));
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
