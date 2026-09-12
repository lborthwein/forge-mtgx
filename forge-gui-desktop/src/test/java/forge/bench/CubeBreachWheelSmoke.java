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
 * for the v64 Breach routes: High Tide and Frantic Search as fuel for the v41
 * Brain Freeze terminal, and the Wheel of Fortune loop with its Brain Freeze,
 * Tendrils of Agony and Thassa's Oracle terminals, with and without our own
 * Hullbreacher.
 *
 * The host supplies the board and nothing else: every cast, cost payment,
 * target, trigger and block is native Forge's. The treated arm is the
 * cube-combo controller; the baseline arm is Forge's Default AI on the
 * identical board. A matched control arm runs this same source against the
 * frozen v61 classes (probe-379), so "v64 moved the line" is separable from
 * "the new fixture rows moved it".
 *
 * v75 adds a SECOND suite, `sequence`, in its own regression group
 * `breachseq`. MOVE / HOLD / SEAT1 and therefore the whole `wheel` suite are
 * untouched, and a `sequence` row's BenchRandomAudit seed is 40 + its index, so
 * no existing row's seed moves. The new rows cover R0 (the plan's own Breach
 * entry in the same turn as the Tide), the Brain Freeze tutor selection its
 * completing name and v74's convertibility gate now allow, and the colourless
 * half of the wheel forecast's pool.
 *
 * These are prepared positions, not natural games: no seed sweep, no win rate
 * and no playing-strength claim. The WHEEL_RESULT rows are scored by the run's
 * readout against its registration, not by this JVM. */
public final class CubeBreachWheelSmoke {
    private static final String BREACH = "Underworld Breach";
    private static final String WHEEL = "Wheel of Fortune";
    private static final String TIDE = "High Tide";
    private static final String FRANTIC = "Frantic Search";
    private static final String FREEZE = "Brain Freeze";
    private static final String TENDRILS = "Tendrils of Agony";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String HULL = "Hullbreacher";
    private static final String NARSET = "Narset, Parter of Veils";
    private static final String LED = "Lion's Eye Diamond";
    private static final String TWISTER = "Timetwister";
    private static final String TREASURE = "Treasure Token";
    /** v75. */
    private static final String DEMONIC = "Demonic Tutor";
    private static final String MYSTICAL = "Mystical Tutor";
    private static final String LOTUS = "Black Lotus";
    private static final String SOL_RING = "Sol Ring";
    private static final String CRYPT = "Mana Crypt";
    private static final String TOMB = "Ancient Tomb";
    /** The card the ordinary AI's own tail prefers over a {1}{U} instant: a
     * creature, and the most expensive thing offered. */
    private static final String DRAGON = "Shivan Dragon";
    private static final String LEYLINE = "Leyline of Sanctity";

    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private static final List<ZoneType> LOOKUP = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Stack, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library, ZoneType.Command);

    /** design.md section 6, in the design's order. */
    private static final List<String> MOVE = List.of(
            "tide-freeze", "frantic-tide", "wheel-freeze", "wheel-tendrils",
            "wheel-hullbreacher", "wheel-oracle", "wheel-breach-in-hand");
    private static final List<String> HOLD = List.of(
            "no-terminal", "decks-us", "opponent-hullbreacher", "opponent-narset",
            "timetwister", "tide-no-gain", "freeze-route-wins", "short-fuel");
    /** The two rows also observed from the seat that acts after the opponent's
     * first turn. */
    private static final List<String> SEAT1 = List.of("tide-freeze", "wheel-freeze");

    /** v75, design.md section 4, in the design's order. The first five must
     * move; the last three must not. */
    private static final List<String> SEQUENCE = List.of(
            "sequence-tide", "sequence-frantic", "tutor-demonic", "tutor-mystical",
            "wheel-colorless", "sequence-no-islands", "tutor-no-fuel", "tutor-hexproof");
    /** The v75 row also observed from the seat that acts second. */
    private static final List<String> SEQUENCE_SEAT1 = List.of("sequence-tide");

    private static int caseIndex(String name) {
        if (SEQUENCE.contains(name)) return 40 + SEQUENCE.indexOf(name);
        return MOVE.contains(name) ? MOVE.indexOf(name) : 20 + HOLD.indexOf(name);
    }

    /** The opponent's starting life; 20 unless the row's terminal is a drain. */
    private static int opponentLife(String name) {
        return switch (name) {
            case "wheel-tendrils" -> 4;
            case "wheel-breach-in-hand" -> 6;
            default -> 20;
        };
    }

    private record Placement(String name, ZoneType zone, boolean tapped) {}

    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone, false));
    }

    private static void one(List<Placement> into, String name, ZoneType zone) {
        into.add(new Placement(name, zone, false));
    }

    /** Our own 40. Every row is padded to exactly 40 with library Forests, and
     * graveyard Forests are the fuel: a land in the graveyard can be exiled to
     * pay an escape cost and can never be cast back out of it, so the fuel
     * count cannot drift because the ordinary AI replayed a cantrip. */
    private static List<Placement> own(String name) {
        List<Placement> own = new ArrayList<>();
        switch (name) {
            case "tide-freeze", "tide-no-gain" -> {
                one(own, BREACH, ZoneType.Battlefield);
                one(own, LED, ZoneType.Battlefield);
                add(own, 4, "Island", ZoneType.Battlefield);
                one(own, TIDE, ZoneType.Hand);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, 12, "Forest", ZoneType.Graveyard);
                // Three cards: too few for the v41 route's self-mill refuel, so
                // the extra blue is the only thing that can carry the line.
                add(own, 3, "Forest", ZoneType.Library);
                add(own, 17, "Forest", ZoneType.Exile);
            }
            // R2's board: a library big enough for the v41 route's self-mill
            // refuel, so the row is a GUARD - the Tide and the Frantic Search
            // both fire, and the matched v61 control reaches the same outcome
            // by a different line.
            case "frantic-tide" -> {
                one(own, BREACH, ZoneType.Battlefield);
                one(own, LED, ZoneType.Battlefield);
                add(own, 4, "Island", ZoneType.Battlefield);
                one(own, TIDE, ZoneType.Hand);
                one(own, FRANTIC, ZoneType.Hand);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, 12, "Forest", ZoneType.Graveyard);
                add(own, 12, "Forest", ZoneType.Library);
                add(own, 7, "Forest", ZoneType.Exile);
            }
            // The family-E rows carry NO Lotus-type engine: with one, the v41
            // route acts first and grinds the fuel away (design.md Amendment 1).
            case "wheel-freeze", "decks-us", "opponent-hullbreacher", "opponent-narset", "short-fuel" -> {
                one(own, BREACH, ZoneType.Battlefield);
                add(own, 5, "Mountain", ZoneType.Battlefield);
                add(own, 3, "Island", ZoneType.Battlefield);
                one(own, WHEEL, ZoneType.Graveyard);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, name.equals("short-fuel") ? 2 : 5, "Forest", ZoneType.Graveyard);
                add(own, 2, "Forest", ZoneType.Hand);
                add(own, name.equals("decks-us") ? 5 : 14, "Forest", ZoneType.Library);
            }
            case "no-terminal" -> {
                one(own, BREACH, ZoneType.Battlefield);
                add(own, 5, "Mountain", ZoneType.Battlefield);
                add(own, 3, "Island", ZoneType.Battlefield);
                one(own, WHEEL, ZoneType.Graveyard);
                add(own, 8, "Forest", ZoneType.Graveyard);
                add(own, 2, "Forest", ZoneType.Hand);
                add(own, 14, "Forest", ZoneType.Library);
            }
            case "timetwister" -> {
                one(own, BREACH, ZoneType.Battlefield);
                add(own, 5, "Mountain", ZoneType.Battlefield);
                add(own, 3, "Island", ZoneType.Battlefield);
                one(own, TWISTER, ZoneType.Graveyard);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, 5, "Forest", ZoneType.Graveyard);
                add(own, 2, "Forest", ZoneType.Hand);
                add(own, 14, "Forest", ZoneType.Library);
            }
            case "wheel-hullbreacher" -> {
                one(own, BREACH, ZoneType.Battlefield);
                one(own, HULL, ZoneType.Battlefield);
                add(own, 5, "Mountain", ZoneType.Battlefield);
                add(own, 3, "Island", ZoneType.Battlefield);
                one(own, WHEEL, ZoneType.Graveyard);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, 5, "Forest", ZoneType.Graveyard);
                add(own, 2, "Forest", ZoneType.Hand);
                add(own, 14, "Forest", ZoneType.Library);
            }
            case "wheel-tendrils" -> {
                one(own, BREACH, ZoneType.Battlefield);
                one(own, LED, ZoneType.Battlefield);
                add(own, 3, "Mountain", ZoneType.Battlefield);
                add(own, 4, "Swamp", ZoneType.Battlefield);
                one(own, WHEEL, ZoneType.Graveyard);
                one(own, TENDRILS, ZoneType.Graveyard);
                add(own, 8, "Forest", ZoneType.Graveyard);
                add(own, 2, "Forest", ZoneType.Hand);
                add(own, 14, "Forest", ZoneType.Library);
            }
            case "wheel-oracle" -> {
                one(own, BREACH, ZoneType.Battlefield);
                add(own, 5, "Mountain", ZoneType.Battlefield);
                add(own, 3, "Island", ZoneType.Battlefield);
                one(own, WHEEL, ZoneType.Graveyard);
                one(own, ORACLE, ZoneType.Graveyard);
                add(own, 5, "Forest", ZoneType.Graveyard);
                add(own, 2, "Forest", ZoneType.Hand);
                // Exactly seven: one wheel draws the last card and leaves the
                // library empty without ever drawing from an empty one.
                add(own, 7, "Forest", ZoneType.Library);
                add(own, 15, "Forest", ZoneType.Exile);
            }
            case "wheel-breach-in-hand" -> {
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
            case "freeze-route-wins" -> {
                one(own, BREACH, ZoneType.Battlefield);
                one(own, LED, ZoneType.Battlefield);
                add(own, 2, "Island", ZoneType.Battlefield);
                add(own, 3, "Mountain", ZoneType.Battlefield);
                one(own, WHEEL, ZoneType.Graveyard);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, 12, "Forest", ZoneType.Graveyard);
                add(own, 14, "Forest", ZoneType.Library);
            }
            // ------------------------------------------------------- v75 R0
            // The Breach is in our own HAND and the fuel is 12, which the v41
            // hand route would also accept - but its own entry is not what is
            // being observed here: the Tide route is, and until v75 it could
            // not be reached at all from a hand Breach. Two Mountains pay the
            // {1}{R} and are the only red on the board, so the four Islands
            // reach the forecast untouched.
            case "sequence-tide" -> {
                one(own, BREACH, ZoneType.Hand);
                one(own, TIDE, ZoneType.Hand);
                one(own, FREEZE, ZoneType.Hand);
                add(own, 4, "Island", ZoneType.Battlefield);
                add(own, 2, "Mountain", ZoneType.Battlefield);
                add(own, 12, "Forest", ZoneType.Graveyard);
            }
            // R2's shape from a hand Breach: three Islands enter TAPPED, which
            // is what Frantic Search untaps, and the Brain Freeze sits in the
            // graveyard as it does in the v64 `frantic-tide` row.
            case "sequence-frantic" -> {
                one(own, BREACH, ZoneType.Hand);
                one(own, TIDE, ZoneType.Hand);
                one(own, FRANTIC, ZoneType.Hand);
                one(own, LED, ZoneType.Battlefield);
                add(own, 4, "Island", ZoneType.Battlefield);
                for (int i = 0; i < 3; i++) own.add(new Placement("Island", ZoneType.Battlefield, true));
                add(own, 2, "Mountain", ZoneType.Battlefield);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, 12, "Forest", ZoneType.Graveyard);
            }
            // The MUST-NOT-MOVE half of R0: the Tide is in hand and the Breach
            // is in hand, and there is no Island anywhere, so the Tide can buy
            // nothing. R0 must decline and v68's hold must govern the ordinary
            // AI's cast exactly as it did in v68.
            case "sequence-no-islands" -> {
                one(own, BREACH, ZoneType.Hand);
                one(own, TIDE, ZoneType.Hand);
                one(own, FREEZE, ZoneType.Hand);
                add(own, 2, "Mountain", ZoneType.Battlefield);
                add(own, 2, "Swamp", ZoneType.Battlefield);
                add(own, 12, "Forest", ZoneType.Graveyard);
            }
            // ---------------------------------------------------- v75 tutors
            // Graveyard fuel is ZERO, so the v41 hand route's own gate is shut
            // and the v41 completing name is empty: the only thing that can
            // name Brain Freeze here is R0's entry, which needs the High Tide
            // and an Island and no fuel at all. Two Swamps cast the tutor.
            // `tutor-no-fuel` is the same board with the High Tide replaced by
            // a Forest, which shuts that entry too.
            case "tutor-demonic", "tutor-no-fuel", "tutor-hexproof" -> {
                one(own, DEMONIC, ZoneType.Hand);
                one(own, BREACH, ZoneType.Hand);
                one(own, LOTUS, ZoneType.Hand);
                one(own, name.equals("tutor-no-fuel") ? "Forest" : TIDE, ZoneType.Hand);
                add(own, 4, "Island", ZoneType.Battlefield);
                add(own, 2, "Swamp", ZoneType.Battlefield);
                // The offered list holds the piece and one card the ordinary
                // tail prefers - a creature, and the most expensive thing
                // there - so every refusal is a refusal on a real list.
                one(own, FREEZE, ZoneType.Library);
                one(own, DRAGON, ZoneType.Library);
            }
            // The same position with a search-to-top tutor. The alternative in
            // the library is a sorcery, because Mystical Tutor offers only
            // instants and sorceries, and it is more expensive than the piece.
            case "tutor-mystical" -> {
                one(own, MYSTICAL, ZoneType.Hand);
                one(own, BREACH, ZoneType.Hand);
                one(own, LOTUS, ZoneType.Hand);
                one(own, TIDE, ZoneType.Hand);
                add(own, 4, "Island", ZoneType.Battlefield);
                add(own, 2, "Swamp", ZoneType.Battlefield);
                one(own, FREEZE, ZoneType.Library);
                one(own, WHEEL, ZoneType.Library);
            }
            // ------------------------------------------------- v75 colourless
            // The v64 `wheel-freeze` board with four of its five Mountains
            // replaced by colourless accel. colorMana("R") is 1, so v64's
            // single pool cannot reach the wheel's {2}{R} and answers
            // wheel-no-terminal on a board that pays for it three times over.
            case "wheel-colorless" -> {
                one(own, BREACH, ZoneType.Battlefield);
                one(own, "Mountain", ZoneType.Battlefield);
                one(own, SOL_RING, ZoneType.Battlefield);
                one(own, CRYPT, ZoneType.Battlefield);
                one(own, TOMB, ZoneType.Battlefield);
                add(own, 3, "Island", ZoneType.Battlefield);
                one(own, WHEEL, ZoneType.Graveyard);
                one(own, FREEZE, ZoneType.Graveyard);
                add(own, 5, "Forest", ZoneType.Graveyard);
                add(own, 2, "Forest", ZoneType.Hand);
                add(own, 14, "Forest", ZoneType.Library);
            }
            default -> throw new AssertionError("unknown case " + name);
        }
        // Padding goes to the library everywhere except the row whose whole
        // point is a library too small to survive a wheel.
        ZoneType pad = name.equals("decks-us") ? ZoneType.Exile : ZoneType.Library;
        while (own.size() < 40) own.add(new Placement("Forest", pad, false));
        if (own.size() != 40) throw new AssertionError("own board is not 40: " + name + " " + own.size());
        return own;
    }

    /** The opponent's 40. Their library size is the mill clock every row is
     * tuned against, so it is stated per row and padded in the graveyard. */
    private static int opponentLibrary(String name) {
        return switch (name) {
            case "tide-freeze", "sequence-tide", "sequence-no-islands" -> 26;
            case "frantic-tide", "sequence-frantic" -> 40;
            case "tutor-demonic", "tutor-mystical", "tutor-no-fuel" -> 40;
            // One fewer, because this row's opponent also has a permanent and
            // every board here is exactly 40 registered cards.
            case "tutor-hexproof" -> 39;
            case "wheel-colorless" -> 13;
            case "tide-no-gain" -> 9;
            case "wheel-hullbreacher" -> 6;
            case "freeze-route-wins" -> 9;
            default -> 13;
        };
    }

    private static List<Placement> other(String name) {
        List<Placement> other = new ArrayList<>();
        if (name.equals("opponent-hullbreacher")) one(other, HULL, ZoneType.Battlefield);
        if (name.equals("opponent-narset")) one(other, NARSET, ZoneType.Battlefield);
        // v75: the one row that isolates the convertibility gate from the
        // completing name. The family still NAMES Brain Freeze here; the gate
        // refuses it because the terminal could never target this opponent.
        if (name.equals("tutor-hexproof")) one(other, LEYLINE, ZoneType.Battlefield);
        add(other, opponentLibrary(name), "Forest", ZoneType.Library);
        while (other.size() < 40) other.add(new Placement("Forest", ZoneType.Graveyard, false));
        if (other.size() != 40) throw new AssertionError("opponent board is not 40: " + name);
        return other;
    }

    private static List<Placement> placements(boolean owner, String name) {
        return owner ? own(name) : other(name);
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("breach-wheel fixture");
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
            // A planeswalker placed straight onto the battlefield has no
            // loyalty and dies to the first state-based check, so the row that
            // is about an opposing Narset would never see one.
            if (p.name().equals(NARSET)) card.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
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

    /** v75: the top card of our own library - what a search-to-top moves, and
     * the only way to observe a Mystical Tutor selection from the board. Read
     * once, at the END of the row; no decision consults it. */
    private static String topOfLibrary(Player player) {
        var library = player.getCardsIn(ZoneType.Library);
        return library.isEmpty() ? "none" : library.get(0).getName().replace(' ', '_');
    }

    /** v75: where a named card of ours ended the row - the arm-independent
     * observation of a fetch, which depends on no marker at all. */
    private static String zoneOf(Player player, String name) {
        for (ZoneType zone : LOOKUP) if (countIn(player, zone, name) > 0) return zone.toString();
        return "none";
    }

    /** stderr lines the policy printed during the current row. CUBE_* lines are
     * observability only and are never read by a decision; counting them here
     * is a test-side read of the log this JVM is already writing, and every
     * line still reaches the real stderr unchanged. */
    private static final List<String> declines = new ArrayList<>();
    private static int stalledLines;
    /** v75: the tutor-selection markers this row produced, in order of first
     * appearance. The controller and CubeComboAi own these lines; collecting
     * them here is a test-side read of a log this JVM is already writing, and
     * every line still reaches the real stderr unchanged. */
    private static final List<String> selections = new ArrayList<>();
    private static int holdLines;

    private static final class CountingErr extends PrintStream {
        CountingErr(PrintStream sink) { super(sink, true); }
        @Override public void println(String line) {
            if (line != null && line.startsWith("CUBE_PLAN_DECLINE family=breach reason=")) {
                String reason = line.substring(line.indexOf("reason=") + 7).trim();
                if (!declines.contains(reason)) declines.add(reason);
            }
            if (line != null && line.startsWith("CUBE_BREACH_PLAN wheel stopped-no-progress")) stalledLines++;
            if (line != null && line.startsWith("CUBE_BREACH_HOLD ")) holdLines++;
            if (line != null && line.startsWith("CUBE_COMBO_SELECTION ")) {
                String tail = line.substring("CUBE_COMBO_SELECTION ".length()).trim().replace(' ', '|');
                if (!selections.contains(tail)) selections.add(tail);
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
        Game game = new Match(rules, players, "native breach-wheel fixture").createGame();
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
        BenchRandomAudit.install(96400L + seat * 100L + caseIndex(name));
        declines.clear();
        selections.clear();
        stalledLines = 0;
        holdLines = 0;
        int bound = startTurn + 1;
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        int opponentLibraryStart = opponent.getCardsIn(ZoneType.Library).size();
        System.out.println("WHEEL_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0 startTurn=" + startTurn
                + " bound=" + bound + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " opponentLibrary=" + opponentLibraryStart + " opponentLife=" + opponent.getLife()
                + " fuel=" + player.getCardsIn(ZoneType.Graveyard).size());
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, tideCasts = 0, franticCasts = 0, wheelCasts = 0, breachCasts = 0;
        int freezeCasts = 0, freezeCopies = 0, tendrilsCasts = 0, oracleCasts = 0, twisterCasts = 0;
        int ledActivations = 0, escapes = 0, maxTreasures = 0;
        int opponentLibraryAfterWheel = -1;
        boolean ledInPlay = countIn(player, ZoneType.Battlefield, LED) > 0;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 900) {
            int manaBefore = player.getManaPool().totalMana();
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (!sa.isSpell() || sa.getActivatingPlayer() != player) continue;
                String host = sa.getHostCard().getName();
                if (sa.isEscape()) escapes++;
                switch (host) {
                    case TIDE -> tideCasts++;
                    case FRANTIC -> franticCasts++;
                    case WHEEL -> wheelCasts++;
                    case BREACH -> breachCasts++;
                    case TENDRILS -> tendrilsCasts++;
                    case ORACLE -> oracleCasts++;
                    case TWISTER -> twisterCasts++;
                    case FREEZE -> { if (sa.isCopied()) freezeCopies++; else freezeCasts++; }
                    default -> { }
                }
                System.out.println("WHEEL_STACK " + key + " step=" + steps + " source=" + host.replace(' ', '_')
                        + " escape=" + sa.isEscape() + " copied=" + sa.isCopied()
                        + " storm=" + game.getStack().getSpellsCastThisTurn().size()
                        + " targets=" + sa.getTargets());
            }
            boolean ledNow = countIn(player, ZoneType.Battlefield, LED) > 0;
            if (ledInPlay && !ledNow && player.getManaPool().totalMana() > manaBefore) ledActivations++;
            ledInPlay = ledNow;
            maxTreasures = Math.max(maxTreasures, countIn(player, ZoneType.Battlefield, TREASURE));
            if (wheelCasts > 0 && opponentLibraryAfterWheel < 0 && game.getStack().isEmpty()
                    && countIn(player, ZoneType.Graveyard, WHEEL) + countIn(player, ZoneType.Exile, WHEEL) > 0)
                opponentLibraryAfterWheel = opponent.getCardsIn(ZoneType.Library).size();
            String state = "turn=" + game.getPhaseHandler().getTurn()
                    + " phase=" + game.getPhaseHandler().getPhase()
                    + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                    + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size()
                    + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size()
                    + " exile=" + player.getCardsIn(ZoneType.Exile).size()
                    + " treasures=" + countIn(player, ZoneType.Battlefield, TREASURE)
                    + " opponentLife=" + opponent.getLife()
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("WHEEL_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("WHEEL_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " tideCasts=" + tideCasts + " franticCasts=" + franticCasts + " wheelCasts=" + wheelCasts
                + " breachCasts=" + breachCasts + " freezeCasts=" + freezeCasts + " freezeCopies=" + freezeCopies
                + " tendrilsCasts=" + tendrilsCasts + " oracleCasts=" + oracleCasts
                + " twisterCasts=" + twisterCasts + " ledActivations=" + ledActivations + " escapes=" + escapes
                + " maxTreasures=" + maxTreasures + " stalledLines=" + stalledLines
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " opponentLibraryStart=" + opponentLibraryStart
                + " opponentLibraryAfterWheel=" + opponentLibraryAfterWheel
                + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size()
                + " opponentLife=" + opponent.getLife() + " life=" + player.getLife()
                + " declines=[" + String.join(";", declines) + "]"
                // v75's four observations are appended ONLY on a v75 row, so
                // every v64 WHEEL_RESULT receipt stays byte-identical and the
                // `wheel` group can be equated in order without a composed
                // expectation.
                + (SEQUENCE.contains(name)
                    ? " holdLines=" + holdLines + " libraryTop=" + topOfLibrary(player)
                        + " freezeZone=" + zoneOf(player, FREEZE)
                        + " selections=[" + String.join(";", selections) + "]"
                    : "")
                + " outcome=" + game.getOutcome());
    }

    private record Row(int seat, String name) {}

    private static List<Row> rows(String suite) {
        List<Row> rows = new ArrayList<>();
        List<String> selected = switch (suite) {
            case "move" -> MOVE;
            case "hold" -> HOLD;
            // v75's own suite. It never runs a v64 row, so the `wheel` group's
            // receipts cannot move because this suite exists.
            case "sequence" -> SEQUENCE;
            default -> { List<String> all = new ArrayList<>(MOVE); all.addAll(HOLD); yield all; }
        };
        for (String name : selected) rows.add(new Row(0, name));
        if (suite.equals("wheel")) for (String name : SEAT1) rows.add(new Row(1, name));
        if (suite.equals("sequence")) for (String name : SEQUENCE_SEAT1) rows.add(new Row(1, name));
        return rows;
    }

    public static void main(String[] args) {
        try {
            System.setErr(new CountingErr(System.err));
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "breach-wheel-native-fixture-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String card : List.of(BREACH, WHEEL, TIDE, FRANTIC, FREEZE, TENDRILS, ORACLE, HULL,
                    NARSET, LED, TWISTER, DEMONIC, MYSTICAL, LOTUS, SOL_RING, CRYPT, TOMB, DRAGON,
                    LEYLINE, "Island", "Mountain", "Swamp", "Forest"))
                StaticData.instance().attemptToLoadCard(card);
            List<Row> rows = rows(args.length > 2 ? args[2] : "wheel");
            for (Row row : rows) run(args[1].equals("improved"), row.seat(), row.name());
            System.out.println("WHEEL_SUITE_COMPLETE cases=" + rows.size());
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
