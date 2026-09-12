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
import forge.player.GamePlayerUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Bounded native baseline, not a scripted line and not a solver. Each fixture has
 * a 25-card library and a legally sufficient line; the selected arm and
 * {@link forge.game.GameRules.AiInformationPolicy#CLOSED_REPAIR} is active.
 */
public final class CubeDoomsdayExecutionSmoke {
    private static boolean improved;
    private static boolean gushRoute;
    private static boolean main2;
    private static String control = "none";

    private static final int STEP_LIMIT = 320;
    private static final java.util.Set<String> loaded = new java.util.HashSet<>();

    private static Card card(final String name, final Player player, final ZoneType zone) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        final Card card = Card.fromPaperCard(Objects.requireNonNull(
                FModel.getMagicDb().getCommonCards().getCard(name), name), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        card.setSickness(false);
        return card;
    }

    private static String cards(final Player player, final ZoneType zone) {
        return player.getCardsIn(zone).stream().map(Card::getName).collect(Collectors.joining(","));
    }

    private static boolean has(final Player player, final ZoneType zone, final String name) {
        return player.getCardsIn(zone).stream().anyMatch(card -> card.getName().equals(name));
    }

    private static Deck registeredDeck() {
        Deck deck = new Deck();
        StaticData.instance().attemptToLoadCard("Thassa's Oracle");
        deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard("Thassa's Oracle"), 1);
        StaticData.instance().attemptToLoadCard("Gush");
        deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard("Gush"), 1);
        return deck;
    }

    private static Game game(final int seat) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int current = 0; current < 2; current++) {
            players.add(new RegisteredPlayer(registeredDeck()).setPlayer(current == seat && improved
                    ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + current)
                    : GamePlayerUtil.createAiPlayer("Default-" + current, current, 0, null, "Default")));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        final Game result = new Match(rules, players, "Default Doomsday native baseline").createGame();
        result.setAge(GameStage.Play);
        final Player active = result.getPlayers().get(seat);
        result.getPhaseHandler().setupFirstTurn(active,
                () -> result.getPhaseHandler().devModeSet(main2 ? PhaseType.MAIN2 : PhaseType.MAIN1, active));
        return result;
    }

    private static void addMana(final Player player) {
        // BBBUUU is available without relying on a mana-pool injection.
        for (int count = 0; count < 3; count++) {
            card(control.equals("one-island") ? "Swamp" : "Underground Sea", player, ZoneType.Battlefield);
        }
        for (int count = 0; count < (gushRoute ? 1 : control.equals("short-mana") ? 2 : 3); count++) {
            card("Island", player, ZoneType.Battlefield);
        }
        if (gushRoute) {
            if (!control.equals("short-mana")) card("Lotus Petal", player, ZoneType.Battlefield);
            if (!control.equals("no-star")) card("Chromatic Star", player, ZoneType.Battlefield);
        }
    }

    private static void settle(final Game game) {
        int depth = 0;
        do {
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (!game.getStack().isEmpty()) {
                game.getStack().resolveStack();
            }
            game.getAction().checkStateEffects(true);
            if (++depth > 64) {
                throw new AssertionError("stack did not settle");
            }
        } while (!game.getStack().isEmpty() || game.getStack().hasSimultaneousStackEntries());
    }

    private static void cast(final Game game, final Player player, final String name) {
        final Card card = player.getCardsIn(ZoneType.Hand).stream().filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("missing hand card " + name));
        final var spell = card.getSpellAbilities().stream().findFirst()
                .orElseThrow(() -> new AssertionError("missing spell ability " + name));
        spell.setActivatingPlayer(player);
        if (name.equals("Ancestral Recall")) {
            spell.getTargets().add(player);
        }
        if (!player.getController().playChosenSpellAbility(spell)) {
            throw new AssertionError("native controller rejected legal scripted witness cast " + name);
        }
        settle(game);
    }

    /** Engine legality witness only: actions are deliberately specified, unlike the baseline. */
    private static void legalityWitness() {
        final Game game = game(0);
        final Player player = game.getPlayers().get(0);
        addMana(player);
        card("Doomsday", player, ZoneType.Hand);
        card("Ancestral Recall", player, ZoneType.Hand);
        card("Thassa's Oracle", player, ZoneType.Hand);
        card("Gitaxian Probe", player, ZoneType.Library);
        card("Street Wraith", player, ZoneType.Library);
        card("Black Lotus", player, ZoneType.Library);
        card("Island", player, ZoneType.Library);
        card("Island", player, ZoneType.Library);
        for (int count = 0; count < 20; count++) {
            card("Forest", player, ZoneType.Library);
        }
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(86199);
        cast(game, player, "Doomsday");
        final int afterDoomsday = player.getCardsIn(ZoneType.Library).size();
        cast(game, player, "Ancestral Recall");
        final int afterAncestral = player.getCardsIn(ZoneType.Library).size();
        cast(game, player, "Thassa's Oracle");
        final boolean won = player.hasWon();
        System.out.println("SCRIPTED_LEGALITY_WITNESS libraryAfterDoomsday=" + afterDoomsday
                + " libraryAfterAncestral=" + afterAncestral + " won=" + won);
        if (afterDoomsday != 5 || afterAncestral != 2 || !won) {
            throw new AssertionError("Doomsday>Ancestral>Oracle legal witness failed");
        }
    }

    private static void run(final int seat, final boolean oracleInHand) {
        final Game game = game(seat);
        final Player player = game.getPlayers().get(seat);
        final Player opponent = game.getPlayers().get(1 - seat);
        addMana(player);
        card("Doomsday", player, ZoneType.Hand);
        if (!gushRoute) card("Ancestral Recall", player, ZoneType.Hand);
        if (oracleInHand && !control.equals("oracle-exiled")) {
            card("Thassa's Oracle", player, ZoneType.Hand);
        }
        // A 25-card library makes Doomsday necessary for the Oracle threshold.
        // With Oracle in hand, any five-card Doomsday pile is sufficient:
        // Ancestral draws three, leaves two, and Oracle's devotion is two.
        // The second variant must search for Oracle and draw it with Ancestral.
        card(gushRoute ? control.equals("gush-exiled") ? "Forest" : "Gush" : "Gitaxian Probe", player, ZoneType.Library);
        if (control.equals("gush-exiled")) card("Gush", player, ZoneType.Exile);
        card("Street Wraith", player, ZoneType.Library);
        card("Black Lotus", player, ZoneType.Library);
        card("Island", player, ZoneType.Library);
        card(oracleInHand || control.equals("oracle-exiled") ? "Island" : "Thassa's Oracle", player, ZoneType.Library);
        if (control.equals("oracle-exiled")) card("Thassa's Oracle", player, ZoneType.Exile);
        for (int count = 0; count < 20; count++) {
            card("Forest", player, ZoneType.Library);
        }
        for (int count = 0; count < 8; count++) {
            card("Forest", opponent, ZoneType.Library);
        }
        if (control.equals("draw-limit")) card("Narset, Parter of Veils", opponent, ZoneType.Battlefield)
                .setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
        if (control.equals("cannot-win")) card("Platinum Angel", opponent, ZoneType.Battlefield);
        if (control.equals("counterspell")) {
            card("Counterspell", opponent, ZoneType.Hand);
            card("Island", opponent, ZoneType.Battlefield);
            card("Island", opponent, ZoneType.Battlefield);
        }
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        if (control.equals("draw-limit") && player.canDrawAmount(3))
            throw new AssertionError("Draw-limit fixture must actually prohibit the three-card draw");
        if (control.equals("cannot-win") && !player.cantWin())
            throw new AssertionError("Win-prohibition fixture must actually prohibit winning");
        final int initialHand = player.getCardsIn(ZoneType.Hand).size();
        BenchRandomAudit.install(86200 + seat + (oracleInHand ? 0 : 10) + (main2 ? 100 : 0));
        System.out.println("FIXTURE seat=" + seat + " variant=" + (oracleInHand ? "oracle-in-hand" : "draw-to-oracle")
                + " policy=CLOSED_REPAIR firstTurn=true library=25 initialManaSources=" + player.getCardsIn(ZoneType.Battlefield).size()
                + " candidatePlan=" + (gushRoute ? "Doomsday>Star>Gush>Oracle" : "Doomsday>Ancestral>Oracle") + " control=" + control);
        System.out.println("INITIAL hand=" + cards(player, ZoneType.Hand) + " library=" + cards(player, ZoneType.Library));
        boolean sawDoomsday = false;
        boolean sawPile = false;
        boolean sawAncestral = false;
        boolean sawOracle = false;
        boolean sawAlternateGush = false;
        int draws = 0;
        String firstFailure = null;
        int steps = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() == 1 && steps < STEP_LIMIT) {
            final int step = ++steps;
            final int handBefore = player.getCardsIn(ZoneType.Hand).size();
            final String libraryBefore = cards(player, ZoneType.Library);
            game.getPhaseHandler().mainLoopStep();
            final int handAfter = player.getCardsIn(ZoneType.Hand).size();
            draws += Math.max(0, handAfter - handBefore);
            final boolean nowDoomsday = has(player, ZoneType.Graveyard, "Doomsday");
            final boolean nowAncestral = has(player, ZoneType.Graveyard, gushRoute ? "Gush" : "Ancestral Recall");
            if (gushRoute && !game.getStack().isEmpty()) {
                var top = game.getStack().peekAbility();
                if (top.getHostCard().getName().equals("Gush")) {
                    sawAlternateGush |= top.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof forge.game.cost.CostReturn);
                    if (improved && (player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getType().hasSubtype("Island")).count() != 2
                            || player.getManaPool().getAmountOfColor(forge.card.MagicColor.BLUE) != 2))
                        throw new AssertionError("Gush must return two Islands while preserving floating UU");
                }
            }
            final boolean nowOracle = has(player, ZoneType.Battlefield, "Thassa's Oracle");
            final String libraryAfter = cards(player, ZoneType.Library);
            if (nowDoomsday != sawDoomsday || nowAncestral != sawAncestral || nowOracle != sawOracle
                    || !libraryBefore.equals(libraryAfter) || handBefore != handAfter) {
                System.out.println("EVENT step=" + step + " phase=" + game.getPhaseHandler().getPhase()
                        + " stack=" + game.getStack() + " hand=" + cards(player, ZoneType.Hand)
                        + " library=" + libraryAfter + " graveyard=" + cards(player, ZoneType.Graveyard));
            }
            sawDoomsday |= nowDoomsday;
            sawAncestral |= nowAncestral;
            sawOracle |= nowOracle;
            sawPile |= sawDoomsday && player.getCardsIn(ZoneType.Library).size() == 5;
            if (improved && !oracleInHand && nowDoomsday && !nowAncestral && game.getStack().isEmpty()
                    && player.getCardsIn(ZoneType.Library).size() == 5
                    && !player.getCardsIn(ZoneType.Library).get(0).getName().equals(gushRoute ? "Gush" : "Thassa's Oracle"))
                throw new AssertionError("Doomsday must actually order the selected draw route");
        }
        if (firstFailure == null && steps >= STEP_LIMIT) {
            firstFailure = "first-turn native step budget exhausted";
        }
        if (firstFailure == null && !player.hasWon()) {
            firstFailure = !sawDoomsday ? "first turn ended without casting Doomsday from the 25-card opportunity" : !sawPile
                    ? "Doomsday did not leave its five-card pile" : !sawAncestral
                    ? "Default AI did not cast Ancestral Recall after pile" : !sawOracle
                    ? "Default AI did not cast or resolve Thassa's Oracle" : "terminal result was not a win";
        }
        boolean oracleWin = player.getOutcome() != null && "Thassa's Oracle".equals(player.getOutcome().altWinSourceName);
        System.out.println("RESULT improved=" + improved + " control=" + control + " initialPhase=" + (main2 ? "MAIN2" : "MAIN1")
                + " seat=" + seat + " variant=" + (oracleInHand ? "oracle-in-hand" : "draw-to-oracle")
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver() + " doomsdayCast=" + sawDoomsday
                + " fiveCardPile=" + sawPile + " drawSpell=" + (gushRoute ? "Gush" : "Recall") + " drawSpellCast=" + sawAncestral
                + " ancestralCast=" + (!gushRoute && sawAncestral) + " alternateGush=" + sawAlternateGush
                + " oracleEnteredBattlefield=" + sawOracle + " oracleWin=" + oracleWin
                + " observedPositiveHandDelta=" + draws + " initialHand=" + initialHand + " steps=" + steps
                + " incidentalNonDoomsdayWin=" + (player.hasWon() && !sawDoomsday)
                + " firstFailure=" + (firstFailure == null ? "none" : firstFailure));
        if (improved && control.equals("none") && !(player.hasWon() && sawDoomsday && sawPile && sawAncestral && sawOracle && oracleWin))
            throw new AssertionError("Native Doomsday plan failed: " + firstFailure);
        if (improved && gushRoute && control.equals("none") && (!sawAlternateGush || !has(player, ZoneType.Graveyard, "Chromatic Star")))
            throw new AssertionError("Star/Gush route must use the real mana/return-cost line");
        if (improved && !control.equals("none") && !control.equals("counterspell") && sawDoomsday)
            throw new AssertionError("Doomsday plan committed despite " + control);
        if (control.equals("counterspell") && improved && (!has(opponent, ZoneType.Graveyard, "Counterspell") || player.hasWon()))
            throw new AssertionError("Native opponent must actually interact and stop this unprotected line");
    }

    private record Placement(String name, ZoneType zone, boolean tapped) { }

    private static List<Placement> coldLayout(String route, String blocker, boolean reverse) {
        List<Placement> out = new ArrayList<>();
        for (int i = 0; i < (route.equals("recall") ? 3 : 2); i++)
            out.add(new Placement(blocker.equals("no-blue") ? "Swamp" : "Island", ZoneType.Battlefield,
                    route.equals("star") && i == 0));
        if (route.equals("star")) {
            if (!blocker.equals("no-star")) out.add(new Placement("Chromatic Star", ZoneType.Battlefield, false));
            if (!blocker.equals("short-mana")) out.add(new Placement("Lotus Petal", ZoneType.Battlefield, false));
        }
        if (route.equals("gush")) out.add(new Placement("Gush", ZoneType.Hand, false));
        if (route.equals("recall")) out.add(new Placement("Ancestral Recall", ZoneType.Hand, false));
        if (route.equals("oracle")) out.add(new Placement("Thassa's Oracle", ZoneType.Hand, false));
        int librarySize = route.equals("oracle") ? 2 : route.equals("gush") ? 4 : 5;
        for (int i = 0; i < librarySize; i++) {
            String name = "Forest";
            if (route.equals("star") && i == (reverse ? 4 : 0)) name = "Gush";
            if (!route.equals("oracle") && i == (route.equals("star") ? 1 : 0)) name = "Thassa's Oracle";
            out.add(new Placement(name, ZoneType.Library, false));
        }
        for (int i = 0; i < out.size(); i++) {
            Placement p = out.get(i);
            if (blocker.equals("oracle-exiled") && p.name().equals("Thassa's Oracle")
                    || blocker.equals("gush-exiled") && p.name().equals("Gush")
                    || blocker.equals("oracle-graveyard") && p.name().equals("Thassa's Oracle")) {
                out.set(i, new Placement("Forest", p.zone(), false));
                out.add(new Placement(p.name(), blocker.equals("oracle-graveyard") ? ZoneType.Graveyard : ZoneType.Exile, false));
                break;
            }
        }
        out.add(new Placement("Doomsday", ZoneType.Graveyard, false));
        while (out.size() < 40) out.add(new Placement("Forest", ZoneType.Exile, false));
        return out;
    }

    private static Game fixtureGame(List<Placement> own, List<Placement> other, int seat) {
        return fixtureGame(own, other, seat, null);
    }

    /** Deterministic adversarial opponent for removal tests only. The test
     * chooses a response; Forge still grants priority, pays costs, checks the
     * target and resolves the spell. This is NOT Default opponent behavior. */
    private static forge.ai.LobbyPlayerAi removalOpponent(String responseTo) {
        var lobby = new forge.ai.LobbyPlayerAi("Scripted-removal", null) {
            @Override public Player createIngamePlayer(Game game, int id) {
                Player responder = new Player(getName(), game, id);
                responder.setFirstController(new forge.ai.PlayerControllerAi(game, responder, this) {
                    @Override public List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
                        if (game.getStack().isEmpty() || !game.getStack().peekAbility().getHostCard().getName().equals(responseTo)) return null;
                        for (Card source : responder.getCardsIn(ZoneType.Hand)) if (source.getName().equals("Dismember")) {
                            for (var original : source.getSpellAbilities()) {
                                var removal = original.copy(responder);
                                for (Player opponent : responder.getOpponents()) for (Card target : opponent.getCardsIn(ZoneType.Battlefield))
                                    if (target.getName().equals("Jace, Vryn's Prodigy") && removal.canTarget(target)) {
                                        removal.resetTargets(); removal.getTargets().add(target);
                                        if (removal.isTargetNumberValid() && forge.ai.CubeComboAi.canPlayNative(removal, responder)
                                                && forge.ai.CubeComboAi.canPayCost(removal, responder, false)) return List.of(removal);
                                    }
                            }
                        }
                        return null;
                    }
                });
                return responder;
            }
        };
        lobby.setAiProfile("Default");
        return lobby;
    }

    private static Game fixtureGame(List<Placement> own, List<Placement> other, int seat, String removalResponseTo) {
        List<List<Placement>> layouts = seat == 0 ? List.of(own, other) : List.of(other, own);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) {
            Deck deck = new Deck();
            for (Placement p : layouts.get(s)) {
                if (loaded.add(p.name())) StaticData.instance().attemptToLoadCard(p.name());
                deck.getMain().add(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name())), 1);
            }
            if (deck.getMain().countAll() != 40) throw new AssertionError("Cold fixture must register forty");
            players.add(new RegisteredPlayer(deck).setPlayer(s == seat && improved
                    ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                    : s != seat && removalResponseTo != null ? removalOpponent(removalResponseTo)
                    : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Cold Oracle recovery").createGame();
        game.setAge(GameStage.Play);
        Player player = game.getPlayers().get(seat);
        game.getPhaseHandler().setupFirstTurn(player,
                () -> game.getPhaseHandler().devModeSet(main2 ? PhaseType.MAIN2 : PhaseType.MAIN1, player));
        for (int s = 0; s < 2; s++) for (Placement p : layouts.get(s)) {
            Card c = card(p.name(), game.getPlayers().get(s), p.zone());
            c.setTapped(p.tapped());
            if (p.name().equals("Narset, Parter of Veils")) c.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
            if (p.name().equals("Teferi, Time Raveler")) c.setCounters(forge.game.card.CounterEnumType.LOYALTY, 4);
            // Placed planeswalkers need their printed loyalty or checkStateEffects
            // puts them straight into the graveyard and the pips vanish.
            if (p.name().equals("Jace, the Mind Sculptor")) c.setCounters(forge.game.card.CounterEnumType.LOYALTY, 3);
            if (p.name().equals("Liliana of the Veil")) c.setCounters(forge.game.card.CounterEnumType.LOYALTY, 3);
        }
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        return game;
    }

    /** Cold-controller recovery, exact registered forty, no pile knowledge fed to
     * the policy. Reverse only changes hidden order, not the initial decision. */
    private static void coldRun(int seat, String route, String blocker, boolean reverse) {
        List<Placement> own = coldLayout(route, blocker, reverse);
        List<Placement> other = new ArrayList<>();
        String hate = switch (blocker) {
            case "draw-limit" -> "Narset, Parter of Veils";
            case "null-rod" -> "Null Rod";
            case "cannot-win" -> "Platinum Angel";
            default -> null;
        };
        if (hate != null) other.add(new Placement(hate, ZoneType.Battlefield, false));
        if (blocker.equals("counterspell")) {
            other.add(new Placement("Counterspell", ZoneType.Hand, false));
            other.add(new Placement("Island", ZoneType.Battlefield, false));
            other.add(new Placement("Island", ZoneType.Battlefield, false));
        }
        while (other.size() < 40) other.add(new Placement("Forest", ZoneType.Library, false));
        Game game = fixtureGame(own, other, seat);
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        BenchRandomAudit.install(86400 + seat + (main2 ? 100 : 0));
        String first = "default";
        if (improved) {
            var action = new forge.ai.CubeDoomsdayPlan(player).nextAction();
            first = action == null ? "none" : action.getHostCard().getName();
            if (blocker.equals("none") && first.equals("none")) throw new AssertionError("Recovery did not reach " + route);
            if (!blocker.equals("none") && !blocker.equals("counterspell") && !first.equals("none"))
                throw new AssertionError("Recovery committed despite " + blocker + ": " + first);
            if (route.equals("star") && blocker.equals("none") && !first.equals("Chromatic Star"))
                throw new AssertionError("Hidden pile order must not change first action");
        }
        boolean alternateGush = false;
        int steps = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() == 1 && steps++ < STEP_LIMIT) {
            game.getPhaseHandler().mainLoopStep();
            if (!game.getStack().isEmpty()) {
                var sa = game.getStack().peekAbility();
                if (sa.getHostCard().getName().equals("Gush")) {
                    alternateGush |= sa.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof forge.game.cost.CostReturn);
                    if (improved && player.getManaPool().getAmountOfColor(forge.card.MagicColor.BLUE) < 2)
                        throw new AssertionError("Cold Gush lost Oracle's floating UU");
                }
            }
        }
        String altWin = player.getOutcome() == null ? null : player.getOutcome().altWinSourceName;
        boolean expectedWin = blocker.equals("none") && !reverse;
        if (improved && expectedWin && (!player.hasWon() || !"Thassa's Oracle".equals(altWin)))
            throw new AssertionError("Cold recovery failed " + route + " hand=" + cards(player, ZoneType.Hand));
        if (improved && expectedWin && (route.equals("star") || route.equals("gush")) && !alternateGush)
            throw new AssertionError("Must use real Gush return cost");
        if (blocker.equals("counterspell") && improved && !has(opponent, ZoneType.Graveyard, "Counterspell"))
            throw new AssertionError("Opponent did not actually interact");
        if (!blocker.equals("none") && player.hasWon()) throw new AssertionError("Unexpected control win: " + blocker);
        if (steps >= STEP_LIMIT) throw new AssertionError("Cold recovery step budget exhausted");
        System.out.println("COLD_RESULT improved=" + improved + " seat=" + seat + " main2=" + main2 + " route=" + route
                + " control=" + blocker + " reverse=" + reverse + " first=" + first + " won=" + player.hasWon()
                + " altWin=" + altWin + " alternateGush=" + alternateGush + " steps=" + steps);
    }

    /** Initial native plan decisions, not whole-game win claims. */
    private static void availabilityRun(int seat, String plan, String variant, boolean assertFixed) {
        List<Placement> own;
        if (plan.equals("recovery")) own = coldLayout("star", "none", false);
        else {
            own = new ArrayList<>();
            if (plan.equals("doomsday")) {
                own.add(new Placement("Doomsday", ZoneType.Hand, false));
                own.add(new Placement("Ancestral Recall", ZoneType.Hand, false));
                for (int i=0;i<3;i++) own.add(new Placement("Swamp", ZoneType.Battlefield, false));
                for (int i=0;i<3;i++) own.add(new Placement("Island", ZoneType.Battlefield, false));
            } else {
                for (String name : List.of("Displacer Kitten", "Teferi, Time Raveler", "Sol Ring", "Island", "Island", "Plains", "Plains", "Plains"))
                    own.add(new Placement(name, ZoneType.Battlefield, false));
            }
            for (int i=0;i<(plan.equals("doomsday")?24:15);i++) own.add(new Placement("Forest", ZoneType.Library, false));
            own.add(new Placement("Thassa's Oracle", ZoneType.Library, false));
            while (own.size()<40) own.add(new Placement("Forest", ZoneType.Exile, false));
        }
        boolean graveyardWithUnknown = variant.equals("oracle-graveyard-with-unknown");
        boolean unavailable = variant.startsWith("oracle-"), altered = !variant.equals("available");
        ZoneType destination = variant.endsWith("bf") ? ZoneType.Battlefield : ZoneType.Exile;
        if (unavailable) {
            int oracle=-1, filler=-1;
            for (int i=0;i<own.size();i++) {
                if (own.get(i).name().equals("Thassa's Oracle")) oracle=i;
                if (own.get(i).name().equals("Forest")&&own.get(i).zone()==ZoneType.Exile) filler=i;
            }
            own.set(oracle,new Placement("Forest",ZoneType.Library,false));
            own.set(filler,new Placement("Thassa's Oracle",graveyardWithUnknown?ZoneType.Graveyard:destination,false));
        } else if (altered&&destination==ZoneType.Battlefield) {
            for (int i=0;i<own.size();i++) if (own.get(i).name().equals("Forest")&&own.get(i).zone()==ZoneType.Exile) {
                own.set(i,new Placement("Forest",destination,false));break;
            }
        }
        List<Placement> other=new ArrayList<>();
        for(int i=0;i<40;i++) other.add(new Placement("Forest",ZoneType.Library,false));
        Game game=fixtureGame(own,other,seat);Player p=game.getPlayers().get(seat);
        for(Card card:p.getCardsIn(ZoneType.Battlefield)) if(card.getName().equals("Teferi, Time Raveler"))
            card.setCounters(forge.game.card.CounterEnumType.LOYALTY,4);
        boolean faceDown=altered&&!variant.equals("oracle-visible-exile"), mayLook=false;
        if(faceDown) {
            Card target=p.getCardsIn(destination).stream().filter(c->c.getName().equals(unavailable&&!graveyardWithUnknown?"Thassa's Oracle":"Forest")).findFirst().orElseThrow();
            target.turnFaceDown(true);
            if(variant.endsWith("-known")) target.addMayLookFaceDownExile(p);
            mayLook=target.getView().canFaceDownBeShownTo(p.getView());
            if(mayLook!=(destination==ZoneType.Battlefield||variant.endsWith("-known"))) throw new AssertionError("Face-down visibility fixture");
        }
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(86500L+seat*100L+plan.hashCode()+variant.hashCode());
        var action=plan.equals("kitten")?new forge.ai.CubeKittenPlan(p).nextAction():new forge.ai.CubeDoomsdayPlan(p).nextAction();
        boolean expected=graveyardWithUnknown?plan.equals("doomsday"):!unavailable&&(!faceDown||mayLook);
        System.out.println("AVAILABILITY_RESULT seat="+seat+" plan="+plan+" variant="+variant+" mayLook="+mayLook
            +" proposed="+(action!=null)+" expected="+expected+" source="+(action==null?"none":action.getHostCard().getName()));
        if(assertFixed&&(action!=null)!=expected) throw new AssertionError("Availability decision mismatch: "+plan+" "+variant);
        if(assertFixed) {
            int steps=0,minimumLibrary=p.getCardsIn(ZoneType.Library).size();
            while(!game.isGameOver()&&game.getPhaseHandler().getTurn()==1&&steps++<900) {
                game.getPhaseHandler().mainLoopStep();
                minimumLibrary=Math.min(minimumLibrary,p.getCardsIn(ZoneType.Library).size());
            }
            boolean oracleWin=p.getOutcome()!=null&&"Thassa's Oracle".equals(p.getOutcome().altWinSourceName);
            System.out.println("AVAILABILITY_EXECUTION seat="+seat+" plan="+plan+" variant="+variant+" improved="+improved
                +" expected="+expected+" won="+p.hasWon()+" oracleWin="+oracleWin+" minimumLibrary="+minimumLibrary+" steps="+steps);
            if(steps>=900) throw new AssertionError("Availability native execution budget");
            if(improved&&expected&&(!p.hasWon()||!oracleWin)) throw new AssertionError("Available line did not execute: "+plan+" "+variant);
            if(!expected&&oracleWin) throw new AssertionError("Unavailable finisher unexpectedly won: "+plan+" "+variant);
        }
    }

    /** Five-card pile, direct alternate-cost Gush, then a public-devotion
     * Oracle finish. No Star/Recall and no host-specified game actions. */
    private static void devotionRun(int seat, String permanent, boolean oracleInHand, String blocker) {
        List<Placement> own=new ArrayList<>(),other=new ArrayList<>();
        own.add(new Placement("Doomsday",ZoneType.Hand,false));
        own.add(new Placement("Gush",blocker.equals("gush-exiled")?ZoneType.Exile:ZoneType.Hand,false));
        for(int i=0;i<3;i++)own.add(new Placement("Swamp",ZoneType.Battlefield,false));
        own.add(new Placement("Island",ZoneType.Battlefield,false));
        own.add(new Placement(blocker.equals("one-island")?"Mox Sapphire":"Island",ZoneType.Battlefield,blocker.equals("short-blue")));
        // Removal controls isolate losing devotion, without Jace looting a
        // third card in response and thereby creating a different legal win.
        own.add(new Placement(blocker.equals("no-devotion")?"Elvish Mystic":permanent,ZoneType.Battlefield,blocker.startsWith("remove-devotion")));
        boolean oracleExiled=blocker.equals("oracle-exiled");
        own.add(new Placement("Thassa's Oracle",oracleExiled?ZoneType.Exile:oracleInHand?ZoneType.Hand:ZoneType.Library,false));
        for(int i=0;i<(oracleExiled||oracleInHand?25:24);i++)own.add(new Placement("Forest",ZoneType.Library,false));
        while(own.size()<40)own.add(new Placement("Forest",ZoneType.Exile,false));
        if(blocker.equals("draw-limit"))other.add(new Placement("Narset, Parter of Veils",ZoneType.Battlefield,false));
        if(blocker.equals("cannot-win"))other.add(new Placement("Platinum Angel",ZoneType.Battlefield,false));
        if(blocker.equals("counterspell")) {
            other.add(new Placement("Counterspell",ZoneType.Hand,false));
            other.add(new Placement("Island",ZoneType.Battlefield,false));
            other.add(new Placement("Island",ZoneType.Battlefield,false));
        }
        if(blocker.startsWith("remove-devotion")) {
            other.add(new Placement("Dismember",ZoneType.Hand,false));
            for(int i=0;i<3;i++)other.add(new Placement("Swamp",ZoneType.Battlefield,false));
        }
        while(other.size()<40)other.add(new Placement("Forest",ZoneType.Library,false));
        String removalResponseTo=blocker.equals("remove-devotion-doom")?"Doomsday":blocker.equals("remove-devotion-gush")?"Gush":null;
        Game game=fixtureGame(own,other,seat,removalResponseTo);Player p=game.getPlayers().get(seat),opponent=game.getPlayers().get(1-seat);
        Card devotionPermanent=p.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.getName().equals(permanent)).findFirst().orElse(null);
        if(blocker.equals("facedown-devotion"))devotionPermanent.turnFaceDown(true);
        if(blocker.equals("island-devotion")) { devotionPermanent.addType("Land");devotionPermanent.addType("Island"); }
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(0); // Fixed constructed fixture, not a sampled opening.
        var proposed=new forge.ai.CubeDoomsdayPlan(p).nextAction();
        System.out.println("DEVOTION_PROPOSAL seat="+seat+" blocker="+blocker+" action="+(proposed==null?"none":proposed.getHostCard().getName()));
        if(improved&&blocker.equals("none")&&(proposed==null||!proposed.getHostCard().getName().equals("Doomsday")))
            throw new AssertionError("Missing direct Gush Doomsday plan");
        if(improved&&!blocker.equals("none")&&!blocker.equals("counterspell")&&!blocker.startsWith("remove-devotion")&&proposed!=null)
            throw new AssertionError("Direct Gush plan committed despite "+blocker);
        int steps=0;boolean doom=false,gush=false,pile=false,alternate=false,removedDevotion=false;int beforeOracle=-1;
        while(!game.isGameOver()&&game.getPhaseHandler().getTurn()==1&&steps++<STEP_LIMIT) {
            game.getPhaseHandler().mainLoopStep();
            doom|=has(p,ZoneType.Graveyard,"Doomsday");
            pile|=doom&&p.getCardsIn(ZoneType.Library).size()==5;
            gush|=has(p,ZoneType.Graveyard,"Gush");
            removedDevotion|=has(opponent,ZoneType.Graveyard,"Dismember")&&has(p,ZoneType.Graveyard,permanent);
            if(!game.getStack().isEmpty()) {
                var sa=game.getStack().peekAbility();
                if(sa.getHostCard().getName().equals("Gush")) {
                    alternate|=sa.getPayCosts().getCostParts().stream().anyMatch(cost->cost instanceof forge.game.cost.CostReturn);
                    if(improved&&doom&&blocker.equals("none")&&p.getManaPool().getAmountOfColor(forge.card.MagicColor.BLUE)<2)
                        throw new AssertionError("Direct Gush lost floating UU");
                }
                if(sa.isSpell()&&sa.getHostCard().getName().equals("Thassa's Oracle"))beforeOracle=p.getCardsIn(ZoneType.Library).size();
            }
        }
        boolean oracleWin=p.getOutcome()!=null&&"Thassa's Oracle".equals(p.getOutcome().altWinSourceName);
        System.out.println("DEVOTION_RESULT improved="+improved+" seat="+seat+" main2="+main2+" permanent="+permanent.replace(' ','_')
                +" oracleInHand="+oracleInHand+" blocker="+blocker+" doom="+doom+" pile="+pile+" gush="+gush+" alternate="+alternate
                +" libraryBeforeOracle="+beforeOracle+" won="+p.hasWon()+" oracleWin="+oracleWin+" steps="+steps
                +" removedDevotion="+removedDevotion+" opponent="+(removalResponseTo==null?"Default":"scripted-removal-on-"+removalResponseTo));
        if(steps>=STEP_LIMIT)throw new AssertionError("Direct Gush step budget");
        if(improved&&blocker.equals("none")&&!(doom&&pile&&gush&&alternate&&oracleWin&&beforeOracle==3))
            throw new AssertionError("Native direct Gush/public-devotion route not executed");
        if(!blocker.equals("none")&&oracleWin)throw new AssertionError("Unexpected direct Gush control win: "+blocker);
        if(improved&&blocker.equals("counterspell")&&!has(opponent,ZoneType.Graveyard,"Counterspell"))
            throw new AssertionError("Counterspell control did not interact");
        if(improved&&blocker.startsWith("remove-devotion")&&!removedDevotion)
            throw new AssertionError("Devotion removal control did not interact");
    }


    /** Runtime policy string of the loaded {@code CubeComboAi}, read reflectively
     * so the matched-v42 control (this test source compiled against the frozen
     * v42 classes) reports the version it actually ran, not a constant inlined
     * at compile time. */
    private static String policy() {
        try {
            return String.valueOf(forge.ai.CubeComboAi.class.getField("VERSION").get(null));
        } catch (final ReflectiveOperationException failure) {
            return "unknown";
        }
    }

    /** Positive assertions run only when the probe asks for them, so the
     * matched-v42 control can execute exactly this source against the frozen
     * v42 classes and record what v42 decides instead of failing. */
    private static final boolean NATURAL_STRICT = Boolean.getBoolean("forge.test.requireDoomsdayNatural");

    private static final List<String> NODRAW_CASES = List.of("pips3-mixed", "pips3-singles", "pips4",
            "pips2-oracle-hand", "liliana", "torpor", "lethal-board", "oracle-battlefield",
            "cannot-win", "life-one", "counterspell");
    private static final List<String> PASSTURN_CASES = List.of("pips2", "oracle-graveyard", "pips3",
            "clock-safe", "clock-blocker", "narset-one-draw", "clock", "torpor", "lethal-board",
            "oracle-battlefield", "cannot-win", "life-one", "counterspell");

    /** Whether the plan's first decision must be Doomsday. `lethal-board` is
     * main-dependent on purpose: the better-attack abstention only dominates a
     * same-turn route while an attack step is still ahead of us, so in MAIN2 a
     * route-1 board must still commit. */
    private static boolean naturalProposes(String kase, boolean passTurn) {
        if (kase.equals("lethal-board")) return !passTurn && main2;
        return switch (kase) {
            case "pips3-mixed", "pips3-singles", "pips4", "pips2", "oracle-graveyard", "pips3",
                 "clock-safe", "clock-blocker", "narset-one-draw", "counterspell" -> true;
            default -> false;
        };
    }

    /** Route 1 (no-draw public devotion) and route 2 (pass-turn pile), both
     * seats and both mains. Board is 3 Swamp + 2 Island untapped, so Doomsday's
     * BBB cannot eat the blue Oracle needs; plus N public blue pips and a
     * 25-card library that makes Doomsday necessary. The fixture specifies
     * zones, tapped states and life totals only - never a game action. */
    private static void naturalRun(int seat, boolean passTurn, String kase) {
        List<Placement> own = new ArrayList<>(), other = new ArrayList<>();
        own.add(new Placement("Doomsday", ZoneType.Hand, false));
        for (int i = 0; i < 3; i++) own.add(new Placement("Swamp", ZoneType.Battlefield, false));
        for (int i = 0; i < 2; i++) own.add(new Placement("Island", ZoneType.Battlefield, false));
        // Tapped devotion still counts toward devotion but cannot block, which
        // is what isolates the clock guard's blocker term.
        boolean tappedPips = kase.startsWith("clock");
        List<String> pips = switch (kase) {
            case "pips3-singles" -> List.of("Spellseeker", "Emry, Lurker of the Loch", "Faerie Mastermind");
            case "pips4" -> List.of("Jace, the Mind Sculptor", "Narset, Parter of Veils");
            case "pips3" -> List.of("Emry, Lurker of the Loch", "Faerie Mastermind", "Spellseeker");
            case "pips2-oracle-hand", "liliana" -> List.of("Spellseeker", "Emry, Lurker of the Loch");
            // Control boards deliberately carry no permanent that can remove
            // the control itself. probe-1 caught Default legally bouncing
            // Platinum Angel with Jace, the Mind Sculptor's -1 and then winning
            // through the restored route: a correct adaptation, and a broken
            // rig. Jace stays only in the two MUST-MOVE cases, where the plan
            // wins on its first priority pass and Jace never acts.
            default -> passTurn ? List.of("Emry, Lurker of the Loch", "Faerie Mastermind")
                    : List.of("Spellseeker", "Emry, Lurker of the Loch", "Faerie Mastermind");
        };
        for (String name : pips) own.add(new Placement(name, ZoneType.Battlefield, tappedPips));
        // Our own fatty: the attacker that makes the board lethal, or the
        // untapped blocker that legitimately answers a lethal public clock.
        if (kase.equals("lethal-board") || kase.equals("clock-blocker"))
            own.add(new Placement("Old One Eye", ZoneType.Battlefield, false));
        ZoneType oracleZone = kase.equals("oracle-battlefield") ? ZoneType.Battlefield
                : kase.equals("oracle-graveyard") ? ZoneType.Graveyard
                : passTurn ? ZoneType.Library : ZoneType.Hand;
        own.add(new Placement("Thassa's Oracle", oracleZone, false));
        for (int i = 0; i < (oracleZone == ZoneType.Library ? 24 : 25); i++)
            own.add(new Placement("Forest", ZoneType.Library, false));
        while (own.size() < 40) own.add(new Placement("Forest", ZoneType.Exile, false));
        switch (kase) {
            case "torpor" -> other.add(new Placement("Torpor Orb", ZoneType.Battlefield, false));
            case "cannot-win" -> other.add(new Placement("Platinum Angel", ZoneType.Battlefield, false));
            case "liliana" -> other.add(new Placement("Liliana of the Veil", ZoneType.Battlefield, false));
            case "narset-one-draw" -> other.add(new Placement("Narset, Parter of Veils", ZoneType.Battlefield, false));
            case "clock", "clock-safe", "clock-blocker" -> other.add(new Placement("Old One Eye", ZoneType.Battlefield, false));
            case "counterspell" -> {
                other.add(new Placement("Counterspell", ZoneType.Hand, false));
                other.add(new Placement("Island", ZoneType.Battlefield, false));
                other.add(new Placement("Island", ZoneType.Battlefield, false));
            }
            default -> { }
        }
        while (other.size() < 40) other.add(new Placement("Forest", ZoneType.Library, false));
        Game game = fixtureGame(own, other, seat);
        Player p = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        if (kase.equals("life-one")) p.setLife(1, null);
        // life 12 -> ceil(12/2) = 6 paid, 6 left, exactly the visible 6/6 clock.
        if (kase.equals("clock") || kase.equals("clock-blocker")) p.setLife(12, null);
        if (kase.equals("lethal-board")) opponent.setLife(6, null);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        int ourPower = 0;
        for (Card card : p.getCardsIn(ZoneType.Battlefield))
            if (card.isCreature() && card.isUntapped()) ourPower += card.getNetPower();
        int clock = 0;
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) if (card.isCreature()) clock += card.getNetPower();
        if (kase.equals("cannot-win") && !p.cantWin())
            throw new AssertionError("Win-prohibition fixture must actually prohibit winning");
        if (kase.equals("clock") && !(clock >= p.getLife() - (p.getLife() + 1) / 2))
            throw new AssertionError("Clock fixture must actually be lethal through the passed turn");
        if (kase.equals("narset-one-draw") && !(p.canDrawAmount(1) && !p.canDrawAmount(2)))
            throw new AssertionError("Narset fixture must permit exactly one draw");
        boolean proposes = naturalProposes(kase, passTurn), expectWin = proposes && !kase.equals("counterspell");
        BenchRandomAudit.install(0); // Fixed constructed fixture, not a sampled opening.
        var proposed = new forge.ai.CubeDoomsdayPlan(p).nextAction();
        String action = proposed == null ? "none" : proposed.getHostCard().getName();
        System.out.println("NATURAL_PROPOSAL suite=" + (passTurn ? "passturn" : "nodraw") + " improved=" + improved
                + " policy=" + policy() + " seat=" + seat + " main2=" + main2 + " case=" + kase
                + " pips=" + String.join("+", pips).replace(' ', '_') + " tappedPips=" + tappedPips
                + " oracleZone=" + oracleZone + " life=" + p.getLife() + " oppLife=" + opponent.getLife()
                + " ourUntappedPower=" + ourPower + " visibleClock=" + clock
                + " mustMove=" + proposes + " action=" + action);
        if (improved && NATURAL_STRICT && proposes != action.equals("Doomsday"))
            throw new AssertionError("Natural route proposal mismatch: " + kase + " main2=" + main2 + " -> " + action);
        int steps = 0, limit = passTurn ? 1500 : STEP_LIMIT, lastTurn = passTurn ? 3 : 1;
        boolean doom = false, pile = false;
        int beforeOracle = -1, doomTurn = -1, oracleTurn = -1;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= lastTurn && steps++ < limit) {
            game.getPhaseHandler().mainLoopStep();
            if (!doom && has(p, ZoneType.Graveyard, "Doomsday")) { doom = true; doomTurn = game.getPhaseHandler().getTurn(); }
            pile |= doom && p.getCardsIn(ZoneType.Library).size() == 5;
            if (!game.getStack().isEmpty()) {
                var sa = game.getStack().peekAbility();
                if (sa.isSpell() && sa.getHostCard().getName().equals("Thassa's Oracle")) {
                    beforeOracle = p.getCardsIn(ZoneType.Library).size();
                    oracleTurn = game.getPhaseHandler().getTurn();
                }
            }
        }
        boolean oracleWin = p.getOutcome() != null && "Thassa's Oracle".equals(p.getOutcome().altWinSourceName);
        System.out.println("NATURAL_RESULT suite=" + (passTurn ? "passturn" : "nodraw") + " improved=" + improved
                + " policy=" + policy() + " seat=" + seat + " main2=" + main2 + " case=" + kase
                + " mustMove=" + proposes + " expectWin=" + expectWin + " proposed=" + action
                + " doomsdayCast=" + doom + " doomsdayTurn=" + doomTurn + " fiveCardPile=" + pile
                + " libraryBeforeOracle=" + beforeOracle + " oracleTurn=" + oracleTurn
                + " oracleEnteredBattlefield=" + has(p, ZoneType.Battlefield, "Thassa's Oracle")
                + " won=" + p.hasWon() + " oracleWin=" + oracleWin + " gameOver=" + game.isGameOver()
                + " life=" + p.getLife() + " oppLife=" + opponent.getLife() + " steps=" + steps
                + " opponentCounterspell=" + has(opponent, ZoneType.Graveyard, "Counterspell"));
        if (steps >= limit) throw new AssertionError("Natural route step budget: " + kase);
        if (improved && NATURAL_STRICT) {
            if (expectWin && !(doom && pile && oracleWin && beforeOracle == (passTurn ? 4 : 5)
                    && doomTurn == 1 && oracleTurn == (passTurn ? 3 : 1)))
                throw new AssertionError("Natural route did not execute: " + kase + " main2=" + main2);
            if (!proposes && doom) throw new AssertionError("Doomsday committed despite " + kase);
            if (!expectWin && oracleWin) throw new AssertionError("Unexpected control Oracle win: " + kase);
            if (kase.equals("counterspell") && !has(opponent, ZoneType.Graveyard, "Counterspell"))
                throw new AssertionError("Counterspell control did not interact: " + kase);
        }
        // Default arm is the improvement witness: it must not reach the Oracle win.
        if (!improved && NATURAL_STRICT && oracleWin)
            throw new AssertionError("Default arm unexpectedly won with Oracle: " + kase);
    }

    private static final boolean RITUAL_STRICT = Boolean.getBoolean("forge.test.requireDoomsdayRitual");

    private static final List<String> RITUAL_CASES = List.of("dark-ritual", "cabal-ritual", "lotus-petal",
            "passturn-exact", "lethal-board", "pips-short", "oracle-battlefield", "torpor", "no-doomsday",
            "rule-of-law", "counterspell");

    /** The mana-only card in hand each case offers the bridge. */
    private static String ritualBridgeCard(String kase) {
        return switch (kase) {
            case "cabal-ritual" -> "Cabal Ritual";
            case "lotus-petal" -> "Lotus Petal";
            default -> "Dark Ritual";
        };
    }

    /** Whether the plan's first decision must be that bridge card.
     * `lethal-board` is main-dependent for the same reason it is in the
     * `nodraw` suite: the better-attack abstention only dominates a same-turn
     * route while an attack step is still ahead of us, so in MAIN2 the bridge
     * must still commit. */
    private static boolean ritualProposes(String kase) {
        if (kase.equals("lethal-board")) return main2;
        return switch (kase) {
            case "dark-ritual", "cabal-ritual", "lotus-petal", "passturn-exact", "counterspell" -> true;
            default -> false;
        };
    }

    /** The plan's own bridge count, read reflectively so this same source can
     * run against the frozen v45 classes, where the field does not exist, and
     * record -1 instead of failing. */
    private static int planBridges(boolean reset) {
        try {
            var field = forge.ai.CubeDoomsdayPlan.class.getDeclaredField("ritualBridges");
            field.setAccessible(true);
            int value = field.getInt(null);
            if (reset) field.setInt(null, 0);
            return value;
        } catch (final ReflectiveOperationException absent) {
            return -1;
        }
    }

    /** Route 3, the ritual bridge, both seats and both mains. Doomsday is in
     * hand and unplayable for want of black; a mana-only card in hand would fix
     * exactly that. The fixture specifies zones, tapped states and life totals
     * only - never a plan action. The one deliberately specified game action is
     * `rule-of-law`'s Mishra's Bauble, which establishes the native
     * spells-cast-this-turn count the control is about; it is asserted before
     * the plan is consulted. */
    private static void ritualRun(int seat, String kase) {
        List<Placement> own = new ArrayList<>(), other = new ArrayList<>();
        boolean passTurn = kase.equals("passturn-exact");
        String bridgeCard = ritualBridgeCard(kase);
        if (!kase.equals("no-doomsday")) own.add(new Placement("Doomsday", ZoneType.Hand, false));
        own.add(new Placement(bridgeCard, ZoneType.Hand, false));
        if (kase.equals("rule-of-law")) own.add(new Placement("Mishra's Bauble", ZoneType.Hand, false));
        // One black source short of Doomsday's own BBB in every case, which is
        // the only shortfall a mana-only card can answer. Lotus Petal makes one
        // mana of any colour, so its board is two black short of nothing else.
        for (int i = 0; i < (kase.equals("lotus-petal") ? 2 : 1); i++)
            own.add(new Placement("Swamp", ZoneType.Battlefield, false));
        // passturn-exact taps its Islands: the bridged pool is then exactly
        // Doomsday's BBB and nothing more, so only the pass-turn route is
        // reachable this turn. They untap before the Oracle cast two turns on.
        for (int i = 0; i < (kase.equals("cabal-ritual") ? 3 : 2); i++)
            own.add(new Placement("Island", ZoneType.Battlefield, passTurn));
        List<String> pips = passTurn ? List.of("Emry, Lurker of the Loch", "Faerie Mastermind")
                : kase.equals("pips-short") ? List.of("Faerie Mastermind")
                : List.of("Spellseeker", "Emry, Lurker of the Loch", "Faerie Mastermind");
        for (String name : pips) own.add(new Placement(name, ZoneType.Battlefield, false));
        if (kase.equals("lethal-board")) own.add(new Placement("Old One Eye", ZoneType.Battlefield, false));
        ZoneType oracleZone = kase.equals("oracle-battlefield") ? ZoneType.Battlefield
                : passTurn || kase.equals("pips-short") ? ZoneType.Library : ZoneType.Hand;
        own.add(new Placement("Thassa's Oracle", oracleZone, false));
        for (int i = 0; i < (oracleZone == ZoneType.Library ? 24 : 25); i++)
            own.add(new Placement("Forest", ZoneType.Library, false));
        while (own.size() < 40) own.add(new Placement("Forest", ZoneType.Exile, false));
        switch (kase) {
            case "torpor" -> other.add(new Placement("Torpor Orb", ZoneType.Battlefield, false));
            case "rule-of-law" -> other.add(new Placement("Rule of Law", ZoneType.Battlefield, false));
            case "counterspell" -> {
                other.add(new Placement("Counterspell", ZoneType.Hand, false));
                other.add(new Placement("Island", ZoneType.Battlefield, false));
                other.add(new Placement("Island", ZoneType.Battlefield, false));
            }
            default -> { }
        }
        while (other.size() < 40) other.add(new Placement("Forest", ZoneType.Library, false));
        Game game = fixtureGame(own, other, seat);
        Player p = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        if (kase.equals("lethal-board")) opponent.setLife(6, null);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(0); // Fixed constructed fixture, not a sampled opening.
        if (kase.equals("rule-of-law")) {
            // The only deliberately specified game action in this suite, and it
            // is the control's premise rather than its decision: one spell must
            // already have been cast this turn for Rule of Law to bite.
            final Card bauble = p.getCardsIn(ZoneType.Hand).stream()
                    .filter(c -> c.getName().equals("Mishra's Bauble")).findFirst().orElseThrow();
            final var baubleSpell = bauble.getSpellAbilities().stream().filter(forge.game.spellability.SpellAbility::isSpell)
                    .findFirst().orElseThrow(() -> new AssertionError("Mishra's Bauble has no permanent spell"));
            baubleSpell.setActivatingPlayer(p);
            if (!p.getController().playChosenSpellAbility(baubleSpell))
                throw new AssertionError("native controller rejected the Rule of Law premise cast");
            settle(game);
            if (p.getSpellsCastThisTurn() != 1)
                throw new AssertionError("Rule of Law fixture must actually have cast one spell this turn");
        }
        // Premises, asserted before the plan is consulted.
        if (!kase.equals("no-doomsday")) {
            var doom = p.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Doomsday"))
                    .findFirst().orElseThrow().getSpellAbilities().get(0).copy(p);
            if (forge.ai.CubeComboAi.canPayCost(doom, p, false))
                throw new AssertionError("Ritual fixture must actually start with Doomsday unpayable: " + kase);
        }
        var bridgeSpell = p.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals(bridgeCard))
                .findFirst().orElseThrow().getSpellAbilities().get(0).copy(p);
        boolean bridgeCastable = forge.ai.CubeComboAi.canPlayNative(bridgeSpell, p)
                && forge.ai.CubeComboAi.canPayCost(bridgeSpell, p, false);
        if (kase.equals("rule-of-law") == bridgeCastable)
            throw new AssertionError("Bridge castability premise wrong for " + kase + ": " + bridgeCastable);
        // Public-board premise, computed exactly as `naturalRun` does: untapped
        // creature power, with no phase-dependent CombatUtil call, so MAIN1 and
        // MAIN2 assert the same position.
        int ourPower = 0;
        int opposingCreatures = 0;
        for (Card card : p.getCardsIn(ZoneType.Battlefield))
            if (card.isCreature() && card.isUntapped()) ourPower += Math.max(0, card.getNetPower());
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield)) if (card.isCreature()) opposingCreatures++;
        if (kase.equals("lethal-board") && (ourPower < opponent.getLife() || opposingCreatures > 0))
            throw new AssertionError("Lethal-board fixture must actually present unblocked lethal");
        if (kase.equals("counterspell") && !has(opponent, ZoneType.Hand, "Counterspell"))
            throw new AssertionError("Counterspell fixture must actually hold a Counterspell");
        boolean proposes = ritualProposes(kase), expectWin = proposes && !kase.equals("counterspell");
        planBridges(true);
        var proposed = new forge.ai.CubeDoomsdayPlan(p).nextAction();
        String action = proposed == null ? "none" : proposed.getHostCard().getName();
        planBridges(true);
        System.out.println("RITUAL_PROPOSAL suite=ritual improved=" + improved + " policy=" + policy()
                + " seat=" + seat + " main2=" + main2 + " case=" + kase + " bridge=" + bridgeCard.replace(' ', '_')
                + " bridgeCastable=" + bridgeCastable + " pips=" + String.join("+", pips).replace(' ', '_')
                + " oracleZone=" + oracleZone + " life=" + p.getLife() + " oppLife=" + opponent.getLife()
                + " ourUntappedPower=" + ourPower + " opposingCreatures=" + opposingCreatures
                + " spellsCastThisTurn=" + p.getSpellsCastThisTurn()
                + " mustMove=" + proposes + " action=" + action);
        if (improved && RITUAL_STRICT && proposes != action.equals(bridgeCard))
            throw new AssertionError("Ritual bridge proposal mismatch: " + kase + " main2=" + main2 + " -> " + action);
        int steps = 0, limit = passTurn ? 1500 : STEP_LIMIT, lastTurn = passTurn ? 3 : 1;
        boolean doom = false, pile = false;
        int beforeOracle = -1, doomTurn = -1, oracleTurn = -1;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= lastTurn && steps++ < limit) {
            game.getPhaseHandler().mainLoopStep();
            if (!doom && has(p, ZoneType.Graveyard, "Doomsday")) { doom = true; doomTurn = game.getPhaseHandler().getTurn(); }
            pile |= doom && p.getCardsIn(ZoneType.Library).size() == 5;
            if (!game.getStack().isEmpty()) {
                var sa = game.getStack().peekAbility();
                if (sa.isSpell() && sa.getHostCard().getName().equals("Thassa's Oracle")) {
                    beforeOracle = p.getCardsIn(ZoneType.Library).size();
                    oracleTurn = game.getPhaseHandler().getTurn();
                }
            }
        }
        int bridges = planBridges(true);
        boolean bridgeLeftHand = !has(p, ZoneType.Hand, bridgeCard);
        boolean oracleWin = p.getOutcome() != null && "Thassa's Oracle".equals(p.getOutcome().altWinSourceName);
        System.out.println("RITUAL_RESULT suite=ritual improved=" + improved + " policy=" + policy()
                + " seat=" + seat + " main2=" + main2 + " case=" + kase + " bridge=" + bridgeCard.replace(' ', '_')
                + " mustMove=" + proposes + " expectWin=" + expectWin + " proposed=" + action
                + " planBridges=" + bridges + " bridgeLeftHand=" + bridgeLeftHand
                + " doomsdayCast=" + doom + " doomsdayTurn=" + doomTurn + " fiveCardPile=" + pile
                + " libraryBeforeOracle=" + beforeOracle + " oracleTurn=" + oracleTurn
                + " oracleEnteredBattlefield=" + has(p, ZoneType.Battlefield, "Thassa's Oracle")
                + " won=" + p.hasWon() + " oracleWin=" + oracleWin + " gameOver=" + game.isGameOver()
                + " life=" + p.getLife() + " oppLife=" + opponent.getLife() + " steps=" + steps
                + " opponentCounterspell=" + has(opponent, ZoneType.Graveyard, "Counterspell"));
        if (steps >= limit) throw new AssertionError("Ritual bridge step budget: " + kase);
        if (improved && RITUAL_STRICT) {
            if (expectWin && !(bridges >= 1 && bridgeLeftHand && doom && pile && oracleWin
                    && beforeOracle == (passTurn ? 4 : 5) && doomTurn == 1 && oracleTurn == (passTurn ? 3 : 1)))
                throw new AssertionError("Ritual bridge did not execute: " + kase + " main2=" + main2);
            if (!proposes && (bridges != 0 || doom))
                throw new AssertionError("Plan spent a bridge or cast Doomsday despite " + kase);
            if (!expectWin && oracleWin) throw new AssertionError("Unexpected control Oracle win: " + kase);
            if (kase.equals("counterspell") && !has(opponent, ZoneType.Graveyard, "Counterspell"))
                throw new AssertionError("Counterspell control did not interact: " + kase);
        }
        // Default arm is the improvement witness: it must not reach the Oracle win.
        if (!improved && RITUAL_STRICT && oracleWin)
            throw new AssertionError("Default arm unexpectedly won with Oracle: " + kase);
    }

    // ------------------------------------------------------------------ v49
    private static final boolean TIGHTEN_STRICT = Boolean.getBoolean("forge.test.requireDoomsdayTighten");
    private static final boolean DISCARD_STRICT = Boolean.getBoolean("forge.test.requireComboDiscard");

    /** v49 R1/R2 controls. Route-2 boards only, both seats and both mains. The
     * fixture specifies zones, tapped states and life totals - never a game
     * action - exactly like {@link #naturalRun}. These live in their own suite
     * rather than extending `passturn`, so every pre-existing suite log stays
     * byte-identical. */
    private static final List<String> TIGHTEN_CASES = List.of("clock-chump", "clock-absorbed",
            "one-blue-source", "two-blue-sources", "petal-only-blue", "petal-consumed", "tapped-two-sources");

    private static boolean tightenProposes(String kase) {
        return switch (kase) {
            case "clock-absorbed", "two-blue-sources", "tapped-two-sources" -> true;
            default -> false;
        };
    }

    /** The plan's own discard-swap count, read reflectively so this same source
     * can run against the frozen v47 classes, where the field does not exist,
     * and record -1 instead of failing. */
    private static int discardSwaps(boolean reset) {
        try {
            var field = Class.forName("forge.ai.CubeComboPlayerController").getDeclaredField("comboDiscardSwaps");
            field.setAccessible(true);
            int value = field.getInt(null);
            if (reset) field.setInt(null, 0);
            return value;
        } catch (final ReflectiveOperationException | LinkageError absent) {
            return -1;
        }
    }

    private static void tightenRun(int seat, String kase) {
        List<Placement> own = new ArrayList<>(), other = new ArrayList<>();
        own.add(new Placement("Doomsday", ZoneType.Hand, false));
        // `petal-consumed` is one black source short of Doomsday's own BBB, so
        // the payment must eat a Petal: that is the analysis's actual
        // `passturn-petal-only` premise, which `petal-only-blue` (three Swamps,
        // both Petals surviving) turns out NOT to be.
        for (int i = 0; i < (kase.equals("petal-consumed") ? 2 : 3); i++)
            own.add(new Placement("Swamp", ZoneType.Battlefield, false));
        // The blue half of the board is the whole point of R2: count the own
        // battlefield permanents that could pay one of Oracle's pips next turn.
        switch (kase) {
            case "one-blue-source" -> own.add(new Placement("Island", ZoneType.Battlefield, false));
            case "two-blue-sources" -> {
                own.add(new Placement("Island", ZoneType.Battlefield, false));
                own.add(new Placement("Underground Sea", ZoneType.Battlefield, false));
            }
            case "petal-only-blue", "petal-consumed" -> {
                own.add(new Placement("Lotus Petal", ZoneType.Battlefield, false));
                own.add(new Placement("Lotus Petal", ZoneType.Battlefield, false));
            }
            default -> {
                for (int i = 0; i < 2; i++)
                    own.add(new Placement("Island", ZoneType.Battlefield, kase.equals("tapped-two-sources")));
            }
        }
        // Two public blue pips, tapped in the clock cases so that the only
        // untapped creatures we control are the blockers under test.
        boolean tappedPips = kase.startsWith("clock");
        for (String name : List.of("Emry, Lurker of the Loch", "Faerie Mastermind"))
            own.add(new Placement(name, ZoneType.Battlefield, tappedPips));
        // Vanilla 1/1s, deliberately with NO mana ability: probe-1 used Elvish
        // Mystic, whose green mana funded Faerie Mastermind's {3}{U} draw on
        // turn 1 and moved `libraryBeforeOracle` from 4 to 3. A control board
        // must not contain the answer to its own premise.
        if (kase.startsWith("clock"))
            for (int i = 0; i < 2; i++) own.add(new Placement("Mons's Goblin Raiders", ZoneType.Battlefield, false));
        own.add(new Placement("Thassa's Oracle", ZoneType.Library, false));
        for (int i = 0; i < 24; i++) own.add(new Placement("Forest", ZoneType.Library, false));
        while (own.size() < 40) own.add(new Placement("Forest", ZoneType.Exile, false));
        // The opposing clock is read as public power only, so the 16701484-s0
        // board (6 + 2 + 2 + 2 = 12) is reproduced by power, not by card
        // identity; `clock-absorbed` is the same shape the k blockers do cover.
        if (kase.equals("clock-chump")) {
            other.add(new Placement("Old One Eye", ZoneType.Battlefield, false));
            for (int i = 0; i < 3; i++) other.add(new Placement("Grizzly Bears", ZoneType.Battlefield, false));
        }
        if (kase.equals("clock-absorbed"))
            for (int i = 0; i < 2; i++) other.add(new Placement("Grizzly Bears", ZoneType.Battlefield, false));
        while (other.size() < 40) other.add(new Placement("Forest", ZoneType.Library, false));
        Game game = fixtureGame(own, other, seat);
        Player p = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        if (kase.equals("clock-chump")) p.setLife(4, null);
        if (kase.equals("clock-absorbed")) p.setLife(12, null);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        // Premises, computed from the same public reads the guard uses and
        // asserted before the plan is consulted.
        int blockers = 0, clockTotal = 0, blueSources = 0;
        List<Integer> powers = new ArrayList<>();
        for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
            if (card.isCreature()) { powers.add(Math.max(0, card.getNetPower())); clockTotal += Math.max(0, card.getNetPower()); }
        for (Card card : p.getCardsIn(ZoneType.Battlefield)) {
            if (card.isCreature() && card.isUntapped()) blockers++;
            for (var original : card.getManaAbilities()) {
                var ability = original.copy(p);
                if (ability.getManaPart() == null || !ability.canProduce("U")) continue;
                if (ability.getPayCosts().getCostParts().stream()
                        .anyMatch(cost -> cost instanceof forge.game.cost.CostSacrifice)) continue;
                blueSources++;
                break;
            }
        }
        powers.sort(java.util.Comparator.reverseOrder());
        int unabsorbed = 0;
        for (int i = blockers; i < powers.size(); i++) unabsorbed += powers.get(i);
        int lifeAfter = p.getLife() - (p.getLife() + 1) / 2;
        if (kase.equals("clock-chump") && !(blockers == 2 && clockTotal == 12 && unabsorbed >= lifeAfter))
            throw new AssertionError("clock-chump premise: blockers=" + blockers + " clock=" + clockTotal
                    + " unabsorbed=" + unabsorbed + " lifeAfter=" + lifeAfter);
        if (kase.equals("clock-absorbed") && !(blockers == 2 && clockTotal > 0 && unabsorbed < lifeAfter))
            throw new AssertionError("clock-absorbed premise: blockers=" + blockers + " unabsorbed=" + unabsorbed);
        int expectedBlue = switch (kase) {
            case "one-blue-source" -> 1;
            case "petal-only-blue", "petal-consumed" -> 0;
            default -> 2;
        };
        if (blueSources != expectedBlue)
            throw new AssertionError("Blue-source premise wrong for " + kase + ": " + blueSources);
        boolean proposes = tightenProposes(kase);
        BenchRandomAudit.install(0); // Fixed constructed fixture, not a sampled opening.
        var proposed = new forge.ai.CubeDoomsdayPlan(p).nextAction();
        String action = proposed == null ? "none" : proposed.getHostCard().getName();
        System.out.println("TIGHTEN_PROPOSAL suite=tighten improved=" + improved + " policy=" + policy()
                + " seat=" + seat + " main2=" + main2 + " case=" + kase
                + " life=" + p.getLife() + " lifeAfterDoomsday=" + lifeAfter
                + " visibleClock=" + clockTotal + " blockers=" + blockers + " unabsorbedClock=" + unabsorbed
                + " ownBlueSources=" + blueSources + " mustMove=" + proposes + " action=" + action);
        if (improved && TIGHTEN_STRICT && proposes != action.equals("Doomsday"))
            throw new AssertionError("Tighten proposal mismatch: " + kase + " main2=" + main2 + " -> " + action);
        int steps = 0, limit = 1500;
        boolean doom = false, pile = false;
        int beforeOracle = -1, doomTurn = -1, oracleTurn = -1;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps++ < limit) {
            game.getPhaseHandler().mainLoopStep();
            if (!doom && has(p, ZoneType.Graveyard, "Doomsday")) { doom = true; doomTurn = game.getPhaseHandler().getTurn(); }
            pile |= doom && p.getCardsIn(ZoneType.Library).size() == 5;
            if (!game.getStack().isEmpty()) {
                var sa = game.getStack().peekAbility();
                if (sa.isSpell() && sa.getHostCard().getName().equals("Thassa's Oracle")) {
                    beforeOracle = p.getCardsIn(ZoneType.Library).size();
                    oracleTurn = game.getPhaseHandler().getTurn();
                }
            }
        }
        boolean oracleWin = p.getOutcome() != null && "Thassa's Oracle".equals(p.getOutcome().altWinSourceName);
        System.out.println("TIGHTEN_RESULT suite=tighten improved=" + improved + " policy=" + policy()
                + " seat=" + seat + " main2=" + main2 + " case=" + kase + " mustMove=" + proposes
                + " proposed=" + action + " doomsdayCast=" + doom + " doomsdayTurn=" + doomTurn
                + " fiveCardPile=" + pile + " libraryBeforeOracle=" + beforeOracle + " oracleTurn=" + oracleTurn
                + " won=" + p.hasWon() + " oracleWin=" + oracleWin + " gameOver=" + game.isGameOver()
                + " life=" + p.getLife() + " oppLife=" + opponent.getLife() + " steps=" + steps);
        if (steps >= limit) throw new AssertionError("Tighten step budget: " + kase);
        if (improved && TIGHTEN_STRICT) {
            if (proposes && !(doom && pile && oracleWin && beforeOracle == 4 && doomTurn == 1 && oracleTurn == 3))
                throw new AssertionError("Tightened route 2 did not execute: " + kase + " main2=" + main2);
            if (!proposes && doom) throw new AssertionError("Doomsday committed despite " + kase);
            if (!proposes && oracleWin) throw new AssertionError("Unexpected control Oracle win: " + kase);
        }
        if (!improved && TIGHTEN_STRICT && oracleWin)
            throw new AssertionError("Default arm unexpectedly won with Oracle: " + kase);
    }

    /** v49 discard ownership. Each case specifies exactly one premise action -
     * our own loot or our own Frantic Search, the ordinary decision this change
     * deliberately does NOT own - and the decision under test is only which
     * card the native controller then discards. The `doom-*` cases first let
     * the plan build its pile with no specified action at all, then loot at the
     * moment the v47 analysis's 16701482-s0 looted. */
    private static final List<String> DISCARD_CASES = List.of("doom-loot", "doom-loot-forced", "doom-no-hold",
            "storm-will", "storm-redundant", "storm-gate-short", "breach-freeze");

    private static boolean discardProtects(String kase) {
        return switch (kase) {
            case "doom-loot", "storm-will", "breach-freeze" -> true;
            default -> false;
        };
    }

    /** The card each case must still hold after its discard resolves. */
    private static String discardPiece(String kase) {
        return kase.startsWith("doom") ? "Thassa's Oracle"
                : kase.equals("breach-freeze") ? "Brain Freeze" : "Yawgmoth's Will";
    }

    private static void activate(final Game game, final Player player, final String host) {
        final Card card = player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(host))
                .findFirst().orElseThrow(() -> new AssertionError("missing battlefield card " + host));
        final var ability = card.getSpellAbilities().stream().filter(a -> !a.isSpell() && a.getApi() != null)
                .findFirst().orElseThrow(() -> new AssertionError("missing activated ability " + host));
        ability.setActivatingPlayer(player);
        if (!player.getController().playChosenSpellAbility(ability))
            throw new AssertionError("native controller rejected the specified loot premise " + host);
        settle(game);
    }

    private static void discardRun(int seat, String kase) {
        boolean doomsday = kase.startsWith("doom");
        List<Placement> own = new ArrayList<>(), other = new ArrayList<>();
        if (doomsday) {
            if (!kase.equals("doom-no-hold")) own.add(new Placement("Doomsday", ZoneType.Hand, false));
            for (int i = 0; i < 3; i++) own.add(new Placement("Swamp", ZoneType.Battlefield, false));
            for (int i = 0; i < 2; i++) own.add(new Placement("Island", ZoneType.Battlefield, false));
            // Jace is both the second public blue pip and the loot outlet, the
            // same double role it played in 16701482-s0.
            own.add(new Placement("Jace, Vryn's Prodigy", ZoneType.Battlefield, false));
            own.add(new Placement("Faerie Mastermind", ZoneType.Battlefield, false));
            // Two uncastable spares: neither is a land, a creature, an artifact
            // or an enchantment, so Forge's own discard ranking reaches
            // getWorstCreatureAI and picks the Oracle - the 482 decision.
            if (!kase.equals("doom-loot-forced")) {
                own.add(new Placement("Liliana of the Veil", ZoneType.Hand, false));
                own.add(new Placement("Wheel of Fortune", ZoneType.Hand, false));
            }
            own.add(new Placement("Thassa's Oracle", ZoneType.Library, false));
            for (int i = 0; i < 24; i++) own.add(new Placement("Forest", ZoneType.Library, false));
        } else {
            // Five Islands: enough lands that Forge's discard ranking treats a
            // four-drop as playable, and no black or white, so nothing in these
            // hands is castable except the Frantic Search the fixture casts.
            for (int i = 0; i < 5; i++) own.add(new Placement("Island", ZoneType.Battlefield, false));
            own.add(new Placement("Frantic Search", ZoneType.Hand, false));
            if (kase.equals("breach-freeze")) {
                own.add(new Placement("Underworld Breach", ZoneType.Battlefield, false));
                own.add(new Placement("Lotus Petal", ZoneType.Graveyard, false));
                own.add(new Placement("Brain Freeze", ZoneType.Hand, false));
            } else {
                own.add(new Placement("Yawgmoth's Will", ZoneType.Hand, false));
                if (kase.equals("storm-redundant")) own.add(new Placement("Yawgmoth's Will", ZoneType.Hand, false));
                if (!kase.equals("storm-gate-short"))
                    own.add(new Placement("Tendrils of Agony", ZoneType.Graveyard, false));
            }
            own.add(new Placement("Wrath of God", ZoneType.Hand, false));
            // Library order is placement order, so these two are exactly what
            // Frantic Search draws. Nothing that could change a gate is put
            // where the draw can reach it before the discard is made.
            for (int i = 0; i < 2; i++) own.add(new Placement("Damnation", ZoneType.Library, false));
            if (kase.equals("storm-gate-short"))
                own.add(new Placement("Tendrils of Agony", ZoneType.Library, false));
            for (int i = 0; i < 12; i++) own.add(new Placement("Forest", ZoneType.Library, false));
        }
        while (own.size() < 40) own.add(new Placement("Forest", ZoneType.Exile, false));
        while (other.size() < 40) other.add(new Placement("Forest", ZoneType.Library, false));
        Game game = fixtureGame(own, other, seat);
        Player p = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(0); // Fixed constructed fixture, not a sampled opening.
        discardSwaps(true);
        String piece = discardPiece(kase);
        boolean protect = discardProtects(kase);
        int steps = 0, limit = 1500;
        boolean doom = false, pile = false, looted = false;
        String handAtDiscard = "not-reached";
        boolean noHold = kase.equals("doom-no-hold");
        if (doomsday && !noHold) {
            // No specified action here: the plan casts Doomsday itself, and the
            // loop stops the instant the pile exists - the exact position the
            // v47 analysis recorded, with no intervening ordinary pass. Only
            // the improved arm has a plan, so only it reaches a pile; the
            // Default arm records that it did not, which is the witness.
            while (!game.isGameOver() && game.getPhaseHandler().getTurn() == 1 && steps++ < limit) {
                game.getPhaseHandler().mainLoopStep();
                if (!doom && has(p, ZoneType.Graveyard, "Doomsday")) doom = true;
                if (doom && game.getStack().isEmpty() && p.getCardsIn(ZoneType.Library).size() == 5) { pile = true; break; }
            }
            if (improved && !pile)
                throw new AssertionError("Discard fixture never reached the pile: " + kase);
        }
        // `doom-no-hold` and the storm/breach cases loot at once, in BOTH arms,
        // with no plan action ahead of them: their whole point is that the two
        // arms must make the same discard.
        if (doomsday ? pile || noHold : true) {
            handAtDiscard = cards(p, ZoneType.Hand);
            int libraryBefore = p.getCardsIn(ZoneType.Library).size();
            if (doomsday) activate(game, p, "Jace, Vryn's Prodigy");
            else cast(game, p, "Frantic Search");
            looted = true;
            // The premise must actually have drawn: the decision under test is
            // the discard that follows it, so a silent no-op would make every
            // assertion below vacuous.
            int drew = libraryBefore - p.getCardsIn(ZoneType.Library).size();
            if (drew != (doomsday ? 1 : 2))
                throw new AssertionError("Loot premise did not draw for " + kase + ": " + drew);
        }
        int swaps = discardSwaps(true);
        boolean pieceHeld = has(p, ZoneType.Hand, piece) || has(p, ZoneType.Battlefield, piece);
        String afterDiscard = cards(p, ZoneType.Hand);
        String graveyard = cards(p, ZoneType.Graveyard);
        System.out.println("DISCARD_PROPOSAL suite=discard improved=" + improved + " policy=" + policy()
                + " seat=" + seat + " main2=" + main2 + " case=" + kase + " piece=" + piece.replace(' ', '_')
                + " mustProtect=" + protect + " looted=" + looted + " pileBuilt=" + pile
                + " handBeforeLoot=[" + handAtDiscard.replace(' ', '_') + "]"
                + " handAfterDiscard=[" + afterDiscard.replace(' ', '_') + "]"
                + " graveyard=[" + graveyard.replace(' ', '_') + "] pieceHeld=" + pieceHeld
                + " discardSwaps=" + swaps);
        if (improved && DISCARD_STRICT && looted) {
            if (protect && !(pieceHeld && swaps >= 1))
                throw new AssertionError("Discard ownership did not keep " + piece + ": " + kase + " swaps=" + swaps);
            if (!protect && swaps != 0)
                throw new AssertionError("Discard ownership fired where no plan gate is active: " + kase);
        }
        if (!improved && DISCARD_STRICT && swaps > 0)
            throw new AssertionError("Default arm must own no discard: " + kase);
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps++ < limit)
            game.getPhaseHandler().mainLoopStep();
        boolean oracleWin = p.getOutcome() != null && "Thassa's Oracle".equals(p.getOutcome().altWinSourceName);
        System.out.println("DISCARD_RESULT suite=discard improved=" + improved + " policy=" + policy()
                + " seat=" + seat + " main2=" + main2 + " case=" + kase + " piece=" + piece.replace(' ', '_')
                + " mustProtect=" + protect + " discardSwaps=" + swaps + " pieceHeld=" + pieceHeld
                + " doomsdayCast=" + doom + " fiveCardPile=" + pile
                + " won=" + p.hasWon() + " oracleWin=" + oracleWin + " gameOver=" + game.isGameOver()
                + " life=" + p.getLife() + " oppLife=" + opponent.getLife() + " steps=" + steps);
        if (steps >= limit) throw new AssertionError("Discard step budget: " + kase);
        if (improved && DISCARD_STRICT && kase.equals("doom-loot") && !oracleWin)
            throw new AssertionError("Kept Oracle but the game did not finish: " + kase + " main2=" + main2);
        if (improved && DISCARD_STRICT && kase.equals("doom-loot-forced") && oracleWin)
            throw new AssertionError("Forced discard must not be overridden into a win: " + kase);
    }

    public static void main(final String[] args) {
        try {
            improved = args.length > 1 && args[1].equals("improved");
            String suite = args.length > 3 ? args[3] : "none";
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[] { IGuiBase.class }, (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "default-doomsday-native-baseline-v1";
                        default -> throw new AssertionError(method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            if (suite.equals("tighten")) {
                for (boolean second : new boolean[] {false, true}) {
                    main2 = second;
                    for (int seat = 0; seat < 2; seat++) for (String kase : TIGHTEN_CASES) tightenRun(seat, kase);
                }
                System.out.println("TIGHTEN_SUITE_COMPLETE suite=tighten improved=" + improved
                        + " policy=" + policy() + " cases=" + 4 * TIGHTEN_CASES.size());
                return;
            }
            if (suite.equals("discard")) {
                for (boolean second : new boolean[] {false, true}) {
                    main2 = second;
                    for (int seat = 0; seat < 2; seat++) for (String kase : DISCARD_CASES) discardRun(seat, kase);
                }
                System.out.println("DISCARD_SUITE_COMPLETE suite=discard improved=" + improved
                        + " policy=" + policy() + " cases=" + 4 * DISCARD_CASES.size());
                return;
            }
            if (suite.equals("ritual")) {
                for (boolean second : new boolean[] {false, true}) {
                    main2 = second;
                    for (int seat = 0; seat < 2; seat++) for (String kase : RITUAL_CASES) ritualRun(seat, kase);
                }
                System.out.println("RITUAL_SUITE_COMPLETE suite=ritual improved=" + improved
                        + " policy=" + policy() + " cases=" + 4 * RITUAL_CASES.size());
                return;
            }
            if (suite.equals("nodraw") || suite.equals("passturn")) {
                boolean passTurn = suite.equals("passturn");
                for (boolean second : new boolean[] {false, true}) {
                    main2 = second;
                    for (int seat = 0; seat < 2; seat++)
                        for (String kase : passTurn ? PASSTURN_CASES : NODRAW_CASES) naturalRun(seat, passTurn, kase);
                }
                System.out.println("NATURAL_SUITE_COMPLETE suite=" + suite + " improved=" + improved
                        + " policy=" + policy() + " cases=" + 4 * (passTurn ? PASSTURN_CASES.size() : NODRAW_CASES.size()));
                return;
            }
            if(suite.equals("devotion")) {
                for(boolean second:new boolean[]{false,true}) {
                    main2=second;
                    for(int seat=0;seat<2;seat++) {
                        for(String permanent:List.of("Jace, Vryn's Prodigy","Faerie Mastermind","Narset, Parter of Veils"))
                            for(boolean hand:new boolean[]{false,true})devotionRun(seat,permanent,hand,"none");
                        for(String blocker:List.of("no-devotion","one-island","short-blue","gush-exiled","oracle-exiled","draw-limit","cannot-win","counterspell",
                                "facedown-devotion","island-devotion","remove-devotion-doom","remove-devotion-gush"))
                            devotionRun(seat,"Jace, Vryn's Prodigy",false,blocker);
                    }
                }
                System.out.println("DEVOTION_SUITE_COMPLETE");return;
            }
            if (suite.startsWith("availability")) {
                for(int seat=0;seat<2;seat++) for(String plan:List.of("doomsday","kitten","recovery"))
                    for(String variant:List.of("available","oracle-visible-exile","oracle-facedown-bf","oracle-facedown-exile",
                            "oracle-facedown-exile-known","filler-facedown-bf","filler-facedown-exile","filler-facedown-exile-known",
                            "oracle-graveyard-with-unknown"))
                        availabilityRun(seat,plan,variant,suite.equals("availability"));
                return;
            }
            if (suite.equals("cold")) {
                for (boolean second : new boolean[] {false, true}) {
                    main2 = second;
                    for (int seat = 0; seat < 2; seat++) {
                        for (String route : List.of("star", "gush", "recall", "oracle")) coldRun(seat, route, "none", false);
                        coldRun(seat, "star", "none", true);
                        for (String blocker : List.of("oracle-exiled", "oracle-graveyard", "gush-exiled", "no-star", "short-mana",
                                "no-blue", "draw-limit", "null-rod", "cannot-win", "counterspell"))
                            coldRun(seat, "star", blocker, false);
                    }
                }
                return;
            }
            if (args.length > 2 && args[2].equals("witness")) legalityWitness();
            List<String> cases = suite.equals("all-routes") ? List.of("positives", "gush", "positives-main2", "gush-main2", "gush-one-island", "gush-short-mana",
                    "gush-no-star", "gush-gush-exiled", "gush-counterspell", "gush-draw-limit", "gush-cannot-win") : List.of(suite);
            for (String selected : cases) {
                main2 = selected.endsWith("-main2");
                if (main2) selected = selected.substring(0, selected.length() - 6);
                gushRoute = selected.equals("gush") || selected.startsWith("gush-");
                control = selected.equals("gush") || selected.equals("positives") ? "none" : gushRoute ? selected.substring(5) : selected;
                for (int seat = 0; seat < 2; seat++) {
                    run(seat, true);
                    run(seat, false);
                }
            }
        } catch (final Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
