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
public final class CubeReservoirPreventionSmoke {
    private static final String BREACH = "Underworld Breach", FRANTIC = "Frantic Search";
    private static final String DRC = "Dragon's Rage Channeler", OUTLET = "Aetherflux Reservoir";
    private static final List<String> ENGINES = List.of("shot", "top", "top-kitten", "witness-snap", "witness-frantic");
    private static final List<String> CONTROLS = List.of("clear", "opponent-chasm", "own-chasm");
    private static final List<String> BOUNDARIES = List.of("shield30", "shield31", "own-shield50", "damage49", "life50", "life51");
    private static final List<String> CASES = java.util.stream.Stream.concat(
            ENGINES.stream().flatMap(e -> CONTROLS.stream().map(c -> e + ":" + c)),
            ENGINES.stream().flatMap(e -> BOUNDARIES.stream().map(c -> e + ":" + c))).toList();
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone, boolean tapped) { }
    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone, false));
    }
    private static List<Placement> own(String name) {
        List<Placement> result = new ArrayList<>();
        String engine = name.split(":")[0];
        add(result, 1, OUTLET, ZoneType.Battlefield);
        switch (engine) {
            case "shot" -> { }
            case "top" -> {
                add(result, 1, "Sensei's Divining Top", ZoneType.Battlefield);
                add(result, 1, "Mystic Forge", ZoneType.Battlefield);
                add(result, 1, "Helm of Awakening", ZoneType.Battlefield);
            }
            case "top-kitten" -> {
                add(result, 1, "Displacer Kitten", ZoneType.Battlefield);
                add(result, 1, "Sensei's Divining Top", ZoneType.Battlefield);
                add(result, 1, "Mystic Forge", ZoneType.Battlefield);
                add(result, 1, "Sol Ring", ZoneType.Battlefield);
            }
            case "witness-snap" -> {
                add(result, 1, "Displacer Kitten", ZoneType.Battlefield);
                add(result, 1, "Eternal Witness", ZoneType.Battlefield);
                add(result, 1, "Snap", ZoneType.Hand);
                add(result, 1, "Lotus Petal", ZoneType.Graveyard);
                add(result, 1, "Elvish Mystic", ZoneType.Battlefield);
                add(result, 2, "Island", ZoneType.Battlefield);
            }
            case "witness-frantic" -> {
                add(result, 1, "Displacer Kitten", ZoneType.Battlefield);
                add(result, 1, "Eternal Witness", ZoneType.Battlefield);
                add(result, 1, "Dark Ritual", ZoneType.Hand);
                add(result, 1, FRANTIC, ZoneType.Graveyard);
                add(result, 1, "Island", ZoneType.Battlefield);
                add(result, 2, "Swamp", ZoneType.Battlefield);
            }
            default -> throw new AssertionError(engine);
        }
        if (name.endsWith(":own-chasm")) add(result, 1, "Glacial Chasm", ZoneType.Battlefield);
        add(result, 20, "Forest", ZoneType.Library);
        add(result, 40-result.size(), "Forest", ZoneType.Exile);
        if (result.size()!=40) throw new AssertionError("own deck size");
        return result;
    }
    private static List<Placement> other(String name) {
        List<Placement> result = new ArrayList<>();
        if (name.endsWith(":opponent-chasm")) add(result, 1, "Glacial Chasm", ZoneType.Battlefield);
        add(result, 40-result.size(), "Forest", ZoneType.Library);
        return result;
    }
    private static List<Placement> placements(boolean owner, String name) {
        return owner ? own(name) : other(name);
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("reservoir-prevention fixture");
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
    private static void probeInitial(Player player, String key, String name) {
        Map<String, Object> before = snapshot(player);
        boolean witness = name.startsWith("witness-");
        var top = new forge.ai.CubeTopPlan(player);
        var kitten = new forge.ai.CubeKittenPlan(player);
        String first = null;
        for (int i = 0; i < 6; i++) {
            var action = witness ? (i < 3 ? new forge.ai.CubeKittenPlan(player) : kitten).nextAction()
                    : (i < 3 ? new forge.ai.CubeTopPlan(player) : top).nextAction();
            String choice = action == null ? "none" : action.getHostCard().getName().replace(' ', '_') + "/" + action.getApi();
            if (i == 0) first = choice;
            else if (!first.equals(choice)) throw new AssertionError("query drift " + key);
            if (!before.equals(snapshot(player))) throw new AssertionError("query mutated native state/RNG " + key);
        }
        System.out.println("RESERVOIR_QUERY " + key + " repeats=6 unchanged=true choice=" + first);
    }
    private static void boundarySetup(Player player, Player opponent, String name) {
        String control = name.split(":")[1];
        Card reservoir = player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> OUTLET.equals(c.getName())).findFirst().orElseThrow();
        if (control.equals("damage49")) for (var sa : reservoir.getSpellAbilities())
            if (sa.getApi() == forge.game.ability.ApiType.DealDamage) sa.putParam("NumDmg", "49");
        if (control.startsWith("life")) opponent.setLife(Integer.parseInt(control.substring(4)), null);
        if (control.contains("shield")) {
            int amount = Integer.parseInt(control.substring(control.indexOf("shield") + 6));
            var shield = forge.game.ability.AbilityFactory.getAbility("AB$ DamagePrevent | Cost$ 0 | Amount$ " + amount + " | ValidTgts$ Player", reservoir);
            shield.setActivatingPlayer(player); shield.getTargets().add(control.startsWith("own-") ? player : opponent);
            new forge.game.ability.effects.DamagePreventEffect().resolve(shield);
            Player target = control.startsWith("own-") ? player : opponent;
            if (target.getPreventNextDamageTotalShields() != amount) throw new AssertionError("shield setup " + name);
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
        Game game = new Match(rules, players, "native reservoir-prevention diagnosis").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, name); populate(opponent, false, name);
        player.setLife(name.startsWith("shot:") ? 55 : 40, null);
        game.setAge(GameStage.Play);
        int startTurn = seat == 0 ? 1 : 2;
        game.getPhaseHandler().setupFirstTurn(seat == 0 ? player : opponent,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        boundarySetup(player, opponent, name);
        BenchRandomAudit.install(989100L + 100L * seat + CASES.indexOf(name));
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        System.out.println("RESERVOIR_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " ownLife=" + player.getLife() + " registered=40 initialMana=0 startTurn=" + startTurn
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size());
        if (improved) probeInitial(player, key, name);
        Set<Integer> ids = new HashSet<>();
        int steps = 0, frantic = 0, escapes = 0, surveilTriggers = 0, shotAbilities = 0;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= startTurn + 1 && steps < 900) {
            game.getPhaseHandler().mainLoopStep(); steps++;
            for (var item : game.getStack()) if (ids.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() != player) continue;
                String host = sa.getHostCard().getName();
                if (sa.isSpell() && host.equals(FRANTIC)) {
                    frantic++; if (sa.isEscape()) escapes++;
                }
                if (!sa.isSpell() && host.equals(OUTLET) && sa.getApi() == forge.game.ability.ApiType.DealDamage) shotAbilities++;
                System.out.println("RESERVOIR_STACK " + key + " step=" + steps + " source="
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
            if (!state.equals(previous)) System.out.println("RESERVOIR_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("RESERVOIR_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                + " shotAbilities=" + shotAbilities + " franticCasts=" + frantic + " escapes=" + escapes + " observedDrcTriggers=" + surveilTriggers
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
                        case "getCurrentVersion" -> "reservoir-prevention-diagnosis-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US"); return null;
            });
            for (String card : List.of(FRANTIC, OUTLET, "Forest", "Island", "Swamp", "Glacial Chasm", "Sensei's Divining Top", "Mystic Forge", "Helm of Awakening", "Displacer Kitten", "Sol Ring", "Eternal Witness", "Snap", "Lotus Petal", "Elvish Mystic", "Dark Ritual"))
                StaticData.instance().attemptToLoadCard(card);
            for (String name : CASES) for (int seat = 0; seat < 2; seat++) run(args[1].equals("improved"), seat, name);
            System.out.println("RESERVOIR_SUITE_COMPLETE cases=" + (CASES.size() * 2));
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
