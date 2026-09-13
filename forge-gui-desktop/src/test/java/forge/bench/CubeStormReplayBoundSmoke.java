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

/** Native observation only: no forced choices or strategy changes. */
public final class CubeStormReplayBoundSmoke {
    private static final String WILL = "Yawgmoth's Will", TENDRILS = "Tendrils of Agony";
    private static final List<String> CASES = List.of("complete", "one-ritual", "rituals-yard", "no-will",
            "no-sac-rock", "mana-short", "rule-of-law", "rest-in-peace", "library-one", "draw-blocked", "pre-cantrip", "late-ritual", "late-rock", "late-cantrip",
            "no-cantrip", "uncastable-cantrip", "no-threshold", "cost-tax", "null-rod",
            "late-ritual-visible", "late-rock-visible", "pre-cantrip-replay");
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone, boolean tapped) { }
    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone, false));
    }
    private static List<Placement> own(String name) {
        List<Placement> result = new ArrayList<>();
        if (!name.equals("no-will")) add(result, 1, WILL, ZoneType.Hand);
        add(result, 1, TENDRILS, ZoneType.Hand);
        ZoneType ritualZone = name.equals("rituals-yard") ? ZoneType.Graveyard : ZoneType.Hand;
        add(result, 1, "Dark Ritual", ritualZone);
        if (!name.equals("one-ritual")) add(result, 1, "Cabal Ritual", ritualZone);
        if (!name.equals("no-cantrip")) add(result, 1,
                name.startsWith("pre-cantrip") || name.equals("uncastable-cantrip") ? "Ponder" : "Gitaxian Probe", ZoneType.Hand);
        if (!name.equals("no-sac-rock")) {
            add(result, 1, "Lotus Petal", ZoneType.Hand);
            add(result, 1, "Black Lotus", ZoneType.Graveyard);
        }
        if (!name.equals("mana-short")) add(result, 3,
                name.startsWith("pre-cantrip") || name.equals("late-cantrip") ? "Underground Sea" : "Swamp", ZoneType.Battlefield);
        if (!name.equals("no-threshold")) add(result, 6, "Forest", ZoneType.Graveyard);
        if (name.startsWith("late-")) add(result, 1, name.startsWith("late-ritual") ? "Dark Ritual"
                : name.startsWith("late-rock") ? "Lotus Petal" : "Ponder", ZoneType.Library);
        add(result, name.equals("library-one") ? 1 : name.startsWith("late-") ? 19 : 20, "Forest", ZoneType.Library);
        add(result, 40-result.size(), "Forest", ZoneType.Exile);
        if (result.size()!=40) throw new AssertionError("own deck size");
        return result;
    }
    private static List<Placement> other(String name) {
        List<Placement> result = new ArrayList<>();
        if (name.equals("rule-of-law")) add(result, 1, "Rule of Law", ZoneType.Battlefield);
        if (name.equals("rest-in-peace")) add(result, 1, "Rest in Peace", ZoneType.Battlefield);
        if (name.equals("draw-blocked")) add(result, 1, "Narset, Parter of Veils", ZoneType.Battlefield);
        if (name.equals("cost-tax")) add(result, 1, "Thalia, Guardian of Thraben", ZoneType.Battlefield);
        if (name.equals("null-rod")) add(result, 1, "Null Rod", ZoneType.Battlefield);
        add(result, 40-result.size(), "Forest", ZoneType.Library);
        return result;
    }
    private static List<Placement> placements(boolean owner, String name) {
        return owner ? own(name) : other(name);
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("storm-replay-bound fixture");
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
    private static void probeInitial(Player player, String key) {
        try {
            var bound = forge.ai.CubeStormPlan.class.getDeclaredMethod("reachableStormBound", int.class);
            bound.setAccessible(true);
            var persistent = new forge.ai.CubeStormPlan(player);
            var before = snapshot(player);
            String first = null;
            for (int i = 0; i < 6; i++) {
                var plan = i < 3 ? new forge.ai.CubeStormPlan(player) : persistent;
                int forecast = (Integer) bound.invoke(plan, 0);
                var action = plan.nextAction();
                String receipt = "bound=" + forecast + " choice=" + (action == null ? "none" : action.getHostCard().getName().replace(' ', '_'))
                        + " decline=" + (action == null ? plan.declineReason() : "selected");
                if (i == 0) first = receipt;
                else if (!first.equals(receipt)) throw new AssertionError("query drift " + key);
                if (!before.equals(snapshot(player))) throw new AssertionError("query mutated state/RNG " + key);
            }
            System.out.println("STORM_REPLAY_QUERY " + key + " repeats=6 unchanged=true " + first);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static String drainReservation(Player player) {
        var hand = player.getCardsIn(ZoneType.Hand);
        Card tendrils = hand.stream().filter(c -> !c.isFaceDown() && c.getName().equals(TENDRILS)).findFirst().orElse(null);
        Card will = hand.stream().filter(c -> !c.isFaceDown() && c.getName().equals(WILL)).findFirst().orElse(null);
        if (tendrils == null || will == null) return "present=false";
        var first = tendrils.getSpellAbilities().get(0).copy(player);
        var second = will.getSpellAbilities().get(0).copy(player);
        boolean firstLegal = forge.ai.CubeComboAi.canPlayNative(first, player);
        boolean secondLegal = forge.ai.CubeComboAi.canPlayNative(second, player);
        boolean firstPay = forge.ai.CubeComboAi.canPayCost(first, player, false);
        boolean secondPay = forge.ai.CubeComboAi.canPayCost(second, player, false);
        var joint = forge.ai.ComputerUtilMana.calculateManaCost(first.getPayCosts(), first, player, true, 0, false);
        var later = forge.ai.ComputerUtilMana.calculateManaCost(second.getPayCosts(), second, player, true, 0, false);
        joint.addManaCost(later.toManaCost());
        boolean both = forge.ai.CubeComboAi.canPayManaCost(joint, first, player, false)
                && forge.ai.CubeComboAi.canPayManaCost(joint, second, player, false);
        return "present=true firstLegal=" + firstLegal + " secondLegal=" + secondLegal
                + " firstPay=" + firstPay + " secondPay=" + secondPay + " jointPay=" + both
                + " jointCost=" + joint.toManaCost().toString().replace(' ', '_');
    }

    private static String observeLive(Player player, String key, int step, String previous) {
        Game game = player.getGame();
        if (!game.getStack().isEmpty() || game.isGameOver()
                || !(game.getPhaseHandler().is(PhaseType.MAIN1, player) || game.getPhaseHandler().is(PhaseType.MAIN2, player))) return previous;
        try {
            var f = forge.ai.CubeComboPlayerController.class.getDeclaredField("stormPlan"); f.setAccessible(true);
            var plan = (forge.ai.CubeStormPlan) f.get(player.getController());
            var attempted = forge.ai.CubeStormPlan.class.getDeclaredField("attemptedWill"); attempted.setAccessible(true);
            var bound = forge.ai.CubeStormPlan.class.getDeclaredMethod("reachableStormBound", int.class); bound.setAccessible(true);
            int storm = game.getStack().getSpellsCastThisTurn().size();
            StringBuilder visible = new StringBuilder("attempted=" + attempted.getBoolean(plan) + " storm=" + storm);
            for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Graveyard, ZoneType.Battlefield)) {
                visible.append(" ").append(zone).append("=[");
                visible.append(String.join(";", player.getCardsIn(zone).stream().filter(c -> !c.isFaceDown())
                        .map(c -> c.getName().replace(' ', '_')).sorted().toList())).append("]");
            }
            String stamp = visible.toString();
            if (stamp.equals(previous)) return previous;
            var before = snapshot(player); Integer first = null;
            for (int i = 0; i < 3; i++) {
                int value = (Integer) bound.invoke(plan, storm);
                if (first == null) first = value;
                else if (first != value) throw new AssertionError("live bound drift " + key);
                if (!before.equals(snapshot(player))) throw new AssertionError("live bound mutated state/RNG " + key);
            }
            System.out.println("STORM_REPLAY_LIVE_BOUND " + key + " step=" + step + " repeats=3 unchanged=true bound=" + first + " " + stamp);
            String reservation = null;
            for (int i = 0; i < 3; i++) {
                String value = drainReservation(player);
                if (reservation == null) reservation = value;
                else if (!reservation.equals(value)) throw new AssertionError("drain reservation drift " + key);
                if (!before.equals(snapshot(player))) throw new AssertionError("drain reservation mutated state/RNG " + key);
            }
            System.out.println("STORM_DRAIN_RESERVATION " + key + " step=" + step + " repeats=3 unchanged=true "
                    + reservation + " mana=" + player.getManaPool().totalMana()
                    + " black=" + player.getManaPool().getAmountOfColor(forge.card.MagicColor.BLACK)
                    + " untappedLands=" + player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && !c.isTapped()).count());
            return stamp;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
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
        Game game = new Match(rules, players, "native storm-replay-bound diagnosis").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, name); populate(opponent, false, name);
        player.setLife(40, null);
        if (name.startsWith("late-") && !name.endsWith("-visible")) opponent.setLife(22, null);
        if (name.equals("no-cantrip")) opponent.setLife(18, null);
        if (name.equals("pre-cantrip-replay")) opponent.setLife(22, null);
        for (Card c : opponent.getCardsIn(ZoneType.Battlefield))
            if (c.isPlaneswalker()) c.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
        game.setAge(GameStage.Play);
        int startTurn = seat == 0 ? 1 : 2;
        game.getPhaseHandler().setupFirstTurn(seat == 0 ? player : opponent,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(989300L + 100L * seat + CASES.indexOf(name));
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        System.out.println("STORM_REPLAY_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " ownLife=" + player.getLife() + " registered=40 initialMana=0 startTurn=" + startTurn
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size());
        if (improved) probeInitial(player, key);
        Set<Integer> ids = new HashSet<>();
        int steps = 0;
        TreeMap<String, Integer> casts = new TreeMap<>();
        String previous = "", previousBound = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= startTurn + 1 && steps < 900) {
            if (improved) previousBound = observeLive(player, key, steps, previousBound);
            game.getPhaseHandler().mainLoopStep(); steps++;
            for (var item : game.getStack()) if (ids.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() != player) continue;
                String host = sa.getHostCard().getName();
                if (sa.isSpell() && !sa.isCopied()) casts.merge(host, 1, Integer::sum);
                System.out.println("STORM_REPLAY_STACK " + key + " step=" + steps + " source="
                        + host.replace(' ', '_') + " api=" + sa.getApi() + " spell=" + sa.isSpell()
                        + " copied=" + sa.isCopied() + " castFrom=" + sa.getHostCard().getCastFrom()
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
            if (!state.equals(previous)) System.out.println("STORM_REPLAY_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("STORM_REPLAY_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                + " willCasts=" + casts.getOrDefault(WILL, 0) + " darkCasts=" + casts.getOrDefault("Dark Ritual", 0)
                + " cabalCasts=" + casts.getOrDefault("Cabal Ritual", 0) + " probeCasts=" + casts.getOrDefault("Gitaxian Probe", 0)
                + " tendrilsCasts=" + casts.getOrDefault(TENDRILS, 0) + " lotusCasts=" + casts.getOrDefault("Black Lotus", 0)
                + " petalCasts=" + casts.getOrDefault("Lotus Petal", 0)
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
                        case "getCurrentVersion" -> "storm-replay-bound-diagnosis-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US"); return null;
            });
            for (String card : List.of("Ponder", "Underground Sea", "Thalia, Guardian of Thraben", "Null Rod", WILL, TENDRILS, "Dark Ritual", "Cabal Ritual", "Gitaxian Probe", "Lotus Petal", "Black Lotus", "Swamp", "Forest", "Rule of Law", "Rest in Peace", "Narset, Parter of Veils"))
                StaticData.instance().attemptToLoadCard(card);
            for (String name : CASES) for (int seat = 0; seat < 2; seat++) run(args[1].equals("improved"), seat, name);
            System.out.println("STORM_REPLAY_SUITE_COMPLETE cases=" + (CASES.size() * 2));
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
