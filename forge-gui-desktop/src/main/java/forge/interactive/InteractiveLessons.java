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
import forge.ai.lesson.LessonScript;
import forge.ai.lesson.LessonScriptController;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.Match;
import forge.game.PriorityDecisionListener;
import forge.game.event.GameEventGameFinished;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * J3, A LESSON POSITION INSTALLED IN A RUNNING GAME (lane lessons-forge-jar-1027): Take it back, a practice rep or a
 * stage jump, with no JVM boot. Armed only for a lesson game (a puzzle with goal {@code 'none'} whose AI seat is a
 * scripted {@link LessonScriptController}); every other game never sees any of this.
 *
 * <p><b>The message.</b> An ordinary input line answering the browser's open PRIORITY request, of kind {@link #KIND}:
 * {@code {type:"input", requestId, inputId, kind:"install", action:{type:"install", position:{state, goal:"none",
 * script?}}}}. The position is checked as a puzzle's (structure, keys, card cap, every card known, the script's
 * phases and names) on the reader thread; it is acked as any input is ({@code accepted}, or not with a reason: not a
 * lesson position, or the browser holds no open priority request). In a game that is not armed the same line is
 * refused as before (its kind matches no request). Because it is an input, the host journals it as it journals every
 * input, and a restore or replay sends it again at the same request: the replay is exact.</p>
 *
 * <p><b>Where it runs.</b> On Forge's game thread, at a safe point: the accepted input passes the browser's priority
 * (as the pass control does), and the priority-decision listener throws {@link Restart} right after that decision,
 * before the phase handler acts on it. {@link #run} catches it outside {@code Match.startGame}, installs the position,
 * and runs the phase handler's main loop again from the new position; at the game's end it runs what
 * {@code GameAction.startGame} and {@code Match.startGame} would have run after their loop.</p>
 *
 * <p><b>What an install resets</b> (everything a fresh puzzle start of the position has fresh):
 * <ul>
 *   <li>the stack: every spell and ability on it and their cards (the stack zone emptied), the simultaneous-trigger
 *   list, a frozen stack, this turn's cast and activation history, the undo stack ({@code MagicStack.reset});</li>
 *   <li>triggers: waiting triggers, delayed triggers (also player-defined), every "until"/"at" command of the untap,
 *   upkeep, beginning of combat, end of combat, end of turn and cleanup ({@code Phase.clearCommands});</li>
 *   <li>combat: ended and gone (the position's combat step, if any, is set up as a puzzle's is);</li>
 *   <li>the mana pool: emptied, then the position's {@code manapool=}/{@code persistentmana=};</li>
 *   <li>the turn and phase: the position's ({@code turn=}, {@code activeplayer=}, {@code activephase=}), with
 *   priority to its active player; the phase handler's per-turn and per-game counters, extra turns and extra phases,
 *   the no-progress guard ({@code PhaseHandler.devResetForPosition});</li>
 *   <li>per-turn history: Forge's turn-boundary reset run twice (this turn's and last turn's: lands, spells cast,
 *   life lost and gained, cards drawn, damage, left-the-battlefield), then the position's own lands played; every
 *   zone of both players is the position's (no card object survives);</li>
 *   <li>each player's life and counters: life back to the starting life, every player counter (poison, energy, rad,
 *   experience, ...) removed, then the position's {@code humanlife=}/{@code ailife=} and {@code humancounters=}/
 *   {@code aicounters=} (GameState sets them only when named);</li>
 *   <li>the AI seat: its card memory cleared; its script replaced by the position's (a fresh place in it; none:
 *   {@code rest=bot}, the plain Forge AI); the browser seat's auto-pass cancelled;</li>
 *   <li>the journal and AI tape: the install is an input (above), and the AI tape is untouched: its next entry records
 *   the state the install left (ids, RNG), so a forced replay that re-sends the install is exact.</li>
 * </ul>
 * NOT reset: the game log (an install adds a line), the process-wide id counters and the game's timestamp (only ever
 * forward), the RNG stream, the players' names and seats, the browser seat's yield preferences; player-level effects
 * that last until end of turn or longer and live on the player rather than a card (e.g. "you can't gain life this turn",
 * a player keyword granted until end of turn); the day/night state; and an attached {@code RlReviewObserver} (a review
 * keeps observing across the install). Chapter 1 positions use none of these; a lesson that does must name them.</p>
 *
 * <p><b>A failed install is a session fatal.</b> The position was checked on the reader thread, so an install that still
 * throws on the game thread (in {@code applyToGame} or the resets) leaves the game in no defined state: it is thrown as
 * {@link InteractivePuzzle.Refused} ("the lesson position could not be installed", the detail on stderr only), and the
 * game thread reports it as the fatal {@code invalid_puzzle}, as a position that fails at the game start is. The host
 * ends the session; there is no rollback to the old position.</p>
 */
final class InteractiveLessons implements PriorityDecisionListener {
    /** The input kind (and action type) of an install. */
    static final String KIND = "install";
    static final String PREFIX = "[lesson-install] ";

    /** A checked position for the game thread. */
    record Install(InteractivePuzzle.Spec spec, List<String> lines, LessonScript script) {
    }

    /** Thrown on the game thread right after the browser's priority decision that carried an install. */
    static final class Restart extends RuntimeException {
        final transient Install install;

        Restart(final Install install) {
            super("lesson install", null, false, false);
            this.install = install;
        }
    }

    private final Game game;
    private final int humanSeat;
    private final Player human;
    private final LessonScriptController seat;
    private final PrintStream log;
    private final AtomicReference<Install> pending = new AtomicReference<>();
    private int installs;

    private InteractiveLessons(final Game game, final int humanSeat, final LessonScriptController seat, final PrintStream log) {
        this.game = game;
        this.humanSeat = humanSeat;
        this.human = InteractiveMain.playerAtSeat(game, humanSeat);
        this.seat = seat;
        this.log = log;
    }

    /**
     * J3 for this game, or null: armed only for a lesson position (goal 'none') whose AI seat is a lesson seat (the game
     * start seats one for every lesson on the classic AI: the position's script, else {@code rest=bot}). Registers the
     * priority-decision listener.
     */
    static InteractiveLessons arm(final InteractivePuzzle.Spec spec, final Game game, final int humanSeat,
                                  final LessonScriptController seat, final PrintStream log) {
        if (spec == null || spec.hasGoal() || seat == null) {
            return null;
        }
        final InteractiveLessons lessons = new InteractiveLessons(game, humanSeat, seat, log);
        game.addPriorityDecisionListener(lessons);
        return lessons;
    }

    int installs() {
        return installs;
    }

    // ------------------------------------------------------------------ the reader thread

    /**
     * The action's position, checked as a lesson position (needs the card database); a refusal's message never names a
     * card. {@code action}: {@code {type:"install", position:{state, goal:"none", script?}}}.
     */
    Install parse(final JsonObject action) throws InteractiveProtocol.ProtocolException {
        final JsonElement position = action.get("position");
        if (position == null || !position.isJsonObject()) {
            throw InteractivePuzzle.refuse("an install carries a position {state, goal:'none', script?}");
        }
        final JsonObject wrapper = new JsonObject();
        wrapper.add(InteractivePuzzle.FIELD, position.deepCopy());
        final InteractivePuzzle.Spec spec = InteractivePuzzle.read(wrapper);
        if (spec.hasGoal()) {
            throw InteractivePuzzle.refuse("an install is a lesson position (goal 'none')");
        }
        final List<String> lines = InteractivePuzzle.lines(spec, humanSeat);
        InteractivePuzzle.checkCards(lines);
        final LessonScript script = spec.script() == null ? LessonScript.parse("rest=bot")
                : InteractivePuzzle.checkedScript(spec.script());
        return new Install(spec, lines, script);
    }

    /** The install the game thread takes at the browser's next priority decision (the one the reader passes). */
    void offer(final Install install) {
        pending.set(install);
    }

    /** The offer taken back when its pass never happened (the request was gone when the pass ran); else nothing. */
    void withdraw(final Install install) {
        pending.compareAndSet(install, null);
    }

    // ------------------------------------------------------------------ the game thread

    @Override
    public void beforeDecision(final Player p) {
        // nothing: the install is taken after the decision that carried it
    }

    /**
     * The only listener here that changes the game, by design: it throws before anything of the decision is acted on
     * (the decision was the pass the install's input made), and {@link #run} installs the position outside the loop.
     */
    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
        if (p != human || chosen != null) {
            return;
        }
        final Install install = pending.getAndSet(null);
        if (install != null) {
            throw new Restart(install);
        }
    }

    /**
     * The game, with installs: {@code Match.startGame} until an install's {@link Restart}, then the install and the main
     * loop again, until the game ends; then the tail {@code GameAction.startGame} and {@code Match.startGame} would have
     * run after their loop.
     */
    void run(final Match match, final Game game, final Runnable startHook) {
        Install next;
        try {
            match.startGame(game, startHook);
            return;
        } catch (Restart r) {
            next = r.install;
        }
        loop(next);
        // GameAction.startGame's and Match.startGame's tail (no ante, no Karn restart in a lesson)
        for (Player p : game.getRegisteredPlayers()) {
            p.setNumCardsInHandStartedThisTurnWith(p.getCardsIn(ZoneType.Hand).size());
            p.getController().autoPassCancel();
        }
        game.clearCaches();
        game.fireEvent(new GameEventGameFinished());
    }

    /**
     * Install {@code first} (null: none, the game as it stands), then the phase handler's main loop, again after each
     * later install, to the game's end.
     */
    void loop(final Install first) {
        Install next = first;
        while (true) {
            if (next != null) {
                install(next);
            }
            try {
                game.getPhaseHandler().mainGameLoop();
                return;
            } catch (Restart r) {
                next = r.install;
            }
        }
    }

    /** The install itself, on the game thread (see the class comment for what it resets). */
    void install(final Install install) {
        final Thread self = Thread.currentThread();
        final String was = self.getName();
        self.setName("Game-lesson-install");
        try {
            final PhaseHandler ph = game.getPhaseHandler();
            // the stack, triggers and every pending "until"/"at" command of the old position
            game.getStack().reset();
            // reset() clears the stack's instances only; their cards stay in the stack zone (review #140 S2-1)
            game.getStackZone().removeAllCards(true);
            // a stack that will not clear fails the install (the session fatal below), never a position over it
            if (!game.getStack().isEmpty() || !game.getStackZone().isEmpty()) {
                throw new IllegalStateException("the stack did not clear");
            }
            game.getTriggerHandler().clearWaitingTriggers();
            game.getTriggerHandler().clearDelayedTrigger();
            game.getTriggerHandler().clearPlayerDefinedDelayedTrigger();
            game.getUntap().clearCommands();
            game.getUpkeep().clearCommands();
            game.getBeginOfCombat().clearCommands();
            game.getEndOfCombat().clearCommands();
            game.getEndOfTurn().clearCommands();
            game.getCleanup().clearCommands();
            ph.endCombat();
            // this turn's and last turn's history, as a fresh game has none
            game.onCleanupPhase();
            game.onCleanupPhase();
            ph.devResetForPosition();
            // the seats
            seat.getAi().getCardMemory().clearAllRemembered();
            seat.load(install.script());
            human.getController().autoPassCancel();
            // life and player counters as a fresh game has them: GameState sets life only when the position names it
            // and player counters only when it names some (review #140 S2-2); raw, so no life or counter trigger runs
            for (Player p : game.getPlayers()) {
                p.setStartingLife(p.getStartingLife());
                if (p.getCounters(forge.game.card.CounterEnumType.RAD) > 0) {
                    p.setCounters(forge.game.card.CounterEnumType.RAD, 0, null, false); // and its radiation effect
                }
                p.clearCounters();
            }
            // the position, installed as a puzzle's is (no goal: a lesson position)
            InteractivePuzzle.position(install.spec(), install.lines(), humanSeat).applyToGame(game);
            InteractivePuzzle.beginCombat(game);
            for (Player p : game.getPlayers()) {
                p.setLifeStartedThisTurnWith(p.getLife());
                p.setNumCardsInHandStartedThisTurnWith(p.getCardsIn(ZoneType.Hand).size());
            }
            game.setAge(GameStage.Play);
            ph.resetPriority();
            ph.onStackResolved();
            installs++;
        } catch (RuntimeException e) {
            // the detail (which may name hidden cards) goes to stderr with the stack, never into the fatal's text
            throw new InteractivePuzzle.Refused("the lesson position could not be installed", e);
        } finally {
            self.setName(was);
        }
        log.println(PREFIX + "{\"n\":" + installs + ",\"turn\":" + game.getPhaseHandler().getTurn() + ",\"phase\":\""
                + game.getPhaseHandler().getPhase() + "\"}");
    }
}
