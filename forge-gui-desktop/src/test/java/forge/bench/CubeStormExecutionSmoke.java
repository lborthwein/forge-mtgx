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
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
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

/** Public-state, exact-40 candidate Storm fixture; no host-selected actions. */
public final class CubeStormExecutionSmoke {
    private static boolean candidate, expectComplete, forecastSuite;
    private static final boolean FORECAST_STRICT = Boolean.getBoolean("forge.test.requireStormForecast");
    private static final int STEP_LIMIT = 400;
    /** v63 C1. Three registered boards for the storm plan's lethal forecast,
     * in their own suite so every pre-existing suite log stays byte-identical.
     *
     * <ul>
     * <li>{@code forecast-short} MUST-MOVE: Will and Tendrils in hand, ONE
     *     cantrip and nothing else - reachable bound 2, so the plan's own
     *     condition needs an opponent at 6 or less and this one is at 20.
     *     v61 casts the cantrip; v63 declines {@code no-lethal-forecast}.</li>
     * <li>{@code forecast-reach} MUST-NOT-MOVE: the identical board with the
     *     opponent at 6, where the bound does meet the condition - the plan
     *     must still build, byte for byte with the matched v61 control.</li>
     * <li>{@code forecast-rich} MUST-NOT-MOVE: the `none` board (bound 12
     *     against a requirement of 9) with the opponent at 20 - the whole
     *     Will/Tendrils execution must still run and win.</li>
     * </ul> */
    private static final List<String> FORECAST_CONTROLS = List.of("forecast-short", "forecast-reach", "forecast-rich");
    /** A board with no rocks, no rituals and one cantrip: the `short` control
     * and the two thin forecast controls. */
    private static boolean thin(final String control) {
        return control.equals("short") || control.equals("forecast-short") || control.equals("forecast-reach");
    }
    private static final List<String> CARD_NAMES = List.of("Yawgmoth's Will", "Tendrils of Agony", "Black Lotus", "Lotus Petal",
            "Mox Jet", "Mox Sapphire", "Underground Sea", "Dark Ritual", "Brainstorm", "Ponder", "Gitaxian Probe", "Duress",
            "Island", "Forest", "Rule of Law", "Null Rod", "Leyline of Sanctity");

    private static void loadCardsOnce() {
        for (final String name : CARD_NAMES) StaticData.instance().attemptToLoadCard(name);
    }
    private static Card add(final String name, final Player player, final ZoneType zone) {
        final Card card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp()); player.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static boolean has(final Player player, final ZoneType zone, final String name) {
        return player.getCardsIn(zone).stream().anyMatch(card -> card.getName().equals(name));
    }
    private static int total(final Player player) {
        int result = 0;
        for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library, ZoneType.Exile)) result += player.getCardsIn(zone).size();
        return result;
    }
    private static String version() {
        try { return (String) forge.ai.CubeComboAi.class.getField("VERSION").get(null); }
        catch (final ReflectiveOperationException failure) { throw new AssertionError("candidate VERSION reflection failed", failure); }
    }
    private static Game game(final int seat) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int current = 0; current < 2; current++) players.add(new RegisteredPlayer(new Deck()).setPlayer(candidate && current == seat
                ? new forge.ai.LobbyPlayerCubeComboAi("CubeCombo-" + current)
                : GamePlayerUtil.createAiPlayer("Default-" + current, current, 0, null, "Default")));
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        final Game game = new Match(rules, players, "40-card Storm native fixture").createGame(); game.setAge(GameStage.Play);
        final Player active = game.getPlayers().get(seat);
        game.getPhaseHandler().setupFirstTurn(active, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active));
        return game;
    }
    private static void populate(final Player player, final Player opponent, final String control) {
        if (control.equals("short")) {
            for (int i = 0; i < 5; i++) add("Island", player, ZoneType.Battlefield);
        } else if (control.equals("forecast-short") || control.equals("forecast-reach")) {
            // Two black sources, so nothing about this board is a mana story:
            // the only thing missing is spells for the storm count itself.
            for (int i = 0; i < 2; i++) add("Underground Sea", player, ZoneType.Battlefield);
            for (int i = 0; i < 3; i++) add("Island", player, ZoneType.Battlefield);
        } else if (control.equals("grave-engine")) {
            add("Island", player, ZoneType.Battlefield); add("Island", player, ZoneType.Battlefield); add("Underground Sea", player, ZoneType.Battlefield);
            add("Mox Sapphire", player, ZoneType.Battlefield); add("Mox Jet", player, ZoneType.Battlefield);
        } else {
            add("Black Lotus", player, ZoneType.Battlefield); add("Lotus Petal", player, ZoneType.Battlefield); add("Mox Jet", player, ZoneType.Battlefield);
            add("Mox Sapphire", player, ZoneType.Battlefield); add("Underground Sea", player, ZoneType.Battlefield);
        }
        add(control.equals("no-will") ? "Duress" : "Yawgmoth's Will", player, ZoneType.Hand);
        add("Tendrils of Agony", player, control.equals("grave-finish") ? ZoneType.Graveyard : ZoneType.Hand); add(thin(control) ? "Island" : "Dark Ritual", player, ZoneType.Hand);
        add(thin(control) ? "Island" : "Brainstorm", player, ZoneType.Hand); add("Ponder", player, ZoneType.Hand);
        for (final String name : List.of("Dark Ritual", "Dark Ritual", "Gitaxian Probe", "Ponder", "Brainstorm", "Lotus Petal", "Mox Jet", "Black Lotus"))
            add(thin(control) ? "Island" : name, player, ZoneType.Graveyard);
        for (int count = 0; count < 22; count++) add("Island", player, ZoneType.Library);
        if (control.equals("rule-law")) add("Rule of Law", opponent, ZoneType.Battlefield);
        if (control.equals("null-rod")) add("Null Rod", opponent, ZoneType.Battlefield);
        if (control.equals("hexproof")) add("Leyline of Sanctity", opponent, ZoneType.Battlefield);
        // One slot per opposing permanent this control puts on the battlefield,
        // so the opponent's 40 stays exact for every control including the new
        // forecast ones. Equivalent to the pre-v63 literal list.
        final int opponentGrave = List.of("rule-law", "null-rod", "hexproof").contains(control) ? 9 : 10;
        for (int count = 0; count < opponentGrave; count++) add("Forest", opponent, ZoneType.Graveyard);
        for (int count = 0; count < 30; count++) add("Forest", opponent, ZoneType.Library);
        if (total(player) != 40 || total(opponent) != 40) throw new AssertionError("exact-40 fixture failed own=" + total(player) + " opp=" + total(opponent));
    }
    private static void run(final int seat, final String control) {
        final Game game = game(seat); final Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, opponent, control);
        // v63 C1: the storm plan's discriminator is the OPPONENT's life total,
        // which is public. `forecast-reach` is the same board as
        // `forecast-short` with that one number changed.
        if (control.equals("forecast-reach")) opponent.setLife(6, null);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        final int opponentLifeStart = opponent.getLife();
        BenchRandomAudit.install(94200 + seat * 20 + control.length());
        // v63 C1. The decision under test is the PLAN's, and C1 deliberately
        // leaves the ordinary AI's own play untouched - on `forecast-short` the
        // ordinary AI goes on to cast the cantrip and even the Tendrils by
        // itself, which is not this gate's business. So the receipt is the
        // plan's own proposal on the prepared board, read the way
        // CubeDoomsdayExecutionSmoke's tighten suite reads its plan, before any
        // game action is taken.
        String proposal = "not-probed";
        if (forecastSuite) {
            final SpellAbility proposed = new forge.ai.CubeStormPlan(player).nextAction();
            proposal = proposed == null ? "none" : proposed.getHostCard().getName().replace(' ', '_');
            System.out.println("STORM_FORECAST_PROPOSAL seat=" + seat + " control=" + control
                    + " arm=" + (candidate ? "improved" : "baseline") + " policy=" + version()
                    + " opponentLifeStart=" + opponentLifeStart + " ownLife=" + player.getLife()
                    + " handSize=" + player.getCardsIn(ZoneType.Hand).size()
                    + " graveyardSize=" + player.getCardsIn(ZoneType.Graveyard).size()
                    + " action=" + proposal);
        }
        System.out.println("STORM_FIXTURE seat=" + seat + " control=" + control + " controller=" + (candidate ? "CubeCombo" : "Default") + " policy=" + version()
                + " opponent=Default infoPolicy=CLOSED_REPAIR ownCards=" + total(player) + " opponentCards=" + total(opponent));
        final Set<Integer> ids = new HashSet<>(); int actualCasts = 0, graveyardCasts = 0, tendrilsCasts = 0, artifactMana = 0, steps = 0, firstTurnSteps = -1;
        int firstTurnCastCount = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 2 && steps < STEP_LIMIT) {
            final int step = ++steps;
            game.getPhaseHandler().mainLoopStep();
            if (control.equals("rule-law") && game.getStack().getSpellsCastThisTurn().stream()
                    .filter(sa -> sa.getActivatingPlayer() == player).count() > 1)
                throw new AssertionError("Rule of Law exceeded per-turn cast limit");
            for (final var item : game.getStack()) {
                final var spell = item.getSpellAbility();
                if (spell.getActivatingPlayer() == player && spell.isSpell() && !spell.isCopied() && ids.add(item.getId())) {
                    actualCasts++;
                    final Card host = spell.getHostCard();
                    final boolean fromGraveyard = host.getCastFrom() != null && host.getCastFrom().getZoneType() == ZoneType.Graveyard;
                    if (fromGraveyard) graveyardCasts++;
                    if (host.getName().equals("Tendrils of Agony")) {
                        tendrilsCasts++;
                        if (control.equals("hexproof") && com.google.common.collect.Iterables.contains(spell.getTargets().getTargetPlayers(), opponent))
                            throw new AssertionError("Tendrils illegally targets hexproof opponent");
                    }
                    System.out.println("STORM_CAST step=" + step + " stackId=" + item.getId() + " card=" + host.getName() + " fromGraveyard=" + fromGraveyard
                            + " targets=" + spell.getTargets() + " storm=" + game.getStack().getSpellsCastThisTurn().size());
                }
            }
            if (game.getPhaseHandler().getTurn() == 1) {
                artifactMana = Math.max(artifactMana, (int) game.getStack().getAbilityActivatedThisTurn().stream()
                    .filter(sa -> sa.getActivatingPlayer() == player && sa.isManaAbility()
                        && sa.getHostCard().isArtifact()).count());
                firstTurnCastCount = actualCasts;
            }
            if (firstTurnSteps < 0 && game.getPhaseHandler().getTurn() > 1) firstTurnSteps = steps;
        }
        if (firstTurnSteps < 0) firstTurnSteps = steps;
        final boolean opponentLifeLoss = opponent.getOutcome() != null && opponent.getOutcome().lossState == GameLossReason.LifeReachedZero;
        if (control.equals("rule-law") && firstTurnCastCount > 1) throw new AssertionError("Rule of Law allowed " + actualCasts + " spells");
        if (control.equals("null-rod") && artifactMana > 0) throw new AssertionError("Null Rod allowed artifact mana activation");
        System.out.println("STORM_RESULT seat=" + seat + " control=" + control + " steps=" + steps + " firstTurnSteps=" + firstTurnSteps
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver() + " actualCasts=" + actualCasts + " graveyardCasts=" + graveyardCasts
                + " tendrilsCasts=" + tendrilsCasts + " artifactMana=" + artifactMana + " opponentLife=" + opponent.getLife()
                + " opponentLifeLossTerminal=" + opponentLifeLoss + " outcome=" + game.getOutcome());
        if (forecastSuite)
            System.out.println("STORM_FORECAST_RESULT seat=" + seat + " control=" + control
                    + " arm=" + (candidate ? "improved" : "baseline") + " policy=" + version()
                    + " opponentLifeStart=" + opponentLifeStart + " actualCasts=" + actualCasts
                    + " tendrilsCasts=" + tendrilsCasts + " graveyardCasts=" + graveyardCasts
                    + " won=" + player.hasWon() + " opponentLife=" + opponent.getLife()
                    + " ownLife=" + player.getLife() + " steps=" + steps + " proposal=" + proposal);
        if (steps >= STEP_LIMIT) throw new AssertionError("native priority bound exceeded");
        if (forecastSuite && candidate && FORECAST_STRICT) {
            if (control.equals("forecast-short") && !proposal.equals("none"))
                throw new AssertionError("forecast-short: the plan proposed " + proposal + " with no reachable lethal");
            if (control.equals("forecast-reach") && proposal.equals("none"))
                throw new AssertionError("forecast-reach: the plan declined a board its own bound reaches");
            if (control.equals("forecast-rich") && (proposal.equals("none") || !player.hasWon() || tendrilsCasts == 0))
                throw new AssertionError("forecast-rich: the unchanged rich board no longer executes");
        }
        if (expectComplete && List.of("none", "grave-engine", "grave-finish").contains(control) && (!player.hasWon() || !opponentLifeLoss || graveyardCasts == 0 || tendrilsCasts == 0))
            throw new AssertionError("expected native Will/Tendrils execution");
    }
    public static void main(final String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[] { IGuiBase.class },
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "cube-storm-execution-smoke-v1";
                        default -> throw new AssertionError(method.getName());
                    }));
            FModel.initialize(null, preferences -> { preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); preferences.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            loadCardsOnce();
            candidate = args.length < 2 || !args[1].equals("baseline");
            expectComplete = candidate && args.length > 3 && args[3].equals("complete");
            forecastSuite = args.length > 3 && args[3].equals("forecast");
            if (forecastSuite) {
                for (int seat = 0; seat < 2; seat++) for (final String control : FORECAST_CONTROLS) run(seat, control);
                System.out.println("STORM_FORECAST_SUITE_COMPLETE cases=" + 2 * FORECAST_CONTROLS.size());
                return;
            }
            for (int seat = 0; seat < 2; seat++) for (final String control : List.of("none", "grave-engine", "grave-finish", "no-will", "short", "rule-law", "null-rod", "hexproof")) run(seat, control);
            System.out.println("STORM_SUITE_COMPLETE");
        } catch (final Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
