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
import forge.game.player.GameLossReason;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Candidate CubeCombo-vs-Default native execution observation for assembled,
 * exact 40-card public-state Breach opportunities. This is not a scripted line,
 * benchmark, or claim that every listed variant should win.
 */
public final class CubeBreachCubeSmoke {
    private static boolean expectComplete;
    private static final int STEP_LIMIT = 400;
    private static final List<String> CARD_NAMES = List.of("Underworld Breach", "Lion's Eye Diamond", "Black Lotus",
            "Lotus Petal", "Island", "Mountain", "Brain Freeze", "Forest");

    private static void loadCardsOnce() {
        for (final String name : CARD_NAMES) {
            StaticData.instance().attemptToLoadCard(name);
        }
    }

    private static String candidateVersion() {
        try {
            return (String) forge.ai.CubeComboAi.class.getField("VERSION").get(null);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("candidate VERSION reflection failed", failure);
        }
    }

    private static Card add(final String name, final Player player, final ZoneType zone) {
        final Card card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        card.setSickness(false);
        return card;
    }

    private static boolean has(final Player player, final ZoneType zone, final String name) {
        return player.getCardsIn(zone).stream().anyMatch(card -> card.getName().equals(name));
    }

    private static String names(final Player player, final ZoneType zone) {
        return player.getCardsIn(zone).stream().map(Card::getName).collect(Collectors.joining(","));
    }

    private static int ownTotal(final Player player) {
        int total = 0;
        for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library,
                ZoneType.Exile, ZoneType.Command)) {
            total += player.getCardsIn(zone).size();
        }
        return total;
    }

    private static Game game(final int seat, final boolean candidateController) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int current = 0; current < 2; current++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(candidateController && current == seat
                    ? new forge.ai.LobbyPlayerCubeComboAi("CubeCombo-" + current)
                    : GamePlayerUtil.createAiPlayer("Default-" + current, current, 0, null, "Default")));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        final Game game = new Match(rules, players, "40-card Breach native baseline").createGame();
        game.setAge(GameStage.Play);
        final Player active = game.getPlayers().get(seat);
        game.getPhaseHandler().setupFirstTurn(active,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active));
        return game;
    }

    private static void populate(final Player player, final Player opponent, final String engine, final ZoneType breachZone,
            final ZoneType freezeZone) {
        add(engine, player, ZoneType.Battlefield);
        add("Island", player, ZoneType.Battlefield);
        add("Island", player, ZoneType.Battlefield);
        add("Mountain", player, ZoneType.Battlefield);
        add("Underworld Breach", player, breachZone);
        add("Brain Freeze", player, freezeZone);
        for (int count = 0; count < 12; count++) {
            add("Island", player, ZoneType.Graveyard);
        }
        for (int count = 0; count < 22; count++) {
            add("Island", player, ZoneType.Library);
        }
        for (int count = 0; count < 10; count++) {
            add("Forest", opponent, ZoneType.Graveyard);
        }
        for (int count = 0; count < 30; count++) {
            add("Forest", opponent, ZoneType.Library);
        }
        if (ownTotal(player) != 40 || ownTotal(opponent) != 40) {
            throw new AssertionError("fixture card total must be exact: own=" + ownTotal(player) + " opponent=" + ownTotal(opponent));
        }
    }

    private static void run(final int seat, final String engine, final ZoneType breachZone, final ZoneType freezeZone,
            final boolean candidateController) {
        final Game game = game(seat, candidateController);
        final Player player = game.getPlayers().get(seat);
        final Player opponent = game.getPlayers().get(1 - seat);
        populate(player, opponent, engine, breachZone, freezeZone);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(93100 + seat * 100 + engine.length() * 10 + breachZone.ordinal() + freezeZone.ordinal());
        System.out.println("FIXTURE seat=" + seat + " engine=" + engine + " breach=" + breachZone + " freeze=" + freezeZone
                + " controller=" + (candidateController ? "CubeCombo" : "Default")
                + " policy=" + (candidateController ? candidateVersion() : "Default") + " opponent=Default infoPolicy=CLOSED_REPAIR ownCards="
                + ownTotal(player) + " opponentCards=" + ownTotal(opponent)
                + " publicFuel=" + player.getCardsIn(ZoneType.Graveyard).size());
        final Set<Integer> seenStackIds = new HashSet<>();
        int casts = 0;
        int escapes = 0;
        int copies = 0;
        int engineActivations = 0;
        int escapePaymentCards = 0;
        int lastExile = player.getCardsIn(ZoneType.Exile).size();
        int lastOpponentLibrary = opponent.getCardsIn(ZoneType.Library).size();
                int steps = 0;
        int firstTurnSteps = -1;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 2 && steps < STEP_LIMIT) {
            final int step = ++steps;
            game.getPhaseHandler().mainLoopStep();
            boolean escapedFreezeThisStep = false;
            for (final var stackItem : game.getStack()) {
                final var spell = stackItem.getSpellAbility();
                if (spell.isSpell() && spell.getHostCard().getName().equals("Brain Freeze") && seenStackIds.add(stackItem.getId())) {
                    if (spell.isCopied()) {
                        copies++;
                    } else {
                        casts++;
                        if (spell.isEscape()) {
                            escapes++;
                            escapedFreezeThisStep = true;
                        }
                    }
                    System.out.println("FREEZE_STACK step=" + step + " stackId=" + stackItem.getId() + " copied=" + spell.isCopied()
                            + " escape=" + spell.isEscape() + " targets=" + spell.getTargets());
                }
            }
            if (game.getPhaseHandler().getTurn() == 1) {
                // Native activation history includes mana abilities even when
                // their mana is spent before this mainLoopStep returns.
                engineActivations = Math.max(engineActivations, (int) game.getStack().getAbilityActivatedThisTurn().stream()
                        .filter(sa -> sa.getActivatingPlayer() == player && sa.getHostCard().getName().equals(engine)).count());
            }
            if (expectComplete && (has(player, ZoneType.Exile, engine) || has(player, ZoneType.Exile, "Brain Freeze")))
                throw new AssertionError("combo key exiled as fuel");
            final int exile = player.getCardsIn(ZoneType.Exile).size();
            if (exile > lastExile) {
                final int delta = exile - lastExile;
                if (delta != 3) throw new AssertionError("escape payment did not exile three cards: " + delta);
                if (escapedFreezeThisStep) {
                    escapePaymentCards += delta;
                    System.out.println("ESCAPE_PAYMENT step=" + step + " exiledDelta=" + delta + " totalExiled=" + exile
                            + " exile=" + names(player, ZoneType.Exile));
                } else {
                    System.out.println("EXILE_TRANSITION step=" + step + " exiledDelta=" + delta + " totalExiled=" + exile
                            + " exile=" + names(player, ZoneType.Exile));
                }
            }
            final int opponentLibrary = opponent.getCardsIn(ZoneType.Library).size();
            if (opponentLibrary != lastOpponentLibrary) {
                System.out.println("MILL step=" + step + " opponentLibrary=" + opponentLibrary);
            }
            lastExile = exile;
            lastOpponentLibrary = opponentLibrary;
            if (firstTurnSteps < 0 && game.getPhaseHandler().getTurn() > 1) {
                firstTurnSteps = steps;
            }
        }
        final boolean won = game.isGameOver() && player.hasWon();
        final boolean opponentMilled = opponent.getOutcome() != null && opponent.getOutcome().lossState == GameLossReason.Milled;
        if (firstTurnSteps < 0) {
            firstTurnSteps = steps;
        }
        System.out.println("BREACH_CUBE_RESULT seat=" + seat + " engine=" + engine + " breach=" + breachZone + " freeze=" + freezeZone
                + " steps=" + steps + " firstTurnSteps=" + firstTurnSteps + " won=" + won + " gameOver=" + game.isGameOver() + " casts=" + casts + " escapes=" + escapes
                + " copied=" + copies + " uniqueStackIds=" + seenStackIds.size() + " engineActivations=" + engineActivations
                + " escapePaymentCards=" + escapePaymentCards + " engineInPlay=" + has(player, ZoneType.Battlefield, engine)
                + " engineExiled=" + has(player, ZoneType.Exile, engine)
                + " freezeZone=" + (has(player, ZoneType.Hand, "Brain Freeze") ? "Hand" : has(player, ZoneType.Graveyard, "Brain Freeze")
                        ? "Graveyard" : has(player, ZoneType.Exile, "Brain Freeze") ? "Exile" : "Other")
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size() + " opponentLibrary=" + lastOpponentLibrary
                + " opponentMilled=" + opponentMilled + " nativeDrawLossTerminal=" + (won && opponentMilled)
                + " outcome=" + game.getOutcome());
        if (steps >= STEP_LIMIT) throw new AssertionError("native priority bound exceeded");
        if (expectComplete && (!won || !opponentMilled || engineActivations < 1 || casts < 2 || escapes < 1 || copies < 1))
            throw new AssertionError("expected complete native Breach execution: " + engine + " " + breachZone + " " + freezeZone);
    }

    public static void main(final String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[] { IGuiBase.class },
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "cube-breach-cube-smoke-v1";
                        default -> throw new AssertionError(method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            loadCardsOnce();
            final boolean candidateController = args.length < 2 || !(args[1].equals("default") || args[1].equals("baseline"));
            expectComplete = candidateController && args.length > 3 && args[3].equals("complete");
            for (int seat = 0; seat < 2; seat++) {
                for (final String engine : List.of("Lion's Eye Diamond", "Black Lotus", "Lotus Petal")) {
                    for (final ZoneType breachZone : List.of(ZoneType.Battlefield, ZoneType.Hand)) {
                        for (final ZoneType freezeZone : List.of(ZoneType.Hand, ZoneType.Graveyard)) {
                            run(seat, engine, breachZone, freezeZone, candidateController);
                        }
                    }
                }
            }
            System.out.println("BREACH_CUBE_SUITE_COMPLETE");
        } catch (final Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
