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
                    + ":" + c.isTapped() + ":" + c.getView().isTapped() + ":" + c.getCastFrom() + ":" + c.getCastSA()).toList());
            state.put(zone.name() + "Abilities", player.getCardsIn(zone).stream().flatMap(c -> c.getSpellAbilities().stream())
                    .map(sa -> sa.getHostCard().getId() + ":" + sa.getActivatingPlayer() + ":" + sa.getTargets() + ":" + System.identityHashCode(sa.getTargets())
                            + ":" + (sa.getManaPart() == null ? "null" : sa.getManaPart().getExpressChoice())).toList());
        }
        state.put("librarySize", player.getCardsIn(ZoneType.Library).size());
        state.put("history", game.getStack().getSpellCardsCastThisTurn().stream().map(c -> c.getId() + ":" + c.getCastFrom()).toList());
        return state;
    }
    private static void probeInitial(Player player) {
        Map<String, Object> before = snapshot(player); String first = null;
        System.out.println("TOP_KITTEN_QUERY_BEGIN");
        for (int repeat = 0; repeat < 3; repeat++) {
            var action = new forge.ai.CubeTopPlan(player).nextAction();
            String choice = action == null ? "none" : action.getHostCard().getName().replace(' ', '_') + "/" + action.getApi();
            if (first == null) first = choice;
            if (!first.equals(choice)) throw new AssertionError("initial recurrence query is unstable");
            if (!before.equals(snapshot(player))) throw new AssertionError("initial recurrence query changed native state");
        }
        System.out.println("TOP_KITTEN_QUERY_END repeats=3 unchanged=true choice=" + first);
    }
    private static void run(int seat, String engine, String control, boolean candidate) {
        List<Entry> own = new ArrayList<>(), other = new ArrayList<>();
        piece(own, "Displacer Kitten", control.equals("no-kitten"));
        piece(own, "Aetherflux Reservoir", control.equals("no-outlet"));
        String restorer = engine.equals("mystic") ? "Mystic Forge" : engine.startsWith("ring") ? "The One Ring" : "Narset, Parter of Veils";
        piece(own, restorer, control.equals("no-restorer"));
        piece(own, engine.equals("mystic") ? "Sol Ring" : engine.endsWith("birgi") ? "Birgi, God of Storytelling" : "Helm of Awakening", false);
        boolean libraryStart = control.equals("visible-library") || control.startsWith("hidden-library");
        own.add(new Entry(control.equals("no-top") || control.equals("hidden-library-nontop") ? "Forest" : "Sensei's Divining Top",
                control.equals("no-top") ? ZoneType.Exile : libraryStart ? ZoneType.Library : engine.startsWith("ring") ? ZoneType.Hand : ZoneType.Battlefield));
        for (int i = 0; i < 2; i++) own.add(new Entry("Island", ZoneType.Battlefield));
        for (int i = 0; i < (control.equals("short-library") ? 2 : 20); i++) {
            String card = control.equals("hidden-swamp") ? "Swamp" : control.equals("hidden-mountain") ? "Mountain"
                    : control.equals("recovery-decoy") && i == 1 ? "Black Lotus"
                    : control.equals("recovery-decoy") && i == 2 ? "Ancestral Recall" : "Forest";
            own.add(new Entry(card, ZoneType.Library));
        }
        while (own.size() < 40) own.add(new Entry("Forest", ZoneType.Exile));
        String restriction = switch (control) {
            case "draw-cap" -> "Narset, Parter of Veils";
            case "cast-cap" -> "Rule of Law";
            case "no-life-gain" -> "Sulfuric Vortex";
            case "protected-opponent" -> "Leyline of Sanctity";
            case "tax-two", "tax-unsustained" -> "Sphere of Resistance";
            case "tax-three" -> "Trinisphere";
            case "root-maze" -> "Root Maze";
            case "activation-off" -> "Stony Silence";
            default -> null;
        };
        if (restriction != null) other.add(new Entry(restriction, ZoneType.Battlefield));
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
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (control.equals("no-ready-mana") && (card.isLand() || card.getName().equals("Sol Ring"))) card.setTapped(true);
            if (control.equals("purity-tapped-source") && card.getName().equals("Sol Ring")) card.setTapped(true);
            if (control.equals("burden-one") && card.getName().equals("The One Ring")) card.setCounters(CounterEnumType.BURDEN, 1);
            if (control.equals("loyalty-one") && card.getName().equals("Narset, Parter of Veils")) card.setCounters(CounterEnumType.LOYALTY, 1);
        }
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(850913L + seat);
        String key = "seat=" + seat + " engine=" + engine + " control=" + control
                + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION;
        System.out.println("TOP_KITTEN_FIXTURE " + key);
        if (candidate && (control.startsWith("purity") || control.startsWith("hidden-") || control.equals("visible-library")
                || control.equals("recovery-decoy"))) probeInitial(player);
        boolean restrictionLive = restriction != null;
        if (restriction != null) System.out.println("TOP_KITTEN_PUBLIC restriction=" + restriction.replace(' ', '_') + " live=true turn=1");
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
            if (restriction != null) {
                boolean live = opponent.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals(restriction));
                if (live != restrictionLive) {
                    System.out.println("TOP_KITTEN_PUBLIC restriction=" + restriction.replace(' ', '_') + " live=" + live
                            + " turn=" + game.getPhaseHandler().getTurn());
                    restrictionLive = live;
                }
            }
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
            if (args.length > 2 && args[2].equals("privacy")) {
                for (int seat = 0; seat < 2; seat++) for (String engine : List.of("mystic", "ring-birgi", "ring-helm", "narset-birgi", "narset-helm")) {
                    List<String> controls = new ArrayList<>(List.of("purity", "hidden-swamp", "hidden-mountain"));
                    if (engine.equals("mystic")) controls.addAll(List.of("visible-library", "purity-tapped-source"));
                    else controls.addAll(List.of("hidden-library-top", "hidden-library-nontop"));
                    if (engine.startsWith("narset")) controls.add("recovery-decoy");
                    for (String control : controls) { run(seat, engine, control, candidate); cases++; }
                }
                System.out.println("TOP_KITTEN_PRIVACY_COMPLETE cases=" + cases + " candidate=" + candidate);
                return;
            }
            if (args.length > 2 && args[2].equals("boundaries")) {
                for (int seat = 0; seat < 2; seat++) for (String engine : List.of("mystic", "ring-birgi", "ring-helm", "narset-birgi", "narset-helm")) {
                    List<String> controls = new ArrayList<>(List.of("no-top", "short-library", "draw-cap", "cast-cap",
                            "no-life-gain", "protected-opponent", "root-maze", "activation-off"));
                    if (engine.equals("mystic")) controls.addAll(List.of("tax-two", "tax-three", "no-ready-mana"));
                    else controls.add("tax-unsustained");
                    if (engine.startsWith("ring")) controls.add("burden-one");
                    if (engine.startsWith("narset")) controls.add("loyalty-one");
                    for (String control : controls) { run(seat, engine, control, candidate); cases++; }
                }
                System.out.println("TOP_KITTEN_BOUNDARIES_COMPLETE cases=" + cases + " candidate=" + candidate);
                return;
            }
            for (int seat = 0; seat < 2; seat++) for (String engine : List.of("mystic", "ring-birgi", "ring-helm", "narset-birgi", "narset-helm"))
                for (String control : List.of("none", "no-kitten", "no-restorer", "no-outlet")) {
                    run(seat, engine, control, candidate); cases++;
                }
            System.out.println("TOP_KITTEN_COMPLETE cases=" + cases + " candidate=" + candidate);
        } catch (Throwable failure) {failure.printStackTrace();System.exit(1);}
    }
}
