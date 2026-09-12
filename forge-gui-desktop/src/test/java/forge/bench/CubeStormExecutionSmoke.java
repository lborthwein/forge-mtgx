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

import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Public-state, exact-40 candidate Storm fixture; no host-selected actions. */
public final class CubeStormExecutionSmoke {
    private static boolean candidate, expectComplete, forecastSuite, replaySuite;
    private static final boolean FORECAST_STRICT = Boolean.getBoolean("forge.test.requireStormForecast");
    private static final boolean REPLAY_STRICT = Boolean.getBoolean("forge.test.requireStormReplay");
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

    /** v72. The replay suite: five registered boards in their OWN suite, so
     * every pre-existing suite log - the v63 {@code forecast-*} rows included -
     * stays byte-identical and their MUST-NOT-MOVE claim is made by the
     * regression rather than restated here.
     *
     * <ul>
     * <li>{@code replay-lethal} MUST-MOVE: the diagnosis's
     *     {@code storm-16702450-s0} t7 MAIN1 board, reconstructed from that
     *     game's own decision line and {@code zoneChange} stream - opponent at
     *     10 after an ORDINARY Tendrils, Black Lotus + Echo of Eons +
     *     Yawgmoth's Will in hand, Cabal Ritual and Tendrils of Agony in our
     *     graveyard. v63's bound is 3 against a need of 4 and declines
     *     {@code no-lethal-forecast}; v72 counts the hand Black Lotus twice -
     *     cast, cracked, replayed after the Will - reaches 4, and the plan's own
     *     build order takes the line the diagnosis traced by hand.</li>
     * <li>{@code replay-short} MUST-NOT-MOVE: the SAME board with the one
     *     public number the plan discriminates on changed - the opponent at 14,
     *     where v72's bound of 4 is still two storm short of the need of 6.
     *     Both arms and the matched v70 control must decline and the row must
     *     stay field-for-field equal to that control.</li>
     * <li>{@code echo-hold-storm}: Tendrils of Agony in our own graveyard and
     *     Yawgmoth's Will in our hand - the diagnosis's own shape one pass
     *     later. Echo of Eons is castable and the ordinary AI casts it (our
     *     hand is under Forge's own {@code Timetwister} threshold); v72 holds
     *     with {@code reason=engine-in-hand}.</li>
     * <li>{@code echo-hold-breach}: Tendrils of Agony AND Yawgmoth's Will in
     *     our own graveyard with Underworld Breach in play, which grants them
     *     escape. A different clause of the same guard fires, so the reason
     *     token is under test and not only the boolean:
     *     {@code reason=engine-in-play}.</li>
     * <li>{@code echo-release} MUST-NOT-MOVE: the {@code echo-hold-storm} board
     *     with no route piece in our graveyard at all. The guard returns on
     *     pure zone reads before it probes anything, the Echo is cast, and the
     *     row must be field-for-field equal to the matched v70 control.</li>
     * </ul> */
    private static final List<String> REPLAY_CONTROLS = List.of(
            "replay-lethal", "replay-short", "echo-hold-storm", "echo-hold-breach", "echo-release");
    /** The opponent's life is the only thing that separates the first two
     * boards, and it is public. 10 is the diagnosis's own number after the
     * ordinary Tendrils; 14 needs storm 6 against a bound of 4. */
    private static int replayOpponentLife(final String control) {
        return control.equals("replay-lethal") ? 10 : control.equals("replay-short") ? 14 : 20;
    }
    private static final List<String> REPLAY_CARD_NAMES = List.of("Ancient Tomb", "Chrome Mox", "Mana Crypt",
            "Sol Ring", "Cabal Ritual", "Demonic Tutor", "Imperial Seal", "Mystical Tutor", "Timetwister",
            "Echo of Eons", "Underworld Breach", "Swamp");

    private record Placement(String name, ZoneType zone) {}

    private static void put(final List<Placement> into, final int count, final String name, final ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone));
    }

    /** Our own 40 for each replay board. Graveyard Forests are inert filler:
     * a land in the graveyard is named by none of the plan's lists, so it can
     * never move the forecast, and it can never be cast back out. */
    private static List<Placement> replayOwn(final String control) {
        final List<Placement> own = new ArrayList<>();
        switch (control) {
            // The diagnosis's reconstructed t7 board, card for card. Chrome Mox
            // arrives with no imprint and so produces nothing; it is kept
            // because the diagnosis's board held one and because neither it nor
            // Mana Crypt nor Sol Ring is a sacrifice rock, which is exactly the
            // asymmetry v72's printed-property test has to get right.
            case "replay-lethal", "replay-short" -> {
                put(own, 1, "Ancient Tomb", ZoneType.Battlefield);
                put(own, 1, "Underground Sea", ZoneType.Battlefield);
                put(own, 1, "Chrome Mox", ZoneType.Battlefield);
                put(own, 1, "Mana Crypt", ZoneType.Battlefield);
                put(own, 1, "Sol Ring", ZoneType.Battlefield);
                put(own, 1, "Black Lotus", ZoneType.Hand);
                put(own, 1, "Echo of Eons", ZoneType.Hand);
                put(own, 1, "Yawgmoth's Will", ZoneType.Hand);
                put(own, 1, "Cabal Ritual", ZoneType.Graveyard);
                put(own, 1, "Demonic Tutor", ZoneType.Graveyard);
                put(own, 1, "Imperial Seal", ZoneType.Graveyard);
                put(own, 1, "Mystical Tutor", ZoneType.Graveyard);
                put(own, 1, "Tendrils of Agony", ZoneType.Graveyard);
                put(own, 1, "Timetwister", ZoneType.Graveyard);
            }
            case "echo-hold-storm", "echo-release" -> {
                put(own, 3, "Island", ZoneType.Battlefield);
                put(own, 3, "Underground Sea", ZoneType.Battlefield);
                put(own, 1, "Echo of Eons", ZoneType.Hand);
                put(own, 1, "Yawgmoth's Will", ZoneType.Hand);
                if (control.equals("echo-hold-storm")) put(own, 1, "Tendrils of Agony", ZoneType.Graveyard);
                put(own, 4, "Forest", ZoneType.Graveyard);
            }
            case "echo-hold-breach" -> {
                put(own, 3, "Island", ZoneType.Battlefield);
                put(own, 3, "Underground Sea", ZoneType.Battlefield);
                put(own, 1, "Underworld Breach", ZoneType.Battlefield);
                put(own, 1, "Echo of Eons", ZoneType.Hand);
                put(own, 1, "Tendrils of Agony", ZoneType.Graveyard);
                put(own, 1, "Yawgmoth's Will", ZoneType.Graveyard);
                put(own, 6, "Forest", ZoneType.Graveyard);
            }
            default -> throw new AssertionError("unknown replay control " + control);
        }
        while (own.size() < 40) own.add(new Placement("Island", ZoneType.Library));
        if (own.size() != 40) throw new AssertionError("replay board is not 40: " + control + " " + own.size());
        return own;
    }

    /** stderr lines the policy printed during the current replay row. The
     * CUBE_* lines are observability only and are never read by a decision;
     * counting them here is a test-side read of the log this JVM is already
     * writing, and every line still reaches the real stderr unchanged. The
     * wrapper is installed ONLY for the replay suite, so no pre-existing suite
     * log can be reordered by it. */
    private static final List<String> replayHolds = new ArrayList<>();
    private static final List<String> replayDeclines = new ArrayList<>();

    private static final class CountingErr extends PrintStream {
        CountingErr(final PrintStream sink) { super(sink, true); }
        @Override public void println(final String line) {
            if (line != null && line.startsWith("CUBE_STORM_HOLD "))
                replayHolds.add(line.substring("CUBE_STORM_HOLD ".length()).trim());
            if (line != null && line.startsWith("CUBE_PLAN_DECLINE family=storm reason=")) {
                final String reason = line.substring(line.indexOf("reason=") + 7).trim();
                if (!replayDeclines.contains(reason)) replayDeclines.add(reason);
            }
            super.println(line);
        }
    }

    /** The DISTINCT hold lines of the current row. The raw count stays in
     * {@code holdLines}: v72's budget is one line per (turn, phase), so a board
     * the ordinary AI is asked about in both main phases of the same turn
     * prints the same line twice, and both numbers are receipts. */
    private static List<String> distinctHolds() {
        final List<String> distinct = new ArrayList<>();
        for (final String hold : replayHolds) if (!distinct.contains(hold)) distinct.add(hold);
        return distinct;
    }

    private static int countIn(final Player player, final ZoneType zone, final String name) {
        return (int) player.getCardsIn(zone).stream().filter(card -> card.getName().equals(name)).count();
    }

    private static void runReplay(final int seat, final String control) {
        final Game game = game(seat);
        final Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        for (final Placement placement : replayOwn(control)) add(placement.name(), player, placement.zone());
        for (int count = 0; count < 10; count++) add("Forest", opponent, ZoneType.Graveyard);
        for (int count = 0; count < 30; count++) add("Forest", opponent, ZoneType.Library);
        if (total(player) != 40 || total(opponent) != 40)
            throw new AssertionError("exact-40 replay fixture failed own=" + total(player) + " opp=" + total(opponent));
        if (replayOpponentLife(control) != 20) opponent.setLife(replayOpponentLife(control), null);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        final int opponentLifeStart = opponent.getLife();
        BenchRandomAudit.install(97400 + seat * 20 + control.length());
        replayHolds.clear(); replayDeclines.clear();
        // The PLAN's own proposal on the prepared board, read before any game
        // action is taken - the same probe the v63 forecast suite takes, and the
        // receipt that separates "the gate opened" from "the build worked".
        final SpellAbility proposed = new forge.ai.CubeStormPlan(player).nextAction();
        final String proposal = proposed == null ? "none" : proposed.getHostCard().getName().replace(' ', '_');
        final String key = "seat=" + seat + " control=" + control + " arm=" + (candidate ? "improved" : "baseline");
        System.out.println("STORM_REPLAY_PROPOSAL " + key + " policy=" + version()
                + " opponentLifeStart=" + opponentLifeStart + " ownLife=" + player.getLife()
                + " handSize=" + player.getCardsIn(ZoneType.Hand).size()
                + " graveyardSize=" + player.getCardsIn(ZoneType.Graveyard).size()
                + " lotusHand=" + countIn(player, ZoneType.Hand, "Black Lotus")
                + " echoHand=" + countIn(player, ZoneType.Hand, "Echo of Eons")
                + " action=" + proposal);
        System.out.println("STORM_REPLAY_FIXTURE " + key + " controller=" + (candidate ? "CubeCombo" : "Default")
                + " policy=" + version() + " opponent=Default infoPolicy=CLOSED_REPAIR ownCards=" + total(player)
                + " opponentCards=" + total(opponent) + " opponentLife=" + opponentLifeStart);
        final Set<Integer> ids = new HashSet<>();
        int steps = 0, actualCasts = 0, graveyardCasts = 0, tendrilsCasts = 0, echoCasts = 0, lotusCasts = 0, willCasts = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 2 && steps < STEP_LIMIT) {
            final int step = ++steps;
            game.getPhaseHandler().mainLoopStep();
            for (final var item : game.getStack()) {
                final var spell = item.getSpellAbility();
                if (spell.getActivatingPlayer() != player || !spell.isSpell() || spell.isCopied() || !ids.add(item.getId())) continue;
                actualCasts++;
                final Card host = spell.getHostCard();
                final boolean fromGraveyard = host.getCastFrom() != null && host.getCastFrom().getZoneType() == ZoneType.Graveyard;
                if (fromGraveyard) graveyardCasts++;
                switch (host.getName()) {
                    case "Tendrils of Agony" -> tendrilsCasts++;
                    case "Echo of Eons" -> echoCasts++;
                    case "Black Lotus" -> lotusCasts++;
                    case "Yawgmoth's Will" -> willCasts++;
                    default -> { }
                }
                System.out.println("STORM_REPLAY_STACK " + key + " step=" + step + " stackId=" + item.getId()
                        + " card=" + host.getName().replace(' ', '_') + " fromGraveyard=" + fromGraveyard
                        + " storm=" + game.getStack().getSpellsCastThisTurn().size());
            }
        }
        System.out.println("STORM_REPLAY_RESULT " + key + " policy=" + version()
                + " opponentLifeStart=" + opponentLifeStart + " proposal=" + proposal
                + " holdLines=" + replayHolds.size() + " holds=[" + String.join(";", distinctHolds()) + "]"
                + " declines=[" + String.join(";", replayDeclines) + "]"
                + " actualCasts=" + actualCasts + " graveyardCasts=" + graveyardCasts
                + " tendrilsCasts=" + tendrilsCasts + " echoCasts=" + echoCasts
                + " lotusCasts=" + lotusCasts + " willCasts=" + willCasts
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " opponentLife=" + opponent.getLife() + " ownLife=" + player.getLife()
                + " tendrilsGrave=" + countIn(player, ZoneType.Graveyard, "Tendrils of Agony")
                + " graveyardSize=" + player.getCardsIn(ZoneType.Graveyard).size()
                + " steps=" + steps + " outcome=" + game.getOutcome());
        if (steps >= STEP_LIMIT) throw new AssertionError("native priority bound exceeded");
        if (!candidate || !REPLAY_STRICT) return;
        final List<String> distinct = distinctHolds();
        switch (control) {
            // The R1 claim, made concrete: ONE physical Black Lotus, cast TWICE
            // - once from hand and once replayed out of the graveyard the crack
            // step put it in - is the whole difference between bound 3 and
            // bound 4 on this board.
            case "replay-lethal" -> {
                if (proposal.equals("none"))
                    throw new AssertionError("replay-lethal: the plan declined its own reachable lethal");
                if (!player.hasWon() || tendrilsCasts == 0)
                    throw new AssertionError("replay-lethal: the build did not finish (won=" + player.hasWon()
                            + " tendrilsCasts=" + tendrilsCasts + ")");
                if (lotusCasts != 2 || willCasts != 1)
                    throw new AssertionError("replay-lethal: the line was not the plan's own build order (lotusCasts="
                            + lotusCasts + " willCasts=" + willCasts + ")");
                if (echoCasts != 0) throw new AssertionError("replay-lethal: the wheel reached the stack");
            }
            // MUST-NOT-MOVE for the FORECAST: the same board two storm short.
            // C2 does fire here - the route pieces are in our own graveyard and
            // the plan declined - which is the diagnosis's own story, so the
            // forecast claim is made on the proposal and the decline token.
            case "replay-short" -> {
                if (!proposal.equals("none"))
                    throw new AssertionError("replay-short: the plan proposed " + proposal + " two storm short of lethal");
                if (!replayDeclines.contains("no-lethal-forecast"))
                    throw new AssertionError("replay-short: expected no-lethal-forecast, saw " + replayDeclines);
                if (tendrilsCasts != 0 || player.hasWon())
                    throw new AssertionError("replay-short: the refused board finished anyway");
            }
            case "echo-hold-storm", "echo-hold-breach" -> {
                final String reason = control.equals("echo-hold-storm") ? "engine-in-hand" : "engine-in-play";
                final String engine = control.equals("echo-hold-storm") ? "Yawgmoth's_Will" : "Underworld_Breach";
                final String expected = "reason=" + reason + " engine=" + engine + " terminal=Tendrils_of_Agony";
                if (distinct.size() != 1 || !distinct.get(0).equals(expected))
                    throw new AssertionError(control + ": distinct hold lines were " + distinct + ", expected [" + expected + "]");
                if (replayHolds.isEmpty()) throw new AssertionError(control + ": no hold line");
                if (echoCasts != 0) throw new AssertionError(control + ": the wheel was cast anyway");
                if (countIn(player, ZoneType.Graveyard, "Tendrils of Agony") != 1)
                    throw new AssertionError(control + ": the terminal left our graveyard");
            }
            case "echo-release" -> {
                if (!replayHolds.isEmpty()) throw new AssertionError("echo-release: the guard held a board with no route piece");
                if (echoCasts != 1) throw new AssertionError("echo-release: the wheel was not cast (echoCasts=" + echoCasts + ")");
            }
            default -> throw new AssertionError("unknown replay control " + control);
        }
    }

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
            replaySuite = args.length > 3 && args[3].equals("replay");
            if (replaySuite) {
                System.setErr(new CountingErr(System.err));
                for (final String name : REPLAY_CARD_NAMES) StaticData.instance().attemptToLoadCard(name);
                for (int seat = 0; seat < 2; seat++) for (final String control : REPLAY_CONTROLS) runReplay(seat, control);
                System.out.println("STORM_REPLAY_SUITE_COMPLETE cases=" + 2 * REPLAY_CONTROLS.size());
                return;
            }
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
