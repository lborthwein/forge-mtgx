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
