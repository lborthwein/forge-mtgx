package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Observation of native decisions about the cube's draw-out finishes OUTSIDE
 * Doomsday, from prepared, exactly registered positions - design-v66, matrix
 * families U (Oath of Druids + Jace, Wielder of Mysteries) and V (Griselbrand +
 * Jace / Laboratory Maniac, and the Sheoldred-class drain).
 *
 * The host supplies the board and nothing else: every cast, activation, target,
 * trigger, block and attack is the native AI's. The treated arm is the
 * cube-combo controller under policy v66; the baseline arm is Forge's Default AI
 * on the identical board. A matched control arm runs this same source against
 * the frozen v61 classes (probe-379), where {@code forge.ai.CubeDrawOutPlan}
 * does not exist - which is why the plan is reached REFLECTIVELY throughout, so
 * one source compiles and runs against both trees.
 *
 * These are prepared positions, not natural games: no seed sweep, no win rate
 * and no playing-strength claim. Registered in
 * runs/2026-09-12-draw-out-v66/registration.md before any run; the
 * DRAWOUT_RESULT rows are scored there by readout.mjs, not by this JVM.
 */
public final class CubeDrawOutSmoke {
    private static final String GRIS = "Griselbrand";
    private static final String JACE = "Jace, Wielder of Mysteries";
    private static final String LABMAN = "Laboratory Maniac";
    private static final String SHEOLDRED = "Sheoldred, the Apocalypse";
    private static final String OATH = "Oath of Druids";
    private static final String WHEEL = "Wheel of Fortune";
    private static final String PYRO = "Young Pyromancer";

    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** The fourteen prepared cases of design-v66 section 9. */
    private static final List<String> CASES = List.of(
            "gris-jace",
            "gris-labman",
            "gris-sheoldred-jace",
            "wheel-sheoldred",
            "oath-jace",
            "labman-hand",
            "gris-sheoldred",
            "budget-short",
            "no-finisher",
            "no-engine",
            "oath-precondition",
            "oath-library-creatures",
            "single-activation",
            // Appended after the first thirteen so their audit seeds - and
            // therefore their whole receipt - cannot move.
            "gris-jace-narrow");

    /** The design's seat-1 variants: the same positions from the seat that acts
     * after the opponent's turn 1. */
    private static final List<String> SEAT1 = List.of("gris-jace", "oath-jace");

    private static int caseIndex(String name) { return CASES.indexOf(name); }

    /** The route this case is about, for the receipt. */
    private static String routeOf(String name) {
        return switch (name) {
            case "gris-jace", "gris-jace-narrow", "gris-labman", "gris-sheoldred-jace",
                 "budget-short", "single-activation" -> "engine";
            case "wheel-sheoldred", "gris-sheoldred" -> "drain";
            case "labman-hand" -> "finisher-cast";
            case "oath-jace", "oath-precondition", "oath-library-creatures" -> "oath";
            default -> "none";
        };
    }

    /** What the plan, consulted directly on the prepared board, must propose.
     * The Oath cases take no {@code nextAction}, so they register "none" here
     * and are scored on their accept counter instead. */
    private static String proposalOf(String name) {
        return switch (name) {
            case "gris-jace", "gris-jace-narrow", "gris-labman", "gris-sheoldred-jace",
                 "single-activation" -> GRIS;
            case "wheel-sheoldred" -> WHEEL;
            case "labman-hand" -> LABMAN;
            default -> "none";
        };
    }

    /** The registered decline token for a case whose proposal is "none". */
    private static String declineOf(String name) {
        return switch (name) {
            case "gris-sheoldred" -> "drain-no-engine";
            case "budget-short" -> "life-budget";
            case "no-finisher" -> "no-finisher";
            // The Oath rows carry a live Jace and no draw engine, so the
            // nextAction chain declines on the engine clause; route O is not a
            // nextAction at all and is scored on its accept counter.
            case "no-engine", "oath-jace", "oath-precondition", "oath-library-creatures" -> "no-engine";
            case "single-activation" -> "single-draw-owned-elsewhere";
            default -> "none";
        };
    }

    /** Turns this case is allowed to run for, counted from the prepared turn.
     * The Oath cases need our NEXT upkeep - the opponent's whole turn plus
     * ours - because the trigger that mills us is an upkeep trigger. */
    private static int extraTurns(String name) {
        return routeOf(name).equals("oath") ? 2 : 1;
    }

    private record Placement(String name, ZoneType zone, boolean tapped) {}

    private static void add(List<Placement> out, String name, ZoneType zone, boolean tapped) {
        out.add(new Placement(name, zone, tapped));
    }

    private static void lands(List<Placement> out, int forests, int islands, int mountains) {
        for (int i = 0; i < forests; i++) add(out, "Forest", ZoneType.Battlefield, false);
        for (int i = 0; i < islands; i++) add(out, "Island", ZoneType.Battlefield, false);
        for (int i = 0; i < mountains; i++) add(out, "Mountain", ZoneType.Battlefield, false);
    }

    private static void library(List<Placement> out, int cards) {
        for (int i = 0; i < cards; i++) add(out, "Forest", ZoneType.Library, false);
    }

    /** Our own forty. Every Griselbrand, Sheoldred and Young Pyromancer that is
     * not the subject of a row enters TAPPED: an untapped body attacks in the
     * combat step between MAIN1 and MAIN2 and would move the life totals these
     * rows are about, which is a second variable. Griselbrand's draw ability has
     * no tap cost, so tapping it changes nothing this plan reads. */
    private static List<Placement> placements(String name) {
        List<Placement> own = new ArrayList<>();
        switch (name) {
            case "gris-jace" -> {
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, GRIS, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 13);
            }
            case "gris-jace-narrow" -> {
                // Eight cards: route J cannot reach it (one resolution draws
                // seven, which is not more than eight) and the ordinary AI's own
                // value-draw heuristics are measured against this row rather
                // than assumed. Two activations cost 14 of our 20 life.
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, GRIS, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 8);
            }
            case "gris-labman" -> {
                // Laboratory Maniac carries the IDENTICAL printed replacement
                // line as Jace; this row is what shows the detector is a
                // property and not a name.
                add(own, LABMAN, ZoneType.Battlefield, true);
                add(own, GRIS, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 13);
            }
            case "gris-sheoldred-jace" -> {
                // Twenty cards and twenty life: three activations cost 21 life,
                // so this row is winnable ONLY if Sheoldred's printed own-draw
                // life gain is credited. Without it the budget is 20 -> 13 -> 6
                // -> -1 and the plan must decline.
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, GRIS, ZoneType.Battlefield, true);
                add(own, SHEOLDRED, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 20);
            }
            case "wheel-sheoldred" -> {
                // Route D. Wheel of Fortune draws seven for EACH player
                // (Defined$ Player), so Sheoldred's opponent-draw half fires
                // seven times for 14 - exactly the opponent's prepared life.
                add(own, SHEOLDRED, ZoneType.Battlefield, true);
                add(own, WHEEL, ZoneType.Hand, false);
                lands(own, 2, 0, 1);
                library(own, 20);
            }
            case "oath-jace" -> {
                // No creature card anywhere in these forty, so "creatures
                // possibly remaining in our library" is zero from the registered
                // deck alone and the library is never read.
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, OATH, ZoneType.Battlefield, false);
                lands(own, 4, 0, 0);
                library(own, 20);
            }
            case "labman-hand" -> {
                // Route F: the replacement is in HAND, which v51's route J
                // deliberately never sees, and one Griselbrand activation would
                // empty a six-card library once it is live.
                add(own, LABMAN, ZoneType.Hand, false);
                add(own, GRIS, ZoneType.Battlefield, true);
                lands(own, 1, 2, 0);
                library(own, 6);
            }
            case "gris-sheoldred" -> {
                // design-v66 section 0. Catalogue row 2498-4322 produces
                // near-infinite DRAW and LIFEGAIN and no win: Sheoldred's drain
                // half needs an OPPONENT's draw and Griselbrand draws only for
                // us. The plan must refuse, and win nothing.
                add(own, GRIS, ZoneType.Battlefield, true);
                add(own, SHEOLDRED, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 20);
            }
            case "budget-short" -> {
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, GRIS, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 34);
            }
            case "no-finisher" -> {
                add(own, GRIS, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 20);
            }
            case "no-engine" -> {
                add(own, JACE, ZoneType.Battlefield, false);
                lands(own, 4, 0, 0);
                library(own, 13);
            }
            case "oath-precondition" -> {
                // We control as many creatures as the opponent, so the trigger's
                // own ValidTgts (an opponent with MORE creatures than the active
                // player) has no legal target and the dig is never offered.
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, OATH, ZoneType.Battlefield, false);
                add(own, PYRO, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 20);
            }
            case "oath-library-creatures" -> {
                // One creature card still possibly in our library. The plan must
                // NOT answer, and the unchanged ordinary DigUntilAi answer -
                // which is "yes" on this board - must stand.
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, OATH, ZoneType.Battlefield, false);
                lands(own, 4, 0, 0);
                library(own, 19);
                // Added LAST, so it is the BOTTOM card: no native draw, mill or
                // Jace +1 the ordinary AI takes on the way to our next upkeep
                // can pull it out of the library and make the row vacuous.
                add(own, PYRO, ZoneType.Library, false);
            }
            case "single-activation" -> {
                // One activation empties this library, so v51's route J owns the
                // board and this plan must decline - the clause that makes the
                // two plans provably disjoint.
                add(own, JACE, ZoneType.Battlefield, false);
                add(own, GRIS, ZoneType.Battlefield, true);
                lands(own, 4, 0, 0);
                library(own, 5);
            }
            default -> throw new AssertionError("unknown case " + name);
        }
        while (own.size() < 40) add(own, "Forest", ZoneType.Exile, false);
        if (own.size() != 40) throw new AssertionError("fixture must register forty: " + name + " " + own.size());
        return own;
    }

    /** The opponent: a plain library, plus the single creature the Oath
     * precondition needs on the public board. */
    private static List<Placement> opposing(String name) {
        List<Placement> other = new ArrayList<>();
        if (routeOf(name).equals("oath")) add(other, PYRO, ZoneType.Battlefield, false);
        while (other.size() < 40) add(other, "Forest", ZoneType.Library, false);
        return other;
    }

    private static Deck deck(List<Placement> placements) {
        Deck result = new Deck("draw-out fixture");
        for (Placement p : placements) result.getMain().add(Objects.requireNonNull(
                FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), 1);
        if (result.getMain().countAll() != 40) throw new AssertionError("fixture must register forty");
        return result;
    }

    private static void populate(Player player, List<Placement> placements) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(
                    FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setTapped(p.tapped());
            // Every prepared body has been under our control since before this
            // turn; summoning sickness is not what any of these rows measure.
            card.setSickness(false);
            // A placed planeswalker needs its printed loyalty or
            // checkStateEffects puts it straight into the graveyard.
            if (p.name().equals(JACE)) card.setCounters(CounterEnumType.LOYALTY, 4);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }

    // ------------------------------------------------------- reflective reach

    /** A package-private diagnostic counter of the plan, read reflectively so
     * this same source runs against the frozen v61 classes, where the class does
     * not exist, and records -1 instead of failing. */
    private static int counter(String owner, String field, boolean reset) {
        try {
            var handle = Class.forName(owner).getDeclaredField(field);
            handle.setAccessible(true);
            int value = handle.getInt(null);
            if (reset) handle.setInt(null, 0);
            return value;
        } catch (final ReflectiveOperationException | LinkageError absent) {
            return -1;
        }
    }

    private record Proposal(String action, String reason) {}

    /** Consult the plan directly on the prepared board, in BOTH arms, exactly as
     * the v51 jace suite's proposal probe does - and reflectively, so the same
     * source can be compiled against a tree that has no such class. */
    private static Proposal proposal(Player player) {
        try {
            Class<?> type = Class.forName("forge.ai.CubeDrawOutPlan");
            Object plan = type.getConstructor(Player.class).newInstance(player);
            Object action = type.getMethod("nextAction").invoke(plan);
            String reason = String.valueOf(type.getMethod("declineReason").invoke(plan));
            String named = action == null ? "none"
                    : ((forge.game.spellability.SpellAbility) action).getHostCard().getName();
            return new Proposal(named.replace(' ', '_'), reason.replace(' ', '_'));
        } catch (final ReflectiveOperationException | LinkageError absent) {
            return new Proposal("absent", "absent");
        }
    }

    private static String policy() { return forge.ai.CubeComboAi.VERSION; }

    /** Creature cards in this player's own REGISTERED main deck - the same
     * quantity route O's forecast starts from, computed independently here so a
     * row's premise is a receipt and not an assumption. */
    private static int deckCreatures(Player player) {
        int total = 0;
        for (var entry : player.getRegisteredPlayer().getDeck().getMain())
            if (entry.getKey().getRules().getType().isCreature()) total += entry.getValue();
        return total;
    }

    private static final List<ZoneType> LOOKUP = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Stack, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library, ZoneType.Command);

    /** The one non-token card of this name this player owns, found afresh every
     * time it is needed: Forge replaces the Card object on every zone change. */
    private static String zoneOf(Game game, Player player, String name) {
        for (ZoneType z : LOOKUP) for (Card c : game.getCardsIn(z))
            if (c.getOwner() == player && !c.isToken() && c.getName().equals(name))
                return c.getZone() == null ? "missing" : c.getZone().getZoneType().name();
        return "missing";
    }

    private static void run(boolean improved, int seat, String name) {
        List<Placement> own = placements(name), other = opposing(name);
        List<List<Placement>> layouts = seat == 0 ? List.of(own, other) : List.of(other, own);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(layouts.get(s))).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : defaultAi(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native draw-out fixture").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, own);
        populate(opponent, other);
        game.setAge(GameStage.Play);
        // Seat 0 is prepared on its own turn 1. Seat 1 is prepared at its own
        // MAIN1 with the turn counter at 2 - "after the opponent's turn 1" -
        // and that turn is skipped, not played.
        int startTurn = seat == 0 ? 1 : 2;
        Player goesFirst = seat == 0 ? player : opponent;
        game.getPhaseHandler().setupFirstTurn(goesFirst,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        if (name.equals("wheel-sheoldred")) opponent.setLife(14, null);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();

        // Premises, computed from the same public and own-visible reads the plan
        // uses, and asserted BEFORE the plan is consulted.
        int librarySize = player.getCardsIn(ZoneType.Library).size();
        int expectedLibrary = switch (name) {
            case "gris-jace", "gris-labman", "no-engine" -> 13;
            case "gris-sheoldred-jace", "wheel-sheoldred", "oath-jace", "gris-sheoldred",
                 "no-finisher", "oath-precondition", "oath-library-creatures" -> 20;
            case "labman-hand" -> 6;
            case "gris-jace-narrow" -> 8;
            case "budget-short" -> 34;
            case "single-activation" -> 5;
            default -> -1;
        };
        if (librarySize != expectedLibrary)
            throw new AssertionError("Library premise " + name + ": " + librarySize);
        if (player.getLife() != 20) throw new AssertionError("Life premise " + name + ": " + player.getLife());
        if (name.equals("wheel-sheoldred") && opponent.getLife() != 14)
            throw new AssertionError("Opponent life premise " + name + ": " + opponent.getLife());
        if (!player.canDrawAmount(librarySize + 1))
            throw new AssertionError("Draw premise wrong for " + name);
        if (routeOf(name).equals("oath")) {
            int mine = player.getCreaturesInPlay().size(), theirs = opponent.getCreaturesInPlay().size();
            boolean more = theirs > mine;
            if (more != !name.equals("oath-precondition"))
                throw new AssertionError("Oath precondition premise " + name + ": " + mine + " vs " + theirs);
        }

        BenchRandomAudit.install(66000L + seat * 100L + caseIndex(name));
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        String expectedProposal = proposalOf(name), expectedDecline = declineOf(name);
        System.out.println("DRAWOUT_FIXTURE " + key + " policy=" + policy()
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0 route=" + routeOf(name)
                + " startTurn=" + startTurn + " bound=" + (startTurn + extraTurns(name))
                + " library=" + librarySize + " life=" + player.getLife()
                + " oppLife=" + opponent.getLife()
                + " deckCreatures=" + deckCreatures(player) + " creaturesInPlay=" + player.getCreaturesInPlay().size()
                + " oppCreaturesInPlay=" + opponent.getCreaturesInPlay().size()
                + " expected=" + expectedProposal.replace(' ', '_') + " expectedDecline=" + expectedDecline);
        Proposal proposed = proposal(player);
        System.out.println("DRAWOUT_PROPOSAL " + key + " policy=" + policy()
                + " action=" + proposed.action() + " reason=" + proposed.reason());
        // The probe above constructs a plan in BOTH arms, so reset here: every
        // recorded counter is the in-game count only.
        counter("forge.ai.CubeDrawOutPlan", "drawOutActions", true);
        counter("forge.ai.CubeDrawOutPlan", "oathAccepts", true);
        counter("forge.ai.CubeDrawOutPlan", "oathDeclines", true);
        counter("forge.ai.CubeDoomsdayPlan", "jaceFinishes", true);

        int bound = startTurn + extraTurns(name);
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 1200) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() != player) continue;
                System.out.println("DRAWOUT_STACK " + key + " step=" + steps
                        + " source=" + sa.getHostCard().getName().replace(' ', '_')
                        + " api=" + sa.getApi() + " trigger=" + sa.isTrigger() + " spell=" + sa.isSpell()
                        + " costs=" + sa.getPayCosts());
            }
        }
        if (steps >= 1200) throw new AssertionError("native step budget exhausted " + key);
        boolean replacementWin = player.hasWon() && player.getCardsIn(ZoneType.Library).isEmpty();
        System.out.println("DRAWOUT_RESULT " + key + " policy=" + policy()
                + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " replacementWin=" + replacementWin
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " drawOutActions=" + counter("forge.ai.CubeDrawOutPlan", "drawOutActions", true)
                + " oathAccepts=" + counter("forge.ai.CubeDrawOutPlan", "oathAccepts", true)
                + " oathDeclines=" + counter("forge.ai.CubeDrawOutPlan", "oathDeclines", true)
                + " jaceFinishes=" + counter("forge.ai.CubeDoomsdayPlan", "jaceFinishes", true)
                + " library=" + player.getCardsIn(ZoneType.Library).size()
                + " life=" + player.getLife() + " oppLife=" + opponent.getLife()
                + " grisZone=" + zoneOf(game, player, GRIS)
                + " jaceZone=" + zoneOf(game, player, JACE)
                + " labmanZone=" + zoneOf(game, player, LABMAN)
                + " wheelZone=" + zoneOf(game, player, WHEEL)
                + " oathZone=" + zoneOf(game, player, OATH));
    }

    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }

    private record Row(int seat, String name) {}

    private static List<Row> rows(String suite) {
        List<Row> rows = new ArrayList<>();
        List<String> selected = switch (suite) {
            case "positives" -> CASES.stream().filter(c -> declineOf(c).equals("none")).toList();
            case "negatives" -> CASES.stream().filter(c -> !declineOf(c).equals("none")).toList();
            default -> CASES;
        };
        for (String name : selected) rows.add(new Row(0, name));
        if (suite.equals("drawout")) for (String name : SEAT1) rows.add(new Row(1, name));
        return rows;
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "draw-out-native-fixture-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String card : List.of(GRIS, JACE, LABMAN, SHEOLDRED, OATH, WHEEL, PYRO,
                    "Forest", "Island", "Mountain"))
                StaticData.instance().attemptToLoadCard(card);
            List<Row> rows = rows(args.length > 2 ? args[2] : "drawout");
            for (Row row : rows) run(args[1].equals("improved"), row.seat(), row.name());
            System.out.println("DRAWOUT_SUITE_COMPLETE cases=" + rows.size());
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
