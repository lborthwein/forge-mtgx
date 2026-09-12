package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.item.PaperCard;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded native paired Top/Citadel/Reservoir execution fixture.
 * This is a development execution test, not a strength panel: every action is selected by
 * the native controller through mainLoopStep().
 */
public final class CubeTopExecutionSmoke {
    private static final int STEP_LIMIT = 600;
    /** Strict mode for the `drain` suite. Off, the suite records what a policy
     * decides instead of asserting it, which is what the matched-v49 control
     * needs. */
    private static final boolean REQUIRE_DRAIN = Boolean.getBoolean("forge.test.requireTopDrain");
    /** The seven registered `drain` boards, in registration order. */
    private static final List<String> DRAIN_CONTROLS = List.of(
            "crawler-citadel", "crawler-mystic-helm", "crawler-sheoldred",
            "sheoldred-only", "crawler-helm-birgi", "crawler-beyond-budget", "crawler-low-life");
    private static final List<String> DRAIN_POSITIVE = List.of(
            "crawler-citadel", "crawler-mystic-helm", "crawler-sheoldred");
    private static boolean drainSuite;
    private static boolean improved;
    private static PhaseType initialPhase;
    private static String engine = "citadel";
    private static final List<String> CARD_NAMES = List.of(
            "Bolas's Citadel", "Sensei's Divining Top", "Aetherflux Reservoir",
            "Island", "Forest", "Null Rod", "Rule of Law", "Sulfuric Vortex",
            "Leyline of Sanctity", "Platinum Angel", "Narset, Parter of Veils", "Counterspell", "Opt", "Mystic Forge", "Helm of Awakening", "Birgi, God of Storytelling", "Sphere of Resistance", "Dress Down",
            "Psychosis Crawler", "Sheoldred, the Apocalypse");

    private static void loadCardsOnce() {
        for (final String name : CARD_NAMES) {
            StaticData.instance().attemptToLoadCard(name);
        }
    }

    private static Card add(final String name, final Player player, final ZoneType zone) {
        final Card card = Card.fromPaperCard(Objects.requireNonNull(
                FModel.getMagicDb().getCommonCards().getCard(name), name), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        card.setSickness(false);
        if (name.equals("Narset, Parter of Veils")) card.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
        return card;
    }

    private static String names(final Player player, final ZoneType zone) {
        return player.getCardsIn(zone).stream().map(Card::getName).collect(Collectors.joining(","));
    }

    private static boolean has(final Player player, final ZoneType zone, final String name) {
        return player.getCardsIn(zone).stream().anyMatch(card -> card.getName().equals(name));
    }

    private static int total(final Player player) {
        int result = 0;
        for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Hand,
                ZoneType.Graveyard, ZoneType.Library, ZoneType.Exile)) {
            result += player.getCardsIn(zone).size();
        }
        return result;
    }

    private record Placement(String name, ZoneType zone) {}
    private static List<Placement> placements(boolean owner, String control) {
        if (drainSuite) return drainPlacements(owner, control);
        List<Placement> cards = new ArrayList<>();
        if (owner) {
            if (!control.equals("missing-citadel")) cards.add(new Placement(engine.equals("citadel") ? "Bolas's Citadel" : "Mystic Forge", ZoneType.Battlefield));
            if (control.startsWith("both-engines-")) cards.add(new Placement("Bolas's Citadel", ZoneType.Battlefield));
            if (!engine.equals("citadel") && !control.endsWith("no-reducer")) cards.add(new Placement(engine.equals("mystic-helm") ? "Helm of Awakening" : "Birgi, God of Storytelling", ZoneType.Battlefield));
            if (!control.equals("missing-top")) cards.add(new Placement("Sensei's Divining Top",
                    control.equals("top-library") ? ZoneType.Library : control.equals("top-hand") ? ZoneType.Hand : ZoneType.Battlefield));
            if (!control.equals("missing-reservoir")) cards.add(new Placement("Aetherflux Reservoir", ZoneType.Battlefield));
            for (int i = 0; i < 8; i++) cards.add(new Placement("Island",
                    control.equals("no-mana") || control.equals("low-life") && engine.equals("citadel") || !engine.equals("citadel") && i > 0 ? ZoneType.Graveyard : ZoneType.Battlefield));
            int library = control.equals("short-library") ? 3 : control.equals("opponent-spell-history") ? 8 : 20;
            for (int i = 0; i < library; i++) cards.add(new Placement("Forest", ZoneType.Library));
        } else {
            String blocker = switch (control) {
                case "null-rod" -> "Null Rod";
                case "sphere-tax" -> "Sphere of Resistance";
                case "rule-of-law" -> "Rule of Law";
                case "no-life-gain" -> "Sulfuric Vortex";
                case "hexproof" -> "Leyline of Sanctity";
                case "cant-lose" -> "Platinum Angel";
                case "draw-limit" -> "Narset, Parter of Veils";
                case "creature-abilities-disabled", "both-engines-creature-abilities-disabled" -> "Dress Down";
                default -> null;
            };
            if (blocker != null) cards.add(new Placement(blocker, ZoneType.Battlefield));
            if (control.equals("opponent-spell-history")) cards.add(new Placement("Opt", ZoneType.Graveyard));
            if (control.equals("counterspell")) {
                cards.add(new Placement("Counterspell", ZoneType.Hand));
                cards.add(new Placement("Island", ZoneType.Battlefield));
                cards.add(new Placement("Island", ZoneType.Battlefield));
            }
        }
        while (cards.size() < 40) cards.add(new Placement("Forest", owner ? ZoneType.Graveyard : ZoneType.Library));
        if (cards.size() != 40) throw new AssertionError("non-40 fixture");
        return cards;
    }

    /**
     * Boards for the `drain` suite. No Aetherflux Reservoir anywhere: the win,
     * where there is one, has to come from a draw-drain permanent. The three
     * cards in hand are uncastable on every board (no white source) and exist
     * only so Psychosis Crawler, whose power is our hand size, is not a 0/0.
     */
    private static List<Placement> drainPlacements(final boolean owner, final String control) {
        List<Placement> cards = new ArrayList<>();
        if (owner) {
            boolean citadel = !List.of("crawler-mystic-helm", "crawler-helm-birgi").contains(control);
            if (citadel) cards.add(new Placement("Bolas's Citadel", ZoneType.Battlefield));
            if (control.equals("crawler-mystic-helm")) {
                cards.add(new Placement("Mystic Forge", ZoneType.Battlefield));
                cards.add(new Placement("Helm of Awakening", ZoneType.Battlefield));
            }
            if (control.equals("crawler-helm-birgi")) {
                cards.add(new Placement("Helm of Awakening", ZoneType.Battlefield));
                cards.add(new Placement("Birgi, God of Storytelling", ZoneType.Battlefield));
            }
            cards.add(new Placement("Sensei's Divining Top", ZoneType.Battlefield));
            if (!control.equals("sheoldred-only")) cards.add(new Placement("Psychosis Crawler", ZoneType.Battlefield));
            if (List.of("crawler-sheoldred", "sheoldred-only").contains(control))
                cards.add(new Placement("Sheoldred, the Apocalypse", ZoneType.Battlefield));
            for (int i = 0; i < 3; i++) cards.add(new Placement("Leyline of Sanctity", ZoneType.Hand));
            for (int i = 0; i < 8; i++) cards.add(new Placement("Island", citadel || i == 0 ? ZoneType.Battlefield : ZoneType.Graveyard));
            for (int i = 0; i < 20; i++) cards.add(new Placement("Forest", ZoneType.Library));
        }
        while (cards.size() < 40) cards.add(new Placement("Forest", owner ? ZoneType.Graveyard : ZoneType.Library));
        if (cards.size() != 40) throw new AssertionError("non-40 fixture");
        return cards;
    }

    /** One `drain` row. Same rig as run(): the native controller selects every
     * action through mainLoopStep(), and this method only records and checks. */
    private static void drainRun(final int seat, final String control) {
        final Game game = game(seat, control);
        final Player player = game.getPlayers().get(seat);
        final Player opponent = game.getPlayers().get(1 - seat);
        populate(player, opponent, control);
        final int opponentLife = control.equals("crawler-beyond-budget") ? 40 : 8;
        opponent.setLife(opponentLife, null);
        if (control.equals("crawler-low-life")) player.setLife(5, null);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(20812 + seat * 100 + control.length());

        // Public premises, asserted before the controller is ever consulted.
        final int startingLife = player.getLife();
        if (!has(player, ZoneType.Battlefield, "Sensei's Divining Top")
                || has(player, ZoneType.Battlefield, "Aetherflux Reservoir")
                || player.getCardsIn(ZoneType.Library).size() != 20
                || opponent.getLife() != opponentLife
                || startingLife != (control.equals("crawler-low-life") ? 5 : 20)
                || has(player, ZoneType.Battlefield, "Psychosis Crawler") == control.equals("sheoldred-only")
                || has(player, ZoneType.Battlefield, "Sheoldred, the Apocalypse")
                    != List.of("crawler-sheoldred", "sheoldred-only").contains(control)
                || has(player, ZoneType.Battlefield, "Bolas's Citadel")
                    == List.of("crawler-mystic-helm", "crawler-helm-birgi").contains(control))
            throw new AssertionError("drain premise mismatch " + control);

        System.out.println("TOP_DRAIN_FIXTURE seat=" + seat + " control=" + control
                + " controller=" + (improved ? "CubeCombo" : "Default") + " policy=" + forge.ai.CubeComboAi.VERSION
                + " phase=" + initialPhase + " infoPolicy=CLOSED_REPAIR"
                + " ownBattlefield=" + names(player, ZoneType.Battlefield)
                + " ownHand=" + names(player, ZoneType.Hand)
                + " life=" + startingLife + " opponentLife=" + opponent.getLife());

        final Set<Integer> stackIds = new HashSet<>();
        int steps = 0, libraryCasts = 0, topActivations = 0, drainTriggers = 0, sustainTriggers = 0;
        int lifePaid = 0, reservoirActivations = 0;
        String firstFailure = null;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 1 && steps < STEP_LIMIT) {
            final int step = ++steps;
            try {
                game.getPhaseHandler().mainLoopStep();
            } catch (final Throwable failure) {
                firstFailure = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
                System.out.println("NATIVE_FAILURE seat=" + seat + " control=" + control
                        + " step=" + step + " failure=" + firstFailure);
                break;
            }
            for (final var item : game.getStack()) {
                final var ability = item.getSpellAbility();
                if (!stackIds.add(item.getId()) || ability.getActivatingPlayer() != player) continue;
                final Card host = ability.getHostCard();
                if (ability.isSpell()) {
                    if (host.getName().equals("Sensei's Divining Top") && host.getCastFrom() != null
                            && host.getCastFrom().getZoneType() == ZoneType.Library) {
                        libraryCasts++;
                        if (ability.getMayPlay() == null) throw new AssertionError("library recast without a native permission");
                        final boolean paidWithLife = ability.getPayCosts().getCostParts().stream()
                                .anyMatch(part -> part instanceof forge.game.cost.CostPayLife);
                        if (paidWithLife != ability.getMayPlay().getHostCard().getName().equals("Bolas's Citadel"))
                            throw new AssertionError("permission and payment disagree: "
                                    + ability.getMayPlay().getHostCard().getName() + " lifePaid=" + ability.getAmountLifePaid());
                    }
                    lifePaid += Math.max(0, ability.getAmountLifePaid());
                    System.out.println("NATIVE_CAST seat=" + seat + " control=" + control + " step=" + step
                            + " card=" + host.getName()
                            + " castFrom=" + (host.getCastFrom() == null ? "unknown" : host.getCastFrom().getZoneType())
                            + " permission=" + (ability.getMayPlay() == null ? "none" : ability.getMayPlay().getHostCard().getName())
                            + " payCosts=" + ability.getPayCosts() + " lifePaid=" + ability.getAmountLifePaid()
                            + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
                } else if (ability.isActivatedAbility() && ability.getParent() == null) {
                    if (host.getName().equals("Sensei's Divining Top")) topActivations++;
                    if (host.getName().equals("Aetherflux Reservoir")) reservoirActivations++;
                    System.out.println("NATIVE_ACTIVATION seat=" + seat + " control=" + control + " step=" + step
                            + " source=" + host.getName() + " payCosts=" + ability.getPayCosts());
                } else if (ability.isTrigger()) {
                    if (host.getName().equals("Psychosis Crawler")) drainTriggers++;
                    if (host.getName().equals("Sheoldred, the Apocalypse")) sustainTriggers++;
                    System.out.println("NATIVE_TRIGGER seat=" + seat + " control=" + control + " step=" + step
                            + " source=" + host.getName());
                }
            }
        }
        if (firstFailure == null && steps >= STEP_LIMIT) firstFailure = "step budget exhausted";
        if (firstFailure != null) throw new AssertionError(firstFailure);

        final boolean positive = DRAIN_POSITIVE.contains(control);
        if (improved && REQUIRE_DRAIN) {
            if (positive) {
                if (!player.hasWon() || opponent.getLife() > 0)
                    throw new AssertionError("registered drain finish missing " + control
                            + " won=" + player.hasWon() + " opponentLife=" + opponent.getLife());
                if (drainTriggers < opponentLife || libraryCasts < opponentLife - 1)
                    throw new AssertionError("drain loop too short " + control
                            + " drainTriggers=" + drainTriggers + " libraryCasts=" + libraryCasts);
                if (reservoirActivations != 0) throw new AssertionError("no Reservoir is on these boards");
                if (control.equals("crawler-citadel") && lifePaid != libraryCasts)
                    throw new AssertionError("Citadel recasts must each pay one actual life: " + lifePaid + "/" + libraryCasts);
                if (control.equals("crawler-mystic-helm") && lifePaid != 0)
                    throw new AssertionError("Mystic Forge recasts must pay no life: " + lifePaid);
                if (control.equals("crawler-sheoldred")
                        && (sustainTriggers < drainTriggers || player.getLife() <= startingLife))
                    throw new AssertionError("Sheoldred must sustain the life payments: life=" + player.getLife()
                            + " sustainTriggers=" + sustainTriggers);
            } else if (player.hasWon() || libraryCasts != 0) {
                throw new AssertionError("control must not run the loop " + control
                        + " won=" + player.hasWon() + " libraryCasts=" + libraryCasts);
            }
        }
        System.out.println("TOP_DRAIN_RESULT seat=" + seat + " control=" + control + " phase=" + initialPhase
                + " arm=" + (improved ? "improved" : "baseline") + " policy=" + forge.ai.CubeComboAi.VERSION
                + " registered=" + (positive ? "must-move" : "must-not-move")
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver() + " steps=" + steps
                + " libraryCasts=" + libraryCasts + " topActivations=" + topActivations
                + " drainTriggers=" + drainTriggers + " sustainTriggers=" + sustainTriggers
                + " reservoirActivations=" + reservoirActivations + " lifePaid=" + lifePaid
                + " playerLife=" + player.getLife() + " opponentLife=" + opponent.getLife()
                + " firstFailure=" + firstFailure);
    }

    private static Deck registeredDeck(final boolean owner, final String control) {
        Deck deck = new Deck("Top native " + owner + " " + control);
        for (Placement p : placements(owner, control)) deck.getMain().add(p.name(), 1);
        if (deck.getMain().countAll() != 40) throw new AssertionError("non-40 registration");
        return deck;
    }

    private static void clearInitialZones(final Player player) {
        for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard,
                ZoneType.Battlefield, ZoneType.Exile, ZoneType.Flashback, ZoneType.Command,
                ZoneType.Sideboard, ZoneType.Ante)) {
            if (player.getZone(zone) != null) {
                player.getZone(zone).removeAllCards(true);
            }
        }
    }

    private static Game game(final int seat, final String control) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int current = 0; current < 2; current++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(
                    GamePlayerUtil.createAiPlayer("Default-" + current, current, 0, null, "Default")));
        }
        players.set(seat, new RegisteredPlayer(registeredDeck(true, control)).setPlayer(
                improved ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + seat) : GamePlayerUtil.createAiPlayer("Default-" + seat, seat, 0, null, "Default")));
        players.set(1 - seat, new RegisteredPlayer(registeredDeck(false, control)).setPlayer(
                GamePlayerUtil.createAiPlayer("Default-" + (1 - seat), 1 - seat, 0, null, "Default")));
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        final Game result = new Match(rules, players, "native Top Citadel Reservoir fixture").createGame();
        for (final Player player : result.getPlayers()) {
            clearInitialZones(player);
        }
        result.setAge(GameStage.Play);
        final Player active = result.getPlayers().get(seat);
        result.getPhaseHandler().setupFirstTurn(active,
                () -> result.getPhaseHandler().devModeSet(initialPhase, active));
        return result;
    }

    private static void populate(final Player player, final Player opponent, final String control) {
        for (Placement p : placements(true, control)) add(p.name(), player, p.zone());
        for (Placement p : placements(false, control)) add(p.name(), opponent, p.zone());
        if (control.equals("low-life") || control.equals("both-engines-low-life")) player.setLife(1, null);
        if (total(player) != 40 || total(opponent) != 40) throw new AssertionError("setup count mismatch");
        for (Player p : List.of(player, opponent)) {
            java.util.Map<String, Integer> actual = new java.util.TreeMap<>();
            for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard))
                for (Card card : p.getCardsIn(z)) actual.merge(card.getName(), 1, Integer::sum);
            java.util.Map<String, Integer> expected = new java.util.TreeMap<>();
            for (var card : p.getRegisteredPlayer().getDeck().getMain()) expected.merge(card.getKey().getName(), card.getValue(), Integer::sum);
            if (!actual.equals(expected)) throw new AssertionError("registration/zone mismatch");
        }
    }

    private static void run(final int seat, final String control) {
        final Game game = game(seat, control);
        final Player player = game.getPlayers().get(seat);
        final Player opponent = game.getPlayers().get(1 - seat);
        populate(player, opponent, control);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        if (control.equals("opponent-spell-history")) {
            // Synthetic initial history, not an AI-selected action or a replay
            // claim: the opponent previously cast this own graveyard Opt from
            // hand during this turn. All 40 registered cards still match zones.
            Card opt = opponent.getCardsIn(ZoneType.Graveyard).get(0);
            opt.setCastFrom(opponent.getZone(ZoneType.Hand));
            game.getStack().getSpellsCastThisTurn().add(opt.getSpellAbilities().get(0).copy(opponent));
        }
        BenchRandomAudit.install(10811 + seat * 100 + control.length());

        System.out.println("TOP_CITADEL_FIXTURE engine=" + engine + " seat=" + seat + " control=" + control
                + " controller=" + (improved ? "CubeCombo" : "Default") + " policy=" + forge.ai.CubeComboAi.VERSION + " infoPolicy=CLOSED_REPAIR"
                + " ownCards=" + total(player) + " opponentCards=" + total(opponent)
                + " ownBattlefield=" + names(player, ZoneType.Battlefield)
                + " ownHand=" + names(player, ZoneType.Hand)
                + " ownLibrary=" + names(player, ZoneType.Library)
                + " opponentBattlefield=" + names(opponent, ZoneType.Battlefield)
                + " opponentLibrary=" + names(opponent, ZoneType.Library));

        final Set<Integer> stackIds = new HashSet<>();
        String seenGraveyard = "";
        int steps = 0;
        int nativeCasts = 0;
        int libraryCasts = 0;
        int nativeActivations = 0;
        int topActivations = 0;
        int reservoirActivations = 0;
        int lifePaid = 0;
        int reservoirLifePaid = 0;
        int opponentCounters = 0;
        int libraryManaPaid = 0, birgiTriggers = 0;
        int manaPoolDecrease = 0;
        int libraryDelta = 0;
        String firstFailure = null;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 1 && steps < STEP_LIMIT) {
            final int step = ++steps;
            final int lifeBefore = player.getLife();
            final int manaBefore = player.getManaPool().totalMana();
            final int libraryBefore = player.getCardsIn(ZoneType.Library).size();
            try {
                game.getPhaseHandler().mainLoopStep();
            } catch (final Throwable failure) {
                firstFailure = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
                System.out.println("NATIVE_FAILURE seat=" + seat + " control=" + control
                        + " step=" + step + " failure=" + firstFailure);
                break;
            }
            libraryDelta += Math.abs(libraryBefore - player.getCardsIn(ZoneType.Library).size());
            manaPoolDecrease += Math.max(0, manaBefore - player.getManaPool().totalMana());

            for (final var item : game.getStack()) {
                final var ability = item.getSpellAbility();
                if (!stackIds.add(item.getId())) {
                    continue;
                }
                final Card host = ability.getHostCard();
                if (ability.getActivatingPlayer() != player) {
                    if (ability.isSpell() && ability.getActivatingPlayer() == opponent
                            && host.getName().equals("Counterspell") && ability.getApi() == forge.game.ability.ApiType.Counter) {
                        for (var target : ability.getTargets().getTargetSpells()) {
                            if (target.getActivatingPlayer() == player && target.getHostCard().getName().equals("Sensei's Divining Top")) {
                                opponentCounters++;
                                System.out.println("OPPONENT_COUNTER_CAST seat=" + seat + " control=" + control
                                        + " target=own-Top castFrom=" + host.getCastFrom().getZoneType());
                            }
                        }
                    }
                    continue;
                }
                if (ability.isSpell()) {
                    nativeCasts++;
                    if (host.getName().equals("Sensei's Divining Top") && host.getCastFrom() != null && host.getCastFrom().getZoneType() == ZoneType.Library) {
                        libraryCasts++;
                        boolean citadelRoute = engine.equals("citadel") || control.equals("both-engines-no-reducer")
                                || engine.equals("mystic-birgi") && control.equals("both-engines-creature-abilities-disabled");
                        if (citadelRoute) {
                            if (ability.getAmountLifePaid() != 1 || ability.getPayCosts().getCostParts().stream().noneMatch(p -> p instanceof forge.game.cost.CostPayLife)
                                    || ability.getMayPlay() == null || !ability.getMayPlay().getHostCard().getName().equals("Bolas's Citadel"))
                                throw new AssertionError("Citadel recast must pay one actual life");
                        } else if (ability.getAmountLifePaid() != 0 || ability.getMayPlay() == null
                                || !ability.getMayPlay().getHostCard().getName().equals("Mystic Forge"))
                            throw new AssertionError("Mystic Forge must authorize the native mana-paid recast");
                        if (!citadelRoute) {
                            int paid = ability.getPayingMana().size();
                            if (paid != (engine.equals("mystic-birgi") ? 1 : 0))
                                throw new AssertionError("Wrong actual mana receipt for Mystic recast: " + paid);
                            libraryManaPaid += paid;
                        }
                    }
                    System.out.println("NATIVE_CAST seat=" + seat + " control=" + control + " step=" + step
                            + " stackId=" + item.getId() + " card=" + host.getName()
                            + " castFrom=" + (host.getCastFrom() == null ? "unknown" : host.getCastFrom().getZoneType())
                            + " mana=" + (ability.getPayCosts() == null ? "" : ability.getPayCosts().getTotalMana())
                            + " payCosts=" + ability.getPayCosts() + " costParts="
                            + (ability.getPayCosts() == null ? "" : ability.getPayCosts().getCostParts())
                            + " lifePaid=" + ability.getAmountLifePaid()
                            + " lifeBefore=" + lifeBefore + " lifeAfter=" + player.getLife()
                            + " manaBefore=" + manaBefore + " manaAfter=" + player.getManaPool().totalMana()
                            + " targets=" + ability.getTargets());
                    lifePaid += Math.max(0, ability.getAmountLifePaid());
                } else if (ability.isActivatedAbility() && ability.getParent() == null) {
                    final boolean paidActivation = ability.getPayCosts() != null
                            && ability.getPayCosts().getTotalMana().getCMC() > 0;
                    nativeActivations++;
                    if (host.getName().equals("Sensei's Divining Top")) {
                        topActivations++;
                    }
                    if (host.getName().equals("Aetherflux Reservoir")) {
                        reservoirActivations++;
                        reservoirLifePaid += ability.getAmountLifePaid();
                        if (ability.getAmountLifePaid() != 50 || !com.google.common.collect.Iterables.contains(ability.getTargets().getTargetPlayers(), opponent))
                            throw new AssertionError("Reservoir must pay 50 life and target opponent");
                    }
                    System.out.println("NATIVE_ACTIVATION seat=" + seat + " control=" + control + " step=" + step
                            + " stackId=" + item.getId() + " source=" + host.getName()
                            + " paidActivation=" + paidActivation
                            + " mana=" + (ability.getPayCosts() == null ? "" : ability.getPayCosts().getTotalMana())
                            + " payCosts=" + ability.getPayCosts());
                } else if (ability.isTrigger()) {
                    if (host.getName().equals("Birgi, God of Storytelling")) birgiTriggers++;
                    System.out.println("NATIVE_TRIGGER seat=" + seat + " control=" + control + " step=" + step
                            + " stackId=" + item.getId() + " source=" + host.getName());
                } else if (ability.isActivatedAbility()) {
                    System.out.println("NATIVE_ABILITY_STEP seat=" + seat + " control=" + control + " step=" + step
                            + " stackId=" + item.getId() + " source=" + host.getName()
                            + " parent=" + (ability.getParent() == null ? "none" : ability.getParent().getHostCard().getName())
                            + " mana=" + (ability.getPayCosts() == null ? "" : ability.getPayCosts().getTotalMana())
                            + " payCosts=" + ability.getPayCosts());
                }
            }

            final String graveyard = names(player, ZoneType.Graveyard);
            if (!graveyard.equals(seenGraveyard)) {
                System.out.println("NATIVE_ZONE seat=" + seat + " control=" + control + " step=" + step
                        + " life=" + player.getLife() + " librarySize=" + player.getCardsIn(ZoneType.Library).size()
                        + " hand=" + names(player, ZoneType.Hand) + " graveyard=" + graveyard);
                seenGraveyard = graveyard;
            }
        }
        if (firstFailure == null && steps >= STEP_LIMIT) {
            firstFailure = "step budget exhausted";
        } else if (firstFailure == null && !player.hasWon()) {
            firstFailure = control.equals("missing-top") ? "missing-piece control did not win (expected)"
                    : control.equals("null-rod") ? "Null Rod prevented native combo actions"
                    : !has(player, ZoneType.Battlefield, "Sensei's Divining Top")
                    ? "Top left the battlefield without a replacement"
                    : topActivations == 0 ? "Default AI did not activate Top"
                    : reservoirActivations == 0 ? "native Top/Citadel loop stopped before Reservoir threshold"
                    : "native terminal result was not a win";
        }
        if (control.equals("null-rod") && (topActivations > 0 || reservoirActivations > 0)) {
            throw new AssertionError("Null Rod allowed artifact activation top=" + topActivations
                    + " reservoir=" + reservoirActivations);
        }
        if (firstFailure != null && (firstFailure.contains("Exception") || firstFailure.equals("step budget exhausted"))) throw new AssertionError(firstFailure);
        boolean positive = List.of("none", "no-mana", "top-library", "top-hand").contains(control);
        if (!engine.equals("citadel") && control.equals("low-life")) positive = true;
        if (!engine.equals("citadel") && control.equals("both-engines-low-life")) positive = true;
        if (control.equals("both-engines-no-reducer") || control.equals("both-engines-creature-abilities-disabled")) positive = true;
        if (engine.equals("mystic-helm") && control.equals("creature-abilities-disabled")) positive = true;
        if (engine.equals("mystic-birgi") && control.equals("no-mana")) positive = false;
        if (improved && positive && (!player.hasWon() || (libraryCasts < (engine.equals("citadel") ? 8 : 7)) || reservoirActivations != 1 || reservoirLifePaid != 50 || opponent.getLife() > 0))
            throw new AssertionError("Native Top/Citadel/Reservoir finish missing: won=" + player.hasWon() + " libraryCasts=" + libraryCasts);
        if (!positive && player.hasWon()) throw new AssertionError("Unexpected control win " + control);
        if (List.of("rule-of-law", "no-life-gain", "draw-limit", "opponent-spell-history").contains(control)
                && (libraryCasts != 0 || topActivations != 0))
            throw new AssertionError("Must not consume Top for a publicly blocked loop " + control);
        if (improved && control.equals("counterspell") && (opponentCounters != 1
                || !has(opponent, ZoneType.Graveyard, "Counterspell") || !has(player, ZoneType.Graveyard, "Sensei's Divining Top")))
            throw new AssertionError("Native opponent must cast Counterspell targeting our Top and resolve it");
        if (improved && positive && engine.equals("mystic-birgi")
                && !List.of("both-engines-no-reducer", "both-engines-creature-abilities-disabled").contains(control)
                && birgiTriggers < libraryCasts)
            throw new AssertionError("Missing native Birgi refund triggers");
        System.out.println("TOP_CITADEL_RESULT engine=" + engine + " opponentCounters=" + opponentCounters + " phase=" + initialPhase + " reservoirLifePaid=" + reservoirLifePaid + " arm=" + (improved ? "improved" : "baseline") + " libraryCasts=" + libraryCasts + " seat=" + seat + " control=" + control
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " nativeCasts=" + nativeCasts
                + " nativeActivations=" + nativeActivations + " topActivations=" + topActivations
                + " reservoirActivations=" + reservoirActivations + " lifePaid=" + lifePaid + " manaPoolDecrease=" + manaPoolDecrease
                + " libraryDelta=" + libraryDelta + " playerLife=" + player.getLife()
                + " opponentLife=" + opponent.getLife() + " libraryManaPaid=" + libraryManaPaid + " birgiTriggers=" + birgiTriggers
                + " firstFailure=" + firstFailure);
    }

    public static void main(final String[] args) {
        try {
            improved = args[1].equals("improved");
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[] { IGuiBase.class }, (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "top-citadel-native-baseline-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            loadCardsOnce();
            if (args.length > 3 && args[3].equals("drain")) {
                drainSuite = true;
                int cases = 0;
                for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2)) {
                    initialPhase = phase;
                    for (String control : DRAIN_CONTROLS) { drainRun(seat, control); cases++; }
                }
                System.out.println("TOP_DRAIN_SUITE_COMPLETE suite=drain improved=" + improved
                        + " policy=" + forge.ai.CubeComboAi.VERSION + " cases=" + cases);
                return;
            }
            if (args.length > 3 && args[3].equals("engines")) {
                for (String family : List.of("mystic-helm", "mystic-birgi")) {
                    engine = family;
                    for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2)) {
                        initialPhase = phase;
                        for (String control : List.of("none", "no-mana", "top-library", "top-hand", "short-library", "low-life",
                                "missing-citadel", "missing-reservoir", "null-rod", "rule-of-law", "no-life-gain", "draw-limit",
                                "counterspell", "no-reducer", "sphere-tax", "creature-abilities-disabled", "both-engines-low-life",
                                "both-engines-no-reducer", "both-engines-creature-abilities-disabled")) run(seat, control);
                    }
                }
                System.out.println("TOP_ENGINE_SUITE_COMPLETE"); return;
            }
            for (int seat = 0; seat < 2; seat++) {
                for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2)) {
                    initialPhase = phase;
                    for (String control : List.of("none", "no-mana", "top-library", "top-hand", "short-library", "low-life",
                            "missing-top", "missing-citadel", "missing-reservoir", "null-rod", "rule-of-law",
                            "no-life-gain", "hexproof", "cant-lose", "draw-limit", "counterspell", "opponent-spell-history")) run(seat, control);
                }
            }
            System.out.println("TOP_CITADEL_SUITE_COMPLETE");
        } catch (final Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
