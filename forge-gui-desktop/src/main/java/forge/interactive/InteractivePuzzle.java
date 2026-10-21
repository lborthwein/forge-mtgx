/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.PlayerStatistics;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.puzzle.Puzzle;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A ONE-SEAT GAME FROM A PUZZLE POSITION (lane puzzle-host-1021): the config line's {@code puzzle} =
 * {@code {state, goal, turns}}, where {@code state} is a .pzl file's {@code [state]} block. Forge's own puzzle code
 * plays it: a {@link Puzzle} is built from that state and a synthesized {@code [metadata]} (Name, Goal, Turns), and
 * installed through the startGameHook of a {@code GameType.Puzzle} match exactly as the desktop's puzzle mode does
 * (no mulligan, no opening hand; {@code GameState} sets every zone; {@code addGoalEnforcement} puts the goal's
 * effect card in the browser seat's command zone). The AI seat is the session's usual seat, bound as in a deck game.
 *
 * <p>{@code human*} keys are the browser seat ({@code humanSeat}), {@code ai*} keys the AI seat. Forge's GameState
 * reads {@code human} as player 0; for humanSeat 1 the keys are rewritten to {@code p1*}/{@code p0*}.</p>
 *
 * <p>The terminal carries {@code puzzle: {goal, goalMet, reason}}: reason {@code solved} (a win goal met),
 * {@code survived} (a survive goal met), {@code turn_limit} (lost to the goal's own turn limit), {@code conceded},
 * {@code draw}, or {@code lost} (any other loss).</p>
 */
final class InteractivePuzzle {
    static final String FIELD = "puzzle";
    static final String REFUSED = "invalid_puzzle";
    static final int MAX_STATE = 65536;
    /** A win must come within this many turns of the position's own turn (and a survive goal end within it). */
    static final int MAX_TURNS_AHEAD = 20;
    /** The goal's effect card, as {@link Puzzle#addGoalEnforcement} names it. */
    static final String GOAL_CARD = "Puzzle Goal";

    private InteractivePuzzle() {
    }

    /** The config's puzzle: Forge's [state] lines, its goal and its absolute turn limit. */
    record Spec(String state, String goal, int turns) {
    }

    /** A refusal of the puzzle itself (the position, not the protocol). */
    static final class Refused extends RuntimeException {
        Refused(final String message) {
            super(message);
        }

        Refused(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private static final Set<String> GLOBAL_KEYS = Set.of("turn", "monarch", "initiative", "removesummoningsickness",
            "activeplayer", "activephase", "activephaseadvance");
    /** GameState.parseLine's per-player keys (after the human/ai/p0/p1 prefix). */
    private static final Set<String> PLAYER_KEYS = Set.of("life", "counters", "landsplayed", "landsplayedlastturn",
            "numringtemptedyou", "speed", "play", "battlefield", "hand", "graveyard", "library", "exile", "command",
            "sideboard", "precast", "putonstack", "manapool", "persistentmana", "completeddungeons");
    private static final Set<String> INT_KEYS = Set.of("life", "landsplayed", "landsplayedlastturn",
            "numringtemptedyou", "speed");
    /** The per-player keys whose value is a ';' list of cards (name|modifier|...). */
    private static final Set<String> CARD_KEYS = Set.of("play", "battlefield", "hand", "graveyard", "library", "exile",
            "command", "sideboard");
    private static final Pattern PLAYER_KEY = Pattern.compile("^(human|ai|p[01])([a-z]+)$");

    /** The config's {@code puzzle}, structurally checked (no card database needed): a ProtocolException refuses it. */
    static Spec read(final JsonObject json) throws InteractiveProtocol.ProtocolException {
        if (!json.has(FIELD) || json.get(FIELD).isJsonNull()) {
            return null;
        }
        if (!json.get(FIELD).isJsonObject()) {
            throw refuse("puzzle must be an object {state, goal, turns}");
        }
        final JsonObject p = json.getAsJsonObject(FIELD);
        for (String key : p.keySet()) {
            if (!Set.of("state", "goal", "turns").contains(key)) {
                throw refuse("puzzle has an unknown field: " + key);
            }
        }
        final JsonElement state = p.get("state");
        if (state == null || !state.isJsonPrimitive() || !state.getAsJsonPrimitive().isString()
                || state.getAsString().isBlank() || state.getAsString().length() > MAX_STATE) {
            throw refuse("puzzle.state must be a non-blank [state] block up to 64 KiB");
        }
        final JsonElement goal = p.get("goal");
        if (goal == null || !goal.isJsonPrimitive() || !goal.getAsJsonPrimitive().isString()
                || !Set.of("win", "survive").contains(goal.getAsString())) {
            throw refuse("puzzle.goal must be 'win' or 'survive'");
        }
        final JsonElement turns = p.get("turns");
        final int t;
        try {
            if (turns == null || !turns.isJsonPrimitive() || !turns.getAsJsonPrimitive().isNumber()) {
                throw new NumberFormatException();
            }
            t = new java.math.BigDecimal(turns.getAsString()).intValueExact();
        } catch (RuntimeException e) {
            throw refuse("puzzle.turns must be an integer");
        }
        final Spec spec = new Spec(state.getAsString(), goal.getAsString(), t);
        lines(spec, 0, false); // the structure, refused here before any Forge singleton is up
        return spec;
    }

    static InteractiveProtocol.ProtocolException refuse(final String message) {
        return new InteractiveProtocol.ProtocolException(REFUSED, message);
    }

    /**
     * The state's lines as Forge's GameState will read them, checked: every non-blank line is {@code key=value} with a
     * key GameState knows, {@code turn}/{@code activeplayer}/{@code activephase} are present and valid, numbers parse,
     * the turn limit is reachable. For humanSeat 1 the seat keys are swapped (see the class comment).
     */
    static List<String> lines(final Spec spec, final int humanSeat) throws InteractiveProtocol.ProtocolException {
        return lines(spec, humanSeat, true);
    }

    /**
     * {@code phases}: check the phase names too. {@link PhaseType}'s class init needs Forge's localizer, so the config
     * reader (before {@code FModel.initialize}) checks everything else and the game start checks the phases.
     */
    static List<String> lines(final Spec spec, final int humanSeat, final boolean phases)
            throws InteractiveProtocol.ProtocolException {
        final List<String> out = new ArrayList<>();
        Integer turn = null;
        String active = null;
        String phase = null;
        for (String raw : spec.state().split("\\R")) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                throw refuse("puzzle.state is the [state] block only (no section headers): " + clip(line));
            }
            final int eq = line.indexOf('=');
            if (eq < 1) {
                throw refuse("puzzle.state line is not key=value: " + clip(line));
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1);
            if (key.startsWith("ability")) {
                if (humanSeat != 0) {
                    throw refuse("ability scripts are not supported with humanSeat 1");
                }
            } else if (GLOBAL_KEYS.contains(key)) {
                switch (key) {
                    case "turn" -> turn = parseInt(key, value, 1, 1000);
                    case "activeplayer" -> active = value.trim().toLowerCase(Locale.ROOT);
                    case "activephase" -> phase = value.trim();
                    case "activephaseadvance" -> {
                        if (phases) {
                            phaseOf(key, value);
                        }
                    }
                    default -> { }
                }
                if (Set.of("activeplayer", "monarch", "initiative").contains(key)) {
                    value = seatRef(key, value, humanSeat);
                }
            } else {
                final Matcher m = PLAYER_KEY.matcher(key);
                if (!m.matches() || !PLAYER_KEYS.contains(m.group(2))) {
                    throw refuse("puzzle.state has an unknown key: " + clip(key));
                }
                if (INT_KEYS.contains(m.group(2))) {
                    parseInt(key, value, -1000, 1000);
                }
                if (humanSeat != 0 && Pattern.compile("(?i)(^|[|:;,=])(human|ai)($|[|;,])").matcher(value).find()) {
                    throw refuse("a card attribute naming a player (HUMAN/AI) is not supported with humanSeat 1: " + key);
                }
                key = seatKey(m.group(1), humanSeat) + m.group(2);
            }
            out.add(key + "=" + value);
        }
        if (turn == null) {
            throw refuse("puzzle.state needs turn=");
        }
        if (active == null || seatIndex(active) < 0) {
            throw refuse("puzzle.state needs activeplayer=human|ai");
        }
        if (phase == null) {
            throw refuse("puzzle.state needs activephase=");
        }
        if (phases) {
            phaseOf("activephase", phase);
        }
        if (spec.turns() < turn || spec.turns() > turn + MAX_TURNS_AHEAD) {
            throw refuse("puzzle.turns must be from the position's turn (" + turn + ") to " + (turn + MAX_TURNS_AHEAD));
        }
        return out;
    }

    private static String clip(final String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static int parseInt(final String key, final String value, final int min, final int max)
            throws InteractiveProtocol.ProtocolException {
        try {
            final int v = Integer.parseInt(value.trim());
            if (v < min || v > max) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw refuse(key + " must be an integer from " + min + " to " + max);
        }
    }

    private static void phaseOf(final String key, final String value) throws InteractiveProtocol.ProtocolException {
        if (value.trim().equalsIgnoreCase("none")) {
            return;
        }
        try {
            if (PhaseType.smartValueOf(value.trim()) == null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw refuse(key + " is not a phase: " + clip(value));
        }
    }

    /** 0 for the browser seat's side of the state (human), 1 for the AI's; -1 if neither. */
    private static int seatIndex(final String ref) {
        final String r = ref.trim().toLowerCase(Locale.ROOT);
        if (r.equals("human") || r.equals("p0")) {
            return 0;
        }
        if (r.equals("ai") || r.equals("p1")) {
            return 1;
        }
        return -1;
    }

    /** The state's own side (human/ai/p0/p1) as Forge's player index for this humanSeat. */
    private static String seatKey(final String side, final int humanSeat) {
        final int s = side.equals("human") || side.equals("p0") ? 0 : 1;
        return "p" + (humanSeat == 0 ? s : 1 - s);
    }

    private static String seatRef(final String key, final String value, final int humanSeat)
            throws InteractiveProtocol.ProtocolException {
        final String v = value.trim().toLowerCase(Locale.ROOT);
        if (!key.equals("activeplayer") && (v.isEmpty() || v.equals("none"))) {
            return value;
        }
        final int s = seatIndex(v);
        if (s < 0) {
            throw refuse(key + " must be human or ai");
        }
        return seatKey(s == 0 ? "human" : "ai", humanSeat);
    }

    /**
     * Every card the state names must be one Forge's card database knows and supports (GameState would skip an unknown
     * one with a stderr line: a different position). Tokens ({@code t:}/{@code T:}) are Forge's to build. Needs the
     * card database: call after {@code FModel.initialize}.
     */
    static void checkCards(final List<String> lines) throws InteractiveProtocol.ProtocolException {
        for (Map.Entry<String, List<String[]>> zone : cardsByKey(lines).entrySet()) {
            for (String[] info : zone.getValue()) {
                if (info[0].startsWith("t:") || info[0].startsWith("T:")) {
                    continue;
                }
                final PaperCard pc = StaticData.instance().getCommonCards().getCard(info[0], setOf(info), artOf(info));
                if (pc == null || pc.getRules().isUnsupported()) {
                    throw refuse("unknown or unsupported card in " + zone.getKey() + ": " + clip(info[0]));
                }
            }
        }
    }

    /** {key: [cardinfo...]} for every card-list key of the (already checked) lines, as GameState splits them. */
    private static Map<String, List<String[]>> cardsByKey(final List<String> lines) {
        final Map<String, List<String[]>> out = new LinkedHashMap<>();
        for (String line : lines) {
            final int eq = line.indexOf('=');
            final String key = line.substring(0, eq);
            final Matcher m = PLAYER_KEY.matcher(key);
            if (!m.matches() || !CARD_KEYS.contains(m.group(2))) {
                continue;
            }
            final String value = line.substring(eq + 1);
            final List<String[]> cards = new ArrayList<>();
            if (!value.isEmpty()) {
                for (String element : value.split(";")) {
                    if (element.isBlank()) {
                        continue;
                    }
                    cards.add(element.trim().split("\\|"));
                }
            }
            out.put(key, cards);
        }
        return out;
    }

    private static String setOf(final String[] info) {
        for (String i : info) {
            if (i.startsWith("Set:")) {
                return i.substring(i.indexOf(':') + 1);
            }
        }
        return null;
    }

    private static int artOf(final String[] info) {
        for (String i : info) {
            if (i.startsWith("Art:")) {
                try {
                    return Integer.parseInt(i.substring(i.indexOf(':') + 1));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Seat {@code seat}'s registered deck: its own puzzle cards (every zone, tokens left out), so an AI seat's "my
     * deck" inputs are its real cards. Match.startGame deals it to the library; the state then clears and sets every
     * zone, so the deck never reaches play.
     */
    static Deck deckOf(final List<String> lines, final int seat) {
        final Deck deck = new Deck("puzzle seat " + seat);
        final String prefix = "p" + seat;
        for (Map.Entry<String, List<String[]>> zone : cardsByKey(lines).entrySet()) {
            if (!zone.getKey().startsWith(prefix)) {
                continue;
            }
            for (String[] info : zone.getValue()) {
                if (info[0].startsWith("t:") || info[0].startsWith("T:")) {
                    continue;
                }
                final PaperCard pc = StaticData.instance().getCommonCards().getCard(info[0], setOf(info), artOf(info));
                if (pc != null) {
                    deck.getOrCreate(DeckSection.Main).add(pc);
                }
            }
        }
        return deck;
    }

    /** Forge's Puzzle for this spec, its goal owned by {@code humanSeat}'s player (whatever its controller says). */
    static Position position(final Spec spec, final List<String> lines, final int humanSeat) {
        final Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("metadata", List.of("Name:mtgx puzzle", "Goal:" + (spec.goal().equals("win") ? "Win" : "Survive"),
                "Turns:" + spec.turns()));
        sections.put("state", lines);
        return new Position(sections, humanSeat);
    }

    /** A Forge {@link Puzzle} whose goal effect goes to the configured browser seat. */
    static final class Position extends Puzzle {
        private final int humanSeat;

        Position(final Map<String, List<String>> sections, final int humanSeat) {
            super(sections);
            this.humanSeat = humanSeat;
        }

        @Override
        public void addGoalEnforcement(final Game game) {
            addGoalEnforcement(game, InteractiveMain.playerAtSeat(game, humanSeat));
        }
    }

    /**
     * The startGameHook: install the position inline on the game thread (named as one for the call, as the table frame
     * does, so GameState never hands it to another thread). A failure is a {@link Refused}.
     */
    static Runnable hook(final Position position, final Game game) {
        return () -> {
            final Thread self = Thread.currentThread();
            final String was = self.getName();
            self.setName("Game-puzzle-install");
            try {
                position.applyToGame(game);
                beginCombat(game);
            } catch (RuntimeException e) {
                throw new Refused("the puzzle position could not be installed: " + e, e);
            } finally {
                self.setName(was);
            }
            System.err.println("[forge.interactive] puzzle position installed: turn " + game.getPhaseHandler().getTurn()
                    + ", " + game.getPhaseHandler().getPhase());
        };
    }

    /**
     * A position in the beginning of combat step has no {@code Combat} yet: GameState sets the phase without running
     * its begin (where PhaseHandler creates it), and declare attackers would then NPE on a null combat. Create it as
     * the step's begin does (CR 507). The other combat steps are GameState's own (it declares attackers/blockers).
     */
    static void beginCombat(final Game game) {
        final forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        if (ph.getPhase() == PhaseType.COMBAT_BEGIN && ph.getCombat() == null && ph.getPlayerTurn() != null) {
            ph.setCombat(new forge.game.combat.Combat(ph.getPlayerTurn()));
            game.updateCombatForView();
        }
    }

    /** The terminal's {@code puzzle} for this finished game, from the browser seat's own outcome. */
    static JsonObject outcome(final Spec spec, final GameOutcome outcome, final List<RegisteredPlayer> registered,
                              final int humanSeat) {
        final JsonObject o = new JsonObject();
        o.addProperty("goal", spec.goal());
        PlayerOutcome mine = null;
        for (Map.Entry<RegisteredPlayer, PlayerStatistics> e : outcome) {
            if (registered.indexOf(e.getKey()) == humanSeat && e.getValue() != null) {
                mine = e.getValue().getOutcome();
            }
        }
        final boolean met = !outcome.isDraw() && mine != null && mine.hasWon();
        o.addProperty("goalMet", met);
        final String reason;
        if (met) {
            reason = spec.goal().equals("win") ? "solved" : "survived";
        } else if (outcome.isDraw()) {
            reason = "draw";
        } else if (mine != null && mine.lossState == GameLossReason.Conceded) {
            reason = "conceded";
        } else if (mine != null && mine.lossState == GameLossReason.SpellEffect && GOAL_CARD.equals(mine.loseConditionSpell)) {
            reason = "turn_limit";
        } else {
            reason = "lost";
        }
        o.addProperty("reason", reason);
        return o;
    }

    /** The configured browser seat's player, for callers outside a live game. */
    static Player seat(final Game game, final int humanSeat) {
        return InteractiveMain.playerAtSeat(game, humanSeat);
    }
}
