package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CounterType;
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

/** Observation of native decisions from assembled "cheat a bomb" positions.
 *
 * Diagnostic of what Forge's Default AI does from an already-assembled board,
 * not a claim about whole-game strength and not a policy test. No action is
 * supplied by the host; setup is synthetic and exactly registered. Neither arm
 * carries a win assertion: the improved arm is the current cube-combo
 * controller (v50), which has no plan for any of these lines, so it is present
 * only as a do-no-harm reading against Default.
 *
 * Registered in 2026-09-12-bomb-lines-diagnosis/registration.md before any run.
 */
public final class CubeBombLinesSmoke {
    private static final String DEPTHS = "Dark Depths";
    private static final String STAGE = "Thespian's Stage";
    private static final String HEXMAGE = "Vampire Hexmage";
    private static final String SNEAK = "Sneak Attack";
    private static final String BREACH = "Through the Breach";
    private static final String SHOWTELL = "Show and Tell";
    private static final String EMRAKUL = "Emrakul, the Aeons Torn";
    private static final String GRISELBRAND = "Griselbrand";
    private static final String HOOF = "Craterhoof Behemoth";
    private static final String ORDER = "Natural Order";
    private static final String REANIMATE = "Reanimate";
    private static final String ANIMATE = "Animate Dead";
    private static final String ELVES = "Llanowar Elves";
    private static final String MARIT = "Marit Lage";

    /** Prepared positions (a)-(g) of the owner's question. Each is legal and
     * already assembled: the only thing under observation is whether Default
     * takes the line. */
    private static final List<String> LINES = List.of(
            "depths-stage",            // (a) Depths + Stage on battlefield, {2} available
            "depths-hexmage",          // (b) Depths on battlefield, Hexmage in hand
            "depths-hexmage-bf",       // (b') Depths and Hexmage both on battlefield
            "sneak-emrakul",           // (c) Sneak Attack on battlefield, Emrakul in hand, R available
            "breach-emrakul",          // (d) Through the Breach and Emrakul in hand, 5 mana
            "showtell-emrakul",        // (e) Show and Tell and Emrakul in hand, opponent hand empty
            "reanimate-griselbrand",   // (f) Reanimate in hand, Griselbrand in graveyard
            "animate-emrakul",         // (f') Animate Dead in hand, Emrakul in graveyard
            "natural-order-hoof");     // (g) Natural Order in hand, Craterhoof in library, board of Elves

    /** Controls: the same position with the line illegal, blanked or answered
     * by a public permanent. `opp-hand` is not a block - it is the same (e)
     * position with a card in the opponent's hand, because Forge's hidden-origin
     * ChangeZone gate reads every defined player's origin zone. */
    private static final List<String> CONTROLS = List.of(
            "depths-stage:no-mana",           // cannot pay the Stage's {2}
            "depths-stage:wasteland",         // opponent can destroy a nonbasic
            "depths-hexmage:no-mana",         // cannot cast the Hexmage
            "sneak-emrakul:priest",           // Containment Priest exiles the cheated-in bomb
            "sneak-emrakul:karakas",          // Karakas answers the legendary bomb
            "sneak-emrakul:no-mana",          // cannot pay {R}
            "breach-emrakul:priest",
            "showtell-emrakul:opp-hand",      // opponent holds a permanent card
            "showtell-emrakul:priest",
            "reanimate-griselbrand:rip",      // Rest in Peace empties the graveyard
            "animate-emrakul:priest",
            "natural-order-hoof:torpor-orb"); // Craterhoof's ETB pump is blanked

    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone) {}

    private static String base(String control) { return control.split(":")[0]; }
    private static String variant(String control) { return control.contains(":") ? control.split(":", 2)[1] : ""; }

    /** The card whose arrival on the battlefield is the point of the line. */
    private static String payoff(String control) {
        return switch (base(control)) {
            case "depths-stage", "depths-hexmage", "depths-hexmage-bf" -> MARIT;
            case "reanimate-griselbrand" -> GRISELBRAND;
            case "natural-order-hoof" -> HOOF;
            default -> EMRAKUL;
        };
    }

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        String base = base(control), variant = variant(control);
        boolean noMana = variant.equals("no-mana");
        if (owner) {
            switch (base) {
                case "depths-stage" -> {
                    result.add(new Placement(DEPTHS, ZoneType.Battlefield));
                    result.add(new Placement(STAGE, ZoneType.Battlefield));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                case "depths-hexmage" -> {
                    result.add(new Placement(DEPTHS, ZoneType.Battlefield));
                    result.add(new Placement(HEXMAGE, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "depths-hexmage-bf" -> {
                    result.add(new Placement(DEPTHS, ZoneType.Battlefield));
                    result.add(new Placement(HEXMAGE, ZoneType.Battlefield));
                    for (int i = 0; i < 2; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "sneak-emrakul" -> {
                    result.add(new Placement(SNEAK, ZoneType.Battlefield));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "breach-emrakul" -> {
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "showtell-emrakul" -> {
                    result.add(new Placement(SHOWTELL, ZoneType.Hand));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                }
                case "reanimate-griselbrand" -> {
                    result.add(new Placement(REANIMATE, ZoneType.Hand));
                    result.add(new Placement(GRISELBRAND, ZoneType.Graveyard));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "animate-emrakul" -> {
                    result.add(new Placement(ANIMATE, ZoneType.Hand));
                    result.add(new Placement(EMRAKUL, ZoneType.Graveyard));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "natural-order-hoof" -> {
                    result.add(new Placement(ORDER, ZoneType.Hand));
                    result.add(new Placement(HOOF, ZoneType.Library));
                    for (int i = 0; i < 5; i++) result.add(new Placement(ELVES, ZoneType.Battlefield));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                default -> throw new AssertionError("unknown line " + base);
            }
        } else {
            String permanent = switch (variant) {
                case "priest" -> "Containment Priest";
                case "karakas" -> "Karakas";
                case "wasteland" -> "Wasteland";
                case "rip" -> "Rest in Peace";
                case "torpor-orb" -> "Torpor Orb";
                default -> null;
            };
            if (permanent != null) {
                result.add(new Placement(permanent, ZoneType.Battlefield));
                // Karakas and Wasteland are the opponent's own answer: give them nothing
                // else, so the only public decision is whether the answer is used.
                if (variant.equals("priest")) for (int i = 0; i < 2; i++) result.add(new Placement("Plains", ZoneType.Battlefield));
            }
            if (variant.equals("opp-hand")) result.add(new Placement("Grizzly Bears", ZoneType.Hand));
        }
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        return result;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("bomb-lines diagnostic");
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
            // Direct zone placement bypasses GameAction.moveTo, so Dark Depths'
            // etbCounter replacement never runs. Without this the land arrives
            // with zero ice counters and its own state trigger makes Marit Lage
            // for free, which is not the position under observation.
            if (p.name().equals(DEPTHS) && p.zone() == ZoneType.Battlefield)
                card.addCounterInternal(CounterType.getType("ICE"), 10, player, false, null, null);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch " + control + " actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }

    private static String zoneOf(Player player, String name) {
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) if (c.getName().equals(name)) return z.name();
        return "missing";
    }

    private static int countOnBattlefield(Player player, String name) {
        return (int) player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(name)).count();
    }

    /** Lowest ice count across every Dark Depths we control; -1 when we control none. */
    private static int minIce(Player player) {
        int best = -1;
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals(DEPTHS)) {
            int ice = c.getCounters(CounterType.getType("ICE"));
            best = best < 0 ? ice : Math.min(best, ice);
        }
        return best;
    }

    /** Static read of each piece's AI deck hint. `AI:RemoveDeck:All` makes
     * ComputerUtilCard.isCardRemAIDeck true, and AiController.getSpellAbilityToPlay
     * drops every ability of such a host from the AI's own candidate list. */
    private static void printHints(String key, Player player) {
        StringBuilder line = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library))
            for (Card c : player.getCardsIn(z)) {
                if (c.isBasicLand() || !seen.add(c.getName())) continue;
                line.append(' ').append(c.getName().replace(' ', '_')).append("=remAIDeck:")
                        .append(forge.ai.ComputerUtilCard.isCardRemAIDeck(c))
                        .append(",remRandom:").append(c.getRules() != null && c.getRules().getAiHints().getRemRandomDecks());
            }
        System.out.println("BOMB_HINT " + key + line);
    }

    private static void run(boolean improved, int seat, PhaseType phase, String control) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : defaultAi(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native bomb-lines diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(20912 + seat * 100 + control.length());
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("BOMB_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0 payoff=" + payoff(control).replace(' ', '_')
                + " startIce=" + minIce(player));
        printHints(key, player);
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, stageClones = 0, depthsRemovals = 0, hexmageRemovals = 0, sneakActivations = 0;
        int breachCasts = 0, showTellCasts = 0, reanimations = 0, naturalOrders = 0, karakasBounces = 0, wastelands = 0;
        int lowestIce = minIce(player), maxMarit = 0;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps < 600) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            int ice = minIce(player);
            if (ice >= 0) lowestIce = lowestIce < 0 ? ice : Math.min(lowestIce, ice);
            maxMarit = Math.max(maxMarit, countOnBattlefield(player, MARIT));
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                String host = sa.getHostCard().getName();
                boolean ours = sa.getActivatingPlayer() == player;
                if (ours && host.equals(STAGE) && sa.isActivatedAbility()) stageClones++;
                if (ours && host.equals(DEPTHS) && sa.isActivatedAbility()) depthsRemovals++;
                if (ours && host.equals(HEXMAGE) && sa.isActivatedAbility()) hexmageRemovals++;
                if (ours && host.equals(SNEAK) && sa.isActivatedAbility()) sneakActivations++;
                if (ours && host.equals(BREACH) && sa.isSpell()) breachCasts++;
                if (ours && host.equals(SHOWTELL) && sa.isSpell()) showTellCasts++;
                if (ours && (host.equals(REANIMATE) || host.equals(ANIMATE)) && sa.isSpell()) reanimations++;
                if (ours && host.equals(ORDER) && sa.isSpell()) naturalOrders++;
                if (!ours && host.equals("Karakas") && sa.isActivatedAbility()) karakasBounces++;
                if (!ours && host.equals("Wasteland") && sa.isActivatedAbility()) wastelands++;
                System.out.println("BOMB_STACK " + key + " step=" + steps + " source=" + host.replace(' ', '_')
                        + " api=" + sa.getApi() + " ours=" + ours + " trigger=" + sa.isTrigger()
                        + " spell=" + sa.isSpell() + " costs=" + sa.getPayCosts());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                    + " ice=" + ice + " depthsOnBf=" + countOnBattlefield(player, DEPTHS)
                    + " marit=" + countOnBattlefield(player, MARIT)
                    + " payoffZone=" + zoneOf(player, payoff(control))
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("BOMB_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 600) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("BOMB_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " stageClones=" + stageClones + " depthsRemovals=" + depthsRemovals
                + " hexmageRemovals=" + hexmageRemovals + " sneakActivations=" + sneakActivations
                + " breachCasts=" + breachCasts + " showTellCasts=" + showTellCasts
                + " reanimations=" + reanimations + " naturalOrders=" + naturalOrders
                + " karakasBounces=" + karakasBounces + " wastelands=" + wastelands
                + " lowestIce=" + lowestIce + " maxMarit=" + maxMarit
                + " payoffZone=" + zoneOf(player, payoff(control))
                + " emrakulZone=" + zoneOf(player, EMRAKUL)
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
    }

    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "bomb-lines-native-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(DEPTHS, STAGE, HEXMAGE, SNEAK, BREACH, SHOWTELL, EMRAKUL, GRISELBRAND,
                    HOOF, ORDER, REANIMATE, ANIMATE, ELVES, "Containment Priest", "Karakas", "Wasteland",
                    "Rest in Peace", "Torpor Orb", "Grizzly Bears", "Forest", "Island", "Mountain", "Swamp", "Plains"))
                StaticData.instance().attemptToLoadCard(name);
            List<String> cases = args.length > 2 ? switch (args[2]) {
                case "lines" -> LINES;
                case "controls" -> CONTROLS;
                case "depths" -> List.of("depths-stage", "depths-hexmage", "depths-hexmage-bf");
                case "cheat-in" -> List.of("sneak-emrakul", "breach-emrakul", "showtell-emrakul");
                case "reanimator" -> List.of("reanimate-griselbrand", "animate-emrakul");
                case "order" -> List.of("natural-order-hoof");
                default -> { var all = new ArrayList<>(LINES); all.addAll(CONTROLS); yield all; }
            } : LINES;
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : cases) run(args[1].equals("improved"), seat, phase, control);
            System.out.println("BOMB_SUITE_COMPLETE cases=" + (4 * cases.size()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
