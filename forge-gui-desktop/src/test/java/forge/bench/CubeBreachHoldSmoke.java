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
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Observation of native decisions on prepared, exactly registered positions
 * for the v68 Underworld Breach hold (diagnosis item C5).
 *
 * Underworld Breach sacrifices itself at the beginning of the end step, so the
 * ordinary AI's {@code PlayMain1} cast of it on a turn that can take no escape
 * line spends the Breach half of CubeBreachPlan's entry gate for nothing. v68
 * refuses exactly that cast, and nothing else: a released position takes the
 * Default action and prints no line at all.
 *
 * The host supplies the board and nothing else: every cast, cost payment,
 * target, trigger and block is native Forge's. The treated arm is the
 * cube-combo controller; the baseline arm is Forge's Default AI on the
 * identical board. A matched control arm runs this same source against the
 * frozen v67 classes (probe-380), so "v68 moved the line" is separable from
 * "the new fixture rows moved it".
 *
 * These are prepared positions, not natural games: no seed sweep, no win rate
 * and no playing-strength claim. The diagnosis's five storm games are SPENT
 * POSITIONS and none of them is replayed here. The BREACHHOLD_RESULT rows are
 * scored by the run's readout against its registration, not by this JVM. */
public final class CubeBreachHoldSmoke {
    private static final String BREACH = "Underworld Breach";
    private static final String FREEZE = "Brain Freeze";
    private static final String WHEEL = "Wheel of Fortune";
    private static final String TENDRILS = "Tendrils of Agony";
    private static final String PETAL = "Lotus Petal";
    private static final String LED = "Lion's Eye Diamond";

    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** design.md section 8, in the design's order. The HOLD rows are the two
     * v68 refuses; the RELEASE rows are the three it must leave alone, and
     * {@code release-escape-available} is the diagnosis's 63-s1 / 76-s1
     * MUST-NOT-MOVE shape. */
    private static final List<String> HOLD = List.of("hold-freeze-mana", "hold-wheel-fuel");
    private static final List<String> RELEASE = List.of(
            "release-no-terminal", "release-escape-available", "release-route-firing");
    /** The row also observed from the seat that acts after the opponent's
     * first turn. */
    private static final List<String> SEAT1 = List.of("hold-freeze-mana");

    private static int caseIndex(String name) {
        return HOLD.contains(name) ? HOLD.indexOf(name) : 20 + RELEASE.indexOf(name);
    }

    /** The opponent's starting life; 20 unless the row's plan route is a drain.
     * {@code release-route-firing} is v64's wheel-breach-in-hand board verbatim,
     * whose Tendrils terminal needs a reachable life total. */
    private static int opponentLife(String name) {
        return name.equals("release-route-firing") ? 6 : 20;
    }

    private record Placement(String name, ZoneType zone, boolean tapped) {}

    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone, false));
    }

    private static void one(List<Placement> into, String name, ZoneType zone) {
        into.add(new Placement(name, zone, false));
    }

    /** Our own 40. Graveyard Forests are the fuel: a land in the graveyard can
     * be exiled to pay an escape cost and can never be cast back out of it, so
     * the fuel count cannot drift because the ordinary AI replayed a cantrip -
     * and, because Underworld Breach's printed grant says {@code nonLand}, a
     * graveyard Forest is never itself an escape candidate. */
    private static List<Placement> own(String name) {
        List<Placement> own = new ArrayList<>();
        switch (name) {
            // Fuel 2: two short of the hand route's own gate even after two
            // land drops, so the hold can only come from the MANA horizon -
            // Brain Freeze's {1}{U} against an own-visible Island.
            case "hold-freeze-mana" -> {
                one(own, BREACH, ZoneType.Hand);
                one(own, FREEZE, ZoneType.Hand);
                add(own, 2, "Island", ZoneType.Battlefield);
                add(own, 2, "Mountain", ZoneType.Battlefield);
                add(own, 2, "Forest", ZoneType.Graveyard);
            }
            // Fuel 4: within two land drops of the hand route's gate of 6, so
            // the FUEL horizon fires first and the Wheel's own {2}{R} is never
            // priced. Two Mountains pay for the Breach and never for the Wheel.
            case "hold-wheel-fuel" -> {
                one(own, BREACH, ZoneType.Hand);
                one(own, WHEEL, ZoneType.Hand);
                add(own, 2, "Mountain", ZoneType.Battlefield);
                add(own, 4, "Forest", ZoneType.Graveyard);
            }
            // The same fuel as the row above and no terminal own-visible
            // anywhere - not in hand, not in the graveyard, not on the
            // battlefield, and none in the library either, so no draw can
            // create one inside the window.
            case "release-no-terminal" -> {
                one(own, BREACH, ZoneType.Hand);
                add(own, 2, "Mountain", ZoneType.Battlefield);
                add(own, 4, "Forest", ZoneType.Graveyard);
            }
            // The 63-s1 / 76-s1 shape: a Lotus Petal in the graveyard beside
            // three other cards IS an escape line this very turn, so the
            // ordinary Breach is not a wasted card. Every other clause of the
            // hold is satisfied on this board - Brain Freeze is own-visible and
            // castable - so the row moves if and only if clause 6 works.
            case "release-escape-available" -> {
                one(own, BREACH, ZoneType.Hand);
                one(own, FREEZE, ZoneType.Hand);
                add(own, 2, "Island", ZoneType.Battlefield);
                add(own, 2, "Mountain", ZoneType.Battlefield);
                one(own, PETAL, ZoneType.Graveyard);
                add(own, 3, "Forest", ZoneType.Graveyard);
            }
            // v64's wheel-breach-in-hand board, verbatim: CubeBreachPlan's own
            // wheel route orders the Breach first, so the ordinary AI is never
            // asked and the row must stay byte-identical to v67.
            case "release-route-firing" -> {
                one(own, LED, ZoneType.Battlefield);
                add(own, 2, "Mountain", ZoneType.Battlefield);
                add(own, 4, "Swamp", ZoneType.Battlefield);
                one(own, BREACH, ZoneType.Hand);
                one(own, WHEEL, ZoneType.Hand);
                one(own, "Forest", ZoneType.Hand);
                one(own, TENDRILS, ZoneType.Graveyard);
                add(own, 8, "Forest", ZoneType.Graveyard);
                add(own, 21, "Forest", ZoneType.Library);
            }
            default -> throw new AssertionError("unknown case " + name);
        }
        while (own.size() < 40) own.add(new Placement("Forest", ZoneType.Library, false));
        if (own.size() != 40) throw new AssertionError("own board is not 40: " + name + " " + own.size());
        return own;
    }

    /** The opponent's 40, padded in the graveyard so their library size is the
     * only thing the row states. */
    private static List<Placement> other(String name) {
        List<Placement> other = new ArrayList<>();
        add(other, 13, "Forest", ZoneType.Library);
        while (other.size() < 40) other.add(new Placement("Forest", ZoneType.Graveyard, false));
        if (other.size() != 40) throw new AssertionError("opponent board is not 40: " + name);
        return other;
    }

    private static List<Placement> placements(boolean owner, String name) {
        return owner ? own(name) : other(name);
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("breach-hold fixture");
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

    private static int countIn(Player player, ZoneType zone, String name) {
        return (int) player.getCardsIn(zone).stream().filter(c -> c.getName().equals(name)).count();
    }

    /** stderr lines the policy printed during the current row. CUBE_* lines are
     * observability only and are never read by a decision; counting them here
     * is a test-side read of the log this JVM is already writing, and every
     * line still reaches the real stderr unchanged. */
    private static final List<String> declines = new ArrayList<>();
    private static final List<String> holds = new ArrayList<>();

    private static final class CountingErr extends PrintStream {
        CountingErr(PrintStream sink) { super(sink, true); }
        @Override public void println(String line) {
            if (line != null && line.startsWith("CUBE_PLAN_DECLINE family=breach reason=")) {
                String reason = line.substring(line.indexOf("reason=") + 7).trim();
                if (!declines.contains(reason)) declines.add(reason);
            }
            if (line != null && line.startsWith("CUBE_BREACH_HOLD ")) {
                holds.add(line.substring("CUBE_BREACH_HOLD ".length()).trim());
            }
            super.println(line);
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
        Game game = new Match(rules, players, "native breach-hold fixture").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, name);
        populate(opponent, false, name);
        game.setAge(GameStage.Play);
        int startTurn = seat == 0 ? 1 : 2;
        Player goesFirst = seat == 0 ? player : opponent;
        game.getPhaseHandler().setupFirstTurn(goesFirst,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        if (opponentLife(name) != 20) opponent.setLife(opponentLife(name), null);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(96800L + seat * 100L + caseIndex(name));
        declines.clear();
        holds.clear();
        int bound = startTurn + 1;
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        System.out.println("BREACHHOLD_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0 startTurn=" + startTurn
                + " bound=" + bound + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size()
                + " opponentLife=" + opponent.getLife()
                + " breachInHand=" + countIn(player, ZoneType.Hand, BREACH)
                + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size());
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, breachCasts = 0, freezeCasts = 0, wheelCasts = 0, tendrilsCasts = 0, escapes = 0;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 900) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (!sa.isSpell() || sa.getActivatingPlayer() != player) continue;
                String host = sa.getHostCard().getName();
                if (sa.isEscape()) escapes++;
                switch (host) {
                    case BREACH -> breachCasts++;
                    case FREEZE -> freezeCasts++;
                    case WHEEL -> wheelCasts++;
                    case TENDRILS -> tendrilsCasts++;
                    default -> { }
                }
                System.out.println("BREACHHOLD_STACK " + key + " step=" + steps + " source=" + host.replace(' ', '_')
                        + " escape=" + sa.isEscape() + " copied=" + sa.isCopied()
                        + " storm=" + game.getStack().getSpellsCastThisTurn().size());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn()
                    + " phase=" + game.getPhaseHandler().getPhase()
                    + " breachHand=" + countIn(player, ZoneType.Hand, BREACH)
                    + " breachPlay=" + countIn(player, ZoneType.Battlefield, BREACH)
                    + " breachGrave=" + countIn(player, ZoneType.Graveyard, BREACH)
                    + " hand=" + player.getCardsIn(ZoneType.Hand).size()
                    + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size()
                    + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                    + " opponentLife=" + opponent.getLife()
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("BREACHHOLD_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("BREACHHOLD_RESULT " + key + " breachCasts=" + breachCasts
                + " holdLines=" + holds.size() + " holds=[" + String.join(";", holds) + "]"
                + " freezeCasts=" + freezeCasts + " wheelCasts=" + wheelCasts
                + " tendrilsCasts=" + tendrilsCasts + " escapes=" + escapes
                + " breachHand=" + countIn(player, ZoneType.Hand, BREACH)
                + " breachPlay=" + countIn(player, ZoneType.Battlefield, BREACH)
                + " breachGrave=" + countIn(player, ZoneType.Graveyard, BREACH)
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size()
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size()
                + " opponentLife=" + opponent.getLife() + " life=" + player.getLife()
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " declines=[" + String.join(";", declines) + "]"
                + " outcome=" + game.getOutcome());
    }

    private record Row(int seat, String name) {}

    private static List<Row> rows(String suite) {
        List<Row> rows = new ArrayList<>();
        List<String> selected = switch (suite) {
            case "hold" -> HOLD;
            case "release" -> RELEASE;
            default -> { List<String> all = new ArrayList<>(HOLD); all.addAll(RELEASE); yield all; }
        };
        for (String name : selected) rows.add(new Row(0, name));
        if (suite.equals("breachhold")) for (String name : SEAT1) rows.add(new Row(1, name));
        return rows;
    }

    public static void main(String[] args) {
        try {
            System.setErr(new CountingErr(System.err));
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "breach-hold-native-fixture-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String card : List.of(BREACH, FREEZE, WHEEL, TENDRILS, PETAL, LED,
                    "Island", "Mountain", "Swamp", "Forest"))
                StaticData.instance().attemptToLoadCard(card);
            List<Row> rows = rows(args.length > 2 ? args[2] : "breachhold");
            for (Row row : rows) run(args[1].equals("improved"), row.seat(), row.name());
            System.out.println("BREACHHOLD_SUITE_COMPLETE cases=" + rows.size());
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
